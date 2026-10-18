package com.winlator.cmod.runtime.system

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the actual JNI Vulkan probe on an account-free test device; no game or Steam session. */
@RunWith(AndroidJUnit4::class)
class GraphicsDriverProbeDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    @Test fun probeAcceptsAnAndroidContextWithoutRequiringContextWrapper() {
        val rawContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("Unknown", GPUInformation.getRenderer("vapor-missing-${System.nanoTime()}", rawContext))
    }

    @Test fun missingCustomDriverDoesNotBorrowTheSystemRenderer() {
        val id = "vapor-missing-probe-${System.nanoTime()}"
        assertEquals("Unknown", GPUInformation.getRenderer(id, context))
        assertEquals(0, GPUInformation.getVendorID(id, context))
    }

    @Test fun brokenCustomLibraryDoesNotBorrowTheSystemRenderer() {
        val id = "vapor-broken-probe-${System.nanoTime()}"
        val directory = File(context.filesDir, "contents/adrenotools/$id").apply { mkdirs() }
        try {
            File(directory, "meta.json").writeText("""{"name":"Probe fixture","libraryName":"missing.so"}""")
            assertEquals("Unknown", GPUInformation.getRenderer(id, context))
            assertEquals(0, GPUInformation.getVendorID(id, context))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun failedCustomProbesLeaveTheSystemProbeUsable() {
        val renderer = GPUInformation.getRenderer("System", context)
        assertNotEquals("Unknown", renderer)
        repeat(3) {
            assertEquals("Unknown", GPUInformation.getRenderer("vapor-absent-${System.nanoTime()}", context))
            assertEquals(renderer, GPUInformation.getRenderer("System", context))
        }
    }

    @Test fun featureSnapshotDoesNotBorrowSystemCapabilitiesForAMissingDriver() {
        val missing = GPUInformation.probeCapabilities("vapor-absent-features", context)
        assertEquals(7, missing.size)
        assertTrue(missing.all { it == 0L })
        val actual = GPUInformation.probeCapabilities("System", context)
        assertEquals(7, actual.size)
        // This qualification device must actually exercise Features2, not pass on an empty result.
        assertTrue("System Features2 snapshot unavailable", actual[0] >= GraphicsDriverCatalog.Capabilities.vulkan(1, 0))
        assertTrue(actual[4] in 0..1023)
        assertEquals(GPUInformation.getVendorID("System", context).toLong(), actual[1])
        val caps = GraphicsDriverCatalog.Capabilities(actual.toList())
        File(context.getExternalFilesDir(null), "Vapor-vulkan-features.txt").writeText(
            "snapshot=${actual.joinToString(",")}\nlegacyFloor=${caps.legacyFloor}\nmodernFloor=${caps.modernFloor}\ndirect3d12Floor=${caps.direct3d12Floor}\n")
        assertNotEquals("Unknown", GPUInformation.getRenderer("System", context))
    }
}
