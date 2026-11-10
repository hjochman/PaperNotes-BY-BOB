package local.papernotes.sync

/**
 * Vergleicht den lokalen Stand mit dem Gedächtnis ([SyncState]) und liefert die nötigen Server-Operationen. Rein
 * rechnerisch, ohne Netz; deshalb auch für die Anzeige der ausstehenden Änderungen geeignet (kostet keinen Datenverkehr).
 *
 * Reihenfolge: Ordner anlegen (flach zuerst), verschieben, hochladen, Dateien löschen, Ordner löschen (tief zuerst).
 * So existiert jedes Ziel, bevor etwas dorthin geht, und ein Ordner wird erst entfernt, wenn seine Notizen weg oder umgezogen sind.
 * Ein Ordner-Umbenennen ergibt: neuer Ordner, Verschieben der Notizen (serverseitig, ohne Neuübertragung), alter Ordner löschen.
 * Notizen werden über ihre UUID erkannt; dieselbe UUID an anderem Pfad ist ein Verschieben.
 */
object SyncPlanner {
    fun plan(local: LocalSnapshot, state: SyncState): SyncPlan {
        val folderCompare = compareBy<String>({ depth(it) }, { it })

        val makeFolders = local.folders.filter { it.isNotEmpty() && it !in state.folders }
            .sortedWith(folderCompare)
            .map { SyncOp.MakeFolder(it) }

        val moves = ArrayList<SyncOp>()
        val uploads = ArrayList<SyncOp>()
        for (id in local.files.keys.sorted()) {
            val now = local.files.getValue(id)
            val before = state.files[id]
            if (before == null) {
                uploads.add(SyncOp.Upload(now, isNew = true))
                continue
            }
            if (before.folder != now.folder) moves.add(SyncOp.Move(id, before.folder, now.folder))
            if (before.size != now.size || before.modified != now.modified) uploads.add(SyncOp.Upload(now, isNew = false))
        }

        val deleteFiles = state.files.keys.filter { it !in local.files }.sorted()
            .map { SyncOp.DeleteFile(it, state.files.getValue(it).folder) }

        val deleteFolders = state.folders.filter { it.isNotEmpty() && it !in local.folders }
            .sortedWith(compareByDescending<String> { depth(it) }.thenBy { it })
            .map { SyncOp.DeleteFolder(it) }

        return SyncPlan(makeFolders + moves + uploads + deleteFiles + deleteFolders)
    }

    private fun depth(path: String): Int = path.count { it == '/' }
}
