package com.kalotrapezis.drive

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Calendar
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.security.auth.x500.X500Principal
import kotlin.concurrent.thread

/**
 * This device answering, instead of only asking (SYNC_PLAN.md 6l). Two phones have no computer between them,
 * so each one must be able to show a QR code and be paired *with* — the same protocol the desktop speaks, and
 * the same pinning: whoever scans the code remembers this certificate's fingerprint and will talk to nothing else.
 *
 * The key never leaves the Android keystore, which also issues the self-signed certificate that goes with it,
 * so no certificate library is needed.
 *
 * It answers two things. **Pairing**, while the QR code is on screen. And **"sync now"** from a device already
 * paired, for as long as Tetra is open here — that is what makes the other direction automatic: the computer
 * has no way to put a photo on this phone by itself, but it can say that there is one, and this phone then does
 * its own sync under its own rules. With a computer paired it goes on listening with the app closed, in
 * SyncListenService and its notification (decided out loud by the user, 23 Oct; Settings › Sync turns it off).
 */
internal class SyncServer(private val context: Context, private val store: SyncStore) {
    companion object {
        const val PORT = 43180
        private const val ALIAS = "tetra-sync"
        private const val PAIRING_MS = 10 * 60 * 1000L

        private var shared: SyncServer? = null
        private var users = 0

        /**
         * One listener, however many parts of the app want it: the Sync screen showing a code and the app
         * itself being open are two reasons for the same socket, and two of them cannot hold one port.
         */
        @Synchronized fun acquire(context: Context, store: SyncStore): SyncServer {
            val server = shared ?: SyncServer(context.applicationContext, store)
            server.start() // throws before anything is counted if the port is already taken
            shared = server
            users++
            return server
        }

        @Synchronized fun release() {
            if (--users > 0) return
            users = 0
            shared?.stop()
            shared = null
        }

        /** Key and certificate from the Android keystore: generated once, and the private half never leaves it. */
        fun identity(): Pair<KeyStore, X509Certificate> {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            // TLS hands the key an already-hashed value to sign, which the keystore calls the NONE digest. A key
            // that was not allowed it cannot serve TLS at all, so it is replaced rather than kept — nothing can
            // have been paired with a certificate that never completed a handshake.
            if (keyStore.containsAlias(ALIAS) && !signsForTls(keyStore)) keyStore.deleteEntry(ALIAS)
            if (!keyStore.containsAlias(ALIAS)) {
                val end = Calendar.getInstance().apply { add(Calendar.YEAR, 10) }
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                    initialize(
                        KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                            .setCertificateSubject(X500Principal("CN=Tetra"))
                            .setCertificateSerialNumber(BigInteger.ONE)
                            .setCertificateNotAfter(end.time)
                            .build(),
                    )
                    generateKeyPair()
                }
            }
            return keyStore to keyStore.getCertificate(ALIAS) as X509Certificate
        }

        private fun signsForTls(keyStore: KeyStore): Boolean = runCatching {
            val key = keyStore.getKey(ALIAS, null) as java.security.PrivateKey
            val info = java.security.KeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
                .getKeySpec(key, android.security.keystore.KeyInfo::class.java)
            KeyProperties.DIGEST_NONE in info.digests
        }.getOrDefault(false)

        fun fingerprint(certificate: X509Certificate): String =
            SyncRules.hex(MessageDigest.getInstance("SHA-256").digest(certificate.encoded))

        /** Every address this device can be reached at, for the QR code. */
        fun lanAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filter { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
                .mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())
    }

    private var socket: SSLServerSocket? = null
    private var beacon: DatagramSocket? = null
    private val codes = HashMap<String, Long>() // every code shown stays valid until used or expired
    @Volatile private var running = false
    lateinit var fingerprint: String
        private set
    var port: Int = PORT
        private set
    /** Set when a device finishes pairing, so the screen showing the QR can say so. */
    @Volatile var onPaired: (Peer) -> Unit = {}

    fun start() {
        if (running) return
        val (keyStore, certificate) = identity()
        fingerprint = fingerprint(certificate)
        val keys = KeyManagerFactory.getInstance("X509").apply { init(keyStore, null) }
        val tls = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        val server = tls.serverSocketFactory.createServerSocket(PORT) as SSLServerSocket
        socket = server
        port = server.localPort
        running = true
        thread(name = "tetra-sync-server") {
            while (running) {
                val client = runCatching { server.accept() }.getOrNull() ?: continue
                thread { runCatching { serve(client.getInputStream(), client.getOutputStream()) }; runCatching { client.close() } }
            }
        }
        startBeacon()
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        runCatching { beacon?.close() }
        socket = null
        beacon = null
    }

    /** A fresh one-time code for the QR; valid ten minutes or until it is used. */
    fun startPairing(): String {
        val code = SyncRules.hex(ByteArray(16).also(SecureRandom()::nextBytes))
        synchronized(codes) {
            codes.values.removeAll { it < System.currentTimeMillis() }
            codes[code] = System.currentTimeMillis() + PAIRING_MS
        }
        return code
    }

    fun pairingQr(code: String): String = JSONObject()
        .put("v", 1).put("name", "${Build.MANUFACTURER} ${Build.MODEL}")
        .put("hosts", org.json.JSONArray(lanAddresses())).put("port", port)
        .put("fp", fingerprint).put("code", code).toString()

    private fun useCode(code: String?): Boolean = synchronized(codes) {
        val until = codes[code] ?: return false
        codes.remove(code)
        until >= System.currentTimeMillis()
    }

    /**
     * One request, one answer. The protocol is ours and tiny, so the parsing is too: a request line, headers,
     * then exactly Content-Length bytes. Anything longer than a pairing message is refused outright until the
     * endpoints that stream photos exist.
     */
    private fun serve(input: InputStream, output: OutputStream) {
        val stream = BufferedInputStream(input)
        val request = readLine(stream) ?: return
        val (method, path) = request.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        var length = 0L
        var bearer: String? = null
        while (true) {
            val header = readLine(stream) ?: return
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toLongOrNull() ?: 0
            if (header.startsWith("Authorization:", true)) bearer = header.substringAfter(':').trim().removePrefix("Bearer ").trim()
        }
        // "Sync now" from a device we are paired with: it says nothing and carries nothing, it only asks. The
        // work is still ours, in our own service, under our own rules — a nudge cannot make this phone do
        // anything it would not do when its owner opens the app.
        if (method == "POST" && path == "/sync") {
            val known = bearer != null && store.peers().any { it.theirToken == bearer }
            if (!known) return reply(output, 401, JSONObject().put("error", "Not paired."))
            // A minute's grace: the computer scans what this phone has just sent it and says "something new"
            // about our own upload, and that round trip is worth skipping. Anything genuinely new still lands.
            ScopedSync.soon(context, "contacts", 0) // or a contact changed on the computer (or by Claude)
            ScopedSync.soon(context, "browser", 0) // or another device's bookmarks or tabs, through the computer
            SyncService.syncInBackground(context, gap = 60_000)
            return reply(output, 200, JSONObject().put("ok", true))
        }
        // "Where are you?" from desktop Maps: one fix, taken now (PhoneLocation). Only a paired device may ask.
        if (method == "POST" && path == "/location") {
            val known = bearer != null && store.peers().any { it.theirToken == bearer }
            if (!known) return reply(output, 401, JSONObject().put("error", "Not paired."))
            val (status, body) = PhoneLocation.answer(context)
            return reply(output, status, body)
        }
        // A call from the computer's Contacts (PhoneCall): this phone places it, or shows it to tap. Only a paired device.
        if (method == "POST" && path.startsWith("/call?")) {
            val known = bearer != null && store.peers().any { it.theirToken == bearer }
            if (!known) return reply(output, 401, JSONObject().put("error", "Not paired."))
            val url = android.net.Uri.parse("https://x$path")
            val number = url.getQueryParameter("number").orEmpty().filter { it.isDigit() || it in "+*#" }.take(32)
            if (number.count(Char::isDigit) < 3) return reply(output, 400, JSONObject().put("error", "Not a phone number."))
            val r = PhoneCall.fromComputer(context, number, url.getQueryParameter("name").orEmpty().take(80))
            return reply(output, if (r.has("error")) 409 else 200, r)
        }
        // A tablet: the phone's call, passed on by the computer (PhoneRelay). Only a paired device.
        if (method == "POST" && path.startsWith("/call/state?")) {
            val known = bearer != null && store.peers().any { it.theirToken == bearer }
            if (!known) return reply(output, 401, JSONObject().put("error", "Not paired."))
            val s = runCatching { JSONObject(android.net.Uri.parse("https://x$path").getQueryParameter("s").orEmpty().take(4096)) }.getOrNull()
                ?: return reply(output, 400, JSONObject().put("error", "No call."))
            PhoneRelay.show(context, s)
            return reply(output, 200, JSONObject().put("ok", true))
        }
        // The computer's call bubble: Answer, Decline, End (PhoneCall.act). Only a paired device.
        if (method == "POST" && path.startsWith("/call/action?")) {
            val known = bearer != null && store.peers().any { it.theirToken == bearer }
            if (!known) return reply(output, 401, JSONObject().put("error", "Not paired."))
            val r = PhoneCall.act(context, android.net.Uri.parse("https://x$path").getQueryParameter("do").orEmpty().take(9)) // "unspeaker", the longest
            return reply(output, if (r.has("error")) 409 else 200, r)
        }
        // Messages on the computer (the user, 2 Nov): the newest SMS, sent and marked read by this phone. Only a paired device.
        if (method == "POST" && path.startsWith("/sms/")) {
            val known = bearer != null && store.peers().any { it.theirToken == bearer }
            if (!known) return reply(output, 401, JSONObject().put("error", "Not paired."))
            if (PhoneRelay.noCalls(context)) return reply(output, 404, JSONObject().put("error", "No SMS here."))
            val r = SmsBox.forComputer(context, android.net.Uri.parse("https://x$path"))
            return reply(output, if (r.has("error")) 409 else 200, r)
        }
        // Send to phone: a file from the computer, into Files › From computer (FromComputer). Only a paired device.
        if (method == "PUT" && path.startsWith("/send/")) {
            val peer = bearer?.let { b -> store.peers().firstOrNull { it.theirToken == b } } ?: return reply(output, 401, JSONObject().put("error", "Not paired."))
            val url = android.net.Uri.parse("https://x$path")
            val sha = url.path.orEmpty().removePrefix("/send/")
            if (!Regex("[0-9a-f]{64}").matches(sha)) return reply(output, 400, JSONObject().put("error", "Invalid file."))
            val dir = java.io.File(TetraFolder.root(android.os.Environment.getExternalStorageDirectory()), FromComputer.FOLDER)
            return try {
                val kept = FromComputer.receive(stream, length, dir, sha, url.getQueryParameter("name").orEmpty(), url.getQueryParameter("modified")?.toLongOrNull() ?: 0)
                FromComputer.arrived(context, kept.name, peer.name)
                reply(output, 200, JSONObject().put("sha256", sha).put("path", "${FromComputer.FOLDER}/${kept.name}").put("verified", true))
            } catch (e: IllegalArgumentException) {
                reply(output, if ("transit" in e.message.orEmpty()) 422 else 400, JSONObject().put("error", e.message))
            }
        }
        // Tetra Car's own link (CarWire): live calls and messages between the car's screen and the phone; never sync, never peers
        if (method == "POST" && path.startsWith("/car/") && length in 0..65536) {
            val raw = ByteArray(length.toInt()).also { var read = 0; while (read < length.toInt()) { val n = stream.read(it, read, length.toInt() - read); if (n < 0) break; read += n } }
            val (status, answer) = CarWire.serve(context, path.substringBefore('?'), runCatching { JSONObject(String(raw, Charsets.UTF_8)) }.getOrDefault(JSONObject()),
                bearer, { useCode(it) }, fingerprint, port)
            return reply(output, status, answer)
        }
        if (method != "POST" || path != "/pair" || length !in 1..8192) return reply(output, 404, JSONObject().put("error", "Unknown request."))
        val body = ByteArray(length.toInt()).also { var read = 0; while (read < length.toInt()) { val n = stream.read(it, read, length.toInt() - read); if (n < 0) break; read += n } }
        reply(output, 200, pair(runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrDefault(JSONObject())))
    }

    /**
     * The scanning device proves it saw the code, and tells us how to reach it back — its own fingerprint, port
     * and a token it made for us. Between two phones neither side is the client, so the pairing has to hand over
     * both halves at once, or only one of them could ever start a sync.
     */
    private fun pair(body: JSONObject): JSONObject {
        if (!useCode(body.optString("code").takeIf { it.isNotBlank() })) {
            return JSONObject().put("error", "Pairing code is not valid. Show a new QR code.")
        }
        val token = SyncRules.hex(ByteArray(32).also(SecureRandom()::nextBytes))
        val peer = Peer(
            name = body.optString("name").ifBlank { "Device" }.take(80),
            hosts = body.optJSONArray("hosts")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty(),
            port = body.optInt("port", PORT),
            fingerprint = body.optString("fp").lowercase(),
            token = body.optString("token"), // what we send when we call them
            theirToken = token,              // what they send when they call us
        )
        store.savePeer(peer)
        onPaired(peer)
        return JSONObject().put("name", "${Build.MANUFACTURER} ${Build.MODEL}").put("fp", fingerprint).put("port", port).put("token", token)
    }

    private fun readLine(stream: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val c = stream.read()
            if (c < 0) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString().trimEnd('\r')
            if (line.length > 8192) return null
            line.append(c.toChar())
        }
    }

    private fun reply(output: OutputStream, status: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray()
        output.write(("HTTP/1.1 $status ${if (status == 200) "OK" else "Not Found"}\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
        output.write(bytes)
        output.flush()
    }

    /** The same beacon the computer answers (SYNC_PLAN.md 6k), so a paired device finds this one again too. */
    private fun startBeacon() {
        val socket = runCatching { DatagramSocket(SyncDiscovery.BEACON_PORT) }.getOrNull() ?: return
        beacon = socket
        socket.soTimeout = 500
        thread(name = "tetra-beacon") {
            val buffer = ByteArray(512)
            while (running) {
                val packet = DatagramPacket(buffer, buffer.size)
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue } catch (_: Exception) { break }
                val asked = runCatching { JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8)) }.getOrNull() ?: continue
                if (asked.optInt("v") != 1 || asked.optString("fp") != fingerprint) continue // only the device that already knows us
                val answer = JSONObject().put("v", 1).put("name", Build.MODEL).put("port", port).toString().toByteArray()
                runCatching { socket.send(DatagramPacket(answer, answer.size, packet.address, packet.port)) }
            }
        }
    }
}

/** A device this one is paired with, in both directions: how to call them, and what they send when they call. */
internal data class Peer(
    val name: String,
    val hosts: List<String>,
    val port: Int,
    val fingerprint: String,
    val token: String,
    val theirToken: String,
)
