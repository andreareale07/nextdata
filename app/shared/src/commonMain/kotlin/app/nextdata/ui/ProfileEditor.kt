package app.nextdata.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.nextdata.i18n.errorText
import app.nextdata.i18n.protocolReasonText
import app.nextdata.protocol.AuthKind
import app.nextdata.protocol.ErrInfo
import app.nextdata.protocol.Profile
import app.nextdata.protocol.ProfileSecrets
import app.nextdata.protocol.ProtocolId
import app.nextdata.protocol.S3Config
import app.nextdata.shared.resources.*
import app.nextdata.state.AppController
import app.nextdata.state.FieldIssue
import app.nextdata.state.Outcome
import app.nextdata.state.isConnectionLost
import app.nextdata.state.ProfileValidation
import app.nextdata.state.ProfileVisuals
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data object Ok : TestState
    data class Failed(val error: ErrInfo, val afterTrustPrompt: Boolean = false) : TestState
}

@Composable
private fun issueText(issue: FieldIssue): String = stringResource(
    when (issue) {
        FieldIssue.Required -> Res.string.field_required
        FieldIssue.HostHasScheme -> Res.string.issue_host_scheme
        FieldIssue.HostHasPath -> Res.string.issue_host_path
        FieldIssue.HostInvalid -> Res.string.issue_host_invalid
        FieldIssue.PortInvalid -> Res.string.issue_port_invalid
        FieldIssue.BucketInvalid -> Res.string.issue_bucket_invalid
        FieldIssue.NameTooLong -> Res.string.issue_name_long
        FieldIssue.BasePathRelative -> Res.string.issue_base_path
    },
)

private fun protocolBlurb(id: String) = when (id) {
    ProtocolId.SFTP -> Res.string.protocol_blurb_sftp
    ProtocolId.WEBDAV -> Res.string.protocol_blurb_webdav
    ProtocolId.S3 -> Res.string.protocol_blurb_s3
    ProtocolId.SMB -> Res.string.protocol_blurb_smb
    else -> Res.string.protocol_blurb_fake
}

/**
 * Create / edit dialog. New profiles go through a 3-step guide (protocol, server, access); editing shows one
 * form. Fields are validated as you type, and "Test connection" saves the profile and opens/closes a session.
 * Secrets live only in this composable's state until `profiles.save`; they are never read back.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProfileEditorDialog(controller: AppController, initial: Profile?, onDismiss: () -> Unit) {
    val profilesState by controller.profiles.state.collectAsState()
    val scope = rememberCoroutineScope()
    val editing = initial != null
    val focusManager = LocalFocusManager.current

    var step by remember { mutableStateOf(if (editing) 1 else 0) }
    var savedId by remember { mutableStateOf(initial?.id.orEmpty()) }

    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var protocol by remember { mutableStateOf(initial?.protocol ?: ProtocolId.SFTP) }
    var host by remember { mutableStateOf(initial?.host.orEmpty()) }
    var port by remember { mutableStateOf(initial?.port?.takeIf { it > 0 }?.toString().orEmpty()) }
    var username by remember { mutableStateOf(initial?.username.orEmpty()) }
    var auth by remember { mutableStateOf(initial?.auth ?: AuthKind.PASSWORD) }
    var basePath by remember { mutableStateOf(initial?.basePath.orEmpty()) }
    var tls by remember { mutableStateOf(initial?.tls ?: true) }
    var color by remember { mutableStateOf(initial?.color ?: controller.profiles.nextColor()) }
    var bucket by remember { mutableStateOf(initial?.s3?.bucket.orEmpty()) }
    var region by remember { mutableStateOf(initial?.s3?.region.orEmpty()) }
    var accessKey by remember { mutableStateOf(initial?.s3?.accessKey.orEmpty()) }
    var pathStyle by remember { mutableStateOf(initial?.s3?.pathStyle ?: false) }

    var password by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var secretKey by remember { mutableStateOf("") }
    var showSecrets by remember { mutableStateOf(false) }

    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<ErrInfo?>(null) }
    var attempted by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf<TestState>(TestState.Idle) }

    val offered = profilesState.offeredProtocols()
    val selectedInfo = offered.firstOrNull { it.id == protocol }
    val unavailable = selectedInfo != null && !selectedInfo.enabled
    val isS3 = protocol == ProtocolId.S3
    val keepSecretHint = editing && initial?.hasSecret == true

    // live validation: format problems show as you type, "required" only after a Next/Save attempt
    fun visible(i: FieldIssue?): FieldIssue? = if (i == FieldIssue.Required && !attempted) null else i
    val nameIssue = visible(ProfileValidation.name(name))
    val hostIssue = visible(ProfileValidation.host(host, required = !isS3))
    val portIssue = ProfileValidation.port(port)
    val bucketIssue = if (isS3) visible(ProfileValidation.bucket(bucket)) else null
    val serverValid = ProfileValidation.name(name) == null && ProfileValidation.host(host, !isS3) == null &&
        ProfileValidation.port(port) == null && (!isS3 || ProfileValidation.bucket(bucket) == null)

    fun buildProfile(): Profile {
        val effectiveAuth = when (protocol) {
            ProtocolId.S3 -> AuthKind.NONE
            ProtocolId.SFTP -> if (auth == AuthKind.KEY) AuthKind.KEY else AuthKind.PASSWORD
            else -> auth
        }
        return Profile(
            id = savedId,
            name = name.trim(),
            protocol = protocol,
            host = host.trim(),
            port = port.toIntOrNull() ?: 0,
            username = if (isS3) "" else username.trim(),
            auth = effectiveAuth,
            basePath = basePath.trim(),
            tls = if (ProfileVisuals.hasTlsSwitch(protocol)) tls else false,
            s3 = if (isS3) S3Config(bucket.trim(), region.trim(), accessKey.trim(), pathStyle) else null,
            hasSecret = initial?.hasSecret ?: false,
            color = color,
        )
    }

    fun buildSecrets(p: Profile): ProfileSecrets? = ProfileSecrets(
        password = password.ifEmpty { null }.takeIf { p.auth == AuthKind.PASSWORD && !isS3 },
        privateKey = privateKey.ifEmpty { null }.takeIf { p.auth == AuthKind.KEY },
        passphrase = passphrase.ifEmpty { null }.takeIf { p.auth == AuthKind.KEY },
        secretKey = secretKey.ifEmpty { null }.takeIf { isS3 },
    ).takeUnless { it.isEmpty }

    fun save() {
        attempted = true
        if (!serverValid || unavailable) {
            if (!editing) step = 1
            return
        }
        saving = true
        error = null
        val profile = buildProfile()
        scope.launch {
            when (val r = controller.profiles.save(profile, buildSecrets(profile))) {
                is Outcome.Success -> onDismiss()
                is Outcome.Failure -> { saving = false; error = r.error }
            }
        }
    }

    fun runTest() {
        attempted = true
        if (!serverValid || unavailable) { if (!editing) step = 1; return }
        test = TestState.Running
        error = null
        val profile = buildProfile()
        scope.launch {
            when (val r = controller.testProfile(profile, buildSecrets(profile))) {
                is Outcome.Success -> {
                    savedId = r.value.profile.id
                    test = r.value.error?.let { TestState.Failed(it, r.value.afterTrustPrompt) } ?: TestState.Ok
                }
                is Outcome.Failure -> test = TestState.Failed(r.error)
            }
        }
    }

    /** A pasted URL in the host field is split into host / port / HTTPS / starting folder. */
    fun applyUrl() {
        ProfileValidation.splitUrl(host)?.let { u ->
            host = u.host
            u.port?.let { port = it.toString() }
            u.tls?.let { tls = it }
            if (u.path.isNotEmpty() && u.path != "/") basePath = u.path
        }
    }

    // keyboard users land on the first field of each step
    val nameFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val accessFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(step) {
        if (!editing && step > 0) runCatching { (if (step == 1) nameFocus else accessFocus).requestFocus() }
    }

    val stepTitles = listOf(Res.string.wizard_step_protocol, Res.string.wizard_step_server, Res.string.wizard_step_access)

    Dialog(onDismissRequest = { if (!saving && test != TestState.Running) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
            val full = maxWidth < 600.dp
            Surface(
                shape = if (full) MaterialTheme.shapes.extraSmall else MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                tonalElevation = 3.dp,
                modifier = (if (full) Modifier.fillMaxSize() else Modifier.padding(16.dp).widthIn(max = 600.dp).heightIn(max = 760.dp).fillMaxWidth().wrapContentHeight()),
            ) {
                Column(Modifier.then(if (full) Modifier.safeContentPadding() else Modifier)) {
                    // header
                    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp)) {
                        Text(
                            stringResource(if (editing) Res.string.profile_edit_title else Res.string.profile_new_title),
                            style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() },
                        )
                        if (!editing) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(Res.string.wizard_step_of, step + 1, 3, stringResource(stepTitles[step])),
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(progress = { (step + 1) / 3f }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Column(
                        Modifier.weight(1f, fill = full).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // ---- step 1: protocol
                        if (step == 0 || editing) {
                            Text(stringResource(Res.string.field_protocol), style = MaterialTheme.typography.labelLarge)
                            offered.forEach { info ->
                                ProtocolCard(
                                    info.id, selected = protocol == info.id, enabled = info.enabled,
                                    onClick = { protocol = info.id; error = null; test = TestState.Idle },
                                )
                            }
                            if (unavailable) {
                                Row(
                                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                                    Column {
                                        Text(stringResource(Res.string.protocol_unavailable_title), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                                        Text(protocolReasonText(selectedInfo?.reasonCode), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }

                        // ---- step 2: server
                        if (step == 1 || editing) {
                            if (editing) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            OutlinedTextField(
                                name, { name = it; test = TestState.Idle }, singleLine = true, isError = nameIssue != null,
                                label = { Text(stringResource(Res.string.field_name)) },
                                supportingText = { Text(nameIssue?.let { issueText(it) } ?: stringResource(Res.string.hint_name)) },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                                modifier = Modifier.fillMaxWidth().focusRequester(nameFocus),
                            )
                            if (!isS3) {
                                OutlinedTextField(
                                    host, { host = it; test = TestState.Idle }, singleLine = true, isError = hostIssue != null,
                                    label = { Text(stringResource(Res.string.field_host)) },
                                    supportingText = {
                                        Column {
                                            Text(hostIssue?.let { issueText(it) } ?: stringResource(Res.string.hint_host))
                                            if (hostIssue == FieldIssue.HostHasScheme || hostIssue == FieldIssue.HostHasPath) {
                                                TextButton(onClick = ::applyUrl, contentPadding = PaddingValues(0.dp)) { Text(stringResource(Res.string.action_fix_url)) }
                                            }
                                        }
                                    },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                OutlinedTextField(
                                    host, { host = it; test = TestState.Idle }, singleLine = true, isError = hostIssue != null,
                                    label = { Text(stringResource(Res.string.field_s3_endpoint)) },
                                    supportingText = { Text(hostIssue?.let { issueText(it) } ?: stringResource(Res.string.field_s3_endpoint_hint)) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            OutlinedTextField(
                                port, { port = it.filter(Char::isDigit).take(5); test = TestState.Idle }, singleLine = true, isError = portIssue != null,
                                label = { Text(stringResource(Res.string.field_port)) },
                                placeholder = { Text(ProfileValidation.defaultPortHint(protocol, tls).takeIf { it > 0 }?.toString() ?: stringResource(Res.string.field_port_default)) },
                                supportingText = {
                                    Text(
                                        portIssue?.let { issueText(it) }
                                            ?: ProfileValidation.defaultPortHint(protocol, tls).takeIf { it > 0 }?.let { stringResource(Res.string.hint_port_default, it) }
                                            ?: stringResource(Res.string.field_port_default),
                                    )
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (ProfileVisuals.hasTlsSwitch(protocol)) {
                                SwitchRow(stringResource(Res.string.field_tls), stringResource(Res.string.field_tls_hint), tls) { tls = it; test = TestState.Idle }
                                if (!tls) StatusBanner(BannerKind.Warning, stringResource(Res.string.security_plain_warning))
                            }
                            if (isS3) {
                                OutlinedTextField(
                                    bucket, { bucket = it; test = TestState.Idle }, singleLine = true, isError = bucketIssue != null,
                                    label = { Text(stringResource(Res.string.field_s3_bucket)) },
                                    supportingText = { Text(bucketIssue?.let { issueText(it) } ?: stringResource(Res.string.hint_bucket)) },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    region, { region = it; test = TestState.Idle }, singleLine = true,
                                    label = { Text(stringResource(Res.string.field_s3_region)) },
                                    supportingText = { Text(stringResource(Res.string.hint_region)) },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                SwitchRow(stringResource(Res.string.field_s3_path_style), stringResource(Res.string.field_s3_path_style_hint), pathStyle) { pathStyle = it; test = TestState.Idle }
                            }
                            OutlinedTextField(
                                basePath, { basePath = it; test = TestState.Idle }, singleLine = true,
                                label = { Text(stringResource(Res.string.field_base_path)) },
                                supportingText = { Text(stringResource(Res.string.field_base_path_hint)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        // ---- step 3: access
                        if (step == 2 || editing) {
                            if (editing) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            if (isS3) {
                                OutlinedTextField(
                                    accessKey, { accessKey = it; test = TestState.Idle }, singleLine = true,
                                    label = { Text(stringResource(Res.string.field_s3_access_key)) },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                    modifier = Modifier.fillMaxWidth().focusRequester(accessFocus),
                                )
                                SecretField(secretKey, { secretKey = it; test = TestState.Idle }, stringResource(Res.string.field_s3_secret_key), showSecrets, { showSecrets = !showSecrets }, keepSecretHint)
                            } else {
                                OutlinedTextField(
                                    username, { username = it; test = TestState.Idle }, singleLine = true,
                                    label = { Text(stringResource(Res.string.field_username)) },
                                    supportingText = { Text(stringResource(Res.string.hint_username)) },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                    modifier = Modifier.fillMaxWidth().focusRequester(accessFocus),
                                )
                                if (protocol == ProtocolId.SFTP) {
                                    Text(stringResource(Res.string.field_auth), style = MaterialTheme.typography.labelLarge)
                                    AuthChoice(auth != AuthKind.KEY, stringResource(Res.string.auth_password)) { auth = AuthKind.PASSWORD; test = TestState.Idle }
                                    AuthChoice(auth == AuthKind.KEY, stringResource(Res.string.auth_key)) { auth = AuthKind.KEY; test = TestState.Idle }
                                } else if (protocol == ProtocolId.WEBDAV || protocol == ProtocolId.FAKE) {
                                    Text(stringResource(Res.string.field_auth), style = MaterialTheme.typography.labelLarge)
                                    AuthChoice(auth != AuthKind.NONE, stringResource(Res.string.auth_password)) { auth = AuthKind.PASSWORD; test = TestState.Idle }
                                    AuthChoice(auth == AuthKind.NONE, stringResource(Res.string.auth_none)) { auth = AuthKind.NONE; test = TestState.Idle }
                                }
                                val effective = if (protocol == ProtocolId.SFTP && auth == AuthKind.KEY) AuthKind.KEY else auth
                                if (effective == AuthKind.PASSWORD) {
                                    SecretField(password, { password = it; test = TestState.Idle }, stringResource(Res.string.field_password), showSecrets, { showSecrets = !showSecrets }, keepSecretHint)
                                } else if (effective == AuthKind.KEY) {
                                    OutlinedTextField(
                                        privateKey, { privateKey = it; test = TestState.Idle },
                                        label = { Text(stringResource(Res.string.field_private_key)) },
                                        supportingText = { Text(stringResource(if (keepSecretHint) Res.string.secret_keep_hint else Res.string.field_private_key_hint)) },
                                        minLines = 3, maxLines = 6,
                                        visualTransformation = if (showSecrets || privateKey.isEmpty()) VisualTransformation.None else PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    OutlinedButton(onClick = {
                                        scope.launch { controller.env.files.pickBytes(emptyList())?.let { privateKey = it.decodeToString() } }
                                    }) { Text(stringResource(Res.string.action_load_key_file)) }
                                    SecretField(passphrase, { passphrase = it }, stringResource(Res.string.field_passphrase_optional), showSecrets, { showSecrets = !showSecrets }, false)
                                }
                            }

                            Text(stringResource(Res.string.field_color), style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                ProfileVisuals.palette.forEach { hex ->
                                    val c = Color((ProfileVisuals.parseColor(hex) ?: 0xFF475569L).toInt())
                                    val chosen = color.equals(hex, ignoreCase = true)
                                    Box(
                                        Modifier
                                            .size(44.dp)
                                            .clip(CircleShape)
                                            .background(c)
                                            .then(if (chosen) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                            .semantics { contentDescription = hex; selected = chosen; role = Role.RadioButton }
                                            .clickable { color = hex },
                                        contentAlignment = Alignment.Center,
                                    ) { if (chosen) Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
                                }
                            }

                            // connection test
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(onClick = ::runTest, enabled = test != TestState.Running && !saving && !unavailable) {
                                    if (test == TestState.Running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.NetworkCheck, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(if (test == TestState.Running) Res.string.profile_test_running else Res.string.profile_test))
                                }
                                Text(stringResource(Res.string.profile_test_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            }
                            when (val t = test) {
                                TestState.Ok -> StatusBanner(BannerKind.Success, stringResource(Res.string.profile_test_ok))
                                is TestState.Failed -> {
                                    StatusBanner(BannerKind.Error, stringResource(Res.string.profile_test_failed, errorText(t.error)))
                                    if (t.afterTrustPrompt && t.error.code == app.nextdata.protocol.ErrorCodes.TIMEOUT) {
                                        Text(stringResource(Res.string.connect_hint_trust_timeout), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (isS3 && t.error.isConnectionLost() && !pathStyle && host.isNotBlank()) {
                                        Text(stringResource(Res.string.hint_s3_path_style), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                else -> Unit
                            }
                        }

                        error?.let {
                            StatusBanner(BannerKind.Error, errorText(it))
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // footer
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!editing && step > 0) {
                            TextButton(onClick = { step -= 1 }, enabled = !saving) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(Res.string.action_back))
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDismiss, enabled = !saving && test != TestState.Running) { Text(stringResource(Res.string.action_cancel)) }
                        Spacer(Modifier.width(8.dp))
                        if (!editing && step < 2) {
                            Button(
                                onClick = {
                                    if (step == 1) {
                                        attempted = true
                                        if (serverValid) step = 2
                                    } else step = 1
                                },
                                enabled = !(step == 0 && unavailable),
                            ) { Text(stringResource(Res.string.action_next)) }
                        } else {
                            Button(onClick = ::save, enabled = !saving && !unavailable && test != TestState.Running) {
                                Text(stringResource(if (saving) Res.string.action_saving else Res.string.action_save))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtocolCard(id: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    val label = stringResource(protocolLabelRes(id))
    val stateDesc = stringResource(if (selected) Res.string.state_selected else Res.string.state_not_selected)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected; stateDescription = stateDesc }
            .heightIn(min = 64.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(protocolIcon(id), null, Modifier.size(28.dp), tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label + if (!enabled) " · " + stringResource(Res.string.protocol_unavailable_badge) else "", style = MaterialTheme.typography.titleSmall)
            Text(stringResource(protocolBlurb(id)), style = MaterialTheme.typography.bodySmall, color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(role = Role.Switch) { onChange(!checked) }.heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, null)
    }
}

@Composable
private fun AuthChoice(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small).clickable(role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null)
        Spacer(Modifier.size(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 8.dp))
    }
}

@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, label: String, visible: Boolean, onToggle: () -> Unit, keepHint: Boolean) {
    OutlinedTextField(
        value, onChange, singleLine = true,
        label = { Text(label) },
        supportingText = if (keepHint) ({ Text(stringResource(Res.string.secret_keep_hint)) }) else null,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = onToggle) {
                Icon(
                    if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = stringResource(if (visible) Res.string.action_hide_secret else Res.string.action_show_secret),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
