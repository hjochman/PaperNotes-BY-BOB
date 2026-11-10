package local.papernotes.sync

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Text-Form des Gedächtnisses ([SyncState]). Unlesbare oder unstimmige Eingaben liefern null; der Aufrufer nimmt dann einen
 * leeren Stand, und der löscht nie etwas (er führt höchstens zu einem Abgleich mit dem Server).
 */
object SyncStateCodec {
    private const val VERSION = 1

    fun toJson(state: SyncState): String {
        val root = JsonObject()
        root.addProperty("version", VERSION)
        root.addProperty("scope", state.scope)
        val folders = JsonArray()
        state.folders.sorted().forEach { folders.add(it) }
        root.add("folders", folders)
        val files = JsonObject()
        for (id in state.files.keys.sorted()) {
            val f = state.files.getValue(id)
            val o = JsonObject()
            o.addProperty("f", f.folder)
            o.addProperty("s", f.size)
            o.addProperty("m", f.modified)
            files.add(id, o)
        }
        root.add("files", files)
        return root.toString()
    }

    fun fromJson(text: String?): SyncState? {
        if (text.isNullOrBlank()) return null
        return try {
            val root = JsonParser.parseString(text).asJsonObject
            if (root.get("version").asInt != VERSION) return null
            val folders = root.getAsJsonArray("folders").map { it.asString }.toSet()
            val files = HashMap<String, SyncedFile>()
            for ((id, value) in root.getAsJsonObject("files").entrySet()) {
                val o = value.asJsonObject
                files[id] = SyncedFile(o.get("f").asString, o.get("s").asLong, o.get("m").asLong)
            }
            SyncState(root.get("scope").asString, folders, files)
        } catch (e: RuntimeException) {
            null
        }
    }
}
