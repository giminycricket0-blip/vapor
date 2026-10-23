package com.winlator.cmod.runtime.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The product promise is "run it or say it cannot", so the vendor classification has to be
 * right for the renderer strings real drivers report, and unsupported vendors must be stated
 * plainly rather than surfacing a graphics error later.
 */
class DeviceGraphicsTest {
    @Test
    fun adrenoRenderersAreRecognised() {
        // The forms Qualcomm and Turnip actually report.
        assertEquals(GpuFamily.ADRENO, GpuFamily.of("Adreno (TM) 840"))
        assertEquals(GpuFamily.ADRENO, GpuFamily.of("Adreno (TM) 740"))
        assertEquals(GpuFamily.ADRENO, GpuFamily.of("adreno 610"))
    }

    @Test
    fun maliRenderersAreRecognised() {
        // MediaTek Dimensity and most Exynos/Tensor parts.
        assertEquals(GpuFamily.MALI, GpuFamily.of("Mali-G615 MC6"))
        assertEquals(GpuFamily.MALI, GpuFamily.of("Mali-G720-Immortalis MC12"))
        assertEquals(GpuFamily.MALI, GpuFamily.of("Immortalis-G925"))
    }

    @Test
    fun xclipseRenderersAreRecognised() {
        assertEquals(GpuFamily.XCLIPSE, GpuFamily.of("Xclipse 940"))
    }

    @Test
    fun anUnknownOrEmptyRendererIsNotGuessed() {
        assertEquals(GpuFamily.UNKNOWN, GpuFamily.of(null))
        assertEquals(GpuFamily.UNKNOWN, GpuFamily.of(""))
        assertEquals(GpuFamily.UNKNOWN, GpuFamily.of("Some Vendor GPU"))
    }

    @Test
    fun onlyTheAdrenoStackIsShippedToday() {
        assertTrue(GpuFamily.ADRENO.shipped)
        assertFalse("Mali needs PanVK, which Vapor does not ship", GpuFamily.MALI.shipped)
        assertFalse(GpuFamily.XCLIPSE.shipped)
        assertFalse(GpuFamily.UNKNOWN.shipped)
    }

    @Test
    fun vendorLabelsAreReadable() {
        assertEquals("Adreno", GpuFamily.ADRENO.label)
        assertEquals("Mali", GpuFamily.MALI.label)
    }
}
