package local.papernotes.sync

import java.io.File

/**
 * Führt einen Sync-Lauf aus: lokal scannen, Gedächtnis laden (beim ersten Lauf oder nach Wechsel von Server/Benutzer/Zielordner
 * mit dem Server abgleichen), planen, ausführen. Das Gedächtnis wird nach JEDER Operation gespeichert, ein Abbruch verliert
 * also nichts. Löschen auf dem Server geschieht nur für Dinge, die laut Gedächtnis von diesem Sync stammen.
 *
 * Einweg: der Server wird nie zurück in den Notizordner gelesen.
 */
class SyncRunner(
    private val root: File,
    private val remote: SyncRemote,
    private val store: SyncStateStore,
    private val scope: String,
    private val shouldStop: () -> Boolean = { false },
) {
    /** [confirmed] = Löschungen über der Schutzgrenze sind bestätigt; [forceReconcile] = Gedächtnis verwerfen und neu mit dem Server abgleichen. */
    fun run(confirmed: Boolean = false, forceReconcile: Boolean = false): SyncResult {
        val local = LocalScanner.scan(root) ?: return SyncResult.NoStorage
        var state = store.load()?.takeIf { it.scope == scope && !forceReconcile }
        try {
            if (state == null) {
                state = reconcile(local)
                store.save(state)
            }
            val plan = SyncPlanner.plan(local, state)
            if (plan.isEmpty) return SyncResult.NothingToDo
            if (!confirmed && SyncPolicy.needsConfirmation(plan, state, local)) return SyncResult.NeedsConfirmation(plan)
            return execute(plan, state, local)
        } catch (e: RemoteException) {
            return fail(e, -1)
        } catch (e: java.io.IOException) {
            return SyncResult.NoStorage // das Gedächtnis ließ sich nicht schreiben
        }
    }

    private fun fail(e: RemoteException, remaining: Int): SyncResult = when (e.kind) {
        RemoteException.Kind.AUTH -> SyncResult.AuthFailed
        RemoteException.Kind.NETWORK, RemoteException.Kind.SERVER,
        RemoteException.Kind.CONFLICT, RemoteException.Kind.NOT_FOUND,
        -> SyncResult.Retry(e.kind, remaining)
        RemoteException.Kind.QUOTA, RemoteException.Kind.PROTOCOL -> SyncResult.Failed(e.kind)
    }

    /**
     * Baut das Gedächtnis aus dem, was der Server schon hat. Nur Dinge, die lokal UND dort vorhanden sind, kommen hinein;
     * deshalb löscht dieser Schritt nie etwas. Gleiche Größe und gleiche Änderungszeit (Sekunden) gelten als schon hochgeladen,
     * alles andere wird neu hochgeladen. Liegt eine Notiz dort in einem anderen Ordner, wird sie verschoben statt doppelt abgelegt.
     */
    private fun reconcile(local: LocalSnapshot): SyncState {
        val tree = remote.listTree()
        val folders = tree.folders.filter { it in local.folders }.toSet()
        val files = HashMap<String, SyncedFile>()
        for ((id, mine) in local.files) {
            val theirs = tree.files[id] ?: continue
            val same = theirs.size == mine.size && theirs.modifiedMs / 1000 == mine.modified / 1000
            files[id] = SyncedFile(theirs.folder, theirs.size, if (same) mine.modified else -1)
        }
        return SyncState(scope, folders, files)
    }

    private fun execute(plan: SyncPlan, start: SyncState, local: LocalSnapshot): SyncResult {
        var state = start
        var done = 0
        var again = false
        val ops = plan.ops
        try {
            for ((index, op) in ops.withIndex()) {
                if (shouldStop()) return SyncResult.Retry(RemoteException.Kind.NETWORK, ops.size - index)
                val (next, complete) = apply(op, state, local)
                state = next
                store.save(state)
                if (complete) done++ else again = true
            }
        } catch (e: RemoteException) {
            return fail(e, ops.size - done)
        } catch (e: java.io.IOException) {
            return SyncResult.NoStorage
        }
        return SyncResult.Done(done, again)
    }

    /** Liefert den neuen Stand und ob die Operation vollständig war (false: Notiz änderte sich gerade, bleibt ausstehend). */
    private fun apply(op: SyncOp, state: SyncState, local: LocalSnapshot): Pair<SyncState, Boolean> = when (op) {
        is SyncOp.MakeFolder -> {
            ensureFolder(op.path)
            state.copy(folders = state.folders + op.path) to true
        }
        is SyncOp.Move -> {
            val moved = try {
                withParents(op.toFolder) { remote.move(op.id, op.fromFolder, op.toFolder) }
                true
            } catch (e: RemoteException) {
                if (e.kind != RemoteException.Kind.NOT_FOUND) throw e
                false
            }
            val before = state.files.getValue(op.id)
            if (moved) {
                state.copy(files = state.files + (op.id to before.copy(folder = op.toFolder))) to true
            } else {
                // Die Datei fehlt auf dem Server (dort gelöscht): neu hochladen statt scheitern.
                val file = local.files.getValue(op.id)
                upload(file, state)
            }
        }
        is SyncOp.Upload -> upload(op.file, state)
        is SyncOp.DeleteFile -> {
            remote.delete(op.id, op.folder)
            state.copy(files = state.files - op.id) to true
        }
        is SyncOp.DeleteFolder -> {
            // Fremde Inhalte (z. B. .DS_Store, Dateien anderer Programme) werden nie gelöscht: dann bleibt der Ordner stehen.
            if (!remote.hasChildren(op.path)) remote.deleteFolder(op.path)
            state.copy(folders = state.folders - op.path) to true
        }
    }

    private fun upload(file: LocalFile, state: SyncState): Pair<SyncState, Boolean> {
        val source = File(File(root, file.folder), "${file.id}.pnote")
        if (!unchanged(source, file)) return state to false
        withParents(file.folder) { remote.upload(file, source) }
        if (!unchanged(source, file)) return state to false // währenddessen geändert: nicht als erledigt merken
        return state.copy(files = state.files + (file.id to SyncedFile(file.folder, file.size, file.modified))) to true
    }

    private fun unchanged(source: File, file: LocalFile): Boolean =
        source.isFile && source.length() == file.size && source.lastModified() == file.modified

    private fun ensureFolder(path: String) {
        try {
            remote.makeFolder(path)
        } catch (e: RemoteException) {
            if (e.kind != RemoteException.Kind.CONFLICT) throw e
            ensureChain(path)
        }
    }

    /** Legt [path] und alle Elternordner an (ein vorhandener Ordner ist kein Fehler). */
    private fun ensureChain(path: String) {
        var current = ""
        for (segment in path.split('/')) {
            current = if (current.isEmpty()) segment else "$current/$segment"
            remote.makeFolder(current)
        }
    }

    /** Führt [block] aus; fehlt dabei ein Elternordner (409), wird die Kette angelegt und genau einmal wiederholt. */
    private fun withParents(folder: String, block: () -> Unit) {
        try {
            block()
        } catch (e: RemoteException) {
            if (e.kind != RemoteException.Kind.CONFLICT || folder.isEmpty()) throw e
            ensureChain(folder)
            block()
        }
    }
}
