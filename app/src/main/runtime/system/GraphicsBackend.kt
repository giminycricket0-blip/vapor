package com.winlator.cmod.runtime.system

/**
 * A graphics driver stack Vapor can run games on.
 *
 * Everything above this layer is already vendor-agnostic: the Vulkan wrapper, DXVK,
 * vkd3d-proton, the capability floors and the retry router do not care whose GPU it is. Only
 * the driver differs, and the differences are structural rather than cosmetic:
 *
 *   | family  | Vulkan driver            | kernel interface | shipped |
 *   |---------|--------------------------|------------------|---------|
 *   | Adreno  | Turnip (mesa freedreno)  | KGSL             | yes     |
 *   | Mali    | PanVK (mesa panfrost)    | kbase            | no      |
 *   | Xclipse | Samsung/AMD driver       | vendor           | no      |
 *
 * This interface exists so "support another chipset" means writing one of these, instead of
 * threading vendor checks through the graphics path. It deliberately describes a stack and
 * not a policy: which route to attempt for a specific game stays with the capability floors
 * and AutomaticGraphics.
 */
internal interface GraphicsBackend {
    /** Stable id used in logs and diagnostics. */
    val id: String

    val family: GpuFamily

    /** Library name the driver is loaded as inside the runtime. */
    val driverLibrary: String

    /**
     * Whether this build actually carries the driver. An unshipped backend can still be
     * described, which is what lets the app say "Mali is not supported yet" instead of
     * failing later with a graphics error.
     */
    val shipped: Boolean

    /** Where the driver comes from when it is shipped; null otherwise. */
    val assetPath: String?
}

/** The only backend Vapor ships today: Mesa freedreno over KGSL. */
internal object TurnipBackend : GraphicsBackend {
    override val id = "turnip"
    override val family = GpuFamily.ADRENO
    override val driverLibrary = "libvulkan_freedreno.so"
    override val shipped = true
    override val assetPath = "linuxfs/usr/lib/libvulkan_freedreno.so"
}

/**
 * ARM Mali via Mesa panfrost. Not shipped: this needs the PanVK driver over the kbase kernel
 * interface, which is a different stack from Turnip/KGSL, not a patch to it.
 */
internal object PanVkBackend : GraphicsBackend {
    override val id = "panvk"
    override val family = GpuFamily.MALI
    override val driverLibrary = "libvulkan_panfrost.so"
    override val shipped = false
    override val assetPath = null
}

/** Samsung Xclipse (AMD RDNA-based). Not shipped. */
internal object XclipseBackend : GraphicsBackend {
    override val id = "xclipse"
    override val family = GpuFamily.XCLIPSE
    override val driverLibrary = "libvulkan_xclipse.so"
    override val shipped = false
    override val assetPath = null
}

/** A GPU Vapor has no backend for at all. */
internal object UnsupportedBackend : GraphicsBackend {
    override val id = "unsupported"
    override val family = GpuFamily.UNKNOWN
    override val driverLibrary = ""
    override val shipped = false
    override val assetPath = null
}

internal object GraphicsBackends {
    val all: List<GraphicsBackend> = listOf(TurnipBackend, PanVkBackend, XclipseBackend)

    /** The shipped backend for a family, or [UnsupportedBackend] when there is none. */
    fun forFamily(family: GpuFamily): GraphicsBackend =
        all.firstOrNull { it.family == family && it.shipped } ?: UnsupportedBackend
}
