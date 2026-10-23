package com.winlator.cmod.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.shared.theme.WinNativeTextSecondary

/**
 * A hint row for people playing with a controller: which physical button does what on this
 * screen.
 *
 * On a handheld the launcher is driven by a controller, but nothing on screen says what the
 * buttons do - you either know it or guess. These are deliberately *labels*, not buttons: the
 * legend describes the hardware, so it is only shown when a controller is actually connected and
 * tapping it does nothing.
 */
internal data class ControllerHint(
    val button: String,
    val action: String,
)

/** The hints shared by the browsing tabs, where focus navigation is the same everywhere. */
internal val BrowseHints =
    listOf(
        ControllerHint("A", "Open"),
        ControllerHint("B", "Back"),
        ControllerHint("\u271B", "Navigate"),
    )

/** Downloads list: A starts/pauses the focused item rather than opening anything. */
internal val DownloadsHints =
    listOf(
        ControllerHint("A", "Select"),
        ControllerHint("Y", "More"),
        ControllerHint("B", "Back"),
        ControllerHint("\u271B", "Navigate"),
    )

/** Settings adds shoulder-button section switching to the shared set. */
internal val SettingsHints =
    listOf(
        ControllerHint("A", "Select"),
        ControllerHint("L1/R1", "Section"),
        ControllerHint("B", "Back"),
    )

/** The hints for a top-level tab key, or null when a legend would only repeat the obvious. */
internal fun controllerHintsFor(tabKey: String): List<ControllerHint>? = when (tabKey) {
    "home", "library" -> BrowseHints
    "steam" -> BrowseHints
    "downloads" -> DownloadsHints
    else -> null
}

@Composable
internal fun ControllerHintLegend(
    hints: List<ControllerHint>,
    modifier: Modifier = Modifier,
) {
    if (hints.isEmpty()) return
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (hint in hints) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(50))
                        .background(WinNativeTextSecondary.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        hint.button,
                        color = WinNativeTextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
                Text(
                    hint.action,
                    color = WinNativeTextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 120.dp),
                )
            }
        }
    }
}
