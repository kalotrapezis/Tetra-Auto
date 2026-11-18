package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Files' thumbnails (the user, 23 Oct): a picture's and a video's, with no icon over them (24 Oct: "when we have a
 * preview of the file have no icon"); documents show their tile instead ("PDFs as well. lets keep documents icon only").
 * Made once and kept in the app's cache (never in Tetra's synced folder), named by the file's path, size
 * and time, so a changed file gets a new one.
 */
internal object FileThumbs {
    enum class Kind { Picture, Video }

    private const val SIZE = 384 // px, the longer side: a tablet's grid tile
    private val pictures = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "avif")
    private val videos = setOf("mp4", "mov", "mkv", "avi", "webm", "3gp", "m4v")

    fun kind(name: String): Kind? = when (name.substringAfterLast('.', "").lowercase()) {
        in pictures -> Kind.Picture
        in videos -> Kind.Video
        else -> null // documents: their tile, never a preview (the user, 24 Oct)
    }


    /** The cache's name for a file as it is now: another path, size or time is another thumbnail. */
    fun key(path: String, length: Long, modified: Long): String =
        MessageDigest.getInstance("SHA-1").digest("$path|$length|$modified".toByteArray()).joinToString("") { "%02x".format(it) }

    /** The oldest thumbnails beyond [keep] go (those of files changed or gone are never asked for again). */
    fun trim(dir: File, keep: Int) {
        val all = dir.listFiles()?.filter { it.isFile } ?: return
        if (all.size > keep) all.sortedByDescending { it.lastModified() }.drop(keep).forEach { it.delete() }
    }

    private val memory by lazy { // lazy: the rules above are tested on a computer, where Android's classes are only stubs
        object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt()) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }
    }
    // a few at a time: a big folder of videos must not decode them all at once
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) private val quick = Dispatchers.IO.limitedParallelism(3)
    @Volatile private var trimmed = false

    fun remembered(file: File, length: Long, modified: Long): Bitmap? = memory.get(key(file.path, length, modified))

    /** The file's thumbnail, made now if it is not in the cache; null when it cannot be drawn (then it is not tried again until it changes). */
    suspend fun load(context: Context, file: File, kind: Kind): Bitmap? {
        val key = key(file.path, file.length(), file.lastModified())
        memory.get(key)?.let { return it }
        return withContext(quick) {
            val dir = File(context.cacheDir, "file-thumbs").apply { mkdirs() }
            if (!trimmed) { trimmed = true; trim(dir, 4000) }
            val cached = File(dir, "$key.webp"); val none = File(dir, "$key.none")
            if (none.exists()) return@withContext null
            val bmp = (if (cached.exists()) BitmapFactory.decodeFile(cached.path) else null) ?: runCatching { make(file, kind) }
                .onFailure { WriterLog.e("Files: thumbnail of ${file.name}", it) }.getOrNull()?.also { b ->
                    val part = File(dir, "$key.part") // written aside and renamed: a half-written one never passes for done
                    runCatching { part.outputStream().use { b.compress(if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP, 80, it) }; part.renameTo(cached) }
                }
            if (bmp == null) none.createNewFile() else memory.put(key, bmp)
            bmp
        }
    }

    private fun make(file: File, kind: Kind): Bitmap? = when (kind) {
        Kind.Picture -> ThumbnailUtils.createImageThumbnail(file, Size(SIZE, SIZE), null)
        Kind.Video -> ThumbnailUtils.createVideoThumbnail(file, Size(SIZE, SIZE), null)
    }
}

/** A file's thumbnail in Files' list and grid; [fallback] (its icon) for a file with none, and while one is being made. */
@Composable
internal fun FileThumb(file: File, modifier: Modifier, modified: Long = file.lastModified(), size: Long = file.length(), fallback: @Composable () -> Unit) {
    val kind = FileThumbs.kind(file.name) ?: return fallback()
    val context = LocalContext.current
    val bitmap = produceState(FileThumbs.remembered(file, size, modified), file, modified) { value = FileThumbs.load(context, file, kind) }.value ?: return fallback()
    Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
}
