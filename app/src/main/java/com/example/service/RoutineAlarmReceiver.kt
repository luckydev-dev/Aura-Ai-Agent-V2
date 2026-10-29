package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.core.agent.TaskManager
import com.example.core.memory.MemoryRepository
import com.example.core.memory.database.AuraDatabase
import com.example.core.root.RootManager
import com.example.core.scheduler.RoutineScheduler
import com.example.core.settings.AuraPreferences
import com.example.core.tools.AppInventory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * BroadcastReceiver triggered by AlarmManager exact alarms to run scheduled routines via TaskManager.
 */
class RoutineAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "Aura"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val routineId = intent.getLongExtra(RoutineScheduler.EXTRA_ROUTINE_ID, -1L)
        val routineName = intent.getStringExtra(RoutineScheduler.EXTRA_ROUTINE_NAME) ?: "Scheduled Routine"

        Log.i(TAG, "RoutineAlarmReceiver: Triggered for routine '$routineName' (id=$routineId)")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AuraDatabase.getInstance(context)
                val routines = db.routineDao().getActiveRoutinesSync()
                val targetRoutine = routines.firstOrNull { it.id == routineId } ?: run {
                    db.routineDao().findByName(routineName)
                }

                if (targetRoutine != null && targetRoutine.isEnabled) {
                    val rootManager = RootManager(context)
                    val prefs = AuraPreferences(context)
                    val memRepo = MemoryRepository(context)
                    val appInv = AppInventory(context, rootManager)
                    val planner = com.example.core.agent.AgentPlanner(context, rootManager, appInv)
                    val taskManager = TaskManager.getInstance(context, rootManager, prefs, memRepo, appInv, planner)

                    val goal = "Execute routine: ${targetRoutine.name}. Steps: ${targetRoutine.stepsJson}"
                    taskManager.startTaskAsync(goal)

                    // Re-schedule for next day
                    RoutineScheduler(context).scheduleRoutine(targetRoutine)
                }
            } catch (e: Exception) {
                Log.e(TAG, "RoutineAlarmReceiver: Failed executing routine: ${e.message}", e)
            }
        }
    }
}
