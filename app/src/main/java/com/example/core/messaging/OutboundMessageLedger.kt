package com.example.core.messaging

import android.util.Log
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe singleton ledger tracking all outgoing messages across all channels
 * (WhatsApp, SMS, notification inline reply, auto-reply, call rejection reply).
 *
 * Prevents duplicate messages to the same recipient with identical normalized content
 * within a 90-second cooldown window.
 */
object OutboundMessageLedger {
    private const val TAG = "Aura"
    private const val DEDUPE_WINDOW_MS = 90_000L // 90 seconds

    // Maps composite key to timestamp of successful dispatch
    private val sentLog = ConcurrentHashMap<String, Long>()

    /**
     * Computes the normalized deduplication key for a recipient and text payload.
     */
    fun computeKey(recipient: String, text: String): String {
        val normRecipient = normalizeRecipient(recipient)
        val normText = normalizeText(text)
        val hash = sha256(normText)
        return "$normRecipient|$hash"
    }

    /**
     * Checks whether a message can be sent to [recipient] with [text].
     * If recently sent within 90s, returns false and logs decision.
     */
    fun canSend(recipient: String, text: String): Boolean {
        cleanupExpired()
        val key = computeKey(recipient, text)
        val now = System.currentTimeMillis()
        val lastSent = sentLog[key]

        return if (lastSent != null && (now - lastSent) < DEDUPE_WINDOW_MS) {
            val remainingSec = ((DEDUPE_WINDOW_MS - (now - lastSent)) / 1000).coerceAtLeast(1)
            Log.i(TAG, "OutboundMessageLedger: BLOCKED duplicate send to '$recipient' (cooldown active: ${remainingSec}s remaining). Text preview: '${text.take(30)}'")
            false
        } else {
            true
        }
    }

    /**
     * Atomically records a message dispatch if not already recorded within cooldown.
     * Returns true if successfully claimed (allowed to send), false if blocked as duplicate.
     */
    fun checkAndRecord(recipient: String, text: String): Boolean {
        cleanupExpired()
        val key = computeKey(recipient, text)
        val now = System.currentTimeMillis()

        synchronized(this) {
            val lastSent = sentLog[key]
            if (lastSent != null && (now - lastSent) < DEDUPE_WINDOW_MS) {
                val remainingSec = ((DEDUPE_WINDOW_MS - (now - lastSent)) / 1000).coerceAtLeast(1)
                Log.i(TAG, "OutboundMessageLedger: BLOCKED duplicate send to '$recipient' (cooldown active: ${remainingSec}s remaining).")
                return false
            }
            sentLog[key] = now
            Log.i(TAG, "OutboundMessageLedger: ALLOWED send to '$recipient' (key recorded for 90s dedupe).")
            return true
        }
    }

    /**
     * Alias for checkAndRecord.
     */
    fun recordAndCheckAllowed(recipient: String, text: String): Boolean = checkAndRecord(recipient, text)

    /**
     * Manually records that a message was successfully sent to [recipient].
     */
    fun recordSent(recipient: String, text: String) {
        val key = computeKey(recipient, text)
        sentLog[key] = System.currentTimeMillis()
        Log.i(TAG, "OutboundMessageLedger: RECORDED send to '$recipient'.")
    }

    /**
     * Resets entry for a recipient and text (e.g., if delivery completely failed).
     */
    fun removeEntry(recipient: String, text: String) {
        val key = computeKey(recipient, text)
        sentLog.remove(key)
        Log.i(TAG, "OutboundMessageLedger: CLEARED entry for '$recipient' after delivery failure.")
    }

    /**
     * Clears all ledger entries (useful for test resets).
     */
    fun clearForTesting() {
        sentLog.clear()
    }

    fun normalizeRecipient(raw: String): String {
        val digits = raw.replace(Regex("""[^\d]"""), "")
        return if (digits.length >= 10) {
            digits.takeLast(10)
        } else {
            raw.trim().lowercase(Locale.ROOT)
        }
    }

    fun normalizeText(raw: String): String {
        return raw.trim()
            .removeSuffix("/?")
            .removeSuffix("/")
            .replace(Regex("""\s+"""), " ")
            .lowercase(Locale.ROOT)
    }

    private fun sha256(input: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            input.hashCode().toString()
        }
    }

    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        if (sentLog.size > 100) {
            val it = sentLog.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                if (now - entry.value > DEDUPE_WINDOW_MS) {
                    it.remove()
                }
            }
        }
    }
}
