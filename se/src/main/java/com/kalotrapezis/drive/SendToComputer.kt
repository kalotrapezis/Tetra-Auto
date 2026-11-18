package com.kalotrapezis.drive

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Send to computer (the user, 23 Oct): Tetra in Android's share menu. What is shared goes to the paired computer at
 * once, over the Wi-Fi link sync already has (desktop sync.js PUT /send), into its Files › From phone. Each file is
 * first copied into a private outbox — another app's permission to read it ends with this screen — so with the
 * computer away the files wait there and go when it is seen: a JobScheduler retry, the next sync, or its "sync now".
 */
class SendToComputer : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.streamExtra())
            Intent.ACTION_SEND_MULTIPLE -> intent.streamsExtra()
            else -> emptyList()
        }
        val computer = SyncStore(this).pairing()?.name
        when {
            computer == null -> { Toast.makeText(this, getString(R.string.sync_send_no_computer), Toast.LENGTH_LONG).show(); return finish() }
            uris.isEmpty() -> { Toast.makeText(this, getString(R.string.sync_send_nothing), Toast.LENGTH_LONG).show(); return finish() }
        }
        val app = applicationContext
        thread(name = "tetra-send") {
            val failed = uris.count { uri -> runCatching { val (name, modified) = describe(uri); Outbox.add(app, contentResolver.openInputStream(uri)!!, name, modified) }.onFailure { WriterLog.w("Send to computer: $uri: ${it.message}") }.isFailure }
            runOnUiThread {
                if (failed > 0) Toast.makeText(this, getString(R.string.sync_send_unreadable, failed, uris.size), Toast.LENGTH_LONG).show()
                finish()
            }
            Outbox.soon(app, say = true)
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamExtra(): Uri? = getParcelableExtra(Intent.EXTRA_STREAM)
    @Suppress("DEPRECATION")
    private fun Intent.streamsExtra(): List<Uri> = getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()

    /** Its name, and when it was last changed where the provider says (a photo keeps its own date on the computer), else 0. */
    private fun describe(uri: Uri): Pair<String, Long> {
        val named = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
        val modified = runCatching { // seconds, in MediaStore and the system's own pickers; not every app gives it
            contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) * 1000 else 0 }
        }.getOrNull() ?: 0
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(contentResolver.getType(uri))
        return Outbox.safeName(named, ext) to modified
    }
}

/** Files on their way to the computer: filesDir/outbox/<sha256>/<name>, each gone once the computer has it verified. */
internal object Outbox {
    private const val WAITING = 4143
    const val EXTRA = "outbox"
    private val running = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun dir(context: Context) = File(context.filesDir, "outbox")

    /** A name the computer takes: no folders, no control characters; one is made up when the sending app gave none. */
    fun safeName(name: String?, ext: String?): String {
        val clean = fitName(name.orEmpty().substringAfterLast('/').substringAfterLast('\\').filter { it >= ' ' }.trim())
        return clean.takeIf { it.isNotEmpty() && it != "." && it != ".." }
            ?: "Shared ${java.text.SimpleDateFormat("yyyy-MM-dd HHmmss", java.util.Locale.ROOT).format(java.util.Date())}${ext?.let { ".$it" }.orEmpty()}"
    }

    /** Within [max] bytes: the disk allows 255 and a Greek letter is two (the user's PDF, 23 Oct); the extension kept, room for " (2)". */
    fun fitName(name: String, max: Int = 200): String {
        if (name.toByteArray().size <= max) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 && name.length - it <= 20 } ?: name.length
        val ext = name.substring(dot)
        var base = name.substring(0, dot)
        while (base.isNotEmpty() && (base + ext).toByteArray().size > max) base = base.substring(0, base.offsetByCodePoints(base.length, -1))
        return base.trimEnd() + ext
    }

    fun add(context: Context, input: InputStream, name: String, modified: Long = 0): File {
        val tmp = File(dir(context), "." + UUID.randomUUID()).apply { mkdirs() }
        val digest = MessageDigest.getInstance("SHA-256")
        input.use { DigestOutputStream(File(tmp, name).outputStream(), digest).use(it::copyTo) }
        if (modified > 0) File(tmp, name).setLastModified(modified) // what the computer gets as its date
        val held = File(dir(context), SyncRules.hex(digest.digest()))
        if (held.exists()) { tmp.deleteRecursively(); return File(held, name) } // shared twice: it goes once
        check(tmp.renameTo(held)) { "could not keep it" }
        return File(held, name)
    }

    fun waiting(context: Context): List<File> = dir(context).listFiles()?.filter { it.isDirectory && it.name.length == 64 }
        ?.sortedBy { it.lastModified() }?.mapNotNull { d -> d.listFiles()?.firstOrNull { it.isFile } }.orEmpty()

    /** Try now; the computer away, Android tries again once there is a network, with a growing gap. [say]: a toast of how it went. */
    fun soon(context: Context, say: Boolean = false) {
        val app = context.applicationContext
        running.launch {
            if (waiting(app).isEmpty()) return@launch
            val store = SyncStore(app)
            runCatching { SyncClient(app, store).sendOutbox() }
                .onSuccess { n -> if (say && n > 0) toast(app, store.pairing()?.name?.let { app.resources.getQuantityString(R.plurals.sync_sent_to, n, n, it) } ?: app.resources.getQuantityString(R.plurals.sync_sent, n, n)) }
                .onFailure {
                    WriterLog.w("Send to computer: waiting (${it.message})")
                    val old = ScopedSync.tooOld(it)
                    if (say) toast(app, if (old) app.getString(R.string.sync_send_too_old) else waiting(app).size.let { w -> store.pairing()?.name?.let { app.resources.getQuantityString(R.plurals.sync_send_waiting_for, w, w, it) } ?: app.resources.getQuantityString(R.plurals.sync_send_waiting, w, w) })
                    if (!old) wait(app)
                }
        }
    }

    /** Run by Android when there is a network, and again with a growing gap until the computer has them (SyncJob). */
    private fun wait(context: Context) {
        val jobs = context.getSystemService(JobScheduler::class.java) ?: return
        jobs.schedule(JobInfo.Builder(WAITING, ComponentName(context, SyncJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setExtras(android.os.PersistableBundle().apply { putBoolean(EXTRA, true) })
            .setPersisted(true)
            .build())
    }

    private fun toast(context: Context, text: String) = Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
}

/**
 * Send to phone, this side (the user, 23 Oct): a file the computer sends (SyncServer PUT /send) lands in Tetra's
 * Files › From computer, hashed while written and kept only if it matches; a name already there with other bytes
 * keeps both, "name (2)". One notification for a burst: "3 files from <computer>".
 */
internal object FromComputer {
    const val FOLDER = "From computer"
    const val SCREEN = "from-computer" // the notification's tap: Files at FOLDER (MainActivity)
    private const val CHANNEL = "files_from_computer_nobadge" // high: on HyperOS a default one went unseen, folded under "Listening" (23 Oct)
    private const val NOTE = 4144
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val names = mutableListOf<String>()
    private var shown: Runnable? = null

    /** The file kept, in [dir]. Throws IllegalArgumentException for a bad name or bytes that are not [sha]. */
    fun receive(input: InputStream, length: Long, dir: File, sha: String, asked: String, modified: Long): File {
        require(asked.isNotEmpty() && asked.length < 256 && '/' !in asked && '\\' !in asked && '\u0000' !in asked && asked != "." && asked != "..") { "Invalid name." }
        val name = Outbox.fitName(asked)
        dir.mkdirs()
        val part = File(dir, ".${UUID.randomUUID()}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            DigestOutputStream(part.outputStream(), digest).use { out ->
                val buffer = ByteArray(256 * 1024)
                var left = length
                while (left > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (n < 0) throw java.io.EOFException("The computer stopped sending.")
                    out.write(buffer, 0, n)
                    left -= n
                }
            }
            require(SyncRules.hex(digest.digest()) == sha) { "The file changed in transit; nothing was kept." }
            val same = File(dir, name)
            if (same.isFile && same.length() == length && sha256(same) == sha) {
                part.delete(); return same // sent again after its answer was lost
            }
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            val kept = generateSequence(1) { it + 1 }.map { n -> File(dir, if (n == 1) name else "${name.substring(0, dot)} ($n)${name.substring(dot)}") }.first { !it.exists() }
            check(part.renameTo(kept)) { "could not keep it" }
            if (modified > 0) kept.setLastModified(modified)
            return kept
        } finally { part.delete() }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(256 * 1024)
        file.inputStream().use { while (true) { val n = it.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return SyncRules.hex(digest.digest())
    }

    fun arrived(context: Context, name: String, from: String) {
        val app = context.applicationContext
        handler.post {
            names += name
            shown?.let(handler::removeCallbacks)
            shown = Runnable { notify(app, names.toList(), from); names.clear() }.also { handler.postDelayed(it, 1_500) }
        }
    }

    private fun notify(context: Context, files: List<String>, from: String) {
        val nm = context.getSystemService(NotificationManager::class.java)?.takeIf { it.areNotificationsEnabled() } ?: return
        nm.deleteNotificationChannel("from_computer") // the first build's, default importance
        // made again when its name is not the one in the language now shown (an existing channel keeps its first name otherwise)
        if (nm.getNotificationChannel(CHANNEL)?.name != context.getString(R.string.sync_from_channel)) nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.sync_from_channel), NotificationManager.IMPORTANCE_HIGH).apply { setShowBadge(false); description = context.getString(R.string.sync_from_channel_about) })
        val open = PendingIntent.getActivity(context, NOTE, Intent(context, MainActivity::class.java).putExtra(Applet.EXTRA, SCREEN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(NOTE, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_computer)
            .setContentTitle(context.resources.getQuantityString(R.plurals.sync_files_from, files.size, files.size, from))
            .setContentText(context.getString(R.string.sync_files_in, FOLDER, files.distinct().joinToString(", ")))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setGroup(CHANNEL)
            .setContentIntent(open).setAutoCancel(true).build())
    }
}
