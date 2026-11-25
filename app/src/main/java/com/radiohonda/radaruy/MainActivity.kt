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
    private lateinit var calcCard: View
    private val showCalc = Runnable { calcCard.visibility = View.VISIBLE }

    private val hudListener = Drive.Listener { hud.setState(it) }
    private val navListener = Navigator.Listener { n ->
        hud.setNav(n)
        searchBar.visibility = if (n.active) View.GONE else View.VISIBLE
        stopBtn.visibility = if (n.active) View.VISIBLE else View.GONE
        n.error?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        // Spinner solo si el cálculo tarda (la primera vez carga la red vial)
        calcCard.removeCallbacks(showCalc)
        if (n.calculating && n.route == null) calcCard.postDelayed(showCalc, 250)
        else calcCard.visibility = View.GONE
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

        // Cartel "Calculando ruta…" con el spinner (aparece si tarda más de un instante)
        calcCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24f), dp(20f), dp(24f), dp(18f))
            background = Ui.rounded(0xF2131A2E.toInt(), dp(28f).toFloat(), Ui.STROKE, dp(1.2f))
            elevation = dp(20f).toFloat()
            addView(SpinnerView(context), LinearLayout.LayoutParams(dp(120f), dp(120f)))
            addView(TextView(context).apply {
                text = "Calculando ruta…"; textSize = 16f; setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, dp(12f), 0, 0)
            })
            visibility = View.GONE
        }
        root.addView(calcCard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // Pantalla de carga con el spinner de UYPS
        val loading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF0C031C.toInt())
            isClickable = true
            elevation = dp(30f).toFloat() // por encima de la barra de búsqueda y los botones
            addView(SpinnerView(context), LinearLayout.LayoutParams(dp(190f), dp(190f)))
            addView(TextView(context).apply {
                text = "Uruguay Positioning System"
                gravity = Gravity.CENTER
                textSize = 13f; letterSpacing = 0.12f; setTextColor(Ui.ACCENT)
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT_BOLD, android.graphics.Typeface.BOLD_ITALIC)
                setPadding(0, dp(22f), 0, dp(6f))
            })
            addView(TextView(context).apply {
                text = "Preparando el mapa de Uruguay…"; textSize = 15f; setTextColor(Ui.TEXT_DIM)
                gravity = Gravity.CENTER
            })
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

        handleDebugIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDebugIntent(intent)
    }

    /**
     * Solo en la versión debug, para pruebas y para grabar la demo desde la PC (adb):
     *   --ez demo true          recorrido simulado (ignora el GPS real)
     *   --es vista 2d|3d        cambia la vista del mapa
     *   --es abrir negocios|cerca|buscar|casa|nada   abre un menú (o cierra los abiertos)
     */
    private fun handleDebugIntent(intent: android.content.Intent?) {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0 || intent == null) return
        if (intent.getBooleanExtra("demo", false)) DemoDriver.start(this)
        when (intent.getStringExtra("vista")) {
            "3d" -> if (!Prefs.view3d(this)) toggleView()
            "2d" -> if (Prefs.view3d(this)) toggleView()
        }
        val menu = intent.getStringExtra("abrir") ?: return
        Ui.closeAllSheets()
        val root = window.decorView
        when (menu) {
            "negocios" -> root.post { showCategories() }
            "cerca" -> root.post { showNearbyMenu() }
            "buscar" -> root.post { showSearch() }
            "casa" -> root.post { showFavoriteSetup(Prefs.Favorite.HOME) }
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
        if (!radar) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10f), 0, 0) }
            for ((i, fav) in Prefs.Favorite.entries.withIndex()) {
                line.addView(Ui.pill(this, "Guardar como ${fav.label.lowercase()}", primary = false) {
                    sheet.dismiss()
                    saveFavorite(fav, SearchIndex.Place(name, cat?.label ?: "", lat, lon))
                }.apply { textSize = 14f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (i == 0) rightMargin = dp(10f) })
            }
            sheet.content.addView(line)
        }
        sheet.show(null)
    }

    // ------------------------------------------------------- casa y trabajo ---
    private fun saveFavorite(fav: Prefs.Favorite, p: SearchIndex.Place) {
        Prefs.setFavorite(this, fav, p)
        Toast.makeText(this, "${fav.label} guardada: ${p.name}", Toast.LENGTH_SHORT).show()
    }

    private fun favIcon(fav: Prefs.Favorite) =
        Icons.ui(if (fav == Prefs.Favorite.HOME) Icons.Ui.HOME else Icons.Ui.WORK, dp(40f), Ui.ACCENT)

    /** Configurar Casa o Trabajo: ubicación actual, buscar, o borrar. */
    private fun showFavoriteSetup(fav: Prefs.Favorite) {
        val current = Prefs.favorite(this, fav)
        val sheet = Ui.Sheet(this, if (current == null) "Marcar ${fav.label.lowercase()}" else fav.label)
        if (current != null) sheet.content.addView(Ui.row(this, favIcon(fav), current.name, "Guardada"))
        sheet.content.addView(Ui.row(this, Icons.ui(Icons.Ui.RECENTER, dp(40f), Ui.ACCENT), "Usar mi ubicación actual",
            if (Drive.state.hasFix) "Donde estás ahora" else "Esperando señal GPS…", onClick = {
                val d = Drive.state
                if (!d.hasFix) {
                    Toast.makeText(this, "Todavía no hay señal GPS", Toast.LENGTH_SHORT).show()
                } else {
                    sheet.dismiss()
                    saveFavorite(fav, SearchIndex.Place("Mi ${fav.label.lowercase()}", fav.label, d.lat, d.lon))
                }
            }))
        sheet.content.addView(Ui.row(this, Icons.ui(Icons.Ui.SEARCH, dp(40f), Ui.ACCENT), "Buscar dirección o lugar",
            "Calle, barrio o negocio", onClick = { sheet.dismiss(); showSearch(pickFor = fav) }))
        sheet.content.addView(Ui.row(this, Icons.destination(dp(40f)), "Elegir en el mapa",
            "Mantené apretado un punto o tocá un negocio y elegí \"Guardar como ${fav.label.lowercase()}\"",
            onClick = { sheet.dismiss() }))
        if (current != null) {
            sheet.content.addView(Ui.pill(this, "Borrar ${fav.label.lowercase()}", primary = false) {
                sheet.dismiss(); Prefs.setFavorite(this, fav, null)
            })
        }
        sheet.show(null)
    }

    private fun startTrip(p: SearchIndex.Place) {
        if (!Drive.state.hasFix) Toast.makeText(this, "Esperando señal GPS…", Toast.LENGTH_SHORT).show()
        if (p.subtitle.isNotEmpty()) Prefs.addRecent(this, p)
        Navigator.navigateTo(p.lat, p.lon, p.name)
        controller?.recenter()
    }

    // ------------------------------------------------------------- búsqueda ---
    /** Buscador. Con [pickFor] el resultado elegido se guarda como Casa/Trabajo en vez de navegar. */
    private fun showSearch(pickFor: Prefs.Favorite? = null) {
        val sheet = Ui.Sheet(this, if (pickFor == null) "¿A dónde vamos?" else "Buscar ${pickFor.label.lowercase()}")
        val input = Ui.searchField(this, "Ciudad, barrio, calle o negocio")
        sheet.root.addView(input, 2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12f) })

        val choose: (SearchIndex.Place) -> Unit = { p ->
            sheet.dismiss()
            if (pickFor != null) saveFavorite(pickFor, p) else startTrip(p)
        }

        fun render(q: String, results: List<SearchIndex.Place>) {
            sheet.content.removeAllViews()
            if (q.isBlank() && pickFor == null) {
                // Accesos rápidos a Casa y Trabajo
                val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for ((i, fav) in Prefs.Favorite.entries.withIndex()) {
                    val place = Prefs.favorite(this, fav)
                    val chip = Ui.chip(this, favIcon(fav), fav.label + "\n" + (place?.let { distanceText(it.lat, it.lon) ?: it.name } ?: "Configurar")) {
                        sheet.dismiss()
                        if (place != null) startTrip(place) else showFavoriteSetup(fav)
                    }
                    chip.setOnLongClickListener { sheet.dismiss(); showFavoriteSetup(fav); true }
                    line.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { if (i == 0) rightMargin = dp(8f); bottomMargin = dp(10f) })
                }
                sheet.content.addView(line)
                if (Prefs.Favorite.entries.any { Prefs.favorite(this, it) != null }) {
                    sheet.content.addView(Ui.header(this, "MANTENÉ APRETADO CASA O TRABAJO PARA CAMBIARLOS"))
                }
            }
            if (q.isBlank() && results.isNotEmpty()) sheet.content.addView(Ui.header(this, "RECIENTES"))
            if (q.isNotBlank() && results.isEmpty()) sheet.content.addView(Ui.header(this, "SIN RESULTADOS"))
            for (p in results) {
                val sub = listOfNotNull(p.subtitle.ifEmpty { null }, distanceText(p.lat, p.lon)).joinToString(" · ")
                val go = { choose(p) }
                sheet.content.addView(Ui.row(this, Icons.destination(dp(36f)), p.name, sub,
                    if (pickFor == null) "Ir" else "Elegir", onAction = go, onClick = go))
            }
        }
        render("", if (pickFor == null) Prefs.recents(this) else emptyList())
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                val q = e.toString()
                val d = Drive.state
                thread {
                    val r = if (q.isBlank()) (if (pickFor == null) Prefs.recents(this@MainActivity) else emptyList())
                        else SearchIndex.search(q, d.lat, d.lon)
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
