package com.winlator.cmod.feature.library

import com.winlator.cmod.feature.stores.steam.utils.Net
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

/** Public community evidence only. Nothing in this reader writes game settings or uses Steam auth. */
internal object EmuReady {
    private const val ORIGIN = "https://www.emuready.com"
    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val LIMIT = 20
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /** The service answered, but not in a shape this reader understands. Not a network failure. */
    class UnexpectedResponseException(message: String, cause: Throwable? = null) : IOException(message, cause)

    data class Game(val id: String, val title: String, val appIdMatch: Boolean)
    /**
     * One custom field from a report.
     *
     * [name] is the stable key EmuReady defines (for example `dx_wrapper`), which is what any
     * mapping must key on: [label] is display text and can be reworded.
     */
    data class Setting(val label: String, val value: String, val name: String = "")
    data class Report(
        val id: String, val gameId: String, val device: String, val soc: String,
        val emulator: String, val performance: String, val notes: String,
        val upvotes: Int?, val downvotes: Int?, val date: String,
    ) {
        val sourceUrl get() = "$ORIGIN/listings/$id"
    }
    /** [ambiguous] is true when several Windows entries matched, so no single entry was selected. */
    data class Page(val game: Game?, val reports: List<Report>, val hasMore: Boolean = false, val ambiguous: Boolean = false)

    fun browseUrl(title: String) = "$ORIGIN/listings".toHttpUrl().newBuilder()
        .addQueryParameter("search", title.take(200)).build().toString()

    /** Caller is the opened game-page modal; results live only in that page's Compose state. */
    suspend fun reports(
        appId: Int, title: String,
        fetch: suspend (String, JsonObject) -> String = ::request,
    ): Page = withContext(Dispatchers.IO) {
        require(appId > 0 && title.isNotBlank())
        val lookup = envelope(fetch("games.batchBySteamAppIds", buildJsonObject {
            putJsonArray("steamAppIds") { add(appId.toString()) }
            put("maxListingsPerGame", 1)
            put("minimal", false)
        }))
        if (lookup["success"] != JsonPrimitive(true)) unavailable()
        val results = lookup["results"] as? JsonArray ?: unavailable()
        val row = results.mapNotNull { it as? JsonObject }.singleOrNull { it.text("steamAppId") == appId.toString() }
        // A missing batch row is a normal answer for an unindexed game; still try the title search.
        // A present row is keyed by the requested AppID, so its game is that Windows entry even
        // when the entry itself has no linked AppID or its title differs in punctuation/accent.
        var game = row?.let { (it["game"] as? JsonObject)?.let { data -> selectGame(data, appId, title, matchedBySteamAppId = true) } }
        var ambiguous = false
        if (game == null) {
            val search = envelope(fetch("games.get", buildJsonObject {
                put("search", title.take(100)); put("page", 1); put("limit", LIMIT)
            }))
            val games = search["games"] as? JsonArray ?: unavailable()
            val candidates = games.mapNotNull { (it as? JsonObject)?.let { obj -> selectGame(obj, appId, title) } }
            val exact = candidates.filter { it.appIdMatch }
            game = if (exact.size == 1) exact.single() else if (exact.isEmpty()) candidates.singleOrNull() else null
            ambiguous = game == null && candidates.size > 1
        }
        val selected = game ?: return@withContext Page(null, emptyList(), ambiguous = ambiguous)
        val data = envelope(fetch("listings.get", buildJsonObject {
            putJsonArray("gameIds") { add(selected.id) }; put("page", 1); put("limit", LIMIT)
        }))
        val reports = data["listings"] as? JsonArray ?: unavailable()
        if (reports.size > LIMIT) unavailable()
        Page(selected, reports.map { parseReport(it as? JsonObject ?: unavailable(), selected.id) }.distinctBy { it.id },
            data.obj("pagination")?.get("hasNextPage") == JsonPrimitive(true))
    }

    suspend fun settings(report: Report, fetch: suspend (String, JsonObject) -> String = ::request): List<Setting> = withContext(Dispatchers.IO) {
        val data = envelope(fetch("listings.byId", buildJsonObject { put("id", report.id) }))
        parseDetails(data, report)
    }

    internal fun parseDetails(data: JsonObject, report: Report): List<Setting> {
        if (data.text("id") != report.id) unavailable()
        parseReport(data, report.gameId) // same approved Windows report, not an unrelated response
        val fields = data["customFieldValues"] as? JsonArray ?: unavailable()
        if (fields.size > 100) unavailable()
        return fields.mapNotNull { field ->
            val item = field as? JsonObject ?: return@mapNotNull null
            val definition = item.obj("customFieldDefinition") ?: return@mapNotNull null
            val label = definition.text("label").take(100)
            val value = when (val raw = item["value"]) {
                is JsonPrimitive -> if (raw is JsonNull) "" else raw.content
                is JsonArray -> raw.take(20).mapNotNull { (it as? JsonPrimitive)?.takeUnless { v -> v is JsonNull }?.content }.joinToString(", ")
                else -> ""
            }.trim().take(500)
            val name = definition.text("name").take(60)
            if (label.isBlank() || value.isBlank()) null else Setting(label, value, name)
        }.take(60)
    }

    internal fun selectGame(data: JsonObject, appId: Int, title: String, matchedBySteamAppId: Boolean = false): Game? {
        if (data.obj("system")?.text("key") != "microsoft_windows" || data.text("status") != "APPROVED") return null
        val id = data.text("id").takeIf(uuid::matches) ?: return null
        val knownId = data.obj("metadata")?.get("steamAppId")?.let { (it as? JsonPrimitive)?.contentOrNull }
        if (knownId != null && knownId != appId.toString()) return null
        // Title equality is only needed for the title-search fallback. A batch row already
        // identifies the Steam AppID, so accept its Windows game without re-matching the title.
        val exact = matchedBySteamAppId || knownId == appId.toString()
        if (!exact && !data.text("title").trim().equals(title.trim(), ignoreCase = true)) return null
        return Game(id, data.text("title").take(200), exact)
    }

    internal fun parseReport(data: JsonObject, gameId: String): Report {
        val id = data.text("id").takeIf(uuid::matches) ?: unavailable()
        if (data.text("gameId") != gameId || data.obj("game")?.text("id") != gameId || data.text("status") != "APPROVED" ||
            data.obj("game")?.obj("system")?.text("key") != "microsoft_windows") unavailable()
        val device = data.obj("device")
        val label = listOfNotNull(device?.obj("brand")?.text("name"), device?.text("modelName"))
            .filter(String::isNotBlank).joinToString(" ").take(160)
        return Report(id, gameId, label, device?.obj("soc")?.text("name").orEmpty().take(100),
            data.obj("emulator")?.text("name").orEmpty().take(100),
            data.obj("performance")?.text("label").orEmpty().take(100), data.text("notes").take(3000),
            data.number("upvoteCount"), data.number("downvoteCount"),
            data.text("createdAt").take(10).takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }.orEmpty())
    }

    internal fun envelope(payload: String): JsonObject = try {
        Json.parseToJsonElement(payload).jsonObject.obj("result")?.obj("data")?.obj("json") ?: unavailable()
    } catch (e: IllegalArgumentException) { throw UnexpectedResponseException("EmuReady response unavailable", e) }

    internal fun requestUrl(procedure: String, input: JsonObject) = "$ORIGIN/api/mobile/trpc/$procedure".toHttpUrl().newBuilder()
        .addQueryParameter("input", buildJsonObject { put("json", input) }.toString()).build()

    private suspend fun request(procedure: String, input: JsonObject): String = suspendCancellableCoroutine { continuation ->
        // Net has no cookie jar or account interceptor; this request has no auth headers.
        val call = Net.http.newCall(Request.Builder().url(requestUrl(procedure, input)).get().build())
        call.timeout().timeout(10, TimeUnit.SECONDS)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        // An HTTP error means the service did not answer us, not that its payload
                        // was malformed; the UI keeps that wording separate.
                        if (!it.isSuccessful) throw IOException("EmuReady request failed with HTTP ${it.code}")
                        val body = it.body ?: unavailable()
                        if (body.contentLength() > MAX_BYTES) unavailable()
                        val buffer = Buffer()
                        val source = body.source()
                        while (buffer.size <= MAX_BYTES && source.read(buffer, minOf(8192L, MAX_BYTES + 1 - buffer.size)) != -1L) { }
                        if (buffer.size > MAX_BYTES) unavailable()
                        if (continuation.isActive) continuation.resume(buffer.readUtf8())
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
        })
    }

    private fun unavailable(): Nothing = throw UnexpectedResponseException("EmuReady response unavailable")
    private fun JsonObject.obj(key: String) = get(key) as? JsonObject
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
}
