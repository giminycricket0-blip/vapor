package com.winlator.cmod.feature.stores.steam.utils

/** Checks the existing launch settings before any Steam game files or processes are touched. */
object SteamLaunchRequirements {
    @JvmStatic
    fun requireGenuineClient(
        genuineClientEnabled: Boolean,
        coldClientEnabled: Boolean,
        username: String?,
        refreshToken: String?,
        steamId: Long,
    ) {
        check(genuineClientEnabled && !coldClientEnabled) {
            "Vapor requires the genuine Steam client. Enable Steam Launcher and turn off ColdClient in the game settings."
        }
        check(!username.isNullOrBlank() && !refreshToken.isNullOrBlank() && steamId > 0) {
            "Sign in to Steam in Vapor before starting this game."
        }
    }
}
