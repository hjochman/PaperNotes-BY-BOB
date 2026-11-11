package local.papernotes.storage

/**
 * Endgültiges Löschen aus dem Papierkorb inklusive der automatischen Sicherungen der Notiz.
 *
 * Sicherungen einer Notiz werden nur gelöscht, wenn danach weder eine aktive Notiz noch ein weiterer Papierkorb-Eintrag
 * mit derselben ID existiert (z. B. dieselbe Notiz mehrfach im Papierkorb oder per Sync wieder angelegt).
 * Schlägt nur das Löschen der Sicherungen fehl, gilt das Löschen aus dem Papierkorb trotzdem als gelungen.
 */
class TrashService(
    private val repo: NoteRepository,
    private val backups: BackupStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Löscht den Papierkorb-Eintrag [entryName] endgültig und danach dessen Sicherungen. */
    fun deleteForever(entryName: String): PnoteResult<Unit> {
        val result = repo.deleteFromTrash(entryName)
        if (result is PnoteResult.Ok) removeOrphanBackups(setOf(idOf(entryName)))
        return result
    }

    /** Leert den Papierkorb und löscht die Sicherungen aller dort liegenden Notizen. Liefert die Zahl gelöschter Einträge. */
    fun emptyTrash(): PnoteResult<Int> {
        val ids = repo.trashEntryNames().map(::idOf).toSet()
        val result = repo.emptyTrash()
        removeOrphanBackups(ids)
        return result
    }

    /** Löscht alle Einträge, deren Frist bei [retentionDays] Tagen abgelaufen ist (0 = nie), samt Sicherungen. Liefert die Anzahl. */
    fun purgeExpired(retentionDays: Int): Int {
        if (retentionDays <= TrashPolicy.RETENTION_NEVER) return 0
        val now = clock()
        val expired = repo.listTrash().valueOrNull().orEmpty().filter { TrashPolicy.isExpired(it.deleted, now, retentionDays) }
        val ids = HashSet<String>()
        var removed = 0
        for (note in expired) {
            if (repo.deleteFromTrash(note.entryName) is PnoteResult.Ok) {
                removed++
                ids.add(note.id)
            }
        }
        removeOrphanBackups(ids)
        return removed
    }

    /** Löscht die Sicherungen von [noteId], wenn weder eine aktive Notiz noch ein Papierkorb-Eintrag mit dieser ID existiert. */
    fun removeBackupsIfOrphaned(noteId: String) = removeOrphanBackups(setOf(noteId))

    private fun removeOrphanBackups(ids: Set<String>) {
        if (ids.isEmpty()) return
        val remaining = repo.trashEntryNames().map(::idOf).toSet()
        for (id in ids) {
            if (id in remaining || repo.noteFile(id) != null) continue
            backups.deleteBackupsForNote(id)
        }
    }

    private fun idOf(entryName: String): String = entryName.substringBefore('.')
}
