package com.winlator.cmod.runtime.system

import android.content.Context

/**
 * Which GPU family this device has, and whether Vapor ships the driver stack for it.
 *
 * The product goal is that a user never chooses a driver or sees the graphics layer: the app
 * either runs the game or says plainly that this device cannot. That needs a vendor answer
 * before a launch, because the layers below are not interchangeable:
 *
 *   Adreno  -> Turnip (Mesa freedreno) over KGSL      Vapor ships this
 *   Mali    -> PanVK (Mesa panfrost) over kbase       Vapor does not
 *   Xclipse -> Samsung/AMD Vulkan driver              Vapor does not
 *
 * Saying so up front is better than letting a launch fail with a D3D error that looks like a
 * game or driver bug. This is deliberately about the *vendor stack*, not about whether a
 * particular game runs: that is decided by the capability floors and the retry router.
 */
internal enum class GpuFamily(
    val label: String,
) {
    ADRENO("Adreno"),
    MALI("Mali"),
    XCLIPSE("Xclipse"),
    UNKNOWN("this GPU"),
    ;

    /** Only the Adreno stack is shipped today: Turnip is what the app builds and patches. */
    val shipped get() = this == ADRENO

    companion object {
        /**
         * Classifies a Vulkan renderer string, e.g. "Adreno (TM) 840",
         * "Mali-G615 MC6" or "Xclipse 940". Matching is case-insensitive and tolerant of the
         * vendor prefixes different drivers use.
         */
        fun of(renderer: String?): GpuFamily {
            val text = renderer.orEmpty().lowercase()
            return when {
                text.isBlank() -> UNKNOWN
                "adreno" in text -> ADRENO
                "mali" in text || "immortalis" in text -> MALI
                "xclipse" in text -> XCLIPSE
                else -> UNKNOWN
            }
        }
    }
}

internal object DeviceGraphics {
    /** The family of the device as the default driver sees it. */
    fun family(context: Context): GpuFamily =
        runCatching { GpuFamily.of(GPUInformation.getRenderer(null, context)) }.getOrDefault(GpuFamily.UNKNOWN)

    fun renderer(context: Context): String =
        runCatching { GPUInformation.getRenderer(null, context) }.getOrNull().orEmpty()

    /**
     * A short explanation when this device has no shipped driver stack, or null when it does.
     *
     * Wording matters: it must say the device is not supported rather than implying the user
     * configured something wrongly, and it must not leak the internal layer names.
     */
    fun unsupportedReason(context: Context): String? {
        val family = family(context)
        if (family.shipped) return null
        val renderer = renderer(context).takeIf { it.isNotBlank() }
        return when (family) {
            GpuFamily.MALI ->
                "Vapor does not support Mali GPUs yet. This device has ${renderer ?: "a Mali GPU"}, " +
                    "which needs a different graphics driver that is not included."
            GpuFamily.XCLIPSE ->
                "Vapor does not support Xclipse GPUs yet. This device has ${renderer ?: "an Xclipse GPU"}, " +
                    "which needs a different graphics driver that is not included."
            else ->
                "Vapor does not recognise this device's GPU${if (renderer != null) " ($renderer)" else ""}, " +
                    "so it cannot choose a graphics driver for it."
        }
    }
}
