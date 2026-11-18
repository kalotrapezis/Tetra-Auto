package com.kalotrapezis.drive

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SuperscriptSpan
import android.text.style.SubscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.text.style.RelativeSizeSpan
import org.w3c.dom.Element

/*
 * The Office engine (Plan.md › "The Office engine", step 5): how text is held and laid out wherever it is — a paragraph's
 * look, its letters' looks, its list marker and tabs, a laid block and a text box's frame. Words' pages, slides' boxes
 * and (later) a sheet's cells use them; the layout itself is `OfficePages.stack`, which shares Words' page code.
 */

/** How a paragraph is laid out: spacing (pt), line spacing (a multiple, or exact pt), indents (pt), its text's look. */
internal data class ParaLook(
    val before: Float = 0f, val after: Float = 0f,
    val lineMultiple: Float = 1f, val lineExact: Float? = null, val lineAtLeast: Boolean = false,
    val left: Float = 0f, val right: Float = 0f, val firstLine: Float = 0f, // firstLine < 0: hanging
    val pageBreakBefore: Boolean = false, val contextual: Boolean = false, val box: ParaBox? = null, val columnBreakBefore: Boolean = false,
    val sizePt: Float? = null, val bold: Boolean? = null, val italic: Boolean? = null, val color: Int? = null, val font: String? = null,
    val tabs: List<TabStop> = emptyList(),
    val listIndented: Boolean = false, // a list item's indent is already in left/firstLine
    val align: DocAlign? = null, // the style's alignment (a paragraph's own is DocPara.align)
)

/**
 * Paragraphs are laid out in twentieths of a point (Word's own unit): Android's layout takes whole numbers for widths
 * and indents, and in points a line that fits by a quarter point broke, a number that ends on its tab stop overshot.
 */
internal const val U = 20f
internal fun StaticLayout.topPt(line: Int) = getLineTop(line) / U
internal fun StaticLayout.bottomPt(line: Int) = getLineBottom(line) / U

/** A list marker's own look: [color] 0xAARRGGBB, [size] a share of its letters', [font] its face (a slide's buClr, buSzPct, buFont). */
internal class MarkerLook(val color: Int?, val size: Float, val font: String?)

/** A tab stop: where (pt from the text's left edge), how the text after it lines up ('l', 'r', 'c'), what fills the gap. */
internal data class TabStop(val pos: Float, val type: Char, val leader: Char? = null)

/** The same left/right/centre tab geometry for the pages and the editable text (points). */
internal fun tabGap(x: Float, followingWidth: Float, stops: List<TabStop>): Pair<TabStop, Float> {
    val stop = stops.firstOrNull { it.pos >= x - 0.05f } ?: TabStop((kotlin.math.floor(x / 36f) + 1) * 36f, 'l')
    val gap = stop.pos - x - when (stop.type) { 'r' -> followingWidth; 'c' -> followingWidth / 2; else -> 0f }
    return stop to gap.coerceAtLeast(0f)
}

/**
 * A list marker in a look of its own (a slide's bullet: PowerPoint's buClr, buSzPct, buFont): drawn in it — its face, else
 * the words' with the phone's fonts behind — but measured with the words' line, as PowerPoint and LibreOffice do (a symbol
 * font's tall letters made the line taller, 17 Oct).
 */
internal class MarkerSpan(val color: Int?, val scale: Float, val face: Typeface?) : android.text.style.ReplacementSpan() {
    private fun look(paint: android.graphics.Paint) = android.text.TextPaint(paint).also { p -> face?.let { p.typeface = it }; p.textSize *= scale; color?.let { p.color = it } }
    override fun getSize(paint: android.graphics.Paint, text: CharSequence?, start: Int, end: Int, fm: android.graphics.Paint.FontMetricsInt?): Int {
        fm?.let { paint.getFontMetricsInt(it) }
        return Math.round(look(paint).measureText(text ?: "", start, end))
    }
    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: android.graphics.Paint) {
        canvas.drawText(text ?: "", start, end, x, y.toFloat(), look(paint))
    }
}

/** A tab laid out: a gap of its own width, filled with its leader (the dots of a table of contents). */
internal class TabSpan(val width: Float, val leader: Char?) : android.text.style.ReplacementSpan() {
    override fun getSize(paint: android.graphics.Paint, text: CharSequence?, start: Int, end: Int, fm: android.graphics.Paint.FontMetricsInt?) = Math.round(width)
    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: android.graphics.Paint) {
        val c = leader ?: return
        val dot = paint.measureText(c.toString()).takeIf { it > 0f } ?: return
        val n = ((width - dot * 0.5f) / dot).toInt()
        if (n > 0) canvas.drawText(c.toString().repeat(n), x + width - n * dot, y.toFloat(), paint)
    }
}


internal class TextBoxDrawable(val frame: ShapePicture?, val text: Block, val inset: Pair<Float, Float>, /** 't', 'c' or 'b': where its words stand */ val anchor: Char = 't',
    /** WordArt bent: its words and their look, laid along the bend instead of in lines. */ val warp: Triple<TextWarp, String, android.text.TextPaint>? = null) : android.graphics.drawable.Drawable() {
    override fun draw(canvas: Canvas) {
        val b = bounds
        (frame ?: ShapePicture("rect", 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0.75f)).drawAt(canvas, b.left.toFloat(), b.top.toFloat(), b.width().toFloat(), b.height().toFloat())
        warp?.let { (bend, words, look) -> bent(canvas, bend, words, look, b.left.toFloat(), b.top.toFloat(), b.width().toFloat(), b.height().toFloat()); return }
        framed(canvas, text, b.left.toFloat(), b.top.toFloat(), b.width().toFloat(), b.height().toFloat(), inset.first, inset.second, inset.first, inset.second, anchor, true)
    }
    companion object {
        /** WordArt's [words] along [bend] in a box [bw] × [bh] at [x], [y], in [look]: letters as tall as the bend allows, smaller when they would not fit along it. */
        fun bent(canvas: Canvas, bend: TextWarp, words: String, look: android.text.TextPaint, x: Float, y: Float, bw: Float, bh: Float) {
            val paint = android.text.TextPaint(look)
            var m = bend.letters(bw, bh)
            fun along(m: Float) = android.graphics.Path().apply { val pts = bend.line(bw, bh, m); moveTo(x + pts[0].first, y + pts[0].second); pts.drop(1).forEach { (px, py) -> lineTo(x + px, y + py) } }
            var path = along(m)
            repeat(3) {
                paint.textSize = m / 0.72f // a letter's cap height is about 0.72 of its size
                val length = android.graphics.PathMeasure(path, false).length
                val needs = paint.measureText(words)
                if (needs <= length * 0.96f) return@repeat
                m *= length * 0.96f / needs; path = along(m)
            }
            canvas.save(); canvas.clipRect(x, y, x + bw, y + bh)
            canvas.drawTextOnPath(words, path, ((android.graphics.PathMeasure(path, false).length - paint.measureText(words)) / 2).coerceAtLeast(0f), 0f, paint)
            canvas.restore()
        }
        /**
         * Words laid out in a frame (a text box, a slide's box or shape, a cell): [text] [left]…[bottom] in from the box's
         * sides, at its top, middle or bottom ([anchor] 't', 'c', 'b'); cut at the box when [clip] (PowerPoint lets it overflow).
         */
        fun framed(canvas: Canvas, text: Block, x: Float, y: Float, w: Float, h: Float, left: Float, top: Float, right: Float, bottom: Float, anchor: Char, clip: Boolean) {
            canvas.save()
            if (clip) canvas.clipRect(x, y, x + w, y + h)
            val room = (h - (top + bottom) - text.height).let { if (clip) it.coerceAtLeast(0f) else it }
            canvas.translate(x + left, y + top + when (anchor) { 'c' -> room / 2; 'b' -> room; else -> 0f })
            LaidPage.drawParts(canvas, text.pieces, text.boxes, text.lines)
            canvas.restore()
        }
    }
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
    @Deprecated("Drawable") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** Some of a page laid out on its own (a table's row, a cell's contents): what goes where, from its top left. */
internal class Block(val height: Float, val pieces: List<LaidPage.Piece>, val boxes: List<LaidPage.Box>, val lines: List<LaidPage.Line>,
    val across: Float = 0f /* turned text: how wide it stands */) {
    fun moved(dx: Float, dy: Float) = Block(height, pieces.map {
        if (it.turn == 0) LaidPage.Piece(it.layout, it.firstLine, it.lastLine, it.x + dx, it.y + dy)
        else LaidPage.Piece(it.layout, it.firstLine, it.lastLine, it.x, it.y, it.turn, it.tx + dx, it.ty + dy) },
        boxes.map { LaidPage.Box(it.x + dx, it.y + dy, it.w, it.h, it.color) }, lines.map { LaidPage.Line(it.x1 + dx, it.y1 + dy, it.x2 + dx, it.y2 + dy, it.width, it.color) }, across)
}


/**
 * Letter looks Writer keeps raw in [DocRun.font] and shows (the user's test suite, 2026-10-01: all four looked plain):
 * capitals, small capitals, double strikethrough, double underline — Word's rPr elements, LibreOffice's attributes.
 */
internal data class LetterLooks(val caps: Boolean, val smallCaps: Boolean, val doubleStrike: Boolean, val doubleUnder: Boolean, val hidden: Boolean = false,
    /** Character spacing (Font › Advanced): points added after each letter, less than 0 closer (Word's w:spacing, LibreOffice's fo:letter-spacing) */ val spacing: Float = 0f) {
    val any get() = caps || smallCaps || doubleStrike || doubleUnder || hidden || spacing != 0f
    /** Character spacing drawn: [pt] after each letter, [unit] the layout's units a point (Android's letter spacing is in ems). */
    class Spaced(val pt: Float, val unit: Float) : android.text.style.MetricAffectingSpan() {
        private fun on(tp: android.text.TextPaint) { if (tp.textSize > 0f) tp.letterSpacing = pt * unit / tp.textSize }
        override fun updateMeasureState(tp: android.text.TextPaint) = on(tp)
        override fun updateDrawState(tp: android.text.TextPaint) = on(tp)
    }
    /** Two lines (struck through, or under) over its letters, drawn after the line: [drawSecondLines]. */
    class SecondLine(val strike: Boolean)
    companion object {
        private val NONE = LetterLooks(false, false, false, false)
        private fun on(font: String, tag: String) = Regex("""<w:$tag(\s*/>|\s+w:val="(1|true|on)")""").containsMatchIn(font)
        fun of(font: String?): LetterLooks = if (font.isNullOrEmpty()) NONE else LetterLooks(
            on(font, "caps") || "fo:text-transform=\"uppercase\"" in font,
            on(font, "smallCaps") || "fo:font-variant=\"small-caps\"" in font,
            on(font, "dstrike") || "style:text-line-through-type=\"double\"" in font,
            "<w:u w:val=\"double\"" in font || "style:text-underline-type=\"double\"" in font,
            on(font, "vanish") || "text:display=\"none\"" in font, spacingOf(font))

        /** A run's character spacing in points: Word's twentieths, or LibreOffice's length ("normal" none). */
        fun spacingOf(font: String): Float {
            Regex("""<w:spacing\b[^>]*\bw:val="(-?\d+)"""").find(font)?.let { return it.groupValues[1].toFloat() / 20f }
            val v = Regex("""fo:letter-spacing="([^"]+)"""").find(font)?.groupValues?.get(1) ?: return 0f
            val m = Regex("""(-?[\d.]+)\s*(pt|cm|mm|in|pc|px)?""").matchEntire(v.trim()) ?: return 0f
            val n = m.groupValues[1].toFloatOrNull() ?: return 0f
            return n * when (m.groupValues[2]) { "cm" -> 28.346457f; "mm" -> 2.8346457f; "in" -> 72f; "pc" -> 12f; "px" -> 0.75f; else -> 1f }
        }
        /** [r] with character spacing [pt] (0: none), in the file's own words: Word's w:spacing, LibreOffice's fo:letter-spacing. */
        fun withSpacing(r: DocRun, kind: OfficeKind, pt: Float): DocRun = r.copy(font = if (kind == OfficeKind.Docx)
            r.font.orEmpty().replace(Regex("""<w:spacing\b[^>]*/>"""), "") + (if (pt != 0f) """<w:spacing w:val="${Math.round(pt * 20)}"/>""" else "")
            else r.font.orEmpty().replace(Regex("""\s?fo:letter-spacing="[^"]*""""), "") + (if (pt != 0f) " fo:letter-spacing=\"${String.format(java.util.Locale.ROOT, "%.2fpt", pt)}\"" else ""))

        /** The [SecondLine] spans of [layout]'s lines [first]..[last]: two thin lines each, in the text's colour. */
        fun drawSecondLines(canvas: android.graphics.Canvas, layout: android.text.Layout, first: Int, last: Int) {
            val text = layout.text as? android.text.Spanned ?: return
            val spans = text.getSpans(0, text.length, SecondLine::class.java).takeIf { it.isNotEmpty() } ?: return
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = layout.paint.color }
            for (sp in spans) {
                val a = text.getSpanStart(sp); val z = text.getSpanEnd(sp)
                for (line in maxOf(first, layout.getLineForOffset(a))..minOf(last, layout.getLineForOffset(z))) {
                    val s0 = maxOf(a, layout.getLineStart(line)); val s1 = minOf(z, layout.getLineEnd(line))
                    if (s1 <= s0) continue
                    val x1 = layout.getPrimaryHorizontal(s0); val x2 = if (s1 >= layout.getLineEnd(line)) layout.getLineRight(line) else layout.getPrimaryHorizontal(s1)
                    val base = layout.getLineBaseline(line).toFloat(); val h = (layout.getLineDescent(line) - layout.getLineAscent(line)).toFloat()
                    paint.strokeWidth = h * 0.045f
                    val ys = if (sp.strike) listOf(base - h * 0.33f, base - h * 0.21f) else listOf(base + h * 0.07f, base + h * 0.17f)
                    ys.forEach { y -> canvas.drawLine(x1, y, x2, y, paint) }
                }
            }
        }
    }
}
