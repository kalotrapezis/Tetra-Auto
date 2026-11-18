package com.kalotrapezis.drive

import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile

/**
 * Home › Merge & centre and Unmerge (Excel's): [SheetRange]s of a sheet made one cell, or their cells again — Excel's
 * mergeCell, LibreOffice's spanned cell over covered ones. As Excel merges, only the top left cell keeps what it holds.
 * A change to the file itself (as inserting rows is): done on the file with the edits in it, one step of Undo.
 */
internal object SheetMerge {
    private fun name(g: SheetRange) = "${Sheets.colName(g.c1)}${g.r1 + 1}:${Sheets.colName(g.c2)}${g.r2 + 1}"
    private fun overlaps(a: SheetRange, b: SheetRange) = a.r1 <= b.r2 && b.r1 <= a.r2 && a.c1 <= b.c2 && b.c1 <= a.c2

    /** Excel: sheet part [sheetPart] with [range] merged ([merge]) or the merges over it taken away. */
    fun xlsx(source: File, target: File, sheetPart: String, range: SheetRange, merge: Boolean) {
        ZipFile(source).use { zip ->
            var x = zip.getEntry(sheetPart)?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } } ?: sheetProblem(R.string.tables_err_no_sheet)
            // the merges kept: those not over the range (a merge partly over it goes, as Excel's Merge takes it in)
            val kept = Regex("""<mergeCell\b[^>]*\bref="([^"]+)"[^>]*/>""").findAll(x).map { it.groupValues[1] }
                .filter { ref -> Sheets.rangeRef(ref)?.let { !overlaps(it, range) } ?: true }.toList()
            val all = if (merge) kept + name(range) else kept
            x = x.replace(Regex("""<mergeCells\b[^>]*?(?:/>|>.*?</mergeCells>)""", RegexOption.DOT_MATCHES_ALL), "")
            // merged: the other cells emptied (their looks kept), as Excel's Merge keeps only the top left's value
            if (merge) x = Regex("""<c\b([^>]*?)\br="([A-Z]+\d+)"([^>]*?)(?:/>|>.*?</c>)""", RegexOption.DOT_MATCHES_ALL).replace(x) { m ->
                val rc = Sheets.cellRef(m.groupValues[2]) ?: return@replace m.value
                if (rc.first !in range.r1..range.r2 || rc.second !in range.c1..range.c2 || (rc.first == range.r1 && rc.second == range.c1)) m.value
                else "<c${m.groupValues[1]}r=\"${m.groupValues[2]}\"${m.groupValues[3].replace(Regex("""\st="[^"]*""""), "")}/>"
            }
            if (all.isNotEmpty()) {
                val block = "<mergeCells count=\"${all.size}\">" + all.joinToString("") { "<mergeCell ref=\"$it\"/>" } + "</mergeCells>"
                // after the sheet's data and what the schema puts between (protection, filter, sort…), before the rest
                val after = Regex("""</sheetData>|<sheetData\s*/>|</?(sheetCalcPr|sheetProtection|protectedRanges|scenarios|autoFilter|sortState|dataConsolidate|customSheetViews)\b[^>]*>""")
                    .findAll(x).lastOrNull()?.let { m -> if (m.value.endsWith("/>") || m.value.startsWith("</")) m.range.last + 1
                        else Regex("</${m.groupValues[1]}>").find(x, m.range.last)?.range?.last?.plus(1) ?: (m.range.last + 1) } ?: sheetProblem(R.string.tables_err_no_sheet_data)
                x = x.substring(0, after) + block + x.substring(after)
            }
            SheetSave.rezip(zip, target, mapOf(sheetPart to x.toByteArray()), null)
        }
    }

    internal const val T = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
    internal fun kids(e: Element, vararg names: String) = (0 until e.childNodes.length).mapNotNull { e.childNodes.item(it) as? Element }.filter { it.namespaceURI == T && it.localName in names }
    internal fun rowsOf(e: Element): List<Element> = kids(e, "table-row", "table-header-rows", "table-row-group", "table-rows").flatMap { if (it.localName == "table-row") listOf(it) else rowsOf(it) }
    /** A run of repeated rows or cells cut so place [i] is an element of its own; that element (made, past the end). */
    internal fun at(list: () -> List<Element>, i: Int, attr: String, make: () -> Element): Element {
        var n0 = 0
        for (e in list()) {
            val n = e.getAttributeNS(T, attr).toIntOrNull() ?: 1
            if (i < n0 + n) {
                if (i > n0) { val first = e.cloneNode(true) as Element; if (i - n0 > 1) first.setAttributeNS(T, "table:$attr", "${i - n0}") else first.removeAttributeNS(T, attr)
                    e.parentNode.insertBefore(first, e); if (n - (i - n0) > 1) e.setAttributeNS(T, "table:$attr", "${n - (i - n0)}") else e.removeAttributeNS(T, attr) }
                if ((e.getAttributeNS(T, attr).toIntOrNull() ?: 1) > 1) { val rest = e.cloneNode(true) as Element
                    val m = e.getAttributeNS(T, attr).toInt(); if (m - 1 > 1) rest.setAttributeNS(T, "table:$attr", "${m - 1}") else rest.removeAttributeNS(T, attr)
                    e.removeAttributeNS(T, attr); e.parentNode.insertBefore(rest, e.nextSibling) }
                return e
            }
            n0 += n
        }
        var last: Element? = null
        while (n0 <= i) { last = make(); n0++ }
        return last!!
    }
    /** LibreOffice: [table]'s cell [r], [c] as an element of its own (repeated rows and cells cut; made when past the end). */
    fun odsCell(doc: org.w3c.dom.Document, table: Element, r: Int, c: Int): Element {
        val row = at({ rowsOf(table) }, r, "number-rows-repeated") { doc.createElementNS(T, "table:table-row").also { table.appendChild(it) } }
        return at({ kids(row, "table-cell", "covered-table-cell") }, c, "number-columns-repeated") { doc.createElementNS(T, "table:table-cell").also { row.appendChild(it) } }
    }

    /** LibreOffice: sheet [index] with [range] as one spanned cell over covered ones ([merge]), or its cells again. */
    fun ods(source: File, target: File, index: Int, range: SheetRange, merge: Boolean) {
        ZipFile(source).use { zip ->
            val doc = OfficeFormats.parse(zip.getInputStream(zip.getEntry("content.xml") ?: sheetProblem(R.string.tables_err_not_spreadsheet)).use { it.readBytes() })
            val O = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
            val table = doc.getElementsByTagNameNS(T, "table").let { l -> (0 until l.length).map { l.item(it) as Element } }
                .filter { t -> generateSequence(t.parentNode) { it.parentNode }.none { it is Element && it.localName == "table" } }.getOrNull(index) ?: sheetProblem(R.string.tables_err_no_sheet_n, index + 1)
            for (r in range.r1..range.r2) {
                for (c in range.c1..range.c2) {
                    val cell = odsCell(doc, table, r, c)
                    val top = r == range.r1 && c == range.c1
                    val now = if (merge && !top && cell.localName == "table-cell") doc.renameNode(cell, T, "table:covered-table-cell") as Element
                        else if (!merge && cell.localName == "covered-table-cell") doc.renameNode(cell, T, "table:table-cell") as Element else cell
                    now.removeAttributeNS(T, "number-columns-spanned"); now.removeAttributeNS(T, "number-rows-spanned")
                    if (merge && top) { now.setAttributeNS(T, "table:number-columns-spanned", "${range.c2 - range.c1 + 1}"); now.setAttributeNS(T, "table:number-rows-spanned", "${range.r2 - range.r1 + 1}") }
                    // merged: only the top left keeps what it holds
                    if (merge && !top) { while (now.firstChild != null) now.removeChild(now.firstChild)
                        listOf("value", "value-type", "date-value", "time-value", "boolean-value", "string-value", "currency").forEach { now.removeAttributeNS(O, it) }
                        now.removeAttributeNS("urn:org:documentfoundation:names:experimental:calc:xmlns:calcext:1.0", "value-type"); now.removeAttributeNS(T, "formula") }
                }
            }
            SheetSave.rezip(zip, target, mapOf("content.xml" to SlidesEdit.domXml(doc)), "mimetype")
        }
    }
}
