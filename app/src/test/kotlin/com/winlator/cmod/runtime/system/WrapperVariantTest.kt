package com.winlator.cmod.runtime.system

import com.winlator.cmod.shared.util.StringUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WrapperVariantTest {
    @Test fun existingIdsKeepTheirAssetsHooksAndMarkerNames() {
        assertEquals("graphics_driver/wrapper.tzst", WrapperVariant.forDriver("wrapper").asset)
        assertEquals("graphics_driver/wrapper-leegao.tzst", WrapperVariant.forDriver("wrapper-leegao").asset)
        assertEquals("graphics_driver/wrapper-gamenative.tzst", WrapperVariant.forDriver("wrapper-gamenative").asset)
        assertTrue(WrapperVariant.LEEGAO.usesLeegaoHooks)
        assertFalse(WrapperVariant.STOCK.usesLeegaoHooks)
        assertFalse(WrapperVariant.GAMENATIVE.usesLeegaoHooks)
        // Marker strings are unchanged from the previous inline code, so existing installs do not re-extract.
        assertEquals("stock:7", WrapperVariant.STOCK.state(7))
        assertEquals("leegao:7", WrapperVariant.LEEGAO.state(7))
        assertEquals("gamenative:7", WrapperVariant.GAMENATIVE.state(7))
    }

    @Test fun unknownOrMissingIdsFallBackToTheStockWrapper() {
        assertEquals(WrapperVariant.STOCK, WrapperVariant.forDriver(null))
        assertEquals(WrapperVariant.STOCK, WrapperVariant.forDriver(""))
        assertEquals(WrapperVariant.STOCK, WrapperVariant.forDriver("turnip"))
    }

    @Test fun experimentalVaporEntryMapsToItsOwnPackageWithStockHooks() {
        val id = StringUtils.parseIdentifier("Wrapper-Vapor (experimental)")
        assertEquals("wrapper-vapor", id)
        val variant = WrapperVariant.forDriver(id)
        assertEquals(WrapperVariant.VAPOR, variant)
        assertEquals("graphics_driver/wrapper-vapor.tzst", variant.asset)
        assertFalse(variant.usesLeegaoHooks)
        assertEquals("vapor:7", variant.state(7))
    }
}
