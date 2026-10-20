package com.winlator.cmod.feature.stores.steam.friends

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.winlator.cmod.R
import com.winlator.cmod.feature.stores.steam.data.SteamChatMessage
import com.winlator.cmod.feature.stores.steam.data.SteamFriendEntry
import com.winlator.cmod.feature.stores.steam.service.SteamService
import com.winlator.cmod.shared.theme.WinNativeAccent as Accent
import com.winlator.cmod.shared.theme.WinNativeBackground as BgDark
import com.winlator.cmod.shared.theme.WinNativeOutline as CardBorder
import com.winlator.cmod.shared.theme.WinNativePanel as SurfaceDark
import com.winlator.cmod.shared.theme.WinNativeTextPrimary as TextPrimary
import com.winlator.cmod.shared.theme.WinNativeTextSecondary as TextSecondary
import com.winlator.cmod.shared.ui.nav.DialogPaneNav
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.PaneNavRegistry
import com.winlator.cmod.shared.ui.nav.paneNavItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val IMG_BBCODE = Regex("""\[img](.+?)\[/img]""", RegexOption.IGNORE_CASE)
// Steam delivers chat images as [img src=URL] or as a bare UGC URL.
private val IMG_SRC = Regex("""\[img\b[^]]*?\bsrc=["']?([^\s"'\]]+)""", RegexOption.IGNORE_CASE)
private val BARE_IMG_URL = Regex("""https?://\S+\.(?:png|jpe?g|gif|webp|bmp)""", RegexOption.IGNORE_CASE)
// Public displayable Steam UGC images (not /filedownload/ endpoints, which need auth).
private val STEAM_IMG_URL = Regex("""https?://\S*images\.steamusercontent\.com/ugc/\S+""", RegexOption.IGNORE_CASE)

private fun imageUrlOf(text: String): String? {
    val t = text.trim()
    return IMG_BBCODE.find(t)?.groupValues?.getOrNull(1)
        ?: IMG_SRC.find(t)?.groupValues?.getOrNull(1)
        ?: STEAM_IMG_URL.find(t)?.value
        ?: BARE_IMG_URL.find(t)?.value
}

@Composable
fun SteamChatScreen(
    friend: SteamFriendEntry,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val availability = rememberSteamChatAvailability()
    val conversation = rememberSteamConversation(friend.steamId, availability)
    val messages = conversation.messages
    var uploading by remember(friend.steamId) { mutableStateOf(false) }
    var imageFailed by remember(friend.steamId) { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null && !uploading && availability == SteamChatAvailability.READY) {
            imageFailed = false
            uploading = true
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }
                    if (bytes == null || bytes.isEmpty()) {
                        imageFailed = true
                        return@launch
                    }
                    val mime = context.contentResolver.getType(uri) ?: "image/png"
                    val ext = when {
                        mime.contains("jpeg") || mime.contains("jpg") -> "jpeg"
                        mime.contains("gif") -> "gif"
                        mime.contains("webp") -> "webp"
                        else -> "png"
                    }
                    val url = SteamService.instance?.sendChatImage(friend.steamId, bytes, "image.$ext")
                    imageFailed = url.isNullOrBlank()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    imageFailed = true
                } finally {
                    uploading = false
                }
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun send() {
        scope.launch {
            conversation.send(availability == SteamChatAvailability.READY) { text ->
                SteamService.instance?.sendChatMessage(friend.steamId, text)
            }
        }
    }

    val registry = remember { PaneNavRegistry() }
    registry.onEdgeUp = { scope.launch { runCatching { listState.animateScrollBy(-280f) } } }
    registry.onEdgeDown = { scope.launch { runCatching { listState.animateScrollBy(280f) } } }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        CompositionLocalProvider(LocalPaneNav provides registry) {
            DialogPaneNav(registry, onDismiss = onClose)
            Box(
                Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = BgDark,
                    border = BorderStroke(1.dp, CardBorder),
                    modifier = Modifier
                        .widthIn(max = 800.dp)
                        .fillMaxWidth()
                        .fillMaxHeight(),
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).background(BgDark),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Outlined.Person, contentDescription = null, tint = TextSecondary)
                                if (friend.avatarUrl != null) {
                                    AsyncImage(
                                        model = friend.avatarUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(40.dp).clip(CircleShape),
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    friend.name.ifBlank { friend.steamId.toString() },
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (availability != SteamChatAvailability.READY) stringResource(R.string.vapor_chat_last_known)
                                    else if (friend.isPlayingGame) friend.gameName.ifBlank { stringResource(R.string.steam_friends_in_game) }
                                    else if (friend.isOnline) stringResource(R.string.stores_accounts_status_online) else stringResource(R.string.stores_accounts_status_offline),
                                    color = if (availability == SteamChatAvailability.READY && friend.isOnline) Accent else TextSecondary,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(
                                onClick = onClose,
                                modifier = Modifier.size(48.dp).paneNavItem(cornerRadius = 18.dp, onActivate = onClose, tapToSelect = true, navRow = 0, navCol = 0),
                            ) {
                                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.steam_common_back), tint = TextSecondary)
                            }
                        }
                        HorizontalDivider(color = CardBorder, thickness = 0.5.dp)

                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            if (conversation.loading && messages.isEmpty()) {
                                CircularProgressIndicator(
                                    color = Accent,
                                    modifier = Modifier.size(28.dp).align(Alignment.Center),
                                )
                            } else if (messages.isEmpty()) {
                                Text(
                                    stringResource(R.string.vapor_chat_empty),
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp, vertical = 12.dp),
                                )
                            } else {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    item { Spacer(Modifier.height(8.dp)) }
                                    items(messages) { msg -> MessageBubble(msg) }
                                    item { Spacer(Modifier.height(8.dp)) }
                                }
                            }
                        }

                        if (uploading) {
                            Row(
                                Modifier.fillMaxWidth().background(SurfaceDark).padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(stringResource(R.string.steam_chat_uploading_image), color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        HorizontalDivider(color = CardBorder, thickness = 0.5.dp)
                        if (imageFailed) {
                            Text(stringResource(R.string.steam_chat_image_failed),
                                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
                        }
                        SteamChatComposer(conversation, availability, ::send, {
                            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }, uploading)
                    }
                }
            }
        }
    }
}

@Composable
internal fun MessageBubble(msg: SteamChatMessage) {
    val imageUrl = imageUrlOf(msg.text)
    val bubbleColor = if (msg.fromSelf) Accent.copy(alpha = 0.22f) else SurfaceDark
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val bubbleWidth = minOf(480.dp, maxWidth * 0.85f)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = if (msg.fromSelf) Arrangement.End else Arrangement.Start,
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 14.dp,
                    topEnd = 14.dp,
                    bottomStart = if (msg.fromSelf) 14.dp else 4.dp,
                    bottomEnd = if (msg.fromSelf) 4.dp else 14.dp,
                ),
                color = bubbleColor,
                modifier = Modifier.widthIn(max = bubbleWidth),
            ) {
                if (imageUrl != null) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = stringResource(R.string.steam_chat_image),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .padding(4.dp)
                            .width(236.dp)
                            .heightIn(min = 120.dp, max = 240.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(BgDark),
                    )
                } else {
                    Text(
                        msg.text,
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
