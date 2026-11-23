"""
Completa style.json con la capa de edificios detectados por satélite y genera style3d.json
(mismos colores de la vista noche, con los edificios en relieve).
"""
import json
import os

A = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "src", "main", "assets")
st = json.load(open(os.path.join(A, "style.json"), encoding="utf-8"))
st["sources"]["ml"] = {
    "type": "vector", "url": "pmtiles://{BUILDINGS}",
    "attribution": "© Overture Maps · Google Open Buildings · Microsoft",
}
layers = [l for l in st["layers"] if l["id"] != "buildings-ml"]
i = next(k for k, l in enumerate(layers) if l["id"] == "buildings")
layers.insert(i + 1, {
    "id": "buildings-ml", "type": "fill", "source": "ml", "source-layer": "buildings", "minzoom": 15,
    "paint": {"fill-color": "#2c3a66", "fill-outline-color": "#43568f"},
})
st["layers"] = layers
json.dump(st, open(os.path.join(A, "style.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)

# Vista 3D: igual a la noche pero con edificios en relieve
s3 = json.loads(json.dumps(st))
s3["name"] = "Radar UY Noche 3D"
s3["light"] = {"anchor": "viewport", "color": "#ffffff", "intensity": 0.32, "position": [1.3, 200, 35]}
out = []
for l in s3["layers"]:
    if l["id"] in ("buildings", "buildings-ml"):
        out.append({
            "id": l["id"] + "-3d", "type": "fill-extrusion", "source": l["source"], "source-layer": "buildings",
            "minzoom": 13.5 if l["id"] == "buildings" else 15,
            "paint": {
                "fill-extrusion-color": "#34437a",
                "fill-extrusion-height": ["coalesce", ["get", "height"], 9 if l["id"] == "buildings" else 5],
                "fill-extrusion-base": ["coalesce", ["get", "min_height"], 0],
                "fill-extrusion-opacity": 0.95,
            },
        })
    else:
        out.append(l)
s3["layers"] = out
json.dump(s3, open(os.path.join(A, "style3d.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print("estilos listos")
