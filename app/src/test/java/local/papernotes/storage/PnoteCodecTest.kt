package local.papernotes.storage

import local.papernotes.model.Note
import local.papernotes.model.NoteFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

class PnoteCodecTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun decode(bytes: ByteArray) = PnoteCodec.decode(ByteArrayInputStream(bytes))

    private fun assertErr(expected: PnoteError, bytes: ByteArray) {
        val r = decode(bytes)
        assertEquals(expected, r.errorOrNull())
    }

    @Test
    fun roundtripKeepsEverythingExactly() {
        val note = TestData.bigNote(pages = 3, strokesPerPage = 4, points = 500) // 12 Striche... plus Seiten
        val file = File(tmp.root, "a.pnote")
        assertTrue(PnoteCodec.write(note, file) is PnoteResult.Ok)
        val back = PnoteCodec.read(file).valueOrNull()
        assertEquals(note, back)
        assertEquals(note.pageIds, back!!.pageIds)
        val p = note.pages.getValue(note.pageIds[2]).strokes[3].points[499]
        val q = back.pages.getValue(note.pageIds[2]).strokes[3].points[499]
        assertEquals(p.x.toRawBits(), q.x.toRawBits())
        assertEquals(p.pressure.toRawBits(), q.pressure.toRawBits())
        assertEquals(p.time, q.time)
    }

    @Test
    fun roundtripOfTextboxes() {
        val base = TestData.simpleNote()
        val pid = base.pageIds[0]
        val textbox1 = local.papernotes.model.Textbox("tb-1", "Hallo Welt", 100f, 200f, 300f, 150f, 24f)
        val textbox2 = local.papernotes.model.Textbox("tb-2", "Hallo Grau", 150f, 250f, 350f, 170f, 28f, 0xFFF5F5F5.toInt())
        val page = base.pages.getValue(pid).copy(textboxes = listOf(textbox1, textbox2))
        val note = base.copy(pages = mapOf(pid to page))
        val file = File(tmp.root, "textbox.pnote")
        assertTrue(PnoteCodec.write(note, file) is PnoteResult.Ok)
        val back = PnoteCodec.read(file).valueOrNull()!!
        assertEquals(note, back)
        assertEquals(listOf(textbox1, textbox2), back.pages.getValue(pid).textboxes)
    }

    @Test
    fun roundtripOfRichTextbox() {
        val base = TestData.simpleNote()
        val pid = base.pageIds[0]
        val tb = local.papernotes.model.Textbox(
            "tb-r", "Titel\nEins\nZwei", 10f, 20f, 300f, 150f, 24f, 0,
            spans = listOf(
                local.papernotes.model.TextSpan(local.papernotes.model.SpanType.BOLD, 0, 5),
                local.papernotes.model.TextSpan(local.papernotes.model.SpanType.SIZE, 0, 5, 150),
                local.papernotes.model.TextSpan(local.papernotes.model.SpanType.COLOR, 6, 10, 0xFFC62828.toInt()),
            ),
            paragraphs = listOf(
                local.papernotes.model.ParaFormat(0, align = local.papernotes.model.ParaFormat.ALIGN_CENTER),
                local.papernotes.model.ParaFormat(2, list = local.papernotes.model.ParaFormat.LIST_NUMBER, level = 1),
            ),
        )
        val page = base.pages.getValue(pid).copy(textboxes = listOf(tb))
        val note = base.copy(pages = mapOf(pid to page))
        val file = File(tmp.root, "rich.pnote")
        assertTrue(PnoteCodec.write(note, file) is PnoteResult.Ok)
        val back = PnoteCodec.read(file).valueOrNull()!!
        assertEquals(listOf(tb), back.pages.getValue(pid).textboxes)
    }

    @Test
    fun richTextboxWithInvalidEntriesIsCleaned() {
        val id = TestData.uuid()
        val pageJson = TestData.pageJson(
            ""","textboxes":[{"id":"t","text":"abc","x":0,"y":0,"w":10,"h":10,"size":12,""" +
                """"spans":[[0,1,2,0],[9,5,99,0],[0,0,3,0],[7,-1,2,0]],"paras":[[0,1,0,0],[5,1,0,0],[1,0,0,0]]}]""",
        )
        val bytes = TestData.zip(
            listOf(
                "manifest.json" to TestData.manifest(pageIds = listOf(id)),
                "pages/$id.json" to pageJson,
            ),
        )
        val tb = decode(bytes).valueOrNull()!!.pages.getValue(id).textboxes.single()
        // Unbekannte Typen (9, 7) entfallen, die beiden Fett-Bereiche verschmelzen zu 0..3.
        assertEquals(listOf(local.papernotes.model.TextSpan(local.papernotes.model.SpanType.BOLD, 0, 3)), tb.spans)
        assertEquals(listOf(local.papernotes.model.ParaFormat(0, align = 1)), tb.paragraphs)
    }

    @Test
    fun richTextboxWithBadShapeIsCorrupt() {
        val id = TestData.uuid()
        val pageJson = TestData.pageJson(
            ""","textboxes":[{"id":"t","text":"abc","x":0,"y":0,"w":10,"h":10,"size":12,"spans":[[0,1,2]]}]""",
        )
        val bytes = TestData.zip(
            listOf(
                "manifest.json" to TestData.manifest(pageIds = listOf(id)),
                "pages/$id.json" to pageJson,
            ),
        )
        assertEquals(PnoteError.Corrupt, decode(bytes).errorOrNull())
    }

    @Test
    fun roundtripOfImagesAndMedia() {
        val base = TestData.simpleNote()
        val pid = base.pageIds[0]
        val name = "${TestData.uuid()}.png"
        val bytes = ByteArray(1000) { (it * 7).toByte() }
        val image = local.papernotes.model.ImageAttachment("img-1", name, 10f, 20f, 300f, 200f)
        val page = base.pages.getValue(pid).copy(images = listOf(image))
        val note = base.copy(pages = mapOf(pid to page), media = mapOf(name to bytes))
        val file = File(tmp.root, "image.pnote")
        assertTrue(PnoteCodec.write(note, file) is PnoteResult.Ok)
        val back = PnoteCodec.read(file).valueOrNull()!!
        assertEquals(note, back)
        assertEquals(listOf(image), back.pages.getValue(pid).images)
        assertArrayEquals(bytes, back.media.getValue(name))
    }

    @Test
    fun roundtripOfAudios() {
        val base = TestData.simpleNote()
        val pid = base.pageIds[0]
        val name = "${TestData.uuid()}.m4a"
        val bytes = ByteArray(2000) { (it * 3).toByte() }
        val audio = local.papernotes.model.AudioAttachment("au-1", name, "Aufnahme 1", 12_345L)
        val page = base.pages.getValue(pid).copy(audios = listOf(audio))
        val note = base.copy(pages = mapOf(pid to page), media = mapOf(name to bytes))
        val file = File(tmp.root, "audio.pnote")
        assertTrue(PnoteCodec.write(note, file) is PnoteResult.Ok)
        val back = PnoteCodec.read(file).valueOrNull()!!
        assertEquals(note, back)
        assertEquals(listOf(audio), back.pages.getValue(pid).audios)
        assertArrayEquals(bytes, back.media.getValue(name))
        assertEquals(1, PnoteCodec.readSummary(file).valueOrNull()!!.audioCount)
    }

    private fun mediaNote(): Pair<Note, Map<String, ByteArray>> {
        val base = TestData.simpleNote()
        val pid = base.pageIds[0]
        val png = "${TestData.uuid()}.png"
        val m4a = "${TestData.uuid()}.m4a"
        val media = mapOf(
            png to ByteArray(5000) { (it * 7).toByte() },
            m4a to ByteArray(20_000) { (it * 31 + 5).toByte() },
        )
        val page = base.pages.getValue(pid).copy(
            images = listOf(local.papernotes.model.ImageAttachment("img-1", png, 10f, 20f, 300f, 200f)),
            audios = listOf(local.papernotes.model.AudioAttachment("au-1", m4a, "Aufnahme 1", 12_345L)),
        )
        return base.copy(pages = mapOf(pid to page), media = media, preview = jpeg) to media
    }

    @Test
    fun mediaAndPreviewAreStoredUncompressedWhileJsonIsDeflated() {
        val (note, media) = mediaNote()
        val file = File(tmp.root, "stored.pnote")
        assertTrue(PnoteCodec.write(note, file) is PnoteResult.Ok)
        java.util.zip.ZipFile(file).use { zf ->
            val entries = zf.entries().toList().associateBy { it.name }
            for ((name, bytes) in media) {
                val e = entries.getValue("media/$name")
                assertEquals(java.util.zip.ZipEntry.STORED, e.method)
                assertEquals(bytes.size.toLong(), e.size)
                assertEquals(bytes.size.toLong(), e.compressedSize)
                assertArrayEquals(bytes, zf.getInputStream(e).readBytes())
            }
            assertEquals(java.util.zip.ZipEntry.STORED, entries.getValue("preview.jpg").method)
            assertEquals(java.util.zip.ZipEntry.DEFLATED, entries.getValue("manifest.json").method)
            assertTrue(entries.filterKeys { it.startsWith("pages/") }.values.all { it.method == java.util.zip.ZipEntry.DEFLATED })
        }
        // Optional: Beispieldatei für externe Leser (unzip, PHP ZipArchive) ablegen.
        System.getenv("PNOTE_SAMPLE_DIR")?.let { dir ->
            File(dir).mkdirs()
            file.copyTo(File(dir, "stored-media.pnote"), overwrite = true)
        }
    }

    @Test
    fun storedMediaAreReadByAllReaders() {
        val (note, media) = mediaNote()
        val file = File(tmp.root, "readers.pnote")
        assertTrue(PnoteCodec.write(note, file) is PnoteResult.Ok)
        val back = PnoteCodec.read(file).valueOrNull()!!
        assertEquals(note, back)
        for ((name, bytes) in media) assertArrayEquals(bytes, back.media.getValue(name))
        assertTrue(PnoteCodec.readSummary(file) is PnoteResult.Ok)
        assertEquals(1, PnoteCodec.readSummary(file).valueOrNull()!!.audioCount)
        assertArrayEquals(jpeg, PnoteCodec.readPreview(file))
        // decode aus einem Stream (Sicherung/Teilen)
        val out = java.io.ByteArrayOutputStream()
        assertTrue(PnoteCodec.encode(note, out) is PnoteResult.Ok)
        assertEquals(note, decode(out.toByteArray()).valueOrNull())
    }

    @Test
    fun emptyMediaAndIncompressibleMediaAtEntryLimitRoundtrip() {
        val base = TestData.simpleNote()
        val empty = "${TestData.uuid()}.png"
        val big = "${TestData.uuid()}.m4a"
        val random = java.util.Random(42)
        val bigBytes = ByteArray(PnoteCodec.MAX_ENTRY_BYTES.toInt()).also { random.nextBytes(it) }
        val note = base.copy(media = mapOf(empty to ByteArray(0), big to bigBytes))
        val file = File(tmp.root, "limit.pnote")
        assertTrue(PnoteCodec.write(note, file) is PnoteResult.Ok)
        val back = PnoteCodec.read(file).valueOrNull()!!
        assertEquals(0, back.media.getValue(empty).size)
        assertArrayEquals(bigBytes, back.media.getValue(big))
    }

    @Test
    fun storedMediaStillRespectLimitsWhenReading() {
        // 1:1 gespeicherte Einträge (kein Kompressionsfaktor) werden nicht abgelehnt, solange sie unter den Grenzen liegen
        val ok = ByteArray(PnoteCodec.MAX_ENTRY_BYTES.toInt()).also { java.util.Random(1).nextBytes(it) }
        assertTrue(decode(okZip(listOf("media/${TestData.uuid()}.bin" to ok), stored = true)) is PnoteResult.Ok)
        // über dem Eintragslimit weiterhin abgelehnt, auch wenn der Eintrag STORED ist
        val tooBig = ByteArray(PnoteCodec.MAX_ENTRY_BYTES.toInt() + 1).also { java.util.Random(2).nextBytes(it) }
        assertErr(PnoteError.LimitExceeded, okZip(listOf("media/${TestData.uuid()}.bin" to tooBig), stored = true))
        // Gesamtgrenze: 5 x 15 MB zufällige Bytes
        val chunk = ByteArray(15 * 1024 * 1024).also { java.util.Random(3).nextBytes(it) }
        val many = List(5) { "media/${TestData.uuid()}.bin" to chunk }
        assertErr(PnoteError.LimitExceeded, okZip(many, stored = true))
    }

    @Test
    fun writeRejectsMediaOverTheLimitsStill() {
        val file = File(tmp.root, "toobig.pnote")
        val tooBig = TestData.simpleNote().copy(media = mapOf("${TestData.uuid()}.bin" to ByteArray(PnoteCodec.MAX_ENTRY_BYTES.toInt() + 1)))
        assertEquals(PnoteError.LimitExceeded, PnoteCodec.write(tooBig, file).errorOrNull())
        assertFalse(file.exists())
    }

    @Test
    fun summaryOfNoteWithoutAudiosHasCountZero() {
        val file = File(tmp.root, "noaudio.pnote")
        PnoteCodec.write(TestData.simpleNote(), file)
        assertEquals(0, PnoteCodec.readSummary(file).valueOrNull()!!.audioCount)
    }

    @Test
    fun pageWithoutAudiosFieldReadsAsEmpty() {
        val back = decode(okZip()).valueOrNull()!!
        assertTrue(back.pages.values.all { it.audios.isEmpty() })
    }

    @Test
    fun audioWithInvalidFileNameIsCorrupt() {
        val pid = TestData.uuid()
        val json = TestData.pageJson(""","audios":[{"id":"a","file":"../x.m4a","title":"t","duration":1}]""")
        val zip = TestData.zip(listOf("manifest.json" to TestData.manifest(pageIds = listOf(pid)), "pages/$pid.json" to json))
        assertErr(PnoteError.Corrupt, zip)
    }

    @Test
    fun differentMediaBytesMakeNotesUnequal() {
        val name = "${TestData.uuid()}.png"
        val a = TestData.simpleNote().copy(media = mapOf(name to byteArrayOf(1, 2)))
        assertEquals(a, a.copy(media = mapOf(name to byteArrayOf(1, 2))))
        assertEquals(a.hashCode(), a.copy(media = mapOf(name to byteArrayOf(1, 2))).hashCode())
        assertFalse(a == a.copy(media = mapOf(name to byteArrayOf(1, 3))))
        assertFalse(a == a.copy(media = emptyMap()))
    }

    @Test
    fun mediaWithInvalidNameIsRejectedOnWrite() {
        val note = TestData.simpleNote().copy(media = mapOf("../evil.png" to byteArrayOf(1)))
        assertEquals(PnoteError.InvalidNote, PnoteCodec.encode(note, java.io.ByteArrayOutputStream()).errorOrNull())
    }

    @Test
    fun imageWithInvalidFileNameIsCorrupt() {
        val pid = TestData.uuid()
        val json = TestData.pageJson(""","images":[{"id":"a","file":"../x.png","x":0,"y":0,"w":1,"h":1}]""")
        val zip = TestData.zip(listOf("manifest.json" to TestData.manifest(pageIds = listOf(pid)), "pages/$pid.json" to json))
        assertErr(PnoteError.Corrupt, zip)
    }

    @Test
    fun mediaEntriesAreReadFromZip() {
        val name = "${TestData.uuid()}.jpg"
        val back = decode(okZip(listOf("media/$name" to byteArrayOf(5, 6, 7)))).valueOrNull()!!
        assertArrayEquals(byteArrayOf(5, 6, 7), back.media.getValue(name))
    }

    @Test
    fun roundtripTenStrokesOnThreePages() {
        val note = TestData.bigNote(pages = 3, strokesPerPage = 10, points = 2000)
        assertEquals(note, decode(TestData.encode(note)).valueOrNull())
    }

    @Test
    fun roundtripOfExtremeFloatsAndTimes() {
        val base = TestData.simpleNote()
        val pid = base.pageIds[0]
        val pts = listOf(
            local.papernotes.model.Point(Float.MIN_VALUE, Float.MAX_VALUE, Long.MAX_VALUE, 0.1f),
            local.papernotes.model.Point(-0f, 1.17549435E-38f, 0L, 1f / 3f),
        )
        val page = base.pages.getValue(pid).copy(strokes = listOf(local.papernotes.model.Stroke(7, 0.3f, -1, pts)))
        val note = base.copy(pages = mapOf(pid to page))
        val back = decode(TestData.encode(note)).valueOrNull()!!
        assertEquals(pts[0], back.pages.getValue(pid).strokes[0].points[0])
        assertEquals(pts[1].x.toRawBits(), back.pages.getValue(pid).strokes[0].points[1].x.toRawBits())
        assertEquals(pts[1].pressure, back.pages.getValue(pid).strokes[0].points[1].pressure, 0f)
    }

    @Test
    fun newNoteHasOneBlankPage() {
        val n = NoteFactory.newNote("Titel")
        assertEquals(1, n.pageIds.size)
        val page = n.pages.getValue(n.pageIds[0])
        assertEquals("blank", page.template)
        assertEquals(1404, page.width)
        assertEquals(1872, page.height)
        assertTrue(PnoteCodec.isValidId(n.id))
    }

    // ------------------------------------------------------------ Atomares Schreiben

    @Test
    fun failedWriteKeepsExistingFileAndLeavesNoTmp() {
        val note = TestData.bigNote(pages = 1, strokesPerPage = 1, points = 10)
        val file = File(tmp.root, "keep.pnote")
        assertTrue(PnoteCodec.write(note, file) is PnoteResult.Ok)
        val before = file.readBytes()

        val r = PnoteCodec.writeAtomic(file) { out ->
            out.write(ByteArray(1000) { 1 })
            throw IOException("simuliert")
        }
        assertEquals(PnoteError.Io, r.errorOrNull())
        assertArrayEquals(before, file.readBytes())
        assertFalse(File(tmp.root, "keep.pnote.tmp").exists())
        assertEquals(listOf("keep.pnote"), tmp.root.list()!!.toList())
    }

    @Test
    fun runtimeExceptionInEncoderAlsoCleansUp() {
        val file = File(tmp.root, "x.pnote")
        val r = PnoteCodec.writeAtomic(file) { throw IllegalStateException("boom") }
        assertEquals(PnoteError.Io, r.errorOrNull())
        assertFalse(file.exists())
        assertEquals(0, tmp.root.list()!!.size)
    }

    @Test
    fun unwritableDirectoryKeepsOldFile() {
        val dir = tmp.newFolder("ro")
        val file = File(dir, "n.pnote")
        val note = TestData.simpleNote()
        assertTrue(PnoteCodec.write(note, file) is PnoteResult.Ok)
        val before = file.readBytes()
        assertTrue(dir.setWritable(false))
        try {
            // Als root (z. B. CI) greift die Rechteprüfung nicht; dann ist nichts zu prüfen.
            val reallyReadOnly = try {
                File(dir, "probe").createNewFile().not()
            } catch (e: IOException) {
                true
            }
            if (reallyReadOnly) {
                val r = PnoteCodec.write(note.copy(title = "neu"), file)
                assertEquals(PnoteError.Io, r.errorOrNull())
                assertArrayEquals(before, file.readBytes())
            }
        } finally {
            dir.setWritable(true)
        }
        assertEquals(listOf("n.pnote"), dir.list()!!.toList())
    }

    @Test
    fun invalidNoteIsRejectedBeforeAnythingIsWritten() {
        val file = File(tmp.root, "bad.pnote")
        val n = TestData.simpleNote().copy(id = "../evil")
        assertEquals(PnoteError.InvalidId, PnoteCodec.write(n, file).errorOrNull())
        val n2 = TestData.simpleNote().copy(folderPath = "../x")
        assertEquals(PnoteError.InvalidFolder, PnoteCodec.write(n2, file).errorOrNull())
        assertEquals(0, tmp.root.list()!!.size)
    }

    // ------------------------------------------------------------ Sicherheit beim Lesen

    private fun okZip(extra: List<Pair<String, ByteArray>> = emptyList(), stored: Boolean = false): ByteArray {
        val pid = TestData.uuid()
        return TestData.zip(
            listOf("manifest.json" to TestData.manifest(pageIds = listOf(pid)), "pages/$pid.json" to TestData.pageJson()) + extra,
            stored,
        )
    }

    @Test
    fun wellFormedHandwrittenZipIsAccepted() {
        assertTrue(decode(okZip()) is PnoteResult.Ok)
    }

    @Test
    fun optionalPreviewAndMediaAreTolerated() {
        val extra = listOf("preview.png" to ByteArray(10), "media/${TestData.uuid()}.png" to ByteArray(5))
        assertTrue(decode(okZip(extra)) is PnoteResult.Ok)
    }

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4, 5)

    @Test
    fun previewRoundtripsAndIsReadableWithoutPages() {
        val note = TestData.bigNote(pages = 2, strokesPerPage = 2, points = 50).copy(preview = jpeg)
        val file = File(tmp.root, "p.pnote")
        assertTrue(PnoteCodec.write(note, file) is PnoteResult.Ok)
        assertArrayEquals(jpeg, PnoteCodec.read(file).valueOrNull()!!.preview)
        assertArrayEquals(jpeg, PnoteCodec.readPreview(file))
        assertEquals(note, PnoteCodec.read(file).valueOrNull())
    }

    @Test
    fun noteWithoutPreviewHasNone() {
        val note = TestData.simpleNote()
        val file = File(tmp.root, "n.pnote")
        assertTrue(PnoteCodec.write(note, file) is PnoteResult.Ok)
        assertEquals(null, PnoteCodec.read(file).valueOrNull()!!.preview)
        assertEquals(null, PnoteCodec.readPreview(file))
    }

    @Test
    fun readPreviewToleratesMissingAndBrokenFiles() {
        assertEquals(null, PnoteCodec.readPreview(File(tmp.root, "gibt-es-nicht.pnote")))
        val bad = File(tmp.root, "bad.pnote").apply { writeBytes(ByteArray(64) { 9 }) }
        assertEquals(null, PnoteCodec.readPreview(bad))
    }

    @Test
    fun writeRejectsPreviewThatIsNotJpegOrTooLarge() {
        val file = File(tmp.root, "x.pnote")
        val notJpeg = TestData.simpleNote().copy(preview = ByteArray(20) { 1 })
        assertEquals(PnoteError.InvalidNote, PnoteCodec.write(notJpeg, file).errorOrNull())
        val big = ByteArray(PnoteCodec.MAX_PREVIEW_BYTES + 1).also { System.arraycopy(jpeg, 0, it, 0, jpeg.size) }
        assertEquals(PnoteError.LimitExceeded, PnoteCodec.write(TestData.simpleNote().copy(preview = big), file).errorOrNull())
        assertFalse(file.exists())
    }

    @Test
    fun readDropsInvalidPreviewButKeepsNote() {
        val pageId = TestData.uuid()
        val entries = listOf(
            "manifest.json" to TestData.manifest(pageIds = listOf(pageId)),
            "preview.jpg" to ByteArray(30) { 1 },
            "pages/$pageId.json" to TestData.pageJson(),
        )
        val note = decode(TestData.zip(entries)).valueOrNull()!!
        assertEquals(null, note.preview)
        assertEquals(1, note.pageIds.size)
    }

    @Test
    fun readPreviewIgnoresOversizedEntry() {
        val big = ByteArray(PnoteCodec.MAX_PREVIEW_BYTES + 10).also { System.arraycopy(jpeg, 0, it, 0, jpeg.size) }
        val file = File(tmp.root, "big.pnote").apply {
            writeBytes(TestData.zip(listOf("manifest.json" to TestData.manifest(), "preview.jpg" to big)))
        }
        assertEquals(null, PnoteCodec.readPreview(file))
    }

    @Test
    fun rejectsPathTraversalEntry() {
        assertErr(PnoteError.InvalidEntryName, okZip(listOf("../evil" to ByteArray(1))))
        assertErr(PnoteError.InvalidEntryName, okZip(listOf("pages/../../evil.json" to ByteArray(1))))
    }

    @Test
    fun rejectsAbsolutePath() {
        assertErr(PnoteError.InvalidEntryName, okZip(listOf("/etc/passwd" to ByteArray(1))))
    }

    @Test
    fun rejectsBackslash() {
        assertErr(PnoteError.InvalidEntryName, okZip(listOf("pages\\x.json" to ByteArray(1))))
        assertErr(PnoteError.InvalidEntryName, okZip(listOf("..\\evil" to ByteArray(1))))
    }

    @Test
    fun rejectsUnexpectedNames() {
        assertErr(PnoteError.InvalidEntryName, okZip(listOf("evil.txt" to ByteArray(1))))
        assertErr(PnoteError.InvalidEntryName, okZip(listOf("pages/not-a-uuid.json" to ByteArray(1))))
        assertErr(PnoteError.InvalidEntryName, okZip(listOf("media/${TestData.uuid()}.p/ng" to ByteArray(1))))
        assertErr(PnoteError.InvalidEntryName, okZip(listOf("a".repeat(300) to ByteArray(1))))
    }

    @Test
    fun rejectsZipBombEntryOverEntryLimit() {
        // 17 MB Nullen komprimieren auf wenige KB, entpackt liegen sie über dem Limit von 16 MB je Eintrag.
        val bomb = ByteArray((PnoteCodec.MAX_ENTRY_BYTES + 1024 * 1024).toInt())
        val bytes = okZip(listOf("media/${TestData.uuid()}.bin" to bomb))
        assertTrue("sollte klein komprimiert sein", bytes.size < 200_000)
        assertErr(PnoteError.LimitExceeded, bytes)
    }

    @Test
    fun rejectsTotalSizeOverLimit() {
        val chunk = ByteArray(15 * 1024 * 1024)
        val entries = List(5) { "media/${TestData.uuid()}.bin" to chunk } // 75 MB > 64 MB
        assertErr(PnoteError.LimitExceeded, okZip(entries))
    }

    @Test
    fun rejectsTooManyEntries() {
        val entries = List(PnoteCodec.MAX_ENTRIES + 1) { "media/${TestData.uuid()}.png" to ByteArray(1) }
        assertErr(PnoteError.LimitExceeded, okZip(entries))
    }

    @Test
    fun rejectsUnknownNewerVersion() {
        val next = PnoteCodec.FORMAT_VERSION + 1
        val z = TestData.zip(listOf("manifest.json" to TestData.manifest(version = next)))
        assertEquals(PnoteError.UnsupportedVersion(next), decode(z).errorOrNull())
    }

    // ------------------------------------------------------------ Format-Version (2 ist das Format von 1.0.0)

    private fun manifestVersion(bytes: ByteArray): Int {
        val zip = java.util.zip.ZipInputStream(ByteArrayInputStream(bytes))
        while (true) {
            val e = zip.nextEntry ?: error("kein Manifest")
            if (e.name == "manifest.json") {
                return Regex("\"version\":(\\d+)").find(String(zip.readBytes()))!!.groupValues[1].toInt()
            }
        }
    }

    @Test
    fun writesCurrentFormatVersion() {
        assertEquals(2, PnoteCodec.FORMAT_VERSION)
        assertEquals(PnoteCodec.FORMAT_VERSION, manifestVersion(TestData.encode(TestData.simpleNote())))
    }

    @Test
    fun rejectsVersionOneBecauseItWasNeverShipped() {
        // Version 1 (Ansichts-Pixel) wurde nie ausgeliefert; ab 1.0.0 ist Version 2 die kleinste lesbare Version.
        val pid = TestData.uuid()
        val page = """{"template":"blank","width":1404,"height":1872,"bookmarked":false,"strokes":[]}"""
        val z = TestData.zip(
            listOf(
                "manifest.json" to TestData.manifest(pageIds = listOf(pid), version = 1),
                "pages/$pid.json" to page.toByteArray(),
            ),
        )
        assertEquals(PnoteError.Corrupt, decode(z).errorOrNull())
    }

    @Test
    fun rejectsVersionZero() {
        val z = TestData.zip(listOf("manifest.json" to TestData.manifest(version = 0)))
        assertEquals(PnoteError.Corrupt, decode(z).errorOrNull())
    }

    @Test
    fun rejectsNonUuidPageId() {
        val z = TestData.zip(
            listOf(
                "manifest.json" to TestData.manifest(pageIds = listOf("../../evil")),
            ),
        )
        assertEquals(PnoteError.InvalidId, decode(z).errorOrNull())
    }

    @Test
    fun rejectsNonUuidNoteId() {
        val z = TestData.zip(listOf("manifest.json" to TestData.manifest(id = "../../x")))
        assertEquals(PnoteError.InvalidId, decode(z).errorOrNull())
    }

    @Test
    fun rejectsEvilFolderPathInManifest() {
        val z = TestData.zip(listOf("manifest.json" to TestData.manifest(folder = "../x")))
        assertEquals(PnoteError.InvalidFolder, decode(z).errorOrNull())
    }

    @Test
    fun rejectsMissingManifestAndMissingPage() {
        assertEquals(PnoteError.MissingManifest, decode(TestData.zip(listOf("preview.png" to ByteArray(1)))).errorOrNull())
        val z = TestData.zip(listOf("manifest.json" to TestData.manifest(pageIds = listOf(TestData.uuid()))))
        assertEquals(PnoteError.Corrupt, decode(z).errorOrNull())
    }

    @Test
    fun rejectsGarbage() {
        assertEquals(PnoteError.MissingManifest, decode(ByteArray(100) { 7 }).errorOrNull())
        val z = TestData.zip(listOf("manifest.json" to "{kaputt".toByteArray()))
        assertEquals(PnoteError.Corrupt, decode(z).errorOrNull())
    }

    @Test
    fun rejectsTooManyPointsPerStroke() {
        val pid = TestData.uuid()
        val pts = (0..PnoteCodec.MAX_POINTS_PER_STROKE).joinToString(",") { "[1,2,3,0.5]" }
        val page = """{"template":"blank","width":10,"height":20,"bookmarked":false,"strokes":[{"t":1,"w":1,"c":0,"p":[$pts]}]}"""
        val z = TestData.zip(
            listOf("manifest.json" to TestData.manifest(pageIds = listOf(pid)), "pages/$pid.json" to page.toByteArray()),
        )
        assertEquals(PnoteError.LimitExceeded, decode(z).errorOrNull())
    }

    // ------------------------------------------------------------ Vorwärtskompatibilität

    @Test
    fun unknownFieldsAreIgnored() {
        val pid = TestData.uuid()
        val page = """{"textboxes":[{"x":1}],"template":"grid","width":10,"height":20,"bookmarked":true,""" +
            """"images":["a"],"strokes":[{"t":2,"w":1.5,"c":-16777216,"future":{"a":[1]},"p":[[1.5,2.5,3,0.25]]}],"zzz":null}"""
        val z = TestData.zip(
            listOf(
                "manifest.json" to TestData.manifest(pageIds = listOf(pid), extra = ""","syncHash":"abc","tags":["a"]"""),
                "pages/$pid.json" to page.toByteArray(),
            ),
        )
        val note = decode(z).valueOrNull()!!
        val p = note.pages.getValue(pid)
        assertEquals("grid", p.template)
        assertTrue(p.bookmarked)
        assertEquals(1, p.strokes.size)
        assertEquals(local.papernotes.model.Point(1.5f, 2.5f, 3L, 0.25f), p.strokes[0].points[0])
        assertEquals(-16777216, p.strokes[0].color)
    }

    @Test
    fun bookmarkRoundtripsAndStaysPerPage() {
        val base = TestData.bigNote(pages = 3, strokesPerPage = 0)
        val ids = base.pageIds
        val note = base.copy(
            pages = base.pages.mapValues { (id, p) -> p.copy(bookmarked = id == ids[0] || id == ids[2]) },
        )
        val back = decode(TestData.encode(note)).valueOrNull()!!
        assertEquals(listOf(true, false, true), ids.map { back.pages.getValue(it).bookmarked })
        assertEquals(note, back)
    }

    @Test
    fun pageWithoutBookmarkFieldReadsAsNotBookmarked() {
        val pid = TestData.uuid()
        val page = """{"template":"blank","width":10,"height":20,"strokes":[]}"""
        val z = TestData.zip(
            listOf(
                "manifest.json" to TestData.manifest(pageIds = listOf(pid), version = PnoteCodec.FORMAT_VERSION),
                "pages/$pid.json" to page.toByteArray(),
            ),
        )
        assertFalse(decode(z).valueOrNull()!!.pages.getValue(pid).bookmarked)
    }

    @Test
    fun readSummaryReadsOnlyManifest() {
        val note = TestData.bigNote(pages = 2, strokesPerPage = 1, points = 5, folder = "A/B")
        val file = File(tmp.root, "s.pnote")
        PnoteCodec.write(note, file)
        val s = PnoteCodec.readSummary(file).valueOrNull()!!
        assertEquals(note.id, s.id)
        assertEquals(note.title, s.title)
        assertEquals("A/B", s.folderPath)
        assertEquals(2, s.pageCount)
        assertTrue(s.favorite)
    }
}
