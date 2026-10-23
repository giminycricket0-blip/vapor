package com.winlator.cmod.feature.settings
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import android.content.Context
import com.winlator.cmod.BuildConfig
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import com.winlator.cmod.shared.theme.*
import com.winlator.cmod.shared.ui.focus.controllerFocusGlow
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideogameAsset
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R
import com.winlator.cmod.shared.ui.widget.chasingBorder

// ─── Palette ────────────────────────────────────────────────────────

private val SidebarBgTop = WinNativePanel
private val SectionLabelClr = WinNativeTextSecondary
private val HeaderTextClr = WinNativeTextPrimary
private val TextNormal = WinNativeTextSecondary
private val TextSelected = WinNativeTextPrimary
private val IconMuted = WinNativeTextSecondary
private val SelectedBg = WinNativeSurface
private val DividerColor = WinNativeOutline
private val AccentSelected = WinNativeAccent
private val AccentPressed = WinNativeTextPrimary
private val InterFamily = WinNativeFontFamily

// ─── Navigation model ───────────────────────────────────────────────

/**
 * Whether the tinkering screens are on show.
 *
 * Vapor's promise is install -> play, so drivers, containers, components and presets are hidden
 * by default: a normal session should never have to think about them. They stay one switch away
 * for the games that misbehave, and the preference is remembered.
 */
internal object SettingsVisibility {
    private const val PREFS = "vapor_settings_ui"
    private const val KEY_ADVANCED = "show_advanced"

    fun isAdvancedVisible(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ADVANCED, false)

    fun setAdvancedVisible(context: Context, visible: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ADVANCED, visible).apply()
    }

    private const val KEY_UNLOCKED = "advanced_unlocked"

    /**
     * Whether the unlock gesture has been performed. The rule is that a normal user never sees
     * the graphics layer, so the switch is not normal UI; it is revealed deliberately (seven
     * taps on the version in About) for the rare game that needs it.
     */
    fun isAdvancedUnlocked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_UNLOCKED, false)

    fun unlockAdvanced(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_UNLOCKED, true).apply()
    }
}

enum class NavSection(val titleRes: Int) {
    EVERYDAY(R.string.vapor_settings_everyday),
    ADVANCED(R.string.vapor_settings_advanced),
    HELP(R.string.vapor_settings_help),
}

enum class SettingsNavItem(
    val menuId: Int,
    val icon: ImageVector,
    val titleRes: Int,
    val section: NavSection,
) {
    // Keep this order aligned with the sidebar: the controller follows entries directly.
    STORES(R.id.main_menu_stores, Icons.Outlined.ShoppingBag, R.string.vapor_settings_accounts_storage, NavSection.EVERYDAY),
    INPUT_CONTROLS(R.id.main_menu_input_controls, Icons.Outlined.SportsEsports, R.string.common_ui_input_controls, NavSection.EVERYDAY),
    OTHER(R.id.main_menu_other, Icons.Outlined.Widgets, R.string.vapor_settings_app_preferences, NavSection.EVERYDAY),
    DEVICE_PROFILE(R.id.main_menu_device_profile, Icons.Outlined.TabletAndroid, R.string.device_profile_nav_title, NavSection.EVERYDAY),
    MORE_STORES(R.id.main_menu_more_stores, Icons.Outlined.ShoppingBag, R.string.vapor_other_stores, NavSection.EVERYDAY),
    GOOGLE(R.id.main_menu_google, Icons.Outlined.AccountCircle, R.string.google_cloud_google, NavSection.EVERYDAY),
    CONTAINERS(R.id.main_menu_containers, Icons.Outlined.ViewInAr, R.string.common_ui_containers, NavSection.ADVANCED),
    PRESETS(R.id.main_menu_settings, Icons.Outlined.Tune, R.string.container_presets_title, NavSection.ADVANCED),
    COMPONENTS(R.id.main_menu_contents, Icons.Outlined.Extension, R.string.settings_content_components, NavSection.ADVANCED),
    DRIVERS(R.id.main_menu_adrenotools_gpu_drivers, Icons.Outlined.Memory, R.string.settings_drivers_title, NavSection.ADVANCED),
    RETRO(R.id.main_menu_retro, Icons.Outlined.VideogameAsset, R.string.settings_retro_title, NavSection.ADVANCED),
    DEBUG(R.id.main_menu_advanced, Icons.Outlined.BugReport, R.string.settings_debug_title, NavSection.ADVANCED),
    SUPPORT(R.id.main_menu_support, Icons.Outlined.HelpOutline, R.string.settings_support_title, NavSection.HELP),
    ABOUT(R.id.main_menu_about, Icons.Outlined.Info, R.string.common_ui_about, NavSection.HELP),
    CREDITS(R.id.main_menu_credits, Icons.Outlined.Info, R.string.retro_scr_tab_credits, NavSection.HELP),
    ;

    companion object {
        fun fromMenuId(id: Int): SettingsNavItem? = entries.find { it.menuId == id }
    }
}

private val sectionedNavItems: Map<NavSection, List<SettingsNavItem>> =
    SettingsNavItem.entries.groupBy { it.section }

// ─── Main sidebar composable ────────────────────────────────────────

@Composable
fun SettingsNavSidebar(
    selectedItem: SettingsNavItem,
    railActive: Boolean = true,
    onItemSelected: (SettingsNavItem) -> Unit,
    onBackPressed: () -> Unit,
    sidebarWidth: Dp = 208.dp,
    showHeader: Boolean = true,
) {
    val layoutDirection = LocalLayoutDirection.current
    val context = LocalContext.current
    // If something has already navigated into a hidden screen, keep it visible rather than
    // rendering a sidebar with no selection.
    var showAdvanced by remember {
        mutableStateOf(
            SettingsVisibility.isAdvancedVisible(context) || selectedItem.section == NavSection.ADVANCED,
        )
    }
    // The product rule is that a user never sees the layer. The switch therefore only appears
    // in debug builds or after the deliberate unlock gesture, never as normal UI.
    val advancedDiscoverable = SettingsVisibility.isAdvancedUnlocked(context) || BuildConfig.DEBUG
    // Hidden entirely unless asked for: a normal session never needs these.
    val visibleSections = remember(showAdvanced) {
        NavSection.entries.filter { it != NavSection.ADVANCED || showAdvanced }
    }
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
    val navStartInset = navBarPadding.calculateStartPadding(layoutDirection)
    val navBottomInset = navBarPadding.calculateBottomPadding()

    val listState = rememberLazyListState()
    val selectedLazyIndex =
        remember(selectedItem) {
            var idx = 0
            var found = 0
            visibleSections.forEachIndexed { sIdx, section ->
                if (sIdx > 0) idx++
                idx++
                val secItems = sectionedNavItems[section].orEmpty()
                val pos = secItems.indexOf(selectedItem)
                if (pos >= 0) found = idx + pos
                idx += secItems.size
            }
            found
        }
    LaunchedEffect(selectedLazyIndex) {
        val info = listState.layoutInfo
        if (info.visibleItemsInfo.isEmpty()) return@LaunchedEffect
        val visible = info.visibleItemsInfo.firstOrNull { it.index == selectedLazyIndex }
        val fullyVisible =
            visible != null &&
                visible.offset >= info.viewportStartOffset &&
                visible.offset + visible.size <= info.viewportEndOffset
        if (!fullyVisible) runCatching { listState.animateScrollToItem(selectedLazyIndex) }
    }

    Row(
        modifier =
            Modifier
                .fillMaxHeight()
                .padding(start = navStartInset),
    ) {
        Column(
            modifier =
                Modifier
                    .width(sidebarWidth)
                    .fillMaxHeight()
                    .background(SidebarBgTop),
        ) {
            if (showHeader) {
                SidebarHeader(onBackPressed)
            } else {
                Spacer(Modifier.height(12.dp))
            }

            // Scrollable nav items
            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(top = 2.dp),
                contentPadding = PaddingValues(bottom = 24.dp + navBottomInset),
            ) {
                visibleSections.forEachIndexed { index, section ->
                    if (index > 0) {
                        item(
                            key = "spacer_${section.name}",
                            contentType = "sectionSpacer",
                        ) {
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    item(
                        key = "header_${section.name}",
                        contentType = "sectionHeader",
                    ) {
                        SectionHeader(stringResource(section.titleRes))
                    }
                    items(
                        items = sectionedNavItems[section].orEmpty(),
                        key = { it.name },
                        contentType = { "navItem" },
                    ) { item ->
                        NavItemRow(
                            item = item,
                            isSelected = item == selectedItem,
                            railActive = railActive,
                            onClick = { onItemSelected(item) },
                        )
                    }
                }
                // The way back in to the hidden screens, phrased as what it is.
                if (advancedDiscoverable) item(key = "advanced_toggle", contentType = "advancedToggle") {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showAdvanced = !showAdvanced
                                    SettingsVisibility.setAdvancedVisible(context, showAdvanced)
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.vapor_settings_show_advanced),
                                color = WinNativeTextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (!showAdvanced) {
                                Text(
                                    stringResource(R.string.vapor_settings_show_advanced_hint),
                                    color = WinNativeTextSecondary.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                )
                            }
                        }
                        Switch(checked = showAdvanced, onCheckedChange = {
                            showAdvanced = it
                            SettingsVisibility.setAdvancedVisible(context, it)
                        })
                    }
                }
            }
        }

        // Right-edge divider
        Box(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(DividerColor),
        )
    }
}

@Composable
fun SettingsPortraitTopBar(
    title: String,
    onBackPressed: () -> Unit,
    onOpenSections: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SidebarBgTop)
                .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(48.dp).controllerFocusGlow()
                    .clip(RoundedCornerShape(3.dp))
                    .clickable(role = Role.Button, onClick = onBackPressed),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.common_ui_back),
                tint = AccentSelected,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = title,
            color = TextSelected,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = InterFamily,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(start = 6.dp, end = 6.dp),
        )
        Box(
            modifier =
                Modifier
                    .heightIn(min = 48.dp).controllerFocusGlow()
                    .clip(RoundedCornerShape(3.dp))
                    .background(SelectedBg)
                    .clickable(role = Role.Button, onClick = onOpenSections)
                    .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Menu,
                    contentDescription = stringResource(R.string.common_ui_settings),
                    tint = AccentSelected,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.common_ui_settings).uppercase(),
                    color = AccentSelected,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = InterFamily,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

// ─── Header ─────────────────────────────────────────────────────────

@Composable
private fun SidebarHeader(onBackPressed: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val arrowTint by animateColorAsState(
        targetValue = if (isPressed) AccentPressed else AccentSelected,
        animationSpec = tween(120),
        label = "sidebarHeaderArrowTint",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .controllerFocusGlow()
                .clickable(
                    role = Role.Button,
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onBackPressed,
                ).padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = stringResource(R.string.common_ui_back),
            tint = arrowTint,
            modifier = Modifier.size(22.dp),
        )

        Text(
            text = stringResource(R.string.common_ui_settings).uppercase(),
            color = HeaderTextClr,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = InterFamily,
            letterSpacing = 1.2.sp,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

// ─── Section header ─────────────────────────────────────────────────

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label.uppercase(),
        color = SectionLabelClr,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = InterFamily,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

// ─── Navigation item row ────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NavItemRow(
    item: SettingsNavItem,
    isSelected: Boolean,
    railActive: Boolean = true,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(isSelected) {
        if (isSelected) runCatching { bringIntoView.bringIntoView() }
    }

    val focusedSelection = isSelected && railActive
    val textColor by animateColorAsState(
        targetValue = when { focusedSelection -> WinNativeBackground; isSelected -> TextSelected; else -> TextNormal },
        animationSpec = tween(120),
        label = "textColor",
    )
    val rowColor by animateColorAsState(
        targetValue = when {
            focusedSelection -> WinNativeTextPrimary
            isSelected || isHovered -> WinNativeSurfaceAlt
            else -> Color.Transparent
        },
        animationSpec = tween(120),
        label = "sidebarSelection",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(bringIntoView)
                .background(rowColor)
                .heightIn(min = 48.dp)
                .controllerFocusGlow(cornerRadius = 0.dp)
                .selectable(selected = isSelected, role = Role.Tab, onClick = onClick)
                .hoverable(interactionSource)
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            tint = textColor,
            modifier = Modifier.size(20.dp),
        )

        Text(
            text = stringResource(item.titleRes),
            color = textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = InterFamily,
            letterSpacing = 0.02.sp,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}
