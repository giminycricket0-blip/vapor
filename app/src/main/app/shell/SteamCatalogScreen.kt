package com.winlator.cmod.app.shell

import android.app.Activity
import android.content.Intent
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.winlator.cmod.feature.stores.steam.SteamLoginActivity
import com.winlator.cmod.feature.stores.steam.service.SteamService
import com.winlator.cmod.feature.stores.steam.service.acquireCatalogApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.winlator.cmod.shared.ui.focus.controllerFocusGlow
import com.winlator.cmod.shared.ui.focus.controllerConfirmOnA
import com.winlator.cmod.shared.ui.focus.isUnclaimedGamepadButton
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.winlator.cmod.R
import com.winlator.cmod.feature.stores.steam.data.SteamApp
import com.winlator.cmod.feature.stores.steam.service.SteamCatalog
import com.winlator.cmod.feature.stores.steam.service.SteamCatalogItem
import com.winlator.cmod.feature.stores.steam.service.SteamCatalogPage
import com.winlator.cmod.feature.stores.steam.service.SteamCatalogSection
import com.winlator.cmod.feature.stores.steam.service.SteamCatalogGenre
import com.winlator.cmod.app.service.NetworkMonitor
import java.io.IOException
import com.winlator.cmod.shared.ui.nav.DialogPaneNav
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.PaneNavRegistry
import com.winlator.cmod.shared.ui.nav.paneNavItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale

/** Native browsing and free-license acquisition; purchases use Steam checkout. */
@Composable
internal fun UnifiedActivity.SteamCatalogScreen(
    ownedApps: List<SteamApp>,
    onOwnedGame: (SteamApp) -> Unit,
    onRefreshLibrary: () -> Unit,
    onBack: () -> Unit,
) {
    var selectedAppId by rememberSaveable { mutableIntStateOf(0) }
    var pendingInstallAppId by rememberSaveable { mutableIntStateOf(0) }
    var acquiring by remember { mutableStateOf(false) }
    var acquireError by remember { mutableStateOf<String?>(null) }
    val signedIn by SteamService.isLoggedInFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val acquire: (Int) -> Unit = { appId ->
        if (!acquiring) {
            acquiring = true; acquireError = null
            scope.launch {
                try {
                    val app = SteamService.acquireCatalogApp(appId)
                    if (selectedAppId == appId) {
                        selectedAppId = 0
                        onOwnedGame(app)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { if (selectedAppId == appId) acquireError = error.message }
                finally { acquiring = false }
            }
        }
    }
    val latestAcquire by rememberUpdatedState(acquire)
    val loginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val requested = pendingInstallAppId
        pendingInstallAppId = 0
        if (result.resultCode == Activity.RESULT_OK && requested > 0) latestAcquire(requested)
    }
    var webUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedPreview by remember { mutableStateOf<SteamCatalogItem?>(null) }
    val ownedById = remember(ownedApps) { ownedApps.associateBy { it.id } }
    val refresh by rememberUpdatedState(onRefreshLibrary)
    SteamCatalogBrowser(
        ownedIds = ownedById.keys,
        active = selectedAppId == 0 && webUrl == null,
        onOpenGame = { selectedPreview = it; selectedAppId = it.appId; acquireError = null },
        onWishlist = { webUrl = "https://store.steampowered.com/wishlist/" },
        onCommunity = { webUrl = "https://steamcommunity.com/" },
        onBack = onBack,
        registerKeyHandler = { handler ->
            steamCatalogKeyHandler = handler
            val unregister = { if (steamCatalogKeyHandler === handler) steamCatalogKeyHandler = null }
            unregister
        },
    )
    if (selectedAppId > 0) {
        val appId = selectedAppId
        val preview = selectedPreview?.takeIf { it.appId == appId }
        val close = { selectedAppId = 0; acquireError = null }
        Dialog(onDismissRequest = close,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            val detailNav = remember(appId) { PaneNavRegistry() }
            CompositionLocalProvider(LocalPaneNav provides detailNav) {
                DialogPaneNav(detailNav, onDismiss = close)
                Surface(color = BgDark, modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(horizontal = 20.dp)) {
                        IconButton(onClick = close, modifier = Modifier.paneNavItem(onActivate = close)) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_ui_back))
                        }
                        key(appId) {
                            SteamCatalogDetails(appId, preview, ownedById[appId], signedIn,
                                acquiring, acquireError,
                                onOwnedGame = { selectedAppId = 0; onOwnedGame(it) },
                                onAcquire = { acquire(appId) },
                                onSignIn = {
                                    pendingInstallAppId = appId
                                    loginLauncher.launch(Intent(this@SteamCatalogScreen, SteamLoginActivity::class.java))
                                },
                                onDemo = { selectedAppId = it; acquireError = null },
                                onCheckout = { webUrl = "https://store.steampowered.com/app/$appId/" },
                                onWeb = { webUrl = it }, onRefreshLibrary = refresh)
                        }
                    }
                }
            }
        }
    }
    webUrl?.let { url ->
        key(url) { SteamWebPanel(url, signedIn = signedIn,
            onConnect = { loginLauncher.launch(Intent(this@SteamCatalogScreen, SteamLoginActivity::class.java)) },
            onInstall = { id -> webUrl = null; selectedAppId = id; acquireError = null; refresh() },
            onClose = { webUrl = null; refresh() }) }
    }

}

private enum class CatalogMenu { SECTION, GENRE, ACTIONS }

/** Public catalog flow. Account, license, detail and web-panel actions stay with the activity. */
@Composable
internal fun SteamCatalogBrowser(
    ownedIds: Set<Int>,
    onOpenGame: (SteamCatalogItem) -> Unit,
    onWishlist: () -> Unit,
    onCommunity: () -> Unit,
    onBack: () -> Unit,
    active: Boolean = true,
    registerKeyHandler: ((KeyEvent) -> Boolean) -> (() -> Unit) = { {} },
    loadPage: suspend (SteamCatalogSection, Int, String, SteamCatalogGenre) -> SteamCatalogPage = SteamCatalog::browse,
) {
    var section by rememberSaveable { mutableStateOf(SteamCatalogSection.POPULAR) }
    var genre by rememberSaveable { mutableStateOf(SteamCatalogGenre.ALL) }
    var openMenu by remember { mutableStateOf<CatalogMenu?>(null) }
    var input by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    // Steam's next offset uses the returned count, which can differ from PAGE_SIZE.
    var pageStarts by rememberSaveable { mutableStateOf(listOf(0)) }
    val start = pageStarts.last()
    var retry by remember { mutableIntStateOf(0) }
    // Retain only this query's last rendered page through refresh/retry. This is
    // screen memory, not an HTTP/disk cache (Steam's API responses are no-store).
    var page by remember(section, genre, start, search) { mutableStateOf<SteamCatalogPage?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }
    val context = LocalContext.current.applicationContext
    val online by NetworkMonitor.hasInternet.collectAsStateWithLifecycle()
    var reconnectPending by remember { mutableStateOf(!online) }
    val currentLoader by rememberUpdatedState(loadPage)
    val clearFilters: () -> Unit = {
        input = ""; search = ""; section = SteamCatalogSection.ALL
        genre = SteamCatalogGenre.ALL; pageStarts = listOf(0)
        keyboard?.hide(); focus.clearFocus()
    }
    val hasFilters = input.isNotBlank() || genre != SteamCatalogGenre.ALL ||
        section in setOf(SteamCatalogSection.FREE, SteamCatalogSection.DEMOS, SteamCatalogSection.DEALS)
    val back: () -> Unit = {
        if (search.isNotEmpty() || input.isNotEmpty()) {
            input = ""; search = ""; pageStarts = listOf(0)
            focus.clearFocus(); keyboard?.hide()
        } else onBack()
    }
    val submit: () -> Unit = {
        val changed = search != input.trim()
        search = input.trim(); pageStarts = listOf(0)
        if (!changed && failed) retry++
        keyboard?.hide(); focus.clearFocus()
    }
    val latestKeyHandler by rememberUpdatedState<(KeyEvent) -> Boolean> { event ->
        when {
            !active || openMenu != null -> false
            event.keyCode == KeyEvent.KEYCODE_BUTTON_B -> {
                if (event.action == KeyEvent.ACTION_DOWN) back()
                true
            }
            searchFocused -> false
            event.keyCode == KeyEvent.KEYCODE_BUTTON_Y -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    searchFocus.requestFocus(); keyboard?.show()
                }
                true
            }
            isUnclaimedGamepadButton(event.keyCode) -> true
            else -> false
        }
    }
    DisposableEffect(Unit) {
        val unregister = registerKeyHandler { latestKeyHandler(it) }
        onDispose(unregister)
    }
    BackHandler(enabled = active && openMenu == null, onBack = back)
    LaunchedEffect(input) {
        if (input.trim() != search) {
            delay(350)
            search = input.trim(); pageStarts = listOf(0)
        }
    }
    LaunchedEffect(online, active) {
        timber.log.Timber.tag("SteamCatalog").d("Connection online=%s active=%s pending=%s failed=%s loading=%s",
            online, active, reconnectPending, failed, loading)
        // Keep the reconnect signal while details/checkout own the foreground.
        // Also replace an old-network request that is still waiting, but leave a
        // successfully loaded page alone. No polling or background retry loop.
        if (!online) reconnectPending = true
        else if (active && reconnectPending) {
            withContext(Dispatchers.IO) { SteamCatalog.discardIdleConnections() }
            if (failed || loading) retry++
            reconnectPending = false
        }
    }
    LaunchedEffect(section, genre, start, search, retry) {
        loading = true; failed = false
        timber.log.Timber.tag("SteamCatalog").d("Page start section=%s offset=%s searching=%s retry=%s",
            section, start, search.isNotBlank(), retry)
        if (page == null) grid.scrollToItem(0)
        try {
            // A missing validation signal is not proof that transport is unavailable.
            // Preserve the existing fallback, but never make its Binder query on Main.
            if (!NetworkMonitor.hasInternet.value &&
                withContext(Dispatchers.IO) { NetworkMonitor.isOffline(context) })
                throw IOException("Steam Store is offline")
            val result = currentLoader(section, start, search, genre)
            currentCoroutineContext().ensureActive()
            page = result
            timber.log.Timber.tag("SteamCatalog").d("Page loaded count=%s", result.items.size)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            timber.log.Timber.tag("SteamCatalog").w("Page failed: %s", error.javaClass.simpleName)
            failed = true
        }
        finally { if (currentCoroutineContext().isActive) loading = false }
    }

    BoxWithConstraints(Modifier.fillMaxSize().controllerConfirmOnA().imePadding().padding(horizontal = 16.dp)) {
        val fontScale = LocalDensity.current.fontScale
        val wide = maxWidth / fontScale >= 680.dp
        val stackFilters = maxWidth / fontScale < 240.dp
        val searchLabel = stringResource(R.string.vapor_store_search)
        val sectionLabel = stringResource(catalogSectionLabel(section))
        val genreLabel = stringResource(catalogGenreLabel(genre))
        val menuModifier = Modifier.widthIn(min = 200.dp, max = 320.dp).onPreviewKeyEvent { event ->
            when (event.nativeKeyEvent.keyCode) {
                KeyEvent.KEYCODE_BUTTON_B -> {
                    if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) openMenu = null
                    true
                }
                else -> isUnclaimedGamepadButton(event.nativeKeyEvent.keyCode)
            }
        }
        val searchField: @Composable (Modifier) -> Unit = { fieldModifier ->
            BasicTextField(input, onValueChange = { input = it }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                cursorBrush = SolidColor(Accent),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                modifier = fieldModifier.focusRequester(searchFocus).onFocusChanged { searchFocused = it.isFocused }
                    .semantics { contentDescription = searchLabel }
                    .vaporGlass(RoundedCornerShape(12.dp))
                    .border(1.dp, if (searchFocused) Accent else TextSecondary.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                decorationBox = { field ->
                    Row(Modifier.heightIn(min = 48.dp).padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Search, null, Modifier.size(20.dp), tint = TextSecondary)
                        Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                            if (input.isEmpty()) Text(searchLabel, color = TextSecondary,
                                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            field()
                        }
                        if (input.isNotEmpty()) IconButton(onClick = { input = ""; submit() },
                            modifier = Modifier.size(48.dp).controllerFocusGlow()) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.workshop_clear_search),
                                Modifier.size(20.dp), tint = TextSecondary)
                        } else Spacer(Modifier.width(4.dp))
                    }
                })
        }
        val sectionSelector: @Composable (Modifier) -> Unit = { selectorModifier ->
            val description = stringResource(R.string.vapor_store_game_filter)
            Box(selectorModifier) {
                TextButton(onClick = { keyboard?.hide(); focus.clearFocus(); openMenu = CatalogMenu.SECTION },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).controllerFocusGlow()
                        .semantics { contentDescription = description; stateDescription = sectionLabel }) {
                    Text(sectionLabel, Modifier.weight(1f), color = TextPrimary,
                        style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.padding(start = 8.dp).size(18.dp), tint = TextSecondary)
                }
                DropdownMenu(expanded = openMenu == CatalogMenu.SECTION, onDismissRequest = { openMenu = null },
                    modifier = menuModifier) {
                    listOf(SteamCatalogSection.POPULAR, SteamCatalogSection.ALL, SteamCatalogSection.FREE,
                        SteamCatalogSection.DEMOS, SteamCatalogSection.DEALS).forEach { option ->
                        DropdownMenuItem(text = { Text(stringResource(catalogSectionLabel(option)), style = MaterialTheme.typography.bodyMedium) },
                            leadingIcon = { if (section == option) Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), tint = Accent)
                                else Spacer(Modifier.size(20.dp)) },
                            modifier = Modifier.semantics { selected = section == option }.controllerFocusGlow(),
                            onClick = { section = option; pageStarts = listOf(0); openMenu = null })
                    }
                }
            }
        }
        val genreSelector: @Composable (Modifier) -> Unit = { selectorModifier ->
            val description = stringResource(R.string.vapor_store_category_filter)
            Box(selectorModifier) {
                TextButton(onClick = { keyboard?.hide(); focus.clearFocus(); openMenu = CatalogMenu.GENRE },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).controllerFocusGlow()
                        .semantics { contentDescription = description; stateDescription = genreLabel }) {
                    Text(genreLabel, Modifier.weight(1f), color = if (genre == SteamCatalogGenre.ALL) TextSecondary else Accent,
                        style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.padding(start = 8.dp).size(18.dp), tint = TextSecondary)
                }
                DropdownMenu(expanded = openMenu == CatalogMenu.GENRE, onDismissRequest = { openMenu = null },
                    modifier = menuModifier) {
                    SteamCatalogGenre.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(stringResource(catalogGenreLabel(option)), style = MaterialTheme.typography.bodyMedium) },
                            leadingIcon = { if (genre == option) Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), tint = Accent)
                                else Spacer(Modifier.size(20.dp)) },
                            modifier = Modifier.semantics { selected = genre == option }.controllerFocusGlow(),
                            onClick = { genre = option; pageStarts = listOf(0); openMenu = null })
                    }
                }
            }
        }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                searchField(Modifier.weight(1f))
                if (wide) {
                    sectionSelector(Modifier.width(140.dp * fontScale))
                    genreSelector(Modifier.width(160.dp * fontScale))
                }
                Box {
                    IconButton(onClick = { keyboard?.hide(); focus.clearFocus(); openMenu = CatalogMenu.ACTIONS },
                        modifier = Modifier.size(48.dp).controllerFocusGlow()) {
                        Icon(Icons.Outlined.MoreVert, stringResource(R.string.vapor_store_options), tint = TextSecondary,
                            modifier = Modifier.size(22.dp))
                    }
                    DropdownMenu(expanded = openMenu == CatalogMenu.ACTIONS, onDismissRequest = { openMenu = null },
                        modifier = menuModifier) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.vapor_steam_wishlist)) },
                            leadingIcon = { Icon(Icons.Outlined.FavoriteBorder, null, Modifier.size(20.dp)) },
                            onClick = { openMenu = null; onWishlist() }, modifier = Modifier.controllerFocusGlow())
                        DropdownMenuItem(text = { Text(stringResource(R.string.vapor_steam_community)) },
                            leadingIcon = { Icon(Icons.Outlined.PeopleOutline, null, Modifier.size(20.dp)) },
                            onClick = { openMenu = null; onCommunity() }, modifier = Modifier.controllerFocusGlow())
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(stringResource(R.string.vapor_store_refresh)) },
                            leadingIcon = { Icon(Icons.Outlined.Refresh, null, Modifier.size(20.dp)) },
                            enabled = !loading, onClick = { openMenu = null; retry++ }, modifier = Modifier.controllerFocusGlow())
                    }
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val width = maxWidth / LocalDensity.current.fontScale
                val columns = when { width < 340.dp -> 1; width < 600.dp -> 2; width < 840.dp -> 3; else -> 4 }
                val items = page?.items.orEmpty()
                val featured = items.firstOrNull()?.takeIf {
                    section == SteamCatalogSection.POPULAR && search.isBlank() && start == 0
                }
                LazyVerticalGrid(columns = GridCells.Fixed(columns), state = grid,
                    modifier = Modifier.fillMaxSize().testTag("steam_catalog_grid"),
                    contentPadding = PaddingValues(bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item(key = "summary", span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            if (!wide) {
                                if (stackFilters) Column(Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    sectionSelector(Modifier.fillMaxWidth())
                                    genreSelector(Modifier.fillMaxWidth())
                                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    sectionSelector(Modifier.weight(1f))
                                    genreSelector(Modifier.weight(1f))
                                }
                            }
                            Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.weight(1f)) {
                                    if (page != null) Text(
                                        if (search.isBlank()) stringResource(R.string.vapor_store_filtered_count, sectionLabel, page?.total ?: 0)
                                        else stringResource(R.string.vapor_store_results, page?.total ?: 0),
                                        color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                                }
                                if (hasFilters) {
                                    val resetLabel = stringResource(R.string.vapor_store_reset_filters)
                                    TextButton(onClick = clearFilters, contentPadding = PaddingValues(horizontal = 8.dp),
                                        modifier = Modifier.heightIn(min = 48.dp).controllerFocusGlow()
                                            .semantics { contentDescription = resetLabel }) {
                                        Text(stringResource(R.string.common_ui_reset), style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                    when {
                        loading && page == null -> item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                CircularProgressIndicator(color = Accent, modifier = Modifier.size(28.dp))
                                Text(stringResource(if (search.isBlank()) R.string.vapor_store_loading else R.string.vapor_store_searching),
                                    color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        failed && page == null -> item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Outlined.CloudOff, null, tint = TextSecondary, modifier = Modifier.size(32.dp))
                                Text(stringResource(if (online) R.string.vapor_store_error else R.string.vapor_store_offline), color = TextSecondary,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                Button(onClick = { retry++ }, modifier = Modifier.heightIn(min = 48.dp).controllerFocusGlow()) {
                                    Text(stringResource(R.string.steam_login_retry))
                                }
                            }
                        }
                        else -> {
                            if (loading || failed) item(key = "refresh-status", span = { GridItemSpan(maxLineSpan) }) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (loading) CircularProgressIndicator(color = Accent,
                                        modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Text(stringResource(when {
                                        !failed -> R.string.vapor_store_refreshing
                                        !online -> R.string.vapor_store_offline_results
                                        else -> R.string.vapor_store_cached_results
                                    }),
                                        Modifier.weight(1f), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                                    if (failed) TextButton(onClick = { retry++ },
                                        modifier = Modifier.heightIn(min = 48.dp).controllerFocusGlow()) {
                                        Text(stringResource(R.string.steam_login_retry))
                                    }
                                }
                            }
                            val suggestion = page?.suggestions?.firstOrNull {
                                it.isNotBlank() && !it.equals(search, ignoreCase = true)
                            }?.takeIf { search.isNotBlank() && items.isEmpty() }
                            if (suggestion != null) {
                                item(key = "suggestion:$suggestion", span = { GridItemSpan(maxLineSpan) }) {
                                    TextButton(onClick = { input = suggestion; search = suggestion; pageStarts = listOf(0); keyboard?.hide(); focus.clearFocus() },
                                        modifier = Modifier.controllerFocusGlow()) {
                                        Text(stringResource(R.string.vapor_store_suggestion, suggestion))
                                    }
                                }
                            }
                            if (items.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Icon(Icons.Outlined.SearchOff, null, tint = TextSecondary, modifier = Modifier.size(32.dp))
                                    Text(stringResource(R.string.vapor_no_results), color = TextPrimary)
                                    Text(stringResource(R.string.vapor_store_empty_hint), color = TextSecondary,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                    TextButton(onClick = clearFilters, modifier = Modifier.heightIn(min = 48.dp).controllerFocusGlow()) {
                                        Text(stringResource(R.string.vapor_store_reset_filters))
                                    }
                                }
                            }
                            featured?.let { game ->
                                item(key = game.appId, span = { GridItemSpan(maxLineSpan) }) {
                                    SteamCatalogSpotlight(game,
                                        if (game.appId in ownedIds) stringResource(R.string.vapor_store_owned) else catalogPrice(game),
                                        Modifier) { onOpenGame(game) }
                                }
                            }
                            gridItems(if (featured != null) items.drop(1) else items, key = { it.appId }) { game ->
                                SteamCatalogCard(game.name, game.artwork,
                                    if (game.appId in ownedIds) stringResource(R.string.vapor_store_owned) else catalogPrice(game),
                                    Modifier) { onOpenGame(game) }
                            }
                            if (search.isBlank() && (pageStarts.size > 1 || page?.nextStart != null)) {
                                item(key = "pages", span = { GridItemSpan(maxLineSpan) }) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(onClick = { pageStarts = pageStarts.dropLast(1) }, enabled = pageStarts.size > 1,
                                            modifier = Modifier.heightIn(min = 48.dp).controllerFocusGlow()) {
                                            Text(stringResource(R.string.vapor_store_previous))
                                        }
                                        Text(stringResource(R.string.vapor_store_page, pageStarts.size),
                                            color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                                        TextButton(onClick = { page?.nextStart?.let { pageStarts = pageStarts + it } },
                                            enabled = page?.nextStart != null,
                                            modifier = Modifier.heightIn(min = 48.dp).controllerFocusGlow()) {
                                            Text(stringResource(R.string.setup_wizard_next))
                                        }
                                    }
                                }
                            }
                            item(key = "notes", span = { GridItemSpan(maxLineSpan) }) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (search.isNotBlank() && items.size >= SteamCatalog.SEARCH_LIMIT)
                                        Text(stringResource(R.string.vapor_store_refine_search, SteamCatalog.SEARCH_LIMIT),
                                            color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                                    Text(stringResource(R.string.vapor_store_compatibility), color = TextSecondary,
                                        style = MaterialTheme.typography.bodySmall)
                                    Text(stringResource(R.string.vapor_store_region,
                                        Locale("", SteamCatalog.countryCode).getDisplayCountry(Locale.getDefault())),
                                        color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun catalogSectionLabel(section: SteamCatalogSection): Int = when (section) {
    SteamCatalogSection.POPULAR -> R.string.vapor_store_popular
    SteamCatalogSection.ALL -> R.string.vapor_store_all
    SteamCatalogSection.FREE -> R.string.vapor_store_free_to_play
    SteamCatalogSection.DEMOS -> R.string.vapor_demos
    SteamCatalogSection.DEALS -> R.string.vapor_store_deals
}

private fun catalogGenreLabel(genre: SteamCatalogGenre): Int = when (genre) {
    SteamCatalogGenre.ALL -> R.string.vapor_store_categories_all
    SteamCatalogGenre.ACTION -> R.string.vapor_store_category_action
    SteamCatalogGenre.ADVENTURE -> R.string.vapor_store_category_adventure
    SteamCatalogGenre.RPG -> R.string.vapor_store_category_rpg
    SteamCatalogGenre.STRATEGY -> R.string.vapor_store_category_strategy
    SteamCatalogGenre.PUZZLE -> R.string.vapor_store_category_puzzle
    SteamCatalogGenre.PLATFORMER -> R.string.vapor_store_category_platformer
}

@Composable
internal fun SteamCatalogSpotlight(item: SteamCatalogItem, price: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.fillMaxWidth().heightIn(min = 180.dp).clip(RoundedCornerShape(12.dp))
        .background(SurfaceDark).controllerFocusGlow(cornerRadius = 12.dp).clickable(onClick = onClick)) {
        AsyncImage(item.artwork, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            listOf(Color.Transparent, Color(0xEE090F1C)))))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.vapor_store_spotlight), color = Accent,
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(item.name, color = Color.White, style = MaterialTheme.typography.headlineSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
            Text(price, color = Color.White, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun SteamCatalogCard(name: String, artwork: String?, status: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.vaporGlass(RoundedCornerShape(16.dp))
        .controllerFocusGlow(cornerRadius = 16.dp)
        .clickable(onClick = onClick)) {
        AsyncImage(artwork, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(460f / 215f).background(SurfaceDark))
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, color = TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 2,
                minLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(status, color = Accent, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun catalogPrice(item: SteamCatalogItem): String = when {
    item.isDemo -> stringResource(R.string.vapor_store_demo)
    item.isFree -> stringResource(R.string.vapor_store_free)
    item.price.isNotBlank() -> item.price
    item.comingSoon -> stringResource(R.string.common_ui_coming_soon)
    else -> stringResource(R.string.vapor_store_price_unavailable)
}

@Composable
private fun SteamCatalogDetails(
    appId: Int,
    preview: SteamCatalogItem?,
    owned: SteamApp?,
    signedIn: Boolean,
    acquiring: Boolean,
    acquireError: String?,
    onOwnedGame: (SteamApp) -> Unit,
    onAcquire: () -> Unit,
    onSignIn: () -> Unit,
    onDemo: (Int) -> Unit,
    onCheckout: () -> Unit,
    onRefreshLibrary: () -> Unit,
    onWeb: (String) -> Unit,
) {
    var game by remember { mutableStateOf(preview) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(retry) {
        failed = false
        try { game = SteamCatalog.details(appId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    val details = game
    if (details == null) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (failed) {
                Text(stringResource(R.string.vapor_store_details_error))
                TextButton(onClick = { retry++ }) { Text(stringResource(R.string.steam_login_retry)) }
            } else CircularProgressIndicator()
        }
        return
    }
    SteamCatalogDetailsContent(details, owned, signedIn, acquiring, acquireError, onOwnedGame,
        onAcquire, onSignIn, onDemo, onCheckout, onRefreshLibrary, detailsFailed = failed, onRetry = { retry++ }, onWeb = onWeb)
}

@Composable
internal fun SteamCatalogDetailsContent(
    game: SteamCatalogItem,
    owned: SteamApp?,
    signedIn: Boolean,
    acquiring: Boolean,
    acquireError: String?,
    onOwnedGame: (SteamApp) -> Unit,
    onAcquire: () -> Unit,
    onSignIn: () -> Unit,
    onDemo: (Int) -> Unit,
    onCheckout: () -> Unit,
    onRefreshLibrary: () -> Unit,
    detailsFailed: Boolean = false,
    onRetry: () -> Unit = {},
    onWeb: (String) -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp
        val artwork: @Composable (Modifier) -> Unit = { modifier ->
            AsyncImage(game.artwork, null, contentScale = ContentScale.Fit,
                modifier = modifier.clip(RoundedCornerShape(3.dp)).aspectRatio(460f / 215f))
        }
        val information: @Composable ColumnScope.() -> Unit = {
            Text(game.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            if (game.developer.isNotBlank()) Text(game.developer, color = TextSecondary)
            Text(if (owned != null) stringResource(R.string.vapor_store_owned) else catalogPrice(game),
                color = Accent, style = MaterialTheme.typography.titleMedium)
            val freeDownload = game.isDemo || game.isFree
            val downloadable = !game.comingSoon && game.windows != false
            val action: () -> Unit = {
                if (!acquiring) when {
                    owned != null -> onOwnedGame(owned)
                    freeDownload && signedIn && downloadable -> onAcquire()
                    freeDownload && downloadable -> onSignIn()
                    !freeDownload -> onCheckout()
                }
            }
            val enabled = !acquiring && (owned != null || !freeDownload || downloadable)
            Button(onClick = action, enabled = enabled, shape = RoundedCornerShape(3.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .paneNavItem(onActivate = action, isEntry = true)) {
                Text(stringResource(when {
                    acquiring -> R.string.vapor_store_adding
                    owned != null -> R.string.vapor_store_open_library
                    freeDownload && !downloadable -> R.string.vapor_store_download_unavailable
                    freeDownload && !signedIn -> R.string.vapor_store_connect_install
                    game.isDemo -> R.string.vapor_store_install_demo
                    freeDownload -> R.string.common_ui_install
                    else -> R.string.vapor_store_checkout
                }))
            }
            if (!game.isDemo) game.demoAppIds.forEach { demoId ->
                OutlinedButton(onClick = { onDemo(demoId) }, enabled = !acquiring,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .paneNavItem(onActivate = { if (!acquiring) onDemo(demoId) })) {
                    Text(stringResource(R.string.vapor_store_install_demo))
                }
            }
            if (acquireError != null) Text(acquireError, color = MaterialTheme.colorScheme.error)
            Text(stringResource(if (game.windows == false) R.string.vapor_store_no_windows else R.string.vapor_store_compatibility),
                color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            if (owned == null) {
                Text(stringResource(if (freeDownload) R.string.vapor_store_direct_download_note else R.string.vapor_store_checkout_note),
                    color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                if (!freeDownload) TextButton(onClick = onRefreshLibrary, modifier = Modifier.paneNavItem(onActivate = onRefreshLibrary)) {
                    Text(stringResource(R.string.vapor_store_refresh_library))
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf(R.string.vapor_steam_community to "https://steamcommunity.com/app/${game.appId}/",
                    R.string.vapor_steam_guides to "https://steamcommunity.com/app/${game.appId}/guides/",
                    R.string.vapor_steam_workshop to "https://steamcommunity.com/app/${game.appId}/workshop/").forEach { (label, url) ->
                    TextButton(onClick = { onWeb(url) }, modifier = Modifier.paneNavItem(onActivate = { onWeb(url) })) {
                        Text(stringResource(label))
                    }
                }
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                artwork(Modifier.weight(1f))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), content = information)
            } else {
                artwork(Modifier.fillMaxWidth())
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = information)
            }
            // EmuReady only lists Windows reports. Keep the action for Windows-capable
            // and unknown-platform entries; hide it only when Steam says it is not Windows.
            if (game.windows != false) CommunityReportsButton(game.appId, game.name, Modifier.fillMaxWidth())
            if (game.description.isNotBlank()) Text(game.description, color = TextPrimary)
            if (detailsFailed) {
                Text(stringResource(R.string.vapor_store_details_error), color = TextSecondary)
                TextButton(onClick = onRetry, modifier = Modifier.paneNavItem(onActivate = onRetry)) {
                    Text(stringResource(R.string.steam_login_retry))
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(game.screenshots) { shot ->
                    AsyncImage(shot, contentDescription = stringResource(R.string.vapor_store_screenshot, game.name),
                        contentScale = ContentScale.Fit, modifier = Modifier.width(300.dp).aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(3.dp)))
                }
            }
        }
    }
}
