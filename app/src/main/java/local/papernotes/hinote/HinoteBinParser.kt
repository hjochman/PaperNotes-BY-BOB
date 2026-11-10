package local.papernotes.hinote

import local.papernotes.storage.PnoteCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Ein Punkt eines HiNote-Strichs in Koordinaten der `.bin` (Breite [HinoteBin.width], meist 1000). [timeMs] relativ zum Strichanfang. */
class HinotePoint(val x: Float, val y: Float, val timeMs: Int, val pressure: Float)

/**
 * Ein Strich der `.bin`. Farbe als Float 0..1 (Rohwerte). [uuidHex] verweist aus den Gruppen des Abschlussblocks;
 * [linkIndex] -1 = normaler Strich.
 */
class HinoteStroke(
    val uuidHex: String,
    val linkIndex: Int,
    val brushType: Int,
    val red: Float,
    val green: Float,
    val blue: Float,
    val alpha: Float,
    val width: Float,
    val points: List<HinotePoint>,
)

/** Inhalt einer `files/<pageId>.bin`. [hiddenUuids] = Striche, die in Abschlussgruppen stehen. */
class HinoteBin(
    val width: Int,
    val height: Int,
    val strokes: List<HinoteStroke>,
    val hiddenUuids: Set<String>,
) {
    /** Striche, die HiNote als normale Striche lädt: `linkIndex == -1` und in keiner Gruppe (Doku Abschnitt 2.4/2.5). */
    fun visibleStrokes(): List<HinoteStroke> = strokes.filter { it.linkIndex == -1 && it.uuidHex !in hiddenUuids }
}

/**
 * Strenger Leser für `PENCILENGINE` und das Altformat `HWEINKBYTE` (beide Big-Endian), Kotlin-Fassung von
 * `tools/hinote_bin.py`; Aufbau und Belege stehen in `docs/hinote-bin-format.md` Abschnitt 2 und 2.6. Jedes Größenfeld
 * wird gegen die Dateilänge geprüft, bevor etwas angelegt wird; nach dem Abschlussblock muss die Datei exakt enden.
 * Wirft nie nach außen, sondern liefert [HinoteResult.Err].
 *
 * Das Altformat liefert dasselbe [HinoteBin]: Koordinaten `x·Breite`, `y·Höhe` (Kopfmaße, meist 1404 x 1677/1460),
 * ARGB-Int als Float-Kanäle, Zeit relativ zum ersten Punkt, keine Gruppen, `linkIndex` -1.
 */
object HinoteBinParser {
    const val MAX_BIN_BYTES = PnoteCodec.MAX_ENTRY_BYTES.toInt()
    const val MAX_STROKES = PnoteCodec.MAX_STROKES_PER_PAGE
    const val MAX_POINTS_PER_STROKE = PnoteCodec.MAX_POINTS_PER_STROKE
    private const val MAX_PAGE_DIMENSION = 100_000

    private val MAGIC = "PENCILENGINE".toByteArray(Charsets.US_ASCII)
    private val LEGACY_MAGIC = "HWEINKBYTE".toByteArray(Charsets.US_ASCII)
    private const val FILE_HEADER_SIZE = 76
    private const val PAGE_HEADER_SIZE = 48
    private const val BASIC_CONTENT_SIZE = 12
    private const val STROKE_HEADER_SIZE = 48
    private const val STROKE_BASIC_SIZE = 24
    private const val STROKE_BRUSH_SIZE = 48
    private const val POINT_BLOCK_HEAD = 20
    private const val POINT_SIZE = 36
    private const val TRAILER_SIZE = 40
    private const val GROUP_HEAD_SIZE = 16
    private const val UUID_SIZE = 16

    // Altformat HWEINKBYTE (Doku 2.6)
    private const val LEGACY_HEADER_SIZE = 28
    private const val LEGACY_SECTION_TABLE = 32
    private const val LEGACY_SECTION_HEAD = 44
    private const val LEGACY_SECTION_HEAD_VALUE = 28
    private const val LEGACY_SECTION_RECORD_SIZE = 16
    private const val LEGACY_STROKE_HEAD = 36
    private const val LEGACY_POINT_SIZE = 28

    /** Kleinster möglicher Strich (null Punkte): Kopf + Grundwerte + Pinsel + Punktblockkopf. */
    private const val MIN_STROKE_SIZE = STROKE_HEADER_SIZE + STROKE_BASIC_SIZE + STROKE_BRUSH_SIZE + POINT_BLOCK_HEAD

    fun parse(bytes: ByteArray): HinoteResult<HinoteBin> = try {
        HinoteResult.Ok(parseStrict(bytes))
    } catch (e: HinoteException) {
        HinoteResult.Err(e.error)
    } catch (e: RuntimeException) {
        HinoteResult.Err(HinoteError.Inconsistent)
    }

    private fun fail(error: HinoteError): Nothing = throw HinoteException(error)

    private fun parseStrict(bytes: ByteArray): HinoteBin {
        if (bytes.size > MAX_BIN_BYTES) fail(HinoteError.LimitExceeded)
        return when {
            startsWith(bytes, MAGIC) -> parseCurrent(bytes)
            startsWith(bytes, LEGACY_MAGIC) -> parseLegacy(bytes)
            else -> fail(HinoteError.BadMagic)
        }
    }

    private fun startsWith(bytes: ByteArray, magic: ByteArray): Boolean =
        bytes.size >= magic.size && magic.indices.all { bytes[it] == magic[it] }

    private fun parseCurrent(bytes: ByteArray): HinoteBin {
        val d = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val len = bytes.size.toLong()

        fun need(off: Long, n: Long) {
            if (off < 0 || n < 0 || off + n > len) fail(HinoteError.Truncated)
        }

        fun int(off: Long): Int {
            need(off, 4)
            return d.getInt(off.toInt())
        }

        fun float(off: Long): Float {
            need(off, 4)
            return d.getFloat(off.toInt())
        }

        // Dateikopf
        need(0, FILE_HEADER_SIZE.toLong())
        if (int(12) != FILE_HEADER_SIZE) fail(HinoteError.Inconsistent)

        // Seitenkopf
        val ph = FILE_HEADER_SIZE.toLong()
        need(ph, PAGE_HEADER_SIZE.toLong())
        val dataSize = int(ph + 12)
        val basicSize = int(ph + 32)
        val strokeCount = int(ph + 36)
        val strokesSize = int(ph + 40)
        if (int(ph) != PAGE_HEADER_SIZE || basicSize != BASIC_CONTENT_SIZE) fail(HinoteError.Inconsistent)
        if (strokeCount > MAX_STROKES) fail(HinoteError.LimitExceeded)
        if (strokeCount < 0 || strokesSize < BASIC_CONTENT_SIZE) fail(HinoteError.Inconsistent)

        // Grundinhalt
        val body = ph + PAGE_HEADER_SIZE
        need(body, BASIC_CONTENT_SIZE.toLong())
        val width = int(body + 4)
        val height = int(body + 8)
        if (width <= 0 || height <= 0 || width > MAX_PAGE_DIMENSION || height > MAX_PAGE_DIMENSION) fail(HinoteError.Inconsistent)

        // Anzahl vor jeder Allokation gegen die Restlänge prüfen
        var p = body + BASIC_CONTENT_SIZE
        if (strokeCount.toLong() * MIN_STROKE_SIZE > len - p) fail(HinoteError.Truncated)
        val strokesEnd = body + strokesSize

        val strokes = ArrayList<HinoteStroke>(minOf(strokeCount, 4096))
        for (i in 0 until strokeCount) {
            need(p, STROKE_HEADER_SIZE.toLong())
            val shSize = int(p)
            val sData = int(p + 12)
            val bSize = int(p + 32)
            val brSize = int(p + 36)
            val headOff = int(p + 40)
            val ptsSize = int(p + 44)
            if (shSize != STROKE_HEADER_SIZE || bSize != STROKE_BASIC_SIZE || brSize != STROKE_BRUSH_SIZE) {
                fail(HinoteError.Inconsistent)
            }
            if (headOff != bSize + brSize || ptsSize < POINT_BLOCK_HEAD || sData.toLong() != headOff.toLong() + ptsSize) {
                fail(HinoteError.Inconsistent)
            }
            need(p, shSize.toLong() + sData)
            val q = p + shSize
            val link = int(q + 20)
            val brushType = int(q + bSize)
            val blue = float(q + bSize + 8)
            val green = float(q + bSize + 12)
            val red = float(q + bSize + 16)
            val alpha = float(q + bSize + 24)
            val strokeWidth = float(q + bSize + 28)
            val c = q + headOff
            val pointCount = int(c + 4)
            val pointSize = int(c + 8)
            if (pointSize != POINT_SIZE || pointCount < 0 || pointCount > MAX_POINTS_PER_STROKE ||
                ptsSize.toLong() != POINT_BLOCK_HEAD + pointCount.toLong() * POINT_SIZE
            ) {
                fail(HinoteError.Inconsistent)
            }
            val points = ArrayList<HinotePoint>(pointCount)
            var o = (c + POINT_BLOCK_HEAD).toInt()
            for (k in 0 until pointCount) {
                val x = d.getFloat(o)
                val y = d.getFloat(o + 4)
                val pressure = d.getFloat(o + 12)
                if (!x.isFinite() || !y.isFinite() || !pressure.isFinite()) fail(HinoteError.Inconsistent)
                points.add(HinotePoint(x, y, d.getInt(o + 8), pressure))
                o += POINT_SIZE
            }
            strokes.add(HinoteStroke(hex(bytes, (p + 16).toInt()), link, brushType, red, green, blue, alpha, strokeWidth, points))
            p += shSize.toLong() + sData
        }
        if (p != strokesEnd) fail(HinoteError.Inconsistent)

        // Abschlussblock mit Gruppen
        need(p, TRAILER_SIZE.toLong())
        if (int(p) != TRAILER_SIZE) fail(HinoteError.Inconsistent)
        val groupCount = int(p + 36)
        var q = p + TRAILER_SIZE
        if (groupCount < 0 || groupCount.toLong() * GROUP_HEAD_SIZE > len - q) fail(HinoteError.Truncated)
        val hidden = HashSet<String>()
        repeat(groupCount) {
            need(q, GROUP_HEAD_SIZE.toLong())
            val count = int(q)
            val entry = int(q + 4)
            q += GROUP_HEAD_SIZE
            if (entry < UUID_SIZE + 4 || count < 0) fail(HinoteError.Inconsistent)
            need(q, count.toLong() * entry)
            for (k in 0 until count) hidden.add(hex(bytes, (q + k.toLong() * entry).toInt()))
            q += count.toLong() * entry
        }
        if (q < len) fail(HinoteError.TrailingBytes)
        if (dataSize.toLong() != len - FILE_HEADER_SIZE - PAGE_HEADER_SIZE) fail(HinoteError.Inconsistent)
        return HinoteBin(width, height, strokes, hidden)
    }

    /** Lesehilfe für das Altformat: jede Position wird gegen die Dateilänge geprüft. */
    private class Reader(bytes: ByteArray) {
        private val d = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val len = bytes.size.toLong()

        fun need(off: Long, n: Long) {
            if (off < 0 || n < 0 || off + n > len) throw HinoteException(HinoteError.Truncated)
        }

        fun int(off: Long): Int {
            need(off, 4)
            return d.getInt(off.toInt())
        }

        fun long(off: Long): Long {
            need(off, 8)
            return d.getLong(off.toInt())
        }

        fun float(off: Long): Float {
            need(off, 4)
            return d.getFloat(off.toInt())
        }
    }

    /**
     * `HWEINKBYTE` (Doku 2.6): Kopf 28 B, ein Abschnitt "Striche" bis Dateiende, je Strich 36 B Kopf + 28 B je Punkt.
     * x, y sind normiert 0..1 und werden mit Kopfbreite/-höhe multipliziert; die Zeit (absolut, i64) wird relativ zum
     * ersten Punkt des Strichs.
     */
    private fun parseLegacy(bytes: ByteArray): HinoteBin {
        val r = Reader(bytes)
        r.need(0, LEGACY_SECTION_TABLE + 8L)
        val headerSize = r.int(12)
        val sectionCount = r.int(28)
        if (headerSize != LEGACY_HEADER_SIZE || sectionCount != 1) fail(HinoteError.Inconsistent)
        val sectionOff = r.int(LEGACY_SECTION_TABLE.toLong()).toLong()
        val sectionLen = r.int(LEGACY_SECTION_TABLE + 4L).toLong()
        if (sectionOff != headerSize + 12L || sectionLen < LEGACY_SECTION_HEAD) fail(HinoteError.Inconsistent)
        val end = sectionOff + sectionLen
        if (end > r.len) fail(HinoteError.Truncated)
        if (end < r.len) fail(HinoteError.TrailingBytes)

        r.need(sectionOff, LEGACY_SECTION_HEAD.toLong())
        if (r.int(sectionOff) != LEGACY_SECTION_HEAD_VALUE || r.int(sectionOff + 28) != LEGACY_SECTION_RECORD_SIZE) {
            fail(HinoteError.Inconsistent)
        }
        val width = r.int(sectionOff + 12)
        val height = r.int(sectionOff + 16)
        val strokeCount = r.int(sectionOff + 32)
        if (width <= 0 || height <= 0 || width > MAX_PAGE_DIMENSION || height > MAX_PAGE_DIMENSION) fail(HinoteError.Inconsistent)
        if (strokeCount > MAX_STROKES) fail(HinoteError.LimitExceeded)
        if (strokeCount < 0) fail(HinoteError.Inconsistent)

        var p = sectionOff + LEGACY_SECTION_HEAD
        // Anzahl vor jeder Allokation gegen die Restlänge prüfen
        if (strokeCount.toLong() * LEGACY_STROKE_HEAD > r.len - p) fail(HinoteError.Truncated)
        val strokes = ArrayList<HinoteStroke>(minOf(strokeCount, 4096))
        for (i in 0 until strokeCount) {
            r.need(p, LEGACY_STROKE_HEAD.toLong())
            val pointCount = r.int(p)
            if (pointCount < 0) fail(HinoteError.Inconsistent)
            if (pointCount > MAX_POINTS_PER_STROKE) fail(HinoteError.LimitExceeded)
            val brushType = r.int(p + 8)
            val argb = r.int(p + 12)
            val strokeWidth = r.float(p + 16)
            if (!strokeWidth.isFinite()) fail(HinoteError.Inconsistent)
            p += LEGACY_STROKE_HEAD
            r.need(p, pointCount.toLong() * LEGACY_POINT_SIZE)
            val points = ArrayList<HinotePoint>(pointCount)
            var t0 = 0L
            for (k in 0 until pointCount) {
                val x = r.float(p + 4)
                val y = r.float(p + 8)
                val pressure = r.float(p + 12)
                val time = r.long(p + 20)
                if (!x.isFinite() || !y.isFinite() || !pressure.isFinite()) fail(HinoteError.Inconsistent)
                if (k == 0) t0 = time
                val rel = time - t0
                if (rel < 0 || rel > Int.MAX_VALUE) fail(HinoteError.Inconsistent)
                points.add(HinotePoint(x * width, y * height, rel.toInt(), pressure))
                p += LEGACY_POINT_SIZE
            }
            strokes.add(
                HinoteStroke(
                    uuidHex = "", linkIndex = -1, brushType = brushType,
                    red = ((argb ushr 16) and 0xFF) / 255f, green = ((argb ushr 8) and 0xFF) / 255f,
                    blue = (argb and 0xFF) / 255f, alpha = ((argb ushr 24) and 0xFF) / 255f,
                    width = strokeWidth, points = points,
                ),
            )
        }
        if (p != end) fail(HinoteError.Inconsistent)
        return HinoteBin(width, height, strokes, emptySet())
    }

    private fun hex(bytes: ByteArray, off: Int): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(UUID_SIZE * 2)
        for (i in off until off + UUID_SIZE) {
            val b = bytes[i].toInt() and 0xFF
            sb.append(digits[b shr 4]).append(digits[b and 15])
        }
        return sb.toString()
    }
}
