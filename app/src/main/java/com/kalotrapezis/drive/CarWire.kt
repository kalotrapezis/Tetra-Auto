package com.kalotrapezis.drive

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * The car's own link (the user, 9 Nov: "this one to one communication just for the car app … the only exception"): the head unit
 * and the phone, paired once by the unit's QR, talking live over the phone's hotspot through Tetra's own server (SyncServer's
 * /car/… requests). It carries only what happens now — a call's state and buttons, a message arriving and its reply, a call
 * placed — and keeps nothing: no files, no sync, and Sync's own pairing and peers are never touched. The unit's AI Assist asks
 * the AI over the hotspot itself; only what the phone alone can do comes here.
 *
 * Each side keeps one link: on the unit, the phone; on the phone, the unit. The unit says hello every 30 s; on the phone a hello
 * in the last 90 s means "in the car" (Car.inCar: quiet, calls answered, messages to the unit).
 */
internal object CarWire {
    data class Link(val name: String, val hosts: List<String>, val port: Int, val fingerprint: String, val token: String, val theirToken: String)

    private fun prefs(c: Context) = c.getSharedPreferences("carlink", Context.MODE_PRIVATE)
    fun link(c: Context): Link? = runCatching {
        val o = JSONObject(prefs(c).getString("link", null)!!)
        Link(o.getString("name"), o.getJSONArray("hosts").let { a -> (0 until a.length()).map(a::getString) }, o.getInt("port"), o.getString("fp"), o.getString("token"), o.getString("theirToken"))
    }.getOrNull()
    private fun save(c: Context, l: Link) = prefs(c).edit().putString("link", JSONObject().put("name", l.name).put("hosts", JSONArray(l.hosts)).put("port", l.port)
        .put("fp", l.fingerprint).put("token", l.token).put("theirToken", l.theirToken).toString()).apply()
    fun forget(c: Context) = prefs(c).edit().clear().apply()

    /** A QR shown by Tetra Car (the unit) to pair the phone with: Sync's QR, marked as the car's. */
    fun isCarQr(raw: String) = runCatching { JSONObject(raw).optBoolean("car") }.getOrDefault(false)
    fun carQr(server: SyncServer): String = JSONObject(server.pairingQr(server.startPairing())).put("car", true).toString()

    private fun token() = SyncRules.hex(ByteArray(32).also(SecureRandom()::nextBytes))

    // ---- the phone ----

    @Volatile private var helloAt = 0L
    /** On the phone: the unit said hello in the last 90 s. */
    fun alive() = System.currentTimeMillis() - helloAt < 90_000

    /** The phone scanned the unit's QR (CodeScannerTab): the unit's address and keys kept here, ours given there. */
    fun pair(c: Context, qr: PairingQr): String {
        val ours = token()
        val body = JSONObject().put("code", qr.code).put("name", "${Build.MANUFACTURER} ${Build.MODEL}").put("port", SyncServer.PORT)
            .put("hosts", JSONArray(SyncServer.lanAddresses())).put("token", ours)
            .put("fp", SyncServer.fingerprint(SyncServer.identity().second))
        var last: Exception? = null
        for (host in qr.hosts) try {
            val r = post(host, qr.port, qr.fingerprint, null, "/car/pair", body)
            save(c, Link(r.optString("name", qr.name), listOf(host) + (qr.hosts - host), qr.port, qr.fingerprint, r.getString("token"), ours))
            SyncListenService.start(c) // this phone's server, for the unit's requests
            WriterLog.i("Car link: paired with ${r.optString("name")}")
            return r.optString("name", qr.name)
        } catch (e: Exception) { last = e }
        throw SyncException(last?.message ?: "The car did not answer.")
    }

    /** Something for the unit: a call's state or a message (on a thread of its own; nothing if the unit is away). */
    fun tell(c: Context, event: JSONObject) {
        if (!alive()) return
        val l = link(c) ?: return
        Thread { runCatching { anyHost(l) { h -> post(h, l.port, l.fingerprint, l.token, "/car/event", event) } }.onFailure { WriterLog.w("Car link: the car did not hear (${it.message})") } }.start()
    }

    /** A call's state for the unit's side pane, as the computer's bubble has it. */
    fun callChanged(c: Context, call: Call) {
        if (!alive() || call.parent != null) return
        val state = when (call.state) { Call.STATE_RINGING -> "ringing"; Call.STATE_ACTIVE -> "active"; Call.STATE_HOLDING -> "held"; Call.STATE_DISCONNECTED -> "ended"; else -> "calling" }
        val number = PhoneCalls.number(call).orEmpty()
        tell(c, JSONObject().put("type", "call").put("state", state).put("number", number).put("name", PhoneCalls.contact(call)?.name ?: number)
            .put("incoming", call.details.callDirection == Call.Details.DIRECTION_INCOMING).put("muted", PhoneCalls.audio?.isMuted == true)
            .put("speaker", PhoneCalls.audio?.route == android.telecom.CallAudioState.ROUTE_SPEAKER).put("since", call.details.connectTimeMillis))
    }

    // ---- the unit ----

    /** On the unit: the phone answered the last hello. */
    val reached = mutableStateOf(false)
    /** On the unit: the phone's call now, for the side pane; null with none. */
    val call = mutableStateOf<JSONObject?>(null)

    /** The unit to the phone: "here I am, at these addresses" (every 30 s while Tetra Car is open). */
    fun hello(c: Context) {
        val l = link(c) ?: return
        val ok = runCatching { anyHost(l) { h -> post(h, l.port, l.fingerprint, l.token, "/car/hello", JSONObject().put("hosts", JSONArray(SyncServer.lanAddresses()))) } }.isSuccess
        if (ok != reached.value) Handler(Looper.getMainLooper()).post { reached.value = ok }
    }

    /** The unit asks the phone: {call: "answer"}, {place: number, name}, {sms: {to, text}}. */
    fun ask(c: Context, what: JSONObject): JSONObject = link(c)?.let { l ->
        runCatching { anyHost(l) { h -> post(h, l.port, l.fingerprint, l.token, "/car/do", what) } }.getOrElse { JSONObject().put("error", it.message) }
    } ?: JSONObject().put("error", "No phone is paired with Tetra Car.")

    /** On the unit: a call from AI Assist or the dock goes to the paired phone. */
    fun usable(c: Context) = PhoneRelay.noCalls(c) && link(c) != null

    // ---- both: SyncServer's /car/… requests ----

    /** One /car/… request: [useCode] spends the pairing code (the unit's QR); the others need the link's token. */
    fun serve(c: Context, path: String, body: JSONObject, bearer: String?, useCode: (String?) -> Boolean, ourFingerprint: String, ourPort: Int): Pair<Int, JSONObject> {
        if (path == "/car/pair") { // on the unit: the phone scanned its QR
            if (!useCode(body.optString("code").takeIf { it.isNotBlank() })) return 403 to JSONObject().put("error", "The code is not valid. Show it again.")
            val ours = token()
            save(c, Link(body.optString("name").ifBlank { "Phone" }.take(80), body.optJSONArray("hosts")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty(),
                body.optInt("port", SyncServer.PORT), body.optString("fp").lowercase(), body.optString("token"), ours))
            WriterLog.i("Car link: paired with ${body.optString("name")}")
            Handler(Looper.getMainLooper()).post { reached.value = true }
            return 200 to JSONObject().put("name", "${Build.MANUFACTURER} ${Build.MODEL}").put("fp", ourFingerprint).put("port", ourPort).put("token", ours)
        }
        val l = link(c)
        if (l == null || bearer == null || bearer != l.theirToken) return 401 to JSONObject().put("error", "Not paired with this car.")
        return when (path) {
            "/car/hello" -> { // on the phone: the unit is here
                val was = alive()
                helloAt = System.currentTimeMillis()
                body.optJSONArray("hosts")?.let { a -> save(c, l.copy(hosts = (0 until a.length()).map(a::getString))) }
                if (!was) { WriterLog.i("Car link: the car is here"); CarQuiet.set(c, true) }
                val main = Handler(Looper.getMainLooper())
                main.removeCallbacksAndMessages(TOKEN)
                main.postAtTime({ if (!alive()) { WriterLog.i("Car link: the car is gone"); CarQuiet.set(c, false) } }, TOKEN, android.os.SystemClock.uptimeMillis() + 95_000)
                200 to JSONObject().put("ok", true)
            }
            "/car/event" -> { // on the unit: from the phone
                val main = Handler(Looper.getMainLooper())
                when (body.optString("type")) {
                    "sms" -> main.post { Car.message?.invoke(CarMessage(body.optString("from"), body.optString("name"), body.optString("body"), remote = true)) }
                    "call" -> main.post {
                        call.value = body
                        if (body.optString("state") == "ended") main.postDelayed({ if (call.value === body) call.value = null }, 1200)
                    }
                }
                200 to JSONObject().put("ok", true)
            }
            "/car/do" -> { // on the phone: the unit's buttons and AI Assist
                body.optString("call").takeIf { it.isNotEmpty() }?.let { what ->
                    val ok = PhoneCalls.act(what.take(9)) ?: false
                    WriterLog.i("Car link: $what from the car: $ok")
                    return if (ok) 200 to JSONObject().put("ok", true) else 409 to JSONObject().put("error", "The phone did not $what the call.")
                }
                body.optString("place").filter { it.isDigit() || it in "+*#" }.take(32).takeIf { it.count(Char::isDigit) >= 3 }?.let { number ->
                    WriterLog.i("Car link: a call placed from the car")
                    return if (placePhoneCall(c, number)) 200 to JSONObject().put("ok", true) else 409 to JSONObject().put("error", "Calls are not allowed on the phone.")
                }
                body.optJSONObject("sms")?.let { s ->
                    if (!SmsBox.canSend(c)) return 409 to JSONObject().put("error", "SMS are not allowed on the phone.")
                    SmsBox.send(c, s.getString("to"), s.getString("text").take(2000))
                    WriterLog.i("Car link: an SMS sent from the car")
                    return 200 to JSONObject().put("ok", true)
                }
                400 to JSONObject().put("error", "Nothing asked.")
            }
            else -> 404 to JSONObject().put("error", "Unknown request.")
        }
    }
    private val TOKEN = Any()

    // ---- the wire: Sync's own pinned TLS, its certificate the only one trusted ----

    private fun <T> anyHost(l: Link, call: (String) -> T): T {
        var last: Exception? = null
        for (h in (l.hosts + listOfNotNull(gateway())).distinct()) try { return call(h) } catch (e: Exception) { last = e }
        throw last ?: SyncException("No address.")
    }

    /** On the phone's hotspot the phone is the gateway: found even when its address changed. */
    private fun gateway(): String? = runCatching {
        java.io.File("/proc/net/route").readLines().drop(1).map { it.split(Regex("\\s+")) }.firstOrNull { it.getOrNull(1) == "00000000" }?.getOrNull(2)
            ?.let { hex -> (0..3).map { i -> hex.substring(6 - i * 2, 8 - i * 2).toInt(16) }.joinToString(".") }
    }.getOrNull()

    private fun post(host: String, port: Int, fingerprint: String, token: String?, path: String, body: JSONObject): JSONObject {
        val pinned = object : X509TrustManager {
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                if (SyncRules.hex(MessageDigest.getInstance("SHA-256").digest(chain.first().encoded)) != fingerprint) throw CertificateException("Not the paired device.")
            }
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = throw CertificateException("Not a server.")
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(pinned), null) }
        val h = (URL("https://$host:$port$path").openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = tls.socketFactory
            hostnameVerifier = HostnameVerifier { _, _ -> true } // identity is the pinned certificate
            requestMethod = "POST"; connectTimeout = 3_000; readTimeout = 10_000; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        h.outputStream.use { it.write(body.toString().toByteArray()) }
        val ok = h.responseCode in 200..299
        val text = (if (ok) h.inputStream else h.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        h.disconnect()
        val answer = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        if (!ok) throw SyncException(answer.optString("error", "The car link answered ${h.responseCode}."))
        return answer
    }
}
