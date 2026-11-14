package app.nextdata

import app.nextdata.i18n.smbErrorRes
import app.nextdata.protocol.ErrInfo
import app.nextdata.protocol.ErrorCodes
import app.nextdata.protocol.Profile
import app.nextdata.protocol.ProtocolId
import app.nextdata.protocol.SmbMode
import app.nextdata.shared.resources.Res
import app.nextdata.shared.resources.err_smb_dialect
import app.nextdata.shared.resources.err_smb_encryption
import app.nextdata.shared.resources.err_smb_name
import app.nextdata.shared.resources.err_smb_negotiation
import app.nextdata.shared.resources.err_smb_public_host
import app.nextdata.state.FieldIssue
import app.nextdata.state.ProfileValidation
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SmbProfileTest {
    @Test
    fun shareRules() {
        assertNull(ProfileValidation.share("documenti"))
        assertNull(ProfileValidation.share(" Foto 2026 "))
        assertEquals(FieldIssue.Required, ProfileValidation.share("  "))
        assertEquals(FieldIssue.ShareInvalid, ProfileValidation.share("a/b"))
        assertEquals(FieldIssue.ShareInvalid, ProfileValidation.share("a\\b"))
        assertEquals(FieldIssue.ShareInvalid, ProfileValidation.share("a\u0000b"))
        assertEquals(FieldIssue.ShareInvalid, ProfileValidation.share("x".repeat(81)))
    }

    @Test
    fun profileCarriesShareAndModeThroughTheWireFormat() {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val p = Profile(name = "NAS", protocol = ProtocolId.SMB, host = "192.168.1.20", share = "documenti", smbMode = SmbMode.RELAXED)
        val text = json.encodeToString(Profile.serializer(), p)
        assert("\"share\":\"documenti\"" in text) { text }
        assert("\"smb_mode\":\"relaxed\"" in text) { text }
        assertEquals(p, json.decodeFromString(Profile.serializer(), text))
        // profiles of other protocols (engine sends share:"" and smb_mode:"") still decode
        val sftp = json.decodeFromString(Profile.serializer(), """{"name":"s","protocol":"sftp","host":"h","share":"","smb_mode":""}""")
        assertEquals("", sftp.share)
        assertEquals("", sftp.smbMode)
    }

    @Test
    fun smbErrorsHaveSpecificWording() {
        assertEquals(Res.string.err_smb_public_host, smbErrorRes(ErrInfo.of(ErrorCodes.POLICY_DENIED, "rule" to "smb_public_host")))
        assertEquals(Res.string.err_smb_dialect, smbErrorRes(ErrInfo.of(ErrorCodes.POLICY_DENIED, "rule" to "smb_dialect")))
        assertEquals(Res.string.err_smb_negotiation, smbErrorRes(ErrInfo.of(ErrorCodes.POLICY_DENIED, "rule" to "smb_negotiation")))
        assertEquals(Res.string.err_smb_name, smbErrorRes(ErrInfo.of(ErrorCodes.POLICY_DENIED, "rule" to "smb_name")))
        assertEquals(Res.string.err_smb_encryption, smbErrorRes(ErrInfo.of(ErrorCodes.PERMISSION_DENIED, "hint" to "server_requires_encryption")))
        // everything else keeps the generic text
        assertNull(smbErrorRes(ErrInfo.of(ErrorCodes.POLICY_DENIED, "rule" to "cleartext_credentials_public")))
        assertNull(smbErrorRes(ErrInfo.of(ErrorCodes.PERMISSION_DENIED)))
        assertNotNull(smbErrorRes(ErrInfo.of(ErrorCodes.POLICY_DENIED, "rule" to "smb_name")))
    }
}
