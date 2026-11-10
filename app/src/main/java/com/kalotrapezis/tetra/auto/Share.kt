package com.kalotrapezis.tetra.auto

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Share (the user, 10 Nov: "the car console can't share anything with the phone", and Bluetooth there carries calls only):
 * files both ways over the link. What the phone sends is kept in the app's Received folder, checked by its SHA-256 before it
 * is kept; an app (.apk, a Tetra Auto update) is offered for installing. The car sends what its own picker finds (a USB stick).
 */
internal class Share(private val context: Context) {
    val dir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "Received").apply { mkdirs() }
    var files by mutableStateOf(list())
        private set
    /** What is on its way, either way: name → share done (0..1). */
    var moving by mutableStateOf(mapOf<String, Float>())
        private set
    /** The last thing to tell (received, sent, failed). */
    var said by mutableStateOf<String?>(null)
    /** An app the phone just sent: the screen offers to install it. */
    var newApp by mutableStateOf<File?>(null)

    fun list() = dir.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") }.sortedByDescending { it.lastModified() }
    fun refresh() { files = list() }
    fun delete(f: File) { f.delete(); refresh() }

    private class In(val name: String, val size: Long, val part: File, val out: OutputStream, val digest: MessageDigest, var got: Long = 0)
    private val incoming = mutableMapOf<Int, In>()

    /** A frame of a file from the phone; [reply] answers it. Runs on the link's thread. */
    fun incoming(type: Byte, body: ByteArray, reply: (Byte, ByteArray) -> Unit) {
        when (type) {
            Wire.FILE_BEGIN -> {
                val o = JSONObject(String(body))
                val id = o.getInt("id"); val name = Wire.safeName(o.optString("name"))
                val part = File(dir, ".$id.part")
                incoming.remove(id)?.let { runCatching { it.out.close() }; it.part.delete() }
                incoming[id] = In(name, o.optLong("size"), part, part.outputStream().buffered(1 shl 16), MessageDigest.getInstance("SHA-256"))
                progress(name, 0f)
            }
            Wire.FILE_DATA -> {
                val id = ByteBuffer.wrap(body, 0, 4).int
                val f = incoming[id] ?: return
                f.out.write(body, 4, body.size - 4); f.digest.update(body, 4, body.size - 4); f.got += body.size - 4
                if (f.size > 0) progress(f.name, (f.got.toFloat() / f.size).coerceIn(0f, 1f))
            }
            Wire.FILE_END -> {
                val o = JSONObject(String(body)); val id = o.getInt("id")
                val f = incoming.remove(id) ?: return
                runCatching { f.out.close() }
                val sha = f.digest.digest().joinToString("") { "%02x".format(it) }
                val ok = sha == o.optString("sha256").lowercase()
                val kept = if (ok) free(f.name).also { f.part.renameTo(it) } else null
                f.part.delete()
                progress(f.name, null)
                reply(Wire.FILE_DONE, JSONObject().put("id", id).put("ok", ok).apply { if (!ok) put("error", "The file changed on the way.") }.toString().toByteArray())
                Log.i(TAG, "Received ${f.name}: ${if (ok) "kept" else "hash did not match"}")
                said = if (ok) context.getString(R.string.share_received, f.name) else context.getString(R.string.share_broken, f.name)
                refresh()
                if (kept != null && kept.name.endsWith(".apk", true)) newApp = kept
            }
        }
    }

    /** [name] in the folder, or "name (2).ext" when it is taken. */
    private fun free(name: String): File {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        return generateSequence(1) { it + 1 }.map { n -> File(dir, if (n == 1) name else "${name.substring(0, dot)} ($n)${name.substring(dot)}") }.first { !it.exists() }
    }

    private fun progress(name: String, done: Float?) { moving = if (done == null) moving - name else moving + (name to done) }

    private var nextId = (System.currentTimeMillis() % 100_000).toInt() * 1000
    private val sending = mutableMapOf<Int, String>()

    /** Files the user picked, to the phone, one after another (on a thread of its own). */
    fun send(link: Link, uris: List<Uri>) = Thread({
        for (uri in uris) runCatching {
            val name = Wire.safeName(context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: uri.lastPathSegment ?: "file")
            val size = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else -1L } ?: -1L
            val id = nextId++
            synchronized(sending) { sending[id] = name }
            check(link.send(Wire.FILE_BEGIN, JSONObject().put("id", id).put("name", name).put("size", size).toString().toByteArray())) { context.getString(R.string.share_no_phone) }
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(Wire.CHUNK + 4); ByteBuffer.wrap(buf).putInt(id)
            var sent = 0L
            context.contentResolver.openInputStream(uri)!!.use { input ->
                while (true) {
                    val n = input.read(buf, 4, Wire.CHUNK); if (n < 0) break
                    digest.update(buf, 4, n); sent += n
                    check(link.send(Wire.FILE_DATA, buf, 0, n + 4)) { context.getString(R.string.share_no_phone) }
                    if (size > 0) progress(name, (sent.toFloat() / size).coerceIn(0f, 1f))
                }
            }
            link.send(Wire.FILE_END, JSONObject().put("id", id).put("sha256", digest.digest().joinToString("") { "%02x".format(it) }).toString().toByteArray())
            Log.i(TAG, "Sent $name ($sent bytes)")
        }.onFailure { said = it.message; Log.w(TAG, "Not sent: ${it.message}") }
    }, "tetra-auto-send").start()

    /** The phone's answer to a file sent. */
    fun answered(o: JSONObject) {
        val name = synchronized(sending) { sending.remove(o.optInt("id")) } ?: return
        progress(name, null)
        said = if (o.optBoolean("ok")) context.getString(R.string.share_sent, name) else context.getString(R.string.share_not_taken, name, o.optString("error"))
    }

    /** Opens [f]: an app goes to Android's installer (Tetra Auto's own updates among them), anything else to the app for it. */
    fun open(f: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        val apk = f.name.endsWith(".apk", true)
        if (apk && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            said = context.getString(R.string.share_allow_install); return
        }
        val type = if (apk) "application/vnd.android.package-archive"
            else android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { said = context.getString(R.string.share_no_app, f.name) }
    }
}
