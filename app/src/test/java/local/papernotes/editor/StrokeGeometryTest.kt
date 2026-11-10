package local.papernotes.editor

import local.papernotes.model.Point
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StrokeGeometryTest {
    @Test
    fun boundsCoverAllPointsPlusWidth() {
        val pts = listOf(Point(10f, 50f, 1, 0.1f), Point(30f, 20f, 2, 0.2f), Point(-5f, 40f, 3, 0.3f))
        assertArrayEquals(floatArrayOf(-9f, 16f, 34f, 54f), StrokeGeometry.bounds(pts, 4f), 0f)
    }

    @Test
    fun singlePoint() {
        val b = StrokeGeometry.bounds(listOf(Point(7f, 8f, 0, 1f)), 2f)
        assertArrayEquals(floatArrayOf(5f, 6f, 9f, 10f), b, 0f)
        assertEquals(4, b.size)
    }

    @Test
    fun emptyPointsRejected() {
        assertThrows(IllegalArgumentException::class.java) { StrokeGeometry.bounds(emptyList(), 1f) }
    }
}
