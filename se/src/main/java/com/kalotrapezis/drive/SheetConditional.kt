package com.kalotrapezis.drive

import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile

/**
 * Home › Conditional formatting made here (Excel's menu): a highlight rule (greater than, less than, between, equal
 * to, text that contains, duplicates), top 10, above average, a data bar or a colour scale on the chosen cells — or
 * their rules cleared. Excel's conditionalFormatting with a dxf for the look; LibreOffice's calcext:conditional-format
 * with a cell style of its own. Put first, as Excel puts a new rule above the others. A change to the file; the
 * readers lay the rules on the cells.
 */
internal object SheetConditional {
    /** Excel's preset looks for a highlight rule: its name, fill, words' colour. */
    val LOOKS = listOf(Triple(R.string.tables_look_red, 0xFFFFC7CE.toInt(), 0xFF9C0006.toInt()),
        Triple(R.string.tables_look_yellow, 0xFFFFEB9C.toInt(), 0xFF9C5700.toInt()),
        Triple(R.string.tables_look_green, 0xFFC6EFCE.toInt(), 0xFF006100.toInt()))

    /**
     * A rule to make: [kind] "greaterThan", "lessThan", "between", "equal", "contains", "duplicate", "top10",
     * "aboveAverage", "dataBar" or "colorScale"; [values] what it compares with (two for between; the words for
     * contains; the count for top 10); [look] one of [LOOKS].
     */
    class Rule(val kind: String, val values: List<String> = emptyList(), val look: Int = 0)

    private fun ref(g: SheetRange) = "${Sheets.colName(g.c1)}${g.r1 + 1}" + if (g.r2 > g.r1 || g.c2 > g.c1) ":${Sheets.colName(g.c2)}${g.r2 + 1}" else ""
    private fun overlaps(a: SheetRange, b: SheetRange) = a.r1 <= b.r2 && b.r1 <= a.r2 && a.c1 <= b.c2 && b.c1 <= a.c2
    private fun hex(c: Int) = "%06X".format(c and 0xFFFFFF)
    /** A value as a formula's operand: a number (a decimal comma made a point), a formula as it is, words in quotes. */
    fun operand(v: String) = v.trim().let { t -> when {
        t.replace(',', '.').toDoubleOrNull() != null -> t.replace(',', '.')
        t.startsWith("=") -> t.removePrefix("=")
        else -> "\"" + t.replace("\"", "\"\"") + "\"" } }

    /** Excel: [rule] put on [range] of sheet part [sheetPart] (styles part [stylesPart] for its look); null: the rules over [range] taken away. */
    fun xlsx(source: File, target: File, sheetPart: String, stylesPart: String, range: SheetRange, rule: Rule?) {
        ZipFile(source).use { zip ->
            fun read(p: String) = zip.getEntry(p)?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }
            var sheet = read(sheetPart) ?: sheetProblem(R.string.tables_err_no_sheet)
            val out = LinkedHashMap<String, ByteArray?>()
            // shortcut: a rule partly over the range goes whole (Excel cuts it round the cells); upgrade if users clear parts of rules
            sheet = sheet.replace(Regex("""<conditionalFormatting\b[^>]*\bsqref="([^"]*)"[^>]*>[\s\S]*?</conditionalFormatting>""")) { m ->
                if (rule == null && m.groupValues[1].split(' ').mapNotNull(Sheets::rangeRef).any { overlaps(it, range) }) "" else m.value }
            if (rule != null) {
                var dxf: Int? = null
                if (rule.kind !in setOf("dataBar", "colorScale")) {
                    var styles = read(stylesPart) ?: sheetProblem(R.string.tables_err_no_styles)
                    val (_, fill, color) = LOOKS[rule.look]
                    val d = """<dxf><font><color rgb="FF${hex(color)}"/></font><fill><patternFill><bgColor rgb="FF${hex(fill)}"/></patternFill></fill></dxf>"""
                    val now = Regex("""<dxfs\b[^>]*?(?:/>|>([\s\S]*?)</dxfs>)""").find(styles)
                    dxf = now?.groupValues?.get(1)?.let { Regex("""<dxf\b""").findAll(it).count() } ?: 0
                    styles = if (now != null) styles.replaceRange(now.range, """<dxfs count="${dxf + 1}">${now.groupValues[1]}$d</dxfs>""")
                        else { val at = Regex("""<(tableStyles|colors|extLst)\b""").find(styles)?.range?.first ?: styles.lastIndexOf("</styleSheet>")
                            styles.substring(0, at) + """<dxfs count="1">$d</dxfs>""" + styles.substring(at) }
                    out[stylesPart] = styles.toByteArray()
                }
                val first = "${Sheets.colName(range.c1)}${range.r1 + 1}"
                val v = rule.values
                val body = when (rule.kind) {
                    "greaterThan", "lessThan", "equal" -> """type="cellIs" dxfId="$dxf" priority="1" operator="${rule.kind}"><formula>${OfficeFormats.esc(operand(v[0]))}</formula>"""
                    "between" -> """type="cellIs" dxfId="$dxf" priority="1" operator="between"><formula>${OfficeFormats.esc(operand(v[0]))}</formula><formula>${OfficeFormats.esc(operand(v[1]))}</formula>"""
                    "contains" -> """type="containsText" dxfId="$dxf" priority="1" operator="containsText" text="${OfficeFormats.esc(v[0])}"><formula>${OfficeFormats.esc("NOT(ISERROR(SEARCH(${operand(v[0])},$first)))")}</formula>"""
                    "duplicate" -> """type="duplicateValues" dxfId="$dxf" priority="1">"""
                    "top10" -> """type="top10" dxfId="$dxf" priority="1" rank="${v.firstOrNull()?.trim()?.toIntOrNull() ?: 10}">"""
                    "aboveAverage" -> """type="aboveAverage" dxfId="$dxf" priority="1">"""
                    "dataBar" -> """type="dataBar" priority="1"><dataBar><cfvo type="min"/><cfvo type="max"/><color rgb="FF638EC6"/></dataBar>"""
                    else -> """type="colorScale" priority="1"><colorScale><cfvo type="min"/><cfvo type="percentile" val="50"/><cfvo type="max"/><color rgb="FFF8696B"/><color rgb="FFFFEB84"/><color rgb="FF63BE7B"/></colorScale>"""
                }
                // the others one lower: the new rule first, as Excel puts it
                sheet = Regex("""(<(?:x14:)?cfRule\b[^>]*?\bpriority=")(\d+)""").replace(sheet) { m -> m.groupValues[1] + (m.groupValues[2].toInt() + 1) }
                val made = """<conditionalFormatting sqref="${ref(range)}"><cfRule $body</cfRule></conditionalFormatting>"""
                // in the schema's place: after the merged cells, before the validations, links and the page's settings
                val at = Regex("""<(dataValidations|hyperlinks|printOptions|pageMargins|pageSetup|headerFooter|rowBreaks|colBreaks|customProperties|cellWatches|ignoredErrors|smartTags|drawing|legacyDrawing|legacyDrawingHF|drawingHF|picture|oleObjects|controls|webPublishItems|tableParts|extLst)\b""").find(sheet)?.range?.first ?: sheet.lastIndexOf("</worksheet>")
                // after the rules there are: Excel reads them in order
                val after = Regex("""</conditionalFormatting>""").findAll(sheet.substring(0, at)).lastOrNull()?.range?.last?.plus(1) ?: at
                sheet = sheet.substring(0, after) + made + sheet.substring(after)
            }
            out[sheetPart] = sheet.toByteArray()
            SheetSave.rezip(zip, target, out, null)
        }
    }

    /** LibreOffice: [rule] put on [range] of sheet [index] named [name]; null: the rules over [range] taken away. */
    fun ods(source: File, target: File, index: Int, name: String, range: SheetRange, rule: Rule?) {
        ZipFile(source).use { zip ->
            val CALC = "urn:org:documentfoundation:names:experimental:calc:xmlns:calcext:1.0"
            val STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"; val FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
            val OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
            val doc = OfficeFormats.parse(zip.getInputStream(zip.getEntry("content.xml") ?: sheetProblem(R.string.tables_err_not_spreadsheet)).use { it.readBytes() })
            val table = doc.getElementsByTagNameNS(SheetMerge.T, "table").let { l -> (0 until l.length).map { l.item(it) as Element } }
                .filter { t -> generateSequence(t.parentNode) { it.parentNode }.none { it is Element && it.localName == "table" } }.getOrNull(index) ?: sheetProblem(R.string.tables_err_no_sheet_n, index + 1)
            fun kids(e: Element, local: String) = (0 until e.childNodes.length).mapNotNull { e.childNodes.item(it) as? Element }.filter { it.localName == local }
            val q = if (Regex("""^\w+$""").matches(name)) name else "'" + name.replace("'", "''") + "'"
            fun addr(g: SheetRange) = "$q.${Sheets.colName(g.c1)}${g.r1 + 1}:$q.${Sheets.colName(g.c2)}${g.r2 + 1}"
            val all = kids(table, "conditional-formats").firstOrNull()
            if (rule == null) all?.let { a -> kids(a, "conditional-format").filter { f ->
                f.getAttributeNS(CALC, "target-range-address").split(' ').mapNotNull { r -> Sheets.rangeRef(r.split(':').joinToString(":") { it.substringAfterLast('.').replace("$", "") }) }.any { overlaps(it, range) }
            }.forEach { a.removeChild(it) } }
            var styles: org.w3c.dom.Document? = null
            if (rule != null) {
                // a file not LibreOffice's may not name its namespace
                if (!doc.documentElement.hasAttribute("xmlns:calcext")) doc.documentElement.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:calcext", CALC)
                val a = all ?: doc.createElementNS(CALC, "calcext:conditional-formats").also { table.appendChild(it) }
                val f = doc.createElementNS(CALC, "calcext:conditional-format").also { it.setAttributeNS(CALC, "calcext:target-range-address", addr(range)) }
                val v = rule.values
                fun entry(parent: Element, tag: String, type: String, value: String, color: String? = null) = parent.appendChild(doc.createElementNS(CALC, "calcext:$tag").also { e ->
                    e.setAttributeNS(CALC, "calcext:value", value); e.setAttributeNS(CALC, "calcext:type", type); color?.let { e.setAttributeNS(CALC, "calcext:color", it) } })
                when (rule.kind) {
                    "dataBar" -> f.appendChild(doc.createElementNS(CALC, "calcext:data-bar").also { b ->
                        b.setAttributeNS(CALC, "calcext:max-length", "100"); b.setAttributeNS(CALC, "calcext:positive-color", "#638ec6")
                        b.setAttributeNS(CALC, "calcext:negative-color", "#ff0000"); b.setAttributeNS(CALC, "calcext:axis-color", "#000000")
                        entry(b, "formatting-entry", "auto-minimum", "0"); entry(b, "formatting-entry", "auto-maximum", "0") })
                    "colorScale" -> f.appendChild(doc.createElementNS(CALC, "calcext:color-scale").also { s ->
                        entry(s, "color-scale-entry", "minimum", "0", "#f8696b"); entry(s, "color-scale-entry", "percentile", "50", "#ffeb84"); entry(s, "color-scale-entry", "maximum", "0", "#63be7b") })
                    else -> {
                        // the look: a cell style of its own in styles.xml, as LibreOffice's conditions name one
                        val (_, fill, color) = LOOKS[rule.look]
                        // one name, no display name: LibreOffice finds a condition's style by the name it shows
                        val styleName = "Tetra_cf_${hex(fill)}"
                        styles = OfficeFormats.parse(zip.getInputStream(zip.getEntry("styles.xml") ?: sheetProblem(R.string.tables_err_no_styles)).use { it.readBytes() })
                        val st = styles!!
                        val office = st.getElementsByTagNameNS(OFFICE, "styles").item(0) as Element? ?: st.createElementNS(OFFICE, "office:styles").also { st.documentElement.insertBefore(it, st.documentElement.firstChild) }
                        if (kids(office, "style").none { it.getAttributeNS(STYLE, "name") == styleName }) office.appendChild(st.createElementNS(STYLE, "style:style").also { s ->
                            s.setAttributeNS(STYLE, "style:name", styleName)
                            s.setAttributeNS(STYLE, "style:family", "table-cell"); s.setAttributeNS(STYLE, "style:parent-style-name", "Default")
                            s.appendChild(st.createElementNS(STYLE, "style:table-cell-properties").also { it.setAttributeNS(FO, "fo:background-color", "#" + hex(fill).lowercase()) })
                            s.appendChild(st.createElementNS(STYLE, "style:text-properties").also { it.setAttributeNS(FO, "fo:color", "#" + hex(color).lowercase()) }) })
                        val value = when (rule.kind) {
                            "greaterThan" -> ">" + operand(v[0]); "lessThan" -> "<" + operand(v[0]); "equal" -> "=" + operand(v[0])
                            "between" -> "between(${operand(v[0])},${operand(v[1])})"
                            "contains" -> "contains-text(\"${v[0].replace("\"", "\"\"")}\")"
                            "duplicate" -> "duplicate"
                            "top10" -> "top-elements(${v.firstOrNull()?.trim()?.toIntOrNull() ?: 10})"
                            else -> "above-average"
                        }
                        f.appendChild(doc.createElementNS(CALC, "calcext:condition").also { c ->
                            c.setAttributeNS(CALC, "calcext:apply-style-name", styleName); c.setAttributeNS(CALC, "calcext:value", value)
                            c.setAttributeNS(CALC, "calcext:base-cell-address", "$q.${Sheets.colName(range.c1)}${range.r1 + 1}") })
                    }
                }
                a.insertBefore(f, a.firstChild) // first: LibreOffice, as Excel, takes the first rule that holds
            }
            SheetSave.rezip(zip, target, LinkedHashMap<String, ByteArray?>().also { m -> m["content.xml"] = SlidesEdit.domXml(doc); styles?.let { m["styles.xml"] = SlidesEdit.domXml(it) } }, "mimetype")
        }
    }
}
