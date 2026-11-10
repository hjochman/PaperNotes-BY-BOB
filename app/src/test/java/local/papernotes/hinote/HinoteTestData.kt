package local.papernotes.hinote

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Baut synthetische `.bin`- und `.hinote`-Dateien für Tests (Aufbau: docs/hinote-bin-format.md, eigener Code). */
internal object HinoteTestData {
    class P(val x: Float, val y: Float, val t: Int = 0, val pressure: Float = 0.5f)

    class S(
        val points: List<P>,
        val brush: Int = 2,
        val r: Float = 0f,
        val g: Float = 0f,
        val b: Float = 0f,
        val alpha: Float = 1f,
        val width: Float = 2f,
        val link: Int = -1,
        val uuid: ByteArray = ByteArray(16) { (it + 1).toByte() },
    )

    fun bin(strokes: List<S>, groups: List<List<ByteArray>> = emptyList(), pageW: Int = 1000, pageH: Int = 1195): ByteArray {
        val strokeBytes = strokes.map { stroke(it) }
        val groupBytes = groups.map { group(it) }
        val strokesSize = 12 + strokeBytes.sumOf { it.size }
        val trailer = trailer(groups.size)
        val total = 76 + 48 + strokesSize + trailer.size + groupBytes.sumOf { it.size }
        val out = buf(total)
        out.put("PENCILENGINE".toByteArray())
        out.putInt(76)
        out.put(ByteArray(76 - 16))
        // Seitenkopf
        out.putInt(48).putInt(2).putShort(1).putShort(0).putInt(total - 124)
        out.put(ByteArray(16) { 9 })
        out.putInt(12).putInt(strokes.size).putInt(strokesSize).putInt(1)
        // Grundinhalt
        out.putInt(0).putInt(pageW).putInt(pageH)
        strokeBytes.forEach { out.put(it) }
        out.put(trailer)
        groupBytes.forEach { out.put(it) }
        check(out.position() == total)
        return out.array()
    }

    /** Punkt des Altformats: x, y normiert 0..1, [t] absolute Zeit (ms). */
    class LP(val x: Float, val y: Float, val t: Long = 0, val pressure: Float = 0.5f)

    class LS(val points: List<LP>, val brush: Int = 2, val argb: Int = 0xFF000000.toInt(), val width: Float = 2f)

    /** Altformat `HWEINKBYTE` (Doku 2.6). */
    fun legacyBin(strokes: List<LS>, w: Int = 1404, h: Int = 1677): ByteArray {
        val sectionLen = 44 + strokes.sumOf { 36 + 28 * it.points.size }
        val out = buf(40 + sectionLen)
        out.put("HWEINKBYTE".toByteArray()).put(ByteArray(2))
        out.putInt(28).putInt(0).putInt(0).putShort(1).putShort(1).putInt(1)
        out.putInt(40).putInt(sectionLen)
        // Abschnittskopf (44 B)
        out.putInt(28).putLong(1_700_000_000_000L).putInt(w).putInt(h).putInt(0).putInt(0).putInt(16).putInt(strokes.size).putInt(0).putInt(0)
        for (s in strokes) {
            out.putInt(s.points.size).putShort(1).putShort(1).putInt(s.brush).putInt(s.argb).putFloat(s.width)
            out.putFloat(0f).putFloat(0f).putFloat(1f).putFloat(1f)
            s.points.forEachIndexed { i, p ->
                out.putInt(if (i == 0) 0 else if (i == s.points.size - 1) 1 else 2)
                out.putFloat(p.x).putFloat(p.y).putFloat(p.pressure).putFloat(0f).putLong(p.t)
            }
        }
        check(out.position() == out.capacity())
        return out.array()
    }

    private fun buf(n: Int): ByteBuffer = ByteBuffer.allocate(n).order(ByteOrder.BIG_ENDIAN)

    private fun stroke(s: S): ByteArray {
        val n = s.points.size
        val ptsSize = 20 + 36 * n
        val out = buf(48 + 72 + ptsSize)
        out.putInt(48).putInt(0).putShort(1).putShort(0).putInt(72 + ptsSize)
        out.put(s.uuid)
        out.putInt(24).putInt(48).putInt(72).putInt(ptsSize)
        // Grundwerte
        out.putLong(0).putFloat(0f).putFloat((n - 1).toFloat()).put(0).put(0).put(0).put(0).putInt(s.link)
        // Pinsel
        out.putInt(s.brush).putInt(0).putFloat(s.b).putFloat(s.g).putFloat(s.r).putFloat(1f).putFloat(s.alpha).putFloat(s.width)
        out.putInt(0).put(0).put(ByteArray(3)).put(ByteArray(8))
        // Punktblock
        out.putInt(0).putInt(n).putInt(36).put(ByteArray(8))
        s.points.forEachIndexed { i, p ->
            out.putFloat(p.x).putFloat(p.y).putInt(p.t).putFloat(p.pressure).putFloat(0f).putFloat(0f).putFloat(0f)
            out.putInt(if (i == 0) 4 else if (i == n - 1) 5 else 6).putFloat(i.toFloat())
        }
        check(out.position() == out.capacity())
        return out.array()
    }

    private fun trailer(groups: Int): ByteArray {
        val out = buf(40)
        out.putInt(40).putInt(3).putShort(1).putShort(0).putInt(0).put(ByteArray(16)).putInt(0).putInt(groups)
        return out.array()
    }

    private fun group(uuids: List<ByteArray>): ByteArray {
        val out = buf(16 + 20 * uuids.size)
        out.putInt(uuids.size).putInt(20).put(ByteArray(8))
        uuids.forEach { out.put(it).putInt(0) }
        return out.array()
    }

    fun gzip(json: String): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(json.toByteArray()) }
        return bos.toByteArray()
    }

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

    const val NOTE_ID = "4ff509b425b1431b8a379d5205bf500f"
    const val PAGE1 = "d495cc51037f4957b6aa7fc55b8c26a9"
    const val PAGE2 = "5d2b94c772a841f7ac907581675b31b9"
    const val PAGE3 = "14b6c64862c749e09ba4bd73d2ffa0e0"

    fun noteJson(title: String = "Testnotiz", background: String = "base1", fav: Int = 0, top: Int = 0, id: String = NOTE_ID) =
        """{"customNoteContent":{"id":"$id","noteTitle":"$title","background":"$background","isFavorite":$fav,"isTop":$top,"createTime":0,"modifiedTime":0},"fileList":[]}"""

    fun pageJson(id: String, number: Int, background: String = "base1", created: Long = 1000, modified: Long = 2000, marks: String = "[]", elements: String = "[]") =
        """{"customNotePageContent":{"id":"$id","pageNumber":$number,"background":"$background","createTime":$created,"modifiedTime":$modified,"pageBookMarks":$marks,"pageElement":$elements},"fileList":[]}"""

    /**
     * Export mit Seite 1 (Striche, Lesezeichen), Seite 2 (`plan2`) und Seite 3 (`base6`) ohne `.bin`;
     * Einträge stehen absichtlich in falscher Reihenfolge (Sortierung nach `pageNumber`).
     */
    fun hinote(
        bin1: ByteArray = bin(listOf(S(listOf(P(0f, 0f), P(500f, 100f, 10), P(1000f, 200f, 20))))),
        extra: List<Pair<String, ByteArray>> = emptyList(),
        noteId: String = NOTE_ID,
        title: String = "Testnotiz",
    ): ByteArray = zip(
        listOf(
            "$noteId.jhinote" to gzip(noteJson(title = title, id = noteId, fav = 1)),
            "custom_md.jhinote" to gzip("""{"customMdContents":[]}"""),
            "pages/" to ByteArray(0),
            "pages/$PAGE3.jhinote" to gzip(pageJson(PAGE3, 3, "base6", 5000, 9000)),
            "pages/$PAGE2.jhinote" to gzip(pageJson(PAGE2, 2, "plan2")),
            "pages/$PAGE1.jhinote" to gzip(pageJson(PAGE1, 1, "base1", 3000, 4000, """[{"bookMarkName":"x"}]""")),
            "files/" to ByteArray(0),
            "files/$PAGE1.bin" to bin1,
            "files/$PAGE1.jpg" to byteArrayOf(1, 2, 3),
        ) + extra,
    )
}
