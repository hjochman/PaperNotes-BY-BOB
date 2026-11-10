package local.papernotes.sync

import local.papernotes.storage.FolderPath
import local.papernotes.storage.PnoteCodec
import java.io.File
import java.nio.file.Files

/**
 * Liest den Notizordner als [LocalSnapshot] und folgt dabei denselben Regeln wie `NoteRepository`: nur `<uuid>.pnote`,
 * Ordner mit gültigem Namen, keine Punkt-Ordner (`.trash`, `.backup`), keine symbolischen Links, höchstens [MAX_DEPTH] Ebenen.
 * Fremde Dateien werden ignoriert und nie angefasst. Kann irgendein Ordner nicht gelesen werden, liefert [scan] null:
 * eine Teilansicht würde sonst wie "gelöscht" aussehen.
 */
object LocalScanner {
    const val MAX_DEPTH = 16

    fun scan(root: File): LocalSnapshot? {
        if (!root.isDirectory) return null
        val folders = LinkedHashSet<String>()
        val files = HashMap<String, LocalFile>()
        if (!walk(root, "", 0, folders, files)) return null
        return LocalSnapshot(folders, files)
    }

    private fun walk(dir: File, path: String, depth: Int, folders: MutableSet<String>, files: MutableMap<String, LocalFile>): Boolean {
        val children = dir.listFiles() ?: return false
        for (child in children) {
            if (Files.isSymbolicLink(child.toPath())) continue
            if (child.isFile) {
                val id = child.name.removeSuffix(".pnote")
                if (!child.name.endsWith(".pnote") || !PnoteCodec.isValidId(id)) continue
                val file = LocalFile(id, path, child.length(), child.lastModified())
                val other = files[id]
                // Dieselbe UUID zweimal: die neuere Datei zählt, damit das Ergebnis nicht von der Lesereihenfolge abhängt.
                if (other == null || file.modified > other.modified || (file.modified == other.modified && file.folder < other.folder)) {
                    files[id] = file
                }
            } else if (child.isDirectory && FolderPath.isValidName(child.name)) {
                if (depth >= MAX_DEPTH) continue
                val sub = if (path.isEmpty()) child.name else "$path/${child.name}"
                folders.add(sub)
                if (!walk(child, sub, depth + 1, folders, files)) return false
            }
        }
        return true
    }
}
