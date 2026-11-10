package local.papernotes.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PdfLayoutTest {
    @Test
    fun defaultPageScalesToA4Width() {
        val s = PdfLayout.pageSize(1404, 1872)
        assertEquals(595, s.widthPt)
        assertEquals(793, s.heightPt) // 1872 * 595 / 1404 = 793.3
        assertEquals(595f / 1404f, s.scale, 1e-6f)
    }

    @Test
    fun aspectRatioIsKept() {
        val s = PdfLayout.pageSize(1000, 3000)
        assertEquals(595, s.widthPt)
        assertEquals(1785, s.heightPt)
    }

    @Test
    fun smallPageIsScaledUp() {
        val s = PdfLayout.pageSize(100, 200)
        assertEquals(595, s.widthPt)
        assertEquals(1190, s.heightPt)
        assertEquals(5.95f, s.scale, 1e-6f)
    }

    @Test
    fun veryTallPageIsLimited() {
        val s = PdfLayout.pageSize(100, 20_000)
        assertEquals(PdfLayout.MAX_DIMENSION_PT, s.heightPt)
        assertTrue(s.widthPt in 1..PdfLayout.PAGE_WIDTH_PT)
        assertEquals(PdfLayout.MAX_DIMENSION_PT / 20_000f, s.scale, 1e-6f)
    }

    @Test
    fun minimumSizeIsOnePoint() {
        val s = PdfLayout.pageSize(20_000, 1)
        assertEquals(595, s.widthPt)
        assertEquals(1, s.heightPt)
    }

    @Test
    fun nonPositiveSizeIsRejected() {
        try {
            PdfLayout.pageSize(0, 100)
            fail()
        } catch (expected: IllegalArgumentException) {
        }
    }
}
