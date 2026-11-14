package app.nextdata.platform

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Captured when this class loads (before the app ever calls [applyAppLocale]). */
private val originalSystemLocale: Locale = Locale.getDefault()

actual fun formatDecimal(value: Double, fractionDigits: Int, languageTag: String): String {
    val nf = NumberFormat.getNumberInstance(Locale.forLanguageTag(languageTag))
    nf.minimumFractionDigits = 0
    nf.maximumFractionDigits = fractionDigits
    return nf.format(value)
}

actual fun formatDateTimeMs(epochMs: Long, languageTag: String): String {
    val f = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(Locale.forLanguageTag(languageTag))
        .withZone(ZoneId.systemDefault())
    return f.format(Instant.ofEpochMilli(epochMs))
}

actual fun systemLanguageTag(): String = originalSystemLocale.toLanguageTag()

actual fun applyAppLocale(languageTag: String) {
    Locale.setDefault(Locale.forLanguageTag(languageTag))
}
