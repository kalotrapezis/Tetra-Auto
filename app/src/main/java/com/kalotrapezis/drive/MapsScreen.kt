package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.File
import java.util.Locale

private val MAPS_COLOR = androidx.compose.ui.graphics.Color(0xFF5C6BC0)
/** The maps there are and a degree of sea round them (Greece's when none says): the camera stays within. */
private fun cameraBounds(root: File): LatLngBounds {
    val all = MapsFiles.maps(root).mapNotNull { it.bounds }
    if (all.isEmpty()) return LatLngBounds.from(42.2, 30.2, 34.2, 18.6)
    return LatLngBounds.from(all.maxOf { it[3] } + 1, all.maxOf { it[2] } + 1, all.minOf { it[1] } - 1, all.minOf { it[0] } - 1)
}

internal fun placeIcon(icon: String) = when (icon) {
    "star" -> R.drawable.ic_star
    "heart" -> R.drawable.ic_favorite
    "accessible" -> R.drawable.ic_accessible
    "home" -> R.drawable.ic_home
    "work" -> R.drawable.ic_work
    "school" -> R.drawable.ic_school
    "family" -> R.drawable.ic_people
    "friend" -> R.drawable.ic_person
    "pharmacy" -> R.drawable.ic_pharmacy
    "hospital" -> R.drawable.ic_hospital
    "doctor" -> R.drawable.ic_doctor
    "travel" -> R.drawable.ic_flight
    "hotel" -> R.drawable.ic_hotel
    "food" -> R.drawable.ic_restaurant
    "tavern" -> R.drawable.ic_tavern
    "cafe" -> R.drawable.ic_cafe
    "shop" -> R.drawable.ic_shop
    "fuel" -> R.drawable.ic_fuel
    "parking" -> R.drawable.ic_parking
    "beach" -> R.drawable.ic_beach
    "waterfall" -> R.drawable.ic_waterfall
    "river" -> R.drawable.ic_river
    "lake" -> R.drawable.ic_lake
    "mountain" -> R.drawable.ic_mountain
    "forest" -> R.drawable.ic_forest
    "camping" -> R.drawable.ic_camping
    else -> R.drawable.ic_place
}

/**
 * Public things on the map — search results, the map's additions — in the map's own icon style (the user, 21 Oct: "my
 * personal keep our style what I save as public info gets the maps own style"): its sprite where it has one, else drawn as
 * it draws them, a rounded square of a pale tint with a coloured edge and symbol, in its colours (orange food, blue shops
 * and parking, purple learning, green nature and money, pink culture; red for health, which it has none of).
 */
private val SPRITE = mapOf("restaurant" to "restaurant", "cafe" to "cafe", "fast_food" to "fast_food", "supermarket" to "supermarket",
    "kindergarten" to "school", "primary_school" to "school", "secondary_school" to "school", "school" to "school", "university" to "university",
    "museum" to "museum", "beach" to "beach", "toilets" to "toilets")
private val ORANGE = 0xFFF7A157.toInt() to 0xFFFDE7D2.toInt(); private val BLUE = 0xFF4BA3D3.toInt() to 0xFFD9EEF8.toInt()
private val PURPLE = 0xFF8F83BC.toInt() to 0xFFE3E2F3.toInt(); private val GREEN = 0xFF57A26D.toInt() to 0xFFD7EEDF.toInt()
private val RED = 0xFFE5635C.toInt() to 0xFFFADBD9.toInt(); private val SLATE = 0xFF6E8FA8.toInt() to 0xFFDDE7EE.toInt()
private fun drawnLook(kind: String): Triple<Int?, Pair<Int, Int>, String> = when (kind) {
    "pharmacy", "eopyy" -> Triple(R.drawable.ic_pharmacy, RED, ""); "hospital" -> Triple(R.drawable.ic_hospital, RED, "")
    "fuel", "charging" -> Triple(R.drawable.ic_fuel, SLATE, ""); "parking" -> Triple(R.drawable.ic_parking, BLUE, "")
    "disabled_parking" -> Triple(R.drawable.ic_accessible, BLUE, ""); "hotel" -> Triple(R.drawable.ic_hotel, PURPLE, "")
    "bakery" -> Triple(R.drawable.ic_shop, ORANGE, ""); "atm" -> Triple(null, GREEN, "ATM"); "toll" -> Triple(null, ORANGE, "€")
    else -> Triple(R.drawable.ic_place, PURPLE, "")
}

/** The marker for a public [kind]: the map's sprite, or ours drawn in its style; the plain pin for one without (a town, "bar"). */
private fun markerOf(kind: String) = SPRITE[kind] ?: if (MapsSearch.PILLS.any { it.key == kind }) "k-$kind" else "k-pin"

/** The drawn markers ("k-<kind>") and the favourites' own ("e-<icon>", Maps' indigo circle) put into the map's style once. */
private fun addMarkers(context: Context, style: Style) {
    val d = context.resources.displayMetrics.density
    fun square(icon: Int?, colors: Pair<Int, Int>, text: String): android.graphics.Bitmap {
        val px = (20 * d).toInt(); val b = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b); val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val r = android.graphics.RectF(d, d, px - d, px - d); val corner = px * 0.28f
        paint.color = colors.second; c.drawRoundRect(r, corner, corner, paint)
        paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = 1.6f * d; paint.color = colors.first; c.drawRoundRect(r, corner, corner, paint)
        paint.style = android.graphics.Paint.Style.FILL
        if (icon != null) androidx.core.content.ContextCompat.getDrawable(context, icon)?.mutate()?.let { dr ->
            dr.setTint(colors.first); val m = (px * 0.22f).toInt(); dr.setBounds(m, m, px - m, px - m); dr.draw(c)
        } else {
            paint.textAlign = android.graphics.Paint.Align.CENTER; paint.isFakeBoldText = true
            paint.textSize = px * (if (text.length > 1) 0.34f else 0.6f); c.drawText(text, px / 2f, px / 2f - (paint.descent() + paint.ascent()) / 2, paint)
        }
        return b
    }
    // the favourites a little bigger than the map's own (20 dp), not half again (the user, 21 Oct: "The favories are huge")
    fun round(icon: Int): android.graphics.Bitmap {
        val px = (22 * d).toInt(); val b = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b); val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = android.graphics.Color.WHITE; c.drawCircle(px / 2f, px / 2f, px / 2f, paint)
        paint.color = 0xFF5C6BC0.toInt(); c.drawCircle(px / 2f, px / 2f, px / 2f - px / 14f, paint)
        androidx.core.content.ContextCompat.getDrawable(context, icon)?.mutate()?.let { dr -> dr.setTint(android.graphics.Color.WHITE); val m = px / 4; dr.setBounds(m, m, px - m, px - m); dr.draw(c) }
        return b
    }
    (MapsSearch.PILLS.map { it.key }.filter { it !in SPRITE } + "pin").forEach { k -> val (icon, colors, text) = drawnLook(k); style.addImage("k-$k", square(icon, colors, text)) }
    Place.ICONS.forEach { style.addImage("e-$it", round(placeIcon(it))) }
}

/** [rolling]: Settings › Maps › On foot is Rolling, a wheelchair (the user, 21 Oct: "Walking to be changed the icon to rolling"). */
internal fun modeIcon(mode: String, rolling: Boolean = false) = when (mode) {
    "foot" -> if (rolling) R.drawable.ic_accessible else R.drawable.ic_directions_walk
    "bike" -> R.drawable.ic_directions_bike
    else -> R.drawable.ic_directions_car
}

/** The ways there, before starting: [chosen] is the one drawn bold. */
private data class Preview(val target: Place, val mode: String, val routes: List<Route>, val chosen: Int, val from: Location)

/** A navigation running: the route, where on it the last fix was, and what has been said. */
internal data class Nav(
    val target: Place, val mode: String, val route: Route, val cum: DoubleArray, val since: Long,
    val index: Int = 0, val p: Progress? = null, val offFixes: Int = 0, val said: Set<String> = emptySet(),
    val bends: List<Bend> = if (mode == "car") bendsOn(route, cum) else emptyList(),
    /** How far from the route it started (a car park off the roads): no new route for that while still short of it. */
    val startOff: Double = 0.0,
)

/**
 * Settings › Maps. The directions' language, words and voice alike (the user, 21 Oct: "at what loaguage will I be able to
 * change it? Eng or Gr?"); on foot walking or rolling; where the car is parked, kept and opened (the user, 21 Oct: "save
 * park location … save automaticaly and have the map start from location to that parking spot automaticaly at the next open").
 */
internal object MapsPrefs {
    private fun prefs(c: Context) = c.getSharedPreferences("maps", Context.MODE_PRIVATE)
    /** The map's own look, apart from the app's (the user, 21 Oct: "I dont like the dark theme on the map"): light unless set. */
    fun mapTheme(c: Context) = prefs(c).getString("map_theme", "light") ?: "light"
    fun setMapTheme(c: Context, theme: String) = prefs(c).edit().putString("map_theme", theme).apply()
    fun rolling(c: Context) = prefs(c).getBoolean("rolling", false)
    fun setRolling(c: Context, on: Boolean) = prefs(c).edit().putBoolean("rolling", on).apply()
    fun bends(c: Context) = prefs(c).getBoolean("bends", true)
    fun setBends(c: Context, on: Boolean) = prefs(c).edit().putBoolean("bends", on).apply()
    /** By car on main roads (MapsRouting.MAIN_ROADS) or the shortest way (the user, 24 Oct). */
    fun mainRoads(c: Context) = prefs(c).getBoolean("main_roads", true)
    fun setMainRoads(c: Context, on: Boolean) = prefs(c).edit().putBoolean("main_roads", on).apply()
    /** No cutting across ([MapsRouting.NO_LEFT]): By car's third way, the way unless another is chosen (the user, 5 Nov). */
    fun noLeft(c: Context) = mainRoads(c) && prefs(c).getBoolean("no_left", true)
    fun setNoLeft(c: Context, on: Boolean) = prefs(c).edit().putBoolean("no_left", on).apply()
    /** What is said a little before a sharp bend ([bendsOn]). */
    fun bendWords(w: Words, right: Boolean) = w.s(if (right) R.string.maps_bend_right else R.string.maps_bend_left)
    fun autoPark(c: Context) = prefs(c).getBoolean("auto_park", true)
    fun setAutoPark(c: Context, on: Boolean) = prefs(c).edit().putBoolean("auto_park", on).apply()
    fun openAtCar(c: Context) = prefs(c).getBoolean("open_at_car", false)
    fun setOpenAtCar(c: Context, on: Boolean) = prefs(c).edit().putBoolean("open_at_car", on).apply()
    /** Places gone to that are not saved, newest first (the car's start page shows them after Home's). */
    fun recent(c: Context): List<Place> = prefs(c).getString("recent", "").orEmpty().lines().mapNotNull { l ->
        val f = l.split('\t'); if (f.size != 3) null else Place("", f[0], f[1].toDoubleOrNull() ?: return@mapNotNull null, f[2].toDoubleOrNull() ?: return@mapNotNull null)
    }
    fun addRecent(c: Context, p: Place) {
        val all = listOf(p) + recent(c).filter { metresBetween(it.lat, it.lng, p.lat, p.lng) > 50 }
        prefs(c).edit().putString("recent", all.take(20).joinToString("\n") { "${it.name.replace(Regex("[\t\n]"), " ")}\t${it.lat}\t${it.lng}" }).apply()
    }
    /**
     * A trip stopped between two stops: the trip and the next stop's index, kept for 12 hours, so the next leg still starts
     * after the engine (and Android Auto) was off at a stop, or the map was closed.
     */
    fun pendingTrip(c: Context): Pair<Trip, Int>? {
        val p = prefs(c)
        if (System.currentTimeMillis() - p.getLong("trip_at", 0) > 12 * 3600_000L) return null
        val t = Trip.parse(p.getString("trip", null) ?: return null) ?: return null
        return (t to p.getInt("trip_next", 0)).takeIf { it.second in t.stops.indices }
    }
    fun setPendingTrip(c: Context, t: Trip?, next: Int = 0) = prefs(c).edit().apply {
        if (t == null) remove("trip").remove("trip_next").remove("trip_at")
        else putString("trip", t.json()).putInt("trip_next", next).putLong("trip_at", System.currentTimeMillis())
    }.apply()

    /** How far from the destination ΑμεΑ parking is looked for, in metres (the user, 22 Oct: "2km 1.5km 1 and 500m"). */
    fun parkingRadius(c: Context) = prefs(c).getInt("parking_radius", 500)
    fun setParkingRadius(c: Context, m: Int) = prefs(c).edit().putInt("parking_radius", m).apply()
    val RADII = listOf(500 to "500 m", 1000 to "1 km", 1500 to "1.5 km", 2000 to "2 km")

    /** How much the car's voice says: "all", "important" (wrong way, new route, no GPS, arrival) or "off". */
    fun voice(c: Context) = prefs(c).getString("voice", "all") ?: "all"
    fun setVoice(c: Context, v: String) = prefs(c).edit().putString("voice", v).apply()
    /** Until one is chosen, the app's own language (Settings › Appearance › Language), of Tetra's; else English. */
    fun lang(c: Context) = prefs(c).getString("lang", null) ?: c.resources.configuration.locales[0].language.let { if (it in AppLocale.tags) it else "en" }
    fun setLang(c: Context, lang: String) = prefs(c).edit().putString("lang", lang).apply()
    /** Tetra's words for the directions, in their language. */
    fun words(c: Context) = Words.of(c, lang(c))
    /**
     * How distances are said and shown, as the phone's region measures (Android's own setting): "us" miles and feet, "uk"
     * miles and yards, else "metric". Read once; JVM tests have no Android, so metric there.
     */
    val units: String by lazy {
        runCatching {
            when (android.icu.util.LocaleData.getMeasurementSystem(android.icu.util.ULocale.forLocale(android.content.res.Resources.getSystem().configuration.locales[0]))) {
                android.icu.util.LocaleData.MeasurementSystem.US -> "us"
                android.icu.util.LocaleData.MeasurementSystem.UK -> "uk"
                else -> "metric"
            }
        }.getOrDefault("metric")
    }
    /** The voice for [w]'s language: Britain's or America's English by the units, Greece's Greek, Portugal's Portuguese. */
    fun ttsLocale(w: Words): Locale = Locale.forLanguageTag(when (w.lang) { "en" -> if (w.units == "us") "en-US" else "en-GB"; "el" -> "el-GR"; "pt" -> "pt-PT"; else -> w.lang })
    /** "In 2 kilometres, [then]": the warning ahead of a turn, its distance as [spokenDistance] rounds it. */
    fun inDistance(w: Words, metres: Double, car: Boolean, then: String): String {
        val (d, unit) = spokenDistance(metres, car, w.units)
        val id = when (unit) { "km" -> R.plurals.maps_in_km; "mi" -> R.plurals.maps_in_mi; "ft" -> R.plurals.maps_in_ft; "yd" -> R.plurals.maps_in_yd; else -> R.plurals.maps_in_m }
        // only kilometres and miles are said in halves
        return w.measure(id, d, if (unit == "mi") R.string.maps_in_mi_fraction else R.string.maps_in_km_fraction, w.number(d), then)
    }
    fun sample(w: Words) = inDistance(w, 300.0, true, w.s(R.string.maps_sample_turn))
    /** Whether the phone's missing voice for [lang] is yet to be told: true once a language. */
    fun firstNoVoice(c: Context, lang: String) = ("no_voice_$lang" !in prefs(c).all).also { if (it) prefs(c).edit().putBoolean("no_voice_$lang", true).apply() }
    /** Android's text-to-speech settings, where a voice is added. */
    fun voiceSettings(c: Context) = runCatching { c.startActivity(android.content.Intent("com.android.settings.TTS_SETTINGS").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

internal fun hasLocation(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/**
 * Maps (Plan.md § Maps): Greece offline (Tetra/.System/Maps/Shared/greece.pmtiles, Protomaps' light or dark style with Greek names), where you are,
 * your saved places (long-press the map to save one), and turn-by-turn directions by car, on foot or by bike, worked out
 * on the phone (MapsRouting) and spoken in Greek. [go] (a place on Home's card) starts its directions as soon as there is a fix.
 */
@SuppressLint("MissingPermission")
@Composable internal fun MapsScreen(root: File, go: Place?, back: () -> Unit, search: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapFile = MapsFiles.map(root)
    var places by remember { mutableStateOf(MapsFiles.places(root)) }
    var selected by remember { mutableStateOf<Place?>(null) }
    var editing by remember { mutableStateOf<Place?>(null) }
    var deleting by remember { mutableStateOf<Place?>(null) }
    // [search]: an address from Contacts, looked for at once
    var tab by remember { mutableStateOf(if (search != null) MapsTab.Search else null) }
    var query by remember { mutableStateOf(search ?: "") }
    var pill by remember { mutableStateOf<String?>(null) }
    var navQuery by remember { mutableStateOf("") }
    var navMode by remember { mutableStateOf("car") }
    var shownList by remember { mutableStateOf<String?>(null) }
    var lists by remember { mutableStateOf(MapsFiles.lists(root)) }
    // trips (Trip): planning one — how many stops, the ones pointed to so far — and one running, the next stop waiting
    var trips by remember { mutableStateOf(MapsFiles.trips(root)) }
    var askCount by remember { mutableStateOf(false) }
    var planCount by remember { mutableStateOf(0) }
    var plan by remember { mutableStateOf<List<Place>?>(null) }
    var naming by remember { mutableStateOf(false) }
    var tripRun by remember { mutableStateOf<Pair<Trip, Int>?>(null) } // the trip and the stop being driven to
    var nextStop by remember { mutableStateOf<Place?>(null) } // a stop reached: the next leg starts when the car moves on
    var stoppedAt by remember { mutableStateOf<Location?>(null) }
    LaunchedEffect(Unit) {
        // a trip left between stops (the map closed, or done in the car): its next leg waits here
        if (go == null && !CarLink.connected.value) MapsPrefs.pendingTrip(context)?.let { (t, i) -> tripRun = t to i; nextStop = t.stops[i] }
    }
    var listEditing by remember { mutableStateOf<PlaceList?>(null) }
    var detail by remember { mutableStateOf<Poi?>(null) } // what the search list knows of the place shown
    var all by remember { mutableStateOf<List<Poi>>(emptyList()) }
    var added by remember { mutableStateOf(runCatching { MapsSearch.added(root) }.getOrDefault(emptyList())) }
    /** After a change to the map's additions: they and the search list read again. */
    fun reloadAdded() = scope.launch {
        added = withContext(Dispatchers.IO) { MapsSearch.added(root) }
        all = withContext(Dispatchers.IO) { runCatching { MapsSearch.load(root) }.getOrDefault(all) }
    }
    var listLoading by remember { mutableStateOf(true) }
    var center by remember { mutableStateOf(LatLng(38.4, 23.8)) }
    var near by remember { mutableStateOf<Place?>(null) } // Nearby: Search around this place instead of the map's middle
    var results by remember { mutableStateOf<List<Poi>>(emptyList()) }
    var onMap by remember { mutableStateOf<Pair<String, List<Poi>>?>(null) } // Search's OK: what it found, left on the map, and its name
    var navResults by remember { mutableStateOf<List<Poi>>(emptyList()) }
    var road by remember { mutableStateOf<Pair<String, String>?>(null) } // the road under me: name, code
    var limit by remember { mutableStateOf<Int?>(null) } // its speed limit, km/h
    var speed by remember { mutableStateOf<Int?>(null) } // mine, km/h
    var limitAsked by remember { mutableStateOf(0L) }
    var lastReal by remember { mutableStateOf<Location?>(null) } // the last fix from the GPS itself
    // after parking: "was it an ΑμεΑ spot?", one tap puts it on the map (the user, 21 Oct: the spots he knows in Καβάλα and Ξάνθη)
    var askAmea by remember { mutableStateOf<Pair<Location, Long>?>(null) }
    // a municipality's ΑμεΑ spot being placed by hand: the next long press puts it there
    var checks by remember { mutableStateOf(runCatching { MapsCheck.load(root) }.getOrDefault(emptyList())) }
    var placing by remember { mutableStateOf<AmeaCheck?>(null) }
    var lastRealAt by remember { mutableStateOf(0L) } // when it came, by this device's clock (a fix's own stamp can be empty)
    var reckoning by remember { mutableStateOf(false) } // no signal: going on along the route at the last speed
    var address by remember { mutableStateOf<String?>(null) } // of the place shown
    var here by remember { mutableStateOf<Location?>(null) }
    var live by remember { mutableStateOf(false) } // a fix came while the map is open (not Android's stored last one)
    var granted by remember { mutableStateOf(hasLocation(context)) }
    var nav by remember { mutableStateOf<Nav?>(null) }
    var tapRoad by remember { mutableStateOf<String?>(null) } // the road a spot was tapped on (roadAtPoint)
    var view by remember { mutableStateOf(DRIVING_VIEW) } // by car, the camera's zoom and tilt (carView)
    val carNav by CarLink.nav.collectAsState() // directions running on the car's screen, shown here too
    var preview by remember { mutableStateOf<Preview?>(null) }
    var navSheet by remember { mutableStateOf(false) } // the handle's sheet over the directions bar: tolls and the turns ahead
    var finding by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf(go) }
    var muted by remember { mutableStateOf(false) }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    OpenTiming("Maps", mapRef != null)
    // a spot tapped on no place is named this until its address comes (compared below: the same words, in the same language)
    val droppedPin = stringResource(R.string.maps_dropped_pin)

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = hasLocation(context) }
    LaunchedEffect(Unit) { if (!granted) ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }

    // the voice: the directions' language (Settings › Maps)
    val words = remember { MapsPrefs.words(context) }
    var noVoice by remember { mutableStateOf(false) }
    val rolling = remember { MapsPrefs.rolling(context) }
    fun profile(mode: String) = if (mode == "foot" && rolling) "wheelchair" else mode
    var fast by remember { mutableStateOf(0) } // fixes in a row at car speed
    var touchedAt by remember { mutableStateOf(0L) } // a finger last moved the map
    var loose by remember { mutableStateOf(false) } // directions running, the camera off me (logged once)
    val tts = remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        var t: TextToSpeech? = null
        t = TextToSpeech(context.applicationContext) { ok ->
            if (ok != TextToSpeech.SUCCESS) return@TextToSpeech
            if ((t?.setLanguage(MapsPrefs.ttsLocale(words)) ?: -2) < 0 && MapsPrefs.firstNoVoice(context, words.lang)) noVoice = true
            tts.value = t
        }
        onDispose { t.stop(); t.shutdown() }
    }
    /** [text] spoken; [why] in the log only: which warning, how far, how fast. */
    fun say(text: String, why: String = "") {
        WriterLog.i("Maps: say \"$text\"${if (muted) " (muted)" else ""}" + if (why.isNotEmpty()) " ($why)" else "")
        if (!muted) tts.value?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "maps")
    }

    /** The car is here: one place, "My car", saved over the last one. */
    fun park(loc: Location, why: String) {
        WriterLog.i("Maps: parked ($why)")
        askAmea = loc to System.currentTimeMillis()
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { MapsFiles.save(root, Place(MapsFiles.PARKING, context.getString(R.string.maps_my_car), loc.latitude, loc.longitude, "parking", mode = "foot")) } }
            places = withContext(Dispatchers.IO) { MapsFiles.places(root) }
            Toast.makeText(context, context.getString(R.string.maps_parking_saved), Toast.LENGTH_SHORT).show()
        }
    }
    /** The car moves on: the parking spot goes (on foot or by bike it stays). */
    fun unpark(why: String) {
        if (places.none { it.id == MapsFiles.PARKING }) return
        WriterLog.i("Maps: parking cleared ($why)")
        scope.launch {
            withContext(Dispatchers.IO) { places.firstOrNull { it.id == MapsFiles.PARKING }?.let { MapsFiles.delete(root, it) } }
            places = withContext(Dispatchers.IO) { MapsFiles.places(root) }
        }
    }

    fun stopNav() {
        nav = null
        view = DRIVING_VIEW
        mapRef?.locationComponent?.takeIf { it.isLocationComponentActivated }?.apply { cameraMode = CameraMode.NONE; renderMode = RenderMode.COMPASS }
        mapRef?.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, 0.0)) // me in the middle again
    }

    /** The camera's padding for [tilt]: tilted further at speed, the car lower on the screen (up to 70 % down), the road ahead above it. */
    fun ahead(tilt: Double) = doubleArrayOf(0.0, context.resources.displayMetrics.heightPixels * 0.4 * ((tilt - 45) / 15).coerceIn(0.0, 1.0), 0.0, 0.0)

    /** The camera on me, turned the way I go; zoom and tilt through the tracking itself (a camera move of ours would end it). */
    fun follow(mode: String) {
        val lc = mapRef?.locationComponent?.takeIf { it.isLocationComponentActivated } ?: return
        lc.renderMode = RenderMode.GPS
        lc.setCameraMode(CameraMode.TRACKING_GPS, object : org.maplibre.android.location.OnLocationCameraTransitionListener {
            override fun onLocationCameraTransitionFinished(cameraMode: Int) {
                val (z, t) = if (mode == "car") view else 17.5 to 0.0
                lc.zoomWhileTracking(z); lc.tiltWhileTracking(t); lc.paddingWhileTracking(if (mode == "car") ahead(t) else ahead(0.0))
            }
            override fun onLocationCameraTransitionCanceled(cameraMode: Int) = Unit
        })
    }
    var fix: (Location) -> Unit = {} // onFix, below: it and startNav call each other
    /** Directions on [route], from [from]: spoken, followed, the car's spot cleared for a car. */
    fun begin(target: Place, mode: String, route: Route, from: Location) {
        WriterLog.i("Maps: route by $mode to ${target.name}, ${route.metres.toInt()} m, ${route.steps.size} steps, ${route.roads}, from ${where(from)}" +
            (if (mode == "car") if (headingOf(from).isFinite()) ", keeping the heading" else ", no heading (slow or still)" else "") +
            "; first: ${route.steps.getOrNull(1)?.text ?: route.steps.firstOrNull()?.text}")
        MapsTrace.start(context, "phone", target, mode, route)
        val rerouted = nav?.target?.id == target.id
        nav = Nav(target, mode, route, cumulative(route), System.currentTimeMillis(), startOff = metresBetween(from.latitude, from.longitude, route.lats[0], route.lngs[0]))
        selected = null; preview = null
        if (!rerouted) route.steps.firstOrNull()?.let { say(it.text) }
        follow(mode)
        fix(from) // the first distance at once, not at the next fix
    }

    /** [choose]: the ways there shown to pick from (a place's Car / Walk / Bike); else the fastest at once (Home's one tap, a new route). */
    fun startNav(target: Place, mode: String, from: Location? = here, choose: Boolean = false) {
        // the car connected: by car on its screen, which the phone then shows
        if (CarLink.connected.value && mode == "car") {
            CarLink.go?.invoke(target); selected = null; preview = null; tab = null
            Toast.makeText(context, context.getString(R.string.maps_on_car_screen, target.name), Toast.LENGTH_SHORT).show(); return
        }
        // directions start from a live fix: Android's stored last one can be hours old and towns away
        if (from == null || !live) { pending = target.copy(mode = mode); return }
        val graphDir = MapsFiles.graphAt(root, from.latitude, from.longitude)
        if (!File(graphDir, "properties").isFile) {
            Toast.makeText(context, context.getString(R.string.maps_no_graph), Toast.LENGTH_LONG).show(); return
        }
        if (mode == "car" && target.id != MapsFiles.PARKING) unpark("a car trip started")
        if (target.id.isEmpty()) MapsPrefs.addRecent(context, target)
        finding = context.getString(R.string.maps_finding)
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { MapsRouting.routes(graphDir, from.latitude, from.longitude, target.lat, target.lng, profile(mode), words,
                if (mode == "car" && !choose) headingOf(from) else Double.NaN, MapsPrefs.mainRoads(context), MapsPrefs.noLeft(context)) } }
            finding = null
            r.onSuccess { routes ->
                if (choose && routes.size > 1) {
                    preview = Preview(target, mode, routes, 0, from); selected = null
                    val b = LatLngBounds.Builder()
                    routes.forEach { rt -> rt.lats.indices.forEach { i -> b.include(LatLng(rt.lats[i], rt.lngs[i])) } }
                    runCatching { mapRef?.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 120)) }
                } else begin(target, mode, routes.first(), from)
            }.onFailure {
                WriterLog.e("Maps: no route by $mode", it)
                Toast.makeText(context, it.message ?: context.getString(R.string.maps_no_way_graph), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** A saved trip from its first stop: on the car's screen when the car is connected. */
    fun startTrip(t: Trip) {
        WriterLog.i("Maps: trip ${t.name}, ${t.stops.size} stops")
        tab = null; nextStop = null
        if (CarLink.connected.value && CarLink.goTrip != null) {
            CarLink.goTrip?.invoke(t.stops); Toast.makeText(context, context.getString(R.string.maps_on_car_screen, t.name), Toast.LENGTH_SHORT).show(); return
        }
        tripRun = t to 0
        startNav(t.stops[0], "car")
    }

    /** Each fix: where on the route, what to say, and a new route when off it for three fixes in a row. */
    fun onFix(loc: Location) {
        if (loc.provider != "tetra-reckoning") { lastReal = loc; lastRealAt = android.os.SystemClock.elapsedRealtimeNanos(); reckoning = false }
        here = loc; live = true
        // at car speed (40 km/h) for three fixes: driving, so the car is no longer where it was parked
        fast = if (loc.hasSpeed() && loc.speed > 11f) fast + 1 else 0
        if (fast == 3 && nav?.mode != "foot" && nav?.mode != "bike") unpark("driving")
        mapRef?.locationComponent?.takeIf { it.isLocationComponentActivated }?.forceLocationUpdate(loc)
        road = roadAt(mapRef, loc, labelLang(context))
        speed = if (loc.hasSpeed()) Math.round(loc.speed * 3.6f) else null
        // the limit at most every two seconds, off the main thread (the graph is opened the first time)
        if (System.currentTimeMillis() - limitAsked > 2000) {
            limitAsked = System.currentTimeMillis()
            scope.launch { limit = withContext(Dispatchers.IO) { runCatching { MapsRouting.limitAt(MapsFiles.graphAt(root, loc.latitude, loc.longitude), loc.latitude, loc.longitude) }.getOrNull() } }
        }
        // a trip's next leg: once moving (15 km/h) or 150 m from the stop
        nextStop?.let { ns ->
            if ((loc.hasSpeed() && loc.speed > 4f) || stoppedAt?.let { metresBetween(it.latitude, it.longitude, loc.latitude, loc.longitude) > 150 } == true) {
                nextStop = null; MapsPrefs.setPendingTrip(context, null); startNav(ns, "car", loc)
            }
        }
        val n = nav ?: return
        val car = n.mode == "car"
        val speedMs = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
        val g = guide(n, loc.latitude, loc.longitude, speedMs, System.currentTimeMillis(), MapsPrefs.bends(context), finding != null)
        val p = g.p
        val lc = mapRef?.locationComponent?.takeIf { it.isLocationComponentActivated }
        MapsTrace.fix(loc, p.takeIf { !g.off }, lc?.let { (if (it.cameraMode == CameraMode.TRACKING_GPS) "follow" else "free") +
            "/z%.1f/t%.0f".format(java.util.Locale.ROOT, mapRef?.cameraPosition?.zoom ?: 0.0, mapRef?.cameraPosition?.tilt ?: 0.0) } ?: "none")
        // the camera let go of me: on me again 15 s after the last touch, at once when nothing touched the map (the user, 5 Nov,
        // near Ελεώνας at 86 km/h: "what touch? I was just driving" — MapLibre let go by itself, and it stayed behind to the end)
        if (lc != null && lc.cameraMode != CameraMode.TRACKING_GPS) {
            val ago = System.currentTimeMillis() - touchedAt
            if (!loose) WriterLog.i("Maps: the camera let go of me (mode ${lc.cameraMode}) at ${where(loc)}, " +
                if (ago < 15_000) "the map touched ${ago / 1000} s ago" else "the map not touched")
            loose = true
            if (ago > 15_000) { WriterLog.i("Maps: the camera on me again"); loose = false; follow(n.mode) }
        } else loose = false
        if (g.off) {
            nav = n.copy(offFixes = n.offFixes + 1)
            if (g.reroute) {
                WriterLog.i("Maps: off the route by ${p.off.toInt()} m at ${where(loc)}, ${MapsTrace.off(n, loc, MapsFiles.graphAt(root, loc.latitude, loc.longitude))}; a new one")
                startNav(n.target, n.mode, loc)
            }
            return
        }
        if (g.arrived) {
            WriterLog.i("Maps: arrived at ${where(loc)}, ${p.left.toInt()} m left on the route")
            if ("n${n.route.steps.lastIndex}" !in n.said) say(n.route.steps.lastOrNull()?.text ?: "")
            stopNav()
            // a trip's stop: the next one starts when the car moves on (the user: "When we comtinue from a stop … start automaticaly")
            tripRun?.let { (t, i) ->
                if (i < t.stops.lastIndex) {
                    tripRun = t to i + 1; nextStop = t.stops[i + 1]; stoppedAt = loc; MapsPrefs.setPendingTrip(context, t, i + 1)
                    say(words.s(R.string.maps_say_stop, Trip.letter(i), Trip.letter(i + 1), t.stops[i + 1].name))
                } else tripRun = null
            }
            if (car && MapsPrefs.autoPark(context)) park(loc, "a car trip ended")
            Toast.makeText(context, context.getString(R.string.maps_arrived, n.target.name), Toast.LENGTH_LONG).show(); return
        }
        // by car, the camera: further out at speed on a long stretch, closer towards a busy crossing or a turn slowed for
        if (car && g.view != view) {
            view = g.view
            WriterLog.i("Maps: camera zoom ${view.first} tilt ${view.second.toInt()} (step ${p.next} ${p.toNext.toInt()} m ahead, ${Math.round(speedMs * 3.6)} km/h)")
            lc?.takeIf { it.cameraMode == CameraMode.TRACKING_GPS }?.let { it.zoomWhileTracking(view.first, 1500); it.tiltWhileTracking(view.second, 1500); it.paddingWhileTracking(ahead(view.second), 1500) }
        }
        g.words(words, car)?.let { say(it, why = g.why()) }
        g.bend?.let { b -> say(MapsPrefs.bendWords(words, n.bends[b].right), why = "bend ${(n.bends[b].along - p.along).toInt()} m ahead, ${Math.round(speedMs * 3.6)} km/h") }
        nav = n.copy(index = g.index, p = p, offFixes = 0, said = g.said(n.said))
    }

    fix = ::onFix
    DisposableEffect(granted) {
        val lm = context.getSystemService(LocationManager::class.java)
        val listener = LocationListener { loc ->
            // a network fix only until GPS has one, and never over a fresher GPS one
            val h = here
            if (loc.provider == LocationManager.NETWORK_PROVIDER && h != null && h.provider == LocationManager.GPS_PROVIDER && loc.time - h.time < 10_000) return@LocationListener
            // in directions, a cell tower's rough fix does not stand for the GPS for two minutes after its last: in a tunnel
            // the arrow goes on along the route instead of jumping to it
            if (loc.provider == LocationManager.NETWORK_PROVIDER && nav != null && lastReal?.provider == LocationManager.GPS_PROVIDER &&
                android.os.SystemClock.elapsedRealtimeNanos() - lastRealAt < 120_000_000_000L) return@LocationListener
            onFix(loc)
        }
        if (granted && lm != null) {
            runCatching {
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { lm.isProviderEnabled(it) }.forEach { pr ->
                    lm.getLastKnownLocation(pr)?.let { if (here == null || it.time > here!!.time) here = it }
                    lm.requestLocationUpdates(pr, 1000L, 0f, listener, context.mainLooper)
                }
            }.onFailure { WriterLog.e("Maps: location updates", it) }
        }
        onDispose { lm?.removeUpdates(listener) }
    }
    // the next open after parking: on foot to the car (Settings › Maps), unless already by it
    LaunchedEffect(Unit) {
        if (go == null && MapsPrefs.openAtCar(context)) places.firstOrNull { it.id == MapsFiles.PARKING }?.let { pending = it.copy(mode = "foot") }
    }
    LaunchedEffect(pending, here, live) {
        val p = pending ?: return@LaunchedEffect
        val h = here ?: return@LaunchedEffect
        if (!live) return@LaunchedEffect
        pending = null
        if (p.id == MapsFiles.PARKING && go == null && metresBetween(h.latitude, h.longitude, p.lat, p.lng) < 80) return@LaunchedEffect
        startNav(p, p.mode)
    }

    LaunchedEffect(Unit) {
        all = withContext(Dispatchers.IO) { runCatching { MapsSearch.load(root) }.onFailure { WriterLog.e("Maps: search list", it) }.getOrDefault(emptyList()) }
        listLoading = false
    }
    // the user's own accessible places count as disabled parking (OSM knows few: none in Xanthi on 21 Oct)
    fun mine(kind: String?) = if (kind == "disabled_parking") places.filter { it.icon == "accessible" }.map { Poi(kind, it.lat, it.lng, it.name, more = context.getString(R.string.maps_saved_mine)) } else emptyList()
    LaunchedEffect(query, pill, center, all, places, near) {
        val c = near?.let { LatLng(it.lat, it.lng) } ?: center
        results = withContext(Dispatchers.Default) { (mine(pill) + MapsSearch.search(all, query, c.latitude, c.longitude, pill, nearby = near != null)).let { r ->
            if (pill == "disabled_parking") r.sortedBy { metresBetween(c.latitude, c.longitude, it.lat, it.lng) } else r } }
    }
    LaunchedEffect(tab) { if (tab != MapsTab.Search) near = null }
    LaunchedEffect(selected, all) {
        val s = selected ?: run { address = null; return@LaunchedEffect }
        address = null
        // a spot tapped on a road: that road is its street where no house near has a number
        val tapped = tapRoad?.takeIf { s.id.isEmpty() && s.name == it }
        address = withContext(Dispatchers.IO) { runCatching { MapsAddresses.describe(root, all, s.lat, s.lng, tapped) }.getOrNull() }
        // a dropped pin is named by its address
        if (s.id.isEmpty() && detail == null && address != null && selected == s && (s.name == droppedPin || s.name == road?.first)) selected = s.copy(name = address!!.substringBefore(","))
    }
    LaunchedEffect(navQuery, center, all) {
        navResults = withContext(Dispatchers.Default) { MapsSearch.search(all, navQuery, center.latitude, center.longitude) }
    }

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(Bundle()) }
    }
    DisposableEffect(mapView, lifecycle) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> mapView.onStart()
            Lifecycle.Event.ON_RESUME -> mapView.onResume()
            Lifecycle.Event.ON_PAUSE -> mapView.onPause()
            Lifecycle.Event.ON_STOP -> mapView.onStop()
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); mapView.onStop(); mapView.onDestroy() }
    }
    LaunchedEffect(mapView) {
        if (!mapFile.isFile) return@LaunchedEffect
        val json = mapStyle(context, root)
        val bounds = withContext(Dispatchers.IO) { cameraBounds(root) }
        mapView.getMapAsync { map ->
            map.setLatLngBoundsForCameraTarget(bounds)
            map.setMinZoomPreference(5.0); map.setMaxZoomPreference(19.0)
            map.cameraPosition = (go ?: here?.let { Place("", "", it.latitude, it.longitude) })
                ?.let { CameraPosition.Builder().target(LatLng(it.lat, it.lng)).zoom(14.0).build() }
                ?: CameraPosition.Builder().target(LatLng(38.4, 23.8)).zoom(5.6).build()
            map.setStyle(Style.Builder().fromJson(json)) { style ->
                style.addSource(GeoJsonSource("alts"))
                style.addLayer(LineLayer("alts-line", "alts").withProperties(PropertyFactory.lineColor("#9E9E9E"), PropertyFactory.lineWidth(6f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND), PropertyFactory.lineOpacity(0.8f)))
                style.addSource(GeoJsonSource("tolls"))
                addRouteLayer(style)
                // places put into the map's own data by hand (added-places.tsv): a small dot and the name, as the map's own
                val dark = MapsPrefs.mapTheme(context) == "dark"
                addMarkers(context, style)
                style.addSource(GeoJsonSource("added"))
                style.addLayer(SymbolLayer("added-dot", "added").withProperties(PropertyFactory.iconImage(Expression.get("icon")),
                    PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconSize(0.8f)))
                style.addLayer(SymbolLayer("added-name", "added").withProperties(PropertyFactory.textField("{name}"), PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                    PropertyFactory.textSize(12f), PropertyFactory.textOffset(arrayOf(0f, 1.1f)), PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                    PropertyFactory.textColor(if (dark) "#E0E0E0" else "#4A3A70"), PropertyFactory.textHaloColor(if (dark) "#000000" else "#FFFFFF"),
                    PropertyFactory.textHaloWidth(1.2f), PropertyFactory.textOptional(true)))
                style.addLayer(CircleLayer("tolls-dot", "tolls").withProperties(PropertyFactory.circleRadius(9f), PropertyFactory.circleColor("#F9A825"),
                    PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2f)))
                style.addLayer(SymbolLayer("tolls-sign", "tolls").withProperties(PropertyFactory.textField("€"), PropertyFactory.textFont(arrayOf("Noto Sans Medium")),
                    PropertyFactory.textSize(12f), PropertyFactory.textColor("#3E2723"), PropertyFactory.textAllowOverlap(true)))
                // a trip's stops, lettered (being planned, or being driven)
                style.addSource(GeoJsonSource("trip"))
                style.addLayer(CircleLayer("trip-dot", "trip").withProperties(PropertyFactory.circleRadius(13f), PropertyFactory.circleColor("#E65100"),
                    PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2f)))
                style.addLayer(SymbolLayer("trip-letter", "trip").withProperties(PropertyFactory.textField("{letter}"), PropertyFactory.textFont(arrayOf("Noto Sans Medium")),
                    PropertyFactory.textSize(14f), PropertyFactory.textColor("#FFFFFF"), PropertyFactory.textAllowOverlap(true)))
                style.addSource(GeoJsonSource("places"))
                style.addLayer(SymbolLayer("places-dot", "places").withProperties(PropertyFactory.iconImage(Expression.get("icon")),
                    PropertyFactory.iconAllowOverlap(true)))
                style.addLayer(SymbolLayer("places-name", "places").withProperties(PropertyFactory.textField("{name}"),
                    PropertyFactory.textFont(arrayOf("Noto Sans Medium")), PropertyFactory.textSize(13f), PropertyFactory.textOffset(arrayOf(0f, 1.3f)),
                    PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP), PropertyFactory.textColor("#FFFFFF"), PropertyFactory.textHaloColor("#1A237E"),
                    PropertyFactory.textHaloWidth(1.5f), PropertyFactory.textAllowOverlap(true)))
                style.addSource(GeoJsonSource("results"))
                style.addLayer(SymbolLayer("results-dot", "results").withProperties(PropertyFactory.iconImage(Expression.get("icon")),
                    PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconSize(0.9f)))
                map.addOnCameraIdleListener { center = map.cameraPosition.target ?: center }
                map.addOnMapLongClickListener { at ->
                    val c = placing
                    if (c != null) {
                        placing = null
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching {
                                MapsShare.add(root, Poi("disabled_parking", at.latitude, at.longitude, context.getString(R.string.maps_amea_parking), more = c.address, web = c.link, note = "${c.municipality} (${c.date})"))
                                MapsCheck.done(root, c.id)
                            } }
                            reloadAdded(); checks = withContext(Dispatchers.IO) { MapsCheck.load(root) }
                        }
                        Toast.makeText(context, context.getString(R.string.maps_amea_placed, c.address), Toast.LENGTH_SHORT).show()
                    } else if (nav == null) editing = Place("", "", at.latitude, at.longitude)
                    true
                }
                // a tap: my place, a search result, a place of the map's own, or a dropped pin (the user: "how do I start
                // navigation if cllicking does not add a mark on the read?")
                map.addOnMapClickListener { at ->
                    if (nav != null) return@addOnMapClickListener false
                    val pt = map.projection.toScreenLocation(at)
                    val box = android.graphics.RectF(pt.x - 24, pt.y - 24, pt.x + 24, pt.y + 24)
                    val mineHit = map.queryRenderedFeatures(box, "places-dot").firstOrNull()?.getStringProperty("id")?.let { id -> places.firstOrNull { it.id == id } }
                    val resultHit = map.queryRenderedFeatures(box, "results-dot").firstOrNull()?.let { f -> (f.geometry() as? Point)?.let { g ->
                        (if (tab == MapsTab.Search) results else onMap?.second.orEmpty()).minByOrNull { metresBetween(g.latitude(), g.longitude(), it.lat, it.lng) } } }
                    val addedHit = map.queryRenderedFeatures(box, "added-dot").firstOrNull()?.let { f -> (f.geometry() as? Point)?.let { g ->
                        added.minByOrNull { metresBetween(g.latitude(), g.longitude(), it.lat, it.lng) } } }
                    // every map's places: the first map's layer is "pois", the others' "pois__<id>" (mapStyle)
                    val poiLayers = map.style?.layers.orEmpty().map { it.id }.filter { it == "pois" || it.startsWith("pois__") }.toTypedArray()
                    val mapHit = map.queryRenderedFeatures(box, *poiLayers).firstOrNull { it.geometry() is Point && it.hasProperty("name") }
                    when {
                        mineHit != null -> { selected = mineHit; detail = MapsSearch.near(all, mineHit.lat, mineHit.lng, mineHit.name, 25.0) }
                        resultHit != null -> { detail = resultHit; selected = resultHit.place(context) }
                        addedHit != null -> { detail = addedHit; selected = addedHit.place(context) }
                        mapHit != null -> {
                            val g = mapHit.geometry() as Point
                            val name = mapHit.getStringProperty("name").orEmpty()
                            val p = MapsSearch.near(all, g.latitude(), g.longitude(), name)
                                ?: Poi(mapHit.getStringProperty("kind").orEmpty(), g.latitude(), g.longitude(), name)
                            detail = p; selected = p.place(context)
                        }
                        else -> {
                            val r = roadAtPoint(map, pt, labelLang(context))
                            tapRoad = r?.first?.ifEmpty { null }
                            detail = null
                            selected = Place("", r?.first?.ifEmpty { null } ?: droppedPin, at.latitude, at.longitude)
                        }
                    }
                    tab = null
                    true
                }
                map.addOnCameraMoveStartedListener { why -> if (why == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) touchedAt = System.currentTimeMillis() }
                mapRef = map
            }
        }
    }
    // the location dot, once the map and the permission are there
    LaunchedEffect(mapRef, granted) {
        val map = mapRef ?: return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect
        if (!granted || map.locationComponent.isLocationComponentActivated) return@LaunchedEffect
        activateArrow(context, map, style)
        map.locationComponent.isLocationComponentEnabled = true
        map.locationComponent.renderMode = RenderMode.COMPASS
        here?.let { map.locationComponent.forceLocationUpdate(it) }
    }
    // the added ones, but not where one of my places stands on it (the five work schools are both); again when one is added
    LaunchedEffect(mapRef, places, added) {
        (mapRef?.style?.getSource("added") as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(added
            .filter { a -> places.none { metresBetween(it.lat, it.lng, a.lat, a.lng) < 30 } }
            .map { a -> Feature.fromGeometry(Point.fromLngLat(a.lng, a.lat)).apply { addStringProperty("name", a.title(context)); addStringProperty("icon", markerOf(a.kind)) } }))
    }
    LaunchedEffect(mapRef, plan, tripRun) {
        val stops = plan ?: tripRun?.first?.stops.orEmpty()
        (mapRef?.style?.getSource("trip") as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(stops.mapIndexed { i, p ->
            Feature.fromGeometry(Point.fromLngLat(p.lng, p.lat)).apply { addStringProperty("letter", Trip.letter(i)) }
        }))
    }
    LaunchedEffect(mapRef, places) {
        (mapRef?.style?.getSource("places") as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(places.map { p ->
            Feature.fromGeometry(Point.fromLngLat(p.lng, p.lat)).apply { addStringProperty("id", p.id); addStringProperty("name", p.name); addStringProperty("icon", "e-${p.icon}") }
        }))
    }
    LaunchedEffect(mapRef, results, tab, selected) {
        val shown = if (tab == MapsTab.Search) results else onMap?.second.orEmpty()
        val pin = selected?.takeIf { s -> s.id.isEmpty() }
        (mapRef?.style?.getSource("results") as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(
            shown.map { Feature.fromGeometry(Point.fromLngLat(it.lng, it.lat)).apply { addStringProperty("icon", markerOf(it.kind)) } } +
            listOfNotNull(pin?.let { Feature.fromGeometry(Point.fromLngLat(it.lng, it.lat)).apply { addStringProperty("icon", markerOf(detail?.kind.orEmpty())) } })))
    }
    // the tolls on the route being driven or chosen, dots on the map
    val navTolls = remember(nav?.route, all) { nav?.let { tollsOn(it.route, it.cum, all) }.orEmpty() }
    val previewTolls = remember(preview?.routes, all) { preview?.routes?.map { tollsOn(it, cumulative(it), all) }.orEmpty() }
    LaunchedEffect(mapRef, navTolls, previewTolls, preview?.chosen) {
        val shown = if (nav != null) navTolls else previewTolls.getOrNull(preview?.chosen ?: -1).orEmpty()
        (mapRef?.style?.getSource("tolls") as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(shown.map { (t, _) -> Feature.fromGeometry(Point.fromLngLat(t.lng, t.lat)) }))
    }
    LaunchedEffect(mapRef, nav?.route, carNav?.route, preview) {
        val src = mapRef?.style?.getSource("route") as? GeoJsonSource ?: return@LaunchedEffect
        fun line(r: Route) = Feature.fromGeometry(LineString.fromLngLats(r.lats.indices.map { Point.fromLngLat(r.lngs[it], r.lats[it]) }))
        (mapRef?.style?.getSource("alts") as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(
            preview?.let { p -> p.routes.filterIndexed { i, _ -> i != p.chosen }.map(::line) }.orEmpty()))
        val r = nav?.route ?: carNav?.route ?: preview?.let { it.routes[it.chosen] }
        src.setGeoJson(routeFeatures(r))
    }
    /*
     * A tunnel (the user, 21 Oct: "What if I loose GPS sygnal on a tannel"; "μπορεί για 5 λεπτά να μην έχω GPS … μπορεί να
     * χρησιμοποιεί τους αισθητήρες του κινητού … μέχρι να ξανά έρθει το GPS"): with no fix for 3 s while directions run at
     * speed, the arrow goes on along the route, a second at a time, for up to 15 minutes or the route's end. The speed is the
     * last one the GPS gave; the accelerometer tells when the car stands (in a tunnel's traffic): the arrow waits, and after
     * a stop goes on at 30 km/h (or the last speed if lower). The first real fix takes over (a new route only if it is off).
     */
    val still = remember { StillDetector() }
    DisposableEffect(nav != null) {
        val sm = context.getSystemService(android.hardware.SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_LINEAR_ACCELERATION)
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) { still.add(e.timestamp, e.values[0], e.values[1], e.values[2]) }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) = Unit
        }
        if (nav != null && sensor != null) sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm?.unregisterListener(listener) }
    }
    LaunchedEffect(nav != null) {
        var along = -1.0 // how far along the route the arrow is, while there is no fix
        var stopped = false
        var lastTick = android.os.SystemClock.elapsedRealtimeNanos()
        while (nav != null) {
            delay(1000)
            val now = android.os.SystemClock.elapsedRealtimeNanos(); val dt = (now - lastTick) / 1e9; lastTick = now
            val n = nav ?: break
            val real = lastReal ?: continue
            val quiet = now - lastRealAt
            val v = if (real.hasSpeed()) real.speed.toDouble() else 0.0
            if (quiet < 3_000_000_000L) { along = -1.0; stopped = false; reckoning = false; continue }
            if (along < 0) {
                if (v < 3) continue // it was not moving when the signal went
                along = progress(n.route, n.cum, real.latitude, real.longitude).first.along + v * quiet / 1e9
                WriterLog.i("Maps: no GPS fix for ${quiet / 1_000_000_000} s, going on along the route at ${(v * 3.6).toInt()} km/h")
            } else {
                val standing = still.still() == true
                if (standing && !stopped) WriterLog.i("Maps: no GPS, standing still")
                if (standing) stopped = true
                along += if (standing) 0.0 else dt * (if (stopped) minOf(v, 30 / 3.6) else v)
            }
            if (quiet > 15 * 60_000_000_000L || along >= n.cum.last() - 30) { reckoning = false; continue }
            val at = along.coerceAtMost(n.cum.last())
            val i = n.cum.indexOfLast { it <= at }.coerceIn(0, n.cum.size - 2)
            val t = ((at - n.cum[i]) / (n.cum[i + 1] - n.cum[i]).coerceAtLeast(0.01)).coerceIn(0.0, 1.0)
            val lat = n.route.lats[i] + t * (n.route.lats[i + 1] - n.route.lats[i]); val lng = n.route.lngs[i] + t * (n.route.lngs[i + 1] - n.route.lngs[i])
            reckoning = true
            fix(Location("tetra-reckoning").apply {
                setLatitude(lat); setLongitude(lng); setSpeed(if (still.still() == true) 0f else v.toFloat()); setTime(System.currentTimeMillis())
                setElapsedRealtimeNanos(now); setAccuracy(50f)
                setBearing(Location("").apply { setLatitude(n.route.lats[i]); setLongitude(n.route.lngs[i]) }
                    .bearingTo(Location("").apply { setLatitude(n.route.lats[i + 1]); setLongitude(n.route.lngs[i + 1]) }))
            })
        }
    }
    // the screen stays on while directions run
    DisposableEffect(nav != null) {
        mapView.keepScreenOn = nav != null
        onDispose { mapView.keepScreenOn = false }
    }
    BackHandler(nav != null || selected != null || tab != null || preview != null) {
        when { preview != null -> { selected = preview?.target; preview = null }; selected != null -> selected = null; tab != null -> tab = null; else -> stopNav() }
    }

    Box(Modifier.fillMaxSize()) {
        if (mapFile.isFile) AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        else Column(Modifier.align(Alignment.Center).padding(32.dp).widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.ic_map), contentDescription = null, tint = MAPS_COLOR, modifier = Modifier.size(48.dp))
            // Tetra comes without a map: a welcome that walks through making one on the computer (the user, 27 Oct)
            Text(stringResource(R.string.maps_welcome_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.maps_welcome_intro), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.maps_welcome_steps), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // bottom left, by car: my speed in its badge, red when over the limit by more than 3 km/h, and the limit's sign beside it
        // (the user, 3 Nov: "No speed limit or speedometer. Wrong graphics"); the sign only where the road has a number, never guessed
        val driving = (nav ?: carNav)?.mode.let { it == null || it == "car" }
        val shownSpeed = speed?.takeIf { driving && !reckoning } // not the guess made while the GPS is lost
        val badge = (nav ?: carNav)?.mode == "car" || (shownSpeed != null && limit != null) // without directions: beside a sign, as before
        if (live && (limit != null || badge)) {
            // clear of the island and the bars below it (the user, 21 Oct: "ακουμπάει πάνω στο νησί … δώσε λίγο αέρα")
            Row(Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 12.dp, bottom = if (nav != null) 150.dp else 100.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (badge) SpeedBadge(shownSpeed, limit?.let { l -> (shownSpeed ?: 0) > l + 3 } == true)
                limit?.let { LimitSign(it) }
            }
        }

        // top left: back and the road I am on, with its code (the user, 21 Oct: "To the top left no title. info about the road the code");
        // no Back while directions run (the user: "the back button remove it when in active navigation")
        val n = nav ?: carNav
        val step = n?.let { nn -> nn.route.steps.getOrNull(((nn.p?.next ?: 1) - 1).coerceAtLeast(0)) }
        val roadName = step?.street?.ifEmpty { null } ?: road?.first?.ifEmpty { null }
        val roadCode = step?.ref?.ifEmpty { null } ?: road?.second?.ifEmpty { null }
        val narrow = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600
        @Composable fun RoadPill() { if (roadName != null || roadCode != null) Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                roadCode?.let { RoadCode(it) }
                roadName?.let { Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp)) }
            }
        } }
        if (n != null && narrow) {
            // a narrow screen: one pill, the road above and the next turn below (the user, 21 Oct)
            // one card: the road I am on at its top (the user, 22 Oct: the pill and the card "seam from a diferent planet")
            NavBanner(n, root, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp).fillMaxWidth(), roadName, roadCode)
        } else {
            Row(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (n == null) Island(R.drawable.ic_chevron_left, stringResource(R.string.common_back), back)
                RoadPill()
            }
            // top right: the next turn in a small window (the user: "A small window will open at the top right to tell me what lane to take")
            if (n != null) NavBanner(n, root, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp))
        }
        // bottom right, above the island: P and where I am (the user, 21 Oct: at the top they covered the map's compass);
        // away while a panel or a place's page is open
        if (n == null && tab == null && selected == null && preview == null) Column(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 12.dp, bottom = 76.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Island(R.drawable.ic_parking, stringResource(R.string.maps_parked_here)) {
                val h = here
                if (h == null || !live) Toast.makeText(context, context.getString(if (granted) R.string.maps_waiting_location else R.string.maps_location_off), Toast.LENGTH_SHORT).show()
                else park(h, "the P button")
            }
            Island(R.drawable.ic_my_location, stringResource(R.string.maps_where_i_am)) {
                val h = here
                if (h == null) Toast.makeText(context, context.getString(if (granted) R.string.maps_waiting_location else R.string.maps_location_off), Toast.LENGTH_SHORT).show()
                else mapRef?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(h.latitude, h.longitude), 16.0))
            }
        }

        // bottom: directions running; or a place's page; or a panel and the island
        // above the keyboard while one is typing in Search or Directions
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(12.dp).widthIn(max = 560.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            finding?.let { Pill(it) }
            plan?.takeIf { it.size < planCount }?.let { pl ->
                Surface(color = islandColor(), contentColor = islandContentColor(), shape = MaterialTheme.shapes.extraLarge) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(stringResource(R.string.maps_plan_point, Trip.letter(pl.size), planCount), style = MaterialTheme.typography.bodyMedium)
                        if (pl.isNotEmpty()) Text(pl.withIndex().joinToString(" → ") { (i, p) -> "${Trip.letter(i)} ${p.name}" }, style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row {
                            if (pl.isNotEmpty()) TextButton(onClick = { plan = pl.dropLast(1) }) { Text(stringResource(R.string.common_undo), color = androidx.compose.ui.graphics.Color(0xFF9FA8DA), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            if (pl.size >= 2) TextButton(onClick = { planCount = pl.size; naming = true }) { Text(stringResource(R.string.common_done), color = androidx.compose.ui.graphics.Color(0xFF9FA8DA), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            TextButton(onClick = { plan = null }) { Text(stringResource(R.string.common_cancel), color = androidx.compose.ui.graphics.Color(0xFF9FA8DA), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
            nextStop?.takeIf { nav == null }?.let { ns ->
                Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                    Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.maps_next_stop, tripRun?.let { Trip.letter(it.second) }.orEmpty(), ns.name), style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        TextButton(onClick = { nextStop = null; MapsPrefs.setPendingTrip(context, null); startNav(ns, "car") }) { Text(stringResource(R.string.maps_go), color = androidx.compose.ui.graphics.Color(0xFF9FA8DA), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        IconButton(onClick = { nextStop = null; tripRun = null; MapsPrefs.setPendingTrip(context, null) }) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.maps_end_trip), modifier = Modifier.size(18.dp)) }
                    }
                }
            }
            askAmea?.let { (loc, at) ->
                LaunchedEffect(at) { delay(20_000); if (askAmea?.second == at) askAmea = null }
                val known = added.any { it.kind == "disabled_parking" && metresBetween(it.lat, it.lng, loc.latitude, loc.longitude) < 8 }
                if (!known) Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                    Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.maps_amea_ask), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = {
                            askAmea = null
                            scope.launch { withContext(Dispatchers.IO) { runCatching { MapsShare.add(root, Poi("disabled_parking", loc.latitude, loc.longitude, "")) } }; reloadAdded() }
                            Toast.makeText(context, context.getString(R.string.maps_amea_added), Toast.LENGTH_SHORT).show()
                        }) { Text(stringResource(R.string.maps_add_it), color = androidx.compose.ui.graphics.Color(0xFF9FA8DA), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        IconButton(onClick = { askAmea = null }) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_no), modifier = Modifier.size(18.dp)) }
                    }
                }
            }
            // what Search's OK left on the map, and its ✕
            onMap?.takeIf { nav == null && tab == null && selected == null }?.let { (name, found) ->
                Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape, modifier = Modifier.clickable { onMap = null }) {
                    Row(Modifier.padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("$name · ${found.size}", style = MaterialTheme.typography.bodyMedium)
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_clear), modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (reckoning && nav != null) Pill(stringResource(R.string.maps_no_gps_reckoning))
            if (pending != null && nav == null && finding == null) Pill(stringResource(if (granted) R.string.maps_waiting_location else R.string.maps_location_off_directions))
            val s = selected
            fun away(lat: Double, lng: Double) = here?.let { metresBetween(it.latitude, it.longitude, lat, lng) }
            if (n != null) Surface(color = islandColor(), contentColor = islandContentColor(), shape = MaterialTheme.shapes.extraLarge) {
              Column {
                // the handle, as Tetra's others: drag up (or tap) for the tolls and the turns ahead
                var drag by remember { mutableStateOf(0f) }
                Box(Modifier.fillMaxWidth().clickable { navSheet = !navSheet }.pointerInput(Unit) {
                    detectVerticalDragGestures(onDragStart = { drag = 0f }, onVerticalDrag = { _, d -> drag += d }, onDragEnd = { if (drag < -30) navSheet = true else if (drag > 30) navSheet = false })
                }.padding(top = 8.dp, bottom = 2.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(islandContentColor().copy(alpha = 0.5f)))
                }
                if (navSheet) NavSheet(n, navTolls)
                Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val left = n.p?.left ?: n.route.metres
                        val minutes = Math.round(n.route.millis / 60_000.0 * (left / n.route.metres.coerceAtLeast(1.0)))
                        Text("${metresText(left)} · ${minutesText(context, minutes)}", style = MaterialTheme.typography.titleMedium)
                        Text(if (nav == null) stringResource(R.string.maps_on_car_screen_bar, n.target.name) else n.target.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (nav == null) Button(colors = panelButton(), onClick = { CarLink.stop?.invoke() }) { Text(stringResource(R.string.common_stop), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    else {
                    IconButton(onClick = { follow(n.mode) }) {
                        Icon(painterResource(R.drawable.ic_navigation), contentDescription = stringResource(R.string.maps_follow_me))
                    }
                    IconButton(onClick = { muted = !muted; if (muted) tts.value?.stop() }) {
                        Icon(painterResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up), contentDescription = stringResource(if (muted) R.string.maps_voice_on else R.string.maps_voice_off))
                    }
                    Button(colors = panelButton(), onClick = {
                        stopNav(); tripRun = null; nextStop = null
                        if (n.mode == "car" && MapsPrefs.autoPark(context)) here?.let { park(it, "a car trip stopped") }
                    }) { Text(stringResource(R.string.common_stop), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
              }
            } else if (placing != null) {
                val c = placing!!
                Surface(color = islandColor(), contentColor = islandContentColor(), shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("♿ ${c.address.ifEmpty { c.municipality }}", style = MaterialTheme.typography.titleMedium)
                        Text(c.subject, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis,
                            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f))
                        Text(stringResource(R.string.maps_amea_long_press), style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(c.link))) } }) {
                                Text(stringResource(R.string.maps_decision), color = androidx.compose.ui.graphics.Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            OutlinedButton(onClick = {
                                // not to be found: off the list on every device
                                placing = null
                                scope.launch { withContext(Dispatchers.IO) { runCatching { MapsCheck.done(root, c.id) } }; checks = withContext(Dispatchers.IO) { MapsCheck.load(root) } }
                            }) { Text(stringResource(R.string.maps_not_there), color = androidx.compose.ui.graphics.Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            OutlinedButton(onClick = { placing = null }) { Text(stringResource(R.string.maps_later), color = androidx.compose.ui.graphics.Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            } else if (preview != null) {
                val pv = preview!!
                RoutesPanel(pv.routes, previewTolls.map { it.size }, pv.chosen, { i -> preview = pv.copy(chosen = i) }, { preview = null; selected = pv.target },
                    { begin(pv.target, pv.mode, pv.routes[pv.chosen], here ?: pv.from) })
            } else if (s != null) {
                val saved = s.id.isNotEmpty() && places.any { it.id == s.id }
                plan?.let { pl ->
                    Button(onClick = {
                        val next = pl + s.copy(id = "", name = s.name.takeIf { it != droppedPin } ?: address?.substringBefore(",") ?: context.getString(R.string.maps_stop_letter, Trip.letter(pl.size)))
                        plan = next; selected = null; detail = null
                        if (next.size >= planCount) naming = true
                    }, modifier = Modifier.fillMaxWidth(), colors = panelButton()) { Text(stringResource(R.string.maps_add_stop, Trip.letter(pl.size), s.name), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                PlaceCard(s, detail, address, saved, away(s.lat, s.lng), rolling, lists, go = { m -> startNav(s, m, choose = true) },
                    save = { editing = s.copy(name = if (s.name == droppedPin) "" else s.name) },
                    edit = { editing = s }, delete = { deleting = s }, close = { selected = null; detail = null },
                    remove = detail?.takeIf { d -> added.any { it.kind == d.kind && metresBetween(it.lat, it.lng, d.lat, d.lng) < 2 } }?.let { d -> {
                        selected = null; detail = null
                        scope.launch { withContext(Dispatchers.IO) { runCatching { MapsShare.remove(root, d) } }; reloadAdded() }
                    } }) {
                    // what is around it, in Search: nearest first, the pills to narrow it
                    near = s; query = ""; pill = null; selected = null; detail = null; tab = MapsTab.Search
                    mapRef?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(s.lat, s.lng), 15.0))
                }
            } else if (mapFile.isFile) {
                when (tab) {
                    // Nearby: how far from the place it is around, not from me
                    MapsTab.Search -> SearchPanel(query, { query = it }, pill, { pill = it }, results,
                        { p -> near?.let { n -> metresBetween(n.lat, n.lng, p.lat, p.lng) } ?: away(p.lat, p.lng) }, all.isNotEmpty() || listLoading,
                        near?.name, { near = null }, listLoading, ok = {
                            // what is on the screen and a little round it (half a screen each way), else the nearest found
                            val map = mapRef
                            val b = map?.projection?.visibleRegion?.latLngBounds
                            val c = near?.let { LatLng(it.lat, it.lng) } ?: center
                            scope.launch {
                                val found = withContext(Dispatchers.Default) {
                                    val many = (mine(pill) + MapsSearch.search(all, query, c.latitude, c.longitude, pill, limit = 2000, nearby = near != null))
                                    val inView = if (b == null) many else {
                                        val dLat = (b.latitudeNorth - b.latitudeSouth) / 2; val dLng = (b.longitudeEast - b.longitudeWest) / 2
                                        many.filter { it.lat in (b.latitudeSouth - dLat)..(b.latitudeNorth + dLat) && it.lng in (b.longitudeWest - dLng)..(b.longitudeEast + dLng) }
                                    }
                                    inView.ifEmpty { many.take(20) }.take(300)
                                }
                                val name = pill?.let { k -> MapsSearch.PILLS.firstOrNull { it.key == k }?.let { "${it.emoji} ${context.getString(it.label)}" } } ?: query.trim()
                                onMap = name to found
                                tab = null
                                WriterLog.i("Maps: OK on \"$name\", ${found.size} on the map")
                                if (found.isNotEmpty() && (b == null || found.none { b.contains(LatLng(it.lat, it.lng)) })) {
                                    val bb = LatLngBounds.Builder(); found.forEach { bb.include(LatLng(it.lat, it.lng)) }
                                    if (found.size > 1) runCatching { mapRef?.animateCamera(CameraUpdateFactory.newLatLngBounds(bb.build(), 120)) }
                                    else mapRef?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(found[0].lat, found[0].lng), 15.0))
                                }
                            }
                        }) { p ->
                        detail = p; selected = p.place(context); tab = null
                        mapRef?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lng), if (MapsSearch.settlement(p.kind)) 13.0 else 16.5))
                    }
                    MapsTab.Favorites -> FavoritesPanel(places, lists, remember(all, here) { all.filter { it.kind == "eopyy" }.sortedBy { p -> away(p.lat, p.lng) ?: 0.0 } },
                        shownList, { shownList = it }, { listEditing = PlaceList("", "", "❤️") }, { listEditing = it }, { away(it.lat, it.lng) },
                        open = { p ->
                            selected = p; detail = MapsSearch.near(all, p.lat, p.lng, p.name, 25.0); tab = null
                            mapRef?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lng), 16.0))
                        },
                        checks = remember(checks, here) { checks.sortedBy { c -> away(c.lat, c.lng) ?: 0.0 } },
                        openCheck = { c ->
                            placing = c; tab = null; selected = null
                            mapRef?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(c.lat, c.lng), 17.0))
                        }) { p ->
                        detail = p; selected = p.place(context); tab = null
                        mapRef?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lng), 16.5))
                    }
                    MapsTab.Navigate -> NavigatePanel(navMode, { navMode = it }, rolling, navQuery, { navQuery = it }, navResults,
                        MapsFiles.slots(places).filterNotNull() + places.filter { it.id == MapsFiles.PARKING }, { away(it.lat, it.lng) },
                        { p -> tab = null; navQuery = ""; startNav(p.place(context), navMode) }, { p -> tab = null; startNav(p, navMode) },
                        trips, plan = { askCount = true }, startTrip = ::startTrip,
                        deleteTrip = { t -> scope.launch { withContext(Dispatchers.IO) { MapsFiles.deleteTrip(root, t) }; trips = withContext(Dispatchers.IO) { MapsFiles.trips(root) } } })
                    null -> Unit
                }
                // ♪: lit while the Browser plays behind; the station is chosen in the Browser's Player (🚗)
                var music by remember { mutableStateOf(BrowserMedia.playing && BrowserMedia.service != null) }
                LaunchedEffect(Unit) { while (true) { music = BrowserMedia.playing && BrowserMedia.service != null; delay(1000) } }
                MapsIsland(tab, music, playMusic = { if (!BrowserMedia.mapsMusic(context)) Toast.makeText(context, context.getString(R.string.maps_music_none), Toast.LENGTH_LONG).show() }) { t -> tab = if (tab == t) null else t }
            }
        }
    }

    listEditing?.let { l -> ListDialog(l, { listEditing = null }, if (l.id.isEmpty()) null else ({
        listEditing = null; if (shownList == l.id) shownList = null
        scope.launch { withContext(Dispatchers.IO) { MapsFiles.deleteList(root, l) }; lists = withContext(Dispatchers.IO) { MapsFiles.lists(root) } }
    })) { saved ->
        listEditing = null
        scope.launch { withContext(Dispatchers.IO) { runCatching { MapsFiles.saveList(root, saved) } }; lists = withContext(Dispatchers.IO) { MapsFiles.lists(root) }; shownList = saved.id }
    } }
    editing?.let { p -> PlaceDialog(p, places, lists, { editing = null }, onMap = { poi ->
        editing = null
        scope.launch { withContext(Dispatchers.IO) { runCatching { MapsShare.add(root, poi) } }; reloadAdded(); detail = poi; selected = poi.place(context) }
    }) { saved ->
        editing = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { MapsFiles.save(root, saved) } }
            r.onFailure { WriterLog.e("Maps: place not saved", it); Toast.makeText(context, context.getString(R.string.maps_place_not_saved), Toast.LENGTH_LONG).show() }
            places = withContext(Dispatchers.IO) { MapsFiles.places(root) }
            selected = r.getOrNull()
        }
    } }
    if (askCount) TripCountDialog({ askCount = false }) { n -> askCount = false; planCount = n; plan = emptyList(); tab = null; selected = null }
    if (naming) plan?.let { pl ->
        TripNameDialog(pl, { naming = false }) { name, go ->
            naming = false; plan = null
            val t = Trip(MapsFiles.newId(), name, pl)
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { MapsFiles.saveTrip(root, t) } }
                trips = withContext(Dispatchers.IO) { MapsFiles.trips(root) }
                if (go) startTrip(t)
            }
        }
    }
    // no voice on this phone for the directions' language: said once, with the way to add one
    if (noVoice) AlertDialog(onDismissRequest = { noVoice = false },
        text = { Text(stringResource(R.string.maps_no_voice, Locale.forLanguageTag(words.lang).getDisplayLanguage(context.resources.configuration.locales[0]))) },
        confirmButton = { TextButton(onClick = { noVoice = false; MapsPrefs.voiceSettings(context) }) { Text(stringResource(R.string.settings_voice_settings)) } },
        dismissButton = { TextButton(onClick = { noVoice = false }) { Text(stringResource(R.string.common_ok)) } })
    deleting?.let { p -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.maps_delete_place, p.name)) },
        text = { Text(stringResource(if (p.slot > 0) R.string.maps_delete_leaves_home else R.string.maps_delete_everywhere)) },
        confirmButton = { TextButton(onClick = {
            deleting = null; selected = null
            scope.launch { withContext(Dispatchers.IO) { MapsFiles.delete(root, p) }; places = withContext(Dispatchers.IO) { MapsFiles.places(root) } }
        }) { Text(stringResource(R.string.common_delete)) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_cancel)) } }) }
}

/** The road the map draws under [pt] (screen pixels): its name and code (A2, EO1…), from the map's own roads. */
/**
 * The map's style (Settings › Maps' light or dark) reading every map in [root]; the phone's map and the car's alike. One
 * source a map, each layer drawn for every map in turn, so two countries meet at their border as one map; the first map
 * keeps the style's own names ("protomaps", "roads_…"), which [roadAtPoint] looks for.
 */
internal fun mapStyle(context: Context, root: File): String {
    val text = labelsIn(context.assets.open("maps/${MapsPrefs.mapTheme(context)}.json").bufferedReader().use { it.readText() }, labelLang(context))
    val ids = MapsFiles.ids(root).ifEmpty { listOf(MapsFiles.MAP.removeSuffix(".pmtiles")) }
    val url = { id: String -> "pmtiles://file://${File(root, "$id.pmtiles").absolutePath}" }
    if (ids.size == 1) return text.replace("pmtiles://MAP_FILE", url(ids[0]))
    val style = org.json.JSONObject(text)
    val sources = style.getJSONObject("sources")
    val base = sources.getJSONObject("protomaps")
    sources.put("protomaps", org.json.JSONObject(base.toString()).put("url", url(ids[0])))
    for (id in ids.drop(1)) sources.put("protomaps__$id", org.json.JSONObject(base.toString()).put("url", url(id)))
    val layers = style.getJSONArray("layers"); val out = org.json.JSONArray()
    for (i in 0 until layers.length()) {
        val l = layers.getJSONObject(i)
        out.put(l)
        if (l.optString("source") == "protomaps") for (id in ids.drop(1))
            out.put(org.json.JSONObject(l.toString()).put("id", "${l.getString("id")}__$id").put("source", "protomaps__$id"))
    }
    return style.put("layers", out).toString()
}

/** The language the map's names are in: Tetra's own (Settings › Appearance › Language). */
internal fun labelLang(context: Context) = AppLocale.get(context).ifEmpty { AppLocale.systemTag() }.substringBefore('-')

/**
 * Protomaps' style, made for Greek (name:el, a name in another script shown under it), for [lang]: its name:<lang> and
 * its script, as Protomaps' own style for that language is; a place without a name in it keeps its local name.
 */
internal fun labelsIn(style: String, lang: String): String {
    if (lang == "el") return style
    val script = when (lang) { "bg", "ru", "uk", "sr", "mk" -> "Cyrillic"; else -> "Latin" }
    return style.replace("\"name:el\"", "\"name:$lang\"").replace("\"Greek\"", "\"$script\"")
}

/** The route being driven, a line with a dark edge, from the source "route". */
internal fun addRouteLayer(style: Style) {
    style.addSource(GeoJsonSource("route"))
    style.addLayer(LineLayer("route-casing", "route").withProperties(PropertyFactory.lineColor("#1A237E"), PropertyFactory.lineWidth(9f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
    style.addLayer(LineLayer("route-line", "route").withProperties(PropertyFactory.lineColor("#7986CB"), PropertyFactory.lineWidth(6f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
}

/** The route's line as the "route" source's data. */
internal fun routeFeatures(r: Route?): FeatureCollection = if (r == null) FeatureCollection.fromFeatures(emptyList()) else
    FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(r.lats.indices.map { Point.fromLngLat(r.lngs[it], r.lats[it]) })))

/** Where I am: a small arrow, so the road under it shows (the user, 21 Oct: "The arrow seams too big for the road"); fixes are ours. */
internal fun activateArrow(context: Context, map: MapLibreMap, style: Style, scale: Float = 0.6f) =
    map.locationComponent.activateLocationComponent(LocationComponentActivationOptions.builder(context, style).useDefaultLocationEngine(false)
        .locationComponentOptions(org.maplibre.android.location.LocationComponentOptions.builder(context).maxZoomIconScale(scale).minZoomIconScale(scale * 0.75f).build()).build())

/** The next turn's arrow, turned from straight ahead by a GraphHopper sign. */
internal fun turnDegrees(sign: Int) = when (sign) { 1 -> 45f; 2 -> 90f; 3 -> 135f; 7 -> 20f; -1 -> -45f; -2 -> -90f; -3 -> -135f; -7 -> -20f; 8, -8, -98 -> 180f; else -> 0f }

internal fun roadAtPoint(map: MapLibreMap, pt: android.graphics.PointF, lang: String): Pair<String, String>? {
    val ids = map.style?.layers.orEmpty().map { it.id }.filter { it.startsWith("roads_") && listOf("casing", "labels", "shields", "oneway", "rail", "runway", "taxiway", "pier").none { x -> x in it } }
    if (ids.isEmpty()) return null
    val f = map.queryRenderedFeatures(android.graphics.RectF(pt.x - 12, pt.y - 12, pt.x + 12, pt.y + 12), *ids.toTypedArray())
        .firstOrNull { it.hasProperty("name") || it.hasProperty("ref") } ?: return null
    val name = (f.getStringProperty("name:$lang")?.takeIf { it.isNotEmpty() } ?: f.getStringProperty("name")).orEmpty()
    val ref = (if (f.hasProperty("ref")) f.getStringProperty("ref") else if (f.hasProperty("shield_text")) f.getStringProperty("shield_text") else null).orEmpty()
    return (name to ref.substringBefore(';')).takeIf { it.first.isNotEmpty() || it.second.isNotEmpty() }
}

private fun roadAt(map: MapLibreMap?, loc: Location, lang: String): Pair<String, String>? =
    map?.let { m -> runCatching { roadAtPoint(m, m.projection.toScreenLocation(LatLng(loc.latitude, loc.longitude)), lang) }.getOrNull() }

/** A road's code as on its signs: motorways green, the rest blue. */
@Composable private fun RoadCode(code: String) = Surface(shape = MaterialTheme.shapes.small,
    color = if (code.startsWith("A") || code.startsWith("Α")) androidx.compose.ui.graphics.Color(0xFF1B7F3B) else androidx.compose.ui.graphics.Color(0xFF1565C0)) {
    Text(code, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.White,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
}

/** My speed as a round badge (Google Maps', Waze's): the number big, km/h under it; red with white letters when [over]. A dash before the GPS gives one. */
@Composable private fun SpeedBadge(kmh: Int?, over: Boolean) = Surface(shape = CircleShape, shadowElevation = 6.dp, modifier = Modifier.size(64.dp),
    color = if (over) androidx.compose.ui.graphics.Color(0xFFD32F2F) else androidx.compose.ui.graphics.Color.White,
    contentColor = if (over) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color(0xFF202124)) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(kmh?.toString() ?: "–", fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 24.sp)
        Text("km/h", fontSize = 11.sp, lineHeight = 11.sp)
    }
}

/** The road's limit as the sign itself: a white disc in a red ring, the number in black (three digits a size smaller). */
@Composable private fun LimitSign(kmh: Int) = Surface(shape = CircleShape, shadowElevation = 6.dp, color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(64.dp)) {
    Box(Modifier.padding(2.dp).border(7.dp, androidx.compose.ui.graphics.Color(0xFFD32F2F), CircleShape), contentAlignment = Alignment.Center) {
        Text("$kmh", fontWeight = FontWeight.Bold, fontSize = if (kmh >= 100) 20.sp else 24.sp, color = androidx.compose.ui.graphics.Color.Black)
    }
}

@Composable private fun Island(icon: Int, label: String, click: () -> Unit) = Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
    IconButton(onClick = click) { Icon(painterResource(icon), contentDescription = label) }
}

@Composable private fun Pill(text: String) = Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
}

/** Under the handle: the tolls still ahead (name, operator and price where OSM has them, how far) and the next turns. */
@Composable private fun NavSheet(n: Nav, tolls: List<Pair<Poi, Double>>) {
    val context = LocalContext.current
    val along = n.p?.along ?: 0.0
    val ahead = tolls.filter { it.second > along - 20 }
    LazyColumn(Modifier.heightIn(max = 320.dp).padding(horizontal = 16.dp)) {
        item { Text(if (ahead.isEmpty()) stringResource(R.string.maps_no_tolls_ahead) else stringResource(R.string.maps_tolls_ahead, ahead.size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 6.dp)) }
        items(ahead) { (t, at) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("💶")
                Column(Modifier.weight(1f)) {
                    Text(t.title(context), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (t.more.isNotEmpty()) Text(t.more, style = MaterialTheme.typography.bodySmall, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f))
                }
                Text(metresText(at - along), style = MaterialTheme.typography.labelMedium)
            }
        }
        item { Text(stringResource(R.string.maps_next_turns), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) }
        val cum = n.cum
        items(n.route.steps.drop(n.p?.next ?: 0).take(8)) { s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(s.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(metresText((cum[s.at.coerceAtMost(cum.lastIndex)] - along).coerceAtLeast(0.0)), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** The next turn: its arrow, how far, and GraphHopper's words for it. */
/**
 * The next turn, in the car's card style (MapsCar's turn card): the arrow and the distance big, the street, the instruction
 * smaller; [road] and [code], the road I am on, as a small line at its top.
 */
@Composable private fun NavBanner(n: Nav, root: File, modifier: Modifier, road: String? = null, code: String? = null) {
    val step = n.route.steps.getOrNull(n.p?.next ?: 0) ?: return
    val white = androidx.compose.ui.graphics.Color.White
    Surface(color = androidx.compose.ui.graphics.Color(0xF0303134), contentColor = white, shape = MaterialTheme.shapes.extraLarge, modifier = modifier.widthIn(max = 560.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (road != null || code != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                code?.let { RoadCode(it) }
                road?.let { Text(it, style = MaterialTheme.typography.titleLarge, color = white.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(if (step.sign == 4) R.drawable.ic_place else R.drawable.ic_navigation), contentDescription = null,
                    modifier = Modifier.size(44.dp).rotate(if (step.sign == 4) 0f else turnDegrees(step.sign)))
                Text(n.p?.let { metresText(it.toNext) } ?: "", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            val instruction = step.text.substringBefore(" στην ").substringBefore(" onto ").substringBefore(" στο ")
            if (step.street.isNotEmpty()) Text(step.street, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (step.street.isEmpty()) step.text else instruction, style = MaterialTheme.typography.bodyMedium, color = white.copy(alpha = 0.75f),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            // the lanes before the turn, the ones to take white
            val lanes = step.lanes?.takeIf { showLanes(it, n.p?.toNext ?: Double.MAX_VALUE) }
            if (lanes != null) {
                val strip = remember(lanes) { lanesPicture(lanes, 120).asImageBitmap() }
                androidx.compose.foundation.Image(strip, contentDescription = null, modifier = Modifier.padding(top = 4.dp).height(40.dp).aspectRatio(strip.width.toFloat() / strip.height).align(Alignment.CenterHorizontally))
            }
            // a busy crossing near: its picture, the way on in blue (the user, 23 Oct)
            val j = step.junction?.takeIf { showJunction(it, n.p?.toNext ?: Double.MAX_VALUE) }
            if (j != null) {
                val picture = remember(j) { junctionPicture(j, 480, MapsFiles.driveLeftAt(root, n.route, step.at)).asImageBitmap() }
                androidx.compose.foundation.Image(picture, contentDescription = null, modifier = Modifier.padding(top = 4.dp).size(120.dp).align(Alignment.CenterHorizontally))
            }
        }
    }
}

/** A place's name, icon, Home slot and how its Home tap goes; a new one (no id) gets its id here. */
@Composable private fun PlaceDialog(p: Place, all: List<Place>, lists: List<PlaceList>, dismiss: () -> Unit, onMap: ((Poi) -> Unit)? = null, save: (Place) -> Unit) {
    // a new one: mine, or on the map for every pill and search, with its kind's symbol (the user, 21 Oct)
    var toMap by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf("disabled_parking") }
    var note by remember { mutableStateOf("") }
    if (toMap && onMap != null) {
        var mapName by remember { mutableStateOf(p.name) }
        AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.maps_add_to_map)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(false, { toMap = false }, label = { Text(stringResource(R.string.maps_my_place), maxLines = 1, overflow = TextOverflow.Ellipsis) }); FilterChip(true, {}, label = { Text(stringResource(R.string.maps_on_the_map), maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
                OutlinedTextField(mapName, { mapName = it }, label = { Text(stringResource(R.string.maps_name_if_any)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.maps_address_or_note)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.maps_what_it_is), style = MaterialTheme.typography.labelLarge)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MapsSearch.PILLS.filter { it.key != "eopyy" }.forEach { k -> FilterChip(kind == k.key, { kind = k.key }, label = { Text("${k.emoji} ${stringResource(k.label)}", maxLines = 1, overflow = TextOverflow.Ellipsis) }) }
                }
                Text(stringResource(R.string.maps_added_where), style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(onClick = { onMap(Poi(kind, p.lat, p.lng, mapName.trim(), more = note.trim())) }) { Text(stringResource(R.string.common_add)) } },
            dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } })
        return
    }
    var inLists by remember { mutableStateOf(p.lists.toSet()) }
    var label by remember { mutableStateOf(p.label) }
    var name by remember { mutableStateOf(p.name) }
    var icon by remember { mutableStateOf(p.icon) }
    var slot by remember { mutableStateOf(p.slot) }
    var mode by remember { mutableStateOf(p.mode) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(if (p.id.isEmpty()) R.string.maps_save_place else R.string.maps_place)) },
        text = { Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { // a phone shows half of it (the user, 23 Oct: "it needs scroll")
            if (p.id.isEmpty() && onMap != null) {
                // one tap: an ΑμεΑ spot here, on the map
                Button(onClick = { onMap(Poi("disabled_parking", p.lat, p.lng, "")) }, colors = panelButton(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.maps_amea_here)) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(true, {}, label = { Text(stringResource(R.string.maps_my_place), maxLines = 1, overflow = TextOverflow.Ellipsis) }); FilterChip(false, { toMap = true }, label = { Text(stringResource(R.string.maps_on_the_map), maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
            }
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.common_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            var tab by remember { mutableStateOf(Place.category(p.icon)) }
            androidx.compose.material3.SecondaryScrollableTabRow(Place.CATEGORIES.keys.indexOf(tab), edgePadding = 0.dp, containerColor = androidx.compose.ui.graphics.Color.Transparent) {
                Place.CATEGORIES.keys.forEach { c -> androidx.compose.material3.Tab(tab == c, { tab = c }, text = { Text(stringResource(Place.categoryLabel(c)), maxLines = 1, overflow = TextOverflow.Ellipsis) }) }
            }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Place.CATEGORIES.getValue(tab).forEach { i -> FilterChip(icon == i, { icon = i }, label = { Icon(painterResource(placeIcon(i)), contentDescription = i, modifier = Modifier.size(18.dp)) }) }
            }
            Text(stringResource(R.string.maps_on_home_card), style = MaterialTheme.typography.labelLarge)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(slot == 0, { slot = 0 }, label = { Text(stringResource(R.string.common_no), maxLines = 1, overflow = TextOverflow.Ellipsis) })
                (1..MapsFiles.SLOTS).forEach { n ->
                    val taken = all.firstOrNull { it.slot == n && it.id != p.id }
                    FilterChip(slot == n, { slot = n }, label = { Text(if (taken != null && slot != n) "$n·" else "$n") })
                }
            }
            all.firstOrNull { it.slot == slot && slot > 0 && it.id != p.id }?.let { Text(stringResource(R.string.maps_takes_slot, slot, it.short), style = MaterialTheme.typography.bodySmall) }
            if (slot > 0) OutlinedTextField(label, { label = it }, label = { Text(stringResource(R.string.maps_name_on_home)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (lists.isNotEmpty()) {
                Text(stringResource(R.string.maps_in_lists), style = MaterialTheme.typography.labelLarge)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    lists.forEach { l -> FilterChip(l.id in inLists, { inLists = if (l.id in inLists) inLists - l.id else inLists + l.id }, label = { Text("${l.emoji} ${l.name}".trim()) }) }
                }
            }
            Text(stringResource(R.string.maps_home_tap_goes), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val rolling = MapsPrefs.rolling(LocalContext.current)
                Place.MODES.forEach { m -> FilterChip(mode == m, { mode = m }, label = { Text(stringResource(modeLabelOf(m, rolling)), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(painterResource(modeIcon(m, rolling)), contentDescription = null, modifier = Modifier.size(16.dp)) }) }
            }
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            save(p.copy(id = p.id.ifEmpty { MapsFiles.newId() }, name = name.trim(), icon = icon, slot = slot, mode = mode, lists = lists.map { it.id }.filter { it in inLists }, label = label.trim()))
        }) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } })
}
