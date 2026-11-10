package local.papernotes.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class PageContentLogicTest {
    @Test
    fun decodeSizeIsDoubleTheTarget() {
        assertEquals(720, PageContentLogic.decodeMaxDim(1000f, 500f, 0.36f))
    }

    @Test
    fun decodeSizeIsLimited() {
        assertEquals(2048, PageContentLogic.decodeMaxDim(1404f, 1872f, 5f))
        assertEquals(64, PageContentLogic.decodeMaxDim(10f, 10f, 0.1f))
        assertEquals(64, PageContentLogic.decodeMaxDim(Float.NaN, 10f, 1f))
    }
}
