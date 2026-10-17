package com.winlator.cmod.runtime.system

import com.winlator.cmod.runtime.system.GameLaunchTiming.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GameLaunchTimingTest {
    @Test fun parallelPreparationUsesOneOriginWithoutAddingOverlappingDurations() {
        var now = 150L
        val lines = mutableListOf<String>()
        val timing = GameLaunchTiming(100L, { now }, lines::add)
        timing.mark(Event.ACTIVITY_CREATED)
        now = 200L
        timing.mark(Event.STEAM_FILES_STARTED)
        now = 210L
        timing.mark(Event.WINE_SETUP_STARTED)
        now = 400L
        timing.mark(Event.STEAM_FILES_READY)
        now = 450L
        timing.mark(Event.WINE_SETUP_RETURNED)
        timing.mark(Event.PREPARATION_FINISHED)

        assertEquals(listOf(50L, 100L, 110L, 300L, 350L, 350L), lines.map(::elapsed))
        assertEquals(1, lines.map { it.substringBefore(" origin=") }.distinct().size)
        assertTrue(lines.all { "origin=library_request" in it })
    }

    @Test fun absentOrFutureRequestTimeStartsAtActivityEntry() {
        for (requestedAt in listOf(-1L, 501L)) {
            val lines = mutableListOf<String>()
            val timing = GameLaunchTiming(requestedAt, { 500L }, lines::add)
            timing.mark(Event.ACTIVITY_CREATED)
            assertTrue(lines.single().contains("origin=activity_entry"))
            assertEquals(0L, elapsed(lines.single()))
        }
    }

    @Test fun repeatedCallbacksAreWrittenOnlyOnceEvenAcrossThreads() {
        val lines = mutableListOf<String>()
        val timing = GameLaunchTiming(0L, { 100L }, lines::add)
        val workers = Executors.newFixedThreadPool(4)
        val ready = CountDownLatch(1)
        try {
            val results = (1..20).map {
                workers.submit {
                    ready.await()
                    timing.mark(Event.X11_CONTENT_OBSERVED)
                }
            }
            ready.countDown()
            results.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, lines.size)
        } finally {
            workers.shutdownNow()
        }
    }

    @Test fun failureOrDestructionClosesTheTraceAgainstLateCallbacks() {
        for (terminal in listOf(Event.LAUNCH_FAILED, Event.ACTIVITY_DESTROYED)) {
            val lines = mutableListOf<String>()
            val timing = GameLaunchTiming(0L, { 100L }, lines::add)
            timing.mark(terminal)
            timing.mark(Event.STEAM_PROCESS_OBSERVED)
            timing.mark(Event.X11_CONTENT_OBSERVED)
            timing.mark(terminal)
            assertEquals(1, lines.size)
            assertTrue(lines.single().contains("event=${terminal.name}"))
        }
    }

    @Test fun diagnosticWriteFailureDoesNotInterruptGameLaunch() {
        val timing = GameLaunchTiming(0L, { 100L }) { throw IllegalStateException("logger unavailable") }
        timing.mark(Event.ACTIVITY_CREATED)
        timing.mark(Event.WINE_SETUP_STARTED)
        timing.mark(Event.LAUNCH_FAILED)
    }

    @Test fun failureSummaryKeepsUnobservedStagesDistinctFromFailedChecks() {
        var now = 100L
        val lines = mutableListOf<String>()
        val timing = GameLaunchTiming(100L, { now }, lines::add)
        timing.mark(Event.DRIVER_CHECK_STARTED)
        now = 125L
        timing.mark(Event.LAUNCH_FAILED)
        assertTrue(lines.last().contains("driver=DRIVER_CHECK_STARTED@0ms"))
        assertTrue(lines.last().contains("runtime=not_observed steam=not_observed display=not_observed"))
        assertFalse(lines.last().contains("DRIVER_CHECK_RETURNED"))
    }

    @Test fun firstContentSummaryReportsObservedMilestonesWithoutCallingThemPlayable() {
        var now = 100L
        val lines = mutableListOf<String>()
        val timing = GameLaunchTiming(100L, { now }, lines::add)
        timing.mark(Event.DRIVER_CHECK_STARTED)
        now = 150L; timing.mark(Event.DRIVER_CHECK_RETURNED)
        now = 200L; timing.mark(Event.DRIVER_SETUP_RETURNED)
        now = 250L; timing.mark(Event.COMPONENTS_START_RETURNED)
        now = 300L; timing.mark(Event.STEAM_SIGNED_IN)
        now = 350L; timing.mark(Event.STEAM_PROCESS_OBSERVED)
        now = 500L; timing.mark(Event.X11_CONTENT_OBSERVED)
        assertTrue(lines.last().endsWith("driver=DRIVER_SETUP_RETURNED@100ms " +
            "runtime=COMPONENTS_START_RETURNED@150ms steam=STEAM_PROCESS_OBSERVED@250ms " +
            "display=X11_CONTENT_OBSERVED@400ms"))
        assertFalse(lines.last().contains("playable", ignoreCase = true))
    }

    @Test fun reattachmentDoesNotInventANewDriverOrSteamStartup() {
        val lines = mutableListOf<String>()
        val timing = GameLaunchTiming(0L, { 100L }, lines::add)
        timing.mark(Event.SESSION_REATTACHED)
        timing.mark(Event.WAYLAND_FRAME_OBSERVED)
        assertTrue(lines.last().contains("driver=not_observed runtime=SESSION_REATTACHED@100ms steam=not_observed"))
        assertTrue(lines.last().endsWith("display=WAYLAND_FRAME_OBSERVED@100ms"))
    }

    private fun elapsed(line: String): Long = line.substringAfter("elapsed_ms=").substringBefore(' ').toLong()
}
