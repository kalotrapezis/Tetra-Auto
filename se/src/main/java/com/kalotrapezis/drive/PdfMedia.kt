package com.kalotrapezis.drive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.PDFPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Films and sounds kept in a PDF (asked 27 Oct, as Words' and Slides' play theirs): a Screen annotation's rendition, a
 * RichMedia annotation's asset, a Movie's file — when the file is in the PDF — and a Sound annotation's samples (made a WAV).
 */
internal object PdfMedia {
    /** One on a page: the annotation's place among the page's, where it is (points from the page's top left), its file's name;
     *  [drawn]: it has a picture of its own (an appearance; MuPDF draws a Sound its speaker), else Tetra draws a dark frame or a speaker there. */
    class Spot(val index: Int, val x: Float, val y: Float, val w: Float, val h: Float, val name: String, val drawn: Boolean = true)

    private fun PDFObject.at(key: String): PDFObject? = get(key)?.takeUnless { it.isNull }

    /** The films and sounds on [p] whose files are in the PDF (blocking); where each is its bounds (MuPDF has no Rect for a Screen annotation). */
    fun spots(p: PDFPage): List<Spot> { val o = p.bounds
        return p.annotations.orEmpty().withIndex().mapNotNull { (n, a) -> file(a)?.let { (name, _) -> a.bounds.let { r -> Spot(n, r.x0 - o.x0, r.y0 - o.y0, r.x1 - r.x0, r.y1 - r.y0, name, a.type == PDFAnnotation.TYPE_SOUND || a.`object`.at("AP") != null) } } }
    }

    /** The [n]th annotation's film or sound on [p] written into [dir] to play (blocking); null: none there. */
    fun play(p: PDFPage, n: Int, dir: File): String? = p.annotations.orEmpty().getOrNull(n)?.let(::file)?.let { (name, bytes) ->
        bytes()?.let { b -> File(dir.apply { mkdirs() }, "pdf-film-${name.substringAfterLast('/')}").apply { writeBytes(b) }.path } }

    /** [a]'s file's name and a way to read it; null: not a film or sound kept in the file. */
    fun file(a: PDFAnnotation): Pair<String, () -> ByteArray?>? = runCatching {
        val o = a.`object`
        when (o.at("Subtype")?.asName()) {
            // its action (or one after it) a rendition, its media clip's data a file
            "Screen" -> generateSequence(o.at("A")) { it.at("Next")?.takeIf { n -> n.isDictionary } }.take(8).firstOrNull { it.at("S")?.asName() == "Rendition" }
                ?.at("R")?.let { r -> if (r.at("S")?.asName() == "SR") r.at("R")?.takeIf { it.isArray && it.size() > 0 }?.get(0) else r }
                ?.at("C")?.at("D")?.let(::spec)
            "RichMedia" -> o.at("RichMediaContent")?.at("Assets")?.at("Names")?.let { names -> (0 until names.size() / 2).map { names.get(2 * it + 1) } }?.firstNotNullOfOrNull(::spec)
            "Movie" -> o.at("Movie")?.at("F")?.let(::spec)
            "Sound" -> o.at("Sound")?.takeIf { it.isStream }?.let { s -> "sound.wav" to { wav(s) } }
            else -> null
        }?.takeIf { OfficeVideo.media(it.first) }
    }.getOrNull()

    /** A file specification with its file embedded (or the stream itself): its name and bytes. */
    private fun spec(f: PDFObject): Pair<String, () -> ByteArray?>? =
        if (f.isStream) "film.mp4" to { f.readStream() }
        else (f.at("EF")?.let { it.at("UF") ?: it.at("F") })?.let { s -> (f.at("UF") ?: f.at("F"))?.asString().orEmpty().ifEmpty { "film.mp4" } to { s.readStream() } }

    /** A Sound's samples as a WAV: its rate, channels and bits; Raw unsigned, Signed, muLaw or ALaw; 16-bit samples big-endian in a PDF. */
    fun wav(s: PDFObject): ByteArray {
        val rate = s.at("R")?.asFloat()?.toInt()?.takeIf { it > 0 } ?: 8000; val ch = s.at("C")?.asInteger()?.takeIf { it > 0 } ?: 1
        val bits = s.at("B")?.asInteger()?.takeIf { it == 16 } ?: 8; val enc = s.at("E")?.asName().orEmpty()
        val data = s.readStream()
        val format = when (enc) { "muLaw" -> 7; "ALaw" -> 6; else -> 1 }
        if (format == 1) {
            if (bits == 16) for (i in 0 until data.size - 1 step 2) { val hi = data[i]; data[i] = data[i + 1]; data[i + 1] = if (enc == "Signed") hi else (hi.toInt() xor 0x80).toByte() }
            else if (enc == "Signed") for (i in data.indices) data[i] = (data[i] + 128).toByte() // a WAV's 8 bits are unsigned
        }
        val bytes = if (format == 1) bits / 8 else 1
        return java.nio.ByteBuffer.allocate(44 + data.size).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVEfmt ".toByteArray()); putInt(16); putShort(format.toShort()); putShort(ch.toShort())
            putInt(rate); putInt(rate * ch * bytes); putShort((ch * bytes).toShort()); putShort((bytes * 8).toShort()); put("data".toByteArray()); putInt(data.size); put(data)
        }.array()
    }
}

/** A PDF's films and sounds being chosen and played: in Edit, as in Words, a tap chooses one ([chosen]: its page and it) and its
 *  Video tab's Play plays it; outside Edit a tap plays it. [playing]: its page, it and its file. */
internal class PdfMediaState {
    var chosen by mutableStateOf<Pair<Int, PdfMedia.Spot>?>(null)
    var playing by mutableStateOf<Triple<Int, PdfMedia.Spot, String>?>(null)
    suspend fun play(doc: PdfDoc, i: Int, sp: PdfMedia.Spot, cache: File) =
        withContext(Dispatchers.IO) { doc.mediaFile(i, sp.index, cache) }?.let { playing = Triple(i, sp, it); WriterLog.i("PDF: a film played") }
}

/** Page [i]'s films and sounds over it (its size [s] in points): a film with Words' play mark, the chosen one framed, the one playing in its place (✕ stops it). */
@Composable
internal fun PdfMediaLayer(doc: PdfDoc, i: Int, s: Size, editing: Boolean, media: PdfMediaState, version: Int, moved: (PdfMedia.Spot, Float, Float) -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    val spots by produceState(emptyList<PdfMedia.Spot>(), doc, i, version) { value = withContext(Dispatchers.IO) { doc.media(i) } }
    // the chosen one read again after a change (moved: its new place)
    LaunchedEffect(spots) { media.chosen?.takeIf { it.first == i }?.let { c -> spots.firstOrNull { it.index == c.second.index }?.let { media.chosen = i to it } } }
    if (spots.isEmpty()) return
    var drag by remember { mutableStateOf<Pair<Int, Offset>?>(null) } // a film being moved (its index) and how far, in points
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val k = maxWidth / s.width; val px = with(LocalDensity.current) { k.toPx() }
        for (sp in spots) Box(Modifier.offset(k * (sp.x + (drag?.takeIf { it.first == sp.index }?.second?.x ?: 0f)), k * (sp.y + (drag?.takeIf { it.first == sp.index }?.second?.y ?: 0f))).size(k * sp.w, k * sp.h)
            .then(if (media.chosen?.let { it.first == i && it.second.index == sp.index } == true) Modifier.border(2.dp, WORD_BLUE) else Modifier)
            // in Edit, as a picture in Words: pressed and dragged it moves (chosen if it was not), not the page under it
            .pointerInput(editing, sp) { if (editing) detectDragGestures(onDragStart = { media.chosen = i to sp; drag = sp.index to Offset.Zero },
                onDragEnd = { drag?.second?.let { d -> if (d != Offset.Zero) moved(sp, d.x, d.y) }; drag = null }, onDragCancel = { drag = null }) { ch, by -> ch.consume()
                drag = sp.index to ((drag?.second ?: Offset.Zero) + by / px) } }
            .clickable { if (editing) media.chosen = i to sp else scope.launch { media.play(doc, i, sp, context.cacheDir) } }
        ) { Canvas(Modifier.fillMaxSize()) { drawIntoCanvas { c ->
            if (!sp.drawn) OfficeVideo.stand(sp.name).run { setBounds(0, 0, size.width.toInt(), size.height.toInt()); draw(c.nativeCanvas) }
            if (!OfficeVideo.audio(sp.name)) OfficeVideo.mark(c.nativeCanvas, 0f, 0f, size.width, size.height) } } }
        media.playing?.takeIf { it.first == i }?.let { (_, sp, f) -> VideoFrame(f, Modifier.offset(k * sp.x, k * sp.y).size(k * sp.w, k * sp.h)) { media.playing = null } }
    }
}
