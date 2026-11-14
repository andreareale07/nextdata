package app.nextdata.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.Sort
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.dp
import app.nextdata.engine.EngineClient
import app.nextdata.i18n.errorText
import app.nextdata.protocol.Entry
import app.nextdata.protocol.EntryKind
import app.nextdata.protocol.ErrInfo
import app.nextdata.protocol.ProtocolId
import app.nextdata.protocol.SortKey
import app.nextdata.shared.resources.*
import app.nextdata.state.AppController
import app.nextdata.state.BreadcrumbNav
import app.nextdata.state.BrowserListing
import app.nextdata.state.BrowserState
import app.nextdata.state.BrowserViewModel
import app.nextdata.state.FileKinds
import app.nextdata.state.Formatters
import app.nextdata.state.Screen
import app.nextdata.state.SecurityInfo
import app.nextdata.state.ViewMode
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// ======================================================================== menus

@Composable
fun SortMenu(expanded: Boolean, state: BrowserState, onDismiss: () -> Unit, onSort: (String) -> Unit) {
    DropdownMenu(expanded, onDismiss) {
        listOf(
            SortKey.NAME to Res.string.sort_name,
            SortKey.SIZE to Res.string.sort_size,
            SortKey.MTIME to Res.string.sort_date,
        ).forEach { (key, res) ->
            val active = state.sortKey == key
            DropdownMenuItem(
                text = { Text(stringResource(res), fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal) },
                leadingIcon = {
                    if (active) Icon(if (state.sortDesc) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward, null)
                    else Spacer(Modifier.size(24.dp))
                },
                onClick = { onDismiss(); onSort(key) },
            )
        }
    }
}

/** Favorites of the open profile + recently visited folders. */
@Composable
fun PlacesMenu(expanded: Boolean, state: BrowserState, recents: List<app.nextdata.state.RecentFolder>, onDismiss: () -> Unit, vm: BrowserViewModel) {
    DropdownMenu(expanded, onDismiss) {
        Text(stringResource(Res.string.favorites_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        if (state.favorites.isEmpty()) {
            DropdownMenuItem(text = { Text(stringResource(Res.string.favorites_empty)) }, onClick = onDismiss, enabled = false)
        }
        state.favorites.forEach { f ->
            DropdownMenuItem(
                text = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Outlined.Star, null, tint = MaterialTheme.colorScheme.tertiary) },
                trailingIcon = { NdIconButton(Icons.Outlined.Close, stringResource(Res.string.action_favorite_remove), { vm.toggleFavorite(f.ref, f.name) }) },
                onClick = { onDismiss(); vm.open(f.ref) },
            )
        }
        HorizontalDivider()
        Text(stringResource(Res.string.recents_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        if (recents.isEmpty()) DropdownMenuItem(text = { Text(stringResource(Res.string.recents_empty)) }, onClick = onDismiss, enabled = false)
        recents.forEach { r ->
            DropdownMenuItem(
                text = { Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Outlined.History, null) },
                onClick = { onDismiss(); vm.open(r.ref) },
            )
        }
    }
}

// ======================================================================== breadcrumb (clickable + editable)

@Composable
fun BreadcrumbBar(state: BrowserState, onOpen: (String) -> Unit, modifier: Modifier = Modifier, onCopyPath: (String) -> Unit = {}) {
    val scroll = rememberScrollState()
    var editing by remember { mutableStateOf(false) }
    var notFound by remember { mutableStateOf(false) }
    LaunchedEffect(state.breadcrumbs) { scroll.animateScrollTo(scroll.maxValue) }
    val display = remember(state.breadcrumbs) { BreadcrumbNav.displayPath(state.breadcrumbs) }
    val focus = remember { FocusRequester() }

    if (editing) {
        var value by remember(display) { mutableStateOf(TextFieldValue(display, TextRange(0, display.length))) }
        LaunchedEffect(Unit) { focus.requestFocus() }
        Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it; notFound = false },
                singleLine = true,
                isError = notFound,
                supportingText = if (notFound) ({ Text(stringResource(Res.string.path_not_found)) }) else null,
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    val hit = BreadcrumbNav.resolve(value.text, state.breadcrumbs)
                    if (hit != null) { editing = false; onOpen(hit.ref) } else notFound = true
                }),
                modifier = Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { editing = false; true } else false
                },
            )
            NdIconButton(Icons.Outlined.ContentCopy, stringResource(Res.string.action_copy_path), { onCopyPath(display) })
            NdIconButton(Icons.Outlined.Close, stringResource(Res.string.action_cancel), { editing = false })
        }
    } else {
        Row(modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f, fill = false).horizontalScroll(scroll).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val crumbs = state.breadcrumbs
                crumbs.forEachIndexed { i, c ->
                    val last = i == crumbs.lastIndex
                    Text(
                        c.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .then(if (last) Modifier else Modifier.clickable(role = Role.Button) { onOpen(c.ref) })
                            .heightIn(min = 32.dp)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                    if (!last) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // the free area behind the crumbs switches to the editable path
            val editLabel = stringResource(Res.string.path_edit)
            Box(Modifier.weight(1f).heightIn(min = 40.dp).clickable(role = Role.Button, onClickLabel = editLabel) { editing = true; notFound = false })
        }
    }
}

// ======================================================================== quick filter

@Composable
fun FilterField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, focus: FocusRequester = remember { FocusRequester() }, onEscape: () -> Unit = {}) {
    val placeholder = stringResource(Res.string.filter_placeholder)
    val clearLabel = stringResource(Res.string.filter_clear)
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .heightIn(min = 36.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused }
                    .semantics { contentDescription = placeholder }
                    .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onEscape(); true } else false },
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                Icons.Outlined.Close, clearLabel,
                Modifier.size(32.dp).clip(MaterialTheme.shapes.small).clickable(role = Role.Button) { onChange("") }.padding(7.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ======================================================================== phone bars

/** Phone header: only the current folder (or the selection count). Every control lives in the bottom bar. */
@Composable
fun CompactHeader(title: String, selectedCount: Int) {
    val selecting = selectedCount > 0
    Surface(color = if (selecting) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
        Text(
            if (selecting) pluralStringResource(Res.plurals.selected_count, selectedCount, selectedCount) else title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp).semantics { heading() },
        )
    }
}

/** Phone bottom bar: back, sort, search, add and the overflow menu, all within thumb reach. */
@Composable
fun CompactBottomBar(
    controller: AppController,
    state: BrowserState,
    vm: BrowserViewModel,
    viewMode: ViewMode,
    activeTransfers: Int,
    onSearch: () -> Unit,
    onSecurity: () -> Unit,
    onAdd: () -> Unit,
) {
    var overflow by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var places by remember { mutableStateOf(false) }
    val settings by controller.settings.state.collectAsState()
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().height(60.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                NdIconButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(Res.string.action_back), { controller.back() })
                Box {
                    NdIconButton(Icons.AutoMirrored.Outlined.Sort, stringResource(Res.string.sort_title), { sortMenu = true })
                    SortMenu(sortMenu, state, { sortMenu = false }, vm::setSort)
                }
                NdIconButton(Icons.Outlined.Search, stringResource(Res.string.action_search), onSearch)
                NdIconButton(Icons.Outlined.Add, stringResource(Res.string.action_add), onAdd)
                Box {
                    NdIconButton(Icons.Outlined.MoreVert, stringResource(Res.string.action_more), { overflow = true })
                    DropdownMenu(overflow, { overflow = false }) {
                        DropdownMenuItem(text = { Text(stringResource(Res.string.action_refresh)) }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) }, onClick = { overflow = false; vm.refresh() })
                        DropdownMenuItem(
                            text = { Text(stringResource(if (viewMode == ViewMode.Grid) Res.string.view_list else Res.string.view_grid)) },
                            leadingIcon = { Icon(if (viewMode == ViewMode.Grid) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView, null) },
                            onClick = { overflow = false; controller.settings.update { it.copy(viewMode = if (viewMode == ViewMode.Grid) ViewMode.List else ViewMode.Grid) } },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(if (state.isFavoriteHere) Res.string.action_favorite_remove else Res.string.action_favorite_add)) },
                            leadingIcon = { Icon(if (state.isFavoriteHere) Icons.Outlined.Star else Icons.Outlined.StarBorder, null) },
                            onClick = { overflow = false; vm.toggleFavoriteHere() },
                        )
                        DropdownMenuItem(text = { Text(stringResource(Res.string.places_title)) }, leadingIcon = { Icon(Icons.Outlined.Bookmarks, null) }, onClick = { overflow = false; places = true })
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.nav_transfers) + if (activeTransfers > 0) " ($activeTransfers)" else "") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.CompareArrows, null) },
                            onClick = { overflow = false; controller.navigate(Screen.Transfers) },
                        )
                        DropdownMenuItem(text = { Text(stringResource(Res.string.security_title)) }, leadingIcon = { Icon(Icons.Outlined.Lock, null) }, onClick = { overflow = false; onSecurity() })
                        DropdownMenuItem(text = { Text(stringResource(Res.string.nav_settings)) }, leadingIcon = { Icon(Icons.Outlined.Settings, null) }, onClick = { overflow = false; controller.navigate(Screen.Settings) })
                        DropdownMenuItem(text = { Text(stringResource(Res.string.action_disconnect)) }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, null) }, onClick = { overflow = false; controller.disconnect() })
                    }
                    PlacesMenu(places, state, settings.recents.filter { it.profileId == vm.profile.id }, { places = false }, vm)
                }
            }
        }
    }
}

/** Bottom bar shown while files are selected. */
@Composable
fun SelectionBottomBar(selected: List<Entry>, vm: BrowserViewModel, visible: List<Entry>, onDelete: (List<Entry>) -> Unit, onRename: (Entry) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.fillMaxWidth().height(60.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            NdIconButton(Icons.Outlined.Close, stringResource(Res.string.action_clear_selection), vm::clearSelection)
            NdIconButton(Icons.Outlined.SelectAll, stringResource(Res.string.action_select_all), { vm.selectAll(visible) })
            NdIconButton(Icons.Outlined.Download, stringResource(Res.string.action_download), { vm.download(selected) })
            if (selected.size == 1) NdIconButton(Icons.Outlined.DriveFileRenameOutline, stringResource(Res.string.action_rename), { onRename(selected.first()) })
            NdIconButton(Icons.Outlined.Delete, stringResource(Res.string.action_delete), { onDelete(selected) }, tint = MaterialTheme.colorScheme.error)
        }
    }
}

// ======================================================================== desktop command bar + address row

@Composable
fun DesktopCommandBar(
    controller: AppController,
    state: BrowserState,
    vm: BrowserViewModel,
    selected: List<Entry>,
    viewMode: ViewMode,
    detailsOpen: Boolean,
    onNewFolder: () -> Unit,
    onRename: (Entry) -> Unit,
    onDelete: (List<Entry>) -> Unit,
    onToggleDetails: () -> Unit,
) {
    var uploadMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var places by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    val settings by controller.settings.state.collectAsState()
    val toggleView = { controller.settings.update { it.copy(viewMode = if (viewMode == ViewMode.Grid) ViewMode.List else ViewMode.Grid) } }
    Surface(color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // below ~900 dp the secondary commands move into an overflow menu so nothing is clipped
            val narrow = maxWidth < 900.dp
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                ProfileAvatar(vm.profile, size = 32.dp)
                if (!narrow) {
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.widthIn(max = 220.dp)) {
                        Text(vm.profile.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(protocolLabelRes(vm.profile.protocol)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.width(8.dp))
                VerticalDivider(Modifier.height(24.dp))
                Spacer(Modifier.width(4.dp))
                CommandButton(Icons.Outlined.CreateNewFolder, stringResource(Res.string.folder_new_title), "Ctrl+N", onNewFolder)
                Box {
                    CommandButton(Icons.Outlined.UploadFile, stringResource(Res.string.action_upload), null, { uploadMenu = true })
                    DropdownMenu(uploadMenu, { uploadMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(Res.string.action_upload_files)) }, leadingIcon = { Icon(Icons.Outlined.UploadFile, null) }, onClick = { uploadMenu = false; vm.uploadFiles() })
                        DropdownMenuItem(text = { Text(stringResource(Res.string.action_upload_folder)) }, leadingIcon = { Icon(Icons.Outlined.DriveFolderUpload, null) }, onClick = { uploadMenu = false; vm.uploadFolder() })
                    }
                }
                CommandButton(Icons.Outlined.Download, stringResource(Res.string.action_download), null, { vm.download(selected) }, enabled = selected.isNotEmpty())
                CommandButton(Icons.Outlined.DriveFileRenameOutline, stringResource(Res.string.action_rename), "F2", { onRename(selected.first()) }, enabled = selected.size == 1)
                CommandButton(Icons.Outlined.Delete, stringResource(Res.string.action_delete), "Del", { onDelete(selected) }, enabled = selected.isNotEmpty())
                CommandButton(Icons.Outlined.Refresh, stringResource(Res.string.action_refresh), "F5", vm::refresh)
                Spacer(Modifier.weight(1f))
                if (!narrow) {
                    Box {
                        CommandButton(Icons.AutoMirrored.Outlined.Sort, stringResource(Res.string.sort_title), null, { sortMenu = true })
                        SortMenu(sortMenu, state, { sortMenu = false }, vm::setSort)
                    }
                    CommandButton(
                        if (viewMode == ViewMode.Grid) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView,
                        stringResource(if (viewMode == ViewMode.Grid) Res.string.view_list else Res.string.view_grid), null, toggleView,
                    )
                    CommandButton(
                        Icons.Outlined.ViewSidebar,
                        stringResource(if (detailsOpen) Res.string.details_hide else Res.string.details_show), "Alt+Enter", onToggleDetails,
                        tint = if (detailsOpen) MaterialTheme.colorScheme.primary else null,
                    )
                    VerticalDivider(Modifier.height(24.dp).padding(horizontal = 2.dp))
                    CommandButton(
                        if (state.isFavoriteHere) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                        stringResource(if (state.isFavoriteHere) Res.string.action_favorite_remove else Res.string.action_favorite_add), null, vm::toggleFavoriteHere,
                        tint = if (state.isFavoriteHere) MaterialTheme.colorScheme.tertiary else null,
                    )
                    CommandButton(Icons.Outlined.Bookmarks, stringResource(Res.string.places_title), null, { places = true })
                    CommandButton(Icons.AutoMirrored.Outlined.Logout, stringResource(Res.string.action_disconnect), null, controller::disconnect)
                } else {
                    Box {
                        CommandButton(Icons.Outlined.MoreVert, stringResource(Res.string.action_more), null, { overflow = true })
                        DropdownMenu(overflow, { overflow = false }) {
                            listOf(SortKey.NAME to Res.string.sort_name, SortKey.SIZE to Res.string.sort_size, SortKey.MTIME to Res.string.sort_date).forEach { (key, res) ->
                                val active = state.sortKey == key
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.sort_by, stringResource(res)), fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal) },
                                    leadingIcon = { if (active) Icon(if (state.sortDesc) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward, null) else Spacer(Modifier.size(24.dp)) },
                                    onClick = { overflow = false; vm.setSort(key) },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(if (viewMode == ViewMode.Grid) Res.string.view_list else Res.string.view_grid)) },
                                leadingIcon = { Icon(if (viewMode == ViewMode.Grid) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView, null) },
                                onClick = { overflow = false; toggleView() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(if (detailsOpen) Res.string.details_hide else Res.string.details_show)) },
                                leadingIcon = { Icon(Icons.Outlined.ViewSidebar, null) },
                                onClick = { overflow = false; onToggleDetails() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(if (state.isFavoriteHere) Res.string.action_favorite_remove else Res.string.action_favorite_add)) },
                                leadingIcon = { Icon(if (state.isFavoriteHere) Icons.Outlined.Star else Icons.Outlined.StarBorder, null) },
                                onClick = { overflow = false; vm.toggleFavoriteHere() },
                            )
                            DropdownMenuItem(text = { Text(stringResource(Res.string.places_title)) }, leadingIcon = { Icon(Icons.Outlined.Bookmarks, null) }, onClick = { overflow = false; places = true })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text(stringResource(Res.string.action_disconnect)) }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, null) }, onClick = { overflow = false; controller.disconnect() })
                        }
                    }
                }
                Box {
                    PlacesMenu(places, state, settings.recents.filter { it.profileId == vm.profile.id }, { places = false }, vm)
                }
            }
        }
    }
}

@Composable
private fun CommandButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, shortcut: String?, onClick: () -> Unit, enabled: Boolean = true, tint: androidx.compose.ui.graphics.Color? = null) {
    NdIconButton(icon, label, onClick, enabled = enabled, tint = tint, tooltip = if (shortcut != null) "$label ($shortcut)" else label)
}

@Composable
fun DesktopAddressRow(
    state: BrowserState,
    vm: BrowserViewModel,
    filterFocus: FocusRequester,
    onCopyPath: (String) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            NdIconButton(Icons.Outlined.ArrowUpward, stringResource(Res.string.action_up), vm::up, enabled = state.canGoUp, tooltip = stringResource(Res.string.action_up) + " (Alt+↑)")
            Box(Modifier.weight(1f)) { BreadcrumbBar(state, onOpen = vm::open, onCopyPath = onCopyPath) }
            Spacer(Modifier.width(8.dp))
            FilterField(state.filter, vm::setFilter, Modifier.width(220.dp), filterFocus, onEscape = { vm.setFilter("") })
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

// ======================================================================== status bar + security

@Composable
fun DesktopStatusBar(
    state: BrowserState,
    visible: List<Entry>,
    selectedCount: Int,
    tag: String,
    security: SecurityInfo?,
    activeTransfers: Int,
    transfersExpanded: Boolean,
    onToggleTransfers: () -> Unit,
    onSecurity: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val shown = visible.size
            val total = state.totalHint
            Text(
                if (total != null && total > state.entries.size && state.nextToken != null) pluralStringResource(Res.plurals.status_items_of, total, shown, total)
                else pluralStringResource(Res.plurals.status_items, shown, shown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (selectedCount > 0) {
                val bytes = BrowserListing.selectionBytes(state.entries, state.selection)
                Text(
                    " · " + pluralStringResource(Res.plurals.selected_count, selectedCount, selectedCount) + if (bytes > 0) " · " + Formatters.size(bytes, tag) else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.weight(1f))
            SecurityChip(security, onSecurity)
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onToggleTransfers, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(Icons.AutoMirrored.Outlined.CompareArrows, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(Res.string.nav_transfers) + if (activeTransfers > 0) " ($activeTransfers)" else "", style = MaterialTheme.typography.labelMedium)
                Icon(if (transfersExpanded) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess, null, Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun SecurityChip(info: SecurityInfo?, onClick: () -> Unit) {
    if (info == null) return
    val nd = MaterialTheme.nd
    val secure = info.encrypted
    val color = if (secure) nd.success else nd.warning
    val label = stringResource(if (secure) Res.string.security_chip_secure else Res.string.security_chip_insecure)
    val proto = stringResource(protocolLabelRes(info.protocol))
    Row(
        Modifier.clip(MaterialTheme.shapes.small).clickable(role = Role.Button, onClickLabel = stringResource(Res.string.security_title), onClick = onClick).heightIn(min = 28.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (secure) Icons.Outlined.Lock else Icons.Outlined.LockOpen, null, Modifier.size(16.dp), tint = color)
        Spacer(Modifier.width(6.dp))
        Text("$proto · $label", style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
fun SecurityDialog(info: SecurityInfo?, profileName: String, onDismiss: () -> Unit) {
    val nd = MaterialTheme.nd
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (info?.encrypted == false) Icons.Outlined.LockOpen else Icons.Outlined.Lock, null, tint = if (info?.encrypted == false) nd.warning else nd.success) },
        title = { Text(stringResource(Res.string.security_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (info == null) {
                    Text(stringResource(Res.string.security_none))
                } else {
                    DetailLine(stringResource(Res.string.detail_name), profileName)
                    DetailLine(stringResource(Res.string.security_protocol), stringResource(protocolLabelRes(info.protocol)))
                    DetailLine(
                        stringResource(Res.string.security_transport),
                        stringResource(if (info.encrypted) Res.string.security_transport_encrypted else Res.string.security_transport_plain),
                    )
                    if (!info.encrypted) {
                        StatusBanner(BannerKind.Warning, stringResource(Res.string.security_plain_warning))
                    }
                    if (info.fingerprint != null) {
                        Text(
                            stringResource(if (info.kind == SecurityInfo.Kind.Certificate) Res.string.security_cert else Res.string.security_host_key) + (info.algo?.let { " ($it)" } ?: ""),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.Top) {
                            FingerprintBlocks(info.fingerprint, modifier = Modifier.weight(1f))
                            CopyButton(info.fingerprint, stringResource(Res.string.action_copy_fingerprint))
                        }
                    } else if (info.encrypted && info.protocol == ProtocolId.SFTP) {
                        Text(stringResource(Res.string.security_pinned_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_close)) } },
    )
}

@Composable
fun DetailLine(label: String, value: String, mono: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(value, style = if (mono) MonoStyle else MaterialTheme.typography.bodyMedium)
        }
    }
}

// ======================================================================== details pane

@Composable
fun DetailsPane(
    state: BrowserState,
    selected: List<Entry>,
    profileName: String,
    tag: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.details_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                NdIconButton(Icons.Outlined.Close, stringResource(Res.string.details_hide), onClose)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                DetailsContent(state, selected, profileName, tag)
            }
        }
    }
}

@Composable
fun DetailsContent(state: BrowserState, selected: List<Entry>, profileName: String, tag: String) {
    when {
        selected.size == 1 -> {
            val e = selected.first()
            val cat = FileKinds.categoryOf(e)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(categoryIcon(cat), null, Modifier.size(40.dp), tint = categoryTint(cat))
                Spacer(Modifier.width(12.dp))
                Text(e.name, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            DetailLine(stringResource(Res.string.detail_type), stringResource(categoryLabelRes(cat)))
            if (e.kind == EntryKind.FILE) DetailLine(stringResource(Res.string.detail_size), e.size?.let { Formatters.size(it, tag) + " (" + it + " B)" } ?: "—")
            DetailLine(stringResource(Res.string.detail_modified), e.mtimeMs?.let { Formatters.dateTime(it, tag) } ?: "—")
            val folder = BreadcrumbNav.displayPath(state.breadcrumbs)
            DetailLine(stringResource(Res.string.detail_location), (if (folder.endsWith("/")) folder else "$folder/") + e.name, mono = true)
        }
        selected.size > 1 -> {
            Text(pluralStringResource(Res.plurals.selected_count, selected.size, selected.size), style = MaterialTheme.typography.titleMedium)
            val bytes = BrowserListing.selectionBytes(state.entries, state.selection)
            DetailLine(stringResource(Res.string.detail_total_size), Formatters.size(bytes, tag))
            val dirs = selected.count { it.kind == EntryKind.DIR }
            if (dirs > 0) Text(pluralStringResource(Res.plurals.detail_folders_note, dirs, dirs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> {
            Icon(Icons.Outlined.Folder, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Text(state.breadcrumbs.lastOrNull()?.name ?: profileName, style = MaterialTheme.typography.titleMedium)
            DetailLine(stringResource(Res.string.detail_location), BreadcrumbNav.displayPath(state.breadcrumbs), mono = true)
            DetailLine(stringResource(Res.string.detail_items), pluralStringResource(Res.plurals.status_items, state.entries.size, state.entries.size))
            Text(stringResource(Res.string.details_empty_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ======================================================================== dialogs

@Composable
fun NameDialog(
    title: String,
    label: String,
    initial: String,
    confirmLabel: String,
    onSubmit: suspend (String) -> ErrInfo?,
    onDismiss: () -> Unit,
    selectBaseName: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var value by remember {
        val dot = initial.lastIndexOf('.')
        val end = if (selectBaseName && dot > 0) dot else initial.length
        mutableStateOf(TextFieldValue(initial, TextRange(0, end)))
    }
    var error by remember { mutableStateOf<ErrInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun submit() {
        val text = value.text
        if (busy || text.isBlank()) return
        busy = true
        scope.launch {
            val err = onSubmit(text)
            busy = false
            if (err == null) onDismiss() else error = err
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it; error = null },
                singleLine = true,
                label = { Text(label) },
                isError = error != null,
                supportingText = error?.let { e -> { Text(errorText(e)) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { if (error != null) liveRegion = LiveRegionMode.Polite },
            )
        },
        confirmButton = { Button(onClick = ::submit, enabled = !busy && value.text.isNotBlank()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

/** Delete confirmation: names and count, recursive warning highlighted, danger button; Cancel has the initial focus. */
@Composable
fun DeleteDialog(entries: List<Entry>, onDismiss: () -> Unit, onConfirm: (recursive: Boolean) -> Unit) {
    val hasDirs = entries.any { it.kind == EntryKind.DIR }
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(pluralStringResource(Res.plurals.delete_title, entries.size, entries.size)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (entries.size == 1) {
                    Text(stringResource(Res.string.delete_body_one, entries.first().name))
                } else {
                    Text(pluralStringResource(Res.plurals.delete_body_many, entries.size, entries.size))
                    entries.take(5).forEach { e ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(categoryIcon(FileKinds.categoryOf(e)), null, Modifier.size(18.dp), tint = categoryTint(FileKinds.categoryOf(e)))
                            Spacer(Modifier.width(8.dp))
                            Text(e.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (entries.size > 5) Text(stringResource(Res.string.delete_names_more, entries.size - 5), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (hasDirs) {
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.errorContainer).padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Text(stringResource(Res.string.delete_recursive_warning), color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(stringResource(Res.string.delete_irreversible), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(hasDirs) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text(stringResource(Res.string.action_delete)) }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss, modifier = Modifier.focusRequester(cancelFocus)) { Text(stringResource(Res.string.action_cancel)) } },
    )
}
