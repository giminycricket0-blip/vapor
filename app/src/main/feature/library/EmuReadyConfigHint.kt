package com.winlator.cmod.feature.library

/**
 * The configuration a community report used, read out of EmuReady's structured fields.
 *
 * This is deliberately a *description*, not a decision: nothing here changes what Vapor
 * launches. It exists so a reader can compare the configuration that evidently worked on
 * someone's device against what Vapor picks automatically, which is the first step toward
 * using community reports as a prior for the per-game choice.
 *
 * Keys are the stable custom-field names EmuReady defines (`dx_wrapper`, `dxvk_version`, ...),
 * never the display labels, which can be reworded.
 */
internal object EmuReadyConfigHint {
    private const val KEY_WRAPPER = "dx_wrapper"
    private const val KEY_DXVK_VERSION = "dxvk_version"
    private const val KEY_DRIVER = "graphics_driver"
    private const val KEY_FPS = "average_fps"
    private const val KEY_RESOLUTION = "resolution"

    data class Hint(
        val dxWrapper: String? = null,
        val dxvkVersion: String? = null,
        val driver: String? = null,
        val fps: String? = null,
        val resolution: String? = null,
    ) {
        /** The DXVK generation, because Vapor ships two and they behave very differently. */
        val dxvkGeneration: String? = when {
            dxvkVersion == null -> null
            dxvkVersion.startsWith("1.") || dxvkVersion.contains("async-1.") -> "legacy (1.x)"
            dxvkVersion.startsWith("2.") || dxvkVersion.startsWith("3.") -> "modern (2.x+)"
            else -> null
        }

        /** A single readable line, or null when nothing useful was reported. */
        fun summary(): String? {
            val parts = mutableListOf<String>()
            dxWrapper?.let { wrapper ->
                val version = dxvkVersion?.takeIf { it.isNotBlank() }
                parts += if (version != null) "$wrapper $version" else wrapper
            }
            driver?.let { parts += it }
            fps?.let { value -> parts += "$value fps" }
            resolution?.let { parts += it }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        }
    }

    fun from(settings: List<EmuReady.Setting>): Hint? {
        if (settings.isEmpty()) return null
        val byName = settings.mapNotNull { setting -> setting.name.takeIf(String::isNotBlank)?.let { it to setting.value } }.toMap()
        val hint =
            Hint(
                dxWrapper = byName[KEY_WRAPPER]?.take(40)?.takeIf(String::isNotBlank),
                dxvkVersion = byName[KEY_DXVK_VERSION]?.take(40)?.takeIf(String::isNotBlank),
                driver = byName[KEY_DRIVER]?.take(60)?.takeIf(String::isNotBlank),
                fps = byName[KEY_FPS]?.trim()?.take(10)?.takeIf { it.toIntOrNull() != null },
                resolution = byName[KEY_RESOLUTION]?.take(24)?.takeIf(String::isNotBlank),
            )
        // A report that only reiterated defaults (empty select fields) carries no signal.
        return hint.takeIf { it.summary() != null }
    }
}
