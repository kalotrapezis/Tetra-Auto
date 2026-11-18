package com.kalotrapezis.drive

import org.json.JSONObject

/*
 * Design for a PDF (PDF_LIKE_WORDS.md, Design): Words' page colour, page border and watermark on every page, drawn by
 * Words' own page code (LaidPage.draw). The colour and border go behind the page's own drawing, as Word's; the
 * watermark over it (a scan's picture would hide it). What is set is kept in the file (the catalog's TetraDesign), so
 * Design shows it and a change or Remove takes the old one away.
 */

/** The catalog's key for what Design set, and the key marking Tetra's own pieces of a page's drawing. */
private const val DESIGN_KEY = "TetraDesign"

/** Design's settings: a page colour (0xRRGGBB), a page border, a watermark's words or its picture. */
internal data class PdfDesign(val color: Int? = null, val border: PageBorder? = null, val watermark: String? = null, val picture: ByteArray? = null) {
    val behind get() = color != null || border != null
    val over get() = watermark != null || picture != null

    fun json(): String = JSONObject().apply {
        color?.let { put("color", it) }
        border?.let { b -> put("border", JSONObject().put("width", b.line.width.toDouble()).put("color", b.line.color).put("style", b.line.style).put("sides", b.sides)
            .put("left", b.left.toDouble()).put("top", b.top.toDouble()).put("right", b.right.toDouble()).put("bottom", b.bottom.toDouble())) }
        watermark?.let { put("watermark", it) }
    }.toString()

    /** Drawn on page [w] × [h] (points), its top left at 0,0: [behind] the colour and border, else the watermark. */
    fun draw(c: android.graphics.Canvas, w: Float, h: Float, behind: Boolean) {
        val page = DocPage(w, h, color = color.takeIf { behind }, border = border.takeIf { behind })
        LaidPage(page, emptyList(), section = DocSection(page, watermark = watermark.takeIf { !behind }, watermarkPicture = picture.takeIf { !behind })).draw(c)
    }

    override fun equals(other: Any?) = other is PdfDesign && other.color == color && other.border == border && other.watermark == watermark && other.picture.contentEquals(picture)
    override fun hashCode() = json().hashCode()

    companion object {
        /** Word's page border: 24 pt in from the page's edges. */
        fun border(line: Border, sides: String) = PageBorder(line, 24f, 24f, 24f, 24f, sides)

        fun parse(s: String?, picture: ByteArray?): PdfDesign = runCatching {
            val o = JSONObject(s ?: return PdfDesign(picture = picture))
            PdfDesign(o.optInt("color", -1).takeIf { it >= 0 }, o.optJSONObject("border")?.let { b ->
                PageBorder(Border(b.getDouble("width").toFloat(), b.getInt("color"), b.optString("style", "single")),
                    b.getDouble("left").toFloat(), b.getDouble("top").toFloat(), b.getDouble("right").toFloat(), b.getDouble("bottom").toFloat(), b.optString("sides", "tlbr")) },
                o.optString("watermark").takeIf { it.isNotEmpty() }, picture)
        }.getOrDefault(PdfDesign())
    }
}

/** What Design set on this file. */
internal fun PdfDoc.design(): PdfDesign = synchronized(this) { runCatching {
    val root = pdfDoc.trailer.get("Root")
    PdfDesign.parse(root.get(DESIGN_KEY).takeIf { it.isString }?.asString(), root.get("TetraDesignPicture").takeIf { it.isStream }?.readStream())
}.getOrDefault(PdfDesign()) }

/**
 * Design [d] on every page (one undo step): Tetra's old design taken off each page's drawing, then the new one put
 * there — behind it a Form drawn by Words' code with the colour and border, over it one with the watermark.
 */
internal fun PdfDoc.setDesign(d: PdfDesign) = edit("Design") { pdf ->
    val root = pdf.trailer.get("Root")
    if (d == PdfDesign()) { root.delete(DESIGN_KEY); root.delete("TetraDesignPicture") }
    else { root.put(DESIGN_KEY, pdf.newString(d.json())); if (d.picture != null) root.put("TetraDesignPicture", pdf.addStream(com.artifex.mupdf.fitz.Buffer().apply { writeBytes(d.picture) }, pdf.newDictionary())) else root.delete("TetraDesignPicture") }
    sizes.forEachIndexed { i, shown ->
        val page = pdf.findPage(i)
        // the page's own drawing as it was before Tetra's
        val c = page.get("Contents")
        val all = if (c.isArray) (0 until c.size()).map { c.get(it) } else if (c.isNull) emptyList() else listOf(c)
        val own = all.filter { it.get(DESIGN_KEY).isNull }
        val res = page.getInheritable("Resources").let { r -> if (r.isNull) pdf.newDictionary().also { page.put("Resources", it) } else r }
        val xo = res.get("XObject").let { x -> if (x.isNull) pdf.newDictionary().also { res.put("XObject", it) } else x }
        // Tetra's Forms of before, by the names its pieces draw them by (resources may be shared: each page's names its own)
        all.filter { !it.get(DESIGN_KEY).isNull }.forEach { m -> Regex("""/(Tetra\w+) Do""").find(String(m.readStream()))?.let { xo.delete(it.groupValues[1]) } }
        val me = runCatching { page.asIndirect() }.getOrDefault(i)
        val out = pdf.newArray()
        fun mark(bytes: String) = pdf.addStream(com.artifex.mupdf.fitz.Buffer().apply { writeBytes(bytes.toByteArray()) }, pdf.newDictionary().apply { put(DESIGN_KEY, pdf.newName("Tetra")) })
        fun form(name: String, behind: Boolean) {
            val (res2, contents) = grafted(onePage(shown.width, shown.height) { canvas -> d.draw(canvas, shown.width, shown.height, behind) })
            val mb = page.getInheritable("MediaBox"); val x0 = mb.get(0).asFloat(); val y0 = mb.get(1).asFloat()
            val uw = mb.get(2).asFloat() - x0; val uh = mb.get(3).asFloat() - y0
            // the shown page's drawing to the page's own space: turned back as the page is turned to be shown
            val m = when (Math.floorMod(page.getInheritable("Rotate").asInteger(), 360)) {
                90 -> com.artifex.mupdf.fitz.Matrix(0f, 1f, -1f, 0f, x0 + uw, y0)
                180 -> com.artifex.mupdf.fitz.Matrix(-1f, 0f, 0f, -1f, x0 + uw, y0 + uh)
                270 -> com.artifex.mupdf.fitz.Matrix(0f, -1f, 1f, 0f, x0, y0 + uh)
                else -> com.artifex.mupdf.fitz.Matrix(1f, 0f, 0f, 1f, x0, y0)
            }
            val dict = pdf.newDictionary().apply {
                put("Type", pdf.newName("XObject")); put("Subtype", pdf.newName("Form")); put("BBox", com.artifex.mupdf.fitz.Rect(0f, 0f, shown.width, shown.height))
                put("Matrix", m); put("Resources", res2); put(DESIGN_KEY, pdf.newName("Tetra"))
            }
            xo.put(name, pdf.addStream(com.artifex.mupdf.fitz.Buffer().apply { writeBytes(contents) }, dict))
            out.push(mark("q /$name Do Q\n"))
        }
        if (d.behind) form("TetraUnder$me", true)
        if (d.over) out.push(mark("q\n"))
        own.forEach { out.push(it) }
        if (d.over) { out.push(mark("\nQ\n")); form("TetraOver$me", false) }
        page.put("Contents", out)
    }
}

/** A one-page PDF [w] × [h] points drawn by [draw] (Android's, its fonts embedded): what [PdfDoc.grafted] takes in. */
internal fun onePage(w: Float, h: Float, draw: (android.graphics.Canvas) -> Unit): ByteArray {
    val doc = android.graphics.pdf.PdfDocument()
    try {
        val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(kotlin.math.ceil(w).toInt().coerceAtLeast(1), kotlin.math.ceil(h).toInt().coerceAtLeast(1), 1).create())
        draw(page.canvas)
        doc.finishPage(page)
        return java.io.ByteArrayOutputStream().also { doc.writeTo(it) }.toByteArray()
    } finally { doc.close() }
}
