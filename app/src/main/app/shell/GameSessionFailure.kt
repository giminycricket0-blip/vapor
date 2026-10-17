package com.winlator.cmod.app.shell

import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.shared.ui.dialog.WinNativeDialogShell
import com.winlator.cmod.shared.ui.focus.controllerFocusGlow
import com.winlator.cmod.shared.ui.focus.controllerMenuInput

/** One failure carried by the existing runtime-to-app return intent, not a persistent error log. */
data class GameSessionFailure @JvmOverloads constructor(
    val gameName: String,
    val reason: String,
    val settingsShortcutPath: String = "",
) {
    fun putInto(intent: Intent) {
        intent.putExtra(KEY, toBundle())
    }

    private fun toBundle() = Bundle().apply {
        putString("game", gameName)
        putString("reason", reason)
        putString("settingsShortcutPath", settingsShortcutPath)
    }

    companion object {
        private const val KEY = "app.vapor.android.GAME_SESSION_FAILURE"

        /** Consume delivery once; saved state decides whether a recreated screen was dismissed. */
        fun takeFrom(intent: Intent, savedState: Bundle? = null): GameSessionFailure? {
            val delivered = intent.getBundleExtra(KEY)
            intent.removeExtra(KEY)
            return read(if (savedState != null) savedState.getBundle(KEY) else delivered)
        }

        fun saveTo(outState: Bundle, failure: GameSessionFailure?) {
            if (failure == null) outState.remove(KEY) else outState.putBundle(KEY, failure.toBundle())
        }

        private fun read(bundle: Bundle?): GameSessionFailure? {
            val reason = bundle?.getString("reason")?.takeIf { it.isNotBlank() } ?: return null
            return GameSessionFailure(bundle.getString("game").orEmpty(), reason,
                bundle.getString("settingsShortcutPath").orEmpty())
        }
    }
}

@Composable
internal fun GameSessionFailureDialog(
    failure: GameSessionFailure,
    onClose: () -> Unit,
    onSettings: () -> Unit,
) {
    WinNativeDialogShell(onDismiss = onClose, maxWidth = 560.dp) {
        val closeFocus = remember { FocusRequester() }
        LaunchedEffect(failure) { closeFocus.requestFocus() }
        Column(
            modifier = Modifier.fillMaxWidth().controllerMenuInput(onDismiss = onClose),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.vapor_session_failure_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (failure.gameName.isNotBlank()) {
                Text(
                    text = failure.gameName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.vapor_session_failure_message),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        Text(
                            text = failure.reason,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.vapor_session_failure_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .focusRequester(closeFocus).controllerFocusGlow(),
            ) {
                Text(stringResource(R.string.common_ui_close))
            }
            OutlinedButton(
                onClick = onSettings,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).controllerFocusGlow(),
            ) {
                Text(stringResource(if (failure.settingsShortcutPath.isNotBlank())
                    R.string.vapor_session_failure_game_settings else R.string.vapor_session_failure_settings))
            }
        }
    }
}
