package local.papernotes.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class LocalScannerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val a = "11111111-1111-4111-8111-111111111111"
    private val b = "22222222-2222-4222-8222-222222222222"
    private val c = "33333333-3333-4333-8333-333333333333"

    private fun file(dir: File, name: String, text: String = "x"): File {
        dir.mkdirs()
        return File(dir, name).also { it.writeText(text) }
    }

    @Test
    fun scansNotesAndFoldersAndIgnoresTheRest() {
        val root = tmp.newFolder("n")
        file(root, "$a.pnote", "12345")
        file(File(root, "Büro/Sub"), "$b.pnote")
        File(root, "Leer").mkdirs()
        file(root, "readme.txt")
        file(root, "kein-uuid.pnote")
        file(root, ".DS_Store")
        file(File(root, ".trash"), "$c.pnote")
        file(File(root, ".backup/$c"), "1.pnote")
        file(File(root, ".versteckt"), "$c.pnote")

        val s = LocalScanner.scan(root)!!
        assertEquals(setOf("Büro", "Büro/Sub", "Leer"), s.folders)
        assertEquals(setOf(a, b), s.files.keys)
        assertEquals(LocalFile(a, "", 5, root.resolve("$a.pnote").lastModified()), s.files[a])
        assertEquals("Büro/Sub", s.files[b]!!.folder)
    }

    @Test
    fun skipsSymbolicLinks() {
        val root = tmp.newFolder("n")
        val outside = tmp.newFolder("outside")
        file(outside, "$c.pnote")
        file(root, "$a.pnote")
        try {
            Files.createSymbolicLink(File(root, "Link").toPath(), outside.toPath())
            Files.createSymbolicLink(File(root, "$b.pnote").toPath(), File(root, "$a.pnote").toPath())
        } catch (e: UnsupportedOperationException) {
            return
        }
        val s = LocalScanner.scan(root)!!
        assertEquals(emptySet<String>(), s.folders)
        assertEquals(setOf(a), s.files.keys)
    }

    @Test
    fun missingRootIsNullNotEmpty() {
        assertNull(LocalScanner.scan(File(tmp.root, "gibtsnicht")))
        assertNull(LocalScanner.scan(file(tmp.root, "datei")))
    }

    @Test
    fun unreadableFolderMakesWholeScanNull() {
        val root = tmp.newFolder("n")
        file(root, "$a.pnote")
        val locked = File(root, "Gesperrt").also { it.mkdirs() }
        file(locked, "$b.pnote")
        if (!locked.setReadable(false) || locked.canRead()) return // z. B. als root: Test nicht aussagekräftig
        try {
            assertNull(LocalScanner.scan(root))
        } finally {
            locked.setReadable(true)
        }
    }

    @Test
    fun duplicateIdTakesNewerFile() {
        val root = tmp.newFolder("n")
        val old = file(File(root, "A"), "$a.pnote").also { it.setLastModified(1_000) }
        val new = file(File(root, "B"), "$a.pnote").also { it.setLastModified(2_000) }
        val s = LocalScanner.scan(root)!!
        assertEquals(1, s.files.size)
        assertEquals("B", s.files[a]!!.folder)
        assertTrue(old.exists() && new.exists())
    }

    @Test
    fun depthIsLimited() {
        val root = tmp.newFolder("n")
        var dir = root
        repeat(LocalScanner.MAX_DEPTH + 3) { dir = File(dir, "d$it") }
        file(dir, "$a.pnote")
        val s = LocalScanner.scan(root)!!
        assertEquals(LocalScanner.MAX_DEPTH, s.folders.size)
        assertTrue(s.files.isEmpty())
    }
}
