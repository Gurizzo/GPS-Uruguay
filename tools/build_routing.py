"""
Genera los datos de navegación a partir de las calles de OpenStreetMap ya descargadas
por build_data.py (data-build/osm_roads_*.json):

  app/src/main/assets/graph.bin     red vial para calcular rutas (formato binario)
  app/src/main/assets/search.json   lugares, calles y negocios para el buscador

Uso:  python tools/build_routing.py   (después de build_data.py)
"""
import glob
import json
import math
import os
import struct
import unicodedata
from collections import defaultdict

from build_data import ASSETS, CACHE, AREA, overpass, parse_speed, DEFAULTS

# Clases de vía (índice guardado en el grafo)
CLASSES = ["motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential",
           "living_street", "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link"]
# Velocidad real promedio respecto del límite (para estimar tiempos)
FACTOR = {"motorway": 0.92, "trunk": 0.9, "primary": 0.8, "secondary": 0.78, "tertiary": 0.72,
          "unclassified": 0.65, "residential": 0.6, "living_street": 0.5}


def limit_for(t):
    hw = t.get("highway", "")
    s = parse_speed(t.get("maxspeed"))
    if s is None:
        fw, bw = parse_speed(t.get("maxspeed:forward")), parse_speed(t.get("maxspeed:backward"))
        s = min(x for x in (fw, bw) if x) if (fw or bw) else None
    if s is None:
        s = parse_speed(t.get("zone:maxspeed") or t.get("source:maxspeed"))
    if s is None:
        if hw in DEFAULTS:
            s = DEFAULTS[hw]
        elif hw.split("_")[0] in ("primary", "secondary", "tertiary"):
            s = 90 if t.get("ref") and not hw.endswith("_link") else 45
        else:
            s = 45
    return max(5, min(s, 130))


def display_name(t):
    name = t.get("name") or t.get("short_name") or ""
    ref = t.get("ref", "")
    if not name and ref:
        first = ref.split(";")[0].strip()
        name = f"Ruta {first}" if first[:1].isdigit() else first
    return name


def haversine(a_lat, a_lon, b_lat, b_lon):
    r = 6371008.8
    p1, p2 = math.radians(a_lat), math.radians(b_lat)
    dp, dl = p2 - p1, math.radians(b_lon - a_lon)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def norm(s):
    s = unicodedata.normalize("NFD", s.lower())
    return "".join(c for c in s if unicodedata.category(c) != "Mn")


def load_ways():
    seen, ways = set(), []
    for f in sorted(glob.glob(os.path.join(CACHE, "osm_roads_*.json"))):
        with open(f, encoding="utf-8") as fh:
            for e in json.load(fh)["elements"]:
                if e["id"] in seen or len(e.get("geometry") or []) < 2:
                    continue
                seen.add(e["id"])
                t = e.get("tags", {})
                if t.get("access") in ("no", "private") or t.get("motor_vehicle") in ("no", "private") \
                        or t.get("motorcar") in ("no", "private") or t.get("area") == "yes":
                    continue
                ways.append(e)
    return ways


def build_graph(ways):
    key = lambda p: (round(p["lat"] * 1e7), round(p["lon"] * 1e7))
    count = defaultdict(int)
    for w in ways:
        g = w["geometry"]
        for i, p in enumerate(g):
            count[key(p)] += 2 if i in (0, len(g) - 1) else 1

    node_id = {}
    node_lat, node_lon = [], []

    def nid(p):
        k = key(p)
        if k not in node_id:
            node_id[k] = len(node_lat)
            node_lat.append(round(p["lat"] * 1e6))
            node_lon.append(round(p["lon"] * 1e6))
        return node_id[k]

    names, name_idx = [""], {"": 0}
    E = defaultdict(list)  # columnas de aristas
    g_lat, g_lon = [], []
    for w in ways:
        t = w["tags"]
        hw = t["highway"]
        cls = CLASSES.index(hw) if hw in CLASSES else CLASSES.index("residential")
        limit = limit_for(t)
        base = hw.replace("_link", "")
        travel = max(10, limit * FACTOR.get(base, 0.65) * (0.8 if hw.endswith("_link") else 1))
        ow = t.get("oneway", "")
        roundabout = t.get("junction") in ("roundabout", "circular")
        flags = 0
        reverse = ow == "-1"
        if ow in ("yes", "1", "true", "-1") or roundabout or (hw == "motorway" and ow != "no"):
            flags |= 1
        if roundabout:
            flags |= 2
        if hw.endswith("_link"):
            flags |= 4
        nm = display_name(t)
        if nm not in name_idx:
            name_idx[nm] = len(names)
            names.append(nm)
        geom = w["geometry"][::-1] if reverse else w["geometry"]
        start = 0
        for i in range(1, len(geom)):
            if i == len(geom) - 1 or count[key(geom[i])] >= 2:
                seg = geom[start:i + 1]
                length = sum(haversine(seg[j]["lat"], seg[j]["lon"], seg[j + 1]["lat"], seg[j + 1]["lon"])
                             for j in range(len(seg) - 1))
                a, b = nid(seg[0]), nid(seg[-1])
                if a != b or length > 1:
                    E["from"].append(a)
                    E["to"].append(b)
                    E["len"].append(max(0.5, length))
                    E["speed"].append(int(round(travel)))
                    E["limit"].append(limit)
                    E["flags"].append(flags)
                    E["cls"].append(cls)
                    E["name"].append(name_idx[nm])
                    E["gstart"].append(len(g_lat))
                    E["gcount"].append(len(seg) - 2)
                    E["way"].append(w["id"])
                    for p in seg[1:-1]:
                        g_lat.append(round(p["lat"] * 1e6))
                        g_lon.append(round(p["lon"] * 1e6))
                start = i

    pairs, triples = build_restrictions(node_id, E)

    n, m, gn = len(node_lat), len(E["from"]), len(g_lat)
    out = bytearray(b"RGRF")
    out += struct.pack("<5I", 2, n, m, gn, len(names))
    out += struct.pack(f"<{n}i", *node_lat) + struct.pack(f"<{n}i", *node_lon)
    out += struct.pack(f"<{m}i", *E["from"]) + struct.pack(f"<{m}i", *E["to"])
    out += struct.pack(f"<{m}f", *E["len"])
    out += struct.pack(f"<{m}i", *E["name"]) + struct.pack(f"<{m}i", *E["gstart"])
    out += struct.pack(f"<{m}H", *E["gcount"])
    out += bytes(E["speed"]) + bytes(E["limit"]) + bytes(E["flags"]) + bytes(E["cls"])
    out += struct.pack(f"<{gn}i", *g_lat) + struct.pack(f"<{gn}i", *g_lon)
    for s in names:
        b = s.encode("utf-8")
        out += struct.pack("<H", len(b)) + b
    # v2: prohibiciones de giro
    out += struct.pack("<I", len(pairs))
    for col in range(3):
        out += struct.pack(f"<{len(pairs)}i", *[p[col] for p in pairs])
    out += struct.pack("<I", len(triples))
    for col in range(3):
        out += struct.pack(f"<{len(triples)}i", *[t[col] for t in triples])
    with open(os.path.join(ASSETS, "graph.bin"), "wb") as f:
        f.write(out)
    print(f"grafo: {n} nodos, {m} aristas, {gn} puntos intermedios, {len(names)} nombres, {len(out) / 1e6:.1f} MB")
    return node_lat, node_lon, E, names


def build_restrictions(node_id, E):
    """
    Convierte las relaciones type=restriction de OSM en giros prohibidos del grafo:
      pares   (arista_entrada, arista_salida, nodo)  -> no se puede pasar de una a otra en ese nodo
      triples (arista_entrada, arista_via, arista_salida) -> para restricciones con "via" = calle
    """
    q = f'''[out:json][timeout:180];{AREA}relation["type"~"^restriction"](area.uy);out body;node(r:"via");out skel qt;'''
    data = overpass(q, "osm_restrictions.json")
    via_coord = {e["id"]: (round(e["lat"] * 1e7), round(e["lon"] * 1e7)) for e in data["elements"] if e["type"] == "node"}
    by_way = defaultdict(list)
    for e, w in enumerate(E["way"]):
        by_way[w].append(e)
    incident = defaultdict(list)
    for e in range(len(E["from"])):
        incident[E["from"][e]].append(e)
        incident[E["to"][e]].append(e)
    oneway = lambda e: E["flags"][e] & 1

    def can_enter(e, v):   # se puede llegar al nodo v recorriendo e
        return E["to"][e] == v or (E["from"][e] == v and not oneway(e))

    def can_leave(e, v):   # se puede salir del nodo v por e
        return E["from"][e] == v or (E["to"][e] == v and not oneway(e))

    pairs, triples = set(), set()
    stats = defaultdict(int)
    for r in data["elements"]:
        if r["type"] != "relation":
            continue
        t = r["tags"]
        kind = t.get("restriction") or t.get("restriction:motorcar") or t.get("restriction:motor_vehicle")
        if not kind or any(x in t.get("except", "") for x in ("motorcar", "motor_vehicle")):
            stats["ignorada (no aplica a autos)"] += 1
            continue
        mem = r["members"]
        frm = [m["ref"] for m in mem if m["role"] == "from" and m["type"] == "way"]
        to = [m["ref"] for m in mem if m["role"] == "to" and m["type"] == "way"]
        via = [m for m in mem if m["role"] == "via"]
        if len(frm) != 1 or len(to) != 1 or len(via) != 1:
            stats["ignorada (forma no soportada)"] += 1
            continue
        only = kind.startswith("only_")

        if via[0]["type"] == "node":
            v = node_id.get(via_coord.get(via[0]["ref"]))
            if v is None:
                stats["ignorada (cruce fuera de la red)"] += 1
                continue
            fe = [e for e in by_way[frm[0]] if can_enter(e, v) and v in (E["from"][e], E["to"][e])]
            te = [e for e in by_way[to[0]] if can_leave(e, v) and v in (E["from"][e], E["to"][e])]
            if frm[0] == to[0] and kind == "no_u_turn":
                for e in fe:
                    pairs.add((e, e, v))
                stats["aplicada"] += 1
                continue
            if len(fe) != 1 or len(te) != 1:
                stats["ignorada (ambigua)"] += 1
                continue
            f, tt = fe[0], te[0]
            if only:
                for x in incident[v]:
                    if x != tt and can_leave(x, v):
                        pairs.add((f, x, v))
            else:
                pairs.add((f, tt, v))
            stats["aplicada"] += 1
        else:
            ve = by_way[via[0]["ref"]]
            fe = by_way[frm[0]]
            te = by_way[to[0]]
            if len(ve) != 1:
                stats["ignorada (calle intermedia larga)"] += 1
                continue
            b = ve[0]
            ends = (E["from"][b], E["to"][b])
            fe = [e for e in fe if set((E["from"][e], E["to"][e])) & set(ends)]
            te = [e for e in te if set((E["from"][e], E["to"][e])) & set(ends)]
            if len(fe) != 1 or len(te) != 1 or only:
                stats["ignorada (ambigua)"] += 1
                continue
            triples.add((fe[0], b, te[0]))
            stats["aplicada"] += 1
    print("prohibiciones de giro:", dict(stats), f"-> {len(pairs)} giros prohibidos, {len(triples)} secuencias")
    return sorted(pairs), sorted(triples)


def build_search(ways):
    q = f"""[out:json][timeout:180];{AREA}
    node["place"~"^(city|town|village|hamlet|suburb|neighbourhood|quarter|locality)$"]["name"](area.uy);
    out body qt;"""
    data = overpass(q, "osm_places.json")
    rank = {"city": 0, "town": 1, "suburb": 2, "village": 3, "quarter": 4, "neighbourhood": 4,
            "hamlet": 5, "locality": 6}
    label = {"city": "Ciudad", "town": "Ciudad", "village": "Pueblo", "hamlet": "Pueblo",
             "suburb": "Barrio", "quarter": "Barrio", "neighbourhood": "Barrio", "locality": "Paraje"}
    places = []
    for e in data["elements"]:
        t = e["tags"]
        places.append((t["name"], t["place"], e["lat"], e["lon"]))
    towns = [p for p in places if p[1] in ("city", "town", "village", "hamlet")]

    # grilla para buscar la localidad más cercana
    grid = defaultdict(list)
    for p in towns:
        grid[(int(p[2] * 5), int(p[3] * 5))].append(p)

    def nearest_town(lat, lon):
        best, bd = None, 1e18
        cy, cx = int(lat * 5), int(lon * 5)
        for r in range(0, 4):
            for dy in range(-r, r + 1):
                for dx in range(-r, r + 1):
                    if max(abs(dy), abs(dx)) != r:
                        continue
                    for p in grid.get((cy + dy, cx + dx), []):
                        d = (p[2] - lat) ** 2 + ((p[3] - lon) * 0.83) ** 2
                        if d < bd:
                            best, bd = p, d
            if best and r >= 1:
                break
        return best[0] if best else ""

    entries = []
    for name, kind, lat, lon in places:
        sub = label[kind]
        if kind not in ("city", "town"):
            t = nearest_town(lat, lon)
            if t and t != name:
                sub += f" · {t}"
        entries.append([name, 0, sub, round(lat, 6), round(lon, 6), rank[kind]])

    # calles: agrupadas por nombre + localidad, punto representativo = mitad de la calle
    streets = defaultdict(list)
    for w in ways:
        nm = w["tags"].get("name")
        if not nm:
            continue
        g = w["geometry"]
        mid = g[len(g) // 2]
        streets[(nm, nearest_town(mid["lat"], mid["lon"]))].append(mid)
    for (nm, town), pts in streets.items():
        clat = sum(p["lat"] for p in pts) / len(pts)
        clon = sum(p["lon"] for p in pts) / len(pts)
        p = min(pts, key=lambda p: (p["lat"] - clat) ** 2 + (p["lon"] - clon) ** 2)
        entries.append([nm, 1, f"Calle · {town}" if town else "Calle", round(p["lat"], 6), round(p["lon"], 6), 7])

    # negocios con nombre
    with open(os.path.join(ASSETS, "pois.geojson"), encoding="utf-8") as f:
        pois = json.load(f)["features"]
    cat_label = {"fuel": "Estación de servicio", "charging": "Carga eléctrica", "restaurant": "Restaurante",
                 "fast_food": "Comida rápida", "cafe": "Cafetería", "bar": "Bar", "supermarket": "Supermercado",
                 "pharmacy": "Farmacia", "bank": "Banco", "parking": "Estacionamiento", "health": "Salud",
                 "hotel": "Hotel", "car_repair": "Taller", "monument": "Monumento",
                 "nightclub": "Discoteca", "police": "Comisaría", "mall": "Shopping"}
    for ft in pois:
        pr = ft["properties"]
        if not pr.get("name"):
            continue
        lon, lat = ft["geometry"]["coordinates"]
        town = nearest_town(lat, lon)
        sub = cat_label.get(pr["cat"], "") + (f" · {town}" if town else "")
        entries.append([pr["name"], 2, sub, lat, lon, 8])

    with open(os.path.join(ASSETS, "search.json"), "w", encoding="utf-8") as f:
        json.dump(entries, f, ensure_ascii=False, separators=(",", ":"))
    print(f"buscador: {len(entries)} entradas ({len(places)} lugares, {len(streets)} calles)")


if __name__ == "__main__":
    ways = load_ways()
    build_graph(ways)
    build_search(ways)
