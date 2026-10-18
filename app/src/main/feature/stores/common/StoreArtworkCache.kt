package com.winlator.cmod.feature.stores.common

import android.content.Context
import com.winlator.cmod.feature.stores.epic.data.EpicGame
import com.winlator.cmod.feature.stores.gog.data.GOGGame
import com.winlator.cmod.feature.stores.steam.data.SteamApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object StoreArtworkCache {
    private const val ROOT_DIR = "library_artwork_cache"
    private const val TAG = "StoreArtworkCache"
    private const val MAX_CONCURRENT_DOWNLOADS = 4

    private val client: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(MAX_CONCURRENT_DOWNLOADS, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .build()
    }

    data class ArtworkRef(
        val store: String,
        val gameId: String,
        val slot: String,
        val url: String,
    )

    fun imageModel(
        context: Context,
        ref: ArtworkRef?,
    ): Any? {
        if (ref == null || ref.url.isBlank()) return null
        val file = cacheFile(context, ref)
        return if (file.exists() && file.length() > 0L) file else ref.url
    }

    suspend fun cacheAll(
        context: Context,
        refs: Collection<ArtworkRef>,
    ): Boolean = withContext(Dispatchers.IO) {
        val distinctRefs =
            refs
                .filter { it.url.isNotBlank() && it.url.startsWith("http", ignoreCase = true) }
                .distinctBy { "${it.store}:${it.gameId}:${it.slot}:${it.url}" }
        if (distinctRefs.isEmpty()) return@withContext false

        // A bounded number of workers, rather than a suspended coroutine for every library image.
        val next = AtomicInteger()
        List(minOf(MAX_CONCURRENT_DOWNLOADS, distinctRefs.size)) {
            async {
                var cachedAny = false
                while (true) {
                    ensureActive()
                    val ref = distinctRefs.getOrNull(next.getAndIncrement()) ?: break
                    if (cache(context, ref)) cachedAny = true
                }
                cachedAny
            }
        }.awaitAll().any { it }
    }

    fun deleteGame(
        context: Context,
        store: String,
        gameId: String,
    ): Boolean {
        val dir = gameDir(context, store, gameId)
        if (!dir.exists()) return true
        return runCatching { dir.deleteRecursively() }
            .onFailure { Timber.tag(TAG).w(it, "Failed to delete artwork cache for %s/%s", store, gameId) }
            .getOrDefault(false)
    }

    fun deleteSlots(
        context: Context,
        store: String,
        gameId: String,
        slots: Collection<String>,
    ): Boolean {
        if (slots.isEmpty()) return false
        val dir = gameDir(context, store, gameId)
        if (!dir.exists()) return false

        val prefixes = slots.map { "${safeName(it)}_" }.toSet()
        return runCatching {
            val files =
                dir.listFiles()
                ?.filter { file -> file.isFile && prefixes.any { prefix -> file.name.startsWith(prefix) } }
                .orEmpty()
            files.isNotEmpty() && files.all { it.delete() || !it.exists() }
        }.onFailure {
            Timber.tag(TAG).w(it, "Failed to delete artwork cache slots for %s/%s", store, gameId)
        }.getOrDefault(false)
    }

    fun steamRefs(app: SteamApp): List<ArtworkRef> {
        val refs =
            mutableListOf(
                ArtworkRef("steam", app.id.toString(), "small_capsule", app.getSmallCapsuleUrl()),
                ArtworkRef("steam", app.id.toString(), "header", app.getHeaderImageUrl()),
                ArtworkRef("steam", app.id.toString(), "capsule", app.getCapsuleUrl()),
                ArtworkRef("steam", app.id.toString(), "library_capsule", app.getLibraryCapsuleUrl()),
                ArtworkRef("steam", app.id.toString(), "hero", app.getHeroUrl()),
                ArtworkRef("steam", app.id.toString(), "logo", app.getLogoUrl()),
            )
        if (app.iconHash.isNotBlank()) refs += ArtworkRef("steam", app.id.toString(), "icon", app.iconUrl)
        if (app.logoHash.isNotBlank()) refs += ArtworkRef("steam", app.id.toString(), "legacy_logo", app.logoUrl)
        if (app.logoSmallHash.isNotBlank()) refs += ArtworkRef("steam", app.id.toString(), "legacy_logo_small", app.logoSmallUrl)
        return refs.distinctBy { "${it.slot}:${it.url}" }
    }

    fun steamRef(
        app: SteamApp,
        slot: String,
        url: String,
    ): ArtworkRef = ArtworkRef("steam", app.id.toString(), slot, url)

    fun libraryRefs(
        app: SteamApp,
        gogGame: GOGGame?,
        epicGame: EpicGame?,
    ): List<ArtworkRef> =
        when {
            gogGame != null -> gogRefs(gogGame)
            epicGame != null -> epicRefs(epicGame)
            app.id < 0 -> emptyList()
            else -> steamRefs(app)
        }

    fun primaryRef(
        app: SteamApp,
        gogGame: GOGGame?,
        epicGame: EpicGame?,
        useLibraryCapsule: Boolean,
        listMode: Boolean,
        preferWide: Boolean = false,
    ): ArtworkRef? =
        when {
            gogGame != null -> gogPrimaryRef(gogGame, preferWide)
            epicGame != null -> epicPrimaryRef(epicGame, preferWide)
            app.id < 0 -> null
            else -> {
                val slot: String
                val url: String
                when {
                    listMode -> {
                        slot = "small_capsule"
                        url = app.getSmallCapsuleUrl()
                    }
                    useLibraryCapsule -> {
                        slot = "library_capsule"
                        url = app.getLibraryCapsuleUrl()
                    }
                    preferWide -> {
                        slot = "header"
                        url = app.getHeaderImageUrl()
                    }
                    else -> {
                        slot = "capsule"
                        url = app.getCapsuleUrl()
                    }
                }
                steamRef(app, slot, url)
            }
        }

    fun heroRef(
        app: SteamApp,
        gogGame: GOGGame?,
        epicGame: EpicGame?,
    ): ArtworkRef? =
        when {
            gogGame != null -> gogHeroRef(gogGame)
            epicGame != null -> epicHeroRef(epicGame)
            app.id < 0 -> null
            else -> steamRef(app, "hero", app.getHeroUrl())
        }

    fun epicRefs(game: EpicGame): List<ArtworkRef> =
        listOf(
            ArtworkRef("epic", game.id.toString(), "cover", game.artCover),
            ArtworkRef("epic", game.id.toString(), "square", game.artSquare),
            ArtworkRef("epic", game.id.toString(), "logo", game.artLogo),
            ArtworkRef("epic", game.id.toString(), "hero", game.artPortrait),
        ).filter { it.url.isNotBlank() }

    fun epicPrimaryRef(
        game: EpicGame,
        preferWide: Boolean = false,
    ): ArtworkRef? =
        when {
            preferWide && game.artPortrait.isNotBlank() -> ArtworkRef("epic", game.id.toString(), "hero", game.artPortrait)
            game.artCover.isNotBlank() -> ArtworkRef("epic", game.id.toString(), "cover", game.artCover)
            game.artSquare.isNotBlank() -> ArtworkRef("epic", game.id.toString(), "square", game.artSquare)
            game.artLogo.isNotBlank() -> ArtworkRef("epic", game.id.toString(), "logo", game.artLogo)
            game.artPortrait.isNotBlank() -> ArtworkRef("epic", game.id.toString(), "hero", game.artPortrait)
            else -> null
        }

    fun epicHeroRef(game: EpicGame): ArtworkRef? =
        when {
            game.artPortrait.isNotBlank() -> ArtworkRef("epic", game.id.toString(), "hero", game.artPortrait)
            else -> epicPrimaryRef(game)
        }

    fun gogRefs(game: GOGGame): List<ArtworkRef> =
        listOf(
            ArtworkRef("gog", game.id, "cover", game.imageUrl),
            ArtworkRef("gog", game.id, "hero", game.heroImageUrl),
            ArtworkRef("gog", game.id, "icon", game.iconUrl),
        ).filter { it.url.isNotBlank() }

    fun gogPrimaryRef(
        game: GOGGame,
        preferWide: Boolean = false,
    ): ArtworkRef? =
        when {
            preferWide && game.heroImageUrl.isNotBlank() -> ArtworkRef("gog", game.id, "hero", game.heroImageUrl)
            game.imageUrl.isNotBlank() -> ArtworkRef("gog", game.id, "cover", game.imageUrl)
            game.iconUrl.isNotBlank() -> ArtworkRef("gog", game.id, "icon", game.iconUrl)
            else -> null
        }

    fun gogHeroRef(game: GOGGame): ArtworkRef? =
        when {
            game.heroImageUrl.isNotBlank() -> ArtworkRef("gog", game.id, "hero", game.heroImageUrl)
            else -> gogPrimaryRef(game)
        }

    private suspend fun cache(
        context: Context,
        ref: ArtworkRef,
    ): Boolean = coroutineScope {
        ensureActive()
        val target = cacheFile(context, ref)
        if (target.exists() && target.length() > 0L) return@coroutineScope false

        val call = runCatching { client.newCall(Request.Builder().url(ref.url).build()) }
            .getOrElse { error ->
                Timber.tag(TAG).d(error, "Invalid artwork URL %s", ref.url)
                return@coroutineScope false
            }
        // Closing the socket unblocks execute/read on lifecycle cancellation. The scope waits
        // for the writer's finally block before a resumed screen can start another prefetch.
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        var temp: File? = null
        try {
            target.parentFile?.mkdirs()
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag(TAG).d("Artwork request failed %s for %s", response.code, ref.url)
                    return@coroutineScope false
                }
                val body = response.body ?: return@coroutineScope false
                val partial = File.createTempFile("${target.name}.", ".tmp", target.parentFile)
                temp = partial
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                ensureActive()
                if (partial.length() == 0L ||
                    (body.contentLength() >= 0L && partial.length() != body.contentLength())
                ) return@coroutineScope false

                // Both paths are in the same app directory. Publish a complete file atomically;
                // a failed/cancelled request must never leave a non-empty partial as cached art.
                if (!partial.renameTo(target)) {
                    throw IOException("Could not publish artwork ${target.name}")
                }
                true
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ensureActive()
            Timber.tag(TAG).d(error, "Failed to cache artwork %s", ref.url)
            false
        } finally {
            cancellation.cancel()
            temp?.delete()
        }
    }

    private fun cacheFile(
        context: Context,
        ref: ArtworkRef,
    ): File {
        val ext = extensionFromUrl(ref.url)
        return File(gameDir(context, ref.store, ref.gameId), "${safeName(ref.slot)}_${sha256(ref.url)}$ext")
    }

    private fun gameDir(
        context: Context,
        store: String,
        gameId: String,
    ): File = File(File(File(context.filesDir, ROOT_DIR), safeName(store)), safeName(gameId))

    private fun extensionFromUrl(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "webp", "gif", "ico" -> ".$ext"
            else -> ".img"
        }
    }

    private fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }
}
