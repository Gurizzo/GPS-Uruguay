package com.radiohonda.radaruy

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.radiohonda.radaruy.routing.SearchIndex
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/** Preferencias compartidas entre el teléfono y Android Auto. */
object Prefs {
    private const val NAME = "radaruy"
    private const val KEY_CATS = "categories"
    private const val KEY_SOUND = "radar_sound"
    private const val KEY_RECENTS = "recents"
    private val DEFAULT_CATS = setOf("fuel", "pharmacy", "bank")

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun categories(c: Context): Set<String> =
        prefs(c).getStringSet(KEY_CATS, DEFAULT_CATS)?.toSet() ?: DEFAULT_CATS

    fun setCategory(c: Context, key: String, enabled: Boolean) {
        val s = categories(c).toMutableSet()
        if (enabled) s.add(key) else s.remove(key)
        prefs(c).edit().putStringSet(KEY_CATS, s).apply()
        notifyChanged()
    }

    /** Vista del mapa: false = noche 2D (desde arriba), true = ciudad 3D (detrás de la flecha). */
    fun view3d(c: Context): Boolean = prefs(c).getBoolean("view3d", false)

    fun setView3d(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("view3d", on).apply()
        notifyChanged()
    }

    fun radarSound(c: Context): Boolean = prefs(c).getBoolean(KEY_SOUND, true)

    fun setRadarSound(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(KEY_SOUND, on).apply()
        notifyChanged()
    }

    /** Lugares guardados: "home" (Casa) y "work" (Trabajo). */
    enum class Favorite(val key: String, val label: String) { HOME("home", "Casa"), WORK("work", "Trabajo") }

    fun favorite(c: Context, f: Favorite): SearchIndex.Place? {
        val raw = prefs(c).getString("fav_${f.key}", null) ?: return null
        return try {
            val o = JSONObject(raw)
            SearchIndex.Place(o.getString("n"), o.optString("s"), o.getDouble("lat"), o.getDouble("lon"))
        } catch (e: Exception) {
            null
        }
    }

    fun setFavorite(c: Context, f: Favorite, p: SearchIndex.Place?) {
        val e = prefs(c).edit()
        if (p == null) e.remove("fav_${f.key}")
        else e.putString("fav_${f.key}", JSONObject().put("n", p.name).put("s", p.subtitle)
            .put("lat", p.lat).put("lon", p.lon).toString())
        e.apply()
        notifyChanged()
    }

    /** Últimos destinos (el más reciente primero). */
    fun recents(c: Context): List<SearchIndex.Place> {
        val raw = prefs(c).getString(KEY_RECENTS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                SearchIndex.Place(o.getString("n"), o.optString("s"), o.getDouble("lat"), o.getDouble("lon"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addRecent(c: Context, p: SearchIndex.Place) {
        val list = listOf(p) + recents(c).filter { it.name != p.name || it.subtitle != p.subtitle }
        val arr = JSONArray()
        for (r in list.take(8)) {
            arr.put(JSONObject().put("n", r.name).put("s", r.subtitle).put("lat", r.lat).put("lon", r.lon))
        }
        prefs(c).edit().putString(KEY_RECENTS, arr.toString()).apply()
    }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    private fun notifyChanged() = main.post { listeners.forEach { it() } }
}
