package com.winlator.cmod.feature.stores.steam.service

import android.app.Application
import android.content.Context
import android.webkit.CookieManager
import android.webkit.ValueCallback
import com.winlator.cmod.feature.stores.steam.utils.PrefManager
import com.winlator.cmod.feature.stores.steam.wnsteam.WnSteamSession
import com.winlator.cmod.runtime.system.SessionKeepAliveService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
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
class SteamWebSessionTest {
    private lateinit var service: SteamService
    private lateinit var native: WnSteamSession
    private lateinit var cookies: CookieManager
    private val writes = mutableListOf<Pair<String, String>>()

    @Before fun setup() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("web-session-test", Context.MODE_PRIVATE)
        ReflectionHelpers.setField(PrefManager, "prefs", prefs)
        PrefManager.refreshToken = "test-only-refresh"
        PrefManager.steamUserSteamId64 = 42
        service = SteamService()
        native = mock(WnSteamSession::class.java)
        cookies = mock(CookieManager::class.java)
        SteamService.instance = service
        SteamService.wnSession = native
        SteamService.isRunning = true
        SteamService.isStopping = false
        SteamService.isLoggingOut = false
        SteamService._isLoggedInFlow.value = true
        `when`(native.state()).thenReturn(3)
        `when`(native.steamId()).thenReturn(42)
        `when`(native.getWebAccessToken("test-only-refresh")).thenReturn("header.payload.signature")
        doAnswer { call ->
            writes += call.getArgument<String>(0) to call.getArgument<String>(1)
            call.getArgument<ValueCallback<Boolean>>(2).onReceiveValue(true)
            null
        }.`when`(cookies).setCookie(anyString(), anyString(), any())
    }

    @After fun teardown() {
        service.scope.cancel()
        SteamService.instance = null
        SteamService.wnSession = null
        SteamService.isRunning = false
        SteamService.isStopping = false
        SteamService.isLoggingOut = false
        SteamService._isLoggedInFlow.value = false
        ReflectionHelpers.setField(PrefManager, "prefs", null)
    }

    @Test fun officialSteamPagesStayInsideTheAuthenticatedPanel() {
        SteamWebSession.hosts.forEach { assertTrue(SteamWebSession.isSteamPage("https://$it/")) }
    }

    @Test fun lookalikeHostsCredentialsAndNonHttpsAreNotTrusted() {
        listOf("https://store.steampowered.com.evil.example/", "https://evil.example/steamcommunity.com",
            "http://steamcommunity.com/", "javascript:alert(1)", "file:///steamcommunity.com",
            "https://user@steamcommunity.com/", "https://steamcommunity.com:8443/",
            "https://steamcommunity.com@evil.example/").forEach { assertFalse(it, SteamWebSession.isSteamPage(it)) }
    }

    @Test fun steamInstallLinksOnlySelectAPositiveAppId() {
        assertEquals(22150, SteamWebSession.installAppId("steam://install/22150"))
        assertEquals(22150, SteamWebSession.installAppId("steam://run/22150"))
        listOf("steam://install/0", "steam://install/-1", "steam://run/22150/command",
            "steam://install/22150?command=other", "https://install/22150", "steam://uninstall/22150",
            "steam://user@install/22150").forEach { assertNull(it, SteamWebSession.installAppId(it)) }
    }

    @Test fun onlyTheShortLivedAccessTokenEntersTheSecureHttpOnlyCookie() {
        val cookie = SteamWebSession.loginCookie(123, "header.payload.signature")
        assertEquals("steamLoginSecure=123%7C%7Cheader.payload.signature; Path=/; Secure; HttpOnly; SameSite=Lax", cookie)
        assertFalse(cookie.contains("Domain=")) // Host-only: no unrelated Steam subdomain receives it.
        assertThrows(IllegalArgumentException::class.java) { SteamWebSession.loginCookie(0, "header.payload.signature") }
        assertThrows(IllegalArgumentException::class.java) { SteamWebSession.loginCookie(123, "token; Domain=evil.example") }
    }

    @Test fun connectUsesCurrentAccountAndPreservesNonAuthenticationCookies() = runBlocking {
        `when`(cookies.getCookie(anyString())).thenReturn("Steam_Language=english; shoppingCartGID=fixture")
        SteamWebSession.connect(cookies)
        verify(native, times(1)).getWebAccessToken("test-only-refresh")
        assertEquals(SteamWebSession.hosts.size, writes.count { it.second.startsWith("steamLoginSecure=42%7C%7C") })
        assertTrue(writes.all { it.first.removePrefix("https://").removeSuffix("/") in SteamWebSession.hosts })
        assertTrue(writes.none { it.second.contains("test-only-refresh") || it.second.startsWith("Steam_Language=") || it.second.startsWith("shoppingCartGID=") })
        assertNull(service.connectJob)
    }

    @Test fun disconnectedOrGameOwnedSessionCannotReconnectOrWriteCookies() = runBlocking {
        service.suspendedForBionic = true
        assertTrue(runCatching { SteamWebSession.connect(cookies) }.isFailure)
        service.suspendedForBionic = false
        `when`(native.state()).thenReturn(2)
        assertTrue(runCatching { SteamWebSession.connect(cookies) }.isFailure)
        verify(native, never()).getWebAccessToken(anyString())
        verifyNoInteractions(cookies)
        assertNull(service.connectJob)
    }

    @Test fun accountOrHostOwnershipChangeWhileGettingTokenDiscardsIt() = runBlocking {
        `when`(native.getWebAccessToken(anyString())).thenAnswer {
            PrefManager.steamUserSteamId64 = 43
            "header.payload.signature"
        }
        assertTrue(runCatching { SteamWebSession.connect(cookies) }.isFailure)
        PrefManager.steamUserSteamId64 = 42
        `when`(native.getWebAccessToken(anyString())).thenAnswer {
            service.suspendedForBionic = true
            "header.payload.signature"
        }
        assertTrue(runCatching { SteamWebSession.connect(cookies) }.isFailure)
        verifyNoInteractions(cookies)
    }

    @Test fun rejectedTokenOrCookieCannotReportReady() = runBlocking {
        `when`(native.getWebAccessToken(anyString())).thenReturn(null)
        assertTrue(runCatching { SteamWebSession.connect(cookies) }.isFailure)
        verifyNoInteractions(cookies)
        `when`(native.getWebAccessToken(anyString())).thenReturn("header.payload.signature")
        doAnswer { call ->
            call.getArgument<ValueCallback<Boolean>>(2).onReceiveValue(false)
            null
        }.`when`(cookies).setCookie(anyString(), anyString(), any())
        assertTrue(runCatching { SteamWebSession.connect(cookies) }.isFailure)
    }

    @Test fun logoutDuringCookieWritesPreventsFurtherAuthenticationWrites() = runBlocking {
        doAnswer { call ->
            writes += call.getArgument<String>(0) to call.getArgument<String>(1)
            SteamService._isLoggedInFlow.value = false
            call.getArgument<ValueCallback<Boolean>>(2).onReceiveValue(true)
            null
        }.`when`(cookies).setCookie(anyString(), anyString(), any())
        assertTrue(runCatching { SteamWebSession.connect(cookies) }.isFailure)
        assertEquals(1, writes.size)
        assertTrue(writes.none { it.second.contains("header.payload.signature") })
    }

    @Test fun cancellationDoesNotBecomeReadyOrWriteCredentials() = runBlocking {
        `when`(native.getWebAccessToken(anyString())).thenThrow(CancellationException("fixture"))
        try {
            SteamWebSession.connect(cookies)
            fail("Cancellation must reach the panel coroutine")
        } catch (_: CancellationException) { }
        verifyNoInteractions(cookies)
    }

    @Test fun ordinaryLogoutCallsCookieCleanup() {
        // Leave no live service/runtime to stop; exercise the real public logout entry point.
        SteamService.instance = null
        SteamService.wnSession = null
        mockStatic(CookieManager::class.java).use { cookieClass ->
            cookieClass.`when`<CookieManager> { CookieManager.getInstance() }.thenReturn(cookies)
            mockStatic(SessionKeepAliveService::class.java).use { keepAlive ->
                keepAlive.`when`<Boolean> { SessionKeepAliveService.isLinuxSessionActive() }.thenReturn(true)
                SteamService.logOut()
            }
        }
        SteamWebSession.hosts.forEach { host ->
            verify(cookies).setCookie("https://$host/", "steamLoginSecure=; Max-Age=0; Path=/; Secure")
        }
        assertFalse(SteamService.isLoggedIn)
        assertTrue(PrefManager.refreshToken.isEmpty())
    }

    @Test fun unavailableWebViewCannotPreventNativeLogout() {
        SteamService.instance = null
        SteamService.wnSession = null
        mockStatic(CookieManager::class.java).use { cookieClass ->
            cookieClass.`when`<CookieManager> { CookieManager.getInstance() }
                .thenThrow(IllegalStateException("No WebView provider"))
            mockStatic(SessionKeepAliveService::class.java).use { keepAlive ->
                keepAlive.`when`<Boolean> { SessionKeepAliveService.isLinuxSessionActive() }.thenReturn(true)
                SteamService.logOut()
            }
        }
        assertFalse(SteamService.isLoggedIn)
        assertTrue(PrefManager.refreshToken.isEmpty())
    }
}
