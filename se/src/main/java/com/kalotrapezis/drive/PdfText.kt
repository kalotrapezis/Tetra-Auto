package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.graphics.drawscope.rotate
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import org.json.JSONObject
import kotlin.math.ceil

/**
 * A text's frame and the look new words take: the font, size and colour words typed in it start with; the box's fill
 * and border's colour (0xRRGGBB; none: clear, no border), its turn. Bold, italic, underline, highlight and alignment are
 * the words' and paragraphs' own now (Words'); they are read only from texts put on before 27 Oct.
 */
internal data class PdfTextLook(val font: String = "Times New Roman", val size: Float = 12f, val bold: Boolean = false, val italic: Boolean = false,
    val underline: Boolean = false, /** 0xRRGGBB */ val color: Int = 0x000000, /** 0xRRGGBB or none */ val highlight: Int? = null, val align: DocAlign = DocAlign.Start,
    val fill: Int? = null, val line: Int? = null, /** turned clockwise, degrees (0–359) */ val turn: Int = 0)

/** Some of an old text's words with a look of their own (24 Oct): read into Words' runs ([PdfText.invoke]). */
internal data class PdfRun(val from: Int, val to: Int, val bold: Boolean = false, val italic: Boolean = false, val underline: Boolean = false,
    val strike: Boolean = false, /** 0xRRGGBB */ val color: Int? = null, val highlight: Int? = null)

/**
 * Words on a PDF page (Fill & Sign › Text, Insert › Text box) as Words' own (PDF_LIKE_WORDS.md, E3): Words' paragraphs
 * ([doc], kept in the annotation as a .docx), laid out and drawn by Words' engine ([OfficePages.stack]) and typed in
 * Words' editor, so Home's tools are Words'. [look]: the frame, and the font, size and colour new words take; [framed]
 * a text box (as wide as it was drawn) or a text (as wide as its longest line).
 */
internal data class PdfText(val doc: OfficeDoc, val look: PdfTextLook, val framed: Boolean) {
    /** The .docx it was read from: written back into it, so its styles stay (Words saves into the file it opened). */
    var source: ByteArray? = null
    /** Its size as laid out before turning (points), as the file keeps it; null: from its place on the page ([unturned]). */
    var laid: Pair<Float, Float>? = null
    val words: String get() = doc.paras.joinToString("\n") { it.text }
    /** Its paragraphs' styles (Title, Heading 1…): its .docx's, else a new document's. */
    val styles: DocStyles by lazy { DocStyles.of(OfficeKind.Docx, source ?: BLANK) }
    /** The same with [paras] for words (its styles kept). */
    fun with(paras: List<DocPara>) = copy(doc = doc.copy(paras = paras)).also { it.source = source; it.laid = laid }
    fun with(look: PdfTextLook) = copy(look = look).also { it.source = source; it.laid = laid }
    fun docx(): ByteArray = OfficeFormats.write(doc, OfficeKind.Docx, source ?: BLANK)
    /** Its frame kept in the file with it (the annotation's own key), [laid] its size before turning; its words go in the .docx. */
    fun json(laid: Pair<Float, Float>? = null): String = JSONObject().put("font", look.font).put("size", look.size.toDouble()).put("color", look.color)
        .put("framed", framed).put("fill", look.fill ?: -1).put("line", look.line ?: -1).put("turn", look.turn)
        .apply { laid?.let { (w, h) -> put("laidW", w.toDouble()).put("laidH", h.toDouble()) } }.toString()

    /**
     * [query] (any case) in its words with [with] in its place — the first one, or [all] — each taking the look of the
     * letters it took the place of (Words' Replace keeps the look); how many.
     */
    fun replaced(query: String, with: String, all: Boolean): Pair<PdfText, Int> {
        if (query.isEmpty()) return this to 0
        var n = 0
        val paras = doc.paras.map { p ->
            if (n > 0 && !all) return@map p
            var runs = p.runs
            val found = Regex(Regex.escape(query), RegexOption.IGNORE_CASE).findAll(p.text).map { it.range }.toList().let { if (all) it else it.take(1) }
            found.asReversed().forEach { m -> runs = replacedRuns(runs, m.first, m.last + 1, with) }
            n += found.size
            p.copy(runs = runs)
        }
        return with(paras) to n
    }
    val pad get() = if (framed) 4f else 1f
    /** Turned at all: it keeps the width it was laid out in (the page's edge means nothing to it). */
    val turned get() = look.turn != 0
    /** [r] (where it is on the page) as it is laid out before turning: across, down. */
    fun unturned(r: android.graphics.RectF) = laid ?: if (look.turn % 180 == 90) r.height() to r.width() else r.width() to r.height()

    companion object {
        /**
         * A new Words document's parts (its styles: Title, Heading 1…), for texts made here: single lines and nothing
         * after a paragraph (Word's new documents' 1.08 and 8 pt would leave a typed line standing off its box).
         */
        private val BLANK: ByteArray by lazy {
            val parts = OfficeFormats.unzip(OfficeFormats.write(OfficeDoc(emptyList()), OfficeKind.Docx))
            parts["word/styles.xml"]?.let { st -> parts["word/styles.xml"] = String(st, Charsets.UTF_8)
                .replace("""<w:spacing w:after="160" w:line="259" w:lineRule="auto"/>""", """<w:spacing w:after="0" w:line="240" w:lineRule="auto"/>""").toByteArray() }
            OfficeFormats.zip(parts)
        }

        /** Words typed with [look] (a text put on before 27 Oct: its words, one look, [runs] with their own), as Words' paragraphs. */
        operator fun invoke(words: String, look: PdfTextLook, framed: Boolean, runs: List<PdfRun> = emptyList()): PdfText {
            var at = 0
            val paras = words.split('\n').map { line ->
                val from = at; at += line.length + 1
                val cuts = sortedSetOf(0, line.length)
                runs.forEach { r -> cuts += (r.from - from).coerceIn(0, line.length); cuts += (r.to - from).coerceIn(0, line.length) }
                DocPara(OfficeFormats.tidy(cuts.toList().zipWithNext().filter { (a, b) -> b > a }.map { (a, b) ->
                    val r = runs.firstOrNull { it.from <= from + a && it.to >= from + b }
                    OfficeFormats.withFont(DocRun(line.substring(a, b), bold = look.bold || r?.bold == true, italic = look.italic || r?.italic == true,
                        underline = look.underline || r?.underline == true, strike = r?.strike == true, sizePt = look.size,
                        color = (r?.color ?: look.color).takeIf { it != 0 }, highlight = r?.highlight ?: look.highlight), OfficeKind.Docx, look.font)
                }), align = look.align)
            }
            return PdfText(OfficeDoc(paras, basePt = look.size), look, framed)
        }

        /** As kept: its frame ([json]) and its words ([docx]; none: a text put on before 27 Oct, its words in [json]). */
        fun parse(json: String, docx: ByteArray? = null): PdfText? = runCatching {
            val o = JSONObject(json)
            val look = PdfTextLook(o.optString("font", "Times New Roman"), o.optDouble("size", 12.0).toFloat(), o.optBoolean("bold"), o.optBoolean("italic"),
                o.optBoolean("underline"), o.optInt("color", 0), o.optInt("highlight", -1).takeIf { it >= 0 }, runCatching { DocAlign.valueOf(o.optString("align")) }.getOrDefault(DocAlign.Start),
                o.optInt("fill", -1).takeIf { it >= 0 }, o.optInt("line", if (o.optBoolean("framed")) 0 else -1).takeIf { it >= 0 }, // a box put on before borders were chosen: its black frame
                Math.floorMod(o.optInt("turn"), 360))
            val t = if (docx != null) PdfText(OfficeFormats.read(docx, OfficeKind.Docx), look, o.optBoolean("framed")).also { it.source = docx }
                else PdfText(o.getString("words"), look, o.optBoolean("framed"), o.optJSONArray("runs")?.let { a -> (0 until a.length()).map { k -> a.getJSONObject(k).let { r ->
                    PdfRun(r.getInt("from"), r.getInt("to"), r.optBoolean("b"), r.optBoolean("i"), r.optBoolean("u"), r.optBoolean("s"),
                        r.optInt("color", -1).takeIf { it >= 0 }, r.optInt("highlight", -1).takeIf { it >= 0 }) } } }.orEmpty())
            t.also { if (o.has("laidW")) it.laid = o.getDouble("laidW").toFloat() to o.getDouble("laidH").toFloat() }
        }.getOrNull()

        /** Its place on the page for [w] × [h] laid out, turned: the box round the turned one. */
        fun bounds(t: PdfText, w: Float, h: Float): Pair<Float, Float> = turnedBox(w, h, t.look.turn.toFloat())

        /** Its words laid out by Words' engine [width] across (Slides' boxes' way). */
        private fun block(t: PdfText, width: Float): Block = OfficePages.stack(t.doc.copy(ligatures = false), t.doc.paras, t.styles, DocPage(), width.coerceAtLeast(1f), { null }, null, 0f, 0f)

        /**
         * Its size on the page (points) given [room] across: a text box keeps the width it was drawn and grows down to
         * [minHeight]; a text is as wide as its longest line.
         */
        fun size(t: PdfText, room: Float, minHeight: Float = 0f): Pair<Float, Float> {
            val inner = room - 2 * t.pad
            val b = block(t, inner)
            val w = if (t.framed) room else (b.pieces.maxOfOrNull { pc -> (pc.firstLine..pc.lastLine).maxOfOrNull { pc.layout.getLineWidth(it) / 20f + pc.x } ?: 0f } ?: 0f).let { it + 2 * t.pad }.coerceAtMost(room)
            return ceil(w) to ceil(maxOf(minHeight, b.height + 2 * t.pad))
        }

        /**
         * The words drawn [w] × [h] points (as laid out, before turning): a one-page PDF of their own (Android's, its fonts
         * embedded), to be the annotation's look — turned, its page the other way round.
         */
        fun pdf(t: PdfText, w: Float, h: Float): ByteArray {
            val doc = android.graphics.pdf.PdfDocument()
            try {
                val (pw, ph) = bounds(t, w, h)
                val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(ceil(pw).toInt(), ceil(ph).toInt(), 1).create())
                // turned about its middle, in the middle of the box round it
                if (t.turned) page.canvas.apply { translate(pw / 2, ph / 2); rotate(t.look.turn.toFloat()); translate(-w / 2, -h / 2) }
                draw(page.canvas, t, w, h)
                doc.finishPage(page)
                return java.io.ByteArrayOutputStream().also { doc.writeTo(it) }.toByteArray()
            } finally { doc.close() }
        }

        private fun draw(c: Canvas, t: PdfText, w: Float, h: Float) {
            t.look.fill?.let { f -> c.drawRect(0f, 0f, w, h, Paint().apply { color = 0xFF000000.toInt() or f }) }
            t.look.line?.let { l -> c.drawRect(0.5f, 0.5f, w - 0.5f, h - 0.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.75f; color = 0xFF000000.toInt() or l }) }
            val b = block(t, w - 2 * t.pad)
            c.save(); c.translate(t.pad, t.pad); LaidPage.drawParts(c, b.pieces, b.boxes, b.lines); c.restore()
        }
    }
}

/**
 * Words being typed on page [page]: their top left at [x], [y] (page points), [room] across, at least [minHeight] tall;
 * [framed] a text box; [replacing] the text typed in again ([PdfObject.id]: taken out when these go on).
 */
internal data class PdfTyping(val page: Int, val x: Float, val y: Float, val room: Float, val minHeight: Float, val framed: Boolean, val replacing: Int? = null)

/**
 * The words typed on the page itself in Words' editor ([state], its letters' sizes Words' editor's: [basePt] shown at
 * [BASE_SP]), scaled so they stand on the page as they will be put on; [page] the page's size in points.
 */
@Composable
internal fun BoxScope.PdfTypingField(page: androidx.compose.ui.geometry.Size, t: PdfTyping, state: com.mohamedrejeb.richeditor.model.RichTextState, look: PdfTextLook, basePt: Float,
    spell: Spelling? = null) =
    BoxWithConstraints(Modifier.matchParentSize()) {
        val k = constraints.maxWidth / page.width // pixels a page point
        val density = LocalDensity.current
        val sp = with(density) { BASE_SP.sp.toPx() }
        val s = (k * basePt / sp).coerceAtLeast(0.05f) // Words' editor's size to the page's
        val pad = if (t.framed) 4f else 1f
        // a new one with nothing typed yet: only the cursor (Words' click-and-type), its frame and fill with the first letter
        val shown = t.replacing != null || state.annotatedString.text.isNotEmpty()
        val focus = remember { FocusRequester() }
        var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) } // for Words' spelling lines
        LaunchedEffect(t) { focus.requestFocus() } // the keyboard at once
        // the selection blue on the white page whatever the app's theme (dark: Ctrl+A's selection hardly showed, 24 Oct)
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.text.selection.LocalTextSelectionColors provides
            androidx.compose.foundation.text.selection.TextSelectionColors(handleColor = Color(0xFF1A73E8), backgroundColor = Color(0x661A73E8))) {
        with(density) {
            com.mohamedrejeb.richeditor.ui.BasicRichTextEditor(state, cursorBrush = SolidColor(Color.Black),
                // laid out as wide as Words' editor's size makes it, then scaled to the page (not squeezed into the page's width first)
                modifier = Modifier.offset((k * t.x).toDp(), (k * t.y).toDp()).wrapContentSize(androidx.compose.ui.Alignment.TopStart, unbounded = true)
                    .graphicsLayer { scaleX = s; scaleY = s; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f) }
                    .requiredWidth((k * t.room / s).toDp()).heightIn(min = (k * maxOf(t.minHeight, basePt * 1.2f + 2 * pad) / s).toDp())
                    // the words as they were hidden while typed again; the box's own fill
                    .background(look.fill?.takeIf { shown }?.let { Color(0xFF000000 or it.toLong()) } ?: if (t.replacing != null) Color.White else Color.Transparent)
                    .border(1.dp, if (!shown) Color.Transparent else look.line?.let { Color(0xFF000000 or it.toLong()) } ?: Color(0x991A73E8)).padding((k * pad / s).toDp()).focusRequester(focus)
                    // Words' red wavy lines under the words that look misspelled, in the editor's own layout
                    .drawWithContent { drawContent(); val l = layout; if (spell != null && l != null && spell.shown.isNotEmpty()) spellingLines(l, spell.shown) },
                onTextLayout = { layout = it },
                textStyle = TextStyle(color = Color(0xFF000000 or look.color.toLong()), fontSize = BASE_SP.sp, fontFamily = OfficeFonts.family(look.font)))
        }
        }
    }

/** Where a chosen text is being dragged, sized or turned to (page points, degrees) until the finger lifts: drawn, then put on. */
internal class PdfFramePreview {
    var shift by androidx.compose.runtime.mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)
    var grow by androidx.compose.runtime.mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)
    var spin by androidx.compose.runtime.mutableStateOf<Float?>(null)
    fun reset() { shift = androidx.compose.ui.geometry.Offset.Zero; grow = androidx.compose.ui.geometry.Offset.Zero; spin = null }
}

/** Words' blue for what is chosen (its outline and [ObjectHandles]). */
private val CHOSEN_BLUE = Color(0xFF1A73E8)

/**
 * The chosen text as Words shows a chosen text box (the user, 25 Oct: "everything as in Words"): its outline in Words'
 * blue, turned with its words, and Words' own handles ([ObjectHandles]) — the corner and sides stretch it, the knob
 * on its stem turns it. [r] its place on the page and [laid] its size before turning (page points), [turn] its turn;
 * [preview] where a finger has it. [sized] gets its new size before turning, [turned] its new turn.
 */
@Composable
internal fun BoxScope.PdfChosenHandles(page: androidx.compose.ui.geometry.Size, r: android.graphics.RectF, laid: Pair<Float, Float>, turn: Float, preview: PdfFramePreview,
    rotatable: Boolean, keepAspect: Boolean, sized: (Float, Float) -> Unit, turned: (Float) -> Unit) = BoxWithConstraints(Modifier.matchParentSize()) {
    val k = constraints.maxWidth / page.width // pixels a page point
    val (lw0, lh0) = laid
    val lw = maxOf(12f, lw0 + preview.grow.x); val lh = maxOf(8f, lh0 + preview.grow.y)
    // stretched from its top left as Words', moved with the finger
    val left = (r.centerX() + preview.shift.x - lw0 / 2) * k; val top = (r.centerY() + preview.shift.y - lh0 / 2) * k
    val angle = preview.spin ?: turn
    androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
        rotate(angle, androidx.compose.ui.geometry.Offset(left + lw * k / 2, top + lh * k / 2)) {
            drawRect(CHOSEN_BLUE, androidx.compose.ui.geometry.Offset(left, top), androidx.compose.ui.geometry.Size(lw * k, lh * k), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx())) }
    }
    if (preview.shift == androidx.compose.ui.geometry.Offset.Zero) // as Words', none while it is dragged
        ObjectHandles(left, top, lw * k, lh * k, angle, rotatable = rotatable, keepAspect = keepAspect,
            size = { a, b -> preview.grow = androidx.compose.ui.geometry.Offset(a / k - lw0, b / k - lh0) },
            sized = { val g = preview.grow; preview.reset(); if (g != androidx.compose.ui.geometry.Offset.Zero) sized(lw0 + g.x, lh0 + g.y) },
            turn = { preview.spin = it }, turned = { val a = preview.spin; preview.reset(); a?.let { turned(it) } })
}

/**
 * An object on the page under a finger or the mouse, as Words' (the user, 25–26 Oct: "when I press it with the mouse or
 * the finger, it is to move it"): pressed and dragged it moves at once — chosen if it was not ([choose]) — shown in
 * [preview] and [moved] when let go; a tap chooses it, a tap on a chosen text types in it ([edit]); held still it is
 * chosen and Position opens ([long], Words' long press). [objectAt] the object under a point (page points); [chosen]
 * the chosen one, whose handles keep their own touches. Elsewhere the page's own.
 */
internal fun Modifier.pdfObjectGestures(page: androidx.compose.ui.geometry.Size, on: Boolean, objectAt: (androidx.compose.ui.geometry.Offset) -> PdfObject?,
    chosen: () -> PdfObject?, preview: PdfFramePreview, choose: (PdfObject) -> Unit, edit: () -> Unit, long: () -> Unit,
    moved: (Float, Float) -> Unit): Modifier = if (!on) this else pointerInput(page) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
        if (currentEvent.buttons.isSecondaryPressed) return@awaitEachGesture // a mouse's right button: the menu's
        val k = size.width / page.width // pixels a page point
        val p = down.position / k
        val c = chosen()
        // on one of Words' handles of the chosen one: theirs ([ObjectHandles]: 32 dp grips, the knob 36 dp over the top)
        if (c != null) {
            val (lw, lh) = c.laid; val mid = androidx.compose.ui.geometry.Offset(c.rect.centerX() * k, c.rect.centerY() * k)
            val w = lw * k; val h = lh * k
            val grips = listOf(androidx.compose.ui.geometry.Offset(w / 2, h / 2), androidx.compose.ui.geometry.Offset(w / 2, 0f), androidx.compose.ui.geometry.Offset(0f, h / 2),
                androidx.compose.ui.geometry.Offset(0f, -h / 2 - 36.dp.toPx())).map { mid + turnedBy(it, c.turn) }
            if (grips.any { (it - down.position).getDistance() < 16.dp.toPx() }) return@awaitEachGesture
        }
        val onChosen = c?.rect?.let { r -> p.x in r.left..r.right && p.y in r.top..r.bottom } == true
        val under = if (onChosen) c else objectAt(p)
        if (under == null) return@awaitEachGesture
        down.consume()
        var going = false; var lifted = false
        // still until the finger moves, lifts or has been held a long press's time
        val decided = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val ch = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                if (ch == null || !ch.pressed) { ch?.consume(); lifted = true; return@withTimeoutOrNull true }
                ch.consume()
                if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop) { going = true; return@withTimeoutOrNull true }
            }
            @Suppress("UNREACHABLE_CODE") true
        }
        if (decided == null) { // held: chosen, and Position (Words')
            if (!onChosen) choose(under)
            long()
            while (true) { val ch = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break; ch.consume(); if (!ch.pressed) break }
            return@awaitEachGesture
        }
        if (lifted) { if (onChosen) edit() else choose(under); return@awaitEachGesture } // a tap chooses it; on a chosen text, its words are typed in
        if (!onChosen) choose(under)
        while (true) {
            val ch = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            ch.consume()
            preview.shift = ch.position / k - p
            if (!ch.pressed) break
        }
        val sh = preview.shift; preview.reset(); if (going) moved(sh.x, sh.y)
    }
}

/** Layout › Size's papers (points), as Word's and Tables' Size. */
internal object PdfPaper {
    val all = listOf("A4" to Size(595f, 842f), "A5" to Size(420f, 595f), "A3" to Size(842f, 1191f), "Letter" to Size(612f, 792f), "Legal" to Size(612f, 1008f))
    /** [s]'s paper ("A4"), either way round; null when it is none of them. */
    fun paper(s: Size): String? {
        val (w, h) = minOf(s.width, s.height) to maxOf(s.width, s.height)
        return all.firstOrNull { (_, p) -> kotlin.math.abs(p.width - w) < 3 && kotlin.math.abs(p.height - h) < 3 }?.first
    }
    /** [s]'s paper and way round ("A4 portrait", in [way]'s words: the paper, landscape), else its size in [unit]. */
    fun name(s: Size, way: (String, Boolean) -> String = { p, land -> "$p ${if (land) "landscape" else "portrait"}" }, unit: String = WriterUnits.name): String =
        paper(s)?.let { way(it, s.width > s.height) } ?: "${WriterUnits.text(s.width)} × ${WriterUnits.text(s.height)} $unit"
}

/** Layout › Margins (Word's presets), the same for every PDF: where Position puts a text and where a new one starts. */
internal object PdfMargins {
    /** name → top/bottom, left/right (points) */
    val all = listOf("Normal" to (72f to 72f), "Narrow" to (36f to 36f), "Moderate" to (72f to 54f), "Wide" to (72f to 144f))
    fun page(name: String) = (all.firstOrNull { it.first == name } ?: all[0]).second.let { (tb, lr) -> DocPage(top = tb, bottom = tb, left = lr, right = lr) }
    fun nameOf(m: DocPage) = all.firstOrNull { (_, v) -> v.first == m.top && v.second == m.left }?.first
    /** Kept as a preset's name, or as Page Setup's own numbers (top, bottom, left, right). */
    fun load(context: android.content.Context): DocPage {
        val kept = context.getSharedPreferences("office_editor", android.content.Context.MODE_PRIVATE).getString("pdf_margins", "Normal") ?: "Normal"
        return kept.split(',').mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 4 }?.let { (t, b, l, r) -> DocPage(top = t, bottom = b, left = l, right = r) } ?: page(kept)
    }
    fun save(context: android.content.Context, name: String) = context.getSharedPreferences("office_editor", android.content.Context.MODE_PRIVATE).edit().putString("pdf_margins", name).apply()
    fun save(context: android.content.Context, m: DocPage) = save(context, nameOf(m) ?: listOf(m.top, m.bottom, m.left, m.right).joinToString(","))
}

/** [paras] in [look]'s font (a text's one font, as a Slides box's), its colour on words with none of their own. */
internal fun fonted(paras: List<DocPara>, look: PdfTextLook): List<DocPara> = paras.map { p -> p.copy(runs = p.runs.map { r ->
    if (r.image != null) r else OfficeFormats.withFont(r, OfficeKind.Docx, look.font).let { if (it.color == null && look.color != 0) it.copy(color = look.color) else it } }) }

/** [runs] with their letters [a] to [b] made [with], in the look of the run [a] was in. */
internal fun replacedRuns(runs: List<DocRun>, a: Int, b: Int, with: String): List<DocRun> {
    val out = ArrayList<DocRun>(); var at = 0; var put = false
    for (r in runs) {
        val s = at; val e = at + r.text.length; at = e
        if (e <= a || s >= b) { if (!put && s >= b) { out += (out.lastOrNull() ?: r).copy(text = with); put = true }; out += r; continue }
        if (s < a) out += r.copy(text = r.text.substring(0, a - s))
        if (!put) { out += r.copy(text = with); put = true }
        if (e > b) out += r.copy(text = r.text.substring(b - s))
    }
    if (!put) out += (runs.lastOrNull() ?: DocRun("")).copy(text = with)
    return OfficeFormats.tidy(out)
}
