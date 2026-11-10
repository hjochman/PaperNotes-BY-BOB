package local.papernotes.storage

/** Regeln für Notiztitel: nicht leer (nach Trimmen), höchstens [MAX_LENGTH] Zeichen, keine Steuerzeichen. */
object NoteTitles {
    const val MAX_LENGTH = 100

    fun isValid(title: String): Boolean =
        title.isNotBlank() && title.length <= MAX_LENGTH && title.none { it.isISOControl() }

    /** Entfernt Steuerzeichen und Rand-Leerzeichen und kürzt auf [MAX_LENGTH]. */
    fun sanitize(input: String): String =
        input.filterNot { it.isISOControl() }.trim().take(MAX_LENGTH).trim()

    /** Titel einer Kopie; [format] hängt die Kennzeichnung an. Der Originaltitel wird gekürzt, bis alles in [MAX_LENGTH] passt. */
    fun copyTitle(original: String, format: (String) -> String): String {
        var base = original
        var result = format(base)
        while (result.length > MAX_LENGTH && base.isNotEmpty()) {
            base = base.dropLast(1).trimEnd()
            result = format(base)
        }
        return result
    }
}
