package com.kalotrapezis.drive

import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile

/**
 * Data › Filter kept in the file (Excel's AutoFilter, LibreOffice's database range with its filter): the table [range]
 * (its first row the headings), each filtered column's values kept ([keep], by the sheet's column; "" an empty cell),
 * and the rows filtered out hidden as Excel hides them ([hidden], the sheet's rows). [keep] empty: the filter's buttons
 * alone, every row shown; [range] null: the filter taken away (every row of [old] shown). A change to the file.
 */
internal object SheetAutoFilter {
    private fun ref(g: SheetRange) = "${Sheets.colName(g.c1)}${g.r1 + 1}:${Sheets.colName(g.c2)}${g.r2 + 1}"

    fun xlsx(source: File, target: File, sheetPart: String, index: Int, name: String, range: SheetRange?, keep: Map<Int, Set<String>>, hidden: Set<Int>, old: SheetRange? = null) {
        ZipFile(source).use { zip ->
            fun read(p: String) = zip.getEntry(p)?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }
            var x = read(sheetPart) ?: sheetProblem(R.string.tables_err_no_sheet)
            x = x.replace(Regex("""<autoFilter\b[^>]*?(?:/>|>[\s\S]*?</autoFilter>)"""), "")
            // the sheet says whether a filter hides rows (LibreOffice reads the filter by it)
            x = x.replace(Regex("""(<sheetPr\b[^>]*?)\sfilterMode="[^"]*""""), "$1")
            if (range != null && keep.isNotEmpty()) x = when {
                Regex("""<sheetPr\b""").containsMatchIn(x) -> x.replaceFirst(Regex("""<sheetPr\b"""), """<sheetPr filterMode="1"""")
                else -> x.replaceFirst(Regex("""(<worksheet\b[^>]*>)"""), """$1<sheetPr filterMode="1"/>""")
            }
            val rowsShown = range ?: old
            val columns = if (range == null) "" else keep.toSortedMap().entries.joinToString("") { (c, values) ->
                val blank = "" in values
                """<filterColumn colId="${c - range.c1}"><filters${if (blank) " blank=\"1\"" else ""}>""" + values.filter { it.isNotEmpty() }.joinToString("") { """<filter val="${OfficeFormats.esc(it)}"/>""" } + "</filters></filterColumn>"
            }
            val filter = if (range == null) "" else """<autoFilter ref="${ref(range)}"${if (columns.isEmpty()) "/>" else ">$columns</autoFilter>"}"""
            // in the schema's place: after the sheet's data and protection, before the sort, merges, rules and the rest
            val at = Regex("""<(sortState|dataConsolidate|customSheetViews|mergeCells|phoneticPr|conditionalFormatting|dataValidations|hyperlinks|printOptions|pageMargins|pageSetup|headerFooter|rowBreaks|colBreaks|customProperties|cellWatches|ignoredErrors|smartTags|drawing|legacyDrawing|legacyDrawingHF|drawingHF|picture|oleObjects|controls|webPublishItems|tableParts|extLst)\b""").find(x)?.range?.first ?: x.lastIndexOf("</worksheet>")
            x = x.substring(0, at) + filter + x.substring(at)
            // the rows filtered out hidden, the others in the table shown (a row the sheet has not written made, hidden)
            val data = Regex("""<sheetData\b[^>]*?(?:/>|>([\s\S]*?)</sheetData>)""").find(x) ?: sheetProblem(R.string.tables_err_no_sheet_data)
            val rows = Regex("""<row\b[^>]*?(?:/>|>[\s\S]*?</row>)""").findAll(data.groupValues[1]).map { m -> (Regex("""\sr="(\d+)"""").find(m.value)!!.groupValues[1].toInt() - 1) to m.value }.toMap(java.util.TreeMap())
            if (rowsShown != null) for (r in rowsShown.r1 + 1..rowsShown.r2) {
                val row = rows[r] ?: if (r in hidden) """<row r="${r + 1}"/>""" else continue
                val head = row.substring(0, row.indexOf('>') + 1).let { if (it.endsWith("/>")) it.removeSuffix("/>") else it.removeSuffix(">") }.replace(Regex("""\shidden="[^"]*""""), "")
                val rest = row.substring(row.indexOf('>') + 1)
                val selfClosing = row.substring(0, row.indexOf('>') + 1).endsWith("/>")
                rows[r] = head + (if (r in hidden) """ hidden="1"""" else "") + (if (selfClosing) "/>" else ">") + rest
            }
            x = x.replaceRange(data.range, "<sheetData>" + rows.values.joinToString("") + "</sheetData>")
            // Excel's hidden name for the filtered table
            var wb = read("xl/workbook.xml") ?: sheetProblem(R.string.tables_err_not_workbook)
            wb = wb.replace(Regex("""<definedName\b[^>]*name="_xlnm\._FilterDatabase"[^>]*localSheetId="$index"[^>]*>[^<]*</definedName>"""), "")
                .replace(Regex("""<definedName\b[^>]*localSheetId="$index"[^>]*name="_xlnm\._FilterDatabase"[^>]*>[^<]*</definedName>"""), "")
            if (range != null) { val d = """<definedName name="_xlnm._FilterDatabase" localSheetId="$index" hidden="1">'${OfficeFormats.esc(name.replace("'", "''"))}'!${'$'}${Sheets.colName(range.c1)}${'$'}${range.r1 + 1}:${'$'}${Sheets.colName(range.c2)}${'$'}${range.r2 + 1}</definedName>"""
                wb = if ("</definedNames>" in wb) wb.replace("</definedNames>", "$d</definedNames>") else wb.replace("</sheets>", "</sheets><definedNames>$d</definedNames>") }
            wb = wb.replace(Regex("""<definedNames>\s*</definedNames>"""), "")
            SheetSave.rezip(zip, target, mapOf(sheetPart to x.toByteArray(), "xl/workbook.xml" to wb.toByteArray()), null)
        }
    }

    /** LibreOffice: the sheet's own database range (__Anonymous_Sheet_DB__) with its filter, its rows filtered out (visibility "filter"). */
    fun ods(source: File, target: File, index: Int, name: String, range: SheetRange?, keep: Map<Int, Set<String>>, hidden: Set<Int>, old: SheetRange? = null) {
        ZipFile(source).use { zip ->
            val doc = OfficeFormats.parse(zip.getInputStream(zip.getEntry("content.xml") ?: sheetProblem(R.string.tables_err_not_spreadsheet)).use { it.readBytes() })
            val T = SheetMerge.T
            val tables = doc.getElementsByTagNameNS(T, "table").let { l -> (0 until l.length).map { l.item(it) as Element } }
                .filter { t -> generateSequence(t.parentNode) { it.parentNode }.none { it is Element && it.localName == "table" } }
            val table = tables.getOrNull(index) ?: sheetProblem(R.string.tables_err_no_sheet_n, index + 1)
            val sheet = table.parentNode as Element
            val q = if (Regex("""^\w+$""").matches(name)) name else "'" + name.replace("'", "''") + "'"
            // the sheet's anonymous range goes, made again
            val all = SheetMerge.kids(sheet, "database-ranges").firstOrNull()
            all?.let { a -> SheetMerge.kids(a, "database-range").filter { it.getAttributeNS(T, "name") == "__Anonymous_Sheet_DB__$index" }.forEach { a.removeChild(it) } }
            if (range != null) {
                val ranges = all ?: doc.createElementNS(T, "table:database-ranges").also { d ->
                    // after the tables and their names, as the schema has it
                    val after = SheetMerge.kids(sheet, "named-expressions").firstOrNull() ?: tables.last()
                    sheet.insertBefore(d, after.nextSibling) }
                val db = doc.createElementNS(T, "table:database-range")
                db.setAttributeNS(T, "table:name", "__Anonymous_Sheet_DB__$index")
                db.setAttributeNS(T, "table:target-range-address", "$q.${Sheets.colName(range.c1)}${range.r1 + 1}:$q.${Sheets.colName(range.c2)}${range.r2 + 1}")
                db.setAttributeNS(T, "table:display-filter-buttons", "true")
                if (keep.isNotEmpty()) {
                    val conditions = keep.toSortedMap().map { (c, values) -> doc.createElementNS(T, "table:filter-condition").also { e ->
                        e.setAttributeNS(T, "table:field-number", "${c - range.c1}"); e.setAttributeNS(T, "table:value", values.first()); e.setAttributeNS(T, "table:operator", "=")
                        if (values.size > 1) values.forEach { v -> e.appendChild(doc.createElementNS(T, "table:filter-set-item").also { it.setAttributeNS(T, "table:value", v) }) } } }
                    db.appendChild(doc.createElementNS(T, "table:filter").also { f ->
                        if (conditions.size == 1) f.appendChild(conditions[0]) else f.appendChild(doc.createElementNS(T, "table:filter-and").also { a -> conditions.forEach { a.appendChild(it) } }) })
                }
                ranges.appendChild(db)
            }
            // the table's rows: those filtered out hidden as LibreOffice hides them, the others shown
            val rowsShown = range ?: old
            if (rowsShown != null) for (r in rowsShown.r1 + 1..rowsShown.r2) {
                val row = SheetMerge.at({ SheetMerge.rowsOf(table) }, r, "number-rows-repeated") { doc.createElementNS(T, "table:table-row").also { table.appendChild(it) } }
                if (r in hidden) row.setAttributeNS(T, "table:visibility", "filter") else if (row.getAttributeNS(T, "visibility") == "filter") row.removeAttributeNS(T, "visibility")
            }
            SheetSave.rezip(zip, target, mapOf("content.xml" to SlidesEdit.domXml(doc)), "mimetype")
        }
    }
}
