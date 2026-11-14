package app.nextdata.state

import app.nextdata.protocol.Profile
import app.nextdata.protocol.Prompt
import app.nextdata.protocol.ProtocolId

/** What the UI can honestly say about the security of the open connection. */
data class SecurityInfo(
    val protocol: String,
    /** The transport is encrypted (SFTP always; WebDAV/S3 only over HTTPS). */
    val encrypted: Boolean,
    /** Host key / certificate details, only known when the user just confirmed them in this connect. */
    val algo: String? = null,
    val fingerprint: String? = null,
    val kind: Kind = Kind.None,
) {
    enum class Kind { None, HostKey, Certificate }

    companion object {
        fun from(profile: Profile, accepted: Prompt?): SecurityInfo {
            val encrypted = when (profile.protocol) {
                ProtocolId.SFTP, ProtocolId.SMB -> true
                ProtocolId.WEBDAV, ProtocolId.S3 -> profile.tls
                else -> false
            }
            return when (accepted) {
                is Prompt.TrustHost -> SecurityInfo(profile.protocol, encrypted, accepted.algo, accepted.fingerprint, Kind.HostKey)
                is Prompt.TrustCert -> SecurityInfo(profile.protocol, encrypted, "SPKI SHA-256", accepted.spkiSha256, Kind.Certificate)
                else -> SecurityInfo(profile.protocol, encrypted)
            }
        }
    }
}
