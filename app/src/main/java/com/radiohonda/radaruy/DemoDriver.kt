package com.radiohonda.radaruy

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import com.radiohonda.radaruy.routing.Geo
import com.radiohonda.radaruy.routing.Navigator
import kotlin.math.atan2
import kotlin.math.cos

/**
 * Modo demo (solo versión debug, se activa por adb con `--ez demo true`):
 * simula un auto que sale de 18 de Julio, junto a Plaza Independencia, y navega hasta el
 * Palacio Legislativo. Ignora el GPS real, así las grabaciones no muestran la ubicación
 * de nadie. Sirve para grabar el GIF de la demo pública.
 */
object DemoDriver {
    private const val START_LAT = -34.90625
    private const val START_LON = -56.19830
    private const val DEST_LAT = -34.89130
    private const val DEST_LON = -56.18720
    private const val DEST_NAME = "Palacio Legislativo"
    private const val SPEED_KMH = 38.0
    /** Más rápido que la realidad para que el GIF sea corto. */
    private const val TIME_FACTOR = 3.0
    private const val TICK_MS = 250L

    private val main = Handler(Looper.getMainLooper())
    private var running = false

    fun start(context: Context) {
        if (running) return
        running = true
        Drive.init(context)
        Navigator.init(context)
        Drive.startDemo()
        inject(START_LAT, START_LON, 0f, 90f)
        // Esperar un instante a que la app esté lista y pedir la ruta
        main.postDelayed({ Navigator.navigateTo(DEST_LAT, DEST_LON, DEST_NAME) }, 1500)
        main.postDelayed(::waitRoute, 1700)
    }

    private fun waitRoute() {
        val route = Navigator.state.route
        if (route == null) { main.postDelayed(::waitRoute, 300); return }
        drive(route.lat, route.lon, 0.0)
    }

    /** Avanza por la polilínea de la ruta a velocidad constante. */
    private fun drive(lat: DoubleArray, lon: DoubleArray, along: Double) {
        if (!running) return
        val cum = DoubleArray(lat.size)
        for (i in 1 until lat.size) cum[i] = cum[i - 1] + Geo.distance(lat[i - 1], lon[i - 1], lat[i], lon[i])
        fun tick(d: Double) {
            if (!running) return
            if (d >= cum.last()) { inject(lat.last(), lon.last(), 0f, null); return }
            var i = 0
            while (i < cum.size - 2 && cum[i + 1] < d) i++
            val seg = cum[i + 1] - cum[i]
            val t = if (seg > 0) (d - cum[i]) / seg else 0.0
            val la = lat[i] + (lat[i + 1] - lat[i]) * t
            val lo = lon[i] + (lon[i + 1] - lon[i]) * t
            val brg = Math.toDegrees(atan2((lon[i + 1] - lon[i]) * cos(Math.toRadians(la)), lat[i + 1] - lat[i]))
            inject(la, lo, (SPEED_KMH / 3.6).toFloat(), ((brg + 360) % 360).toFloat())
            val step = SPEED_KMH / 3.6 * TICK_MS / 1000.0 * TIME_FACTOR
            main.postDelayed({ tick(d + step) }, TICK_MS)
        }
        tick(along)
    }

    private fun inject(lat: Double, lon: Double, speedMs: Float, bearing: Float?) {
        val loc = Location(Drive.DEMO_PROVIDER).apply {
            latitude = lat
            longitude = lon
            speed = speedMs
            if (bearing != null) this.bearing = bearing
            accuracy = 4f
            time = System.currentTimeMillis()
        }
        Drive.injectDemo(loc)
    }
}
