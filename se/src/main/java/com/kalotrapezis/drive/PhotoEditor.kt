package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import android.Manifest
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Color
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import java.io.File
import android.os.Environment
import android.media.MediaScannerConnection
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Photo edits reuse the scanner's tools: crop & straighten corners, 90° rotation and brush markup; and hidden parts. */
internal data class PhotoEdit(val quad: DocumentQuad? = null, val rotation: Int = 0, val strokes: List<ScanStroke> = emptyList(), val covers: List<PhotoCover> = emptyList()) {
    fun rotatedBy(degrees: Int) = copy(rotation = (rotation + degrees + 360) % 360, strokes = strokes.map { it.rotated(degrees) })
}

/**
 * A hidden part of the photo — a black bar, or stars (the user, 22 Oct: "For the eyes stars or a black bar") — in the
 * uncropped photo's 0..1 space, burned in before crop and rotation so it cannot be cropped back out of place.
 */
internal data class PhotoCover(val left: Float, val top: Float, val right: Float, val bottom: Float, val stars: Boolean, val eye: Boolean = false) {
    fun has(x: Float, y: Float) = x in left..right && y in top..bottom
}

/** What the viewer's Hide eyes / Hide text opens the editor to find. */
internal enum class PhotoHide { Eyes, Text }

private const val STAR_COLOR = 0xFFFDD835.toInt()

/** Draws one cover at [r] (pixels of [canvas]): a black bar, or stars filling it along its longer side. */
internal fun drawCover(canvas: android.graphics.Canvas, r: android.graphics.RectF, stars: Boolean) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    if (!stars) { paint.color = android.graphics.Color.BLACK; canvas.drawRect(r, paint); return }
    paint.color = STAR_COLOR
    val side = minOf(r.width(), r.height())
    val n = maxOf(1, Math.round(maxOf(r.width(), r.height()) / side))
    val step = maxOf(r.width(), r.height()) / n
    repeat(n) { i ->
        val cx = if (r.width() >= r.height()) r.left + step * (i + 0.5f) else r.centerX()
        val cy = if (r.width() >= r.height()) r.centerY() else r.top + step * (i + 0.5f)
        val path = android.graphics.Path()
        for (k in 0 until 10) { // a fat five-point star (inner radius 0.5) so little shows between its arms
            val rad = side / 2f * (if (k % 2 == 0) 1f else 0.5f)
            val a = Math.toRadians(-90.0 + k * 36.0)
            val x = cx + rad * Math.cos(a).toFloat(); val y = cy + rad * Math.sin(a).toFloat()
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close(); canvas.drawPath(path, paint)
    }
}

private fun drawCovers(target: Bitmap, covers: List<PhotoCover>) {
    val canvas = android.graphics.Canvas(target)
    covers.forEach { drawCover(canvas, android.graphics.RectF(it.left * target.width, it.top * target.height, it.right * target.width, it.bottom * target.height), it.stars) }
}

/** Each face's two eyes in [bitmap]'s pixels (ML Kit, as People uses); a face without eye points gets them from its box. */
private fun findEyes(bitmap: Bitmap): List<Pair<android.graphics.PointF, android.graphics.PointF>> {
    val detector = com.google.mlkit.vision.face.FaceDetection.getClient(com.google.mlkit.vision.face.FaceDetectorOptions.Builder()
        .setPerformanceMode(com.google.mlkit.vision.face.FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setLandmarkMode(com.google.mlkit.vision.face.FaceDetectorOptions.LANDMARK_MODE_ALL).setMinFaceSize(0.05f).build())
    try {
        return com.google.android.gms.tasks.Tasks.await(detector.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0))).map { face ->
            val l = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE)?.position
            val r = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE)?.position
            val b = face.boundingBox
            if (l != null && r != null) l to r
            else android.graphics.PointF(b.left + 0.3f * b.width(), b.top + 0.38f * b.height()) to android.graphics.PointF(b.left + 0.7f * b.width(), b.top + 0.38f * b.height())
        }
    } finally { detector.close() }
}

/** A bar across both eyes, or a star on each; [w]×[h] is the picture the eyes were found in. */
internal fun eyeCovers(eyes: List<Pair<android.graphics.PointF, android.graphics.PointF>>, w: Int, h: Int, stars: Boolean): List<PhotoCover> = eyes.flatMap { (a, b) ->
    val d = Math.hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()
    fun box(cx: Float, cy: Float, rx: Float, ry: Float) = PhotoCover((cx - rx) / w, (cy - ry) / h, (cx + rx) / w, (cy + ry) / h, stars, eye = true)
    // shortcut: the bar stays level (a tilted head only makes it taller); turn it with the head if tilted eyes show past its ends
    if (stars) listOf(box(a.x, a.y, 0.6f * d, 0.6f * d), box(b.x, b.y, 0.6f * d, 0.6f * d))
    else listOf(box((a.x + b.x) / 2, (a.y + b.y) / 2, d, 0.4f * d + kotlin.math.abs(a.y - b.y) / 2))
}

private const val PREVIEW_EDGE = 2048
// Full resolution for the 50 MP sensor (8160 px); needs android:largeHeap. Out of memory fails before anything is written.
private const val SAVE_EDGE = 8192

private val MARKUP_COLORS = listOf(
    android.graphics.Color.WHITE, android.graphics.Color.BLACK, 0xFFE53935.toInt(), 0xFFFDD835.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt(),
)

private fun decodePhoto(context: Context, uri: Uri, maxEdge: Int): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        val scale = minOf(1f, maxEdge.toFloat() / maxOf(info.size.width, info.size.height))
        decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.isMutableRequired = true
    }

private fun renderPhotoEdit(original: Bitmap, edit: PhotoEdit, maxEdge: Float, withStrokes: Boolean, recycleSource: Boolean = false): Bitmap {
    // The save's decode is its own mutable copy, drawn on in place; the preview's source stays clean.
    val source = if (edit.covers.isEmpty()) original else (if (recycleSource) original else original.copy(Bitmap.Config.ARGB_8888, true)).also { drawCovers(it, edit.covers) }
    // Recycling each intermediate keeps a full-size save to two bitmaps at a time.
    val cropped = edit.quad?.let { quad -> cropScan(source, quad, maxEdge).also { if (recycleSource) source.recycle() } } ?: source
    val rotated = rotateScan(cropped, edit.rotation).also { if (recycleSource && it !== cropped) cropped.recycle() }
    val result = if (withStrokes && !rotated.isMutable) rotated.copy(Bitmap.Config.ARGB_8888, true).also { if (recycleSource) rotated.recycle() } else rotated
    if (withStrokes) drawStrokes(result, edit.strokes)
    return result
}

private fun renderForSave(context: Context, original: Uri, edit: PhotoEdit): Bitmap = try {
    renderPhotoEdit(decodePhoto(context, original, SAVE_EDGE), edit, SAVE_EDGE.toFloat(), withStrokes = true, recycleSource = true)
} catch (_: OutOfMemoryError) {
    throw DriveProblem("Not enough memory to save this photo at full size. Nothing was changed.", R.string.photos_error_memory)
}

/**
 * Saves the edit as a new photo beside the original (same folder and date, location copied), never touching the
 * original. A failed write removes only the new, still-pending entry.
 */
private fun savePhotoCopy(context: Context, entry: Entry, edit: PhotoEdit): Uri {
    val original = entry.contentUri ?: throw DriveProblem("This photo is no longer available.", R.string.photos_unavailable)
    val image = renderForSave(context, original, edit)
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "${entry.name.substringBeforeLast('.')}_edited.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, entry.relativePath ?: "DCIM/Camera/")
        if (entry.takenMillis > 0) put(MediaStore.Images.Media.DATE_TAKEN, entry.takenMillis)
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val target = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: throw DriveProblem("Could not create the edited photo.", R.string.photos_error_create)
    try {
        checkNotNull(resolver.openOutputStream(target)).use { need(image.compress(Bitmap.CompressFormat.JPEG, 95, it), R.string.photos_error_write) { "Could not write the edited photo." } }
        writeExif(context, target, readExif(context, original))
        resolver.update(target, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    } catch (failure: Throwable) {
        resolver.delete(target, null, null)
        throw failure
    }
    return target
}

/**
 * The key the gallery will list for [uri] once MediaStore has caught up with the new size (it includes the size).
 * Waits briefly for MediaStore; null if it never settles, which leaves the metadata where it was.
 */
private fun settledPhotoKey(context: Context, uri: Uri): String? {
    val bytes = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: return null
    repeat(20) {
        context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && c.getLong(2) == bytes) return PhotoMetadataRules.stableKey(uri.toString(), c.getString(1) ?: "Unnamed", c.getString(0) ?: "Unnamed", bytes)
        }
        Thread.sleep(100)
    }
    return null
}

/**
 * Replaces the original photo's pixels (after Android's own modify consent). Everything is rendered and encoded
 * first; the original is only opened for writing once the complete new file exists.
 */
private fun replacePhoto(context: Context, entry: Entry, edit: PhotoEdit): Uri {
    val original = entry.contentUri ?: throw DriveProblem("This photo is no longer available.", R.string.photos_unavailable)
    val exif = readExif(context, original)
    val encoded = File.createTempFile("edit", ".jpg", context.cacheDir)
    try {
        val image = renderForSave(context, original, edit)
        encoded.outputStream().use { need(image.compress(Bitmap.CompressFormat.JPEG, 95, it), R.string.photos_error_encode) { "Could not encode the edited photo." } }
        image.recycle()
        (context.contentResolver.openOutputStream(original, "wt") ?: throw DriveProblem("Could not open the photo for writing.", R.string.photos_error_open)).use { output ->
            encoded.inputStream().use { it.copyTo(output) }
        }
        writeExif(context, original, exif)
    } finally {
        encoded.delete()
    }
    // Refresh MediaStore's size, dimensions and thumbnail for the rewritten file.
    entry.relativePath?.let { folder ->
        MediaScannerConnection.scanFile(context, arrayOf(File(Environment.getExternalStorageDirectory(), folder + entry.name).path), null, null)
    }
    return original
}

private val EXIF_TAGS = listOf(
    ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
    ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
    ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
)

/** The original's date, camera and (when the app may read it) location. */
private fun readExif(context: Context, original: Uri): Map<String, String> = runCatching {
    val canReadLocation = context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
    val source = if (canReadLocation) MediaStore.setRequireOriginal(original) else original
    context.contentResolver.openInputStream(source)?.use { stream -> ExifInterface(stream).let { exif -> EXIF_TAGS.mapNotNull { tag -> exif.getAttribute(tag)?.let { tag to it } }.toMap() } }
}.getOrNull().orEmpty()

/** The pixels are already upright, so orientation is reset. */
private fun writeExif(context: Context, target: Uri, tags: Map<String, String>) = runCatching {
    context.contentResolver.openFileDescriptor(target, "rw")?.use { descriptor ->
        val exif = ExifInterface(descriptor.fileDescriptor)
        tags.forEach { (tag, value) -> exif.setAttribute(tag, value) }
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        exif.saveAttributes()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoEditorScreen(entry: Entry, close: () -> Unit, saved: (uri: Uri, newPhotoKey: String?) -> Unit, hide: PhotoHide? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val source by produceState<Bitmap?>(null, entry.contentUri) {
        value = entry.contentUri?.let { uri -> withContext(Dispatchers.IO) { runCatching { decodePhoto(context, uri, PREVIEW_EDGE) }.getOrNull() } }
    }
    var edit by remember(entry.contentUri) { mutableStateOf(PhotoEdit()) }
    var cropping by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(if (hide != null) EditTab.Hide else EditTab.Adjust) }
    val marking = tab == EditTab.Markup
    val hiding = tab == EditTab.Hide
    var expanded by remember { mutableStateOf(true) } // the island's handle folds the tab's tools away
    var brushColor by remember { mutableStateOf(0xFFE53935.toInt()) }
    var brushWidth by remember { mutableStateOf(0.012f) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var stars by remember { mutableStateOf(false) }
    var finding by remember { mutableStateOf(false) }
    var eyes by remember { mutableStateOf(emptyList<Pair<android.graphics.PointF, android.graphics.PointF>>()) }
    var words by remember { mutableStateOf(emptyList<PhotoCover>()) } // found words, hidden when tapped
    var discardOpen by remember { mutableStateOf(false) }
    var saveSheetOpen by remember { mutableStateOf(false) }
    // Geometry is re-rendered only when it changes; strokes are drawn live on top.
    val preview by produceState<Bitmap?>(null, source, edit.quad, edit.rotation, edit.covers) {
        value = source?.let { withContext(Dispatchers.Default) { renderPhotoEdit(it, edit, PREVIEW_EDGE.toFloat(), withStrokes = false) } }
    }
    val changed = edit != PhotoEdit()
    BackHandler {
        when {
            cropping -> cropping = false
            changed -> discardOpen = true
            else -> close()
        }
    }
    if (cropping) {
        CornerEditor(source, edit.quad ?: DocumentQuad.fullFrame(), DocumentQuad.fullFrame(), stringResource(R.string.photos_reset), done = { quad ->
            edit = edit.copy(quad = quad.takeUnless { it == DocumentQuad.fullFrame() })
            cropping = false
        }, cancel = { cropping = false })
        return
    }
    fun find(what: PhotoHide) {
        val image = source ?: return
        finding = true; error = null
        scope.launch {
            runCatching {
                if (what == PhotoHide.Eyes) withContext(Dispatchers.Default) { findEyes(image) }.let { found ->
                    eyes = found
                    edit = edit.copy(covers = edit.covers.filterNot { it.eye } + eyeCovers(found, image.width, image.height, stars))
                    if (found.isEmpty()) error = context.getString(R.string.photos_no_faces)
                } else withContext(Dispatchers.Default) {
                    // small pictures enlarged, as Photos' Text in the photo does: Tesseract misses letters under ~20 px
                    val k = minOf(3f, 2400f / maxOf(image.width, image.height))
                    val read = if (k > 1f) Bitmap.createScaledBitmap(image, (image.width * k).toInt(), (image.height * k).toInt(), true) else image
                    Ocr.words(context, read).map { w -> PhotoCover(w.box.left / read.width, w.box.top / read.height, w.box.right / read.width, w.box.bottom / read.height, stars = false) }
                }.let { found ->
                    words = found
                    if (found.isEmpty()) error = context.getString(R.string.photos_no_words)
                }
            }.onFailure { WriterLog.e("Photos: hide", it); error = context.getString(R.string.photos_find_failed) }
            finding = false
        }
    }
    LaunchedEffect(source) { if (source != null && hide != null) find(hide) }
    fun save(replace: Boolean) {
        saving = true
        error = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                if (replace) replacePhoto(context, entry, edit).let { it to settledPhotoKey(context, it) } else savePhotoCopy(context, entry, edit) to null
            } }
                .onSuccess { (uri, newKey) -> saved(uri, newKey) }
                .onFailure { error = it.shownMessage(context) ?: context.getString(R.string.photos_error_save) }
            saving = false
        }
    }
    // Replacing another app's photo needs Android's own "allow modify" consent first.
    val modifyConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) save(replace = true)
    }
    fun replace() {
        val uri = entry.contentUri ?: return
        val request = MediaStore.createWriteRequest(context.contentResolver, listOf(uri))
        modifyConsent.launch(IntentSenderRequest.Builder(request.intentSender).build())
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            // Hiding works on the whole, upright photo the covers are measured on; the preview shows them burned in.
            val image = if (hiding) source else preview
            if (image == null) CircularProgressIndicator()
            else {
                Image(image.asImageBitmap(), contentDescription = entry.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                if (hiding) CoverLayer(image.width, image.height, edit.covers, words, stars, tap = { x, y ->
                    val hit = edit.covers.lastOrNull { it.has(x, y) }
                    if (hit != null) edit = edit.copy(covers = edit.covers - hit)
                    else words.firstOrNull { it.has(x, y) }?.let { edit = edit.copy(covers = edit.covers + it) }
                }) { edit = edit.copy(covers = edit.covers + it) }
                else ScanStrokeLayer(image.width, image.height, edit.strokes, marking, brushColor, brushWidth) { stroke -> edit = edit.copy(strokes = edit.strokes + stroke) }
            }
            if (saving || finding) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        Surface(
            color = islandColor(), contentColor = islandContentColor(), shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        ) {
            Column(Modifier.padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth().height(22.dp).clickable { expanded = !expanded }, contentAlignment = Alignment.Center) {
                    Box(Modifier.width(34.dp).height(4.dp).clip(CircleShape).background(islandContentColor().copy(alpha = 0.72f)))
                }
                AnimatedVisibility(expanded && marking) {
                    PaintPanel(MARKUP_COLORS, stringResource(R.string.photos_draw_hint), brushColor, { brushColor = it }, brushWidth, { brushWidth = it }) {
                        edit = edit.copy(strokes = edit.strokes.dropLast(1))
                    }
                }
                // one row: back, the tabs and the open tab's tools, then undo all and save (the user, 22 Oct: "circular buttons keep it one row with tabs")
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    EditCircle(R.drawable.ic_chevron_left, stringResource(R.string.common_back)) { if (changed) discardOpen = true else close() }
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        EditTab.entries.forEach { t -> ScanPill(stringResource(t.label), t.icon, tab == t) { tab = t; expanded = true } }
                        if (expanded && tab != EditTab.Markup) Box(Modifier.width(1.dp).height(28.dp).background(islandContentColor().copy(alpha = 0.3f)))
                        if (expanded) when (tab) {
                            EditTab.Adjust -> {
                                EditCircle(R.drawable.ic_crop, stringResource(R.string.photos_crop), enabled = source != null && !saving) { cropping = true }
                                EditCircle(R.drawable.ic_rotate_left, stringResource(R.string.photos_rotate_left), enabled = !saving) { edit = edit.rotatedBy(270) }
                                EditCircle(R.drawable.ic_rotate_right, stringResource(R.string.photos_rotate_right), enabled = !saving) { edit = edit.rotatedBy(90) }
                            }
                            EditTab.Markup -> {}
                            EditTab.Hide -> {
                                EditCircle(R.drawable.ic_visibility_off, stringResource(R.string.photos_find_eyes), enabled = !finding) { find(PhotoHide.Eyes) }
                                EditCircle(R.drawable.ic_text_fields, stringResource(R.string.photos_find_text), enabled = !finding) { find(PhotoHide.Text) }
                                if (words.isNotEmpty()) EditCircle(R.drawable.ic_select_all, stringResource(R.string.photos_hide_all_text)) { edit = edit.copy(covers = edit.covers + words.filterNot { it in edit.covers }) }
                                EditCircle(R.drawable.ic_crop_square, stringResource(R.string.photos_black_bar), selected = !stars) {
                                    stars = false; edit = edit.copy(covers = edit.covers.filterNot { it.eye } + eyeCovers(eyes, source?.width ?: 1, source?.height ?: 1, false)) }
                                EditCircle(R.drawable.ic_star, stringResource(R.string.photos_stars), selected = stars) {
                                    stars = true; edit = edit.copy(covers = edit.covers.filterNot { it.eye } + eyeCovers(eyes, source?.width ?: 1, source?.height ?: 1, true)) }
                                EditCircle(R.drawable.ic_undo, stringResource(R.string.photos_show_last), enabled = edit.covers.isNotEmpty()) { edit = edit.copy(covers = edit.covers.dropLast(1)) }
                            }
                        }
                    }
                    EditCircle(R.drawable.ic_refresh, stringResource(R.string.photos_undo_all), enabled = changed && !saving) { edit = PhotoEdit() }
                    EditCircle(R.drawable.ic_save, stringResource(R.string.photos_save_or_discard), enabled = changed && !saving) { saveSheetOpen = true }
                }
                if (hiding && expanded && error == null) Text(stringResource(if (words.isNotEmpty()) R.string.photos_hide_hint_words else R.string.photos_hide_hint),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }
            }
        }
    }
    if (saveSheetOpen) ModalBottomSheet(onDismissRequest = { saveSheetOpen = false }, containerColor = islandColor(), contentColor = islandContentColor()) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(entry.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            DriveWideAction(R.drawable.ic_save, stringResource(R.string.common_save)) { saveSheetOpen = false; replace() }
            DriveWideAction(R.drawable.ic_copy, stringResource(R.string.photos_save_copy)) { saveSheetOpen = false; save(replace = false) }
            DriveWideAction(R.drawable.ic_cancel, stringResource(R.string.photos_discard_changes)) { saveSheetOpen = false; close() }
        }
    }
    if (discardOpen) AlertDialog(
        onDismissRequest = { discardOpen = false },
        title = { Text(stringResource(R.string.photos_discard_ask)) },
        text = { Text(stringResource(R.string.photos_discard_body)) },
        confirmButton = { TextButton(onClick = { discardOpen = false; close() }) { Text(stringResource(R.string.common_discard), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        dismissButton = { TextButton(onClick = { discardOpen = false }) { Text(stringResource(R.string.photos_keep_editing), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
    )
}


/** The covers over the photo, the found words as outlines to tap, and a dragged box hiding any part (in the photo's 0..1 space). */
@Composable
private fun CoverLayer(imageWidth: Int, imageHeight: Int, covers: List<PhotoCover>, words: List<PhotoCover>, stars: Boolean, tap: (Float, Float) -> Unit, add: (PhotoCover) -> Unit) {
    var layerSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var drag by remember { mutableStateOf<Pair<androidx.compose.ui.geometry.Offset, androidx.compose.ui.geometry.Offset>?>(null) }
    val currentTap by androidx.compose.runtime.rememberUpdatedState(tap)
    val currentAdd by androidx.compose.runtime.rememberUpdatedState(add)
    val currentStars by androidx.compose.runtime.rememberUpdatedState(stars)
    fun imageRect(): androidx.compose.ui.geometry.Rect {
        val scale = minOf(layerSize.width / imageWidth.toFloat(), layerSize.height / imageHeight.toFloat())
        val w = imageWidth * scale; val h = imageHeight * scale
        return androidx.compose.ui.geometry.Rect(androidx.compose.ui.geometry.Offset((layerSize.width - w) / 2f, (layerSize.height - h) / 2f), androidx.compose.ui.geometry.Size(w, h))
    }
    fun norm(p: androidx.compose.ui.geometry.Offset) = imageRect().let { r -> ((p.x - r.left) / r.width).coerceIn(0f, 1f) to ((p.y - r.top) / r.height).coerceIn(0f, 1f) }
    val outline = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize().onSizeChanged { layerSize = it }
        .pointerInput(Unit) { detectTapGestures { p -> norm(p).let { (x, y) -> currentTap(x, y) } } }
        .pointerInput(Unit) { detectDragGestures(
            onDragStart = { drag = it to it },
            onDrag = { change, _ -> change.consume(); drag = drag?.copy(second = change.position) },
            onDragEnd = { drag?.let { (a, b) ->
                val (x1, y1) = norm(a); val (x2, y2) = norm(b)
                if (kotlin.math.abs(x2 - x1) > 0.005f && kotlin.math.abs(y2 - y1) > 0.005f) currentAdd(PhotoCover(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2), currentStars))
            }; drag = null },
            onDragCancel = { drag = null },
        ) }) {
        val r = imageRect()
        fun place(c: PhotoCover) = androidx.compose.ui.geometry.Rect(r.left + c.left * r.width, r.top + c.top * r.height, r.left + c.right * r.width, r.top + c.bottom * r.height)
        words.filterNot { it in covers }.forEach { val b = place(it); drawRect(outline, b.topLeft, b.size, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx())) }
        drawIntoCanvas { canvas -> covers.forEach { val b = place(it); drawCover(canvas.nativeCanvas, android.graphics.RectF(b.left, b.top, b.right, b.bottom), it.stars) } }
        drag?.let { (a, b) -> drawRect(outline, androidx.compose.ui.geometry.Offset(minOf(a.x, b.x), minOf(a.y, b.y)),
            androidx.compose.ui.geometry.Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx())) }
    }
}

private enum class EditTab(@androidx.annotation.StringRes val label: Int, val icon: Int) { Adjust(R.string.photos_adjust, R.drawable.ic_tune), Markup(R.string.photos_markup, R.drawable.ic_edit), Hide(R.string.common_hide, R.drawable.ic_visibility_off) }

/** The editor island's round button; [selected] marks a chosen style (bar or stars). */
@Composable
private fun EditCircle(icon: Int, label: String, selected: Boolean = false, enabled: Boolean = true, click: () -> Unit) = Surface(
    color = if (selected) driveNavigationSelectedColor() else islandContentColor().copy(alpha = 0.1f),
    contentColor = if (selected) driveNavigationSelectedContentColor() else islandContentColor(),
    shape = CircleShape, modifier = Modifier.size(44.dp),
) { IconButton(onClick = click, enabled = enabled) { Icon(painterResource(icon), contentDescription = label) } }
