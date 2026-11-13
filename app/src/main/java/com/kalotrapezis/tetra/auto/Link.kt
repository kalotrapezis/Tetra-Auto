package com.kalotrapezis.tetra.auto

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom

internal const val TAG = "TetraAuto"

/** Where the car stands with the phone, for the screen. */
internal sealed interface LinkState {
    /** Looking for the phone; [pairCode] while this car has never been paired (its QR is shown). */
    data class Looking(val pairCode: String?) : LinkState
    data class Connected(val phone: String) : LinkState
}

/**
 * The car's side: find the phone (its beacon, the last address it had, the Wi-Fi's gateway, which is the phone when the car is
 * on its hotspot), say hello with this screen's size, then show what comes and send the touches. Started again a second after
 * any failure, for as long as the screen is open.
 */
internal class Link(private val context: Context, private val video: Video, private val share: Share,
                    /** "standalone" for Tetra Auto SE (no picture); its EVENTs and ANSWERs go to [signal], on the link's thread. */
                    private val mode: String? = null, private val signal: (Byte, ByteArray) -> Unit = { _, _ -> }) {
    private val prefs = context.getSharedPreferences("link", Context.MODE_PRIVATE)
    var state: LinkState by mutableStateOf(LinkState.Looking(if (prefs.getString("token", null) == null) pairCode() else null))
        private set
    /** Tetra Car's dock asked for the Share page. */
    var shareAsked by mutableStateOf(0)
        private set
    /** A line from the phone for this screen (what to allow there), until it is read. */
    var notice by mutableStateOf<String?>(null)
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var running = false
    /** Each start's own loop: a loop left over from an earlier start (the screen stopped and started) ends itself. */
    @Volatile private var generation = 0
    var width = 0; var height = 0; var dpi = 160
    /** The phone said it knows Tetra Auto SE (an older Tetra sends the picture instead, which SE ignores). */
    var standalone by mutableStateOf(false)
        private set

    /** The code this car's QR carries until a phone pairs with it: made once, kept, so the QR does not change each start. */
    private fun pairCode(): String = prefs.getString("code", null) ?: SecureRandom().let { r ->
        (1..10).map { "ABCDEFGHJKMNPQRSTUVWXYZ23456789"[r.nextInt(31)] }.joinToString("")
    }.also { prefs.edit().putString("code", it).apply() }

    /** What the QR says: the phone's Tetra reads it in Tetra Car › Pair with the car. */
    fun qr(code: String) = JSONObject().put("tetraAuto", Wire.VERSION).put("code", code).put("name", carName()).toString()
    private fun carName() = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" ").ifEmpty { "Car" }

    fun start() {
        if (running) return
        running = true
        val g = ++generation
        Thread({ loop(g) }, "tetra-auto-link").start()
    }

    fun stop() { running = false; runCatching { socket?.close() } }

    /** Pair again (Settings › Forget the phone): a new code, a new QR. */
    fun forget() {
        prefs.edit().remove("token").remove("code").remove("phone").apply()
        state = LinkState.Looking(pairCode()); runCatching { socket?.close() }
    }

    private fun loop(g: Int) {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("tetra-auto")?.apply { setReferenceCounted(false); runCatching { acquire() } }
        try {
            while (running && g == generation) {
                val hosts = candidates(wifi)
                val connected = hosts.firstNotNullOfOrNull { h -> runCatching { Socket().apply { connect(InetSocketAddress(h, Wire.PORT), 1500); tcpNoDelay = true } }.getOrNull()?.let { h to it } }
                if (connected == null) { Thread.sleep(1000); continue }
                val (host, s) = connected
                socket = s
                Log.i(TAG, "Connected to $host")
                runCatching { session(s, host) }.onFailure { Log.i(TAG, "Link ended: ${it.message}") }
                runCatching { s.close() }
                out = null; socket = null
                video.stop()
                if (state is LinkState.Connected) state = LinkState.Looking(if (prefs.getString("token", null) == null) pairCode() else null)
                Thread.sleep(1000)
            }
        } finally { runCatching { lock?.release() } }
    }

    /** The phone's beacon heard within 2 s first, then the last address and the gateway. */
    private fun candidates(wifi: WifiManager?): List<InetAddress> {
        val found = mutableListOf<InetAddress>()
        runCatching {
            DatagramSocket(null).use { d ->
                d.reuseAddress = true; d.broadcast = true; d.soTimeout = 2200
                d.bind(InetSocketAddress(Wire.BEACON_PORT))
                val buf = ByteArray(512); val p = DatagramPacket(buf, buf.size)
                d.receive(p)
                if (String(p.data, 0, p.length).startsWith(Wire.BEACON)) found += p.address
            }
        }.onFailure { if (it !is SocketTimeoutException) Log.w(TAG, "Beacon: ${it.message}") }
        prefs.getString("host", null)?.let { h -> runCatching { found += InetAddress.getByName(h) } }
        @Suppress("DEPRECATION") // Android 10's way to the gateway; on the phone's hotspot the gateway is the phone
        wifi?.dhcpInfo?.gateway?.takeIf { it != 0 }?.let { g ->
            found += InetAddress.getByAddress(byteArrayOf((g and 0xff).toByte(), (g shr 8 and 0xff).toByte(), (g shr 16 and 0xff).toByte(), (g shr 24 and 0xff).toByte()))
        }
        found += InetAddress.getLoopbackAddress() // the phone app on this same device (a test on one emulator); a head unit has none
        return found.distinct()
    }

    private fun session(s: Socket, host: InetAddress) {
        val input = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
        val o = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 1 shl 16)).also { out = it }
        val token = prefs.getString("token", null)
        val hello = JSONObject().put("version", Wire.VERSION).put("name", carName()).put("width", width).put("height", height).put("dpi", dpi).put("pings", true)
        mode?.let { hello.put("mode", it) }
        if (token != null) hello.put("token", token) else hello.put("code", pairCode())
        Wire.write(o, Wire.HELLO, hello.toString().toByteArray())
        s.soTimeout = 15_000 // the phone sends a picture or a ping at least every few seconds
        // and this car says it is here every 5 s: the phone lets a silent car go after 20 s, and is free for it again
        Thread({ while (out === o) { runCatching { Thread.sleep(5000) }; if (out === o) send(Wire.PING, ByteArray(0)) } }, "tetra-auto-ping").start()
        while (running) {
            val (type, body) = Wire.read(input)
            when (type) {
                Wire.WELCOME -> {
                    val w = JSONObject(String(body))
                    prefs.edit().putString("host", host.hostAddress).putString("phone", w.optString("name")).apply {
                        w.optString("token").takeIf { it.isNotEmpty() }?.let { putString("token", it); remove("code") }
                    }.apply()
                    state = LinkState.Connected(w.optString("name").ifEmpty { "Phone" })
                    standalone = w.optBoolean("standalone")
                    Log.i(TAG, "Welcome from ${w.optString("name")}")
                }
                Wire.REFUSED -> {
                    val r = JSONObject(String(body))
                    Log.w(TAG, "Refused: ${r.optString("error")}")
                    if (r.optBoolean("unpaired")) { prefs.edit().remove("token").apply(); state = LinkState.Looking(pairCode()) }
                    Thread.sleep(4000) // not paired yet: asked again in a while, not every second
                    return
                }
                Wire.VIDEO_CONFIG -> if (mode == null) video.config(body)
                Wire.VIDEO -> if (mode == null && !video.frame(body)) send(Wire.KEYFRAME, ByteArray(0))
                Wire.OPEN_SHARE -> shareAsked++
                Wire.NOTICE -> notice = JSONObject(String(body)).optString("text").ifEmpty { null }
                Wire.FILE_BEGIN, Wire.FILE_DATA, Wire.FILE_END -> share.incoming(type, body) { t, b -> send(t, b) }
                Wire.FILE_DONE -> share.answered(JSONObject(String(body)))
                Wire.EVENT, Wire.ANSWER -> signal(type, body)
                Wire.PING -> Unit
            }
        }
    }

    val connected get() = out != null && state is LinkState.Connected

    /** The screen's frames (touches, keys) go out on this thread: Android allows no network on the screen's own. */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** A frame to the phone; false when there is no phone. From the screen's thread it is queued; from any other, written now. */
    fun send(type: Byte, body: ByteArray, off: Int = 0, len: Int = body.size): Boolean {
        val o = out ?: return false
        val write = { runCatching { Wire.write(o, type, body, off, len) }.onFailure { Log.w(TAG, "Not sent: ${it.message}"); runCatching { socket?.close() } }.isSuccess }
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return write()
        writer.execute { write() }
        return true
    }
}
