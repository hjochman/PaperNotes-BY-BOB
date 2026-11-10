#!/usr/bin/env python3
"""Leser fuer die Stiftdaten `files/<pageId>.bin` (Kennungen PENCILENGINE und Altformat HWEINKBYTE) aus HiNote-Exporten.

Eigener Code nach der Formatbeschreibung in docs/hinote-bin-format.md. Der Huawei-Code wurde nur als
Anleitung zum Format gelesen, nichts davon kopiert.

Nutzung:
    python3 tools/hinote_bin.py check  <Export.hinote|.bin|Verzeichnis> ...   # strikt parsen, Plausibilitaet pruefen
    python3 tools/hinote_bin.py render <Export.hinote|.bin|Verzeichnis> <Ausgabeverzeichnis>   # braucht Pillow
    python3 tools/hinote_bin.py json   <datei.bin>                            # Striche als JSON

Als Modul: `parse_bin(bytes) -> BinFile`, `read_hinote(pfad) -> HinoteNote`.
Alle Zahlen im Format sind Big-Endian.
"""
from __future__ import annotations

import gzip
import io
import json
import struct
import sys
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterator

MAGIC = b"PENCILENGINE"
LEGACY_MAGIC = b"HWEINKBYTE"

# Sicherheitsgrenzen (AGENT.md: Groessenlimits, Zip-Bomben)
MAX_ENTRY_BYTES = 64 * 1024 * 1024
MAX_ZIP_ENTRIES = 2000
MAX_TOTAL_BYTES = 512 * 1024 * 1024
MAX_STROKES = 2_000_000
MAX_POINTS_PER_STROKE = 1_000_000

# feste Groessen (belegt, siehe Doku)
FILE_HEADER_SIZE = 76
PAGE_HEADER_SIZE = 48
BASIC_CONTENT_SIZE = 12
STROKE_HEADER_SIZE = 48
STROKE_BASIC_SIZE = 24
STROKE_BRUSH_SIZE = 48
POINT_BLOCK_HEAD = 20
POINT_SIZE = 36
TRAILER_SIZE = 40

# Punktrolle
ROLE_FIRST, ROLE_LAST, ROLE_MID = 4, 5, 6

# Pinseltyp in der Datei -> Name, und Stifttyp des Android-Frameworks (IHWTrack.getPenType)
BRUSH_NAMES = {1: "pen", 2: "ballpoint", 3: "pencil", 4: "marker", 5: "highlighter",
               11: "2b", 12: "beauty", 13: "chinese_writing"}
# Annahme: Rueckabbildung wie in der Konvertierung beobachtet (2->2, 3->3, 4/5->6, sonst 4)
BRUSH_TO_FRAMEWORK = {1: 4, 2: 2, 3: 3, 4: 6, 5: 6}


class BinFormatError(ValueError):
    """Datei ist keine gueltige/erwartete PENCILENGINE-Datei."""


@dataclass
class Point:
    x: float           # Seitenkoordinate (Breite = BinFile.width, meist 1000)
    y: float
    t_ms: int          # Millisekunden seit erstem Punkt des Strichs
    pressure: float    # 0..1
    extra: tuple       # drei weitere Floats (in allen Proben 0; Neigung o. ae., Annahme)
    role: int          # 4 erster, 5 letzter, 6 mittlerer Punkt
    index: float       # laufender Index im Strich (als float)


@dataclass
class Stroke:
    uuid: bytes
    start_time: int            # Epoche ms oder 0 (in Proben 0)
    first_index: float
    last_index: float
    flag_a: bool
    flag_b: bool
    link_index: int            # -1 = normal; andere Werte unbeobachtet
    brush_type: int
    brush_aux: int             # zweiter Int der Pinselstruktur (in Proben 0)
    red: float
    green: float
    blue: float
    intensity: float           # Feld "b" der Pinselstruktur (Proben: 1.0)
    alpha: float
    width: float
    brush_tail_int: int
    brush_flag: bool
    points: list[Point] = field(default_factory=list)

    @property
    def argb(self) -> int:
        def c(v: float) -> int:
            return max(0, min(255, int(round(v * 255))))
        return (c(self.alpha) << 24) | (c(self.red) << 16) | (c(self.green) << 8) | c(self.blue)

    @property
    def brush_name(self) -> str:
        return BRUSH_NAMES.get(self.brush_type, f"unknown_{self.brush_type}")

    @property
    def bounds(self) -> tuple[float, float, float, float]:
        xs = [p.x for p in self.points]
        ys = [p.y for p in self.points]
        return min(xs), min(ys), max(xs), max(ys)


@dataclass
class BinFile:
    file_version: tuple[int, int]
    file_int_a: int
    file_int_b: int
    create_time: int
    modify_time: int
    page_version: int
    page_minor: int
    page_uuid: bytes
    width: int
    height: int
    strokes: list[Stroke]
    groups: list[list[bytes]]   # Gruppen aus Strich-UUIDs (in allen Proben leer)
    trailer: bytes
    size: int

    def visible_strokes(self) -> list[Stroke]:
        """Annahme: Striche mit link_index != -1 oder in einer Gruppe gelten in HiNote nicht als normale Striche."""
        hidden = {u for g in self.groups for u in g}
        return [s for s in self.strokes if s.link_index == -1 and s.uuid not in hidden]


def _need(d: bytes, off: int, n: int, what: str) -> None:
    if n < 0 or off < 0 or off + n > len(d):
        raise BinFormatError(f"{what}: {n} Bytes ab {off} ueberschreiten Dateiende ({len(d)})")


def parse_legacy_bin(d: bytes) -> BinFile:
    """Altformat HWEINKBYTE (Doku 2.6) strikt parsen; liefert dieselbe Struktur wie parse_bin.

    x, y sind normiert 0..1 und werden mit Kopfbreite/-hoehe multipliziert; die absolute Zeit (i64) wird relativ
    zum ersten Punkt des Strichs; ARGB-Int wird in Float-Kanaele zerlegt; Rollen 0/2/1 -> 4/6/5.
    """
    n = len(d)
    _need(d, 0, 40, "Altkopf")
    hdr, = struct.unpack_from(">I", d, 12)
    n_sec, = struct.unpack_from(">i", d, 28)
    if hdr != 28 or n_sec != 1:
        raise BinFormatError(f"Altformat: Kopf {hdr}, Abschnitte {n_sec}")
    off, size = struct.unpack_from(">ii", d, 32)
    if off != hdr + 12 or size < 44:
        raise BinFormatError(f"Altformat: Abschnitt {off}/{size}")
    if off + size > n:
        raise BinFormatError("Altformat: Abschnitt ueberschreitet Dateiende")
    if off + size < n:
        raise BinFormatError(f"{n - off - size} Restbytes nach dem Abschnitt")
    if struct.unpack_from(">i", d, off)[0] != 28 or struct.unpack_from(">i", d, off + 28)[0] != 16:
        raise BinFormatError("Altformat: Abschnittskopf unerwartet")
    ctime, = struct.unpack_from(">q", d, off + 4)
    width, height = struct.unpack_from(">ii", d, off + 12)
    n_strokes, = struct.unpack_from(">i", d, off + 32)
    if width <= 0 or height <= 0:
        raise BinFormatError(f"Seitenmasse {width}x{height}")
    if n_strokes < 0 or n_strokes > MAX_STROKES:
        raise BinFormatError(f"zu viele Striche: {n_strokes}")
    p = off + 44
    if n_strokes * 36 > n - p:
        raise BinFormatError("Altformat: Strichzahl passt nicht in die Datei")
    role_map = {0: ROLE_FIRST, 1: ROLE_LAST, 2: ROLE_MID}
    strokes: list[Stroke] = []
    for i in range(n_strokes):
        _need(d, p, 36, f"Altstrich {i}")
        n_pts, _v1, _v2, brush, argb, wf = struct.unpack_from(">ihhiif", d, p)
        if n_pts < 0 or n_pts > MAX_POINTS_PER_STROKE:
            raise BinFormatError(f"Altstrich {i}: Punktzahl {n_pts}")
        p += 36
        _need(d, p, n_pts * 28, f"Altstrich {i} Punkte")
        argb &= 0xFFFFFFFF
        st = Stroke(b"", 0, 0.0, float(max(n_pts - 1, 0)), False, False, -1, brush, 0,
                    ((argb >> 16) & 255) / 255, ((argb >> 8) & 255) / 255, (argb & 255) / 255, 1.0,
                    ((argb >> 24) & 255) / 255, wf, 0, False)
        t0 = 0
        for k in range(n_pts):
            role, x, y, pr, e, t = struct.unpack_from(">iffffq", d, p)
            if k == 0:
                t0 = t
            st.points.append(Point(x * width, y * height, t - t0, pr, (e, 0.0, 0.0), role_map.get(role, role), float(k)))
            p += 28
        strokes.append(st)
    if p != off + size:
        raise BinFormatError(f"Altformat: Strichbereich endet bei {p}, Abschnitt bei {off + size}")
    return BinFile((1, 1), 0, 0, ctime, ctime, 1, 1, b"", width, height, strokes, [], b"", n)


def parse_bin(d: bytes) -> BinFile:
    """Parst eine .bin strikt (PENCILENGINE oder Altformat HWEINKBYTE). Wirft BinFormatError bei jeder Abweichung, auch bei Restbytes."""
    if d[:10] == LEGACY_MAGIC:
        return parse_legacy_bin(d)
    if d[:12] != MAGIC:
        raise BinFormatError("Kennung PENCILENGINE/HWEINKBYTE fehlt")
    _need(d, 12, 4, "Dateikopf")
    (fhs,) = struct.unpack_from(">I", d, 12)
    if fhs != FILE_HEADER_SIZE:
        raise BinFormatError(f"Dateikopf-Groesse {fhs} != {FILE_HEADER_SIZE}")
    _need(d, 0, fhs, "Dateikopf")
    v1, v2, ia, ib, ctime, mtime = struct.unpack_from(">hhIIqq", d, 16)
    o = fhs
    # Seitenkopf
    _need(d, o, PAGE_HEADER_SIZE, "Seitenkopf")
    (ph_size, ph_ver, ph_minor, ph_zero, data_size, page_uuid, basic_size, n_strokes, strokes_size, ph_last) = \
        struct.unpack_from(">IIHHI16sIIII", d, o)
    if ph_size != PAGE_HEADER_SIZE or basic_size != BASIC_CONTENT_SIZE:
        raise BinFormatError(f"Seitenkopf unerwartet: size={ph_size} basic={basic_size}")
    if n_strokes > MAX_STROKES:
        raise BinFormatError(f"zu viele Striche: {n_strokes}")
    body = o + ph_size            # Beginn Grundinhalt
    _need(d, body, BASIC_CONTENT_SIZE, "Grundinhalt")
    _, width, height = struct.unpack_from(">III", d, body)
    if width <= 0 or height <= 0:
        raise BinFormatError(f"Seitenmasse {width}x{height}")
    # strokes_size umfasst Grundinhalt (12) + alle Striche
    strokes_end = body + strokes_size
    p = body + BASIC_CONTENT_SIZE
    strokes: list[Stroke] = []
    for i in range(n_strokes):
        _need(d, p, STROKE_HEADER_SIZE, f"Strichkopf {i}")
        (sh_size, _s_ver, _s_a, _s_b, s_data, s_uuid, b_size, br_size, head_off, pts_size) = \
            struct.unpack_from(">IIHHI16sIIII", d, p)
        if (sh_size, b_size, br_size) != (STROKE_HEADER_SIZE, STROKE_BASIC_SIZE, STROKE_BRUSH_SIZE):
            raise BinFormatError(f"Strich {i}: Kopfgroessen {sh_size}/{b_size}/{br_size}")
        if head_off != b_size + br_size or s_data != head_off + pts_size:
            raise BinFormatError(f"Strich {i}: Groessen inkonsistent")
        _need(d, p, sh_size + s_data, f"Strich {i}")
        q = p + sh_size
        st_time, first_i, last_i, fa, fb, _res, link = struct.unpack_from(">qff??2si", d, q)
        (btype, baux, blue, green, red, inten, alpha, width_f, tail_i, bflag) = \
            struct.unpack_from(">iiffffffi?", d, q + b_size)
        c = q + head_off
        _, n_pts, psize = struct.unpack_from(">iii", d, c)
        if psize != POINT_SIZE or pts_size != POINT_BLOCK_HEAD + n_pts * psize or n_pts > MAX_POINTS_PER_STROKE:
            raise BinFormatError(f"Strich {i}: Punktblock inkonsistent (n={n_pts}, size={psize})")
        st = Stroke(s_uuid, st_time, first_i, last_i, fa, fb, link, btype, baux, red, green, blue, inten,
                    alpha, width_f, tail_i, bflag)
        for k in range(n_pts):
            x, y, t, pr, e1, e2, e3, role, idx = struct.unpack_from(">ffiffffif", d, c + POINT_BLOCK_HEAD + k * POINT_SIZE)
            # Feldfolge: x, y, t(int), pressure, 3 Floats, role(int), index(float)
            st.points.append(Point(x, y, t, pr, (e1, e2, e3), role, idx))
        strokes.append(st)
        p += sh_size + s_data
    if p != strokes_end:
        raise BinFormatError(f"Strichbereich endet bei {p}, Seitenkopf erwartet {strokes_end}")
    # Abschlussblock ("Verbindungs"-Abschnitt): 40 Byte Kopf, danach Gruppen aus Strich-UUIDs
    t = p
    _need(d, t, TRAILER_SIZE, "Abschlusskopf")
    (t_size, t_ver, t_minor, t_zero, t_a, t_uuid, t_x, n_groups) = struct.unpack_from(">IIHHI16sII", d, t)
    if t_size != TRAILER_SIZE:
        raise BinFormatError(f"Abschlusskopf-Groesse {t_size} != {TRAILER_SIZE}")
    q = t + t_size
    groups: list[list[bytes]] = []
    for gi in range(n_groups):
        _need(d, q, 16, f"Gruppe {gi}")
        g_count, g_entry = struct.unpack_from(">II", d, q)
        q += 16  # Zaehler, Eintragsgroesse, 8 reservierte Bytes
        if g_entry < 20:
            raise BinFormatError(f"Gruppe {gi}: Eintragsgroesse {g_entry}")
        _need(d, q, g_count * g_entry, f"Gruppe {gi} Eintraege")
        groups.append([bytes(d[q + k * g_entry:q + k * g_entry + 16]) for k in range(g_count)])
        q += g_count * g_entry
    if q != len(d):
        raise BinFormatError(f"{len(d) - q} Restbytes nach dem Abschlussblock")
    trailer = d[t:]
    if data_size != len(d) - fhs - ph_size:
        raise BinFormatError(f"Datengroesse im Seitenkopf {data_size} != {len(d) - fhs - ph_size}")
    return BinFile((v1, v2), ia, ib, ctime, mtime, ph_ver, ph_minor, page_uuid, width, height, strokes,
                   groups, trailer, len(d))


def check_plausible(b: BinFile) -> list[str]:
    """Liefert Warnungen (leere Liste = alles plausibel)."""
    w: list[str] = []
    for i, s in enumerate(b.strokes):
        if len(s.points) < 2:
            w.append(f"Strich {i}: weniger als 2 Punkte")
            continue
        x0, y0, x1, y1 = s.bounds
        if x0 < -1 or y0 < -1 or x1 > b.width + 1 or y1 > b.height + 1:
            w.append(f"Strich {i}: ausserhalb der Seite ({x0:.1f},{y0:.1f},{x1:.1f},{y1:.1f})")
        if [p.role for p in s.points] != [ROLE_FIRST] + [ROLE_MID] * (len(s.points) - 2) + [ROLE_LAST]:
            w.append(f"Strich {i}: Punktrollen unerwartet")
        ts = [p.t_ms for p in s.points]
        if ts[0] != 0 or any(a > c for a, c in zip(ts, ts[1:])):
            w.append(f"Strich {i}: Zeiten nicht monoton ab 0")
        if any(not (0.0 <= p.pressure <= 1.0) for p in s.points):
            w.append(f"Strich {i}: Druck ausserhalb 0..1")
        if s.brush_type not in BRUSH_NAMES:
            w.append(f"Strich {i}: unbekannter Pinseltyp {s.brush_type}")
        if s.link_index != -1:
            w.append(f"Strich {i}: link_index {s.link_index} (unbeobachtet)")
    return w


# ---------------------------------------------------------------- Container (.hinote = ZIP)

@dataclass
class HinotePage:
    page_id: str
    meta: dict
    bin: BinFile | None
    jpg: bytes | None


@dataclass
class HinoteNote:
    note_id: str
    meta: dict
    pages: list[HinotePage]


def _safe_name(name: str) -> bool:
    return not (name.startswith("/") or ".." in name.split("/") or "\\" in name)


def _read_limited(zf: zipfile.ZipFile, info: zipfile.ZipInfo) -> bytes:
    if info.file_size > MAX_ENTRY_BYTES:
        raise BinFormatError(f"{info.filename}: zu gross ({info.file_size})")
    with zf.open(info) as f:
        data = f.read(MAX_ENTRY_BYTES + 1)
    if len(data) > MAX_ENTRY_BYTES:
        raise BinFormatError(f"{info.filename}: entpackt zu gross")
    return data


def _gunzip_json(data: bytes) -> dict:
    with gzip.GzipFile(fileobj=io.BytesIO(data)) as g:
        raw = g.read(MAX_ENTRY_BYTES + 1)
    if len(raw) > MAX_ENTRY_BYTES:
        raise BinFormatError("JSON entpackt zu gross")
    return json.loads(raw.decode("utf-8"))


def read_hinote(path: str | Path) -> HinoteNote:
    """Liest einen .hinote-Export im Speicher (nichts wird auf die Platte entpackt)."""
    with zipfile.ZipFile(path) as zf:
        infos = [i for i in zf.infolist() if not i.is_dir()]
        if len(infos) > MAX_ZIP_ENTRIES or sum(i.file_size for i in infos) > MAX_TOTAL_BYTES:
            raise BinFormatError("ZIP ueberschreitet Grenzen")
        by_name = {}
        for i in infos:
            if not _safe_name(i.filename):
                raise BinFormatError(f"unsicherer ZIP-Pfad: {i.filename}")
            by_name[i.filename] = i
        note_entries = [n for n in by_name if "/" not in n and n.endswith(".jhinote") and n != "custom_md.jhinote"]
        if len(note_entries) != 1:
            raise BinFormatError(f"erwartet genau eine Notiz-.jhinote, gefunden {note_entries}")
        note_meta = _gunzip_json(_read_limited(zf, by_name[note_entries[0]]))["customNoteContent"]
        pages: list[HinotePage] = []
        for n in sorted(by_name):
            if n.startswith("pages/") and n.endswith(".jhinote"):
                pm = _gunzip_json(_read_limited(zf, by_name[n]))["customNotePageContent"]
                pid = pm["id"]
                if Path(n).stem != pid:
                    raise BinFormatError(f"Seiten-ID {pid} passt nicht zum Dateinamen {n}")
                bn, jn = f"files/{pid}.bin", f"files/{pid}.jpg"
                bin_ = parse_bin(_read_limited(zf, by_name[bn])) if bn in by_name else None
                jpg = _read_limited(zf, by_name[jn]) if jn in by_name else None
                pages.append(HinotePage(pid, pm, bin_, jpg))
        pages.sort(key=lambda p: p.meta.get("pageNumber", 0))
        return HinoteNote(note_meta["id"], note_meta, pages)


# ---------------------------------------------------------------- Rendering (optional, Pillow)

def render_page(b: BinFile, out_w: int, out_h: int, width_scale: float = 1.0):
    """Zeichnet die Striche auf weissen Grund. Seite (b.width x b.height) wird auf out_w x out_h gestreckt.

    Strichbreite: Annahme, `width` ist in Ansichtspixeln (Ansichtsbreite ~ 1404) angegeben;
    Skalierung = out_w / 1404 * width_scale.
    """
    from PIL import Image, ImageDraw
    ss = 2  # Supersampling
    img = Image.new("RGB", (out_w * ss, out_h * ss), "white")
    dr = ImageDraw.Draw(img, "RGBA")
    sx, sy = out_w * ss / b.width, out_h * ss / b.height
    for s in b.visible_strokes():
        col = (int(s.red * 255), int(s.green * 255), int(s.blue * 255),
               int(255 * (s.alpha if s.brush_type in (4, 5) else 1.0)))
        w = max(1.0, s.width * out_w * ss / 1404.0 * width_scale)
        pts = [(p.x * sx, p.y * sy) for p in s.points]
        dr.line(pts, fill=col, width=int(round(w)), joint="curve")
        r = w / 2
        for (x, y) in (pts[0], pts[-1]):
            dr.ellipse((x - r, y - r, x + r, y + r), fill=col)
    return img.resize((out_w, out_h), Image.LANCZOS)


# ---------------------------------------------------------------- CLI

def _iter_bins(arg: str) -> Iterator[tuple[str, BinFile, HinotePage | None]]:
    p = Path(arg)
    files = sorted(p.glob("*.hinote")) if p.is_dir() else [p]
    for f in files:
        if f.suffix == ".bin":
            yield f.name, parse_bin(f.read_bytes()), None
        else:
            note = read_hinote(f)
            for pg in note.pages:
                if pg.bin is not None:
                    yield f"{f.name}:{pg.page_id}", pg.bin, pg


def _cmd_check(args: list[str]) -> int:
    ok = bad = warn = 0
    for a in args:
        p = Path(a)
        files = sorted(p.glob("*.hinote")) if p.is_dir() else [p]
        for f in files:
            try:
                items = list(_iter_bins(str(f)))
                if not items:
                    raise BinFormatError("keine .bin im Export")
                for name, b, pg in items:
                    w = check_plausible(b)
                    pts = sum(len(s.points) for s in b.strokes)
                    extra = ""
                    if pg is not None:
                        ratio = pg.meta.get("pageRatio")
                        extra = f" pageRatio={ratio} bin={b.width / b.height:.4f}"
                    print(f"OK   {name}: {b.width}x{b.height}, {len(b.strokes)} Striche, {pts} Punkte,"
                          f" Restbytes=0{extra}" + (f" WARN {len(w)}: {w[:2]}" if w else ""))
                    warn += bool(w)
                ok += 1
            except Exception as e:  # noqa: BLE001 - CLI meldet jede Datei einzeln
                bad += 1
                print(f"FAIL {f}: {e}")
    print(f"{ok} Dateien fehlerfrei, {bad} fehlerhaft, {warn} Seiten mit Warnungen")
    return 0 if bad == 0 else 1


def _cmd_render(args: list[str]) -> int:
    if len(args) < 2:
        print(__doc__)
        return 2
    out = Path(args[-1])
    out.mkdir(parents=True, exist_ok=True)
    from PIL import Image
    n = 0
    for a in args[:-1]:
        for name, b, pg in _iter_bins(a):
            stem = name.replace(":", "_").replace(" ", "_").replace(".hinote", "")
            if pg is not None and pg.jpg:
                ref = Image.open(io.BytesIO(pg.jpg)).convert("RGB")
                mine = render_page(b, ref.width, ref.height)
                scale = min(1.0, 400 / ref.width)
                sz = (int(ref.width * scale), int(ref.height * scale))
                both = Image.new("RGB", (sz[0] * 2 + 6, sz[1]), (200, 0, 0))
                both.paste(ref.resize(sz, Image.LANCZOS), (0, 0))
                both.paste(mine.resize(sz, Image.LANCZOS), (sz[0] + 6, 0))
                both.save(out / f"{stem}.jpg", quality=80)
            else:
                render_page(b, 400, int(400 * b.height / b.width)).save(out / f"{stem}.png")
            n += 1
    print(f"{n} Seiten nach {out} gerendert (links JPG des Exports, rechts eigenes Rendering)")
    return 0


def _cmd_json(args: list[str]) -> int:
    b = parse_bin(Path(args[0]).read_bytes())
    print(json.dumps({"width": b.width, "height": b.height, "strokes": [
        {"brush": s.brush_name, "argb": f"{s.argb:08x}", "width": s.width,
         "points": [[p.x, p.y, p.t_ms, p.pressure] for p in s.points]} for s in b.strokes]}))
    return 0


def main(argv: list[str]) -> int:
    if len(argv) < 3 or argv[1] not in ("check", "render", "json"):
        print(__doc__)
        return 2
    return {"check": _cmd_check, "render": _cmd_render, "json": _cmd_json}[argv[1]](argv[2:])


if __name__ == "__main__":
    sys.exit(main(sys.argv))
