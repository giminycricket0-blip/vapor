package com.winlator.cmod.app.shell

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.cmod.feature.stores.steam.data.DownloadInfo
import com.winlator.cmod.feature.stores.steam.enums.DownloadPhase
import com.winlator.cmod.shared.theme.WinNativeTheme
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real download state, production card, synthetic files and callbacks. No transfers or account. */
@RunWith(AndroidJUnit4::class)
class DownloadQueueUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var paused = 0
    private var resumed = 0
    private var cancelled = 0
    private var opened = 0

    private fun info(phase: DownloadPhase, progress: Float = .46f, knownTotal: Boolean = true, error: String? = null) =
        DownloadInfo(gameId = 1, downloadingAppIds = CopyOnWriteArrayList()).apply {
            if (knownTotal) { setTotalExpectedBytes(100); initializeBytesDownloaded((progress * 100).toLong()) }
            setProgress(progress)
            updateStatus(phase, error)
        }

    private fun show(info: DownloadInfo, canOpen: Boolean = false, narrow: Boolean = false) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (narrow) 1.6f else 1f)) {
                WinNativeTheme {
                    Column(Modifier.then(if (narrow) Modifier.widthIn(max = 280.dp) else Modifier.widthIn(max = 720.dp))
                        .fillMaxSize().background(MaterialTheme.colorScheme.background)
                        .verticalScroll(rememberScrollState()).padding(16.dp)) {
                        DownloadQueueCard("Sample game", null, "Steam", info, false,
                            { paused++ }, { resumed++ }, { cancelled++ }, if (canOpen) ({ opened++ }) else null)
                    }
                }
            }
        }
    }

    @Test fun completedInstallOpensGamePageWithoutTransferActions() {
        show(info(DownloadPhase.COMPLETE, 1f), canOpen = true)
        compose.onNodeWithText("Ready to play").assertIsDisplayed()
        compose.onNodeWithText("Open game").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, opened); assertEquals(0, paused); assertEquals(0, resumed) }
        compose.onNodeWithText("Pause").assertDoesNotExist()
        compose.onNodeWithText("Cancel Download").assertDoesNotExist()
    }

    @Test fun completionWithoutATrustedInstallationDoesNotOfferPlay() {
        show(info(DownloadPhase.COMPLETE, 1f))
        compose.onNodeWithText("Complete").assertExists()
        compose.onNodeWithText("Ready to play").assertDoesNotExist()
        compose.onNodeWithText("Open game").assertDoesNotExist()
    }

    @Test fun controllerAOpensACompletedInstallExactlyOnce() {
        show(info(DownloadPhase.COMPLETE, 1f), canOpen = true)
        compose.onNodeWithText("Open game").performScrollTo()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun press(code: Int) {
            val now = SystemClock.uptimeMillis()
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                instrumentation.sendKeySync(KeyEvent(now, SystemClock.uptimeMillis(), action, code,
                    0, 0, -1, 0, 0, InputDevice.SOURCE_GAMEPAD))
            }
            compose.waitForIdle()
        }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Open game").assertIsFocused()
        press(KeyEvent.KEYCODE_BUTTON_Y)
        compose.runOnIdle { assertEquals(0, opened) }
        press(KeyEvent.KEYCODE_BUTTON_A)
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun unknownTotalDoesNotInventZeroPercent() {
        show(info(DownloadPhase.DOWNLOADING, 0f, knownTotal = false))
        compose.onNodeWithText("Downloading").assertExists()
        compose.onNodeWithText("0%").assertDoesNotExist()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)).assertExists()
        compose.onNodeWithText("Pause").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, paused) }
    }

    @Test fun finalizationAtFullTransferProgressDoesNotClaimReady() {
        show(info(DownloadPhase.FINALIZING, 1f), canOpen = true)
        compose.onNodeWithText("Finalizing").assertExists()
        compose.onNodeWithText("Ready to play").assertDoesNotExist()
        compose.onNodeWithText("Open game").assertDoesNotExist()
        compose.onNodeWithText("100%").assertDoesNotExist()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)).assertExists()
    }

    @Test fun failedTransferKeepsTheWholeErrorAndRetriesExistingDownload() {
        val error = "Connection interrupted. Verified files are kept. Reconnect to Wi-Fi, then retry this download."
        show(info(DownloadPhase.FAILED, error = error))
        compose.onNodeWithText(error).assertExists()
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, resumed); assertEquals(0, cancelled) }
    }

    @Test fun pausedTransferResumesInsteadOfStartingAnotherAction() {
        show(info(DownloadPhase.PAUSED))
        compose.onNodeWithText("Resume").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, resumed); assertEquals(0, paused) }
    }

    @Test fun cancelIsSentOnlyAfterConfirmation() {
        show(info(DownloadPhase.DOWNLOADING))
        compose.onNodeWithText("Cancel Download").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, cancelled) }
        compose.onAllNodes(hasText("Cancel Download") and hasClickAction()).onLast().performClick()
        compose.runOnIdle { assertEquals(1, cancelled) }
    }

    @Test fun completionReplacesInFlightControlsWithoutRecreatingTheQueue() {
        val download = info(DownloadPhase.DOWNLOADING)
        show(download, canOpen = true)
        compose.onNodeWithText("Pause").assertExists()
        compose.runOnIdle { download.updateStatus(DownloadPhase.COMPLETE) }
        compose.onNodeWithText("Pause").assertDoesNotExist()
        compose.onNodeWithText("Open game").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun narrowLargeTextKeepsRecoveryActionsReachable() {
        show(info(DownloadPhase.FAILED, error = "Connection interrupted. Your verified files are kept."), narrow = true)
        compose.onNodeWithText("Retry").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Cancel Download").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, resumed) }
    }

    @Test fun previewQueue() {
        val active = info(DownloadPhase.DOWNLOADING).apply { updateCurrentFileName("Content/Paks/sample-game-data.pak") }
        val complete = info(DownloadPhase.COMPLETE, 1f)
        compose.setContent {
            WinNativeTheme {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Downloads · UI preview with sample transfers", color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.titleLarge)
                    DownloadQueueCard("Sample adventure", null, "Steam", active, false, {}, {}, {})
                    DownloadQueueCard("Sample puzzle game", null, "Steam", complete, false, {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("Downloading").assertExists()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val landscape = context.resources.configuration.screenWidthDp > context.resources.configuration.screenHeightDp
        val file = File(context.getExternalFilesDir(null), "downloads-review/queue-${if (landscape) "landscape" else "portrait"}.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
