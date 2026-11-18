package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.ceil

/** PDF's red, as Files colours a .pdf. */
internal val PDF_RED = Color(0xFFE3685F)

/** PDFs (asked 17 Oct: a fourth Office app, a viewer — share, print and the basics, no editing). */
internal object Pdf {
    const val MAX_ZOOM = 5f
    fun isPdf(name: String) = name.endsWith(".pdf", ignoreCase = true)

    /** A new PDF in [dir] (asked 24 Oct: "a blank page so we can create a PDF"): one empty A4 portrait page, "New PDF (n).pdf". */
    fun newBlank(dir: File): File {
        dir.mkdirs()
        val f = generateSequence(1) { it + 1 }.map { File(dir, if (it == 1) "New PDF.pdf" else "New PDF ($it).pdf") }.first { !it.exists() }
        val out = com.artifex.mupdf.fitz.PDFDocument()
        try {
            out.insertPage(0, out.addPage(com.artifex.mupdf.fitz.Rect(0f, 0f, 595f, 842f), 0, out.newDictionary(), ""))
            out.save(f.path, "compress")
        } finally { out.destroy() }
        return f
    }

    /** Page text and search: MuPDF reads them on every Android (Android's own renderer only from 15). */
    const val hasText = true

    /**
     * A pinch: the zoom times [by] about [at] (from the middle of the view) and moved by [pan], kept to 1–5× and to the
     * zoomed view's edges, so the point under the fingers stays under them.
     */
    fun zoomed(zoom: Float, offset: Offset, by: Float, at: Offset, pan: Offset, size: Size): Pair<Float, Offset> {
        val z = (zoom * by).coerceIn(1f, MAX_ZOOM)
        val k = z / zoom
        val o = at * (1 - k) + offset * k + pan
        val mx = size.width * (z - 1) / 2; val my = size.height * (z - 1) / 2
        return z to Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    /** A page's bitmap width for [widthPx] on screen at [zoom]: in half steps of zoom (not one per frame), its long edge at most 4096 px (memory). */
    fun renderWidth(widthPx: Int, zoom: Float, aspect: Float): Int {
        val w = widthPx * ceil(zoom * 2) / 2
        val cap = if (aspect >= 1f) 4096f else 4096f * aspect
        return minOf(w, cap).toInt().coerceAtLeast(1)
    }
}

/** A comment on a page, as the Comments pane lists it: its kind, words, place (page points) and [index] among the page's. */
/** Find & redact's ready patterns (the user, 22 Oct: "text redaction in data level"): what people most often black out. */
internal val REDACT_PATTERNS = listOf(
    R.string.pdf_redact_phones to Regex("""(?<!\d)(?:\+30[\s-]?)?(?:2\d|69)\d(?:[\s-]?\d){7}(?!\d)"""),
    R.string.pdf_redact_emails to Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+"""),
    R.string.pdf_redact_afm to Regex("""(?<!\d)\d{9}(?!\d)"""),
    R.string.pdf_redact_amka to Regex("""(?<!\d)\d{11}(?!\d)"""),
    R.string.pdf_redact_iban to Regex("""\b[A-Z]{2}\d{2}(?:\s?[A-Z0-9]{4}){3,7}(?:\s?[A-Z0-9]{1,4})?\b"""),
)

internal class PdfComment(val page: Int, val index: Int, @androidx.annotation.StringRes val kind: Int, val words: String, val at: Offset, val replyTo: Int? = null, val author: String = "",
    /** when it was last changed (ISO), and whether it was resolved (a Review state reply, as other readers mark it) */ val date: String? = null, val done: Boolean = false) {
    /** As Words' comment cards show it: its own id, its parent's, who wrote it (else its kind), when. */
    fun card(kindName: String) = DocComment("$page:$index", author.ifBlank { kindName }, date, words, parent = replyTo?.let { "$page:$it" }, done = done)
}

/** A bookmark (the file's contents): its title, page, depth. */
internal class PdfMark(val title: String, val page: Int, val depth: Int)

/** A list's marker at a line's start (Edit in Words): a bullet, or a number or letter with . or ). */
internal val LIST_BULLET = Regex("""^[•◦▪▫●○■□‣⁃∙·\-–—*\uF0B7\uF0A7]\s*""")
internal val LIST_NUMBER = Regex("""^(\d{1,3}|[a-zα-ω]|[ivx]{1,4})[.)](\s+|$)""", RegexOption.IGNORE_CASE)
internal fun isMarkerOnly(t: String) = t.isNotBlank() && (LIST_BULLET.replace(t.trim(), "").isEmpty() || LIST_NUMBER.replace(t.trim(), "").isEmpty())

/** A piece of a PDF's line between wide gaps (Edit in Words): a table's cell when it lines up with others, else words. */
internal class PdfSeg(val text: String, val x0: Float, val x1: Float, val y: Float, val size: Float, val bold: Boolean, val italic: Boolean, val color: Int)

/** A form's field tapped: what it is ("text", "check", "radio", "choice", other), its name, value and choices. */
internal class PdfField(val kind: String, val name: String, val value: String, val options: List<String>, val multiline: Boolean, val readOnly: Boolean)

/** The editor's tools that work on the page itself (Review and Draw); a finger draws while one is on. */
internal enum class PdfTool(@androidx.annotation.StringRes val label: Int, val icon: Int) {
    Highlight(R.string.words_highlight, R.drawable.ic_highlighter), Underline(R.string.words_underline, R.drawable.ic_format_underlined), Strike(R.string.words_strikethrough, R.drawable.ic_format_strikethrough),
    Note(R.string.pdf_note, R.drawable.ic_comment), Rectangle(R.string.pdf_rectangle, R.drawable.ic_crop_square), Oval(R.string.pdf_oval, R.drawable.ic_circle), Arrow(R.string.pdf_arrow, R.drawable.ic_arrow_forward),
    Pen(R.string.words_pen, R.drawable.ic_ink_pen), Marker(R.string.words_highlighter, R.drawable.ic_ink_highlighter), Erase(R.string.words_eraser, R.drawable.ic_delete),
    Text(R.string.pdf_text, R.drawable.ic_text_fields), Tick(R.string.pdf_tick, R.drawable.ic_check), Cross(R.string.pdf_cross, R.drawable.ic_close), Dot(R.string.pdf_dot, R.drawable.ic_circle),
    Date(R.string.words_date, R.drawable.ic_calendar_today), Sign(R.string.pdf_sign, R.drawable.ic_draw), Redact(R.string.pdf_mark_area, R.drawable.ic_crop_square),
    TextBox(R.string.words_text_box, R.drawable.ic_text_fields), Stamp(R.string.pdf_stamp, R.drawable.ic_branding_watermark), RedactText(R.string.pdf_select_text, R.drawable.ic_format_strikethrough),
    TextField(R.string.words_text_field, R.drawable.ic_crop_square), CheckBox(R.string.words_cc_check_box, R.drawable.ic_check_box_outline_blank),
}

/** PDF's standard stamps, as every reader names and draws them. */
internal const val PICTURE_STAMP = "Picture"
/** Tetra's key on a text it put on ([PdfDoc.putText]): its words and look, to be typed in again. */
internal const val TYPED_KEY = "TetraText"
/** Words' screen round its pages and its ribbon's colour (Writer.kt's), the PDF's too. */
private val WORDS_SCREEN = Color(0xFF1B1D1E)
private val WORDS_RIBBON = Color(0xFF16191A)
/** The chosen object's own tab (shown by its kind's name, as Words' Image, Shape, Text box…). */
private const val OBJECT_TAB = "Object"
internal val STAMPS = listOf("Approved", "Draft", "Final", "Confidential", "Experimental", "Expired", "NotApproved", "ForComment", "AsIs", "Sold",
    "Departmental", "NotForPublicRelease", "TopSecret", "ForPublicRelease")


/** A word found: its page and its boxes there (in the page's points). */
internal class PdfMatch(val page: Int, val boxes: List<RectF>)

/** A PDF that needs its password: [tried] when one was given and it was wrong. */
internal class PdfPasswordException(val tried: Boolean) : Exception("password")

/** A failure of ours, its words for the user in [res]: what [pdfSays] shows. */
internal class PdfError(@androidx.annotation.StringRes val res: Int) : Exception()
/** What to tell the user about [t]: our own words, else what the library said, else [fallback]. */
internal fun pdfSays(context: android.content.Context, t: Throwable, @androidx.annotation.StringRes fallback: Int): String =
    (t as? PdfError)?.let { context.getString(it.res) } ?: t.message ?: context.getString(fallback)

/**
 * One open PDF, read by MuPDF (Plan.md § PDF: it draws a file's comments — highlights, notes, ink, stamps — which Android's
 * PdfRenderer leaves out, and it edits): every use takes the lock, off the main thread; its pages' sizes; a small cache
 * of drawn pages so scrolling back is instant.
 */
internal class PdfDoc(private val file: File, password: String? = null) {
    private val doc: com.artifex.mupdf.fitz.Document = com.artifex.mupdf.fitz.Document.openDocument(file.path).also { d ->
        if (d.needsPassword() && (password == null || !d.authenticatePassword(password))) { d.destroy(); throw PdfPasswordException(password != null) }
    }
    /** The PDF itself, for editing (every change an undo step of MuPDF's own journal). */
    private val pdf: com.artifex.mupdf.fitz.PDFDocument? = doc.asPDF()?.also { p -> runCatching { p.enableJournal() }.onFailure { WriterLog.w("PDF: no undo", it) } }
    private var closed = false
    private var logged = false

    /** Each page's size in points (1/72 in), read again after a change: the list lays out every page before any is drawn. */
    var sizes: List<Size> = synchronized(this) { readSizes() }
        private set
    private fun readSizes() = (0 until doc.countPages()).map { i -> page(i) { p ->
        val b = p.bounds; Size((b.x1 - b.x0).coerceAtLeast(1f), (b.y1 - b.y0).coerceAtLeast(1f)) } }

    // —— editing (Plan.md § PDF, Phase 1: pages) ——

    val editable get() = pdf != null
    // MuPDF's undo cannot take off what a save wrote (it says it can, and changes nothing; changes after the save undo
    // and redo as before, 27 Oct): Undo goes back to the last save, Redo only to what was undone since
    private var savedAt = 0
    private var redoStale = false
    val canUndo get() = synchronized(this) { (pdf?.undoRedoPosition() ?: 0) > savedAt }
    val canRedo get() = synchronized(this) { !redoStale && pdf?.let { it.undoRedoPosition() < it.undoRedoSteps() } == true }
    val dirty get() = synchronized(this) { pdf?.hasUnsavedChanges() == true || rewrite != null || copied }
    /** Saved only as a copy (File › Save a copy) since the last change: the file itself still is not. */
    private var copied = false
    /** The document's own details (Document.META_INFO_TITLE…), null when it has none. */
    fun info(key: String): String? = synchronized(this) { runCatching { doc.getMetaData(key) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() } }

    /** One change, one undo step named [what]; the pages drawn again. */
    fun edit(what: String, change: (com.artifex.mupdf.fitz.PDFDocument) -> Unit) = synchronized(this) {
        val p = pdf ?: throw PdfError(R.string.pdf_cannot_edit)
        WriterLog.i("PDF: $what")
        p.beginOperation(what)
        try { change(p) } finally { p.endOperation() }
        redoStale = false // what could be redone is gone with a new change
        changed()
    }
    fun undo() = synchronized(this) { if (canUndo) { pdf?.undo(); changed() } }
    /** What was just undone not to be redone (a gallery's preview taken off, as Words' Cancel). */
    fun forgetRedo() = synchronized(this) { redoStale = true }
    fun redo() = synchronized(this) { if (canRedo) { pdf?.redo(); changed() } }
    private fun changed() { cache.evictAll(); sizes = readSizes() }

    /** Pages [pages] turned by [by] degrees (90 right, -90 left). */
    fun rotate(pages: Collection<Int>, by: Int) = edit("Rotate") { p ->
        pages.forEach { i -> val o = p.findPage(i); o.put("Rotate", ((o.getInheritable("Rotate").asInteger() + by) % 360 + 360) % 360) }
    }
    fun delete(pages: Collection<Int>) = edit("Delete pages") { p -> pages.sortedDescending().forEach { p.deletePage(it) } }
    /** The pages in a new order: [order][k] is the page that goes k-th. */
    fun move(order: List<Int>) = edit("Move pages") { p -> p.rearrangePages(order.toIntArray()) }
    /** An empty page at [at], the size of the one beside it. */
    fun insertBlank(at: Int) = edit("Blank page") { p ->
        val s = sizes.getOrNull(at.coerceAtMost(sizes.lastIndex).coerceAtLeast(0)) ?: Size(595f, 842f)
        p.insertPage(at, p.addPage(com.artifex.mupdf.fitz.Rect(0f, 0f, s.width, s.height), 0, p.newDictionary(), ""))
    }
    /**
     * Every page made [to] its size as shown (Layout › Size and Orientation, as Word's: the whole document), its top left
     * where it was so what is on it stays put; its crop the same box (the old one would cut the new page).
     */
    fun resize(to: (Size) -> Size) = edit("Page size") { p ->
        sizes.forEachIndexed { i, now ->
            val want = to(now); if (want == now) return@forEachIndexed
            val o = p.findPage(i); val mb = o.getInheritable("MediaBox")
            val x0 = mb.get(0).asFloat(); val y1 = mb.get(3).asFloat()
            val turned = Math.floorMod(o.getInheritable("Rotate").asInteger(), 180) == 90 // shown turned: the box the other way round
            val (w, h) = if (turned) want.height to want.width else want.width to want.height
            val box = com.artifex.mupdf.fitz.Rect(x0, y1 - h, x0 + w, y1)
            o.put("MediaBox", box); o.put("CropBox", box); listOf("TrimBox", "BleedBox", "ArtBox").forEach { o.delete(it) }
        }
    }
    /** Another PDF's pages at [at] (Combine): how many came. */
    fun insertFile(at: Int, other: File): Int {
        val src = com.artifex.mupdf.fitz.Document.openDocument(other.path)
        try {
            if (src.needsPassword()) throw PdfError(R.string.pdf_insert_has_password)
            val o = src.asPDF() ?: throw PdfError(R.string.pdf_not_a_pdf)
            val n = o.countPages()
            edit("Insert ${other.name}") { p -> repeat(n) { k -> p.graftPage(at + k, o, k) } }
            return n
        } finally { src.destroy() }
    }
    /** Pages [pages] as a PDF of their own in [to] (Extract). */
    fun extract(pages: List<Int>, to: File) = synchronized(this) {
        val p = pdf ?: throw PdfError(R.string.pdf_cannot_edit)
        val out = com.artifex.mupdf.fitz.PDFDocument()
        try { pages.forEachIndexed { k, i -> out.graftPage(k, p, i) }; out.save(to.path, "garbage,compress") } finally { out.destroy() }
    }

    // —— comments (Plan.md § PDF, Phase 1): points are page points from the page's top left, as [sizes] and [find] ——

    private fun rgb(c: Int) = floatArrayOf((c shr 16 and 0xFF) / 255f, (c shr 8 and 0xFF) / 255f, (c and 0xFF) / 255f)
    internal fun annotate(i: Int, what: String, make: (com.artifex.mupdf.fitz.PDFPage, Float, Float) -> Boolean): Boolean {
        var made = false
        edit(what) { _ -> page(i) { p -> val o = p.bounds; made = make(p as com.artifex.mupdf.fitz.PDFPage, o.x0, o.y0); p.update() } }
        return made
    }

    /** Highlight, underline or strike out ([type]) the words from [a] to [b], as a finger went over them: false when none. */
    fun markText(i: Int, a: Offset, b: Offset, type: Int, color: Int) = annotate(i, when (type) {
        com.artifex.mupdf.fitz.PDFAnnotation.TYPE_UNDERLINE -> "Underline"; com.artifex.mupdf.fitz.PDFAnnotation.TYPE_STRIKE_OUT -> "Strikethrough"; com.artifex.mupdf.fitz.PDFAnnotation.TYPE_REDACT -> "Mark for redaction"; else -> "Highlight" }) { p, x0, y0 ->
        val t = p.toStructuredText()
        try {
            val quads = t.highlight(com.artifex.mupdf.fitz.Point(a.x + x0, a.y + y0), com.artifex.mupdf.fitz.Point(b.x + x0, b.y + y0)).orEmpty()
            if (quads.isEmpty()) false else { p.createAnnotation(type).apply { setQuadPoints(quads); setColor(rgb(color)); update() }; true }
        } finally { t.destroy() }
    }

    /** A pen's strokes, [width] points wide. */
    fun ink(i: Int, strokes: List<List<Offset>>, color: Int, width: Float) = annotate(i, "Draw") { p, x0, y0 ->
        p.createAnnotation(com.artifex.mupdf.fitz.PDFAnnotation.TYPE_INK).apply {
            setInkList(strokes.map { st -> st.map { com.artifex.mupdf.fitz.Point(it.x + x0, it.y + y0) }.toTypedArray() }.toTypedArray())
            setColor(rgb(color)); setBorderWidth(width); update()
        }; true
    }

    /** A note's icon at [at], [text] in it. */
    fun note(i: Int, at: Offset, text: String, color: Int, author: String = "") = annotate(i, "Note") { p, x0, y0 ->
        p.createAnnotation(com.artifex.mupdf.fitz.PDFAnnotation.TYPE_TEXT).apply {
            setRect(com.artifex.mupdf.fitz.Rect(at.x + x0, at.y + y0, at.x + x0 + 20, at.y + y0 + 20)); setContents(text); setColor(rgb(color))
            if (author.isNotBlank()) runCatching { setAuthor(author) }; runCatching { setModificationDate(java.util.Date()) }; update()
        }; true
    }

    /** A rectangle, an oval ([type] square or circle) or an arrow from [a] to [b]. */
    fun shape(i: Int, type: Int, a: Offset, b: Offset, color: Int, width: Float) = annotate(i, when (type) {
        com.artifex.mupdf.fitz.PDFAnnotation.TYPE_CIRCLE -> "Oval"; com.artifex.mupdf.fitz.PDFAnnotation.TYPE_LINE -> "Arrow"; else -> "Rectangle" }) { p, x0, y0 ->
        p.createAnnotation(type).apply {
            if (type == com.artifex.mupdf.fitz.PDFAnnotation.TYPE_LINE) {
                setLine(com.artifex.mupdf.fitz.Point(a.x + x0, a.y + y0), com.artifex.mupdf.fitz.Point(b.x + x0, b.y + y0))
                setLineEndingStyles(com.artifex.mupdf.fitz.PDFAnnotation.LINE_ENDING_NONE, com.artifex.mupdf.fitz.PDFAnnotation.LINE_ENDING_OPEN_ARROW)
            } else setRect(com.artifex.mupdf.fitz.Rect(minOf(a.x, b.x) + x0, minOf(a.y, b.y) + y0, maxOf(a.x, b.x) + x0, maxOf(a.y, b.y) + y0))
            setColor(rgb(color)); setBorderWidth(width); update()
        }; true
    }

    /**
     * Words put on the page at [at] (Fill & Sign › Text, Date), [size] points, no box round them: a text box comment whose
     * look MuPDF makes with its own fonts, Greek included (URW's base fonts carry Latin, Greek and Cyrillic).
     */
    fun text(i: Int, at: Offset, words: String, size: Float, color: Int) = annotate(i, "Text") { p, x0, y0 ->
        val lines = words.lines()
        val w = lines.maxOf { it.length } * size * 0.62f + size; val h = lines.size * size * 1.25f + size * 0.4f
        p.createAnnotation(com.artifex.mupdf.fitz.PDFAnnotation.TYPE_FREE_TEXT).apply {
            setRect(com.artifex.mupdf.fitz.Rect(at.x + x0, at.y + y0, at.x + x0 + w, at.y + y0 + h))
            // the Greek set MuPDF writes text boxes in has no long dashes or curly quotes: their plain ones
            setDefaultAppearance("Helv", size, rgb(color)); setContents(words.replace('—', '-').replace('–', '-').replace('“', '"').replace('”', '"'))
            setBorderWidth(0f); update()
        }; true
    }

    /** A tick, a cross or a dot ([mark] ✓ ✗ •) at [at], [size] points: drawn strokes (no font needed), as Fill & Sign's. */
    fun mark(i: Int, at: Offset, mark: Char, size: Float, color: Int) = annotate(i, "Fill: $mark") { p, x0, y0 ->
        val x = at.x + x0 - size / 2; val y = at.y + y0 - size / 2
        fun pt(fx: Float, fy: Float) = com.artifex.mupdf.fitz.Point(x + fx * size, y + fy * size)
        if (mark == '•') p.createAnnotation(com.artifex.mupdf.fitz.PDFAnnotation.TYPE_CIRCLE).apply {
            setRect(com.artifex.mupdf.fitz.Rect(x + size * 0.3f, y + size * 0.3f, x + size * 0.7f, y + size * 0.7f)); setColor(rgb(color)); setInteriorColor(rgb(color)); update()
        } else p.createAnnotation(com.artifex.mupdf.fitz.PDFAnnotation.TYPE_INK).apply {
            setInkList(if (mark == '✓') arrayOf(arrayOf(pt(0.1f, 0.55f), pt(0.4f, 0.85f), pt(0.9f, 0.15f)))
                else arrayOf(arrayOf(pt(0.15f, 0.15f), pt(0.85f, 0.85f)), arrayOf(pt(0.85f, 0.15f), pt(0.15f, 0.85f))))
            setColor(rgb(color)); setBorderWidth(size / 9); update()
        }; true
    }

    /** A picture (the signature, drawn once and kept) at [at], [width] points wide: a stamp whose look is the picture. */
    fun picture(i: Int, at: Offset, png: ByteArray, width: Float, aspect: Float) = annotate(i, "Signature") { p, x0, y0 ->
        p.createAnnotation(com.artifex.mupdf.fitz.PDFAnnotation.TYPE_STAMP).apply {
            setRect(com.artifex.mupdf.fitz.Rect(at.x + x0, at.y + y0, at.x + x0 + width, at.y + y0 + width * aspect))
            setAppearance(com.artifex.mupdf.fitz.Image(png)); update()
        }; true
    }

    // —— Recognize text (OCR): a scan's words, invisible over it, so Find, Copy and other apps read it ——

    private var ocrFont: com.artifex.mupdf.fitz.PDFObject? = null
    private val greek = charset("ISO-8859-7") // MuPDF's Greek simple encoding: ASCII and Greek in one byte each

    /** Page [i] has words of its own (not a scan, or read already). */
    fun hasText(i: Int): Boolean = synchronized(this) { page(i) { p -> p.toStructuredText().let { t -> t.asText().isNotBlank().also { t.destroy() } } } }
    /** Page [i]'s films and sounds (PdfMedia), and the [n]th annotation's written into [dir] to play. */
    fun media(i: Int) = synchronized(this) { page(i) { p -> (p as? com.artifex.mupdf.fitz.PDFPage)?.let(PdfMedia::spots).orEmpty() } }
    /** Page [i]'s [n]th annotation (a film, a sound) moved [dx], [dy] points (right, down): one undo step. */
    fun moveMedia(i: Int, n: Int, dx: Float, dy: Float) = annotate(i, "Move") { p, _, _ ->
        val o = p.annotations.orEmpty().getOrNull(n)?.`object` ?: return@annotate false
        val r = o.get("Rect"); val v = (0 until 4).map { r.get(it).asFloat() }
        // shortcut: on a page turned by its Rotate it goes the page's own way, not the screen's
        o.put("Rect", pdf!!.newArray().apply { push(v[0] + dx); push(v[1] - dy); push(v[2] + dx); push(v[3] - dy) }); true
    }
    fun mediaFile(i: Int, n: Int, dir: File) = synchronized(this) { page(i) { p -> (p as? com.artifex.mupdf.fitz.PDFPage)?.let { PdfMedia.play(it, n, dir) } } }

    /**
     * The file's words as a Words document (the user, 22 Oct: "make it in to words"; "can we make a table and content table
     * transfer correctly … assume the output was a table and fix it after the fact"):
     *  - each block of text a paragraph (its lines joined, a word broken by a hyphen made whole), a page's end a page break;
     *    a block in bigger letters than most a heading (1.6× level 1, 1.3× 2, 1.15× 3), or the level the PDF's bookmarks give
     *    it; colours, sizes, bold, italic as the PDF has them;
     *  - rows of words split by wide gaps, two or more of them with their columns lined up, a Words table;
     *  - a contents page (lines ending in a page number, with dots or not, most of the page) Words' own table of contents,
     *    made from the headings; also when the PDF has bookmarks and no such page, at the start;
     *  - a header or footer (the same words on most pages, numbers aside) left out.
     */
    fun toWords(): OfficeDoc = synchronized(this) {
        class Block(val lines: List<List<PdfSeg>>, val image: com.artifex.mupdf.fitz.Rect? = null)
        val pages = sizes.indices.map { i -> page(i) { p ->
            val t = p.toStructuredText("preserve-images") // the pictures' places too (left out by default)
            try {
                val json = org.json.JSONObject(t.asJSON())
                // the JSON has the fonts, the blocks the letters' places and colours: matched by where each line starts
                val lineChars = t.blocks.orEmpty().flatMap { b -> b.lines.orEmpty().map { l -> Triple(l.bbox.x0, l.bbox.y0, l) } }
                val blocks = json.optJSONArray("blocks") ?: org.json.JSONArray()
                (0 until blocks.length()).mapNotNull { b -> val jb = blocks.getJSONObject(b)
                    // a picture: where it is, cut from the page below
                    if (jb.optString("type") == "image") return@mapNotNull jb.optJSONObject("bbox")?.let { bb ->
                        val x = bb.optDouble("x").toFloat(); val y = bb.optDouble("y").toFloat()
                        Block(emptyList(), com.artifex.mupdf.fitz.Rect(x, y, x + bb.optDouble("w").toFloat(), y + bb.optDouble("h").toFloat())) }
                    jb.takeIf { it.optString("type") == "text" }?.optJSONArray("lines")?.let { ls ->
                    Block((0 until ls.length()).map { k ->
                        val l = ls.getJSONObject(k); val font = l.optJSONObject("font"); val bb = l.optJSONObject("bbox")
                        val x = bb?.optDouble("x", 0.0)?.toFloat() ?: 0f; val y = bb?.optDouble("y", 0.0)?.toFloat() ?: 0f
                        val size = font?.optDouble("size", 11.0)?.toFloat() ?: 11f
                        val bold = font?.optString("weight") == "bold"; val italic = font?.optString("style") == "italic"
                        val line = lineChars.minByOrNull { (cx, cy, _) -> kotlin.math.abs(cx - x) + kotlin.math.abs(cy - y) }?.third
                        val chars = line?.chars.orEmpty().toList()
                        if (chars.isEmpty()) listOf(PdfSeg(l.optString("text"), x, x + (bb?.optDouble("w", 0.0)?.toFloat() ?: 0f), y, size, bold, italic, 0))
                        else {
                            // split where the gap between two letters is wider than a letter and a half
                            val out = ArrayList<PdfSeg>(); var from = 0
                            for (c in 1..chars.size) {
                                val gap = if (c < chars.size) chars[c].quad.ul_x - chars[c - 1].quad.ur_x else Float.MAX_VALUE
                                if (gap > size * 1.5f) {
                                    val part = chars.subList(from, c)
                                    val text = part.joinToString("") { String(Character.toChars(it.c)) }.trim()
                                    if (text.isNotEmpty()) out += PdfSeg(text, part.first().quad.ul_x, part.last().quad.ur_x, y, size, bold, italic,
                                        part.firstOrNull { !it.isWhitespace }?.argb?.and(0xFFFFFF) ?: 0)
                                    from = c
                                }
                            }
                            out
                        }
                    })
                } }
            } finally { t.destroy() }
        } }
        fun textOf(line: List<PdfSeg>) = line.joinToString(" ") { it.text }
        // headers and footers: the same words, numbers aside, on most pages
        fun bare(t: String) = t.replace(Regex("\\d+"), "#").trim()
        val repeated = if (pages.size < 3) emptySet() else pages.flatMap { bs -> bs.map { b -> bare(b.lines.joinToString(" ") { textOf(it) }) }.distinct() }
            .groupingBy { it }.eachCount().filter { (t, n) -> t.isNotBlank() && n * 2 > pages.size }.keys
        val kept = pages.map { bs -> bs.filter { b -> bare(b.lines.joinToString(" ") { textOf(it) }) !in repeated } }
        // the body's size: the one most letters have
        val body = kept.flatten().flatMap { it.lines }.flatten().groupBy { Math.round(it.size * 2) / 2f }.maxByOrNull { (_, ss) -> ss.sumOf { it.text.length } }?.key ?: 11f
        // the headings' sizes, biggest first: the biggest is level 1, the next 2, the next 3 (a title over sections over sub-sections)
        val headingSizes = kept.flatten().flatMap { it.lines }.flatten().map { Math.round(it.size * 2) / 2f }.filter { it >= body * 1.15f }.distinct().sortedDescending().take(3)
        // the PDF's bookmarks: a block with a bookmark's title is a heading of its depth
        val marks = bookmarks().filter { it.title.isNotBlank() }.associate { MapsSearch.fold(it.title.trim()) to (it.depth + 1).coerceAtMost(3) }
        // a contents line: words, then dots or spaces, then a page number
        val contentsLine = Regex("""^(.{2,}?)[\s.·…_]{2,}(\d{1,4})$""")
        fun run(seg: PdfSeg, text: String = seg.text, heading: Int = 0) = DocRun(text, bold = seg.bold && heading == 0, italic = seg.italic,
            color = seg.color.takeIf { it != 0 }, sizePt = if (heading == 0) Math.round(seg.size * 2) / 2f else null)
        val out = ArrayList<DocPara>()
        var contentsAt: Int? = null
        kept.forEachIndexed { pi, blocks ->
            var first = pi > 0 && out.isNotEmpty() // the page's first paragraph starts a page, as in the PDF
            fun add(p: DocPara) { out += if (first) p.copy(keep = OfficeFormats.withPageBreak(p.keep, OfficeKind.Docx, true)) else p; first = false }
            // a contents page: most of its lines end in a page number
            // a contents page: three or more lines that end in a page number — they and their title make way for Words' own
            val contentsTitle = Regex("""^(περιεχόμενα|πίνακας περιεχομένων|contents|table of contents)$""", RegexOption.IGNORE_CASE)
            fun isContents(b: Block) = b.lines.isNotEmpty() && b.lines.all { l -> textOf(l).trim().let { contentsLine.matches(it) || contentsTitle.matches(it) } }
            val blocks = if (contentsAt == null && blocks.flatMap { it.lines }.count { contentsLine.matches(textOf(it).trim()) } >= 3) {
                val firstAt = blocks.indexOfFirst(::isContents)
                blocks.filterIndexed { k, b -> !isContents(b) || k == firstAt }.map { b -> if (isContents(b)) Block(emptyList(), null).also { } else b }
                    .also { contentsAt = -1 } // placed below, where its first block was
            } else blocks
            // the page's lines in rows by where they sit (a table's columns can come as separate blocks)
            val all = blocks.flatMap { it.lines }.filter { it.isNotEmpty() }
            val rows = all.sortedBy { it.first().y }.fold(ArrayList<MutableList<PdfSeg>>()) { acc, line ->
                val last = acc.lastOrNull()
                if (last != null && kotlin.math.abs(last.first().y - line.first().y) < line.first().size * 0.5f) last.addAll(line) else acc += line.toMutableList()
                acc
            }.map { r -> r.sortedBy { it.x0 } }
            // runs of rows of two cells or more, their cells starting where the run's columns do: tables
            val tableOf = HashMap<PdfSeg, Int>(); val tables = ArrayList<List<List<PdfSeg>>>()
            var k = 0
            while (k < rows.size) {
                if (rows[k].size < 2 || isMarkerOnly(rows[k].first().text)) { k++; continue }
                var e = k + 1
                // a cell lines up with a column by its left edge, its right edge or its middle (centred columns); the run's
                // columns grow with each row taken in
                val seen = ArrayList(rows[k])
                fun lines(a: PdfSeg, b: PdfSeg) = kotlin.math.abs(a.x0 - b.x0) < body * 2 || kotlin.math.abs(a.x1 - b.x1) < body * 2 ||
                    kotlin.math.abs((a.x0 + a.x1) - (b.x0 + b.x1)) < body * 4
                fun aligned(b: List<PdfSeg>) = b.size >= 2 && !isMarkerOnly(b.first().text) && b.all { s -> seen.any { lines(it, s) } }
                while (e < rows.size && aligned(rows[e]) && rows[e].first().y - rows[e - 1].first().y < body * 4) { seen.addAll(rows[e]); e++ }
                if (e - k >= 2) { val t = rows.subList(k, e).toList(); t.flatten().forEach { tableOf[it] = tables.size }; tables += t }
                k = maxOf(e, k + 1)
            }
            val emitted = HashSet<Int>()
            // the page drawn once (150 dpi) when it has pictures, each cut from it as the PDF shows it
            val drawn by lazy { page(pi) { p -> val o = p.bounds; com.artifex.mupdf.fitz.android.AndroidDrawDevice.drawPage(p, com.artifex.mupdf.fitz.Matrix.Scale(150f / 72f)) to o } }
            // a list's level from how far its marker is in from the page's least indented marker (level 1 there)
            val left = blocks.flatMap { it.lines }.mapNotNull { it.firstOrNull() }.filter { sg -> sg.size < body * 1.15f && (isMarkerOnly(sg.text) ||
                LIST_BULLET.containsMatchIn(sg.text.trimStart().take(2)) || LIST_NUMBER.find(sg.text.trimStart()) != null) }.minOfOrNull { it.x0 } ?: 0f
            var pending: PdfSeg? = null // a bullet or number standing alone (its own line or block): put before the words after it
            fun withPending(ls: List<List<PdfSeg>>): List<List<PdfSeg>> {
                val out = ArrayList<List<PdfSeg>>()
                for (l in ls) {
                    if (l.size == 1 && isMarkerOnly(l[0].text)) { pending = l[0]; continue }
                    val m = pending
                    if (m != null && l.isNotEmpty()) { out += listOf(PdfSeg(m.text.trim() + " " + l[0].text, m.x0, l[0].x1, l[0].y, l[0].size, l[0].bold, l[0].italic, l[0].color)) + l.drop(1); pending = null }
                    else out += l
                }
                return out
            }
            for (b0 in blocks) {
                // the contents' place: an empty paragraph Words' contents replace
                if (b0.image == null && b0.lines.isEmpty()) { if (contentsAt == -1) { contentsAt = out.size; add(DocPara(listOf(DocRun("")))) }; continue }
                val b = if (b0.image != null) b0 else Block(withPending(b0.lines))
                if (b.image == null && b.lines.isEmpty()) continue
                b.image?.let { r ->
                    if (r.x1 - r.x0 < 24 || r.y1 - r.y0 < 24) return@let // a rule or a dot, not a picture
                    runCatching {
                        val (bmp, o) = drawn; val k = 150f / 72f
                        val cx = ((r.x0 - o.x0) * k).toInt().coerceIn(0, bmp.width - 1); val cy = ((r.y0 - o.y0) * k).toInt().coerceIn(0, bmp.height - 1)
                        val cut = Bitmap.createBitmap(bmp, cx, cy, ((r.x1 - r.x0) * k).toInt().coerceIn(1, bmp.width - cx), ((r.y1 - r.y0) * k).toInt().coerceIn(1, bmp.height - cy))
                        val jpg = java.io.ByteArrayOutputStream().also { cut.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
                        val w = (r.x1 - r.x0).coerceAtMost(sizes[pi].width - 2 * 56f); val h = w * (r.y1 - r.y0) / (r.x1 - r.x0)
                        add(DocPara(listOf(DocRun(OBJECT_MARK.toString(), image = DocImage("n" + java.util.UUID.randomUUID(), null, w, h, null, jpg, "jpg")))))
                    }.onFailure { WriterLog.w("PDF: a picture to Words", it) }
                    continue
                }
                val inTable = b.lines.flatten().firstOrNull { it in tableOf }?.let { tableOf[it] }
                if (inTable != null) {
                    if (emitted.add(inTable)) {
                        val t = tables[inTable]
                        // the columns: where the cells start, the widest row's
                        val cols = t.maxBy { it.size }.map { (it.x0 + it.x1) / 2 }
                        val width = (sizes[pi].width - 2 * 56f).coerceAtLeast(200f)
                        val table = TableEdit.newTable(t.size, cols.size, width).let { tb ->
                            tb.copy(rows = tb.rows.mapIndexed { r, row -> row.copy(cells = row.cells.mapIndexed { c, cell ->
                                val inCell = t[r].filter { s -> cols.indices.minBy { kotlin.math.abs(cols[it] - (s.x0 + s.x1) / 2) } == c }
                                cell.copy(paras = listOf(DocPara(if (inCell.isEmpty()) listOf(DocRun("")) else inCell.mapIndexed { n, s -> run(s, (if (n > 0) " " else "") + s.text) })))
                            }) })
                        }
                        add(DocPara(listOf(DocRun(OBJECT_MARK.toString())), table = table))
                    }
                    // the block's lines that are not the table's stay text
                    val rest = b.lines.filter { l -> l.none { it in tableOf } }
                    if (rest.isEmpty()) continue
                    paragraph(rest, ::add, body, headingSizes, marks, left, ::run)
                } else paragraph(b.lines, ::add, body, headingSizes, marks, left, ::run)
            }
        }
        var doc = OfficeDoc(out)
        // the contents: where the PDF's were, or at the start when it had bookmarks but no such page
        val at = contentsAt?.takeIf { it >= 0 } ?: if (marks.isNotEmpty() && doc.paras.any { it.heading > 0 }) 0 else null
        if (at != null && doc.paras.any { it.heading > 0 }) runCatching {
            val styles = DocStyles.of(OfficeKind.Docx, null)
            doc = Contents.insert(doc, OfficeKind.Docx, styles, at, 3) { d, pg -> OfficePages.layout(d, styles, pg) { null } }
        }.onFailure { WriterLog.e("PDF: contents", it) }
        doc
    }

    /**
     * [lines] as paragraphs given to [add]: one, a heading by size or by a bookmark's title; or, where lines start with a
     * bullet (• ◦ ▪ – - *) or a number (1. 2) a. α.), a list item each, its level from how far it is in from [left].
     */
    private fun paragraph(lines: List<List<PdfSeg>>, add: (DocPara) -> Unit, body: Float, headingSizes: List<Float>, marks: Map<String, Int>, left: Float,
        run: (PdfSeg, String, Int) -> DocRun) {
        val bullet = LIST_BULLET; val number = LIST_NUMBER
        fun marker(l: List<PdfSeg>) = l.firstOrNull()?.text?.trimStart()?.let { t -> bullet.find(t)?.let { false to it.value } ?: number.find(t)?.let { true to it.value } }
        // the lines in groups: a new one at each line that starts with a marker
        val groups = ArrayList<MutableList<List<PdfSeg>>>()
        for (l in lines) { if (l.isEmpty()) continue; if (groups.isEmpty() || marker(l) != null) groups += mutableListOf(l) else groups.last() += l }
        for (g in groups) {
            val segs = g.flatten()
            val text = StringBuilder()
            for (l in g) { val w = l.joinToString(" ") { it.text }.trim(); if (w.isEmpty()) continue
                if (text.isNotEmpty()) { if (text.endsWith("-") && w.first().isLowerCase()) text.setLength(text.length - 1) else text.append(' ') }
                text.append(w) }
            if (text.isBlank()) continue
            // a number in a heading's bigger letters ("1. Introduction") is the heading's, not a list's
            val m = marker(g.first())?.takeIf { mk -> !mk.first || g.flatten().maxOf { it.size } < body * 1.15f }
            val words = if (m != null) text.toString().trimStart().removePrefix(m.second).trim() else text.toString()
            val size = segs.maxOf { it.size }
            val heading = if (m != null) 0 else marks[MapsSearch.fold(words.trim())] ?: if (words.length > 160) 0
                else headingSizes.indexOfFirst { kotlin.math.abs(it - Math.round(size * 2) / 2f) < 0.6f }.let { if (it < 0) 0 else it + 1 }
            // the paragraph's look: its most common letters' (colour, size, bold) — one run, as the block is one paragraph
            val main = segs.groupBy { Triple(it.color, Math.round(it.size * 2), it.bold) }.maxBy { (_, ss) -> ss.sumOf { it.text.length } }.value.first()
            val list = m?.let { DocList(ordered = it.first, level = ((g.first().first().x0 - left) / 18f).toInt().coerceIn(0, 4)) }
            add(DocPara(listOf(run(main, words, heading)), heading = heading, list = list))
        }
    }

    /** Page [i] drawn for reading: about 300 dpi, its long edge at most 3300 px (a phone's memory). */
    fun scanOf(i: Int): Pair<Bitmap, Float> = synchronized(this) { page(i) { p ->
        val s = sizes[i]; val k = minOf(300f / 72f, 3300f / maxOf(s.width, s.height))
        com.artifex.mupdf.fitz.android.AndroidDrawDevice.drawPage(p, com.artifex.mupdf.fitz.Matrix.Scale(k)) to k
    } }

    /** [words] (boxes in the pixels of a drawing [k] times the page's points) put on page [i] as invisible text, one undo step. */
    fun addTextLayer(i: Int, words: List<OcrWord>, k: Float) = edit("Recognize text, page ${i + 1}") { pdf ->
        if (words.isEmpty()) return@edit
        val font = com.artifex.mupdf.fitz.Font("Helvetica")
        val ref = ocrFont ?: pdf.addSimpleFont(font, com.artifex.mupdf.fitz.Font.SIMPLE_ENCODING_GREEK).also { ocrFont = it }
        val obj = pdf.findPage(i)
        val res = obj.get("Resources").takeIf { !it.isNull } ?: pdf.newDictionary().also { r ->
            obj.getInheritable("Resources").takeIf { !it.isNull }?.let { inh -> for (key in listOf("Font", "XObject", "ExtGState", "ColorSpace", "Pattern", "Shading", "ProcSet")) inh.get(key).takeIf { !it.isNull }?.let { r.put(key, it) } }
            obj.put("Resources", r) }
        val fonts = res.get("Font").takeIf { !it.isNull } ?: pdf.newDictionary().also { res.put("Font", it) }
        fonts.put("TetraOCR", ref)
        // from the drawing's top-left pixels to the PDF's own space (its origin bottom left, any turn the page has)
        val toPdf = page(i) { p -> val o = p.bounds; com.artifex.mupdf.fitz.Matrix.Inverted((p as com.artifex.mupdf.fitz.PDFPage).transform).let { inv ->
            com.artifex.mupdf.fitz.Matrix(com.artifex.mupdf.fitz.Matrix.Scale(1f / k), com.artifex.mupdf.fitz.Matrix(com.artifex.mupdf.fitz.Matrix.Translate(o.x0, o.y0), inv)) } }
        val sb = StringBuilder("q BT 3 Tr\n")
        for (w in words) {
            val bytes = w.text.toByteArray(greek)
            val adv = w.text.sumOf { c -> font.advanceGlyph(font.encodeCharacter(c.code)).toDouble() }.toFloat().coerceAtLeast(0.1f)
            val bl = com.artifex.mupdf.fitz.Point(w.box.left, w.box.bottom).transform(toPdf)
            val tl = com.artifex.mupdf.fitz.Point(w.box.left, w.box.top).transform(toPdf)
            val br = com.artifex.mupdf.fitz.Point(w.box.right, w.box.bottom).transform(toPdf)
            val hx = tl.x - bl.x; val hy = tl.y - bl.y; val wx = br.x - bl.x; val wy = br.y - bl.y
            // the word's own matrix: along its width, up its height, the font's em that tall and stretched to the box
            sb.append("/TetraOCR 1 Tf ").append(f(wx / adv)).append(' ').append(f(wy / adv)).append(' ').append(f(hx)).append(' ').append(f(hy)).append(' ')
                .append(f(bl.x)).append(' ').append(f(bl.y)).append(" Tm <").append(bytes.joinToString("") { "%02x".format(it) }).append("> Tj\n")
        }
        sb.append("ET Q\n")
        val before = pdf.addStream("q\n"); val after = pdf.addStream("Q\n" + sb)
        val old = obj.get("Contents")
        val list = pdf.newArray(); list.push(before)
        if (old.isArray) for (j in 0 until old.size()) list.push(old.get(j)) else if (!old.isNull) list.push(old)
        list.push(after)
        obj.put("Contents", list)
    }
    private fun f(v: Float) = "%.3f".format(java.util.Locale.ROOT, v)

    // —— Find and redact: words or patterns marked everywhere, then taken out of the file ——

    /** Every place [query] is (or, a pattern, every match of [regex] in a page's text) marked for redaction: how many. */
    fun markAll(query: String?, regex: Regex?): Int {
        var n = 0
        edit("Mark for redaction") { _ ->
            sizes.indices.forEach { i -> page(i) { p ->
                val pp = p as com.artifex.mupdf.fitz.PDFPage
                val hits: List<Array<com.artifex.mupdf.fitz.Quad>> = if (query != null) p.search(query).orEmpty().toList()
                else {
                    val t = p.toStructuredText()
                    try {
                        val text = t.asText()
                        // each match looked for as words on the page (StructuredText.search crashes MuPDF 1.28.5's JNI; Page.search is Find's own)
                        regex!!.findAll(text).map { it.value.trim() }.distinct().flatMap { v -> p.search(v).orEmpty().toList() }.toList()
                    } finally { t.destroy() }
                }
                hits.forEach { quads -> pp.createAnnotation(com.artifex.mupdf.fitz.PDFAnnotation.TYPE_REDACT).apply { setQuadPoints(quads); update() }; n++ }
                pp.update()
            } }
        }
        return n
    }

    // —— the Comments pane and Bookmarks ——

    private val kinds = mapOf(com.artifex.mupdf.fitz.PDFAnnotation.TYPE_HIGHLIGHT to R.string.words_highlight, com.artifex.mupdf.fitz.PDFAnnotation.TYPE_UNDERLINE to R.string.words_underline,
        com.artifex.mupdf.fitz.PDFAnnotation.TYPE_STRIKE_OUT to R.string.words_strikethrough, com.artifex.mupdf.fitz.PDFAnnotation.TYPE_SQUIGGLY to R.string.pdf_kind_squiggly,
        com.artifex.mupdf.fitz.PDFAnnotation.TYPE_TEXT to R.string.pdf_note, com.artifex.mupdf.fitz.PDFAnnotation.TYPE_FREE_TEXT to R.string.pdf_text,
        com.artifex.mupdf.fitz.PDFAnnotation.TYPE_INK to R.string.pdf_kind_drawing, com.artifex.mupdf.fitz.PDFAnnotation.TYPE_SQUARE to R.string.pdf_rectangle,
        com.artifex.mupdf.fitz.PDFAnnotation.TYPE_CIRCLE to R.string.pdf_oval, com.artifex.mupdf.fitz.PDFAnnotation.TYPE_LINE to R.string.words_line,
        com.artifex.mupdf.fitz.PDFAnnotation.TYPE_STAMP to R.string.pdf_stamp, com.artifex.mupdf.fitz.PDFAnnotation.TYPE_REDACT to R.string.pdf_kind_to_redact,
        com.artifex.mupdf.fitz.PDFAnnotation.TYPE_POLYGON to R.string.pdf_kind_polygon, com.artifex.mupdf.fitz.PDFAnnotation.TYPE_CARET to R.string.pdf_kind_caret)

    /** Every comment, page by page (not form fields, links or popups); a highlight's words are the ones it covers. */
    fun comments(): List<PdfComment> = synchronized(this) {
        sizes.indices.flatMap { i -> page(i) { p ->
            val o = p.bounds
            val all = (p as? com.artifex.mupdf.fitz.PDFPage)?.annotations.orEmpty()
            val byNumber = all.withIndex().associate { (k, a) -> runCatching { a.`object`.asIndirect() }.getOrDefault(-1) to k }
            // Review states (Resolved, as Acrobat marks it): replies of their own, not comments; the last one says
            fun state(a: com.artifex.mupdf.fitz.PDFAnnotation) = runCatching { a.`object`.get("StateModel").takeIf { !it.isNull }?.let { a.`object`.get("State").asName() } }.getOrNull()
            val resolved = HashMap<Int, Boolean>()
            all.forEach { a -> state(a)?.let { st -> runCatching { a.`object`.get("IRT").asIndirect() }.getOrNull()?.let { byNumber[it] }?.let { resolved[it] = st == "Completed" || st == "Accepted" } } }
            all.withIndex().mapNotNull { (k, a) ->
                val kind = kinds[a.type] ?: return@mapNotNull null
                if (state(a) != null) return@mapNotNull null
                // Tetra's own texts and Words objects are things on the page, not comments
                if (!a.`object`.get(TYPED_KEY).isNull || !a.`object`.get(WORDS_KEY).isNull) return@mapNotNull null
                val parent = runCatching { a.`object`.get("IRT").takeIf { !it.isNull }?.asIndirect()?.let { byNumber[it] } }.getOrNull()
                var words = a.contents.orEmpty()
                if (words.isBlank() && a.type in listOf(com.artifex.mupdf.fitz.PDFAnnotation.TYPE_HIGHLIGHT, com.artifex.mupdf.fitz.PDFAnnotation.TYPE_UNDERLINE,
                        com.artifex.mupdf.fitz.PDFAnnotation.TYPE_STRIKE_OUT)) runCatching {
                    val r = a.bounds; val t = p.toStructuredText()
                    words = t.copy(com.artifex.mupdf.fitz.Point(r.x0, (r.y0 + r.y1) / 2), com.artifex.mupdf.fitz.Point(r.x1, (r.y0 + r.y1) / 2)).orEmpty().trim(); t.destroy()
                }
                val r = a.bounds
                PdfComment(i, k, if (parent != null) R.string.words_reply else kind, words.replace('\n', ' '), Offset((r.x0 + r.x1) / 2 - o.x0, (r.y0 + r.y1) / 2 - o.y0),
                    parent, runCatching { a.author.orEmpty() }.getOrDefault(""),
                    runCatching { listOf(a.modificationDate, a.creationDate).firstOrNull { it != null && it.time > 0 }?.toInstant()?.toString() }.getOrNull(), resolved[k] == true)
            }
        } }
    }

    /** A reply to [c]: a note in the same place that answers it (PDF's In Reply To), as other readers thread them. */
    fun reply(c: PdfComment, words: String, author: String = "") = annotate(c.page, "Reply") { p, x0, y0 ->
        val parent = p.annotations.orEmpty().getOrNull(c.index) ?: return@annotate false
        p.createAnnotation(com.artifex.mupdf.fitz.PDFAnnotation.TYPE_TEXT).apply {
            setRect(com.artifex.mupdf.fitz.Rect(c.at.x + x0, c.at.y + y0, c.at.x + x0 + 20, c.at.y + y0 + 20)); setContents(words)
            if (author.isNotBlank()) runCatching { setAuthor(author) }; runCatching { setModificationDate(java.util.Date()) }
            `object`.put("IRT", parent.`object`); `object`.put("RT", pdf!!.newName("R")); update()
        }; true
    }
    /** [c] resolved or reopened (Words' Resolve): a Review state reply to it, hidden, as Acrobat marks a comment Completed. */
    fun resolve(c: PdfComment, done: Boolean) = annotate(c.page, if (done) "Resolve" else "Reopen") { p, x0, y0 ->
        val parent = p.annotations.orEmpty().getOrNull(c.index) ?: return@annotate false
        p.createAnnotation(com.artifex.mupdf.fitz.PDFAnnotation.TYPE_TEXT).apply {
            setRect(com.artifex.mupdf.fitz.Rect(c.at.x + x0, c.at.y + y0, c.at.x + x0 + 20, c.at.y + y0 + 20)); setContents("")
            `object`.put("IRT", parent.`object`); `object`.put("StateModel", "Review"); `object`.put("State", pdf!!.newName(if (done) "Completed" else "None"))
            setFlags(com.artifex.mupdf.fitz.PDFAnnotation.IS_HIDDEN or com.artifex.mupdf.fitz.PDFAnnotation.IS_NO_VIEW); update()
        }; true
    }
    /** [c]'s words changed (Words' Edit on a comment). */
    fun editComment(c: PdfComment, words: String) = annotate(c.page, "Edit comment") { p, _, _ ->
        p.annotations.orEmpty().getOrNull(c.index)?.let { it.setContents(words); runCatching { it.setModificationDate(java.util.Date()) }; it.update(); true } ?: false
    }

    // —— Forms: fields made (a text field, a check box), as other apps fill them ——

    private var fieldCount = 0
    /**
     * A [checkbox] or a text field from [a] to [b] on page [i], named Field n: MuPDF's own widget (so the page and the form's
     * list refer to one object; built by hand, MuPDF 1.28's Java copied it into each), its field properties set on it.
     */
    fun addField(i: Int, a: Offset, b: Offset, checkbox: Boolean) = annotate(i, if (checkbox) "Check box" else "Text field") { p, x0, y0 ->
        val w = p.createAnnotation(com.artifex.mupdf.fitz.PDFAnnotation.TYPE_WIDGET)
        w.setRect(com.artifex.mupdf.fitz.Rect(minOf(a.x, b.x) + x0, minOf(a.y, b.y) + y0, maxOf(a.x, b.x) + x0, maxOf(a.y, b.y) + y0))
        val o = w.`object`
        val taken = runCatching { pdf!!.trailer.get("Root").get("AcroForm").get("Fields").let { f -> (0 until f.size()).map { f.get(it).get("T").asString() } } }.getOrDefault(emptyList()).toSet()
        val name = generateSequence(fieldCount + 1) { it + 1 }.map { "Field $it" }.first { it !in taken }; fieldCount++
        o.put("T", name); o.put("F", 4)
        if (checkbox) { o.put("FT", pdf!!.newName("Btn")); o.put("V", pdf.newName("Off")); o.put("AS", pdf.newName("Off")); o.put("DA", "/ZaDb 0 Tf 0 g") }
        else { o.put("FT", pdf!!.newName("Tx")); o.put("DA", "/Helv 11 Tf 0 g") }
        // in the form's list (a form made if the file had none)
        val root = pdf.trailer.get("Root")
        if (root.get("AcroForm").isNull) root.put("AcroForm", pdf.newDictionary())
        val acro = root.get("AcroForm")
        if (acro.get("Fields").isNull) acro.put("Fields", pdf.newArray())
        if (acro.get("DA").isNull) acro.put("DA", "/Helv 11 Tf 0 g")
        acro.get("Fields").push(o)
        w.update(); true
    }

    /** The comment [c] deleted (the Comments pane's bin). */
    fun deleteComment(c: PdfComment) = annotate(c.page, "Delete comment") { p, _, _ ->
        p.annotations.orEmpty().getOrNull(c.index)?.let { p.deleteAnnotation(it); true } ?: false
    }

    /** The file's bookmarks, nested ones a step in. */
    fun bookmarks(): List<PdfMark> = synchronized(this) {
        val out = ArrayList<PdfMark>()
        fun walk(items: Array<com.artifex.mupdf.fitz.Outline>?, depth: Int) {
            items.orEmpty().forEach { o ->
                val pg = runCatching { doc.pageNumberFromLocation(doc.resolveLink(o)) }.getOrDefault(-1)
                out += PdfMark(o.title.orEmpty(), pg, depth); walk(o.down, depth + 1)
            }
        }
        runCatching { walk(doc.loadOutline(), 0) }
        out
    }

    /** A bookmark called [title] to page [i], after the last one at the top level. */
    fun addBookmark(title: String, i: Int) = edit("Bookmark") { _ ->
        val it0 = doc.outlineIterator()
        try {
            while (it0.next() == com.artifex.mupdf.fitz.OutlineIterator.ITERATOR_AT_ITEM) Unit
            it0.insert(title, doc.formatLinkURI(com.artifex.mupdf.fitz.LinkDestination.Fit(0, i)), false)
        } finally { it0.destroy() }
    }

    /** The [n]th bookmark (in [bookmarks]' order) deleted, with any under it. */
    fun deleteBookmark(n: Int) = edit("Delete bookmark") { _ ->
        val it0 = doc.outlineIterator()
        try {
            var k = 0
            fun visit(): Boolean {
                do {
                    if (it0.item() == null) return false
                    if (k == n) { it0.delete(); return true }
                    k++
                    if (it0.down() == com.artifex.mupdf.fitz.OutlineIterator.ITERATOR_AT_ITEM) { if (visit()) return true; it0.up() }
                } while (it0.next() == com.artifex.mupdf.fitz.OutlineIterator.ITERATOR_AT_ITEM)
                return false
            }
            visit()
        } finally { it0.destroy() }
    }

    /** Reduce size: the next save rewrites the file whole — what nothing uses dropped, copies of the same thing made one, streams and fonts compressed. */
    fun shrink() { rewrite = rewrite ?: "garbage=deduplicate,compress,compress-fonts,compress-images" }

    // —— Insert: on every page ——

    /** A picture on page [i], [box] in page points (from its top left); [opacity] 1 solid. */
    private fun stamp(p: com.artifex.mupdf.fitz.PDFPage, x0: Float, y0: Float, png: ByteArray, box: android.graphics.RectF, opacity: Float) =
        p.createAnnotation(com.artifex.mupdf.fitz.PDFAnnotation.TYPE_STAMP).apply {
            setRect(com.artifex.mupdf.fitz.Rect(box.left + x0, box.top + y0, box.right + x0, box.bottom + y0))
            setAppearance(com.artifex.mupdf.fitz.Image(png)); if (opacity < 1f) setOpacity(opacity); update()
        }



    /** Words at the top and/or the bottom of every page ([footer] "{n}" and "{N}" for the page number and count), [size] points. */
    fun headerFooter(header: String, footer: String, size: Float) = edit("Header and footer") { _ ->
        sizes.indices.forEach { i -> page(i) { p -> val o = p.bounds; val s = sizes[i]; val pp = p as com.artifex.mupdf.fitz.PDFPage
            fun put(words: String, top: Float) {
                if (words.isBlank()) return
                val t = words.replace("{n}", "${i + 1}").replace("{N}", "${sizes.size}").replace('—', '-').replace('–', '-')
                val w = t.length * size * 0.6f + size
                pp.createAnnotation(com.artifex.mupdf.fitz.PDFAnnotation.TYPE_FREE_TEXT).apply {
                    setRect(com.artifex.mupdf.fitz.Rect(o.x0 + (s.width - w) / 2, o.y0 + top, o.x0 + (s.width + w) / 2, o.y0 + top + size * 1.6f))
                    setDefaultAppearance("Helv", size, floatArrayOf(0.25f, 0.25f, 0.25f)); setContents(t); setBorderWidth(0f); update()
                }
            }
            put(header, 18f); put(footer, s.height - 18f - size * 1.6f); pp.update() } }
    }

    // —— Protect ——

    /** Changes that an appended save would leave readable in the file (redaction) or that change how it is read (a password). */
    private var rewrite: String? = null

    /** An area marked to be blacked out (Protect › Redact), from [a] to [b]: nothing is removed until [applyRedactions]. */
    fun markRedaction(i: Int, a: Offset, b: Offset) = annotate(i, "Mark for redaction") { p, x0, y0 ->
        p.createAnnotation(com.artifex.mupdf.fitz.PDFAnnotation.TYPE_REDACT).apply {
            setRect(com.artifex.mupdf.fitz.Rect(minOf(a.x, b.x) + x0, minOf(a.y, b.y) + y0, maxOf(a.x, b.x) + x0, maxOf(a.y, b.y) + y0)); update()
        }; true
    }

    /** Every marked area's text, pictures and drawings taken out of the file, black boxes in their place; how many pages had some. */
    fun applyRedactions(): Int {
        var n = 0
        edit("Redact") { _ -> sizes.indices.forEach { i -> page(i) { p -> if ((p as com.artifex.mupdf.fitz.PDFPage).applyRedactions(true, com.artifex.mupdf.fitz.PDFPage.REDACT_IMAGE_PIXELS)) n++ } } }
        if (n > 0) rewrite = rewrite ?: "garbage,compress"
        return n
    }
    val hasRedactionMarks get() = synchronized(this) { sizes.indices.any { i -> page(i) { p -> (p as? com.artifex.mupdf.fitz.PDFPage)?.annotations.orEmpty().any { it.type == com.artifex.mupdf.fitz.PDFAnnotation.TYPE_REDACT } } } }

    /** The next save asks for [password] to open the file (AES-256), or with null opens without one. */
    fun protect(password: String?) {
        rewrite = if (password == null) "garbage,compress,decrypt" else "garbage,compress,encrypt=aes-256,user-password=${password.replace(",", "")},owner-password=${password.replace(",", "")}"
    }
    val rewriting get() = rewrite != null
    /** The next save writes the file whole (what was taken out does not stay in its older parts). */
    internal fun rewriteWhole() { rewrite = rewrite ?: "garbage,compress" }

    // —— forms (their own fields: fill in, tick, choose) ——

    /** The file has form fields to fill in. */
    val hasForm: Boolean by lazy { synchronized(this) { runCatching { (pdf?.trailer?.get("Root")?.get("AcroForm")?.get("Fields")?.size() ?: 0) > 0 }.getOrDefault(false) } }

    private fun widget(p: com.artifex.mupdf.fitz.Page, x: Float, y: Float) = (p as? com.artifex.mupdf.fitz.PDFPage)?.widgets.orEmpty()
        .lastOrNull { w -> w.bounds.let { r -> x >= r.x0 && x <= r.x1 && y >= r.y0 && y <= r.y1 } }

    /** The field under [at]. */
    fun fieldAt(i: Int, at: Offset): PdfField? = synchronized(this) { page(i) { p ->
        val o = p.bounds
        widget(p, at.x + o.x0, at.y + o.y0)?.let { w ->
            PdfField(when { w.isText -> "text"; w.isCheckbox -> "check"; w.isRadioButton -> "radio"; w.isChoice -> "choice"; else -> "other" },
                w.label?.ifBlank { null } ?: w.name.orEmpty(), w.value.orEmpty(), w.options?.toList().orEmpty(), w.isMultiline, w.isReadOnly)
        }
    } }

    /** The field under [at] filled with [value] (text, a choice) or, a box or a round button, ticked or not. */
    fun fill(i: Int, at: Offset, value: String?) = annotate(i, "Fill in") { p, x0, y0 ->
        val w = widget(p, at.x + x0, at.y + y0) ?: return@annotate false
        when {
            w.isText -> w.setTextValue(value.orEmpty())
            w.isCheckbox || w.isRadioButton -> w.toggle()
            w.isChoice -> w.setChoiceValue(value.orEmpty())
            else -> return@annotate false
        }
        w.update(); true
    }

    /** A standard stamp ([name] one of [STAMPS]: Approved, Draft, …) centred on [at], drawn by MuPDF in the stamp's own look. */
    fun stampAt(i: Int, at: Offset, name: String) = annotate(i, "Stamp") { p, x0, y0 ->
        p.createAnnotation(com.artifex.mupdf.fitz.PDFAnnotation.TYPE_STAMP).apply {
            setIcon(name); setRect(com.artifex.mupdf.fitz.Rect(at.x + x0 - 75, at.y + y0 - 22, at.x + x0 + 75, at.y + y0 + 22)); update()
        }; true
    }

    /** A text box from [a] to [b]: [words] inside a frame of [color], as a comment others' readers show and let you edit. */
    /**
     * Words with their look ([PdfText]) on page [i], their top left at [x], [y] (page points), [room] across (a text box:
     * its width; a text: as far as its longest line), at least [minHeight] tall: a text annotation whose look is the
     * words laid out by Android with their font embedded (MuPDF's own text boxes know only its few fonts), its words kept
     * in it for other readers and the look in Tetra's own key. [replacing]: the one it was ([PdfObject.id]), taken out in
     * the same step. Its id (0: emptied, only taken out).
     */
    fun putText(i: Int, x: Float, y: Float, t: PdfText, room: Float, minHeight: Float = 0f, replacing: Int? = null): Int { var id = 0; annotate(i, "Text") { p, x0, y0 ->
        replacing?.let { r -> p.annotations.orEmpty().firstOrNull { it.id() == r }?.let(p::deleteAnnotation) }
        if (t.words.isBlank()) return@annotate true // emptied: only taken out
        val (lw, lh) = PdfText.size(t, room, minHeight)
        val (w, h) = PdfText.bounds(t, lw, lh) // its place on the page, turned
        val (res, contents) = grafted(PdfText.pdf(t, lw, lh))
        p.createAnnotation(com.artifex.mupdf.fitz.PDFAnnotation.TYPE_FREE_TEXT).apply {
            setRect(com.artifex.mupdf.fitz.Rect(x + x0, y + y0, x + x0 + w, y + y0 + h))
            setDefaultAppearance("Helv", t.look.size, rgb(t.look.color)); setContents(t.words); setBorderWidth(0f)
            setAppearance(upright(p), com.artifex.mupdf.fitz.Rect(0f, 0f, w, h), res, com.artifex.mupdf.fitz.Buffer().apply { writeBytes(contents) })
            getObject().put(TYPED_KEY, pdf!!.newString(t.json(lw to lh)))
            getObject().put(WORDS_KEY, pdfDoc.addStream(com.artifex.mupdf.fitz.Buffer().apply { writeBytes(t.docx()) }, pdfDoc.newDictionary()))
            id = id()
        }
        true
    }; return id }
    /** A one-page PDF's drawing ([onePage], Android's) taken into this one: its resources and its page's contents, to be an annotation's look. */
    internal fun grafted(onePage: ByteArray): Pair<com.artifex.mupdf.fitz.PDFObject, ByteArray> {
        val src = com.artifex.mupdf.fitz.Document.openDocument(onePage, "application/pdf")
        try {
            val sp = (src.asPDF() ?: error("No PDF")).findPage(0)
            val c = sp.get("Contents")
            val contents = if (c.isArray) (0 until c.size()).fold(ByteArray(0)) { all, k -> all + c.get(k).readStream() + '\n'.code.toByte() } else c.readStream()
            return pdfDoc.graftObject(sp.get("Resources")) to contents
        } finally { src.destroy() }
    }
    /** The comment under [at] (the topmost, a few points round it), deleted: false when there is none. */
    fun deleteAt(i: Int, at: Offset) = annotate(i, "Delete comment") { p, x0, y0 ->
        val x = at.x + x0; val y = at.y + y0; val slop = 6f
        p.annotations.orEmpty().lastOrNull { a -> a.bounds.let { r -> x >= r.x0 - slop && x <= r.x1 + slop && y >= r.y0 - slop && y <= r.y1 + slop } }
            ?.let { p.deleteAnnotation(it); true } ?: false
    }

    /** The note under [at]: its words, to read (a note's icon only says one is there). */
    fun noteAt(i: Int, at: Offset): String? = synchronized(this) { page(i) { p ->
        val o = p.bounds; val x = at.x + o.x0; val y = at.y + o.y0
        (p as? com.artifex.mupdf.fitz.PDFPage)?.annotations.orEmpty().lastOrNull { a -> a.type == com.artifex.mupdf.fitz.PDFAnnotation.TYPE_TEXT &&
            a.bounds.let { r -> x >= r.x0 - 6 && x <= r.x1 + 6 && y >= r.y0 - 6 && y <= r.y1 + 6 } }?.contents
    } }

    /** The link on page [i] at [at] (the page's points): its address and, for a place in this file, the page it goes to. */
    fun linkAt(i: Int, at: Offset): Pair<String, Int?>? = synchronized(this) { page(i) { p ->
        val o = p.bounds; val x = at.x + o.x0; val y = at.y + o.y0
        p.links.orEmpty().lastOrNull { l -> l.bounds.let { r -> x >= r.x0 && x <= r.x1 && y >= r.y0 && y <= r.y1 } }?.let { l ->
            l.uri to (if (l.isExternal) null else runCatching { doc.pageNumberFromLocation(doc.resolveLink(l)) }.getOrNull()?.takeIf { it >= 0 })
        }
    } }
    /** A link over [r] (page points) on page [i] to [uri] — "#page=n" a page of this file — the links there before taken off; null: only taken off. */
    fun link(i: Int, r: android.graphics.RectF, uri: String?) = edit(if (uri == null) "Remove link" else "Link") { _ -> page(i) { p ->
        val o = p.bounds; val box = com.artifex.mupdf.fitz.Rect(r.left + o.x0, r.top + o.y0, r.right + o.x0, r.bottom + o.y0)
        // the ones whose middle is in it
        p.links.orEmpty().filter { l -> l.bounds.let { b -> box.contains((b.x0 + b.x1) / 2, (b.y0 + b.y1) / 2) } }.forEach(p::deleteLink)
        if (uri != null) p.createLink(box, uri)
    } }

    /** The file as this document read it (its length and time): what its one appended save goes onto. */
    private val openedAs = file.length() to file.lastModified()
    /** Saved once already: MuPDF appends only onto the file it opened, so every save after the first is written whole. */
    private var savedOnce = false

    /**
     * Saved over [file] (or into [to]), through a copy beside it renamed over it. The session's first save appends its
     * changes to the file as opened (an incremental save: all the file had stays, signatures too); every later one is
     * written whole, as is one after another app changed the file, or one MuPDF had to repair. (Before 27 Oct every save
     * appended: MuPDF chains an appended part to the file it opened, so the parts of the saves before were lost — a
     * second save's, AutoSave's, a second day's.)
     */
    fun save(to: File = file, copy: Boolean = false) = synchronized(this) {
        val p = pdf ?: throw PdfError(R.string.pdf_cannot_edit)
        val was = dirty
        val temp = File(to.parentFile, ".${to.name}.saving")
        // redacted or (un)locked: written whole, the old objects dropped (an appended save would keep what was taken out);
        // a copy leaves that still to do for the file itself
        val whole = rewrite
        val untouched = (file.length() to file.lastModified()) == openedAs
        val appendable = p.canBeSavedIncrementally() && !savedOnce && untouched
        if (whole != null) { p.save(temp.path, whole); if (!copy) rewrite = null }
        // nothing new and nothing written since it was opened: the file as it is (MuPDF appending nothing left a broken stub)
        else if (!p.hasUnsavedChanges() && untouched && !savedOnce) file.copyTo(temp, overwrite = true)
        else if (appendable) { file.copyTo(temp, overwrite = true); p.save(temp.path, "incremental,compress") } // new pictures compressed (a watermark was 2.3 MB raw)
        // what is unused dropped, the objects keep their numbers (compact renumbered them: what was chosen, a group's first
        // one, lost its object after every save but the first, 27 Oct)
        else p.save(temp.path, "garbage,compress")
        if (!temp.renameTo(to)) throw PdfError(R.string.pdf_save_failed_file)
        p.undoRedoPosition().let { at -> savedAt = at; redoStale = p.undoRedoSteps() > at }
        savedOnce = true
        copied = copy && was
        WriterLog.i("PDF: saved${if (copy) " a copy" else ""}${if (whole == null && !appendable) " whole" else ""} (${to.length() / 1024} KB)")
    }

    private inline fun <T> page(i: Int, use: (com.artifex.mupdf.fitz.Page) -> T): T { val p = doc.loadPage(i); try { return use(p) } finally { p.destroy() } }
    /** Page [i] loaded for [use] (PdfObjects.kt's); the caller holds the lock. */
    internal fun <T> withPage(i: Int, use: (com.artifex.mupdf.fitz.Page) -> T): T = page(i, use)
    /** The PDF being edited (PdfObjects.kt's; inside [annotate]). */
    internal val pdfDoc get() = pdf ?: throw PdfError(R.string.pdf_cannot_edit)

    // an eighth of the app's memory, at most 96 MB: a few screens of pages
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).coerceAtMost(96L shl 20).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(i: Int, w: Int, hide: Int? = null): Bitmap? = cache.get("$i/$w/$hide")

    /**
     * Page [i] drawn [w] px wide, its comments and form fields too ([hide]: but that object, being moved over it); null
     * when closed, scrolled away before its turn, or it could not be drawn (left white).
     */
    suspend fun page(i: Int, w: Int, hide: Int? = null): Bitmap? = cached(i, w, hide) ?: withContext(Dispatchers.IO) {
        synchronized(this@PdfDoc) {
            ensureActive() // a page flung past while it waited is not drawn
            if (closed) return@synchronized null
            cached(i, w, hide) ?: runCatching {
                val start = System.nanoTime()
                val b = page(i) { p ->
                    hide?.let { id -> (p as? com.artifex.mupdf.fitz.PDFPage)?.annotations.orEmpty().firstOrNull { it.id() == id }?.setHiddenForEditing(true) }
                    com.artifex.mupdf.fitz.android.AndroidDrawDevice.drawPageFitWidth(p, w) }
                val ms = (System.nanoTime() - start) / 1_000_000
                // the first page's time, then only slow ones (the log is not a list of every page scrolled by)
                if (!logged || ms >= 150) { logged = true; WriterLog.i("PDF: page ${i + 1} at $w px: $ms ms") }
                b.also { cache.put("$i/$w/$hide", it) }
            }.onFailure { WriterLog.w("PDF: page ${i + 1} could not be drawn", it) }.getOrNull()
        }
    }

    /** Page [i]'s words, as the file has them (none on a scan). */
    suspend fun text(i: Int): String = withContext(Dispatchers.IO) {
        synchronized(this@PdfDoc) { if (closed) "" else page(i) { p -> p.toDisplayList(true).let { dl -> try { dl.toStructuredText().let { t -> t.asText().also { t.destroy() } } } finally { dl.destroy() } } } }
    }

    /** Every place [query] is, page by page (the lock let go between pages, so the shown pages still draw). */
    suspend fun find(query: String): List<PdfMatch> = withContext(Dispatchers.IO) {
        sizes.indices.flatMap { i ->
            ensureActive()
            synchronized(this@PdfDoc) {
                if (closed) emptyList() else page(i) { p ->
                    val o = p.bounds
                    // the page as drawn, its comments and Tetra's texts too (a page's own search leaves annotations out)
                    p.toDisplayList(true).let { dl -> try { dl.search(query).orEmpty() } finally { dl.destroy() } }.map { hit -> PdfMatch(i, hit.map { q -> q.toRect().let { r -> RectF(r.x0 - o.x0, r.y0 - o.y0, r.x1 - o.x0, r.y1 - o.y0) } }) }
                }
            }
        }
    }

    /** Closed on a thread of its own: a page being drawn finishes first. */
    fun close() { thread { synchronized(this) { if (!closed) { closed = true; doc.destroy() } } } }
}

/**
 * Office's PDF viewer: the pages one under the other, a swipe going a page at a time as Slides' list, pinched to zoom
 * (and then moved with a finger; a double tap in and out), a tablet's page thumbnails at the side; Find and Copy where
 * Android reads a PDF's words; Share, Print, Open in another app. View only.
 */
@Composable
internal fun PdfScreen(back: () -> Unit, open: String, openWords: (String) -> Unit = {}, openInScanner: (PdfDoc) -> Unit = {}) {
    val context = LocalContext.current
    remember { OfficeFonts.assets = context.applicationContext.assets } // Words' fonts (Times' and Calibri's twins) for its texts, as Words' and Slides'
    val scope = rememberCoroutineScope()
    var file by remember(open) { mutableStateOf(Uri.parse(open).path?.let(::File)) } // File › Save as and Rename go on with another
    remember { WriterUnits.load(context) } // View's cm or inches, Words' own setting
    val unitName = stringResource(if (WriterUnits.inches) R.string.pdf_unit_in else R.string.words_unit_cm)
    val paperWay: (String, Boolean) -> String = { n, land -> context.getString(if (land) R.string.pdf_paper_landscape else R.string.pdf_paper_portrait, n) }
    RememberOpened(file) // the Office home's Recents
    var doc by remember { mutableStateOf<PdfDoc?>(null) }
    OpenTiming("PDF", doc != null)
    var failed by remember { mutableStateOf<Int?>(null) }
    var password by remember { mutableStateOf<String?>(null) } // given in the password dialog
    var askPassword by remember { mutableStateOf<Boolean?>(null) } // the dialog: true when the last one was wrong
    LaunchedEffect(open, password) {
        WriterLog.init(context)
        val f = file ?: run { failed = R.string.pdf_cannot_open; return@LaunchedEffect }
        WriterLog.i("PDF: open ${f.length() / 1024} KB")
        withContext(Dispatchers.IO) { runCatching { WriterLog.timed("PDF: read") { PdfDoc(f, password) } } }
            .onSuccess { doc = it; askPassword = null; WriterLog.i("PDF: ${it.sizes.size} pages") }
            .onFailure {
                if (it is PdfPasswordException) { askPassword = it.tried; return@onFailure }
                WriterLog.e("PDF: could not open", it)
                failed = R.string.pdf_unreadable
            }
    }
    askPassword?.let { wrong ->
        var typed by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(onDismissRequest = { askPassword = null; back() },
            title = { Text(stringResource(R.string.pdf_has_password)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (wrong) Text(stringResource(R.string.pdf_wrong_password), color = MaterialTheme.colorScheme.error)
                androidx.compose.material3.OutlinedTextField(typed, { typed = it }, label = { Text(stringResource(R.string.pdf_password)) }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { askPassword = null; password = typed }))
            } },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { askPassword = null; password = typed }) { Text(stringResource(R.string.common_open), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { askPassword = null; back() }) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) } })
    }
    DisposableEffect(doc) { val d = doc; onDispose { d?.close() } } // this one: reading doc when it goes would close the next one
    BackHandler { back() }

    // View, as Words': pages two across, as wide as the screen, rulers (a tablet), the page list at the side
    var across by remember { mutableIntStateOf(1) }
    var fitWidth by remember { mutableStateOf(false) }
    var rulers by remember { mutableStateOf(true) }
    var navigation by remember { mutableStateOf(true) }
    // Layout › Margins (Word's presets): where Position puts a text and a new one starts; the rulers show them
    var margins by remember { mutableStateOf(PdfMargins.load(context)) }
    val list = rememberLazyListState()
    // the page in the middle of the screen, where a swipe leaves it; at either end the first or last (short pages, as beside a
    // phone's page list, never reach the middle there)
    val at by remember { derivedStateOf { list.layoutInfo.let { l -> val mid = (l.viewportStartOffset + l.viewportEndOffset) / 2
        if (!list.canScrollBackward) 0 else if (!list.canScrollForward) (l.totalItemsCount - 1).coerceAtLeast(0)
        else l.visibleItemsInfo.minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - mid) }?.index ?: 0 }
        .let { row -> (row * across).coerceAtMost(((doc?.sizes?.size ?: 1) - 1).coerceAtLeast(0)) } } } // two across: a row's first page
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    // the zoom a pinch ended at: the page in the middle drawn again that sharp
    var settled by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(zoom) { delay(250); settled = zoom }
    fun goTo(page: Int) { zoom = 1f; pan = Offset.Zero; scope.launch { list.scrollToItem(page / across) } }

    var finding by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<PdfMatch>?>(null) }
    var match by remember { mutableStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var replacingWords by remember { mutableStateOf(false) } // Find and Replace's second row
    var replacement by remember { mutableStateOf("") }
    fun find() {
        val d = doc ?: return
        if (query.isBlank() || !Pdf.hasText) return
        searching = true
        scope.launch {
            val start = System.nanoTime()
            val found = runCatching { d.find(query.trim()) }.onFailure { WriterLog.e("PDF: find", it) }.getOrDefault(emptyList())
            WriterLog.i("PDF: find, ${found.size} found in ${(System.nanoTime() - start) / 1_000_000} ms") // never what was looked for
            matches = found; match = 0; searching = false
            found.firstOrNull()?.let { goTo(it.page) }
        }
    }
    fun step(by: Int) { val m = matches ?: return; if (m.isEmpty()) return; match = (match + by + m.size) % m.size; goTo(m[match].page) }
    fun copyPage() {
        val d = doc ?: return
        if (!Pdf.hasText) return
        val page = at
        scope.launch {
            val text = runCatching { d.text(page) }.onFailure { WriterLog.e("PDF: copy", it) }.getOrDefault("")
            WriterLog.i("PDF: page ${page + 1}'s text copied (${text.length} letters)")
            if (text.isBlank()) Toast.makeText(context, context.getString(R.string.pdf_page_no_text, page + 1), Toast.LENGTH_LONG).show()
            else context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.pdf_page_n, page + 1), text))
        }
    }
    /**
     * The PDF's words as a Words document beside it, opened there (the user, 22 Oct: "export in to image is stupid … make it in
     * to words"): its paragraphs, headings told by their size, bold kept; a scan's after Recognize text.
     */
    fun exportWords() {
        val d = doc ?: return; val f = file ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching {
                val words = d.toWords()
                if (words.paras.none { it.text.isNotBlank() || it.table != null }) throw PdfError(R.string.pdf_no_text_scan)
                val to = run { var n = 0; var t: File; do { n++; t = File(f.parentFile, "${f.nameWithoutExtension}${if (n > 1) " $n" else ""}.docx") } while (t.exists()); t }
                to.writeBytes(OfficeFormats.write(words, OfficeKind.Docx)); to
            } }
            r.onSuccess { to -> WriterLog.i("PDF: to Words, ${to.length() / 1024} KB"); Toast.makeText(context, context.getString(R.string.words_saved_as, to.name), Toast.LENGTH_SHORT).show(); openWords(Uri.fromFile(to).toString()) }
                .onFailure { WriterLog.e("PDF: to Words", it); Toast.makeText(context, pdfSays(context, it, R.string.pdf_doc_not_made), Toast.LENGTH_LONG).show() }
        }
    }
    /** All the file's words (a scan's after Recognize text) in a .txt beside it. */
    fun exportText() {
        val d = doc ?: return; val f = file ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching {
                val text = d.sizes.indices.joinToString("\n\n") { i -> kotlinx.coroutines.runBlocking { d.text(i) }.trim() }
                val to = generateSequence(1) { it }.let { _ -> var n = 0; var t: File; do { n++; t = File(f.parentFile, "${f.nameWithoutExtension}${if (n > 1) " $n" else ""}.txt") } while (t.exists()); t }
                to.writeText(text); to to text.isBlank()
            } }
            r.onSuccess { (to, empty) -> Toast.makeText(context, if (empty) context.getString(R.string.pdf_no_text_scan) else context.getString(R.string.words_saved_as, to.name), Toast.LENGTH_LONG).show() }
                .onFailure { WriterLog.e("PDF: export text", it); Toast.makeText(context, pdfSays(context, it, R.string.pdf_export_failed), Toast.LENGTH_LONG).show() }
        }
    }
    fun print() {
        val f = file ?: return
        runCatching { context.getSystemService(android.print.PrintManager::class.java)?.print(f.nameWithoutExtension, PdfPrintAdapter(f, f.nameWithoutExtension), null) }
            .onSuccess { WriterLog.i("PDF: print") }.onFailure { WriterLog.e("PDF: print", it) }
    }

    // —— editing (Plan.md § PDF): opened view only, Edit as Words' ——
    var editing by remember { mutableStateOf(JustMade.take(open)) } // a file just made with New: editing at once
    var tab by remember { mutableStateOf("Home") }
    val media = remember { PdfMediaState() } // a film or sound chosen (its Video tab) and the one playing
    var tool by remember { mutableStateOf<PdfTool?>(null) }
    var markColour by remember { mutableStateOf(0xFFEB3B) } // Review's colour (highlight, shapes, notes)
    var penColour by remember { mutableStateOf(0xE53935) } // Draw's
    var penWidth by remember { mutableStateOf(2f) }
    var writing by remember { mutableStateOf<Pair<Int, Offset>?>(null) } // a note being written: its page and place
    var reading by remember { mutableStateOf<String?>(null) } // a note tapped: its words
    var typing by remember { mutableStateOf<PdfTyping?>(null) } // Fill & Sign › Text or Review › Text box being typed on the page
    val typed = remember { com.mohamedrejeb.richeditor.model.RichTextState() } // the words typed, in Words' editor
    var typingText by remember { mutableStateOf<PdfText?>(null) } // the text being typed: its paragraphs as they were, its styles
    var textLook by remember { mutableStateOf(PdfTextLook()) } // the last look used: the next text starts with it
    var fontBefore by remember { mutableStateOf<String?>(null) } // Font's strip open: the font before it (Cancel puts it back)
    var chosen by remember { mutableStateOf<PdfObject?>(null) } // what is chosen on a page, as Words' picture, shape or text box
    val framePreview = remember { PdfFramePreview() } // where a finger or an arrow has the chosen object, until put on
    var clip by remember { mutableStateOf<PdfObject?>(null) } // Copy's object, for Paste
    var copiedLook by remember { mutableStateOf<PdfTextLook?>(null) } // Selection › Copy formatting, for Paste formatting (Slides')
    var tabBefore by remember { mutableStateOf("Home") } // the tab shown before an object was chosen, back when none is
    var sizing by remember { mutableStateOf(false) } // Picture › Size
    var rotating by remember { mutableStateOf(false) } // Picture › Rotate
    var positioning by remember { mutableStateOf(false) } // Picture › Position
    var shapeOptions by remember { mutableStateOf(false) } // Picture › Shape options
    var redrawing by remember { mutableStateOf(false) } // a signature's Redraw
    var replacing by remember { mutableStateOf(false) } // Picture › Replace: the next picture picked takes the chosen one's place
    var ribbonOverKeyboard by remember { mutableStateOf(false) } // a phone typing: Words' whole ribbon over the keyboard, not its bar
    // the keyboard bar's dictation: what was said where the cursor is, a space between it and the words beside it (Words')
    val dictate = rememberDictation { said -> val t = typed.annotatedString.text
        val before = t.getOrNull(typed.selection.min - 1); val after = t.getOrNull(typed.selection.max)
        typed.addTextAfterSelection((if (before != null && !before.isWhitespace()) " " else "") + said + (if (after != null && after.isLetterOrDigit()) " " else "")) }
    // Review › Spelling (Words', shared): the words being typed checked as Words checks them, Words' own setting
    val speller = remember { WriterSpelling(context) }
    val writerPrefs = remember { context.getSharedPreferences("writer", android.content.Context.MODE_PRIVATE) }
    var spellingOn by remember { mutableStateOf(writerPrefs.getBoolean("spelling", true)) }
    val spell = rememberSpelling(typed, speller, spellingOn, typing != null)
    var inserting by remember { mutableStateOf<String?>(null) } // Insert's, Design's and Layout's windows: shapes, chart, equation, symbol, date, page, border, boxText
    var drawingOn by remember { mutableStateOf<Pair<Int, Bitmap?>?>(null) } // Draw › Draw on page: the page, its picture under the pen
    var drawingBox by remember { mutableStateOf(false) } // Draw › Drawing box
    var editingInk by remember { mutableStateOf(false) } // Picture › Edit drawing
    var cropping by remember { mutableStateOf<Bitmap?>(null) } // Picture › Crop: the picture as it shows, in Words' crop window
    var linking by remember { mutableStateOf<Pair<String, Int?>?>(null) } // Insert › Link for the chosen one: its link now ("" none)
    var linkHere by remember { mutableStateOf<Pair<String, Int?>?>(null) } // a link tapped: Words' "Go to" over the page
    var paragraphPage by remember { mutableStateOf<Pair<Set<Int>, ParaLook>?>(null) } // Home › Line spacing, More…: Words' Paragraph window
    // an object chosen: its own tab shown, named by its kind (Words' Image, Shape, Text box…); none chosen: the tab before
    LaunchedEffect(chosen != null) {
        if (chosen != null) { media.chosen = null; if (tab != "Selection" && tab != OBJECT_TAB) { tabBefore = tab; tab = OBJECT_TAB } }
        else if (tab == "Selection" || tab == OBJECT_TAB) tab = tabBefore
    }
    // a film or sound chosen: its Video (or Audio) tab, as Words' when one is tapped
    LaunchedEffect(media.chosen != null) {
        if (media.chosen != null) { chosen = null; if (tab != "Video") { tabBefore = tab; tab = "Video" } }
        else if (tab == "Video") tab = tabBefore
    }
    LaunchedEffect(editing) { media.chosen = null }
    var pageMenu by remember { mutableStateOf<Triple<Int, Offset, Offset>?>(null) } // a right-click's menu: its page, where (px on the page), where (page points)
    var filling by remember { mutableStateOf<Triple<Int, Offset, PdfField>?>(null) } // a form's text or choice field tapped
    var fillSize by remember { mutableStateOf(11f) }
    val signatureFile = remember { File(context.filesDir, "pdf-signature.png") }
    var signing by remember { mutableStateOf(false) } // drawing the signature
    var asking by remember { mutableStateOf<String?>(null) } // Insert's and Protect's windows: "header", "watermark", "password", "bookmark"
    var stampName by remember { mutableStateOf("Approved") }
    var stampPicture by remember { mutableStateOf<Pair<ByteArray, Float>?>(null) } // your own stamp: a picture and its height / width
    val pickStamp = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val bmp = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(context.contentResolver, uri)) { dec, info, _ ->
                val k = minOf(1f, 800f / maxOf(info.size.width, info.size.height)); dec.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1)) }
            java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() to bmp.height.toFloat() / bmp.width
        }.onSuccess { stampPicture = it; stampName = PICTURE_STAMP; tool = PdfTool.Stamp; Toast.makeText(context, R.string.pdf_tap_stamp, Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(context, R.string.pdf_not_a_picture, Toast.LENGTH_SHORT).show() }
    }
    var reading2 by remember { mutableStateOf<Pair<Int, Int>?>(null) } // Recognize text running: the page, of how many
    var ocrJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var pane by remember { mutableStateOf<String?>(null) } // "Comments" or "Bookmarks", beside the pages (a phone: over their lower half)
    var version by remember { mutableStateOf(0) } // after each change: the pages drawn again
    // each page's objects, read once a change (a finger's touch asks which is under it)
    val pageObjects = remember(version, doc) { HashMap<Int, List<PdfObject>>() }
    fun objectsOn(i: Int) = pageObjects.getOrPut(i) { doc?.objects(i).orEmpty() }
    /**
     * The object under [pt] on page [i] (4 pt round it), as the page is now: the page's touch handlers are made once, and
     * a list kept from then missed what was put on since (27 Oct: taps on a new text made another one on top of it).
     */
    fun objectUnder(i: Int, pt: Offset) = doc?.objects(i).orEmpty().lastOrNull { o -> o.rect.let { r -> pt.x >= r.left - 4 && pt.x <= r.right + 4 && pt.y >= r.top - 4 && pt.y <= r.bottom + 4 } }
    var organize by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) } // Back with changes not saved: Save or not
    var showVersions by remember { mutableStateOf(false) }
    // the old saves' copies of files as first opened (a save now goes onto the file itself): gone
    remember { runCatching { File(context.cacheDir, "pdf-base").deleteRecursively() } }
    fun change(what: () -> Unit) {
        runCatching(what).onFailure { WriterLog.e("PDF: change", it); Toast.makeText(context, pdfSays(context, it, R.string.pdf_could_not_do), Toast.LENGTH_LONG).show() }
        version++
    }
    /** Saved over the file, or into [to] (File › Save as, Save a copy: [copy] leaves the file itself unsaved). */
    fun save(to: File? = null, copy: Boolean = false, then: () -> Unit = {}) {
        val d = doc ?: return; val f = file ?: return; val t = to ?: f
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { if (t == f) keepPrevious(context, f); d.save(t, copy) } }
            r.onFailure { WriterLog.e("PDF: save", it); Toast.makeText(context, context.getString(R.string.pdf_could_not_save, pdfSays(context, it, R.string.pdf_could_not_do)), Toast.LENGTH_LONG).show() }
                .onSuccess { version++; chosen = chosen?.let { c -> d.objectById(c.page, c.id) } // Make smaller renumbers: what is gone let go
                    Toast.makeText(context, if (t != f) context.getString(R.string.pdf_saved_as_kb, t.name, t.length() / 1024) else context.getString(R.string.pdf_saved_kb, t.length() / 1024), Toast.LENGTH_SHORT).show(); then() }
        }
    }
    var autoSave by remember { mutableStateOf(TextEditorStore(context).autoSave) } // File › AutoSave: Words' own setting, one for all
    var naming by remember { mutableStateOf<String?>(null) } // File › Save as ("as") or Rename ("rename")
    var about by remember { mutableStateOf(false) } // File › About this file
    var officeSettings by remember { mutableStateOf(false) }
    // Pages › Insert PDF (Combine): another PDF's pages after the one shown
    // Insert PDF: one or several, each after the one before, in the order chosen
    val pickPdf = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val d = doc ?: return@rememberLauncherForActivityResult
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val at0 = at
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching {
                val tmp = File(context.cacheDir, "pdf-insert.pdf")
                uris.fold(0) { n, uri -> context.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                    n + d.insertFile(at0 + 1 + n, tmp) }.also { tmp.delete() }
            } }
            r.onSuccess { n -> version++; Toast.makeText(context, context.resources.getQuantityString(R.plurals.pdf_pages_put_in, n, n, at0 + 1), Toast.LENGTH_SHORT).show() }
                .onFailure { WriterLog.e("PDF: insert", it); Toast.makeText(context, pdfSays(context, it, R.string.pdf_insert_failed), Toast.LENGTH_LONG).show() }
        }
    }
    val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        val d = doc ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val page = at
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching {
                // a photo made a JPEG no wider than 1600 px (a camera's 12 MP would make the PDF huge)
                val raw = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val bmp = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: throw PdfError(R.string.pdf_not_a_picture)
                val k = minOf(1f, 1600f / bmp.width); val small = android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
                val jpg = java.io.ByteArrayOutputStream().also { small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
                val aspect = small.height.toFloat() / small.width
                // Words' picture: half the page across, in its middle (Replace: the chosen one's width, its place)
                val old = chosen?.takeIf { replacing && it.words != null }
                val s = d.sizes[old?.page ?: page]; val w = old?.laid?.first ?: (s.width / 2)
                val img = PdfWords.of(DocImage("n" + java.util.UUID.randomUUID(), null, w, w * aspect, bytes = jpg, extension = "jpg", wrap = PictureWrap.InFront))
                val turned = old?.words?.turn?.let { t -> Arrange.rotated(img.img, t)?.let(img::with) } ?: img
                old?.page to d.putWords(old?.page ?: page, turned, old?.rect?.centerX() ?: (s.width / 2), old?.rect?.centerY() ?: (s.height / 2), old?.id)
            } }
            replacing = false
            r.onSuccess { (p0, id) -> version++; chosen = d.objectById(p0 ?: page, id) }.onFailure { WriterLog.e("PDF: image", it); Toast.makeText(context, pdfSays(context, it, R.string.pdf_image_failed), Toast.LENGTH_LONG).show() }
        }
    }
    /** Typing on the page starts at [t] in Words' editor; [old]: a text put on before, typed in again. Home's tab shows, as Words' while typing. */
    fun startTyping(t: PdfTyping, old: PdfText? = null) {
        chosen = null
        // a new text box starts with a border (as before), a new text with neither border nor fill
        textLook = old?.look ?: if (t.framed) textLook.copy(line = textLook.line ?: 0x000000) else textLook.copy(line = null, fill = null)
        val base = old ?: PdfText(OfficeDoc(listOf(DocPara(emptyList())), basePt = textLook.size), textLook, t.framed) // one empty paragraph, as a new document
        typingText = base
        typed.setRichTextDocument(WriterBridge.toEditor(base.doc, HashMap(), base.styles))
        typed.selection = androidx.compose.ui.text.TextRange(typed.annotatedString.text.length); typing = t
        tab = "Home"
    }
    /**
     * Where a text tapped at [a] on page [i] starts, stuck to what is there as a form's boxes (the user, 27 Oct: "boxes
     * of elements stuck next, above or below"): just right of a box on that line, top to top; under a box just above,
     * its left edge and 6 pt under it; else at the margin or a box's left edge when near one. As wide as there is room.
     */
    fun placed(i: Int, a: Offset, framed: Boolean): PdfTyping {
        val s = doc?.sizes?.getOrNull(i) ?: Size(595f, 842f); val m = margins; val near = 24f; val gap = 6f
        val rs = doc?.objects(i).orEmpty().map { it.rect } // as it is now (a text just put on included)
        val beside = rs.filter { r -> a.y >= r.top && a.y <= r.bottom && a.x >= r.right && a.x - r.right < 2 * near }.maxByOrNull { it.right }
        // under a box: from its left edge to the margin (a short line's words are as wide as they are, not their line)
        val above = rs.filter { r -> a.y >= r.bottom && a.y - r.bottom < 2 * near && a.x >= r.left - near && a.x <= maxOf(r.right, s.width - m.right) + near }.maxByOrNull { it.bottom }
        val (x, y) = when {
            beside != null -> beside.right + gap to beside.top
            above != null -> above.left to above.bottom + gap
            else -> ((rs.map { it.left } + m.left).firstOrNull { kotlin.math.abs(it - a.x) < near } ?: a.x) to a.y - textLook.size * 0.8f
        }
        val x0 = x.coerceIn(0f, maxOf(0f, s.width - 36f)); var y0 = y.coerceIn(0f, maxOf(0f, s.height - 12f))
        // never on top of another box: under it (and under the one under that…)
        while (true) { val r = rs.firstOrNull { r -> x0 + 2f >= r.left && x0 + 2f <= r.right && y0 + 2f >= r.top && y0 + 2f <= r.bottom } ?: break; y0 = r.bottom + gap }
        // to the right margin, or the next box on its line
        val end = rs.filter { r -> r.left > x0 + 12f && r.top < y0 + textLook.size * 1.5f && r.bottom > y0 }.minOfOrNull { it.left - gap } ?: (s.width - m.right)
        return PdfTyping(i, x0, y0, (if (end - x0 >= 36f) end - x0 else s.width - x0 - 6f).coerceAtLeast(24f), 0f, framed)
    }
    /** A text put on before ([o]), tapped: typed in again in its place. */
    fun retype(o: PdfObject) { val old = o.text ?: return; val r = o.rect; val (uw, uh) = old.unturned(r)
        startTyping(PdfTyping(o.page, r.left, r.top, if (old.framed || old.turned) uw else maxOf(uw, (doc?.sizes?.getOrNull(o.page)?.width ?: 595f) - r.left - 18f),
            if (old.framed) uh else 0f, old.framed, o.id), old) }
    /** The words typed put on the page, one undo step (emptied: the old one only taken out). */
    fun putTyped() {
        val t = typing ?: return; typing = null; fontBefore = null
        val d = doc ?: return
        val base = typingText ?: return; typingText = null
        // Words' own step from its editor to its paragraphs; the text's font on all its words, its colour on words with none
        val text = base.with(fonted(WriterBridge.fromEditorKeeping(base.doc.paras, typed.toRichTextDocument(), base.doc.basePt), textLook)).with(textLook)
        if (text.words.isBlank() && t.replacing == null) return
        // what is stacked under it moves with its end (a new one pushes what starts where it does out of its way)
        val all = d.objects(t.page)
        val old = t.replacing?.let { r -> all.firstOrNull { it.id == r } }
        val from = old?.rect ?: android.graphics.RectF(t.x, t.y, t.x + t.room, t.y)
        val stack = stackedUnder(all, from, t.replacing).map { it.id }
        var id = 0
        change { id = d.putTextStacked(t.page, t.x, t.y, text, t.room, t.minHeight, t.replacing, stack, from.bottom, if (old == null) 6f else 0f) }
        WriterLog.i("PDF: text ${if (t.replacing != null) "changed" else "put on"} (${textLook.font}, ${textLook.size} pt)")
        // it stays chosen, as a Slides box after typing: moved, sized, restyled from there
        if (id != 0) chosen = d.objectById(t.page, id)
    }
    /**
     * The chosen text put on again, one undo step: at [x], [y], [room] across and at least [minH] tall, as [t] — it stays
     * chosen. A text box keeps its width; a text is as wide as its words up to the page's edge.
     */
    fun rechange(x: Float? = null, y: Float? = null, room: Float? = null, minH: Float? = null, t: PdfText? = null) {
        val c = chosen ?: return; val old = c.text ?: return; val d = doc ?: return
        val i = c.page; val r = c.rect
        val s = d.sizes.getOrNull(i) ?: return; val nt = t ?: old
        val (uw, uh) = old.unturned(r) // as laid out before turning
        val nx = (x ?: r.left).coerceIn(0f, maxOf(0f, s.width - 12f)); val ny = (y ?: r.top).coerceIn(0f, maxOf(0f, s.height - 8f))
        var id = 0
        change { id = d.putText(i, nx, ny, nt, room ?: if (nt.framed || nt.turned) uw else maxOf(uw, s.width - nx - 18f), minH ?: if (nt.framed) uh else 0f, c.id) }
        chosen = d.objectById(i, id)
    }
    /** The chosen Words object put on again as [w], its middle at [cx], [cy] (one undo step); it stays chosen. */
    fun reput(w: PdfWords, cx: Float? = null, cy: Float? = null) {
        val c = chosen ?: return; val d = doc ?: return
        var id = 0
        change { id = d.putWords(c.page, w, cx ?: c.rect.centerX(), cy ?: c.rect.centerY(), c.id) }
        chosen = d.objectById(c.page, id)
    }
    /** The chosen object moved [dx], [dy] points, kept on its page (one undo step). */
    fun moveChosen(dx: Float, dy: Float) {
        val c = chosen ?: return; val d = doc ?: return; val s = d.sizes.getOrNull(c.page) ?: return
        // at least a corner of it left on the page
        val mx = dx.coerceIn(-c.rect.right + 12f, s.width - c.rect.left - 12f); val my = dy.coerceIn(-c.rect.bottom + 8f, s.height - c.rect.top - 8f)
        if (mx == 0f && my == 0f) return
        when {
            c.text != null -> rechange(x = c.rect.left + mx, y = c.rect.top + my)
            c.words != null -> reput(c.words!!, c.rect.centerX() + mx, c.rect.centerY() + my)
            else -> { var id = 0; change { id = d.moveObject(c.page, c, mx, my) }; chosen = d.objectById(c.page, id) }
        }
    }
    /**
     * The chosen object stretched by Words' handles or Size to [lw] × [lh] before turning, its top left kept (a text
     * gets a fixed width from then on: a box without a border).
     */
    fun sizeChosen(lw: Float, lh: Float) {
        val c = chosen ?: return; val d = doc ?: return
        val w = lw.coerceAtLeast(8f); val h = lh.coerceAtLeast(8f)
        c.text?.let { t -> rechange(room = w.coerceAtLeast(12f), minH = h, t = t.copy(framed = true)); return }
        c.words?.let { o -> val (ow, oh) = o.laid; val mid = turnedBy(Offset((w - ow) / 2, (h - oh) / 2), c.turn)
            reput(o.with(PictureXml.resized(o.img, w, h)), c.rect.centerX() + mid.x, c.rect.centerY() + mid.y); return }
        change { d.sizeOther(c.page, c, w, h) }; chosen = d.objectById(c.page, c.id)
    }
    /** The arrows' nudge put on where they have got to (one step of Undo for a whole press). */
    fun nudged() { val sh = framePreview.shift; framePreview.reset(); if (sh != Offset.Zero) moveChosen(sh.x, sh.y) }
    /** The chosen object turned to [to] degrees clockwise about its middle (one undo step). */
    fun turnChosen(to: Float) {
        val c = chosen ?: return
        c.words?.let { o -> Arrange.rotated(o.img, to)?.let { reput(o.with(it)) }; WriterLog.i("PDF: ${o.kind} turned ${Math.round(to)}°"); return }
        val old = c.text ?: return; val (lw, lh) = old.unturned(c.rect)
        val nt = old.copy(look = old.look.copy(turn = Math.floorMod(Math.round(to), 360))); val (bw, bh) = PdfText.bounds(nt, lw, lh)
        rechange(x = c.rect.centerX() - bw / 2, y = c.rect.centerY() - bh / 2, t = nt)
        WriterLog.i("PDF: text turned ${nt.look.turn}°")
    }
    /** Whether the chosen object can be turned: Tetra's own (another app's keeps its way up). */
    fun turnable(c: PdfObject) = c.text != null || c.words?.let { Arrange.rotated(it.img, 0f) != null } == true
    /** The chosen object taken off the page (one undo step). */
    // Words' Group: the first one chosen (its page and id); the next one tapped joins it
    var grouping by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(chosen?.id) {
        val (gp, gid) = grouping ?: return@LaunchedEffect; val now = chosen ?: return@LaunchedEffect; val d = doc ?: return@LaunchedEffect
        if (now.id == gid) return@LaunchedEffect
        grouping = null
        val first = d.objectById(gp, gid)
        when {
            now.page != gp -> Toast.makeText(context, R.string.words_group_same_page, Toast.LENGTH_SHORT).show()
            first == null || first.words == null || now.words == null -> Toast.makeText(context, R.string.words_cannot_group, Toast.LENGTH_SHORT).show()
            else -> { var id: Int? = null; change { id = d.groupObjects(gp, first, now) }
                id?.let { chosen = d.objectById(gp, it); Toast.makeText(context, R.string.words_grouped, Toast.LENGTH_SHORT).show() }
                    ?: Toast.makeText(context, R.string.words_cannot_group, Toast.LENGTH_SHORT).show() }
        }
    }
    fun ungroupChosen() {
        val c = chosen ?: return; val d = doc ?: return
        var n = 0; change { n = d.ungroupObject(c.page, c) }
        chosen = null
        Toast.makeText(context, if (n > 0) context.getString(R.string.words_ungrouped_into, n) else context.getString(R.string.words_cannot_ungroup), Toast.LENGTH_SHORT).show()
    }
    /** Insert › Link's window for the chosen one, with the link it has. */
    fun askLink() { val c = chosen ?: return; val d = doc ?: return
        scope.launch { linking = withContext(Dispatchers.IO) { runCatching { d.linkAt(c.page, Offset(c.rect.centerX(), c.rect.centerY())) }.getOrNull() } ?: ("" to null) } }
    /** A tap at [pt] on page [i]: a link there shown as Words' "Go to" (not on an object being chosen). */
    fun tappedLink(i: Int, pt: Offset) {
        val d = doc ?: return
        if (tool != null || typing != null || editing && objectUnder(i, pt) != null) return
        scope.launch { linkHere = withContext(Dispatchers.IO) { runCatching { d.linkAt(i, pt) }.getOrNull() } }
    }
    fun deleteChosen() {
        val c = chosen ?: return; chosen = null; val d = doc ?: return
        change { d.deleteObject(c.page, c.id) }
        WriterLog.i("PDF: ${c.kind} deleted")
    }
    /** Word's Align on the page for the chosen object: at the margin it is put by (Layout › Margins), or the middle. */
    fun alignChosen(across: Char?, down: Char?) {
        val c = chosen ?: return; val r = c.rect; val s = doc?.sizes?.getOrNull(c.page) ?: return; val m = margins
        val x = when (across) { 'l' -> m.left; 'c' -> (s.width - r.width()) / 2; 'r' -> s.width - m.right - r.width(); else -> r.left }
        val y = when (down) { 't' -> m.top; 'm' -> (s.height - r.height()) / 2; 'b' -> s.height - m.bottom - r.height(); else -> r.top }
        moveChosen(x - r.left, y - r.top)
        WriterLog.i("PDF: ${c.kind} aligned ${across ?: ""}${down ?: ""}")
    }
    /** Copy (Cut): the chosen object kept for Paste, as Slides' items; a text's words on the clipboard for other apps. */
    fun copyChosen(): Boolean {
        val c = chosen?.takeIf { it.ours } ?: return false
        clip = c
        c.text?.let { t -> runCatching { context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("PDF text", t.words)) } }
        WriterLog.i("PDF: ${c.kind} copied"); return true
    }
    /**
     * Paste on page [i]: what was copied here, a little lower and to the right of where it stood (Slides' way), or at
     * [at]; else words copied in another app, a new text in the look last used, at the margins' corner.
     */
    fun paste(i: Int, at: Offset? = null) {
        val d = doc ?: return; val s = d.sizes.getOrNull(i) ?: return; val m = margins.left; val mt = margins.top
        val c = clip
        var id = 0
        when {
            c?.words != null -> { val cw = c.words!!
                val cx = at?.x?.plus(c.rect.width() / 2) ?: (c.rect.centerX() + 12f); val cy = at?.y?.plus(c.rect.height() / 2) ?: (c.rect.centerY() + 12f)
                change { id = d.putWords(i, cw, cx.coerceAtMost(s.width - 12f), cy.coerceAtMost(s.height - 8f)) }
            }
            c?.text != null -> {
                val ct = c.text!!; val r = c.rect; val (uw, uh) = ct.unturned(r)
                val x = (at?.x ?: (r.left + 12f)).coerceAtMost(s.width - 12f); val y = (at?.y ?: (r.top + 12f)).coerceAtMost(s.height - 8f)
                change { id = d.putText(i, x, y, ct, if (ct.framed || ct.turned) uw else maxOf(uw, s.width - x - 18f), if (ct.framed) uh else 0f) }
            }
            else -> {
                val words = runCatching { context.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()?.trim()
                if (words.isNullOrEmpty()) { Toast.makeText(context, R.string.pdf_nothing_to_paste, Toast.LENGTH_SHORT).show(); return }
                val x = (at?.x ?: m).coerceAtMost(s.width - 12f); val y = (at?.y ?: mt).coerceAtMost(s.height - 8f)
                change { id = d.putText(i, x, y, PdfText(words, textLook.copy(fill = null, line = null, turn = 0), false), maxOf(72f, s.width - x - margins.right), 0f) }
            }
        }
        if (id != 0) chosen = d.objectById(i, id)
        WriterLog.i("PDF: pasted")
    }
    /** Duplicate (Ctrl+D): a copy of the chosen object beside it, Copy's and the clipboard left as they were. */
    fun duplicateChosen() { val c = chosen?.takeIf { it.ours } ?: return; val keep = clip; clip = c; paste(c.page); clip = keep }
    /** The chosen object one step forward or backward among the page's objects and comments ([to] as [restackObject]). */
    fun restackChosen(to: Int) {
        val c = chosen ?: return; val d = doc ?: return
        var moved = false
        change { moved = d.restackObject(c.page, c.id, to) }
        if (!moved) Toast.makeText(context, if (to > 0) R.string.pdf_in_front_already else R.string.pdf_at_back_already, Toast.LENGTH_SHORT).show()
    }
    val design = remember(version, doc) { doc?.design() ?: PdfDesign() } // Design's watermark, page colour and border
    val pickWatermark = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        val d = doc ?: return@rememberLauncherForActivityResult; uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.onSuccess { b -> change { d.setDesign(design.copy(watermark = null, picture = b)) } }
            .onFailure { Toast.makeText(context, R.string.pdf_not_a_picture, Toast.LENGTH_SHORT).show() }
    }
    val pickSignaturePhoto = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { signatureFromPhoto(context, uri, signatureFile) }.onSuccess { tool = PdfTool.Sign; Toast.makeText(context, R.string.pdf_signature_kept, Toast.LENGTH_LONG).show() }
            .onFailure { Toast.makeText(context, R.string.words_picture_not_used, Toast.LENGTH_SHORT).show() }
    }
    /** Pages › Split: [every] page a file, or in two before the page shown; the parts beside the file (it stays as it is). */
    fun splitFile(every: Boolean) { val d = doc ?: return; val f = file ?: return; val page = at
        scope.launch { withContext(Dispatchers.IO) { runCatching { d.split(f, every, page) } }
            .onSuccess { Toast.makeText(context, R.string.pdf_split_done, Toast.LENGTH_LONG).show(); WriterLog.i("PDF: split into ${it.size}") }
            .onFailure { WriterLog.e("PDF: split", it); Toast.makeText(context, pdfSays(context, it, R.string.pdf_extract_failed), Toast.LENGTH_LONG).show() } } }
    /** A new Words object (Insert's: a shape, WordArt, chart, equation, drawing) on the page shown, its middle at [mid] or the page's; chosen after, as Words'. */
    fun putNew(img: DocImage, page: Int = at, mid: Offset? = null) {
        val d = doc ?: return; val s = d.sizes.getOrNull(page) ?: return
        val w = PdfWords.of(img)
        var id = 0
        change { id = d.putWords(page, w, mid?.x ?: (s.width / 2), mid?.y ?: (s.height / 2)) }
        tool = null; chosen = d.objectById(page, id)
        WriterLog.i("PDF: ${w.kind} put in")
    }
    // Insert › WordArt (Words'): its gallery in the ribbon's place, the card tapped on the page at once; OK keeps it (one
    // undo step) and its words are typed next, Cancel takes it off
    var artGallery by remember { mutableStateOf<Pair<ArtStyle?, ArtBend?>?>(null) }
    var artPut by remember { mutableStateOf(false) }
    fun artPick(c: Any) {
        val d = doc ?: return; val (s0, b0) = artGallery ?: return
        val style = c as? ArtStyle ?: s0 ?: ART_STYLES[0]; val bend = c as? ArtBend ?: b0 ?: ArtBend(null)
        if (artPut) d.undo() // the card before taken off: one step in all
        putNew(newTextBoxImage(OfficeKind.Docx, listOf("Your text here"), 0xFF000000.toInt() or style.color,
            TextEffects(if (style.outline) artOutline(style.color) else null, style.shadow, if (style.glow) artGlow(style.color) else null), bend.warp, PictureWrap.InFront))
        artPut = true; artGallery = style to bend
    }
    fun artClosed(keep: Boolean) {
        if (artPut) { if (keep) inserting = "boxText" else { doc?.let { d -> d.undo(); d.forgetRedo() }; chosen = null; version++ } }
        artGallery = null; artPut = false
    }
    // Review (Words'): the comments as Words' cards, the one tapped "here" for Reply, Resolve and Delete; Read aloud the pages' words
    var commentList by remember { mutableStateOf<List<PdfComment>>(emptyList()) }
    var hereComment by remember { mutableStateOf<PdfComment?>(null) }
    var commentAction by remember { mutableStateOf<Pair<String, PdfComment>?>(null) } // "reply" or "edit"
    var commentsOpen by remember { mutableStateOf(false) } // a phone's sidebar
    LaunchedEffect(version, doc, pane, tab) { val d = doc ?: return@LaunchedEffect
        if (pane == "Comments" || tab == "Review" || commentsOpen) commentList = withContext(Dispatchers.IO) { runCatching { d.comments() }.getOrDefault(emptyList()) }
        hereComment = hereComment?.let { h -> commentList.firstOrNull { it.page == h.page && it.index == h.index } } }
    fun commentOf(id: String) = commentList.firstOrNull { "${it.page}:${it.index}" == id }
    val commentActions = CommentActions(
        repliesOf = { dc -> commentList.filter { it.replyTo != null && "${it.page}:${it.replyTo}" == dc.id }.map { it.card(context.getString(it.kind)) } },
        open = { dc -> commentOf(dc.id)?.let { hereComment = it; goTo(it.page) } },
        delete = { dc -> commentOf(dc.id)?.let { c -> doc?.let { d -> change { d.deleteComment(c) } }; if (hereComment == c) hereComment = null } },
        reply = { dc -> commentOf(dc.id)?.let { commentAction = "reply" to it } },
        resolve = { dc -> commentOf(dc.id)?.let { c -> doc?.let { d -> change { d.resolve(c, !c.done) } } } },
        edit = { dc -> commentOf(dc.id)?.let { commentAction = "edit" to it } })
    val author = remember { context.getSharedPreferences("writer", android.content.Context.MODE_PRIVATE).getString("author", "") ?: "" }
    fun keepAuthor(name: String) { if (name.isNotBlank()) context.getSharedPreferences("writer", android.content.Context.MODE_PRIVATE).edit().putString("author", name.trim()).apply() }
    val reader = rememberReadAloud()
    var readingParas by remember { mutableStateOf<List<String>>(emptyList()) }
    /** Words put in (Symbol, Date): where one types, else a new text at the margins' corner, chosen. */
    fun insertWords(words: String) {
        if (typing != null) { typed.addTextAfterSelection(words); return }
        val d = doc ?: return; val i = at; val s = d.sizes.getOrNull(i) ?: return
        var id = 0
        change { id = d.putText(i, margins.left, margins.top, PdfText(words, textLook.copy(fill = null, line = null, turn = 0), false), maxOf(72f, s.width - margins.left - margins.right)) }
        chosen = d.objectById(i, id)
    }
    /** Picture › Save as (Words'): the picture into the phone's Pictures/Tetra. */
    fun savePicture(o: PdfWords) { val b = o.bytes() ?: return
        scope.launch { Toast.makeText(context, savePictureToPhone(context, b, o.img.extension, file?.name ?: "PDF"), Toast.LENGTH_LONG).show() } }
    /** Turn left, Turn right and Flip (Words' Picture tab): the picture itself turned or mirrored ([turn] degrees, [flip]). */
    fun transformChosen(turn: Int, flip: Boolean) {
        val o = chosen?.words ?: return; val b = o.bytes() ?: return
        val bmp = android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size) ?: return
        val m = android.graphics.Matrix().apply { if (flip) postScale(-1f, 1f); postRotate(turn.toFloat()) }
        val out = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        val png = o.img.extension.lowercase() == "png"
        val bytes = java.io.ByteArrayOutputStream().also { out.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        val (w, h) = o.laid
        val img = DocImage(o.img.key, null, if (turn % 180 != 0) h else w, if (turn % 180 != 0) w else h, bytes = bytes, extension = if (png) "png" else "jpg", wrap = PictureWrap.InFront)
        reput(PdfWords.of(img).let { n -> Arrange.rotated(n.img, o.turn)?.let(n::with) ?: n })
    }
    /**
     * Find and Replace's Replace (the found words in a text Tetra put on: the page's own cannot be typed over, Edit in
     * Words has them) — the one found now, or [all] in every text; found again after.
     */
    fun replaceFound(all: Boolean) {
        val d = doc ?: return
        var n = 0
        if (all) change { d.sizes.indices.forEach { i -> d.objects(i).forEach { o -> o.text?.let { t ->
            val (nt, k) = t.replaced(query.trim(), replacement, true)
            if (k > 0) { val (uw, uh) = t.unturned(o.rect); d.putText(i, o.rect.left, o.rect.top, nt, uw, if (t.framed) uh else 0f, o.id); n += k }
        } } } }
        else matches?.getOrNull(match)?.let { m -> val box = m.boxes.firstOrNull() ?: return@let
            objectsOn(m.page).lastOrNull { o -> o.text != null && o.rect.contains(box.centerX(), box.centerY()) }?.let { o -> val t = o.text!!
                val (nt, k) = t.replaced(query.trim(), replacement, false)
                if (k > 0) { val (uw, uh) = t.unturned(o.rect); change { d.putText(m.page, o.rect.left, o.rect.top, nt, uw, if (t.framed) uh else 0f, o.id) }; n = k }
            } }
        if (n == 0) Toast.makeText(context, R.string.pdf_replace_only_typed, Toast.LENGTH_LONG).show()
        else { WriterLog.i("PDF: replaced $n"); if (all) Toast.makeText(context, context.getString(R.string.words_replaced_count, n), Toast.LENGTH_SHORT).show(); find() }
    }
    // Home's target (Words' Home): the words being typed, else a chosen text's words, all chosen, in an editor of their own
    val chosenText = chosen?.text
    val scratch = remember(chosen?.id, version) { chosenText?.let { t -> com.mohamedrejeb.richeditor.model.RichTextState().apply {
        setRichTextDocument(WriterBridge.toEditor(t.doc, HashMap(), t.styles)); selection = androidx.compose.ui.text.TextRange(0, annotatedString.text.length) } } }
    val homeTarget: PdfTextTarget? = typingText?.takeIf { typing != null }?.let { base -> PdfTextTarget(typed, base, {}) { ps ->
        val nb = base.with(ps); typingText = nb; val sel = typed.selection; typed.setRichTextDocument(WriterBridge.toEditor(nb.doc, HashMap(), nb.styles), sel) } }
        ?: if (chosenText != null && scratch != null) PdfTextTarget(scratch, chosenText, {
            rechange(t = chosenText.with(WriterBridge.fromEditorKeeping(chosenText.doc.paras, scratch.toRichTextDocument(), chosenText.doc.basePt))) }) { ps -> rechange(t = chosenText.with(ps)) }
        else null
    BackHandler(enabled = editing || organize) { if (organize) organize = false else if (typing != null) putTyped() else if (chosen != null) chosen = null else if (tool != null) tool = null else if (doc?.dirty != true) editing = false else if (autoSave) save { editing = false } else leaving = true }
    /** What a finger did on page [i] with the tool on: [pts] its path in page points (a tap: one point). */
    fun onPage(i: Int, pts: List<Offset>) {
        // a tap off the words being typed puts them on, as Words' click outside a box; nothing typed yet in a new one: the
        // cursor goes where tapped instead (no box was made)
        typing?.let { t0 ->
            if (t0.replacing == null && pts.size == 1 && typed.annotatedString.text.isBlank() && objectUnder(i, pts[0]) == null) { startTyping(placed(i, pts[0], t0.framed)); return }
            putTyped(); chosen = null
            // and the cursor where tapped, unless on an object (then that one chosen), as Words' click elsewhere
            if (pts.size == 1 && tool == null) objectUnder(i, pts[0])?.let { chosen = it } ?: startTyping(placed(i, pts[0], false))
            return
        }
        val had = chosen != null
        chosen = null // a tap off the chosen text: none chosen (on it, its frame takes the tap)
        val d = doc ?: return; val t = tool ?: run {
            if (pts.size != 1) return
            // an object tapped while editing: chosen, as Words' (a text: a second tap types in it)
            if (editing) objectUnder(i, pts[0])?.let { chosen = it; return }
            // no tool: a form's field is filled in (a box ticked at once), a note tapped shows its words
            d.fieldAt(i, pts[0])?.let { f ->
                if (f.readOnly) { Toast.makeText(context, R.string.pdf_field_read_only, Toast.LENGTH_SHORT).show(); return }
                editing = true
                when (f.kind) {
                    "check", "radio" -> change { d.fill(i, pts[0], null) }
                    "text", "choice" -> filling = Triple(i, pts[0], f)
                }
                return
            }
            d.noteAt(i, pts[0])?.let { reading = it.ifBlank { context.getString(R.string.pdf_empty_note) }; return }
            // editing, on an empty spot: Words' click-and-type — the cursor there, a text made when something is typed (the
            // user, 27 Oct); with something chosen, this tap only lets it go, as Words'
            if (editing && !had) startTyping(placed(i, pts[0], false))
            return }
        val a = pts.first(); val b = pts.last()
        when (t) {
            PdfTool.RedactText -> change {
                // the words a finger crossed, marked to be taken out (the user, 22 Oct: "cant we do select and reduct?")
                if (!d.markText(i, a, b, com.artifex.mupdf.fitz.PDFAnnotation.TYPE_REDACT, 0x000000))
                    Toast.makeText(context, R.string.pdf_no_words_redact, Toast.LENGTH_SHORT).show()
            }
            PdfTool.Highlight, PdfTool.Underline, PdfTool.Strike -> change {
                val type = when (t) { PdfTool.Underline -> com.artifex.mupdf.fitz.PDFAnnotation.TYPE_UNDERLINE; PdfTool.Strike -> com.artifex.mupdf.fitz.PDFAnnotation.TYPE_STRIKE_OUT
                    else -> com.artifex.mupdf.fitz.PDFAnnotation.TYPE_HIGHLIGHT }
                if (!d.markText(i, a, b, type, if (t == PdfTool.Highlight) markColour else if (markColour == 0xFFEB3B) 0xE53935 else markColour))
                    Toast.makeText(context, R.string.pdf_no_words_mark, Toast.LENGTH_SHORT).show()
            }
            PdfTool.Note -> writing = i to a
            PdfTool.Redact -> if ((b - a).getDistance() > 4f) change { d.markRedaction(i, a, b) }
            // Words' Insert › Text box: one box, drawn or put where tapped (its frame shows once something is typed)
            PdfTool.TextBox -> { tool = null
                if (pts.size == 1) startTyping(placed(i, a, true))
                else if ((b - a).getDistance() > 10f) startTyping(PdfTyping(i, minOf(a.x, b.x), minOf(a.y, b.y), kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y), true)) }
            PdfTool.TextField -> if ((b - a).getDistance() > 10f) change { d.addField(i, a, b, checkbox = false) }
            PdfTool.CheckBox -> change { d.addField(i, a - Offset(7f, 7f), a + Offset(7f, 7f), checkbox = true) }
            PdfTool.Stamp -> change {
                val pic = stampPicture
                if (stampName == PICTURE_STAMP && pic != null) d.picture(i, a - Offset(70f, 70f * pic.second / 2), pic.first, 140f, pic.second) else d.stampAt(i, a, stampName)
            }
            PdfTool.Rectangle, PdfTool.Oval, PdfTool.Arrow -> if ((b - a).getDistance() > 4f) change {
                d.shape(i, when (t) { PdfTool.Oval -> com.artifex.mupdf.fitz.PDFAnnotation.TYPE_CIRCLE; PdfTool.Arrow -> com.artifex.mupdf.fitz.PDFAnnotation.TYPE_LINE
                    else -> com.artifex.mupdf.fitz.PDFAnnotation.TYPE_SQUARE }, a, b, if (markColour == 0xFFEB3B) 0xE53935 else markColour, 2f)
            }
            PdfTool.Pen -> if (pts.size > 1) change { d.ink(i, listOf(pts), penColour, penWidth) }
            PdfTool.Marker -> if (pts.size > 1) change { d.ink(i, listOf(pts), 0xFFEB3B, 12f) }
            PdfTool.Erase -> change { if (!d.deleteAt(i, a)) Toast.makeText(context, R.string.pdf_no_comment_there, Toast.LENGTH_SHORT).show() }
            // the words start where the finger was (its line through the tap), as far across as the page leaves room
            PdfTool.Text -> d.objectAt(i, a)?.takeIf { it.text != null }?.let(::retype)
                ?: startTyping(PdfTyping(i, a.x, a.y - textLook.size * 0.8f, (d.sizes[i].width - a.x - minOf(margins.right, d.sizes[i].width - a.x - 72f)).coerceAtLeast(72f), 0f, false))
            PdfTool.Tick -> change { d.mark(i, a, '✓', fillSize * 1.3f, 0x000000) }
            PdfTool.Cross -> change { d.mark(i, a, '✗', fillSize * 1.3f, 0x000000) }
            PdfTool.Dot -> change { d.mark(i, a, '•', fillSize * 1.3f, 0x000000) }
            PdfTool.Date -> change { d.text(i, a - Offset(0f, fillSize * 0.6f), java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")), fillSize, 0x000000) }
            PdfTool.Sign -> if (!signatureFile.isFile) signing = true else {
                // Words' signature (Insert › Signature): a picture named so, chosen after, moved and sized as any
                var id = 0
                change { id = d.putWords(i, signatureWords(signatureFile.readBytes()), a.x, a.y) }
                tool = null; chosen = d.objectById(i, id)
            }
        }
    }

    val wide = LocalConfiguration.current.screenWidthDp >= 700
    var pages by remember { mutableStateOf(false) } // a phone's list of pages, brought out from the page label (asked 17 Oct)
    // Pages › Organize: the scanner's grid (ReorderGrid), long-press and drag; Done puts the pages in that order, one undo step
    val d1 = doc
    if (organize && d1 != null) {
        var order by remember { mutableStateOf(d1.sizes.indices.toList()) }
        val density = LocalDensity.current
        ReorderGrid(order.map { "p$it" }, { from, to -> order = order.toMutableList().apply { add(to, removeAt(from)) } },
            open = { k -> organize = false; scope.launch { delay(50); goTo(k) } }, back = { organize = false }, title = stringResource(R.string.pdf_organize),
            actions = { RibbonButton(stringResource(R.string.common_done), MaterialTheme.colorScheme.onBackground, R.drawable.ic_check) {
                if (order != d1.sizes.indices.toList()) change { d1.move(order) }
                organize = false
            } }) { k, m -> PdfPage(d1, order[k], with(density) { 160.dp.roundToPx() }, m.background(Color.White)) }
        return
    }
    leaving.takeIf { it }?.let {
        androidx.compose.material3.AlertDialog(onDismissRequest = { leaving = false }, title = { Text(stringResource(R.string.pdf_save_changes)) },
            text = { Text(stringResource(R.string.pdf_stays_in_versions)) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { leaving = false; save { editing = false } }) { Text(stringResource(R.string.common_save), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            dismissButton = { Row {
                androidx.compose.material3.TextButton(onClick = {
                    leaving = false; editing = false
                    // not saved: the file read again as it is
                    val f = file; doc?.close(); doc = null
                    if (f != null) scope.launch { withContext(Dispatchers.IO) { runCatching { PdfDoc(f, password) } }.onSuccess { doc = it; version++ } }
                }) { Text(stringResource(R.string.words_dont_save), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                androidx.compose.material3.TextButton(onClick = { leaving = false }) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            } })
    }
    // The windows, in a function of their own (as Words' Dialogs): PdfScreen's one method is at the size Android's
    // verifier takes (past it, a VerifyError when a PDF opens, 27 Oct)
    @Composable fun Dialogs() {
    // Insert › Comment where tapped, Reply and Edit: Words' comment window (your name kept as Words keeps it)
    writing?.let { (i, at0) -> NewCommentDialog(author, { writing = null }) { name, text -> writing = null; keepAuthor(name)
        doc?.let { d -> change { d.note(i, at0, text.trim(), markColour, name.trim()) } } } }
    commentAction?.let { (what, c) -> if (what == "edit") NewCommentDialog(c.author, { commentAction = null }, title = stringResource(R.string.words_edit_comment), start = c.words,
            confirm = stringResource(R.string.common_done)) { _, text -> commentAction = null; doc?.let { d -> editing = true; change { d.editComment(c, text.trim()) } } }
        else NewCommentDialog(author, { commentAction = null }, title = stringResource(R.string.words_reply), confirm = stringResource(R.string.words_reply)) { name, text -> commentAction = null; keepAuthor(name)
            doc?.let { d -> editing = true; change { d.reply(c, text.trim(), name.trim()) } } } }
    if (commentsOpen) CommentSidebar(commentList.filter { it.replyTo == null }.map { it.card(stringResource(it.kind)) }, hereComment?.card(stringResource(hereComment!!.kind)), { commentsOpen = false }, commentActions)
    // the signature: drawn once in Words' drawing box, kept in the app (never in a file), put where Sign is tapped
    if (signing) DrawDialog(200f, { signing = false }, boxAspect = 0.4f) { png, _, _, _, _ ->
        signing = false
        runCatching { signatureFile.writeBytes(png) }.onSuccess { tool = PdfTool.Sign; Toast.makeText(context, R.string.pdf_signature_kept, Toast.LENGTH_LONG).show() }
    }
    // Insert's, Design's and Layout's windows: Words' own
    when (inserting) {
        "shapes" -> ShapesDialog(false, { inserting = null }) { shape, fill, line -> inserting = null
            val (w, h) = shapeSize(shape)
            putNew(DocImage("s${System.nanoTime()}", null, w, h, PictureXml.newShape(shape, OfficeKind.Docx, fill, line, 1f, w, h), wrap = PictureWrap.InFront)) }
        "chart" -> ChartDialog(WORD_SAMPLE_CHART, false, { inserting = null }) { ch -> inserting = null
            putNew(DocImage("c${System.nanoTime()}", null, 396f, 230f, chart = ch, wrap = PictureWrap.InFront)) }
        "equation" -> EquationDialog(null, { inserting = null }) { m -> inserting = null
            putNew(OfficeMath.image("n${System.nanoTime()}", m, OfficeMath.equation(m, false), 12f)) }
        "chartEdit" -> chosen?.words?.let { o -> o.img.chart?.let { ch -> ChartDialog(ch, true, { inserting = null }) { n -> inserting = null; val (lw, lh) = o.laid
            reput(PdfWords.of(DocImage(o.img.key, null, lw, lh, chart = n, wrap = PictureWrap.InFront))) } } }
        "mathEdit" -> chosen?.words?.let { o -> EquationDialog(o.img.math, { inserting = null }) { m -> inserting = null
            reput(PdfWords.of(OfficeMath.image(o.img.key, m, OfficeMath.equation(m, false), 12f))) } }
        "symbol" -> SymbolDialog({ inserting = null }) { sym -> inserting = null; insertWords(sym) }
        "date" -> DateDialog({ inserting = null }) { day -> inserting = null; insertWords(day) }
        "page" -> doc?.sizes?.getOrNull(at)?.let { s -> PageSetupDialog(DocPage(s.width, s.height, margins.top, margins.bottom, margins.left, margins.right), { inserting = null }) { np ->
            inserting = null
            // Word's Page Setup: every page that size (as a document of one section), the margins Position and a new text go by
            if (np.width != s.width || np.height != s.height) doc?.let { d -> change { d.resize { Size(np.width, np.height) } } }
            margins = np; PdfMargins.save(context, np)
            WriterLog.i("PDF: page setup ${np.width.toInt()}×${np.height.toInt()}")
        } }
        "border" -> PageBorderDialog(design.border, { inserting = null }) { line, sides -> inserting = null
            doc?.let { d -> change { d.setDesign(design.copy(border = line?.let { PdfDesign.border(it, sides) })) } } }
        "boxText" -> chosen?.words?.let { o -> val img = o.img; val art = TextWarp.art(img.raw) != null; val shape = img.box == null
            TextBoxDialog(img.box.orEmpty().joinToString("\n") { it.text }, { inserting = null }, art = art, confirm = stringResource(R.string.common_done),
                title = if (shape) stringResource(R.string.words_words_in_shape) else null,
                // a WordArt's colour, effects and bend as they are (Words'), kept unless changed
                start = if (art) TextWarp.art(img.raw)?.let { (rgb, fx) -> Triple(0xFF000000.toInt() or rgb, fx, TextWarp.of(img.raw)) } else null) { text, color, fx, warp ->
                inserting = null
                val lines = text.lines()
                if (art) reput(PdfWords.of(newTextBoxImage(OfficeKind.Docx, lines, color, fx, warp, PictureWrap.InFront).let { n -> Arrange.rotated(n, o.turn) ?: n }))
                else img.raw?.let { raw -> reput(o.with(img.with(raw = PictureXml.boxText(raw, OfficeKind.Docx, lines), box = PictureXml.boxParas(img, lines)
                    // a shape's first words centred, as Words'
                    .let { ps -> if (shape) ps.map { it.copy(align = DocAlign.Center) } else ps }))) }
            } }
    }
    // Draw › Draw on page (Words'): drawn over the page as it is, put on it as a drawing; Drawing box: drawn in a box, put in the page's middle
    drawingOn?.let { (page, under) -> doc?.sizes?.getOrNull(page)?.let { s ->
        DrawDialog(s.width, { drawingOn = null }, slide = Triple(s.width, s.height) { c -> under?.let { b -> c.drawBitmap(b, null, android.graphics.RectF(0f, 0f, s.width, s.height), null) } }) { png, ww, hh, place, _ ->
            drawingOn = null
            putNew(DocImage("d${System.nanoTime()}", null, ww, hh, bytes = png, wrap = PictureWrap.InFront), page, place?.let { (x, y) -> Offset(x + ww / 2, y + hh / 2) })
        } } }
    if (drawingBox) DrawDialog(minOf(450f, doc?.sizes?.getOrNull(at)?.width?.minus(margins.left + margins.right) ?: 450f), { drawingBox = false }) { png, ww, hh, _, _ ->
        drawingBox = false; putNew(DocImage("d${System.nanoTime()}", null, ww, hh, bytes = png, wrap = PictureWrap.InFront)) }
    if (editingInk) chosen?.words?.let { o -> o.bytes()?.let(InkPicture::strokesOf)?.let { (strokes, _) -> val (lw, lh) = o.laid
        DrawDialog(lw, { editingInk = false }, boxAspect = lh / lw.coerceAtLeast(1f), initial = strokes) { png, _, _, _, _ ->
            editingInk = false
            reput(PdfWords.of(DocImage(o.img.key, null, lw, lh, bytes = png, wrap = PictureWrap.InFront)).let { n -> Arrange.rotated(n.img, o.turn)?.let(n::with) ?: n })
        } } }
    // Insert › Link (Words' window): a web address or a page of this file, for the chosen text or object
    linking?.let { (now, toPage) -> chosen?.let { c -> doc?.let { d ->
        LinkDialog(false, toPage?.let { "#$it" } ?: now.ifEmpty { null }, null, now.isNotEmpty(), { linking = null }, { linking = null; change { d.link(c.page, c.rect, null) } },
            slides = d.sizes.indices.map { "" }, place = stringResource(R.string.pdf_page_in_file)) { _, to ->
            linking = null
            val uri = to.takeIf { it.startsWith("#") }?.drop(1)?.toIntOrNull()?.let { "#page=${it + 1}" } ?: to.trim().takeIf { it.isNotEmpty() && it != "https://" } ?: return@LinkDialog
            change { d.link(c.page, c.rect, uri) }
            WriterLog.i("PDF: link on ${c.kind}: ${if (uri.startsWith("#")) uri else "web"}")
        } } } }
    // Picture › Crop: Words' crop window (straight cuts at first); the piece cut stays where it was on the page
    cropping?.let { bitmap -> androidx.compose.ui.window.Dialog({ cropping = null }, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            CornerEditor(bitmap, DocumentQuad.fullFrame(), DocumentQuad.fullFrame(), stringResource(R.string.words_reset), straightOption = true, done = { quad ->
                cropping = null
                val c = chosen ?: return@CornerEditor; val o = c.words ?: return@CornerEditor
                if (quad == DocumentQuad.fullFrame()) return@CornerEditor
                val cut = runCatching { cropScan(bitmap, quad, 2400f) }.getOrNull() ?: return@CornerEditor
                val png = o.img.extension == "png" || o.img.part?.endsWith(".png", ignoreCase = true) == true
                val bytes = java.io.ByteArrayOutputStream().also { cut.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
                val (lw, lh) = o.laid
                val n = PdfWords.of(DocImage(o.img.key, null, lw * cut.width / bitmap.width, lh * cut.height / bitmap.height, null, bytes, if (png) "png" else "jpg", PictureWrap.InFront))
                // the cut piece's middle where it was (a turned picture: its middle kept)
                val xs = quad.points.map { it.x }; val ys = quad.points.map { it.y }
                val (cx, cy) = if (o.turn != 0f) c.rect.centerX() to c.rect.centerY()
                    else c.rect.left + (xs.min() + xs.max()) / 2 * c.rect.width() to c.rect.top + (ys.min() + ys.max()) / 2 * c.rect.height()
                reput(Arrange.rotated(n.img, o.turn)?.let(n::with) ?: n, cx, cy)
            }, cancel = { cropping = null })
        }
    } }
    // Home › Line spacing and More…: Words' Paragraph window, for the paragraphs Home's target has chosen
    paragraphPage?.let { (at, look) -> ParagraphDialog(look, { paragraphPage = null }) { now ->
        paragraphPage = null
        homeTarget?.let { t -> t.paras(t.now().mapIndexed { i, p -> if (i !in at) p else p.copy(keep = OfficeFormats.withSpacing(p.keep, OfficeKind.Docx,
            now.lineMultiple.takeIf { now.lineExact == null && it != look.lineMultiple }, now.before.takeIf { it != look.before }, now.after.takeIf { it != look.after },
            now.left.takeIf { it != look.left }, now.firstLine.takeIf { it != look.firstLine }, now.right.takeIf { it != look.right })) }) }
    } }
    // Picture › Size, Rotate, Position, Shape options and a signature's Redraw: Words' own windows
    chosen?.let { c ->
        val d0 = doc ?: return@let
        if (sizing) { val (lw, lh) = c.laid
            PictureSizeDialog(lw, lh, c.words?.let { o -> picturePainter(o.img, o.parts) } ?: androidx.compose.ui.graphics.painter.ColorPainter(Color.LightGray),
                { sizing = false }) { nw, nh -> sizing = false; sizeChosen(nw, nh) } }
        if (rotating) RotateDialog(c.turn, { rotating = false }) { deg -> rotating = false; turnChosen(deg) }
        // a text box's own: its fill and border, its size and turn (Words' window; dash, shadow and alt text are a shape's)
        if (shapeOptions) c.text?.let { t -> val (lw, lh) = c.laid
            ShapeOptionsDialog(ShapeOptions(t.look.fill?.let { 0xFF000000.toInt() or it }, t.look.line?.let { 0xFF000000.toInt() or it }, 0.75f, lw, lh, c.turn, ""), { shapeOptions = false }) { op ->
                shapeOptions = false
                val l = t.look.copy(fill = op.fill?.takeIf { it ushr 24 != 0 }?.and(0xFFFFFF), line = op.line?.and(0xFFFFFF))
                if (l != t.look) rechange(t = t.with(l))
                if (op.widthPt != lw || op.heightPt != lh) sizeChosen(op.widthPt, op.heightPt)
                if (op.degrees != c.turn) turnChosen(op.degrees)
            } }
        if (shapeOptions) c.words?.let { o -> ShapePicture.of(o.img)?.let { sp -> val img = o.img
            ShapeOptionsDialog(ShapeOptions(sp.fill, sp.line, sp.lineWidth, img.widthPt, img.heightPt, Arrange.rotationOf(img), PictureXml.altOf(img), sp.dash, sp.shadow), { shapeOptions = false }) { op ->
                shapeOptions = false
                var next = PictureXml.shapeLook(img, op.fill, op.line, op.lineWidth, op.dash, op.shadow) ?: img
                if (op.widthPt != img.widthPt || op.heightPt != img.heightPt) next = PictureXml.resized(next, op.widthPt, op.heightPt)
                if (op.degrees != Arrange.rotationOf(img)) next = Arrange.rotated(next, op.degrees) ?: next
                if (op.alt != PictureXml.altOf(img)) next = PictureXml.withAlt(next, op.alt) ?: next
                reput(o.with(next))
                WriterLog.i("PDF: shape options ${op.widthPt.toInt()}×${op.heightPt.toInt()} ${op.degrees}°")
            } } }
        if (positioning) {
            // the page without it and it alone, drawn by MuPDF (a page's drawing at most 1200 px across)
            val s = d0.sizes[c.page]; val px = 1200
            var under by remember(c.id, version) { mutableStateOf<Bitmap?>(null) }
            var it0 by remember(c.id, version) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(c.id, version) { under = d0.page(c.page, px, c.id); it0 = withContext(Dispatchers.IO) { d0.objectPicture(c.page, c.id, px) } }
            val k = s.width / px
            PositionDialog(s.width, s.height, c.rect, c.id to version, { n -> under?.let { b -> n.save(); n.scale(k, k); n.drawBitmap(b, 0f, 0f, null); n.restore() } },
                { n, x, y -> it0?.let { b -> n.save(); n.translate(x, y); n.scale(c.rect.width() / b.width, c.rect.height() / b.height); n.drawBitmap(b, 0f, 0f, null); n.restore() } },
                { positioning = false }) { x, y -> moveChosen(x - c.rect.left, y - c.rect.top) }
        }
        if (redrawing) DrawDialog(200f, { redrawing = false }, boxAspect = 0.4f) { png, _, _, _, _ ->
            redrawing = false
            val (lw, _) = c.laid
            reput(signatureWords(png, lw).let { n -> c.words?.turn?.let { t -> Arrange.rotated(n.img, t)?.let(n::with) } ?: n })
        }
    }
    }
    Dialogs()
    filling?.let { (i, at0, f) ->
        var value by remember { mutableStateOf(f.value) }
        androidx.compose.material3.AlertDialog(onDismissRequest = { filling = null }, title = { Text(f.name.ifBlank { stringResource(R.string.pdf_field) }) },
            text = {
                if (f.kind == "choice") Column { f.options.forEach { o ->
                    Row(Modifier.fillMaxWidth().clickable { value = o }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(value == o, { value = o }); Text(o)
                    } } }
                else androidx.compose.material3.OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), singleLine = !f.multiline, minLines = if (f.multiline) 3 else 1)
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { filling = null; doc?.let { d -> change { d.fill(i, at0, value) } } }) { Text(stringResource(R.string.common_ok), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { filling = null }) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) } })
    }
    when (asking) {
        "header" -> {
            var head by remember { mutableStateOf("") }; var foot by remember { mutableStateOf("") }
            androidx.compose.material3.AlertDialog(onDismissRequest = { asking = null }, title = { Text(stringResource(R.string.words_header_footer)) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedTextField(head, { head = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.words_header)) }, singleLine = true)
                    androidx.compose.material3.OutlinedTextField(foot, { foot = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.words_footer)) }, singleLine = true)
                    Text(stringResource(R.string.pdf_header_hint), style = MaterialTheme.typography.bodySmall)
                } },
                confirmButton = { androidx.compose.material3.TextButton(enabled = head.isNotBlank() || foot.isNotBlank(), onClick = { asking = null
                    doc?.let { d -> change { d.headerFooter(head.trim(), foot.trim(), 10f) } } }) { Text(stringResource(R.string.pdf_put_in), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { asking = null }) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) } })
        }
        "redact" -> {
            var words by remember { mutableStateOf("") }
            var pattern by remember { mutableStateOf<Int?>(null) }
            androidx.compose.material3.AlertDialog(onDismissRequest = { asking = null }, title = { Text(stringResource(R.string.pdf_find_redact)) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.pdf_find_redact_about), style = MaterialTheme.typography.bodySmall)
                    androidx.compose.material3.OutlinedTextField(words, { words = it; if (it.isNotEmpty()) pattern = null }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.pdf_words)) }, singleLine = true)
                    Text(stringResource(R.string.pdf_or_every), style = MaterialTheme.typography.labelLarge)
                    REDACT_PATTERNS.forEachIndexed { k, (label, _) ->
                        Row(Modifier.fillMaxWidth().clickable { pattern = k; words = "" }, verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.RadioButton(pattern == k, { pattern = k; words = "" }); Text(stringResource(label))
                        }
                    }
                } },
                confirmButton = { androidx.compose.material3.TextButton(enabled = words.isNotBlank() || pattern != null, onClick = { asking = null
                    val d = doc ?: return@TextButton
                    var n = 0
                    change { n = d.markAll(words.trim().ifEmpty { null }, pattern?.let { REDACT_PATTERNS[it].second }) }
                    Toast.makeText(context, if (n == 0) context.getString(R.string.pdf_redact_not_found) else context.resources.getQuantityString(R.plurals.pdf_marked, n, n), Toast.LENGTH_LONG).show()
                }) { Text(stringResource(R.string.pdf_mark), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { asking = null }) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) } })
        }
        "bookmark" -> {
            var title by remember { mutableStateOf(context.getString(R.string.pdf_page_n, at + 1)) }
            androidx.compose.material3.AlertDialog(onDismissRequest = { asking = null }, title = { Text(stringResource(R.string.pdf_bookmark_page, at + 1)) },
                text = { androidx.compose.material3.OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.words_title)) }, singleLine = true) },
                confirmButton = { androidx.compose.material3.TextButton(enabled = title.isNotBlank(), onClick = { asking = null; val pg = at
                    doc?.let { d -> editing = true; change { d.addBookmark(title.trim(), pg) } } }) { Text(stringResource(R.string.common_add), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { asking = null }) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) } })
        }
        "watermark" -> {
            // Design › Watermark › Custom… (Words'): its words across every page
            var words by remember { mutableStateOf(design.watermark ?: "") }
            androidx.compose.material3.AlertDialog(onDismissRequest = { asking = null }, title = { Text(stringResource(R.string.words_watermark)) },
                text = { androidx.compose.material3.OutlinedTextField(words, { words = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.words_text_across_pages)) }, singleLine = true) },
                confirmButton = { androidx.compose.material3.TextButton(enabled = words.isNotBlank(), onClick = { asking = null
                    doc?.let { d -> change { d.setDesign(design.copy(watermark = words.trim(), picture = null)) } } }) { Text(stringResource(R.string.common_ok), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { asking = null }) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) } })
        }
        "password" -> {
            var pw by remember { mutableStateOf("") }; var again by remember { mutableStateOf("") }
            androidx.compose.material3.AlertDialog(onDismissRequest = { asking = null }, title = { Text(stringResource(R.string.pdf_password)) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.pdf_password_about), style = MaterialTheme.typography.bodySmall)
                    androidx.compose.material3.OutlinedTextField(pw, { pw = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.pdf_password)) }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                    androidx.compose.material3.OutlinedTextField(again, { again = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.pdf_password_again)) }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), isError = again.isNotEmpty() && again != pw)
                } },
                confirmButton = { androidx.compose.material3.TextButton(enabled = pw.length >= 4 && pw == again, onClick = { asking = null
                    doc?.protect(pw); version++; Toast.makeText(context, R.string.pdf_password_on_save, Toast.LENGTH_LONG).show() }) { Text(stringResource(R.string.common_ok), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { asking = null }) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) } })
        }
    }
    reading2?.let { (k, n) ->
        androidx.compose.material3.AlertDialog(onDismissRequest = {}, title = { Text(stringResource(R.string.pdf_recognizing)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.pdf_recognizing_page, k, n))
                androidx.compose.material3.LinearProgressIndicator(progress = { (k - 1f) / n }, modifier = Modifier.fillMaxWidth())
            } },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { ocrJob?.cancel(); reading2 = null }) { Text(stringResource(R.string.common_stop), maxLines = 1, overflow = TextOverflow.Ellipsis) } })
    }
    reading?.let { words ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { reading = null }, title = { Text(stringResource(R.string.pdf_note)) }, text = { Text(words) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { reading = null }) { Text(stringResource(R.string.common_close), maxLines = 1, overflow = TextOverflow.Ellipsis) } })
    }
    // File › AutoSave (Words' own): a few seconds after the last change, saved
    // AutoSave: after half a minute without a change (Undo goes back only to a save), when Tetra goes to the background,
    // and on leaving editing; not while "Save the changes?" asks, nor a gallery's preview
    LaunchedEffect(version, autoSave, editing, typing == null, leaving, artGallery == null) {
        if (autoSave && editing && typing == null && !leaving && artGallery == null && doc?.dirty == true) { delay(30_000); save() } }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle, autoSave, editing) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP && autoSave && editing && artGallery == null) { putTyped(); if (doc?.dirty == true) save() } }
        lifecycle.lifecycle.addObserver(o); onDispose { lifecycle.lifecycle.removeObserver(o) }
    }
    if (officeSettings) OfficeSettingsDialog { officeSettings = false }
    // File › Save as and Rename (Words' and Slides'): beside it, a PDF; Save as goes on with the new one
    naming?.let { how -> file?.let { f ->
        var typedName by remember { mutableStateOf(if (how == "as") "${f.nameWithoutExtension} (2)" else f.nameWithoutExtension) }
        var into by remember { mutableStateOf(f.parentFile) } // Save as: the folder chosen (Rename stays beside)
        androidx.compose.material3.AlertDialog(onDismissRequest = { naming = null }, title = { Text(stringResource(if (how == "as") R.string.words_save_as else R.string.common_rename)) },
            text = { val field = @Composable { androidx.compose.material3.OutlinedTextField(typedName, { typedName = it }, singleLine = true, label = { Text(stringResource(R.string.words_file_name)) }, suffix = { Text(".pdf") }) }
                if (how == "as") SaveAsWhere(into, { into = it }) { field() } else field() },
            confirmButton = { androidx.compose.material3.TextButton(onClick = {
                val t = typedName.trim().removeSuffix(".pdf").takeIf { it.isNotEmpty() && '/' !in it }?.let { File(if (how == "as") into else f.parentFile, "$it.pdf") }
                    ?: run { Toast.makeText(context, R.string.words_not_a_file_name, Toast.LENGTH_SHORT).show(); return@TextButton }
                if (t == f) { naming = null; return@TextButton }
                if (t.exists()) { Toast.makeText(context, context.getString(R.string.words_name_taken, t.name), Toast.LENGTH_LONG).show(); return@TextButton }
                naming = null
                if (how == "as") save(to = t) { file = t; WriterLog.i("PDF: saved as") }
                else scope.launch {
                    // the file and its versions under the new name (as Words and Slides rename)
                    val ok = withContext(Dispatchers.IO) { runCatching { val v = versionsDir(context, f)
                        check(f.renameTo(t)) { "Could not rename ${f.name}." }; if (v.isDirectory) v.renameTo(versionsDir(context, t)) }.onFailure { WriterLog.e("PDF: rename", it) }.isSuccess }
                    if (ok) { file = t; Toast.makeText(context, context.getString(R.string.words_renamed, t.name), Toast.LENGTH_SHORT).show() } else Toast.makeText(context, R.string.words_could_not_rename, Toast.LENGTH_LONG).show()
                }
            }) { Text(stringResource(if (how == "as") R.string.common_save else R.string.common_rename), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { naming = null }) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) } })
    } }
    // File › About this file (as Words' and Slides'): its kind, size, date, pages and their size, its own details
    if (about) androidx.compose.material3.AlertDialog(onDismissRequest = { about = false },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { about = false }) { Text(stringResource(R.string.common_close), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        title = { Text(file?.name ?: stringResource(R.string.app_pdf)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val d = doc
            Text(d?.info("format")?.let { stringResource(R.string.pdf_document_format, it) } ?: stringResource(R.string.pdf_document))
            file?.let { Text(stringResource(R.string.pdf_size_date, "%.1f".format(it.length() / 1024.0 / 1024.0), java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.lastModified())))) }
            d?.sizes?.let { ss -> val s0 = ss.firstOrNull()
                Text(listOfNotNull(pluralStringResource(R.plurals.pdf_pages, ss.size, ss.size), s0?.let { PdfPaper.name(it, paperWay, unitName) }).joinToString(" · ") +
                    if (ss.distinct().size > 1) " " + stringResource(R.string.pdf_not_all_same_size) else "") }
            listOf(R.string.pdf_meta_title to com.artifex.mupdf.fitz.Document.META_INFO_TITLE, R.string.pdf_meta_author to com.artifex.mupdf.fitz.Document.META_INFO_AUTHOR,
                R.string.pdf_meta_creator to com.artifex.mupdf.fitz.Document.META_INFO_CREATOR, R.string.pdf_meta_producer to com.artifex.mupdf.fitz.Document.META_INFO_PRODUCER)
                .forEach { (label, key) -> d?.info(key)?.let { Text(stringResource(label, it), style = MaterialTheme.typography.bodySmall) } }
        } })
    if (showVersions) file?.let { f -> VersionsDialog(f, "", { showVersions = false }, about = stringResource(R.string.pdf_versions_about)) { v ->
        showVersions = false
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { keepPrevious(context, f); doc?.close(); v.copyTo(f, overwrite = true) } }
            withContext(Dispatchers.IO) { runCatching { PdfDoc(f, password) } }.onSuccess { doc = it; version++ }
            Toast.makeText(context, R.string.pdf_version_back, Toast.LENGTH_SHORT).show()
        }
    } }
    // a keyboard's shortcuts, as Slides' (asked 24 Oct: "look how the other applets work"): the screen holds the keys while nothing is typed in
    val keys = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(editing, typing == null) { if (typing == null) runCatching { keys.requestFocus() } }
    // the ribbon while editing: at the bottom for a finger, under the top bar while a mouse is in use or as Settings say (Words')
    val ribbon: @Composable () -> Unit = {
    // the ribbon while editing, at the bottom as Slides' (a tablet: the floating island; a phone: across the bottom)
    val d = doc
    // typing on the page: Font's strip in the ribbon's place, as Words' and Slides'; a card tapped shows on the words at once
    val before = fontBefore
    val ch0 = chosen?.takeIf { it.text != null }
    if (artGallery != null) DesignGalleryStrip(DesignGallery.WordArt, artGallery, cancel = { artClosed(false) }, ok = { artClosed(true) }, pick = ::artPick)
    else if (typing != null && before != null) DesignGalleryStrip(DesignGallery.Font, FontChoice(textLook.font), cancel = { textLook = textLook.copy(font = before); fontBefore = null },
        ok = { fontBefore = null }, pick = { ch -> (ch as? FontChoice)?.let { textLook = textLook.copy(font = it.name) } }, fonts = STYLE_FONTS, now = textLook.font)
    // a chosen text: the font a card shows put on at once (Cancel puts the one before back)
    else if (ch0 != null && before != null) ch0.text!!.let { ct -> fun inFont(f: String) = ct.look.copy(font = f).let { l -> ct.with(fonted(ct.doc.paras, l)).with(l) }
        DesignGalleryStrip(DesignGallery.Font, FontChoice(ct.look.font),
            cancel = { if (ct.look.font != before) rechange(t = inFont(before)); fontBefore = null },
            ok = { fontBefore = null }, pick = { c -> (c as? FontChoice)?.let { rechange(t = inFont(it.name)) } },
            fonts = STYLE_FONTS, now = ct.look.font) }
    // Home with nothing chosen: the next text's font
    else if (before != null && editing) DesignGalleryStrip(DesignGallery.Font, FontChoice(textLook.font), cancel = { textLook = textLook.copy(font = before); fontBefore = null },
        ok = { fontBefore = null }, pick = { ch -> (ch as? FontChoice)?.let { textLook = textLook.copy(font = it.name) } }, fonts = STYLE_FONTS, now = textLook.font)
    else if (editing && d != null) {
        val w = Color.White
        Surface(color = WORDS_RIBBON, contentColor = w, modifier = (if (wide) Modifier.fillMaxWidth().then(if (OfficeUi.top) Modifier else Modifier.bottomBarPadding()).padding(12.dp).clip(MaterialTheme.shapes.extraLarge) else Modifier.fillMaxWidth())
            .then(if (typing != null) Modifier.imePadding() else Modifier)) {
            // a phone typing with its keyboard up: Words' bar on the keyboard (B, I, U, highlighter, dictation, ⋯ the whole ribbon, the keyboard away)
            val imeOpen = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(LocalDensity.current) > 0
            LaunchedEffect(imeOpen, typing != null) { if (!imeOpen || typing == null) ribbonOverKeyboard = false }
            if (typing != null && imeOpen && !wide && !ribbonOverKeyboard) {
                val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                val span = typed.currentSpanStyle
                Column { SpellingBar(spell); KeyboardBar(w, (span.fontWeight?.weight ?: 400) >= 600, span.fontStyle == androidx.compose.ui.text.font.FontStyle.Italic,
                    span.textDecoration?.contains(androidx.compose.ui.text.style.TextDecoration.Underline) == true,
                    { typed.toggleSpanStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)) },
                    { typed.toggleSpanStyle(androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) },
                    { typed.toggleSpanStyle(androidx.compose.ui.text.SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)) },
                    { c -> if (c == null) span.background.takeIf { it != Color.Unspecified }?.let { typed.removeSpanStyle(androidx.compose.ui.text.SpanStyle(background = it)) }
                        else typed.addSpanStyle(androidx.compose.ui.text.SpanStyle(background = c)) },
                    { dictate?.invoke() ?: Toast.makeText(context, R.string.words_no_voice_typing, Toast.LENGTH_SHORT).show() }, { ribbonOverKeyboard = true }, { keyboard?.hide() }) }
            } else
            OfficeStripFrame(w, if (wide) Modifier.padding(vertical = 4.dp) else if (OfficeUi.top) Modifier else Modifier.bottomBarPadding()) { Column {
                // a text chosen, as a Slides box: Selection first (Cut, Copy, Paste, formatting), the text's own tab last in the lighter colour
                // Words' tabs in Words' order (the user, 24 Oct: "take a look on what the other applets have"), the PDF's own among them
                val tabNames = mapOf("Selection" to stringResource(R.string.words_tab_selection), "File" to stringResource(R.string.words_tab_file),
                    "Home" to stringResource(R.string.words_tab_home), "Insert" to stringResource(R.string.words_tab_insert), "Draw" to stringResource(R.string.words_tab_draw), "Design" to stringResource(R.string.words_tab_design),
                    "Layout" to stringResource(R.string.words_tab_layout), "Pages" to stringResource(R.string.words_pages), "Review" to stringResource(R.string.words_tab_review),
                    "Fill & Sign" to stringResource(R.string.pdf_tab_fill_sign), "Protect" to stringResource(R.string.pdf_tab_protect), "View" to stringResource(R.string.words_tab_view),
                    OBJECT_TAB to (chosen?.kind?.let { if (it == "Drawing") stringResource(R.string.pdf_drawing) else objectKindName(it) } ?: ""),
                    "Video" to stringResource(if (media.chosen?.second?.name?.let(OfficeVideo::audio) == true) R.string.slides_audio else R.string.words_video))
                // reading aloud: the paragraph being read and Stop, above the tabs (Words')
                if (reader.speaking) ReadAloudBar(reader, readingParas.getOrNull(reader.at), w)
                if (typing != null) SpellingBar(spell)
                OfficeTabRow(listOfNotNull("Selection".takeIf { chosen != null }, "File", "Home", "Insert", "Draw", "Design", "Layout", "Pages", "Review", "Fill & Sign", "Protect", "View",
                    OBJECT_TAB.takeIf { chosen != null }, "Video".takeIf { media.chosen != null }),
                    tab, w, PDF_RED, { tab = it; tool = null }, contextual = if (media.chosen != null) "Video" else OBJECT_TAB, label = { tabNames[it] ?: it }) {
                    // typing: ✓ puts the words on; an object chosen: ✕ lets go of it (Words')
                    if (ribbonOverKeyboard) Tool(R.drawable.ic_more_horiz, stringResource(R.string.words_back_to_keyboard_bar), w, on = true) { ribbonOverKeyboard = false }
                    else if (typing != null) Tool(R.drawable.ic_check, stringResource(R.string.common_done), w, on = true) { putTyped() }
                    else if (chosen != null) androidx.compose.material3.IconButton(onClick = { chosen = null }, modifier = Modifier.size(36.dp)) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_close), modifier = Modifier.size(16.dp)) }
                }
                @Composable fun ToolButton(t: PdfTool) = RibbonButton(stringResource(t.label), w, t.icon, on = tool == t) { tool = if (tool == t) null else t }
                // Words' colours, white among them (the user, 25 Oct: to cover what was drawn), and its highlighters: the marks' and pens' colour
                @Composable fun MarkColours() = Swatches(w, R.drawable.ic_palette, stringResource(R.string.words_colour), (HIGHLIGHT_COLOURS + TEXT_COLOURS).distinct()) { c ->
                    c?.let { markColour = (it.toArgb() and 0xFFFFFF); penColour = markColour } }
                key(tab) { RibbonLine(androidx.compose.foundation.rememberScrollState(), Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                    val c0 = chosen
                    // Words', Tables' and Slides' File: the file's own actions
                    // Words' Video tab: Play (a film or sound the PDF carries)
                    if (tab == "Video") { media.chosen?.let { (pi, sp) -> RibbonButton(stringResource(R.string.words_play), w, R.drawable.ic_play) { scope.launch { media.play(d, pi, sp, context.cacheDir) } } }
                        return@RibbonLine }
                    if (tab == "File") {
                        RibbonButton(stringResource(R.string.common_save), w, R.drawable.ic_save, enabled = d.dirty) { save() }
                        RibbonButton(stringResource(R.string.words_save_copy), w, R.drawable.ic_copy, enabled = file != null) {
                            file?.let { f -> save(to = generateSequence(1) { it + 1 }.map { File(f.parentFile, "${f.nameWithoutExtension} (copy${if (it == 1) "" else " $it"}).pdf") }.first { !it.exists() }, copy = true) } }
                        // the whole file written smaller on its next save (a whole-file action: File's, beside Save a copy)
                        RibbonButton(stringResource(R.string.pdf_reduce_size), w, R.drawable.ic_compress) {
                            d.shrink(); version++
                            Toast.makeText(context, context.getString(R.string.pdf_shrink_note, (file?.length() ?: 0) / 1024), Toast.LENGTH_LONG).show()
                        }
                        RibbonButton(stringResource(R.string.words_autosave), w, R.drawable.ic_history, on = autoSave) {
                            autoSave = !autoSave; TextEditorStore(context).autoSave = autoSave
                            Toast.makeText(context, if (autoSave) R.string.words_autosave_on else R.string.words_autosave_off_msg, Toast.LENGTH_SHORT).show() }
                        FavouriteFileButton(file, w) // a phone: the Office home's favourite (a tablet: the heart in the top bar)
                        RibbonButton(stringResource(R.string.words_save_as), w, R.drawable.ic_save, enabled = file != null) { naming = "as" }
                        RibbonButton(stringResource(R.string.common_rename), w, R.drawable.ic_edit, enabled = file != null) { naming = "rename" }
                        RibbonDivider(w)
                        RibbonButton(stringResource(R.string.words_versions), w, R.drawable.ic_history, enabled = file != null) { showVersions = true }
                        RibbonButton(stringResource(R.string.pdf_edit_in_words), w, R.drawable.ic_edit_note) { exportWords() }
                        RibbonButton(stringResource(R.string.pdf_export_text), w, R.drawable.ic_text_fields) { exportText() }
                        if (Pdf.hasText) RibbonButton(stringResource(R.string.pdf_copy_page_text), w, R.drawable.ic_copy) { copyPage() }
                        RibbonButton(stringResource(R.string.common_print), w, R.drawable.ic_print) { if (d.dirty) save { print() } else print() }
                        file?.let { f ->
                            RibbonDivider(w)
                            // a change saved first, so what goes is what shows (Slides')
                            RibbonButton(stringResource(R.string.common_share), w, R.drawable.ic_share) { if (d.dirty) save { shareDriveFile(context, f) } else shareDriveFile(context, f) }
                            RibbonButton(stringResource(R.string.words_open_in_another_app), w, R.drawable.ic_open_with) { if (d.dirty) save { openElsewhere(context, f) } else openElsewhere(context, f) }
                        }
                        RibbonDivider(w)
                        RibbonButton(stringResource(R.string.words_about_file), w, R.drawable.ic_info) { about = true }
                        RibbonButton(stringResource(R.string.words_send_log), w, R.drawable.ic_share) { WriterLog.export(context)?.let { shareDriveFile(context, it) } }
                        RibbonDivider(w)
                        RibbonButton(stringResource(R.string.common_settings), w, R.drawable.ic_settings) { officeSettings = true }
                        return@RibbonLine
                    }
                    // Words' Home: the words being typed, a chosen text's (all of them), else the font, size and colour the next text takes
                    if (tab == "Home") {
                        PdfHomeTools(w, homeTarget, homeTarget?.text?.look?.takeIf { typing == null } ?: textLook, { textLook = it }, { fontBefore = (homeTarget?.text?.look?.takeIf { typing == null } ?: textLook).font }) { at, look -> paragraphPage = at to look }
                        return@RibbonLine
                    }
                    // Layout, as Words' and Tables': the pages' size and way round (all of them, as Word's without sections), the margins
                    if (tab == "Layout") {
                        // Words' Layout: margins, orientation, the chosen text's paragraph, size (a page's own turns, deletes and blanks: Pages)
                        val now = d.sizes.getOrNull(at)
                        RibbonButton(stringResource(R.string.words_margins), w, R.drawable.ic_margin) { inserting = "page" }
                        RibbonButton(stringResource(R.string.words_portrait), w, R.drawable.ic_crop_portrait, on = now != null && now.height >= now.width) {
                            change { d.resize { s -> if (s.width > s.height) Size(s.height, s.width) else s } }; WriterLog.i("PDF: portrait") }
                        RibbonButton(stringResource(R.string.words_landscape), w, R.drawable.ic_crop_landscape, on = now != null && now.width > now.height) {
                            change { d.resize { s -> if (s.height > s.width) Size(s.height, s.width) else s } }; WriterLog.i("PDF: landscape") }
                        RibbonDivider(w)
                        PdfParagraphBoxes(w, homeTarget)
                        RibbonDivider(w)
                        RibbonButton(stringResource(R.string.words_size), w, R.drawable.ic_fit_page) { inserting = "page" }
                        return@RibbonLine
                    }
                    // View, as Words': the page list, comments, bookmarks; the zoom; one or two pages, as wide as the screen; rulers; the unit
                    if (tab == "View") {
                        RibbonButton(stringResource(R.string.words_navigation), w, R.drawable.ic_navigation, on = if (wide) navigation else pages) { if (wide) navigation = !navigation else pages = !pages }
                        RibbonButton(stringResource(R.string.words_comments), w, R.drawable.ic_comment, on = pane == "Comments" || commentsOpen) { if (wide) pane = if (pane == "Comments") null else "Comments" else commentsOpen = true }
                        RibbonButton(stringResource(R.string.pdf_bookmarks), w, R.drawable.ic_bookmark, on = pane == "Bookmarks") { pane = if (pane == "Bookmarks") null else "Bookmarks" }
                        RibbonDivider(w)
                        Tool(R.drawable.ic_remove, stringResource(R.string.words_zoom_out), w, enabled = zoom > 1f) { zoom = (zoom - 0.25f).coerceAtLeast(1f); pan = Offset.Zero; settled = zoom }
                        Text("${Math.round(zoom * 100)}%", color = w, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(44.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Tool(R.drawable.ic_add, stringResource(R.string.words_zoom_in), w, enabled = zoom < Pdf.MAX_ZOOM) { zoom = (zoom + 0.25f).coerceAtMost(Pdf.MAX_ZOOM); pan = Offset.Zero; settled = zoom }
                        RibbonDivider(w)
                        RibbonButton(stringResource(R.string.words_fit_width), w, R.drawable.ic_fit_page, on = fitWidth) { val keep = at; fitWidth = !fitWidth; goTo(keep) }
                        RibbonButton(stringResource(R.string.words_two_pages), w, R.drawable.ic_view_column, on = across == 2) { val keep = at; across = 3 - across; goTo(keep) }
                        if (wide) RibbonButton(stringResource(R.string.pdf_ruler), w, R.drawable.ic_margin, on = rulers) { rulers = !rulers }
                        RibbonDivider(w)
                        RibbonButton(stringResource(R.string.words_unit_cm), w, on = !WriterUnits.inches) { WriterUnits.set(context, false) }
                        RibbonButton(stringResource(R.string.words_unit_inches), w, on = WriterUnits.inches) { WriterUnits.set(context, true) }
                        return@RibbonLine
                    }
                    // Words' Selection tab: what is chosen cut, copied, pasted; words pasted alone; a text's look copied and put on another
                    if (tab == "Selection" && c0 != null) {
                        val ct = c0.text
                        RibbonButton(stringResource(R.string.common_cut), w, R.drawable.ic_content_cut, enabled = c0.ours) { if (copyChosen()) deleteChosen() }
                        RibbonButton(stringResource(R.string.common_copy), w, R.drawable.ic_copy, enabled = c0.ours) { copyChosen() }
                        RibbonButton(stringResource(R.string.common_paste), w, R.drawable.ic_content_paste, enabled = clip != null) { paste(c0.page) }
                        RibbonButton(stringResource(R.string.words_paste_text_only), w, R.drawable.ic_content_paste) { val keep = clip; clip = null; paste(c0.page); clip = keep }
                        RibbonDivider(w)
                        RibbonButton(stringResource(R.string.words_copy_formatting), w, R.drawable.ic_format_paint, enabled = ct != null) { ct?.let { copiedLook = it.look; Toast.makeText(context, R.string.pdf_formatting_copied, Toast.LENGTH_SHORT).show() } }
                        // its turn, fill and border its own, as Slides' (the words' look only)
                        RibbonButton(stringResource(R.string.words_paste_formatting), w, R.drawable.ic_format_paint, enabled = ct != null && copiedLook != null) { val l = copiedLook; if (ct != null && l != null) {
                            rechange(t = ct.copy(look = l.copy(turn = ct.look.turn, fill = ct.look.fill, line = ct.look.line))); WriterLog.i("PDF: formatting pasted") } }
                        return@RibbonLine
                    }
                    // the chosen object's own tab, named by its kind, as Words' Picture tab (the user, 26 Oct: "the same ribbon tabs and items in the same places")
                    if (tab == OBJECT_TAB && c0 != null) {
                        val o = c0.words; val img = o?.img
                        val ours = c0.ours; val turns = turnable(c0)
                        val picture = img != null && o.bytes() != null && img.box == null && img.chart == null && img.math == null && img.group == null
                        if (c0.kind == "Signature") {
                            // a signature's own tab, as Words' (Size, Position, Rotate | Bring to front, Send to back | Redraw, Delete)
                            RibbonButton(stringResource(R.string.words_size), w, R.drawable.ic_aspect_ratio) { sizing = true }
                            RibbonButton(stringResource(R.string.words_position), w, R.drawable.ic_open_with) { positioning = true }
                            RibbonButton(stringResource(R.string.words_rotate), w, R.drawable.ic_rotate_right, enabled = turns) { rotating = true }
                            RibbonDivider(w)
                            RibbonButton(stringResource(R.string.words_bring_to_front), w, R.drawable.ic_flip_to_front) { restackChosen(2) }
                            RibbonButton(stringResource(R.string.words_send_to_back), w, R.drawable.ic_flip_to_back) { restackChosen(-2) }
                            RibbonDivider(w)
                            RibbonButton(stringResource(R.string.words_redraw), w, R.drawable.ic_draw) { redrawing = true }
                            RibbonButton(stringResource(R.string.common_delete), w, R.drawable.ic_delete) { deleteChosen() }
                            return@RibbonLine
                        }
                        if (c0.text != null) RibbonButton(stringResource(R.string.words_edit_text), w, R.drawable.ic_edit) { retype(c0) }
                        // Words' own for its kinds: a text box's or WordArt's words, a drawing drawn on again, a chart's data, an equation
                        if (img?.box != null) RibbonButton(stringResource(R.string.words_edit_text), w, R.drawable.ic_edit) { inserting = "boxText" }
                        // a shape's own words (Word's Add Text): typed in the box's window, in the shape's middle
                        if (img != null && img.box == null && img.group == null && ShapePicture.of(img)?.geometry?.let { it != "line" && it != "straightConnector1" } == true)
                            RibbonButton(stringResource(R.string.words_add_text), w, R.drawable.ic_edit) { inserting = "boxText" }
                        if (img != null && remember(c0.id) { o.bytes()?.let(InkPicture::strokesOf) != null }) RibbonButton(stringResource(R.string.words_edit_drawing), w, R.drawable.ic_draw) { editingInk = true }
                        if (img?.chart != null) RibbonButton(stringResource(R.string.words_edit_data), w, R.drawable.ic_bar_chart) { inserting = "chartEdit" }
                        if (img?.math != null) RibbonButton(stringResource(R.string.words_edit_equation), w, R.drawable.ic_functions) { inserting = "mathEdit" }
                        if (c0.text != null || img != null && img.group == null && ShapePicture.of(img) != null) RibbonButton(stringResource(R.string.words_shape_options), w, R.drawable.ic_tune) { shapeOptions = true }
                        RibbonButton(stringResource(R.string.words_size), w, R.drawable.ic_aspect_ratio) { sizing = true }
                        if (picture) RibbonButton(stringResource(R.string.words_crop), w, R.drawable.ic_crop) { scope.launch {
                            cropping = withContext(Dispatchers.IO) { o.bytes()?.let { runCatching { OfficePages.croppedPicture(img, it, 2400) }.getOrNull() } }
                            if (cropping == null) Toast.makeText(context, R.string.words_cannot_crop, Toast.LENGTH_SHORT).show()
                        } }
                        RibbonDivider(w)
                        RibbonButton(stringResource(R.string.words_position), w, R.drawable.ic_open_with) { positioning = true }
                        RibbonDivider(w)
                        RibbonButton(stringResource(R.string.words_bring_to_front), w, R.drawable.ic_flip_to_front) { restackChosen(2) }
                        RibbonButton(stringResource(R.string.words_send_to_back), w, R.drawable.ic_flip_to_back) { restackChosen(-2) }
                        RibbonButton(stringResource(R.string.words_rotate), w, R.drawable.ic_rotate_right, enabled = turns) { rotating = true }
                        // Words' Group (the next one tapped joins it) and Ungroup
                        if (img?.group != null) RibbonButton(stringResource(R.string.words_ungroup), w, R.drawable.ic_group_objects) { ungroupChosen() }
                        else if (img != null && c0.text == null) RibbonButton(stringResource(if (grouping?.second == c0.id) R.string.words_tap_another else R.string.words_group), w,
                            R.drawable.ic_group_objects, on = grouping?.second == c0.id) {
                            grouping = if (grouping?.second == c0.id) null else (c0.page to c0.id).also { Toast.makeText(context, R.string.words_tap_other_to_group, Toast.LENGTH_SHORT).show() } }
                        RibbonMenu(stringResource(R.string.words_align), w, R.drawable.ic_align_horizontal_center, preset = false, choices = listOf(
                            MenuChoice(stringResource(R.string.words_align_left)) { alignChosen('l', null) }, MenuChoice(stringResource(R.string.words_align_centre)) { alignChosen('c', null) },
                            MenuChoice(stringResource(R.string.words_align_right)) { alignChosen('r', null) }, MenuChoice(stringResource(R.string.words_align_top)) { alignChosen(null, 't') },
                            MenuChoice(stringResource(R.string.words_align_middle)) { alignChosen(null, 'm') }, MenuChoice(stringResource(R.string.words_align_bottom)) { alignChosen(null, 'b') }))
                        if (picture) {
                            RibbonDivider(w)
                            RibbonButton(stringResource(R.string.words_turn_left), w, R.drawable.ic_rotate_left) { transformChosen(-90, false) }
                            RibbonButton(stringResource(R.string.words_turn_right), w, R.drawable.ic_rotate_right) { transformChosen(90, false) }
                            RibbonButton(stringResource(R.string.words_flip), w, R.drawable.ic_flip) { transformChosen(0, true) }
                        }
                        RibbonDivider(w)
                        if (picture) RibbonButton(stringResource(R.string.words_replace), w, R.drawable.ic_gallery) { replacing = true; pickImage.launch(arrayOf("image/*")) }
                        if (picture) RibbonButton(stringResource(R.string.words_save_as), w, R.drawable.ic_save) { savePicture(o) }
                        RibbonButton(stringResource(R.string.pdf_duplicate), w, R.drawable.ic_add_to_collection, enabled = ours) { duplicateChosen() }
                        RibbonButton(stringResource(R.string.common_delete), w, R.drawable.ic_delete) { deleteChosen() }
                        return@RibbonLine
                    }
                    if (tab == "Review") {
                        // Words' Review: Spelling and the next mistake (in the words being typed); Read aloud; the comments, new, the one
                        // before or after, Reply, Resolve, Delete — then the PDF's own markup
                        RibbonButton(stringResource(R.string.words_spelling), w, R.drawable.ic_spellcheck, on = spellingOn) {
                            spellingOn = !spellingOn; writerPrefs.edit().putBoolean("spelling", spellingOn).apply()
                            Toast.makeText(context, if (spellingOn) R.string.words_spelling_on_msg else R.string.words_spelling_off_msg, Toast.LENGTH_SHORT).show()
                        }
                        Tool(R.drawable.ic_chevron_right, stringResource(R.string.words_next_spelling_mistake), w, enabled = spellingOn && typing != null) {
                            if (!spell.next()) Toast.makeText(context, R.string.words_no_spelling_mistakes, Toast.LENGTH_SHORT).show() }
                        RibbonButton(stringResource(if (reader.speaking) R.string.words_stop_reading else R.string.words_read_aloud), w, R.drawable.ic_volume_up, on = reader.speaking) {
                            if (reader.speaking) reader.stop() else scope.launch {
                                val pages = withContext(Dispatchers.IO) { d.sizes.indices.map { k -> runCatching { d.text(k) }.getOrDefault("") } }
                                // a page's lines put back into paragraphs (a blank line between them); from the page shown
                                val byPage = pages.map { t -> t.split(Regex("\n\\s*\n")).map { it.replace('\n', ' ').trim() }.filter { it.isNotEmpty() } }
                                readingParas = byPage.flatten()
                                if (!reader.read(readingParas, byPage.take(at).sumOf { it.size })) Toast.makeText(context, R.string.words_no_tts, Toast.LENGTH_SHORT).show()
                            }
                        }
                        RibbonDivider(w)
                        RibbonButton(stringResource(R.string.words_comments), w, R.drawable.ic_comment, on = pane == "Comments" || commentsOpen) { if (wide) pane = if (pane == "Comments") null else "Comments" else commentsOpen = true }
                        RibbonButton(stringResource(R.string.words_new_comment), w, R.drawable.ic_comment, on = tool == PdfTool.Note) { tool = if (tool == PdfTool.Note) null else PdfTool.Note }
                        val tops = commentList.filter { it.replyTo == null }
                        fun goComment(next: Boolean) { if (tops.isEmpty()) return; val k = tops.indexOfFirst { it.page == hereComment?.page && it.index == hereComment?.index }
                            val n = if (k < 0) (if (next) 0 else tops.size - 1) else Math.floorMod(k + if (next) 1 else -1, tops.size); hereComment = tops[n]; goTo(tops[n].page) }
                        Tool(R.drawable.ic_chevron_left, stringResource(R.string.words_previous_comment), w, enabled = tops.isNotEmpty()) { goComment(false) }
                        Tool(R.drawable.ic_chevron_right, stringResource(R.string.words_next_comment), w, enabled = tops.isNotEmpty()) { goComment(true) }
                        RibbonDivider(w)
                        val hc = hereComment
                        RibbonButton(stringResource(R.string.words_reply), w, R.drawable.ic_comment, enabled = hc != null) { hc?.let { commentAction = "reply" to it } }
                        RibbonButton(stringResource(if (hc?.done == true) R.string.words_reopen else R.string.words_resolve), w, R.drawable.ic_check, enabled = hc != null) { hc?.let { c -> change { d.resolve(c, !c.done) } } }
                        RibbonButton(stringResource(R.string.words_delete_comment), w, R.drawable.ic_delete, enabled = hc != null) { hc?.let { c -> change { d.deleteComment(c) }; hereComment = null } }
                        RibbonDivider(w)
                        // the page's own words marked (the pens and shapes are Draw's)
                        ToolButton(PdfTool.Highlight); ToolButton(PdfTool.Underline); ToolButton(PdfTool.Strike)
                        MarkColours()
                        return@RibbonLine
                    }
                    if (tab == "Insert") {
                        // Words' Insert, its groups in its order (the page break, table, video, link, drop cap and line break: a PDF's pages are fixed);
                        // its page group first: another PDF's pages after this one (the user, 27 Oct: "place it on insert at the beginning"), a blank page
                        val page = at
                        RibbonButton(stringResource(R.string.pdf_insert_pdf), w, R.drawable.ic_add_to_collection) { pickPdf.launch(arrayOf("application/pdf")) }
                        RibbonButton(stringResource(R.string.words_blank_page), w, R.drawable.ic_note_add) { change { d.insertBlank(page + 1) }; scope.launch { delay(100); goTo(page + 1) } }
                        RibbonDivider(w)
                        RibbonButton(stringResource(R.string.words_picture), w, R.drawable.ic_gallery) { pickImage.launch(arrayOf("image/*")) }
                        RibbonMenu(stringResource(R.string.words_signature), w, R.drawable.ic_draw, on = tool == PdfTool.Sign, preset = false, choices = listOf(
                            MenuChoice(stringResource(if (signatureFile.isFile) R.string.words_put_my_signature else R.string.words_draw_my_signature), icon = R.drawable.ic_draw) { if (!signatureFile.isFile) signing = true else tool = PdfTool.Sign },
                            MenuChoice(stringResource(R.string.words_draw_new_one), icon = R.drawable.ic_edit, opens = true) { signing = true },
                            MenuChoice(stringResource(R.string.words_from_picture_of_it), icon = R.drawable.ic_gallery, opens = true) { pickSignaturePhoto.launch(arrayOf("image/*")) }))
                        RibbonButton(stringResource(R.string.words_shapes), w, R.drawable.ic_category) { inserting = "shapes" }
                        RibbonDivider(w)
                        RibbonButton(stringResource(R.string.words_text_box), w, R.drawable.ic_text_box, on = tool == PdfTool.TextBox) { tool = if (tool == PdfTool.TextBox) null else PdfTool.TextBox }
                        RibbonButton(stringResource(R.string.words_wordart), w, R.drawable.ic_format_color_text) { artGallery = null to null }
                        RibbonButton(stringResource(R.string.words_chart), w, R.drawable.ic_bar_chart) { inserting = "chart" }
                        // Stamp: the kind chosen in a strip (✓/×, as Office's options), then a tap on the page puts it there
                        val stampStrip = officeStrip(stringResource(R.string.pdf_stamp), ok = { st -> (st.picked as? String)?.let { stampName = it; tool = PdfTool.Stamp } }) { st ->
                            STAMPS.forEach { n -> RibbonButton(n.replace(Regex("(?<=[a-z])(?=[A-Z])"), " "), w, on = st.picked == n) { st.picked = n } }
                            val strips = LocalOfficeStrips.current
                            RibbonButton(stringResource(R.string.pdf_picture_more), w, R.drawable.ic_gallery) { strips?.open = null; pickStamp.launch(arrayOf("image/*")) }
                        }
                        RibbonButton(if (tool != PdfTool.Stamp) stringResource(R.string.pdf_stamp) else if (stampName == PICTURE_STAMP) stringResource(R.string.words_picture)
                            else stampName.replace(Regex("(?<=[a-z])(?=[A-Z])"), " "), w, R.drawable.ic_branding_watermark, on = tool == PdfTool.Stamp) {
                            if (stampStrip != null) stampStrip() else tool = if (tool == PdfTool.Stamp) null else PdfTool.Stamp
                        }
                        RibbonDivider(w)
                        // Words' Links: on the chosen text or object (a PDF link covers a part of the page)
                        RibbonButton(stringResource(R.string.words_link), w, R.drawable.ic_link, enabled = chosen != null && typing == null) { askLink() }
                        RibbonDivider(w)
                        RibbonButton(stringResource(R.string.words_comment), w, R.drawable.ic_comment, on = tool == PdfTool.Note) { tool = if (tool == PdfTool.Note) null else PdfTool.Note }
                        RibbonDivider(w)
                        RibbonButton(stringResource(R.string.words_header_footer), w, R.drawable.ic_page_header) { asking = "header" }
                        RibbonButton(stringResource(R.string.words_page_numbers), w, R.drawable.ic_numbers) { change { d.headerFooter("", "{n} / {N}", 10f) } }
                        RibbonDivider(w)
                        RibbonButton(stringResource(R.string.words_date), w, R.drawable.ic_calendar_today) { inserting = "date" }
                        RibbonButton(stringResource(R.string.words_symbol), w, R.drawable.ic_special_character) { inserting = "symbol" }
                        RibbonButton(stringResource(R.string.words_equation), w, R.drawable.ic_functions) { inserting = "equation" }
                        return@RibbonLine
                    }
                    if (tab == "Protect") {
                        RibbonButton(stringResource(R.string.pdf_password), w, R.drawable.ic_lock) { asking = "password" }
                        if (password != null) RibbonButton(stringResource(R.string.pdf_remove_password), w, R.drawable.ic_lock_open) { d.protect(null); version++
                            Toast.makeText(context, R.string.pdf_password_off_on_save, Toast.LENGTH_LONG).show() }
                        RibbonDivider(w)
                        ToolButton(PdfTool.RedactText)
                        ToolButton(PdfTool.Redact)
                        RibbonButton(stringResource(R.string.pdf_find_redact), w, R.drawable.ic_search) { asking = "redact" }
                        RibbonButton(stringResource(R.string.pdf_redact_marked), w, R.drawable.ic_check) {
                            var n = 0; change { n = d.applyRedactions() }
                            Toast.makeText(context, if (n == 0) context.getString(R.string.pdf_mark_first) else context.resources.getQuantityString(R.plurals.pdf_redacted_pages, n, n), Toast.LENGTH_LONG).show()
                        }
                        RibbonDivider(w)
                        RibbonButton(stringResource(R.string.pdf_remove_hidden), w, R.drawable.ic_visibility_off) { d.removeHidden(); version++; Toast.makeText(context, R.string.pdf_hidden_removed, Toast.LENGTH_LONG).show() }
                        return@RibbonLine
                    }
                    if (tab == "Fill & Sign") {
                        ToolButton(PdfTool.Text); ToolButton(PdfTool.Tick); ToolButton(PdfTool.Cross); ToolButton(PdfTool.Dot); ToolButton(PdfTool.Date)
                        RibbonDivider(w)
                        ToolButton(PdfTool.TextField); ToolButton(PdfTool.CheckBox)
                        RibbonDivider(w)
                        // Words' Signature: put yours where tapped, draw a new one, or one from a photo of it on paper
                        RibbonMenu(stringResource(R.string.words_signature), w, R.drawable.ic_draw, on = tool == PdfTool.Sign, preset = false, choices = listOf(
                            MenuChoice(stringResource(if (signatureFile.isFile) R.string.words_put_my_signature else R.string.words_draw_my_signature), icon = R.drawable.ic_draw) { if (!signatureFile.isFile) signing = true else tool = PdfTool.Sign },
                            MenuChoice(stringResource(R.string.words_draw_new_one), icon = R.drawable.ic_edit, opens = true) { signing = true },
                            MenuChoice(stringResource(R.string.words_from_picture_of_it), icon = R.drawable.ic_gallery, opens = true) { pickSignaturePhoto.launch(arrayOf("image/*")) }))
                        RibbonDivider(w)
                        listOf(9f to R.string.pdf_small, 11f to R.string.pdf_normal, 14f to R.string.pdf_large).forEach { (pt, label) -> RibbonButton(stringResource(label), w, on = fillSize == pt) { fillSize = pt } }
                        RibbonDivider(w)
                        ToolButton(PdfTool.Erase)
                        return@RibbonLine
                    }
                    if (tab == "Draw") {
                        // Words' Draw: the pens, the shapes drawn by hand, the colour, the eraser; then on the page as it is (drawn over it, put
                        // on it), or in a box (the pens and shapes were on Review: the user, 27 Oct, "do they make sense?")
                        ToolButton(PdfTool.Pen); ToolButton(PdfTool.Marker)
                        ToolButton(PdfTool.Rectangle); ToolButton(PdfTool.Oval); ToolButton(PdfTool.Arrow)
                        MarkColours()
                        ToolButton(PdfTool.Erase)
                        RibbonDivider(w)
                        RibbonButton(stringResource(R.string.words_draw_on_page), w, R.drawable.ic_draw) { val page = at; scope.launch { drawingOn = page to d.page(page, 1200) } }
                        RibbonButton(stringResource(R.string.words_drawing_box), w, R.drawable.ic_ink_pen) { drawingBox = true }
                        return@RibbonLine
                    }
                    if (tab == "Design") {
                        // Words' Design for a PDF: the watermark, the page colour and the page borders, on every page
                        RibbonMenu(stringResource(R.string.words_watermark), w, R.drawable.ic_branding_watermark, on = design.over, choices =
                            Watermark.PRESETS.map { t -> MenuChoice(t, checked = t == design.watermark) { change { d.setDesign(design.copy(watermark = t, picture = null)) } } } +
                            MenuChoice(stringResource(R.string.words_custom_watermark), opens = true) { asking = "watermark" } +
                            MenuChoice(stringResource(R.string.words_picture_watermark), checked = design.picture != null, opens = true, icon = R.drawable.ic_gallery) { pickWatermark.launch(arrayOf("image/*")) } +
                            listOfNotNull(MenuChoice(stringResource(R.string.words_remove_watermark), opens = true, icon = R.drawable.ic_delete) { change { d.setDesign(design.copy(watermark = null, picture = null)) } }.takeIf { design.over }))
                        Swatches(w, R.drawable.ic_format_color_fill, stringResource(R.string.words_page_colour), PAGE_COLORS.map { Color(0xFF000000 or it.toLong()) }) { c ->
                            change { d.setDesign(design.copy(color = c?.let { it.toArgb() and 0xFFFFFF }?.takeIf { it != 0xFFFFFF })) } }
                        RibbonButton(stringResource(R.string.words_page_borders), w, R.drawable.ic_border_outer, on = design.border != null) { inserting = "border" }
                        return@RibbonLine
                    }
                    val page = at
                    RibbonButton(stringResource(R.string.pdf_organize), w, R.drawable.ic_view_grid) { organize = true }
                    RibbonDivider(w)
                    RibbonButton(stringResource(R.string.pdf_rotate_left), w, R.drawable.ic_rotate_left) { change { d.rotate(listOf(page), -90) } }
                    RibbonButton(stringResource(R.string.pdf_rotate_right), w, R.drawable.ic_rotate_right) { change { d.rotate(listOf(page), 90) } }
                    RibbonButton(stringResource(R.string.pdf_delete_page), w, R.drawable.ic_delete, enabled = d.sizes.size > 1) { change { d.delete(listOf(page)) } }
                    RibbonDivider(w)
                    RibbonButton(stringResource(R.string.words_blank_page), w, R.drawable.ic_note_add) { change { d.insertBlank(page + 1) }; scope.launch { delay(100); goTo(page + 1) } }
                    RibbonButton(stringResource(R.string.pdf_duplicate_page), w, R.drawable.ic_add_to_collection) { change { d.duplicatePage(page) }; scope.launch { delay(100); goTo(page + 1) } }
                    RibbonDivider(w)
                    RibbonButton(stringResource(R.string.pdf_recognize_text), w, R.drawable.ic_text_fields) {
                        // every page without words of its own read (a scan, a photo), each one undo step; Find and Copy then work on it
                        ocrJob = scope.launch {
                            val todo = withContext(Dispatchers.IO) { d.sizes.indices.filter { !d.hasText(it) } }
                            if (todo.isEmpty()) { Toast.makeText(context, R.string.pdf_all_have_text, Toast.LENGTH_SHORT).show(); return@launch }
                            var words = 0
                            for ((k, i) in todo.withIndex()) {
                                reading2 = k + 1 to todo.size
                                val got = withContext(Dispatchers.Default) { runCatching { val (bmp, sc) = d.scanOf(i); Ocr.words(context, bmp).also { ws -> d.addTextLayer(i, ws, sc) } } }
                                got.onFailure { WriterLog.e("PDF: recognize text", it) }.onSuccess { words += it.size }
                                version++
                            }
                            reading2 = null
                            WriterLog.i("PDF: recognized ${todo.size} pages, $words words")
                            Toast.makeText(context, context.resources.getQuantityString(R.plurals.pdf_ocr_done, todo.size, words, todo.size), Toast.LENGTH_LONG).show()
                        }
                    }
                    RibbonButton(stringResource(R.string.pdf_extract_page), w, R.drawable.ic_copy) {
                        val f = file ?: return@RibbonButton
                        scope.launch {
                            val to = generateSequence(1) { it }.let { _ -> var n = 0; var t: File; do { n++; t = File(f.parentFile, "${f.nameWithoutExtension} (page ${page + 1}${if (n > 1) " $n" else ""}).pdf") } while (t.exists()); t }
                            withContext(Dispatchers.IO) { runCatching { d.extract(listOf(page), to) } }
                                .onSuccess { Toast.makeText(context, context.getString(R.string.words_saved_as, to.name), Toast.LENGTH_LONG).show() }
                                .onFailure { WriterLog.e("PDF: extract", it); Toast.makeText(context, pdfSays(context, it, R.string.pdf_extract_failed), Toast.LENGTH_LONG).show() }
                        }
                    }
                    // every page (as it is now, unsaved changes too) a picture in the scanner: filters, perspective, tilt, paint
                    RibbonButton(stringResource(R.string.pdf_open_in_scanner), w, R.drawable.ic_perspective) { openInScanner(d) }
                    RibbonMenu(stringResource(R.string.pdf_split), w, R.drawable.ic_view_column, preset = false, choices = listOf(
                        MenuChoice(stringResource(R.string.pdf_split_every)) { splitFile(true) },
                        MenuChoice(stringResource(R.string.pdf_split_here), enabled = page > 0) { splitFile(false) }))
                    RibbonMenu(stringResource(R.string.pdf_crop_margins), w, R.drawable.ic_crop, preset = false, choices = listOf(
                        MenuChoice(stringResource(R.string.pdf_this_page)) { var n = 0; change { n = d.cropToContent(listOf(page)) }; if (n == 0) Toast.makeText(context, R.string.pdf_nothing_to_crop, Toast.LENGTH_SHORT).show() },
                        MenuChoice(stringResource(R.string.pdf_every_page)) { var n = 0; change { n = d.cropToContent(d.sizes.indices.toList()) }; if (n == 0) Toast.makeText(context, R.string.pdf_nothing_to_crop, Toast.LENGTH_SHORT).show() }))
                    RibbonDivider(w)
                    RibbonButton(stringResource(R.string.pdf_flatten), w, R.drawable.ic_layers) { change { d.flatten() }; Toast.makeText(context, R.string.pdf_flattened, Toast.LENGTH_LONG).show() }
                } }
            } }
        }
    }
    }
    // Words' colours: the screen round the pages, its ribbon a shade darker (PDF_LIKE_WORDS.md)
    Column(Modifier.fillMaxSize().background(WORDS_SCREEN).focusRequester(keys).focusable().onPreviewKeyEvent { k ->
        if (k.type != KeyEventType.KeyDown || typing != null || !editing) return@onPreviewKeyEvent false
        val d = doc ?: return@onPreviewKeyEvent false
        val c = chosen; val step = if (k.isShiftPressed) 10f else 1f // points
        when {
            k.isCtrlPressed && k.key == Key.S -> { if (d.dirty) save(); true }
            k.isCtrlPressed && (k.key == Key.Y || (k.key == Key.Z && k.isShiftPressed)) -> { chosen = null; change { d.redo() }; true }
            k.isCtrlPressed && k.key == Key.Z -> { chosen = null; change { d.undo() }; true }
            k.isCtrlPressed && k.key == Key.V -> { paste(c?.page ?: at); true }
            c == null -> false
            k.isCtrlPressed && k.key == Key.C -> { copyChosen(); true }
            k.isCtrlPressed && k.key == Key.X -> { if (copyChosen()) deleteChosen(); true }
            k.isCtrlPressed && k.key == Key.D -> { duplicateChosen(); true }
            k.key == Key.Delete || k.key == Key.Backspace -> { deleteChosen(); true }
            k.key == Key.Enter -> { retype(c); true }
            k.key == Key.Escape -> { chosen = null; true }
            k.key == Key.DirectionLeft -> { moveChosen(-step, 0f); true }
            k.key == Key.DirectionRight -> { moveChosen(step, 0f); true }
            k.key == Key.DirectionUp -> { moveChosen(0f, -step); true }
            k.key == Key.DirectionDown -> { moveChosen(0f, step); true }
            else -> false
        }
    }) {
        Surface(color = WORDS_SCREEN, contentColor = islandContentColor(), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.statusBarsPadding()) {
                val tint = islandContentColor()
                val d0 = doc
                // Words' Find and Replace: its bar in the top bar's place, found as it is typed; Replace in the words Tetra put on
                if (finding && d0 != null) {
                    LaunchedEffect(query) { if (query.isBlank()) matches = null else { delay(300); find() } }
                    FindReplaceBar(tint, query, { query = it }, when { query.isBlank() -> ""; searching -> "…"; matches.isNullOrEmpty() -> "0"; else -> "${match + 1}/${matches!!.size}" },
                        !matches.isNullOrEmpty(), previous = { step(-1) }, next = { step(1) }, replacing = replacingWords && editing, setReplacing = { replacingWords = it; if (it) editing = true },
                        replacement = replacement, setReplacement = { replacement = it }, replace = { replaceFound(false) }, replaceAll = { replaceFound(true) },
                        close = { finding = false; matches = null })
                } else
                Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tool(R.drawable.ic_chevron_left, stringResource(R.string.common_back), tint) { back() }
                    val dirty = version >= 0 && d0?.dirty == true
                    val phone = LocalConfiguration.current.screenWidthDp < 600
                    // Words' bar: the name (a dot while unsaved); a phone the pages under it, a tablet beside it with the file's state and kind (a tap: About)
                    Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                        Text((if (dirty) "• " else "") + (file?.name ?: stringResource(R.string.app_pdf)), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (phone && d0 != null) Text(pluralStringResource(R.plurals.pdf_pages, d0.sizes.size, d0.sizes.size), color = tint.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.clickable { about = true })
                    }
                    if (!phone && d0 != null) Text(pluralStringResource(R.plurals.pdf_pages, d0.sizes.size, d0.sizes.size) +
                        (if (file != null && editing) " · " + saveState(false, dirty) + (if (autoSave && dirty) stringResource(R.string.words_autosave_on_suffix) else "") else "") + " · .pdf",
                        color = tint.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.clickable { about = true }.padding(horizontal = 8.dp))
                    RibbonTools {
                    FavouriteHeart(file, tint) // a tablet or bigger: the Office home's Favourites (the user, 18 Oct)
                    if (editing && d0 != null) {
                        if (Pdf.hasText) Tool(R.drawable.ic_search, stringResource(R.string.words_find_replace), tint) { putTyped(); finding = true }
                        key(version) {
                            // words being typed on the page: put on first (Undo then takes them off, as any change)
                            Tool(R.drawable.ic_undo, stringResource(R.string.common_undo), tint, enabled = d0.canUndo || typing != null) { putTyped(); chosen = null; change { d0.undo() } }
                            Tool(R.drawable.ic_redo, stringResource(R.string.common_redo), tint, enabled = d0.canRedo) { putTyped(); chosen = null; change { d0.redo() } }
                            Tool(R.drawable.ic_save, stringResource(R.string.common_save), tint, enabled = d0.dirty || typing != null) { putTyped(); save() }
                        }
                        // Done at the far right, back to the pages (Words')
                        Tool(R.drawable.ic_check, stringResource(R.string.common_done), tint) { putTyped(); chosen = null; if (!d0.dirty) editing = false else if (autoSave) save { editing = false } else leaving = true }
                    } else if (d0 != null) {
                        // Words' view-only bar: the list of pages, two pages, find, share, save (with changes), Edit
                        val wide0 = LocalConfiguration.current.screenWidthDp >= 700
                        Tool(R.drawable.ic_view_list, stringResource(R.string.words_navigation), tint, on = if (wide0) navigation else pages) { if (wide0) navigation = !navigation else pages = !pages }
                        Tool(R.drawable.ic_view_column, stringResource(R.string.words_two_pages), tint, on = across == 2) { val keep = at; across = 3 - across; goTo(keep) }
                        if (Pdf.hasText) Tool(R.drawable.ic_search, stringResource(R.string.office_find), tint) { finding = true }
                        file?.let { f -> Tool(R.drawable.ic_share, stringResource(R.string.common_share), tint) { shareDriveFile(context, f) } }
                        if (d0.dirty) Tool(R.drawable.ic_save, stringResource(R.string.common_save), tint) { save() }
                        // a phone: the pencil alone, as Tables' and Slides' (a longer word, "Επεξεργασία", left the file's name no room)
                        if (d0.editable) { if (phone) Tool(R.drawable.ic_edit, stringResource(R.string.common_edit), tint) { editing = true; WriterLog.i("PDF: edit") }
                            else RibbonButton(stringResource(R.string.common_edit), tint, R.drawable.ic_edit) { editing = true; WriterLog.i("PDF: edit") } }
                    }
                    }
                }
            }
        }
        if (OfficeUi.top) ribbon()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val d = doc
            when {
                failed != null -> Text(stringResource(failed!!), color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp))
                d == null -> Text(stringResource(R.string.words_opening), color = Color.LightGray, modifier = Modifier.align(Alignment.Center))
                d.sizes.isEmpty() -> Text(stringResource(R.string.pdf_no_pages), color = Color.White, modifier = Modifier.align(Alignment.Center))
                else -> key(version) { Row(Modifier.fillMaxSize()) {
                    val density = LocalDensity.current
                    // a tablet: the pages small at the side, the one shown marked, tapped to go to it; a phone: the same, narrower, when asked for
                    // Words' comment cards beside the pages (a tablet), each with its replies, Edit, Reply, Resolve, Delete
                    if (pane == "Comments" && wide) Column(Modifier.width(320.dp).fillMaxHeight().background(WORDS_RIBBON)) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.words_comments), color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            IconButton(onClick = { pane = null }) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_close), tint = Color.White) }
                        }
                        val tops = commentList.filter { it.replyTo == null }
                        if (tops.isEmpty()) Text(stringResource(R.string.pdf_no_comments), Modifier.padding(16.dp), color = Color.LightGray)
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(tops.size) { k -> val c = tops[k]; CommentCard(c.card(stringResource(c.kind)), hereComment?.let { it.page == c.page && it.index == c.index } == true, commentActions) }
                        }
                    }
                    else
                    if (pane != null && wide) PdfPane(d, pane!!, editing, version, Modifier.width(300.dp).fillMaxHeight(), { goTo(it) }, { pane = null }, { asking = "bookmark" }, { editing = true }) { act -> change(act) }
                    else if ((wide && navigation) || (!wide && pages)) {
                        val side = if (wide) 150.dp else 110.dp
                        val thumbs = rememberLazyListState()
                        LaunchedEffect(at) { if (thumbs.layoutInfo.visibleItemsInfo.none { it.index == at && it.offset >= 0 }) thumbs.animateScrollToItem((at - 1).coerceAtLeast(0)) }
                        LazyColumn(Modifier.width(side).fillMaxHeight().background(WORDS_RIBBON), state = thumbs, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(d.sizes.size) { i ->
                                Column(Modifier.fillMaxWidth().clickable { goTo(i); if (!wide) pages = false; WriterLog.i("PDF: thumbnail ${i + 1}") }, horizontalAlignment = Alignment.CenterHorizontally) {
                                    PdfPage(d, i, with(density) { (side - 24.dp).roundToPx() }, Modifier.fillMaxWidth().then(if (i == at) Modifier.border(2.dp, PDF_RED) else Modifier))
                                    Text("${i + 1}", color = if (i == at) PDF_RED else Color.LightGray, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    }
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().clipToBounds().pointerInput(Unit) { // zoomed: kept off the bar and the thumbnails
                        // a pinch zooms (two fingers, before the list scrolls); zoomed in, one finger moves about the page
                        val view = Size(size.width.toFloat(), size.height.toFloat())
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                var ev = awaitPointerEvent(PointerEventPass.Initial)
                                val two = ev.changes.count { it.pressed } >= 2
                                // one finger, zoomed in: the page moves unless the page's own took it first (a chosen object or its
                                // handles, the words being typed) — after them, so a chosen text no longer pins the page (27 Oct)
                                if (!two && zoom > 1f && tool == null) ev = awaitPointerEvent(PointerEventPass.Main)
                                if (two || (zoom > 1f && tool == null && ev.changes.none { it.isConsumed })) {
                                    val by = ev.calculateZoom(); val moved = ev.calculatePan()
                                    val c = ev.calculateCentroid(useCurrent = true).takeIf { it.isSpecified } ?: Offset(view.width / 2, view.height / 2)
                                    val (z, o) = Pdf.zoomed(zoom, pan, by, c - Offset(view.width / 2, view.height / 2), moved, view)
                                    zoom = z; pan = o
                                    // a still tap is left alone, for the double tap
                                    if (by != 1f || moved != Offset.Zero) ev.changes.forEach { it.consume() }
                                }
                            } while (ev.changes.any { it.pressed })
                        }
                    }.pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { p ->
                            val view = Size(size.width.toFloat(), size.height.toFloat())
                            if (zoom > 1f) { zoom = 1f; pan = Offset.Zero }
                            else Pdf.zoomed(1f, Offset.Zero, 2.5f, p - Offset(view.width / 2, view.height / 2), Offset.Zero, view).let { (z, o) -> zoom = z; pan = o }
                        })
                    }) {
                        // page by page, as Slides' list: a swipe ends with the next or the one before in the middle, however hard it is flung
                        val oneAtATime = remember(list) {
                            val base = androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider(list, androidx.compose.foundation.gestures.snapping.SnapPosition.Center)
                            object : androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider by base {
                                override fun calculateApproachOffset(velocity: Float, decayOffset: Float) = 0f
                            }
                        }
                        // Words' rulers on a tablet (one page across, not zoomed in): along the top for the page in view, down each page's side
                        val ruled = rulers && wide && editing && across == 1 && zoom == 1f
                        val rulerW = 18.dp; val sideRuler = if (ruled) rulerW + 12.dp else 0.dp // the side ruler and the row's gap after it
                        val roomW = maxWidth - 24.dp; val shownH = maxHeight - 24.dp - (if (ruled) 22.dp else 0.dp)
                        // the keyboard up: the page keeps the size it had (it scrolls under the words being typed, as Words'), not shrunk to what is left
                        val keyboard = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(density) > 0
                        val calmH = remember { mutableStateOf(shownH) }
                        androidx.compose.runtime.SideEffect { if (!keyboard) calmH.value = shownH }
                        val roomH = if (keyboard) maxOf(calmH.value, shownH) else shownH
                        // the page shown on the screen: as wide as there is room (Fit width: all of it), no taller than the screen
                        fun widthOf(s: Size) = ((roomW - sideRuler - 12.dp * (across - 1)) / across).let { room -> if (fitWidth) room else minOf(room, roomH * (s.width / s.height)) }
                        fun pageOf(s: Size) = DocPage(s.width, s.height, margins.top, margins.bottom, margins.left, margins.right)
                        Column(Modifier.fillMaxSize()) {
                        if (ruled) d.sizes.getOrNull(at)?.let { s -> val w = widthOf(s)
                            HorizontalRuler(pageOf(s), w / s.width, 12.dp + (roomW - (sideRuler + w)) / 2 + sideRuler, Modifier.fillMaxWidth().height(22.dp)) }
                        LazyColumn(Modifier.weight(1f).fillMaxWidth().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y }, state = list,
                            userScrollEnabled = zoom == 1f && tool == null,
                            // a page at a time, as Slides' list; Fit width's pages are taller than the screen: they scroll freely
                            flingBehavior = if (fitWidth) androidx.compose.foundation.gestures.ScrollableDefaults.flingBehavior() else androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(oneAtATime),
                            contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            // View › Two pages: a row of two, as Words'
                            items((d.sizes.size + across - 1) / across) { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                              for (i in row * across until minOf(d.sizes.size, row * across + across)) {
                                val s = d.sizes[i]
                                val w = widthOf(s)
                                val px = with(density) { w.roundToPx() }
                                if (ruled) VerticalRuler(pageOf(s), w / s.width, Modifier.width(rulerW).height(w * (s.height / s.width)))
                                val marks = matches?.filter { it.page == i }.orEmpty()
                                PdfPage(d, i, if (i == at) Pdf.renderWidth(px, settled, s.width / s.height) else px,
                                    Modifier.width(w)
                                        // a mouse's right button (as Slides'): the text under it chosen, and its menu; before the taps see it
                                        .pointerInput(editing, i) {
                                            if (!editing) return@pointerInput
                                            awaitEachGesture {
                                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                                if (!currentEvent.buttons.isSecondaryPressed) return@awaitEachGesture
                                                down.consume()
                                                if (typing != null) putTyped()
                                                val pt = down.position / (size.width / s.width)
                                                chosen = doc?.objectAt(i, pt)
                                                pageMenu = Triple(i, down.position, pt)
                                            }
                                        }
                                        // a tap on a link: Words' "Go to" for it (watched, not taken: the page's own taps go on)
                                        .pointerInput(i, s) { awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                            while (true) {
                                                val ch = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                                                if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                                                if (!ch.pressed) { if (ch.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) tappedLink(i, down.position / (size.width / s.width)); break }
                                            }
                                        } }
                                        // a tap anywhere lets go of a chosen film (one on a film chooses it again, after this); a drag does not
                                        .pointerInput(editing) { if (editing) awaitEachGesture { val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                            waitForUpOrCancellation(PointerEventPass.Initial)?.takeIf { (it.position - down.position).getDistance() < viewConfiguration.touchSlop }?.let { media.chosen = null } } }
                                        // an object on this page as Words' (the user, 26 Oct): pressed and dragged it moves at once, a tap chooses it,
                                        // a long press opens Position, a tap on a chosen text types in it
                                        .pdfObjectGestures(s, editing && typing == null && (tool == null || tool == PdfTool.Text || tool == PdfTool.TextBox),
                                            { pt -> objectUnder(i, pt) },
                                            { chosen?.takeIf { it.page == i } }, framePreview,
                                            { o -> chosen = o; tool = null },
                                            { chosen?.let(::retype) },
                                            { positioning = true },
                                            ::moveChosen), marks, matches?.getOrNull(match)?.takeIf { it.page == i }, hide = chosen?.takeIf { it.page == i && typing == null }, preview = framePreview) {
                                    if (editing || d.hasForm) PdfDrawLayer(s, tool, if (tool == PdfTool.Pen) penColour else if (tool == PdfTool.Marker) 0xFFEB3B else markColour) { pts -> onPage(i, pts) }
                                    if (tool == null && typing == null) PdfMediaLayer(d, i, s, editing, media, version) { sp, dx, dy -> change { d.moveMedia(i, sp.index, dx, dy) } } // over a form's layer: only its films' boxes take taps
                                    typing?.takeIf { it.page == i }?.let { tp -> PdfTypingField(s, tp, typed, textLook, typingText?.doc?.basePt ?: textLook.size, spell) }
                                    pageMenu?.takeIf { it.first == i }?.let { (_, spot, pt) -> OfficeMenu(spot, { pageMenu = null }) {
                                        @Composable fun entry(label: String, icon: Int, act: () -> Unit) = OfficeMenuItem(label, icon) { pageMenu = null; WriterLog.i("PDF: menu › $label"); act() }
                                        val c = chosen
                                        if (c != null) {
                                            if (c.text != null) entry(stringResource(R.string.words_edit_text), R.drawable.ic_edit) { retype(c) }
                                            entry(stringResource(R.string.common_cut), R.drawable.ic_content_cut) { if (copyChosen()) deleteChosen() }
                                            entry(stringResource(R.string.common_copy), R.drawable.ic_copy) { copyChosen() }
                                            entry(stringResource(R.string.words_link), R.drawable.ic_link) { askLink() }
                                        }
                                        entry(stringResource(R.string.common_paste), R.drawable.ic_content_paste) { paste(i, if (c == null) pt else null) }
                                        if (c != null) {
                                            entry(stringResource(R.string.words_bring_to_front), R.drawable.ic_flip_to_front) { restackChosen(2) }
                                            entry(stringResource(R.string.words_send_to_back), R.drawable.ic_flip_to_back) { restackChosen(-2) }
                                            entry(stringResource(R.string.common_delete), R.drawable.ic_delete) { deleteChosen() }
                                        }
                                    } }
                                    chosen?.takeIf { it.page == i && typing == null }?.let { c ->
                                        PdfChosenHandles(s, c.rect, c.laid, c.turn, framePreview, turnable(c), c.kind == "Image" || c.kind == "Signature", ::sizeChosen, ::turnChosen)
                                    }
                                }
                              } }
                            }
                        }
                        }
                    }
                } }
            }
            // on a phone the label is also the button for the list of pages; gone while editing, as Words' (the user, 24 Oct: it sat on the page)
            if (d != null && d.sizes.isNotEmpty() && !editing) Surface(color = Color.Black.copy(alpha = 0.75f), contentColor = if (pages) PDF_RED else Color.White, shape = CircleShape,
                modifier = Modifier.align(Alignment.BottomCenter).bottomBarPadding().padding(bottom = 16.dp).then(if (wide) Modifier else Modifier.clickable { val keep = at; pages = !pages; scope.launch { list.scrollToItem(keep / across) } })) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!wide) Icon(painterResource(R.drawable.ic_view_grid), contentDescription = stringResource(if (pages) R.string.pdf_hide_pages else R.string.pdf_show_pages), modifier = Modifier.padding(end = 8.dp).size(18.dp))
                    Text(stringResource(R.string.words_page_of, (at + 1).coerceAtMost(d.sizes.size), d.sizes.size), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // a link tapped: Words' "Go to" pill, a tap goes (a page of this file, or the address in its app); gone by itself
            linkHere?.let { (uri, toPage) ->
                LaunchedEffect(linkHere) { delay(5000); linkHere = null }
                Surface(color = Color.Black, contentColor = Color.White, shape = androidx.compose.foundation.shape.CircleShape,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp, start = 16.dp, end = 16.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable {
                        linkHere = null
                        if (toPage != null) goTo(toPage)
                        else runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri))) }
                            .onFailure { Toast.makeText(context, R.string.pdf_could_not_do, Toast.LENGTH_SHORT).show() }
                        WriterLog.i("PDF: link followed (${if (toPage != null) "page" else "address"})")
                    }) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_link), contentDescription = null, modifier = Modifier.size(18.dp).padding(end = 6.dp))
                        Text(stringResource(R.string.words_go_to, toPage?.let { stringResource(R.string.pdf_page_lower, it + 1) } ?: uri.removePrefix("mailto:").take(60)),
                            style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            // a phone: the pane over the lower half of the pages
            if (d != null && pane != null && !wide) PdfPane(d, pane!!, editing, version, Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.55f),
                { goTo(it) }, { pane = null }, { asking = "bookmark" }, { editing = true }) { act -> change(act) }
        }
        if (!OfficeUi.top) ribbon()
    }
}

/** Page [i], drawn [widthPx] wide off the main thread (the sharper one in when it is ready); words found marked on it, the current one stronger. */
@Composable
private fun PdfPage(doc: PdfDoc, i: Int, widthPx: Int, modifier: Modifier, marks: List<PdfMatch> = emptyList(), current: PdfMatch? = null,
    hide: PdfObject? = null, preview: PdfFramePreview? = null, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {}) {
    val s = doc.sizes[i]
    var bitmap by remember(i) { mutableStateOf(doc.cached(i, widthPx)) }
    // a chosen object: the page without it and it alone, swapped in together (no moment without it), it drawn where a finger has it
    var lifted by remember(i) { mutableStateOf<Pair<Int, Bitmap>?>(null) }
    LaunchedEffect(i, widthPx, hide?.id) {
        val b = doc.page(i, widthPx, hide?.id) ?: return@LaunchedEffect
        val o = hide?.id?.let { id -> withContext(Dispatchers.IO) { doc.objectPicture(i, id, widthPx) }?.let { id to it } }
        bitmap = b; lifted = o
    }
    Box(modifier.aspectRatio(s.width / s.height).background(Color.White)) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = stringResource(R.string.pdf_page_n, i + 1), modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
        lifted?.takeIf { it.first == hide?.id }?.let { (_, b) -> val r = hide!!.rect; val turn = hide.turn; val img = remember(b) { b.asImageBitmap() }
            Canvas(Modifier.fillMaxSize()) {
                val k = size.width / s.width; val sh = preview?.shift ?: Offset.Zero; val spin = preview?.spin?.let { it - turn } ?: 0f
                translate(sh.x * k, sh.y * k) { rotate(spin, Offset(r.centerX() * k, r.centerY() * k)) {
                    drawImage(img, dstOffset = androidx.compose.ui.unit.IntOffset(Math.round(r.left * k), Math.round(r.top * k)),
                        dstSize = androidx.compose.ui.unit.IntSize(Math.round(r.width() * k).coerceAtLeast(1), Math.round(r.height() * k).coerceAtLeast(1))) } }
            } }
        if (marks.isNotEmpty()) Canvas(Modifier.fillMaxSize()) {
            val k = size.width / s.width
            for (m in marks) for (r in m.boxes) drawRect(if (m === current) Color(0x88FF8A00) else Color(0x66FFEB3B), Offset(r.left * k, r.top * k), Size(r.width() * k, r.height() * k))
        }
        content()
    }
}

/**
 * The editor's layer over a page: a finger's path while a tool is on, drawn as it goes (the pen's line, a shape's outline,
 * the stretch a highlight will cover), then handed over in the page's points; with no tool, a tap (to read a note).
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.PdfDrawLayer(page: Size, tool: PdfTool?, colour: Int, done: (List<Offset>) -> Unit) {
    var path by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val c = Color(0xFF000000.toInt() or colour)
    Canvas(Modifier.matchParentSize().pointerInput(tool) {
        val k = size.width / page.width
        val taps = tool == null || tool == PdfTool.Note || tool == PdfTool.Erase || tool == PdfTool.Stamp || tool == PdfTool.CheckBox || tool in PdfTool.Text..PdfTool.Sign
        // one finger only: a second one makes it the page's pinch, nothing put on (the user, 27 Oct: zooming made text
        // boxes); a text box is drawn, or put where tapped
        awaitEachGesture {
            val down = awaitFirstDown()
            val pts = mutableListOf(down.position); var moved = false; var two = false
            while (true) {
                val ev = awaitPointerEvent()
                if (ev.changes.count { it.pressed } > 1) { two = true; path = emptyList() }
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) { if (!two && !moved) ch.consume(); break }
                if (two) continue
                if (!moved && (ch.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                if (moved && !taps) { ch.consume(); pts += ch.position; path = pts.toList() }
            }
            path = emptyList()
            if (!two) when {
                !moved -> if (taps || tool == PdfTool.TextBox) done(listOf(down.position / k))
                !taps -> done(pts.map { it / k })
            }
        }
    }) {
        if (path.size < 2) return@Canvas
        val a = path.first(); val b = path.last()
        when (tool) {
            PdfTool.Pen, PdfTool.Marker -> drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(a.x, a.y); path.drop(1).forEach { lineTo(it.x, it.y) } },
                c.copy(alpha = if (tool == PdfTool.Marker) 0.4f else 1f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = if (tool == PdfTool.Marker) 24f else 5f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            PdfTool.Rectangle, PdfTool.TextBox, PdfTool.TextField -> drawRect(c, Offset(minOf(a.x, b.x), minOf(a.y, b.y)), Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)), style = androidx.compose.ui.graphics.drawscope.Stroke(4f))
            PdfTool.Redact -> drawRect(Color.Black.copy(alpha = 0.5f), Offset(minOf(a.x, b.x), minOf(a.y, b.y)), Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)))
            PdfTool.Oval -> drawOval(c, Offset(minOf(a.x, b.x), minOf(a.y, b.y)), Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)), style = androidx.compose.ui.graphics.drawscope.Stroke(4f))
            PdfTool.Arrow -> drawLine(c, a, b, 4f)
            else -> drawLine(c.copy(alpha = 0.5f), a, b, 18f) // the words a highlight will cover, roughly
        }
    }
}


/**
 * The Comments or Bookmarks pane: every comment with its words and page, or the file's bookmarks; a row tapped goes to its
 * page. While editing a comment can be deleted, a bookmark deleted or one added for the page shown. [version] reads them again.
 */
@Composable
private fun PdfPane(d: PdfDoc, which: String, editing: Boolean, version: Int, modifier: Modifier, go: (Int) -> Unit, close: () -> Unit, addMark: () -> Unit,
    edit: () -> Unit = {}, change: (() -> Unit) -> Unit) {
    var replying by remember { mutableStateOf<PdfComment?>(null) }
    replying?.let { c ->
        var words by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(onDismissRequest = { replying = null }, title = { Text(stringResource(R.string.words_reply)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (c.words.isNotBlank()) Text(stringResource(R.string.pdf_quoted, c.words.take(140)), style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.OutlinedTextField(words, { words = it }, Modifier.fillMaxWidth(), minLines = 2)
            } },
            confirmButton = { androidx.compose.material3.TextButton(enabled = words.isNotBlank(), onClick = { replying = null; edit(); change { d.reply(c, words.trim()) } }) { Text(stringResource(R.string.words_reply), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { replying = null }) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) } })
    }
    var comments by remember { mutableStateOf<List<PdfComment>?>(null) }
    var marks by remember { mutableStateOf<List<PdfMark>?>(null) }
    LaunchedEffect(which, version) {
        withContext(Dispatchers.IO) { if (which == "Comments") comments = runCatching { d.comments() }.getOrDefault(emptyList()) else marks = runCatching { d.bookmarks() }.getOrDefault(emptyList()) }
    }
    Surface(color = WORDS_RIBBON, contentColor = Color.White, modifier = modifier) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (which == "Comments") R.string.words_comments else R.string.pdf_bookmarks), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (which == "Bookmarks") androidx.compose.material3.TextButton(onClick = addMark) { Text(stringResource(R.string.pdf_add_for_page), color = PDF_RED, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                IconButton(onClick = close) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_close)) }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (which == "Comments") {
                    val l = comments
                    if (l == null) item { Text(stringResource(R.string.pdf_reading), Modifier.padding(16.dp), color = Color.LightGray) }
                    else if (l.isEmpty()) item { Text(stringResource(R.string.pdf_no_comments), Modifier.padding(16.dp), color = Color.LightGray) }
                    else {
                        // each comment with its replies under it, a step in
                        val ordered = l.filter { it.replyTo == null }.flatMap { c -> listOf(c) + l.filter { r -> r.page == c.page && r.replyTo == c.index } }
                        items(ordered.size) { k -> val c = ordered[k]; val reply = c.replyTo != null
                            Row(Modifier.fillMaxWidth().clickable { go(c.page) }.padding(start = if (reply) 36.dp else 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(listOfNotNull(stringResource(c.kind), c.author.ifBlank { null }, stringResource(R.string.pdf_page_lower, c.page + 1)).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = PDF_RED)
                                    if (c.words.isNotBlank()) Text(c.words, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                                if (!reply) IconButton(onClick = { replying = c }) { Icon(painterResource(R.drawable.ic_comment), contentDescription = stringResource(R.string.words_reply)) }
                                if (editing) IconButton(onClick = { change { d.deleteComment(c) } }) { Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.common_delete)) }
                            }
                        }
                    }
                } else {
                    val l = marks
                    if (l == null) item { Text(stringResource(R.string.pdf_reading), Modifier.padding(16.dp), color = Color.LightGray) }
                    else if (l.isEmpty()) item { Text(stringResource(R.string.pdf_no_bookmarks), Modifier.padding(16.dp), color = Color.LightGray) }
                    else items(l.size) { k -> val m = l[k]
                        Row(Modifier.fillMaxWidth().clickable(enabled = m.page >= 0) { go(m.page) }.padding(start = 16.dp + 16.dp * m.depth, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(m.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (m.page >= 0) Text("${m.page + 1}", style = MaterialTheme.typography.labelMedium, color = Color.LightGray, modifier = Modifier.padding(horizontal = 8.dp))
                            if (editing) IconButton(onClick = { change { d.deleteBookmark(k) } }) { Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.common_delete)) }
                        }
                    }
                }
            }
        }
    }
}
