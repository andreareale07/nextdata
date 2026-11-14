package app.nextdata.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.nextdata.shared.resources.*
import org.jetbrains.compose.resources.stringResource

/** Icon button with a hover/long-press tooltip and a content description (>= 48 dp target by default). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NdIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color? = null,
    tooltip: String = label,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
            Icon(icon, contentDescription = label, tint = tint ?: LocalContentColor.current)
        }
    }
}

/** Shimmering placeholder fill; static when the OS asks to reduce motion. */
@Composable
fun Modifier.shimmer(): Modifier {
    val reduce = LocalReduceMotion.current
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val hi = MaterialTheme.colorScheme.surfaceContainerHighest
    if (reduce) return this.background(base)
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "x")
    return this.background(
        Brush.linearGradient(
            colors = listOf(base, hi, base),
            start = Offset(x * 1200f - 400f, 0f),
            end = Offset(x * 1200f, 200f),
        ),
    )
}

/** Skeleton rows shown while a folder loads (announced as busy to assistive tech). */
@Composable
fun SkeletonList(modifier: Modifier = Modifier, rows: Int = 9) {
    val spec = LocalDensitySpec.current
    val desc = stringResource(Res.string.loading)
    Column(modifier.fillMaxWidth().semantics { contentDescription = desc; liveRegion = LiveRegionMode.Polite }) {
        repeat(rows) { i ->
            Row(Modifier.fillMaxWidth().height(spec.rowHeight).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(spec.iconSize).clip(RoundedCornerShape(6.dp)).shimmer())
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.fillMaxWidth(if (i % 3 == 0) 0.55f else if (i % 3 == 1) 0.4f else 0.7f).height(12.dp).clip(RoundedCornerShape(4.dp)).shimmer())
                    if (!spec.compact) Box(Modifier.fillMaxWidth(0.25f).height(10.dp).clip(RoundedCornerShape(4.dp)).shimmer())
                }
            }
        }
    }
}

@Composable
fun SkeletonGrid(modifier: Modifier = Modifier) {
    val spec = LocalDensitySpec.current
    FlowRow(modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(12) {
            Box(Modifier.size(spec.gridCell, spec.gridCell * 0.95f).clip(RoundedCornerShape(12.dp)).shimmer())
        }
    }
}

private fun Modifier.size(w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp) = this.width(w).height(h)

enum class BannerKind { Info, Warning, Error, Success }

/** Non-blocking inline banner (offline, partial failure...). Announced politely to screen readers. */
@Composable
fun StatusBanner(
    kind: BannerKind,
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    dismissLabel: String = "",
) {
    val nd = MaterialTheme.nd
    val (bg, fg, icon) = when (kind) {
        BannerKind.Info -> Triple(nd.infoContainer, nd.onInfoContainer, Icons.Outlined.Info)
        BannerKind.Warning -> Triple(nd.warningContainer, nd.onWarningContainer, Icons.Outlined.WarningAmber)
        BannerKind.Error -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, Icons.Outlined.ErrorOutline)
        BannerKind.Success -> Triple(nd.successContainer, nd.onSuccessContainer, Icons.Outlined.CheckCircle)
    }
    Surface(color = bg, contentColor = fg, modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel, fontWeight = FontWeight.SemiBold, color = fg) }
            }
            if (onDismiss != null) {
                NdIconButton(Icons.Outlined.Close, dismissLabel, onDismiss)
            }
        }
    }
}

/** Visible keyboard-focus ring (WCAG 2.4.7 / 2.4.11: >= 2 px, >= 3:1 against the surface). */
@Composable
fun Modifier.ndFocusRing(interaction: MutableInteractionSource, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp)): Modifier {
    val focused by interaction.collectIsFocusedAsState()
    return if (focused) this.border(2.dp, MaterialTheme.colorScheme.primary, shape) else this
}

/**
 * Right-click (secondary button) handler for pointer devices; [onClick] gets the position inside this
 * element. Does not consume the event, so primary clicks keep working.
 */
fun Modifier.onSecondaryClick(onClick: (Offset) -> Unit): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press && event.buttons.isSecondaryPressed) {
                val change = event.changes.firstOrNull() ?: continue
                onClick(change.position)
                event.changes.forEach { it.consume() }
            }
        }
    }
}

/** Fingerprint split into fixed-size blocks (monospace); blocks that differ from [other] are highlighted. */
@Composable
fun FingerprintBlocks(value: String, other: String? = null, modifier: Modifier = Modifier, blockSize: Int = 8) {
    val prefix = value.substringBefore(':', "").let { if (value.contains(':')) "$it:" else "" }
    val body = value.removePrefix(prefix)
    val otherBody = other?.let { o -> o.removePrefix(o.substringBefore(':', "").let { p -> if (o.contains(':')) "$p:" else "" }) }
    val blocks = body.chunked(blockSize)
    val danger = MaterialTheme.colorScheme.error
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (prefix.isNotEmpty()) Text(prefix, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        blocks.forEachIndexed { i, b ->
            val differs = otherBody != null && otherBody.chunked(blockSize).getOrNull(i) != b
            Text(
                b,
                style = MonoStyle,
                fontWeight = if (differs) FontWeight.Bold else FontWeight.Normal,
                color = if (differs) danger else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (differs) danger.copy(alpha = 0.14f) else Color.Transparent)
                    .padding(horizontal = 2.dp),
            )
        }
    }
}

/** Copies [text] to the clipboard. */
@Composable
fun rememberCopyToClipboard(): (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    return remember(clipboard) { { text: String -> clipboard.setText(AnnotatedString(text)) } }
}

@Composable
fun CopyButton(text: String, label: String) {
    val copy = rememberCopyToClipboard()
    NdIconButton(Icons.Outlined.ContentCopy, label, { copy(text) })
}
