package com.winlator.cmod.feature.setup

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.winlator.cmod.shared.theme.WinNativeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrepareVaporScreenTest {
    @get:Rule val compose = createComposeRule()
    private var storageRequests = 0
    private var starts = 0
    private var continues = 0
    private var advanced = 0

    private fun show(storage: Boolean = true, busy: Boolean = false, ready: Boolean = false,
        progress: Float? = null, error: String? = null) {
        compose.setContent {
            WinNativeTheme {
                PrepareVaporScreen(storage, true, busy, ready, "Downloading game support (1 of 3)", progress, error, "Proton test version",
                    onGrantStorage = { storageRequests++ }, onNotifications = {}, onPrepare = { starts++ },
                    onContinue = { continues++ }, onAdvanced = { advanced++ })
            }
        }
    }

    @Test fun missingPermissionOffersAccessBeforePreparation() {
        show(storage = false)
        compose.onNodeWithText("Allow file access").performScrollTo().performClick()
        compose.onNodeWithText("Continue to library").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, storageRequests); assertEquals(0, starts) }
    }

    @Test fun startAndAdvancedHaveSeparateActions() {
        show()
        compose.onAllNodesWithText("Prepare Vapor").filterToOne(hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Advanced setup").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, starts); assertEquals(1, advanced); assertEquals(0, continues) }
    }

    @Test fun activePreparationShowsStepProgressAndCannotBeFinishedOrSwitched() {
        show(busy = true, progress = 0.42f)
        compose.onNodeWithText("42% of this step").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Continue to library").assertDoesNotExist()
        compose.onNodeWithText("Advanced setup").assertDoesNotExist()
        compose.onNodeWithText("Retry preparation").assertDoesNotExist()
    }

    @Test fun unknownProgressDoesNotInventAPercentage() {
        show(busy = true)
        compose.onAllNodes(hasText("% of this step", substring = true)).assertCountEquals(0)
        compose.onNodeWithText("Downloading game support (1 of 3)").performScrollTo().assertIsDisplayed()
    }

    @Test fun failureOffersRetryAndKeepsContinueUnavailable() {
        show(error = "The download did not finish.")
        compose.onNodeWithText("The download did not finish.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retry preparation").performScrollTo().performClick()
        compose.onNodeWithText("Continue to library").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, starts); assertEquals(0, continues) }
    }

    @Test fun successfulPreparationOffersLibraryAndOptionalDetails() {
        show(ready = true)
        compose.onNodeWithText("Vapor is prepared").assertExists()
        compose.onNodeWithText("Continue to library").performScrollTo().performClick()
        compose.onNodeWithText("Proton test version").assertDoesNotExist()
        compose.onNodeWithText("Component details").performScrollTo().performClick()
        compose.onNodeWithText("Proton test version").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, continues); assertEquals(0, starts) }
    }
}
