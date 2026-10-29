package com.example.core.telephony

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Data model for a unified phone or VoIP incoming call session.
 */
data class CallSession(
    val id: String,
    val startedAt: Long,
    var number: String?,
    var name: String?,
    val source: String, // "TELEPHONY", "PHONE_STATE", "CALL_SCREENING", "VOIP"
    var isVoip: Boolean = false,
    val handled: AtomicBoolean = AtomicBoolean(false)
) {
    val callerName: String? get() = name
}

/**
 * Pure state manager for call sessions. Deduplicates multiple incoming signals
 * (TelephonyCallback, PHONE_STATE broadcast, CallScreeningService, NotificationListener)
 * into a single unified call session.
 */
class CallSessionTracker(
    private val cooldownMs: Long = 60_000L
) {
    companion object {
        private const val TAG = "Aura"
        val instance = CallSessionTracker()

        fun onRinging(number: String?, name: String? = null, app: String = "Phone", isVoip: Boolean = false): CallSession {
            val (session, _) = instance.onCallRinging(number, name, app, isVoip)
            return session
        }

        fun onNumberDiscovered(number: String): CallSession {
            val (session, _) = instance.onCallRinging(number = number, name = null, source = "PHONE_STATE", isVoip = false)
            return session
        }

        fun onScreeningDisallowed(number: String): CallSession {
            val (session, _) = instance.onCallRinging(number = number, name = null, source = "CALL_SCREENING", isVoip = false)
            return session
        }

        fun onVoipNotification(appName: String, callerName: String): CallSession {
            val (session, _) = instance.onCallRinging(number = null, name = callerName, source = appName, isVoip = true)
            return session
        }

        fun onCallEnded(delayCloseMs: Long = 5000L): CallSession? {
            return instance.onCallEnded(delayCloseMs)
        }

        fun getActiveSession(): CallSession? = instance.getActiveSession()

        private fun logI(tag: String, msg: String) {
            try {
                android.util.Log.i(tag, msg)
            } catch (_: Throwable) {
                println("[$tag] $msg")
            }
        }
    }

    private var activeSession: CallSession? = null
    private val numberCooldowns = ConcurrentHashMap<String, Long>()

    @Synchronized
    fun onCallRinging(
        number: String?,
        name: String?,
        source: String,
        isVoip: Boolean = false
    ): Pair<CallSession, Boolean> {
        val now = System.currentTimeMillis()
        val existing = activeSession

        if (existing != null && (now - existing.startedAt) < 45_000L) {
            // Merge into active session
            if (existing.number.isNullOrBlank() && !number.isNullOrBlank()) {
                existing.number = number
                logI(TAG, "CallSessionTracker: Enriched active session ${existing.id} with number: $number from $source")
            }
            if (existing.name.isNullOrBlank() && !name.isNullOrBlank()) {
                existing.name = name
                logI(TAG, "CallSessionTracker: Enriched active session ${existing.id} with name: $name from $source")
            }
            logI(TAG, "CallSessionTracker: Merged ringing signal from $source into ongoing session ${existing.id}")
            return Pair(existing, false) // Not a new session
        }

        // Clean up and start new session
        val sessionId = "call_${now}_${(1000..9999).random()}"
        val newSession = CallSession(
            id = sessionId,
            startedAt = now,
            number = number?.trim()?.ifBlank { null },
            name = name?.trim()?.ifBlank { null },
            source = source,
            isVoip = isVoip
        )
        activeSession = newSession

        logI(TAG, "CallSessionTracker: Created new call session $sessionId (source=$source, number=$number, name=$name, isVoip=$isVoip)")
        return Pair(newSession, true)
    }

    @Synchronized
    fun onCallEnded(delayCloseMs: Long = 5000L): CallSession? {
        val current = activeSession
        if (current != null) {
            logI(TAG, "CallSessionTracker: Call ended for session ${current.id}. Will close after ${delayCloseMs}ms.")
        }
        return current
    }

    @Synchronized
    fun closeActiveSession() {
        activeSession?.let {
            logI(TAG, "CallSessionTracker: Closed call session ${it.id}")
        }
        activeSession = null
    }

    @Synchronized
    fun getActiveSession(): CallSession? = activeSession

    /**
     * Checks per-number auto-reject cooldown (60 seconds).
     * Returns true if allowed to send reject message, false if on cooldown.
     */
    fun checkAndRecordCooldown(numberOrKey: String, now: Long = System.currentTimeMillis()): Boolean {
        val clean = numberOrKey.replace(Regex("""[^\d]"""), "").ifBlank { numberOrKey.trim() }
        if (clean.isBlank()) return true

        val lastSent = numberCooldowns[clean] ?: 0L
        if (now - lastSent < cooldownMs) {
            val remainSec = (cooldownMs - (now - lastSent)) / 1000
            logI(TAG, "CallSessionTracker: BLOCKED reject reply to '$numberOrKey' (cooldown active: ${remainSec}s remaining)")
            return false
        }
        numberCooldowns[clean] = now
        return true
    }

    fun clearForTesting() {
        activeSession = null
        numberCooldowns.clear()
    }
}
