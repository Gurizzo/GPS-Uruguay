package com.radiohonda.radaruy

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.radiohonda.radaruy.routing.Geo
import com.radiohonda.radaruy.routing.Navigator
import com.radiohonda.radaruy.routing.SearchIndex
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import kotlin.concurrent.thread

/** Pantalla del teléfono: el mismo mapa que en Android Auto, con controles flotantes. */
class MainActivity : Activity() {

    private lateinit var mapView: MapView
    private lateinit var hud: HudView
    private lateinit var searchBar: View
    private lateinit var recenterBtn: ImageView
    private lateinit var stopBtn: ImageView
    private lateinit var viewBtn: ImageView
    private lateinit var soundBtn: ImageView
    private var controller: MapController? = null

    private val hudListener = Drive.Listener { hud.setState(it) }
    private val navListener = Navigator.Listener { n ->
        hud.setNav(n)
        searchBar.visibility = if (n.active) View.GONE else View.VISIBLE
        stopBtn.visibility = if (n.active) View.VISIBLE else View.GONE
        n.error?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        // Pantalla siempre encendida mientras se navega con el teléfono
        if (n.active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun dp(v: Float) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        Drive.init(this)
        Navigator.init(this)
        SearchIndex.init(this)
        NearbyIndex.init(this)

        val root = FrameLayout(this)
        mapView = MapView(this)
        hud = HudView(this, showNavBanner = true)
        root.addView(mapView)
        root.addView(hud)

        // --- Barra de búsqueda arriba ---
        searchBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18f), dp(14f), dp(18f), dp(14f))
            background = Ui.rounded(0xF21B2442.toInt(), dp(28f).toFloat(), Ui.STROKE, dp(1.2f))
            elevation = dp(8f).toFloat()
            addView(ImageView(context).apply { setImageBitmap(Icons.ui(Icons.Ui.SEARCH, dp(26f), Ui.ACCENT)) },
                LinearLayout.LayoutParams(dp(26f), dp(26f)).apply { rightMargin = dp(12f) })
            addView(TextView(context).apply {
                text = "¿A dónde vamos?"; textSize = 17f; setTextColor(Ui.TEXT_DIM)
            })
            setOnClickListener { showSearch() }
        }
        root.addView(searchBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP).apply { setMargins(dp(14f), dp(14f), dp(14f), 0) })

        // --- Botones redondos a la derecha ---
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        stopBtn = Ui.roundButton(this, Icons.Ui.CLOSE) { confirmStop() }.apply {
            background = Ui.rounded(Ui.DANGER, dp(27f).toFloat()); visibility = View.GONE
        }
        recenterBtn = Ui.roundButton(this, Icons.Ui.RECENTER) { controller?.recenter() }.apply { visibility = View.GONE }
        viewBtn = Ui.roundButton(this, if (Prefs.view3d(this)) Icons.Ui.VIEW_2D else Icons.Ui.VIEW_3D) { toggleView() }
        soundBtn = Ui.roundButton(this, if (Prefs.radarSound(this)) Icons.Ui.SOUND_ON else Icons.Ui.SOUND_OFF) { toggleSound() }
        buttons.addView(stopBtn)
        buttons.addView(recenterBtn)
        buttons.addView(Ui.roundButton(this, Icons.Ui.NEARBY) { showNearbyMenu() })
        buttons.addView(Ui.roundButton(this, Icons.Ui.LAYERS) { showCategories() })
        buttons.addView(viewBtn)
        buttons.addView(soundBtn)
        root.addView(buttons, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.BOTTOM
        ).apply { setMargins(0, 0, dp(14f), dp(24f)) })

        val loading = TextView(this).apply {
            text = "Preparando el mapa de Uruguay…"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setBackgroundColor(Ui.BG)
        }
        root.addView(loading)

        root.fitsSystemWindows = true
        setContentView(root)
        mapView.onCreate(savedInstanceState)

        mapView.getMapAsync { map ->
            controller = MapController(this, map).also { c ->
                c.onFollowChanged = { following -> recenterBtn.visibility = if (following) View.GONE else View.VISIBLE }
                c.onReady = { loading.visibility = View.GONE }
                c.onPoiClick = { hit -> showPlace(hit.name, hit.category, hit.lat, hit.lon, hit.radar) }
                c.viewHeight = mapView.height
                c.start()
            }
            map.addOnMapLongClickListener { p ->
                showPlace("Punto del mapa", null, p.latitude, p.longitude, false)
                true
            }
        }
        mapView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (controller?.viewHeight != v.height) controller?.viewHeight = v.height
        }

        requestNeededPermissions()

        // Solo para pruebas desde la PC (adb): abrir un menú directamente
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            when (intent?.getStringExtra("abrir")) {
                "negocios" -> root.post { showCategories() }
                "cerca" -> root.post { showNearbyMenu() }
                "buscar" -> root.post { showSearch() }
            }
        }
    }

    // ------------------------------------------------------------ acciones ---
    private fun toggleView() {
        val to3d = !Prefs.view3d(this)
        Prefs.setView3d(this, to3d)
        viewBtn.setImageBitmap(Icons.ui(if (to3d) Icons.Ui.VIEW_2D else Icons.Ui.VIEW_3D, dp(30f)))
        Toast.makeText(this, if (to3d) "Vista ciudad 3D" else "Vista noche", Toast.LENGTH_SHORT).show()
    }

    private fun toggleSound() {
        val on = !Prefs.radarSound(this)
        Prefs.setRadarSound(this, on)
        soundBtn.setImageBitmap(Icons.ui(if (on) Icons.Ui.SOUND_ON else Icons.Ui.SOUND_OFF, dp(30f)))
        Toast.makeText(this, if (on) "Aviso de radares con sonido" else "Aviso de radares sin sonido", Toast.LENGTH_SHORT).show()
    }

    private fun confirmStop() {
        val sheet = Ui.Sheet(this, "¿Terminar el viaje?")
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        line.addView(Ui.pill(this, "Seguir", primary = false) { sheet.dismiss() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(10f) })
        line.addView(Ui.pill(this, "Terminar", color = Ui.DANGER) { sheet.dismiss(); Navigator.stop() }.apply {
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sheet.content.addView(line)
        sheet.show(null)
    }

    private fun distanceText(lat: Double, lon: Double): String? {
        val d = Drive.state
        if (!d.hasFix) return null
        return Navigator.formatDistance(Geo.distance(d.lat, d.lon, lat, lon))
    }

    /** Tarjeta de un lugar (tocado en el mapa) con el botón "Ir". */
    private fun showPlace(name: String, cat: PoiCategory?, lat: Double, lon: Double, radar: Boolean) {
        val sheet = Ui.Sheet(this, if (radar) "Radar" else "Destino")
        val icon = when {
            radar -> Icons.radar("speed", dp(40f))
            cat != null -> Icons.poi(cat, dp(40f))
            else -> Icons.destination(dp(40f))
        }
        val sub = listOfNotNull(cat?.label, distanceText(lat, lon)).joinToString(" · ")
        sheet.content.addView(Ui.row(this, icon, name, sub))
        sheet.content.addView(Ui.pill(this, "Ir") {
            sheet.dismiss()
            startTrip(SearchIndex.Place(name, cat?.label ?: "", lat, lon))
        }.apply { textSize = 17f; setPadding(0, dp(14f), 0, dp(14f)) })
        sheet.show(null)
    }

    private fun startTrip(p: SearchIndex.Place) {
        if (!Drive.state.hasFix) Toast.makeText(this, "Esperando señal GPS…", Toast.LENGTH_SHORT).show()
        if (p.subtitle.isNotEmpty()) Prefs.addRecent(this, p)
        Navigator.navigateTo(p.lat, p.lon, p.name)
        controller?.recenter()
    }

    // ------------------------------------------------------------- búsqueda ---
    private fun showSearch() {
        val sheet = Ui.Sheet(this, "¿A dónde vamos?")
        val input = Ui.searchField(this, "Ciudad, barrio, calle o negocio")
        sheet.root.addView(input, 2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12f) })

        fun render(q: String, results: List<SearchIndex.Place>) {
            sheet.content.removeAllViews()
            if (q.isBlank() && results.isNotEmpty()) sheet.content.addView(Ui.header(this, "RECIENTES"))
            if (q.isNotBlank() && results.isEmpty()) sheet.content.addView(Ui.header(this, "SIN RESULTADOS"))
            for (p in results) {
                val sub = listOfNotNull(p.subtitle.ifEmpty { null }, distanceText(p.lat, p.lon)).joinToString(" · ")
                val go = { sheet.dismiss(); startTrip(p) }
                sheet.content.addView(Ui.row(this, Icons.destination(dp(36f)), p.name, sub, "Ir", onAction = go, onClick = go))
            }
        }
        render("", Prefs.recents(this))
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                val q = e.toString()
                val d = Drive.state
                thread {
                    val r = if (q.isBlank()) Prefs.recents(this@MainActivity) else SearchIndex.search(q, d.lat, d.lon)
                    runOnUiThread { if (input.text.toString() == q) render(q, r) }
                }
            }
        })
        sheet.show(0.85f)
        sheet.dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        input.requestFocus()
        input.postDelayed({ getSystemService(InputMethodManager::class.java).showSoftInput(input, 0) }, 250)
    }

    // ----------------------------------------------------------- cerca mío ---
    /** Menú de actividades: se elige un tipo y se listan los más cercanos primero. */
    private fun showNearbyMenu() {
        val sheet = Ui.Sheet(this, "¿Qué buscás cerca?")
        val chips = PoiCategory.nearbyOrder.map { cat ->
            Ui.chip(this, Icons.poi(cat, dp(36f)), cat.label) { sheet.dismiss(); showNearby(cat) }
        }
        sheet.content.addView(Ui.grid(this, chips))
        sheet.show(0.75f)
    }

    private fun showNearby(cat: PoiCategory) {
        // La lista se calcula cada vez que se abre, con la posición de este momento
        val d = Drive.state
        val sheet = Ui.Sheet(this, cat.label, onBack = { showNearbyMenu() })
        if (!d.hasFix) sheet.content.addView(Ui.header(this, "SIN GPS: DISTANCIAS DESDE MONTEVIDEO"))
        sheet.show(0.8f)
        thread {
            val places = NearbyIndex.nearest(cat, d.lat, d.lon)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (places.isEmpty()) sheet.content.addView(Ui.header(this, "NO HAY LUGARES DE ESTE TIPO CARGADOS"))
                val icon = Icons.poi(cat, dp(40f))
                for (p in places) {
                    val go = { sheet.dismiss(); startTrip(SearchIndex.Place(p.name, cat.label, p.lat, p.lon)) }
                    sheet.content.addView(Ui.row(this, icon, p.name, NearbyIndex.describe(p, d.lat, d.lon), "Ir",
                        onAction = go, onClick = go))
                }
            }
        }
    }

    // ------------------------------------------------------------ negocios ---
    private fun showCategories() {
        val sheet = Ui.Sheet(this, "Negocios en el mapa")
        val selected = Prefs.categories(this)
        for (cat in PoiCategory.entries) {
            sheet.content.addView(Ui.toggleRow(this, Icons.poi(cat, dp(40f)), cat.label, cat.key in selected) { on ->
                Prefs.setCategory(this, cat.key, on)
            })
        }
        sheet.show(0.75f)
    }

    // ------------------------------------------------------------- permisos ---
    private fun requestNeededPermissions() {
        val perms = mutableListOf<String>()
        if (!Drive.hasPermission(this)) {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
            perms += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) perms += Manifest.permission.POST_NOTIFICATIONS
        if (perms.isNotEmpty()) requestPermissions(perms.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (Drive.hasPermission(this)) Drive.onPermissionGranted()
    }

    // -------------------------------------------------------- ciclo de vida ---
    override fun onStart() {
        super.onStart()
        mapView.onStart()
        Drive.acquire(this)
        Drive.addListener(hudListener)
        Navigator.addListener(navListener)
    }

    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }

    override fun onStop() {
        Drive.removeListener(hudListener)
        Navigator.removeListener(navListener)
        Drive.release(this)
        mapView.onStop()
        super.onStop()
    }

    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        controller?.destroy()
        mapView.onDestroy()
        super.onDestroy()
    }
}
