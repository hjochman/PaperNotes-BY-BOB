# HiNote-Export: Container `.hinote` und Stiftdaten `.bin` (PENCILENGINE)

Stand: Analyse von HiNote 12.4.3.395 (Format-Kennung der JSON-Dateien `hinote_1.0.4`) anhand von zwei Beständen:
**(a)** 14 Exporte aus dem ersten Test (19 Seiten, 4607 Striche, 78 119 Punkte) und **(b)** dem Einmal-Export des
gesamten Bestands vom 2026-11-07: **34 Notizen** (die HiNote-Kopfzeile zeigt „34 Notizen“; in den Plänen stand 33),
264 Seiten, 260 `.bin` (203 `PENCILENGINE`, 57 `HWEINKBYTE`), 71 793 + 18 810 Striche. Die Befunde aus (b) sind in den
Abschnitten mit **„Gesamtbestand“** gekennzeichnet. Referenzimplementierung: [`tools/hinote_bin.py`](../tools/hinote_bin.py)
(liest `PENCILENGINE` und das Altformat `HWEINKBYTE`). Exportskript: `tmp/export_all.py`.

Die Beschreibung ist in eigenen Worten allein aus der Untersuchung exportierter HiNote-Dateien (Hexdumps, entpackte
JSON-Dateien und Vorschaubilder) abgeleitet. Es wurde kein Huawei-Code kopiert oder eingebunden.

**Kennzeichnung:** *belegt* = durch die Beispieldateien (14 Erstproben, 34 Notizen im Gesamtbestand) (Parser läuft ohne Restbytes, Rendering deckt sich mit der
Vorschau). *Annahme* = nur aus Feldnamen, Standardwerten oder einer einzelnen Beobachtung
abgeleitet, durch die Proben nicht überprüfbar (auch der Gesamtbestand enthält fast nur schwarze Kugelschreiberstriche, siehe 2.7).

Alle Zahlen sind **Big-Endian** (wie `java.io.DataInputStream`). `int` = 4 Byte vorzeichenbehaftet, `u16` = 2 Byte
vorzeichenlos, `i64` = 8 Byte, `f32` = IEEE-754 Single, `bool` = 1 Byte.

---

## 1. Container `.hinote` (ZIP)

| Eintrag | Inhalt | Status |
|---|---|---|
| `<noteId>.jhinote` | gzip-komprimiertes JSON, Wurzel `customNoteContent` (Notiz-Metadaten) + `fileList` | belegt |
| `pages/<pageId>.jhinote` | gzip-JSON, Wurzel `customNotePageContent` (Seite) + `fileList` | belegt |
| `files/<pageId>.bin` | Stiftstriche der Seite (Abschnitt 2). **Fehlt bei leeren Seiten** (Beispiel: Seite 2 von „API Gateway“) | belegt |
| `files/<pageId>.jpg` | Seitenvorschau, **inklusive Hintergrundvorlage und Strichen**, 903 × 1080 px (in allen 19 Seiten) | belegt |
| `custom_md.jhinote` | gzip-JSON `customMdContents[]` mit `fileMdStr` = SHA-256 des Dateiinhalts und `fileNameMdStr` = SHA-256 des Dateinamens (ohne `files/`) | belegt (alle Einträge nachgerechnet) |
| `files/`, `pages/` | leere Verzeichniseinträge | belegt |

Seiten-ID und Dateiname stimmen überein (`pages/<id>.jhinote` enthält `"id": "<id>"`). Die Seitenreihenfolge steht in
`pageNumber` (ab 1), nicht im ZIP.

### 1.1 Notiz-JSON (`customNoteContent`)

| Feld | Beispiel | Bedeutung | Import |
|---|---|---|---|
| `id` | `4ff509b4…` | Notiz-ID (32 Hex) | als Quelle merken, neue UUID vergeben |
| `noteTitle` | `"- Alle Pipeline"` | Titel | ja → `Note.title` |
| `background` | `base1`, `base6` | Standardvorlage der Notiz | nur Fallback für Seiten |
| `createTime`, `modifiedTime` | **immer 0** | im Export nicht befüllt | nein; stattdessen aus den Seiten ableiten (Min/Max) |
| `pageRatio` | `0.8357143` | Breite/Höhe der Seite (= 1404/1680) | ja, bestimmt die Seitenhöhe |
| `pageColor` | `-1` | Papierfarbe (Annahme: -1 = Standard/weiß) | nein |
| `pageOrientation` | `0` | Annahme: 0 = Hochformat | nein (in allen Proben 0) |
| `noteType` | `100` | Annahme: Notiztyp „E-Ink-Handschrift“ | nein |
| `isFavorite`, `isTop` | `0` | Favorit / angeheftet | ja (→ `favorite`, `pinned`), in Proben 0 |
| `hasCover`, `noteIcon` | `0`, `""` | Deckblatt | nein |
| `data1` | `{"originDeviceType":"eink","isContentCover":"0"}` | JSON-String | nein |
| `attachment`, `outLineAttachment`, `recordItemEntities` | `[]` | Anhänge, Gliederung, Audioaufnahmen | in den Proben immer leer → nicht prüfbar |
| `cloudSyncState`, `deleteTag`, `deleteTime`, `extendFields` | – | Sync-/Papierkorbstatus, `hinote_1.0.4` | nein |

**Ordner/Kategorie steht NICHT im Export** (belegt für alle 34 Notizen: kein Feld nennt den Ordner). Der Einmal-Export
hält den Ordner extern fest: `tmp/device/exp/all/<Ordner>/…` und `tmp/device/exp/all/index.json` (`file`, `title`,
`folder`, `noteId`, `deviceFile`; Wurzelnotizen haben `folder: ""` und liegen in `_Wurzel/`).

**Gesamtbestand (34 Notizen):** Wurzel 4, Ordner `BIT` 19, `BR` 5, `Open Shift` 3, `Shibary` 2, `Lesenotizen` 1.
Feldwerte über alle 34: `isFavorite`=0, `isTop`=0, `hasCover`=0, `deleteTag`=0, `createTime`=`modifiedTime`=0, `pageColor`=-1,
`pageOrientation`=0, `data1`=`{"originDeviceType":"eink","isContentCover":"0"}`; `attachment`, `outLineAttachment`,
`recordItemEntities` immer leer. Neu: `noteType` ist **100** (29 Notizen) oder **0** (5 Notizen: `BIT-2022`,
`BR-Seminar Waldsrode 2022`, `RHTE 2023` (Open Shift), `Sibari Advent`, `shibari` – die ältesten Notizen, Annahme:
Altbestand von einem früheren Gerät); `noteIcon` (Deckblatt-Symbol) kommt vor als `base_1`, `base_3`, `base_4`, `base_11`
(je 1 Notiz), sonst leer/fehlend; nicht relevant für den Import. Die `background` der Notiz weicht in 6 Notizen von der
der Seiten ab (z. B. Notiz `base1`, Seiten `base6`) – **die Seite gilt**, wie der Importer es bereits macht.

### 1.2 Seiten-JSON (`customNotePageContent`)

| Feld | Beispiel | Bedeutung | Import |
|---|---|---|---|
| `id`, `notesId` | 32 Hex | Seiten-/Notiz-ID | Zuordnung |
| `pageNumber` | `1`, `2` | Reihenfolge (ab 1) | ja |
| `background` | `base1`, `base6`, `plan2` | Vorlage der Seite | ja, über Tabelle 1.3 → `Page.template` |
| `pageRatio` | `0.8357143` | wie oben | ja |
| `createTime`, `modifiedTime` | ms seit 1970 | Erstell-/Änderungszeit der Seite | ja (für Notizzeit) |
| `pageBookMarks` | `[]` | Lesezeichen (Entität `NotePageBookMarkEntity`: `bookMarkName`, `noteId`, …) | **in allen 264 Seiten leer** (Gesamtbestand) → Struktur weiter ungeprüft; Annahme: nicht-leer = Seite hat Lesezeichen → `bookmarked=true` |
| `lastPageTag` | `0`/`1` | Annahme: 1 = zuletzt geöffnete Seite (genau eine Seite je Notiz hat 1) | nein |
| `pageElement` | `[]` | Textboxen/Bilder, **Aufbau belegt, siehe 1.2.1** | im Gesamtbestand auf **9 Seiten nicht leer** (11 Bilder, 2 Textboxen) |
| `attachment[]` | siehe unten | Verweis auf die `.bin` | nur zur Zuordnung |
| `bkgAttachmentIndex`, `pageType`, `pageColor`, `pageOrientation`, `isDelete`, `cloudSyncState`, `thumbnail` | – | intern | nein |
| `fileList[]` | `{hash, name}` | SHA-256 + Name der zur Seite gehörenden Dateien (`.bin`, `.jpg`) | zur Integritätsprüfung |

`attachment[0]` hat `attachType: 0`, `data1: {"synDataTpye":"memopage"}` und `filePath` auf den privaten Pfad
`/data/data/com.huawei.hinote/files/hwFile/<pageId>.bin` (für den Import wertlos; maßgeblich ist `files/<pageId>.bin`).
Bei leeren Seiten ist `attachment` leer und `fileList` nennt nur die `.jpg`.

**Gesamtbestand:** 260 von 264 Seiten haben eine `.bin` (233 mit genau einem `attachment` vom Typ 0, dazu 27 mit
zusätzlichem `attachment` vom Typ 1); 4 Seiten sind leer (kein `attachment`, nur `.jpg`). Alle Seiten haben
`pageType`=1, `bkgAttachmentIndex`=0, `isDelete`=0, `pageColor`=-1, `pageOrientation`=0; je Notiz genau eine Seite mit
`lastPageTag`=1. `createTime`/`modifiedTime` der Seiten sind in allen 264 Seiten ≠ 0. `pageRatio` ist **0,8357143**
(144 Seiten, = 1404/1680) oder **0,9616438** (120 Seiten, = 1404/1460; die `.bin` hat dann 1000 × 1039, gerundet
1404 × 1459; Notizen `shibari`, `Sibari Advent`, `BIT-2022`, `BR-Seminar Waldsrode 2022`).

*`attachment` Typ 1 (neu):* `attachType: 1`, `contentText` = von HiNote erkannter Text der Seite (Schrifterkennung;
Stichprobe `BIT-2022` Seite 7: „Jeup-Pipeline Library …“ gibt die Handschrift sinngemäß wieder), `filePath` fehlt. Bei
den 25 Seiten mit Typ-1-Anhang und Altformat-`.bin` heißt die Vorschau **`files/<pageId>.jpeg`** statt `.jpg` (1404 × 1354 in der Stichprobe, mit
Hintergrund und Handschrift); bei 2 weiteren Seiten bleibt es bei `.jpg`. `.jpeg` ist also ebenfalls ein erlaubter
Vorschau-Name. Für die Suche in der neuen App wäre `contentText` verwendbar (Annahme: Erkennungstext ist unvollständig/fehlerhaft).

#### 1.2.1 `pageElement[]` (Gesamtbestand, belegt)

| Feld | Bild (`elementType` 1, 11 Stück) | Text (`elementType` 0, 2 Stück) |
|---|---|---|
| `positionX`, `positionY` | linke obere Ecke als Bruchteil der Seitenbreite bzw. -höhe (Werte −0,009 … 0,60; leicht negativ möglich) | wie Bild (0,02 … 0,32 / 0,18 … 0,25) |
| `width`, `height` | Bruchteile (Annahme: von Breite bzw. Höhe der Seite; Breiten 0,59 … 1,00) | 0,38 / 0,067 |
| `angle` | Drehung, vermutlich Bogenmaß (−0,78 … 0,78), 0 bei zwei Bildern | 0 |
| `scale` | 1,0 | 1,0 |
| `positionZ` | Ebenenreihenfolge (große ganze Zahl, auch negativ) | negativ |
| `filePath` | privater Pfad `…/files/image/<name>.jpg\|png`; **die Bilddatei liegt im ZIP als `files/<name>.jpg` bzw. `.png`** (alle 11 vorhanden, Namen auch in `fileList`) | – |
| `contentText`, `html` | – | Klartext und `<note><element type="TEXT"><font color ="#ff000000">Text</font></element></note>`. Der Import (`HinoteHtml`) liest zusätzlich `<b>`, `<i>`, `<u>`, `<hw_font size ="1.25">`, `<div align="center\|right">`, `<tab_span style ="type:N,level:N,number:N">`, `<br>` (aus den Feldnamen und der Struktur des `html`-Felds abgeleitet, **ohne Beispiel im Bestand**) |
| `data1` | `{"sourceDpi":"0","type":"0","stretchSplits":"null"}` | `sourceDpi` „320“ |
| `createTime`, `modifiedTime`, `isDelete` | ms; `isDelete`=0 | ebenso |

Vorkommen: Bilder auf `BIT-2022` S. 9, `BR-Seminar Waldsrode 2022` S. 9 und `RHTE 2023` (BIT) S. 2, 3, 4, 5, 6, 8 (2×), 9
(9 JPG, 2 PNG); Textboxen nur in `Das ist ein Tes` (Ordner Lesenotizen, Vorlage `plan6`, Texte „Remarkable“ und „Dies ist
ein Test“; die Vorschau-JPG zeigt sie eingebrannt). Die Vorschau-JPG enthält Elemente also mit; ohne Elementunterstützung geht
nur die Vorschau als Ersatz. Ist ein Bild gedreht (`angle`), ist die Drehung um die Mitte Annahme.

### 1.3 Vorlagen: HiNote-`background` → `TemplateCatalog`-ID

HiNote-IDs stammen aus dem Feld `background` der Exporte, die deutschen Anzeigenamen aus der Oberfläche von HiNote. In den Proben
kommen nur `base1`, `base6`, `plan2`, `plan6` vor; die übrigen Zuordnungen sind **Annahme** (nach deutschem Anzeigenamen).

| HiNote-ID | Anzeigename (de) | `TemplateCatalog`-ID | Status |
|---|---|---|---|
| `base1` | Leer | `blank` | belegt (Probe) |
| `base4` | Breite Linien | `lines_wide` | Annahme |
| `base5` | Schmale Linien | `lines_narrow` | Annahme |
| `base6` | Gepunktet | `dotted` | belegt (Probe: JPG zeigt Punktraster) |
| `base3` | Kleines Raster | `grid` | Annahme (Rastergröße weicht ab) |
| `base2` | Mittleres Raster | `grid` | Annahme |
| `handwrite1` | Papier für Handschrift | `handwriting` | Annahme |
| `handwrite2` | Millimeterpapier | `millimeter` | Annahme |
| `handwrite4` | Brief (chinesisch) | `letter` | Annahme (vermutlich anderes Layout) |
| `plan2` | To-Do-Liste | `todo` | Annahme (Probe vorhanden, Zuordnung per Name) |
| `plan3` | Besprechungsprotokolle | `meeting` | Annahme |
| `plan4` | Wochenplan | `weekly` | Annahme |
| `plan5` | Monatsplan | `monthly` | Annahme |
| `plan1` | Aufgabenliste | `tasks` | Annahme |
| `learning7` | Fehlerprotokoll | `error_log` | Annahme |
| `learning2` | Lesenotizen | `reading_notes` | Annahme |
| `learning3` | Cornell-Notizen | `cornell` | Annahme |
| `handwrite3` | Gitterpapier (chinesisch) | `character_grid` | nach dem Aussehen in HiNote nachgebaut |
| `learning6` | Notenlinien | `staff` | nach dem Aussehen in HiNote nachgebaut |
| `plan6` | Zeitmanagement | `time_management` | belegt (Probe) und nach dem Aussehen in HiNote nachgebaut |
| `learning4` | Punkteblatt | `point_sheet` | nach dem Aussehen in HiNote nachgebaut; Beschriftungsposition vom Nutzer bestätigt |
| `learning5` | SWOT | `swot` | nach dem Aussehen in HiNote nachgebaut; Beschriftungsposition vom Nutzer bestätigt |

**Gesamtbestand (Seitenvorlagen, 264 Seiten):** `base6` 157, `base1` 104, `plan2` 2, `plan6` 1 – sonst nichts. Neu belegt
durch Ansicht der Vorschau-JPG: `plan2` = Tabelle „Fertig / Zeit / To-Dos“ (Kreise links, 14 Zeilen) → `todo` passt;
**`plan6` ist laut Vorschau eine Vier-Felder-Matrix mit den Achsenbeschriftungen „Dringend / Nicht dringend / Wichtig / Nicht wichtig“**
(Eisenhower-Matrix, in der App-Tabelle als „Zeitmanagement“ geführt). Dafür gibt es die Vorlage `time_management`
(Maße 702 x 840 nach dem Aussehen der HiNote-Vorlage, gleichmäßig auf die Seite skaliert). `base3` kommt nur
als Notiz-`background` vor (`shibari`; die Seiten haben `base6`), nicht als Seitenvorlage – die Zuordnung `base3` → `grid`
bleibt Annahme. Alle anderen Tabellenzeilen (`base2`, `base4`, `base5`, `handwrite*`, `plan1/3/4/5`, `learning*`) sind im Bestand
**nicht** vorhanden, also weiter Annahme.

`TemplateCatalog.lookup()` fällt bei unbekannten IDs ohnehin auf `blank` zurück. Die JPG-Vorschau enthält den Hintergrund
(belegt); sie eignet sich als Notbehelf nur für die Anzeige, nicht als Strichersatz (sie enthält die Striche mit).

---

## 2. Stiftdaten `files/<pageId>.bin` (`PENCILENGINE`)

Aufbau (alles hintereinander, keine Lücken; belegt in allen 19 Dateien, es bleiben **0 Restbytes**):

```
Dateikopf (76 B) | Seitenkopf (48 B) | Grundinhalt (12 B) | Strich 1 … Strich n | Abschlussblock (40 B + Gruppen)
```

Beispiel `d495cc51….bin` (131 956 Byte): Dateikopf 0–75, Seitenkopf 76–123, Grundinhalt 124–135, erster Strich ab 136
(`0x88`), 184 Striche, Abschlussblock ab 131 916 (`0x2034C`) bis Dateiende.

### 2.1 Dateikopf (76 Byte, Offset 0)

| Offset | Typ | Wert in Proben | Bedeutung | Status |
|---|---|---|---|---|
| 0 | 12 × byte | `PENCILENGINE` | Kennung (ASCII, genau 12 Zeichen) | belegt |
| 12 | int | 76 | Größe dieses Kopfes | belegt |
| 16 | i16, i16 | 0, 0 | Version (Haupt/Neben); Annahme: unbenutzt | Annahme |
| 20 | int, int | 0, 0 | reserviert/unbekannt | – |
| 28 | i64 | 0 | Erstellzeit (ms); im Export 0 | belegt (0) |
| 36 | i64 | 0 | Änderungszeit (ms); im Export 0 | belegt (0) |
| 44 | 32 × byte | 0 | reserviert | belegt (0) |

### 2.2 Seitenkopf (48 Byte, Offset 76)

| Offset | Typ | Wert | Bedeutung | Status |
|---|---|---|---|---|
| +0 | int | 48 | Größe dieses Kopfes | belegt |
| +4 | int | 2 | Format-/Abschnittsversion | belegt (konstant 2) |
| +8 | u16, u16 | 1, 0 | Neben-/Unterversion | belegt (konstant) |
| +12 | int | Dateigröße − 124 | Länge aller Daten nach dem Seitenkopf | belegt (geprüft) |
| +16 | 16 × byte | zufällig | UUID der Seite (nicht identisch mit `pageId`) | belegt |
| +32 | int | 12 | Größe des Grundinhalts | belegt |
| +36 | int | n | **Anzahl Striche** | belegt |
| +40 | int | 12 + Σ Strichgrößen | Länge von Grundinhalt + allen Strichen, also Offset des Abschlussblocks relativ zum Seitenkopf-Ende | belegt |
| +44 | int | 1 | unbekannt (Annahme: Anzahl Abschnitte) | Annahme |

### 2.3 Grundinhalt (12 Byte, Offset 124)

| Offset | Typ | Wert | Bedeutung | Status |
|---|---|---|---|---|
| +0 | int | 0 | unbekannt | – |
| +4 | int | **1000** | Seitenbreite der Koordinaten (normiert) | belegt |
| +8 | int | **1195** | Seitenhöhe der Koordinaten; entspricht `Ansichtshöhe × 1000 / Ansichtsbreite` (bei `pageRatio` 0,8357 ergibt das 1195) | belegt |

### 2.4 Strich (variable Länge)

Jeder Strich: Kopf (48) + Grundwerte (24) + Pinsel (48) + Punktblock (20 + 36 × Punkte).

**Strichkopf (48 Byte)**

| Offset | Typ | Wert | Bedeutung | Status |
|---|---|---|---|---|
| +0 | int | 48 | Kopfgröße | belegt |
| +4 | int | 0 | unbekannt (Version) | – |
| +8 | u16, u16 | 1, 0 | Unterversion | belegt (konstant) |
| +12 | int | 72 + Punktblock | Länge aller Daten dieses Strichs nach dem Kopf (Grundwerte + Pinsel + Punktblock) | belegt |
| +16 | 16 × byte | zufällig | Strich-UUID (Verweis aus Gruppen im Abschlussblock) | belegt |
| +32 | int | 24 | Größe der Grundwerte | belegt |
| +36 | int | 48 | Größe der Pinselstruktur | belegt |
| +40 | int | 72 | Offset des Punktblocks ab Kopfende (= 24 + 48) | belegt |
| +44 | int | 20 + 36·n | Größe des Punktblocks | belegt |

Nächster Strich beginnt bei `Strichanfang + 48 + Wert(+12)`.

**Grundwerte (24 Byte, ab Kopfende)**

| Offset | Typ | Wert | Bedeutung | Status |
|---|---|---|---|---|
| +0 | i64 | 0 | Startzeit (ms, Epoche); im Export 0 | belegt (0) |
| +8 | f32 | 0.0 | Index des ersten Punkts | belegt |
| +12 | f32 | n − 1 | Index des letzten Punkts (in allen 4607 Strichen = Punktzahl − 1) | belegt |
| +16 | bool | 0 | Flag A (Annahme: „aus Datei geladen/Teil einer Formkorrektur“) | Annahme |
| +17 | bool | 0 | Flag B (Annahme: Kennzeichen für erkannten/geglätteten Strich) | Annahme |
| +18 | 2 × byte | 0 | reserviert | belegt (0) |
| +20 | int | **−1** | Gruppen-/Verbindungsindex. −1 = normaler Strich; ≠ −1 = Strich gehört zu einer Formgruppe und wird beim normalen Zeichnen vermutlich **nicht** als eigener Strich dargestellt | belegt (−1 in allen Proben), Bedeutung ≠ −1 Annahme |

**Pinselstruktur (48 Byte)**

| Offset | Typ | Wert in Proben | Bedeutung | Status |
|---|---|---|---|---|
| +0 | int | **2** | Pinseltyp (siehe 2.7) | belegt |
| +4 | int | 0 | zweiter Typwert (Annahme: Unterart) | Annahme |
| +8 | f32 | 0.0 | **Blau** 0..1 | belegt (Reihenfolge B, G, R aus Hexdump und Vorschau) |
| +12 | f32 | 0.0 | **Grün** 0..1 | belegt (Code) |
| +16 | f32 | 0.0 | **Rot** 0..1 | belegt (Code) |
| +20 | f32 | 1.0 | „Intensität“ (Annahme: Deckkraftfaktor für Marker; beim Schreiben aus Alpha abgeleitet: Marker α/5, Textmarker α) | Annahme |
| +24 | f32 | 1.0 | **Alpha** 0..1 | belegt (Code), in Proben nur 1.0 |
| +28 | f32 | **2.0** | **Strichbreite** (Einheit: Ansichtspixel, Abschnitt 4) | belegt (Wert/Einheit teils Annahme) |
| +32 | int | 0 | unbekannt | – |
| +36 | bool | 0 | Flag; gesetzt, wenn der Pinseltyp 3 (Bleistift) ist; Bedeutung unbekannt | Beobachtung (1 Strich), Bedeutung Annahme |
| +37 | 3 × byte | 0 | reserviert | belegt (0) |
| +40 | 8 × byte | 0 | reserviert | belegt (0) |

ARGB für die Anzeige: `A=round(alpha·255)`, `R/G/B` analog. HiNote hellt beim Einlesen die Deckkraft aller Farben außer
Schwarz (`0xFF000000`), Dunkelgrau (`0xFF505050`) und Weiß um `(255−A)/6` auf (Anzeigekorrektur, Annahme: für den Import
den Rohwert verwenden).

**Punktblock (20 Byte Kopf + 36 Byte je Punkt)**

Kopf: `int` 0 (unbekannt) | `int` n (Punktzahl) | `int` 36 (Punktgröße) | 8 × byte 0 (reserviert) — belegt.

Punkt (36 Byte):

| Offset | Typ | Bedeutung | Status |
|---|---|---|---|
| +0 | f32 | **x** in Seitenkoordinaten (Breite 1000) | belegt |
| +4 | f32 | **y** in Seitenkoordinaten (Höhe 1195) | belegt |
| +8 | int | **Zeit in ms relativ zum ersten Punkt des Strichs** (erster Punkt 0, monoton nicht fallend, max. 1800 ms in den Proben) | belegt |
| +12 | f32 | **Druck** 0..1 (Proben: 0.0049 … 0.9993, in 1/4096-Stufen quantisiert, Mittel 0.67) | belegt |
| +16 | f32 | unbekannt, immer 0 (Annahme: Neigung/Azimut o. ä.) | Annahme |
| +20 | f32 | unbekannt, immer 0 | Annahme |
| +24 | f32 | unbekannt, immer 0 (HiNote schreibt hier explizit 0.0) | Annahme |
| +28 | int | Punktrolle: **4** = erster, **5** = letzter, **6** = mittlerer Punkt | belegt |
| +32 | f32 | laufender Punktindex 0.0 … n−1 | belegt (alle 78 119 Punkte) |

### 2.5 Abschlussblock (≥ 40 Byte, am Dateiende)

Kopf (40 Byte): `int` 40 (Größe) | `int` 3 | `u16` 1 | `u16` 0 | `int` 0 | 16 × byte 0 (UUID) | `int` 0 | `int` **g** =
Anzahl Gruppen. In allen Proben ist g = 0, die Datei endet unmittelbar danach (belegt).

Je Gruppe (nur aus dem Code, **ungetestet**): `int` Anzahl Einträge c | `int` Eintragsgröße e | 8 × byte reserviert |
c × e Byte; jeder Eintrag beginnt mit der 16-Byte-UUID eines Strichs (gefolgt von einem `int`, e = 20). Annahme:
Gruppen fassen Striche zu einem erkannten Objekt (Formerkennung) zusammen; solche Striche werden beim normalen Laden
übersprungen.

### 2.6 Weitere Formatvarianten

**`HWEINKBYTE` kommt im Gesamtbestand vor: 57 von 260 `.bin` (22 %), 18 810 Striche, in 8 von 34 Notizen** – `BIT 2023` (10 von 15 Seiten),
`BIT-2022` (13/13), `ESTV` (2/6), `RHTE 2023` (BIT, 8/8), `BR-Seminar Waldsrode 2022` (11/11), `RHTE 2023` (Open Shift, 1/1),
`Sibari Advent` (2/2), `shibari` (10/94). Gemischte Notizen enthalten beide Formate nebeneinander. Das ist der
Altformat-Weg („Version 1“, Datei-Kennung `HWEINKBYTE`); `tools/hinote_bin.py` (`parse_legacy_bin`) und der Kotlin-Importer
(`HinoteBinParser.parseLegacy`) lesen es seit dem Gesamtimport. Sicherheitsprüfungen wie beim neuen Format: Größenfelder vor jeder
Allokation gegen die Dateilänge, Strich-/Punktzahl-Limits, Abschnitt endet exakt am Dateiende (Restbytes → Fehler).

Aufbau (Big-Endian; aus dem Hexdump aller 57 Dateien abgeleitet; alle 57 Dateien
laufen ohne Restbytes durch):

```
Kennung "HWEINKBYTE" (10 B) + 2 B Null | int Kopfgröße (=28) | int 0 | int 0 | i16 Version (=1) | i16 Unterversion (=1)
| int Anzahl Abschnitte (=1) | je Abschnitt: int Offset, int Länge (Offset = Kopfgröße + 12 = 40, bis Dateiende)
Abschnitt "Striche": int 28 (unbekannt) | i64 Zeitstempel (ms, Epoche; Erstellung) | int Breite (=1404) | int Höhe (1677 oder 1460)
                     | 2 × int 0 | int 16 | int Anzahl Striche n | int 0 | int 0          (44 Byte Kopf)
Strich:  int Punktzahl p | i16 1 | i16 1 | int Pinseltyp | int ARGB | f32 Breite | 4 × f32 Rechteck (normiert)   (36 Byte)
Punkt:   int Rolle (0 erster, 2 mittlerer, 1 letzter) | f32 x | f32 y | f32 Druck | f32 (immer 0) | i64 Zeit (ms)  (28 Byte)
```

Befunde: `x`, `y` sind **normiert 0..1** (x ∈ [0,004; 0,998], y ∈ [−0,001; 1,003]) – Umrechnung `x·Breite`, `y·Höhe` mit der
Kopf-Breite/-Höhe (1404 × 1677 bei `pageRatio` 0,8357; 1404 × 1460 bei 0,9616). Der Pinsel steht als Typ (alle 18 810 Striche = 2,
Kugelschreiber) und **ARGB-Int** (nicht Float) im Strich, die Breite ist 1,0 (1×), 2,0 (5 991×) oder 4,0 (12 818×).
Druck 0,007 … 0,999. Die Zeit der Punkte ist **absolut** (Millisekunden seit einem Gerätebezug, z. B. 15 710 188 … 326 783 314 –
Annahme: Betriebszeit), nicht relativ zum Strichanfang. Es gibt nur den einen Abschnitt „Striche“; eine Formgruppe
(Abschnitt 2) kommt in keiner Datei vor. Importregel (umgesetzt) ist die Umrechnung auf Seitenkoordinaten wie bei `PENCILENGINE`
(`x·1404`, `y·Höhe` bzw. `y·1404·Höhe/Breite` identisch), mit Zeit relativ zum ersten Punkt des Strichs.

| Kennung | Vorkommen | Bemerkung |
|---|---|---|
| `PENCILENGINE` | 203 von 260 `.bin` | dieses Dokument |
| `HWEINKBYTE` | 57 von 260 `.bin` | oben; im Importer und in `tools/hinote_bin.py` implementiert |

Im Bestand kommen nur diese beiden Kennungen vor; jede `.bin` enthält genau eine davon, gemischte Notizen enthalten beide Formate nebeneinander.

### 2.7 Pinseltyp (Dateiwert) → Stift in `PenCatalog`

Der Dateiwert ist der Pinseltyp von HiNote (12.4.3). Die Gruppen sind aus den Namen abgeleitet (Stift: 1, 2, 12, 13;
Bleistift: 3, 11; Marker: 4, 5). Beim Import wird der Wert über eine kleine Tabelle auf den Stifttyp des E-Ink-Frameworks
abgebildet (2→2, 3→3, 4/5→6, alles andere→4); `PenCatalog` führt dieselbe Zuordnung. Sie ist nur für Typ 2 belegt, die übrigen
Zeilen sind Annahmen und am Gerät zu prüfen.

| Dateiwert | Pinseltyp (HiNote) | Framework-Typ | `PenCatalog`-ID | Status |
|---|---|---|---|---|
| 1 | PEN | 4 | `fountain` | Annahme; **Gesamtbestand: 1 Strich** (`Api open`, Seite mit Bleistift-Strich) |
| **2** | BALLPOINT | 2 | `ballpoint` | **belegt: 71 791 von 71 793 `PENCILENGINE`-Strichen und 18 810 von 18 810 `HWEINKBYTE`-Strichen** |
| 3 | PENCIL | 3 | `pencil` | Annahme; **Gesamtbestand: 1 Strich** (`Api open`) |
| 4 | MARKER | 6 | `marker` | Annahme; im Bestand nicht vorhanden. Die Transparenz liegt laut Gerätetest in der Stiftfarbe (Alpha 0x80), siehe `PenSpec.colorFor` |
| 5 | HIGHLIGHTER | 6 | `marker` (mit Alpha) | Annahme; im Bestand nicht vorhanden |
| 11 | TWOB (2B-Bleistift) | 4 | `pencil` | Annahme (Gruppe Bleistift), vom Nutzer so akzeptiert; im Bestand nicht vorhanden |
| 12 | BEAUTY | 4 | `fountain` | Annahme |
| 13 | CHINESE_WRITING | 4 | `fountain` | Annahme |

**Gesamtbestand (belegt):** Es kommen nur die Pinseltypen 2 (Kugelschreiber), 3 (1×) und 1 (1×) vor – Marker, Textmarker und
die Typen 11–13 gibt es im Bestand nicht. **Farben:** alle 71 793 `PENCILENGINE`-Striche sind Schwarz `ff000000` (Alpha 1,0);
im Altformat 18 803 × `ff000000` und **7 × `ff505050` (Dunkelgrau)** auf einer Seite von `BR-Seminar Waldsrode 2022`
(`ad7c6cdd…`). Keine Farbstriche, kein Alpha < 1. **Breiten:** `PENCILENGINE` 2,0 (71 560×) und 4,0 (233×, `shibari` und die
beiden Nicht-Kugelschreiber-Striche); Altformat 1,0, 2,0, 4,0. Einheiten wie in Abschnitt 3.

---

## 3. Koordinatensystem und Einheiten

| Größe | Ergebnis | Status |
|---|---|---|
| Koordinaten | x ∈ [0, 1000], y ∈ [0, 1195]; Proben: x 0,1…988, y 3…1189 (alle innerhalb) | belegt |
| Normierung | beim Laden: `x_Ansicht = x / 1000 · Ansichtsbreite`, `y_Ansicht = y / Höhe_Datei · Ansichtshöhe` – x und y teilen also **einen** Maßstab (aus Vorschau-JPG und Rendering belegt) | belegt |
| Seitenverhältnis | Datei 1000 : 1195 = 0,8368; `pageRatio` 0,8357143 (= 1404/1680); Abweichung 0,13 % (Rundung der Ansichtshöhe auf ganze Pixel/Abschneiden) | belegt |
| Bezug zu PaperNotes (1404 × 1872) | Breite passt: Maßstab `1404/1000 = 1,404`. Die HiNote-Seitenhöhe ist **1404/0,8357 ≈ 1680 px** (nicht 1872): HiNote zeigt die Seite mit etwas weniger Höhe. 1195 · 1,404 = 1678 px | Breite belegt; „1404 px = Ansichtsbreite“ Annahme (aus `pageRatio` = 1404/1680 gefolgert) |
| Vorschau-JPG | 903 × 1080 = Seite (1404 × 1680) × 0,643; Verhältnis 0,8361 | belegt |
| Strichbreite | Zahl wird im Importer **ohne Skalierung** als Stiftbreite in Ansichtspixeln übernommen. Test: Rendering mit Faktor 1,0 auf 1404er Breite liefert etwa 1,1-fache Tintenmenge der Vorschau (Schwarzschwelle 110; Faktor 0,75 → 0,75-fach), optisch deckungsgleich | Einheit „Pixel einer 1404 px breiten Ansicht“ ≈ belegt, genauer Faktor offen |
| Zeit | ms ab erstem Punkt des Strichs | belegt |
| Farbe | `PENCILENGINE`: Float-RGBA 0..1 → ARGB; `HWEINKBYTE`: direkter ARGB-Int. Gesamtbestand: Schwarz und ein Dunkelgrau (`ff505050`) | Formel belegt, Farbvielfalt (bunte Stifte, Alpha) ungeprüft |
| Druck | 0..1 | belegt |
| Radierer/Gelöscht | Es gibt **keine** Löschmarkierung im Strichformat; Rendering aller Striche deckt sich mit der Vorschau (nichts Unsichtbares gefunden). Annahme: Gelöschte Striche werden beim Speichern entfernt | Annahme |
| Text, Bilder, Lesezeichen | **nicht in der `.bin`**, sondern in `pageElement[]`/`pageBookMarks[]` der Seiten-JSON (Bilder und Text belegt, Lesezeichen im Bestand nie vorhanden) | belegt |

---

## 4. Verifikation

Gesamtbestand (2026-11-07): `unzip -t` → **34 von 34 ZIPs fehlerfrei**. `python3 tools/hinote_bin.py check` je Ordner von
`tmp/device/exp/all` → **34 von 34 fehlerfrei** (vor der Altformat-Unterstützung 26 von 34; die 8 übrigen enthalten `HWEINKBYTE`, siehe 2.6). Die Zahlen
stimmen mit dem Kotlin-Test `allRealExportsImportWithoutWarnings` überein: 264 Seiten, 260 `.bin`, 71 793 + 18 810 = 90 603 Striche
(1 251 474 + 333 559 Punkte). Plausibilitätswarnungen: 10 Seiten mit Strichen, die höchstens ~50 Einheiten über den Seitenrand
reichen (jeweils 1–3 Striche, unkritisch).

Erster Test: `python3 tools/hinote_bin.py check tmp/device/exp/custom` → **14 von 14 Exporten fehlerfrei** (19 `.bin`, 4607 Striche,
78 119 Punkte, 0 Restbytes, 0 Plausibilitätswarnungen). Geprüft werden: Magic, alle Größenfelder untereinander,
Dateiende exakt am Ende des Abschlussblocks, Koordinaten innerhalb der Seite, Zeit monoton ab 0, Druck 0..1, Punktrollen,
Pinseltyp bekannt. Negativtests (angehängtes Byte, gekürzte Datei, falsche Kennung) werden erkannt.

Sichtvergleich: `python3 tools/hinote_bin.py render <Export.hinote> <Ausgabeverzeichnis>` (Pillow nötig) legt je Seite
ein JPG mit Vorschau links und eigenem Rendering rechts ab. Beispiel PI37, Seite 1: Positionen, Zeilenabstände und
Schriftbild stimmen überein (Hintergrundpunkte fehlen im eigenen Rendering, weil die Vorlage nicht mitgezeichnet wird).

## 5. Importierbar / nicht importierbar

| Inhalt | Importierbar | Anmerkung |
|---|---|---|
| Striche (Typ, Farbe, Breite, Punkte, Druck, Zeit) | ja | siehe Mapping in der Empfehlung |
| Seitenreihenfolge, Seitenzahl | ja | `pageNumber`; leere Seiten haben keine `.bin` |
| Titel | ja | Zeitstempel der Notiz fehlen → aus Seiten ableiten |
| Vorlage | teilweise | Tabelle 1.3; unbekannt → `blank` |
| Favorit/angeheftet | ja | Felder vorhanden, in Proben 0 |
| Lesezeichen | vermutlich | `pageBookMarks` in Proben leer |
| Bilder (11), Textboxen (2) | ja (seit 0.5.0) | `pageElement[]` siehe 1.2.1; Bilddateien liegen im ZIP; Textformatierung aus dem `html`-Feld (`HinoteHtml`) |
| Seiten im Altformat `HWEINKBYTE` | ja (seit dem Gesamtimport) | 57 Seiten in 8 Notizen, Aufbau in 2.6 |
| Audioaufnahmen, Gliederung | **ungeprüft/nein** | Listen in Proben leer |
| Ordner/Kategorie | **nein** (nicht im Export) | extern: `tmp/device/exp/all/index.json`; der Ordnerimport übernimmt die Unterverzeichnisse von `Documents/hinote/custom` als Unterordner von `HiNote-Import` (Abschnitt 6.1) |
| Erkennungstext (`attachment` Typ 1) | möglich | 27 Seiten, optional für die Suche |
| Verschlossene Notizen | nicht exportiert | im Bestand keine verschlossene Notiz gefunden (kein Schloss-Symbol, alle 34 exportierbar) |
| Formgruppen (Abschlussblock) | nur lesen, nicht prüfbar | Proben ohne Gruppen |
| Vorschau-JPG | nur als Kontrollbild | enthält Hintergrund und Striche |

## 6. Empfehlung für den Kotlin-Import (Mapping HiNote → PaperNotes)

Koordinaten und Breiten werden beim Import einmal in Seitenkoordinaten der PaperNotes-Seite (1404 × 1872) umgerechnet.
HiNote-Seiten sind 1404 × 1680 groß (aus `pageRatio`); die Striche bleiben oben ausgerichtet, die Seite wird unten
um 192 px verlängert (vom Nutzer bestätigt: das passt so).

| HiNote | PaperNotes | Regel |
|---|---|---|
| Notiz `noteTitle` | `Note.title` | direkt |
| `createTime`/`modifiedTime` der Notiz (0) | `Note.created`/`modified` | Minimum/Maximum der Seiten-`createTime`/`modifiedTime` |
| `isFavorite`, `isTop` | `favorite`, `pinned` | `== 1` |
| Ordner | `folderPath` | nicht im Export; beim Einmal-Export mitgeben (Unterordner), sonst leer |
| Seite (nach `pageNumber`) | `Page` | neue UUID; `width` = 1404, `height` = 1872 |
| Seite ohne `.bin` | leere `Page` | belegt (kommt vor) |
| `background` | `Page.template` | Tabelle 1.3 |
| `pageBookMarks` nicht leer | `bookmarked = true` | Annahme |
| Strich | `Stroke` | siehe unten |
| Pinseltyp 1/2/3/4/5/11/12/13 | `penType` = `PenCatalog`-`frameworkType` | 1→4, 2→2, 3→3, 4→6, 5→6, 11→3, 12→4, 13→4 (Spalte „Framework-Typ“ der Tabelle 2.7; 11→3 ist eine vom Nutzer akzeptierte Annahme) |
| Farbe `(α, R, G, B)` Float | `color` ARGB-Int | `round(v·255)` je Kanal; Pinselwert „Intensität“ ignorieren |
| `width` | `Stroke.width` | `width` unverändert (Ansichtspixel bei 1404 px Breite); bei Marker/Textmarker prüfen |
| Punkt `x`, `y` | `Point.x`, `Point.y` | `PENCILENGINE`: `x·1404/Breite`, `y·1404/Breite` (gleicher Maßstab für beide Achsen, oben ausgerichtet). `HWEINKBYTE`: zuerst `x·Kopfbreite`, `y·Kopfhöhe` (Kopfbreite 1404), dann derselbe Maßstab `1404/Kopfbreite` = 1 |
| Punkt `t_ms` | `Point.time` | relativ zum Strichanfang; im Altformat wird die absolute Zeit dazu auf den ersten Punkt bezogen |
| Punkt `pressure` | `Point.pressure` | direkt |
| Striche mit `link_index != -1` oder in Abschlussgruppen | überspringen | wie HiNote (`BinFile.visible_strokes`) |
| `pageElement[]` (Text/Bild) | `ImageAttachment` / `Textbox` | Position und Größe als Bruchteil der Seite, Bilddatei aus dem ZIP nach `Note.media`, Text aus `html` (sonst `contentText`), siehe 1.2.1 |

Importregeln (AGENT.md): ZIP-Pfade kanonisieren (kein `..`), Größenlimits je Eintrag und gesamt, Kompressionsrate
begrenzen, `.bin`-Größenfelder gegen Dateilänge prüfen (der Python-Parser macht das vor jeder Allokation), kein Entpacken
auf die Platte nötig. Der Kotlin-Parser sollte `ByteBuffer.order(BIG_ENDIAN)` nutzen und nach dem Abschlussblock exakt das
Dateiende verlangen.

### 6.1 Ordnerimport aus `Documents/hinote/custom`

- Der Import „Aus Documents/hinote/custom importieren“ durchsucht das Verzeichnis rekursiv (Tiefe höchstens 3, keine Symlinks, höchstens
  500 Dateien). Das Unterverzeichnis relativ zur Scanwurzel wird Ordnerpfad unterhalb von `HiNote-Import` (`BIT` → `HiNote-Import/BIT`).
  Dateien direkt in der Wurzel sowie Verzeichnisnamen, die nach `FolderPath` ungültig sind, oder zu tiefe Verzeichnisse → `HiNote-Import`.
- **Mehrere Dateien derselben `noteId` im selben Lauf:** Es wird genau eine importiert – die mit dem neuesten Dateizeitstempel; bei Gleichstand
  die mit den meisten Seiten, dann die im Unterverzeichnis, dann die erste in Namensreihenfolge. Die übrigen zählen als „übersprungen“.
- Bereits importierte Notizen (gleiche `importSource` im Bestand) werden übersprungen und **nicht** in einen anderen Ordner verschoben.
- Hinweis: Die Auswahl über das Dateiauswahl-Fenster (SAF) kennt keine Ordner und legt alles in `HiNote-Import` ab.
