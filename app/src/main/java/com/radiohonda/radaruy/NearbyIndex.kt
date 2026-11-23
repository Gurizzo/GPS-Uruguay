package com.radiohonda.radaruy

import android.content.Context
import com.radiohonda.radaruy.routing.Geo
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Lugares de assets/pois.geojson agrupados por categoría, para listar los más cercanos. */
object NearbyIndex {

    data class Place(val name: String, val category: PoiCategory, val lat: Double, val lon: Double, val distanceM: Double)

    private class Poi(val name: String, val lat: Double, val lon: Double)

    private var future: Future<Map<String, List<Poi>>>? = null

    fun init(context: Context) {
        if (future != null) return
        val app = context.applicationContext
        future = Executors.newSingleThreadExecutor().submit<Map<String, List<Poi>>> {
            val json = app.assets.open("pois.geojson").bufferedReader().use { it.readText() }
            val feats = JSONObject(json).getJSONArray("features")
            val out = HashMap<String, MutableList<Poi>>()
            for (i in 0 until feats.length()) {
                val f = feats.getJSONObject(i)
                val c = f.getJSONObject("geometry").getJSONArray("coordinates")
                val p = f.getJSONObject("properties")
                out.getOrPut(p.getString("cat")) { ArrayList() }
                    .add(Poi(p.optString("name"), c.getDouble(1), c.getDouble(0)))
            }
            out
        }
    }

    /** Los [limit] lugares de la categoría más cercanos a (lat, lon), del más cercano al más lejano. */
    fun nearest(cat: PoiCategory, lat: Double, lon: Double, limit: Int = 40): List<Place> {
        val all = future?.get()?.get(cat.key) ?: return emptyList()
        return all.asSequence()
            .map { Place(it.name.ifBlank { sinNombre(cat) }, cat, it.lat, it.lon, Geo.distance(lat, lon, it.lat, it.lon)) }
            .sortedBy { it.distanceM }
            .take(limit)
            .toList()
    }

    private fun sinNombre(cat: PoiCategory) = when (cat) {
        PoiCategory.PHARMACY -> "Farmacia"
        PoiCategory.FUEL -> "Estación de servicio"
        PoiCategory.BANK -> "Banco / cajero"
        PoiCategory.POLICE -> "Comisaría"
        PoiCategory.HEALTH -> "Centro de salud"
        PoiCategory.PARKING -> "Estacionamiento"
        PoiCategory.MONUMENT -> "Lugar turístico"
        PoiCategory.NIGHTCLUB -> "Discoteca"
        PoiCategory.MALL -> "Shopping"
        PoiCategory.RESTAURANT -> "Restaurante"
        else -> "Lugar"
    } + " (sin nombre)"

    /** Distancia + punto cardinal, ej. "350 m · al norte". */
    fun describe(p: Place, fromLat: Double, fromLon: Double): String {
        val dLat = p.lat - fromLat
        val dLon = (p.lon - fromLon) * Math.cos(Math.toRadians(fromLat))
        val deg = (Math.toDegrees(Math.atan2(dLon, dLat)) + 360) % 360
        val dir = listOf("al norte", "al noreste", "al este", "al sureste", "al sur", "al suroeste", "al oeste", "al noroeste")[
            ((deg + 22.5) / 45).toInt() % 8]
        return com.radiohonda.radaruy.routing.Navigator.formatDistance(p.distanceM) + " · " + dir
    }
}
