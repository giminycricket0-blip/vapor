package com.winlator.cmod.app.shell

import android.graphics.Bitmap
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.cmod.feature.stores.steam.service.SteamCloseSync
import com.winlator.cmod.shared.theme.WinNativeTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SteamCloseSyncUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun screen(onUninstall: () -> Unit) {
        compose.setContent {
            WinNativeTheme {
                val active by SteamCloseSync.activeAppIds.collectAsStateWithLifecycle()
                LibraryGameLaunchScreen(
                    appName = "Test game", subtitle = "", sourceLabel = "Steam",
                    heroImageUrl = null, customHeroImageCacheKey = null,
                    releaseDateEpochSeconds = 0, totalPlaytimeMillis = 0, playCount = 0,
                    lastPlayedMillis = 0, installSizeText = null, removalKeepsFiles = false,
                    hasPinnedShortcut = false, showBootToDesktop = false,
                    uninstallEnabled = 22150 !in active,
                    onBack = {}, onPlay = {}, onSettings = {}, onBootToDesktop = {},
                    onShortcut = {}, onCloudSaves = {}, onUninstall = onUninstall,
                )
            }
        }
    }

    @Test fun exitUploadDisablesUninstallAndExplainsWhy() {
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        val upload = SteamCloseSync.reserve(22150)
        var uninstalls = 0
        try {
            screen { uninstalls++ }
            compose.onNodeWithText("Save sync is still finishing. Your local saves are kept.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Manage").performScrollTo().performClick()
            compose.onNodeWithText("Finishing save sync").performScrollTo().assertIsDisplayed().assertIsNotEnabled().performClick()
            compose.onNodeWithText("Uninstall").assertDoesNotExist()
            compose.runOnIdle { assertEquals(0, uninstalls) }
            capture("vapor-saveguard-test.png")
        } finally { upload.complete() }
        compose.onNodeWithText("Uninstall").performScrollTo().assertIsEnabled()
    }

    @Test fun anOpenConfirmationClosesWhenUploadStarts() {
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        var uninstalls = 0
        screen { uninstalls++ }
        compose.onNodeWithText("Manage").performScrollTo().performClick()
        compose.onNodeWithText("Uninstall").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Uninstall Game", ignoreCase = true).assertIsDisplayed()
        val upload = SteamCloseSync.reserve(22150)
        try {
            compose.onNodeWithText("Uninstall Game", ignoreCase = true).assertDoesNotExist()
            compose.onNodeWithText("Save sync is still finishing. Your local saves are kept.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Manage").performScrollTo().performClick()
            compose.onNodeWithText("Finishing save sync").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
            compose.runOnIdle { assertEquals(0, uninstalls) }
            capture("vapor-saveguard-landscape-test.png")
        } finally { upload.complete() }
        compose.onNodeWithText("Uninstall").assertIsEnabled().performClick()
        compose.onNode(hasText("Uninstall", ignoreCase = true) and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertEquals(1, uninstalls) }
    }
}
