package app.nextdata.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nextdata.i18n.LocalLanguageTag
import app.nextdata.i18n.errorText
import app.nextdata.platform.PlatformKind
import app.nextdata.protocol.Entry
import app.nextdata.protocol.EntryKind
import app.nextdata.shared.resources.*
import app.nextdata.state.AppController
import app.nextdata.state.BrowserListing
import app.nextdata.state.BrowserViewModel
import app.nextdata.state.UiMessage
import app.nextdata.state.isConnectionLost
import app.nextdata.state.ViewMode
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The file browser for the open session. [compact] = single-pane phone layout (collapsing top bar, FAB);
 * otherwise the three-zone layout (command bar, content, optional details pane, status bar), used by
 * desktop and by tablets / landscape phones.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun BrowserScreen(
    controller: AppController,
    vm: BrowserViewModel,
    compact: Boolean,
    transfersExpanded: Boolean = false,
    onToggleTransfers: () -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val settings by controller.settings.state.collectAsState()
    val transfers by controller.transfers.items.collectAsState()
    val security by controller.security.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val tag = LocalLanguageTag.current
    val touch = controller.env.kind == PlatformKind.Android
    val viewMode = settings.viewMode
    val copy = rememberCopyToClipboard()

    val visible = remember(state.entries, settings.showHidden, state.filter) {
        BrowserListing.visible(state.entries, settings.showHidden, state.filter)
    }
    val selected = remember(state.selection, state.entries) { state.entries.filter { it.ref in state.selection } }

    var newFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Entry?>(null) }
    var deleting by remember { mutableStateOf<List<Entry>?>(null) }
    var sheetFor by remember { mutableStateOf<Entry?>(null) }
    var fabSheet by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }
    var securityOpen by remember { mutableStateOf(false) }
    var detailsFor by remember { mutableStateOf<Entry?>(null) }
    val widths = remember { ColumnWidths() }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val filterFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }
    val dialogOpen = newFolder || renaming != null || deleting != null || sheetFor != null || fabSheet || securityOpen || detailsFor != null

    // snackbar feedback; a finished download offers "Show" to open the destination
    val msg = state.message
    val msgText = msg?.let { messageText(it) }
    val showLabel = stringResource(Res.string.action_show)
    LaunchedEffect(state.message) {
        if (msg != null && msgText != null) {
            val loc = (msg as? UiMessage.DownloadDone)?.location
            val r = snackbar.showSnackbar(msgText, actionLabel = if (loc != null) showLabel else null, withDismissAction = true, duration = if (loc != null) SnackbarDuration.Long else SnackbarDuration.Short)
            vm.consumeMessage()
            if (r == SnackbarResult.ActionPerformed && loc != null) vm.openLocation(loc)
        }
    }

    // keyboard focus is (re)claimed by the browser whenever no dialog is open
    LaunchedEffect(dialogOpen) { if (!dialogOpen) runCatching { rootFocus.requestFocus() } }

    fun openEntry(e: Entry) {
        if (e.kind == EntryKind.DIR || e.kind == EntryKind.SYMLINK) vm.open(e.ref) else vm.openFile(e)
    }

    fun toggleDetails() = controller.settings.update { it.copy(detailsOpen = !it.detailsOpen) }

    val keyModifier = Modifier
        .focusRequester(rootFocus)
        .focusable()
        .onKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown || dialogOpen) return@onKeyEvent false
            val ctrl = e.isCtrlPressed
            val shift = e.isShiftPressed
            when {
                e.key == Key.F5 || (ctrl && e.key == Key.R) -> { vm.refresh(); true }
                e.key == Key.F2 && selected.size == 1 -> { renaming = selected.first(); true }
                e.key == Key.Delete && selected.isNotEmpty() -> { deleting = selected; true }
                ctrl && e.key == Key.A -> { vm.selectAll(visible); true }
                ctrl && e.key == Key.N -> { newFolder = true; true }
                ctrl && e.key == Key.F -> { if (compact) filterOpen = true; runCatching { filterFocus.requestFocus() }; true }
                e.isAltPressed && e.key == Key.DirectionUp -> { if (state.canGoUp) vm.up(); true }
                e.isAltPressed && e.key == Key.Enter -> { toggleDetails(); true }
                e.key == Key.Backspace && state.canGoUp -> { vm.up(); true }
                e.key == Key.Escape -> when {
                    state.filter.isNotEmpty() -> { vm.setFilter(""); true }
                    state.selectionMode -> { vm.clearSelection(); true }
                    else -> false
                }
                e.key == Key.Enter && selected.size == 1 -> { openEntry(selected.first()); true }
                e.key == Key.DirectionDown -> { vm.moveLead(visible, +1, shift); true }
                e.key == Key.DirectionUp -> { vm.moveLead(visible, -1, shift); true }
                e.key == Key.PageDown -> { vm.moveLead(visible, +10, shift); true }
                e.key == Key.PageUp -> { vm.moveLead(visible, -10, shift); true }
                e.key == Key.MoveHome -> { vm.moveLead(visible, -1, shift, absolute = true); true }
                e.key == Key.MoveEnd -> { vm.moveLead(visible, +1, shift, absolute = true); true }
                else -> false
            }
        }

    // back: first undo transient UI state (selection, search), then navigate up / disconnect (Shell)
    BackHandler(enabled = state.selectionMode || filterOpen || state.filter.isNotEmpty()) {
        when {
            state.selectionMode -> vm.clearSelection()
            else -> { vm.setFilter(""); filterOpen = false }
        }
    }

    val dlLabel = stringResource(Res.string.action_download)
    val renameLabel = stringResource(Res.string.action_rename)
    val deleteLabel = stringResource(Res.string.action_delete)
    val openLabel = stringResource(Res.string.action_open)
    val favAddLabel = stringResource(Res.string.action_favorite_add)
    val favRemoveLabel = stringResource(Res.string.action_favorite_remove)
    val detailsLabel = stringResource(Res.string.details_title)
    val newFolderLabel = stringResource(Res.string.folder_new_title)
    val uploadLabel = stringResource(Res.string.action_upload)
    val uploadFolderLabel = stringResource(Res.string.action_upload_folder)
    val refreshLabel = stringResource(Res.string.action_refresh)
    val selectAllLabel = stringResource(Res.string.action_select_all)

    /** Actions for [e] (applied to the whole selection when [e] is part of it); null = empty area. */
    fun menuFor(e: Entry?): List<EntryAction> = buildList {
        if (e == null) {
            add(EntryAction(Icons.Outlined.CreateNewFolder, newFolderLabel) { newFolder = true })
            add(EntryAction(Icons.Outlined.UploadFile, uploadLabel) { vm.upload() })
            if (!vm.mixedUpload) add(EntryAction(Icons.Outlined.UploadFile, uploadFolderLabel) { vm.uploadFolder() })
            add(EntryAction(Icons.Outlined.Refresh, refreshLabel) { vm.refresh() })
            add(EntryAction(Icons.Outlined.SelectAll, selectAllLabel) { vm.selectAll(visible) })
            return@buildList
        }
        val targets = if (e.ref in state.selection) selected else listOf(e)
        val single = targets.size == 1
        if (single && (e.kind == EntryKind.DIR || e.kind == EntryKind.SYMLINK)) add(EntryAction(Icons.Outlined.FolderOpen, openLabel) { vm.open(e.ref) })
        add(EntryAction(Icons.Outlined.Download, dlLabel) { vm.download(targets) })
        if (single) add(EntryAction(Icons.Outlined.DriveFileRenameOutline, renameLabel) { renaming = e })
        if (single && e.kind == EntryKind.DIR) {
            val fav = state.favorites.any { it.ref == e.ref }
            add(EntryAction(if (fav) Icons.Outlined.Star else Icons.Outlined.StarBorder, if (fav) favRemoveLabel else favAddLabel) { vm.toggleFavorite(e.ref, e.name) })
        }
        if (single) add(EntryAction(Icons.Outlined.Info, detailsLabel) { if (compact) detailsFor = e else if (!settings.detailsOpen) toggleDetails() })
        add(EntryAction(Icons.Outlined.Delete, deleteLabel, destructive = true) { deleting = targets })
    }

    val banner: @Composable () -> Unit = {
        TransfersStrip(controller)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.preparing) {
            Column(Modifier.fillMaxWidth()) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(Res.string.upload_preparing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
            }
        }
        val err = state.error
        AnimatedVisibility(err != null && state.entries.isNotEmpty(), enter = expandVertically(tween(motionMs(160))) + fadeIn(tween(motionMs(160))), exit = shrinkVertically(tween(motionMs(120))) + fadeOut(tween(motionMs(120)))) {
            if (err != null) StatusBanner(
                BannerKind.Error, errorText(err),
                actionLabel = stringResource(if (err.isConnectionLost()) Res.string.action_reconnect else Res.string.action_retry),
                onAction = if (err.isConnectionLost()) controller::reconnect else vm::retry,
                onDismiss = vm::dismissError, dismissLabel = stringResource(Res.string.action_close),
            )
        }
    }

    if (compact) {
        Box(Modifier.fillMaxSize().then(keyModifier)) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.surface,
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    CompactHeader(
                        if (state.canGoUp) state.breadcrumbs.lastOrNull()?.name ?: vm.profile.name else vm.profile.name,
                        if (state.selectionMode) state.selection.size else 0,
                    )
                },
                bottomBar = {
                    if (state.selectionMode) SelectionBottomBar(selected, vm, visible, onDelete = { deleting = it }, onRename = { renaming = it })
                    else CompactBottomBar(
                        controller, state, vm, viewMode, transfers.count { it.status.isActive },
                        onSearch = { filterOpen = !filterOpen; if (!filterOpen) vm.setFilter("") },
                        onSecurity = { securityOpen = true },
                        onAdd = { fabSheet = true },
                    )
                },
            ) { pad ->
                Column(Modifier.padding(pad).fillMaxSize()) {
                    BreadcrumbBar(state, onOpen = vm::open, onCopyPath = { copy(it); vm.notifyPathCopied() }, vm = vm)
                    AnimatedVisibility(filterOpen) {
                        LaunchedEffect(Unit) { runCatching { filterFocus.requestFocus() } }
                        FilterField(state.filter, vm::setFilter, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), filterFocus, onEscape = { vm.setFilter(""); filterOpen = false })
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    banner()
                    FileCollection(
                        state, visible, viewMode, phoneRows = true, touch = true, widths = widths, showTypeColumn = false, tag = tag, vm = vm,
                        listState = listState, gridState = gridState, onOpenEntry = ::openEntry, onMore = { sheetFor = it }, menuFor = ::menuFor, onReconnect = controller::reconnect,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    } else {
        Box(Modifier.fillMaxSize().then(keyModifier)) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.surface,
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    Column {
                        DesktopCommandBar(controller, state, vm, selected, viewMode, settings.detailsOpen, { newFolder = true }, { renaming = it }, { deleting = it }, ::toggleDetails)
                        DesktopAddressRow(state, vm, filterFocus, onCopyPath = { copy(it); vm.notifyPathCopied() })
                        banner()
                    }
                },
                bottomBar = {
                    DesktopStatusBar(
                        state, visible, selected.size, tag, security,
                        transfers.count { it.status.isActive }, transfersExpanded, onToggleTransfers, onSecurity = { securityOpen = true },
                    )
                },
            ) { pad ->
                BoxWithConstraints(Modifier.padding(pad).fillMaxSize()) {
                    val detailsOpen = settings.detailsOpen
                    val listWidth = maxWidth - if (detailsOpen) 300.dp else 0.dp
                    Row(Modifier.fillMaxSize()) {
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            if (viewMode == ViewMode.List) {
                                TableHeader(
                                    state, widths, showType = listWidth > 760.dp, showCheckbox = !touch || state.selectionMode,
                                    selectAllChecked = visible.isNotEmpty() && visible.all { it.ref in state.selection },
                                    onSelectAll = { checked -> if (checked) vm.selectAll(visible) else vm.clearSelection() },
                                    onSort = vm::setSort,
                                )
                            }
                            FileCollection(
                                state, visible, viewMode, phoneRows = false, touch = touch, widths = widths, showTypeColumn = listWidth > 760.dp, tag = tag, vm = vm,
                                listState = listState, gridState = gridState, onOpenEntry = ::openEntry, onMore = { sheetFor = it }, menuFor = ::menuFor, onReconnect = controller::reconnect,
                                modifier = Modifier.weight(1f).pointerInput(Unit) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                        runCatching { rootFocus.requestFocus() }
                                    }
                                },
                            )
                        }
                        AnimatedVisibility(detailsOpen, enter = fadeIn(tween(motionMs(160))), exit = fadeOut(tween(motionMs(120)))) {
                            Row {
                                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                DetailsPane(state, selected, vm.profile.name, tag, onClose = ::toggleDetails, modifier = Modifier.width(300.dp).fillMaxHeight())
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- dialogs and sheets
    if (newFolder) {
        NameDialog(
            title = stringResource(Res.string.folder_new_title),
            label = stringResource(Res.string.field_folder_name),
            initial = "",
            confirmLabel = stringResource(Res.string.action_create),
            onSubmit = { vm.mkdir(it) },
            onDismiss = { newFolder = false },
        )
    }
    renaming?.let { e ->
        NameDialog(
            title = stringResource(Res.string.rename_title),
            label = stringResource(Res.string.field_new_name),
            initial = e.name,
            confirmLabel = stringResource(Res.string.action_rename),
            selectBaseName = e.kind == EntryKind.FILE,
            onSubmit = { vm.rename(e, it) },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { list ->
        DeleteDialog(list, onDismiss = { deleting = null }, onConfirm = { recursive ->
            deleting = null
            scope.launch { vm.delete(list, recursive) }
        })
    }
    if (securityOpen) SecurityDialog(security, vm.profile.name, onDismiss = { securityOpen = false })
    detailsFor?.let { e ->
        AlertDialog(
            onDismissRequest = { detailsFor = null },
            title = { Text(stringResource(Res.string.details_title)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { DetailsContent(state, listOf(e), vm.profile.name, tag) } },
            confirmButton = { TextButton(onClick = { detailsFor = null }) { Text(stringResource(Res.string.action_close)) } },
        )
    }
    sheetFor?.let { e ->
        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
        ModalBottomSheet(onDismissRequest = { sheetFor = null }) {
          Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            val cat = app.nextdata.state.FileKinds.categoryOf(e)
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(categoryIcon(cat), null, Modifier.size(36.dp), tint = categoryTint(cat))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(e.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(
                        e.size?.takeIf { e.kind == EntryKind.FILE }?.let { app.nextdata.state.Formatters.size(it, tag) },
                        e.mtimeMs?.let { app.nextdata.state.Formatters.dateTime(it, tag) },
                    ).joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            menuFor(e).forEach { a ->
                ListItem(
                    headlineContent = { Text(a.label, color = if (a.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
                    leadingContent = { Icon(a.icon, null, tint = if (a.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) },
                    modifier = Modifier.clickable(role = Role.Button) {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        sheetFor = null; a.onClick()
                    },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                )
            }
            Spacer(Modifier.height(24.dp))
          }
        }
    }
    if (fabSheet) {
        ModalBottomSheet(onDismissRequest = { fabSheet = false }) {
          Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            val items = buildList<Triple<androidx.compose.ui.graphics.vector.ImageVector, String, () -> Unit>> {
                add(Triple(Icons.Outlined.CreateNewFolder, newFolderLabel) { newFolder = true })
                add(Triple(Icons.Outlined.UploadFile, uploadLabel) { vm.upload() })
                if (!vm.mixedUpload) add(Triple(Icons.Outlined.UploadFile, uploadFolderLabel) { vm.uploadFolder() })
            }
            items.forEach { (icon, label, act) ->
                ListItem(
                    headlineContent = { Text(label) },
                    leadingContent = { Icon(icon, null) },
                    modifier = Modifier.clickable(role = Role.Button) { fabSheet = false; act() },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                )
            }
            Spacer(Modifier.height(24.dp))
          }
        }
    }
}

@Composable
private fun messageText(m: UiMessage): String = when (m) {
    is UiMessage.Error -> errorText(m.error)
    is UiMessage.FolderCreated -> stringResource(Res.string.msg_folder_created, m.name)
    is UiMessage.Renamed -> stringResource(Res.string.msg_renamed, m.name)
    is UiMessage.Moved -> pluralStringResource(Res.plurals.msg_moved, m.count, m.count, m.to)
    is UiMessage.Deleted -> pluralStringResource(Res.plurals.msg_deleted, m.count, m.count)
    is UiMessage.DownloadQueued -> pluralStringResource(Res.plurals.msg_download_queued, m.count, m.count)
    is UiMessage.UploadQueued -> pluralStringResource(Res.plurals.msg_upload_queued, m.count, m.count)
    is UiMessage.FavoriteChanged -> stringResource(if (m.added) Res.string.msg_favorite_added else Res.string.msg_favorite_removed)
    is UiMessage.DownloadDone -> if (m.label != null) pluralStringResource(Res.plurals.msg_download_done_to, m.count, m.count, m.label) else pluralStringResource(Res.plurals.msg_download_done, m.count, m.count)
    is UiMessage.UploadDone -> pluralStringResource(Res.plurals.msg_upload_done, m.count, m.count)
    UiMessage.PathCopied -> stringResource(Res.string.msg_path_copied)
    UiMessage.RestoreFailed -> stringResource(Res.string.msg_restore_failed)
}
