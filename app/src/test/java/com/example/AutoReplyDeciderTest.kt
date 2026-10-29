package com.example

import com.example.core.messaging.AutoReplyDecider
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoReplyDeciderTest {

    private fun createBaseContext(
        packageName: String = "com.whatsapp",
        text: String = "Hi, are you free?",
        sender: String? = "Rahul",
        isSenderSelf: Boolean = false,
        isGroup: Boolean = false,
        isGroupSummary: Boolean = false,
        isOngoing: Boolean = false,
        messageTimestamp: Long = 1000L,
        lastOurReplyText: String = "",
        lastHandledMessageTimestamp: Long = 0L,
        lastAutoReplySentTimestamp: Long = 0L,
        ignoreConversationUntilTimestamp: Long = 0L,
        isAutoReplyEnabled: Boolean = true,
        isPackageAllowed: Boolean = true,
        isGroupsAllowed: Boolean = false,
        cooldownMinutes: Int = 10,
        currentTimeMs: Long = 2000L
    ) = AutoReplyDecider.NotificationContext(
        packageName = packageName,
        conversationKey = "$packageName|$sender",
        text = text,
        sender = sender,
        isSenderSelf = isSenderSelf,
        isGroup = isGroup,
        isGroupSummary = isGroupSummary,
        isOngoing = isOngoing,
        messageTimestamp = messageTimestamp,
        lastOurReplyText = lastOurReplyText,
        lastHandledMessageTimestamp = lastHandledMessageTimestamp,
        lastAutoReplySentTimestamp = lastAutoReplySentTimestamp,
        ignoreConversationUntilTimestamp = ignoreConversationUntilTimestamp,
        isAutoReplyEnabled = isAutoReplyEnabled,
        isPackageAllowed = isPackageAllowed,
        isGroupsAllowed = isGroupsAllowed,
        cooldownMinutes = cooldownMinutes,
        currentTimeMs = currentTimeMs
    )

    @Test
    fun testEligibleMessageAllowed() {
        val ctx = createBaseContext()
        val decision = AutoReplyDecider.evaluate(ctx)
        assertTrue("Eligible message should be Allowed", decision is AutoReplyDecider.Decision.Allow)
    }

    @Test
    fun testGroupMessageIgnoredWhenGroupsDisabled() {
        val ctx = createBaseContext(isGroup = true, isGroupsAllowed = false)
        val decision = AutoReplyDecider.evaluate(ctx)
        assertTrue("Group message should be skipped when groups disabled", decision is AutoReplyDecider.Decision.Skip)
    }

    @Test
    fun testEchoGuardIgnored() {
        val ctx = createBaseContext(
            text = "Mai abhi busy hu, baad me text karta hu.",
            lastOurReplyText = "Mai abhi busy hu, baad me text karta hu."
        )
        val decision = AutoReplyDecider.evaluate(ctx)
        assertTrue("Echo of own reply text should be skipped", decision is AutoReplyDecider.Decision.Skip)
    }

    @Test
    fun testCooldownRespected() {
        // Last auto-reply sent 1 minute ago, cooldown is 10 minutes
        val ctx = createBaseContext(
            lastAutoReplySentTimestamp = 1000L,
            currentTimeMs = 60_000L,
            cooldownMinutes = 10
        )
        val decision = AutoReplyDecider.evaluate(ctx)
        assertTrue("Message within cooldown window should be skipped", decision is AutoReplyDecider.Decision.Skip)
    }

    @Test
    fun testGroupSummaryIgnored() {
        val ctx = createBaseContext(isGroupSummary = true)
        val decision = AutoReplyDecider.evaluate(ctx)
        assertTrue("Group summary should be skipped", decision is AutoReplyDecider.Decision.Skip)
    }

    @Test
    fun testOlderTimestampIgnored() {
        val ctx = createBaseContext(
            messageTimestamp = 1000L,
            lastHandledMessageTimestamp = 1500L
        )
        val decision = AutoReplyDecider.evaluate(ctx)
        assertTrue("Older timestamp should be skipped", decision is AutoReplyDecider.Decision.Skip)
    }
}
