package com.radiohonda.radaruy.routing

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.radiohonda.radaruy.Drive
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Navegación giro a giro: calcula la ruta, sigue el avance con el GPS, recalcula si
 * te desviás y dice las indicaciones en voz alta. Compartido por el teléfono y el auto.
 */
object Navigator : Drive.Listener {

    data class State(
        val route: Route? = null,
        val calculating: Boolean = false,
        val error: String? = null,
        /** Metros recorridos sobre la ruta. */
        val along: Double = 0.0,
        val next: Maneuver? = null,
        val afterNext: Maneuver? = null,
        val distToNext: Double = 0.0,
        val remainingDist: Double = 0.0,
        val remainingTime: Double = 0.0,
        val arrived: Boolean = false,
        /** Índice del punto de la ruta donde está el auto (para dibujar solo lo que falta). */
        val index: Int = 0,
    ) {
        val active get() = route != null || calculating
    }

    fun interface Listener { fun onNavState(s: State) }

    @Volatile var state = State(); private set
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var graphFuture: Future<RoadGraph>? = null
    private var dest: DoubleArray? = null
    private var destName = ""
    private var offCount = 0
    private var lastReroute = 0L
    private var announced = HashMap<Int, Int>() // maniobra -> etapa anunciada (1 = lejos, 2 = cerca)
    private var lastIndex = 0

    fun init(context: Context) {
        if (graphFuture != null) return
        val app = context.applicationContext
        Voice.init(app)
        graphFuture = worker.submit<RoadGraph> { RoadGraph.load(app) }
        Drive.addListener(this)
    }

    fun addListener(l: Listener) { listeners.add(l); l.onNavState(state) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private fun publish(s: State) {
        state = s
        main.post { listeners.forEach { it.onNavState(s) } }
    }

    /** Empieza a navegar hacia un destino desde la posición actual. */
    fun navigateTo(lat: Double, lon: Double, name: String) {
        dest = doubleArrayOf(lat, lon)
        destName = name
        announced.clear()
        lastIndex = 0
        val d = Drive.state
        if (!d.hasFix) {
            publish(State(calculating = true))
            pendingStart = true
            return
        }
        calculate(d.lat, d.lon, d.bearing, first = true)
    }

    private var pendingStart = false

    fun stop() {
        dest = null
        pendingStart = false
        publish(State())
    }

    private fun calculate(lat: Double, lon: Double, bearing: Float?, first: Boolean) {
        val target = dest ?: return
        if (first) publish(State(calculating = true))
        lastReroute = System.currentTimeMillis()
        val name = destName
        worker.execute {
            val route = try {
                Router(graphFuture!!.get()).route(lat, lon, bearing, target[0], target[1], name)
            } catch (e: Exception) {
                Log.e("Navigator", "Error calculando ruta", e)
                null
            }
            main.post {
                if (dest !== target) return@post // se canceló mientras calculaba
                if (route == null) {
                    if (first) publish(State(error = "No se encontró un camino hasta el destino"))
                    return@post
                }
                announced.clear()
                lastIndex = 0
                offCount = 0
                val s = progress(route, lat, lon)
                publish(s)
                if (first) {
                    val km = route.totalDistance / 1000
                    val min = (route.totalTime / 60).roundToInt()
                    val intro = if (km >= 1) "Ruta de ${"%.0f".format(km)} kilómetros, unos $min minutos. " else ""
                    Voice.say(intro + (route.maneuvers.firstOrNull()?.speech() ?: ""))
                } else {
                    Voice.say("Ruta recalculada")
                }
            }
        }
    }

    override fun onDriveState(s: Drive.State) {
        if (!s.hasFix) return
        if (pendingStart && dest != null) {
            pendingStart = false
            calculate(s.lat, s.lon, s.bearing, first = true)
            return
        }
        val route = state.route ?: return
        if (state.arrived) return
        val p = progress(route, s.lat, s.lon)
        // ¿Fuera de la ruta?
        if (offDistance > 45) offCount++ else offCount = 0
        if (offCount >= 3 && System.currentTimeMillis() - lastReroute > 8000) {
            offCount = 0
            calculate(s.lat, s.lon, s.bearing, first = false)
        }
        publish(p)
        speak(p, s.speedKmh / 3.6)
        if (p.arrived) main.postDelayed({ if (state.arrived) stop() }, 20_000)
    }

    private var offDistance = 0.0

    private fun progress(route: Route, lat: Double, lon: Double): State {
        // Buscar el tramo más cercano cerca de la última posición conocida
        val mLat = 111_132.0
        val mLon = 111_320.0 * cos(Math.toRadians(lat))
        val from = (lastIndex - 3).coerceAtLeast(0)
        val to = (lastIndex + 80).coerceAtMost(route.size - 2)
        var best = Double.MAX_VALUE
        var bi = lastIndex
        var bt = 0.0
        for (i in from..to) {
            val ax = (route.lon[i] - lon) * mLon; val ay = (route.lat[i] - lat) * mLat
            val bx = (route.lon[i + 1] - lon) * mLon; val by = (route.lat[i + 1] - lat) * mLat
            val vx = bx - ax; val vy = by - ay
            val l2 = vx * vx + vy * vy
            val t = if (l2 > 0) ((-ax * vx - ay * vy) / l2).coerceIn(0.0, 1.0) else 0.0
            val qx = ax + t * vx; val qy = ay + t * vy
            val d = sqrt(qx * qx + qy * qy)
            if (d < best) { best = d; bi = i; bt = t }
        }
        offDistance = best
        lastIndex = bi
        val along = route.cumDist[bi] + bt * (route.cumDist[bi + 1] - route.cumDist[bi])
        val idx = route.maneuvers.indexOfFirst { it.type != Maneuver.Type.DEPART && it.distAlong > along + 3 }
        val next = route.maneuvers.getOrNull(idx)
        val afterNext = if (idx >= 0) route.maneuvers.getOrNull(idx + 1) else null
        val remaining = (route.totalDistance - along).coerceAtLeast(0.0)
        return State(
            route = route,
            along = along,
            next = next,
            afterNext = afterNext,
            distToNext = (next?.distAlong ?: route.totalDistance) - along,
            remainingDist = remaining,
            remainingTime = (route.totalTime - route.timeAt(along, bi)).coerceAtLeast(0.0),
            arrived = remaining < 25,
            index = bi,
        )
    }

    private fun speak(p: State, speedMs: Double) {
        val m = p.next ?: return
        val id = m.pointIndex
        val stage = announced[id] ?: 0
        if (m.type == Maneuver.Type.ARRIVE) {
            if (p.arrived && stage < 2) { announced[id] = 2; Voice.say("Llegaste a destino") }
            else if (stage < 1 && p.distToNext < farDistance(speedMs) && p.distToNext > 150) {
                announced[id] = 1; Voice.say("En ${spokenDistance(p.distToNext)}, llegás a destino")
            }
            return
        }
        val near = maxOf(35.0, speedMs * 5)
        if (stage < 2 && p.distToNext <= near) {
            announced[id] = 2
            var text = m.speech()
            // Si la siguiente maniobra viene enseguida, encadenarla
            val after = p.afterNext
            if (after != null && after.type != Maneuver.Type.ARRIVE && after.distAlong - m.distAlong < 120) {
                text += ", y luego " + after.speech().replaceFirstChar { it.lowercase() }
            }
            Voice.say(text)
        } else if (stage < 1 && p.distToNext <= farDistance(speedMs) && p.distToNext > near + 80) {
            announced[id] = 1
            Voice.say("En ${spokenDistance(p.distToNext)}, " + m.speech().replaceFirstChar { it.lowercase() })
        }
    }

    private fun farDistance(speedMs: Double) = when {
        speedMs > 70 / 3.6 -> 1000.0
        speedMs > 40 / 3.6 -> 450.0
        else -> 220.0
    }

    private fun spokenDistance(m: Double): String = when {
        m >= 1000 -> {
            val km = (m / 100).roundToInt() / 10.0
            if (km == km.toInt().toDouble()) "${km.toInt()} kilómetro${if (km.toInt() > 1) "s" else ""}"
            else "${"%.1f".format(km).replace('.', ',')} kilómetros"
        }
        m >= 100 -> "${(m / 50).roundToInt() * 50} metros"
        else -> "${(m / 10).roundToInt() * 10} metros"
    }

    /** "350 m", "1,2 km" */
    fun formatDistance(m: Double): String = when {
        m >= 10_000 -> "${(m / 1000).roundToInt()} km"
        m >= 1000 -> "${"%.1f".format(m / 1000).replace('.', ',')} km"
        m >= 100 -> "${(m / 50).roundToInt() * 50} m"
        else -> "${(m / 10).roundToInt() * 10} m"
    }

    fun formatDuration(s: Double): String {
        val min = (s / 60).roundToInt()
        return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
    }
}
