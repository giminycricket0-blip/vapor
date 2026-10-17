package com.winlator.cmod.app.shell

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.cmod.R
import com.winlator.cmod.feature.stores.steam.service.SteamWebSession
import com.winlator.cmod.shared.theme.WinNativeTheme
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Local HTML and synthetic authentication only. Never requests a Steam page or uses an account. */
@RunWith(AndroidJUnit4::class)
class SteamWebPanelUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val status = mutableStateOf(SteamWebAvailability.READY)
    private val visible = mutableStateOf(true)
    private val urls = mutableListOf<String>()
    private var calls = 0
    private var destroyed = 0
    private var connects = 0
    private val url = "https://store.steampowered.com/wishlist/"
    private fun label(id: Int) = compose.activity.getString(id)

    private fun show(connect: suspend () -> Unit = { calls++ }) {
        compose.setContent { WinNativeTheme {
            if (visible.value) SteamWebPanelContent(url, status.value,
                onConnect = { connects++ }, onInstall = {}, onClose = { visible.value = false },
                connect = connect, createWebView = { context ->
                    object : WebView(context) {
                        override fun loadUrl(url: String) {
                            urls.add(url)
                            super.loadDataWithBaseURL(url,
                                "<html><body style='background:#172332;color:white'><h1>Local Store fixture</h1><p>No Steam account or network request.</p></body></html>",
                                "text/html", "UTF-8", null)
                        }
                        override fun destroy() { destroyed++; super.destroy() }
                    }
                })
        } }
    }

    private fun press(code: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val now = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            instrumentation.sendKeySync(KeyEvent(now, SystemClock.uptimeMillis(), action, code,
                0, 0, -1, 0, 0, InputDevice.SOURCE_GAMEPAD))
        }
        compose.waitForIdle()
    }

    @Test fun signedOutPanelUsesNativeSignInWithoutOpeningThePage() {
        status.value = SteamWebAvailability.SIGNED_OUT
        show()
        compose.onNodeWithText(label(R.string.vapor_connect_steam)).performClick()
        compose.runOnIdle { assertEquals(1, connects); assertEquals(0, calls); assertTrue(urls.isEmpty()) }
    }

    @Test fun offlineAndGameOwnedSessionsWaitForExistingConnection() {
        status.value = SteamWebAvailability.OFFLINE
        show()
        compose.onNodeWithText(label(R.string.vapor_steam_web_offline)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, calls); status.value = SteamWebAvailability.IN_GAME }
        compose.onNodeWithText(label(R.string.vapor_steam_web_in_game)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, calls); status.value = SteamWebAvailability.READY }
        compose.waitUntil(5000) { urls.size == 1 }
        compose.runOnIdle { assertEquals(1, calls); assertEquals(url, urls.single()) }
    }

    @Test fun failedSignInHasAnExplicitRetryAndNoPageOrRawError() {
        show { calls++; if (calls == 1) throw IOException("private-test-detail") }
        compose.onNodeWithText(label(R.string.vapor_steam_web_auth_failed)).assertIsDisplayed()
        compose.onNodeWithText("private-test-detail", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertTrue(urls.isEmpty()) }
        compose.onNodeWithText(label(R.string.steam_login_retry)).performClick()
        compose.waitUntil(5000) { urls.size == 1 }
        compose.runOnIdle { assertEquals(2, calls) }
    }

    @Test fun closingWhileConnectingCancelsAndNeverCreatesAWebView() {
        var cancelled = false
        show { calls++; try { awaitCancellation() } finally { cancelled = true } }
        compose.onNodeWithText(label(R.string.vapor_steam_web_loading)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.common_ui_close)).performClick()
        compose.runOnIdle { assertTrue(cancelled); assertEquals(1, calls); assertTrue(urls.isEmpty()) }
    }

    @Test fun reconnectDoesNotReloadALoadedCheckoutOrStartAnotherAuthentication() {
        show()
        compose.waitUntil(5000) { urls.size == 1 }
        compose.runOnIdle { status.value = SteamWebAvailability.OFFLINE }
        compose.onNodeWithContentDescription(label(R.string.vapor_steam_web_refresh)).assertIsNotEnabled()
        compose.runOnIdle { status.value = SteamWebAvailability.READY }
        compose.onNodeWithContentDescription(label(R.string.vapor_steam_web_refresh)).assertIsEnabled()
        compose.runOnIdle { assertEquals(1, calls); assertEquals(1, urls.size); assertEquals(0, destroyed) }
    }

    @Test fun gamepadDirectionsAndABXYUseTheDialogWindowWithoutAccidentalActions() {
        show()
        compose.waitUntil(5000) { urls.size == 1 }
        compose.onNodeWithContentDescription(label(R.string.common_ui_close))
            .performSemanticsAction(SemanticsActions.RequestFocus)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.onNodeWithContentDescription(label(R.string.vapor_steam_web_refresh)).assertIsFocused()
        press(KeyEvent.KEYCODE_BUTTON_X)
        press(KeyEvent.KEYCODE_BUTTON_Y)
        compose.runOnIdle { assertEquals(1, calls); assertEquals(1, urls.size) }
        press(KeyEvent.KEYCODE_BUTTON_A)
        compose.runOnIdle { assertEquals("A activates Refresh exactly once", 2, calls) }
        compose.waitUntil(5000) { urls.size >= 2 }
        compose.runOnIdle { assertEquals(2, calls); assertEquals(listOf(url, url), urls); assertEquals(1, destroyed) }
        // The refreshed WebView may take focus; return to an actual toolbar control before B.
        compose.onNodeWithContentDescription(label(R.string.common_ui_close))
            .performSemanticsAction(SemanticsActions.RequestFocus)
        press(KeyEvent.KEYCODE_BUTTON_B)
        compose.runOnIdle { assertFalse(visible.value); assertEquals(2, destroyed) }
    }

    @Test fun realCookieManagerLogoutRemovesSteamHostAndDomainCookiesOnly() {
        val prepared = CountDownLatch(1)
        compose.runOnIdle {
            val cookies = CookieManager.getInstance()
            cookies.setCookie("https://store.steampowered.com/", "steamLoginSecure=fixture; Path=/; Secure; HttpOnly")
            cookies.setCookie("https://store.steampowered.com/", "sessionid=fixture; Domain=.steampowered.com; Path=/; Secure")
            cookies.setCookie("https://steamcommunity.com/", "steamLoginSecure=fixture; Domain=steamcommunity.com; Path=/; Secure; HttpOnly")
            cookies.setCookie("https://other-store.example/", "account=fixture; Path=/; Secure") { prepared.countDown() }
        }
        assertTrue(prepared.await(5, TimeUnit.SECONDS))
        val cleared = CountDownLatch(1)
        compose.runOnIdle {
            SteamWebSession.clearCookies()
            CookieManager.getInstance().setCookie("https://other-store.example/", "barrier=fixture; Path=/; Secure") { cleared.countDown() }
        }
        assertTrue(cleared.await(5, TimeUnit.SECONDS))
        compose.runOnIdle {
            val cookies = CookieManager.getInstance()
            SteamWebSession.hosts.forEach { host ->
                val remaining = cookies.getCookie("https://$host/").orEmpty()
                assertFalse(remaining.contains("steamLoginSecure=")); assertFalse(remaining.contains("sessionid="))
            }
            assertTrue(cookies.getCookie("https://other-store.example/").contains("account=fixture"))
            cookies.setCookie("https://other-store.example/", "account=; Max-Age=0; Path=/; Secure")
            cookies.setCookie("https://other-store.example/", "barrier=; Max-Age=0; Path=/; Secure")
        }
    }
}
