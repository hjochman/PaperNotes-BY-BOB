package local.papernotes.editor

import local.papernotes.editor.PageCanvasView.EraserStyle
import local.papernotes.editor.PageCanvasView.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorToolStateTest {
    private class MapStore(val map: MutableMap<String, Int> = HashMap()) : ToolSettingsStore {
        override fun getInt(key: String, default: Int): Int = map[key] ?: default
        override fun putInt(key: String, value: Int) {
            map[key] = value
        }
    }

    @Test
    fun startsWithPenAndClosedBar() {
        val s = EditorToolState(MapStore())
        assertEquals(Tool.PEN, s.tool)
        assertFalse(s.barVisible)
        assertEquals(PenCatalog.default, s.pen)
        assertEquals(PenColor.BLACK, s.penColor)
        assertEquals(EraserStyle.STANDARD, s.eraserStyle)
        assertEquals(EditorToolState.DEFAULT_ERASER_RADIUS, s.eraserRadius)
    }

    @Test
    fun changingToolOpensBarAndTappingActiveToolTogglesIt() {
        val s = EditorToolState(MapStore())
        s.selectTool(Tool.PEN)
        assertTrue(s.barVisible)
        s.selectTool(Tool.PEN)
        assertFalse(s.barVisible)
        s.selectTool(Tool.ERASER)
        assertEquals(Tool.ERASER, s.tool)
        assertTrue(s.barVisible)
    }

    @Test
    fun lassoHasBar() {
        val s = EditorToolState(MapStore())
        s.selectTool(Tool.LASSO)
        assertTrue(s.barVisible)
        s.selectTool(Tool.LASSO)
        assertFalse(s.barVisible)
        s.selectTool(Tool.PEN)
        assertTrue(s.barVisible)
    }

    @Test
    fun lassoFiltersPersistAndDefaultToTrue() {
        val store = MapStore()
        val first = EditorToolState(store)
        assertTrue(first.lassoSelectInk)
        assertTrue(first.lassoSelectText)
        assertTrue(first.lassoSelectImage)

        first.setLassoSelectInk(false)
        first.setLassoSelectText(false)
        first.setLassoSelectImage(false)

        val second = EditorToolState(store)
        assertFalse(second.lassoSelectInk)
        assertFalse(second.lassoSelectText)
        assertFalse(second.lassoSelectImage)
    }

    @Test
    fun contentChangeCollapsesBarUnlessPinned() {
        val s = EditorToolState(MapStore())
        s.selectTool(Tool.PEN)
        assertTrue(s.barVisible)
        s.onContentChanged()
        assertFalse(s.barVisible)
        s.selectTool(Tool.PEN)
        s.togglePinned()
        assertTrue(s.barPinned)
        s.onContentChanged()
        assertTrue(s.barVisible)
        s.togglePinned()
        s.onContentChanged()
        assertFalse(s.barVisible)
    }

    @Test
    fun pinIsRememberedAndOpensBarOnStart() {
        val store = MapStore()
        val first = EditorToolState(store)
        first.togglePinned()
        val second = EditorToolState(store)
        assertTrue(second.barPinned)
        assertTrue(second.barVisible)
    }

    @Test
    fun penWidthIsKeptPerPenAndClamped() {
        val s = EditorToolState(MapStore())
        val ballpoint = PenCatalog.pens.first { it.id == "ballpoint" }
        val marker = PenCatalog.pens.first { it.isMarker }
        s.selectPen(ballpoint)
        s.setPenWidth(7)
        s.selectPen(marker)
        assertEquals(marker.defaultWidth, s.penWidth)
        s.setPenWidth(1000)
        assertEquals(marker.maxWidth, s.penWidth)
        s.setPenWidth(-5)
        assertEquals(marker.minWidth, s.penWidth)
        s.selectPen(ballpoint)
        assertEquals(7, s.penWidth)
        s.setPenWidth(ballpoint.maxWidth)
        s.stepPenWidth(1)
        assertEquals(ballpoint.maxWidth, s.penWidth)
        s.setPenWidth(ballpoint.minWidth)
        s.stepPenWidth(-1)
        assertEquals(ballpoint.minWidth, s.penWidth)
    }

    @Test
    fun eraserRadiusIsClamped() {
        val s = EditorToolState(MapStore())
        s.setEraserRadius(10_000)
        assertEquals(EditorToolState.MAX_ERASER_RADIUS, s.eraserRadius)
        s.setEraserRadius(0)
        assertEquals(EditorToolState.MIN_ERASER_RADIUS, s.eraserRadius)
        s.stepEraserRadius(-1)
        assertEquals(EditorToolState.MIN_ERASER_RADIUS, s.eraserRadius)
        s.stepEraserRadius(5)
        assertEquals(EditorToolState.MIN_ERASER_RADIUS + 5, s.eraserRadius)
    }

    @Test
    fun restoresSettingsFromStore() {
        val store = MapStore()
        val first = EditorToolState(store)
        val pencil = PenCatalog.pens.first { it.id == "pencil" }
        first.selectPen(pencil)
        first.selectColor(PenColor.GRAY)
        first.setPenWidth(9)
        first.selectEraserStyle(EraserStyle.STROKES)
        first.setEraserRadius(33)

        val second = EditorToolState(store)
        assertEquals(pencil, second.pen)
        assertEquals(PenColor.GRAY, second.penColor)
        assertEquals(9, second.penWidth)
        assertEquals(EraserStyle.STROKES, second.eraserStyle)
        assertEquals(33, second.eraserRadius)
    }

    @Test
    fun restoreSanitizesInvalidValues() {
        val store = MapStore(
            hashMapOf(
                EditorToolState.KEY_PEN_TYPE to 99,
                EditorToolState.KEY_PEN_COLOR to 0x12345678,
                EditorToolState.KEY_PEN_WIDTH + "ballpoint" to 5000,
                EditorToolState.KEY_ERASER_STYLE to 42,
                EditorToolState.KEY_ERASER_RADIUS to -3,
            ),
        )
        val s = EditorToolState(store)
        assertEquals(PenCatalog.default, s.pen)
        assertEquals(PenColor.BLACK, s.penColor)
        assertEquals(PenCatalog.default.maxWidth, s.penWidth)
        assertEquals(EraserStyle.STANDARD, s.eraserStyle)
        assertEquals(EditorToolState.MIN_ERASER_RADIUS, s.eraserRadius)
    }
}
