package local.papernotes.sync

import com.google.gson.JsonParser
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Zugangsdaten nach erfolgreicher Anmeldung. Das App-Passwort wird nie angezeigt oder geloggt. */
class LoginResult(val loginName: String, val appPassword: String) {
    override fun toString() = "LoginResult(loginName=$loginName)"
}

/** Ein gestarteter Anmeldevorgang: [loginUrl] öffnet der Nutzer im Browser, [pollUrl] und [token] fragt die App ab. */
class LoginSession(val loginUrl: String, val pollUrl: String, val token: String)

/**
 * Nextcloud Login Flow v2: Die App erhält ein eigenes App-Passwort, das Hauptpasswort sieht sie nie. Der Nutzer meldet
 * sich im Browser an und bestätigt dort. Alle vom Server genannten Adressen müssen `https` und derselbe Host wie die
 * eingegebene Basisadresse sein; sonst wird abgebrochen (kein Umleiten der Zugangsdaten).
 */
class LoginFlow(private val client: OkHttpClient, baseUrl: String) {
    private val base: HttpUrl = baseUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("url")

    /** Startet den Vorgang. Wirft [RemoteException] bei Netz-, Server- oder Protokollfehlern. */
    fun start(): LoginSession {
        val url = base.newBuilder().addPathSegments("index.php/login/v2").build()
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            .post(ByteArray(0).toRequestBody()).build()
        val text = send(req) { code, body -> if (code == 200) body else throw RemoteException(kindOf(code), code) }
        return parseStart(text) ?: throw RemoteException(RemoteException.Kind.PROTOCOL)
    }

    /** Fragt einmal ab. Null = der Nutzer hat noch nicht bestätigt. */
    fun poll(session: LoginSession): LoginResult? {
        val form = FormBody.Builder().add("token", session.token).build()
        val req = Request.Builder().url(session.pollUrl).header("User-Agent", USER_AGENT).post(form).build()
        val text = send(req) { code, body ->
            when (code) {
                200 -> body
                404 -> null
                else -> throw RemoteException(kindOf(code), code)
            }
        } ?: return null
        return parseResult(text) ?: throw RemoteException(RemoteException.Kind.PROTOCOL)
    }

    /** Wertet die Antwort des Starts aus; null bei unlesbarer Antwort oder wenn eine Adresse nicht zum Server passt. */
    internal fun parseStart(text: String): LoginSession? = try {
        val root = JsonParser.parseString(text).asJsonObject
        val poll = root.getAsJsonObject("poll")
        val token = poll.get("token").asString
        val endpoint = poll.get("endpoint").asString
        val login = root.get("login").asString
        if (token.isEmpty() || !sameServer(endpoint) || !sameServer(login)) null else LoginSession(login, endpoint, token)
    } catch (e: RuntimeException) {
        null
    }

    internal fun parseResult(text: String): LoginResult? = try {
        val root = JsonParser.parseString(text).asJsonObject
        val name = root.get("loginName").asString
        val password = root.get("appPassword").asString
        if (name.isEmpty() || password.isEmpty()) null else LoginResult(name, password)
    } catch (e: RuntimeException) {
        null
    }

    private fun sameServer(address: String): Boolean {
        val u = address.toHttpUrlOrNull() ?: return false
        return u.isHttps && u.host.equals(base.host, ignoreCase = true) && u.port == base.port
    }

    private fun <T> send(req: Request, handle: (Int, String) -> T): T {
        try {
            client.newCall(req).execute().use { r ->
                val body = r.body.source().apply { request(MAX_BODY) }.buffer.readUtf8()
                return handle(r.code, body.take(MAX_BODY.toInt()))
            }
        } catch (e: IOException) {
            throw RemoteException(RemoteException.Kind.NETWORK)
        }
    }

    private fun kindOf(code: Int) = when {
        code == 401 || code == 403 -> RemoteException.Kind.AUTH
        code >= 500 || code == 429 -> RemoteException.Kind.SERVER
        else -> RemoteException.Kind.PROTOCOL
    }

    companion object {
        private const val USER_AGENT = "PaperNotes-by-Bob"
        private const val MAX_BODY = 64L * 1024

        /** Widerruft das App-Passwort auf dem Server (best effort, Fehler werden ignoriert). */
        fun revoke(client: OkHttpClient, baseUrl: String, authorization: String) {
            val base = baseUrl.toHttpUrlOrNull() ?: return
            val url = base.newBuilder().addPathSegments("ocs/v2.php/core/apppassword").build()
            val req = Request.Builder().url(url).delete()
                .header("Authorization", authorization).header("OCS-APIRequest", "true").header("User-Agent", USER_AGENT).build()
            try {
                client.newCall(req).execute().close()
            } catch (e: IOException) {
                // Der Nutzer kann das App-Passwort in Nextcloud unter Sicherheit selbst entfernen.
            }
        }
    }
}
