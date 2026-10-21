package com.winlator.cmod.app.shell

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.cmod.feature.library.EmuReady
import com.winlator.cmod.shared.theme.WinNativeTheme
import java.io.File
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Android presentation and window navigation only; no account or live API is used. */
@RunWith(AndroidJUnit4::class)
class CommunityReportsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val report = EmuReady.Report("78a76cba-63cc-4e19-bdfd-3d29b7c69019", "89cf7540-e0b9-4fc0-ab7a-8b7edcd35a8a",
        "Sample handheld", "Snapdragon · sample", "Winlator · sample version", "Playable",
        "Sample report for UI testing. No live account or performance test.", 3, 1, "2026-10-21")
    private val page = EmuReady.Page(EmuReady.Game(report.gameId, "Half-Life 2", false), listOf(report))
    private fun open() = compose.onNode(hasText("Community reports") and hasClickAction()).performClick()
    private fun screen(load: suspend (Int, String) -> EmuReady.Page = { _, _ -> page }, onSource: (String) -> Unit = {}) {
        compose.setContent { WinNativeTheme {
            CommunityReportsButton(220, "Half-Life 2 · sample UI", load = load,
                loadSettings = { listOf(EmuReady.Setting("Resolution · sample", "1280x720")) }, onSource = onSource)
        } }
    }
    @Test fun noRequestUntilOpenedAndReopeningReusesTheSnapshot() {
        var requests = 0
        screen(load = { _, _ -> requests++; page })
        compose.runOnIdle { assertEquals(0, requests) }
        open()
        compose.onNodeWithText("Sample handheld").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close").performClick()
        open()
        compose.onNodeWithText("Sample handheld").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, requests) }
    }
    @Test fun settingsAndSourceRemainExplicitActions() {
        val links = mutableListOf<String>()
        screen(onSource = { links += it })
        open()
        compose.onNodeWithText("1280x720").assertDoesNotExist()
        compose.onNodeWithText("View reported settings").performScrollTo().performClick()
        compose.onNodeWithText("1280x720").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Read original report").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(report.sourceUrl), links) }
    }
    @Test fun gamepadBClosesTheModalAndCancelsLoading() {
        var cancelled = false
        screen(load = { _, _ -> try { awaitCancellation() } finally { cancelled = true } })
        open()
        compose.onNodeWithText("Loading reports…").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_B)
        compose.onNodeWithContentDescription("Close").assertDoesNotExist()
        compose.runOnIdle { assertTrue(cancelled) }
    }
    @Test fun noMatchingEntryStillAllowsBrowsingAndClosing() {
        screen(load = { _, _ -> EmuReady.Page(null, emptyList()) })
        open()
        compose.onNodeWithText("No matching Windows entry found", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Browse on EmuReady").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close").performClick()
    }
    @Test fun captureSampleReportsWithReportedSettings() {
        screen()
        open()
        compose.onNodeWithText("View reported settings").performScrollTo().performClick()
        compose.onNodeWithText("1280x720").performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Compose's semantic tree can settle before SurfaceFlinger presents it.
        SystemClock.sleep(500)
        val capture = instrumentation.uiAutomation.takeScreenshot()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "community-reports-sample.png")
        file.outputStream().use { capture.compress(Bitmap.CompressFormat.PNG, 100, it) }
        capture.recycle()
    }
}
