package com.winlator.cmod.feature.library

import com.winlator.cmod.feature.stores.steam.data.SteamApp
import org.junit.Assert.*
import org.junit.Test

class LibraryViewTest {
    private val installed = SteamApp(id = 10, name = "Played recently", gameDir = "/games/ten")
    private val owned = SteamApp(id = 20, name = "Another game", gameDir = "default folder")
    private val custom = SteamApp(id = -5, name = "My local game")

    @Test fun homeUsesActualRecentEntriesInTheirExistingOrder() {
        assertEquals(listOf(custom, installed), libraryGamesForHome(listOf(custom, installed, owned), setOf(10, -5)))
    }

    @Test fun homeWithoutHistoryShowsTheExistingLibraryWithoutInventingHistory() {
        val games = listOf(installed, owned)
        assertEquals(games, libraryGamesForHome(games, emptySet()))
        assertEquals(0L, libraryLastOpenedAt(owned, emptyMap<String, Any>()))
    }

    @Test fun homeCannotBringBackAHiddenOrRemovedGameAndBoundsItsShelf() {
        val games = (1..20).map { SteamApp(id = it, name = "Game $it") }
        assertEquals(games.take(12), libraryGamesForHome(games, (1..25).toSet()))
        assertTrue(libraryGamesForHome(emptyList(), setOf(10)).isEmpty())
        assertEquals(listOf(owned), libraryGamesForHome(listOf(owned), setOf(installed.id)))
    }

    @Test fun recentTimeReadsBothWindowsSanitizedAndRetroOriginalNames() {
        val game = SteamApp(id = -7, name = "Local: game!")
        assertEquals(42L, libraryLastOpenedAt(game, mapOf("Local game_last_played" to 42L)))
        assertEquals(90L, libraryLastOpenedAt(game, mapOf("Local: game!_last_played" to 90L, "Local game_last_played" to 42L)))
    }

    @Test fun invalidHistoryDoesNotMakeAGameRecent() {
        assertEquals(0L, libraryLastOpenedAt(owned, mapOf("Another game_last_played" to -5L)))
        assertEquals(0L, libraryLastOpenedAt(owned, mapOf("Another game_last_played" to "yesterday")))
    }

    @Test fun allGamesIncludesOwnedTitlesAndLocalInstallsWithoutDuplicates() {
        assertEquals(listOf(installed, custom, owned), libraryGamesForView(
            listOf(installed, custom), listOf(owned, installed.copy(name = "Store title")), false,
        ))
    }

    @Test fun owningAGameOrHavingAnInstallFolderNameDoesNotMakeItInstalled() {
        assertEquals(listOf(installed), libraryGamesForView(listOf(installed), listOf(installed, owned), true))
        assertTrue(libraryGamesForView(emptyList(), listOf(owned), true).isEmpty())
    }

    @Test fun newDownloadCompletionMovesTheGameIntoInstalledWithoutDuplicatingAllGames() {
        val ownedGames = listOf(installed, owned)
        assertEquals(1, libraryGamesForView(listOf(installed), ownedGames, true).size)
        val afterDownload = listOf(installed, owned)
        assertEquals(afterDownload, libraryGamesForView(afterDownload, ownedGames, true))
        assertEquals(afterDownload, libraryGamesForView(afterDownload, ownedGames, false))
    }

    @Test fun removingAnInstallKeepsTheOwnedGameAvailableToDownload() {
        assertEquals(listOf(owned), libraryGamesForView(emptyList(), listOf(owned), false))
        assertTrue(libraryGamesForView(emptyList(), listOf(owned), true).isEmpty())
    }

    @Test fun searchAppliesToBothViewsWithoutChangingTheSourceLists() {
        val installs = listOf(installed, custom)
        val account = listOf(owned)
        assertEquals(listOf(custom, owned), libraryGamesForView(installs, account, false, " GAME "))
        assertEquals(listOf(custom), libraryGamesForView(installs, account, true, "GAME"))
        assertEquals(2, installs.size)
        assertEquals(1, account.size)
    }

    @Test fun sharedInstallAliasesStayHiddenInBothViews() {
        for (installedOnly in listOf(true, false)) {
            assertEquals(listOf(installed), libraryGamesForView(
                listOf(installed, custom), listOf(owned), installedOnly, hiddenIds = setOf(custom.id, owned.id),
            ))
        }
    }

    @Test fun uninstalledTitlesSortByNameAfterTheExistingRecentOrder() {
        val alpha = owned.copy(id = 21, name = "alpha")
        val beta = owned.copy(id = 22, name = "Beta")
        assertEquals(listOf(custom, installed, alpha, beta), libraryGamesForView(
            listOf(custom, installed), listOf(beta, alpha), false,
        ))
    }
}
