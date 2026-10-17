package com.winlator.cmod.runtime.system

import android.os.SystemClock
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicLong

/**
 * One activity's launch observations, written through the existing diagnostic logger.
 * Parallel stages have their own start/end events; elapsed time always uses the same
 * monotonic origin. A process or guest surface is not evidence of a playable menu.
 */
class GameLaunchTiming internal constructor(
    requestedAtMs: Long,
    private val clock: () -> Long,
    private val write: (String) -> Unit,
) {
    enum class Event {
        ACTIVITY_CREATED,
        PREPARATION_STARTED,
        DRIVER_CHECK_STARTED,
        DRIVER_CHECK_RETURNED,
        CLOUD_CHECK_STARTED,
        CLOUD_CHECK_RETURNED,
        STEAM_FILES_STARTED,
        STEAM_FILES_READY,
        WINE_SETUP_STARTED,
        WINE_SETUP_RETURNED,
        DRIVER_SETUP_STARTED,
        DRIVER_SETUP_RETURNED,
        AUDIO_SETUP_STARTED,
        AUDIO_SETUP_RETURNED,
        PREPARATION_FINISHED,
        PREPARATION_SKIPPED_ACTIVE_SESSION,
        ENVIRONMENT_SETUP_STARTED,
        ENVIRONMENT_SETUP_RETURNED,
        COMPONENTS_STARTING,
        COMPONENTS_START_RETURNED,
        SESSION_REATTACHED,
        STEAM_HOST_STARTED,
        STEAM_SIGNED_IN,
        STEAM_LAUNCH_DISPATCHED,
        STEAM_PROCESS_OBSERVED,
        X11_CONTENT_OBSERVED,
        WAYLAND_FRAME_OBSERVED,
        LAUNCH_FAILED,
        ACTIVITY_DESTROYED,
    }

    private val activityAtMs = clock()
    private val hasRequestTime = requestedAtMs in 0..activityAtMs
    private val startedAtMs = if (hasRequestTime) requestedAtMs else activityAtMs
    private val origin = if (hasRequestTime) "library_request" else "activity_entry"
    private val traceId = "$activityAtMs-${nextId.incrementAndGet()}"
    private val observed = EnumMap<Event, Long>(Event::class.java)
    private var closed = false

    @Synchronized
    fun mark(event: Event) {
        if (closed || observed.containsKey(event)) return
        val elapsedMs = (clock() - startedAtMs).coerceAtLeast(0L)
        observed[event] = elapsedMs
        if (event == Event.LAUNCH_FAILED || event == Event.ACTIVITY_DESTROYED) closed = true
        // Only fixed event names and timings cross this boundary: no game paths, accounts,
        // command lines or raw Steam log lines. Diagnostics must never prevent a launch.
        try {
            val includeSummary = closed || event == Event.X11_CONTENT_OBSERVED || event == Event.WAYLAND_FRAME_OBSERVED
            write("launch=$traceId origin=$origin event=${event.name} elapsed_ms=$elapsedMs" +
                if (includeSummary) " ${summary()}" else "")
        } catch (_: RuntimeException) {
        }
    }

    /** Furthest observed milestone per lane. Returned setup calls are not proof of a working game. */
    @Synchronized
    fun summary(): String =
        "driver=${latest(Event.DRIVER_SETUP_RETURNED, Event.DRIVER_SETUP_STARTED, Event.DRIVER_CHECK_RETURNED, Event.DRIVER_CHECK_STARTED)} " +
            "runtime=${latest(Event.SESSION_REATTACHED, Event.COMPONENTS_START_RETURNED, Event.COMPONENTS_STARTING, Event.WINE_SETUP_RETURNED, Event.WINE_SETUP_STARTED)} " +
            "steam=${latest(Event.STEAM_PROCESS_OBSERVED, Event.STEAM_LAUNCH_DISPATCHED, Event.STEAM_SIGNED_IN, Event.STEAM_HOST_STARTED)} " +
            "display=${latest(Event.WAYLAND_FRAME_OBSERVED, Event.X11_CONTENT_OBSERVED)}"

    private fun latest(vararg milestones: Event): String {
        val event = milestones.firstOrNull { observed.containsKey(it) } ?: return "not_observed"
        return "${event.name}@${observed[event]}ms"
    }

    companion object {
        const val EXTRA_REQUESTED_AT_MS = "vapor_launch_requested_at_ms"
        private const val TAG = "GameLaunchTiming"
        private val nextId = AtomicLong()

        @JvmStatic
        fun start(requestedAtMs: Long): GameLaunchTiming = GameLaunchTiming(
            requestedAtMs,
            SystemClock::elapsedRealtime,
            { LogManager.logI(TAG, it) },
        ).also { it.mark(Event.ACTIVITY_CREATED) }
    }
}
