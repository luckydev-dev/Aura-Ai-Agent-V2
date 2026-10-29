package com.example

import com.example.core.telephony.CallSessionTracker
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CallSessionTrackerTest {

    private lateinit var tracker: CallSessionTracker

    @Before
    fun setup() {
        tracker = CallSessionTracker(cooldownMs = 60_000L)
        tracker.clearForTesting()
    }

    @Test
    fun testThreeSignalsIn1500MsMergeIntoOneSession() {
        // Signal 1: Telephony ringing with no number
        val (session1, isNew1) = tracker.onCallRinging(
            number = null,
            name = null,
            source = "TELEPHONY",
            isVoip = false
        )
        assertTrue("First ringing signal should create new session", isNew1)
        assertNotNull(session1)

        // Signal 2: PHONE_STATE broadcast with incoming number arriving 500ms later
        val (session2, isNew2) = tracker.onCallRinging(
            number = "+919876543210",
            name = null,
            source = "PHONE_STATE",
            isVoip = false
        )
        assertFalse("Second signal in 500ms should be merged into existing session", isNew2)
        assertEquals("Sessions must have identical ID", session1.id, session2.id)
        assertEquals("+919876543210", session2.number)

        // Signal 3: CallScreeningService arriving 1000ms later
        val (session3, isNew3) = tracker.onCallRinging(
            number = "+919876543210",
            name = "Rahul Sharma",
            source = "CALL_SCREENING",
            isVoip = false
        )
        assertFalse("Third signal should also be merged into existing session", isNew3)
        assertEquals(session1.id, session3.id)
        assertEquals("Rahul Sharma", session3.name)
        assertEquals("+919876543210", session3.number)
    }

    @Test
    fun testVoipCallMapsToSession() {
        val (session, isNew) = tracker.onCallRinging(
            number = null,
            name = "Priya",
            source = "VOIP",
            isVoip = true
        )
        assertTrue(isNew)
        assertTrue("Session must be marked as VoIP", session.isVoip)
        assertEquals("Priya", session.name)
        assertNull("VoIP call without phone number has null number", session.number)
    }

    @Test
    fun testZeroDigitNumberMapsToNull() {
        val (session, isNew) = tracker.onCallRinging(
            number = "   ",
            name = "Unknown Caller",
            source = "TELEPHONY",
            isVoip = false
        )
        assertTrue(isNew)
        assertNull("Blank or 0-digit number should normalize to null", session.number)
    }

    @Test
    fun testAutoRejectCooldownBlocksSecondRejectWithin60Seconds() {
        val number = "+919876543210"
        val allowed1 = tracker.checkAndRecordCooldown(number, now = 1000L)
        assertTrue("First reject message should be allowed", allowed1)

        val allowed2 = tracker.checkAndRecordCooldown(number, now = 15_000L)
        assertFalse("Second reject message within 60s should be blocked by cooldown", allowed2)

        val allowed3 = tracker.checkAndRecordCooldown(number, now = 70_000L)
        assertTrue("Reject message after 60s cooldown expires should be allowed", allowed3)
    }
}
