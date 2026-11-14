package app.nextdata.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.nextdata.shared.resources.*
import app.nextdata.state.Screen
import org.jetbrains.compose.resources.stringResource

/** Screens that are reachable from the phone's bottom bar. */
internal val PhoneTabs = setOf(Screen.Profiles, Screen.Transfers, Screen.Settings)

/** Slim bottom bar (phone): the thumb reaches it, unlike controls in the top corner. */
@Composable
internal fun PhoneNavBar(current: Screen, activeTransfers: Int, onSelect: (Screen) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                PhoneTab(current == Screen.Profiles, Icons.Outlined.Storage, stringResource(Res.string.profiles_title), 0, { onSelect(Screen.Profiles) }, Modifier.weight(1f))
                PhoneTab(current == Screen.Transfers, Icons.AutoMirrored.Outlined.CompareArrows, stringResource(Res.string.nav_transfers), activeTransfers, { onSelect(Screen.Transfers) }, Modifier.weight(1f))
                PhoneTab(current == Screen.Settings, Icons.Outlined.Settings, stringResource(Res.string.nav_settings), 0, { onSelect(Screen.Settings) }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PhoneTab(selected: Boolean, icon: ImageVector, label: String, badge: Int, onClick: () -> Unit, modifier: Modifier) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.fillMaxHeight().clickable(role = Role.Tab, onClick = onClick).semantics { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BadgedBox(badge = { if (badge > 0) Badge { Text("$badge") } }) { Icon(icon, null, tint = tint) }
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}

/**
 * Horizontal swipe between sibling screens. A finger moving right-to-left calls [onLeft]; left-to-right calls [onRight].
 * Drags that start in the outer edge strips are ignored, so the system "back" edge gesture keeps working untouched.
 */
internal fun Modifier.horizontalSwipe(onLeft: (() -> Unit)? = null, onRight: (() -> Unit)? = null): Modifier = composedSwipe(onLeft, onRight)

private fun Modifier.composedSwipe(onLeft: (() -> Unit)?, onRight: (() -> Unit)?): Modifier =
    this.then(Modifier.pointerInput(onLeft != null, onRight != null) {
        val edge = 32.dp.toPx()
        val threshold = 72.dp.toPx()
        var startX = 0f
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { startX = it.x; total = 0f },
            onDragCancel = { total = 0f },
            onDragEnd = {
                val inside = startX >= edge && startX <= size.width - edge
                if (inside && total <= -threshold) onLeft?.invoke() else if (inside && total >= threshold) onRight?.invoke()
                total = 0f
            },
            onHorizontalDrag = { _, dx -> total += dx },
        )
    })
