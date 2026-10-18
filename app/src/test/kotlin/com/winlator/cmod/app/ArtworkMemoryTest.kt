package com.winlator.cmod.app

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import coil.ImageLoader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ArtworkMemoryTest {
    private val loaders = mutableListOf<ImageLoader>()

    @After fun closeLoaders() { loaders.forEach { it.shutdown() } }

    private fun cacheLimit(memoryClassMb: Int, lowRam: Boolean = false): Int {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(manager).setMemoryClass(memoryClassMb)
        shadowOf(manager).setIsLowRamDevice(lowRam)
        val loader = PluviaApp.buildArtworkImageLoader(context).also { loaders += it }
        return loader.memoryCache!!.maxSize / (1024 * 1024)
    }

    @Test fun flagshipHeapCannotGrowTheArtworkCachePast48Mb() {
        assertEquals(48, cacheLimit(512))
        assertEquals(48, cacheLimit(1024))
    }

    @Test fun smallerHeapsKeepAProportionateCache() {
        assertEquals(32, cacheLimit(256))
        assertEquals(8, cacheLimit(64))
    }

    @Test fun lowRamDevicesNeverReserveMoreThan16MbForDecodedArtwork() {
        assertEquals(16, cacheLimit(256, lowRam = true))
        assertEquals(8, cacheLimit(64, lowRam = true))
    }
}
