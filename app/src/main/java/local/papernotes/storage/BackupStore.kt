package local.papernotes.storage

import local.papernotes.model.Note
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Einzelne Sicherungskopie einer Notiz.
 *
 * @property noteId ID der gesicherten Notiz
 * @property fileName Dateiname in `.backup/<noteId>/`, z. B. `1700000000000.pnote`
 * @property timestamp Zeitstempel der Sicherung (in Millisekunden)
 * @property file Dateireferenz auf die Sicherungsdatei
 * @property sizeBytes Dateigröße in Bytes
 */
data class BackupEntry(
    val noteId: String,
    val fileName: String,
    val timestamp: Long,
    val file: File,
    val sizeBytes: Long,
)

/**
 * Zusammenfassung aller Sicherungskopien einer Notiz für die Sicherungs-Übersicht.
 *
 * @property noteId ID der Notiz
 * @property title Titel der Notiz aus der jüngsten Sicherung
 * @property folderPath Ordnerpfad aus der jüngsten Sicherung
 * @property latestTimestamp Zeitstempel der jüngsten Sicherung
 * @property latestPageCount Seitenzahl der jüngsten Sicherung
 * @property backupCount Gesamtanzahl der Sicherungen dieser Notiz
 * @property totalSizeBytes Gesamtgröße aller Sicherungen dieser Notiz in Bytes
 * @property latestBackupFileName Dateiname der jüngsten Sicherung
 */
data class NoteBackupGroup(
    val noteId: String,
    val title: String,
    val folderPath: String,
    val latestTimestamp: Long,
    val latestPageCount: Int,
    val backupCount: Int,
    val totalSizeBytes: Long,
    val latestBackupFileName: String,
)

/**
 * Verwaltung von automatischen Notiz-Sicherungskopien unter `Documents/PaperNotes/.backup/<noteId>/<zeitstempel>.pnote`.
 *
 * Eigenschaften & Sicherheitsregeln:
 * - Der Ordner `.backup` wird wie `.trash` von allen Notizlisten, Suchen und Ordneroperationen ignoriert.
 * - Pfadsicherheit: [noteId] wird strikt auf UUID-Muster geprüft, kanonische Pfade werden gegen das Wurzelverzeichnis
 *   geprüft, symbolischen Links wird nicht gefolgt.
 * - Atomares Schreiben: Kopie der bestehenden `.pnote`-Datei über temporäre Zwischendatei und atomaren Rename.
 * - Aufbewahrungsbegrenzung: Pro Notiz maximal [MAX_BACKUPS_PER_NOTE] Sicherungen; älteste zuerst gelöscht.
 * - Persistenz bei Papierkorb-Aktionen: Wenn eine Notiz in den Papierkorb wandert oder aus dem Papierkorb endgültig
 *   gelöscht bzw. der Papierkorb geleert wird, bleiben die Sicherungen in `.backup/` für Notfälle erhalten.
 * - Wiederherstellung: Erzeugt immer eine NEUE Notizkopie mit frischen UUIDs (über [NoteRepository.save]) und
 *   überschreibt niemals die vorhandene Notiz.
 */
class BackupStore(
    rootDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val root: File = rootDir.canonicalFile
    private val backupDir: File = File(root, BACKUP_DIR)
    private val lock = Any()

    /**
     * Erstellt eine Sicherungskopie der Notizdatei [sourceFile] für die Notiz [noteId] mit Zeitstempel [timestamp].
     * Rotiert automatisch alte Sicherungen, wenn [MAX_BACKUPS_PER_NOTE] überschritten wird.
     */
    fun backup(noteId: String, sourceFile: File, timestamp: Long = clock()): PnoteResult<File> = synchronized(lock) {
        if (!PnoteCodec.isValidId(noteId)) return PnoteResult.Err(PnoteError.InvalidId)
        if (!sourceFile.isFile || Files.isSymbolicLink(sourceFile.toPath())) return PnoteResult.Err(PnoteError.NotFound)

        val noteDir = resolveNoteBackupDir(noteId) ?: return PnoteResult.Err(PnoteError.InvalidFolder)
        try {
            Files.createDirectories(noteDir.toPath())
        } catch (e: IOException) {
            return PnoteResult.Err(PnoteError.Io)
        }

        val targetFile = File(noteDir, "$timestamp.pnote")
        val tmpFile = File(noteDir, ".tmp-$timestamp-${UUID.randomUUID()}.pnote")
        try {
            Files.copy(sourceFile.toPath(), tmpFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            FileOps.moveReplacing(tmpFile, targetFile)
        } catch (e: IOException) {
            tmpFile.delete()
            return PnoteResult.Err(PnoteError.Io)
        }

        pruneOldBackups(noteDir)
        PnoteResult.Ok(targetFile)
    }

    /**
     * Liefert alle Sicherungen für die Notiz [noteId], sortiert nach Zeitstempel absteigend (jüngste zuerst).
     */
    fun list(noteId: String): List<BackupEntry> = synchronized(lock) {
        if (!PnoteCodec.isValidId(noteId)) return emptyList()
        val noteDir = resolveNoteBackupDir(noteId) ?: return emptyList()
        if (!noteDir.isDirectory || Files.isSymbolicLink(noteDir.toPath())) return emptyList()

        return (noteDir.listFiles() ?: emptyArray())
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && BACKUP_FILE_PATTERN.matches(it.name) }
            .mapNotNull { file ->
                val ts = file.name.removeSuffix(".pnote").toLongOrNull() ?: return@mapNotNull null
                BackupEntry(noteId, file.name, ts, file, file.length())
            }
            .sortedByDescending { it.timestamp }
    }

    /**
     * Liefert eine Übersicht aller gesicherten Notizen (gruppiert nach Notiz), sortiert nach dem Datum der
     * jüngsten Sicherung absteigend.
     */
    fun listGroups(): List<NoteBackupGroup> = synchronized(lock) {
        if (!backupDir.isDirectory || Files.isSymbolicLink(backupDir.toPath())) return emptyList()

        val subdirs = (backupDir.listFiles() ?: emptyArray())
            .filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) && PnoteCodec.isValidId(it.name) }

        val groups = ArrayList<NoteBackupGroup>()
        for (dir in subdirs) {
            val noteId = dir.name
            val backups = list(noteId)
            if (backups.isEmpty()) continue
            val latest = backups.first()
            val summary = PnoteCodec.readSummary(latest.file).valueOrNull() ?: continue
            val totalSize = backups.sumOf { it.sizeBytes }
            groups.add(
                NoteBackupGroup(
                    noteId = noteId,
                    title = summary.title,
                    folderPath = summary.folderPath,
                    latestTimestamp = latest.timestamp,
                    latestPageCount = summary.pageCount,
                    backupCount = backups.size,
                    totalSizeBytes = totalSize,
                    latestBackupFileName = latest.fileName,
                )
            )
        }
        groups.sortedByDescending { it.latestTimestamp }
    }

    /**
     * Stellt die Sicherung [backupFileName] der Notiz [noteId] als NEUE Notizkopie mit frischen IDs wieder her.
     * Überschreibt nie eine vorhandene Notiz.
     *
     * @param noteId ID der ursprünglichen Notiz
     * @param backupFileName Dateiname der Sicherungsdatei in `.backup/<noteId>/`
     * @param newTitle Optionaler neuer Titel; falls null, wird der Titel aus der Sicherung unverändert übernommen
     * @param targetFolder Optionaler Zielordner; falls null, wird der ursprüngliche Ordner der Sicherung verwendet
     * @return ID der neu erstellten Notizkopie
     */
    fun restore(
        noteId: String,
        backupFileName: String,
        newTitle: String? = null,
        targetFolder: String? = null,
    ): PnoteResult<String> = synchronized(lock) {
        if (!PnoteCodec.isValidId(noteId) || !BACKUP_FILE_PATTERN.matches(backupFileName)) {
            return PnoteResult.Err(PnoteError.InvalidId)
        }
        val noteDir = resolveNoteBackupDir(noteId) ?: return PnoteResult.Err(PnoteError.InvalidFolder)
        val backupFile = File(noteDir, backupFileName)
        if (!backupFile.isFile || Files.isSymbolicLink(backupFile.toPath())) {
            return PnoteResult.Err(PnoteError.NotFound)
        }
        if (backupFile.canonicalFile.parentFile != noteDir.canonicalFile) {
            return PnoteResult.Err(PnoteError.InvalidFolder)
        }

        val loadedNote = when (val r = PnoteCodec.read(backupFile)) {
            is PnoteResult.Ok -> r.value
            is PnoteResult.Err -> return r
        }

        val now = clock()
        val titleToUse = if (newTitle != null && NoteTitles.isValid(newTitle)) {
            newTitle
        } else {
            loadedNote.title
        }

        val folderToUse = targetFolder ?: loadedNote.folderPath
        val fresh = withFreshIds(loadedNote).copy(
            title = titleToUse,
            folderPath = folderToUse,
            created = now,
            modified = now,
        )

        val repo = NoteRepository(root, clock)
        when (val saved = repo.save(fresh)) {
            is PnoteResult.Ok -> PnoteResult.Ok(fresh.id)
            is PnoteResult.Err -> saved
        }
    }

    /**
     * Gesamtanzahl aller vorhandenen Sicherungsdateien über alle Notizen hinweg.
     */
    fun count(): Int = synchronized(lock) {
        if (!backupDir.isDirectory || Files.isSymbolicLink(backupDir.toPath())) return 0
        var total = 0
        for (dir in (backupDir.listFiles() ?: emptyArray())) {
            if (dir.isDirectory && !Files.isSymbolicLink(dir.toPath()) && PnoteCodec.isValidId(dir.name)) {
                total += (dir.listFiles() ?: emptyArray()).count {
                    it.isFile && !Files.isSymbolicLink(it.toPath()) && BACKUP_FILE_PATTERN.matches(it.name)
                }
            }
        }
        total
    }

    /**
     * Gesamtgröße aller Sicherungsdateien in Bytes.
     */
    fun totalSizeBytes(): Long = synchronized(lock) {
        if (!backupDir.isDirectory || Files.isSymbolicLink(backupDir.toPath())) return 0L
        var total = 0L
        for (dir in (backupDir.listFiles() ?: emptyArray())) {
            if (dir.isDirectory && !Files.isSymbolicLink(dir.toPath()) && PnoteCodec.isValidId(dir.name)) {
                for (file in (dir.listFiles() ?: emptyArray())) {
                    if (file.isFile && !Files.isSymbolicLink(file.toPath()) && BACKUP_FILE_PATTERN.matches(file.name)) {
                        total += file.length()
                    }
                }
            }
        }
        total
    }

    /**
     * Löscht alle Sicherungsdateien und Sicherungsverzeichnisse in `.backup/` endgültig.
     * Liefert die Anzahl der gelöschten Sicherungsdateien.
     */
    fun deleteAll(): PnoteResult<Int> = synchronized(lock) {
        if (!backupDir.exists()) return PnoteResult.Ok(0)
        if (Files.isSymbolicLink(backupDir.toPath()) || !backupDir.isDirectory) {
            return PnoteResult.Err(PnoteError.InvalidFolder)
        }
        var removed = 0
        var failed = false
        val subdirs = backupDir.listFiles() ?: emptyArray()
        for (dir in subdirs) {
            if (dir.isDirectory && !Files.isSymbolicLink(dir.toPath())) {
                val files = dir.listFiles() ?: emptyArray()
                for (f in files) {
                    try {
                        if (Files.isRegularFile(f.toPath())) {
                            Files.delete(f.toPath())
                            removed++
                        }
                    } catch (e: IOException) {
                        failed = true
                    }
                }
                try {
                    Files.delete(dir.toPath())
                } catch (e: IOException) {
                    failed = true
                }
            }
        }
        if (failed) PnoteResult.Err(PnoteError.Io) else PnoteResult.Ok(removed)
    }

    /**
     * Löscht alle Sicherungen für eine einzelne Notiz [noteId].
     */
    fun deleteBackupsForNote(noteId: String): PnoteResult<Int> = synchronized(lock) {
        if (!PnoteCodec.isValidId(noteId)) return PnoteResult.Err(PnoteError.InvalidId)
        val noteDir = resolveNoteBackupDir(noteId) ?: return PnoteResult.Err(PnoteError.InvalidFolder)
        if (!noteDir.exists()) return PnoteResult.Ok(0)
        var removed = 0
        var failed = false
        val files = noteDir.listFiles() ?: emptyArray()
        for (f in files) {
            try {
                if (Files.isRegularFile(f.toPath())) {
                    Files.delete(f.toPath())
                    removed++
                }
            } catch (e: IOException) {
                failed = true
            }
        }
        try {
            Files.delete(noteDir.toPath())
        } catch (e: IOException) {
            failed = true
        }
        if (failed) PnoteResult.Err(PnoteError.Io) else PnoteResult.Ok(removed)
    }

    private fun pruneOldBackups(noteDir: File) {
        val files = (noteDir.listFiles() ?: emptyArray())
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && BACKUP_FILE_PATTERN.matches(it.name) }
            .sortedBy { it.name.removeSuffix(".pnote").toLongOrNull() ?: 0L }

        if (files.size > MAX_BACKUPS_PER_NOTE) {
            val toDeleteCount = files.size - MAX_BACKUPS_PER_NOTE
            for (i in 0 until toDeleteCount) {
                try {
                    Files.delete(files[i].toPath())
                } catch (ignored: IOException) {
                }
            }
        }
    }

    private fun resolveNoteBackupDir(noteId: String): File? {
        if (!PnoteCodec.isValidId(noteId)) return null
        val dir = File(backupDir, noteId).canonicalFile
        return if (isInsideBackupDir(dir)) dir else null
    }

    private fun isInsideBackupDir(dir: File): Boolean {
        val backupCanonical = backupDir.canonicalFile
        return dir == backupCanonical || dir.path.startsWith(backupCanonical.path + File.separator)
    }

    private fun withFreshIds(note: Note): Note {
        val idMap = note.pageIds.associateWith { UUID.randomUUID().toString() }
        return note.copy(
            id = UUID.randomUUID().toString(),
            pageIds = note.pageIds.map { idMap.getValue(it) },
            pages = note.pageIds.associate { old ->
                idMap.getValue(old) to note.pages.getValue(old).copy(id = idMap.getValue(old))
            },
        )
    }

    companion object {
        const val BACKUP_DIR = ".backup"
        const val MAX_BACKUPS_PER_NOTE = 10
        private val BACKUP_FILE_PATTERN = Regex("^(\\d{1,19})\\.pnote$")
    }
}
