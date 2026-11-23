"""
Extrae una región (bbox) de un archivo PMTiles v3 remoto (por ej. el build diario
de Protomaps) y la guarda como un PMTiles local, usando solo peticiones HTTP Range.

Uso:
  python tools/pmtiles_extract.py <url> <salida.pmtiles> --bbox minLon,minLat,maxLon,maxLat --maxzoom 14
"""
import argparse
import gzip
import io
import json
import math
import struct
import sys
import time
from concurrent.futures import ThreadPoolExecutor

import requests

UA = {"User-Agent": "pmtiles-extract-py/1.0"}
SESSION = requests.Session()


# ---------------------------------------------------------------- HTTP ----
def fetch(url, offset, length, tries=5):
    for i in range(tries):
        try:
            r = SESSION.get(url, headers={**UA, "Range": f"bytes={offset}-{offset + length - 1}"}, timeout=60)
            if r.status_code == 206 and len(r.content) == length:
                return r.content
            raise IOError(f"HTTP {r.status_code}, {len(r.content)} bytes")
        except Exception as e:  # noqa: BLE001
            if i == tries - 1:
                raise
            time.sleep(1 + i * 2)
            print(f"  reintento ({e})", file=sys.stderr)


# ------------------------------------------------------------- varints ----
def read_varint(buf, pos):
    result = 0
    shift = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if b < 0x80:
            return result, pos
        shift += 7


def write_varint(out, v):
    while v >= 0x80:
        out.append((v & 0x7F) | 0x80)
        v >>= 7
    out.append(v)


# ------------------------------------------------------------- tile ids ---
def zxy_to_id(z, x, y):
    acc = ((1 << (2 * z)) - 1) // 3  # tiles de zooms anteriores
    n = 1 << z
    d = 0
    s = n >> 1
    while s > 0:
        rx = 1 if (x & s) else 0
        ry = 1 if (y & s) else 0
        d += s * s * ((3 * rx) ^ ry)
        if ry == 0:
            if rx == 1:
                x = n - 1 - x
                y = n - 1 - y
            x, y = y, x
        s >>= 1
    return acc + d


def lonlat_to_tile(lon, lat, z):
    n = 1 << z
    x = int((lon + 180.0) / 360.0 * n)
    lat_r = math.radians(lat)
    y = int((1.0 - math.asinh(math.tan(lat_r)) / math.pi) / 2.0 * n)
    return min(max(x, 0), n - 1), min(max(y, 0), n - 1)


# ------------------------------------------------------------ directory ---
def decompress(data, comp):
    if comp == 2:
        return gzip.decompress(data)
    if comp in (0, 1):
        return data
    raise ValueError(f"compresión interna no soportada: {comp}")


def parse_directory(buf):
    n, pos = read_varint(buf, 0)
    ids, runs, lens, offs = [0] * n, [0] * n, [0] * n, [0] * n
    last = 0
    for i in range(n):
        v, pos = read_varint(buf, pos)
        last += v
        ids[i] = last
    for i in range(n):
        runs[i], pos = read_varint(buf, pos)
    for i in range(n):
        lens[i], pos = read_varint(buf, pos)
    for i in range(n):
        v, pos = read_varint(buf, pos)
        if v == 0 and i > 0:
            offs[i] = offs[i - 1] + lens[i - 1]
        else:
            offs[i] = v - 1
    return list(zip(ids, runs, lens, offs))


def serialize_directory(entries):
    out = bytearray()
    write_varint(out, len(entries))
    last = 0
    for e in entries:
        write_varint(out, e[0] - last)
        last = e[0]
    for e in entries:
        write_varint(out, e[1])
    for e in entries:
        write_varint(out, e[2])
    for i, e in enumerate(entries):
        if i > 0 and e[3] == entries[i - 1][3] + entries[i - 1][2]:
            write_varint(out, 0)
        else:
            write_varint(out, e[3] + 1)
    return gzip.compress(bytes(out), compresslevel=9, mtime=0)


HEADER_FMT = "<7sB QQQQQQQQ QQQ BBBBBB iiii B ii"


def parse_header(b):
    f = struct.unpack(HEADER_FMT, b[:127])
    keys = ["magic", "version", "root_off", "root_len", "meta_off", "meta_len", "leaf_off", "leaf_len",
            "data_off", "data_len", "addressed", "entries", "contents", "clustered", "internal_comp",
            "tile_comp", "tile_type", "min_zoom", "max_zoom", "min_lon", "min_lat", "max_lon", "max_lat",
            "center_zoom", "center_lon", "center_lat"]
    h = dict(zip(keys, f))
    if h["magic"] != b"PMTiles" or h["version"] != 3:
        raise ValueError("no es un PMTiles v3")
    return h


# ----------------------------------------------------------------- main ---
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("url")
    ap.add_argument("out")
    ap.add_argument("--bbox", required=True)
    ap.add_argument("--maxzoom", type=int, default=14)
    ap.add_argument("--threads", type=int, default=8)
    ap.add_argument("--dry-run", action="store_true", help="solo calcular el tamaño, sin descargar")
    a = ap.parse_args()
    min_lon, min_lat, max_lon, max_lat = map(float, a.bbox.split(","))

    head = fetch(a.url, 0, 16384)
    h = parse_header(head)
    comp = h["internal_comp"]
    root = parse_directory(decompress(head[h["root_off"]:h["root_off"] + h["root_len"]], comp))
    meta_raw = fetch(a.url, h["meta_off"], h["meta_len"]) if h["meta_len"] else b"{}"
    maxz = min(a.maxzoom, h["max_zoom"])

    # 1) Ids de tiles dentro del bbox
    wanted = []
    for z in range(h["min_zoom"], maxz + 1):
        x0, y0 = lonlat_to_tile(min_lon, max_lat, z)
        x1, y1 = lonlat_to_tile(max_lon, min_lat, z)
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                wanted.append(zxy_to_id(z, x, y))
    wanted.sort()
    print(f"tiles en bbox: {len(wanted)} (z{h['min_zoom']}-{maxz})")

    # 2) Resolver directorios (raíz + hojas, con caché)
    leaf_cache = {}

    def find(entries, tid):
        lo, hi = 0, len(entries) - 1
        while lo <= hi:
            mid = (lo + hi) // 2
            if entries[mid][0] <= tid:
                lo = mid + 1
            else:
                hi = mid - 1
        return entries[hi] if hi >= 0 else None

    def lookup(tid):
        entries = root
        for _ in range(4):
            e = find(entries, tid)
            if e is None:
                return None
            if e[1] == 0:  # puntero a hoja
                key = (e[3], e[2])
                if key not in leaf_cache:
                    raw = fetch(a.url, h["leaf_off"] + e[3], e[2])
                    leaf_cache[key] = parse_directory(decompress(raw, comp))
                entries = leaf_cache[key]
                continue
            if tid < e[0] + e[1]:
                return e[3], e[2]
            return None
        return None

    found = []  # (tid, src_off, length)
    for i, tid in enumerate(wanted):
        r = lookup(tid)
        if r:
            found.append((tid, r[0], r[1]))
        if i % 20000 == 0:
            print(f"  directorios: {i}/{len(wanted)} (hojas leídas: {len(leaf_cache)})")
    print(f"tiles existentes: {len(found)}, hojas leídas: {len(leaf_cache)}")

    # 3) Descargar contenidos únicos agrupando rangos cercanos
    uniq = sorted({(o, l) for _, o, l in found})
    total = sum(l for _, l in uniq)
    print(f"contenidos únicos: {len(uniq)}, {total / 1e6:.1f} MB")
    if a.dry_run:
        return
    ranges, cur = [], None
    for o, l in uniq:
        if cur and o - (cur[0] + cur[1]) < 256 * 1024 and cur[1] < 16 * 1024 * 1024:
            cur[1] = max(cur[1], o + l - cur[0])
            cur[2].append((o, l))
        else:
            cur = [o, l, [(o, l)]]
            ranges.append(cur)
    print(f"rangos HTTP: {len(ranges)}")

    blobs = {}
    done = [0]

    def get_range(rg):
        data = fetch(a.url, h["data_off"] + rg[0], rg[1])
        for o, l in rg[2]:
            blobs[(o, l)] = data[o - rg[0]:o - rg[0] + l]
        done[0] += 1
        if done[0] % 50 == 0:
            print(f"  descargados {done[0]}/{len(ranges)} rangos")

    with ThreadPoolExecutor(a.threads) as ex:
        list(ex.map(get_range, ranges))

    # 4) Escribir archivo agrupado (clustered), contenidos deduplicados
    data = io.BytesIO()
    new_off = {}
    entries = []
    for tid, o, l in found:  # found está ordenado por tid
        key = (o, l)
        if key not in new_off:
            new_off[key] = data.tell()
            data.write(blobs[key])
        off = new_off[key]
        if entries and entries[-1][0] + entries[-1][1] == tid and entries[-1][3] == off:
            entries[-1][1] += 1
        else:
            entries.append([tid, 1, l, off])
    data_bytes = data.getvalue()

    # directorio: raíz sola si entra, si no raíz + hojas
    root_bytes = serialize_directory(entries)
    leaves_bytes = b""
    if len(root_bytes) > 16384 - 127:
        leaf_size = 4096
        while True:
            leaves = bytearray()
            root_entries = []
            for i in range(0, len(entries), leaf_size):
                chunk = entries[i:i + leaf_size]
                ser = serialize_directory(chunk)
                root_entries.append([chunk[0][0], 0, len(ser), len(leaves)])
                leaves += ser
            root_bytes = serialize_directory(root_entries)
            if len(root_bytes) <= 16384 - 127:
                leaves_bytes = bytes(leaves)
                break
            leaf_size *= 2

    meta_bytes = gzip.compress(decompress(meta_raw, comp), mtime=0)
    root_off = 127
    meta_off = root_off + len(root_bytes)
    leaf_off = meta_off + len(meta_bytes)
    data_off = leaf_off + len(leaves_bytes)
    header = struct.pack(
        HEADER_FMT, b"PMTiles", 3,
        root_off, len(root_bytes), meta_off, len(meta_bytes), leaf_off, len(leaves_bytes),
        data_off, len(data_bytes), len(found), len(entries), len(new_off),
        1, 2, h["tile_comp"], h["tile_type"], h["min_zoom"], maxz,
        int(min_lon * 1e7), int(min_lat * 1e7), int(max_lon * 1e7), int(max_lat * 1e7),
        min(maxz, 10), int((min_lon + max_lon) / 2 * 1e7), int((min_lat + max_lat) / 2 * 1e7),
    )
    with open(a.out, "wb") as f:
        f.write(header + root_bytes + meta_bytes + leaves_bytes + data_bytes)
    print(f"listo: {a.out} ({(data_off + len(data_bytes)) / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
