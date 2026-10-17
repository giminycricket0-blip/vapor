package com.winlator.cmod.app.shell

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.imageLoader
import coil.request.ImageRequest
import com.winlator.cmod.feature.stores.steam.data.SteamApp
import com.winlator.cmod.shared.theme.WinNativeTheme
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeUiTest {
    @get:Rule val compose = createComposeRule()
    private val sample = listOf(SteamApp(id = -11, name = "First sample"), SteamApp(id = -12, name = "Second sample"))

    @Composable private fun Shelf(games: List<SteamApp> = sample, recent: Boolean = true, initialIndex: Int = 0,
        heroArtwork: Any? = null, installed: Boolean = true, onSettings: (SteamApp) -> Unit = {},
        onOpen: (SteamApp) -> Unit) {
        var selected by remember { mutableIntStateOf(initialIndex) }
        VaporGameShelf(games, recent, selected, heroArtwork, installed, { selected = it }, onOpen,
            onSettings = if (installed) ({ onSettings(games[selected]) }) else null,
        ) { game, _, focused, modifier, activate ->
            GameCapsule(game, useLibraryCapsule = true, showCaption = false,
                isFocusedOverride = focused, isControllerActive = true,
                onClick = activate, modifier = modifier)
        }
    }

    @Test fun openingHomeDoesNotLaunchAndPrimaryActionUsesCurrentSelection() {
        var opened = 0
        compose.setContent { WinNativeTheme { Shelf(initialIndex = 1) { opened = it.id } } }
        compose.runOnIdle { assertEquals(0, opened) }
        compose.onNodeWithText("Open game").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(-12, opened) }
    }

    @Test fun withoutHistoryTheHeadingDoesNotClaimRecentPlay() {
        compose.setContent { WinNativeTheme { Shelf(recent = false) {} } }
        compose.onNodeWithText("YOUR GAMES").assertIsDisplayed()
        compose.onNodeWithText("RECENT GAMES").assertDoesNotExist()
    }

    @Test fun tappingAnotherCoverSelectsItBeforeOpening() {
        val opened = mutableListOf<Int>()
        compose.setContent { WinNativeTheme { Shelf { opened.add(it.id) } } }
        compose.onNodeWithTag("vapor_shelf_game_-12").performScrollTo().performTouchInput { click() }
        compose.onNodeWithTag("vapor_shelf_game_-12").assertIsSelected()
        compose.runOnIdle { assertEquals(emptyList<Int>(), opened) }
        compose.onNodeWithText("Open game").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(-12), opened) }
    }

    @Test fun secondTapOnSelectedCoverOpensOnlyThatGame() {
        val opened = mutableListOf<Int>()
        compose.setContent { WinNativeTheme { Shelf { opened.add(it.id) } } }
        compose.onNodeWithTag("vapor_shelf_game_-12").performScrollTo().performTouchInput { click() }
        compose.onNodeWithTag("vapor_shelf_game_-12").performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf(-12), opened) }
    }

    @Test fun settingsBelongToTheSelectedGame() {
        val settings = mutableListOf<Int>()
        compose.setContent { WinNativeTheme { Shelf(onSettings = { settings.add(it.id) }) {} } }
        compose.onNodeWithTag("vapor_shelf_game_-12").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Game settings").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(-12), settings) }
    }

    @Test fun uninstalledGameOffersInstallThroughItsExistingManager() {
        var opened = 0
        compose.setContent { WinNativeTheme { Shelf(installed = false) { opened = it.id } } }
        compose.onNodeWithText("Not installed").assertIsDisplayed()
        compose.onNodeWithText("Open game").assertDoesNotExist()
        compose.onNodeWithContentDescription("Game settings").assertDoesNotExist()
        compose.onNodeWithText("Install").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(-11, opened) }
    }

    @Test fun shortViewportAndLargeTextStillReachTheAction() {
        var opened = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                WinNativeTheme { Box(Modifier.width(300.dp).height(250.dp)) { Shelf { opened = it.id } } }
            }
        }
        compose.onNodeWithText("Open game").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(-11, opened) }
    }

    @Test fun wideShelfPreservesPortraitArtAndSpaceBelowTheGameAction() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                WinNativeTheme { Box(Modifier.width(880.dp).height(300.dp).testTag("shelfViewport")) {
                    Shelf(games = listOf(SteamApp(id = 400, name = "Portrait test"))) {}
                } }
            }
        }
        val artwork = compose.onNodeWithContentDescription("Portrait test", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue("A title strip must not crop the 2:3 portrait artwork",
            kotlin.math.abs((artwork.right - artwork.left).value / (artwork.bottom - artwork.top).value - 2f / 3f) < .02f)
        val viewport = compose.onNodeWithTag("shelfViewport").getUnclippedBoundsInRoot()
        val action = compose.onNodeWithText("Open game").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("The game action needs its bottom padding in a short wide window",
            action.bottom + 16.dp <= viewport.bottom)
    }

    @Test fun homeControllerMovesHorizontallyAndOpensOnReleaseOnly() {
        compose.setContent { Box(Modifier.size(1.dp)) }
        val actions = CopyOnWriteArrayList<Int>()
        lateinit var host: UnifiedActivity
        lateinit var collector: Job
        compose.runOnIdle {
            host = UnifiedActivity().apply {
                currentTabKey = "home"; libraryItemCount = 3; currentLibraryLayoutMode = LibraryLayoutMode.CAROUSEL
            }
            host.moveLibraryFocus(false, true, false, false)
            assertEquals(1, host.libraryFocusIndex.value)
            host.moveLibraryFocus(false, false, false, true)
            assertEquals(1, host.libraryFocusIndex.value)
            collector = CoroutineScope(Dispatchers.Main.immediate).launch(start = CoroutineStart.UNDISPATCHED) {
                host.keyEventFlow.collect { actions.add(it.action) }
            }
            host.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
        }
        compose.runOnIdle { assertEquals(emptyList<Int>(), actions.toList()) }
        compose.runOnIdle { host.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A)) }
        compose.waitUntil { actions.size == 1 }
        compose.runOnIdle { assertEquals(listOf(KeyEvent.ACTION_UP), actions.toList()); collector.cancel() }
    }

    @Test fun populatedHomeRendersProductionComponentsWithLabeledSampleData() {
        val games = listOf(SteamApp(id = 400, name = "Portal"), SteamApp(id = 367520, name = "Hollow Knight"),
            SteamApp(id = 413150, name = "Stardew Valley"), SteamApp(id = 620, name = "Portal 2"),
            SteamApp(id = 427520, name = "Factorio"), SteamApp(id = 1289310, name = "Helltaker"),
            SteamApp(id = 286100, name = "You Have to Win the Game"), SteamApp(id = 440, name = "Team Fortress 2"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Optional public artwork for visual review. No assertions depend on network or ownership.
        runCatching { runBlocking { withTimeout(15_000) {
            games.map { game -> async {
                context.imageLoader.execute(ImageRequest.Builder(context)
                    .data(game.getLibraryCapsuleUrl()).build())
            } }.awaitAll()
        } } }
        compose.setContent {
            WinNativeTheme {
                Column(Modifier.fillMaxSize().background(BgDark)) {
                    VaporHeader(
                        tabs = listOf("Home" to "home", "Library" to "library", "Store" to "steam", "Downloads" to "downloads")
                            .map { UnifiedActivity.TabDef(it.first, it.second) },
                        selectedIndex = 0, onSelect = {}, searchQuery = TextFieldValue(""), onSearchQueryChange = {},
                        searchExpanded = false, onToggleSearch = {}, onFilter = {}, onFriends = {}, onSettings = {},
                        onQuickAccess = {}, onMoreStores = {},
                    )
                    Box(Modifier.weight(1f)) { Shelf(games, heroArtwork = games.first().getHeroUrl()) {} }
                    Text("UI test · sample games and installs · no Steam account", color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                }
            }
        }
        compose.onNodeWithText("RECENT GAMES").assertIsDisplayed()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), "vapor-home-components.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
