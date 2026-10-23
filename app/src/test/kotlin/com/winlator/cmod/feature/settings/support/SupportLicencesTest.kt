package com.winlator.cmod.feature.settings.support

import android.app.Application
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The bundled third-party licence texts must be readable in the app: shipping them
 * inside the APK without a way to see them only half satisfies the licences.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w1000dp-h800dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SupportLicencesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    // Deliberately a fixed list: adding a licence text is a decision, so the bundled
    // set is asserted exactly rather than only "non-empty".
    // Deliberately a fixed list: adding a licence text is a decision, so the bundled
    // set is asserted exactly rather than only "non-empty".
    private val expected = listOf(
        "7zip-19.00-LICENSE.txt", "OpenSSL-1.1.1q.txt", "cnc-ddraw-6.6.0.0-MIT.txt",
        "curl-7.85.0.txt", "dxvk-zlib-libpng-LICENSE.txt",
        "ffmpeg-COPYING.LGPLv2.1.txt", "flac-COPYING.Xiph.txt", "lame-LICENSE.txt",
        "libogg-COPYING.txt", "libsamplerate-COPYING.txt", "libsoxr-LICENCE-LGPL-2.1.txt",
        "libtheora-COPYING.txt", "libunistring-COPYING-GPL-3.0.txt",
        "libunistring-COPYING.LIB-LGPL-3.0.txt", "libvorbis-COPYING.txt",
        "mangohud-MIT.txt", "readline-COPYING-GPL-3.0.txt",
        "vkd3d-proton-LGPL-COPYING.txt", "wine-COPYING.LIB-LGPL-2.1.txt",
        "wine-LICENSE.txt", "xorgproto-COPYING.txt",
    )

    @Test fun everyBundledLicenceIsListedWithItsSize() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val licences = loadBundledLicences(context)
        assertEquals("bundled licence set changed", expected, licences.map { it.fileName })
        assertTrue("sizes must be positive", licences.all { it.bytes > 0 })
    }

    @Test fun licenceLabelsAreHumanReadableNotRawFilenames() {
        assertEquals("Wine (COPYING.LIB-LGPL-2.1)", licenceLabel("wine-COPYING.LIB-LGPL-2.1.txt"))
        assertEquals("cnc-ddraw (MIT)", licenceLabel("cnc-ddraw-6.6.0.0-MIT.txt"))
        assertEquals("7-Zip 19.00", licenceLabel("7zip-19.00-LICENSE.txt"))
        assertEquals("X11 protocol headers", licenceLabel("xorgproto-COPYING.txt"))
        assertEquals("mangohud-MIT", licenceLabel("mangohud-MIT.txt"))
    }

    @Test fun theSupportScreenListsTheLicencesAndOpensOne() {
        compose.setContent { SupportScreen() }
        compose.onNodeWithText("THIRD-PARTY LICENCES").assertIsDisplayed()
        compose.onNodeWithText("cnc-ddraw (MIT)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Wine (LICENSE)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("xorgproto-COPYING.txt").performScrollTo().assertIsDisplayed()
        // Opening one shows its verbatim text (a single text node, so match as substring).
        compose.onNodeWithText("cnc-ddraw (MIT)").performScrollTo().performClick()
        compose.onNodeWithText("MIT License", substring = true).assertIsDisplayed()
    }
}
