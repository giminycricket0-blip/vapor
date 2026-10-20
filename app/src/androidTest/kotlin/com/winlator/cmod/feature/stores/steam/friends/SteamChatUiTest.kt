package com.winlator.cmod.feature.stores.steam.friends

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.winlator.cmod.R
import com.winlator.cmod.feature.stores.steam.data.SteamChatMessage
import com.winlator.cmod.feature.stores.steam.data.SteamFriend
import com.winlator.cmod.feature.stores.steam.data.SteamFriendEntry
import com.winlator.cmod.feature.stores.steam.enums.EPersonaState
import com.winlator.cmod.shared.theme.WinNativeTheme
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI fixtures only. No Steam login, network send or game session is created. */
@RunWith(AndroidJUnit4::class)
class SteamChatUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun label(id: Int) = compose.activity.getString(id)
    private val friend = SteamFriendEntry(42, "Fixture friend", EPersonaState.Online)

    @Test fun signedOutDrawerOffersSignInAndHidesCachedFriends() {
        var signIns = 0
        compose.setContent { WinNativeTheme {
            FriendsDrawerContent(true, SteamFriend(), listOf(friend), emptySet(),
                SteamChatAvailability.SIGNED_OUT, { signIns++ }, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText(label(R.string.vapor_friends_chat)).assertIsDisplayed()
        compose.onNodeWithText(friend.name).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, signIns) }
        compose.onNodeWithText(label(R.string.common_ui_sign_in)).performClick()
        compose.runOnIdle { assertEquals(1, signIns) }
    }

    @Test fun gameSessionLabelsCachedStatusAndHidesStaleJoinAction() {
        val playing = friend.copy(gameAppId = 440, gameName = "Fixture game", connectString = "fixture")
        var opened = 0; var joined = 0
        compose.setContent { WinNativeTheme {
            FriendsDrawerContent(true, SteamFriend(), listOf(playing), setOf(440),
                SteamChatAvailability.IN_GAME, {}, {}, { opened++ }, { joined++ }, { joined++ }, {})
        } }
        compose.onNodeWithText(label(R.string.vapor_chat_game_session)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.vapor_chat_last_known)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.steam_friends_join)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.steam_friends_play)).assertDoesNotExist()
        compose.onNodeWithText(friend.name).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, opened); assertEquals(0, joined) }
    }

    @Test fun readyFriendsStillOpenTheExistingConversation() {
        var opened = 0
        compose.setContent { WinNativeTheme {
            FriendsDrawerContent(true, SteamFriend(), listOf(friend), emptySet(),
                SteamChatAvailability.READY, {}, {}, { opened++ }, {}, {}, {})
        } }
        compose.onNodeWithText(friend.name).performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun offlineComposerKeepsEditableDraftButBlocksSendAndImage() {
        val chat = SteamConversation().apply { input = "Draft" }
        var calls = 0
        compose.setContent { WinNativeTheme {
            SteamChatComposer(chat, SteamChatAvailability.OFFLINE, { calls++ }, { calls++ })
        } }
        compose.onNodeWithContentDescription(label(R.string.steam_chat_send)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(label(R.string.steam_chat_send_image)).assertIsNotEnabled()
        compose.onNodeWithText("Draft").performTextReplacement("Updated draft")
        compose.onNodeWithText("Updated draft").performImeAction()
        compose.runOnIdle { assertEquals("Updated draft", chat.input); assertEquals(0, calls) }
    }

    @Test fun failedSendKeepsDraftAndManualRetryCanSucceed() {
        val chat = SteamConversation().apply { input = "Fixture message" }
        var calls = 0
        compose.setContent { WinNativeTheme {
            val scope = rememberCoroutineScope()
            SteamChatComposer(chat, SteamChatAvailability.READY, {
                scope.launch { chat.send(true) { text ->
                    calls++
                    if (calls == 1) null else SteamChatMessage(true, text, 1234, 1)
                } }
            }, {})
        } }
        compose.onNodeWithContentDescription(label(R.string.steam_chat_send)).performClick()
        compose.onNodeWithText(label(R.string.vapor_chat_send_unconfirmed)).assertIsDisplayed()
        compose.onNodeWithText("Fixture message").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, calls); assertTrue(chat.messages.isEmpty()) }
        compose.onNodeWithContentDescription(label(R.string.steam_chat_send)).performClick()
        compose.runOnIdle { assertEquals(2, calls); assertEquals("", chat.input); assertEquals(1, chat.messages.size) }
    }

    @Test fun handoffAndReconnectNeverAutomaticallySendAnExistingDraft() {
        val chat = SteamConversation().apply { input = "Keep this" }
        val status = mutableStateOf(SteamChatAvailability.READY)
        var calls = 0
        compose.setContent { WinNativeTheme { SteamChatComposer(chat, status.value, { calls++ }, {}) } }
        compose.runOnIdle { status.value = SteamChatAvailability.IN_GAME }
        compose.onNodeWithContentDescription(label(R.string.steam_chat_send)).assertIsNotEnabled()
        compose.runOnIdle { status.value = SteamChatAvailability.READY }
        compose.onNodeWithContentDescription(label(R.string.steam_chat_send)).assertIsEnabled()
        compose.runOnIdle { assertEquals(0, calls); assertEquals("Keep this", chat.input) }
    }

    @Test fun keyboardSendUsesSameExplicitAction() {
        val chat = SteamConversation().apply { input = "Fixture message" }
        var calls = 0
        compose.setContent { WinNativeTheme { SteamChatComposer(chat, SteamChatAvailability.READY, { calls++ }, {}) } }
        compose.onNodeWithText("Fixture message").performImeAction()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun composerRemainsUsableWithNarrowWidthAndLargeText() {
        val chat = SteamConversation().apply { input = "A short draft" }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) { WinNativeTheme {
                Box(Modifier.width(280.dp)) { SteamChatComposer(chat, SteamChatAvailability.OFFLINE, {}, {}) }
            } }
        }
        compose.onNodeWithText("A short draft").assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.steam_chat_send)).assertIsDisplayed().assertIsNotEnabled()
    }
}
