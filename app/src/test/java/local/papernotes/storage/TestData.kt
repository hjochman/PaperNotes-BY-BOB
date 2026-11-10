package local.papernotes.storage

import local.papernotes.model.Note
import local.papernotes.model.NoteFactory
import local.papernotes.model.Page
import local.papernotes.model.Point
import local.papernotes.model.Stroke
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object TestData {
    fun uuid(): String = UUID.randomUUID().toString()

    /** Notiz mit [pages] Seiten, je [strokesPerPage] Strichen mit [points] Punkten (deterministisch per [seed]). */
    fun bigNote(pages: Int = 3, strokesPerPage: Int = 4, points: Int = 500, seed: Long = 42, folder: String = ""): Note {
        val rnd = Random(seed)
        val ids = List(pages) { uuid() }
        val map = LinkedHashMap<String, Page>()
        ids.forEachIndexed { i, id ->
            val strokes = List(strokesPerPage) { s ->
                Stroke(
                    penType = s % 4,
                    width = 1f + rnd.nextFloat() * 5f,
                    color = if (s % 2 == 0) 0xFF000000.toInt() else rnd.nextInt(),
                    points = List(points) {
                        Point(
                            rnd.nextFloat() * 1404f,
                            rnd.nextFloat() * 1872f,
                            1_700_000_000_000L + rnd.nextInt(1_000_000),
                            rnd.nextFloat(),
                        )
                    },
                )
            }
            map[id] = Page(id, listOf("blank", "lines_wide", "grid")[i % 3], 1404, 1872, strokes, i == 1)
        }
        return Note(
            id = uuid(),
            title = "Größe Übung – Äpfel",
            folderPath = folder,
            created = 1_700_000_000_000L,
            modified = 1_700_000_123_456L,
            favorite = true,
            pinned = false,
            pageIds = ids,
            pages = map,
        )
    }

    fun simpleNote(folder: String = "", title: String = "T"): Note = NoteFactory.newNote(title, folder)

    fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    fun manifest(
        id: String = uuid(),
        pageIds: List<String> = emptyList(),
        version: Int = 1,
        extra: String = "",
        folder: String = "",
    ): ByteArray {
        val ids = pageIds.joinToString(",") { "\"$it\"" }
        return ("""{"version":$version,"id":"$id","title":"x","folderPath":"$folder","created":1,"modified":2,""" +
            """"favorite":false,"pinned":false,"pageIds":[$ids]$extra}""").toByteArray()
    }

    fun pageJson(extra: String = ""): ByteArray =
        ("""{"template":"blank","width":10,"height":20,"bookmarked":false,"strokes":[]$extra}""").toByteArray()

    fun encode(note: Note): ByteArray {
        val bos = ByteArrayOutputStream()
        check(PnoteCodec.encode(note, bos) is PnoteResult.Ok)
        return bos.toByteArray()
    }
}
