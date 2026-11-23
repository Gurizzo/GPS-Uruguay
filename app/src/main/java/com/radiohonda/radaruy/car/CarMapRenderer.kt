package com.radiohonda.radaruy.car

import android.app.Presentation
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import android.widget.FrameLayout
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.radiohonda.radaruy.Drive
import com.radiohonda.radaruy.HudView
import com.radiohonda.radaruy.MapController
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import kotlin.math.ln

/**
 * Dibuja el mapa en la pantalla del auto. Android Auto entrega una "superficie";
 * se crea una pantalla virtual sobre ella y se muestra un MapView normal adentro.
 *
 * Android Auto avisa qué parte de la superficie queda libre (onVisibleAreaChanged):
 * en la vista dividida, la tarjeta de Spotify ocupa un costado y el mapa/HUD se
 * acomodan al resto.
 */
class CarMapRenderer(
    private val carContext: CarContext,
    lifecycle: Lifecycle,
) : SurfaceCallback, DefaultLifecycleObserver {

    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var mapView: MapView? = null
    private var hud: HudView? = null
    private var controller: MapController? = null
    private var visibleArea: Rect? = null
    private var surfaceW = 0
    private var surfaceH = 0
    private val hudListener = Drive.Listener { hud?.setState(it) }

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        teardown()
    }

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        val surface = container.surface ?: return
        teardown()
        surfaceW = container.width
        surfaceH = container.height
        val dm = carContext.getSystemService(DisplayManager::class.java)
        val vd = dm.createVirtualDisplay(
            "RadarUY", container.width, container.height, container.dpi, surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        )
        display = vd

        val pres = Presentation(carContext, vd.display)
        val ctx = pres.context
        MapLibre.getInstance(carContext.applicationContext)
        val mv = MapView(ctx, MapLibreMapOptions.createFromAttributes(ctx).textureMode(true))
        val h = HudView(ctx, scale = 0.9f)
        val root = FrameLayout(ctx)
        root.addView(mv)
        root.addView(h)
        pres.setContentView(root)
        try {
            pres.show()
        } catch (e: Exception) {
            Log.e("CarMap", "No se pudo mostrar el mapa", e)
            return
        }
        presentation = pres
        mapView = mv
        hud = h
        mv.onCreate(null)
        mv.onStart()
        mv.onResume()
        Drive.addListener(hudListener)
        visibleArea?.let { h.setContentArea(it) }

        mv.getMapAsync { map ->
            if (mapView !== mv) return@getMapAsync
            controller = MapController(ctx, map, iconScale = 1.15f, followOffset = 0.3f).also {
                it.viewHeight = surfaceH
                it.start()
                applyPadding()
            }
        }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = Rect(visibleArea)
        hud?.setContentArea(visibleArea)
        applyPadding()
    }

    override fun onStableAreaChanged(stableArea: Rect) {}

    private fun applyPadding() {
        val a = visibleArea ?: return
        // El controlador baja la flecha dentro de la zona visible (más en la vista 3D)
        controller?.setPadding(a.left, a.top, surfaceW - a.right, surfaceH - a.bottom)
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        teardown()
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        val mv = mapView ?: return
        controller?.follow = false
        mv.getMapAsync { it.scrollBy(-distanceX, -distanceY) }
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        val mv = mapView ?: return
        controller?.follow = false
        mv.getMapAsync {
            it.moveCamera(CameraUpdateFactory.zoomBy(ln(scaleFactor.toDouble()) / ln(2.0),
                android.graphics.Point(focusX.toInt(), focusY.toInt())))
        }
    }

    /** Se llama al tocar un negocio o radar en la pantalla del auto. */
    var onPoiTapped: ((MapController.PoiHit) -> Unit)? = null

    override fun onClick(x: Float, y: Float) {
        val hit = controller?.poiAt(x, y) ?: return
        onPoiTapped?.invoke(hit)
    }

    fun toggleView() = controller?.toggleView()
    val is3d: Boolean get() = controller?.is3d ?: com.radiohonda.radaruy.Prefs.view3d(carContext)

    fun recenter() = controller?.recenter()
    fun zoom(delta: Double) = controller?.zoomBy(delta)

    private fun teardown() {
        Drive.removeListener(hudListener)
        controller?.destroy()
        controller = null
        mapView?.let {
            it.onPause(); it.onStop(); it.onDestroy()
        }
        mapView = null
        hud = null
        presentation?.dismiss()
        presentation = null
        display?.release()
        display = null
    }
}
