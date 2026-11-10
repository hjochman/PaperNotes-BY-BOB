package local.papernotes.sync

import local.papernotes.storage.FolderPath
import local.papernotes.storage.PnoteCodec
import okhttp3.ConnectionSpec
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * WebDAV-Zugriff auf Nextcloud (`remote.php/dav/files/<benutzer>/<ziel>`). Jede Methode ist ein einzelner, kurzer
 * Aufruf und wirft [RemoteException]. Authentifizierung per Basic-Header mit dem App-Passwort; es werden keine
 * Weiterleitungen verfolgt, damit die Zugangsdaten nie an einen anderen Host gehen.
 *
 * Die Prüfung "nur https" geschieht beim Bauen aus den Einstellungen ([SyncPaths.normalizeBaseUrl]); [newClient] erlaubt
 * ebenfalls nur TLS. Der Zielordner wird beim ersten Schreiben angelegt.
 */
class WebDavRemote(
    private val client: OkHttpClient,
    davRoot: String,
    private val target: String,
    private val authorization: String,
) : SyncRemote {
    private val root = davRoot.trimEnd('/')

    /** Dekodierter Pfad des Zielordners, gegen den die Pfade der Antworten verglichen werden. */
    private val targetPath: String = URI(SyncPaths.folderUrl(root, target, "")).path.trimEnd('/')
    private var targetReady = false

    // ---------------------------------------------------------------- Lesen

    override fun listTree(): RemoteTree {
        val folders = LinkedHashSet<String>()
        val files = HashMap<String, RemoteFile>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add("" to 0)
        while (queue.isNotEmpty()) {
            val (folder, depth) = queue.removeFirst()
            val entries = propfind(folder)
            if (entries == null) {
                if (folder.isEmpty()) return RemoteTree(emptySet(), emptyMap())
                continue
            }
            for (e in entries) {
                if (e.isCollection) {
                    if (depth < LocalScanner.MAX_DEPTH && FolderPath.isValidName(e.name)) {
                        val path = if (folder.isEmpty()) e.name else "$folder/${e.name}"
                        folders.add(path)
                        queue.add(path to depth + 1)
                    }
                } else if (e.name.endsWith(".pnote")) {
                    val id = e.name.removeSuffix(".pnote")
                    if (PnoteCodec.isValidId(id)) files.putIfAbsent(id, RemoteFile(folder, e.size, e.modifiedMs))
                }
            }
        }
        return RemoteTree(folders, files)
    }

    override fun hasChildren(path: String): Boolean = propfind(path)?.isNotEmpty() ?: false

    /** Inhalt eines Ordners eine Ebene tief; null, wenn der Ordner nicht existiert. */
    private fun propfind(folder: String): List<DavEntry>? {
        val url = SyncPaths.folderUrl(root, target, folder) + "/"
        val body = PROPFIND_BODY.toRequestBody(XML)
        val req = request(url).header("Depth", "1").method("PROPFIND", body).build()
        return call(req) { r ->
            when (r.code) {
                404 -> null
                207 -> {
                    val bytes = readBounded(r)
                    val self = if (folder.isEmpty()) targetPath else "$targetPath/$folder"
                    try {
                        MultiStatusParser.parse(bytes, self)
                    } catch (e: IllegalArgumentException) {
                        throw RemoteException(RemoteException.Kind.PROTOCOL, 207)
                    }
                }
                else -> throw fail(r.code)
            }
        }
    }

    private fun readBounded(r: Response): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        try {
            r.body.byteStream().use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_LISTING_BYTES) throw RemoteException(RemoteException.Kind.PROTOCOL, r.code)
                }
            }
        } catch (e: IOException) {
            throw RemoteException(RemoteException.Kind.NETWORK)
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- Schreiben

    override fun makeFolder(path: String) {
        ensureTarget()
        mkcol(SyncPaths.folderUrl(root, target, path))
    }

    override fun move(id: String, fromFolder: String, toFolder: String) {
        ensureTarget()
        val from = SyncPaths.fileUrl(root, target, fromFolder, id)
        val to = SyncPaths.fileUrl(root, target, toFolder, id)
        val req = request(from).header("Destination", to).header("Overwrite", "T").method("MOVE", null).build()
        call(req) { r -> if (r.code != 201 && r.code != 204) throw fail(r.code) }
    }

    override fun upload(file: LocalFile, source: File) {
        ensureTarget()
        if (source.length() > MAX_UPLOAD_BYTES) throw RemoteException(RemoteException.Kind.PROTOCOL)
        // Einmal einlesen: Prüfsumme und gesendete Bytes stammen so aus demselben Stand der Datei.
        val bytes = try {
            source.readBytes()
        } catch (e: IOException) {
            throw RemoteException(RemoteException.Kind.NETWORK)
        }
        val sha1 = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
        val req = request(SyncPaths.fileUrl(root, target, file.folder, file.id))
            .header("X-OC-Mtime", (file.modified / 1000).toString())
            .header("OC-Checksum", "SHA1:$sha1")
            .put(bytes.toRequestBody(OCTET))
            .build()
        call(req) { r -> if (r.code != 201 && r.code != 204) throw fail(r.code) }
    }

    override fun delete(id: String, folder: String) = deleteUrl(SyncPaths.fileUrl(root, target, folder, id))

    override fun deleteFolder(path: String) = deleteUrl(SyncPaths.folderUrl(root, target, path) + "/")

    private fun deleteUrl(url: String) {
        call(request(url).delete().build()) { r -> if (r.code != 204 && r.code != 200 && r.code != 404) throw fail(r.code) }
    }

    /** Legt den Zielordner (und seine Elternordner) einmal je Client an; "schon vorhanden" (405) ist kein Fehler. */
    private fun ensureTarget() {
        if (targetReady) return
        var current = ""
        for (segment in target.split('/')) {
            current = if (current.isEmpty()) segment else "$current/$segment"
            mkcol("$root/${SyncPaths.encodePath(current)}")
        }
        targetReady = true
    }

    private fun mkcol(url: String) {
        call(request(url).method("MKCOL", null).build()) { r -> if (r.code != 201 && r.code != 405) throw fail(r.code) }
    }

    // ---------------------------------------------------------------- Hilfen

    private fun request(url: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", authorization).header("User-Agent", USER_AGENT)

    private fun <T> call(req: Request, handle: (Response) -> T): T {
        val response = try {
            client.newCall(req).execute()
        } catch (e: IOException) {
            throw RemoteException(RemoteException.Kind.NETWORK)
        }
        return response.use(handle)
    }

    private fun fail(code: Int): RemoteException = RemoteException(
        when {
            code == 401 || code == 403 -> RemoteException.Kind.AUTH
            code == 507 -> RemoteException.Kind.QUOTA
            code == 409 -> RemoteException.Kind.CONFLICT
            code == 404 -> RemoteException.Kind.NOT_FOUND
            code == 423 || code == 429 || code >= 500 -> RemoteException.Kind.SERVER
            else -> RemoteException.Kind.PROTOCOL // u. a. 3xx: Weiterleitungen werden bewusst nicht verfolgt
        },
        code,
    )

    companion object {
        private const val USER_AGENT = "PaperNotes-by-Bob"
        private const val MAX_LISTING_BYTES = 8 * 1024 * 1024
        private const val MAX_UPLOAD_BYTES = 256L * 1024 * 1024
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()
        private const val PROPFIND_BODY = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"

        /** Client für den Sync: nur TLS (kein Klartext), keine Weiterleitungen, knappe Zeitgrenzen. */
        fun newClient(): OkHttpClient = OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .build()

        /** `Authorization`-Wert für Basic-Anmeldung (UTF-8). */
        fun basicAuth(user: String, appPassword: String): String = Credentials.basic(user, appPassword, Charsets.UTF_8)
    }
}
