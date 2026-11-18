package com.kalotrapezis.drive

import org.w3c.dom.Element
import java.io.File

/**
 * Design › Slide size (PowerPoint's): a copy of a deck made another size — Widescreen 16:9, 16:10, Standard 4:3, 3:2, 1:2 or one's own — every slide's,
 * layout's and master's things scaled into it as PowerPoint does, "Ensure fit" (all of it smaller, in the middle, its
 * letters too) or "Maximize" (as big as the new size allows, cut at its sides). Shown and saved from the copy, as a theme is.
 */
internal object SlidesSize {
    /** A size chosen: [w] × [h] points, maximized or fitted. */
    data class Size(val w: Float, val h: Float, val maximize: Boolean)
    /** PowerPoint's two (13.333 × 7.5 in and 10 × 7.5 in), in points. */
    const val WIDE_W = 960f; const val STANDARD_W = 720f; const val HEIGHT = 540f
    /** The sizes offered (w × h pt): PowerPoint's Widescreen, On-screen Show (16:10), Standard, 35mm slides (3:2), and a tall 1:2 page. */
    val PRESETS = listOf(R.string.slides_size_wide to (WIDE_W to HEIGHT), R.string.slides_size_1610 to (720f to 450f), R.string.slides_size_standard to (STANDARD_W to HEIGHT),
        R.string.slides_size_32 to (810f to HEIGHT), R.string.slides_size_tall to (540f to 1080f))
    /** PowerPoint's own name for a size (p:sldSz's type), "custom" for one it has none for; Widescreen has none written. */
    fun type(w: Float, h: Float): String? {
        fun at(a: Float, b: Float) = Math.abs(w - a) < 0.5f && Math.abs(h - b) < 0.5f
        return when { at(WIDE_W, HEIGHT) -> null; at(STANDARD_W, HEIGHT) -> "screen4x3"; at(720f, 450f) -> "screen16x10"; at(810f, HEIGHT) -> "35mm"; else -> "custom" }
    }

    /** How [w0] × [h0] goes into [w] × [h]: its scale and the shift across and down (pt). */
    fun fit(w0: Float, h0: Float, w: Float, h: Float, maximize: Boolean): Triple<Float, Float, Float> {
        val k = if (maximize) maxOf(w / w0, h / h0) else minOf(w / w0, h / h0)
        return Triple(k, (w - w0 * k) / 2, (h - h0 * k) / 2)
    }

    fun resize(source: File, size: Size, target: File) {
        val parts = OfficeFormats.unzip(source.readBytes())
        if (source.extension.equals("odp", true)) odp(parts, size) else pptx(parts, size)
        target.writeBytes(OfficeFormats.zip(parts, if (source.extension.equals("odp", true)) "mimetype" else null))
    }

    private fun Element.kids() = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
    private fun Element.all(): List<Element> = kids().flatMap { listOf(it) + it.all() }

    private fun pptx(parts: LinkedHashMap<String, ByteArray>, size: Size) {
        val pres = String(parts["ppt/presentation.xml"] ?: return, Charsets.UTF_8)
        val sz = Regex("""<p:sldSz\b[^>]*/>""").find(pres) ?: return
        fun attr(n: String) = Regex("""\b$n="(\d+)"""").find(sz.value)?.groupValues?.get(1)?.toFloatOrNull()
        val w0 = (attr("cx") ?: return) / 12700f; val h0 = (attr("cy") ?: return) / 12700f
        val (k, dx, dy) = fit(w0, h0, size.w, size.h, size.maximize)
        val type = type(size.w, size.h)?.let { """ type="$it"""" } ?: ""
        parts["ppt/presentation.xml"] = pres.replaceRange(sz.range, """<p:sldSz cx="${Math.round(size.w * 12700)}" cy="${Math.round(size.h * 12700)}"$type/>""").toByteArray()
        for (name in parts.keys.filter { Regex("""ppt/(slides|slideLayouts|slideMasters)/[^/]+\.xml""").matches(it) }) {
            val doc = OfficeFormats.parse(parts.getValue(name)); val root = doc.documentElement
            for (x in root.all().filter { it.localName == "xfrm" }) {
                // a thing in a group stays in the group's own units (the group's box scaled takes it along)
                val owner = (x.parentNode as Element).let { p -> if (p.localName == "spPr" || p.localName == "grpSpPr") p.parentNode as Element else p }
                var up = owner.parentNode as? Element; var inGroup = false
                while (up != null) { if (up.localName == "grpSp") { inGroup = true; break }; up = up.parentNode as? Element }
                if (inGroup) continue
                x.kids().firstOrNull { it.localName == "off" }?.let { o -> o.setAttribute("x", "${Math.round((o.getAttribute("x").toLongOrNull() ?: 0) * k + dx * 12700)}"); o.setAttribute("y", "${Math.round((o.getAttribute("y").toLongOrNull() ?: 0) * k + dy * 12700)}") }
                x.kids().firstOrNull { it.localName == "ext" }?.let { e -> e.setAttribute("cx", "${Math.round((e.getAttribute("cx").toLongOrNull() ?: 0) * k)}"); e.setAttribute("cy", "${Math.round((e.getAttribute("cy").toLongOrNull() ?: 0) * k)}") }
            }
            // the letters and the room round lines with it (hundredths of a point), as PowerPoint's Ensure fit
            if (k != 1f) for (e in root.all()) {
                if (e.localName in setOf("rPr", "defRPr", "endParaRPr") && e.hasAttribute("sz")) e.getAttribute("sz").toIntOrNull()?.let { e.setAttribute("sz", "${maxOf(100, Math.round(it * k / 50f) * 50)}") }
                if (e.localName == "spcPts" && e.hasAttribute("val")) e.getAttribute("val").toIntOrNull()?.let { e.setAttribute("val", "${Math.round(it * k)}") }
            }
            parts[name] = SlidesEdit.domXml(doc, standalone = true)
        }
    }

    private const val DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
    private const val SVG = "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"
    private const val STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    private const val FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
    private const val PRES = "urn:oasis:names:tc:opendocument:xmlns:presentation:1.0"
    private fun cm(pt: Float) = String.format(java.util.Locale.ROOT, "%.3fcm", pt / 28.346457f)

    private fun odp(parts: LinkedHashMap<String, ByteArray>, size: Size) {
        val styles = OfficeFormats.parse(parts["styles.xml"] ?: return); val content = OfficeFormats.parse(parts["content.xml"] ?: return)
        val masters = styles.getElementsByTagNameNS(STYLE, "master-page").let { l -> (0 until l.length).map { l.item(it) as Element } }
        val used = masters.map { it.getAttributeNS(STYLE, "page-layout-name") }.toSet()
        val layouts = styles.getElementsByTagNameNS(STYLE, "page-layout").let { l -> (0 until l.length).map { l.item(it) as Element } }.filter { it.getAttributeNS(STYLE, "name") in used }
        val props = layouts.mapNotNull { it.kids().firstOrNull { k -> k.localName == "page-layout-properties" } }
        val first = props.firstOrNull() ?: return
        val w0 = odfLength(first.getAttributeNS(FO, "page-width")) ?: return; val h0 = odfLength(first.getAttributeNS(FO, "page-height")) ?: return
        val (k, dx, dy) = fit(w0, h0, size.w, size.h, size.maximize)
        props.forEach { it.setAttributeNS(FO, "fo:page-width", cm(size.w)); it.setAttributeNS(FO, "fo:page-height", cm(size.h)) }
        // every thing on the pages and master pages (a group's too: ODF places them on the page), not the notes pages
        fun place(e: Element) {
            for ((n, across) in listOf("x" to true, "y" to false, "x1" to true, "y1" to false, "x2" to true, "y2" to false))
                odfLength(e.getAttributeNS(SVG, n).takeIf { it.isNotEmpty() })?.let { e.setAttributeNS(SVG, "svg:$n", cm(it * k + if (across) dx else dy)) }
            for (n in listOf("width", "height")) odfLength(e.getAttributeNS(SVG, n).takeIf { it.isNotEmpty() })?.let { e.setAttributeNS(SVG, "svg:$n", cm(it * k)) }
            // turned: its translate is where it stands on the page
            e.getAttributeNS(DRAW, "transform").takeIf { it.isNotEmpty() }?.let { t ->
                e.setAttributeNS(DRAW, "draw:transform", Regex("""translate\s*\(\s*([-\d.]+\w*)\s+([-\d.]+\w*)\s*\)""").replace(t) { m ->
                    val x = odfLength(m.groupValues[1]) ?: return@replace m.value; val y = odfLength(m.groupValues[2]) ?: return@replace m.value
                    "translate (${cm(x * k + dx)} ${cm(y * k + dy)})" }) }
            e.kids().filter { it.localName != "notes" }.forEach(::place)
        }
        masters.forEach { m -> m.kids().filter { it.localName != "notes" }.forEach(::place) }
        content.getElementsByTagNameNS(DRAW, "page").let { l -> (0 until l.length).forEach { i -> (l.item(i) as Element).kids().filter { it.localName != "notes" }.forEach(::place) } }
        // the letters with it, as PowerPoint's Ensure fit (sizes in points; a percent is of its parent's)
        if (k != 1f) for (doc in listOf(styles, content)) for (e in doc.documentElement.all()) for (n in listOf("font-size", "font-size-asian", "font-size-complex")) {
            val ns = if (n == "font-size") FO else STYLE
            e.getAttributeNS(ns, n).takeIf { it.endsWith("pt") }?.removeSuffix("pt")?.toFloatOrNull()?.let { e.setAttributeNS(ns, "${if (ns == FO) "fo" else "style"}:$n", String.format(java.util.Locale.ROOT, "%.1fpt", it * k)) }
        }
        parts["styles.xml"] = SlidesEdit.domXml(styles); parts["content.xml"] = SlidesEdit.domXml(content)
    }
}
