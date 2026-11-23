package com.radiohonda.radaruy

import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import com.radiohonda.radaruy.routing.Navigator
import com.radiohonda.radaruy.routing.Route
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.net.URI
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Configura un mapa MapLibre con una de dos vistas:
 *  - Noche 2D: azul oscuro visto desde arriba, ruta cian.
 *  - Ciudad 3D: mismos colores de noche, edificios en relieve, cámara detrás de la flecha, ruta rosa.
 * Agrega radares, negocios filtrados, ruta, destino y la flecha del auto.
 * Lo usan el teléfono y Android Auto.
 */
class MapController(
    private val context: Context,
    private val map: MapLibreMap,
    /** Tamaño de íconos (en Android Auto la pantalla está más lejos: se agrandan). */
    private val iconScale: Float = 1f,
    /** Fracción del alto en que se baja la flecha al seguir el auto (vista 2D). */
    private val followOffset: Float = 0f,
) : Drive.Listener, Navigator.Listener {

    /** Un negocio o radar tocado en el mapa. */
    data class PoiHit(val name: String, val category: PoiCategory?, val lat: Double, val lon: Double, val radar: Boolean)

    private class Palette(
        val route: Int, val routeCasing: Int, val trail: Int,
        val poiText: Int, val poiHalo: Int,
    )

    private val night = Palette(
        route = Color.parseColor("#2FE6E9"), routeCasing = Color.parseColor("#0B1430"),
        trail = Color.parseColor("#5C6DA6"), poiText = Color.parseColor("#C9D2F2"), poiHalo = Color.parseColor("#131A2E"),
    )
    /** Vista 3D: mismos colores que la noche, con la ruta rosa. */
    private val city = Palette(
        route = Color.parseColor("#FF2E93"), routeCasing = Color.parseColor("#0B1430"),
        trail = Color.parseColor("#5C6DA6"), poiText = Color.parseColor("#C9D2F2"), poiHalo = Color.parseColor("#131A2E"),
    )

    /** Si es true la cámara sigue al auto; se apaga cuando el usuario mueve el mapa. */
    var follow = true
        set(v) {
            field = v
            onFollowChanged?.invoke(v)
        }
    var onFollowChanged: ((Boolean) -> Unit)? = null
    /** Se llama cada vez que termina de cargar un estilo (la primera vez, el mapa está listo). */
    var onReady: (() -> Unit)? = null
    /** Toque sobre un negocio o radar (solo teléfono; en el auto se usa [poiAt]). */
    var onPoiClick: ((PoiHit) -> Unit)? = null

    var is3d = Prefs.view3d(context); private set
    private var style: Style? = null
    private var registered = false
    private var loadingStyle = false
    private val main = Handler(Looper.getMainLooper())
    private var basePadding = intArrayOf(0, 0, 0, 0)
    /** Margen efectivo (con la flecha bajada) que se aplica en cada movimiento de cámara. */
    private var camPadding = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
    /** Alto de la vista del mapa en píxeles (para calcular el margen superior). */
    var viewHeight = 0
        set(v) { field = v; applyPadding() }

    private val prefsListener: () -> Unit = {
        applyCategoryFilter()
        if (Prefs.view3d(context) != is3d) loadStyle(Prefs.view3d(context))
    }

    fun start() {
        map.uiSettings.apply {
            isCompassEnabled = false
            isLogoEnabled = false
            isAttributionEnabled = true
            isRotateGesturesEnabled = true
            isTiltGesturesEnabled = true
        }
        map.cameraPosition = CameraPosition.Builder()
            .target(LatLng(Drive.state.lat, Drive.state.lon)).zoom(13.0).build()
        map.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) follow = false
        }
        map.addOnMapClickListener { latLng ->
            val p = map.projection.toScreenLocation(latLng)
            val hit = poiAt(p.x, p.y)
            if (hit != null) onPoiClick?.invoke(hit)
            hit != null
        }
        loadStyle(is3d)
    }

    /** Cambia entre la vista noche 2D y ciudad 3D (y lo recuerda). */
    fun toggleView() {
        Prefs.setView3d(context, !is3d) // el listener de preferencias recarga el estilo
    }

    private fun loadStyle(view3d: Boolean) {
        if (loadingStyle) return
        loadingStyle = true
        is3d = view3d
        thread {
            val json = MapFiles.styleJson(context, if (view3d) "style3d.json" else "style.json")
            main.post {
                map.setStyle(Style.Builder().fromJson(json)) { s ->
                    loadingStyle = false
                    style = s
                    addOverlays(s, if (view3d) city else night)
                    shownRoute = null
                    if (!registered) {
                        registered = true
                        Prefs.addListener(prefsListener)
                        Drive.addListener(this)
                        Navigator.addListener(this)
                    } else {
                        onDriveState(Drive.state)
                        onNavState(Navigator.state)
                    }
                    applyPadding()
                    if (follow) recenter()
                    onReady?.invoke()
                    // Si mientras cargaba se pidió la otra vista, cargarla ahora
                    if (Prefs.view3d(context) != is3d) loadStyle(Prefs.view3d(context))
                }
            }
        }
    }

    fun destroy() {
        Prefs.removeListener(prefsListener)
        Drive.removeListener(this)
        Navigator.removeListener(this)
    }

    private fun addOverlays(s: Style, pal: Palette) {
        val dm = context.resources.displayMetrics.density
        val poiPx = (30 * dm * iconScale).toInt()
        val radarPx = (34 * dm * iconScale).toInt()
        for (cat in PoiCategory.entries) s.addImage("poi-${cat.key}", Icons.poi(cat, poiPx))
        for (k in listOf("speed", "average", "redlight")) s.addImage("radar-$k", Icons.radar(k, radarPx))
        s.addImage("me", Icons.me((46 * dm * iconScale).toInt()))
        s.addImage("dest", Icons.destination((40 * dm * iconScale).toInt(), pal.route))

        // Recorrido reciente (tenue)
        s.addSource(GeoJsonSource("trail"))
        s.addLayer(LineLayer("trail-line", "trail").withProperties(
            lineColor(pal.trail), lineOpacity(0.5f), lineWidth(4f * iconScale),
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))

        // Ruta (cian de noche, rosa en la vista ciudad)
        s.addSource(GeoJsonSource("route"))
        s.addLayer(LineLayer("route-casing", "route").withProperties(
            lineColor(pal.routeCasing), lineWidth(13f * iconScale),
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
        s.addLayer(LineLayer("route-glow", "route").withProperties(
            lineColor(pal.route), lineOpacity(0.3f), lineWidth(17f * iconScale),
            lineBlur(4f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
        s.addLayer(LineLayer("route-line", "route").withProperties(
            lineColor(pal.route), lineWidth(8f * iconScale),
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))

        // Negocios
        s.addSource(GeoJsonSource("pois", URI("asset://pois.geojson")))
        s.addLayer(SymbolLayer("pois", "pois").withProperties(
            iconImage(Expression.concat(literal("poi-"), get("cat"))),
            iconAllowOverlap(false),
            iconPadding(4f),
            textField(get("name")),
            textFont(arrayOf("NotoSansMedium")),
            textSize(11f * iconScale),
            textColor(pal.poiText),
            textHaloColor(pal.poiHalo),
            textHaloWidth(1.4f),
            textAnchor(Property.TEXT_ANCHOR_TOP),
            textOffset(arrayOf(0f, 1.3f)),
            textOptional(true),
            textMaxWidth(8f),
        ).apply { minZoom = 13f })
        applyCategoryFilter()

        // Radares (siempre visibles, encima de los negocios)
        s.addSource(GeoJsonSource("radars", URI("asset://radars.geojson")))
        s.addLayer(SymbolLayer("radars", "radars").withProperties(
            iconImage(Expression.concat(literal("radar-"), get("kind"))),
            iconAllowOverlap(true),
            iconIgnorePlacement(true),
            iconSize(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(8, 0.5f), Expression.stop(14, 1f))),
            textField(Expression.switchCase(
                Expression.gt(get("maxspeed"), literal(0)),
                Expression.concat(Expression.toString(get("maxspeed")), literal(" km/h")),
                literal(""))),
            textFont(arrayOf("NotoSansMedium")),
            textSize(11f * iconScale),
            textColor(Color.WHITE),
            textHaloColor(Color.parseColor("#B71C1C")),
            textHaloWidth(2f),
            textAnchor(Property.TEXT_ANCHOR_TOP),
            textOffset(arrayOf(0f, 1.6f)),
            textOptional(true),
        ).apply { minZoom = 8f })

        // Destino
        s.addSource(GeoJsonSource("dest"))
        s.addLayer(SymbolLayer("dest", "dest").withProperties(
            iconImage("dest"), iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            iconAllowOverlap(true), iconIgnorePlacement(true)))

        // Auto
        s.addSource(GeoJsonSource("me"))
        s.addLayer(SymbolLayer("me", "me").withProperties(
            iconImage("me"),
            iconRotate(get("bearing")),
            iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            iconAllowOverlap(true),
            iconIgnorePlacement(true),
        ))
    }

    private fun applyCategoryFilter() {
        val layer = style?.getLayerAs<SymbolLayer>("pois") ?: return
        val cats = Prefs.categories(context)
        layer.setFilter(
            if (cats.isEmpty()) literal(false)
            else Expression.any(*cats.map { Expression.eq(get("cat"), literal(it)) }.toTypedArray())
        )
    }

    /** Negocio o radar en ese punto de la pantalla (con un margen para el dedo). */
    fun poiAt(x: Float, y: Float): PoiHit? {
        if (style == null) return null
        val r = 22 * context.resources.displayMetrics.density * iconScale
        val box = RectF(x - r, y - r, x + r, y + r)
        val feats = map.queryRenderedFeatures(box, "radars", "pois")
        if (feats.isEmpty()) return null
        // el más cercano al dedo
        val f = feats.minByOrNull { ft ->
            val pt = ft.geometry() as? Point ?: return@minByOrNull Double.MAX_VALUE
            val sp: PointF = map.projection.toScreenLocation(LatLng(pt.latitude(), pt.longitude()))
            ((sp.x - x) * (sp.x - x) + (sp.y - y) * (sp.y - y)).toDouble()
        } ?: return null
        val pt = f.geometry() as? Point ?: return null
        if (f.hasProperty("kind")) {
            val kind = f.getStringProperty("kind")
            val max = if (f.hasProperty("maxspeed")) f.getNumberProperty("maxspeed").toInt() else 0
            val label = when (kind) {
                "average" -> "Radar de tramo"
                "redlight" -> "Cámara de semáforo"
                else -> "Radar"
            } + if (max > 0) " · $max km/h" else ""
            return PoiHit(label, null, pt.latitude(), pt.longitude(), radar = true)
        }
        val cat = PoiCategory.entries.firstOrNull { it.key == f.getStringProperty("cat") }
        val name = f.getStringProperty("name")?.takeIf { it.isNotBlank() } ?: cat?.label ?: "Lugar"
        return PoiHit(name, cat, pt.latitude(), pt.longitude(), radar = false)
    }

    override fun onDriveState(s: Drive.State) {
        val st = style ?: return
        if (!s.hasFix) return
        val me = Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat))
        me.addNumberProperty("bearing", s.bearing ?: 0f)
        st.getSourceAs<GeoJsonSource>("me")?.setGeoJson(me)
        if (s.trail.size >= 2) {
            st.getSourceAs<GeoJsonSource>("trail")?.setGeoJson(
                LineString.fromLngLats(s.trail.map { Point.fromLngLat(it[0], it[1]) }))
        }
        if (follow) moveCamera(s, 900)
    }

    private var shownRoute: Route? = null
    private var shownIndex = -1

    override fun onNavState(n: Navigator.State) {
        val st = style ?: return
        val route = n.route
        val routeSrc = st.getSourceAs<GeoJsonSource>("route") ?: return
        val destSrc = st.getSourceAs<GeoJsonSource>("dest") ?: return
        if (route == null) {
            routeSrc.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            destSrc.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            shownRoute = null
            return
        }
        if (route !== shownRoute) {
            destSrc.setGeoJson(Point.fromLngLat(route.destLon, route.destLat))
            shownIndex = -1
            follow = true
        }
        // Dibujar solo lo que falta recorrer
        if (route !== shownRoute || n.index != shownIndex) {
            val pts = ArrayList<Point>(route.size - n.index + 1)
            val d = Drive.state
            if (d.hasFix && n.index > 0) pts.add(Point.fromLngLat(d.lon, d.lat))
            for (i in n.index until route.size) pts.add(Point.fromLngLat(route.lon[i], route.lat[i]))
            if (pts.size >= 2) routeSrc.setGeoJson(LineString.fromLngLats(pts))
            shownIndex = n.index
        }
        shownRoute = route
    }

    fun recenter() {
        follow = true
        val s = Drive.state
        if (s.hasFix) moveCamera(s, 600)
    }

    fun zoomBy(delta: Double) {
        map.animateCamera(CameraUpdateFactory.zoomBy(delta), 300)
    }

    private fun moveCamera(s: Drive.State, ms: Int) {
        val pos = if (is3d) {
            // Detrás de la flecha, bien inclinado, para ver los edificios
            CameraPosition.Builder()
                .target(LatLng(s.lat, s.lon))
                .zoom(when { s.speedKmh > 90 -> 16.0; s.speedKmh > 50 -> 16.8; else -> 17.4 })
                .bearing((s.bearing ?: map.cameraPosition.bearing.toFloat()).toDouble())
                .tilt(60.0)
                .padding(camPadding)
                .build()
        } else {
            // Más lejos a mayor velocidad, como Waze
            CameraPosition.Builder()
                .target(LatLng(s.lat, s.lon))
                .zoom(when { s.speedKmh > 90 -> 15.0; s.speedKmh > 50 -> 15.8; else -> 16.6 })
                .bearing((s.bearing ?: map.cameraPosition.bearing.toFloat()).toDouble())
                .tilt(if (s.speedKmh > 5) 45.0 else max(0.0, minOf(45.0, map.cameraPosition.tilt)))
                .padding(camPadding)
                .build()
        }
        map.animateCamera(CameraUpdateFactory.newCameraPosition(pos), ms)
    }

    /** Margen del mapa (zona libre de la pantalla). */
    fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        basePadding = intArrayOf(left, top, right, bottom)
        applyPadding()
    }

    private fun applyPadding() {
        val (l, t, r, b) = basePadding.toList()
        val free = (viewHeight - t - b).coerceAtLeast(0)
        // En 3D la flecha va más abajo para ver más camino adelante
        val frac = if (is3d) max(followOffset, 0.38f) else followOffset
        camPadding = doubleArrayOf(l.toDouble(), (t + free * frac).toDouble(), r.toDouble(), b.toDouble())
        if (follow) recenter()
    }
}
