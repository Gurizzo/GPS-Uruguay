"""
Agrega los edificios que faltan en OpenStreetMap usando Overture Maps (que incluye los
edificios detectados por satélite de Google Open Buildings y Microsoft).

Entrada (descargar antes con la herramienta de Overture, ver README):
  data-build/ov_buildings.parquet       edificios del recuadro de Uruguay
  data-build/ov_division_area.parquet   contornos de países (para filtrar Uruguay)

Salida:
  app/src/main/assets/buildings.pmtiles  capa "buildings" en zoom 15 (solo los que NO son de OSM)

Uso:  tools/.venv/Scripts/python tools/build_buildings.py
"""
import gzip
import json
import math
import os
import struct
import sys
from collections import defaultdict

import pyarrow.parquet as pq
import shapely

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pmtiles_extract import HEADER_FMT, serialize_directory, zxy_to_id  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DB = os.path.join(ROOT, "data-build")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "buildings.pmtiles")
Z = 15
EXTENT = 4096
N = 1 << Z
DEFAULT_HEIGHT = 5  # metros: una casa de una planta y algo


# ------------------------------------------------------------ protobuf ---
def varint(v, out):
    while v >= 0x80:
        out.append((v & 0x7F) | 0x80)
        v >>= 7
    out.append(v)


def key(field, wt, out):
    varint((field << 3) | wt, out)


def lendelim(field, payload, out):
    key(field, 2, out)
    varint(len(payload), out)
    out += payload


def zz(n):
    return (n << 1) ^ (n >> 31)


# ----------------------------------------------------------- geometría ---
def to_tile_px(lon, lat):
    x = (lon + 180.0) / 360.0 * N
    y = (1.0 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2.0 * N
    return x, y


def encode_ring(ring_px):
    """ring_px: lista de (x, y) enteros en coordenadas del tile, sin repetir el primero al final."""
    # anillo exterior: área positiva (sentido horario con Y hacia abajo)
    area = 0
    for i in range(len(ring_px)):
        x1, y1 = ring_px[i]
        x2, y2 = ring_px[(i + 1) % len(ring_px)]
        area += x1 * y2 - x2 * y1
    if area == 0:
        return None
    if area < 0:
        ring_px = ring_px[::-1]
    out = []
    cx = cy = 0
    x, y = ring_px[0]
    out += [(1 & 7) | (1 << 3), zz(x - cx), zz(y - cy)]
    cx, cy = x, y
    out.append((2 & 7) | ((len(ring_px) - 1) << 3))
    for x, y in ring_px[1:]:
        out += [zz(x - cx), zz(y - cy)]
        cx, cy = x, y
    out.append((7 & 7) | (1 << 3))
    return out


def main():
    # --- contorno de Uruguay (tierra)
    div = pq.read_table(os.path.join(DB, "ov_division_area.parquet")).to_pylist()
    uy = [d for d in div if d.get("country") == "UY" and d.get("subtype") == "country"]
    land = [d for d in uy if d.get("class") == "land"] or uy
    if not land:
        sys.exit("No se encontró el contorno de Uruguay en ov_division_area.parquet")
    border = shapely.union_all([shapely.from_wkb(d["geometry"]) for d in land]).buffer(0.002)
    shapely.prepare(border)
    print("contorno de Uruguay listo")

    pf = pq.ParquetFile(os.path.join(DB, "ov_buildings.parquet"))
    cols = [c for c in ("geometry", "height", "num_floors", "sources") if c in pf.schema_arrow.names]
    tiles = defaultdict(list)  # (x, y) -> [(geom_cmds, height)]
    stats = defaultdict(int)
    for batch in pf.iter_batches(batch_size=200_000, columns=cols):
        d = batch.to_pydict()
        geoms = shapely.from_wkb(d["geometry"])
        cent = shapely.centroid(geoms)
        cx, cy = shapely.get_x(cent), shapely.get_y(cent)
        inside = shapely.contains_xy(border, cx, cy)
        for i, g in enumerate(geoms):
            stats["total"] += 1
            if not inside[i]:
                stats["fuera de Uruguay"] += 1
                continue
            src = d["sources"][i] or []
            if any((s or {}).get("dataset") == "OpenStreetMap" for s in src):
                stats["ya en OpenStreetMap"] += 1
                continue
            h = d.get("height", [None] * len(geoms))[i]
            if h is None:
                fl = d.get("num_floors", [None] * len(geoms))[i]
                h = fl * 3 if fl else DEFAULT_HEIGHT
            h = max(2, min(int(round(h)), 250))
            tx, ty = to_tile_px(cx[i], cy[i])
            tx, ty = int(tx), int(ty)
            polys = [g] if g.geom_type == "Polygon" else list(getattr(g, "geoms", []))
            for p in polys:
                coords = list(p.exterior.coords)[:-1]
                ring = []
                for lon, lat in coords:
                    px, py = to_tile_px(lon, lat)
                    q = (int(round((px - tx) * EXTENT)), int(round((py - ty) * EXTENT)))
                    if not ring or ring[-1] != q:
                        ring.append(q)
                if len(ring) >= 2 and ring[0] == ring[-1]:
                    ring.pop()
                if len(ring) < 3:
                    stats["muy chico"] += 1
                    continue
                cmds = encode_ring(ring)
                if cmds:
                    tiles[(tx, ty)].append((cmds, h))
                    stats["agregados"] += 1
        print(f"  procesados {stats['total']:,} …", dict(stats))

    # --- codificar tiles
    entries, data = [], bytearray()
    for (x, y) in sorted(tiles, key=lambda t: zxy_to_id(Z, t[0], t[1])):
        feats = tiles[(x, y)]
        heights = sorted({h for _, h in feats})
        hidx = {h: i for i, h in enumerate(heights)}
        layer = bytearray()
        key(15, 0, layer); varint(2, layer)
        lendelim(1, b"buildings", layer)
        for cmds, h in feats:
            f = bytearray()
            tags = bytearray(); varint(0, tags); varint(hidx[h], tags)
            lendelim(2, bytes(tags), f)
            key(3, 0, f); varint(3, f)  # POLYGON
            geo = bytearray()
            for c in cmds:
                varint(c, geo)
            lendelim(4, bytes(geo), f)
            lendelim(2, bytes(f), layer)
        lendelim(3, b"height", layer)
        for h in heights:
            v = bytearray(); key(5, 0, v); varint(h, v)
            lendelim(4, bytes(v), layer)
        key(5, 0, layer); varint(EXTENT, layer)
        tile = bytearray()
        lendelim(3, bytes(layer), tile)
        blob = gzip.compress(bytes(tile), compresslevel=9, mtime=0)
        entries.append([zxy_to_id(Z, x, y), 1, len(blob), len(data)])
        data += blob

    root = serialize_directory(entries)
    leaves = b""
    if len(root) > 16384 - 127:
        size = 4096
        while True:
            lv, re_ = bytearray(), []
            for i in range(0, len(entries), size):
                ser = serialize_directory(entries[i:i + size])
                re_.append([entries[i][0], 0, len(ser), len(lv)])
                lv += ser
            root = serialize_directory(re_)
            if len(root) <= 16384 - 127:
                leaves = bytes(lv)
                break
            size *= 2
    meta = gzip.compress(json.dumps({
        "name": "Edificios detectados por satélite (Overture Maps)",
        "attribution": "© Overture Maps Foundation, Google Open Buildings, Microsoft",
        "vector_layers": [{"id": "buildings", "fields": {"height": "Number"}, "minzoom": Z, "maxzoom": Z}],
    }).encode(), mtime=0)
    ro = 127
    mo = ro + len(root)
    lo = mo + len(meta)
    do = lo + len(leaves)
    header = struct.pack(HEADER_FMT, b"PMTiles", 3, ro, len(root), mo, len(meta), lo, len(leaves),
                         do, len(data), len(entries), len(entries), len(entries),
                         1, 2, 2, 1, Z, Z,
                         int(-58.5e7), int(-35.1e7), int(-53.05e7), int(-30.05e7), Z, int(-56.19e7), int(-34.9e7))
    with open(OUT, "wb") as f:
        f.write(header + root + meta + leaves + data)
    print(f"listo: {OUT} — {stats['agregados']:,} edificios en {len(entries):,} tiles, {(do + len(data)) / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
