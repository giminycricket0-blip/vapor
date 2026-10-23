package com.winlator.cmod.runtime.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wrapper is now part of a route rather than a consequence of the driver name, so that a
 * combination the wrapper cannot serve can move to another wrapper instead of failing. These
 * pin the two properties that makes that work: routes differing only by wrapper are distinct,
 * and the chosen wrapper survives into the driver config the launch reads.
 */
class RouteWrapperTest {
    private fun route(wrapper: WrapperVariant) =
        AutomaticGraphics.Route(
            driver = "System",
            dxvk = "2.7.1-gplasync-arm64ec",
            vkd3d = "3.0.1-arm64ec",
            wrapper = wrapper,
        )

    @Test
    fun routesDifferingOnlyByWrapperAreDistinct() {
        val stock = route(WrapperVariant.STOCK)
        val vapor = route(WrapperVariant.VAPOR)
        assertNotEquals("the wrapper must be part of route identity", stock.id, vapor.id)
    }

    @Test
    fun theChosenWrapperIsWrittenToTheDriverConfig() {
        val config = mutableMapOf("version" to "System")
        route(WrapperVariant.VAPOR).apply(com.winlator.cmod.shared.util.KeyValueSet(""), config)
        assertEquals("wrapper-vapor", config["wrapperVariant"])
        assertEquals("System", config["version"])
    }

    @Test
    fun theDescriptionNamesANonStockWrapper() {
        assertTrue(route(WrapperVariant.VAPOR).description.contains("wrapper-vapor"))
        assertTrue(
            "a stock wrapper is the norm and should not be spelled out",
            !route(WrapperVariant.STOCK).description.contains("wrapper"),
        )
    }

    @Test
    fun anExplicitWrapperIdWins() {
        assertEquals(WrapperVariant.VAPOR, WrapperVariant.forChosen("wrapper-vapor", "System"))
        assertEquals(WrapperVariant.LEEGAO, WrapperVariant.forChosen("wrapper-leegao", "System"))
    }

    @Test
    fun aMissingWrapperIdFallsBackToTheDriver() {
        // Older configs and manual launches carry no wrapper choice.
        assertEquals(WrapperVariant.VAPOR, WrapperVariant.forChosen(null, "wrapper-vapor"))
        assertEquals(WrapperVariant.STOCK, WrapperVariant.forChosen(null, "System"))
        assertEquals(WrapperVariant.STOCK, WrapperVariant.forChosen("", "Turnip"))
    }

    @Test
    fun anUnknownWrapperIdIsIgnored() {
        assertEquals(WrapperVariant.STOCK, WrapperVariant.forChosen("wrapper-nonsense", "System"))
    }
}
