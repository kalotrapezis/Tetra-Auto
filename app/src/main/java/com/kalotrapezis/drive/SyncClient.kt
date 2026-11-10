package com.kalotrapezis.drive

import android.content.ContentValues
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.coroutineContext

/** What the desktop's pairing QR carries (desktop: sync.js startPairing). */
/** The desktop stores the same string (faces.js EMBEDDING_MODEL): same weights, same eye alignment, one space. */
internal const val FACE_EMBEDDING_MODEL = "mobilefacenet-192-eyes38x44-74x44"

internal data class PairingQr(val name: String, val hosts: List<String>, val port: Int, val fingerprint: String, val code: String)
internal data class Pairing(val name: String, val hosts: List<String>, val port: Int, val fingerprint: String, val token: String)
internal data class BackupProgress(val stage: String, val done: Int, val total: Int)
internal data class BackupResult(val checked: Int, val sent: Int, val alreadyThere: Int, val failed: List<String>, val received: Int = 0)

/**
 * What this device may do with one kind of content, as the computer holds it (SYNC_PLAN.md 6j). Direction is
 * written from this device's point of view, because this device is the one that reads it and obeys: **send** is
 * phone → computer, **receive** is computer → phone, **both** is both. Keep says what the source does with its
 * copy afterwards, and only "everything" — a Copy — is implemented; two-way forces it anyway.
 */
/**
 * One row of a device's card on the computer (the computer owns them): photos, files, mail, other. A path follows its own
 * row ([of]): Mail/… the mail row; Calendar, Contacts, Phone, Data, Timer, Maps, AI Assist the other row; the rest, the
 * Trash among it, the files row. Mail goes both ways unless off. [keepAuto] (Automatic): everything stays here, nothing
 * goes by itself; at 90 % full the computer suggests a Move, which arrives as an ordinary one once the user confirms it.
 */
internal data class SyncConnection(val content: String, val direction: String, val keep: String, val keepDays: Int = 30, val keepFavorites: Boolean = true,
    val keepAuto: Boolean = false, val keepBytes: Long? = null) {
    val sends: Boolean get() = if (content == "mail") direction != "off" else direction == "send" || direction == "both"
    val receives: Boolean get() = if (content == "mail") direction != "off" else direction == "receive" || direction == "both"

    /** The way this row goes, as the phone shows it before the computer's name: → send, ← receive, ⇄ both; null when off. */
    val arrow: String? get() = when (direction) {
        "off" -> null
        "send" -> "→"
        "receive" -> "←"
        else -> "⇄"
    }
    /** A Move (the source lets its copy go after [keepDays]), not a Copy; Automatic keeps everything. */
    val moves: Boolean get() = keep == "nothing" && !keepAuto

    companion object {
        val defaults = listOf(SyncConnection("photos", "both", "everything"), SyncConnection("files", "both", "everything"),
            SyncConnection("mail", "both", "everything"), SyncConnection("other", "both", "everything"))
        /** Which row a Drive path follows (the computer's sync.js contentOf). */
        fun of(rel: String): String = rel.substringBefore('/').let { top -> when {
            top == "Mail" -> "mail"
            top != "Trash" && top in TetraSystem.SHARED -> "other"
            else -> "files"
        } }
        /** A computer from before the four rows: mail and other as its files row says. */
        fun complete(rows: List<SyncConnection>): List<SyncConnection> {
            val files = rows.firstOrNull { it.content == "files" } ?: defaults[1]
            return rows + listOf("mail", "other").filter { c -> rows.none { it.content == c } }.map { files.copy(content = it) }
        }
    }
}

internal object SyncRules {
    private val hex64 = Regex("^[0-9a-f]{64}$")

    /**
     * Hidden ".….part" files a transfer left when it was cut off (the app stopped mid-file; 31 Oct: three in Documents and
     * From phone, one from the 25th). A day old, none is being written: they go, at app start and before a whole sync.
     */
    fun staleParts(root: File, now: Long = System.currentTimeMillis()): List<File> = root.walkTopDown()
        .filter { it.isFile && it.name.startsWith(".") && it.name.endsWith(".part") && it.lastModified() < now - 86_400_000L }.toList()
    fun sweepParts(root: File) = staleParts(root).forEach { if (it.delete()) WriterLog.i("Sync: a transfer's leftover gone: ${it.name}") }

    /** How many times a sync that died on a lost network is started again, once a network is back. */
    const val NETWORK_RETRIES = 3

    /**
     * A sync that died when the Wi-Fi moved under it — an extender handing over to the main router, on
     * 24 September — is worth starting again the moment a network is back. Three times, and no more: after
     * that the computer is assumed to be away, and the next attempt waits for someone to open the app. A
     * cancellation is not a failure; Stop means stop.
     */
    fun retriesAfterNetworkLoss(attempt: Int, failure: Throwable?): Boolean =
        failure != null && failure !is kotlinx.coroutines.CancellationException && attempt < NETWORK_RETRIES

    fun parseQr(text: String): PairingQr? = runCatching {
        val o = JSONObject(text)
        if (o.getInt("v") != 1) return null
        val hosts = o.getJSONArray("hosts").let { a -> (0 until a.length()).map { a.getString(it) } }
        PairingQr(o.optString("name", "Computer"), hosts, o.getInt("port"), o.getString("fp").lowercase(), o.getString("code"))
            .takeIf { it.fingerprint.matches(hex64) && it.port in 1..65535 && it.hosts.isNotEmpty() && it.code.length >= 16 }
    }.getOrNull()

    /** Photos keep their phone folders on the computer (1:1), e.g. DCIM/Camera. */
    fun blobPath(sha256: String, relativePath: String?, name: String, modified: Long): String {
        fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
        return "/blob/$sha256?path=${enc(relativePath.orEmpty().trim('/'))}&name=${enc(name)}&modified=$modified"
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /**
     * The colour of a number of places, the same as the computer's copies bar: 1 and 2 are the risk (red, yellow);
     * from 3 on the hue is found by halving — 0, 1/2, 1/4, 3/4, 1/8… of the way from green to magenta — so every
     * count stays as far from the others as it can. Hue, saturation, lightness.
     */
    fun copiesColor(n: Int): Triple<Float, Float, Float> {
        if (n <= 1) return Triple(358f, 0.75f, 0.59f)
        if (n == 2) return Triple(47f, 0.92f, 0.53f)
        var k = n - 3; var f = 0.0; var half = 0.5
        while (k > 0) { if (k and 1 == 1) f += half; k = k shr 1; half /= 2 }
        return Triple(Math.round(125 + f * 190).toFloat(), 0.75f, 0.52f)
    }

    /** 3 months, 1 year, 10 days: a Move's window, in the largest unit it divides into (as the computer shows it); the count and its plural. */
    fun span(days: Int): Pair<Int, Int> {
        val (unit, per) = listOf(R.plurals.sync_span_year to 365, R.plurals.sync_span_month to 30, R.plurals.sync_span_week to 7, R.plurals.sync_span_day to 1).first { (_, n) -> days % n == 0 }
        return days / per to unit
    }

    /**
     * Where a photo the computer sends is allowed to land.
     *
     * Android does not let an app file an image just anywhere: the first folder of a MediaStore path has to be
     * one of the few it considers a place for pictures, and anything else is refused outright. So the computer's
     * own layout is kept when it starts somewhere this phone can write to — a photo that left DCIM/Camera goes
     * back to DCIM/Camera — and everything else lands under Pictures/Tetra rather than failing.
     */
    fun incomingFolder(path: String, video: Boolean): String {
        val clean = path.trim('/').takeIf { it.isNotEmpty() && it.split('/').all { part -> part.isNotBlank() && part != "." && part != ".." } }
        val allowed = if (video) setOf("DCIM", "Movies", "Pictures") else setOf("DCIM", "Pictures")
        return clean?.takeIf { it.substringBefore('/') in allowed } ?: if (video) "Movies/Tetra" else "Pictures/Tetra"
    }

    /**
     * Face boxes are stored in the pixels of the bitmap PhotoClassifier analysed (longest side capped at 1280),
     * so the same formula turns them back into the fractions of the upright image that both apps sync
     * (SYNC_PLAN.md "Face box"). Null when MediaStore did not report a size.
     */
    fun analysisSize(width: Int, height: Int): Pair<Int, Int>? {
        if (width <= 0 || height <= 0) return null
        val scale = minOf(1f, 1280f / maxOf(width, height))
        return (width * scale).toInt() to (height * scale).toInt()
    }
}

/** MediaStore derives the gallery date from the file mtime when the image has no readable EXIF. */
internal fun publishReceivedPhoto(resolver: ContentResolver, uri: Uri, taken: Long?) {
    taken?.let { time ->
        val path = resolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        // A wrong date is worth a warning, not the photo: failing here would delete it and fetch it again forever.
        if (path == null || !File(path).setLastModified(time)) android.util.Log.w("SyncClient", "Could not keep the date of $uri")
    }
    check(resolver.update(uri, ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
}

/** Separate from photo_metadata.db: content hashes (by the gallery's photo key) and receipts from the computer. */
internal class SyncStore(private val context: Context) : SQLiteOpenHelper(context, "sync.db", null, 3) {
    private val prefs = context.getSharedPreferences("sync_pairing", Context.MODE_PRIVATE)

    private companion object {
        /**
         * Photos a Move has finished with: sent, and confirmed by the computer's own reading of the bytes. A
         * sync never removes anything itself — it cannot, and should not: taking a photo off a phone is
         * Android's own request with Android's own confirmation, and that needs a screen. So they wait here
         * until someone says yes, and until then the photo is exactly where it was.
         */
        const val MOVE_QUEUE = "CREATE TABLE IF NOT EXISTS to_remove (photo_key TEXT PRIMARY KEY, sha256 TEXT NOT NULL, queued_at INTEGER NOT NULL)"
        const val PEERS = "CREATE TABLE IF NOT EXISTS peers (fp TEXT PRIMARY KEY, name TEXT NOT NULL, hosts TEXT NOT NULL, " +
            "port INTEGER NOT NULL, token TEXT NOT NULL, their_token TEXT NOT NULL, paired_at INTEGER NOT NULL)"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE identity (photo_key TEXT PRIMARY KEY, sha256 TEXT NOT NULL)")
        db.execSQL("CREATE TABLE receipts (photo_key TEXT PRIMARY KEY, sha256 TEXT NOT NULL, path TEXT NOT NULL, sent_at INTEGER NOT NULL)")
        db.execSQL(PEERS)
        db.execSQL(MOVE_QUEUE)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL(PEERS)
        if (oldVersion < 3) db.execSQL(MOVE_QUEUE)
    }

    /**
     * Every device this one is paired with, not just the computer: a phone and a tablet pair with each other and
     * with the same computer, so one remembered pairing was never going to be enough. Identity is the pinned
     * certificate fingerprint, which is why it is the key.
     */
    fun peers(): List<Peer> = readableDatabase.rawQuery(
        "SELECT name, hosts, port, fp, token, their_token FROM peers ORDER BY paired_at", null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(Peer(c.getString(0), c.getString(1).split(',').filter(String::isNotBlank),
                c.getInt(2), c.getString(3), c.getString(4), c.getString(5)))
        }
    }

    fun savePeer(peer: Peer) {
        writableDatabase.insertWithOnConflict("peers", null, ContentValues().apply {
            put("fp", peer.fingerprint); put("name", peer.name); put("hosts", peer.hosts.joinToString(","))
            put("port", peer.port); put("token", peer.token); put("their_token", peer.theirToken)
            put("paired_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun forgetPeer(fingerprint: String) { writableDatabase.delete("peers", "fp = ?", arrayOf(fingerprint)) }

    // ponytail: one active pairing only (pairing a second device overwrites this one; receipts are global,
    // not per-device). See SYNC_PLAN.md "Not yet: multiple paired devices" for the upgrade path.
    fun pairing(): Pairing? {
        val token = prefs.getString("token", null) ?: return null
        return Pairing(prefs.getString("name", "Computer")!!, prefs.getString("hosts", "")!!.split(',').filter(String::isNotBlank),
            prefs.getInt("port", 0), prefs.getString("fp", "")!!, token)
    }
    fun savePairing(p: Pairing) = prefs.edit().putString("name", p.name).putString("hosts", p.hosts.joinToString(","))
        .putInt("port", p.port).putString("fp", p.fingerprint).putString("token", p.token).apply()
    fun forgetPairing() = prefs.edit().clear().apply()
    /**
     * The rules the computer last told us, kept only so the Sync page can say what a sync will do before one
     * runs. They are never the authority — the computer's answer at sync time is.
     */
    fun connections(): List<SyncConnection> = prefs.getString("connections", null)
        ?.split(';')?.mapNotNull { row -> row.split(',').takeIf { it.size >= 3 }?.let {
            SyncConnection(it[0], it[1], it[2], it.getOrNull(3)?.toIntOrNull() ?: 30, it.getOrNull(4) != "0", it.getOrNull(5) == "1", it.getOrNull(6)?.toLongOrNull())
        } }
        ?.takeIf { it.isNotEmpty() }?.let(SyncConnection::complete) ?: SyncConnection.defaults

    fun saveConnections(rows: List<SyncConnection>) =
        prefs.edit().putString("connections", rows.joinToString(";") { "${it.content},${it.direction},${it.keep},${it.keepDays},${if (it.keepFavorites) 1 else 0},${if (it.keepAuto) 1 else 0},${it.keepBytes ?: ""}" }).apply()

    /** Trash items already safe in the purgatory, so they are not sent twice while Android counts down. */
    fun handedOver(uri: String) = uri in prefs.getStringSet("purgatory_sent", emptySet())!!
    fun markHandedOver(uri: String) = prefs.edit().putStringSet("purgatory_sent", prefs.getStringSet("purgatory_sent", emptySet())!! + uri).apply()

    /** The computer's overview from the last sync (GET /overview), or null before the first one. */
    fun overview(): JSONObject? = prefs.getString("overview", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    fun saveOverview(json: String) = prefs.edit().putString("overview", json).apply()

    fun lastBackup(): Long = prefs.getLong("last_backup", 0)
    fun setLastBackup(at: Long) = prefs.edit().putLong("last_backup", at).apply()

    /** One line for the Home card. */
    fun summary(): String = pairing()?.let { receiptCount().let { n -> context.resources.getQuantityString(R.plurals.sync_summary, n, it.name, n) } } ?: context.getString(R.string.sync_no_computer_yet)

    fun cachedHash(photoKey: String): String? = readableDatabase.rawQuery("SELECT sha256 FROM identity WHERE photo_key = ?", arrayOf(photoKey)).use { if (it.moveToFirst()) it.getString(0) else null }
    fun saveHash(photoKey: String, sha256: String) { writableDatabase.insertWithOnConflict("identity", null, ContentValues().apply { put("photo_key", photoKey); put("sha256", sha256) }, SQLiteDatabase.CONFLICT_REPLACE) }
    /**
     * Everything is asked for again when the rules for accepting it change.
     *
     * `?since=` is an efficiency, and it quietly assumes that what we skipped before we would skip again. That
     * stops being true the moment this app learns to accept something it used to drop — as it did when a person
     * named on another device started being created here instead of ignored. Those people were named long ago,
     * so they sit outside every future window and would never arrive at all.
     *
     * So the rules carry a number. When it moves, the next sync asks from the beginning, once.
     */
    fun metadataSince(): Long = if (prefs.getInt("metadata_epoch", 0) == METADATA_EPOCH) prefs.getLong("last_metadata_sync", 0) else 0

    fun lastMetadataSync(): Long = prefs.getLong("last_metadata_sync", 0)
    fun setLastMetadataSync(at: Long) = prefs.edit().putLong("last_metadata_sync", at).putInt("metadata_epoch", METADATA_EPOCH).apply()
    fun saveReceipt(photoKey: String, sha256: String, path: String) { writableDatabase.insertWithOnConflict("receipts", null, ContentValues().apply {
        put("photo_key", photoKey); put("sha256", sha256); put("path", path); put("sent_at", System.currentTimeMillis())
    }, SQLiteDatabase.CONFLICT_REPLACE) }
    /** Verified on the computer, and this connection says Keep Nothing: waiting for someone to say yes. */
    fun queueForRemoval(photoKey: String, sha256: String) {
        writableDatabase.insertWithOnConflict("to_remove", null, ContentValues().apply {
            put("photo_key", photoKey); put("sha256", sha256); put("queued_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // Photos deleted on the computer that Android would not let this app trash by itself (no Media management):
    // they wait for one tap on the Sync page, with Android's own confirmation (Both ways trash, 2026-09-26).
    private fun trashTable() = writableDatabase.execSQL("CREATE TABLE IF NOT EXISTS to_trash (photo_key TEXT PRIMARY KEY, sha256 TEXT NOT NULL, queued_at INTEGER NOT NULL)")
    fun queueForTrash(photoKey: String, sha256: String) { trashTable(); writableDatabase.insertWithOnConflict("to_trash", null, ContentValues().apply {
        put("photo_key", photoKey); put("sha256", sha256); put("queued_at", System.currentTimeMillis())
    }, SQLiteDatabase.CONFLICT_REPLACE) }
    fun queuedForTrash(): Set<String> { trashTable(); return readableDatabase.rawQuery("SELECT photo_key FROM to_trash", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } } }
    fun forgetTrash(photoKeys: Collection<String>) { if (photoKeys.isEmpty()) return; trashTable()
        writableDatabase.delete("to_trash", "photo_key IN (${photoKeys.joinToString { "?" }})", photoKeys.toTypedArray()) }

    fun queuedForRemoval(): Set<String> = readableDatabase.rawQuery("SELECT photo_key FROM to_remove", null)
        .use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    fun forgetQueued(photoKeys: Collection<String>) {
        if (photoKeys.isEmpty()) return
        writableDatabase.delete("to_remove", "photo_key IN (${photoKeys.joinToString { "?" }})", photoKeys.toTypedArray())
    }

    /** The gallery keys of every photo given to the computer: People still shows their named people (24 Oct). */
    fun receiptKeys(): Set<String> = readableDatabase.rawQuery("SELECT photo_key FROM receipts", null).use { c ->
        buildSet { while (c.moveToNext()) add(c.getString(0)) }
    }

    fun receiptCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM receipts", null).use { it.moveToFirst(); it.getInt(0) }

    /** Every photo this phone has given the computer — the proof that a copy coming back would be one it deleted. */
    fun receiptShas(): Set<String> = readableDatabase.rawQuery("SELECT DISTINCT sha256 FROM receipts", null).use { c ->
        buildSet { while (c.moveToNext()) add(c.getString(0)) }
    }
}

/** [unreachable]: the computer did not answer at any address (the Sync page says so). */
internal class SyncException(message: String, val unreachable: Boolean = false) : Exception(message)

/** Talks only to the paired computer: HTTPS whose certificate must match the QR's SHA-256 fingerprint. */
/**
 * Which rules this app applies to metadata it receives. Raise it whenever it starts accepting something it used
 * to ignore, and every device asks for everything once more — otherwise what was skipped stays skipped for ever.
 *
 * 2: a person named on another device is created here rather than dropped (SYNC_PLAN.md 6y).
 */
/** Days before Android's own deletion at which a trashed photo goes to the purgatory (D6). */
private const val HAND_OVER_DAYS = 3
/** Files' Trash has no clock of its own; the same 30 days as Android's (D6). */
private const val FILES_TRASH_DAYS = 30
private const val METADATA_EPOCH = 6 // 5: reconcile every local copy of a face with the computer's group. 6: pull again the
// collection members that were dropped while their folder was hidden here (2026-09-25)

/** How many files cross at once. Four keeps the link busy; more turns a phone's Wi-Fi into stalled sockets. */
private const val AT_ONCE = 4

internal class SyncClient(private val context: Context, private val store: SyncStore) {
    private val driveMetadata by lazy { DriveMetadata(context) }
    private val driveRecents by lazy { DriveRecents(context) }
    private val driveManifest by lazy { DriveManifest(context) }
    // A sync can run with Tetra's window closed: a Move must still know the chosen System folders, or it could remove one left empty.
    init { SystemFolderChoice.load(context) }

    /** One line of a sync's failures: what went wrong, then why ("Notes: …"; the Sync page splits it there). */
    private fun fail(@androidx.annotation.StringRes what: Int, why: String?) = context.getString(R.string.sync_failure, context.getString(what), why.orEmpty())

    private fun open(host: String, port: Int, fingerprint: String, path: String, method: String, token: String?): HttpsURLConnection {
        val pinned = object : X509TrustManager {
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                val actual = SyncRules.hex(MessageDigest.getInstance("SHA-256").digest(chain.first().encoded))
                if (actual != fingerprint) throw CertificateException(context.getString(R.string.sync_not_paired_computer))
            }
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = throw CertificateException("Not a server.")
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(pinned), null) }
        return (URL("https://$host:$port$path").openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = tls.socketFactory
            hostnameVerifier = HostnameVerifier { _, _ -> true } // identity is the pinned certificate, not a hostname
            requestMethod = method
            connectTimeout = 4_000
            readTimeout = 120_000
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
    }

    private fun HttpsURLConnection.jsonResult(): JSONObject {
        val ok = responseCode in 200..299
        val text = (if (ok) inputStream else errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        val body = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        if (!ok) throw SyncException(body.optString("error", context.getString(R.string.sync_computer_answered, responseCode)))
        return body
    }

    private fun postJson(host: String, p: Pairing, path: String, body: JSONObject): JSONObject =
        open(host, p.port, p.fingerprint, path, "POST", p.token).run {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            outputStream.use { it.write(body.toString().toByteArray()) }
            jsonResult().also { disconnect() }
        }

    /** The tablet's Phone (PhoneRelay): a call placed, or a button pressed, by the phone through the computer. */
    fun phoneViaComputer(path: String): JSONObject { val p = reach(store.pairing() ?: throw SyncException(context.getString(R.string.sync_not_paired))); return postJson(p.hosts.first(), p, path, JSONObject()) }



    /** Data › Signal: what the computer sees of its Wi-Fi now (signal, speed, network), for "Phone as hotspot". */
    fun computerWifi(): JSONObject { val p = reach(store.pairing() ?: throw SyncException(context.getString(R.string.sync_not_paired))); return postJson(p.hosts.first(), p, "/data/wifi", JSONObject()) }

    /** The computer's address now, for Data's loss test (a ping to it). */
    fun computerHost(): String = reach(store.pairing() ?: throw SyncException(context.getString(R.string.sync_not_paired))).hosts.first()

    /** Data › Signal: the Wi-Fi's real speed to the computer — [bytes] down from it, then up to it; Mb/s down, up, and the ping in ms. */
    fun speedTest(bytes: Int = 20_000_000): Triple<Double, Double, Long> {
        val p = reach(store.pairing() ?: throw SyncException(context.getString(R.string.sync_not_paired)))
        val host = p.hosts.first()
        val t0 = System.nanoTime(); postJson(host, p, "/data/wifi", JSONObject()); val ping = (System.nanoTime() - t0) / 1_000_000
        val d0 = System.nanoTime()
        val got = open(host, p.port, p.fingerprint, "/data/speed?n=$bytes", "GET", p.token).run { inputStream.use { s -> val b = ByteArray(65536); var n = 0L; while (true) { val r = s.read(b); if (r < 0) break; n += r }; n }.also { disconnect() } }
        val down = got * 8 / ((System.nanoTime() - d0) / 1e9) / 1e6
        val u0 = System.nanoTime()
        open(host, p.port, p.fingerprint, "/data/speed", "POST", p.token).run {
            doOutput = true; setFixedLengthStreamingMode(bytes); setRequestProperty("Content-Type", "application/octet-stream")
            outputStream.use { o -> val b = ByteArray(65536); var left = bytes; while (left > 0) { val n = minOf(left, b.size); o.write(b, 0, n); left -= n } }
            jsonResult().also { disconnect() }
        }
        val up = bytes * 8 / ((System.nanoTime() - u0) / 1e9) / 1e6
        return Triple(down, up, ping)
    }

    /** A call's state for the computer's call bubble (PhoneCall.Watcher): ringing, active, ended. */
    /** AI Assist's send_to_device: to the computer, which does it or passes it on (sync.js assistDo). */
    fun assistDo(c: JSONObject): JSONObject { val p = reach(store.pairing() ?: throw SyncException(context.getString(R.string.sync_not_paired))); return postJson(p.hosts.first(), p, "/assist/do", c) }
    fun callState(state: JSONObject) { val p = reach(store.pairing() ?: return); postJson(p.hosts.first(), p, "/call/state", state.put("country", PhoneCountry.of(context))) }

    /** First address that answers wins; a changed network may still reach one of the others. */
    private fun <T> anyHost(hosts: List<String>, call: (String) -> T): T {
        var last: Exception? = null
        for (host in hosts) try { return call(host) } catch (e: SyncException) { throw e } catch (e: Exception) { last = e }
        throw SyncException(context.getString(R.string.sync_unreachable, last?.message ?: context.getString(R.string.sync_no_address)), unreachable = true)
    }

    /**
     * The address the computer answers on *now*. The ones pairing knew come first, and if none of them answer
     * the beacon asks who out there holds the pinned certificate (SYNC_PLAN.md 6k) — a new IP after a router
     * restart, or a different network entirely, stops being the end of the pairing. Whatever answers is
     * remembered, so the next sync starts there.
     */
    private fun reach(p: Pairing): Pairing {
        var last: Exception? = null
        fun tryHost(candidate: Pairing): Pairing? = try {
            postJson(candidate.hosts.first(), candidate, "/have", JSONObject().put("hashes", JSONArray()))
            candidate
        } catch (e: SyncException) { throw e } catch (e: Exception) { last = e; null }

        for (host in p.hosts) tryHost(p.copy(hosts = listOf(host) + (p.hosts - host)))?.let { return it }
        for ((host, port) in SyncDiscovery.find(p.fingerprint)) {
            tryHost(p.copy(hosts = listOf(host) + (p.hosts - host), port = port))?.let { store.savePairing(it); return it }
        }
        throw SyncException(context.getString(R.string.sync_unreachable, last?.message ?: context.getString(R.string.sync_no_address)), unreachable = true)
    }

    /**
     * Scanning hands over both halves at once: we prove we saw the code, and we say who we are — our own
     * certificate fingerprint, the port we listen on and a token the other device sends when it calls us.
     * Between two phones neither side is the client, so a pairing that only travelled one way would leave one
     * of them unable to ever start a sync. A computer simply ignores the extra fields.
     */
    fun pair(qr: PairingQr): Pairing = anyHost(qr.hosts) { host ->
        val ourToken = SyncRules.hex(ByteArray(32).also(java.security.SecureRandom()::nextBytes))
        val ourFingerprint = runCatching { SyncServer.fingerprint(SyncServer.identity().second) }.getOrNull()
        val body = open(host, qr.port, qr.fingerprint, "/pair", "POST", null).run {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            val request = JSONObject().put("code", qr.code).put("name", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("port", SyncServer.PORT).put("hosts", JSONArray(SyncServer.lanAddresses()))
                .put("token", ourToken)
                // Android's own answer to "phone or tablet": the narrowest the screen ever gets, in dp. 369 here,
                // 777 on the tablet, and 600 is where Android itself draws the line — so the computer can pick
                // the right picture without asking anyone (SYNC_PLAN.md 6af).
                .put("widthDp", context.resources.configuration.smallestScreenWidthDp)
            ourFingerprint?.let { request.put("fp", it) }
            outputStream.use { it.write(request.toString().toByteArray()) }
            jsonResult().also { disconnect() }
        }
        val hosts = listOf(host) + (qr.hosts - host)
        val name = body.optString("name", qr.name)
        store.savePeer(Peer(name, hosts, qr.port, qr.fingerprint, body.getString("token"), ourToken))
        Pairing(name, hosts, qr.port, qr.fingerprint, body.getString("token")).also(store::savePairing)
            .also { SyncListenService.start(context) } // paired with a computer: listening for it from now on
    }

    /**
     * The rows this device is to obey. The computer owns them; if it is too old to answer, Send & receive is
     * assumed, which is what every pairing before this change did.
     */
    private fun connections(host: String, p: Pairing): List<SyncConnection> = runCatching {
        val body = open(host, p.port, p.fingerprint, "/connections", "GET", p.token).jsonResult()
        val array = body.optJSONArray("connections") ?: return@runCatching SyncConnection.defaults
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            SyncConnection(o.getString("content"), o.optString("direction", "both"), o.optString("keep", "everything"),
                o.optInt("keepDays", 30).coerceAtLeast(1), o.optBoolean("keepFavorites", true), o.optBoolean("keepAuto"),
                o.optLong("keepBytes", -1).takeIf { it >= 0 && !o.isNull("keepBytes") })
        }.ifEmpty { SyncConnection.defaults }.let(SyncConnection::complete)
    }.getOrDefault(SyncConnection.defaults).also(store::saveConnections)

    /**
     * Move a list of things, a few at a time, and report each one as it finishes.
     *
     * One file at a time left the link idle between files — most of a small file's time is the round trip, not
     * the bytes — and on a real library that is thousands of round trips spent waiting. Four at once is enough
     * to keep the link busy without turning a phone's Wi-Fi into a queue of stalled sockets.
     *
     * Each item is tried **twice**: a single dropped connection (a phone hopping access points) is not a file
     * that failed, it is the same request a second later. Whatever still fails is named, one line per file, and
     * nothing partial is kept, so the next sync simply asks again.
     *
     * Stop and Pause are checked before each item is picked up, so they land between files, never inside one.
     */
    private suspend fun <T> eachInParallel(
        items: List<T>,
        stage: String,
        checkpoint: suspend () -> Unit,
        progress: (BackupProgress) -> Unit,
        failed: MutableList<String>,
        name: (T) -> String,
        work: suspend (T) -> Unit,
    ): Int = coroutineScope {
        if (items.isEmpty()) return@coroutineScope 0
        val limit = Semaphore(AT_ONCE)
        val done = AtomicInteger(0)
        val succeeded = AtomicInteger(0)
        items.map { item ->
            async(Dispatchers.IO) {
                limit.withPermit {
                    coroutineContext.ensureActive()
                    checkpoint()
                    var result = runCatching { work(item) }
                    if (result.isFailure && result.exceptionOrNull() !is kotlinx.coroutines.CancellationException) {
                        delay(1_000)
                        result = runCatching { work(item) }
                    }
                    result.onSuccess { succeeded.incrementAndGet() }
                    progress(BackupProgress(stage, done.incrementAndGet(), items.size))
                    result.exceptionOrNull()?.let { failure ->
                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                        synchronized(failed) { failed += "${name(item)}: ${failure.message}" }
                    }
                }
            }
        }.awaitAll()
        succeeded.get()
    }

    /**
     * A copy that notices Stop. `copyTo` hands a whole 400MB video to the socket before anything looks at the
     * coroutine again, which is why Stop did nothing until the video in flight had finished (24 Sept).
     * ponytail: a read already blocked on a dead socket still waits out readTimeout; closing the connection
     * from outside is the fix if that ever matters.
     */
    private suspend fun pump(input: java.io.InputStream, output: java.io.OutputStream, digest: MessageDigest? = null) {
        val buffer = ByteArray(256 * 1024)
        while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            digest?.update(buffer, 0, n)
            output.write(buffer, 0, n)
        }
    }





    /**
     * One sync, in whichever directions the computer's connection rows allow (SYNC_PLAN.md 6i and 6j).
     *
     * Sending is what it always was: every gallery photo and video the computer does not have, each verified by
     * its SHA-256 there. Receiving is the same idea read backwards — what the computer holds and this phone does
     * not. Nothing is deleted on either side by either half; a Copy adds, and that is all it does.
     */
    /** Tetra Auto's sync: the files (the maps the computer makes, AI Assist's, the Browser's, Contacts'), no photos, notes or mail. */
    suspend fun backUp(checkpoint: suspend () -> Unit = {}, progress: (BackupProgress) -> Unit): BackupResult {
        val paired = store.pairing() ?: throw SyncException(context.getString(R.string.sync_pair_first))
        val p = reach(paired)
        val host = p.hosts.first()
        val failed = mutableListOf<String>()
        val rows = connections(host, p).associateBy { it.content }
        var received = 0
        runCatching { received += syncFiles(host, p, rows, failed, checkpoint, progress) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else failed += fail(R.string.sync_fail_drive_files, it.message) }
        runCatching { store.saveOverview(open(host, p.port, p.fingerprint, "/overview", "GET", p.token).jsonResult().toString()) }
        progress(BackupProgress(context.getString(R.string.common_done), 0, 0))
        store.setLastBackup(System.currentTimeMillis())
        return BackupResult(0, 0, 0, failed, received)
    }








    /**
     * One part of Drive alone, at once ([DriveScope]: the Calendar), by the same rules and the same requests as the
     * whole sync; the rest waits for that. What arrives re-plans the reminders. The computer away: it throws, and
     * [ScopedSync] tries again when there is a network.
     */
    suspend fun scopeOnly(scope: String): Int {
        val p = reach(store.pairing() ?: return 0)
        val host = p.hosts.first()
        val rows = connections(host, p).associateBy { it.content }
        if (rows[if (scope == "mail") "mail" else "other"]?.let { !it.sends && !it.receives } == true) return 0 // that part's row is off
        val failed = mutableListOf<String>()
        val counts = IntArray(1)
        val n = syncFiles(host, p, rows, failed, {}, {}, scope, counts)
        if (scope == "contacts") ContactsAccount.soon(context) // a contact changed elsewhere: the phone's copy follows
        if (failed.isNotEmpty()) throw SyncException(failed.joinToString("; "))
        return n + counts[0] // what came here, and what went there or moved
    }

    private suspend fun syncFiles(host: String, p: Pairing, rows: Map<String, SyncConnection>, failed: MutableList<String>, checkpoint: suspend () -> Unit, progress: (BackupProgress) -> Unit, scope: String? = null, counts: IntArray? = null): Int {
        val root = TetraFolder.root(android.os.Environment.getExternalStorageDirectory())
        if (!root.isDirectory) return 0
        // each path by its own row; the part sync runs while any of files, mail and other is on
        val row = { rel: String -> SyncConnection.of(rel).let { rows[it] ?: SyncConnection.defaults.first { d -> d.content == it } } }
        if (rows.values.none { it.content != "photos" && (it.sends || it.receives) }) return 0
        if (scope == null) runCatching { SyncRules.sweepParts(root) }
        val entries = if (scope == null) driveManifest.entries(root) else driveManifest.entries(root) { DriveScope.contains(scope, it) }
        val me = thisDevice()
        val manifest = JSONArray(entries.map { JSONObject().put("path", it.relativePath).put("sha256", it.sha256).put("size", it.sizeBytes).put("modified", it.modified) })
        // and what this device says of itself: its free storage (the card suggests moving old things when nearly full), Mail's name for it
        val stat = runCatching { android.os.StatFs(root.path) }.getOrNull()
        val about = JSONObject().put("mail", me).apply { stat?.let { put("free", it.availableBytes).put("size", it.totalBytes) } }
        // a part's list goes to its own address: a computer too old for parts answers "Unknown request" instead of
        // taking it for the whole Drive (and offering everything else back as copies)
        val answer = postJson(host, p, if (scope == null) "/files/manifest" else "/files/manifest/scoped", JSONObject().put("files", manifest).put("device", about).apply { if (scope != null) put("scope", scope) })
        val want = answer.optJSONArray("want") ?: JSONArray()
        val sent = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        run {
            val byPath = entries.associateBy { it.relativePath }
            // never the PC's own Mail files back (when both changed, the PC asks: its version wins here)
            val mine = (0 until want.length()).mapNotNull { byPath[want.getString(it)] }.filter { row(it.relativePath).sends && !it.relativePath.startsWith("Mail/") } // no mail in the car
            eachInParallel(mine, context.getString(R.string.sync_stage_sending_files), checkpoint, progress, failed, { it.relativePath }) { entry ->
                val file = File(root, TetraSystem.onDisk(entry.relativePath))
                uploadFile(host, p, entry, file)
                sent += entry.relativePath
            }
            counts?.let { it[0] += sent.size + (answer.optJSONArray("moved")?.length() ?: 0) }
            // A Move keeps a window of files here and deletes the older ones — files only; system folders stay,
            // regular folders left empty go (asked 2026-09-25). Only what the computer confirmed holding in this sync
            // goes: it did not ask for it, or it just received it verified. Each path by its row's window, its last
            // keepDays (Automatic keeps everything). Mail has its own plan, below.
            if (scope == null) { // a part's sync never lets files go
                val asked = (0 until want.length()).mapTo(HashSet()) { want.getString(it) }
                val now = System.currentTimeMillis()
                val favorites = driveMetadata.records().filter { it.favorite }.mapTo(HashSet()) { it.path }
                val held = entries.filter { e -> val r = row(e.relativePath)
                    r.content != "mail" && r.sends && r.moves && !e.relativePath.startsWith("Trash/") && (e.relativePath !in asked || e.relativePath in sent) &&
                        e.modified > 0 && !(r.keepFavorites && e.relativePath in favorites)
                }
                held.filter { it.modified < now - row(it.relativePath).keepDays * 86_400_000L }.also { going ->
                    // Deleted, not trashed: the computer holds each one (asked 2026-09-25 — a Trash only keeps the room).
                    going.forEach { e -> runCatching { check(DriveRules.file(root, e.relativePath).delete()) { context.getString(R.string.sync_not_deleted) } }.onFailure { failed += "${e.relativePath}: ${it.message}" } }
                    DriveRules.removeEmptyFolders(root, going.map { DriveRules.parent(it.relativePath) }.toSet())
                }
            }
        }
        // A move the computer made is followed, never downloaded: the bytes are already here, under another name.
        val moveTo = answer.optJSONArray("moveTo")?.let { a -> (0 until a.length()).map(a::getJSONObject) }.orEmpty()
            .filter { row(it.optString("from")).receives && row(it.optString("to")).receives }
        counts?.let { it[0] += moveTo.size }
        for (move in moveTo) runCatching {
            moveFile(root, move.getString("from"), move.getString("to"))
        }.onFailure { failed += fail(R.string.sync_fail_moving, it.message) }

        val have = answer.optJSONArray("have") ?: JSONArray()
        val rest = (0 until have.length()).map(have::getJSONObject).filter { row(it.optString("path")).receives && !it.optString("path").startsWith("Mail/") }
        return eachInParallel(rest, context.getString(R.string.sync_stage_receiving_files), checkpoint, progress, failed, { it.optString("path") }) { file ->
            receiveFile(host, p, root, file)
        }
    }

    /**
     * The same rules as a file arriving on the computer: verified into a .part, and nothing is ever replaced — except
     * an edit ("replaces": the computer's newer version of what is here, 29 September), and only when what is here is
     * still exactly that older version; it is then kept in the app's "Files history", outside Drive.
     */
    private suspend fun receiveFile(host: String, p: Pairing, root: File, file: JSONObject) {
        val sha = file.getString("sha256")
        val rel = file.getString("path")
        val replaces = file.optString("replaces").takeIf { it.length == 64 }
        val here = runCatching { DriveRules.file(root, rel) }.getOrNull()?.takeIf { it.isFile }
        val hereSha = if (here != null) sha256(here) else null
        // Those very bytes are already at that path (two syncs asked at once): nothing to add. 330 of Personal's 334
        // "(2)" events were byte for byte their original (23 Oct).
        if (hereSha == sha) return
        val update = replaces != null && hereSha == replaces
        val target = if (update) here!! else DriveRules.newFile(root, rel)
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, ".${target.name}.${java.util.UUID.randomUUID()}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val connection = open(host, p.port, p.fingerprint, "/file/$sha?path=${URLEncoder.encode(rel, "UTF-8")}", "GET", p.token)
            connection.inputStream.use { input -> part.outputStream().use { output -> pump(input, output, digest) } }
            connection.disconnect()
            if (SyncRules.hex(digest.digest()) != sha) throw SyncException(context.getString(R.string.sync_changed_on_way))
            if (update) File(context.filesDir, "Files history/${System.currentTimeMillis()}/$rel").let { old ->
                old.parentFile?.mkdirs()
                target.copyTo(old, overwrite = true)
            }
            if (!part.renameTo(target)) throw SyncException(context.getString(R.string.sync_not_in_place))
            // a mail keeps its arrival here as its time: let go a week after it came or was opened, not by the mail's date
            file.optLong("modified").takeIf { it > 0 && !rel.startsWith("Mail/") }?.let { target.setLastModified(it) }
            driveManifest.remember(DriveRules.relative(root, target), target, sha)
        } finally {
            part.delete()
        }
    }




    private suspend fun sha256(f: File): String = SyncRules.hex(MessageDigest.getInstance("SHA-256").also { d -> f.inputStream().use { pump(it, java.io.OutputStream.nullOutputStream(), d) } }.digest())

    /**
     * Follows a move the computer made. It never overwrites: if something else is already there, both are kept; the
     * same bytes already there are the move done, so the source goes rather than land as "(2)".
     */
    private suspend fun moveFile(root: File, from: String, to: String) {
        val source = DriveRules.file(root, from)
        val there = runCatching { DriveRules.file(root, to) }.getOrNull() // null: nothing there yet
        if (from != to && there != null && there.length() == source.length() && sha256(there) == sha256(source)) {
            if (!source.delete()) throw SyncException(context.getString(R.string.sync_not_moved, from, to))
            return
        }
        val target = DriveRules.newFile(root, to)
        target.parentFile?.mkdirs()
        if (!source.renameTo(target)) throw SyncException(context.getString(R.string.sync_not_moved, from, to))
    }

    private suspend fun uploadFile(host: String, p: Pairing, entry: DriveFileEntry, file: File) {
        val path = "/file/${entry.sha256}?path=${URLEncoder.encode(entry.relativePath, "UTF-8")}&modified=${entry.modified}"
        val c = open(host, p.port, p.fingerprint, path, "PUT", p.token)
        c.doOutput = true
        c.setFixedLengthStreamingMode(entry.sizeBytes)
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.outputStream.use { out -> file.inputStream().use { pump(it, out) } }
        val receipt = c.jsonResult().also { c.disconnect() }
        if (!receipt.optBoolean("verified")) throw SyncException(context.getString(R.string.sync_no_receipt, entry.relativePath))
    }



    private inline fun JSONObject.each(name: String, block: (JSONObject) -> Unit) {
        val array = optJSONArray(name) ?: return
        for (i in 0 until array.length()) runCatching { block(array.getJSONObject(i)) }
    }
}
