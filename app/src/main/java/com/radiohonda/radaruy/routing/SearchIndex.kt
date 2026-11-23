package com.radiohonda.radaruy.routing

import android.content.Context
import android.location.Location
import org.json.JSONArray
import java.text.Normalizer
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Buscador de destinos sin conexión: ciudades, barrios, calles y negocios. */
object SearchIndex {

    data class Place(val name: String, val subtitle: String, val lat: Double, val lon: Double, val kind: Int = 0)

    private class Entry(val place: Place, val norm: String, val rank: Int)

    private var future: Future<List<Entry>>? = null

    fun init(context: Context) {
        if (future != null) return
        val app = context.applicationContext
        future = Executors.newSingleThreadExecutor().submit<List<Entry>> {
            val arr = JSONArray(app.assets.open("search.json").bufferedReader().use { it.readText() })
            List(arr.length()) { i ->
                val e = arr.getJSONArray(i)
                val name = e.getString(0)
                Entry(Place(name, e.getString(2), e.getDouble(3), e.getDouble(4), e.getInt(1)), normalize(name), e.getInt(5))
            }
        }
    }

    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** Busca [text]; los resultados más cercanos a (lat, lon) aparecen primero. Bloquea si aún carga. */
    fun search(text: String, lat: Double, lon: Double, limit: Int = 30): List<Place> {
        val entries = future?.get() ?: return emptyList()
        val tokens = normalize(text).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        val tmp = FloatArray(1)
        val scored = ArrayList<Pair<Double, Place>>()
        for (e in entries) {
            if (!tokens.all { e.norm.contains(it) }) continue
            Location.distanceBetween(lat, lon, e.place.lat, e.place.lon, tmp)
            val km = tmp[0] / 1000.0
            var score = e.rank * 3.0 + minOf(km, 600.0) * 0.08
            if (e.norm.startsWith(tokens[0])) score -= 6
            if (e.norm == tokens.joinToString(" ")) score -= 10
            scored.add(score to e.place)
        }
        scored.sortBy { it.first }
        return scored.take(limit).map { it.second }
    }
}
