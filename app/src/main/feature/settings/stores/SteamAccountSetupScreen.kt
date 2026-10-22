package com.winlator.cmod.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.shared.ui.focus.controllerFocusGlow
import com.winlator.cmod.shared.ui.layout.VaporOnboardingCard
import com.winlator.cmod.shared.ui.layout.VaporOnboardingLayout

/** First-run connection; the existing shell preference owns completion and skip. */
@Composable
internal fun SteamAccountSetupScreen(connected: Boolean, onConnect: () -> Unit, onContinue: () -> Unit) {
    val primary = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current.inputMode
    // In a Dialog the composition can settle before its window gains focus, and a focus
    // request made then is dropped, so controller users landed on Back instead of the
    // primary action. Request again once the window is focused.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(connected, inputMode, windowFocused) {
        if (inputMode == InputMode.Keyboard && windowFocused) primary.requestFocus()
    }
    VaporOnboardingLayout(title = stringResource(R.string.vapor_accounts_title),
        subtitle = stringResource(R.string.vapor_accounts_intro), section = stringResource(R.string.vapor_onboarding_steam),
        onBack = onContinue) { compact ->
        val wide = maxWidth / LocalDensity.current.fontScale >= 720.dp
        val card: @Composable (Modifier) -> Unit = { modifier ->
            VaporOnboardingCard(modifier, compact = compact) {
                if (!compact) Icon(if (connected) Icons.Outlined.CheckCircle else Icons.Outlined.LibraryBooks, null,
                    Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(if (connected) R.string.vapor_accounts_connected else R.string.vapor_onboarding_your_library),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(stringResource(if (connected) R.string.vapor_onboarding_connected_body else R.string.vapor_onboarding_connect_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = if (connected) onContinue else onConnect,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).focusRequester(primary).controllerFocusGlow(cornerRadius = 12.dp),
                    shape = RoundedCornerShape(12.dp)) {
                    Text(stringResource(if (connected) R.string.vapor_accounts_continue else R.string.vapor_connect_steam))
                }
                if (!connected) TextButton(onClick = onContinue,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).controllerFocusGlow()) {
                    Text(stringResource(R.string.vapor_accounts_later))
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.vapor_onboarding_local_title), style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.vapor_local_play_note), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    card(Modifier.weight(1f))
                }
            } else card(Modifier.fillMaxWidth())
            Text(stringResource(R.string.vapor_accounts_later_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
