package local.papernotes.editor

import local.papernotes.model.ImageAttachment
import kotlin.math.ceil
import kotlin.math.floor

/** Reine Rechenhilfen für eingefügte Bilder (JVM-testbar, ohne Android-Bezug). */
object ImageUtil {
    /** Kleinster zulässiger Ausschnitt beim Zuschneiden, als Anteil der Bildbreite bzw. -höhe. */
    const val MIN_FRACTION = 0.05f

    /** Anteil der Seitenbreite, den ein neu eingefügtes Bild einnimmt. */
    const val INITIAL_WIDTH_FRACTION = 0.5f

    /** Obergrenze der Höhe eines neu eingefügten Bildes als Anteil der Seitenhöhe. */
    const val INITIAL_HEIGHT_FRACTION = 0.6f

    /**
     * Wie ein Bild mit EXIF-Orientation aufrecht gestellt wird: erst bei [flipHorizontal] an der senkrechten Achse
     * spiegeln, dann um [rotationDegrees] (0, 90, 180, 270) im Uhrzeigersinn drehen.
     * Bei 90 und 270 Grad tauschen Breite und Höhe ([swapsDimensions]).
     */
    data class Orientation(val rotationDegrees: Int, val flipHorizontal: Boolean) {
        val swapsDimensions: Boolean get() = rotationDegrees == 90 || rotationDegrees == 270

        /** True, wenn das Bild unverändert bleibt. */
        val isIdentity: Boolean get() = rotationDegrees == 0 && !flipHorizontal
    }

    /** Keine Drehung und keine Spiegelung. */
    val ORIENTATION_NONE = Orientation(0, false)

    /**
     * Abbildung eines EXIF-Orientation-Wertes (1..8, Tag 0x0112) auf [Orientation]. Normal (1), Undefined (0) und alle
     * unbekannten Werte ergeben [ORIENTATION_NONE].
     */
    fun orientationOf(exifValue: Int): Orientation = when (exifValue) {
        2 -> Orientation(0, true) // gespiegelt
        3 -> Orientation(180, false)
        4 -> Orientation(180, true) // senkrecht gespiegelt
        5 -> Orientation(270, true) // an der Hauptdiagonale gespiegelt
        6 -> Orientation(90, false)
        7 -> Orientation(90, true) // an der Nebendiagonale gespiegelt
        8 -> Orientation(270, false)
        else -> ORIENTATION_NONE
    }

    /** Breite und Höhe nach Anwendung von [o]. */
    fun orientedSize(width: Int, height: Int, o: Orientation): Pair<Int, Int> =
        if (o.swapsDimensions) height to width else width to height

    /** Ausschnitt als Anteile (0..1) der Bildbreite bzw. -höhe. */
    data class Fractions(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** Ausschnitt in Bildpunkten; [right] und [bottom] sind exklusiv. */
    data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /** Dateiendung (für `media/<uuid>.<ext>`) nach den ersten Bytes; unbekannte Formate ergeben "img". */
    fun extensionOf(bytes: ByteArray): String = when {
        bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "jpg"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
            String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "webp"
        bytes.size >= 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "GIF8" -> "gif"
        else -> "img"
    }

    /** Größe eines neu eingefügten Bildes in Seitenkoordinaten: halbe Seitenbreite, Seitenverhältnis bleibt erhalten. */
    fun initialSize(imageWidth: Int, imageHeight: Int, pageWidth: Float, pageHeight: Float): Pair<Float, Float> {
        var w = pageWidth * INITIAL_WIDTH_FRACTION
        var h = w * imageHeight / imageWidth
        val maxH = pageHeight * INITIAL_HEIGHT_FRACTION
        if (h > maxH) {
            h = maxH
            w = h * imageWidth / imageHeight
        }
        return w to h
    }

    /** Potenz von 2, bei der die längere Seite höchstens [maxDim] Bildpunkte hat (für `inSampleSize`). */
    fun sampleSize(width: Int, height: Int, maxDim: Int): Int {
        var s = 1
        val longest = maxOf(width, height)
        while (longest / s > maxDim) s *= 2
        return s
    }

    /**
     * Ausschnitt aus einer Auswahl in Ansichtspunkten ([x1],[y1])-([x2],[y2]) einer Ansicht der Größe [viewW] x [viewH].
     * Reihenfolge der Ecken egal, außerhalb liegende Teile werden abgeschnitten. Null, wenn der Ausschnitt zu klein ist.
     */
    fun fractions(x1: Float, y1: Float, x2: Float, y2: Float, viewW: Float, viewH: Float): Fractions? {
        if (viewW <= 0f || viewH <= 0f) return null
        val l = (minOf(x1, x2) / viewW).coerceIn(0f, 1f)
        val r = (maxOf(x1, x2) / viewW).coerceIn(0f, 1f)
        val t = (minOf(y1, y2) / viewH).coerceIn(0f, 1f)
        val b = (maxOf(y1, y2) / viewH).coerceIn(0f, 1f)
        if (r - l < MIN_FRACTION || b - t < MIN_FRACTION) return null
        return Fractions(l, t, r, b)
    }

    /** Ausschnitt in Bildpunkten eines Bildes der Größe [imageWidth] x [imageHeight]; mindestens 1 x 1 Punkt. */
    fun pixelRect(f: Fractions, imageWidth: Int, imageHeight: Int): PixelRect {
        val l = floor(f.left * imageWidth).toInt().coerceIn(0, imageWidth - 1)
        val t = floor(f.top * imageHeight).toInt().coerceIn(0, imageHeight - 1)
        val r = ceil(f.right * imageWidth).toInt().coerceIn(l + 1, imageWidth)
        val b = ceil(f.bottom * imageHeight).toInt().coerceIn(t + 1, imageHeight)
        return PixelRect(l, t, r, b)
    }

    /** Das zugeschnittene Bild liegt an der Stelle des gewählten Ausschnitts und zeigt die neue Datei [newFile]. */
    fun cropped(img: ImageAttachment, f: Fractions, newFile: String): ImageAttachment = img.copy(
        filePath = newFile,
        x = img.x + f.left * img.width,
        y = img.y + f.top * img.height,
        width = (f.right - f.left) * img.width,
        height = (f.bottom - f.top) * img.height,
    )
}
