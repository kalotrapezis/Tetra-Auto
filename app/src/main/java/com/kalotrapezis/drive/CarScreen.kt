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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WriterLog.init(this)
        TetraLook.load(this)
        if (PhoneLog.recent == null) PhoneLog.warm(this)
        if (ContactsPreload.all == null) ContactsPreload.warm(this)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = TetraLook.light
        WriterLog.i("Tetra Car opened")
        Car.running = true
        CarQuiet.set(this, true)
        // the car's link to the phone (CarWire): this unit's server for the phone's calls and messages, and a hello every 30 s
        server = runCatching { SyncServer.acquire(applicationContext, SyncStore(applicationContext)) }.onFailure { WriterLog.w("Car: no server for the phone", it) }.getOrNull()
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { while (true) { CarWire.hello(applicationContext); kotlinx.coroutines.delay(30_000) } }
        setContent { TetraTheme { CarScreen() } }
    }
    private var server: SyncServer? = null
    override fun onDestroy() { Car.running = false; CarQuiet.set(this, false); if (server != null) runCatching { SyncServer.release() }; super.onDestroy() }
    // the steering wheel's voice key, where the head unit passes it on: AI Assist listens
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOICE_ASSIST || keyCode == KeyEvent.KEYCODE_SEARCH) { carListen(this); return true }
        return super.onKeyDown(keyCode, event)
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
    fun side(c: Context): DockSide = dock.value ?: runCatching { DockSide.valueOf(prefs(c).getString("dock", null)!!) }.getOrDefault(DockSide.Bottom).also { dock.value = it }
    fun setSide(c: Context, d: DockSide) { dock.value = d; prefs(c).edit().putString("dock", d.name).apply() }
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
        .filter { it.activityInfo.name != "${CarActivity::class.java.packageName}.AppCar" }.map { it to fold(it.loadLabel(pm).toString()) }
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
    private fun component(c: Context) = ComponentName(c.packageName, "${CarActivity::class.java.packageName}.AppCar")
    fun shown(c: Context) = c.packageManager.getComponentEnabledSetting(component(c)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    fun show(c: Context, on: Boolean) {
        c.packageManager.setComponentEnabledSetting(component(c),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        WriterLog.i("App icon ${if (on) "shown" else "hidden"}: Car")
    }
}

/** What a pane shows. Media is the Browser too (one Browser: its tabs and its sound), opened on its three tiles. */
internal enum class CarApp { Maps, Browser, Phone, Messages }

/** Where the dock sits (the user, 9 Nov: "κανονικά έχει τη μπάρα στο πλάι"): along the bottom, or down a side. */
internal enum class DockSide { Bottom, Left, Right }

internal enum class CarLayout(val weights: List<Float>) { Full(listOf(1f)), Wide(listOf(0.6f, 0.4f)), Half(listOf(0.5f, 0.5f)), Three(listOf(0.5f, 0.25f, 0.25f)) }

/** The panes left to right, and the one the dock fills ([focus]); kept as "Wide|Maps,Browser|1". */
internal data class CarState(val layout: CarLayout = CarLayout.Wide, val panes: List<CarApp> = listOf(CarApp.Maps, CarApp.Browser), val focus: Int = 1) {
    /** Another layout: the pages shown stay in their order, the next ones fill a new pane, the last ones go. */
    fun laid(l: CarLayout): CarState {
        val apps = (panes.distinct() + CarApp.entries.filter { it !in panes }).take(l.weights.size)
        return CarState(l, apps, focus.coerceIn(apps.indices))
    }
    /** The dock's button: a page already shown is focused where it is, else it takes the focused pane. */
    fun put(app: CarApp): CarState = panes.indexOf(app).let { i -> if (i >= 0) copy(focus = i) else copy(panes = panes.toMutableList().also { it[focus] = app }) }
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

@Composable
private fun CarScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("car", Context.MODE_PRIVATE) }
    var state by remember { mutableStateOf(CarState.parse(prefs.getString("state", null))) }
    fun set(s: CarState) { state = s; prefs.edit().putString("state", s.toString()).apply() }
    val opened = remember { mutableStateListOf<CarApp>().apply { addAll(state.panes) } } // pages once shown stay running
    LaunchedEffect(state.panes) { state.panes.forEach { if (it !in opened) opened += it } }
    var play by remember { mutableStateOf<String?>(null) }
    var tiles by remember { mutableStateOf(false) }
    var layouts by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf(false) }
    val side = Car.side(context) // Settings › Tetra Car › Dock: changed there, followed here at once
    // AI Assist's «άνοιξε …» reaches the panes while this screen is up
    var message by remember { mutableStateOf<CarMessage?>(null) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        Car.show = { app -> set(state.beside(app)); if (app == CarApp.Browser) tiles = false }
        Car.message = { m -> message = m }
        onDispose { Car.show = null; Car.message = null }
    }
    // a call or a message takes the side pane for a while (the user, 9 Nov: "replace it … then bring back the app"); the map stays,
    // and with the map alone on the screen, the screen splits until it is over
    val remote by CarWire.call // the phone's call, through the car's link (on a head unit)
    val calling = PhoneCalls.calls.isNotEmpty() || remote != null
    val covering = calling || message != null
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
            layouts -> layouts = false
            tiles && state.shown == CarApp.Browser -> tiles = false
            else -> backs.getValue(state.shown).onBackPressedDispatcher.let { if (it.hasEnabledCallbacks()) it.onBackPressed() }
        }
    }
    fun media() {
        val wasShown = state.shown == CarApp.Browser
        set(state.put(CarApp.Browser))
        when {
            wasShown && !tiles -> tiles = true // Media again: back to the three
            BrowserMedia.title.isNotEmpty() && BrowserMedia.control != null -> { tiles = false; play = carPlay(JSONObject()) } // what plays now
            else -> tiles = true
        }
        WriterLog.i("Car: Media${if (tiles) " (tiles)" else ""}")
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        // one layout for both children whichever side the dock is on: the pages are never put elsewhere in the tree (the route, the sound go on)
        Layout(modifier = Modifier.fillMaxSize(), content = {
            CarPanes(shownState, opened, coverAt, cover = {
                if (PhoneCalls.calls.isNotEmpty()) CarCallPane() else remote?.let { CarRemoteCallPane(it) } ?: message?.let { m -> CarSmsPane(m) { message = null } }
            }, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = if (side == DockSide.Bottom) 0.dp else 8.dp),
                focus = { app -> state.panes.indexOf(app).takeIf { it >= 0 && it != state.focus }?.let { set(state.focused(it)) } }) { app ->
                CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides backs.getValue(app)) {
                    when (app) {
                        CarApp.Maps -> MapsScreen(MapsFiles.root(driveRoot()), null, back = {})
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
                        CarApp.Messages -> SmsScreen(back = {})
                    }
                }
            }
            CarDock(state, side, layouts, setLayouts = { layouts = it }, layout = { set(state.laid(it)) },
                open = { set(state.put(it)); if (it == CarApp.Browser) tiles = false }, media = ::media, apps = { apps = true })
        }) { (panes, dock), c ->
            val d = dock.measure(if (side == DockSide.Bottom) c.copy(minHeight = 0) else c.copy(minWidth = 0))
            val w = if (side == DockSide.Bottom) c.maxWidth else c.maxWidth - d.width
            val h = if (side == DockSide.Bottom) c.maxHeight - d.height else c.maxHeight
            val p = panes.measure(Constraints.fixed(w, h))
            layout(c.maxWidth, c.maxHeight) {
                when (side) {
                    DockSide.Bottom -> { p.place(0, 0); d.place(0, h) }
                    DockSide.Left -> { d.place(0, 0); p.place(d.width, 0) }
                    DockSide.Right -> { p.place(0, 0); d.place(w, 0) }
                }
            }
        }
        if (apps) CarApps { apps = false }
    }
}

/** The panes: every page once opened stays composed; the ones not shown wait off the screen, still running. */
@Composable
private fun CarPanes(state: CarState, opened: List<CarApp>, coverAt: Int?, cover: @Composable () -> Unit, modifier: Modifier, focus: (CarApp) -> Unit, content: @Composable (CarApp) -> Unit) {
    val ring = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(16.dp)
    Layout(modifier = modifier, content = {
        opened.forEach { app ->
            key(app) {
                val on = state.panes.size > 1 && state.shown == app
                Box(Modifier.clip(shape)
                    .pointerInput(app) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); focus(app) } }
                    .drawWithContent { drawContent(); if (on) drawRoundRect(ring, cornerRadius = CornerRadius(16.dp.toPx()), style = Stroke(3.dp.toPx())) }) {
                    PaneSized { content(app) }
                }
            }
        }
        if (coverAt != null) Box(Modifier.clip(shape).background(MaterialTheme.colorScheme.background)) { PaneSized { cover() } } // last: over its pane, the page under it still running
    }) { measurables, c ->
        val spans = carSpans(c.maxWidth, 8.dp.roundToPx(), state.layout.weights)
        val placeables = measurables.mapIndexed { i, m ->
            val at = if (i < opened.size) state.panes.indexOf(opened[i]) else coverAt ?: 0
            m.measure(Constraints.fixed((spans.getOrNull(at) ?: spans[0]).second, c.maxHeight))
        }
        layout(c.maxWidth, c.maxHeight) {
            placeables.forEachIndexed { i, p ->
                val at = if (i < opened.size) state.panes.indexOf(opened[i]) else coverAt ?: 0
                if (at >= 0) p.place(spans[at].first, 0) else p.place(-2 * c.maxWidth, 0)
            }
        }
    }
}

@Composable
private fun CarDock(state: CarState, side: DockSide, layouts: Boolean, setLayouts: (Boolean) -> Unit, layout: (CarLayout) -> Unit,
                    open: (CarApp) -> Unit, media: () -> Unit, apps: () -> Unit) {
    val context = LocalContext.current
    val down = side != DockSide.Bottom // down a side: icons without their names, so it fits a short screen
    val strip: @Composable () -> Unit = { // the layouts' strip, with its way back (the user's sliding strips)
        IconButton({ setLayouts(false) }) { Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.common_back)) }
        CarLayout.entries.forEach { l -> Box(Modifier.size(80.dp, if (down) 46.dp else 56.dp).clip(RoundedCornerShape(12.dp)).clickable { layout(l); setLayouts(false) }, contentAlignment = Alignment.Center) { LayoutGlyph(l, l == state.layout) } }
    }
    val buttons: @Composable () -> Unit = {
        DockApp(R.drawable.maps_art, R.string.app_maps, CarApp.Maps in state.panes, down) { open(CarApp.Maps) }
        DockIcon(R.drawable.ic_play, R.string.car_media, Color(0xFFE53935), down, media)
        DockApp(R.drawable.phone_art, R.string.app_phone, CarApp.Phone in state.panes, down) { open(CarApp.Phone) }
        Box(Modifier.padding(if (down) 4.dp else 12.dp).size(if (down) 56.dp else 68.dp).clip(CircleShape).background(ASSISTANT_COLOR).clickable { carListen(context) }, contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_mic), stringResource(R.string.app_assistant), Modifier.size(34.dp), tint = Color.White)
        }
        // down a side only Maps, Media and Phone stay; Messages and the Browser while they are on the screen (the user, 9 Nov)
        if (!down || CarApp.Messages in state.panes) DockApp(R.drawable.sms_art, R.string.app_sms, CarApp.Messages in state.panes, down) { open(CarApp.Messages) }
        if (!down || CarApp.Browser in state.panes) DockApp(R.drawable.browser_art, R.string.app_browser, CarApp.Browser in state.panes, down) { open(CarApp.Browser) }
        DockIcon(R.drawable.ic_view_grid, R.string.car_apps, MaterialTheme.colorScheme.secondary, down, apps)
    }
    val layoutButton: @Composable () -> Unit = {
        Box(Modifier.size(72.dp, if (down) 44.dp else 56.dp).clip(RoundedCornerShape(12.dp)).clickable { setLayouts(true) }, contentAlignment = Alignment.Center) { LayoutGlyph(state.layout, true) }
    }
    if (down) Column(Modifier.fillMaxHeight().width(96.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) { // scrolls only on a very short screen
        if (layouts) strip() else { NowPlaying(Modifier, media, small = true); buttons(); layoutButton() }
    }
    else Row(Modifier.fillMaxWidth().height(88.dp).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (layouts) { Spacer(Modifier.weight(1f)); strip(); Spacer(Modifier.weight(1f)); return@Row }
        NowPlaying(Modifier.weight(1f), media)
        buttons()
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { layoutButton() }
    }
}

@Composable
private fun DockApp(art: Int, label: Int, shown: Boolean, down: Boolean, tap: () -> Unit) = DockButton(label, shown, down, tap) { Image(painterResource(art), stringResource(label).takeIf { down }, Modifier.size(if (down) 40.dp else 44.dp)) }

@Composable
private fun DockIcon(icon: Int, label: Int, color: Color, down: Boolean, tap: () -> Unit) = DockButton(label, false, down, tap) {
    Box(Modifier.size(if (down) 40.dp else 44.dp).clip(RoundedCornerShape(12.dp)).background(color), contentAlignment = Alignment.Center) { Icon(painterResource(icon), stringResource(label).takeIf { down }, Modifier.size(26.dp), tint = Color.White) }
}

@Composable
private fun DockButton(label: Int, shown: Boolean, down: Boolean, tap: () -> Unit, art: @Composable () -> Unit) {
    Column(Modifier.width(76.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = tap).padding(vertical = if (down) 2.dp else 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        art()
        if (!down) Text(stringResource(label), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.padding(top = 2.dp).size(5.dp).clip(CircleShape).background(if (shown) MaterialTheme.colorScheme.primary else Color.Transparent)) // on the screen now
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
private fun CarApps(close: () -> Unit) {
    val context = LocalContext.current
    val all by produceState(emptyList<Triple<ComponentName, String, ImageBitmap>>()) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .filter { it.activityInfo.name != "${CarActivity::class.java.packageName}.AppCar" }
                .map { r -> Triple(ComponentName(r.activityInfo.packageName, r.activityInfo.name), r.loadLabel(pm).toString(), r.loadIcon(pm).toBitmap(96, 96).asImageBitmap()) }
                .sortedBy { it.second.lowercase() }
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        var pairing by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(close) { Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.common_back)) }
            Text(stringResource(R.string.car_apps), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            // the car's own link to the phone (CarWire): its QR, scanned once by the phone
            if (PhoneRelay.noCalls(context)) androidx.compose.material3.TextButton({ pairing = true }) {
                Icon(painterResource(R.drawable.ic_phone), null, Modifier.size(20.dp)); Text("  " + stringResource(R.string.car_pair_phone))
            }
        }
        if (pairing) CarPairDialog { pairing = false }
        LazyVerticalGrid(GridCells.Adaptive(112.dp), Modifier.fillMaxSize().padding(8.dp)) {
            items(all, key = { it.first.flattenToString() }) { (component, label, icon) ->
                Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable {
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
    androidx.compose.material3.AlertDialog(onDismissRequest = close, confirmButton = { androidx.compose.material3.TextButton(close) { Text(stringResource(R.string.common_close)) } },
        title = { Text(stringResource(R.string.car_pair_phone)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                paired?.let { Text(stringResource(R.string.codes_paired, it), style = MaterialTheme.typography.titleMedium) }
                    ?: bitmap?.let { Image(it, null, Modifier.size(240.dp).background(Color.White).padding(8.dp)) }
                if (paired == null) Text(stringResource(R.string.car_pair_hint))
            }
        })
}
