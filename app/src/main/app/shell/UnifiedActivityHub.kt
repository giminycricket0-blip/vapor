package com.winlator.cmod.app.shell
import com.winlator.cmod.app.shell.UnifiedActivity.TabDef

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.winlator.cmod.BuildConfig
import com.winlator.cmod.R
import com.winlator.cmod.app.PluviaApp
import com.winlator.cmod.app.db.PluviaDatabase
import com.winlator.cmod.app.service.DownloadService
import com.winlator.cmod.app.service.download.DownloadCoordinator
import com.winlator.cmod.app.update.UpdateService
import com.winlator.cmod.feature.library.LibraryCache
import com.winlator.cmod.feature.library.LibraryContentFilters
import com.winlator.cmod.feature.library.LibraryItemType
import com.winlator.cmod.feature.library.LibraryStoreLinks
import com.winlator.cmod.feature.library.LibraryStoreOption
import com.winlator.cmod.feature.library.LibraryStoreTransfer
import com.winlator.cmod.feature.settings.InputControlsFragment
import com.winlator.cmod.feature.stores.steam.enums.AppType
import com.winlator.cmod.runtime.linux.LinuxClientInstaller
import com.winlator.cmod.feature.settings.SettingsFocusZone
import com.winlator.cmod.feature.settings.SettingsHost
import com.winlator.cmod.feature.settings.SettingsNavBridge
import com.winlator.cmod.feature.settings.SettingsNavItem
import com.winlator.cmod.feature.setup.SetupWizardActivity
import com.winlator.cmod.feature.shortcuts.LibraryShortcutUtils
import com.winlator.cmod.feature.shortcuts.LibraryShortcutArtwork
import com.winlator.cmod.feature.shortcuts.ShortcutBroadcastReceiver
import com.winlator.cmod.feature.shortcuts.ShortcutSettingsComposeDialog
import com.winlator.cmod.feature.shortcuts.ShortcutsFragment
import com.winlator.cmod.feature.stores.common.InstallStore
import com.winlator.cmod.feature.stores.common.StoreArtworkCache
import com.winlator.cmod.feature.stores.epic.data.EpicCredentials
import com.winlator.cmod.feature.stores.epic.data.EpicGame
import com.winlator.cmod.feature.stores.epic.data.EpicGameToken
import com.winlator.cmod.feature.stores.epic.service.EpicAuthManager
import com.winlator.cmod.feature.stores.epic.service.EpicCloudSavesManager
import com.winlator.cmod.feature.stores.epic.service.EpicConstants
import com.winlator.cmod.feature.stores.epic.service.EpicDownloadManager
import com.winlator.cmod.feature.stores.epic.service.EpicGameLauncher
import com.winlator.cmod.feature.stores.epic.service.EpicManager
import com.winlator.cmod.feature.stores.epic.service.EpicService
import com.winlator.cmod.feature.stores.epic.service.EpicUpdateInfo
import com.winlator.cmod.feature.stores.epic.ui.auth.EpicOAuthActivity
import com.winlator.cmod.feature.stores.gog.data.GOGDlcInfo
import com.winlator.cmod.feature.stores.gog.data.GOGGame
import com.winlator.cmod.feature.stores.gog.data.LibraryItem
import com.winlator.cmod.feature.stores.gog.service.GOGAuthManager
import com.winlator.cmod.feature.stores.gog.service.GOGConstants
import com.winlator.cmod.feature.stores.gog.service.GOGManifestSizes
import com.winlator.cmod.feature.stores.gog.service.GOGService
import com.winlator.cmod.feature.stores.gog.service.GOGUpdateInfo
import com.winlator.cmod.feature.stores.gog.ui.auth.GOGOAuthActivity
import com.winlator.cmod.feature.stores.itch.service.ItchLibrary
import com.winlator.cmod.feature.stores.itch.ui.auth.ItchLoginActivity
import com.winlator.cmod.feature.stores.steam.SteamLoginActivity
import com.winlator.cmod.feature.stores.steam.data.DepotInfo
import com.winlator.cmod.feature.stores.steam.data.DownloadInfo
import com.winlator.cmod.feature.stores.steam.data.SteamApp
import com.winlator.cmod.feature.stores.steam.enums.DownloadPhase
import com.winlator.cmod.feature.stores.steam.events.AndroidEvent
import com.winlator.cmod.feature.stores.steam.events.EventDispatcher
import com.winlator.cmod.feature.stores.steam.service.processLicenseList
import com.winlator.cmod.feature.stores.steam.service.SteamService
import com.winlator.cmod.feature.stores.steam.utils.PrefManager
import com.winlator.cmod.feature.stores.steam.utils.getAvatarURL
import com.winlator.cmod.feature.sync.CloudSyncHelper
import com.winlator.cmod.feature.sync.google.CloudSyncManager
import com.winlator.cmod.feature.sync.google.GameSaveBackupManager
import com.winlator.cmod.feature.sync.ui.CloudSavesContent
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.container.Shortcut
import com.winlator.cmod.runtime.display.XServerDisplayActivity
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.runtime.input.ControllerHelper
import com.winlator.cmod.runtime.wine.PeIconExtractor
import com.winlator.cmod.shared.android.ActivityResultHost
import com.winlator.cmod.shared.android.AppTerminationHelper
import com.winlator.cmod.shared.android.DirectoryPickerDialog
import com.winlator.cmod.shared.android.FixedFontScaleAppCompatActivity
import com.winlator.cmod.shared.android.RefreshRateUtils
import com.winlator.cmod.shared.io.StorageUtils
import com.winlator.cmod.shared.io.FileUtils
import com.winlator.cmod.shared.ui.CarouselView
import com.winlator.cmod.shared.ui.layout.isCompactWidth
import com.winlator.cmod.shared.ui.layout.isPortraitLayout
import com.winlator.cmod.shared.ui.layout.screenWidthDp
import com.winlator.cmod.shared.ui.dialog.PopupDialog
import com.winlator.cmod.shared.ui.dialog.PopupTextAction
import androidx.compose.foundation.focusGroup
import com.winlator.cmod.shared.ui.focus.controllerFocusGlow
import com.winlator.cmod.shared.ui.focus.controllerMenuInput
import com.winlator.cmod.shared.ui.focus.controllerTextFieldEscape
import com.winlator.cmod.shared.ui.nav.DialogPaneNav
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.PANE_DIR_ACTIVATE
import com.winlator.cmod.shared.ui.nav.PANE_DIR_DOWN
import com.winlator.cmod.shared.ui.nav.PANE_DIR_LEFT
import com.winlator.cmod.shared.ui.nav.PANE_DIR_RIGHT
import com.winlator.cmod.shared.ui.nav.PANE_DIR_SECONDARY
import com.winlator.cmod.shared.ui.nav.PANE_DIR_UP
import com.winlator.cmod.shared.ui.nav.PaneNavRegistry
import com.winlator.cmod.shared.ui.nav.paneNavItem
import com.winlator.cmod.shared.ui.FourByTwoGridView
import com.winlator.cmod.shared.ui.JoystickGridScroll
import com.winlator.cmod.shared.ui.JoystickListScroll
import com.winlator.cmod.shared.ui.ListView
import com.winlator.cmod.shared.ui.widget.chasingBorder
import com.winlator.cmod.shared.theme.WinNativeTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.Lazy
import com.winlator.cmod.feature.stores.steam.enums.EPersonaState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

// Main hub scaffold + top bar + glasses sheet + library carousel, split out of UnifiedActivity.kt (behavior-identical).

internal val StoreTabKeys = setOf("epic", "gog", "itch")
private val HeaderCollapseTriggerDistance = 24.dp
private const val HeaderRevealFraction = 0.5f
private val TabLabelAutoSize =
    TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 13.sp, stepSize = 0.5.sp)

@Composable
internal fun UnifiedActivity.UnifiedHub() {
    val horizontalNavigationInsets =
        WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)
    val initialLibraryLayoutMode = startupLibraryLayoutMode
    val initialContentFilters = startupContentFilters ?: mapOf("games" to true, "dlc" to false, "applications" to false, "tools" to false)
    if (!startupBootstrapReady || initialLibraryLayoutMode == null) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(BgDark)
                    .windowInsetsPadding(horizontalNavigationInsets),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(color = Accent)
                Text(
                    text = stringResource(R.string.common_ui_app_name),
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        return
    }

    var showAccountSetup by rememberSaveable { mutableStateOf(!PrefManager.storeAccountsSetupComplete) }
    var showAddCustomGame by remember { mutableStateOf(false) }
    var showQuickAccess by remember { mutableStateOf(false) }
    var libraryInstalledOnly by rememberSaveable { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    var searchQueryTfv by remember { mutableStateOf(TextFieldValue("")) }
    var isSearchExpanded by remember { mutableStateOf(false) }
    val searchQuery = searchQueryTfv.text
    var localLibraryRefreshKey by remember { mutableIntStateOf(0) }
    var shortcutDataRefreshKey by remember { mutableIntStateOf(0) }
    var iconRefreshKey by remember { mutableIntStateOf(0) }

    val currentRefreshSignal = this@UnifiedHub.libraryRefreshSignal
    val libraryRefreshKey = currentRefreshSignal + localLibraryRefreshKey
    val shortcutRefreshKey = libraryRefreshKey + shortcutDataRefreshKey
    val playtimeRefreshKey = this@UnifiedHub.libraryPlaytimeRefreshSignal

    val contentFilters = remember { mutableStateMapOf(*initialContentFilters.entries.map { it.key to it.value }.toTypedArray()) }
    var libraryLayoutMode by remember {
        mutableStateOf(
            runCatching { LibraryLayoutMode.valueOf(PrefManager.libraryLayoutMode) }
                .getOrElse { initialLibraryLayoutMode },
        )
    }
    var immersiveMode by remember { mutableStateOf(PrefManager.libraryImmersiveMode) }
    var immersiveBlur by remember { mutableStateOf(PrefManager.libraryImmersiveBlur) }
    var forceLandscape by remember { mutableStateOf(PrefManager.libraryForceLandscape) }
    val tabs = remember { buildTabs() }
    val primaryTabIndices = remember(tabs) { tabs.indices.filter { tabs[it].key !in StoreTabKeys } }
    val tabState = rememberSaveableStateHolder()
    var selectedIdx by rememberSaveable { mutableIntStateOf(0) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    LaunchedEffect(drawerState.isOpen) {
        drawerOpen = drawerState.isOpen
        if (!drawerState.isOpen) drawerNavBridge.controllerActive = false
    }
    val isLoggedIn by SteamService.isLoggedInFlow.collectAsStateWithLifecycle()
    val chatServiceEnabled by SteamService.chatServiceEnabledFlow.collectAsStateWithLifecycle()
    val chatAvailability = com.winlator.cmod.feature.stores.steam.friends.rememberSteamChatAvailability()
    val isEpicLoggedIn by EpicAuthManager.isLoggedInFlow.collectAsStateWithLifecycle()
    val isGogLoggedIn by GOGAuthManager.isLoggedInFlow.collectAsStateWithLifecycle()
    // Null until Room has answered, which is how the library tells "nothing owned" from "not read yet".
    val steamAppsOrNull by db.steamAppDao().getAllOwnedApps().collectAsStateWithLifecycle(initialValue = null)
    val steamApps = steamAppsOrNull ?: emptyList()
    val context = LocalContext.current
    val linuxClient by LinuxClientInstaller.state.collectAsStateWithLifecycle()
    val linuxClientWasWorking = remember { booleanArrayOf(false) }
    // Runtime installation/removal can change its installed games; rescan the Library.
    LaunchedEffect(linuxClient) {
        val found = linuxClient
        val removed = linuxClientWasWorking[0] && found is LinuxClientInstaller.State.Missing
        linuxClientWasWorking[0] = found is LinuxClientInstaller.State.Working
        if (found is LinuxClientInstaller.State.Installed || removed) localLibraryRefreshKey++
    }
    val persona by SteamService.instance?.localPersona?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(null) }
    val scope = rememberCoroutineScope()
    val openSteamStore: () -> Unit = {
        selectedIdx = tabs.indexOfFirst { it.key == "steam" }
    }
    val hubState = rootNavController?.getBackStackEntry("hub")?.savedStateHandle
    val requestedStore by hubState?.getStateFlow<String?>("open_store", null)?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(null) }
    LaunchedEffect(requestedStore) {
        requestedStore?.takeIf { it in StoreTabKeys }?.let { store ->
            selectedIdx = tabs.indexOfFirst { it.key == store }
            hubState?.set("open_store", null as String?)
        }
    }

    val openLibrary: () -> Unit = {
        libraryInstalledOnly = false
        selectedIdx = tabs.indexOfFirst { it.key == "library" }
    }
    val openMoreStores: () -> Unit = {
        selectedIdx = 0
        navigateToSettings(SettingsNavItem.MORE_STORES)
    }
    val rightDrawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val friends by SteamService.instance?.friendsList?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(emptyList<com.winlator.cmod.feature.stores.steam.data.SteamFriendEntry>()) }
    var chatFriend by remember { mutableStateOf<com.winlator.cmod.feature.stores.steam.data.SteamFriendEntry?>(null) }
    LaunchedEffect(isLoggedIn) { if (!isLoggedIn) chatFriend = null }
    val friendsDrawerOpen = rightDrawerState.isOpen
    LaunchedEffect(rightDrawerState.isOpen) {
        rightDrawerOpen = rightDrawerState.isOpen
        if (!rightDrawerState.isOpen) friendsDrawerNavBridge.controllerActive = false
    }
    LaunchedEffect(Unit) {
        (context as? UnifiedActivity)?.openFriendsSignal?.collect {
            if (rightDrawerState.isOpen) rightDrawerState.close() else rightDrawerState.open()
        }
    }
    var installedFriendGameIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    LaunchedEffect(friends) {
        val ids = friends.map { it.gameAppId }.filter { it > 0 }.distinct()
        installedFriendGameIds =
            withContext(Dispatchers.IO) { ids.filter { SteamService.isAppInstalled(it) }.toSet() }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, chatAvailability) {
        if (chatAvailability == com.winlator.cmod.feature.stores.steam.friends.SteamChatAvailability.READY) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    runCatching { SteamService.instance?.refreshFriends() }
                    kotlinx.coroutines.delay(30_000L)
                }
            }
        }
    }
    LaunchedEffect(lifecycle, chatAvailability, friendsDrawerOpen) {
        if (chatAvailability == com.winlator.cmod.feature.stores.steam.friends.SteamChatAvailability.READY && friendsDrawerOpen) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    runCatching { SteamService.instance?.syncFriendsPresence() }
                    kotlinx.coroutines.delay(5_000L)
                }
            }
        }
    }
    LaunchedEffect(isLoggedIn, chatServiceEnabled) {
        if (isLoggedIn && chatServiceEnabled) {
            runCatching { com.winlator.cmod.feature.stores.steam.chat.ChatOverlayService.start(context) }
        }
    }

    val epicAppsOrNull by db.epicGameDao().getAll().collectAsStateWithLifecycle(initialValue = null)
    val gogAppsOrNull by db.gogGameDao().getAll().collectAsStateWithLifecycle(initialValue = null)
    val epicApps = epicAppsOrNull ?: emptyList()
    val gogApps = gogAppsOrNull ?: emptyList()
    val storeListsReady = steamAppsOrNull != null && epicAppsOrNull != null && gogAppsOrNull != null

    val controllerState = rememberControllerConnectionState()
    val isControllerConnected = controllerState.isConnected
    val isPS = controllerState.isPlayStation
    val isLibraryTab = tabs.getOrNull(selectedIdx)?.key in setOf("home", "library")

    val libraryRefreshListener =
        remember {
            object : EventDispatcher.JavaEventListener {
                override fun onEvent(event: Any) {
                    when (event) {
                        is AndroidEvent.LibraryInstallStatusChanged -> {
                            localLibraryRefreshKey++
                            shortcutDataRefreshKey++
                            iconRefreshKey++
                        }
                        is AndroidEvent.LibraryArtworkChanged -> {
                            shortcutDataRefreshKey++
                            iconRefreshKey++
                        }
                    }
                }
            }
        }
    DisposableEffect(libraryRefreshListener) {
        PluviaApp.events.onJava(AndroidEvent.LibraryInstallStatusChanged::class, libraryRefreshListener)
        PluviaApp.events.onJava(AndroidEvent.LibraryArtworkChanged::class, libraryRefreshListener)
        onDispose {
            PluviaApp.events.offJava(AndroidEvent.LibraryInstallStatusChanged::class, libraryRefreshListener)
            PluviaApp.events.offJava(AndroidEvent.LibraryArtworkChanged::class, libraryRefreshListener)
        }
    }

    LaunchedEffect(isEpicLoggedIn) {
        if (isEpicLoggedIn) {
            EpicService.start(context)
        }
    }

    LaunchedEffect(isGogLoggedIn) {
        if (isGogLoggedIn) {
            GOGService.start(context)
        }
    }

    val epicLoginLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                val code = result.data?.getStringExtra(EpicOAuthActivity.EXTRA_AUTH_CODE)
                if (code != null) {
                    scope.launch {
                        val authResult = EpicAuthManager.authenticateWithCode(context, code)
                        if (authResult.isSuccess) {
                            com.winlator.cmod.shared.ui.toast.WinToast.show(
                                context,
                                R.string.stores_accounts_logged_in_epic,
                                android.widget.Toast.LENGTH_SHORT,
                            )
                        } else {
                            com.winlator.cmod.shared.ui.toast.WinToast.show(
                                context,
                                getString(R.string.stores_accounts_epic_login_failed, authResult.exceptionOrNull()?.message),
                                android.widget.Toast.LENGTH_LONG,
                            )
                        }
                    }
                }
            }
        }

    val gogLoginLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                val code = result.data?.getStringExtra(GOGOAuthActivity.EXTRA_AUTH_CODE)
                if (!code.isNullOrBlank()) {
                    scope.launch {
                        val authResult = GOGAuthManager.authenticateWithCode(context, code)
                        if (authResult.isSuccess) {
                            GOGService.start(context)
                            com.winlator.cmod.shared.ui.toast.WinToast.show(
                                context,
                                R.string.stores_accounts_logged_in_gog,
                                android.widget.Toast.LENGTH_SHORT,
                            )
                        } else {
                            com.winlator.cmod.shared.ui.toast.WinToast.show(
                                context,
                                getString(R.string.stores_accounts_gog_login_failed, authResult.exceptionOrNull()?.message),
                                android.widget.Toast.LENGTH_LONG,
                            )
                        }
                    }
                }
            }
        }

    var itchAuthRefreshKey by remember { mutableIntStateOf(0) }
    val itchLoginLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            itchAuthRefreshKey++
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                com.winlator.cmod.shared.ui.toast.WinToast.show(
                    context,
                    R.string.itch_store_signed_in,
                    android.widget.Toast.LENGTH_SHORT,
                )
            }
        }

    if (showAccountSetup) {
        val complete = { PrefManager.storeAccountsSetupComplete = true; showAccountSetup = false }
        Dialog(onDismissRequest = complete,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            com.winlator.cmod.feature.settings.SteamAccountSetupScreen(
                connected = isLoggedIn,
                onConnect = { startActivity(Intent(this@UnifiedHub, SteamLoginActivity::class.java)) },
                onContinue = complete,
            )
        }
    }

    val filteredSteamApps =
        remember(steamApps, contentFilters.toMap()) {
            steamApps.filter { app -> LibraryContentFilters.allows(app.type, contentFilters) }
        }

    var globalSettingsApp by remember { mutableStateOf<SteamApp?>(null) }
    var globalSettingsGogGame by remember { mutableStateOf<GOGGame?>(null) }

    LaunchedEffect(tabs.size) { if (selectedIdx !in tabs.indices) selectedIdx = 0 }
    LaunchedEffect(isLoggedIn, persona) {
        if (isLoggedIn && persona == null) {
            SteamService.requestUserPersona()
        }
    }

    val activity = LocalContext.current as? UnifiedActivity

    LaunchedEffect(tabs) {
        activity?.keyEventFlow?.collect { event ->
            val key = tabs.getOrNull(selectedIdx)?.key ?: "library"
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_BUTTON_L1 -> {
                    if (key in StoreTabKeys) return@collect
                    val current = primaryTabIndices.indexOf(selectedIdx)
                    selectedIdx = primaryTabIndices[if (current <= 0) primaryTabIndices.lastIndex else current - 1]
                }

                android.view.KeyEvent.KEYCODE_BUTTON_R1 -> {
                    if (key in StoreTabKeys) return@collect
                    val current = primaryTabIndices.indexOf(selectedIdx)
                    selectedIdx = primaryTabIndices[(current + 1) % primaryTabIndices.size]
                }

                android.view.KeyEvent.KEYCODE_BUTTON_START -> { showQuickAccess = true }

                android.view.KeyEvent.KEYCODE_BUTTON_SELECT -> {
                    if (key == "library") {
                        if (drawerState.isOpen) drawerState.close() else drawerState.open()
                    }
                }

                android.view.KeyEvent.KEYCODE_BUTTON_X -> {
                    if (key == "home") {
                        openLibrary()
                    } else if (key == "library") {
                        libraryInstalledOnly = !libraryInstalledOnly
                        activity?.libraryFocusIndex?.value = 0
                    }
                }

                android.view.KeyEvent.KEYCODE_BUTTON_THUMBL -> {
                    if (key == "library") {
                        activity?.openSearchSignal?.tryEmit(Unit)
                    }
                }

                android.view.KeyEvent.KEYCODE_BUTTON_THUMBR -> {
                    if (key == "library") {
                        showAddCustomGame = true
                    }
                }

                android.view.KeyEvent.KEYCODE_BUTTON_B -> {
                    if (chatFriend != null) {
                        chatFriend = null
                    } else if (rightDrawerState.isOpen) {
                        rightDrawerState.close()
                    } else if (drawerState.isOpen) {
                        drawerState.close()
                    } else if (globalSettingsApp != null) {
                        globalSettingsApp = null
                    } else if (globalSettingsGogGame != null) {
                        globalSettingsGogGame = null
                    } else if (showAddCustomGame) {
                        showAddCustomGame = false
                    } else if (isSearchExpanded) {
                        isSearchExpanded = false
                        searchQueryTfv = TextFieldValue("")
                    } else if (key in StoreTabKeys) {
                        openMoreStores()
                    } else if (key != "home") {
                        selectedIdx = 0
                    } else {
                        showExitDialog = true
                    }
                }

                android.view.KeyEvent.KEYCODE_BUTTON_Y -> {
                    if (key in setOf("home", "library") && (selectedSteamAppId != 0 || selectedGogGameId.isNotEmpty())) {
                        if (selectedLibrarySource == "GOG") {
                            globalSettingsGogGame = gogApps.find { it.id == selectedGogGameId }
                            return@collect
                        }
                        val isCustom = selectedSteamAppId < 0
                        val epicId = if (selectedSteamAppId >= 2000000000) selectedSteamAppId - 2000000000 else 0

                        globalSettingsApp = (
                            steamApps.find { it.id == selectedSteamAppId }
                                ?: if (isCustom) {
                                    SteamApp(id = selectedSteamAppId, name = selectedSteamAppName, developer = "Custom")
                                } else if (epicId > 0) {
                                    val epic = epicApps.find { it.id == epicId }
                                    SteamApp(
                                        id = selectedSteamAppId,
                                        name = selectedSteamAppName,
                                        developer = epic?.developer ?: "Epic Games",
                                        gameDir = epic?.installPath ?: "",
                                    )
                                } else {
                                    null
                                }
                        )
                    }
                }

                android.view.KeyEvent.KEYCODE_BUTTON_A, android.view.KeyEvent.KEYCODE_DPAD_CENTER -> {
                    if (key in setOf("home", "library") && (selectedSteamAppId != 0 || selectedGogGameId.isNotEmpty())) {
                        // Touch and controller selection share the install-or-play decision.
                        activity?.openHeroForFocusedSignal?.tryEmit(Unit)
                    } else if (key !in setOf("home", "library", "downloads")) {
                        storeItemClickCallback?.invoke(storeFocusIndex.value)
                    }
                }

            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl,
    ) {
    ModalNavigationDrawer(
        drawerState = rightDrawerState,
        drawerContent = {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr,
            ) {
                com.winlator.cmod.feature.stores.steam.friends.FriendsDrawerContent(
                    isOpen = rightDrawerState.isOpen,
                    onDismiss = { scope.launch { rightDrawerState.close() } },
                    self = persona ?: com.winlator.cmod.feature.stores.steam.data.SteamFriend(),
                    friends = friends,
                    installedGameIds = installedFriendGameIds,
                    availability = chatAvailability,
                    onSignIn = {
                        scope.launch { rightDrawerState.close() }
                        startActivity(Intent(this@UnifiedHub, SteamLoginActivity::class.java))
                    },
                    onSetState = { st -> scope.launch { SteamService.setPersonaState(st) } },
                    onOpenChat = { f -> chatFriend = f; scope.launch { rightDrawerState.close() } },
                    onJoinGame = { f ->
                        scope.launch { rightDrawerState.close() }
                        scope.launch {
                            val app = withContext(Dispatchers.IO) { SteamService.getAppInfoOf(f.gameAppId) }
                            val installed = withContext(Dispatchers.IO) { SteamService.getInstalledApp(f.gameAppId) }
                            val label = f.gameName.ifBlank { context.getString(R.string.steam_join_the_game) }
                            if (app != null && installed != null) {
                                android.widget.Toast.makeText(
                                    context, context.getString(R.string.steam_join_joining, f.name, label), android.widget.Toast.LENGTH_SHORT,
                                ).show()
                                launchSteamGame(context, ContainerManager(context), app, f.connectString)
                            } else {
                                android.widget.Toast.makeText(
                                    context,
                                    if (app != null) context.getString(R.string.steam_join_install, label, f.name)
                                    else context.getString(R.string.steam_join_not_owned, label),
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                    onPlayGame = { f ->
                        scope.launch { rightDrawerState.close() }
                        scope.launch {
                            val app = withContext(Dispatchers.IO) { SteamService.getAppInfoOf(f.gameAppId) }
                            if (app != null) {
                                launchSteamGame(context, ContainerManager(context), app, null)
                            }
                        }
                    },
                )
            }
        },
        scrimColor = Color.Black.copy(alpha = 0.5f),
        gesturesEnabled = rightDrawerState.isOpen,
    ) {
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr,
    ) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DrawerContent(
                persona = persona,
                isOpen = drawerState.isOpen,
                context = context,
                scope = scope,
                contentFilters = contentFilters,
                libraryLayoutMode = libraryLayoutMode,
                immersiveMode = immersiveMode,
                immersiveBlur = immersiveBlur,
                forceLandscape = forceLandscape,
                onLibraryLayoutSelected = {
                    libraryLayoutMode = it
                    PrefManager.libraryLayoutMode = it.name
                },
                onContentFiltersChanged = { key, value ->
                    contentFilters[key] = value
                    PrefManager.libraryContentFilters = contentFilters.entries.filter { it.value }.joinToString(",") { it.key }
                },
                onImmersiveModeChanged = {
                    immersiveMode = it
                    PrefManager.libraryImmersiveMode = it
                },
                onImmersiveBlurChanged = {
                    immersiveBlur = it
                    PrefManager.libraryImmersiveBlur = it
                },
                onForceLandscapeChanged = {
                    forceLandscape = it
                    com.winlator.cmod.shared.android.OrientationLock.forceLandscape = it
                },
                onExportAll = {
                    scope.launch {
                        val count =
                            withContext(Dispatchers.IO) {
                                com.winlator.cmod.feature.shortcuts.FrontendExporter.exportAll(context)
                            }
                        val dir = com.winlator.cmod.feature.shortcuts.FrontendExporter.resolveExportDir(context)
                        com.winlator.cmod.shared.ui.toast.WinToast.show(
                            context,
                            if (count > 0) {
                                context.getString(R.string.shortcuts_export_all_done, count, dir?.path ?: "")
                            } else {
                                context.getString(R.string.shortcuts_export_all_none)
                            },
                        )
                    }
                },
                onExitApp = {
                    AppTerminationHelper.exitApplication(this@UnifiedHub, "hub_drawer_exit")
                },
            )
        },
        scrimColor = Color.Black.copy(alpha = 0.5f),
        gesturesEnabled = drawerState.isOpen,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(BgDark)
                .windowInsetsPadding(horizontalNavigationInsets),
        ) {
            val currentTabKeyForImmersive = tabs.getOrNull(selectedIdx)?.key ?: "library"
            val immersiveActive = immersiveMode && currentTabKeyForImmersive == "library"
            DisposableEffect(immersiveActive) {
                applyImmersiveSystemBars(immersiveActive)
                onDispose { applyImmersiveSystemBars(false) }
            }
            if (immersiveMode && currentTabKeyForImmersive == "library") {
                val immersiveModel by immersiveBackgroundRef.collectAsStateWithLifecycle()
                val immersiveRequest =
                    remember(immersiveModel, immersiveBlur, context) {
                        val builder = ImageRequest.Builder(context).data(immersiveModel)
                        (immersiveModel as? java.io.File)?.takeIf { it.isFile }?.let { file ->
                            // Custom uploads can be overwritten in place.
                            val key = "library_immersive_bg:${file.absolutePath}:${file.lastModified()}"
                            builder.memoryCacheKey(if (immersiveBlur) "$key:blur" else key).diskCacheKey(key)
                        }
                        if (immersiveBlur) {
                            // Blur baked into the bitmap at decode (quarter-res + radius 2 ≈ 8px on screen), so drawing costs the same as a plain image.
                            val dm = context.resources.displayMetrics
                            builder
                                .size(dm.widthPixels / 4, dm.heightPixels / 4)
                                .scale(coil.size.Scale.FILL)
                                .transformations(BoxBlurTransformation(radius = 2))
                        }
                        builder.crossfade(400).build()
                    }
                AnimatedVisibility(
                    visible = immersiveModel != null,
                    enter = fadeIn(tween(400)),
                    exit = fadeOut(tween(400)),
                    modifier = Modifier.matchParentSize(),
                ) {
                    Box(Modifier.matchParentSize()) {
                        AsyncImage(
                            model = immersiveRequest,
                            contentDescription = null,
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.Crop,
                        )
                        Box(
                            Modifier
                                .matchParentSize()
                                .background(BgDark.copy(alpha = 0.5f)),
                        )
                    }
                }
            }
            val scaffoldContainer = if (immersiveMode && currentTabKeyForImmersive == "library") Color.Transparent else BgDark
            val openFileManager: () -> Unit = {
                val internalPath = android.os.Environment.getExternalStorageDirectory().absolutePath
                val managedRoots = driveRoots(includeInternal = true)
                val containerManager = com.winlator.cmod.runtime.container.ContainerManager(context)
                val containers =
                    containerManager.getContainers().map {
                        DirectoryPickerDialog.ManagedContainer(it.id, it.getName())
                    }
                DirectoryPickerDialog.showManager(
                    activity = this@UnifiedHub,
                    initialPath = internalPath,
                    managedRoots = managedRoots,
                    containers = containers,
                    onRunFile = { exePath, containerId ->
                        val container = containerManager.getContainerById(containerId)
                        if (container != null) {
                            val winePath =
                                com.winlator.cmod.runtime.wine.WineUtils
                                    .hostPathToMappedWinePath(container, exePath)
                            startActivity(
                                android.content.Intent(
                                    this@UnifiedHub,
                                    com.winlator.cmod.runtime.display.XServerDisplayActivity::class.java,
                                ).apply {
                                    putExtra("container_id", container.id)
                                    putExtra("boot_exe", winePath)
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                            )
                        }
                    },
                    onCreateShortcut = { exePath ->
                        val exeFile = java.io.File(exePath)
                        addCustomGame(
                            context,
                            exeFile.nameWithoutExtension,
                            exePath,
                            exeFile.parent ?: exePath,
                        )
                        localLibraryRefreshKey++
                    },
                )
            }
            val activeTabKey = tabs.getOrNull(selectedIdx)?.key ?: "library"
            val headerCollapsible = activeTabKey in StoreTabKeys
            val headerCollapseTriggerPx = with(LocalDensity.current) { HeaderCollapseTriggerDistance.toPx() }
            var headerHeightPx by remember { mutableIntStateOf(0) }
            var headerVisible by remember { mutableStateOf(true) }
            val headerOffsetPx by animateFloatAsState(
                targetValue =
                    if (!headerCollapsible || headerVisible || headerHeightPx == 0) {
                        0f
                    } else {
                        -headerHeightPx.toFloat()
                    },
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                label = "headerOffset",
            )
            LaunchedEffect(activeTabKey) { headerVisible = true }
            LaunchedEffect(headerVisible, headerCollapsible) {
                storeHeaderVisible.value = !headerCollapsible || headerVisible
            }
            val headerScrollConnection =
                remember(headerCollapsible, headerCollapseTriggerPx) {
                    object : NestedScrollConnection {
                        private var downDistance = 0f
                        private var upDistance = 0f

                        override fun onPreScroll(
                            available: Offset,
                            source: NestedScrollSource,
                        ): Offset {
                            if (!headerCollapsible || headerHeightPx == 0) return Offset.Zero
                            val delta = available.y
                            if (delta < 0f) {
                                upDistance = 0f
                                downDistance -= delta
                                if (downDistance > headerCollapseTriggerPx) headerVisible = false
                            } else if (delta > 0f) {
                                downDistance = 0f
                                upDistance += delta
                                if (upDistance > headerHeightPx * HeaderRevealFraction) headerVisible = true
                            }
                            return Offset.Zero
                        }

                        override fun onPostScroll(
                            consumed: Offset,
                            available: Offset,
                            source: NestedScrollSource,
                        ): Offset {
                            if (headerCollapsible && available.y > 0f) {
                                downDistance = 0f
                                upDistance = 0f
                                headerVisible = true
                            }
                            return Offset.Zero
                        }
                    }
                }

            Scaffold(
                modifier = Modifier.nestedScroll(headerScrollConnection),
                containerColor = scaffoldContainer,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    Box(
                        Modifier
                            .onSizeChanged { headerHeightPx = it.height }
                            .graphicsLayer { translationY = headerOffsetPx },
                    ) {
                    TopBar(
                        tabs = tabs, selectedIdx = selectedIdx, onSelect = { selectedIdx = it },
                        searchQuery = searchQueryTfv, onSearchQueryChange = { searchQueryTfv = it },
                        isSearchExpanded = isSearchExpanded, onSearchExpandedChange = { isSearchExpanded = it },
                        onFilterClicked = { scope.launch { drawerState.open() } },
                        onFriendsClicked = { scope.launch { rightDrawerState.open() } },
                        onQuickAccess = { showQuickAccess = true },
                        onMoreStores = openMoreStores,
                        libraryInstalledOnly = libraryInstalledOnly,
                        onLibraryViewChange = { libraryInstalledOnly = it; libraryFocusIndex.value = 0 },
                    )
                    }
                },
                bottomBar = {
                    if (activeTabKey == "home") Row(Modifier.fillMaxWidth().background(BgDark).navigationBarsPadding()
                        .padding(horizontal = 16.dp).heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = openLibrary, modifier = Modifier.heightIn(min = 48.dp).controllerFocusGlow()) {
                            Text(stringResource(R.string.vapor_view_library))
                        }
                        Spacer(Modifier.weight(1f))
                        if (isControllerConnected && !isCompactWidth()) {
                            Text(stringResource(R.string.vapor_home_controller_hint), color = TextSecondary,
                                style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.weight(1f))
                        }
                        TextButton(onClick = openSteamStore, modifier = Modifier.heightIn(min = 48.dp).controllerFocusGlow()) {
                            Text(stringResource(R.string.vapor_browse_store))
                        }
                    } else if (activeTabKey == "library") Row(Modifier.fillMaxWidth().background(BgDark).navigationBarsPadding()
                        .padding(horizontal = 16.dp).heightIn(min = 52.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { openFileManager() }) {
                            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.vapor_files))
                        }
                        Spacer(Modifier.weight(1f))
                        if (isControllerConnected && !isCompactWidth()) {
                            Text(stringResource(R.string.vapor_controller_hint), color = TextSecondary,
                                style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.weight(1f))
                        }
                        TextButton(onClick = { showAddCustomGame = true }) {
                            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.vapor_add_game))
                        }
                    } else if (isControllerConnected) {
                        Text(stringResource(if (activeTabKey == "steam") R.string.vapor_catalog_controller_hint
                            else R.string.vapor_navigation_hint), color = TextSecondary,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.fillMaxWidth().background(BgDark).navigationBarsPadding()
                                .padding(horizontal = 20.dp, vertical = 12.dp))
                    } else Spacer(Modifier.navigationBarsPadding())
                },
            ) { padding ->
                LaunchedEffect(selectedIdx, tabs) {
                    currentTabKey = tabs.getOrNull(selectedIdx)?.key ?: "library"
                    storeFocusIndex.value = 0
                    downloadsNavBridge.controllerActive = false
                }

                val key = tabs.getOrNull(selectedIdx)?.key ?: "library"
                val innerBoxBg = if (immersiveMode && key == "library") Color.Transparent else BgDark
                val headerLayoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
                val headerDensity = LocalDensity.current
                val headerMinTopPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                val contentTopPadding =
                    with(headerDensity) {
                        (padding.calculateTopPadding().toPx() + headerOffsetPx)
                            .coerceAtLeast(headerMinTopPadding.toPx())
                            .toDp()
                    }

                Box(
                    Modifier
                        .padding(
                            start = padding.calculateStartPadding(headerLayoutDirection),
                            top = contentTopPadding,
                            end = padding.calculateEndPadding(headerLayoutDirection),
                            bottom = padding.calculateBottomPadding(),
                        ).fillMaxSize()
                        .background(innerBoxBg),
                ) {

                    LaunchedEffect(key) { libraryTabActive.value = key in setOf("home", "library") }

                    AnimatedContent(
                        targetState = key,
                        transitionSpec = {
                            // Home and Library share game-selection signals. Replace that owner atomically,
                            // rather than keeping two collectors alive during a crossfade.
                            if (initialState in setOf("home", "library") && targetState in setOf("home", "library")) {
                                EnterTransition.None togetherWith ExitTransition.None
                            } else fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                        },
                        label = "tabContent",
                    ) { animatedKey ->
                        // Platform saved state keeps scroll position; off-screen effects stop after the fade.
                        tabState.SaveableStateProvider(animatedKey) {
                            when (animatedKey) {
                                "home", "library" -> LibraryCarousel(
                                    onClearSearch = { searchQueryTfv = TextFieldValue("") },
                                    home = animatedKey == "home",
                                    installedOnly = libraryInstalledOnly,
                                    onShowAllGames = { libraryInstalledOnly = false },
                                    isLoggedIn = isLoggedIn,
                                    steamApps = filteredSteamApps,
                                    epicApps = epicApps,
                                    gogApps = gogApps,
                                    storeListsReady = storeListsReady,
                                    contentFilters = contentFilters.toMap(),
                                    layoutMode = libraryLayoutMode,
                                    libraryRefreshKey = libraryRefreshKey,
                                    shortcutRefreshKey = shortcutRefreshKey,
                                    playtimeRefreshKey = playtimeRefreshKey,
                                    iconRefreshKey = iconRefreshKey,
                                    searchQuery = searchQuery,
                                    isControllerConnected = isControllerConnected,
                                    onAddGame = { showAddCustomGame = true },
                                    onBrowseStore = openSteamStore,
                                    onConnectSteam = { showAccountSetup = true },
                                )
                                "downloads" -> {
                                    DownloadsTab(
                                        animationsActive = key == "downloads",
                                        onBrowseStore = openSteamStore,
                                    )
                                }

                                "steam" -> {
                                    SteamStoreTab(steamApps,
                                        onBack = openLibrary)
                                }

                                "epic" -> {
                                    EpicStoreTab(isEpicLoggedIn, epicApps, searchQuery, LibraryLayoutMode.GRID_4) {
                                        epicLoginLauncher.launch(Intent(this@UnifiedHub, EpicOAuthActivity::class.java))
                                    }
                                }

                                "gog" -> {
                                    GOGStoreTab(isGogLoggedIn, gogApps, searchQuery, LibraryLayoutMode.GRID_4) {
                                        gogLoginLauncher.launch(Intent(this@UnifiedHub, GOGOAuthActivity::class.java))
                                    }
                                }

                                "itch" -> {
                                    ItchStoreTab(
                                        searchQuery = searchQuery,
                                        signInSignal = itchAuthRefreshKey,
                                        onSignInClick = {
                                            itchLoginLauncher.launch(Intent(this@UnifiedHub, ItchLoginActivity::class.java))
                                        },
                                        onSignedOut = { itchAuthRefreshKey++ },
                                    )
                                }

                                else -> {}
                            }
                        }
                    }

                    if (drawerState.isClosed) {
                        DrawerSwipeHotZone(
                            modifier = Modifier.align(Alignment.CenterStart),
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                        )
                    }
                    if (rightDrawerState.isClosed) {
                        DrawerSwipeHotZone(
                            modifier = Modifier.align(Alignment.CenterEnd),
                            isRightSide = true,
                            onOpenDrawer = { scope.launch { rightDrawerState.open() } },
                        )
                    }


                }
            }
        }
    } // end ModalNavigationDrawer
    } // end inner LTR
    } // end right friends ModalNavigationDrawer
    } // end RTL provider

    if (showQuickAccess) {
        VaporQuickAccess(
            activity = this@UnifiedHub,
            onDismiss = { showQuickAccess = false },
            onSettings = { showQuickAccess = false; navigateToSettings(SettingsNavItem.STORES) },
            onLibraryOptions = { showQuickAccess = false; openLibrary(); scope.launch { drawerState.open() } },
        )
    }

    if (globalSettingsApp != null) {
        GameSettingsDialog(
            app = globalSettingsApp!!,
            onDismissRequest = { globalSettingsApp = null },
        )
    }
    if (globalSettingsGogGame != null) {
        GOGGameSettingsDialog(
            app = globalSettingsGogGame!!,
            onDismissRequest = { globalSettingsGogGame = null },
        )
    }

    if (showAddCustomGame) {
        AddCustomGameDialog(
            onDismiss = {
                showAddCustomGame = false
                localLibraryRefreshKey++
            },
        )
    }

    chatFriend?.let { cf ->
        com.winlator.cmod.feature.stores.steam.friends.SteamChatScreen(
            friend = friends.firstOrNull { it.steamId == cf.steamId } ?: cf,
            onClose = { chatFriend = null },
        )
    }

    BackHandler(enabled = true) {
        if (chatFriend != null) {
            chatFriend = null
        } else if (rightDrawerState.isOpen) {
            scope.launch { rightDrawerState.close() }
        } else if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else if (globalSettingsApp != null) {
            globalSettingsApp = null
        } else if (globalSettingsGogGame != null) {
            globalSettingsGogGame = null
        } else if (showAddCustomGame) {
            showAddCustomGame = false
        } else if (isSearchExpanded) {
            isSearchExpanded = false
            searchQueryTfv = TextFieldValue("")
        } else if (tabs.getOrNull(selectedIdx)?.key in StoreTabKeys) {
            openMoreStores()
        } else if (tabs.getOrNull(selectedIdx)?.key != "home") {
            selectedIdx = 0
        } else {
            showExitDialog = true
        }
    }

    if (showExitDialog) {
        Dialog(
            onDismissRequest = { showExitDialog = false },
            properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
        ) {
            Box(
                modifier =
                    Modifier
                        .widthIn(max = 320.dp)
                        .fillMaxWidth(0.9f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(SurfaceDark)
                        .border(1.dp, Accent.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
                        .padding(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.common_ui_exit_app_confirm),
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(24.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        OutlinedButton(
                            onClick = { showExitDialog = false },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                            border = androidx.compose.foundation.BorderStroke(1.dp, TextSecondary.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.common_ui_cancel), fontWeight = FontWeight.Medium)
                        }
                        Button(
                            onClick = {
                                AppTerminationHelper.exitApplication(this@UnifiedHub, "hub_exit_menu")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.common_ui_exit), color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun UnifiedActivity.DrawerSwipeHotZone(
    modifier: Modifier = Modifier,
    isRightSide: Boolean = false,
    onOpenDrawer: () -> Unit,
) {
    val density = LocalDensity.current
    val openThresholdPx = with(density) { 36.dp.toPx() }

    Box(
        modifier =
            modifier
                .fillMaxHeight()
                // Stay inside the unused outer gutter; an invisible strip over
                // content steals taps even when no swipe is recognized.
                .width(8.dp)
                .pointerInput(openThresholdPx, isRightSide) {
                    var accumulatedDrag = 0f
                    var opened = false

                    detectHorizontalDragGestures(
                        onDragStart = {
                            accumulatedDrag = 0f
                            opened = false
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            val delta = if (isRightSide) -dragAmount else dragAmount
                            if (delta <= 0f || opened) return@detectHorizontalDragGestures

                            accumulatedDrag += delta
                            change.consume()

                            if (accumulatedDrag >= openThresholdPx) {
                                opened = true
                                onOpenDrawer()
                            }
                        },
                    )
                },
    )
}

@Composable
internal fun UnifiedActivity.GlassesSettingsSheet(onDismiss: () -> Unit) {
    val gm = com.winlator.cmod.runtime.display.GlassesManager
    val settings by gm.settings.collectAsStateWithLifecycle()
    val brightnessMax = gm.brightnessMax()
    val volumeMax = gm.volumeMax()
    val brightness = if (settings.brightness < 0) brightnessMax else settings.brightness
    val volume = if (settings.volume < 0) volumeMax else settings.volume
    val registry = remember { PaneNavRegistry() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        CompositionLocalProvider(LocalPaneNav provides registry) {
        DialogPaneNav(registry, onDismiss = onDismiss)
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(24.dp),
            color = SurfaceDark,
            modifier = Modifier.fillMaxWidth(0.82f),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Eyeglasses2Icon, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(gm.modelName(), color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                // Two 'weight(1f)' columns inside a 0.82f-wide dialog leave about 130 dp each at
                // phone width: the 60/90/120 Hz chips shrink to ~38 dp and the sliders become stubs.
                // Stack them in portrait instead.
                val glassesColumnA: @Composable (Modifier) -> Unit = { columnModifier ->
                    Column(modifier = columnModifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassesLabel(stringResource(R.string.glasses_panel_refresh))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(60, 90, 120).forEach { hz ->
                                    val selected = settings.refreshHz == hz
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(11.dp))
                                            .background(if (selected) Accent else TextSecondary.copy(alpha = 0.12f))
                                            .paneNavItem(cornerRadius = 11.dp, onActivate = { gm.setRefreshHz(hz) }, isEntry = hz == 60)
                                            .clickable { gm.setRefreshHz(hz) }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("$hz", color = if (selected) SurfaceDark else TextPrimary,
                                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            GlassesToggleTile(stringResource(R.string.glasses_panel_sunblock),
                                settings.sunblock, Modifier.weight(1f)) { gm.setSunblock(it) }
                            GlassesToggleTile(stringResource(R.string.session_drawer_output_3d),
                                settings.threeD, Modifier.weight(1f)) { gm.set3D(it) }
                        }
                    }
                }
                val glassesColumnB: @Composable (Modifier) -> Unit = { columnModifier ->
                    Column(modifier = columnModifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        GlassesPercentSlider(stringResource(R.string.session_drawer_output_brightness),
                            brightness, brightnessMax) { gm.setBrightness(it) }
                        GlassesPercentSlider(stringResource(R.string.session_drawer_output_volume),
                            volume, volumeMax) { gm.setVolume(it) }
                    }
                }
                if (isPortraitLayout()) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        glassesColumnA(Modifier.fillMaxWidth())
                        glassesColumnB(Modifier.fillMaxWidth())
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        glassesColumnA(Modifier.weight(1f))
                        glassesColumnB(Modifier.weight(1f))
                    }
                }
            }
        }
        }
    }
}

@Composable
internal fun UnifiedActivity.GlassesLabel(text: String) {
    Text(text, color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
}

@Composable
internal fun UnifiedActivity.GlassesPercentSlider(label: String, level: Int, max: Int, onChange: (Int) -> Unit) {
    val pct = if (max > 0) Math.round(level * 100f / max) else 0
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            GlassesLabel(label)
            Text("$pct%", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        androidx.compose.material3.Slider(
            value = level.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..max.toFloat(),
            steps = (max - 1).coerceAtLeast(0),
            modifier = Modifier.paneNavItem(
                cornerRadius = 8.dp,
                onAdjust = { dir -> onChange((level + dir).coerceIn(0, max)) },
            ),
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = TextSecondary.copy(alpha = 0.2f),
            ),
        )
    }
}

@Composable
internal fun UnifiedActivity.GlassesToggleTile(label: String, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .background(if (checked) Accent.copy(alpha = 0.16f) else TextSecondary.copy(alpha = 0.08f))
            .paneNavItem(cornerRadius = 13.dp, onActivate = { onChange(!checked) })
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Accent,
            ),
        )
    }
}

@Composable
internal fun UnifiedActivity.TopBar(
    tabs: List<TabDef>,
    selectedIdx: Int,
    onSelect: (Int) -> Unit,
    searchQuery: TextFieldValue,
    onSearchQueryChange: (TextFieldValue) -> Unit,
    isSearchExpanded: Boolean,
    onSearchExpandedChange: (Boolean) -> Unit,
    onFilterClicked: () -> Unit,
    onFriendsClicked: () -> Unit,
    onQuickAccess: () -> Unit,
    onMoreStores: () -> Unit,
    libraryInstalledOnly: Boolean,
    onLibraryViewChange: (Boolean) -> Unit,
) {
    val currentKey = tabs.getOrNull(selectedIdx)?.key
    val searchable = currentKey !in setOf("home", "downloads", "steam")
    val latestSearchable by rememberUpdatedState(searchable)
    val glassesConnected by com.winlator.cmod.runtime.display.GlassesManager.connected.collectAsStateWithLifecycle()
    var showGlassesPanel by remember { mutableStateOf(false) }
    val latestGlasses by rememberUpdatedState(glassesConnected)
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val closeSearch = {
        onSearchExpandedChange(false)
        onSearchQueryChange(TextFieldValue(""))
        keyboard?.hide()
        focus.clearFocus()
    }
    val toggleSearch by rememberUpdatedState {
        if (isSearchExpanded) closeSearch() else onSearchExpandedChange(true)
    }
    LaunchedEffect(selectedIdx) {
        onSearchExpandedChange(false)
        onSearchQueryChange(TextFieldValue(""))
    }
    LaunchedEffect(Unit) {
        openSearchSignal.collect { if (latestSearchable) toggleSearch() }
    }
    LaunchedEffect(Unit) { openGlassesSignal.collect { if (latestGlasses) showGlassesPanel = true } }

    VaporHeader(tabs, selectedIdx, onSelect, searchQuery, onSearchQueryChange,
        isSearchExpanded, onToggleSearch = toggleSearch, onFilter = onFilterClicked, onFriends = onFriendsClicked,
        onSettings = { navigateToSettings(SettingsNavItem.STORES) },
        onQuickAccess = onQuickAccess, onMoreStores = onMoreStores,
        libraryInstalledOnly = libraryInstalledOnly, onLibraryViewChange = onLibraryViewChange)
    if (showGlassesPanel) GlassesSettingsSheet { showGlassesPanel = false }
}

private data class ShortcutScanResult(
    val shortcuts: List<Shortcut>,
    val customApps: List<SteamApp>,
    val badges: Map<Int, String>,
    val storagePaths: Map<Int, String>,
)

@Composable
internal fun UnifiedActivity.LibraryCarousel(
    home: Boolean,
    installedOnly: Boolean,
    onShowAllGames: () -> Unit,
    isLoggedIn: Boolean,
    steamApps: List<SteamApp>,
    epicApps: List<EpicGame>,
    gogApps: List<GOGGame>,
    storeListsReady: Boolean,
    contentFilters: Map<String, Boolean>,
    layoutMode: LibraryLayoutMode,
    libraryRefreshKey: Int = 0,
    shortcutRefreshKey: Int = 0,
    playtimeRefreshKey: Int = 0,
    iconRefreshKey: Int = 0,
    searchQuery: String = "",
    isControllerConnected: Boolean = false,
    onAddGame: () -> Unit,
    onBrowseStore: () -> Unit,
    onConnectSteam: () -> Unit,
    onClearSearch: () -> Unit,
) {
    val context = LocalContext.current
    val libraryScope = rememberCoroutineScope()

    // Where this screen left off; see LibraryCache.session.
    val resumed = remember { LibraryCache.session }
    var cachedShortcuts by remember { mutableStateOf(resumed?.shortcuts ?: emptyList()) }
    var customApps by remember { mutableStateOf(resumed?.customApps ?: emptyList()) }
    var customStoragePathByAppId by remember { mutableStateOf(resumed?.customStoragePaths ?: emptyMap()) }
    // A scan that ran before the shortcuts or a store's rows were read would publish a library with
    // those games missing, and the grid would drop them only to bring them back a moment later.
    var shortcutsReady by remember { mutableStateOf(resumed != null) }
    val externalStorageState by com.winlator.cmod.feature.storage.ExternalStorage.state.collectAsStateWithLifecycle()
    var localLibraryRefreshKey by remember { mutableIntStateOf(0) }
    var shortcutsLoaded by remember { mutableStateOf(false) }
    var pullRefreshing by remember { mutableStateOf(false) }
    LaunchedEffect(shortcutRefreshKey, localLibraryRefreshKey, com.winlator.cmod.feature.retro.RetroBoxart.artVersion.value) {
        shortcutsLoaded = false

        // Pull-to-refresh only: rescan disk so a manually moved game is picked up without faking a re-download.
        // Skipped on the initial pass because the scan walks every known app.
        if (pullRefreshing) {
            runCatching {
                withContext(Dispatchers.IO) { SteamService.repairInstalledMetadataFromDisk() }
            }.onFailure { Log.w("UnifiedActivity", "Pull-to-refresh install repair failed", it) }
        }

        val shortcutScanResult =
            runCatching {
                withContext(Dispatchers.IO) {
                    runCatching { com.winlator.cmod.feature.retro.RetroRomScanner.scanConfiguredFolder(context) }
                    val cm = ContainerManager(context)
                    cm.upgradeShortcuts {
                        localLibraryRefreshKey++
                    }
                    val allShortcuts = cm.loadShortcuts()
                    val badges = HashMap<Int, String>()
                    val storagePaths = HashMap<Int, String>()
                    val apps =
                        allShortcuts
                            .mapNotNull { shortcut ->
                                if (!LibraryShortcutUtils.isCustomLibraryShortcut(shortcut)) {
                                    return@mapNotNull null
                                }

                                val displayName =
                                    shortcut
                                        .getExtra("custom_name", shortcut.name)
                                        .ifBlank { shortcut.name }

                                val customId = LibraryShortcutUtils.customLibraryId(shortcut)

                                com.winlator.cmod.feature.retro.RetroSystems
                                    .fromId(
                                        shortcut.getExtra(
                                            com.winlator.cmod.feature.retro.RetroShortcuts.KEY_SYSTEM,
                                        ),
                                    )?.let { badges[customId] = it.id }

                                val gameDir =
                                    shortcut.getExtra(
                                        "game_install_path",
                                        shortcut.getExtra("custom_game_folder", ""),
                                    )
                                val storagePath =
                                    sequenceOf(
                                        gameDir,
                                        shortcut.getExtra("custom_exe", ""),
                                        shortcut.getExtra(
                                            com.winlator.cmod.feature.retro.RetroShortcuts.KEY_ROM,
                                            "",
                                        ),
                                    ).firstOrNull { it.isNotBlank() }
                                if (storagePath != null) storagePaths[customId] = storagePath

                                SteamApp(
                                    id = customId,
                                    name = displayName,
                                    type = LibraryItemType.of(shortcut).appType,
                                    developer = "Custom",
                                    gameDir = gameDir,
                                )
                            }

                    ShortcutScanResult(allShortcuts, apps, badges, storagePaths)
                }
            }.getOrNull()

        if (shortcutScanResult != null) {
            cachedShortcuts = shortcutScanResult.shortcuts
            customApps = shortcutScanResult.customApps
            customStoragePathByAppId = shortcutScanResult.storagePaths
            retroLibrarySystemIds.value = shortcutScanResult.badges
        }

        shortcutsLoaded = true
        shortcutsReady = true
    }

    // Move library filtering and file checks off the main thread.
    var mergedInstalledApps by remember { mutableStateOf(resumed?.merged ?: emptyList()) }
    var installedApps by remember { mutableStateOf(resumed?.apps ?: emptyList()) }
    var recentlyOpenedIds by remember { mutableStateOf(emptySet<Int>()) }
    // What the library drew last time, shown until this launch's own scan has an answer.
    val savedPicture = remember { resumed?.apps ?: LibraryCache.preloaded }
    var stableInstalledApps by remember { mutableStateOf(savedPicture ?: emptyList()) }
    var libraryCacheLoaded by remember { mutableStateOf(savedPicture != null) }
    LaunchedEffect(Unit) {
        if (libraryCacheLoaded) return@LaunchedEffect
        val cached = withContext(Dispatchers.IO) { LibraryCache.load(context) }
        if (stableInstalledApps.isEmpty()) stableInstalledApps = cached
        libraryCacheLoaded = true
    }
    var gogByPseudoId by remember { mutableStateOf(resumed?.gogByPseudoId ?: emptyMap()) }
    var libraryStoreLinks by remember { mutableStateOf(LibraryStoreLinkResult()) }
    var epicByPseudoId by remember { mutableStateOf(resumed?.epicByPseudoId ?: emptyMap()) }
    var stableGogByPseudoId by remember { mutableStateOf(resumed?.gogByPseudoId ?: emptyMap()) }
    var stableEpicByPseudoId by remember { mutableStateOf(resumed?.epicByPseudoId ?: emptyMap()) }
    val resumedArtwork = resumed?.artwork ?: LibraryCache.Artwork()
    var customListArtworkPathByAppId by remember { mutableStateOf(resumedArtwork.list) }
    var customHeroArtworkPathByAppId by remember { mutableStateOf(resumedArtwork.hero) }
    var customCarouselArtworkPathByAppId by remember { mutableStateOf(resumedArtwork.carousel) }
    var customArtworkPathByAppId by remember { mutableStateOf(resumedArtwork.cover) }
    var customIconArtworkPathByAppId by remember { mutableStateOf(resumedArtwork.iconArtwork) }
    var customIconPathByAppId by remember { mutableStateOf(resumedArtwork.icon) }
    var stableCustomArtworkPathByAppId by remember { mutableStateOf(resumedArtwork.cover) }
    var stableCustomIconArtworkPathByAppId by remember { mutableStateOf(resumedArtwork.iconArtwork) }
    var stableCustomIconPathByAppId by remember { mutableStateOf(resumedArtwork.icon) }
    var stableCustomHeroPathByAppId by remember { mutableStateOf(resumedArtwork.hero) }
    var stableCustomCarouselPathByAppId by remember { mutableStateOf(resumedArtwork.carousel) }
    var stableCustomListPathByAppId by remember { mutableStateOf(resumedArtwork.list) }
    var artworkCacheRefreshKey by remember { mutableIntStateOf(0) }
    var libraryLoaded by remember { mutableStateOf(resumed != null) }
    // Suppress transient empty states before background recomputation starts.
    val scanInputToken =
        remember(
            steamApps,
            epicApps,
            gogApps,
            customApps,
            contentFilters,
            customStoragePathByAppId,
            externalStorageState.connectivityKey,
            libraryRefreshKey,
            localLibraryRefreshKey,
            storeListsReady,
            shortcutsReady,
        ) { Any() }
    var processedScanToken by remember { mutableStateOf<Any?>(null) }

    LaunchedEffect(scanInputToken) {
        if (!storeListsReady || !shortcutsReady) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val steamInstalled = steamApps.filter { SteamService.isAppInstalled(it.id) }

            val epicInstalled = epicApps.filter { it.isInstalled }

            // Match Epic's DB-backed install filter during verify/update.
            val gogInstalled = gogApps.filter { it.isInstalled }

            val gogMap = gogInstalled.associateBy { gogPseudoId(it.id) }
            val epicMap = epicInstalled.associateBy { 2000000000 + it.id }

            val playtimePrefs = context.getSharedPreferences("playtime_stats", android.content.Context.MODE_PRIVATE)
            val allPlaytime = playtimePrefs.all
            val mappedEpic =
                epicInstalled.map { epic ->
                    SteamApp(
                        id = 2000000000 + epic.id,
                        name = epic.title,
                        developer = epic.developer,
                        gameDir = epic.installPath,
                    )
                }
            val mappedGog =
                gogInstalled.map { gog ->
                    SteamApp(
                        id = gogPseudoId(gog.id),
                        name = gog.title,
                        developer = gog.developer,
                        gameDir = gog.installPath,
                    )
                }
            val installedRows =
                steamInstalled.map { steam ->
                    LibraryStoreOption(
                        store = InstallStore.STEAM,
                        libraryId = steam.id,
                        storeGameId = steam.id.toString(),
                        title = steam.name,
                        installPath = runCatching { SteamService.getAppDirPath(steam.id) }.getOrDefault(""),
                        isInstalled = true,
                    )
                } +
                    epicInstalled.map { epic ->
                        LibraryStoreOption(
                            store = InstallStore.EPIC,
                            libraryId = 2000000000 + epic.id,
                            storeGameId = epic.id.toString(),
                            title = epic.title,
                            installPath = epic.installPath,
                            isInstalled = true,
                        )
                    } +
                    gogInstalled.map { gog ->
                        LibraryStoreOption(
                            store = InstallStore.GOG,
                            libraryId = gogPseudoId(gog.id),
                            storeGameId = gog.id,
                            title = gog.title,
                            installPath = gog.installPath,
                            isInstalled = true,
                        )
                    }
            val customRows =
                customApps.map { custom ->
                    LibraryStoreOption(
                        store = InstallStore.ITCH,
                        libraryId = custom.id,
                        storeGameId = custom.id.toString(),
                        title = custom.name,
                        installPath = custom.gameDir.orEmpty(),
                        isInstalled = true,
                    )
                }
            val steamInstalledIds = steamInstalled.map { it.id }.toSet()
            val ownedEntries =
                steamApps.map { steam ->
                    LibraryStoreOption(
                        store = InstallStore.STEAM,
                        libraryId = steam.id,
                        storeGameId = steam.id.toString(),
                        title = steam.name,
                        installPath = "",
                        isInstalled = steam.id in steamInstalledIds,
                    )
                } +
                    epicApps.map { epic ->
                        LibraryStoreOption(
                            store = InstallStore.EPIC,
                            libraryId = 2000000000 + epic.id,
                            storeGameId = epic.id.toString(),
                            title = epic.title,
                            installPath = epic.installPath,
                            isInstalled = epic.isInstalled,
                        )
                    } +
                    gogApps.map { gog ->
                        LibraryStoreOption(
                            store = InstallStore.GOG,
                            libraryId = gogPseudoId(gog.id),
                            storeGameId = gog.id,
                            title = gog.title,
                            installPath = gog.installPath,
                            isInstalled = gog.isInstalled,
                        )
                    }
            val itchInstalls =
                runCatching {
                    ItchLibrary.all(context).map { itch ->
                        LibraryStoreOption(
                            store = InstallStore.ITCH,
                            libraryId = 0,
                            storeGameId = itch.id.toString(),
                            title = itch.title,
                            installPath = itch.installPath,
                            isInstalled = true,
                        )
                    }
                }.getOrDefault(emptyList())

            val shortcutSourceByPath =
                cachedShortcuts
                    .mapNotNull { shortcut ->
                        val dir = shortcut.getExtra("game_install_path").orEmpty()
                        if (dir.isBlank()) return@mapNotNull null
                        val store =
                            InstallStore.fromId(LibraryShortcutUtils.inferGameSource(shortcut))
                                ?: return@mapNotNull null
                        LibraryStoreLinks.pathKey(dir) to store
                    }.toMap()

            val storeLinks =
                computeLibraryStoreLinks(
                    installedRows = installedRows,
                    customRows = customRows,
                    ownedEntries = ownedEntries,
                    itchInstalls = itchInstalls,
                    shortcutSourceByPath = shortcutSourceByPath,
                    preferredStoreOf = { key -> LibraryStoreLinks.preferredStore(context, key) },
                )

            val externalSnapshot = externalStorageState
            val customStoragePaths = customStoragePathByAppId
            val storeInstallPaths =
                installedRows.associate { row -> row.libraryId to row.installPath }
            val merged =
                (
                    steamInstalled +
                        customApps.filter { LibraryContentFilters.allows(it.type, contentFilters) } +
                        mappedEpic + mappedGog
                )
                    .distinctBy { it.id }
                    .filterNot { it.id in storeLinks.hiddenLibraryIds }
                    .filterNot { app ->
                        val path =
                            customStoragePaths[app.id]
                                ?: storeInstallPaths[app.id]?.takeIf { it.isNotBlank() }
                                ?: app.gameDir.orEmpty()
                        externalSnapshot.isOnDisconnectedDrive(path)
                    }
            val sorted =
                merged.sortedByDescending { app ->
                    com.winlator.cmod.feature.library.libraryLastOpenedAt(app, allPlaytime)
                }

            // Every store's rows and the shortcuts were read before this ran, so what it found is
            // the library, empty or not. None of it needs the network.
            LibraryCache.save(context, sorted)

            withContext(Dispatchers.Main) {
                gogByPseudoId = gogMap
                epicByPseudoId = epicMap
                mergedInstalledApps = merged
                recentlyOpenedIds = sorted.filter { com.winlator.cmod.feature.library.libraryLastOpenedAt(it, allPlaytime) > 0L }.map { it.id }.toSet()
                installedApps = sorted
                libraryStoreLinks = storeLinks
                if (sorted.isNotEmpty()) {
                    stableInstalledApps = sorted
                    stableGogByPseudoId = gogMap
                    stableEpicByPseudoId = epicMap
                }
                libraryLoaded = true
                processedScanToken = scanInputToken
                pullRefreshing = false
            }
        }
    }

    LaunchedEffect(installedApps, gogByPseudoId, cachedShortcuts, iconRefreshKey) {
        val appsSnapshot = installedApps
        val gogSnapshot = gogByPseudoId
        val shortcutsSnapshot = cachedShortcuts

        val artworkPaths =
            withContext(Dispatchers.IO) {
                buildMap<Int, String> {
                    appsSnapshot.forEach { app ->
                        val gogGame = gogSnapshot[app.id]
                        val isCustom = app.id < 0
                        val isEpic = app.id >= 2000000000
                        val epicId = if (isEpic) app.id - 2000000000 else 0
                        val shortcut =
                            if (gogGame != null) {
                                shortcutsSnapshot.find {
                                    it.getExtra("game_source") == "GOG" && it.getExtra("gog_id") == gogGame.id
                                }
                            } else {
                                findShortcutForGame(shortcutsSnapshot, app, isCustom, isEpic, epicId)
                            }
                        val customPath =
                            shortcut
                                ?.getExtra("customLibraryIconPath")
                                ?.ifBlank { shortcut.getExtra("customCoverArtPath") }
                        if (!customPath.isNullOrBlank() && java.io.File(customPath).exists()) {
                            put(app.id, customPath)
                        }
                    }
                }
            }

        val iconArtworkPaths =
            withContext(Dispatchers.IO) {
                buildMap<Int, String> {
                    appsSnapshot.forEach { app ->
                        val gogGame = gogSnapshot[app.id]
                        val isCustom = app.id < 0
                        val isEpic = app.id >= 2000000000
                        val epicId = if (isEpic) app.id - 2000000000 else 0
                        val shortcut =
                            if (gogGame != null) {
                                shortcutsSnapshot.find {
                                    it.getExtra("game_source") == "GOG" && it.getExtra("gog_id") == gogGame.id
                                }
                            } else {
                                findShortcutForGame(shortcutsSnapshot, app, isCustom, isEpic, epicId)
                            }
                        val customPath = shortcut?.let(LibraryShortcutArtwork::findIconArtworkPath)
                        if (customPath != null) {
                            put(app.id, customPath)
                        }
                    }
                }
            }

        val customHeroPath =
            withContext(Dispatchers.IO) {
                buildMap<Int, String> {
                    appsSnapshot.forEach { app ->
                        if (app.id >= 0) return@forEach
                        val shortcut = findShortcutForGame(shortcutsSnapshot, app, true, false, 0) ?: return@forEach
                        val heroPath = shortcut.getExtra("customLibraryHeroArtPath")
                        if (heroPath.isNullOrBlank() || !java.io.File(heroPath).isFile)
                            return@forEach
                        put(app.id, heroPath)
                    }
                }
            }

        val customCarouselPath =
            withContext(Dispatchers.IO) {
                buildMap<Int, String> {
                    appsSnapshot.forEach { app ->
                        if (app.id >= 0) return@forEach
                        val shortcut = findShortcutForGame(shortcutsSnapshot, app, true, false, 0) ?: return@forEach
                        val carouselPath = shortcut.getExtra("customLibraryCarouselArtPath")
                        if (carouselPath.isNullOrBlank() || !java.io.File(carouselPath).isFile)
                            return@forEach
                        put(app.id, carouselPath)
                    }
                }
            }

        val customListPath =
            withContext(Dispatchers.IO) {
                buildMap<Int, String> {
                    appsSnapshot.forEach { app ->
                        if (app.id >= 0) return@forEach
                        val shortcut = findShortcutForGame(shortcutsSnapshot, app, true, false, 0) ?: return@forEach
                        val listPath = shortcut.getExtra("customLibraryListArtPath")
                        if (listPath.isNullOrBlank() || !java.io.File(listPath).isFile)
                            return@forEach
                        put(app.id, listPath)
                    }
                }
            }

        val customIconPaths =
            withContext(Dispatchers.IO) {
                buildMap<Int, String> {
                    appsSnapshot.forEach { app ->
                        if (app.id >= 0) return@forEach
                        val safeName = app.name.replace("/", "_").replace("\\", "_")
                        val iconFile = java.io.File(context.filesDir, "custom_icons/$safeName.png")
                        if (iconFile.exists()) {
                            put(app.id, iconFile.absolutePath)
                        }
                    }
                }
            }

        customArtworkPathByAppId = artworkPaths
        customIconArtworkPathByAppId = iconArtworkPaths
        customIconPathByAppId = customIconPaths
        customHeroArtworkPathByAppId = customHeroPath
        customCarouselArtworkPathByAppId = customCarouselPath
        customListArtworkPathByAppId = customListPath
        if (appsSnapshot.isNotEmpty()) {
            stableCustomArtworkPathByAppId = artworkPaths
            stableCustomIconArtworkPathByAppId = iconArtworkPaths
            stableCustomIconPathByAppId = customIconPaths
            stableCustomHeroPathByAppId = customHeroPath
            stableCustomCarouselPathByAppId = customCarouselPath
            stableCustomListPathByAppId = customListPath
        }
    }

    LaunchedEffect(
        libraryLoaded,
        installedApps,
        mergedInstalledApps,
        gogByPseudoId,
        epicByPseudoId,
        cachedShortcuts,
        customApps,
        customStoragePathByAppId,
        customArtworkPathByAppId,
        customIconArtworkPathByAppId,
        customIconPathByAppId,
        customHeroArtworkPathByAppId,
        customCarouselArtworkPathByAppId,
        customListArtworkPathByAppId,
    ) {
        if (!libraryLoaded) return@LaunchedEffect
        LibraryCache.session =
            LibraryCache.Session(
                apps = installedApps,
                merged = mergedInstalledApps,
                gogByPseudoId = gogByPseudoId,
                epicByPseudoId = epicByPseudoId,
                shortcuts = cachedShortcuts,
                customApps = customApps,
                customStoragePaths = customStoragePathByAppId,
                artwork =
                    LibraryCache.Artwork(
                        cover = customArtworkPathByAppId,
                        iconArtwork = customIconArtworkPathByAppId,
                        icon = customIconPathByAppId,
                        hero = customHeroArtworkPathByAppId,
                        carousel = customCarouselArtworkPathByAppId,
                        list = customListArtworkPathByAppId,
                    ),
            )
    }

    LaunchedEffect(mergedInstalledApps, playtimeRefreshKey) {
        if (mergedInstalledApps.isEmpty()) {
            recentlyOpenedIds = emptySet()
            installedApps = emptyList()
            return@LaunchedEffect
        }

        val sorted =
            withContext(Dispatchers.IO) {
                val playtimePrefs = context.getSharedPreferences("playtime_stats", android.content.Context.MODE_PRIVATE)
                val allPlaytime = playtimePrefs.all
                val ordered = mergedInstalledApps.sortedByDescending { app ->
                    com.winlator.cmod.feature.library.libraryLastOpenedAt(app, allPlaytime)
                }
                ordered to ordered.filter { com.winlator.cmod.feature.library.libraryLastOpenedAt(it, allPlaytime) > 0L }.map { it.id }.toSet()
            }

        installedApps = sorted.first
        recentlyOpenedIds = sorted.second
    }

    val awaitingShortcutScan = installedApps.isEmpty() && !shortcutsLoaded
    val keepPreviousLibraryVisible =
        installedApps.isEmpty() &&
            stableInstalledApps.isNotEmpty() &&
            (processedScanToken !== scanInputToken || awaitingShortcutScan)
    // The saved picture holds a card's id and name and nothing of its artwork. The stores' rows are
    // read long before the scan has checked every install, so the cards are drawn from those.
    val savedPictureApps =
        remember(stableInstalledApps, steamApps) {
            val owned = steamApps.associateBy { it.id }
            stableInstalledApps.map { owned[it.id] ?: it }
        }
    val savedPictureGog =
        remember(stableGogByPseudoId, gogApps) {
            stableGogByPseudoId.ifEmpty { gogApps.filter { it.isInstalled }.associateBy { gogPseudoId(it.id) } }
        }
    val savedPictureEpic =
        remember(stableEpicByPseudoId, epicApps) {
            stableEpicByPseudoId.ifEmpty { epicApps.filter { it.isInstalled }.associateBy { 2000000000 + it.id } }
        }
    val visibleInstalledApps = if (keepPreviousLibraryVisible) savedPictureApps else installedApps
    val visibleGogByPseudoId = if (keepPreviousLibraryVisible) savedPictureGog else gogByPseudoId
    val visibleEpicByPseudoId = if (keepPreviousLibraryVisible) savedPictureEpic else epicByPseudoId
    val visibleCustomArtworkPathByAppId =
        if (keepPreviousLibraryVisible) stableCustomArtworkPathByAppId else customArtworkPathByAppId
    val visibleCustomIconArtworkPathByAppId =
        if (keepPreviousLibraryVisible) stableCustomIconArtworkPathByAppId else customIconArtworkPathByAppId
    val visibleCustomIconPathByAppId =
        if (keepPreviousLibraryVisible) stableCustomIconPathByAppId else customIconPathByAppId
    val visibleCustomListPathByAppId =
        if (keepPreviousLibraryVisible) stableCustomListPathByAppId else customListArtworkPathByAppId
    val visibleCustomHeroPathByAppId =
        if (keepPreviousLibraryVisible) stableCustomHeroPathByAppId else customHeroArtworkPathByAppId
    val visibleCustomCarouselPathByAppId =
        if (keepPreviousLibraryVisible) stableCustomCarouselPathByAppId else customCarouselArtworkPathByAppId

    val ownedGogById = remember(gogApps) { gogApps.associateBy { gogPseudoId(it.id) } }
    val ownedEpicById = remember(epicApps) { epicApps.associateBy { 2000000000 + it.id } }
    // Steam owns the main library catalog. Other stores are browsed from Settings;
    // their installed games still enter through the existing installed-game scan.
    val ownedApps = steamApps
    val displayedApps = remember(visibleInstalledApps, ownedApps, home, recentlyOpenedIds, installedOnly, searchQuery, libraryStoreLinks.hiddenLibraryIds) {
        val games = com.winlator.cmod.feature.library.libraryGamesForView(
            visibleInstalledApps, ownedApps, !home && installedOnly, if (home) "" else searchQuery, libraryStoreLinks.hiddenLibraryIds,
        )
        if (home) com.winlator.cmod.feature.library.libraryGamesForHome(games, recentlyOpenedIds) else games
    }
    val visibleInstalledIds = remember(visibleInstalledApps) { visibleInstalledApps.map { it.id }.toSet() }
    val gameGogById = ownedGogById + visibleGogByPseudoId
    val gameEpicById = ownedEpicById + visibleEpicByPseudoId

    // Empty results must clear controller selection, including when the user changes filters.
    LaunchedEffect(displayedApps) {
        libraryItemCount = displayedApps.size
        libraryFocusIndex.value = libraryFocusIndex.value.coerceIn(0, (displayedApps.size - 1).coerceAtLeast(0))
        if (displayedApps.isEmpty()) {
            selectedSteamAppId = 0
            selectedSteamAppName = ""
            selectedGogGameId = ""
            selectedLibrarySource = ""
            immersiveBackgroundRef.value = null
        }
    }

    val artworkLifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(
        artworkLifecycle,
        visibleInstalledApps,
        visibleGogByPseudoId,
        visibleEpicByPseudoId,
        visibleCustomArtworkPathByAppId,
        visibleCustomIconArtworkPathByAppId,
        cachedShortcuts,
    ) {
        // This work belongs to the visible Library, not the Steam/download service. Resuming
        // reuses completed disk files; STOPPED or leaving the screen cancels active HTTP calls.
        artworkLifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // A previous prefetch may have published files just before the screen was hidden.
            artworkCacheRefreshKey++
            var deletedCustomOverrides = false
            val refs =
                visibleInstalledApps.flatMap { app ->
                    val gogGame = visibleGogByPseudoId[app.id]
                    val epicGame = visibleEpicByPseudoId[app.id]
                    val overriddenSlots =
                        customArtworkOverrideSlots(
                            app = app,
                            gogGame = gogGame,
                            epicGame = epicGame,
                            hasDefaultCustomArt = visibleCustomArtworkPathByAppId[app.id] != null,
                            hasIconCustomArt = visibleCustomIconArtworkPathByAppId[app.id] != null,
                            hasHeroCustomArt =
                                findLibraryArtworkShortcut(cachedShortcuts, app, gogGame, epicGame)
                                    ?.hasExistingArtwork(LibraryShortcutArtwork.LibraryArtworkSlot.GAME_CARD.extraKey) == true,
                        )

                    if (overriddenSlots.isNotEmpty()) {
                        val cacheId = artworkCacheId(app, gogGame, epicGame)
                        if (cacheId != null) {
                            val deleted =
                                withContext(Dispatchers.IO) {
                                    StoreArtworkCache.deleteSlots(context, cacheId.store, cacheId.gameId, overriddenSlots)
                                }
                            deletedCustomOverrides = deletedCustomOverrides || deleted
                        }
                    }

                    StoreArtworkCache
                        .libraryRefs(
                            app = app,
                            gogGame = gogGame,
                            epicGame = epicGame,
                        ).filterNot { it.slot in overriddenSlots }
                }
            val cachedAny =
                withContext(Dispatchers.IO) {
                    StoreArtworkCache.cacheAll(context, refs)
                }
            if (cachedAny || deletedCustomOverrides) artworkCacheRefreshKey++
        }
    }

    // Room and the disk scan are authoritative, including an empty answer. Empty accounts
    // and offline refreshes must not hold the Library behind an endless spinner.
    val showLoading = !libraryLoaded && displayedApps.isEmpty()
    if (showLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val spinAlpha by animateFloatAsState(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 600),
                label = "loaderFade",
            )
            CircularProgressIndicator(
                color = Accent,
                strokeWidth = 3.dp,
                modifier = Modifier.size(48.dp).alpha(spinAlpha),
            )
        }
        return
    }

    if (displayedApps.isEmpty()) {
        if (!home && searchQuery.isNotBlank()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Outlined.SearchOff, null, tint = TextSecondary, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.vapor_no_results), color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onClearSearch, modifier = Modifier.heightIn(min = 48.dp).controllerFocusGlow()) {
                    Text(stringResource(R.string.workshop_clear_search))
                }
            }
        } else {
            VaporWelcome(
                home = home,
                signedIn = isLoggedIn,
                installedOnly = !home && installedOnly,
                onShowAllGames = onShowAllGames,
                onRefresh = {
                    libraryScope.launch(Dispatchers.IO) {
                        SteamService.instance?.processLicenseList()
                        SteamService.wnLibrary?.refresh()
                    }
                    localLibraryRefreshKey++
                },
                onSteam = { if (isLoggedIn) onBrowseStore()
                    else onConnectSteam() },
                onAddGame = onAddGame,
            )
        }
        return
    }

    var selectedAppForSettings by remember { mutableStateOf<SteamApp?>(null) }
    var selectedGogGameForSettings by remember { mutableStateOf<GOGGame?>(null) }
    var detailApp by remember { mutableStateOf<SteamApp?>(null) }
    var installApp by remember { mutableStateOf<SteamApp?>(null) }
    var detailGogGame by remember { mutableStateOf<GOGGame?>(null) }
    var detailStoreOptions by remember { mutableStateOf<List<LibraryStoreOption>>(emptyList()) }
    var detailActiveStore by remember { mutableStateOf<InstallStore?>(null) }
    var detailInstallPath by remember { mutableStateOf("") }

    LaunchedEffect(detailApp?.id, libraryStoreLinks) {
        val id = detailApp?.id
        if (id == null) {
            detailStoreOptions = emptyList()
            detailActiveStore = null
            detailInstallPath = ""
            return@LaunchedEffect
        }
        if (!libraryStoreLinks.installPathByLibraryId.containsKey(id)) return@LaunchedEffect
        detailStoreOptions = libraryStoreLinks.optionsByLibraryId[id].orEmpty()
        detailActiveStore = libraryStoreLinks.activeStoreByLibraryId[id]
        detailInstallPath = libraryStoreLinks.installPathByLibraryId[id].orEmpty()
    }
    val gridState = rememberLazyGridState()
    val activity = LocalContext.current as? UnifiedActivity

    // Other inherited surfaces still observe the shared modal-animation gate.
    LaunchedEffect(selectedAppForSettings, selectedGogGameForSettings, detailApp, installApp) {
        chasingBordersPaused.value =
            selectedAppForSettings != null || selectedGogGameForSettings != null || detailApp != null || installApp != null
    }
    DisposableEffect(Unit) {
        onDispose { chasingBordersPaused.value = false }
    }

    LaunchedEffect(home, layoutMode) {
        currentLibraryLayoutMode = if (home) LibraryLayoutMode.CAROUSEL else layoutMode
    }

    // FocusRequesters for each grid item
    val focusRequesters =
        remember(displayedApps.size) {
            List(displayedApps.size) { FocusRequester() }
        }

    // Observe focus index changes from the activity and request focus on the target item
    val focusIndex by (activity?.libraryFocusIndex ?: kotlinx.coroutines.flow.MutableStateFlow(0)).collectAsStateWithLifecycle()
    LaunchedEffect(focusIndex, focusRequesters.size, layoutMode) {
        if (!home && searchQuery.isEmpty() &&
            layoutMode == LibraryLayoutMode.GRID_4 &&
            focusRequesters.isNotEmpty() &&
            focusIndex in focusRequesters.indices
        ) {
            gridState.animateScrollToItem(focusIndex)
            try {
                focusRequesters[focusIndex].requestFocus()
            } catch (_: Exception) {
            }
        }
    }

    // Track selected app for the top-right Game Settings button
    LaunchedEffect(focusIndex, displayedApps) {
        val app = displayedApps.getOrNull(focusIndex) ?: displayedApps.firstOrNull()
        selectedSteamAppId = app?.id ?: 0
        selectedSteamAppName = app?.name ?: ""
        val gogGame = app?.let { gameGogById[it.id] }
        selectedLibrarySource =
            when {
                gogGame != null -> "GOG"
                app == null -> ""
                app.id >= 2000000000 -> "EPIC"
                app.id < 0 -> "CUSTOM"
                else -> "STEAM"
            }
        selectedGogGameId = gogGame?.id.orEmpty()
    }

    val heroApps = rememberUpdatedState(displayedApps)
    val heroFocus = rememberUpdatedState(focusIndex)
    val openGame: (SteamApp) -> Unit = { app ->
        if (app.id in visibleInstalledIds) {
            detailGogGame = gameGogById[app.id]
            detailApp = app
        } else {
            installApp = app
        }
    }
    val latestOpenGame by rememberUpdatedState(openGame)
    LaunchedEffect(Unit) {
        activity?.openHeroForFocusedSignal?.collect {
            if (currentTabKey != if (home) "home" else "library") return@collect
            val list = heroApps.value
            val app = list.getOrNull(heroFocus.value) ?: list.firstOrNull()
            if (app != null) {
                latestOpenGame(app)
            }
        }
    }

    // Publish the focused game's hero art (custom card > store hero > grid capsule) for the immersive background; shortcuts load once per refresh signal, not per focus move.
    var immersiveShortcuts by remember { mutableStateOf<List<Shortcut>?>(null) }
    LaunchedEffect(shortcutRefreshKey, libraryRefreshKey, artworkCacheRefreshKey) {
        immersiveShortcuts =
            withContext(Dispatchers.IO) { ContainerManager(context).loadShortcuts() }
    }

    LaunchedEffect(focusIndex, displayedApps, immersiveShortcuts) {
        val shortcuts = immersiveShortcuts ?: return@LaunchedEffect
        val app = displayedApps.getOrNull(focusIndex) ?: displayedApps.firstOrNull()
        if (app == null) {
            activity?.immersiveBackgroundRef?.value = null
            return@LaunchedEffect
        }
        // Debounce so scrubbing the grid doesn't decode every intermediate hero.
        delay(200)
        val gogGame = gameGogById[app.id]
        val epicGame = gameEpicById[app.id]
        val isCustom = app.id < 0
        val isEpic = app.id >= 2000000000
        val epicId = if (isEpic) app.id - 2000000000 else 0

        val shortcut =
            when {
                gogGame != null ->
                    shortcuts.find {
                        it.getExtra("game_source") == "GOG" && it.getExtra("gog_id") == gogGame.id
                    }
                else -> findShortcutForGame(shortcuts, app, isCustom, isEpic, epicId)
            }
        val customHeroFile =
            withContext(Dispatchers.IO) {
                shortcut
                    ?.getExtra(LibraryShortcutArtwork.LibraryArtworkSlot.GAME_CARD.extraKey)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { java.io.File(it) }
                    ?.takeIf { it.isFile }
            }

        activity?.immersiveBackgroundRef?.value =
            customHeroFile
                ?: run {
                    val ref =
                        StoreArtworkCache.heroRef(app, gogGame, epicGame)
                            ?: StoreArtworkCache.primaryRef(
                                app,
                                gogGame,
                                epicGame,
                                useLibraryCapsule = false,
                                listMode = false,
                            )
                    StoreArtworkCache.imageModel(context, ref)
                }
    }

    val openSettingsForApp: (Int, SteamApp) -> Unit = { index, app ->
        activity?.libraryFocusIndex?.value = index
        selectedSteamAppId = app.id
        selectedSteamAppName = app.name
        val gogGame = gameGogById[app.id]
        selectedLibrarySource =
            when {
                gogGame != null -> "GOG"
                app.id >= 2000000000 -> "EPIC"
                app.id < 0 -> "CUSTOM"
                else -> "STEAM"
            }
        selectedGogGameId = gogGame?.id.orEmpty()

        if (gogGame != null) {
            selectedGogGameForSettings = gogGame
        } else {
            selectedAppForSettings = app
        }
    }

    PullToRefreshBox(
        isRefreshing = pullRefreshing,
        onRefresh = {
            pullRefreshing = true
            localLibraryRefreshKey++
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        if (home || layoutMode == LibraryLayoutMode.CAROUSEL) {
            val focusedApp = displayedApps.getOrNull(focusIndex) ?: displayedApps.first()
            val hero = visibleCustomHeroPathByAppId[focusedApp.id]?.let { java.io.File(it) }
                ?: StoreArtworkCache.imageModel(context, StoreArtworkCache.heroRef(focusedApp,
                    gameGogById[focusedApp.id], gameEpicById[focusedApp.id]))
            VaporGameShelf(displayedApps, home && displayedApps.any { it.id in recentlyOpenedIds }, focusIndex, hero,
                installed = focusedApp.id in visibleInstalledIds,
                onSelect = { libraryFocusIndex.value = it }, onOpenGame = openGame,
                onSettings = if (focusedApp.id in visibleInstalledIds) ({ openSettingsForApp(focusIndex, focusedApp) }) else null,
            ) { app, index, focused, cardModifier, activate ->
                GameCapsule(app = app,
                    useLibraryCapsule = true, showCaption = false,
                    gogGame = gameGogById[app.id], epicGame = gameEpicById[app.id],
                    iconRefreshKey = iconRefreshKey, artworkCacheRefreshKey = artworkCacheRefreshKey,
                    isFocusedOverride = focused, isControllerActive = true,
                    customArtworkPath = visibleCustomIconArtworkPathByAppId[app.id] ?: visibleCustomArtworkPathByAppId[app.id],
                    customIconPath = visibleCustomIconPathByAppId[app.id],
                    customCarouselPath = visibleCustomCarouselPathByAppId[app.id],
                    onClick = activate,
                    onLongClick = { openSettingsForApp(index, app) }, modifier = cardModifier)
            }
        } else Column(Modifier.fillMaxSize()) {
                Text(stringResource(R.string.vapor_catalog_count, displayedApps.size), color = TextSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (layoutMode == LibraryLayoutMode.GRID_4) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(storeColumns), state = gridState,
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                            itemsIndexed(displayedApps, key = { _, app -> app.id }) { index, app ->
                                GameCapsule(
                                    app = app,
                                    installStatus = app.id in visibleInstalledIds,
                                    useLibraryCapsule = true,
                                    gogGame = gameGogById[app.id],
                                    epicGame = gameEpicById[app.id],
                                    iconRefreshKey = iconRefreshKey,
                                    artworkCacheRefreshKey = artworkCacheRefreshKey,
                                    isFocusedOverride = index == focusIndex,
                                    isControllerActive = isControllerConnected,
                                    customArtworkPath = visibleCustomIconArtworkPathByAppId[app.id] ?: visibleCustomArtworkPathByAppId[app.id],
                                    customIconPath = visibleCustomIconPathByAppId[app.id],
                                    customListPath = visibleCustomListPathByAppId[app.id],
                                    customHeroPath = visibleCustomHeroPathByAppId[app.id],
                                    onClick = {
                                        // Keeps the immersive background on the opened game after backing out.
                                        activity?.libraryFocusIndex?.value = index
                                        openGame(app)
                                    },
                                    onLongClick = {
                                        openSettingsForApp(index, app)
                                    },
                                    modifier =
                                        Modifier
                                            .aspectRatio(2f / 3f)
                                            .then(
                                                if (index in focusRequesters.indices) {
                                                    Modifier.focusRequester(focusRequesters[index])
                                                } else {
                                                    Modifier
                                                },
                                            ),
                                )
                            }
                            }
                        } else {
                            val listViewState = rememberLazyListState()
                            ListView(
                                items = displayedApps,
                                modifier = Modifier.tabScreenPadding(),
                                listState = listViewState,
                                contentPadding = TabListContentPadding,
                                selectedIndex = focusIndex,
                                onSelectedIndexChanged = { newIdx ->
                                    activity?.libraryFocusIndex?.value = newIdx
                                },
                                keyOf = { it.id },
                            ) { app, index, isSelected ->
                                GameCapsule(
                                    app = app,
                                    installStatus = app.id in visibleInstalledIds,
                                    gogGame = gameGogById[app.id],
                                    epicGame = gameEpicById[app.id],
                                    iconRefreshKey = iconRefreshKey,
                                    artworkCacheRefreshKey = artworkCacheRefreshKey,
                                    isFocusedOverride = isSelected,
                                    isControllerActive = isControllerConnected,
                                    customArtworkPath = visibleCustomIconArtworkPathByAppId[app.id] ?: visibleCustomArtworkPathByAppId[app.id],
                                    customIconPath = visibleCustomIconPathByAppId[app.id],
                                    customListPath = visibleCustomListPathByAppId[app.id],
                                    customHeroPath = visibleCustomHeroPathByAppId[app.id],
                                    onClick = {
                                        // Keeps the immersive background on the opened game after backing out.
                                        activity?.libraryFocusIndex?.value = index
                                        openGame(app)
                                    },
                                    onLongClick = { openSettingsForApp(index, app) },
                                    listMode = true,
                                    modifier =
                                        Modifier
                                            .then(
                                                if (index in focusRequesters.indices) {
                                                    Modifier.focusRequester(focusRequesters[index])
                                                } else {
                                                    Modifier
                                                },
                                            ),
                                )
                            }
                            JoystickListScroll(
                                listState = listViewState,
                                stickFlow = activity?.rightStickScrollState,
                                minSpeed = 2.5f,
                                maxSpeed = 16f,
                                quadratic = true,
                            )
                        }
                }
        }
    }

    installApp?.let { app ->
        val gog = gameGogById[app.id]
        val epic = gameEpicById[app.id]
        when {
            gog != null -> GOGGameManagerDialog(gog) { installApp = null }
            epic != null -> EpicGameManagerDialog(epic) { installApp = null }
            else -> GameManagerDialog(app) { installApp = null }
        }
    }

    if (selectedAppForSettings != null) {
        GameSettingsDialog(
            app = selectedAppForSettings!!,
            onDismissRequest = { selectedAppForSettings = null },
        )
    }
    if (selectedGogGameForSettings != null) {
        GOGGameSettingsDialog(
            app = selectedGogGameForSettings!!,
            onDismissRequest = { selectedGogGameForSettings = null },
        )
    }
    if (detailApp != null) {
        LibraryGameDetailDialog(
            app = detailApp!!,
            gogGame = detailGogGame,
            storeOptions = detailStoreOptions,
            activeStore = detailActiveStore,
            onSelectStore = { target ->
                val current = detailStoreOptions.firstOrNull { it.store == detailActiveStore }
                val installPath = detailInstallPath
                detailActiveStore = target.store
                libraryScope.launch {
                    val rebound =
                        withContext(Dispatchers.IO) {
                            LibraryStoreTransfer.switchStore(context, installPath, current, target)
                            resolveStoreRow(target, customApps)
                        }
                    if (rebound != null) {
                        detailApp = rebound.first
                        detailGogGame = rebound.second
                    }
                    localLibraryRefreshKey++
                    com.winlator.cmod.shared.ui.toast.WinToast.show(
                        context,
                        context.getString(
                            R.string.library_games_store_switched,
                            libraryStoreDisplayName(
                                target.store,
                                context.getString(R.string.itch_store_title),
                            ),
                        ),
                    )
                }
            },
            onDismissRequest = {
                detailApp = null
                detailGogGame = null
            },
        )
    }
}

private suspend fun UnifiedActivity.resolveStoreRow(
    target: LibraryStoreOption,
    customApps: List<SteamApp>,
): Pair<SteamApp, GOGGame?>? {
    val db = PluviaDatabase.getInstance()
    return when (target.store) {
        InstallStore.STEAM ->
            target.storeGameId.toIntOrNull()?.let { db.steamAppDao().findApp(it) }?.let { it to null }

        InstallStore.EPIC ->
            target.storeGameId.toIntOrNull()?.let { db.epicGameDao().getById(it) }?.let { epic ->
                SteamApp(
                    id = 2000000000 + epic.id,
                    name = epic.title,
                    developer = epic.developer,
                    gameDir = epic.installPath,
                ) to null
            }

        InstallStore.GOG ->
            db.gogGameDao().getById(target.storeGameId)?.let { gog ->
                SteamApp(
                    id = gogPseudoId(gog.id),
                    name = gog.title,
                    developer = gog.developer,
                    gameDir = gog.installPath,
                ) to gog
            }

        InstallStore.ITCH ->
            customApps.firstOrNull { it.id == target.libraryId }?.let { it to null }
    }
}
