package local.papernotes.editor

import local.papernotes.model.Point
import local.papernotes.model.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTransformTest {
    private val stroke = Stroke(
        penType = 2,
        width = 4f,
        color = 0xFF112233.toInt(),
        points = listOf(Point(10f, 20f, 100L, 0.5f), Point(300.5f, 1500.25f, 200L, 0.75f)),
    )

    @Test
    fun identityReturnsSameObjects() {
        val t = PageTransform.IDENTITY
        assertTrue(t.isIdentity)
        assertSame(stroke, t.toView(stroke))
        assertSame(stroke, t.toPage(stroke))
        assertSame(stroke.points[0], t.toView(stroke.points[0]))
    }

    @Test
    fun fitWidthWithEqualWidthsIsIdentity() {
        assertEquals(PageTransform.IDENTITY, PageTransform.fitWidth(1404f, 1404f))
        assertTrue(PageTransform.fitWidth(1404f, 1404f).isIdentity)
        assertFalse(PageTransform.fitWidth(702f, 1404f).isIdentity)
    }

    @Test
    fun pointsScaleAndShift() {
        val t = PageTransform(scale = 2f, offsetX = 5f, offsetY = -10f)
        assertEquals(25f, t.pageToViewX(10f), 0f)
        assertEquals(10f, t.pageToViewY(10f), 0f)
        assertEquals(10f, t.viewToPageX(25f), 0f)
        assertEquals(10f, t.viewToPageY(10f), 0f)
        val p = t.toView(Point(10f, 10f, 7L, 0.25f))
        assertEquals(Point(25f, 10f, 7L, 0.25f), p)
    }

    @Test
    fun strokeWidthScalesWithoutOffset() {
        val t = PageTransform(scale = 0.5f, offsetX = 100f, offsetY = 100f)
        assertEquals(2f, t.pageToViewWidth(4f), 0f)
        assertEquals(4f, t.viewToPageWidth(2f), 0f)
        val v = t.toView(stroke)
        assertEquals(2f, v.width, 0f)
        assertEquals(stroke.penType, v.penType)
        assertEquals(stroke.color, v.color)
        assertEquals(stroke.points[0].time, v.points[0].time)
        assertEquals(stroke.points[0].pressure, v.points[0].pressure, 0f)
    }

    @Test
    fun roundtripPageViewPageIsExactForPowersOfTwo() {
        val t = PageTransform(scale = 0.5f, offsetX = 8f, offsetY = -16f)
        assertEquals(stroke, t.toPage(t.toView(stroke)))
    }

    @Test
    fun roundtripPageViewPageIsCloseForArbitraryScale() {
        val t = PageTransform.fitWidth(1000f, 1404f)
        val back = t.toPage(t.toView(stroke))
        assertEquals(stroke.width, back.width, 1e-4f)
        for (i in stroke.points.indices) {
            assertEquals(stroke.points[i].x, back.points[i].x, 1e-3f)
            assertEquals(stroke.points[i].y, back.points[i].y, 1e-3f)
        }
    }

    @Test
    fun roundtripViewPageViewIsClose() {
        val t = PageTransform(scale = 1.5f, offsetX = 30f, offsetY = 40f)
        val back = t.toView(t.toPage(stroke))
        assertEquals(stroke.width, back.width, 1e-4f)
        assertEquals(stroke.points[1].x, back.points[1].x, 1e-3f)
        assertEquals(stroke.points[1].y, back.points[1].y, 1e-3f)
    }

    @Test
    fun fitWidthScalesPageToViewWidth() {
        val t = PageTransform.fitWidth(702f, 1404f)
        assertEquals(0.5f, t.scale, 0f)
        assertEquals(702f, t.pageToViewX(1404f), 0f)
        assertEquals(936f, t.pageToViewY(1872f), 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroScale() {
        PageTransform(0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteOffset() {
        PageTransform(1f, Float.NaN, 0f)
    }
}
