package com.winlator.cmod.feature.settings.support

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
        }
    }
}
