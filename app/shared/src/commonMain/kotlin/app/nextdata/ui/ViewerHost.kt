package app.nextdata.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nextdata.i18n.errorText
import app.nextdata.protocol.DocState
import app.nextdata.protocol.SaveResolution
import app.nextdata.shared.resources.*
import app.nextdata.state.AppController
import app.nextdata.state.OpenManager
import app.nextdata.state.OpenMessage
import app.nextdata.state.TextCodec
import app.nextdata.state.ViewerKind
import app.nextdata.state.ViewerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Full-window viewer for files opened in place, plus the opening bar, the conflict dialog and result messages. */
@Composable
fun ViewerHost(controller: AppController) {
    val opener = controller.opener
    val viewer by opener.viewer.collectAsState()
    val progress by opener.progress.collectAsState()
    val message by opener.message.collectAsState()
    val conflict by opener.conflict.collectAsState()

    viewer?.let { v -> ViewerScreen(controller, v) }
    val media by opener.media.collectAsState()
    media?.let { m -> MediaScreen(controller, m) }
    val large by opener.large.collectAsState()
    large?.let { l ->
        ConfirmDialog(
            title = stringResource(Res.string.viewer_large_title),
            text = stringResource(Res.string.viewer_large_text, app.nextdata.state.Formatters.size(l.entry.size ?: 0L, app.nextdata.i18n.LocalLanguageTag.current)),
            confirmLabel = stringResource(Res.string.viewer_large_confirm),
            onConfirm = opener::confirmLarge,
            onDismiss = opener::dismissLarge,
        )
    }

    progress?.let { p ->
        Box(Modifier.fillMaxSize().navigationBarsPadding(), contentAlignment = Alignment.BottomCenter) {
            Surface(Modifier.fillMaxWidth().padding(12.dp), shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp, shadowElevation = 6.dp) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(Res.string.opening_file, p.name), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = opener::cancelOpening) { Text(stringResource(Res.string.action_cancel)) }
                    }
                    val f = p.fraction
                    if (f != null) LinearProgressIndicator(progress = { f }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }

    message?.let { m -> OpenMessageBar(m, opener) }
    val pendingOpen by opener.pendingOpen.collectAsState()
    if (pendingOpen) PendingDialog(controller)

    conflict?.let { _ -> ConflictDialog(opener) }
}

@Composable
private fun OpenMessageBar(m: OpenMessage, opener: OpenManager) {
    val hasAction = m is OpenMessage.ExternalChanged || m is OpenMessage.CopyDetected || m is OpenMessage.NoChangeDetected || m is OpenMessage.PendingFound
    LaunchedEffect(m) {
        delay(if (m is OpenMessage.Error) 8000 else if (hasAction) 20000 else 3500)
        opener.consumeMessage()
    }
    val text = when (m) {
        is OpenMessage.Error -> errorText(m.error)
        is OpenMessage.Saved -> stringResource(if (m.content) Res.string.viewer_saved_verified else Res.string.viewer_saved_size)
        OpenMessage.SavedAsCopy -> stringResource(Res.string.viewer_saved_copy)
        OpenMessage.RemoteChangedKept -> stringResource(Res.string.viewer_remote_changed_kept)
        OpenMessage.NothingToSave -> stringResource(Res.string.viewer_nothing_to_save)
        OpenMessage.LocalNotReady -> stringResource(Res.string.viewer_local_not_ready)
        OpenMessage.NoExternalApp -> stringResource(Res.string.viewer_no_external_app)
        is OpenMessage.ExternalSaved -> stringResource(Res.string.msg_external_saved, m.name)
        is OpenMessage.ExternalChanged -> stringResource(Res.string.msg_external_changed, m.name)
        is OpenMessage.CopyDetected -> stringResource(Res.string.msg_copy_detected, m.names.first())
        is OpenMessage.NoChangeDetected -> stringResource(Res.string.msg_no_change, m.name)
        is OpenMessage.PendingFound -> stringResource(Res.string.msg_pending_found, m.count)
    }
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 64.dp), contentAlignment = Alignment.BottomCenter) {
        Surface(
            Modifier.padding(12.dp).testTag("open_message"),
            shape = MaterialTheme.shapes.medium,
            color = if (m is OpenMessage.Error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.inverseSurface,
            contentColor = if (m is OpenMessage.Error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 6.dp,
        ) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text, Modifier.weight(1f, fill = false).padding(vertical = 8.dp))
                when (m) {
                    is OpenMessage.ExternalChanged -> TextButton(onClick = { opener.consumeMessage(); opener.saveAsked(m.sessionId, m.docId) }) { Text(stringResource(Res.string.viewer_save_now)) }
                    is OpenMessage.CopyDetected -> TextButton(onClick = { opener.consumeMessage(); opener.useCopy(m.sessionId, m.docId, m.names.first()) }) { Text(stringResource(Res.string.action_use_copy)) }
                    is OpenMessage.PendingFound -> TextButton(onClick = { opener.consumeMessage(); opener.showPending(true) }) { Text(stringResource(Res.string.action_review)) }
                    is OpenMessage.NoChangeDetected -> TextButton(onClick = { opener.consumeMessage(); opener.pickAndImportFor(m.docId) }) { Text(stringResource(Res.string.action_import_version)) }
                    else -> Unit
                }
            }
        }
    }
}

/** The three ways out of a conflict. Overwriting needs a second, explicit confirmation and is never the default. */
@Composable
private fun ConflictDialog(opener: OpenManager) {
    var confirmOverwrite by remember { mutableStateOf(false) }
    if (confirmOverwrite) {
        ConfirmDialog(
            title = stringResource(Res.string.conflict_overwrite_title),
            text = stringResource(Res.string.conflict_overwrite_body),
            confirmLabel = stringResource(Res.string.conflict_overwrite),
            destructive = true,
            onConfirm = { confirmOverwrite = false; opener.resolveConflict(SaveResolution.OVERWRITE) },
            onDismiss = { confirmOverwrite = false },
        )
        return
    }
    AlertDialog(
        onDismissRequest = opener::dismissConflict,
        title = { Text(stringResource(Res.string.conflict_title)) },
        text = { Text(stringResource(Res.string.conflict_body)) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { opener.resolveConflict(SaveResolution.COPY) }) { Text(stringResource(Res.string.conflict_copy)) }
                TextButton(onClick = { confirmOverwrite = true }) { Text(stringResource(Res.string.conflict_overwrite), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = opener::dismissConflict) { Text(stringResource(Res.string.conflict_keep)) }
            }
        },
    )
}

@Composable
private fun docStateLabel(state: String): String = when (state) {
    DocState.CLEAN -> stringResource(Res.string.doc_state_clean)
    DocState.MODIFIED -> stringResource(Res.string.doc_state_modified)
    DocState.SAVING -> stringResource(Res.string.doc_state_saving)
    DocState.SAVE_FAILED -> stringResource(Res.string.doc_state_failed)
    DocState.UNCERTAIN -> stringResource(Res.string.doc_state_uncertain)
    DocState.CONFLICT -> stringResource(Res.string.doc_state_conflict)
    DocState.IMPORTED -> stringResource(Res.string.doc_state_imported)
    DocState.ORPHANED -> stringResource(Res.string.doc_state_orphaned)
    else -> ""
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun ViewerScreen(controller: AppController, v: ViewerState) {
    val opener = controller.opener
    val saving by opener.saving.collectAsState()
    val doc by opener.doc.collectAsState()
    val scope = rememberCoroutineScope()
    val files = controller.env.files
    // text editor state is owned here so the back handler and the bar can see it
    var dirty by remember(v.doc.id) { mutableStateOf(false) }
    var askLeave by remember { mutableStateOf(false) }
    var saveText by remember(v.doc.id) { mutableStateOf<(suspend () -> Unit)?>(null) }

    fun leave() {
        opener.close()
    }
    BackHandler(enabled = true) { if (dirty) askLeave = true else leave() }

    Surface(Modifier.fillMaxSize().testTag("viewer"), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (v.kind) {
                    ViewerKind.Image -> ImageViewer(controller, v)
                    ViewerKind.Pdf -> PdfViewer(controller, v)
                    ViewerKind.Text -> TextViewer(controller, v, onDirty = { dirty = it }, onSaveHandle = { saveText = it })
                    else -> UnsupportedViewer(controller, v)
                }
            }
            HorizontalDivider()
            // controls at the bottom (thumb reach), sober
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                NdIconButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(Res.string.action_back), { if (dirty) askLeave = true else leave() })
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(v.entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    val st = (doc ?: v.doc).state
                    val label = if (saving) stringResource(Res.string.doc_state_saving) else if (dirty) stringResource(Res.string.doc_state_unsaved) else docStateLabel(st)
                    if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                if (v.kind == ViewerKind.Image && v.images.size > 1) {
                    NdIconButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(Res.string.viewer_prev), { opener.showImage(v.index - 1) }, enabled = v.index > 0)
                    NdIconButton(Icons.AutoMirrored.Outlined.ArrowForward, stringResource(Res.string.viewer_next), { opener.showImage(v.index + 1) }, enabled = v.index < v.images.lastIndex)
                }
                if (v.kind == ViewerKind.Text && saveText != null) {
                    if (saving) CircularProgressIndicator(Modifier.padding(12.dp).width(20.dp))
                    else NdIconButton(Icons.Outlined.Check, stringResource(Res.string.action_save), { scope.launch { saveText?.invoke() } }, enabled = dirty || (doc ?: v.doc).state != DocState.CLEAN)
                }
                if (v.kind != ViewerKind.Unsupported) {
                    NdIconButton(Icons.Outlined.OpenInNew, stringResource(Res.string.viewer_open_external), { scope.launch { opener.openExternally(v.sessionId, v.entry, v.doc, edit = true) } })
                }
                NdIconButton(Icons.Outlined.Download, stringResource(Res.string.action_download), { controller.browser.value?.download(listOf(v.entry)) })
            }
        }
    }
    if (askLeave) {
        AlertDialog(
            onDismissRequest = { askLeave = false },
            title = { Text(stringResource(Res.string.viewer_discard_title)) },
            text = { Text(stringResource(Res.string.viewer_discard_text)) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { askLeave = false; scope.launch { saveText?.invoke(); leave() } }) { Text(stringResource(Res.string.viewer_save_now)) }
                    TextButton(onClick = { askLeave = false; leave() }) { Text(stringResource(Res.string.viewer_keep_draft)) }
                    TextButton(onClick = { askLeave = false }) { Text(stringResource(Res.string.action_cancel)) }
                }
            },
        )
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
/** Everything whose local work is not (verifiably) on the server, with the ways to save, rescue or drop it. */
@Composable
private fun PendingDialog(controller: AppController) {
    val opener = controller.opener
    val docs by opener.pending.collectAsState()
    val browser by controller.browser.collectAsState()
    val profiles by controller.profiles.state.collectAsState()
    var confirmDiscard by remember { mutableStateOf<String?>(null) }
    confirmDiscard?.let { id ->
        ConfirmDialog(
            title = stringResource(Res.string.pending_discard_title),
            text = stringResource(Res.string.pending_discard_text),
            confirmLabel = stringResource(Res.string.pending_discard),
            destructive = true,
            onConfirm = { confirmDiscard = null; opener.discardPending(id) },
            onDismiss = { confirmDiscard = null },
        )
    }
    AlertDialog(
        onDismissRequest = { opener.showPending(false) },
        title = { Text(stringResource(Res.string.pending_title)) },
        text = {
            if (docs.isEmpty()) {
                Text(stringResource(Res.string.pending_none))
            } else {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    docs.forEach { d ->
                        val profileName = profiles.profiles.firstOrNull { it.id == d.profileId }?.name
                        val session = browser?.takeIf { it.profile.id == d.profileId }?.sessionId
                        Column(Modifier.fillMaxWidth().testTag("pending_${d.id}")) {
                            Text(d.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(profileName, docStateLabel(d.state).ifEmpty { null }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                val canSave = session != null && d.state != DocState.ORPHANED && d.state != DocState.UNCERTAIN
                                TextButton(onClick = { session?.let { opener.savePending(it, d.id) } }, enabled = canSave) { Text(stringResource(Res.string.viewer_save_now)) }
                                TextButton(onClick = { opener.exportPending(d.id) }) { Text(stringResource(Res.string.pending_export)) }
                                TextButton(onClick = { confirmDiscard = d.id }) { Text(stringResource(Res.string.pending_discard), color = MaterialTheme.colorScheme.error) }
                            }
                            if (session == null && d.state != DocState.ORPHANED) {
                                Text(stringResource(Res.string.pending_connect_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { opener.showPending(false) }) { Text(stringResource(Res.string.action_close)) } },
    )
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun MediaScreen(controller: AppController, m: app.nextdata.state.MediaState) {
    val opener = controller.opener
    val source = remember(m) { opener.mediaSource(m) }
    val scope = rememberCoroutineScope()
    androidx.compose.runtime.DisposableEffect(m) { onDispose { scope.launch { source.close() } } }
    BackHandler(enabled = true) { opener.closeMedia() }
    Surface(Modifier.fillMaxSize().testTag("media"), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val isVideo = app.nextdata.state.FileKinds.categoryOf(m.entry) == app.nextdata.state.FileCategory.Video
                controller.env.mediaPlayer?.invoke(app.nextdata.platform.MediaRequest(m.entry.name, m.entry.size, source, isVideo), Modifier.fillMaxSize())
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                NdIconButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(Res.string.action_back), opener::closeMedia)
                Text(m.entry.name, Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                NdIconButton(Icons.Outlined.OpenInNew, stringResource(Res.string.viewer_open_external), opener::mediaToExternal)
                NdIconButton(Icons.Outlined.Download, stringResource(Res.string.action_download), { controller.browser.value?.download(listOf(m.entry)) })
            }
        }
    }
}

@Composable
private fun PdfViewer(controller: AppController, v: ViewerState) {
    val files = controller.env.files
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
        val widthPx = with(density) { maxWidth.roundToPx() }.coerceIn(200, 2400)
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().testTag("pdf_pages"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(v.pages) { index ->
                // pages are rendered one at a time when they scroll into view
                val page by produceState<ImageBitmap?>(null, v.doc.localPath, index, widthPx) { value = files.renderPdfPage(v.doc.localPath, index, widthPx) }
                val b = page
                if (b != null) {
                    Image(bitmap = b, contentDescription = stringResource(Res.string.viewer_pdf_page, index + 1, v.pages), contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                } else {
                    Box(Modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
        }
    }
}

@Composable
private fun UnsupportedViewer(controller: AppController, v: ViewerState) {
    val scope = rememberCoroutineScope()
    EmptyState(
        icon = Icons.Outlined.OpenInNew,
        title = v.entry.name,
        body = stringResource(Res.string.viewer_unsupported_body),
        action = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.OutlinedButton(onClick = { scope.launch { controller.opener.openExternally(v.sessionId, v.entry, v.doc) } }) { Text(stringResource(Res.string.viewer_open_external)) }
                androidx.compose.material3.Button(onClick = { controller.browser.value?.download(listOf(v.entry)) }) { Text(stringResource(Res.string.action_download)) }
            }
        },
    )
}

@Composable
private fun ImageViewer(controller: AppController, v: ViewerState) {
    var bmp by remember(v.doc.localPath) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(v.doc.localPath) { mutableStateOf(false) }
    LaunchedEffect(v.doc.localPath) {
        val b = controller.env.files.decodeImage(v.doc.localPath)
        bmp = b
        failed = b == null
    }
    var scale by remember(v.doc.localPath) { mutableStateOf(1f) }
    var ox by remember(v.doc.localPath) { mutableStateOf(0f) }
    var oy by remember(v.doc.localPath) { mutableStateOf(0f) }
    var drag by remember(v.doc.localPath) { mutableStateOf(0f) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).clipToBounds(), contentAlignment = Alignment.Center) {
        val b = bmp
        when {
            b != null -> Image(
                bitmap = b,
                contentDescription = v.entry.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    .pointerInput(v.doc.localPath) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            if (scale > 1f) { ox += pan.x; oy += pan.y } else { ox = 0f; oy = 0f; drag += pan.x }
                            if (scale == 1f && kotlin.math.abs(drag) > 160f) {
                                val next = if (drag < 0) v.index + 1 else v.index - 1
                                drag = 0f
                                controller.opener.showImage(next)
                            }
                        }
                    }
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = ox, translationY = oy),
            )
            failed -> Text(stringResource(Res.string.viewer_image_failed), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> CircularProgressIndicator()
        }
    }
}

@Composable
private fun TextViewer(controller: AppController, v: ViewerState, onDirty: (Boolean) -> Unit, onSaveHandle: ((suspend () -> Unit)?) -> Unit) {
    val files = controller.env.files
    val opener = controller.opener
    var loaded by remember(v.doc.id) { mutableStateOf<TextCodec.Result?>(null) }
    var value by remember(v.doc.id) { mutableStateOf(TextFieldValue("")) }
    var original by remember(v.doc.id) { mutableStateOf("") }
    var failed by remember(v.doc.id) { mutableStateOf(false) }
    LaunchedEffect(v.doc.id) {
        val bytes = files.readFile(v.doc.localPath, OpenManager.TEXT_EDIT_MAX)
        if (bytes == null) { failed = true; return@LaunchedEffect }
        val r = TextCodec.decode(bytes)
        loaded = r
        if (r is TextCodec.Text) { value = TextFieldValue(r.text); original = r.text }
        else if (r is TextCodec.NotEditable) { value = TextFieldValue(r.preview.orEmpty()) }
    }
    val doc = loaded as? TextCodec.Text
    val dirty = doc != null && value.text != original
    LaunchedEffect(dirty) { onDirty(dirty) }
    // save handle for the bar / back dialog: write the bytes, then let the engine verify and upload
    LaunchedEffect(doc, value.text) {
        if (doc == null) { onSaveHandle(null); return@LaunchedEffect }
        onSaveHandle {
            val bytes = TextCodec.encode(value.text, doc.encoding, doc.crlf)
            if (opener.writeLocal(bytes)) { original = value.text; opener.save() }
        }
    }
    // crash-safe draft in the workspace copy (never uploaded by itself)
    LaunchedEffect(value.text) {
        if (doc == null || value.text == original) return@LaunchedEffect
        delay(3000)
        opener.writeLocal(TextCodec.encode(value.text, doc.encoding, doc.crlf))
    }
    Column(Modifier.fillMaxSize()) {
        val l = loaded
        if (l is TextCodec.NotEditable) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(
                        when (l.why) {
                            TextCodec.Why.Binary -> Res.string.viewer_not_text_binary
                            TextCodec.Why.UnknownEncoding -> Res.string.viewer_not_text_encoding
                            TextCodec.Why.MixedLineEndings -> Res.string.viewer_not_text_eol
                            TextCodec.Why.Odd16 -> Res.string.viewer_not_text_encoding
                        },
                    ),
                    Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        when {
            failed -> Text(stringResource(Res.string.viewer_image_failed), Modifier.padding(24.dp))
            loaded == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            l is TextCodec.NotEditable && l.why == TextCodec.Why.Binary -> Unit
            else -> {
                val vs = rememberScrollState()
                val save = remember { mutableStateOf<(() -> Unit)?>(null) }
                // the field spans the whole visible area (a tap anywhere below the text puts the cursor there)
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                    val minHeight = maxHeight
                    Box(Modifier.fillMaxSize().verticalScroll(vs)) {
                        BasicTextField(
                            value = value,
                            onValueChange = { if (doc != null) value = it },
                            readOnly = doc == null,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions.Default,
                            modifier = Modifier.fillMaxWidth().heightIn(min = minHeight).padding(12.dp).testTag("text_editor")
                                .onPreviewKeyEvent { e ->
                                    if (e.type == KeyEventType.KeyDown && e.isCtrlPressed && e.key == Key.S && doc != null) {
                                        save.value?.invoke()
                                        true
                                    } else false
                                },
                        )
                    }
                }
                LaunchedEffect(doc, value.text) {
                    save.value = if (doc == null) null else ({
                        val bytes = TextCodec.encode(value.text, doc.encoding, doc.crlf)
                        controller.scope.launch { if (opener.writeLocal(bytes)) { original = value.text; opener.save() } }
                        Unit
                    })
                }
            }
        }
    }
}
