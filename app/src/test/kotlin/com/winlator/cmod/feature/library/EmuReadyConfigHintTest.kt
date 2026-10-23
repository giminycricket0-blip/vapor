package com.winlator.cmod.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping from EmuReady's structured fields to a readable configuration. This is the only
 * part that has to be right for community reports to be usable as a prior: keys are stable
 * field names, and a report that says nothing must not produce a confident-looking hint.
 */
class EmuReadyConfigHintTest {
    private fun setting(
        name: String,
        value: String,
    ) = EmuReady.Setting(label = name, value = value, name = name)

    @Test
    fun aReportedDxvkConfigurationIsSummarised() {
        val hint =
            EmuReadyConfigHint.from(
                listOf(
                    setting("dx_wrapper", "DXVK"),
                    setting("dxvk_version", "2.3.1"),
                    setting("graphics_driver", "Turnip (Adreno)"),
                    setting("average_fps", "60"),
                    setting("resolution", "1280x720"),
                ),
            )
        assertEquals(
            "DXVK 2.3.1 · Turnip (Adreno) · 60 fps · 1280x720",
            hint?.summary(),
        )
    }

    @Test
    fun theDxvkGenerationDistinguishesTheTwoBundledBuilds() {
        val legacy =
            EmuReadyConfigHint.from(listOf(setting("dx_wrapper", "DXVK"), setting("dxvk_version", "1.10.3")))
        assertEquals("legacy (1.x)", legacy?.dxvkGeneration)

        val modern =
            EmuReadyConfigHint.from(listOf(setting("dx_wrapper", "DXVK"), setting("dxvk_version", "2.3.1")))
        assertEquals("modern (2.x+)", modern?.dxvkGeneration)

        // An async 1.10.x build is still the legacy generation.
        val asyncLegacy =
            EmuReadyConfigHint.from(listOf(setting("dx_wrapper", "DXVK"), setting("dxvk_version", "async-1.10.3")))
        assertEquals("legacy (1.x)", asyncLegacy?.dxvkGeneration)
    }

    @Test
    fun labelsWithoutStableNamesProduceNothing() {
        // Display-only settings (no custom-field name) must not be guessed at.
        val hint = EmuReadyConfigHint.from(listOf(EmuReady.Setting("DX Wrapper", "DXVK", name = "")))
        assertNull("unnamed fields carry no reliable meaning", hint)
    }

    @Test
    fun anEmptyReportProducesNothing() {
        assertNull(EmuReadyConfigHint.from(emptyList()))
        assertNull(EmuReadyConfigHint.from(listOf(setting("dx_wrapper", ""), setting("dxvk_version", ""))))
    }

    @Test
    fun aPartialReportStillSummarisesWhatItHas() {
        val hint = EmuReadyConfigHint.from(listOf(setting("graphics_driver", "Turnip (Adreno)")))
        assertEquals("Turnip (Adreno)", hint?.summary())
    }

    @Test
    fun aNonNumericFrameRateIsDropped() {
        val hint =
            EmuReadyConfigHint.from(
                listOf(setting("dx_wrapper", "VKD3D"), setting("average_fps", "varies")),
            )
        assertEquals("VKD3D", hint?.summary())
        assertTrue(hint?.fps == null)
    }
}
