package com.kalotrapezis.drive

import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile

/**
 * A sheet hidden or shown again (Excel's Hide and Unhide on a sheet's tab): Excel's state="hidden" on the workbook's sheet
 * (the open one moved off it), LibreOffice's table:display in the sheet's table style. A change to the file.
 */
internal object SheetVisibility {
    fun xlsx(source: File, target: File, index: Int, hidden: Boolean) {
        ZipFile(source).use { zip ->
            var x = zip.getEntry("xl/workbook.xml")?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } } ?: sheetProblem(R.string.tables_err_not_workbook)
            val sheets = Regex("""<sheet\b[^>]*/>""").findAll(x).toList()
            val m = sheets.getOrNull(index) ?: sheetProblem(R.string.tables_err_no_sheet_n, index + 1)
            val bare = m.value.replace(Regex("""\sstate="[^"]*""""), "")
            x = x.replaceRange(m.range, if (hidden) bare.replaceFirst("<sheet ", """<sheet state="hidden" """) else bare)
            // Excel opens on the active tab: not a hidden one
            if (hidden) { val visible = Regex("""<sheet\b[^>]*/>""").findAll(x).withIndex().firstOrNull { (_, s) -> "state=" !in s.value }?.index ?: 0
                x = x.replace(Regex("""(<workbookView\b[^>]*?)\s(activeTab|firstSheet)="\d+""""), "$1").replace(Regex("""(<workbookView\b[^>]*?)\s(activeTab|firstSheet)="\d+""""), "$1")
                    .replace(Regex("""<workbookView\b"""), """<workbookView activeTab="$visible"""") }
            SheetSave.rezip(zip, target, mapOf("xl/workbook.xml" to x.toByteArray()), null)
        }
    }

    fun ods(source: File, target: File, index: Int, hidden: Boolean) = tableStyle(source, target, index) { it.setAttributeNS(SheetMerge.T, "table:display", if (hidden) "false" else "true") }

    /** A sheet tab's colour ([colour] null: none): Excel's sheetPr tabColor. */
    fun colourXlsx(source: File, target: File, sheetPart: String, colour: Int?) {
        ZipFile(source).use { zip ->
            var x = zip.getEntry(sheetPart)?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } } ?: sheetProblem(R.string.tables_err_no_sheet)
            x = x.replace(Regex("""<tabColor\b[^>]*/>"""), "")
            if (colour != null) { val tag = """<tabColor rgb="FF%06X"/>""".format(colour and 0xFFFFFF)
                x = when {
                    Regex("""<sheetPr\b[^>]*/>""").containsMatchIn(x) -> x.replace(Regex("""<sheetPr\b([^>]*)/>"""), "<sheetPr$1>$tag</sheetPr>")
                    Regex("""<sheetPr\b""").containsMatchIn(x) -> x.replaceFirst(Regex("""(<sheetPr\b[^>]*>)"""), "$1$tag") // tabColor first, as the schema has it
                    else -> x.replaceFirst(Regex("""(<worksheet\b[^>]*>)"""), "$1<sheetPr>$tag</sheetPr>")
                } }
            SheetSave.rezip(zip, target, mapOf(sheetPart to x.toByteArray()), null)
        }
    }

    /** LibreOffice: a sheet tab's colour, its table style's tableooo:tab-color. */
    fun colourOds(source: File, target: File, index: Int, colour: Int?) = tableStyle(source, target, index) { pr ->
        val OOO = "http://openoffice.org/2009/table"
        if (colour == null) pr.removeAttributeNS(OOO, "tab-color") else pr.setAttributeNS(OOO, "tableooo:tab-color", "#%06x".format(colour and 0xFFFFFF)) }

    /** Sheet [index]'s table style (a copy of its own: others may share it) with its table properties [set]. */
    private fun tableStyle(source: File, target: File, index: Int, set: (Element) -> Unit) {
        ZipFile(source).use { zip ->
            val doc = OfficeFormats.parse(zip.getInputStream(zip.getEntry("content.xml") ?: sheetProblem(R.string.tables_err_not_spreadsheet)).use { it.readBytes() })
            val S = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
            val table = doc.getElementsByTagNameNS(SheetMerge.T, "table").let { l -> (0 until l.length).map { l.item(it) as Element } }
                .filter { t -> generateSequence(t.parentNode) { it.parentNode }.none { it is Element && it.localName == "table" } }.getOrNull(index) ?: sheetProblem(R.string.tables_err_no_sheet_n, index + 1)
            val autos = doc.getElementsByTagNameNS("urn:oasis:names:tc:opendocument:xmlns:office:1.0", "automatic-styles").item(0) as? Element ?: sheetProblem(R.string.tables_err_no_styles)
            val styles = (0 until autos.childNodes.length).mapNotNull { autos.childNodes.item(it) as? Element }
            // the table's own style copied (others may share it), its properties set
            val old = table.getAttributeNS(SheetMerge.T, "style-name").takeIf { it.isNotEmpty() }?.let { n -> styles.firstOrNull { it.getAttributeNS(S, "name") == n && it.getAttributeNS(S, "family") == "table" } }
            val st = (old?.cloneNode(true) as Element?) ?: doc.createElementNS(S, "style:style").also { it.setAttributeNS(S, "style:family", "table") }
            val name = generateSequence(1) { it + 1 }.map { "taT$it" }.first { n -> styles.none { it.getAttributeNS(S, "name") == n } }
            st.setAttributeNS(S, "style:name", name)
            val pr = (0 until st.childNodes.length).mapNotNull { st.childNodes.item(it) as? Element }.firstOrNull { it.localName == "table-properties" }
                ?: doc.createElementNS(S, "style:table-properties").also { st.appendChild(it) }
            set(pr)
            autos.appendChild(st); table.setAttributeNS(SheetMerge.T, "table:style-name", name)
            SheetSave.rezip(zip, target, mapOf("content.xml" to SlidesEdit.domXml(doc)), "mimetype")
        }
    }

    /** View › Gridlines (Excel's), or Headings ([attr] "showRowColHeaders"): sheet part [sheetPart]'s first view shows them or not. */
    fun gridXlsx(source: File, target: File, sheetPart: String, on: Boolean, attr: String = "showGridLines") {
        ZipFile(source).use { zip ->
            var x = zip.getEntry(sheetPart)?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } } ?: sheetProblem(R.string.tables_err_no_sheet)
            val view = Regex("""<sheetView\b[^>]*?(/?)>""").find(x)
            x = if (view != null) x.replaceRange(view.range, view.value.replace(Regex("""\s$attr="[^"]*""""), "").let { v -> if (on) v else v.replaceFirst("<sheetView", """<sheetView $attr="0"""") })
                else if (on) x else x.replaceFirst(Regex("""<(sheetFormatPr|cols|sheetData)\b"""), """<sheetViews><sheetView $attr="0" workbookViewId="0"/></sheetViews><$1""")
            SheetSave.rezip(zip, target, mapOf(sheetPart to x.toByteArray()), null)
        }
    }

    /**
     * LibreOffice: sheet [name]'s ShowGrid (or [key]) in the document's view settings (shortcut: no entry of its own there:
     * every sheet's); [perSheet] false: the view's own, every sheet's (LibreOffice's headings are the whole file's).
     */
    fun gridOds(source: File, target: File, name: String, on: Boolean, key: String = "ShowGrid", perSheet: Boolean = true) {
        ZipFile(source).use { zip ->
            var x = zip.getEntry("settings.xml")?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } } ?: sheetProblem(R.string.tables_err_no_view_settings)
            val item = """<config:config-item config:name="$key" config:type="boolean">$on</config:config-item>"""
            val showGrid = Regex("""<config:config-item config:name="$key" config:type="boolean">[^<]*</config:config-item>""")
            // the sheet's own entry in the view's Tables list (not another list's — the macros' names its sheets too)
            val tables = Regex("""<config:config-item-map-named config:name="Tables">[\s\S]*?</config:config-item-map-named>""").find(x)
            val entry = if (!perSheet) null else tables?.let { t -> Regex("""(<config:config-item-map-entry config:name="${Regex.escape(OfficeFormats.esc(name))}">)([\s\S]*?)(</config:config-item-map-entry>)""").find(x, t.range.first)?.takeIf { it.range.last <= t.range.last } }
            x = if (entry != null) x.replaceRange(entry.range, entry.groupValues[1] + (if (showGrid.containsMatchIn(entry.groupValues[2])) showGrid.replace(entry.groupValues[2], item) else entry.groupValues[2] + item) + entry.groupValues[3])
                else if (showGrid.containsMatchIn(x)) showGrid.replace(x, item) else sheetProblem(R.string.tables_err_no_view_settings_keep)
            SheetSave.rezip(zip, target, mapOf("settings.xml" to x.toByteArray()), "mimetype")
        }
    }

    /** View › Freeze panes (Excel's): sheet part [sheetPart]'s first view with [rows] rows and [cols] columns kept still (none: unfrozen). */
    fun freezeXlsx(source: File, target: File, sheetPart: String, rows: Int, cols: Int) {
        ZipFile(source).use { zip ->
            var x = zip.getEntry(sheetPart)?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } } ?: sheetProblem(R.string.tables_err_no_sheet)
            val active = when { rows > 0 && cols > 0 -> "bottomRight"; rows > 0 -> "bottomLeft"; else -> "topRight" }
            val pane = if (rows <= 0 && cols <= 0) "" else "<pane" + (if (cols > 0) " xSplit=\"$cols\"" else "") + (if (rows > 0) " ySplit=\"$rows\"" else "") +
                " topLeftCell=\"${Sheets.colName(cols)}${rows + 1}\" activePane=\"$active\" state=\"frozen\"/>"
            val view = Regex("""<sheetView\b[^>]*?(?:/>|>[\s\S]*?</sheetView>)""").find(x)
            x = if (view != null) {
                val open = Regex("""<sheetView\b[^>]*?(/?)>""").find(view.value)!!
                // the old pane and the selections that name panes go; the new pane first, as the schema has it
                val inner = if (open.groupValues[1] == "/") "" else view.value.substring(open.value.length).removeSuffix("</sheetView>")
                    .replace(Regex("""<pane\b[^>]*/>"""), "").replace(Regex("""<selection\b[^>]*\bpane="[^"]*"[^>]*/>"""), "")
                x.replaceRange(view.range, open.value.removeSuffix("/>").removeSuffix(">") + ">" + pane + inner + "</sheetView>")
            } else if (pane.isEmpty()) x else x.replaceFirst(Regex("""<(sheetFormatPr|cols|sheetData)\b"""), """<sheetViews><sheetView workbookViewId="0">$pane</sheetView></sheetViews><$1""")
            SheetSave.rezip(zip, target, mapOf(sheetPart to x.toByteArray()), null)
        }
    }

    /** LibreOffice: sheet [name]'s view entry with [rows] rows and [cols] columns kept still (its split settings; none: unfrozen). */
    fun freezeOds(source: File, target: File, name: String, rows: Int, cols: Int) {
        ZipFile(source).use { zip ->
            var x = zip.getEntry("settings.xml")?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } } ?: sheetProblem(R.string.tables_err_no_view_settings)
            val items = linkedMapOf("HorizontalSplitMode" to ("short" to if (cols > 0) 2 else 0), "VerticalSplitMode" to ("short" to if (rows > 0) 2 else 0),
                "HorizontalSplitPosition" to ("int" to cols), "VerticalSplitPosition" to ("int" to rows), "ActiveSplitRange" to ("short" to if (rows > 0) 2 else if (cols > 0) 3 else 2),
                "PositionLeft" to ("int" to 0), "PositionRight" to ("int" to cols), "PositionTop" to ("int" to 0), "PositionBottom" to ("int" to rows))
            fun item(k: String, t: String, v: Int) = """<config:config-item config:name="$k" config:type="$t">$v</config:config-item>"""
            // a file LibreOffice wrote without a window has no view's sheets: LibreOffice's Views › Tables made
            if (!x.contains("""config:name="Tables"""")) {
                val tablesMap = """<config:config-item-map-named config:name="Tables"></config:config-item-map-named>"""
                x = when {
                    Regex("""<config:config-item-map-indexed config:name="Views">\s*<config:config-item-map-entry>""").containsMatchIn(x) ->
                        x.replaceFirst(Regex("""(<config:config-item-map-indexed config:name="Views">\s*<config:config-item-map-entry>)"""), "$1$tablesMap")
                    x.contains("""config:name="ooo:view-settings">""") -> x.replaceFirst("""config:name="ooo:view-settings">""",
                        """config:name="ooo:view-settings"><config:config-item-map-indexed config:name="Views"><config:config-item-map-entry><config:config-item config:name="ViewId" config:type="string">view1</config:config-item>$tablesMap</config:config-item-map-entry></config:config-item-map-indexed>""")
                    else -> sheetProblem(R.string.tables_err_no_view_settings_keep)
                }
            }
            val tables = Regex("""<config:config-item-map-named config:name="Tables">[\s\S]*?</config:config-item-map-named>""").find(x) ?: sheetProblem(R.string.tables_err_no_view_settings_keep)
            val entry = Regex("""(<config:config-item-map-entry config:name="${Regex.escape(OfficeFormats.esc(name))}">)([\s\S]*?)(</config:config-item-map-entry>)""").find(x, tables.range.first)?.takeIf { it.range.last <= tables.range.last }
            x = if (entry != null) {
                var body = entry.groupValues[2]
                for ((k, tv) in items) { val one = Regex("""<config:config-item config:name="$k" config:type="\w+">[^<]*</config:config-item>""")
                    body = if (one.containsMatchIn(body)) one.replace(body, item(k, tv.first, tv.second)) else body + item(k, tv.first, tv.second) }
                x.replaceRange(entry.range, entry.groupValues[1] + body + entry.groupValues[3])
            } else x.replaceRange(tables.range, tables.value.replace("</config:config-item-map-named>",
                """<config:config-item-map-entry config:name="${OfficeFormats.esc(name)}">""" + items.entries.joinToString("") { (k, tv) -> item(k, tv.first, tv.second) } + "</config:config-item-map-entry></config:config-item-map-named>"))
            SheetSave.rezip(zip, target, mapOf("settings.xml" to x.toByteArray()), "mimetype")
        }
    }
}
