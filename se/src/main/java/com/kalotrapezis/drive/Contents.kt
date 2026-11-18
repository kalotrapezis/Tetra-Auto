package com.kalotrapezis.drive

import kotlin.math.roundToInt

/**
 * References › Table of contents (asked 2026-09-29): the document's headings (levels 1–3), each with its page, in the
 * file's own contents styles — Word's TOC 1–3 and TOC Heading, LibreOffice's Contents 1–3 and Contents Heading — a dot
 * leader to the number at the text's right edge. Plain paragraphs, not a field: Word and LibreOffice show it exactly as
 * written, and Update finds it again by its styles. Each entry links to its heading's bookmark (_Toc…, as Word names
 * them): a click there goes to it (16 Oct: clickable entries).
 */
internal object Contents {
    fun headingStyle(kind: OfficeKind) = if (kind == OfficeKind.Docx) "TOCHeading" else "Contents_20_Heading"
    fun levelStyle(kind: OfficeKind, level: Int) = if (kind == OfficeKind.Docx) "TOC$level" else "Contents_20_$level"
    private fun isContents(p: DocPara, kind: OfficeKind) = p.keep?.style.let { s -> s == headingStyle(kind) || (1..9).any { s == levelStyle(kind, it) } }

    /** How many heading levels the document's contents list (the deepest of their TOC 1… styles), 3 when it has none. */
    fun levelsOf(doc: OfficeDoc, kind: OfficeKind): Int = find(doc, kind)?.let { r -> doc.paras.slice(r).maxOfOrNull { p -> (1..9).firstOrNull { p.keep?.style == levelStyle(kind, it) } ?: 0 } }?.takeIf { it > 0 } ?: 3

    /** Where the contents already are (their first and last paragraph), or null. */
    fun find(doc: OfficeDoc, kind: OfficeKind): IntRange? {
        val first = doc.paras.indexOfFirst { isContents(it, kind) }.takeIf { it >= 0 } ?: return null
        var last = first
        while (last + 1 < doc.paras.size && isContents(doc.paras[last + 1], kind)) last++
        return first..last
    }

    /** The contents' paragraphs for [doc], with [pages] (the page each paragraph starts on, or null: not known yet). */
    fun paragraphs(doc: OfficeDoc, kind: OfficeKind, textWidth: Float, pages: IntArray?, skip: IntRange? = null, levels: Int = 3): List<DocPara> {
        val tab = if (kind == OfficeKind.Docx) "tabs" to """<w:tabs><w:tab w:val="right" w:leader="dot" w:pos="${(textWidth * 20).roundToInt() - 10}"/></w:tabs>"""
            else "child" to """<style:tab-stops><style:tab-stop style:position="${OdtWriterFmt.pt(textWidth - 0.5f)}" style:type="right" style:leader-style="dotted" style:leader-text="."/></style:tab-stops>"""
        val title = DocPara(listOf(DocRun("Contents")), keep = DocKeep(style = headingStyle(kind)))
        val entries = doc.paras.withIndex().filter { (i, p) -> p.heading in 1..levels && p.text.isNotBlank() && (skip == null || i !in skip) }.map { (i, p) ->
            DocPara(listOf(DocRun(p.text.replace(LINE_BREAK, ' ').replace(OBJECT_MARK.toString(), "").trim() + "\t" + (pages?.getOrNull(i)?.takeIf { it > 0 }?.toString() ?: ""), link = mark(p)?.let { "#$it" })),
                keep = DocKeep(style = levelStyle(kind, p.heading), parts = listOf(tab)))
        }
        return listOf(title) + entries.ifEmpty { listOf(DocPara(listOf(DocRun("No headings yet — give titles a Heading style (Home › H1–H3)")), keep = DocKeep(style = levelStyle(kind, 1)))) }
    }

    /** A heading's bookmark for the contents to link to: its own _Toc one, if it has one. */
    fun mark(p: DocPara): String? = p.keep?.parts?.firstOrNull { it.first == BOOKMARKS }?.second?.split(' ')?.firstOrNull { it.startsWith("_Toc") }
    /** [doc] with a _Toc bookmark on each heading the contents list (a new name for those without one). */
    fun marked(doc: OfficeDoc, skip: IntRange?, levels: Int = 3): OfficeDoc {
        val used = doc.paras.flatMap { p -> p.keep?.parts?.firstOrNull { it.first == BOOKMARKS }?.second?.split(' ').orEmpty() }.toMutableSet()
        var n = 100000000
        return doc.copy(paras = doc.paras.mapIndexed { i, p ->
            if (p.heading !in 1..levels || p.text.isBlank() || (skip != null && i in skip) || mark(p) != null) p else {
                val name = generateSequence { "_Toc${n++}" }.first { it !in used }.also { used += it }
                val parts = p.keep?.parts.orEmpty().let { ps -> val k = ps.indexOfFirst { it.first == BOOKMARKS }
                    if (k < 0) ps + (BOOKMARKS to name) else ps.toMutableList().also { it[k] = BOOKMARKS to "${it[k].second} $name" } }
                p.copy(keep = (p.keep ?: DocKeep()).copy(parts = parts))
            }
        })
    }

    /** [doc] with its contents put at paragraph [at] (or, if it has them, replacing them), numbered from its pages. */
    /** [levels]: how many heading levels it lists (Word's 1–9); by default as many as the contents already there do. */
    fun insert(original: OfficeDoc, kind: OfficeKind, styles: DocStyles, at: Int, levels: Int = levelsOf(original, kind), layout: (OfficeDoc, IntArray) -> Unit): OfficeDoc {
        val old = find(original, kind)
        val doc = marked(original, old, levels)
        fun with(entries: List<DocPara>): OfficeDoc {
            val paras = doc.paras.toMutableList()
            if (old != null) { repeat(old.last - old.first + 1) { paras.removeAt(old.first) }; paras.addAll(old.first, entries) }
            else {
                val here = at.coerceIn(0, paras.size)
                // An empty paragraph at the cursor becomes the contents.
                if (here < paras.size && paras[here].text.isEmpty() && paras[here].table == null) paras.removeAt(here)
                paras.addAll(here, entries)
            }
            return doc.copy(paras = paras)
        }
        // Laid out once with the contents in place (numbers not yet known), then numbered from where the headings fell.
        val draft = with(paragraphs(doc, kind, styles.page.textWidth, null, old, levels))
        val pages = IntArray(draft.paras.size)
        layout(draft, pages)
        // The draft's paragraph for each of the document's: the pages it gave, back to the document's own indices.
        val shift = draft.paras.size - doc.paras.size
        val start = old?.first ?: at.coerceIn(0, doc.paras.size)
        val mine = IntArray(doc.paras.size) { i -> if (old != null && i in old) 0 else pages.getOrElse(if (i >= start) i + shift else i) { 0 } }
        return with(paragraphs(doc, kind, styles.page.textWidth, mine, old, levels))
    }
}

/** ODT lengths as the writer writes them. */
internal object OdtWriterFmt { fun pt(v: Float) = "${(v * 100).roundToInt() / 100f}pt" }
