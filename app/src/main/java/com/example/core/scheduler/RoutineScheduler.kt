package com.example.core.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.core.memory.database.AuraDatabase
import com.example.core.memory.database.RoutineEntity
import com.example.service.RoutineAlarmReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Manages exact alarm schedules for routines using AlarmManager.
 * Runs routines via TaskManager and restores on system reboot.
 */
class RoutineScheduler(private val context: Context) {

    companion object {
        private const val TAG = "Aura"
        const val EXTRA_ROUTINE_ID = "extra_routine_id"
        const val EXTRA_ROUTINE_NAME = "extra_routine_name"
        const val EXTRA_IS_PROACTIVE_CHECKIN = "extra_is_proactive_checkin"
        const val ACTION_EXECUTE_ROUTINE = "com.example.aura.ACTION_EXECUTE_ROUTINE"
    }

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val database = AuraDatabase.getInstance(context)

    /**
     * Re-registers all active routines with scheduledTime on boot or app init.
     */
    suspend fun scheduleAllActiveRoutines() = withContext(Dispatchers.IO) {
        try {
            val routines = database.routineDao().getActiveRoutinesSync()
            Log.i(TAG, "RoutineScheduler: Rescheduling ${routines.size} active routines")
            for (routine in routines) {
                if (routine.scheduledTime.isNotBlank()) {
                    scheduleRoutine(routine)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "RoutineScheduler: Error rescheduling routines: ${e.message}", e)
        }
    }

    /**
     * Schedules a single routine with an exact alarm at its scheduledTime ("HH:mm").
     */
    fun scheduleRoutine(routine: RoutineEntity) {
        val timeParts = routine.scheduledTime.split(":")
        if (timeParts.size != 2) return

        val hour = timeParts[0].trim().toIntOrNull() ?: return
        val minute = timeParts[1].trim().toIntOrNull() ?: return

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(Calendar.getInstance())) {
                add(Calendar.DAY_OF_YEAR, 1) // Next occurrence tomorrow
            }
        }

        val intent = Intent(context, RoutineAlarmReceiver::class.java).apply {
            action = ACTION_EXECUTE_ROUTINE
            putExtra(EXTRA_ROUTINE_ID, routine.id)
            putExtra(EXTRA_ROUTINE_NAME, routine.name)
        }

        val pi = PendingIntent.getBroadcast(
            context,
            routine.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerMs = calendar.timeInMillis
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerMs, pi)
            }
            Log.i(TAG, "RoutineScheduler: Scheduled exact alarm for '${routine.name}' at ${routine.scheduledTime} (in ${(triggerMs - System.currentTimeMillis()) / 60000}m)")
        } catch (e: Exception) {
            Log.e(TAG, "RoutineScheduler: Failed to set exact alarm: ${e.message}", e)
        }
    }

    /**
     * Cancels scheduled alarm for a routine.
     */
    fun cancelRoutine(routineId: Long) {
        val intent = Intent(context, RoutineAlarmReceiver::class.java).apply {
            action = ACTION_EXECUTE_ROUTINE
        }
        val pi = PendingIntent.getBroadcast(
            context,
            routineId.toInt(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) {
            alarmManager.cancel(pi)
            pi.cancel()
            Log.i(TAG, "RoutineScheduler: Cancelled routine alarm for id $routineId")
        }
    }
}
