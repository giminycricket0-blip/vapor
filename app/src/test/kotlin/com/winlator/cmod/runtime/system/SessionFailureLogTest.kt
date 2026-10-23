package com.winlator.cmod.runtime.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The failure log must stay bounded and cheap, because it runs for every line of every session
 * even though it is almost never written. These pin the ring behaviour and the reset rule.
 */
class SessionFailureLogTest {
    @Test
    fun linesAreBufferedInOrder() {
        SessionFailureLog.begin()
        SessionFailureLog.record("first")
        SessionFailureLog.record("second")
        assertEquals(listOf("first", "second"), SessionFailureLog.snapshot())
    }

    @Test
    fun theBufferIsBoundedAndKeepsTheNewest() {
        SessionFailureLog.begin()
        repeat(1000) { SessionFailureLog.record("line-$it") }
        val snapshot = SessionFailureLog.snapshot()
        assertTrue("the buffer must not grow without limit", snapshot.size <= 400)
        assertEquals("line-999", snapshot.last())
        assertTrue("the oldest lines are dropped first", snapshot.none { it == "line-0" })
    }

    @Test
    fun aNewSessionForgetsThePreviousOne() {
        SessionFailureLog.begin()
        SessionFailureLog.record("old session line")
        SessionFailureLog.begin()
        assertEquals(0, SessionFailureLog.size())
        assertTrue(SessionFailureLog.snapshot().isEmpty())
    }

    @Test
    fun anEmptyBufferIsNotAnError() {
        SessionFailureLog.begin()
        assertEquals(0, SessionFailureLog.size())
    }
}
