package com.winlator.cmod.app.shell

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StoreGameDetailPlayTest {
    @get:Rule val compose = createComposeRule()

    private fun screen(installed: Boolean = true, enabled: Boolean = true, loading: Boolean = false,
                       update: Boolean = false, onPlay: (() -> Unit)? = {}, onInstall: () -> Unit = {}) {
        compose.setContent {
            StoreGameDetailScreen(
                title = "Test demo", subtitle = "Test studio", sourceLabel = "Steam",
                heroImageUrl = null, isLoading = loading, isInstalled = installed,
                installPathDisplay = "/test/demo", downloadSize = 100L, installSize = 200L,
                availableBytes = 1000L, isInstallEnabled = true, customPathLabel = "Custom",
                showUninstall = false, showUpdateCheck = true, isUpdateAvailable = update,
                onBack = {}, onPlay = onPlay, isPlayEnabled = enabled, onInstall = onInstall,
            )
        }
    }

    @Test fun installedGameCanLaunchFromItsStorePage() {
        var launches = 0
        screen(onPlay = { launches++ })
        compose.onNodeWithText("Play").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, launches) }
        compose.onNodeWithText("Download").assertDoesNotExist()
    }

    @Test fun uninstalledGameOffersDownloadInstead() {
        var downloads = 0
        screen(installed = false, onInstall = { downloads++ })
        compose.onNodeWithText("Play").assertDoesNotExist()
        compose.onNodeWithText("Download").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, downloads) }
    }

    @Test fun pendingDownloadPreventsLaunchAndExplainsWhy() {
        var launches = 0
        screen(enabled = false, onPlay = { launches++ })
        compose.onNodeWithText("Play").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Finish or cancel this game’s download before playing.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, launches) }
    }

    @Test fun metadataLoadingPreventsLaunch() {
        screen(loading = true)
        compose.onNodeWithText("Play").assertIsNotEnabled()
    }

    @Test fun availableUpdateDoesNotHidePlay() {
        screen(update = true)
        compose.onNodeWithText("Play").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Download update", ignoreCase = true).assertIsDisplayed()
    }

    @Test fun callersWithoutALaunchActionRemainUnchanged() {
        screen(onPlay = null)
        compose.onNodeWithText("Play").assertDoesNotExist()
    }
}
