package com.kalotrapezis.drive

import java.io.File

/**
 * Home › Reuse slides (PowerPoint's, with its Use destination theme): another deck's slide (a .pptx or an .odp) put in this
 * one as a new slide on this deck's own layout — its words as text boxes keeping their letters' looks and their paragraphs'
 * alignment, its shapes with their fill and words, its pictures, tables and charts, its background colour and its notes,
 * moved and sized for this deck's slide size. The other deck's master and layout decorations stay behind: this deck's
 * theme is used, as PowerPoint does by default.
 * shortcut: custom outlines, gradients, effects, rotation, a group's insides, animations and films are not brought (counted
 * as left out); upgrade to copying the slide's own part with its relationships (Keep source formatting) when asked.
 */
internal object SlidesReuse {
    /** What one slide brings: the things put on it (by their ids), their looks and words, its background and notes, how many left out. */
    class Brought(val added: List<SlidesEdit.Added>, val looks: Map<String, SlidesEdit.Look>, val texts: Map<String, List<String>>,
                  val back: SlidesEdit.Back?, val notes: String, val skipped: Int)

    /** Slide [s] of deck [from] as things for a slide of a [width] × [height] deck, ids from [firstId]; its pictures copied into [pictures].
     *  [whole] (Save as the other format): the master's and layout's decorations and a background picture come too, as the slide's own. */
    fun bring(from: Deck, s: Slide, width: Float, height: Float, firstId: Int, pictures: File, whole: Boolean = false): Brought {
        val kx = width / from.width; val ky = height / from.height; val kt = minOf(kx, ky)
        var n = firstId; fun id() = (n++).toString()
        val added = ArrayList<SlidesEdit.Added>(); val looks = HashMap<String, SlidesEdit.Look>(); val texts = HashMap<String, List<String>>()
        var skipped = 0
        fun box(i: SlideItem) = floatArrayOf(i.x * kx, i.y * ky, i.w * kx, i.h * ky)
        // a run here: its size for this deck's slide, a link only to a page out there (the other deck's slides are not here)
        fun run(r: DocRun, scale: Float = 1f) = r.copy(sizePt = (r.sizePt ?: 18f) * kt * scale, link = r.link?.takeIf { it.startsWith("http") || it.startsWith("mailto:") })
        fun picture(bytes: ByteArray?): Pair<File, String>? {
            val ext = bytes?.let { when { it.size > 3 && it[0] == 0x89.toByte() && it[1] == 'P'.code.toByte() -> "png"; it.size > 2 && it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte() -> "jpeg"; else -> null } } ?: return null
            return File(pictures.apply { mkdirs() }, "${System.nanoTime()}-${n}.$ext").apply { writeBytes(bytes) } to ext
        }
        if (whole) s.backPicture?.let { p -> picture(runCatching { p() }.getOrNull())?.let { (f, ext) -> added += SlidesEdit.Added(id(), 0f, 0f, width, height, image = f.path, ext = ext) } }
        for (item in s.items) when (item) {
            is SlideItem.Shape -> {
                // the other deck's master and layout decorations, and empty placeholders' hints, stay behind
                if (item.id == null && !whole || item.hint) continue
                val b = box(item); val t = item.text; val g = item.shape
                val first = t?.paras?.flatMap { it.runs }?.firstOrNull { it.text.isNotBlank() }
                if (g?.fill != null) {
                    // a shape with its fill (its outline Slides' own, a shade darker) and its words in its middle
                    val k = id()
                    added += SlidesEdit.Added(k, b[0], b[1], b[2], b[3], prst = g.geometry.takeIf { it in ShapeGeometry.presets } ?: "rect", fill = g.fill or 0xFF000000.toInt())
                    if (t != null) { texts[k] = SlidesEdit.lines(item); looks[k] = SlidesEdit.Look(text = first?.color?.or(0xFF000000.toInt()), size = first?.sizePt?.times(kt), anchor = t.anchor,
                        align = t.paras.firstOrNull { p -> p.runs.any { it.text.isNotBlank() } }?.para?.align) }
                } else if (t != null && t.paras.any { p -> p.runs.any { it.text.isNotBlank() } }) {
                    // words alone: a text box, each paragraph's runs with their looks, the first paragraph's alignment for all
                    val k = id()
                    // its words as they show (a box shrunk to fit: its letters smaller too); a list's paragraphs keep their bullet and level as indent
                    // shortcut: a bullet and a level as letters (a mark and spaces), not PowerPoint's list; upgrade with Added's own paragraph looks when asked
                    added += SlidesEdit.Added(k, b[0], b[1], b[2], b[3], prst = SlidesEdit.TEXT_BOX, runs = t.paras.map { p ->
                        val rs = p.runs.filter { it.text.isNotEmpty() }.map { run(it, t.scale) }
                        val mark = p.bullet?.text?.trim()?.takeIf { rs.isNotEmpty() }?.let { m -> if (m.all { c -> c.isLetterOrDigit() || c in ".)(" }) m else "•" }
                        if (mark == null) rs else listOf(rs.first().copy(text = "\u2003".repeat(p.level) + "$mark ", link = null, bold = false, italic = false, underline = false)) + rs })
                    val align = t.paras.firstOrNull { p -> p.runs.any { it.text.isNotBlank() } }?.para?.align
                    // its box's size kept (shrinking its words to fit when it did), its words where they were in it
                    looks[k] = SlidesEdit.Look(align = align?.takeIf { it != DocAlign.Start }, anchor = t.anchor.takeIf { it != 't' }, autofit = if (t.shrink) 's' else 'n')
                } else skipped++
            }
            is SlideItem.Picture -> {
                if (item.media != null) { skipped++; continue }
                val (f, ext) = picture(runCatching { item.bytes() }.getOrNull()) ?: run { skipped++; null } ?: continue
                val b = box(item)
                added += SlidesEdit.Added(id(), b[0], b[1], b[2], b[3], image = f.path, ext = ext, math = item.math)
            }
            is SlideItem.Chart -> { val b = box(item); added += SlidesEdit.Added(id(), b[0], b[1], b[2], b[3], prst = "chart", chart = item.chart) }
            is SlideItem.Table -> {
                val b = box(item)
                val cells = item.cells.flatMap { row -> List(item.cols.size) { c -> row.getOrNull(c)?.second?.paras?.joinToString(" ") { p -> p.runs.joinToString("") { it.text } }.orEmpty() } }
                added += SlidesEdit.Added(id(), b[0], b[1], b[2], b[3], prst = "table", rows = item.cells.size, cols = item.cols.size, cells = cells)
            }
            else -> skipped++
        }
        val back = s.background.takeIf { (it and 0xFFFFFF) != 0xFFFFFF }?.let { SlidesEdit.Back(color = it or 0xFF000000.toInt()) }
        return Brought(added, looks, texts, back, s.notes, skipped)
    }

    /** This deck's layout a reused slide goes on: its Blank one (as PowerPoint's), else its last. */
    fun blank(deck: Deck) = deck.layouts.indexOfFirst { it.name.contains("blank", true) || it.name.contains("κενή", true) || it.name.contains("κενό", true) }
        .takeIf { it >= 0 } ?: deck.layouts.lastIndex

    /** [e] with slides [chosen] of [from] put in after slide [at] ([pictures]: where their pictures are kept); and how many things were left out. */
    fun reused(e: SlidesEdit.Edit, to: Deck, from: Deck, chosen: List<Int>, at: Int, pictures: File, whole: Boolean = false): Pair<SlidesEdit.Edit, Int> {
        var ed = e; var skipped = 0
        chosen.forEachIndexed { k, i ->
            val uid = ed.next()
            val got = bring(from, from.slides[i], to.width, to.height, ed.newId(uid).toInt(), pictures, whole)
            skipped += got.skipped
            ed = ed.copy(order = ed.order.toMutableList().apply { add((at + 1 + k).coerceIn(0, size), SlidesEdit.Entry(-1, uid, blank(to))) },
                added = ed.added + (uid to got.added), looks = ed.looks + got.looks.mapKeys { (id, _) -> uid to id }, texts = ed.texts + got.texts.mapKeys { (id, _) -> uid to id },
                backgrounds = got.back?.let { ed.backgrounds + (uid to it) } ?: ed.backgrounds, notes = if (got.notes.isNotBlank()) ed.notes + (uid to got.notes) else ed.notes,
                transitions = if (whole) from.slides[i].transition?.let { ed.transitions + (uid to it) } ?: ed.transitions else ed.transitions,
                hidden = if (whole && from.slides[i].hidden) ed.hidden + (uid to true) else ed.hidden)
        }
        return ed to skipped
    }

    /**
     * File › Save as the other format (.pptx ⇄ .odp): [source] written as [target], built on [blank] (Tetra's empty deck of
     * [target]'s kind) sized as the source — every slide as Reuse slides brings one, with its decorations, background picture,
     * transition, hidden mark and notes; the source's pictures kept in [pictures] while it is written. How many things were left out.
     * shortcut: placeholders become text boxes on the blank layout and the destination's theme is used (fonts and colours a run
     * does not set itself); animations, films, a group's insides and custom outlines stay behind — upgrade to a part-by-part
     * translation of masters and layouts if a converted deck must be edited further as the original was.
     */
    fun convert(source: File, target: File, blank: ByteArray, pictures: File): Int {
        val from = Slides.read(source)
        val empty = File(pictures.apply { mkdirs() }, "blank-${System.nanoTime()}.${target.extension}").apply { writeBytes(blank) }
        val sized = File(pictures, "sized-${System.nanoTime()}.${target.extension}")
        try {
            SlidesSize.resize(empty, SlidesSize.Size(from.width, from.height, false), sized)
            val to = Slides.read(sized)
            val (e, skipped) = reused(SlidesEdit.start(to).copy(order = emptyList()), to, from, from.slides.indices.toList(), -1, pictures, whole = true)
            if (target.extension.equals("odp", true)) SlidesOdpEdit.save(sized, to, e, target) else SlidesEdit.save(sized, to, e, target)
            return skipped
        } finally { empty.delete(); sized.delete() }
    }
}
