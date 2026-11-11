[![PaperNotes by BOB](PaperNotes%20BY%20BOB-banner.jpg)](PaperNotes%20BY%20BOB-banner.jpg)

# PaperNotes by BOB

**PaperNotes by BOB** ist ein vollständiges Notiz-Ökosystem bestehend aus:
1. Einer **Android-App** für das **Huawei MatePad Paper** (HMW-W09, Android 10, E-Ink) als vollwertiger, quelloffener Ersatz für Huawei HiNote.
2. Einem **Nextcloud-Plugin** (`papernotes`), das `.pnote`-Notizbücher direkt im Browser visualisiert, als Vektor-PDF exportiert und per WebDAV integriert.

Handschrift wird über die geräteeigene E-Ink-Stiftschicht mit minimalster Latenz gezeichnet; die Benutzeroberfläche setzt auf klare, kontrastreiche Bedienelemente ohne störende Animationen und ist vollständig zweisprachig (Deutsch und Englisch).

*English: PaperNotes by BOB is an open-source note-taking ecosystem for the Huawei MatePad Paper E-Ink tablet and Nextcloud. It features native E-Ink pen integration, an open `.pnote` notebook format, rich-text boxes, images, audio recordings, automatic backups, HiNote import, and a browser-based Nextcloud viewer and PDF exporter.*

---

## 1. Android-App (Huawei MatePad Paper)

### ✍️ Editor & Stift-Engine
- **Flüssiges E-Ink-Schreiben:** Direkte Anbindung an Huaweis hardwarebeschleunigte E-Ink-Stiftschicht (`HWEditView`) für verzögerungsfreies Schreiben.
- **Stifttypen:** Vier Stiftmodi (Füllfederhalter, Kugelschreiber, Bleistift, Marker mit echter Transparenz) in den Farben Schwarz, Blau und Rot mit stufenloser Größenwahl.
- **Radierer:** Präzises Teilstück-Radieren und schnelles Vollstrich-Löschen („Striche löschen“).
- **Lasso-Werkzeug:** Auswählen, Verschieben, Ausschneiden, Kopieren, Einfügen und Löschen von Strichen mit Filteroptionen (Stift, Text, Bild).
- **Zwei-Finger-Zoom & Pan:** Stufenloser Zoom von 100 % bis 500 % und Verschieben der Schreibfläche.
- **Seitenleiste & Lesezeichen:** Schnellnavigation mit Miniaturvorschauen aller Seiten, Lesezeichen-Markierungen (`★`) und direktem Seitensprung („Gehe zu“).
- **Verlauf:** Unbegrenzter Undo/Redo-Verlauf pro Seite.

### 📄 21 Seitenvorlagen
- **Grundlegend:** Blank, Weit liniert (`lines_wide`), Eng liniert (`lines_narrow`), Gepunktet (`dotted`).
- **Schreiben:** Schreiblinie (`handwriting`), Millimeterpapier (`millimeter`), Kariert (`grid`), Briefbogen (`letter`), Gitterpapier chinesisch (`character_grid`), Notenlinien (`staff`).
- **Pläne:** To-Do-Liste (`todo`), Besprechung (`meeting`), Wochenplan (`weekly`), Monatsplan (`monthly`), Zeitmanagement (`time_management`).
- **Arbeit & Bildung:** Aufgaben (`tasks`), Fehlerprotokoll (`error_log`), Lesenotizen (`reading_notes`), Cornell-Methode (`cornell`), Punkteblatt (`point_sheet`), SWOT (`swot`).

### 📦 Multimediale Inhalte
- **Rich-Text-Textboxen:** Direktes Bearbeiten auf dem Blatt. Feste Formatleiste über der Tastatur mit Fett, Kursiv, Unterstrichen, Schriftgröße (Schieberegler 10–130), Listen (Punkte, Quadrate, Zahlen, Buchstaben), Einzug, Ausrichtung und Hintergrundfarben.
- **Bilder:** Einfügen aus Galerie oder Speicher, Zuschneiden mit Crop-Overlay, Skalieren und freies Platzieren.
- **Audioaufnahmen:** Integrierter Voice-Recorder (AAC/M4A), zweizeilige Steuerleiste, Umbenennen, Spurverwaltung und Export einzelner Aufnahmen.

### 📂 Startseite & Notizverwaltung
- **Übersicht:** Umschaltbar zwischen Kachelraster und kompakter Liste mit Seitenvorschau, Notiztitel, Änderungsdatum und Favoriten-/Fixier-Symbol.
- **Kategoriemenü:** Schnellauswahl im Titel für *Alle Notizen*, *Nicht kategorisiert*, *Favoriten*, *Papierkorb* und *Sicherungen*.
- **Ordnerhierarchie:** Vollständige Ordnerverwaltung mit Mehrfach-Vorschaukacheln.
- **Suche & Sortierung:** Echtzeit-Suche über alle Notiztitel; Sortierung nach Änderungsdatum, Erstelldatum oder Name.
- **Mehrfachauswahl:** Stapelverarbeitung für Notizen und Ordner (Teilen, Verschieben, Löschen).

### 🛡️ Datensicherheit & Speicher
- **Offenes Format:** Notizen werden als unverschlüsselte, standardkonforme `.pnote`-Dateien (ZIP-Archiv mit JSON und Mediendateien) in `Documents/PaperNotes` gespeichert.
- **Papierkorb mit automatischer Frist:** Gelöschte Notizen wandern in den Papierkorb und werden nach einer einstellbaren Frist (Standard: 30 Tage; wählbar: Nie, 7, 14, 30, 60, 90, 180, 365 Tage) automatisch endgültig gelöscht.
- **Automatische Sicherungskopien:** Erzeugt während des Schreibens zyklische Sicherungen (Intervall wählbar von 1 bis 30 Min., bis zu 10 Sicherungen je Notiz) und ermöglicht die Wiederherstellung als Kopie.
- **HiNote-Import:** Vollständiger Import aller bestehenden Notizen aus Huawei HiNote (`.hinote` oder Verzeichnis `Documents/hinote/custom`) inklusive aller Striche, Vorlagen, Bilder, Textboxen und Ordner.

### 📤 PDF-Export & Teilen
- Hochwertiger Vektor-PDF-Export in DIN A4 direkt auf dem Gerät.
- Notizen mit Audioaufnahmen können optional mit echten PDF-Dateianhängen (`/EmbeddedFiles`) exportiert werden.
- Teilen per Android-Share-Sheet oder direktes Speichern in `Documents/PaperNotesPDF`.

### 🔄 Nextcloud-Sync & Launcher-Widget
- **Nextcloud-Sync:** Stromsparender Hintergrund-Abgleich per WebDAV (Tablet → Nextcloud) über den Android `JobScheduler`. Anmeldung über Login Flow v2 (App-Passwort sicher im Android Keystore gespeichert).
- **Launcher-Widget:** Optimiert für E-Ink-Launcher (z. B. inkOS) mit konfigurierbaren Notizlisten, Uhr, Datum, Schnellnotiz-Knopf, Akkustand, Benachrichtigungszähler und Nextcloud-Sync-Status.

---

## 2. Nextcloud-Plugin (`papernotes`)

Das im Verzeichnis `nextcloud-app/` enthaltene Plugin integriert PaperNotes nahtlos in Nextcloud:

- **Dateityp & MIME-Type Erkennung:**
  - Registriert `.pnote`-Dateien als `application/x-papernotes`.
  - Zeigt den Dateityp in der Nextcloud-Oberfläche (Dateiliste, Kacheln und Metadaten-Seitenleiste) durchgängig als **PaperNote** mit passendem Notizbuch-Icon an.
  - SabreDAV-Integration liefert den Notiztitel als `{DAV:}displayname` für WebDAV-Clients.
- **Web-Viewer im Browser:**
  - Öffnet `.pnote`-Dateien direkt per Klick im Nextcloud-Webinterface (`OCA.Viewer`).
  - Pixelgetreue Darstellung aller 21 Seitenvorlagen, Vektor-Striche, Rich-Text-Textboxen und Bilder auf HTML5-Canvas.
  - Eigene Player-Leiste für Tonaufnahmen unterhalb der Seite.
  - Zoom & Pan, Seitenleiste mit Miniaturen, Lesezeichen und Tastaturnavigation.
- **Vektor-PDF-Export auf dem Server:**
  - Exportiert Notizen serverseitig in standardkonformes PDF 1.4.
  - Unterstützt optionale Audio-Dateianhänge (`?audio=1`).
  - Reines PHP ohne externe Binärabhängigkeiten.
- **Sicherheit & Rechte:**
  - Strikte Schutzmechanismen gegen Zip-Bomben, Path-Traversal und manipulierte Archive.
  - Zugriffsschutz über Nextclouds Benutzerdateisystem (inkl. angenommener Gruppen- und Benutzerfreigaben).

---

## Bauen & Entwickeln

### Voraussetzungen
- **Android-App:** Java 17, Android SDK (Build-Tools 36.0.0, Compile SDK 36, Min/Target SDK 29).
- **Nextcloud-Plugin:** PHP 8.1+ mit `zip` und `gd` Erweiterung.

### Android-App bauen
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home

# Tests, Debug-APK und Lint ausführen
./gradlew test assembleDebug lint

# Debug-APK auf verbundenem Tablet installieren
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Nextcloud-Plugin testen & paketieren
```bash
# Eigenständige PHP-Testsuite ausführen (98 Tests)
php nextcloud-app/papernotes/tests/run_tests.php

# Release-Tarball erstellen
COPYFILE_DISABLE=1 tar --exclude=tests -czf dist/papernotes-nextcloud-app-1.0.0.tar.gz -C nextcloud-app papernotes
```

### Installation auf dem Nextcloud-Server
```bash
# Plugin auf den Server synchronisieren
rsync -avz --delete nextcloud-app/papernotes/ user@nextcloud-server:/var/www/html/nextcloud/apps/papernotes/

# Dateirechte setzen & App aktivieren
sudo chown -R www-data:www-data /var/www/html/nextcloud/apps/papernotes
sudo -u www-data php /var/www/html/nextcloud/occ app:enable papernotes
sudo -u www-data php /var/www/html/nextcloud/occ maintenance:mimetype:update-db
sudo -u www-data php /var/www/html/nextcloud/occ maintenance:mimetype:update-js
```

---

## Verzeichnisstruktur

```
PaperNotes-BY-BOB/
├── app/                       # Android-Anwendung (Kotlin, Android 10 E-Ink)
│   ├── src/main/java/         # App-Quellcode (Editor, Storage, Sync, HiNote, Widget, etc.)
│   ├── src/main/res/          # Layouts, Vektorgrafiken, Lokalisierung (de, en)
│   └── src/test/              # JUnit-Testsuite für App-Logik und Codecs
├── eink-stub/                 # Compile-Only Stubs für Huaweis E-Ink-Framework (android.eink.*)
├── nextcloud-app/             # Nextcloud-App (papernotes)
│   ├── papernotes/            # Installierbares Nextcloud-Plugin (PHP, JS, CSS, Templates)
│   └── README.md              # Dokumentation des Nextcloud-Plugins
├── dist/                      # Gebaute Release-Pakete (APKs, Nextcloud-Tarball)
├── tools/                     # Hilfswerkzeuge (HiNote-Export-Tools, Signierung)
├── docs/                      # Dokumentation des binären HiNote-Formats
├── CHANGELOG.md               # Versionshistorie nach Keep a Changelog
├── ReleasePlan.md             # Release-Plan und Feature-Roadmap
├── AGENT.md                   # Technische Dokumentation und Entwicklungsrichtlinien
└── LICENSE                    # GNU General Public License v3.0
```

---

## Lizenz

Freie Software unter der **GNU General Public License v3.0 oder später** (GPL-3.0-or-later). Den vollständigen Text enthält die Datei [LICENSE](LICENSE).

Copyright © 2025–2026 hjochman. Dieses Programm wird ohne jede Gewährleistung verteilt.

*English: Free software under the GNU General Public License v3.0 or later. See [LICENSE](LICENSE). This program is distributed without any warranty.*
