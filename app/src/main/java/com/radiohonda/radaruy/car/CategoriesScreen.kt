package com.radiohonda.radaruy.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import com.radiohonda.radaruy.PoiCategory
import com.radiohonda.radaruy.Prefs

/**
 * Lista de tipos de negocio con interruptores. Android Auto limita las filas por
 * pantalla (sobre todo manejando), así que se pagina con "Más tipos…".
 */
class CategoriesScreen(carContext: CarContext, private val start: Int = 0) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val limit = try {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        } catch (e: Exception) {
            6
        }.coerceAtLeast(3)

        val all = PoiCategory.entries
        val selected = Prefs.categories(carContext)
        val remaining = all.size - start
        val perPage = if (remaining > limit) limit - 1 else remaining
        val list = ItemList.Builder()
        for (cat in all.subList(start, start + perPage)) {
            list.addItem(Row.Builder()
                .setTitle(cat.label)
                .setToggle(Toggle.Builder { on ->
                    Prefs.setCategory(carContext, cat.key, on)
                }.setChecked(cat.key in selected).build())
                .build())
        }
        if (start + perPage < all.size) {
            list.addItem(Row.Builder()
                .setTitle("Más tipos…")
                .setBrowsable(true)
                .setOnClickListener { screenManager.push(CategoriesScreen(carContext, start + perPage)) }
                .build())
        }
        return ListTemplate.Builder()
            .setTitle("Negocios en el mapa")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}
