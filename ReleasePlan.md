# PaperNotes by BOB: Release-Plan bis Version 1.0.0

Stand: 2026-11-10, Version 0.5.0 plus Änderungen danach (siehe `CHANGELOG.md`, Abschnitt Unreleased). Dies ist der einzige Plan. Technik, Konventionen und Gerätewissen stehen in `AGENT.md`, das HiNote-Dateiformat in `docs/hinote-bin-format.md`, der gebaute Funktionsumfang im `CHANGELOG.md`.

## 1. Ziel und Rahmen

Eigene Notiz-App für das Huawei Paper (HMW-W09, Android 10, E-Ink, kein Root) als Ersatz für HiNote: flüssiger Stift über `HWEditView`, Ordner und Notizen, Text, Bild, Audio, PDF-Export, Nextcloud-Sync mit Viewer-Plugin.

**Spielregeln bis 1.0.0 (Entscheidung des Nutzers, 2026-11-09):**
- Bis der Nutzer die Version 1.0.0 freigibt, existiert die App nur auf dem Tablet des Nutzers. Der Nutzer arbeitet produktiv weiter mit **HiNote**.
- Es gibt nur Testnotizen und Notizen aus Testimporten. **Auf ältere `.pnote`-Fassungen wird keine Rücksicht genommen.** Das Format darf sich ohne Migration ändern; alte Testnotizen dürfen unbrauchbar werden und werden bei Bedarf gelöscht und neu importiert.
- **Vor 1.0.0 wird das Format eingefroren** (B2). Ab 1.0.0 gilt Abwärtskompatibilität.
- **Am Ende steht ein finaler HiNote-Import** aller Notizen (die bis dahin entstandenen Testimporte werden vorher verworfen).
- Festgelegte Rahmenentscheidungen: Kotlin, klassische Views, kein Compose; `targetSdk 29`; Speicherort `Documents/PaperNotes`; GPL-3.0-or-later; Deutsch und Englisch; Stromsparen hat Vorrang (kein Dauerdienst, keine Wake-Locks); Schrifterkennung der Huawei-Engine wird nicht umgangen.

## 2. Stand

Alle bisherigen Aufgaben sind umgesetzt.

Bekannte Fehler: keine offenen. Bewusst nicht vorhanden: eigener Radierkreis (Versuch war langsam und wurde zurückgenommen, **entfällt**)

## 3. Offene Aufgaben bis 1.0.0

Reihenfolge: B1 (Test im Plugin) → B2 → C1 → C2 → C3.

| Nr. | Aufgabe | Hinweise |
|---|---|---|
| B1 | **Textbox, Audio und Bild im Nextcloud-Plugin prüfen** (umgesetzt 2026-11-10, in der App am Gerät bestätigt; **Test in Nextcloud ausstehend**) | Umgesetzt: Textboxen (mit Hintergrund und Auszeichnungen), Bilder und Audio in Kachelvorschau, Seitenvorschau, PDF und Plugin. Audio: Symbol mit Zahl (Übersicht, Seitenleiste); im PDF (App und Plugin) als Option beim Export als Dateianhang an der Seite mit dem Namen der Aufnahme. Plugin: `PnoteParserService.php`, `PdfExportService.php`, `papernotes-viewer.js`; Medien liegen als `media/<uuid>.<ext>` im ZIP. 58 PHP-Tests grün. Plugin-Version bleibt bis zur finalen Version 1.0.0; Paket `dist/papernotes-nextcloud-app-1.0.0.tar.gz` (der auf NC 35.0.2 verifizierte Stand ist **vor** B1). **Offen:** Paket neu bauen (Befehl in `AGENT.md`, Abschnitt Nextcloud-Plugin), auf dem Server installieren (`nextcloud-app/README.md`, „Update über die Nextcloud-Oberfläche“) und prüfen: Viewer zeigt Textboxen, Bilder und Player; „PDF Download“ mit und ohne Aufnahmen (`?audio=1`); Notiz vom Tablet per Sync öffnen |
| B2 | **Format einfrieren** | Format-Version festlegen und `PnoteCodec.FORMAT_VERSION` sowie `PnoteParserService::FORMAT_VERSION` angleichen (das Plugin lehnt höhere Versionen ab). **Entscheidung offen:** auf 3 anheben (empfohlen, dann sind alle Testnotizen mit alter Version sofort als „vor dem Freeze“ erkennbar) oder bei 2 bleiben (alle Erweiterungen sind optionale Felder: `spans`, `paras`, `bgColor`, `audioCount`). Mitziehen: `PnoteCodecTest` (prüft heute `assertEquals(2, …)`), `tests/run_tests.php`, `AGENT.md` (Abschnitt „Dateiformat“ und Verzeichnisbaum nennen „Format-Version 2“), `docs/`, Sync-Logik prüfen. Danach Plugin-Paket neu bauen. Vor dem Freeze alle Felder des Formats noch einmal gegen die Funktionen prüfen (Textbox, Bild, Audio, Lesezeichen, Vorlagen), denn ab 1.0.0 gilt Abwärtskompatibilität |
| C1 | **Finaler HiNote-Import** aller Notizen auf dem Tablet | Vorher Testimporte und Testnotizen entfernen, HiNote-Export erneut ziehen. Der Export läuft mit `tools/export_all.py run` (adb + uiautomator, Prototyp: bisher 11 von 33 Notizen belegt). **Noch nicht abgedeckt:** Notizen im Wurzelverzeichnis, verschlossene Notizen, Ordnerkarten-Menü; vorher gegen das aktuelle HiNote prüfen, notfalls manuell exportieren. Der Import über die Dateiauswahl (SAF) kennt keine Ordner und legt alles in `HiNote-Import` ab; für die Ordnerstruktur den Import aus `Documents/hinote/custom` nutzen. **Sichtprüfung gegen HiNote**, besonders: Seitenhöhe (Verlängerung um 192 px ist bestätigt), Textboxen und Bilder, und die Vorlagen `base2`, `base3`, `handwrite1`, `handwrite2`, `handwrite4` (Zuordnung nur nach dem Namen angenommen, im Bestand nicht belegt; `base2` und `base3` zeigen beide auf `grid`). Pinseltyp 11→3 ist bewusst so akzeptiert |
| C2 | **Release 1.0.0** | `VERSION`; `versionCode` (heute 4, in `app/build.gradle`); `CHANGELOG.md` (Abschnitt Unreleased nach `[1.0.0]` mit Datum verschieben); Über-Seite („Neu in Version“, Beschreibung, de/en: `about_news_title`, `about_news_items`, `about_description`); README (Stand, Verweis auf diesen Plan); Release-Signatur klären (Debug- und Release-Signatur sind nicht kompatibel, `keystore.properties` und `patch-key.jks` müssen vorhanden sein, siehe `AGENT.md`, „Signieren“); `dist/` (APKs mit Versionsname, Plugin-Paket); Prüfungen: `./gradlew test`, `lint` ohne `MissingTranslation`, `unzip -l` der APK ohne `android/eink`; `php nextcloud-app/papernotes/tests/run_tests.php` |
| C3 | **Freigabe von 1.0.0 durch den Nutzer** | Danach gilt Abwärtskompatibilität des Formats |


## 5. Spätere Erweiterungen (nach 1.0.0, nicht planen, nur merken)

### KI-Anbindung (Entscheidungen offen)

- **Audio: KI-Zusammenfassung als Textbox.** Aus einer Aufnahme eine Text-Zusammenfassung erzeugen und als Textbox auf der Seite einfügen. Vor dem Bau gemeinsam festlegen:
  1. Wo läuft die Spracherkennung? (Huawei-Engine gesperrt; Google-Dienste fehlen; Optionen: Server/Self-Hosted, Nextcloud-Dienst)
  2. Welches Sprachmodell?
  3. Datenschutz: Audio verlässt das Tablet nur nach ausdrücklicher Aktion des Nutzers, nur über HTTPS mit Zertifikatsprüfung, keine Zugangsdaten im Code (Android Keystore wie beim Sync).
  4. Bedienung: Knopf in der Audioleiste, Fortschritt, Ergebnis als Textbox.
- **Handschrift zu Text („Schrift zu Text“)** über dieselbe KI-Anbindung, erst nach der Audio-Zusammenfassung; als Eintrag im Lasso-Menü und für die Suche über Handschrift; ggf. serverseitig im Nextcloud-Plugin.

### Weitere Ideen

- Lasso-Menü: „Teilen“ der Auswahl.
- Audio: Markierungen/Lesezeichen in der Tonspur, Wiedergabegeschwindigkeit, Wellenform.
- Textbox: Kopieren/Ausschneiden im Menü; Farbwahl in der Formatleiste; Bild: Ausschneiden, freier Zuschnitt.
- Seiten in der Seitenleiste verschieben; Ordner sortierbar.
- Bild-Export, PDF mit Seitenauswahl.
- Tags, Lesemodus ohne Stift, Verschließen (Schloss) von Notizen.
- Widget: einstellbares Intervall (15/30/60 min) für den Status-Alarm.
