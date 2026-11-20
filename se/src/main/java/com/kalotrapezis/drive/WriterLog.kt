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
        // the car's 2 GB (the user, 19 Nov: YouTube stuck, Tetra started again 6 times, no crash written): Android's own warnings
        // that memory runs short, the last thing it says before it kills the app; not UI_HIDDEN, said on every switch away
        ram(context, "start")
        context.applicationContext.registerComponentCallbacks(object : android.content.ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) { if (level != android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) ram(context, "Android asks for memory back (trim level $level)") }
            override fun onLowMemory() = ram(context, "Android is out of memory")
            override fun onConfigurationChanged(c: android.content.res.Configuration) = Unit
        })
        // the car restarting with nothing written and memory to spare (the user, 20 Nov): a frozen main thread, a native crash, or
        // Android killing the app — the first found by a watchdog, the others in logcat, which outlives the process
        watchMain()
        Thread { lastRun(context) }.start()
    }

    /** The main thread stuck over 5 s (Android kills an app frozen too long, writing nothing of it here): where it was, and for how long. */
    private fun watchMain() = Thread({
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        while (true) {
            val answered = java.util.concurrent.atomic.AtomicBoolean(false)
            val sent = android.os.SystemClock.uptimeMillis()
            main.post { answered.set(true) }
            Thread.sleep(5000)
            if (answered.get()) continue
            w("Main thread stuck over 5 s, at:\n" + android.os.Looper.getMainLooper().thread.stackTrace.take(40).joinToString("\n") { "\tat $it" })
            while (!answered.get()) Thread.sleep(250)
            i("Main thread free again after ${android.os.SystemClock.uptimeMillis() - sent} ms")
        }
    }, "Tetra main watch").apply { isDaemon = true }.start()

    /**
     * What Android wrote of the run before this one (warnings, native crashes, a freeze's SIGQUIT): an app reads its own lines of
     * logcat without permission, and logcat outlives the process. Only the lines since that run started, and not this log's own.
     */
    private fun lastRun(context: Context) = runCatching {
        val prefs = context.getSharedPreferences("writer_log", Context.MODE_PRIVATE)
        val since = prefs.getString("logcat_since", null)
        prefs.edit().putString("logcat_since", java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.ROOT).format(java.util.Date())).apply()
        if (since == null) return@runCatching
        val me = android.os.Process.myPid().toString()
        val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-b", "main", "-b", "system", "-b", "crash", "-t", since, "*:W", "zygote64:I", "zygote:I")
            .redirectErrorStream(true).start()
        val lines = p.inputStream.bufferedReader().readLines()
            .filter { l -> !l.startsWith("-----") && !l.contains(" $TAG") && l.trim().split(Regex("\\s+")).getOrNull(2) != me }
        i(if (lines.isEmpty()) "Last run: Android wrote no warning or crash of Tetra since $since"
          else "Last run: Android wrote, since $since:\n" + lines.takeLast(150).joinToString("\n") { "\t" + it.take(300) })
    }.onFailure { w("Last run: logcat not read (${it.message})") }

    /**
     * A line of memory, off the main thread: the device's free RAM and whether Android counts it low, Tetra's Java heap, and
     * every Tetra process Android lists (the Browser's Gecko pages among them unless they run isolated) with its PSS.
     */
    fun ram(context: Context, why: String) = Thread {
        runCatching {
            val am = context.getSystemService(android.app.ActivityManager::class.java)
            val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val rt = Runtime.getRuntime(); val mb = 1024 * 1024
            val procs = am.runningAppProcesses.orEmpty()
            val pss = am.getProcessMemoryInfo(procs.map { it.pid }.toIntArray())
            val each = procs.indices.joinToString { "${procs[it].processName.substringAfterLast(':').substringAfterLast('$')} ${pss[it].totalPss / 1024} MB" }
            i("RAM ($why): ${mi.availMem / mb} MB free of ${mi.totalMem / mb}${if (mi.lowMemory) ", LOW" else ""} (low under ${mi.threshold / mb}); " +
                "heap ${(rt.totalMemory() - rt.freeMemory()) / mb} of ${rt.maxMemory() / mb} MB; Tetra ${pss.sumOf { it.totalPss } / 1024} MB: $each")
        }.onFailure { w("RAM ($why): not read (${it.message})") }
    }.start()

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
