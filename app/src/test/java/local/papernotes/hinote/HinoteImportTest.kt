package local.papernotes.hinote

import local.papernotes.hinote.HinoteTestData.P
import local.papernotes.hinote.HinoteTestData.S
import local.papernotes.editor.TemplateCatalog
import local.papernotes.model.NoteFactory
import local.papernotes.storage.NoteRepository
import local.papernotes.storage.PnoteCodec
import local.papernotes.storage.PnoteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.Random

class HinoteImportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun read(bytes: ByteArray) = HinoteContainerReader.read(ByteArrayInputStream(bytes))

    private fun source(name: String, bytes: ByteArray) = HinoteImportSource(name) { ByteArrayInputStream(bytes) }

    @Test
    fun roundtripMapsNoteAndPages() {
        val container = read(HinoteTestData.hinote()).valueOrNull()!!
        assertEquals(HinoteTestData.NOTE_ID, container.noteId)
        assertEquals(listOf(HinoteTestData.PAGE1, HinoteTestData.PAGE2, HinoteTestData.PAGE3), container.pages.map { it.id })

        val imported = HinoteImporter.importNote(container, "HiNote-Import", "Fallback", now = 123L)
        val note = imported.note
        assertEquals(0, imported.warnings)
        assertEquals("Testnotiz", note.title)
        assertEquals("HiNote-Import", note.folderPath)
        assertEquals("hinote:" + HinoteTestData.NOTE_ID, note.importSource)
        assertTrue(note.favorite)
        assertEquals(1000L, note.created) // Minimum der Seiten
        assertEquals(9000L, note.modified) // Maximum der Seiten
        val pages = note.pageIds.map { note.pages.getValue(it) }
        assertEquals(listOf("blank", "todo", "dotted"), pages.map { it.template })
        assertEquals(listOf(true, false, false), pages.map { it.bookmarked })
        assertTrue(pages.all { it.width == 1404 && it.height == 1872 })
        assertEquals(0, pages[1].strokes.size) // Seite ohne .bin
        val stroke = pages[0].strokes.single()
        assertEquals(2, stroke.penType)
        assertEquals(0xFF000000.toInt(), stroke.color)
        assertEquals(2f * HinoteImporter.STROKE_WIDTH_FACTOR, stroke.width, 0f)
        assertEquals(0f, stroke.points[0].x, 0f)
        assertEquals(702f, stroke.points[1].x, 0.01f) // 500 * 1,404
        assertEquals(140.4f, stroke.points[1].y, 0.01f) // y mit demselben Maßstab, oben ausgerichtet
        assertEquals(10L, stroke.points[1].time)
        assertEquals(0.5f, stroke.points[1].pressure, 0f)
    }

    @Test
    fun importNoteParsesTextboxesAndImages() {
        val elementsJson = """[
            {"elementType":0,"positionX":0.1,"positionY":0.2,"width":0.3,"height":0.4,"contentText":"Dies ist ein Test"},
            {"elementType":1,"positionX":0.5,"positionY":0.6,"width":0.2,"height":0.3,"filePath":"/some/path/image1.png"}
        ]"""
        val imgBytes = byteArrayOf(1, 2, 3, 4, 5)
        val PAGE_ELEMENTS = "a1b2c3d4e5f67890abcdef1234567890"
        val zipBytes = HinoteTestData.hinote(
            extra = listOf(
                "pages/$PAGE_ELEMENTS.jhinote" to HinoteTestData.gzip(
                    HinoteTestData.pageJson(
                        id = PAGE_ELEMENTS,
                        number = 4,
                        background = "plan2",
                        elements = elementsJson
                    )
                ),
                "files/image1.png" to imgBytes
            )
        )
        val container = read(zipBytes).valueOrNull()!!
        assertTrue(container.images["image1.png"].contentEquals(imgBytes))

        val imported = HinoteImporter.importNote(container, "HiNote-Import", "Fallback", now = 123L)
        val note = imported.note
        assertEquals(0, imported.warnings)

        // Verify media map contains the newly mapped image
        val newImgName = note.media.keys.single()
        assertTrue(newImgName.endsWith(".png"))
        assertTrue(note.media[newImgName].contentEquals(imgBytes))

        // Verify Page 4 (index 3) has the textbox and image correctly converted
        val pages = note.pageIds.map { note.pages.getValue(it) }
        val tb = pages[3].textboxes.single()
        assertEquals("Dies ist ein Test", tb.text)
        assertEquals(0.1f * 1404f, tb.x, 0.01f)
        assertEquals(0.2f * 1872f, tb.y, 0.01f)
        assertEquals(0.3f * 1404f, tb.width, 0.01f)
        assertEquals(0.4f * 1872f, tb.height, 0.01f)

        val img = pages[3].images.single()
        assertEquals(newImgName, img.filePath)
        assertEquals(0.5f * 1404f, img.x, 0.01f)
        assertEquals(0.6f * 1872f, img.y, 0.01f)
        assertEquals(0.2f * 1404f, img.width, 0.01f)
        assertEquals(0.3f * 1872f, img.height, 0.01f)
    }

    @Test
    fun importNoteTakesRichTextFromHtml() {
        val html = """<note><element type="TEXT"><div align="center"><b>Titel</b></div><br>""" +
            """<tab_span style ="type:4,level:1,number:0"><font color ="#ffff0000">rot</font></tab_span></element></note>"""
        val elementsJson = """[{"elementType":0,"positionX":0.1,"positionY":0.2,"width":0.3,"height":0.4,""" +
            """"contentText":"Titel rot","html":${com.google.gson.Gson().toJson(html)}}]"""
        val pageId = "a1b2c3d4e5f67890abcdef1234567891"
        val zipBytes = HinoteTestData.hinote(
            extra = listOf(
                "pages/$pageId.jhinote" to HinoteTestData.gzip(
                    HinoteTestData.pageJson(id = pageId, number = 4, elements = elementsJson),
                ),
            ),
        )
        val note = HinoteImporter.importNote(read(zipBytes).valueOrNull()!!, "HiNote-Import", "F", now = 1L).note
        val tb = note.pageIds.map { note.pages.getValue(it) }[3].textboxes.single()
        assertEquals("Titel\nrot", tb.text)
        assertEquals(
            listOf(
                local.papernotes.model.TextSpan(local.papernotes.model.SpanType.BOLD, 0, 5),
                local.papernotes.model.TextSpan(local.papernotes.model.SpanType.COLOR, 6, 9, 0xFFFF0000.toInt()),
            ),
            tb.spans,
        )
        assertEquals(
            listOf(
                local.papernotes.model.ParaFormat(0, align = local.papernotes.model.ParaFormat.ALIGN_CENTER),
                local.papernotes.model.ParaFormat(1, list = local.papernotes.model.ParaFormat.LIST_BULLET),
            ),
            tb.paragraphs,
        )
    }

    @Test
    fun importedNoteSurvivesCodecRoundtripWithImportSource() {
        val container = read(HinoteTestData.hinote()).valueOrNull()!!
        val note = HinoteImporter.importNote(container, "HiNote-Import", "x", 1L).note
        val file = File(tmp.root, "n.pnote")
        assertTrue(PnoteCodec.write(note, file) is PnoteResult.Ok)
        assertEquals(note, PnoteCodec.read(file).valueOrNull())
        assertEquals("hinote:" + HinoteTestData.NOTE_ID, PnoteCodec.readSummary(file).valueOrNull()!!.importSource)
    }

    @Test
    fun pnoteWithoutImportSourceStaysReadable() {
        val note = NoteFactory.newNote("T")
        val file = File(tmp.root, "a.pnote")
        PnoteCodec.write(note, file)
        assertNull(PnoteCodec.read(file).valueOrNull()!!.importSource)
    }

    @Test
    fun colorAndPenMapping() {
        assertEquals(0xFFFF8000.toInt(), HinoteImporter.argb(1f, 1f, 0.5f, 0f))
        assertEquals(0x80FF0000.toInt(), HinoteImporter.argb(0.5f, 1f, 0f, 0f))
        val expected = mapOf(1 to 4, 2 to 2, 3 to 3, 4 to 6, 5 to 6, 11 to 3, 12 to 4, 13 to 4, 99 to 2)
        for ((brush, framework) in expected) assertEquals("Pinsel $brush", framework, HinoteImporter.penTypeFor(brush))
    }

    @Test
    fun blueGreenRedOrderOfTheFileIsConverted() {
        // Datei: B, G, R. Rot (r=1) muss ARGB-Rot ergeben.
        val bin = HinoteTestData.bin(listOf(S(listOf(P(1f, 1f), P(2f, 2f)), r = 1f, g = 0f, b = 0f)))
        val s = HinoteBinParser.parse(bin).valueOrNull()!!.strokes.single()
        assertEquals(0xFFFF0000.toInt(), HinoteImporter.argb(s.alpha, s.red, s.green, s.blue))
    }

    @Test
    fun templateMapping() {
        assertEquals("blank", HinoteImporter.templateFor("base1"))
        assertEquals("dotted", HinoteImporter.templateFor("base6"))
        assertEquals("todo", HinoteImporter.templateFor("plan2"))
        assertEquals("swot", HinoteImporter.templateFor("learning5"))
        assertEquals("point_sheet", HinoteImporter.templateFor("learning4"))
        assertEquals("staff", HinoteImporter.templateFor("learning6"))
        assertEquals("time_management", HinoteImporter.templateFor("plan6"))
        assertEquals("character_grid", HinoteImporter.templateFor("handwrite3"))
        assertEquals("blank", HinoteImporter.templateFor("../x"))
    }

    @Test
    fun everyHinoteTemplateIsMappedToAnExistingTemplate() {
        val hinoteIds = listOf(
            "base1", "base2", "base3", "base4", "base5", "base6",
            "handwrite1", "handwrite2", "handwrite3", "handwrite4",
            "plan1", "plan2", "plan3", "plan4", "plan5", "plan6",
            "learning2", "learning3", "learning4", "learning5", "learning6", "learning7",
        )
        val mapped = hinoteIds.map { HinoteImporter.templateFor(it) }
        mapped.forEach { assertTrue(TemplateCatalog.isKnown(it)) }
        // Nur base1 (Leer) darf auf "blank" fallen; alle anderen haben eine eigene Vorlage.
        hinoteIds.zip(mapped).filter { it.first != "base1" }.forEach { (id, t) ->
            assertNotEquals("$id fällt auf blank", "blank", t)
        }
    }

    @Test
    fun brokenBinBecomesEmptyPageWithWarning() {
        val valid = HinoteTestData.bin(listOf(S(listOf(P(1f, 1f), P(2f, 2f)))))
        val container = read(HinoteTestData.hinote(bin1 = valid.copyOf(valid.size - 1))).valueOrNull()!!
        val imported = HinoteImporter.importNote(container, "HiNote-Import", "x", 1L)
        assertEquals(1, imported.warnings)
        assertEquals(3, imported.note.pageIds.size)
        assertTrue(imported.note.pages.values.all { it.strokes.isEmpty() })
    }

    @Test
    fun brokenPageJsonBecomesEmptyPageWithWarning() {
        val bytes = HinoteTestData.hinote(extra = listOf("pages/${"a".repeat(32)}.jhinote" to byteArrayOf(1, 2, 3)))
        val container = read(bytes).valueOrNull()!!
        val imported = HinoteImporter.importNote(container, "HiNote-Import", "x", 1L)
        assertEquals(1, imported.warnings)
        assertEquals(4, imported.note.pageIds.size)
    }

    @Test
    fun zipSlipNameIsRejected() {
        for (name in listOf("../evil.bin", "files/../../x", "/abs", "pages\\x.jhinote")) {
            val bytes = HinoteTestData.hinote(extra = listOf(name to byteArrayOf(1)))
            assertEquals(name, HinoteError.InvalidEntryName, read(bytes).errorOrNull())
        }
    }

    @Test
    fun oversizedEntryIsRejected() {
        val big = ByteArray((PnoteCodec.MAX_ENTRY_BYTES + 1).toInt())
        val bytes = HinoteTestData.hinote(extra = listOf("files/${"b".repeat(32)}.bin" to big))
        assertEquals(HinoteError.LimitExceeded, read(bytes).errorOrNull())
    }

    @Test
    fun highCompressionRatioIsRejected() {
        // 8 MiB Nullen packen auf wenige KiB: Rate weit über der Grenze, Größe unter dem Einzellimit
        val zeros = ByteArray(8 * 1024 * 1024)
        val bytes = HinoteTestData.hinote(extra = listOf("files/${"c".repeat(32)}.bin" to zeros))
        assertEquals(HinoteError.LimitExceeded, read(bytes).errorOrNull())
    }

    @Test
    fun notAZipAndMissingNoteAreCorrupt() {
        assertEquals(HinoteError.Corrupt, read(byteArrayOf(1, 2, 3, 4)).errorOrNull())
        val noNote = HinoteTestData.zip(listOf("custom_md.jhinote" to HinoteTestData.gzip("{}")))
        assertEquals(HinoteError.Corrupt, read(noNote).errorOrNull())
    }

    @Test
    fun strokeCountLimitInContainerBecomesWarning() {
        val bad = HinoteTestData.bin(listOf(S(listOf(P(1f, 1f), P(2f, 2f))))).also {
            ByteBuffer.wrap(it).putInt(76 + 36, HinoteBinParser.MAX_STROKES + 1)
        }
        val container = read(HinoteTestData.hinote(bin1 = bad)).valueOrNull()!!
        assertEquals(1, HinoteImporter.importNote(container, "HiNote-Import", "x", 1L).warnings)
    }

    @Test
    fun runnerImportsOnceAndSkipsDuplicates() {
        val repo = NoteRepository(tmp.newFolder("notes"))
        val a = HinoteTestData.hinote()
        val b = HinoteTestData.hinote(noteId = "e".repeat(32), title = "Zweite")
        val broken = byteArrayOf(0, 1, 2)
        val progress = ArrayList<Int>()
        val first = HinoteImportRunner.run(
            listOf(source("a", a), source("a-again", a), source("b", b), source("broken", broken)),
            repo, "Fallback", onProgress = { done, _ -> progress.add(done) },
        )
        assertEquals(HinoteImportReport(imported = 2, skipped = 1, withWarnings = 0, failed = 1), first)
        assertEquals(listOf(1, 2), progress) // Fortschritt zählt nur die zu importierenden Dateien (Duplikat und kaputte Datei fallen vorher heraus)
        assertEquals(2, repo.list(HinoteImporter.TARGET_FOLDER).valueOrNull()!!.size)
        // zweiter Lauf: alles bereits vorhanden
        val second = HinoteImportRunner.run(listOf(source("a", a), source("b", b)), repo, "Fallback")
        assertEquals(HinoteImportReport(imported = 0, skipped = 2, withWarnings = 0, failed = 0), second)
        assertEquals(2, repo.listAll("").valueOrNull()!!.size)
    }

    @Test
    fun randomGarbageNeverThrows() {
        val rnd = Random(7)
        repeat(200) {
            val bytes = ByteArray(rnd.nextInt(400)).also { rnd.nextBytes(it) }
            HinoteBinParser.parse(bytes)
            read(bytes)
        }
        // Zufällige Verfälschung einer gültigen Datei
        val valid = HinoteTestData.bin(listOf(S(listOf(P(1f, 1f), P(2f, 2f), P(3f, 3f)))))
        repeat(500) {
            val copy = valid.copyOf()
            copy[rnd.nextInt(copy.size)] = rnd.nextInt().toByte()
            HinoteBinParser.parse(copy)
        }
    }

    @Test
    fun legacyPageIsImportedWithTopAlignedScaleOne() {
        val legacy = HinoteTestData.legacyBin(
            listOf(HinoteTestData.LS(listOf(HinoteTestData.LP(0.5f, 0.5f, 1000L, 0.4f), HinoteTestData.LP(1f, 1f, 1030L)), argb = 0xFF505050.toInt(), width = 4f)),
            h = 1460,
        )
        val container = read(HinoteTestData.hinote(bin1 = legacy)).valueOrNull()!!
        val imported = HinoteImporter.importNote(container, "HiNote-Import", "x", 1L)
        assertEquals(0, imported.warnings)
        val page = imported.note.pages.getValue(imported.note.pageIds[0])
        assertEquals(1404, page.width)
        assertEquals(1872, page.height)
        val s = page.strokes.single()
        assertEquals(0xFF505050.toInt(), s.color)
        assertEquals(4f * HinoteImporter.STROKE_WIDTH_FACTOR, s.width, 0f)
        assertEquals(702f, s.points[0].x, 0.01f) // 0,5 * 1404
        assertEquals(730f, s.points[0].y, 0.01f) // 0,5 * 1460, oben ausgerichtet
        assertEquals(1404f, s.points[1].x, 0.01f)
        assertEquals(1460f, s.points[1].y, 0.01f)
        assertEquals(30L, s.points[1].time) // relativ zum Strichanfang
    }

    @Test
    fun brokenLegacyBinBecomesWarning() {
        val legacy = HinoteTestData.legacyBin(listOf(HinoteTestData.LS(listOf(HinoteTestData.LP(0f, 0f), HinoteTestData.LP(1f, 1f)))))
        val container = read(HinoteTestData.hinote(bin1 = legacy + byteArrayOf(0))).valueOrNull()!!
        assertEquals(1, HinoteImporter.importNote(container, "HiNote-Import", "x", 1L).warnings)
    }

    @Test
    fun runnerPlacesNotesInTheFolderOfTheirSource() {
        val repo = NoteRepository(tmp.newFolder("notes2"))
        val a = HinoteTestData.hinote()
        val b = HinoteTestData.hinote(noteId = "e".repeat(32), title = "Zweite")
        val report = HinoteImportRunner.run(
            listOf(
                HinoteImportSource("a", "HiNote-Import/BIT", 0, true) { ByteArrayInputStream(a) },
                HinoteImportSource("b") { ByteArrayInputStream(b) },
            ),
            repo, "Fallback",
        )
        assertEquals(2, report.imported)
        assertEquals(1, repo.list("HiNote-Import/BIT").valueOrNull()!!.size)
        assertEquals(1, repo.list("HiNote-Import").valueOrNull()!!.size)
    }

    @Test
    fun runnerKeepsOnlyNewestFileOfTheSameNoteId() {
        val repo = NoteRepository(tmp.newFolder("notes3"))
        val old = HinoteTestData.hinote(title = "Alt")
        val new = HinoteTestData.hinote(title = "Neu")
        val report = HinoteImportRunner.run(
            listOf(
                HinoteImportSource("flach", "HiNote-Import", lastModified = 100, inSubfolder = false) { ByteArrayInputStream(old) },
                HinoteImportSource("BIT/neu", "HiNote-Import/BIT", lastModified = 200, inSubfolder = true) { ByteArrayInputStream(new) },
            ),
            repo, "Fallback",
        )
        assertEquals(HinoteImportReport(imported = 1, skipped = 1, withWarnings = 0, failed = 0), report)
        val only = repo.listAll("").valueOrNull()!!.single()
        assertEquals("Neu", only.title)
        assertEquals("HiNote-Import/BIT", only.folderPath)
    }

    @Test
    fun randomLegacyGarbageNeverThrows() {
        val rnd = Random(5)
        repeat(200) {
            val bytes = "HWEINKBYTE".toByteArray() + ByteArray(rnd.nextInt(300)).also { rnd.nextBytes(it) }
            HinoteBinParser.parse(bytes)
        }
    }

    /**
     * Alle 34 echten Exporte (`.hinote`-Dateien unter `tmp/device/exp/all/<Ordner>`, git-ignoriert, ohne `_dupes`); ohne die Dateien wird
     * der Test übersprungen. Zahlen aus `tools/hinote_bin.py` (inkl. Altformat): 71 793 + 18 810 Striche, 264 Seiten, 260 `.bin`.
     */
    @Test
    fun allRealExportsImportWithoutWarnings() {
        val dir = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "tmp/device/exp/all") }.firstOrNull { it.isDirectory }
        assumeTrue("tmp/device/exp/all fehlt", dir != null)
        val files = dir!!.listFiles { f -> f.isDirectory && !f.name.startsWith("_") || f.name == "_Wurzel" }!!
            .flatMap { d -> d.listFiles { f -> f.name.endsWith(".hinote") }!!.toList() }
        assumeTrue("keine Exporte", files.size == 34)
        var warnings = 0
        var pageCount = 0
        var bins = 0
        var strokes = 0
        var legacyNotes = 0
        val ids = HashSet<String>()
        for (f in files) {
            val container = FileInputStream(f).use { HinoteContainerReader.read(it) }.valueOrNull()
            assertNotNull(f.name, container)
            assertTrue("doppelte noteId in ${f.name}", ids.add(container!!.noteId))
            val imported = HinoteImporter.importNote(container, "HiNote-Import", "x", 1L)
            warnings += imported.warnings
            pageCount += container.pages.size
            bins += container.pages.count { it.bin != null }
            if (container.pages.any { it.bin?.let { b -> String(b, 0, 10) == "HWEINKBYTE" } == true }) legacyNotes++
            strokes += imported.note.pages.values.sumOf { it.strokes.size }
        }
        assertEquals(0, warnings)
        assertEquals(264, pageCount)
        assertEquals(260, bins)
        assertEquals(8, legacyNotes)
        assertEquals(71_793 + 18_810, strokes)
    }
}
