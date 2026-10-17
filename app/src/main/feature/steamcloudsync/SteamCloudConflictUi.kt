package com.winlator.cmod.feature.steamcloudsync

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.PaneNavRegistry
import com.winlator.cmod.shared.ui.nav.paneNavItem

data class SteamCloudConflictTimestamps(
    val localTimestampLabel: String,
    val cloudTimestampLabel: String,
)

@Composable
internal fun SteamCloudConflictDialogContent(
    navRegistry: PaneNavRegistry,
    timestamps: SteamCloudConflictTimestamps,
    initialKeepBackup: Boolean,
    onKeepBackupChanged: (Boolean) -> Unit,
    onUseCloud: (keepBackup: Boolean) -> Unit,
    onUseLocal: (keepBackup: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var keepBackup by remember { mutableStateOf(initialKeepBackup) }
    val changeBackup: (Boolean) -> Unit = { keepBackup = it; onKeepBackupChanged(it) }
    CompositionLocalProvider(LocalPaneNav provides navRegistry) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column(
                Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(R.string.vapor_cloud_conflict_title),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(stringResource(R.string.vapor_cloud_conflict_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .paneNavItem(onActivate = onCancel, isEntry = true),
                ) { Text(stringResource(R.string.vapor_cloud_conflict_cancel)) }
                SaveVersion(
                    stringResource(R.string.vapor_cloud_conflict_local), timestamps.localTimestampLabel,
                    stringResource(R.string.vapor_cloud_conflict_local_effect),
                    stringResource(R.string.vapor_cloud_conflict_use_local),
                    onChoose = { onUseLocal(keepBackup) },
                )
                SaveVersion(
                    stringResource(R.string.vapor_cloud_conflict_cloud), timestamps.cloudTimestampLabel,
                    stringResource(R.string.vapor_cloud_conflict_cloud_effect),
                    stringResource(R.string.vapor_cloud_conflict_use_cloud),
                    onChoose = { onUseCloud(keepBackup) },
                )
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .paneNavItem(onActivate = { changeBackup(!keepBackup) })
                        .toggleable(keepBackup, role = Role.Checkbox, onValueChange = changeBackup),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(checked = keepBackup, onCheckedChange = null)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.vapor_cloud_conflict_backup),
                            style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.vapor_cloud_conflict_backup_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SaveVersion(label: String, timestamp: String, effect: String, action: String, onChoose: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(timestamp, style = MaterialTheme.typography.bodyMedium)
            Text(effect, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onChoose,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).paneNavItem(onActivate = onChoose)) {
                Text(action)
            }
        }
    }
}
