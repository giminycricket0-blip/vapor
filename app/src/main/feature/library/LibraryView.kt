package com.winlator.cmod.feature.library

import com.winlator.cmod.feature.stores.steam.data.SteamApp
import java.util.Locale

/** A view of the existing ownership and install records; never written to the install cache. */
internal fun libraryGamesForView(
    installed: List<SteamApp>,
    owned: List<SteamApp>,
    installedOnly: Boolean,
    search: String = "",
    hiddenIds: Set<Int> = emptySet(),
): List<SteamApp> {
    // Keep installed games in their existing last-played order, then show the rest alphabetically.
    // Retain the installed row when a title appears in both sources (custom names/artwork included).
    val games = if (installedOnly) installed else
        (installed + owned.sortedBy { it.name.lowercase(Locale.ROOT) }).distinctBy { it.id }
    return games.filter { it.id !in hiddenIds && it.name.contains(search.trim(), ignoreCase = true) }
}

/** Windows sessions sanitize shortcut names; retro sessions can retain the original name. */
internal fun libraryLastOpenedAt(app: SteamApp, playtime: Map<String, *>): Long =
    maxOf(
        (playtime["${app.name}_last_played"] as? Long) ?: 0L,
        (playtime["${app.name.replace(LIBRARY_PLAYTIME_NAME, "")}_last_played"] as? Long) ?: 0L,
    ).coerceAtLeast(0L)

private val LIBRARY_PLAYTIME_NAME = "[^A-Za-z0-9 _-]".toRegex()

/** Input is the existing filtered Library, already ordered by local last-opened time. */
internal fun libraryGamesForHome(games: List<SteamApp>, recentlyOpenedIds: Set<Int>): List<SteamApp> {
    val recent = games.filter { it.id in recentlyOpenedIds }
    return recent.ifEmpty { games }.take(12)
}
