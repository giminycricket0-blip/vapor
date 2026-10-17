package com.winlator.cmod.feature.stores.steam.friends

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.feature.stores.steam.data.SteamChatMessage
import com.winlator.cmod.feature.stores.steam.service.SteamService
import com.winlator.cmod.shared.ui.nav.paneNavItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

internal enum class SteamChatAvailability(val notice: Int?) {
    READY(null),
    SIGNED_OUT(R.string.vapor_chat_sign_in),
    DISABLED(R.string.steam_friends_chat_off),
    IN_GAME(R.string.vapor_chat_game_session),
    OFFLINE(R.string.vapor_chat_offline);

    companion object {
        fun from(signedIn: Boolean, enabled: Boolean, handoff: Boolean, connected: Boolean) = when {
            !signedIn -> SIGNED_OUT
            !enabled -> DISABLED
            handoff -> IN_GAME
            !connected -> OFFLINE
            else -> READY
        }
    }
}

@Composable
internal fun rememberSteamChatAvailability(): SteamChatAvailability {
    val signedIn by SteamService.isLoggedInFlow.collectAsState()
    val enabled by SteamService.chatServiceEnabledFlow.collectAsState()
    val connected by SteamService.isConnectedFlow.collectAsState()
    val loggedOn by SteamService.wnLoggedOnFlow.collectAsState()
    val noService = remember { MutableStateFlow(false) }
    val handoff by (SteamService.instance?.bionicHandoffFlow ?: noService).collectAsState()
    return SteamChatAvailability.from(signedIn, enabled, handoff, connected && loggedOn)
}

/** Shared by the normal chat dialog and floating chat. No queue or automatic resend. */
@Stable
internal class SteamConversation {
    val messages = mutableStateListOf<SteamChatMessage>()
    var input by mutableStateOf("")
    var loading by mutableStateOf(false)
    var sending by mutableStateOf(false)
        private set
    var sendFailed by mutableStateOf(false)
        private set

    fun merge(received: List<SteamChatMessage>) {
        for (message in received) {
            // The same Steam identity can arrive as an acknowledgement, live echo and history.
            if (message.timestamp <= 0) continue
            if (messages.none { it.fromSelf == message.fromSelf &&
                    it.timestamp == message.timestamp && it.ordinal == message.ordinal }) {
                messages.add(message)
            }
        }
        messages.sortWith(compareBy({ it.timestamp }, { it.ordinal }))
    }

    suspend fun send(available: Boolean, action: suspend (String) -> SteamChatMessage?) {
        if (!available || sending || input.isBlank()) return
        val draft = input
        sending = true
        sendFailed = false
        try {
            val acknowledged = action(draft.trim())
            if (acknowledged == null) {
                sendFailed = true
            } else {
                merge(listOf(acknowledged))
                if (input == draft) input = ""
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep the draft. Do not put a local error into the Steam conversation itself.
            sendFailed = true
        } finally {
            sending = false
        }
    }
}

@Composable
internal fun rememberSteamConversation(friendId: Long, availability: SteamChatAvailability): SteamConversation {
    val service = SteamService.instance
    val conversation = remember(friendId, service) { SteamConversation() }
    // Subscribe separately from the blocking history request so messages arriving during it survive.
    LaunchedEffect(friendId, service) {
        service?.incomingChat?.collect { (id, message) ->
            if (id == friendId) conversation.merge(listOf(message))
        }
    }
    LaunchedEffect(friendId, service, availability) {
        if (availability == SteamChatAvailability.READY) {
            conversation.loading = true
            try {
                conversation.merge(service?.loadChatHistory(friendId).orEmpty())
            } finally {
                conversation.loading = false
            }
        }
    }
    DisposableEffect(friendId, service, availability) {
        val active = availability == SteamChatAvailability.READY
        if (active) service?.setActiveConversation(friendId)
        onDispose { if (active) service?.clearActiveConversation(friendId) }
    }
    return conversation
}

@Composable
internal fun SteamChatConnectionNotice(availability: SteamChatAvailability) {
    availability.notice?.let { label ->
        Text(
            stringResource(label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Composable
internal fun SteamChatComposer(
    conversation: SteamConversation,
    availability: SteamChatAvailability,
    onSend: () -> Unit,
    onImage: () -> Unit,
    uploading: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    val ready = availability == SteamChatAvailability.READY
    val canSend = ready && !conversation.sending && conversation.input.isNotBlank()
    Column(Modifier.fillMaxWidth()) {
        SteamChatConnectionNotice(availability)
        if (conversation.sendFailed) {
            Text(
                stringResource(R.string.vapor_chat_send_unconfirmed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        if (conversation.sending) {
            Text(stringResource(R.string.vapor_chat_sending),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onImage,
                enabled = ready && !uploading,
                modifier = Modifier.paneNavItem(onActivate = { if (ready && !uploading) onImage() },
                    tapToSelect = true, navRow = 1, navCol = 0),
            ) {
                Icon(Icons.Outlined.Image, contentDescription = stringResource(R.string.steam_chat_send_image))
            }
            OutlinedTextField(
                value = conversation.input,
                onValueChange = { conversation.input = it },
                enabled = !conversation.sending && availability != SteamChatAvailability.SIGNED_OUT,
                placeholder = { Text(stringResource(R.string.steam_chat_message_hint)) },
                maxLines = 3,
                modifier = Modifier.weight(1f).focusRequester(focus)
                    .paneNavItem(onActivate = { runCatching { focus.requestFocus() } },
                        navRow = 1, navCol = 1, isEntry = true),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
            )
            IconButton(
                onClick = onSend,
                enabled = canSend,
                modifier = Modifier.paneNavItem(onActivate = { if (canSend) onSend() },
                    tapToSelect = true, navRow = 1, navCol = 2),
            ) {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = stringResource(R.string.steam_chat_send))
            }
        }
    }
}
