package com.winlator.cmod.app.shell

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.winlator.cmod.R
import com.winlator.cmod.feature.stores.steam.data.SteamApp
import com.winlator.cmod.shared.ui.focus.controllerFocusGlow

/** Home and the Library carousel share one cover shelf. Game actions stay with the Library. */
@Composable
internal fun VaporGameShelf(
    games: List<SteamApp>,
    recent: Boolean,
    selectedIndex: Int,
    heroArtwork: Any?,
    installed: Boolean,
    onSelect: (Int) -> Unit,
    onOpenGame: (SteamApp) -> Unit,
    onSettings: (() -> Unit)?,
    card: @Composable (SteamApp, Int, Boolean, Modifier, () -> Unit) -> Unit,
) {
    if (games.isEmpty()) return
    val selected = selectedIndex.coerceIn(games.indices)
    val listState = rememberLazyListState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val stackedAction = maxWidth / density.fontScale < 520.dp
        val compact = maxHeight / density.fontScale < 320.dp
        val cardHeight = if (compact) (maxHeight - 160.dp * density.fontScale).coerceIn(88.dp, 196.dp)
            else (maxHeight * 0.5f).coerceIn(176.dp, 288.dp)
        val cardWidth = (cardHeight * 2f / 3f).coerceAtMost(maxWidth - 48.dp)
        val peek = with(density) { (maxWidth * 0.1f).coerceAtMost(cardWidth).roundToPx() }
        LaunchedEffect(selected, peek) { listState.animateScrollToItem(selected, -peek) }

        AsyncImage(heroArtwork, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize().blur(24.dp, BlurredEdgeTreatment.Unbounded)
                .graphicsLayer { alpha = 0.5f })
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(BgDark.copy(alpha = 0.15f), BgDark))))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (recent) R.string.vapor_home_recent else R.string.vapor_home_your_games).uppercase(),
                    color = TextSecondary, style = MaterialTheme.typography.labelLarge, letterSpacing = 1.5.sp,
                    modifier = Modifier.weight(1f))
                Text("${selected + 1} / ${games.size}", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            }
            LazyRow(state = listState, modifier = Modifier.fillMaxWidth().height(cardHeight + 16.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(games, key = { _, game -> game.id }) { index, game ->
                    val focused = index == selected
                    val emphasis by animateFloatAsState(if (focused) 1f else 0.84f,
                        tween(160), label = "shelfCardEmphasis")
                    card(game, index, focused, Modifier.width(cardWidth).height(cardHeight)
                        .graphicsLayer {
                            scaleX = emphasis; scaleY = emphasis
                            alpha = if (focused) 1f else 0.78f
                            transformOrigin = TransformOrigin(0.5f, 0f)
                        }
                        .testTag("vapor_shelf_game_${game.id}").semantics { this.selected = focused }
                        .onFocusChanged { if (it.hasFocus) onSelect(index) }) {
                            if (focused) onOpenGame(game) else onSelect(index)
                        }
                }
            }
            val title: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Crossfade(games[selected].name, animationSpec = tween(160), label = "shelfTitle") { name ->
                        Text(name, color = TextPrimary,
                            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(stringResource(if (installed) R.string.common_ui_installed else R.string.vapor_game_not_installed),
                        color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            val open: @Composable (Modifier) -> Unit = { modifier ->
                Row(modifier, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onOpenGame(games[selected]) }, shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TextPrimary, contentColor = BgDark),
                        modifier = (if (stackedAction) Modifier.weight(1f) else Modifier)
                            .heightIn(min = 48.dp).controllerFocusGlow()) {
                        if (installed) {
                            Icon(Icons.Outlined.PlayArrow, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(if (installed) R.string.vapor_open_game else R.string.common_ui_install))
                    }
                    onSettings?.let { settings ->
                        OutlinedIconButton(onClick = settings,
                            border = BorderStroke(1.dp, TextSecondary.copy(alpha = 0.55f)),
                            modifier = Modifier.size(48.dp).controllerFocusGlow()) {
                            Icon(Icons.Outlined.Settings, stringResource(R.string.vapor_session_failure_game_settings),
                                Modifier.size(22.dp), tint = TextPrimary)
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).vaporGlass(RoundedCornerShape(20.dp)).padding(16.dp)) {
                if (stackedAction) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    title(Modifier.fillMaxWidth())
                    open(Modifier.fillMaxWidth())
                } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    title(Modifier.weight(1f))
                    open(Modifier)
                }
            }
        }
    }
}
