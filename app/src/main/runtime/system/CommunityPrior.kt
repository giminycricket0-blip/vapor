package com.winlator.cmod.runtime.system

import android.content.Context

/**
 * The DXVK generation a community report used for a game, remembered so the automatic
 * graphics router can try that first.
 *
 * This is a *prior*, not an override. It only reorders combinations the router has already
 * found eligible for this device, so a report recorded on different hardware can change the
 * order of attempts but can never select something that would not run. Explicit per-game
 * settings still win, and a launch that fails simply moves on to the next candidate.
 *
 * Stored as a preference rather than fetched here: the launch path must not do network I/O,
 * so the value is written when the community panel shows a report and only read at launch.
 */
internal object CommunityPrior {
    private const val PREFS = "vapor_community_prior"
    private const val LEGACY = "legacy"
    private const val MODERN = "modern"

    /** Records the generation a report used, or clears it when the report does not say. */
    fun record(context: Context, appId: Int, dxvkGeneration: String?) {
        if (appId <= 0) return
        val value = when {
            dxvkGeneration == null -> null
            dxvkGeneration.startsWith("legacy") -> LEGACY
            dxvkGeneration.startsWith("modern") -> MODERN
            else -> null
        }
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        val key = appId.toString()
        if (value == null) editor.remove(key) else editor.putString(key, value)
        editor.apply()
    }

    /** True when the community reports that this game worked on the legacy DXVK build. */
    fun prefersLegacy(context: Context, appId: Int): Boolean? {
        if (appId <= 0) return null
        return when (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(appId.toString(), null)) {
            LEGACY -> true
            MODERN -> false
            else -> null
        }
    }

    /**
     * Moves combinations matching [preferLegacy] to the front, keeping the router's order
     * within each group. Stable, so without a prior the list is untouched.
     */
    internal fun <T> orderByPrior(
        items: List<T>,
        preferLegacy: Boolean?,
        isLegacy: (T) -> Boolean,
    ): List<T> {
        if (preferLegacy == null) return items
        val (matching, rest) = items.partition { isLegacy(it) == preferLegacy }
        return matching + rest
    }
}
