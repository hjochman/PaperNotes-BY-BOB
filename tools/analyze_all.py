#!/usr/bin/env python3
"""Sammelt Vorkommen von Elementen/Pinseln/Farben/Vorlagen ueber alle Exporte in tmp/device/exp/all."""
import collections, gzip, json, struct, subprocess, sys, tempfile, zipfile
from pathlib import Path


def parse_legacy(d):
    """HWEINKBYTE (Altformat, Big-Endian) -> (Strichliste, Restbytes). Layout aus dem Hexdump."""
    assert d[:10] == b"HWEINKBYTE"
    hdr, _, _ = struct.unpack(">iii", d[12:24])
    ver, sub, nsec = struct.unpack(">hhi", d[24:32])
    secs = [struct.unpack(">ii", d[32 + 8 * i:40 + 8 * i]) for i in range(nsec)]
    off0, size0 = secs[0]
    assert off0 == hdr + 12, (off0, hdr)
    pos = off0
    w, h = struct.unpack(">ii", d[pos + 12:pos + 20]); nstr = struct.unpack(">i", d[pos + 32:pos + 36])[0]
    pos += 44  # Abschnittskopf: int, long, 8 x int
    strokes = []
    for _ in range(nstr):
        n, v1, v2, brush, argb, width, x1, y1, x2, y2 = struct.unpack(">ihhiifffff", d[pos:pos + 36]); pos += 36
        pts = []
        for _ in range(n):
            role, x, y, pr, f, t = struct.unpack(">iffffq", d[pos:pos + 28]); pos += 28
            pts.append((x, y, pr, role, t))
        strokes.append({"brush": brush, "argb": argb & 0xffffffff, "width": width, "points": pts})
    return strokes, len(d) - (off0 + size0), len(d) - pos, (w, h)

ALL = Path(__file__).resolve().parent.parent / "tmp" / "device" / "exp" / "all"
HB = Path(__file__).resolve().parent / "hinote_bin.py"
idx = json.loads((ALL / "index.json").read_text())

bg_note = collections.Counter(); bg_page = collections.Counter()
keys = collections.defaultdict(list)       # Feld -> [(Notiz, Seite, Wert)]
pagekeys = collections.Counter()
brush = collections.Counter(); colors = collections.Counter(); widths = collections.Counter()
nonempty = collections.defaultdict(list)
n_pages = n_bins = n_strokes = 0
extra_files = collections.Counter()
ratios = collections.Counter(); notekeys = collections.Counter()
flags = collections.Counter()
legacy = collections.Counter(); legacy_notes = set()
lbrush = collections.Counter(); lcolors = collections.Counter(); lwidth = collections.Counter(); lroles = collections.Counter(); lstrokes_n = [0]; legacy_rest = []; legacy_wh = collections.Counter()
for e in idx:
    f = ALL / e["file"]
    z = zipfile.ZipFile(f)
    for n in z.namelist():
        if n.endswith("/"): continue
        if n.startswith("files/"): extra_files[n.rsplit(".", 1)[-1]] += 1
        elif not n.startswith("pages/") and not n.endswith(".jhinote"): extra_files["root:" + n] += 1
    for n in z.namelist():
        if n.startswith("pages/") and n.endswith(".jhinote"):
            n_pages += 1
            d = json.loads(gzip.decompress(z.read(n)))
            p = d["customNotePageContent"]
            bg_page[p.get("background")] += 1
            ratios[str(p.get("pageRatio"))] += 1
            for k in ("pageElement", "pageBookMarks", "recordItemEntities", "attachment", "outLineAttachment"):
                v = p.get(k)
                if v: nonempty[k].append((e["title"], p["pageNumber"], v if k != "attachment" else len(v)))
            for k in ("pageType", "pageColor", "pageOrientation", "bkgAttachmentIndex", "isDelete", "lastPageTag"):
                flags[f"page.{k}={p.get(k)}"] += 1
            pagekeys.update(p.keys())
            if len(p.get("attachment") or []) > 1:
                nonempty["attachment>1"].append((e["title"], p["pageNumber"], len(p["attachment"])))
            for a in p.get("attachment") or []:
            	flags[f"att.attachType={a.get('attachType')} data1={a.get('data1')}"] += 1
        elif n.endswith(".jhinote") and "/" not in n and n != "custom_md.jhinote":
            c = json.loads(gzip.decompress(z.read(n)))["customNoteContent"]
            bg_note[c.get("background")] += 1
            notekeys.update(c.keys())
            for k in ("attachment", "outLineAttachment", "recordItemEntities"):
                if c.get(k): nonempty["note." + k].append((e["title"], c[k]))
            for k in ("isFavorite", "isTop", "noteType", "pageColor", "pageOrientation", "hasCover", "noteIcon", "data1", "deleteTag", "createTime", "modifiedTime"):
                flags[f"note.{k}={c.get(k)}"] += 1
    # bins
    with tempfile.TemporaryDirectory() as td:
        for n in z.namelist():
            if n.startswith("files/") and n.endswith(".bin"):
                data = z.read(n); n_bins += 1
                if not data.startswith(b"PENCILENGINE"):
                    legacy[data[:12]] += 1; legacy_notes.add(e["title"])
                    sts, rest_sec, rest_pos, wh = parse_legacy(data)
                    legacy_rest.append((rest_sec, rest_pos)); legacy_wh[wh] += 1
                    for st in sts:
                        lbrush[st["brush"]] += 1; lcolors["%08x" % st["argb"]] += 1; lwidth[round(st["width"], 2)] += 1
                        lroles.update(p[3] for p in st["points"]); lstrokes_n[0] += 1
                    continue
                b = Path(td) / "x.bin"; b.write_bytes(data)
                out = subprocess.run([sys.executable, str(HB), "json", str(b)], capture_output=True, text=True)
                if out.returncode:
                    print("json FAIL", e["file"], n, out.stderr[-200:]); continue
                j = json.loads(out.stdout)
                for s in j["strokes"]:
                    n_strokes += 1
                    brush[s["brush"]] += 1; colors[s["argb"]] += 1; widths[round(s["width"], 2)] += 1
                    # Punkte-Eigenschaften nicht noetig

print(f"Notizen {len(idx)}, Seiten {n_pages}, bins {n_bins}, Striche {n_strokes}")
print("note.background", dict(bg_note)); print("page.background", dict(bg_page))
print("pageRatio", dict(ratios))
print("brush", dict(brush)); print("colors", dict(colors)); print("widths", dict(sorted(widths.items())))
print("zip-Dateitypen", dict(extra_files))
print("nonempty:")
for k, v in nonempty.items(): print(" ", k, len(v), str(v[:3])[:600])
print("flags:"); [print(" ", k, v) for k, v in sorted(flags.items())]
print("pagekeys", dict(pagekeys)); print("notekeys", dict(notekeys))
print("Altformat-bins", dict(legacy), sorted(legacy_notes))
print("Altformat: Striche", lstrokes_n[0], "brush", dict(lbrush), "colors", dict(lcolors), "widths", dict(sorted(lwidth.items())), "roles", dict(lroles), "WxH", dict(legacy_wh), "Rest(sec,pos) !=0:", [r for r in legacy_rest if r != (0, 0)][:5])
