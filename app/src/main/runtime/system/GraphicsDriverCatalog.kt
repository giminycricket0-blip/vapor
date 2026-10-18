package com.winlator.cmod.runtime.system

import android.content.Context
import android.util.Log
import com.winlator.cmod.R
import com.winlator.cmod.runtime.content.AdrenotoolsManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object GraphicsDriverCatalog {
    private const val TAG = "GraphicsDriverCatalog"
    private val supportCache = ConcurrentHashMap<String, Boolean>()
    private val extensionCache = ConcurrentHashMap<String, List<String>>()

    /** Underlying driver features, not a promise that a particular game/feature level works. */
    data class Capabilities(val values: List<Long>) {
        val api get() = values.getOrElse(0) { 0L }
        val identity get() = values.joinToString(":")
        private fun feature(bit: Int) = (values.getOrElse(4) { 0L } and (1L shl bit)) != 0L
        val textureCompressionBC get() = feature(0)
        val legacyFloor get() = api >= vulkan(1, 1) && feature(7)
        val modernFloor get() = api >= vulkan(1, 3) && feature(1) && feature(4) && feature(9) && feature(7)
        val direct3d12Floor get() = modernFloor && feature(2) && feature(3) && feature(5) && feature(6) && feature(8)

        companion object {
            fun vulkan(major: Int, minor: Int) = (major.toLong() shl 22) or (minor.toLong() shl 12)
        }
    }

    /** No cache: imported payloads and device features are rechecked for each launch. */
    @JvmStatic
    fun probe(context: Context, version: String): Capabilities? {
        if (unavailableReason(context, version) != null) return null
        return try {
            val values = GPUInformation.probeCapabilities(version, context)
            if (values == null || values.size != 7 || values[0] == 0L) null else Capabilities(values.toList())
        } catch (error: Throwable) {
            Log.w(TAG, "Feature probe failed: $version", error)
            null
        }
    }

    @JvmStatic
    fun supportedVersions(context: Context): List<String> {
        val candidates = linkedSetOf("System")
        try {
            candidates.addAll(context.resources.getStringArray(R.array.wrapper_graphics_driver_version_entries))
        } catch (e: Throwable) {
            Log.e(TAG, "Error loading wrapper versions", e)
        }
        try {
            candidates.addAll(AdrenotoolsManager(context).enumarateInstalledDrivers())
        } catch (e: Throwable) {
            Log.w(TAG, "Error loading Adrenotools drivers", e)
        }
        // Keep System available as an explicit recovery choice, even if its probe currently fails.
        return candidates.filter { version ->
            version == "System" || (supportCache[version]
                ?: (unavailableReason(context, version) == null).also { supportCache[version] = it })
        }
    }

    /** Retain an unavailable saved choice so saving unrelated settings cannot replace it. */
    @JvmStatic
    fun selectionEntries(context: Context, savedVersion: String): List<String> {
        val versions = supportedVersions(context)
        return if (savedVersion.isNotBlank() && savedVersion !in versions) versions + savedVersion else versions
    }

    /** Fresh validation for setup and launch; the picker cache is not launch authority. */
    @JvmStatic
    fun unavailableReason(context: Context, version: String?): String? {
        val driver = version?.takeIf { it.isNotBlank() } ?: "System"
        try {
            if (driver != "System") {
                if (!GPUInformation.isAdrenoGPU(context)) {
                    return context.getString(R.string.vapor_driver_requires_adreno, driver)
                }
                val manager = AdrenotoolsManager(context)
                val library = manager.getLibraryName(driver)
                if (library.isBlank() || !File(manager.getDriverPath(driver), library).isFile) {
                    return context.getString(R.string.vapor_driver_missing, driver)
                }
            }
            if (GPUInformation.isDriverSupported(driver, context)) return null
        } catch (error: Throwable) {
            Log.w(TAG, "Driver validation failed: $driver", error)
        }
        return context.getString(
            if (driver == "System") R.string.vapor_driver_system_unavailable else R.string.vapor_driver_unavailable,
            driver,
        )
    }

    @JvmStatic
    fun requireAvailable(context: Context, version: String?) {
        unavailableReason(context, version)?.let { throw IllegalStateException(it) }
    }

    @JvmStatic
    fun extensions(
        context: Context,
        version: String,
    ): List<String> {
        if (version != "System" && unavailableReason(context, version) != null) return emptyList()
        extensionCache[version]?.let { return it }
        val extensions =
            try {
                GPUInformation.enumerateExtensions(version, context)?.toList() ?: emptyList()
            } catch (e: Throwable) {
                Log.e(TAG, "Error loading extensions for $version", e)
                emptyList()
            }
        if (extensions.isNotEmpty()) extensionCache[version] = extensions
        return extensions
    }

    @JvmStatic
    fun invalidate() {
        supportCache.clear()
        extensionCache.clear()
    }
}
