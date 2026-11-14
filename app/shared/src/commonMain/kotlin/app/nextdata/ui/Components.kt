package app.nextdata.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.nextdata.i18n.errorText
import app.nextdata.protocol.ErrInfo
import app.nextdata.protocol.Profile
import app.nextdata.protocol.ProtocolId
import app.nextdata.shared.resources.*
import app.nextdata.state.FileCategory
import app.nextdata.state.ProfileVisuals
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

fun protocolIcon(protocol: String): ImageVector = when (protocol) {
    ProtocolId.SFTP -> Icons.Outlined.Terminal
    ProtocolId.WEBDAV -> Icons.Outlined.Cloud
    ProtocolId.S3 -> Icons.Outlined.Storage
    ProtocolId.SMB -> Icons.Outlined.Dns
    ProtocolId.FAKE -> Icons.Outlined.Science
    else -> Icons.Outlined.Storage
}

fun protocolLabelRes(protocol: String): StringResource = when (protocol) {
    ProtocolId.SFTP -> Res.string.protocol_sftp
    ProtocolId.WEBDAV -> Res.string.protocol_webdav
    ProtocolId.S3 -> Res.string.protocol_s3
    ProtocolId.SMB -> Res.string.protocol_smb
    ProtocolId.FAKE -> Res.string.protocol_fake
    else -> Res.string.protocol_unknown
}

fun categoryIcon(c: FileCategory): ImageVector = when (c) {
    FileCategory.Folder -> Icons.Outlined.Folder
    FileCategory.Link -> Icons.Outlined.Link
    FileCategory.Special -> Icons.Outlined.Memory
    FileCategory.Image -> Icons.Outlined.Image
    FileCategory.Audio -> Icons.Outlined.AudioFile
    FileCategory.Video -> Icons.Outlined.Movie
    FileCategory.Archive -> Icons.Outlined.FolderZip
    FileCategory.Pdf -> Icons.Outlined.PictureAsPdf
    FileCategory.Document -> Icons.Outlined.Description
    FileCategory.Spreadsheet -> Icons.Outlined.TableChart
    FileCategory.Presentation -> Icons.Outlined.Slideshow
    FileCategory.Code -> Icons.Outlined.Code
    FileCategory.Text -> Icons.Outlined.Article
    FileCategory.Generic -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

@Composable
fun categoryTint(c: FileCategory): Color = when (c) {
    FileCategory.Folder -> MaterialTheme.colorScheme.primary
    FileCategory.Link -> MaterialTheme.colorScheme.tertiary
    FileCategory.Image -> Color(0xFF7C3AED)
    FileCategory.Audio -> Color(0xFFDB2777)
    FileCategory.Video -> Color(0xFFEA580C)
    FileCategory.Archive -> Color(0xFFB45309)
    FileCategory.Pdf -> Color(0xFFDC2626)
    FileCategory.Document -> Color(0xFF2563EB)
    FileCategory.Spreadsheet -> Color(0xFF16A34A)
    FileCategory.Presentation -> Color(0xFFD97706)
    FileCategory.Code -> Color(0xFF0891B2)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Rounded tile with the profile's colour and protocol icon. */
@Composable
fun ProfileAvatar(profile: Profile, size: Dp = 40.dp, enabled: Boolean = true) {
    val argb = ProfileVisuals.parseColor(profile.color) ?: 0xFF475569L
    val bg = Color(argb.toInt())
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4))
            .background(if (enabled) bg else bg.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(protocolIcon(profile.protocol), contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
fun CenterLoading(modifier: Modifier = Modifier) {
    val desc = stringResource(Res.string.loading)
    Box(modifier.fillMaxSize().semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp),
            )
        }
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

@Composable
fun ErrorState(
    error: ErrInfo,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    retryLabel: String? = null,
) {
    EmptyState(
        icon = Icons.Outlined.ErrorOutline,
        title = stringResource(Res.string.error_title),
        body = errorText(error),
        modifier = modifier,
        action = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onSecondary != null && secondaryLabel != null) OutlinedButton(onClick = onSecondary) { Text(secondaryLabel) }
                if (onRetry != null) Button(onClick = onRetry) { Text(retryLabel ?: stringResource(Res.string.action_retry)) }
            }
        },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

@Composable
fun ErrorDialog(error: ErrInfo, onDismiss: () -> Unit, onRetry: (() -> Unit)? = null, title: String = stringResource(Res.string.error_title)) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(title) },
        text = { Text(errorText(error)) },
        confirmButton = {
            if (onRetry != null) TextButton(onClick = onRetry) { Text(stringResource(Res.string.action_retry)) }
            else TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_ok)) }
        },
        dismissButton = if (onRetry != null) {
            { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_close)) } }
        } else null,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NdTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                if (subtitle != null) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
