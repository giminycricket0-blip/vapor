package com.winlator.cmod.feature.library

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.feature.stores.steam.utils.PrefManager
import com.winlator.cmod.shared.theme.WinNativeTheme
import com.winlator.cmod.shared.ui.nav.PANE_DIR_RIGHT
import com.winlator.cmod.shared.ui.nav.PANE_DIR_LEFT
import com.winlator.cmod.shared.ui.nav.PANE_DIR_UP
import com.winlator.cmod.shared.ui.nav.PANE_DIR_DOWN
import com.winlator.cmod.shared.ui.nav.PANE_DIR_ACTIVATE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w1000dp-h700dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameSettingsLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val state = GameSettingsStateHolder().apply {
        name.value = "A game with a long title and an expansion"
        isLoaded.value = true
        isSteamGame.value = true
        // Preloaded presentation fixture; native shader discovery is a separate device check.
        frameGenShaderState.intValue = FRAMEGEN_SHADERS_MISSING
    }
    private val nav = GameSettingsNav()
    private val panelWidth = mutableIntStateOf(520)
    private var panelHeight = 400
    private var saves = 0
    private var cancels = 0
    private val callbacks = object : GameSettingsCallbacks {
        override fun onConfirm() { saves++ }
        override fun onDismiss() { cancels++ }
        override fun onAddToHomeScreen() {}
        override fun onRemoveEnvVar(index: Int) {}
        override fun onUpdateWinComponent(isDirectX: Boolean, index: Int, newValue: Int) {}
    }

    @Before fun preferences() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("settings-layout-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        ReflectionHelpers.setField(PrefManager, "prefs", prefs)
    }
    @After fun clearPreferences() { ReflectionHelpers.setField(PrefManager, "prefs", null) }

    private fun label(id: Int) = compose.activity.getString(id)
    private fun section(id: Int) = compose.onNode(hasText(label(id)) and hasClickAction())

    private fun settings(width: Int, height: Int, fontScale: Float = 1f) {
        panelWidth.intValue = width
        panelHeight = height
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                WinNativeTheme {
                    Box(Modifier.size(panelWidth.intValue.dp, height.dp)) { GameSettingsContent(state, callbacks, nav) }
                }
            }
        }
    }

    @Test fun aNarrowPanelUsesCompactNavigationInsideALargeLandscapeWindow() {
        settings(520, 400)
        capture("narrow-panel.png")
        val general = section(R.string.settings_general_title).getUnclippedBoundsInRoot()
        val graphics = section(R.string.common_ui_graphics).getUnclippedBoundsInRoot()
        assertEquals("Narrow panel should place its sections in the same horizontal rail", general.top, graphics.top)
    }

    @Test fun aWidePanelRetainsSidebarNavigation() {
        settings(920, 600)
        val general = section(R.string.settings_general_title).getUnclippedBoundsInRoot()
        val graphics = section(R.string.common_ui_graphics).getUnclippedBoundsInRoot()
        assertTrue(graphics.top > general.top)
        capture("wide-panel.png")
    }

    @Test fun compactHeaderGivesTheTitleRoomAndRetainsFullSizeActionsAtLargeText() {
        settings(300, 600, fontScale = 1.5f)
        val title = compose.onNode(hasText(state.name.value) and !hasSetTextAction())
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("The title should not compete with action buttons", title.right - title.left >= 180.dp)
        for (id in listOf(R.string.common_ui_save, R.string.common_ui_cancel)) {
            val bounds = compose.onNodeWithText(label(id)).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue(bounds.bottom - bounds.top >= 48.dp)
            assertTrue(bounds.left >= 0.dp && bounds.right <= 300.dp)
        }
        capture("portrait-large-text.png")
    }

    @Test fun saveWaitsForLoadedStateAndActionsKeepTheirExistingCallbacks() {
        state.isLoaded.value = false
        settings(360, 600)
        compose.onNodeWithText(label(R.string.common_ui_save)).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, saves); state.isLoaded.value = true }
        compose.onNodeWithText(label(R.string.common_ui_save)).assertIsEnabled().performClick()
        compose.onNodeWithText(label(R.string.common_ui_cancel)).performClick()
        compose.runOnIdle { assertEquals(1, saves); assertEquals(1, cancels) }
    }

    @Test fun graphicsAndInputStillOpenTheirExistingSections() {
        settings(520, 400)
        section(R.string.common_ui_graphics).performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(1, state.currentSection.intValue) }
        section(R.string.common_ui_input_controls).performClick().assertIsSelected()
        compose.onNodeWithText(label(R.string.common_ui_profile)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, state.currentSection.intValue) }
    }

    @Test fun pairedFieldsStackInsideANarrowPanel() {
        settings(520, 600)
        section(R.string.common_ui_input_controls).performClick()
        val profile = compose.onNodeWithText(label(R.string.common_ui_profile)).getUnclippedBoundsInRoot()
        val count = compose.onNodeWithText(label(R.string.num_controllers)).getUnclippedBoundsInRoot()
        assertTrue(count.top > profile.bottom)
        capture("controls-narrow.png")
    }

    @Test fun pairedFieldsRemainSideBySideWhenThereIsRoom() {
        settings(920, 600)
        section(R.string.common_ui_input_controls).performClick()
        val profile = compose.onNodeWithText(label(R.string.common_ui_profile)).getUnclippedBoundsInRoot()
        val count = compose.onNodeWithText(label(R.string.num_controllers)).getUnclippedBoundsInRoot()
        assertEquals(profile.top, count.top)
        assertTrue(count.left > profile.right)
        capture("controls-wide.png")
    }

    @Test fun resizingKeepsTheSelectedPageAndDraft() {
        settings(520, 400)
        section(R.string.common_ui_input_controls).performClick()
        compose.runOnIdle {
            state.name.value = "Unsaved edit"
            panelWidth.intValue = 920
        }
        section(R.string.common_ui_input_controls).assertIsSelected()
        compose.onNodeWithText(label(R.string.common_ui_profile)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(2, state.currentSection.intValue)
            assertEquals("Unsaved edit", state.name.value)
            assertEquals(0, saves)
        }
    }

    @Test fun artworkActionsHaveFullSizeTargetsInANarrowPanel() {
        settings(360, 600)
        for (id in listOf(R.string.shortcuts_list_add_to_home_screen,
                R.string.library_games_scrape_artwork, R.string.shortcuts_library_artwork_open_source)) {
            compose.onNodeWithText(label(id)).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
        capture("artwork-actions.png")
    }

    @Test fun compactControllerRightAndLeftFollowTheVisibleSectionStrip() {
        settings(520, 400)
        compose.runOnIdle {
            nav.dpad(PANE_DIR_RIGHT) // First event reveals the existing controller highlight.
            nav.dpad(PANE_DIR_RIGHT)
        }
        section(R.string.common_ui_graphics).assertIsSelected()
        capture("controller-compact-strip.png")
        compose.runOnIdle {
            assertEquals(false, nav.inContent)
            nav.dpad(PANE_DIR_LEFT)
        }
        section(R.string.settings_general_title).assertIsSelected()
    }

    @Test fun compactControllerReachesActionsAboveTheStripAndReturnsToTheSelectedSection() {
        settings(520, 400)
        section(R.string.common_ui_input_controls).performClick()
        compose.runOnIdle { nav.active = true; nav.dpad(PANE_DIR_UP) }
        compose.runOnIdle {
            assertTrue(nav.onActionRow)
            nav.dpad(PANE_DIR_RIGHT)
        }
        capture("controller-compact-save.png")
        compose.runOnIdle {
            nav.dpad(PANE_DIR_ACTIVATE)
            assertEquals(1, saves)
            nav.dpad(PANE_DIR_DOWN)
            assertEquals(2, nav.sidebarIndex)
        }
        section(R.string.common_ui_input_controls).assertIsSelected()
    }

    @Test fun compactControllerDownEntersTheFormAndUpFromItsFirstControlReturnsToSections() {
        settings(520, 400)
        compose.runOnIdle { nav.active = true; nav.dpad(PANE_DIR_DOWN) }
        compose.runOnIdle { assertTrue(nav.inContent) }
        compose.runOnIdle { nav.dpad(PANE_DIR_UP) }
        compose.runOnIdle { assertEquals(false, nav.inContent); assertEquals(0, nav.sidebarIndex) }
    }

    @Test fun compactControllerCannotSaveBeforeTheEditorHasLoaded() {
        state.isLoaded.value = false
        settings(520, 400)
        compose.runOnIdle {
            nav.active = true
            nav.dpad(PANE_DIR_UP)
            nav.dpad(PANE_DIR_RIGHT)
            nav.dpad(PANE_DIR_ACTIVATE)
            assertEquals(0, saves)
            state.isLoaded.value = true
        }
        compose.runOnIdle { nav.dpad(PANE_DIR_ACTIVATE); assertEquals(1, saves) }
    }

    @Test fun wideControllerStillMovesDownTheSidebarAndRightIntoTheForm() {
        settings(920, 600)
        compose.runOnIdle { nav.active = true; nav.dpad(PANE_DIR_DOWN) }
        section(R.string.common_ui_graphics).assertIsSelected()
        compose.runOnIdle { nav.dpad(PANE_DIR_RIGHT) }
        compose.runOnIdle { assertTrue(nav.inContent); assertEquals(1, state.currentSection.intValue) }
    }

    @Test fun controllerDirectionsFollowTheLayoutAfterResizing() {
        settings(520, 400)
        compose.runOnIdle { nav.active = true; nav.dpad(PANE_DIR_RIGHT) }
        section(R.string.common_ui_graphics).assertIsSelected()
        compose.runOnIdle { panelWidth.intValue = 920 }
        compose.runOnIdle { nav.dpad(PANE_DIR_RIGHT) }
        compose.runOnIdle { assertTrue(nav.inContent); assertEquals(1, state.currentSection.intValue) }
    }

    private fun capture(name: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val density = compose.activity.resources.displayMetrics.density
            val image = Bitmap.createBitmap((panelWidth.intValue * density).toInt(),
                (panelHeight * density).toInt(), Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            File("build/reports/tests/game-settings-polish", name).apply { parentFile!!.mkdirs() }
                .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }
}
