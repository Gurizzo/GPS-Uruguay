package com.radiohonda.radaruy

import android.content.Context
import java.io.File

/**
 * Los mapas viajan dentro del APK (archivos .pmtiles en assets). MapLibre los lee desde archivos
 * locales, así que la primera vez se copian al almacenamiento de la app.
 *  - uruguay.pmtiles: mapa base de OpenStreetMap (Protomaps)
 *  - buildings.pmtiles: edificios detectados por satélite que faltan en OpenStreetMap
 */
object MapFiles {
    private val lock = Any()

    /** Copia el archivo si hace falta (puede tardar: llamar fuera del hilo principal). */
    fun ensure(c: Context, asset: String = "uruguay.pmtiles"): File = synchronized(lock) {
        val f = File(c.filesDir, asset)
        val size = c.assets.openFd(asset).use { it.length }
        if (f.exists() && f.length() == size) return f
        val tmp = File(c.filesDir, "$asset.tmp")
        c.assets.open(asset).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
        if (f.exists()) f.delete()
        tmp.renameTo(f)
        f
    }

    /** Estilo del mapa con las rutas a los archivos locales. */
    fun styleJson(c: Context, asset: String = "style.json"): String {
        val base = ensure(c, "uruguay.pmtiles")
        val buildings = ensure(c, "buildings.pmtiles")
        val json = c.assets.open(asset).bufferedReader().use { it.readText() }
        return json.replace("{PMTILES}", "file://" + base.absolutePath)
            .replace("{BUILDINGS}", "file://" + buildings.absolutePath)
    }
}
