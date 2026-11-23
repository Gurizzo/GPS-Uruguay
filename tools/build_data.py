"""
Descarga desde OpenStreetMap (Overpass API) los datos de Uruguay que usa la app
y los deja en app/src/main/assets/:

  radars.geojson     radares / cámaras de velocidad
  pois.geojson       negocios agrupados por categoría
  speedlimits.bin    tramos de calles con su límite de velocidad (formato binario compacto)

Uso:  python tools/build_data.py
"""
import json
import os
import re
import struct
import sys
import time

import requests

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
CACHE = os.path.join(ROOT, "data-build")
OVERPASS_SERVERS = ["https://overpass-api.de/api/interpreter",
                    "https://overpass.private.coffee/api/interpreter",
                    "https://maps.mail.ru/osm/tools/overpass/api/interpreter"]
AREA = 'area["ISO3166-1"="UY"][admin_level=2]->.uy;'

# --- categorías de negocios: clave -> lista de (tag, valores) ---------------
CATEGORIES = {
    "fuel":        [("amenity", "fuel")],
    "charging":    [("amenity", "charging_station")],
    "restaurant":  [("amenity", "restaurant")],
    "fast_food":   [("amenity", "fast_food")],
    "cafe":        [("amenity", "cafe"), ("shop", "bakery")],
    "bar":         [("amenity", "bar|pub")],
    "supermarket": [("shop", "supermarket|convenience")],
    "pharmacy":    [("amenity", "pharmacy")],
    "bank":        [("amenity", "bank|atm")],
    "parking":     [("amenity", "parking")],
    "health":      [("amenity", "hospital|clinic")],
    "hotel":       [("tourism", "hotel|motel|hostel")],
    "car_repair":  [("shop", "car_repair|tyres")],
    "monument":    [("historic", "monument|memorial"), ("tourism", "attraction|museum|viewpoint")],
    "nightclub":   [("amenity", "nightclub")],
    "police":      [("amenity", "police")],
    "mall":        [("shop", "mall|department_store")],
}

# --- límites por defecto en Uruguay (Ley 18.191) cuando OSM no tiene maxspeed
ROAD_TYPES = ("motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|"
              "tertiary|tertiary_link|unclassified|residential|living_street")
DEFAULTS = {
    "motorway": 90, "motorway_link": 60, "trunk": 90, "trunk_link": 60,
    "unclassified": 45, "residential": 45, "living_street": 20,
}
ZONE = {"uy:urban": 45, "uy:rural": 90, "uy:motorway": 110, "uy:living_street": 20, "walk": 10}


def overpass(query, cache_name):
    path = os.path.join(CACHE, cache_name)
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    for i in range(9):
        server = OVERPASS_SERVERS[i % len(OVERPASS_SERVERS)]
        print(f"  consultando Overpass ({cache_name})...")
        try:
            r = requests.post(server, data={"data": query}, timeout=600,
                              headers={"User-Agent": "radio-honda-data-builder/1.0"})
        except requests.RequestException as ex:
            print(f"  error de red: {ex}")
            continue
        if r.status_code == 200 and r.text.lstrip().startswith("{"):
            data = r.json()
            os.makedirs(CACHE, exist_ok=True)
            with open(path, "w", encoding="utf-8") as f:
                json.dump(data, f)
            return data
        print(f"  HTTP {r.status_code}, reintentando...")
        time.sleep(10 * (i + 1))
    sys.exit("Overpass no respondió")


def parse_speed(value):
    if not value:
        return None
    v = value.strip().lower()
    if v in ZONE:
        return ZONE[v]
    m = re.match(r"^(\d+)\s*(km/h|kmh|kph)?$", v)
    if m:
        return int(m.group(1))
    m = re.match(r"^(\d+)\s*mph$", v)
    if m:
        return round(int(m.group(1)) * 1.609)
    return None


def feature(lon, lat, props):
    return {"type": "Feature", "geometry": {"type": "Point", "coordinates": [round(lon, 6), round(lat, 6)]},
            "properties": props}


def build_radars():
    q = f"""[out:json][timeout:180];{AREA}
    (node["highway"="speed_camera"](area.uy);
     node["enforcement"~"maxspeed|traffic_signals"](area.uy);
     relation["enforcement"~"maxspeed|average_speed|traffic_signals"](area.uy););
    out body; >; out skel qt;"""
    data = overpass(q, "osm_radars.json")
    nodes = {e["id"]: e for e in data["elements"] if e["type"] == "node"}
    out = {}
    # nodos marcados directamente como radar
    for e in data["elements"]:
        t = e.get("tags", {})
        if e["type"] == "node" and (t.get("highway") == "speed_camera" or "enforcement" in t):
            kind = "redlight" if t.get("enforcement") == "traffic_signals" else "speed"
            out[e["id"]] = feature(e["lon"], e["lat"], {
                "kind": kind, "maxspeed": parse_speed(t.get("maxspeed")) or 0,
                "name": t.get("name", "")})
    # relaciones enforcement: tomar el "device" (o el "from") como posición
    for e in data["elements"]:
        if e["type"] != "relation":
            continue
        t = e.get("tags", {})
        kind = {"average_speed": "average", "traffic_signals": "redlight"}.get(t.get("enforcement"), "speed")
        members = [m for m in e.get("members", []) if m["type"] == "node" and m["ref"] in nodes]
        pick = [m for m in members if m["role"] == "device"] or [m for m in members if m["role"] == "from"]
        for m in pick[:1]:
            n = nodes[m["ref"]]
            prev = out.get(n["id"], {}).get("properties", {})
            out[n["id"]] = feature(n["lon"], n["lat"], {
                "kind": kind,
                "maxspeed": parse_speed(t.get("maxspeed")) or prev.get("maxspeed", 0),
                "name": t.get("name", prev.get("name", ""))})
    fc = {"type": "FeatureCollection", "features": list(out.values())}
    with open(os.path.join(ASSETS, "radars.geojson"), "w", encoding="utf-8") as f:
        json.dump(fc, f, ensure_ascii=False, separators=(",", ":"))
    print(f"radares: {len(out)}")


def build_pois():
    parts = []
    for rules in CATEGORIES.values():
        for k, v in rules:
            parts.append(f'nwr["{k}"~"^({v})$"](area.uy);')
    q = f"[out:json][timeout:300];{AREA}({''.join(parts)});out tags center qt;"
    data = overpass(q, "osm_pois.json")
    feats = []
    counts = {}
    for e in data["elements"]:
        t = e.get("tags", {})
        lat = e.get("lat", e.get("center", {}).get("lat"))
        lon = e.get("lon", e.get("center", {}).get("lon"))
        if lat is None:
            continue
        cat = None
        for key, rules in CATEGORIES.items():
            if any(re.fullmatch(v, t.get(k, "")) for k, v in rules):
                cat = key
                break
        if not cat:
            continue
        name = t.get("name") or t.get("brand") or ""
        feats.append(feature(lon, lat, {"cat": cat, "name": name}))
        counts[cat] = counts.get(cat, 0) + 1
    with open(os.path.join(ASSETS, "pois.geojson"), "w", encoding="utf-8") as f:
        json.dump({"type": "FeatureCollection", "features": feats}, f, ensure_ascii=False, separators=(",", ":"))
    print(f"negocios: {len(feats)} {counts}")


def build_speedlimits():
    # Se pide por zonas (grilla de 3x3 sobre Uruguay) para no saturar el servidor
    elements, seen = [], set()
    lons = [-58.5, -56.6, -54.8, -53.0]
    lats = [-35.1, -33.4, -31.7, -30.0]
    for i in range(3):
        for j in range(3):
            bb = f"{lats[j]},{lons[i]},{lats[j + 1]},{lons[i + 1]}"
            q = (f'[out:json][timeout:300];{AREA}'
                 f'way["highway"~"^({ROAD_TYPES})$"](area.uy)({bb});out tags geom qt;')
            for e in overpass(q, f"osm_roads_{i}{j}.json")["elements"]:
                if e["id"] not in seen:
                    seen.add(e["id"])
                    elements.append(e)
    data = {"elements": elements}
    buf = bytearray()
    count = tagged = pts = 0
    for e in data["elements"]:
        t = e.get("tags", {})
        geom = e.get("geometry") or []
        if len(geom) < 2:
            continue
        hw = t.get("highway", "")
        speed = parse_speed(t.get("maxspeed"))
        if speed is None:
            fw, bw = parse_speed(t.get("maxspeed:forward")), parse_speed(t.get("maxspeed:backward"))
            speed = min(x for x in (fw, bw) if x) if (fw or bw) else None
        if speed is None:
            speed = parse_speed(t.get("zone:maxspeed") or t.get("source:maxspeed"))
        estimated = speed is None
        if speed is None:
            if hw in DEFAULTS:
                speed = DEFAULTS[hw]
            elif hw.split("_")[0] in ("primary", "secondary", "tertiary"):
                # Con número de ruta = ruta nacional (90); sin número = avenida urbana (45)
                speed = 90 if t.get("ref") and not hw.endswith("_link") else 45
            else:
                continue
        speed = max(5, min(speed, 250))
        flags = 1 if estimated else 0
        if t.get("oneway") in ("yes", "1"):
            flags |= 2
        geom = geom[:65535]
        buf += struct.pack("<BBH", speed, flags, len(geom))
        for p in geom:
            buf += struct.pack("<ii", round(p["lat"] * 1e6), round(p["lon"] * 1e6))
        count += 1
        tagged += 0 if estimated else 1
        pts += len(geom)
    with open(os.path.join(ASSETS, "speedlimits.bin"), "wb") as f:
        f.write(b"SPDL" + struct.pack("<II", 1, count) + buf)
    print(f"tramos: {count} (con maxspeed en OSM: {tagged}), puntos: {pts}, {len(buf) / 1e6:.1f} MB")


if __name__ == "__main__":
    os.makedirs(ASSETS, exist_ok=True)
    build_radars()
    build_pois()
    build_speedlimits()
