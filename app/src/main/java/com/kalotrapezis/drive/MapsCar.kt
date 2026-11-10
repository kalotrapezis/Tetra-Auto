package com.kalotrapezis.drive

import android.Manifest
import android.app.Presentation
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Environment
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.AppManager
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.model.CarColor
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.validation.HostValidator
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.File
import java.util.Locale
import java.util.TimeZone
import androidx.car.app.navigation.model.Step as CarStep

/**
 * Tetra Maps on the car's screen (the user, 22 Oct: "it does not work with Android Auto its not on the list of the available
 * Apps can we add it?"): the offline map with where you are, your places and what is near (fuel, ΑμεΑ parking…), and
 * directions shown and spoken — the phone's own routing (MapsRouting) and guidance (progress, dueWarning), by car.
 * Android Auto lists a navigation app not installed from Play only with its Developer settings › Unknown sources on.
 */
class MapsCarService : CarAppService() {
    // the log too when only the car screen runs (no activity has started it)
    override fun onCreate() { super.onCreate(); WriterLog.init(applicationContext) }

    // the hosts Google signs (Android Auto, Android Automotive), the library's own list
    override fun createHostValidator() = HostValidator.Builder(applicationContext).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()
    override fun onCreateSession() = object : Session() {
        // the map, with the places over it as cards until Map is pressed or directions start
        override fun onCreateScreen(intent: Intent): Screen = MapsCarScreen(carContext)
    }
}

/**
 * The phone's map and the car's screen, one trip (the user, 22 Oct: "In Gogole maps when in Android auto I cant open the map
 * on the phone, is it the same with tetra … we need a good UI and UX on bouth"): while the car is connected a place picked on
 * the phone starts its directions on the car's screen, and the phone shows them (no second route or voice). One process, so
 * a shared object is enough.
 */
internal object CarLink {
    val connected = kotlinx.coroutines.flow.MutableStateFlow(false)
    val nav = kotlinx.coroutines.flow.MutableStateFlow<Nav?>(null)
    var go: ((Place) -> Unit)? = null
    var stop: (() -> Unit)? = null
    var goTrip: ((List<Place>) -> Unit)? = null
}

/** The map, the next turn over it while directions run, and Places / Nearby / Stop. */
private class MapsCarScreen(car: CarContext) : Screen(car), DefaultLifecycleObserver {
    private val root = MapsFiles.root(TetraFolder.root(Environment.getExternalStorageDirectory()))
    /** The roads of the map a spot is on (Tetra/.System/Maps/Shared can hold several countries'). */
    private fun graphAt(lat: Double, lng: Double) = MapsFiles.graphAt(root, lat, lng)
    private val words = MapsPrefs.words(car)
    private val navigation = car.getCarService(NavigationManager::class.java)
    private var here: Location? = null
    private var nav: Nav? = null
    private var finding: String? = null
    private var map: MapLibreMap? = null

    init {
        lifecycle.addObserver(this)
        navigation.setNavigationManagerCallback(object : NavigationManagerCallback { override fun onStopNavigation() = stop() })
        // the car can be gone already (unplugged as it starts): nothing to draw on then, and no crash
        runCatching { car.getCarService(AppManager::class.java).setSurfaceCallback(MapSurface()) }.onFailure { WriterLog.e("Maps car: no surface", it) }
    }

    /** The car's words, in the app's language (the car's own context is one). */
    private fun s(id: Int, vararg args: Any): String = carContext.getString(id, *args)

    private fun granted() = ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onGetTemplate(): Template {
        val n = nav; val p = n?.p
        val actions = ActionStrip.Builder()
        val pinned = pin
        when {
            n != null -> actions.addAction(action(s(R.string.common_stop)) { stop() })
            // a spot tapped on the map: there, or not
            pinned != null -> {
                actions.addAction(action(s(R.string.maps_go)) { goToPin(pinned) })
                actions.addAction(icon(R.drawable.ic_close) { setPin(null) })
            }
            else -> {
                actions.addAction(action(s(if (showPlaces) R.string.car_map else R.string.car_places)) { showPlaces = !showPlaces; showPanel(); invalidate() })
                actions.addAction(action(s(R.string.common_search)) { screenManager.push(SearchScreen()) })
            }
        }
        actions.addAction(icon(R.drawable.ic_settings) { screenManager.push(SettingsScreen()) })
        // on the map: drag it (pan), zoom in and out, and back to me once moved (the user, 22 Oct: "it does not zoom?")
        val mapActions = ActionStrip.Builder().addAction(Action.PAN)
            .addAction(icon(R.drawable.ic_add) { zoom(1.0) }).addAction(icon(R.drawable.ic_remove) { zoom(-1.0) })
        // back to the car: while directions run always there (the user, 23 Oct), else once the map was moved
        if (!tracking || n != null) mapActions.addAction(icon(R.drawable.ic_my_location) { back?.cancel(); follow("my location"); invalidate() })
        val t = NavigationTemplate.Builder().setActionStrip(actions.build()).setMapActionStrip(mapActions.build())
            .setPanModeListener { }
        val message = when {
            !granted() -> s(R.string.car_allow_location)
            !MapsFiles.map(root).isFile -> s(R.string.car_no_map)
            else -> finding
        }
        if (message != null) t.setNavigationInfo(MessageInfo.Builder(message).build())
        // while directions run the turn and arrival cards are Tetra's own on both sides, one style and size with the rest
        // (the user, 22 Oct: "the other side?"); Android Auto's smaller ones are left out
        return t.build()
    }

    private fun secondsLeft(n: Nav, p: Progress) = n.route.millis / 1000 * (p.left / n.route.metres.coerceAtLeast(1.0))

    private fun action(title: String, click: () -> Unit) = Action.Builder().setTitle(title).setOnClickListener(click).build()
    private fun icon(res: Int, click: () -> Unit) =
        Action.Builder().setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()).setOnClickListener(click).build()

    /** The camera following me; false once the map was moved by hand, until "my location". */
    private var tracking = true
    private var unsureAt = 0L // when a poor GPS accuracy was last written down
    private var newRouteSaidAt = 0L
    /** What the camera does, for the drive's trace: following (zoom, tilt) or let go, and close over a crossing. */
    private fun camera(): String {
        val m = map ?: return "none"
        return (if (tracking) "follow" else "free") + "/z%.1f/t%.0f".format(Locale.ROOT, m.cameraPosition.zoom, m.cameraPosition.tilt) 
    }
    private var view = DRIVING_VIEW // the camera's zoom and tilt while directions run (carView)

    /** By hand: the camera stops following me. */
    private fun loose() {
        // while directions run, back to the car's arrow 15 s after the last touch (as Google Maps does)
        back?.cancel()
        back = lifecycleScope.launch { kotlinx.coroutines.delay(15_000); if (nav != null && !tracking) { follow("15 s after the last touch"); invalidate() } }
        if (!tracking) return
        WriterLog.i("Maps car: camera let go of the car (a finger on the map)")
        tracking = false
        map?.locationComponent?.takeIf { it.isLocationComponentActivated }?.cameraMode = CameraMode.NONE
        invalidate()
    }
    private var back: kotlinx.coroutines.Job? = null

    private fun zoom(by: Double) {
        val m = map ?: return
        val lc = m.locationComponent.takeIf { it.isLocationComponentActivated }
        if (tracking && lc != null) lc.zoomWhileTracking(m.cameraPosition.zoom + by)
        else m.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.zoomBy(by))
    }

    // —— the map, drawn on the car's surface: a MapLibre MapView on a virtual display of that surface ——

    private inner class MapSurface : SurfaceCallback {
        private var display: VirtualDisplay? = null
        private var presentation: Presentation? = null
        private var view: MapView? = null
        private var size = 0 to 0

        override fun onSurfaceAvailable(c: SurfaceContainer) {
            val surface = c.surface ?: return
            if (!MapsFiles.map(root).isFile) return
            onSurfaceDestroyed(c) // a new size comes as a new surface: the old display goes first
            val d = carContext.getSystemService(DisplayManager::class.java)
                .createVirtualDisplay("Tetra Maps", c.width, c.height, c.dpi, surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
            display = d; size = c.width to c.height; surfaceW = c.width; surfaceH = c.height
            WriterLog.i("Maps car: surface ${c.width}×${c.height} at ${c.dpi} dpi")
            presentation = Presentation(carContext, d.display).apply {
                MapLibre.getInstance(context)
                val v = MapView(context).also { view = it }
                v.onCreate(null)
                val frame = android.widget.FrameLayout(context)
                frame.addView(v)
                u = c.height / 100f
                frame.addView(turnPanel(context))
                frame.addView(junctionCard(context))
                frame.addView(etaCard(context))
                frame.addView(favesGrid(context))
                frame.addView(speedSign(context), android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START))
                setContentView(frame)
                show()
                v.onStart(); v.onResume()
                v.getMapAsync { m -> m.setStyle(Style.Builder().fromJson(mapStyle(carContext, root))) { style -> ready(m, style) } }
            }
        }

        override fun onVisibleAreaChanged(r: Rect) {
            // the arrow in the middle of what the cards leave free
            val (w, h) = size
            // the small card: the part of the surface shown lies on one side of its middle (the surface keeps the screen's size:
            // 1816 wide on 22 Oct, the big side 24–1168, the small card 1216–1804); the big side crosses the middle, even with
            // the turn card over its left part
            val was = compact
            compact = r.left > w / 2 || r.right < w / 2; shown = Rect(r)
            if (was != compact) invalidate() // the turn card moves between Android Auto's and Tetra's
            WriterLog.i("Maps car: visible $r of ${w}×$h")
            sizePanel(r, w)
            showSpeed()
            // the column at the driver's side (the user, 22 Oct: "bring every sign close to the driver"; "the buttons are always
            // to the drivers side … depending on the county"), in the middle of the height the cards leave
            sign?.parent?.let { col -> (col as android.view.View).apply {
                val edge = (1.5f * u).toInt()
                // the big side: at the screen's edge, past the cards; the small card: inside it (the surface reaches under the other app)
                // lined up with the cards (Android Auto's own no longer push the visible part in)
                val left = r.left + gap(r); val right = w - r.right + gap(r)
                (layoutParams as android.widget.FrameLayout.LayoutParams).setMargins(if (driverRight) 0 else left, r.top, if (driverRight) right else 0, h - r.bottom + if (compact) edge + (textPx * 1.4f + 2 * r.width() / 25).toInt() + edge else 0); requestLayout()
            } }
            placeSpeed()
            padCamera()
        }

        // a tap: on a card, that place; on the map with no directions, a pin there (Go, or ✕)
        override fun onClick(x: Float, y: Float) {
            val hit = Rect()
            if (nav != null) {
                if (!compact) etaTaps.firstOrNull { (v, _) -> v.getGlobalVisibleRect(hit) && hit.contains(x.toInt(), y.toInt()) }?.second?.invoke()
                return
            }
            if (faves?.visibility == android.view.View.VISIBLE) {
                faveCells.firstOrNull { (v, _) -> v.getGlobalVisibleRect(hit) && hit.contains(x.toInt(), y.toInt()) }?.second?.tap?.invoke()
            } else if (!compact) map?.let { setPin(it.projection.fromScreenLocation(android.graphics.PointF(x, y))) }
        }

        // a finger on the car's screen (Android Auto 2+): drag, pinch
        override fun onScroll(dx: Float, dy: Float) { loose(); map?.scrollBy(-dx, -dy, 0) }
        override fun onScale(x: Float, y: Float, factor: Float) {
            val m = map ?: return
            if (x < 0 || y < 0) { zoom(if (factor > 1) 1.0 else -1.0); return } // a double tap or the rotary knob: one step
            loose(); m.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.zoomBy(Math.log(factor.toDouble()) / Math.log(2.0), android.graphics.Point(x.toInt(), y.toInt())))
        }

        override fun onSurfaceDestroyed(c: SurfaceContainer) {
            map = null; sign = null; speedText = null; tollView = null; gpsView = null; panel = null; faves = null; gridKey = ""; etaBox = null; etaTaps = emptyList()
            view?.run { onPause(); onStop(); onDestroy() }; view = null
            presentation?.dismiss(); presentation = null
            display?.release(); display = null
        }
    }

    // —— the speed limit as its sign, red when I am over it by more than 3 km/h, my speed under it (as on the phone) ——

    private var sign: android.widget.TextView? = null
    private var speedText: android.widget.TextView? = null
    private var limit: Int? = null
    private var limitAsked = 0L
    private var tolls: List<Double> = emptyList() // the route's toll stations, metres along it
    private var tollsAhead = 0
    private var tollSaid: Set<Int> = emptySet()
    private var parkingAsked: Place? = null
    private var parkingSpots = 0

    /** The Parking button only when the destination has known ΑμεΑ spots (the user, 22 Oct: "show the button only if there are spot available"). */
    private fun showParking(count: Int) {
        parkingSpots = count
        parkingButton?.apply {
            visibility = if (count > 0) android.view.View.VISIBLE else android.view.View.GONE
            (getChildAt(1) as? android.widget.TextView)?.apply { text = "$count"; visibility = if (count > 0) android.view.View.VISIBLE else android.view.View.GONE }
        }
    }

    /** The ΑμεΑ spots near [p] (Settings › Maps: 500 m to 2 km; the municipalities', OSM's, the user's own), nearest first. */
    private fun parkingNear(p: Place): List<Poi> = runCatching {
        val mine = MapsFiles.places(root).filter { it.icon == "accessible" }.map { Poi("disabled_parking", it.lat, it.lng, it.name, more = s(R.string.maps_saved_mine)) }
        (MapsSearch.load(root).filter { it.kind == "disabled_parking" } + mine)
            .map { it to metresBetween(p.lat, p.lng, it.lat, it.lng) }.filter { it.second < MapsPrefs.parkingRadius(carContext) }.sortedBy { it.second }.map { it.first }
    }.getOrDefault(emptyList())
    private var lastAlong = 0.0; private var backFixes = 0; private var wrongSaidAt = 0L
    private var fixAt = android.os.SystemClock.elapsedRealtime()
    private var signalLost = false
    private var tollView: android.widget.TextView? = null
    private var gpsView: android.widget.TextView? = null

    /*
     * A column on the map with no background (the user, 22 Oct: "an invisible vertical list corrent speed/ spped limit/
     * tolls/ when a root has it the symbol turns on"): my speed, the limit's sign, the tolls left on the route, no GPS.
     */
    /*
     * The small card (the map beside another app, narrower than tall) while directions run: the next turn only, big —
     * arrow, distance, street — and the limit's sign, as Google Maps does there (the user, 22 Oct: "From the small screen
     * you show only nav info"; "only nav info limit and an X" — the ✕ is Android Auto's own, beside the arrival time).
     */
    private var compact = false
    private var panel: android.widget.LinearLayout? = null
    private var panelArrow: android.widget.ImageView? = null
    private var panelDistance: android.widget.TextView? = null
    private var panelText: android.widget.TextView? = null

    // As Google Maps there (the user's picture, 22 Oct): a card at the top — the arrow, the distance, the road's number in
    // its box and the street — and the map with the route under it; Android Auto's own bar below (✕, time, distance).
    private var panelRef: android.widget.TextView? = null
    private var panelHint: android.widget.TextView? = null // the instruction itself, smaller ("Στρίψτε κλειστά αριστερά")
    private var panelJunction: android.widget.ImageView? = null // a busy crossing's picture, near it
    private var junctionShown: Pair<Junction, Int>? = null
    private var panelLanes: android.widget.ImageView? = null // the lanes before the turn, the ones to take white
    private var lanesShown: Pair<Lanes, Int>? = null
    private var cardLogged: Pair<Route, Int>? = null // the step whose crossing or lanes the log has

    private fun turnPanel(c: android.content.Context): android.view.View {
        panelArrow = android.widget.ImageView(c).apply { setColorFilter(Color.WHITE) }
        panelDistance = android.widget.TextView(c).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) }
        panelRef = android.widget.TextView(c).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) }
        panelText = android.widget.TextView(c).apply { setTextColor(0xFFE8EAED.toInt()); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        panelHint = android.widget.TextView(c).apply { setTextColor(0xFFBDC1C6.toInt()); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        panelLanes = android.widget.ImageView(c).apply { visibility = android.view.View.GONE; adjustViewBounds = true }; lanesShown = null
        val top = android.widget.LinearLayout(c).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            addView(panelArrow); addView(panelDistance)
        }
        val road = android.widget.LinearLayout(c).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            addView(panelRef); addView(panelText)
        }
        return android.widget.LinearLayout(c).also { panel = it }.apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xF0303134.toInt()); cornerRadius = 3 * u }
            layoutParams = android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP or android.view.Gravity.START)
            addView(top); addView(road); addView(panelHint); addView(panelLanes)
            visibility = android.view.View.GONE
        }
    }

    /**
     * A busy crossing's picture in a small square of its own in the map's top corner away from the driver (the user, 23 Oct:
     * "a window, a square up in the corner, since it does not fit there"), not in the turn card, where it covered the rest.
     */
    private var junctionBox: android.widget.FrameLayout? = null

    private fun junctionCard(c: android.content.Context): android.view.View {
        panelJunction = android.widget.ImageView(c); junctionShown = null
        return android.widget.FrameLayout(c).also { junctionBox = it }.apply {
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xF0303134.toInt()); cornerRadius = 3 * u }
            addView(panelJunction)
            visibility = android.view.View.GONE
        }
    }

    /** The card as wide as the part [r] shown, its arrow and words as big as that width allows. */
    // the bottom card: time, arrival, distance; on the big side under it ✕ end, other routes, a stop on the way (the user,
    // 22 Oct: "Can we add X to end rout, add stop, rerout options?"), tapped through onClick like the places
    private var etaBox: android.widget.LinearLayout? = null
    private var eta: android.widget.TextView? = null
    private var etaButtons: android.widget.LinearLayout? = null
    private var etaTaps: List<Pair<android.view.View, () -> Unit>> = emptyList()
    private var parkingButton: android.widget.LinearLayout? = null

    private fun etaCard(c: android.content.Context): android.view.View {
        eta = android.widget.TextView(c).apply { gravity = android.view.Gravity.CENTER; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD }
        // the buttons as icons side by side in one row, so the card is low and the map large (the user, 23 Oct: "remove all
        // text from the buttons so we can place them side by side"); End the red ✕; Parking with its count
        fun button(icon: Int, label: String, color: Int, tap: () -> Unit) = android.widget.LinearLayout(c).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = 100 * u }
            contentDescription = label
            addView(android.widget.ImageView(c).apply { setImageResource(icon); setColorFilter(Color.WHITE) })
            addView(android.widget.TextView(c).apply { setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD; maxLines = 1; visibility = android.view.View.GONE })
        }.also { etaTaps = etaTaps + (it to tap) }
        etaTaps = emptyList()
        fun row(vararg b: android.view.View) = android.widget.LinearLayout(c).apply { orientation = android.widget.LinearLayout.HORIZONTAL; b.forEach(::addView) }
        parkingButton = button(R.drawable.ic_accessible, s(R.string.maps_kind_parking), 0xFF1565C0.toInt()) { nav?.let { screenManager.push(ParkingScreen(it.target)) } }
        etaButtons = row(button(R.drawable.ic_close, s(R.string.car_end), 0xFFC62828.toInt()) { stop() },
            button(R.drawable.ic_call_split, s(R.string.car_routes), 0xFF5F6368.toInt()) { nav?.let { screenManager.push(RoutesScreen(it.target)) } },
            button(R.drawable.ic_add_location, s(R.string.car_add_stop_button), 0xFF5F6368.toInt()) { screenManager.push(KindsScreen(stop = true)) },
            parkingButton!!.apply { visibility = if (parkingSpots > 0) android.view.View.VISIBLE else android.view.View.GONE })
        return android.widget.LinearLayout(c).also { etaBox = it }.apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xF0303134.toInt()); cornerRadius = 3 * u }
            addView(eta); addView(etaButtons)
            visibility = android.view.View.GONE
        }
    }

    /** The cards' width: the small card's, or on the big side a column at the driver's side. */
    private fun cardWidth(r: Rect) = if (compact) r.width() - 2 * (r.width() / 25) else maxOf((r.width() * 0.4f).toInt(), (44 * u).toInt()).coerceAtMost(r.width() - 2 * (r.width() / 25))
    private fun cardLeft(r: Rect, w: Int) = if (compact || !driverRight) r.left + gap(r) else r.right - gap(r) - cardWidth(r)

    /**
     * While directions run on the big side, the speed and its limit between the turn card and the bottom card, not over
     * them (the 23 Oct head unit picture: the limit's sign on the bottom card).
     */
    private fun placeSpeed() {
        val r = shown ?: return
        val col = sign?.parent as? android.view.View ?: return
        val lp = col.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        val cards = nav != null && !compact && panel?.visibility == android.view.View.VISIBLE
        val top = if (cards) r.top + gap(r) + (panel?.height ?: 0) else r.top
        val bottom = if (cards) surfaceH - r.bottom + gap(r) + (etaBox?.height ?: 0) else lp.bottomMargin
        if (lp.topMargin != top || lp.bottomMargin != bottom) { lp.topMargin = top; lp.bottomMargin = bottom; col.layoutParams = lp }
    }

    /** The space between the cards and the screen's edges: little on the big side, so the map between is as wide as can be
     *  (the user, 23 Oct: "push everything to the edges to open the map view"). */
    private fun gap(r: Rect) = if (compact) r.width() / 25 else (1.2f * u).toInt()

    /**
     * Where the car's arrow sits: in the part of the map the cards leave, and while directions run low in it, so more of
     * the road ahead shows (as Google Maps does); padding in the map's own pixels from each edge.
     */
    private fun padCamera() {
        val r = shown ?: return; val w = surfaceW; val h = surfaceH
        val ahead = if (!compact && nav != null) r.height() * 0.3 else 0.0
        map?.locationComponent?.takeIf { it.isLocationComponentActivated }
            ?.paddingWhileTracking(doubleArrayOf(r.left + (if (!compact && nav != null && !driverRight) (cardLeft(r, w) - r.left + cardWidth(r)).toDouble() else 0.0),
                r.top + (if (compact) r.width() * 0.45 else 0.0) + ahead,
                (w - r.right) + (if (!compact && nav != null && driverRight) cardWidth(r).toDouble() + gap(r) else 0.0), (h - r.bottom).toDouble()))
    }

    private fun sizePanel(r: Rect, w: Int) {
        val px = android.util.TypedValue.COMPLEX_UNIT_PX
        val pad = (if (compact) r.width() else cardWidth(r)) / 25
        val cw = cardWidth(r); val cl = cardLeft(r, w)
        panel?.apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(cw, -2, android.view.Gravity.TOP or android.view.Gravity.START)
                .apply { setMargins(cl, r.top + gap(r), 0, 0) }
            setPadding(pad, pad, pad, pad)
        }
        panelArrow?.layoutParams = android.widget.LinearLayout.LayoutParams(iconPx.toInt(), iconPx.toInt()).apply { rightMargin = pad }
        panelDistance?.setTextSize(px, bigPx)
        panelRef?.apply {
            setTextSize(px, smallPx); setPadding(pad / 2, 0, pad / 2, 0)
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF1B5E20.toInt()); setStroke(3, Color.WHITE); cornerRadius = pad / 3f }
            layoutParams = android.widget.LinearLayout.LayoutParams(-2, -2).apply { rightMargin = pad / 2 }
        }
        panelText?.setTextSize(px, textPx)
        panelHint?.setTextSize(px, smallPx)
        // the crossing's square: under a third of the height shown, at the screen's edge away from the driver, at the top of
        // the part shown — right under the settings button while Android Auto's buttons show, in the corner once they go
        // (the user, 23 Oct: "below the settings button"; "further to the corner when everything goes away"), clear of the
        // map round the car
        val side = (r.height() * 0.3f).toInt()
        junctionBox?.apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(side, side, android.view.Gravity.TOP or android.view.Gravity.START).apply {
                setMargins(if (driverRight) gap(r) else w - gap(r) - side, r.top + gap(r), 0, 0)
            }
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
        }
        panelJunction?.layoutParams = android.widget.FrameLayout.LayoutParams(side - pad, side - pad)
        panelLanes?.layoutParams = android.widget.LinearLayout.LayoutParams(-2, (iconPx * 0.8f).toInt()).apply { topMargin = pad; gravity = android.view.Gravity.CENTER_HORIZONTAL }
        // the bottom card: the card's width, a little less tall than the turn card
        eta?.setTextSize(px, textPx)
        etaBox?.apply {
            setPadding(pad, pad, pad, pad)
            layoutParams = android.widget.FrameLayout.LayoutParams(cw, -2, android.view.Gravity.BOTTOM or android.view.Gravity.START)
                .apply { setMargins(cl, 0, 0, surfaceH - r.bottom + gap(r)) }
        }
        // the buttons big enough for a finger at a glance, only where the car passes taps on (the big side)
        val b = (iconPx * 0.75f).toInt()
        etaButtons?.visibility = if (compact) android.view.View.GONE else android.view.View.VISIBLE
        etaTaps.forEach { (v, _) ->
            v.layoutParams = android.widget.LinearLayout.LayoutParams(0, b, 1f).apply { setMargins(pad / 2, pad, pad / 2, 0) }
            val g = v as android.widget.LinearLayout
            g.getChildAt(0).layoutParams = android.widget.LinearLayout.LayoutParams((b * 0.55f).toInt(), (b * 0.55f).toInt()).apply { rightMargin = pad / 2 }
            (g.getChildAt(1) as android.widget.TextView).setTextSize(px, smallPx)
        }
    }

    // —— no directions running: the places as cards over the map. The small card: the phone Home's 8, 2 by 4 (the user, 22
    // Oct: "when there is no nav active on the small show the home faves 2x4"; Android Auto opens Tetra on a tap there).
    // The big side: Home's 8, Favourites, Nearby, then the recent ones not saved (the user: "the non active side is usless
    // to me … I want the oposide to have the places an click map to see the map … and click to mark my next location") ——

    private class Card(val title: String, val icon: Int, val text: String, val tap: () -> Unit)
    private var showPlaces = true // the big side's cards, until Map
    private var faves: android.widget.LinearLayout? = null
    private var faveCells: List<Pair<android.view.View, Card>> = emptyList()
    private var gridKey = ""
    private var shown: Rect? = null
    private var surfaceW = 0
    private var surfaceH = 0

    private fun favesGrid(c: android.content.Context) = android.widget.LinearLayout(c).also { faves = it }.apply {
        orientation = android.widget.LinearLayout.VERTICAL
        layoutParams = android.widget.FrameLayout.LayoutParams(-1, -1)
        visibility = android.view.View.GONE
    }

    private fun cards(): List<Card> {
        val all = MapsFiles.places(root)
        val home = MapsFiles.slots(all).take(MapsFiles.PHONE_SLOTS).filterNotNull()
        val mine = home.map { p -> Card(p.short, placeIcon(p.icon), away(p.lat, p.lng)) { pick(p) } }
        if (compact) return mine
        val recent = MapsPrefs.recent(carContext).filter { r -> all.none { metresBetween(it.lat, it.lng, r.lat, r.lng) < 50 } }
        return mine + Card(s(R.string.car_favourites), R.drawable.ic_star, s(R.string.car_more, all.size - home.size)) { screenManager.push(FavouritesScreen()) } +
            Card(s(R.string.maps_nearby), R.drawable.ic_search, s(R.string.car_nearby_hint)) { screenManager.push(KindsScreen()) } +
            (MapsFiles.trips(root).takeIf { it.isNotEmpty() }?.let { ts -> listOf(Card(s(R.string.car_trips), R.drawable.ic_call_split, s(R.string.car_trips_saved, ts.size)) { screenManager.push(TripsScreen()) }) } ?: emptyList()) +
            Card(s(R.string.maps_amea_parking), R.drawable.ic_accessible, s(R.string.car_near_me)) { screenManager.push(FoundScreen(MapsSearch.PILLS.first { it.key == "disabled_parking" })) } +
            recent.map { p -> Card(p.name, R.drawable.ic_history, away(p.lat, p.lng)) { pick(p) } }
    }

    private fun fillFaves() {
        val g = faves ?: return; val r = shown ?: return
        val c = g.context
        g.removeAllViews()
        g.setPadding(r.left, r.top, (surfaceW - r.right).coerceAtLeast(0), 0)
        // the small card 2 by 4 on dark; the big side 4 or 5 across, 3 down, cards over the map dimmed
        // cards no narrower than names need (they broke "Favour-ites" squeezed beside a message, 22 Oct): fewer across instead
        val cols = if (compact) 2 else (r.width() / (26 * u)).toInt().coerceIn(2, 5)
        val rows = if (compact) 4 else 3
        g.setBackgroundColor(if (compact) 0xFF202124.toInt() else 0xD9202124.toInt())
        val gap = if (compact) 0 else (r.height() / 60)
        val cellH = r.height() / rows - 2 * gap; val px = android.util.TypedValue.COMPLEX_UNIT_PX
        val cells = mutableListOf<Pair<android.view.View, Card>>()
        cards().take(cols * rows).chunked(cols).forEach { line ->
            val row = android.widget.LinearLayout(c).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
            line.forEach { k ->
                val cell = android.widget.LinearLayout(c).apply {
                    orientation = android.widget.LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER
                    if (!compact) background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF303134.toInt()); cornerRadius = cellH / 10f }
                    addView(android.widget.ImageView(c).apply { setImageResource(k.icon); setColorFilter(0xFF9FA8DA.toInt()) },
                        android.widget.LinearLayout.LayoutParams(minOf(iconPx, cellH * 0.38f).toInt(), minOf(iconPx, cellH * 0.38f).toInt()))
                    addView(android.widget.TextView(c).apply { text = k.title; gravity = android.view.Gravity.CENTER; setTextColor(Color.WHITE); setTextSize(px, textPx); maxLines = if (compact) 1 else 2
                        ellipsize = android.text.TextUtils.TruncateAt.END; typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(gap, 0, gap, 0) })
                    if (k.text.isNotEmpty()) addView(android.widget.TextView(c).apply { text = k.text; gravity = android.view.Gravity.CENTER; setTextColor(0xFFBDC1C6.toInt()); setTextSize(px, smallPx); maxLines = 1 })
                }
                row.addView(cell, android.widget.LinearLayout.LayoutParams(0, cellH, 1f).apply { setMargins(gap, gap, gap, gap) }); cells += cell to k
            }
            repeat(cols - line.size) { row.addView(android.view.View(c), android.widget.LinearLayout.LayoutParams(0, cellH, 1f).apply { setMargins(gap, gap, gap, gap) }) }
            g.addView(row)
        }
        faveCells = cells
    }

    private fun showPanel() {
        val n = nav; val p = n?.p
        val on = n != null && p != null
        val grid = n == null && pin == null && finding == null && (compact || showPlaces)
        val key = "$compact $shown $grid"
        if (grid && key != gridKey) fillFaves()
        gridKey = if (grid) key else ""
        faves?.visibility = if (grid) android.view.View.VISIBLE else android.view.View.GONE
        panel?.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
        etaBox?.visibility = panel?.visibility ?: android.view.View.GONE
        if (!on) { junctionBox?.visibility = android.view.View.GONE; return }
        panel?.post { placeSpeed() } // once the cards have their height
        val left = secondsLeft(n!!, p!!).toLong()
        val time = java.time.LocalTime.now().plusSeconds(left).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        eta?.text = "${if (left >= 3600) s(R.string.maps_hours_minutes, left / 3600, left % 3600 / 60) else s(R.string.maps_minutes, (left + 59) / 60)} · $time · ${metresText(p.left)}" +
            if (tollsAhead > 0) " · € $tollsAhead" else ""
        val step = n!!.route.steps[p!!.next]
        panelArrow?.apply { setImageResource(if (step.sign == 4) R.drawable.ic_place else R.drawable.ic_navigation); rotation = if (step.sign == 4) 0f else turnDegrees(step.sign) }
        panelDistance?.text = metresText(p.toNext)
        panelRef?.apply { text = step.ref; visibility = if (step.ref.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE }
        panelText?.apply { text = step.street; visibility = if (step.street.isEmpty() && step.ref.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE }
        // the instruction without the street, which is above it
        panelHint?.text = step.text.substringBefore(" στην ").substringBefore(" onto ").substringBefore(" στο ")
        // the lanes before the turn, under the words (in the small card too: one short row)
        val l = step.lanes?.takeIf { showLanes(it, p.toNext) }
        panelLanes?.apply {
            val h = layoutParams?.height ?: 0
            if (l != null && h > 0 && lanesShown != l to h) { setImageBitmap(lanesPicture(l, h)); lanesShown = l to h }
            visibility = if (l != null && h > 0) android.view.View.VISIBLE else android.view.View.GONE
        }
        // a busy crossing near: its picture under the words (not in the small card, no room there)
        val j = step.junction?.takeIf { !compact && showJunction(it, p.toNext) }
        if ((j != null || l != null) && cardLogged != n.route to p.next) {
            cardLogged = n.route to p.next
            WriterLog.i("Maps car: the card shows step ${p.next}${j?.let { " crossing ${it.arms.map { a -> a.toInt() }} taken ${it.arms[it.taken].toInt()}" + if (it.roundabout) " (roundabout)" else "" } ?: ""}" +
                (l?.let { " lanes ${it.turns.joinToString("|") { x -> x.joinToString(";") }} use ${it.use.sorted()}" } ?: ""))
        }
        panelJunction?.apply {
            val px = layoutParams?.width ?: 0
            if (j != null && px > 0 && junctionShown != j to px) { setImageBitmap(junctionPicture(j, px, MapsFiles.driveLeftAt(root, n.route, step.at))); junctionShown = j to px }
            junctionBox?.visibility = if (j != null && px > 0) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    /** The driver sits on the right where traffic keeps left (Cyprus, the UK, Malta, Ireland…): the country of the phone's network. */
    private val driverRight = run {
        val tm = carContext.getSystemService(android.telephony.TelephonyManager::class.java)
        val country = (tm?.networkCountryIso?.ifEmpty { null } ?: tm?.simCountryIso?.ifEmpty { null } ?: java.util.Locale.getDefault().country).lowercase()
        country in setOf("gb", "ie", "cy", "mt", "im", "je", "gg", "jp", "au", "nz", "za", "in", "id", "th", "my", "sg", "hk", "mo", "ke", "tz", "ug",
            "zw", "zm", "bw", "na", "mz", "lk", "bd", "np", "pk", "bt", "jm", "tt", "bb", "bs", "gy", "sr", "mu", "sc", "bn", "tl", "fj", "ws", "to",
            "pg", "ls", "sz", "mw", "vc", "lc", "gd", "dm", "ag", "kn", "ai", "vg", "ky", "bm", "fk", "sh", "ck", "nr", "tv", "ki", "sb")
    }

    /**
     * A hundredth of the car screen's height in pixels, and one set of sizes from it for everything Tetra draws (the user,
     * 22 Oct: "Other things are small others are big cant we be consistant?"): close to Android Auto's own cards and buttons.
     */
    private var u = 5f
    private val bigPx get() = 6.5f * u   // speed, distance to the turn
    private val textPx get() = 3.6f * u    // names, streets
    private val smallPx get() = 2.6f * u // km, km/h, badges
    private val iconPx get() = 9f * u    // card icons, the turn's arrow
    private val signPx get() = 11f * u   // the limit's sign

    private fun speedSign(c: android.content.Context): android.view.View {
        val px = android.util.TypedValue.COMPLEX_UNIT_PX
        fun badge(color: Int) = android.widget.TextView(c).apply {
            gravity = android.view.Gravity.CENTER; setTextSize(px, smallPx); typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            setPadding((1.6f * u).toInt(), (0.6f * u).toInt(), (1.6f * u).toInt(), (0.6f * u).toInt())
            background = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = 10 * u }
        }
        tollView = badge(0xFF1565C0.toInt())
        gpsView = badge(0xFFD32F2F.toInt()).apply { text = s(R.string.car_no_gps) }
        sign = android.widget.TextView(c).apply {
            gravity = android.view.Gravity.CENTER; setTextSize(px, signPx * 0.42f); typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        speedText = android.widget.TextView(c).apply {
            // big enough to read at a glance (the user, 22 Oct: "The km/h make it bigger it bearly shows"); a white halo for the map under it
            gravity = android.view.Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(0xFF202124.toInt())
            setShadowLayer(1.2f * u, 0f, 0f, Color.WHITE)
        }
        showSpeed()
        return android.widget.LinearLayout(c).apply {
            orientation = android.widget.LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL
            fun gap() = android.widget.LinearLayout.LayoutParams(-2, -2).apply { topMargin = u.toInt() }
            addView(speedText); addView(sign, gap().apply { width = signPx.toInt(); height = signPx.toInt() }); addView(tollView, gap()); addView(gpsView, gap())
        }
    }

    private fun showSpeed() {
        val l = limit; val kmh = here?.takeIf { it.hasSpeed() }?.let { Math.round(it.speed * 3.6f) }
        val over = l != null && (kmh ?: 0) > l + 3
        sign?.apply {
            visibility = if (l == null) android.view.View.GONE else android.view.View.VISIBLE
            text = l?.toString().orEmpty(); setTextColor(if (over) Color.WHITE else Color.BLACK)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(if (over) 0xFFD32F2F.toInt() else Color.WHITE); setStroke((signPx * 0.09f).toInt(), 0xFFD32F2F.toInt())
            }
        }
        val small = compact && nav != null
        (sign?.parent as? android.view.View)?.visibility = if (nav == null && pin == null && finding == null && (compact || showPlaces)) android.view.View.GONE else android.view.View.VISIBLE
        speedText?.apply {
            visibility = if (kmh == null || small) android.view.View.GONE else android.view.View.VISIBLE
            text = android.text.SpannableString("$kmh\nkm/h").apply {
                setSpan(android.text.style.AbsoluteSizeSpan(bigPx.toInt()), 0, "$kmh".length, 0)
                setSpan(android.text.style.AbsoluteSizeSpan(smallPx.toInt()), "$kmh".length, length, 0)
            }
        }
        // the toll symbol lit while the route has tolls ahead, with how many
        tollView?.visibility = android.view.View.GONE // the tolls are in the arrival card (the user: "I dont see it fiting on top of the KM/H")
        (sign?.parent as? android.view.View)?.let { col ->
            val lp = col.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return@let
            val side = if (driverRight) android.view.Gravity.END else android.view.Gravity.START
            val g = if (small) android.view.Gravity.BOTTOM or side else android.view.Gravity.CENTER_VERTICAL or side
            if (lp.gravity != g) { lp.gravity = g; col.layoutParams = lp }
        }
        showPanel()
        gpsView?.visibility = if (signalLost) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun ready(m: MapLibreMap, style: Style) {
        m.uiSettings.isCompassEnabled = false; m.uiSettings.isLogoEnabled = false; m.uiSettings.isAttributionEnabled = false
        addRouteLayer(style)
        // the route's tolls, as on the phone: a yellow dot with €
        style.addSource(GeoJsonSource("tolls"))
        style.addLayer(org.maplibre.android.style.layers.CircleLayer("tolls-dot", "tolls").withProperties(
            org.maplibre.android.style.layers.PropertyFactory.circleRadius(12f), org.maplibre.android.style.layers.PropertyFactory.circleColor("#F9A825"),
            org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor("#FFFFFF"), org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth(2f)))
        style.addLayer(org.maplibre.android.style.layers.SymbolLayer("tolls-sign", "tolls").withProperties(
            org.maplibre.android.style.layers.PropertyFactory.textField("€"), org.maplibre.android.style.layers.PropertyFactory.textFont(arrayOf("Noto Sans Medium")),
            org.maplibre.android.style.layers.PropertyFactory.textSize(15f), org.maplibre.android.style.layers.PropertyFactory.textColor("#3E2723"),
            org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap(true)))
        style.addSource(GeoJsonSource("pin"))
        style.addLayer(org.maplibre.android.style.layers.CircleLayer("pin", "pin").withProperties(
            org.maplibre.android.style.layers.PropertyFactory.circleRadius(12f), org.maplibre.android.style.layers.PropertyFactory.circleColor("#D32F2F"),
            org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth(4f), org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor("#FFFFFF")))
        map = m
        m.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder()
            .target(here?.let { org.maplibre.android.geometry.LatLng(it.latitude, it.longitude) } ?: org.maplibre.android.geometry.LatLng(38.4, 23.8))
            .zoom(if (here != null) 15.0 else 5.6).build()
        if (granted()) {
            activateArrow(carContext, m, style, scale = 2.6f) // the car's screen is seen from further than the phone's (the user, 23 Oct: "a bit bigger")
            m.locationComponent.isLocationComponentEnabled = true
            here?.let { m.locationComponent.forceLocationUpdate(it) }
            follow()
        }
        drawRoute()
    }

    /** The camera on me, turned the way I go: closer and tilted while directions run. */
    private fun follow(why: String = "") {
        val lc = map?.locationComponent?.takeIf { it.isLocationComponentActivated } ?: return
        if (why.isNotEmpty()) WriterLog.i("Maps car: camera on the car again ($why)")
        lc.renderMode = RenderMode.GPS
        tracking = true
        // zoom and tilt once the tracking has taken hold (asked during its transition, they are dropped)
        lc.setCameraMode(CameraMode.TRACKING_GPS, object : org.maplibre.android.location.OnLocationCameraTransitionListener {
            override fun onLocationCameraTransitionFinished(cameraMode: Int) {
                val (z, t) = if (nav != null) view else 15.0 to 0.0
                lc.zoomWhileTracking(z); lc.tiltWhileTracking(t)
            }
            override fun onLocationCameraTransitionCanceled(cameraMode: Int) = Unit
        })
    }

    private fun drawRoute() { (map?.style?.getSource("route") as? GeoJsonSource)?.setGeoJson(routeFeatures(nav?.route)) }

    // —— where I am ——

    private val listener = LocationListener { loc ->
        // a network fix only until GPS has one, and never over a fresher GPS one
        val h = here
        if (loc.provider == LocationManager.NETWORK_PROVIDER && h != null && h.provider == LocationManager.GPS_PROVIDER && loc.time - h.time < 10_000) return@LocationListener
        onFix(loc)
    }

    override fun onCreate(owner: LifecycleOwner) {
        CarLink.go = { p -> WriterLog.i("Maps car: from the phone, ${p.name}"); pick(p) }
        CarLink.stop = { stop() }
        CarLink.goTrip = { stops -> startTrip(stops) }
        // a trip stopped at one of its stops (the engine off there): the next leg starts when the car moves on
        MapsPrefs.pendingTrip(carContext)?.let { (tr, i) ->
            trip = tr; tripLetter = i; queue = tr.stops.drop(i + 1); waitNext = tr.stops[i]; stoppedAt = null
            WriterLog.i("Maps car: the trip goes on at ${Trip.letter(i)}")
        }
        CarLink.connected.value = true
        // the graph opened now, so the first route does not wait for it (it took 25 s on 22 Oct)
        lifecycleScope.launch(Dispatchers.IO) {
            val g = graphAt(here?.latitude ?: Double.NaN, here?.longitude ?: Double.NaN)
            if (File(g, "properties").isFile) runCatching { WriterLog.timed("Maps car: graph opened") { MapsRouting.open(g) } }
        }
        if (!granted()) return
        // no fix for 10 s: "No GPS" on the map, and said while directions run
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(2000)
                if (!signalLost && android.os.SystemClock.elapsedRealtime() - fixAt > 10_000) {
                    signalLost = true; showSpeed()
                    if (nav != null) say(words.s(R.string.maps_say_gps_lost), important = true)
                }
            }
        }
        val lm = carContext.getSystemService(LocationManager::class.java) ?: return
        runCatching {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { lm.isProviderEnabled(it) }.forEach { pr ->
                lm.getLastKnownLocation(pr)?.let { if (here == null || it.time > here!!.time) here = it }
                lm.requestLocationUpdates(pr, 1000L, 0f, listener, carContext.mainLooper)
            }
        }.onFailure { WriterLog.e("Maps car: location updates", it) }
    }

    /** The car disconnected: it is parked where the last fix was (Settings › Maps › Save where I park). */
    override fun onDestroy(owner: LifecycleOwner) {
        CarLink.connected.value = false; CarLink.nav.value = null; CarLink.go = null; CarLink.stop = null; CarLink.goTrip = null
        carContext.getSystemService(LocationManager::class.java)?.removeUpdates(listener)
        if (nav != null) hostNav { navigationEnded() }
        here?.takeIf { MapsPrefs.autoPark(carContext) && System.currentTimeMillis() - it.time < 5 * 60_000 }?.let { park(it, "Android Auto disconnected") }
        tts?.shutdown()
    }

    private fun park(loc: Location, why: String) {
        WriterLog.i("Maps car: parked ($why)")
        runCatching { MapsFiles.save(root, Place(MapsFiles.PARKING, s(R.string.maps_my_car), loc.latitude, loc.longitude, "parking", mode = "foot")) }
    }

    private fun onFix(loc: Location) {
        here = loc; fixAt = android.os.SystemClock.elapsedRealtime()
        if (signalLost) { signalLost = false; showSpeed() }
        map?.locationComponent?.takeIf { it.isLocationComponentActivated }?.let { lc ->
            lc.forceLocationUpdate(loc)
            // following, but the camera let go of the car (the user, 23 Oct: at the start it stayed off the car): on it again
            if (tracking && lc.cameraMode != CameraMode.TRACKING_GPS) follow("it had left the car (mode ${lc.cameraMode})")
        }
        // the limit at most every two seconds, off the main thread (the graph is opened the first time)
        if (System.currentTimeMillis() - limitAsked > 2000) {
            limitAsked = System.currentTimeMillis()
            lifecycleScope.launch { limit = withContext(Dispatchers.IO) { runCatching { MapsRouting.limitAt(graphAt(loc.latitude, loc.longitude), loc.latitude, loc.longitude) }.getOrNull() }; showSpeed() }
        }
        showSpeed()
        waitNext?.let { next ->
            if ((loc.hasSpeed() && loc.speed > 4f) || stoppedAt?.let { metresBetween(it.latitude, it.longitude, loc.latitude, loc.longitude) > 150 } == true) {
                waitNext = null; MapsPrefs.setPendingTrip(carContext, null); go(next)
            }
        }
        val n = nav ?: return
        val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
        val g = guide(n, loc.latitude, loc.longitude, speed, System.currentTimeMillis(), MapsPrefs.bends(carContext), finding != null)
        val p = g.p
        MapsTrace.fix(loc, p.takeIf { !g.off }, camera())
        // the GPS unsure: written down, once a minute at most
        if (loc.hasAccuracy() && loc.accuracy > 30 && System.currentTimeMillis() - unsureAt > 60_000) {
            unsureAt = System.currentTimeMillis(); WriterLog.i("Maps car: GPS accuracy ${loc.accuracy.toInt()} m (${loc.provider})")
        }
        if (g.off) {
            // the card still shows the way on (the start of a route is often off it: a house back from the road)
            nav = n.copy(offFixes = n.offFixes + 1, p = n.p ?: p)
            invalidate()
            if (g.reroute) { WriterLog.i("Maps car: off the route by ${p.off.toInt()} m at ${where(loc)}, ${MapsTrace.off(n, loc, graphAt(loc.latitude, loc.longitude))}; a new one"); go(n.target) }
            return
        }
        if (g.arrived) {
            WriterLog.i("Maps car: arrived at ${where(loc)}, ${p.left.toInt()} m left on the route")
            if ("n${n.route.steps.lastIndex}" !in n.said) say(n.route.steps.lastOrNull()?.text ?: "", important = true)
            // a stop reached with more to go (a trip's, or one added on the way): the next leg starts when the car moves on
            if (queue.isNotEmpty()) {
                val next = queue.first(); queue = queue.drop(1)
                val letter = tripLetter?.let { Trip.letter(it + 1) }.orEmpty(); tripLetter = tripLetter?.plus(1)
                stop(keepTrip = true)
                waitNext = next; stoppedAt = loc
                trip?.let { tr -> MapsPrefs.setPendingTrip(carContext, tr, tr.stops.size - 1 - queue.size) }
                say(words.s(R.string.maps_say_next_stop, letter, next.name), important = true)
                CarToast.makeText(carContext, s(R.string.car_next, next.name), CarToast.LENGTH_LONG).show(); return
            }
            stop()
            if (MapsPrefs.autoPark(carContext)) park(loc, "a car trip ended")
            CarToast.makeText(carContext, s(R.string.maps_arrived, n.target.name), CarToast.LENGTH_LONG).show(); return
        }
        // going back along the route for 3 fixes at speed: the wrong way, said once a minute at most
        backFixes = if (p.along < lastAlong - 5 && loc.hasSpeed() && loc.speed > 3f) backFixes + 1 else 0
        lastAlong = p.along
        if (backFixes >= 3 && System.currentTimeMillis() - wrongSaidAt > 60_000) {
            wrongSaidAt = System.currentTimeMillis()
            say(words.s(R.string.maps_say_wrong_way), important = true)
        }
        tollsAhead = tolls.count { it > p.along }
        // about a kilometre before each toll (a minute ahead at motorway speed)
        tolls.withIndex().firstOrNull { (i, at) -> at - p.along in 0.0..1100.0 && i !in tollSaid }?.let { (i, at) ->
            tollSaid = tollSaid + i
            if (at - p.along > 300) say(MapsPrefs.inDistance(words, if (at - p.along > 750) 1000.0 else 500.0, true, words.s(R.string.maps_say_toll)))
        }
        // near the end: the ΑμεΑ spots by the destination said once, when there are any
        if (p.left < 1500 && parkingAsked != n.target) {
            parkingAsked = n.target
            if (parkingSpots > 0 && n.target.icon != "accessible")
                say(words.q(R.plurals.maps_say_parking, parkingSpots, parkingSpots), important = true)
        }
        val step = g.step
        // the camera: further out at speed on a long stretch, closer towards a busy crossing or a turn slowed for
        if (g.view != view) {
            view = g.view
            WriterLog.i("Maps car: camera zoom ${view.first} tilt ${view.second.toInt()} (step ${p.next} ${p.toNext.toInt()} m ahead, ${Math.round(speed * 3.6)} km/h)")
            map?.locationComponent?.takeIf { tracking && it.isLocationComponentActivated }?.let { lc ->
                lc.zoomWhileTracking(view.first, 1500); lc.tiltWhileTracking(view.second, 1500)
            }
        }
        g.words(words, true)?.let { say(it, why = g.why()) }
        g.bend?.let { b -> say(MapsPrefs.bendWords(words, n.bends[b].right), why = "bend ${(n.bends[b].along - p.along).toInt()} m ahead, ${(speed * 3.6).toInt()} km/h") }
        val said = g.said(n.said); val i = g.index
        nav = n.copy(index = i, p = p, offFixes = 0, said = said)
        CarLink.nav.value = nav
        invalidate()
    }

    // —— directions ——

    /** By car to [target], the fastest way, from the last fix. */
    /** The stops still to go after the one being driven to (a trip's, or the trip's end after a stop on the way). */
    private var queue: List<Place> = emptyList()
    private var tripLetter: Int? = null // a trip's: the stop being driven to, A = 0
    private var trip: Trip? = null
    private var waitNext: Place? = null // a stop reached: the next leg, started when the car moves on
    private var stoppedAt: Location? = null

    /** A stop on the way: there first, then on to where the trip was going. */
    private fun pickStop(p: Place) {
        nav?.target?.let { queue = listOf(it) + queue }
        WriterLog.i("Maps car: a stop on the way, then ${queue.firstOrNull()?.name}")
        screenManager.popToRoot(); go(p)
    }

    /** A trip, A → B → C…: to A now, each next one once the car moves on from the last. */
    private fun startTrip(stops: List<Place>, from: Int = 0) {
        if (from !in stops.indices) return
        WriterLog.i("Maps car: a trip of ${stops.size} stops, from ${Trip.letter(from)}")
        stop(); trip = Trip("run", "Trip", stops); queue = stops.drop(from + 1); tripLetter = from
        MapsPrefs.setPendingTrip(carContext, null)
        screenManager.popToRoot(); go(stops[from])
    }

    /** By car to [target]: [chosen] when one of the other routes was picked, else the fastest from the last fix. */
    fun go(target: Place, chosen: Route? = null) {
        val from = here ?: run { CarToast.makeText(carContext, s(R.string.car_waiting_gps), CarToast.LENGTH_SHORT).show(); return }
        val graphDir = graphAt(from.latitude, from.longitude)
        if (!File(graphDir, "properties").isFile) { CarToast.makeText(carContext, s(R.string.car_no_graph), CarToast.LENGTH_LONG).show(); return }
        finding = s(R.string.maps_finding); showSpeed(); invalidate()
        val t0 = System.currentTimeMillis()
        val facing = headingOf(from)
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { chosen ?: MapsRouting.route(graphDir, from.latitude, from.longitude, target.lat, target.lng, "car", words, facing, MapsPrefs.mainRoads(carContext), MapsPrefs.noLeft(carContext)) } }
            finding = null
            r.onSuccess { route ->
                val rerouted = nav?.target == target
                WriterLog.i("Maps car: route to ${target.name}, ${route.metres.toInt()} m, ${route.steps.size} steps, in ${System.currentTimeMillis() - t0} ms, " +
                    "from ${where(from)}, " + (if (chosen != null) "chosen" else if (facing.isFinite()) "keeping the heading" else "no heading (slow or still)") +
                    "; first: ${route.steps.getOrNull(1)?.text ?: route.steps.firstOrNull()?.text}")
                MapsTrace.start(carContext, "car", target, "car", route)
                nav = Nav(target, "car", route, cumulative(route), System.currentTimeMillis(), startOff = metresBetween(from.latitude, from.longitude, route.lats[0], route.lngs[0]))
                lastAlong = 0.0; backFixes = 0
                // the tolls on it, found beside the directions, which do not wait for them
                tolls = emptyList(); tollsAhead = 0; tollSaid = emptySet()
                (map?.style?.getSource("tolls") as? GeoJsonSource)?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(emptyList()))
                launch {
                    val onRoute = withContext(Dispatchers.IO) { runCatching { tollsOn(route, cumulative(route), MapsSearch.tolls(root)) }.getOrDefault(emptyList()) }
                    if (nav?.route !== route) return@launch
                    tolls = onRoute.map { it.second }; tollsAhead = tolls.size
                    (map?.style?.getSource("tolls") as? GeoJsonSource)?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(
                        onRoute.map { (t, _) -> org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.Point.fromLngLat(t.lng, t.lat)) }))
                    if (!rerouted && tolls.isNotEmpty()) {
                        kotlinx.coroutines.delay(4000) // after the first direction
                        say(words.q(R.plurals.maps_say_route_tolls, tolls.size, tolls.size))
                    }
                    invalidate()
                }
                // "new route" said once in two minutes at most (the user, 23 Oct: it said it all the way for kilometres)
                if (rerouted && System.currentTimeMillis() - newRouteSaidAt > 120_000) { newRouteSaidAt = System.currentTimeMillis(); say(words.s(R.string.maps_say_new_route), important = true) }
                else { showParking(0); launch { val c = withContext(Dispatchers.IO) { parkingNear(target).size }; if (nav?.target == target) showParking(c) } }
                if (!rerouted) {
                    hostNav { navigationStarted() }
                    route.steps.firstOrNull()?.let { say(it.text) }
                    // a car trip: the car is no longer where it was parked
                    if (target.id != MapsFiles.PARKING) MapsFiles.places(root).firstOrNull { it.id == MapsFiles.PARKING }?.let { MapsFiles.delete(root, it) }
                }
                drawRoute(); padCamera(); follow(); onFix(here ?: from)
            }.onFailure {
                WriterLog.e("Maps car: no route", it)
                CarToast.makeText(carContext, it.message ?: s(R.string.car_no_way), CarToast.LENGTH_LONG).show()
            }
            invalidate()
        }
    }

    /** Telling the car a route started or ended: a car's Android Auto can refuse it ("not a navigation app", the
     *  user's car 23 Oct, Tetra being from Unknown sources) — the route is driven all the same, the car just isn't told. */
    private fun hostNav(call: NavigationManager.() -> Unit) = runCatching { navigation.call() }.onFailure { WriterLog.i("Maps car: the car refused: ${it.cause?.cause?.message ?: it.message}") }

    fun stop(keepTrip: Boolean = false) {
        if (!keepTrip) { queue = emptyList(); tripLetter = null; waitNext = null; trip = null; MapsPrefs.setPendingTrip(carContext, null) }
        if (nav == null) return
        nav = null; view = DRIVING_VIEW; tolls = emptyList(); tollsAhead = 0; showPlaces = true; parkingAsked = null; showParking(0)
        (map?.style?.getSource("tolls") as? GeoJsonSource)?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(emptyList()))
        CarLink.nav.value = null
        hostNav { navigationEnded() }
        drawRoute(); padCamera(); follow(); showSpeed(); invalidate()
    }

    // —— the voice: over the music, which is lowered meanwhile ——

    private val speech = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(speech).build()
    private val audio = car.getSystemService(AudioManager::class.java)
    private var tts: TextToSpeech? = null
    init {
        var t: TextToSpeech? = null
        t = TextToSpeech(car.applicationContext) { ok ->
            if (ok != TextToSpeech.SUCCESS) return@TextToSpeech
            // no voice for the language: said once on the car's screen (its settings are on the phone)
            if ((t?.setLanguage(MapsPrefs.ttsLocale(words)) ?: -2) < 0 && MapsPrefs.firstNoVoice(carContext, words.lang))
                CarToast.makeText(carContext, s(R.string.maps_no_voice, java.util.Locale.forLanguageTag(words.lang).getDisplayLanguage(carContext.resources.configuration.locales[0])), CarToast.LENGTH_LONG).show()
            t?.setAudioAttributes(speech)
            t?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) { audio.abandonAudioFocusRequest(focus) }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { audio.abandonAudioFocusRequest(focus) }
            })
            tts = t
        }
    }

    /** Spoken unless the voice is off, or set to the important ones only and this is not one. */
    /** [text] spoken over the music; [why] in the log only: which warning, how far, how fast. */
    private fun say(text: String, important: Boolean = false, why: String = "") {
        val voice = MapsPrefs.voice(carContext)
        if (text.isEmpty() || voice == "off" || (voice == "important" && !important)) return
        WriterLog.i("Maps car: say \"$text\"" + if (why.isNotEmpty()) " ($why)" else "")
        audio.requestAudioFocus(focus)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "car")
    }

    // —— what the turn looks like ——

    private fun maneuver(sign: Int) = when (sign) {
        0 -> Maneuver.TYPE_STRAIGHT
        1 -> Maneuver.TYPE_TURN_SLIGHT_RIGHT; -1 -> Maneuver.TYPE_TURN_SLIGHT_LEFT
        2 -> Maneuver.TYPE_TURN_NORMAL_RIGHT; -2 -> Maneuver.TYPE_TURN_NORMAL_LEFT
        3 -> Maneuver.TYPE_TURN_SHARP_RIGHT; -3 -> Maneuver.TYPE_TURN_SHARP_LEFT
        7 -> Maneuver.TYPE_KEEP_RIGHT; -7 -> Maneuver.TYPE_KEEP_LEFT
        8 -> Maneuver.TYPE_U_TURN_RIGHT; -8, -98 -> Maneuver.TYPE_U_TURN_LEFT
        6 -> Maneuver.TYPE_ROUNDABOUT_ENTER_CCW; -6 -> Maneuver.TYPE_ROUNDABOUT_EXIT_CCW // driving on the right: round anticlockwise
        4, 5 -> Maneuver.TYPE_DESTINATION
        else -> Maneuver.TYPE_UNKNOWN
    }

    /** The phone's arrow (NavBanner), turned the same way, white. */
    private fun turnIcon(sign: Int): CarIcon {
        val d = ContextCompat.getDrawable(carContext, if (sign == 4) R.drawable.ic_place else R.drawable.ic_navigation)!!.mutate()
        d.setTint(Color.WHITE)
        val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        Canvas(b).apply { if (sign != 4) rotate(turnDegrees(sign), 48f, 48f); d.setBounds(0, 0, 96, 96); d.draw(this) }
        return CarIcon.Builder(IconCompat.createWithBitmap(b)).build()
    }

    /** As [metresText]: metres and kilometres, or miles and feet or yards where the phone's region uses them. */
    private fun distance(m: Double): Distance = when (words.units) {
        "us", "uk" -> if (m >= 0.1 * MILE) Distance.create(m / MILE, if (m < 9.95 * MILE) Distance.UNIT_MILES_P1 else Distance.UNIT_MILES)
            else if (words.units == "us") Distance.create((Math.round(m * FEET / 10.0) * 10).toDouble(), Distance.UNIT_FEET)
            else Distance.create((Math.round(m * YARDS / 10.0) * 10).toDouble(), Distance.UNIT_YARDS)
        else -> if (m >= 950) Distance.create(m / 1000, if (m < 9950) Distance.UNIT_KILOMETERS_P1 else Distance.UNIT_KILOMETERS)
            else Distance.create((Math.round(m / 10.0) * 10).toDouble(), Distance.UNIT_METERS)
    }

    private fun away(lat: Double, lng: Double) = here?.let { metresText(metresBetween(it.latitude, it.longitude, lat, lng)) } ?: ""

    private fun limit() = carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)

    // —— a spot tapped on the map ——

    private var pin: org.maplibre.android.geometry.LatLng? = null

    private fun setPin(at: org.maplibre.android.geometry.LatLng?) {
        pin = at
        (map?.style?.getSource("pin") as? GeoJsonSource)?.setGeoJson(
            org.maplibre.geojson.FeatureCollection.fromFeatures(listOfNotNull(at?.let { org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.Point.fromLngLat(it.longitude, it.latitude)) })))
        showPanel(); invalidate()
    }

    /** To the pin at once; its address names it among the recent ones when found (the address list is slow to read). */
    private fun goToPin(at: org.maplibre.android.geometry.LatLng) {
        WriterLog.i("Maps car: to the pin")
        setPin(null)
        screenManager.popToRoot(); go(Place("", s(R.string.car_pin), at.latitude, at.longitude))
        lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) { runCatching { MapsAddresses.describe(root, MapsSearch.load(root), at.latitude, at.longitude) }.getOrNull() }
                ?.substringBefore(",")?.ifBlank { null } ?: s(R.string.car_pin)
            MapsPrefs.addRecent(carContext, Place("", name, at.latitude, at.longitude))
        }
    }

    /** A place picked: back to the map, and on our way; one not saved is kept among the recent ones. */
    private fun pick(p: Place) {
        if (p.id.isEmpty()) MapsPrefs.addRecent(carContext, p)
        screenManager.popToRoot(); go(p)
    }

    private fun card(title: String, icon: Int, text: String, click: () -> Unit) = GridItem.Builder().setTitle(title.ifBlank { s(R.string.maps_place) })
        .apply { if (text.isNotEmpty()) setText(text) }
        .setImage(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).setTint(CarColor.createCustom(0xFF5C6BC0.toInt(), 0xFF9FA8DA.toInt())).build(), GridItem.IMAGE_TYPE_LARGE) // big targets while driving
        .setOnClickListener(click).build()

    /** All the saved places not on the start page, nearest first. */
    private inner class FavouritesScreen : Screen(carContext) {
        override fun onGetTemplate(): Template {
            val all = MapsFiles.places(root)
            val home = MapsFiles.slots(all).take(MapsFiles.PHONE_SLOTS).filterNotNull().toSet()
            val h = here
            val items = ItemList.Builder().setNoItemsMessage(s(R.string.car_no_other_places))
            (all - home).sortedBy { if (h == null) 0.0 else metresBetween(h.latitude, h.longitude, it.lat, it.lng) }
                .take(carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID))
                .forEach { p -> items.addItem(card(p.short, placeIcon(p.icon), away(p.lat, p.lng)) { pick(p) }) }
            return GridTemplate.Builder().setTitle(s(R.string.car_favourites)).setHeaderAction(Action.BACK).setSingleList(items.build()).build()
        }
    }

    // —— ⚙ on the map: how much the voice says (the user, 22 Oct: "a gear for settings like to disable voice on nav or to
    // keep only the iportant warnings like wrong direction and choage of root. No sygnal and so on") ——

    private inner class SettingsScreen : Screen(carContext) {
        override fun onGetTemplate(): Template {
            val modes = listOf("all" to s(R.string.car_voice_all), "important" to s(R.string.car_voice_important), "off" to s(R.string.car_voice_off))
            val items = ItemList.Builder().setSelectedIndex(modes.indexOfFirst { it.first == MapsPrefs.voice(carContext) }.coerceAtLeast(0))
                .setOnSelectedListener { i -> MapsPrefs.setVoice(carContext, modes[i].first); if (modes[i].first == "off") tts?.stop(); invalidate() }
            modes.forEach { (_, label) -> items.addItem(Row.Builder().setTitle(label).build()) }
            val radii = ItemList.Builder().setSelectedIndex(MapsPrefs.RADII.indexOfFirst { it.first == MapsPrefs.parkingRadius(carContext) }.coerceAtLeast(0))
                .setOnSelectedListener { i -> MapsPrefs.setParkingRadius(carContext, MapsPrefs.RADII[i].first); nav?.target?.let { tg -> lifecycleScope.launch { showParking(withContext(Dispatchers.IO) { parkingNear(tg).size }) } }; invalidate() }
            MapsPrefs.RADII.forEach { (_, label) -> radii.addItem(Row.Builder().setTitle(label).build()) }
            val roads = ItemList.Builder().addItem(Row.Builder().setTitle(s(R.string.car_warn_bends))
                .setToggle(androidx.car.app.model.Toggle.Builder { on -> MapsPrefs.setBends(carContext, on) }.setChecked(MapsPrefs.bends(carContext)).build()).build())
                .addItem(Row.Builder().setTitle(s(R.string.maps_roads_main)).addText(s(R.string.car_main_roads_off))
                    .setToggle(androidx.car.app.model.Toggle.Builder { on -> MapsPrefs.setMainRoads(carContext, on) }.setChecked(MapsPrefs.mainRoads(carContext)).build()).build())
            return ListTemplate.Builder().setTitle(s(R.string.common_settings)).setHeaderAction(Action.BACK)
                .addSectionedList(androidx.car.app.model.SectionedItemList.create(items.build(), s(R.string.car_voice)))
                .addSectionedList(androidx.car.app.model.SectionedItemList.create(roads.build(), s(R.string.car_roads)))
                .addSectionedList(androidx.car.app.model.SectionedItemList.create(radii.build(), s(R.string.car_amea_radius))).build()
        }
    }

    // —— Search: saved places and the offline list, nearest first, as you type (the car lets typing only when parked) ——

    private inner class SearchScreen : Screen(carContext) {
        private var found: List<Place> = emptyList()
        private var job: kotlinx.coroutines.Job? = null

        private fun find(q: String) {
            job?.cancel()
            val h = here
            job = lifecycleScope.launch {
                found = withContext(Dispatchers.Default) {
                    val f = MapsSearch.fold(q.trim())
                    if (f.isEmpty()) return@withContext emptyList()
                    val mine = MapsFiles.places(root).filter { MapsSearch.fold("${it.name} ${it.label}").contains(f) }
                    val list = runCatching { MapsSearch.search(MapsSearch.load(root), q, h?.latitude ?: 38.4, h?.longitude ?: 23.8, limit = 30) }.getOrDefault(emptyList())
                    mine + list.map { Place("", it.title(carContext), it.lat, it.lng) }
                }
                invalidate()
            }
        }

        override fun onGetTemplate(): Template {
            val items = ItemList.Builder()
            found.take(limit()).forEach { p ->
                items.addItem(Row.Builder().setTitle(p.name).apply { away(p.lat, p.lng).takeIf { it.isNotEmpty() }?.let(::addText) }.setOnClickListener { pick(p) }.build())
            }
            return androidx.car.app.model.SearchTemplate.Builder(object : androidx.car.app.model.SearchTemplate.SearchCallback {
                override fun onSearchTextChanged(text: String) = find(text)
                override fun onSearchSubmitted(text: String) = find(text)
            }).setHeaderAction(Action.BACK).setSearchHint(s(R.string.car_search_hint)).setShowKeyboardByDefault(true).setItemList(items.build()).build()
        }
    }

    // —— Nearby: what a driver looks for, then the nearest of it ——

    /** The saved trips (planned on the phone): one picked starts at its A. */
    private inner class TripsScreen : Screen(carContext) {
        override fun onGetTemplate(): Template {
            val items = ItemList.Builder().setNoItemsMessage(s(R.string.car_no_trips))
            MapsFiles.trips(root).take(limit()).forEach { tr ->
                items.addItem(Row.Builder().setTitle(tr.name).addText(tr.stops.withIndex().joinToString(" → ") { (i, p) -> "${Trip.letter(i)} ${p.name}" })
                    .setOnClickListener { startTrip(tr.stops) }.build())
            }
            return ListTemplate.Builder().setTitle(s(R.string.car_trips)).setHeaderAction(Action.BACK).setSingleList(items.build()).build()
        }
    }

    /** The ways to [target] from here, fastest first, with their roads and tolls: one picked goes instead. */
    private inner class RoutesScreen(private val target: Place) : Screen(carContext) {
        private var routes: List<Route>? = null
        private var tollCounts: List<Int> = emptyList()
        init {
            val h = here
            lifecycleScope.launch {
                routes = if (h == null) emptyList() else withContext(Dispatchers.IO) {
                    runCatching { MapsRouting.routes(graphAt(h.latitude, h.longitude), h.latitude, h.longitude, target.lat, target.lng, "car", words, main = MapsPrefs.mainRoads(carContext), noLeft = MapsPrefs.noLeft(carContext)) }.getOrDefault(emptyList())
                        .also { rs -> tollCounts = rs.map { r -> runCatching { tollsOn(r, cumulative(r), MapsSearch.tolls(root)).size }.getOrDefault(0) } }
                }
                invalidate()
            }
        }

        override fun onGetTemplate(): Template {
            val t = ListTemplate.Builder().setTitle(s(R.string.car_routes_to, target.name)).setHeaderAction(Action.BACK)
            val rs = routes ?: return t.setLoading(true).build()
            val items = ItemList.Builder().setNoItemsMessage(s(R.string.car_no_other_way))
            rs.forEachIndexed { i, r ->
                val min = (r.millis / 60000).toInt()
                val time = minutesText(carContext, min.toLong())
                val roads = when (r.roads) { "motorway" -> s(R.string.maps_roads_motorway); "main" -> s(R.string.maps_roads_main); "local" -> s(R.string.maps_roads_local)
                    "track" -> s(R.string.maps_roads_tracks); else -> r.roads.replaceFirstChar { it.uppercase() } }
                val tolls = tollCounts.getOrElse(i) { 0 }.let { if (it == 0) s(R.string.car_no_tolls) else carContext.resources.getQuantityString(R.plurals.car_tolls, it, it) }
                items.addItem(Row.Builder().setTitle("$time · ${metresText(r.metres)}").addText("$roads · $tolls")
                    .setOnClickListener { screenManager.popToRoot(); go(target, r) }.build())
            }
            return t.setSingleList(items.build()).build()
        }
    }

    /** ΑμεΑ parking near [target], nearest to it first: one picked becomes where the drive ends. */
    private inner class ParkingScreen(private val target: Place) : Screen(carContext) {
        private var spots: List<Poi>? = null
        init { lifecycleScope.launch { spots = withContext(Dispatchers.IO) { parkingNear(target) }; invalidate() } }

        override fun onGetTemplate(): Template {
            val t = ListTemplate.Builder().setTitle(s(R.string.car_parking_near, target.name)).setHeaderAction(Action.BACK)
            val s = spots ?: return t.setLoading(true).build()
            val items = ItemList.Builder().setNoItemsMessage(s(R.string.car_no_amea_within, MapsPrefs.RADII.first { it.first == MapsPrefs.parkingRadius(carContext) }.second))
            s.take(limit()).forEach { poi ->
                val d = metresText(metresBetween(target.lat, target.lng, poi.lat, poi.lng))
                items.addItem(Row.Builder().setTitle(poi.more.ifEmpty { poi.title(carContext) }).addText(s(R.string.car_from, d, target.name))
                    .setOnClickListener { screenManager.popToRoot(); go(Place("", "♿ ${poi.more.ifEmpty { s(R.string.maps_kind_parking) }}", poi.lat, poi.lng, "accessible")) }.build())
            }
            return t.setSingleList(items.build()).build()
        }
    }

    private inner class KindsScreen(private val stop: Boolean = false) : Screen(carContext) {
        override fun onGetTemplate(): Template {
            val items = ItemList.Builder()
            listOf("disabled_parking", "fuel", "charging", "parking", "pharmacy", "eopyy", "supermarket", "toilets", "hospital").take(limit()).forEach { k ->
                val kind = MapsSearch.PILLS.first { it.key == k }
                items.addItem(Row.Builder().setTitle("${kind.emoji} ${s(kind.label)}").setBrowsable(true).setOnClickListener { screenManager.push(FoundScreen(kind, stop)) }.build())
            }
            return ListTemplate.Builder().setTitle(s(if (stop) R.string.car_add_stop else R.string.maps_nearby)).setHeaderAction(Action.BACK).setSingleList(items.build()).build()
        }
    }

    private inner class FoundScreen(private val kind: MapsSearch.Kind, private val stop: Boolean = false) : Screen(carContext) {
        private var found: List<Poi>? = null
        init {
            lifecycleScope.launch {
                val h = here
                found = if (h == null) emptyList() else withContext(Dispatchers.IO) {
                    runCatching { MapsSearch.search(MapsSearch.load(root), "", h.latitude, h.longitude, kind.key, limit = limit()) }.getOrDefault(emptyList())
                }
                invalidate()
            }
        }

        override fun onGetTemplate(): Template {
            val t = ListTemplate.Builder().setTitle(s(kind.label)).setHeaderAction(Action.BACK)
            val f = found ?: return t.setLoading(true).build()
            val items = ItemList.Builder().setNoItemsMessage(s(if (here == null) R.string.car_waiting_gps else R.string.car_none_near))
            f.forEach { poi ->
                items.addItem(Row.Builder().setTitle(poi.title(carContext)).apply { listOf(away(poi.lat, poi.lng), poi.more).filter { it.isNotEmpty() }.joinToString(" · ").takeIf { it.isNotEmpty() }?.let(::addText) }
                    .setOnClickListener { Place("", poi.title(carContext), poi.lat, poi.lng).let { if (stop) pickStop(it) else pick(it) } }.build())
            }
            return t.setSingleList(items.build()).build()
        }
    }
}
