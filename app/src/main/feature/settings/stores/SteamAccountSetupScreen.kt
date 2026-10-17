package com.winlator.cmod.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.shared.ui.focus.controllerConfirmOnA
import com.winlator.cmod.shared.ui.focus.controllerFocusGlow

/** First-run Steam connection; optional stores remain in Settings. */
@Composable
internal fun SteamAccountSetupScreen(
    connected: Boolean,
    onConnect: () -> Unit,
    onContinue: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().controllerConfirmOnA().verticalScroll(rememberScrollState())
            .padding(24.dp).widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Icon(Icons.Outlined.Gamepad, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.vapor_accounts_title), style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.vapor_accounts_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (connected) {
                Card(shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.vapor_accounts_connected), Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                }
            }
            Button(onClick = if (connected) onContinue else onConnect,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).controllerFocusGlow()) {
                Text(stringResource(if (connected) R.string.vapor_accounts_continue else R.string.vapor_connect_steam))
            }
            if (!connected) TextButton(onClick = onContinue,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).controllerFocusGlow()) {
                Text(stringResource(R.string.vapor_accounts_later))
            }
            Text(stringResource(R.string.vapor_accounts_later_note), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}
