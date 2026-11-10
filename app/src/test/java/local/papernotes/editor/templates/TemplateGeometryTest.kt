package local.papernotes.editor.templates

import local.papernotes.editor.TemplateCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateGeometryTest {
    // 1404x1872 mit ~2,0 Pixel je dp (320 dpi) und 12,6 Pixel je mm.
    private fun metrics(w: Float = 1404f, h: Float = 1872f) = TemplateMetrics(w, h, 2f, 12.6f, 28f)

    private fun TemplateShapes.allCoords(): List<FloatArray> = listOf(fine, main, head, dots)

    private fun assertInside(s: TemplateShapes, w: Float, h: Float, name: String) {
        for (arr in s.allCoords()) {
            for (i in arr.indices) {
                val v = arr[i]
                assertTrue("$name NaN/inf", v.isFinite())
                assertTrue("$name outside: $v", v >= 0f && v <= if (i % 2 == 0) w else h)
            }
        }
        var i = 0
        while (i < s.circles.size) {
            assertTrue(s.circles[i + 2] > 0f && s.circles[i + 2].isFinite())
            assertTrue(s.circles[i] in 0f..w && s.circles[i + 1] in 0f..h)
            i += 3
        }
        for (l in s.labels) assertTrue("$name label", l.x in 0f..w && l.y in 0f..h)
    }

    private fun hlines(a: FloatArray): List<Float> = (a.indices step 4).filter { a[it + 1] == a[it + 3] }.map { a[it + 1] }
    private fun vlines(a: FloatArray): List<Float> = (a.indices step 4).filter { a[it] == a[it + 2] }.map { a[it] }

    @Test
    fun blankAndUnknownAreEmpty() {
        assertTrue(TemplateGeometry.build("blank", metrics()).isEmpty)
        assertTrue(TemplateGeometry.build("from_the_future", metrics()).isEmpty)
    }

    @Test
    fun allTemplatesStayInsideSheetAtManySizes() {
        val sizes = listOf(
            1404f to 1872f, 600f to 800f, 120f to 160f, 40f to 60f, 5f to 5f, 1f to 1f, 0.5f to 0.5f,
            3000f to 100f, 100f to 3000f, 0f to 100f, -5f to 10f, Float.NaN to 10f,
        )
        for (spec in TemplateCatalog.all) {
            for ((w, h) in sizes) {
                for (dp in floatArrayOf(0.1f, 1f, 2f, 3.5f, Float.NaN)) {
                    val m = TemplateMetrics(w, h, dp, if (dp.isNaN()) Float.NaN else dp * 6f, 14f * (if (dp.isNaN()) 1f else dp))
                    val s = TemplateGeometry.build(spec.id, m)
                    assertEquals(0, s.fine.size % 4); assertEquals(0, s.main.size % 4); assertEquals(0, s.head.size % 4)
                    assertEquals(0, s.dots.size % 2); assertEquals(0, s.circles.size % 3)
                    if (w > 0f && h > 0f) assertInside(s, w, h, "${spec.id} ${w}x$h dp=$dp")
                    else assertTrue(s.isEmpty)
                }
            }
        }
    }

    @Test
    fun everyNonBlankTemplateDrawsSomething() {
        for (spec in TemplateCatalog.all.filter { it.id != "blank" }) {
            assertTrue(spec.id, !TemplateGeometry.build(spec.id, metrics()).isEmpty)
        }
    }

    @Test
    fun wideLinesHaveWiderSpacingThanNarrow() {
        val wide = hlines(TemplateGeometry.build("lines_wide", metrics()).main)
        val narrow = hlines(TemplateGeometry.build("lines_narrow", metrics()).main)
        assertEquals(112f, wide[1] - wide[0], 0.01f)
        assertEquals(72f, narrow[1] - narrow[0], 0.01f)
        assertTrue(wide.all { it in 0f..1872f })
        assertTrue(narrow.size > wide.size)
    }

    @Test
    fun dottedIsRegularGrid() {
        val s = TemplateGeometry.build("dotted", metrics())
        assertTrue(s.dots.isNotEmpty())
        assertEquals(0, s.dots.size % 2)
        // 1404 / 72 = 19 Spalten, 1872 / 72 = 26 Zeilen
        assertEquals(19 * 26, s.dots.size / 2)
    }

    @Test
    fun millimeterHasMajorLineEveryFifth() {
        val s = TemplateGeometry.build("millimeter", metrics())
        val minor = vlines(s.fine)
        val major = vlines(s.main)
        assertTrue(minor.isNotEmpty() && major.isNotEmpty())
        assertEquals(12.6f * 5f, major[0], 0.01f)
        assertEquals(12.6f * 5f, major[1] - major[0], 0.01f)
        // Zwischen zwei Hauptlinien liegen vier feine.
        assertEquals(4, minor.count { it > major[0] && it < major[1] })
    }

    @Test
    fun gridUsesFiveMillimeterCells() {
        val s = TemplateGeometry.build("grid", metrics())
        val v = vlines(s.main)
        assertEquals(12.6f * 5f, v[1] - v[0], 0.01f)
        assertTrue(s.fine.isEmpty())
    }

    @Test
    fun todoHasThreeColumnsHeaderAndCircles() {
        val s = TemplateGeometry.build("todo", metrics())
        assertEquals(listOf(TemplateLabel.DONE, TemplateLabel.TIME, TemplateLabel.TODOS), s.labels.map { it.label })
        // Außenrahmen links/rechts + 2 innere Spaltenlinien
        assertEquals(4, vlines(s.main).size)
        assertTrue(s.circles.size / 3 >= 10)
        // Kreise in der ersten Spalte, eine je Zeile, gleichmäßig verteilt
        val cx = (0 until s.circles.size / 3).map { s.circles[it * 3] }
        assertTrue(cx.distinct().size == 1)
        val firstCol = vlines(s.main).sorted()[1]
        assertTrue(cx[0] < firstCol)
        assertEquals(1, hlines(s.head).size)
    }

    @Test
    fun monthlyIsSevenColumnsBySixRows() {
        val s = TemplateGeometry.build("monthly", metrics())
        // Spaltenlinien: 6 innere + 2 Rahmen
        assertEquals(8, vlines(s.main).size)
        // Zeilen im Raster: 5 innere Linien + Kopfzeile (head) + 2 Rahmen + Titel-Trenner
        val inner = hlines(s.main)
        assertEquals(2 + 1 + 5, inner.size)
        assertEquals(1, hlines(s.head).size)
        assertEquals(7, s.labels.size)
        assertEquals(TemplateLabel.MON, s.labels.first().label)
        assertEquals(TemplateLabel.SUN, s.labels.last().label)
    }

    @Test
    fun weeklyHasSevenDayBlocks() {
        val s = TemplateGeometry.build("weekly", metrics())
        assertEquals(7, s.labels.size)
        assertEquals(6, hlines(s.head).size)
    }

    @Test
    fun cornellSplitsIntoCueNotesAndSummary() {
        val s = TemplateGeometry.build("cornell", metrics())
        val cueLine = vlines(s.main).sorted()[1]
        val rightEdge = vlines(s.main).max()
        val leftEdge = vlines(s.main).min()
        val frac = (cueLine - leftEdge) / (rightEdge - leftEdge)
        assertEquals(0.28f, frac, 0.01f)
        val summaryY = hlines(s.head).max()
        assertTrue(summaryY > 1872f * 0.7f)
        assertTrue(s.labels.map { it.label }.containsAll(listOf(TemplateLabel.CUES, TemplateLabel.NOTES, TemplateLabel.SUMMARY, TemplateLabel.TOPIC)))
    }

    @Test
    fun errorLogHasFourColumnLabels() {
        val s = TemplateGeometry.build("error_log", metrics())
        assertEquals(listOf(TemplateLabel.DATE, TemplateLabel.ERROR, TemplateLabel.CAUSE, TemplateLabel.SOLUTION), s.labels.map { it.label })
    }

    @Test
    fun tasksHasChecksPriorityAndDue() {
        val s = TemplateGeometry.build("tasks", metrics())
        assertEquals(
            listOf(TemplateLabel.DONE, TemplateLabel.TASK, TemplateLabel.PRIORITY, TemplateLabel.DUE),
            s.labels.map { it.label },
        )
    }

    @Test
    fun characterGridHasHundredCellsInTenRows() {
        val s = TemplateGeometry.build("character_grid", metrics())
        // je Zeile 2 waagerechte Rahmenlinien, 11 senkrechte
        assertEquals(20, hlines(s.main).size)
        assertEquals(110, vlines(s.main).size)
        assertTrue(s.fine.isNotEmpty())
    }

    @Test
    fun staffHasTenSystemsOfFiveLines() {
        val s = TemplateGeometry.build("staff", metrics())
        val ys = hlines(s.main)
        assertEquals(50, ys.size)
        assertEquals(ys[1] - ys[0], ys[4] - ys[3], 0.01f)
        assertTrue(s.labels.isEmpty())
    }

    @Test
    fun timeManagementHasCrossAndFourLabels() {
        val s = TemplateGeometry.build("time_management", metrics())
        assertEquals(1, vlines(s.main).size)
        assertEquals(1, hlines(s.main).size)
        assertEquals(
            setOf(TemplateLabel.URGENT, TemplateLabel.NOT_URGENT, TemplateLabel.IMPORTANT, TemplateLabel.NOT_IMPORTANT),
            s.labels.map { it.label }.toSet(),
        )
        assertTrue(s.labels.single { it.label == TemplateLabel.IMPORTANT }.right)
    }

    @Test
    fun pointSheetHasFrameAndTwoColumnLabels() {
        val s = TemplateGeometry.build("point_sheet", metrics())
        assertEquals(listOf(TemplateLabel.POINT, TemplateLabel.ACTION), s.labels.map { it.label })
        assertEquals(1, hlines(s.head).size)
        assertEquals(3, vlines(s.main).size)
    }

    @Test
    fun swotHasCrossAndFourLabels() {
        val s = TemplateGeometry.build("swot", metrics())
        assertEquals(
            listOf(TemplateLabel.STRENGTHS, TemplateLabel.WEAKNESSES, TemplateLabel.OPPORTUNITIES, TemplateLabel.THREATS),
            s.labels.map { it.label },
        )
        assertEquals(1, vlines(s.main).size)
        assertEquals(1, hlines(s.main).size)
    }

    @Test
    fun metricsAreOnlyReadNotRetained() {
        val a = TemplateGeometry.build("todo", metrics())
        val b = TemplateGeometry.build("todo", metrics())
        assertTrue(a.main.contentEquals(b.main))
    }
}
