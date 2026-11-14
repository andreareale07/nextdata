package app.nextdata

import androidx.compose.ui.graphics.Color
import app.nextdata.ui.DarkColors
import app.nextdata.ui.DarkNd
import app.nextdata.ui.LightColors
import app.nextdata.ui.LightNd
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/** WCAG 2.2 contrast guard (1.4.3 text >= 4.5:1, 1.4.11 UI components >= 3:1) for both themes. */
class ContrastTest {
    private fun lum(c: Color): Double {
        fun ch(v: Float): Double = v.toDouble().let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    private fun ratio(a: Color, b: Color): Double {
        val l1 = lum(a); val l2 = lum(b)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private fun text(name: String, fg: Color, bg: Color, min: Double = 4.5) =
        assertTrue(ratio(fg, bg) >= min, "$name contrast ${ratio(fg, bg)} < $min")

    @Test
    fun materialPairsMeetAA() {
        listOf("light" to LightColors, "dark" to DarkColors).forEach { (n, c) ->
            text("$n onSurface/surface", c.onSurface, c.surface)
            text("$n onSurfaceVariant/surface", c.onSurfaceVariant, c.surface)
            text("$n onSurfaceVariant/surfaceContainerLow", c.onSurfaceVariant, c.surfaceContainerLow)
            text("$n onSurfaceVariant/surfaceContainerHigh", c.onSurfaceVariant, c.surfaceContainerHigh)
            text("$n onPrimary/primary", c.onPrimary, c.primary)
            text("$n onPrimaryContainer/primaryContainer", c.onPrimaryContainer, c.primaryContainer)
            text("$n primary/surface (links, active labels)", c.primary, c.surface)
            text("$n onError/error", c.onError, c.error)
            text("$n onErrorContainer/errorContainer", c.onErrorContainer, c.errorContainer)
            text("$n error/surface", c.error, c.surface)
            text("$n tertiary/surface (favourite star)", c.tertiary, c.surface, 3.0)
            text("$n outline/surface (input borders, 1.4.11)", c.outline, c.surface, 3.0)
            text("$n onSecondaryContainer/secondaryContainer", c.onSecondaryContainer, c.secondaryContainer)
        }
    }

    @Test
    fun semanticStatusPairsMeetAA() {
        listOf("light" to (LightNd to LightColors), "dark" to (DarkNd to DarkColors)).forEach { (n, p) ->
            val (nd, m) = p
            text("$n success/surface", nd.success, m.surface)
            text("$n warning/surface", nd.warning, m.surface)
            text("$n info/surface", nd.info, m.surface)
            text("$n onSuccess/success", nd.onSuccess, nd.success)
            text("$n onWarning/warning", nd.onWarning, nd.warning)
            text("$n onInfo/info", nd.onInfo, nd.info)
            text("$n onSuccessContainer", nd.onSuccessContainer, nd.successContainer)
            text("$n onWarningContainer", nd.onWarningContainer, nd.warningContainer)
            text("$n onInfoContainer", nd.onInfoContainer, nd.infoContainer)
        }
    }
}
