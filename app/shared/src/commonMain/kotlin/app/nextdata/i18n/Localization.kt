package app.nextdata.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import app.nextdata.platform.applyAppLocale
import app.nextdata.protocol.ErrInfo
import app.nextdata.protocol.ErrorCodes
import app.nextdata.shared.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** BCP-47 tag ("it" / "en") the UI is currently rendered in; used for number/date formatting. */
val LocalLanguageTag: ProvidableCompositionLocal<String> = compositionLocalOf { "en" }

/**
 * Makes every `stringResource`/`pluralStringResource` resolve in [tag] regardless of the OS language.
 * Compose resources read the platform default locale, so the default is switched and the content is
 * re-created (`key`) when the tag changes: the in-app language switch takes effect immediately.
 */
@Composable
fun ProvideAppLanguage(tag: String, content: @Composable () -> Unit) {
    remember(tag) {
        applyAppLocale(tag)
        tag
    }
    key(tag) {
        CompositionLocalProvider(LocalLanguageTag provides tag) { content() }
    }
}

/** Resource for an engine error code; unknown future codes fall back to a generic message. */
fun errorStringRes(code: String): StringResource = when (code) {
    ErrorCodes.INTERNAL -> Res.string.err_INTERNAL
    ErrorCodes.INVALID_ARGUMENT -> Res.string.err_INVALID_ARGUMENT
    ErrorCodes.NOT_FOUND -> Res.string.err_NOT_FOUND
    ErrorCodes.ALREADY_EXISTS -> Res.string.err_ALREADY_EXISTS
    ErrorCodes.PERMISSION_DENIED -> Res.string.err_PERMISSION_DENIED
    ErrorCodes.AUTH_FAILED -> Res.string.err_AUTH_FAILED
    ErrorCodes.HOST_KEY_REJECTED -> Res.string.err_HOST_KEY_REJECTED
    ErrorCodes.HOST_KEY_CHANGED -> Res.string.err_HOST_KEY_CHANGED
    ErrorCodes.TLS_UNTRUSTED -> Res.string.err_TLS_UNTRUSTED
    ErrorCodes.TLS_ERROR -> Res.string.err_TLS_ERROR
    ErrorCodes.NETWORK -> Res.string.err_NETWORK
    ErrorCodes.TIMEOUT -> Res.string.err_TIMEOUT
    ErrorCodes.UNSUPPORTED -> Res.string.err_UNSUPPORTED
    ErrorCodes.POLICY_DENIED -> Res.string.err_POLICY_DENIED
    ErrorCodes.IO -> Res.string.err_IO
    ErrorCodes.DISK_FULL -> Res.string.err_DISK_FULL
    ErrorCodes.SERVER_ANOMALY -> Res.string.err_SERVER_ANOMALY
    ErrorCodes.LIMIT_EXCEEDED -> Res.string.err_LIMIT_EXCEEDED
    ErrorCodes.IMPORT_INVALID -> Res.string.err_IMPORT_INVALID
    ErrorCodes.PASSPHRASE_INVALID -> Res.string.err_PASSPHRASE_INVALID
    ErrorCodes.NOT_INITIALIZED -> Res.string.err_NOT_INITIALIZED
    ErrorCodes.SESSION_CLOSED -> Res.string.err_SESSION_CLOSED
    ErrorCodes.PROTOCOL_DISABLED -> Res.string.err_PROTOCOL_DISABLED
    else -> Res.string.err_UNKNOWN
}

/** Variant with the item name substituted, for the few codes where it reads better; null if none. */
fun namedErrorStringRes(code: String): StringResource? = when (code) {
    ErrorCodes.NOT_FOUND -> Res.string.err_NOT_FOUND_named
    ErrorCodes.ALREADY_EXISTS -> Res.string.err_ALREADY_EXISTS_named
    ErrorCodes.PERMISSION_DENIED -> Res.string.err_PERMISSION_DENIED_named
    ErrorCodes.INVALID_ARGUMENT -> Res.string.err_INVALID_ARGUMENT_named
    else -> null
}

/** The `name` param, only if it looks like a plain display name (never raw engine diagnostics). */
private fun ErrInfo.displayName(): String? = param("name")?.takeIf { it.isNotBlank() && it.length <= 120 }

/** Localized, user-facing text for an engine error. Only `code` + `params.name` are used. */
@Composable
fun errorText(err: ErrInfo): String {
    smbErrorRes(err)?.let { return stringResource(it) }
    val name = err.displayName()
    val named = namedErrorStringRes(err.code)
    return if (named != null && name != null) stringResource(named, name) else stringResource(errorStringRes(err.code))
}

/** SMB-specific wording for policy refusals and encryption mismatches (engine `params.rule` / `params.hint`); null if not SMB. */
fun smbErrorRes(err: ErrInfo): StringResource? = when {
    err.code == ErrorCodes.POLICY_DENIED -> when (err.param("rule")) {
        "smb_public_host" -> Res.string.err_smb_public_host
        "smb_dialect" -> Res.string.err_smb_dialect
        "smb_negotiation" -> Res.string.err_smb_negotiation
        "smb_name" -> Res.string.err_smb_name
        else -> null
    }
    err.code == ErrorCodes.PERMISSION_DENIED && err.param("hint") == "server_requires_encryption" -> Res.string.err_smb_encryption
    else -> null
}

/** Reason shown for a protocol the engine reports as disabled (`protocols[].reason_code`). */
@Composable
fun protocolReasonText(reasonCode: String?): String {
    if (reasonCode == "SMB_GATE_NOT_PASSED") return stringResource(Res.string.reason_smb_gate)
    val known = reasonCode != null && reasonCode in ErrorCodes.all
    return if (known) stringResource(errorStringRes(reasonCode!!)) else stringResource(Res.string.protocol_unavailable_generic)
}

fun certReasonRes(reason: String): StringResource = when (reason) {
    "self_signed" -> Res.string.cert_reason_self_signed
    "expired" -> Res.string.cert_reason_expired
    "hostname_mismatch" -> Res.string.cert_reason_hostname_mismatch
    "unknown_issuer" -> Res.string.cert_reason_unknown_issuer
    else -> Res.string.cert_reason_generic
}
