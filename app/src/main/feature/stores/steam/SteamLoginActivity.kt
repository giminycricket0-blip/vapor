package com.winlator.cmod.feature.stores.steam

import android.content.Intent
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.winlator.cmod.feature.stores.steam.enums.LoginResult
import com.winlator.cmod.feature.stores.steam.enums.LoginScreen
import com.winlator.cmod.feature.stores.steam.service.SteamService
import com.winlator.cmod.feature.stores.steam.ui.SteamLoginScreen
import com.winlator.cmod.feature.stores.steam.ui.SteamLoginViewModel
import com.winlator.cmod.runtime.system.SessionKeepAliveService
import com.winlator.cmod.shared.android.FixedFontScaleComponentActivity
import com.winlator.cmod.shared.theme.WinNativeTheme
import timber.log.Timber

/** Activity and service lifecycle only; the native screen renders the ViewModel's existing state. */
class SteamLoginActivity : FixedFontScaleComponentActivity() {
    private val viewModel: SteamLoginViewModel by viewModels()
    private val controllerInput by lazy {
        com.winlator.cmod.runtime.input.controls.SteamControllerUiSession(this)
    }

    override fun onResume() {
        super.onResume()
        controllerInput.resume()
    }

    override fun onPause() {
        controllerInput.pause()
        super.onPause()
    }

    override fun onStop() {
        controllerInput.stop()
        super.onStop()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        controllerInput.isShadow(event.device) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean =
        controllerInput.isShadow(event.device) || super.dispatchGenericMotionEvent(event)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            startService(Intent(this, SteamService::class.java))
            SessionKeepAliveService.startComponent(this, SessionKeepAliveService.COMPONENT_STEAM, "Steam Login Service")
        } catch (error: Exception) {
            Timber.e(error, "Failed to start SteamService from SteamLoginActivity")
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            WinNativeTheme {
                val state by viewModel.loginState.collectAsStateWithLifecycle()
                LaunchedEffect(state.loginResult) {
                    if (state.loginResult == LoginResult.Success) {
                        setResult(RESULT_OK)
                        finish()
                    }
                }
                LaunchedEffect(state.loginScreen) {
                    if (state.loginScreen == LoginScreen.CREDENTIAL) viewModel.onStartQrLogin()
                }
                val back = {
                    if (state.loginScreen == LoginScreen.TWO_FACTOR) viewModel.onShowLoginScreen(LoginScreen.CREDENTIAL)
                    else finish()
                }
                BackHandler(onBack = back)
                SteamLoginScreen(state,
                    onUsername = viewModel::setUsername, onPassword = viewModel::setPassword,
                    onRemember = viewModel::setRememberSession, onCode = viewModel::setTwoFactorCode,
                    onSignIn = viewModel::onCredentialLogin, onSubmitCode = viewModel::submitTwoFactor,
                    onQrRetry = viewModel::onQrRetry, onBack = back)
            }
        }
    }

    override fun onDestroy() {
        controllerInput.stop()
        SessionKeepAliveService.stopComponent(this, SessionKeepAliveService.COMPONENT_STEAM)
        if (viewModel.loginState.value.loginResult != LoginResult.Success) SteamService.stop()
        super.onDestroy()
    }
}
