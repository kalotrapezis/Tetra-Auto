package com.kalotrapezis.drive

import org.w3c.dom.Element
import java.io.File
import java.util.Locale

/**
 * Editing a LibreOffice deck (.odp), as [SlidesEdit] does a .pptx (asked 16 Oct: the three apps alike): the pages in
 * their new order (a duplicate a copy of its page, a deleted one gone), hidden or shown, words typed, the page's own
 * items moved, sized, coloured or deleted, pictures and shapes put on — written into content.xml (and the pictures with
 * the manifest), speaker notes in the page's notes, every other part as it was. Items are found by their place on the page ([SlidesOdp]'s "o3", "o3.1").
 */
internal object SlidesOdpEdit {
    private const val OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    private const val DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
    private const val SVG = "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"
    private const val PRES = "urn:oasis:names:tc:opendocument:xmlns:presentation:1.0"
    private const val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    private const val STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    private const val FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
    private const val XLINK = "http://www.w3.org/1999/xlink"
    private const val XML = "http://www.w3.org/XML/1998/namespace"
    private const val MANIFEST = "urn:oasis:names:tc:opendocument:xmlns:manifest:1.0"
    private const val TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
    private const val ANIM = "urn:oasis:names:tc:opendocument:xmlns:animation:1.0"
    private const val SMIL = "urn:oasis:names:tc:opendocument:xmlns:smil-compatible:1.0"
    private const val LOEXT = "urn:org:documentfoundation:names:experimental:office:xmlns:loext:1.0"

    private fun Element.kids(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
    private fun Element.a(ns: String, n: String): String? = getAttributeNS(ns, n).takeIf { it.isNotEmpty() }
    private fun Element.all(): List<Element> = kids().flatMap { listOf(it) + it.all() }
    private fun cm(pt: Float) = String.format(Locale.ROOT, "%.4fcm", pt / 72f * 2.54f)
    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)
    private fun darker(c: Int): Int {
        fun ch(s: Int) = (((c shr s) and 255) * 0.7f).toInt() shl s
        return (c and 0xFF000000.toInt()) or ch(16) or ch(8) or ch(0)
    }
    private fun xml(doc: org.w3c.dom.Document): ByteArray = SlidesEdit.domXml(doc)

    fun save(source: File, deck: Deck, edit: SlidesEdit.Edit, target: File) {
        val zip = java.util.zip.ZipFile(source)
        fun read(n: String) = zip.getEntry(n)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
        val out = HashMap<String, ByteArray?>()
        val doc = OfficeFormats.parse(read("content.xml") ?: error("Not a presentation."))
        val root = doc.documentElement
        val pres = root.getElementsByTagNameNS(OFFICE, "presentation").item(0) as Element? ?: error("Not a presentation.")
        val pages = pres.kids().filter { it.localName == "page" && it.namespaceURI == DRAW }
        val autos = root.kids().firstOrNull { it.localName == "automatic-styles" }
            ?: doc.createElementNS(OFFICE, "office:automatic-styles").also { a -> root.insertBefore(a, root.kids().firstOrNull { it.localName == "body" }) }
        val names = root.all().flatMap { listOfNotNull(it.a(STYLE, "name"), it.a(DRAW, "name"), it.a(XML, "id"), it.a(DRAW, "id")) }.toMutableSet()
        fun fresh(prefix: String) = generateSequence(1) { it + 1 }.map { "$prefix$it" }.first { it !in names }.also { names += it }

        // a style's properties in the schema's order
        val order = listOf("drawing-page-properties", "graphic-properties", "paragraph-properties", "text-properties")
        fun props(st: Element, kind: String): Element = st.kids().firstOrNull { it.localName == kind } ?: doc.createElementNS(STYLE, "style:$kind").also { p ->
            st.insertBefore(p, st.kids().firstOrNull { order.indexOf(it.localName) > order.indexOf(kind) }) }
        val made = HashMap<String, String>()
        val dashes = LinkedHashSet<String>() // the outlines' dashes used, defined in styles.xml
        val masterPages by lazy { read("styles.xml")?.let { runCatching { OfficeFormats.parse(it) }.getOrNull() }?.getElementsByTagNameNS(STYLE, "master-page")
            ?.let { l -> (0 until l.length).map { l.item(it) as Element } }.orEmpty().associateBy { it.a(STYLE, "name") } }
        /** A new automatic style of [family] for an element styled [name]: a copy of it when it is content.xml's own, else one on it; [key] the change, made once. */
        fun derive(family: String, name: String?, key: String, change: (Element) -> Unit): String = made.getOrPut("$family|$name|$key") {
            val old = name?.let { nm -> autos.kids().firstOrNull { it.localName == "style" && it.a(STYLE, "name") == nm && it.a(STYLE, "family") == family } }
            val st = old?.cloneNode(true) as Element? ?: doc.createElementNS(STYLE, "style:style").also { s ->
                s.setAttributeNS(STYLE, "style:family", family); if (name != null) s.setAttributeNS(STYLE, "style:parent-style-name", name) }
            val nm = fresh(when (family) { "drawing-page" -> "dpT"; "presentation" -> "prT"; "text" -> "TT"; else -> "grT" })
            st.setAttributeNS(STYLE, "style:name", nm); change(st); autos.appendChild(st); nm
        }

        /** The words' holder of a frame (its text box) or shape (itself). */
        fun holder(el: Element) = if (el.localName == "frame") el.kids().firstOrNull { it.localName == "text-box" } else el
        fun paras(h: Element) = h.all().filter { it.namespaceURI == TEXT && (it.localName == "p" || it.localName == "h") }
        /** Where some letters stood: the spans and links round them (copies, outermost first), or a [field] (a copy) they were. */
        class Look(val chain: List<Element>, val field: Element? = null)
        /** A paragraph's letters, each in its look (one [Look] per span or link, so letters of one stay together). */
        fun letters(p: Element): List<Pair<Look?, String>> {
            val out = ArrayList<Pair<Look?, String>>(); val looks = HashMap<Element, Look>()
            fun look(chain: List<Element>) = looks.getOrPut(chain.lastOrNull() ?: p) { Look(chain.map { it.cloneNode(false) as Element }) }
            fun walk(n: org.w3c.dom.Node, chain: List<Element>) {
                val e = n as? Element
                when {
                    n is org.w3c.dom.Text -> if (n.data.isNotEmpty()) out += look(chain) to n.data.replace('\n', ' ')
                    e == null || e.namespaceURI != TEXT -> Unit
                    e.localName == "s" -> out += look(chain) to " ".repeat(e.a(TEXT, "c")?.toIntOrNull() ?: 1)
                    e.localName == "tab" -> out += look(chain) to "\t"
                    e.localName == "line-break" -> out += look(chain) to LINE_BREAK.toString()
                    e.localName == "span" || e.localName == "a" -> for (i in 0 until e.childNodes.length) walk(e.childNodes.item(i), chain + e)
                    e.textContent.isNotEmpty() -> out += Look(chain.map { it.cloneNode(false) as Element }, e.cloneNode(true) as Element) to e.textContent // a field: slide number, date…
                }
            }
            for (i in 0 until p.childNodes.length) walk(p.childNodes.item(i), emptyList())
            return out
        }
        /** [p]'s words made [runs]: each in its spans and links again, a field kept whole stays the field; spaces, tabs and line breaks as ODF writes them. */
        fun words(p: Element, runs: List<Pair<Look?, String>>) {
            while (p.firstChild != null) p.removeChild(p.firstChild)
            fun into(look: Look?): Element = (look?.chain ?: emptyList()).fold(p) { parent, c -> (c.cloneNode(false) as Element).also { parent.appendChild(it) } }
            fun write(to: Element, w: String) {
                val plain = StringBuilder()
                fun flush() { if (plain.isNotEmpty()) to.appendChild(doc.createTextNode(plain.toString())); plain.clear() }
                var k = 0
                while (k < w.length) {
                    val c = w[k]
                    when {
                        c == '\t' -> { flush(); to.appendChild(doc.createElementNS(TEXT, "text:tab")) }
                        c == LINE_BREAK -> { flush(); to.appendChild(doc.createElementNS(TEXT, "text:line-break")) }
                        // spaces after the first (or at the start) would fold into one: written as text:s
                        c == ' ' && (k == 0 || w[k - 1] == ' ') -> { var n = 1; while (k + n < w.length && w[k + n] == ' ') n++
                            flush(); to.appendChild(doc.createElementNS(TEXT, "text:s").also { if (n > 1) it.setAttributeNS(TEXT, "text:c", "$n") }); k += n; continue }
                        else -> plain.append(c)
                    }
                    k++
                }
                flush()
            }
            for ((look, w) in runs) {
                if (w.isEmpty()) continue
                val whole = look?.field?.textContent?.takeIf { it.isNotEmpty() && it in w }
                if (whole == null) { write(into(look), w); continue }
                val i = w.indexOf(whole)
                if (i > 0) write(into(Look(look.chain)), w.substring(0, i))
                into(Look(look.chain)).appendChild(look.field.cloneNode(true))
                if (i + whole.length < w.length) write(into(Look(look.chain)), w.substring(i + whole.length))
            }
        }
        fun type(el: Element, lines: List<String>) {
            val h = holder(el) ?: return
            var ps = paras(h)
            if (ps.isEmpty()) {
                val p = doc.createElementNS(TEXT, "text:p")
                // an empty text placeholder: its lines a list, as LibreOffice's bullets are
                val unit = if (el.a(PRES, "class") == "outline") doc.createElementNS(TEXT, "text:list").also { l -> l.appendChild(doc.createElementNS(TEXT, "text:list-item").also { it.appendChild(p) }) } else p
                h.insertBefore(unit, h.kids().firstOrNull { it.localName == "enhanced-geometry" }); ps = paras(h)
            }
            // each line's letters in the looks of the paragraph it came from (Words' text boxes' way)
            // shortcut: a line keeps the paragraph (and list level) at its place, not the one it came from; matters only when lines move
            val laid = PictureXml.relaid<Element, Look>(ps.map { it to letters(it) }, lines, ps.last(), null).map { it.second }
            lines.forEachIndexed { i, line ->
                val p = ps.getOrNull(i) ?: run {
                    // another paragraph: a copy of the last (in a list, its list item)
                    val last = ps.last(); val item = (last.parentNode as? Element)?.takeIf { it.localName == "list-item" }
                    val unit = item ?: last
                    val copy = unit.cloneNode(true) as Element
                    unit.parentNode.insertBefore(copy, unit.nextSibling)
                    (if (item != null) paras(copy).first() else copy).also { ps = ps + it }
                }
                words(p, laid[i])
            }
            for (p in ps.drop(lines.size)) {
                val item = (p.parentNode as? Element)?.takeIf { it.localName == "list-item" && paras(it).size == 1 }
                val gone = item ?: p
                val list = (gone.parentNode as? Element)
                list?.removeChild(gone)
                if (list != null && list.localName == "list" && list.kids().none { it.localName == "list-item" }) list.parentNode.removeChild(list)
            }
            if (lines.any { it.isNotBlank() }) el.removeAttributeNS(PRES, "placeholder")
        }
        /** A face named in content.xml's font declarations (style:font-name points at one), declared when it is not. */
        fun face(name: String): String {
            val decls = root.kids().firstOrNull { it.localName == "font-face-decls" }
                ?: doc.createElementNS(OFFICE, "office:font-face-decls").also { d -> root.insertBefore(d, root.kids().firstOrNull { it.localName in setOf("automatic-styles", "body") }) }
            decls.kids().firstOrNull { it.a(STYLE, "name") == name }?.let { return name }
            decls.appendChild(doc.createElementNS(STYLE, "style:font-face").also { it.setAttributeNS(STYLE, "style:name", name); it.setAttributeNS(SVG, "svg:font-family", if (' ' in name) "'$name'" else name) })
            return name
        }
        /** A list style of LibreOffice's for Home's Bullets ('b') or Numbering ('1'): its first levels hanging as PowerPoint's (0.95 cm). */
        fun listStyle(kind: Char, scheme: Int? = null): String = made.getOrPut("list $kind $scheme") {
            val nm = fresh("LT")
            autos.appendChild(doc.importNode(OfficeFormats.parse(buildString {
                append("""<text:list-style xmlns:text="$TEXT" xmlns:style="$STYLE" xmlns:fo="$FO" style:name="$nm">""")
                for (lv in 1..9) {
                    val props = """<style:list-level-properties text:space-before="${String.format(Locale.ROOT, "%.2f", (lv - 1) * 0.95)}cm" text:min-label-width="0.95cm"/>"""
                    append(if (kind == 'b') """<text:list-level-style-bullet text:level="$lv" text:bullet-char="•">$props<style:text-properties fo:font-family="Arial" style:use-window-font-color="true" fo:font-size="100%"/></text:list-level-style-bullet>"""
                        else """<text:list-level-style-number text:level="$lv" style:num-format="${scheme?.let { SlideBullet.form(LIST_SCHEMES[it].levels[lv - 1].first) } ?: '1'}" style:num-suffix=".">$props<style:text-properties fo:font-size="100%" style:use-window-font-color="true"/></text:list-level-style-number>""")
                }
                append("</text:list-style>")
            }.toByteArray()).documentElement, true))
            nm
        }
        /** Home's looks [l] on the words of shape [s]: every span (words outside one put in one), each paragraph, its list, the shape's anchor and autofit. */
        fun words(s: Element, l: SlidesEdit.Look) {
            val h = holder(s) ?: return
            val key = "words ${l.copy(fill = null, line = null)}"
            fun text(st: Element) {
                val t = props(st, "text-properties")
                l.font?.let { f -> t.setAttributeNS(STYLE, "style:font-name", face(f)); t.removeAttributeNS(FO, "font-family") }
                l.size?.let { t.setAttributeNS(FO, "fo:font-size", String.format(Locale.ROOT, "%.1fpt", it)) }
                l.bold?.let { t.setAttributeNS(FO, "fo:font-weight", if (it) "bold" else "normal") }
                l.italic?.let { t.setAttributeNS(FO, "fo:font-style", if (it) "italic" else "normal") }
                l.underline?.let { t.setAttributeNS(STYLE, "style:text-underline-style", if (it) "solid" else "none"); if (it) { t.setAttributeNS(STYLE, "style:text-underline-width", "auto"); t.setAttributeNS(STYLE, "style:text-underline-color", "font-color") } }
                l.strike?.let { t.setAttributeNS(STYLE, "style:text-line-through-style", if (it) "solid" else "none") }
                // as LibreOffice's Format › Text › Superscript and Subscript
                l.script?.let { t.setAttributeNS(STYLE, "style:text-position", when { it > 0 -> "super 58%"; it < 0 -> "sub 58%"; else -> "0% 100%" }) }
                l.text?.let {
                    t.setAttributeNS(FO, "fo:color", hex(it)); t.setAttributeNS(STYLE, "style:use-window-font-color", "false") // else LibreOffice paints the window's colour
                    // and a theme colour it named before goes, or LibreOffice keeps writing that one (its .pptx: dk1)
                    listOf("color-type", "theme-type", "color-lum-mod", "color-lum-off").forEach { n -> t.removeAttributeNS(LOEXT, n) }
                    t.kids().filter { k -> k.localName == "char-complex-color" }.forEach { k -> t.removeChild(k) }
                }
                l.highlight?.let { t.setAttributeNS(FO, "fo:background-color", if (it == SlidesEdit.NONE) "transparent" else hex(it)) }
                // WordArt: hollow letters and a shadow, as LibreOffice has them (no glow on letters)
                l.effects?.let { fx -> t.setAttributeNS(STYLE, "style:text-outline", "${fx.outline != null}"); t.setAttributeNS(FO, "fo:text-shadow", if (fx.shadow) "1pt 1pt" else "none") }
                l.letterSpacing?.let { t.setAttributeNS(FO, "fo:letter-spacing", if (it == 0f) "normal" else String.format(Locale.ROOT, "%.2fpt", it)) }
            }
            val styled = l.font != null || l.size != null || l.bold != null || l.italic != null || l.underline != null || l.strike != null  || l.text != null || l.highlight != null || l.effects != null || l.script != null || l.letterSpacing != null
            for (p in paras(h)) {
                if (l.cleared) {
                    // the letters' own looks gone: the spans' words put back in the paragraph, its own text looks taken off
                    for (span in p.all().filter { it.localName == "span" && it.namespaceURI == TEXT }) { while (span.firstChild != null) span.parentNode.insertBefore(span.firstChild, span); span.parentNode.removeChild(span) }
                    p.a(TEXT, "style-name")?.let { n -> p.setAttributeNS(TEXT, "text:style-name", derive("paragraph", n, "cleared") { st -> st.kids().filter { it.localName == "text-properties" }.forEach { st.removeChild(it) } }) }
                }
                if (styled) {
                    for (span in p.all().filter { it.localName == "span" && it.namespaceURI == TEXT }) span.setAttributeNS(TEXT, "text:style-name", derive("text", span.a(TEXT, "style-name"), key, ::text))
                    for (t in (0 until p.childNodes.length).map { p.childNodes.item(it) }.filter { it is org.w3c.dom.Text || (it is Element && it.localName in setOf("s", "tab", "line-break", "a")) }) {
                        val span = doc.createElementNS(TEXT, "text:span")
                        span.setAttributeNS(TEXT, "text:style-name", derive("text", null, key, ::text))
                        p.replaceChild(span, t); span.appendChild(t)
                    }
                }
                val first = paras(h).indexOfFirst { it.textContent.isNotBlank() }
                val dropped = l.drop?.takeIf { first >= 0 }?.let { paras(h).indexOf(p) - first }?.takeIf { it == 0 || it == 1 && l.dropRest }
                if (l.align != null || l.spacing != null || l.para != null || dropped != null) p.setAttributeNS(TEXT, "text:style-name", derive("paragraph", p.a(TEXT, "style-name"), "$key $dropped") { st ->
                    val pp = props(st, "paragraph-properties")
                    // dropped: the lines beside the letter stand in, with no space under them; the rest goes on under them
                    if (dropped != null) { pp.setAttributeNS(FO, "fo:text-indent", "0cm")
                        if (dropped == 0) { pp.setAttributeNS(FO, "fo:margin-left", String.format(Locale.ROOT, "%.3fcm", l.drop!! * 2.54f / 72)); pp.setAttributeNS(FO, "fo:margin-bottom", "0cm") }
                        else pp.setAttributeNS(FO, "fo:margin-top", "0cm") }
                    l.align?.let { pp.setAttributeNS(FO, "fo:text-align", when (it) { DocAlign.Center -> "center"; DocAlign.End -> "end"; DocAlign.Justify -> "justify"; else -> "start" }) }
                    l.spacing?.let { pp.setAttributeNS(FO, "fo:line-height", "${Math.round(it * 100)}%") }
                    // Words' paragraph page, as LibreOffice keeps a paragraph's: margins above and below, line height, indents
                    l.para?.let { q ->
                        fun pt(v: Float) = String.format(Locale.ROOT, "%.2fpt", v)
                        fun on(k: String) = l.paraParts.isEmpty() || k in l.paraParts
                        if (on("before")) pp.setAttributeNS(FO, "fo:margin-top", pt(q.before)); if (on("after")) pp.setAttributeNS(FO, "fo:margin-bottom", pt(q.after))
                        if (on("line")) pp.setAttributeNS(FO, "fo:line-height", q.lineExact?.let(::pt) ?: "${Math.round(q.lineMultiple * 100)}%")
                        if (on("left")) pp.setAttributeNS(FO, "fo:margin-left", pt(q.left)); if (on("right")) pp.setAttributeNS(FO, "fo:margin-right", pt(q.right))
                        if (on("first")) pp.setAttributeNS(FO, "fo:text-indent", pt(q.firstLine)) }
                })
            }
            // WordArt's bend on a shape: LibreOffice's fontwork geometry (straight: a rectangle again); a frame keeps its words in lines
            // shortcut: a frame (a text box of the file's own) is not made fontwork, which needs a custom shape
            l.warp?.let { w -> s.kids().firstOrNull { it.localName == "enhanced-geometry" }?.let { g ->
                val bend = TextWarp.entries.firstOrNull { it.word == w }
                g.attributes.let { at -> (0 until at.length).map { at.item(it) as org.w3c.dom.Attr }.filter { it.namespaceURI == DRAW && it.localName != "viewBox" } }.forEach { g.removeAttributeNode(it) }
                while (g.firstChild != null) g.removeChild(g.firstChild)
                if (bend == null) g.setAttributeNS(DRAW, "draw:type", "rectangle")
                // as Impress writes one ("path": the words stretched along the whole bend)
                else { g.setAttributeNS(DRAW, "draw:text-path", "true"); g.setAttributeNS(DRAW, "draw:text-path-mode", "path"); g.setAttributeNS(DRAW, "draw:text-path-scale", "path"); g.setAttributeNS(DRAW, "draw:type", bend.fontwork) }
            } }
            // Insert › Link: each paragraph's words in a link (a slide's named once every page is in place); "" a link taken off
            l.link?.let { to -> for (p in paras(h)) {
                for (a in p.all().filter { it.localName == "a" && it.namespaceURI == TEXT }) { while (a.firstChild != null) a.parentNode.insertBefore(a.firstChild, a); a.parentNode.removeChild(a) }
                if (to.isEmpty() || p.textContent.isBlank()) continue
                // inside each span, as LibreOffice's own (Impress keeps no span inside a link)
                fun texts(n: org.w3c.dom.Node): List<org.w3c.dom.Text> = if (n is org.w3c.dom.Text) listOf(n) else (0 until n.childNodes.length).flatMap { texts(n.childNodes.item(it)) }
                for (t in texts(p).filter { it.data.isNotEmpty() }) {
                    val a = doc.createElementNS(TEXT, "text:a").also { it.setAttributeNS(XLINK, "xlink:type", "simple"); it.setAttributeNS(XLINK, "xlink:href", if (to.startsWith("slide:")) "#tetra-" + to else to) }
                    t.parentNode.replaceChild(a, t); a.appendChild(t)
                }
            } }
            // Insert › Drop cap, raised: the first letter in a span of its own, its size (and face, colour); 0: a raised one plain again
            l.raise?.let { size -> paras(h).firstOrNull { it.textContent.isNotBlank() }?.let { p ->
                fun texts(n: org.w3c.dom.Node): List<org.w3c.dom.Text> = if (n is org.w3c.dom.Text) listOf(n) else (0 until n.childNodes.length).flatMap { texts(n.childNodes.item(it)) }
                val t = texts(p).firstOrNull { it.data.isNotEmpty() } ?: return@let
                val holder = t.parentNode as Element
                if (size > 0f) {
                    val n = t.data.offsetByCodePoints(0, 1)
                    val rest = t.splitText(n)
                    val span = doc.createElementNS(TEXT, "text:span")
                    span.setAttributeNS(TEXT, "text:style-name", derive("text", holder.takeIf { it.localName == "span" }?.a(TEXT, "style-name"), "raise $size ${l.raiseFont} ${l.raiseColor}") { st ->
                        val tp = props(st, "text-properties"); tp.setAttributeNS(FO, "fo:font-size", String.format(Locale.ROOT, "%.1fpt", size))
                        l.raiseFont?.let { f -> tp.setAttributeNS(STYLE, "style:font-name", face(f)); tp.removeAttributeNS(FO, "font-family") }
                        l.raiseColor?.let { c -> tp.setAttributeNS(FO, "fo:color", hex(c)); tp.setAttributeNS(STYLE, "style:use-window-font-color", "false") }
                    })
                    holder.insertBefore(span, t); span.appendChild(t)
                    if (rest.data.isEmpty()) holder.removeChild(rest)
                } else if (holder.localName == "span" && holder.textContent.codePointCount(0, holder.textContent.length) == 1) {
                    // plain again: the letter in the look of the words after it
                    val outer = holder.parentNode as? Element
                    if (outer?.localName == "span") { while (holder.firstChild != null) outer.insertBefore(holder.firstChild, holder); outer.removeChild(holder) } // Tetra's, inside the words' own
                    else (holder.nextSibling as? Element)?.takeIf { it.localName == "span" }?.a(TEXT, "style-name")?.let { holder.setAttributeNS(TEXT, "text:style-name", it) }
                }
            } }
            when (if (l.scheme != null) '1' else l.list) {
                // none: the list's paragraphs taken out of it, in their place
                'n' -> for (list in h.kids().filter { it.localName == "list" && it.namespaceURI == TEXT }) {
                    for (p in paras(list)) h.insertBefore(p, list)
                    h.removeChild(list)
                }
                'b', '1' -> {
                    val style = listStyle(if (l.scheme != null) '1' else l.list!!, l.scheme)
                    h.kids().filter { it.localName == "list" && it.namespaceURI == TEXT }.forEach { it.setAttributeNS(TEXT, "text:style-name", style) }
                    // paragraphs outside a list: each an item of one
                    for (p in h.kids().filter { it.namespaceURI == TEXT && it.localName in setOf("p", "h") }) {
                        val list = doc.createElementNS(TEXT, "text:list").also { it.setAttributeNS(TEXT, "text:style-name", style) }
                        val item = doc.createElementNS(TEXT, "text:list-item")
                        h.insertBefore(list, p); list.appendChild(item); item.appendChild(p)
                    }
                }
            }
            if (l.anchor != null || l.autofit != null || l.insets != null || l.wrap != null || l.cols != null || l.vert != null) {
                val pr = s.a(PRES, "style-name")
                val nm = derive(if (pr != null) "presentation" else "graphic", pr ?: s.a(DRAW, "style-name"), "box ${l.anchor} ${l.autofit} ${l.insets} ${l.wrap} ${l.cols} ${l.colGap} ${l.vert}") { st ->
                    val g = props(st, "graphic-properties")
                    // Layout: LibreOffice's Text attributes — spacing to borders, word wrap, columns
                    l.insets?.let { m -> listOf("left", "top", "right", "bottom").forEachIndexed { i, side -> g.setAttributeNS(FO, "fo:padding-$side", String.format(Locale.ROOT, "%.2fpt", m[i])) } }
                    l.wrap?.let { g.setAttributeNS(FO, "fo:wrap-option", if (it) "wrap" else "no-wrap") }
                    l.cols?.let { n -> g.kids().filter { it.localName == "columns" }.forEach { g.removeChild(it) }
                        if (n > 1) g.appendChild(st.ownerDocument.createElementNS(STYLE, "style:columns").apply { setAttributeNS(FO, "fo:column-count", "$n"); setAttributeNS(FO, "fo:column-gap", String.format(Locale.ROOT, "%.2fpt", l.colGap ?: 0f)) }) }
                    l.anchor?.let { g.setAttributeNS(DRAW, "draw:textarea-vertical-align", when (it) { 'c' -> "middle"; 'b' -> "bottom"; else -> "top" }) }
                    // Text direction: LibreOffice's own (loext:writing-mode), as it writes PowerPoint's vert and vert270
                    // (and on its paragraphs' properties, which LibreOffice draws by: a copied style's own "lr-tb" there would win)
                    l.vert?.let { v -> val LOEXT = "urn:org:documentfoundation:names:experimental:office:xmlns:loext:1.0"; val mode = when (v) { 90 -> "tb-rl90"; 270 -> "bt-lr"; else -> "lr-tb" }
                        g.setAttributeNS(LOEXT, "loext:writing-mode", mode)
                        props(st, "paragraph-properties").apply { removeAttributeNS(STYLE, "writing-mode"); setAttributeNS(LOEXT, "loext:writing-mode", mode) } }
                    l.autofit?.let { f ->
                        g.setAttributeNS(DRAW, "draw:auto-grow-height", if (f == 'g') "true" else "false")
                        g.setAttributeNS(DRAW, "draw:fit-to-size", "false")
                        if (f == 's') g.setAttributeNS(STYLE, "style:shrink-to-fit", "true") else g.removeAttributeNS(STYLE, "shrink-to-fit")
                    }
                }
                if (pr != null) s.setAttributeNS(PRES, "presentation:style-name", nm) else s.setAttributeNS(DRAW, "draw:style-name", nm)
            }
        }
        // pictures put on: each a part of its own, listed in the manifest
        val manifest = read("META-INF/manifest.xml")?.let(OfficeFormats::parse)
        var nextPicture = (zip.entries().asSequence().mapNotNull { Regex("""^Pictures/tetra(\d+)\.""").find(it.name)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0) + 1
        fun picture(a: SlidesEdit.Added): String {
            val ext = when (a.ext) { "jpeg", "jpg" -> "jpg"; "gif" -> "gif"; else -> "png" }
            val path = "Pictures/tetra${nextPicture++}.$ext"
            out[path] = File(a.image!!).readBytes()
            manifest?.documentElement?.appendChild(manifest.createElementNS(MANIFEST, "manifest:file-entry").apply {
                setAttributeNS(MANIFEST, "manifest:full-path", path); setAttributeNS(MANIFEST, "manifest:media-type", if (ext == "jpg") "image/jpeg" else "image/$ext") })
            return path
        }
        /** Insert › Link on an object: LibreOffice's click action — go to a page (named once every page is in place) or a web page; "" none. */
        fun clickTo(el: Element, to: String) {
            el.kids().filter { it.localName == "event-listeners" }.forEach { el.removeChild(it) }
            // shortcut: LibreOffice has no "the slide it came from": in an .odp such a link is left out (Tetra's show offers it in .pptx only)
            if (to.isEmpty() || to == "back") return
            el.appendChild(doc.importNode(OfficeFormats.parse(("""<office:event-listeners xmlns:office="$OFFICE" xmlns:presentation="$PRES" xmlns:script="urn:oasis:names:tc:opendocument:xmlns:script:1.0" xmlns:xlink="$XLINK">""" +
                """<presentation:event-listener script:event-name="dom:click" presentation:action="show" xlink:href="${(if (to.startsWith("slide:")) "#tetra-$to" else to).replace("&", "&amp;").replace("\"", "&quot;")}" xlink:type="simple"/></office:event-listeners>""").toByteArray()).documentElement, true))
        }
        /** Shape options' alt text: the item's svg:desc ("" none). */
        fun alt(el: Element, words: String) {
            el.kids().filter { it.localName == "desc" && it.namespaceURI == SVG }.forEach { el.removeChild(it) }
            if (words.isEmpty()) return
            val d = doc.createElementNS(SVG, "svg:desc").also { it.textContent = words }
            val title = el.kids().firstOrNull { it.localName == "title" && it.namespaceURI == SVG }
            el.insertBefore(d, title?.nextSibling) // last, after its title, as LibreOffice writes them
        }
        /** A picture's crop as LibreOffice keeps it: its style's fo:clip, lengths of the picture at its own size (as Slides reads it). */
        fun crop(el: Element, c: List<Float>) {
            val href = el.kids().firstOrNull { it.localName == "image" }?.a(XLINK, "href")?.removePrefix("./") ?: return
            val (w, h) = (out[href] ?: read(href))?.let { Slides.picturePt(it) } ?: return
            val pr = el.a(PRES, "style-name")
            val nm = derive(if (pr != null) "presentation" else "graphic", pr ?: el.a(DRAW, "style-name"), "clip $c") { st ->
                props(st, "graphic-properties").setAttributeNS(FO, "fo:clip", "rect(${cm(c[1] * h)}, ${cm(c[2] * w)}, ${cm(c[3] * h)}, ${cm(c[0] * w)})") }
            if (pr != null) el.setAttributeNS(PRES, "presentation:style-name", nm) else el.setAttributeNS(DRAW, "draw:style-name", nm)
        }
        /** A picture's Transparency and Picture style as LibreOffice keeps them: its style's image opacity, line and shadow. */
        fun picLook(el: Element, l: SlidesEdit.Look) {
            if (l.alpha == null && l.picStyle == null) return
            val pr = el.a(PRES, "style-name")
            val nm = derive(if (pr != null) "presentation" else "graphic", pr ?: el.a(DRAW, "style-name"), "pic ${l.alpha} ${l.picStyle}") { st ->
                val g = props(st, "graphic-properties")
                l.alpha?.let { g.setAttributeNS(DRAW, "draw:image-opacity", "${Math.round(it * 100)}%") }
                l.picStyle?.let { s ->
                    if (s.line != null && s.lineW > 0f) { g.setAttributeNS(DRAW, "draw:stroke", "solid"); g.setAttributeNS(SVG, "svg:stroke-color", hex(s.line)); g.setAttributeNS(SVG, "svg:stroke-width", cm(s.lineW)) }
                    else g.setAttributeNS(DRAW, "draw:stroke", "none")
                    g.setAttributeNS(DRAW, "draw:shadow", if (s.shadow) "visible" else "hidden")
                    if (s.shadow) { g.setAttributeNS(DRAW, "draw:shadow-offset-x", "0.1cm"); g.setAttributeNS(DRAW, "draw:shadow-offset-y", "0.1cm"); g.setAttributeNS(DRAW, "draw:shadow-color", "#000000"); g.setAttributeNS(DRAW, "draw:shadow-opacity", "40%") }
                }
            }
            if (pr != null) el.setAttributeNS(PRES, "presentation:style-name", nm) else el.setAttributeNS(DRAW, "draw:style-name", nm)
        }
        fun restyle(el: Element, l: SlidesEdit.Look) {
            val shapes = if (el.localName == "g") el.all().filter { it.namespaceURI == DRAW && it.localName in setOf("frame", "custom-shape", "rect", "ellipse") } else listOf(el)
            for (s in shapes) {
                if (l.fill != null || l.line != null || l.lineWidth != null || l.dash != null || l.shadow != null) {
                    val pr = s.a(PRES, "style-name")
                    val nm = derive(if (pr != null) "presentation" else "graphic", pr ?: s.a(DRAW, "style-name"), "fill ${l.fill} line ${l.line} ${l.lineWidth} ${l.dash} ${l.shadow}") { st ->
                        val g = props(st, "graphic-properties")
                        l.fill?.let { g.removeAttributeNS(DRAW, "fill-gradient-name"); if (it == SlidesEdit.NONE) g.setAttributeNS(DRAW, "draw:fill", "none") else { g.setAttributeNS(DRAW, "draw:fill", "solid"); g.setAttributeNS(DRAW, "draw:fill-color", hex(it)) } }
                        l.line?.let { if (it == SlidesEdit.NONE) g.setAttributeNS(DRAW, "draw:stroke", "none") else { g.setAttributeNS(DRAW, "draw:stroke", "solid"); g.setAttributeNS(SVG, "svg:stroke-color", hex(it)) } }
                        l.lineWidth?.let { g.setAttributeNS(SVG, "svg:stroke-width", cm(it)) }
                        // a dash names its draw:stroke-dash (added to styles.xml on saving); solid again where it was dashed
                        l.dash?.let { d -> LineDash.odfName(d)?.let { n -> dashes += d; g.setAttributeNS(DRAW, "draw:stroke", "dash"); g.setAttributeNS(DRAW, "draw:stroke-dash", n) }
                            ?: run { g.removeAttributeNS(DRAW, "stroke-dash"); if (g.getAttributeNS(DRAW, "stroke") == "dash") g.setAttributeNS(DRAW, "draw:stroke", "solid") } }
                        l.shadow?.let { on -> g.setAttributeNS(DRAW, "draw:shadow", if (on) "visible" else "hidden")
                            if (on) { g.setAttributeNS(DRAW, "draw:shadow-offset-x", "0.075cm"); g.setAttributeNS(DRAW, "draw:shadow-offset-y", "0.075cm"); g.setAttributeNS(DRAW, "draw:shadow-color", "#000000"); g.setAttributeNS(DRAW, "draw:shadow-opacity", "40%") } }
                    }
                    if (pr != null) s.setAttributeNS(PRES, "presentation:style-name", nm) else s.setAttributeNS(DRAW, "draw:style-name", nm)
                    // LibreOffice keeps a copy of the fill in the shape's text style (loext:graphic-properties), and paints that one
                    val ts = s.a(DRAW, "text-style-name")
                    val copy = ts?.let { n -> autos.kids().firstOrNull { it.a(STYLE, "name") == n && it.a(STYLE, "family") == "paragraph" } }?.kids()?.firstOrNull { it.localName == "graphic-properties" && it.namespaceURI == LOEXT }
                    if (l.fill != null && copy != null) s.setAttributeNS(DRAW, "draw:text-style-name", derive("paragraph", ts, "fill ${l.fill}") { st ->
                        val g = st.kids().first { it.localName == "graphic-properties" && it.namespaceURI == LOEXT }
                        g.removeAttributeNS(DRAW, "fill-gradient-name")
                        if (l.fill == SlidesEdit.NONE) g.setAttributeNS(DRAW, "draw:fill", "none") else { g.setAttributeNS(DRAW, "draw:fill", "solid"); g.setAttributeNS(DRAW, "draw:fill-color", hex(l.fill)) }
                    })
                }
                if (l.words) words(s, l)
            }
            l.alt?.let { alt(el, it) }
            // the Selection pane: its name, and hidden as LibreOffice keeps one (drawooo:display)
            l.name?.let { if (it.isEmpty()) el.removeAttributeNS(DRAW, "name") else el.setAttributeNS(DRAW, "draw:name", it) }
            l.hide?.let { if (it) el.setAttributeNS("http://openoffice.org/2010/draw", "drawooo:display", "none") else el.removeAttributeNS("http://openoffice.org/2010/draw", "display") }
            l.objectLink?.let { clickTo(el, it) }
            // Replace picture, Edit drawing: the frame's picture a new one in the package
            l.image?.let { p -> el.kids().firstOrNull { it.localName == "image" }?.setAttributeNS(XLINK, "xlink:href", picture(SlidesEdit.Added("image", 0f, 0f, 0f, 0f, image = p, ext = p.substringAfterLast('.')))) }
            l.crop?.let { crop(el, it) }
            picLook(el, l)
        }
        /** A deck's own table in [frame] with [ops] done (SlidesEdit.tabled's): rows and columns copied with no words, or taken; [now] its size. */
        fun tabled(frame: Element, ops: List<Pair<Char, Int>>, now: SlideItem?) {
            val t = frame.kids().firstOrNull { it.localName == "table" } ?: return
            // repeated columns and cells each one of their own first, so each can be copied or taken
            fun split(e: Element) { val n = e.a(TABLE, "number-columns-repeated")?.toIntOrNull()?.coerceIn(1, 64) ?: return; e.removeAttributeNS(TABLE, "number-columns-repeated")
                repeat(n - 1) { e.parentNode.insertBefore(e.cloneNode(true), e.nextSibling) } }
            fun cols() = t.kids().filter { it.localName == "table-column" }
            fun rows() = t.kids().filter { it.localName == "table-row" }
            fun cells(r: Element) = r.kids().filter { it.localName == "table-cell" || it.localName == "covered-table-cell" }
            cols().forEach(::split); rows().forEach { r -> cells(r).forEach(::split) }
            fun emptied(c: Element): Element = (c.cloneNode(true) as Element).let { x -> (if (x.localName == "covered-table-cell") doc.renameNode(x, TABLE, "table:table-cell") as Element else x).also { n ->
                n.removeAttributeNS(TABLE, "number-columns-spanned"); n.removeAttributeNS(TABLE, "number-rows-spanned")
                val ps = n.kids(); ps.drop(1).forEach { n.removeChild(it) }
                ps.firstOrNull()?.let { p -> if (p.localName == "p") p.kids().forEach { p.removeChild(it) } else n.removeChild(p) } } }
            for ((op, k) in ops) when (op) {
                'r' -> { val rs = rows(); if (k !in 0..rs.size) continue; val src = rs[(k - 1).coerceIn(0, rs.lastIndex)]
                    val nr = src.cloneNode(false) as Element; cells(src).forEach { nr.appendChild(emptied(it)) }; t.insertBefore(nr, rs.getOrNull(k) ?: rs.last().nextSibling) }
                'c' -> { val cs = cols(); if (k !in 0..cs.size) continue; val src = (k - 1).coerceIn(0, cs.lastIndex)
                    t.insertBefore(cs[src].cloneNode(true), cs.getOrNull(k) ?: cs.last().nextSibling)
                    for (r in rows()) { val cl = cells(r); if (cl.isEmpty()) continue; r.insertBefore(emptied(cl[src.coerceAtMost(cl.lastIndex)]), cl.getOrNull(k) ?: cl.last().nextSibling) } }
                'R' -> { val rs = rows(); if (rs.size > 1 && k in rs.indices) t.removeChild(rs[k]) }
                'C' -> { val cs = cols(); if (cs.size > 1 && k in cs.indices) { t.removeChild(cs[k]); for (r in rows()) cells(r).getOrNull(k)?.let { r.removeChild(it) } } }
            }
            now?.let { frame.setAttributeNS(SVG, "svg:width", cm(it.w)); frame.setAttributeNS(SVG, "svg:height", cm(it.h)) }
        }
        /** [el] at [x], [y], [w] × [h] (points), turned [deg] clockwise about its middle (Words' draw:transform; 0: plain svg:x and svg:y). */
        fun turnTo(el: Element, x: Float, y: Float, w: Float, h: Float, deg: Float) {
            listOf("x", "y").forEach { el.removeAttributeNS(SVG, it) }; el.removeAttributeNS(DRAW, "transform")
            el.setAttributeNS(SVG, "svg:width", cm(w)); el.setAttributeNS(SVG, "svg:height", cm(h))
            val placed = Arrange.odtTurned("<x svg:x=\"${cm(x)}\" svg:y=\"${cm(y)}\" svg:width=\"${cm(w)}\" svg:height=\"${cm(h)}\">", deg, w, h)
            Regex("""\s(svg:x|svg:y|draw:transform)="([^"]*)"""").findAll(placed).forEach { m ->
                if (m.groupValues[1] == "draw:transform") el.setAttributeNS(DRAW, "draw:transform", m.groupValues[2]) else el.setAttributeNS(SVG, m.groupValues[1], m.groupValues[2]) }
        }
        /** [el] (or, a group, each item in it as the group's [box] moves to [f]) at its new place. */
        fun place(el: Element, f: List<Float>, box: SlideItem?) {
            fun len(e: Element, n: String) = odfLength(e.a(SVG, n))
            val items = if (el.localName == "g") el.all().filter { it.a(SVG, "x") != null && it.a(SVG, "width") != null } else listOf(el)
            for (e in items) {
                // turned: placed again with its turn (a group's pieces keep theirs)
                val turned = e.a(DRAW, "transform")?.takeIf { el.localName != "g" }?.let { t -> Arrange.odtTransform(""" svg:width="${e.a(SVG, "width")}" svg:height="${e.a(SVG, "height")}" draw:transform="$t"""")?.first }
                if (e.a(DRAW, "transform") != null && turned == null) continue
                if (turned != null) { turnTo(e, f[0], f[1], f[2], f[3], turned); continue }
                val x = len(e, "x") ?: 0f; val y = len(e, "y") ?: 0f; val w = len(e, "width") ?: 0f; val h = len(e, "height") ?: 0f
                val n = if (box == null || el.localName != "g") f else {
                    val kx = if (box.w > 0) f[2] / box.w else 1f; val ky = if (box.h > 0) f[3] / box.h else 1f
                    listOf(f[0] + (x - box.x) * kx, f[1] + (y - box.y) * ky, w * kx, h * ky)
                }
                e.setAttributeNS(SVG, "svg:x", cm(n[0])); e.setAttributeNS(SVG, "svg:y", cm(n[1])); e.setAttributeNS(SVG, "svg:width", cm(n[2])); e.setAttributeNS(SVG, "svg:height", cm(n[3]))
            }
        }

        val ns = """xmlns:draw="$DRAW" xmlns:svg="$SVG" xmlns:text="$TEXT" xmlns:xlink="$XLINK""""
        fun added(a: SlidesEdit.Added, look: SlidesEdit.Look?): Element? {
            val box = """svg:x="${cm(a.x)}" svg:y="${cm(a.y)}" svg:width="${cm(a.w)}" svg:height="${cm(a.h)}""""
            val x = if (a.math != null && a.image != null) {
                // an equation: LibreOffice Math's object (MathML, Words' writer), its picture as the object's replacement
                val name = generateSequence(1) { it + 1 }.map { "Object tetra-math-$it" }.first { zip.getEntry("$it/content.xml") == null && "$it/content.xml" !in out }
                out["$name/content.xml"] = OfficeMath.mathml(a.math, true).toByteArray()
                manifest?.documentElement?.let { m ->
                    m.appendChild(manifest.createElementNS(MANIFEST, "manifest:file-entry").apply { setAttributeNS(MANIFEST, "manifest:full-path", "$name/"); setAttributeNS(MANIFEST, "manifest:version", "1.3"); setAttributeNS(MANIFEST, "manifest:media-type", "application/vnd.oasis.opendocument.formula") })
                    m.appendChild(manifest.createElementNS(MANIFEST, "manifest:file-entry").apply { setAttributeNS(MANIFEST, "manifest:full-path", "$name/content.xml"); setAttributeNS(MANIFEST, "manifest:media-type", "text/xml") }) }
                """<draw:frame $ns draw:layer="layout" $box><draw:object xlink:href="./$name" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad"/><draw:image xlink:href="${picture(a)}" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad"/></draw:frame>"""
            } else if (a.chart != null) {
                // a chart put on: an object of its own in the package, as LibreOffice keeps one (Words' ChartWriter)
                val name = generateSequence(1) { it + 1 }.map { "Object tetra-$it" }.first { zip.getEntry("$it/content.xml") == null && "$it/content.xml" !in out }
                out["$name/content.xml"] = ChartWriter.odf(a.chart, a.w, a.h).toByteArray()
                manifest?.documentElement?.let { m ->
                    m.appendChild(manifest.createElementNS(MANIFEST, "manifest:file-entry").apply { setAttributeNS(MANIFEST, "manifest:full-path", "$name/"); setAttributeNS(MANIFEST, "manifest:version", "1.3"); setAttributeNS(MANIFEST, "manifest:media-type", "application/vnd.oasis.opendocument.chart") })
                    m.appendChild(manifest.createElementNS(MANIFEST, "manifest:file-entry").apply { setAttributeNS(MANIFEST, "manifest:full-path", "$name/content.xml"); setAttributeNS(MANIFEST, "manifest:media-type", "text/xml") }) }
                """<draw:frame $ns draw:layer="layout" $box><draw:object xlink:href="./$name" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad"/></draw:frame>"""
            } else if (a.video != null && a.image != null) {
                // a video as Impress keeps one: a plugin playing the film (in the package's Media, or a web address), its picture beside
                val v = a.video
                val href = if (v.startsWith("http")) v else { val ext = v.substringAfterLast('.', "mp4").lowercase(); val path = "Media/tetra${nextPicture++}.$ext"; out[path] = File(v).readBytes()
                    manifest?.documentElement?.appendChild(manifest.createElementNS(MANIFEST, "manifest:file-entry").apply { setAttributeNS(MANIFEST, "manifest:full-path", path); setAttributeNS(MANIFEST, "manifest:media-type", OfficeVideo.mime(ext, odp = true)) }); path }
                val st = derive("graphic", null, "picture") { st -> props(st, "graphic-properties").apply { setAttributeNS(DRAW, "draw:stroke", "none"); setAttributeNS(DRAW, "draw:fill", "none") } }
                fun esc(t: String) = t.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
                """<draw:frame $ns draw:style-name="$st" draw:layer="layout" $box><draw:plugin xlink:href="${esc(href)}" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad" draw:mime-type="${if (v.startsWith("http")) "video/mp4" else OfficeVideo.mime(v.substringAfterLast('.', "mp4"), odp = true)}">""" +
                    """<draw:param draw:name="Loop" draw:value="false"/><draw:param draw:name="Mute" draw:value="false"/><draw:param draw:name="VolumeDB" draw:value="0"/></draw:plugin>""" +
                    """<draw:image xlink:href="${picture(a)}" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad"><text:p/></draw:image></draw:frame>"""
            } else if (a.image != null) {
                val st = derive("graphic", null, "picture") { st -> props(st, "graphic-properties").apply { setAttributeNS(DRAW, "draw:stroke", "none"); setAttributeNS(DRAW, "draw:fill", "none") } }
                """<draw:frame $ns draw:style-name="$st" draw:layer="layout" $box><draw:image xlink:href="${picture(a)}" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad"><text:p/></draw:image></draw:frame>"""
            } else if (a.prst == SlidesEdit.TEXT_BOX && !look?.warp.isNullOrEmpty()) {
                // WordArt bent: LibreOffice's fontwork, which paints its letters in the shape's fill (and outlines them in its line)
                val col = look?.text ?: 0xFF000000.toInt(); val outline = look?.effects?.outline
                val st = derive("graphic", null, "fontwork $col $outline") { st -> props(st, "graphic-properties").apply {
                    setAttributeNS(DRAW, "draw:fill", "solid"); setAttributeNS(DRAW, "draw:fill-color", hex(col))
                    if (outline == null) setAttributeNS(DRAW, "draw:stroke", "none") else { setAttributeNS(DRAW, "draw:stroke", "solid"); setAttributeNS(SVG, "svg:stroke-color", hex(outline)) } } }
                """<draw:custom-shape $ns draw:style-name="$st" draw:layer="layout" $box><text:p/><draw:enhanced-geometry svg:viewBox="0 0 21600 21600" draw:type="rectangle"/></draw:custom-shape>"""
            } else if (a.prst == SlidesEdit.TEXT_BOX) {
                // LibreOffice's text box: a frame with no fill or line, growing to its words
                val st = derive("graphic", null, "text box") { st -> props(st, "graphic-properties").apply { setAttributeNS(DRAW, "draw:stroke", "none"); setAttributeNS(DRAW, "draw:fill", "none")
                    setAttributeNS(DRAW, "draw:auto-grow-height", "true"); setAttributeNS(DRAW, "draw:textarea-vertical-align", "top") } }
                // words with their own looks (References, Contents): spans in automatic styles, links as LibreOffice's (a slide's named once the pages are in place)
                fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                val paras = if (a.runs.isEmpty()) "<text:p/>" else a.runs.joinToString("") { rs ->
                    val ps = if (a.hang > 0f) derive("paragraph", null, "hang ${a.hang}") { st -> props(st, "paragraph-properties").apply { setAttributeNS(FO, "fo:margin-left", cm(a.hang)); setAttributeNS(FO, "fo:text-indent", "-" + cm(a.hang)) } } else null
                    "<text:p${ps?.let { " text:style-name=\"$it\"" }.orEmpty()}>" + rs.joinToString("") { r ->
                        val ts = derive("text", null, "run ${r.italic} ${r.bold} ${r.sizePt} ${r.color}") { st -> props(st, "text-properties").apply {
                            setAttributeNS(FO, "fo:font-size", String.format(Locale.ROOT, "%.1fpt", r.sizePt ?: 18f)); if (r.italic) setAttributeNS(FO, "fo:font-style", "italic"); if (r.bold) setAttributeNS(FO, "fo:font-weight", "bold")
                            r.color?.let { setAttributeNS(FO, "fo:color", hex(it)) } } }
                        val span = """<text:span text:style-name="$ts">${esc(r.text)}</text:span>"""
                        r.link?.let { to -> """<text:a xlink:type="simple" xlink:href="${esc(if (to.startsWith("slide:")) "#tetra-$to" else to)}">$span</text:a>""" } ?: span
                    } + "</text:p>" }
                """<draw:frame $ns draw:style-name="$st" draw:layer="layout" $box><draw:text-box>$paras</draw:text-box></draw:frame>"""
            } else if (a.prst != null && a.prst != "table") {
                val fill = look?.fill ?: a.fill; val line = look?.line ?: darker(a.fill)
                val st = derive("graphic", null, "shape $fill $line ${look?.lineWidth}") { st -> props(st, "graphic-properties").apply {
                    if (fill == SlidesEdit.NONE) setAttributeNS(DRAW, "draw:fill", "none") else { setAttributeNS(DRAW, "draw:fill", "solid"); setAttributeNS(DRAW, "draw:fill-color", hex(fill)) }
                    if (line == SlidesEdit.NONE) setAttributeNS(DRAW, "draw:stroke", "none") else { setAttributeNS(DRAW, "draw:stroke", "solid"); setAttributeNS(SVG, "svg:stroke-color", hex(line)); setAttributeNS(SVG, "svg:stroke-width", look?.lineWidth?.let(::cm) ?: "0.053cm") }
                } }
                val geometry = ShapeGeometry.odfGeometry(a.prst, a.w, a.h) ?: """<draw:enhanced-geometry svg:viewBox="0 0 21600 21600" draw:type="${ShapePicture.odfType(a.prst)}"/>"""
                """<draw:custom-shape $ns draw:style-name="$st" draw:layer="layout" $box><text:p/>$geometry</draw:custom-shape>"""
            } else if (a.prst == "table") {
                // PowerPoint's default look, as LibreOffice keeps it: the first row in the accent, the others banded, white lines
                val co = derive("table-column", null, "col ${a.w / a.cols}") { props(it, "table-column-properties").setAttributeNS(STYLE, "style:column-width", cm(a.w / a.cols)) }
                val ro = derive("table-row", null, "row ${a.h / a.rows}") { props(it, "table-row-properties").setAttributeNS(STYLE, "style:row-height", cm(a.h / a.rows)) }
                fun cell(r: Int) = SlidesEdit.cellFill(look?.fill, r).let { fill ->
                    derive("table-cell", null, "cell $fill") { st ->
                        st.appendChild(doc.createElementNS(LOEXT, "loext:graphic-properties").apply { setAttributeNS(DRAW, "draw:fill", "solid"); setAttributeNS(DRAW, "draw:fill-color", hex(fill))
                            setAttributeNS(FO, "fo:padding-top", "0.127cm"); setAttributeNS(FO, "fo:padding-bottom", "0.127cm"); setAttributeNS(FO, "fo:padding-left", "0.254cm"); setAttributeNS(FO, "fo:padding-right", "0.254cm") })
                        props(st, "paragraph-properties").setAttributeNS(FO, "fo:border", "0.48pt solid #ffffff")
                    } }
                fun words(head: Boolean) = derive("text", null, "cell words $head ${look?.text}") { st -> props(st, "text-properties").apply {
                    setAttributeNS(FO, "fo:font-size", "14pt"); setAttributeNS(FO, "fo:color", hex(look?.text ?: if (head) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())); if (head) setAttributeNS(FO, "fo:font-weight", "bold") } }
                fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                val st = derive("graphic", null, "table") { }
                """<draw:frame $ns xmlns:table="$TABLE" draw:style-name="$st" draw:layer="layout" $box><table:table><table:table-column table:style-name="$co" table:number-columns-repeated="${a.cols}"/>""" +
                    (0 until a.rows).joinToString("") { r -> """<table:table-row table:style-name="$ro" table:default-cell-style-name="${cell(r)}">""" + (0 until a.cols).joinToString("") { c ->
                        val t = a.cells.getOrNull(r * a.cols + c).orEmpty()
                        """<table:table-cell><text:p>${if (t.isEmpty()) "" else """<text:span text:style-name="${words(r == 0)}">${esc(t)}</text:span>"""}</text:p></table:table-cell>"""
                    } + "</table:table-row>" } + "</table:table></draw:frame>"
            } else return null
            return doc.importNode(OfficeFormats.parse(x.toByteArray()).documentElement, true) as Element
        }

        /** [el]'s id, what an animation names (one of its own made when it has none). */
        fun idOf(el: Element): String = el.a(XML, "id") ?: el.a(DRAW, "id") ?: fresh("idT").also { el.setAttributeNS(XML, "xml:id", it); el.setAttributeNS(DRAW, "draw:id", it) }
        val ans = """xmlns:anim="$ANIM" xmlns:smil="$SMIL" xmlns:presentation="$PRES""""
        fun secs(ms: Int) = String.format(Locale.ROOT, "%.3fs", ms / 1000f)
        /** [fx] on [target], in LibreOffice's presets, starting [node] (on-click, with-previous, after-previous). */
        fun effect(fx: SlideEffect, target: String, node: String): String {
            // a film or sound played, as LibreOffice writes PowerPoint's media call
            if (fx.kind == "play") return """<anim:par smil:begin="0s" smil:fill="hold" presentation:node-type="$node" presentation:preset-class="media-call" presentation:preset-id=""><anim:command smil:dur="${secs(fx.ms)}" smil:fill="hold" smil:targetElement="$target" anim:command="play"/></anim:par>"""
            val kind = when (fx.kind) { "appear" -> if (fx.exit) "disappear" else "appear"; "fly" -> if (fx.exit) "fly-out" else "fly-in"; "wipe" -> "wipe"; "zoom" -> "zoom"
                "spin" -> "spin"; "grow" -> "grow-and-shrink"; "pulse" -> "flash-bulb"; else -> if (fx.exit) "fade-out" else "fade-in" }
            val cls = if (fx.emphasis) "emphasis" else if (fx.exit) "exit" else "entrance"
            val sub = when (fx.kind) { "fly", "wipe" -> """ presentation:preset-sub-type="${OdpEffects.subtype(fx.from)}""""; "zoom" -> """ presentation:preset-sub-type="${if (fx.exit) "out" else "in"}""""; else -> "" }
            val begin = if (fx.exit && fx.kind != "appear") secs(fx.ms - 1) else "0s"
            val set = if (fx.emphasis) "" else """<anim:set smil:begin="$begin" smil:dur="0.001s" smil:fill="hold" smil:targetElement="$target" smil:attributeName="visibility" smil:to="${if (fx.exit) "hidden" else "visible"}"/>"""
            fun transform(type: String, by: String, back: Boolean = false) =
                """<anim:animateTransform xmlns:svg="$SVG" smil:dur="${secs(if (back) (fx.ms / 2).coerceAtLeast(1) else fx.ms)}" smil:fill="hold"${if (back) " smil:autoReverse=\"true\"" else ""} smil:targetElement="$target" smil:by="$by" smil:attributeName="transform" svg:type="$type"/>"""
            val move = when (fx.kind) {
                // emphasis as LibreOffice writes PowerPoint's: a whole turn, half as big again, a little bigger and back
                "spin" -> transform("rotate", "360")
                "grow" -> transform("scale", "0.5,0.5")
                "pulse" -> transform("scale", "0.1,0.1", back = true)
                "appear" -> ""
                "fly" -> {
                    val edge = when (fx.from) { 'l' -> "x" to "0-width/2"; 'r' -> "x" to "1+width/2"; 'u' -> "y" to "0-height/2"; else -> "y" to "1+height/2" }
                    listOf("x", "y").joinToString("") { at ->
                        val there = if (at == edge.first) edge.second else at
                        """<anim:animate smil:dur="${secs(fx.ms)}" smil:fill="hold" smil:targetElement="$target" smil:attributeName="$at" smil:values="${if (fx.exit) "$at;$there" else "$there;$at"}" smil:keyTimes="0;1"/>"""
                    }
                }
                // LibreOffice's bar wipe: top to bottom (reversed: from the bottom), left to right (reversed: from the right)
                "wipe" -> """<anim:transitionFilter smil:dur="${secs(fx.ms)}" smil:targetElement="$target" smil:type="barWipe" smil:subtype="${if (fx.from == 'l' || fx.from == 'r') "leftToRight" else "topToBottom"}"${if (fx.from == 'd' || fx.from == 'r') " smil:direction=\"reverse\"" else ""}${if (fx.exit) " smil:mode=\"out\"" else ""}/>"""
                "zoom" -> listOf("width", "height").joinToString("") { at ->
                    """<anim:animate smil:dur="${secs(fx.ms)}" smil:fill="hold" smil:targetElement="$target" smil:attributeName="$at" smil:values="${if (fx.exit) "$at;0" else "0;$at"}" smil:keyTimes="0;1"/>"""
                }
                else -> """<anim:transitionFilter smil:dur="${secs(fx.ms)}" smil:targetElement="$target" smil:type="fade" smil:subtype="crossfade"${if (fx.exit) " smil:mode=\"out\"" else ""}/>"""
            }
            return """<anim:par smil:begin="0s" smil:fill="hold" presentation:node-type="$node" presentation:preset-class="$cls" presentation:preset-id="ooo-$cls-$kind"$sub>$set$move</anim:par>"""
        }
        /** The clicks: each a group (on a click, or by itself), its effects in pars by when they start. */
        fun clicks(list: List<SlideEffect>, ids: Map<String, String>): String = SlidesEdit.group(list.filter { it.target in ids }).joinToString("") { step ->
            var last: SlideEffect? = null
            """<anim:par smil:begin="${if (step.auto) "0s" else "next"}" smil:fill="hold">""" + step.effects.groupBy { it.delay }.toSortedMap().entries.joinToString("") { (delay, fxs) ->
                """<anim:par smil:begin="${secs(delay)}" smil:fill="hold">""" + fxs.joinToString("") { fx ->
                    val node = when { last == null -> if (step.auto) "after-previous" else "on-click"; fx.start == 'a' -> "after-previous"; else -> "with-previous" }
                    last = fx
                    effect(fx, ids.getValue(fx.target), node)
                } + "</anim:par>"
            } + "</anim:par>"
        }
        fun fragment(x: String) = doc.importNode(OfficeFormats.parse(x.toByteArray()).documentElement, true) as Element
        /** The page's timing root, made when it has none (after its shapes, before its notes). */
        fun timingRoot(page: Element): Element = page.kids().firstOrNull { it.namespaceURI == ANIM && it.a(PRES, "node-type") == "timing-root" }
            ?: fragment("""<anim:par $ans smil:dur="indefinite" smil:restart="never" presentation:node-type="timing-root"/>""").also { r -> page.insertBefore(r, page.kids().firstOrNull { it.localName == "notes" }) }

        /** Page [page] (showing [slide] as read, [uid] in the edit) with its edits. */
        // Design › Format background: the pictures' fill images, for styles.xml (where LibreOffice keeps them), one a picture
        val fillImages = LinkedHashMap<String, String>()
        val fillGradients = ArrayList<Pair<Int, Int>>() // background gradients made here, for styles.xml
        fun apply(page: Element, slide: Slide, uid: Int) {
            // Design › Format background: the page's own drawing-page style, a colour or a picture stretched over it
            edit.backgrounds[uid]?.let { b ->
                val name = b.picture?.let { p -> fillImages.getOrPut(p) { picture(SlidesEdit.Added("background", 0f, 0f, 0f, 0f, image = p, ext = b.ext)) }.let { "tetra-bg-" + (fillImages.keys.indexOf(p) + 1) } }
                page.setAttributeNS(DRAW, "draw:style-name", derive("drawing-page", page.a(DRAW, "style-name"), "back $b") { st ->
                    val d = props(st, "drawing-page-properties")
                    listOf("fill-gradient-name", "fill-image-name", "fill-hatch-name").forEach { d.removeAttributeNS(DRAW, it) }
                    if (name != null) { d.setAttributeNS(DRAW, "draw:fill", "bitmap"); d.setAttributeNS(DRAW, "draw:fill-image-name", name); d.setAttributeNS(STYLE, "style:repeat", "stretch") }
                    // a gradient: LibreOffice's linear one, top to bottom (angle 0), defined in styles.xml
                    else if (b.to != null) { val g = (b.color ?: -1) to b.to; if (g !in fillGradients) fillGradients += g
                        d.setAttributeNS(DRAW, "draw:fill", "gradient"); d.setAttributeNS(DRAW, "draw:fill-color", hex(g.first)); d.setAttributeNS(DRAW, "draw:fill-gradient-name", "tetra-grad-${fillGradients.indexOf(g) + 1}") }
                    else { d.setAttributeNS(DRAW, "draw:fill", "solid"); d.setAttributeNS(DRAW, "draw:fill-color", hex(b.color ?: -1)) }
                    d.setAttributeNS(PRES, "presentation:background-visible", "true")
                })
            }
            // Home › Layout: the page on the master page chosen
            edit.relayout[uid]?.let { k -> page.setAttributeNS(DRAW, "draw:master-page-name", deck.layouts[k].part) }
            // Home › Reset: its placeholders as its master page has them (before anything done after the reset)
            if (uid in edit.reset) SlidesOdp.resetPage(page, masterPages[page.a(DRAW, "master-page-name")])
            fun at(id: String): Element? { var el: Element? = page; for (k in id.removePrefix("o").split('.')) el = el?.kids()?.getOrNull(k.toIntOrNull() ?: return null); return el }
            // found before anything moves: the places name the page as it was read
            val texts = edit.texts.filterKeys { it.first == uid }.mapNotNull { (k, v) -> at(k.second)?.let { it to v } }
            val looks = edit.looks.filterKeys { it.first == uid && it.second.startsWith("o") }.mapNotNull { (k, v) -> at(k.second)?.let { it to v } }
            val frames = (edit.frames.filterKeys { it.first == uid }.mapKeys { it.key.second } + SlidesEdit.grown(deck, edit, edit.order.first { it.uid == uid }))
                .mapNotNull { (id, v) -> at(id)?.let { Triple(it, v, SlidesEdit.box(slide, id)) } }
            val removed = edit.removed.filter { it.first == uid }.mapNotNull { at(it.second) }
            // Arrange: the page's own items turned, and the order from the back
            val turnsOwn = edit.turns.filterKeys { it.first == uid && it.second.startsWith("o") }.mapNotNull { (k, t) -> at(k.second)?.let { Triple(k.second, it, t) } }
            val made = edit.groups[uid].orEmpty()
            val stackEls = edit.stack[uid]?.flatMap { made[it] ?: listOf(it) }?.map { id -> id to (if (id.startsWith("o")) at(id) else null) }
            // Group and Ungroup: found now, joined or taken apart once all else is done
            val joins = made.map { (gid, ms) -> gid to ms.map { m -> m to (if (m.startsWith("o")) at(m) else null) } }
            val aparts = edit.ungrouped.filter { it.first == uid }.mapNotNull { at(it.second)?.takeIf { g -> g.localName == "g" } }
            val anims = edit.anims[uid]?.mapNotNull { fx -> (if (fx.target.startsWith("o")) at(fx.target) else null).let { fx to it } }
            texts.forEach { (el, lines) -> type(el, lines) }
            looks.forEach { (el, l) -> restyle(el, l) }
            // a deck's own tables' rows and columns (the Table tab), the frame as big as the table now is
            edit.tables.filterKeys { it.first == uid && it.second.startsWith("o") }.takeIf { it.isNotEmpty() }?.let { ts ->
                val shown = SlidesEdit.slide(deck, edit, edit.order.first { it.uid == uid })
                ts.forEach { (k, ops) -> at(k.second)?.let { tabled(it, ops, shown.items.lastOrNull { i -> i.anim == k.second }) } } }
            frames.forEach { (el, f, box) -> place(el, f, box) }
            removed.forEach { it.parentNode?.removeChild(it) }
            // Insert › Header & footer, as LibreOffice keeps it: the page's own date, footer and number boxes gone; the master's shown or not by the
            // page's style, the footer's and date's words declared (presentation:footer-decl, date-time-decl) and named by the page
            edit.footers[uid]?.let { f ->
                page.kids().filter { it.a(PRES, "class") in setOf("date-time", "footer", "page-number") }.forEach { page.removeChild(it) }
                page.setAttributeNS(DRAW, "draw:style-name", derive("drawing-page", page.a(DRAW, "style-name"), "footers ${f.date} ${f.number} ${f.text != null}") { st ->
                    val d = props(st, "drawing-page-properties")
                    d.setAttributeNS(PRES, "presentation:display-footer", "${f.text != null}"); d.setAttributeNS(PRES, "presentation:display-page-number", "${f.number}")
                    d.setAttributeNS(PRES, "presentation:display-date-time", "${f.date}")
                })
                fun declare(kind: String, prefix: String, fill: (Element) -> Unit): String {
                    val n = fresh(prefix)
                    pres.insertBefore(doc.createElementNS(PRES, "presentation:$kind").also { it.setAttributeNS(PRES, "presentation:name", n); fill(it) }, pres.kids().firstOrNull { it.localName == "page" })
                    return n
                }
                page.removeAttributeNS(PRES, "use-footer-name"); page.removeAttributeNS(PRES, "use-date-time-name")
                f.text?.let { t -> page.setAttributeNS(PRES, "presentation:use-footer-name", declare("footer-decl", "ftr") { it.textContent = t }) }
                if (f.date) page.setAttributeNS(PRES, "presentation:use-date-time-name", declare("date-time-decl", "dtd") { d ->
                    if (f.fixed != null) { d.setAttributeNS(PRES, "presentation:source", "fixed"); d.textContent = f.fixed } else d.setAttributeNS(PRES, "presentation:source", "current-date") })
            }
            // put on: over the page's own, before its notes and animations
            val before = page.kids().firstOrNull { it.localName in setOf("notes", "par", "seq", "animations") }
            // Review › Comments: Impress's annotations, written again from the list (a reply is one of its own: Impress has no threads)
            edit.comments[uid]?.let { list ->
                page.kids().filter { it.localName == "annotation" }.forEach { page.removeChild(it) }
                fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                for (sc in list) { val c = sc.comment
                    val date = (c.date ?: java.time.LocalDateTime.now().toString()).removeSuffix("Z").replace(Regex("""[+-]\d\d:\d\d$"""), "")
                    val x = """<officeooo:annotation xmlns:officeooo="http://openoffice.org/2009/office" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:meta="urn:oasis:names:tc:opendocument:xmlns:meta:1.0" xmlns:svg="$SVG" xmlns:text="$TEXT" """ +
                        """svg:x="${cm(sc.x)}" svg:y="${cm(sc.y)}" svg:width="0.6cm" svg:height="0.6cm"><dc:creator>${esc(c.author)}</dc:creator>""" +
                        (c.initials?.takeIf { it.isNotBlank() }?.let { "<meta:creator-initials>${esc(it)}</meta:creator-initials>" } ?: "") +
                        "<dc:date>${esc(date)}</dc:date>" + c.text.split('\n').joinToString("") { "<text:p>${esc(it)}</text:p>" } + "</officeooo:annotation>"
                    page.insertBefore(doc.importNode(OfficeFormats.parse(x.toByteArray()).documentElement, true), before)
                }
            }
            val put = HashMap<String, Element>()
            val grown = SlidesEdit.grown(deck, edit, edit.order.first { it.uid == uid })
            for (a in edit.added[uid].orEmpty()) added(grown[a.id]?.let { g -> a.copy(h = g[3]) } ?: a, edit.looks[uid to a.id])?.let { page.insertBefore(it, before); put[a.id] = it }
            // the words typed into what was put on, and Home's looks on them, as on the page's own
            for (a in edit.added[uid].orEmpty()) put[a.id]?.let { el -> edit.texts[uid to a.id]?.let { type(el, it) }; edit.looks[uid to a.id]?.takeIf { it.words }?.let { words(el, it) }; edit.looks[uid to a.id]?.alt?.let { alt(el, it) }; edit.looks[uid to a.id]?.crop?.let { crop(el, it) }; edit.looks[uid to a.id]?.let { picLook(el, it) }; edit.looks[uid to a.id]?.objectLink?.let { clickTo(el, it) } }
            // Arrange › Rotate and Flip: turned about its middle (Words' draw:transform), mirrored as LibreOffice says it (a shape's geometry, a picture's style)
            if (edit.turns.keys.any { it.first == uid }) {
                val shown = SlidesEdit.slide(deck, edit, edit.order.first { it.uid == uid })
                fun turn(el: Element, id: String, t: SlidesEdit.Turn) {
                    val b = SlidesEdit.box(shown, id) ?: return
                    val geo = el.kids().firstOrNull { it.localName == "enhanced-geometry" }
                    // a shape mirrored one way: ODF turns it before mirroring, so the other way round (as LibreOffice converts PowerPoint's)
                    turnTo(el, b.x, b.y, b.w, b.h, if (geo != null && t.flipH != t.flipV) (360f - t.deg) % 360f else t.deg)
                    geo?.let { g -> g.setAttributeNS(DRAW, "draw:mirror-horizontal", "${t.flipH}"); g.setAttributeNS(DRAW, "draw:mirror-vertical", "${t.flipV}") }
                    if (el.kids().any { it.localName == "image" }) el.a(DRAW, "style-name").let { n -> el.setAttributeNS(DRAW, "draw:style-name", derive("graphic", n, "mirror ${t.flipH} ${t.flipV}") { st ->
                        props(st, "graphic-properties").setAttributeNS(STYLE, "style:mirror", listOfNotNull("horizontal".takeIf { t.flipH }, "vertical".takeIf { t.flipV }).joinToString(" ").ifEmpty { "none" }) }) }
                }
                turnsOwn.forEach { (id, el, t) -> turn(el, id, t) }
                edit.turns.filterKeys { it.first == uid && !it.second.startsWith("o") }.forEach { (k, t) -> put[k.second]?.let { turn(it, k.second, t) } }
            }
            // Arrange's order: each named item moved, from the back, to the end of the page's items
            stackEls?.forEach { (id, el) -> (el ?: put[id])?.let { it.parentNode?.removeChild(it); page.insertBefore(it, before) } }
            // Ungroup: a group's things in its place on the page (ODF's groups place nothing themselves), a group in it too
            fun unwrap(g: Element) { for (k in g.kids()) { g.parentNode.insertBefore(k, g); if (k.localName == "g" && k.namespaceURI == DRAW) unwrap(k) }; g.parentNode.removeChild(g) }
            aparts.forEach(::unwrap)
            // Group: the things in one draw:g where the front-most was, then moved as one (from where its things are now)
            for ((gid, ms) in joins) {
                val els = ms.mapNotNull { (m, el) -> el ?: put[m] }.filter { it.parentNode == page }.distinct().sortedBy { page.kids().indexOf(it) }
                if (els.size < 2) continue
                val g = doc.createElementNS(DRAW, "draw:g")
                page.insertBefore(g, els.last())
                els.forEach { page.removeChild(it); g.appendChild(it) }
                edit.frames[uid to gid]?.let { f -> place(g, f, SlidesEdit.box(SlidesEdit.slide(deck, edit.copy(frames = edit.frames - (uid to gid)), edit.order.first { it.uid == uid }), gid)) }
            }
            // the transition: in the page's style and the timing's own filter, as LibreOffice writes it
            edit.transitions[uid]?.let { t ->
                val smil = t.kind.takeIf { it.isNotEmpty() }?.let { OdpEffects.smil(it, t.dir) }
                page.setAttributeNS(DRAW, "draw:style-name", derive("drawing-page", page.a(DRAW, "style-name"), "transition $t") { st ->
                    val d = props(st, "drawing-page-properties")
                    for (n in listOf("type", "subtype", "direction", "fadeColor")) d.removeAttributeNS(SMIL, n)
                    d.removeAttributeNS(PRES, "transition-speed"); d.removeAttributeNS(PRES, "transition-type"); d.removeAttributeNS(PRES, "duration")
                    // going on by itself after its time
                    t.after?.let { ms -> d.setAttributeNS(PRES, "presentation:transition-type", "automatic"); d.setAttributeNS(PRES, "presentation:duration", String.format(Locale.ROOT, "PT%.3fS", ms / 1000f)) }
                    if (smil != null) {
                        d.setAttributeNS(SMIL, "smil:type", smil.first); d.setAttributeNS(SMIL, "smil:subtype", smil.second)
                        if (smil.third) d.setAttributeNS(SMIL, "smil:direction", "reverse")
                        d.setAttributeNS(PRES, "presentation:transition-speed", when { t.ms <= 500 -> "fast"; t.ms >= 1000 -> "slow"; else -> "medium" })
                    }
                })
                val r = timingRoot(page)
                r.kids().filter { p -> p.kids().any { it.localName == "transitionFilter" && it.a(SMIL, "targetElement") == null } }.forEach { r.removeChild(it) }
                if (smil != null) r.insertBefore(fragment("""<anim:par $ans smil:begin="${idOf(page)}.begin"><anim:transitionFilter smil:dur="${secs(t.ms)}" smil:type="${smil.first}" smil:subtype="${smil.second}"${if (smil.third) " smil:direction=\"reverse\"" else ""}/></anim:par>"""), r.firstChild)
            }
            // the clicks: the main sequence written again (what is still on the page)
            // shortcut: rewrites the whole main sequence, so an effect of a kind not offered here comes back as its nearest (a pulse, a fade) when its animations are changed
            if (anims != null) {
                val r = timingRoot(page)
                r.kids().filter { it.localName == "seq" && it.a(PRES, "node-type") == "main-sequence" }.forEach { r.removeChild(it) }
                val ids = anims.mapNotNull { (fx, el) -> (el?.takeIf { it.parentNode != null } ?: put[fx.target])?.let { fx.target to idOf(it) } }.toMap()
                val xml = clicks(anims.map { it.first }, ids)
                if (xml.isNotEmpty()) r.appendChild(fragment("""<anim:seq $ans smil:dur="indefinite" presentation:node-type="main-sequence">$xml</anim:seq>"""))
            }
            page.kids().firstOrNull { it.namespaceURI == ANIM && it.a(PRES, "node-type") == "timing-root" }?.let { if (it.kids().isEmpty()) page.removeChild(it) }
            // the speaker notes as typed: the page's notes box's paragraphs (in the look of its first), a notes part made for a page without
            edit.notes[uid]?.let { text ->
                val notes = page.kids().firstOrNull { it.localName == "notes" && it.namespaceURI == PRES } ?: if (text.isBlank()) return@let else
                    // shortcut: LibreOffice's A4 notes page places (cm), whatever the deck's own notes master says; it moves them to its own on the first save there
                    fragment("""<presentation:notes xmlns:presentation="$PRES" xmlns:draw="$DRAW" xmlns:svg="$SVG"><draw:page-thumbnail draw:layer="layout" svg:width="17.271cm" svg:height="${String.format(Locale.ROOT, "%.3fcm", 17.271f * deck.height / deck.width.coerceAtLeast(1f))}" svg:x="1.865cm" svg:y="2.3cm" presentation:class="page"/>""" +
                        """<draw:frame draw:layer="layout" svg:width="17.271cm" svg:height="13cm" svg:x="1.865cm" svg:y="14.2cm" presentation:class="notes" presentation:placeholder="true"><draw:text-box/></draw:frame></presentation:notes>""").also { page.appendChild(it) }
                val frame = notes.kids().firstOrNull { it.a(PRES, "class") == "notes" } ?: fragment("""<draw:frame xmlns:presentation="$PRES" xmlns:draw="$DRAW" draw:layer="layout" presentation:class="notes"><draw:text-box/></draw:frame>""").also { notes.appendChild(it) }
                val box = frame.kids().firstOrNull { it.localName == "text-box" } ?: doc.createElementNS(DRAW, "draw:text-box").also { frame.appendChild(it) }
                frame.removeAttributeNS(PRES, "placeholder")
                val old = box.kids().filter { it.namespaceURI == TEXT && (it.localName == "p" || it.localName == "h" || it.localName == "list") }
                val pStyle = old.firstNotNullOfOrNull { o -> (listOf(o) + o.all()).firstOrNull { it.localName == "p" }?.a(TEXT, "style-name") }
                val sStyle = old.firstNotNullOfOrNull { o -> o.all().firstOrNull { it.localName == "span" }?.a(TEXT, "style-name") }
                old.forEach { box.removeChild(it) }
                for (line in text.split("\n")) box.appendChild(doc.createElementNS(TEXT, "text:p").also { p ->
                    pStyle?.let { p.setAttributeNS(TEXT, "text:style-name", it) }
                    if (line.isNotEmpty()) p.appendChild(if (sStyle == null) doc.createTextNode(line) else doc.createElementNS(TEXT, "text:span").also { sp -> sp.setAttributeNS(TEXT, "text:style-name", sStyle); sp.textContent = line })
                })
            }
            edit.hidden[uid]?.let { h -> if (h != slide.hidden)
                page.setAttributeNS(DRAW, "draw:style-name", derive("drawing-page", page.a(DRAW, "style-name"), "hidden $h") { props(it, "drawing-page-properties").setAttributeNS(PRES, "presentation:visibility", if (h) "hidden" else "visible") }) }
        }

        val used = HashSet<Int>()
        val ordered = ArrayList<Element>(); val orderedUid = ArrayList<Int>()
        for (e in edit.order) {
            // a new slide: its layout's page (empty boxes in the master's places)
            if (e.layout >= 0) {
                val l = deck.layouts.getOrNull(e.layout) ?: continue
                val page = fragment(String(l.xml)).also { it.setAttributeNS(DRAW, "draw:name", fresh("page")) }
                apply(page, l.preview, e.uid); ordered += page; orderedUid += e.uid; continue
            }
            val orig = pages.getOrNull(e.src) ?: continue
            val page = if (used.add(e.src)) orig else (orig.cloneNode(true) as Element).also { copy ->
                // a copy: its own name, and ids of its own (its animations follow them)
                copy.setAttributeNS(DRAW, "draw:name", fresh("page"))
                val renamed = HashMap<String, String>()
                val els = listOf(copy) + copy.all()
                for (el in els) for ((n, a) in listOf(XML to "id", DRAW to "id")) el.a(n, a)?.let { old -> renamed.getOrPut(old) { fresh("${old}c") }.let { el.setAttributeNS(n, if (n == XML) "xml:id" else "draw:id", it) } }
                for (el in els) for (i in 0 until el.attributes.length) el.attributes.item(i).let { at ->
                    if (at.localName == "targetElement") renamed[at.nodeValue]?.let { at.nodeValue = it }
                    if (at.localName == "begin" && at.nodeValue.endsWith(".begin")) renamed[at.nodeValue.removeSuffix(".begin")]?.let { at.nodeValue = "$it.begin" }
                }
            }
            apply(page, deck.slides[e.src], e.uid)
            ordered += page; orderedUid += e.uid
        }
        // links to slides: to the page's name, as LibreOffice's (a page with none, or a name another has, given its own)
        val named = HashSet<String>()
        val pageName = orderedUid.zip(ordered).associate { (uid, page) ->
            val n = page.a(DRAW, "name")?.takeIf { named.add(it) } ?: fresh("page").also { page.setAttributeNS(DRAW, "draw:name", it); named += it }
            uid to n }
        for (a in ordered.flatMap { it.all() }.filter { (it.localName == "a" || it.localName == "event-listener") && it.a(XLINK, "href")?.startsWith("#tetra-slide:") == true })
            a.setAttributeNS(XLINK, "xlink:href", a.a(XLINK, "href")!!.removePrefix("#tetra-slide:").toIntOrNull()?.let(pageName::get)?.let { "#$it" } ?: "")
        // Slide show › Loop until Esc: the presentation's settings say endless
        if (edit.loop != null || edit.noAnimation != null || edit.manual != null) { val st = pres.kids().firstOrNull { it.localName == "settings" && it.namespaceURI == PRES }
            ?: doc.createElementNS(PRES, "presentation:settings").also { pres.appendChild(it) }
            edit.loop?.let { loop -> if (loop) { st.setAttributeNS(PRES, "presentation:endless", "true"); st.setAttributeNS(PRES, "presentation:pause", "PT0S") } else st.removeAttributeNS(PRES, "endless") }
            // Set Up Slide Show: LibreOffice's Animations allowed and Change slides manually
            edit.noAnimation?.let { if (it) st.setAttributeNS(PRES, "presentation:animations", "disabled") else st.removeAttributeNS(PRES, "animations") }
            edit.manual?.let { if (it) st.setAttributeNS(PRES, "presentation:force-manual", "true") else st.removeAttributeNS(PRES, "force-manual") } }
        // the pages in their order where they were (before the presentation's settings)
        pages.forEach { pres.removeChild(it) }
        val after = pres.kids().firstOrNull { it.localName == "settings" }
        ordered.forEach { pres.insertBefore(it, after) }
        out["content.xml"] = xml(doc)
        // References: the sources (Word's form) and their style in meta.xml's user-defined properties
        if (edit.sources != null || edit.citeStyle != null) read("meta.xml")?.let { String(it) }?.let { m ->
            fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            val style = edit.citeStyle ?: deck.citeStyle
            val xml = """<b:Sources xmlns:b="http://schemas.openxmlformats.org/officeDocument/2006/bibliography" xmlns="http://schemas.openxmlformats.org/officeDocument/2006/bibliography" StyleName="${style.wordName}">""" +
                (edit.sources ?: deck.sources).joinToString("") { WordSources.source(it) } + "</b:Sources>"
            val mine = """<meta:user-defined meta:name="${SlidesOdp.SOURCES_NAME}">${esc(xml)}</meta:user-defined><meta:user-defined meta:name="${SlidesOdp.STYLE_NAME}">${style.name}</meta:user-defined>"""
            val clean = m.replace(Regex("""<meta:user-defined meta:name="(${Regex.escape(SlidesOdp.SOURCES_NAME)}|${Regex.escape(SlidesOdp.STYLE_NAME)})"[^>]*>[\s\S]*?</meta:user-defined>"""), "")
            out["meta.xml"] = (if ("</office:meta>" in clean) clean.replace("</office:meta>", "$mine</office:meta>") else clean.replace(Regex("""<office:meta\s*/>"""), "<office:meta>$mine</office:meta>")).toByteArray()
        }
        if (fillImages.isNotEmpty()) read("styles.xml")?.let { String(it) }?.let { st ->
            val images = fillImages.values.withIndex().joinToString("") { (k, href) -> """<draw:fill-image draw:name="tetra-bg-${k + 1}" xlink:href="$href" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad"/>""" }
            out["styles.xml"] = (if ("</office:styles>" in st) st.replace("</office:styles>", "$images</office:styles>") else st.replace("<office:automatic-styles", "<office:styles>$images</office:styles><office:automatic-styles")).toByteArray()
        }
        if (fillGradients.isNotEmpty()) (out["styles.xml"] ?: read("styles.xml"))?.let { String(it) }?.let { st ->
            val defs = fillGradients.withIndex().joinToString("") { (k, g) -> "<draw:gradient draw:name=\"tetra-grad-${k + 1}\" draw:display-name=\"Tetra gradient ${k + 1}\" draw:style=\"linear\" draw:cx=\"50%\" draw:cy=\"50%\" draw:start-color=\"${hex(g.first)}\" draw:end-color=\"${hex(g.second)}\" draw:start-intensity=\"100%\" draw:end-intensity=\"100%\" draw:angle=\"0deg\" draw:border=\"0%\"/>" }
            out["styles.xml"] = (if ("</office:styles>" in st) st.replace("</office:styles>", "$defs</office:styles>") else st.replace("<office:automatic-styles", "<office:styles>$defs</office:styles><office:automatic-styles")).toByteArray()
        }
        if (dashes.isNotEmpty()) (out["styles.xml"] ?: read("styles.xml"))?.let { out["styles.xml"] = LineDash.intoStyles(String(it), dashes).toByteArray() }
        // Arrange › On every slide: what was put on the slide master onto each master page (styles.xml), the automatic styles it names
        // copied there from content.xml (a master page can only name styles.xml's); the master's own shapes taken off where they were
        val everySlide = edit.added[SlidesEdit.MASTER].orEmpty(); val offMaster = edit.removed.filter { it.first == SlidesEdit.MASTER }.map { it.second }
        if (everySlide.isNotEmpty() || offMaster.isNotEmpty()) (out["styles.xml"] ?: read("styles.xml"))?.let { bytes ->
            val sdoc = OfficeFormats.parse(bytes)
            val sAutos = sdoc.documentElement.kids().firstOrNull { it.localName == "automatic-styles" }
                ?: sdoc.createElementNS(OFFICE, "office:automatic-styles").also { a -> sdoc.documentElement.insertBefore(a, sdoc.documentElement.kids().firstOrNull { it.localName == "master-styles" }) }
            val copied = HashSet<String>()
            fun copyStyles(el: Element) {
                val named = (listOf(el) + el.all()).flatMap { x -> (0 until x.attributes.length).map { x.attributes.item(it) as org.w3c.dom.Attr }.filter { it.localName?.endsWith("style-name") == true }.map { it.value } }
                for (nm in named) if (copied.add(nm)) autos.kids().firstOrNull { it.a(STYLE, "name") == nm || it.a(TEXT, "name") == nm }?.let { st ->
                    sAutos.appendChild(sdoc.importNode(st, true)); copyStyles(st) }
            }
            for (mp in sdoc.getElementsByTagNameNS(STYLE, "master-page").let { l -> (0 until l.length).map { l.item(it) as Element } }) {
                val name = mp.a(STYLE, "name")
                val gone = offMaster.filter { it.startsWith("$name ") }.mapNotNull { it.substringAfterLast(' ').toIntOrNull() }.toSet()
                mp.kids().withIndex().filter { it.index in gone }.map { it.value }.forEach { mp.removeChild(it) }
                for (a in everySlide) added(a, edit.looks[SlidesEdit.MASTER to a.id])?.let { el -> edit.texts[SlidesEdit.MASTER to a.id]?.let { type(el, it) }
                    edit.looks[SlidesEdit.MASTER to a.id]?.takeIf { it.words }?.let { words(el, it) }
                    copyStyles(el); mp.appendChild(sdoc.importNode(el, true)) }
            }
            out["styles.xml"] = xml(sdoc)
        }
        if (manifest != null && out.keys.any { it.startsWith("Pictures/") || it.startsWith("Media/") || it.startsWith("Object tetra-") }) out["META-INF/manifest.xml"] = xml(manifest)
        SheetSave.rezip(zip, target, out, stored = "mimetype")
        zip.close()
    }
}
