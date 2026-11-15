package app.nextdata.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nextdata.protocol.Profile
import app.nextdata.protocol.ProtocolId
import app.nextdata.shared.resources.*
import app.nextdata.state.AppController
import app.nextdata.state.Screen
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Hoisted state of the profile create/edit/delete dialogs. */
@Stable
class ProfileDialogs(private val openInBody: ((Profile?) -> Unit)? = null) {
    var editor by mutableStateOf<EditorRequest?>(null)
    var deleting by mutableStateOf<Profile?>(null)

    class EditorRequest(val profile: Profile?)

    /** Wide layouts open the editor in the content area ([openInBody]); otherwise it is a dialog. */
    fun create() { if (openInBody != null) openInBody.invoke(null) else editor = EditorRequest(null) }
    fun edit(p: Profile) { if (openInBody != null) openInBody.invoke(p) else editor = EditorRequest(p) }
}

@Composable
fun ProfileDialogsHost(controller: AppController, dialogs: ProfileDialogs) {
    val scope = rememberCoroutineScope()
    val wantsNew by controller.newProfileRequested.collectAsState()
    val wantsEdit by controller.editProfileRequested.collectAsState()
    LaunchedEffect(wantsNew) { if (wantsNew) { dialogs.create(); controller.consumeNewProfileRequest() } }
    LaunchedEffect(wantsEdit) { wantsEdit?.let { dialogs.edit(it); controller.consumeEditProfileRequest() } }
    dialogs.editor?.let { req ->
        ProfileEditorDialog(controller, req.profile, onDismiss = { dialogs.editor = null })
    }
    dialogs.deleting?.let { p ->
        ConfirmDialog(
            title = stringResource(Res.string.profile_delete_title, p.name),
            text = stringResource(Res.string.profile_delete_body),
            confirmLabel = stringResource(Res.string.action_delete),
            destructive = true,
            onDismiss = { dialogs.deleting = null },
            onConfirm = {
                dialogs.deleting = null
                scope.launch {
                    controller.onProfileRemoved(p.id)
                    controller.profiles.delete(p.id)
                }
            },
        )
    }
}

/** Phone: the list of profiles under a plain "NextData" title; a plain "+" sits at the top end, level with the title. */
@Composable
fun ProfilesScreen(controller: AppController) {
    val dialogs = remember { ProfileDialogs(openInBody = controller::openProfileEditor) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).semantics { heading() })
                IconButton(onClick = dialogs::create) { Icon(Icons.Outlined.Add, stringResource(Res.string.profile_add)) }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            ProfilesList(controller, dialogs, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp))
        }
    }
    ProfileDialogsHost(controller, dialogs)
}

/** Navigation pane (desktop/tablet): profiles, then Favorites and Recent of the open profile, then Transfers/Settings. */
@Composable
fun ProfilesPane(controller: AppController, modifier: Modifier = Modifier) {
    val dialogs = remember { ProfileDialogs(openInBody = controller::openProfileEditor) }
    val transfers by controller.transfers.items.collectAsState()
    val browser by controller.browser.collectAsState()
    val settings by controller.settings.state.collectAsState()
    val active = transfers.count { it.status.isActive }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 0.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.profiles_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            NdIconButton(Icons.Outlined.Add, stringResource(Res.string.profile_add), dialogs::create, tooltip = stringResource(Res.string.profile_add) + " (Ctrl+Shift+N)")
        }
        Box(Modifier.weight(1f)) {
            val b = browser
            val bs = b?.state?.collectAsState()?.value
            // one scrolling list: profiles first, then Favorites and Recent of the open profile
            ProfilesList(controller, dialogs, contentPadding = PaddingValues(8.dp), footer = if (b == null || bs == null) null else ({
                item(key = "h-fav") { PaneHeader(stringResource(Res.string.favorites_title), Icons.Outlined.Star) }
                if (bs.favorites.isEmpty()) item(key = "fav-empty") { PaneHint(stringResource(Res.string.favorites_empty)) }
                items(bs.favorites, key = { "f" + it.ref }) { f -> PaneLink(f.name, Icons.Outlined.Star, bs.ref == f.ref) { b.open(f.ref) } }
                item(key = "h-rec") { PaneHeader(stringResource(Res.string.recents_title), Icons.Outlined.History) }
                val recents = settings.recents.filter { it.profileId == b.profile.id }
                if (recents.isEmpty()) item(key = "rec-empty") { PaneHint(stringResource(Res.string.recents_empty)) }
                items(recents, key = { "r" + it.ref }) { r -> PaneLink(r.name, Icons.Outlined.Folder, bs.ref == r.ref) { b.open(r.ref) } }
            }))
        }
        TransfersStrip(controller)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { controller.navigate(Screen.Transfers) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) {
                BadgedBox(badge = { if (active > 0) Badge { Text("$active") } }) {
                    Icon(Icons.AutoMirrored.Outlined.CompareArrows, null)
                }
                Spacer(Modifier.size(6.dp))
                Text(stringResource(Res.string.nav_transfers), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { controller.navigate(Screen.Settings) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) {
                Icon(Icons.Outlined.Settings, null)
                Spacer(Modifier.size(6.dp))
                Text(stringResource(Res.string.nav_settings), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    ProfileDialogsHost(controller, dialogs)
}

@Composable
private fun PaneHeader(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PaneHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 40.dp, vertical = 4.dp))
}

@Composable
private fun PaneLink(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, current: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(MaterialTheme.shapes.small)
            .background(if (current) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 32.dp).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (icon == Icons.Outlined.Star) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ProfilesList(controller: AppController, dialogs: ProfileDialogs, contentPadding: PaddingValues, footer: (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)? = null) {
    val state by controller.profiles.state.collectAsState()
    val browser by controller.browser.collectAsState()
    when {
        state.error != null && state.profiles.isEmpty() -> ErrorState(state.error!!, onRetry = controller.profiles::refresh)
        state.profiles.isEmpty() -> EmptyState(
            icon = Icons.Outlined.Storage,
            title = stringResource(Res.string.profiles_empty_title),
            body = stringResource(Res.string.profiles_empty_body),
            action = { Button(onClick = dialogs::create) { Text(stringResource(Res.string.profile_add)) } },
        )
        else -> LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.profiles, key = { it.id }) { p ->
                ProfileRow(
                    profile = p,
                    usable = controller.isProtocolUsable(p),
                    connected = browser?.profile?.id == p.id,
                    onOpen = { controller.connect(p) },
                    onEdit = { dialogs.edit(p) },
                    onDelete = { dialogs.deleting = p },
                )
            }
            footer?.invoke(this)
        }
    }
}

@Composable
private fun ProfileRow(profile: Profile, usable: Boolean, connected: Boolean, onOpen: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val subtitle = buildString {
        if (profile.username.isNotEmpty() && profile.protocol != ProtocolId.S3) append(profile.username).append('@')
        append(profile.s3?.bucket?.takeIf { it.isNotEmpty() }?.let { "$it · " }.orEmpty())
        append(profile.host.ifEmpty { "—" })
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth().animateContentSize(tween(160)),
    ) {
        Row(
            Modifier
                .clickable(enabled = usable, role = Role.Button, onClick = onOpen)
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileAvatar(profile, enabled = usable)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (usable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (usable) stringResource(protocolLabelRes(profile.protocol)) + " · " + subtitle
                    else stringResource(protocolLabelRes(profile.protocol)) + " · " + stringResource(Res.string.protocol_unavailable_badge),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (connected) {
                Icon(Icons.Outlined.CheckCircle, stringResource(Res.string.state_connected), Modifier.size(20.dp), tint = MaterialTheme.nd.success)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(Res.string.action_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.action_edit)) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = { menu = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.action_delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

