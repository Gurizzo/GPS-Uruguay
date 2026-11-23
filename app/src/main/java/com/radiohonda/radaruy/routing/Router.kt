package com.radiohonda.radaruy.routing

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/** Calcula la ruta más rápida con A* sobre la red vial, sin conexión. */
class Router(private val g: RoadGraph) {

    /** Una porción de arista recorrida (completa o parcial) en un sentido. */
    private class Leg(val edge: Int, val forward: Boolean, val fromM: Double, val toM: Double)

    fun route(
        startLat: Double, startLon: Double, startBearing: Float?,
        destLat: Double, destLon: Double, destName: String,
    ): Route? {
        val s = g.snap(startLat, startLon) ?: return null
        val t = g.snap(destLat, destLon) ?: return null
        val legs = search(s, t, startBearing) ?: return null
        return build(legs, s, t, destName, destLat, destLon)
    }

    // ------------------------------------------------------------------ A* ---
    /**
     * A* "por tramos": cada estado es una calle recorrida en un sentido (arco), así se
     * sabe de dónde venís al llegar a un cruce y se pueden aplicar los giros prohibidos,
     * los sentidos de circulación y un costo extra por doblar a la izquierda o retomar.
     */
    private fun search(s: RoadGraph.Snap, t: RoadGraph.Snap, bearing: Float?): List<Leg>? {
        val arcs = g.edgeCount * 2
        val cost = DoubleArray(arcs) { Double.POSITIVE_INFINITY }
        val parent = IntArray(arcs) { -1 }   // arco anterior; -2 = arco de salida
        val closed = BooleanArray(arcs)
        val heap = Heap()
        val dLat = g.nodeLat
        val dLon = g.nodeLon
        val tLatI = t.lat * 1e6
        val tLonI = t.lon * 1e6
        val cosLat = cos(Math.toRadians(t.lat))
        val maxSpeed = 120 / 3.6
        fun h(v: Int): Double {
            val dy = (dLat[v] - tLatI) * 0.111132
            val dx = (dLon[v] - tLonI) * 0.111320 * cosLat
            return sqrt(dx * dx + dy * dy) / maxSpeed
        }
        // Penalizar arrancar en sentido contrario al que vamos (evita retornos innecesarios)
        fun penalty(dirBearing: Double): Double {
            if (bearing == null) return 0.0
            val diff = abs(((dirBearing - bearing) % 360 + 540) % 360 - 180)
            return if (diff > 100) 90.0 else 0.0
        }

        val e0 = s.edge
        val v0 = g.speedKmh(e0) / 3.6
        val L0 = g.length[e0].toDouble()
        fun seed(arc: Int, c: Double) {
            cost[arc] = c; parent[arc] = -2
            heap.push(c + h(g.arcEnd(arc)), arc)
        }
        seed(e0 * 2, (L0 - s.along) / v0 + penalty(s.bearing))
        if (!g.isOneway(e0)) seed(e0 * 2 + 1, s.along / v0 + penalty(s.bearing + 180))

        val e1 = t.edge
        val v1 = g.speedKmh(e1) / 3.6
        val L1 = g.length[e1].toDouble()

        var best = Double.POSITIVE_INFINITY
        var bestPrev = -1   // último arco completo antes de entrar a la calle destino
        var bestArc = -1    // arco (sentido) con el que se entra a la calle destino
        var direct: List<Leg>? = null
        // Origen y destino en la misma calle, sin doblar
        if (e0 == e1) {
            if (t.along >= s.along) {
                best = (t.along - s.along) / v0 + penalty(s.bearing)
                direct = listOf(Leg(e0, true, s.along, t.along))
            } else if (!g.isOneway(e0)) {
                best = (s.along - t.along) / v0 + penalty(s.bearing + 180)
                direct = listOf(Leg(e0, false, s.along, t.along))
            }
        }

        while (heap.size > 0) {
            val f = heap.topKey()
            val a = heap.pop()
            if (closed[a]) continue
            if (f >= best) break
            closed[a] = true
            val ca = cost[a]
            val v = g.arcEnd(a)
            val prevEdge = if (parent[a] >= 0) parent[a] shr 1 else -1
            for (k in g.adjStart[v] until g.adjStart[v + 1]) {
                val b = g.adjArc[k]
                if (g.isTurnForbidden(prevEdge, a, b)) continue
                val tc = g.turnCost(a, b, v)
                val eb = b shr 1
                if (eb == e1) {
                    // Entrar a la calle destino y frenar en el punto
                    val partial = if (b and 1 == 0) t.along / v1 else (L1 - t.along) / v1
                    val cand = ca + tc + partial
                    if (cand < best) { best = cand; bestPrev = a; bestArc = b; direct = null }
                }
                if (closed[b]) continue
                val nc = ca + tc + g.time(eb)
                if (nc < cost[b]) {
                    cost[b] = nc
                    parent[b] = a
                    heap.push(nc + h(g.arcEnd(b)), b)
                }
            }
        }
        if (direct != null) return direct
        if (bestArc < 0) return null

        // Reconstruir hacia atrás hasta el arco de salida
        val chain = ArrayList<Int>()
        var x = bestPrev
        while (x >= 0) { chain.add(x); x = parent[x] }
        chain.reverse()

        val legs = ArrayList<Leg>()
        val first = chain[0]
        if (first and 1 == 0) legs.add(Leg(e0, true, s.along, L0)) else legs.add(Leg(e0, false, s.along, 0.0))
        for (i in 1 until chain.size) {
            val e = chain[i] shr 1
            val fwd = chain[i] and 1 == 0
            val len = g.length[e].toDouble()
            legs.add(Leg(e, fwd, if (fwd) 0.0 else len, if (fwd) len else 0.0))
        }
        if (bestArc and 1 == 0) legs.add(Leg(e1, true, 0.0, t.along)) else legs.add(Leg(e1, false, L1, t.along))
        return legs
    }

    // ------------------------------------------------------------- armado ---
    private fun build(legs: List<Leg>, s: RoadGraph.Snap, t: RoadGraph.Snap, name: String, dLat: Double, dLon: Double): Route {
        val lat = ArrayList<Double>(); val lon = ArrayList<Double>()
        val cum = ArrayList<Double>(); val time = ArrayList<Double>(); val lim = ArrayList<Int>()
        val legStart = IntArray(legs.size)

        fun add(la: Double, lo: Double, speedMs: Double, limit: Int) {
            if (lat.isNotEmpty()) {
                val d = Geo.distance(lat.last(), lon.last(), la, lo)
                if (d < 0.3) return
                cum.add(cum.last() + d); time.add(time.last() + d / speedMs)
                lim[lim.size - 1] = limit
            } else {
                cum.add(0.0); time.add(0.0)
            }
            lat.add(la); lon.add(lo); lim.add(limit)
        }

        add(s.lat, s.lon, 1.0, 0)
        for ((i, leg) in legs.withIndex()) {
            legStart[i] = lat.size - 1
            val sp = g.speedKmh(leg.edge) / 3.6
            val limit = g.limitKmh(leg.edge)
            for (p in slice(leg)) add(p[0], p[1], sp, limit)
        }
        add(t.lat, t.lon, g.speedKmh(t.edge) / 3.6, g.limitKmh(t.edge))

        val la = lat.toDoubleArray(); val lo = lon.toDoubleArray()
        val cd = cum.toDoubleArray()
        val man = Maneuvers(g).build(legs.map { it.edge }, legs.map { it.forward }, legStart, la, lo, cd)
        return Route(la, lo, cd, time.toDoubleArray(), lim.toIntArray(), man, name, dLat, dLon,
            legs.map { it.edge }.toIntArray(), legs.map { it.forward }.toBooleanArray())
    }

    /** Puntos de la porción de arista, en el sentido de marcha. */
    private fun slice(leg: Leg): List<DoubleArray> {
        val p = g.edgePoints(leg.edge)
        val n = p.size / 2
        // distancias acumuladas a lo largo de la arista (escaladas a su longitud)
        val acc = DoubleArray(n)
        for (i in 1 until n) {
            acc[i] = acc[i - 1] + Geo.distance(p[i * 2 - 2], p[i * 2 - 1], p[i * 2], p[i * 2 + 1])
        }
        val scale = if (acc[n - 1] > 0) g.length[leg.edge] / acc[n - 1] else 1.0
        for (i in 0 until n) acc[i] *= scale

        fun pointAt(d: Double): DoubleArray {
            var i = 0
            while (i < n - 2 && acc[i + 1] < d) i++
            val seg = acc[i + 1] - acc[i]
            val f = if (seg > 0) ((d - acc[i]) / seg).coerceIn(0.0, 1.0) else 0.0
            return doubleArrayOf(p[i * 2] + (p[i * 2 + 2] - p[i * 2]) * f, p[i * 2 + 1] + (p[i * 2 + 3] - p[i * 2 + 1]) * f)
        }

        val a = minOf(leg.fromM, leg.toM)
        val b = maxOf(leg.fromM, leg.toM)
        val out = ArrayList<DoubleArray>()
        out.add(pointAt(a))
        for (i in 0 until n) if (acc[i] > a && acc[i] < b) out.add(doubleArrayOf(p[i * 2], p[i * 2 + 1]))
        out.add(pointAt(b))
        if (!leg.forward) out.reverse()
        return out
    }

    /** Montículo binario mínimo de (prioridad, nodo). */
    private class Heap {
        private var keys = DoubleArray(1024)
        private var vals = IntArray(1024)
        var size = 0; private set

        fun push(k: Double, v: Int) {
            if (size == keys.size) { keys = keys.copyOf(size * 2); vals = vals.copyOf(size * 2) }
            var i = size++
            while (i > 0) {
                val p = (i - 1) / 2
                if (keys[p] <= k) break
                keys[i] = keys[p]; vals[i] = vals[p]; i = p
            }
            keys[i] = k; vals[i] = v
        }

        fun topKey() = keys[0]

        fun pop(): Int {
            val top = vals[0]
            size--
            if (size > 0) {
                val k = keys[size]; val v = vals[size]
                var i = 0
                while (true) {
                    var c = i * 2 + 1
                    if (c >= size) break
                    if (c + 1 < size && keys[c + 1] < keys[c]) c++
                    if (keys[c] >= k) break
                    keys[i] = keys[c]; vals[i] = vals[c]; i = c
                }
                keys[i] = k; vals[i] = v
            }
            return top
        }
    }

    @Suppress("unused")
    private fun bearing(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double =
        Math.toDegrees(atan2((bLon - aLon) * cos(Math.toRadians(aLat)), bLat - aLat))
}
