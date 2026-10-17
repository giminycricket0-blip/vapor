package com.winlator.cmod.app.shell

import androidx.activity.ComponentActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.winlator.cmod.R
import com.winlator.cmod.feature.settings.StoreCard
import com.winlator.cmod.feature.settings.MoreStoresSettings
import com.winlator.cmod.feature.settings.StoreState
import com.winlator.cmod.shared.theme.WinNativeAccent
import com.winlator.cmod.shared.theme.WinNativeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Callback checks only: no Steam account, storage setting or media volume is changed. */
@RunWith(AndroidJUnit4::class)
class SteamShellUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun label(id: Int) = compose.activity.getString(id)

    @Test fun signedOutAccountOffersSignIn() {
        var signs = 0
        compose.setContent {
            WinNativeTheme { StoreCard("Steam", Icons.Outlined.Gamepad, WinNativeAccent, false, { signs++ }, {}) }
        }
        compose.onNodeWithText(label(R.string.common_ui_sign_in)).assertIsDisplayed().performClick()
        compose.onNodeWithText(label(R.string.common_ui_sign_out)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, signs) }
    }

    @Test fun cancellingConfirmationKeepsAccountSignedIn() {
        var signsOut = 0
        compose.setContent {
            WinNativeTheme { StoreCard("Steam", Icons.Outlined.Gamepad, WinNativeAccent, true, {}, { signsOut++ }) }
        }
        compose.onNodeWithText(label(R.string.common_ui_sign_out)).performClick()
        compose.runOnIdle { assertEquals(0, signsOut) }
        compose.onNodeWithText(label(R.string.common_ui_cancel)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, signsOut) }
        compose.onNodeWithText(label(R.string.common_ui_signed_in)).assertIsDisplayed()
    }

    @Test fun confirmingSignOutCallsExistingActionOnce() {
        var signsOut = 0
        compose.setContent {
            WinNativeTheme { StoreCard("Steam", Icons.Outlined.Gamepad, WinNativeAccent, true, {}, { signsOut++ }) }
        }
        compose.onNodeWithText(label(R.string.common_ui_sign_out)).performClick()
        compose.onAllNodesWithText(label(R.string.common_ui_sign_out)).onLast().performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, signsOut) }
        compose.onNodeWithText(label(R.string.common_ui_cancel)).assertDoesNotExist()
    }

    @Test fun accountActionRemainsReachableAtNarrowWidthAndLargeText() {
        var signs = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                WinNativeTheme {
                    Box(Modifier.width(260.dp).padding(12.dp)) {
                        StoreCard("Steam", Icons.Outlined.Gamepad, WinNativeAccent, false, { signs++ }, {})
                    }
                }
            }
        }
        compose.onNodeWithText(label(R.string.common_ui_sign_in)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, signs) }
    }

    @Test fun quickAccessRoutesLibrarySettingsAndCloseWithoutAutomaticActions() {
        var library = 0; var settings = 0; var closes = 0
        compose.setContent {
            WinNativeTheme {
                VaporQuickAccess(compose.activity, { closes++ }, { settings++ }, { library++ })
            }
        }
        compose.runOnIdle { assertEquals(0, library + settings + closes) }
        compose.onNodeWithText(label(R.string.vapor_library_options)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.vapor_all_settings)).performScrollTo().performClick()
        compose.onNodeWithContentDescription(label(R.string.vapor_close)).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, library); assertEquals(1, settings); assertEquals(1, closes)
        }
    }

    @Test fun moreStoresOffersExistingAccountsAndBrowseActionsWithoutAutomaticSignIn() {
        val browsed = mutableListOf<String>(); var signedOut = 0; var signedIn = 0
        compose.setContent { WinNativeTheme {
            Box(Modifier.verticalScroll(rememberScrollState())) {
                MoreStoresSettings(StoreState(isGogLoggedIn = true),
                    { signedIn++ }, { signedOut++ }, { signedIn++ }, { signedOut++ },
                    { signedIn++ }, { signedOut++ }, { browsed.add(it) }, {}, {}, {})
            }
        } }
        compose.onNodeWithText("More stores").assertIsDisplayed()
        compose.onNodeWithText("Steam").assertDoesNotExist()
        compose.onNodeWithText("Browse Epic Games").performScrollTo().performClick()
        compose.onNodeWithText("Browse GOG").performScrollTo().performClick()
        compose.onNodeWithText("Browse itch.io").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("epic", "gog", "itch"), browsed)
            assertEquals(0, signedIn); assertEquals(0, signedOut)
        }
    }

    @Test fun optionalStoresBrowseRemainsReachableWithLargeText() {
        var browsed = ""
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                WinNativeTheme {
                    Box(Modifier.width(260.dp).verticalScroll(rememberScrollState())) {
                        MoreStoresSettings(StoreState(), {}, {}, {}, {}, {}, {}, { browsed = it }, {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithText("Browse itch.io").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("itch", browsed) }
    }
}
