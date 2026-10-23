package com.winlator.cmod.runtime.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One backend per GPU family is the structure that makes "support another chipset" a matter of
 * writing a backend instead of threading vendor checks through the graphics path. These pin the
 * selection rule and the fact that an unshipped family resolves to unsupported rather than to a
 * backend that cannot work.
 */
class GraphicsBackendTest {
    @Test
    fun adrenoResolvesToTheShippedTurnipBackend() {
        val backend = GraphicsBackends.forFamily(GpuFamily.ADRENO)
        assertEquals("turnip", backend.id)
        assertTrue(backend.shipped)
        assertEquals("libvulkan_freedreno.so", backend.driverLibrary)
        assertTrue("a shipped backend must name its asset", backend.assetPath != null)
    }

    @Test
    fun maliResolvesToUnsupportedBecausePanVkIsNotShipped() {
        // PanVK exists as a description, but no build ships it yet.
        assertEquals(GpuFamily.MALI, PanVkBackend.family)
        val selected = GraphicsBackends.forFamily(GpuFamily.MALI)
        assertEquals("unsupported", selected.id)
        assertTrue(!selected.shipped)
        assertNull(selected.assetPath)
    }

    @Test
    fun xclipseResolvesToUnsupported() {
        assertEquals("unsupported", GraphicsBackends.forFamily(GpuFamily.XCLIPSE).id)
    }

    @Test
    fun anUnknownFamilyResolvesToUnsupported() {
        assertEquals("unsupported", GraphicsBackends.forFamily(GpuFamily.UNKNOWN).id)
    }

    @Test
    fun theBackendsCarryDistinctKernelStacks() {
        // Turnip speaks KGSL; PanVK speaks kbase. They are not interchangeable.
        assertEquals("libvulkan_freedreno.so", TurnipBackend.driverLibrary)
        assertEquals("libvulkan_panfrost.so", PanVkBackend.driverLibrary)
        assertTrue(TurnipBackend.driverLibrary != PanVkBackend.driverLibrary)
    }

    @Test
    fun onlyOneBackendIsShippedToday() {
        assertEquals(1, GraphicsBackends.all.count { it.shipped })
    }
}
