package app.nextdata.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.nextdata.i18n.errorText
import app.nextdata.platform.SecretProtection
import app.nextdata.protocol.Collision
import app.nextdata.protocol.ErrInfo
import app.nextdata.protocol.ExportOptions
import app.nextdata.protocol.ImportResult
import app.nextdata.shared.resources.*
import app.nextdata.state.AppController
import app.nextdata.state.BootState
import app.nextdata.state.ExportOutcome
import app.nextdata.state.LanguageChoice
import app.nextdata.state.Outcome
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(controller: AppController, onBack: () -> Unit) {
    val settings by controller.settings.state.collectAsState()
    val boot by controller.boot.collectAsState()
    val settingsError by controller.settings.error.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var exporting by remember { mutableStateOf(false) }
    var importBytes by remember { mutableStateOf<ByteArray?>(null) }
    val exportedMsg = stringResource(Res.string.export_done)
    val settingsErrorText = settingsError?.let { errorText(it) }
    var importedCount by remember { mutableStateOf<Int?>(null) }
    val importedText = importedCount?.let { pluralStringResource(Res.plurals.import_done, it, it) }
    LaunchedEffect(importedCount) {
        if (importedText != null) {
            snackbar.showSnackbar(importedText)
            importedCount = null
        }
    }

    LaunchedEffect(settingsError) {
        if (settingsErrorText != null) {
            snackbar.showSnackbar(settingsErrorText)
            controller.settings.consumeError()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { NdTopBar(stringResource(Res.string.nav_settings), onBack = onBack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(bottom = 32.dp)) {
                // ---- language
                SectionLabel(stringResource(Res.string.settings_language))
                LanguageChoice.entries.forEach { choice ->
                    RadioRow(
                        selected = settings.language == choice,
                        label = stringResource(
                            when (choice) {
                                LanguageChoice.System -> Res.string.language_system
                                LanguageChoice.Italian -> Res.string.language_it
                                LanguageChoice.English -> Res.string.language_en
                            },
                        ),
                        onClick = { controller.settings.update { it.copy(language = choice) } },
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                // ---- display
                SectionLabel(stringResource(Res.string.settings_display))
                Text(stringResource(Res.string.settings_theme), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp))
                app.nextdata.state.ThemeChoice.entries.forEach { t ->
                    RadioRow(
                        settings.theme == t,
                        stringResource(when (t) { app.nextdata.state.ThemeChoice.System -> Res.string.theme_system; app.nextdata.state.ThemeChoice.Light -> Res.string.theme_light; app.nextdata.state.ThemeChoice.Dark -> Res.string.theme_dark }),
                        onClick = { controller.settings.update { it.copy(theme = t) } },
                    )
                }
                Text(stringResource(Res.string.settings_density), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp))
                RadioRow(settings.density == null, stringResource(Res.string.density_auto), onClick = { controller.settings.update { it.copy(density = null) } }, supporting = stringResource(Res.string.density_auto_hint))
                RadioRow(settings.density == app.nextdata.state.Density.Comfortable, stringResource(Res.string.density_comfortable), onClick = { controller.settings.update { it.copy(density = app.nextdata.state.Density.Comfortable) } }, supporting = stringResource(Res.string.density_comfortable_hint))
                RadioRow(settings.density == app.nextdata.state.Density.Compact, stringResource(Res.string.density_compact), onClick = { controller.settings.update { it.copy(density = app.nextdata.state.Density.Compact) } }, supporting = stringResource(Res.string.density_compact_hint))
                Text(stringResource(Res.string.settings_view_mode), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
                RadioRow(settings.viewMode == app.nextdata.state.ViewMode.List, stringResource(Res.string.view_list), onClick = { controller.settings.update { it.copy(viewMode = app.nextdata.state.ViewMode.List) } })
                RadioRow(settings.viewMode == app.nextdata.state.ViewMode.Grid, stringResource(Res.string.view_grid), onClick = { controller.settings.update { it.copy(viewMode = app.nextdata.state.ViewMode.Grid) } })
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                // ---- browsing
                SectionLabel(stringResource(Res.string.settings_files))
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Switch) { controller.settings.update { it.copy(askDownloadLocation = !it.askDownloadLocation) } }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(Res.string.settings_ask_download), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(Res.string.settings_ask_download_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(settings.askDownloadLocation, onCheckedChange = null)
                }
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Switch) { controller.settings.update { it.copy(showHidden = !it.showHidden) } }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(Res.string.settings_show_hidden), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(Res.string.settings_show_hidden_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(settings.showHidden, onCheckedChange = null)
                }
                Text(
                    stringResource(Res.string.settings_collision),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )
                Text(
                    stringResource(Res.string.settings_collision_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Collision.all.forEach { c ->
                    val (label, hint) = when (c) {
                        Collision.SKIP -> Res.string.collision_skip to Res.string.collision_skip_hint
                        Collision.FAIL -> Res.string.collision_fail to Res.string.collision_fail_hint
                        else -> Res.string.collision_rename to Res.string.collision_rename_hint
                    }
                    RadioRow(settings.collision == c, stringResource(label), onClick = { controller.settings.update { it.copy(collision = c) } }, supporting = stringResource(hint))
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                // ---- profile export / import
                SectionLabel(stringResource(Res.string.settings_profiles_data))
                Text(
                    stringResource(Res.string.settings_profiles_data_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { exporting = true }) {
                        Icon(Icons.Outlined.FileUpload, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(Res.string.action_export_profiles))
                    }
                    OutlinedButton(onClick = {
                        scope.launch { controller.profileTransfer.pickImportFile()?.let { importBytes = it } }
                    }) {
                        Icon(Icons.Outlined.FileDownload, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(Res.string.action_import_profiles))
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                // ---- privacy
                SectionLabel(stringResource(Res.string.settings_privacy))
                PrivacyCard(controller)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                // ---- about
                SectionLabel(stringResource(Res.string.settings_about))
                Text(
                    stringResource(Res.string.about_app_version, controller.env.appVersion),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                (boot as? BootState.Ready)?.let {
                    Text(
                        stringResource(Res.string.about_engine_version, it.info.version.ifEmpty { "?" }),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }

    if (exporting) {
        ExportDialog(
            controller,
            onDismiss = { exporting = false },
            onDone = { exporting = false; scope.launch { snackbar.showSnackbar(exportedMsg) } },
        )
    }
    importBytes?.let { bytes ->
        ImportDialog(
            controller, bytes,
            onDismiss = { importBytes = null },
            onApplied = { n ->
                importBytes = null
                importedCount = n
            },
        )
    }
}

@Composable
private fun RadioRow(selected: Boolean, label: String, onClick: () -> Unit, supporting: String? = null) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).semantics { role = Role.RadioButton }.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null)
        Spacer(Modifier.size(16.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PrivacyCard(controller: AppController) {
    val env = controller.env
    val protection = when (env.secretProtection) {
        SecretProtection.WindowsDpapi -> stringResource(Res.string.privacy_pmk_dpapi)
        SecretProtection.AndroidKeystore -> stringResource(Res.string.privacy_pmk_keystore)
        SecretProtection.Other -> stringResource(Res.string.privacy_pmk_other)
    }
    Card(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.PrivacyTip, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(10.dp))
                Text(stringResource(Res.string.privacy_title), style = MaterialTheme.typography.titleMedium)
            }
            PrivacyItem(stringResource(Res.string.privacy_what_title), stringResource(Res.string.privacy_what_body))
            PrivacyItem(stringResource(Res.string.privacy_secrets_title), stringResource(Res.string.privacy_secrets_body))
            PrivacyItem(stringResource(Res.string.privacy_key_title), protection)
            PrivacyItem(stringResource(Res.string.privacy_where_title), stringResource(Res.string.privacy_where_body, env.dataDir))
            PrivacyItem(stringResource(Res.string.privacy_trust_title), stringResource(Res.string.privacy_trust_body))
            PrivacyItem(stringResource(Res.string.privacy_export_title), stringResource(Res.string.privacy_export_body))
            PrivacyItem(stringResource(Res.string.privacy_network_title), stringResource(Res.string.privacy_network_body))
        }
    }
}

@Composable
private fun PrivacyItem(title: String, body: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ============================================================================ export

@Composable
private fun ExportDialog(controller: AppController, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var favorites by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var pins by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<ErrInfo?>(null) }
    val mismatch = confirm.isNotEmpty() && pass != confirm
    val fileName = stringResource(Res.string.export_file_name)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(Res.string.export_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.export_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    pass, { pass = it; error = null }, singleLine = true,
                    label = { Text(stringResource(Res.string.field_passphrase)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    confirm, { confirm = it }, singleLine = true, isError = mismatch,
                    label = { Text(stringResource(Res.string.field_passphrase_confirm)) },
                    supportingText = if (mismatch) ({ Text(stringResource(Res.string.passphrase_mismatch)) }) else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(Res.string.export_include), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                ExportSwitch(stringResource(Res.string.export_opt_favorites), favorites) { favorites = it }
                ExportSwitch(stringResource(Res.string.export_opt_settings), settings) { settings = it }
                ExportSwitch(stringResource(Res.string.export_opt_pins), pins) { pins = it }
                if (pins) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Warning, null, tint = MaterialTheme.colorScheme.error)
                        Text(stringResource(Res.string.export_pins_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && pass.isNotEmpty() && pass == confirm,
                onClick = {
                    busy = true
                    scope.launch {
                        when (val r = controller.profileTransfer.export(pass, ExportOptions(favorites, settings, pins), fileName)) {
                            is Outcome.Success -> if (r.value == ExportOutcome.Saved) onDone() else busy = false
                            is Outcome.Failure -> { busy = false; error = r.error }
                        }
                    }
                },
            ) { Text(stringResource(Res.string.action_export)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

@Composable
private fun ExportSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange)
    }
}

// ============================================================================ import

@Composable
private fun ImportDialog(controller: AppController, bytes: ByteArray, onDismiss: () -> Unit, onApplied: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<ImportResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<ErrInfo?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(Res.string.import_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val p = preview
                if (p == null) {
                    Text(stringResource(Res.string.import_body), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        pass, { pass = it; error = null }, singleLine = true,
                        label = { Text(stringResource(Res.string.field_passphrase)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = error != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(pluralStringResource(Res.plurals.import_preview_summary, p.profiles.size, p.profiles.size), fontWeight = FontWeight.SemiBold)
                    val conflictIds = p.conflicts.map { it.id }.toSet()
                    p.profiles.forEach { prof ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ProfileAvatar(prof, size = 28.dp)
                            Spacer(Modifier.size(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(prof.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    stringResource(if (prof.id in conflictIds) Res.string.import_will_replace else Res.string.import_new),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (prof.id in conflictIds) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    if (p.conflicts.isNotEmpty()) {
                        Text(stringResource(Res.string.import_conflict_warning), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(stringResource(Res.string.import_no_credentials), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            val p = preview
            if (p == null) {
                Button(enabled = !busy && pass.isNotEmpty(), onClick = {
                    busy = true
                    scope.launch {
                        when (val r = controller.profileTransfer.preview(bytes, pass)) {
                            is Outcome.Success -> { preview = r.value; busy = false }
                            is Outcome.Failure -> { error = r.error; busy = false }
                        }
                    }
                }) { Text(stringResource(Res.string.action_continue)) }
            } else {
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        when (val r = controller.profileTransfer.apply(bytes, pass)) {
                            is Outcome.Success -> onApplied(r.value.profiles.size)
                            is Outcome.Failure -> { error = r.error; busy = false }
                        }
                    }
                }) { Text(stringResource(Res.string.action_import)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(Res.string.action_cancel)) } },
    )
}
