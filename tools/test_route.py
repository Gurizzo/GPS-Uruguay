"""Prueba rápida del grafo: calcula una ruta entre dos puntos (A*) e imprime tiempo y distancia."""
import heapq, math, struct, sys
b = open("app/src/main/assets/graph.bin", "rb").read()
assert b[:4] == b"RGRF"
_, n, m, gn, nn = struct.unpack_from("<5I", b, 4); p = 24
def arr(fmt, k):
    global p
    v = struct.unpack_from(f"<{k}{fmt}", b, p); p += k * struct.calcsize(fmt); return v
lat, lon = arr("i", n), arr("i", n)
fr, to = arr("i", m), arr("i", m)
ln = arr("f", m); name = arr("i", m); gs = arr("i", m); gc = arr("H", m)
speed = arr("B", m); limit = arr("B", m); flags = arr("B", m); cls = arr("B", m)
adj = [[] for _ in range(n)]
for e in range(m):
    t = ln[e] / (speed[e] / 3.6)
    adj[fr[e]].append((to[e], t, e))
    if not flags[e] & 1: adj[to[e]].append((fr[e], t, e))
def nearest(la, lo):
    return min(range(n), key=lambda i: (lat[i] / 1e6 - la) ** 2 + (lon[i] / 1e6 - lo) ** 2)
def h(a, b):
    return math.hypot((lat[a] - lat[b]) * 0.111, (lon[a] - lon[b]) * 0.092) / (120 / 3.6)
def route(a, b):
    dist = {a: 0}; prev = {}; pq = [(h(a, b), a)]
    while pq:
        _, u = heapq.heappop(pq)
        if u == b: break
        for v, t, e in adj[u]:
            nd = dist[u] + t
            if nd < dist.get(v, 1e18): dist[v] = nd; prev[v] = (u, e); heapq.heappush(pq, (nd + h(v, b), v))
    if b not in dist: return None
    es = []; u = b
    while u != a: u, e = prev[u]; es.append(e)
    return dist[b], es[::-1]
tests = [("Montevideo centro -> Punta del Este", (-34.9058, -56.1890), (-34.9625, -54.9450)),
         ("Pocitos -> Aeropuerto Carrasco", (-34.9100, -56.1500), (-34.8380, -56.0300)),
         ("Montevideo -> Salto", (-34.9058, -56.1890), (-31.3880, -57.9620))]
for label, A, B in tests:
    a, bb = nearest(*A), nearest(*B)
    r = route(a, bb)
    if not r: print(label, "SIN RUTA"); continue
    t, es = r
    km = sum(ln[e] for e in es) / 1000
    roads = []
    for e in es:
        nm = name[e]
        if not roads or roads[-1] != nm: roads.append(nm)
    print(f"{label}: {km:.1f} km, {t/60:.0f} min, {len(es)} tramos, {len(roads)} cambios de calle")
