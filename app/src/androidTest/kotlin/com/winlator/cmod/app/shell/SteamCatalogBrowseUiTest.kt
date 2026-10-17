package com.winlator.cmod.app.shell

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.cmod.feature.stores.steam.service.*
import com.winlator.cmod.app.service.NetworkMonitor
import com.winlator.cmod.shared.theme.WinNativeTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** The production public browser with controlled responses; no Steam account or network. */
@RunWith(AndroidJUnit4::class)
class SteamCatalogBrowseUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Suppress("UNCHECKED_CAST")
    private val internet = NetworkMonitor::class.java.getDeclaredField("_hasInternet").let {
        it.isAccessible = true
        it.get(null) as MutableStateFlow<Boolean>
    }
    private var originalInternet = false
    @Before fun connectedFixture() { originalInternet = internet.value; internet.value = true }
    @After fun restoreConnection() { internet.value = originalInternet }
    private data class Request(val section: SteamCatalogSection, val start: Int, val search: String, val genre: SteamCatalogGenre)
    private val requests = CopyOnWriteArrayList<Request>()
    private fun game(id: Int, name: String = "Sample $id") = SteamCatalogItem(id, name, false, true,
        "", null, "", "", true, false, emptyList())
    private fun page(id: Int = 1) = SteamCatalogPage(listOf(game(id)), 0, 1, 1)
    private fun grid() = compose.onNodeWithTag("steam_catalog_grid")
    private fun chooseFilter(control: String, option: String) {
        grid().performScrollToIndex(0)
        compose.onNodeWithContentDescription(control).performClick()
        compose.onNode(hasText(option) and hasAnyAncestor(isPopup())).performScrollTo().performClick()
    }
    private fun press(code: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val now = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP))
            instrumentation.sendKeySync(KeyEvent(now, SystemClock.uptimeMillis(), action, code,
                0, 0, -1, 0, 0, InputDevice.SOURCE_GAMEPAD))
        compose.waitForIdle()
    }
    private fun awaitRequest(predicate: (Request) -> Boolean) = compose.waitUntil(5_000) { requests.any(predicate) }

    private fun show(loader: suspend (Request) -> SteamCatalogPage = { page() }) {
        compose.setContent { WinNativeTheme {
            SteamCatalogBrowser(emptySet(), {}, {}, {}, {}, loadPage = { section, start, search, genre ->
                val request = Request(section, start, search, genre)
                requests.add(request); loader(request)
            })
        } }
    }

    @Test fun loadingIsLabeledAndRefreshIsDisabledUntilTheResponseArrives() {
        val response = CompletableDeferred<SteamCatalogPage>()
        show { response.await() }
        compose.onNodeWithText("Loading games…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Steam Store options").performClick()
        compose.onNodeWithText("Refresh games").assertIsNotEnabled()
        compose.onNodeWithText("Sample 1").assertDoesNotExist()
        response.complete(page())
        compose.onNodeWithText("Refresh games").assertIsEnabled().performClick()
        compose.onNodeWithText("Sample 1").assertIsDisplayed()
    }

    @Test fun searchKeepsChosenFiltersAndResetReturnsToAllGames() {
        show()
        chooseFilter("Game filter", "Free to play")
        chooseFilter("Category", "Action")
        compose.onNode(hasSetTextAction()).performTextInput("portal")
        awaitRequest { it.search == "portal" && it.section == SteamCatalogSection.FREE && it.genre == SteamCatalogGenre.ACTION }
        compose.onNodeWithContentDescription("Clear search and filters").performClick()
        awaitRequest { it.search.isEmpty() && it.section == SteamCatalogSection.ALL && it.genre == SteamCatalogGenre.ALL }
        compose.onNodeWithContentDescription("Game filter").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "All games"))
        compose.onNodeWithContentDescription("Category").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "All categories"))
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test fun cancelledOldResponseCannotReplaceNewResults() {
        val old = CompletableDeferred<SteamCatalogPage>()
        show { request ->
            if (request.section == SteamCatalogSection.POPULAR) withContext(NonCancellable) { withTimeout(10_000) { old.await() } }
            else page(2)
        }
        chooseFilter("Game filter", "All games")
        compose.onNodeWithText("Sample 2").assertIsDisplayed()
        old.complete(page(1))
        compose.waitForIdle()
        compose.onNodeWithText("Sample 2").assertIsDisplayed()
        compose.onNodeWithText("Sample 1").assertDoesNotExist()
    }

    @Test fun retryPreservesTheFailedFilterRequest() {
        var fail = true
        show { request ->
            if (request.genre == SteamCatalogGenre.ACTION && fail) throw IOException("Synthetic failure")
            page()
        }
        chooseFilter("Category", "Action")
        compose.onNodeWithText("Steam Store could not be loaded. Check your connection and try again.").assertIsDisplayed()
        compose.runOnIdle { fail = false }
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Sample 1").assertIsDisplayed()
        compose.runOnIdle {
            val retries = requests.filter { it.genre == SteamCatalogGenre.ACTION }
            assertEquals(2, retries.size)
            assertEquals(retries.first(), retries.last())
        }
    }

    @Test fun previousReturnsToTheActualOffsetWhenSteamReturnsAShortPage() {
        show { request -> SteamCatalogPage(listOf(game(request.start + 1)), request.start, 7, 30) }
        grid().performScrollToNode(hasText("Next"))
        compose.onNodeWithText("Previous").assertIsNotEnabled()
        compose.onNodeWithText("Next").performClick()
        awaitRequest { it.start == 7 }
        grid().performScrollToNode(hasText("Next"))
        compose.onNodeWithText("Page 2").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()
        awaitRequest { it.start == 14 }
        grid().performScrollToNode(hasText("Previous"))
        compose.onNodeWithText("Previous").performClick()
        compose.waitUntil(5_000) { requests.count { it.start == 7 } == 2 }
        grid().performScrollToNode(hasText("Page 2"))
        compose.onNodeWithText("Page 2").assertIsDisplayed()
    }

    @Test fun emptySearchOffersRecoveryAndSpellingSuggestion() {
        show { request -> if (request.search == "poratl") SteamCatalogPage(emptyList(), 0, 0, 0, listOf("portal")) else page() }
        compose.onNode(hasSetTextAction()).performTextInput("poratl")
        awaitRequest { it.search == "poratl" }
        grid().performScrollToNode(hasText("Try another name or clear your filters to browse more games."))
        compose.onNodeWithText("Try another name or clear your filters to browse more games.").assertIsDisplayed()
        grid().performScrollToNode(hasText("Try “portal”"))
        compose.onNodeWithText("Try “portal”").performClick()
        awaitRequest { it.search == "portal" }
        compose.onNodeWithText("Sample 1").assertIsDisplayed()
    }

    @Test fun matchingGamesDoNotShowSpellingPrompts() {
        show { request ->
            if (request.search.isNotEmpty()) page().copy(suggestions = listOf("portal", "portals", "portal 2", "portal knight"))
            else page()
        }
        compose.onNode(hasSetTextAction()).performTextInput("portal")
        awaitRequest { it.search == "portal" }
        compose.onNodeWithText("Sample 1").assertIsDisplayed()
        compose.onAllNodesWithText("Try “", substring = true).assertCountEquals(0)
    }

    @Test fun emptySearchOffersOnlyTheBestDifferentSuggestion() {
        show { request ->
            if (request.search == "poratl") SteamCatalogPage(emptyList(), 0, 0, 0,
                listOf("poratl", "portal", "portals", "portal 2")) else page()
        }
        compose.onNode(hasSetTextAction()).performTextInput("poratl")
        awaitRequest { it.search == "poratl" }
        compose.onNodeWithText("Try “portal”").assertIsDisplayed()
        compose.onAllNodesWithText("Try “", substring = true).assertCountEquals(1)
    }

    @Test fun unchangedSuccessfulSearchSubmitDoesNotRequestItAgain() {
        show()
        compose.onNode(hasSetTextAction()).performTextInput("portal")
        awaitRequest { it.search == "portal" }
        compose.onNodeWithText("Sample 1").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.runOnIdle { assertEquals(1, requests.count { it.search == "portal" }) }
    }

    @Test fun failedRefreshKeepsOnlyTheCurrentResultsAndRetryRecovers() {
        var fail = false
        var id = 1
        show {
            if (fail) throw IOException("Synthetic disconnected refresh")
            page(id)
        }
        compose.onNodeWithText("Sample 1").assertIsDisplayed()
        compose.runOnIdle { fail = true }
        compose.onNodeWithContentDescription("Steam Store options").performClick()
        compose.onNodeWithText("Refresh games").performClick()
        compose.onNodeWithText("Could not refresh. Showing the last loaded results.").assertIsDisplayed()
        compose.onNodeWithText("Sample 1").assertIsDisplayed()
        compose.runOnIdle { fail = false; id = 2 }
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Sample 2").assertIsDisplayed()
        compose.onNodeWithText("Could not refresh. Showing the last loaded results.").assertDoesNotExist()
        compose.onNodeWithText("Sample 1").assertDoesNotExist()
        compose.runOnIdle { fail = true }
        compose.onNode(hasSetTextAction()).performTextInput("different game")
        awaitRequest { it.search == "different game" }
        compose.onNodeWithText("Steam Store could not be loaded. Check your connection and try again.").assertIsDisplayed()
        compose.onNodeWithText("Sample 2").assertDoesNotExist()
        compose.onNodeWithText("Could not refresh. Showing the last loaded results.").assertDoesNotExist()
    }

    @Test fun resultCountNamesTheSelectedSubset() {
        show { page().copy(total = 84) }
        compose.onNodeWithText("Hot games: 84").assertIsDisplayed()
        chooseFilter("Game filter", "All games")
        compose.onNodeWithText("All games: 84").assertIsDisplayed()
    }

    @Test fun connectionReturnRetriesAFailedPageButNotASuccessfulOne() {
        val original = internet.value
        try {
            internet.value = false
            show {
                if (!internet.value) throw IOException("Synthetic disconnected load")
                page(2)
            }
            awaitRequest { true }
            compose.onNodeWithText("You’re offline. Games will reload when you reconnect.").assertIsDisplayed()
            compose.runOnIdle { internet.value = true }
            // Reconnect clears idle HTTP sockets on IO before dispatching the retry.
            compose.waitUntil(5_000) { requests.size == 2 }
            compose.onNodeWithText("Sample 2").assertIsDisplayed()
            compose.runOnIdle { assertEquals(2, requests.size); internet.value = false }
            compose.waitForIdle()
            compose.runOnIdle { internet.value = true }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(2, requests.size) }
        } finally { internet.value = original }
    }

    @Test fun connectionReturnRestartsARequestStillWaitingOnTheOldNetwork() {
        val original = internet.value
        val oldResponse = CompletableDeferred<SteamCatalogPage>()
        try {
            show {
                if (requests.size == 1) oldResponse.await() else page(2)
            }
            compose.onNodeWithText("Loading games…").assertIsDisplayed()
            compose.runOnIdle { internet.value = false }
            compose.waitForIdle()
            compose.runOnIdle { internet.value = true }
            compose.waitUntil(5_000) { requests.size == 2 }
            compose.onNodeWithText("Sample 2").assertIsDisplayed()
            // A late old-network response must not replace the recovered page.
            oldResponse.complete(page(1))
            compose.onNodeWithText("Sample 1").assertDoesNotExist()
            compose.runOnIdle { assertEquals(2, requests.size) }
        } finally { internet.value = original; oldResponse.cancel() }
    }

    @Test fun connectionReturningBehindDetailsRecoversWhenTheModalCloses() {
        val response = CompletableDeferred<SteamCatalogPage>()
        var selected by mutableStateOf(false)
        compose.setContent { WinNativeTheme {
            SteamCatalogBrowser(emptySet(), { selected = true }, {}, {}, {}, active = !selected,
                loadPage = { section, start, search, genre ->
                    requests.add(Request(section, start, search, genre))
                    when (requests.size) { 1 -> page(1); 2 -> response.await(); else -> page(2) }
                })
            if (selected) Dialog(onDismissRequest = { selected = false }) {
                Button(onClick = { selected = false }) { Text("Close sample details") }
            }
        } }
        compose.onNodeWithText("Sample 1").assertIsDisplayed()
        compose.onNodeWithContentDescription("Steam Store options").performClick()
        compose.onNodeWithText("Refresh games").performClick()
        compose.waitUntil(5_000) { requests.size == 2 }
        compose.onNodeWithText("Sample 1").performClick()
        compose.onNodeWithText("Close sample details").assertIsDisplayed()
        compose.runOnIdle { internet.value = false }
        response.completeExceptionally(IOException("Disconnected while details were open"))
        compose.waitForIdle()
        compose.runOnIdle { internet.value = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("Recovery waits for the browser to be active", 2, requests.size) }
        compose.onNodeWithText("Close sample details").performClick()
        compose.waitUntil(5_000) { requests.size == 3 }
        compose.onNodeWithText("Sample 2").assertIsDisplayed()
        compose.onNodeWithText("Sample 1").assertDoesNotExist()
        compose.runOnIdle { assertEquals(requests[0], requests[2]) }
    }

    @Test fun offlineCopyDistinguishesRetainedResultsFromANewFailedQuery() {
        show { if (internet.value) page(2) else throw IOException("Synthetic offline request") }
        compose.onNodeWithText("Sample 2").assertIsDisplayed()
        compose.runOnIdle { internet.value = false }
        compose.onNodeWithContentDescription("Steam Store options").performClick()
        compose.onNodeWithText("Refresh games").performClick()
        compose.waitUntil(5_000) { requests.size == 2 }
        compose.onNodeWithText("You’re offline. Showing the last loaded results.").assertIsDisplayed()
        compose.onNodeWithText("Sample 2").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("Hollow Knight")
        awaitRequest { it.search == "Hollow Knight" }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("You’re offline. Games will reload when you reconnect.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("You’re offline. Games will reload when you reconnect.").assertIsDisplayed()
        compose.onNodeWithText("Sample 2").assertDoesNotExist()
        compose.onNodeWithText("No games found").assertDoesNotExist()
        compose.runOnIdle { internet.value = true }
        compose.waitUntil(5_000) { requests.count { it.search == "Hollow Knight" } == 2 }
        compose.onNodeWithText("Sample 2").assertIsDisplayed()
        compose.onNodeWithText("You’re offline. Games will reload when you reconnect.").assertDoesNotExist()
    }

    @Test fun unvalidatedConnectionChecksAndroidOffMainAndStillAttemptsTheTransport() {
        val queriedOnMain = CopyOnWriteArrayList<Boolean>()
        val local = object : ContextWrapper(compose.activity) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? {
                if (name == Context.CONNECTIVITY_SERVICE) {
                    queriedOnMain.add(Looper.myLooper() == Looper.getMainLooper())
                    error("Synthetic unavailable connectivity service")
                }
                return super.getSystemService(name)
            }
        }
        internet.value = false
        compose.setContent { CompositionLocalProvider(LocalContext provides local) {
            WinNativeTheme {
                SteamCatalogBrowser(emptySet(), {}, {}, {}, {}, loadPage = { section, start, search, genre ->
                    requests.add(Request(section, start, search, genre))
                    throw IOException("Synthetic transport failure after an unknown connectivity result")
                })
            }
        } }
        awaitRequest { true }
        compose.onNodeWithText("You’re offline. Games will reload when you reconnect.").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(false), queriedOnMain.toList()); assertEquals(1, requests.size) }
    }

    @Test fun gridIsLazyAndClosingDetailsKeepsTheCardAndRequest() {
        var loads = 0
        compose.setContent { WinNativeTheme {
            var selected by remember { mutableStateOf<SteamCatalogItem?>(null) }
            SteamCatalogBrowser(emptySet(), { selected = it }, {}, {}, {}, active = selected == null,
                loadPage = { _, _, _, _ -> loads++; SteamCatalogPage((100..149).map { game(it) }, 0, 50, 50) })
            if (selected != null) Dialog(onDismissRequest = { selected = null }) {
                Button(onClick = { selected = null }) { Text("Close sample details") }
            }
        } }
        compose.onNodeWithText("Sample 149").assertDoesNotExist()
        grid().performScrollToKey(149)
        compose.onNodeWithText("Sample 149").assertIsDisplayed().performClick()
        compose.onNodeWithText("Close sample details").performClick()
        compose.onNodeWithText("Sample 149").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, loads) }
    }

    @Test fun controllerSearchAndBackRespectTheDetailModal() {
        var handler: ((KeyEvent) -> Boolean)? = null
        var backs = 0
        val active = mutableStateOf(true)
        compose.setContent { WinNativeTheme {
            SteamCatalogBrowser(emptySet(), {}, {}, {}, { backs++ }, active = active.value,
                registerKeyHandler = { callback ->
                    handler = callback
                    val unregister = { if (handler === callback) handler = null }
                    unregister
                }, loadPage = { _, _, _, _ -> page() })
        } }
        compose.runOnIdle { assertTrue(handler!!(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_Y))) }
        compose.onNode(hasSetTextAction()).assertIsFocused().performTextInput("portal")
        compose.runOnIdle { assertTrue(handler!!(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B))) }
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.runOnIdle { assertEquals(0, backs); active.value = false }
        compose.runOnIdle { assertFalse(handler!!(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B))); active.value = true }
        compose.runOnIdle { assertTrue(handler!!(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B))); assertEquals(1, backs) }
    }

    @Test fun largeTextNarrowWindowCanReachRecoveryAndPageActions() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                WinNativeTheme { Box(Modifier.width(320.dp)) {
                    SteamCatalogBrowser(emptySet(), {}, {}, {}, {}, loadPage = { _, start, _, _ ->
                        SteamCatalogPage(listOf(game(start + 1)), start, 1, 2)
                    })
                } }
            }
        }
        grid().performScrollToNode(hasText("Next"))
        compose.onNodeWithText("Next").assertIsDisplayed().performClick()
        grid().performScrollToNode(hasText("Page 2"))
        compose.onNodeWithText("Page 2").assertIsDisplayed()
        compose.onNodeWithText("Previous").assertIsDisplayed().performClick()
        grid().performScrollToNode(hasText("Page 1"))
        compose.onNodeWithText("Page 1").assertIsDisplayed()
    }

    @Test fun savedBrowserStateRestoresFiltersAndExactPageHistory() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { WinNativeTheme {
            SteamCatalogBrowser(emptySet(), {}, {}, {}, {}, loadPage = { section, start, search, genre ->
                requests.add(Request(section, start, search, genre))
                SteamCatalogPage(listOf(game(start + 1)), start, 7, 30)
            })
        } }
        chooseFilter("Game filter", "Free to play")
        chooseFilter("Category", "Action")
        grid().performScrollToNode(hasText("Next"))
        compose.onNodeWithText("Next").performClick()
        awaitRequest { it.start == 7 && it.genre == SteamCatalogGenre.ACTION }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(5_000) { requests.count { it.start == 7 && it.genre == SteamCatalogGenre.ACTION } == 2 }
        grid().performScrollToIndex(0)
        compose.onNodeWithContentDescription("Game filter").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Free to play"))
        grid().performScrollToNode(hasText("Page 2"))
        compose.onNodeWithText("Previous").performClick()
        grid().performScrollToNode(hasText("Page 1"))
        compose.onNodeWithText("Page 1").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, requests.last().start); assertEquals(SteamCatalogGenre.ACTION, requests.last().genre) }
    }

    @Test fun compactPortraitShowsGamesEarlyWithoutShrinkingTouchTargets() {
        compose.setContent { WinNativeTheme { Box(Modifier.width(400.dp)) {
            SteamCatalogBrowser(emptySet(), {}, {}, {}, {}, loadPage = { _, _, _, _ -> page() })
        } } }
        val search = compose.onNode(hasSetTextAction()).assertHeightIsAtLeast(48.dp).getUnclippedBoundsInRoot()
        val options = compose.onNodeWithContentDescription("Steam Store options").assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp).getUnclippedBoundsInRoot()
        val section = compose.onNodeWithContentDescription("Game filter").assertHeightIsAtLeast(48.dp).getUnclippedBoundsInRoot()
        val genre = compose.onNodeWithContentDescription("Category").assertHeightIsAtLeast(48.dp).getUnclippedBoundsInRoot()
        val artwork = compose.onNode(hasText("Sample 1") and hasClickAction()).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(search.right + 8.dp <= options.left)
        assertTrue(section.right + 8.dp <= genre.left)
        assertTrue(search.bottom <= section.top)
        assertTrue("First artwork should start within 160dp of the Store", artwork.top - search.top <= 160.dp)
        compose.onNodeWithText("Find your next game").assertDoesNotExist()
        compose.onNodeWithText("Explore games").assertDoesNotExist()
    }

    @Test fun shortLandscapeShowsCardNamesAndPricesBelowOneToolbar() {
        // Keep the available dp width deterministic even when the emulator is in portrait.
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
            WinNativeTheme { Box(Modifier.width(880.dp).height(320.dp)) {
                SteamCatalogBrowser(emptySet(), {}, {}, {}, {}, loadPage = { _, _, _, _ ->
                    SteamCatalogPage((1..8).map { game(it) }, 0, 8, 8)
                })
            } }
        } }
        chooseFilter("Game filter", "All games")
        val search = compose.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
        val section = compose.onNodeWithContentDescription("Game filter").getUnclippedBoundsInRoot()
        val genre = compose.onNodeWithContentDescription("Category").getUnclippedBoundsInRoot()
        assertEquals(search.top, section.top)
        assertEquals(section.top, genre.top)
        assertTrue(search.right + 8.dp <= section.left)
        assertTrue(section.right + 8.dp <= genre.left)
        (1..4).forEach { id -> compose.onNodeWithText("Sample $id").assertIsDisplayed() }
        compose.onAllNodesWithText("Free", useUnmergedTree = true)[0].assertIsDisplayed()
    }

    @Test fun doubleTextShortWindowCanChooseAndClearEveryFilter() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                WinNativeTheme { Box(Modifier.width(320.dp).height(360.dp)) {
                    SteamCatalogBrowser(emptySet(), {}, {}, {}, {}, loadPage = { section, start, search, genre ->
                        requests.add(Request(section, start, search, genre)); page()
                    })
                } }
            }
        }
        val section = compose.onNodeWithContentDescription("Game filter").getUnclippedBoundsInRoot()
        val category = compose.onNodeWithContentDescription("Category").getUnclippedBoundsInRoot()
        assertTrue("Large text selectors keep an 8dp gap", section.bottom + 8.dp <= category.top)
        chooseFilter("Game filter", "On sale")
        chooseFilter("Category", "Platformer")
        awaitRequest { it.section == SteamCatalogSection.DEALS && it.genre == SteamCatalogGenre.PLATFORMER }
        grid().performScrollToNode(hasContentDescription("Clear search and filters"))
        compose.onNodeWithContentDescription("Clear search and filters").assertIsDisplayed().performClick()
        awaitRequest { it.section == SteamCatalogSection.ALL && it.genre == SteamCatalogGenre.ALL }
        grid().performScrollToKey(1)
        compose.onNodeWithText("Sample 1").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
    }

    @Test fun menusAcceptControllerSelectionAndDismissalWithoutLeavingStore() {
        var backs = 0
        compose.setContent { WinNativeTheme {
            SteamCatalogBrowser(emptySet(), {}, {}, {}, { backs++ }, loadPage = { section, start, search, genre ->
                requests.add(Request(section, start, search, genre)); page()
            })
        } }
        compose.onNodeWithContentDescription("Game filter").performClick()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Demos").performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(KeyEvent.KEYCODE_BUTTON_Y)
        compose.onNodeWithText("Demos").assertIsDisplayed().assertIsFocused()
        press(KeyEvent.KEYCODE_BUTTON_A)
        awaitRequest { it.section == SteamCatalogSection.DEMOS }
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Category").performClick()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_BUTTON_B)
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, backs); assertEquals(1, requests.count { it.section == SteamCatalogSection.DEMOS }) }
    }

    @Test fun storeMenuRetainsWishlistCommunityAndRefreshActions() {
        var wishlist = 0; var community = 0; var loads = 0
        compose.setContent { WinNativeTheme {
            SteamCatalogBrowser(emptySet(), {}, { wishlist++ }, { community++ }, {}, loadPage = { _, _, _, _ -> loads++; page() })
        } }
        compose.onNodeWithContentDescription("Steam Store options").performClick()
        compose.onNodeWithText("Wishlist").performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Steam Store options").performClick()
        compose.onNodeWithText("Community").performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Steam Store options").performClick()
        compose.onNodeWithText("Refresh games").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, wishlist); assertEquals(1, community); assertEquals(2, loads) }
    }
}
