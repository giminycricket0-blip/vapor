package com.winlator.cmod.feature.stores.steam.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.cmod.feature.settings.SteamAccountSetupScreen
import com.winlator.cmod.feature.stores.steam.enums.LoginResult
import com.winlator.cmod.feature.stores.steam.enums.LoginScreen
import com.winlator.cmod.feature.stores.steam.ui.data.UserLoginState
import com.winlator.cmod.shared.theme.WinNativeTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production UI with synthetic authentication state. No Steam service or account is started. */
@RunWith(AndroidJUnit4::class)
class SteamOnboardingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sample = UserLoginState(qrCode = "Vapor UI sample. Not a Steam sign-in code.")
    private val state = mutableStateOf(sample)
    private var signs = 0
    private var retries = 0
    private var backs = 0
    private var confirms = 0
    private var connects = 0
    private var continues = 0

    @Composable private fun Login() {
        SteamLoginScreen(state.value,
            onUsername = { state.value = state.value.copy(username = it) },
            onPassword = { state.value = state.value.copy(password = it) },
            onRemember = { state.value = state.value.copy(rememberSession = it) },
            onCode = { state.value = state.value.copy(twoFactorCode = it, previousCodeIncorrect = false) },
            onSignIn = { signs++; state.value = state.value.copy(isLoggingIn = true, loginResult = LoginResult.InProgress) },
            onSubmitCode = { confirms++; state.value = state.value.copy(isLoggingIn = true) },
            onQrRetry = { retries++ }, onBack = { backs++ })
    }

    private fun login(initial: UserLoginState = sample, width: Int = 420, scale: Float = 1f, preview: Boolean = false) {
        state.value = initial
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                WinNativeTheme {
                    Box(Modifier.then(if (width > 0) Modifier.widthIn(max = width.dp) else Modifier).fillMaxSize()) {
                        Login()
                        if (preview) Text("UI preview · sample sign-in; no Steam account",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp,
                            modifier = Modifier.align(Alignment.BottomEnd).background(MaterialTheme.colorScheme.background).padding(4.dp))
                    }
                }
            }
        }
    }

    private fun account(connected: Boolean = false, width: Int = 420, scale: Float = 1f, dialog: Boolean = false, preview: Boolean = false) {
        compose.setContent {
            val density = LocalDensity.current
            val content: @Composable () -> Unit = {
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    WinNativeTheme {
                        Box(Modifier.then(if (width > 0) Modifier.widthIn(max = width.dp) else Modifier).fillMaxSize()) {
                            SteamAccountSetupScreen(connected, { connects++ }, { continues++ })
                            if (preview) Text("UI preview · sample setup; no Steam account",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp,
                                modifier = Modifier.align(Alignment.BottomEnd).background(MaterialTheme.colorScheme.background).padding(4.dp))
                        }
                    }
                }
            }
            if (dialog) Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false), content = content)
            else content()
        }
    }

    private fun credentials() {
        compose.onNode(hasText("Account name") and hasClickAction()).performScrollTo().performClick()
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

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "onboarding-review").apply { mkdirs() }
        val orientation = if (context.resources.configuration.screenWidthDp > context.resources.configuration.screenHeightDp) "landscape" else "portrait"
        File(directory, "$name-$orientation.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun welcomeKeepsConnectAndSkipSeparate() {
        account()
        compose.onNodeWithText("Connect Steam").performScrollTo().performClick()
        compose.onNodeWithText("Set up later").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, connects); assertEquals(1, continues) }
    }

    @Test fun connectedAccountContinuesWithoutRequestingAnotherLogin() {
        account(connected = true)
        compose.onNodeWithText("Steam connected").assertExists()
        compose.onNodeWithText("Set up later").assertDoesNotExist()
        compose.onNodeWithText("Continue to Vapor").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, connects); assertEquals(1, continues) }
    }

    @Test fun accountDialogControllerConfirmsOnceAndRejectsOtherFaceButtons() {
        account(dialog = true)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Connect Steam").assertIsFocused()
        press(KeyEvent.KEYCODE_BUTTON_X)
        press(KeyEvent.KEYCODE_BUTTON_Y)
        compose.runOnIdle { assertEquals(0, connects) }
        press(KeyEvent.KEYCODE_BUTTON_A)
        compose.runOnIdle { assertEquals(1, connects) }
        press(KeyEvent.KEYCODE_BUTTON_B)
        compose.runOnIdle { assertEquals(1, continues) }
    }

    @Test fun phoneMethodSwitchPreservesEnteredCredentials() {
        login()
        compose.onNodeWithText("Steam account name").assertDoesNotExist()
        credentials()
        compose.onNodeWithText("Steam account name").performTextInput("sample_account")
        compose.onNodeWithText("Password").performTextInput("sample-password")
        compose.onNode(hasText("QR code") and hasClickAction()).performScrollTo().performClick()
        credentials()
        compose.runOnIdle { assertEquals("sample_account", state.value.username); assertEquals("sample-password", state.value.password) }
    }

    @Test fun credentialsRequireValuesAndBusyLoginCannotBeRepeated() {
        login()
        credentials()
        compose.onNodeWithText("Sign In").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Steam account name").performTextInput("sample_account")
        compose.onNodeWithText("Password").performTextInput("sample-password")
        compose.onNodeWithText("Sign In").performScrollTo().performClick()
        compose.onNodeWithText("Signing in…").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, signs) }
    }

    @Test fun passwordVisibilityAndRememberLabelHaveAccessibleActions() {
        login(sample.copy(username = "sample_account", password = "sample-password"))
        credentials()
        compose.onNodeWithContentDescription("Show password").performClick()
        compose.onNodeWithContentDescription("Hide password").assertExists().performClick()
        compose.onNodeWithText("Keep me signed in").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(state.value.rememberSession) }
    }

    @Test fun qrFailureCanBeRetriedOrSwitchedToCredentials() {
        login(sample.copy(qrCode = null, isQrFailed = true))
        compose.onNodeWithText("Get a new code").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        credentials()
        compose.onNodeWithText("Steam account name").assertExists()
    }

    @Test fun failedLoginShowsRecoveryWithoutBlamingOnlyThePassword() {
        login(sample.copy(username = "sample_account", password = "sample-password"))
        credentials()
        compose.onNodeWithText("Sign In").performScrollTo().performClick()
        compose.runOnIdle { state.value = state.value.copy(isLoggingIn = false, loginResult = LoginResult.Failed) }
        compose.onNodeWithText("Unable to sign in.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sign In").performScrollTo().assertIsEnabled()
    }

    @Test fun emailCodeRejectionCanBeEditedAndConfirmed() {
        login(sample.copy(loginScreen = LoginScreen.TWO_FACTOR, loginResult = LoginResult.EmailAuth,
            lastTwoFactorMethod = "email_code", email = "v***@example.test", previousCodeIncorrect = true))
        compose.onNodeWithText("That code was not accepted.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Code").performScrollTo().performTextInput("AB123")
        compose.onNodeWithText("That code was not accepted.", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Confirm code").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, confirms) }
        compose.onNodeWithText("Checking code…").assertIsNotEnabled()
    }

    @Test fun deviceApprovalFailureOffersAnotherMethodInsteadOfEndlessWaiting() {
        login(sample.copy(loginScreen = LoginScreen.TWO_FACTOR, loginResult = LoginResult.DeviceConfirm,
            lastTwoFactorMethod = "steam_guard", isLoggingIn = true))
        compose.onNodeWithText("Waiting for approval…").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(loginResult = LoginResult.Failed, isLoggingIn = false) }
        compose.onNodeWithText("Waiting for approval…").assertDoesNotExist()
        compose.onNodeWithText("Use another sign-in method").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test fun largeTextNarrowLoginKeepsRecoveryAndBackReachable() {
        login(sample.copy(qrCode = null, isQrFailed = true), width = 280, scale = 1.6f)
        compose.onNodeWithText("Get a new code").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Back").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(1, backs) }
    }

    @Test fun largeTextNarrowWelcomeKeepsPrimaryAndSkipReachable() {
        account(width = 280, scale = 1.6f)
        compose.onNodeWithText("Connect Steam").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Set up later").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, connects); assertEquals(1, continues) }
    }

    @Test fun previewWelcome() {
        account(width = 0, preview = true)
        compose.onNodeWithText("Connect Steam").assertIsDisplayed()
        capture("welcome")
    }

    @Test fun previewLogin() {
        login(width = 0, preview = true)
        compose.onNodeWithText("Sign in with QR Code").assertExists()
        capture("login")
    }

    @Test fun previewSteamGuard() {
        login(sample.copy(loginScreen = LoginScreen.TWO_FACTOR, loginResult = LoginResult.DeviceConfirm,
            lastTwoFactorMethod = "steam_guard", isLoggingIn = true), width = 0, preview = true)
        compose.onNodeWithText("Waiting for approval…").assertIsDisplayed()
        capture("steam-guard")
    }
}
