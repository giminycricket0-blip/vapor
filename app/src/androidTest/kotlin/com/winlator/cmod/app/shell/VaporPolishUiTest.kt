package com.winlator.cmod.app.shell

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.cmod.R
import com.winlator.cmod.feature.stores.steam.service.SteamCatalogItem
import com.winlator.cmod.shared.theme.WinNativeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual production components in an account-free activity. No game or Steam traffic. */
@RunWith(AndroidJUnit4::class)
class VaporPolishUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val tabs = listOf("Home" to "home", "Library" to "library", "Store" to "steam", "Downloads" to "downloads")
        .map { UnifiedActivity.TabDef(it.first, it.second) }

    @Test fun drawerEdgeGesturesDoNotStealActionTaps() {
        lateinit var host: UnifiedActivity
        compose.runOnIdle { host = UnifiedActivity() }
        var taps = 0
        var leftOpens = 0
        var rightOpens = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                WinNativeTheme { Box(Modifier.size(360.dp, 240.dp).testTag("drawer-edge-host")) {
                    Box(Modifier.align(androidx.compose.ui.Alignment.CenterStart).padding(start = 16.dp)) {
                        Button(onClick = { taps++ }, modifier = Modifier.size(48.dp).testTag("left-edge-action")) { Text("L") }
                    }
                    Box(Modifier.align(androidx.compose.ui.Alignment.CenterEnd).padding(end = 16.dp)) {
                        Button(onClick = { taps++ }, modifier = Modifier.size(48.dp).testTag("right-edge-action")) { Text("R") }
                    }
                    host.DrawerSwipeHotZone(Modifier.align(androidx.compose.ui.Alignment.CenterStart)) { leftOpens++ }
                    host.DrawerSwipeHotZone(Modifier.align(androidx.compose.ui.Alignment.CenterEnd), isRightSide = true) { rightOpens++ }
                } }
            }
        }
        compose.onNodeWithTag("left-edge-action").performTouchInput { click(Offset(12f, centerY)) }
        compose.onNodeWithTag("right-edge-action").performTouchInput { click(Offset(width - 12f, centerY)) }
        compose.runOnIdle { assertEquals(2, taps); assertEquals(0, leftOpens); assertEquals(0, rightOpens) }
        compose.onNodeWithTag("drawer-edge-host").performTouchInput {
            swipe(Offset(1f, centerY), Offset(100f, centerY), 300)
        }
        compose.onNodeWithTag("drawer-edge-host").performTouchInput {
            swipe(Offset(width - 1f, centerY), Offset(width - 100f, centerY), 300)
        }
        compose.runOnIdle { assertEquals(1, leftOpens); assertEquals(1, rightOpens) }
    }
    private val selected = mutableIntStateOf(1)
    private val query = mutableStateOf(TextFieldValue(""))
    private val expanded = mutableStateOf(false)
    private val installedOnly = mutableStateOf(false)
    private var settings = 0; private var friends = 0; private var quick = 0; private var filters = 0
    private fun label(id: Int) = compose.activity.getString(id)

    @Composable private fun Header() {
        VaporHeader(tabs, selected.intValue, { selected.intValue = it }, query.value, { query.value = it },
            expanded.value, { expanded.value = !expanded.value }, { filters++ }, { friends++ }, { settings++ }, { quick++ }, {},
            libraryInstalledOnly = installedOnly.value, onLibraryViewChange = { installedOnly.value = it })
    }

    @Test fun narrowLargeTextHeaderKeepsEveryUtilityReachableWithoutOverlap() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                WinNativeTheme { Box(Modifier.width(280.dp).background(BgDark)) { Header() } }
            }
        }
        val descriptions = listOf(R.string.vapor_friends, R.string.common_ui_settings, R.string.vapor_quick_access)
        val bounds = descriptions.map { id ->
            compose.onNodeWithContentDescription(label(id)).assertIsDisplayed().performClick().getUnclippedBoundsInRoot()
        }
        assertTrue(bounds.zipWithNext().all { (a, b) -> a.right <= b.left })
        compose.onNodeWithContentDescription(label(R.string.vapor_library_options)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(1, 1, 1, 1), listOf(friends, settings, quick, filters)) }
        compose.onNodeWithText("Downloads").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithContentDescription(label(R.string.vapor_library_options)).assertDoesNotExist()
    }

    @Test fun selectedTabScrollsIntoViewAtDoubleTextSize() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                WinNativeTheme { Box(Modifier.width(320.dp)) { Header() } }
            }
        }
        compose.runOnIdle { selected.intValue = 3 }
        compose.onNodeWithText("Downloads").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithContentDescription(label(R.string.vapor_quick_access)).assertIsDisplayed()
    }

    @Test fun searchCanBeClearedWithoutClosingOrReenteringTheScreen() {
        compose.setContent { WinNativeTheme { Header() } }
        compose.onNodeWithContentDescription(label(R.string.vapor_search)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("missing game")
        compose.onNodeWithContentDescription(label(R.string.workshop_clear_search)).performClick()
        compose.runOnIdle { assertEquals("", query.value.text); assertTrue(expanded.value) }
        compose.onNodeWithContentDescription(label(R.string.vapor_close_search)).assertIsDisplayed()
    }

    @Test fun portraitLibraryCombinesViewsAndActionsInOneCompactRow() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                WinNativeTheme { Box(Modifier.width(360.dp)) { Header() } }
            }
        }
        val all = compose.onNodeWithText("All games").assertIsDisplayed().assertIsSelected().getUnclippedBoundsInRoot()
        val installed = compose.onNodeWithText("Installed").assertIsDisplayed().getUnclippedBoundsInRoot()
        val search = compose.onNodeWithContentDescription(label(R.string.vapor_search)).assertIsDisplayed().getUnclippedBoundsInRoot()
        val options = compose.onNodeWithContentDescription(label(R.string.vapor_library_options)).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(all.right <= installed.left && installed.right <= search.left && search.right <= options.left)
        assertTrue(all.top >= search.top && all.bottom <= search.bottom)
        assertTrue(options.bottom <= 176.dp)
        assertTrue(search.right - search.left >= 48.dp && search.bottom - search.top >= 48.dp)
        assertTrue(options.right - options.left >= 48.dp && options.bottom - options.top >= 48.dp)
        compose.onNodeWithText("Installed").performClick().assertIsSelected()
        compose.onNodeWithText("All games").assertIsNotSelected()
    }

    @Test fun doubleTextSizeStacksLibraryActionsAndKeepsSelectedViewReachable() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                WinNativeTheme { Box(Modifier.width(320.dp)) { Header() } }
            }
        }
        compose.onNodeWithText("Installed").performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
        val installed = compose.onNodeWithText("Installed").getUnclippedBoundsInRoot()
        val options = compose.onNodeWithContentDescription(label(R.string.vapor_library_options)).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(installed.bottom <= options.top)
        compose.onNodeWithText(label(R.string.vapor_search)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Portal")
        compose.onNodeWithContentDescription(label(R.string.workshop_clear_search)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("", query.value.text); assertTrue(installedOnly.value) }
        compose.onNodeWithText("All games").performScrollTo().performClick().assertIsSelected()
    }

    @Test fun wideLibraryKeepsPrimaryNavigationAndToolbarWithinShortHeader() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                WinNativeTheme { Box(Modifier.width(880.dp)) { Header() } }
            }
        }
        compose.onNodeWithText("Downloads").assertIsDisplayed()
        val all = compose.onNodeWithText("All games").assertIsDisplayed().getUnclippedBoundsInRoot()
        val search = compose.onNodeWithContentDescription(label(R.string.vapor_search)).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(all.top >= search.top && all.bottom <= search.bottom)
        assertTrue(search.bottom <= 116.dp)
        compose.onNodeWithContentDescription(label(R.string.vapor_library_options)).performClick()
        compose.runOnIdle { assertEquals(1, filters) }
    }

    @Test fun welcomeActionsRemainReachableInShortLargeTextWindow() {
        var signs = 0; var adds = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                WinNativeTheme { Box(Modifier.width(280.dp).height(320.dp)) {
                    VaporWelcome(false, false, {}, { signs++ }, {}, { adds++ }, home = true)
                } }
            }
        }
        compose.runOnIdle { assertEquals(0, signs + adds) }
        compose.onNodeWithText(label(R.string.vapor_connect_steam)).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(label(R.string.vapor_add_pc_game)).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, signs); assertEquals(1, adds) }
    }

    @Test fun landscapeWelcomeShowsAllActionsWithoutScrolling() {
        var signedIn by mutableStateOf(false)
        var steam = 0; var refreshes = 0; var adds = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                WinNativeTheme { Box(Modifier.width(880.dp).height(220.dp).testTag("welcomeViewport")) {
                    VaporWelcome(signedIn, false, {}, { steam++ }, { refreshes++ }, { adds++ })
                } }
            }
        }
        fun checkAction(id: Int) {
            val viewport = compose.onNodeWithTag("welcomeViewport").getUnclippedBoundsInRoot()
            val action = compose.onNodeWithText(label(id)).assertIsDisplayed()
            val bounds = action.getUnclippedBoundsInRoot()
            assertTrue(bounds.left >= viewport.left && bounds.right <= viewport.right)
            assertTrue(bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
            assertTrue(bounds.bottom - bounds.top >= 48.dp)
            action.performClick()
        }
        checkAction(R.string.vapor_connect_steam)
        checkAction(R.string.vapor_add_pc_game)
        compose.runOnIdle { signedIn = true }
        checkAction(R.string.vapor_store_refresh_library)
        checkAction(R.string.vapor_browse_store)
        checkAction(R.string.vapor_add_pc_game)
        compose.runOnIdle { assertEquals(2, steam); assertEquals(1, refreshes); assertEquals(2, adds) }
    }

    @Test fun storeSpotlightGrowsForLargeTextAndKeepsPriceAndActionVisible() {
        var opens = 0
        val game = SteamCatalogItem(1, "An unusually long game title for a narrow display", false,
            false, "€19.99", null, "", "", true, false, emptyList())
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                WinNativeTheme { Box(Modifier.width(280.dp)) {
                    SteamCatalogSpotlight(game, game.price, Modifier) { opens++ }
                } }
            }
        }
        val title = compose.onNodeWithText(game.name, useUnmergedTree = true).assertIsDisplayed().getUnclippedBoundsInRoot()
        val price = compose.onNodeWithText(game.price, useUnmergedTree = true).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(title.bottom <= price.top)
        compose.onNode(hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    private fun press(code: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val now = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP))
            instrumentation.sendKeySync(KeyEvent(now, SystemClock.uptimeMillis(), action, code,
                0, 0, -1, 0, 0, InputDevice.SOURCE_GAMEPAD))
        compose.waitForIdle()
    }

    @Test fun quickAccessWindowAcceptsControllerAAndBWithoutDuplicateActions() {
        var settings = 0; var dismissed = 0
        compose.setContent { WinNativeTheme {
            VaporQuickAccess(compose.activity, { dismissed++ }, { settings++ }, {})
        } }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText(label(R.string.vapor_all_settings)).performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText(label(R.string.vapor_all_settings)).assertIsFocused()
        press(KeyEvent.KEYCODE_BUTTON_A)
        compose.runOnIdle { assertEquals(1, settings); assertEquals(0, dismissed) }
        press(KeyEvent.KEYCODE_BUTTON_B)
        compose.runOnIdle { assertEquals(1, dismissed) }
    }
}
