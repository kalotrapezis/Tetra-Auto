package com.kalotrapezis.drive

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Tetra Car (the user, 9 Nov: "our own Car UI in the app … AI, browser for youtube or radio, maps, messages and phone"; Android's
 * split screen "is horrible"): a head unit's home screen. Tetra's own pages side by side in panes (Full, 60/40, 50/50, three), a
 * dock at the bottom with AI Assist's mic in its middle, and Media: the Browser in Player mode, YouTube Music, YouTube or
 * Live24.gr. A page keeps running when its pane is given to another (the route, the music), until Tetra Car closes.
 */
class CarActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.wrap(base))
    private var size = 1f
    // a new size on the car's screen (Settings › Tetra Car): this screen drawn again at it
    override fun onResume() { super.onResume(); if (CarSize.of(this) != size) recreate() }
    override fun onCreate(savedInstanceState: Bundle?) {
        size = CarSize.of(this)
        super.onCreate(savedInstanceState)
        WriterLog.init(this)
        TetraLook.load(this)
        CalendarIndex.dir = cacheDir; NotesStore.indexDir = cacheDir // the caches MainActivity names at its start
        if (PhoneLog.recent == null) PhoneLog.warm(this)
        if (ContactsPreload.all == null) ContactsPreload.warm(this)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = TetraLook.light
        fullScreen()
        WriterLog.i("Tetra Car opened")
        open++; Car.running = true
        CarQuiet.set(this, true)
        // the car's link to the phone (CarWire): this unit's server for the phone's calls and messages, and a hello every 30 s
        server = runCatching { SyncServer.acquire(applicationContext, SyncStore(applicationContext)) }.onFailure { WriterLog.w("Car: no server for the phone", it) }.getOrNull()
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { while (true) { CarWire.hello(applicationContext); kotlinx.coroutines.delay(30_000) } }
        setContent { TetraTheme { CarScreen() } }
    }
    private var server: SyncServer? = null
    /** The whole screen, the system's bars hidden (the user, 13 Nov: "hide the system bar, make it full screen"); a swipe shows them for a while. */
    private fun fullScreen() = WindowCompat.getInsetsController(window, window.decorView).run {
        systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE; hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) fullScreen() }
    // a new size recreates this screen: the old one goes after the new one came, so only the last one out says Tetra Car closed
    override fun onDestroy() { if (--open == 0) { Car.running = false; CarQuiet.set(this, false) }; if (server != null) runCatching { SyncServer.release() }; super.onDestroy() }
    private companion object { var open = 0 }
    // the steering wheel's voice key, where the head unit passes it on: AI Assist listens
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOICE_ASSIST || keyCode == KeyEvent.KEYCODE_SEARCH) { Car.assist?.invoke() ?: carListen(this); return true }
        return super.onKeyDown(keyCode, event)
    }
}

/**
 * Home's saved places on the car (the user, 10 Nov: "the home faves in the car as a page with bubbles and an X or go to map"): big
 * bubbles, one tap the route there; ✕, Map or Back to the map. The same places as Home's Maps card and the map's ♥.
 */
@Composable
private fun CarPlaces(go: (Place) -> Unit, close: () -> Unit) {
    val saved by produceState<List<Place>?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { MapsFiles.slots(MapsFiles.places(MapsFiles.root(driveRoot()))).filterNotNull() }.getOrDefault(emptyList()) }
    }
    BackHandler(onBack = close)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.home_favorites), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            androidx.compose.material3.TextButton(close) { Icon(painterResource(R.drawable.ic_map), null); Text("  " + stringResource(R.string.app_maps)) }
            IconButton(close) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.common_close)) }
        }
        val list = saved ?: return@Column
        if (list.isEmpty()) Text(stringResource(R.string.car_favorites_none), style = MaterialTheme.typography.bodyLarge)
        LazyVerticalGrid(GridCells.Adaptive(140.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(list, key = { it.id }) { p ->
                Column(Modifier.clip(RoundedCornerShape(24.dp)).clickable { go(p) }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(88.dp).clip(CircleShape).background(Color(0xFF25C4FE)), contentAlignment = Alignment.Center) {
                        Icon(painterResource(placeIcon(p.icon)), null, Modifier.size(44.dp), tint = Color.White)
                    }
                    Text(p.short, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

/**
 * Tetra Car's sizes and Maps' camera there (the user, 13 Nov: "everything when it comes to maps is small … the view more like
 * Google Maps … tilt or zoom level can be part of a settings page … UI scaling as well 100% 150% 200%"). The car asks for
 * 1280×720 at 160 dpi and its panel shows that on 1024×600, seen from the seat: [map] ×2 looks as big as the phone in the hand.
 * [ui] Tetra Car's own screens; [map] Maps' map and buttons, instead of [ui]; [zoom] added to the camera's zoom while driving;
 * [tilt] fixed degrees, -1 the camera's own (45° rising to 60° with speed); [low] the arrow low on the screen, the road ahead above it;
 * [res] the picture drawn at that part of the size the car asks for, its shape kept (0.8: 1024×576 for its 1280×720, the panel's 1024 wide), so all is bigger and sharper.
 * [card], [island], [side]: the map's top card, its bottom island and the speed and limit at its side, each its own size (the user, 17 Nov:
 * "separate their size on the screen", the map seen around them); 0 until chosen: as [map], which stays as it was.
 */
internal data class CarView(val ui: Float, val map: Float, val zoom: Float, val tilt: Int, val low: Boolean, val res: Float = 1f,
                            val card: Float = 0f, val island: Float = 0f, val side: Float = 0f) {
    /** The map's parts, each over the map's own size (Maps is drawn at [map]). */
    fun parts() = MapParts(card.ifZero(map) / map, island.ifZero(map) / map, side.ifZero(map) / map)
    private fun Float.ifZero(o: Float) = if (this == 0f) o else this
    /** A driving camera's zoom and tilt, as chosen here. */
    fun camera(v: Pair<Double, Double>) = (v.first + zoom).coerceIn(5.0, 19.0) to (if (tilt >= 0) tilt.toDouble() else v.second)
    companion object {
        val PHONE = CarView(1f, 1f, 0f, -1, false)
        val UI = listOf(1f, 1.25f, 1.5f, 1.75f, 2f) // 25% steps (the user, 13 Nov)
        val MAP = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.25f, 2.5f, 2.75f, 3f)
        val PART = listOf(0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f) // smaller than the map's too (the user: "a bit smaller")
        val ZOOM = listOf(-1f, -0.5f, 0f, 0.5f, 1f)
        val TILT = listOf(-1, 0, 30, 45, 60)
        val RES = listOf(1f, 0.8f, 2f / 3)
    }
}

/**
 * The car's menu (the dock's ☰; the user, 13 Nov: "a settings app, the phone, the messages and whatever else is not on the dock"):
 * a tile for each, [open] puts a page in the panes; Settings slides to Tetra Car's sizes and how Maps looks at the road, each a
 * row of choices changed at once, with its way back.
 */
@Composable
internal fun CarViewPage(open: (CarApp) -> Unit, media: () -> Unit, startSettings: Boolean = false, inSettings: Boolean = false, close: () -> Unit) {
    val context = LocalContext.current
    val v = Car.view(context)
    var settings by remember { mutableStateOf(startSettings) }
    fun back() { if (settings && !startSettings) settings = false else close() } // opened at its settings (the car's build): back closes it
    fun pct(f: Float) = "${(f * 100).toInt()} %"
    @Composable fun <T> row(label: Int, options: List<T>, chosen: T, name: @Composable (T) -> String, pick: (T) -> Unit) {
        Text(stringResource(label), Modifier.padding(top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o -> FilterChip(o == chosen, { pick(o) }, { Text(name(o), style = MaterialTheme.typography.titleMedium) }) }
        }
    }
    // an icon and its name, four to a row, as Android Auto's launcher, in Tetra's rounded squares (the user, 13 Nov)
    @Composable fun tile(label: Int, tap: () -> Unit, modifier: Modifier, art: @Composable () -> Unit) {
        Column(modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = tap).padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) { art() } // Tetra's rounded squares
            Text(stringResource(label), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    @Composable fun icon(icon: Int, color: Color) = Box(Modifier.size(72.dp).background(color), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(36.dp), tint = Color.White)
    }
    BackHandler { back() }
    if (!settings) Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) {}
        .verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            IconButton(close) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.common_close)) }
        }
        androidx.compose.foundation.layout.FlowRow(maxItemsInEachRow = 4) {
            val w = Modifier.weight(1f)
            listOf(CarApp.Maps to (R.drawable.maps_art to R.string.app_maps), CarApp.Phone to (R.drawable.phone_art to R.string.app_phone),
                CarApp.Messages to (R.drawable.sms_art to R.string.app_sms), CarApp.Browser to (R.drawable.browser_art to R.string.app_browser)).forEach { (app, look) ->
                tile(look.second, { close(); open(app) }, w) { Image(painterResource(look.first), null, Modifier.size(72.dp)) }
            }
            tile(R.string.car_media, { close(); media() }, w) { icon(R.drawable.ic_play, Color(0xFFE53935)) }
            if (!CarMaps.edition) tile(R.string.auto_share, { close(); AutoProjection.session?.openShare() }, w) { icon(R.drawable.ic_share, MaterialTheme.colorScheme.secondary) } // Tetra Auto's Share
            tile(R.string.common_settings, { settings = true }, w) { icon(R.drawable.ic_settings, Color(0xFF1A73E8)) }
            Spacer(w) // the last row's empty place, so its tiles keep their width
        }
    }
    else Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable { back() }, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(16.dp).widthIn(max = 600.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surface)
            .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) {}.verticalScroll(rememberScrollState()).padding(20.dp)) { // a tap on the card stays on it
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(::back) { Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.common_back)) }
                Text(stringResource(R.string.car_map_view), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                IconButton(close) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.common_close)) }
            }
                row(R.string.car_map_size, CarView.MAP, v.map, { pct(it) }) { Car.setView(context, v.copy(map = it)) }
                // each chip the part's size on the screen, as the map's: until chosen, the one nearest the map's lit
                @Composable fun part(label: Int, now: Float, set: (Float) -> CarView) =
                    row(label, CarView.PART, CarView.PART.minBy { kotlin.math.abs(it - if (now == 0f) v.map else now) }, { pct(it) }) { Car.setView(context, set(it)) }
                part(R.string.car_map_card, v.card) { v.copy(card = it) }
                part(R.string.car_map_island, v.island) { v.copy(island = it) }
                part(R.string.car_map_side, v.side) { v.copy(side = it) }
                row(R.string.car_map_zoom, CarView.ZOOM, v.zoom, { if (it == 0f) "0" else "%+.1f".format(it) }) { Car.setView(context, v.copy(zoom = it)) }
                row(R.string.car_map_tilt, CarView.TILT, v.tilt, { if (it < 0) stringResource(R.string.car_map_tilt_auto) else "$it°" }) { Car.setView(context, v.copy(tilt = it)) }
                row(R.string.car_map_arrow, listOf(false, true), v.low, { stringResource(if (it) R.string.car_map_arrow_low else R.string.car_map_arrow_middle) }) { Car.setView(context, v.copy(low = it)) }
                // the car's build: one size for all of Tetra (CarSize, also in Settings › Tetra Car), not a second one over Tetra Car's alone
                if (CarMaps.edition) row(R.string.car_screen_size, CarSize.STEPS, CarSize.of(context), { pct(it) }) { CarSize.set(context, it); (context as? android.app.Activity)?.recreate() }
                else row(R.string.car_ui_size, CarView.UI, v.ui, { pct(it) }) { Car.setView(context, v.copy(ui = it)) }
                val asked = AutoProjection.session?.asked
                // the car's build: the rest of Tetra's settings for the car (Tetra Car, Maps, Browser, AI Assist, the look)
                if (CarMaps.edition && !inSettings) androidx.compose.material3.TextButton({
                    AppLocale.reopenSettings = true
                    runCatching { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    close()
                }, Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.common_settings) + "  ›", style = MaterialTheme.typography.titleMedium) }
                if (!CarMaps.edition) row(R.string.car_resolution, CarView.RES, v.res, { r -> asked?.let { (w, h) -> "${(w * r).toInt() and 1.inv()}×${(h * r).toInt() and 1.inv()}" } ?: pct(r) }) { Car.setView(context, v.copy(res = it)) }
        }
    }
}

/** The time, for the dock down a side (the user, 10 Nov: "the player buttons at the top I don't like, just the time there"); a tap shows the recent messages (13 Nov). */
@Composable
private fun CarClock(modifier: Modifier = Modifier, tap: () -> Unit = {}) {
    val context = LocalContext.current
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(60_000 - value % 60_000) } }
    Text(android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(now)), modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = tap).padding(horizontal = 2.dp, vertical = 6.dp), fontSize = 20.sp, maxLines = 1) // "18:30" within the side dock's 64 dp
}

/**
 * The time and the battery at the dock's end (the user, 13 Nov: "a clock and battery to the horizontal as well as to the side"):
 * on a head unit the phone's battery, as Android Auto shows it (CarWire's hello brings it); on the phone its own.
 */
@Composable
private fun CarStatus(down: Boolean, clock: () -> Unit) {
    val context = LocalContext.current
    val remote by CarWire.battery
    val own by produceState<Pair<Int, Boolean>?>(null) {
        while (true) {
            value = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.takeIf { it.getBooleanExtra(android.os.BatteryManager.EXTRA_PRESENT, false) }?.let { i ->
                val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) * 100 / i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                val status = i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, 0)
                level to (status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL)
            }
            delay(60_000)
        }
    }
    val battery = remote ?: own
    val parts: @Composable () -> Unit = { battery?.let { (level, charging) -> CarBattery(level, charging) }; CarClock(tap = clock) }
    if (down) Column(horizontalAlignment = Alignment.CenterHorizontally) { parts() }
    else Column(Modifier.padding(start = 12.dp, end = 8.dp), horizontalAlignment = Alignment.End) { parts() }
}

/** A battery drawn as a battery: its outline, filled to the level (red under 20 %), a bolt while it charges, and the per cent. */
@Composable
private fun CarBattery(level: Int, charging: Boolean) {
    val ink = MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(24.dp, 12.dp)) {
            val tip = size.width * 0.1f
            drawRoundRect(ink, size = Size(size.width - tip, size.height), cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(1.5.dp.toPx()))
            drawRect(ink, Offset(size.width - tip, size.height * 0.3f), Size(tip, size.height * 0.4f))
            val inset = 2.5.dp.toPx()
            drawRect(if (level < 20 && !charging) Color(0xFFE53935) else if (charging) Color(0xFF43A047) else ink, Offset(inset, inset),
                Size((size.width - tip - inset * 2) * level.coerceIn(0, 100) / 100f, size.height - inset * 2))
        }
        Text((if (charging) "⚡" else "") + "$level%", fontSize = 13.sp, maxLines = 1)
    }
}

/**
 * Tetra Car's state for the rest of Tetra: open here ([running]), its panes reachable by AI Assist ([show]), and in the car
 * (Tetra Car open, or this phone on a car's Bluetooth) messages read aloud and calls answered after 2 s (the user, 9 Nov).
 */
internal object Car {
    @Volatile var running = false
    /** AI Assist's «άνοιξε τα μηνύματα»: that page beside the map; null when Tetra Car is not open. */
    @Volatile var show: ((CarApp) -> Unit)? = null
    /** AI Assist's answer (an Applet.EXTRA word: maps-go:, browser-play:, sms:…) in Tetra Car's panes; false: not one of theirs. */
    @Volatile var go: ((String) -> Boolean)? = null
    /** AI Assist in Tetra Car's side pane (its TV); null when Tetra Car is not open. */
    @Volatile var assist: (() -> Unit)? = null
    /** A message arrived: Tetra Car's side pane talks about it (CarSmsPane); null when Tetra Car is not open. */
    @Volatile var message: ((CarMessage) -> Unit)? = null
    private fun prefs(c: Context) = c.getSharedPreferences("car", Context.MODE_PRIVATE)
    fun reads(c: Context) = prefs(c).getBoolean("read_sms", true)
    /** Open by itself (the user, 9 Nov: "open Tetra Car app on boot or on connection with phone"): at start, or as the chosen Bluetooth joins. */
    fun opensOnBoot(c: Context) = prefs(c).getBoolean("open_boot", false)
    fun opensOnBluetooth(c: Context) = prefs(c).getBoolean("open_bt", false)
    fun setOpens(c: Context, boot: Boolean? = null, bluetooth: Boolean? = null) = prefs(c).edit().apply { boot?.let { putBoolean("open_boot", it) }; bluetooth?.let { putBoolean("open_bt", it) } }.apply()
    /** Android lets an app open a screen from the background only with "Display over other apps" (or as the home screen). */
    fun mayOpen(c: Context) = android.provider.Settings.canDrawOverlays(c)
    fun askOpen(c: Context) = c.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    fun open(c: Context) = runCatching { c.startActivity(Intent(c, CarActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { WriterLog.w("Car: not opened by itself", it) }
    private val dock = mutableStateOf<DockSide?>(null)
    /** Where Tetra Car's dock sits (Settings › Tetra Car, radio buttons): one state, so an open Tetra Car follows a change at once. */
    fun side(c: Context): DockSide = dock.value ?: runCatching { DockSide.valueOf(prefs(c).getString("dock", null)!!) }.getOrDefault(DockSide.Auto).also { dock.value = it }
    private val driver = mutableStateOf<Boolean?>(null)
    /** The driver sits on the right (right-hand drive: UK, Ireland, Cyprus, Malta…): Auto's dock goes down the right. */
    fun driverRight(c: Context): Boolean = driver.value ?: prefs(c).getBoolean("driver_right", false).also { driver.value = it }
    fun setDriverRight(c: Context, right: Boolean) { driver.value = right; prefs(c).edit().putBoolean("driver_right", right).apply() }
    fun setSide(c: Context, d: DockSide) { dock.value = d; prefs(c).edit().putString("dock", d.name).apply() }
    private val viewState = mutableStateOf<CarView?>(null)
    /** Tetra Car's sizes and Maps' camera (the car's menu): one state, so the screen and the map follow a change at once. */
    fun view(c: Context): CarView = viewState.value ?: prefs(c).let { p ->
        CarView(if (CarMaps.edition) 1f else p.getFloat("ui_scale", 1f) /* the car's build: CarSize instead */, p.getFloat("map_scale", if (CarMaps.edition) 1.25f else 2f) /* the car's build is 150 % already (CarSize) */, p.getFloat("map_zoom", 0f), p.getInt("map_tilt", -1), p.getBoolean("map_low", true), p.getFloat("resolution", 1f),
            p.getFloat("card_scale", 0f), p.getFloat("island_scale", 0f), p.getFloat("side_scale", 0f))
    }.also { viewState.value = it }
    fun setView(c: Context, v: CarView) {
        val old = viewState.value
        viewState.value = v
        prefs(c).edit().putFloat("ui_scale", v.ui).putFloat("map_scale", v.map).putFloat("map_zoom", v.zoom).putInt("map_tilt", v.tilt).putBoolean("map_low", v.low).putFloat("resolution", v.res)
            .putFloat("card_scale", v.card).putFloat("island_scale", v.island).putFloat("side_scale", v.side).apply()
        WriterLog.i("Car: view $v")
        // another resolution: the car's picture again at that size (the car joins again by itself)
        if (old != null && old.res != v.res) AutoProjection.session?.end()
    }
    fun answers(c: Context) = prefs(c).getBoolean("answer_calls", true)
    fun set(c: Context, read: Boolean?, answer: Boolean?) = prefs(c).edit().apply { read?.let { putBoolean("read_sms", it) }; answer?.let { putBoolean("answer_calls", it) } }.apply()

    /**
     * The car's Bluetooth, chosen by the user in Settings › Tetra Car (the user, 9 Nov: "otherwise every bluetooth speaker will
     * open the car app"): its addresses, one per car. None chosen: Bluetooth never puts the phone in the car.
     */
    fun cars(c: Context): Set<String> = prefs(c).getStringSet("bt_cars", emptySet()).orEmpty()
    fun setCars(c: Context, addresses: Set<String>) = prefs(c).edit().putStringSet("bt_cars", addresses).apply()
    fun isCar(c: Context, address: String?) = address != null && address in cars(c)

    /** In the car: Tetra Car open here, or a car connected on Bluetooth's hands-free; [then] on the main thread or Bluetooth's. */
    @android.annotation.SuppressLint("MissingPermission") // checked first
    fun inCar(c: Context, then: (Boolean) -> Unit) {
        if (running || CarWire.alive()) return then(true) // Tetra Car open here, or on the car's screen and saying hello
        val bt = c.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        if (cars(c).isEmpty() || bt == null || !bt.isEnabled || c.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return then(false)
        val asked = runCatching { bt.getProfileProxy(c.applicationContext, object : android.bluetooth.BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
                val car = runCatching { proxy.connectedDevices.any { isCar(c, it.address) } }.getOrDefault(false)
                runCatching { bt.closeProfileProxy(profile, proxy) }
                then(car)
            }
            override fun onServiceDisconnected(profile: Int) {}
        }, android.bluetooth.BluetoothProfile.HEADSET) }.getOrDefault(false)
        if (!asked) then(false)
    }
}

/** A page of Tetra Car by the name said («χάρτες», «μηνύματα», «Spotify»); null for any other app. */
internal fun carAppFor(name: String): CarApp? {
    val n = java.text.Normalizer.normalize(name.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
    fun has(vararg w: String) = w.any { it in n }
    return when {
        has("χαρτ", "map", "xart", "harte", "navig", "πλοηγ") -> CarApp.Maps
        has("τηλεφων", "phone", "κλησ", "dial", "tilef") -> CarApp.Phone
        has("μηνυμ", "messag", "sms", "minim") -> CarApp.Messages
        has("browser", "περιηγ", "spotify", "youtube", "live24", "media", "πολυμεσ", "μουσικ", "ραδιο", "radio") -> CarApp.Browser
        else -> null
    }
}

/**
 * AI Assist's «άνοιξε» / «φέρε μπροστά» (the user, 9 Nov: "να την ανοίγει δίπλα στο χάρτη"): in Tetra Car a page of its own beside
 * the map, another app beside Tetra Car where the unit allows it; elsewhere that app as usual. False when no app has that name.
 */
internal fun carOpenApp(c: Context, name: String): Boolean {
    if (Car.running) carAppFor(name)?.let { app -> Car.show?.let { it(app); WriterLog.i("Car: $app beside the map, as asked"); return true } }
    fun fold(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").trim()
    val said = fold(name)
    val pm = c.packageManager
    val all = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .filter { it.activityInfo.name != "${CarActivity::class.java.name.substringBeforeLast('.')}.AppCar" }.map { it to fold(it.loadLabel(pm).toString()) }
    val hit = all.firstOrNull { it.second == said } ?: all.firstOrNull { it.second.contains(said) || said.contains(it.second) } ?: return false
    val a = hit.first.activityInfo
    c.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(a.packageName, a.name))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or (if (Car.running) Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT else 0)))
    WriterLog.i("Car: ${a.packageName} opened${if (Car.running) " beside Tetra Car" else ""}")
    return true
}

/** AI Assist's sheet over the car's screen: it listens at once, answers, and goes. */
internal fun carListen(c: Context) = c.startActivity(Intent(c, AssistantSheetActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

/** Settings › App icons › Tetra Car: its launcher icon, also offered as the home screen (off until asked for, as the applets'). */
internal object CarIcon {
    private fun component(c: Context) = ComponentName(c.packageName, "${CarActivity::class.java.name.substringBeforeLast('.')}.AppCar")
    fun shown(c: Context) = c.packageManager.getComponentEnabledSetting(component(c)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    fun show(c: Context, on: Boolean) {
        c.packageManager.setComponentEnabledSetting(component(c),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        WriterLog.i("App icon ${if (on) "shown" else "hidden"}: Car")
    }
}

/** A page of Tetra Car's is in a pane now (false: kept running off the screen); outside Tetra Car always true. */
internal val LocalCarPaneShown = androidx.compose.runtime.compositionLocalOf { true }

/** What a pane shows. Media is the Browser too (one Browser: its tabs and its sound), opened on its three tiles. */
internal enum class CarApp { Maps, Browser, Phone, Messages }

/** Where the dock sits (the user, 9 Nov: "κανονικά έχει τη μπάρα στο πλάι"): along the bottom, or down a side. */
internal enum class DockSide { Auto, Bottom, Left, Right }

/**
 * The dock's apps (the user, 13 Nov: "if I open an app that is not on the dock it goes to the dock and the oldest app that was on the
 * dock goes away from it"): Tetra's pages ("maps", "media", "phone", "messages", "browser") and the unit's own apps ("app:" and its
 * component), [SLOTS] of them, in the order they came; one already there stays where it is.
 */
internal object CarDockApps {
    const val SLOTS = 4
    val START = listOf("maps", "media", "phone", "messages")
    private val state = mutableStateOf<List<String>?>(null)
    private fun prefs(c: Context) = c.getSharedPreferences("car", Context.MODE_PRIVATE)
    fun all(c: Context): List<String> = state.value ?: runCatching { org.json.JSONArray(prefs(c).getString("dock_apps", null)!!).let { a -> (0 until a.length()).map(a::getString) } }
        .getOrDefault(START).also { state.value = it }
    fun used(c: Context, id: String) {
        val next = docked(all(c), id)
        if (next == state.value) return
        state.value = next; prefs(c).edit().putString("dock_apps", org.json.JSONArray(next).toString()).apply()
    }
}

/** The dock after [id] was opened: unchanged when it is there; else it joins at the end, the oldest leaving to keep [slots]. */
internal fun docked(now: List<String>, id: String, slots: Int = CarDockApps.SLOTS) = if (id in now) now else (now + id).takeLast(slots)

/**
 * Auto, as Android Auto does it (the user, 13 Nov: "in my car the bar is to the side, it depends on the aspect ratio … right hand
 * drive and left hand drive changes at what side the controls are"): a wide screen has the dock down the driver's side, a narrower
 * one along the bottom.
 */
internal fun dockFor(chosen: DockSide, widthDp: Int, heightDp: Int, driverRight: Boolean) = when {
    chosen != DockSide.Auto -> chosen
    heightDp > 0 && widthDp.toFloat() / heightDp >= 1.5f -> if (driverRight) DockSide.Right else DockSide.Left
    else -> DockSide.Bottom
}

internal enum class CarLayout(val weights: List<Float>) { Full(listOf(1f)), Wide(listOf(0.6f, 0.4f)), Half(listOf(0.5f, 0.5f)), Three(listOf(0.5f, 0.25f, 0.25f)) }

/** The panes left to right, and the one the dock fills ([focus]); kept as "Wide|Maps,Browser|1". */
internal data class CarState(val layout: CarLayout = CarLayout.Wide, val panes: List<CarApp> = listOf(CarApp.Maps, CarApp.Browser), val focus: Int = 1) {
    /** Another layout: the pages shown stay in their order, the next ones fill a new pane, the last ones go. */
    fun laid(l: CarLayout): CarState {
        val apps = (panes.distinct() + CarApp.entries.filter { it !in panes }).take(l.weights.size)
        return CarState(l, apps, focus.coerceIn(apps.indices))
    }
    /**
     * The rail's button (the user, 11 Nov: "when the map ends up in the small window it is very hard to get it back"): the rule is
     * *the tapped page goes to the big pane* (the first, the widest in every layout). The others slide one pane along, keeping their
     * order; a page that was not on the screen pushes the last one off (still running). So the map is always one tap from the big pane,
     * and with two panes the big one's page takes the small one: Maps|Media, Phone › Phone|Maps, Maps › Maps|Phone.
     */
    fun put(app: CarApp): CarState = copy(panes = (listOf(app) + panes.filter { it != app }).take(panes.size), focus = 0)
    /**
     * The dock's tap (the user, 14 Nov: "when I click Media it goes to the big window, I don't want that; only Expose changes positions"):
     * a page on the screen stays where it is; one that is not takes the pane without the map, of those the small one, of two the same
     * size the one further from the driver ([driverRight]: the leftmost, else the rightmost).
     */
    fun docked(app: CarApp, driverRight: Boolean): CarState {
        panes.indexOf(app).takeIf { it >= 0 }?.let { return copy(focus = it) }
        val free = panes.indices.filter { panes[it] != CarApp.Maps }.ifEmpty { panes.indices.toList() }
        val least = free.minOf { layout.weights[it] }
        val small = free.filter { layout.weights[it] == least }
        val at = if (driverRight) small.first() else small.last()
        return copy(panes = panes.toMutableList().also { it[at] = app }, focus = at)
    }
    /** Expose's choice: [app] in pane [i]; when it was in another pane, that pane takes what [i] had (a swap, nothing lost). */
    fun placed(i: Int, app: CarApp): CarState {
        if (i !in panes.indices) return this
        val was = panes.indexOf(app)
        return copy(panes = panes.toMutableList().also { if (was >= 0) it[was] = it[i]; it[i] = app }, focus = i)
    }
    fun focused(i: Int) = copy(focus = i.coerceIn(panes.indices))
    /** [app] beside the map: two panes at least, the map in one, [app] in another, focused. */
    fun beside(app: CarApp): CarState {
        if (app == CarApp.Maps) return put(app)
        var s = if (layout == CarLayout.Full) laid(CarLayout.Wide) else this
        if (CarApp.Maps !in s.panes) s = s.copy(panes = s.panes.toMutableList().also { it[0] = CarApp.Maps })
        s.panes.indexOf(app).takeIf { it >= 0 }?.let { return s.copy(focus = it) }
        val at = s.focus.takeIf { s.panes[it] != CarApp.Maps } ?: s.panes.indices.first { s.panes[it] != CarApp.Maps }
        return s.copy(panes = s.panes.toMutableList().also { it[at] = app }, focus = at)
    }
    val shown get() = panes[focus]
    override fun toString() = "${layout.name}|${panes.joinToString(",") { it.name }}|$focus"
    companion object {
        fun parse(s: String?): CarState = runCatching {
            val (l, p, f) = s!!.split("|")
            CarState(CarLayout.valueOf(l), p.split(",").map(CarApp::valueOf), f.toInt()).let { if (it.panes.distinct().size != it.layout.weights.size) it.laid(it.layout) else it.focused(it.focus) }
        }.getOrDefault(CarState())
    }
}

/** Each pane's left edge and width in [width] pixels, [gap] between them; the last pane takes what rounding left. */
internal fun carSpans(width: Int, gap: Int, weights: List<Float>): List<Pair<Int, Int>> {
    val free = width - gap * (weights.size - 1)
    var x = 0
    return weights.mapIndexed { i, w -> val pw = if (i == weights.lastIndex) width - x else (free * w).roundToInt(); (x to pw).also { x += pw + gap } }
}

/** Live24.gr's tile: the first ♥ station of live24.gr, else Maps' ♪ when it is one, else the site's front page. */
internal fun carLive24(bookmarks: List<Bookmark>, mapsStation: String?): String {
    fun live24(url: String) = runCatching { java.net.URI(url).host.orEmpty().lowercase().let { it == "live24.gr" || it.endsWith(".live24.gr") } }.getOrDefault(false)
    return bookmarks.firstOrNull { live24(it.url) }?.url ?: mapsStation?.takeIf(::live24) ?: "https://www.live24.gr"
}

private fun carPlay(o: JSONObject) = "browser-play:" + o.put("player", true).put("at", System.currentTimeMillis())

/** A pane's own Back: the page in it hears Back only while its pane is the focused one. */
private class PaneBack(override val lifecycle: Lifecycle) : OnBackPressedDispatcherOwner {
    override val onBackPressedDispatcher = OnBackPressedDispatcher()
}

/** Tetra Car; [projected]: drawn for a car's own screen (Tetra Auto, AutoProjection), whose dock opens the car's Share. */
@Composable
internal fun CarScreen(projected: Boolean = false) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("car", Context.MODE_PRIVATE) }
    var state by remember { mutableStateOf(CarState.parse(prefs.getString("state", null))) }
    fun set(s: CarState) { state = s; prefs.edit().putString("state", s.toString()).apply() }
    val opened = remember { mutableStateListOf<CarApp>().apply { addAll(state.panes) } } // pages once shown stay running
    LaunchedEffect(state.panes) { state.panes.forEach { if (it !in opened) opened += it } }
    var play by remember { mutableStateOf<String?>(null) }
    var tiles by remember { mutableStateOf(false) }
    var layouts by remember { mutableStateOf(false) }
    // expose (the user, 11 Nov): every pane shows the pages to choose from; the panes chosen so far, null when it is not up
    var expose by remember { mutableStateOf<Set<Int>?>(null) }
    var apps by remember { mutableStateOf(false) }
    var carShare by remember { mutableStateOf(false) } // the car's build: the menu's Share (CarShare)
    val config = LocalConfiguration.current
    val side = dockFor(Car.side(context), config.screenWidthDp, config.screenHeightDp, Car.driverRight(context)) // Settings › Tetra Car › Dock: changed there, followed here at once
    // AI Assist's «άνοιξε …» reaches the panes while this screen is up
    var message by remember { mutableStateOf<CarMessage?>(null) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        Car.show = { app -> set(state.beside(app)); if (app == CarApp.Browser) tiles = false }
        Car.message = { m -> message = m }
        onDispose { Car.show = null; Car.message = null }
    }
    // AI Assist's TV takes the side pane while it works (the user, 10 Nov: "bring the TV UI to the car, on the small side"); it goes when done
    var assist by remember { mutableStateOf(false) }
    var assistLive by remember { mutableStateOf(false) }
    // the mic (the user, 11 Nov): a tap asks once, held starts a live talk; a tap while live talks ends it (the wheel's voice key is a tap)
    fun mic(live: Boolean) { if (assist && (assistLive || CarMaps.edition) && !live) assist = false else { assistLive = live; assist = true } } // the car build: a tap ends it too, nothing else shows it
    androidx.compose.runtime.DisposableEffect(Unit) { Car.assist = { mic(false) }; onDispose { Car.assist = null } }
    // AI Assist's «πήγαινέ με…», «παίξε…», «άνοιξε τα μηνύματα» (the user, 10 Nov: "it recognised the command but nothing loaded in the
    // car's map"): into the panes here, not a window of their own
    var mapsGo by remember { mutableStateOf<Place?>(null) }
    var mapsSearch by remember { mutableStateOf<String?>(null) }
    var smsStart by remember { mutableStateOf<String?>(null) } // «γράψε μήνυμα…» › Edit: its conversation, the text filled in
    var places by remember { mutableStateOf(false) } // the saved places as bubbles, over the map (Maps in the dock again)
    androidx.compose.runtime.DisposableEffect(Unit) {
        Car.go = { w ->
            fun show(app: CarApp) { set(state.beside(app)); if (app == CarApp.Browser) tiles = false; WriterLog.i("Car: AI Assist's ${w.substringBefore(':')} in $app") }
            when {
                w.startsWith("maps-go:") -> w.removePrefix("maps-go:").split(",", limit = 3).takeIf { it.size == 3 }
                    ?.let { (lat, lng, name) -> mapsGo = Place("ask-${System.nanoTime()}", name, lat.toDouble(), lng.toDouble()); show(CarApp.Maps); true } ?: false
                w.startsWith(MapsActivity.PLACE) -> Place.parse(w.removePrefix(MapsActivity.PLACE))?.let { mapsGo = it; show(CarApp.Maps); true } ?: false
                w.startsWith("maps-search:") -> { mapsSearch = w.removePrefix("maps-search:"); show(CarApp.Maps); true }
                w == Applet.Maps.name -> { show(CarApp.Maps); true }
                w.startsWith("browser-play:") -> { play = w; show(CarApp.Browser); true }
                w.startsWith("youtube:") -> { play = carPlay(JSONObject().put("site", "youtube").put("q", w.removePrefix("youtube:"))); show(CarApp.Browser); true }
                w == Applet.Browser.name -> { show(CarApp.Browser); true }
                w == Applet.Messages.name || w.startsWith("sms:") -> { if (w.startsWith("sms:")) smsStart = w; show(CarApp.Messages); true }
                w == Applet.Phone.name -> { show(CarApp.Phone); true }
                else -> false
            }
        }
        onDispose { Car.go = null }
    }
    // a call or a message takes the side pane for a while (the user, 9 Nov: "replace it … then bring back the app"); the map stays,
    // and with the map alone on the screen, the screen splits until it is over
    val remote by CarWire.call // the phone's call, through the car's link (on a head unit)
    val calling = PhoneCalls.calls.isNotEmpty() || remote != null
    val covering = calling || message != null || (assist && !CarMaps.edition) // the car build: AI Assist with no pane of its own
    val shownState = if (covering && state.panes.all { it == CarApp.Maps }) state.laid(CarLayout.Wide) else state
    val coverAt = if (!covering) null else shownState.panes.indices.let { all -> all.firstOrNull { shownState.panes[it] != CarApp.Maps && it == shownState.focus } ?: all.firstOrNull { shownState.panes[it] != CarApp.Maps } ?: 0 }
    var pausedForCall by remember { mutableStateOf(false) }
    LaunchedEffect(calling) { // the music stops for a call and comes back after it (a message's talk does its own)
        if (calling && BrowserMedia.control != null && BrowserMedia.playing) { pausedForCall = true; BrowserMedia.act("pause") }
        else if (!calling && pausedForCall) { pausedForCall = false; BrowserMedia.act("play") }
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val backs = remember { CarApp.entries.associateWith { PaneBack(lifecycle) } }
    // the home screen never closes on Back: the focused page goes back, else nothing
    BackHandler {
        when {
            apps -> apps = false
            expose != null -> expose = null
            layouts -> layouts = false
            tiles && state.shown == CarApp.Browser -> tiles = false
            else -> backs.getValue(state.shown).onBackPressedDispatcher.let { if (it.hasEnabledCallbacks()) it.onBackPressed() }
        }
    }
    fun media() {
        CarDockApps.used(context, "media")
        val wasShown = CarApp.Browser in state.panes
        set(state.docked(CarApp.Browser, Car.driverRight(context)))
        when {
            wasShown && !tiles -> tiles = true // Media again: back to the three
            BrowserMedia.title.isNotEmpty() && BrowserMedia.control != null -> { tiles = false; play = carPlay(JSONObject()) } // what plays now
            else -> tiles = true
        }
        WriterLog.i("Car: Media${if (tiles) " (tiles)" else ""}")
    }
    fun openApp(app: CarApp) {
        expose = null
        CarDockApps.used(context, app.name.lowercase())
        // Maps again while the map is in the big pane: the saved places (the user, 10 Nov: "the home faves in the car as a page with bubbles")
        if (app == CarApp.Maps && CarApp.Maps in state.panes) places = !places
        else { set(state.docked(app, Car.driverRight(context))); if (app == CarApp.Browser) tiles = false }
    }
    // expose's choice in pane [i]; null: AI Assist, whose TV then takes that pane while it works. Done once every pane has chosen.
    fun pick(i: Int, app: CarApp?) {
        if (app == null) { set(state.focused(i)); expose = null; Car.assist?.invoke() ?: carListen(context); return }
        if (app == CarApp.Browser && app !in opened) tiles = true // Media's first time: its three tiles, not an empty Browser
        set(state.placed(i, app))
        expose = (expose.orEmpty() + i).takeIf { it.size < shownState.panes.size }
        WriterLog.i("Car: expose put $app in pane $i")
    }
    val base = LocalDensity.current
    val view = if (projected || CarMaps.edition) Car.view(context) else CarView.PHONE // the sizes are the car's screen's; Tetra Car on the phone stays as drawn
    var viewPage by remember { mutableStateOf(false) } // the car's menu
    CompositionLocalProvider(LocalDensity provides Density(base.density * view.ui, base.fontScale)) { // Tetra Car's interface size
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        // the car build: AI Assist heard and heard from, nothing shown but the dock's mic flashing (the user, 14 Nov: "remove the TV stuff,
        // no window switching, just a flashing mic"); a tap on the mic again ends it
        LaunchedEffect(assist) { AssistBusy.open.value = assist && CarMaps.edition }
        // the car's log on the phone every 5 minutes, not only as they meet (the user, 14 Nov: the drive's lines were still in the car)
        LaunchedEffect(Unit) { if (CarMaps.edition) while (true) { delay(5 * 60_000L); Thread { runCatching { CarWire.log(context.applicationContext) } }.start() } }
        if (assist && CarMaps.edition) Box(Modifier.size(1.dp).graphicsLayer { alpha = 0f }) { key(assistLive) { AssistantApp(sheet = true, liveFirst = assistLive) { assist = false } } }
        // one layout for both children whichever side the dock is on: the pages are never put elsewhere in the tree (the route, the sound go on)
        Layout(modifier = Modifier.fillMaxSize(), content = {
            CarPanes(shownState, opened, coverAt, cover = {
                if (PhoneCalls.calls.isNotEmpty()) CarCallPane() else remote?.let { CarRemoteCallPane(it) } ?: message?.let { m -> CarSmsPane(m) { message = null } }
                    ?: if (assist && !CarMaps.edition) key(assistLive) { AssistantApp(sheet = true, liveFirst = assistLive) { assist = false } } else Unit
            }, expose = expose?.let { ::pick }, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = if (side == DockSide.Bottom) 0.dp else 8.dp),
                focus = { app -> state.panes.indexOf(app).takeIf { it >= 0 && it != state.focus }?.let { set(state.focused(it)) } }) { app ->
                CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides backs.getValue(app)) {
                    when (app) {
                        CarApp.Maps -> Box(Modifier.fillMaxSize()) {
                            CompositionLocalProvider(LocalDensity provides Density(base.density * view.map, base.fontScale), LocalMapParts provides view.parts()) { // Maps' own size, instead of the interface's; its parts' own
                                key(CarMaps.version.intValue) { PaneSized { MapsScreen(MapsFiles.root(driveRoot()), mapsGo, back = {}, search = mapsSearch) } } // its pane's size in its own dp; again when maps came
                            }
                            if (places) CarPlaces(go = { p -> mapsGo = p.copy(id = "ask-${System.nanoTime()}"); places = false; WriterLog.i("Car: to a saved place") }, close = { places = false })
                            // Tetra Auto SE: the maps from the phone, the first time and whenever it has more or newer
                            var pairPhone by remember { mutableStateOf(false) }
                            CarMapsGuide(pair = { pairPhone = true })
                            if (pairPhone) CarPairDialog { pairPhone = false }
                        }
                        CarApp.Browser -> Box(Modifier.fillMaxSize()) {
                            BrowserScreen(back = {}, play = play, hide = {})
                            if (tiles) CarMediaTiles(Modifier.fillMaxSize()) { o -> play = carPlay(o); tiles = false }
                        }
                        CarApp.Phone -> {
                            val keypad = remember { mutableStateOf(false) }
                            val tab = remember { mutableStateOf(PhoneTab.Recents) }
                            val number = remember { mutableStateOf("") }
                            val missed = remember { mutableStateOf(0) }
                            val blocked = remember { mutableStateOf<String?>(null) }
                            PhoneApp(keypad, tab, number, missed, blocked, back = {})
                        }
                        CarApp.Messages -> key(smsStart) { SmsScreen(back = {}, start = smsStart) }
                    }
                }
            }
            CarDock(state, side, layouts, setLayouts = { layouts = it; expose = null }, layout = { set(state.laid(it)) },
                expose = { expose = if (expose == null) emptySet() else null; layouts = false },
                open = ::openApp, media = ::media, apps = { apps = true }, mic = ::mic,
                clock = { set(state.beside(CarApp.Messages)); WriterLog.i("Car: the clock › messages") }, menu = { viewPage = true }, projected = projected)
        }) { (panes, dock), c ->
            val d = dock.measure(if (side == DockSide.Bottom) c.copy(minHeight = 0) else c.copy(minWidth = 0))
            val w = if (side == DockSide.Bottom) c.maxWidth else c.maxWidth - d.width
            val h = if (side == DockSide.Bottom) c.maxHeight - d.height else c.maxHeight
            val p = panes.measure(Constraints.fixed(w, h))
            layout(c.maxWidth, c.maxHeight) {
                when (side) {
                    DockSide.Bottom, DockSide.Auto -> { p.place(0, 0); d.place(0, h) } // Auto is resolved to one of the others above
                    DockSide.Left -> { d.place(0, 0); p.place(d.width, 0) }
                    DockSide.Right -> { p.place(0, 0); d.place(w, 0) }
                }
            }
        }
        if (apps) CarApps(close = { apps = false }, settings = { // the car's build: Settings itself, the map's view in it under Maps (the user, 14 Nov: "I don't like going through them to press Settings")
            AppLocale.reopenSettings = true
            runCatching { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, share = { carShare = true }) { id -> if (id == "media") media() else CarApp.entries.firstOrNull { it.name.lowercase() == id }?.let(::openApp) } // a page of Tetra's from the grid: as from the dock
        if (viewPage) CarViewPage(::openApp, ::media) { viewPage = false }
        if (carShare) CarSharePage { carShare = false }
    }
    }
}

/** The panes: every page once opened stays composed; the ones not shown wait off the screen, still running. */
@Composable
private fun CarPanes(state: CarState, opened: List<CarApp>, coverAt: Int?, cover: @Composable () -> Unit, expose: ((Int, CarApp?) -> Unit)?,
                     modifier: Modifier, focus: (CarApp) -> Unit, content: @Composable (CarApp) -> Unit) {
    val ring = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(16.dp)
    val widths = remember { mutableMapOf<CarApp, Int>() }
    Layout(modifier = modifier, content = {
        opened.forEach { app ->
            key(app) {
                val on = state.panes.size > 1 && state.shown == app
                Box(Modifier.clip(shape)
                    .pointerInput(app) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); focus(app) } }
                    .drawWithContent { drawContent(); if (on) drawRoundRect(ring, cornerRadius = CornerRadius(16.dp.toPx()), style = Stroke(3.dp.toPx())) }) {
                    CompositionLocalProvider(LocalCarPaneShown provides (app in state.panes)) { PaneSized { content(app) } }
                }
            }
        }
        if (coverAt != null) Box(Modifier.clip(shape).background(MaterialTheme.colorScheme.background)) { PaneSized { cover() } } // over its pane, the page under it still running
        if (expose != null) state.panes.forEachIndexed { i, app -> Box(Modifier.clip(shape)) { CarExpose(app) { expose(i, it) } } } // last: over every pane
    }) { measurables, c ->
        val spans = carSpans(c.maxWidth, 8.dp.roundToPx(), state.layout.weights)
        val covers = if (coverAt != null) 1 else 0
        fun at(i: Int) = when { i < opened.size -> state.panes.indexOf(opened[i]); i < opened.size + covers -> coverAt ?: 0; else -> i - opened.size - covers }
        // a page off the screen keeps the width it last had (the user, 17 Nov: the Browser made as wide as the map behind Phone, then narrow
        // again, left YouTube's page pausing every play until another page loaded)
        val placeables = measurables.mapIndexed { i, m ->
            val w = spans.getOrNull(at(i))?.second?.also { if (i < opened.size) widths[opened[i]] = it } ?: (if (i < opened.size) widths[opened[i]] else null) ?: spans[0].second
            m.measure(Constraints.fixed(w, c.maxHeight)) }
        layout(c.maxWidth, c.maxHeight) {
            placeables.forEachIndexed { i, p ->
                val at = at(i)
                if (at >= 0) p.place(spans[at].first, 0) else p.place(-2 * c.maxWidth, 0)
            }
        }
    }
}

@Composable
private fun CarDock(state: CarState, side: DockSide, layouts: Boolean, setLayouts: (Boolean) -> Unit, layout: (CarLayout) -> Unit, expose: () -> Unit,
                    open: (CarApp) -> Unit, media: () -> Unit, apps: () -> Unit, mic: (live: Boolean) -> Unit, clock: () -> Unit = {}, menu: () -> Unit = {}, projected: Boolean = false) {
    val context = LocalContext.current
    val down = side != DockSide.Bottom // down a side: icons without their names, so it fits a short screen
    val strip: @Composable () -> Unit = { // the layouts' strip, with its way back (the user's sliding strips)
        IconButton({ setLayouts(false) }) { Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.common_back)) }
        CarLayout.entries.forEach { l -> Box(Modifier.size(if (down) DOCK_SIDE else 80.dp, if (down) 46.dp else 56.dp).clip(RoundedCornerShape(12.dp)).clickable { layout(l); setLayouts(false) }, contentAlignment = Alignment.Center) { LayoutGlyph(l, l == state.layout) } }
    }
    val recent: @Composable () -> Unit = { // the dock's apps, the newest last (CarDockApps)
        CarDockApps.all(context).forEach { id ->
            when (id) {
                "maps" -> DockApp(R.drawable.maps_art, R.string.app_maps, CarApp.Maps in state.panes, down) { open(CarApp.Maps) }
                "media" -> DockIcon(R.drawable.ic_play, R.string.car_media, Color(0xFFE53935), down, media)
                "phone" -> DockApp(R.drawable.phone_art, R.string.app_phone, CarApp.Phone in state.panes, down) { open(CarApp.Phone) }
                "messages" -> DockApp(R.drawable.sms_art, R.string.app_sms, CarApp.Messages in state.panes, down) { open(CarApp.Messages) }
                "browser" -> DockApp(R.drawable.browser_art, R.string.app_browser, CarApp.Browser in state.panes, down) { open(CarApp.Browser) }
                else -> ComponentName.unflattenFromString(id.removePrefix("app:"))?.let { DockOther(it, down) }
            }
        }
    }
    val busy = AssistBusy.on.value || AssistBusy.open.value
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "mic").animateFloat(1f, 1.18f,
        androidx.compose.animation.core.infiniteRepeatable<Float>(androidx.compose.animation.core.tween(550), androidx.compose.animation.core.RepeatMode.Reverse), label = "pulse")
    // the apps' grid and AI Assist's mic side by side (the user, 13 Nov: "the assistant mic next to the app menu"), as Android Auto has them
    val group: @Composable () -> Unit = {
        // the car's menu (the user, 13 Nov: "make the share icon a menu icon and put all the rest there"): Share, and the map's sizes and camera
        if (projected) DockIcon(R.drawable.ic_menu, R.string.common_more, MaterialTheme.colorScheme.secondary, down, menu)
        else DockIcon(R.drawable.ic_view_grid, R.string.car_apps, MaterialTheme.colorScheme.secondary, down, apps)
        // the size of the other icons (the user, 13 Nov: "so we can control the panel's width from the icon size")
        DockButton(R.string.app_assistant, false, down, { mic(false) }, long = { mic(true) }) {
            Box(Modifier.size(if (down) 40.dp else 44.dp).graphicsLayer { if (busy) { scaleX = pulse; scaleY = pulse } } // AI Assist at work: the mic breathes
                .clip(CircleShape).background(ASSISTANT_COLOR), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_mic), stringResource(R.string.app_assistant).takeIf { down }, Modifier.size(26.dp), tint = Color.White)
            }
        }
    }
    val layoutButton: @Composable () -> Unit = { // a tap: expose, a page to choose in every pane; held: the layouts' strip
        Box(Modifier.size(if (down) DOCK_SIDE else 72.dp, if (down) 44.dp else 56.dp).clip(RoundedCornerShape(12.dp)).combinedClickable(onLongClick = { setLayouts(true) }, onClick = expose),
            contentAlignment = Alignment.Center) { LayoutGlyph(state.layout, true) }
    }
    if (down && layouts) Column(Modifier.fillMaxHeight().width(DOCK_SIDE).verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) { strip() } // scrolls only on a very short screen
    else if (down) Column(Modifier.fillMaxHeight().width(DOCK_SIDE).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // the time on top, the apps, then the apps' menu, the mic and the layouts' switch at the bottom (the user, 13 Nov); the apps
        // in what room is left, the oldest left out when a bigger size takes it (the user, 13 Nov: bigger made the dock's icons smaller)
        CarStatus(true, clock)
        // every icon the same distance apart, the apps' menu, the mic and the layouts' switch too (the user, 13 Nov: "it hits the eye")
        DockFit(Modifier.weight(1f).fillMaxWidth(), keep = 3) { recent(); group(); layoutButton() }
    }
    else Row(Modifier.fillMaxWidth().height(88.dp).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (layouts) { Spacer(Modifier.weight(1f)); strip(); Spacer(Modifier.weight(1f)); return@Row }
        // as down a side, left to right: the apps, the apps' menu, the mic, the layouts' switch; the time at the end
        NowPlaying(Modifier.weight(1f), media)
        recent()
        group()
        layoutButton()
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { CarStatus(false, clock) }
    }
}

/** The dock down a side: as wide as its icons and the clock (the user, 13 Nov: "the dock's width is still there, the icons are small"). */
private val DOCK_SIDE = 64.dp

/** The dock down a side: the last [keep] always, then as many apps as fit its height, the newest kept (they come last); all spaced evenly. */
@Composable
private fun DockFit(modifier: Modifier, keep: Int, content: @Composable () -> Unit) = Layout(content, modifier) { ms, c ->
    val all = ms.map { it.measure(c.copy(minWidth = 0, minHeight = 0)) }
    val shown = ArrayList(all.takeLast(keep))
    var used = shown.sumOf { it.height }
    for (p in all.dropLast(keep).asReversed()) { if (used + p.height > c.maxHeight) break; used += p.height; shown.add(0, p) }
    val gap = (c.maxHeight - used) / (shown.size + 1)
    layout(c.maxWidth, c.maxHeight) { var y = gap; shown.forEach { it.place((c.maxWidth - it.width) / 2, y); y += it.height + gap } }
}

@Composable
private fun DockApp(art: Int, label: Int, shown: Boolean, down: Boolean, tap: () -> Unit) = DockButton(label, shown, down, tap) { Image(painterResource(art), stringResource(label).takeIf { down }, Modifier.size(if (down) 40.dp else 44.dp)) }

@Composable
private fun DockIcon(icon: Int, label: Int, color: Color, down: Boolean, tap: () -> Unit) = DockButton(label, false, down, tap) {
    Box(Modifier.size(if (down) 40.dp else 44.dp).clip(RoundedCornerShape(12.dp)).background(color), contentAlignment = Alignment.Center) { Icon(painterResource(icon), stringResource(label).takeIf { down }, Modifier.size(26.dp), tint = Color.White) }
}

/** One of the unit's own apps on the dock: its icon and name, a tap opens it beside Tetra Car where the unit allows. */
@Composable
private fun DockOther(component: ComponentName, down: Boolean) {
    val context = LocalContext.current
    val look by produceState<Pair<String, ImageBitmap>?>(null, component) {
        value = withContext(Dispatchers.IO) { runCatching { context.packageManager.getActivityInfo(component, 0).let { i -> i.loadLabel(context.packageManager).toString() to i.loadIcon(context.packageManager).toBitmap(96, 96).asImageBitmap() } }.getOrNull() }
    }
    val (label, icon) = look ?: return
    Column(Modifier.width(76.dp).clip(RoundedCornerShape(12.dp)).clickable {
        runCatching { context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)) }
            .onFailure { WriterLog.w("Car: ${component.flattenToShortString()} not opened", it) }
    }.padding(vertical = if (down) 2.dp else 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(icon, label.takeIf { down }, Modifier.size(if (down) 40.dp else 44.dp).clip(RoundedCornerShape(12.dp)))
        if (!down) Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(7.dp))
    }
}

@Composable
private fun DockButton(label: Int, shown: Boolean, down: Boolean, tap: () -> Unit, long: (() -> Unit)? = null, art: @Composable () -> Unit) {
    Column(Modifier.width(if (down) DOCK_SIDE else 76.dp).clip(RoundedCornerShape(12.dp)).combinedClickable(onLongClick = long, onClick = tap).padding(vertical = if (down) 2.dp else 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        art()
        if (!down) Text(stringResource(label), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.padding(top = 2.dp).size(5.dp).clip(CircleShape).background(if (shown) MaterialTheme.colorScheme.primary else Color.Transparent)) // on the screen now
    }
}


/** Expose in one pane: the pages it can show, [now] ringed (a tap on it keeps it); null is AI Assist. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CarExpose(now: CarApp, pick: (CarApp?) -> Unit) {
    @Composable fun tile(app: CarApp?, label: Int, art: @Composable () -> Unit) {
        Column(Modifier.width(112.dp).clip(RoundedCornerShape(16.dp))
            .then(if (app == now) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)) else Modifier)
            .clickable { pick(app) }.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) { art() }
            Text(stringResource(label), Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    @Composable fun square(color: Color, icon: Int) = Box(Modifier.size(60.dp).clip(RoundedCornerShape(14.dp)).background(color), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(34.dp), tint = Color.White)
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.FlowRow(Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tile(CarApp.Browser, R.string.car_media) { square(Color(0xFFE53935), R.drawable.ic_play) }
            tile(CarApp.Maps, R.string.app_maps) { Image(painterResource(R.drawable.maps_art), null, Modifier.size(60.dp)) }
            tile(null, R.string.app_assistant) {
                Box(Modifier.size(60.dp).clip(CircleShape).background(ASSISTANT_COLOR), contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_mic), null, Modifier.size(34.dp), tint = Color.White) }
            }
            tile(CarApp.Messages, R.string.app_sms) { Image(painterResource(R.drawable.sms_art), null, Modifier.size(60.dp)) }
            tile(CarApp.Phone, R.string.app_phone) { Image(painterResource(R.drawable.phone_art), null, Modifier.size(60.dp)) }
        }
    }
}

/** A layout drawn as its panes. */
@Composable
private fun LayoutGlyph(l: CarLayout, on: Boolean) {
    val color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(48.dp, 30.dp)) {
        val gap = 3.dp.toPx()
        carSpans(size.width.roundToInt(), gap.roundToInt(), l.weights).forEach { (x, w) ->
            drawRoundRect(color, Offset(x.toFloat(), 0f), Size(w.toFloat(), size.height), CornerRadius(4.dp.toPx()), style = if (on) androidx.compose.ui.graphics.drawscope.Fill else Stroke(2.dp.toPx()))
        }
    }
}

/** What the Browser plays, from any layout: ‹ ⏯ › and its name; the name opens Media. */
@Composable
private fun NowPlaying(modifier: Modifier, media: () -> Unit, small: Boolean = false) {
    val now by produceState<Pair<String, Boolean>?>(null) {
        while (true) { value = BrowserMedia.title.takeIf { it.isNotEmpty() && BrowserMedia.control != null }?.let { it to BrowserMedia.playing }; delay(500) }
    }
    val (title, playing) = now ?: run { Spacer(modifier); return }
    if (small) { Row(modifier) { // down a side: ⏯ and ›
        IconButton({ BrowserMedia.act("toggle") }) { Icon(painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play), title) }
        IconButton({ BrowserMedia.act("next") }) { Icon(painterResource(R.drawable.ic_skip_next), null) } }; return }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton({ BrowserMedia.act("prev") }) { Icon(painterResource(R.drawable.ic_skip_previous), null) }
        IconButton({ BrowserMedia.act("toggle") }) { Icon(painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play), null, Modifier.size(32.dp)) }
        IconButton({ BrowserMedia.act("next") }) { Icon(painterResource(R.drawable.ic_skip_next), null) }
        Text(title, Modifier.weight(1f).clickable(onClick = media), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Media's three: each opens in Player mode; Live24.gr's station plays at once. */
@Composable
private fun CarMediaTiles(modifier: Modifier, pick: (JSONObject) -> Unit) {
    val context = LocalContext.current
    val live = remember {
        val p = context.getSharedPreferences("browser", Context.MODE_PRIVATE)
        carLive24(BrowserRules.bookmarks(p.getString("bookmarks", null)), BrowserMedia.station(context)?.first)
    }
    val choices = listOf(
        Triple("YouTube Music", Color(0xFFC62828), JSONObject().put("url", "https://music.youtube.com").put("open", true)),
        Triple("YouTube", Color(0xFFE53935), JSONObject().put("url", "https://m.youtube.com").put("open", true)),
        // Spotify's web player (the user, 9 Nov: "as a webpage add the spotify as well"): its desktop site, as it sends phones to its app
        Triple("Spotify", Color(0xFF1E8E3E), JSONObject().put("url", "https://open.spotify.com").put("open", true).put("desktop", true)),
        Triple("Live24.gr", Color(0xFF1565C0), JSONObject().put("url", live)),
    )
    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
        val tile: @Composable (Modifier, Triple<String, Color, JSONObject>) -> Unit = { m, (name, color, o) ->
            Box(m.clip(RoundedCornerShape(20.dp)).background(color).clickable { WriterLog.i("Car: Media › $name"); pick(o) }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(painterResource(R.drawable.ic_play), null, Modifier.size(40.dp), tint = Color.White)
                    Text(name, color = Color.White, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // a row in a wide pane, a column in a tall one, else two by two
        val rows = when { maxWidth > maxHeight * 1.8f -> listOf(choices); maxHeight > maxWidth * 1.8f -> choices.map { listOf(it) }; else -> choices.chunked(2) }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            rows.forEach { row -> Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { row.forEach { tile(Modifier.weight(1f).fillMaxSize(), it) } } }
        }
    }
}

/** The dock's Apps: every app on the head unit; Android puts it beside Tetra Car where the unit allows, else in front. */
@Composable
private fun CarApps(close: () -> Unit, settings: () -> Unit, share: () -> Unit, page: (String) -> Unit) {
    val context = LocalContext.current
    val all by produceState(emptyList<Triple<ComponentName, String, ImageBitmap>>()) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .filter { it.activityInfo.name != "${CarActivity::class.java.name.substringBeforeLast('.')}.AppCar" }
                .map { r -> Triple(ComponentName(r.activityInfo.packageName, r.activityInfo.name), r.loadLabel(pm).toString(), r.loadIcon(pm).toBitmap(96, 96).asImageBitmap()) }
                .sortedBy { it.second.lowercase() }
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        var pairing by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(close) { Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.common_back)) }
            Text(stringResource(R.string.car_apps), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            // the car's own link to the phone (CarWire): its QR, scanned once by the phone; once paired, the phone's name, green while it
            // answers, and a red ✕ that unpairs (the user, 15 Nov: "red X disconnect, green connected (Xiaomi 15)")
            if (CarMaps.edition || PhoneRelay.noCalls(context)) {
                LaunchedEffect(pairing) { if (!pairing) CarWire.linkName.value = withContext(Dispatchers.IO) { CarWire.link(context)?.name } }
                val phone = CarWire.linkName.value
                if (phone == null) androidx.compose.material3.TextButton({ pairing = true }) {
                    Icon(painterResource(R.drawable.ic_phone), null, Modifier.size(20.dp)); Text("  " + stringResource(R.string.car_pair_phone))
                } else {
                    val on = CarWire.reached.value
                    val green = Color(0xFF2E7D32)
                    Row(Modifier.clip(RoundedCornerShape(50)).background((if (on) green else MaterialTheme.colorScheme.outline).copy(alpha = 0.18f)).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_phone), null, Modifier.size(18.dp), tint = if (on) green else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("  " + if (on) stringResource(R.string.car_phone_connected, phone) else stringResource(R.string.car_phone_away, phone),
                            color = if (on) green else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleSmall)
                    }
                    var unpair by remember { mutableStateOf(false) }
                    androidx.compose.material3.TextButton({ unpair = true }, colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = Color(0xFFD32F2F))) {
                        Icon(painterResource(R.drawable.ic_close), null, Modifier.size(20.dp)); Text(" " + stringResource(R.string.car_phone_disconnect))
                    }
                    if (unpair) androidx.compose.material3.AlertDialog(onDismissRequest = { unpair = false },
                        title = { Text(stringResource(R.string.car_phone_disconnect_ask, phone)) },
                        text = { Text(stringResource(R.string.car_phone_disconnect_note)) },
                        confirmButton = { androidx.compose.material3.TextButton({
                            CarWire.forget(context); CarWire.linkName.value = null; CarWire.reached.value = false; unpair = false
                            WriterLog.i("Car link: unpaired from the car's menu")
                        }) { Text(stringResource(R.string.car_phone_disconnect), color = Color(0xFFD32F2F)) } },
                        dismissButton = { androidx.compose.material3.TextButton({ unpair = false }) { Text(stringResource(R.string.common_cancel)) } })
                }
            }
        }
        if (pairing) CarPairDialog { pairing = false }
        LazyVerticalGrid(GridCells.Adaptive(112.dp), Modifier.fillMaxSize().padding(8.dp)) {
            // the car's build: Tetra Car's own settings first (the map's size, zoom, tilt and arrow, the size of everything), the rest under them
            if (CarMaps.edition) item {
                Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { close(); settings() }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(painterResource(R.drawable.settings_art), null, Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)))
                    Text(stringResource(R.string.common_settings), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            // Share with the phone, both ways (CarShare), as Tetra Auto's Share
            if (CarMaps.edition) item {
                Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { close(); share() }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondary), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_share), null, Modifier.size(32.dp), tint = Color.White)
                    }
                    Text(stringResource(R.string.auto_share), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            // Tetra's own pages with the rest, all apps in one place (the user, 13 Nov: "group all the apps together")
            listOf(Triple("maps", R.drawable.maps_art, R.string.app_maps), Triple("media", 0, R.string.car_media), Triple("phone", R.drawable.phone_art, R.string.app_phone),
                Triple("messages", R.drawable.sms_art, R.string.app_sms), Triple("browser", R.drawable.browser_art, R.string.app_browser)).forEach { (id, art, label) ->
                item(key = id) {
                    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { page(id); close() }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (art != 0) Image(painterResource(art), null, Modifier.size(56.dp))
                        else Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFE53935)), contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_play), null, Modifier.size(32.dp), tint = Color.White) }
                        Text(stringResource(label), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            items(all, key = { it.first.flattenToString() }) { (component, label, icon) ->
                Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                    CarDockApps.used(context, "app:" + component.flattenToString())
                    runCatching { context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)) }
                        .onFailure { WriterLog.w("Car: ${component.flattenToShortString()} not opened", it) }
                    close()
                }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(icon, null, Modifier.size(56.dp))
                    Text(label, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The pages choose phone or tablet layouts by the screen's size: each is told its pane's. */
@Composable
private fun PaneSized(content: @Composable () -> Unit) = BoxWithConstraints {
    val base = LocalConfiguration.current
    val w = maxWidth.value.toInt(); val h = maxHeight.value.toInt()
    val pane = remember(base, w, h) { Configuration(base).apply { screenWidthDp = w; screenHeightDp = h; smallestScreenWidthDp = minOf(w, h) } }
    CompositionLocalProvider(LocalConfiguration provides pane) { content() }
}

/** A call in Tetra Car's side pane: the call's own screen (answer, loudspeaker, hang up) until it ends. */
@Composable
private fun CarCallPane() {
    val context = LocalContext.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        PhoneCalls.screenShown = true; PhoneCalls.changed(context) // its notification quiet: the screen is up
        onDispose { PhoneCalls.screenShown = false; PhoneCalls.changed(context) }
    }
    PhoneCallScreen(done = {})
}

/** The phone's call on the car's screen (CarWire): who, how it stands, and its buttons, pressed on the phone. */
@Composable
private fun CarRemoteCallPane(s: JSONObject) {
    val context = LocalContext.current
    val state = s.optString("state")
    fun press(what: String) = Thread { CarWire.ask(context, JSONObject().put("call", what)).optString("error").takeIf { it.isNotEmpty() }?.let { WriterLog.w("Car: $what: $it") } }.start()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) { while (state == "active") { now = System.currentTimeMillis(); delay(1000) } }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_person), null, Modifier.size(40.dp), tint = Color.White)
        }
        Text(s.optString("name").ifBlank { s.optString("number") }, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(when (state) {
            "active" -> s.optLong("since").takeIf { it > 0 }?.let { ((now - it) / 1000).let { t -> "%d:%02d".format(t / 60, t % 60) } }.orEmpty()
            "ringing" -> stringResource(R.string.phone_answer) + "?"
            else -> ""
        }, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            if (state == "ringing") {
                RoundButton(R.drawable.ic_call_end, stringResource(R.string.phone_decline), CALL_RED, 64.dp) { press("decline") }
                RoundButton(R.drawable.ic_call, stringResource(R.string.phone_answer), CALL_GREEN, 64.dp) { press("answer") }
            } else if (state != "ended") {
                val muted = s.optBoolean("muted"); val loud = s.optBoolean("speaker")
                RoundButton(R.drawable.ic_mic_off, stringResource(if (muted) R.string.phone_unmute else R.string.phone_mute), if (muted) MaterialTheme.colorScheme.primary else null, 56.dp) { press(if (muted) "unmute" else "mute") }
                RoundButton(R.drawable.ic_call_end, stringResource(R.string.phone_end), CALL_RED, 64.dp) { press("end") }
                RoundButton(R.drawable.ic_volume_up, stringResource(R.string.phone_speaker), if (loud) MaterialTheme.colorScheme.primary else null, 56.dp) { press(if (loud) "unspeaker" else "speaker") }
            }
        }
    }
}

/** Tetra Car's code for the phone to scan (Settings › Tetra Car › Pair with the car on the phone), until it is paired. */
@Composable
private fun CarPairDialog(close: () -> Unit) {
    val context = LocalContext.current
    var qr by remember { mutableStateOf<String?>(null) }
    var paired by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val server = runCatching { SyncServer.acquire(context.applicationContext, SyncStore(context.applicationContext)) }.getOrNull()
        qr = server?.let { runCatching { CarWire.carQr(it) }.getOrNull() }
        onDispose { if (server != null) runCatching { SyncServer.release() } }
    }
    val before = remember { CarWire.link(context)?.theirToken }
    LaunchedEffect(Unit) { while (paired == null) { CarWire.link(context)?.takeIf { it.theirToken != before }?.let { paired = it.name }; delay(1000) } }
    val bitmap = remember(qr) { qr?.let { runCatching { qrBitmap(it).asImageBitmap() }.getOrNull() } }
    TetraAlertDialog(onDismissRequest = close, confirmButton = { androidx.compose.material3.TextButton(close) { Text(stringResource(R.string.common_close)) } },
        title = { Text(stringResource(R.string.car_pair_phone)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                paired?.let { Text(stringResource(R.string.codes_paired, it), style = MaterialTheme.typography.titleMedium) }
                    ?: bitmap?.let { Image(it, null, Modifier.size(240.dp).background(Color.White).padding(8.dp)) }
                if (paired == null) Text(stringResource(R.string.car_pair_hint))
            }
        })
}
