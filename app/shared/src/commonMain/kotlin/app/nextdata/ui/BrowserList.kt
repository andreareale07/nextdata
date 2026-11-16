package app.nextdata.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.nextdata.protocol.Entry
import app.nextdata.protocol.EntryKind
import app.nextdata.protocol.SortKey
import app.nextdata.shared.resources.*
import app.nextdata.state.BrowserState
import app.nextdata.state.BrowserViewModel
import app.nextdata.state.FileCategory
import app.nextdata.state.FileKinds
import app.nextdata.state.Formatters
import app.nextdata.state.ViewMode
import app.nextdata.state.isConnectionLost
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** One action in a context/overflow menu or bottom sheet. */
class EntryAction(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String, val destructive: Boolean = false, val enabled: Boolean = true, val onClick: () -> Unit)

fun categoryLabelRes(c: FileCategory): StringResource = when (c) {
    FileCategory.Folder -> Res.string.type_folder
    FileCategory.Link -> Res.string.type_link
    FileCategory.Special -> Res.string.type_special
    FileCategory.Image -> Res.string.type_image
    FileCategory.Audio -> Res.string.type_audio
    FileCategory.Video -> Res.string.type_video
    FileCategory.Archive -> Res.string.type_archive
    FileCategory.Pdf -> Res.string.type_pdf
    FileCategory.Document -> Res.string.type_document
    FileCategory.Spreadsheet -> Res.string.type_spreadsheet
    FileCategory.Presentation -> Res.string.type_presentation
    FileCategory.Code -> Res.string.type_code
    FileCategory.Text -> Res.string.type_text
    FileCategory.Generic -> Res.string.type_file
}

/** Widths of the resizable table columns (the Name column takes the rest). */
@Stable
class ColumnWidths(size: Dp = 96.dp, date: Dp = 168.dp, type: Dp = 132.dp) {
    var size by mutableStateOf(size)
    var date by mutableStateOf(date)
    var type by mutableStateOf(type)

    companion object {
        val MinCol = 64.dp
        val MaxCol = 320.dp
    }
}

/** Where a context menu is open: on [entry] (null = empty area) at [offset] inside the list container. */
class MenuAnchor(val entry: Entry?, val offset: DpOffset)

/**
 * The folder contents: skeleton while loading, empty/no-match/error states, then list, table or grid.
 * [touch] switches interaction (tap opens, long-press selects) vs pointer (click selects, double-click opens).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileCollection(
    state: BrowserState,
    visible: List<Entry>,
    viewMode: ViewMode,
    phoneRows: Boolean,
    touch: Boolean,
    widths: ColumnWidths,
    showTypeColumn: Boolean,
    tag: String,
    vm: BrowserViewModel,
    listState: LazyListState,
    gridState: LazyGridState,
    onOpenEntry: (Entry) -> Unit,
    onMore: (Entry) -> Unit,
    menuFor: (Entry?) -> List<EntryAction>,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spec = LocalDensitySpec.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val modifiers = LocalWindowInfo.current

    // auto-load the next page when the end of the list comes into view
    val nearEndList by remember(state.nextToken) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - 4
        }
    }
    val nearEndGrid by remember(state.nextToken) {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - 8
        }
    }
    val nearEnd = if (viewMode == ViewMode.Grid) nearEndGrid else nearEndList
    LaunchedEffect(nearEnd, state.nextToken, state.entries.size) {
        if (nearEnd && state.nextToken != null) vm.loadMore()
    }
    // keep the keyboard cursor in view
    LaunchedEffect(state.lead, viewMode) {
        val idx = visible.indexOfFirst { it.ref == state.lead }
        if (idx >= 0) {
            if (viewMode == ViewMode.Grid) {
                val vis = gridState.layoutInfo.visibleItemsInfo
                if (vis.none { it.index == idx }) gridState.animateScrollToItem(idx)
            } else {
                val vis = listState.layoutInfo.visibleItemsInfo
                if (vis.isEmpty() || idx < vis.first().index || idx > vis.last().index - 1) listState.animateScrollToItem(maxOf(0, idx - 2))
            }
        }
    }

    var menu by remember { mutableStateOf<MenuAnchor?>(null) }
    // a new filter / folder starts at the top of the list
    LaunchedEffect(state.filter) { listState.scrollToItem(0); gridState.scrollToItem(0) }
    LaunchedEffect(state.ref) { listState.scrollToItem(0); gridState.scrollToItem(0) }

    fun entryAt(px: androidx.compose.ui.geometry.Offset): Entry? {
        return if (viewMode == ViewMode.Grid) {
            gridState.layoutInfo.visibleItemsInfo.firstOrNull { i ->
                px.x >= i.offset.x && px.x < i.offset.x + i.size.width && px.y >= i.offset.y && px.y < i.offset.y + i.size.height
            }?.let { visible.getOrNull(it.index) }
        } else {
            listState.layoutInfo.visibleItemsInfo.firstOrNull { i -> px.y >= i.offset && px.y < i.offset + i.size }?.let { visible.getOrNull(it.index) }
        }
    }

    // desktop: rows can be dragged (to the PC / onto folders) and folders accept drops
    val dragHost = LocalEntryDrag.current
    @Composable
    fun dragExtra(e: Entry): Modifier = if (dragHost == null || touch) Modifier else
        dragHost.source(vm) { dragSet(e, state.selection, visible) }.folderDropTarget(e.ref, e.name, e.kind == EntryKind.DIR, vm)

    val err = state.error
    val body: @Composable () -> Unit = {
        when {
            err != null && state.entries.isEmpty() -> ErrorState(
                err,
                onRetry = if (err.isConnectionLost()) onReconnect else vm::retry,
                retryLabel = if (err.isConnectionLost()) stringResource(Res.string.action_reconnect) else null,
                secondaryLabel = if (state.ref != null) stringResource(Res.string.action_back) else null,
                onSecondary = if (state.ref != null) vm::dismissError else null,
            )
            state.loading && state.entries.isEmpty() -> if (viewMode == ViewMode.Grid) SkeletonGrid() else SkeletonList()
            visible.isEmpty() && state.filter.isNotBlank() -> EmptyState(
                icon = Icons.Outlined.SearchOff,
                title = stringResource(Res.string.filter_no_results_title),
                body = stringResource(Res.string.filter_no_results_body, state.filter),
                action = { OutlinedButton(onClick = { vm.setFilter("") }) { Text(stringResource(Res.string.filter_clear)) } },
            )
            visible.isEmpty() && !state.loading -> EmptyState(
                icon = Icons.Outlined.FolderOpen,
                title = stringResource(Res.string.folder_empty_title),
                body = stringResource(if (touch) Res.string.folder_empty_body_phone else Res.string.folder_empty_body_desktop),
            )
            viewMode == ViewMode.Grid -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(spec.gridCell),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = if (touch) 96.dp else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visible, key = { it.ref }) { e ->
                    GridTile(
                        extra = dragExtra(e),
                        entry = e,
                        selected = e.ref in state.selection,
                        lead = e.ref == state.lead,
                        selectionMode = state.selectionMode,
                        touch = touch,
                        onClick = { handleClick(vm, visible, e, touch, state, modifiers, onOpenEntry, onMore) },
                        onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); vm.toggleSelection(e.ref) },
                        onDoubleClick = { onOpenEntry(e) },
                    )
                }
                if (state.nextToken != null) item(key = "__more", span = { GridItemSpan(maxLineSpan) }) { LoadMoreFooter(state, vm) }
            }
            else -> LazyColumn(state = listState, contentPadding = PaddingValues(bottom = if (touch) 96.dp else 8.dp)) {
                items(visible, key = { it.ref }) { e ->
                    EntryRow(
                        extra = dragExtra(e),
                        entry = e,
                        tag = tag,
                        phoneRows = phoneRows,
                        touch = touch,
                        widths = widths,
                        showType = showTypeColumn,
                        selected = e.ref in state.selection,
                        lead = e.ref == state.lead,
                        selectionMode = state.selectionMode,
                        favorite = state.favorites.any { it.ref == e.ref },
                        onMore = { onMore(e) },
                        onClick = { handleClick(vm, visible, e, touch, state, modifiers, onOpenEntry, onMore) },
                        onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); vm.toggleSelection(e.ref) },
                        onDoubleClick = { onOpenEntry(e) },
                        onToggle = { vm.toggleSelection(e.ref) },
                    )
                }
                if (state.nextToken != null) item(key = "__more") { LoadMoreFooter(state, vm) }
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .then(
                if (touch) Modifier else Modifier.onSecondaryClick { px ->
                    val hit = entryAt(px)
                    if (hit != null && hit.ref !in state.selection) vm.selectOnly(hit.ref)
                    if (hit == null) vm.clearSelection()
                    menu = MenuAnchor(hit, with(density) { DpOffset(px.x.toDp(), px.y.toDp()) })
                },
            ),
    ) {
        if (touch) {
            PullToRefreshBox(isRefreshing = state.loading && state.entries.isNotEmpty(), onRefresh = vm::refresh) { body() }
        } else {
            body()
            if (state.loading && state.entries.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
        menu?.let { m ->
            // a 1 dp anchor at the click point, so the menu opens right where the pointer is
            Box(Modifier.offset(m.offset.x, m.offset.y).size(1.dp)) {
                DropdownMenu(expanded = true, onDismissRequest = { menu = null }) {
                    menuFor(m.entry).forEach { a ->
                        DropdownMenuItem(
                            text = { Text(a.label, color = if (a.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
                            leadingIcon = { Icon(a.icon, null, tint = if (a.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) },
                            enabled = a.enabled,
                            onClick = { menu = null; a.onClick() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadMoreFooter(state: BrowserState, vm: BrowserViewModel) {
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (state.loadingMore) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
        else TextButton(onClick = vm::loadMore) { Text(stringResource(Res.string.action_load_more)) }
    }
}

private fun handleClick(
    vm: BrowserViewModel,
    visible: List<Entry>,
    e: Entry,
    touch: Boolean,
    state: BrowserState,
    window: androidx.compose.ui.platform.WindowInfo,
    onOpen: (Entry) -> Unit,
    onMore: (Entry) -> Unit,
) {
    if (touch) {
        when {
            state.selectionMode -> vm.toggleSelection(e.ref)
            e.kind == EntryKind.DIR || e.kind == EntryKind.SYMLINK -> onOpen(e)
            else -> onOpen(e)
        }
    } else {
        val m = window.keyboardModifiers
        vm.click(visible, e.ref, ctrl = m.isCtrlPressed, shift = m.isShiftPressed)
    }
}

// ======================================================================== rows

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    entry: Entry,
    tag: String,
    phoneRows: Boolean,
    touch: Boolean,
    widths: ColumnWidths,
    showType: Boolean,
    selected: Boolean,
    lead: Boolean,
    selectionMode: Boolean,
    favorite: Boolean,
    onMore: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onToggle: () -> Unit,
    extra: Modifier = Modifier,
) {
    val spec = LocalDensitySpec.current
    val category = FileKinds.categoryOf(entry)
    val sizeText = entry.size?.takeIf { entry.kind == EntryKind.FILE }?.let { Formatters.size(it, tag) }.orEmpty()
    val dateText = entry.mtimeMs?.let { Formatters.dateTime(it, tag) }.orEmpty()
    val typeText = stringResource(categoryLabelRes(category))
    val selectedDesc = stringResource(Res.string.state_selected)
    val bg = when {
        selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    val interaction = remember { MutableInteractionSource() }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = spec.rowHeight)
            .background(bg)
            .then(extra)
            .combinedClickable(
                interactionSource = interaction,
                indication = androidx.compose.material3.ripple(),
                onClick = onClick,
                onLongClick = onLongClick,
                onDoubleClick = if (touch) null else onDoubleClick,
            )
            .then(if (lead && !touch) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), shape) else Modifier)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                if (selected) stateDescription = selectedDesc
            }
            .padding(horizontal = if (phoneRows) 16.dp else 8.dp, vertical = if (phoneRows) 8.dp else if (spec.compact) 2.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!touch || selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
        }
        Icon(categoryIcon(category), contentDescription = null, tint = categoryTint(category), modifier = Modifier.size(spec.iconSize))
        Spacer(Modifier.width(if (phoneRows) 16.dp else 12.dp))
        if (phoneRows) {
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val sub = listOf(sizeText, dateText).filter { it.isNotEmpty() }.joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            if (favorite) Icon(Icons.Outlined.Star, stringResource(Res.string.state_favorite), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
            if (!selectionMode) NdIconButton(Icons.Outlined.MoreVert, stringResource(Res.string.action_more), onMore)
        } else {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(entry.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (favorite) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Outlined.Star, stringResource(Res.string.state_favorite), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
                }
            }
            Text(sizeText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(widths.size).padding(end = 8.dp))
            Text(dateText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(widths.date).padding(end = 8.dp))
            if (showType) Text(typeText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(widths.type).padding(end = 8.dp))
            if (touch) NdIconButton(Icons.Outlined.MoreVert, stringResource(Res.string.action_more), onMore)
            else Spacer(Modifier.width(4.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridTile(
    entry: Entry,
    selected: Boolean,
    lead: Boolean,
    selectionMode: Boolean,
    touch: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDoubleClick: () -> Unit,
    extra: Modifier = Modifier,
) {
    val spec = LocalDensitySpec.current
    val category = FileKinds.categoryOf(entry)
    val selectedDesc = stringResource(Res.string.state_selected)
    val shape = MaterialTheme.shapes.medium
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
            .then(if (lead && !touch) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .then(extra)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onDoubleClick = if (touch) null else onDoubleClick)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                if (selected) stateDescription = selectedDesc
            }
            .heightIn(min = spec.gridCell * 0.9f),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.padding(8.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(categoryIcon(category), null, tint = categoryTint(category), modifier = Modifier.size(spec.iconSize * 2))
            Spacer(Modifier.height(6.dp))
            Text(entry.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        if (selected) {
            Icon(
                Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
            )
        }
    }
}

// ======================================================================== column header (sortable + resizable)

@Composable
fun TableHeader(state: BrowserState, widths: ColumnWidths, showType: Boolean, showCheckbox: Boolean, selectAllChecked: Boolean, onSelectAll: (Boolean) -> Unit, onSort: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp + 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val a11y = stringResource(Res.string.action_select_all)
            // same geometry as the rows: the checkbox column exists only where the rows show it
            if (showCheckbox) Checkbox(checked = selectAllChecked, onCheckedChange = onSelectAll, modifier = Modifier.semantics { contentDescription = a11y })
            Spacer(Modifier.width(LocalDensitySpec.current.iconSize + 12.dp))
            HeaderCell(stringResource(Res.string.sort_name), SortKey.NAME, state, onSort, Modifier.weight(1f), TextAlign.Start, null)
            HeaderCell(stringResource(Res.string.sort_size), SortKey.SIZE, state, onSort, Modifier.width(widths.size), TextAlign.End) { widths.size = (widths.size - it).coerceIn(ColumnWidths.MinCol, ColumnWidths.MaxCol) }
            HeaderCell(stringResource(Res.string.sort_date), SortKey.MTIME, state, onSort, Modifier.width(widths.date), TextAlign.End) { widths.date = (widths.date - it).coerceIn(ColumnWidths.MinCol, ColumnWidths.MaxCol) }
            if (showType) HeaderCell(stringResource(Res.string.col_type), null, state, onSort, Modifier.width(widths.type), TextAlign.Start) { widths.type = (widths.type - it).coerceIn(ColumnWidths.MinCol, ColumnWidths.MaxCol) }
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun HeaderCell(
    label: String,
    key: String?,
    state: BrowserState,
    onSort: (String) -> Unit,
    modifier: Modifier,
    align: TextAlign,
    /** Called with the drag delta (dp, positive = to the right) when the column's left edge is dragged; null = fixed. */
    onResize: ((Dp) -> Unit)?,
) {
    val active = key != null && state.sortKey == key
    val density = LocalDensity.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (onResize != null) {
            // the resize grip sits on the left edge of the column (like Explorer's separators)
            Box(
                Modifier
                    .width(8.dp)
                    .height(28.dp)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { change, dx ->
                            change.consume()
                            onResize(with(density) { dx.toDp() })
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.width(1.dp).fillMaxHeight(0.6f).background(MaterialTheme.colorScheme.outlineVariant)) }
        }
        Row(
            Modifier
                .weight(1f)
                .then(if (key != null) Modifier.clip(MaterialTheme.shapes.small).clickable(role = Role.Button) { onSort(key) } else Modifier)
                .padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalArrangement = if (align == TextAlign.End) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            AnimatedVisibility(active, enter = fadeIn(tween(120)) + expandHorizontally(tween(120)), exit = fadeOut(tween(120)) + shrinkHorizontally(tween(120))) {
                Icon(if (state.sortDesc) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward, null, Modifier.size(14.dp).padding(start = 2.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
