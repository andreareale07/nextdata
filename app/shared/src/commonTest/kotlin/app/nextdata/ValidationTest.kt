package app.nextdata

import app.nextdata.protocol.Entry
import app.nextdata.protocol.EntryKind
import app.nextdata.state.BrowserListing
import app.nextdata.state.FieldIssue
import app.nextdata.state.ProfileValidation
import app.nextdata.state.SpeedMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ValidationTest {
    @Test
    fun hostRules() {
        assertNull(ProfileValidation.host("example.com"))
        assertNull(ProfileValidation.host("10.0.2.2"))
        assertNull(ProfileValidation.host("my-nas.local"))
        assertNull(ProfileValidation.host("[::1]"))
        assertNull(ProfileValidation.host("fe80::1"))
        assertEquals(FieldIssue.Required, ProfileValidation.host("  "))
        assertNull(ProfileValidation.host("", required = false))
        assertEquals(FieldIssue.HostHasScheme, ProfileValidation.host("https://example.com"))
        assertEquals(FieldIssue.HostHasPath, ProfileValidation.host("example.com/dav"))
        assertEquals(FieldIssue.HostInvalid, ProfileValidation.host("exa mple.com"))
        assertEquals(FieldIssue.HostInvalid, ProfileValidation.host("300.1.1.1"))
        assertEquals(FieldIssue.HostInvalid, ProfileValidation.host("host:22"))
        assertEquals(FieldIssue.HostInvalid, ProfileValidation.host("-bad.example.com"))
    }

    @Test
    fun splitsPastedUrls() {
        val u = ProfileValidation.splitUrl("https://files.example.com:8443/remote.php/dav")!!
        assertEquals("files.example.com", u.host)
        assertEquals(8443, u.port)
        assertEquals(true, u.tls)
        assertEquals("/remote.php/dav", u.path)
        val v = ProfileValidation.splitUrl("http://10.0.0.5/dav")!!
        assertEquals(false, v.tls); assertEquals(null, v.port)
        assertEquals("example.com", ProfileValidation.splitUrl("example.com/dav")!!.host)
        assertNull(ProfileValidation.splitUrl("example.com"))
    }

    @Test
    fun portAndBucketRules() {
        assertNull(ProfileValidation.port(""))
        assertNull(ProfileValidation.port("22"))
        assertNotNull(ProfileValidation.port("0"))
        assertNotNull(ProfileValidation.port("70000"))
        assertNull(ProfileValidation.bucket("ndbucket"))
        assertNull(ProfileValidation.bucket("my.bucket-01"))
        assertEquals(FieldIssue.BucketInvalid, ProfileValidation.bucket("ab"))
        assertEquals(FieldIssue.BucketInvalid, ProfileValidation.bucket("My_Bucket"))
        assertEquals(FieldIssue.BucketInvalid, ProfileValidation.bucket("-bucket"))
        assertEquals(FieldIssue.Required, ProfileValidation.bucket(""))
    }

    private fun e(name: String, kind: String = EntryKind.FILE, size: Long? = 10) = Entry(name, "r/$name", kind, size, null, name)

    @Test
    fun listingFilterAndSelectionBytes() {
        val all = listOf(e("a.txt"), e("B.TXT"), e(".hidden"), e("dir", EntryKind.DIR, null), e("c.png", size = 100))
        assertEquals(4, BrowserListing.visible(all, false, "").size)
        assertEquals(5, BrowserListing.visible(all, true, "").size)
        assertEquals(listOf("a.txt", "B.TXT"), BrowserListing.visible(all, false, "txt").map { it.name })
        assertEquals(110L, BrowserListing.selectionBytes(all, setOf("r/a.txt", "r/c.png", "r/dir")))
    }

    @Test
    fun durationFormatting() {
        assertEquals("38 s", app.nextdata.state.Formatters.duration(38))
        assertEquals("4 min 05 s", app.nextdata.state.Formatters.duration(245))
        assertEquals("1 h 05 min", app.nextdata.state.Formatters.duration(3900))
    }

    @Test
    fun speedMeterSmoothsAndEstimates() {
        val m = SpeedMeter()
        assertEquals(null to null, m.sample(0, 0, 1000))
        val (bps, eta) = m.sample(1000, 100, 1000)
        assertEquals(100L, bps)
        assertEquals(9L, eta)
        val (bps2, _) = m.sample(2000, 300, 1000)
        // EMA: 0.3*200 + 0.7*100 = 130
        assertEquals(130L, bps2)
        // counter reset (retry) restarts the measurement
        assertEquals(null to null, m.sample(3000, 0, 1000))
    }
}

class BreadcrumbNavTest {
    private val crumbs = listOf(
        app.nextdata.protocol.Breadcrumb("/", "r0"),
        app.nextdata.protocol.Breadcrumb("Documenti", "r1"),
        app.nextdata.protocol.Breadcrumb("2024", "r2"),
    )

    @Test
    fun displayAndResolve() {
        assertEquals("/Documenti/2024", app.nextdata.state.BreadcrumbNav.displayPath(crumbs))
        assertEquals("r1", app.nextdata.state.BreadcrumbNav.resolve("/Documenti", crumbs)?.ref)
        assertEquals("r2", app.nextdata.state.BreadcrumbNav.resolve("/Documenti//2024/", crumbs)?.ref)
        assertEquals("r0", app.nextdata.state.BreadcrumbNav.resolve("/", crumbs)?.ref)
        assertNull(app.nextdata.state.BreadcrumbNav.resolve("/Altro", crumbs))
        assertNull(app.nextdata.state.BreadcrumbNav.resolve("", crumbs))
    }
}
