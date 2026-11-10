package com.kalotrapezis.drive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
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

/** A name cut to fit a file system (200 bytes), its extension kept. */
internal fun fitName(name: String, max: Int = 200): String {
    if (name.toByteArray().size <= max) return name
    val dot = name.lastIndexOf('.').takeIf { it > 0 && name.length - it <= 20 } ?: name.length
    val ext = name.substring(dot)
    var base = name.substring(0, dot)
    while (base.isNotEmpty() && (base + ext).toByteArray().size > max) base = base.substring(0, base.offsetByCodePoints(base.length, -1))
    return base.trimEnd() + ext
}

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
        val name = fitName(asked)
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
        val open = PendingIntent.getActivity(context, NOTE, Intent(context, CarActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(NOTE, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_computer)
            .setContentTitle(context.resources.getQuantityString(R.plurals.sync_files_from, files.size, files.size, from))
            .setContentText(context.getString(R.string.sync_files_in, FOLDER, files.distinct().joinToString(", ")))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setGroup(CHANNEL)
            .setContentIntent(open).setAutoCancel(true).build())
    }
}
