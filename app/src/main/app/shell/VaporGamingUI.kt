package com.winlator.cmod.app.shell

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.widget.TextClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.cmod.shared.ui.focus.controllerFocusGlow
import com.winlator.cmod.shared.ui.focus.controllerConfirmOnA
import com.winlator.cmod.shared.ui.focus.controllerMenuInput
import com.winlator.cmod.BuildConfig
import com.winlator.cmod.R
import com.winlator.cmod.shared.ui.branding.VaporBrandMark
import kotlin.math.roundToInt

/** Shared browsing-panel treatment; blur belongs to artwork behind these controls. */
internal fun Modifier.vaporGlass(shape: Shape = RoundedCornerShape(16.dp)): Modifier =
    clip(shape)
        .background(Brush.linearGradient(listOf(Color(0xE6293D54), Color(0xEB101D2A))))
        .border(1.dp, Brush.linearGradient(listOf(Color(0x3DFFFFFF), Color(0x0AFFFFFF))), shape)

@Composable
internal fun VaporClock() {
    // TextClock owns its receiver and stops updates when detached.
    AndroidView(factory = { context ->
        TextClock(context).apply {
            format12Hour = "h:mm a"
            format24Hour = "HH:mm"
            setTextColor(android.graphics.Color.rgb(173, 186, 202))
            textSize = 13f
        }
    }, modifier = Modifier.padding(horizontal = 12.dp))
}

/** Library tabs use the same compact, underlined navigation as Big Picture. */
@Composable
internal fun VaporLibraryTabs(installedOnly: Boolean, modifier: Modifier = Modifier, onSelect: (Boolean) -> Unit) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(false, true).forEach { installed ->
            val selected = installedOnly == installed
            val bringIntoView = remember { BringIntoViewRequester() }
            LaunchedEffect(selected) { if (selected) { withFrameNanos { }; bringIntoView.bringIntoView() } }
            Tab(selected = selected, onClick = { onSelect(installed) },
                selectedContentColor = TextPrimary, unselectedContentColor = TextSecondary,
                modifier = Modifier.width(IntrinsicSize.Max).widthIn(min = 88.dp).heightIn(min = 48.dp)
                    .bringIntoViewRequester(bringIntoView).controllerFocusGlow(cornerRadius = 50.dp)) {
                // Segment-style pill, matching the header's selected tab treatment.
                Text(stringResource(if (installed) R.string.common_ui_installed else R.string.vapor_library_all),
                    fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge,
                    maxLines = 1, modifier = Modifier.padding(vertical = 6.dp)
                        .background(if (selected) Color.White.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(50))
                        .border(1.dp, if (selected) Color.White.copy(alpha = 0.14f) else Color.Transparent, RoundedCornerShape(50))
                        .padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
internal fun VaporWelcome(
    signedIn: Boolean,
    installedOnly: Boolean,
    onShowAllGames: () -> Unit,
    onSteam: () -> Unit,
    onRefresh: () -> Unit,
    onAddGame: () -> Unit,
    home: Boolean = false,
) {
    val firstAction = remember { FocusRequester() }
    LaunchedEffect(installedOnly, signedIn) {
        if (com.winlator.cmod.runtime.input.ControllerHelper.isControllerConnected()) firstAction.requestFocus()
    }
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val reveal by animateFloatAsState(if (entered) 1f else 0f, tween(220), label = "welcomeReveal")
    BoxWithConstraints(Modifier.fillMaxSize().controllerConfirmOnA(), contentAlignment = Alignment.Center) {
        val compact = maxHeight < 400.dp
        val sideBySide = compact && maxWidth / LocalDensity.current.fontScale >= 680.dp
        Box(Modifier.matchParentSize().background(Brush.radialGradient(
            listOf(Accent.copy(alpha = 0.07f), BgDark))))
        val introduction: @Composable () -> Unit = {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (sideBySide) 8.dp else 16.dp)) {
                VaporBrandMark(Modifier.size(if (sideBySide) 40.dp else if (compact) 48.dp else 72.dp))
                Text(stringResource(if (installedOnly) R.string.vapor_library_no_installs else if (home) R.string.vapor_home_welcome else R.string.vapor_welcome_title),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    color = TextPrimary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Text(stringResource(when {
                    installedOnly -> R.string.vapor_library_install_hint
                    signedIn -> R.string.vapor_welcome_signed_in
                    else -> R.string.vapor_welcome_body
                }), color = TextSecondary, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth())
            }
        }
        val actions: @Composable () -> Unit = {
            Column(Modifier.widthIn(max = 360.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val primaryAction = when { installedOnly -> onShowAllGames; signedIn -> onRefresh; else -> onSteam }
                Button(onClick = primaryAction, shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .focusRequester(firstAction).controllerFocusGlow()) {
                    Text(stringResource(when {
                        installedOnly -> R.string.vapor_library_show_all
                        signedIn -> R.string.vapor_store_refresh_library
                        else -> R.string.vapor_connect_steam
                    }))
                }
                if (signedIn && !installedOnly) TextButton(onClick = onSteam,
                    modifier = Modifier.heightIn(min = 48.dp).controllerFocusGlow()) {
                    Text(stringResource(R.string.vapor_browse_store))
                }
                OutlinedButton(onClick = onAddGame, shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).controllerFocusGlow()) {
                    Icon(Icons.Outlined.SportsEsports, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.vapor_add_pc_game))
                }
            }
        }
        val contentModifier = Modifier.widthIn(max = if (sideBySide) 960.dp else 560.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = if (sideBySide) 16.dp else 24.dp)
            .graphicsLayer { alpha = reveal; translationY = (1f - reveal) * 12.dp.toPx() }
        if (sideBySide) {
            Row(contentModifier, verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Box(Modifier.weight(1f)) { introduction() }
                Box(Modifier.weight(0.8f), contentAlignment = Alignment.Center) { actions() }
            }
        } else {
            Column(contentModifier, horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                introduction()
                actions()
            }
        }
    }
}

@Composable
internal fun VaporQuickAccess(
    activity: Activity,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
    onLibraryOptions: () -> Unit,
) {
    val audio = remember(activity) { activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember(audio) { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
    val initialBrightness = activity.window.attributes.screenBrightness
    var brightness by remember {
        mutableFloatStateOf(if (initialBrightness >= 0) initialBrightness else
            (Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f))
    }
    val battery = remember { (activity.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }
    val closeFocus = remember { FocusRequester() }
    var entered by remember { mutableStateOf(false) }
    val reveal by animateFloatAsState(if (entered) 1f else 0f, tween(180), label = "quickAccessReveal")

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(Unit) { entered = true; closeFocus.requestFocus() }
        Box(Modifier.fillMaxSize().controllerMenuInput(onDismiss = onDismiss), contentAlignment = Alignment.CenterEnd) {
            Surface(color = Color.Transparent, modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().fillMaxHeight()
                .graphicsLayer { translationX = (1f - reveal) * size.width; alpha = reveal }
                .vaporGlass(RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp))) {
                Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        VaporBrandMark(Modifier.size(32.dp))
                        Text(stringResource(R.string.vapor_quick_access), color = TextPrimary,
                            style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismiss, modifier = Modifier.focusRequester(closeFocus).controllerFocusGlow(cornerRadius = 3.dp)) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.vapor_close))
                        }
                    }
                    HorizontalDivider(color = CardBorder)
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.VolumeUp, null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                            Text(stringResource(R.string.vapor_media_volume, (volume * 100 / maxVolume).roundToInt()),
                                style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                        }
                        VaporQuickSlider(value = volume, valueRange = 0f..maxVolume.toFloat(), steps = (maxVolume - 1).coerceAtLeast(0),
                            label = stringResource(R.string.vapor_media_volume, (volume * 100 / maxVolume).roundToInt()),
                            enabled = !audio.isVolumeFixed,
                            onValueChange = {
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, it.roundToInt(), 0)
                                volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                            })
                    }
                    HorizontalDivider(color = CardBorder)
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.BrightnessMedium, null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                            Text(stringResource(R.string.vapor_app_brightness, (brightness * 100).roundToInt()),
                                style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                        }
                        VaporQuickSlider(value = brightness, valueRange = 0.05f..1f,
                            label = stringResource(R.string.vapor_app_brightness, (brightness * 100).roundToInt()),
                            onValueChange = {
                            brightness = it
                            activity.window.attributes = activity.window.attributes.apply { screenBrightness = it }
                        })
                        Text(stringResource(R.string.vapor_brightness_note), color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider(color = CardBorder)
                    TextButton(onClick = onLibraryOptions, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).controllerFocusGlow(cornerRadius = 3.dp),
                        shape = RoundedCornerShape(3.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(Icons.Outlined.LibraryBooks, null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.vapor_library_options), color = TextPrimary, modifier = Modifier.weight(1f))
                    }
                    TextButton(onClick = {
                        activity.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)); onDismiss()
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).controllerFocusGlow(cornerRadius = 3.dp),
                        shape = RoundedCornerShape(3.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(Icons.Outlined.Wifi, null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.vapor_network_settings), color = TextPrimary, modifier = Modifier.weight(1f))
                    }
                    TextButton(onClick = onSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).controllerFocusGlow(cornerRadius = 3.dp),
                        shape = RoundedCornerShape(3.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(Icons.Outlined.Tune, null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.vapor_all_settings), color = TextPrimary, modifier = Modifier.weight(1f))
                    }
                    HorizontalDivider(color = CardBorder)
                    Text(if (battery in 0..100) stringResource(R.string.vapor_battery_status, battery, Build.VERSION.RELEASE)
                        else "Android ${Build.VERSION.RELEASE}", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    Text("Vapor ${BuildConfig.VERSION_NAME}", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** Keep Material's input and accessibility behavior while matching the thin Quick Access controls. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaporQuickSlider(
    value: Float,
    label: String,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    enabled: Boolean = true,
) {
    val colors = SliderDefaults.colors(
        thumbColor = TextPrimary, activeTrackColor = Accent, inactiveTrackColor = SurfaceDark,
        disabledActiveTrackColor = Accent.copy(alpha = 0.4f),
        disabledInactiveTrackColor = SurfaceDark.copy(alpha = 0.4f),
    )
    Slider(value = value, onValueChange = onValueChange, valueRange = valueRange,
        steps = steps, enabled = enabled, colors = colors,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .semantics { contentDescription = label }.controllerFocusGlow(cornerRadius = 3.dp),
        thumb = {
            Box(Modifier.size(20.dp).background(TextPrimary.copy(alpha = if (enabled) 1f else 0.4f), CircleShape))
        },
        track = { state ->
            SliderDefaults.Track(sliderState = state, modifier = Modifier.height(4.dp),
                enabled = enabled, colors = colors, drawStopIndicator = null, drawTick = { _, _ -> },
                thumbTrackGapSize = 0.dp, trackInsideCornerSize = 0.dp)
        })
}
