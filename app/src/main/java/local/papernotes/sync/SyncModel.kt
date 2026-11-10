package local.papernotes.sync

/** Eine Notizdatei auf dem Tablet: `<id>.pnote` in [folder] ("" = Wurzel). [modified] = Änderungszeit der Datei in ms. */
data class LocalFile(val id: String, val folder: String, val size: Long, val modified: Long)

/** Momentaufnahme des Notizordners: alle sichtbaren Ordner (ohne Wurzel) und alle Notizen nach ID. */
data class LocalSnapshot(val folders: Set<String>, val files: Map<String, LocalFile>)

/** Stand einer Notiz zum Zeitpunkt des letzten erfolgreichen Hochladens bzw. Verschiebens. */
data class SyncedFile(val folder: String, val size: Long, val modified: Long)

/**
 * Das "Gedächtnis" des Sync: was die App selbst auf den Server gebracht hat. Nur dafür darf sie dort löschen.
 * [scope] benennt Server, Benutzer und Zielordner; ändert er sich, gilt der Stand nicht mehr (siehe [SyncPaths.scope]).
 * Ein leerer Stand löscht nie etwas.
 */
data class SyncState(
    val scope: String = "",
    val folders: Set<String> = emptySet(),
    val files: Map<String, SyncedFile> = emptyMap(),
)

/** Eine Operation auf dem Server. Reihenfolge und Abhängigkeiten legt [SyncPlanner] fest. */
sealed class SyncOp {
    /** Legt den Ordner [path] an (Eltern sind durch die Reihenfolge schon da). */
    data class MakeFolder(val path: String) : SyncOp()

    /** Verschiebt die Notiz serverseitig, ohne sie neu zu übertragen. */
    data class Move(val id: String, val fromFolder: String, val toFolder: String) : SyncOp()

    /** Lädt die Notiz hoch; [isNew] = auf dem Server (laut Gedächtnis) noch nicht vorhanden. */
    data class Upload(val file: LocalFile, val isNew: Boolean) : SyncOp()

    data class DeleteFile(val id: String, val folder: String) : SyncOp()

    /** Löscht einen vom Sync angelegten Ordner; der Runner prüft vorher, dass er leer ist. */
    data class DeleteFolder(val path: String) : SyncOp()
}

/** Geordnete Liste der nötigen Operationen. [count] ist die Zahl der ausstehenden Änderungen in der Anzeige. */
data class SyncPlan(val ops: List<SyncOp>) {
    val count: Int get() = ops.size
    val isEmpty: Boolean get() = ops.isEmpty()
    val uploads: Int get() = ops.count { it is SyncOp.Upload }
    val moves: Int get() = ops.count { it is SyncOp.Move }
    val newFolders: Int get() = ops.count { it is SyncOp.MakeFolder }
    val deletes: Int get() = ops.count { it is SyncOp.DeleteFile || it is SyncOp.DeleteFolder }
    val fileDeletes: Int get() = ops.count { it is SyncOp.DeleteFile }
}
