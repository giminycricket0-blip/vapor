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
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.winlator.cmod.feature.stores.steam.data.DownloadInfo
import com.winlator.cmod.feature.stores.steam.data.SteamApp
import com.winlator.cmod.feature.stores.steam.enums.DownloadPhase
import com.winlator.cmod.shared.theme.WinNativeTheme
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Visual-review gallery for the main shell screens.
 *
 * Renders the production Home, Library, game page and Downloads components with labelled
 * sample data (no account, launch or save changes) and writes PNGs to
 * build/test-evidence/VaporUiGallery/. Public Steam CDN artwork loads through the normal
 * image loader when the network is available; otherwise the artwork placeholders show,
 * so both paths get reviewed. Used for before/after design reviews; the assertions only
 * check that each screen rendered a non-empty image.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w1100dp-h1000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VaporUiGalleryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val games = listOf(
        SteamApp(id = 400, name = "Portal"), SteamApp(id = 367520, name = "Hollow Knight"),
        SteamApp(id = 413150, name = "Stardew Valley"), SteamApp(id = 620, name = "Portal 2"),
        SteamApp(id = 427520, name = "Factorio"), SteamApp(id = 1289310, name = "Helltaker"),
        SteamApp(id = 286100, name = "You Have to Win the Game"), SteamApp(id = 440, name = "Team Fortress 2"),
    )

    private val tabs = listOf("Home" to "home", "Library" to "library", "Store" to "steam", "Downloads" to "downloads")
        .map { UnifiedActivity.TabDef(it.first, it.second) }

    private fun capture(name: String, width: Int, height: Int, content: @Composable () -> Unit) {
        var root: View? = null
        compose.setContent {
            WinNativeTheme {
                val view = LocalView.current
                SideEffect { root = view }
                Box(Modifier.size(width.dp, height.dp).background(BgDark)) { content() }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val view = root!!
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            val output = File("build/test-evidence/VaporUiGallery/$name.png")
            output.parentFile!!.mkdirs()
            output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue("$name rendered an empty image", image.width > 0 && image.height > 0)
            image.recycle()
        }
    }

    @Composable private fun Header(selected: Int) = VaporHeader(
        tabs = tabs, selectedIndex = selected, onSelect = {}, searchQuery = TextFieldValue(""),
        onSearchQueryChange = {}, searchExpanded = false, onToggleSearch = {}, onFilter = {},
        onFriends = {}, onSettings = {}, onQuickAccess = {}, onMoreStores = {},
    )

    @Composable private fun Shelf(hero: Any? = null, ambient: Color? = null) {
        var selected by remember { mutableIntStateOf(0) }
        VaporGameShelf(games, true, selected, hero, true, { selected = it }, {}, onSettings = {},
            ambientOverride = ambient) {
                game, _, focused, modifier, activate ->
            GameCapsule(game, useLibraryCapsule = true, showCaption = false, isFocusedOverride = focused,
                isControllerActive = true, onClick = activate, modifier = modifier)
        }
    }

    @Composable private fun Home() = Column(Modifier.fillMaxSize()) {
        Header(0)
        Box(Modifier.weight(1f)) { Shelf() }
        Text("UI gallery · sample games · no Steam account", color = TextSecondary,
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    }

    @Composable private fun Library(columns: Int) = Column(Modifier.fillMaxSize()) {
        Header(1)
        LazyVerticalGrid(columns = GridCells.Fixed(columns), modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            itemsIndexed(games, key = { _, game -> game.id }) { index, game ->
                GameCapsule(game, onClick = {}, installStatus = index % 3 == 0, useLibraryCapsule = true,
                    isFocusedOverride = index == 0, isControllerActive = true, modifier = Modifier.aspectRatio(2f / 3f))
            }
        }
    }

    @Composable private fun GamePage() = LibraryGameLaunchScreen(
        steamAppId = null, appName = "Helltaker", subtitle = "Game page · sample play history",
        sourceLabel = "Steam", heroImageUrl = null, customHeroImageCacheKey = null,
        releaseDateEpochSeconds = 0, totalPlaytimeMillis = 5_400_000, playCount = 3, lastPlayedMillis = 0,
        installSizeText = "350 MB", removalKeepsFiles = false, hasPinnedShortcut = false,
        steamMenuEnabled = true, areSteamActionsEnabled = true, playEnabled = true, playDisabledLabel = null,
        branches = emptyList(), selectedBranchId = "public", onSelectBranch = {},
        showSaveTransfer = false, onSaveTransfer = null, onBack = {}, onPlay = {}, onSettings = {},
        onBootToDesktop = {}, onAchievements = {}, onShortcut = {}, onCloudSaves = {}, onVerifyFiles = {},
        onCheckForUpdate = {}, onWorkshop = {}, onUninstall = {}, onCheats = null,
    )

    private fun download(phase: DownloadPhase, progress: Float) =
        DownloadInfo(gameId = 1, downloadingAppIds = CopyOnWriteArrayList()).apply {
            setTotalExpectedBytes(100); initializeBytesDownloaded((progress * 100).toLong())
            setProgress(progress); updateStatus(phase, null)
        }

    @Composable private fun Downloads() {
        val active = remember { download(DownloadPhase.DOWNLOADING, .46f).apply { updateCurrentFileName("Content/Paks/sample-game-data.pak") } }
        val complete = remember { download(DownloadPhase.COMPLETE, 1f) }
        val failed = remember { download(DownloadPhase.FAILED, .62f).apply { updateStatus(DownloadPhase.FAILED, "Connection interrupted. Your verified files are kept.") } }
        Column(Modifier.fillMaxSize()) {
            Header(3)
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                DownloadQueueCard("Sample adventure", null, "Steam", active, false, {}, {}, {})
                DownloadQueueCard("Sample puzzle game", null, "Steam", complete, false, {}, {}, {}, {})
                DownloadQueueCard("Sample strategy game", null, "Steam", failed, false, {}, {}, {})
            }
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

    /**
     * The ambient background only shows if the tint actually comes back from a decoded cover.
     * A local bitmap goes through the same Coil request path as real artwork, so this exercises
     * extraction, not just layout.
     */
    @Test
    fun homeAmbientLandscape() =
        capture("home-ambient-landscape", 960, 540) {
            val cover =
                java.io.File(compose.activity.cacheDir, "gallery-cover.png").apply {
                    parentFile?.mkdirs()
                    val bitmap = Bitmap.createBitmap(96, 144, Bitmap.Config.ARGB_8888)
                    for (y in 0 until 144) {
                        for (x in 0 until 96) {
                            bitmap.setPixel(x, y, android.graphics.Color.rgb(118 + x / 6, 58 + y / 9, 196))
                        }
                    }
                    outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            Column(Modifier.fillMaxSize()) {
                Header(0)
                Box(Modifier.weight(1f)) { Shelf(cover, Color(0xFF6C43C4)) }
            }
        }

    @Test fun homeLandscape() = capture("home-landscape", 960, 540) { Home() }
    @Test fun homePortrait() = capture("home-portrait", 412, 892) { Home() }
    @Test fun libraryLandscape() = capture("library-landscape", 960, 540) { Library(6) }
    @Test fun libraryPortrait() = capture("library-portrait", 412, 892) { Library(3) }
    @Test fun gamePageLandscape() = capture("game-page-landscape", 960, 540) { GamePage() }
    @Test fun gamePagePortrait() = capture("game-page-portrait", 412, 892) { GamePage() }
    @Test fun downloadsLandscape() = capture("downloads-landscape", 960, 540) { Downloads() }
    @Test fun downloadsPortrait() = capture("downloads-portrait", 412, 892) { Downloads() }
    @Test fun storeLandscape() = capture("store-landscape", 960, 540) { Store() }
    @Test fun storePortrait() = capture("store-portrait", 412, 892) { Store() }
    /** The default settings surface: no drivers, containers, components or presets. */
    @Test
    fun settingsSimpleLandscape() =
        capture("settings-simple-landscape", 960, 1000) {
            Row(Modifier.fillMaxSize()) { SettingsNavSidebar(selectedItem = SettingsNavItem.STORES, onItemSelected = {}, onBackPressed = {}) }
        }

    @Test fun settingsDriversLandscape() = capture("settings-drivers-landscape", 960, 540) { SettingsDrivers() }
}
