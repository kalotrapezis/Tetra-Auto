package com.kalotrapezis.drive

import android.graphics.Canvas

/**
 * A slide's words laid out and drawn by Words' engine (the Office engine, step 1, 17 Oct: "Text is text no matter where it
 * is"): a [SlideText] as Words' paragraphs and runs with looks of their own, stacked by [OfficePages.stack] and drawn in
 * the box by [TextBoxDrawable.framed] — not cut at the box, as PowerPoint lets words overflow it.
 */
internal object SlidesText {
    /** A slide's paragraphs' looks and bullets. */
    private class Styles(val looks: java.util.IdentityHashMap<DocPara, ParaLook>, val bullets: java.util.IdentityHashMap<DocPara, SlideBullet>) : DocStyles() {
        override fun look(p: DocPara) = looks[p] ?: ParaLook()
        // a bullet no face here has (Noto Sans Symbols' 🞆): Word's •; one the phone draws as a colour emoji (⚫) asked for as a letter, in its colour
        override fun marker(p: DocPara) = bullets[p]?.text?.let { bullet(it) }?.let { b -> if (b.codePointCount(0, b.length) > 1 || glyphs.hasGlyph(b)) b + '\uFE0E' else "•" }
        // centred or to the right: the bullet before the words by its hang (tab stops count from a line's start)
        override fun markerHangs(p: DocPara) = p.align == DocAlign.Center || p.align == DocAlign.End
        override fun markerLook(p: DocPara) = bullets[p]?.let { MarkerLook(it.color, it.size, it.font) }
        private val glyphs = android.graphics.Paint()
    }

    /** Laid out per box: slides are drawn often (thumbnails, each frame of an animation). */
    private val laid = java.util.WeakHashMap<SlideText, HashMap<Long, Block>>()

    /** [t]'s words in a box [w] wide, their sizes times [k] (shrink on overflow), from the box's inside left. */
    fun block(t: SlideText, w: Float, k: Float = 1f): Block {
        val key = (w.toRawBits().toLong() shl 32) or k.toRawBits().toLong().and(0xFFFFFFFFL)
        synchronized(laid) { laid[t]?.get(key) }?.let { return it }
        val styles = Styles(java.util.IdentityHashMap(), java.util.IdentityHashMap())
        val paras = t.paras.map { p ->
            val first = p.runs.firstOrNull()
            // shortcut: a run's colour has no see-through part (Words' runs are 0xRRGGBB); when a deck needs one, carry alpha in DocRun
            p.para.copy(runs = p.runs.map { r -> r.copy(sizePt = (r.sizePt ?: 18f) * k) }).also { d ->
                styles.looks[d] = p.look.copy(lineMultiple = p.look.lineMultiple * single(first, styles), lineExact = p.look.lineExact?.times(k),
                    sizePt = (first?.sizePt ?: 18f) * k, font = styles.fontName(first?.font), color = first?.color)
                // an empty paragraph shows no bullet, as PowerPoint's
                p.bullet?.takeIf { p.runs.any { it.text.isNotEmpty() } }?.let { styles.bullets[d] = it }
            }
        }
        val doc = OfficeDoc(paras, basePt = 18f * k)
        val inner = maxOf(1f, w - t.insets[0] - t.insets[2])
        fun stack(width: Float) = OfficePages.stack(doc, paras, styles, DocPage(), width, { null }, null, 0f, 0f)
        val block = if (t.wrap) stack(inner) else {
            // Not wrapped: as wide as its longest line, standing out of the box on the side its alignment says.
            val wide = stack(100_000f).pieces.maxOfOrNull { pc -> (pc.firstLine..pc.lastLine).maxOf { pc.layout.getLineWidth(it) } / 20f + pc.x } ?: 0f
            val width = maxOf(inner, wide + 1f)
            // shortcut: the first paragraph's alignment places them all; paragraphs aligned differently in one unwrapped box are rare
            stack(width).moved(when (paras.firstOrNull()?.align) { DocAlign.Center -> (inner - width) / 2; DocAlign.End -> inner - width; else -> 0f }, 0f)
        }
        synchronized(laid) { laid.getOrPut(t) { HashMap() }[key] = block }
        return block
    }

    /**
     * PowerPoint's single line is 1.2 times its letters' size (LibreOffice draws it so too), Word's the font's own height:
     * the share of Words' line that makes PowerPoint's, for [r]'s face.
     */
    private fun single(r: DocRun?, styles: DocStyles): Float {
        val fm = android.text.TextPaint().apply { typeface = OfficeFonts.styled(OfficeFonts.typeface(styles.fontName(r?.font)), r?.bold == true, r?.italic == true); textSize = 100f }.fontMetrics
        return 120f / (fm.descent - fm.ascent + fm.leading).coerceAtLeast(1f)
    }

    /** Where a Drop cap, dropped, splits paragraph [i] of [t]: [at] letters into its words (its first letter off) its line [lines] ends (all of them: fewer),
     *  [top] its first line's baseline under the words' top, [step] line [lines]' baseline under the first's (pt). */
    class Drop(val at: Int, val top: Float, val step: Float)
    fun drop(t: SlideText, w: Float, i: Int, left: Float, lines: Int): Drop? {
        val p = t.paras.getOrNull(i) ?: return null
        val k = p.runs.indexOfFirst { it.text.isNotEmpty() }.takeIf { it >= 0 } ?: return null
        val r = p.runs[k]
        val rest = SlidePara(p.para.copy(runs = p.runs.take(k) + r.copy(text = r.text.substring(r.text.offsetByCodePoints(0, 1))) + p.runs.drop(k + 1)), p.look.copy(left = left, firstLine = 0f), p.bullet, p.base)
        val b = block(SlideText(t.paras.take(i) + rest, t.anchor, t.insets, t.scale, t.wrap, t.shrink), w)
        val pc = b.pieces.lastOrNull() ?: return null; val lay = pc.layout
        val words = rest.runs.joinToString("") { it.text }
        val n = minOf(lines, lay.lineCount)
        // layout units are twentieths of a point (OfficePages' U); fewer lines than asked: the rest at the first lines' pitch
        val pitch = if (lay.lineCount > 1) (lay.getLineBaseline(1) - lay.getLineBaseline(0)) / 20f else lay.getLineBottom(0) / 20f
        val step = (lay.getLineBaseline(n - 1) - lay.getLineBaseline(0)) / 20f + (lines - n) * pitch
        val at = if (lay.lineCount <= lines) words.length else (lay.getLineEnd(lines - 1) - (lay.text.length - words.length)).coerceIn(0, words.length)
        return Drop(at, pc.y + lay.getLineBaseline(0) / 20f, step)
    }

    /** How tall [t] stands in a box [w] wide, its insets too (a table's row grows to it). */
    fun height(t: SlideText, w: Float) = block(t, w).height + t.insets[1] + t.insets[3]

    /** [b]'s lines in [n] columns [step] apart (a column's width and the gap), each going on into the next below [room]; the last takes the rest. */
    fun columns(b: Block, n: Int, step: Float, room: Float): Block {
        val out = ArrayList<LaidPage.Piece>(); var col = 0; var shift = 0f; var tallest = 0f
        for (p in b.pieces) {
            if (p.turn != 0) { out += p; continue }
            val top = p.layout.getLineTop(p.firstLine) / 20f
            var from = p.firstLine
            for (line in p.firstLine..p.lastLine) {
                val y = p.y + (p.layout.getLineTop(line) / 20f - top); val foot = p.y + (p.layout.getLineBottom(line) / 20f - top)
                if (foot - shift > room && col < n - 1 && y - shift > 0f) {
                    if (line > from) out += LaidPage.Piece(p.layout, from, line - 1, p.x + col * step, p.y + (p.layout.getLineTop(from) / 20f - top) - shift)
                    col++; shift = y; from = line
                }
                tallest = maxOf(tallest, foot - shift)
            }
            out += LaidPage.Piece(p.layout, from, p.lastLine, p.x + col * step, p.y + (p.layout.getLineTop(from) / 20f - top) - shift)
        }
        // shortcut: a paragraph's shading and borders (rare in a slide's columns) stay where one column would put them
        return Block(tallest, out, b.boxes, b.lines, b.across)
    }

    /** [t] in its box: from the top, the middle or the bottom; made smaller until it fits when it shrinks on overflow. */
    fun draw(c: Canvas, t: SlideText, x: Float, y: Float, w: Float, h: Float) {
        // Text direction: laid in the box turned (its height across), then turned back with it, as PowerPoint's
        if (t.vert != 0) { c.save()
            if (t.vert == 90) { c.translate(x + w, y); c.rotate(90f) } else { c.translate(x, y + h); c.rotate(-90f) }
            draw(c, SlideText(t.paras, t.anchor, t.insets, t.scale, t.wrap, t.shrink, t.warp, t.cols, t.colGap), 0f, 0f, h, w); c.restore(); return }
        // WordArt bent: its words along the bend, in its first letters' look (as Words draws a document's)
        t.warp?.let { bend ->
            val r = t.paras.flatMap { it.runs }.firstOrNull { it.text.isNotBlank() } ?: return
            val look = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF000000.toInt() or (r.color ?: 0); typeface = OfficeFonts.styled(OfficeFonts.typeface(DocStyles.Plain.fontName(r.font)), r.bold, r.italic) }
            TextBoxDrawable.bent(c, bend, t.paras.joinToString(" ") { p -> p.runs.joinToString("") { it.text }.trim() }.trim(), look, x, y, w, h)
            return
        }
        val room = h - t.insets[1] - t.insets[3]
        // in columns: laid as wide as one, flowing into the next where the box's room ends (PowerPoint's way)
        val cols = if (t.wrap) t.cols.coerceAtLeast(1) else 1
        val colW = (w - t.insets[0] - t.insets[2] - t.colGap * (cols - 1)) / cols
        fun laid(k: Float) = if (cols == 1) block(t, w, k) else columns(block(t, colW + t.insets[0] + t.insets[2], k), cols, colW + t.colGap, room)
        // shrink on overflow: a size a twentieth smaller each time, until the words fit (no smaller than a third)
        var k = 1f
        var text = laid(k)
        while (t.shrink && text.height > room && k > 0.35f) { k -= 0.05f; text = laid(k) }
        TextBoxDrawable.framed(c, text, x, y, w, h, t.insets[0], t.insets[1], t.insets[2], t.insets[3], t.anchor, false)
    }
}
