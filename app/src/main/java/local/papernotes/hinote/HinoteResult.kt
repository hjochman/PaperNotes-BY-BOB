package local.papernotes.hinote

/** Fehler beim Lesen von HiNote-Exporten. Absichtlich ohne Meldungstext, Pfade oder Titel. */
enum class HinoteError {
    /** ZIP-Eintrag mit unsicherem Namen (`..`, absolut, Backslash, zu lang). */
    InvalidEntryName,

    /** Ein Größen-, Mengen- oder Kompressionsratenlimit wurde überschritten. */
    LimitExceeded,

    /** Kein lesbarer Export (kein ZIP, kein gzip/JSON, Pflichtfelder fehlen). */
    Corrupt,

    /** `.bin` beginnt weder mit `PENCILENGINE` noch mit `HWEINKBYTE`. */
    BadMagic,

    /** `.bin` endet vor dem Ende eines Abschnitts. */
    Truncated,

    /** `.bin` hat Bytes nach dem Abschlussblock. */
    TrailingBytes,

    /** `.bin`: Größenfelder passen nicht zusammen oder enthalten ungültige Werte. */
    Inconsistent,

    /** Ein-/Ausgabefehler beim Öffnen der Quelle. */
    Io,
}

sealed class HinoteResult<out T> {
    data class Ok<out T>(val value: T) : HinoteResult<T>()
    data class Err(val error: HinoteError) : HinoteResult<Nothing>()

    fun valueOrNull(): T? = (this as? Ok)?.value
    fun errorOrNull(): HinoteError? = (this as? Err)?.error
}

/** Interne Abkürzung für Fehlerabbruch in Parsern. Ohne Stacktrace und ohne Meldung. */
internal class HinoteException(val error: HinoteError) : RuntimeException(null, null, false, false)
