package com.example.core.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.AuraApplication
import com.example.MainActivity
import com.example.R
import com.example.core.memory.MemoryRepository
import com.example.core.memory.database.AgentTaskEntity
import com.example.core.memory.database.AuraDatabase
import com.example.core.root.RootManager
import com.example.core.settings.AuraPreferences
import com.example.core.tools.AppInventory
import com.example.service.AuraForegroundService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class TaskState {
    QUEUED,
    RUNNING,
    WAITING_USER,
    DONE,
    FAILED,
    CANCELLED
}

data class TaskProgress(
    val taskId: String,
    val goal: String,
    val state: TaskState,
    val currentStep: Int,
    val maxSteps: Int,
    val latestThought: String,
    val result: String = ""
)

/**
 * Service-scoped autonomous task coordinator.
 * Outlives individual voice call sessions, persists task history in Room,
 * informs Live Gemini stream or posts notifications, and coordinates with the silence watchdog.
 */
class TaskManager(
    private val context: Context,
    private val rootManager: RootManager,
    private val preferences: AuraPreferences,
    private val memoryRepository: MemoryRepository,
    private val appInventory: AppInventory,
    private val agentPlanner: AgentPlanner
) {
    companion object {
        private const val TAG = "Aura"
        private const val TASK_NOTIFICATION_CHANNEL = "aura_tasks_channel"
        private const val TASK_NOTIF_ID = 2002

        @Volatile
        private var instance: TaskManager? = null

        fun getInstance(
            context: Context,
            rootManager: RootManager,
            preferences: AuraPreferences,
            memoryRepository: MemoryRepository,
            appInventory: AppInventory,
            agentPlanner: AgentPlanner
        ): TaskManager {
            return instance ?: synchronized(this) {
                instance ?: TaskManager(
                    context.applicationContext,
                    rootManager,
                    preferences,
                    memoryRepository,
                    appInventory,
                    agentPlanner
                ).also { instance = it }
            }
        }

        fun getInstance(context: Context): TaskManager {
            val app = context.applicationContext as? AuraApplication ?: AuraApplication.instance
            return getInstance(
                context = app,
                rootManager = app.rootManager,
                preferences = AuraPreferences(app),
                memoryRepository = MemoryRepository(app),
                appInventory = AppInventory(app, app.rootManager),
                agentPlanner = AgentPlanner(app, app.rootManager, AppInventory(app, app.rootManager))
            )
        }

        fun getInstanceOrNull(): TaskManager? = instance
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val taskDao = AuraDatabase.getInstance(context).agentTaskDao()

    private val runningJobs = ConcurrentHashMap<String, Job>()
    private val activeTasks = ConcurrentHashMap<String, AgentTaskEntity>()

    private val _currentTaskProgress = MutableStateFlow<TaskProgress?>(null)
    val currentTaskProgress: StateFlow<TaskProgress?> = _currentTaskProgress.asStateFlow()

    init {
        createNotificationChannel()
    }

    fun isAnyTaskRunning(): Boolean {
        return activeTasks.values.any { it.state == TaskState.RUNNING.name || it.state == TaskState.WAITING_USER.name }
    }

    /**
     * Starts an autonomous task in background, returns immediately with the generated taskId.
     */
    fun startTaskAsync(goal: String, maxSteps: Int = 25): String {
        val taskId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val entity = AgentTaskEntity(
            id = taskId,
            goal = goal,
            state = TaskState.QUEUED.name,
            stepsJson = "[]",
            result = "Task queued",
            createdAt = now,
            updatedAt = now
        )

        activeTasks[taskId] = entity
        scope.launch {
            taskDao.insertOrUpdate(entity)
        }

        val job = scope.launch {
            executeTaskInternal(taskId, goal, maxSteps)
        }
        runningJobs[taskId] = job

        Log.i(TAG, "TaskManager: Queued background task $taskId: '$goal'")
        return taskId
    }

    fun startTask(goal: String, maxSteps: Int = 25): String = startTaskAsync(goal, maxSteps)

    suspend fun getLatestTask(): AgentTaskEntity? = taskDao.getLatestTask()

    suspend fun getTask(id: String): AgentTaskEntity? = taskDao.getTaskById(id)

    /**
     * Executes task synchronously/blocking (used by run_task tool).
     */
    suspend fun executeTaskBlocking(goal: String, maxSteps: Int = 25): com.example.core.tools.ToolExecutionResult = withContext(Dispatchers.Default) {
        val taskId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val entity = AgentTaskEntity(
            id = taskId,
            goal = goal,
            state = TaskState.QUEUED.name,
            stepsJson = "[]",
            result = "Running",
            createdAt = now,
            updatedAt = now
        )
        activeTasks[taskId] = entity
        taskDao.insertOrUpdate(entity)

        val success = executeTaskInternal(taskId, goal, maxSteps)
        val finalEntity = taskDao.getTaskById(taskId)
        com.example.core.tools.ToolExecutionResult(success, finalEntity?.result ?: "Completed")
    }

    private suspend fun executeTaskInternal(taskId: String, goal: String, maxSteps: Int): Boolean {
        updateTaskState(taskId, TaskState.RUNNING, "Starting autonomous execution")

        val apiKey = preferences.apiKey.first()
        val plannerModel = preferences.plannerModelName.first()
        val useScreenshot = preferences.plannerUseScreenshots.first()
        val inputController = AuraApplication.instance.inputController

        val history = mutableListOf<AgentActionHistory>()
        val stepsJsonArray = JSONArray()

        var taskSuccess = false
        var finalResult = "Completed"

        try {
            for (step in 1..maxSteps) {
                if (!currentCoroutineContext().isActive) {
                    finalResult = "Task was cancelled"
                    break
                }

                // 1. Mark activity so silence watchdog knows an autonomous task step is in progress
                markSessionActivity()

                // 2. Observe screen nodes
                val nodes = ScreenAutomationToolsHelper.getScreenNodes(context, rootManager)

                // 3. Safety check
                val (isSensitive, reason) = agentPlanner.isSensitiveScreen(nodes)
                if (isSensitive) {
                    finalResult = "Safety Pause: $reason requires user interaction."
                    updateTaskState(taskId, TaskState.WAITING_USER, finalResult)
                    break
                }

                // 4. Repeating loop guard
                if (agentPlanner.isRepeatingLoop(history)) {
                    Log.w(TAG, "TaskManager: Loop detected, pressing back to recover...")
                    inputController.pressKey(4, "Back")
                    delay(500L)
                }

                val fgApp = nodes.firstOrNull { it.packageName.isNotBlank() }?.packageName ?: "Unknown"

                // 5. Plan next step
                val planResult = agentPlanner.planNextStep(
                    apiKey = apiKey,
                    modelName = plannerModel,
                    goal = goal,
                    nodes = nodes,
                    foregroundApp = fgApp,
                    history = history,
                    useScreenshot = useScreenshot
                )

                if (planResult.isFailure) {
                    finalResult = "Planning error: ${planResult.exceptionOrNull()?.message}"
                    break
                }

                val decision = planResult.getOrThrow()
                Log.i(TAG, "TaskManager [$taskId] Step $step/$maxSteps: ${decision.action} (${decision.thought})")

                _currentTaskProgress.value = TaskProgress(
                    taskId = taskId,
                    goal = goal,
                    state = TaskState.RUNNING,
                    currentStep = step,
                    maxSteps = maxSteps,
                    latestThought = decision.thought
                )

                val stepLog = JSONObject().apply {
                    put("step", step)
                    put("thought", decision.thought)
                    put("action", decision.action)
                    put("params", JSONObject(decision.params))
                }
                stepsJsonArray.put(stepLog)

                // Notify live voice if active
                notifyTaskStepLive(step, maxSteps, decision)

                // Execute step
                var stepOutput = "Done"
                when (decision.action) {
                    "done" -> {
                        finalResult = decision.params["summary"]?.toString() ?: "Task completed successfully."
                        taskSuccess = true
                        break
                    }
                    "fail" -> {
                        finalResult = decision.params["summary"]?.toString() ?: "Cannot complete task."
                        taskSuccess = false
                        break
                    }
                    "ask_user" -> {
                        finalResult = decision.params["question"]?.toString() ?: "User input requested"
                        updateTaskState(taskId, TaskState.WAITING_USER, finalResult)
                        break
                    }
                    else -> {
                        stepOutput = ScreenAutomationToolsHelper.executeStep(
                            context = context,
                            rootManager = rootManager,
                            appInventory = appInventory,
                            decision = decision,
                            nodes = nodes
                        )
                    }
                }

                history.add(
                    AgentActionHistory(
                        stepIndex = step,
                        action = "${decision.action}: ${decision.thought}",
                        target = decision.params.toString(),
                        result = stepOutput,
                        screenSignature = nodes.take(4).joinToString { it.text + it.bounds }
                    )
                )

                delay(600L) // Wait for UI update
            }
        } catch (ce: CancellationException) {
            finalResult = "Task cancelled by user"
            taskSuccess = false
        } catch (e: Exception) {
            Log.e(TAG, "TaskManager execution error: ${e.message}", e)
            finalResult = "Error: ${e.message}"
            taskSuccess = false
        } finally {
            val finalState = if (taskSuccess) TaskState.DONE else if (finalResult.contains("cancel", ignoreCase = true)) TaskState.CANCELLED else TaskState.FAILED
            updateTaskState(taskId, finalState, finalResult, stepsJsonArray.toString())
            _currentTaskProgress.value = null
            runningJobs.remove(taskId)
            activeTasks.remove(taskId)

            onTaskFinished(taskId, goal, finalState, finalResult)
        }

        return taskSuccess
    }

    fun cancelTask(taskId: String? = null): Boolean {
        val targetId = taskId ?: activeTasks.keys.firstOrNull() ?: return false
        val job = runningJobs[targetId]
        job?.cancel()
        runningJobs.remove(targetId)
        updateTaskState(targetId, TaskState.CANCELLED, "Cancelled by user")
        _currentTaskProgress.value = null
        Log.i(TAG, "TaskManager: Cancelled task $targetId")
        return true
    }

    fun getTaskStatus(taskId: String? = null): String {
        val targetId = taskId ?: activeTasks.keys.firstOrNull()
        if (targetId == null) {
            val p = _currentTaskProgress.value
            return if (p != null) {
                "Active task [${p.taskId}] Step ${p.currentStep}/${p.maxSteps}: ${p.latestThought}"
            } else {
                "No active tasks."
            }
        }
        val entity = activeTasks[targetId]
        return if (entity != null) {
            "Task [${entity.id}] Status: ${entity.state}. Result: ${entity.result}"
        } else {
            runBlocking {
                val past = taskDao.getTaskById(targetId)
                if (past != null) "Task [${past.id}] Status: ${past.state}. Result: ${past.result}"
                else "Task '$targetId' not found."
            }
        }
    }

    private fun updateTaskState(taskId: String, state: TaskState, result: String, stepsJson: String? = null) {
        val now = System.currentTimeMillis()
        val current = activeTasks[taskId]?.copy(state = state.name, result = result, updatedAt = now)
            ?: AgentTaskEntity(id = taskId, goal = "", state = state.name, result = result, createdAt = now, updatedAt = now)

        activeTasks[taskId] = current
        scope.launch {
            if (stepsJson != null) {
                taskDao.insertOrUpdate(current.copy(stepsJson = stepsJson))
            } else {
                taskDao.updateState(taskId, state.name, result, now)
            }
        }
    }

    private fun markSessionActivity() {
        AuraForegroundService.currentService?.liveClient?.markActivityExternal()
    }

    private fun notifyTaskStepLive(step: Int, maxSteps: Int, decision: AgentActionStep) {
        AuraForegroundService.currentService?.let { service ->
            if (AuraForegroundService.isCallActive.value &&
                service.liveClient.connectionState.value == com.example.core.live.LiveConnectionState.Connected
            ) {
                val shortNote = when (decision.action) {
                    "open_app" -> "Opening app"
                    "type_text" -> "Typing text"
                    "tap_text", "tap_index", "tap_xy" -> "Selecting item"
                    "scroll", "swipe" -> "Scrolling"
                    else -> decision.thought.take(30)
                }
                service.liveClient.sendTextMessage(
                    "[INFO — already handled by the app, do not repeat this action.] " +
                            "[SYSTEM EVENT: Step $step of $maxSteps: $shortNote]"
                )
            }
        }
    }

    private fun onTaskFinished(taskId: String, goal: String, state: TaskState, result: String) {
        val isCallActive = AuraForegroundService.isCallActive.value
        val service = AuraForegroundService.currentService

        if (isCallActive && service != null &&
            service.liveClient.connectionState.value == com.example.core.live.LiveConnectionState.Connected
        ) {
            val statusWord = if (state == TaskState.DONE) "Task completed" else "Task finished ($state)"
            service.liveClient.sendTextMessage(
                "[INFO — already handled by the app, do not repeat this action.] " +
                        "[SYSTEM EVENT: $statusWord: '$goal'. Result: '$result']"
            )
        } else {
            // Post high-priority notification to user
            postTaskNotification(goal, state, result)
        }
    }

    private fun postTaskNotification(goal: String, state: TaskState, result: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val openIntent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(context, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val title = if (state == TaskState.DONE) "Task Finished: $goal" else "Task Update: $goal"
        val notif = NotificationCompat.Builder(context, TASK_NOTIFICATION_CHANNEL)
            .setContentTitle(title)
            .setContentText(result)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        nm.notify(TASK_NOTIF_ID, notif)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                TASK_NOTIFICATION_CHANNEL,
                "Aura Background Tasks",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for autonomous task progress and completions"
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }
}

/**
 * Helper to execute individual screen steps without circular dependency.
 */
object ScreenAutomationToolsHelper {
    suspend fun getScreenNodes(context: Context, rootManager: RootManager): List<ScreenNode> {
        val a11y = com.example.service.AuraAccessibilityService.instance
        if (a11y != null) {
            val nodes = a11y.extractScreenNodes()
            val filtered = nodes.filter { it.packageName != context.packageName && it.packageName.isNotBlank() }
            if (filtered.isNotEmpty()) return filtered
            if (nodes.isNotEmpty()) return nodes
        }
        val res = rootManager.execute("uiautomator dump /sdcard/ui_mgr.xml && cat /sdcard/ui_mgr.xml")
        val parsed = ScreenXmlParser.parse(res.stdout)
        val ext = parsed.filter { it.packageName != context.packageName && it.packageName.isNotBlank() }
        return if (ext.isNotEmpty()) ext else parsed
    }

    suspend fun executeStep(
        context: Context,
        rootManager: RootManager,
        appInventory: AppInventory,
        decision: AgentActionStep,
        nodes: List<ScreenNode>
    ): String {
        val input = AuraApplication.instance.inputController
        return when (decision.action) {
            "tap_index" -> {
                val idx = (decision.params["index"] as? Number)?.toInt() ?: 1
                val target = nodes.find { it.index == idx }
                if (target != null) {
                    input.tap(target.centerX.toFloat(), target.centerY.toFloat(), "Tap #$idx")
                    "Tapped #${idx}"
                } else "Node #$idx not found"
            }
            "tap_text" -> {
                val query = decision.params["query"]?.toString() ?: ""
                val target = nodes.find {
                    it.text.contains(query, ignoreCase = true) || it.contentDescription.contains(query, ignoreCase = true)
                }
                if (target != null) {
                    input.tap(target.centerX.toFloat(), target.centerY.toFloat(), "Tap $query")
                    "Tapped '$query'"
                } else "Text '$query' not found"
            }
            "tap_xy" -> {
                val x = (decision.params["x"] as? Number)?.toFloat() ?: 540f
                val y = (decision.params["y"] as? Number)?.toFloat() ?: 1200f
                input.tap(x, y, "Tap ($x, $y)")
                "Tapped ($x, $y)"
            }
            "type_text" -> {
                val text = decision.params["text"]?.toString() ?: ""
                input.typeText(text, "Type text")
                "Typed '$text'"
            }
            "scroll" -> {
                val dir = decision.params["direction"]?.toString() ?: "down"
                input.scroll(dir, 0.5f, "Scroll $dir")
                "Scrolled $dir"
            }
            "swipe" -> {
                val x1 = (decision.params["x1"] as? Number)?.toFloat() ?: 540f
                val y1 = (decision.params["y1"] as? Number)?.toFloat() ?: 1800f
                val x2 = (decision.params["x2"] as? Number)?.toFloat() ?: 540f
                val y2 = (decision.params["y2"] as? Number)?.toFloat() ?: 600f
                input.swipe(x1, y1, x2, y2, 300L, "Swipe")
                "Swiped"
            }
            "back" -> {
                input.pressKey(4, "Back")
                "Back"
            }
            "home" -> {
                input.pressKey(3, "Home")
                "Home"
            }
            "open_app" -> {
                val pkg = decision.params["package_name"]?.toString()
                    ?: decision.params["app_name"]?.toString() ?: ""
                val ok = appInventory.openApp(pkg)
                if (ok) "Opened $pkg" else "Failed opening $pkg"
            }
            "wait" -> {
                val ms = (decision.params["ms"] as? Number)?.toLong() ?: 1000L
                delay(ms)
                "Waited ${ms}ms"
            }
            else -> "Action ${decision.action} executed"
        }
    }
}
