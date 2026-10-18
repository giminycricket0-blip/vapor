package com.winlator.cmod.app.shell

import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.layout.onGloballyPositioned
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
import com.winlator.cmod.feature.settings.InputControlsFragment
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
import com.winlator.cmod.feature.stores.steam.SteamLoginActivity
import com.winlator.cmod.feature.stores.steam.data.DepotInfo
import com.winlator.cmod.feature.stores.steam.data.DownloadInfo
import com.winlator.cmod.feature.stores.steam.data.SteamApp
import com.winlator.cmod.feature.stores.steam.enums.DownloadPhase
import com.winlator.cmod.feature.stores.steam.events.AndroidEvent
import com.winlator.cmod.feature.stores.steam.events.EventDispatcher
import com.winlator.cmod.feature.stores.steam.service.STEAM_DEFAULT_BRANCH
import com.winlator.cmod.feature.stores.steam.service.SteamService
import com.winlator.cmod.feature.stores.steam.service.getInstalledBranch
import com.winlator.cmod.feature.stores.steam.service.getSelectableBranches
import com.winlator.cmod.feature.stores.steam.service.getSelectedBranch
import com.winlator.cmod.feature.stores.steam.service.setSelectedBranch
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
import com.winlator.cmod.shared.ui.layout.isCompactWidth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

// Downloads presentation and existing game/workshop managers.

// Downloads Tab. DownloadService and DownloadCoordinator remain the owners of the queue.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UnifiedActivity.DownloadsTab(
    animationsActive: Boolean = true,
    onBrowseStore: () -> Unit,
) {
    val downloads = remember { mutableStateListOf<Pair<String, DownloadInfo>>() }
    var tick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var confirmCancelAll by remember { mutableStateOf(false) }
    var completedGame by remember { mutableStateOf<SteamApp?>(null) }
    val bridge = downloadsNavBridge
    val navRegistry = remember(bridge) { PaneNavRegistry(initialSignal = bridge.navSignal) }
    navRegistry.controllerActive = bridge.controllerActive
    LaunchedEffect(navRegistry, bridge.navSignal) {
        navRegistry.processNav(bridge.navSignal, bridge.navDir)
    }
    val syncDownloads = remember {
        {
            downloads.clear()
            downloads.addAll(DownloadService.getAllDownloads())
        }
    }
    val statusListener = remember {
        object : EventDispatcher.JavaEventListener {
            override fun onEvent(event: Any) {
                if (event is AndroidEvent.DownloadStatusChanged) scope.launch { syncDownloads() }
            }
        }
    }
    DisposableEffect(statusListener) {
        syncDownloads()
        PluviaApp.events.onJava(AndroidEvent.DownloadStatusChanged::class, statusListener)
        onDispose { PluviaApp.events.offJava(AndroidEvent.DownloadStatusChanged::class, statusListener) }
    }
    LaunchedEffect(Unit) { DownloadCoordinator.changes.collect { syncDownloads() } }
    downloads.forEach { (id, info) ->
        key(id, info) {
            LaunchedEffect(info) { info.getStatusFlow().collect { tick++ } }
        }
    }
    // Status changes reorder the existing queue; progress is observed inside each card.
    @Suppress("UNUSED_EXPRESSION")
    tick
    val unfinished = downloads.filter { (_, info) ->
        info.getStatusFlow().value !in listOf(DownloadPhase.COMPLETE, DownloadPhase.CANCELLED)
    }
    val allPaused = unfinished.isNotEmpty() && unfinished.all { (_, info) ->
        info.getStatusFlow().value in listOf(DownloadPhase.PAUSED, DownloadPhase.FAILED)
    }
    val hasHistory = downloads.any { (_, info) ->
        info.getStatusFlow().value in listOf(DownloadPhase.COMPLETE, DownloadPhase.CANCELLED, DownloadPhase.FAILED)
    }
    val sortedDownloads = downloads.sortedBy { (_, info) ->
        when (info.getStatusFlow().value) {
            DownloadPhase.PAUSED, DownloadPhase.FAILED -> 1
            DownloadPhase.QUEUED -> 2
            DownloadPhase.COMPLETE -> 3
            DownloadPhase.CANCELLED -> 4
            else -> 0
        }
    }
    val listState = rememberLazyListState()
    val density = LocalDensity.current.density
    LaunchedEffect(listState) {
        rightStickScrollState.collect {
            while (kotlin.math.abs(rightStickScrollState.value) > 0.1f) {
                val value = rightStickScrollState.value
                val speed = 1.25f + (value * value * (32f - 1.25f))
                listState.dispatchRawDelta(speed * (if (value > 0) 1f else -1f) * density)
                delay(16)
            }
        }
    }
    CompositionLocalProvider(LocalPaneNav provides navRegistry) {
        LazyColumn(
            state = listState,
            // UnifiedHub's Scaffold already reserves the navigation-bar inset.
            modifier = Modifier.fillMaxSize()
                .tabScreenPadding(top = 12.dp)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) {
                                bridge.controllerActive = false
                            }
                        }
                    }
                },
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "heading") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.vapor_downloads_title), style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                    Text(stringResource(R.string.vapor_downloads_intro), color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (downloads.isEmpty()) {
                item(key = "empty") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Icon(Icons.Outlined.Download, null, tint = Accent,
                                modifier = Modifier.size(48.dp).background(CardDark, RoundedCornerShape(12.dp)).padding(12.dp))
                            Text(stringResource(R.string.downloads_queue_empty), color = TextPrimary,
                                style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                            Text(stringResource(R.string.vapor_downloads_empty_hint), color = TextSecondary,
                                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                            DownloadsQueueButton(stringResource(R.string.vapor_browse_store), Accent, true, onClick = onBrowseStore)
                        }
                    }
                }
            } else {
                item(key = "queue_controls") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.vapor_downloads_count, unfinished.size), color = TextSecondary)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            DownloadsQueueButton(
                                label = stringResource(if (allPaused) R.string.downloads_queue_resume_all else R.string.downloads_queue_pause_all),
                                accentColor = Accent,
                                enabled = unfinished.isNotEmpty(),
                                onClick = {
                                    if (allPaused) runIfOnlineOrToast { DownloadService.resumeAll() }
                                    else DownloadService.pauseAll()
                                },
                            )
                            Box {
                                DownloadsQueueButton(
                                    label = stringResource(R.string.downloads_queue_cancel_all),
                                    accentColor = DangerRed,
                                    enabled = unfinished.isNotEmpty(),
                                    onClick = { confirmCancelAll = true },
                                )
                                DownloadCancelWarningMenu(
                                    expanded = confirmCancelAll,
                                    onDismissRequest = { confirmCancelAll = false },
                                    onConfirm = { confirmCancelAll = false; DownloadService.cancelAll() },
                                    isCancelAll = true,
                                )
                            }
                            DownloadsQueueButton(
                                label = stringResource(R.string.vapor_clear_download_history),
                                accentColor = TextSecondary,
                                enabled = hasHistory,
                                onClick = { DownloadService.clearCompletedDownloads() },
                            )
                        }
                        if (hasHistory) {
                            Text(stringResource(R.string.vapor_download_history_hint), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                items(sortedDownloads, key = { it.first }) { (id, info) ->
                    DownloadItemDeck(id = id, info = info, animationsActive = animationsActive,
                        onOpenGame = { completedGame = it })
                }
            }
        }
    }
    completedGame?.let { game ->
        LibraryGameDetailDialog(app = game, onDismissRequest = { completedGame = null })
    }
}

@Composable
internal fun DownloadsQueueButton(
    label: String,
    accentColor: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onSecondary: () -> Unit = {},
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp).controllerFocusGlow()
            .then(if (enabled) Modifier.paneNavItem(cornerRadius = 10.dp, onActivate = onClick, onSecondary = onSecondary) else Modifier),
        colors = ButtonDefaults.buttonColors(
            containerColor = SurfaceDark,
            contentColor = accentColor,
            disabledContainerColor = SurfaceDark.copy(alpha = 0.5f),
            disabledContentColor = TextSecondary.copy(alpha = 0.5f),
        ),
        border = BorderStroke(1.dp, if (enabled) accentColor.copy(alpha = 0.5f) else CardBorder),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun DownloadProgressBar(progress: Float, status: DownloadPhase, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier,
        color = when (status) {
            DownloadPhase.FAILED, DownloadPhase.CANCELLED -> DangerRed
            DownloadPhase.COMPLETE -> StatusOnline
            DownloadPhase.PAUSED -> TextSecondary
            else -> Accent
        },
        trackColor = CardBorder,
    )
}

/**
 * The live progress body (phase label, bar, percentage, byte counts) for a
 * game's in-flight download / verify. Observes [info] directly so it
 * refreshes live. Rendered inside [SteamTaskProgressDialog].
 */
@Composable
internal fun UnifiedActivity.SteamTaskProgressBody(info: DownloadInfo) {
    var progress by remember(info) { mutableFloatStateOf(info.getProgress()) }
    DisposableEffect(info) {
        val listener: (Float) -> Unit = { progress = it }
        info.addProgressListener(listener)
        onDispose { info.removeProgressListener(listener) }
    }
    val status by info.getStatusFlow().collectAsState()
    // The status message carries a unique suffix every progress tick;
    // keying the byte sample on it (and on `progress`) keeps the card
    // refreshing live — the Downloads-tab row relies on the same.
    val statusMessage by info.getStatusMessageFlow().collectAsState()
    val fraction = progress.coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = 400),
        label = "steamTaskProgress",
    )
    val (doneBytes, totalBytes) =
        remember(progress, statusMessage) { info.getDisplayBytesProgress() }

    val phaseText =
        when (status) {
            DownloadPhase.VERIFYING -> stringResource(R.string.downloads_queue_phase_verifying)
            DownloadPhase.DOWNLOADING -> stringResource(R.string.downloads_queue_phase_downloading)
            DownloadPhase.PAUSED -> stringResource(R.string.downloads_queue_phase_paused)
            DownloadPhase.QUEUED -> stringResource(R.string.downloads_queue_phase_queued)
            DownloadPhase.PREPARING -> stringResource(R.string.downloads_queue_phase_preparing)
            DownloadPhase.PATCHING -> stringResource(R.string.downloads_queue_phase_patching)
            DownloadPhase.APPLYING_DATA -> stringResource(R.string.downloads_queue_phase_applying_data)
            DownloadPhase.UNPACKING -> stringResource(R.string.downloads_queue_phase_unpacking)
            DownloadPhase.FINALIZING -> stringResource(R.string.downloads_queue_phase_finalizing)
            DownloadPhase.COMPLETE -> stringResource(R.string.downloads_queue_phase_complete)
            DownloadPhase.FAILED -> stringResource(R.string.common_ui_failed)
            DownloadPhase.CANCELLED -> stringResource(R.string.downloads_queue_phase_cancelled)
            else -> stringResource(R.string.common_ui_working)
        }
    val phaseColor =
        when (status) {
            DownloadPhase.COMPLETE -> StatusOnline
            DownloadPhase.FAILED, DownloadPhase.CANCELLED -> DangerRed
            DownloadPhase.PAUSED, DownloadPhase.QUEUED -> StatusAway
            else -> Accent
        }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(phaseText, color = phaseColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                "${(fraction * 100).toInt()}%",
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        DownloadProgressBar(
            progress = animatedFraction,
            status = status,
            modifier = Modifier.fillMaxWidth().height(10.dp),
        )
        Text(
            if (totalBytes > 0L) {
                "${StorageUtils.formatDecimalSize(doneBytes)} / " +
                    StorageUtils.formatDecimalSize(totalBytes)
            } else {
                " "
            },
            color = TextSecondary,
            fontSize = 11.sp,
        )
    }
}

/**
 * Activity-root host for the Verify Files progress pop-up + completion
 * notice. Rendered once near the NavHost so it outlives the game-detail
 * dialogs that start the task — the verify-completion library refresh
 * tears those down, and a dialog-scoped watcher would miss COMPLETE.
 *
 * Reads the `taskProgress*` activity fields; [showTaskProgressPopup]
 * populates them. The watcher is keyed on [taskProgressInfo] so it
 * re-attaches if recomposed and (because the status is a StateFlow)
 * still observes a terminal phase that landed in between.
 */
@Composable
internal fun UnifiedActivity.TaskProgressHost() {
    val info = taskProgressInfo
    LaunchedEffect(info) {
        if (info == null) return@LaunchedEffect
        info.getStatusFlow().collect { st ->
            when (st) {
                DownloadPhase.COMPLETE -> {
                    // Snapshot first: `taskProgressShown = false` can trigger
                    // a follow-up task that overwrites these fields before we read.
                    val msg = taskProgressCompleteMsg
                    val asToast = taskProgressCompleteAsToast
                    taskProgressShown = false
                    taskProgressInfo = null
                    if (asToast) {
                        com.winlator.cmod.shared.ui.toast.WinToast.show(
                            this@TaskProgressHost,
                            msg,
                            android.widget.Toast.LENGTH_SHORT,
                        )
                    } else {
                        taskDoneFailed = false
                        taskDoneMessage = msg
                    }
                }
                DownloadPhase.FAILED -> {
                    taskProgressShown = false
                    taskDoneFailed = true
                    taskDoneMessage = taskProgressFailedMsg
                    taskProgressInfo = null
                }
                DownloadPhase.CANCELLED -> {
                    taskProgressShown = false
                    taskProgressInfo = null
                }
                else -> Unit
            }
        }
    }
    if (taskCheckingShown) {
        TaskCheckingDialog(
            gameName = taskCheckingGameName,
            onDismissRequest = { taskCheckingShown = false },
        )
    }
    if (info != null && taskProgressShown) {
        SteamTaskProgressDialog(
            info = info,
            gameName = taskProgressGameName,
            onDismissRequest = { taskProgressShown = false },
        )
    }
    taskDoneMessage?.let { msg ->
        TaskCompleteDialog(
            message = msg,
            failed = taskDoneFailed,
            onClose = { taskDoneMessage = null },
        )
    }
}

/**
 * Indeterminate "Checking for updates" pop-up — same frame as
 * [SteamTaskProgressDialog] but without a known task to track. Dismissable;
 * the underlying check keeps running and the host shows the result.
 */
@Composable
internal fun UnifiedActivity.TaskCheckingDialog(
    gameName: String,
    onDismissRequest: () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        PopupDialog(
            title = gameName,
            message = stringResource(R.string.store_game_checking_updates),
            icon = Icons.Outlined.Sync,
            accentColor = Accent,
            modifier = Modifier.widthIn(min = 280.dp, max = 360.dp),
            content = {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Accent,
                )
            },
            footer = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    PopupTextAction(
                        label = stringResource(R.string.common_ui_close),
                        textColor = Accent,
                        onClick = onDismissRequest,
                    )
                }
            },
        )
    }
}

/**
 * Dismissable pop-up showing live progress for a Steam task (verify /
 * update). Tapping outside closes it — the task keeps running and stays
 * visible in the Downloads tab. The host watches the task to completion
 * separately and shows [TaskCompleteDialog] when it finishes.
 */
@Composable
internal fun UnifiedActivity.SteamTaskProgressDialog(
    info: DownloadInfo,
    gameName: String,
    onDismissRequest: () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        PopupDialog(
            title = gameName,
            icon = Icons.Outlined.Download,
            accentColor = Accent,
            modifier = Modifier.widthIn(min = 280.dp, max = 360.dp),
            content = {
                SteamTaskProgressBody(info)
                Text(
                    stringResource(R.string.store_game_progress_background_hint),
                    color = TextSecondary,
                    fontSize = 11.sp,
                )
            },
            footer = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    PopupTextAction(
                        label = stringResource(R.string.common_ui_close),
                        textColor = Accent,
                        onClick = onDismissRequest,
                    )
                }
            },
        )
    }
}

/**
 * Small completion notice ("Verify Files Complete" / " Failed") with a
 * single Close button. Shown by the host once a watched task finishes.
 */
@Composable
internal fun UnifiedActivity.TaskCompleteDialog(message: String, failed: Boolean, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        PopupDialog(
            title = message,
            icon = if (failed) Icons.Outlined.Warning else Icons.Outlined.CheckCircle,
            accentColor = if (failed) DangerRed else StatusOnline,
            confirmButtonColor = Accent,
            confirmLabel = stringResource(R.string.common_ui_close),
            onConfirm = onClose,
            modifier = Modifier.widthIn(min = 280.dp, max = 360.dp),
        )
    }
}

@Composable
internal fun DownloadCancelWarningMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    isCancelAll: Boolean = false,
) {
    val titleRes = if (isCancelAll) R.string.downloads_queue_cancel_all_title else R.string.downloads_queue_cancel_download_title
    val messageRes = if (isCancelAll) R.string.downloads_queue_cancel_all_warning else R.string.downloads_queue_cancel_download_warning
    val confirmRes = if (isCancelAll) R.string.downloads_queue_cancel_all else R.string.downloads_queue_cancel_download
    LaunchDangerConfirmDialog(
        visible = expanded,
        title = stringResource(titleRes),
        message = stringResource(messageRes),
        confirmLabel = stringResource(confirmRes),
        onDismissRequest = onDismissRequest,
        onConfirm = onConfirm,
        icon = Icons.Outlined.Warning,
        titleTextAlign = TextAlign.Center,
        messageTextAlign = TextAlign.Center,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UnifiedActivity.DownloadItemDeck(
    id: String,
    info: DownloadInfo,
    animationsActive: Boolean,
    onOpenGame: (SteamApp) -> Unit,
) {
    val isSteam = id.startsWith("STEAM_")
    val isEpic = id.startsWith("EPIC_")
    val isGog = id.startsWith("GOG_")
    val isItch = id.startsWith("ITCH_")
    val appId =
        if (isSteam) {
            id.removePrefix("STEAM_").toIntOrNull() ?: 0
        } else if (isEpic) {
            id.removePrefix("EPIC_").toIntOrNull() ?: 0
        } else {
            0
        }
    val gogId = if (isGog) id.removePrefix("GOG_") else ""
    val itchId = if (isItch) id.removePrefix("ITCH_").toIntOrNull() ?: 0 else 0

    var steamApp by remember(appId) { mutableStateOf<SteamApp?>(null) }
    var epicGame by remember(appId) { mutableStateOf<EpicGame?>(null) }
    var gogGame by remember(gogId) { mutableStateOf<GOGGame?>(null) }
    var itchGame by remember(itchId) {
        mutableStateOf<com.winlator.cmod.feature.stores.itch.service.ItchInstalledGame?>(null)
    }
    val context = LocalContext.current
    LaunchedEffect(appId, gogId, itchId, isSteam, isEpic, isGog, isItch) {
        withContext(Dispatchers.IO) {
            if (isSteam) {
                steamApp = db.steamAppDao().findApp(appId)
            } else if (isEpic) {
                epicGame = EpicService.getEpicGameOf(appId)
            } else if (isGog) {
                gogGame = GOGService.getGOGGameOf(gogId)
            } else if (isItch) {
                itchGame =
                    com.winlator.cmod.feature.stores.itch.service.ItchLibrary
                        .find(context, itchId)
            }
        }
    }

    val unknownGameLabel = stringResource(R.string.library_games_unknown_game)
    val displayName =
        if (isSteam) {
            steamApp?.name
        } else if (isEpic) {
            epicGame?.title
        } else if (isGog) {
            gogGame?.title
        } else if (isItch) {
            itchGame?.title ?: unknownGameLabel
        } else {
            unknownGameLabel
        }
    val displayImage =
        if (isSteam) {
            steamApp?.getHeaderImageUrl()
        } else if (isEpic) {
            epicGame?.primaryImageUrl ?: epicGame?.iconUrl
        } else if (isGog) {
            gogGame?.imageUrl ?: gogGame?.iconUrl
        } else if (isItch) {
            itchGame?.coverUrl
        } else {
            null
        }

    val status by info.getStatusFlow().collectAsStateWithLifecycle()
    val app = steamApp
    val canOpen = isSteam && app != null && status == DownloadPhase.COMPLETE && SteamService.isAppInstalled(appId)
    DownloadQueueCard(
        title = displayName ?: unknownGameLabel, image = displayImage,
        store = when { isSteam -> "Steam"; isEpic -> "Epic"; isGog -> "GOG"; isItch -> "itch.io"; else -> "" },
        info = info, animationsActive = animationsActive,
        onPause = { DownloadService.pauseDownload(id) },
        onResume = { runIfOnlineOrToast { DownloadService.resumeDownload(id) } },
        onCancel = { DownloadService.cancelDownload(id) },
        onOpenGame = if (canOpen) ({ onOpenGame(app!!) }) else null,
    )
}

// Game Manager Dialog
@Composable
internal fun UnifiedActivity.GameManagerDialog(
    app: SteamApp,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var selectedManifestSizes by remember { mutableStateOf(SteamService.ManifestSizes()) }
    var baseInstallSize by remember(app.id) { mutableStateOf(0L) }
    var dlcApps by remember { mutableStateOf<List<SteamApp>>(emptyList()) }
    var dlcSizes by remember { mutableStateOf<Map<Int, SteamService.ManifestSizes>>(emptyMap()) }
    var installedDlcIds by remember(app.id) { mutableStateOf<Set<Int>>(emptySet()) }
    var installed by remember(app.id) { mutableStateOf<Boolean?>(null) }
    val selectedDlcIds = remember { mutableStateListOf<Int>() }
    var customPath by remember { mutableStateOf<String?>(null) }
    var showCustomPathWarning by remember { mutableStateOf(false) }
    var showDownloadTarget by remember(app.id) { mutableStateOf(false) }
    var isCheckingForUpdate by remember(app.id) { mutableStateOf(false) }
    var isUpdateCheckCoolingDown by remember(app.id) { mutableStateOf(false) }
    var showWorkshopDialog by remember(app.id) { mutableStateOf(false) }
    var updateInfo by remember(app.id) { mutableStateOf<SteamService.SteamUpdateInfo?>(null) }
    var updateStatusText by remember(app.id) { mutableStateOf<String?>(null) }
    var branchOptions by remember(app.id) { mutableStateOf<List<StoreBranchOption>>(emptyList()) }
    var selectedBranchId by remember(app.id) { mutableStateOf(STEAM_DEFAULT_BRANCH) }
    val downloadRecords by com.winlator.cmod.app.service.download.DownloadCoordinator.records.collectAsState(
        initial = com.winlator.cmod.app.service.download.DownloadCoordinator.snapshotRecords(),
    )
    val scope = rememberCoroutineScope()

    if (showCustomPathWarning) {
        CustomPathWarningDialog(
            onDismiss = { showCustomPathWarning = false },
            onProceed = {
                showCustomPathWarning = false
                DirectoryPickerDialog.show(
                    activity = this@GameManagerDialog,
                    initialPath = customPath ?: SteamService.defaultAppInstallPath,
                    title = getString(R.string.settings_content_install_directory),
                    extraRoots = driveRoots(includeInternal = true),
                ) { path -> customPath = path }
            },
        )
    }

    data class SteamInstallLoadData(
        val dlcApps: List<SteamApp>,
        val dlcSizes: Map<Int, SteamService.ManifestSizes>,
        val installedDlcIds: Set<Int>,
        val baseManifestSizes: SteamService.ManifestSizes,
        val installed: Boolean,
        val branches: List<StoreBranchOption>,
        val selectedBranch: String,
    )

    val publicBranchLabel = stringResource(R.string.store_game_branch_public)

    LaunchedEffect(app.id, downloadRecords) {
        val loadData =
            withContext(Dispatchers.IO) {
                val selectableDlcApps = SteamService.getSelectableDlcAppsOf(app.id)
                val perDlcSizes =
                    selectableDlcApps.associate { dlc ->
                        dlc.id to SteamService.getDlcOnlyManifestSizes(app.id, dlc.id)
                    }
                val installedDlcIds =
                    SteamService.getInstalledDlcDepotsOf(app.id)
                        .orEmpty()
                        .toSet()
                val installedBranch = SteamService.getInstalledBranch(app.id)
                val isInstalled = SteamService.isAppInstalled(app.id)
                SteamInstallLoadData(
                    dlcApps = selectableDlcApps,
                    dlcSizes = perDlcSizes,
                    installedDlcIds = installedDlcIds,
                    baseManifestSizes = SteamService.getInstallableSelectedManifestSizes(app.id),
                    installed = isInstalled,
                    branches =
                        SteamService.getSelectableBranches(app.id).map { branch ->
                            StoreBranchOption(
                                id = branch.name,
                                label =
                                    if (branch.name.equals(STEAM_DEFAULT_BRANCH, ignoreCase = true)) {
                                        publicBranchLabel
                                    } else {
                                        branch.name
                                    },
                                buildId = branch.buildId,
                                isInstalled = isInstalled && branch.name.equals(installedBranch, ignoreCase = true),
                            )
                        },
                    selectedBranch = SteamService.getSelectedBranch(app.id),
                )
            }
        dlcApps = loadData.dlcApps
        dlcSizes = loadData.dlcSizes
        installedDlcIds = loadData.installedDlcIds
        selectedDlcIds.removeAll(loadData.installedDlcIds)
        selectedManifestSizes = loadData.baseManifestSizes
        baseInstallSize = loadData.baseManifestSizes.installSize
        installed = loadData.installed
        branchOptions = loadData.branches
        selectedBranchId = loadData.selectedBranch
        isLoading = false
    }

    LaunchedEffect(app.id, selectedBranchId, selectedDlcIds.toList()) {
        selectedManifestSizes =
            withContext(Dispatchers.IO) {
                SteamService.getInstallableSelectedManifestSizes(app.id, selectedDlcIds.toList())
            }
    }

    val totalDownloadSize = selectedManifestSizes.downloadSize
    val totalInstallSize = selectedManifestSizes.installSize
    val defaultPathSet =
        if (PrefManager.useSingleDownloadFolder) {
            PrefManager.defaultDownloadFolder.isNotEmpty()
        } else {
            PrefManager.steamDownloadFolder
                .isNotEmpty()
        }
    val effectivePath = customPath ?: SteamService.defaultAppInstallPath
    val availableBytes =
        try {
            StorageUtils.getAvailableSpace(effectivePath)
        } catch (e: Exception) {
            0L
        }
    // For an already-installed game the base content is on disk, so only require free
    // space for the newly-selected DLC (already-installed DLC is excluded from selection).
    val requiredBytes =
        if (installed == true) (totalInstallSize - baseInstallSize).coerceAtLeast(0L) else totalInstallSize
    val isInstallEnabled = requiredBytes == 0L || availableBytes >= requiredBytes
    val installPathDisplay = customPath ?: SteamService.defaultAppInstallPath

    val dlcItems =
        remember(dlcApps, dlcSizes, installedDlcIds) {
            dlcApps.map { dlc ->
                val sizes = dlcSizes[dlc.id]
                val size =
                    sizes
                        ?.downloadSize
                        ?.takeIf { it > 0L }
                        ?: sizes?.installSize
                        ?: 0L
                StoreDlcItem(
                    id = dlc.id,
                    name = dlc.name,
                    downloadSize = size,
                    isInstalled = dlc.id in installedDlcIds,
                )
            }
        }
    val customPathLabel =
        when {
            customPath != null -> stringResource(R.string.common_ui_custom)
            defaultPathSet -> stringResource(R.string.common_ui_already_set)
            else -> stringResource(R.string.common_ui_custom)
        }
    val isReallyInstalled = installed == true
    val externalStorageState by com.winlator.cmod.feature.storage.ExternalStorage.state.collectAsState()
    val externalDrive = externalStorageState.preferredDrive()
    val externalInstallRoot =
        externalDrive?.let {
            com.winlator.cmod.feature.storage.ExternalStorage
                .storeInstallRoot(
                    it.drive.downloadPath,
                    com.winlator.cmod.feature.stores.common.InstallStore.STEAM,
                )
        }
    val steamDownloadRecord =
        downloadRecords.firstOrNull {
            it.store == com.winlator.cmod.app.db.download.DownloadRecord.STORE_STEAM &&
                it.storeGameId == app.id.toString() &&
                it.status in setOf(
                    com.winlator.cmod.app.db.download.DownloadRecord.STATUS_QUEUED,
                    com.winlator.cmod.app.db.download.DownloadRecord.STATUS_DOWNLOADING,
                    com.winlator.cmod.app.db.download.DownloadRecord.STATUS_PAUSED,
                )
    }
    val hasBlockingSteamDownload =
        downloadRecords.any {
            it.store == com.winlator.cmod.app.db.download.DownloadRecord.STORE_STEAM &&
                it.storeGameId == app.id.toString() &&
                it.status in setOf(
                    com.winlator.cmod.app.db.download.DownloadRecord.STATUS_QUEUED,
                    com.winlator.cmod.app.db.download.DownloadRecord.STATUS_DOWNLOADING,
                    com.winlator.cmod.app.db.download.DownloadRecord.STATUS_PAUSED,
                    com.winlator.cmod.app.db.download.DownloadRecord.STATUS_FAILED,
                )
        }
    val updateActionEnabled = steamDownloadRecord == null
    val installActionEnabled = isInstallEnabled && steamDownloadRecord == null
    val activeSteamDownloadText = stringResource(R.string.store_game_download_already_active)
    val noUpdateAvailableText = stringResource(R.string.store_game_no_update_available)
    val updateAvailableText = stringResource(R.string.store_game_update_available)
    val updateFailedText = stringResource(R.string.store_game_update_check_failed)

    val startSteamInstall: (com.winlator.cmod.feature.storage.DownloadTarget) -> Unit = { target ->
        val requestedPath =
            if (target == com.winlator.cmod.feature.storage.DownloadTarget.EXTERNAL) {
                externalInstallRoot ?: customPath
            } else {
                customPath
            }
        context.runIfOnlineOrToast {
            scope.launch(Dispatchers.IO) {
                val installableDlcIds = dlcItems
                    .filter { !it.isInstalled && it.id in selectedDlcIds }
                    .map { it.id }
                SteamService.downloadApp(app.id, installableDlcIds, false, requestedPath)
                withContext(Dispatchers.Main) { onDismissRequest() }
            }
        }
    }

    if (showDownloadTarget) {
        com.winlator.cmod.feature.storage.DownloadTargetDialog(
            internalPath = installPathDisplay,
            externalPath = externalInstallRoot,
            externalLabel = externalDrive?.drive?.label.orEmpty(),
            onDismiss = { showDownloadTarget = false },
            onConfirm = { target ->
                showDownloadTarget = false
                startSteamInstall(target)
            },
        )
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RectangleShape,
            color = Color.Black,
        ) {
            StoreGameDetailScreen(
                title = app.name,
                subtitle =
                    listOfNotNull(
                        app.developer.takeIf { it.isNotBlank() },
                        app.publisher.takeIf {
                            it.isNotBlank() && !it.equals(app.developer, ignoreCase = true)
                        },
                    ).joinToString(" • "),
                sourceLabel = "Steam",
                heroImageUrl = StoreArtworkCache.imageModel(context, StoreArtworkCache.steamRef(app, "hero", app.getHeroUrl())),
                isLoading = isLoading,
                isInstalled = isReallyInstalled,
                installPathDisplay = installPathDisplay,
                downloadSize = totalDownloadSize,
                installSize = totalInstallSize,
                availableBytes = availableBytes,
                isInstallEnabled = isInstallEnabled,
                isDownloadActionEnabled = installActionEnabled,
                customPathLabel = customPathLabel,
                showCustomPath = true,
                showCloudSync = false,
                showUninstall = false,
                showUpdateCheck = true,
                isCheckingForUpdate = isCheckingForUpdate,
                isUpdateAvailable = updateInfo?.hasUpdate == true,
                updateDownloadSize = updateInfo?.downloadSize ?: 0L,
                updateStatusText = updateStatusText,
                isUpdateActionEnabled = updateActionEnabled,
                isUpdateCheckCoolingDown = isUpdateCheckCoolingDown,
                // Shown for any installed game; titles without UGC simply
                // open to an empty Workshop window (handled gracefully).
                showWorkshop = isReallyInstalled,
                showVerifyFiles = isReallyInstalled,
                areSteamActionsEnabled = !hasBlockingSteamDownload,
                dlcs = dlcItems,
                selectedDlcIds = selectedDlcIds.toSet(),
                isDlcSelectionEnabled = steamDownloadRecord == null,
                branches = branchOptions,
                selectedBranchId = selectedBranchId,
                isBranchSelectionEnabled = steamDownloadRecord == null,
                onSelectBranch = { branchId ->
                    if (steamDownloadRecord != null) {
                        return@StoreGameDetailScreen
                    }
                    selectedBranchId = branchId
                    val label =
                        branchOptions.firstOrNull { it.id == branchId }?.label ?: branchId
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            SteamService.setSelectedBranch(app.id, branchId)
                        }
                        updateInfo = null
                        updateStatusText = null
                        selectedManifestSizes =
                            withContext(Dispatchers.IO) {
                                SteamService.getInstallableSelectedManifestSizes(app.id, selectedDlcIds.toList())
                            }
                        com.winlator.cmod.shared.ui.toast.WinToast.show(
                            context,
                            if (isReallyInstalled) {
                                getString(R.string.store_game_branch_switch_installed, label)
                            } else {
                                getString(R.string.store_game_branch_changed, label)
                            },
                            android.widget.Toast.LENGTH_SHORT,
                        )
                    }
                },
                onBack = onDismissRequest,
                onPlay = {
                    // Reuse the same genuine-client launch path as the Library.
                    launchSteamGame(context, ContainerManager(context), app)
                    onDismissRequest()
                },
                isPlayEnabled = !hasBlockingSteamDownload,
                onInstall = {
                    if (steamDownloadRecord != null) {
                        com.winlator.cmod.shared.ui.toast.WinToast.show(
                            context,
                            activeSteamDownloadText,
                            android.widget.Toast.LENGTH_SHORT,
                        )
                        return@StoreGameDetailScreen
                    }
                    if (isReallyInstalled) {
                        startSteamInstall(com.winlator.cmod.feature.storage.DownloadTarget.INTERNAL)
                    } else {
                        showDownloadTarget = true
                    }
                },
                onCheckForUpdate = { startUpdateCheck(app.id, app.name) },
                onWorkshop = { showWorkshopDialog = true },
                onVerifyFiles = {
                    if (steamDownloadRecord != null) {
                        com.winlator.cmod.shared.ui.toast.WinToast.show(
                            context,
                            activeSteamDownloadText,
                            android.widget.Toast.LENGTH_SHORT,
                        )
                        return@StoreGameDetailScreen
                    }
                    context.runIfOnlineOrToast {
                        scope.launch {
                            val started =
                                withContext(Dispatchers.IO) {
                                    SteamService.downloadAppForVerify(app.id)
                                }
                            if (started != null) {
                                // Hand off to the activity-root host so the
                                // pop-up + completion notice outlive this dialog.
                                showTaskProgressPopup(
                                    started,
                                    app.name,
                                    getString(R.string.store_game_verify_complete),
                                    getString(R.string.store_game_verify_failed_notice),
                                    completeAsToast = true,
                                )
                            }
                        }
                    }
                },
                onDownloadUpdate = {
                    if (!updateActionEnabled || updateInfo?.hasUpdate != true) return@StoreGameDetailScreen
                    context.runIfOnlineOrToast {
                        scope.launch(Dispatchers.IO) {
                            try {
                                val latest = SteamService.checkForAppUpdate(app.id)
                                withContext(Dispatchers.Main) {
                                    updateInfo = latest
                                    updateStatusText =
                                        when {
                                            latest.hasUpdate -> updateAvailableText
                                            latest.message != null -> updateFailedText
                                            else -> null
                                        }
                                }
                                if (!latest.hasUpdate) {
                                    withContext(Dispatchers.Main) {
                                        com.winlator.cmod.shared.ui.toast.WinToast.show(
                                            context,
                                            noUpdateAvailableText,
                                            android.widget.Toast.LENGTH_SHORT,
                                        )
                                    }
                                    return@launch
                                }

                                SteamService.downloadAppForUpdate(app.id, latest.depotIds)
                                withContext(Dispatchers.Main) { onDismissRequest() }
                            } catch (e: Exception) {
                                Log.w("UnifiedActivity", "Steam update download failed to start for appId=${app.id}", e)
                                withContext(Dispatchers.Main) {
                                    updateStatusText = updateFailedText
                                }
                            }
                        }
                    }
                },
                onCustomPath = {
                    if (customPath == null && defaultPathSet) {
                        showCustomPathWarning = true
                    } else {
                        DirectoryPickerDialog.show(
                            activity = this@GameManagerDialog,
                            initialPath = customPath ?: SteamService.defaultAppInstallPath,
                            title = getString(R.string.settings_content_install_directory),
                            extraRoots = driveRoots(includeInternal = true),
                        ) { path -> customPath = path }
                    }
                },
                onToggleDlc = { id ->
                    if (steamDownloadRecord != null) {
                        return@StoreGameDetailScreen
                    }
                    if (dlcItems.any { it.id == id && it.isInstalled }) {
                        return@StoreGameDetailScreen
                    }
                    if (selectedDlcIds.contains(id)) {
                        selectedDlcIds.remove(id)
                    } else {
                        selectedDlcIds.add(id)
                    }
                },
                onToggleSelectAllDlcs = {
                    if (steamDownloadRecord != null) {
                        return@StoreGameDetailScreen
                    }
                    val selectableDlcItems = dlcItems.filterNot { it.isInstalled }
                    val all = selectableDlcItems.isNotEmpty() && selectableDlcItems.all { it.id in selectedDlcIds }
                    if (all) {
                        selectedDlcIds.removeAll(selectableDlcItems.map { it.id }.toSet())
                    } else {
                        selectableDlcItems.forEach { if (it.id !in selectedDlcIds) selectedDlcIds.add(it.id) }
                    }
                },
            )
        }
    }

    if (showWorkshopDialog) {
        WorkshopDialog(
            appId = app.id,
            gameTitle = app.name,
            onDismissRequest = { showWorkshopDialog = false },
        )
    }
}

@Composable
internal fun UnifiedActivity.WorkshopDialog(
    appId: Int,
    gameTitle: String,
    onDismissRequest: () -> Unit,
) {
    var loadState by remember(appId) { mutableStateOf(WorkshopLoadState.LOADING) }
    var errorMessage by remember(appId) { mutableStateOf<String?>(null) }
    var allItems by remember(appId) { mutableStateOf<List<StoreWorkshopItem>>(emptyList()) }
    var query by remember(appId) { mutableStateOf("") }
    // Published-file-ids with an install OR uninstall in flight.
    val busyIds = remember(appId) { mutableStateListOf<Long>() }
    var reloadKey by remember(appId) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(appId, reloadKey) {
        loadState = WorkshopLoadState.LOADING
        errorMessage = null
        // Drop any in-flight spinners — a reload re-fetches the list.
        busyIds.clear()
        try {
            val items =
                withContext(Dispatchers.IO) {
                    val json = SteamService.getSubscribedWorkshopItems(appId)
                    if (json == null) {
                        null
                    } else {
                        val installed =
                            com.winlator.cmod.feature.stores.steam.workshop.WorkshopModsGenerator
                                .installedItemIds(applicationContext, appId)
                        parseWorkshopItemsJson(json, installed)
                    }
                }
            if (items == null) {
                errorMessage =
                    "Couldn't load your Workshop subscriptions. " +
                        "Make sure you're signed in to Steam and online."
                loadState = WorkshopLoadState.ERROR
            } else {
                allItems = items
                loadState = WorkshopLoadState.READY
            }
        } catch (e: Exception) {
            Log.w("UnifiedActivity", "Workshop load failed for appId=$appId", e)
            errorMessage = e.message
            loadState = WorkshopLoadState.ERROR
        }
    }

    val filtered =
        remember(allItems, query) {
            val q = query.trim()
            if (q.isBlank()) {
                allItems
            } else {
                allItems.filter {
                    it.title.contains(q, ignoreCase = true) ||
                        it.author.contains(q, ignoreCase = true)
                }
            }
        }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        StoreWorkshopScreen(
            gameTitle = gameTitle,
            loadState = loadState,
            errorMessage = errorMessage,
            items = filtered,
            query = query,
            // Snapshotted here inside the Dialog content lambda: a mutation of
            // the SnapshotStateList invalidates this scope, so .toSet() re-runs.
            busyIds = busyIds.toSet(),
            onQueryChange = { query = it },
            onInstall = { id ->
                val item = allItems.firstOrNull { it.publishedFileId == id }
                if (item != null && id !in busyIds) {
                    if (item.manifestId == 0L) {
                        com.winlator.cmod.shared.ui.toast.WinToast.show(
                            this@WorkshopDialog,
                            "This Workshop item has no downloadable content",
                            android.widget.Toast.LENGTH_SHORT,
                        )
                    } else {
                        busyIds.add(id)
                        scope.launch {
                            val ok =
                                SteamService.installWorkshopItem(
                                    appId = appId,
                                    publishedFileId = item.publishedFileId,
                                    manifestId = item.manifestId,
                                    title = item.title,
                                    fileSizeBytes = item.fileSizeBytes,
                                    timeUpdated = item.timeUpdated,
                                    previewUrl = item.previewImageUrl ?: "",
                                )
                            if (ok) {
                                allItems =
                                    allItems.map {
                                        if (it.publishedFileId == id) it.copy(isInstalled = true) else it
                                    }
                            } else {
                                com.winlator.cmod.shared.ui.toast.WinToast.show(
                                    this@WorkshopDialog,
                                    "Workshop download failed — check your Steam connection",
                                    android.widget.Toast.LENGTH_LONG,
                                )
                            }
                            busyIds.remove(id)
                        }
                    }
                }
            },
            onUninstall = { id ->
                if (id !in busyIds) {
                    busyIds.add(id)
                    scope.launch {
                        val ok =
                            withContext(Dispatchers.IO) {
                                com.winlator.cmod.feature.stores.steam.workshop.WorkshopModsGenerator
                                    .uninstall(applicationContext, appId, id)
                            }
                        if (ok) {
                            allItems =
                                allItems.map {
                                    if (it.publishedFileId == id) it.copy(isInstalled = false) else it
                                }
                        } else {
                            com.winlator.cmod.shared.ui.toast.WinToast.show(
                                this@WorkshopDialog,
                                "Couldn't uninstall this Workshop item",
                                android.widget.Toast.LENGTH_SHORT,
                            )
                        }
                        busyIds.remove(id)
                    }
                }
            },
            onRetry = { reloadKey++ },
            onClose = onDismissRequest,
        )
    }
}
