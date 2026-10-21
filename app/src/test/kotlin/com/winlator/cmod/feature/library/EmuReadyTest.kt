package com.winlator.cmod.feature.library

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrl

class EmuReadyTest {
    private val gameId = "89cf7540-e0b9-4fc0-ab7a-8b7edcd35a8a"
    private val reportId = "78a76cba-63cc-4e19-bdfd-3d29b7c69019"
    private val title = "Half-Life 2"
    private fun game(platform: String = "microsoft_windows", name: String = title, appId: String? = null) = buildJsonObject {
        put("id", gameId); put("title", name); put("status", "APPROVED")
        putJsonObject("system") { put("key", platform) }
        if (appId != null) putJsonObject("metadata") { put("steamAppId", appId) }
    }
    private fun report(platform: String = "microsoft_windows", status: String = "APPROVED", id: String = reportId) = buildJsonObject {
        put("id", id); put("gameId", gameId); put("status", status)
        put("game", game(platform)); put("notes", "Tested at 720p")
        putJsonObject("device") { put("modelName", "Test phone"); putJsonObject("soc") { put("name", "Test SoC") } }
        putJsonObject("emulator") { put("name", "Winlator") }
        putJsonObject("performance") { put("label", "Playable") }
        put("upvoteCount", 3); put("downvoteCount", 1); put("createdAt", "2026-10-21T10:00:00.000Z")
    }
    private fun wrap(data: JsonObject) = buildJsonObject {
        putJsonObject("result") { putJsonObject("data") { put("json", data) } }
    }.toString()
    private fun lookup(game: JsonElement = game(appId = "220")) = wrap(buildJsonObject {
        put("success", true); putJsonArray("results") {
            add(buildJsonObject { put("steamAppId", "220"); put("game", game) })
        }
    })
    private fun search(games: List<JsonObject>) = wrap(buildJsonObject { put("games", JsonArray(games)) })
    private fun listings(rows: List<JsonObject> = listOf(report()), more: Boolean = false) = wrap(buildJsonObject {
        put("listings", JsonArray(rows)); putJsonObject("pagination") { put("hasNextPage", more) }
    })

    @Test fun aSteamLookupCannotTurnAConsolePortIntoAWindowsResult() {
        assertNull(EmuReady.selectGame(game(platform = "nintendo_switch", appId = "220"), 220, title))
    }
    @Test fun explicitDifferentAppIdIsRejectedEvenWhenTheTitleMatches() {
        assertNull(EmuReady.selectGame(game(appId = "400"), 220, title))
    }
    @Test fun fullGameTitleDoesNotMatchADemoTitle() {
        assertNull(EmuReady.selectGame(game(), 220, "$title Demo"))
    }
    @Test fun titleOnlyMatchStaysDistinctFromAnAppIdMatch() {
        assertFalse(EmuReady.selectGame(game(), 220, " HALF-LIFE 2 ")!!.appIdMatch)
        assertTrue(EmuReady.selectGame(game(appId = "220"), 220, title)!!.appIdMatch)
    }
    @Test fun onlyApprovedGamesWithValidSourceIdsAreSelected() {
        assertNull(EmuReady.selectGame(JsonObject(game() + ("status" to JsonPrimitive("PENDING"))), 220, title))
        assertNull(EmuReady.selectGame(JsonObject(game() + ("id" to JsonPrimitive("../../elsewhere"))), 220, title))
    }
    @Test fun rpcErrorsAndInvalidShapesAreNotEmptyCompatibilityResults() {
        for (payload in listOf("{}", "[]", "not json", "{\"error\":{\"message\":\"offline\"}}")) {
            assertThrows(IOException::class.java) { EmuReady.envelope(payload) }
        }
    }
    @Test fun anExactWindowsMatchReadsTheFilteredReportListOnly() = runBlocking {
        val calls = mutableListOf<String>()
        val page = EmuReady.reports(220, title) { method, input ->
            calls += method
            when (method) {
                "games.batchBySteamAppIds" -> lookup()
                "listings.get" -> {
                    assertEquals(JsonArray(listOf(JsonPrimitive(gameId))), input["gameIds"])
                    assertEquals(JsonPrimitive(20), input["limit"])
                    listings(more = true)
                }
                else -> error("Unexpected request: $method")
            }
        }
        assertEquals(listOf("games.batchBySteamAppIds", "listings.get"), calls)
        assertTrue(page.game!!.appIdMatch); assertTrue(page.hasMore)
        assertEquals("Winlator", page.reports.single().emulator)
    }
    @Test fun crossPlatformBatchMatchFallsBackToAUniqueWindowsTitle() = runBlocking {
        val page = EmuReady.reports(220, title) { method, _ -> when (method) {
            "games.batchBySteamAppIds" -> lookup(game(platform = "nintendo_switch"))
            "games.get" -> search(listOf(game(platform = "nintendo_switch"), game()))
            else -> listings()
        } }
        assertEquals(gameId, page.game!!.id); assertFalse(page.game!!.appIdMatch)
    }
    @Test fun ambiguousWindowsTitlesDoNotSelectTheFirstEdition() = runBlocking {
        val duplicate = JsonObject(game() + ("id" to JsonPrimitive("00000000-0000-0000-0000-000000000001")))
        val page = EmuReady.reports(220, title) { method, _ -> when (method) {
            "games.batchBySteamAppIds" -> lookup(JsonNull)
            "games.get" -> search(listOf(game(), duplicate))
            else -> error("Ambiguous title must not request reports")
        } }
        assertNull(page.game); assertTrue(page.reports.isEmpty())
    }
    @Test fun noMatchingEntryAndAnEntryWithoutReportsRemainDistinct() = runBlocking {
        val missing = EmuReady.reports(220, title) { method, _ ->
            if (method == "games.batchBySteamAppIds") lookup(JsonNull) else search(emptyList())
        }
        val empty = EmuReady.reports(220, title) { method, _ ->
            if (method == "games.batchBySteamAppIds") lookup() else listings(emptyList())
        }
        assertNull(missing.game); assertNotNull(empty.game)
        assertTrue(missing.reports.isEmpty()); assertTrue(empty.reports.isEmpty())
    }
    @Test fun lookupResponseMustBelongToTheRequestedSteamAppId() {
        assertThrows(IOException::class.java) { runBlocking {
            EmuReady.reports(220, title) { _, _ -> lookup().replace("\"steamAppId\":\"220\"", "\"steamAppId\":\"400\"") }
        } }
    }
    @Test fun serviceFailureAndCancellationPropagateWithoutAnIncompatibleVerdict() {
        assertThrows(IOException::class.java) { runBlocking { EmuReady.reports(220, title) { _, _ -> throw IOException("timeout") } } }
        assertThrows(CancellationException::class.java) { runBlocking { EmuReady.reports(220, title) { _, _ -> throw CancellationException() } } }
    }
    @Test fun malformedListingsAreNotReportedAsNoReports() {
        for (response in listOf(wrap(buildJsonObject { }), listings(List(21) { report() }))) {
            assertThrows(IOException::class.java) { runBlocking {
                EmuReady.reports(220, title) { method, _ -> if (method == "games.batchBySteamAppIds") lookup() else response }
            } }
        }
    }
    @Test fun reportIdentityStatusAndPlatformMustRemainConsistent() {
        for (bad in listOf(report("nintendo_switch"), report(status = "PENDING"), report(id = "bad/path"))) {
            assertThrows(IOException::class.java) { EmuReady.parseReport(bad, gameId) }
        }
        assertThrows(IOException::class.java) { EmuReady.parseReport(report(), "another-game") }
    }
    @Test fun reportCountsAreVotesRatherThanACompatibilityPercentage() {
        val row = EmuReady.parseReport(report(), gameId)
        assertEquals(3, row.upvotes); assertEquals(1, row.downvotes)
        assertEquals("2026-10-21", row.date)
        assertEquals("https://www.emuready.com/listings/$reportId", row.sourceUrl)
    }
    @Test fun absentDeviceAndVotesStayUnknown() {
        val row = EmuReady.parseReport(JsonObject(report() - "device" - "upvoteCount" - "downvoteCount"), gameId)
        assertEquals("", row.device); assertNull(row.upvotes); assertNull(row.downvotes)
    }
    private fun field(label: String, value: JsonElement) = buildJsonObject {
        putJsonObject("customFieldDefinition") { put("label", label) }; put("value", value)
    }
    @Test fun settingsUseReportedValuesOnlyAndNeverFillExporterDefaults() = runBlocking {
        val row = EmuReady.parseReport(report(), gameId)
        val data = JsonObject(report() + ("customFieldValues" to JsonArray(listOf(
            field("Resolution", JsonPrimitive("1280x720")), field("VSync", JsonPrimitive(false)),
            field("Unknown", JsonNull), field("Blank", JsonPrimitive("")),
            field("Object", buildJsonObject { put("path", "do not execute") }),
        ))))
        val result = EmuReady.settings(row) { method, input ->
            assertEquals("listings.byId", method); assertEquals(JsonPrimitive(reportId), input["id"])
            wrap(data)
        }
        assertEquals(listOf(EmuReady.Setting("Resolution", "1280x720"), EmuReady.Setting("VSync", "false")), result)
    }
    @Test fun settingsMustBelongToTheSameApprovedWindowsReport() {
        val row = EmuReady.parseReport(report(), gameId)
        assertThrows(IOException::class.java) {
            EmuReady.parseDetails(JsonObject(report(id = "00000000-0000-0000-0000-000000000001") + ("customFieldValues" to JsonArray(emptyList()))), row)
        }
    }
    @Test fun emptyRecordedSettingsAreAValidResult() {
        val row = EmuReady.parseReport(report(), gameId)
        assertTrue(EmuReady.parseDetails(JsonObject(report() + ("customFieldValues" to JsonArray(emptyList()))), row).isEmpty())
    }
    @Test fun queryEncodingKeepsTitlesOutOfThePathAndUsesThePublicGetEndpoint() {
        val url = EmuReady.browseUrl("A&B / demo?")
        assertEquals("A&B / demo?", url.toHttpUrl().queryParameter("search"))
        val rpc = EmuReady.requestUrl("games.get", buildJsonObject { put("search", "A&B") })
        assertEquals("www.emuready.com", rpc.host)
        assertEquals("/api/mobile/trpc/games.get", rpc.encodedPath)
        assertEquals("A&B", Json.parseToJsonElement(rpc.queryParameter("input")!!).jsonObject["json"]!!.jsonObject["search"]!!.jsonPrimitive.content)
    }
}
