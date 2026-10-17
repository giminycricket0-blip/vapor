package com.winlator.cmod.feature.stores.steam.service

import java.io.IOException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SteamCatalogTest {
    private val game = """{"item_type":0,"id":10,"success":1,"visible":true,"name":"Example","type":0}"""
    private fun page(items: String, start: Int = 0, count: Int = 1, total: Int = 1) =
        """{"response":{"metadata":{"start":$start,"count":$count,"total_matching_records":$total},"store_items":[$items]}}"""

    @Test fun eu3ParentKeepsItsDemoAsASeparateInstallTarget() {
        val parent = game.dropLast(1) + ""","related_items":{"demo_appid":[22150],"demos":[{"appid":22150}]}}"""
        val parsed = SteamCatalog.parsePage(page(parent)).items.single()
        assertEquals(listOf(22150), parsed.demoAppIds)
        assertFalse(parsed.isDemo)
        assertFalse(parsed.isFree)
    }

    @Test fun demosRemainFreeEvenWithoutAPriceAndInvalidLinksAreDropped() {
        val demo = game.replace("\"type\":0", "\"type\":1").dropLast(1) +
            ""","related_items":{"standalone_demo_appid":[0,-1,10,22],"standalone_demos":[{"appid":22},{"appid":23}]}}"""
        val parsed = SteamCatalog.parsePage(page(demo)).items.single()
        assertTrue(parsed.isDemo)
        assertEquals(listOf(22,23), parsed.demoAppIds)
    }

    @Test fun demosDoNotAccidentallyIncludeEverySteamApp() {
        val query = SteamCatalog.query(SteamCatalogSection.DEMOS, 24, "")["query"]!!.jsonObject
        val types = query["filters"]!!.jsonObject["type_filters"]!!.jsonObject
        assertEquals(JsonPrimitive(false), types["include_games"])
        assertEquals(JsonPrimitive(true), types["include_demos"])
        assertNull(types["include_apps"])
        assertEquals(JsonPrimitive(24), query["start"])
    }

    @Test fun searchesAreBoundedAndDoNotInheritThePopularLimit() {
        val query = SteamCatalog.query(SteamCatalogSection.POPULAR, 0, " Portal ")
        assertEquals(JsonPrimitive("Portal"), query["search_term"])
        assertEquals(JsonPrimitive(50), query["max_results"])
        assertNull(query["filters"]!!.jsonObject["global_top_n_sellers"])
        assertNull(query["query"])
    }

    @Test fun everyCatalogRequestIncludesTheCountryRequiredBySteam() {
        val context = SteamCatalog.query(SteamCatalogSection.ALL, 0, "")["context"]!!.jsonObject
        assertEquals(JsonPrimitive(SteamCatalog.countryCode), context["country_code"])
        assertTrue(SteamCatalog.countryCode in java.util.Locale.getISOCountries())
    }

    @Test fun freeSearchPreservesItsPriceFilter() {
        val query = SteamCatalog.query(SteamCatalogSection.FREE, 0, "puzzle")
        assertEquals(JsonPrimitive(true), query["filters"]!!.jsonObject["price_filters"]!!.jsonObject["only_free_items"])
    }

    @Test fun genreAndSaleFiltersApplyToBothBrowseAndSearch() {
        for (term in listOf("", "Portal")) {
            val request = SteamCatalog.query(SteamCatalogSection.DEALS, 0, term, SteamCatalogGenre.PUZZLE)
            val query = request["query"]?.jsonObject ?: request
            val filters = query["filters"]!!.jsonObject
            assertEquals(JsonPrimitive(1), filters["price_filters"]!!.jsonObject["min_discount_percent"])
            assertEquals(JsonPrimitive(1664), filters["tagids_must_match"]!!.jsonArray.single()
                .jsonObject["tagids"]!!.jsonArray.single())
        }
    }

    @Test fun hotGamesUseReleasedTopSellersAndSteamOrdering() {
        val query = SteamCatalog.query(SteamCatalogSection.POPULAR, 0, "")["query"]!!.jsonObject
        assertEquals(JsonPrimitive(0), query["sort"])
        val filters = query["filters"]!!.jsonObject
        assertEquals(JsonPrimitive(100), filters["global_top_n_sellers"])
        assertEquals(JsonPrimitive(true), filters["released_only"])
        assertNull(SteamCatalog.query(SteamCatalogSection.POPULAR, 0, "upcoming game")
            ["filters"]!!.jsonObject["released_only"])
    }

    @Test fun spellingSuggestionsAreBoundedAndDoNotBecomeGames() {
        val input = page(game).replace("\"count\":1", "\"count\":1,\"spellcheck_suggestions\":[\"Portal\",\"\",\"Portal\",\"Helltaker\"]")
        val result = SteamCatalog.parsePage(input)
        assertEquals(listOf("Portal", "Helltaker"), result.suggestions)
        assertEquals(listOf(10), result.items.map { it.appId })
    }

    @Test fun paginationAdvancesPastHiddenAndDuplicateRecords() {
        val hidden = game.replace("\"visible\":true", "\"visible\":false")
        val result = SteamCatalog.parsePage(page("$game,$hidden,$game", start = 24, count = 3, total = 28))
        assertEquals(listOf(10), result.items.map { it.appId })
        assertEquals(27, result.nextStart)
        assertNull(SteamCatalog.parsePage(page(game, start = 27, total = 28)).nextStart)
    }

    @Test fun unknownPriceAndPlatformAreNotReportedAsFreeOrSupported() {
        val item = SteamCatalog.parsePage(page(game)).items.single()
        assertFalse(item.isFree)
        assertEquals("", item.price)
        assertNull(item.windows)
    }

    @Test fun unreleasedGamesUseSteamReleaseMetadata() {
        val unreleased = game.dropLast(1) + ""","release":{"is_coming_soon":true}}"""
        assertTrue(SteamCatalog.parsePage(page(unreleased)).items.single().comingSoon)
        assertFalse(SteamCatalog.parsePage(page(game)).items.single().comingSoon)
    }

    @Test fun failedEntriesDlcAndPackagesAreExcluded() {
        val items = listOf(
            game.replace("\"success\":1", "\"success\":2"),
            game.replace("\"type\":0", "\"type\":4"),
            game.replace("\"item_type\":0", "\"item_type\":1"),
        )
        assertTrue(SteamCatalog.parsePage(page(items.joinToString(), count = 3, total = 3)).items.isEmpty())
    }

    @Test fun invalidAndMissingPaginationFailInsteadOfAppearingEmpty() {
        assertThrows(IOException::class.java) { SteamCatalog.parsePage("""{"response":{}}""") }
        assertThrows(IOException::class.java) { SteamCatalog.parsePage(page(game, count = -1)) }
        assertThrows(IOException::class.java) { SteamCatalog.parsePage(page(game, count = 51)) }
    }
}
