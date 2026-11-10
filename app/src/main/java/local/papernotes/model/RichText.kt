package local.papernotes.model

/** Zeichenformat einer Textbox (A3). Werte: [TextSpan.value] je Typ siehe Kommentar. */
enum class SpanType(val code: Int) {
    BOLD(0),
    ITALIC(1),
    UNDERLINE(2),

    /** value = ARGB-Farbe (Schwarz ist Standard und wird nicht gespeichert). */
    COLOR(3),

    /** value = Größe in Prozent der Textbox-Schriftgröße (100 ist Standard und wird nicht gespeichert). */
    SIZE(4),
    ;

    companion object {
        fun fromCode(code: Int): SpanType? = values().firstOrNull { it.code == code }
    }
}

/** Ein formatierter Bereich [start, end) in Zeichen des Textes. */
data class TextSpan(val type: SpanType, val start: Int, val end: Int, val value: Int = 0)

/**
 * Absatzformat. [index] zählt die Absätze (durch `\n` getrennt, ab 0). Gespeichert werden nur Absätze mit
 * abweichendem Format (siehe [isDefault]).
 */
data class ParaFormat(
    val index: Int,
    val align: Int = ALIGN_LEFT,
    val list: Int = LIST_NONE,
    val level: Int = 0,
) {
    val isDefault: Boolean get() = align == ALIGN_LEFT && list == LIST_NONE && level == 0

    companion object {
        const val ALIGN_LEFT = 0
        const val ALIGN_CENTER = 1
        const val ALIGN_RIGHT = 2
        const val LIST_NONE = 0
        const val LIST_BULLET = 1
        const val LIST_NUMBER = 2
        const val LIST_LETTER = 3
        const val LIST_SQUARE = 4
        const val MAX_LEVEL = 4
    }
}

/** Reine Logik für Auszeichnungen (Spans) und Absatzformate; kein Android-Bezug. */
object RichTextOps {
    const val SIZE_DEFAULT = 100
    const val COLOR_DEFAULT = 0xFF000000.toInt()
    val SIZE_STEPS = intArrayOf(60, 80, 100, 125, 150, 200, 300)

    /**
     * Bringt [spans] in die kanonische Form: auf [0, length] begrenzt, je Typ ohne Überlappung (später in der Liste
     * gewinnt), gleiche benachbarte Bereiche verschmolzen, Standardwerte entfernt, sortiert nach Start und Typ.
     */
    fun normalizeSpans(spans: List<TextSpan>, length: Int): List<TextSpan> {
        if (length <= 0 || spans.isEmpty()) return emptyList()
        val out = ArrayList<TextSpan>()
        for (type in SpanType.values()) {
            val own = spans.filter { it.type == type }
            if (own.isEmpty()) continue
            val cells = arrayOfNulls<Int>(length)
            for (s in own) {
                val from = s.start.coerceIn(0, length)
                val to = s.end.coerceIn(0, length)
                for (i in from until to) cells[i] = s.value
            }
            var i = 0
            while (i < length) {
                val v = cells[i]
                if (v == null) {
                    i++
                    continue
                }
                var j = i + 1
                while (j < length && cells[j] == v) j++
                val keep = when (type) {
                    SpanType.COLOR -> v != COLOR_DEFAULT
                    SpanType.SIZE -> v != SIZE_DEFAULT
                    else -> true
                }
                if (keep) out.add(TextSpan(type, i, j, if (type == SpanType.COLOR || type == SpanType.SIZE) v else 0))
                i = j
            }
        }
        return out.sortedWith(compareBy({ it.start }, { it.type.code }, { it.end }))
    }

    /** Setzt [type] im Bereich [start, end) auf [value]; `null` entfernt die Auszeichnung dort. */
    fun setSpan(spans: List<TextSpan>, length: Int, type: SpanType, start: Int, end: Int, value: Int?): List<TextSpan> {
        val s = start.coerceIn(0, length)
        val e = end.coerceIn(0, length)
        val result = ArrayList<TextSpan>()
        for (sp in spans) {
            if (sp.type != type) {
                result.add(sp)
                continue
            }
            if (sp.start < s) result.add(sp.copy(end = minOf(sp.end, s)))
            if (sp.end > e) result.add(sp.copy(start = maxOf(sp.start, e)))
        }
        if (value != null && e > s) result.add(TextSpan(type, s, e, value))
        return normalizeSpans(result.filter { it.end > it.start }, length)
    }

    /** `true`, wenn der ganze Bereich [start, end) mit [type] ausgezeichnet ist (Fett, Kursiv, Unterstrichen). */
    fun isCovered(spans: List<TextSpan>, type: SpanType, start: Int, end: Int): Boolean {
        if (end <= start) return false
        var pos = start
        for (sp in spans.filter { it.type == type }.sortedBy { it.start }) {
            if (sp.end <= pos) continue
            if (sp.start > pos) return false
            pos = sp.end
            if (pos >= end) return true
        }
        return pos >= end
    }

    /** Schaltet Fett, Kursiv oder Unterstrichen im Bereich um: ganz gesetzt -> entfernen, sonst überall setzen. */
    fun toggle(spans: List<TextSpan>, length: Int, type: SpanType, start: Int, end: Int): List<TextSpan> =
        setSpan(spans, length, type, start, end, if (isCovered(spans, type, start, end)) null else 0)

    /** Wert von [type] an Position [pos] (Zeichen rechts davon) oder [default]. */
    fun valueAt(spans: List<TextSpan>, type: SpanType, pos: Int, default: Int): Int =
        spans.firstOrNull { it.type == type && pos >= it.start && pos < it.end }?.value ?: default

    /** Nächste Größenstufe über/unter [current] (Prozent); an den Enden unverändert. */
    fun nextSize(current: Int, larger: Boolean): Int =
        if (larger) SIZE_STEPS.firstOrNull { it > current } ?: current
        else SIZE_STEPS.lastOrNull { it < current } ?: current

    // ---- Absätze ----

    /** Anzahl der Absätze: Zeilenumbrüche + 1 (nach einem letzten `\n` zählt der leere Absatz mit). */
    fun paragraphCount(text: CharSequence): Int = text.count { it == '\n' } + 1

    /** Absatzindex an Position [pos] (Anzahl der `\n` davor). */
    fun paragraphIndexAt(text: CharSequence, pos: Int): Int {
        var n = 0
        for (i in 0 until pos.coerceIn(0, text.length)) if (text[i] == '\n') n++
        return n
    }

    /** Absätze, die von der Auswahl [start, end) berührt werden. */
    fun paragraphRange(text: CharSequence, start: Int, end: Int): IntRange {
        val s = minOf(start, end)
        val e = maxOf(start, end)
        val last = if (e > s) paragraphIndexAt(text, e - 1) else paragraphIndexAt(text, s)
        return paragraphIndexAt(text, s)..last
    }

    /** Dichte Liste mit genau einem Eintrag je Absatz (Standardformat, wo nichts gespeichert ist). */
    fun denseParas(paras: List<ParaFormat>, count: Int): List<ParaFormat> {
        val byIndex = paras.associateBy { it.index }
        return List(count) { byIndex[it] ?: ParaFormat(it) }
    }

    /** Kanonische, dünn besetzte Form: nur abweichende Absätze, sortiert, ohne Doppelte. */
    fun sparseParas(dense: List<ParaFormat>): List<ParaFormat> =
        dense.mapIndexed { i, p -> p.copy(index = i) }.filter { !it.isDefault }

    /**
     * Ausrichtung setzen. Listenpunkte bleiben linksbündig (wie in HiNote), ihre Ausrichtung ist gesperrt. Mitte und
     * rechts kennen keinen Einzug: ein vorhandener Einzug entfällt.
     */
    fun setAlign(dense: List<ParaFormat>, range: IntRange, align: Int): List<ParaFormat> =
        dense.mapIndexed { i, p ->
            if (i in range && p.list == ParaFormat.LIST_NONE) {
                p.copy(align = align, level = if (align == ParaFormat.ALIGN_LEFT) p.level else 0)
            } else {
                p
            }
        }

    /** `true`, wenn die Ausrichtung für die Absätze in [range] einstellbar ist (kein Listenpunkt darunter). */
    fun alignEditable(dense: List<ParaFormat>, range: IntRange): Boolean =
        range.none { it in dense.indices && dense[it].list != ParaFormat.LIST_NONE }

    /**
     * Liste umschalten: haben alle Absätze der Auswahl schon [kind], wird sie entfernt, sonst gesetzt (eine andere
     * Listenart wird ersetzt). Ein Listenpunkt ist linksbündig.
     */
    fun toggleList(dense: List<ParaFormat>, range: IntRange, kind: Int): List<ParaFormat> {
        val allSet = range.all { it in dense.indices && dense[it].list == kind }
        val target = if (allSet) ParaFormat.LIST_NONE else kind
        return dense.mapIndexed { i, p ->
            if (i !in range) p
            else if (target == ParaFormat.LIST_NONE) p.copy(list = target)
            else p.copy(list = target, align = ParaFormat.ALIGN_LEFT)
        }
    }

    /** Einzug gilt für Listenpunkte und für linksbündigen Text, nicht für zentrierten oder rechtsbündigen. */
    fun indentable(p: ParaFormat): Boolean = p.list != ParaFormat.LIST_NONE || p.align == ParaFormat.ALIGN_LEFT

    fun changeLevel(dense: List<ParaFormat>, range: IntRange, delta: Int): List<ParaFormat> =
        dense.mapIndexed { i, p ->
            if (i in range && indentable(p)) p.copy(level = (p.level + delta).coerceIn(0, ParaFormat.MAX_LEVEL)) else p
        }

    /** Zählnummer (ab 1) des Absatzes [i] unter den unmittelbar vorangehenden Punkten gleicher Art und Stufe. */
    fun listNumber(dense: List<ParaFormat>, i: Int): Int {
        val me = dense[i]
        var n = 1
        var k = i - 1
        while (k >= 0) {
            val p = dense[k]
            if (p.level > me.level) {
                k--
                continue
            }
            if (p.level < me.level || p.list != me.list) break
            n++
            k--
        }
        return n
    }

    /** Größenfaktor der Listenzeichen im Symbol der Formatleiste (im Text werden sie selbst gezeichnet). */
    fun markerScale(marker: String): Float = when (marker) {
        "\u2022" -> 1.3f
        "\u25A0", "\u25A1" -> 0.75f
        else -> 1f
    }

    /** Aufzählungszeichen oder Nummer des Absatzes [i]; leer, wenn der Absatz kein Listenpunkt ist. */
    fun marker(dense: List<ParaFormat>, i: Int): String {
        val p = dense.getOrNull(i) ?: return ""
        return when (p.list) {
            ParaFormat.LIST_BULLET -> if (p.level % 2 == 0) "\u2022" else "\u2013"
            ParaFormat.LIST_SQUARE -> if (p.level % 2 == 0) "\u25A0" else "\u25A1"
            ParaFormat.LIST_NUMBER -> "${listNumber(dense, i)}."
            ParaFormat.LIST_LETTER -> "${'a' + (listNumber(dense, i) - 1) % 26}."
            else -> ""
        }
    }
}
