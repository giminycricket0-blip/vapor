package com.winlator.cmod.feature.stores.steam.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.feature.stores.steam.enums.LoginResult
import com.winlator.cmod.feature.stores.steam.enums.LoginScreen
import com.winlator.cmod.feature.stores.steam.ui.components.QrCodeImage
import com.winlator.cmod.feature.stores.steam.ui.data.UserLoginState
import com.winlator.cmod.shared.ui.focus.controllerFocusGlow
import com.winlator.cmod.shared.ui.focus.controllerTextFieldEscape
import com.winlator.cmod.shared.ui.layout.VaporOnboardingCard
import com.winlator.cmod.shared.ui.layout.VaporOnboardingLayout

/** Rendering and input only. SteamLoginActivity/ViewModel retain authentication ownership. */
@Composable
internal fun SteamLoginScreen(
    state: UserLoginState,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onRemember: (Boolean) -> Unit,
    onCode: (String) -> Unit,
    onSignIn: () -> Unit,
    onSubmitCode: () -> Unit,
    onQrRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val guard = state.loginScreen == LoginScreen.TWO_FACTOR
    VaporOnboardingLayout(
        title = stringResource(if (guard) R.string.vapor_login_guard_title else R.string.vapor_login_title),
        subtitle = stringResource(if (guard) R.string.vapor_login_guard_body else R.string.vapor_login_body),
        section = stringResource(R.string.vapor_onboarding_steam), onBack = onBack,
    ) { compact ->
        val wide = maxWidth / LocalDensity.current.fontScale >= 720.dp
        var qrSelected by rememberSaveable { mutableStateOf(true) }
        Crossfade(targetState = guard, animationSpec = tween(180), label = "steamLoginStage") { showGuard ->
            when {
                state.loginResult == LoginResult.Success -> VaporOnboardingCard(Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                    Text(stringResource(R.string.vapor_accounts_connected), style = MaterialTheme.typography.titleLarge)
                }
                showGuard -> SteamGuardForm(state, onCode, onSubmitCode, onBack)
                wide -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                    CredentialForm(state, onUsername, onPassword, onRemember, onSignIn, Modifier.weight(1.25f), compact)
                    QrSignIn(state, onQrRetry, Modifier.weight(1f), compact)
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(selected = qrSelected, onClick = { qrSelected = true }, enabled = !state.isLoggingIn,
                            shape = SegmentedButtonDefaults.itemShape(0, 2), modifier = Modifier.heightIn(min = 48.dp).controllerFocusGlow()) {
                            Text(stringResource(R.string.vapor_login_qr_tab))
                        }
                        SegmentedButton(selected = !qrSelected, onClick = { qrSelected = false }, enabled = !state.isLoggingIn,
                            shape = SegmentedButtonDefaults.itemShape(1, 2), modifier = Modifier.heightIn(min = 48.dp).controllerFocusGlow()) {
                            Text(stringResource(R.string.vapor_login_account_tab))
                        }
                    }
                    if (qrSelected) QrSignIn(state, onQrRetry, Modifier.fillMaxWidth(), compact)
                    else CredentialForm(state, onUsername, onPassword, onRemember, onSignIn, Modifier.fillMaxWidth(), compact)
                }
            }
        }
    }
}

@Composable
private fun CredentialForm(state: UserLoginState, onUsername: (String) -> Unit, onPassword: (String) -> Unit,
    onRemember: (Boolean) -> Unit, onSignIn: () -> Unit, modifier: Modifier, compact: Boolean) {
    var passwordVisible by remember { mutableStateOf(false) }
    var submitted by remember(state.username, state.password) { mutableStateOf(false) }
    val passwordFocus = remember { FocusRequester() }
    val canSubmit = state.username.isNotBlank() && state.password.isNotEmpty() && !state.isLoggingIn
    val failed = submitted && !state.isLoggingIn && state.loginResult == LoginResult.Failed
    val submit = { if (canSubmit) { submitted = true; passwordVisible = false; onSignIn() } }
    VaporOnboardingCard(modifier, compact = compact) {
        if (!compact) Text(stringResource(R.string.vapor_login_account_tab), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(state.username, onUsername, modifier = Modifier.fillMaxWidth().controllerTextFieldEscape(),
            label = { Text(stringResource(R.string.vapor_login_account_name)) }, singleLine = true, enabled = !state.isLoggingIn,
            shape = RoundedCornerShape(12.dp), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }))
        OutlinedTextField(state.password, onPassword,
            modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus).controllerTextFieldEscape()
                .onFocusChanged { if (!it.hasFocus) passwordVisible = false },
            label = { Text(stringResource(R.string.steam_login_password)) }, singleLine = true, enabled = !state.isLoggingIn,
            shape = RoundedCornerShape(12.dp), visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }, enabled = !state.isLoggingIn,
                    modifier = Modifier.controllerFocusGlow()) {
                    Icon(if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        stringResource(if (passwordVisible) R.string.vapor_login_hide_password else R.string.vapor_login_show_password))
                }
            }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }))
        if (failed) LoginError(stringResource(R.string.vapor_login_failed))
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).controllerFocusGlow()
            .toggleable(state.rememberSession, enabled = !state.isLoggingIn, role = Role.Checkbox, onValueChange = onRemember),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(state.rememberSession, null, enabled = !state.isLoggingIn)
            Text(stringResource(R.string.vapor_login_remember), style = MaterialTheme.typography.bodyMedium)
        }
        Button(onClick = submit, enabled = canSubmit, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).controllerFocusGlow(cornerRadius = 12.dp)) {
            if (state.isLoggingIn) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
            }
            Text(stringResource(if (state.isLoggingIn) R.string.vapor_login_signing_in else R.string.common_ui_sign_in))
        }
        if (!compact) Text(stringResource(R.string.vapor_login_account_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QrSignIn(state: UserLoginState, onRetry: () -> Unit, modifier: Modifier, compact: Boolean) {
    VaporOnboardingCard(modifier, compact = compact) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.steam_login_sign_in_with_qr_code), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Box(Modifier.size(if (compact) 160.dp else 200.dp).background(Color.White, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                when {
                    state.qrCode != null -> QrCodeImage(content = state.qrCode, size = if (compact) 144.dp else 184.dp)
                    state.isQrFailed -> Icon(Icons.Outlined.Refresh, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.background)
                    else -> CircularProgressIndicator(Modifier.size(32.dp), color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(stringResource(if (state.isQrFailed) R.string.vapor_login_qr_failed else if (state.qrCode == null)
                R.string.steam_login_generating_code else R.string.vapor_login_qr_hint),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (state.isQrFailed) OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).controllerFocusGlow()) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.vapor_login_qr_retry))
            }
        }
    }
}

@Composable
private fun SteamGuardForm(state: UserLoginState, onCode: (String) -> Unit, onSubmit: () -> Unit, onBack: () -> Unit) {
    val approval = state.lastTwoFactorMethod == "steam_guard"
    val failed = state.loginResult == LoginResult.Failed
    val canSubmit = state.twoFactorCode.trim().length >= 5 && !state.isLoggingIn
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        VaporOnboardingCard(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
            Icon(Icons.Outlined.Lock, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(if (approval) R.string.vapor_login_guard_approve else R.string.vapor_login_guard_code),
                style = MaterialTheme.typography.titleLarge)
            if (failed) LoginError(stringResource(R.string.vapor_login_failed))
            else if (approval) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.vapor_login_guard_waiting), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            } else {
                Text(if (state.lastTwoFactorMethod == "email_code") stringResource(R.string.steam_login_enter_code_sent_to, state.email.orEmpty())
                    else stringResource(R.string.steam_login_enter_steam_guard_code), color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(state.twoFactorCode, onCode, label = { Text(stringResource(R.string.steam_login_code)) },
                    modifier = Modifier.fillMaxWidth().controllerTextFieldEscape(), singleLine = true, enabled = !state.isLoggingIn,
                    isError = state.previousCodeIncorrect, shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (canSubmit) onSubmit() }))
                if (state.previousCodeIncorrect) LoginError(stringResource(R.string.vapor_login_code_incorrect))
                Button(onClick = onSubmit, enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).controllerFocusGlow(), shape = RoundedCornerShape(12.dp)) {
                    if (state.isLoggingIn) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(stringResource(if (state.isLoggingIn) R.string.vapor_login_verifying else R.string.vapor_login_verify))
                }
            }
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).controllerFocusGlow()) {
                Text(stringResource(R.string.vapor_login_another_method))
            }
        }
    }
}

@Composable
private fun LoginError(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
}
