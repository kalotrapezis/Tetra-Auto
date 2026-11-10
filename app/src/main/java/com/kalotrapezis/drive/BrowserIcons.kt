package com.kalotrapezis.drive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sites' icons in the favourites' and tabs' circles (the user, 4 Nov: "why it does not take the page icons in the
 * circle?"). Learnt as a page loads (the page names its own, tetra-ext/content.js), else guessed at the site's usual
 * places; one small PNG per site in Tetra's own storage, so the circles show them offline too. Nothing in Private.
 */
internal object BrowserIcons {
    private const val SIZE = 96
    private const val MAX_AGE = 30L * 24 * 3600 * 1000 // a site's new logo shows within a month
    private const val MAX_BYTES = 512 * 1024
    private val memory = ConcurrentHashMap<String, ImageBitmap>()
    private val busy = ConcurrentHashMap.newKeySet<String>()
    private val guessed = ConcurrentHashMap.newKeySet<String>() // tried this run and found nothing: not again until Tetra restarts

    /** Read by the circles: a new icon saved redraws them. */
    var version by mutableIntStateOf(0); private set

    /** One icon per site (youtube.com for m.youtube.com and www.youtube.com alike). */
    fun key(url: String) = BrowserRules.site(BrowserRules.host(url)).lowercase()

    /** Its file: the site's name, only the characters a file name takes everywhere. */
    fun fileName(key: String) = key.replace(Regex("[^a-z0-9.-]"), "_").take(120) + ".png"

    private fun file(c: Context, key: String) = File(c.filesDir, "browser-icons/${fileName(key)}")

    /** The site's icon if it is here; reads the disk the first time (call off the main thread). */
    fun cached(c: Context, url: String): ImageBitmap? {
        val key = key(url).ifEmpty { return null }
        memory[key]?.let { return it }
        val f = file(c, key).takeIf { it.isFile } ?: return null
        return BitmapFactory.decodeFile(f.path)?.asImageBitmap()?.also { memory[key] = it }
    }

    /** A page said where its icon is: fetched unless this site's is fresh. */
    fun learn(c: Context, pageUrl: String, iconUrl: String) {
        if (!iconUrl.startsWith("https://") && !iconUrl.startsWith("http://")) return
        val key = key(pageUrl).ifEmpty { return }
        val f = file(c, key)
        if (f.isFile && System.currentTimeMillis() - f.lastModified() < MAX_AGE) return
        fetchInto(c.applicationContext, key, listOf(iconUrl))
    }

    /** A favourite never opened on this device: the site's usual places, once a run. */
    fun guess(c: Context, url: String) {
        val key = key(url).ifEmpty { return }
        if (file(c, key).isFile || !guessed.add(key)) return
        val origin = "https://" + BrowserRules.host(url)
        fetchInto(c.applicationContext, key, listOf("$origin/apple-touch-icon.png", "$origin/favicon.ico"))
    }

    private fun fetchInto(app: Context, key: String, urls: List<String>) {
        if (!busy.add(key)) return
        Thread {
            try {
                val icon = urls.firstNotNullOfOrNull { u -> runCatching { download(u) }.getOrNull() } ?: return@Thread
                val small = Bitmap.createScaledBitmap(icon, SIZE, SIZE, true)
                val f = file(app, key).apply { parentFile?.mkdirs() }
                val part = File(f.parentFile, ".${f.name}.part")
                part.outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
                part.renameTo(f)
                memory[key] = small.asImageBitmap()
                android.os.Handler(android.os.Looper.getMainLooper()).post { version++ }
            } catch (e: Exception) { WriterLog.w("Browser: an icon for $key not saved (${e.message})")
            } finally { busy.remove(key) }
        }.start()
    }

    /** A picture Android can draw (PNG, JPEG, WebP, ICO), at least 16 px; an SVG or a page instead of one: null. */
    private fun download(url: String): Bitmap? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000; instanceFollowRedirects = true }
        try {
            if (c.responseCode !in 200..299) return null
            val bytes = c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192); var n: Int
                while (input.read(buf).also { n = it } >= 0) { out.write(buf, 0, n); if (out.size() > MAX_BYTES) return null }
                out.toByteArray()
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.takeIf { it.width >= 16 && it.height >= 16 }
        } finally { c.disconnect() }
    }
}

/** A site's icon for a circle: from the device, else fetched once (not in Private: no address leaves the phone for it). */
@Composable internal fun siteIcon(url: String, fetch: Boolean = true): ImageBitmap? {
    val c = LocalContext.current
    val v = BrowserIcons.version
    val icon by produceState<ImageBitmap?>(null, url, v) {
        value = withContext(Dispatchers.IO) { BrowserIcons.cached(c, url) }
        if (value == null && fetch && url.startsWith("http")) BrowserIcons.guess(c, url)
    }
    return icon
}
