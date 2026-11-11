package local.papernotes.sync

import local.papernotes.storage.FolderPath
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.util.Locale

/** Adressen und Eingabeprüfung für den WebDAV-Zugriff. Reine Logik, ohne Netz. */
object SyncPaths {
    const val DEFAULT_TARGET = "PaperNotes"
    private const val MAX_URL_LENGTH = 300

    /**
     * Prüft die eingegebene Nextcloud-Adresse (z. B. `https://cloud.example.de/nextcloud`): nur `https`, ein Host, kein
     * Benutzername, keine Abfrage, kein Fragment, keine leeren oder `.`/`..`-Pfadteile. Liefert die Adresse ohne
     * abschließenden Schrägstrich mit kleingeschriebenem Host oder null.
     */
    fun normalizeBaseUrl(input: String): String? {
        val text = input.trim().trimEnd('/')
        if (text.isEmpty() || text.length > MAX_URL_LENGTH) return null
        val uri = try {
            URI(text)
        } catch (e: URISyntaxException) {
            return null
        }
        if (uri.scheme?.lowercase(Locale.ROOT) != "https") return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        val path = uri.rawPath.orEmpty()
        if (path.isNotEmpty()) {
            if (!path.startsWith("/")) return null
            if (path.substring(1).split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        }
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "https://$host$port$path"
    }

    /** Zielordner auf dem Server ("PaperNotes" oder "A/B"): Schrägstriche am Rand weg, nach NFC normalisiert, Namen wie bei [FolderPath]; sonst null. */
    fun normalizeTarget(input: String): String? {
        val text = FolderPath.normalize(input).trim().trim('/')
        return text.takeIf { it.isNotEmpty() && FolderPath.isValid(it) }
    }

    /** Ein Pfadteil prozentkodiert (Leerzeichen als %20), damit Umlaute und Leerzeichen sicher übertragen werden. */
    fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    fun encodePath(path: String): String = path.split('/').joinToString("/") { encodeSegment(it) }

    /** Wurzel der Dateien eines Benutzers: `<basis>/remote.php/dav/files/<benutzer>`. */
    fun davRoot(baseUrl: String, user: String): String = "$baseUrl/remote.php/dav/files/${encodeSegment(user)}"

    /** Adresse des Ordners [folder] ("" = Wurzel des Zielordners) unter dem Zielordner [target]. */
    fun folderUrl(davRoot: String, target: String, folder: String): String =
        "$davRoot/${encodePath(target)}" + if (folder.isEmpty()) "" else "/${encodePath(folder)}"

    /** Adresse der Notiz `<id>.pnote` im Ordner [folder]. */
    fun fileUrl(davRoot: String, target: String, folder: String, id: String): String =
        "${folderUrl(davRoot, target, folder)}/$id.pnote"

    /** Kennung von Server, Benutzer und Zielordner; ändert sie sich, ist das Gedächtnis ungültig. */
    fun scope(baseUrl: String, user: String, target: String): String = "$baseUrl|$user|$target"
}
