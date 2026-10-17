package com.winlator.cmod.feature.stores.steam.service

import android.app.Application
import android.content.Context
import com.winlator.cmod.feature.stores.steam.utils.PrefManager
import com.winlator.cmod.feature.stores.steam.wnsteam.WnSteamSession
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SteamChatServiceTest {
    private lateinit var service: SteamService
    private lateinit var session: WnSteamSession
    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        ReflectionHelpers.setField(PrefManager, "prefs", app.getSharedPreferences("chat-test", Context.MODE_PRIVATE))
        PrefManager.refreshToken = "test-only-placeholder"
        service = SteamService()
        session = mock(WnSteamSession::class.java)
        SteamService.instance = service
        SteamService.wnSession = session
        SteamService.isRunning = true
        SteamService.isStopping = false
        SteamService.isLoggingOut = false
        SteamService._isLoggedInFlow.value = true
        SteamService.wnLoggedOnHandled = true
        `when`(session.state()).thenReturn(3)
    }
    @After fun teardown() {
        service.scope.cancel()
        SteamService.wnSession = null
        SteamService.instance = null
        SteamService.isRunning = false
        SteamService.isStopping = false
        SteamService._isLoggedInFlow.value = false
        SteamService.wnLoggedOnHandled = false
        ReflectionHelpers.setField(PrefManager, "prefs", null)
    }

    @Test fun genuineHostOwnershipBlocksAllChatRequestsWithoutReconnect() = runBlocking {
        service.suspendedForBionic = true
        assertTrue(service.bionicHandoffFlow.value)
        assertNull(service.sendChatMessage(42, "test"))
        assertTrue(service.loadChatHistory(42).isEmpty())
        assertNull(service.sendChatImage(42, byteArrayOf(1), "test.png"))
        verifyNoInteractions(session)
        assertNull(service.connectJob)
    }

    @Test fun disconnectedOrChannelOnlySessionCannotSend() = runBlocking {
        for (state in listOf(0, 2)) {
            `when`(session.state()).thenReturn(state)
            assertNull(service.sendChatMessage(42, "test"))
        }
        verify(session, never()).sendFriendMessage(anyLong(), anyString())
        assertNull(service.connectJob)
    }

    @Test fun stoppingServiceCannotSend() = runBlocking {
        SteamService.isStopping = true
        assertNull(service.sendChatMessage(42, "test"))
        verifyNoInteractions(session)
    }

    @Test fun serverAcknowledgementIsReturnedAsTheMessageIdentity() = runBlocking {
        `when`(session.sendFriendMessage(42, "test")).thenReturn("{\"serverTimestamp\":1234,\"ordinal\":7}")
        val result = service.sendChatMessage(42, "test")!!
        assertEquals(1234, result.timestamp)
        assertEquals(7, result.ordinal)
        assertEquals("test", result.text)
        assertTrue(result.fromSelf)
    }

    @Test fun missingMalformedOrIncompleteAcknowledgementIsNotSuccess() = runBlocking {
        for (response in listOf(null, "garbage", "{}", "{\"serverTimestamp\":1234}")) {
            `when`(session.sendFriendMessage(42, "test")).thenReturn(response)
            assertNull(service.sendChatMessage(42, "test"))
        }
    }

    @Test fun noRecipientOrBlankTextCannotReachSteam() = runBlocking {
        assertNull(service.sendChatMessage(0, "test"))
        assertNull(service.sendChatMessage(42, "  "))
        verifyNoInteractions(session)
    }
}
