package com.winlator.cmod.shared.ui.layout

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R
import com.winlator.cmod.shared.ui.branding.VaporBrandMark
import com.winlator.cmod.shared.ui.focus.controllerConfirmOnA
import com.winlator.cmod.shared.ui.focus.controllerFocusGlow
import com.winlator.cmod.shared.ui.focus.controllerMenuInput

/** Shared first-run frame. Content scrolls under a measured header, including with the IME open. */
@Composable
internal fun VaporOnboardingLayout(
    title: String,
    subtitle: String,
    section: String,
    onBack: (() -> Unit)? = null,
    content: @Composable BoxWithConstraintsScope.(compact: Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(colors.background)
            .background(Brush.linearGradient(listOf(colors.primaryContainer.copy(alpha = .72f), colors.background, colors.background)))
            .safeDrawingPadding().imePadding()
            .then(if (onBack != null) Modifier.controllerMenuInput(onDismiss = onBack) else Modifier.controllerConfirmOnA()),
        contentAlignment = Alignment.Center,
    ) {
        val compact = maxHeight < 480.dp
        Column(Modifier.widthIn(max = 1120.dp).fillMaxWidth().fillMaxHeight()
            .padding(horizontal = if (maxWidth < 600.dp) 20.dp else 32.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = if (compact) 8.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                VaporBrandMark(Modifier.size(32.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (compact) title else "VAPOR", style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium.copy(letterSpacing = 3.sp),
                        color = colors.onBackground, modifier = Modifier.semantics { if (compact) heading() })
                    Text(section, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
                if (onBack != null) TextButton(onClick = onBack,
                    modifier = Modifier.heightIn(min = 48.dp).controllerFocusGlow()) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.common_ui_back))
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = if (compact) 8.dp else 24.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 20.dp else 28.dp)) {
                    if (!compact) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title, style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold, color = colors.onBackground, modifier = Modifier.semantics { heading() })
                        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant,
                            modifier = Modifier.widthIn(max = 720.dp))
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) { content(compact) }
                }
            }
        }
    }
}

@Composable
internal fun VaporOnboardingCard(modifier: Modifier = Modifier, compact: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .82f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .5f))) {
        Column(Modifier.padding(if (compact) 16.dp else 24.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp), content = content)
    }
}
