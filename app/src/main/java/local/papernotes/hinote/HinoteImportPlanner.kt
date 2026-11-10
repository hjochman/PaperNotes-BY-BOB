package local.papernotes.hinote

import local.papernotes.storage.FolderPath
import java.io.File
import java.nio.file.Files

/** Eine beim Durchsuchen gefundene `.hinote`-Datei. [relativeDir] = Unterverzeichnis relativ zur Scanwurzel ("" = direkt in der Wurzel, sonst "A" oder "A/B"). */
class ScannedFile(val file: File, val relativeDir: String, val lastModified: Long)

/**
 * Reine Planungslogik des Ordnerimports (JVM-testbar):
 *
 * - **Zielordner:** Das Unterverzeichnis relativ zur Scanwurzel wird Ordnerpfad unterhalb von [HinoteImporter.TARGET_FOLDER]
 *   (`BIT` -> `HiNote-Import/BIT`). Dateien direkt in der Wurzel, zu tiefe oder nicht über [FolderPath] gültige
 *   Verzeichnisnamen -> `HiNote-Import`. Verzeichnisnamen werden nicht umgeschrieben.
 * - **Doppelte Dateien derselben noteId im selben Lauf:** Gewinner ist die Datei mit (1) dem neuesten Dateizeitstempel,
 *   bei Gleichstand (2) der größten Seitenanzahl, dann (3) der in einem Unterverzeichnis (trägt eine Ordnerzuordnung),
 *   dann (4) der kleinsten Position in der Eingabeliste (= Namensreihenfolge). Alle anderen zählen als übersprungen.
 */
object HinoteImportPlanner {
    /** Größte Tiefe unterhalb der Scanwurzel (`A/B/C` = 3). */
    const val MAX_DEPTH = 3

    fun folderFor(relativeDir: String): String {
        if (relativeDir.isEmpty()) return HinoteImporter.TARGET_FOLDER
        val segments = relativeDir.split('/')
        if (segments.size > MAX_DEPTH || segments.any { !FolderPath.isValidName(it) }) return HinoteImporter.TARGET_FOLDER
        val path = HinoteImporter.TARGET_FOLDER + "/" + segments.joinToString("/")
        return if (FolderPath.isValid(path)) path else HinoteImporter.TARGET_FOLDER
    }

    class Candidate(val key: Int, val noteId: String, val lastModified: Long, val pageCount: Int, val inSubfolder: Boolean)

    /** Liefert die [Candidate.key]-Werte der Gewinner, je noteId genau einer. */
    fun selectWinners(candidates: List<Candidate>): Set<Int> {
        val best = HashMap<String, Candidate>()
        for (c in candidates) {
            val cur = best[c.noteId]
            if (cur == null || beats(c, cur)) best[c.noteId] = c
        }
        return best.values.mapTo(HashSet()) { it.key }
    }

    private fun beats(a: Candidate, b: Candidate): Boolean = when {
        a.lastModified != b.lastModified -> a.lastModified > b.lastModified
        a.pageCount != b.pageCount -> a.pageCount > b.pageCount
        a.inSubfolder != b.inSubfolder -> a.inSubfolder
        else -> a.key < b.key
    }
}

/** Durchsucht ein Verzeichnis rekursiv nach `*.hinote` (Tiefe höchstens [HinoteImportPlanner.MAX_DEPTH], keine Symlinks). */
object HinoteFolderScanner {
    fun scan(root: File, maxFiles: Int): List<ScannedFile> {
        val out = ArrayList<ScannedFile>()
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) return out
        walk(root, "", 0, maxFiles, out)
        return out
    }

    private fun walk(dir: File, rel: String, depth: Int, maxFiles: Int, out: MutableList<ScannedFile>) {
        val entries = (dir.listFiles() ?: return).sortedBy { it.name }
        for (f in entries) {
            if (out.size >= maxFiles) return
            if (Files.isSymbolicLink(f.toPath())) continue
            if (f.isFile && f.name.endsWith(".hinote", ignoreCase = true)) {
                out.add(ScannedFile(f, rel, f.lastModified()))
            }
        }
        if (depth >= HinoteImportPlanner.MAX_DEPTH) return
        for (f in entries) {
            if (out.size >= maxFiles) return
            if (f.isDirectory && !Files.isSymbolicLink(f.toPath())) {
                walk(f, if (rel.isEmpty()) f.name else rel + "/" + f.name, depth + 1, maxFiles, out)
            }
        }
    }
}
