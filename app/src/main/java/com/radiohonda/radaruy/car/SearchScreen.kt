package com.radiohonda.radaruy.car

import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import com.radiohonda.radaruy.Drive
import com.radiohonda.radaruy.Prefs
import com.radiohonda.radaruy.routing.Navigator
import com.radiohonda.radaruy.routing.SearchIndex
import kotlin.concurrent.thread

/**
 * Búsqueda de destino en el auto. Con el auto en movimiento Android Auto bloquea el
 * teclado, pero se pueden elegir los destinos recientes (o dictar con el micrófono).
 */
class SearchScreen(carContext: CarContext) : Screen(carContext) {

    private var results: List<SearchIndex.Place> = Prefs.recents(carContext)
    private var query = ""
    private var loading = false
    private val main = Handler(Looper.getMainLooper())

    override fun onGetTemplate(): Template {
        val limit = try {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        } catch (e: Exception) {
            6
        }
        val list = ItemList.Builder()
        for (p in results.take(limit)) {
            val row = Row.Builder().setTitle(p.name)
            if (p.subtitle.isNotEmpty()) row.addText(p.subtitle)
            row.setOnClickListener { go(p) }
            list.addItem(row.build())
        }
        if (results.isEmpty()) {
            list.setNoItemsMessage(if (query.isBlank()) "Escribí una ciudad, calle o negocio" else "Sin resultados")
        }
        val b = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(text: String) = search(text)
            override fun onSearchSubmitted(text: String) = search(text)
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint("¿A dónde vamos?")
            .setShowKeyboardByDefault(false)
        if (loading) b.setLoading(true) else b.setItemList(list.build())
        return b.build()
    }

    private fun search(text: String) {
        query = text
        if (text.isBlank()) {
            results = Prefs.recents(carContext)
            invalidate()
            return
        }
        val d = Drive.state
        thread {
            val r = SearchIndex.search(text, d.lat, d.lon)
            main.post {
                if (query == text) { results = r; invalidate() }
            }
        }
    }

    private fun go(p: SearchIndex.Place) {
        Prefs.addRecent(carContext, p)
        Navigator.navigateTo(p.lat, p.lon, p.name)
        screenManager.pop()
    }
}
