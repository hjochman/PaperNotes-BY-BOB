package local.papernotes.hinote

import local.papernotes.hinote.HinoteTestData.P
import local.papernotes.hinote.HinoteTestData.S
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class HinoteBinParserTest {
    private fun err(bytes: ByteArray) = HinoteBinParser.parse(bytes).errorOrNull()

    private val sample = HinoteTestData.bin(
        listOf(
            S(listOf(P(1f, 2f, 0, 0.25f), P(3f, 4f, 7, 0.5f), P(5f, 6f, 9, 1f)), brush = 3, r = 1f, g = 0.5f, b = 0f, alpha = 0.5f, width = 3.5f),
            S(listOf(P(10f, 20f), P(30f, 40f)), uuid = ByteArray(16) { 0x40 }),
        ),
    )

    @Test
    fun validFileIsParsedExactly() {
        val bin = HinoteBinParser.parse(sample).valueOrNull()!!
        assertEquals(1000, bin.width)
        assertEquals(1195, bin.height)
        assertEquals(2, bin.strokes.size)
        val s = bin.strokes[0]
        assertEquals(3, s.brushType)
        assertEquals(1f, s.red, 0f)
        assertEquals(0.5f, s.green, 0f)
        assertEquals(0.5f, s.alpha, 0f)
        assertEquals(3.5f, s.width, 0f)
        assertEquals(3, s.points.size)
        assertEquals(7, s.points[1].timeMs)
        assertEquals(0.25f, s.points[0].pressure, 0f)
        assertEquals(40f, bin.strokes[1].points[1].y, 0f)
        assertEquals("40".repeat(16), bin.strokes[1].uuidHex)
        assertEquals(2, bin.visibleStrokes().size)
    }

    @Test
    fun emptyPageIsValid() {
        assertEquals(0, HinoteBinParser.parse(HinoteTestData.bin(emptyList())).valueOrNull()!!.strokes.size)
    }

    @Test
    fun truncatedFileIsRejectedAtEveryLength() {
        for (n in listOf(0, 5, 11, 12, 40, 76, 100, 135, 200, sample.size - 41, sample.size - 1)) {
            val e = err(sample.copyOf(n))
            assertTrue("Länge $n: $e", e != null)
        }
    }

    @Test
    fun appendedByteIsRejected() {
        assertEquals(HinoteError.TrailingBytes, err(sample + byteArrayOf(0)))
    }

    @Test
    fun wrongMagicIsRejected() {
        val bad = sample.copyOf().also { it[0] = 'X'.code.toByte() }
        assertEquals(HinoteError.BadMagic, err(bad))
        assertEquals(HinoteError.BadMagic, err(ByteArray(3)))
    }

    @Test
    fun strokeCountLimitIsEnforcedBeforeAllocation() {
        val bad = sample.copyOf()
        ByteBuffer.wrap(bad).putInt(76 + 36, HinoteBinParser.MAX_STROKES + 1)
        assertEquals(HinoteError.LimitExceeded, err(bad))
        // Anzahl passt nicht zur Dateilänge (aber unter dem Limit): Abbruch ohne große Allokation
        val big = sample.copyOf()
        ByteBuffer.wrap(big).putInt(76 + 36, 150_000)
        assertEquals(HinoteError.Truncated, err(big))
    }

    @Test
    fun pointCountMismatchIsRejected() {
        val bad = sample.copyOf()
        // Punktzahl des ersten Strichs: Strichanfang 136, +48 Kopf +72 -> Punktblock, +4
        ByteBuffer.wrap(bad).putInt(136 + 48 + 72 + 4, Int.MAX_VALUE)
        assertEquals(HinoteError.Inconsistent, err(bad))
    }

    @Test
    fun nonFiniteCoordinateIsRejected() {
        val bad = sample.copyOf()
        ByteBuffer.wrap(bad).putFloat(136 + 48 + 72 + 20, Float.NaN)
        assertEquals(HinoteError.Inconsistent, err(bad))
    }

    @Test
    fun linkedAndGroupedStrokesAreHidden() {
        val grouped = ByteArray(16) { 0x22 }
        val bin = HinoteTestData.bin(
            listOf(
                S(listOf(P(1f, 1f), P(2f, 2f))),
                S(listOf(P(1f, 1f), P(2f, 2f)), link = 3, uuid = ByteArray(16) { 0x11 }),
                S(listOf(P(1f, 1f), P(2f, 2f)), uuid = grouped),
            ),
            groups = listOf(listOf(grouped)),
        )
        val parsed = HinoteBinParser.parse(bin).valueOrNull()!!
        assertEquals(3, parsed.strokes.size)
        assertEquals(1, parsed.visibleStrokes().size)
    }
}

class HinoteLegacyParserTest {
    private fun err(bytes: ByteArray) = HinoteBinParser.parse(bytes).errorOrNull()

    private val sample = HinoteTestData.legacyBin(
        listOf(
            HinoteTestData.LS(
                listOf(HinoteTestData.LP(0.5f, 0.25f, 15_710_188L, 0.25f), HinoteTestData.LP(0.75f, 0.5f, 15_710_200L), HinoteTestData.LP(1f, 1f, 15_710_250L, 1f)),
                brush = 2, argb = 0x80FF8000.toInt(), width = 4f,
            ),
            HinoteTestData.LS(listOf(HinoteTestData.LP(0.1f, 0.1f, 5L), HinoteTestData.LP(0.2f, 0.2f, 9L))),
        ),
    )

    @Test
    fun validLegacyFileIsParsedAndNormalised() {
        val bin = HinoteBinParser.parse(sample).valueOrNull()!!
        assertEquals(1404, bin.width)
        assertEquals(1677, bin.height)
        assertEquals(2, bin.visibleStrokes().size)
        val s = bin.strokes[0]
        assertEquals(2, s.brushType)
        assertEquals(4f, s.width, 0f)
        assertEquals(-1, s.linkIndex)
        assertEquals(0x80, Math.round(s.alpha * 255))
        assertEquals(0xFF, Math.round(s.red * 255))
        assertEquals(0x80, Math.round(s.green * 255))
        assertEquals(0, Math.round(s.blue * 255))
        assertEquals(0.5f * 1404, s.points[0].x, 0.01f) // x * Breite
        assertEquals(0.25f * 1677, s.points[0].y, 0.01f) // y * Höhe
        assertEquals(listOf(0, 12, 62), s.points.map { it.timeMs }) // relativ zum Strichanfang
        assertEquals(0.25f, s.points[0].pressure, 0f)
        assertEquals(0, bin.strokes[1].points[0].timeMs)
    }

    @Test
    fun emptyLegacyPageIsValid() {
        assertEquals(0, HinoteBinParser.parse(HinoteTestData.legacyBin(emptyList(), h = 1460)).valueOrNull()!!.strokes.size)
    }

    @Test
    fun truncatedLegacyFileIsRejectedAtEveryLength() {
        for (n in sample.indices) assertTrue("Länge $n", err(sample.copyOf(n)) != null)
    }

    @Test
    fun appendedByteIsRejected() {
        assertEquals(HinoteError.TrailingBytes, err(sample + byteArrayOf(0)))
    }

    @Test
    fun wrongMagicIsRejected() {
        assertEquals(HinoteError.BadMagic, err(sample.copyOf().also { it[3] = 'X'.code.toByte() }))
        assertEquals(HinoteError.BadMagic, err("HWEINKDATA".toByteArray() + ByteArray(60)))
    }

    @Test
    fun strokeCountLimitIsEnforcedBeforeAllocation() {
        val tooMany = sample.copyOf().also { ByteBuffer.wrap(it).putInt(40 + 32, HinoteBinParser.MAX_STROKES + 1) }
        assertEquals(HinoteError.LimitExceeded, err(tooMany))
        val huge = sample.copyOf().also { ByteBuffer.wrap(it).putInt(40 + 32, HinoteBinParser.MAX_STROKES) }
        assertEquals(HinoteError.Truncated, err(huge)) // Anzahl passt nicht in die Restlänge
        val negative = sample.copyOf().also { ByteBuffer.wrap(it).putInt(40 + 32, -1) }
        assertEquals(HinoteError.Inconsistent, err(negative))
    }

    @Test
    fun pointCountLimitAndOversizedPointCountAreRejected() {
        val firstStroke = 40 + 44
        val tooMany = sample.copyOf().also { ByteBuffer.wrap(it).putInt(firstStroke, HinoteBinParser.MAX_POINTS_PER_STROKE + 1) }
        assertEquals(HinoteError.LimitExceeded, err(tooMany))
        val beyondEnd = sample.copyOf().also { ByteBuffer.wrap(it).putInt(firstStroke, 5000) }
        assertEquals(HinoteError.Truncated, err(beyondEnd))
    }

    @Test
    fun inconsistentHeaderFieldsAreRejected() {
        // Abschnittslänge passt nicht zur Datei
        val shortSection = sample.copyOf().also { ByteBuffer.wrap(it).putInt(36, sample.size - 41) }
        assertEquals(HinoteError.TrailingBytes, err(shortSection))
        // Strichzahl kleiner als tatsächlicher Inhalt -> Rest im Abschnitt
        val fewer = sample.copyOf().also { ByteBuffer.wrap(it).putInt(40 + 32, 1) }
        assertEquals(HinoteError.Inconsistent, err(fewer))
        val badHeader = sample.copyOf().also { ByteBuffer.wrap(it).putInt(12, 99) }
        assertEquals(HinoteError.Inconsistent, err(badHeader))
        val zeroWidth = sample.copyOf().also { ByteBuffer.wrap(it).putInt(40 + 12, 0) }
        assertEquals(HinoteError.Inconsistent, err(zeroWidth))
    }

    @Test
    fun randomCorruptionNeverThrows() {
        val rnd = java.util.Random(11)
        repeat(1000) {
            val copy = sample.copyOf()
            copy[rnd.nextInt(copy.size)] = rnd.nextInt().toByte()
            HinoteBinParser.parse(copy)
        }
    }
}
