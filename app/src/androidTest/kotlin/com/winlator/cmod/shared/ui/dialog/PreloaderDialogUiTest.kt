package com.winlator.cmod.shared.ui.dialog

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
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
import com.winlator.cmod.shared.theme.WinNativeTheme
import java.io.File
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production loading UI with explicit fixture state; no runtime, Steam login or Cloud transfer. */
@RunWith(AndroidJUnit4::class)
class PreloaderDialogUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val phase = "Checking Steam Cloud saves…"
    private val game = "You Have to Win the Game"
    private val state = PreloaderDialogState().apply {
        setTitle(game)
        setBadge("STEAM")
        setSubtitle("Interface test · no game running")
        setStep(phase)
        artwork.value = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff28394b.toInt()) }
    }

    private fun rotate(landscape: Boolean) {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        compose.waitUntil(10_000) {
            compose.activity.resources.configuration.orientation ==
                if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        }
    }

    @After fun restoreOrientation() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    private fun show(compact: Boolean = false) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (compact) 1.5f else 1f)) {
                WinNativeTheme {
                    Box(if (compact) Modifier.size(280.dp, 340.dp) else Modifier.fillMaxSize()) {
                        PreloaderDialogContent(state)
                    }
                }
            }
        }
    }

    @Test fun landscapeShowsTheGameAndCloudStepWithoutScrolling() {
        rotate(true)
        show()
        compose.onNodeWithText(game).assertIsDisplayed()
        compose.onNodeWithText(phase).assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
        capture("Vapor-loading-landscape-ui-test.png")
    }

    @Test fun portraitKeepsTheGameAndCloudStepVisible() {
        rotate(false)
        show()
        compose.onNodeWithText(game).assertIsDisplayed()
        compose.onNodeWithText(phase).assertIsDisplayed()
        capture("Vapor-loading-portrait-ui-test.png")
    }

    @Test fun largeTextOnASmallWindowCanReachTheWholeStatus() {
        rotate(false)
        show(compact = true)
        compose.onNodeWithText(phase).performScrollTo().assertIsDisplayed()
    }

    @Test fun theNextPhaseDoesNotShowACompletedCloudTransferPercentage() {
        rotate(true)
        state.setIndeterminate(false)
        state.setProgress(100)
        show()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo(1f, 0f..1f))).assertIsDisplayed()
        compose.runOnIdle { state.setStep("Starting the game…") }
        compose.onNodeWithText("Starting the game…").assertIsDisplayed()
        compose.onNodeWithText(phase).assertDoesNotExist()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(compose.activity.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
