package com.winlator.cmod.app.shell

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.cmod.shared.theme.WinNativeTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production game page with labeled sample history. No account, game launch or save changes. */
@RunWith(AndroidJUnit4::class)
class LibraryGamePageUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val actions = mutableListOf<String>()
    private var pageView: View? = null

    private fun screen(
        width: Int? = null,
        height: Int = 700,
        fontScale: Float = 1f,
        playEnabled: Boolean = true,
        steamMenuEnabled: Boolean = true,
        steamActionsEnabled: Boolean = true,
        showSaveTransfer: Boolean = false,
        showAchievements: Boolean = true,
        showCheats: Boolean = false,
        useDialog: Boolean = false,
        preview: Boolean = false,
        showBranches: Boolean = false,
        steamAppId: Int? = null,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val artwork = File(context.getExternalFilesDir(null), "game-page-art.jpg").takeIf { preview && it.isFile }
        val previewBitmap = artwork?.let { BitmapFactory.decodeFile(it.path) }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(if (width == null) density.density else 1f, fontScale)) {
                WinNativeTheme {
                    val page = @androidx.compose.runtime.Composable {
                        val view = LocalView.current
                        SideEffect { pageView = view }
                        Box(if (width != null) Modifier.size(width.dp, height.dp) else Modifier) {
                            LibraryGameLaunchScreen(
                                steamAppId = steamAppId,
                                appName = if (preview) "Helltaker" else "Sample game with a longer title",
                                subtitle = if (preview) "Game page preview · sample play history" else "Sample history · no account connected",
                                sourceLabel = "Steam", heroImageUrl = previewBitmap, customHeroImageCacheKey = null,
                                releaseDateEpochSeconds = 0, totalPlaytimeMillis = 5_400_000,
                                playCount = 3, lastPlayedMillis = 0, installSizeText = "350 MB",
                                removalKeepsFiles = false, hasPinnedShortcut = false,
                                steamMenuEnabled = steamMenuEnabled, areSteamActionsEnabled = steamActionsEnabled,
                                playEnabled = playEnabled, playDisabledLabel = if (playEnabled) null else "Updating",
                                branches = if (showBranches) listOf(StoreBranchOption("public", "Default"), StoreBranchOption("legacy", "legacy")) else emptyList(),
                                selectedBranchId = "public", onSelectBranch = { actions += "branch:$it" },
                                showSaveTransfer = showSaveTransfer,
                                onSaveTransfer = if (showSaveTransfer) ({ actions += "transfer" }) else null,
                                onBack = { actions += "back" }, onPlay = { actions += "play" },
                                onSettings = { actions += "settings" }, onBootToDesktop = { actions += "desktop" },
                                onAchievements = if (showAchievements) ({ actions += "achievements" }) else null,
                                onShortcut = { actions += "shortcut" }, onCloudSaves = { actions += "saves" },
                                onVerifyFiles = { actions += "verify" }, onCheckForUpdate = { actions += "update" },
                                onWorkshop = { actions += "workshop" }, onUninstall = { actions += "uninstall" },
                                onCheats = if (showCheats) ({ actions += "cheats" }) else null,
                            )
                        }
                    }
                    if (useDialog) {
                        Dialog(onDismissRequest = { actions += "back" },
                            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) { page() }
                    } else page()
                }
            }
        }
    }

    @Test fun everydayActionsUseAvailableWidthAndMaintenanceIsInManage() {
        screen(width = 400)
        val play = compose.onNodeWithText("Play").assertIsDisplayed().getUnclippedBoundsInRoot()
        val manage = compose.onNodeWithText("Manage").assertIsDisplayed().getUnclippedBoundsInRoot()
        val shortcuts = listOf("Settings", "Cloud Saves", "Achievements").map {
            compose.onNodeWithText(it).assertIsDisplayed().getUnclippedBoundsInRoot()
        }
        assertTrue((play.right - play.left) > (manage.right - manage.left))
        assertTrue(shortcuts.all { (it.bottom - it.top) >= 48.dp })
        assertEquals(shortcuts[0].top, shortcuts[1].top)
        assertTrue(shortcuts[0].right + 8.dp <= shortcuts[1].left)
        assertTrue(shortcuts[2].top >= maxOf(shortcuts[0].bottom, shortcuts[1].bottom) + 8.dp)
        assertTrue((shortcuts[2].right - shortcuts[2].left) >= 359.dp)
        compose.onNodeWithText("Boot to Desktop").assertDoesNotExist()
        compose.onNodeWithText("Verify files", ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithText("Achievements").performClick()
        compose.onNodeWithText("Cloud Saves").performClick()
        compose.runOnIdle { assertEquals(listOf("achievements", "saves"), actions) }
    }

    @Test fun narrowDoubleTextReflowsWithoutOverlappingActions() {
        screen(width = 280, height = 520, fontScale = 2f)
        val bounds = listOf("Play", "Manage", "Settings", "Cloud Saves", "Achievements").map {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed().getUnclippedBoundsInRoot()
        }
        assertTrue(bounds.all { it.left >= 20.dp && it.right <= 260.dp && (it.bottom - it.top) >= 48.dp })
        // Bounds are measured at different scroll positions. Each control must be a full-width row.
        assertTrue(bounds.all { (it.right - it.left) >= 239.dp })
        compose.onNodeWithText("Settings").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("settings"), actions) }
    }

    @Test fun shortWidePageKeepsEveryGameActionFullyVisible() {
        screen(width = 880, height = 360)
        for (label in listOf("Play", "Manage", "Settings", "Cloud Saves", "Achievements")) {
            val node = compose.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode()
            assertTrue("$label is clipped: visible=${node.boundsInRoot.height}, measured=${node.size.height}",
                node.boundsInRoot.height >= node.size.height - 1f)
        }
    }

    @Test fun steamCommunityReportsAndPlayRemainReachableInShortLandscape() {
        screen(width = 880, height = 400, steamAppId = 220)
        compose.onNodeWithText("Community reports").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Play").performScrollTo().assertIsDisplayed()
    }

    @Test fun managePreservesExistingMaintenanceCallbacks() {
        screen()
        for ((label, action) in listOf("Verify files" to "verify", "Check for update" to "update",
            "Workshop" to "workshop", "Add to Home Screen" to "shortcut", "Boot to Desktop" to "desktop")) {
            compose.onNodeWithText("Manage").performScrollTo().performClick()
            compose.onNodeWithText(label, ignoreCase = true).performScrollTo().assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(action, actions.last()) }
        }
        compose.runOnIdle { assertEquals(5, actions.size) }
    }

    @Test fun disabledSteamMaintenanceCannotStartAnOperation() {
        screen(steamActionsEnabled = false)
        compose.onNodeWithText("Manage").performClick()
        for (label in listOf("Verify files", "Check for update", "Workshop")) {
            compose.onNodeWithText(label, ignoreCase = true).performScrollTo().assertIsNotEnabled().performClick()
        }
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test fun optionalSaveTransferAndCheatsStayAvailableAlongsideCloudSaves() {
        screen(showSaveTransfer = true, showCheats = true)
        compose.onNodeWithText("Cloud Saves").performClick()
        compose.onNodeWithText("Manage").performClick()
        compose.onNodeWithText("Cheats", ignoreCase = true).performScrollTo().performClick()
        compose.onNodeWithText("Manage").performClick()
        compose.onNodeWithText("Transfer Save", ignoreCase = true).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("saves", "cheats", "transfer"), actions) }
    }

    @Test fun unsupportedSteamActionsAndAchievementsAreNotInvented() {
        screen(steamMenuEnabled = false, showAchievements = false)
        compose.onNodeWithText("Achievements").assertDoesNotExist()
        compose.onNodeWithText("Manage").performClick()
        for (label in listOf("Verify files", "Check for update", "Workshop")) {
            compose.onNodeWithText(label, ignoreCase = true).assertDoesNotExist()
        }
        compose.onNodeWithText("Uninstall").performScrollTo().assertIsDisplayed()
    }

    private fun press(code: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val now = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            instrumentation.sendKeySync(KeyEvent(now, SystemClock.uptimeMillis(), action, code,
                0, 0, -1, 0, 0, InputDevice.SOURCE_GAMEPAD))
        }
        compose.waitForIdle()
    }

    private fun enterControllerAt(label: String) {
        val target = compose.onNodeWithText(label)
        if (!target.fetchSemanticsNode().config[SemanticsProperties.Focused]) press(KeyEvent.KEYCODE_DPAD_DOWN)
        target.assertIsFocused()
    }

    @Test fun controllerAActivatesOnceAndBClosesManageBeforeTheGamePage() {
        screen(useDialog = true)
        enterControllerAt("Play")
        press(KeyEvent.KEYCODE_BUTTON_A)
        compose.runOnIdle { assertEquals(listOf("play"), actions) }
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText("Manage").assertIsFocused()
        press(KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithText("Verify files", ignoreCase = true).assertExists()
        press(KeyEvent.KEYCODE_BUTTON_B)
        compose.onNodeWithText("Verify files", ignoreCase = true).assertDoesNotExist()
        // Compose can be idle before the native Popup window finishes returning focus.
        compose.waitUntil(5_000) { pageView?.hasWindowFocus() == true }
        compose.onNodeWithText("Manage").assertIsFocused()
        press(KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithText("Verify files", ignoreCase = true).assertExists()
        press(KeyEvent.KEYCODE_BUTTON_B)
        compose.waitUntil(5_000) { pageView?.hasWindowFocus() == true }
        press(KeyEvent.KEYCODE_BUTTON_B)
        compose.runOnIdle { assertEquals(listOf("play", "back"), actions) }
    }

    @Test fun disabledPlayCannotBeTriggeredByController() {
        screen(playEnabled = false, useDialog = true)
        compose.onNodeWithText("Updating").assertIsNotEnabled()
        enterControllerAt("Settings")
        press(KeyEvent.KEYCODE_BUTTON_A)
        compose.runOnIdle { assertEquals(listOf("settings"), actions) }
        compose.onNodeWithText("Updating").performClick()
        compose.runOnIdle { assertEquals(listOf("settings"), actions) }
    }

    @Test fun narrowLargeTextBranchHeaderDoesNotOverlapTheGameTitle() {
        screen(width = 280, height = 600, fontScale = 2f, showBranches = true)
        val source = compose.onNodeWithText("STEAM").getUnclippedBoundsInRoot()
        val branch = compose.onNodeWithText("PUBLIC").assertIsDisplayed().getUnclippedBoundsInRoot()
        val title = compose.onNodeWithText("Sample game with a longer title").performScrollTo()
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(title.top >= maxOf(branch.bottom, source.bottom) + 24.dp)
        assertTrue(source.right <= 260.dp && branch.right <= 260.dp)
        compose.onNodeWithText("PUBLIC").performClick()
        compose.onNodeWithText("legacy").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("branch:legacy"), actions) }
        compose.onNodeWithText("Cloud Saves").performScrollTo().assertIsDisplayed()
    }

    @Test fun productionGamePagePreview() {
        screen(preview = true)
        compose.onNodeWithText("Play").assertExists()
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), "vapor-game-page-preview.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("Manage").performScrollTo().performClick()
        compose.waitForIdle()
        val menu = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "vapor-game-manage-preview.png").outputStream().use {
            menu.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
