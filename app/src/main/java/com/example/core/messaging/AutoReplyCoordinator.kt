package com.example.core.messaging

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.core.settings.AuraPreferences
import com.example.service.AuraForegroundService
import com.example.service.AuraNotificationListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton coordinator for handling automated replies to incoming chat notifications.
 * Ensures robust per-conversation locking, deduplication, echo suppression, and cooldowns.
 */
class AutoReplyCoordinator private constructor(
    private val context: Context,
    private val preferences: AuraPreferences
) {
    companion object {
        private const val TAG = "Aura"
        private const val POST_REPLY_IGNORE_WINDOW_MS = 20_000L // Ignore chat updates for 20s after replying

        @Volatile
        private var instance: AutoReplyCoordinator? = null

        fun getInstance(context: Context, preferences: AuraPreferences): AutoReplyCoordinator {
            return instance ?: synchronized(this) {
                instance ?: AutoReplyCoordinator(context.applicationContext, preferences).also {
                    instance = it
                }
            }
        }

        fun getInstanceOrNull(): AutoReplyCoordinator? = instance
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Per-conversation mutex to prevent parallel processing of rapid updates
    private val conversationLocks = ConcurrentHashMap<String, Mutex>()

    // Tracking state per conversationKey
    private val lastHandledMessageTimestamp = ConcurrentHashMap<String, Long>()
    private val lastAutoReplySentTimestamp = ConcurrentHashMap<String, Long>()
    private val ignoreUntilTimestamp = ConcurrentHashMap<String, Long>()
    private val lastOurReplyText = ConcurrentHashMap<String, String>()

    fun handleNotification(sbn: StatusBarNotification) {
        val notif = sbn.notification ?: return
        val pkg = sbn.packageName ?: return

        scope.launch {
            try {
                val isBusy = preferences.notifAutoReplyBusy.first()
                if (!isBusy) return@launch

                val allowedPackages = preferences.autoReplyPackages.first()
                val isPkgAllowed = allowedPackages.contains(pkg) ||
                        allowedPackages.any { pkg.contains(it, ignoreCase = true) }
                if (!isPkgAllowed) return@launch

                // Extract notification properties & MessagingStyle if available
                val extras = notif.extras ?: return@launch
                val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notif)

                val isGroupSummary = (notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0
                val isOngoing = sbn.isOngoing

                var sender: String? = null
                var messageText: String = ""
                var messageTimestamp: Long = sbn.postTime
                var isSenderSelf = false
                var isGroup = false

                if (messagingStyle != null) {
                    isGroup = messagingStyle.isGroupConversation
                    val messages = messagingStyle.messages
                    if (messages.isNotEmpty()) {
                        val newest = messages.last()
                        messageText = newest.text?.toString() ?: ""
                        sender = newest.person?.name?.toString()
                        messageTimestamp = newest.timestamp
                        val selfPerson = messagingStyle.user
                        if (newest.person == null || (selfPerson != null && newest.person?.key == selfPerson.key)) {
                            isSenderSelf = true
                        }
                    }
                }

                if (messageText.isBlank()) {
                    messageText = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                        ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
                }
                if (sender.isNullOrBlank()) {
                    sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                }

                // Identify conversation key
                val shortcutId = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    notif.shortcutId
                } else null
                val conversationKey = "$pkg|" + (shortcutId ?: sender ?: "unknown")

                val mutex = conversationLocks.computeIfAbsent(conversationKey) { Mutex() }
                mutex.withLock {
                    val now = System.currentTimeMillis()
                    val cooldownMin = preferences.autoReplyCooldownMinutes.first()
                    val allowGroups = preferences.autoReplyGroups.first()
                    val ourReplyMsg = preferences.notifAutoReplyMessage.first().trim()

                    val ctx = AutoReplyDecider.NotificationContext(
                        packageName = pkg,
                        conversationKey = conversationKey,
                        text = messageText,
                        sender = sender,
                        isSenderSelf = isSenderSelf,
                        isGroup = isGroup,
                        isGroupSummary = isGroupSummary,
                        isOngoing = isOngoing,
                        messageTimestamp = messageTimestamp,
                        lastOurReplyText = lastOurReplyText[conversationKey] ?: "",
                        lastHandledMessageTimestamp = lastHandledMessageTimestamp[conversationKey] ?: 0L,
                        lastAutoReplySentTimestamp = lastAutoReplySentTimestamp[conversationKey] ?: 0L,
                        ignoreConversationUntilTimestamp = ignoreUntilTimestamp[conversationKey] ?: 0L,
                        isAutoReplyEnabled = isBusy,
                        isPackageAllowed = isPkgAllowed,
                        isGroupsAllowed = allowGroups,
                        cooldownMinutes = cooldownMin,
                        currentTimeMs = now
                    )

                    when (val decision = AutoReplyDecider.evaluate(ctx)) {
                        is AutoReplyDecider.Decision.Skip -> {
                            Log.i(TAG, "AutoReplyCoordinator: SKIP for '$conversationKey': ${decision.reason}")
                            return@withLock
                        }
                        is AutoReplyDecider.Decision.Allow -> {
                            // Deduplicate against global outbound message ledger
                            val cleanMsg = ourReplyMsg.ifBlank { "Mai busy hu, baad me text karta hu." }
                            val ledgerKey = OutboundMessageLedger.computeKey(conversationKey, cleanMsg)
                            if (!OutboundMessageLedger.canSend(conversationKey, cleanMsg)) {
                                Log.i(TAG, "AutoReplyCoordinator: SKIP for '$conversationKey': blocked by global OutboundMessageLedger")
                                return@withLock
                            }

                            Log.i(TAG, "AutoReplyCoordinator: SENDING auto-reply to '$conversationKey' on $pkg: '$cleanMsg'")

                            // Attempt inline reply via AuraNotificationListener
                            val replied = AuraNotificationListener.replyToNotification(sbn.key, cleanMsg)
                            if (replied) {
                                // Record success in ledger and local tracker
                                OutboundMessageLedger.recordSent(conversationKey, cleanMsg)
                                lastHandledMessageTimestamp[conversationKey] = messageTimestamp
                                lastAutoReplySentTimestamp[conversationKey] = now
                                ignoreUntilTimestamp[conversationKey] = now + POST_REPLY_IGNORE_WINDOW_MS
                                lastOurReplyText[conversationKey] = cleanMsg

                                Log.i(TAG, "AutoReplyCoordinator: SENT inline auto-reply successfully to '$conversationKey' on $pkg. Muting updates for 20s.")

                                // Notify Live agent stream with INFO prefix so model does not repeat
                                AuraForegroundService.currentService?.let { service ->
                                    if (AuraForegroundService.isCallActive.value &&
                                        service.liveClient.connectionState.value == com.example.core.live.LiveConnectionState.Connected
                                    ) {
                                        service.liveClient.sendTextMessage(
                                            "[INFO — already handled by the app, do not repeat this action.] " +
                                                    "[SYSTEM EVENT: Auto-replied to message from $sender on $pkg: \"$cleanMsg\"]"
                                        )
                                    }
                                }
                            } else {
                                // Explicitly do NOT open WhatsApp as a second path per specification!
                                Log.w(TAG, "AutoReplyCoordinator: FAILED to send inline reply to notification '${sbn.key}'. Logging failure without launching external app fallback.")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "AutoReplyCoordinator: Exception handling notification: ${e.message}", e)
            }
        }
    }
}
