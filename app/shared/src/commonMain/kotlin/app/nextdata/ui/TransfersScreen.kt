package app.nextdata.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nextdata.i18n.LocalLanguageTag
import app.nextdata.i18n.errorText
import app.nextdata.shared.resources.*
import app.nextdata.state.AppController
import app.nextdata.state.Formatters
import app.nextdata.state.TransferDirection
import app.nextdata.state.TransferItem
import app.nextdata.state.TransferStatus
import app.nextdata.state.TransfersSummary
import app.nextdata.state.Screen
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import app.nextdata.state.TransfersViewModel
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * One compact line + bar for ALL running transfers, shown wherever the user is (browser, profiles): "Uploading · 3/12 ·
 * 45% · 2.1 MB/s". Tapping opens the transfers page. Nothing is shown when nothing is running.
 */
@Composable
fun TransfersStrip(controller: AppController, modifier: Modifier = Modifier) {
    val items by controller.transfers.items.collectAsState()
    val sum = remember(items) { TransfersSummary.of(items) } ?: return
    val tag = LocalLanguageTag.current
    val label = stringResource(
        when {
            sum.uploads > 0 && sum.downloads == 0 -> Res.string.transfer_strip_upload
            sum.downloads > 0 && sum.uploads == 0 -> Res.string.transfer_strip_download
            else -> Res.string.transfer_strip_both
        },
    )
    val text = buildString {
        append(label)
        if (sum.itemsTotal > 1) append(" · ").append(sum.itemsDone).append("/").append(sum.itemsTotal)
        sum.fraction?.let { append(" · ").append((it * 100).toInt()).append("%") }
        sum.speedBps?.let { append(" · ").append(Formatters.speed(it, tag)) }
        if (sum.active > 1) append(" · ").append(pluralStringResource(Res.plurals.transfer_strip_count, sum.active, sum.active))
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = modifier.fillMaxWidth().clickable(role = Role.Button) { controller.navigate(Screen.Transfers) }) {
        Column {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (sum.downloads > 0 && sum.uploads == 0) Icons.Outlined.Download else Icons.Outlined.Upload, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.size(10.dp))
                Text(
                    text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            val desc = stringResource(Res.string.transfer_strip_desc)
            val f = sum.fraction
            if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = desc })
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().semantics { contentDescription = desc })
        }
    }
}

/** Full-screen transfers page (phone, or the content area on desktop when opened from the side panel). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen(controller: AppController, onBack: () -> Unit) {
    val items by controller.transfers.items.collectAsState()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            val paused by controller.transfers.paused.collectAsState()
            NdTopBar(stringResource(Res.string.nav_transfers), onBack = onBack) {
                if (items.any { it.status == TransferStatus.Queued } || paused) {
                    NdIconButton(
                        if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                        stringResource(if (paused) Res.string.transfers_resume_queue else Res.string.transfers_pause_queue),
                        { controller.transfers.setPaused(!paused) },
                    )
                }
                if (items.any { !it.status.isActive }) {
                    NdIconButton(Icons.Outlined.DeleteSweep, stringResource(Res.string.transfers_clear_finished), controller.transfers::clearFinished)
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) { TransferList(controller.transfers, items, Modifier.weight(1f)) }
    }
}

/** Desktop collapsible panel shown below the browser. */
@Composable
fun TransfersPanel(controller: AppController, modifier: Modifier = Modifier) {
    val items by controller.transfers.items.collectAsState()
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            val paused by controller.transfers.paused.collectAsState()
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.nav_transfers), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (items.any { it.status == TransferStatus.Queued } || paused) {
                    TextButton(onClick = { controller.transfers.setPaused(!paused) }) {
                        Icon(if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(if (paused) Res.string.transfers_resume_queue else Res.string.transfers_pause_queue))
                    }
                }
                if (items.any { !it.status.isActive }) {
                    TextButton(onClick = controller.transfers::clearFinished) { Text(stringResource(Res.string.transfers_clear_finished)) }
                }
            }
            TransferList(controller.transfers, items, Modifier.weight(1f, fill = false))
        }
    }
}

@Composable
private fun TransferList(vm: TransfersViewModel, items: List<TransferItem>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) {
        EmptyState(
            icon = Icons.AutoMirrored.Outlined.CompareArrows,
            title = stringResource(Res.string.transfers_empty_title),
            body = stringResource(Res.string.transfers_empty_body),
            modifier = modifier,
        )
        return
    }
    val active = items.filter { it.status.isActive }
    val finished = items.filterNot { it.status.isActive }
    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (active.isNotEmpty()) {
            item(key = "h-active") { SectionLabel(stringResource(Res.string.transfers_active)) }
            items(active.asReversed(), key = { it.id }) { TransferRow(it, vm) }
        }
        if (finished.isNotEmpty()) {
            item(key = "h-done") { SectionLabel(stringResource(Res.string.transfers_finished)) }
            items(finished.asReversed(), key = { it.id }) { TransferRow(it, vm) }
        }
    }
}

@Composable
private fun TransferRow(item: TransferItem, vm: TransfersViewModel) {
    val tag = LocalLanguageTag.current
    val paused by vm.paused.collectAsState()
    val title = remember(item.names) { summarize(item.names) }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth().animateContentSize(tween(160))) {
        Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val (icon, tint) = when (item.status) {
                TransferStatus.Done -> Icons.Outlined.CheckCircle to MaterialTheme.nd.success
                is TransferStatus.Failed -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
                TransferStatus.Cancelled -> Icons.Outlined.Cancel to MaterialTheme.colorScheme.onSurfaceVariant
                else -> (if (item.direction == TransferDirection.Download) Icons.Outlined.Download else Icons.Outlined.Upload) to MaterialTheme.colorScheme.primary
            }
            Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = when (val s = item.status) {
                    TransferStatus.Queued -> stringResource(if (paused) Res.string.transfer_paused else Res.string.transfer_queued)
                    TransferStatus.Running -> {
                        val p = item.progress
                        if (p == null) stringResource(Res.string.transfer_starting)
                        else buildString {
                            append(Formatters.size(p.bytes, tag))
                            p.total?.let { append(" / ").append(Formatters.size(it, tag)) }
                            if (p.itemsTotal > 1) append(" · ").append(p.itemsDone.coerceAtMost(p.itemsTotal)).append("/").append(p.itemsTotal)
                            item.speedBps?.let { append(" · ").append(Formatters.speed(it, tag)) }
                            item.etaSec?.let { append(" · ").append(stringResource(Res.string.transfer_eta, Formatters.duration(it))) }
                        }
                    }
                    TransferStatus.Done -> stringResource(if (item.direction == TransferDirection.Download) Res.string.transfer_done_download else Res.string.transfer_done_upload)
                    TransferStatus.Cancelled -> stringResource(Res.string.transfer_cancelled)
                    is TransferStatus.Failed -> if (s.storage == app.nextdata.platform.StorageProblem.PermissionLost) stringResource(Res.string.transfer_storage_permission) else errorText(s.error)
                }
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.status is TransferStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail = (item.status as? TransferStatus.Failed)?.error?.param("detail")
                if (detail != null) {
                    // raw engine diagnostics (not localized): selectable so it can be copied into a support request
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(stringResource(Res.string.transfer_detail, detail), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(
                    item.profileName + (item.destinationLabel?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.status.isActive) {
                    Spacer(Modifier.size(6.dp))
                    val fraction = item.progress?.fraction
                    val desc = stringResource(Res.string.transfer_progress_desc, title)
                    if (item.status == TransferStatus.Running && fraction != null) {
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = desc })
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().semantics { contentDescription = desc })
                    }
                }
            }
            when (item.status) {
                TransferStatus.Queued, TransferStatus.Running ->
                    NdIconButton(Icons.Outlined.Close, stringResource(Res.string.transfer_cancel), { vm.cancel(item.id) })
                else -> Row {
                    if (item.status !is TransferStatus.Done) {
                        val storageProblem = (item.status as? TransferStatus.Failed)?.storage == app.nextdata.platform.StorageProblem.PermissionLost
                        NdIconButton(
                            if (storageProblem) Icons.Outlined.FolderOpen else Icons.Outlined.Replay,
                            stringResource(if (storageProblem) Res.string.transfer_choose_folder_again else Res.string.action_retry),
                            { vm.retry(item.id) },
                        )
                    }
                    NdIconButton(Icons.Outlined.Close, stringResource(Res.string.transfer_remove), { vm.remove(item.id) })
                }
            }
        }
    }
}

private fun summarize(names: List<String>): String = when {
    names.isEmpty() -> ""
    names.size == 1 -> names[0]
    names.size == 2 -> names.joinToString(", ")
    else -> names.take(2).joinToString(", ") + " +" + (names.size - 2)
}
