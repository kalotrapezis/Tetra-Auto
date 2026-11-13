package com.kalotrapezis.tetra.auto

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.File

/** Tetra Car's sizes and Maps' camera (the car's menu, as Tetra Car on the phone has them; the user, 13 Nov). */
internal data class CarView(val ui: Float, val map: Float, val zoom: Float, val tilt: Int, val low: Boolean, val dockRight: Boolean) {
    companion object {
        val UI = listOf(1f, 1.25f, 1.5f, 2f)
        val MAP = listOf(1f, 1.5f, 2f, 2.5f, 3f)
        val ZOOM = listOf(-1f, -0.5f, 0f, 0.5f, 1f)
        val TILT = listOf(-1, 0, 30, 45, 60)
        val state = mutableStateOf(CarView(1.25f, 1.5f, 0f, -1, true, false))
        private fun prefs(c: Context) = c.getSharedPreferences("car", Context.MODE_PRIVATE)
        fun load(c: Context) = prefs(c).let { p ->
            state.value = CarView(p.getFloat("ui_scale", 1.25f), p.getFloat("map_scale", 1.5f), p.getFloat("map_zoom", 0f), p.getInt("map_tilt", -1), p.getBoolean("map_low", true), p.getBoolean("dock_right", false))
        }
        fun set(c: Context, v: CarView) {
            state.value = v
            prefs(c).edit().putFloat("ui_scale", v.ui).putFloat("map_scale", v.map).putFloat("map_zoom", v.zoom).putInt("map_tilt", v.tilt).putBoolean("map_low", v.low).putBoolean("dock_right", v.dockRight).apply()
        }
    }
}

/**
 * The driving camera, as Maps on the phone has it: closer when slow, further out and more tilted when fast (the user: "more like
 * the Google Maps one"); the chosen zoom is added and a chosen tilt replaces the automatic one.
 */
internal fun driveCamera(speed: Float, v: CarView): Pair<Double, Double> {
    val kmh = speed * 3.6
    val zoom = 17.25 - 2.25 * ((kmh - 10) / 100).coerceIn(0.0, 1.0)
    val tilt = 45.0 + 15 * ((kmh - 50) / 60).coerceIn(0.0, 1.0)
    return (zoom + v.zoom).coerceIn(5.0, 19.0) to (if (v.tilt >= 0) v.tilt.toDouble() else tilt)
}

/**
 * Maps' style for the maps on this car: Tetra's own (Protomaps, dark), each .pmtiles in [root] a source, every layer drawn for
 * each map so two countries meet at the border as one (as mapStyle in Tetra for Android). Null without a map.
 */
internal fun carMapStyle(c: Context, root: File): String? {
    val ids = root.list().orEmpty().filter { it.endsWith(".pmtiles") && !it.startsWith(".") }.map { it.removeSuffix(".pmtiles") }.sorted()
    if (ids.isEmpty()) return null
    val text = c.assets.open("maps/dark.json").bufferedReader().use { it.readText() }
    val url = { id: String -> "pmtiles://file://${File(root, "$id.pmtiles").absolutePath}" }
    if (ids.size == 1) return text.replace("pmtiles://MAP_FILE", url(ids[0]))
    val style = JSONObject(text)
    val sources = style.getJSONObject("sources")
    val base = sources.getJSONObject("protomaps")
    sources.put("protomaps", JSONObject(base.toString()).put("url", url(ids[0])))
    for (id in ids.drop(1)) sources.put("protomaps__$id", JSONObject(base.toString()).put("url", url(id)))
    val layers = style.getJSONArray("layers"); val out = JSONArray()
    for (i in 0 until layers.length()) {
        val l = layers.getJSONObject(i)
        out.put(l)
        if (l.optString("source") == "protomaps") for (id in ids.drop(1)) out.put(JSONObject(l.toString()).put("id", "${l.getString("id")}__$id").put("source", "protomaps__$id"))
    }
    return style.put("layers", out).toString()
}

private const val NO_MAP = """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#24262b"}}]}"""

/** Metres between two points (haversine). */
internal fun metres(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1); val dLng = Math.toRadians(lng2 - lng1)
    val a = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2).let { it * it }
    return 2 * r * Math.asin(Math.sqrt(a))
}

/**
 * The map, large (the user: "the map drawn large … tilted, the arrow low"): Tetra's offline maps from the Maps folder, the
 * position the fix shown ([Fixes.now]: the unit's GPS, the phone's, or the better of both), the camera following it until the
 * map is moved by hand. A destination from the phone shows as a pin and a line to it.
 */
@SuppressLint("MissingPermission") // the arrow is drawn from our own fixes, MapLibre asks Android for nothing
@Composable
internal fun MapPage(root: File, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view by CarView.state
    val fix by Fixes.now
    val going by PhoneLink.going
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var following by remember { mutableStateOf(true) }
    var hasMap by remember { mutableStateOf(true) }
    var height by remember { mutableStateOf(720) }
    val ratio = LocalDensity.current.density * view.map
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    Box(modifier) {
        // the map's size is MapLibre's pixel ratio, fixed when the view is made: a new size, a new view
        androidx.compose.runtime.key(ratio) {
            val mapView = remember {
                MapLibre.getInstance(context)
                MapView(context, MapLibreMapOptions.createFromAttributes(context).pixelRatio(ratio)).apply { onCreate(Bundle()) }
            }
            DisposableEffect(lifecycle, mapView) {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
                val observer = LifecycleEventObserver { _, e ->
                    when (e) {
                        Lifecycle.Event.ON_START -> mapView.onStart(); Lifecycle.Event.ON_RESUME -> mapView.onResume()
                        Lifecycle.Event.ON_PAUSE -> mapView.onPause(); Lifecycle.Event.ON_STOP -> mapView.onStop()
                        else -> Unit
                    }
                }
                lifecycle.addObserver(observer)
                mapView.getMapAsync { m ->
                    val json = carMapStyle(context, root)
                    hasMap = json != null
                    m.uiSettings.isCompassEnabled = false; m.uiSettings.isAttributionEnabled = false; m.uiSettings.isLogoEnabled = false
                    m.cameraPosition = CameraPosition.Builder().target(LatLng(38.0, 23.73)).zoom(if (json == null) 3.0 else 12.0).build()
                    m.setStyle(Style.Builder().fromJson(json ?: NO_MAP)) { style ->
                        style.addSource(GeoJsonSource("going"))
                        style.addLayer(LineLayer("going-line", "going").withProperties(PropertyFactory.lineColor("#7986CB"), PropertyFactory.lineWidth(5f),
                            PropertyFactory.lineDasharray(arrayOf(2f, 2f)), PropertyFactory.lineCap(Property.LINE_CAP_ROUND)))
                        style.addLayer(CircleLayer("going-pin", "going").withProperties(PropertyFactory.circleColor("#E53935"), PropertyFactory.circleRadius(9f),
                            PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(3f)))
                        m.locationComponent.activateLocationComponent(LocationComponentActivationOptions.builder(context, style).useDefaultLocationEngine(false)
                            .locationComponentOptions(LocationComponentOptions.builder(context).gpsDrawable(R.drawable.maps_my_arrow).maxZoomIconScale(0.6f).minZoomIconScale(0.45f).build()).build())
                        m.locationComponent.isLocationComponentEnabled = true
                        m.locationComponent.cameraMode = CameraMode.NONE
                        m.locationComponent.renderMode = RenderMode.GPS
                        map = m
                    }
                    m.addOnCameraMoveStartedListener { why -> if (why == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) following = false }
                }
                onDispose { lifecycle.removeObserver(observer); map = null; mapView.onStop(); mapView.onDestroy() }
            }
            AndroidView({ mapView }, Modifier.fillMaxSize(), update = { height = it.height.takeIf { h -> h > 0 } ?: height })
        }

        // each fix: the arrow there, and the camera after it while following
        LaunchedEffect(map, fix, view, following) {
            val m = map ?: return@LaunchedEffect
            val f = fix ?: return@LaunchedEffect
            m.locationComponent.forceLocationUpdate(Location(if (f.phone) "phone" else "car").apply {
                latitude = f.lat; longitude = f.lng; accuracy = f.accuracy; speed = f.speed; if (!f.bearing.isNaN()) bearing = f.bearing; time = System.currentTimeMillis()
            })
            if (!following) return@LaunchedEffect
            val (zoom, tilt) = driveCamera(f.speed, view)
            val top = if (view.low) height * 0.45 else 0.0 // the arrow low on the screen: the road ahead shows
            m.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(f.lat, f.lng)).zoom(zoom).tilt(tilt)
                .bearing(if (f.moving) f.bearing.toDouble() else m.cameraPosition.bearing).padding(0.0, top, 0.0, 0.0).build()), 600)
        }
        // where to go: a pin and a straight line from here (offline directions on the routing graph come next)
        LaunchedEffect(map, going, fix?.let { it.lat.toFloat() to it.lng.toFloat() }) {
            val src = map?.style?.getSourceAs<GeoJsonSource>("going") ?: return@LaunchedEffect
            val g = going
            src.setGeoJson(if (g == null) FeatureCollection.fromFeatures(emptyList()) else FeatureCollection.fromFeatures(listOfNotNull(
                Feature.fromGeometry(Point.fromLngLat(g.lng, g.lat)),
                fix?.let { Feature.fromGeometry(LineString.fromLngLats(listOf(Point.fromLngLat(it.lng, it.lat), Point.fromLngLat(g.lng, g.lat)))) })))
        }

        if (!hasMap) Surface(Modifier.align(Alignment.Center).padding(32.dp).widthIn(max = 520.dp), shape = RoundedCornerShape(20.dp), color = Color(0xEE202226), contentColor = Color.White) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.se_no_map), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.se_no_map_how), style = MaterialTheme.typography.bodyLarge, color = Color(0xFFCCCCCC))
            }
        }
        Column(Modifier.align(Alignment.TopStart).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            going?.let { g ->
                Surface(shape = RoundedCornerShape(16.dp), color = Color(0xEE1A237E), contentColor = Color.White) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_navigation), null)
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(g.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            fix?.let { Text("%.1f km".format(metres(it.lat, it.lng, g.lat, g.lng) / 1000), style = MaterialTheme.typography.bodyMedium) }
                        }
                        IconButton({ PhoneLink.going.value = null }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close)) }
                    }
                }
            }
            fix?.let { f ->
                Surface(shape = RoundedCornerShape(12.dp), color = Color(0xCC000000), contentColor = Color.White) {
                    Text("${(f.speed * 3.6).toInt()} km/h · ${stringResource(if (f.phone) R.string.se_gps_phone else R.string.se_gps_car)} ±${f.accuracy.toInt()} m",
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (!following) Surface(onClick = { following = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(64.dp), shape = RoundedCornerShape(18.dp),
            color = Color(0xFF1A73E8), contentColor = Color.White) {
            Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_my_location), stringResource(R.string.se_recenter), Modifier.size(32.dp)) }
        }
    }
}
