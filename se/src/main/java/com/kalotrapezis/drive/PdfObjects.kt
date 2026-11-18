package com.kalotrapezis.drive

import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import com.artifex.mupdf.fitz.PDFAnnotation

/*
 * PDF like Words (PDF_LIKE_WORDS.md, E1): what Tetra puts on a PDF page — a picture, shape, text box, WordArt,
 * signature, drawing, chart or equation — is a Words object. The page's annotation keeps it as a one-picture Words
 * document (a .docx, its own stream under [WORDS_KEY]) and shows it drawn by Words' own code ([OfficePages.pictureOf]),
 * so it is chosen, moved, sized, turned and changed again exactly as in Words. Other apps' pictures, stamps, shapes and
 * drawings are chosen too: moved, sized and deleted.
 */

/** Tetra's key on a Words object it put on a page: the object as a one-picture .docx (a stream). */
internal const val WORDS_KEY = "TetraWords"

/**
 * A Words object on a PDF page: its one-paragraph document, the parts its picture's bytes are in, and the .docx it was
 * read from ([source]: written back into it, as Words saves into the file it opened, so its parts stay).
 */
internal class PdfWords(val doc: OfficeDoc, val parts: Map<String, ByteArray> = emptyMap(), private val source: ByteArray? = null) {
    val img: DocImage = doc.paras.firstNotNullOf { p -> p.runs.firstNotNullOfOrNull { it.image } }
    fun bytes(i: DocImage = img): ByteArray? = i.bytes ?: i.part?.let(parts::get)
    /** Words' name for it: Image, Shape, Text box, WordArt, Signature… ([PictureXml.kind]). */
    val kind: String get() = PictureXml.kind(img)
    /** Its size before turning (points) and its turn (degrees clockwise), as Word keeps them. */
    val laid get() = img.widthPt to img.heightPt
    val turn get() = Arrange.rotationOf(img)
    /** The same, [i] in its picture's place. */
    fun with(i: DocImage) = PdfWords(doc.copy(paras = doc.paras.map { p -> p.copy(runs = p.runs.map { r -> if (r.image != null) r.copy(image = i) else r }) }), parts, source)
    fun docx(): ByteArray = OfficeFormats.write(doc, OfficeKind.Docx, source)
    /** What it shows, as Words' pages draw it. */
    fun picture(): android.graphics.drawable.Drawable = OfficePages.pictureOf(doc, DocStyles.Plain, img) { bytes(it) }
    /** Only a photo or a drawing's picture (no crop, words, chart…): put in the PDF as the picture itself, its JPEG kept. */
    val plainPicture get() = img.group == null && img.math == null && img.box == null && img.chart == null && bytes() != null && Slides.srcRect(img.raw) == null

    companion object {
        /** [img] as a Words object, written and read again so it has Word's own XML (Rotate, Shape options… change that). */
        fun of(img: DocImage): PdfWords {
            val one = PdfWords(OfficeDoc(listOf(DocPara(listOf(DocRun(OBJECT_MARK.toString(), image = img))))))
            return read(one.docx()) ?: one
        }
        fun read(docx: ByteArray): PdfWords? = runCatching { PdfWords(OfficeFormats.read(docx, OfficeKind.Docx), OfficeFormats.unzip(docx), docx) }.getOrNull()
    }
}

/** The same object with its picture's [bytes] in it (not a part of the file it came from). */
private fun DocImage.withBytes(bytes: ByteArray?) = DocImage(key, null, widthPt, heightPt, raw, bytes, extension, wrap, table, onPage, box, chart, look, math, group, film)

/**
 * Words' Group for two Words objects on page [i] ([Arrange.group], as Words groups two floating pictures): one group
 * where they were, the two taken off, one undo step. Its id, or null when Word cannot group them.
 */
internal fun PdfDoc.groupObjects(i: Int, a: PdfObject, b: PdfObject): Int? {
    val wa = a.words ?: return null; val wb = b.words ?: return null
    // each piece by its box before turning, round its middle; its picture's bytes carried in (not a part of its own file)
    fun piece(o: PdfObject, w: PdfWords) = w.img.withBytes(w.bytes()) to w.laid.let { (lw, lh) -> floatArrayOf(o.rect.centerX() - lw / 2, o.rect.centerY() - lh / 2, lw, lh) }
    val g = Arrange.group(listOf(piece(a, wa), piece(b, wb)), PictureWrap.InFront, 0, "g" + java.util.UUID.randomUUID()) ?: return null
    val (gx, gy) = g.onPage ?: return null
    var id = 0
    edit("Group") { _ -> id = putWords(i, PdfWords.of(g), gx + g.widthPt / 2, gy + g.heightPt / 2); deleteObject(i, a.id); deleteObject(i, b.id) }
    return id
}

/** Words' Ungroup for the group [o] on page [i]: each piece put where it was, the group taken off (one undo step). How many. */
internal fun PdfDoc.ungroupObject(i: Int, o: PdfObject): Int {
    val w = o.words ?: return 0
    val (lw, lh) = w.laid
    val pieces = Arrange.ungroup(w.img, o.rect.centerX() - lw / 2, o.rect.centerY() - lh / 2, PictureWrap.InFront, 0) ?: return 0
    edit("Ungroup") { _ ->
        pieces.forEach { p -> val (px, py) = p.onPage ?: return@forEach
            putWords(i, PdfWords.of(p.withBytes(p.bytes ?: w.bytes(p))), px + p.widthPt / 2, py + p.heightPt / 2) }
        deleteObject(i, o.id)
    }
    return pieces.size
}

/** Words' signature (Insert › Signature): [png] as a picture named so, [width] points across. */
internal fun signatureWords(png: ByteArray, width: Float = 140f): PdfWords {
    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size, o)
    return PdfWords.of(DocImage(SIGNATURE_KEY + java.util.UUID.randomUUID(), null, width, width * o.outHeight / o.outWidth.coerceAtLeast(1), bytes = png, wrap = PictureWrap.InFront))
}

/**
 * Something chosen on page [page] as Words' objects are: the annotation [id] (its object number), [rect] the box round
 * it on the page (points from the page's top left, turned), and what it is — a Words object ([words]), a Tetra text
 * ([text]) or another app's annotation of [type].
 */
internal class PdfObject(val page: Int, val id: Int, val rect: RectF, val type: Int, private val docx: ByteArray? = null, private val json: String? = null) {
    /** Read when first wanted (a page's objects are listed at every change, most never chosen): a text's frame and words, or a Words object. */
    val text: PdfText? by lazy { json?.let { PdfText.parse(it, docx) } }
    val words: PdfWords? by lazy { if (json == null) docx?.let(PdfWords::read) else null }
    /** Words' name for its kind, its tab's name. */
    val kind: String get() = words?.kind ?: if (text != null) "Text box" else when (type) {
        PDFAnnotation.TYPE_STAMP -> "Image"; PDFAnnotation.TYPE_FREE_TEXT -> "Text box"; PDFAnnotation.TYPE_INK -> "Drawing"; else -> "Shape" }
    /** Its size before turning (points), and its turn. */
    val laid: Pair<Float, Float> get() = words?.laid ?: text?.unturned(rect) ?: (rect.width() to rect.height())
    val turn: Float get() = words?.turn ?: text?.look?.turn?.toFloat() ?: 0f
    /** Tetra's own: everything of Words' can be done to it; another app's is only moved, sized and deleted. */
    val ours get() = docx != null || json != null
}

/** The kinds of annotation chosen as objects (a highlight or a note is a comment, a field the form's). */
private val CHOSEN_TYPES = setOf(PDFAnnotation.TYPE_STAMP, PDFAnnotation.TYPE_FREE_TEXT, PDFAnnotation.TYPE_INK, PDFAnnotation.TYPE_SQUARE,
    PDFAnnotation.TYPE_CIRCLE, PDFAnnotation.TYPE_LINE, PDFAnnotation.TYPE_POLYGON, PDFAnnotation.TYPE_POLY_LINE)

internal fun PDFAnnotation.id() = getObject().asIndirect()

/** Page [i]'s objects, the one drawn last (on top) last. */
internal fun PdfDoc.objects(i: Int): List<PdfObject> = synchronized(this) { withPage(i) { p ->
    val o = p.bounds
    (p as? com.artifex.mupdf.fitz.PDFPage)?.annotations.orEmpty().filter { it.type in CHOSEN_TYPES }.map { a ->
        val r = runCatching { a.rect }.getOrNull() ?: a.bounds; val obj = a.getObject() // ink, lines and polygons have no Rect of their own in MuPDF's eyes
        val docx = obj.get(WORDS_KEY).takeIf { it.isStream }?.let { runCatching { it.readStream() }.getOrNull() }
        val json = obj.get(TYPED_KEY).takeIf { !it.isNull }?.asString()
        PdfObject(i, a.id(), RectF(r.x0 - o.x0, r.y0 - o.y0, r.x1 - o.x0, r.y1 - o.y0), a.type, docx, json)
    }
} }

/** The object under [at] on page [i] (the topmost, a few points round it), or none. */
internal fun PdfDoc.objectAt(i: Int, at: Offset, slop: Float = 4f): PdfObject? = objects(i).lastOrNull { o ->
    at.x >= o.rect.left - slop && at.x <= o.rect.right + slop && at.y >= o.rect.top - slop && at.y <= o.rect.bottom + slop }

internal fun PdfDoc.objectById(i: Int, id: Int): PdfObject? = objects(i).firstOrNull { it.id == id }

private fun com.artifex.mupdf.fitz.PDFPage.annotation(id: Int) = annotations.orEmpty().firstOrNull { it.id() == id }

/**
 * Words object [w] on page [i], its middle at [cx], [cy] (page points) and turned as it says: drawn by Words' code as
 * the look of a stamp (what every reader shows), the object itself kept in the stamp. [replacing]: the one it was,
 * taken out in the same undo step. Its new id.
 */
internal fun PdfDoc.putWords(i: Int, w: PdfWords, cx: Float, cy: Float, replacing: Int? = null): Int {
    var id = 0
    annotate(i, w.kind) { p, x0, y0 ->
        replacing?.let { r -> p.annotation(r)?.let(p::deleteAnnotation) }
        val (lw, lh) = w.laid
        val (bw, bh) = turnedBox(lw, lh, w.turn)
        val a = p.createAnnotation(PDFAnnotation.TYPE_STAMP)
        a.setRect(com.artifex.mupdf.fitz.Rect(cx - bw / 2 + x0, cy - bh / 2 + y0, cx + bw / 2 + x0, cy + bh / 2 + y0))
        // its words for other readers and the object itself before its look: a change after it has MuPDF draw a stamp of its own
        a.setContents(PictureXml.altOf(w.img).ifBlank { w.img.box?.joinToString("\n") { it.text } ?: w.kind })
        a.getObject().put(WORDS_KEY, pdfDoc.addStream(com.artifex.mupdf.fitz.Buffer().apply { writeBytes(w.docx()) }, pdfDoc.newDictionary()))
        val box = com.artifex.mupdf.fitz.Rect(0f, 0f, bw, bh)
        if (w.plainPicture) {
            // the photo itself, its JPEG kept (drawn by Android it would be written out as raw pixels, megabytes)
            val res = pdfDoc.newDictionary(); val xo = pdfDoc.newDictionary()
            xo.put("Im0", pdfDoc.addImage(com.artifex.mupdf.fitz.Image(w.bytes()!!))); res.put("XObject", xo)
            a.setAppearance(upright(p), box, res, com.artifex.mupdf.fitz.Buffer().apply { writeBytes(pictureMatrix(lw, lh, bw, bh, w.turn).toByteArray()) })
        } else {
            val (res, contents) = grafted(wordsPdf(w, bw, bh))
            a.setAppearance(upright(p), box, res, com.artifex.mupdf.fitz.Buffer().apply { writeBytes(contents) })
        }
        id = a.id()
        true
    }
    return id
}

/**
 * The look's matrix for a drawing made upright as page [p] is shown: its /Rotate undone. A PDF reader fits the look's
 * box onto the annotation's Rect, which sits in the unturned page — on a turned page (a landscape scan) the words were
 * squeezed one way and stretched the other into a smear (3 Nov). Only the turn: the Rect places it.
 */
internal fun upright(p: com.artifex.mupdf.fitz.PDFPage): com.artifex.mupdf.fitz.Matrix =
    com.artifex.mupdf.fitz.Matrix(1f, 0f, 0f, -1f).concat(com.artifex.mupdf.fitz.Matrix.Inverted(p.transform)).let { m ->
        fun r(v: Float) = Math.round(v * 1e4f) / 1e4f + 0f // a quarter turn exactly, no -0
        com.artifex.mupdf.fitz.Matrix(r(m.a), r(m.b), r(m.c), r(m.d), 0f, 0f)
    }

/** [lw] × [lh] turned [turn] degrees: the size of the box round it. */
internal fun turnedBox(lw: Float, lh: Float, turn: Float): Pair<Float, Float> {
    val a = Math.toRadians(turn.toDouble()); val c = kotlin.math.abs(kotlin.math.cos(a)).toFloat(); val s = kotlin.math.abs(kotlin.math.sin(a)).toFloat()
    return kotlin.math.ceil(lw * c + lh * s - 0.01f) to kotlin.math.ceil(lw * s + lh * c - 0.01f) // a hair off: a quarter turn's cos is 6e-17, not 0
}

/** The PDF drawing of a picture [lw] × [lh] turned [turn] clockwise in the middle of a [bw] × [bh] box (PDF's y goes up). */
internal fun pictureMatrix(lw: Float, lh: Float, bw: Float, bh: Float, turn: Float): String {
    val t = Math.toRadians(-turn.toDouble()); val c = kotlin.math.cos(t).toFloat(); val s = kotlin.math.sin(t).toFloat()
    fun n(v: Float) = "%.4f".format(java.util.Locale.ROOT, v + 0f) // no "-0.0000"
    return "q ${n(lw * c)} ${n(lw * s)} ${n(-lh * s)} ${n(lh * c)} ${n(bw / 2 - lw / 2 * c + lh / 2 * s)} ${n(bh / 2 - lw / 2 * s - lh / 2 * c)} cm /Im0 Do Q"
}

/** [w] drawn by Words' code, turned, in a one-page PDF [bw] × [bh] of its own (Android's, fonts embedded). */
private fun wordsPdf(w: PdfWords, bw: Float, bh: Float): ByteArray {
    val doc = android.graphics.pdf.PdfDocument()
    try {
        val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(kotlin.math.ceil(bw).toInt().coerceAtLeast(1), kotlin.math.ceil(bh).toInt().coerceAtLeast(1), 1).create())
        val (lw, lh) = w.laid
        drawTurned(page.canvas, w.picture(), (bw - lw) / 2, (bh - lh) / 2, lw, lh, w.turn)
        doc.finishPage(page)
        return java.io.ByteArrayOutputStream().also { doc.writeTo(it) }.toByteArray()
    } finally { doc.close() }
}

/**
 * Annotation [a] given [to] as its place (page points with the page's origin), its look as it was: a stamp's or a text
 * box's own drawing kept (MuPDF would draw one of its own), stretched to the new place as PDF readers draw it.
 */
private fun PdfDoc.placeKeepingLook(a: PDFAnnotation, to: com.artifex.mupdf.fitz.Rect) {
    val n = a.getObject().get("AP").get("N")
    if (!n.isStream) { a.setRect(to); a.update(); return }
    val bb = n.get("BBox"); val bbox = com.artifex.mupdf.fitz.Rect(bb.get(0).asFloat(), bb.get(1).asFloat(), bb.get(2).asFloat(), bb.get(3).asFloat())
    val mm = n.get("Matrix"); val matrix = if (mm.isArray && mm.size() == 6) com.artifex.mupdf.fitz.Matrix(mm.get(0).asFloat(), mm.get(1).asFloat(), mm.get(2).asFloat(), mm.get(3).asFloat(), mm.get(4).asFloat(), mm.get(5).asFloat()) else com.artifex.mupdf.fitz.Matrix()
    val res = n.get("Resources"); val contents = n.readStream()
    a.setRect(to)
    a.setAppearance(matrix, bbox, res, com.artifex.mupdf.fitz.Buffer().apply { writeBytes(contents) })
}

/** The object [o] on page [i] moved by [dx], [dy] points (one undo step): Tetra's put on again, another app's shifted. Its id. */
internal fun PdfDoc.moveObject(i: Int, o: PdfObject, dx: Float, dy: Float): Int {
    o.words?.let { w -> return putWords(i, w, o.rect.centerX() + dx, o.rect.centerY() + dy, replacing = o.id) }
    annotate(i, "Move") { p, _, _ -> p.annotation(o.id)?.let { shifted(it, dx, dy) } != null }
    return o.id
}

/** [a] moved [dx], [dy] points as it looks (any kind: its drawing kept, only its place changed). */
private fun PdfDoc.shifted(a: PDFAnnotation, dx: Float, dy: Float) {
    fun pt(q: com.artifex.mupdf.fitz.Point) = com.artifex.mupdf.fitz.Point(q.x + dx, q.y + dy)
    when (a.type) {
        PDFAnnotation.TYPE_INK -> { a.setInkList(a.inkList.map { s -> s.map(::pt).toTypedArray() }.toTypedArray()); a.update() }
        PDFAnnotation.TYPE_LINE -> { a.line.let { l -> a.setLine(pt(l[0]), pt(l[1])) }; a.update() }
        PDFAnnotation.TYPE_POLYGON, PDFAnnotation.TYPE_POLY_LINE -> { a.setVertices(a.vertices.map(::pt).toTypedArray()); a.update() }
        else -> a.rect.let { r -> placeKeepingLook(a, com.artifex.mupdf.fitz.Rect(r.x0 + dx, r.y0 + dy, r.x1 + dx, r.y1 + dy)) }
    }
}

/** Objects [ids] on page [i] moved [dy] points down (up when less than 0), as they look: what is stacked under a text that grew. */
internal fun PdfDoc.shiftDown(i: Int, ids: Collection<Int>, dy: Float) = annotate(i, "Move") { p, _, _ ->
    ids.forEach { id -> p.annotation(id)?.let { shifted(it, 0f, dy) } }; true }

/**
 * A text put on as [PdfDoc.putText], and what was stacked under it ([stack]: under [oldBottom], where it ended
 * before) moved with its new end ([gap] more for a new text, which pushes them out of its way) — Words' flow, boxes
 * stuck one under another. One undo step. Its id.
 */
internal fun PdfDoc.putTextStacked(i: Int, x: Float, y: Float, t: PdfText, room: Float, minHeight: Float, replacing: Int?,
    stack: List<Int>, oldBottom: Float, gap: Float): Int {
    var id = 0
    edit("Text") { _ ->
        id = putText(i, x, y, t, room, minHeight, replacing)
        val end = objectById(i, id)?.rect?.bottom ?: return@edit
        val dy = end + gap - oldBottom
        if (stack.isNotEmpty() && kotlin.math.abs(dy) > 0.5f) shiftDown(i, stack, dy)
    }
    return id
}

/**
 * What is stacked under [r] on page [i] (Words' flow in a PDF: a box starting at most 12 pt under it, across from it),
 * and under those in turn; [except] left out (the text itself).
 */
internal fun stackedUnder(all: List<PdfObject>, r: android.graphics.RectF, except: Int?): List<PdfObject> {
    val found = LinkedHashMap<Int, PdfObject>()
    fun under(b: android.graphics.RectF) = all.filter { o -> o.id != except && o.id !in found && o.rect.left < b.right && o.rect.right > b.left &&
        o.rect.top >= b.bottom - 2f && o.rect.top <= b.bottom + 12f }
    var next = under(r)
    while (next.isNotEmpty()) { next.forEach { found[it.id] = it }; next = next.flatMap { under(it.rect) }.distinctBy { it.id } }
    return found.values.toList()
}

/** Another app's object [o] stretched from its top left to [w] × [h] points (one undo step). */
internal fun PdfDoc.sizeOther(i: Int, o: PdfObject, w: Float, h: Float) = annotate(i, "Size") { p, _, _ ->
    val a = p.annotation(o.id) ?: return@annotate false
    val r = a.bounds; val kx = w / (r.x1 - r.x0).coerceAtLeast(1f); val ky = h / (r.y1 - r.y0).coerceAtLeast(1f)
    fun pt(q: com.artifex.mupdf.fitz.Point) = com.artifex.mupdf.fitz.Point(r.x0 + (q.x - r.x0) * kx, r.y0 + (q.y - r.y0) * ky)
    when (a.type) {
        PDFAnnotation.TYPE_INK -> { a.setInkList(a.inkList.map { s -> s.map(::pt).toTypedArray() }.toTypedArray()); a.update() }
        PDFAnnotation.TYPE_LINE -> { a.line.let { l -> a.setLine(pt(l[0]), pt(l[1])) }; a.update() }
        PDFAnnotation.TYPE_POLYGON, PDFAnnotation.TYPE_POLY_LINE -> { a.setVertices(a.vertices.map(::pt).toTypedArray()); a.update() }
        else -> a.rect.let { q -> placeKeepingLook(a, com.artifex.mupdf.fitz.Rect(q.x0, q.y0, q.x0 + w, q.y0 + h)) }
    }
    true
}

internal fun PdfDoc.deleteObject(i: Int, id: Int) = annotate(i, "Delete") { p, _, _ -> p.annotation(id)?.let { p.deleteAnnotation(it); true } ?: false }

/**
 * Object [id] on page [i] moved among the page's objects and comments (Word's Arrange): [to] 1 forward, -1 backward,
 * 2 to the front, -2 to the back. False when there was nowhere to go.
 */
internal fun PdfDoc.restackObject(i: Int, id: Int, to: Int): Boolean {
    var moved = false
    annotate(i, if (to > 0) "Bring forward" else "Send backward") { p, _, _ ->
        val annots = p.getObject().get("Annots").takeIf { it.isArray } ?: return@annotate false
        val list = (0 until annots.size()).map { annots.get(it) }.toMutableList()
        val k = list.indexOfFirst { runCatching { it.asIndirect() == id }.getOrDefault(false) }.takeIf { it >= 0 } ?: return@annotate false
        val n = when (to) { 2 -> list.size - 1; -2 -> 0; else -> (k + to).coerceIn(0, list.size - 1) }
        if (n == k) return@annotate false
        list.add(n, list.removeAt(k)) // drawn in this order: the last on top
        val again = pdfDoc.newArray(); list.forEach { again.push(it) }; p.getObject().put("Annots", again)
        moved = true; true
    }
    return moved
}

/**
 * Object [id] alone as it is drawn on page [i] shown [w] px wide: only its own box (what is dragged over the page left
 * without it, and Position's moving piece).
 */
internal fun PdfDoc.objectPicture(i: Int, id: Int, w: Int): android.graphics.Bitmap? = synchronized(this) { runCatching { withPage(i) { p ->
    val m = com.artifex.mupdf.fitz.android.AndroidDrawDevice.fitPageWidth(p, w)
    val a = (p as com.artifex.mupdf.fitz.PDFPage).annotation(id) ?: return@withPage null
    val r = a.bounds.transform(m)
    val x0 = kotlin.math.floor(r.x0).toInt(); val y0 = kotlin.math.floor(r.y0).toInt()
    val bmp = android.graphics.Bitmap.createBitmap((kotlin.math.ceil(r.x1).toInt() - x0).coerceIn(1, 4096), (kotlin.math.ceil(r.y1).toInt() - y0).coerceIn(1, 4096), android.graphics.Bitmap.Config.ARGB_8888)
    // not cleared to white: the object alone, what is under it on the page seen through it
    val dev = com.artifex.mupdf.fitz.android.AndroidDrawDevice(bmp, x0, y0, false)
    try { a.run(dev, m, null) } finally { dev.close(); dev.destroy() }
    bmp
} }.onFailure { WriterLog.w("PDF: object picture", it) }.getOrNull() }
