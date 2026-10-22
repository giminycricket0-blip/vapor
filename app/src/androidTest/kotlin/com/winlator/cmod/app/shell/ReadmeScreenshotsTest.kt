package com.winlator.cmod.app.shell

import androidx.compose.foundation.layout.Row
import com.winlator.cmod.feature.stores.steam.service.SteamCatalogItem
import com.winlator.cmod.feature.stores.steam.service.SteamCatalogPage
import com.winlator.cmod.feature.settings.SettingsNavSidebar
import com.winlator.cmod.feature.settings.SettingsNavItem
import com.winlator.cmod.feature.settings.DriversScreen
import com.winlator.cmod.feature.settings.DriversState
import com.winlator.cmod.feature.settings.InstalledDriverItem
import com.winlator.cmod.feature.settings.DriverRepo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.imageLoader
import coil.request.ImageRequest
import com.winlator.cmod.feature.stores.steam.data.DownloadInfo
import com.winlator.cmod.feature.stores.steam.data.SteamApp
import com.winlator.cmod.feature.stores.steam.enums.DownloadPhase
import com.winlator.cmod.shared.theme.WinNativeTheme
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * README screenshot set: the production Home, Library, game page and Downloads
 * components with public Steam catalog artwork and sample install/progress state.
 * No account, purchase, launch or save changes. Artwork is optional: if the CDN
 * is unreachable the artwork placeholders render instead. Captures are written to
 * the app's external files dir under readme/ for docs/images/.
 */
@RunWith(AndroidJUnit4::class)
class ReadmeScreenshotsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val games = listOf(
        SteamApp(id = 1289310, name = "Helltaker"), SteamApp(id = 367520, name = "Hollow Knight"),
        SteamApp(id = 413150, name = "Stardew Valley"), SteamApp(id = 620, name = "Portal 2"),
        SteamApp(id = 427520, name = "Factorio"), SteamApp(id = 400, name = "Portal"),
        SteamApp(id = 440, name = "Team Fortress 2"), SteamApp(id = 250900, name = "The Binding of Isaac: Rebirth"),
        SteamApp(id = 105600, name = "Terraria"), SteamApp(id = 646570, name = "Slay the Spire"),
        SteamApp(id = 1145360, name = "Hades"), SteamApp(id = 504230, name = "Celeste"),
    )

    private val tabs = listOf("Home" to "home", "Library" to "library", "Store" to "steam", "Downloads" to "downloads")
        .map { UnifiedActivity.TabDef(it.first, it.second) }

    private fun preload(urls: List<String>) = runCatching {
        runBlocking {
            withTimeout(20_000) {
                urls.map { url -> async { context.imageLoader.execute(ImageRequest.Builder(context).data(url).build()) } }.awaitAll()
            }
        }
    }

    private fun capture(name: String, content: @Composable () -> Unit) {
        compose.setContent { WinNativeTheme { Box(Modifier.fillMaxSize().background(BgDark)) { content() } } }
        compose.waitForIdle()
        Thread.sleep(600) // let crossfades and focus animations settle
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(context.getExternalFilesDir(null), "readme/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable private fun Header(selected: Int) = VaporHeader(
        tabs = tabs, selectedIndex = selected, onSelect = {}, searchQuery = TextFieldValue(""),
        onSearchQueryChange = {}, searchExpanded = false, onToggleSearch = {}, onFilter = {},
        onFriends = {}, onSettings = {}, onQuickAccess = {}, onMoreStores = {},
    )

    @Test fun home() {
        preload(games.map { it.getLibraryCapsuleUrl() } + games.first().getHeroUrl())
        capture("home") {
            Column(Modifier.fillMaxSize()) {
                Header(0)
                Box(Modifier.weight(1f)) {
                    var selected by remember { mutableIntStateOf(0) }
                    VaporGameShelf(games, true, selected, games.first().getHeroUrl(), true, { selected = it }, {}, onSettings = {}) {
                            game, _, focused, modifier, activate ->
                        GameCapsule(game, useLibraryCapsule = true, showCaption = false, isFocusedOverride = focused,
                            isControllerActive = true, onClick = activate, modifier = modifier)
                    }
                }
            }
        }
    }

    @Test fun library() {
        preload(games.map { it.getLibraryCapsuleUrl() })
        capture("library") {
            Column(Modifier.fillMaxSize()) {
                Header(1)
                LazyVerticalGrid(columns = GridCells.Adaptive(118.dp), modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    itemsIndexed(games, key = { _, game -> game.id }) { index, game ->
                        GameCapsule(game, onClick = {}, installStatus = index % 3 != 1, useLibraryCapsule = true,
                            isFocusedOverride = index == 0, isControllerActive = true, modifier = Modifier.aspectRatio(2f / 3f))
                    }
                }
            }
        }
    }

    @Test fun gamePage() {
        val art = File(context.getExternalFilesDir(null), "game-page-art.jpg").takeIf { it.isFile }
            ?.let { BitmapFactory.decodeFile(it.path) }
        capture("game-page") {
            LibraryGameLaunchScreen(
                steamAppId = null, appName = "Helltaker", subtitle = "Puzzle adventure · sample play history",
                sourceLabel = "Steam", heroImageUrl = art, customHeroImageCacheKey = null,
                releaseDateEpochSeconds = 0, totalPlaytimeMillis = 5_400_000, playCount = 3, lastPlayedMillis = 0,
                installSizeText = "350 MB", removalKeepsFiles = false, hasPinnedShortcut = false,
                steamMenuEnabled = true, areSteamActionsEnabled = true, playEnabled = true, playDisabledLabel = null,
                branches = emptyList(), selectedBranchId = "public", onSelectBranch = {},
                showSaveTransfer = false, onSaveTransfer = null, onBack = {}, onPlay = {}, onSettings = {},
                onBootToDesktop = {}, onAchievements = {}, onShortcut = {}, onCloudSaves = {}, onVerifyFiles = {},
                onCheckForUpdate = {}, onWorkshop = {}, onUninstall = {}, onCheats = null,
            )
        }
    }


    @Composable private fun Store() {
        val items = remember { listOf(
            Triple(1145360, "Hades", "€24.50"), Triple(504230, "Celeste", "€19.99"), Triple(646570, "Slay the Spire", "€24.99"),
            Triple(105600, "Terraria", "€9.99"), Triple(250900, "The Binding of Isaac: Rebirth", "€14.99"), Triple(1794680, "Vampire Survivors", "€4.99"),
            Triple(367520, "Hollow Knight", "€14.79"), Triple(413150, "Stardew Valley", "€13.99"),
        ).map { (id, name, price) ->
            SteamCatalogItem(id, name, false, false, price, "https://cdn.cloudflare.steamstatic.com/steam/apps/$id/header.jpg",
                "", "", true, false, emptyList())
        } }
        Column(Modifier.fillMaxSize()) {
            Header(2)
            Box(Modifier.weight(1f)) {
                SteamCatalogBrowser(ownedIds = setOf(367520), onOpenGame = {}, onWishlist = {}, onCommunity = {}, onBack = {},
                    loadPage = { _, start, _, _ -> SteamCatalogPage(items, start, items.size, items.size) })
            }
        }
    }

    @Composable private fun SettingsDrivers() = Row(Modifier.fillMaxSize()) {
        SettingsNavSidebar(selectedItem = SettingsNavItem.DRIVERS, onItemSelected = {}, onBackPressed = {})
        Box(Modifier.weight(1f)) {
            DriversScreen(
                state = DriversState(installedDrivers = listOf(
                    InstalledDriverItem(id = "system", name = "System driver", version = "Vulkan 1.3", selected = true, removable = false),
                    InstalledDriverItem(id = "wn-b", name = "WN Turnip (balanced)", version = "26.2.2"),
                    InstalledDriverItem(id = "wn-p", name = "WN Turnip (performance)", version = "26.2.2"),
                ), sources = listOf(DriverRepo("WinNative Drivers", "https://github.com/WinNative-Emu/Drivers", "https://api.github.com/repos/WinNative-Emu/Drivers/releases"))),
                onInstallFromFile = {}, onPlatformSelected = {}, onSelectDriver = {}, onSourceTapped = {}, onReleaseTapped = {},
                onDownloadAsset = {}, onRemoveDriver = {}, onRepoAdded = { _, _ -> }, onRepoUpdated = { _, _, _ -> },
                onRepoDeleted = {}, onRestoreDefaultRepos = {},
            )
        }
    }

    @Test fun store() {
        preload(listOf(1145360, 504230, 646570, 105600, 250900, 1794680, 367520, 413150)
            .map { "https://cdn.cloudflare.steamstatic.com/steam/apps/$it/header.jpg" })
        capture("store") { Store() }
    }

    @Test fun settings() = capture("settings") { SettingsDrivers() }

    private fun download(phase: DownloadPhase, progress: Float, total: Long) =
        DownloadInfo(gameId = 1, downloadingAppIds = CopyOnWriteArrayList()).apply {
            setTotalExpectedBytes(total); initializeBytesDownloaded((progress * total).toLong())
            setProgress(progress); updateStatus(phase, null)
        }

    @Test fun downloads() {
        val hollow = games[1]; val stardew = games[2]; val factorio = games[4]
        preload(listOf(hollow, stardew, factorio).map { it.getCapsuleUrl() })
        val active = download(DownloadPhase.DOWNLOADING, .46f, 9_663_676_416L).apply { updateCurrentFileName("Hollow Knight_Data/sharedassets12.assets") }
        val queued = download(DownloadPhase.PAUSED, .18f, 501_219_328L)
        val complete = download(DownloadPhase.COMPLETE, 1f, 1_231_945_728L)
        capture("downloads") {
            Column(Modifier.fillMaxSize()) {
                Header(3)
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    DownloadQueueCard(hollow.name, hollow.getCapsuleUrl(), "Steam", active, false, {}, {}, {})
                    DownloadQueueCard(stardew.name, stardew.getCapsuleUrl(), "Steam", queued, false, {}, {}, {})
                    DownloadQueueCard(factorio.name, factorio.getCapsuleUrl(), "Steam", complete, false, {}, {}, {}, {})
                }
            }
        }
    }
}
