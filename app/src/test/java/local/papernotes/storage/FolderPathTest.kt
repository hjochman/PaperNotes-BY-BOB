package local.papernotes.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unicode-Normalisierung (NFC) der Ordnernamen. Nur reine Strings, keine Dateien (das macOS-Dateisystem normalisiert selbst). */
class FolderPathTest {
    // "Ü" zusammengesetzt (NFC, U+00DC) und zerlegt (NFD, "U" + U+0308)
    private val uNfc = "\u00DC"
    private val uNfd = "U\u0308"

    @Test
    fun nfcAndNfdInputsNormalizeToTheSameName() {
        val nfc = "${uNfc}bung M\u00E4rz"
        val nfd = "${uNfd}bung Ma\u0308rz"
        assertNotEquals(nfc, nfd)
        assertEquals(nfc, FolderPath.normalize(nfd))
        assertEquals(FolderPath.normalize(nfc), FolderPath.normalize(nfd))
    }

    @Test
    fun accentsAreComposed() {
        assertEquals("Caf\u00E9", FolderPath.normalize("Cafe\u0301"))
        assertEquals("\u00F1o\u00F1o", FolderPath.normalize("n\u0303on\u0303o"))
        assertEquals("\u00E5ngstr\u00F6m", FolderPath.normalize("a\u030Angstro\u0308m"))
    }

    @Test
    fun normalizeIsIdempotentAndLeavesPlainNamesAlone() {
        listOf("BIT", "Open Shift", "A-b_c.d", "${uNfc}bung", "Cafe\u0301", "").forEach {
            val once = FolderPath.normalize(it)
            assertEquals(once, FolderPath.normalize(once))
        }
        assertEquals("Open Shift", FolderPath.normalize("Open Shift"))
        assertEquals("A/B", FolderPath.normalize("A/B"))
    }

    @Test
    fun normalizedNamesAreValidAndRawNfdIsRejected() {
        assertTrue(FolderPath.isValidName("${uNfc}bung"))
        assertTrue(FolderPath.isValidName(FolderPath.normalize("${uNfd}bung")))
        // Kombinierende Zeichen stehen nicht in der Allowlist: rohe NFD-Eingabe ist ungültig und wird nie als Dublette angelegt.
        assertFalse(FolderPath.isValidName("${uNfd}bung"))
        assertFalse(FolderPath.isValid("A/${uNfd}bung"))
        assertTrue(FolderPath.isValid("A/${FolderPath.normalize("${uNfd}bung")}"))
    }

    @Test
    fun invalidInputsStayInvalidAfterNormalization() {
        listOf("", " ", ".", "..", ".trash", ".hidden", "a/b", "a\\b", "a?b", "a:b", " x", "x ", "a\u0000b", "\u0308").forEach {
            assertFalse(it, FolderPath.isValidName(FolderPath.normalize(it)))
        }
        // Ein einzelnes kombinierendes Zeichen bleibt ohne Grundbuchstaben stehen und ist ungültig.
        assertFalse(FolderPath.isValidName(FolderPath.normalize("\u0308")))
        assertFalse(FolderPath.isValidName(FolderPath.normalize("a".repeat(FolderPath.MAX_SEGMENT_LENGTH + 1))))
    }

    @Test
    fun normalizationCanOnlyShortenNamesSoLengthLimitStillHolds() {
        // 80 zerlegte Umlaute (160 Zeichen) wären roh zu lang, normalisiert sind es 80 gültige Zeichen.
        val nfd = uNfd.repeat(FolderPath.MAX_SEGMENT_LENGTH)
        val nfc = FolderPath.normalize(nfd)
        assertEquals(FolderPath.MAX_SEGMENT_LENGTH, nfc.length)
        assertTrue(FolderPath.isValidName(nfc))
        assertFalse(FolderPath.isValidName(nfd))
    }

    @Test
    fun nfcAndNfdSpellingsGiveTheSamePathForSetsAndMaps() {
        val a = FolderPath.normalize("${uNfd}bung/Ma\u0308rz")
        val b = FolderPath.normalize("${uNfc}bung/M\u00E4rz")
        assertEquals(a, b)
        assertEquals(setOf(a), setOf(a, b))
        assertEquals(a.hashCode(), b.hashCode())
    }
}
