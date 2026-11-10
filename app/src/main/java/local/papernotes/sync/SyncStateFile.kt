package local.papernotes.sync

import local.papernotes.storage.FileOps
import java.io.File
import java.io.IOException

/** Das Gedächtnis als Datei im privaten App-Speicher. Schreiben ist atomar (temporäre Datei, dann ersetzen). */
internal class SyncStateFile(private val file: File) : SyncStateStore {
    override fun load(): SyncState? = try {
        if (file.isFile) SyncStateCodec.fromJson(file.readText(Charsets.UTF_8)) else null
    } catch (e: IOException) {
        null
    }

    /** Wirft [IOException], wenn nicht geschrieben werden kann (der Lauf endet dann mit [SyncResult.NoStorage]). */
    override fun save(state: SyncState) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(SyncStateCodec.toJson(state), Charsets.UTF_8)
        FileOps.moveReplacing(tmp, file)
    }

    fun delete() {
        file.delete()
        File(file.path + ".tmp").delete()
    }
}
