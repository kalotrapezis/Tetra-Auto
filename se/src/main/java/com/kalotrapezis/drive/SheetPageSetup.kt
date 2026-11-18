package com.kalotrapezis.drive

import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile

/**
 * Page layout (Excel's tab of that name): how a sheet prints — portrait or landscape, A4 or Letter, the margins, a scale
 * or fit to pages, gridlines printed, the print area. Excel's pageSetup, pageMargins, printOptions, sheetPr's fitToPage
 * and _xlnm.Print_Area; LibreOffice's page layout (a master page of the sheet's own, so other sheets keep theirs) and
 * table:print-ranges. What Export PDF and Print then use ([sheetPages]). A change to the file.
 */
internal object SheetPageSetup {
    private fun ref(g: SheetRange) = "\$${Sheets.colName(g.c1)}\$${g.r1 + 1}:\$${Sheets.colName(g.c2)}\$${g.r2 + 1}"
    private fun num(v: Float) = String.format(java.util.Locale.ROOT, "%.3f", v).trimEnd('0').trimEnd('.')

    fun xlsx(source: File, target: File, sheetPart: String, index: Int, name: String, p: SheetPrint) {
        ZipFile(source).use { zip ->
            fun read(n: String) = zip.getEntry(n)?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }
            var x = read(sheetPart) ?: sheetProblem(R.string.tables_err_no_sheet)
            fun attrs(tag: String?) = Regex("""(\w+(?::\w+)?)="([^"]*)"""").findAll(tag.orEmpty()).associate { it.groupValues[1] to it.groupValues[2] }.toMutableMap()
            // pageSetup: its own other settings (a printer's r:id…) kept
            val setup = Regex("""<pageSetup\b[^>]*/>""").find(x)
            val sa = attrs(setup?.value?.removePrefix("<pageSetup"))
            sa["orientation"] = if (p.landscape) "landscape" else "portrait"; sa["paperSize"] = if (p.letter) "1" else "9"
            sa["scale"] = "${p.scale}"; if (p.fitWidth != null) { sa["fitToWidth"] = "${p.fitWidth}"; sa["fitToHeight"] = "${p.fitHeight ?: 0}" } else { sa.remove("fitToWidth"); sa.remove("fitToHeight") }
            val setupXml = "<pageSetup " + sa.entries.joinToString(" ") { (k, v) -> "$k=\"$v\"" } + "/>"
            val (l, r, t, b) = p.margins
            val margins = """<pageMargins left="${num(l)}" right="${num(r)}" top="${num(t)}" bottom="${num(b)}" header="0.3" footer="0.3"/>"""
            val options = if (p.grid) """<printOptions gridLines="1"/>""" else ""
            x = x.replace(Regex("""<printOptions\b[^>]*/>"""), "").replace(Regex("""<pageMargins\b[^>]*/>"""), "").replace(Regex("""<pageSetup\b[^>]*/>"""), "")
                .replace(Regex("""<headerFooter\b[^>]*?(?:/>|>[\s\S]*?</headerFooter>)"""), "")
            // shortcut: Excel's even-page and first-page headers are not kept (one header and footer for every page)
            val hf = if (p.header == null && p.footer == null) "" else "<headerFooter>" + (p.header?.let { "<oddHeader>${OfficeFormats.esc(it)}</oddHeader>" } ?: "") +
                (p.footer?.let { "<oddFooter>${OfficeFormats.esc(it)}</oddFooter>" } ?: "") + "</headerFooter>"
            // in the schema's place: after the sheet's data and its rules, links and validations; before the header and footer and the rest
            val at = Regex("""<(headerFooter|rowBreaks|colBreaks|customProperties|cellWatches|ignoredErrors|smartTags|drawing|legacyDrawing|legacyDrawingHF|drawingHF|picture|oleObjects|controls|webPublishItems|tableParts|extLst)\b""").find(x)?.range?.first ?: x.lastIndexOf("</worksheet>")
            x = x.substring(0, at) + options + margins + setupXml + hf + x.substring(at)
            // fit to pages is a sheet property (sheetPr's pageSetUpPr)
            val fit = """<pageSetUpPr fitToPage="${if (p.fitWidth != null) 1 else 0}"/>"""
            x = when {
                Regex("""<pageSetUpPr\b[^>]*/>""").containsMatchIn(x) -> x.replace(Regex("""<pageSetUpPr\b[^>]*/>"""), fit)
                Regex("""<sheetPr\b[^>]*/>""").containsMatchIn(x) -> x.replace(Regex("""<sheetPr\b([^>]*)/>"""), "<sheetPr$1>$fit</sheetPr>")
                "</sheetPr>" in x -> x.replace("</sheetPr>", "$fit</sheetPr>")
                else -> x.replaceFirst(Regex("""(<worksheet\b[^>]*>)"""), "$1<sheetPr>$fit</sheetPr>")
            }
            // the print area: the workbook's defined name for this sheet
            var wb = read("xl/workbook.xml") ?: sheetProblem(R.string.tables_err_not_workbook)
            // and the rows printed on every page (shortcut: columns printed on every page are not kept; upgrade if a file has them)
            for (n in listOf("Print_Area", "Print_Titles")) wb = wb.replace(Regex("""<definedName\b[^>]*name="_xlnm\.$n"[^>]*localSheetId="$index"[^>]*>[^<]*</definedName>"""), "")
                .replace(Regex("""<definedName\b[^>]*localSheetId="$index"[^>]*name="_xlnm\.$n"[^>]*>[^<]*</definedName>"""), "")
            val quoted = "'${OfficeFormats.esc(name.replace("'", "''"))}'"
            listOfNotNull(p.area?.let { g -> """<definedName name="_xlnm.Print_Area" localSheetId="$index">$quoted!${ref(g)}</definedName>""" },
                p.titles?.let { t -> """<definedName name="_xlnm.Print_Titles" localSheetId="$index">$quoted!${'$'}${t.first + 1}:${'$'}${t.last + 1}</definedName>""" }).forEach { d ->
                wb = if ("</definedNames>" in wb) wb.replace("</definedNames>", "$d</definedNames>") else wb.replace("</sheets>", "</sheets><definedNames>$d</definedNames>") }
            wb = wb.replace(Regex("""<definedNames>\s*</definedNames>"""), "")
            SheetSave.rezip(zip, target, mapOf(sheetPart to x.toByteArray(), "xl/workbook.xml" to wb.toByteArray()), null)
        }
    }

    private const val S = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    private const val FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
    private fun kids(e: Element) = (0 until e.childNodes.length).mapNotNull { e.childNodes.item(it) as? Element }
    private fun tables(doc: org.w3c.dom.Document) = doc.getElementsByTagNameNS(SheetMerge.T, "table").let { l -> (0 until l.length).map { l.item(it) as Element } }
        .filter { t -> generateSequence(t.parentNode) { it.parentNode }.none { it is Element && it.localName == "table" } }
    private fun cm(inches: Float) = String.format(java.util.Locale.ROOT, "%.3fcm", inches * 2.54f)

    /** LibreOffice's room for a page's [kind] ("header", "footer") when [master] shows one: its height with its gap (pt). */
    private fun room(layout: Element?, master: Element?, kind: String): Float {
        val shown = master?.let { m -> kids(m).firstOrNull { it.localName == kind } }?.takeIf { it.getAttributeNS(S, "display") != "false" } ?: return 0f
        val props = layout?.let { l -> kids(l).firstOrNull { it.localName == "$kind-style" } }?.let { kids(it).firstOrNull { k -> k.localName == "header-footer-properties" } } ?: return 0f
        return (odfLength(props.getAttributeNS(FO, "min-height").ifEmpty { null }) ?: odfLength(props.getAttributeNS(FO, "height").ifEmpty { null }) ?: 0f).let { if (shown.hasChildNodes()) it else it }
    }

    /** LibreOffice: sheet [index]'s print settings from its master page's page layout and its print ranges; null: none said. */
    fun readOds(styles: org.w3c.dom.Document?, masterPage: String?, printRanges: String?): SheetPrint? {
        val masterName = masterPage ?: "Default"
        val masters = styles?.getElementsByTagNameNS(S, "master-page")?.let { l -> (0 until l.length).map { l.item(it) as Element } }.orEmpty()
        val master = masters.firstOrNull { it.getAttributeNS(S, "name") == masterName } ?: masters.firstOrNull()
        val layout = styles?.getElementsByTagNameNS(S, "page-layout")?.let { l -> (0 until l.length).map { l.item(it) as Element } }
            ?.firstOrNull { it.getAttributeNS(S, "name") == master?.getAttributeNS(S, "page-layout-name") }
        val pr = layout?.let { kids(it).firstOrNull { k -> k.localName == "page-layout-properties" } }
        val area = printRanges?.takeIf { it.isNotEmpty() }?.split(' ')?.firstOrNull()
            ?.split(':')?.joinToString(":") { it.substringAfterLast('.').replace("$", "") }?.let(Sheets::rangeRef)
        if (pr == null && area == null) return null
        fun inches(n: String) = odfLength(pr?.getAttributeNS(FO, n)?.ifEmpty { null })?.div(72f)
        val w = odfLength(pr?.getAttributeNS(FO, "page-width")?.ifEmpty { null }) ?: 595f; val h = odfLength(pr?.getAttributeNS(FO, "page-height")?.ifEmpty { null }) ?: 842f
        val fitX = pr?.getAttributeNS(S, "scale-to-X")?.toIntOrNull(); val fitY = pr?.getAttributeNS(S, "scale-to-Y")?.toIntOrNull()
        return SheetPrint(
            landscape = pr?.getAttributeNS(S, "print-orientation") == "landscape" || w > h,
            scale = pr?.getAttributeNS(S, "scale-to")?.removeSuffix("%")?.toFloatOrNull()?.toInt()?.coerceIn(10, 400) ?: 100,
            fitWidth = fitX ?: fitY?.let { 0 }, fitHeight = fitY ?: fitX?.let { 0 },
            grid = pr?.getAttributeNS(S, "print")?.split(' ')?.contains("grid") == true,
            // Excel's top and bottom are the page's edge to the cells: LibreOffice's margin plus its header's (footer's) room
            margins = listOf(inches("margin-left") ?: 0.79f, inches("margin-right") ?: 0.79f, (inches("margin-top") ?: 0.79f) + room(layout, master, "header") / 72f,
                (inches("margin-bottom") ?: 0.79f) + room(layout, master, "footer") / 72f),
            area = area, letter = Math.abs(minOf(w, h) - 612f) < 3f,
            header = master?.let { m -> SheetHeaderFooter.fromOds(kids(m).firstOrNull { it.localName == "header" }) },
            footer = master?.let { m -> SheetHeaderFooter.fromOds(kids(m).firstOrNull { it.localName == "footer" }) })
    }

    fun ods(source: File, target: File, index: Int, name: String, p: SheetPrint) {
        ZipFile(source).use { zip ->
            fun bytes(n: String) = zip.getEntry(n)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
            val content = OfficeFormats.parse(bytes("content.xml") ?: sheetProblem(R.string.tables_err_not_spreadsheet))
            val styles = OfficeFormats.parse(bytes("styles.xml") ?: sheetProblem(R.string.tables_err_no_styles))
            val table = tables(content).getOrNull(index) ?: sheetProblem(R.string.tables_err_no_sheet_n, index + 1)
            // a page layout and master page of this sheet's own (named after it), copies of the ones it had
            val masters = styles.getElementsByTagNameNS(S, "master-page").let { l -> (0 until l.length).map { l.item(it) as Element } }
            val layouts = styles.getElementsByTagNameNS(S, "page-layout").let { l -> (0 until l.length).map { l.item(it) as Element } }
            val autosC = content.getElementsByTagNameNS("urn:oasis:names:tc:opendocument:xmlns:office:1.0", "automatic-styles").item(0) as Element
            val tableStyleName = table.getAttributeNS(SheetMerge.T, "style-name")
            val tableStyle = kids(autosC).firstOrNull { it.getAttributeNS(S, "name") == tableStyleName && it.getAttributeNS(S, "family") == "table" }
            val oldMaster = masters.firstOrNull { it.getAttributeNS(S, "name") == (tableStyle?.getAttributeNS(S, "master-page-name")?.ifEmpty { null } ?: "Default") } ?: masters.first()
            val oldLayout = layouts.first { it.getAttributeNS(S, "name") == oldMaster.getAttributeNS(S, "page-layout-name") }
            val mine = "Tetra_${index + 1}"
            masters.filter { it.getAttributeNS(S, "name") == mine }.forEach { it.parentNode.removeChild(it) }
            layouts.filter { it.getAttributeNS(S, "name") == "${mine}_pm" }.forEach { it.parentNode.removeChild(it) }
            val layout = oldLayout.cloneNode(true) as Element; layout.setAttributeNS(S, "style:name", "${mine}_pm"); oldLayout.parentNode.appendChild(layout)
            val master = oldMaster.cloneNode(true) as Element; master.setAttributeNS(S, "style:name", mine); master.removeAttributeNS(S, "display-name")
            master.setAttributeNS(S, "style:page-layout-name", "${mine}_pm"); oldMaster.parentNode.appendChild(master)
            val pr = kids(layout).firstOrNull { it.localName == "page-layout-properties" } ?: styles.createElementNS(S, "style:page-layout-properties").also { layout.appendChild(it) }
            val (short, long) = if (p.letter) 612f to 792f else 595.3f to 841.9f
            pr.setAttributeNS(FO, "fo:page-width", cm((if (p.landscape) long else short) / 72f)); pr.setAttributeNS(FO, "fo:page-height", cm((if (p.landscape) short else long) / 72f))
            pr.setAttributeNS(S, "style:print-orientation", if (p.landscape) "landscape" else "portrait")
            // the header and footer: the master page's regions, the page layout giving them room (LibreOffice's default) when it gave none
            SheetHeaderFooter.intoOds(master, "header", p.header); SheetHeaderFooter.intoOds(master, "footer", p.footer)
            for ((kind, code) in listOf("header" to p.header, "footer" to p.footer)) if (code != null && kids(layout).none { it.localName == "$kind-style" && kids(it).isNotEmpty() }) {
                kids(layout).filter { it.localName == "$kind-style" }.forEach { layout.removeChild(it) }
                layout.appendChild(styles.createElementNS(S, "style:$kind-style").also { st -> st.appendChild(styles.createElementNS(S, "style:header-footer-properties").also {
                    it.setAttributeNS(FO, "fo:min-height", "0.75cm"); it.setAttributeNS(FO, if (kind == "header") "fo:margin-bottom" else "fo:margin-top", "0.25cm") }) })
            }
            val rooms = listOf(0f, 0f, room(layout, master, "header") / 72f, room(layout, master, "footer") / 72f)
            listOf("margin-left", "margin-right", "margin-top", "margin-bottom").zip(p.margins).forEachIndexed { k, (n, v) -> pr.setAttributeNS(FO, "fo:$n", cm(maxOf(0f, v - rooms[k]))) }
            listOf("scale-to", "scale-to-X", "scale-to-Y", "scale-to-pages").forEach { pr.removeAttributeNS(S, it) }
            if (p.fitWidth != null) { if ((p.fitWidth ?: 0) > 0) pr.setAttributeNS(S, "style:scale-to-X", "${p.fitWidth}"); if ((p.fitHeight ?: 0) > 0) pr.setAttributeNS(S, "style:scale-to-Y", "${p.fitHeight}") }
            else pr.setAttributeNS(S, "style:scale-to", "${p.scale}%")
            val printed = pr.getAttributeNS(S, "print").split(' ').filter { it.isNotEmpty() && it != "grid" }.ifEmpty { listOf("objects", "charts", "drawings", "zero-values") }
            pr.setAttributeNS(S, "style:print", (printed + listOfNotNull("grid".takeIf { p.grid })).joinToString(" "))
            // the sheet's table style naming the master page (a copy, others may share the style)
            val st = (tableStyle?.cloneNode(true) as Element?) ?: content.createElementNS(S, "style:style").also { it.setAttributeNS(S, "style:family", "table") }
            val stName = generateSequence(1) { it + 1 }.map { "taP$it" }.first { n -> kids(autosC).none { it.getAttributeNS(S, "name") == n } }
            st.setAttributeNS(S, "style:name", stName); st.setAttributeNS(S, "style:master-page-name", mine); autosC.appendChild(st)
            table.setAttributeNS(SheetMerge.T, "table:style-name", stName)
            p.area?.let { g -> table.setAttributeNS(SheetMerge.T, "table:print-ranges", "'${name.replace("'", "''")}'.${Sheets.colName(g.c1)}${g.r1 + 1}:'${name.replace("'", "''")}'.${Sheets.colName(g.c2)}${g.r2 + 1}") }
                ?: table.removeAttributeNS(SheetMerge.T, "print-ranges")
            // the rows printed on every page: LibreOffice's header rows round them (the old ones' rows put back first)
            SheetMerge.kids(table, "table-header-rows").forEach { h -> while (h.firstChild != null) table.insertBefore(h.firstChild, h); table.removeChild(h) }
            p.titles?.let { t ->
                fun make() = content.createElementNS(SheetMerge.T, "table:table-row").also { table.appendChild(it) }
                SheetMerge.at({ SheetMerge.rowsOf(table) }, t.first, "number-rows-repeated", ::make); SheetMerge.at({ SheetMerge.rowsOf(table) }, t.last, "number-rows-repeated", ::make)
                var n = 0
                val rows = SheetMerge.rowsOf(table).filter { e -> val at = n; n += e.getAttributeNS(SheetMerge.T, "number-rows-repeated").toIntOrNull() ?: 1; at in t }
                // shortcut: rows in a row group are not wrapped; upgrade if grouped sheets want print titles
                if (rows.isNotEmpty() && rows.all { it.parentNode === table }) {
                    val h = content.createElementNS(SheetMerge.T, "table:table-header-rows"); table.insertBefore(h, rows.first())
                    rows.forEach { h.appendChild(it) }
                }
            }
            SheetSave.rezip(zip, target, mapOf("content.xml" to SlidesEdit.domXml(content), "styles.xml" to SlidesEdit.domXml(styles)), "mimetype")
        }
    }
}
