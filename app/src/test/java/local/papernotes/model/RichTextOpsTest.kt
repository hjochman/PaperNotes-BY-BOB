package local.papernotes.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichTextOpsTest {
    private fun bold(a: Int, b: Int) = TextSpan(SpanType.BOLD, a, b)

    @Test
    fun normalizeMergesAdjacentAndClips() {
        val r = RichTextOps.normalizeSpans(listOf(bold(0, 3), bold(3, 6), bold(5, 99), bold(-4, 0)), 10)
        assertEquals(listOf(bold(0, 10)), r)
    }

    @Test
    fun normalizeDropsDefaultsAndEmpty() {
        val spans = listOf(
            TextSpan(SpanType.COLOR, 0, 4, RichTextOps.COLOR_DEFAULT),
            TextSpan(SpanType.SIZE, 0, 4, 100),
            bold(2, 2),
        )
        assertEquals(emptyList<TextSpan>(), RichTextOps.normalizeSpans(spans, 4))
        assertEquals(emptyList<TextSpan>(), RichTextOps.normalizeSpans(listOf(bold(0, 1)), 0))
    }

    @Test
    fun laterSpanOfSameTypeWins() {
        val r = RichTextOps.normalizeSpans(
            listOf(TextSpan(SpanType.COLOR, 0, 6, 0xFFFF0000.toInt()), TextSpan(SpanType.COLOR, 2, 4, 0xFF0000FF.toInt())),
            6,
        )
        assertEquals(
            listOf(
                TextSpan(SpanType.COLOR, 0, 2, 0xFFFF0000.toInt()),
                TextSpan(SpanType.COLOR, 2, 4, 0xFF0000FF.toInt()),
                TextSpan(SpanType.COLOR, 4, 6, 0xFFFF0000.toInt()),
            ),
            r,
        )
    }

    @Test
    fun toggleSetsThenRemoves() {
        val on = RichTextOps.toggle(emptyList(), 10, SpanType.BOLD, 2, 5)
        assertEquals(listOf(bold(2, 5)), on)
        assertTrue(RichTextOps.isCovered(on, SpanType.BOLD, 2, 5))
        val off = RichTextOps.toggle(on, 10, SpanType.BOLD, 2, 5)
        assertEquals(emptyList<TextSpan>(), off)
    }

    @Test
    fun toggleOnPartiallyCoveredRangeSetsAll() {
        val partial = listOf(bold(0, 3))
        assertFalse(RichTextOps.isCovered(partial, SpanType.BOLD, 0, 6))
        assertEquals(listOf(bold(0, 6)), RichTextOps.toggle(partial, 10, SpanType.BOLD, 0, 6))
    }

    @Test
    fun removeInsideSplitsSpan() {
        val r = RichTextOps.setSpan(listOf(bold(0, 10)), 10, SpanType.BOLD, 3, 6, null)
        assertEquals(listOf(bold(0, 3), bold(6, 10)), r)
    }

    @Test
    fun otherTypesAreKept() {
        val u = TextSpan(SpanType.UNDERLINE, 0, 10)
        val r = RichTextOps.setSpan(listOf(u), 10, SpanType.BOLD, 2, 4, 0)
        assertEquals(listOf(u, TextSpan(SpanType.BOLD, 2, 4)), r.sortedBy { it.type.code }.sortedBy { it.type != SpanType.UNDERLINE })
    }

    @Test
    fun sizeSteps() {
        assertEquals(125, RichTextOps.nextSize(100, true))
        assertEquals(80, RichTextOps.nextSize(100, false))
        assertEquals(300, RichTextOps.nextSize(300, true))
        assertEquals(60, RichTextOps.nextSize(60, false))
        assertEquals(125, RichTextOps.nextSize(110, true))
        assertEquals(100, RichTextOps.nextSize(110, false))
    }

    @Test
    fun paragraphs() {
        val t = "a\nbb\n\nc"
        assertEquals(4, RichTextOps.paragraphCount(t))
        assertEquals(0, RichTextOps.paragraphIndexAt(t, 1))
        assertEquals(1, RichTextOps.paragraphIndexAt(t, 2))
        assertEquals(3, RichTextOps.paragraphIndexAt(t, t.length))
        assertEquals(0..1, RichTextOps.paragraphRange(t, 0, 4))
        assertEquals(1..1, RichTextOps.paragraphRange(t, 2, 4))
        assertEquals(1..1, RichTextOps.paragraphRange(t, 3, 3))
    }

    @Test
    fun denseSparseRoundtrip() {
        val sparse = listOf(ParaFormat(1, ParaFormat.ALIGN_CENTER), ParaFormat(3, list = ParaFormat.LIST_BULLET, level = 1))
        val dense = RichTextOps.denseParas(sparse, 5)
        assertEquals(5, dense.size)
        assertEquals(sparse, RichTextOps.sparseParas(dense))
    }

    @Test
    fun toggleListAndLevel() {
        var d = RichTextOps.denseParas(emptyList(), 3)
        d = RichTextOps.toggleList(d, 0..1, ParaFormat.LIST_NUMBER)
        assertEquals(listOf(ParaFormat.LIST_NUMBER, ParaFormat.LIST_NUMBER, ParaFormat.LIST_NONE), d.map { it.list })
        d = RichTextOps.toggleList(d, 0..1, ParaFormat.LIST_NUMBER)
        assertEquals(listOf(0, 0, 0), d.map { it.list })
        d = RichTextOps.changeLevel(d, 2..2, -1)
        assertEquals(0, d[2].level)
        repeat(9) { d = RichTextOps.changeLevel(d, 2..2, 1) }
        assertEquals(ParaFormat.MAX_LEVEL, d[2].level)
    }

    @Test
    fun indentWorksForLeftTextButNotCenterOrRight() {
        var d = RichTextOps.denseParas(emptyList(), 3)
        d = RichTextOps.setAlign(d, 1..1, ParaFormat.ALIGN_CENTER)
        d = RichTextOps.setAlign(d, 2..2, ParaFormat.ALIGN_RIGHT)
        d = RichTextOps.changeLevel(d, 0..2, 1)
        assertEquals(listOf(1, 0, 0), d.map { it.level })
        d = RichTextOps.setAlign(d, 0..0, ParaFormat.ALIGN_CENTER) // Mitte kennt keinen Einzug
        assertEquals(0, d[0].level)
    }

    @Test
    fun numberingCountsPerLevel() {
        val n = ParaFormat.LIST_NUMBER
        val d = listOf(
            ParaFormat(0, list = n),
            ParaFormat(1, list = n, level = 1),
            ParaFormat(2, list = n, level = 1),
            ParaFormat(3, list = n),
            ParaFormat(4),
            ParaFormat(5, list = n),
        )
        assertEquals(listOf("1.", "1.", "2.", "2.", "", "1."), d.indices.map { RichTextOps.marker(d, it) })
    }

    @Test
    fun letterListAndKindChange() {
        var d = RichTextOps.denseParas(emptyList(), 3)
        d = RichTextOps.toggleList(d, 0..2, ParaFormat.LIST_LETTER)
        assertEquals(listOf("a.", "b.", "c."), d.indices.map { RichTextOps.marker(d, it) })
        d = RichTextOps.toggleList(d, 1..1, ParaFormat.LIST_NUMBER) // andere Art ersetzt die Liste
        assertEquals(listOf("a.", "1.", "a."), d.indices.map { RichTextOps.marker(d, it) })
    }

    @Test
    fun alignmentIsLockedInLists() {
        var d = RichTextOps.denseParas(emptyList(), 2)
        d = RichTextOps.setAlign(d, 0..1, ParaFormat.ALIGN_CENTER)
        assertTrue(RichTextOps.alignEditable(d, 0..1))
        d = RichTextOps.toggleList(d, 0..0, ParaFormat.LIST_BULLET)
        assertEquals(ParaFormat.ALIGN_LEFT, d[0].align) // Liste setzt linksbündig
        assertFalse(RichTextOps.alignEditable(d, 0..1))
        d = RichTextOps.setAlign(d, 0..1, ParaFormat.ALIGN_RIGHT)
        assertEquals(ParaFormat.ALIGN_LEFT, d[0].align) // Listenpunkt unverändert
        assertEquals(ParaFormat.ALIGN_RIGHT, d[1].align)
    }

    @Test
    fun bulletMarker() {
        val d = listOf(ParaFormat(0, list = ParaFormat.LIST_BULLET), ParaFormat(1, list = ParaFormat.LIST_BULLET, level = 1))
        assertEquals("\u2022", RichTextOps.marker(d, 0))
        assertEquals("\u2013", RichTextOps.marker(d, 1))
        assertEquals("", RichTextOps.marker(d, 7))
    }

    @Test
    fun squareMarkerAndKindChange() {
        var d = RichTextOps.denseParas(emptyList(), 2)
        d = RichTextOps.toggleList(d, 0..1, ParaFormat.LIST_SQUARE)
        d = RichTextOps.changeLevel(d, 1..1, 1)
        assertEquals(listOf("\u25A0", "\u25A1"), d.indices.map { RichTextOps.marker(d, it) })
        d = RichTextOps.toggleList(d, 0..1, ParaFormat.LIST_BULLET) // andere Art ersetzt
        assertEquals(listOf("\u2022", "\u2013"), d.indices.map { RichTextOps.marker(d, it) })
    }
}
