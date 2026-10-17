package com.winlator.cmod.feature.settings

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.winlator.cmod.runtime.linux.LinuxClientInstaller.State
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SteamClientDialogTest {
    @get:Rule val compose = createComposeRule()
    private var opened = 0
    private var started = 0
    private var removed = 0
    private var closed = 0

    private fun show(state: State) {
        compose.setContent {
            LinuxClientDialog(state, onStart = { started++ }, onCancelInstall = {},
                onUninstall = { removed++; true }, onOpen = { opened++ }, onDismiss = { closed++ })
        }
    }

    @Test fun installedClientOpensFromItsAdvancedPanelAndCanClose() {
        show(State.Installed)
        compose.onNodeWithText("Full Steam client (experimental)").assertIsDisplayed()
        compose.onNodeWithText("Open Steam client").assertIsDisplayed().performClick()
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertEquals(1, opened); assertEquals(1, closed); assertEquals(0, started) }
    }

    @Test fun availableUpdateDoesNotPreventOpeningInstalledClient() {
        show(State.UpdateAvailable(2, false))
        compose.onNodeWithText("Open Steam client").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, opened); assertEquals(0, started) }
    }

    @Test fun missingClientOffersDownloadWithoutAnOpenAction() {
        show(State.Missing)
        compose.onNodeWithText("Open Steam client").assertDoesNotExist()
        compose.onNodeWithText("Download").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, started); assertEquals(0, opened) }
    }

    @Test fun uninstallStillRequiresExplicitConfirmation() {
        show(State.Installed)
        compose.onNodeWithText("Uninstall").performClick()
        compose.runOnIdle { assertEquals(0, removed) }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Open Steam client").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, removed) }
    }
}
