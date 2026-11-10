package local.papernotes.editor

import android.eink.view.edit.HWPoint
import android.eink.view.edit.IHWTrack
import android.eink.view.edit.IHWTrackCreator
import android.graphics.RectF
import local.papernotes.model.Point
import local.papernotes.model.Stroke
import java.util.IdentityHashMap

/**
 * Reine Zahlenlogik für Striche (JVM-testbar, ohne android.eink und ohne android.graphics).
 */
internal object StrokeGeometry {
    /** Umgebendes Rechteck als [links, oben, rechts, unten], um die Strichbreite aufgeweitet (wie im Spike: `inset(-w)`). */
    fun bounds(points: List<Point>, width: Float): FloatArray {
        require(points.isNotEmpty()) { "points" }
        var l = points[0].x
        var t = points[0].y
        var r = l
        var b = t
        for (i in 1 until points.size) {
            val p = points[i]
            if (p.x < l) l = p.x
            if (p.x > r) r = p.x
            if (p.y < t) t = p.y
            if (p.y > b) b = p.y
        }
        return floatArrayOf(l - width, t - width, r + width, b + width)
    }
}

/**
 * Wandelt [Stroke] <-> [IHWTrack]. Dünne Schicht über `getHWTrackCreator()`; verlustfrei für Stifttyp, Breite, Farbe
 * sowie x, y, Zeit und Druck je Punkt (belegt in Spike 2). Striche ohne Punkte werden übersprungen.
 *
 * Koordinaten: [Stroke] liegt in Seitenkoordinaten, die Tracks der `HWEditView` in Ansichts-Pixeln. [transform] rechnet
 * beim Setzen (Seite -> Ansicht) und beim Lesen (Ansicht -> Seite) um, Strichbreiten inklusive. Ein Wechsel von
 * [transform] verwirft den Zwischenspeicher; der Aufrufer setzt danach die Tracks neu (`toTracks`).
 *
 * Die Elemente von `IHWTrack.getPoints()` sind [HWPoint]; die Roh-Typen der Framework-Listen werden hier einmal
 * gecastet. Nicht für den Stift-Pfad gedacht: wird nur nach Änderungen (Strichende, Radieren, Lasso) und beim
 * Seitenwechsel aufgerufen.
 *
 * Zwischenspeicher: Das Framework behält unveränderte Track-Objekte in seiner Liste. Damit nach jedem Strich nicht die
 * ganze Seite neu umgerechnet wird, merkt sich der Mapper je Track das zugehörige [Stroke] samt einem Fingerabdruck
 * (Anzahl Punkte, erster/letzter Punkt, Typ, Breite, Farbe). Der Lasso verschiebt Tracks an Ort und Stelle; dabei
 * ändern sich Anfangs- und Endpunkt, der Eintrag wird verworfen. Der Zwischenspeicher wird bei jedem Aufruf neu
 * aufgebaut und wächst daher nicht.
 */
class TrackMapper(private val creator: IHWTrackCreator) {
    /** [stroke] in Seitenkoordinaten; der Fingerabdruck ([view]) in den Ansichtswerten, die der Track trägt. */
    private class Cached(val stroke: Stroke, view: Stroke) {
        val n = view.points.size
        val first = view.points.first()
        val last = view.points.last()
        val penType = view.penType
        val width = view.width
        val color = view.color
    }

    private var cache = IdentityHashMap<IHWTrack, Cached>()

    var transform: PageTransform = PageTransform.IDENTITY
        set(value) {
            if (value != field) cache = IdentityHashMap()
            field = value
        }

    fun toTracks(strokes: List<Stroke>): MutableList<IHWTrack> {
        val out = ArrayList<IHWTrack>(strokes.size)
        val fresh = IdentityHashMap<IHWTrack, Cached>()
        for (page in strokes) {
            if (page.points.isEmpty()) continue
            val s = transform.toView(page)
            val pts = ArrayList<HWPoint>(s.points.size)
            for (p in s.points) pts.add(creator.createHWPoint(p.x, p.y, p.time, p.pressure))
            val b = StrokeGeometry.bounds(s.points, s.width)
            val track = creator.createHWTrack(s.penType, s.width, s.color, 0L, pts, RectF(b[0], b[1], b[2], b[3]))
            out.add(track)
            fresh[track] = Cached(page, s)
        }
        cache = fresh
        return out
    }

    fun fromTracks(tracks: List<*>?): List<Stroke> {
        if (tracks == null) return emptyList()
        val out = ArrayList<Stroke>(tracks.size)
        val fresh = IdentityHashMap<IHWTrack, Cached>()
        for (o in tracks) {
            val t = o as? IHWTrack ?: continue
            val raw = t.points ?: continue
            if (raw.isEmpty()) continue
            val known = cache[t]
            if (known != null && matches(known, t, raw)) {
                out.add(known.stroke)
                fresh[t] = known
            } else {
                val view = convert(t, raw)
                val page = transform.toPage(view)
                out.add(page)
                fresh[t] = Cached(page, view)
            }
        }
        cache = fresh
        return out
    }

    private fun matches(c: Cached, t: IHWTrack, raw: List<*>): Boolean {
        if (raw.size != c.n) return false
        if (t.penType != c.penType || t.penWidth != c.width || t.penColor != c.color) return false
        val f = raw[0] as HWPoint
        val l = raw[raw.size - 1] as HWPoint
        return f.x == c.first.x && f.y == c.first.y && l.x == c.last.x && l.y == c.last.y
    }

    /** Liest den Track unverändert (Ansichtswerte); die Umrechnung auf Seitenkoordinaten macht der Aufrufer. */
    private fun convert(t: IHWTrack, raw: List<*>): Stroke {
        val pts = ArrayList<Point>(raw.size)
        for (po in raw) {
            val p = po as HWPoint
            pts.add(Point(p.x, p.y, p.eventtime, p.pressure))
        }
        return Stroke(t.penType, t.penWidth, t.penColor, pts)
    }
}
