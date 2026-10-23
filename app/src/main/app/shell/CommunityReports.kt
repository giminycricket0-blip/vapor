package com.winlator.cmod.app.shell

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.cmod.R
import com.winlator.cmod.feature.library.EmuReady
import com.winlator.cmod.feature.library.EmuReadyConfigHint
import com.winlator.cmod.shared.ui.focus.controllerFocusGlow
import com.winlator.cmod.shared.ui.nav.DialogPaneNav
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.PaneNavRegistry
import com.winlator.cmod.shared.ui.nav.paneNavItem
import kotlinx.coroutines.CancellationException

/** One page-local snapshot. Opening the game page or pressing Play makes no community request. */
@Composable
internal fun CommunityReportsButton(
    appId: Int,
    gameTitle: String,
    modifier: Modifier = Modifier,
    load: suspend (Int, String) -> EmuReady.Page = { id, title -> EmuReady.reports(id, title) },
    loadSettings: suspend (EmuReady.Report) -> List<EmuReady.Setting> = { EmuReady.settings(it) },
    onSource: ((String) -> Unit)? = null,
) {
    if (appId <= 0 || gameTitle.isBlank()) return
    key(appId, gameTitle) {
        var open by remember { mutableStateOf(false) }
        var page by remember { mutableStateOf<EmuReady.Page?>(null) }
        var loading by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        var unexpected by remember { mutableStateOf(false) }
        var request by remember { mutableIntStateOf(0) }
        var completed by remember { mutableIntStateOf(-1) }
        // The snapshot survives closing this modal, but never another game or process.
        LaunchedEffect(open, request) {
            if (!open || request == completed) return@LaunchedEffect
            loading = true
            failed = false
            unexpected = false
            try {
                page = load(appId, gameTitle)
                completed = request
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: EmuReady.UnexpectedResponseException) { unexpected = true; completed = request }
            catch (_: Exception) { failed = true; completed = request }
            finally { loading = false }
        }
        val openReports = { open = true }
        OutlinedButton(onClick = openReports,
            modifier = modifier.heightIn(min = 48.dp).controllerFocusGlow()
                .paneNavItem(onActivate = openReports),
            shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, CardBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)) {
            Icon(Icons.Outlined.PeopleOutline, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.vapor_community_reports))
        }
        if (open) {
            val context = LocalContext.current
            val uriHandler = LocalUriHandler.current
            val noBrowser = stringResource(R.string.vapor_community_no_browser)
            val source: (String) -> Unit = onSource ?: { url ->
                try { uriHandler.openUri(url) }
                catch (_: ActivityNotFoundException) { Toast.makeText(context, noBrowser, Toast.LENGTH_SHORT).show() }
            }
            Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                val nav = remember { PaneNavRegistry() }
                CompositionLocalProvider(LocalPaneNav provides nav) {
                    DialogPaneNav(nav, onDismiss = { open = false })
                    CommunityReportsContent(gameTitle, page, loading, failed, unexpected,
                        onClose = { open = false }, onRetry = { request++ },
                        // Prefer the matched EmuReady entry when it differs from the Steam title.
                        onBrowse = { source(EmuReady.browseUrl(page?.game?.title?.takeIf { it.isNotBlank() } ?: gameTitle)) },
                        onSource = source, loadSettings = loadSettings)
                }
            }
        }
    }
}

@Composable
internal fun CommunityReportsContent(
    gameTitle: String, page: EmuReady.Page?, loading: Boolean, failed: Boolean,
    unexpected: Boolean = false,
    onClose: () -> Unit, onRetry: () -> Unit, onBrowse: () -> Unit,
    onSource: (String) -> Unit,
    loadSettings: suspend (EmuReady.Report) -> List<EmuReady.Setting>,
) {
    Surface(modifier = Modifier.padding(16.dp).widthIn(max = 760.dp).fillMaxWidth().fillMaxHeight(0.92f),
        color = BgDark, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, CardBorder)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.vapor_community_reports), style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text(gameTitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onClose, modifier = Modifier.paneNavItem(onActivate = onClose, isEntry = true)) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.common_ui_close), tint = TextPrimary)
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(stringResource(R.string.vapor_community_note), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
                if (loading) item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Accent)
                        Text(stringResource(R.string.vapor_community_loading), color = TextSecondary)
                    }
                }
                if (failed || unexpected) item {
                    Column {
                        Text(stringResource(if (unexpected) R.string.vapor_community_unexpected else R.string.vapor_community_unavailable),
                            color = TextSecondary)
                        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).paneNavItem(onActivate = onRetry)) {
                            Text(stringResource(R.string.steam_login_retry))
                        }
                    }
                }
                if (page != null) {
                    if (page.game != null && !page.game.appIdMatch) item {
                        Text(stringResource(R.string.vapor_community_title_match, page.game.title),
                            color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    if (page.reports.isEmpty()) item {
                        Text(stringResource(when {
                            page.game != null -> R.string.vapor_community_empty
                            page.ambiguous -> R.string.vapor_community_ambiguous
                            else -> R.string.vapor_community_no_game
                        }), color = TextSecondary)
                    }
                    items(page.reports, key = { it.id }) { report ->
                        CommunityReportCard(report, onSource, loadSettings)
                    }
                    if (page.hasMore) item {
                        Text(stringResource(R.string.vapor_community_more), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            OutlinedButton(onClick = onBrowse, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .paneNavItem(onActivate = onBrowse), shape = RoundedCornerShape(8.dp)) {
                Text(stringResource(R.string.vapor_community_browse))
            }
        }
    }
}

@Composable
private fun CommunityReportCard(
    report: EmuReady.Report, onSource: (String) -> Unit,
    loadSettings: suspend (EmuReady.Report) -> List<EmuReady.Setting>,
) {
    var requested by remember(report) { mutableStateOf(false) }
    var settings by remember(report) { mutableStateOf<List<EmuReady.Setting>?>(null) }
    var failed by remember(report) { mutableStateOf(false) }
    var attempt by remember(report) { mutableIntStateOf(0) }
    LaunchedEffect(requested, report, attempt) {
        if (!requested) return@LaunchedEffect
        try { settings = loadSettings(report); failed = false }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    Surface(color = CardDark, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, CardBorder)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(report.device.ifBlank { stringResource(R.string.vapor_community_unknown_device) },
                color = TextPrimary, style = MaterialTheme.typography.titleMedium)
            if (report.soc.isNotBlank()) Text(report.soc, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            val notSpecified = stringResource(R.string.vapor_community_not_specified)
            Text(stringResource(R.string.vapor_community_reported,
                report.performance.ifBlank { notSpecified }, report.emulator.ifBlank { notSpecified }),
                color = Accent, style = MaterialTheme.typography.bodyMedium)
            if (report.date.isNotBlank()) Text(report.date, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            if (report.upvotes != null && report.downvotes != null) Text(
                stringResource(R.string.vapor_community_votes, report.upvotes, report.downvotes),
                color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            if (report.notes.isNotBlank()) Text(report.notes, color = TextPrimary, style = MaterialTheme.typography.bodyMedium,
                maxLines = 5, overflow = TextOverflow.Ellipsis)
            if (!requested) {
                val showSettings = { requested = true }
                TextButton(onClick = showSettings, modifier = Modifier.heightIn(min = 48.dp).paneNavItem(onActivate = showSettings)) {
                    Text(stringResource(R.string.vapor_community_view_settings))
                }
            } else if (failed) {
                Text(stringResource(R.string.vapor_community_settings_error), color = TextSecondary)
                val retry: () -> Unit = { failed = false; attempt++ }
                TextButton(onClick = retry, modifier = Modifier.heightIn(min = 48.dp).paneNavItem(onActivate = retry)) {
                    Text(stringResource(R.string.steam_login_retry))
                }
            } else if (settings == null) {
                Text(stringResource(R.string.vapor_community_loading), color = TextSecondary)
            } else {
                if (settings!!.isEmpty()) Text(stringResource(R.string.vapor_community_no_settings), color = TextSecondary)
                // A condensed read of the fields that matter here, so the configuration that
                // evidently worked can be compared against Vapor's own choice at a glance.
                EmuReadyConfigHint.from(settings!!)?.summary()?.let { summary ->
                    Text(stringResource(R.string.vapor_community_reported_config, summary),
                        color = Accent, style = MaterialTheme.typography.bodyMedium)
                }
                settings!!.forEach { setting ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(setting.label, color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                        Text(setting.value, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            val source = { onSource(report.sourceUrl) }
            TextButton(onClick = source, modifier = Modifier.heightIn(min = 48.dp).paneNavItem(onActivate = source)) {
                Text(stringResource(R.string.vapor_community_source))
            }
        }
    }
}
