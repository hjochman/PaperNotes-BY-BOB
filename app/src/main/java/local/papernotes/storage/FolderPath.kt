package local.papernotes.storage

import java.text.Normalizer

/**
 * Ordnerpfade: "" = Wurzel, sonst Segmente getrennt durch "/" (z. B. "BIT" oder "A/B").
 * Jedes Segment wird per Allowlist geprüft: Buchstaben (inkl. Umlaute), Ziffern, Leerzeichen, `-`, `_`, `.`;
 * kein führender Punkt (schließt ".", ".." und ".trash" aus), kein führendes/abschließendes Leerzeichen, max. 80 Zeichen.
 *
 * Unicode: Kombinierende Zeichen (`\p{M}`) fehlen in der Allowlist, ein zerlegtes "Ü" (NFD: "U" + Trema, z. B. aus der
 * Zwischenablage oder einer macOS-Quelle) ist also ungültig, ein zusammengesetztes (NFC) gültig. Eingaben werden deshalb
 * vor der Prüfung mit [normalize] nach NFC gebracht; so entstehen lokale Namen, Sync-Gedächtnis und gesendete Server-Pfade
 * immer in derselben Schreibweise (keine Dubletten "Ü"/"U"+Trema).
 */
object FolderPath {
    const val MAX_SEGMENT_LENGTH = 80
    private val SEGMENT = Regex("[\\p{L}\\p{Nd} _.-]{1,$MAX_SEGMENT_LENGTH}")

    /** Bringt [text] (Name oder Pfad) in die Normalform NFC; ändert nichts, was schon NFC ist (idempotent). */
    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)

    fun isValidName(name: String): Boolean =
        SEGMENT.matches(name) && !name.startsWith('.') && name == name.trim()

    fun isValid(path: String): Boolean =
        path.isEmpty() || path.split('/').all { isValidName(it) }
}
