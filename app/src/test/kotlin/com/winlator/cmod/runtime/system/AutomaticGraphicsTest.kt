package com.winlator.cmod.runtime.system

import android.app.Application
import android.os.Bundle
import com.winlator.cmod.runtime.system.AutomaticGraphics.Route
import com.winlator.cmod.shared.util.KeyValueSet
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AutomaticGraphicsTest {
    private val legacy = Route("Turnip", "1.10.3-arm64ec-0")
    private val modern = Route("Turnip", "2.7.1-arm64ec-0")
    private val d3d12 = modern.copy(vkd3d = "3.0.1-arm64ec-0")
    private val system = legacy.copy(driver = "System")
    private val routes = listOf(legacy, modern, d3d12, system)
    private fun start(next: Bundle? = null, remembered: String = "", d12: Boolean = false) =
        AutomaticGraphics.start(routes, "device-component-game", d12, next, remembered)
    private fun fail(session: AutomaticGraphics.Session) {
        assertTrue(session.observe("err: Failed to create Vulkan device: VK_ERROR_FEATURE_NOT_PRESENT", 1000))
    }

    @Test fun firstAttemptIsConservativeAndDirectX12AlwaysUsesThePairedRuntime() {
        assertEquals(legacy, start().route)
        assertEquals(d3d12, start(d12 = true).route)
        val config = KeyValueSet("version=old,vkd3dVersion=None,async=0")
        val driver = mutableMapOf("version" to "System")
        assertEquals("dxvk+vkd3d", d3d12.apply(config, driver))
        assertEquals(d3d12.dxvk, config.get("version"))
        assertEquals(d3d12.vkd3d, config.get("vkd3dVersion"))
        assertEquals("0", config.get("async"))
    }

    @Test fun retriesWaitForCleanTeardownCloudAndForeground() {
        val session = start(); fail(session)
        assertNull(session.afterCleanup(false, true, true))
        assertNull(session.afterCleanup(true, false, true))
        assertNull(session.afterCleanup(true, true, false))
        val next = start(session.afterCleanup(true, true, true)!!)
        assertEquals(2, next.attempt)
        assertEquals(modern, next.route)
    }

    @Test fun duplicateErrorsCannotConsumeMultipleAttempts() {
        val session = start(); fail(session)
        assertFalse(session.observe("err: Failed to create Vulkan device: -8", 1001))
        assertEquals(1, session.afterCleanup(true, true, true)!!.getStringArrayList("tried")!!.size)
    }

    @Test fun cancellationAlsoStopsAnAlreadyPendingRetry() {
        val session = start(); fail(session); session.cancel()
        assertNull(session.afterCleanup(true, true, true))
    }

    @Test fun directX12StartupDiagnosticsPromoteOnlyPairedCandidates() {
        val session = start()
        assertTrue(session.observe("012c:err:vkd3d_create_vk_device: Failed to create Vulkan device, vr -8.", 1000))
        assertEquals(d3d12, start(session.afterCleanup(true, true, true)).route)
    }

    @Test fun cloudSteamCodecAndGenericCrashMessagesNeverTriggerGraphicsRetries() {
        for (line in listOf("[wn-launcher] error: D3D12CreateDevice failed", "Steam Cloud failed to create Vulkan device",
            "err:module:import_dll Library vcruntime140.dll not found", "err:mfplat: video initialization failed",
            "err:seh: Unhandled exception page fault", "err: VK_ERROR_DEVICE_LOST", "Loading...",
            "info: Failed to create Vulkan device", "err: Steam Cloud upload failed")) {
            assertNull(line, AutomaticGraphics.classify(line))
        }
        // Games can probe a high D3D feature level and then successfully fall back.
        assertNull(AutomaticGraphics.classify("012c:err:d3d12:D3D12CreateDevice failed: unsupported feature level"))
        assertNull(AutomaticGraphics.classify("err: DXGI: Failed to create adapter"))
    }

    @Test fun pinnedTranslatorDiagnosticsAreRecognized() {
        assertEquals(AutomaticGraphics.Failure.VULKAN_STARTUP,
            AutomaticGraphics.classify("err:  DxvkInstance::createInstance: Failed to create Vulkan instance"))
        assertEquals(AutomaticGraphics.Failure.VULKAN_STARTUP,
            AutomaticGraphics.classify("err:  DXVK: No adapters found. Please check your device filter settings"))
        assertEquals(AutomaticGraphics.Failure.DIRECT3D12_STARTUP,
            AutomaticGraphics.classify("014c:err:vkd3d_create_vk_instance: Failed to create Vulkan instance, vr -3."))
    }

    @Test fun ordinaryGameExitAndTimePassingCannotStartAnotherAttempt() {
        val session = start()
        assertNull(session.afterCleanup(true, true, true))
        session.processObserved(0)
        assertFalse(session.observe("err: Failed to create Vulkan device: -8", 90_001))
    }

    @Test fun firstFrameOrAWindowWithoutTheGameProcessCannotQualifyAChoice() {
        val session = start()
        repeat(500) { session.frame(it * 100L) }
        assertTrue(session.observe("err: Failed to create Vulkan device", 50_000))
        assertNotNull(session.afterCleanup(true, true, true))
    }

    @Test fun sustainedProcessAndFramesStopAutomaticRestartsDuringGameplay() {
        val session = start(); session.processObserved(0)
        repeat(121) { session.frame(it * 200L) }
        assertFalse(session.observe("err: Failed to create Vulkan device: -4", 25_000))
        assertNull(session.afterCleanup(true, true, true))
    }

    @Test fun cloudPreparationTimeDoesNotConsumeTheGraphicsStartupWindow() {
        val session = start()
        session.processObserved(150_000)
        assertTrue(session.observe("err: Failed to create Vulkan device", 151_000))
        assertNotNull(session.afterCleanup(true, true, true))
    }

    @Test fun staleRememberedChoiceCannotOverrideTheCurrentPlan() {
        assertEquals(modern, start(remembered = "device-component-game|${modern.id}").route)
        assertEquals(legacy, start(remembered = "old-device-or-component|${modern.id}").route)
        assertEquals(legacy, start(remembered = "device-component-game|unknown-route").route)
    }

    @Test fun changedInputsAndUnknownRouteIdsCannotResumeAnOldAttempt() {
        for (next in listOf(
            Bundle().apply { putString("fingerprint", "old"); putStringArrayList("tried", arrayListOf(legacy.id)) },
            Bundle().apply { putString("fingerprint", "device-component-game"); putStringArrayList("tried", arrayListOf("unknown")) },
            Bundle().apply { putString("fingerprint", "device-component-game"); putStringArrayList("tried", arrayListOf(legacy.id, legacy.id)) },
        )) assertThrows(IllegalStateException::class.java) { start(next) }
    }

    @Test fun noRouteIsRetriedAndExhaustionDoesNotLoop() {
        var session = start()
        val used = mutableSetOf<String>()
        repeat(3) {
            assertTrue(used.add(session.route.id)); fail(session)
            val next = session.afterCleanup(true, true, true)
            if (it < 2) session = start(next!!) else assertNull(next)
        }
    }

    @Test fun hardBudgetStillAppliesWhenManyDriversAreInstalled() {
        val many = (1..20).map { legacy.copy(driver = "Driver $it") }
        var session = AutomaticGraphics.start(many, "same", false, null, "")
        repeat(4) {
            fail(session)
            val next = session.afterCleanup(true, true, true)
            if (it < 3) session = AutomaticGraphics.start(many, "same", false, next!!, "")
            else assertNull(next)
        }
    }

    @Test fun minimumFeaturesUseBitsNotJustAVulkanVersionLabel() {
        fun caps(api: Long, mask: Long) = GraphicsDriverCatalog.Capabilities(listOf(api, 0, 0, 0, mask, 0, 0))
        val v13 = GraphicsDriverCatalog.Capabilities.vulkan(1, 3)
        assertFalse(caps(v13, 0).modernFloor)
        assertFalse(caps(v13, 0).direct3d12Floor)
        assertTrue(caps(v13, 1023).direct3d12Floor)
        for (required in listOf(1, 2, 3, 4, 5, 6, 7, 8, 9))
            assertFalse("Missing bit $required", caps(v13, 1023L xor (1L shl required)).direct3d12Floor)
        assertFalse(caps(GraphicsDriverCatalog.Capabilities.vulkan(1, 1), 1023).modernFloor)
        assertTrue(caps(GraphicsDriverCatalog.Capabilities.vulkan(1, 1), 128).legacyFloor)
    }
}
