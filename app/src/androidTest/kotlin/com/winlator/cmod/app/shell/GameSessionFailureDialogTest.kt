package com.winlator.cmod.app.shell

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameSessionFailureDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun preparationFailureOffersGameSettingsAndClose() {
        var settingsOpened = 0
        var closed = 0
        compose.setContent {
            GameSessionFailureDialog(
                GameSessionFailure("Test demo", "Screen settings could not be prepared.", "/test/demo.desktop"),
                onClose = { closed++ }, onSettings = { settingsOpened++ },
            )
        }
        compose.onNodeWithText("Screen settings could not be prepared.").assertIsDisplayed()
        compose.onNodeWithText("Game settings").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, settingsOpened) }
        compose.onNodeWithText("Close").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, closed) }
    }

    @Test fun steamSessionFailuresRetainAccountSettingsAction() {
        var settingsOpened = 0
        compose.setContent {
            GameSessionFailureDialog(GameSessionFailure("Test demo", "Steam sign-in failed."),
                onClose = {}, onSettings = { settingsOpened++ })
        }
        compose.onNodeWithText("Game settings").assertDoesNotExist()
        compose.onNodeWithText("Open Settings").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, settingsOpened) }
    }
}
