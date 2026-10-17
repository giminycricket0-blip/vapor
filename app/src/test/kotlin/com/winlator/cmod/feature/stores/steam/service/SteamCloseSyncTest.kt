package com.winlator.cmod.feature.stores.steam.service

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SteamCloseSyncTest {
    @get:Rule val files = TemporaryFolder()
    @get:Rule val timeout = org.junit.rules.Timeout.seconds(10)

    @Test fun reservationBlocksDeletionBeforeUploadCoroutineStarts() = runBlocking {
        val reservation = SteamCloseSync.reserve(101)
        var deleted = false
        val deletion = launch(start = CoroutineStart.UNDISPATCHED) {
            SteamCloseSync.awaitIdle(101)
            deleted = true
        }
        try {
            assertTrue(101 in SteamCloseSync.activeAppIds.value)
            assertFalse(deleted)
        } finally { reservation.complete() }
        deletion.join()
        assertTrue(deleted)
        assertFalse(101 in SteamCloseSync.activeAppIds.value)
    }

    @Test fun uploaderCanReadTheSaveBeforeDeletion() = runBlocking {
        val save = files.newFile("save.dat").apply { writeText("level 3") }
        val releaseUpload = CompletableDeferred<Unit>()
        val reservation = SteamCloseSync.reserve(102)
        var uploaded = ""
        val upload = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                releaseUpload.await()
                uploaded = save.readText()
            } finally { reservation.complete() }
        }
        val deletion = launch(start = CoroutineStart.UNDISPATCHED) {
            SteamCloseSync.awaitIdle(102)
            save.delete()
        }
        try {
            assertTrue(save.exists())
            assertFalse(deletion.isCompleted)
        } finally { releaseUpload.complete(Unit) }
        upload.join()
        deletion.join()
        assertEquals("level 3", uploaded)
        assertFalse(save.exists())
    }

    @Test fun failedUploadReleasesTheReservation() = runBlocking {
        val reservation = SteamCloseSync.reserve(103)
        val failure = runCatching {
            try { throw IOException("connection lost") }
            finally { reservation.complete() }
        }
        SteamCloseSync.awaitIdle(103)
        assertTrue(failure.exceptionOrNull() is IOException)
        assertFalse(103 in SteamCloseSync.activeAppIds.value)
    }

    @Test fun waitsForOverlappingNativeAndAndroidUploadsIncludingALaterRetry() = runBlocking {
        val native = SteamCloseSync.reserve(104)
        val android = SteamCloseSync.reserve(104)
        val deletion = launch(start = CoroutineStart.UNDISPATCHED) { SteamCloseSync.awaitIdle(104) }
        val retry = SteamCloseSync.reserve(104)
        try {
            native.complete()
            android.complete()
            // Run the waiter until it reaches the later reservation.
            kotlinx.coroutines.yield()
            assertFalse(deletion.isCompleted)
            assertTrue(104 in SteamCloseSync.activeAppIds.value)
        } finally {
            native.complete(); android.complete(); retry.complete()
        }
        deletion.join()
        assertFalse(104 in SteamCloseSync.activeAppIds.value)
    }

    @Test fun cancelledDeletionDoesNotCancelUploadOrClearItsState() = runBlocking {
        val reservation = SteamCloseSync.reserve(105)
        try {
            val deletion = launch(start = CoroutineStart.UNDISPATCHED) { SteamCloseSync.awaitIdle(105) }
            deletion.cancelAndJoin()
            assertTrue(reservation.isActive)
            assertTrue(105 in SteamCloseSync.activeAppIds.value)
        } finally { reservation.complete() }
    }

    @Test fun anotherAppCanBeDeletedAndDuplicateCompletionIsHarmless() = runBlocking {
        val reservation = SteamCloseSync.reserve(106)
        try {
            SteamCloseSync.awaitIdle(107)
            assertTrue(106 in SteamCloseSync.activeAppIds.value)
        } finally {
            reservation.complete()
            reservation.complete()
        }
        assertFalse(106 in SteamCloseSync.activeAppIds.value)
    }
}
