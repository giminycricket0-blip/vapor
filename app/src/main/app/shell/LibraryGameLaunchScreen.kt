package com.winlator.cmod.app.shell

import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.winlator.cmod.R
import com.winlator.cmod.feature.library.LibraryStorageMove
import com.winlator.cmod.shared.ui.layout.screenWidthDp
import androidx.compose.runtime.CompositionLocalProvider
import com.winlator.cmod.shared.ui.focus.controllerFocusGlow
import com.winlator.cmod.shared.ui.focus.controllerMenuInput
import com.winlator.cmod.shared.ui.outlinedSwitchColors
import com.winlator.cmod.shared.ui.nav.DialogPaneNav
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.PaneNavRegistry
import com.winlator.cmod.shared.ui.nav.paneNavItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LaunchBlack = Color.Black
private val LaunchCard = CardDark
internal val LaunchAccent = Accent
private val LaunchAccentGlow = Color(0xFF58A6FF)
private val LaunchTextPrimary = TextPrimary
private val LaunchTextSecondary = TextSecondary
private val LaunchDanger = Color(0xFFFF6B6B)

internal data class LaunchStoreOption(
    val id: String,
    val label: String,
)

@Composable
internal fun LibraryGameLaunchScreen(
    appName: String,
    subtitle: String,
    sourceLabel: String,
    heroImageUrl: Any?,
    customHeroImageCacheKey: String?,
    releaseDateEpochSeconds: Long,
    totalPlaytimeMillis: Long,
    playCount: Int,
    lastPlayedMillis: Long,
    installSizeText: String?,
    removalKeepsFiles: Boolean,
    isRetro: Boolean = false,
    showBootToDesktop: Boolean = !isRetro,
    showSaveTransfer: Boolean = false,
    hasPinnedShortcut: Boolean,
    steamMenuEnabled: Boolean = false,
    areSteamActionsEnabled: Boolean = true,
    showVerifyFiles: Boolean = true,
    showCheckForUpdate: Boolean = true,
    showWorkshop: Boolean = true,
    playEnabled: Boolean = true,
    playDisabledLabel: String? = null,
    uninstallEnabled: Boolean = true,
    /**
     * An alternative engine this particular game can be played with, offered
     * right above Play because it changes what Play does. Absent (and the row
     * not drawn at all) for every game that has no such choice, which is all
     * but a handful.
     */
    altEngineLabel: String? = null,
    altEngineEnabled: Boolean = false,
    onAltEngineChange: ((Boolean) -> Unit)? = null,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onSettings: () -> Unit,
    onBootToDesktop: () -> Unit,
    onAchievements: (() -> Unit)? = null,
    onCheats: (() -> Unit)? = null,
    cheatsEnabled: Boolean = true,
    onShortcut: () -> Unit,
    onCloudSaves: () -> Unit,
    onSaveTransfer: (() -> Unit)? = null,
    onUninstall: () -> Unit,
    onVerifyFiles: () -> Unit = {},
    onCheckForUpdate: () -> Unit = {},
    onWorkshop: () -> Unit = {},
    /**
     * Where this game would go if moved, or null when there is nowhere to move it. Drives the one
     * move entry, which only ever offers the other location.
     */
    moveTarget: LibraryStorageMove.Target? = null,
    onMoveGame: () -> Unit = {},
    branches: List<StoreBranchOption> = emptyList(),
    selectedBranchId: String = "",
    isBranchSelectionEnabled: Boolean = true,
    onSelectBranch: (String) -> Unit = {},
    storeOptions: List<LaunchStoreOption> = emptyList(),
    selectedStoreId: String = "",
    onSelectStore: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var uninstallMenuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(uninstallEnabled) {
        if (!uninstallEnabled) uninstallMenuOpen = false
    }
    val saveTransferVisible = showSaveTransfer && onSaveTransfer != null
    val bootVisible = showBootToDesktop
    val playFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current.inputMode
    LaunchedEffect(inputMode) {
        if (inputMode == InputMode.Keyboard) {
            if (playEnabled) playFocus.requestFocus() else settingsFocus.requestFocus()
        }
    }
    LaunchScreenCutoutMode()

    BoxWithConstraints(
        Modifier.fillMaxSize().background(BgDark).controllerMenuInput(onDismiss = onBack),
    ) {
        val edgePadding = 20.dp
        val bottomPadding = 20.dp
        val portraitHero = maxWidth < 700.dp
        val actionWidth = 360.dp
        val playHeight = 56.dp
        val contentGap = 18.dp

        if (heroImageUrl != null) {
            val heroRequest =
                remember(heroImageUrl, customHeroImageCacheKey, context) {
                    ImageRequest
                        .Builder(context)
                        .data(heroImageUrl)
                        .apply {
                            if (customHeroImageCacheKey != null) {
                                memoryCacheKey(customHeroImageCacheKey)
                                diskCacheKey(customHeroImageCacheKey)
                            }
                        }.crossfade(150)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build()
                }
            AsyncImage(
                model = heroRequest,
                contentDescription = "$appName artwork",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(LaunchAccent.copy(alpha = 0.34f), LaunchCard, LaunchBlack),
                            radius = 980f,
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.SportsEsports,
                    contentDescription = null,
                    tint = LaunchTextPrimary.copy(alpha = 0.18f),
                    modifier = Modifier.size(132.dp),
                )
            }
        }

        // The horizontal scrim is tuned for the landscape left-hand content column. In
        // portrait the content spans the full width, so its right-hand text would sit over
        // the transparent band and lose contrast; darken from the bottom there instead.
        val heroScrimStops =
            arrayOf(
                0.0f to LaunchBlack.copy(alpha = 0.9f),
                0.36f to LaunchBlack.copy(alpha = 0.58f),
                0.72f to LaunchBlack.copy(alpha = 0.18f),
                1.0f to LaunchBlack.copy(alpha = 0.62f),
            )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    if (portraitHero) {
                        Brush.verticalGradient(
                            colorStops =
                                arrayOf(
                                    0.0f to LaunchBlack.copy(alpha = 0.18f),
                                    0.45f to LaunchBlack.copy(alpha = 0.58f),
                                    1.0f to LaunchBlack.copy(alpha = 0.9f),
                                ),
                        )
                    } else {
                        Brush.horizontalGradient(colorStops = heroScrimStops)
                    },
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops =
                            arrayOf(
                                0.0f to LaunchBlack.copy(alpha = 0.54f),
                                0.36f to Color.Transparent,
                                0.72f to LaunchBlack.copy(alpha = 0.32f),
                                1.0f to LaunchBlack.copy(alpha = 0.94f),
                            ),
                    ),
                ),
        )

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = edgePadding, top = 12.dp, end = edgePadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier =
                        Modifier
                            .size(48.dp)
                            .controllerFocusGlow(cornerRadius = 24.dp)
                            .clip(CircleShape)
                            .background(LaunchBlack.copy(alpha = 0.5f))
                            .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.common_ui_back),
                        tint = LaunchTextPrimary,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (branches.size > 1) {
                        LaunchBranchTag(
                            branches = branches,
                            selectedBranchId = selectedBranchId,
                            enabled = isBranchSelectionEnabled,
                            onSelectBranch = onSelectBranch,
                        )
                    }
                    Surface(
                        color = Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                    ) {
                        Text(
                            sourceLabel.uppercase(),
                            modifier = Modifier.widthIn(max = 132.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                            color = LaunchTextSecondary,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth()
                    .padding(start = edgePadding, top = 24.dp, end = edgePadding, bottom = bottomPadding),
            ) {
                val availableBodyHeight = maxHeight
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .heightIn(min = availableBodyHeight),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    val gameHeading: @Composable () -> Unit = {
                        Column(
                            modifier = Modifier.widthIn(max = 640.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                appName,
                                style = MaterialTheme.typography.headlineMedium,
                                color = LaunchTextPrimary,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (subtitle.isNotBlank()) {
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = LaunchTextPrimary.copy(alpha = 0.72f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (releaseDateEpochSeconds > 0L) {
                                val releaseDateText = remember(releaseDateEpochSeconds) { formatReleaseDate(releaseDateEpochSeconds) }
                                Text(
                                    releaseDateText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LaunchTextPrimary.copy(alpha = 0.6f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    val statChips: @Composable (Modifier) -> Unit = { statsModifier ->
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = statsModifier,
                        ) {
                            if (totalPlaytimeMillis > 0L) {
                                val playtimeText = remember(totalPlaytimeMillis) { formatLibraryPlaytime(totalPlaytimeMillis) }
                                GameStatChip(
                                    icon = Icons.Outlined.Schedule,
                                    label = stringResource(R.string.library_games_playtime),
                                    value = playtimeText,
                                )
                            }
                            if (playCount > 0) {
                                GameStatChip(
                                    icon = Icons.Outlined.SportsEsports,
                                    label = stringResource(R.string.library_games_plays),
                                    value = playCount.toString(),
                                )
                            }
                            if (lastPlayedMillis > 0L) {
                                val lastPlayedText = remember(lastPlayedMillis) { formatLibraryLastPlayed(lastPlayedMillis) }
                                GameStatChip(
                                    icon = Icons.Outlined.History,
                                    label = stringResource(R.string.library_games_last_played),
                                    value = lastPlayedText,
                                )
                            }
                            if (installSizeText != null) {
                                GameStatChip(
                                    icon = Icons.Outlined.Storage,
                                    label = stringResource(R.string.common_ui_size),
                                    value = installSizeText,
                                )
                            }
                        }
                    }

                    val actionBlock: @Composable (Modifier) -> Unit = { actionsModifier ->
                        Column(
                            modifier = actionsModifier,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (altEngineLabel != null && onAltEngineChange != null) {
                                LaunchAltEngineToggle(
                                    label = altEngineLabel,
                                    checked = altEngineEnabled,
                                    // In portrait the action block already fills the width; pinning the
                                    // toggle to actionWidth clipped it on narrow phones.
                                    modifier =
                                        if (portraitHero) Modifier.fillMaxWidth() else Modifier.width(actionWidth),
                                    onCheckedChange = onAltEngineChange,
                                )
                            }

                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val stacked = maxWidth < 280.dp * LocalDensity.current.fontScale
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    maxItemsInEachRow = if (stacked) 1 else 2,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    LaunchPlayButton(
                                        height = playHeight,
                                        enabled = playEnabled,
                                        disabledLabel = playDisabledLabel,
                                        onClick = onPlay,
                                        modifier = Modifier.weight(1f).focusRequester(playFocus),
                                    )
                                    LaunchManageMenu(
                                        steamMenuEnabled = steamMenuEnabled,
                                        areSteamActionsEnabled = areSteamActionsEnabled,
                                        showVerifyFiles = showVerifyFiles,
                                        showCheckForUpdate = showCheckForUpdate,
                                        showWorkshop = showWorkshop,
                                        showBootToDesktop = bootVisible,
                                        hasPinnedShortcut = hasPinnedShortcut,
                                        uninstallEnabled = uninstallEnabled,
                                        removalKeepsFiles = removalKeepsFiles,
                                        moveTarget = moveTarget,
                                        storeOptions = storeOptions,
                                        selectedStoreId = selectedStoreId,
                                        cheatsEnabled = cheatsEnabled,
                                        onSelectStore = onSelectStore,
                                        onVerifyFiles = onVerifyFiles,
                                        onCheckForUpdate = onCheckForUpdate,
                                        onWorkshop = onWorkshop,
                                        onBootToDesktop = onBootToDesktop,
                                        onShortcut = onShortcut,
                                        onMoveGame = onMoveGame,
                                        onCheats = onCheats,
                                        onSaveTransfer = if (saveTransferVisible) onSaveTransfer else null,
                                        onUninstall = { if (uninstallEnabled) uninstallMenuOpen = true },
                                        modifier = if (stacked) Modifier.fillMaxWidth() else Modifier,
                                    )
                                }
                            }

                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val columns = (maxWidth / (144.dp * LocalDensity.current.fontScale)).toInt().coerceIn(1, 2)
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    maxItemsInEachRow = columns,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    LaunchIconActionButton(
                                        icon = Icons.Outlined.Settings,
                                        contentDescription = stringResource(R.string.common_ui_settings),
                                        modifier = Modifier.weight(1f).focusRequester(settingsFocus),
                                        onClick = onSettings,
                                    )
                                    LaunchIconActionButton(
                                        icon = Icons.Outlined.CloudSync,
                                        contentDescription = stringResource(R.string.cloud_saves_title),
                                        modifier = Modifier.weight(1f),
                                        onClick = onCloudSaves,
                                    )
                                    if (onAchievements != null) {
                                        LaunchIconActionButton(
                                            icon = Icons.Outlined.EmojiEvents,
                                            contentDescription = stringResource(R.string.steam_achievements_title),
                                            modifier = Modifier.weight(1f),
                                            onClick = onAchievements,
                                        )
                                    }
                                }
                            }
                            if (!uninstallEnabled) {
                                Text(
                                    stringResource(R.string.vapor_game_save_sync_pending),
                                    color = LaunchTextSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }

                    if (portraitHero) {
                        gameHeading()
                        Spacer(Modifier.height(24.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(contentGap),
                        ) {
                            statChips(Modifier.fillMaxWidth())
                            actionBlock(Modifier.fillMaxWidth())
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = availableBodyHeight),
                            horizontalArrangement = Arrangement.spacedBy(contentGap),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f).heightIn(min = availableBodyHeight),
                                verticalArrangement = Arrangement.SpaceBetween,
                            ) {
                                gameHeading()
                                Spacer(Modifier.height(24.dp))
                                statChips(Modifier.fillMaxWidth())
                            }
                            actionBlock(Modifier.width(actionWidth))
                        }
                    }
                }
            }
        }

        LaunchUninstallMenu(
            expanded = uninstallMenuOpen && uninstallEnabled,
            appName = appName,
            removalKeepsFiles = removalKeepsFiles,
            onDismissRequest = { uninstallMenuOpen = false },
            onConfirm = {
                uninstallMenuOpen = false
                if (uninstallEnabled) onUninstall()
            },
        )
    }
}

@Composable
private fun LaunchScreenCutoutMode() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
            ?: return@DisposableEffect onDispose { }

        val originalCutoutMode = window.attributes.layoutInDisplayCutoutMode
        val originalWidth = window.attributes.width
        val originalHeight = window.attributes.height
        val originalNavigationBarColor = window.navigationBarColor
        val originalNavBarContrastEnforced =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced
            } else {
                false
            }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS,
        )
        // FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS is required for navigationBarColor; Dialog windows don't set it by default.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
        )
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = launchScreenCutoutMode()
        }
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        onDispose {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = originalCutoutMode
            }
            window.navigationBarColor = originalNavigationBarColor
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = originalNavBarContrastEnforced
            }
            window.clearFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            )
            WindowCompat.setDecorFitsSystemWindows(window, true)
            window.setLayout(originalWidth, originalHeight)
        }
    }
}

private fun launchScreenCutoutMode(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    } else {
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

@Composable
private fun LaunchUninstallMenu(
    expanded: Boolean,
    appName: String,
    removalKeepsFiles: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title =
        stringResource(
            if (removalKeepsFiles) R.string.library_games_remove_game else R.string.library_games_uninstall_game,
        )
    val confirmLabel =
        stringResource(if (removalKeepsFiles) R.string.common_ui_remove else R.string.common_ui_uninstall)
    val message =
        stringResource(
            if (removalKeepsFiles) R.string.library_games_remove_confirm else R.string.library_games_uninstall_confirm,
            appName,
        )

    LaunchDangerConfirmDialog(
        visible = expanded,
        title = title,
        message = message,
        confirmLabel = confirmLabel,
        onDismissRequest = onDismissRequest,
        onConfirm = onConfirm,
        icon = Icons.Outlined.Delete,
        cancelColor = LaunchAccent,
    )
}

@Composable
internal fun LaunchDangerConfirmMenu(
    expanded: Boolean,
    title: String,
    message: String,
    confirmLabel: String,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    icon: ImageVector = Icons.Outlined.Delete,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        offset = DpOffset(x = 0.dp, y = (-56).dp),
        modifier = Modifier.width(minOf(286.dp, screenWidthDp() - 32.dp)),
        shape = RoundedCornerShape(12.dp),
        containerColor = LaunchCard,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
        tonalElevation = 0.dp,
        shadowElevation = 14.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = LaunchDanger,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    title,
                    color = LaunchTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                message,
                color = LaunchTextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LaunchMenuTextAction(
                    label = stringResource(R.string.common_ui_cancel),
                    textColor = LaunchTextSecondary,
                    onClick = onDismissRequest,
                )
                LaunchMenuTextAction(
                    label = confirmLabel,
                    textColor = LaunchDanger,
                    onClick = onConfirm,
                )
            }
        }
    }
}

@Composable
internal fun LaunchDangerConfirmDialog(
    visible: Boolean,
    title: String,
    message: String,
    confirmLabel: String,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    icon: ImageVector = Icons.Outlined.Warning,
    titleTextAlign: TextAlign = TextAlign.Start,
    messageTextAlign: TextAlign = TextAlign.Start,
    accentColor: Color = LaunchDanger,
    cancelColor: Color = LaunchTextSecondary,
) {
    if (!visible) return

    val registry = remember { PaneNavRegistry() }
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        CompositionLocalProvider(LocalPaneNav provides registry) {
            DialogPaneNav(registry, onDismiss = onDismissRequest)
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(LaunchBlack.copy(alpha = 0.46f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismissRequest,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier =
                        Modifier
                            .width(minOf(286.dp, screenWidthDp() - 32.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { },
                            ),
                    shape = RoundedCornerShape(12.dp),
                    color = LaunchCard,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                    shadowElevation = 14.dp,
                    tonalElevation = 0.dp,
                ) {
                    LaunchDangerConfirmContent(
                        title = title,
                        message = message,
                        confirmLabel = confirmLabel,
                        onDismissRequest = onDismissRequest,
                        onConfirm = onConfirm,
                        icon = icon,
                        titleTextAlign = titleTextAlign,
                        messageTextAlign = messageTextAlign,
                        accentColor = accentColor,
                        cancelColor = cancelColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun LaunchDangerConfirmContent(
    title: String,
    message: String,
    confirmLabel: String,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    icon: ImageVector,
    titleTextAlign: TextAlign,
    messageTextAlign: TextAlign,
    accentColor: Color,
    cancelColor: Color,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (titleTextAlign == TextAlign.Center) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier =
                        Modifier
                            .align(Alignment.CenterStart)
                            .size(18.dp),
                )
                Text(
                    title,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 28.dp),
                    color = LaunchTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    title,
                    color = LaunchTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            message,
            modifier = Modifier.fillMaxWidth(),
            color = LaunchTextSecondary,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            textAlign = messageTextAlign,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LaunchMenuTextAction(
                label = stringResource(R.string.common_ui_cancel),
                textColor = cancelColor,
                onClick = onDismissRequest,
                modifier = Modifier.paneNavItem(onActivate = onDismissRequest),
            )
            LaunchMenuTextAction(
                label = confirmLabel,
                textColor = accentColor,
                onClick = onConfirm,
                modifier = Modifier.paneNavItem(onActivate = onConfirm, isEntry = true),
            )
        }
    }
}

@Composable
private fun LaunchMenuTextAction(
    label: String,
    textColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .controllerFocusGlow(cornerRadius = 8.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun LaunchManageMenu(
    steamMenuEnabled: Boolean,
    areSteamActionsEnabled: Boolean,
    showVerifyFiles: Boolean,
    showCheckForUpdate: Boolean,
    showWorkshop: Boolean,
    showBootToDesktop: Boolean,
    hasPinnedShortcut: Boolean,
    uninstallEnabled: Boolean,
    removalKeepsFiles: Boolean,
    moveTarget: LibraryStorageMove.Target?,
    storeOptions: List<LaunchStoreOption>,
    selectedStoreId: String,
    cheatsEnabled: Boolean,
    onSelectStore: (String) -> Unit,
    onVerifyFiles: () -> Unit,
    onCheckForUpdate: () -> Unit,
    onWorkshop: () -> Unit,
    onBootToDesktop: () -> Unit,
    onShortcut: () -> Unit,
    onMoveGame: () -> Unit,
    onCheats: (() -> Unit)?,
    onSaveTransfer: (() -> Unit)?,
    onUninstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var storeMenuOpen by remember { mutableStateOf(false) }
    val anchorFocus = remember { FocusRequester() }
    val closeMenu: () -> Unit = {
        menuOpen = false
        storeMenuOpen = false
        anchorFocus.requestFocus()
    }
    Box(modifier, propagateMinConstraints = true) {
        LaunchIconActionButton(
            icon = Icons.Outlined.MoreHoriz,
            contentDescription = stringResource(R.string.vapor_game_manage),
            modifier = Modifier.heightIn(min = 56.dp).focusRequester(anchorFocus),
            onClick = { anchorFocus.requestFocus(); menuOpen = true },
        )
        LaunchSourceActionPopup(expanded = menuOpen, onDismissRequest = closeMenu) {
            if (steamMenuEnabled && showCheckForUpdate) {
                LaunchSourceMenuItem(
                    icon = Icons.Outlined.Refresh,
                    label = stringResource(R.string.store_game_check_for_update),
                    enabled = areSteamActionsEnabled,
                ) { menuOpen = false; onCheckForUpdate() }
            }
            if (steamMenuEnabled && showVerifyFiles) {
                LaunchSourceMenuItem(
                    icon = Icons.AutoMirrored.Outlined.FactCheck,
                    label = stringResource(R.string.store_game_verify_files),
                    enabled = areSteamActionsEnabled,
                ) { menuOpen = false; onVerifyFiles() }
            }
            if (steamMenuEnabled && showWorkshop) {
                LaunchSourceMenuItem(
                    icon = Icons.Outlined.Construction,
                    label = stringResource(R.string.store_game_workshop),
                    enabled = areSteamActionsEnabled,
                ) { menuOpen = false; onWorkshop() }
            }
            if (onCheats != null) {
                LaunchSourceMenuItem(
                    icon = Icons.Outlined.Bolt,
                    label = stringResource(R.string.retro_cheats_title),
                    enabled = cheatsEnabled,
                ) { menuOpen = false; onCheats() }
            }
            LaunchSourceMenuItem(
                icon = Icons.Outlined.Home,
                label = stringResource(if (hasPinnedShortcut) R.string.vapor_game_remove_home_shortcut else R.string.shortcuts_list_add_to_home_screen),
            ) { menuOpen = false; onShortcut() }
            if (onSaveTransfer != null) {
                LaunchSourceMenuItem(
                    icon = Icons.Outlined.SaveAlt,
                    label = stringResource(R.string.retro_save_transfer_title),
                ) { menuOpen = false; onSaveTransfer() }
            }
            if (showBootToDesktop) {
                LaunchSourceMenuItem(
                    icon = Icons.Outlined.DesktopWindows,
                    label = stringResource(R.string.hero_boot_to_desktop_title),
                ) { menuOpen = false; onBootToDesktop() }
            }
            if (moveTarget != null) {
                LaunchSourceMenuItem(
                    icon = Icons.Outlined.DriveFileMove,
                    label = stringResource(
                        if (moveTarget == LibraryStorageMove.Target.APP_STORAGE) R.string.library_games_move_to_app_storage_title
                        else R.string.library_games_move_to_download_folder_title,
                    ),
                    enabled = areSteamActionsEnabled && uninstallEnabled,
                ) { menuOpen = false; onMoveGame() }
            }
            LaunchSourceMenuItem(
                icon = Icons.Outlined.Storefront,
                label = stringResource(R.string.library_games_store_change),
            ) { menuOpen = false; storeMenuOpen = true }
            androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.14f))
            LaunchSourceMenuItem(
                icon = Icons.Outlined.Delete,
                label = stringResource(
                    if (!uninstallEnabled) R.string.library_finishing_exit_cloud_sync
                    else if (removalKeepsFiles) R.string.common_ui_remove
                    else R.string.common_ui_uninstall,
                ),
                enabled = uninstallEnabled,
                tint = LaunchDanger,
            ) { closeMenu(); if (uninstallEnabled) onUninstall() }
        }
        LaunchSourceActionPopup(expanded = storeMenuOpen, onDismissRequest = closeMenu) {
            LaunchSourceMenuHeader(stringResource(R.string.library_games_store_switch_label))
            if (storeOptions.isEmpty()) {
                LaunchSourceMenuItem(
                    icon = Icons.Outlined.Storefront,
                    label = stringResource(R.string.library_games_store_none_owned),
                    enabled = false,
                ) {}
            }
            storeOptions.forEach { option ->
                LaunchStoreMenuItem(label = option.label, selected = option.id == selectedStoreId) {
                    closeMenu()
                    if (option.id != selectedStoreId) onSelectStore(option.id)
                }
            }
        }
    }
}

@Composable
private fun LaunchBranchTag(
    branches: List<StoreBranchOption>,
    selectedBranchId: String,
    enabled: Boolean,
    onSelectBranch: (String) -> Unit,
) {
    val selected =
        branches.firstOrNull { it.id.equals(selectedBranchId, ignoreCase = true) }
            ?: branches.firstOrNull()
            ?: return
    var menuOpen by remember { mutableStateOf(false) }
    val contentColor = if (enabled) LaunchTextPrimary else LaunchTextPrimary.copy(alpha = 0.45f)

    Box {
        Surface(
            color = Color.White.copy(alpha = 0.1f),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
            modifier =
                Modifier
                    .heightIn(min = 48.dp)
                    .controllerFocusGlow()
                    .clickable(enabled = enabled, role = Role.Button) { menuOpen = true },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Outlined.Layers,
                    contentDescription = null,
                    tint = if (enabled) LaunchAccentGlow else LaunchTextSecondary,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    selected.id.uppercase(),
                    color = contentColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).widthIn(max = 132.dp),
                )
                if (enabled) {
                    Icon(
                        Icons.Outlined.ArrowDropDown,
                        contentDescription = stringResource(R.string.store_game_branch_label),
                        tint = contentColor,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        if (enabled) {
            LaunchSourceActionPopup(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                Column(
                    modifier =
                        Modifier
                            .widthIn(min = 172.dp, max = 240.dp)
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                ) {
                    branches.forEach { branch ->
                        val isSelected = branch.id.equals(selected.id, ignoreCase = true)
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .controllerFocusGlow()
                                    .clickable(role = Role.Button) {
                                        menuOpen = false
                                        if (!isSelected) onSelectBranch(branch.id)
                                    }.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                if (isSelected) Icons.Outlined.CheckCircle else Icons.Outlined.Layers,
                                contentDescription = null,
                                tint = if (isSelected) LaunchAccentGlow else Color.White.copy(alpha = 0.55f),
                                modifier = Modifier.size(16.dp),
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(1.dp),
                            ) {
                                Text(
                                    branch.label,
                                    color = if (isSelected) LaunchTextPrimary else Color.White.copy(alpha = 0.86f),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (branch.buildId > 0L) {
                                    Text(
                                        stringResource(R.string.store_game_branch_build, branch.buildId.toString()),
                                        color = LaunchTextSecondary,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (branch.isInstalled) {
                                Icon(
                                    Icons.Outlined.Storage,
                                    contentDescription = null,
                                    tint = LaunchTextSecondary,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LaunchSourceActionPopup(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier.widthIn(min = 220.dp, max = 300.dp)
            .controllerMenuInput(onDismiss = onDismissRequest),
        shape = RoundedCornerShape(12.dp),
        containerColor = LaunchCard,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
        tonalElevation = 0.dp,
        shadowElevation = 16.dp,
    ) {
        content()
    }
}

@Composable
private fun LaunchSourceMenuHeader(label: String) {
    Text(
        label.uppercase(),
        color = LaunchTextSecondary,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun LaunchStoreMenuItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = if (selected) LaunchAccentGlow else Color.White
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .controllerFocusGlow()
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.Storefront,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(16.dp),
        )
        Text(
            label,
            color = contentColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun LaunchSourceMenuItem(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    val contentColor = if (enabled) tint else LaunchTextSecondary
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .controllerFocusGlow()
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(16.dp),
        )
        Text(
            label,
            color = contentColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun GameStatChip(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = LaunchBlack.copy(alpha = 0.44f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.11f)),
    ) {
        Row(
            modifier =
                Modifier.padding(
                    horizontal = 10.dp,
                    vertical = 8.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = LaunchAccentGlow)
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    label.uppercase(),
                    color = LaunchTextSecondary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    value,
                    color = LaunchTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The alternative-engine switch above Play.
 *
 * Deliberately the same width as the Play button and immediately above it: it
 * decides which engine Play will start, so it belongs in the reading path to
 * that button rather than buried in a settings pane. The state is the game's
 * own saved setting, so what it shows survives leaving the screen.
 */
@Composable
private fun LaunchAltEngineToggle(
    label: String,
    checked: Boolean,
    modifier: Modifier,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = outlinedSwitchColors(
                accentColor = LaunchAccent,
                textSecondaryColor = Color.White.copy(alpha = 0.55f),
            ),
        )
    }
}

@Composable
private fun LaunchPlayButton(
    height: Dp,
    enabled: Boolean = true,
    disabledLabel: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showStatus = !enabled && !disabledLabel.isNullOrBlank()
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(3.dp),
        colors = ButtonDefaults.buttonColors(containerColor = com.winlator.cmod.shared.theme.WinNativePlay, contentColor = Color.White),
        modifier = modifier.heightIn(min = height).controllerFocusGlow(cornerRadius = 3.dp),
    ) {
        Icon(if (showStatus) Icons.Outlined.Refresh else Icons.Outlined.PlayArrow,
            contentDescription = null, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (showStatus) disabledLabel!! else stringResource(R.string.library_games_play),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LaunchIconActionButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    tint: Color = Color.White,
    enabled: Boolean = true,
) {
    Surface(
        modifier =
            modifier
                .heightIn(min = 48.dp)
                .controllerFocusGlow(cornerRadius = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        color = LaunchBlack.copy(alpha = 0.46f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.18f)),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val contentTint = if (enabled) tint else LaunchTextSecondary
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = contentTint)
            Text(contentDescription, color = contentTint, style = MaterialTheme.typography.labelMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun formatReleaseDate(releaseDateEpochSeconds: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        .format(Date(releaseDateEpochSeconds * 1000L))

private fun formatLibraryPlaytime(playtimeMillis: Long): String {
    val totalMinutes = (playtimeMillis / 60000L).coerceAtLeast(1L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
        hours > 0L -> "${hours}h"
        else -> "${minutes}m"
    }
}

private fun formatLibraryLastPlayed(lastPlayedMillis: Long): String =
    SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(lastPlayedMillis))
