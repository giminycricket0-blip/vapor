package com.winlator.cmod.feature.stores.steam.service

import android.app.Application
import android.content.Context
import com.winlator.cmod.feature.stores.steam.utils.PrefManager
import com.winlator.cmod.feature.stores.steam.enums.EOSType
import com.winlator.cmod.feature.stores.steam.wnsteam.WnSteamSession
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SteamHandoffTest {
    private lateinit var service: SteamService
    private lateinit var session: WnSteamSession

    @Before fun setup() {
        // Test-only preferences and a mock transport: no account, token or native login is used.
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("handoff-test", Context.MODE_PRIVATE)
        ReflectionHelpers.setField(PrefManager, "prefs", prefs)
        service = SteamService()
        session = mock(WnSteamSession::class.java)
        SteamService.instance = service
        SteamService.wnSession = session
        SteamService.isRunning = true
        SteamService.isStopping = false
        SteamService.isLoggingOut = false
        `when`(session.state()).thenReturn(3)
    }

    @After fun teardown() {
        service.scope.cancel()
        SteamService.wnSession = null
        SteamService.instance = null
        SteamService.isRunning = false
        SteamService.isStopping = false
        SteamService.isLoggingOut = false
        ReflectionHelpers.setField(PrefManager, "prefs", null)
    }

    @Test fun anOldExitCannotKickTheNewGenuineHostSession() = runBlocking {
        service.bionicHandoffAcquireImpl()
        assertFalse(SteamService.kickPlayingSessionIfReady())
        verify(session, never()).kickPlayingSession(anyBoolean())
        verify(session, never()).notifyGamesPlayed(anyString(), anyInt())
    }

    @Test fun stoppedServiceDoesNotSendAnExitKick() = runBlocking {
        SteamService.isStopping = true
        assertFalse(SteamService.kickPlayingSessionIfReady())
        verify(session, never()).kickPlayingSession(anyBoolean())
    }

    @Test fun readyAndroidSessionCanStillClearThePreviousGame() = runBlocking {
        assertTrue(SteamService.kickPlayingSessionIfReady())
        verify(session).kickPlayingSession(true)
        verify(session).notifyGamesPlayed("[]", EOSType.AndroidUnknown.code())
    }

    @Test fun nextLaunchCancelsTheWholePreviousRelease() = runBlocking {
        PrefManager.refreshToken = "test-only-placeholder"
        service.bionicHandoffAcquireImpl()
        val oldRelease = service.bionicHandoffReleaseImpl(true)!!
        assertTrue(oldRelease.isActive)

        service.bionicHandoffAcquireImpl()
        oldRelease.join()
        assertTrue(oldRelease.isCancelled)
        assertNull(service.handoffReleaseJob)
        assertTrue(service.suspendedForBionic)
        verify(session, never()).kickPlayingSession(anyBoolean())

        val nextRelease = service.bionicHandoffReleaseImpl(true)!!
        assertNotSame(oldRelease, nextRelease)
        assertTrue(nextRelease.isActive)
        assertTrue(oldRelease.isCancelled)
    }

    @Test fun duplicateExitSharesItsExistingCleanupInsteadOfRestartingIt() {
        PrefManager.refreshToken = "test-only-placeholder"
        service.bionicHandoffAcquireImpl()
        val first = service.bionicHandoffReleaseImpl(true)!!
        assertSame(first, service.bionicHandoffReleaseImpl(true))
        assertSame(first, service.handoffReleaseJob)
        assertTrue(first.isActive)
    }

    @Test fun aShortWaitDoesNotCancelCleanupNeededAfterTheReapDelay() = runBlocking {
        PrefManager.refreshToken = "test-only-placeholder"
        service.bionicHandoffAcquireImpl()
        assertFalse(service.bionicHandoffReleaseAndKickPlayingSessionBlockingImpl(true, 1))
        assertTrue(service.handoffReleaseJob!!.isActive)
        verify(session, never()).kickPlayingSession(anyBoolean())
    }

    @Test fun playingStatusIsClearedAfterTheReapWindowRatherThanTimingOutAlongsideIt() = runBlocking {
        PrefManager.refreshToken = "test-only-placeholder"
        service.bionicHandoffAcquireImpl()
        val release = service.bionicHandoffReleaseImpl(true)!!
        assertTrue(withTimeout(15_000) { release.await() })
        verify(session).kickPlayingSession(true)
        verify(session).notifyGamesPlayed("[]", EOSType.AndroidUnknown.code())
    }

    @Test fun returningHomeDoesNotStartAnEarlyReconnect() {
        PrefManager.refreshToken = "test-only-placeholder"
        `when`(session.state()).thenReturn(0)
        service.bionicHandoffAcquireImpl()
        service.bionicHandoffReleaseImpl(true)
        service.ensureHealthySessionImpl()
        assertNull(service.connectJob)
        assertTrue(service.handoffReleaseJob!!.isActive)
    }

    @Test fun idleBackgroundSuspendCancelsReleaseAndDoesNotWakeSteamAgain() = runBlocking {
        PrefManager.refreshToken = "test-only-placeholder"
        service.bionicHandoffAcquireImpl()
        val release = service.bionicHandoffReleaseImpl(true)!!
        service.appInForeground = false
        assertTrue(service.maybeSuspendForBackground())
        release.join()
        assertTrue(release.isCancelled)
        assertNull(service.handoffReleaseJob)
        assertFalse(SteamService.kickPlayingSessionIfReady())
        verify(session, never()).kickPlayingSession(anyBoolean())
    }

    @Test fun backgroundSuspendWaitsUntilReservedSaveCleanupHasFinished() {
        service.appInForeground = false
        val upload = SteamCloseSync.reserve(286100)
        try {
            assertFalse(service.maybeSuspendForBackground())
            assertFalse(service.suspendedForBackground)
            verify(session, never()).disconnect()
        } finally { upload.complete() }
        assertTrue(service.maybeSuspendForBackground())
    }
}
