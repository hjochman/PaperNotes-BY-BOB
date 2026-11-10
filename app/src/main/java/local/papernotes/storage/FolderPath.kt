package local.papernotes.storage

/**
 * Ordnerpfade: "" = Wurzel, sonst Segmente getrennt durch "/" (z. B. "BIT" oder "A/B").
 * Jedes Segment wird per Allowlist geprüft: Buchstaben (inkl. Umlaute), Ziffern, Leerzeichen, `-`, `_`, `.`;
 * kein führender Punkt (schließt ".", ".." und ".trash" aus), kein führendes/abschließendes Leerzeichen, max. 80 Zeichen.
 */
object FolderPath {
    const val MAX_SEGMENT_LENGTH = 80
    private val SEGMENT = Regex("[\\p{L}\\p{Nd} _.-]{1,$MAX_SEGMENT_LENGTH}")

    fun isValidName(name: String): Boolean =
        SEGMENT.matches(name) && !name.startsWith('.') && name == name.trim()

    fun isValid(path: String): Boolean =
        path.isEmpty() || path.split('/').all { isValidName(it) }
}
