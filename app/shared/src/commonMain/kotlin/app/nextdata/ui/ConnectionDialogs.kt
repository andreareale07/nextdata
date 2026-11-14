package app.nextdata.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.nextdata.i18n.certReasonRes
import app.nextdata.i18n.errorText
import app.nextdata.protocol.ErrorCodes
import app.nextdata.protocol.Prompt
import app.nextdata.protocol.PromptKind
import app.nextdata.shared.resources.*
import app.nextdata.state.AppController
import app.nextdata.state.ConnectionState
import org.jetbrains.compose.resources.stringResource

/** Host-wide dialogs: engine prompts and the connection progress/error dialog. */
@Composable
fun ConnectionDialogs(controller: AppController) {
    val queue by controller.prompts.queue.collectAsState()
    val connection by controller.connection.collectAsState()
    val prompt = queue.firstOrNull()

    when (val p = prompt) {
        is Prompt.TrustHost -> if (p.prior != null) HostKeyChangedDialog(p, controller) else TrustHostDialog(p, controller)
        is Prompt.TrustCert -> TrustCertDialog(p, controller)
        is Prompt.Secret -> SecretDialog(p, controller)
        else -> when (val c = connection) {
            is ConnectionState.Connecting -> ConnectingDialog(c.profile.name, controller::cancelConnect)
            is ConnectionState.Failed -> ConnectFailedDialog(
                name = c.profile.name,
                code = c.error.code,
                message = errorText(c.error),
                onDismiss = controller::dismissConnectionError,
                onRetry = controller::retryConnect,
                onEdit = { controller.dismissConnectionError(); controller.requestEditProfile(c.profile) },
                trustTimeout = c.afterTrustPrompt && c.error.code == ErrorCodes.TIMEOUT,
            )
            ConnectionState.Idle -> Unit
        }
    }
}

@Composable
private fun ConnectFailedDialog(name: String, code: String, message: String, onDismiss: () -> Unit, onRetry: () -> Unit, onEdit: () -> Unit, trustTimeout: Boolean = false) {
    val hint = if (trustTimeout) Res.string.connect_hint_trust_timeout else when (code) {
        ErrorCodes.AUTH_FAILED -> Res.string.connect_hint_auth
        ErrorCodes.NETWORK, ErrorCodes.TIMEOUT -> Res.string.connect_hint_network
        ErrorCodes.HOST_KEY_REJECTED, ErrorCodes.HOST_KEY_CHANGED, ErrorCodes.TLS_UNTRUSTED -> Res.string.connect_hint_trust
        else -> null
    }
    val canEdit = code == ErrorCodes.AUTH_FAILED || code == ErrorCodes.NETWORK || code == ErrorCodes.TIMEOUT ||
        code == ErrorCodes.INVALID_ARGUMENT || code == ErrorCodes.TLS_ERROR
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(Res.string.connect_failed_title, name)) },
        text = {
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Assertive }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message)
                if (hint != null) Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (canEdit) TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.connect_edit_profile))
                }
            }
        },
        confirmButton = { Button(onClick = onRetry) { Text(stringResource(Res.string.action_retry)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_close)) } },
    )
}

@Composable
private fun ConnectingDialog(name: String, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(Res.string.connecting_title)) },
        text = {
            val desc = stringResource(Res.string.connecting_to, name)
            Row(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                Text(desc)
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

/** Shared frame of the trust dialogs: tinted header with big icon and a status label, scrollable body, action area. */
@Composable
private fun TrustFrame(
    icon: ImageVector,
    accent: Color,
    accentContainer: Color,
    onAccentContainer: Color,
    title: String,
    statusLabel: String,
    subtitle: String,
    onDismiss: () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(16.dp).widthIn(max = 520.dp).fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
        ) {
            Column {
                Column(Modifier.fillMaxWidth().background(accentContainer).padding(horizontal = 24.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
                        Icon(icon, null, Modifier.size(32.dp), tint = Color.White)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(statusLabel, style = MaterialTheme.typography.labelLarge, color = onAccentContainer, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text(title, style = MaterialTheme.typography.titleLarge, color = onAccentContainer, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = onAccentContainer, textAlign = TextAlign.Center)
                }
                // body and actions scroll together, so with a 200% font scale nothing is cut off
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    body()
                    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
                }
            }
        }
    }
}

@Composable
private fun FingerprintSection(label: String, value: String, other: String? = null) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.text.selection.SelectionContainer(Modifier.weight(1f)) { FingerprintBlocks(value, other) }
            CopyButton(value, stringResource(Res.string.action_copy_fingerprint))
        }
    }
}

@Composable
private fun TrustHostDialog(p: Prompt.TrustHost, controller: AppController) {
    val nd = MaterialTheme.nd
    TrustFrame(
        icon = Icons.Outlined.Shield, accent = nd.info, accentContainer = nd.infoContainer, onAccentContainer = nd.onInfoContainer,
        title = stringResource(Res.string.trust_host_title), statusLabel = stringResource(Res.string.trust_status_new),
        subtitle = "${p.host}:${p.port}",
        onDismiss = { controller.prompts.reply(accept = false) },
        body = {
            Text(stringResource(Res.string.trust_host_body, p.host, p.port))
            FingerprintSection(stringResource(Res.string.fingerprint_algo, p.algo), p.fingerprint)
            Text(stringResource(Res.string.trust_host_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        actions = {
            // keyboard default = the safe choice: Enter never trusts a server by accident
            val safe = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { safe.requestFocus() } }
            Button(onClick = { controller.prompts.reply(accept = true) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.trust_accept)) }
            OutlinedButton(onClick = { controller.prompts.reply(accept = false) }, modifier = Modifier.fillMaxWidth().focusRequester(safe)) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

/** Alarming dialog: the server key differs from the pinned one. Replacing it is separate, secondary and gated. */
@Composable
private fun HostKeyChangedDialog(p: Prompt.TrustHost, controller: AppController) {
    var verified by remember(p.promptId) { mutableStateOf(false) }
    val danger = MaterialTheme.colorScheme.error
    TrustFrame(
        icon = Icons.Outlined.GppBad, accent = danger,
        accentContainer = MaterialTheme.colorScheme.errorContainer, onAccentContainer = MaterialTheme.colorScheme.onErrorContainer,
        title = stringResource(Res.string.hostkey_changed_title), statusLabel = stringResource(Res.string.trust_status_changed),
        subtitle = "${p.host}:${p.port}",
        onDismiss = { controller.prompts.reply(accept = false) },
        body = {
            Text(stringResource(Res.string.hostkey_changed_body, p.host, p.port), fontWeight = FontWeight.SemiBold)
            Text(stringResource(Res.string.hostkey_changed_risk), style = MaterialTheme.typography.bodyMedium)
            FingerprintSection(stringResource(Res.string.hostkey_previous), p.prior.orEmpty())
            FingerprintSection(stringResource(Res.string.hostkey_presented, p.algo), p.fingerprint, other = p.prior)
            Text(stringResource(Res.string.hostkey_diff_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        actions = {
            // The safe choice is the prominent one and has the keyboard focus.
            val safe = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { safe.requestFocus() } }
            Button(onClick = { controller.prompts.reply(accept = false) }, modifier = Modifier.fillMaxWidth().focusRequester(safe)) {
                Icon(Icons.Outlined.Block, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.hostkey_changed_cancel))
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(role = androidx.compose.ui.semantics.Role.Checkbox) { verified = !verified }.heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = verified, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.hostkey_changed_verified), style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(
                onClick = { controller.prompts.reply(accept = true, replace = true) },
                enabled = verified,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = danger),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Warning, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.hostkey_changed_replace), fontWeight = FontWeight.Bold)
            }
        },
    )
}

@Composable
private fun TrustCertDialog(p: Prompt.TrustCert, controller: AppController) {
    val nd = MaterialTheme.nd
    TrustFrame(
        icon = Icons.Outlined.Lock, accent = nd.warning, accentContainer = nd.warningContainer, onAccentContainer = nd.onWarningContainer,
        title = stringResource(Res.string.trust_cert_title), statusLabel = stringResource(Res.string.trust_status_untrusted),
        subtitle = "${p.host}:${p.port}",
        onDismiss = { controller.prompts.reply(accept = false) },
        body = {
            Text(stringResource(Res.string.trust_cert_body, p.host, p.port))
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(nd.warningContainer).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.WarningAmber, null, tint = nd.onWarningContainer)
                Text(stringResource(certReasonRes(p.reason)), color = nd.onWarningContainer, fontWeight = FontWeight.SemiBold)
            }
            if (p.subject.isNotEmpty()) DetailLine(stringResource(Res.string.cert_subject), p.subject, mono = true)
            FingerprintSection(stringResource(Res.string.cert_spki), p.spkiSha256)
            Text(stringResource(Res.string.trust_cert_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        actions = {
            val safe = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { safe.requestFocus() } }
            Button(onClick = { controller.prompts.reply(accept = false) }, modifier = Modifier.fillMaxWidth().focusRequester(safe)) { Text(stringResource(Res.string.action_cancel)) }
            OutlinedButton(onClick = { controller.prompts.reply(accept = true) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.trust_cert_accept)) }
        },
    )
}

@Composable
private fun SecretDialog(p: Prompt.Secret, controller: AppController) {
    var value by remember(p.promptId) { mutableStateOf("") }
    var visible by remember(p.promptId) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val isPassphrase = p.kind == PromptKind.PASSPHRASE
    LaunchedEffect(p.promptId) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = { controller.prompts.reply(accept = false) },
        icon = { Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(if (isPassphrase) Res.string.secret_passphrase_title else Res.string.secret_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.secret_body, p.profileName))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text(stringResource(if (isPassphrase) Res.string.field_passphrase else Res.string.field_password)) },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = stringResource(if (visible) Res.string.action_hide_secret else Res.string.action_show_secret),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = {
            Button(onClick = { controller.prompts.reply(accept = true, value = value) }, enabled = value.isNotEmpty()) {
                Text(stringResource(Res.string.action_connect))
            }
        },
        dismissButton = { TextButton(onClick = { controller.prompts.reply(accept = false) }) { Text(stringResource(Res.string.action_cancel)) } },
    )
}
