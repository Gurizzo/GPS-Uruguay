package com.radiohonda.radaruy.routing

import com.radiohonda.radaruy.routing.Maneuver.Type
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos

/** Convierte la secuencia de calles de una ruta en indicaciones giro a giro. */
class Maneuvers(private val g: RoadGraph) {

    fun build(
        edges: List<Int>, forward: List<Boolean>, legStart: IntArray,
        lat: DoubleArray, lon: DoubleArray, cum: DoubleArray,
    ): List<Maneuver> {
        val out = ArrayList<Maneuver>()
        out.add(Maneuver(Type.DEPART, 0, 0.0, g.name(edges[0])))
        var i = 1
        while (i < edges.size) {
            val prev = edges[i - 1]
            val cur = edges[i]
            val b = legStart[i]
            val node = if (forward[i - 1]) g.to[prev] else g.from[prev]
            if (prev == cur) { i++; continue }

            // Rotonda: una sola indicación al entrar, contando las salidas
            if (g.isRoundabout(cur) && !g.isRoundabout(prev)) {
                var exits = 0
                var j = i
                while (j < edges.size && g.isRoundabout(edges[j])) {
                    val end = if (forward[j]) g.to[edges[j]] else g.from[edges[j]]
                    if (j + 1 < edges.size && g.isRoundabout(edges[j + 1])) {
                        if (hasExit(end)) exits++
                    } else {
                        exits++
                    }
                    j++
                }
                val road = if (j < edges.size) g.name(edges[j]) else g.name(cur)
                out.add(Maneuver(Type.ROUNDABOUT, b, cum[b], road, exits.coerceAtLeast(1)))
                i = j + 1
                continue
            }
            if (g.isRoundabout(cur)) { i++; continue }

            val angle = turnAngle(lat, lon, cum, b)
            val deg = g.degree[node]
            val prevName = g.name(prev)
            val curName = g.name(cur)
            val type: Type? = when {
                deg < 3 -> null // no hay otra opción: es una curva, no un cruce
                g.isLink(cur) && !g.isLink(prev) && abs(angle) < 60 ->
                    if (angle < -8) Type.RAMP_LEFT else Type.RAMP_RIGHT
                abs(angle) < 25 ->
                    if (curName.isNotEmpty() && curName != prevName && !g.isLink(prev)) Type.STRAIGHT else null
                // Seguir por la misma calle aunque haga una curva en el cruce: no hace falta avisar
                abs(angle) < 50 && curName.isNotEmpty() && curName == prevName -> null
                else -> classify(angle)
            }
            if (type != null) {
                val last = out.last()
                val isTurn = type != Type.STRAIGHT && type != Type.RAMP_LEFT && type != Type.RAMP_RIGHT
                if (isTurn && last.type != Type.DEPART && last.type != Type.ROUNDABOUT && cum[b] - last.distAlong < 40) {
                    // Dos giros casi seguidos (avenida con cantero central): una sola indicación
                    val merged = classify(turnAngle(lat, lon, cum, last.pointIndex, b))
                    out[out.size - 1] = Maneuver(merged, last.pointIndex, last.distAlong, curName)
                } else {
                    out.add(Maneuver(type, b, cum[b], curName))
                }
            }
            i++
        }
        val last = lat.size - 1
        out.add(Maneuver(Type.ARRIVE, last, cum[last], ""))
        return out
    }

    private fun classify(angle: Double): Type = when {
        abs(angle) < 25 -> Type.STRAIGHT
        abs(angle) < 50 -> if (angle < 0) Type.SLIGHT_LEFT else Type.SLIGHT_RIGHT
        abs(angle) < 130 -> if (angle < 0) Type.LEFT else Type.RIGHT
        abs(angle) < 165 -> if (angle < 0) Type.SHARP_LEFT else Type.SHARP_RIGHT
        else -> Type.UTURN
    }

    /** ¿Se puede salir de la rotonda en este nodo hacia una calle que no es la rotonda? */
    private fun hasExit(node: Int): Boolean {
        for (k in g.adjStart[node] until g.adjStart[node + 1]) {
            if (!g.isRoundabout(g.adjArc[k] shr 1)) return true
        }
        return false
    }

    /** Ángulo de giro en el punto b: negativo = izquierda, positivo = derecha. */
    private fun turnAngle(lat: DoubleArray, lon: DoubleArray, cum: DoubleArray, b: Int, b2: Int = b): Double {
        var a = b
        while (a > 0 && cum[b] - cum[a] < 20) a--
        var c = b2
        while (c < lat.size - 1 && cum[c] - cum[b2] < 20) c++
        if (a == b || c == b2) return 0.0
        val inB = bearing(lat[a], lon[a], lat[b], lon[b])
        val outB = bearing(lat[b2], lon[b2], lat[c], lon[c])
        return ((outB - inB) % 360 + 540) % 360 - 180
    }

    private fun bearing(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double =
        Math.toDegrees(atan2((bLon - aLon) * cos(Math.toRadians(aLat)), bLat - aLat))
}
