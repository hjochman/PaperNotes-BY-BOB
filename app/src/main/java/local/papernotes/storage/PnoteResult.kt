package local.papernotes.storage

/** Fehler der Speicherschicht. Absichtlich ohne Meldungstext, Pfade oder Titel (nichts davon darf in Logs landen). */
sealed class PnoteError {
    /** Eintrag im ZIP steht nicht auf der Allowlist (z. B. `..`, absolut, Backslash). */
    data object InvalidEntryName : PnoteError()

    /** Ein Größen- oder Mengenlimit wurde überschritten. */
    data object LimitExceeded : PnoteError()

    /** Dateiformat mit höherer Version als dieser Code versteht. */
    data class UnsupportedVersion(val version: Int) : PnoteError()

    /** ID ist keine UUID (IDs werden als Pfadbestandteil benutzt). */
    data object InvalidId : PnoteError()

    data object MissingManifest : PnoteError()

    /** Inhalt kaputt oder nicht lesbar (kein ZIP, ungültiges JSON, fehlende Pflichtfelder). */
    data object Corrupt : PnoteError()

    /** Beim Schreiben: Notiz in sich inkonsistent oder nicht kodierbar (z. B. NaN-Koordinate). */
    data object InvalidNote : PnoteError()

    data object InvalidFolder : PnoteError()
    data object NotFound : PnoteError()
    data object AlreadyExists : PnoteError()

    /** Ordner enthält noch Dateien oder Unterordner und wird deshalb nicht gelöscht. */
    data object NotEmpty : PnoteError()

    /** Ein-/Ausgabefehler (Datei nicht schreibbar o. ä.). */
    data object Io : PnoteError()
}

sealed class PnoteResult<out T> {
    data class Ok<out T>(val value: T) : PnoteResult<T>()
    data class Err(val error: PnoteError) : PnoteResult<Nothing>()

    fun valueOrNull(): T? = (this as? Ok)?.value
    fun errorOrNull(): PnoteError? = (this as? Err)?.error
}
