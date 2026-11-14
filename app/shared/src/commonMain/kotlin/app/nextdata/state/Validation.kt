package app.nextdata.state

import app.nextdata.protocol.ProtocolId

/** Why a profile field is not acceptable (the UI maps each to a localized hint). */
enum class FieldIssue {
    Required, HostHasScheme, HostHasPath, HostInvalid, PortInvalid, BucketInvalid, NameTooLong, BasePathRelative
}

/** Real-time, side-effect-free validation of the profile form. The engine remains the authority. */
object ProfileValidation {
    fun name(v: String): FieldIssue? = when {
        v.isBlank() -> FieldIssue.Required
        v.trim().length > 60 -> FieldIssue.NameTooLong
        else -> null
    }

    /** [required] is false for the optional S3 endpoint. */
    fun host(v: String, required: Boolean = true): FieldIssue? {
        val h = v.trim()
        if (h.isEmpty()) return if (required) FieldIssue.Required else null
        if (h.contains("://")) return FieldIssue.HostHasScheme
        if (h.contains('/') || h.contains('?') || h.contains('#')) return FieldIssue.HostHasPath
        if (h.any { it.isWhitespace() }) return FieldIssue.HostInvalid
        if (h.startsWith("[")) return if (h.endsWith("]") && h.length > 2) null else FieldIssue.HostInvalid
        if (h.count { it == ':' } > 1) return if (h.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) null else FieldIssue.HostInvalid
        if (h.contains(':')) return FieldIssue.HostInvalid // "host:port" belongs in the port field
        if (h.all { it.isDigit() || it == '.' }) {
            val parts = h.split('.')
            return if (parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.toInt() in 0..255 }) null else FieldIssue.HostInvalid
        }
        val labels = h.trimEnd('.').split('.')
        val ok = labels.all { l ->
            l.isNotEmpty() && l.length <= 63 && !l.startsWith('-') && !l.endsWith('-') &&
                l.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        }
        return if (ok && h.length <= 253) null else FieldIssue.HostInvalid
    }

    fun port(v: String): FieldIssue? {
        if (v.isEmpty()) return null
        val p = v.toIntOrNull() ?: return FieldIssue.PortInvalid
        return if (p in 1..65535) null else FieldIssue.PortInvalid
    }

    /** S3 bucket naming rules (3-63, lowercase letters/digits/dots/hyphens, alphanumeric ends). */
    fun bucket(v: String): FieldIssue? {
        val b = v.trim()
        if (b.isEmpty()) return FieldIssue.Required
        val ok = b.length in 3..63 && b.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' } &&
            b.first().isLetterOrDigit() && b.last().isLetterOrDigit() && !b.contains("..")
        return if (ok) null else FieldIssue.BucketInvalid
    }

    fun basePath(v: String): FieldIssue? = null

    /** Default port shown as a hint for [protocol] and TLS choice (0 = engine decides). */
    fun defaultPortHint(protocol: String, tls: Boolean): Int = when (protocol) {
        ProtocolId.SFTP -> 22
        ProtocolId.WEBDAV -> if (tls) 443 else 80
        ProtocolId.S3 -> if (tls) 443 else 80
        ProtocolId.SMB -> 445
        else -> 0
    }

    /** Result of splitting a pasted URL into the profile fields. */
    data class UrlParts(val host: String, val port: Int?, val tls: Boolean?, val path: String)

    /** "https://files.example.com:8443/remote.php/dav" -> host, port, tls, path (null when [text] is not a URL). */
    fun splitUrl(text: String): UrlParts? {
        val t = text.trim()
        val schemeEnd = t.indexOf("://")
        val scheme = if (schemeEnd > 0) t.substring(0, schemeEnd).lowercase() else null
        val rest = if (schemeEnd > 0) t.substring(schemeEnd + 3) else t
        if (scheme == null && !rest.contains('/')) return null
        val slash = rest.indexOf('/')
        val authority = (if (slash >= 0) rest.substring(0, slash) else rest).substringAfterLast('@')
        val path = if (slash >= 0) rest.substring(slash) else ""
        val hostPart: String
        var port: Int? = null
        if (authority.startsWith("[")) {
            hostPart = authority.substringBefore(']') + "]"
            port = authority.substringAfter("]:", "").toIntOrNull()
        } else if (authority.count { it == ':' } == 1) {
            hostPart = authority.substringBefore(':')
            port = authority.substringAfter(':').toIntOrNull()
        } else hostPart = authority
        if (hostPart.isBlank()) return null
        val tls = when (scheme) { "https" -> true; "http" -> false; else -> null }
        return UrlParts(hostPart, port, tls, path.trimEnd('?', '#'))
    }
}
