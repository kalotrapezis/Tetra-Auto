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
 * Proper pages (asked 2026-09-27): the document laid out on its own pages — the file's page size and margins, each
 * paragraph's spacing, line spacing and indents as its style and its own settings say — for the page view and for
 * Export › PDF alike. Laid out by Android's text layout, in points (1 unit = 1 pt).
 */

/** A page: its size and margins in points. */
internal data class DocPage(val width: Float = 595.3f, val height: Float = 841.9f, val top: Float = 56.7f, val bottom: Float = 56.7f,
    val left: Float = 56.7f, val right: Float = 56.7f, val header: Float = 35.4f, val footer: Float = 35.4f /* from the page's edge */,
    val columns: Int = 1, val columnGap: Float = 36f,
    val color: Int? = null /* Design › Page colour (RGB); null: white */, val border: PageBorder? = null /* Design › Page borders */) {
    val textWidth get() = (width - left - right).coerceAtLeast(8f)
    /** Column [k] of the text, as a page of its own width (the text flows down one, then the next). */
    fun column(k: Int): DocPage {
        if (columns <= 1) return this
        val w = ((textWidth - columnGap * (columns - 1)) / columns).coerceAtLeast(8f)
        val l = left + k * (w + columnGap)
        return copy(left = l, right = width - l - w)
    }
}

/** A box round the page: its line, and how far in from each edge of the page it runs (pt). */
internal data class PageBorder(val line: Border, val left: Float, val top: Float, val right: Float, val bottom: Float,
    val sides: String = "tlbr" /* which sides have it: t, l, b, r (Word's and LibreOffice's borders on some sides only) */)

/**
 * A section of the document: its pages' size and margins, headers and footers ("default", "first" for a title page,
 * "even"), where its page numbers start (null: they go on) and their style, and whether it starts a new page.
 */
internal data class DocSection(val page: DocPage, val headers: Map<String, List<DocPara>> = emptyMap(), val footers: Map<String, List<DocPara>> = emptyMap(),
    val titlePage: Boolean = false, val pageStart: Int? = null, val pageFormat: String = "decimal", val continuous: Boolean = false,
    val watermark: String? = null /* Design › Watermark: its text */,
    val watermarkPicture: ByteArray? = null /* Design › Watermark › Picture: its bytes (drawn washed out) */,
    val lineNumbers: LineNumbers? = null /* Layout › Line numbers */)

/**
 * Layout › Line numbers (Word's): each line of the text numbered in the margin, [countBy] shown, starting again on each
 * page ("newPage"), each section ("newSection") or never ("continuous"); [distance] from the text (null: Word's ¼ inch).
 */
internal data class LineNumbers(val restart: String = "newPage", val countBy: Int = 1, val distance: Float? = null)

/**
 * A table as the pages need it: column widths, indent and alignment; each row's height ("at least", or exact) and
 * whether it is a heading row (repeated on each page); each cell's column span, vertical merge ('n' none, 'r' starts,
 * 'c' goes on), shading, vertical alignment ('t', 'c', 'b'), margins (left, top, right, bottom) and borders (top, left,
 * bottom, right; null: none).
 */
internal class TableSpec(val cols: List<Float>, val indent: Float, val align: DocAlign, val rows: List<RowSpec>)
internal class RowSpec(val height: Float?, val exact: Boolean, val header: Boolean, val cells: List<CellSpec>)
internal class CellSpec(val span: Int, val merge: Char, val fill: Int?, val vAlign: Char, val margin: FloatArray, val borders: Array<Border?>,
    val turn: Int = 0 /* its text read upwards (-90, Word's btLr) or downwards (90, tbRl) */,
    val text: ParaLook? = null /* the table style's look for its text here (header bold…), under the paragraphs' own */)
/** [style]: Word's word for the line — single, double, dotted or dashed (LibreOffice's solid is single). */
internal data class Border(val width: Float, val color: Int, val style: String = "single")

/** A Word border (w:top, w:bottom… in eighths of a point); none when it says so. */
internal fun wordBorder(e: Element): Border? {
    val v = e.attr(W, "val")
    if (v == null || v == "nil" || v == "none") return null
    val sz = (e.attr(W, "sz")?.toFloatOrNull() ?: 4f) / 8f
    val style = when { v == "double" -> "double"; v == "dotted" -> "dotted"; v.startsWith("dash") || v.startsWith("dot") -> "dashed"; else -> "single" }
    return Border(if (v == "double") sz * 3 else sz.coerceAtLeast(0.25f), e.attr(W, "color")?.takeIf { it != "auto" }?.let { OfficeFormats.parseHex(it) } ?: 0, style)
}

/** An OpenDocument border ("0.5pt solid #000000"); none when it says so. */
internal fun odfBorder(v: String?): Border? {
    if (v == null || v.startsWith("none") || v.startsWith("hidden")) return null
    val parts = v.trim().split(Regex("\\s+"))
    val w = parts.firstNotNullOfOrNull { odfLength(it) } ?: 0.5f
    val style = when { "double" in parts -> "double"; "dotted" in parts -> "dotted"; parts.any { it.startsWith("dash") || it == "dot-dash" } -> "dashed"; else -> "single" }
    return Border(if ("double" in parts) w else w.coerceAtLeast(0.25f), parts.firstOrNull { it.startsWith("#") }?.let { OfficeFormats.parseHex(it) } ?: 0, style)
}

/**
 * A paragraph's borders (Word's pBdr, ODT's fo:border): its four sides, the line between two paragraphs with the
 * same borders (one box, as Word draws them), and how far each side stands from the text (pt).
 */
internal data class ParaBox(val top: Border? = null, val left: Border? = null, val bottom: Border? = null, val right: Border? = null,
    val between: Border? = null, val padTop: Float = 1f, val padLeft: Float = 4f, val padBottom: Float = 1f, val padRight: Float = 4f) {
    val isEmpty get() = top == null && left == null && bottom == null && right == null && between == null
}
internal data class TableCellArea(val row: Int, val cell: Int, val x: Float, val y: Float, val width: Float, val height: Float)

/** The document's styles, as far as laying it out needs them: its sections, and each paragraph's look. */
internal abstract class DocStyles {
    /** The document's last section (a file without section breaks has only this one). */
    open val section = DocSection(DocPage())
    val page get() = section.page
    /** Layout › Hyphenation: automatic (Word's autoHyphenation; LibreOffice's fo:hyphenate on the default paragraph style). */
    open val hyphenate: Boolean get() = false
    abstract fun look(p: DocPara): ParaLook
    /** Each paragraph's section: a paragraph that carries a section break ends one. */
    open fun sections(paras: List<DocPara>): List<DocSection> = List(paras.size) { section }
    /** Even pages have headers and footers of their own. */
    open val evenAndOdd = false
    /** A table's geometry; by default columns of equal width, thin black borders. */
    open fun tableSpec(t: DocTable): TableSpec {
        val thin = Border(0.5f, 0)
        return TableSpec(emptyList(), 0f, DocAlign.Start, t.rows.map { r -> RowSpec(null, false, false, r.cells.map { CellSpec(1, 'n', null, 't', floatArrayOf(5.4f, 0f, 5.4f, 0f), arrayOf(thin, thin, thin, thin)) }) })
    }
    /** Where a list item's text starts and its number or bullet hangs (pt), by the file's own list when it has one. */
    open fun listIndent(p: DocPara): Pair<Float, Float> = (36f * (p.list!!.level + 1)) to 18f
    /** A run's font name from its raw settings (Word's rFonts, ODF's font attributes). */
    open fun fontName(raw: String?): String? = raw?.let { Regex("""(?:w:ascii|w:hAnsi|style:font-name|fo:font-family)="([^"]+)"""").find(it)?.groupValues?.get(1) }
        ?.trim('\'', ' ')?.replace(Regex("""\d+$"""), "")
    /** A list item's number or bullet where paragraphs are stacked (a header, a cell, a text box, a slide's box). */
    open fun marker(p: DocPara): String? = p.list?.let { if (it.ordered) "${it.start ?: 1}." else "•" }
    /** Its own look (a slide's bullet); null: its paragraph's first letters'. */
    open fun markerLook(p: DocPara): MarkerLook? = null
    /** Its gap to the words a fixed one (the hang less the marker), not the next tab stop: a centred slide list's, as PowerPoint's. */
    open fun markerHangs(p: DocPara): Boolean = false
    /** A list item's number or bullet as its list writes it ("2.1.", "a)", "iv."), from the counts at each level. */
    open fun listLabel(p: DocPara, counts: IntArray): String? = null
    /** Each level's number format and text ("lowerLetter", "%2.") of the file's numbered lists, for the editor; null: Writer's 1. 2. 3. */
    open fun orderedLevels(paras: List<DocPara>): List<Pair<String, String>?> = emptyList()

    /**
     * The paragraph styles to offer: Normal, Title, Subtitle, the six headings, Quote, then the document's own
     * (its hidden ones left out).
     */
    open fun paragraphStyles(): List<StyleEntry> = standardStyles(emptyList())
    protected fun standardStyles(own: List<StyleEntry>, title: String = "Title", subtitle: String = "Subtitle", quote: String = "Quote"): List<StyleEntry> {
        val first = listOf(StyleEntry("Normal", null), StyleEntry("Title", title), StyleEntry("Subtitle", subtitle)) +
            (1..6).map { StyleEntry("Heading $it", null, it) } + StyleEntry("Quote", quote)
        return first + own.filter { o -> first.none { it.id == o.id } }.sortedBy { it.name.lowercase() }
    }
    /** How the document's styles name [e] (its id in Word, its name in LibreOffice); null: it cannot be changed. */
    open fun styleKey(e: StyleEntry): String? = null
    /** Word's plain style's id (what new styles are based on). */
    open val normalId: String? get() = null
    /** Which of [paragraphStyles] a paragraph has. */
    fun styleOf(p: DocPara): StyleEntry = paragraphStyles().firstOrNull { if (p.heading > 0) it.heading == p.heading else it.heading == 0 && it.id == p.keep?.style }
        ?: StyleEntry(p.keep?.style ?: "Normal", p.keep?.style)

    /** A number in a list's style: "1", "a", "A", "i", "I", "01"… */
    fun number(n: Int, format: String): String = when (format) {
        "lowerLetter", "a" -> letters(n).lowercase()
        "upperLetter", "A" -> letters(n)
        "lowerRoman", "i" -> roman(n).lowercase()
        "upperRoman", "I" -> roman(n)
        "decimalZero" -> if (n < 10) "0$n" else "$n"
        "none", "" -> ""
        else -> "$n"
    }
    private fun letters(n: Int): String = if (n <= 0) "" else ('A' + (n - 1) % 26).toString().repeat((n - 1) / 26 + 1)
    private fun roman(n: Int): String {
        var k = n.coerceIn(1, 3999); val sb = StringBuilder()
        for ((v, r) in listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC", 50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I"))
            while (k >= v) { sb.append(r); k -= v }
        return sb.toString()
    }
    /** Word's bullets in the Symbol and Wingdings fonts are private-use characters: the bullets they draw. */
    fun bullet(text: String): String = text.map { c -> when (c) { '\uF0B7', '\uF06C' -> '•'; '\uF0A7', '\uF0FC' -> '▪'; '\uF06F' -> '◦'; '\uF0D8', '\uF0E0' -> '➢'; else -> if (c in '\uF000'..'\uF0FF') '•' else c } }.joinToString("")
    /** A numbered item's label from Word's level texts ("%1.%2." → "2.1."), each level counted as [counts] says. */
    fun label(levels: List<Pair<String, String>?>, level: Int, counts: IntArray): String? {
        val (fmt, text) = levels.getOrNull(level) ?: return null
        if (fmt == "bullet") return bullet(text)
        return Regex("%([1-9])").replace(text) { m -> val l = m.groupValues[1].toInt() - 1; number(counts.getOrElse(l) { 1 }.coerceAtLeast(1), levels.getOrNull(l)?.first ?: "decimal") }
    }

    companion object {
        fun of(kind: OfficeKind, bytes: ByteArray?): DocStyles = runCatching {
            // A new document: the styles of the blank file Writer will write, so what is set on it shows before it is saved.
            val parts = OfficeFormats.unzip(bytes ?: OfficeFormats.write(OfficeDoc(emptyList()), kind))
            if (kind == OfficeKind.Docx) DocxStyles(parts) else OdtStyles(parts)
        }.getOrNull() ?: Plain

        /** A new document: Writer's own (A4, 2 cm, as the blank files it writes). */
        val Plain = object : DocStyles() {
            override fun look(p: DocPara) = if (p.heading > 0) ParaLook(before = 12f, after = 6f, bold = true) else ParaLook(after = 6f, lineMultiple = 1.08f)
        }
    }
}

/** Word: page from the last sectPr; looks from docDefaults, the style chain and the paragraph's own pPr. */
private class DocxStyles(parts: Map<String, ByteArray>) : DocStyles() {
    private val styles = HashMap<String, Element>() // paragraph styleId → style
    private val tableStyles = HashMap<String, Element>() // table styleId → style
    private val byName = HashMap<String, String>() // name, lowercased → styleId
    private val ownStyles = ArrayList<String>() // the paragraph styles people see, in the file's order
    private var defaultStyle: String? = null
    private var defaults: Element? = null // docDefaults
    private val numInd = HashMap<Pair<String, Int>, Element>() // (numId, level) → the level's ind
    private val numLevels = HashMap<String, Array<Pair<String, String>?>>() // numId → each level's (numFmt, lvlText)
    private var minorFont: String? = null; private var majorFont: String? = null // the theme's body and heading fonts
    override var evenAndOdd = false
    private var hyphens = false
    override val hyphenate get() = hyphens
    private var bodySect: Element? = null
    private var background: Int? = null
    private val targets: Map<String, String> // relationship id → part (headers, footers)
    private val parts = parts
    private fun theme(name: String?) = when { name == null -> null; name.startsWith("minor") -> minorFont; name.startsWith("major") -> majorFont; else -> null }
    override fun fontName(raw: String?): String? = super.fontName(raw)
        ?: raw?.let { Regex("""w:(?:ascii|hAnsi)Theme="([^"]+)"""").find(it)?.groupValues?.get(1) }?.let(::theme)
    override val section: DocSection

    init {
        val dir = documentPath(parts).substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        parts[dir + "styles.xml"]?.let { OfficeFormats.parse(it).documentElement }?.let { root ->
            defaults = root.kid(W, "docDefaults")
            for (s in root.kids().filter { it.localName == "style" && it.attr(W, "type") == "table" }) s.attr(W, "styleId")?.let { tableStyles[it] = s }
            for (s in root.kids().filter { it.localName == "style" && it.attr(W, "type") == "paragraph" }) {
                val id = s.attr(W, "styleId") ?: continue
                styles[id] = s
                byName[(s.kid(W, "name")?.attr(W, "val") ?: id).lowercase()] = id
                if (s.attr(W, "default").let { it == "1" || it == "true" }) defaultStyle = id
                if (s.kid(W, "semiHidden") == null) ownStyles += id
            }
        }
        // Title, Subtitle and Quote as Word makes them, for a document without: shown before it is saved with them.
        for ((id, xml) in DOCX_BUILTIN) if (id.lowercase() !in byName && id !in styles) {
            val d = defaultStyle?.let { xml.replace("NORMAL", it) } ?: xml.replace(Regex("""<w:(basedOn|next) w:val="NORMAL"/>"""), "")
            styles[id] = OfficeFormats.parse("<x xmlns:w=\"$W\">$d</x>".toByteArray()).documentElement.kids().first()
            byName[id.lowercase()] = id
        }
        parts[dir + "numbering.xml"]?.let { OfficeFormats.parse(it).documentElement }?.let { root ->
            val abstracts = root.kids().filter { it.localName == "abstractNum" }.associateBy { it.attr(W, "abstractNumId") }
            for (n in root.kids().filter { it.localName == "num" }) {
                val id = n.attr(W, "numId") ?: continue
                val abs = abstracts[n.kid(W, "abstractNumId")?.attr(W, "val")] ?: continue
                val levels = arrayOfNulls<Pair<String, String>>(9)
                for (lvl in abs.kids().filter { it.localName == "lvl" }) {
                    val l = lvl.attr(W, "ilvl")?.toIntOrNull() ?: continue
                    lvl.kid(W, "pPr")?.kid(W, "ind")?.let { numInd[id to l] = it }
                    if (l in 0..8) levels[l] = (lvl.kid(W, "numFmt")?.attr(W, "val") ?: "decimal") to (lvl.kid(W, "lvlText")?.attr(W, "val") ?: "")
                }
                numLevels[id] = levels
            }
        }
        parts[dir + "theme/theme1.xml"]?.let { String(it, Charsets.UTF_8) }?.let { t ->
            fun latin(tag: String) = Regex("""<a:$tag>[\s\S]*?<a:latin typeface="([^"]*)"""").find(t)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
            minorFont = latin("minorFont"); majorFont = latin("majorFont")
        }
        ShapePicture.theme = DocTheme.colorsOf(parts)
        val doc = OfficeFormats.parse(parts.getValue(documentPath(parts))).documentElement
        background = doc.kid(W, "background")?.attr(W, "color")?.takeIf { it != "auto" }?.let { OfficeFormats.parseHex(it) }
        bodySect = doc.kid(W, "body")?.kid(W, "sectPr")
        targets = parts[relsPath(documentPath(parts))]?.let { OfficeFormats.parse(it).documentElement.kids().associate { r -> r.getAttribute("Id") to resolve(dir, r.getAttribute("Target")) } }.orEmpty()
        evenAndOdd = parts[dir + "settings.xml"]?.let { "<w:evenAndOddHeaders" in String(it, Charsets.UTF_8) } ?: false
        hyphens = parts[dir + "settings.xml"]?.let { Regex("""<w:autoHyphenation\b([^>]*)/>""").find(String(it, Charsets.UTF_8)) }?.let { m -> Regex("""w:val="(0|false|off)"""").find(m.groupValues[1]) == null } ?: false
        section = section(bodySect, null)
    }

    /** A section from its sectPr; headers and footers it does not name are the section before's (Word's "link to previous"). */
    private fun section(sect: Element?, before: DocSection?): DocSection {
        fun twips(e: Element?, name: String) = e?.attr(W, name)?.toFloatOrNull()?.div(20f)
        val size = sect?.kid(W, "pgSz"); val mar = sect?.kid(W, "pgMar")
        val d = DocPage()
        val page = DocPage(twips(size, "w") ?: d.width, twips(size, "h") ?: d.height, twips(mar, "top") ?: d.top, twips(mar, "bottom") ?: d.bottom,
            twips(mar, "left") ?: d.left, twips(mar, "right") ?: d.right, twips(mar, "header") ?: d.header, twips(mar, "footer") ?: d.footer,
            sect?.kid(W, "cols")?.attr(W, "num")?.toIntOrNull()?.coerceIn(1, 6) ?: 1, twips(sect?.kid(W, "cols"), "space") ?: 36f).let { pg ->
            // Page borders: the top one's line for the box; from the page's edge, or (Word's default) from the text.
            val borders = sect?.kid(W, "pgBorders")
            val line = borders?.kids()?.firstNotNullOfOrNull { wordBorder(it) }
            // A side with no border: as far in as the sides that have one (a top line alone still stops 24 pt from the edges).
            val anySpace = listOf("top", "left", "bottom", "right").firstNotNullOfOrNull { borders?.kid(W, it)?.attr(W, "space")?.toFloatOrNull() } ?: 0f
            fun space(side: String) = borders?.kid(W, side)?.attr(W, "space")?.toFloatOrNull() ?: anySpace
            val sides = listOf("top" to 't', "left" to 'l', "bottom" to 'b', "right" to 'r').filter { (n, _) -> borders?.kid(W, n)?.let { wordBorder(it) } != null }.map { it.second }.joinToString("")
            val box = line?.let { l -> if (borders.attr(W, "offsetFrom") == "page") PageBorder(l, space("left"), space("top"), space("right"), space("bottom"), sides)
                else PageBorder(l, pg.left - space("left"), pg.top - space("top"), pg.right - space("right"), pg.bottom - space("bottom"), sides) }
            pg.copy(color = background, border = box)
        }
        val headers = HashMap(before?.headers.orEmpty()); val footers = HashMap(before?.footers.orEmpty())
        sect?.kids()?.forEach { e ->
            val into = when (e.localName) { "headerReference" -> headers; "footerReference" -> footers; else -> null } ?: return@forEach
            targets[e.attr(RELATIONSHIPS, "id")]?.let { into[e.attr(W, "type") ?: "default"] = OfficeFormats.docxPart(parts, it) }
        }
        val numbers = sect?.kid(W, "pgNumType")
        // Its watermark: in one of its own headers, or (having none) the section before's.
        val refs = sect?.kids()?.filter { it.localName == "headerReference" }.orEmpty()
        val watermark = if (refs.isEmpty()) before?.watermark
            else refs.firstNotNullOfOrNull { r -> targets[r.attr(RELATIONSHIPS, "id")]?.let { parts[it] }?.let { Watermark.textIn(String(it, Charsets.UTF_8)) } }
        val watermarkPicture = if (refs.isEmpty()) before?.watermarkPicture
            else refs.firstNotNullOfOrNull { r -> targets[r.attr(RELATIONSHIPS, "id")]?.let { Watermark.pictureIn(parts, it) } }
        return DocSection(page, headers, footers, watermark = watermark, watermarkPicture = watermarkPicture,
            titlePage = sect?.kid(W, "titlePg")?.let { it.attr(W, "val").let { v -> v == null || v == "1" || v == "true" } } ?: false,
            pageStart = numbers?.attr(W, "start")?.toIntOrNull(), pageFormat = numbers?.attr(W, "fmt") ?: "decimal",
            continuous = sect?.kid(W, "type")?.attr(W, "val") == "continuous",
            lineNumbers = sect?.kid(W, "lnNumType")?.let { l -> LineNumbers(l.attr(W, "restart") ?: "newPage", l.attr(W, "countBy")?.toIntOrNull()?.coerceAtLeast(1) ?: 1, twips(l, "distance")) })
    }

    override fun tableSpec(t: DocTable): TableSpec {
        fun el(xml: String) = runCatching { OfficeFormats.parse("<x xmlns:w=\"$W\">$xml</x>".toByteArray()).documentElement }.getOrNull()
        fun tw(e: Element?, n: String = "w") = e?.attr(W, n)?.toFloatOrNull()?.div(20f)
        fun border(e: Element) = wordBorder(e)
        fun edge(name: String) = when (name) { "start" -> "left"; "end" -> "right"; else -> name }
        val tblPr = el(t.props)?.kid(W, "tblPr")
        // The table style (and the styles it is based on), then the table's own settings.
        val chain = ArrayList<Element>()
        var st = tableStyles[tblPr?.kid(W, "tblStyle")?.attr(W, "val")]
        while (st != null && chain.size < 10) { chain.add(0, st); st = tableStyles[st.kid(W, "basedOn")?.attr(W, "val")] }
        val prs = chain.mapNotNull { it.kid(W, "tblPr") } + listOfNotNull(tblPr)
        val borders = HashMap<String, Border?>()
        val margin = floatArrayOf(5.4f, 0f, 5.4f, 0f)
        fun margins(pr: Element?, m: FloatArray) = pr?.kids()?.forEach { e ->
            val v = tw(e) ?: return@forEach
            when (edge(e.localName)) { "left" -> m[0] = v; "top" -> m[1] = v; "right" -> m[2] = v; "bottom" -> m[3] = v }
        }
        for (pr in prs) {
            pr.kid(W, "tblBorders")?.kids()?.forEach { borders[edge(it.localName)] = border(it) }
            margins(pr.kid(W, "tblCellMar"), margin)
        }
        val cols = el(t.grid)?.kid(W, "tblGrid")?.kids()?.filter { it.localName == "gridCol" }?.mapNotNull { tw(it) }.orEmpty()
        val align = when (tblPr?.kid(W, "jc")?.attr(W, "val")) { "center" -> DocAlign.Center; "right", "end" -> DocAlign.End; else -> DocAlign.Start }
        val gridCount = cols.size.coerceAtLeast(t.rows.maxOfOrNull { r -> r.cells.sumOf { c -> el(c.props)?.kid(W, "tcPr")?.kid(W, "gridSpan")?.attr(W, "val")?.toIntOrNull() ?: 1 } } ?: 1)
        // The style's parts for some cells only (Word's conditional formatting): which the table turns on (tblLook),
        // and how many rows or columns make a band.
        val look = tblPr?.kid(W, "tblLook")
        fun flag(name: String, bit: Int) = look?.attr(W, name)?.let { it == "1" || it == "true" } ?: ((look?.attr(W, "val")?.toIntOrNull(16) ?: 0x04A0) and bit != 0)
        val firstRow = flag("firstRow", 0x20); val lastRow = flag("lastRow", 0x40); val firstCol = flag("firstColumn", 0x80); val lastCol = flag("lastColumn", 0x100)
        val hBand = !flag("noHBand", 0x200); val vBand = !flag("noVBand", 0x400)
        fun band(name: String) = prs.firstNotNullOfOrNull { it.kid(W, name)?.attr(W, "val")?.toIntOrNull() }?.coerceAtLeast(1) ?: 1
        val rowBand = band("tblStyleRowBandSize"); val colBand = band("tblStyleColBandSize")
        fun part(type: String) = chain.flatMap { st -> st.kids().filter { it.localName == "tblStylePr" && it.attr(W, "type") == type } }
        val parts = listOf("wholeTable", "band1Vert", "band2Vert", "band1Horz", "band2Horz", "firstCol", "lastCol", "firstRow", "lastRow", "neCell", "nwCell", "seCell", "swCell")
            .associateWith { part(it) }.filterValues { it.isNotEmpty() }
        val headerRows = if (firstRow) 1 else 0
        fun partsFor(ri: Int, g: Int, span: Int): List<Element> {
            val top = firstRow && ri == 0; val bottom = lastRow && ri == t.rows.size - 1
            val left = firstCol && g == 0; val right = lastCol && g + span >= gridCount
            val types = ArrayList<String>().apply {
                add("wholeTable")
                if (vBand && !left && !right) add(if (((g - (if (firstCol) 1 else 0)) / colBand) % 2 == 0) "band1Vert" else "band2Vert")
                if (hBand && !top && !bottom) add(if (((ri - headerRows) / rowBand) % 2 == 0) "band1Horz" else "band2Horz")
                if (left) add("firstCol"); if (right) add("lastCol"); if (top) add("firstRow"); if (bottom) add("lastRow")
                if (top && right) add("neCell"); if (top && left) add("nwCell"); if (bottom && right) add("seCell"); if (bottom && left) add("swCell")
            }
            return types.flatMap { parts[it].orEmpty() }
        }
        val rows = t.rows.mapIndexed { ri, row ->
            val trPr = el(row.props)?.kid(W, "trPr")
            val h = trPr?.kid(W, "trHeight")
            var g = 0
            val cells = row.cells.map { c ->
                val tcPr = el(c.props)?.kid(W, "tcPr")
                val span = tcPr?.kid(W, "gridSpan")?.attr(W, "val")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val merge = tcPr?.kid(W, "vMerge")?.let { if (it.attr(W, "val") == "restart") 'r' else 'c' } ?: 'n'
                val cond = partsFor(ri, g, span)
                fun shdOf(pr: Element?) = pr?.kid(W, "shd")?.attr(W, "fill")?.takeIf { it != "auto" }?.let { OfficeFormats.parseHex(it) }
                // The cell's own shading wins, even "none" (fill auto); else the style's part for this cell, else its whole.
                val fill = if (tcPr?.kid(W, "shd") != null) shdOf(tcPr)
                    else (cond + chain).lastOrNull { it.kid(W, "tcPr")?.kid(W, "shd") != null }?.let { shdOf(it.kid(W, "tcPr")) }
                // Its text's look from those parts: bold, italic, colour, size (the paragraph's own style still wins).
                val text = cond.mapNotNull { it.kid(W, "rPr") }.fold(null as ParaLook?) { l, rPr ->
                    fun on(n: String) = rPr.kid(W, n)?.attr(W, "val").let { v -> if (rPr.kid(W, n) == null) null else v == null || v == "1" || v == "true" }
                    (l ?: ParaLook()).let { x -> x.copy(bold = on("b") ?: x.bold, italic = on("i") ?: x.italic,
                        color = rPr.kid(W, "color")?.attr(W, "val")?.takeIf { it != "auto" }?.let { OfficeFormats.parseHex(it) } ?: x.color,
                        sizePt = rPr.kid(W, "sz")?.attr(W, "val")?.toFloatOrNull()?.div(2) ?: x.sizePt) }
                }
                val m = margin.copyOf().also { margins(tcPr?.kid(W, "tcMar"), it) }
                // Each edge: the table's outer border on the outside, its inner one inside; the cell's own over both.
                val b = arrayOf(
                    borders[if (ri == 0) "top" else "insideH"], borders[if (g == 0) "left" else "insideV"],
                    borders[if (ri == t.rows.size - 1) "bottom" else "insideH"], borders[if (g + span >= gridCount) "right" else "insideV"])
                // The style's parts' lines for this cell, then the cell's own.
                (cond.mapNotNull { it.kid(W, "tcPr")?.kid(W, "tcBorders") } + listOfNotNull(tcPr?.kid(W, "tcBorders"))).forEach { bs -> bs.kids().forEach { e ->
                    when (edge(e.localName)) { "top" -> b[0] = border(e); "left" -> b[1] = border(e); "bottom" -> b[2] = border(e); "right" -> b[3] = border(e) }
                } }
                g += span
                CellSpec(span, merge, fill, when (tcPr?.kid(W, "vAlign")?.attr(W, "val")) { "center" -> 'c'; "bottom" -> 'b'; else -> 't' }, m, b,
                    when (tcPr?.kid(W, "textDirection")?.attr(W, "val")) { "btLr" -> -90; "tbRl", "tbRlV" -> 90; else -> 0 }, text)
            }
            RowSpec(tw(h, "val"), h?.attr(W, "hRule") == "exact", trPr?.kid(W, "tblHeader").let { it != null && it.attr(W, "val").let { v -> v == null || v == "1" || v == "true" } }, cells)
        }
        return TableSpec(cols, tw(tblPr?.kid(W, "tblInd")) ?: 0f, align, rows)
    }

    override fun sections(paras: List<DocPara>): List<DocSection> {
        val out = arrayOfNulls<DocSection>(paras.size)
        var start = 0; var before: DocSection? = null
        for ((i, p) in paras.withIndex()) {
            val xml = p.keep?.parts?.firstOrNull { it.first == "sectPr" }?.second ?: continue
            val sect = runCatching { OfficeFormats.parse("<x xmlns:w=\"$W\" xmlns:r=\"$RELATIONSHIPS\">$xml</x>".toByteArray()).documentElement.kids().firstOrNull() }.getOrNull()
            val s = section(sect, before)
            for (k in start..i) out[k] = s
            before = s; start = i + 1
        }
        val last = if (before == null) section else section(bodySect, before)
        return out.map { it ?: last }
    }

    private fun chain(id: String?): List<Element> {
        val out = ArrayList<Element>()
        var s = styles[id ?: defaultStyle] ?: return out
        while (out.size < 20) { out.add(0, s); s = styles[s.kid(W, "basedOn")?.attr(W, "val")] ?: break }
        return out
    }

    private fun apply(l: ParaLook, pPr: Element?, rPr: Element?): ParaLook {
        var look = l
        pPr?.kids()?.forEach { e -> look = pPrOne(look, e) }
        rPr?.kids()?.forEach { e ->
            val on = e.attr(W, "val").let { it == null || it == "1" || it == "true" || it == "on" }
            when (e.localName) {
                "sz" -> e.attr(W, "val")?.toFloatOrNull()?.let { look = look.copy(sizePt = it / 2) }
                "b" -> look = look.copy(bold = on)
                "i" -> look = look.copy(italic = on)
                "color" -> OfficeFormats.parseHex(e.attr(W, "val"))?.let { look = look.copy(color = it) }
                "rFonts" -> (e.attr(W, "ascii") ?: e.attr(W, "hAnsi") ?: theme(e.attr(W, "asciiTheme") ?: e.attr(W, "hAnsiTheme")))?.let { look = look.copy(font = it) }
            }
        }
        return look
    }

    private fun pPrOne(l: ParaLook, e: Element): ParaLook {
        fun tw(name: String) = e.attr(W, name)?.toFloatOrNull()?.div(20f)
        return when (e.localName) {
            "spacing" -> {
                var n = l.copy(before = tw("before") ?: l.before, after = tw("after") ?: l.after)
                e.attr(W, "line")?.toFloatOrNull()?.let { line ->
                    n = when (e.attr(W, "lineRule")) {
                        "exact" -> n.copy(lineExact = line / 20f, lineAtLeast = false)
                        "atLeast" -> n.copy(lineExact = line / 20f, lineAtLeast = true)
                        else -> n.copy(lineMultiple = line / 240f, lineExact = null)
                    }
                }
                n
            }
            "ind" -> l.copy(left = tw("left") ?: tw("start") ?: l.left, right = tw("right") ?: tw("end") ?: l.right,
                firstLine = tw("hanging")?.let { -it } ?: tw("firstLine") ?: l.firstLine)
            "tabs" -> {
                val t = l.tabs.toMutableList()
                for (tab in e.kids().filter { it.localName == "tab" }) {
                    val pos = tab.attr(W, "pos")?.toFloatOrNull()?.div(20f) ?: continue
                    t.removeAll { kotlin.math.abs(it.pos - pos) < 1f }
                    val v = tab.attr(W, "val")
                    if (v != "clear" && v != "bar") t += TabStop(pos, when (v) { "right", "end", "decimal" -> 'r'; "center" -> 'c'; else -> 'l' },
                        when (tab.attr(W, "leader")) { "dot" -> '.'; "middleDot" -> '·'; "hyphen" -> '-'; "underscore", "heavy" -> '_'; else -> null })
                }
                l.copy(tabs = t.sortedBy { it.pos })
            }
            "pageBreakBefore" -> l.copy(pageBreakBefore = e.attr(W, "val").let { it == null || it == "1" || it == "true" })
            "columnBreak" -> l.copy(columnBreakBefore = true) // Writer's own mark: a column break at the paragraph's start
            "pBdr" -> {
                var b = ParaBox()
                for (k in e.kids()) {
                    val space = k.attr(W, "space")?.toFloatOrNull()
                    when (k.localName) {
                        "top" -> b = b.copy(top = wordBorder(k), padTop = space ?: b.padTop)
                        "left", "start" -> b = b.copy(left = wordBorder(k), padLeft = space ?: b.padLeft)
                        "bottom" -> b = b.copy(bottom = wordBorder(k), padBottom = space ?: b.padBottom)
                        "right", "end" -> b = b.copy(right = wordBorder(k), padRight = space ?: b.padRight)
                        "between" -> b = b.copy(between = wordBorder(k))
                    }
                }
                l.copy(box = b.takeIf { !it.isEmpty })
            }
            "jc" -> l.copy(align = when (e.attr(W, "val")) { "center" -> DocAlign.Center; "right", "end" -> DocAlign.End; "both", "distribute" -> DocAlign.Justify; else -> DocAlign.Start })
            "contextualSpacing" -> l.copy(contextual = e.attr(W, "val").let { it == null || it == "1" || it == "true" })
            else -> l
        }
    }

    private var listIndented = false
    override fun paragraphStyles(): List<StyleEntry> {
        val headings = (1..6).mapNotNull { byName["heading $it"] }.toSet()
        val own = ownStyles.filter { it != defaultStyle && it !in headings }.map { id ->
            StyleEntry((styles[id]?.kid(W, "name")?.attr(W, "val") ?: id).replaceFirstChar { it.uppercase() }, id)
        }
        return standardStyles(own, byName["title"] ?: "Title", byName["subtitle"] ?: "Subtitle", byName["quote"] ?: "Quote")
    }
    override fun styleKey(e: StyleEntry) = if (e.heading > 0) byName["heading ${e.heading}"] ?: "Heading${e.heading}" else e.id ?: defaultStyle ?: "Normal"
    override val normalId get() = defaultStyle

    override fun look(p: DocPara): ParaLook {
        listIndented = false
        val d = defaults
        var look = apply(ParaLook(), d?.kid(W, "pPrDefault")?.kid(W, "pPr"), d?.kid(W, "rPrDefault")?.kid(W, "rPr"))
        val styleId = if (p.heading > 0) byName["heading ${p.heading}"] else p.keep?.style
        for (s in chain(styleId)) look = apply(look, s.kid(W, "pPr"), s.kid(W, "rPr"))
        // A list item: its level's indent comes after the style's and before the paragraph's own (Word's order).
        if (p.list != null) p.keep?.list?.substringBefore('|')?.let { numInd[it to p.list.level] }?.let { look = pPrOne(look, it); listIndented = true }
        p.keep?.parts?.forEach { (_, xml) -> runCatching { OfficeFormats.parse(("<x xmlns:w=\"$W\">$xml</x>").toByteArray()).documentElement.kids().forEach { look = pPrOne(look, it) } } }
        return look.copy(listIndented = listIndented)
    }

    // shortcut: the first numbered list at each level speaks for the whole document; per-list formats if a file mixes them.
    override fun orderedLevels(paras: List<DocPara>): List<Pair<String, String>?> = (0..8).map { l ->
        paras.firstNotNullOfOrNull { p -> p.list?.takeIf { it.ordered && it.level == l }?.let { p.keep?.list?.substringBefore('|')?.let { numLevels[it] }?.getOrNull(l) } }
            ?.takeIf { it.first != "bullet" }
    }

    override fun listLabel(p: DocPara, counts: IntArray): String? {
        val levels = p.keep?.list?.substringBefore('|')?.let { numLevels[it] } ?: return null
        return label(levels.asList(), p.list!!.level, counts)
    }

    override fun listIndent(p: DocPara): Pair<Float, Float> {
        val ind = p.keep?.list?.substringBefore('|')?.let { numInd[it to p.list!!.level] }
        val left = ind?.let { (it.attr(W, "left") ?: it.attr(W, "start"))?.toFloatOrNull()?.div(20f) }
        val hang = ind?.attr(W, "hanging")?.toFloatOrNull()?.div(20f)
        return if (left != null) left to (hang ?: 0f) else super.listIndent(p)
    }
}

/** ODF: page from the master page's layout; looks from the default style, the named style chain and the paragraph's own. */
private class OdtStyles(parts: Map<String, ByteArray>) : DocStyles() {
    override val evenAndOdd get() = "even" in section.headers || "even" in section.footers
    override val hyphenate get() = listOfNotNull(named["Standard"], defaults).firstNotNullOfOrNull { it.kid(STYLE, "text-properties")?.attr(FO, "hyphenate") } == "true"
    private val named = HashMap<String, Element>() // paragraph style name → style
    private val tableStyles = HashMap<String, Element>() // "table-cell/A1" … → style
    private var defaults: Element? = null
    private val listLevels = HashMap<Pair<String, Int>, Element>() // (list style, level) → list-level-label-alignment
    private val listStyles = HashMap<Pair<String, Int>, Element>() // (list style, level) → the level's own style
    override val section: DocSection
    private val sectionColumns = HashMap<String, Pair<Int, Float>>() // a section style's columns and the gap between them
    private val masterPages = HashMap<String, Element>() // a page style's page-layout-properties, by its name
    private var mainMaster: String? = null

    /**
     * LibreOffice's sections and page styles: a paragraph whose style names another page style starts a page of that
     * style's size, margins and columns; inside a text:section, its columns from where it begins (on down the page).
     * shortcut: another page style's headers, footers and borders are the main one's; read them when a file needs it.
     */
    override fun sections(paras: List<DocPara>): List<DocSection> {
        if (paras.none { p -> p.keep?.parts?.any { it.first == OdtSections.PART || it.first == "master" } == true }) return super.sections(paras)
        val made = HashMap<Triple<String?, Int, Float>, DocSection>() // one each, so the layout sees where one changes
        var base = section; var masterName: String? = null
        return paras.map { p ->
            p.keep?.parts?.firstOrNull { it.first == "master" }?.second?.takeIf { it.isNotEmpty() && it != masterName }?.let { m ->
                masterName = m
                base = masterPages[m]?.let { pr -> fun len(n: String) = odfLength(pr.attr(FO, n))
                    val top = len("margin-top") ?: section.page.top; val bottom = len("margin-bottom") ?: section.page.bottom
                    section.copy(page = section.page.copy(width = len("page-width") ?: section.page.width, height = len("page-height") ?: section.page.height,
                        top = top, bottom = bottom, left = len("margin-left") ?: section.page.left, right = len("margin-right") ?: section.page.right, header = top, footer = bottom,
                        columns = pr.kid(STYLE, "columns")?.attr(FO, "column-count")?.toIntOrNull()?.coerceIn(1, 6) ?: 1,
                        columnGap = odfLength(pr.kid(STYLE, "columns")?.attr(FO, "column-gap")) ?: 36f)) }?.takeIf { m != mainMaster } ?: section
            }
            val cols = OdtSections.tags(p).lastOrNull()?.let(OdtSections::styleName)?.let { n -> sectionColumns[n] ?: n.removePrefix(OdtSections.TETRA).toIntOrNull()?.let { it to 36f } }
                ?: (base.page.columns to base.page.columnGap)
            made.getOrPut(Triple(masterName, cols.first, cols.second)) {
                // The first of a page style starts a page; a section on down the same one.
                base.copy(page = base.page.copy(columns = cols.first, columnGap = cols.second), continuous = made.keys.any { it.first == masterName })
            }
        }
    }

    init {
        val styles = parts["styles.xml"]?.let { OfficeFormats.parse(it).documentElement }
        val content = parts["content.xml"]?.let { OfficeFormats.parse(it).documentElement }
        var layouts = HashMap<String, Element>()
        var master: Element? = null
        for (root in listOfNotNull(styles, content)) {
            for (group in root.kids()) when (group.localName) {
                "styles", "automatic-styles" -> for (s in group.kids()) when (s.localName) {
                    "default-style" -> if (s.attr(STYLE, "family") == "paragraph") defaults = s
                    "style" -> {
                        if (s.attr(STYLE, "family") == "paragraph" && group.localName == "styles") s.attr(STYLE, "name")?.let { named[it] = s }
                        s.attr(STYLE, "family")?.takeIf { it.startsWith("table") }?.let { f -> s.attr(STYLE, "name")?.let { tableStyles["$f/$it"] = s } }
                        if (s.attr(STYLE, "family") == "section") s.attr(STYLE, "name")?.let { n -> s.kid(STYLE, "section-properties")?.kid(STYLE, "columns")?.let { c ->
                            sectionColumns[n] = (c.attr(FO, "column-count")?.toIntOrNull()?.coerceIn(1, 6) ?: 1) to (odfLength(c.attr(FO, "column-gap")) ?: 36f) } }
                    }
                    "page-layout" -> s.attr(STYLE, "name")?.let { layouts[it] = s }
                    "list-style" -> s.attr(STYLE, "name")?.let { name ->
                        s.kids().forEach { lvl -> lvl.attr(TEXT, "level")?.toIntOrNull()?.let { n ->
                            listStyles[name to n - 1] = lvl
                            lvl.kid(STYLE, "list-level-properties")?.kid(STYLE, "list-level-label-alignment")?.let { listLevels[name to n - 1] = it }
                        } }
                    }
                }
                "master-styles" -> {
                    master = group.kids().firstOrNull { it.attr(STYLE, "name") == "Standard" } ?: group.kids().firstOrNull()
                    mainMaster = master?.attr(STYLE, "name")
                    group.kids().forEach { m -> m.attr(STYLE, "name")?.let { n -> m.attr(STYLE, "page-layout-name")?.let { layouts[it] }?.kid(STYLE, "page-layout-properties")?.let { masterPages[n] = it } } }
                }
            }
        }
        // Title, Subtitle and Quotations as LibreOffice makes them, for a document without: shown before it is saved with them.
        OfficeFormats.parse(("<x xmlns:style=\"$STYLE\" xmlns:fo=\"$FO\">" + ODT_BUILTIN.filterKeys { it !in named }.values.joinToString("") + "</x>").toByteArray())
            .documentElement.kids().forEach { st -> st.attr(STYLE, "name")?.let { named[it] = st } }
        // A table Writer put in, not saved yet: its styles are Writer's own.
        if ("table-cell/$ODT_TETRA_CELL" !in tableStyles) OfficeFormats.parse(("<x xmlns:style=\"$STYLE\" xmlns:fo=\"$FO\" xmlns:table=\"$TABLE\">$ODT_TETRA_STYLES</x>").toByteArray())
            .documentElement.kids().forEach { st -> tableStyles["${st.attr(STYLE, "family")}/${st.attr(STYLE, "name")}"] = st }
        val layout = master?.attr(STYLE, "page-layout-name")?.let { layouts[it] }
        val props = layout?.kid(STYLE, "page-layout-properties")
        fun len(name: String) = odfLength(props?.attr(FO, name))
        val d = DocPage()
        // A header sits at the top margin and the text starts below it (its height, at least one line, and its spacing),
        // as LibreOffice lays a page out; the footer likewise at the bottom.
        val (headers, footers) = OfficeFormats.odtMaster(parts)
        fun band(paras: List<DocPara>?, style: String, gap: String): Float {
            if (paras == null) return 0f
            val hf = layout?.kid(STYLE, style)?.kid(STYLE, "header-footer-properties")
            // LibreOffice's height of a header includes its spacing (its dialog's Height takes in Spacing): a letterhead's
            // text came 2.5 cm lower when the two were added (2026-10-13).
            val spacing = odfLength(hf?.attr(FO, gap)) ?: 0f
            return maxOf(odfLength(hf?.attr(FO, "min-height")) ?: 0f, paras.size.coerceAtLeast(1) * 14f + spacing)
        }
        val top = len("margin-top") ?: d.top; val bottom = len("margin-bottom") ?: d.bottom
        // A page's border stands at the margins; the text starts its padding inside it.
        val sideBorders = listOf("top" to 't', "left" to 'l', "bottom" to 'b', "right" to 'r').mapNotNull { (n, c) -> odfBorder(props?.attr(FO, "border-$n"))?.let { c to it } }
        val edge = odfBorder(props?.attr(FO, "border")) ?: sideBorders.firstOrNull()?.second
        val edgeSides = if (props?.attr(FO, "border") != null && edge != null) "tlbr" else sideBorders.joinToString("") { it.first.toString() }
        fun pad(side: String) = if (edge == null) 0f else len("padding-$side") ?: len("padding") ?: 0f
        section = DocSection(DocPage(len("page-width") ?: d.width, len("page-height") ?: d.height,
            top + pad("top") + band(headers["default"], "header-style", "margin-bottom"), bottom + pad("bottom") + band(footers["default"], "footer-style", "margin-top"),
            (len("margin-left") ?: d.left) + pad("left"), (len("margin-right") ?: d.right) + pad("right"), header = top, footer = bottom,
            columns = props?.kid(STYLE, "columns")?.attr(FO, "column-count")?.toIntOrNull()?.coerceIn(1, 6) ?: 1,
            columnGap = odfLength(props?.kid(STYLE, "columns")?.attr(FO, "column-gap")) ?: 36f,
            color = props?.attr(FO, "background-color")?.takeIf { it.startsWith("#") }?.let { OfficeFormats.parseHex(it.removePrefix("#")) },
            // LibreOffice draws a page's border round its margins.
            border = edge?.let { PageBorder(it, len("margin-left") ?: d.left, top, len("margin-right") ?: d.right, bottom, edgeSides) }),
            headers, footers, titlePage = "first" in headers || "first" in footers,
            // Its pages numbered i, ii… (the page style's num-format; "1" the usual).
            pageFormat = props?.attr(STYLE, "num-format")?.takeIf { it.isNotEmpty() && it != "1" } ?: "decimal",
            // LibreOffice numbers lines for the whole document (styles.xml), from page to page or on each page anew.
            lineNumbers = parts["styles.xml"]?.let { String(it, Charsets.UTF_8) }?.let { Regex("""<text:linenumbering-configuration\b[^>]*""").find(it)?.value }
                ?.takeIf { "text:number-lines=\"false\"" !in it }?.let { c -> fun a(n: String) = Regex("""\s$n="([^"]*)"""").find(c)?.groupValues?.get(1)
                    LineNumbers(if (a("text:restart-on-page") == "true") "newPage" else "continuous", a("text:increment")?.toIntOrNull()?.coerceAtLeast(1) ?: 1, odfLength(a("text:offset"))) },
            watermark = master?.getElementsByTagNameNS("urn:oasis:names:tc:opendocument:xmlns:drawing:1.0", "custom-shape")?.let { l ->
                (0 until l.length).map { l.item(it) as Element }.firstOrNull { it.getAttributeNS("urn:oasis:names:tc:opendocument:xmlns:drawing:1.0", "name").startsWith(Watermark.NAME) }?.textContent?.trim() },
            watermarkPicture = master?.getElementsByTagNameNS("urn:oasis:names:tc:opendocument:xmlns:drawing:1.0", "frame")?.let { l ->
                (0 until l.length).map { l.item(it) as Element }.firstOrNull { it.getAttributeNS("urn:oasis:names:tc:opendocument:xmlns:drawing:1.0", "name").startsWith(Watermark.PICTURE) }
                    ?.getElementsByTagNameNS("urn:oasis:names:tc:opendocument:xmlns:drawing:1.0", "image")?.item(0)?.let { (it as Element).getAttributeNS("http://www.w3.org/1999/xlink", "href") }
                    ?.let { Watermark.pictureInOdt(parts, it) } })
    }

    /**
     * An OpenDocument table (2026-09-28): its columns' widths, its rows' heights, each cell's background, lines,
     * padding and vertical alignment, from their styles; spans and merged-away (covered) cells as Word's are drawn.
     */
    override fun tableSpec(t: DocTable): TableSpec {
        fun a(xml: String, name: String) = Regex("""$name="([^"]*)"""").find(xml)?.groupValues?.get(1)
        fun props(family: String, attrs: String, kind: String) = a(attrs, "table:style-name")?.let { tableStyles["$family/$it"] }?.kid(STYLE, kind)
        val tp = props("table", t.props, "table-properties")
        val width = odfLength(tp?.attr(STYLE, "width"))
        val cols = ArrayList<Float>()
        Regex("""<table:table-column\b([^>]*?)/?>""").findAll(t.grid).forEach { m ->
            val cp = props("table-column", m.groupValues[1], "table-column-properties")
            val n = a(m.groupValues[1], "table:number-columns-repeated")?.toIntOrNull()?.coerceIn(1, 64) ?: 1
            val w = odfLength(cp?.attr(STYLE, "column-width")) ?: cp?.attr(STYLE, "rel-column-width")?.removeSuffix("*")?.toFloatOrNull()?.let { -it } ?: 0f
            repeat(n) { cols += w }
        }
        // Relative widths (negative here) share the table's width.
        if (cols.any { it < 0f }) { val rel = cols.filter { it < 0f }.sumOf { -it.toDouble() }.toFloat(); val w = width ?: section.page.textWidth; cols.replaceAll { if (it < 0f) -it / rel * w else it } }
        fun border(v: String?) = odfBorder(v)
        val gridCount = cols.size.coerceAtLeast(1)
        val vertical = IntArray(gridCount + 64) // rows still covered below a cell merged down, per grid column
        val verticalSpan = IntArray(gridCount + 64)
        val rows = t.rows.map { row ->
            val rp = props("table-row", row.props, "table-row-properties")
            var g = 0; var sideways = 0
            val cells = row.cells.map { c ->
                val covered = c.props.startsWith(ODT_COVERED)
                val cp = props("table-cell", c.props, "table-cell-properties")
                val pad = odfLength(cp?.attr(FO, "padding")) ?: 0f
                fun padding(side: String) = odfLength(cp?.attr(FO, "padding-$side")) ?: pad
                val all = cp?.attr(FO, "border")
                fun edge(side: String) = border(cp?.attr(FO, "border-$side") ?: all)
                val fill = cp?.attr(FO, "background-color")?.takeIf { it.startsWith("#") }?.let { OfficeFormats.parseHex(it) }
                val vAlign = when (cp?.attr(STYLE, "vertical-align")) { "middle" -> 'c'; "bottom" -> 'b'; else -> 't' }
                val turn = when (cp?.attr(STYLE, "writing-mode")) { "tb-rl", "tb" -> 90; else -> 0 }
                val spec = when {
                    covered && sideways > 0 -> { sideways--; CellSpec(0, 'c', null, 't', FloatArray(4), arrayOfNulls(4)) }
                    covered && vertical.getOrElse(g) { 0 } > 0 -> {
                        val span = verticalSpan[g]; vertical[g]--; sideways = span - 1
                        CellSpec(span, 'c', null, 't', FloatArray(4), arrayOfNulls(4)).also { g += span }
                    }
                    else -> {
                        val span = a(c.props, "table:number-columns-spanned")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        val down = a(c.props, "table:number-rows-spanned")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        if (down > 1 && g < vertical.size) { vertical[g] = down - 1; verticalSpan[g] = span }
                        sideways = span - 1
                        CellSpec(span, if (down > 1) 'r' else if (covered) 'c' else 'n', fill, vAlign,
                            floatArrayOf(padding("left"), padding("top"), padding("right"), padding("bottom")),
                            arrayOf(edge("top"), edge("left"), edge("bottom"), edge("right")), turn).also { g += span }
                    }
                }
                spec
            }
            val exact = odfLength(rp?.attr(STYLE, "row-height"))
            RowSpec(exact ?: odfLength(rp?.attr(STYLE, "min-row-height")), exact != null, row.props.startsWith(ODT_HEADER), cells)
        }
        val align = when (tp?.attr(TABLE, "align")) { "center" -> DocAlign.Center; "right" -> DocAlign.End; else -> DocAlign.Start }
        return TableSpec(cols, odfLength(tp?.attr(FO, "margin-left")) ?: 0f, align, rows)
    }

    private fun one(l: ParaLook, name: String, v: String, base: Float): ParaLook = when (name) {
        "fo:margin-top" -> odfLength(v)?.let { l.copy(before = it) } ?: l
        "fo:margin-bottom" -> odfLength(v)?.let { l.copy(after = it) } ?: l
        "fo:margin-left" -> odfLength(v)?.let { l.copy(left = it) } ?: l
        "fo:margin-right" -> odfLength(v)?.let { l.copy(right = it) } ?: l
        "fo:text-indent" -> odfLength(v)?.let { l.copy(firstLine = it) } ?: l
        "fo:line-height" -> if (v.endsWith("%")) v.removeSuffix("%").toFloatOrNull()?.let { l.copy(lineMultiple = it / 100f, lineExact = null) } ?: l
            else odfLength(v)?.let { l.copy(lineExact = it, lineAtLeast = false) } ?: l
        "style:line-height-at-least" -> odfLength(v)?.let { l.copy(lineExact = it, lineAtLeast = true) } ?: l
        "fo:break-before" -> l.copy(pageBreakBefore = v == "page", columnBreakBefore = v == "column")
        "fo:border" -> odfBorder(v).let { b -> l.copy(box = (l.box ?: ParaBox()).copy(top = b, left = b, bottom = b, right = b).takeIf { !it.isEmpty }) }
        "fo:border-top" -> l.copy(box = (l.box ?: ParaBox()).copy(top = odfBorder(v)).takeIf { !it.isEmpty })
        "fo:border-bottom" -> l.copy(box = (l.box ?: ParaBox()).copy(bottom = odfBorder(v)).takeIf { !it.isEmpty })
        "fo:border-left" -> l.copy(box = (l.box ?: ParaBox()).copy(left = odfBorder(v)).takeIf { !it.isEmpty })
        "fo:border-right" -> l.copy(box = (l.box ?: ParaBox()).copy(right = odfBorder(v)).takeIf { !it.isEmpty })
        "style:join-border" -> if (v == "false") l.box?.let { l.copy(box = it.copy(between = it.top ?: it.bottom)) } ?: l else l
        "fo:padding" -> odfLength(v)?.let { d -> l.box?.let { l.copy(box = it.copy(padTop = d, padLeft = d, padBottom = d, padRight = d)) } } ?: l
        "fo:padding-top" -> odfLength(v)?.let { d -> l.box?.let { l.copy(box = it.copy(padTop = d)) } } ?: l
        "fo:padding-bottom" -> odfLength(v)?.let { d -> l.box?.let { l.copy(box = it.copy(padBottom = d)) } } ?: l
        "fo:padding-left" -> odfLength(v)?.let { d -> l.box?.let { l.copy(box = it.copy(padLeft = d)) } } ?: l
        "fo:padding-right" -> odfLength(v)?.let { d -> l.box?.let { l.copy(box = it.copy(padRight = d)) } } ?: l
        "fo:text-align" -> l.copy(align = when (v) { "center" -> DocAlign.Center; "end", "right" -> DocAlign.End; "justify" -> DocAlign.Justify; else -> DocAlign.Start })
        "fo:font-family" -> l.copy(font = v.trim('\'', '"'))
        "style:contextual-spacing", "fo:contextual-spacing" -> l.copy(contextual = v == "true")
        "fo:font-size" -> (if (v.endsWith("%")) v.removeSuffix("%").toFloatOrNull()?.let { (l.sizePt ?: base) * it / 100 } else odfLength(v))?.let { l.copy(sizePt = it) } ?: l
        "fo:font-weight" -> l.copy(bold = v == "bold" || (v.toIntOrNull() ?: 400) >= 600)
        "fo:font-style" -> l.copy(italic = v == "italic" || v == "oblique")
        "fo:color" -> OfficeFormats.parseHex(v)?.let { l.copy(color = it) } ?: l
        "style:font-name" -> l.copy(font = v)
        else -> l
    }

    /** A style's or a paragraph's tab stops (they replace the ones before), positions from the text's indent. */
    private fun tabs(l: ParaLook, xml: String): ParaLook {
        if ("tab-stops" !in xml) return l
        val t = Regex("""<style:tab-stop\s([^>]*?)/?>""").findAll(xml).mapNotNull { m ->
            fun a(n: String) = Regex("""$n="([^"]*)"""").find(m.groupValues[1])?.groupValues?.get(1)
            val pos = odfLength(a("style:position")) ?: return@mapNotNull null
            TabStop(pos, when (a("style:type")) { "right", "char" -> 'r'; "center" -> 'c'; else -> 'l' },
                a("style:leader-text")?.firstOrNull()?.takeIf { it != ' ' } ?: when (a("style:leader-style")) { "dotted" -> '.'; "dash" -> '-'; "solid" -> '_'; else -> null })
        }.toList()
        return l.copy(tabs = t.sortedBy { it.pos })
    }

    private fun apply(l: ParaLook, style: Element?, base: Float): ParaLook {
        var look = l
        style?.kid(STYLE, "paragraph-properties")?.let { pp -> pp.kids().firstOrNull { it.localName == "tab-stops" }?.let { look = tabs(look, OfficeFormats.xml(it)) } }
        for (group in listOfNotNull(style?.kid(STYLE, "paragraph-properties"), style?.kid(STYLE, "text-properties"))) {
            val a = group.attributes
            for (i in 0 until a.length) a.item(i).let { look = one(look, it.nodeName, it.nodeValue.trim(), base) }
        }
        return look
    }

    override fun paragraphStyles(): List<StyleEntry> {
        val own = named.filterKeys { it != "Standard" && it != "Text_20_body" && it != "Heading" && !it.startsWith("Heading_20_") }
            .map { (name, e) -> StyleEntry(e.attr(STYLE, "display-name") ?: odfDisplayName(name), name) }
        return standardStyles(own, "Title", "Subtitle", "Quotations")
    }
    override fun styleKey(e: StyleEntry) = if (e.heading > 0) "Heading_20_${e.heading}" else e.id ?: if ("Text_20_body" in named) "Text_20_body" else "Standard"

    override fun look(p: DocPara): ParaLook {
        var look = apply(ParaLook(), defaults, 12f)
        val name = if (p.heading > 0) "Heading_20_${p.heading}".takeIf { it in named } ?: "Heading" else p.keep?.style ?: "Standard"
        val chain = ArrayList<Element>()
        var s = named[name]
        while (s != null && chain.size < 20) { chain.add(0, s); s = named[s.attr(STYLE, "parent-style-name")] }
        for (st in chain) look = apply(look, st, look.sizePt ?: 12f)
        p.keep?.parts?.forEach { (kind, xml) ->
            if (kind == "@" || kind == "text@") Regex(""" (\S+)="([^"]*)"""").findAll(xml).forEach { m -> look = one(look, m.groupValues[1], m.groupValues[2], look.sizePt ?: 12f) }
            if (kind == "child") look = tabs(look, xml)
        }
        // LibreOffice counts tab stops from the paragraph's indent.
        return look.copy(tabs = look.tabs.map { it.copy(pos = it.pos + look.left) })
    }

    override fun listLabel(p: DocPara, counts: IntArray): String? {
        val name = p.keep?.list?.substringBefore('|') ?: return null
        val level = p.list!!.level
        val l = listStyles[name to level] ?: return null
        if (l.localName == "list-level-style-bullet") return bullet(l.attr(TEXT, "bullet-char") ?: "•")
        val shown = (l.attr(TEXT, "display-levels")?.toIntOrNull() ?: 1).coerceIn(1, level + 1)
        val numbers = (level - shown + 1..level).joinToString(".") { k -> number(counts[k].coerceAtLeast(1), listStyles[name to k]?.attr(STYLE, "num-format") ?: "1") }
        return (l.attr(STYLE, "num-prefix") ?: "") + numbers + (l.attr(STYLE, "num-suffix") ?: "")
    }

    // shortcut: as Word's, the first numbered list at each level speaks for the whole document.
    override fun orderedLevels(paras: List<DocPara>): List<Pair<String, String>?> = (0..8).map { l ->
        paras.firstNotNullOfOrNull { p -> p.list?.takeIf { it.ordered && it.level == l }?.let { p.keep?.list?.substringBefore('|') }?.let { listStyles[it to l] } }
            ?.takeIf { it.localName != "list-level-style-bullet" }?.let { s ->
                val shown = (s.attr(TEXT, "display-levels")?.toIntOrNull() ?: 1).coerceIn(1, l + 1)
                (s.attr(STYLE, "num-format") ?: "1") to (s.attr(STYLE, "num-prefix") ?: "") + (l - shown + 1..l).joinToString(".") { "%${it + 1}" } + (s.attr(STYLE, "num-suffix") ?: "")
            }
    }

    override fun listIndent(p: DocPara): Pair<Float, Float> {
        val a = p.keep?.list?.substringBefore('|')?.let { listLevels[it to p.list!!.level] } ?: return super.listIndent(p)
        val left = odfLength(a.attr(FO, "margin-left")) ?: return super.listIndent(p)
        return left to -(odfLength(a.attr(FO, "text-indent")) ?: 0f)
    }
}

/** An ODF length in points ("2cm", "0.5in", "12pt"…); null for anything else. */
internal fun odfLength(v: String?): Float? = v?.let { Regex("""^(-?[\d.]+)(cm|mm|in|pt|pc|px)$""").find(it.trim()) }?.let { m ->
    val n = m.groupValues[1].toFloatOrNull() ?: return null
    when (m.groupValues[2]) { "cm" -> n * 28.3465f; "mm" -> n * 2.83465f; "in" -> n * 72; "pc" -> n * 12; "px" -> n * 0.75f; else -> n }
}

/** One page, laid out: pieces of paragraphs (some of their lines) and where they go, and floating pictures. */
internal class LaidPage(val page: DocPage, val pieces: List<Piece>, val floats: List<Floating> = emptyList(),
    val section: DocSection = DocSection(page), val number: Int = 1, val firstOfSection: Boolean = false,
    val boxes: List<Box> = emptyList(), val lines: List<Line> = emptyList(), val lineNumbers: List<LineNumber> = emptyList()) {
    /** A line's number in the margin: right-aligned at [x], its baseline at [y]. */
    class LineNumber(val x: Float, val y: Float, val text: String, val paint: android.graphics.Paint)
    /** [turn]: drawn turned that many degrees about ([tx], [ty]), its x and y then counted in the turned frame. */
    class Piece(val layout: StaticLayout, val firstLine: Int, val lastLine: Int, val x: Float, val y: Float,
        val turn: Int = 0, val tx: Float = 0f, val ty: Float = 0f)
    /** A table cell's shading, and a border. */
    class Box(val x: Float, val y: Float, val w: Float, val h: Float, val color: Int)
    class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val width: Float, val color: Int)
    /** [key]: the picture's (DocImage.key), for Picture › Position to find it. */
    class Floating(val picture: android.graphics.drawable.Drawable, val x: Float, val y: Float, val w: Float, val h: Float, val behind: Boolean, val key: String? = null,
        val z: Long = 0 /* drawn over the lower ones (Word's relativeHeight, LibreOffice's z-index) */, val turn: Float = 0f /* degrees clockwise, about its middle */) {
        fun drawAt(canvas: Canvas, x: Float = this.x, y: Float = this.y) = drawTurned(canvas, picture, x, y, w, h, turn)
    }

    /** The video whose picture is at [x], [y] (points from the page's top left), and where it is, else null: a tap on it plays it there. */
    fun videoAt(x: Float, y: Float): OfficeVideo.Spot? {
        floats.lastOrNull { it.picture is OfficeVideo.Marked && x in it.x..it.x + it.w && y in it.y..it.y + it.h }?.let { return OfficeVideo.Spot((it.picture as OfficeVideo.Marked).film, it.x, it.y, it.w, it.h, it.turn) }
        for (p in pieces) {
            if (p.turn != 0) continue
            val text = p.layout.text as? Spanned ?: continue
            val top = p.layout.getLineTop(p.firstLine)
            for (span in text.getSpans(0, text.length, ImageSpan::class.java)) {
                val d = span.drawable as? OfficeVideo.Marked ?: continue
                val at = text.getSpanStart(span); val line = p.layout.getLineForOffset(at)
                if (line !in p.firstLine..p.lastLine) continue
                // in twentieths of a point, its foot on the line's (ALIGN_BOTTOM)
                val left = p.x + p.layout.getPrimaryHorizontal(at) / U; val foot = p.y + (p.layout.getLineBottom(line) - top) / U
                val w = d.bounds.width() / U; val h = d.bounds.height() / U
                if (x in left..left + w && y in foot - h..foot) return OfficeVideo.Spot(d.film, left, foot - h, w, h)
            }
        }
        return null
    }

    /** Drawn with the canvas in points, the page's top left at 0,0 (white is the caller's; a page colour is drawn). */
    fun draw(canvas: Canvas, skip: String? = null /* a float's key: left out (it is being moved) */) {
        page.color?.let { canvas.drawRect(0f, 0f, page.width, page.height, android.graphics.Paint().apply { color = 0xFF000000.toInt() or it }) }
        page.border?.let { b ->
            val (l, t, r, bt) = listOf(b.left, b.top, page.width - b.right, page.height - b.bottom)
            if ('t' in b.sides) drawBorderLine(canvas, l, t, r, t, b.line)
            if ('b' in b.sides) drawBorderLine(canvas, l, bt, r, bt, b.line)
            if ('l' in b.sides) drawBorderLine(canvas, l, t, l, bt, b.line)
            if ('r' in b.sides) drawBorderLine(canvas, r, t, r, bt, b.line)
        }
        fun floats(behind: Boolean) = floats.filter { it.behind == behind && (skip == null || it.key != skip) }.sortedBy { it.z }.forEach { it.drawAt(canvas) }
        section.watermark?.let { text ->
            // Across the text's width, turned 45° up, light grey and half see-through, behind the text (Word's look).
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x80C0C0C0.toInt(); typeface = android.graphics.Typeface.DEFAULT; textSize = 100f }
            p.textSize = 100f * page.textWidth / p.measureText(text).coerceAtLeast(1f)
            canvas.save(); canvas.rotate(-45f, page.width / 2, page.height / 2)
            canvas.drawText(text, (page.width - p.measureText(text)) / 2, page.height / 2 - (p.ascent() + p.descent()) / 2, p)
            canvas.restore()
        }
        section.watermarkPicture?.let { bytes -> washedOut(bytes) }?.let { bmp ->
            // Centred on the text, as wide as it (no taller), washed out (Word's Washout: its contrast and brightness).
            val (w, h) = Watermark.pictureBox(bmp.height.toFloat() / bmp.width.coerceAtLeast(1), page)
            val x = page.left + (page.textWidth - w) / 2; val y = page.top + (page.height - page.top - page.bottom - h) / 2
            canvas.drawBitmap(bmp, null, android.graphics.RectF(x, y, x + w, y + h), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        }
        floats(behind = true)
        drawParts(canvas, pieces, boxes, lines)
        for (n in lineNumbers) canvas.drawText(n.text, n.x - n.paint.measureText(n.text), n.y, n.paint)
        floats(behind = false)
    }
    companion object {
    private val washed = java.util.WeakHashMap<ByteArray, android.graphics.Bitmap>()
    /** A watermark picture as Word's Washout shows it (decoded once per picture). */
    private fun washedOut(bytes: ByteArray): android.graphics.Bitmap? = synchronized(washed) { washed[bytes] } ?: runCatching {
        val src = decodePicture(bytes, 1600) ?: return null
        val out = android.graphics.Bitmap.createBitmap(src.width, src.height, android.graphics.Bitmap.Config.ARGB_8888)
        // Word's gain 0.3 and black level 0.35: the colours kept at three tenths, lifted most of the way to white.
        val m = android.graphics.ColorMatrix(floatArrayOf(0.3f, 0f, 0f, 0f, 178f, 0f, 0.3f, 0f, 0f, 178f, 0f, 0f, 0.3f, 0f, 178f, 0f, 0f, 0f, 1f, 0f))
        Canvas(out).drawBitmap(src, 0f, 0f, android.graphics.Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(m) })
        out
    }.getOrNull()?.also { synchronized(washed) { washed[bytes] = it } }
    /** Text, shading and lines, in points from the canvas's 0,0. */
    fun drawParts(canvas: Canvas, pieces: List<Piece>, boxes: List<Box>, lines: List<Line>) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        for (b in boxes) { paint.color = 0xFF000000.toInt() or b.color; canvas.drawRect(b.x, b.y, b.x + b.w, b.y + b.h, paint) }
        for (p in pieces) {
            val top = p.layout.getLineTop(p.firstLine).toFloat()
            val bottom = p.layout.getLineBottom(p.lastLine).toFloat()
            canvas.save()
            if (p.turn != 0) { canvas.translate(p.tx, p.ty); canvas.rotate(p.turn.toFloat()) }
            canvas.translate(p.x, p.y)
            canvas.scale(1 / U, 1 / U) // the layout counts in twentieths of a point
            canvas.translate(0f, -top)
            canvas.clipRect(-p.x * U, top, p.layout.width + (p.x + 1000f) * U, bottom)
            p.layout.draw(canvas)
            LetterLooks.drawSecondLines(canvas, p.layout, p.firstLine, p.lastLine)
            canvas.restore()
        }
        paint.strokeCap = android.graphics.Paint.Cap.SQUARE
        for (l in lines) { paint.color = 0xFF000000.toInt() or l.color; paint.strokeWidth = l.width; canvas.drawLine(l.x1, l.y1, l.x2, l.y2, paint) }
    }
    }
}

/**
 * A text box (Word's and LibreOffice's; the plan, 2026-10-01): its frame — a shape's fill and outline, else Word's white
 * box with a thin black line — and its words laid out inside it, [inset] from its sides.
 */
/** A drawing in points drawn in a line, which counts in [scale]ths of a point. */
/** A border's line from ([x1], [y1]) to ([x2], [y2]) — straight across or down — in its style: double as two thin lines a line apart. */
internal fun drawBorderLine(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, b: Border) {
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; color = 0xFF000000.toInt() or b.color }
    when (b.style) {
        "double" -> {
            val each = b.width / 3; p.strokeWidth = each
            val (dx, dy) = if (y1 == y2) 0f to each else each to 0f
            canvas.drawLine(x1 - dx, y1 - dy, x2 - dx, y2 - dy, p); canvas.drawLine(x1 + dx, y1 + dy, x2 + dx, y2 + dy, p)
            return
        }
        "dotted" -> { p.strokeCap = android.graphics.Paint.Cap.ROUND; p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(0.01f, b.width * 2), 0f) }
        "dashed" -> p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(b.width * 4, b.width * 2.5f), 0f)
    }
    p.strokeWidth = b.width
    canvas.drawLine(x1, y1, x2, y2, p)
}

/** [picture] in [x], [y], [w] × [h] (points), turned [turn] degrees clockwise about its middle. */
internal fun drawTurned(canvas: Canvas, picture: android.graphics.drawable.Drawable, x: Float, y: Float, w: Float, h: Float, turn: Float) {
    if (turn != 0f) { canvas.save(); canvas.rotate(turn, x + w / 2, y + h / 2) }
    (picture as? ShapePicture)?.drawAt(canvas, x, y, w, h) ?: run { picture.setBounds(x.toInt(), y.toInt(), (x + w).toInt(), (y + h).toInt()); picture.draw(canvas) }
    if (turn != 0f) canvas.restore()
}

/** A group's pieces, each in its place in the group's [w] × [h] box (points) and turned as it is. */
internal class GroupDrawable(val w: Float, val h: Float, val parts: List<Pair<GroupPart, android.graphics.drawable.Drawable>>) : android.graphics.drawable.Drawable() {
    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.save(); canvas.translate(b.left.toFloat(), b.top.toFloat()); canvas.scale(b.width() / w.coerceAtLeast(0.1f), b.height() / h.coerceAtLeast(0.1f))
        for ((p, d) in parts) drawTurned(canvas, d, p.x, p.y, p.w, p.h, Arrange.rotationOf(p.img))
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
    @Deprecated("Drawable") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

private class InLineDrawable(val inner: android.graphics.drawable.Drawable, val scale: Float) : android.graphics.drawable.Drawable() {
    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.save(); canvas.translate(b.left.toFloat(), b.top.toFloat()); canvas.scale(scale, scale)
        inner.setBounds(0, 0, Math.round(b.width() / scale), Math.round(b.height() / scale)); inner.draw(canvas)
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
    @Deprecated("Drawable") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

internal object OfficePages {
    /**
     * A table's columns fitted into [width] when wider: each keeps at least [least] (its longest word, when known),
     * and what is left is shared in proportion to what they had over that — so on a phone a column of short numbers
     * narrows and a column of words keeps its words whole, rather than every column shrinking alike.
     */
    internal fun fittedTableColumns(columns: List<Float>, width: Float, indent: Float, least: List<Float> = emptyList()): List<Float> {
        val limit = (width - indent.coerceAtLeast(0f)).coerceAtLeast(1f)
        if (columns.sum() <= limit) return columns
        val need = columns.indices.map { (least.getOrNull(it) ?: 0f).coerceAtMost(columns[it]) }
        if (need.sum() >= limit) return need.sum().let { n -> if (n > 0f) need.map { it * limit / n } else columns.map { it * limit / columns.sum() } }
        val over = columns.indices.map { columns[it] - need[it] }
        return columns.indices.map { need[it] + (limit - need.sum()) * over[it] / over.sum() }
    }

    /** The widest word of a cell's paragraphs (pt), in their own sizes and fonts: less than this, a word breaks. */
    private fun longestWord(doc: OfficeDoc, paras: List<DocPara>, styles: DocStyles): Float {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG)
        return paras.filter { it.table == null }.maxOfOrNull { p ->
            val look = styles.look(p)
            p.runs.filter { it.image == null }.maxOfOrNull { r ->
                paint.textSize = r.sizePt ?: look.sizePt ?: doc.basePt
                paint.typeface = OfficeFonts.styled(OfficeFonts.typeface(styles.fontName(r.font) ?: look.font), r.bold || look.bold == true, r.italic || look.italic == true)
                r.text.split(' ', '\t', LINE_BREAK).maxOfOrNull { paint.measureText(it) } ?: 0f
            } ?: 0f
        } ?: 0f
    }

    /** Word's default tab stops: every half inch. */
    private const val TAB = 36f
    /** Between a Square picture and the text beside it: Word's 0.13" (distL/distR 114300). */
    private const val SIDE_GAP = 9f
    /** Less room than this beside a Square picture: no text there. */
    private const val MIN_BESIDE = 72f

    /**
     * [pageOf], when given, learns the page number (as printed) each paragraph starts on — for a table of contents;
     * [pageIndexOf] the page itself (0 = the first), as the list has them.
     */
    /** A note's paragraphs as laid out: its number (superscript) and a space before its first words. */
    fun noteParas(n: DocNote, ref: DocRun): List<DocPara> = n.paras.mapIndexed { k, q ->
        if (k > 0) q else q.copy(runs = listOf(DocRun(ref.text, script = 1), DocRun(" ")) + q.runs)
    }

    /** A floating picture the text goes around on its page (Square wrap), or under (a [band]: no text beside it). */
    private class Wall(val key: String, val x0: Float, val y0: Float, val x1: Float, val y1: Float, val band: Boolean)

    fun layout(given: OfficeDoc, styles: DocStyles, pageOf: IntArray? = null, pageIndexOf: IntArray? = null, pictureBytes: (DocImage) -> ByteArray?): List<LaidPage> =
        layout(given, styles, pageOf, pageIndexOf, null, pictureBytes)
    /** …and [paraTopOf] each paragraph's top on its page (pt): where a picture placed from its paragraph is measured from. */
    fun layout(given: OfficeDoc, styles: DocStyles, pageOf: IntArray?, pageIndexOf: IntArray?, paraTopOf: FloatArray?, pictureBytes: (DocImage) -> ByteArray?): List<LaidPage> {
        // Pictures placed on the page itself (not from their paragraph) may stand beside text that comes before them:
        // laid out once to find where they are, then again with the text going around them from the page's top.
        val found = HashMap<Int, MutableList<Wall>>()
        val once = layoutPass(given, styles, pageOf, pageIndexOf, paraTopOf, pictureBytes, emptyMap(), found)
        return if (found.isEmpty()) once else layoutPass(given, styles, pageOf, pageIndexOf, paraTopOf, pictureBytes, found, HashMap())
    }

    private fun layoutPass(given: OfficeDoc, styles: DocStyles, pageOf: IntArray?, pageIndexOf: IntArray?, paraTopOf: FloatArray?, pictureBytes: (DocImage) -> ByteArray?,
        seed: Map<Int, List<Wall>>, found: MutableMap<Int, MutableList<Wall>>): List<LaidPage> {
        // Notes numbered as they stand now; endnotes laid out after the text, each its number then its words.
        val numbered = OfficeFormats.numberNotes(given)
        val bodyCount = numbered.paras.size
        val endnotes = ArrayList<DocPara>()
        numbered.paras.forEach { p -> p.runs.forEach { r -> r.note?.takeIf { it.end }?.let { n -> endnotes += noteParas(n, r) } } }
        val doc = numbered.copy(paras = numbered.paras + endnotes)
        val sections = styles.sections(numbered.paras)
        // The text starts below a header taller than the top margin leaves it room for, and ends above such a footer, as
        // Word and LibreOffice lay it out (a worksheet's 21 pt margin under a header band at 36 pt ran into it).
        // shortcut: measured from each section's default header and footer; a title page's or even pages' own are not.
        val fitted = java.util.IdentityHashMap<DocSection, DocSection>()
        fun fit(s: DocSection) = fitted.getOrPut(s) {
            fun tall(map: Map<String, List<DocPara>>) = map["default"]?.takeIf { it.isNotEmpty() }
                ?.let { stack(doc, it, styles, s.page, s.page.textWidth, pictureBytes, null, 0f, 0f).height } ?: 0f
            val top = maxOf(s.page.top, s.page.header + tall(s.headers)); val bottom = maxOf(s.page.bottom, s.page.footer + tall(s.footers))
            if (top == s.page.top && bottom == s.page.bottom) s else s.copy(page = s.page.copy(top = top, bottom = bottom))
        }
        var section = fit(sections.firstOrNull() ?: styles.section)
        var frame = section.page // the sheet
        var col = 0
        var page = frame.column(0) // where the text flows: the page, or one of its columns
        var number = section.pageStart ?: 1
        var firstOfSection = true
        val pages = ArrayList<LaidPage>()
        var pieces = ArrayList<LaidPage.Piece>()
        var floats = ArrayList<LaidPage.Floating>()
        var boxes = ArrayList<LaidPage.Box>(); var lines = ArrayList<LaidPage.Line>()
        var y = page.top
        var colTop = page.top // where columns start on this page (lower, after a continuous section break)
        // Layout › Line numbers: the lines numbered so far, and this page's numbers.
        var lineNo = 0
        var lineNums = ArrayList<LaidPage.LineNumber>()
        val numberPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            val look = styles.look(DocPara(emptyList())) // Word's Line Number style is the text's own font
            typeface = OfficeFonts.styled(OfficeFonts.typeface(look.font), false, false); textSize = look.sizePt ?: doc.basePt; color = android.graphics.Color.BLACK
        }
        // The page's pictures that no text may stand beside (top-and-bottom wrap), top to bottom.
        val bands = ArrayList<Pair<Float, Float>>()
        // …and those it goes around (Square), with the pictures already counted (seeded from the page's first pass).
        val squares = ArrayList<Wall>()
        val walled = HashSet<String>()
        fun seedPage() { seed[pages.size]?.forEach { w -> walled += w.key; if (w.band) bands += w.y0 to w.y1 else squares += w } }
        seedPage()
        /** Does a line from [t] to [b] cross a band? */
        fun crosses(t: Float, b: Float) = bands.any { (a, e) -> t < e && b > a }
        /** [at] moved below any band a line of height [h] there would cross. */
        fun clear(at: Float, h: Float): Float {
            var yy = at
            var moved = true
            while (moved) { moved = false; for ((a, b) in bands) if (yy < b && yy + h > a) { yy = b; moved = true } }
            return yy
        }
        // Footnotes (asked 2026-09-30): at the foot of the page their reference is on, under a short line, as Word does.
        var feet = ArrayList<StaticLayout>() // this page's (or column's) footnotes, laid out
        var feetHeight = 0f
        val sepGap = 12f // the line above the footnotes and the space around it
        fun footLayouts(refs: List<DocRun>) = refs.flatMap { r -> noteParas(r.note!!, r).map { q ->
            paragraph(doc, q, styles.look(q), styles, page, null, pictureBytes, ArrayList()).first } }
        fun heightOf(ls: List<StaticLayout>) = ls.sumOf { (it.height / U).toDouble() }.toFloat()
        fun placeFeet() {
            if (feet.isEmpty()) return
            var fy = page.height - page.bottom - feetHeight
            lines += LaidPage.Line(page.left, fy + sepGap / 2, page.left + minOf(144f, page.textWidth / 3), fy + sepGap / 2, 0.5f, 0)
            fy += sepGap
            for (l in feet) { pieces += LaidPage.Piece(l, 0, l.lineCount - 1, page.left, fy); fy += l.height / U }
            feet = ArrayList(); feetHeight = 0f
        }
        fun newPage() {
            placeFeet()
            // In columns: the next column first (the page's pictures still stand where they are).
            if (col + 1 < frame.columns) { col++; page = frame.column(col); y = colTop; return }
            bands.clear(); squares.clear(); walled.clear()
            pages += LaidPage(frame, pieces, floats, section, number, firstOfSection, boxes, lines, lineNums)
            pieces = ArrayList(); floats = ArrayList(); boxes = ArrayList(); lines = ArrayList(); lineNums = ArrayList(); col = 0; page = frame.column(0); y = page.top; colTop = page.top; number++; firstOfSection = false
            if (section.lineNumbers?.restart == "newPage") lineNo = 0
            seedPage()
        }
        /** A page break: a whole new page, whatever column the text is in. */
        fun breakPage() { col = frame.columns; newPage() }
        fun place(b: Block) { pieces += b.pieces; boxes += b.boxes; lines += b.lines }
        val counters = IntArray(9)
        var prevLook: ParaLook? = null; var prevStyle: String? = null
        // Where the paragraph before ended (its text, before the space after it), on which page: a bordered box goes on
        // down through the space between two paragraphs with the same borders.
        var prevEnd = 0f; var prevEndPage = -1
        doc.paras.forEachIndexed { i, p ->
            // The endnotes: after a short line below the text.
            if (i == bodyCount) {
                y += 12f
                lines += LaidPage.Line(page.left, y, page.left + minOf(144f, page.textWidth / 3), y, 0.5f, 0)
                y += 6f
            }
            // A new section: a new page (unless it goes on on the same one) of its own size, numbered as it says.
            sections.getOrNull(i)?.let(::fit)?.takeIf { it !== section }?.let { next ->
                if (!next.continuous) {
                    if (pieces.isNotEmpty() || floats.isNotEmpty()) breakPage()
                    section = next; frame = next.page; col = 0; page = frame.column(0); y = page.top; colTop = y; firstOfSection = true
                    if (next.lineNumbers?.restart != "continuous") lineNo = 0
                    next.pageStart?.let { number = it }
                } else {
                    // Continuous: on down the same page — in the new section's columns from here (not balanced).
                    if (next.page.columns != frame.columns) { frame = frame.copy(columns = next.page.columns, columnGap = next.page.columnGap); col = 0; page = frame.column(0); colTop = y }
                    section = next
                }
            }
            // A table: its rows, each kept whole on a page; its heading rows again at the top of each page it goes on to.
            p.table?.let { t ->
                val rows = tableRows(doc, t, styles, page, page.textWidth, pictureBytes)
                val heading = rows.takeWhile { it.second }.map { it.first }
                val limit = page.height - page.bottom - feetHeight
                rows.forEachIndexed { ri, (row, _) ->
                    y = clear(y, row.height) // below a picture no text stands beside, as a line goes
                    if (y + row.height > limit && (pieces.isNotEmpty() || boxes.isNotEmpty() || lines.isNotEmpty())) {
                        newPage()
                        if (ri >= heading.size) heading.forEach { h -> place(h.moved(page.left, y)); y += h.height }
                    }
                    place(row.moved(page.left, y)); y += row.height
                }
                counters.fill(0); prevLook = null; prevStyle = null; prevEndPage = -1
                return@forEachIndexed
            }
            val list = p.list
            if (list == null) counters.fill(0) else {
                // A list begins after a paragraph that is not in one, or where a top-level item is of another list.
                fun key(q: DocPara) = q.keep?.list?.substringBefore('|') ?: "writer ${q.list?.ordered}"
                val prev = doc.paras.getOrNull(i - 1)
                if (prev?.list == null || (list.level == 0 && key(prev) != key(p))) { counters.fill(0); counters[0] = (list.start ?: 1) - 1 }
                list.start?.takeIf { list.level > 0 && counters[list.level] == 0 }?.let { counters[list.level] = it - 1 }
                counters[list.level]++
                for (d in list.level + 1 until 9) counters[d] = 0
            }
            val look = styles.look(p)
            val marker = if (list == null) null else styles.listLabel(p, counters) ?: if (list.ordered) doc.numbering?.takeIf { p.keep?.list == null }?.let { styles.label(it, list.level, counters) } ?: "${counters[list.level]}." else listOf("•", "◦", "▪")[list.level % 3]
            val floating = ArrayList<Pair<DocImage, PictureXml.Place>>()
            val drops = ArrayList<DropLetters>()
            val notesAt = ArrayList<Pair<Int, DocRun>>()
            var (layout, shift, lastExtra) = paragraph(doc, p, look, styles, page, marker, pictureBytes, floating, drops, notesAt)
            /**
             * Square wrap: each line of [l], laid out from [at], indented past the pictures beside it — on the side with more
             * room. ponytail: one side only (Word's "both sides" puts text on each side of a picture in the middle).
             */
            fun besides(l: StaticLayout, at: Float): Pair<IntArray, IntArray>? {
                val x0 = page.left - shift; val x1 = x0 + l.width / U
                val lefts = IntArray(l.lineCount + 1); val rights = IntArray(l.lineCount + 1)
                var any = false
                for (k in 0 until l.lineCount) {
                    val t = at + l.topPt(k) - l.topPt(0); val b = at + l.bottomPt(k) - l.topPt(0)
                    for (w in squares) if (t < w.y1 && b > w.y0 && w.x1 > x0 && w.x0 < x1) {
                        any = true
                        if (w.x0 - SIDE_GAP - x0 >= x1 - w.x1 - SIDE_GAP) rights[k] = maxOf(rights[k], Math.round((x1 - w.x0 + SIDE_GAP) * U))
                        else lefts[k] = maxOf(lefts[k], Math.round((w.x1 + SIDE_GAP - x0) * U))
                    }
                }
                return if (any) lefts to rights else null
            }
            // Each line's footnotes: they must fit on the page with it.
            val feetOf = notesAt.filter { it.second.note?.end == false }.groupBy({ layout.getLineForOffset(it.first) }, { it.second })
            val style = p.keep?.style + "/" + p.heading
            // Space between paragraphs: after the one before, then before this one — none between two of the same
            // style that say so (contextual spacing).
            val same = look.contextual && prevLook?.contextual == true && style == prevStyle
            if (look.pageBreakBefore && pieces.isNotEmpty()) breakPage()
            else if (look.columnBreakBefore && i > 0) newPage() // the next column (or page, from the last one) — even at a column's top, as Word and LibreOffice do
            // Space before: not at a page's top — except the document's first paragraph, as Word and LibreOffice keep it.
            if ((pieces.isNotEmpty() || i == 0) && !same) y += look.before
            fun limitNow() = page.height - page.bottom - (if (feet.isEmpty()) 0f else feetHeight)
            /** How far the text may go with lines [from]..[to] on this page: their footnotes too below it. */
            fun limitWith(from: Int, to: Int): Float {
                val refs = (from..to).flatMap { feetOf[it].orEmpty() }
                if (refs.isEmpty()) return limitNow()
                return page.height - page.bottom - feetHeight - heightOf(footLayouts(refs)) - (if (feet.isEmpty()) sepGap else 0f)
            }
            var line = 0
            // Where the paragraph starts as the text flows, before a picture's band pushes its lines down: what its
            // floating pictures are placed from (Word's "from the paragraph"; found 2026-09-29 — pen strokes on a
            // keyboard picture were drawn a keyboard lower).
            var natural = y
            var floated = false
            while (line < layout.lineCount) {
                // As many lines as fit on this page (a line never splits; one taller than a page goes on a page of its own).
                val top = layout.topPt(line)
                y = clear(y, layout.bottomPt(line) - top)
                if (y + (layout.bottomPt(line) - top) > limitWith(line, line) && pieces.isNotEmpty()) { newPage(); if (line == 0) natural = y; continue }
                if (line == 0 && !floated) {
                    floated = true
                    paraTopOf?.let { if (i < it.size) it[i] = natural }
                    for (d in drops) floats += LaidPage.Floating(DropDrawable(d, page.left, y), page.left, y, 1f, 1f, behind = false)
                    for ((img, place) in floating) {
                        val f = floatAt(doc, styles, img, place, natural, page, pictureBytes)
                        floats += f
                        if (place.wrap != PictureWrap.TopBottom && place.wrap != PictureWrap.Square) continue
                        // Top and bottom: no text beside it — lines that would cross it go on below it. Square: the text
                        // beside it, unless there is too little room beside it for words (then as top and bottom, as Word).
                        val room = maxOf(f.x - SIDE_GAP - page.left, page.left + page.textWidth - f.x - f.w - SIDE_GAP)
                        val wall = Wall(img.key, f.x, f.y, f.x + f.w, f.y + f.h, place.wrap == PictureWrap.TopBottom || room < MIN_BESIDE)
                        if (place.fromV != "paragraph") found.getOrPut(pages.size) { ArrayList() } += wall
                        if (!walled.add(img.key)) continue
                        if (wall.band) bands += f.y to f.y + f.h else squares += wall
                    }
                    y = clear(y, layout.bottomPt(line) - top)
                }
                // Beside a Square picture: laid out again, the lines beside it narrower (again while that moves lines).
                if (line == 0 && squares.isNotEmpty()) {
                    var was: Pair<IntArray, IntArray>? = null
                    for (pass in 0 until 3) {
                        val ind = besides(layout, y) ?: break
                        if (was != null && ind.first.contentEquals(was.first) && ind.second.contentEquals(was.second)) break
                        was = ind
                        layout = paragraph(doc, p, look, styles, page, marker, pictureBytes, ArrayList(), ArrayList(), ArrayList(), ind).first
                    }
                }
                var last = line
                // Lines that fit and cross no band (a picture no text stands beside, further down).
                while (last + 1 < layout.lineCount && y + (layout.bottomPt(last + 1) - top) <= limitWith(line, last + 1)
                    && !crosses(y + layout.topPt(last + 1) - top, y + layout.bottomPt(last + 1) - top)) last++
                val atBand = last + 1 < layout.lineCount && crosses(y + layout.topPt(last + 1) - top, y + layout.bottomPt(last + 1) - top)
                pieces += LaidPage.Piece(layout, line, last, page.left - shift, y)
                // Line numbers: the text's lines (not a table's, a note's or a header's), in the margin left of them.
                section.lineNumbers?.takeIf { i < bodyCount }?.let { ln -> for (k in line..last) {
                    lineNo++
                    if (lineNo % ln.countBy == 0) lineNums += LaidPage.LineNumber(page.left - (ln.distance ?: 18f), y + layout.getLineBaseline(k) / U - top, lineNo.toString(), numberPaint)
                } }
                (line..last).flatMap { feetOf[it].orEmpty() }.takeIf { it.isNotEmpty() }?.let { refs ->
                    val ls = footLayouts(refs)
                    if (feet.isEmpty()) feetHeight += sepGap
                    feet.addAll(ls); feetHeight += heightOf(ls)
                }
                if (line == 0) { pageOf?.let { if (i < it.size) it[i] = number }; pageIndexOf?.let { if (i < it.size) it[i] = pages.size } }
                look.box?.let { box -> boxLines(box, look, page, y, y + layout.bottomPt(last) - top, line == 0, last == layout.lineCount - 1,
                    box == prevLook?.box && prevEndPage == pages.size, box == doc.paras.getOrNull(i + 1)?.takeIf { it.table == null }?.let { styles.look(it).box },
                    prevEnd, lines) }
                y += layout.bottomPt(last) - top
                line = last + 1
                if (line < layout.lineCount && !atBand) newPage() // at a band: the next lines go on below it (clear)
            }
            // Word spaces every line, the last one too; Android's layout leaves the last line unspaced.
            y += lastExtra
            prevEnd = y; prevEndPage = pages.size
            if (!same || i + 1 >= doc.paras.size) y += look.after
            prevLook = look; prevStyle = style
        }
        placeFeet()
        pages += LaidPage(frame, pieces, floats, section, number, firstOfSection, boxes, lines, lineNums)
        return pages.mapIndexed { i, laid -> withHeaderAndFooter(doc, styles, laid, i, pages.size, pictureBytes) }
    }

    /**
     * A paragraph's borders around its lines from [y0] to [y1] (one piece of it on a page). With the paragraph before
     * in the same box ([joinsAbove]) the sides go on up to where it ended and the line between them is drawn; the top
     * line only where the box starts, the bottom one where it ends.
     */
    private fun boxLines(box: ParaBox, look: ParaLook, page: DocPage, y0: Float, y1: Float, first: Boolean, last: Boolean,
        joinsAbove: Boolean, joinsBelow: Boolean, prevEnd: Float, out: MutableList<LaidPage.Line>) {
        // The box stands out from the text by its sides' spacing, only where it has a side (a line under alone spans the text).
        val x0 = page.left + minOf(look.left, look.left + look.firstLine) - (if (box.left != null) box.padLeft else 0f)
        val x1 = page.left + page.textWidth - look.right + (if (box.right != null) box.padRight else 0f)
        fun line(b: Border?, xa: Float, ya: Float, xb: Float, yb: Float) { if (b != null) out += LaidPage.Line(xa, ya, xb, yb, b.width, b.color) }
        val top = when {
            !first -> y0
            joinsAbove -> { line(box.between, x0, y0 - box.padTop, x1, y0 - box.padTop); prevEnd }
            else -> (y0 - box.padTop).also { line(box.top, x0, it, x1, it) }
        }
        val bottom = if (last && !joinsBelow) (y1 + box.padBottom).also { line(box.bottom, x0, it, x1, it) } else y1
        line(box.left, x0, top, x0, bottom)
        line(box.right, x1, top, x1, bottom)
    }

    /**
     * The same table layout used on a page, as one block for the editor, [width] pt wide: the editor lays a table out as
     * wide as its screen at its own text size, so a phone re-flows it rather than shrinking the letters.
     */
    private fun tableBlock(doc: OfficeDoc, table: DocTable, styles: DocStyles, width: Float, pictureBytes: (DocImage) -> ByteArray?,
        areas: MutableList<TableCellArea>? = null): Block {
        // On a screen wider than the page, the columns widen with it: the table keeps its share of the text's width.
        val rows = tableRows(doc, table, styles, styles.page, width, pictureBytes, areas, (width / styles.page.textWidth).coerceAtLeast(1f))
        val pieces = ArrayList<LaidPage.Piece>(); val boxes = ArrayList<LaidPage.Box>(); val lines = ArrayList<LaidPage.Line>()
        var y = 0f
        rows.forEach { (row, _) ->
            val placed = row.moved(0f, y)
            pieces += placed.pieces; boxes += placed.boxes; lines += placed.lines; y += row.height
        }
        return Block(y.coerceAtLeast(1f), pieces, boxes, lines)
    }

    /** A table's width (the page's text, or [most]: the editor's) and laid-out height, for its editor stand-in. */
    fun tableSize(doc: OfficeDoc, table: DocTable, styles: DocStyles, most: Float = Float.MAX_VALUE): Pair<Float, Float> {
        val width = if (most < Float.MAX_VALUE) most else styles.page.textWidth
        return width to tableBlock(doc, table, styles, width, { null }).height
    }


    /** The table laid out as page view lays it, [width] pt wide, as a page of its own the editor draws; [areas] learns its cells. */
    fun tableLaid(doc: OfficeDoc, table: DocTable, styles: DocStyles, width: Float, areas: MutableList<TableCellArea>?,
        pictureBytes: (DocImage) -> ByteArray?): LaidPage {
        val block = tableBlock(doc, table, styles, width, pictureBytes, areas)
        return LaidPage(DocPage(width = width, height = block.height, top = 0f, bottom = 0f, left = 0f, right = 0f), block.pieces,
            boxes = block.boxes, lines = block.lines)
    }

    /** The page's header (from the top edge, at its distance) and footer (ending at its distance from the bottom). */
    private fun withHeaderAndFooter(doc: OfficeDoc, styles: DocStyles, laid: LaidPage, index: Int, count: Int, pictureBytes: (DocImage) -> ByteArray?): LaidPage {
        val page = laid.page
        val section = laid.section
        fun which(map: Map<String, List<DocPara>>) = when {
            laid.firstOfSection && section.titlePage -> map["first"] ?: emptyList() // a title page has its own (none, if the file has none)
            styles.evenAndOdd && laid.number % 2 == 0 -> map["even"] ?: map["default"]
            else -> map["default"]
        }.orEmpty()
        val number = styles.number(laid.number, section.pageFormat)
        fun filled(paras: List<DocPara>) = paras.map { p -> p.copy(runs = p.runs.map { r ->
            if (PAGE_FIELD in r.text || PAGES_FIELD in r.text) r.copy(text = r.text.replace(PAGE_FIELD.toString(), number).replace(PAGES_FIELD.toString(), count.toString())) else r
        }) }
        val extraFloats = ArrayList<LaidPage.Floating>()
        val header = stack(doc, filled(which(section.headers)), styles, page, page.textWidth, pictureBytes, extraFloats, page.left, page.header).moved(page.left, page.header)
        // The footer ends at its distance from the page's bottom.
        val f = stack(doc, filled(which(section.footers)), styles, page, page.textWidth, pictureBytes, null, 0f, 0f)
        val footer = f.moved(page.left, page.height - page.footer - f.height)
        return LaidPage(page, header.pieces + footer.pieces + laid.pieces, laid.floats + extraFloats, section, laid.number, laid.firstOfSection,
            header.boxes + footer.boxes + laid.boxes, laid.lines + header.lines + footer.lines, laid.lineNumbers)
    }

    /**
     * Paragraphs (and tables) one under the other in [width], from 0,0 — a header, a footer, a table's cell. Floating
     * pictures go to [floats] placed from ([left], [top]) when given; else (in a cell) they are left out.
     */
    internal fun stack(doc: OfficeDoc, paras: List<DocPara>, styles: DocStyles, page: DocPage, width: Float, pictureBytes: (DocImage) -> ByteArray?,
        floats: MutableList<LaidPage.Floating>?, left: Float, top: Float, tableExtraWidth: Float = 0f, under: ParaLook? = null): Block {
        val area = page.copy(width = width, left = 0f, right = 0f)
        val pieces = ArrayList<LaidPage.Piece>(); val boxes = ArrayList<LaidPage.Box>(); val lines = ArrayList<LaidPage.Line>()
        var y = 0f
        paras.forEachIndexed { i, p ->
            p.table?.let { t ->
                // Word lets a nested table use the parent cell's right margin; ordinary text still stops before it.
                tableRows(doc, t, styles, page, width + tableExtraWidth, pictureBytes).forEach { (row, _) -> val b = row.moved(0f, y); pieces += b.pieces; boxes += b.boxes; lines += b.lines; y += row.height }
                return@forEachIndexed
            }
            val look = styles.look(p).let { l -> under?.let { u -> l.copy(bold = l.bold ?: u.bold, italic = l.italic ?: u.italic, color = l.color ?: u.color, sizePt = l.sizePt ?: u.sizePt) } ?: l }
            val marker = styles.marker(p)
            val floating = ArrayList<Pair<DocImage, PictureXml.Place>>()
            val (layout, shift, lastExtra) = paragraph(doc, p, look, styles, area, marker, pictureBytes, floating)
            if (i > 0) y += look.before
            pieces += LaidPage.Piece(layout, 0, layout.lineCount - 1, -shift, y)
            if (floats != null) floating.forEach { (img, place) -> floats += floatAt(doc, styles, img, place, top + y, page, pictureBytes) }
            y += layout.height / U
            y += lastExtra
            y += look.after
        }
        return Block(y, pieces, boxes, lines)
    }

    /**
     * A table's rows, each laid out from its top left (0 = the text's left edge), and whether it is a heading row.
     * A row is as tall as its tallest cell (or its set height); a cell merged down over rows makes the last of them
     * taller when it needs to.
     */
    /**
     * A cell whose text reads upwards or downwards: laid out as a line as long as the row is tall (a row of set height),
     * else as long as its longest line, and turned; the block is as tall as that line is long, as Word makes the row.
     */
    private fun turned(doc: OfficeDoc, paras: List<DocPara>, styles: DocStyles, page: DocPage, row: RowSpec?, cs: CellSpec, pictureBytes: (DocImage) -> ByteArray?): Block {
        fun lay(width: Float) = stack(doc, paras, styles, page, width, pictureBytes, null, 0f, 0f)
        val set = row?.height?.minus(cs.margin[1] + cs.margin[3])?.takeIf { it > 8f && row.exact }
        val length = set ?: lay(1000f).pieces.maxOfOrNull { p -> (p.firstLine..p.lastLine).maxOf { p.layout.getLineWidth(it) / U } + p.x }?.plus(1f)?.coerceIn(8f, 1000f) ?: 8f
        val flat = lay(length)
        // Upwards: the line's start at the cell's bottom; downwards: at its top, the first line on the right.
        val (tx, ty) = if (cs.turn < 0) 0f to length else flat.height to 0f
        return Block(length, flat.pieces.map { LaidPage.Piece(it.layout, it.firstLine, it.lastLine, it.x, it.y, cs.turn, tx, ty) }, emptyList(), emptyList(), flat.height)
    }

    private fun tableRows(doc: OfficeDoc, t: DocTable, styles: DocStyles, page: DocPage, width: Float, pictureBytes: (DocImage) -> ByteArray?,
        areas: MutableList<TableCellArea>? = null, grow: Float = 1f): List<Pair<Block, Boolean>> {
        val spec = styles.tableSpec(t)
        val count = spec.rows.maxOfOrNull { r -> r.cells.sumOf { it.span } } ?: 1
        val rawCols = spec.cols.takeIf { it.size >= count && it.sum() > 0f }?.map { it * grow } ?: List(count) { width / count }
        // The least each grid column can take: the longest word of the cells in it alone, and their margins.
        val least = if (rawCols.sum() <= width - spec.indent.coerceAtLeast(0f)) emptyList() else FloatArray(rawCols.size).also { least ->
            t.rows.forEachIndexed { ri, row ->
                var g = 0
                row.cells.forEachIndexed { ci, cell ->
                    val cs = spec.rows.getOrNull(ri)?.cells?.getOrNull(ci) ?: return@forEachIndexed
                    if (cs.span == 1 && g < least.size) least[g] = maxOf(least[g], longestWord(doc, cell.paras, styles) + cs.margin[0] + cs.margin[2] + 1f)
                    g += cs.span
                }
            }
        }.toList()
        val cols = fittedTableColumns(rawCols, width, spec.indent, least)
        val total = cols.sum()
        val x0 = spec.indent + when (spec.align) { DocAlign.Center -> (width - total) / 2; DocAlign.End -> width - total; else -> 0f }
        val colX = cols.runningFold(x0) { a, c -> a + c }
        // Each cell's place in the grid and its contents.
        class Laid(val row: Int, val cell: Int, val g: Int, val spec: CellSpec, val content: Block?)
        val cells = t.rows.mapIndexed { ri, row ->
            var g = 0
            row.cells.mapIndexedNotNull { ci, cell ->
                val cs = spec.rows.getOrNull(ri)?.cells?.getOrNull(ci) ?: return@mapIndexedNotNull null
                val w = cols.subList(g.coerceAtMost(cols.size), (g + cs.span).coerceAtMost(cols.size)).sum()
                val content = if (cs.merge == 'c') null else if (cs.turn != 0) turned(doc, cell.paras, styles, page, spec.rows.getOrNull(ri), cs, pictureBytes)
                    else stack(doc, cell.paras, styles, page, (w - cs.margin[0] - cs.margin[2]).coerceAtLeast(4f), pictureBytes, null, 0f, 0f, cs.margin[2], cs.text)
                Laid(ri, ci, g, cs, content).also { g += cs.span }
            }
        }
        val heights = FloatArray(t.rows.size) { ri ->
            val rs = spec.rows.getOrNull(ri)
            // A row holds its borders too: the one above it, and the one below the last row.
            val lines = (cells[ri].maxOfOrNull { it.spec.borders[0]?.width ?: 0f } ?: 0f) + if (ri == t.rows.size - 1) cells[ri].maxOfOrNull { it.spec.borders[2]?.width ?: 0f } ?: 0f else 0f
            val need = lines + (cells[ri].filter { it.spec.merge == 'n' }.maxOfOrNull { it.content!!.height + it.spec.margin[1] + it.spec.margin[3] } ?: 0f)
            when { rs?.height == null -> need; rs.exact -> rs.height; else -> maxOf(need, rs.height) }
        }
        // How many rows a cell that starts a vertical merge covers: the rows under it that go on in its column.
        fun down(c: Laid): Int { var k = 1; while (c.row + k < cells.size && cells[c.row + k].any { it.g == c.g && it.spec.merge == 'c' }) k++; return k }
        for (c in cells.flatten().filter { it.spec.merge == 'r' }) {
            val k = down(c)
            val have = (c.row until c.row + k).sumOf { heights[it].toDouble() }.toFloat()
            val need = c.content!!.height + c.spec.margin[1] + c.spec.margin[3]
            if (need > have) heights[c.row + k - 1] += need - have
        }
        var rowY = 0f
        return t.rows.indices.map { ri ->
            val pieces = ArrayList<LaidPage.Piece>(); val boxes = ArrayList<LaidPage.Box>(); val lines = ArrayList<LaidPage.Line>(); val edges = ArrayList<LaidPage.Line>()
            for (c in cells[ri]) {
                if (c.spec.merge == 'c') continue // drawn by the cell above that starts it
                val x = colX[c.g.coerceAtMost(colX.size - 1)]; val w = colX[(c.g + c.spec.span).coerceAtMost(colX.size - 1)] - x
                val h = if (c.spec.merge == 'r') (ri until ri + down(c)).sumOf { heights[it].toDouble() }.toFloat() else heights[ri]
                areas?.add(TableCellArea(ri, c.cell, x, rowY, w, h))
                c.spec.fill?.let { boxes += LaidPage.Box(x, 0f, w, h, it) }
                val content = c.content!!
                val free = h - content.height - c.spec.margin[1] - c.spec.margin[3]
                val m = c.spec.margin
                // Turned text is aligned across the cell, as Word turns its top with it: downwards, "top" is the right
                // side; upwards, the left. Its line starts at the top (downwards) or the bottom (upwards).
                val side = (w - m[0] - m[2] - content.across).coerceAtLeast(0f)
                val placed = when {
                    c.spec.turn > 0 -> content.moved(x + m[0] + when (c.spec.vAlign) { 'c' -> side / 2; 'b' -> 0f; else -> side }, m[1])
                    c.spec.turn < 0 -> content.moved(x + m[0] + when (c.spec.vAlign) { 'c' -> side / 2; 'b' -> side; else -> 0f }, (h - m[3] - content.height).coerceAtLeast(m[1]))
                    else -> content.moved(x + m[0], m[1] + when (c.spec.vAlign) { 'c' -> free / 2; 'b' -> free; else -> 0f }.coerceAtLeast(0f))
                }
                pieces += placed.pieces; boxes += placed.boxes; lines += placed.lines
                val b = c.spec.borders
                b[0]?.let { edges += LaidPage.Line(x, 0f, x + w, 0f, it.width, it.color) }
                b[1]?.let { edges += LaidPage.Line(x, 0f, x, h, it.width, it.color) }
                b[2]?.let { edges += LaidPage.Line(x, h, x + w, h, it.width, it.color) }
                b[3]?.let { edges += LaidPage.Line(x + w, 0f, x + w, h, it.width, it.color) }
            }
            // The cells' own lines under what is in them: a table in a cell reaching its edge keeps its line there
            // (the cell's line, and the next cell's, were drawn over it — a nested table lost its right side).
            (Block(heights[ri], pieces, boxes, edges + lines) to (spec.rows.getOrNull(ri)?.header == true)).also { rowY += heights[ri] }
        }
    }

    /** A floating picture's place on the page: from the page, the margin, or its paragraph (at [paraTop]). */
    private fun floatAt(doc: OfficeDoc, styles: DocStyles, img: DocImage, place: PictureXml.Place, paraTop: Float, page: DocPage, pictureBytes: (DocImage) -> ByteArray?): LaidPage.Floating {
        val (w, h) = img.widthPt to img.heightPt
        val x = when (place.alignH) {
            "center" -> (if (place.fromH == "page") page.width else page.textWidth) / 2 - w / 2 + (if (place.fromH == "page") 0f else page.left)
            "right", "outside" -> (if (place.fromH == "page") page.width else page.left + page.textWidth) - w
            "left", "inside" -> if (place.fromH == "page") 0f else page.left
            else -> (place.x ?: 0f) + if (place.fromH == "page") 0f else page.left
        }
        val y = when (place.alignV) {
            "center" -> (page.height - h) / 2
            "bottom" -> (if (place.fromV == "page") page.height else page.height - page.bottom) - h
            "top" -> if (place.fromV == "page") 0f else page.top
            // one moving with its text stays on its page, above the bottom margin, as LibreOffice keeps it (a worksheet's,
            // 848 pt below its paragraph, was drawn under the page)
            else -> ((place.y ?: 0f) + when (place.fromV) { "page" -> 0f; "margin" -> page.top; else -> paraTop })
                .let { y -> if (place.fromV == "page" || place.fromV == "margin") y else minOf(y, page.height - page.bottom - h).coerceAtLeast(0f) }
        }
        return LaidPage.Floating(pictureOf(doc, styles, img, pictureBytes).let { d -> PictureXml.video(img)?.let { OfficeVideo.Marked(d, it) } ?: d }, x, y, w, h, place.wrap == PictureWrap.Behind, img.key, PictureXml.zOf(img), Arrange.rotationOf(img))
    }

    /** A picture's bitmap as the file shows it: only the part its crop leaves (Word's a:srcRect, LibreOffice's fo:clip on its style). */
    fun croppedPicture(img: DocImage, bytes: ByteArray, max: Int): android.graphics.Bitmap? {
        val bmp = decodePicture(bytes, max) ?: return null
        return Slides.cropped(bmp, Slides.srcRect(img.raw) ?: img.look?.let { l -> Regex("""fo:clip="([^"]*)"""").findAll(l).lastOrNull() }?.let { Slides.clip(it.groupValues[1], bytes) })
    }

    /** What a floating picture, shape, text box, chart, equation or group draws (a grey box for what Writer cannot draw). */
    fun pictureOf(doc: OfficeDoc, styles: DocStyles, img: DocImage, pictureBytes: (DocImage) -> ByteArray?): android.graphics.drawable.Drawable =
        img.group?.let { parts -> GroupDrawable(img.widthPt, img.heightPt, parts.map { it to pictureOf(doc, styles, it.img, pictureBytes) }) }
            ?: img.math?.let { MathDrawable(it, doc.basePt) }
            ?: textBox(doc, styles, img, pictureBytes) ?: img.chart?.let { ChartDrawable(it) } ?: pictureBytes(img)?.let { b -> croppedPicture(img, b, 1600) }?.let { BitmapDrawable(null, it) } ?: PictureXml.video(img)?.let(OfficeVideo::stand) ?: ShapePicture.of(img) ?: ColorDrawable(0xFFE3E3E3.toInt())

    /** A text box's picture: its words laid out in it (Word's insets: 0.1" at the sides, 0.05" above and below). */
    fun textBox(doc: OfficeDoc, styles: DocStyles, img: DocImage, pictureBytes: (DocImage) -> ByteArray?): TextBoxDrawable? {
        val paras = img.box ?: return null
        // Its insets (bodyPr lIns/tIns, EMU; Word's 0.1" and 0.05" when not said).
        fun ins(n: String, or: Float) = img.raw?.let { Regex("""<(?:wps|a):bodyPr\b[^>]*\b$n="(\d+)"""").find(it)?.groupValues?.get(1)?.toFloatOrNull()?.div(12700f) } ?: or
        val inset = ins("lIns", 7.2f) to ins("tIns", 3.6f)
        val text = runCatching { stack(doc, paras, styles, DocPage(), (img.widthPt - 2 * inset.first).coerceAtLeast(8f), pictureBytes, null, 0f, 0f) }.getOrNull() ?: return null
        // Its words' place in it (Word's bodyPr anchor: SmartArt's and many text boxes' are centred).
        val anchor = img.raw?.let { Regex("""<(?:wps|a):bodyPr\b[^>]*\banchor="(\w+)"""").find(it)?.groupValues?.get(1) }.let { when (it) { "ctr" -> 'c'; "b" -> 'b'; else -> 't' } }
        // WordArt bent (Word's prstTxWarp, LibreOffice's fontwork): its words along the bend, in its first letters' look.
        val warp = TextWarp.of(img.raw)?.let { bend ->
            val r = paras.flatMap { it.runs }.firstOrNull { it.image == null && it.text.isNotBlank() } ?: DocRun("")
            val look = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = (0xFF000000.toInt() or (r.color ?: 0x4472C4)); typeface = OfficeFonts.styled(OfficeFonts.typeface(styles.fontName(r.font)), r.bold || r.sizePt == PictureXml.ART_PT, r.italic) }
            Triple(bend, paras.joinToString(" ") { it.text.trim() }, look)
        }
        return TextBoxDrawable(ShapePicture.of(img), text, inset, anchor, warp)
    }

    /** A drop cap's letters as the pages draw them: in points, [x] from the margin, [baseline] from the paragraph's top. */
    class DropLetters(val text: String, val paint: TextPaint, val x: Float, val baseline: Float)
    /** Drawn where it was placed (the float's box only says it is there). */
    private class DropDrawable(val d: DropLetters, val left: Float, val top: Float) : android.graphics.drawable.Drawable() {
        override fun draw(canvas: Canvas) { canvas.drawText(d.text, left + d.x, top + d.baseline, d.paint) }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
    /** The first lines of a paragraph beside a drop cap: indented by its width. */
    private class DropMargin(val px: Int, val lines: Int) : LeadingMarginSpan.LeadingMarginSpan2 {
        override fun getLeadingMargin(first: Boolean) = if (first) px else 0 // "first": the lines it counts
        override fun drawLeadingMargin(c: Canvas, p: android.graphics.Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int, text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?) {}
        override fun getLeadingMarginLineCount() = lines
    }

    private fun paragraph(doc: OfficeDoc, p: DocPara, look: ParaLook, styles: DocStyles, page: DocPage, marker: String?, pictureBytes: (DocImage) -> ByteArray?,
        floating: MutableList<Pair<DocImage, PictureXml.Place>>, drops: MutableList<DropLetters>? = null /* the body's: a drop cap drawn */,
        notesAt: MutableList<Pair<Int, DocRun>>? = null /* the body's: where each note's reference is in the text */,
        indents: Pair<IntArray, IntArray>? = null /* each line's left and right, beside a picture (layout units) */): Triple<StaticLayout, Float, Float> {
        val size = look.sizePt ?: doc.basePt
        val text = SpannableStringBuilder()
        // Indents: the list's own (a number hanging before the text), else the paragraph's.
        val (left, first) = if (p.list != null && !look.listIndented) styles.listIndent(p).let { (l, hang) ->
            // Writer's own "1.1.1." needs more room than "1." (as saved: OfficeFormats' hanging for a compound level).
            val wide = p.keep?.list == null && p.list.ordered && (doc.numbering?.getOrNull(p.list.level)?.second?.count { it == '%' } ?: 0) > 1
            l to l - if (wide) 18f + 7.2f * p.list.level else hang
        } else look.left to look.left + look.firstLine
        if (marker != null) {
            text.append(marker).append('\t')
            // The number or bullet in the look of the paragraph's first letter (Word: its paragraph mark's).
            p.runs.firstOrNull { it.image == null && it.text.isNotBlank() }?.let { r ->
                fun mark(s: Any) = text.setSpan(s, 0, marker.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (r.bold) mark(StyleSpan(Typeface.BOLD))
                r.sizePt?.let { mark(AbsoluteSizeSpan(Math.round(it * U).coerceAtLeast(1))) }
                OfficeFonts.typeface(styles.fontName(r.font))?.let { mark(TypefaceSpan(OfficeFonts.styled(it, r.bold, r.italic))) }
                r.color?.let { mark(ForegroundColorSpan(0xFF000000.toInt() or it)) }
            }
            // its own look (a slide's bullet): drawn in its colour, size and face, the line as tall as its words make it
            styles.markerLook(p)?.let { m -> text.setSpan(MarkerSpan(m.color, m.size, OfficeFonts.typeface(m.font)), 0, marker.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        for (r in OfficeFormats.tidy(p.runs)) {
            if (r.image == null && LetterLooks.of(r.font).hidden) continue // hidden text: not on the page, as in Word
            // A floating picture takes no room in the line: it is placed with the paragraph.
            r.image?.let { img -> PictureXml.placeOf(img)?.let { floating += img to it } }?.let { continue }
            val start = text.length
            text.append(r.text.replace(LINE_BREAK, '\n'))
            val end = text.length
            if (r.note != null) notesAt?.add(start to r)
            fun span(s: Any) = text.setSpan(s, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            r.image?.let { img ->
                val width = page.textWidth - left.coerceAtLeast(0f)
                val fit = minOf(width / img.widthPt, (page.height - page.top - page.bottom - 20f) / img.heightPt, 1f)
                val w = Math.round(img.widthPt * fit * U).coerceAtLeast(1); val h = Math.round(img.heightPt * fit * U).coerceAtLeast(1)
                // In a line the layout counts in twentieths of a point: a shape's outline is given in them too.
                val drawable = img.math?.let { MathDrawable(it, doc.basePt) } ?: img.group?.let { pictureOf(doc, styles, img, pictureBytes) } ?: (textBox(doc, styles, img, pictureBytes) ?: img.chart?.let { ChartDrawable(it) })?.let { InLineDrawable(it, U) } ?: pictureBytes(img)?.let { b -> croppedPicture(img, b, 1600) }?.let { BitmapDrawable(null, it) }
                    ?: PictureXml.video(img)?.let(OfficeVideo::stand) ?: ShapePicture.of(img)?.let { it.withLine(it.lineWidth * U) } ?: ColorDrawable(0xFFE3E3E3.toInt())
                drawable.setBounds(0, 0, w, h)
                span(ImageSpan(PictureXml.video(img)?.let { OfficeVideo.Marked(drawable, it).apply { setBounds(0, 0, w, h) } } ?: drawable, ImageSpan.ALIGN_BOTTOM))
                continue
            }
            when { // the style's bold and italic are the paint's
                r.bold && r.italic -> span(StyleSpan(Typeface.BOLD_ITALIC))
                r.bold -> span(StyleSpan(Typeface.BOLD))
                r.italic -> span(StyleSpan(Typeface.ITALIC))
            }
            val letters = LetterLooks.of(r.font)
            // a link to a page out there in Word's blue and underlined; one inside the document (#: the contents') as plain words, as Word shows it
            val web = r.link != null && !r.link.startsWith("#")
            if (letters.doubleUnder) span(LetterLooks.SecondLine(false)) else if (r.underline || web) span(UnderlineSpan())
            if (letters.doubleStrike) span(LetterLooks.SecondLine(true)) else if (r.strike) span(StrikethroughSpan())
            // Capitals: the letters shown as capitals (the file keeps them as typed); small ones a size smaller.
            if (letters.caps || letters.smallCaps) {
                for (k in start until end) {
                    val c = text[k]
                    if (c.isLowerCase()) { text.replace(k, k + 1, c.uppercaseChar().toString()); if (letters.smallCaps && !letters.caps) text.setSpan(android.text.style.RelativeSizeSpan(0.8f), k, k + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                }
            }
            r.sizePt?.let { span(AbsoluteSizeSpan(Math.round(it * U).coerceAtLeast(1))) }
            if (r.script != 0) { span(if (r.script > 0) SuperscriptSpan() else SubscriptSpan()); span(RelativeSizeSpan(0.65f)) }
            // Character spacing, after the size is set (only a run that has some: the rest laid out as before)
            if (letters.spacing != 0f) span(LetterLooks.Spaced(letters.spacing, U))
            (r.color ?: if (web) 0x0563C1 else null)?.let { span(ForegroundColorSpan(0xFF000000.toInt() or it)) }
            r.highlight?.let { span(BackgroundColorSpan(0xFF000000.toInt() or it)) }
            // Tracked changes, as Word shows all markup: inserted underlined, deleted struck through, in the author's colour.
            r.change?.let { c -> span(ForegroundColorSpan(0xFF000000.toInt() or TrackChanges.color(c.author))); span(if (c.deleted) StrikethroughSpan() else UnderlineSpan()) }
            OfficeFonts.typeface(styles.fontName(r.font))?.let { span(TypefaceSpan(OfficeFonts.styled(it, r.bold || look.bold == true, r.italic || look.italic == true))) }
            // WordArt's effects (outline, shadow, glow): each word drawn with them, the line still breaking between words.
            TextEffects.of(r.font).takeIf { it.any }?.let { fx ->
                val fill = 0xFF000000.toInt() or (r.color ?: look.color ?: 0)
                Regex("""\S+""").findAll(text.substring(start, end)).forEach { m -> text.setSpan(EffectSpan(fx, fill, U), start + m.range.first, start + m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            }
        }
        // An empty paragraph is still a line — as tall as its paragraph mark's size, as Word and LibreOffice make it (the
        // 602 cover's 17 pt empty lines came out 12 pt high; the text below rode up). Floating pictures take no room.
        val markPt = if (text.isEmpty()) OfficeFormats.markSize(p) else null
        if (text.isEmpty()) text.append(" ")
        fun whole(s: Any) = text.setSpan(s, 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        // A paragraph may start left of the margin (a negative indent): it is laid out that much wider and drawn that much
        // further left (the layout has nothing left of 0).
        val shift = -minOf(0f, first, left)
        if (left + shift > 0f || first + shift > 0f) whole(LeadingMarginSpan.Standard(Math.round((first + shift) * U), Math.round((left + shift) * U)))
        look.lineExact?.let { exact -> if (!look.lineAtLeast || exact > size * 1.17f) whole(LineHeightSpan.Standard(Math.round(exact * U).coerceAtLeast(1))) }
        // Laid out in points and drawn scaled: linear, sub-pixel text, or letters snap to whole points and space unevenly.
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.LINEAR_TEXT_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG).apply {
            color = look.color?.let { 0xFF000000.toInt() or it } ?: Color.BLACK
            textSize = (markPt ?: size) * U
            typeface = OfficeFonts.styled(OfficeFonts.typeface(look.font), look.bold == true, look.italic == true)
            if (!doc.ligatures) fontFeatureSettings = "'liga' 0, 'clig' 0"
        }
        val width = Math.round((page.textWidth - look.right + shift) * U).coerceAtLeast(40)
        val aligned = p.align.takeIf { it != DocAlign.Start || p.keep?.parts?.any { k -> k.first == "jc" } == true } ?: look.align ?: DocAlign.Start // its own (left said in the file too), else its style's
        val align = when (aligned) { DocAlign.Center -> Layout.Alignment.ALIGN_CENTER; DocAlign.End -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }
        // Word's single line is the font's height and its line gap; Android's leaves the gap out (rows came out 4% short).
        // A picture alone in its paragraph keeps its own height, as LibreOffice lays it (asked 17 Oct: 1.15 put a sixth of the picture under it).
        val multiple = if (look.lineExact == null && !text.all { it == '\uFFFC' || it == '\n' }) look.lineMultiple.coerceIn(0.5f, 5f) else 1f
        // The line gap of the font most of the text is in (a paragraph in Calibri may be all Times, letter by letter).
        val main = p.runs.filter { it.image == null }.groupBy { it.font to it.sizePt }.maxByOrNull { (_, rs) -> rs.sumOf { it.text.length } }?.value?.first()
        val gapPaint = TextPaint(paint).apply {
            main?.let { r -> OfficeFonts.typeface(styles.fontName(r.font))?.let { typeface = it }; r.sizePt?.let { textSize = it * U } }
        }
        val gap = if (look.lineExact == null) gapPaint.fontMetrics.leading * multiple else 0f
        // Layout › Hyphenation: words broken at the end of a line, by the paragraph's language (Greek or English).
        if (styles.hyphenate) paint.textLocale = if (text.count { it in '\u0370'..'\u03FF' || it in '\u1F00'..'\u1FFF' } * 2 > text.count { it.isLetter() }) java.util.Locale("el", "GR") else java.util.Locale.US
        fun build() = StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setAlignment(align)
            .setHyphenationFrequency(if (styles.hyphenate) Layout.HYPHENATION_FREQUENCY_FULL else Layout.HYPHENATION_FREQUENCY_NONE)
            .apply { if (styles.hyphenate) setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY) } // the simple (Word-like) one does not hyphenate
            .setLineSpacing(gap, multiple).setIncludePad(false)
            .setJustificationMode(if (aligned == DocAlign.Justify) Layout.JUSTIFICATION_MODE_INTER_WORD else Layout.JUSTIFICATION_MODE_NONE)
            .apply { indents?.let { (l, r) -> setIndents(l, r) } }
            .build()
        // Tabs, left to right (Android's layout knows only left stops): each goes to the next stop after where it
        // starts — the paragraph's own, a list's text position, then every half inch — the text after it lined up to
        // the stop's side (a table of contents: page numbers right-aligned, dots before them).
        val stops = (look.tabs + if (first < left) listOf(TabStop(left, 'l')) else emptyList()).sortedBy { it.pos }
        for (k in text.indices.filter { text[it] == '\t' }) {
            if (marker != null && k == marker.length && styles.markerHangs(p)) {
                val mark = Layout.getDesiredWidth(android.text.SpannableStringBuilder(text, 0, k).also { b -> b.getSpans(0, b.length, android.text.style.ParagraphStyle::class.java).forEach(b::removeSpan) }, paint) / U
                text.setSpan(TabSpan(maxOf(left - first - mark, size * 0.25f) * U, null), k, k + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                continue
            }
            val x = build().getPrimaryHorizontal(k) / U - shift // in points from the margin, as tab stops are
            // A stop the text reaches exactly still counts (a number as wide as its hanging indent).
            val end = (k + 1 until text.length).firstOrNull { text[it] == '\t' || text[it] == '\n' } ?: text.length
            // the words after it with their letters' looks only: Android counts the paragraph's indent (a LeadingMarginSpan) into
            // any width it measures, and an indented contents line's number fell short of the stop
            val piece = android.text.SpannableStringBuilder(text, k + 1, end).also { b -> b.getSpans(0, b.length, android.text.style.ParagraphStyle::class.java).forEach(b::removeSpan) }
            val after = Layout.getDesiredWidth(piece, paint) / U
            val (stop, gap) = tabGap(x, after, stops)
            text.setSpan(TabSpan(gap.coerceAtLeast(0f) * U, stop.leader), k, k + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        var laid = build()
        // A drop cap: its letters out of the first line, as tall as the lines they drop through (their capitals from
        // the first line's top to the last one's baseline), the first lines indented by their width.
        DropCap.of(p.keep)?.takeIf { drops != null }?.let { drop ->
            val from = marker?.let { it.length + 1 } ?: 0
            val n = drop.length.coerceAtMost(text.length - from)
            val letters = if (n > 0) text.substring(from, from + n) else ""
            if (letters.isBlank() || '\n' in letters || text.getSpans(from, from + n, ImageSpan::class.java).isNotEmpty()) return@let
            val r0 = p.runs.firstOrNull { it.image == null && it.text.isNotEmpty() }
            val letterPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG).apply {
                typeface = OfficeFonts.styled(r0?.let { OfficeFonts.typeface(styles.fontName(it.font)) } ?: OfficeFonts.typeface(look.font), r0?.bold == true || look.bold == true, r0?.italic == true || look.italic == true)
                color = (r0?.color ?: look.color)?.let { 0xFF000000.toInt() or it } ?: Color.BLACK
                textSize = 100f
            }
            val bounds = android.graphics.Rect(); letterPaint.getTextBounds("H", 0, 1, bounds)
            val capRatio = (bounds.height() / 100f).coerceAtLeast(0.3f)
            val base0 = laid.getLineBaseline(0) / U
            // a line's step, from the first two — no more than the first line's own height (a picture in the second made the letter huge, 17 Oct)
            val lineStep = if (laid.lineCount > 1) minOf((laid.getLineBaseline(1) - laid.getLineBaseline(0)) / U, laid.bottomPt(0) - laid.topPt(0)) else laid.bottomPt(0) - laid.topPt(0)
            val baseN = base0 + (drop.lines - 1) * lineStep
            val capTop = base0 - (r0?.sizePt ?: size) * capRatio
            letterPaint.textSize = ((baseN - capTop) / capRatio).coerceAtLeast(size)
            val w = letterPaint.measureText(letters)
            text.delete(from, from + n)
            if (!drop.margin) text.setSpan(DropMargin(Math.round((w + drop.distance) * U), drop.lines), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            laid = build()
            drops!! += DropLetters(letters, letterPaint, (if (drop.margin) -w - drop.distance else 0f) + first, baseN - laid.topPt(0))
        }
        // Word spaces every line, the last one too; Android's layout leaves the last line unspaced.
        val lastExtra = if (look.lineExact == null && laid.lineCount > 0) laid.lineCount.minus(1).let { last -> (multiple - 1f) * (laid.bottomPt(last) - laid.topPt(last)) + gap / U } else 0f
        return Triple(laid, shift, lastExtra)
    }

}

/**
 * The fonts Writer brings (assets/fonts, SIL Open Font License): free fonts made with the letter widths of Word's own —
 * Liberation Serif and Sans for Times New Roman and Arial, Carlito for Calibri, Caladea for Cambria — so lines and
 * pages break where Word's and LibreOffice's do (2026-09-28: with the phone's own fonts a 20-page paper took 24).
 */
internal object OfficeFonts {
    @Volatile var assets: android.content.res.AssetManager? = null
    private val families = HashMap<String, Typeface?>()

    private fun load(name: String, fallback: String): Typeface? = synchronized(families) {
        families.getOrPut(name) {
            val a = assets ?: return null // not known yet: asked again later, not remembered as missing
            runCatching {
                fun font(style: String, weight: Int, italic: Boolean) = android.graphics.fonts.Font.Builder(a, "fonts/$name-$style.ttf").setWeight(weight)
                    .setSlant(if (italic) android.graphics.fonts.FontStyle.FONT_SLANT_ITALIC else android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT).build()
                val family = android.graphics.fonts.FontFamily.Builder(font("Regular", 400, false)).addFont(font("Bold", 700, false))
                    .addFont(font("Italic", 400, true)).addFont(font("BoldItalic", 700, true)).build()
                Typeface.CustomFallbackBuilder(family).setSystemFallback(fallback).build() // letters it lacks: the phone's
            }.getOrNull()
        }
    }

    /**
     * The phone's font families (asked 17 Oct: Android's and its maker's — Noto, MiSans, Dancing Script…) by the name in
     * their files (Android lists the files, not their names), each with its faces, its name as written.
     */
    private val phone: Map<String, Pair<String, List<android.graphics.fonts.Font>>> by lazy {
        runCatching { android.graphics.fonts.SystemFonts.getAvailableFonts().mapNotNull { f -> f.file?.let { runCatching { familyName(it, f.ttcIndex) }.getOrNull() }?.let { it to f } }
            .groupBy({ it.first.lowercase() }, { it }).mapValues { (_, l) -> l.first().first to l.map { it.second }.distinctBy { it.style.weight to it.style.slant } } }.getOrElse { emptyMap() }
    }
    private val phoneFaces = HashMap<String, Typeface?>()
    /** A family of the phone's by its name, its faces together (bold and italic pick theirs); the phone's other fonts behind it. */
    private fun phoneTypeface(n: String): Typeface? = synchronized(phoneFaces) {
        phoneFaces.getOrPut(n) {
            val faces = phone[n]?.second ?: return@getOrPut null
            runCatching { Typeface.CustomFallbackBuilder(android.graphics.fonts.FontFamily.Builder(faces[0]).apply { faces.drop(1).forEach { addFont(it) } }.build())
                .setSystemFallback("sans-serif").build() }.getOrNull()
        }
    }
    /**
     * The phone's families for the font picker: those whose own letters include Latin ones (not fonts for other scripts
     * alone, nor emoji or clocks; Noto's "UI" twins left out), by name.
     */
    fun phoneFamilies(): List<String> = phone.values.filter { (name, faces) ->
        !Regex("emoji|clock|symbol|icon| UI$", RegexOption.IGNORE_CASE).containsMatchIn(name) &&
            faces.first().let { f -> f.file?.let { runCatching { covers(it, f.ttcIndex, 'a'.code) }.getOrNull() } } == true
    }.map { it.first }.sortedBy { it.lowercase() }

    /** A font file's tables: tag → (offset, length), the [index]th font of a collection; [read] its numbers. */
    private fun tables(f: java.io.RandomAccessFile, index: Int): Map<String, Long> {
        f.seek(0); val ttc = f.readInt() == 0x74746366 // "ttcf"
        val base = if (ttc) { f.seek(12L + 4 * index); f.readInt().toLong() and 0xFFFFFFFFL } else 0L
        f.seek(base + 4); val n = f.readUnsignedShort()
        return (0 until n).associate { k -> f.seek(base + 12 + 16L * k); val tag = ByteArray(4).also(f::readFully); f.skipBytes(4); String(tag, Charsets.ISO_8859_1) to (f.readInt().toLong() and 0xFFFFFFFFL) }
    }

    /** Whether a font file's own letters (its cmap: Unicode format 4 or 12) include [code]. */
    internal fun covers(file: java.io.File, index: Int, code: Int): Boolean = java.io.RandomAccessFile(file, "r").use { f ->
        fun u16(at: Long): Int { f.seek(at); return f.readUnsignedShort() }
        fun u32(at: Long): Long { f.seek(at); return f.readInt().toLong() and 0xFFFFFFFFL }
        val cmap = tables(f, index)["cmap"] ?: return false
        val subs = (0 until u16(cmap + 2)).map { cmap + 4 + 8L * it }.filter { r -> u16(r).let { it == 0 || it == 3 } }.map { cmap + u32(it + 4) }
        subs.any { at -> when (u16(at)) {
            4 -> { val seg = u16(at + 6) / 2
                (0 until seg).any { i -> val end = u16(at + 14 + 2L * i); val start = u16(at + 16 + 2L * seg + 2L * i)
                    code in start..end && end != 0xFFFF } }
            12 -> (0 until u32(at + 12)).any { g -> code.toLong() in u32(at + 16 + 12 * g)..u32(at + 20 + 12 * g) }
            else -> false
        } }
    }

    /** A font file's family name: its name table's typographic family (else its family), the [index]th font of a collection. */
    internal fun familyName(file: java.io.File, index: Int = 0): String? = java.io.RandomAccessFile(file, "r").use { f ->
        fun u16(at: Long): Int { f.seek(at); return f.readUnsignedShort() }
        fun u32(at: Long): Long { f.seek(at); return f.readInt().toLong() and 0xFFFFFFFFL }
        val base = if (u32(0) == 0x74746366L) u32(12L + 4 * index) else 0L // "ttcf": where this font starts
        val name = (0 until u16(base + 4)).map { base + 12 + 16L * it }.firstOrNull { u32(it) == 0x6E616D65L } ?: return null // "name"
        val at = u32(name + 8); val strings = at + u16(at + 4)
        // (platform, encoding, language, name id, length, offset): the typographic family (16) before the family (1), Windows' English first
        val best = (0 until u16(at + 2)).map { at + 6 + 12L * it }.map { r -> IntArray(6) { k -> u16(r + 2L * k) } }.filter { it[3] == 16 || it[3] == 1 }
            .minByOrNull { (if (it[3] == 16) 0 else 10) + (if (it[0] == 3 && it[2] == 0x409) 0 else if (it[0] == 3 || it[0] == 0) 1 else 2) } ?: return null
        val bytes = ByteArray(best[4]); f.seek(strings + best[5]); f.readFully(bytes)
        String(bytes, if (best[0] == 1) Charsets.ISO_8859_1 else Charsets.UTF_16BE).trim().ifEmpty { null }
    }

    /**
     * The typeface for a document's font name; null: the phone's default. The phone's own font of that name when it has
     * one (asked 17 Oct), else Word's fonts' twins (their letter widths keep lines where Word breaks them), else a guess.
     */
    fun typeface(name: String?): Typeface? {
        val n = name?.lowercase()?.trim() ?: return null
        phoneTypeface(n)?.let { return it }
        return when {
            "narrow" in n || "condensed" in n -> Typeface.create("sans-serif-condensed", Typeface.NORMAL) // Arial Narrow: no metric twin bundled
            "times" in n || n == "liberation serif" -> load("LiberationSerif", "serif")
            "arial" in n || "helvetica" in n || n == "liberation sans" -> load("LiberationSans", "sans-serif")
            "calibri" in n || "carlito" in n -> load("Carlito", "sans-serif")
            "cambria" in n || "caladea" in n -> load("Caladea", "serif")
            listOf("mono", "courier", "consol").any { it in n } -> Typeface.MONOSPACE
            listOf("serif", "georgia", "garamond", "book", "palatino", "roman").any { it in n } && "sans" !in n -> load("LiberationSerif", "serif")
            else -> null
        }
    }

    private val composeFamilies = HashMap<String, androidx.compose.ui.text.font.FontFamily?>()

    /**
     * The editor's family for a document's font name: its four faces as fonts of their own, so the letters' Bold and
     * Italic pick theirs (a family made of one Typeface was drawn upright and regular whatever the letters said, 14 Oct).
     */
    fun family(name: String?): androidx.compose.ui.text.font.FontFamily? {
        val n = name?.lowercase()?.trim() ?: return null
        // the phone's own font of that name: its faces from their files (a collection's other fonts left to the phone)
        phone[n]?.second?.filter { it.file != null && it.ttcIndex == 0 }?.takeIf { it.isNotEmpty() }?.let { faces ->
            synchronized(composeFamilies) { composeFamilies.getOrPut("phone $n") { runCatching { androidx.compose.ui.text.font.FontFamily(faces.map { f ->
                androidx.compose.ui.text.font.Font(f.file!!, androidx.compose.ui.text.font.FontWeight(f.style.weight.coerceIn(1, 1000)),
                    if (f.style.slant == android.graphics.fonts.FontStyle.FONT_SLANT_ITALIC) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal) }) }.getOrNull() } }?.let { return it }
        }
        val file = when {
            "times" in n || n == "liberation serif" -> "LiberationSerif"
            "arial" in n || "helvetica" in n || n == "liberation sans" -> "LiberationSans"
            "calibri" in n || "carlito" in n -> "Carlito"
            "cambria" in n || "caladea" in n -> "Caladea"
            listOf("mono", "courier", "consol").any { it in n } -> return androidx.compose.ui.text.font.FontFamily.Monospace
            listOf("serif", "georgia", "garamond", "book", "palatino", "roman").any { it in n } && "sans" !in n -> "LiberationSerif"
            else -> return null
        }
        val a = assets ?: return null
        return synchronized(composeFamilies) {
            composeFamilies.getOrPut(file) {
                runCatching {
                    fun f(style: String, w: androidx.compose.ui.text.font.FontWeight, italic: Boolean) = androidx.compose.ui.text.font.Font("fonts/$file-$style.ttf", a, w,
                        if (italic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal)
                    val w4 = androidx.compose.ui.text.font.FontWeight.Normal; val w7 = androidx.compose.ui.text.font.FontWeight.Bold
                    androidx.compose.ui.text.font.FontFamily(f("Regular", w4, false), f("Bold", w7, false), f("Italic", w4, true), f("BoldItalic", w7, true))
                }.getOrNull()
            }
        }
    }

    fun styled(tf: Typeface?, bold: Boolean, italic: Boolean): Typeface =
        Typeface.create(tf ?: Typeface.DEFAULT, if (bold) 700 else 400, italic)
}

/**
 * An outline's dashes as Word and PowerPoint name them (DrawingML's prstDash) and as LibreOffice converts each (its
 * draw:stroke-dash: dashes, their length, dots after them, their length, the gap — in line widths; checked with
 * LibreOffice 26.2's conversions, 17 Oct). Shape options offers [CHOICES]; every name is read and drawn.
 */
internal object LineDash {
    private class Dash(val odf: String, val dots1: Int, val len1: Int, val dots2: Int, val len2: Int, val gap: Int)
    private val ALL = linkedMapOf(
        "sysDot" to Dash("Dot", 1, 1, 0, 0, 1), "sysDash" to Dash("Dash", 1, 3, 0, 0, 1), "dash" to Dash("Long_20_Dash", 1, 4, 0, 0, 3),
        "dashDot" to Dash("Long_20_Dash_20_Dot", 1, 4, 1, 1, 3), "lgDash" to Dash("Double_20_Dash", 1, 8, 0, 0, 3),
        "lgDashDot" to Dash("Double_20_Dash_20_Dot", 1, 8, 1, 1, 3), "lgDashDotDot" to Dash("Double_20_Dash_20_Dot_20_Dot", 1, 8, 2, 1, 3),
        "dot" to Dash("Long_20_Dot", 1, 1, 0, 0, 3), "sysDashDot" to Dash("Dash_20_Dot", 1, 3, 1, 1, 1), "sysDashDotDot" to Dash("Dash_20_Dot_20_Dot", 1, 3, 2, 1, 1))
    /** Word's list of dashes (Format Shape › Line › Dash type): its name and the shown word. */
    val CHOICES = listOf(null to "Solid", "sysDot" to "Round dot", "sysDash" to "Square dot", "dash" to "Dash", "dashDot" to "Dash dot",
        "lgDash" to "Long dash", "lgDashDot" to "Long dash dot", "lgDashDotDot" to "Long dash dot dot")
    /** [CHOICES]' words as shown, by the dash's name (null: Solid). */
    @androidx.annotation.StringRes fun labelRes(name: String?): Int = when (name) {
        "sysDot" -> R.string.office_dash_round_dot; "sysDash" -> R.string.office_dash_square_dot; "dash" -> R.string.office_dash_dash
        "dashDot" -> R.string.office_dash_dash_dot; "lgDash" -> R.string.office_dash_long_dash; "lgDashDot" -> R.string.office_dash_long_dash_dot
        "lgDashDotDot" -> R.string.office_dash_long_dash_dot_dot; else -> R.string.office_dash_solid
    }
    /** Android's on/off lengths for [name] on a line [width] pt wide; null: solid. */
    fun intervals(name: String, width: Float): FloatArray? {
        val d = ALL[name] ?: return null
        val w = width.coerceAtLeast(0.5f)
        return (List(d.dots1) { listOf(d.len1 * w, d.gap * w) } + List(d.dots2) { listOf(d.len2 * w, d.gap * w) }).flatten().toFloatArray()
    }
    /** LibreOffice's draw:stroke-dash name for [name] (what a style's draw:stroke-dash says). */
    fun odfName(name: String) = ALL[name]?.odf
    /** LibreOffice's draw:stroke-dash element for [name], to go into styles.xml's office:styles. */
    fun odfStyle(name: String): String? = ALL[name]?.let { d -> """<draw:stroke-dash draw:name="${d.odf}" draw:display-name="${d.odf.replace("_20_", " ")}" draw:style="rect" draw:dots1="${d.dots1}" draw:dots1-length="${d.len1 * 100}%"""" +
        (if (d.dots2 > 0) """ draw:dots2="${d.dots2}" draw:dots2-length="${d.len2 * 100}%"""" else "") + """ draw:distance="${d.gap * 100}%"/>""" }
    /** A LibreOffice dash by its name (its own names for these; any other a plain dash). */
    fun fromOdf(odf: String): String = ALL.entries.firstOrNull { it.value.odf == odf }?.key ?: "dash"
    /** Adds the draw:stroke-dash definitions [names] need to an ODF styles.xml (those it has kept). */
    fun intoStyles(styles: String, names: Collection<String>): String {
        val add = names.mapNotNull { n -> odfName(n)?.takeIf { "draw:name=\"$it\"" !in styles }?.let { odfStyle(n) } }.distinct().joinToString("")
        return if (add.isEmpty() || "</office:styles>" !in styles) styles else styles.replace("</office:styles>", "$add</office:styles>")
    }
}

/**
 * A drawn shape (Word's wps:wsp, LibreOffice's draw:custom-shape): its outline path in a w×h box, with its fill (and
 * see-through) and outline as the file sets them (found 2026-09-29: circles around keys on a keyboard picture were grey
 * boxes; Insert › Shapes makes them). A shape of another kind is still a grey box.
 */
internal class ShapePicture(val geometry: String, val fill: Int?, val line: Int?, val lineWidth: Float,
    /** Its own geometry (a LibreOffice shape's enhanced path); else Word's preset [geometry]'s. */
    val custom: ShapeGeometry? = null, /** Word's adjustments (avLst), by name. */ val adjust: Map<String, Double> = emptyMap(),
    /** a line's arrowheads: 1 at its start, 2 at its end (Word's headEnd, tailEnd) */ val ends: Int = 0,
    /** its outline's dashes ([LineDash]'s names, DrawingML's prstDash; null: solid) and a shadow under it (PowerPoint's "Offset: bottom right") */
    val dash: String? = null, val shadow: Boolean = false) : android.graphics.drawable.Drawable() {
    fun withLine(width: Float) = ShapePicture(geometry, fill, line, width, custom, adjust, ends, dash, shadow)
    /** Its outline's dashes for a Paint (null: solid). */
    fun dashes() = dash?.let { LineDash.intervals(it, lineWidth) }?.let { android.graphics.DashPathEffect(it, 0f) }
    /** Its shadow under [path]: the shape 3 pt down and right, 40 % black, softened (PowerPoint's and Word's "Offset: bottom right"). */
    fun shadowOf(canvas: Canvas, path: android.graphics.Path, filled: Boolean) {
        if (!shadow) return
        val shade = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000
            maskFilter = android.graphics.BlurMaskFilter(2f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            style = if (filled) android.graphics.Paint.Style.FILL else android.graphics.Paint.Style.STROKE; strokeWidth = lineWidth }
        canvas.drawPath(android.graphics.Path(path).apply { offset(2.12f, 2.12f) }, shade)
    }
    fun drawAt(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.strokeJoin = android.graphics.Paint.Join.ROUND
        val dashes = dashes()
        fun shadowOf(path: android.graphics.Path, filled: Boolean) = shadowOf(canvas, path, filled)
        // Drawn from its geometry (Word's 187 presets, LibreOffice's paths): each sub-path filled (shaded) and stroked as it says.
        val geo = custom ?: ShapeGeometry.presets[geometry]
        if (geo != null && geometry !in LINES) {
            for (sub in geo.paths(w, h, adjust)) {
                val path = android.graphics.Path()
                for (op in sub.ops) when (op) {
                    is ShapeGeometry.Op.Move -> path.moveTo(x + op.x, y + op.y)
                    is ShapeGeometry.Op.Line -> path.lineTo(x + op.x, y + op.y)
                    is ShapeGeometry.Op.Cubic -> path.cubicTo(x + op.x1, y + op.y1, x + op.x2, y + op.y2, x + op.x, y + op.y)
                    is ShapeGeometry.Op.Quad -> path.quadTo(x + op.x1, y + op.y1, x + op.x, y + op.y)
                    ShapeGeometry.Op.Close -> path.close()
                }
                shadowOf(path, sub.fill && fill != null)
                if (sub.fill) fill?.let { paint.style = android.graphics.Paint.Style.FILL; paint.pathEffect = null; paint.color = shaded(it, sub.shade); canvas.drawPath(path, paint) }
                if (sub.stroke) line?.let { paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = lineWidth; paint.pathEffect = dashes; paint.color = it; canvas.drawPath(path, paint) }
            }
            return
        }
        val path = path(geometry, w, h).apply { offset(x, y) }
        shadowOf(path, geometry !in LINES && fill != null)
        if (geometry !in LINES) fill?.let { paint.style = android.graphics.Paint.Style.FILL; paint.color = it; canvas.drawPath(path, paint) }
        line?.let { paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = lineWidth; paint.pathEffect = dashes; paint.color = it; canvas.drawPath(path, paint) }
        paint.pathEffect = null
        // a line's arrowheads: a filled triangle at each end that has one, along the line
        val heads = ends or when (geometry) { "lineArrow" -> 2; "lineArrows" -> 3; else -> 0 }
        if (geometry in LINES && heads != 0) line?.let { c ->
            val len = kotlin.math.hypot(w.toDouble(), h.toDouble()).toFloat().takeIf { it > 0f } ?: return@let
            val ux = w / len; val uy = h / len; val size = maxOf(lineWidth * 3.5f, 6f)
            fun head(px: Float, py: Float, dx: Float, dy: Float) {
                val p = android.graphics.Path().apply { moveTo(px, py); lineTo(px - dx * size - dy * size * 0.5f, py - dy * size + dx * size * 0.5f)
                    lineTo(px - dx * size + dy * size * 0.5f, py - dy * size - dx * size * 0.5f); close() }
                paint.style = android.graphics.Paint.Style.FILL; paint.color = c; canvas.drawPath(p, paint)
            }
            if (heads and 2 != 0) head(x + w, y + h, ux, uy)
            if (heads and 1 != 0) head(x, y, -ux, -uy)
        }
    }
    /** A face darker (−) or lighter (+): a can's top, a cube's sides. */
    private fun shaded(c: Int, k: Float): Int {
        if (k == 0f) return c
        fun ch(v: Int) = (if (k < 0) v * (1 + k) else v + (255 - v) * k).toInt().coerceIn(0, 255)
        return (c and 0xFF000000.toInt()) or (ch((c shr 16) and 0xFF) shl 16) or (ch((c shr 8) and 0xFF) shl 8) or ch(c and 0xFF)
    }
    override fun draw(canvas: Canvas) { val b = bounds; drawAt(canvas, b.left.toFloat(), b.top.toFloat(), b.width().toFloat(), b.height().toFloat()) }
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
    @Deprecated("Drawable") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT

    companion object {
        /** Word's preset names; LibreOffice's draw:type names map to them. */
        val KINDS = listOf("rect", "roundRect", "ellipse", "triangle", "rtTriangle", "diamond", "pentagon", "hexagon", "octagon", "star5",
            "rightArrow", "leftArrow", "upArrow", "downArrow", "plus", "heart", "line")
        private val LINES = setOf("line", "straightConnector1", "lineArrow", "lineArrows")
        private val ODF = mapOf("rectangle" to "rect", "round-rectangle" to "roundRect", "ellipse" to "ellipse", "isosceles-triangle" to "triangle",
            "right-triangle" to "rtTriangle", "diamond" to "diamond", "pentagon" to "pentagon", "hexagon" to "hexagon", "octagon" to "octagon",
            "star5" to "star5", "right-arrow" to "rightArrow", "left-arrow" to "leftArrow", "up-arrow" to "upArrow", "down-arrow" to "downArrow",
            "cross" to "plus", "heart" to "heart", "mso-spt20" to "line")
        fun odfType(kind: String) = ODF.entries.firstOrNull { it.value == kind }?.key ?: "rectangle"
        /** A LibreOffice shape type as Word's preset name: its own names, or "ooxml-<preset>". */
        fun presetOf(odfType: String) = ODF[odfType] ?: odfType.removePrefix("ooxml-").takeIf { odfType.startsWith("ooxml-") && it in ShapeGeometry.presets }

        /** The shape's outline in a [w]×[h] box from 0,0. */
        fun path(kind: String, w: Float, h: Float): android.graphics.Path = android.graphics.Path().apply {
            fun poly(vararg p: Float) { moveTo(p[0] * w, p[1] * h); var i = 2; while (i < p.size) { lineTo(p[i] * w, p[i + 1] * h); i += 2 }; close() }
            fun ring(n: Int, start: Double = -Math.PI / 2) = poly(*FloatArray(n * 2) { k -> val a = start + 2 * Math.PI * (k / 2) / n
                if (k % 2 == 0) (0.5 + 0.5 * Math.cos(a)).toFloat() else (0.5 + 0.5 * Math.sin(a)).toFloat() })
            when (kind) {
                "ellipse" -> addOval(0f, 0f, w, h, android.graphics.Path.Direction.CW)
                "roundRect" -> addRoundRect(0f, 0f, w, h, minOf(w, h) / 6, minOf(w, h) / 6, android.graphics.Path.Direction.CW)
                "triangle" -> poly(0.5f, 0f, 1f, 1f, 0f, 1f)
                "rtTriangle" -> poly(0f, 0f, 1f, 1f, 0f, 1f)
                "diamond" -> poly(0.5f, 0f, 1f, 0.5f, 0.5f, 1f, 0f, 0.5f)
                "pentagon" -> ring(5)
                "hexagon" -> poly(0.25f, 0f, 0.75f, 0f, 1f, 0.5f, 0.75f, 1f, 0.25f, 1f, 0f, 0.5f)
                "octagon" -> poly(0.29f, 0f, 0.71f, 0f, 1f, 0.29f, 1f, 0.71f, 0.71f, 1f, 0.29f, 1f, 0f, 0.71f, 0f, 0.29f)
                "star5" -> poly(*FloatArray(20) { k -> val i = k / 2; val a = -Math.PI / 2 + Math.PI * i / 5; val r = if (i % 2 == 0) 0.5 else 0.2
                    if (k % 2 == 0) (0.5 + r * Math.cos(a)).toFloat() else (0.5 + r * Math.sin(a) * 1.05 + 0.03).toFloat() })
                "rightArrow" -> poly(0f, 0.25f, 0.6f, 0.25f, 0.6f, 0f, 1f, 0.5f, 0.6f, 1f, 0.6f, 0.75f, 0f, 0.75f)
                "leftArrow" -> poly(1f, 0.25f, 0.4f, 0.25f, 0.4f, 0f, 0f, 0.5f, 0.4f, 1f, 0.4f, 0.75f, 1f, 0.75f)
                "upArrow" -> poly(0.25f, 1f, 0.25f, 0.4f, 0f, 0.4f, 0.5f, 0f, 1f, 0.4f, 0.75f, 0.4f, 0.75f, 1f)
                "downArrow" -> poly(0.25f, 0f, 0.25f, 0.6f, 0f, 0.6f, 0.5f, 1f, 1f, 0.6f, 0.75f, 0.6f, 0.75f, 0f)
                "plus" -> poly(0.35f, 0f, 0.65f, 0f, 0.65f, 0.35f, 1f, 0.35f, 1f, 0.65f, 0.65f, 0.65f, 0.65f, 1f, 0.35f, 1f, 0.35f, 0.65f, 0f, 0.65f, 0f, 0.35f, 0.35f, 0.35f)
                "heart" -> { moveTo(0.5f * w, h); cubicTo(-0.1f * w, 0.55f * h, 0.05f * w, -0.05f * h, 0.5f * w, 0.25f * h)
                    cubicTo(0.95f * w, -0.05f * h, 1.1f * w, 0.55f * h, 0.5f * w, h); close() }
                "line", "straightConnector1", "lineArrow", "lineArrows" -> { moveTo(0f, 0f); lineTo(w, h) }
                else -> addRect(0f, 0f, w, h, android.graphics.Path.Direction.CW)
            }
        }

        /**
         * The open document's theme colours (its theme part, when its styles are read — one document is open at a time),
         * for Word shapes that name them; Office's default theme until then.
         */
        @Volatile var theme: Map<String, Int> = emptyMap()
        private val THEME_NAMES = mapOf("tx1" to "dk1", "bg1" to "lt1", "tx2" to "dk2", "bg2" to "lt2")
        private val THEME = mapOf("accent1" to 0x4472C4, "accent2" to 0xED7D31, "accent3" to 0xA5A5A5, "accent4" to 0xFFC000, "accent5" to 0x5B9BD5,
            "accent6" to 0x70AD47, "dk1" to 0x000000, "lt1" to 0xFFFFFF, "dk2" to 0x44546A, "lt2" to 0xE7E6E6, "tx1" to 0x000000, "bg1" to 0xFFFFFF, "tx2" to 0x44546A, "bg2" to 0xE7E6E6)
        /** A colour as DrawingML gives it (srgbClr or a theme's schemeClr), with its shade, lumMod/lumOff and alpha. */
        fun themeColor(xml: String?): Int? {
            xml ?: return null
            val m = Regex("""<a:(srgbClr|schemeClr)\s+val="(\w+)"(?:\s*/>|>([\s\S]*?)</a:\1>)""").find(xml) ?: return null
            val name = m.groupValues[2]
            var rgb = if (m.groupValues[1] == "srgbClr") name.toIntOrNull(16) ?: return null else theme[THEME_NAMES[name] ?: name] ?: THEME[name] ?: return null
            val mods = m.groupValues[3]
            fun mod(n: String) = Regex("""<a:$n val="(\d+)"""").find(mods)?.groupValues?.get(1)?.toFloatOrNull()?.div(100000f)
            fun each(f: (Float) -> Float) { rgb = (f(((rgb shr 16) and 0xFF) / 255f).coerceIn(0f, 1f) * 255).toInt() shl 16 or ((f(((rgb shr 8) and 0xFF) / 255f).coerceIn(0f, 1f) * 255).toInt() shl 8) or (f((rgb and 0xFF) / 255f).coerceIn(0f, 1f) * 255).toInt() }
            mod("shade")?.let { k -> each { it * k } }
            mod("tint")?.let { k -> each { 1 - (1 - it) * k } }
            val lm = mod("lumMod"); val lo = mod("lumOff")
            if (lm != null || lo != null) each { it * (lm ?: 1f) + (lo ?: 0f) }
            val alpha = mod("alpha")?.let { Math.round(it * 255) } ?: 255
            return (alpha shl 24) or rgb
        }

        /** A LibreOffice shape in its style's colours (draw:fill / draw:stroke, none for none), else LibreOffice's default blue. */
        private fun styledShape(kind: String, look: String?): ShapePicture {
            fun v(n: String) = look?.let { Regex("""\s$n="([^"]*)"""").findAll(it).lastOrNull()?.groupValues?.get(1) }
            fun color(c: String?) = c?.removePrefix("#")?.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
            val fill = when (v("draw:fill")) { "none" -> null; null -> 0xFF729FCF.toInt(); else -> color(v("draw:fill-color")) ?: 0xFF729FCF.toInt() }
            val line = when (v("draw:stroke")) { "none" -> null; null -> 0xFF3465A4.toInt(); else -> color(v("svg:stroke-color")) ?: 0xFF3465A4.toInt() }
            val width = v("svg:stroke-width")?.let { odfLength(it) }?.takeIf { it > 0f } ?: 1f
            return ShapePicture(kind, fill, line, width, dash = v("draw:stroke-dash")?.takeIf { v("draw:stroke") == "dash" }?.let(LineDash::fromOdf), shadow = v("draw:shadow") == "visible")
        }

        /** From a drawing's XML, when it is a shape of those kinds (Word's, or a custom shape Writer made); null otherwise. */
        fun of(img: DocImage): ShapePicture? = of(img.raw, img.look)
        fun of(raw: String?, look: String? = null): ShapePicture? {
            if (raw == null) return null
            if (raw.startsWith("<draw:custom-shape")) {
                // Its own path when it has one (any LibreOffice shape); else its type, if Word has it.
                val own = ShapeGeometry.ofOdf(raw)
                val type = Regex("""draw:type="([^"]+)"""").find(raw)?.groupValues?.get(1)
                val kind = type?.let(::presetOf) ?: if (own != null) "custom" else return null
                fun sized(p: ShapePicture) = if (own != null) ShapePicture(kind, p.fill, p.line, p.lineWidth, own, dash = p.dash, shadow = p.shadow) else p
                // Writer's own shapes carry their look in their style's name: Tetra_20_shape_<fill|none>_<line|none>_<width×100>[-<dash>][-shadow]_<wrap>.
                val m = Regex("""draw:style-name="Tetra_20_shape_(\w+?)_(\w+?)_(\d+)((?:-[A-Za-z]+)*)_""").find(raw)
                fun hex(v: String?) = v?.takeIf { it != "none" }?.let { t -> t.toLongOrNull(16)?.let { if (t.length == 8) it.toInt() else (0xFF000000 or it).toInt() } }
                val extra = m?.groupValues?.get(4)?.split('-')?.filter { it.isNotEmpty() }.orEmpty()
                return sized(if (m != null) ShapePicture(kind, hex(m.groupValues[1]), hex(m.groupValues[2]), m.groupValues[3].toInt() / 100f,
                    dash = extra.firstOrNull { it != "shadow" }, shadow = "shadow" in extra)
                    else styledShape(kind, look)) // its style's fill and line, else LibreOffice's own default look
            }
            val sp = Regex("""<wps:spPr>([\s\S]*?)</wps:spPr>""").find(raw)?.groupValues?.get(1) ?: return null
            val geometry = Regex("""<a:prstGeom prst="(\w+)"""").find(sp)?.groupValues?.get(1)?.takeIf { it in KINDS || it in LINES || it in ShapeGeometry.presets } ?: return null
            // Its adjustments (a rounded corner's size, an arrow's head…): Word's "val n".
            val adjust = Regex("""<a:gd name="(\w+)" fmla="val (-?\d+)"""").findAll(Regex("""<a:avLst>([\s\S]*?)</a:avLst>""").find(sp)?.groupValues?.get(1).orEmpty())
                .associate { it.groupValues[1] to it.groupValues[2].toDouble() }
            // A colour as given (its own, or the theme's — SmartArt's and Word's own shapes name the theme's), its alpha too.
            fun color(xml: String?): Int? = if (xml == null || "<a:noFill" in xml) null else themeColor(xml)
            val ln = Regex("""<a:ln\b([^>]*)>([\s\S]*?)</a:ln>""").find(sp)
            // What is before the outline is the fill (spPr's order: geometry, fill, outline); none said there: the
            // shape style's (Word's own shapes take the theme's accent, its outline darker).
            val fillPart = sp.substringBefore("<a:ln").substringAfter("</a:prstGeom>", "").ifEmpty { sp.substringBefore("<a:ln").substringAfter("<a:avLst/>", "") }
            val styleXml = Regex("""<wps:style>([\s\S]*?)</wps:style>""").find(raw)?.groupValues?.get(1)
            val saysFill = Regex("""<a:(noFill|solidFill|gradFill|blipFill|pattFill)\b""").containsMatchIn(fillPart)
            val fill = if (saysFill) color(fillPart) else styleXml?.let { themeColor(Regex("""<a:fillRef\b[^>]*>([\s\S]*?)</a:fillRef>""").find(it)?.groupValues?.get(1)) }
            val styleLine = if (ln != null && Regex("""<a:(noFill|solidFill|gradFill)\b""").containsMatchIn(ln.groupValues[2])) null
                else styleXml?.let { themeColor(Regex("""<a:lnRef\b[^>]*>([\s\S]*?)</a:lnRef>""").find(it)?.groupValues?.get(1)) }
            val width = ln?.groupValues?.get(1)?.let { Regex("""w="(\d+)"""").find(it)?.groupValues?.get(1)?.toFloatOrNull() }?.div(12700f) ?: 0.75f
            // its arrowheads (Word's line ends that are not "none")
            fun end(tag: String) = Regex("""<a:$tag\b[^>]*\btype="(\w+)"""").find(ln?.groupValues?.get(2).orEmpty())?.groupValues?.get(1)?.let { it != "none" } == true
            val dash = Regex("""<a:prstDash val="(\w+)"""").find(ln?.groupValues?.get(2).orEmpty())?.groupValues?.get(1)?.takeIf { it != "solid" }
            return ShapePicture(geometry, fill, styleLine ?: color(ln?.groupValues?.get(2)), width, adjust = adjust, ends = (if (end("headEnd")) 1 else 0) or (if (end("tailEnd")) 2 else 0),
                dash = dash, shadow = "<a:outerShdw" in sp)
        }
    }
}
