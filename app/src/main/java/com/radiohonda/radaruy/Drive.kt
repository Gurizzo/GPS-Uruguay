package com.radiohonda.radaruy

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Estado de manejo compartido por el teléfono y Android Auto: posición, velocidad,
 * límite de la calle actual y radar próximo.
 */
object Drive {
    data class State(
        val hasFix: Boolean = false,
        val lat: Double = -34.9055,
        val lon: Double = -56.1913,
        val bearing: Float? = null,
        val speedKmh: Int = 0,
        val limit: SpeedLimitIndex.Limit? = null,
        val radar: RadarIndex.Ahead? = null,
        val trail: List<DoubleArray> = emptyList(),
    ) {
        val overLimit: Boolean get() = limit != null && speedKmh > limit.kmh + 3
        /** Límite que aplica en el radar (el del radar si lo tiene, si no el de la calle). */
        val radarLimit: Int? get() = radar?.radar?.maxspeed?.takeIf { it > 0 } ?: limit?.kmh
    }

    fun interface Listener { fun onDriveState(s: State) }

    @Volatile var state = State(); private set
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    @Volatile private var limits: SpeedLimitIndex? = null
    @Volatile private var radars: RadarIndex? = null
    private var users = 0
    private var locationManager: LocationManager? = null
    private var tone: ToneGenerator? = null
    private val trail = ArrayDeque<DoubleArray>()
    private var lastLimit: SpeedLimitIndex.Limit? = null
    private var lastLimitTime = 0L
    private var announcedRadar = -1
    private var warnedRadar = -1

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        thread(name = "drive-data") {
            try {
                radars = RadarIndex.load(app)
                limits = SpeedLimitIndex.load(app)
            } catch (e: Exception) {
                Log.e("Drive", "No se pudieron cargar los datos", e)
            }
        }
    }

    fun hasPermission(c: Context) =
        ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun addListener(l: Listener) { listeners.add(l); l.onDriveState(state) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    /** El teléfono o el auto empiezan a usar el GPS. */
    fun acquire(c: Context) {
        init(c)
        users++
        if (users == 1) startTracking()
    }

    fun release(c: Context) {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) {
            app.stopService(Intent(app, DriveService::class.java))
            stopLocation()
        }
    }

    /** Llamar cuando se concede el permiso de ubicación. */
    fun onPermissionGranted() {
        if (users > 0) startTracking()
    }

    private fun startTracking() {
        if (!hasPermission(app)) return
        // Servicio en primer plano: mantiene el GPS activo con la pantalla del teléfono apagada
        try {
            ContextCompat.startForegroundService(app, Intent(app, DriveService::class.java))
        } catch (e: Exception) {
            Log.w("Drive", "No se pudo iniciar el servicio en primer plano", e)
        }
        startLocation()
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (locationManager != null || !hasPermission(app)) return
        val lm = app.getSystemService(LocationManager::class.java)
        locationManager = lm
        lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let(::onLocation)
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
        if (lm.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, listener, Looper.getMainLooper())
        }
    }

    private fun stopLocation() {
        locationManager?.removeUpdates(listener)
        locationManager = null
    }

    private val listener = LocationListener { onLocation(it) }

    private fun onLocation(loc: Location) {
        // Si hay GPS, ignorar las posiciones de red (menos precisas)
        if (loc.provider == LocationManager.NETWORK_PROVIDER && state.hasFix &&
            System.currentTimeMillis() - lastGps < 10_000) return
        if (loc.provider == LocationManager.GPS_PROVIDER) lastGps = System.currentTimeMillis()

        val speedMs = if (loc.hasSpeed()) loc.speed else 0f
        val kmh = (speedMs * 3.6f).roundToInt()
        val bearing = if (loc.hasBearing() && speedMs > 1.5f) loc.bearing else null

        // Límite: si por un momento no se encuentra la calle, mantener el último unos segundos
        var limit = limits?.query(loc.latitude, loc.longitude, bearing)
        val now = System.currentTimeMillis()
        if (limit != null) { lastLimit = limit; lastLimitTime = now }
        else if (now - lastLimitTime < 8000) limit = lastLimit

        val radar = radars?.ahead(loc.latitude, loc.longitude, bearing)
        if (speedMs > 1.5f || trail.isEmpty()) {
            trail.addLast(doubleArrayOf(loc.longitude, loc.latitude))
            while (trail.size > 400) trail.removeFirst()
        }

        val s = State(true, loc.latitude, loc.longitude, bearing ?: state.bearing, kmh, limit, radar, trail.toList())
        state = s
        alerts(s)
        main.post { listeners.forEach { it.onDriveState(s) } }
    }

    private var lastGps = 0L

    private fun alerts(s: State) {
        if (!Prefs.radarSound(app)) return
        val r = s.radar ?: return
        if (r.radar.id != announcedRadar && r.distanceM <= 500) {
            announcedRadar = r.radar.id
            beep(ToneGenerator.TONE_PROP_BEEP2, 400)
        }
        val lim = s.radarLimit
        if (lim != null && s.speedKmh > lim + 3 && r.distanceM <= 300 && r.radar.id != warnedRadar) {
            warnedRadar = r.radar.id
            beep(ToneGenerator.TONE_CDMA_HIGH_L, 900)
        }
    }

    private fun beep(type: Int, ms: Int) {
        try {
            val t = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 90).also { tone = it }
            t.startTone(type, ms)
        } catch (e: RuntimeException) {
            Log.w("Drive", "Sin sonido", e)
        }
    }
}
