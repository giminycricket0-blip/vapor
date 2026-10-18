package com.winlator.cmod.feature.setup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.shared.ui.focus.controllerFocusGlow
import com.winlator.cmod.shared.ui.layout.VaporOnboardingCard
import com.winlator.cmod.shared.ui.layout.VaporOnboardingLayout

/** Guided preparation; existing installer, permissions and manual Advanced setup remain owners. */
@Composable
internal fun PrepareVaporScreen(
    storageGranted: Boolean, notificationsGranted: Boolean, busy: Boolean, ready: Boolean,
    status: String?, progress: Float?, error: String?, details: String?,
    onGrantStorage: () -> Unit, onNotifications: () -> Unit, onPrepare: () -> Unit,
    onContinue: () -> Unit, onAdvanced: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    var showDetails by remember { mutableStateOf(false) }
    val inputMode = LocalInputModeManager.current.inputMode
    LaunchedEffect(storageGranted, busy, ready, error, inputMode) {
        if (!busy && inputMode == InputMode.Keyboard) focus.requestFocus()
    }
    VaporOnboardingLayout(
        title = stringResource(if (ready) R.string.vapor_prepare_ready_title else R.string.vapor_prepare_title),
        subtitle = stringResource(if (ready) R.string.vapor_prepare_ready_body else R.string.vapor_prepare_body),
        section = stringResource(R.string.vapor_onboarding_game_support),
    ) { compact ->
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            VaporOnboardingCard(Modifier.fillMaxWidth(), compact = compact) {
                Icon(if (ready) Icons.Outlined.CheckCircle else Icons.Outlined.Download, null,
                    Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                if (busy) {
                    Text(status.orEmpty(), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.vapor_prepare_step_percent, (progress.coerceIn(0f, 1f) * 100).toInt()),
                            style = MaterialTheme.typography.labelMedium)
                    } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.vapor_prepare_keep_open), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    if (!ready && error == null) Text(
                        if (storageGranted) stringResource(R.string.vapor_prepare_download_note,
                            (VaporPreparation.packages.sumOf { it.downloadBytes } + 999_999) / 1_000_000)
                        else stringResource(R.string.vapor_prepare_storage_note),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (error != null) {
                        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.vapor_prepare_failed_title), fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                                SelectionContainer { Text(error) }
                                Text(stringResource(R.string.vapor_prepare_retry_note), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Button(onClick = when { ready -> onContinue; !storageGranted -> onGrantStorage; else -> onPrepare },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).focusRequester(focus).controllerFocusGlow(cornerRadius = 12.dp),
                        shape = RoundedCornerShape(12.dp)) {
                        Text(stringResource(when { ready -> R.string.vapor_prepare_continue; !storageGranted -> R.string.vapor_prepare_grant_storage
                            error != null -> R.string.vapor_prepare_retry; else -> R.string.vapor_prepare_start }))
                    }
                    if (!ready && !notificationsGranted) TextButton(onClick = onNotifications,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).controllerFocusGlow()) {
                        Text(stringResource(R.string.vapor_prepare_notifications))
                    }
                    TextButton(onClick = onAdvanced, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).controllerFocusGlow()) {
                        Text(stringResource(R.string.vapor_prepare_advanced))
                    }
                }
                if (!details.isNullOrBlank()) {
                    TextButton(onClick = { showDetails = !showDetails }, modifier = Modifier.heightIn(min = 48.dp).controllerFocusGlow()) {
                        Text(stringResource(if (showDetails) R.string.vapor_prepare_hide_details else R.string.vapor_prepare_details))
                    }
                    if (showDetails) SelectionContainer { Text(details, style = MaterialTheme.typography.bodySmall) }
                }
            }
            Text(stringResource(R.string.vapor_local_play_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
