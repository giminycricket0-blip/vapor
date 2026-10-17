package com.winlator.cmod.feature.stores.steam.service

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Exit uploads read live game files. Reserve synchronously before queuing exit work, and
 * complete only after its uploader has stopped (including native Steam host shutdown).
 * The existing sync lock serializes Android cloud calls; it cannot track a native worker.
 *
 * Adapted from GameNative CloseSyncTracker, Jeremy Bernstein, GPL-3.0:
 * https://github.com/utkarshdalal/GameNative/pull/1962
 * merge 0fdaf7aba375c1510bec82c142a9b6941744aec6.
 */
object SteamCloseSync {
    private val pending = mutableMapOf<Int, MutableSet<CompletableJob>>()
    private val active = MutableStateFlow<Set<Int>>(emptySet())
    val activeAppIds: StateFlow<Set<Int>> = active.asStateFlow()

    @JvmStatic
    fun reserve(appId: Int): CompletableJob {
        val reservation = Job()
        synchronized(pending) {
            pending.getOrPut(appId) { mutableSetOf() }.add(reservation)
            active.value = pending.keys.toSet()
        }
        reservation.invokeOnCompletion {
            synchronized(pending) {
                pending[appId]?.let { jobs ->
                    jobs.remove(reservation)
                    if (jobs.isEmpty()) pending.remove(appId)
                }
                active.value = pending.keys.toSet()
            }
        }
        return reservation
    }

    suspend fun awaitIdle(appId: Int) {
        while (true) {
            val uploads = synchronized(pending) { pending[appId]?.toList().orEmpty() }
            if (uploads.isEmpty()) return
            uploads.forEach { it.join() }
            // A retry or fallback upload may have been reserved while we were waiting.
        }
    }
}
