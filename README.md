[![PaperNotes by BOB](PaperNotes%20BY%20BOB-banner.jpg)](PaperNotes%20BY%20BOB-banner.jpg)

# PaperNotes by BOB

Notiz-App für das Huawei Paper (HMW-W09, Android 10, E-Ink) als Ersatz für HiNote. Handschrift wird flüssig über die geräteeigene E-Ink-Stiftschicht geschrieben; die Oberfläche besteht aus klassischen Views (ohne Animationen, hoher Kontrast) und ist zweisprachig (Deutsch/Englisch).

*English: PaperNotes by BOB is a handwritten note-taking app for the Huawei Paper E-Ink tablet (Android 10). It uses the device's native E-Ink pen layer, stores notes as `.pnote` files in `Documents/PaperNotes` and is available in German and English.*

## Funktionsstand

- Editor mit Stiften (Füllfeder, Kugelschreiber, Bleistift, Marker), Radierer, Lasso, Rückgängig/Wiederholen, mehreren Seiten und 16 Seitenvorlagen
- Startseite mit Ordnern, Raster- und Listenansicht, Suche, Sortierung, Favoriten und Fixieren
- Mehrfachauswahl mit Teilen, Verschieben und Löschen
- Papierkorb (wiederherstellen, endgültig löschen, leeren), Einstellungen und Über-Seite
- Seitenleiste mit Seitenvorschauen und Lesezeichen; Striche in Seitenkoordinaten
- Automatische Sicherungskopien (Intervall einstellbar, Sicherungen ansehen, als Kopie wiederherstellen)
- PDF-Export (Teilen oder Speichern in `Documents/PaperNotesPDF`)
- Android-Widget für den Launcher (z. B. inkOS: „Android Widget“): letzte/favorisierte/fixierte Notizen oder ein Ordner, optionale Uhr, „+“ für eine neue Notiz; optional Batterie und Benachrichtigungszähler in der Kopfzeile (der Zähler zählt nur die Anzahl, nie Titel, Texte oder Apps; er braucht den Benachrichtigungszugriff, den du in den Android-Einstellungen erteilst); in inkOS auf dem Gerät platziert und angesehen
- Import von HiNote-Exporten (`.hinote`, inkl. Altformat und Unterordner) mit Strichen, Bildern und Textboxen
- Nextcloud-Sync (Einweg, Tablet → Nextcloud): stromsparend per JobScheduler (nur bei Netz und wenn etwas ansteht, auch bei beendeter App), Anmeldung per Login Flow v2 im Browser, Server und Zielordner einstellbar, Strukturänderungen (neu, umbenannt, verschoben, gelöscht) werden übertragen; Sync-Symbol mit Zahl der ausstehenden Änderungen auf der Startseite und im Widget. Auf Huawei: Tablet-Manager › Akku › App-Start auf „Manuell verwalten“.
- Zwei-Finger-Zoom und Verschieben (100 bis 500 %), Lasso mit Filtern
- Textboxen (verschieben, skalieren, Schriftgröße, Hintergrund), Bilder (einfügen, zuschneiden) und Audioaufnahmen (aufnehmen, abspielen, umbenennen, löschen) auf der Seite
- Nextcloud-Plugin `papernotes` (Viewer im Browser, PDF-Download) unter `nextcloud-app/`
- Stand und offene Aufgaben bis Version 1.0.0: siehe [ReleasePlan.md](ReleasePlan.md)

## Bauen

Java 17 und das Android SDK sind nötig. Befehle, Signierung und Arbeitsregeln stehen in [AGENT.md](AGENT.md); kurz:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew test assembleDebug lint
```

## Lizenz

Freie Software unter der GNU General Public License v3.0 oder später (GPL-3.0-or-later). Den vollständigen Text enthält die Datei [LICENSE](LICENSE).

Copyright © 2025 hjochman. Dieses Programm wird ohne jede Gewährleistung verteilt.

*English: Free software under the GNU General Public License v3.0 or later. See [LICENSE](LICENSE). This program is distributed without any warranty.*
