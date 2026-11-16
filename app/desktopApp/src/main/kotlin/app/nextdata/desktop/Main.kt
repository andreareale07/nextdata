package app.nextdata.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import androidx.compose.ui.window.rememberWindowState
import app.nextdata.bridge.JniEngine
import app.nextdata.bridge.NativeLoader
import app.nextdata.engine.Engine
import app.nextdata.engine.FakeEngine
import app.nextdata.engine.UnavailableEngine
import app.nextdata.platform.AppEnvironment
import app.nextdata.platform.PlatformKind
import app.nextdata.platform.SecretProtection
import app.nextdata.protocol.ErrInfo
import app.nextdata.protocol.ErrorCodes
import app.nextdata.shared.resources.*
import app.nextdata.state.AppController
import app.nextdata.state.Screen
import app.nextdata.ui.NextDataApp
import app.nextdata.ui.NextDataTheme
import app.nextdata.ui.rememberAppLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import org.jetbrains.compose.resources.stringResource
import java.awt.Frame
import java.io.File
import java.net.URI
import javax.imageio.ImageIO
import javax.swing.UIManager

/** Per-user locations: everything lives under %LOCALAPPDATA%\NextData (not Roaming). `ND_APP_HOME` overrides it (tests). */
class DesktopPaths(val base: File) {
    val dataDir = File(base, "data")
    val nativeDir = File(base, "native")
    val pmkFile = File(base, "pmk.dpapi")

    companion object {
        fun resolve(): DesktopPaths {
            val override = System.getenv("ND_APP_HOME")
            val root = when {
                !override.isNullOrBlank() -> File(override)
                else -> File(System.getenv("LOCALAPPDATA") ?: (System.getProperty("user.home") + "\\AppData\\Local"), "NextData")
            }
            return DesktopPaths(root)
        }
    }
}

fun createEngine(fake: Boolean, paths: DesktopPaths): Engine {
    if (fake) return FakeEngine(latencyMs = 150, transferStepDelayMs = 400, seedProfiles = true)
    return try {
        NativeLoader.ensureLoaded(paths.nativeDir)
        JniEngine()
    } catch (e: Throwable) {
        System.err.println("NextData: cannot load the engine library: $e")
        UnavailableEngine(ErrInfo.of(ErrorCodes.NOT_INITIALIZED))
    }
}

/** Drops duplicates and anything already inside a dropped folder (it would be uploaded twice). */
fun dropRoots(paths: List<String>): List<String> {
    val files = paths.map { File(it).absoluteFile.normalize() }.distinct()
    return files.filter { f -> files.none { o -> o != f && o.isDirectory && f.toPath().startsWith(o.toPath()) } }.map { it.absolutePath }
}

fun fileUriToPath(uri: String): String? = try {
    File(URI(uri)).absolutePath
} catch (_: Exception) {
    null
}

@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    // Dragging entries out to the file manager copies them from the server at the moment of the drop. AWT gives the
    // data conversion only ~10 s by default, so a folder that takes longer was silently dropped: allow up to an hour.
    // Must be set before AWT starts.
    System.setProperty("sun.awt.datatransfer.timeout", "3600000")
    val fake = "--fake" in args || System.getenv("ND_FAKE_ENGINE") == "1"
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val paths = DesktopPaths.resolve()

    application {
        var frame: Frame? = null
        val controller = remember {
            val env = AppEnvironment(
                dataDir = paths.dataDir.absolutePath,
                secretStore = DpapiSecretStore(paths.pmkFile),
                files = DesktopFiles { frame },
                kind = PlatformKind.Desktop,
                secretProtection = SecretProtection.WindowsDpapi,
                appVersion = "1.0.0" + if (fake) " (fake engine)" else "",
            )
            AppController(env, createEngine(fake, paths), CoroutineScope(SupervisorJob() + Dispatchers.Swing))
        }
        val tag = rememberAppLanguage(controller)
        val settings by controller.settings.state.collectAsState()
        val browser by controller.browser.collectAsState()
        val icon = remember { BitmapPainter(ImageIO.read(DesktopPaths::class.java.getResource("/icons/nextdata.png")).toComposeImageBitmap()) }
        var dragging by remember { mutableStateOf(false) }
        val saved = remember { if (System.getenv("ND_WINDOW_SIZE") == null) WindowGeometry.load(paths.base) else null }
        val windowState = rememberWindowState(
            size = System.getenv("ND_WINDOW_SIZE")?.split("x")?.let { p -> p.getOrNull(0)?.toIntOrNull()?.let { w -> p.getOrNull(1)?.toIntOrNull()?.let { h -> DpSize(w.dp, h.dp) } } }
                ?: saved?.size ?: DpSize(1180.dp, 780.dp),
            position = saved?.position ?: WindowPosition.Aligned(Alignment.Center),
            placement = if (saved?.maximized == true) androidx.compose.ui.window.WindowPlacement.Maximized else androidx.compose.ui.window.WindowPlacement.Floating,
        )
        // remember size / position / maximised state (not when a preview size is forced)
        if (System.getenv("ND_WINDOW_SIZE") == null) {
            androidx.compose.runtime.LaunchedEffect(windowState) {
                androidx.compose.runtime.snapshotFlow { Triple(windowState.size, windowState.position, windowState.placement) }
                    .debounce(500)
                    .collect { (size, pos, placement) -> WindowGeometry.save(paths.base, size, pos, placement == androidx.compose.ui.window.WindowPlacement.Maximized) }
            }
        }

        // Quitting must never cut a transfer: with transfers running the user chooses to wait for them (or to quit anyway).
        var quitAsk by remember { mutableStateOf(false) }
        var quitWaiting by remember { mutableStateOf(false) }
        val quitNow = {
            controller.disconnect(navigateToProfiles = false)
            exitApplication()
        }
        val requestQuit = { if (controller.transfers.activeCount == 0) quitNow() else quitAsk = true }
        androidx.compose.runtime.LaunchedEffect(quitWaiting) {
            if (quitWaiting) {
                controller.transfers.items.first { list -> list.none { it.status.isActive } }
                quitNow()
            }
        }

        Window(
            onCloseRequest = requestQuit,
            title = "NextData" + (browser?.let { " — " + it.profile.name } ?: ""),
            icon = icon,
            state = windowState,
        ) {
            frame = window
            // Menus are re-created when the language changes (tag is part of the key).
            androidx.compose.runtime.key(tag) {
                DesktopMenuBar(controller, settings.showHidden, settings.viewMode == app.nextdata.state.ViewMode.Grid, settings.detailsOpen, requestQuit)
            }
            if (quitAsk) {
                val active = controller.transfers.items.collectAsState().value.count { it.status.isActive }
                NextDataTheme {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { quitAsk = false },
                        title = { Text(stringResource(Res.string.quit_busy_title)) },
                        text = { Text(if (quitWaiting) stringResource(Res.string.quit_waiting) else stringResource(Res.string.quit_busy_body, active)) },
                        confirmButton = {
                            androidx.compose.material3.TextButton(enabled = !quitWaiting, onClick = { quitWaiting = true }) { Text(stringResource(Res.string.quit_wait)) }
                        },
                        dismissButton = {
                            Row {
                                androidx.compose.material3.TextButton(onClick = { quitNow() }) { Text(stringResource(Res.string.quit_now)) }
                                androidx.compose.material3.TextButton(onClick = { quitWaiting = false; quitAsk = false }) { Text(stringResource(Res.string.action_cancel)) }
                            }
                        },
                    )
                }
            }
            val entryDrag = remember { DesktopEntryDrag() }
            val dropTarget = remember {
                object : DragAndDropTarget {
                    // a drag of the app's own entries (move / drag out) is not an upload: no upload overlay, no upload
                    override fun onStarted(event: DragAndDropEvent) { dragging = controller.browser.value?.dragging?.value == null }
                    override fun onEnded(event: DragAndDropEvent) { dragging = false }
                    override fun onDrop(event: DragAndDropEvent): Boolean {
                        dragging = false
                        if (controller.browser.value?.dragging?.value != null) return false
                        val data = event.dragData()
                        if (data is DragData.FilesList) {
                            val dropped = data.readFiles().mapNotNull(::fileUriToPath).let(::dropRoots)
                            // Big folders are measured off the UI thread: the window must not freeze after a drop.
                            CoroutineScope(Dispatchers.IO).launch {
                                val total = dropped.sumOf { p -> File(p).let { f -> if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() } } }
                                controller.uploadDropped(dropped, total)
                            }
                            return true
                        }
                        return false
                    }
                }
            }
            Box(Modifier.fillMaxSize().dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)) {
                androidx.compose.runtime.CompositionLocalProvider(app.nextdata.ui.LocalEntryDrag provides entryDrag) { NextDataApp(controller) }
                if (dragging && browser != null) DropOverlay()
            }
        }
    }
}

@Composable
private fun DropOverlay() {
    NextDataTheme {
        Box(
            Modifier.fillMaxSize().padding(12.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                .border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), MaterialTheme.shapes.large),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(Res.string.drop_overlay), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun androidx.compose.ui.window.FrameWindowScope.DesktopMenuBar(controller: AppController, showHidden: Boolean, grid: Boolean, details: Boolean, onQuit: () -> Unit) {
    MenuBar {
        Menu(stringResource(Res.string.menu_file)) {
            Item(stringResource(Res.string.menu_new_profile), shortcut = KeyShortcut(Key.N, ctrl = true, shift = true), onClick = controller::requestNewProfile)
            Item(stringResource(Res.string.action_disconnect), enabled = controller.browser.value != null, onClick = { controller.disconnect() })
            Separator()
            Item(stringResource(Res.string.menu_quit), shortcut = KeyShortcut(Key.Q, ctrl = true), onClick = onQuit)
        }
        Menu(stringResource(Res.string.menu_view)) {
            Item(stringResource(Res.string.action_refresh), shortcut = KeyShortcut(Key.F5), onClick = { controller.browser.value?.refresh() })
            CheckboxItem(stringResource(Res.string.menu_show_hidden), checked = showHidden, onCheckedChange = { v -> controller.settings.update { it.copy(showHidden = v) } })
            CheckboxItem(stringResource(Res.string.view_grid), checked = grid, onCheckedChange = { v -> controller.settings.update { it.copy(viewMode = if (v) app.nextdata.state.ViewMode.Grid else app.nextdata.state.ViewMode.List) } })
            CheckboxItem(stringResource(Res.string.details_show), checked = details, onCheckedChange = { v -> controller.settings.update { it.copy(detailsOpen = v) } })
            Menu(stringResource(Res.string.settings_density)) {
                Item(stringResource(Res.string.density_comfortable), onClick = { controller.settings.update { it.copy(density = app.nextdata.state.Density.Comfortable) } })
                Item(stringResource(Res.string.density_compact), onClick = { controller.settings.update { it.copy(density = app.nextdata.state.Density.Compact) } })
            }
            Separator()
            Item(stringResource(Res.string.nav_transfers), onClick = { controller.navigate(Screen.Transfers) })
            Item(stringResource(Res.string.nav_settings), shortcut = KeyShortcut(Key.Comma, ctrl = true), onClick = { controller.navigate(Screen.Settings) })
        }
        Menu(stringResource(Res.string.menu_help)) {
            Item(stringResource(Res.string.menu_about), onClick = { controller.showAbout(true) })
        }
    }
}

/** Window size/position/maximised state kept between runs in `window.properties` next to the app data. */
object WindowGeometry {
    class Saved(val size: DpSize, val position: WindowPosition, val maximized: Boolean)

    private fun file(base: File) = File(base, "window.properties")

    fun load(base: File): Saved? = runCatching {
        val p = java.util.Properties().apply { file(base).inputStream().use { load(it) } }
        val w = p.getProperty("w").toFloat(); val h = p.getProperty("h").toFloat()
        val x = p.getProperty("x").toFloat(); val y = p.getProperty("y").toFloat()
        if (w < 360 || h < 320) return null
        // ignore a position that is no longer on any screen (monitor unplugged)
        val screens = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }
        val onScreen = screens.any { b -> x + 80 > b.x && x < b.x + b.width - 80 && y > b.y - 1 && y < b.y + b.height - 80 }
        Saved(DpSize(w.dp, h.dp), if (onScreen) WindowPosition(x.dp, y.dp) else WindowPosition.Aligned(Alignment.Center), p.getProperty("max") == "true")
    }.getOrNull()

    fun save(base: File, size: DpSize, pos: WindowPosition, maximized: Boolean) {
        runCatching {
            if (!pos.isSpecified) return
            val p = java.util.Properties()
            // while maximised keep the previous floating geometry
            if (maximized) file(base).takeIf { it.exists() }?.inputStream()?.use { p.load(it) }
            else {
                p.setProperty("w", size.width.value.toString()); p.setProperty("h", size.height.value.toString())
                p.setProperty("x", pos.x.value.toString()); p.setProperty("y", pos.y.value.toString())
            }
            p.setProperty("max", maximized.toString())
            base.mkdirs()
            file(base).outputStream().use { p.store(it, null) }
        }
    }
}
