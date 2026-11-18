package com.kalotrapezis.drive

import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile

/**
 * Data › Drop-down list on cells (Excel's Data Validation › List, LibreOffice's Validity › Values): [items] the values a cell
 * may take, as a stop — anything else refused; null takes the cells' list away. Excel's dataValidation, LibreOffice's
 * table:content-validation the cells name. A change to the file, as merging is.
 */
internal object SheetLists {
    private fun name(g: SheetRange) = "${Sheets.colName(g.c1)}${g.r1 + 1}:${Sheets.colName(g.c2)}${g.r2 + 1}"
    private fun overlaps(a: SheetRange, b: SheetRange) = a.r1 <= b.r2 && b.r1 <= a.r2 && a.c1 <= b.c2 && b.c1 <= a.c2

    fun xlsx(source: File, target: File, sheetPart: String, range: SheetRange, items: List<String>?) {
        ZipFile(source).use { zip ->
            var x = zip.getEntry(sheetPart)?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } } ?: sheetProblem(R.string.tables_err_no_sheet)
            // shortcut: a validation partly over the range goes whole (not cut round it); upgrade if users keep half of one
            val kept = Regex("""<dataValidation\b(?:[^>]*/>|[\s\S]*?</dataValidation>)""").findAll(x).map { it.value }
                .filter { v -> Regex("""\bsqref="([^"]*)"""").find(v)?.groupValues?.get(1).orEmpty().split(' ').mapNotNull(Sheets::rangeRef).none { overlaps(it, range) } }.toList()
            val all = kept + listOfNotNull(items?.let { l ->
                """<dataValidation type="list" allowBlank="1" showInputMessage="1" showErrorMessage="1" sqref="${name(range)}"><formula1>${OfficeFormats.esc("\"" + l.joinToString(",") { it.replace("\"", "\"\"") } + "\"")}</formula1></dataValidation>""" })
            x = x.replace(Regex("""<dataValidations\b[^>]*?(?:/>|>[\s\S]*?</dataValidations>)"""), "")
            if (all.isNotEmpty()) {
                // in the schema's place: after the rules, before the links and the page's settings
                val at = Regex("""<(hyperlinks|printOptions|pageMargins|pageSetup|headerFooter|rowBreaks|colBreaks|customProperties|cellWatches|ignoredErrors|smartTags|drawing|legacyDrawing|legacyDrawingHF|drawingHF|picture|oleObjects|controls|webPublishItems|tableParts|extLst)\b""").find(x)?.range?.first ?: x.lastIndexOf("</worksheet>")
                x = x.substring(0, at) + """<dataValidations count="${all.size}">${all.joinToString("")}</dataValidations>""" + x.substring(at)
            }
            SheetSave.rezip(zip, target, mapOf(sheetPart to x.toByteArray()), null)
        }
    }

    fun ods(source: File, target: File, index: Int, range: SheetRange, items: List<String>?) {
        ZipFile(source).use { zip ->
            val doc = OfficeFormats.parse(zip.getInputStream(zip.getEntry("content.xml") ?: sheetProblem(R.string.tables_err_not_spreadsheet)).use { it.readBytes() })
            val T = SheetMerge.T
            val tables = doc.getElementsByTagNameNS(T, "table").let { l -> (0 until l.length).map { l.item(it) as Element } }
                .filter { t -> generateSequence(t.parentNode) { it.parentNode }.none { it is Element && it.localName == "table" } }
            val table = tables.getOrNull(index) ?: sheetProblem(R.string.tables_err_no_sheet_n, index + 1)
            var name: String? = null
            if (items != null) {
                // the validities sit before the first table
                val all = doc.getElementsByTagNameNS(T, "content-validations").item(0) as Element?
                    ?: doc.createElementNS(T, "table:content-validations").also { tables[0].parentNode.insertBefore(it, tables[0]) }
                val used = doc.getElementsByTagNameNS(T, "content-validation").let { l -> (0 until l.length).map { (l.item(it) as Element).getAttributeNS(T, "name") } }.toSet()
                name = generateSequence(1) { it + 1 }.map { "Tetra_list_$it" }.first { it !in used }
                all.appendChild(doc.createElementNS(T, "table:content-validation").also { v ->
                    v.setAttributeNS(T, "table:name", name)
                    v.setAttributeNS(T, "table:condition", "of:cell-content-is-in-list(" + items.joinToString(";") { "\"" + it.replace("\"", "\"\"") + "\"" } + ")")
                    v.setAttributeNS(T, "table:allow-empty-cell", "true"); v.setAttributeNS(T, "table:display-list", "unsorted")
                    v.appendChild(doc.createElementNS(T, "table:error-message").also { it.setAttributeNS(T, "table:display", "true"); it.setAttributeNS(T, "table:message-type", "stop") })
                })
            }
            for (r in range.r1..minOf(range.r2, range.r1 + 999)) for (c in range.c1..minOf(range.c2, range.c1 + 99)) {
                val cell = SheetMerge.odsCell(doc, table, r, c)
                if (name != null) cell.setAttributeNS(T, "table:content-validation-name", name) else cell.removeAttributeNS(T, "content-validation-name")
            }
            SheetSave.rezip(zip, target, mapOf("content.xml" to SlidesEdit.domXml(doc)), "mimetype")
        }
    }
}
