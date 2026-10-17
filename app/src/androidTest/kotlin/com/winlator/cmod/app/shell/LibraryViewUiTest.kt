package com.winlator.cmod.app.shell

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.cmod.feature.stores.steam.data.SteamApp
import com.winlator.cmod.shared.theme.WinNativeTheme
import com.winlator.cmod.shared.ui.defaultGridColumns
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryViewUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun viewTabsExposeTheirSelectionAndSwitchBothWays() {
        compose.setContent {
            var installed by remember { mutableStateOf(false) }
            WinNativeTheme { VaporLibraryTabs(installed) { installed = it } }
        }
        compose.onNodeWithText("All games").assertIsSelected()
        compose.onNodeWithText("Installed").performClick().assertIsSelected()
        compose.onNodeWithText("All games").assertIsNotSelected().performClick().assertIsSelected()
    }

    @Test fun emptyInstalledViewReturnsToOwnedGames() {
        var allGames = 0
        compose.setContent {
            WinNativeTheme { VaporWelcome(true, true, { allGames++ }, {}, {}, {}) }
        }
        compose.onNodeWithText("View all games").performScrollTo().performClick()
        compose.onNodeWithText("Connect Steam").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, allGames) }
    }

    @Test fun signedOutViewOffersSteamAndLocalGames() {
        var signs = 0; var local = 0
        compose.setContent {
            WinNativeTheme { VaporWelcome(false, false, {}, { signs++ }, {}, { local++ }) }
        }
        compose.onNodeWithText("Connect Steam").performScrollTo().performClick()
        compose.onNodeWithText("Connect your stores").assertDoesNotExist()
        compose.onNodeWithText("Epic Games").assertDoesNotExist()
        compose.onNodeWithText("GOG").assertDoesNotExist()
        compose.onNodeWithText("itch.io").assertDoesNotExist()
        compose.onNodeWithText("Add a PC game").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, signs); assertEquals(1, local) }
    }

    @Test fun emptySignedInViewOffersRefreshWithoutAnEndlessLoader() {
        var refresh = 0
        compose.setContent {
            WinNativeTheme { VaporWelcome(true, false, {}, {}, { refresh++ }, {}) }
        }
        compose.onNodeWithText("Refresh your Steam library").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, refresh) }
    }

    @Test fun libraryCardsExplainInstallStateAndOpenTheSelectedTitle() {
        var selected = 0
        // Exercise the actual card with explicit callbacks; no account or runtime is started.
        compose.setContent {
            WinNativeTheme {
                Row(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    GameCapsule(SteamApp(id = -1, name = "Installed sample"), installStatus = true,
                        modifier = Modifier.weight(1f).aspectRatio(2f / 3f), onClick = { selected = 1 })
                    GameCapsule(SteamApp(id = -2, name = "Owned sample"), installStatus = false,
                        modifier = Modifier.weight(1f).aspectRatio(2f / 3f), onClick = { selected = 2 })
                }
            }
        }
        compose.onNodeWithText("Installed").assertIsDisplayed()
        compose.onNodeWithText("Not installed").assertIsDisplayed()
        compose.onNodeWithText("Owned sample").performClick()
        compose.runOnIdle { assertEquals(2, selected) }
    }

    @Test fun controllerSelectionIsDeliveredOnReleaseOnly() {
        compose.setContent { Box(Modifier.size(1.dp)) }
        val actions = CopyOnWriteArrayList<Int>()
        lateinit var host: UnifiedActivity
        lateinit var collector: Job
        compose.runOnIdle {
            host = UnifiedActivity().apply { currentTabKey = "library"; libraryItemCount = 1 }
            collector = CoroutineScope(Dispatchers.Main.immediate).launch(start = CoroutineStart.UNDISPATCHED) {
                host.keyEventFlow.collect { actions.add(it.action) }
            }
            host.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
        }
        compose.runOnIdle { assertEquals(emptyList<Int>(), actions.toList()) }
        compose.runOnIdle { host.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A)) }
        compose.waitUntil { actions.size == 1 }
        compose.runOnIdle {
            assertEquals(listOf(KeyEvent.ACTION_UP), actions.toList())
            collector.cancel()
        }
    }

    @Test fun populatedLibraryComponentsRenderWithSampleData() {
        // Visual review artifact only. These rows are never written to the account or install DB.
        val games = listOf(
            SteamApp(id = 400, name = "Portal"),
            SteamApp(id = 22150, name = "Europa Universalis III Demo"),
            SteamApp(id = 367520, name = "Hollow Knight"),
            SteamApp(id = 413150, name = "Stardew Valley"),
            SteamApp(id = 620, name = "Portal 2"),
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Optional public artwork for the review image, through the same Coil loader as the cards.
        // Assertions do not depend on network access or successful artwork downloads.
        runCatching {
            runBlocking {
                withTimeout(15_000) {
                    games.map { game -> async {
                        context.imageLoader.execute(ImageRequest.Builder(context).data(game.getLibraryCapsuleUrl()).build())
                    } }.awaitAll()
                }
            }
        }
        compose.setContent {
            WinNativeTheme {
                Column(Modifier.fillMaxSize().background(BgDark)) {
                    VaporHeader(
                        tabs = listOf("Home" to "home", "Library" to "library", "Store" to "steam", "Downloads" to "downloads")
                            .map { UnifiedActivity.TabDef(it.first, it.second) },
                        selectedIndex = 1, onSelect = {}, searchQuery = TextFieldValue(""), onSearchQueryChange = {},
                        searchExpanded = false, onToggleSearch = {}, onFilter = {}, onFriends = {}, onSettings = {},
                        onQuickAccess = {}, onMoreStores = {},
                    )
                    Text("NATIVE UI TEST · SAMPLE LIBRARY", color = TextSecondary,
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    LazyVerticalGrid(columns = GridCells.Fixed(defaultGridColumns()),
                        modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        itemsIndexed(games, key = { _, game -> game.id }) { index, game ->
                            GameCapsule(game, onClick = {}, installStatus = index == 0,
                                useLibraryCapsule = true, isFocusedOverride = index == 0, isControllerActive = true,
                                modifier = Modifier.aspectRatio(2f / 3f))
                        }
                    }
                    Text("Sample ownership and install states — no Steam account connected", color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(16.dp))
                }
            }
        }
        compose.onNodeWithText("Portal", substring = false).assertExists()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), "vapor-library-components.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
