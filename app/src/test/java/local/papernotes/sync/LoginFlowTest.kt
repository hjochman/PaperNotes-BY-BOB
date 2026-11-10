package local.papernotes.sync

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LoginFlowTest {
    private lateinit var server: MockWebServer
    private lateinit var flow: LoginFlow
    private lateinit var base: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        base = server.url("/nextcloud").toString().trimEnd('/')
        flow = LoginFlow(OkHttpClient.Builder().followRedirects(false).build(), base)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun json(code: Int, body: String) = MockResponse.Builder().code(code).body(body).build()

    @Test
    fun startPostsToLoginV2AndReadsSession() {
        val poll = "https://${server.hostName}:${server.port}/nextcloud/login/v2/poll"
        val login = "https://${server.hostName}:${server.port}/nextcloud/login/v2/flow/abc"
        server.enqueue(json(200, """{"poll":{"token":"T0K","endpoint":"$poll"},"login":"$login"}"""))
        // parseStart prüft https + gleicher Host; start() selbst spricht im Test Klartext mit dem Mock-Server.
        val r = server.takeRequestAfter { flow.start() }
        assertEquals("POST", r.method)
        assertEquals("/nextcloud/index.php/login/v2", r.url.encodedPath)
    }

    @Test
    fun acceptsOnlySameHostHttpsAddresses() {
        val h = "${server.hostName}:${server.port}"
        val ok = """{"poll":{"token":"t","endpoint":"https://$h/p"},"login":"https://$h/l"}"""
        assertNotNull(flow.parseStart(ok))
        assertNull(flow.parseStart("""{"poll":{"token":"t","endpoint":"http://$h/p"},"login":"https://$h/l"}"""))
        assertNull(flow.parseStart("""{"poll":{"token":"t","endpoint":"https://evil.example/p"},"login":"https://$h/l"}"""))
        assertNull(flow.parseStart("""{"poll":{"token":"t","endpoint":"https://$h/p"},"login":"https://evil.example/l"}"""))
        assertNull(flow.parseStart("""{"poll":{"token":"","endpoint":"https://$h/p"},"login":"https://$h/l"}"""))
        assertNull(flow.parseStart("""{"poll":{},"login":"x"}"""))
        assertNull(flow.parseStart("kein json"))
    }

    @Test
    fun pollReturnsNullUntilConfirmedThenCredentials() {
        val s = LoginSession("https://x/l", server.url("/poll").toString(), "TOK")
        server.enqueue(json(404, ""))
        assertNull(flow.poll(s))
        server.enqueue(json(200, """{"server":"https://x","loginName":"hanspeter","appPassword":"geheim"}"""))
        val r = flow.poll(s)!!
        assertEquals("hanspeter", r.loginName)
        assertEquals("geheim", r.appPassword)
        assertFalse(r.toString().contains("geheim"))
        assertEquals("token=TOK", server.takeRequest().body!!.utf8())
    }

    @Test
    fun brokenOrErrorAnswersThrow() {
        val s = LoginSession("https://x/l", server.url("/poll").toString(), "TOK")
        server.enqueue(json(200, "{}"))
        assertTrue(runCatching { flow.poll(s) }.exceptionOrNull() is RemoteException)
        server.enqueue(json(500, ""))
        assertEquals(RemoteException.Kind.SERVER, (runCatching { flow.poll(s) }.exceptionOrNull() as RemoteException).kind)
    }

    private fun <T> MockWebServer.takeRequestAfter(block: () -> T): mockwebserver3.RecordedRequest {
        runCatching { block() }
        return takeRequest()
    }
}
