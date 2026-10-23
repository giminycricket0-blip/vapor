package com.winlator.cmod.runtime.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The retry only happens for errors the classifier positively recognises. These use the strings
 * the two bundled DXVK builds actually emit, so a game that cannot create a D3D11 device now
 * gets another combination tried instead of just failing.
 */
class AutomaticGraphicsClassifyTest {
    @Test
    fun legacyDxvkFeatureLevelRefusalIsRetriable() {
        // DXVK 1.10.3 wording - the Onimusha "unsupported graphics card" case.
        assertEquals(
            AutomaticGraphics.Failure.DIRECT3D11_DEVICE,
            AutomaticGraphics.classify("err:  D3D11CoreCreateDevice: Requested feature level not supported"),
        )
    }

    @Test
    fun modernDxvkFeatureLevelRefusalIsRetriable() {
        // DXVK 2.7.1 wording.
        assertEquals(
            AutomaticGraphics.Failure.DIRECT3D11_DEVICE,
            AutomaticGraphics.classify("err:  D3D11InternalCreateDevice: Minimum required feature level not supported"),
        )
    }

    @Test
    fun deviceCreationFailureEitherGenerationIsRetriable() {
        assertEquals(
            AutomaticGraphics.Failure.DIRECT3D11_DEVICE,
            AutomaticGraphics.classify("err:  D3D11CoreCreateDevice: Failed to create D3D11 device"),
        )
        assertEquals(
            AutomaticGraphics.Failure.DIRECT3D11_DEVICE,
            AutomaticGraphics.classify("err:  D3D11InternalCreateDevice: Failed to create D3D11 device"),
        )
    }

    @Test
    fun vulkanStartupStillWinsWhereItsOwnStringsMatch() {
        assertEquals(
            AutomaticGraphics.Failure.VULKAN_STARTUP,
            AutomaticGraphics.classify("err:  DxvkInstance: No adapters found"),
        )
        assertEquals(
            AutomaticGraphics.Failure.DIRECT3D12_STARTUP,
            AutomaticGraphics.classify("err:  vkd3d_create_vk_device: Failed to create Vulkan device"),
        )
    }

    @Test
    fun aWrapperRejectingACommandIsRetriable() {
        // The wrapper renders "%s.%s not supported" as extension.command.
        assertEquals(
            AutomaticGraphics.Failure.WRAPPER_CAPABILITY,
            AutomaticGraphics.classify("err:  VK_EXT_depth_bias2.vkCmdSetDepthBias2EXT not supported"),
        )
    }

    @Test
    fun aPlainNotSupportedMessageIsNotAGraphicsDiagnosis() {
        // Too generic on its own: only the rendered command form is matched.
        org.junit.Assert.assertNull(AutomaticGraphics.classify("err:  audio device not supported"))
    }

    @Test
    fun unrelatedErrorsAreNotGraphicsDiagnoses() {
        assertNull(AutomaticGraphics.classify("err:  some unrelated failure"))
        assertNull(AutomaticGraphics.classify("info: D3D11CoreCreateDevice: Requested feature level not supported"))
        assertNull(AutomaticGraphics.classify("[wn-launcher] err:  vkd3d_create_vk_instance failed to create vulkan instance"))
    }
}
