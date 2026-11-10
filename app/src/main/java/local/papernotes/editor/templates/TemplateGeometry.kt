package local.papernotes.editor.templates

import kotlin.math.floor

/** Beschriftungen in Vorlagen (Spaltenköpfe usw.). Die Texte kommen aus den Android-Ressourcen. */
enum class TemplateLabel {
    DONE, TIME, TODOS, SENDER, RECIPIENT, SUBJECT, DATE, TITLE, PARTICIPANTS, AGENDA, NOTES, DECISIONS,
    MON, TUE, WED, THU, FRI, SAT, SUN, TASK, PRIORITY, DUE, ERROR, CAUSE, SOLUTION, AUTHOR, QUOTES, CUES, SUMMARY, TOPIC,
    URGENT, NOT_URGENT, IMPORTANT, NOT_IMPORTANT, STRENGTHS, WEAKNESSES, OPPORTUNITIES, THREATS, POINT, ACTION,
}

/** Maße des Blatts in Pixeln: [dp] = Pixel je dp, [mm] = Pixel je Millimeter, [textPx] = Schriftgröße der Beschriftung. */
class TemplateMetrics(val width: Float, val height: Float, val dp: Float, val mm: Float, val textPx: Float)

/** Eine Beschriftung: [x] ist linker Rand, (bei [centered]) Mitte oder (bei [right]) rechter Rand, [y] die Grundlinie. */
class LabelItem(val label: TemplateLabel, val x: Float, val y: Float, val centered: Boolean, val right: Boolean = false)

/**
 * Berechnete Zeichenelemente einer Vorlage. Linien als Folgen von `x1, y1, x2, y2`, Punkte als `x, y`,
 * Kreise als `cx, cy, r`. Alle Koordinaten liegen im Blatt (0..Breite, 0..Höhe).
 * [fine] sehr hell (Feinraster), [main] mittelgrau (Linien, Rahmen), [head] dunkler (Kopf-/Abschnittslinien).
 */
class TemplateShapes(
    val fine: FloatArray,
    val main: FloatArray,
    val head: FloatArray,
    val dots: FloatArray,
    val circles: FloatArray,
    val labels: List<LabelItem>,
) {
    val isEmpty: Boolean get() = fine.isEmpty() && main.isEmpty() && head.isEmpty() && dots.isEmpty() && circles.isEmpty() && labels.isEmpty()

    companion object {
        val EMPTY = TemplateShapes(FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), emptyList())
    }
}

/** Reine Geometrie aller Vorlagen (ohne Android). Zeichnen macht [ShapeTemplateRenderer]. */
object TemplateGeometry {
    private const val MAX_STEPS = 2000
    private const val MAX_DOTS_PER_AXIS = 200

    /** Berechnet die Elemente der Vorlage [id]. Unbekannte IDs und "blank" ergeben [TemplateShapes.EMPTY]. */
    fun build(id: String, m: TemplateMetrics): TemplateShapes {
        if (!(m.width > 0f && m.height > 0f) || !m.width.isFinite() || !m.height.isFinite()) return TemplateShapes.EMPTY
        val b = Builder(m)
        when (id) {
            "lines_wide" -> b.ruled(56f)
            "lines_narrow" -> b.ruled(36f)
            "dotted" -> b.dotted()
            "handwriting" -> b.handwriting()
            "millimeter" -> b.millimeter()
            "grid" -> b.grid()
            "letter" -> b.letter()
            "todo" -> b.todo()
            "meeting" -> b.meeting()
            "weekly" -> b.weekly()
            "monthly" -> b.monthly()
            "tasks" -> b.tasks()
            "error_log" -> b.errorLog()
            "reading_notes" -> b.readingNotes()
            "cornell" -> b.cornell()
            "character_grid" -> b.characterGrid()
            "staff" -> b.staff()
            "time_management" -> b.timeManagement()
            "point_sheet" -> b.pointSheet()
            "swot" -> b.swot()
            else -> Unit
        }
        return b.build()
    }

    private class FloatBuf {
        private var a = FloatArray(128)
        var size = 0
            private set

        fun add(v: Float) {
            if (size == a.size) a = a.copyOf(size * 2)
            a[size++] = v
        }

        fun toArray(): FloatArray = a.copyOf(size)
    }

    private class Builder(m: TemplateMetrics) {
        val w = m.width
        val h = m.height
        val dp = m.dp.takeIf { it.isFinite() }?.coerceAtLeast(0.5f) ?: 1f
        val mm = m.mm.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 8f
        val tx = m.textPx.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 14f

        /** Maßstab der HiNote-Vorlagen (Entwurfsmaß 702 x 840, oben links verankert, gleichmäßig skaliert). */
        val ds = w / DESIGN_WIDTH

        private val fine = FloatBuf()
        private val main = FloatBuf()
        private val head = FloatBuf()
        private val dots = FloatBuf()
        private val circles = FloatBuf()
        private val labels = ArrayList<LabelItem>()

        // Rahmenbereich für Tabellen-Vorlagen.
        val margin = minOf(12f * dp, minOf(w, h) / 10f)
        val pad = minOf(6f * dp, margin)
        val fl = margin
        val ft = margin
        val fr = w - margin
        val fb = h - margin
        val fw = fr - fl
        val fh = fb - ft

        fun build() = TemplateShapes(fine.toArray(), main.toArray(), head.toArray(), dots.toArray(), circles.toArray(), labels.toList())

        // ---- Grundelemente (alles wird ins Blatt geklemmt, nicht endliche Werte entfallen) ----

        private fun seg(buf: FloatBuf, x1: Float, y1: Float, x2: Float, y2: Float) {
            if (!(x1.isFinite() && y1.isFinite() && x2.isFinite() && y2.isFinite())) return
            buf.add(x1.coerceIn(0f, w)); buf.add(y1.coerceIn(0f, h)); buf.add(x2.coerceIn(0f, w)); buf.add(y2.coerceIn(0f, h))
        }

        fun hline(kind: Int, y: Float, x1: Float, x2: Float) = seg(buf(kind), x1, y, x2, y)
        fun vline(kind: Int, x: Float, y1: Float, y2: Float) = seg(buf(kind), x, y1, x, y2)

        private fun buf(kind: Int) = when (kind) {
            FINE -> fine
            HEAD -> head
            else -> main
        }

        fun rect(kind: Int, l: Float, t: Float, r: Float, b: Float) {
            hline(kind, t, l, r); hline(kind, b, l, r); vline(kind, l, t, b); vline(kind, r, t, b)
        }

        fun dot(x: Float, y: Float) {
            if (x.isFinite() && y.isFinite()) {
                dots.add(x.coerceIn(0f, w)); dots.add(y.coerceIn(0f, h))
            }
        }

        fun circle(cx: Float, cy: Float, r: Float) {
            if (cx.isFinite() && cy.isFinite() && r.isFinite() && r > 0f) {
                circles.add(cx.coerceIn(0f, w)); circles.add(cy.coerceIn(0f, h)); circles.add(r)
            }
        }

        fun labelLeft(label: TemplateLabel, left: Float, top: Float, cellH: Float) = addLabel(label, left + pad, top + cellH / 2f + tx * 0.35f, false)

        fun labelCenter(label: TemplateLabel, left: Float, right: Float, top: Float, cellH: Float) =
            addLabel(label, (left + right) / 2f, top + cellH / 2f + tx * 0.35f, true)

        /** Beschriftung, deren rechter Rand an [right] (minus Innenabstand) liegt. */
        fun labelRight(label: TemplateLabel, right: Float, top: Float, cellH: Float) =
            addLabel(label, right - pad, top + cellH / 2f + tx * 0.35f, false, true)

        private fun addLabel(label: TemplateLabel, x: Float, y: Float, centered: Boolean, right: Boolean = false) {
            if (x.isFinite() && y.isFinite()) labels.add(LabelItem(label, x.coerceIn(0f, w), y.coerceIn(0f, h), centered, right))
        }

        /** Ruft [action] mit (Index, Position) für start, start+step, … solange Position < limit (begrenzt). */
        inline fun steps(start: Float, step: Float, limit: Float, action: (Int, Float) -> Unit) {
            val s = step.coerceAtLeast(3f)
            var i = 0
            var v = start
            while (v < limit && i < MAX_STEPS) {
                action(i, v)
                i++
                v = start + i * s
            }
        }

        /** Zeilenlinien in [kind] zwischen [x1] und [x2] von [from] bis vor [to]. */
        fun lines(kind: Int, from: Float, step: Float, to: Float, x1: Float, x2: Float) =
            steps(from, step, to) { _, y -> hline(kind, y, x1, x2) }

        // ---- Grundlage ----

        fun ruled(stepDp: Float) {
            val step = stepDp * dp
            lines(MAIN, step, step, h, 0f, w)
        }

        fun dotted() {
            val step = (36f * dp).coerceAtLeast(3f)
            val nx = floor(w / step).toInt().coerceAtMost(MAX_DOTS_PER_AXIS)
            val ny = floor(h / step).toInt().coerceAtMost(MAX_DOTS_PER_AXIS)
            if (nx < 1 || ny < 1) return
            val x0 = (w - (nx - 1) * step) / 2f
            val y0 = (h - (ny - 1) * step) / 2f
            for (j in 0 until ny) for (i in 0 until nx) dot(x0 + i * step, y0 + j * step)
        }

        // ---- Schreiben ----

        fun handwriting() {
            val step = 48f * dp
            lines(MAIN, step, step, h, 0f, w)
            // Hilfslinie auf halber Zeilenhöhe (Mittellänge) über jeder Grundlinie.
            steps(step, step, h) { _, y -> hline(FINE, y - step / 2f, 0f, w) }
            vline(MAIN, minOf(60f * dp, w / 4f), 0f, h)
        }

        fun millimeter() {
            val step = mm.coerceAtLeast(3f)
            steps(step, step, w) { i, x -> vline(if ((i + 1) % 5 == 0) MAIN else FINE, x, 0f, h) }
            steps(step, step, h) { i, y -> hline(if ((i + 1) % 5 == 0) MAIN else FINE, y, 0f, w) }
        }

        fun grid() {
            val step = (5f * mm).coerceAtLeast(3f)
            steps(step, step, w) { _, x -> vline(MAIN, x, 0f, h) }
            steps(step, step, h) { _, y -> hline(MAIN, y, 0f, w) }
        }

        fun letter() {
            val l = 0.1f * w
            val r = 0.9f * w
            val mid = 0.55f * w
            val top = 0.05f * h
            val senderH = 28f * dp
            rect(MAIN, l, top, mid, top + senderH)
            labelLeft(TemplateLabel.SENDER, l, top, senderH)
            val recTop = top + senderH
            val recBottom = recTop + 110f * dp
            rect(MAIN, l, recTop, mid, recBottom)
            labelLeft(TemplateLabel.RECIPIENT, l, recTop, senderH)
            rect(MAIN, 0.62f * w, top, r, top + senderH)
            labelLeft(TemplateLabel.DATE, 0.62f * w, top, senderH)
            val subjectY = recBottom + 44f * dp
            hline(HEAD, subjectY, l, r)
            addLabel(TemplateLabel.SUBJECT, l + pad, subjectY - pad, false)
            // Falz- und Lochmarken am linken Rand.
            for (f in floatArrayOf(1f / 3f, 0.5f, 2f / 3f)) hline(MAIN, f * h, 0f, minOf(12f * dp, w / 4f))
        }

        // ---- Tabellen ----

        /** Rahmen mit Kopfzeile, Spaltenlinien [colXs] und Zeilen; gibt die Zeilenzahl zurück. Körper beginnt bei ft+[header]. */
        private fun table(header: Float, rowH: Float, colXs: List<Float>, labelsInHeader: List<TemplateLabel>): Int {
            val rh = rowH.coerceAtLeast(3f)
            val rows = floor((fh - header) / rh).toInt().coerceIn(0, MAX_STEPS)
            val bottom = ft + header + rows * rh
            rect(MAIN, fl, ft, fr, bottom)
            hline(HEAD, ft + header, fl, fr)
            for (x in colXs) vline(MAIN, x, ft, bottom)
            for (k in 1 until rows) hline(MAIN, ft + header + k * rh, fl, fr)
            val bounds = listOf(fl) + colXs + listOf(fr)
            for ((i, label) in labelsInHeader.withIndex()) labelCenter(label, bounds[i], bounds[i + 1], ft, header)
            return rows
        }

        fun todo() {
            val header = 44f * dp
            val rowH = 68f * dp
            val c1 = fl + 0.09f * fw
            val c2 = fl + 0.32f * fw
            val rows = table(header, rowH, listOf(c1, c2), listOf(TemplateLabel.DONE, TemplateLabel.TIME, TemplateLabel.TODOS))
            for (k in 0 until rows) circle((fl + c1) / 2f, ft + header + (k + 0.5f) * rowH, minOf(11f * dp, (c1 - fl) / 3f))
        }

        fun tasks() {
            val header = 44f * dp
            val rowH = 56f * dp
            val c1 = fl + 0.08f * fw
            val due = fr - 0.16f * fw
            val prio = due - 0.12f * fw
            val rows = table(header, rowH, listOf(c1, prio, due), listOf(TemplateLabel.DONE, TemplateLabel.TASK, TemplateLabel.PRIORITY, TemplateLabel.DUE))
            val half = minOf(10f * dp, (c1 - fl) / 3f)
            for (k in 0 until rows) {
                val cy = ft + header + (k + 0.5f) * rowH
                val cx = (fl + c1) / 2f
                rect(MAIN, cx - half, cy - half, cx + half, cy + half)
            }
        }

        fun errorLog() {
            val header = 44f * dp
            val x1 = fl + 0.14f * fw
            val rest = (fr - x1) / 3f
            table(
                header, 64f * dp, listOf(x1, x1 + rest, x1 + 2f * rest),
                listOf(TemplateLabel.DATE, TemplateLabel.ERROR, TemplateLabel.CAUSE, TemplateLabel.SOLUTION),
            )
        }

        // ---- Pläne ----

        fun meeting() {
            val hdr = 44f * dp
            val sec = 30f * dp
            val row = 40f * dp
            rect(MAIN, fl, ft, fr, fb)
            val y1 = ft + hdr
            val y2 = y1 + hdr
            hline(MAIN, y1, fl, fr)
            labelLeft(TemplateLabel.TITLE, fl, ft, hdr)
            val midX = (fl + fr) / 2f
            vline(MAIN, midX, y1, y2)
            labelLeft(TemplateLabel.DATE, fl, y1, hdr)
            labelLeft(TemplateLabel.PARTICIPANTS, midX, y1, hdr)
            // Agenda
            hline(HEAD, y2, fl, fr)
            labelLeft(TemplateLabel.AGENDA, fl, y2, sec)
            val agendaEnd = y2 + sec + 5f * row
            lines(FINE, y2 + sec + row, row, agendaEnd, fl, fr)
            // Beschlüsse unten, Notizen dazwischen
            val decTop = maxOf(agendaEnd + sec, fb - 0.22f * fh).coerceAtMost(fb)
            hline(HEAD, agendaEnd, fl, fr)
            labelLeft(TemplateLabel.NOTES, fl, agendaEnd, sec)
            lines(FINE, agendaEnd + sec + row, row, decTop, fl, fr)
            hline(HEAD, decTop, fl, fr)
            labelLeft(TemplateLabel.DECISIONS, fl, decTop, sec)
            lines(FINE, decTop + sec + row, row, fb, fl, fr)
        }

        fun weekly() {
            val days = listOf(
                TemplateLabel.MON, TemplateLabel.TUE, TemplateLabel.WED, TemplateLabel.THU,
                TemplateLabel.FRI, TemplateLabel.SAT, TemplateLabel.SUN,
            )
            val rowH = fh / 7f
            val lc = fl + 0.1f * fw
            val cell = 40f * dp
            rect(MAIN, fl, ft, fr, fb)
            vline(MAIN, lc, ft, fb)
            for ((i, day) in days.withIndex()) {
                val top = ft + i * rowH
                if (i > 0) hline(HEAD, top, fl, fr)
                labelCenter(day, fl, lc, top, cell)
                lines(FINE, top + cell, cell, top + rowH - 1f, lc, fr)
            }
        }

        fun monthly() {
            val days = listOf(
                TemplateLabel.MON, TemplateLabel.TUE, TemplateLabel.WED, TemplateLabel.THU,
                TemplateLabel.FRI, TemplateLabel.SAT, TemplateLabel.SUN,
            )
            val titleH = 44f * dp
            val dayH = 32f * dp
            val gridTop = ft + titleH + dayH
            val colW = fw / 7f
            val rowH = (fb - gridTop) / 6f
            rect(MAIN, fl, ft, fr, fb)
            hline(MAIN, ft + titleH, fl, fr)
            hline(HEAD, gridTop, fl, fr)
            for (c in 1 until 7) vline(MAIN, fl + c * colW, ft + titleH, fb)
            for (r in 1 until 6) hline(MAIN, gridTop + r * rowH, fl, fr)
            for ((c, day) in days.withIndex()) labelCenter(day, fl + c * colW, fl + (c + 1) * colW, ft + titleH, dayH)
        }

        // ---- Arbeit und Bildung ----

        fun readingNotes() {
            val hdr = 44f * dp
            val sec = 30f * dp
            val row = 40f * dp
            rect(MAIN, fl, ft, fr, fb)
            val y1 = ft + hdr
            val y2 = y1 + hdr
            hline(MAIN, y1, fl, fr)
            labelLeft(TemplateLabel.TITLE, fl, ft, hdr)
            val midX = (fl + fr) / 2f
            vline(MAIN, midX, y1, y2)
            labelLeft(TemplateLabel.AUTHOR, fl, y1, hdr)
            labelLeft(TemplateLabel.DATE, midX, y1, hdr)
            hline(HEAD, y2, fl, fr)
            labelLeft(TemplateLabel.QUOTES, fl, y2, sec)
            val quotesEnd = y2 + 0.4f * (fb - y2)
            lines(FINE, y2 + sec + row, row, quotesEnd, fl, fr)
            hline(HEAD, quotesEnd, fl, fr)
            labelLeft(TemplateLabel.NOTES, fl, quotesEnd, sec)
            lines(FINE, quotesEnd + sec + row, row, fb, fl, fr)
        }

        fun cornell() {
            val hdr = 56f * dp
            val sec = 30f * dp
            val cueX = fl + 0.28f * fw
            val summaryTop = fb - 0.2f * fh
            rect(MAIN, fl, ft, fr, fb)
            hline(HEAD, ft + hdr, fl, fr)
            labelLeft(TemplateLabel.TOPIC, fl, ft, hdr)
            vline(MAIN, cueX, ft + hdr, summaryTop)
            hline(HEAD, summaryTop, fl, fr)
            labelLeft(TemplateLabel.CUES, fl, ft + hdr, sec)
            labelLeft(TemplateLabel.NOTES, cueX, ft + hdr, sec)
            labelLeft(TemplateLabel.SUMMARY, fl, summaryTop, sec)
        }

        // ---- Vorlagen aus HiNote (Maße aus den HiNote-PDF-Vorlagen, Entwurfsmaß 702 x 840 mal [ds]) ----

        /** Gitterpapier (HiNote `handwrite3`): 10 x 10 Kästchen je 66 x 67, gestrichelte Mittelkreuze. */
        fun characterGrid() {
            val x0 = 21.5f * ds
            val colW = 66f * ds
            val rowH = 67f * ds
            val pitch = 80.67f * ds
            val y0 = 23.5f * ds
            val dash = 4f * ds
            val dashPitch = 6f * ds
            for (r in 0 until 10) {
                val top = y0 + r * pitch
                val bottom = top + rowH
                hline(MAIN, top, x0, x0 + 10 * colW)
                hline(MAIN, bottom, x0, x0 + 10 * colW)
                for (c in 0..10) vline(MAIN, x0 + c * colW, top, bottom)
                val cy = top + rowH / 2f
                for (c in 0 until 10) {
                    val left = x0 + c * colW
                    val cx = left + colW / 2f
                    for (k in 0 until 11) {
                        val a = top + k * dashPitch
                        vline(FINE, cx, a, minOf(a + dash, bottom))
                        val b = left + k * dashPitch
                        hline(FINE, cy, b, minOf(b + dash, left + colW))
                    }
                }
            }
        }

        /** Notenlinien (HiNote `learning6`): 10 Systeme zu je 5 Linien im Abstand 8. */
        fun staff() {
            val x1 = 25f * ds
            val x2 = 678f * ds
            for (g in 0 until 10) {
                val top = (116.479f + g * 72.78f) * ds
                for (j in 0 until 5) hline(MAIN, top + j * 8f * ds, x1, x2)
            }
        }

        /** Zeitmanagement (HiNote `plan6`): Vier-Felder-Matrix mit Achsenbeschriftung. */
        fun timeManagement() {
            vline(MAIN, 351f * ds, 62f * ds, 772f * ds)
            hline(MAIN, 420f * ds, 0f, w)
            labelLeft(TemplateLabel.URGENT, 351f * ds, 40f * ds, 40f * ds)
            labelLeft(TemplateLabel.NOT_URGENT, 351f * ds, 761f * ds, 40f * ds)
            labelLeft(TemplateLabel.NOT_IMPORTANT, 0f, 418f * ds, 40f * ds)
            labelRight(TemplateLabel.IMPORTANT, w, 418f * ds, 40f * ds)
        }

        /** Punkteblatt (HiNote `learning4`, Accenture): Rahmen, Titelband, zwei Spalten Punkt/Aktion. */
        fun pointSheet() {
            val l = 24f * ds
            val r = 681f * ds
            val mid = 351.3f * ds
            rect(MAIN, l, 24f * ds, r, 816f * ds)
            hline(MAIN, 72f * ds, l, r)
            hline(HEAD, 117f * ds, l, r)
            vline(MAIN, mid, 72f * ds, 816f * ds)
            labelCenter(TemplateLabel.POINT, l, mid, 72f * ds, 45f * ds)
            labelCenter(TemplateLabel.ACTION, mid, r, 72f * ds, 45f * ds)
        }

        /** SWOT (HiNote `learning5`): Kreuz; die Lage der Beschriftungen ist eine Annahme (S/W oben, O/T unten). */
        fun swot() {
            vline(MAIN, 351f * ds, 0f, 840f * ds)
            hline(MAIN, 420f * ds, 0f, w)
            labelLeft(TemplateLabel.STRENGTHS, 0f, 8f * ds, 44f * ds)
            labelLeft(TemplateLabel.WEAKNESSES, 351f * ds, 8f * ds, 44f * ds)
            labelLeft(TemplateLabel.OPPORTUNITIES, 0f, 428f * ds, 44f * ds)
            labelLeft(TemplateLabel.THREATS, 351f * ds, 428f * ds, 44f * ds)
        }
    }

    private const val DESIGN_WIDTH = 702f

    // Linienstärken/-farben (Auswahl im Renderer).
    private const val FINE = 0
    private const val MAIN = 1
    private const val HEAD = 2
}
