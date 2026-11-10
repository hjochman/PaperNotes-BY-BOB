package local.papernotes.home

import local.papernotes.storage.PnoteCodec
import java.io.File

/**
 * Dateiablage der Vorschaubilder: `<dir>/<noteId>-<modified>.png`. Der Schlüssel enthält ID und `modified`; nach einer
 * Änderung entsteht also eine neue Datei, die alte derselben Notiz wird entfernt. Die Gesamtgröße wird per LRU
 * (zuletzt benutzt = letzte Änderungszeit der Datei) begrenzt. Reine Dateilogik ohne Android-Bezug (JVM-testbar).
 * Es werden nur Dateien angefasst, deren Name dem Muster entspricht.
 */
class PreviewCache(private val dir: File, private val maxBytes: Long = DEFAULT_MAX_BYTES) {

    fun file(noteId: String, modified: Long): File? {
        if (!PnoteCodec.isValidId(noteId) || modified < 0) return null
        return File(dir, "$noteId-$modified.png")
    }

    /** Vorhandene Vorschau oder null. Ein Treffer gilt als Benutzung (LRU). */
    fun find(noteId: String, modified: Long, now: Long = System.currentTimeMillis()): File? {
        val f = file(noteId, modified) ?: return null
        if (!f.isFile || f.length() == 0L) return null
        f.setLastModified(now)
        return f
    }

    /** Legt die Zieldatei an (Verzeichnis wird erstellt). Null bei ungültigem Schlüssel oder nicht erstellbarem Ordner. */
    fun prepare(noteId: String, modified: Long): File? {
        val f = file(noteId, modified) ?: return null
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return null
        return f
    }

    /** Löscht alle anderen Fassungen derselben Notiz (alte `modified`-Werte). */
    fun deleteOtherVersions(noteId: String, keepModified: Long) {
        if (!PnoteCodec.isValidId(noteId)) return
        for (f in cacheFiles()) {
            val parsed = parse(f.name) ?: continue
            if (parsed.first == noteId && parsed.second != keepModified) f.delete()
        }
    }

    /** Entfernt die ältesten Dateien, bis die Gesamtgröße höchstens [maxBytes] beträgt. */
    fun prune() {
        val files = cacheFiles().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBytes) break
            val size = f.length()
            if (f.delete()) total -= size
        }
    }

    fun totalBytes(): Long = cacheFiles().sumOf { it.length() }

    private fun cacheFiles(): List<File> =
        (dir.listFiles() ?: emptyArray()).filter { it.isFile && parse(it.name) != null }

    companion object {
        const val DEFAULT_MAX_BYTES = 50L * 1024 * 1024
        private val NAME = Regex("([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})-(\\d{1,18})\\.png")

        /** Zerlegt einen Dateinamen in (Notiz-ID, modified); null, wenn er nicht zum Muster passt. */
        fun parse(name: String): Pair<String, Long>? {
            val m = NAME.matchEntire(name) ?: return null
            return m.groupValues[1] to (m.groupValues[2].toLongOrNull() ?: return null)
        }
    }
}
