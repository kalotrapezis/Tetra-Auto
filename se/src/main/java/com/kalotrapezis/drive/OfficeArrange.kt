package com.kalotrapezis.drive

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/** One piece of a group (Word's wpg): where it is in the group's box, in points, and what it is. */
internal data class GroupPart(val x: Float, val y: Float, val w: Float, val h: Float, val img: DocImage)

/**
 * Arrange › Rotate, Group and Ungroup (Phase 2) for floating pictures, shapes and text boxes — in each program's words:
 * Word's a:xfrm rot (60000ths of a degree, clockwise) and wpg:wgp groups; LibreOffice's draw:transform (radians,
 * counter-clockwise, about the frame's corner, then moved).
 */
internal object Arrange {
    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private const val WPG = "http://schemas.microsoft.com/office/word/2010/wordprocessingGroup"
    private const val GROUP_URI = WPG
    private const val SHAPE_URI = "http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
    private const val PICTURE_URI = "http://schemas.openxmlformats.org/drawingml/2006/picture"
    /** Prefixes a piece of Word's XML may use, declared where it is cut out so it stands alone. */
    private val NAMESPACES = mapOf(
        "a" to A, "wp" to WP, "wpg" to WPG, "wps" to SHAPE_URI, "pic" to PICTURE_URI,
        "r" to "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
        "w" to "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
        "w14" to "http://schemas.microsoft.com/office/word/2010/wordml",
        "wp14" to "http://schemas.microsoft.com/office/word/2010/wordprocessingDrawing",
        "a14" to "http://schemas.microsoft.com/office/drawing/2010/main",
        "mc" to "http://schemas.openxmlformats.org/markup-compatibility/2006",
        "v" to "urn:schemas-microsoft-com:vml", "o" to "urn:schemas-microsoft-com:office:office")

    // ---- rotation

    /** Degrees clockwise, as Word says it (0: not turned). */
    fun rotationOf(img: DocImage): Float = rotationOf(img.raw)
    fun rotationOf(raw: String?): Float {
        raw ?: return 0f
        if (raw.startsWith("<draw:")) return odtTransform(raw.substring(0, raw.indexOf('>').coerceAtLeast(0)))?.first ?: 0f
        return Regex("""<a:xfrm\b[^>]*?\brot="(-?\d+)"""").find(raw)?.groupValues?.get(1)?.toFloatOrNull()?.div(60000f)?.let(::normal) ?: 0f
    }
    private fun normal(d: Float) = ((d % 360f) + 360f) % 360f

    /** [img] turned to [degrees] clockwise (about its middle); null: its XML has no place to say it. */
    fun rotated(img: DocImage, degrees: Float): DocImage? {
        val raw = img.raw ?: return null
        val d = normal(degrees).let { if (it > 359.95f) 0f else it }
        if (raw.startsWith("<draw:g")) return null // LibreOffice turns a group's pieces each; ungroup first
        if (raw.startsWith("<draw:")) return img.with(raw = odtTurned(raw, d, img.widthPt, img.heightPt))
        val m = Regex("""<a:xfrm\b([^>]*?)(/?)>""").find(raw) ?: return null
        val attrs = m.groupValues[1].replace(Regex("""\srot="[^"]*""""), "") + if (d == 0f) "" else " rot=\"${(d * 60000).roundToLong()}\""
        return img.with(raw = raw.replaceRange(m.range, "<a:xfrm$attrs${m.groupValues[2]}>"))
    }
    /** An OpenDocument frame or shape turned to [d] degrees clockwise about its middle (its unturned corner kept). */
    fun odtTurned(raw: String, d: Float, widthPt: Float = 0f, heightPt: Float = 0f): String {
        val end = raw.indexOf('>')
        val head = raw.substring(0, end)
        val w = odfLength(attr(head, "svg:width")) ?: widthPt
        val h = odfLength(attr(head, "svg:height")) ?: heightPt
        val (x, y) = odtCorner(head, w, h)
        val bare = head.replace(Regex("""\s(svg:x|svg:y|draw:transform)="[^"]*""""), "")
        val placed = if (d == 0f) "$bare svg:x=\"${cm(x.toDouble())}cm\" svg:y=\"${cm(y.toDouble())}cm\"" else {
            val a = -d * PI / 180
            val tx = x + w / 2 - (w / 2 * cos(a) + h / 2 * sin(a))
            val ty = y + h / 2 - (-w / 2 * sin(a) + h / 2 * cos(a))
            "$bare draw:transform=\"rotate (${"%.9f".format(java.util.Locale.ROOT, a)}) translate (${cm(tx)}cm ${cm(ty)}cm)\""
        }
        return placed + raw.substring(end)
    }
    private fun cm(pt: Double) = "%.4f".format(java.util.Locale.ROOT, pt / 28.3465)
    private fun attr(head: String, n: String) = Regex("""\s$n="([^"]*)"""").find(head)?.groupValues?.get(1)

    /** An OpenDocument frame's draw:transform: (degrees clockwise, its unturned top left in points). */
    fun odtTransform(head: String): Pair<Float, Pair<Float, Float>>? {
        val t = attr(head, "draw:transform") ?: return null
        val a = Regex("""rotate\s*\(\s*([-+\d.eE]+)\s*\)""").find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val tr = Regex("""translate\s*\(\s*(\S+?)\s*[ ,]\s*(\S+?)\s*\)""").find(t) ?: return null
        val tx = odfLength(tr.groupValues[1]) ?: return null
        val ty = odfLength(tr.groupValues[2]) ?: return null
        val w = odfLength(attr(head, "svg:width")) ?: 0f
        val h = odfLength(attr(head, "svg:height")) ?: 0f
        // The middle: where the frame's own middle lands; its unturned corner half its size back from there.
        val cx = tx + w / 2 * cos(a) + h / 2 * sin(a)
        val cy = ty - w / 2 * sin(a) + h / 2 * cos(a)
        return normal((-a * 180 / PI).toFloat()) to ((cx - w / 2).toFloat() to (cy - h / 2).toFloat())
    }
    private fun odtCorner(head: String, w: Float, h: Float) = odtTransform(head)?.second ?: ((odfLength(attr(head, "svg:x")) ?: 0f) to (odfLength(attr(head, "svg:y")) ?: 0f))

    // ---- groups (LibreOffice's draw:g)

    private fun xmlOf(raw: String) = runCatching {
        // Not namespace-aware: a piece cut out of content.xml uses prefixes it does not declare.
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(raw))).documentElement
    }.getOrNull()
    private fun drawKids(e: org.w3c.dom.Element) = (0 until e.childNodes.length).mapNotNull { e.childNodes.item(it) as? org.w3c.dom.Element }.filter { it.tagName.startsWith("draw:") }
    private fun headOf(e: org.w3c.dom.Element) = "<" + e.tagName + (0 until e.attributes.length).joinToString("") { val a = e.attributes.item(it); " ${a.nodeName}=\"${a.nodeValue}\"" }

    /** A drawing's box (x, y, width, height in points, where its anchor puts it): a group's, round all its pieces. */
    fun odtGroupBox(raw: String): FloatArray? {
        val e = xmlOf(raw) ?: return null
        fun box(e: org.w3c.dom.Element): FloatArray? {
            if (e.tagName == "draw:g") {
                val all = drawKids(e).mapNotNull(::box).ifEmpty { return null }
                val x = all.minOf { it[0] }; val y = all.minOf { it[1] }
                return floatArrayOf(x, y, all.maxOf { it[0] + it[2] } - x, all.maxOf { it[1] + it[3] } - y)
            }
            val head = headOf(e)
            val w = odfLength(attr(head, "svg:width")) ?: return null; val h = odfLength(attr(head, "svg:height")) ?: return null
            val (x, y) = odtCorner(head, w, h)
            return floatArrayOf(x, y, w, h)
        }
        return box(e)
    }

    /**
     * A group's pieces moved together, its box's corner to [to] (pt; null: where it is), and grown to [w] × [h] (each
     * piece's place and size scaled from the box's corner; null: as they are).
     */
    fun odtGroupMoved(raw: String, to: Pair<Float, Float>?, w: Float? = null, h: Float? = null): String {
        val b = odtGroupBox(raw) ?: return raw
        val sx = w?.let { it / b[2].coerceAtLeast(0.1f) } ?: 1f; val sy = h?.let { it / b[3].coerceAtLeast(0.1f) } ?: 1f
        val tx = to?.first ?: b[0]; val ty = to?.second ?: b[1]
        // Every piece's opening tag, wherever it stands in the group (in a group inside it too).
        return Regex("""<draw:(frame|custom-shape|rect|ellipse|line|polygon|polyline|path|circle|connector|measure|control)\b[^>]*>""").replace(raw) { m ->
            val head = m.value.removeSuffix(">").removeSuffix("/")
            val pw = odfLength(attr(head, "svg:width")); val ph = odfLength(attr(head, "svg:height"))
            if (pw == null || ph == null) return@replace m.value
            val turn = rotationOf(head + ">")
            val (x, y) = odtCorner(head, pw, ph)
            val nw = pw * sx; val nh = ph * sy
            val placed = head.replace(Regex("""\s(svg:x|svg:y|draw:transform)="[^"]*""""), "")
                .replace(Regex("""svg:width="[^"]*""""), "svg:width=\"${cm(nw.toDouble())}cm\"").replace(Regex("""svg:height="[^"]*""""), "svg:height=\"${cm(nh.toDouble())}cm\"") +
                " svg:x=\"${cm((tx + (x - b[0]) * sx).toDouble())}cm\" svg:y=\"${cm((ty + (y - b[1]) * sy).toDouble())}cm\""
            val end = if (m.value.endsWith("/>")) "/>" else ">"
            if (turn == 0f) placed + end else odtTurned(placed + end, turn, nw, nh)
        }
    }

    /** LibreOffice's group of [pieces] (each its drawing and its box on the page, pt), placed on that page. */
    fun odtGroup(pieces: List<Pair<DocImage, FloatArray>>, wrap: PictureWrap, z: Long, key: String): DocImage? {
        if (pieces.size < 2) return null
        val gx = pieces.minOf { it.second[0] }; val gy = pieces.minOf { it.second[1] }
        val gw = pieces.maxOf { it.second[0] + it.second[2] } - gx; val gh = pieces.maxOf { it.second[1] + it.second[3] } - gy
        if (pieces.any { it.first.raw?.startsWith("<draw:") != true }) return null
        val inner = pieces.joinToString("") { (img, b) ->
            val raw = img.raw!!
            // Its anchor is the group's now; its place, as on the page (the group's style places it from the page).
            val e = raw.indexOf('>')
            val head = raw.substring(0, e).replace(Regex("""\s(text:anchor-type|draw:z-index)="[^"]*""""), "")
            if (raw.startsWith("<draw:g")) odtGroupMoved(head + raw.substring(e), b[0] to b[1])
            else Regex("""^<draw:g\b[^>]*>([\s\S]*)</draw:g>$""").find(odtGroupMoved("<draw:g>" + head + raw.substring(e) + "</draw:g>", b[0] to b[1]))!!.groupValues[1]
        }
        val n = System.nanoTime() % 900000 + 100000
        val raw = """<draw:g draw:name="Group $n" draw:z-index="$z" text:anchor-type="char">$inner</draw:g>"""
        val parts = pieces.map { (img, b) -> GroupPart(b[0] - gx, b[1] - gy, b[2], b[3], img) }
        return DocImage(key, null, gw, gh, PictureXml.odt(raw, wrap, gx to gy), wrap = wrap, onPage = gx to gy, group = parts)
    }

    /** A LibreOffice group taken apart: each piece floating on its own where it was (the group's box at [x], [y] on its page). */
    fun odtUngroup(img: DocImage, x: Float, y: Float, wrap: PictureWrap, z: Long): List<DocImage>? {
        val root = img.raw?.takeIf { it.startsWith("<draw:g") }?.let(::xmlOf) ?: return null
        val gb = odtGroupBox(img.raw) ?: return null
        val out = ArrayList<DocImage>()
        for (k in drawKids(root)) {
            val xml = OfficeFormats.xml(k)
            val b = odtGroupBox(xml) ?: continue
            val part = img.group?.getOrNull(out.size)?.img
            val at = (x + b[0] - gb[0]) to (y + b[1] - gb[1])
            val head = xml.substring(0, xml.indexOf('>')).replace(Regex("""\sdraw:z-index="[^"]*""""), "") + " draw:z-index=\"${z + out.size}\""
            val placed = PictureXml.odt(if (xml.startsWith("<draw:g")) head + xml.substring(xml.indexOf('>')) else odtGroupMoved("<draw:g>" + head + xml.substring(xml.indexOf('>')) + "</draw:g>", at)
                .let { Regex("""^<draw:g>([\s\S]*)</draw:g>$""").find(it)!!.groupValues[1] }, wrap, at)
            out += DocImage("u" + java.util.UUID.randomUUID(), part?.part, b[2], b[3], placed, bytes = part?.bytes, extension = part?.extension ?: "png",
                wrap = wrap, onPage = at, box = part?.box, chart = part?.chart, group = part?.group, look = part?.look)
        }
        return out.takeIf { it.size >= 2 }
    }

    // ---- groups (Word)

    /** The drawing's own piece: a picture, a shape (or text box) or a group, as a group holds it. */
    fun graphicOf(raw: String): String? = Regex("""<(pic:pic|wps:wsp|wpg:wgp)\b[\s\S]*</\1>""").find(raw)?.value

    /** [xml] with every prefix it uses declared on its first element (it is cut out of a document that declared them). */
    fun declared(xml: String): String {
        val end = Regex("""^<[\w:]+""").find(xml)?.range?.last?.plus(1) ?: return xml
        val head = xml.substring(0, xml.indexOf('>'))
        val used = Regex("""</?(\w+):|\s(\w+):\w+=""").findAll(xml).mapNotNull { (it.groups[1] ?: it.groups[2])?.value }.toSet() - setOf("xmlns", "xml")
        val add = used.filter { "xmlns:$it=" !in head }.mapNotNull { p -> NAMESPACES[p]?.let { " xmlns:$p=\"$it\"" } }.joinToString("")
        return xml.substring(0, end) + add + xml.substring(end)
    }

    /** A piece set in its group's box: its a:xfrm's place and size (in EMU, the group's own units). */
    private fun placedPiece(graphic: String, x: Long, y: Long, cx: Long, cy: Long): String {
        val xf = Regex("""<a:xfrm\b([^>]*?)(?:/>|>([\s\S]*?)</a:xfrm>)""").find(graphic)
        // A group's own box: its pieces' units kept (chOff/chExt), only where it is changes.
        val rest = xf?.groupValues?.get(2)?.let { Regex("""<a:chOff[^>]*/><a:chExt[^>]*/>""").find(it)?.value }.orEmpty()
        val box = "<a:xfrm${xf?.groupValues?.get(1).orEmpty()}><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$cx\" cy=\"$cy\"/>$rest</a:xfrm>"
        if (xf != null) return graphic.replaceRange(xf.range, box)
        // None (seldom): the first thing in its shape properties.
        val spPr = Regex("""<(pic:spPr|wps:spPr|wpg:grpSpPr)\b[^>]*?(/?)>""").find(graphic) ?: return graphic
        return if (spPr.groupValues[2] == "/") graphic.replaceRange(spPr.range, "<${spPr.groupValues[1]}>$box</${spPr.groupValues[1]}>")
            else graphic.replaceRange(spPr.range, spPr.value + box)
    }

    /** A group's piece in its parent: a group inside a group is a wpg:grpSp. */
    private fun asPiece(graphic: String) = if (graphic.startsWith("<wpg:wgp")) graphic.replaceFirst("<wpg:wgp", "<wpg:grpSp").removeSuffix("</wpg:wgp>") + "</wpg:grpSp>" else graphic
    private fun asDrawing(piece: String) = if (piece.startsWith("<wpg:grpSp")) piece.replaceFirst("<wpg:grpSp", "<wpg:wgp").removeSuffix("</wpg:grpSp>") + "</wpg:wgp>" else piece

    private fun how(wrap: PictureWrap) = when (wrap) { PictureWrap.Square -> """<wp:wrapSquare wrapText="bothSides"/>"""; PictureWrap.TopBottom -> "<wp:wrapTopAndBottom/>"; else -> "<wp:wrapNone/>" }
    private fun emu(pt: Float) = (pt * 12700).roundToLong()

    /** A floating drawing at [x], [y] on its page ([w] × [h] pt), holding [graphic] (a pic:pic, wps:wsp or wpg:wgp). */
    fun anchor(graphic: String, x: Float, y: Float, w: Float, h: Float, wrap: PictureWrap, z: Long, id: Long, name: String): String {
        val uri = when { graphic.startsWith("<pic:") -> PICTURE_URI; graphic.startsWith("<wps:") -> SHAPE_URI; else -> GROUP_URI }
        return """<w:drawing><wp:anchor distT="0" distB="0" distL="114300" distR="114300" simplePos="0" relativeHeight="$z" behindDoc="${if (wrap == PictureWrap.Behind) 1 else 0}" locked="0" layoutInCell="1" allowOverlap="1" xmlns:wp="$WP">""" +
            """<wp:simplePos x="0" y="0"/><wp:positionH relativeFrom="page"><wp:posOffset>${emu(x)}</wp:posOffset></wp:positionH><wp:positionV relativeFrom="page"><wp:posOffset>${emu(y)}</wp:posOffset></wp:positionV>""" +
            """<wp:extent cx="${emu(w)}" cy="${emu(h)}"/><wp:effectExtent l="0" t="0" r="0" b="0"/>${how(wrap)}<wp:docPr id="$id" name="$name"/><wp:cNvGraphicFramePr/>""" +
            """<a:graphic xmlns:a="$A"><a:graphicData uri="$uri">${declared(graphic)}</a:graphicData></a:graphic></wp:anchor></w:drawing>"""
    }

    /**
     * Word's group of [pieces] — each (its drawing, its box on the page in points) — as one floating drawing at their
     * box's corner on that page; wrapped as [wrap], at [z]. Null: one has no piece Word can group.
     */
    fun group(pieces: List<Pair<DocImage, FloatArray>>, wrap: PictureWrap, z: Long, key: String): DocImage? {
        if (pieces.size < 2) return null
        val gx = pieces.minOf { it.second[0] }; val gy = pieces.minOf { it.second[1] }
        val gw = pieces.maxOf { it.second[0] + it.second[2] } - gx; val gh = pieces.maxOf { it.second[1] + it.second[3] } - gy
        val inner = pieces.map { (img, b) ->
            val g = img.raw?.let(::graphicOf) ?: return null
            placedPiece(asPiece(declared(g)), emu(b[0] - gx), emu(b[1] - gy), emu(b[2]), emu(b[3]))
        }
        val wgp = """<wpg:wgp xmlns:wpg="$WPG"><wpg:cNvGrpSpPr/><wpg:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="${emu(gw)}" cy="${emu(gh)}"/><a:chOff x="0" y="0"/><a:chExt cx="${emu(gw)}" cy="${emu(gh)}"/></a:xfrm></wpg:grpSpPr>""" +
            inner.joinToString("") + "</wpg:wgp>"
        val id = System.nanoTime() % 900000 + 100000
        val parts = pieces.map { (img, b) -> GroupPart(b[0] - gx, b[1] - gy, b[2], b[3], img) }
        return DocImage(key, null, gw, gh, anchor(wgp, gx, gy, gw, gh, wrap, z, id, "Group $id"), wrap = wrap, onPage = gx to gy, group = parts)
    }

    /**
     * A group taken apart: each piece a floating drawing of its own where it was on the page (the group at [x], [y]),
     * wrapped as the group was. Null: not a group Writer can take apart.
     */
    fun ungroup(img: DocImage, x: Float, y: Float, wrap: PictureWrap, z: Long): List<DocImage>? {
        val raw = img.raw ?: return null
        val wgp = graphicOf(raw)?.takeIf { it.startsWith("<wpg:wgp") } ?: return null
        val doc = runCatching {
            val f = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            f.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(declared(wgp))))
        }.getOrNull() ?: return null
        val root = doc.documentElement
        fun kids(e: org.w3c.dom.Element) = (0 until e.childNodes.length).mapNotNull { e.childNodes.item(it) as? org.w3c.dom.Element }
        fun xfrmOf(e: org.w3c.dom.Element): org.w3c.dom.Element? = kids(e).firstOrNull { it.localName.endsWith("spPr") || it.localName == "grpSpPr" }?.let { pr -> kids(pr).firstOrNull { it.localName == "xfrm" } }
        fun num(e: org.w3c.dom.Element?, kid: String, a: String) = e?.let { kids(it).firstOrNull { k -> k.localName == kid }?.getAttribute(a)?.toLongOrNull() } ?: 0L
        val gx = xfrmOf(root)
        val sx = num(gx, "ext", "cx").toFloat() / num(gx, "chExt", "cx").coerceAtLeast(1); val sy = num(gx, "ext", "cy").toFloat() / num(gx, "chExt", "cy").coerceAtLeast(1)
        val out = ArrayList<DocImage>()
        for (k in kids(root).filter { it.localName in setOf("wsp", "pic", "grpSp") }) {
            val kx = xfrmOf(k)
            val px = x + (num(kx, "off", "x") - num(gx, "chOff", "x")) * sx / 12700f
            val py = y + (num(kx, "off", "y") - num(gx, "chOff", "y")) * sy / 12700f
            val pw = num(kx, "ext", "cx") * sx / 12700f; val ph = num(kx, "ext", "cy") * sy / 12700f
            val piece = asDrawing(placedPiece(OfficeFormats.xml(k), 0, 0, emu(pw), emu(ph)))
            val id = System.nanoTime() % 900000 + 100000 + out.size
            val part = img.group?.getOrNull(out.size)?.img
            out += DocImage("u" + java.util.UUID.randomUUID(), part?.part, pw, ph, anchor(piece, px, py, pw, ph, wrap, z + out.size, id, "Shape $id"),
                bytes = part?.bytes, extension = part?.extension ?: "png", wrap = wrap, onPage = px to py, box = part?.box, chart = part?.chart, group = part?.group)
        }
        return out.takeIf { it.size >= 2 }
    }
}
