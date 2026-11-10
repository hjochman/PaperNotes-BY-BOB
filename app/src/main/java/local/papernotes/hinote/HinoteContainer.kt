package local.papernotes.hinote

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import local.papernotes.storage.PnoteCodec
import local.papernotes.storage.PnoteException
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

class HinoteElement(
    val type: Int, // 0 = Text, 1 = Picture
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val filePath: String?,
    val contentText: String?,
    val html: String? = null,
)

/** Eine Seite eines HiNote-Exports. [broken] = Seiten-JSON unlesbar (Seite wird leer angelegt); [bin] null = Seite ohne Striche. */
class HinotePageEntry(
    val id: String,
    val pageNumber: Int,
    val background: String,
    val createTime: Long,
    val modifiedTime: Long,
    val bookmarked: Boolean,
    val bin: ByteArray?,
    val broken: Boolean,
    val elements: List<HinoteElement> = emptyList(),
)

/** Inhalt eines `.hinote`-Exports ohne Seitenvorschauen (die JPG-Vorschauen unter `files/` werden nicht gelesen). */
class HinoteContainer(
    val noteId: String,
    val title: String,
    val background: String,
    val favorite: Boolean,
    val pinned: Boolean,
    val pages: List<HinotePageEntry>,
    val images: Map<String, ByteArray> = emptyMap(),
)

/**
 * Liest den ZIP-Container eines HiNote-Exports (Aufbau: `docs/hinote-bin-format.md` Abschnitt 1) vollständig im
 * Speicher, nichts wird auf die Platte entpackt. Erlaubt sind nur:
 *
 * ```
 * <noteId>.jhinote  pages/<id>.jhinote  files/<id>.bin   (id = 32 Hex-Zeichen)
 * custom_md.jhinote  files/<id>.jpg  pages/  files/       (werden gelesen/verworfen, aber nicht ausgewertet)
 * ```
 *
 * Namen mit `..`, Backslash, führendem `/` oder zu lang führen zum Abbruch (ZIP-Slip). Sonstige, harmlos benannte
 * Einträge werden ignoriert. Grenzen wie beim `.pnote`-Codec ([PnoteCodec.MAX_ENTRIES], [PnoteCodec.MAX_ENTRY_BYTES],
 * [PnoteCodec.MAX_TOTAL_BYTES]) plus Kompressionsratenprüfung. Wirft nie nach außen.
 */
object HinoteContainerReader {
    const val MAX_JSON_BYTES = 4 * 1024 * 1024
    const val MAX_NAME_LENGTH = 200
    const val MAX_PAGES = PnoteCodec.MAX_PAGES

    /** Größte Kompressionsrate (entpackt / gepackt) für Einträge über [RATIO_FREE_BYTES]. */
    const val MAX_RATIO = 200L
    const val RATIO_FREE_BYTES = 1L * 1024 * 1024

    private const val ID = "[0-9a-f]{32}"
    private val NOTE_ENTRY = Regex("($ID)\\.jhinote")
    private val PAGE_ENTRY = Regex("pages/($ID)\\.jhinote")
    private val BIN_ENTRY = Regex("files/($ID)\\.bin")
    private val IMAGE_ENTRY = Regex("files/([^/]+\\.(jpg|png|jpeg|gif))", RegexOption.IGNORE_CASE)

    fun read(input: InputStream): HinoteResult<HinoteContainer> = try {
        HinoteResult.Ok(readStrict(CountingLimitInputStream(input, PnoteCodec.MAX_TOTAL_BYTES)))
    } catch (e: HinoteException) {
        HinoteResult.Err(e.error)
    } catch (e: PnoteException) {
        HinoteResult.Err(HinoteError.LimitExceeded)
    } catch (e: IOException) {
        HinoteResult.Err(HinoteError.Corrupt)
    } catch (e: RuntimeException) {
        HinoteResult.Err(HinoteError.Corrupt)
    }

    /** Begrenzt die gepackte Eingabe (Dateigröße des `.hinote`). */
    private class CountingLimitInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var count = 0L

        private fun add(n: Long) {
            count += n
            if (count > limit) throw HinoteException(HinoteError.LimitExceeded)
        }

        override fun read(): Int = super.read().also { if (it >= 0) add(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) add(it.toLong()) }

        override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) add(it) }
    }

    private fun isSafeName(name: String, isDirectory: Boolean): Boolean {
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) return false
        if (name.contains('\\') || name.startsWith("/") || name.contains("..") || name.contains('\u0000')) return false
        return name.none { it.isISOControl() } && (!isDirectory || name.endsWith("/"))
    }

    private fun readStrict(input: InputStream): HinoteContainer {
        val zip = ZipInputStream(input)
        val seen = HashSet<String>()
        var noteJson: ByteArray? = null
        var noteId: String? = null
        val pageJson = LinkedHashMap<String, ByteArray>()
        val bins = HashMap<String, ByteArray>()
        val images = HashMap<String, ByteArray>()
        var entries = 0
        var total = 0L
        while (true) {
            val entry = zip.nextEntry ?: break
            if (++entries > PnoteCodec.MAX_ENTRIES) throw HinoteException(HinoteError.LimitExceeded)
            val name = entry.name
            if (!isSafeName(name, entry.isDirectory)) throw HinoteException(HinoteError.InvalidEntryName)
            if (!seen.add(name)) throw HinoteException(HinoteError.Corrupt)
            if (entry.isDirectory) continue
            val noteMatch = NOTE_ENTRY.matchEntire(name)
            val pageMatch = PAGE_ENTRY.matchEntire(name)
            val binMatch = BIN_ENTRY.matchEntire(name)
            val imgMatch = IMAGE_ENTRY.matchEntire(name)
            val keep = noteMatch != null || pageMatch != null || binMatch != null || imgMatch != null
            val limit = minOf(PnoteCodec.MAX_ENTRY_BYTES, PnoteCodec.MAX_TOTAL_BYTES - total)
            val data = PnoteCodec.readLimited(zip, limit, keep)
            total += data.size
            val packed = entry.compressedSize
            if (packed > 0 && data.size > RATIO_FREE_BYTES && data.size / packed > MAX_RATIO) {
                throw HinoteException(HinoteError.LimitExceeded)
            }
            when {
                noteMatch != null -> {
                    if (noteJson != null) throw HinoteException(HinoteError.Corrupt)
                    noteId = noteMatch.groupValues[1]
                    noteJson = data.bytes
                }
                pageMatch != null -> {
                    if (pageJson.size >= MAX_PAGES) throw HinoteException(HinoteError.LimitExceeded)
                    pageJson[pageMatch.groupValues[1]] = data.bytes
                }
                binMatch != null -> bins[binMatch.groupValues[1]] = data.bytes
                imgMatch != null -> images[imgMatch.groupValues[1]] = data.bytes
            }
        }
        val id = noteId ?: throw HinoteException(HinoteError.Corrupt)
        val note = noteContent(noteJson ?: throw HinoteException(HinoteError.Corrupt)) ?: throw HinoteException(HinoteError.Corrupt)
        val pages = pageJson.map { (pageId, json) -> pageEntry(pageId, json, bins[pageId]) }
        return HinoteContainer(
            noteId = id,
            title = note.string("noteTitle").orEmpty(),
            background = note.string("background") ?: "base1",
            favorite = note.intOr("isFavorite", 0) == 1,
            pinned = note.intOr("isTop", 0) == 1,
            pages = pages.sortedWith(compareBy({ it.pageNumber }, { it.id })),
            images = images,
        )
    }

    private fun pageEntry(pageId: String, json: ByteArray, bin: ByteArray?): HinotePageEntry {
        val page = pageContent(json)
        if (page == null || page.string("id").let { it != null && it != pageId }) {
            return HinotePageEntry(pageId, Int.MAX_VALUE, "base1", 0, 0, false, null, broken = true)
        }
        val marks = page.get("pageBookMarks")

        val elements = ArrayList<HinoteElement>()
        val pageElementsArray = page.get("pageElement")?.takeIf { it.isJsonArray }?.asJsonArray
        if (pageElementsArray != null) {
            for (elElem in pageElementsArray) {
                if (!elElem.isJsonObject) continue
                val el = elElem.asJsonObject
                val elementType = el.intOr("elementType", -1)
                if (elementType == 0 || elementType == 1) {
                    elements.add(HinoteElement(
                        type = elementType,
                        x = el.floatOr("positionX", 0f),
                        y = el.floatOr("positionY", 0f),
                        w = el.floatOr("width", 0f),
                        h = el.floatOr("height", 0f),
                        filePath = el.string("filePath"),
                        contentText = el.string("contentText"),
                        html = el.string("html"),
                    ))
                }
            }
        }

        return HinotePageEntry(
            id = pageId,
            pageNumber = page.intOr("pageNumber", Int.MAX_VALUE - 1),
            background = page.string("background") ?: "base1",
            createTime = page.longOr("createTime", 0),
            modifiedTime = page.longOr("modifiedTime", 0),
            bookmarked = marks != null && marks.isJsonArray && marks.asJsonArray.size() > 0,
            bin = bin,
            broken = false,
            elements = elements,
        )
    }

    private fun noteContent(gz: ByteArray): JsonObject? = gunzipJson(gz)?.let { root ->
        root.get("customNoteContent")?.takeIf { it.isJsonObject }?.asJsonObject
    }

    private fun pageContent(gz: ByteArray): JsonObject? = gunzipJson(gz)?.let { root ->
        root.get("customNotePageContent")?.takeIf { it.isJsonObject }?.asJsonObject
    }

    /** gzip -> JSON-Objekt; null bei jedem Fehler (kein gzip, zu groß, kein JSON). */
    private fun gunzipJson(gz: ByteArray): JsonObject? = try {
        val raw = GZIPInputStream(ByteArrayInputStream(gz)).use { PnoteCodec.readLimited(it, MAX_JSON_BYTES.toLong(), true).bytes }
        JsonParser.parseString(String(raw, UTF_8)).takeIf { it.isJsonObject }?.asJsonObject
    } catch (e: IOException) {
        null
    } catch (e: PnoteException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    private fun JsonObject.primitive(name: String): JsonElement? = get(name)?.takeIf { it.isJsonPrimitive }

    private fun JsonObject.string(name: String): String? = primitive(name)?.takeIf { it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.intOr(name: String, default: Int): Int =
        try {
            primitive(name)?.asInt ?: default
        } catch (e: RuntimeException) {
            default
        }

    private fun JsonObject.floatOr(name: String, default: Float): Float =
        try {
            primitive(name)?.asFloat ?: default
        } catch (e: RuntimeException) {
            default
        }

    private fun JsonObject.longOr(name: String, default: Long): Long =
        try {
            primitive(name)?.asLong ?: default
        } catch (e: RuntimeException) {
            default
        }
}
