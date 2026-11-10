package local.papernotes.hinote

import local.papernotes.model.ParaFormat
import local.papernotes.model.RichTextOps
import local.papernotes.model.SpanType
import local.papernotes.model.TextSpan
import java.util.Locale

/** Ergebnis der Umwandlung des HiNote-Textmarkups: reiner Text, Zeichen- und Absatzformate. */
class HinoteRichText(val text: String, val spans: List<TextSpan>, val paragraphs: List<ParaFormat>)

/**
 * Wandelt das HiNote-Textmarkup (`html` einer Textbox, siehe docs/hinote-bin-format.md 1.2.1) in Text mit Auszeichnung.
 *
 * Bewusst ein kleiner, eigener Tokenizer ohne XML-/HTML-Parser (keine Entitäten-Auflösung außer den üblichen
 * Zeichen, kein DOCTYPE, keine Nachladevorgänge). Unbekannte Tags werden übergangen.
 *
 * Abgeleitet aus dem Aufbau des `html`-Felds in den Exporten: `<b>`, `<i>`, `<u>`, `<font color ="#aarrggbb">`,
 * `<hw_font size ="1.25">` (relative Größe), `<div align="center|right|left">`, `<tab_span style ="type:N,level:N,number:N">`
 * (Liste/Einzug; Typen 2, 6, 7 = nummeriert, 3 = Buchstaben, 4 = Punkt, 5 = Quadrat) und `<br>` als Zeilenumbruch.
 * Die Zuordnung der Typen zu Nummerierung/Aufzählung und `level - 1` als Einzug ist vom Nutzer am Gerät bestätigt.
 */
object HinoteHtml {
    private const val MAX_HTML_CHARS = 500_000
    private const val MAX_OPEN_TAGS = 200

    fun parse(html: String): HinoteRichText? {
        if (html.length > MAX_HTML_CHARS) return null
        val text = StringBuilder()
        val spans = ArrayList<TextSpan>()
        val open = ArrayList<Open>()
        val alignStack = ArrayList<Int>()
        val tabStack = ArrayList<IntArray>() // [list, level]
        val paraSeen = HashMap<Int, ParaFormat>()
        var paraIndex = 0

        fun markPara() {
            if (paraSeen.containsKey(paraIndex)) return
            val align = alignStack.lastOrNull() ?: ParaFormat.ALIGN_LEFT
            val tab = tabStack.lastOrNull()
            paraSeen[paraIndex] = ParaFormat(paraIndex, align, tab?.get(0) ?: ParaFormat.LIST_NONE, tab?.get(1) ?: 0)
        }

        var i = 0
        val n = html.length
        while (i < n) {
            val c = html[i]
            if (c == '<') {
                val close = html.indexOf('>', i + 1)
                if (close < 0) break
                val raw = html.substring(i + 1, close).trim()
                i = close + 1
                if (raw.startsWith("!") || raw.startsWith("?")) continue
                val closing = raw.startsWith("/")
                val body = raw.removePrefix("/").removeSuffix("/").trim()
                val name = body.takeWhile { !it.isWhitespace() }.lowercase(Locale.ROOT)
                val attrs = parseAttrs(body.substring(name.length))
                if (name == "br") {
                    if (!closing) {
                        markPara()
                        text.append('\n')
                        paraIndex++
                    }
                    continue
                }
                if (!closing) {
                    if (open.size >= MAX_OPEN_TAGS) continue
                    when (name) {
                        "b", "strong" -> open.add(Open(name, SpanType.BOLD, 0, text.length))
                        "i", "em", "cite" -> open.add(Open(name, SpanType.ITALIC, 0, text.length))
                        "u" -> open.add(Open(name, SpanType.UNDERLINE, 0, text.length))
                        "font" -> open.add(Open(name, SpanType.COLOR, parseColor(attrs["color"]) ?: 0xFF000000.toInt(), text.length))
                        "hw_font" -> {
                            val pct = attrs["size"]?.toFloatOrNull()?.let { Math.round(it * 100f) }?.coerceIn(25, 500) ?: 100
                            open.add(Open(name, SpanType.SIZE, pct, text.length))
                        }
                        "div" -> alignStack.add(
                            when (attrs["align"]?.lowercase(Locale.ROOT)) {
                                "center" -> ParaFormat.ALIGN_CENTER
                                "right" -> ParaFormat.ALIGN_RIGHT
                                else -> ParaFormat.ALIGN_LEFT
                            },
                        )
                        "tab_span" -> tabStack.add(parseTab(attrs["style"]))
                    }
                } else {
                    when (name) {
                        "div" -> if (alignStack.isNotEmpty()) alignStack.removeAt(alignStack.size - 1)
                        "tab_span" -> if (tabStack.isNotEmpty()) tabStack.removeAt(tabStack.size - 1)
                        "b", "strong", "i", "em", "cite", "u", "font", "hw_font" -> {
                            val idx = open.indexOfLast { it.tag == name }
                            if (idx >= 0) {
                                val o = open.removeAt(idx)
                                if (text.length > o.start) spans.add(TextSpan(o.type, o.start, text.length, o.value))
                            }
                        }
                    }
                }
            } else if (c == '&') {
                val semi = html.indexOf(';', i + 1)
                val decoded = if (semi in (i + 2)..(i + 10)) entity(html.substring(i + 1, semi)) else null
                if (decoded != null) {
                    markPara()
                    text.append(decoded)
                    i = semi + 1
                } else {
                    markPara()
                    text.append(c)
                    i++
                }
            } else {
                // Rohe Zeilenumbrüche stehen nur hinter <br> (Zeilentrenner); der Umbruch selbst ist <br>.
                if (c != '\n' && c != '\r') {
                    markPara()
                    text.append(if (c.code < 32 && c != '\t') ' ' else c)
                }
                i++
            }
        }
        for (o in open) if (text.length > o.start) spans.add(TextSpan(o.type, o.start, text.length, o.value))
        markPara()

        val s = text.toString()
        val dense = RichTextOps.denseParas(paraSeen.values.toList(), RichTextOps.paragraphCount(s))
        return HinoteRichText(
            s,
            RichTextOps.normalizeSpans(spans, s.length),
            RichTextOps.sparseParas(dense),
        )
    }

    private class Open(val tag: String, val type: SpanType, val value: Int, val start: Int)

    private fun parseAttrs(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        while (i < s.length) {
            while (i < s.length && s[i].isWhitespace()) i++
            val ns = i
            while (i < s.length && !s[i].isWhitespace() && s[i] != '=') i++
            val key = s.substring(ns, i).lowercase(Locale.ROOT)
            while (i < s.length && s[i].isWhitespace()) i++
            if (i < s.length && s[i] == '=') {
                i++
                while (i < s.length && s[i].isWhitespace()) i++
                val value: String
                if (i < s.length && (s[i] == '"' || s[i] == '\'')) {
                    val q = s[i]
                    val end = s.indexOf(q, i + 1).let { if (it < 0) s.length else it }
                    value = s.substring(i + 1, end)
                    i = minOf(end + 1, s.length)
                } else {
                    val vs = i
                    while (i < s.length && !s[i].isWhitespace()) i++
                    value = s.substring(vs, i)
                }
                if (key.isNotEmpty()) out[key] = value
            } else if (i == ns) {
                i++
            }
        }
        return out
    }

    private fun parseColor(s: String?): Int? {
        val hex = s?.trim()?.removePrefix("#") ?: return null
        val v = hex.toLongOrNull(16) ?: return null
        return when (hex.length) {
            8 -> v.toInt()
            6 -> (0xFF000000L or v).toInt()
            else -> null
        }
    }

    /** `type:N,level:N,number:N` -> [Liste, Einzug]. */
    private fun parseTab(style: String?): IntArray {
        var type = 1
        var level = 1
        for (part in style.orEmpty().split(',')) {
            val kv = part.split(':')
            if (kv.size < 2) continue
            val v = kv[1].trim().toIntOrNull() ?: continue
            when (kv[0].trim()) {
                "type" -> type = v
                "level" -> level = v
            }
        }
        val list = when (type) {
            4 -> ParaFormat.LIST_BULLET
            5 -> ParaFormat.LIST_SQUARE
            3 -> ParaFormat.LIST_LETTER
            2, 6, 7 -> ParaFormat.LIST_NUMBER
            else -> ParaFormat.LIST_NONE
        }
        return intArrayOf(list, (level - 1).coerceIn(0, ParaFormat.MAX_LEVEL))
    }

    private fun entity(name: String): String? = when {
        name == "lt" -> "<"
        name == "gt" -> ">"
        name == "amp" -> "&"
        name == "quot" -> "\""
        name == "apos" -> "'"
        name == "nbsp" -> " "
        name.startsWith("#x") || name.startsWith("#X") ->
            name.substring(2).toIntOrNull(16)?.takeIf { Character.isValidCodePoint(it) && it >= 32 }
                ?.let { String(Character.toChars(it)) }
        name.startsWith("#") ->
            name.substring(1).toIntOrNull()?.takeIf { Character.isValidCodePoint(it) && it >= 32 }
                ?.let { String(Character.toChars(it)) }
        else -> null
    }
}
