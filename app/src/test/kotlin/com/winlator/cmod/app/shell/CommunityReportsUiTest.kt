package com.winlator.cmod.app.shell

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.winlator.cmod.feature.library.EmuReady
import com.winlator.cmod.shared.theme.WinNativeTheme
import java.io.File
import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w1000dp-h800dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommunityReportsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val report = EmuReady.Report("78a76cba-63cc-4e19-bdfd-3d29b7c69019", "89cf7540-e0b9-4fc0-ab7a-8b7edcd35a8a",
        "Sample handheld", "Snapdragon · sample", "Winlator · sample version", "Playable", "Sample community report, not a live Steam test.", 3, 1, "2026-10-21")
    private val page = EmuReady.Page(EmuReady.Game(report.gameId, "Half-Life 2", false), listOf(report))
    private fun button() = compose.onNode(hasText("Community reports") and hasClickAction())
    private fun screen(load: suspend (Int, String) -> EmuReady.Page,
        settings: suspend (EmuReady.Report) -> List<EmuReady.Setting> = { emptyList() },
        source: (String) -> Unit = {}) {
        compose.setContent { WinNativeTheme { CommunityReportsButton(220, "Half-Life 2", load = load, loadSettings = settings, onSource = source) } }
    }

    @Test fun constructingAGamePageDoesNotFetchReportsOrSettings() {
        var calls = 0
        screen({ _, _ -> calls++; page }, { calls++; emptyList() })
        button().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, calls) }
    }
    @Test fun reopeningTheModalReusesThePageSnapshotAndSourceLinksAreExplicit() {
        var calls = 0
        val links = mutableListOf<String>()
        screen({ id, title -> assertEquals(220, id); assertEquals("Half-Life 2", title); calls++; page }, source = { links += it })
        button().performClick()
        compose.onNodeWithText("Sample handheld").assertIsDisplayed()
        compose.onNodeWithText("Read original report").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(report.sourceUrl), links); assertEquals(1, calls) }
        compose.onNodeWithContentDescription("Close").performClick()
        button().performClick()
        compose.onNodeWithText("Sample handheld").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, calls) }
    }
    @Test fun aServiceErrorOffersAnExplicitRetryAndNeverSaysIncompatible() {
        var calls = 0
        screen({ _, _ -> if (++calls == 1) throw IOException("offline") else page })
        button().performClick()
        compose.onNodeWithText("Could not reach EmuReady", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Sample handheld").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, calls) }
    }
    @Test fun noEntryIsNotAConnectionErrorOrAnUnsupportedVerdict() {
        screen({ _, _ -> EmuReady.Page(null, emptyList()) })
        button().performClick()
        compose.onNodeWithText("No matching Windows entry found", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Could not reach EmuReady", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Browse on EmuReady").assertIsDisplayed()
    }
    @Test fun aTitleOnlyMatchIsLabeledAndSettingsLoadOnlyOnRequest() {
        var settingsCalls = 0
        screen({ _, _ -> page }, { settingsCalls++; listOf(EmuReady.Setting("Resolution", "1280x720")) })
        button().performClick()
        compose.onNodeWithText("Steam edition and build are not confirmed", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, settingsCalls) }
        compose.onNodeWithText("View reported settings").performScrollTo().performClick()
        compose.onNodeWithText("1280x720").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, settingsCalls) }
    }
    @Test fun settingsRetryNeedsOnlyOneTapAndDoesNotRefetchTheReportList() {
        var reports = 0
        var settingsCalls = 0
        screen({ _, _ -> reports++; page }, { if (++settingsCalls == 1) throw IOException("offline") else emptyList() })
        button().performClick()
        compose.onNodeWithText("View reported settings").performScrollTo().performClick()
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        compose.onNodeWithText("No settings recorded in this report.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, reports); assertEquals(2, settingsCalls) }
    }
    @Test fun closingALoadingModalCancelsTheRequest() {
        var cancelled = false
        screen({ _, _ -> try { awaitCancellation() } finally { cancelled = true } })
        button().performClick()
        compose.onNodeWithText("Loading reports…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.runOnIdle { assertTrue(cancelled) }
    }
    @Test fun aMissingSteamIdDoesNotCreateAnAction() {
        compose.setContent { WinNativeTheme { CommunityReportsButton(0, "Custom game") } }
        compose.onNodeWithText("Community reports").assertDoesNotExist()
    }
    private fun layout(width: Int, height: Int, scale: Float, name: String) {
        var view: View? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                WinNativeTheme {
                    val root = LocalView.current
                    SideEffect { view = root }
                    Box(Modifier.size(width.dp, height.dp)) {
                        CommunityReportsContent("Half-Life 2 · sample", page, false, false, {}, {}, {}, {}, { emptyList() })
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Close").assertIsDisplayed()
        compose.onNodeWithText("Browse on EmuReady").assertIsDisplayed()
        compose.onNodeWithText("Read original report").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            val root = view!!
            val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(image))
            val output = File("build/test-evidence/CommunityReportsUiTest/$name.png")
            output.parentFile.mkdirs()
            output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }
    @Test fun narrowLargeTextKeepsCloseAndSourceActionsReachable() = layout(340, 640, 1.5f, "narrow-large-text")
    @Test fun shortLandscapeKeepsSourceActionsReachable() = layout(880, 400, 1f, "landscape")
}
