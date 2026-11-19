package app.nextdata.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.nextdata.i18n.errorText
import app.nextdata.protocol.Entry
import app.nextdata.protocol.EntryKind
import app.nextdata.shared.resources.*
import app.nextdata.state.BrowserViewModel
import app.nextdata.state.FolderListing
import app.nextdata.state.Outcome
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Picks the destination folder for Move and Copy (one picker, the caller decides what happens): breadcrumb and up button to
 * move around, a row to create a folder, and one confirm button named after the action. The folders that are themselves being
 * moved or copied are not offered (a folder cannot go inside itself). Full screen on a phone, a window on the desktop.
 */
@Composable
fun FolderPickerDialog(
    vm: BrowserViewModel,
    copy: Boolean,
    entries: List<Entry>,
    startRef: String?,
    compact: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (destRef: String, destName: String) -> Unit,
) {
    var ref by remember { mutableStateOf(startRef ?: vm.homeRef) }
    var reload by remember { mutableIntStateOf(0) }
    var listing by remember { mutableStateOf<Outcome<FolderListing>?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    LaunchedEffect(ref, reload) {
        listing = null
        listing = vm.listFolders(ref)
    }
    val here = (listing as? Outcome.Success)?.value
    val skip = remember(entries) { entries.filter { it.kind == EntryKind.DIR }.map { it.ref }.toSet() }
    val hereName = here?.let { if (it.parentRef == null) vm.profile.name else it.breadcrumbs.lastOrNull()?.name ?: vm.profile.name }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(
            shape = if (compact) MaterialTheme.shapes.extraSmall else MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = if (compact) Modifier.fillMaxSize() else Modifier.width(440.dp).heightIn(max = 600.dp),
        ) {
            Column(Modifier.then(if (compact) Modifier.statusBarsPadding().navigationBarsPadding() else Modifier)) {
                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp)) {
                    Text(
                        pluralStringResource(if (copy) Res.plurals.copy_title else Res.plurals.move_title, entries.size, entries.size),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(stringResource(Res.string.picker_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // where we are: up button + clickable breadcrumb
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    NdIconButton(Icons.Outlined.ArrowUpward, stringResource(Res.string.action_up), { here?.parentRef?.let { ref = it } }, enabled = here?.parentRef != null)
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        val crumbs = here?.breadcrumbs ?: emptyList()
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
                                    .then(if (last) Modifier else Modifier.clickable(role = Role.Button) { ref = c.ref })
                                    .heightIn(min = 32.dp)
                                    .padding(horizontal = 6.dp, vertical = 6.dp),
                            )
                            if (!last) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f, fill = !compact).fillMaxWidth().heightIn(min = 160.dp)) {
                    when (val l = listing) {
                        null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        is Outcome.Failure -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(errorText(l.error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = { reload++ }) { Text(stringResource(Res.string.action_retry)) }
                        }
                        is Outcome.Success -> {
                            val folders = l.value.folders.filter { it.ref !in skip }
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(folders, key = { it.ref }) { f ->
                                    Row(
                                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { ref = f.ref }.padding(horizontal = 24.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(16.dp))
                                        Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (folders.isEmpty()) item {
                                    Text(
                                        stringResource(Res.string.picker_no_folders), style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                                    )
                                }
                                item {
                                    Row(
                                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { newFolder = true }.padding(horizontal = 24.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Outlined.CreateNewFolder, null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(16.dp))
                                        Text(stringResource(Res.string.folder_new_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
                    Button(
                        onClick = { if (here != null && hereName != null) onConfirm(here.ref, hereName) },
                        enabled = here != null,
                    ) { Text(stringResource(if (copy) Res.string.picker_copy_here else Res.string.picker_move_here)) }
                }
            }
        }
    }

    if (newFolder && here != null) {
        NameDialog(
            title = stringResource(Res.string.folder_new_title),
            label = stringResource(Res.string.field_folder_name),
            initial = "",
            confirmLabel = stringResource(Res.string.action_create),
            onSubmit = { name -> vm.createFolderIn(here.ref, name).also { if (it == null) reload++ } },
            onDismiss = { newFolder = false },
        )
    }
}
