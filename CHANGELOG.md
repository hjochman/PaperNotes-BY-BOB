# Changelog

Alle nennenswerten Änderungen an diesem Projekt werden in dieser Datei dokumentiert.

Das Format basiert auf [Keep a Changelog](https://keepachangelog.com/de/1.0.0/)
und dieses Projekt folgt [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Hinzugefügt

- **Neue Notiz direkt im Ordner (Android-App):** Das Ordnermenü (⋮) enthält den Eintrag „Neue Notiz“, der die Notiz in diesem Ordner anlegt und öffnet. In der Kachelansicht zeigt jede Ordnerkachel oben rechts ein kleines „+“ mit derselben Funktion (nicht im Auswahlmodus). Am Gerät noch zu bestätigen.

### Behoben

- **Verschluckter Strichanfang beim Schreiben (Android-App):** Die Zwei-Finger-Geste für Zoom/Pan sperrte das Schreiben, sobald ein zweiter Zeiger (z. B. Handballen) aufsetzte. Sie entfällt. Zoom läuft nun über `-`/`+` mit Prozentanzeige in der Kopfzeile (100 bis 500 % in Stufen), Verschieben im gezoomten Zustand per Ein-Finger-Wischen, das nie greift, solange der Stift aufliegt, schwebt oder gerade abgehoben wurde. Beim Wischen zeigt ein Gummiband-Pfeil die Richtung, dünne Balken am Rand zeigen den sichtbaren Ausschnitt. Beim Öffnen einer Notiz ist das Schreiben bis zum Anzeigen der Seite gesperrt, und eine Größenänderung der Schreibfläche wird zurückgestellt, solange der Stift aufliegt. Das Debug-Log im Touch-Pfad entfällt. Am Gerät noch zu bestätigen.

## [1.0.0] - 2026-11-11

Erstes vollständiges Haupt-Release von **PaperNotes by BOB** – die Notiz-App für das Huawei MatePad Paper (E-Ink) inklusive Nextcloud-Plugin.

### Android-App (Huawei MatePad Paper)

- **Flüssiges E-Ink-Schreiben:**
  - Direkte Anbindung an die geräteeigene E-Ink-Stiftschicht (`HWEditView`) für minimalste Latenz.
  - Vier Stifttypen (Füllfederhalter, Kugelschreiber, Bleistift, Marker mit echter Transparenz) in 3 Farben (Schwarz, Blau, Rot) und einstellbarer Strichstärke.
  - Radierer mit Teilstück- und Vollstrich-Löschung („Striche löschen“).
  - Lasso-Werkzeug mit Filtern (Stift, Text, Bild) und Aktionsmenü (Ausschneiden, Kopieren, Einfügen, Löschen).
  - Zoom (100 % bis 500 %) über `-`/`+` in der Kopfzeile und Verschieben der Seite per Ein-Finger-Wischen im Editor.
  - Undo/Redo-Verlauf pro Seite.

- **Seitenvorlagen (21 Vorlagen):**
  - *Grundlegend:* Blank, Weit liniert, Eng liniert, Gepunktet.
  - *Schreiben:* Schreiblinie, Millimeterpapier, Kariert, Briefbogen, Gitterpapier chinesisch, Notenlinien.
  - *Pläne:* To-Do-Liste, Besprechung, Wochenplan, Monatsplan, Zeitmanagement.
  - *Arbeit/Bildung:* Aufgaben, Fehlerprotokoll, Lesenotizen, Cornell-Methode, Punkteblatt, SWOT.

- **Multimediale Notizelemente:**
  - **Rich-Text-Textboxen:** Formatierungsleiste direkt über der Tastatur (Fett, Kursiv, Unterstrichen, Schriftgröße mit Schieberegler, Listen mit Punkten/Quadraten/Zahlen/Buchstaben, Einzug, Ausrichtung, Hintergrundfarben).
  - **Bilder:** Einfügen aus der Galerie/Dateiauswahl, Zuschneiden (Crop-Overlay), Verschieben und Skalieren.
  - **Audioaufnahmen:** AAC/M4A-Tonaufnahme und -Wiedergabe, Zweizeilen-Audioleiste, Umbenennen, gezieltes Löschen und Export einzelner Aufnahmen.

- **Startseite & Notizverwaltung:**
  - Kachel- und Listenansicht mit Vorschau, Notiztitel, Änderungsdatum und Favoriten-/Fixier-Symbol.
  - Schnellauswahl-Kategoriemenü im Titel (Alle Notizen, Nicht kategorisiert, Favoriten, Papierkorb, Sicherungen).
  - Vollständige Ordnerhierarchie mit Ordnerkacheln und Multi-Vorschau.
  - Titelsuche, Sortierung (Zuletzt geändert, Älteste zuerst, Zuletzt erstellt, Titel A–Z / Z–A).
  - Mehrfachauswahlmodus für Notizen und Ordner (Teilen, Verschieben, Löschen).

- **Papierkorb & Datensicherheit:**
  - Papierkorb mit konfigurierbarer Vorhaltefrist (Standard 30 Tage, wählbar: Nie, 7, 14, 30, 60, 90, 180, 365 Tage) und automatischer Bereinigung abgelaufener Notizen.
  - Wiederherstellen von Notizen oder endgültiges Löschen samt zugehöriger Sicherungen.
  - Automatische Sicherungskopien im Hintergrund während des Schreibens (einstellbares Intervall, bis zu 10 Sicherungen je Notiz, Wiederherstellen als neue Kopie).
  - Offenes `.pnote`-Dateiformat (ZIP-Archiv mit JSON-Seiten und Medien) im regulären Benutzerverzeichnis `Documents/PaperNotes`.

- **HiNote-Import:**
  - Vollständiger Import aller Notizen aus der vorinstallierten Huawei-HiNote-App (`.hinote`-Archive und `Documents/hinote/custom`).
  - Übernahme aller Handschriftstriche, Seitenvorlagen, Bilder, Textboxen und Ordnerstrukturen.
  - Erkennung von Doppelimporten und Schutz vor Mehrfachimporten.

- **Vektor-PDF-Export & Teilen:**
  - Hochwertiger Vektor-PDF-Export in DIN A4 direkt auf dem Gerät.
  - Optionales Einbetten von Tonaufnahmen als echte PDF-Dateianhänge (`/EmbeddedFiles`).
  - Teilen-Funktion für PDF-Dokumente über Android-Share-Sheet.

- **Nextcloud-Synchronisation:**
  - Effiziente Einweg-Synchronisation (Tablet → Nextcloud) per WebDAV im Hintergrund.
  - Sichere Authentifizierung über Nextcloud Login Flow v2 (App-Passwort im Android Keystore).
  - Statusanzeige auf der Startseite und im Launcher-Widget.

- **Android-Launcher-Widget:**
  - Anpassbares Widget für inkOS / E-Ink-Launcher mit Notizliste (Zuletzt geändert, Favoriten, Fixierte, Ordner).
  - Optionale Uhr, Schnellknopf für neue Notizen, Akkustand, Benachrichtigungszähler und Nextcloud-Sync-Status.

### Nextcloud-Plugin (`papernotes`)

- **Natives PaperNote-Dateityp-Erlebnis:**
  - Erkennt `.pnote`-Dateien serverseitig als `application/x-papernotes`.
  - Durchgängige Darstellung des Dateityps als **PaperNote** mit eigenem Icon in Dateilisten, Kacheln und der Detail-Seitenleiste.
  - SabreDAV-Plugin für menschenlesbare Notiztitel als `{DAV:}displayname` und Content-Type in WebDAV-Clients.
- **Web-Viewer im Browser:**
  - Vollständiger Canvas-Viewer zum Lesen von `.pnote`-Notizbüchern im Browser.
  - Pixelgenaues Vektor-Rendering aller 21 Seitenvorlagen und Striche.
  - Anzeige von Textboxen, Bildern und integrierten Audio-Playern für Tonaufnahmen.
  - Zoom & Pan, Seitenleiste mit Miniaturen und Lesezeichen, Tastatur-Navigation.
- **Serverseitiger Vektor-PDF-Export:**
  - Erzeugt PDF-1.4-Vektordokumente mit Textboxen, Bildern und optionalen Audio-Dateianhängen (`?audio=1`).
  - Reines PHP ohne externe Binärabhängigkeiten.
- **Umfassende Sicherheit:**
  - Schutz vor Zip-Bomben (Größen- und Mengengrenzen), strikte Pfadprüfungen (Path-Traversal-Schutz), UUID-Validierung und Dateirechteprüfung über Nextclouds Dateisystem.
