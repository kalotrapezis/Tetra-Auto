package com.kalotrapezis.drive

import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile

/**
 * A column's width, a row's height, and hiding them (Excel's Format ▸ Column width, Row height, Hide & Unhide): a change
 * to the file, as inserting rows is. Columns ([cols]) or rows [from]..[to] (the sheet's, from 0) made [px] sheet pixels
 * (null: as they are) and hidden or shown again ([hidden]; null: as they are).
 */
internal object SheetSizes {
    private fun attrs(tag: String) = Regex("""(\w+)="([^"]*)"""").findAll(tag).associate { it.groupValues[1] to it.groupValues[2] }.toMutableMap()

    fun xlsx(source: File, target: File, sheetPart: String, cols: Boolean, from: Int, to: Int, px: Float?, hidden: Boolean?) {
        ZipFile(source).use { zip ->
            var x = zip.getEntry(sheetPart)?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } } ?: sheetProblem(R.string.tables_err_no_sheet)
            if (cols) {
                // Excel's columns: <col min max …/> runs (from 1); the ones over [from..to] cut out and changed, the gaps there filled
                val a = from + 1; val b = to + 1
                val old = Regex("""<col\b[^>]*/>""").findAll(Regex("""<cols>([\s\S]*?)</cols>""").find(x)?.groupValues?.get(1).orEmpty()).map { attrs(it.value) }.toList()
                fun changed(m: MutableMap<String, String>) = m.also { c ->
                    px?.let { c["width"] = "%.4f".format(java.util.Locale.ROOT, it / 7.0); c["customWidth"] = "1" }
                    hidden?.let { if (it) c["hidden"] = "1" else c.remove("hidden") }
                    if (c["width"] == null) c["width"] = "8.43" }
                val out = ArrayList<MutableMap<String, String>>()
                for (c in old) {
                    val lo = c["min"]?.toIntOrNull() ?: continue; val hi = c["max"]?.toIntOrNull() ?: lo
                    if (hi < a || lo > b) { out += c; continue }
                    if (lo < a) out += c.toMutableMap().also { it["max"] = "${a - 1}" }
                    if (hi > b) out += c.toMutableMap().also { it["min"] = "${b + 1}" }
                    out += changed(c.toMutableMap().also { it["min"] = "${maxOf(lo, a)}"; it["max"] = "${minOf(hi, b)}" })
                }
                var k = a
                while (k <= b) {
                    if (out.any { (it["min"]?.toIntOrNull() ?: 0) <= k && k <= (it["max"]?.toIntOrNull() ?: 0) }) { k++; continue }
                    val end = generateSequence(k) { it + 1 }.takeWhile { j -> j <= b && out.none { (it["min"]?.toIntOrNull() ?: 0) <= j && j <= (it["max"]?.toIntOrNull() ?: 0) } }.last()
                    out += changed(mutableMapOf("min" to "$k", "max" to "$end")); k = end + 1
                }
                val block = "<cols>" + out.sortedBy { it["min"]?.toIntOrNull() ?: 0 }.joinToString("") { m -> "<col " + m.entries.joinToString(" ") { (n, v) -> "$n=\"$v\"" } + "/>" } + "</cols>"
                val had = Regex("""<cols>[\s\S]*?</cols>|<cols\s*/>""").find(x)
                x = if (had != null) x.replaceRange(had.range, block) else x.replaceFirst(Regex("""<sheetData\b"""), "$block<sheetData")
            } else {
                // Excel's rows: each row's ht and customHeight, hidden; a row the sheet has not got made, in its place
                for (r in from..minOf(to, from + 4999)) {
                    val n = r + 1
                    val m = Regex("""<row\b[^>]*\br="$n"[^>]*?(/?)>""").find(x)
                    val a0 = m?.let { attrs(it.value.removePrefix("<row").removeSuffix("/>").removeSuffix(">")) } ?: mutableMapOf("r" to "$n")
                    px?.let { a0["ht"] = "%.2f".format(java.util.Locale.ROOT, it * 3 / 4); a0["customHeight"] = "1" }
                    hidden?.let { if (it) a0["hidden"] = "1" else a0.remove("hidden") }
                    val tag = "<row " + a0.entries.joinToString(" ") { (k, v) -> "$k=\"$v\"" } + (if (m?.groupValues?.get(1) == "/" || m == null) "/>" else ">")
                    x = if (m != null) x.replaceRange(m.range, tag) else {
                        // before the first row after it, else at the end of the sheet's data
                        val next = Regex("""<row\b[^>]*\br="(\d+)"""").findAll(x).firstOrNull { (it.groupValues[1].toIntOrNull() ?: 0) > n }?.range?.first
                        val at = next ?: x.indexOf("</sheetData>").takeIf { it >= 0 } ?: run { x = x.replace(Regex("""<sheetData\s*/>"""), "<sheetData></sheetData>"); x.indexOf("</sheetData>") }
                        x.substring(0, at) + tag + x.substring(at)
                    }
                }
            }
            SheetSave.rezip(zip, target, mapOf(sheetPart to x.toByteArray()), null)
        }
    }

    fun ods(source: File, target: File, index: Int, cols: Boolean, from: Int, to: Int, px: Float?, hidden: Boolean?) {
        ZipFile(source).use { zip ->
            val doc = OfficeFormats.parse(zip.getInputStream(zip.getEntry("content.xml") ?: sheetProblem(R.string.tables_err_not_spreadsheet)).use { it.readBytes() })
            val T = SheetMerge.T
            val S = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
            val table = doc.getElementsByTagNameNS(T, "table").let { l -> (0 until l.length).map { l.item(it) as Element } }
                .filter { t -> generateSequence(t.parentNode) { it.parentNode }.none { it is Element && it.localName == "table" } }.getOrNull(index) ?: sheetProblem(R.string.tables_err_no_sheet_n, index + 1)
            // the size as a style of its own (LibreOffice keeps a column's width and a row's height in one)
            val style = px?.let { p ->
                val autos = doc.getElementsByTagNameNS("urn:oasis:names:tc:opendocument:xmlns:office:1.0", "automatic-styles").item(0) as? Element ?: sheetProblem(R.string.tables_err_no_styles)
                val taken = (0 until autos.childNodes.length).mapNotNull { (autos.childNodes.item(it) as? Element)?.getAttributeNS(S, "name") }.toSet()
                val name = generateSequence(1) { it + 1 }.map { (if (cols) "coT" else "roT") + it }.first { it !in taken }
                val st = doc.createElementNS(S, "style:style"); st.setAttributeNS(S, "style:name", name); st.setAttributeNS(S, "style:family", if (cols) "table-column" else "table-row")
                val pr = doc.createElementNS(S, if (cols) "style:table-column-properties" else "style:table-row-properties")
                val len = String.format(java.util.Locale.ROOT, "%.3fcm", p * 2.54f / 96f)
                if (cols) pr.setAttributeNS(S, "style:column-width", len) else { pr.setAttributeNS(S, "style:row-height", len); pr.setAttributeNS(S, "style:use-optimal-row-height", "false") }
                st.appendChild(pr); autos.appendChild(st); name }
            fun columns() = SheetMerge.kids(table, "table-column", "table-columns", "table-header-columns", "table-column-group").flatMap { if (it.localName == "table-column") listOf(it) else SheetMerge.kids(it, "table-column") }
            for (i in from..minOf(to, from + if (cols) 1023 else 4999)) {
                val e = if (cols) SheetMerge.at({ columns() }, i, "number-columns-repeated") {
                    doc.createElementNS(T, "table:table-column").also { c -> table.insertBefore(c, SheetMerge.kids(table, "table-row", "table-rows", "table-header-rows", "table-row-group").firstOrNull()) } }
                    else SheetMerge.at({ SheetMerge.rowsOf(table) }, i, "number-rows-repeated") { doc.createElementNS(T, "table:table-row").also { table.appendChild(it) } }
                style?.let { e.setAttributeNS(T, "table:style-name", it) }
                hidden?.let { if (it) e.setAttributeNS(T, "table:visibility", "collapse") else e.removeAttributeNS(T, "visibility") }
            }
            SheetSave.rezip(zip, target, mapOf("content.xml" to SlidesEdit.domXml(doc)), "mimetype")
        }
    }
}
