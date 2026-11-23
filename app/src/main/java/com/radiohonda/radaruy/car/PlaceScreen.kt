package com.radiohonda.radaruy.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import com.radiohonda.radaruy.Drive
import com.radiohonda.radaruy.Icons
import com.radiohonda.radaruy.MapController
import com.radiohonda.radaruy.Prefs
import com.radiohonda.radaruy.routing.Geo
import com.radiohonda.radaruy.routing.Navigator
import com.radiohonda.radaruy.routing.SearchIndex

/** Tarjeta de un negocio tocado en el mapa del auto, con el botón "Ir". */
class PlaceScreen(carContext: CarContext, private val hit: MapController.PoiHit) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val d = Drive.state
        val dist = if (d.hasFix) Navigator.formatDistance(Geo.distance(d.lat, d.lon, hit.lat, hit.lon)) else null
        val msg = listOfNotNull(hit.category?.label ?: if (hit.radar) "Radar" else null, dist).joinToString(" · ")
        val bmp = when {
            hit.radar -> Icons.radar("speed", 128)
            hit.category != null -> Icons.poi(hit.category, 128)
            else -> Icons.destination(128)
        }
        val go = Action.Builder()
            .setTitle("Ir")
            .setBackgroundColor(CarColor.createCustom(0xFF2FE6E9.toInt(), 0xFF2FE6E9.toInt()))
            .setOnClickListener {
                Prefs.addRecent(carContext, SearchIndex.Place(hit.name, hit.category?.label ?: "", hit.lat, hit.lon))
                Navigator.navigateTo(hit.lat, hit.lon, hit.name)
                screenManager.popToRoot()
            }.build()
        return MessageTemplate.Builder(msg.ifEmpty { " " })
            .setTitle(hit.name)
            .setHeaderAction(Action.BACK)
            .setIcon(CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build())
            .addAction(go)
            .addAction(Action.Builder().setTitle("Cancelar").setOnClickListener { screenManager.pop() }.build())
            .build()
    }
}
