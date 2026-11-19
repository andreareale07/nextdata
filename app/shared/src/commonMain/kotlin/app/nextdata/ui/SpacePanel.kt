package app.nextdata.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.nextdata.i18n.LocalLanguageTag
import app.nextdata.protocol.SpaceInfo
import app.nextdata.shared.resources.*
import app.nextdata.state.Formatters
import org.jetbrains.compose.resources.stringResource

/** How full the volume is. Colour is never the only signal: the warning levels also get an icon and a sentence. */
enum class SpaceLevel { Normal, Warning, Critical }

fun SpaceInfo.level(): SpaceLevel {
    val f = usedFraction ?: return SpaceLevel.Normal
    return when {
        f >= 0.95f -> SpaceLevel.Critical
        f >= 0.85f -> SpaceLevel.Warning
        else -> SpaceLevel.Normal
    }
}

@Composable
private fun SpaceLevel.color(): Color = when (this) {
    SpaceLevel.Normal -> MaterialTheme.colorScheme.primary
    SpaceLevel.Warning -> MaterialTheme.nd.warning
    SpaceLevel.Critical -> MaterialTheme.colorScheme.error
}

/** The used share as a bar; nothing at all when the server gave no total (the numbers are shown without a bar). */
@Composable
fun SpaceBar(space: SpaceInfo, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    val fraction = space.usedFraction ?: return
    val tag = LocalLanguageTag.current
    val used = space.used ?: ((space.total ?: 0L) - (space.free ?: 0L))
    val desc = stringResource(Res.string.space_bar_desc, Formatters.size(used, tag), Formatters.size(space.total ?: 0L, tag))
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .semantics { contentDescription = desc },
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceAtLeast(0.01f)).background(space.level().color()))
    }
}

/** Title, bar, the three values the server gave us, a sentence when it is nearly full, and where the numbers come from. */
@Composable
fun SpaceDetails(space: SpaceInfo, onRefresh: () -> Unit, modifier: Modifier = Modifier, headline: Boolean = false) {
    val tag = LocalLanguageTag.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.space_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            NdIconButton(Icons.Outlined.Refresh, stringResource(Res.string.action_refresh), onRefresh)
        }
        if (headline && space.free != null) {
            Text(stringResource(Res.string.space_chip, Formatters.size(space.free, tag)), style = MaterialTheme.typography.headlineSmall)
        }
        SpaceBar(space, Modifier.fillMaxWidth(), height = 10.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            space.used?.let { SpaceValue(Formatters.size(it, tag), stringResource(Res.string.space_used)) }
            space.free?.let { SpaceValue(Formatters.size(it, tag), stringResource(Res.string.space_free)) }
            space.total?.let { SpaceValue(Formatters.size(it, tag), stringResource(Res.string.space_total)) }
        }
        val level = space.level()
        if (level != SpaceLevel.Normal) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.WarningAmber, null, Modifier.size(18.dp), tint = level.color())
                Text(
                    stringResource(if (level == SpaceLevel.Critical) Res.string.space_critical else Res.string.space_nearly_full),
                    style = MaterialTheme.typography.bodySmall, color = level.color(),
                )
            }
        }
        Text(stringResource(Res.string.space_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SpaceValue(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleSmall)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Desktop status bar: icon, a small bar and "1,2 TB free"; a click opens the details in a popover. With [short] (narrow
 * window) only the icon and the bar stay. Shown only when the server told us the space.
 */
@Composable
fun SpaceChip(space: SpaceInfo?, onRefresh: () -> Unit, short: Boolean) {
    if (space == null) return
    val tag = LocalLanguageTag.current
    var open by remember { mutableStateOf(false) }
    val level = space.level()
    val color = if (level == SpaceLevel.Normal) MaterialTheme.colorScheme.onSurfaceVariant else level.color()
    val text = space.free?.let { stringResource(Res.string.space_chip, Formatters.size(it, tag)) }
        ?: space.used?.let { stringResource(Res.string.space_used) + " " + Formatters.size(it, tag) }
        ?: return
    Box {
        Row(
            Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.space_title)) { open = true }
                .heightIn(min = 28.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (level == SpaceLevel.Normal) Icons.Outlined.DataUsage else Icons.Outlined.WarningAmber, null, Modifier.size(16.dp), tint = color)
            if (space.usedFraction != null) {
                Spacer(Modifier.width(6.dp))
                SpaceBar(space, Modifier.width(56.dp), height = 6.dp)
            }
            if (!short) {
                Spacer(Modifier.width(6.dp))
                Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
            }
        }
        DropdownMenu(open, { open = false }) {
            SpaceDetails(space, onRefresh = onRefresh, modifier = Modifier.width(280.dp).padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}

/** Phone: the same details in a bottom sheet (opened from the overflow menu). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceSheet(space: SpaceInfo, onRefresh: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SpaceDetails(space, onRefresh = onRefresh, headline = true, modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 32.dp))
    }
}
