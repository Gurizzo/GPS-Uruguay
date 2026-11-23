package com.radiohonda.radaruy.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import com.radiohonda.radaruy.Drive
import com.radiohonda.radaruy.Icons
import com.radiohonda.radaruy.NearbyIndex
import com.radiohonda.radaruy.PoiCategory
import com.radiohonda.radaruy.Prefs
import com.radiohonda.radaruy.routing.Navigator
import com.radiohonda.radaruy.routing.SearchIndex

private fun CarContext.listLimit(): Int = try {
    getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
} catch (e: Exception) {
    6
}.coerceAtLeast(3)

/** Menú "Cerca": tipos de lugar. Se pagina porque Android Auto limita las filas. */
class NearbyMenuScreen(carContext: CarContext, private val start: Int = 0) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val all = PoiCategory.nearbyOrder
        val limit = carContext.listLimit()
        val remaining = all.size - start
        val perPage = if (remaining > limit) limit - 1 else remaining
        val list = ItemList.Builder()
        for (cat in all.subList(start, start + perPage)) {
            list.addItem(Row.Builder()
                .setTitle(cat.label)
                .setImage(CarIcon.Builder(IconCompat.createWithBitmap(Icons.poi(cat, 96))).build())
                .setBrowsable(true)
                .setOnClickListener { screenManager.push(NearbyPlacesScreen(carContext, cat)) }
                .build())
        }
        if (start + perPage < all.size) {
            list.addItem(Row.Builder().setTitle("Más…").setBrowsable(true)
                .setOnClickListener { screenManager.push(NearbyMenuScreen(carContext, start + perPage)) }
                .build())
        }
        return ListTemplate.Builder()
            .setTitle("¿Qué buscás cerca?")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}

/** Lugares de un tipo, del más cercano al más lejano, calculado al abrir la pantalla. */
class NearbyPlacesScreen(carContext: CarContext, private val cat: PoiCategory) : Screen(carContext) {

    private val from = Drive.state
    private val places = NearbyIndex.nearest(cat, from.lat, from.lon, limit = 20)

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        for (p in places.take(carContext.listLimit())) {
            list.addItem(Row.Builder()
                .setTitle(p.name)
                .addText(NearbyIndex.describe(p, from.lat, from.lon))
                .setOnClickListener {
                    Prefs.addRecent(carContext, SearchIndex.Place(p.name, cat.label, p.lat, p.lon))
                    Navigator.navigateTo(p.lat, p.lon, p.name)
                    screenManager.popToRoot()
                }
                .build())
        }
        if (places.isEmpty()) list.setNoItemsMessage("No hay lugares de este tipo cargados")
        return ListTemplate.Builder()
            .setTitle(cat.label + if (from.hasFix) " cerca" else " (sin GPS)")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}
