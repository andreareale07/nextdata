package app.nextdata

import app.nextdata.protocol.Codec
import app.nextdata.protocol.EngineEvent
import app.nextdata.protocol.EntryKind
import app.nextdata.protocol.ErrorCodes
import app.nextdata.protocol.ListResult
import app.nextdata.protocol.Profile
import app.nextdata.protocol.ProfileSecrets
import app.nextdata.protocol.Prompt
import app.nextdata.protocol.S3Config
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodecTest {
    @Test
    fun commandHasOpCmdArgs() {
        val line = Codec.encodeCommand(7, "session.list", buildJsonObject { put("session", "s1"); put("desc", true) })
        val o = Codec.json.parseToJsonElement(line).jsonObject
        assertEquals(7, o["op"]!!.jsonPrimitive.long)
        assertEquals("session.list", o["cmd"]!!.jsonPrimitive.content)
        assertEquals("s1", o["args"]!!.jsonObject["session"]!!.jsonPrimitive.content)
        assertTrue(o["args"]!!.jsonObject["desc"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun commandRejectsZeroOp() {
        assertFailsWith<IllegalArgumentException> { Codec.encodeCommand(0, "init") }
    }

    @Test
    fun decodesOk() {
        val ev = Codec.decodeEvent("""{"op":3,"ev":"ok","data":{"deleted":2}}""")
        assertIs<EngineEvent.Ok>(ev)
        assertEquals(3, ev.op)
        assertEquals(2, ev.data["deleted"]!!.jsonPrimitive.int)
    }

    @Test
    fun decodesErrWithParamsAndRetryable() {
        val ev = Codec.decodeEvent("""{"op":4,"ev":"err","err":{"code":"NOT_FOUND","params":{"name":"a.txt","detail":"x"},"retryable":true}}""")
        assertIs<EngineEvent.Err>(ev)
        assertEquals(ErrorCodes.NOT_FOUND, ev.error.code)
        assertEquals("a.txt", ev.error.param("name"))
        assertTrue(ev.error.retryable)
    }

    @Test
    fun unknownErrorCodeIsPreserved() {
        val ev = Codec.decodeEvent("""{"op":4,"ev":"err","err":{"code":"BRAND_NEW"}}""") as EngineEvent.Err
        assertEquals("BRAND_NEW", ev.error.code)
        assertEquals(false, ev.error.retryable)
    }

    @Test
    fun decodesCancelledAndProgress() {
        assertIs<EngineEvent.Cancelled>(Codec.decodeEvent("""{"op":9,"ev":"cancelled"}"""))
        val p = Codec.decodeEvent("""{"op":9,"ev":"progress","data":{"bytes":50,"total":200,"item":"a.bin","items_done":1,"items_total":3}}""")
        assertIs<EngineEvent.Progress>(p)
        assertEquals(50, p.progress.bytes)
        assertEquals(0.25f, p.progress.fraction)
        assertEquals("a.bin", p.progress.item)
        assertEquals(3, p.progress.itemsTotal)
    }

    @Test
    fun progressWithNullTotalHasNoFraction() {
        val p = Codec.decodeEvent("""{"op":9,"ev":"progress","data":{"bytes":50,"total":null,"item":null,"items_done":0,"items_total":0}}""") as EngineEvent.Progress
        assertNull(p.progress.total)
        assertNull(p.progress.fraction)
    }

    @Test
    fun decodesTrustHostPromptNewAndChanged() {
        val first = Codec.decodeEvent(
            """{"op":1,"ev":"prompt","data":{"prompt_id":"p1","kind":"trust_host","host":"h","port":22,"algo":"ssh-ed25519","fingerprint":"SHA256:abc","prior":null}}""",
        ) as EngineEvent.PromptRequest
        val tp = first.prompt as Prompt.TrustHost
        assertNull(tp.prior)
        assertEquals("p1", tp.promptId)
        assertEquals(22, tp.port)
        val changed = Codec.decodeEvent(
            """{"op":1,"ev":"prompt","data":{"prompt_id":"p2","kind":"trust_host","host":"h","port":22,"algo":"a","fingerprint":"SHA256:new","prior":"SHA256:old"}}""",
        ) as EngineEvent.PromptRequest
        assertEquals("SHA256:old", (changed.prompt as Prompt.TrustHost).prior)
    }

    @Test
    fun decodesTrustCertAndSecretPrompts() {
        val cert = (Codec.decodeEvent(
            """{"op":1,"ev":"prompt","data":{"prompt_id":"p3","kind":"trust_cert","host":"h","port":443,"spki_sha256":"ff","subject":"CN=h","self_signed":true,"reason":"self_signed"}}""",
        ) as EngineEvent.PromptRequest).prompt as Prompt.TrustCert
        assertTrue(cert.selfSigned)
        assertEquals("ff", cert.spkiSha256)
        val pw = (Codec.decodeEvent(
            """{"op":1,"ev":"prompt","data":{"prompt_id":"p4","kind":"passphrase","profile_name":"NAS"}}""",
        ) as EngineEvent.PromptRequest).prompt as Prompt.Secret
        assertEquals("passphrase", pw.kind)
        assertEquals("NAS", pw.profileName)
        val unknown = (Codec.decodeEvent(
            """{"op":1,"ev":"prompt","data":{"prompt_id":"p5","kind":"mystery"}}""",
        ) as EngineEvent.PromptRequest).prompt
        assertIs<Prompt.Unknown>(unknown)
    }

    @Test
    fun malformedLinesAreIgnored() {
        assertNull(Codec.decodeEvent("not json"))
        assertNull(Codec.decodeEvent("""{"ev":"ok"}"""))
        assertNull(Codec.decodeEvent("""{"op":1}"""))
        assertNull(Codec.decodeEvent("""{"op":1,"ev":"weird"}"""))
        assertNull(Codec.decodeEvent("[1,2]"))
    }

    @Test
    fun profileUsesSnakeCaseAndOmitsNulls() {
        val p = Profile("", "S3", "s3", "h", 0, "", "none", "", true, S3Config("b", "eu-west-1", "AK", true), false, "#112233")
        val o = Codec.json.encodeToJsonElement(Profile.serializer(), p).jsonObject
        assertEquals("", o["base_path"]!!.jsonPrimitive.content)
        assertEquals(false, o["has_secret"]!!.jsonPrimitive.boolean)
        val s3 = o["s3"]!!.jsonObject
        assertEquals("AK", s3["access_key"]!!.jsonPrimitive.content)
        assertTrue(s3["path_style"]!!.jsonPrimitive.boolean)
        val sftp = Codec.json.encodeToJsonElement(Profile.serializer(), p.copy(s3 = null, color = null)).jsonObject
        assertTrue("s3" !in sftp)
        assertTrue("color" !in sftp)
        val back = Codec.json.decodeFromJsonElement(Profile.serializer(), o)
        assertEquals(p, back)
    }

    @Test
    fun secretsEncodeOnlyPresentFieldsWithSnakeCase() {
        val o = Codec.json.encodeToJsonElement(ProfileSecrets.serializer(), ProfileSecrets(privateKey = "K", passphrase = "P")).jsonObject
        assertEquals("K", o["private_key"]!!.jsonPrimitive.content)
        assertEquals("P", o["passphrase"]!!.jsonPrimitive.content)
        assertTrue("password" !in o)
        assertTrue("secret_key" !in o)
    }

    @Test
    fun listResultDecodesNullableFields() {
        val json = """{"ref":"r1","parent_ref":null,"breadcrumbs":[{"name":"/","ref":"r1"}],
            "entries":[{"name":"a","ref":"r2","kind":"dir","size":null,"mtime_ms":null,"safe_local_name":"a"},
                       {"name":"b.txt","ref":"r3","kind":"file","size":12,"mtime_ms":1700000000000,"safe_local_name":"b.txt"}],
            "next_token":"t2","total_hint":300,"future_field":1}"""
        val r = Codec.json.decodeFromString(ListResult.serializer(), json)
        assertNull(r.parentRef)
        assertEquals("t2", r.nextToken)
        assertEquals(300, r.totalHint)
        assertEquals(EntryKind.DIR, r.entries[0].kind)
        assertNull(r.entries[0].size)
        assertEquals(12L, r.entries[1].size)
        assertEquals(1, r.breadcrumbs.size)
    }

    @Test
    fun promptReplyArgs() {
        val o: JsonObject = Codec.promptReplyArgs("p1", true, null, replace = true)
        assertEquals("p1", o["prompt_id"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), o["accept"])
        assertEquals(JsonPrimitive(true), o["replace"])
        assertNull(o["value"])
        val plain = Codec.promptReplyArgs("p2", false)
        assertNull(plain["replace"])
        assertNotNull(plain["accept"])
    }
}
