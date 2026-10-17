package com.winlator.cmod.app.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R
import com.winlator.cmod.shared.ui.focus.controllerFocusGlow

/** Presentation of the existing hub state; navigation and search still belong to UnifiedHub. */
@Composable
internal fun VaporHeader(
    tabs: List<UnifiedActivity.TabDef>, selectedIndex: Int, onSelect: (Int) -> Unit,
    searchQuery: TextFieldValue, onSearchQueryChange: (TextFieldValue) -> Unit,
    searchExpanded: Boolean, onToggleSearch: () -> Unit,
    onFilter: () -> Unit, onFriends: () -> Unit, onSettings: () -> Unit,
    onQuickAccess: () -> Unit, onMoreStores: () -> Unit,
    libraryInstalledOnly: Boolean = false, onLibraryViewChange: (Boolean) -> Unit = {},
) {
    val current = tabs.getOrNull(selectedIndex)
    val library = current?.key == "library"
    val searchable = current?.key !in setOf("home", "downloads", "steam")
    val primary = tabs.withIndex().filter { it.value.key !in StoreTabKeys }
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var searchFocused by remember { mutableStateOf(false) }
    LaunchedEffect(searchExpanded, searchable) { if (searchExpanded && searchable) searchFocus.requestFocus() }

    BoxWithConstraints(Modifier.fillMaxWidth().background(Brush.verticalGradient(
        listOf(SurfaceDark, BgDark.copy(alpha = 0.96f))))) {
        val effectiveWidth = maxWidth / LocalDensity.current.fontScale
        val singleRow = effectiveWidth >= 760.dp
        val stackLibraryControls = (maxWidth - 32.dp) / LocalDensity.current.fontScale < 320.dp
        @Composable fun Navigation(modifier: Modifier) {
            if (current != null && current.key in StoreTabKeys) {
                Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onMoreStores, modifier = Modifier.controllerFocusGlow()) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack,
                            stringResource(R.string.vapor_back_to_more_stores), tint = TextPrimary)
                    }
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(stringResource(R.string.vapor_other_stores), color = TextSecondary,
                            style = MaterialTheme.typography.labelMedium)
                        Text(current.label, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                    }
                }
            } else BoxWithConstraints(modifier) {
                val tabWidth = (maxWidth / primary.size.coerceAtLeast(1)).coerceAtLeast(64.dp)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically) {
                    primary.forEach { (index, tab) ->
                        val selected = selectedIndex == index
                        val bringIntoView = remember { BringIntoViewRequester() }
                        LaunchedEffect(selected) {
                            if (selected) { withFrameNanos { }; bringIntoView.bringIntoView() }
                        }
                        val ink by animateColorAsState(if (selected) TextPrimary else TextSecondary,
                            tween(160), label = "tabLabel")
                        val underline by animateFloatAsState(if (selected) 1f else 0f,
                            tween(180), label = "tabUnderline")
                        Tab(selected = selected, onClick = { onSelect(index) },
                            modifier = Modifier.width(IntrinsicSize.Max).widthIn(min = tabWidth)
                                .heightIn(min = 48.dp).bringIntoViewRequester(bringIntoView)
                                .controllerFocusGlow(cornerRadius = 4.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(tab.label, color = ink, fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.labelLarge, maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp))
                                Box(Modifier.fillMaxWidth().height(3.dp), contentAlignment = Alignment.Center) {
                                    Box(Modifier.fillMaxWidth(underline).height(3.dp).background(Accent, RoundedCornerShape(2.dp)))
                                }
                            }
                        }
                    }
                }
            }
        }

        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                VaporBrandMark(Modifier.size(32.dp))
                if (effectiveWidth >= 320.dp && (!singleRow || effectiveWidth >= 900.dp)) Text("VAPOR", color = TextPrimary,
                    fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(start = 6.dp))
                if (singleRow) Navigation(Modifier.width(400.dp).padding(start = 12.dp))
                Spacer(Modifier.weight(1f))
                if (effectiveWidth >= 1100.dp) VaporClock()
                if (singleRow && searchable && !library) {
                    IconButton(onClick = onToggleSearch, modifier = Modifier.controllerFocusGlow()) {
                        Icon(if (searchExpanded) Icons.Outlined.Close else Icons.Outlined.Search,
                            stringResource(if (searchExpanded) R.string.vapor_close_search else R.string.vapor_search), tint = TextPrimary)
                    }
                }
                Row(Modifier.vaporGlass(RoundedCornerShape(18.dp)), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onFriends, modifier = Modifier.controllerFocusGlow()) {
                        Icon(Icons.Outlined.People, stringResource(R.string.vapor_friends), tint = TextPrimary)
                    }
                    IconButton(onClick = onSettings, modifier = Modifier.controllerFocusGlow()) {
                        Icon(Icons.Outlined.Settings, stringResource(R.string.common_ui_settings), tint = TextPrimary)
                    }
                    IconButton(onClick = onQuickAccess, modifier = Modifier.controllerFocusGlow()) {
                        Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.vapor_quick_access), tint = TextPrimary)
                    }
                }
            }
            if (!singleRow) Navigation(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            if (library) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VaporLibraryTabs(libraryInstalledOnly, modifier = Modifier.weight(1f), onSelect = onLibraryViewChange)
                    if (!stackLibraryControls) {
                        IconButton(onClick = onToggleSearch, modifier = Modifier.size(48.dp).controllerFocusGlow()) {
                            Icon(if (searchExpanded) Icons.Outlined.Close else Icons.Outlined.Search,
                                stringResource(if (searchExpanded) R.string.vapor_close_search else R.string.vapor_search),
                                tint = TextPrimary, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = onFilter, modifier = Modifier.size(48.dp).controllerFocusGlow()) {
                            Icon(Icons.Outlined.Tune, stringResource(R.string.vapor_library_options),
                                tint = TextPrimary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            if ((searchable && !library && !singleRow) || (library && stackLibraryControls)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onToggleSearch,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).controllerFocusGlow(),
                        contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Icon(if (searchExpanded) Icons.Outlined.Close else Icons.Outlined.Search, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(if (searchExpanded) R.string.vapor_close_search else R.string.vapor_search),
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (library) IconButton(onClick = onFilter, modifier = Modifier.size(48.dp).controllerFocusGlow()) {
                        Icon(Icons.Outlined.Tune, stringResource(R.string.vapor_library_options), tint = TextPrimary)
                    }
                }
            }
            AnimatedVisibility(searchExpanded && searchable,
                enter = expandVertically(tween(180)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(140)) + fadeOut(tween(140))) {
                val searchLabel = if (current?.key in StoreTabKeys)
                    stringResource(R.string.vapor_search_optional_store, current?.label.orEmpty())
                    else stringResource(R.string.vapor_search_library)
                BasicTextField(value = searchQuery, onValueChange = onSearchQueryChange, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                    cursorBrush = SolidColor(Accent),
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                        .focusRequester(searchFocus).onFocusChanged { searchFocused = it.isFocused }
                        .semantics { contentDescription = searchLabel }
                        .vaporGlass(RoundedCornerShape(12.dp))
                        .border(1.dp, if (searchFocused) Accent else TextSecondary.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus() }),
                    decorationBox = { field ->
                        Row(Modifier.heightIn(min = 48.dp).padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Search, null, Modifier.size(20.dp), tint = TextSecondary)
                            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                                if (searchQuery.text.isEmpty()) Text(searchLabel, color = TextSecondary,
                                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                field()
                            }
                            if (searchQuery.text.isNotEmpty()) IconButton(onClick = { onSearchQueryChange(TextFieldValue("")) },
                                modifier = Modifier.size(48.dp).controllerFocusGlow()) {
                                Icon(Icons.Outlined.Close, stringResource(R.string.workshop_clear_search),
                                    Modifier.size(20.dp), tint = TextSecondary)
                            } else Spacer(Modifier.width(4.dp))
                        }
                    })
            }
            HorizontalDivider(color = CardBorder.copy(alpha = 0.35f))
        }
    }
}
