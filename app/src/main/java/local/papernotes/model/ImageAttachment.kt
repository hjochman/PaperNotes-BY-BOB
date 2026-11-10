package local.papernotes.model

/**
 * Ein eingefügtes Bild auf einer Seite (Phase 4).
 * [filePath] ist der Dateiname unterhalb von `media/` im Paket (z. B. `<uuid>.jpg`) und zugleich der Schlüssel in
 * [Note.media]. Mehrere Bilder dürfen dieselbe Datei verwenden. Position und Größe (x, y, width, height) liegen in
 * Seitenkoordinaten.
 */
data class ImageAttachment(
    val id: String,
    val filePath: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)
