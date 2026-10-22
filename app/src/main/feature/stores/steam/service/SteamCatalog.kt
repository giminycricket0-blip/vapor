package com.winlator.cmod.feature.stores.steam.service

import com.winlator.cmod.feature.stores.steam.utils.Net
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

/** Public store metadata, never an ownership or installation record. */
internal data class SteamCatalogItem(
    val appId: Int,
    val name: String,
    val isDemo: Boolean,
    val isFree: Boolean,
    val price: String,
    val artwork: String?,
    val description: String,
    val developer: String,
    val windows: Boolean?,
    val comingSoon: Boolean,
    val screenshots: List<String>,
    val demoAppIds: List<Int> = emptyList(),
)

internal enum class SteamCatalogSection { POPULAR, ALL, DEMOS, FREE, DEALS }
internal enum class SteamCatalogGenre(val tagId: Int?) {
    ALL(null), ACTION(19), ADVENTURE(21), RPG(122), STRATEGY(9), PUZZLE(1664), PLATFORMER(1625),
}

internal data class SteamCatalogPage(
    val items: List<SteamCatalogItem>,
    val start: Int,
    val count: Int,
    val total: Int,
    val suggestions: List<String> = emptyList(),
    /** Set when the exact search found nothing and these are results for [matchedTerm] instead. */
    val relaxedFrom: String? = null,
    val matchedTerm: String? = null,
) {
    // Advance by Steam's result count, including entries we cannot display.
    val nextStart: Int? get() = (start + count).takeIf { count > 0 && it < total }
}

/**
 * Read-only Steam store services. These endpoints are used by Steam's storefront
 * but are not a stable public SDK contract. No account token or web cookie is sent.
 * Purchases and license grants remain Steam's responsibility; installations use
 * the existing authenticated SteamService path and its owned SteamApp records.
 */
internal object SteamCatalog {
    const val PAGE_SIZE = 24
    const val SEARCH_LIMIT = 50
    private const val MAX_RELAXED = 6
    private const val ASSETS = "https://shared.fastly.steamstatic.com/store_item_assets/"
    // Steam rejects a query without a country. This public catalog uses the device
    // region, explicitly labeled in the UI; it is not the signed-in account's region.
    val countryCode: String
        get() = Locale.getDefault().country.takeIf { it in Locale.getISOCountries() } ?: "US"

    /** Reconnect with the existing HTTP client; idle sockets can still refer to the old network. */
    fun discardIdleConnections() {
        val pool = Net.http.connectionPool
        timber.log.Timber.tag("SteamCatalog").d("Discarding idle connections count=%s", pool.idleConnectionCount())
        pool.evictAll()
    }

    suspend fun browse(section: SteamCatalogSection, start: Int, search: String, genre: SteamCatalogGenre = SteamCatalogGenre.ALL): SteamCatalogPage {
        val searching = search.isNotBlank()
        val method = if (searching) "SearchSuggestions" else "Query"
        val page = parsePage(request("IStoreQueryService/$method/v1/", query(section, start, search, genre)), searching)
        if (!searching || page.items.isNotEmpty()) return page
        // SearchSuggestions matches titles, so descriptive or misspelled phrases ("open world car",
        // "hollw nite") return nothing. Try a few shorter forms and show the first that finds games,
        // clearly labelled as closest matches. A fallback failure keeps the honest empty result.
        for (candidate in relaxedSearchTerms(search)) {
            val relaxed = try {
                parsePage(request("IStoreQueryService/SearchSuggestions/v1/", query(section, 0, candidate, genre)), true)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { break }
            if (relaxed.items.isNotEmpty())
                return relaxed.copy(suggestions = emptyList(), relaxedFrom = search.trim(), matchedTerm = candidate)
        }
        return page
    }

    private val STOP_WORDS = setOf("a", "an", "the", "of", "and", "or", "with", "for", "to", "in", "on", "game", "games", "play")

    /**
     * Shorter searches to try when an exact search finds nothing, most specific first, at most
     * [MAX_RELAXED] of them: contiguous word groups from longest to shortest (left first), then
     * single significant words (longest first); a single word is shortened by up to three letters.
     */
    internal fun relaxedSearchTerms(search: String): List<String> {
        val words = search.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
        val out = LinkedHashSet<String>()
        if (words.size >= 2) {
            for (length in words.size - 1 downTo 2) for (from in 0..words.size - length) {
                val group = words.subList(from, from + length)
                if (group.any { it !in STOP_WORDS }) out += group.joinToString(" ")
            }
            words.filter { it.length >= 3 && it !in STOP_WORDS }.sortedByDescending { it.length }.forEach { out += it }
        } else if (words.size == 1 && words[0].length >= 5) {
            val word = words[0]
            for (cut in 1..3) if (word.length - cut >= 4) out += word.dropLast(cut)
        }
        return out.take(MAX_RELAXED)
    }

    suspend fun details(appId: Int): SteamCatalogItem {
        require(appId > 0)
        val payload = buildJsonObject {
            putJsonArray("ids") { add(buildJsonObject { put("appid", appId) }) }
            put("context", context())
            put("data_request", dataRequest(screenshots = true))
        }
        val response = response(request("IStoreBrowseService/GetItems/v1/", payload))
        return response["store_items"]?.jsonArray?.mapNotNull { parseItem(it.jsonObject) }
            ?.firstOrNull { it.appId == appId } ?: throw IOException("Store details unavailable")
    }

    internal fun query(section: SteamCatalogSection, start: Int, search: String, genre: SteamCatalogGenre = SteamCatalogGenre.ALL): JsonObject {
        require(start >= 0)
        val searching = search.isNotBlank()
        val filters = buildJsonObject {
            putJsonObject("type_filters") {
                // include_apps=true would also admit DLC/software, overriding these types.
                put("include_games", section != SteamCatalogSection.DEMOS)
                put("include_demos", section == SteamCatalogSection.DEMOS || searching)
            }
            if (!searching && section == SteamCatalogSection.POPULAR) {
                put("global_top_n_sellers", 100)
                put("released_only", true)
            }
            genre.tagId?.let { tag ->
                putJsonArray("tagids_must_match") {
                    add(buildJsonObject { putJsonArray("tagids") { add(tag) } })
                }
            }
            if (section == SteamCatalogSection.FREE) putJsonObject("price_filters") { put("only_free_items", true) }
            if (section == SteamCatalogSection.DEALS) putJsonObject("price_filters") { put("min_discount_percent", 1) }
        }
        return buildJsonObject {
            put("context", context())
            put("data_request", dataRequest())
            if (searching) {
                put("search_term", search.trim())
                put("max_results", SEARCH_LIMIT)
                put("use_spellcheck", true)
                put("filters", filters)
            } else {
                putJsonObject("query") {
                    put("start", start)
                    put("count", PAGE_SIZE)
                    put("sort", 0) // Steam default order; never label this a numbered sales ranking.
                    put("filters", filters)
                }
            }
        }
    }

    private fun context() = buildJsonObject {
        put("language", "english")
        put("country_code", countryCode)
    }

    private fun dataRequest(screenshots: Boolean = false) = buildJsonObject {
        put("include_assets", true)
        put("include_basic_info", true)
        put("include_platforms", true)
        put("include_release", true)
        put("include_screenshots", screenshots)
    }

    internal fun parsePage(json: String, searching: Boolean = false): SteamCatalogPage {
        val response = response(json)
        val meta = response["metadata"]?.jsonObject ?: throw IOException("Missing store results")
        val start = meta.int("start") ?: 0
        val reportedCount = meta.int("count") ?: throw IOException("Missing store result count")
        val total = meta.int("total_matching_records") ?: throw IOException("Missing store total")
        val items = response["store_items"]?.jsonArray?.mapNotNull { parseItem(it.jsonObject) }.orEmpty().distinctBy { it.appId }
        // Search returns at most SEARCH_LIMIT items, but Steam's count can be larger (100 for
        // "rpg"). That is not a transport error, so search reports what it actually returned;
        // browse pages keep the strict check because their count drives the next offset.
        val count = if (searching) items.size else reportedCount
        if (start < 0 || count < 0 || total < 0 || (!searching && count > SEARCH_LIMIT))
            throw IOException("Invalid store pagination")
        val suggestions = (meta["spellcheck_suggestions"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
            .distinct().take(5)
        return SteamCatalogPage(items, start, count, if (searching) maxOf(total, items.size) else total, suggestions)
    }

    private fun response(json: String): JsonObject =
        Json.parseToJsonElement(json).jsonObject["response"]?.jsonObject ?: throw IOException("Missing store response")

    internal fun parseItem(item: JsonObject): SteamCatalogItem? {
        if (item.int("item_type") != 0 || item.int("success") != 1 || item.bool("visible") != true) return null
        val id = item.int("appid") ?: item.int("id") ?: return null
        val name = item.text("name")
        val type = item.int("type") ?: 0
        if (id <= 0 || name.isBlank() || type !in listOf(0, 1)) return null
        val assets = item["assets"] as? JsonObject
        val filename = assets?.text("header").orEmpty().ifBlank { assets?.text("main_capsule").orEmpty() }
        val assetPath = assets?.text("asset_url_format").orEmpty().replace("\${FILENAME}", filename)
        val basic = item["basic_info"] as? JsonObject
        val shots = (item["screenshots"] as? JsonObject)?.get("all_ages_screenshots") as? JsonArray
        val related = item["related_items"] as? JsonObject
        val demos = listOf("demo_appid", "standalone_demo_appid").flatMap { key ->
            (related?.get(key) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }
        } + listOf("demos", "standalone_demos").flatMap { key ->
            (related?.get(key) as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.int("appid") }
        }
        return SteamCatalogItem(
            appId = id, name = name, isDemo = type == 1, isFree = item.bool("is_free") == true,
            price = (item["best_purchase_option"] as? JsonObject)?.text("formatted_final_price").orEmpty(),
            artwork = assetPath.takeIf { filename.isNotBlank() && it.startsWith("steam/apps/$id/") }?.let { ASSETS + it },
            description = basic?.text("short_description").orEmpty(),
            developer = (basic?.get("developers") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.text("name") }?.joinToString().orEmpty(),
            windows = (item["platforms"] as? JsonObject)?.bool("windows"),
            comingSoon = (item["release"] as? JsonObject)?.bool("is_coming_soon") == true,
            screenshots = shots.orEmpty().mapNotNull { (it as? JsonObject)?.text("filename") }
                .filter { it.startsWith("steam/apps/$id/") }.take(6).map { ASSETS + it },
            demoAppIds = demos.filter { it > 0 && it != id }.distinct(),
        )
    }

    private suspend fun request(path: String, payload: JsonObject): String = suspendCancellableCoroutine { continuation ->
        val url = "https://api.steampowered.com/$path".toHttpUrl().newBuilder()
            .addQueryParameter("input_json", payload.toString()).build()
        val call = Net.http.newCall(Request.Builder().url(url).build())
        call.timeout().timeout(20, TimeUnit.SECONDS)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!it.isSuccessful || it.header("x-eresult")?.let { result -> result != "1" } == true)
                            throw IOException("Steam store request failed")
                        val body = it.body?.string() ?: throw IOException("Empty store response")
                        if (continuation.isActive) continuation.resume(body)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
        })
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.int(key: String) = (get(key) as? JsonPrimitive)?.intOrNull
    private fun JsonObject.bool(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull
}
