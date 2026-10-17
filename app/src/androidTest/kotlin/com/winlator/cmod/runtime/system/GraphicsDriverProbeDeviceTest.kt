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
}
