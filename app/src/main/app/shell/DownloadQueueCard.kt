package com.winlator.cmod.app.shell

import android.text.format.DateUtils
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import com.winlator.cmod.R
import com.winlator.cmod.feature.stores.steam.data.DownloadInfo
import com.winlator.cmod.feature.stores.steam.enums.DownloadPhase
import com.winlator.cmod.shared.ui.focus.controllerConfirmOnA
import com.winlator.cmod.shared.io.StorageUtils
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The existing download owns progress and transfers; this card owns presentation only. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DownloadQueueCard(
    title: String,
    image: Any?,
    store: String,
    info: DownloadInfo,
    animationsActive: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onOpenGame: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val status by info.getStatusFlow().collectAsStateWithLifecycle()
    val message by info.getStatusMessageFlow().collectAsStateWithLifecycle()
    val currentFile by info.getCurrentFileNameFlow().collectAsStateWithLifecycle()
    var progress by remember(info) { mutableFloatStateOf(info.getProgress()) }
    var speed by remember(info) { mutableLongStateOf(0L) }
    var eta by remember(info) { mutableStateOf<Long?>(null) }
    var confirmCancel by remember(info) { mutableStateOf(false) }
    LaunchedEffect(info, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val listener: (Float) -> Unit = { progress = it }
            info.addProgressListener(listener)
            progress = info.getProgress()
            try { awaitCancellation() } finally { info.removeProgressListener(listener) }
        }
    }
    LaunchedEffect(info, status, lifecycle) {
        speed = 0L
        eta = null
        if (status == DownloadPhase.DOWNLOADING) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                speed = info.getCurrentDownloadSpeed() ?: 0L
                eta = info.getEstimatedTimeRemaining()
                delay(1000)
            }
        }
    }
    LaunchedEffect(status) {
        if (status == DownloadPhase.COMPLETE || status == DownloadPhase.CANCELLED) confirmCancel = false
    }
    val value by animateFloatAsState(if (status == DownloadPhase.COMPLETE) 1f else progress.coerceIn(0f, 1f),
        tween(if (animationsActive) 200 else 0), label = "downloadProgress")
    val (bytes, total) = info.getDisplayBytesProgress()
    val finished = status == DownloadPhase.COMPLETE || status == DownloadPhase.CANCELLED
    val resumable = status == DownloadPhase.PAUSED || status == DownloadPhase.FAILED
    val processing = status in setOf(DownloadPhase.PREPARING, DownloadPhase.VERIFYING,
        DownloadPhase.PATCHING, DownloadPhase.APPLYING_DATA, DownloadPhase.FINALIZING, DownloadPhase.UNPACKING)
    val knownProgress = total > 0 || progress > 0f
    val indeterminate = (processing && (progress <= 0f || progress >= 1f)) ||
        (status == DownloadPhase.DOWNLOADING && !knownProgress)
    val accent = when (status) {
        DownloadPhase.COMPLETE -> StatusOnline
        DownloadPhase.FAILED, DownloadPhase.CANCELLED -> colors.error
        DownloadPhase.PAUSED, DownloadPhase.QUEUED -> colors.onSurfaceVariant
        else -> colors.primary
    }
    val phase = stringResource(when (status) {
        DownloadPhase.COMPLETE -> if (onOpenGame != null) R.string.vapor_download_ready else R.string.downloads_queue_phase_complete
        DownloadPhase.DOWNLOADING -> R.string.downloads_queue_phase_downloading
        DownloadPhase.PAUSED -> R.string.downloads_queue_phase_paused
        DownloadPhase.QUEUED -> R.string.downloads_queue_phase_queued
        DownloadPhase.PREPARING -> R.string.downloads_queue_phase_preparing
        DownloadPhase.VERIFYING -> R.string.downloads_queue_phase_verifying
        DownloadPhase.PATCHING -> R.string.downloads_queue_phase_patching
        DownloadPhase.APPLYING_DATA -> R.string.downloads_queue_phase_applying_data
        DownloadPhase.FINALIZING -> R.string.downloads_queue_phase_finalizing
        DownloadPhase.UNPACKING -> R.string.downloads_queue_phase_unpacking
        DownloadPhase.CANCELLED -> R.string.downloads_queue_phase_cancelled
        DownloadPhase.FAILED -> R.string.vapor_download_failed
        else -> R.string.downloads_queue_phase_unknown
    })
    Surface(Modifier.fillMaxWidth().controllerConfirmOnA(), color = colors.surface,
        contentColor = colors.onSurface, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, colors.outline.copy(alpha = .6f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AsyncImage(image, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(88.dp, 52.dp).clip(RoundedCornerShape(8.dp)).background(colors.background))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (store.isNotEmpty()) Text(store, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(phase, color = accent, style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (status == DownloadPhase.FAILED) {
                Text(message?.takeIf { it.isNotBlank() && it != "null" } ?: stringResource(R.string.common_ui_unknown_error),
                    color = colors.error, style = MaterialTheme.typography.bodyMedium)
            } else if (!finished && !currentFile.isNullOrBlank()) {
                Text(currentFile.orEmpty(), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (indeterminate) LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp), color = accent, trackColor = colors.outlineVariant)
            else DownloadProgressBar(value, status, Modifier.fillMaxWidth().height(6.dp))
            if (knownProgress && !processing && status != DownloadPhase.CANCELLED) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${(value * 100).roundToInt()}%", fontWeight = FontWeight.SemiBold)
                    if (total > 0) Text("${StorageUtils.formatDecimalSize(bytes)} / ${StorageUtils.formatDecimalSize(total)}", color = colors.onSurfaceVariant)
                    if (status == DownloadPhase.DOWNLOADING && progress < 1f && speed > 0) {
                        Text(StorageUtils.formatBitsPerSecond(speed), color = colors.primary)
                        eta?.takeIf { it > 0 }?.let { Text(stringResource(R.string.vapor_download_time_left,
                            DateUtils.formatElapsedTime((it / 1000).coerceAtLeast(1))), color = colors.onSurfaceVariant) }
                    }
                }
            }
            if (status == DownloadPhase.COMPLETE && onOpenGame != null) {
                DownloadsQueueButton(stringResource(R.string.vapor_open_game), StatusOnline, true, onClick = onOpenGame)
            } else if (!finished) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DownloadsQueueButton(stringResource(when {
                        status == DownloadPhase.FAILED -> R.string.session_drawer_retry
                        resumable -> R.string.session_drawer_resume
                        else -> R.string.session_drawer_pause
                    }), colors.primary, true, onSecondary = { confirmCancel = true }, onClick = if (resumable) onResume else onPause)
                    DownloadsQueueButton(stringResource(R.string.downloads_queue_cancel_download), colors.error, true,
                        onSecondary = { confirmCancel = true }, onClick = { confirmCancel = true })
                }
            }
        }
    }
    DownloadCancelWarningMenu(confirmCancel, { confirmCancel = false }, { confirmCancel = false; onCancel() })
}
