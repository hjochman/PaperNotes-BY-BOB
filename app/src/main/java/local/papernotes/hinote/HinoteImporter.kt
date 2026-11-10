package local.papernotes.hinote

import local.papernotes.editor.PenCatalog
import local.papernotes.editor.TemplateCatalog
import local.papernotes.model.Note
import local.papernotes.model.NoteFactory
import local.papernotes.model.Page
import local.papernotes.model.Point
import local.papernotes.model.Stroke
import local.papernotes.model.Textbox
import local.papernotes.model.ImageAttachment
import local.papernotes.storage.NoteTitles
import java.util.UUID

/** Ergebnis der Umwandlung einer Notiz; [warnings] = Seiten, die leer angelegt werden mussten (unlesbar). */
class ImportedNote(val note: Note, val warnings: Int)

/**
 * Wandelt einen gelesenen HiNote-Export ([HinoteContainer]) in eine PaperNotes-[Note] um. Mapping nach
 * `docs/hinote-bin-format.md` Abschnitt 6. Reine Logik, keine Dateizugriffe.
 */
object HinoteImporter {
    /** Zielordner in der Wurzel, damit der Nutzer das Ergebnis prüfen und dann verschieben kann. */
    const val TARGET_FOLDER = "HiNote-Import"

    /** Präfix der Herkunftskennung im Manifest (`importSource`). */
    const val SOURCE_PREFIX = "hinote:"

    /**
     * Faktor auf die Strichbreite aus der `.bin` (Doku: Einheit "Pixel einer 1404 px breiten Ansicht", genauer Faktor
     * offen; 1,0 liefert laut Doku etwa 1,1-fache Tintenmenge der Vorschau). Am Gerät kalibrierbar.
     */
    const val STROKE_WIDTH_FACTOR = 1.0f

    /** Seitenbreite in `.bin`-Koordinaten wird auf die PaperNotes-Seitenbreite abgebildet; y nutzt denselben Maßstab, oben ausgerichtet. */
    private const val PAGE_WIDTH = NoteFactory.DEFAULT_PAGE_WIDTH
    private const val PAGE_HEIGHT = NoteFactory.DEFAULT_PAGE_HEIGHT

    /**
     * HiNote-Vorlagen-ID -> `TemplateCatalog`-ID (Doku Tabelle 1.3; belegt nur `base1`, `base6`, `plan2`, `plan6`, der Rest
     * ist Annahme nach deutschem Anzeigenamen). Alle 22 HiNote-Vorlagen haben eine eigene Entsprechung; nur unbekannte IDs
     * werden `blank`.
     */
    private val TEMPLATES = mapOf(
        "base1" to "blank",
        "base4" to "lines_wide",
        "base5" to "lines_narrow",
        "base6" to "dotted",
        "base3" to "grid",
        "base2" to "grid",
        "handwrite1" to "handwriting",
        "handwrite2" to "millimeter",
        "handwrite4" to "letter",
        "plan2" to "todo",
        "plan3" to "meeting",
        "plan4" to "weekly",
        "plan5" to "monthly",
        "plan1" to "tasks",
        "learning7" to "error_log",
        "learning2" to "reading_notes",
        "learning3" to "cornell",
        "handwrite3" to "character_grid",
        "learning6" to "staff",
        "plan6" to "time_management",
        "learning4" to "point_sheet",
        "learning5" to "swot",
    )

    fun templateFor(hinoteBackground: String): String {
        val id = TEMPLATES[hinoteBackground] ?: return TemplateCatalog.DEFAULT_ID
        return if (TemplateCatalog.isKnown(id)) id else TemplateCatalog.DEFAULT_ID
    }

    /**
     * Pinseltyp der Datei -> `PenCatalog`-ID. Belegt: 2 (Kugelschreiber) in allen Proben.
     * Angenommen: 1 -> Füllfeder (Framework 4), 3 -> Bleistift, 4/5 -> Marker (Framework 6). Annahmen: 11 (2B) -> Bleistift (vom Nutzer akzeptiert),
     * 12/13 (Beauty, Chinese Writing) -> Füllfeder. Unbekannte Werte -> Kugelschreiber.
     */
    private fun penIdFor(brushType: Int): String = when (brushType) {
        1, 12, 13 -> "fountain"
        2 -> "ballpoint"
        3, 11 -> "pencil"
        4, 5 -> "marker"
        else -> "ballpoint"
    }

    fun penTypeFor(brushType: Int): Int {
        val id = penIdFor(brushType)
        return (PenCatalog.pens.firstOrNull { it.id == id } ?: PenCatalog.default).frameworkType
    }

    /** Float-BGR(A) 0..1 -> ARGB-Int; der Pinselwert "Intensität" wird ignoriert (Doku Abschnitt 6). */
    fun argb(alpha: Float, red: Float, green: Float, blue: Float): Int {
        fun c(v: Float) = Math.round(v.coerceIn(0f, 1f) * 255f)
        return (c(alpha) shl 24) or (c(red) shl 16) or (c(green) shl 8) or c(blue)
    }

    fun importNote(container: HinoteContainer, folderPath: String, fallbackTitle: String, now: Long): ImportedNote {
        var warnings = 0
        val pages = LinkedHashMap<String, Page>()
        val noteMedia = HashMap<String, ByteArray>()

        for (entry in container.pages) {
            val id = UUID.randomUUID().toString()
            var strokes: List<Stroke> = emptyList()
            var template = TemplateCatalog.DEFAULT_ID
            val pageTextboxes = ArrayList<Textbox>()
            val pageImages = ArrayList<ImageAttachment>()

            if (entry.broken) {
                warnings++
            } else {
                template = templateFor(entry.background.ifBlank { container.background })
                val bytes = entry.bin
                if (bytes != null) {
                    when (val r = HinoteBinParser.parse(bytes)) {
                        is HinoteResult.Ok -> strokes = convertStrokes(r.value)
                        is HinoteResult.Err -> warnings++
                    }
                }

                // Parse and map page elements
                for (el in entry.elements) {
                    if (el.type == 0) { // Textbox
                        // Auszeichnung aus dem HiNote-Markup; ohne lesbares Markup bleibt der Klartext.
                        val rich = el.html?.let { HinoteHtml.parse(it) }?.takeIf { it.text.isNotEmpty() }
                        val tb = Textbox(
                            id = UUID.randomUUID().toString(),
                            text = rich?.text ?: el.contentText.orEmpty(),
                            x = el.x * PAGE_WIDTH,
                            y = el.y * PAGE_HEIGHT,
                            width = el.w * PAGE_WIDTH,
                            height = el.h * PAGE_HEIGHT,
                            fontSize = PAGE_WIDTH * 0.03f,
                            spans = rich?.spans.orEmpty(),
                            paragraphs = rich?.paragraphs.orEmpty(),
                        )
                        pageTextboxes.add(tb)
                    } else if (el.type == 1) { // Picture
                        val originalName = el.filePath?.substringAfterLast('/') ?: ""
                        val bytes = container.images[originalName]
                        if (bytes != null) {
                            val ext = originalName.substringAfterLast('.', "jpg")
                            val newName = "${UUID.randomUUID()}.$ext"
                            noteMedia[newName] = bytes
                            val img = ImageAttachment(
                                id = UUID.randomUUID().toString(),
                                filePath = newName,
                                x = el.x * PAGE_WIDTH,
                                y = el.y * PAGE_HEIGHT,
                                width = el.w * PAGE_WIDTH,
                                height = el.h * PAGE_HEIGHT
                            )
                            pageImages.add(img)
                        }
                    }
                }
            }
            pages[id] = Page(
                id = id,
                template = template,
                width = PAGE_WIDTH,
                height = PAGE_HEIGHT,
                strokes = strokes,
                bookmarked = entry.bookmarked && !entry.broken,
                textboxes = pageTextboxes,
                images = pageImages
            )
        }
        if (pages.isEmpty()) {
            val id = UUID.randomUUID().toString()
            pages[id] = Page(id, TemplateCatalog.DEFAULT_ID, PAGE_WIDTH, PAGE_HEIGHT, emptyList(), false)
        }
        val created = container.pages.filter { !it.broken && it.createTime > 0 }.minOfOrNull { it.createTime } ?: now
        val modified = container.pages.filter { !it.broken && it.modifiedTime > 0 }.maxOfOrNull { it.modifiedTime } ?: created
        val title = NoteTitles.sanitize(container.title).ifEmpty { fallbackTitle }
        val note = Note(
            id = UUID.randomUUID().toString(),
            title = title,
            folderPath = folderPath,
            created = created,
            modified = maxOf(created, modified),
            favorite = container.favorite,
            pinned = container.pinned,
            pageIds = pages.keys.toList(),
            pages = pages,
            importSource = SOURCE_PREFIX + container.noteId,
            media = noteMedia,
        )
        return ImportedNote(note, warnings)
    }

    /** Striche mit `link_index != -1` oder in Abschlussgruppen entfallen (wie HiNote, Doku Abschnitt 2.4/2.5). */
    private fun convertStrokes(bin: HinoteBin): List<Stroke> {
        val scale = PAGE_WIDTH.toFloat() / bin.width
        val out = ArrayList<Stroke>()
        for (s in bin.visibleStrokes()) {
            if (s.points.isEmpty()) continue
            out.add(
                Stroke(
                    penType = penTypeFor(s.brushType),
                    width = s.width * STROKE_WIDTH_FACTOR,
                    color = argb(s.alpha, s.red, s.green, s.blue),
                    // Zeit bleibt relativ zum Strichanfang (die App speichert absolute Zeit, die Bedeutung ist für Darstellung egal).
                    points = s.points.map { Point(it.x * scale, it.y * scale, it.timeMs.toLong(), it.pressure.coerceIn(0f, 1f)) },
                ),
            )
        }
        return out
    }
}
