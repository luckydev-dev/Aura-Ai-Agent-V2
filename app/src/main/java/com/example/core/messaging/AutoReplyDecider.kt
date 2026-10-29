package com.example.core.messaging

/**
 * Pure Kotlin decision engine for notification auto-reply.
 * Has ZERO Android framework dependencies, making it directly testable in unit tests.
 */
object AutoReplyDecider {

    sealed class Decision {
        data class Allow(val reason: String) : Decision()
        data class Skip(val reason: String) : Decision()
    }

    data class NotificationContext(
        val packageName: String,
        val conversationKey: String,
        val text: String,
        val sender: String?,
        val isSenderSelf: Boolean,
        val isGroup: Boolean,
        val isGroupSummary: Boolean,
        val isOngoing: Boolean,
        val messageTimestamp: Long,
        val lastOurReplyText: String,
        val lastHandledMessageTimestamp: Long,
        val lastAutoReplySentTimestamp: Long,
        val ignoreConversationUntilTimestamp: Long,
        val isAutoReplyEnabled: Boolean,
        val isPackageAllowed: Boolean,
        val isGroupsAllowed: Boolean,
        val cooldownMinutes: Int,
        val currentTimeMs: Long
    )

    fun evaluate(context: NotificationContext): Decision {
        if (!context.isAutoReplyEnabled) {
            return Decision.Skip("Auto-reply is globally disabled in preferences")
        }

        if (!context.isPackageAllowed) {
            return Decision.Skip("Package '${context.packageName}' is not in auto-reply packages list")
        }

        if (context.isOngoing) {
            return Decision.Skip("Notification is ongoing")
        }

        if (context.isGroupSummary) {
            return Decision.Skip("Notification is a group summary header")
        }

        val cleanText = context.text.trim()
        if (cleanText.isBlank()) {
            return Decision.Skip("Message text is blank")
        }

        // Echo guard: message text equals our own reply text
        val cleanOurReply = context.lastOurReplyText.trim()
        if (cleanOurReply.isNotBlank() && cleanText.equals(cleanOurReply, ignoreCase = true)) {
            return Decision.Skip("Echo guard: incoming message matches our own previous reply text")
        }

        if (context.isSenderSelf) {
            return Decision.Skip("Newest sender is self or null")
        }

        if (context.isGroup && !context.isGroupsAllowed) {
            return Decision.Skip("Group chat auto-reply is disabled in preferences")
        }

        // Check if conversation is temporarily ignored (e.g. 20s immediately following our reply)
        if (context.currentTimeMs < context.ignoreConversationUntilTimestamp) {
            val remainSec = (context.ignoreConversationUntilTimestamp - context.currentTimeMs) / 1000
            return Decision.Skip("Conversation is muted for ${remainSec}s post-reply")
        }

        // Ensure this is a newer message than the one we last processed
        if (context.messageTimestamp > 0L && context.messageTimestamp <= context.lastHandledMessageTimestamp) {
            return Decision.Skip("Message timestamp (${context.messageTimestamp}) is not newer than last handled (${context.lastHandledMessageTimestamp})")
        }

        // Per-conversation cooldown check
        val cooldownMs = context.cooldownMinutes.coerceAtLeast(1) * 60 * 1000L
        if (context.lastAutoReplySentTimestamp > 0L) {
            val elapsed = context.currentTimeMs - context.lastAutoReplySentTimestamp
            if (elapsed < cooldownMs) {
                val remainingMin = ((cooldownMs - elapsed) / 60000L).coerceAtLeast(1)
                return Decision.Skip("Conversation cooldown active (${remainingMin}m remaining)")
            }
        }

        return Decision.Allow("Eligible for auto-reply")
    }
}
