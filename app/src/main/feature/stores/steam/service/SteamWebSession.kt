package com.winlator.cmod.feature.stores.steam.service

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import com.winlator.cmod.feature.stores.steam.utils.PrefManager
import java.io.IOException
import java.net.URI
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Authentication boundary for official Steam pages. No page or JavaScript bridge receives a refresh token. */
internal object SteamWebSession {
    val hosts = setOf("store.steampowered.com", "checkout.steampowered.com", "login.steampowered.com",
        "help.steampowered.com", "steamcommunity.com")

    fun isSteamPage(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.scheme == "https" && parsed.host in hosts && parsed.port == 443 &&
            parsed.username.isEmpty() && parsed.password.isEmpty()
    }

    // A web install link only opens Vapor's native game page; it cannot grant ownership or run a command.
    fun installAppId(url: String): Int? = runCatching {
        val uri = URI(url)
        if (uri.scheme != "steam" || uri.host !in setOf("install", "run") || uri.userInfo != null ||
            uri.port != -1 || uri.query != null || uri.fragment != null) return null
        uri.path.removePrefix("/").toIntOrNull()?.takeIf { it > 0 }
    }.getOrNull()

    fun loginCookie(steamId: Long, accessToken: String): String {
        require(steamId > 0 && accessToken.matches(Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")))
        return "steamLoginSecure=$steamId%7C%7C$accessToken; Path=/; Secure; HttpOnly; SameSite=Lax"
    }

    private val loginCookieNames = setOf("steamLoginSecure", "steamLogin", "sessionid")

    /** Main-thread caller. Reuse the Android connection; never race game/exit ownership by reconnecting here. */
    suspend fun connect(manager: CookieManager = CookieManager.getInstance()) {
        val service = SteamService.instance ?: throw IOException("Steam service unavailable")
        val nativeSession = SteamService.wnSession ?: throw IOException("Steam session unavailable")
        val accountId = PrefManager.steamUserSteamId64
        val refreshToken = PrefManager.refreshToken
        fun checkSession() {
            check(SteamService.instance === service && SteamService.wnSession === nativeSession &&
                SteamService.isRunning && !SteamService.isStopping && SteamService.isLoggedIn &&
                !service.suspendedForBionic && !service.suspendedForBackground &&
                accountId > 0 && PrefManager.steamUserSteamId64 == accountId &&
                PrefManager.refreshToken == refreshToken && refreshToken.isNotBlank() &&
                nativeSession.state() == 3 && nativeSession.steamId() == accountId) {
                "Steam session changed or is unavailable"
            }
        }
        val accessToken = withContext(Dispatchers.IO) {
            checkSession()
            val token = nativeSession.getWebAccessToken(refreshToken) ?: throw IOException("Steam web session unavailable")
            coroutineContext.ensureActive()
            checkSession()
            token
        }
        val cookie = loginCookie(accountId, accessToken)
        val sessionCookie = "sessionid=${UUID.randomUUID().toString().replace("-", "")}; Path=/; Secure; SameSite=Lax"
        suspend fun writeCookie(host: String, value: String) {
            kotlin.coroutines.coroutineContext.ensureActive()
            checkSession()
            suspendCancellableCoroutine<Unit> { continuation ->
                manager.setCookie("https://$host/", value) { accepted ->
                    if (continuation.isActive) {
                        if (accepted) continuation.resume(Unit)
                        else continuation.resumeWithException(IOException("Steam web sign-in could not be prepared. Try again."))
                    }
                }
            }
        }
        // Replace authentication only. Opening another panel must not erase language, age-gate or cart cookies.
        for (host in hosts) {
            for (name in loginCookieNames) {
                for (expired in expirations(host, name)) writeCookie(host, expired)
            }
            writeCookie(host, cookie)
            writeCookie(host, sessionCookie)
        }
        checkSession()
    }

    /** Steam logout must also end the web session, without logging out the other stores. */
    fun clearCookies(manager: CookieManager? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { clearCookies(manager) }
            return
        }
        // A disabled/missing WebView must not prevent native account logout.
        try {
            val cookies = manager ?: CookieManager.getInstance()
            for (host in hosts) {
                // Also expire authentication writes still queued by an interrupted connect().
                val names = (cookies.getCookie("https://$host/").orEmpty().split(';')
                    .map { it.substringBefore('=').trim() } + loginCookieNames)
                    .filter { it.isNotEmpty() }.distinct()
                for (name in names) {
                    for (expired in expirations(host, name)) cookies.setCookie("https://$host/", expired)
                }
            }
        } catch (_: RuntimeException) {
            timber.log.Timber.w("Steam web cookie cleanup unavailable; native logout continues")
        }
    }

    private fun expirations(host: String, name: String): List<String> {
        val expired = "$name=; Max-Age=0; Path=/; Secure"
        return listOf(expired, "$expired; Domain=$host") +
            if (host.endsWith(".steampowered.com")) listOf("$expired; Domain=.steampowered.com") else emptyList()
    }
}
