package com.radiohonda.radaruy.car

import android.Manifest
import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.radiohonda.radaruy.Drive
import com.radiohonda.radaruy.routing.Navigator
import com.radiohonda.radaruy.routing.SearchIndex

/** Punto de entrada de Android Auto. */
class RadarCarAppService : CarAppService() {

    // Uso personal: se acepta cualquier host (Android Auto, emulador DHU).
    // Si algún día se publica en Play Store, restringir a los hosts de Google.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = RadarSession()
}

class RadarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        Drive.init(carContext)
        Navigator.init(carContext)
        SearchIndex.init(carContext)
        com.radiohonda.radaruy.NearbyIndex.init(carContext)
        val renderer = CarMapRenderer(carContext, lifecycle)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) = Drive.acquire(carContext)
            override fun onDestroy(owner: LifecycleOwner) = Drive.release(carContext)
        })
        if (!Drive.hasPermission(carContext)) {
            // Muestra el pedido de permiso en el teléfono
            carContext.requestPermissions(listOf(Manifest.permission.ACCESS_FINE_LOCATION)) { granted, _ ->
                if (granted.isNotEmpty()) Drive.onPermissionGranted()
            }
        }
        return MapScreen(carContext, renderer)
    }
}
