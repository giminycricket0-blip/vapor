package com.winlator.cmod.feature.stores.steam.friends

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.feature.stores.steam.data.SteamChatMessage
import com.winlator.cmod.feature.stores.steam.data.SteamFriend
import com.winlator.cmod.feature.stores.steam.data.SteamFriendEntry
import com.winlator.cmod.feature.stores.steam.enums.EPersonaState
import com.winlator.cmod.feature.stores.steam.utils.PrefManager
import com.winlator.cmod.shared.theme.WinNativeBackground
import com.winlator.cmod.shared.theme.WinNativeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** Production Compose layout in an account-free JVM Activity. No APK or Steam request. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SteamSocialLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val friend = SteamFriendEntry(42, "Sample friend", EPersonaState.Online)

    @Before fun preferences() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("social-layout-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        ReflectionHelpers.setField(PrefManager, "prefs", prefs)
    }

    @After fun clearPreferences() { ReflectionHelpers.setField(PrefManager, "prefs", null) }

    private fun label(id: Int) = compose.activity.getString(id)

    private fun content(fontScale: Float = 1f, body: @Composable () -> Unit) {
        RuntimeEnvironment.setFontScale(fontScale)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                WinNativeTheme {
                    Box(Modifier.fillMaxSize().background(WinNativeBackground)) { body() }
                }
            }
        }
    }

    private fun drawer(fontScale: Float = 1f, onOpen: () -> Unit = {}, onDismiss: () -> Unit = {}) {
        content(fontScale) {
            FriendsDrawerContent(true, SteamFriend(), listOf(friend), emptySet(),
                SteamChatAvailability.READY, {}, {}, { onOpen() }, {}, {}, onDismiss)
        }
    }

    private fun settingsClose() = compose.onNode(
        hasContentDescription(label(R.string.steam_common_back)) and hasAnyAncestor(isDialog()))

    @Test fun accountSettingsHasAFullTouchTarget() {
        drawer()
        val bounds = compose.onNodeWithContentDescription(label(R.string.steam_friends_settings))
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("Settings target must be at least 48 dp wide", bounds.right - bounds.left >= 48.dp)
        assertTrue("Settings target must be at least 48 dp high", bounds.bottom - bounds.top >= 48.dp)
        capture("friends-portrait.png")
    }

    @Test fun narrowChatSettingsCanReachLastOptionAtLargeTextSize() {
        drawer(fontScale = 1.5f)
        compose.onNodeWithContentDescription(label(R.string.steam_friends_settings)).performClick()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(label(R.string.steam_chat_settings_title))
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1.5f, layouts.single().layoutInput.density.fontScale)
        capture("settings-large-text.png", dialog = true)
        compose.onNodeWithText(label(R.string.steam_chat_setting_stay_running))
            .performScrollTo().assertIsDisplayed()
        val close = settingsClose().assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(close.right - close.left >= 48.dp && close.bottom - close.top >= 48.dp)
        capture("settings-large-text-scrolled.png", dialog = true)
    }

    @Test @Config(qualifiers = "w800dp-h320dp-land")
    fun shortLandscapeSettingsKeepTheHeaderReachableAfterScrolling() {
        drawer(fontScale = 1.5f)
        compose.onNodeWithContentDescription(label(R.string.steam_friends_settings)).performClick()
        compose.onNodeWithText(label(R.string.steam_chat_setting_stay_running))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.steam_chat_settings_title)).assertIsDisplayed()
        capture("settings-landscape.png", dialog = true)
        settingsClose().assertIsDisplayed().performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test fun tappingTheSettingLabelTogglesItsExistingPreferenceOnce() {
        drawer()
        compose.onNodeWithContentDescription(label(R.string.steam_friends_settings)).performClick()
        val before = PrefManager.chatNotificationsEnabled
        compose.onNodeWithText(label(R.string.steam_chat_setting_notifications)).performClick()
        compose.runOnIdle { assertEquals(!before, PrefManager.chatNotificationsEnabled) }
    }

    @Test @Config(qualifiers = "w640dp-h320dp-land")
    fun shortDrawerKeepsFriendsReachableAndClosesThroughItsOwner() {
        var opened = 0
        var closed = 0
        drawer(fontScale = 1.5f, onOpen = { opened++ }, onDismiss = { closed++ })
        compose.onNodeWithText(friend.name).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription(label(R.string.common_ui_close)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, opened); assertEquals(1, closed) }
    }

    @Test @Config(qualifiers = "w900dp-h640dp-land")
    fun wideMessagesUseReadableLinesAndKeepTheirSideOfTheConversation() {
        val incoming = "Incoming message. ".repeat(30)
        val outgoing = "Outgoing message. ".repeat(30)
        content {
            Column(Modifier.width(800.dp)) {
                MessageBubble(SteamChatMessage(false, incoming, 1))
                MessageBubble(SteamChatMessage(true, outgoing, 2))
            }
        }
        val left = compose.onNodeWithText(incoming).assertIsDisplayed().getUnclippedBoundsInRoot()
        val right = compose.onNodeWithText(outgoing).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(left.right - left.left > 260.dp && left.right - left.left <= 480.dp)
        assertTrue(right.left > left.left)
        capture("messages-wide.png")
    }

    @Test fun narrowMessagesWrapWithoutHorizontalOverflowAtLargeTextSize() {
        val message = "A long message that should wrap in a narrow handheld window. ".repeat(4)
        content(fontScale = 1.5f) {
            Column(Modifier.width(240.dp)) { MessageBubble(SteamChatMessage(false, message, 1)) }
        }
        val bounds = compose.onNodeWithText(message).getUnclippedBoundsInRoot()
        assertTrue(bounds.left >= 0.dp && bounds.right <= 240.dp)
        capture("messages-narrow.png")
    }

    @Test fun conversationKeepsItsCloseActionAndBlocksSendingWhenSignedOut() {
        var closed = 0
        val longName = friend.copy(name = "A friend with a very long display name that needs to wrap")
        content(fontScale = 1.5f) { SteamChatScreen(longName) { closed++ } }
        compose.onNodeWithText(longName.name).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.steam_chat_send)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(label(R.string.steam_chat_send_image)).assertIsNotEnabled()
        val close = settingsClose().assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(close.right - close.left >= 48.dp && close.bottom - close.top >= 48.dp)
        capture("conversation-portrait.png", dialog = true)
        settingsClose().performClick()
        compose.runOnIdle { assertEquals(1, closed) }
    }

    // Window capture uses PixelCopy, which does not redraw under Robolectric. Draw the
    // actual Android view into a native Canvas instead; these are JVM renders, not phone captures.
    private fun capture(name: String, dialog: Boolean = false) {
        val target = File("build/reports/tests/social-polish", name).apply { parentFile!!.mkdirs() }
        compose.runOnIdle {
            val view = if (dialog) ShadowDialog.getLatestDialog().window!!.decorView
                else compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
