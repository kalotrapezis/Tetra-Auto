package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Writer's log (asked 2026-10-01: "the word app is huge, we are going to need them"): what was opened, what was tapped,
 * what was saved, how long the layouts took, and every error — to logcat (tag Tetra.Writer, for adb) and to
 * files/logs/writer.log on the device, kept to about 1 MB (the older half in writer.log.1), so a problem seen on the
 * phone away from the computer can be sent: File › Send log. Words of documents are never written to it, only their
 * shape (paragraph counts, sizes, what Writer could not keep) — the user's documents are private.
 */
internal object WriterLog {
    private const val TAG = "Tetra.Writer"
    private const val MAX_BYTES = 512 * 1024L
    private var file: File? = null
    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    /**
     * Tetra's logs (this one, Maps' drives): in its own folder on the shared storage, /sdcard/Android/data/<package>/files/logs,
     * where adb reads them without run-as — the build installed on the phone and the tablet is not debuggable (25 Oct: it ran
     * several times faster). The app's private folder only if there is no shared storage.
     */
    fun dir(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")

    /** Once per process: where the log goes, and a line saying which app and device wrote what follows. */
    fun init(context: Context) {
        if (file != null) return
        val logs = dir(context).apply { mkdirs() }
        // the logs written before into the app's private folder, moved there once
        File(context.filesDir, "logs").takeIf { it.isDirectory && it.canonicalPath != logs.canonicalPath }?.let { old ->
            runCatching { if (old.copyRecursively(logs, overwrite = false) { _, _ -> OnErrorAction.SKIP }) old.deleteRecursively() }
        }
        file = File(logs, "writer.log")
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        i("— Tetra $version on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        // A crash anywhere in the app: written down before the app goes (the system's own handler still runs after).
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> e("crash on ${t.name}", e); before?.uncaughtException(t, e) }
    }

    fun i(message: String) = write('I', message, null)
    fun w(message: String, error: Throwable? = null) = write('W', message, error)
    fun e(message: String, error: Throwable? = null) = write('E', message, error)

    /** [block] done, and how long it took. */
    inline fun <T> timed(what: String, block: () -> T): T {
        val start = System.nanoTime()
        return block().also { i("$what: ${(System.nanoTime() - start) / 1_000_000} ms") }
    }

    /** The log as one file to send: the older part first. */
    fun export(context: Context): File? {
        val f = file ?: return null
        val out = File(dir(context), "Tetra Writer log.txt")
        synchronized(this) { out.writeText(File(f.path + ".1").takeIf { it.isFile }?.readText().orEmpty() + (f.takeIf { it.isFile }?.readText().orEmpty())) }
        return out
    }

    private fun write(level: Char, message: String, error: Throwable?) {
        // runCatching: in the JVM unit tests android.util.Log is a stub that throws.
        runCatching { when (level) { 'E' -> Log.e(TAG, message, error); 'W' -> Log.w(TAG, message, error); else -> Log.i(TAG, message) } }
        val f = file ?: return
        val line = "${LocalDateTime.now().format(time)} $level $message\n" + (error?.let { Log.getStackTraceString(it) + "\n" } ?: "")
        synchronized(this) {
            runCatching {
                if (f.length() > MAX_BYTES) f.renameTo(File(f.path + ".1"))
                f.appendText(line)
            }
        }
    }
}

/**
 * How long an applet takes to open (the user, 25 Oct: "slow, taps don't answer until it's all ready"), one line in
 * writer.log each time: the screen opened → its first content drawn → the main thread free (the last frame that took
 * over 100 ms, i.e. a tap waited) within five seconds, and the longest frame.
 */
internal object ScreenTiming {
    fun watch(opened: Long, what: String, shown: () -> Long?) {
        var last = opened; var stuckUntil = 0L; var longest = 0L
        val choreographer = android.view.Choreographer.getInstance()
        choreographer.postFrameCallback(object : android.view.Choreographer.FrameCallback {
            override fun doFrame(nanos: Long) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - last > 100) stuckUntil = now
                longest = maxOf(longest, now - last)
                last = now
                if (now - opened < 5000) choreographer.postFrameCallback(this)
                else WriterLog.i("$what: opened → first ${what.lowercase()} ${shown()?.let { "${it - opened} ms" } ?: "not yet"}, taps answered from ${if (stuckUntil == 0L) 0 else stuckUntil - opened} ms, longest frame $longest ms")
            }
        })
    }
}

/** At a screen's first composition: [ScreenTiming] from now; "first … drawn" is the frame after [ready] turns true. */
@Composable
internal fun OpenTiming(what: String, ready: Boolean) {
    var shownAt by remember { mutableStateOf<Long?>(null) }
    remember { ScreenTiming.watch(android.os.SystemClock.uptimeMillis(), what) { shownAt } }
    LaunchedEffect(ready) { if (ready && shownAt == null) { withFrameMillis {}; shownAt = android.os.SystemClock.uptimeMillis() } }
}
