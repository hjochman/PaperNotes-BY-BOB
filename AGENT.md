# AGENT.md – PaperNotes by BOB

Dieser Leitfaden beschreibt Architektur, Konventionen, dauerhaft gültiges Gerätewissen und Arbeitsregeln für KI-Agenten in diesem Projekt. Den Plan mit Ist-Stand und offenen Aufgaben führt [`ReleasePlan.md`](ReleasePlan.md); das HiNote-Dateiformat steht in `docs/hinote-bin-format.md`; Änderungen je Version in `CHANGELOG.md`.

## Projektziel

- Eigene Notiz-App für das Huawei Paper (HMW-W09, Android 10, kein Root) als Ersatz für HiNote.
- Flüssiges Schreiben über die geräteeigene E-Ink-Schicht (`android.eink.view.edit.HWEditView`).
- Nextcloud-Sync, PDF-Export und saubere Notizverwaltung mit klassischer View-Architektur (kein Compose).

## Modulstruktur

```
PaperNotes/
├── app/                  – Hauptanwendung (local.papernotes, minSdk 29, targetSdk 29, Java 17)
│   ├── src/main/java/    – App-Quellcode (Kotlin, klassische Views)
│   │   └── local/papernotes/
│   │       ├── model/    – reine Kotlin-Datenklassen (Point, Stroke, Page, Note inkl. `media`, NoteFactory, Textbox mit `bgColor`, `spans`, `paragraphs` (RichText.kt: TextSpan, ParaFormat, RichTextOps = reine Logik für Auszeichnung), ImageAttachment, AudioAttachment), kein Android-Bezug
│   │       ├── storage/  – PnoteCodec (.pnote ZIP+JSON; Manifest/Seiten Deflate, Vorschau und Medien STORED, da JPEG/PNG/M4A schon komprimiert), NoteRepository (Ordner, .trash; `save` schreibt die neue Datei zuerst, wertet das Löschen der alten Datei nach Ordnerwechsel aus und liefert bei Fehlschlag `PnoteError.Io` ohne die neue Datei zu verlieren; bei doppelter ID gewinnt die jüngste Datei, das nächste `save` räumt auf), BackupPolicy (reine Entscheidungslogik, Intervalle) + BackupStore (`.backup/<noteId>/<zeitstempel>.pnote`, max. 10, Wiederherstellen nur als Kopie; Prune-Fehler stehen in `lastPruneFailures`, die Sicherung gilt trotzdem als gelungen) + FailureStreak (reine Logik: Hinweis erst nach N Fehlschlägen in Folge, nur einmal; der Editor meldet damit dauerhaft scheiternde Sicherungen), FolderPath (Allowlist je Segment; `normalize` bringt Namen nach Unicode-NFC, die Oberfläche normalisiert Eingaben vor der Prüfung, rohe NFD-Namen sind ungültig) + NoteTitles (`sanitize` normalisiert ebenfalls nach NFC), PnoteResult/PnoteError
│   │       ├── editor/   – PageHistory + NoteSession (Autosave-Debounce 5 s, mit Medien 30 s, `debounceMs`; Seitenwechsel, Seite hinzufügen/löschen und onPause/onStop/onDestroy speichern sofort) + PageTransform (Abbildung Seite<->Ansicht; Striche liegen in Seitenkoordinaten, `.pnote` Format-Version 2) + EditorToolState + PenCatalog (reine Logik, JVM-Tests; PenCatalog = einzige Tabelle der Stifttypen); TrackMapper, PageCanvasView (einzige Berührung mit HWEditView), PageBackgroundView/TemplateRenderer, TemplateCatalog (einzige Tabelle der Vorlagen) + TemplateDialog + `templates/` (TemplateGeometry = reine Geometrie, ShapeTemplateRenderer, TemplateThumbnails), TextboxView (Textfeld: Ziehen, Skalieren, Hintergrund) + RichEditText (Eingabefeld auf dem Blatt, Format am Cursor) + TextFormatBar (Leiste über der Tastatur) + RichTextAndroid (Modell <-> Android-Spans) + ImageAttachmentView + CropOverlayView + ImageUtil (Bild einfügen/zuschneiden; EXIF-Orientation -> Drehung/Spiegelung als reine Logik, `ImageAttachmentView.upright` wendet sie beim Import einmal an, ohne Drehung bleiben die Originalbytes) + AudioLogic (reine Audiologik) + PagePreviews + PageSidebar/SidebarLogic (Seitenleiste); EditorActivity (Kopf-/Werkzeug-/Unterleiste mit fester Höhe über der Schreibfläche; Layout `res/layout/activity_editor.xml`, Symbole `res/drawable/ic_*.xml`)
│   │       ├── home/     – Startseite-Logik: HomeModel, Pagination, GridMetrics, DateLabels, PreviewLoader/-Cache/-Renderer, HomeTiles, SelectionState + SelectionActions (Mehrfachauswahl; reine Logik mit JVM-Tests)
│   │       ├── hinote/   – Import von HiNote-Exporten (`.hinote`): HinoteBinParser (PENCILENGINE und Altformat HWEINKBYTE, Big-Endian, strikt) + HinoteContainerReader (ZIP-Allowlist, ZIP-Slip, Größenlimits 16 MB je Eintrag/64 MB gesamt, Ratenlimit nach dem Lesen des Eintrags; bei unbekannter gepackter Größe schützen die Größenlimits) + HinoteHtml (Textmarkup `html` der Textbox -> Text, Spans, Absätze) + HinoteImporter (Mapping nach docs/hinote-bin-format.md Abschnitt 6, `STROKE_WIDTH_FACTOR`, Zielordner `HiNote-Import`) + HinoteImportRunner (Doppelimport über `importSource`, Dedup je `noteId` im Lauf, reine Logik mit JVM-Tests) + HinoteImportPlanner/HinoteFolderScanner (Unterverzeichnis -> Unterordner von `HiNote-Import`, Tiefe 3, keine Symlinks) + HinoteImportFlow (SAF/Documents/hinote/custom, Hintergrund-Thread, Dialoge; `cancel()` in `MainActivity.onDestroy`, Abbruch zwischen den Notizen, Executor in `finally`)
│   │       ├── export/   – PDF-Export: PdfExporter (android.graphics.pdf.PdfDocument, Vektor, 595 pt Breite), PdfLayout (Seitenmaße) + ExportFileNames (Dateinamen; beide reine Logik mit JVM-Tests), ExportLocation (`Documents/PaperNotesPDF`), ExportFlow (Dialoge Format/Ziel, Hintergrund-Thread, Fortschritt, Teilen über FileProvider `cacheDir/export`)
│   │       ├── trash/    – TrashActivity (Papierkorb-Ansicht: wiederherstellen, endgültig löschen) + TrashEntryText/TrashPaging (reine Logik)
│   │       ├── backup/   – BackupsActivity (Sicherungen ansehen, nach Notiz gruppiert, "Wiederherstellen als Kopie", nicht exportiert; Einstellungen: Intervall + "Alle Sicherungen löschen")
│   │       ├── about/    – AboutActivity (Über-Seite, nicht exportiert; Logo `res/drawable-nodpi/ic_launcher_art.png`, Texte in strings.xml unter "Über-Seite") + AboutPolicy (`shouldShowAbout`, reine Logik, JVM-Test)
│   │       ├── sync/     – Nextcloud-Einwegsync (Tablet → Server): SyncPlanner (Reihenfolge MakeFolder, Move, Upload, DeleteFile, DeleteFolder), SyncPolicy (Bestätigung bei Massenlöschung), SyncRunner (Gedächtnis nach jeder Operation, 409-Wiederholung), LocalScanner, SyncStateCodec, SyncPaths (Zielordner nach NFC; Server-Ordner mit zerlegten Umlauten (NFD) werden beim Abgleich ignoriert, nicht umgedeutet), WebDavRemote (OkHttp 5.4.0, MODERN_TLS, keine Weiterleitungen; Upload als Streaming-Body aus der Datei, SHA-1 in eigenem Durchlauf, `MAX_UPLOAD_BYTES` = 64 MB + 1 MB wie `MAX_FILE_BYTES` im Plugin), MultiStatusParser (lehnt DOCTYPE/ENTITY ab), LoginFlow (Login Flow v2), CredentialStore (Keystore AES-GCM, nur Android), SyncSettings/SyncStateFile/SyncEngine/SyncCoordinator (JobScheduler `JOB_SYNC`=4711 und `JOB_DAILY`=4712) + SyncJobService, SyncStatus (reine Statuslogik) + SyncTexts/SyncDialog/SyncSettingsActivity; Hook `SyncCoordinator.onNotesChanged` in `NotesWidgetUpdater.requestUpdate`. Nichts loggen, keine Zugangsdaten ins Repo. Huawei: Hintergrundstart nur mit „Manuell verwalten“ (siehe CHANGELOG 0.4.0)
│   │       ├── widget/   – Launcher-Widget: NotesWidgetProvider (Receiver, goAsync), NotesWidgetUpdater (`requestUpdate` gedrosselt 30 s, von Editor/Startseite/Papierkorb/Sicherungen aufgerufen), WidgetRenderer (RemoteViews, feste Zeilen 1..8, Layout `res/layout/widget_notes.xml`), NewNoteActivity (nicht exportiert), NotesWidgetConfigActivity (exportiert nur für APPWIDGET_CONFIGURE, prüft appWidgetId), NotesWidgetPrefs; Status in der Kopfzeile (nie eine eigene Zeile): WidgetStatus.kt (BatteryMath, NotificationFilter, StatusDisplay, WidgetStatusLogic.headerLayout = Titel/Datum entfallen bei Platzmangel, StatusChangeTracker = nur bei Änderung neu zeichnen), NotificationCountService (NotificationListenerService, zählt nur die Anzahl, liest nie Inhalte, loggt nichts) + NotificationCountStore (nur die Zahl), StatusScheduler (inexakter 15-min-Timer nur bei Bedarf; plant/bricht zusätzlich den Lade-Job ab) + ChargeStatusJobService (JobScheduler `JOB_CHARGE`=4713, `requiresCharging`, nur eingeplant solange NICHT geladen, plant sich nie selbst neu: das macht der nächste Tick) + ChargeJobLogic (reine Entscheidung, JVM-Tests) + StatusReceiver (nicht exportiert; Manifest ohne POWER_CONNECTED/DISCONNECTED, ab API 26 nicht zustellbar), Rahmen `res/drawable/bg_widget_framed.xml` + `bg_list_plain_bottom.xml` (Radius/Rand in `values/dimens.xml`: widget_corner, widget_inset); reine Logik mit JVM-Tests: WidgetConfig, WidgetLogic, WidgetStatus, UpdateThrottle. Provider-XML `res/xml/notes_widget_info.xml` (+ `xml-v31` mit previewLayout), `updatePeriodMillis=0`. Option „Favoriten oben“ (`WidgetConfig.favoritesFirst`, Regeln in `WidgetLogic.select`). **targetSdk 29 beibehalten, kein Manifest-Receiver für POWER_*/SCREEN_*** (Entscheidung 2026-11-09, siehe Abschnitt „Stromregeln“); der 15-min-Alarm bleibt inexakt und nicht aufweckend
│   │       ├── MainActivity.kt – Startseite (Raster/Liste, Suche, Menü ⋮, Auswahlmodus; öffnet beim ersten Start nach Installation/Update die Über-Seite); SettingsActivity.kt – Einstellungen (Speicherort, Papierkorb, Version, Info); NoteSharing.kt – Teilen per FileProvider; EinkDialog.kt – Bestätigungsdialog; NotesLocation.kt – Speicherort `Documents/PaperNotes` (gemeinsamer Speicher, `WRITE_EXTERNAL_STORAGE` + `requestLegacyExternalStorage`, targetSdk 29)
│   └── src/test/java/    – JVM-Tests (JUnit 4, `./gradlew test`)
├── eink-stub/            – Compile-Only-Stub für android.eink.* (Namespace local.papernotes.einkstub)
│   └── src/main/java/    – Stub-Signaturen (wird NIE in die fertige APK gepackt)
└── tools/                – Hilfswerkzeuge: hinote_bin.py (Leser für `.bin`), export_all.py (Einmal-Export aller HiNote-Notizen per adb + uiautomator, Ausgabe nach `tmp/device/exp/all/`, Befehle `run`/`pull`/`list`; Tablet entsperrt, nur lesend), analyze_all.py (Auswertung der Exporte), ui.sh (UI-Dump + Screenshot nach `tmp/device/ui/`); penspike/ (erster Spike: eigenständiges Gradle-Projekt mit `HWEditView`-Tests aus einer Fremd-App, eigene Stubs; gehört nicht zum Haupt-Build, bei Bedarf separat bauen); uber-apk-signer.jar ist git-ignoriert
```

## Build-, Test- und Lint-Befehle

Alle Builds erfordern Java 17 (`openjdk@17` via Homebrew) und das Android SDK der Homebrew-Cask `android-commandlinetools`
(Android Studio ist ebenfalls per Homebrew installiert). `adb` liegt unter `/opt/homebrew/bin/adb`.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export ANDROID_SDK_ROOT=$ANDROID_HOME

# JVM-Tests
./gradlew test

# Debug-APK bauen
./gradlew assembleDebug

# Release-APK bauen (signiert mit keystore.properties; ohne vollständige Signierung bricht der Build ab)
./gradlew assembleRelease

# Lint ausführen
./gradlew lint

# Alles zusammen (Build + Lint)
./gradlew assembleDebug assembleRelease lint
```

## Signieren

### Debug-Schlüssel (wichtig für `adb install -r`)
Debug-APKs signiert Gradle mit `~/.android/debug.keystore` des jeweiligen Rechners. Das Tablet trägt eine App, die mit dem Debug-Schlüssel des
**anderen Entwicklungsrechners** signiert wurde (SHA-256 des Zertifikats `372d988a1959d2391d231202501660d27128de9db0f5da6b5e9a1b9feeca56ce`).
Auf einem Rechner mit anderem Schlüssel scheitert `adb install -r` mit `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Abhilfe: die Datei
`~/.android/debug.keystore` vom anderen Rechner übernehmen (nicht in Git ablegen) oder die App vorher deinstallieren (Notizen in `Documents/PaperNotes` bleiben,
Widgets und App-Einstellungen gehen verloren). Prüfen: `apksigner verify --print-certs <apk>` (im SDK unter `build-tools/36.0.0`).

### Variante A – Gradle mit `keystore.properties` (Standard)
Liegt `keystore.properties` im Projektwurzelverzeichnis (git-ignoriert), signiert `./gradlew assembleRelease` automatisch mit `patch-key.jks`.
Erforderliche Property-Schlüssel in `keystore.properties`:
- `PAPYRUS_STORE_PASSWORD`
- `PAPYRUS_KEY_ALIAS`
- `PAPYRUS_KEY_PASSWORD`
*(Optional: `storeFile=patch-key.jks`)*

**Abbruch bei fehlender Signierung:** `gradle.taskGraph.whenReady` in `app/build.gradle` prüft, ob `assembleRelease`, `bundleRelease` oder `packageRelease` im Task-Graphen liegen und die Release-Signierung fehlt (kein Keystore, fehlende Einträge in `keystore.properties` oder fehlende Keystore-Datei). Dann endet der Build mit „Release-Build abgebrochen: Die Signierung ist nicht vollständig konfiguriert (…)“; die Klammer nennt, was fehlt (nie Passwörter). Abhilfe: `keystore.properties` mit den drei Schlüsseln und `patch-key.jks` (oder `storeFile=<Pfad>`) im Projektwurzelverzeichnis ablegen. Debug-Builds, `test`, `lint`, `lintRelease` und `lintVitalRelease` sind nicht betroffen und laufen auch ohne Schlüssel. Es gibt kein Skript im Repo, das Bauen oder Signieren übernimmt; Variante B (unten) arbeitet mit einem unsignierten APK und kollidiert daher mit der Prüfung: dafür vorübergehend `keystore.properties` anlegen oder Variante A nutzen.

### Variante B – Manuell per `uber-apk-signer.jar`
```bash
java -jar tools/uber-apk-signer.jar \
  --apks app/build/outputs/apk/release/app-release-unsigned.apk \
  --ks patch-key.jks \
  --ksAlias <PAPYRUS_KEY_ALIAS> \
  --ksKeyPass <PAPYRUS_KEY_PASSWORD> \
  --ksPass <PAPYRUS_STORE_PASSWORD> \
  --verbose
```

## Versionsregel

Bei jeder Versionsänderung:
1. `VERSION` (Projektwurzel) mit neuer Versionsnummer (z. B. `0.2.0`) aktualisieren.
2. `CHANGELOG.md` – neuen Eintrag unter `[Unreleased]` bzw. der neuen Version pflegen.
3. `versionCode` in `app/build.gradle` bei Bedarf erhöhen.
4. `VERSION`-Datei ist die zentrale Quelle für `versionName`.
4a. Die Über-Seite (`about/AboutActivity`) existiert: `about_news_title`, `about_news_items` ("Neu in Version") und `about_description` in `values/strings.xml` UND `values-en/strings.xml` an die neue Version anpassen (Teil der Release-Prüfung). Die Seite erscheint nach einem Update mit geänderter `versionName` automatisch einmal (Preference `about_last_seen_version`).
5. Nach dem Release-Build APKs mit Versionsname nach `dist/` kopieren:

```bash
VERSION=$(cat VERSION)
mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk     "dist/PaperNotes-by-Bob-${VERSION}-debug.apk"
cp app/build/outputs/apk/release/app-release.apk "dist/PaperNotes-by-Bob-${VERSION}.apk"
cp app/build/outputs/apk/release/app-release.apk "dist/PaperNotes-by-Bob-${VERSION}-release.apk"
```

## Screenshots und Geräte-Debugging

- `adb` liegt unter `/opt/homebrew/bin/adb`.
- Vor Installation immer mit dem Nutzer abstimmen (nicht selbstständig ungefragt installieren).
- **Screenshots klein halten:** Niemals unkomprimierte PNG-Screenshots direkt mit `read_file` laden. Verkleinern und als JPEG speichern:

```bash
mkdir -p .bob/tmp
/opt/homebrew/bin/adb exec-out screencap -p > .bob/tmp/shot.png \
  && sips -Z 800 .bob/tmp/shot.png --setProperty format jpeg --setProperty formatOptions 60 --out .bob/tmp/shot.jpg \
  && rm .bob/tmp/shot.png
```

## Sicherheitsregeln

1. **Keine Secrets im Code oder Git:** Passwörter, Keystores (`*.jks`, `keystore.properties`, `local.properties`) NIEMALS committen, in Logs ausgeben oder in Markdown-Dateien schreiben.
2. **Pfadsicherheit (Path Traversal):** Alle Dateipfade aus Notiz-Paketen (.pnote / ZIP) müssen kanonisiert (`canonicalPath`) und auf das Zielverzeichnis geprüft werden. Keine Pfade mit `..` zulassen.
3. **Größenlimits:** Beim Entpacken von ZIPs/Dateien strikte Größenbegrenzungen und Kompressionsraten-Prüfungen einhalten (Schutz vor Zip-Bombs).
4. **Compile-Only-Integrität:** Der `:eink-stub` darf nie in die APK gelangen (`compileOnly`). Nach Builds mit `unzip -l` prüfen, dass kein `android/eink` in der APK enthalten ist.

## Arbeitsregeln für KI-Agenten

- **Gradle-Ausführung:** Gradle-Befehle dürfen NUR im Hauptagenten / aktuellen Subtask ausgeführt werden, niemals parallel aus Subagents heraus.
- **Sequentielle Datei-Edits:** Mehrere Änderungen an derselben Datei immer nacheinander durchführen, um Überschreibungen zu vermeiden.
- **Ausgaben kompakt halten:** Logs mit `| tail -40` kürzen, keine riesigen Ausgaben in den Chat spülen.
- **Zweisprachig (Deutsch + Englisch):** Die App muss vollständig auf Deutsch und Englisch laufen. Jeder sichtbare Text (Beschriftungen, Dialoge, Menüs, Fehlermeldungen, `contentDescription`, Notiztitel-Vorgaben wie "Notiz …" und die in Seitenvorlagen gezeichneten Beschriftungen, z. B. "Fertig", "Mo") kommt aus `res/values/strings.xml` (Deutsch, Standard) UND `res/values-en/strings.xml` (Englisch), niemals fest im Code. Neue Strings immer in beiden Dateien anlegen; Datums- und Zahlenformate über das Gerätegebietsschema (`Locale`) formatieren. Vor Abschluss prüfen, dass `lint` keine `MissingTranslation`-Funde meldet.

## Dokumentation

- **`ReleasePlan.md`** ist der einzige Plan (offene Aufgaben bis 1.0.0, spätere Erweiterungen). Bei jeder abgeschlossenen Aufgabe dort den Status nachziehen.
- `AGENT.md` (diese Datei) enthält Technik und Regeln, keinen Statusbericht.
- Exportierte HiNote-Dateien und Gerätedateien (`tmp/device/…`, `appBackup/`) sind git-ignoriert und nur lokale Referenz. Das Wissen über das Format stammt allein aus der Untersuchung dieser Exportdateien; Huawei-Code wird weder kopiert noch eingebunden.

## Entwicklungsphase bis Version 1.0.0

- Bis der Nutzer 1.0.0 freigibt, läuft die App nur auf dem Tablet des Nutzers; produktiv nutzt er weiter HiNote. Es gibt nur Test- und Importnotizen.
- **Keine Rücksicht auf alte `.pnote`-Fassungen**: Das Format darf sich ohne Migration ändern. Vor 1.0.0 wird es eingefroren, am Ende steht ein finaler HiNote-Import.
- Nutzerdaten (HiNote, Notizen in `Documents/PaperNotes`) nie ändern oder löschen; nur eigene Testnotizen. Standard-Launcher inkOS und das Widget dort nicht anfassen. Installationen auf dem Tablet (`adb install -r`; bei mehreren Geräten vorher `ANDROID_SERIAL` setzen), kein Deinstallieren. Nichts pushen.

## Dateiformat `.pnote`

ZIP mit `manifest.json` (Version, Titel, Ordnerpfad, Zeiten, UUID, Favorit, Fixiert, Seitenreihenfolge), `preview.jpg` (360 px, max. 2 MB, direkt hinter dem Manifest), `pages/<uuid>.json` (Vorlage, Größe, Striche [Typ, Breite, Farbe, Punkte x/y/Zeit/Druck], Textboxen [optional `spans`/`paras`: Arrays aus Zahlen-Quadrupeln, beim Lesen normalisiert], Bilder, Audios, Lesezeichen) und `media/<uuid>.<ext>` (Bilder, AAC/M4A, max. 16 MB je Datei). Vorschau und Medien liegen unkomprimiert (ZIP STORED) im Paket, Manifest und Seiten-JSON mit Deflate; beide Arten lesen alle Leser (App `ZipInputStream`, PHP `ZipArchive`), die Format-Version bleibt 2. Format-Version 2 (`PnoteCodec.FORMAT_VERSION`); Striche liegen in Seitenkoordinaten (`PageTransform`). Beim Lesen: Größenlimits, keine Pfade mit `..`, Versionsprüfung. Schreiben atomar. **Format eingefroren (B2, Entscheidung 2026-11-11): Version 2 ist das Format von 1.0.0.** Vor dem Release wird alles gelöscht und neu importiert, es gibt nie `.pnote`-Dateien aus der Zeit davor. Ab 1.0.0 muss jede künftige App-Version Version-2-Dateien lesen können; Erweiterungen nur als optionale Felder (fehlt das Feld, gilt der bisherige Wert), die Versionsnummer wird nicht angehoben, solange das möglich ist; das Plugin lehnt höhere Versionen ab. **Jede Formatänderung betrifft auch das Nextcloud-Plugin (PHP-Parser, Viewer, PDF) und die Sync-Logik** und braucht Tests dort.

## Nextcloud-Plugin (`nextcloud-app/papernotes`)

PHP-App (Nextcloud 27 bis 36, auf NC 35.0.2 verifiziert): Dateiaktion für `.pnote`, Canvas-Viewer (alle Vorlagen, Zoom/Pan, Seitenleiste, Lesezeichen), Vektor-PDF in reinem PHP, SabreDAV-Plugin liefert den Notiztitel als `displayname`. Parser mit Zip-Bomben-Schutz (64 MB gesamt, 16 MB je Eintrag, 2000 Einträge, nur erlaubte Eintragsnamen, UUID-Prüfung). Mit B1 (Version bleibt bis zur finalen Version 1.0.0): Textboxen (Rich-Text), Bilder und Aufnahmen in Parser, Viewer und PDF (`PdfTextRenderer.php` mit Helvetica-Breiten, Bilder als JPEG per GD, Aufnahmen werden nie gezeichnet, nur optional als Dateianhänge der Seite über `?audio=1`; der Viewer fragt dazu und zeigt die Player unter der Seite), Medien-Endpunkt `/api/notes/{id}/media/{name}`. Der Parser liefert Medien-Bytes nur mit `withMedia=true`, nie ins Browser-JSON. Fehlermeldungen des Parsers enthalten nie Inhalte aus der Datei (Eintragsnamen, UUIDs); der `NoteController` loggt Details über `LoggerInterface` und gibt dem Client nur feste Meldungen, der Viewer setzt Fehlertext per `textContent`. Zugriff nur auf Dateien im Dateibaum des angemeldeten Nutzers (`getById`; angenommene Benutzer-/Gruppen-/Team-Freigaben gehören dazu, öffentliche Links ohne Anmeldung nicht). Vorschau und Medien streamen die Datei begrenzt (`PnoteParserService::MAX_FILE_BYTES`) in eine temporäre Datei, kein `getContent()`. Der Viewer ist zweisprachig: Texttabelle `VIEWER_TEXTS` (de/en) in `papernotes-viewer.js`, Sprache aus `OC.getLanguage()`, neue Texte immer in beiden Sprachen (der Test prüft das). Tests: `php nextcloud-app/papernotes/tests/run_tests.php` (98 Tests). Paket für das Update über die Nextcloud-Oberfläche: `COPYFILE_DISABLE=1 tar --exclude=tests -czf dist/papernotes-nextcloud-app-<Version>.tar.gz -C nextcloud-app papernotes` (Version aus `appinfo/info.xml`; bis zur finalen Version bleibt sie 1.0.0, nicht hochzählen). App-seitig zeichnet `editor/PageContentPainter.kt` Bilder und Textboxen für Kachelvorschau, Seitenvorschau und PDF. Aufnahmen: Symbol `ic_speaker` mit Zahl in Übersicht (`HomeTiles.audioBadge`, aus `NoteSummary.audioCount`/Manifest `audioCount`) und Seitenleiste; Export fragt in `ExportFlow` „Aufnahmen mitnehmen?“ (nur bei vorhandenen Aufnahmen) und hängt sie per `PdfAttachments` (inkrementelles PDF-Update, Skia-Aufbau mit klassischer xref-Tabelle vorausgesetzt) als Dateianhang an die Seite; nur bei Fehlschlag entstehen eigene Dateien neben dem PDF. Neue Vorlagen in der App müssen im Plugin (Viewer-JS und `PdfExportService.php`) nachgezogen werden. Installation siehe `nextcloud-app/README.md`.

## Gerätewissen: E-Ink-Stiftschicht (`HWEditView`)

Belegt durch Spikes auf dem Tablet (Android 10, EMUI 12):
- `android.eink.view.edit.HWEditView` läuft in einer normalen Fremd-App ohne Root und ohne `<uses-library>` (Klassen kommen aus dem Boot-Classpath), `targetSdk 29`. Manifest-Meta `hw_eink.app.refresh.mode=3` wie in HiNote. Die Latenz ist laut Nutzer wie in HiNote; xnotes und Saber waren deutlich träger, daher keine Fremd-Basis.
- `HWEditView` liegt transparent über einer eigenen View (Vorlage). `getTrackList`/`setTrackList` speichern/laden Striche verlustfrei.
- **`setTrackList()` und `clear()` leeren den Undo-Verlauf** → eigener Verlauf pro Seite (`PageHistory`). Kein Zoom, kein Scrollen im Framework → die App rechnet Tracks um (`PageTransform`, `TrackMapper`).
- **Radierer schnell nur mit gebundenem Weiß** (`OnEraserPrepareListener` und `FBPicture.bindColor(WHITE)`); dann fehlt der Radierkreis. Das ist bewusst so, der Radierkreis entfällt (ein Overlay-Versuch war langsam und wurde zurückgenommen).
- `HWEditView` ist eine `SurfaceView` mit `setZOrderOnTop`; Zeichnen im Container (`onDraw`/`dispatchDraw`) liegt dahinter. Dialoge und Leisten über der Schreibfläche: Muster **`suspendWriting` plus `setWriteExclusion`** (Stift sperren, solange ein Overlay offen ist). Gilt für jeden neuen Dialog im Editor.
- **Finger-Gesten:** `HWEditView` reagiert nur auf den Stift. Damit Zwei-Finger-Zoom/Pan ankommt, muss `PageCanvasView.dispatchTouchEvent` bei Finger-`ACTION_DOWN` `true` zurückgeben, sonst filtert Android alle Folge-Events.
- Stifttyp-Zuordnung (`setAhwPenType` 4/2/3/6 → Füllfeder, Kugelschreiber, Bleistift, Marker) ist aus Beobachtungen abgeleitet und nur für den Kugelschreiber belegt (`PenCatalog`); Marker-Transparenz: Alpha 0x80 in der Stiftfarbe, am Gerät bestätigt.
- Handschrifterkennung: Die Huawei-Engine lehnt fremde Apps ab (`The app isn't in white list`). **Nicht umgehen** (kein Fälschen von Paketname oder Signatur). Google Play Services fehlen auf dem Gerät.
- Der Stub `:eink-stub` bleibt `compileOnly`; nach Builds prüfen, dass `unzip -l` keinen `android/eink`-Eintrag in der APK zeigt.

## Huawei-Besonderheiten

- Hintergrundstart von Job-Diensten (Sync) nur, wenn die App im Tablet-Manager › Akku › App-Start auf „Manuell verwalten“ steht (sonst „Service starting has been prevented by iaware“).
- HiNote-Originaldaten (`/data/data/com.huawei.hinote`) sind ohne Root nicht lesbar; die Daten kommen nur über den HiNote-Export (`.hinote` nach `Documents/hinote/custom/`, pro Notiz) heraus. Der Import liest sie (`hinote/`, Format: `docs/hinote-bin-format.md`).
- Das Tablet: HMW-W09, Android 10, kein Root. Die Seriennummer steht nicht im Repo; die Skripte lesen `ANDROID_SERIAL` oder nehmen das einzige verbundene Gerät.

## Stromregeln (haben Vorrang)

Kein Dauerdienst, keine Wake-Locks, nur inexakte, nicht aufweckende Alarme bzw. `JobScheduler`-Jobs, E-Ink nur bei Änderung neu zeichnen. `targetSdk` bleibt 29 (die Einschränkung impliziter Broadcasts gilt ab 26; ein Wert unter 26 kommt aus Sicherheitsgründen nicht infrage). `POWER_CONNECTED/DISCONNECTED` und `SCREEN_ON` nicht im Manifest. Messung Widget-Timer (15 min): 0 Wakeups, vernachlässigbar.

## Weitere Arbeitsregeln

- **Subagenten** nur für klar abgegrenzte Aufgaben mit kleinem Kontext; Zwischenergebnisse sofort in Dateien schreiben. Gradle nie parallel.
- Behauptungen über das Gerät erst eintragen, wenn sie am Tablet geprüft sind; ungeprüftes als Annahme kennzeichnen.
- Keine Fremdbibliotheken ohne Pflege- und Lizenzprüfung (letzter Commit < 6 Monate, Apache/MIT/BSD bevorzugt). Aktuell: OkHttp 5.4.0 (Sync), Testabhängigkeit `mockwebserver3`; PDF über Android-eigenes `PdfDocument`.
- Vor jedem Abschluss: `./gradlew test assembleDebug lint`, `git status` (keine Secrets, APKs, `tmp`-Daten).
