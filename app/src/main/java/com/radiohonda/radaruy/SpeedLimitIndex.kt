package com.radiohonda.radaruy

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Índice espacial de los tramos de calle con su límite de velocidad
 * (assets/speedlimits.bin, generado por tools/build_data.py).
 */
class SpeedLimitIndex private constructor(
    private val lat: IntArray,          // micro-grados
    private val lon: IntArray,
    private val pointWay: IntArray,     // a qué tramo pertenece cada punto
    private val speed: ShortArray,
    private val flags: ByteArray,
    private val grid: HashMap<Long, IntArray>,
) {
    data class Limit(val kmh: Int, val estimated: Boolean)

    /** Límite de la calle más cercana (máx. 30 m), favoreciendo la que va en nuestro rumbo. */
    fun query(latDeg: Double, lonDeg: Double, bearing: Float?): Limit? {
        val pLat = (latDeg * 1e6).toInt()
        val pLon = (lonDeg * 1e6).toInt()
        val cy = Math.floorDiv(pLat, CELL)
        val cx = Math.floorDiv(pLon, CELL)
        val mPerLat = 0.11132          // metros por micro-grado de latitud
        val mPerLon = 0.11132 * cos(Math.toRadians(latDeg))
        var bestScore = Double.MAX_VALUE
        var bestWay = -1
        for (dy in -1..1) for (dx in -1..1) {
            val segs = grid[key(cx + dx, cy + dy)] ?: continue
            for (i in segs) {
                val ax = (lon[i] - pLon) * mPerLon
                val ay = (lat[i] - pLat) * mPerLat
                val bx = (lon[i + 1] - pLon) * mPerLon
                val by = (lat[i + 1] - pLat) * mPerLat
                val vx = bx - ax
                val vy = by - ay
                val len2 = vx * vx + vy * vy
                val t = if (len2 > 0) ((-ax * vx - ay * vy) / len2).coerceIn(0.0, 1.0) else 0.0
                val qx = ax + t * vx
                val qy = ay + t * vy
                val d = sqrt(qx * qx + qy * qy)
                if (d > MAX_DIST) continue
                var score = d
                if (bearing != null && len2 > 0) {
                    val segBearing = Math.toDegrees(atan2(vx, vy))
                    var diff = abs(((segBearing - bearing) % 360 + 540) % 360 - 180)
                    if (flags[pointWay[i]].toInt() and 2 == 0) diff = minOf(diff, 180 - diff)
                    score += diff * 0.25
                }
                if (score < bestScore) {
                    bestScore = score
                    bestWay = pointWay[i]
                }
            }
        }
        if (bestWay < 0) return null
        return Limit(speed[bestWay].toInt(), flags[bestWay].toInt() and 1 != 0)
    }

    companion object {
        private const val CELL = 4000           // 0.004° ≈ 440 m
        private const val MAX_DIST = 30.0

        private fun key(cx: Int, cy: Int): Long = (cy.toLong() shl 32) or (cx.toLong() and 0xffffffffL)

        fun load(context: Context): SpeedLimitIndex {
            val bytes = context.assets.open("speedlimits.bin").use { it.readBytes() }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { buf.get(it) }
            require(String(magic) == "SPDL") { "speedlimits.bin inválido" }
            buf.int // versión
            val ways = buf.int
            val totalPoints = (bytes.size - 12 - ways * 4) / 8
            val lat = IntArray(totalPoints)
            val lon = IntArray(totalPoints)
            val pointWay = IntArray(totalPoints)
            val speed = ShortArray(ways)
            val flags = ByteArray(ways)
            val cells = HashMap<Long, IntList>(200_000)
            var p = 0
            for (w in 0 until ways) {
                speed[w] = (buf.get().toInt() and 0xff).toShort()
                flags[w] = buf.get()
                val n = buf.short.toInt() and 0xffff
                val first = p
                for (k in 0 until n) {
                    lat[p] = buf.int
                    lon[p] = buf.int
                    pointWay[p] = w
                    p++
                }
                // indexar cada segmento [i, i+1] en las celdas que recorre
                for (i in first until p - 1) {
                    val dLat = lat[i + 1] - lat[i]
                    val dLon = lon[i + 1] - lon[i]
                    val steps = max(1, ceil(max(abs(dLat), abs(dLon)) * 2.0 / CELL).toInt())
                    var last = Long.MIN_VALUE
                    for (s in 0..steps) {
                        val y = lat[i] + dLat.toLong() * s / steps
                        val x = lon[i] + dLon.toLong() * s / steps
                        val k = key(Math.floorDiv(x.toInt(), CELL), Math.floorDiv(y.toInt(), CELL))
                        if (k != last) {
                            cells.getOrPut(k) { IntList() }.add(i)
                            last = k
                        }
                    }
                }
            }
            val grid = HashMap<Long, IntArray>(cells.size * 2)
            for ((k, v) in cells) grid[k] = v.toArray()
            return SpeedLimitIndex(lat, lon, pointWay, speed, flags, grid)
        }
    }

    private class IntList {
        private var a = IntArray(4)
        private var n = 0
        fun add(v: Int) {
            if (n > 0 && a[n - 1] == v) return
            if (n == a.size) a = a.copyOf(n * 2)
            a[n++] = v
        }
        fun toArray(): IntArray = a.copyOf(n)
    }
}
