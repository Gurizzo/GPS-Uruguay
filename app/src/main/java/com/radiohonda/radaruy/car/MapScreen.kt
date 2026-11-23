package com.radiohonda.radaruy.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Maneuver as CarManeuver
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.radiohonda.radaruy.Icons
import com.radiohonda.radaruy.R
import com.radiohonda.radaruy.routing.Maneuver
import com.radiohonda.radaruy.routing.Navigator
import java.util.TimeZone

/**
 * Pantalla principal en el auto: el mapa con botones y, al navegar, la tarjeta de
 * indicaciones de Android Auto (flecha, distancia, calle y hora de llegada).
 */
class MapScreen(carContext: CarContext, private val renderer: CarMapRenderer) : Screen(carContext) {

    private val navManager = carContext.getCarService(NavigationManager::class.java)
    private var navigating = false
    private var lastError: String? = null
    private val iconCache = HashMap<Maneuver.Type, CarIcon>()

    private val navListener = Navigator.Listener { n ->
        val active = n.route != null
        if (active && !navigating) { navManager.navigationStarted(); navigating = true }
        if (!active && navigating) { navManager.navigationEnded(); navigating = false }
        if (n.error != null && n.error != lastError) {
            CarToast.makeText(carContext, n.error, CarToast.LENGTH_LONG).show()
        }
        lastError = n.error
        invalidate()
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    init {
        renderer.onPoiTapped = { hit -> screenManager.push(PlaceScreen(carContext, hit)) }
        navManager.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() = Navigator.stop()
            override fun onAutoDriveEnabled() {}
        })
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) = Navigator.addListener(navListener)
            override fun onDestroy(owner: LifecycleOwner) {
                Navigator.removeListener(navListener)
                if (navigating) navManager.navigationEnded()
            }
        })
    }

    private fun icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    override fun onGetTemplate(): Template {
        val n = Navigator.state
        val actions = ActionStrip.Builder()
        if (n.active) {
            actions.addAction(Action.Builder().setTitle("Salir")
                .setOnClickListener { Navigator.stop() }.build())
        } else {
            actions.addAction(Action.Builder().setTitle("Buscar")
                .setOnClickListener { screenManager.push(SearchScreen(carContext)) }.build())
        }
        actions.addAction(Action.Builder().setTitle("Cerca")
            .setOnClickListener { screenManager.push(NearbyMenuScreen(carContext)) }.build())
        actions.addAction(Action.Builder().setTitle("Negocios")
            .setOnClickListener { screenManager.push(CategoriesScreen(carContext)) }.build())
        actions.addAction(Action.Builder().setIcon(icon(R.drawable.ic_recenter))
            .setOnClickListener { renderer.recenter() }.build())

        val mapActions = ActionStrip.Builder()
            .addAction(Action.PAN)
            .addAction(Action.Builder().setIcon(icon(R.drawable.ic_zoom_in))
                .setOnClickListener { renderer.zoom(1.0) }.build())
            .addAction(Action.Builder().setIcon(icon(R.drawable.ic_zoom_out))
                .setOnClickListener { renderer.zoom(-1.0) }.build())
            .addAction(Action.Builder()
                .setIcon(CarIcon.Builder(IconCompat.createWithBitmap(
                    Icons.ui(if (renderer.is3d) Icons.Ui.VIEW_2D else Icons.Ui.VIEW_3D, 96))).build())
                .setOnClickListener { renderer.toggleView(); main.postDelayed({ invalidate() }, 300) }.build())
            .build()

        val b = NavigationTemplate.Builder()
            .setActionStrip(actions.build())
            .setMapActionStrip(mapActions)

        val route = n.route
        val next = n.next
        if (n.calculating && route == null) {
            b.setNavigationInfo(RoutingInfo.Builder().setLoading(true).build())
        } else if (route != null && next != null) {
            val info = RoutingInfo.Builder().setCurrentStep(step(next), distance(n.distToNext))
            n.afterNext?.let { info.setNextStep(step(it)) }
            b.setNavigationInfo(info.build())
            val arrival = System.currentTimeMillis() + (n.remainingTime * 1000).toLong()
            b.setDestinationTravelEstimate(
                TravelEstimate.Builder(distance(n.remainingDist), DateTimeWithZone.create(arrival, TimeZone.getDefault()))
                    .setRemainingTimeSeconds(n.remainingTime.toLong())
                    .build()
            )
        }
        return b.build()
    }

    private fun step(m: Maneuver): Step {
        val cm = CarManeuver.Builder(carType(m.type))
            .setIcon(iconCache.getOrPut(m.type) {
                CarIcon.Builder(IconCompat.createWithBitmap(Icons.maneuver(m.type, 128))).build()
            })
        if (m.type == Maneuver.Type.ROUNDABOUT) cm.setRoundaboutExitNumber(m.exit.coerceAtLeast(1))
        val s = Step.Builder(m.text()).setManeuver(cm.build())
        if (m.road.isNotBlank()) s.setRoad(m.road)
        return s.build()
    }

    private fun distance(m: Double): Distance = when {
        m >= 10_000 -> Distance.create(Math.round(m / 1000).toDouble(), Distance.UNIT_KILOMETERS)
        m >= 1000 -> Distance.create(Math.round(m / 100) / 10.0, Distance.UNIT_KILOMETERS_P1)
        m >= 100 -> Distance.create((Math.round(m / 50) * 50).toDouble(), Distance.UNIT_METERS)
        else -> Distance.create((Math.round(m / 10) * 10).toDouble(), Distance.UNIT_METERS)
    }

    private fun carType(t: Maneuver.Type): Int = when (t) {
        Maneuver.Type.DEPART -> CarManeuver.TYPE_DEPART
        Maneuver.Type.STRAIGHT -> CarManeuver.TYPE_STRAIGHT
        Maneuver.Type.SLIGHT_LEFT -> CarManeuver.TYPE_TURN_SLIGHT_LEFT
        Maneuver.Type.SLIGHT_RIGHT -> CarManeuver.TYPE_TURN_SLIGHT_RIGHT
        Maneuver.Type.LEFT -> CarManeuver.TYPE_TURN_NORMAL_LEFT
        Maneuver.Type.RIGHT -> CarManeuver.TYPE_TURN_NORMAL_RIGHT
        Maneuver.Type.SHARP_LEFT -> CarManeuver.TYPE_TURN_SHARP_LEFT
        Maneuver.Type.SHARP_RIGHT -> CarManeuver.TYPE_TURN_SHARP_RIGHT
        Maneuver.Type.UTURN -> CarManeuver.TYPE_U_TURN_LEFT
        // En Uruguay se circula por la derecha: rotondas en sentido antihorario
        Maneuver.Type.ROUNDABOUT -> CarManeuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW
        Maneuver.Type.RAMP_LEFT -> CarManeuver.TYPE_OFF_RAMP_SLIGHT_LEFT
        Maneuver.Type.RAMP_RIGHT -> CarManeuver.TYPE_OFF_RAMP_SLIGHT_RIGHT
        Maneuver.Type.ARRIVE -> CarManeuver.TYPE_DESTINATION
    }
}
