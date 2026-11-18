package com.kalotrapezis.drive

import org.w3c.dom.Element
import kotlin.math.sign

/**
 * LibreOffice Impress's decks (.odp): each draw:page's frames (titles, outlines, text boxes, pictures, charts), custom
 * shapes and groups where they are (ODF keeps every shape's place on the page), their words with the looks of their
 * styles — the shape's presentation style and its master's parents, the paragraph's, the span's, the list's bullets —
 * the page's background, its notes; the master page's own shapes under each page.
 */
internal object SlidesOdp {
    private const val DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
    /** meta.xml's user-defined properties an .odp keeps its sources and their style in (References). */
    const val SOURCES_NAME = "Tetra sources"; const val STYLE_NAME = "Tetra citation style"
    private const val SVG = "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"
    private const val PRES = "urn:oasis:names:tc:opendocument:xmlns:presentation:1.0"
    private const val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    private const val STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    private const val XLINK = "http://www.w3.org/1999/xlink"
    private const val XML = "http://www.w3.org/XML/1998/namespace"
    private const val ANIM = "urn:oasis:names:tc:opendocument:xmlns:animation:1.0"
    private const val SMIL = "urn:oasis:names:tc:opendocument:xmlns:smil-compatible:1.0"

    private fun Element.kids(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
    private fun Element.a(ns: String, n: String): String? = getAttributeNS(ns, n).takeIf { it.isNotEmpty() }
    /**
     * Home › Reset (PowerPoint's; Impress has none): [page]'s placeholders back to [master]'s — their place and size, the
     * master's presentation style, no paragraph or letter looks of their own (lists kept). Shown and saved alike.
     */
    fun resetPage(page: Element, master: Element?) {
        val own = master?.let { m -> (0 until m.childNodes.length).mapNotNull { m.childNodes.item(it) as? Element } }.orEmpty().filter { it.a(PRES, "class") != null }
        for (el in (0 until page.childNodes.length).mapNotNull { page.childNodes.item(it) as? Element }) {
            val cls = el.a(PRES, "class") ?: continue
            if (cls in setOf("date-time", "footer", "page-number", "header", "notes")) continue
            // a subtitle where the master's outline is (Impress's own way)
            val m = own.firstOrNull { it.a(PRES, "class") == cls } ?: own.firstOrNull { cls == "subtitle" && it.a(PRES, "class") == "outline" }
            m?.let { for (n in listOf("x", "y", "width", "height")) it.a(SVG, n)?.let { v -> el.setAttributeNS(SVG, "svg:$n", v) }; el.removeAttributeNS(DRAW, "transform") }
            m?.a(PRES, "style-name")?.let { el.setAttributeNS(PRES, "presentation:style-name", it) }
            el.removeAttributeNS(DRAW, "text-style-name")
            listOf("p", "span", "h").forEach { n -> el.getElementsByTagNameNS(TEXT, n).let { l -> (0 until l.length).forEach { (l.item(it) as Element).removeAttributeNS(TEXT, "style-name") } } }
        }
    }
    /** A paragraph's words, its line breaks kept (textContent drops them). */
    private fun plainText(n: org.w3c.dom.Node): String = when {
        n is Element && n.localName == "line-break" -> "\n"
        n.nodeType == org.w3c.dom.Node.TEXT_NODE -> n.nodeValue
        else -> (0 until n.childNodes.length).joinToString("") { plainText(n.childNodes.item(it)) }
    }
    private fun Element.attrs(): Map<String, String> = (0 until attributes.length).associate { attributes.item(it).let { a -> (a.localName ?: a.nodeName) to a.nodeValue } }

    /** A length ("2.5cm", "12pt", "0.5in") in points. */
    private fun pt(v: String?): Float? {
        val m = Regex("""^(-?[0-9.]+)\s*(cm|mm|in|pt|pc|px)?$""").find(v?.trim() ?: return null) ?: return null
        val n = m.groupValues[1].toFloatOrNull() ?: return null
        return when (m.groupValues[2]) { "cm" -> n / 2.54f * 72; "mm" -> n / 25.4f * 72; "in" -> n * 72; "pc" -> n * 12; "px" -> n * 0.75f; else -> n }
    }
    /** An element as XML text (a custom shape's geometry, for [ShapeGeometry.ofOdf]). */
    private fun xml(e: Element): String = java.io.StringWriter().also { w ->
        javax.xml.transform.TransformerFactory.newInstance().newTransformer().apply { setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes") }
            .transform(javax.xml.transform.dom.DOMSource(e), javax.xml.transform.stream.StreamResult(w))
    }.toString()
    private fun hex(v: String?) = v?.takeIf { it.startsWith("#") && it.length == 7 }?.substring(1)?.toIntOrNull(16)?.or(0xFF000000.toInt())

    private class Style(val parent: String?, val props: Map<String, String>)
    private class Bullet(val char: String?, val before: Float, val label: Float, val color: Int?, val size: Float = 1f, val font: String? = null)

    fun read(file: java.io.File): Deck = java.util.zip.ZipFile(file).let { zip ->
        fun part(n: String): ByteArray? = zip.getEntry(n)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
        val content = OfficeFormats.parse(part("content.xml") ?: error("Not a presentation.")).documentElement
        val styles = part("styles.xml")?.let { OfficeFormats.parse(it).documentElement }
        // every style by family and name, its properties' attributes as "t." text, "p." paragraph, "g." graphic, "d." drawing page
        val all = HashMap<String, Style>(); val lists = HashMap<String, Map<Int, Bullet>>()
        fun collect(root: Element?) {
            root ?: return
            val l = root.getElementsByTagNameNS(STYLE, "style")
            for (i in 0 until l.length) {
                val s = l.item(i) as Element
                val props = HashMap<String, String>()
                for (k in s.kids()) {
                    val p = when (k.localName) { "text-properties" -> "t."; "paragraph-properties" -> "p."; "graphic-properties" -> "g."; "drawing-page-properties" -> "d."
                        "table-column-properties" -> "c."; "table-row-properties" -> "r."; else -> continue }
                    k.attrs().forEach { (n, v) -> props[p + n] = v }
                    // a text box's columns (style:columns inside its graphic properties)
                    k.kids().firstOrNull { it.localName == "columns" }?.let { c -> c.attrs().forEach { (n, v) -> props[p + "columns." + n] = v } }
                }
                all[s.a(STYLE, "family") + ":" + s.a(STYLE, "name")] = Style(s.a(STYLE, "parent-style-name"), props)
            }
            val ls = root.getElementsByTagNameNS(TEXT, "list-style")
            for (i in 0 until ls.length) {
                val s = ls.item(i) as Element
                lists[s.a(STYLE, "name") ?: continue] = s.kids().associate { lv ->
                    val lp = lv.kids().firstOrNull { it.localName == "list-level-properties" }
                    val tp = lv.kids().firstOrNull { it.localName == "text-properties" }
                    (lv.a(TEXT, "level")?.toIntOrNull() ?: 1) - 1 to Bullet(if (lv.localName == "list-level-style-bullet") lv.a(TEXT, "bullet-char") else if (lv.localName == "list-level-style-number") SlideBullet.text(SlideBullet.form(lv.a(STYLE, "num-format")), 1) else null,
                        pt(lp?.a(TEXT, "space-before")) ?: 0f, pt(lp?.a(TEXT, "min-label-width")) ?: 0f, hex(tp?.getAttributeNS("urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0", "color")),
                        tp?.getAttributeNS("urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0", "font-size")?.takeIf { it.endsWith("%") }?.removeSuffix("%")?.toFloatOrNull()?.div(100f) ?: 1f,
                        tp?.a(STYLE, "font-name") ?: tp?.getAttributeNS("urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0", "font-family")?.takeIf { it.isNotEmpty() })
                }
            }
        }
        collect(styles); collect(content)
        // LibreOffice's gradients by name: their two colours and angle (0: top to bottom, turning anticlockwise)
        // the fill pictures (a page's bitmap background names one)
        val fillImages = listOfNotNull(styles, content).flatMap { root -> root.getElementsByTagNameNS(DRAW, "fill-image").let { l -> (0 until l.length).map { l.item(it) as Element } } }
            .mapNotNull { f -> f.a(DRAW, "name")?.let { n -> f.a(XLINK, "href")?.removePrefix("./")?.let { n to it } } }.toMap()
        val gradients = HashMap<String, SlideGradient>()
        for (root in listOfNotNull(styles, content)) root.getElementsByTagNameNS(DRAW, "gradient").let { l -> for (i in 0 until l.length) { val g = l.item(i) as Element
            val a = g.a(DRAW, "start-color")?.let(::hex) ?: continue; val b = g.a(DRAW, "end-color")?.let(::hex) ?: continue
            val deg = g.a(DRAW, "angle")?.let { v -> if (v.endsWith("deg")) v.removeSuffix("deg").toFloatOrNull() else v.toFloatOrNull()?.div(10f) } ?: 0f
            val axial = g.a(DRAW, "style") == "axial"
            gradients[g.a(DRAW, "name") ?: continue] = SlideGradient(if (axial) intArrayOf(b, a, b) else intArrayOf(a, b), if (axial) floatArrayOf(0f, 0.5f, 1f) else floatArrayOf(0f, 1f), 90f - deg)
        } }
        /** A style's property with its parents' under it. */
        fun prop(family: String, name: String?, key: String, depth: Int = 0): String? {
            val s = all["$family:${name ?: return null}"] ?: return null
            return s.props[key] ?: if (depth < 12) prop(family, s.parent, key, depth + 1) else null
        }
        // the page's size: the first page's master's page layout
        val masters = styles?.getElementsByTagNameNS(STYLE, "master-page")?.let { l -> (0 until l.length).map { l.item(it) as Element } }.orEmpty().associateBy { it.a(STYLE, "name") }
        val layouts = styles?.getElementsByTagNameNS(STYLE, "page-layout")?.let { l -> (0 until l.length).map { l.item(it) as Element } }.orEmpty()
            .associate { it.a(STYLE, "name") to it.kids().firstOrNull { k -> k.localName == "page-layout-properties" } }
        val pages = content.getElementsByTagNameNS(DRAW, "page").let { l -> (0 until l.length).map { l.item(it) as Element } }
        val firstMaster = masters[pages.firstOrNull()?.a(DRAW, "master-page-name")]
        val layout = layouts[firstMaster?.a(STYLE, "page-layout-name")]
        val fo = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
        val width = pt(layout?.getAttributeNS(fo, "page-width")) ?: 720f; val height = pt(layout?.getAttributeNS(fo, "page-height")) ?: 540f

        fun box(e: Element): FloatArray {
            val w = pt(e.a(SVG, "width")) ?: 0f; val h = pt(e.a(SVG, "height")) ?: 0f
            // turned: its unturned corner from its draw:transform (Words' reading of one)
            val turned = e.a(DRAW, "transform")?.let { t -> Arrange.odtTransform(""" svg:width="${e.a(SVG, "width")}" svg:height="${e.a(SVG, "height")}" draw:transform="$t"""")?.second }
            return floatArrayOf(turned?.first ?: pt(e.a(SVG, "x")) ?: 0f, turned?.second ?: pt(e.a(SVG, "y")) ?: 0f, w, h)
        }
        fun rotation(e: Element) = e.a(DRAW, "transform")?.let { Regex("""rotate\s*\(\s*(-?[0-9.eE]+)""").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }?.let { (-Math.toDegrees(it)).toFloat() } ?: 0f

        /** The words of a frame or shape: its paragraphs, a list's items a level each, with their looks. */
        fun text(holder: Element, presStyle: String?, graphicStyle: String?): SlideText? {
            val paras = ArrayList<SlidePara>()
            fun family(name: String?) = if (presStyle != null) "presentation" to presStyle else "graphic" to graphicStyle
            fun shapeProp(key: String): String? { val (f, n) = family(null); return prop(f, n, key) }
            fun para(p: Element, level: Int, list: String?) {
                val pStyle = p.a(TEXT, "style-name")
                // an outline's level n takes its master's outline n style (Title_and_Content-outline2…)
                val levelStyle = presStyle?.let { s -> prop("presentation", s, "__") ?: all["presentation:$s"]?.parent }?.let { base ->
                    if (level > 0 && Regex("""outline\d$""").containsMatchIn(base)) base.dropLast(1) + (level + 1) else base }
                fun textProp(span: String?, key: String) = prop("text", span, "t.$key") ?: prop("paragraph", pStyle, "t.$key")
                    ?: levelStyle?.let { prop("presentation", it, "t.$key") } ?: shapeProp("t.$key")
                val runs = ArrayList<DocRun>()
                fun look(t: String, span: String?, link: String? = null): DocRun {
                    fun has(key: String) = textProp(span, key).let { it != null && it != "none" }
                    val face = textProp(span, "font-name") ?: textProp(span, "font-family")
                    val effects = TextEffects(if (textProp(span, "text-outline") == "true") 0 else null, has("text-shadow"))
                    // the letters' looks Words reads from a run's raw settings, in LibreOffice's form
                    val looks = listOfNotNull("text-transform".takeIf { textProp(span, it) == "uppercase" }?.let { """ fo:text-transform="uppercase"""" },
                        "font-variant".takeIf { textProp(span, it) == "small-caps" }?.let { """ fo:font-variant="small-caps"""" },
                        "text-line-through-type".takeIf { textProp(span, it) == "double" }?.let { """ style:text-line-through-type="double"""" },
                        "text-underline-type".takeIf { textProp(span, it) == "double" }?.let { """ style:text-underline-type="double"""" },
                        textProp(span, "letter-spacing")?.takeIf { it != "normal" }?.let { """ fo:letter-spacing="$it"""" }).joinToString("") + effects.odt()
                    // "super 58%", "sub", "33% 58%": raised or lowered
                    val position = textProp(span, "text-position")?.substringBefore(' ')
                    return DocRun(t, bold = textProp(span, "font-weight").let { it == "bold" || (it?.toIntOrNull() ?: 400) >= 600 }, italic = textProp(span, "font-style") == "italic",
                        underline = has("text-underline-style"), strike = has("text-line-through-style"), sizePt = pt(textProp(span, "font-size")) ?: 18f,
                        color = (hex(textProp(span, "color")) ?: 0) and 0xFFFFFF, highlight = textProp(span, "background-color")?.takeIf { it != "transparent" }?.let(::hex)?.and(0xFFFFFF),
                        link = link, font = (face?.let { """style:font-name="$it"""" } ?: "") + looks,
                        script = when { position == null -> 0; position == "super" -> 1; position == "sub" -> -1; else -> position.removeSuffix("%").toFloatOrNull()?.sign?.toInt() ?: 0 })
                }
                fun run(t: String, span: String?, link: String? = null) { runs += look(t, span, link) }
                fun walk(n: org.w3c.dom.Node, span: String?, link: String? = null) {
                    when {
                        n is org.w3c.dom.Text -> if (n.data.isNotEmpty()) run(n.data.replace('\n', ' '), span, link)
                        n is Element && n.localName == "span" -> for (i in 0 until n.childNodes.length) walk(n.childNodes.item(i), n.a(TEXT, "style-name") ?: span, link)
                        n is Element && n.localName == "s" -> run(" ".repeat(n.a(TEXT, "c")?.toIntOrNull() ?: 1), span, link)
                        n is Element && n.localName == "tab" -> run("\t", span, link)
                        n is Element && n.localName == "line-break" -> run(LINE_BREAK.toString(), span, link)
                        n is Element && n.localName == "a" -> for (i in 0 until n.childNodes.length) walk(n.childNodes.item(i), span, n.getAttributeNS("http://www.w3.org/1999/xlink", "href").takeIf { it.isNotEmpty() })
                        n is Element && n.localName in setOf("page-number", "date", "time") -> for (i in 0 until n.childNodes.length) walk(n.childNodes.item(i), span, link)
                    }
                }
                for (i in 0 until p.childNodes.length) walk(p.childNodes.item(i), null)
                if (runs.isEmpty()) run("", null)
                val bullet = list?.let { lists[it]?.get(level) }
                fun pProp(key: String) = prop("paragraph", pStyle, "p.$key") ?: levelStyle?.let { prop("presentation", it, "p.$key") } ?: shapeProp("p.$key")
                val align = when (pProp("text-align")) { "center" -> DocAlign.Center; "end", "right" -> DocAlign.End; "justify" -> DocAlign.Justify; else -> DocAlign.Start }
                val marL = (bullet?.let { it.before + it.label } ?: 0f) + (pt(pProp("margin-left")) ?: 0f)
                // a line height in points: exact; a percentage: proportional
                val height = pProp("line-height")
                paras += SlidePara(DocPara(runs, list = DocList(bullet?.char?.firstOrNull()?.isDigit() == true, level), align = align),
                    ParaLook(before = pt(pProp("margin-top")) ?: 0f, after = pt(pProp("margin-bottom")) ?: 0f, lineMultiple = height?.takeIf { it.endsWith("%") }?.removeSuffix("%")?.toFloatOrNull()?.div(100f) ?: 1f,
                        lineExact = height?.takeIf { !it.endsWith("%") }?.let(::pt), left = marL, firstLine = -(bullet?.label ?: 0f) + (pt(pProp("text-indent")) ?: 0f), listIndented = true),
                    bullet?.char?.let { SlideBullet(it, bullet.color, bullet.size, bullet.font, it.takeIf { c -> c.length > 1 && c.endsWith(".") }?.let { c -> SlideBullet.form(c.dropLast(1)) }) }, look("", null))
            }
            // a list's items sit one level in each time a list is inside an item
            fun walkList(list: Element, level: Int, name: String?) {
                for (item in list.kids()) for (inner in item.kids()) when (inner.localName) {
                    "p", "h" -> para(inner, level, name)
                    "list" -> walkList(inner, level + 1, inner.a(TEXT, "style-name") ?: name)
                }
            }
            // a list without a style of its own takes its outline's (the presentation style's own list style, LibreOffice's bullets)
            val outlineList = generateSequence(presStyle) { all["presentation:$it"]?.parent }.take(12).firstOrNull { it in lists }
            for (k in holder.kids()) when (k.localName) {
                "p", "h" -> para(k, 0, null)
                "list" -> walkList(k, 0, k.a(TEXT, "style-name") ?: outlineList)
            }
            if (paras.none { p -> p.runs.any { it.text.isNotBlank() } }) return null
            fun g(key: String) = shapeProp("g.$key")
            val anchor = when (g("textarea-vertical-align")) { "middle" -> 'c'; "bottom" -> 'b'; else -> 't' }
            return SlideText(SlidePara.numbered(paras), anchor, floatArrayOf(pt(g("padding-left")) ?: 7.2f, pt(g("padding-top")) ?: 3.6f, pt(g("padding-right")) ?: 7.2f, pt(g("padding-bottom")) ?: 3.6f),
                shrink = g("shrink-to-fit") == "true" || g("fit-to-size") == "shrink-to-fit", wrap = g("wrap-option") != "no-wrap",
                cols = g("columns.column-count")?.toIntOrNull()?.coerceIn(1, 16) ?: 1, colGap = pt(g("columns.column-gap")) ?: 0f,
                // LibreOffice's text direction (its loext:writing-mode): tb-rl90 turned 90°, bt-lr 270°
                vert = when (g("writing-mode")) { "tb-rl90", "tb-rl" -> 90; "bt-lr" -> 270; else -> 0 })
        }

        /** A table in its frame's box [b]: its columns' and rows' sizes (from their styles, fitted to the box), each cell's fill and words. */
        fun table(t: Element, b: FloatArray): SlideItem.Table {
            val TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
            fun repeat(e: Element) = e.a(TABLE, "number-columns-repeated")?.toIntOrNull()?.coerceIn(1, 64) ?: 1
            val cols = t.kids().filter { it.localName == "table-column" }.flatMap { c -> List(repeat(c)) { c } }
            val rows = t.kids().filter { it.localName == "table-row" }
            fun fit(sizes: List<Float?>, total: Float) = sizes.map { it ?: (total / sizes.size.coerceAtLeast(1)) }.let { l -> val sum = l.sum(); if (sum > 0) l.map { it * total / sum } else l }
            val widths = fit(cols.map { pt(prop("table-column", it.a(TABLE, "style-name"), "c.column-width")) }, b[2])
            val heights = fit(rows.map { pt(prop("table-row", it.a(TABLE, "style-name"), "r.row-height")) }, b[3])
            var line: Int? = null
            val cells = rows.map { r ->
                r.kids().filter { it.localName == "table-cell" || it.localName == "covered-table-cell" }.flatMap { c -> List(repeat(c)) { c } }.take(widths.size.coerceAtLeast(1)).mapIndexed { k, c ->
                    val st = c.a(TABLE, "style-name") ?: r.a(TABLE, "default-cell-style-name") ?: cols.getOrNull(k)?.a(TABLE, "default-cell-style-name")
                    val fill = if (prop("table-cell", st, "g.fill") == "none") null else hex(prop("table-cell", st, "g.fill-color"))
                    if (line == null) line = prop("table-cell", st, "p.border")?.let { Regex("#[0-9a-fA-F]{6}").find(it)?.value }?.let(::hex)
                    fill to text(c, null, null)
                }
            }
            return SlideItem.Table(b[0], b[1], b[2], b[3], widths, heights, cells, line)
        }
        /**
         * The shapes under [parent]; a page's own named by their place on it ("o3": the page's fourth element, "o3.1" the
         * second in that group) — what editing finds them by again in the file ([top]: the group's, what moves with it).
         */
        val named = HashMap<String, String>() // a shape's xml:id (what an animation names) → its item's id
        fun shapes(parent: Element, out: MutableList<SlideItem>, master: Boolean, prefix: String? = null, top: String? = null,
                   /** the master page's name: its own shapes keyed by their place in it, to be chosen on a page and taken off every page */ masterKey: String? = null) {
            for ((k, e) in parent.kids().withIndex()) runCatching { val first = out.size; try {
                val path = if (master) null else prefix?.let { "$it.$k" } ?: "o$k"
                if (path != null) (e.a(XML, "id") ?: e.a(DRAW, "id"))?.let { named[it] = top ?: path }
                val before = out.size
                // a master's placeholders (title, outline, numbers): their words show only on pages that fill them
                if (master && (e.a(PRES, "class") != null || e.a(PRES, "placeholder") == "true")) return@runCatching
                val b = box(e); val rot = rotation(e)
                val pres = e.a(PRES, "style-name"); val gr = e.a(DRAW, "style-name")
                fun g(key: String) = if (pres != null) prop("presentation", pres, "g.$key") else prop("graphic", gr, "g.$key")
                fun fill() = if (g("fill") == "none") null else if (g("fill") == "solid" || g("fill-color") != null) hex(g("fill-color")) else
                    if (g("fill") == "gradient") hex(g("fill-color")) ?: 0xFF729FCF.toInt() else null // shortcut: a gradient as one colour
                fun line() = if (g("stroke") == "none") null else hex(g("stroke-color")) ?: if (g("stroke") == "solid") 0xFF3465A4.toInt() else null
                fun gradient() = if (g("fill") == "gradient") g("fill-gradient-name")?.let(gradients::get) else null
                fun dash() = g("stroke-dash")?.takeIf { g("stroke") == "dash" }?.let(LineDash::fromOdf)
                when (e.localName) {
                    "frame" -> {
                        val inner = e.kids().firstOrNull() ?: return@runCatching
                        when (inner.localName) {
                            "text-box" -> {
                                val t = text(inner, pres, gr)
                                val f = fill(); val l = line()
                                val shape = if (f != null || l != null) ShapePicture("rect", f, l, pt(g("stroke-width"))?.coerceAtLeast(0.5f) ?: 0.75f, dash = dash(), shadow = g("shadow") == "visible") else null
                                if (t != null || shape != null) out += SlideItem.Shape(b[0], b[1], b[2], b[3], rot, shape, t, gradient = gradient(), id = path)
                            }
                            // a video (Impress's plugin): its picture the frame's image, its film a part of the file or a web address
                            // (a sound with no picture: a speaker, as PowerPoint's)
                            "plugin" -> e.kids().firstOrNull { it.localName == "image" }?.a(XLINK, "href")?.removePrefix("./").let { p -> val m = inner.a(XLINK, "href")?.removePrefix("./")
                                out += SlideItem.Picture(b[0], b[1], b[2], b[3], rot) { p?.let { part(it) } ?: m?.takeIf(OfficeVideo::audio)?.let { OfficeVideo.speaker() } }.also { it.media = m } }
                            "image" -> inner.a(XLINK, "href")?.removePrefix("./")?.let { p -> out += SlideItem.Picture(b[0], b[1], b[2], b[3], rot, g("clip")?.let { Slides.clip(it, part(p)) },
                                flipH = g("mirror")?.contains("horizontal") == true, flipV = g("mirror")?.contains("vertical") == true) { part(p) }.also {
                                // its transparency, outline and shadow as LibreOffice keeps them (no rounded pictures there)
                                it.pic = PictureLook(g("image-opacity")?.removeSuffix("%")?.toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f) ?: 1f,
                                    hex(g("stroke-color"))?.and(0xFFFFFF)?.takeIf { g("stroke") == "solid" }, pt(g("stroke-width"))?.takeIf { g("stroke") == "solid" } ?: 0f,
                                    g("shadow") == "visible").takeIf { l -> l != PictureLook() } } }
                            "table" -> out += table(inner, b)
                            // a film or sound embedded as an object (LibreOffice keeps PowerPoint's package as it was): its picture, played from it
                            "object-ole" -> inner.a(XLINK, "href")?.removePrefix("./")?.let { o -> part(o)?.let { OfficeVideo.ole(it, nameOnly = true) }?.first?.takeIf(OfficeVideo::media)?.let { name ->
                                val p = e.kids().firstOrNull { it.localName == "image" }?.a(XLINK, "href")?.removePrefix("./")
                                out += SlideItem.Picture(b[0], b[1], b[2], b[3], rot) { p?.let { part(it) } }.also { it.media = "$o#$name" } } }
                            "object" -> {
                                val c = inner.a(XLINK, "href")?.removePrefix("./")?.let { p -> part("$p/content.xml") }
                                val chart = c?.let(OfficeCharts::readOdf)
                                if (chart != null) out += SlideItem.Chart(b[0], b[1], b[2], b[3], chart)
                                // a formula (LibreOffice Math): its replacement picture shown, its equation kept to edit
                                else e.kids().firstOrNull { it.localName == "image" }?.a(XLINK, "href")?.removePrefix("./")?.let { p ->
                                    out += SlideItem.Picture(b[0], b[1], b[2], b[3], rot, null, c?.let(OfficeMath::fromMathML)) { part(p) } }
                            }
                        }
                    }
                    "custom-shape", "rect", "ellipse" -> {
                        val geo = e.kids().firstOrNull { it.localName == "enhanced-geometry" }
                        val type = geo?.a(DRAW, "type")
                        val preset = if (e.localName == "ellipse") "ellipse" else if (e.localName == "rect") "rect" else type?.let { ShapePicture.presetOf(it) }
                        val mods = geo?.a(DRAW, "modifiers")?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toDoubleOrNull() }.orEmpty()
                        val adjust = preset?.let { ShapeGeometry.presets[it]?.adjustNames?.zip(mods)?.toMap() }.orEmpty()
                        val own = if (preset == null && geo != null) ShapeGeometry.ofOdf(xml(geo)) else null
                        val f = fill() ?: if (g("fill") == null) 0xFF729FCF.toInt() else null; val l = line()
                        val shape = if (preset != null || own != null) ShapePicture(preset ?: "custom", f, l, pt(g("stroke-width"))?.coerceAtLeast(0.5f) ?: 0.75f, own, adjust, dash = dash(), shadow = g("shadow") == "visible") else null
                        // LibreOffice's fontwork (WordArt): its words along the bend, in the shape's fill (LibreOffice paints its letters so), no shape drawn
                        val warp = geo?.takeIf { it.a(DRAW, "text-path") == "true" }?.let { TextWarp.entries.firstOrNull { w -> w.fontwork == type } }
                        val words = text(e, null, gr)
                        if (warp != null && words != null) out += SlideItem.Shape(b[0], b[1], b[2], b[3], rot, null, SlideText(words.paras.map { p ->
                            SlidePara(p.para.copy(runs = p.runs.map { r -> r.copy(color = f?.and(0xFFFFFF) ?: r.color) }), p.look, p.bullet, p.base) }, words.anchor, words.insets, warp = warp), id = path)
                        else {
                            // mirrored one way: ODF turns before it mirrors, PowerPoint (and the drawing here) after — the same look is the other way round
                            val fh = geo?.a(DRAW, "mirror-horizontal") == "true"; val fv = geo?.a(DRAW, "mirror-vertical") == "true"
                            out += SlideItem.Shape(b[0], b[1], b[2], b[3], if (fh != fv) (360f - rot) % 360f else rot, shape, words, flipH = fh, flipV = fv, gradient = gradient(), id = path)
                        }
                    }
                    "g" -> shapes(e, out, master, path, top ?: path)
                }
                val desc = e.kids().firstOrNull { it.localName == "desc" }?.textContent
                // its click: LibreOffice's "go to page or object" or a web page (office:event-listeners)
                val click = e.kids().firstOrNull { it.localName == "event-listeners" }?.kids()?.firstOrNull { it.localName == "event-listener" && it.a(PRES, "action") == "show" }?.a(XLINK, "href")
                if (path != null) for (i in before until out.size) { if (out[i].anim == null) out[i].anim = top ?: path; if (top != null && out[i].own == null) out[i].own = path; if (top != null) out[i].grouped = true else { out[i].alt = desc; out[i].link = click
                    out[i].name = e.a(DRAW, "name")?.ifEmpty { null }; out[i].hidden = e.getAttributeNS("http://openoffice.org/2010/draw", "display") == "none" } }
            } finally { if (masterKey != null) for (j in first until out.size) { out[j].master = "$masterKey $k"; out[j].anim = "master:$masterKey $k" } }
            }.onFailure { WriterLog.w("Slides: an .odp shape could not be read", it) }
        }

        // Header & footer as LibreOffice keeps them: the words a page's footer and date name (presentation:footer-decl, date-time-decl)
        val decls = content.getElementsByTagNameNS(PRES, "*").let { l -> (0 until l.length).map { l.item(it) as Element } }.filter { it.localName in setOf("footer-decl", "date-time-decl") }
        val FOOT = mapOf("date-time" to "dt", "footer" to "ftr", "page-number" to "sldNum")
        /** Page [index] read as it shows on master page [masterName] (its own, or another chosen by Home › Layout). */
        fun pageSlide(index: Int, page: Element, masterName: String?): Slide {
            val items = ArrayList<SlideItem>()
            val master = masters[masterName]
            val dp = page.a(DRAW, "style-name")
            // the master's date, footer and number boxes as a page's own would show (its field as the kind's name, for the words put in)
            val footers = master?.kids().orEmpty().filter { it.localName == "frame" && it.a(PRES, "class") in FOOT }.mapNotNull { f ->
                val k = FOOT.getValue(f.a(PRES, "class")!!)
                val copy = f.cloneNode(true) as Element; copy.removeAttributeNS(PRES, "class")
                fun all(e: Element): List<Element> = e.kids().flatMap { listOf(it) + all(it) }
                all(copy).filter { it.localName in FOOT.keys }.forEach { fe -> fe.parentNode.replaceChild(fe.ownerDocument.createTextNode(k), fe) }
                val wrap = f.ownerDocument.createElementNS(DRAW, "draw:g").also { it.appendChild(copy) }
                val got = ArrayList<SlideItem>(); shapes(wrap, got, false, "footer")
                (got.lastOrNull() as? SlideItem.Shape)?.let { k to it }
            }.toMap()
            fun shows(key: String, otherwise: Boolean) = prop("drawing-page", dp, "d.$key")?.let { it == "true" } ?: otherwise
            fun decl(name: String?) = name?.let { n -> decls.firstOrNull { it.a(PRES, "name") == n } }
            val footed = listOfNotNull(
                footers["dt"]?.takeIf { shows("display-date-time", true) }?.let { sh -> decl(page.a(PRES, "use-date-time-name"))?.let { d -> SlidesEdit.worded(sh, if (d.a(PRES, "source") == "fixed") d.textContent else SlidesEdit.today(), "footer.dt") } },
                footers["ftr"]?.takeIf { shows("display-footer", true) }?.let { sh -> decl(page.a(PRES, "use-footer-name"))?.let { SlidesEdit.worded(sh, it.textContent, "footer.ftr") } },
                footers["sldNum"]?.takeIf { shows("display-page-number", false) }?.let { SlidesEdit.worded(it, "${index + 1}", "footer.sldNum") })
            if (prop("drawing-page", dp, "d.background-objects-visible") != "false") master?.let { shapes(it, items, true, masterKey = masterName) }
            shapes(page, items, false)
            fun bg(style: String?) = if (prop("drawing-page", style, "d.fill") == "solid" || prop("drawing-page", style, "d.fill-color") != null) hex(prop("drawing-page", style, "d.fill-color")) else null
            val background = bg(dp) ?: bg(master?.a(DRAW, "style-name")) ?: 0xFFFFFFFF.toInt()
            // a bitmap background (the page's, else its master's)
            fun bgPicture(style: String?) = if (prop("drawing-page", style, "d.fill") == "bitmap") prop("drawing-page", style, "d.fill-image-name")?.let(fillImages::get) else null
            val backPicture = (bgPicture(dp) ?: bgPicture(master?.a(DRAW, "style-name")))?.let { p -> { part(p) } }
            // a gradient background: the page's own fill, else its master's
            fun bgGradient(style: String?) = if (prop("drawing-page", style, "d.fill") == "gradient") prop("drawing-page", style, "d.fill-gradient-name")?.let(gradients::get) else null
            val backGradient = if (prop("drawing-page", dp, "d.fill") != null) bgGradient(dp) else bgGradient(master?.a(DRAW, "style-name"))
            val notes = page.kids().firstOrNull { it.localName == "notes" }?.kids()?.firstOrNull { it.a(PRES, "class") == "notes" }
                ?.getElementsByTagNameNS(TEXT, "p")?.let { l -> (0 until l.length).joinToString("\n") { plainText(l.item(it)) } }?.trim().orEmpty()
            // the transition: the page style's (LibreOffice's names), its length from the timing's own filter
            val root = page.kids().firstOrNull { it.namespaceURI == ANIM && it.a(PRES, "node-type") == "timing-root" }
            fun secs(v: String?) = v?.let { if (it.endsWith("ms")) it.removeSuffix("ms").toFloatOrNull()?.div(1000) else it.removeSuffix("s").toFloatOrNull() }
            val filter = root?.kids()?.firstNotNullOfOrNull { p -> p.kids().firstOrNull { it.localName == "transitionFilter" && it.a(SMIL, "targetElement") == null } }
            // going on by itself: an automatic transition after its duration (ISO 8601: PT5S, PT00H00M05S)
            val after = if (prop("drawing-page", dp, "d.transition-type") == "automatic") prop("drawing-page", dp, "d.duration")?.let { d ->
                Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?""").find(d)?.let { m -> val (h, mi, sec) = m.destructured
                    (((h.toIntOrNull() ?: 0) * 3600 + (mi.toIntOrNull() ?: 0) * 60 + (sec.toFloatOrNull() ?: 0f)) * 1000).toInt() } } ?: 0 else null
            val transition = prop("drawing-page", dp, "d.type")?.let { type ->
                OdpEffects.kind(type, prop("drawing-page", dp, "d.subtype"), prop("drawing-page", dp, "d.direction") == "reverse")?.let { (kind, dir) ->
                    val ms = secs(filter?.a(SMIL, "dur"))?.let { (it * 1000).toInt() } ?: when (prop("drawing-page", dp, "d.transition-speed")) { "slow" -> 1000; "fast" -> 500; else -> 750 }
                    SlideTransition(kind, dir, ms, after)
                }
            } ?: after?.let { SlideTransition("", null, 0, it) }
            // the clicks: the main sequence's groups, each its entrances and exits (LibreOffice's presets)
            val main = root?.getElementsByTagNameNS(ANIM, "seq")?.let { l -> (0 until l.length).map { l.item(it) as Element } }?.firstOrNull { it.a(PRES, "node-type") == "main-sequence" }
            val steps = main?.kids().orEmpty().mapNotNull { click ->
                val effects = click.getElementsByTagNameNS(ANIM, "par").let { l -> (0 until l.length).map { l.item(it) as Element } }
                    .filter { it.a(PRES, "preset-class") in setOf("entrance", "exit", "emphasis", "media-call") }.mapNotNull { fx ->
                        val kids = fx.kids()
                        val target = kids.firstNotNullOfOrNull { it.a(SMIL, "targetElement") }?.let(named::get) ?: return@mapNotNull null
                        // played there and back (autoReverse): twice its length
                        val ms = kids.mapNotNull { k -> secs(k.a(SMIL, "dur"))?.let { if (k.a(SMIL, "autoReverse") == "true") it * 2 else it } }.filter { it > 0.01f }.maxOrNull()?.let { (it * 1000).toInt() } ?: 500
                        val id = fx.a(PRES, "preset-id").orEmpty()
                        // when it starts within its click: its par's begin (LibreOffice groups effects by it)
                        val at = secs((fx.parentNode as? Element)?.a(SMIL, "begin"))?.let { (it * 1000).toInt() } ?: 0
                        val kind = if (fx.a(PRES, "preset-class") == "media-call") "play" else if (fx.a(PRES, "preset-class") == "emphasis") (if ("spin" in id) "spin" else if ("grow" in id) "grow" else "pulse")
                            else if ("appear" in id) "appear" else if ("fly" in id) "fly" else if ("wipe" in id) "wipe" else if ("zoom" in id) "zoom" else "fade"
                        val start = when (fx.a(PRES, "node-type")) { "with-previous" -> 'w'; "after-previous" -> 'a'; else -> 'c' }
                        SlideEffect(target, kind, OdpEffects.side(fx.a(PRES, "preset-sub-type")), at, ms, exit = fx.a(PRES, "preset-class") == "exit", start = start)
                    }
                if (effects.isEmpty()) null else SlideStep(click.a(SMIL, "begin") != "next", effects)
            }
            // its part: its name as a link names it ("#page3"), so a show follows one
            val own = page.kids().withIndex().mapNotNull { (k, e) -> FOOT[e.a(PRES, "class")]?.let { "o$k" to it } }.toMap() + footed.mapNotNull { it.id }.associateWith { it.removePrefix("footer.") }
            return Slide(items + footed, background, notes, prop("drawing-page", dp, "d.visibility") == "hidden", page.a(DRAW, "name")?.let { "#$it" } ?: "", transition = transition, steps = steps,
                footers = footers, footerIds = own, titleSlide = page.kids().any { it.a(PRES, "class") == "subtitle" },
                // Impress's comments: an annotation each, where its marker stands, who and when (Dublin Core)
                comments = page.kids().filter { it.localName == "annotation" }.mapIndexed { n, an ->
                    fun dc(k: String) = an.kids().firstOrNull { it.localName == k && it.namespaceURI == "http://purl.org/dc/elements/1.1/" }?.textContent
                    SlideComment(DocComment("odp-$index-$n", dc("creator").orEmpty(), dc("date"), an.kids().filter { it.localName == "p" }.joinToString("\n") { it.textContent },
                        initials = an.kids().firstOrNull { it.localName == "creator-initials" }?.textContent), pt(an.a(SVG, "x")) ?: 0f, pt(an.a(SVG, "y")) ?: 0f) },
                backPicture = backPicture, backGradient = backGradient, placeholders = page.kids().withIndex().filter { it.value.a(PRES, "class") != null }.map { "o${it.index}" }.toSet())
        }
        val slides = pages.mapIndexed { index, page -> pageSlide(index, page, page.a(DRAW, "master-page-name")) }
        // New slide's layouts: each master page (LibreOffice's copy of a PowerPoint layout is one), its title and text
        // placeholders as a page's empty boxes (grey hints); a deck of one master gets Title only and Blank as well
        val presStyles = styles?.getElementsByTagNameNS(STYLE, "style")?.let { l -> (0 until l.length).map { l.item(it) as Element } }.orEmpty()
            .filter { it.a(STYLE, "family") == "presentation" }.mapNotNull { it.a(STYLE, "name") }.toSet()
        fun layout(m: Element, name: String, kinds: Set<String>): SlideLayout {
            val mn = m.a(STYLE, "name").orEmpty()
            val boxes = m.kids().filter { it.localName == "frame" && it.a(PRES, "class") in kinds }
                .let { f -> val rest = f.filter { it.a(PRES, "class") != "title" }
                    // the layout's own boxes (a PowerPoint layout's, moved by it) before LibreOffice's generic placeholder
                    listOfNotNull(f.firstOrNull { it.a(PRES, "class") == "title" }) + rest.filter { it.a(PRES, "placeholder") != "true" }.ifEmpty { rest }.take(2) }
            val items = ArrayList<SlideItem>().also { shapes(m, it, true) }
            fun hint(words: String, size: Float, align: DocAlign) = SlideText(listOf(SlidePara.plain(words, size, 0x888888, align = align)), 'm', floatArrayOf(7.2f, 3.6f, 7.2f, 3.6f))
            boxes.forEachIndexed { k, f -> val b = box(f); val title = f.a(PRES, "class") == "title"
                items += SlideItem.Shape(b[0], b[1], b[2], b[3], 0f, null, hint(if (title) Slides.hintTitle else Slides.hintText, if (title) 32f else 20f, if (title) DocAlign.Center else DocAlign.Start), id = "o$k", hint = true).also { it.anim = "o$k" } }
            // the page saved: these boxes empty, in the master's places and styles
            val frames = boxes.joinToString("") { f ->
                val cls = f.a(PRES, "class") ?: "outline"
                val st = "$mn-${if (cls == "outline") "outline1" else cls}".takeIf { it in presStyles }?.let { """ presentation:style-name="$it"""" }.orEmpty()
                """<draw:frame$st draw:layer="layout" svg:width="${f.a(SVG, "width")}" svg:height="${f.a(SVG, "height")}" svg:x="${f.a(SVG, "x")}" svg:y="${f.a(SVG, "y")}" presentation:class="$cls" presentation:placeholder="true"><draw:text-box/></draw:frame>"""
            }
            val xml = """<draw:page xmlns:draw="$DRAW" xmlns:svg="$SVG" xmlns:presentation="$PRES" draw:master-page-name="$mn">$frames</draw:page>"""
            val bg = prop("drawing-page", m.a(DRAW, "style-name"), "d.fill-color")?.let(::hex) ?: 0xFFFFFFFF.toInt()
            return SlideLayout(name, mn, Slide(items, bg, ""), xml.toByteArray())
        }
        val placeholders = setOf("title", "outline", "subtitle")
        val kinds = masters.values.filterNotNull().map { m -> layout(m, m.a(STYLE, "display-name") ?: odfDisplayName(m.a(STYLE, "name").orEmpty()), placeholders) }.toMutableList()
        if (masters.size == 1) masters.values.first()?.let { m -> kinds += layout(m, "Title Only", setOf("title")); kinds += layout(m, "Blank", emptySet()) }
        val again = HashMap<Triple<Int, Int?, Boolean>, Slide>()
        val settings = content.getElementsByTagNameNS(PRES, "settings").let { l -> (0 until l.length).map { l.item(it) as Element } }
        val endless = settings.any { it.a(PRES, "endless") == "true" }
        val noAnimation = settings.any { it.a(PRES, "animations") == "disabled" }; val manual = settings.any { it.a(PRES, "force-manual") == "true" }
        // References: Tetra's sources in the deck's own properties (meta.xml's user-defined, which LibreOffice keeps), in Word's form
        val meta = part("meta.xml")?.let { runCatching { OfficeFormats.parse(it).documentElement }.getOrNull() }
        fun userDefined(n: String) = meta?.getElementsByTagNameNS("urn:oasis:names:tc:opendocument:xmlns:meta:1.0", "user-defined")?.let { l -> (0 until l.length).map { l.item(it) as Element } }
            ?.firstOrNull { it.getAttributeNS("urn:oasis:names:tc:opendocument:xmlns:meta:1.0", "name") == n }?.textContent
        val sources = userDefined(SOURCES_NAME)?.let { WordSources.read(mapOf("customXml/item1.xml" to it.toByteArray())) }.orEmpty()
        Deck(width, height, slides, kinds, loop = endless, noAnimation = noAnimation, manual = manual, sources = sources, citeStyle = userDefined(STYLE_NAME)?.let { n -> CiteStyle.entries.firstOrNull { it.name == n } } ?: CiteStyle.APA, relaid = { n, k, reset -> synchronized(again) { again.getOrPut(Triple(n, k, reset)) { val name = k?.let { kinds[it].part } ?: pages[n].a(DRAW, "master-page-name")
            pageSlide(n, if (reset) (pages[n].cloneNode(true) as Element).also { resetPage(it, masters[name]) } else pages[n], name) } } })
    }
}

/**
 * PowerPoint's transitions and effect sides as LibreOffice names them (its own .pptx import's mapping, so a deck keeps
 * them between the two): push ↔ pushWipe, cover ↔ slideWipe, uncover (pull) ↔ slideWipe reversed, wipe ↔ barWipe, fade.
 */
internal object OdpEffects {
    private fun from(dir: String?) = when (dir) { "r" -> "fromRight"; "u" -> "fromTop"; "d" -> "fromBottom"; else -> "fromLeft" }
    /** [kind] and [dir] as smil's type, subtype and whether it runs reversed; null: LibreOffice has none (cut). */
    fun smil(kind: String, dir: String?): Triple<String, String, Boolean>? = when (kind) {
        "fade" -> Triple("fade", "crossfade", false)
        "push" -> Triple("pushWipe", when (dir) { "u" -> "fromBottom"; "d" -> "fromTop"; "r" -> "fromLeft"; else -> "fromRight" }, false)
        "cover" -> Triple("slideWipe", from(dir), false)
        "pull" -> Triple("slideWipe", from(dir), true)
        "wipe" -> when (dir) { "u" -> Triple("barWipe", "topToBottom", true); "d" -> Triple("barWipe", "topToBottom", false); "r" -> Triple("barWipe", "leftToRight", false); else -> Triple("barWipe", "leftToRight", true) }
        else -> null
    }
    /** LibreOffice's transition back as PowerPoint's kind and side (another of its own kinds: shown as a fade). */
    fun kind(type: String, subtype: String?, reverse: Boolean): Pair<String, String?>? {
        fun dir(s: String?) = when (s) { "fromRight" -> "r"; "fromTop" -> "u"; "fromBottom" -> "d"; else -> "l" }
        return when (type) {
            "none" -> null
            "fade" -> "fade" to null
            "pushWipe" -> "push" to when (subtype) { "fromBottom" -> "u"; "fromTop" -> "d"; "fromLeft" -> "r"; else -> "l" }
            "slideWipe" -> (if (reverse) "pull" else "cover") to dir(subtype)
            "barWipe" -> "wipe" to if (subtype == "topToBottom") (if (reverse) "u" else "d") else (if (reverse) "l" else "r")
            else -> "fade" to null
        }
    }
    /** An effect's side as LibreOffice's sub-type ("from-left": where a fly comes from, or goes to). */
    fun subtype(from: Char) = when (from) { 'l' -> "from-left"; 'r' -> "from-right"; 'u' -> "from-top"; else -> "from-bottom" }
    fun side(subtype: String?) = when (subtype) { "from-left" -> 'l'; "from-right" -> 'r'; "from-top" -> 'u'; else -> 'd' }
}
