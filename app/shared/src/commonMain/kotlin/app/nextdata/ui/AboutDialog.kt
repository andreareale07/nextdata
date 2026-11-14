package app.nextdata.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nextdata.shared.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun AboutDialog(appVersion: String, engineVersion: String?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.about_title)) },
        text = {
            Column {
                Text(stringResource(Res.string.about_body))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(Res.string.about_app_version, appVersion), style = MaterialTheme.typography.bodySmall)
                if (engineVersion != null) {
                    Text(stringResource(Res.string.about_engine_version, engineVersion), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_ok)) } },
    )
}
