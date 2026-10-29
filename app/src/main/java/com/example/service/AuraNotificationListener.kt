package com.example.service

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.core.telephony.CallAnnouncementManager
import java.util.concurrent.ConcurrentHashMap

data class CachedNotification(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val sbn: StatusBarNotification
)

class AuraNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "Aura"
        private var instance: AuraNotificationListener? = null
        private val notificationCache = ConcurrentHashMap<String, CachedNotification>()

        fun isRunning(): Boolean = instance != null

        fun getActiveNotificationsCached(): List<CachedNotification> {
            return notificationCache.values.sortedByDescending { it.postTime }
        }

        fun replyToNotification(queryOrKey: String, message: String): Boolean {
            val inst = instance ?: return false
            val cleanMsg = message.trim().removeSuffix("/").removeSuffix("/?").trim()
            val item = notificationCache[queryOrKey] ?: run {
                val q = queryOrKey.trim().lowercase(java.util.Locale.ROOT)
                notificationCache.values
                    .sortedByDescending { it.postTime }
                    .firstOrNull { cached ->
                        cached.key == queryOrKey ||
                        cached.packageName.lowercase(java.util.Locale.ROOT).contains(q) ||
                        cached.appName.lowercase(java.util.Locale.ROOT).contains(q) ||
                        cached.title.lowercase(java.util.Locale.ROOT).contains(q) ||
                        cached.text.lowercase(java.util.Locale.ROOT).contains(q)
                    }
            } ?: return false
            val notif = item.sbn.notification ?: return false

            val actions = notif.actions ?: return false
            for (action in actions) {
                val remoteInputs = action.remoteInputs ?: continue
                for (remoteInput in remoteInputs) {
                    val bundle = Bundle().apply {
                        putCharSequence(remoteInput.resultKey, cleanMsg)
                    }
                    val intent = Intent()
                    RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, bundle)
                    try {
                        action.actionIntent.send(inst, 0, intent)
                        return true
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send notification reply", e)
                        return false
                    }
                }
            }
            return false
        }

        fun dismissNotification(key: String): Boolean {
            val inst = instance ?: return false
            return try {
                inst.cancelNotification(key)
                notificationCache.remove(key)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dismiss notification $key", e)
                false
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        refreshNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        notificationCache.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        cacheSbn(sbn)
        checkVoipIncomingCall(sbn)

        // Route to AutoReplyCoordinator for independent chat auto-replies
        val prefs = com.example.core.settings.AuraPreferences(this)
        com.example.core.messaging.AutoReplyCoordinator.getInstance(this, prefs).handleNotification(sbn)

        // Route to NotificationAnnouncementManager for voice announcements
        val manager = com.example.core.telephony.NotificationAnnouncementManager.instance
        manager?.processNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        notificationCache.remove(sbn.key)
    }

    private fun checkVoipIncomingCall(sbn: StatusBarNotification) {
        try {
            val notif = sbn.notification ?: return
            val pkg = sbn.packageName?.lowercase(java.util.Locale.ROOT) ?: ""

            // Strictly ignore cellular dialer/telecom/incallui packages
            if (pkg.contains("dialer") || pkg.contains("telecom") || pkg.contains("incallui") || pkg.contains("phone")) {
                return
            }

            // Treat as VoIP call ONLY when category is CATEGORY_CALL AND has answer/decline actions or fullScreenIntent
            val isCallCategory = notif.category == Notification.CATEGORY_CALL
            val hasActions = notif.actions != null && notif.actions.any { action ->
                val title = action.title?.toString()?.lowercase(java.util.Locale.ROOT) ?: ""
                title.contains("answer") || title.contains("accept") || title.contains("decline") ||
                        title.contains("reject") || title.contains("उठाएं") || title.contains("काटें")
            }
            val hasFullScreen = notif.fullScreenIntent != null

            if (isCallCategory && (hasActions || hasFullScreen)) {
                val extras = notif.extras ?: return
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

                val appLabel = when {
                    pkg.contains("whatsapp") -> "WhatsApp"
                    pkg.contains("telegram") -> "Telegram"
                    else -> {
                        try {
                            val pm = packageManager
                            val ai = pm.getApplicationInfo(pkg, 0)
                            pm.getApplicationLabel(ai).toString()
                        } catch (e: Exception) {
                            "VoIP Call"
                        }
                    }
                }
                var caller = if (title.isNotBlank() && !title.contains("call", ignoreCase = true)) {
                    title
                } else if (text.isNotBlank() && !text.contains("call", ignoreCase = true)) {
                    text
                } else {
                    title.ifBlank { text }
                }
                caller = caller.replace(Regex("""(?i)^(whatsapp\s+call\s+from\s+|incoming\s+call\s+from\s+|call\s+from\s+)"""), "").trim()
                if (caller.isBlank()) caller = "Unknown"

                Log.i(TAG, "AuraNotificationListener: Confirmed VoIP incoming call notification from $appLabel ($caller)")
                CallAnnouncementManager.instance?.onVoipCallDetected(appLabel, caller)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking VoIP incoming call notification", e)
        }
    }

    private fun refreshNotifications() {
        try {
            val active = activeNotifications ?: return
            notificationCache.clear()
            for (sbn in active) {
                cacheSbn(sbn)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh active notifications", e)
        }
    }

    private fun cacheSbn(sbn: StatusBarNotification) {
        val notif = sbn.notification ?: return
        val extras = notif.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val pkg = sbn.packageName ?: ""
        val appName = try {
            val pm = packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get app label for $pkg", e)
            pkg
        }

        if (title.isNotBlank() || text.isNotBlank()) {
            notificationCache[sbn.key] = CachedNotification(
                key = sbn.key,
                packageName = pkg,
                appName = appName,
                title = title,
                text = text,
                postTime = sbn.postTime,
                sbn = sbn
            )
        }
    }
}
