package com.winlator.cmod.shared.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The ambient background only works if the colour it picks actually belongs to the artwork and
 * still reads on a dark screen. These cover the two properties that matter, without needing a
 * real cover image on disk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkTintTest {
    private fun solid(
        color: Int,
        width: Int = 48,
        height: Int = 72,
    ): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    /** Half one colour, half another - a cover is rarely one flat colour. */
    private fun halves(
        left: Int,
        right: Int,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888)
        for (y in 0 until 96) {
            for (x in 0 until 64) bitmap.setPixel(x, y, if (x < 32) left else right)
        }
        return bitmap
    }

    @Test
    fun aSaturatedCoverProducesTintFromThatCover() {
        val tint = ArtworkTint.fromBitmap(solid(AndroidColor.rgb(214, 40, 40)))
        assertNotNull("a solid red cover must yield a tint", tint)
        val c = tint!!
        assertTrue("red channel should dominate: $c", c.red > c.green && c.red > c.blue)
    }

    @Test
    fun aPurpleCoverProducesPurpleTint() {
        val tint = ArtworkTint.fromBitmap(solid(AndroidColor.rgb(112, 66, 180)))!!
        assertTrue("blue and red should both beat green: $tint", tint.blue > tint.green && tint.red > tint.green)
    }

    @Test
    fun aMixedCoverStillYieldsSomething() {
        val tint = ArtworkTint.fromBitmap(halves(AndroidColor.rgb(30, 120, 220), AndroidColor.rgb(220, 170, 40)))
        assertNotNull("two-tone artwork must still produce a tint", tint)
    }

    @Test
    fun nearBlackTintsAreLiftedSoTheyAreVisible() {
        val nearBlack = Color(0xFF07090B)
        val lifted = ArtworkTint.readableOnDark(nearBlack)
        assertTrue("a near-black cover must be lifted: $lifted", lifted.luminance() > nearBlack.luminance())
        assertTrue("but must not become grey mush", lifted.luminance() < 0.30f)
    }

    @Test
    fun nearWhiteTintsAreDarkenedSoLabelsStayLegible() {
        val nearWhite = Color(0xFFF2F2F2)
        val darkened = ArtworkTint.readableOnDark(nearWhite)
        assertTrue("a near-white cover must be darkened: $darkened", darkened.luminance() < nearWhite.luminance())
        assertTrue("but must stay recognisable", darkened.luminance() > 0.25f)
    }

    @Test
    fun midRangeTintsPassThroughUnchanged() {
        val vivid = Color(0xFF7050B4)
        val result = ArtworkTint.readableOnDark(vivid)
        assertTrue("a readable mid colour should not be altered: $vivid -> $result", result == vivid)
    }
}
