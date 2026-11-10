package local.papernotes.export

/**
 * Dateinamen für exportierte Dateien (reine Logik, JVM-testbar): aus dem Titel ein sicherer Name ohne Pfadtrenner,
 * `..`, Steuerzeichen oder auf Dateisystemen mit Einschränkungen (FAT/sdcardfs) verbotene Zeichen; Längenlimit;
 * eindeutig innerhalb eines Exports und gegenüber vorhandenen Dateien (" (2)", " (3)" usw.).
 */
object ExportFileNames {
    const val MAX_BASE_LENGTH = 80
    const val FALLBACK = "note"
    private const val FORBIDDEN = "/\\:*?\"<>|"

    /** Titel -> sicherer Dateiname ohne Endung (nie leer, nie länger als [MAX_BASE_LENGTH], enthält kein `..`). */
    fun base(title: String): String {
        val mapped = StringBuilder(title.length)
        for (ch in title) {
            mapped.append(if (ch.isISOControl() || ch in FORBIDDEN || Character.getType(ch) == Character.FORMAT.toInt()) '_' else ch)
        }
        var s = mapped.toString()
        while (s.contains("..")) s = s.replace("..", ".")
        s = s.trim().trim('.').trim()
        if (s.length > MAX_BASE_LENGTH) {
            var end = MAX_BASE_LENGTH
            if (Character.isHighSurrogate(s[end - 1])) end-- // kein halbes Zeichenpaar abschneiden
            s = s.substring(0, end).trim().trim('.').trim()
        }
        return s.ifEmpty { FALLBACK }
    }

    /**
     * Nächster freier Dateiname (mit [extension]) zu [title]. [used] enthält die bereits belegten Namen in Kleinschreibung
     * (Groß-/Kleinschreibung zählt nicht) und wird um den gewählten Namen ergänzt. Kollisionen erhalten " (2)", " (3)" usw.
     */
    fun next(title: String, used: MutableSet<String>, extension: String = ".pdf"): String {
        val base = base(title)
        var name = base + extension
        var n = 1
        while (!used.add(name.lowercase())) name = "$base (${++n})$extension"
        return name
    }

    /** Dateinamen zu [titles], eindeutig untereinander und gegenüber [existing] (Dateinamen mit Endung). */
    fun unique(titles: List<String>, existing: Collection<String> = emptyList(), extension: String = ".pdf"): List<String> {
        val used = existing.mapTo(HashSet()) { it.lowercase() }
        return titles.map { next(it, used, extension) }
    }
}
