package com.kalotrapezis.drive

import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile

/**
 * A picture, chart, shape or text box on a sheet changed (tapped while editing, its tab): taken away, moved to another
 * cell (dragged there), or sized (Size, in Words' unit). The drawing is found by its place among the file's
 * ([SheetDrawing.ordinal]): Excel's anchors in the sheet's drawing part, LibreOffice's frames and shapes in the sheet.
 * A change to the file.
 */
internal object SheetDrawings {
    sealed interface Change
    object Delete : Change
    data class Move(val row: Int, val col: Int) : Change
    data class Size(val widthPt: Float, val heightPt: Float) : Change

    private val ANCHOR = Regex("""<((?:\w+:)?)(twoCellAnchor|oneCellAnchor|absoluteAnchor)\b[\s\S]*?</\1\2>""")
    private fun mark(x: String, p: String, tag: String, name: String) = Regex("""<$p$tag>[\s\S]*?<$p$name>(\d+)</$p$name>""").find(x)?.groupValues?.get(1)?.toInt()

    fun xlsx(source: File, target: File, sheetPart: String, ordinal: Int, change: Change) {
        ZipFile(source).use { zip ->
            fun read(p: String) = zip.getEntry(p)?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }
            val sheet = read(sheetPart) ?: sheetProblem(R.string.tables_err_no_sheet)
            val rels = read(sheetPart.substringBeforeLast('/') + "/_rels/" + sheetPart.substringAfterLast('/') + ".rels").orEmpty()
            val id = Regex("""<drawing\b[^>]*\br:id="([^"]+)"""").find(sheet)?.groupValues?.get(1) ?: sheetProblem(R.string.tables_err_no_drawings)
            val drawingPath = (Regex("""<Relationship\b[^>]*\bId="$id"[^>]*\bTarget="([^"]+)"""").find(rels) ?: Regex("""<Relationship\b[^>]*\bTarget="([^"]+)"[^>]*\bId="$id"""").find(rels))
                ?.groupValues?.get(1)?.let { t -> if (t.startsWith("/")) t.removePrefix("/") else "xl/" + t.removePrefix("../") } ?: sheetProblem(R.string.tables_err_no_drawing_part)
            var x = read(drawingPath) ?: sheetProblem(R.string.tables_err_no_drawing_part)
            val m = ANCHOR.findAll(x).elementAtOrNull(ordinal) ?: sheetProblem(R.string.tables_err_drawing_gone)
            val p = m.groupValues[1]; var a = m.value
            when (change) {
                Delete -> a = ""
                is Move -> {
                    val r0 = mark(a, p, "from", "row") ?: 0; val c0 = mark(a, p, "from", "col") ?: 0
                    fun moved(tag: String, dr: Int, dc: Int, zero: Boolean) { a = Regex("""(<$p$tag>)([\s\S]*?)(</$p$tag>)""").replace(a) { t ->
                        var body = t.groupValues[2]
                        body = Regex("""(<$p(row|col)>)(\d+)(</$p\2>)""").replace(body) { v -> v.groupValues[1] + (v.groupValues[3].toInt() + if (v.groupValues[2] == "row") dr else dc) + v.groupValues[4] }
                        if (zero) body = Regex("""(<$p(rowOff|colOff)>)-?\d+(</$p\2>)""").replace(body) { v -> v.groupValues[1] + "0" + v.groupValues[3] }
                        t.groupValues[1] + body + t.groupValues[3] } }
                    moved("from", change.row - r0, change.col - c0, true); moved("to", change.row - r0, change.col - c0, false)
                }
                is Size -> {
                    val cx = (change.widthPt * 12700).toLong(); val cy = (change.heightPt * 12700).toLong()
                    // a two-cell anchor becomes a one-cell one of that size (its far corner no longer a cell)
                    if (m.groupValues[2] == "twoCellAnchor") a = a.replaceFirst(Regex("""<${p}twoCellAnchor\b[^>]*>"""), "<${p}oneCellAnchor>").replace(Regex("""</${p}twoCellAnchor>$"""), "</${p}oneCellAnchor>")
                        .replace(Regex("""<${p}to>[\s\S]*?</${p}to>"""), "").replaceFirst("</${p}from>", """</${p}from><${p}ext cx="$cx" cy="$cy"/>""")
                    else a = a.replaceFirst(Regex("""<${p}ext\b[^>]*/>"""), """<${p}ext cx="$cx" cy="$cy"/>""")
                    Regex("""(<(?:\w+:)?xfrm\b[^>]*>[\s\S]*?<(?:\w+:)?ext\b)[^>]*?/>""").find(a)?.let { t -> a = a.replaceRange(t.range, t.groupValues[1] + """ cx="$cx" cy="$cy"/>""") }
                }
            }
            x = x.replaceRange(m.range, a)
            SheetSave.rezip(zip, target, mapOf(drawingPath to x.toByteArray()), null)
        }
    }

    fun ods(source: File, target: File, index: Int, ordinal: Int, change: Change) {
        ZipFile(source).use { zip ->
            val doc = OfficeFormats.parse(zip.getInputStream(zip.getEntry("content.xml") ?: sheetProblem(R.string.tables_err_not_spreadsheet)).use { it.readBytes() })
            val T = SheetMerge.T; val SVG = "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"; val DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
            val table = doc.getElementsByTagNameNS(T, "table").let { l -> (0 until l.length).map { l.item(it) as Element } }
                .filter { t -> generateSequence(t.parentNode) { it.parentNode }.none { it is Element && it.localName == "table" } }.getOrNull(index) ?: sheetProblem(R.string.tables_err_no_sheet_n, index + 1)
            // the sheet's frames and shapes in order, not those inside another (as the reader counts them)
            fun top(e: Element) = generateSequence(e.parentNode) { it.parentNode }.takeWhile { it !== table }.none { it is Element && it.namespaceURI == DRAW && it.localName in setOf("frame", "custom-shape") }
            val all = ArrayList<Element>()
            fun walk(e: Element) { for (i in 0 until e.childNodes.length) (e.childNodes.item(i) as? Element)?.let { c ->
                if (c.namespaceURI == DRAW && c.localName in setOf("frame", "custom-shape") && top(c)) all += c
                else if (c.localName != "table" && !(c.namespaceURI == DRAW && c.localName == "g")) walk(c) } } // a group's shapes the reader leaves out too
            walk(table)
            val d = all.getOrNull(ordinal) ?: sheetProblem(R.string.tables_err_drawing_gone)
            fun cm(pt: Float) = String.format(java.util.Locale.ROOT, "%.3fcm", pt / 28.346457f)
            // its far corner a cell no more: its size says how big
            fun loose() = listOf("end-cell-address", "end-x", "end-y").forEach { d.removeAttributeNS(T, it) }
            when (change) {
                Delete -> d.parentNode.removeChild(d)
                is Move -> { d.parentNode.removeChild(d); loose(); d.setAttributeNS(SVG, "svg:x", "0cm"); d.setAttributeNS(SVG, "svg:y", "0cm")
                    SheetMerge.odsCell(doc, table, change.row, change.col).let { it.insertBefore(d, it.firstChild) } }
                is Size -> { loose(); d.setAttributeNS(SVG, "svg:width", cm(change.widthPt)); d.setAttributeNS(SVG, "svg:height", cm(change.heightPt)) }
            }
            SheetSave.rezip(zip, target, mapOf("content.xml" to SlidesEdit.domXml(doc)), "mimetype")
        }
    }
}
