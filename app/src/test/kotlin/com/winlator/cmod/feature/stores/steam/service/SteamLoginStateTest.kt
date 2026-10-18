package com.winlator.cmod.feature.stores.steam.service

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.winlator.cmod.feature.stores.steam.enums.LoginScreen
import com.winlator.cmod.feature.stores.steam.ui.SteamLoginViewModel
import com.winlator.cmod.feature.stores.steam.wnsteam.WnAuthenticator
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Synthetic Steam Guard challenges; no service, account or native authentication is started. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SteamLoginStateTest {
    private val store = ViewModelStore()
    private lateinit var model: SteamLoginViewModel
    private lateinit var authenticator: WnAuthenticator

    @Before fun create() {
        assertNull(SteamService.instance)
        model = SteamLoginViewModel()
        store.put("login", model)
        authenticator = ReflectionHelpers.getField(model, "authenticator")
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After fun close() { store.clear(); shadowOf(Looper.getMainLooper()).idle() }

    @Test fun leavingCodeEntryCancelsItsPendingChallenge() {
        val old = authenticator.getDeviceCode(false)
        shadowOf(Looper.getMainLooper()).idle()
        model.onShowLoginScreen(LoginScreen.CREDENTIAL)
        assertTrue(old.isCancelled)
        assertEquals("", model.loginState.value.twoFactorCode)
    }

    @Test fun retryDeliversTheCodeOnlyToTheCurrentChallenge() {
        val old = authenticator.getDeviceCode(false)
        shadowOf(Looper.getMainLooper()).idle()
        model.onShowLoginScreen(LoginScreen.CREDENTIAL)
        val current = authenticator.getEmailCode("v***@example.test", false)
        shadowOf(Looper.getMainLooper()).idle()
        model.setTwoFactorCode(" ab123 ")
        model.submitTwoFactor()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(old.isCancelled)
        assertTrue(current.isDone)
        assertEquals("AB123", current.getNow("not completed"))
        assertTrue(model.loginState.value.isLoggingIn)
    }

    @Test fun editingARejectedCodeClearsItsInlineError() {
        authenticator.getDeviceCode(true)
        assertTrue(model.loginState.value.previousCodeIncorrect)
        model.setTwoFactorCode("new12")
        assertFalse(model.loginState.value.previousCodeIncorrect)
    }

    @Test fun closingLoginReleasesAPendingCodeRequest() {
        val pending = authenticator.getDeviceCode(false)
        shadowOf(Looper.getMainLooper()).idle()
        store.clear()
        assertTrue(pending.isCancelled)
    }
}
