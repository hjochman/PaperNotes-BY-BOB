package local.papernotes.model

/**
 * Eine Tonaufnahme einer Seite (Phase 4).
 * [filePath] ist der Dateiname unterhalb von `media/` im Paket (z. B. `<uuid>.m4a`) und zugleich der Schlüssel in
 * [Note.media]. [title] ist die Bezeichnung in der Liste, [durationMs] die Länge in Millisekunden.
 */
data class AudioAttachment(
    val id: String,
    val filePath: String,
    val title: String,
    val durationMs: Long,
)
