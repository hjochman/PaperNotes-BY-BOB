package local.papernotes.editor

import local.papernotes.model.Point
import local.papernotes.model.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PageHistoryTest {
    private fun stroke(seed: Int, n: Int = 3) = Stroke(
        penType = 2,
        width = 4f,
        color = 0xFF000000.toInt(),
        points = List(n) { Point(seed + it.toFloat(), seed * 2f + it, 1000L + it, 0.5f) },
    )

    @Test
    fun startsEmptyWithoutHistory() {
        val h = PageHistory(emptyList())
        assertFalse(h.canUndo)
        assertFalse(h.canRedo)
        assertNull(h.undo())
        assertNull(h.redo())
        assertTrue(h.strokes.isEmpty())
    }

    @Test
    fun addStrokeUndoRedo() {
        val a = stroke(1)
        val b = stroke(2)
        val h = PageHistory(listOf(a))
        assertTrue(h.record(listOf(a, b)))
        assertEquals(listOf(a, b), h.strokes)
        assertTrue(h.canUndo)
        assertEquals(listOf(a), h.undo())
        assertTrue(h.canRedo)
        assertFalse(h.canUndo)
        assertEquals(listOf(a, b), h.redo())
        assertFalse(h.canRedo)
    }

    @Test
    fun unchangedListIsNoStep() {
        val a = stroke(1)
        val h = PageHistory(listOf(a))
        assertFalse(h.record(listOf(a.copy())))
        assertFalse(h.canUndo)
    }

    @Test
    fun eraseSplitsStrokeIntoParts() {
        val a = stroke(1)
        val big = stroke(5, 10)
        val c = stroke(9)
        val part1 = big.copy(points = big.points.subList(0, 4))
        val part2 = big.copy(points = big.points.subList(6, 10))
        val h = PageHistory(listOf(a, big, c))
        assertTrue(h.record(listOf(a, part1, part2, c)))
        assertEquals(listOf(a, part1, part2, c), h.strokes)
        assertSame(a, h.strokes[0])
        assertSame(c, h.strokes[3])
        assertEquals(listOf(a, big, c), h.undo())
        assertEquals(listOf(a, part1, part2, c), h.redo())
    }

    @Test
    fun eraseRemovesWholeStrokeAndSharesUntouchedObjects() {
        val a = stroke(1)
        val b = stroke(2)
        val c = stroke(3)
        val h = PageHistory(listOf(a, b, c))
        val aCopy = a.copy()
        val cCopy = c.copy()
        assertTrue(h.record(listOf(aCopy, cCopy)))
        // Gleiche Inhalte: das ursprüngliche Objekt bleibt erhalten, es wird nicht doppelt gehalten.
        assertSame(a, h.strokes[0])
        assertSame(c, h.strokes[1])
        assertEquals(listOf(a, b, c), h.undo())
    }

    @Test
    fun changeAcrossSeveralStrokes() {
        val s = List(6) { stroke(it + 1) }
        val h = PageHistory(s)
        val changed = s.toMutableList()
        changed[1] = s[1].copy(width = 9f)
        changed.removeAt(4)
        assertTrue(h.record(changed))
        assertEquals(changed, h.strokes)
        assertEquals(s, h.undo())
        assertEquals(changed, h.redo())
    }

    @Test
    fun replaceWholePage() {
        val h = PageHistory(listOf(stroke(1), stroke(2)))
        val fresh = listOf(stroke(7))
        assertTrue(h.record(fresh))
        assertEquals(fresh, h.strokes)
        assertEquals(listOf(stroke(1), stroke(2)), h.undo())
        assertTrue(h.record(emptyList()))
        assertTrue(h.strokes.isEmpty())
    }

    @Test
    fun undoRedoChains() {
        val s = List(5) { stroke(it + 1) }
        val h = PageHistory(emptyList())
        for (i in 1..5) assertTrue(h.record(s.subList(0, i)))
        for (i in 4 downTo 0) assertEquals(s.subList(0, i), h.undo())
        assertNull(h.undo())
        for (i in 1..5) assertEquals(s.subList(0, i), h.redo())
        assertNull(h.redo())
        assertEquals(s, h.strokes)
    }

    @Test
    fun newActionDiscardsRedo() {
        val a = stroke(1)
        val b = stroke(2)
        val c = stroke(3)
        val h = PageHistory(emptyList())
        h.record(listOf(a))
        h.record(listOf(a, b))
        h.undo()
        assertTrue(h.canRedo)
        assertTrue(h.record(listOf(a, c)))
        assertFalse(h.canRedo)
        assertNull(h.redo())
        assertEquals(listOf(a), h.undo())
    }

    @Test
    fun limitDropsOldestSteps() {
        val h = PageHistory(emptyList(), limit = 3)
        val s = List(6) { stroke(it + 1) }
        for (i in 1..6) h.record(s.subList(0, i))
        var steps = 0
        while (h.undo() != null) steps++
        assertEquals(3, steps)
        assertEquals(s.subList(0, 3), h.strokes)
    }

    @Test
    fun defaultLimitIsHundred() {
        val h = PageHistory(emptyList())
        val s = List(150) { stroke(it + 1, 2) }
        for (i in 1..150) h.record(s.subList(0, i))
        var steps = 0
        while (h.undo() != null) steps++
        assertEquals(PageHistory.DEFAULT_LIMIT, steps)
    }

    @Test
    fun initialListIsCopied() {
        val src = mutableListOf(stroke(1))
        val h = PageHistory(src)
        src.add(stroke(2))
        assertEquals(1, h.strokes.size)
    }
}
