package com.winlator.cmod.feature.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R

/** Ordinary first run. The existing manual wizard is reached only through Advanced setup. */
@Composable
internal fun PrepareVaporScreen(
    storageGranted: Boolean,
    notificationsGranted: Boolean,
    busy: Boolean,
    ready: Boolean,
    status: String?,
    progress: Float?,
    error: String?,
    details: String?,
    onGrantStorage: () -> Unit,
    onNotifications: () -> Unit,
    onPrepare: () -> Unit,
    onContinue: () -> Unit,
    onAdvanced: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    var primaryFocused by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    LaunchedEffect(storageGranted, busy, ready, error) { if (!busy) focus.requestFocus() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 680.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text("VAPOR", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Icon(if (ready) Icons.Outlined.CheckCircle else Icons.Outlined.SportsEsports,
                    contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
                Text(stringResource(if (ready) R.string.vapor_prepare_ready_title else R.string.vapor_prepare_title),
                    style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                Text(stringResource(if (ready) R.string.vapor_prepare_ready_body else R.string.vapor_prepare_body),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

                if (busy) {
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)).padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(status.orEmpty(), style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        if (progress != null) {
                            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Text(stringResource(R.string.vapor_prepare_step_percent, (progress.coerceIn(0f, 1f) * 100).toInt()),
                                style = MaterialTheme.typography.labelMedium)
                        } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.vapor_prepare_keep_open), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    if (!ready && error == null) {
                        Text(stringResource(if (storageGranted) R.string.vapor_prepare_download_note else R.string.vapor_prepare_storage_note),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (error != null) {
                        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp)).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.vapor_prepare_failed_title), fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                            SelectionContainer { Text(error, color = MaterialTheme.colorScheme.onErrorContainer) }
                            Text(stringResource(R.string.vapor_prepare_retry_note), color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Button(
                        onClick = when { ready -> onContinue; !storageGranted -> onGrantStorage; else -> onPrepare },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).focusRequester(focus)
                            .onFocusChanged { primaryFocused = it.isFocused }
                            .then(if (primaryFocused) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(8.dp)) else Modifier),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(stringResource(when { ready -> R.string.vapor_prepare_continue; !storageGranted -> R.string.vapor_prepare_grant_storage
                            error != null -> R.string.vapor_prepare_retry; else -> R.string.vapor_prepare_start }))
                    }
                    if (!ready && !notificationsGranted) {
                        TextButton(onClick = onNotifications, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.vapor_prepare_notifications))
                        }
                    }
                    OutlinedButton(onClick = onAdvanced, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(8.dp)) {
                        Text(stringResource(R.string.vapor_prepare_advanced))
                    }
                }
                if (!details.isNullOrBlank()) {
                    TextButton(onClick = { showDetails = !showDetails }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(if (showDetails) R.string.vapor_prepare_hide_details else R.string.vapor_prepare_details))
                    }
                    if (showDetails) SelectionContainer { Text(details, style = MaterialTheme.typography.bodySmall) }
                }
                Text(stringResource(R.string.vapor_local_play_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
