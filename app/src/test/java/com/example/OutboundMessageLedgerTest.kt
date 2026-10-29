package com.example

import com.example.core.messaging.OutboundMessageLedger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OutboundMessageLedgerTest {

    @Before
    fun setup() {
        OutboundMessageLedger.clearForTesting()
    }

    @Test
    fun testFirstSendAllowed() {
        val allowed = OutboundMessageLedger.recordAndCheckAllowed("+919876543210", "Hello there!")
        assertTrue("First outbound message should be allowed", allowed)
    }

    @Test
    fun testDuplicateSendBlockedWithinWindow() {
        val first = OutboundMessageLedger.recordAndCheckAllowed("+919876543210", "Hello there!")
        assertTrue(first)

        val second = OutboundMessageLedger.recordAndCheckAllowed("+919876543210", "Hello there!")
        assertFalse("Exact duplicate send within 90s should be blocked", second)
    }

    @Test
    fun testDifferentRecipientAllowed() {
        val first = OutboundMessageLedger.recordAndCheckAllowed("+919876543210", "Hello there!")
        val second = OutboundMessageLedger.recordAndCheckAllowed("+919876543211", "Hello there!")
        assertTrue(first)
        assertTrue("Different recipient with same text should be allowed", second)
    }

    @Test
    fun testDifferentTextAllowed() {
        val first = OutboundMessageLedger.recordAndCheckAllowed("+919876543210", "Hello there!")
        val second = OutboundMessageLedger.recordAndCheckAllowed("+919876543210", "Different text")
        assertTrue(first)
        assertTrue("Same recipient with different text should be allowed", second)
    }
}
