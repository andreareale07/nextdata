package app.nextdata.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

/** Ink-slate neutrals with a teal accent. */
object Ink {
    val Slate50 = Color(0xFFF8FAFC)
    val Slate100 = Color(0xFFF1F5F9)
    val Slate200 = Color(0xFFE2E8F0)
    val Slate300 = Color(0xFFCBD5E1)
    val Slate400 = Color(0xFF94A3B8)
    val Slate500 = Color(0xFF64748B)
    val Slate600 = Color(0xFF475569)
    val Slate700 = Color(0xFF334155)
    val Slate800 = Color(0xFF1E293B)
    val Slate900 = Color(0xFF0F172A)
    val Slate950 = Color(0xFF0B1220)

    val Teal700 = Color(0xFF0F766E)
    val Teal600 = Color(0xFF0D9488)
    val Teal400 = Color(0xFF2DD4BF)
    val Teal300 = Color(0xFF5EEAD4)
    val Teal100 = Color(0xFFCCFBF1)
    val Teal900 = Color(0xFF134E4A)
    val Teal950 = Color(0xFF042F2E)

    val Red700 = Color(0xFFB91C1C)
    val Red100 = Color(0xFFFEE2E2)
    val Red300 = Color(0xFFFCA5A5)
    val Red400 = Color(0xFFF87171)
    val Red900 = Color(0xFF7F1D1D)
    val Amber700 = Color(0xFFB45309)
    val Amber300 = Color(0xFFFCD34D)
    val Amber100 = Color(0xFFFEF3C7)
    val Amber900 = Color(0xFF78350F)
    val Green700 = Color(0xFF15803D)
    val Green100 = Color(0xFFDCFCE7)
    val Green900 = Color(0xFF14532D)
    val Green400 = Color(0xFF4ADE80)
    val Blue700 = Color(0xFF1D4ED8)
    val Blue100 = Color(0xFFDBEAFE)
    val Blue900 = Color(0xFF1E3A8A)
    val Blue300 = Color(0xFF93C5FD)
}

/**
 * Semantic status colours that Material's scheme has no slot for. Every pair meets WCAG AA (>= 4.5:1)
 * for text on its container; status is always also conveyed by an icon and a label, never colour alone.
 */
@androidx.compose.runtime.Immutable
class NdColors(
    val success: Color, val onSuccess: Color, val successContainer: Color, val onSuccessContainer: Color,
    val warning: Color, val onWarning: Color, val warningContainer: Color, val onWarningContainer: Color,
    val info: Color, val onInfo: Color, val infoContainer: Color, val onInfoContainer: Color,
)

internal val LightNd = NdColors(
    Ink.Green700, Color.White, Ink.Green100, Ink.Green900,
    Ink.Amber700, Color.White, Ink.Amber100, Ink.Amber900,
    Ink.Blue700, Color.White, Ink.Blue100, Ink.Blue900,
)
internal val DarkNd = NdColors(
    Ink.Green400, Color(0xFF052E16), Color(0xFF14391F), Color(0xFFBBF7D0),
    Ink.Amber300, Color(0xFF3B1D04), Color(0xFF422A08), Color(0xFFFDE68A),
    Ink.Blue300, Color(0xFF0B1B45), Color(0xFF172A5C), Color(0xFFBFDBFE),
)

val LocalNdColors = androidx.compose.runtime.staticCompositionLocalOf { LightNd }

/** 4/8 dp grid. */
object Space {
    val x1 = 4.dp
    val x2 = 8.dp
    val x3 = 12.dp
    val x4 = 16.dp
    val x6 = 24.dp
    val x8 = 32.dp
}

/** Row geometry for the two densities (touch targets stay >= 48 dp on comfortable, >= 32 dp on compact). */
@androidx.compose.runtime.Immutable
class DensitySpec(val rowHeight: androidx.compose.ui.unit.Dp, val iconSize: androidx.compose.ui.unit.Dp, val gridCell: androidx.compose.ui.unit.Dp, val compact: Boolean)

val ComfortableSpec = DensitySpec(60.dp, 28.dp, 128.dp, false)
val CompactSpec = DensitySpec(36.dp, 22.dp, 104.dp, true)
val LocalDensitySpec = androidx.compose.runtime.staticCompositionLocalOf { ComfortableSpec }

/** Honours the OS "remove animations" preference: shimmer stops and transitions become instant. */
val LocalReduceMotion = androidx.compose.runtime.staticCompositionLocalOf { false }

/** Duration helper: 0 when motion is reduced. */
@Composable
fun motionMs(ms: Int): Int = if (LocalReduceMotion.current) 0 else ms

internal val LightColors: ColorScheme = lightColorScheme(
    primary = Ink.Teal700,
    onPrimary = Color.White,
    primaryContainer = Ink.Teal100,
    onPrimaryContainer = Ink.Teal900,
    secondary = Ink.Slate600,
    onSecondary = Color.White,
    secondaryContainer = Ink.Slate200,
    onSecondaryContainer = Ink.Slate800,
    tertiary = Ink.Amber700,
    background = Ink.Slate50,
    onBackground = Ink.Slate900,
    surface = Ink.Slate50,
    onSurface = Ink.Slate900,
    surfaceVariant = Ink.Slate100,
    onSurfaceVariant = Ink.Slate600,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF4F7FA),
    surfaceContainer = Ink.Slate100,
    surfaceContainerHigh = Ink.Slate200,
    surfaceContainerHighest = Ink.Slate200,
    outline = Ink.Slate500,
    outlineVariant = Ink.Slate300,
    error = Ink.Red700,
    onError = Color.White,
    errorContainer = Ink.Red100,
    onErrorContainer = Ink.Red900,
    scrim = Color.Black,
)

internal val DarkColors: ColorScheme = darkColorScheme(
    primary = Ink.Teal400,
    onPrimary = Ink.Teal950,
    primaryContainer = Ink.Teal900,
    onPrimaryContainer = Color(0xFF99F6E4),
    secondary = Ink.Slate300,
    onSecondary = Ink.Slate900,
    secondaryContainer = Ink.Slate700,
    onSecondaryContainer = Ink.Slate100,
    tertiary = Ink.Amber300,
    background = Ink.Slate950,
    onBackground = Ink.Slate200,
    surface = Ink.Slate950,
    onSurface = Ink.Slate200,
    surfaceVariant = Ink.Slate800,
    onSurfaceVariant = Ink.Slate400,
    surfaceContainerLowest = Color(0xFF080E19),
    surfaceContainerLow = Color(0xFF0F172A),
    surfaceContainer = Color(0xFF131C2E),
    surfaceContainerHigh = Ink.Slate800,
    surfaceContainerHighest = Ink.Slate700,
    outline = Ink.Slate500,
    outlineVariant = Ink.Slate700,
    error = Ink.Red400,
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF5B1A1A),
    onErrorContainer = Ink.Red100,
    scrim = Color.Black,
)

private val base = Typography()

/**
 * Complete type scale (Material 3 roles) tuned for dense data UI: titles are semi-bold, body 14/16 sp,
 * tabular figures for sizes and dates. All sizes are sp so they follow the system font scale up to 200%.
 */
private val NdTypography = Typography(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.SemiBold),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.SemiBold),
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
)

/** Radii: 4 (chips/inputs inline), 8 (buttons/rows), 12 (cards), 16 (sheets/panels), 28 (dialogs). */
private val NdShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Animation durations (ms): everything stays within 120-200 ms with the standard easing. */
object Motion {
    const val Fast = 120
    const val Normal = 160
    const val Slow = 200
}

val MonoStyle = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp, lineBreak = LineBreak.Simple)

@Composable
fun NextDataTheme(
    density: DensitySpec = ComfortableSpec,
    reduceMotion: Boolean = false,
    /** null = follow the system; true/false = forced by the user's theme setting. */
    forceDark: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val dark = forceDark ?: isSystemInDarkTheme()
    androidx.compose.runtime.CompositionLocalProvider(
        // pointer-driven compact density may use smaller targets (>= 24 px, WCAG 2.5.8); touch keeps 48 dp
        androidx.compose.material3.LocalMinimumInteractiveComponentSize provides (if (density.compact) 32.dp else 48.dp),
        LocalNdColors provides (if (dark) DarkNd else LightNd),
        LocalDensitySpec provides density,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = NdTypography,
            shapes = NdShapes,
            content = content,
        )
    }
}

/** Shortcut to the semantic colours: `MaterialTheme.nd.success`. */
val MaterialTheme.nd: NdColors
    @Composable get() = LocalNdColors.current
