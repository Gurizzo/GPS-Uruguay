package com.radiohonda.radaruy

import android.content.Context
import android.location.Location
import org.json.JSONObject
import kotlin.math.abs

/** Radares de assets/radars.geojson y búsqueda del próximo radar en nuestro camino. */
class RadarIndex private constructor(private val radars: List<Radar>) {

    data class Radar(val id: Int, val lat: Double, val lon: Double, val kind: String, val maxspeed: Int)
    data class Ahead(val radar: Radar, val distanceM: Int)

    private val tmp = FloatArray(2)

    /**
     * Radar más cercano delante del auto (hasta [range] metros y ±35° del rumbo).
     * Sin rumbo (auto detenido) solo avisa de radares a menos de 150 m.
     */
    fun ahead(lat: Double, lon: Double, bearing: Float?, range: Int = 800): Ahead? {
        var best: Ahead? = null
        for (r in radars) {
            if (abs(r.lat - lat) > 0.01 || abs(r.lon - lon) > 0.012) continue
            Location.distanceBetween(lat, lon, r.lat, r.lon, tmp)
            val d = tmp[0]
            if (d > range) continue
            if (bearing != null) {
                val diff = abs(((tmp[1] - bearing) % 360 + 540) % 360 - 180)
                if (diff > 35 && d > 40) continue
            } else if (d > 150) continue
            if (best == null || d < best.distanceM) best = Ahead(r, d.toInt())
        }
        return best
    }

    companion object {
        fun load(context: Context): RadarIndex {
            val json = context.assets.open("radars.geojson").bufferedReader().use { it.readText() }
            val feats = JSONObject(json).getJSONArray("features")
            val list = ArrayList<Radar>(feats.length())
            for (i in 0 until feats.length()) {
                val f = feats.getJSONObject(i)
                val c = f.getJSONObject("geometry").getJSONArray("coordinates")
                val p = f.getJSONObject("properties")
                list += Radar(i, c.getDouble(1), c.getDouble(0), p.optString("kind", "speed"), p.optInt("maxspeed", 0))
            }
            return RadarIndex(list)
        }
    }
}
