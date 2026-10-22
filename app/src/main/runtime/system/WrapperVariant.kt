package com.winlator.cmod.runtime.system

/**
 * The bundled Vulkan wrapper builds, keyed by the container/shortcut `graphicsDriver` id
 * (the display entry lower-cased by StringUtils.parseIdentifier).
 *
 * [VAPOR] is Leegao's bionic-vulkan-wrapper (3abf4870) built from source with Vapor's local
 * patches in tools/vulkan-wrapper (sRGB host path, odd-extent bounds, BC6H image view,
 * device teardown, host-decode mapping). It is opt-in and experimental: verified on
 * software-Vulkan emulators only, not on an Adreno phone. It is the stock package with only
 * libvulkan_wrapper.so replaced: the patched build was linked against, and qualified with,
 * the stock package's adrenotools and hook libraries, so it does not use Leegao's hook path.
 */
enum class WrapperVariant(val id: String, val stateName: String, val asset: String, val usesLeegaoHooks: Boolean) {
    STOCK("wrapper", "stock", "graphics_driver/wrapper.tzst", false),
    LEEGAO("wrapper-leegao", "leegao", "graphics_driver/wrapper-leegao.tzst", true),
    GAMENATIVE("wrapper-gamenative", "gamenative", "graphics_driver/wrapper-gamenative.tzst", false),
    VAPOR("wrapper-vapor", "vapor", "graphics_driver/wrapper-vapor.tzst", false);

    /** Marker content; a different variant or app version forces re-extraction. */
    fun state(versionCode: Int): String = "$stateName:$versionCode"

    companion object {
        /** Unknown or empty ids keep the existing behaviour: the stock wrapper. */
        @JvmStatic fun forDriver(graphicsDriver: String?): WrapperVariant =
            entries.firstOrNull { it.id == graphicsDriver } ?: STOCK
    }
}
