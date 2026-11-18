package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.location.Geocoder
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.IconFactory
import java.util.Locale

private const val OPEN_FREE_MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"

private data class LocatedPhoto(val entry: Entry, val location: PhotoLocation)
private data class LocationScan(val done: Int, val total: Int, val photos: List<LocatedPhoto>, val finished: Boolean)

@Composable
internal fun PhotoMapScreen(
    entries: List<Entry>,
    metadataStore: PhotoMetadataStore,
    hasLocationAccess: Boolean,
    requestLocationAccess: () -> Unit,
    locationsUpdated: () -> Unit,
    focusPhotoKey: String?,
    back: () -> Unit,
    openPhoto: (Entry) -> Unit,
) {
    val context = LocalContext.current
    if (!hasLocationAccess) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.photos_map_allow_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.photos_map_allow_body), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = requestLocationAccess) { Text(stringResource(R.string.photos_allow_locations), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            MapBackButton(back, Modifier.align(Alignment.TopStart).padding(16.dp))
        }
        return
    }
    val scan by produceState(LocationScan(0, entries.size, emptyList(), false), entries) {
        value = withContext(Dispatchers.IO) {
            val cached = metadataStore.locations(entries.map(Entry::photoKey))
            val found = mutableListOf<LocatedPhoto>()
            entries.forEach { entry ->
                val location = if (entry.photoKey in cached) cached[entry.photoKey] else readLocation(context, entry).also {
                    metadataStore.recordLocation(entry.photoKey, it)
                }
                if (location != null) found += LocatedPhoto(entry, location)
            }
            LocationScan(entries.size, entries.size, found, true)
        }
    }
    var namedPhotos by remember(scan.photos) { mutableStateOf(scan.photos) }
    LaunchedEffect(scan.photos, scan.finished) {
        if (!scan.finished) return@LaunchedEffect
        namedPhotos = withContext(Dispatchers.IO) {
            scan.photos.map { photo ->
                if (photo.location.placeResolved) photo else {
                    val placeName = reverseGeocodePlace(context, photo.location)
                    metadataStore.recordPlaceName(photo.entry.photoKey, placeName)
                    photo.copy(location = photo.location.copy(placeName = placeName, placeResolved = true))
                }
            }
        }
        locationsUpdated()
    }
    Box(Modifier.fillMaxSize()) {
        when {
            !scan.finished -> MapLoading(Modifier.align(Alignment.Center))
            scan.photos.isEmpty() -> Text(stringResource(R.string.photos_map_none), modifier = Modifier.align(Alignment.Center).padding(24.dp))
            else -> PhotoMap(namedPhotos, focusPhotoKey, openPhoto)
        }
        MapBackButton(back, Modifier.align(Alignment.TopStart).padding(16.dp))
    }
}

@Composable
private fun MapLoading(modifier: Modifier = Modifier) {
    val animation = rememberInfiniteTransition(label = "map-loading")
    val rotation by animation.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Restart),
        label = "map-rotation",
    )
    Column(
        modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_map),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp).graphicsLayer { rotationZ = rotation },
        )
        Text(
            stringResource(R.string.photos_map_loading),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PhotoMap(photos: List<LocatedPhoto>, focusPhotoKey: String?, openPhoto: (Entry) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pinIcon = remember(context) { mapPinIcon(context, MAP_PIN_RED) }
    var selected by remember(photos, focusPhotoKey) { mutableStateOf(photos.firstOrNull { it.entry.photoKey == focusPhotoKey }) }
    val mapView = remember(photos) {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(Bundle()) }
    }
    var mapReady by remember(mapView) { mutableStateOf(false) }
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
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    LaunchedEffect(mapView, photos) {
        mapView.getMapAsync { map ->
            map.setStyle(OPEN_FREE_MAP_STYLE) {
                val byMarker = photos.associateBy { photo ->
                    map.addMarker(MarkerOptions().position(LatLng(photo.location.latitude, photo.location.longitude)).icon(pinIcon).title(photo.location.placeName ?: photo.entry.name)).id
                }
                map.setOnMarkerClickListener { marker -> selected = byMarker[marker.id]; true }
                map.addOnMapClickListener { selected = null; false }
                val points = photos.map { LatLng(it.location.latitude, it.location.longitude) }
                mapView.post {
                    val focused = photos.firstOrNull { it.entry.photoKey == focusPhotoKey }
                    if (focused != null) map.cameraPosition = CameraPosition.Builder().target(LatLng(focused.location.latitude, focused.location.longitude)).zoom(13.0).build()
                    else if (points.size == 1) map.cameraPosition = CameraPosition.Builder().target(points.first()).zoom(13.0).build()
                    else map.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(), 100))
                }
                mapReady = true
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        if (!mapReady) Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.align(Alignment.Center),
        ) { MapLoading(Modifier.padding(vertical = 24.dp)) }
        selected?.let { photo -> Surface(
            color = islandColor(), contentColor = islandContentColor(), shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp).fillMaxWidth(),
        ) {
            Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PhotoThumbnail(photo.entry, false, Modifier.size(68.dp).clip(MaterialTheme.shapes.medium)) { openPhoto(photo.entry) }
                Column(Modifier.weight(1f)) {
                    Text(photo.location.placeName ?: photo.entry.name, style = MaterialTheme.typography.titleSmall)
                    Text("${"%.5f".format(photo.location.latitude)}, ${"%.5f".format(photo.location.longitude)}", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { openExternalMap(context, photo) }) { Text(stringResource(R.string.photos_map_app), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        } }
    }
}

@Composable
private fun MapBackButton(back: () -> Unit, modifier: Modifier = Modifier) = Surface(
    color = islandColor(), contentColor = islandContentColor(), shape = CircleShape, modifier = modifier,
) {
    androidx.compose.material3.IconButton(onClick = back) {
        androidx.compose.material3.Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.photos_map_back))
    }
}

@Composable
/** A static map preview: no pan or zoom; a tap opens the Map collection at this photo's pin. */
internal fun PhotoLocationPreview(location: PhotoLocation, open: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember(location.latitude, location.longitude) {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(Bundle()) }
    }
    val pinIcon = remember(context) { mapPinIcon(context, MAP_PIN_RED) }
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
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    LaunchedEffect(mapView, location) {
        mapView.getMapAsync { map ->
            map.uiSettings.setAllGesturesEnabled(false)
            map.setStyle(OPEN_FREE_MAP_STYLE) {
                map.addMarker(MarkerOptions().position(LatLng(location.latitude, location.longitude)).icon(pinIcon).title(location.placeName))
                map.cameraPosition = CameraPosition.Builder().target(LatLng(location.latitude, location.longitude)).zoom(11.5).build()
            }
        }
    }
    val showOnMap = stringResource(R.string.photos_show_on_map)
    Box(Modifier.fillMaxWidth().height(176.dp).clip(MaterialTheme.shapes.large)) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        // Catches every touch above the map view, so scrolling the Details sheet never drags the map.
        Box(Modifier.fillMaxSize().clickable(onClickLabel = showOnMap, onClick = open))
    }
}

/** Red on every device, the same as the desktop map: a pin reads as a place, whatever the theme accent is. */
private const val MAP_PIN_RED = 0xFFE5484D.toInt()

private fun mapPinIcon(context: Context, color: Int): org.maplibre.android.annotations.Icon {
    val size = (48 * context.resources.displayMetrics.density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    val center = size / 2f
    val radius = size * 0.32f
    canvas.drawCircle(center, size * 0.38f, radius, paint)
    canvas.drawPath(Path().apply {
        moveTo(center - radius * 0.72f, size * 0.54f)
        lineTo(center + radius * 0.72f, size * 0.54f)
        lineTo(center, size * 0.9f)
        close()
    }, paint)
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(center, size * 0.38f, radius * 0.38f, paint)
    return IconFactory.getInstance(context).fromBitmap(bitmap)
}

internal fun loadPhotoLocation(context: Context, entry: Entry, metadataStore: PhotoMetadataStore): PhotoLocation? {
    val cached = metadataStore.locations(listOf(entry.photoKey))
    var location = if (entry.photoKey in cached) cached[entry.photoKey] else readLocation(context, entry).also {
        metadataStore.recordLocation(entry.photoKey, it)
    }
    if (location != null && !location.placeResolved) {
        val placeName = reverseGeocodePlace(context, location)
        metadataStore.recordPlaceName(entry.photoKey, placeName)
        location = location.copy(placeName = placeName, placeResolved = true)
    }
    return location
}

private fun readLocation(context: Context, entry: Entry): PhotoLocation? = entry.contentUri?.let { uri -> runCatching {
    if (entry.isVideo) MediaMetadataRetriever().let { retriever ->
        try {
            retriever.setDataSource(context, uri)
            parseIso6709Location(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))
        } finally {
            retriever.release()
        }
    } else {
        context.contentResolver.openInputStream(MediaStore.setRequireOriginal(uri)).use { input ->
            requireNotNull(input)
            val coordinates = FloatArray(2)
            if (ExifInterface(input).getLatLong(coordinates)) PhotoLocation(coordinates[0].toDouble(), coordinates[1].toDouble()) else null
        }
    }
}.getOrNull() }

internal fun mainPlaceName(locality: String?, subAdminArea: String?, adminArea: String?, countryName: String?): String? =
    listOf(locality, subAdminArea, adminArea, countryName).firstOrNull { !it.isNullOrBlank() }?.trim()

private fun reverseGeocodePlace(context: Context, location: PhotoLocation): String? {
    if (!Geocoder.isPresent()) return null
    @Suppress("DEPRECATION")
    val address = runCatching {
        Geocoder(context, Locale.getDefault()).getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
    }.getOrNull()
    return mainPlaceName(address?.locality, address?.subAdminArea, address?.adminArea, address?.countryName)
}

internal fun parseIso6709Location(value: String?): PhotoLocation? {
    val match = value?.let { Regex("^([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)/?$").matchEntire(it) } ?: return null
    val latitude = match.groupValues[1].toDoubleOrNull() ?: return null
    val longitude = match.groupValues[2].toDoubleOrNull() ?: return null
    return PhotoLocation(latitude, longitude).takeIf { latitude in -90.0..90.0 && longitude in -180.0..180.0 }
}

private fun openExternalMap(context: Context, photo: LocatedPhoto) {
    val location = photo.location
    val label = Uri.encode(photo.entry.name)
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${location.latitude},${location.longitude}?q=${location.latitude},${location.longitude}($label)")))
}
