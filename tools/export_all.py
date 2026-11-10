#!/usr/bin/env python3
"""Einmal-Export aller HiNote-Notizen per UI-Automation (adb + uiautomator) als .hinote.

Nur Standardbibliothek. Es wird nichts in HiNote veraendert oder geloescht, auch nichts auf dem Tablet.

Voraussetzung: Tablet entsperrt, Bildschirm an, HiNote 12.4.3 installiert, USB-Debugging an.

Befehle:
  export_all.py run  [--only ORDNER] [--root-only] [--limit N] [--dry-run] [--no-pull]
  export_all.py pull                 # nur Pull + Dedup + index.json
  export_all.py list                 # Struktur ausgeben (wie dry-run)

Ablauf `run`:
  Wurzelansicht seitenweise ("Naechste Seite") durchgehen -> Notizen exportieren, Ordnerkarten merken ->
  jeden Ordner oeffnen, dort ebenso seitenweise exportieren -> zurueck. Je Notiz:
  Menue (grid_item_menu) -> "Exportieren" -> "HiNote". Fertig = neue Datei in /sdcard/Documents/hinote/custom/.
  Danach oeffnet HiNote den Dateimanager; BACK bringt HiNote zurueck.

Wiederholbarkeit: tmp/device/exp/all/state.json merkt sich je (Ordner|Titel|Datum) die exportierte Geraetedatei.
Ist sie noch auf dem Tablet, wird die Notiz uebersprungen. Verschlossene Notizen (Schloss-Symbol iv_lock, Menue
ohne "Verschliessen" oder PIN-Abfrage) werden uebersprungen und im Bericht gelistet.

Nach `run` (oder `pull`): alle Dateien vom Tablet ziehen, nach noteId (aus <noteId>.jhinote im ZIP) deduplizieren
(neueste behalten, aeltere nach _dupes/), nach all/<Ordner>/ einsortieren, all/index.json schreiben.
"""
import argparse, gzip, json, os, re, shutil, subprocess, sys, time, zipfile
from pathlib import Path
import xml.etree.ElementTree as ET

# Seriennummer des Tablets aus der Umgebung (ANDROID_SERIAL, wie bei adb); ohne Angabe muss genau ein Gerät verbunden sein.
SERIAL = os.environ.get("ANDROID_SERIAL") or subprocess.run(
    ["adb", "get-serialno"], capture_output=True, text=True).stdout.strip()
ADB = shutil.which("adb") or "/opt/homebrew/bin/adb"
PKG = "com.huawei.hinote"
EXPORT_DIR = "/sdcard/Documents/hinote/custom"
MENU_EXPORT = ("Exportieren", "Export")
FMT_HINOTE = "HiNote"
ROOT_TITLE = "Alle Notizen"
NOTE_TIMEOUT = 90  # Sekunden je Notiz (alle Schritte)
OUT = Path(__file__).resolve().parent.parent / "tmp" / "device" / "exp" / "all"
STATE = OUT / "state.json"
CONN_ERR = re.compile(r"not found|offline|no devices|unauthorized|closed|protocol fault|cannot connect", re.I)


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


class Abort(Exception):
    pass


# ------------------------------------------------------------------ adb

def adb(*a, check=True, timeout=60, retries=4):
    r = None
    for attempt in range(retries + 1):
        try:
            r = subprocess.run([ADB, "-s", SERIAL, *a], capture_output=True, text=True,
                               encoding="utf-8", errors="replace", timeout=timeout)
        except subprocess.TimeoutExpired:
            log("  ! adb Timeout, wait-for-device + Retry", a[:2])
            subprocess.run([ADB, "-s", SERIAL, "wait-for-device"], timeout=180)
            time.sleep(2)
            continue
        if r.returncode and CONN_ERR.search(r.stderr or ""):
            log("  ! adb-Verbindungsproblem, wait-for-device + Retry:", (r.stderr or "").strip()[:80])
            subprocess.run([ADB, "-s", SERIAL, "wait-for-device"], timeout=180)
            time.sleep(2)
            continue
        break
    if r is None:
        raise RuntimeError("adb ohne Ergebnis")
    if check and r.returncode:
        raise RuntimeError(r.stderr.strip())
    return r.stdout


def sh(cmd, **kw):
    return adb("shell", cmd, **kw)


def key_back():
    sh("input keyevent KEYCODE_BACK")


def screen_ok():
    """True wenn Bildschirm an und nicht gesperrt; sonst wird einmal geweckt, dann False."""
    for attempt in range(2):
        p = sh("dumpsys power | grep -E 'mWakefulness='", check=False)
        k = sh("dumpsys window policy | grep -E 'mIsShowing='", check=False)
        awake = "Awake" in p
        locked = "mIsShowing=true" in k
        if awake and not locked:
            return True
        if not awake and attempt == 0:
            sh("input keyevent KEYCODE_WAKEUP")
            time.sleep(2)
            continue
        return False
    return False


# ------------------------------------------------------------------ UI

def ui():
    for attempt in range(4):
        sh("uiautomator dump /sdcard/_ui.xml >/dev/null 2>&1", check=False)
        xml = adb("exec-out", "cat /sdcard/_ui.xml", check=False)
        try:
            return ET.fromstring(xml)
        except ET.ParseError:
            time.sleep(1)
    raise RuntimeError("uiautomator dump liefert kein gueltiges XML")


def bounds(n):
    return tuple(map(int, re.findall(r"\d+", n.get("bounds"))))


def center(n):
    x1, y1, x2, y2 = bounds(n)
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap(n):
    x, y = center(n)
    sh(f"input tap {x} {y}")


def rid(n):
    return n.get("resource-id", "").split("/")[-1]


def find(root, rid_=None, text=None):
    texts = text if isinstance(text, tuple) else ((text,) if text else None)
    return [n for n in root.iter("node")
            if (rid_ is None or rid(n) == rid_) and (texts is None or n.get("text") in texts)]


def foreground():
    return sh("dumpsys activity activities | grep mResumedActivity | head -1", check=False)


def wait_hinote(timeout=10):
    t = time.time()
    while time.time() - t < timeout:
        if PKG in foreground():
            return True
        time.sleep(0.5)
    return False


def clean_ui(max_tries=5):
    """Dialoge, Menues, Dateimanager schliessen, bis die HiNote-Listenansicht sichtbar ist."""
    root = None
    for _ in range(max_tries):
        if not screen_ok():
            raise Abort("Bildschirm aus oder gesperrt")
        if PKG not in foreground():
            key_back()
            time.sleep(1)
            if PKG not in foreground():
                sh(f"am start -n {PKG}/.HomeLauncherActivity")
                time.sleep(2)
            continue
        root = ui()
        dirty = (find(root, "alertTitle") or find(root, "menu_name") or find(root, "item_title")
                 or find(root, "note_book_popup_menu") or not find(root, "note_list_view"))
        if not dirty:
            return root
        key_back()
        time.sleep(1)
    return ui()


# ------------------------------------------------------------------ Karten und Seiten

def clean_title(t):
    return (t or "").replace("\u00a0", " ").strip()


def parse_cards(root):
    """Karten der aktuellen Seite: [{'kind','title','date','locked','menu','open'}]"""
    cards = []
    for n in root.iter("node"):
        r = rid(n)
        if r == "item_content":
            if not find(n, "grid_item_menu"):  # Kurznotiz-Kachel
                continue
            title = find(n, "caudal_title")
            date = find(n, "grid_item_date")
            cards.append({
                "kind": "note",
                "title": clean_title(title[0].get("text")) if title else "",
                "date": clean_title(date[0].get("text")) if date else "",
                "locked": bool(find(n, "iv_lock")),
                "menu": find(n, "grid_item_menu")[0],
            })
        elif r == "nfv_note_item_folder":
            title = find(n, "caudal_title")
            date = find(n, "tv_folder_remain_time")
            raw = clean_title(title[0].get("text")) if title else ""
            m = re.match(r"^(.*)\((\d+) Notiz(?:en)?\)$", raw)
            cards.append({
                "kind": "folder",
                "title": clean_title(m.group(1)) if m else raw,
                "count": int(m.group(2)) if m else None,
                "date": clean_title(date[0].get("text")) if date else "",
                "locked": bool(find(n, "iv_lock")),
                "open": (find(n, "note_constraintLayout") or [n])[0],
            })
    return cards


def page_info(root):
    t = find(root, "tv_page")
    if not t:
        return 1, 1
    m = re.match(r"(\d+)/(\d+)", t[0].get("text") or "")
    return (int(m.group(1)), int(m.group(2))) if m else (1, 1)


def goto_page(target):
    """Per Seitenwechsel-Button auf Seite `target` blaettern. Gibt frisches UI-XML zurueck."""
    root = ui()
    for _ in range(40):
        cur, total = page_info(root)
        if cur == target or total == 1:
            return root
        btn = find(root, "ll_next_page" if cur < target else "ll_last_page")
        if not btn:
            return root
        tap(btn[0])
        time.sleep(1.5)
        root = ui()
    raise RuntimeError(f"Seite {target} nicht erreichbar")


def at_root(root):
    t = find(root, "toolbar_title")
    return bool(t) and clean_title(t[0].get("text")) == ROOT_TITLE and not find(root, "toolbar_cancel")


def go_root():
    for _ in range(6):
        root = clean_ui()
        if at_root(root):
            return root
        c = find(root, "toolbar_cancel")
        if c:
            tap(c[0])
        else:
            key_back()
        time.sleep(1.5)
    raise RuntimeError("Wurzelansicht nicht erreichbar")


# ------------------------------------------------------------------ Export

def listing():
    out = sh(f"ls {EXPORT_DIR}", check=False)
    return {l.strip() for l in out.splitlines() if l.strip() and not l.startswith("ls:")}


class Locked(Exception):
    pass


def export_item(menu_node, title):
    """Fuehrt den Export fuer eine Karte aus. Gibt Dateinamen zurueck; wirft Locked/RuntimeError/TimeoutError."""
    t0 = time.time()

    def chk():
        if time.time() - t0 > NOTE_TIMEOUT:
            raise TimeoutError(f"Zeitlimit {NOTE_TIMEOUT}s")

    before = listing()
    tap(menu_node)
    time.sleep(1.2)
    root = ui()
    chk()
    names = [n.get("text") for n in find(root, "menu_name")]
    ex = find(root, "menu_name", MENU_EXPORT)
    if not ex:
        if any(re.search(r"Entsperr|aufheben|Verschluss", x or "") for x in names):
            raise Locked("Menue bietet Entsperren statt Verschliessen")
        raise RuntimeError(f"kein Export-Menue (Eintraege: {names})")
    tap(ex[0])
    time.sleep(1.5)
    root = ui()
    chk()
    fmt = find(root, "item_title", FMT_HINOTE)
    if not fmt:
        txt = " ".join((n.get("text") or "") for n in root.iter("node"))
        if re.search(r"PIN|Passwort|Entsperr|Muster|Fingerabdruck", txt) and not find(root, "item_title"):
            raise Locked("PIN-Abfrage")
        raise RuntimeError("kein HiNote-Format im Dialog")
    tap(fmt[0])
    new = set()
    for _ in range(60):
        chk()
        time.sleep(0.5)
        new = listing() - before
        if new:
            break
    else:
        # evtl. PIN-Abfrage nach Formatwahl
        root = ui()
        txt = " ".join((n.get("text") or "") for n in root.iter("node"))
        if re.search(r"PIN|Passwort|Entsperr|Muster", txt):
            raise Locked("PIN-Abfrage nach Formatwahl")
        raise RuntimeError("keine neue Datei")
    time.sleep(1.0)
    if PKG not in foreground():
        key_back()
    wait_hinote()
    time.sleep(1.0)
    return sorted(new)[0]


# ------------------------------------------------------------------ Zustand

def load_state():
    if STATE.exists():
        return json.loads(STATE.read_text(encoding="utf-8"))
    return {"items": {}}


def save_state(st):
    OUT.mkdir(parents=True, exist_ok=True)
    STATE.write_text(json.dumps(st, ensure_ascii=False, indent=1), encoding="utf-8")


class Run:
    def __init__(self, args):
        self.a = args
        self.st = load_state()
        self.report = {"exported": [], "skipped": [], "failed": [], "locked": [], "folders": {}}
        self.exported_count = 0

    def want_folder(self, name):
        return not self.a.root_only and (not self.a.only or name == self.a.only)

    def limit_hit(self):
        return self.a.limit and self.exported_count >= self.a.limit

    def handle_note(self, folder, page, card, occ):
        key = f"{folder}|{card['title']}|{card['date']}|{occ}"
        label = f"[{folder or '/'}] {card['title']} ({card['date']})"
        if card["locked"]:
            log("  verschlossen:", label)
            self.report["locked"].append(label)
            return
        if self.a.dry_run:
            log("  Notiz:", label)
            return
        known = self.st["items"].get(key)
        if known and known["file"] in self.device_files:
            log("  bereits exportiert:", label)
            self.report["skipped"].append(label)
            return
        if self.limit_hit():
            return
        last_err = None
        for attempt in (1, 2):
            try:
                if attempt == 2:
                    go_to_view(self, folder, page)
                root = ui()
                cur = [c for c in parse_cards(root) if c["kind"] == "note"
                       and c["title"] == card["title"] and c["date"] == card["date"]]
                if len(cur) <= occ:
                    raise RuntimeError("Karte nicht mehr gefunden")
                fname = export_item(cur[occ]["menu"], card["title"])
                self.st["items"][key] = {"file": fname, "title": card["title"], "folder": folder,
                                         "date": card["date"], "exported": time.strftime("%Y-%m-%d %H:%M:%S")}
                save_state(self.st)
                self.device_files.add(fname)
                self.report["exported"].append(label)
                self.exported_count += 1
                log("  exportiert:", label, "->", fname)
                break
            except Locked as e:
                log("  verschlossen (", e, "):", label)
                self.report["locked"].append(label + f" [{e}]")
                clean_ui()
                break
            except Abort:
                raise
            except Exception as e:  # noqa: BLE001
                last_err = e
                log(f"  ! Versuch {attempt} fehlgeschlagen: {label}: {e!r}")
                clean_ui()
                goto_view_page(page)
        else:
            self.report["failed"].append(f"{label}: {last_err!r}")
        # Layout kann sich aendern -> naechster Aufruf liest neu
        goto_view_page(page)

    def walk_view(self, folder):
        """Alle Seiten der aktuellen Ansicht durchgehen. Gibt Liste (Ordnername, Seite) zurueck."""
        folders = []
        root = ui()
        _, total = page_info(root)
        p = 1
        while p <= total:
            root = goto_page(p)
            _, total = page_info(root)
            cards = parse_cards(root)
            log(f"Ansicht '{folder or '/'}' Seite {p}/{total}: " +
                ", ".join(f"{c['kind'][0]}:{c['title']}" for c in cards))
            seen = {}
            for c in cards:
                if c["kind"] == "folder":
                    folders.append((c["title"], p, c.get("count"), c["locked"]))
                    continue
                k = (c["title"], c["date"])
                occ = seen.get(k, 0)
                seen[k] = occ + 1
                self.handle_note(folder, p, c, occ)
                if self.limit_hit():
                    return folders
            p += 1
        return folders

    def run(self):
        if not screen_ok():
            raise Abort("Bildschirm aus oder gesperrt - Abbruch")
        sh(f"am start -n {PKG}/.HomeLauncherActivity")
        time.sleep(3)
        self.device_files = listing()
        go_root()
        folders = self.walk_view("") if not self.a.only else self.collect_folders()
        for name, page, count, locked in folders:
            self.report["folders"][name] = {"count": count, "locked": locked}
            if self.limit_hit() and not self.a.dry_run:
                break
            if not self.want_folder(name):
                continue
            if locked:
                log("Ordner verschlossen:", name)
                self.report["locked"].append(f"Ordner {name}")
                continue
            log("== Ordner", name)
            go_root()
            root = goto_page(page)
            card = [c for c in parse_cards(root) if c["kind"] == "folder" and c["title"] == name]
            if not card:
                self.report["failed"].append(f"Ordner {name}: nicht gefunden")
                continue
            tap(card[0]["open"])
            time.sleep(2)
            root = ui()
            if not find(root, "toolbar_cancel"):
                self.report["failed"].append(f"Ordner {name}: liess sich nicht oeffnen")
                clean_ui()
                continue
            self.walk_view(name)
            go_root()
        return self.report

    def collect_folders(self):
        folders = []
        _, total = page_info(ui())
        for p in range(1, total + 1):
            root = goto_page(p)
            for c in parse_cards(root):
                if c["kind"] == "folder":
                    folders.append((c["title"], p, c.get("count"), c["locked"]))
        return folders


def goto_view_page(page):
    try:
        goto_page(page)
    except Exception as e:  # noqa: BLE001
        log("  ! goto_page:", e)


def go_to_view(run, folder, page):
    """Ansicht (Wurzel oder Ordner) wiederherstellen und auf Seite `page` blaettern."""
    root = clean_ui()
    cur = find(root, "toolbar_title")
    cur_name = clean_title(cur[0].get("text")) if cur else ""
    want = folder or ROOT_TITLE
    if cur_name != want:
        go_root()
        if folder:
            for p in range(1, 10):
                r = goto_page(p)
                c = [x for x in parse_cards(r) if x["kind"] == "folder" and x["title"] == folder]
                if c:
                    tap(c[0]["open"])
                    time.sleep(2)
                    break
    goto_page(page)


# ------------------------------------------------------------------ Pull, Dedup, Index

def note_id_of(zpath):
    with zipfile.ZipFile(zpath) as z:
        for n in z.namelist():
            if n.endswith(".jhinote") and "/" not in n and n != "custom_md.jhinote":
                meta = json.loads(gzip.decompress(z.read(n)))
                c = meta.get("customNoteContent", {})
                return n[:-len(".jhinote")], c.get("noteTitle", ""), meta
    return None, None, None


def safe_dir(name):
    return re.sub(r'[\\:*?"<>|]', "_", name).strip() or "_"


def file_ts(name):
    m = re.search(r"_(\d{12})_\d+\.hinote$", name)
    return m.group(1) if m else "0"


def pull_and_dedup(st):
    OUT.mkdir(parents=True, exist_ok=True)
    tmp = OUT / "_pull_tmp"
    shutil.rmtree(tmp, ignore_errors=True)
    tmp.mkdir()
    dupes = OUT / "_dupes"
    dupes.mkdir(exist_ok=True)
    files = sorted(f for f in listing() if f.endswith(".hinote"))
    by_file = {v["file"]: (k, v) for k, v in st["items"].items()}
    order = {v["file"]: i for i, v in enumerate(st["items"].values())}
    # bereits einsortierte Dateien (frueherer Pull) mitzaehlen
    local = {}
    for d in OUT.rglob("*.hinote"):
        if tmp in d.parents:
            continue
        local[d.name] = d
    info = []
    for f in files:
        src = local.get(f)
        if src is None:
            adb("pull", f"{EXPORT_DIR}/{f}", str(tmp / f), timeout=120)
            src = tmp / f
        try:
            with zipfile.ZipFile(src) as z:
                bad = z.testzip()
            nid, title, _ = note_id_of(src)
            if bad or not nid:
                raise ValueError(f"defekt: {bad}")
        except Exception as e:  # noqa: BLE001
            log("  ! unlesbar:", f, e)
            info.append({"name": f, "src": src, "nid": None})
            continue
        info.append({"name": f, "src": src, "nid": nid, "title": title})
    groups = {}
    for i in info:
        if i["nid"]:
            groups.setdefault(i["nid"], []).append(i)
    # Ordner je noteId: aus State-Eintrag einer beliebigen Datei der Gruppe, sonst nach Titel
    title_folder = {}
    for k, v in st["items"].items():
        title_folder.setdefault(v["title"], v["folder"])
    index, ndupes = [], 0
    for nid, lst in groups.items():
        lst.sort(key=lambda i: (file_ts(i["name"]), order.get(i["name"], -1)))
        keep, old = lst[-1], lst[:-1]
        folder = None
        for i in reversed(lst):
            if i["name"] in by_file:
                folder = by_file[i["name"]][1]["folder"]
                break
        if folder is None:
            folder = title_folder.get(keep["title"], "_unbekannt")
        dest_dir = OUT / (safe_dir(folder) if folder else "_Wurzel")
        dest_dir.mkdir(parents=True, exist_ok=True)
        dest = dest_dir / keep["name"]
        if keep["src"] != dest:
            shutil.move(str(keep["src"]), str(dest))
        index.append({"file": str(dest.relative_to(OUT)), "title": keep["title"], "folder": folder,
                      "noteId": nid, "deviceFile": keep["name"]})
        for o in old:
            ndupes += 1
            target = dupes / o["name"]
            if o["src"] != target:
                shutil.move(str(o["src"]), str(target))
    for i in info:  # nicht lesbare nicht verlieren
        if not i["nid"]:
            shutil.move(str(i["src"]), str(OUT / ("_defekt_" + i["name"])))
    shutil.rmtree(tmp, ignore_errors=True)
    index.sort(key=lambda e: (e["folder"], e["title"]))
    (OUT / "index.json").write_text(json.dumps(index, ensure_ascii=False, indent=1), encoding="utf-8")
    log(f"Pull: {len(files)} Geraetedateien, {len(index)} Notizen (noteId), {ndupes} Duplikate nach _dupes/")
    return index, ndupes


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("cmd", nargs="?", default="run", choices=["run", "pull", "list"])
    ap.add_argument("--only", help="nur diesen Ordner (Name)")
    ap.add_argument("--root-only", action="store_true", help="nur Notizen im Wurzelverzeichnis")
    ap.add_argument("--limit", type=int, default=0, help="max. Anzahl neu exportierter Notizen (Test)")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--no-pull", action="store_true")
    a = ap.parse_args()
    if a.cmd == "list":
        a.dry_run = True
    if a.cmd == "pull":
        pull_and_dedup(load_state())
        return 0
    r = Run(a)
    try:
        rep = r.run()
    except Abort as e:
        log("ABBRUCH:", e)
        return 3
    print(json.dumps({k: (v if k == "folders" else len(v)) for k, v in rep.items()}, ensure_ascii=False))
    (OUT / "report.json").write_text(json.dumps(rep, ensure_ascii=False, indent=1), encoding="utf-8")
    if not a.dry_run and not a.no_pull:
        pull_and_dedup(r.st)
    return 0


if __name__ == "__main__":
    sys.exit(main())
