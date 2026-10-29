package com.example.core.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.example.core.agent.*
import com.example.core.memory.MemoryRepository
import com.example.core.memory.database.TaskExecutionEntity
import com.example.core.root.RootManager
import com.example.core.settings.AuraPreferences
import com.example.service.AuraAccessibilityService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

class ScreenAutomationTools(
    private val context: Context,
    private val rootManager: RootManager,
    private val agentPlanner: AgentPlanner,
    private val memoryRepository: MemoryRepository,
    private val preferences: AuraPreferences,
    private val appInventory: AppInventory = AppInventory(context, rootManager)
) {
    private val inputController by lazy { com.example.AuraApplication.instance.inputController }

    companion object {
        private const val TAG = "AuraScreenTools"
    }

    private var activeTaskJob: Job? = null
    @Volatile
    private var isTaskCancelRequested = false

    fun cancelRunningTask() {
        isTaskCancelRequested = true
        activeTaskJob?.cancel()
        activeTaskJob = null
    }

    suspend fun getScreenNodes(): List<ScreenNode> {
        return inputController.withCursorHidden {
            val myPkg = context.packageName
            val a11y = AuraAccessibilityService.instance
            if (a11y != null) {
                val a11yNodes = a11y.extractScreenNodes()
                // Filter out Aura's own overlay, mini-orb, and captions so Maya sees ONLY the target application!
                val externalNodes = a11yNodes.filter { it.packageName != myPkg && it.packageName.isNotBlank() }
                if (externalNodes.isNotEmpty()) {
                    return@withCursorHidden externalNodes
                }
                if (a11yNodes.isNotEmpty()) return@withCursorHidden a11yNodes
            }

            // Fallback to uiautomator dump
            val res = rootManager.execute("uiautomator dump /sdcard/ui_aura.xml && cat /sdcard/ui_aura.xml")
            val parsed = ScreenXmlParser.parse(res.stdout)
            val extParsed = parsed.filter { it.packageName != myPkg && it.packageName.isNotBlank() }
            if (extParsed.isNotEmpty()) extParsed else parsed
        }
    }

    suspend fun waitForUiIdle(maxWaitMs: Long = 1000L, pollIntervalMs: Long = 250L): List<ScreenNode> {
        var lastNodes = getScreenNodes()
        var elapsed = 0L

        while (elapsed < maxWaitMs) {
            delay(pollIntervalMs)
            elapsed += pollIntervalMs
            val currentNodes = getScreenNodes()
            if (currentNodes.size == lastNodes.size &&
                currentNodes.take(5).map { it.text } == lastNodes.take(5).map { it.text }
            ) {
                return currentNodes
            }
            lastNodes = currentNodes
        }
        return lastNodes
    }

    val readScreenTool = object : Tool {
        override val name = "read_screen"
        override val description = "Reads and parses all interactive elements and text from the active screen."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val nodes = getScreenNodes()
            val summary = ScreenXmlParser.toCompactSummary(nodes)
            return ToolExecutionResult(true, summary.ifBlank { "Screen is empty or unable to read hierarchy." })
        }
    }

    val tapElementTool = object : Tool {
        override val name = "tap_element"
        override val description = "Finds an on-screen element by its text or description and taps it."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "query" to mapOf("type" to "string", "description" to "The button text, label, or content-desc.")
            ),
            "required" to listOf("query")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val query = args["query"]?.toString()?.lowercase(Locale.ROOT) ?: return ToolExecutionResult(false, "Missing query")
            val nodes = getScreenNodes()

            val match = nodes.find {
                it.text.lowercase(Locale.ROOT).contains(query) ||
                it.contentDescription.lowercase(Locale.ROOT).contains(query) ||
                it.resourceId.lowercase(Locale.ROOT).contains(query)
            }

            return if (match != null) {
                com.example.AuraApplication.instance.overlayController.dodgeCaptions(match.centerX, match.centerY)
                val targetLabel = match.text.ifEmpty { match.contentDescription.ifEmpty { match.resourceId } }
                val initialNodes = nodes
                val tapRes = inputController.tapResult(match.centerX.toFloat(), match.centerY.toFloat(), "Tap $targetLabel")

                delay(200)
                val nodesAfter = getScreenNodes()
                val screenChanged = nodesAfter.size != initialNodes.size ||
                        nodesAfter.take(5).map { it.text } != initialNodes.take(5).map { it.text }

                if (!screenChanged) {
                    Log.w(TAG, "Screen unchanged after tap on '$targetLabel', attempting direct accessibility click and offset tap...")
                    val a11y = AuraAccessibilityService.instance
                    val a11yNode = a11y?.findNodeByQuery(query)
                    val clicked = a11yNode?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) ?: false
                    if (!clicked) {
                        inputController.tapResult(match.centerX.toFloat() + 4f, match.centerY.toFloat() + 4f, "Retry Tap $targetLabel")
                    }
                }
                ToolExecutionResult(tapRes.success, "Tapped '$targetLabel' via ${tapRes.method} at (${match.centerX}, ${match.centerY})")
            } else {
                ToolExecutionResult(false, "Could not find element matching '$query' on current screen")
            }
        }
    }

    val tapXyTool = object : Tool {
        override val name = "tap_xy"
        override val description = "Taps screen at exact (x, y) coordinates."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "x" to mapOf("type" to "integer", "description" to "X coordinate in pixels."),
                "y" to mapOf("type" to "integer", "description" to "Y coordinate in pixels.")
            ),
            "required" to listOf("x", "y")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val x = (args["x"] as? Number)?.toFloat() ?: return ToolExecutionResult(false, "Missing x")
            val y = (args["y"] as? Number)?.toFloat() ?: return ToolExecutionResult(false, "Missing y")
            com.example.AuraApplication.instance.overlayController.dodgeCaptions(x.toInt(), y.toInt())
            val res = inputController.tapResult(x, y, "Tap ($x, $y)")
            return ToolExecutionResult(res.success, if (res.success) "Tapped at ($x, $y) via ${res.method}" else "Failed to tap ($x, $y): ${res.error}")
        }
    }

    val longPressTool = object : Tool {
        override val name = "long_press"
        override val description = "Performs a long press at (x, y) coordinates."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "x" to mapOf("type" to "integer", "description" to "X coordinate."),
                "y" to mapOf("type" to "integer", "description" to "Y coordinate."),
                "duration_ms" to mapOf("type" to "integer", "description" to "Duration in ms (default 1000).")
            ),
            "required" to listOf("x", "y")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val x = (args["x"] as? Number)?.toFloat() ?: return ToolExecutionResult(false, "Missing x")
            val y = (args["y"] as? Number)?.toFloat() ?: return ToolExecutionResult(false, "Missing y")
            val duration = (args["duration_ms"] as? Number)?.toLong() ?: 1000L
            val res = inputController.longPress(x, y, duration, "Long press")
            return ToolExecutionResult(res, "Long pressed at ($x, $y)")
        }
    }

    val swipeTool = object : Tool {
        override val name = "swipe"
        override val description = "Swipes across the screen in a specified direction ('up', 'down', 'left', 'right') or custom coords."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "direction" to mapOf("type" to "string", "description" to "'up', 'down', 'left', or 'right'.")
            ),
            "required" to listOf("direction")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val dir = args["direction"]?.toString()?.lowercase(Locale.ROOT) ?: "up"
            val res = inputController.swipeDirection(dir, "Swipe $dir")
            return ToolExecutionResult(res, "Swiped $dir")
        }
    }

    val scrollTool = object : Tool {
        override val name = "scroll"
        override val description = "Scrolls the screen down or up."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("direction" to mapOf("type" to "string", "description" to "'down' or 'up' (default 'down')."))
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val dir = args["direction"]?.toString()?.lowercase(Locale.ROOT) ?: "down"
            val res = inputController.scroll(dir, 0.5f, "Scroll $dir")
            return ToolExecutionResult(res, "Scrolled $dir")
        }
    }

    val typeTextTool = object : Tool {
        override val name = "type_text"
        override val description = "Types text into the currently focused input field. Handles Unicode and Hindi."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("text" to mapOf("type" to "string", "description" to "Text to type.")),
            "required" to listOf("text")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val text = args["text"]?.toString() ?: return ToolExecutionResult(false, "Missing text")
            val res = inputController.typeText(text, "Type text")
            return ToolExecutionResult(res, if (res) "Typed: $text" else "Failed to type: $text")
        }
    }

    val typeAndSendMessageTool = object : Tool {
        override val name = "type_and_send_message"
        override val description = "Types a message directly into the current chat conversation field (e.g. WhatsApp, Telegram, SMS) even when keyboard is closed, transforms the voice/mic button into the Send button, and taps Send."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "message" to mapOf("type" to "string", "description" to "The message text to type and send.")
            ),
            "required" to listOf("message")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val message = args["message"]?.toString() ?: return ToolExecutionResult(false, "Missing message")
            val a11y = AuraAccessibilityService.instance
            if (a11y != null) {
                val (ok, detail) = a11y.typeAndSendInCurrentChat(message)
                if (ok) {
                    return ToolExecutionResult(true, detail)
                }
            }

            // Fallback via inputController
            val typed = inputController.typeText(message, "Type chat message")
            delay(250)
            val sendPressed = inputController.pressKey(66, "Press Enter Send")
            return ToolExecutionResult(typed, if (typed) "Typed message into chat and sent" else "Failed to type message")
        }
    }

    val pressKeyTool = object : Tool {
        override val name = "press_key"
        override val description = "Presses a key on device: 'enter', 'back', 'home', 'recents', 'tab', 'delete', 'power'."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("key" to mapOf("type" to "string", "description" to "Key name or keycode.")),
            "required" to listOf("key")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val key = args["key"]?.toString()?.lowercase(Locale.ROOT) ?: "enter"
            val code = when (key) {
                "enter" -> 66
                "back" -> 4
                "home" -> 3
                "recents", "app_switch" -> 187
                "tab" -> 61
                "delete", "backspace" -> 67
                "power" -> 26
                "volume_up" -> 24
                "volume_down" -> 25
                else -> key.toIntOrNull() ?: 66
            }
            val res = inputController.pressKey(code, "Key $key")
            return ToolExecutionResult(res, "Pressed $key")
        }
    }

    val waitTool = object : Tool {
        override val name = "wait"
        override val description = "Waits for a duration in milliseconds to allow UI animation."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("ms" to mapOf("type" to "integer", "description" to "Milliseconds to wait (e.g. 1000).")),
            "required" to listOf("ms")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val ms = (args["ms"] as? Number)?.toLong()?.coerceIn(100, 10000) ?: 1000
            delay(ms)
            return ToolExecutionResult(true, "Waited ${ms}ms")
        }
    }

    val findOnScreenTool = object : Tool {
        override val name = "find_on_screen"
        override val description = "Searches for text on screen and returns matched elements and coordinates."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("query" to mapOf("type" to "string", "description" to "Text to find.")),
            "required" to listOf("query")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val query = args["query"]?.toString()?.lowercase(Locale.ROOT) ?: return ToolExecutionResult(false, "Missing query")
            val nodes = getScreenNodes()
            val matches = nodes.filter {
                it.text.lowercase(Locale.ROOT).contains(query) ||
                it.contentDescription.lowercase(Locale.ROOT).contains(query)
            }

            return if (matches.isNotEmpty()) {
                val formatted = matches.joinToString("\n") {
                    "Found \"${it.text.ifEmpty { it.contentDescription }}\" at (${it.centerX}, ${it.centerY})"
                }
                ToolExecutionResult(true, formatted)
            } else {
                ToolExecutionResult(true, "No match for '$query' on screen")
            }
        }
    }

    val runTaskTool = object : Tool {
        override val name = "run_task"
        override val description = "Executes an autonomous multi-step Android phone task using Gemini observation and planning."
        override val timeoutMs = 120_000L
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "goal" to mapOf("type" to "string", "description" to "The user goal to achieve on the phone."),
                "max_steps" to mapOf("type" to "integer", "description" to "Maximum planning steps (default 20).")
            ),
            "required" to listOf("goal")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val goal = args["goal"]?.toString() ?: return ToolExecutionResult(false, "Missing goal")
            val maxSteps = (args["max_steps"] as? Number)?.toInt()?.coerceIn(1, 40) ?: 20
            val tm = com.example.core.agent.TaskManager.getInstance(context)
            return tm.executeTaskBlocking(goal, maxSteps)
        }
    }

    val startTaskTool = object : Tool {
        override val name = "start_task"
        override val description = "Starts an autonomous screen automation goal in the background so it outlives the current call and survives session disconnects."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "goal" to mapOf("type" to "string", "description" to "Clear description of the goal to accomplish on screen."),
                "max_steps" to mapOf("type" to "integer", "description" to "Maximum number of steps (default 15).")
            ),
            "required" to listOf("goal")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val goal = args["goal"]?.toString() ?: return ToolExecutionResult(false, "Missing goal")
            val maxSteps = (args["max_steps"] as? Number)?.toInt()?.coerceIn(1, 40) ?: 15
            val tm = com.example.core.agent.TaskManager.getInstance(context)
            val taskId = tm.startTask(goal, maxSteps)
            return ToolExecutionResult(true, "Background task started with ID '$taskId'. Maya will perform this task in the background and notify you when complete.")
        }
    }

    val taskStatusTool = object : Tool {
        override val name = "task_status"
        override val description = "Checks the status, current step, and result of a background agent task."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "task_id" to mapOf("type" to "string", "description" to "The ID of the task to check (or leave blank for the most recent task).")
            )
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val tm = com.example.core.agent.TaskManager.getInstance(context)
            val taskId = args["task_id"]?.toString()
            val task = if (taskId.isNullOrBlank()) tm.getLatestTask() else tm.getTask(taskId)
            return if (task != null) {
                val statusDesc = "Task ${task.taskId}: Status=${task.status}, Step=${task.currentStep}/${task.maxSteps}, Goal='${task.goal}', Result='${task.result ?: "in progress"}'"
                ToolExecutionResult(true, statusDesc)
            } else {
                ToolExecutionResult(false, "No task found matching ID: $taskId")
            }
        }
    }

    val cancelTaskTool = object : Tool {
        override val name = "cancel_task"
        override val description = "Cancels a running background screen automation task."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "task_id" to mapOf("type" to "string", "description" to "The ID of the task to cancel.")
            ),
            "required" to listOf("task_id")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val taskId = args["task_id"]?.toString() ?: return ToolExecutionResult(false, "Missing task_id")
            val tm = com.example.core.agent.TaskManager.getInstance(context)
            val cancelled = tm.cancelTask(taskId)
            return ToolExecutionResult(cancelled, if (cancelled) "Task $taskId cancelled successfully" else "Could not find active task $taskId")
        }
    }

    fun getAllTools(): List<Tool> = listOf(
        readScreenTool, tapElementTool, tapXyTool, longPressTool, swipeTool,
        scrollTool, typeTextTool, typeAndSendMessageTool, pressKeyTool, waitTool, findOnScreenTool,
        runTaskTool, startTaskTool, taskStatusTool, cancelTaskTool
    )
}
