package com.winlator.cmod.shared.ui.dialog

import android.app.Activity
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.winlator.cmod.R
import com.winlator.cmod.shared.theme.WinNativeTheme
import java.text.NumberFormat

// Shared by the Java launch dialog and the retro import screen.
class PreloaderDialogState {
    val text = mutableStateOf("")
    val isIndeterminate = mutableStateOf(true)
    val progress = mutableIntStateOf(0)
    val title = mutableStateOf("")
    val badge = mutableStateOf("")
    val subtitle = mutableStateOf("")
    val artwork = mutableStateOf<Bitmap?>(null)
    val graphicsAttempt = mutableStateOf("")
    val cancelAction = mutableStateOf<Runnable?>(null)

    fun setAutomaticGraphics(description: String, cancel: Runnable?) {
        graphicsAttempt.value = description
        cancelAction.value = cancel
    }

    fun cancel() {
        val action = cancelAction.value ?: return
        cancelAction.value = null
        action.run()
    }

    /** A phase without measured progress must not inherit the previous phase's percentage. */
    fun setStep(value: String) {
        text.value = value
        isIndeterminate.value = true
        progress.intValue = 0
    }

    fun setIndeterminate(value: Boolean) {
        isIndeterminate.value = value
    }

    fun setProgress(value: Int) {
        progress.intValue = value.coerceIn(0, 100)
    }

    fun setTitle(value: String) {
        title.value = value
    }

    fun setBadge(value: String) {
        badge.value = value
    }

    fun setSubtitle(value: String) {
        subtitle.value = value
    }
}

private fun badgeStringRes(value: String): Int? =
    when (value.uppercase()) {
        "STEAM" -> R.string.preloader_platform_steam
        "EPIC" -> R.string.preloader_platform_epic
        "GOG" -> R.string.preloader_platform_gog
        "CUSTOM" -> R.string.preloader_platform_custom
        else -> null
    }

@Composable
fun PreloaderDialogContent(state: PreloaderDialogState) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.surface, colors.background)))
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val wide = maxWidth >= 600.dp && maxWidth > maxHeight
        // Keep the actual phase beside the cover on a landscape phone. Stacking all the
        // artwork above it hid Cloud/launch messages below the fold on short displays.
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(if (wide) 20.dp else 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (wide) {
                Row(
                    Modifier.widthIn(max = 1040.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                ) {
                    PreloaderGameInfo(state, compact = true, modifier = Modifier.weight(1f))
                    PreloaderPhase(state, Modifier.weight(1f))
                }
            } else {
                Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                    PreloaderGameInfo(state, compact = false, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(24.dp))
                    PreloaderPhase(state, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun PreloaderGameInfo(state: PreloaderDialogState, compact: Boolean, modifier: Modifier) {
    val title by state.title
    val badge by state.badge
    val subtitle by state.subtitle
    val artwork by state.artwork
    val colors = MaterialTheme.colorScheme
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.common_ui_app_name),
            style = MaterialTheme.typography.labelLarge,
            color = colors.primary,
            letterSpacing = 3.sp,
        )
        Spacer(Modifier.height(if (compact) 16.dp else 24.dp))
        artwork?.let { cover ->
            Image(
                bitmap = cover.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(if (compact) 80.dp else 112.dp).clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Fit,
            )
            Spacer(Modifier.height(if (compact) 12.dp else 20.dp))
        }
        Text(
            text = title.ifBlank { stringResource(R.string.preloader_loading_title) },
            modifier = Modifier.fillMaxWidth().semantics { heading() },
            style = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = colors.onBackground,
            textAlign = TextAlign.Center,
        )
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        badgeStringRes(badge)?.let { label ->
            Spacer(Modifier.height(16.dp))
            Surface(shape = RoundedCornerShape(6.dp), color = colors.surfaceVariant) {
                Text(
                    text = stringResource(label),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurface,
                )
            }
        }
    }
}

@Composable
private fun PreloaderPhase(state: PreloaderDialogState, modifier: Modifier) {
    val text by state.text
    val indeterminate by state.isIndeterminate
    val progress = state.progress.intValue.coerceIn(0, 100)
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = colors.background,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.preloader_current_step),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
            Text(
                text = text.ifBlank { stringResource(R.string.preloader_initializing) },
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleMedium,
                color = colors.onBackground,
            )
            if (indeterminate) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    trackColor = colors.surfaceVariant,
                )
            } else {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    trackColor = colors.surfaceVariant,
                )
                Text(
                    text = NumberFormat.getPercentInstance().format(progress / 100.0),
                    // The progress indicator already exposes the value to accessibility services.
                    modifier = Modifier.align(Alignment.End).clearAndSetSemantics {},
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                )
            }
            if (state.graphicsAttempt.value.isNotBlank()) {
                Text(state.graphicsAttempt.value, style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
            }
            if (state.cancelAction.value != null) {
                OutlinedButton(onClick = state::cancel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.vapor_graphics_cancel))
                }
            }
        }
    }
}

// Java bridge, retaining the dialog's existing lifecycle owner.
fun setupPreloaderComposeView(composeView: ComposeView, state: PreloaderDialogState, activity: Activity) {
    if (activity is LifecycleOwner) composeView.setViewTreeLifecycleOwner(activity)
    if (activity is SavedStateRegistryOwner) composeView.setViewTreeSavedStateRegistryOwner(activity)
    composeView.setContent { WinNativeTheme { PreloaderDialogContent(state) } }
}
