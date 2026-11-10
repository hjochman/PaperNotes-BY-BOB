package local.papernotes.model

/**
 * Eine Notiz mit Seiten in der Reihenfolge von [pageIds].
 * [folderPath]: "" = Wurzel, sonst "BIT" oder "A/B". Zeiten in Millisekunden seit 1970.
 * [preview]: JPEG der ersten Seite (wird als `preview.jpg` im Paket gespeichert), null wenn noch keine vorliegt.
 * [importSource]: optionale Herkunftskennung einer importierten Notiz (z. B. `hinote:<noteId>`), um Doppelimporte zu erkennen.
 * [media]: Mediendateien (Bilder) des Pakets, Schlüssel = Dateiname unterhalb von `media/`.
 */
data class Note(
    val id: String,
    val title: String,
    val folderPath: String,
    val created: Long,
    val modified: Long,
    val favorite: Boolean,
    val pinned: Boolean,
    val pageIds: List<String>,
    val pages: Map<String, Page>,
    val preview: ByteArray? = null,
    val importSource: String? = null,
    val media: Map<String, ByteArray> = emptyMap(),
) {
    // Eigene Gleichheit, weil ein ByteArray in einer data class sonst nur per Referenz verglichen würde.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Note) return false
        return id == other.id && title == other.title && folderPath == other.folderPath &&
            created == other.created && modified == other.modified && favorite == other.favorite &&
            pinned == other.pinned && pageIds == other.pageIds && pages == other.pages &&
            preview.contentEquals(other.preview) && importSource == other.importSource && mediaEquals(other.media)
    }

    private fun mediaEquals(other: Map<String, ByteArray>): Boolean =
        media.keys == other.keys && media.all { (name, bytes) -> bytes.contentEquals(other.getValue(name)) }

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + title.hashCode()
        h = 31 * h + folderPath.hashCode()
        h = 31 * h + created.hashCode()
        h = 31 * h + modified.hashCode()
        h = 31 * h + favorite.hashCode()
        h = 31 * h + pinned.hashCode()
        h = 31 * h + pageIds.hashCode()
        h = 31 * h + pages.hashCode()
        h = 31 * h + preview.contentHashCode()
        h = 31 * h + importSource.hashCode()
        h = 31 * h + media.entries.sumOf { it.key.hashCode() xor it.value.contentHashCode() }
        return h
    }
}
