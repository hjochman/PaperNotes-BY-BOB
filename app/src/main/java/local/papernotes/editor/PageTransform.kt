package local.papernotes.editor

import local.papernotes.model.Point
import local.papernotes.model.Stroke

/**
 * Reine Abbildung zwischen Seitenkoordinaten (logische Seite, z. B. 1404x1872) und Ansichtskoordinaten (Pixel der
 * Schreibfläche): `Ansicht = Seite * [scale] + Versatz`. Gleicher Maßstab in x und y, damit Strichbreiten eindeutig
 * mitskalieren. Ohne Android-Bezug (JVM-testbar).
 *
 * Gespeichert werden Striche immer in Seitenkoordinaten; nur an der Grenze zur `HWEditView` (TrackMapper) und beim
 * Zeichnen der Vorlage wird umgerechnet. Zoom und Verschieben ändern später nur diese Abbildung.
 *
 * Die Identität ([isIdentity]) gibt Striche unverändert (dasselbe Objekt) zurück; so bleibt der Normalfall bitgenau und
 * der Vergleich im Verlauf ([PageHistory]) unberührt.
 */
data class PageTransform(val scale: Float, val offsetX: Float = 0f, val offsetY: Float = 0f) {
    init {
        require(scale.isFinite() && scale > 0f) { "scale" }
        require(offsetX.isFinite() && offsetY.isFinite()) { "offset" }
    }

    val isIdentity: Boolean get() = scale == 1f && offsetX == 0f && offsetY == 0f

    fun pageToViewX(x: Float): Float = x * scale + offsetX
    fun pageToViewY(y: Float): Float = y * scale + offsetY
    fun viewToPageX(x: Float): Float = (x - offsetX) / scale
    fun viewToPageY(y: Float): Float = (y - offsetY) / scale

    /** Strichbreite: Seite -> Ansicht (nur Maßstab, kein Versatz). */
    fun pageToViewWidth(w: Float): Float = w * scale
    fun viewToPageWidth(w: Float): Float = w / scale

    fun toView(p: Point): Point = if (isIdentity) p else Point(pageToViewX(p.x), pageToViewY(p.y), p.time, p.pressure)
    fun toPage(p: Point): Point = if (isIdentity) p else Point(viewToPageX(p.x), viewToPageY(p.y), p.time, p.pressure)

    /** Wandelt Punkte und Breite eines Strichs von Seiten- in Ansichtskoordinaten; Typ, Farbe, Zeit und Druck bleiben. */
    fun toView(s: Stroke): Stroke =
        if (isIdentity) s else Stroke(s.penType, pageToViewWidth(s.width), s.color, s.points.map { toView(it) })

    /** Umkehrung von [toView]: Ansichts- in Seitenkoordinaten. */
    fun toPage(s: Stroke): Stroke =
        if (isIdentity) s else Stroke(s.penType, viewToPageWidth(s.width), s.color, s.points.map { toPage(it) })

    companion object {
        val IDENTITY = PageTransform(1f)

        /** Seite füllt die Breite der Ansicht (Maßstab = Ansichtsbreite / Seitenbreite), Versatz 0. */
        fun fitWidth(viewWidth: Float, pageWidth: Float): PageTransform = PageTransform(viewWidth / pageWidth)
    }
}
