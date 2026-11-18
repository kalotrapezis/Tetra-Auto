package com.kalotrapezis.drive

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Presentation
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.ActivityOptionsCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Tetra Auto's cars (the user, 10 Nov: "compute on phone, code on phone, apps on phone … the phone is the server and the car a
 * dumb client"): each paired car's name and token, kept here only; and the code of the car being paired, for ten minutes after
 * its QR was scanned (Codes, Settings › Tetra Car › Pair with the car).
 */
internal object AutoCars {
    data class Car(val name: String, val token: String)
    private fun prefs(c: Context) = c.getSharedPreferences("auto_cars", Context.MODE_PRIVATE)
    fun all(c: Context): List<Car> = runCatching { JSONArray(prefs(c).getString("cars", "[]")).let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> Car(o.getString("name"), o.getString("token")) } } } }.getOrDefault(emptyList())
    private fun save(c: Context, cars: List<Car>) = prefs(c).edit().putString("cars", JSONArray(cars.map { JSONObject().put("name", it.name).put("token", it.token) }).toString()).apply()
    fun forget(c: Context, car: Car) { save(c, all(c) - car); WriterLog.i("Tetra Auto: forgot ${car.name}") }

    @Volatile private var pending: Triple<String, String, Long>? = null
    /** The car just paired: the scanner that read its code says so and closes. */
    val justPaired = androidx.compose.runtime.mutableStateOf<String?>(null)

    /** The car's pairing QR ({tetraAuto, code, name}): its code and name; null for any other code. */
    fun code(raw: String): Pair<String, String>? = runCatching { JSONObject(raw).takeIf { it.optInt("tetraAuto") == AutoWire.VERSION }
        ?.let { o -> o.getString("code").takeIf { it.matches(Regex("[A-Z0-9]{10}")) }?.let { it to o.optString("name").take(60).ifBlank { "Car" } } } }.getOrNull()

    /** The QR was scanned: the car that says this code in the next ten minutes is paired, and the projection waits for it. */
    fun expect(c: Context, code: String, name: String) {
        pending = Triple(code, name, SystemClock.elapsedRealtime() + 10 * 60_000)
        WriterLog.i("Tetra Auto: waiting for $name to pair")
        AutoProjection.start(c)
    }

    /** A car's hello: its car (and a new token when it was the one being paired), or why not. */
    fun admit(c: Context, hello: JSONObject): Result<Pair<Car, Boolean>> = runCatching {
        hello.optString("token").takeIf { it.isNotEmpty() }?.let { t -> all(c).firstOrNull { it.token == t }?.let { return@runCatching it to false }; error("unpaired") }
        val p = pending
        require(p != null && SystemClock.elapsedRealtime() < p.third && hello.optString("code") == p.first) { "Scan this car's code with Tetra on the phone first." }
        pending = null
        val token = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        Car(hello.optString("name").take(60).ifBlank { p.second }, token).also { save(c, all(c) + it); justPaired.value = it.name; WriterLog.i("Tetra Auto: paired with ${it.name}") } to true
    }
}

/** True inside Tetra Car drawn for a car's screen: a page says less there (Maps without a map: download one on the phone). */
internal val LocalOnCar = androidx.compose.runtime.staticCompositionLocalOf { false }

/** Starting and stopping the projection, and the session there is now. */
internal object AutoProjection {
    @Volatile var session: AutoSession? = null
    fun start(c: Context) {
        runCatching { c.startForegroundService(Intent(c, AutoProjectionService::class.java)) }.onFailure { WriterLog.w("Tetra Auto: not started (${it.message})") }
    }
    /** At start, at boot and as the car's Bluetooth joins: only when a car was ever paired. */
    fun startIfPaired(c: Context) { if (AutoCars.all(c).isNotEmpty()) start(c) }
    fun stop(c: Context) = c.stopService(Intent(c, AutoProjectionService::class.java))
}

/**
 * Tetra Car for the car's screen, drawn here: a foreground service that says where the phone is (a broadcast every 2 s), takes
 * one car at a time, and draws Tetra Car on a private screen of the car's size whose picture goes, as H.264, to the car. The
 * phone's own screen may be off. It stops itself after half an hour with no car.
 */
class AutoProjectionService : Service() {
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var idleSince = SystemClock.elapsedRealtime()
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.wrap(base)) // Tetra Car in the app's language

    override fun onCreate() {
        super.onCreate()
        WriterLog.init(this)
        TetraLook.load(this)
        // Tetra stopping with a car on the line (a crash): the projection back in 3 s, so the car's screen comes back by itself
        val before = Thread.getDefaultUncaughtExceptionHandler()
        if (before !is RestartOnCrash) Thread.setDefaultUncaughtExceptionHandler(RestartOnCrash(applicationContext, before))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { WriterLog.i("Tetra Auto: stopped from its notification"); stopSelf(); return START_NOT_STICKY }
        val n = note(null)
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(NOTE, n)
        if (!running) {
            running = true
            Thread({ serve() }, "auto-server").start()
            Thread({ beacon() }, "auto-beacon").start()
            WriterLog.i("Tetra Auto: ready for the car")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        runCatching { server?.close() }
        AutoProjection.session?.end()
        super.onDestroy()
    }

    private fun serve() {
        try {
            val s = ServerSocket().apply { reuseAddress = true; bind(java.net.InetSocketAddress(AutoWire.PORT)) }
            server = s
            while (running) {
                val socket = runCatching { s.accept() }.getOrNull() ?: continue
                socket.tcpNoDelay = true
                // a small send buffer: on a weak link the kernel would hold seconds of picture (the user, 12 Nov: "sits there, then
                // answers every touch at once after 2-3 s"); with this the drain sees it at once and skips to a fresh picture
                runCatching { socket.sendBufferSize = 128 * 1024 }
                Thread({
                    val session = AutoSession(this, socket, main) { name -> update(name) }
                    session.run() // it takes AutoProjection.session once its car is admitted
                    if (AutoProjection.session === session) AutoProjection.session = null
                    idleSince = SystemClock.elapsedRealtime()
                    update(null)
                }, "auto-session").start()
            }
        } catch (e: Exception) { if (running) WriterLog.e("Tetra Auto: the server stopped", e) }
    }

    /** "TETRA-AUTO 1 43190 <phone>" to every Wi-Fi's broadcast address (the hotspot's among them), every 2 s. */
    private fun beacon() {
        val msg = "${AutoWire.BEACON} ${AutoWire.VERSION} ${AutoWire.PORT} ${Build.MODEL}".toByteArray()
        runCatching { DatagramSocket().use { d ->
            d.broadcast = true
            while (running) {
                if (AutoProjection.session == null) {
                    val targets = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                        .flatMap { it.interfaceAddresses.mapNotNull { a -> a.broadcast } } + InetAddress.getByName("255.255.255.255")
                    targets.distinct().forEach { t -> runCatching { d.send(DatagramPacket(msg, msg.size, t, AutoWire.BEACON_PORT)) } }
                    // no car for half an hour: the car's Bluetooth or Tetra Car starts it again
                    if (SystemClock.elapsedRealtime() - idleSince > 30 * 60_000) { main.post { stopSelf() }; return@use }
                }
                Thread.sleep(2000)
            }
        } }.onFailure { WriterLog.w("Tetra Auto: beacon (${it.message})") }
    }

    private fun update(car: String?) = runCatching { getSystemService(NotificationManager::class.java).notify(NOTE, note(car)) }

    private fun note(car: String?): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL)?.name != getString(R.string.auto_channel))
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.auto_channel), NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        val stop = PendingIntent.getService(this, NOTE, Intent(this, AutoProjectionService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_directions_car).setOngoing(true)
            .setContentTitle(if (car != null) getString(R.string.auto_on_car, car) else getString(R.string.auto_ready))
            .setContentText(getString(R.string.auto_note_text)).addAction(0, getString(R.string.auto_stop), stop).build()
    }

    companion object { private const val NOTE = 4170; private const val CHANNEL = "tetra_auto_nobadge"; private const val STOP = "stop" }
}

/** The process ending in a crash while a car shows Tetra Car: an exact alarm starts the projection again (the user, 10 Nov). */
private class RestartOnCrash(private val app: Context, private val next: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread, e: Throwable) {
        if (AutoProjection.session != null) runCatching {
            val pi = PendingIntent.getForegroundService(app, 4171, Intent(app, AutoProjectionService::class.java), PendingIntent.FLAG_IMMUTABLE)
            app.getSystemService(android.app.AlarmManager::class.java).setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3000, pi)
        }
        next?.uncaughtException(t, e)
    }
}

/**
 * One car on the line: its hello, then Tetra Car drawn for it and its touches played into it, and Share both ways. Everything
 * Tetra Car needs from an Activity is given here: a lifecycle, saved state, Back, and a stand-in for "ask Android" (a
 * permission or a role is asked on the phone itself, where the user answers it).
 */
internal class AutoSession(private val service: Service, private val socket: Socket, private val main: Handler, private val shown: (String?) -> Unit) :
    LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner, OnBackPressedDispatcherOwner, ActivityResultRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore = ViewModelStore()
    override val onBackPressedDispatcher = OnBackPressedDispatcher()
    override val activityResultRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            contract.getSynchronousResult(service, input)?.let { dispatchResult(requestCode, it.value); return }
            WriterLog.i("Tetra Auto: Android asks something (${contract.javaClass.simpleName}): on the phone")
            main.post { Toast.makeText(service, R.string.auto_answer_on_phone, Toast.LENGTH_LONG).show() }
            runCatching { service.startActivity(contract.createIntent(service, input).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            dispatchResult(requestCode, Activity.RESULT_CANCELED, null)
        }
    }

    private lateinit var out: DataOutputStream
    @Volatile private var ended = false
    private var car: AutoCars.Car? = null
    private var width = 0; private var height = 0
    /** The screen size the car asked for (its hello); the picture may be drawn smaller (Tetra Car's menu › Resolution). */
    @Volatile var asked = 1024 to 600
    private var encoder: MediaCodec? = null
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    fun run() {
        try {
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 1 shl 16))
            out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 1 shl 16))
            socket.soTimeout = 10_000
            val (type, body) = AutoWire.read(input)
            if (type != AutoWire.HELLO) return
            val hello = JSONObject(String(body))
            val admitted = AutoCars.admit(service, hello).getOrElse { e ->
                send(AutoWire.REFUSED, JSONObject().put("error", e.message).put("unpaired", e.message == "unpaired").toString().toByteArray())
                WriterLog.i("Tetra Auto: a car refused (${e.message})"); return
            }
            car = admitted.first
            // one car at a time: the newest admitted wins (the car came back); a stranger on the port never ends the car's picture
            AutoProjection.session?.takeIf { it !== this }?.end()
            AutoProjection.session = this
            send(AutoWire.WELCOME, JSONObject().put("name", "${Build.MANUFACTURER} ${Build.MODEL}").apply { if (admitted.second) put("token", admitted.first.token) }.toString().toByteArray())
            asked = hello.optInt("width", 1024).coerceIn(320, 2560) to hello.optInt("height", 600).coerceIn(240, 1600)
            val r = Car.view(service).res // Tetra Car's menu › Resolution: smaller, so all is bigger and sharper on the car
            width = (asked.first * r).toInt().coerceAtLeast(320) and 1.inv(); height = (asked.second * r).toInt().coerceAtLeast(240) and 1.inv()
            WriterLog.i("Tetra Auto: ${admitted.first.name} connected, ${width}x$height at ${hello.optInt("dpi")} dpi, ${link()}")
            shown(admitted.first.name)
            hold(true)
            val ready = java.util.concurrent.CountDownLatch(1)
            main.post { runCatching { open(hello.optInt("dpi", 160).coerceIn(100, 480)) }.onFailure { WriterLog.e("Tetra Auto: Tetra Car not drawn", it); end() }; ready.countDown() }
            ready.await()
            Thread({ pings() }, "auto-ping").start()
            // a car that says hello every 5 s (Tetra Auto 0.2) and goes silent is gone: its picture stops and the phone is free for it again
            socket.soTimeout = if (hello.optBoolean("pings")) 20_000 else 0
            while (!ended) {
                val (t, b) = AutoWire.read(input)
                when (t) {
                    AutoWire.TOUCH -> { val at = SystemClock.uptimeMillis(); main.post { waited(at); touch(b) } }
                    AutoWire.KEY -> main.post { key(ByteBuffer.wrap(b).int) }
                    AutoWire.KEYFRAME -> runCatching { encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
                    AutoWire.FILE_BEGIN, AutoWire.FILE_DATA, AutoWire.FILE_END -> incoming(t, b)
                    AutoWire.FILE_DONE -> answered(JSONObject(String(b)))
                }
            }
        } catch (e: Exception) {
            if (!ended) WriterLog.i("Tetra Auto: the car went (${e.message})")
        } finally { end() }
    }

    fun end() {
        if (ended) return
        ended = true
        runCatching { socket.close() }
        main.post { close() }
        hold(false)
        car?.let { WriterLog.i("Tetra Auto: ${it.name} disconnected") }
    }

    /** The phone stays awake and on Wi-Fi while a car shows Tetra Car; Tetra Car's own state (quiet, messages read) follows. */
    private fun hold(on: Boolean) {
        Car.running = on
        CarQuiet.set(service, on)
        if (on) {
            wake = service.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tetra:auto").apply { acquire(12 * 3600_000L) }
            wifiLock = service.applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "tetra:auto").apply { runCatching { acquire() } }
        } else {
            runCatching { wake?.release() }; runCatching { wifiLock?.release() }
            wake = null; wifiLock = null
        }
    }

    /** What Tetra Car's screen sends (its dock's Share) goes out on this thread: Android allows no network on the screen's own. */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun send(type: Byte, body: ByteArray, off: Int = 0, len: Int = body.size): Boolean {
        val write = { runCatching { AutoWire.write(out, type, body, off, len) }.onFailure { WriterLog.w("Tetra Auto: not sent (${it.message})"); end() }.isSuccess }
        if (Looper.myLooper() != Looper.getMainLooper()) return write()
        writer.execute { write() }
        return true
    }

    private fun pings() { while (!ended) { Thread.sleep(5000); if (!ended) send(AutoWire.PING, ByteArray(0)) } }

    /** The encoder, the private screen it reads and Tetra Car on it. On the main thread. */
    private fun open(dpi: Int) {
        if (ended) return
        if (CalendarIndex.dir == null) CalendarIndex.dir = service.cacheDir
        if (NotesStore.indexDir == null) NotesStore.indexDir = service.cacheDir
        if (PhoneLog.recent == null) PhoneLog.warm(service)
        if (ContactsPreload.all == null) ContactsPreload.warm(service)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * 4).coerceIn(2_000_000, 6_000_000)) // a car's Wi-Fi is no better than this
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000) // a still screen still sends: the car stays current
        }
        val e = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        e.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = e.createInputSurface()
        e.start()
        encoder = e
        Thread({ drain(e) }, "auto-encoder").start()
        val d = service.getSystemService(DisplayManager::class.java).createVirtualDisplay("Tetra Auto", width, height, dpi, surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
        display = d
        panel(d)
    }

    /**
     * Tetra Car on a panel (a Presentation) on that screen. Not an Activity: Android lets only system apps open one on a screen like this
     * (ACTIVITY_EMBEDDING), so its dialogs are layers of the panel (CarDialogs.kt), and AI Assist is its side pane.
     */
    private fun panel(d: VirtualDisplay) {
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
        val p = Presentation(service, d.display)
        // what Tetra Car opens (a call's screen, another app) opens on the phone: from here it needs a task of its own, and a refusal must not
        // end the picture on the car
        // in Tetra's own language: the panel's own context follows the display's (the system's) and changed language when a menu opened
        val chosen = if (Build.VERSION.SDK_INT >= 33) service.getSystemService(android.app.LocaleManager::class.java).applicationLocales.takeIf { !it.isEmpty } else null
        val inLanguage = chosen?.let { p.context.createConfigurationContext(android.content.res.Configuration(p.context.resources.configuration).apply { setLocales(it) }) }
            ?: AppLocale.wrap(p.context) // Android 13+: the app's language is Android's own per-app setting, which a panel's context does not get
        val safe = object : android.content.ContextWrapper(inLanguage) {
            override fun startActivity(intent: Intent) = startActivity(intent, null)
            override fun startActivity(intent: Intent, options: Bundle?) { runCatching { super.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options) }.onFailure { WriterLog.w("Tetra Auto: not opened (${it.message})") } }
        }
        val view = ComposeView(safe).apply {
            setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides this@AutoSession, LocalOnCar provides true) { TetraTheme { CarOverlayHost { CarScreen(projected = true) } } } }
        }
        p.setContentView(view)
        p.window!!.decorView.let { v ->
            v.setViewTreeLifecycleOwner(this); v.setViewTreeSavedStateRegistryOwner(this); v.setViewTreeViewModelStoreOwner(this); v.setViewTreeOnBackPressedDispatcherOwner(this)
        }
        p.show()
        presentation = p
    }

    private fun close() {
        // a car gone before Tetra Car was drawn never left INITIALIZED, and a lifecycle may not go from there to DESTROYED
        if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) registry.currentState = Lifecycle.State.DESTROYED
        runCatching { presentation?.dismiss() }; presentation = null
        runCatching { display?.release() }; display = null
        encoder?.let { e -> encoder = null; runCatching { e.signalEndOfInputStream() }; runCatching { e.stop() }; runCatching { e.release() } }
        viewModelStore.clear()
    }

    /** The encoder's output to the car: its config once, then each picture. */
    private fun drain(e: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var buf = ByteArray(1 shl 16)
        while (!ended && encoder === e) {
            val i = runCatching { e.dequeueOutputBuffer(info, 50_000) }.getOrElse { return }
            if (i < 0) continue
            val b = runCatching { e.getOutputBuffer(i) }.getOrNull()
            if (b != null && info.size > 0) {
                b.position(info.offset); b.limit(info.offset + info.size)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    send(AutoWire.VIDEO_CONFIG, ByteArray(info.size).also { b.get(it) })
                } else if (stale(e, info)) {
                    // behind: this picture is old; skipped until a fresh whole one, asked for once
                } else {
                    if (buf.size < info.size + 9) buf = ByteArray(info.size + 9 + (1 shl 16))
                    buf[0] = if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) 1 else 0
                    ByteBuffer.wrap(buf, 1, 8).putLong(info.presentationTimeUs)
                    b.get(buf, 9, info.size)
                    send(AutoWire.VIDEO, buf, 0, info.size + 9)
                }
            }
            runCatching { e.releaseOutputBuffer(i, false) }
        }
    }

    private var downAt = 0L

    /**
     * Which Wi-Fi the car came over (the user, 12 Nov: the hotspot is 2.4 GHz for the vacuum and the printer; the car's own
     * CarLinkAP is 5 GHz, and Android Auto joins the phone to it): a network the phone joined, with its band, or the phone's hotspot.
     */
    private fun link(): String = runCatching {
        val iface = java.net.NetworkInterface.getByInetAddress(socket.localAddress)?.name ?: "?"
        val cm = service.getSystemService(android.net.ConnectivityManager::class.java)
        val joined = cm.allNetworks.firstOrNull { cm.getLinkProperties(it)?.interfaceName == iface }
        val mhz = joined?.let { (cm.getNetworkCapabilities(it)?.transportInfo as? android.net.wifi.WifiInfo)?.frequency } ?: 0
        "over $iface " + when {
            joined == null -> "(the phone's hotspot)"
            mhz > 0 -> "(a Wi-Fi the phone joined, ${if (mhz >= 5000) "5" else "2.4"} GHz, $mhz MHz)"
            else -> "(a Wi-Fi the phone joined)"
        } + ", car ${socket.inetAddress.hostAddress}"
    }.getOrElse { "link unknown (${it.message})" }

    // —— keeping up (the user, 12 Nov: "very slow to respond to touches, sits there, then every touch at once after 2-3 s") ——

    private var behindSince = 0L // the picture fell behind at (ms), 0 while current
    private var skipped = 0
    private var clockBase = Long.MAX_VALUE // now − a picture's time at its quickest: whatever clock the encoder's times are on
    /**
     * A picture made more than 250 ms ago (the link was slow and the encoder's output waited): skipped, and the encoder asked for a
     * fresh whole picture, sent as soon as it comes; the car shows that, not the backlog.
     */
    private fun stale(e: MediaCodec, info: MediaCodec.BufferInfo): Boolean {
        val raw = System.nanoTime() / 1000_000 - info.presentationTimeUs / 1000
        clockBase = minOf(clockBase, raw)
        val lagMs = raw - clockBase
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (behindSince == 0L) {
            if (lagMs < 250) return false
            behindSince = SystemClock.uptimeMillis(); skipped = 0
            runCatching { e.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
        }
        if (key && lagMs < 250) {
            WriterLog.i("Tetra Auto: the picture was ${SystemClock.uptimeMillis() - behindSince} ms behind (Wi-Fi to the car slow), $skipped old pictures skipped")
            behindSince = 0; return false
        }
        skipped++
        // a fresh whole picture asked for again every second while still behind
        if (skipped % 30 == 0) runCatching { e.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
        return true
    }

    private var waitLogged = 0L
    /** A touch that waited for the phone's main thread, or whose redraw waited for a frame, logged (at most every 10 s). */
    private fun waited(at: Long) {
        val now = SystemClock.uptimeMillis()
        val screen = if (service.getSystemService(PowerManager::class.java).isInteractive) "phone screen on" else "phone screen off"
        if (now - at > 300 && now - waitLogged > 10_000) { waitLogged = now; WriterLog.i("Tetra Auto: a touch waited ${now - at} ms for the phone ($screen)") }
        android.view.Choreographer.getInstance().postFrameCallback {
            val f = SystemClock.uptimeMillis()
            if (f - now > 300 && f - waitLogged > 10_000) { waitLogged = f; WriterLog.i("Tetra Auto: the redraw after a touch waited ${f - now} ms for a frame ($screen)") }
        }
    }

    /** A touch on the car, played into Tetra Car here: its pointers as fractions of the car's screen, made this screen's. */
    private fun touch(b: ByteArray) {
        val p = presentation ?: return
        val bb = ByteBuffer.wrap(b)
        val action = bb.get().toInt(); val index = bb.get().toInt(); val n = bb.get().toInt().coerceIn(1, 10)
        val props = Array(n) { MotionEvent.PointerProperties().apply { toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(n) { MotionEvent.PointerCoords().apply { pressure = 1f; size = 1f } }
        for (i in 0 until n) { props[i].id = bb.get().toInt(); coords[i].x = bb.float * width; coords[i].y = bb.float * height }
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downAt = now
        val full = if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) else action
        val ev = MotionEvent.obtain(downAt, now, full, n, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        runCatching { deliver(p, ev, action) }.onFailure { WriterLog.w("Tetra Auto: a touch not delivered (${it.message})") }
        ev.recycle()
    }

    /** The window a touch began in (the panel, or a menu over it), its top-left corner on the car's screen: the rest of the touch goes there. */
    private var touched: android.view.View? = null
    private val touchedAt = IntArray(2)

    /**
     * A touch to the topmost of Tetra's windows under it on the car's screen: the panel, or a menu over it (a menu is a window of its
     * own, as on the phone). Android's touch screen would do it; here the touches come from the car. A touch outside an open menu
     * tells it so (ACTION_OUTSIDE), and it closes, as on the phone.
     */
    private fun deliver(p: Presentation, ev: MotionEvent, action: Int) {
        val shown = p.display.displayId
        if (action == MotionEvent.ACTION_DOWN || touched?.isAttachedToWindow != true) {
            val windows = android.view.inspector.WindowInspector.getGlobalWindowViews()
                .filter { it.isAttachedToWindow && it.isShown && it.display?.displayId == shown }
            val at = IntArray(2)
            val hit = windows.lastOrNull { v -> v.getLocationOnScreen(at); ev.x >= at[0] && ev.y >= at[1] && ev.x < at[0] + v.width && ev.y < at[1] + v.height }
                ?: p.window?.decorView ?: return
            if (action == MotionEvent.ACTION_DOWN) windows.dropWhile { it !== hit }.drop(1).forEach { above -> // menus over it, not touched: outside
                MotionEvent.obtain(ev).apply { this.action = MotionEvent.ACTION_OUTSIDE }.let { o -> above.dispatchTouchEvent(o); o.recycle() }
            }
            touched = hit; hit.getLocationOnScreen(touchedAt)
        }
        val target = touched ?: return
        ev.offsetLocation(-touchedAt[0].toFloat(), -touchedAt[1].toFloat())
        target.dispatchTouchEvent(ev)
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) touched = null
    }

    /** The unit's keys: Back goes back in Tetra Car, the voice key opens AI Assist, play/pause the music. */
    private fun key(code: Int) {
        when (code) {
            KeyEvent.KEYCODE_BACK -> onBackPressedDispatcher.onBackPressed()
            KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_SEARCH -> Car.assist?.invoke() // AI Assist's TV in Tetra Car's side pane
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> BrowserMedia.act(if (BrowserMedia.playing) "pause" else "play")
        }
    }

    /** Tetra Car's dock: the car's own Share page. */
    fun openShare() = send(AutoWire.OPEN_SHARE, ByteArray(0))

    // ---- Share ----

    private class In(val name: String, val size: Long, val part: File, val out: OutputStream, val digest: MessageDigest, var got: Long = 0)
    private val incoming = mutableMapOf<Int, In>()
    private fun folder() = File(driveRoot(), FROM_CAR).apply { mkdirs() }

    /** A file from the car, into Tetra › From car, checked by its SHA-256 before it is kept. */
    private fun incoming(type: Byte, body: ByteArray) {
        when (type) {
            AutoWire.FILE_BEGIN -> {
                val o = JSONObject(String(body)); val id = o.getInt("id")
                val part = File(folder(), ".$id.part")
                incoming[id] = In(AutoWire.safeName(o.optString("name")), o.optLong("size"), part, part.outputStream().buffered(1 shl 16), MessageDigest.getInstance("SHA-256"))
            }
            AutoWire.FILE_DATA -> {
                val f = incoming[ByteBuffer.wrap(body, 0, 4).int] ?: return
                f.out.write(body, 4, body.size - 4); f.digest.update(body, 4, body.size - 4); f.got += body.size - 4
            }
            AutoWire.FILE_END -> {
                val o = JSONObject(String(body)); val id = o.getInt("id")
                val f = incoming.remove(id) ?: return
                runCatching { f.out.close() }
                val ok = f.digest.digest().joinToString("") { "%02x".format(it) } == o.optString("sha256").lowercase()
                if (ok) {
                    val dot = f.name.lastIndexOf('.').takeIf { it > 0 } ?: f.name.length
                    val kept = generateSequence(1) { it + 1 }.map { n -> File(folder(), if (n == 1) f.name else "${f.name.substring(0, dot)} ($n)${f.name.substring(dot)}") }.first { !it.exists() }
                    f.part.renameTo(kept)
                    main.post { Toast.makeText(service, service.getString(R.string.auto_file_from_car, kept.name, FROM_CAR), Toast.LENGTH_LONG).show() }
                }
                f.part.delete()
                WriterLog.i("Tetra Auto: ${f.name} from the car ${if (ok) "kept" else "refused: its hash did not match"}")
                send(AutoWire.FILE_DONE, JSONObject().put("id", id).put("ok", ok).apply { if (!ok) put("error", "The file changed on the way.") }.toString().toByteArray())
            }
        }
    }

    private val sending = mutableMapOf<Int, String>()
    private var nextId = (System.currentTimeMillis() % 100_000).toInt() * 1000

    /** Files from the phone's share menu (Send to car) to the car's Share page, one after another. */
    fun sendFiles(uris: List<Uri>) = Thread({
        val r = service.contentResolver
        for (uri in uris) runCatching {
            val name = AutoWire.safeName(r.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: uri.lastPathSegment ?: "file")
            val size = r.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else -1L } ?: -1L
            val id = nextId++
            synchronized(sending) { sending[id] = name }
            check(send(AutoWire.FILE_BEGIN, JSONObject().put("id", id).put("name", name).put("size", size).toString().toByteArray()))
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(AutoWire.CHUNK + 4); ByteBuffer.wrap(buf).putInt(id)
            r.openInputStream(uri)!!.use { input ->
                while (true) { val n = input.read(buf, 4, AutoWire.CHUNK); if (n < 0) break; digest.update(buf, 4, n); check(send(AutoWire.FILE_DATA, buf, 0, n + 4)) }
            }
            send(AutoWire.FILE_END, JSONObject().put("id", id).put("sha256", digest.digest().joinToString("") { "%02x".format(it) }).toString().toByteArray())
            WriterLog.i("Tetra Auto: $name sent to the car")
        }.onFailure { WriterLog.w("Tetra Auto: not sent (${it.message})") }
    }, "auto-send").start()

    private fun answered(o: JSONObject) {
        val name = synchronized(sending) { sending.remove(o.optInt("id")) } ?: return
        main.post { Toast.makeText(service, if (o.optBoolean("ok")) service.getString(R.string.auto_file_on_car, name) else service.getString(R.string.auto_file_not_on_car, name), Toast.LENGTH_LONG).show() }
    }

    companion object { const val FROM_CAR = "From car" }
}

/** Send to car: Tetra in the share menu; what is shared goes to the car's Share page while a car shows Tetra Car. */
class AutoShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = @Suppress("DEPRECATION") (intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)))
        val s = AutoProjection.session
        if (s == null && uris.isNotEmpty() && CarWire.alive()) { CarShare.send(this, uris); Toast.makeText(this, resources.getQuantityString(R.plurals.auto_sending, uris.size, uris.size), Toast.LENGTH_SHORT).show() } // Tetra Auto SE's Share
        else if (s == null) Toast.makeText(this, R.string.auto_no_car, Toast.LENGTH_LONG).show()
        else if (uris.isNotEmpty()) { s.sendFiles(uris); Toast.makeText(this, resources.getQuantityString(R.plurals.auto_sending, uris.size, uris.size), Toast.LENGTH_SHORT).show() }
        finish()
    }
}
