package local.papernotes.export

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

/**
 * Hängt Dateianhänge an Seiten eines fertigen PDFs, das `android.graphics.pdf.PdfDocument` geschrieben hat (die Klasse selbst
 * kann das nicht). Verfahren: inkrementelles Update nach ISO 32000. Hinter das vorhandene PDF kommen die neuen Objekte
 * (eingebettete Datei, Filespec, FileAttachment-Annotation je Anhang), die geänderten Seitenobjekte mit `/Annots` und eine
 * zusätzliche xref-Tabelle mit `/Prev`. Das Original bleibt Byte für Byte erhalten. Auf der Seite wird nichts gezeichnet,
 * der PDF-Betrachter zeigt ein kleines Symbol oben rechts.
 *
 * Erwartet den Aufbau von Skia (klassische xref-Tabelle, flacher Seitenbaum, Seitenobjekte ohne Stream). Passt das PDF nicht
 * dazu, liefert [buildUpdate] null und der Aufrufer weicht auf einzelne Dateien aus.
 */
object PdfAttachments {
    /** Obergrenze für alle eingebetteten Daten (wie im Nextcloud-Plugin). */
    const val MAX_EMBEDDED_BYTES = 48L * 1024 * 1024

    private const val ICON_PT = 20.0
    private const val MARGIN_PT = 8.0
    private const val GAP_PT = 4.0

    /** [key] identifiziert die Daten; gleiche Schlüssel werden nur einmal eingebettet. [pageIndex] ist 0-basiert. */
    class Item(val pageIndex: Int, val key: String, val name: String, val desc: String, val mime: String, val data: ByteArray)

    /** Hängt [items] an [pdf] an. Bei false bleibt die Datei unverändert. */
    fun attach(pdf: File, pageCount: Int, items: List<Item>): Boolean {
        if (items.isEmpty()) return true
        if (items.sumOf { it.data.size.toLong() } > MAX_EMBEDDED_BYTES) return false
        val tmp = File(pdf.parentFile, pdf.name + ".tmp")
        try {
            val original = pdf.readBytes()
            val update = buildUpdate(original, pageCount, items) ?: return false
            FileOutputStream(tmp).use {
                it.write(original)
                it.write(update)
            }
            if (!tmp.renameTo(pdf)) return false
            return true
        } catch (e: IOException) {
            return false
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Die Bytes, die hinter [pdf] angehängt werden, oder null, wenn das PDF nicht erwartungsgemäß aufgebaut ist. */
    fun buildUpdate(pdf: ByteArray, pageCount: Int, items: List<Item>): ByteArray? {
        if (items.isEmpty() || items.any { it.pageIndex !in 0 until pageCount }) return null
        val text = String(pdf, Charsets.ISO_8859_1)

        val startXref = Regex("""startxref\s+(\d+)\s+%%EOF\s*$""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val offsets = readXref(text, startXref) ?: return null
        val trailer = text.substring(text.indexOf("trailer", startXref).takeIf { it >= 0 } ?: return null)
        val size = Regex("""/Size\s+(\d+)""").find(trailer)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val root = Regex("""/Root\s+(\d+)\s+0\s+R""").find(trailer)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val info = Regex("""/Info\s+(\d+)\s+0\s+R""").find(trailer)?.groupValues?.get(1)

        val catalog = objectBody(text, offsets, root) ?: return null
        val pagesNum = Regex("""/Pages\s+(\d+)\s+0\s+R""").find(catalog)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val pagesBody = objectBody(text, offsets, pagesNum) ?: return null
        val kidsText = Regex("""/Kids\s*\[([^\]]*)]""").find(pagesBody)?.groupValues?.get(1) ?: return null
        val kids = Regex("""(\d+)\s+0\s+R""").findAll(kidsText).map { it.groupValues[1].toInt() }.toList()
        if (kids.size != pageCount) return null

        // Seitenobjekte prüfen, bevor etwas geschrieben wird.
        val pageBodies = HashMap<Int, String>()
        for (index in items.map { it.pageIndex }.distinct()) {
            val body = objectBody(text, offsets, kids[index]) ?: return null
            if (!Regex("""/Type\s*/Page(?![A-Za-z])""").containsMatchIn(body) || "/Annots" in body || "stream" in body) return null
            if (!body.startsWith("<<") || !body.endsWith(">>")) return null
            pageBodies[index] = body
        }

        val out = ByteArrayOutputStream()
        val base = pdf.size.toLong()
        if (pdf.isNotEmpty() && pdf.last() != '\n'.code.toByte()) out.write('\n'.code)
        val newOffsets = java.util.TreeMap<Int, Long>()
        var next = size

        fun writeObject(num: Int, body: ByteArray) {
            newOffsets[num] = base + out.size()
            out.write("$num 0 obj\n".toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.write("\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        }

        val embedded = HashMap<String, Int>()
        val annotsByPage = java.util.TreeMap<Int, MutableList<Int>>()
        val stackedByPage = HashMap<Int, Int>()
        for (item in items) {
            val box = mediaBox(pageBodies.getValue(item.pageIndex)) ?: return null
            val fileObj = embedded.getOrPut(item.key) {
                val num = next++
                val head = "<</Type /EmbeddedFile /Subtype /${mimeName(item.mime)} /Params <</Size ${item.data.size}>> /Length ${item.data.size}>>\nstream\n"
                writeObject(num, head.toByteArray(Charsets.ISO_8859_1) + item.data + "\nendstream".toByteArray(Charsets.ISO_8859_1))
                num
            }
            val specObj = next++
            val spec = "<</Type /Filespec /F (${asciiName(item.name)}) /UF ${hexString(item.name)} /Desc ${hexString(item.desc)} " +
                "/EF <</F $fileObj 0 R /UF $fileObj 0 R>>>>"
            writeObject(specObj, spec.toByteArray(Charsets.ISO_8859_1))

            val stacked = stackedByPage.getOrDefault(item.pageIndex, 0)
            val right = box[2] - MARGIN_PT
            val top = box[3] - MARGIN_PT - stacked * (ICON_PT + GAP_PT)
            stackedByPage[item.pageIndex] = stacked + 1
            val rect = String.format(Locale.ROOT, "%.2f %.2f %.2f %.2f", right - ICON_PT, top - ICON_PT, right, top)
            val annotObj = next++
            val annot = "<</Type /Annot /Subtype /FileAttachment /Rect [$rect] /Contents ${hexString(item.desc)} /FS $specObj 0 R " +
                "/Name /PushPin /F 28 /Border [0 0 0] /C []>>"
            writeObject(annotObj, annot.toByteArray(Charsets.ISO_8859_1))
            annotsByPage.getOrPut(item.pageIndex) { ArrayList() }.add(annotObj)
        }

        for ((index, annots) in annotsByPage) {
            val body = pageBodies.getValue(index).removeSuffix(">>").trimEnd()
            val updated = body + "\n/Annots [" + annots.joinToString(" ") { "$it 0 R" } + "]>>"
            writeObject(kids[index], updated.toByteArray(Charsets.ISO_8859_1))
        }

        val xrefOffset = base + out.size()
        val xref = StringBuilder("xref\n")
        for ((num, offset) in newOffsets) {
            xref.append(num).append(" 1\n").append(String.format(Locale.ROOT, "%010d 00000 n \n", offset))
        }
        xref.append("trailer\n<</Size ").append(next).append(" /Root ").append(root).append(" 0 R")
        if (info != null) xref.append(" /Info ").append(info).append(" 0 R")
        xref.append(" /Prev ").append(startXref).append(">>\nstartxref\n").append(xrefOffset).append("\n%%EOF\n")
        out.write(xref.toString().toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    /** Liest die (einzige) xref-Tabelle ab [position]. Null bei Xref-Streams oder älteren Abschnitten (`/Prev`). */
    private fun readXref(text: String, position: Int): Map<Int, Int>? {
        if (position < 0 || !text.startsWith("xref", position)) return null
        var p = position + 4
        val offsets = HashMap<Int, Int>()
        val header = Regex("""\s*(\d+)\s+(\d+)\s*\n""")
        while (true) {
            val m = header.matchAt(text, p) ?: break
            val first = m.groupValues[1].toInt()
            val count = m.groupValues[2].toInt()
            p = m.range.last + 1
            if (count < 0 || p + count.toLong() * 20 > text.length) return null
            for (i in 0 until count) {
                val entry = text.substring(p + i * 20, p + i * 20 + 20)
                if (entry[17] == 'n') offsets[first + i] = entry.substring(0, 10).trim().toIntOrNull() ?: return null
            }
            p += count * 20
        }
        val trailerAt = text.indexOf("trailer", p)
        if (trailerAt < 0 || text.indexOf("/Prev", trailerAt) in 0 until text.length) return null
        return offsets
    }

    /** Inhalt des Objekts [num] zwischen `obj` und `endobj`, getrimmt. */
    private fun objectBody(text: String, offsets: Map<Int, Int>, num: Int): String? {
        val at = offsets[num] ?: return null
        val head = Regex("""$num\s+0\s+obj\s""").matchAt(text, at) ?: return null
        val end = text.indexOf("endobj", head.range.last + 1)
        if (end < 0) return null
        return text.substring(head.range.last + 1, end).trim()
    }

    private fun mediaBox(pageBody: String): DoubleArray? {
        val m = Regex("""/MediaBox\s*\[\s*([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)\s*]""").find(pageBody) ?: return null
        return DoubleArray(4) { m.groupValues[it + 1].toDoubleOrNull() ?: return null }
    }

    private fun mimeName(mime: String): String = mime.replace(Regex("[^A-Za-z0-9.+-/]"), "").replace("/", "#2F")

    private fun asciiName(name: String): String = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "audio" }

    /** Textstring als UTF-16BE mit BOM in Hex-Schreibweise. */
    private fun hexString(text: String): String {
        val sb = StringBuilder("<FEFF")
        for (c in text) sb.append(String.format(Locale.ROOT, "%04X", c.code))
        return sb.append('>').toString()
    }
}
