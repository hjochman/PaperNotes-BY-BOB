# PaperNotes – Nextcloud Plugin

Nextcloud-App für die **PaperNotes** Notiz-App des Huawei MatePad Paper (E-Ink).
Ermöglicht das direkte Betrachten von `.pnote`-Notizbüchern im Nextcloud-Webbrowser sowie den Vektor-PDF-Export und -Download.

---

## Funktionen

- **Dateiaktion für `.pnote`:**
  - Standard-Klickaktion: Öffnet das Notizbuch direkt im interaktiven Web-Viewer.
  - Drei-Punkte-Menü `...`: „In PaperNotes öffnen“ und „Als PDF herunterladen“.
  - Nahtlose Integration mit `OCA.Viewer` und der Nextcloud Dateien-App.
- **Anzeige im Browser (HTML5-Canvas & Vektor-Striche):**
  - **Alle 21 Vorlagen** werden pixelgenau gezeichnet:
    - *Grundlegend:* Blank, Weit liniert (`lines_wide`), Eng liniert (`lines_narrow`), Gepunktet (`dotted`).
    - *Schreiben:* Schreiblinie (`handwriting`), Millimeterpapier (`millimeter`), Kariert (`grid`), Briefbogen (`letter`), Gitterpapier chinesisch (`character_grid`), Notenlinien (`staff`).
    - *Pläne:* To-Do-Liste (`todo`), Besprechung (`meeting`), Wochenplan (`weekly`), Monatsplan (`monthly`), Zeitmanagement (`time_management`).
    - *Arbeit/Bildung:* Aufgaben (`tasks`), Fehlerprotokoll (`error_log`), Lesenotizen (`reading_notes`), Cornell-Methode (`cornell`), Punkteblatt (`point_sheet`), SWOT (`swot`).
  - **Vektor-Striche:** Originalgetreue Darstellung aller Striche mit runden Kappen (`lineCap: round`), runden Übergängen (`lineJoin: round`), variabler Strichbreite und exakter ARGB-Farbpalette.
  - **Textboxen, Bilder, Aufnahmen (ab B1):** Textboxen mit Hintergrund, Fett, Kursiv, Unterstrichen, Farbe, Größe, Listen und Ausrichtung; Bilder unter den Strichen; Aufnahmen stehen nicht auf der Seite, sondern als eigene Player-Zeile unter der Seite.
  - **Navigation:** Vor/Zurück-Blättern, Seitenzahlanzeige (`Seite X / Y`), Tastatursteuerung (Pfeiltasten / Bild auf/ab), Seitenleiste mit anklickbaren Miniaturvorschauen und Lesezeichen-Sternen (`★`).
  - **Sprachen:** Der Viewer (Schaltflächen, Hinweise, Fehlermeldungen) und die Vorlagenbeschriftungen erscheinen auf Deutsch, wenn die Nextcloud-Sprache des Nutzers Deutsch ist, sonst auf Englisch. Die Texte stehen als Tabelle (`de`/`en`) in `js/papernotes-viewer.js`; die Dateiaktionen übersetzt `l10n/`.
  - **Zoom & Pan:** Vergrößern (`+`), Verkleinern (`-`), Einpassen ins Fenster (`Fit` bzw. `Anpassen`), 100%-Ansicht, Verschieben per Mausdrag oder Touch-Geste, Strg+Mausrad-Zoom.
- **PDF-Export & Download:**
  - Erzeugt hochwertige, standardkonforme PDF-1.4-Vektordokumente direkt auf dem Server.
  - Skaliert Seiten automatisch auf DIN-A4-Breite (595 pt) unter Beibehaltung des Seitenverhältnisses.
  - Reines PHP ohne externe Abhängigkeiten oder Binärprogramme (zlib-Kompression integriert).
  - Enthält Bilder und Textboxen (Rich-Text). Bei Notizen mit Aufnahmen fragt „PDF herunterladen“ (englisch „Download PDF“), ob die Aufnahmen als Dateianhänge an den Seiten eingebettet werden sollen (`?audio=1`, bis 48 MB); gezeichnet werden sie nie.
  - Download per Klick in der Viewer-Kopfzeile oder direkt aus dem Dateien-Kontextmenü.
- **Umfassende Sicherheit:**
  - Schutz vor Zip-Bomben und Überlastung durch strikte Obergrenzen:
    - Max. Gesamtgröße entpackter Daten: 64 MB
    - Max. Größe eines einzelnen Archiveintrags: 16 MB
    - Max. Vorschaugröße (`preview.jpg`): 2 MB
    - Max. Anzahl an ZIP-Einträgen: 2.000
    - Max. Seiten je Notizbuch: 5.000
    - Max. Striche je Seite: 200.000
    - Max. Punkte je Strich: 20.000
    - Max. Seitendimension: 20.000 px
  - Strikte Pfad- und Namensprüfung (Verhinderung von Path-Traversal):
    - Ablehnung von Einträgen mit `..`, `\`, führendem `/`, unzulässigen Zeichen oder Längen > 200 Zeichen.
    - Nur erlaubte Einträge: `manifest.json`, `preview.jpg`, `preview.png`, `pages/<uuid>.json`, `media/<uuid>.<ext>`.
  - UUID-Prüfung nach RFC 4122 (nur Kleinbuchstaben-Hex-Muster).
  - Rechteprüfung über das Dateisystem des angemeldeten Nutzers (`IRootFolder::getUserFolder()->getById()`, `IUserSession`). Es werden nur Notizen geöffnet, die im Dateibaum des Nutzers sichtbar sind und die er lesen darf.
  - **Freigaben:** Mit dem Nutzer geteilte Notizen und Ordner (Benutzer-, Gruppen-, Team-, Talk- und Deck-Freigaben) liegen nach dem Annehmen in seinem Dateibaum und funktionieren daher wie eigene Dateien. **Nicht unterstützt:** öffentliche Link-Freigaben ohne Anmeldung (Viewer und PDF verlangen eine Nextcloud-Anmeldung) sowie noch nicht angenommene Freigaben. Dieses Verhalten ist noch nicht in einer echten Nextcloud geprüft.
  - Vorschau und Medien werden aus einer temporären Kopie gelesen, die Datei wird dafür gestreamt (nicht im Speicher gehalten) und nur bis zu einer Größe von etwa 65 MB kopiert.

---

## Verzeichnisstruktur

```
nextcloud-app/
├── README.md                      # Diese Beschreibung und Installationsanleitung
└── papernotes/                    # Das installierbare Nextcloud-App-Paket
    ├── appinfo/
    │   ├── info.xml               # App-Metadaten, Version, Abhängigkeiten (NC 27–36)
    │   └── routes.php             # REST-API- und Viewer-Routen
    ├── css/
    │   └── papernotes.css         # Styling für Viewer-Modal, Toolbar, Sidebar, Thumbnails
    ├── img/
    │   ├── app.svg                # App-Icon
    │   └── pnote.svg              # Dateityp-Icon für .pnote
    ├── js/
    │   ├── papernotes-main.js     # Registrierung von Dateiaktionen & OCA.Viewer
    │   └── papernotes-viewer.js   # Interaktiver Canvas-Viewer & Vorlagen-Renderer
    ├── l10n/
    │   ├── de.json / de.js        # Deutsche Übersetzung
    │   └── en.json / en.js        # Englische Übersetzung
    ├── lib/
    │   ├── AppInfo/
    │   │   └── Application.php    # App-Bootstrap, Event-Registrierung & MIME-Type
    │   ├── Controller/
    │   │   ├── NoteController.php # Endpunkte: Notiz-JSON, Vorschau, PDF-Download
    │   │   └── ViewerController.php # Standalone-Vollbild-Ansicht
    │   ├── Listener/
    │   │   └── LoadScriptsListener.php # Einbindung von JS/CSS in Nextcloud Files & Viewer
    │   └── Service/
    │       ├── PdfExportService.php    # Vektor-PDF-Generator für alle 21 Vorlagen
    │       ├── PnoteParserService.php  # Sicherer .pnote ZIP- & JSON-Parser
    │       └── PnoteStorageService.php # Speicher- und Zugriffsrechteverwaltung
    ├── templates/
    │   └── viewer.php             # Template für Direktansicht
    └── tests/
        └── run_tests.php          # Eigenständige Testsuite (96 Tests)
```

---

## Installation

### Update über die Nextcloud-Oberfläche

Nextcloud kann Apps nicht aus einer Datei hochladen; der App-Store-Weg braucht eine Veröffentlichung. Praktikabel ist der Austausch des Ordners: das Paket `papernotes-nextcloud-app-<Version>.tar.gz` auf dem Server in `apps/` entpacken (siehe Schritt 1), danach unter **Administrationseinstellungen → Apps** erscheint die neue Version, bei Bedarf die App einmal deaktivieren und aktivieren. Das Paket enthält keine Tests.

### 1. App-Ordner auf den Nextcloud-Server kopieren

Kopieren Sie den Ordner `papernotes` in das `apps/`-Verzeichnis Ihrer Nextcloud-Installation:

```bash
# Beispiel per SCP/Rsync:
rsync -avz nextcloud-app/papernotes/ user@nextcloud-server:/var/www/html/nextcloud/apps/papernotes/
```

### 2. Dateirechte anpassen

Stellen Sie sicher, dass der Webserver-Benutzer (meist `www-data`) Lese- und Schreibrechte besitzt:

```bash
sudo chown -R www-data:www-data /var/www/html/nextcloud/apps/papernotes
sudo chmod -R 755 /var/www/html/nextcloud/apps/papernotes
```

### 3. App aktivieren

Aktivieren Sie die App über die Nextcloud-Kommandozeile (`occ`):

```bash
# Mit sudo:
sudo -u www-data php /var/www/html/nextcloud/occ app:enable papernotes

# Oder als root mit su:
su -s /bin/sh www-data -c 'php /var/www/html/nextcloud/occ app:enable papernotes'
```

*Alternativ:* Im Nextcloud-Webmenü unter **Administrationseinstellungen → Apps → Deaktivierte Apps** nach **PaperNotes** suchen und auf **Aktivieren** klicken.

---

## Tests ausführen

Die App enthält eine vollständige, eigenständige PHP-Testsuite zur Verifikation von Parser-Sicherheit und PDF-Generierung:

```bash
php nextcloud-app/papernotes/tests/run_tests.php
```

Ausgabe:
```
=== Running PaperNotes Nextcloud Plugin Tests ===

  [PASS] Valid Note: Manifest ID matches
  [PASS] Valid Note: Title preserved with UTF-8
  [PASS] Valid Note: Two pages parsed
  [PASS] Valid Note: Preview detected
  [PASS] Valid Note: Page 1 strokes parsed
  [PASS] PDF Export: Starts with valid header
  [PASS] PDF Export: Ends with %%EOF
  [PASS] PDF Export: 2 pages recorded in catalog
  [PASS] Security: Prohibit relative path traversal (../evil.txt)
  [PASS] Security: Prohibit leading slash (/etc/passwd)
  [PASS] Security: Prohibit backslashes in entry names
  [PASS] Security: Prohibit arbitrary entries like script.php
  [PASS] Security: Limit max entries in ZIP
  [PASS] Security: Limit entry size > 16MB
  [PASS] Security: Reject future format version 999
  [PASS] All 21 Templates: German PDF generation succeeded
  [PASS] All 21 Templates: 21 pages present in PDF
  [PASS] All 21 Templates: English PDF generation succeeded
  [PASS] Preview: Extracted preview.jpg faithfully

================================================
Results: 98 passed, 0 failed
```

---

## Lizenz

Dieses Plugin ist freie Software unter der **GNU General Public License v3.0 oder später** (GPL-3.0-or-later).
Copyright © 2025–2026 hjochman.
