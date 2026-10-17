package com.winlator.cmod.feature.library

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameSettingsNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val state = GameSettingsStateHolder().apply {
        name.value = "Test game"
        isLoaded.value = true
        isSteamGame.value = true
    }
    private var saves = 0
    private var cancels = 0
    private val callbacks = object : GameSettingsCallbacks {
        override fun onConfirm() { saves++ }
        override fun onDismiss() { cancels++ }
        override fun onAddToHomeScreen() {}
        override fun onRemoveEnvVar(index: Int) {}
        override fun onUpdateWinComponent(isDirectX: Boolean, index: Int, newValue: Int) {}
    }

    @Test fun graphicsAndControlsAreDirectlyReachable() {
        compose.setContent { GameSettingsContent(state, callbacks) }
        compose.onNode(hasText("Graphics") and hasClickAction())
            .assertHeightIsAtLeast(48.dp).performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(1, state.currentSection.intValue) }
        compose.onNode(hasText("Input Controls") and hasClickAction())
            .performScrollTo().assertHeightIsAtLeast(48.dp).performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(2, state.currentSection.intValue) }
        compose.onNodeWithText("Profile").assertIsDisplayed()
    }

    @Test fun saveAndCancelRemainReachableAtTouchSize() {
        compose.setContent { GameSettingsContent(state, callbacks) }
        compose.onNodeWithText("Save").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Cancel").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle {
            assertEquals(1, saves)
            assertEquals(1, cancels)
        }
    }
}
