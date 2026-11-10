package local.papernotes.hinote

import local.papernotes.model.ParaFormat
import local.papernotes.model.SpanType
import local.papernotes.model.TextSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HinoteHtmlTest {
    private fun parse(s: String) = HinoteHtml.parse(s)!!

    @Test
    fun plainFontText() {
        val r = parse("""<note><element type="TEXT"><font color ="#ff000000">Dies ist ein Test</font></element></note>""")
        assertEquals("Dies ist ein Test", r.text)
        assertEquals(emptyList<TextSpan>(), r.spans) // Schwarz ist Standard
        assertEquals(emptyList<ParaFormat>(), r.paragraphs)
    }

    @Test
    fun boldItalicUnderlineColorSize() {
        val r = parse("""<b>fett</b> <i>kursiv</i> <u>unter</u> <font color ="#ffff0000">rot</font> <hw_font size ="1.5">groß</hw_font>""")
        assertEquals("fett kursiv unter rot groß", r.text)
        assertEquals(
            listOf(
                TextSpan(SpanType.BOLD, 0, 4),
                TextSpan(SpanType.ITALIC, 5, 11),
                TextSpan(SpanType.UNDERLINE, 12, 17),
                TextSpan(SpanType.COLOR, 18, 21, 0xFFFF0000.toInt()),
                TextSpan(SpanType.SIZE, 22, 26, 150),
            ),
            r.spans,
        )
    }

    @Test
    fun nestedTagsOverlap() {
        val r = parse("<b>a<i>b</i>c</b>")
        assertEquals("abc", r.text)
        assertEquals(listOf(TextSpan(SpanType.BOLD, 0, 3), TextSpan(SpanType.ITALIC, 1, 2)), r.spans)
    }

    @Test
    fun brStartsNewParagraph() {
        val r = parse("eins<br>\nzwei<br>\n<br>\ndrei")
        assertEquals("eins\nzwei\n\ndrei", r.text)
    }

    @Test
    fun alignAndLists() {
        val r = parse(
            """<div align="center">Mitte</div><br><tab_span style ="type:4,level:1,number:0">Punkt</tab_span><br>""" +
                """<tab_span style ="type:2,level:2,number:3">Nr</tab_span><br>""" +
                """<tab_span style ="type:5,level:1,number:0">Quadrat</tab_span>""",
        )
        assertEquals("Mitte\nPunkt\nNr\nQuadrat", r.text)
        assertEquals(
            listOf(
                ParaFormat(0, align = ParaFormat.ALIGN_CENTER),
                ParaFormat(1, list = ParaFormat.LIST_BULLET),
                ParaFormat(2, list = ParaFormat.LIST_NUMBER, level = 1),
                ParaFormat(3, list = ParaFormat.LIST_SQUARE),
            ),
            r.paragraphs,
        )
    }

    @Test
    fun entitiesAndUnknownTags() {
        val r = parse("a &lt; b &amp; &#65;&#x42; &nbsp;<foo bar=1>x</foo> &unknown; <!-- c -->")
        assertEquals("a < b & AB  x &unknown; ", r.text)
    }

    @Test
    fun brokenInputDoesNotThrow() {
        assertNotNull(HinoteHtml.parse("<b>offen"))
        assertNotNull(HinoteHtml.parse("</b></i>zu"))
        assertNotNull(HinoteHtml.parse("<font color=\"#zzz\">x</font"))
        assertEquals("offen", parse("<b>offen").text)
        assertEquals(listOf(TextSpan(SpanType.BOLD, 0, 5)), parse("<b>offen").spans)
    }

    @Test
    fun oversizedInputIsRejected() {
        assertNull(HinoteHtml.parse("x".repeat(600_000)))
    }

    @Test
    fun deepNestingIsBounded() {
        val html = "<b>".repeat(5000) + "x"
        val r = parse(html)
        assertEquals("x", r.text)
    }
}
