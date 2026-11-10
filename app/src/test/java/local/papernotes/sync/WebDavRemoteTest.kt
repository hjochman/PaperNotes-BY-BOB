package local.papernotes.sync

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class WebDavRemoteTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var remote: WebDavRemote
    private val a = "11111111-1111-4111-8111-111111111111"
    private val b = "22222222-2222-4222-8222-222222222222"
    private val base = "/nextcloud/remote.php/dav/files/hanspeter"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // Der Produktions-Client erlaubt nur TLS; der Test spricht Klartext mit dem lokalen Mock-Server.
        val client = OkHttpClient.Builder().followRedirects(false).readTimeout(5, TimeUnit.SECONDS).build()
        remote = WebDavRemote(client, server.url(base).toString(), "PaperNotes", WebDavRemote.basicAuth("hanspeter", "geheim"))
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun status(code: Int, body: String = "") = MockResponse.Builder().code(code).body(body).build()

    private fun multistatus(vararg entries: String) =
        """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">${entries.joinToString("")}</d:multistatus>"""

    private fun entry(href: String, collection: Boolean, size: Long = 0, date: String = "Sat, 07 Nov 2026 10:00:00 GMT") =
        """<d:response><d:href>$href</d:href><d:propstat><d:prop>
        <d:resourcetype>${if (collection) "<d:collection/>" else ""}</d:resourcetype>
        ${if (collection) "" else "<d:getcontentlength>$size</d:getcontentlength>"}
        <d:getlastmodified>$date</d:getlastmodified></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""

    private fun kind(block: () -> Unit): RemoteException.Kind {
        try {
            block()
        } catch (e: RemoteException) {
            return e.kind
        }
        fail("RemoteException erwartet")
        throw IllegalStateException()
    }

    @Test
    fun listTreeWalksFoldersAndIgnoresForeignNames() {
        val t = "$base/PaperNotes"
        server.enqueue(
            status(
                207,
                multistatus(
                    entry("$t/", true), entry("$t/Open%20Shift/", true), entry("$t/.hidden/", true),
                    entry("$t/$a.pnote", false, 123), entry("$t/.DS_Store", false, 5), entry("$t/readme.txt", false, 1),
                ),
            ),
        )
        server.enqueue(status(207, multistatus(entry("$t/Open%20Shift/", true), entry("$t/Open%20Shift/$b.pnote", false, 77))))
        val tree = remote.listTree()
        assertEquals(setOf("Open Shift"), tree.folders)
        assertEquals(setOf(a, b), tree.files.keys)
        assertEquals(RemoteFile("", 123, 1_794_045_600_000), tree.files[a])
        assertEquals("Open Shift", tree.files[b]!!.folder)
        val first = server.takeRequest()
        assertEquals("PROPFIND", first.method)
        assertEquals("1", first.headers["Depth"])
        assertTrue(first.headers["Authorization"]!!.startsWith("Basic "))
        assertEquals("$t/", first.url.encodedPath)
        assertEquals("$t/Open%20Shift/", server.takeRequest().url.encodedPath)
    }

    @Test
    fun missingTargetFolderIsAnEmptyTree() {
        server.enqueue(status(404))
        assertEquals(RemoteTree(emptySet(), emptyMap()), remote.listTree())
    }

    @Test
    fun hasChildrenIsFalseForMissingOrEmptyFolder() {
        server.enqueue(status(404))
        assertFalse(remote.hasChildren("X"))
        server.enqueue(status(207, multistatus(entry("$base/PaperNotes/X/", true))))
        assertFalse(remote.hasChildren("X"))
        server.enqueue(status(207, multistatus(entry("$base/PaperNotes/X/", true), entry("$base/PaperNotes/X/.DS_Store", false, 3))))
        assertTrue(remote.hasChildren("X"))
    }

    @Test
    fun rejectsDoctypeAndHugeOrBrokenXml() {
        server.enqueue(status(207, """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e "boom">]><d:multistatus xmlns:d="DAV:"/>"""))
        assertEquals(RemoteException.Kind.PROTOCOL, kind { remote.listTree() })
        server.enqueue(status(207, "<d:multistatus"))
        assertEquals(RemoteException.Kind.PROTOCOL, kind { remote.listTree() })
    }

    @Test
    fun uploadSendsBodyChecksumAndMtimeAndCreatesTargetFirst() {
        val f = tmp.newFile("x.pnote").apply { writeText("hallo"); setLastModified(1_794_132_000_000) }
        server.enqueue(status(201)) // MKCOL PaperNotes
        server.enqueue(status(201)) // PUT
        remote.upload(LocalFile(a, "", f.length(), f.lastModified()), f)
        val mkcol = server.takeRequest()
        assertEquals("MKCOL", mkcol.method)
        assertEquals("$base/PaperNotes", mkcol.url.encodedPath)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("$base/PaperNotes/$a.pnote", put.url.encodedPath)
        assertEquals("1794132000", put.headers["X-OC-Mtime"])
        assertEquals("SHA1:fd4f6b3c6c3b8c1c0a3e55d3a0d2d3b5c0b3b6e1".length, put.headers["OC-Checksum"]!!.length)
        assertEquals("hallo", put.body!!.utf8())
        // Zweiter Upload legt den Zielordner nicht erneut an.
        server.enqueue(status(204))
        remote.upload(LocalFile(a, "Neu Ordner", f.length(), f.lastModified()), f)
        assertEquals("$base/PaperNotes/Neu%20Ordner/$a.pnote", server.takeRequest().url.encodedPath)
    }

    @Test
    fun checksumIsRealSha1() {
        val f = tmp.newFile("y.pnote").apply { writeText("abc") }
        server.enqueue(status(405)); server.enqueue(status(201))
        remote.upload(LocalFile(a, "", 3, f.lastModified()), f)
        server.takeRequest()
        assertEquals("SHA1:a9993e364706816aba3e25717850c26c9cd0d89d", server.takeRequest().headers["OC-Checksum"])
    }

    @Test
    fun moveSendsDestinationAndOverwrite() {
        server.enqueue(status(405)); server.enqueue(status(201))
        remote.move(a, "Alt", "Neu")
        server.takeRequest()
        val mv = server.takeRequest()
        assertEquals("MOVE", mv.method)
        assertEquals("$base/PaperNotes/Alt/$a.pnote", mv.url.encodedPath)
        assertEquals("T", mv.headers["Overwrite"])
        assertTrue(mv.headers["Destination"]!!.endsWith("$base/PaperNotes/Neu/$a.pnote"))
    }

    @Test
    fun deleteTreatsMissingAsDone() {
        server.enqueue(status(404))
        remote.delete(a, "")
        assertEquals("DELETE", server.takeRequest().method)
        server.enqueue(status(204))
        remote.deleteFolder("X")
        assertEquals("$base/PaperNotes/X/", server.takeRequest().url.encodedPath)
    }

    @Test
    fun makeFolderAcceptsExisting() {
        server.enqueue(status(405)); server.enqueue(status(405))
        remote.makeFolder("A")
    }

    @Test
    fun statusCodesMapToKinds() {
        val f = tmp.newFile("z.pnote")
        val lf = LocalFile(a, "", 0, 0)
        server.enqueue(status(405)) // Zielordner
        server.enqueue(status(401))
        assertEquals(RemoteException.Kind.AUTH, kind { remote.upload(lf, f) })
        server.enqueue(status(507))
        assertEquals(RemoteException.Kind.QUOTA, kind { remote.upload(lf, f) })
        server.enqueue(status(409))
        assertEquals(RemoteException.Kind.CONFLICT, kind { remote.upload(lf, f) })
        server.enqueue(status(503))
        assertEquals(RemoteException.Kind.SERVER, kind { remote.upload(lf, f) })
        server.enqueue(status(423))
        assertEquals(RemoteException.Kind.SERVER, kind { remote.upload(lf, f) })
        server.enqueue(status(404))
        assertEquals(RemoteException.Kind.NOT_FOUND, kind { remote.move(a, "", "X") })
    }

    @Test
    fun redirectsAreNotFollowedAndNeverLeakCredentials() {
        val other = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", other.url("/x").toString()).build())
            assertEquals(RemoteException.Kind.PROTOCOL, kind { remote.listTree() })
            assertEquals(0, other.requestCount)
        } finally {
            other.close()
        }
    }

    @Test
    fun connectionFailureIsNetwork() {
        server.close()
        assertEquals(RemoteException.Kind.NETWORK, kind { remote.listTree() })
    }

    @Test
    fun productionClientAllowsOnlyTlsAndNoRedirects() {
        val c = WebDavRemote.newClient()
        assertFalse(c.followRedirects)
        assertFalse(c.followSslRedirects)
        assertTrue(c.connectionSpecs.none { it.isTls.not() })
        assertNotNull(WebDavRemote.basicAuth("u", "p"))
    }

    @Test
    fun noPasswordInErrorMessage() {
        val msg = try {
            server.enqueue(status(401)); remote.listTree(); ""
        } catch (e: RemoteException) {
            e.message.orEmpty()
        }
        assertFalse(msg.contains("geheim"))
        assertFalse(msg.contains("http"))
        assertTrue(File(tmp.root.path).exists())
    }
}
