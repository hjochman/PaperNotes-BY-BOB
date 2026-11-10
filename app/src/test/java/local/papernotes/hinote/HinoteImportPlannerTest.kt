package local.papernotes.hinote

import local.papernotes.hinote.HinoteImportPlanner.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class HinoteImportPlannerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun folderMapping() {
        assertEquals("HiNote-Import", HinoteImportPlanner.folderFor(""))
        assertEquals("HiNote-Import/BIT", HinoteImportPlanner.folderFor("BIT"))
        assertEquals("HiNote-Import/Open Shift", HinoteImportPlanner.folderFor("Open Shift"))
        assertEquals("HiNote-Import/A/B/C", HinoteImportPlanner.folderFor("A/B/C"))
    }

    @Test
    fun invalidOrTooDeepFoldersFallBackToImportFolder() {
        for (rel in listOf("A/B/C/D", ".versteckt", "a..b/.x", "x*y", "a/ b", "a\\b", "..", " ", "a".repeat(81))) {
            assertEquals(rel, "HiNote-Import", HinoteImportPlanner.folderFor(rel))
        }
    }

    private fun c(key: Int, id: String, time: Long, pages: Int, sub: Boolean = false) = Candidate(key, id, time, pages, sub)

    @Test
    fun newestTimestampWins() {
        val w = HinoteImportPlanner.selectWinners(listOf(c(0, "n", 100, 9), c(1, "n", 200, 1), c(2, "m", 5, 1)))
        assertEquals(setOf(1, 2), w)
    }

    @Test
    fun morePagesBreakTimestampTie() {
        val w = HinoteImportPlanner.selectWinners(listOf(c(0, "n", 100, 3), c(1, "n", 100, 7), c(2, "n", 100, 5)))
        assertEquals(setOf(1), w)
    }

    @Test
    fun subfolderThenListOrderBreakRemainingTies() {
        assertEquals(setOf(1), HinoteImportPlanner.selectWinners(listOf(c(0, "n", 1, 1, false), c(1, "n", 1, 1, true))))
        assertEquals(setOf(0), HinoteImportPlanner.selectWinners(listOf(c(0, "n", 1, 1, true), c(1, "n", 1, 1, true))))
    }

    @Test
    fun scannerFindsNestedFilesUpToDepthThreeWithoutSymlinks() {
        val root = tmp.newFolder("custom")
        fun touch(path: String) = File(root, path).also { it.parentFile!!.mkdirs() }.writeText("x")
        touch("a.hinote")
        touch("BIT/b.hinote")
        touch("BIT/note.txt")
        touch("A/B/C/deep.hinote")
        touch("A/B/C/D/toodeep.hinote")
        touch("Open Shift/o.HINOTE")
        val outside = tmp.newFolder("outside")
        File(outside, "x.hinote").writeText("x")
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "BIT/l.hinote").toPath(), File(outside, "x.hinote").toPath())

        val found = HinoteFolderScanner.scan(root, 500).map { it.relativeDir + "|" + it.file.name }.sorted()
        assertEquals(setOf("A/B/C|deep.hinote", "BIT|b.hinote", "Open Shift|o.HINOTE", "|a.hinote"), found.toSet())
        assertEquals(4, found.size)
        assertTrue(found.none { it.contains("toodeep") || it.contains("x.hinote") || it.contains("l.hinote") })
    }

    @Test
    fun scannerHonoursFileLimitAndMissingRoot() {
        val root = tmp.newFolder("many")
        repeat(10) { File(root, "f$it.hinote").writeText("x") }
        assertEquals(4, HinoteFolderScanner.scan(root, 4).size)
        assertEquals(0, HinoteFolderScanner.scan(File(root, "gibt-es-nicht"), 4).size)
    }
}
