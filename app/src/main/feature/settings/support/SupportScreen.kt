package com.winlator.cmod.feature.settings.support

import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.outlined.Description
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Info
import com.winlator.cmod.R
import com.winlator.cmod.feature.settings.SettingsNavBridge
import com.winlator.cmod.shared.ui.focus.rememberSettingsContentNav
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.paneNavItem

private val SupportBg = Color(0xFF101018)
private val SupportText = Color(0xFFF0F4FF)
private val SupportSub = Color(0xFF93A6BC)
private val SupportCard = Color(0xFF181822)
private val SupportAccent = Color(0xFF4FC3F7)

private data class SupportLink(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val titleRes: Int,
    val subtitleRes: Int,
    val url: String,
)

private val SUPPORT_LINKS = listOf(
    SupportLink(Icons.Outlined.Code, R.string.vapor_support_project, R.string.vapor_support_project_desc,
        "https://github.com/giminycricket0-blip/vapor"),
    SupportLink(Icons.Outlined.BugReport, R.string.vapor_support_issues, R.string.vapor_support_issues_desc,
        "https://github.com/giminycricket0-blip/vapor/issues"),
    SupportLink(Icons.Outlined.Info, R.string.vapor_support_upstream, R.string.vapor_support_upstream_desc,
        "https://github.com/WinNative-Emu/WinNative"),
)

/** One bundled licence text in `assets/licenses/`. */
internal data class BundledLicence(val fileName: String, val bytes: Int)

/**
 * Reads the licence texts actually present in the APK, so the list can never drift
 * from what ships. Unknown files are listed by name; known ones get a readable label.
 */
internal fun loadBundledLicences(context: android.content.Context): List<BundledLicence> =
    runCatching {
        context.assets.list("licenses")
            ?.mapNotNull { name ->
                runCatching {
                    BundledLicence(name, context.assets.open("licenses/$name").available())
                }.getOrNull()
            }
            ?.sortedBy { it.fileName }
            .orEmpty()
    }.getOrDefault(emptyList())

internal fun licenceLabel(fileName: String): String = when {
    fileName.startsWith("wine-") -> "Wine (" + fileName.removePrefix("wine-").removeSuffix(".txt") + ")"
    fileName.startsWith("cnc-ddraw") -> "cnc-ddraw (MIT)"
    fileName.startsWith("7zip") -> "7-Zip 19.00"
    fileName.startsWith("xorgproto") -> "X11 protocol headers"
    else -> fileName.removeSuffix(".txt")
}

@Composable
private fun SectionHeading(
    textRes: Int,
    descRes: Int,
    topPadding: Int,
) {
    Text(
        stringResource(textRes),
        color = SupportSub,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = topPadding.dp),
    )
    Text(
        stringResource(descRes),
        color = SupportSub,
        style = MaterialTheme.typography.labelMedium,
    )
    Spacer(Modifier.size(2.dp))
}

@Composable
private fun SupportLinkRow(
    link: SupportLink,
    onOpen: (String) -> Unit,
    highlighted: Boolean = false,
) {
    val shape = RoundedCornerShape(14.dp)
    val surface =
        if (highlighted) {
            Modifier
                .background(SupportAccent.copy(alpha = 0.08f))
                .border(1.dp, SupportAccent.copy(alpha = 0.35f), shape)
        } else {
            Modifier.background(SupportCard)
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .then(surface)
                .clickable { onOpen(link.url) }
                .paneNavItem(
                    cornerRadius = 14.dp,
                    onActivate = { onOpen(link.url) },
                    highlightColor = SupportAccent,
                    tapToSelect = true,
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(SupportAccent.copy(alpha = if (highlighted) 0.20f else 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = link.icon,
                contentDescription = null,
                tint = SupportAccent,
                modifier = Modifier.size(22.dp),
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Text(
                stringResource(link.titleRes),
                color = SupportText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                stringResource(link.subtitleRes),
                color = SupportSub,
                fontSize = 11.sp,
            )
        }

        Icon(
            Icons.Outlined.OpenInNew,
            contentDescription = null,
            tint = SupportSub,
            modifier = Modifier.size(17.dp),
        )
    }
}

@Composable
private fun ThirdPartyLicenceRow(
    licence: BundledLicence,
    onOpen: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(SupportCard)
                .clickable(onClick = onOpen)
                .paneNavItem(
                    cornerRadius = 14.dp,
                    onActivate = onOpen,
                    highlightColor = SupportAccent,
                    tapToSelect = true,
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(SupportAccent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Description,
                contentDescription = null,
                tint = SupportAccent,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                licenceLabel(licence.fileName),
                color = SupportText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                licence.fileName,
                color = SupportSub,
                fontSize = 11.sp,
            )
        }
    }
}

/** Shows one bundled licence text verbatim. Read-only; no network. */
@Composable
private fun LicenceTextDialog(
    licence: BundledLicence,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val text = remember(licence.fileName) {
        runCatching {
            context.assets.open("licenses/" + licence.fileName)
                .bufferedReader(Charsets.UTF_8)
                .readText()
                .ifBlank { "(empty licence text)" }
        }.getOrElse { "(the licence text could not be read: " + (it.message ?: "unknown error") + ")" }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(licenceLabel(licence.fileName)) },
        text = {
            SelectionContainer {
                Text(
                    text,
                    color = SupportText,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.support_license_close)) }
        },
    )
}

@Composable
fun SupportScreen(bridge: SettingsNavBridge? = null) {
    val context = LocalContext.current
    val contentNav = rememberSettingsContentNav(bridge)

    fun open(url: String) {
        runCatching {
            context.startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url),
                ),
            )
        }
    }

    CompositionLocalProvider(LocalPaneNav provides contentNav) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(SupportBg)
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionHeading(
                textRes = R.string.support_heading,
                descRes = R.string.support_desc,
                topPadding = 10,
            )

            SUPPORT_LINKS.forEach { link ->
                SupportLinkRow(link = link, onOpen = ::open)
            }

            val licences = remember(context) { loadBundledLicences(context) }
            var opened by remember { mutableStateOf<BundledLicence?>(null) }
            if (licences.isNotEmpty()) {
                SectionHeading(
                    textRes = R.string.support_licenses_heading,
                    descRes = R.string.support_licenses_desc,
                    topPadding = 18,
                )
                licences.forEach { licence ->
                    ThirdPartyLicenceRow(licence = licence, onOpen = { opened = licence })
                }
            }
            opened?.let { LicenceTextDialog(licence = it, onDismiss = { opened = null }) }
        }
    }
}
