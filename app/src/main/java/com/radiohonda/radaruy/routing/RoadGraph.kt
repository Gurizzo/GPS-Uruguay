package com.radiohonda.radaruy.routing

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Red vial de Uruguay (assets/graph.bin, generado por tools/build_routing.py).
 *
 * Cada arista va de un cruce a otro. Un "arco" es una arista recorrida en un sentido:
 * arco = arista * 2 + (0 = sentido de la arista, 1 = sentido contrario).
 */
class RoadGraph private constructor(
    val nodeLat: IntArray, val nodeLon: IntArray,
    val from: IntArray, val to: IntArray, val length: FloatArray,
    private val nameIdx: IntArray, private val gStart: IntArray, private val gCount: IntArray,
    private val speed: ByteArray, private val limit: ByteArray, private val flags: ByteArray,
    private val cls: ByteArray,
    private val gLat: IntArray, private val gLon: IntArray, private val names: Array<String>,
    /** Giros prohibidos en un cruce: (arista de entrada, arista de salida, nodo). */
    val banFrom: IntArray = IntArray(0), val banTo: IntArray = IntArray(0), val banVia: IntArray = IntArray(0),
    /** Secuencias prohibidas: entrada -> calle intermedia -> salida. */
    val seqA: IntArray = IntArray(0), val seqB: IntArray = IntArray(0), val seqC: IntArray = IntArray(0),
) {
    val nodeCount = nodeLat.size
    val edgeCount = from.size

    /** Arcos salientes de cada nodo (formato CSR). */
    val adjStart = IntArray(nodeCount + 1)
    val adjArc: IntArray
    /** Cantidad de calles que llegan a cada nodo (para saber si es un cruce). */
    val degree = IntArray(nodeCount)

    private val grid = HashMap<Long, IntArray>()
    /** (entrada, salida) -> nodo donde rige (-1 = en cualquiera de los dos extremos). */
    private val banned = HashMap<Long, Int>(banFrom.size * 2)
    private val bannedSeq = HashSet<Long>(seqA.size * 2)
    /** Rumbo al salir del primer punto y al llegar al último punto de cada arista. */
    private val headBearing = FloatArray(edgeCount)
    private val tailBearing = FloatArray(edgeCount)

    init {
        for (e in 0 until edgeCount) {
            adjStart[from[e] + 1]++
            if (!isOneway(e)) adjStart[to[e] + 1]++
            degree[from[e]]++
            degree[to[e]]++
        }
        for (i in 0 until nodeCount) adjStart[i + 1] += adjStart[i]
        adjArc = IntArray(adjStart[nodeCount])
        val fill = adjStart.copyOf()
        for (e in 0 until edgeCount) {
            adjArc[fill[from[e]]++] = e * 2
            if (!isOneway(e)) adjArc[fill[to[e]]++] = e * 2 + 1
        }
        buildGrid()
        for (i in banFrom.indices) {
            val k = pairKey(banFrom[i], banTo[i])
            val prev = banned[k]
            banned[k] = if (prev == null || prev == banVia[i]) banVia[i] else -1
        }
        for (i in seqA.indices) bannedSeq.add(pairKey(seqA[i], seqB[i]) * 31 + seqC[i])
        for (e in 0 until edgeCount) {
            val p = edgePoints(e)
            val n = p.size / 2
            headBearing[e] = bearingDeg(p[0], p[1], p[2], p[3]).toFloat()
            tailBearing[e] = bearingDeg(p[n * 2 - 4], p[n * 2 - 3], p[n * 2 - 2], p[n * 2 - 1]).toFloat()
        }
    }

    private fun pairKey(a: Int, b: Int): Long = a.toLong() * edgeCount + b

    private fun bearingDeg(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double =
        Math.toDegrees(atan2((bLon - aLon) * cos(Math.toRadians(aLat)), bLat - aLat))

    /** Rumbo al entrar al arco (desde su nodo inicial). */
    fun arcStartBearing(arc: Int): Float =
        if (arc and 1 == 0) headBearing[arc shr 1] else (tailBearing[arc shr 1] + 180f) % 360f

    /** Rumbo al terminar el arco (llegando a su nodo final). */
    fun arcEndBearing(arc: Int): Float =
        if (arc and 1 == 0) tailBearing[arc shr 1] else (headBearing[arc shr 1] + 180f) % 360f

    /** ¿Está prohibido pasar del arco a al arco b? prevEdge = calle anterior a la de a (o -1). */
    fun isTurnForbidden(prevEdge: Int, a: Int, b: Int): Boolean {
        val ea = a shr 1
        val eb = b shr 1
        val via = banned[pairKey(ea, eb)]
        if (via != null && (via == -1 || via == arcEnd(a))) return true
        return prevEdge >= 0 && bannedSeq.contains(pairKey(prevEdge, ea) * 31 + eb)
    }

    /**
     * Segundos extra por el giro en el nodo v: doblar a la izquierda cuesta más que a la
     * derecha (hay que esperar el tránsito de frente) y retomar en U cuesta mucho.
     */
    fun turnCost(a: Int, b: Int, v: Int): Double {
        if (a shr 1 == b shr 1) return if (degree[v] <= 1) 20.0 else 120.0 // vuelta en U por la misma calle
        if (degree[v] <= 2) return 0.0
        val ang = ((arcStartBearing(b) - arcEndBearing(a)) % 360 + 540) % 360 - 180
        return when {
            abs(ang) > 150 -> 45.0
            ang < -45 -> 8.0
            ang > 45 -> 3.0
            else -> 0.0
        }
    }

    fun isOneway(e: Int) = flags[e].toInt() and 1 != 0
    fun isRoundabout(e: Int) = flags[e].toInt() and 2 != 0
    fun isLink(e: Int) = flags[e].toInt() and 4 != 0
    fun name(e: Int): String = names[nameIdx[e]]
    fun speedKmh(e: Int) = speed[e].toInt() and 0xff
    fun limitKmh(e: Int) = limit[e].toInt() and 0xff
    fun roadClass(e: Int) = cls[e].toInt()
    /** Segundos para recorrer la arista completa. */
    fun time(e: Int): Double = length[e] / (speedKmh(e) / 3.6)

    fun arcStart(arc: Int) = if (arc and 1 == 0) from[arc shr 1] else to[arc shr 1]
    fun arcEnd(arc: Int) = if (arc and 1 == 0) to[arc shr 1] else from[arc shr 1]

    /** Puntos de la arista en su sentido: [lat0, lon0, lat1, lon1, ...] en grados. */
    fun edgePoints(e: Int): DoubleArray {
        val n = gCount[e] + 2
        val out = DoubleArray(n * 2)
        out[0] = nodeLat[from[e]] / 1e6; out[1] = nodeLon[from[e]] / 1e6
        for (k in 0 until gCount[e]) {
            out[2 + k * 2] = gLat[gStart[e] + k] / 1e6
            out[3 + k * 2] = gLon[gStart[e] + k] / 1e6
        }
        out[n * 2 - 2] = nodeLat[to[e]] / 1e6; out[n * 2 - 1] = nodeLon[to[e]] / 1e6
        return out
    }

    data class Snap(
        val edge: Int,
        /** Metros desde el inicio de la arista (en su sentido) hasta el punto proyectado. */
        val along: Double,
        val lat: Double, val lon: Double,
        val distance: Double,
        /** Rumbo de la arista (en su sentido) en ese punto, en grados. */
        val bearing: Double,
    )

    /** Calle más cercana a un punto (hasta ~1,5 km). */
    fun snap(lat: Double, lon: Double): Snap? {
        val cy = Math.floorDiv((lat * 1e6).toInt(), CELL)
        val cx = Math.floorDiv((lon * 1e6).toInt(), CELL)
        var best: Snap? = null
        val seen = HashSet<Int>()
        for (r in 0..3) {
            for (dy in -r..r) for (dx in -r..r) {
                if (max(abs(dx), abs(dy)) != r) continue
                val edges = grid[key(cx + dx, cy + dy)] ?: continue
                for (e in edges) {
                    if (!seen.add(e)) continue
                    val s = projectOnEdge(e, lat, lon)
                    if (best == null || s.distance < best.distance) best = s
                }
            }
            // si ya hay algo más cerca que el anillo siguiente, listo
            if (best != null && best.distance < r * CELL * 0.1) break
        }
        return best
    }

    private fun projectOnEdge(e: Int, lat: Double, lon: Double): Snap {
        val p = edgePoints(e)
        val mLat = 111_132.0
        val mLon = 111_320.0 * cos(Math.toRadians(lat))
        var bestD = Double.MAX_VALUE
        var bestAlong = 0.0
        var bLat = p[0]; var bLon = p[1]; var bBear = 0.0
        var acc = 0.0
        for (i in 0 until p.size / 2 - 1) {
            val ax = (p[i * 2 + 1] - lon) * mLon; val ay = (p[i * 2] - lat) * mLat
            val bx = (p[i * 2 + 3] - lon) * mLon; val by = (p[i * 2 + 2] - lat) * mLat
            val vx = bx - ax; val vy = by - ay
            val l2 = vx * vx + vy * vy
            val t = if (l2 > 0) ((-ax * vx - ay * vy) / l2).coerceIn(0.0, 1.0) else 0.0
            val qx = ax + t * vx; val qy = ay + t * vy
            val d = sqrt(qx * qx + qy * qy)
            val segLen = sqrt(l2)
            if (d < bestD) {
                bestD = d
                bestAlong = acc + t * segLen
                bLat = lat + qy / mLat; bLon = lon + qx / mLon
                bBear = Math.toDegrees(atan2(vx, vy))
            }
            acc += segLen
        }
        // escalar a la longitud oficial de la arista
        val scale = if (acc > 0) length[e] / acc else 1.0
        return Snap(e, bestAlong * scale, bLat, bLon, bestD, bBear)
    }

    private fun buildGrid() {
        val tmp = HashMap<Long, MutableList<Int>>(100_000)
        for (e in 0 until edgeCount) {
            val p = edgePoints(e)
            var last = Long.MIN_VALUE
            for (i in 0 until p.size / 2 - 1) {
                val la0 = p[i * 2]; val lo0 = p[i * 2 + 1]
                val la1 = p[i * 2 + 2]; val lo1 = p[i * 2 + 3]
                val steps = max(1, (max(abs(la1 - la0), abs(lo1 - lo0)) * 1e6 * 2 / CELL).toInt() + 1)
                for (s in 0..steps) {
                    val la = la0 + (la1 - la0) * s / steps
                    val lo = lo0 + (lo1 - lo0) * s / steps
                    val k = key(Math.floorDiv((lo * 1e6).toInt(), CELL), Math.floorDiv((la * 1e6).toInt(), CELL))
                    if (k != last) {
                        val l = tmp.getOrPut(k) { ArrayList(4) }
                        if (l.isEmpty() || l[l.size - 1] != e) l.add(e)
                        last = k
                    }
                }
            }
        }
        for ((k, v) in tmp) grid[k] = v.distinct().toIntArray()
    }

    companion object {
        private const val CELL = 5000 // 0.005° ≈ 550 m

        private fun key(cx: Int, cy: Int): Long = (cy.toLong() shl 32) or (cx.toLong() and 0xffffffffL)

        fun load(context: Context): RoadGraph =
            load(context.assets.open("graph.bin").use { it.readBytes() })

        fun load(bytes: ByteArray): RoadGraph {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(String(bytes, 0, 4) == "RGRF") { "graph.bin inválido" }
            buf.position(4)
            val version = buf.int
            val n = buf.int; val m = buf.int; val gn = buf.int; val nn = buf.int

            fun ints(k: Int): IntArray {
                val a = IntArray(k)
                buf.slice().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(a)
                buf.position(buf.position() + k * 4)
                return a
            }
            fun floats(k: Int): FloatArray {
                val a = FloatArray(k)
                buf.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(a)
                buf.position(buf.position() + k * 4)
                return a
            }
            fun bytes(k: Int): ByteArray = ByteArray(k).also { buf.get(it) }

            val lat = ints(n); val lon = ints(n)
            val fr = ints(m); val to = ints(m)
            val len = floats(m)
            val name = ints(m); val gs = ints(m)
            val gc = IntArray(m) { buf.short.toInt() and 0xffff }
            val sp = bytes(m); val lim = bytes(m); val fl = bytes(m); val cl = bytes(m)
            val gLat = ints(gn); val gLon = ints(gn)
            val names = Array(nn) {
                val l = buf.short.toInt() and 0xffff
                val b = ByteArray(l).also { buf.get(it) }
                String(b, Charsets.UTF_8)
            }
            if (version < 2) return RoadGraph(lat, lon, fr, to, len, name, gs, gc, sp, lim, fl, cl, gLat, gLon, names)
            val np = buf.int
            val bf = ints(np); val bt = ints(np); val bv = ints(np)
            val ns = buf.int
            val sa = ints(ns); val sb = ints(ns); val sc = ints(ns)
            return RoadGraph(lat, lon, fr, to, len, name, gs, gc, sp, lim, fl, cl, gLat, gLon, names,
                bf, bt, bv, sa, sb, sc)
        }
    }
}
