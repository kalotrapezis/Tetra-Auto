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
        val body = JSONObject().put("code", qr.code).put("name", DataFiles.deviceName(c).ifBlank { "${Build.MANUFACTURER} ${Build.MODEL}" }).put("port", SyncServer.PORT)
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
    /** On the unit: the paired phone's name for the menu (null: none paired), kept fresh by the hello. */
    val linkName = mutableStateOf<String?>(null)
    /** On the unit: the phone's battery (per cent, charging), from its answer to the hello; null without a phone. */
    val battery = mutableStateOf<Pair<Int, Boolean>?>(null)
    /** On the unit: the phone's call now, for the side pane; null with none. */
    val call = mutableStateOf<JSONObject?>(null)

    /** The unit to the phone: "here I am, at these addresses" (every 30 s while Tetra Car is open). */
    fun hello(c: Context) {
        val l = link(c) ?: return
        val answer = runCatching { anyHost(l) { h -> post(h, l.port, l.fingerprint, l.token, "/car/hello", JSONObject().put("hosts", JSONArray(SyncServer.lanAddresses()))) } }.getOrNull()
        val ok = answer != null
        val power = answer?.optInt("battery", -1)?.takeIf { it >= 0 }?.let { it to answer.optBoolean("charging") }
        answer?.optString("name")?.takeIf { it.isNotBlank() && it != l.name }?.let { save(c, l.copy(name = it.take(80))); Handler(Looper.getMainLooper()).post { linkName.value = it.take(80) } }
        Handler(Looper.getMainLooper()).post { if (ok != reached.value) reached.value = ok; battery.value = power }
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
                // the phone's battery for the dock on the car's screen (CarStatus)
                val power = c.getSystemService(android.os.BatteryManager::class.java)
                200 to JSONObject().put("ok", true).put("battery", power.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)).put("charging", power.isCharging)
                    .put("name", DataFiles.deviceName(c)) // the name the user gave the phone ("Xiaomi 15"), for the car's menu
            }
            "/car/event" -> { // on the unit: from the phone
                val main = Handler(Looper.getMainLooper())
                when (body.optString("type")) {
                    "sms" -> main.post { Car.message?.invoke(CarMessage(body.optString("from"), body.optString("name"), body.optString("body"), remote = true)) }
                    "share" -> main.post { CarMaps.check(c) } // the phone's Send to car: taken now
                    "call" -> main.post {
                        call.value = body
                        if (body.optString("state") == "ended") main.postDelayed({ if (call.value === body) call.value = null }, 1200)
                    }
                }
                200 to JSONObject().put("ok", true)
            }
            "/car/maps" -> 200 to CarMaps.list() // on the phone: its maps, for the unit to add or update its own
            "/car/assistant" -> 200 to CarAssistant.give(c) // on the phone: AI Assist's settings and keys, for the paired unit only
            // on the phone: the Browser's saved logins (never its cards), for the paired unit only (the user, 14 Nov: "I had to type my password all over again")
            "/car/vault" -> 200 to JSONObject().put("vault", BrowserVault(logins = BrowserSecrets.vault(c).logins).json())
            "/car/where" -> 200 to CarGps.where(c) // on the phone: its GPS fix, for the car that uses the phone's (Settings › Tetra Car › GPS)
            "/car/log" -> { // on the phone: the car's own log, each time it connects (its crashes read from the phone)
                java.io.File(WriterLog.dir(c), "car.log").writeText(body.optString("log"))
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
                body.optJSONArray("taken")?.let { names -> CarShare.taken(names); return 200 to JSONObject().put("ok", true) } // the car has these
                body.optJSONObject("bookmarks")?.let { b -> // the car's bookmarks file, kept with the others (BrowserSync), on to the computer
                    val set = BrowserSync.keepCar(BrowserSync.root(), BrowserSync.device(c), b.optString("name"), b.optString("text"))
                        ?: return 400 to JSONObject().put("error", "Not a bookmarks file.")
                    BrowserSync.keepSaved(set.marks.values.map { it.url to it.title }) // the deleted too: their titles stay in Saved pages.txt
                    ScopedSync.soon(c.applicationContext, "browser", orWhole = true)
                    return 200 to JSONObject().put("ok", true)
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

    /** On the unit: the phone's AI Assist settings and keys (CarAssistant). */
    fun assistant(c: Context): JSONObject = link(c)?.let { l -> anyHost(l) { h -> post(h, l.port, l.fingerprint, l.token, "/car/assistant", JSONObject()) } }
        ?: throw SyncException("No phone is paired with Tetra Car.")

    /** On the unit: the phone's saved logins (CarMaps.check keeps them in this unit's own vault). */
    fun vault(c: Context): BrowserVault = link(c)?.let { l -> BrowserVault.of(anyHost(l) { h -> post(h, l.port, l.fingerprint, l.token, "/car/vault", JSONObject()) }.optString("vault")) }
        ?: throw SyncException("No phone is paired with Tetra Car.")

    /** On the unit: the phone's GPS fix now (CarGps). */
    fun where(c: Context): JSONObject = link(c)?.let { l -> anyHost(l) { h -> post(h, l.port, l.fingerprint, l.token, "/car/where", JSONObject()) } }
        ?: throw SyncException("No phone is paired with Tetra Car.")

    /** On the phone: [bearer] is the paired car's. */
    fun paired(c: Context, bearer: String?) = bearer != null && bearer == link(c)?.theirToken

    /** On the unit: a file of the car's to the phone's Tetra › From car (Share), its SHA-256 checked there. */
    fun put(c: Context, uri: android.net.Uri, name: String) {
        val l = link(c) ?: throw SyncException("No phone is paired with Tetra Car.")
        val r = c.contentResolver
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var size = 0L
        r.openInputStream(uri)!!.use { input -> val b = ByteArray(1 shl 16); while (true) { val n = input.read(b); if (n < 0) break; digest.update(b, 0, n); size += n } }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        anyHost(l) { h ->
            val conn = open(h, l.port, l.fingerprint, l.token, "/car/send?sha=$sha&name=" + java.net.URLEncoder.encode(name, "UTF-8")).apply {
                requestMethod = "PUT"; doOutput = true; setFixedLengthStreamingMode(size); readTimeout = 60_000
            }
            r.openInputStream(uri)!!.use { input -> conn.outputStream.use { input.copyTo(it, 1 shl 16) } }
            val ok = conn.responseCode in 200..299
            val text = (if (ok) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (!ok) throw SyncException(runCatching { JSONObject(text).optString("error") }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "The phone answered ${conn.responseCode}.")
        }
    }

    /** On the unit: the end of its own log, to the phone (logs/car.log there), as the car has no other way out for it. */
    fun log(c: Context) {
        val l = link(c) ?: return
        val text = WriterLog.export(c)?.readText()?.takeLast(300_000) ?: return
        anyHost(l) { h -> post(h, l.port, l.fingerprint, l.token, "/car/log", JSONObject().put("log", text)) }
    }

    /** On the unit: the phone's maps, path → {size, modified} (CarMaps). */
    fun maps(c: Context): JSONObject = link(c)?.let { l -> anyHost(l) { h -> post(h, l.port, l.fingerprint, l.token, "/car/maps", JSONObject()) } }
        ?: throw SyncException("No phone is paired with Tetra Car.")

    /** On the unit: one of the phone's map files, streamed to [into] ([got]: the bytes so far); the link's own pinned TLS. */
    fun download(c: Context, path: String, into: java.io.OutputStream, got: (Long) -> Unit) {
        val l = link(c) ?: throw SyncException("No phone is paired with Tetra Car.")
        anyHost(l) { host ->
            val h = open(host, l.port, l.fingerprint, l.token, "/car/map?path=" + java.net.URLEncoder.encode(path, "UTF-8")).apply { requestMethod = "GET"; readTimeout = 30_000 }
            if (h.responseCode !in 200..299) throw SyncException("The phone did not give $path (${h.responseCode}).")
            h.inputStream.use { input ->
                val buf = ByteArray(1 shl 16); var n: Int; var total = 0L
                while (input.read(buf).also { n = it } >= 0) { into.write(buf, 0, n); total += n; got(total) }
            }
            h.disconnect()
        }
    }

    /** On the phone (SyncServer's GET /car/map): the map file a paired unit asks for, or null when it may not have it. */
    fun mapFile(c: Context, bearer: String?, path: String?): java.io.File? {
        val l = link(c) ?: return null
        if (bearer == null || bearer != l.theirToken || path == null) return null
        return CarMaps.file(path)
    }

    private fun open(host: String, port: Int, fingerprint: String, token: String?, path: String): HttpsURLConnection {
        val pinned = object : X509TrustManager {
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                if (SyncRules.hex(MessageDigest.getInstance("SHA-256").digest(chain.first().encoded)) != fingerprint) throw CertificateException("Not the paired device.")
            }
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = throw CertificateException("Not a server.")
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(pinned), null) }
        return (URL("https://$host:$port$path").openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = tls.socketFactory
            hostnameVerifier = HostnameVerifier { _, _ -> true } // identity is the pinned certificate
            connectTimeout = 3_000; readTimeout = 10_000
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
    }

    private fun post(host: String, port: Int, fingerprint: String, token: String?, path: String, body: JSONObject): JSONObject {
        val h = open(host, port, fingerprint, token, path).apply {
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Content-Type", "application/json")
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
