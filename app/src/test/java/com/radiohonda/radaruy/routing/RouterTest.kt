package com.radiohonda.radaruy.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Prueba el motor de rutas con la red vial real de Uruguay (assets/graph.bin). */
class RouterTest {
    companion object {
        lateinit var graph: RoadGraph

        @BeforeClass @JvmStatic
        fun load() {
            val t = System.currentTimeMillis()
            graph = RoadGraph.load(File("src/main/assets/graph.bin").readBytes())
            println("grafo cargado en ${System.currentTimeMillis() - t} ms: ${graph.nodeCount} nodos")
        }
    }

    private fun run(label: String, aLat: Double, aLon: Double, bLat: Double, bLon: Double, bearing: Float? = null): Route {
        val t = System.currentTimeMillis()
        val r = Router(graph).route(aLat, aLon, bearing, bLat, bLon, label)
        val ms = System.currentTimeMillis() - t
        assertNotNull("sin ruta: $label", r)
        r!!
        println("\n== $label: ${"%.1f".format(r.totalDistance / 1000)} km, ${(r.totalTime / 60).toInt()} min, " +
            "${r.maneuvers.size} indicaciones, calculada en $ms ms")
        for (m in r.maneuvers.take(25)) {
            println("  ${"%6.0f".format(m.distAlong)} m  ${m.type.name.padEnd(12)} ${m.text()}")
        }
        if (r.maneuvers.size > 25) println("  … (${r.maneuvers.size - 25} más)")
        // Consistencia: distancias crecientes y extremos correctos
        for (i in 1 until r.size) assertTrue(r.cumDist[i] >= r.cumDist[i - 1])
        assertEquals(Maneuver.Type.DEPART, r.maneuvers.first().type)
        assertEquals(Maneuver.Type.ARRIVE, r.maneuvers.last().type)
        assertTrue("el inicio debe quedar cerca del origen",
            Geo.distance(r.lat[0], r.lon[0], aLat, aLon) < 300)
        assertTrue("el final debe quedar cerca del destino",
            Geo.distance(r.lat[r.size - 1], r.lon[r.size - 1], bLat, bLon) < 1000)
        checkLegal(r)
        return r
    }

    /** Nunca a contramano y nunca un giro prohibido. */
    private fun checkLegal(r: Route) {
        for (i in r.legEdges.indices) {
            val e = r.legEdges[i]
            if (graph.isOneway(e)) assertTrue("contramano en ${graph.name(e)}", r.legForward[i])
            if (i > 0) {
                val a = r.legEdges[i - 1] * 2 + if (r.legForward[i - 1]) 0 else 1
                val b = e * 2 + if (r.legForward[i]) 0 else 1
                val prev = if (i > 1) r.legEdges[i - 2] else -1
                if (r.legEdges[i - 1] != e) {
                    assertTrue("giro prohibido de ${graph.name(r.legEdges[i - 1])} a ${graph.name(e)}",
                        !graph.isTurnForbidden(prev, a, b))
                }
            }
        }
    }

    @Test fun rutasAlAzarSonLegales() {
        // 300 viajes al azar dentro de Montevideo
        val rnd = java.util.Random(7)
        var ok = 0
        var oneways = 0
        val t = System.currentTimeMillis()
        repeat(300) {
            val r = Router(graph).route(
                -34.92 + rnd.nextDouble() * 0.08, -56.24 + rnd.nextDouble() * 0.16, null,
                -34.92 + rnd.nextDouble() * 0.08, -56.24 + rnd.nextDouble() * 0.16, "x") ?: return@repeat
            checkLegal(r)
            oneways += r.legEdges.count { graph.isOneway(it) }
            ok++
        }
        println("\n== $ok rutas al azar en Montevideo sin contramano ni giros prohibidos " +
            "($oneways tramos de mano única recorridos bien), ${(System.currentTimeMillis() - t) / ok} ms por ruta")
        assertTrue(ok > 280)
    }

    @Test fun respetaProhibicionesDeGiro() {
        // Para cada giro prohibido: salir justo antes del cruce por la calle de entrada y
        // pedir ir justo después por la calle de salida. La ruta no puede hacer ese giro directo.
        var probadas = 0
        var desvios = 0
        val ejemplos = ArrayList<String>()
        for (i in graph.banFrom.indices) {
            val f = graph.banFrom[i]; val to = graph.banTo[i]; val v = graph.banVia[i]
            if (f == to || graph.length[f] < 40 || graph.length[to] < 40) continue
            val fp = graph.edgePoints(f)
            val tp = graph.edgePoints(to)
            // punto a ~15 m del cruce sobre cada calle
            fun near(p: DoubleArray, atEnd: Boolean, len: Float): DoubleArray {
                val n = p.size / 2
                val (ax, ay, bx, by) = if (atEnd) listOf(p[n * 2 - 2], p[n * 2 - 1], p[n * 2 - 4], p[n * 2 - 3])
                    else listOf(p[0], p[1], p[2], p[3])
                val seg = Geo.distance(ax, ay, bx, by)
                val fr = if (seg > 0) minOf(1.0, 15.0 / seg) else 0.0
                return doubleArrayOf(ax + (bx - ax) * fr, ay + (by - ay) * fr)
            }
            val a = near(fp, graph.to[f] == v, graph.length[f])
            val b = near(tp, graph.to[to] == v, graph.length[to])
            val r = Router(graph).route(a[0], a[1], null, b[0], b[1], "x") ?: continue
            if (r.legEdges.first() != f || r.legEdges.last() != to) continue
            probadas++
            for (k in 1 until r.legEdges.size) {
                assertTrue("giro prohibido usado", !(r.legEdges[k - 1] == f && r.legEdges[k] == to))
            }
            if (r.legEdges.size > 2) {
                desvios++
                if (ejemplos.size < 4) ejemplos += "${graph.name(f)} -> ${graph.name(to)}: " +
                    "desvío de ${r.totalDistance.toInt()} m (${r.maneuvers.joinToString(", ") { it.text() }})"
            }
            if (probadas >= 150) break
        }
        println("\n== $probadas giros prohibidos probados, en todos se evitó el giro ($desvios con desvío por otras calles)")
        ejemplos.forEach { println("  $it") }
        assertTrue(probadas > 50)
    }

    @Test fun pocitosAeropuerto() {
        val r = run("Pocitos -> Aeropuerto de Carrasco", -34.9100, -56.1500, -34.8380, -56.0300)
        assertTrue(r.totalDistance in 12_000.0..25_000.0)
    }

    @Test fun montevideoPuntaDelEste() {
        val r = run("Montevideo -> Punta del Este", -34.9058, -56.1890, -34.9625, -54.9450)
        assertTrue(r.totalDistance in 120_000.0..150_000.0)
    }

    @Test fun montevideoSalto() {
        val r = run("Montevideo -> Salto", -34.9058, -56.1890, -31.3880, -57.9620)
        assertTrue(r.totalDistance in 450_000.0..540_000.0)
    }

    @Test fun cortaMismaZona() {
        run("18 de Julio y Ejido -> Plaza Independencia", -34.9055, -56.1866, -34.9065, -56.2010)
    }

    @Test fun conRumboContrario() {
        // Arrancando hacia el oeste por 18 de Julio, con destino al este
        run("Arranque con rumbo oeste", -34.9058, -56.1890, -34.8950, -56.1600, bearing = 270f)
    }
}
