package local.papernotes.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class PreviewCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun id() = UUID.randomUUID().toString()

    private fun make(cache: PreviewCache, id: String, modified: Long, size: Int, time: Long): File {
        val f = cache.prepare(id, modified)!!
        f.writeBytes(ByteArray(size) { 1 })
        f.setLastModified(time)
        return f
    }

    @Test
    fun keyContainsIdAndModified() {
        val cache = PreviewCache(File(tmp.root, "p"))
        val a = id()
        assertEquals("$a-123.png", cache.file(a, 123)!!.name)
        assertNull(cache.find(a, 123))
        make(cache, a, 123, 10, 1_000)
        assertNotNull(cache.find(a, 123))
        assertNull("anderes modified = neue Vorschau nötig", cache.find(a, 124))
    }

    @Test
    fun rejectsInvalidKeys() {
        val cache = PreviewCache(File(tmp.root, "p"))
        assertNull(cache.file("../x", 1))
        assertNull(cache.file("abc", 1))
        assertNull(cache.file(id(), -1))
        assertNull(cache.prepare("..", 1))
    }

    @Test
    fun deleteOtherVersionsKeepsOnlyCurrentOfThatNote() {
        val cache = PreviewCache(File(tmp.root, "p"))
        val a = id()
        val b = id()
        make(cache, a, 1, 5, 1)
        make(cache, a, 2, 5, 2)
        make(cache, b, 1, 5, 3)
        cache.deleteOtherVersions(a, 2)
        assertNull(cache.find(a, 1))
        assertNotNull(cache.find(a, 2))
        assertNotNull(cache.find(b, 1))
    }

    @Test
    fun pruneRemovesLeastRecentlyUsedFirst() {
        val cache = PreviewCache(File(tmp.root, "p"), maxBytes = 25)
        val a = id()
        val b = id()
        val c = id()
        make(cache, a, 1, 10, 1_000)
        make(cache, b, 1, 10, 2_000)
        make(cache, c, 1, 10, 3_000)
        // a wird benutzt und ist damit nicht mehr die älteste
        assertNotNull(cache.find(a, 1, now = 9_000))
        cache.prune()
        assertTrue(cache.totalBytes() <= 25)
        assertNull("b war am längsten unbenutzt", cache.find(b, 1))
        assertNotNull(cache.find(a, 1))
        assertNotNull(cache.find(c, 1))
    }

    @Test
    fun pruneNeverTouchesForeignFiles() {
        val dir = File(tmp.root, "p").also { it.mkdirs() }
        val foreign = File(dir, "readme.txt").apply { writeBytes(ByteArray(100)) }
        val cache = PreviewCache(dir, maxBytes = 1)
        make(cache, id(), 1, 10, 1)
        cache.prune()
        assertTrue(foreign.isFile)
        assertEquals(0, cache.totalBytes())
    }

    @Test
    fun parseAcceptsOnlyExactNames() {
        val a = id()
        assertEquals(a to 77L, PreviewCache.parse("$a-77.png"))
        assertNull(PreviewCache.parse("$a-77.png.tmp"))
        assertNull(PreviewCache.parse("$a.png"))
        assertNull(PreviewCache.parse("x-77.png"))
        assertFalse(PreviewCache.parse("../$a-77.png") != null)
    }

    @Test
    fun emptyFileIsNotAHit() {
        val cache = PreviewCache(File(tmp.root, "p"))
        val a = id()
        cache.prepare(a, 5)!!.writeBytes(ByteArray(0))
        assertNull(cache.find(a, 5))
    }
}
