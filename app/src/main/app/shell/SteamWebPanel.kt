package com.winlator.cmod.app.shell

import android.graphics.Bitmap
import android.content.Context
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.cmod.R
import com.winlator.cmod.feature.stores.steam.service.SteamWebSession
import com.winlator.cmod.feature.stores.steam.service.SteamService
import com.winlator.cmod.feature.stores.steam.utils.PrefManager
import com.winlator.cmod.shared.ui.focus.controllerFocusGlow
import com.winlator.cmod.shared.ui.focus.controllerMenuInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

internal enum class SteamWebAvailability { SIGNED_OUT, IN_GAME, OFFLINE, READY }

/** Official web features; the native catalog/download path remains separate and owns installations. */
@Composable
internal fun SteamWebPanel(url: String, signedIn: Boolean, onConnect: () -> Unit,
    onInstall: (Int) -> Unit, onClose: () -> Unit) {
    val connected by SteamService.isConnectedFlow.collectAsState()
    val loggedOn by SteamService.wnLoggedOnFlow.collectAsState()
    val service = SteamService.instance
    val noService = remember { MutableStateFlow(false) }
    val handoff by (service?.bionicHandoffFlow ?: noService).collectAsState()
    val availability = when {
        handoff -> SteamWebAvailability.IN_GAME
        !signedIn -> SteamWebAvailability.SIGNED_OUT
        !connected || !loggedOn -> SteamWebAvailability.OFFLINE
        else -> SteamWebAvailability.READY
    }
    val accountId = if (signedIn) PrefManager.steamUserSteamId64 else 0L
    key(url, accountId, availability == SteamWebAvailability.SIGNED_OUT) {
        SteamWebPanelContent(url, availability, onConnect, onInstall, onClose)
    }
}

@Composable
internal fun SteamWebPanelContent(
    url: String,
    availability: SteamWebAvailability,
    onConnect: () -> Unit,
    onInstall: (Int) -> Unit,
    onClose: () -> Unit,
    connect: suspend () -> Unit = { SteamWebSession.connect() },
    createWebView: (Context) -> WebView = { WebView(it) },
) {
    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var host by remember { mutableStateOf(android.net.Uri.parse(url).host.orEmpty()) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    val title = stringResource(when {
        url.contains("/wishlist") -> R.string.vapor_steam_wishlist
        url.contains("/guides") -> R.string.vapor_steam_guides
        url.contains("/workshop") -> R.string.vapor_steam_workshop
        android.net.Uri.parse(url).host == "steamcommunity.com" -> R.string.vapor_steam_community
        else -> R.string.vapor_steam_web_store
    })
    val uriHandler = LocalUriHandler.current
    val currentInstall by rememberUpdatedState(onInstall)
    val signedIn = availability != SteamWebAvailability.SIGNED_OUT
    val refresh: () -> Unit = { ready = false; error = null; retry++ }
    LaunchedEffect(url, retry, availability) {
        if (!signedIn) { ready = false; error = null; return@LaunchedEffect }
        // A loaded page uses its web session independently. Do not reload a checkout on a CM reconnect.
        if (ready || availability != SteamWebAvailability.READY) return@LaunchedEffect
        error = null; progress = 0; canGoBack = false
        try {
            check(SteamWebSession.isSteamPage(url))
            connect()
            ready = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = R.string.vapor_steam_web_auth_failed }
    }
    Dialog(onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler {
            if (webView?.canGoBack() == true) webView?.goBack() else onClose()
        }
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            Surface(Modifier.padding(if (maxWidth < 600.dp) 10.dp else 24.dp)
                .widthIn(max = 1100.dp).fillMaxWidth().fillMaxHeight(.94f)
                .controllerMenuInput(onDismiss = {
                    if (webView?.canGoBack() == true) webView?.goBack() else onClose()
                }), shape = RoundedCornerShape(20.dp), tonalElevation = 8.dp) {
                Column(Modifier.imePadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { webView?.goBack() }, enabled = canGoBack,
                            modifier = Modifier.controllerFocusGlow()) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_ui_back))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(host, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = refresh,
                            enabled = availability == SteamWebAvailability.READY && (ready || error != null),
                            modifier = Modifier.controllerFocusGlow()) {
                            Icon(Icons.Outlined.Refresh, stringResource(R.string.vapor_steam_web_refresh))
                        }
                        IconButton(onClick = onClose, modifier = Modifier.controllerFocusGlow()) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.common_ui_close))
                        }
                    }
                    HorizontalDivider()
                    if (ready && progress < 100 && error == null) {
                        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                    if (!signedIn) {
                        Column(Modifier.fillMaxWidth().weight(1f).padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.vapor_steam_web_connect))
                            Button(onClick = onConnect, modifier = Modifier.controllerFocusGlow()) {
                                Text(stringResource(R.string.vapor_connect_steam))
                            }
                        }
                    } else if (!ready && availability != SteamWebAvailability.READY) {
                        Column(Modifier.fillMaxWidth().weight(1f).padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(if (availability == SteamWebAvailability.IN_GAME)
                                R.string.vapor_steam_web_in_game else R.string.vapor_steam_web_offline))
                        }
                    } else if (error != null) {
                        Column(Modifier.fillMaxWidth().weight(1f).padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(error!!), color = MaterialTheme.colorScheme.error)
                            Button(onClick = refresh,
                                enabled = availability == SteamWebAvailability.READY,
                                modifier = Modifier.controllerFocusGlow()) { Text(stringResource(R.string.steam_login_retry)) }
                        }
                    } else if (!ready) Column(Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.vapor_steam_web_loading))
                    }
                    else key(retry) { AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { context ->
                        createWebView(context).apply {
                            webView = this
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.setSupportMultipleWindows(false)
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                            }
                            // WebView supplies normal keyboard/D-pad focus. No credential-bearing JS bridge.
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val next = request.url.toString()
                                    val installId = SteamWebSession.installAppId(next)
                                    if (installId != null) { currentInstall(installId); return true }
                                    if (SteamWebSession.isSteamPage(next)) return false
                                    if (request.isForMainFrame && request.hasGesture() && request.url.scheme == "https") {
                                        runCatching { uriHandler.openUri(next) }
                                    }
                                    return true
                                }
                                override fun onPageFinished(view: WebView, finished: String) {
                                    host = android.net.Uri.parse(finished).host.orEmpty()
                                    canGoBack = view.canGoBack()
                                }
                                override fun onPageStarted(view: WebView, started: String, favicon: Bitmap?) {
                                    progress = 0
                                    host = android.net.Uri.parse(started).host.orEmpty()
                                }
                                override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                                    if (request.isForMainFrame) error = R.string.vapor_steam_web_page_failed
                                }
                            }
                            loadUrl(url)
                        }
                    }, onRelease = { it.stopLoading(); it.destroy(); if (webView === it) webView = null }) }
                }
            }
        }
    }
}
