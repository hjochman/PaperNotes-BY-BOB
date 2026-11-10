package local.papernotes.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PdfAttachmentsTest {
    /** Minimales PDF im Aufbau von Skia: klassische xref-Tabelle, flacher Seitenbaum, 2 Seiten. */
    private fun samplePdf(): ByteArray {
        val sb = StringBuilder("%PDF-1.4\n")
        val offsets = HashMap<Int, Int>()
        fun obj(n: Int, body: String) {
            offsets[n] = sb.length
            sb.append("$n 0 obj\n$body\nendobj\n")
        }
        obj(1, "<</Producer (Skia/PDF m75)>>")
        obj(2, "<</Type /Page\n/Resources <</ProcSets [/PDF /Text]>>\n/MediaBox [0 0 595 793]\n/Contents 4 0 R\n/Parent 6 0 R>>")
        obj(3, "<</Type /Page\n/Resources <</ProcSets [/PDF /Text]>>\n/MediaBox [0 0 595 800]\n/Contents 4 0 R\n/Parent 6 0 R>>")
        obj(4, "<</Length 0>> stream\n\nendstream")
        obj(6, "<</Type /Pages\n/Count 2\n/Kids [2 0 R 3 0 R]>>")
        obj(7, "<</Type /Catalog\n/Pages 6 0 R>>")
        val xref = sb.length
        sb.append("xref\n0 8\n")
        sb.append("0000000000 65535 f \n")
        for (n in 1..7) {
            val off = offsets[n]
            sb.append(if (off == null) "0000000000 65535 f \n" else String.format("%010d 00000 n \n", off))
        }
        sb.append("trailer\n<</Size 8\n/Root 7 0 R\n/Info 1 0 R>>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun item(page: Int, key: String, name: String, data: ByteArray = byteArrayOf(1, 2, 3, 4, 5)) =
        PdfAttachments.Item(page, key, "$name.m4a", name, "audio/mp4", data)

    @Test
    fun updateKeepsOriginalAndAddsAnnotation() {
        val pdf = samplePdf()
        val update = PdfAttachments.buildUpdate(pdf, 2, listOf(item(1, "a.m4a", "Memo")))
        assertNotNull(update)
        val text = String(update!!, Charsets.ISO_8859_1)
        assertTrue("/Subtype /FileAttachment" in text)
        assertTrue("/Type /EmbeddedFile" in text)
        assertTrue("/Annots [" in text)
        assertTrue("/Prev ${startXref(pdf)}" in text)
        // Rechteck oben rechts auf Seite 2 (Höhe 800): 595-8-20 .. 595-8, 800-8-20 .. 800-8
        assertTrue("[567.00 772.00 587.00 792.00]" in text)
        assertTrue(text.trimEnd().endsWith("%%EOF"))
    }

    @Test
    fun xrefOffsetsPointToObjects() {
        val pdf = samplePdf()
        val update = PdfAttachments.buildUpdate(pdf, 2, listOf(item(0, "a.m4a", "Eins"), item(1, "b.m4a", "Zwei")))!!
        val whole = String(pdf + update, Charsets.ISO_8859_1)
        val last = Regex("""startxref\s+(\d+)\s+%%EOF\s*$""").find(whole)!!.groupValues[1].toInt()
        assertTrue(whole.startsWith("xref", last))
        val entries = Regex("""(\d+) 1\n(\d{10}) 00000 n """).findAll(whole.substring(last)).toList()
        assertEquals(8, entries.size) // 2 Dateien, 2 Specs, 2 Annots, 2 geänderte Seiten
        for (e in entries) {
            assertTrue("Objekt ${e.groupValues[1]}", whole.startsWith("${e.groupValues[1]} 0 obj", e.groupValues[2].toInt()))
        }
        assertEquals(14, Regex("""/Size (\d+)""").findAll(whole).last().groupValues[1].toInt())
    }

    @Test
    fun severalAttachmentsOnOnePageStackDownwards() {
        val update = PdfAttachments.buildUpdate(samplePdf(), 2, listOf(item(0, "a", "A"), item(0, "b", "B")))!!
        val text = String(update, Charsets.ISO_8859_1)
        assertTrue("[567.00 765.00 587.00 785.00]" in text)
        assertTrue("[567.00 741.00 587.00 761.00]" in text)
    }

    @Test
    fun sameKeyIsEmbeddedOnce() {
        val update = PdfAttachments.buildUpdate(samplePdf(), 2, listOf(item(0, "x", "A"), item(1, "x", "A")))!!
        val text = String(update, Charsets.ISO_8859_1)
        assertEquals(1, Regex("/Type /EmbeddedFile").findAll(text).count())
        assertEquals(2, Regex("/Subtype /FileAttachment").findAll(text).count())
    }

    @Test
    fun binaryDataIsKeptExactly() {
        val data = ByteArray(300) { (it * 7).toByte() }
        val update = PdfAttachments.buildUpdate(samplePdf(), 2, listOf(item(0, "x", "A", data)))!!
        val needle = "stream\n".toByteArray(Charsets.ISO_8859_1) + data + "\nendstream".toByteArray(Charsets.ISO_8859_1)
        assertTrue(indexOf(update, needle) >= 0)
    }

    @Test
    fun umlautsAreWrittenAsUtf16Hex() {
        val update = PdfAttachments.buildUpdate(samplePdf(), 2, listOf(item(0, "x", "Tür")))!!
        val text = String(update, Charsets.ISO_8859_1)
        assertTrue("<FEFF005400FC0072>" in text)
        assertTrue("(T_r.m4a)" in text)
    }

    @Test
    fun rejectsUnexpectedStructure() {
        val pdf = samplePdf()
        assertNull(PdfAttachments.buildUpdate(pdf, 2, listOf(item(2, "x", "A")))) // Seite außerhalb
        assertNull(PdfAttachments.buildUpdate(pdf, 3, listOf(item(0, "x", "A")))) // Seitenzahl passt nicht
        assertNull(PdfAttachments.buildUpdate("kein pdf".toByteArray(), 2, listOf(item(0, "x", "A"))))
        val twice = pdf + PdfAttachments.buildUpdate(pdf, 2, listOf(item(0, "x", "A")))!!
        assertNull(PdfAttachments.buildUpdate(twice, 2, listOf(item(0, "y", "B")))) // /Prev vorhanden
    }

    @Test
    fun attachRewritesFileAndKeepsOriginalOnFailure() {
        val dir = createTempDir()
        try {
            val file = File(dir, "n.pdf")
            val pdf = samplePdf()
            file.writeBytes(pdf)
            assertTrue(PdfAttachments.attach(file, 2, listOf(item(0, "x", "A"))))
            val result = file.readBytes()
            assertTrue(result.size > pdf.size)
            assertArrayEquals(pdf, result.copyOf(pdf.size))
            file.writeBytes(pdf)
            assertEquals(false, PdfAttachments.attach(file, 5, listOf(item(0, "x", "A"))))
            assertArrayEquals(pdf, file.readBytes())
            assertEquals(listOf("n.pdf"), dir.list()!!.toList())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun startXref(pdf: ByteArray): Int =
        Regex("""startxref\s+(\d+)""").find(String(pdf, Charsets.ISO_8859_1))!!.groupValues[1].toInt()

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
