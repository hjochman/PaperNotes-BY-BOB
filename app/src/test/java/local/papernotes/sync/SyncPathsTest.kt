package local.papernotes.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncPathsTest {
    @Test
    fun acceptsHttpsUrlsAndNormalizes() {
        assertEquals("https://cloud.example.de/nextcloud", SyncPaths.normalizeBaseUrl(" https://Cloud.Example.DE/nextcloud/ "))
        assertEquals("https://cloud.example.de", SyncPaths.normalizeBaseUrl("https://cloud.example.de"))
        assertEquals("https://cloud.example.de:8443/nc", SyncPaths.normalizeBaseUrl("https://cloud.example.de:8443/nc"))
    }

    @Test
    fun rejectsUnsafeOrBrokenUrls() {
        listOf(
            "", "   ", "http://cloud.example.de", "ftp://x.de", "cloud.example.de", "https://", "https:///nc",
            "https://user:pw@cloud.example.de", "https://cloud.example.de?x=1", "https://cloud.example.de/#a",
            "https://cloud.example.de/a/../b", "https://cloud.example.de//nc", "https://cloud.example.de/a b",
            "https://" + "a".repeat(300),
        ).forEach { assertNull("sollte abgelehnt werden: $it", SyncPaths.normalizeBaseUrl(it)) }
    }

    @Test
    fun targetFollowsFolderRules() {
        assertEquals("PaperNotes", SyncPaths.normalizeTarget(" /PaperNotes/ "))
        assertEquals("Büro/Notizen 2", SyncPaths.normalizeTarget("Büro/Notizen 2"))
        listOf("", "/", "..", "a/../b", ".hidden", "a//b", "a\\b", "a?b").forEach { assertNull(it, SyncPaths.normalizeTarget(it)) }
    }

    @Test
    fun encodesSegmentsForWebDav() {
        assertEquals("Open%20Shift", SyncPaths.encodeSegment("Open Shift"))
        assertEquals("B%C3%BCro", SyncPaths.encodeSegment("Büro"))
        assertEquals("A%20B/C", SyncPaths.encodePath("A B/C"))
    }

    @Test
    fun buildsUrls() {
        val root = SyncPaths.davRoot("https://c.de/nextcloud", "hans peter")
        assertEquals("https://c.de/nextcloud/remote.php/dav/files/hans%20peter", root)
        assertEquals("$root/PaperNotes", SyncPaths.folderUrl(root, "PaperNotes", ""))
        assertEquals("$root/PaperNotes/HiNote-Import/Open%20Shift", SyncPaths.folderUrl(root, "PaperNotes", "HiNote-Import/Open Shift"))
        assertEquals("$root/PaperNotes/X/abc.pnote", SyncPaths.fileUrl(root, "PaperNotes", "X", "abc"))
        assertEquals("$root/PaperNotes/abc.pnote", SyncPaths.fileUrl(root, "PaperNotes", "", "abc"))
    }

    @Test
    fun scopeChangesWithServerUserOrTarget() {
        val s = SyncPaths.scope("https://a", "u", "T")
        assertEquals(false, s == SyncPaths.scope("https://b", "u", "T"))
        assertEquals(false, s == SyncPaths.scope("https://a", "v", "T"))
        assertEquals(false, s == SyncPaths.scope("https://a", "u", "U"))
    }
}
