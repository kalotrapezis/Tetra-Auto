package com.kalotrapezis.drive

import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile

/**
 * Data › Group and Ungroup rows, and their Collapse and Expand (Excel's outline; LibreOffice's Group and Outline): rows
 * [from]..[to] (the sheet's, from 0) a level deeper ([by] 1) or less (-1), and a group's rows hidden ([collapse] true) or
 * shown (false). Excel's row outlineLevel, hidden and collapsed (on the row after the group, its summary); LibreOffice's
 * table:table-row-group round the rows (display "false", rows visibility "collapse"). A change to the file.
 */
internal object SheetOutline {
    /** The group round row [r] by [levels] (row → its level): its rows; or, on the row just after a group, that group's. */
    fun groupAt(levels: Map<Int, Int>, r: Int): IntRange? {
        val at = if ((levels[r] ?: 0) > 0) r else if ((levels[r - 1] ?: 0) > 0) r - 1 else return null
        val level = levels.getValue(at)
        var a = at; while ((levels[a - 1] ?: 0) >= level) a--
        var b = at; while ((levels[b + 1] ?: 0) >= level) b++
        return a..b
    }

    private fun attrs(tag: String) = Regex("""(\w+)="([^"]*)"""").findAll(tag).associate { it.groupValues[1] to it.groupValues[2] }.toMutableMap()

    fun xlsx(source: File, target: File, sheetPart: String, from: Int, to: Int, by: Int, collapse: Boolean?) {
        ZipFile(source).use { zip ->
            var x = zip.getEntry(sheetPart)?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } } ?: sheetProblem(R.string.tables_err_no_sheet)
            val data = Regex("""<sheetData\b[^>]*?(?:/>|>([\s\S]*?)</sheetData>)""").find(x) ?: sheetProblem(R.string.tables_err_no_sheet_data)
            val rows = Regex("""<row\b[^>]*?(?:/>|>[\s\S]*?</row>)""").findAll(data.groupValues[1]).map { m -> (Regex("""\sr="(\d+)"""").find(m.value)!!.groupValues[1].toInt() - 1) to m.value }.toMap(java.util.TreeMap())
            fun edit(r: Int, change: (MutableMap<String, String>) -> Unit) {
                val row = rows[r] ?: """<row r="${r + 1}"/>"""
                val end = row.indexOf('>'); val closed = row[end - 1] == '/'
                val a = attrs(row.substring(4, if (closed) end - 1 else end)); change(a)
                rows[r] = "<row " + a.entries.joinToString(" ") { (k, v) -> "$k=\"$v\"" } + (if (closed) "/>" else ">" + row.substring(end + 1))
            }
            for (r in from..to) edit(r) { a ->
                if (by != 0) { val level = ((a["outlineLevel"]?.toIntOrNull() ?: 0) + by).coerceIn(0, 7); if (level > 0) a["outlineLevel"] = "$level" else a.remove("outlineLevel") }
                when (collapse) { true -> a["hidden"] = "1"; false -> a.remove("hidden"); null -> Unit }
            }
            // the summary row after the group says it is collapsed (Excel's plus button)
            if (collapse != null) edit(to + 1) { a -> if (collapse) a["collapsed"] = "1" else a.remove("collapsed") }
            x = x.replaceRange(data.range, "<sheetData>" + rows.values.joinToString("") + "</sheetData>")
            // the deepest level, for Excel's outline buttons
            val deepest = rows.values.maxOfOrNull { Regex("""\soutlineLevel="(\d+)"""").find(it)?.groupValues?.get(1)?.toInt() ?: 0 } ?: 0
            x = x.replace(Regex("""(<sheetFormatPr\b[^>]*?)\soutlineLevelRow="\d+""""), "$1")
            if (deepest > 0) x = if (Regex("""<sheetFormatPr\b""").containsMatchIn(x)) x.replaceFirst(Regex("""<sheetFormatPr\b"""), """<sheetFormatPr outlineLevelRow="$deepest"""")
                else x.replaceFirst(Regex("""<sheetData\b"""), """<sheetFormatPr defaultRowHeight="15" outlineLevelRow="$deepest"/><sheetData""")
            SheetSave.rezip(zip, target, mapOf(sheetPart to x.toByteArray()), null)
        }
    }

    fun ods(source: File, target: File, index: Int, from: Int, to: Int, by: Int, collapse: Boolean?) {
        ZipFile(source).use { zip ->
            val doc = OfficeFormats.parse(zip.getInputStream(zip.getEntry("content.xml") ?: sheetProblem(R.string.tables_err_not_spreadsheet)).use { it.readBytes() })
            val T = SheetMerge.T
            val table = doc.getElementsByTagNameNS(T, "table").let { l -> (0 until l.length).map { l.item(it) as Element } }
                .filter { t -> generateSequence(t.parentNode) { it.parentNode }.none { it is Element && it.localName == "table" } }.getOrNull(index) ?: sheetProblem(R.string.tables_err_no_sheet_n, index + 1)
            fun make() = doc.createElementNS(T, "table:table-row").also { table.appendChild(it) }
            // rows from..to as elements of their own
            SheetMerge.at({ SheetMerge.rowsOf(table) }, from, "number-rows-repeated", ::make); SheetMerge.at({ SheetMerge.rowsOf(table) }, to, "number-rows-repeated", ::make)
            if (to + 1 < 1_048_576) SheetMerge.at({ SheetMerge.rowsOf(table) }, to + 1, "number-rows-repeated", ::make)
            var n = 0
            val rows = SheetMerge.rowsOf(table).filter { e -> val at = n; n += e.getAttributeNS(T, "number-rows-repeated").toIntOrNull() ?: 1; at in from..to }
            if (rows.isEmpty()) sheetProblem(R.string.tables_err_no_rows_there)
            when {
                by > 0 -> {
                    // shortcut: rows across a group's edge are not grouped (Excel splits them); upgrade if that is asked for
                    if (rows.any { it.parentNode !== rows[0].parentNode }) sheetProblem(R.string.tables_err_rows_other_group)
                    val g = doc.createElementNS(T, "table:table-row-group"); rows[0].parentNode.insertBefore(g, rows[0]); rows.forEach { g.appendChild(it) }
                }
                by < 0 -> {
                    // the innermost group round the rows taken apart (its rows put back where it was)
                    val g = rows[0].parentNode as? Element ?: sheetProblem(R.string.tables_err_not_grouped)
                    if (g.localName != "table-row-group") sheetProblem(R.string.tables_err_rows_not_grouped)
                    while (g.firstChild != null) g.parentNode.insertBefore(g.firstChild, g); g.parentNode.removeChild(g)
                }
            }
            if (collapse != null) {
                val g = rows[0].parentNode as? Element
                if (g?.localName == "table-row-group") { if (collapse) g.setAttributeNS(T, "table:display", "false") else g.removeAttributeNS(T, "display") }
                rows.forEach { if (collapse) it.setAttributeNS(T, "table:visibility", "collapse") else if (it.getAttributeNS(T, "visibility") == "collapse") it.removeAttributeNS(T, "visibility") }
            }
            SheetSave.rezip(zip, target, mapOf("content.xml" to SlidesEdit.domXml(doc)), "mimetype")
        }
    }
}
