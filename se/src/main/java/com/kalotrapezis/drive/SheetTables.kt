package com.kalotrapezis.drive

import java.io.File
import java.util.zip.ZipFile

/**
 * Excel's tables (Insert › Table, Home › Format as Table): its built-in styles, laid on a range's cells, and a table
 * made on the chosen cells — Excel's table part (the file's, a change to it), or for LibreOffice, which has no table
 * styles, the same looks put on the cells as its AutoFormat does.
 */
internal object SheetTables {
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    /** Office's theme colours (light 1, dark 1, light 2, dark 2, accents 1–6, as a file's are read): for a file without a theme of its own. */
    val OFFICE = intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFE7E6E6.toInt(), 0xFF44546A.toInt(), 0xFF4472C4.toInt(),
        0xFFED7D31.toInt(), 0xFFA5A5A5.toInt(), 0xFFFFC000.toInt(), 0xFF5B9BD5.toInt(), 0xFF70AD47.toInt())
    /** Home › Format as table: Excel's own, Medium 2 (its default) first — Medium 1–7 and Light 8–14, black and the six accents. */
    val OFFERED = (listOf(2, 3, 4, 5, 6, 7, 1).map { "TableStyleMedium$it" } + (8..14).map { "TableStyleLight$it" })
    /** A style's colour, for its button: black or its accent. */
    fun colour(name: String, theme: IntArray = OFFICE) = Regex("""(\d+)$""").find(name)?.groupValues?.get(1)?.toInt()?.let { n -> (n - 1) % 7 }
        ?.let { k -> if (k == 0) 0xFF000000.toInt() else theme.getOrNull(3 + k) } ?: 0xFF000000.toInt()

    /**
     * Excel's built-in table styles, made from the theme's colours as Excel makes them: Light, Medium and Dark, each
     * in black and the six accents. shortcut: the common parts (header, stripes, lines) close to Excel's, not every
     * line of all 60; upgrade from Excel's own definitions (presetTableStyles.xml) if a file shows the difference.
     */
    fun builtIn(name: String, theme: IntArray): Map<String, SheetDxf>? {
        val m = Regex("TableStyle(Light|Medium|Dark)(\\d+)").matchEntire(name) ?: return null
        val n = m.groupValues[2].toInt()
        val k = (n - 1) % 7; val group = (n - 1) / 7
        val black = 0xFF000000.toInt(); val white = 0xFFFFFFFF.toInt()
        val base = if (k == 0) black else theme.getOrNull(3 + k) ?: return null
        fun t(x: Double) = SheetColors.tint(base, x)
        fun thin(c: Int) = SheetLine(c, 1f)
        val boxed = SheetDxf(left = thin(base), right = thin(base), top = thin(base), bottom = thin(base))
        val total = SheetDxf(bold = true, top = SheetLine(base, 3f, double = true))
        return when (m.groupValues[1]) {
            "Light" -> when (group) {
                0 -> mapOf("wholeTable" to SheetDxf(top = thin(base), bottom = thin(base)), "headerRow" to SheetDxf(bold = true, bottom = thin(base)),
                    "firstRowStripe" to SheetDxf(fill = t(0.8)), "firstColumn" to SheetDxf(bold = true), "lastColumn" to SheetDxf(bold = true), "totalRow" to total)
                1 -> mapOf("wholeTable" to boxed, "headerRow" to SheetDxf(fill = base, color = white, bold = true),
                    "firstRowStripe" to SheetDxf(top = thin(base), bottom = thin(base)), "firstColumn" to SheetDxf(bold = true), "lastColumn" to SheetDxf(bold = true), "totalRow" to total)
                else -> mapOf("wholeTable" to boxed.copy(horizontal = thin(base), vertical = thin(base)), "headerRow" to SheetDxf(bold = true, bottom = SheetLine(base, 2f)),
                    "firstRowStripe" to SheetDxf(fill = t(0.8)), "firstColumn" to SheetDxf(bold = true), "lastColumn" to SheetDxf(bold = true), "totalRow" to total)
            }
            "Medium" -> when (group) {
                0 -> mapOf("wholeTable" to SheetDxf(left = thin(t(0.4)), right = thin(t(0.4)), top = thin(t(0.4)), bottom = thin(t(0.4)), horizontal = thin(t(0.4))),
                    "headerRow" to SheetDxf(fill = base, color = white, bold = true), "firstRowStripe" to SheetDxf(fill = t(0.8)),
                    "firstColumn" to SheetDxf(bold = true), "lastColumn" to SheetDxf(bold = true), "totalRow" to total)
                1 -> mapOf("wholeTable" to SheetDxf(fill = t(0.8), horizontal = thin(white), vertical = thin(white)), "headerRow" to SheetDxf(fill = base, color = white, bold = true),
                    "firstRowStripe" to SheetDxf(fill = t(0.6)), "firstColumn" to SheetDxf(fill = base, color = white, bold = true),
                    "lastColumn" to SheetDxf(fill = base, color = white, bold = true), "totalRow" to SheetDxf(fill = base, color = white, bold = true, top = thin(white)))
                2 -> mapOf("wholeTable" to boxed.copy(horizontal = thin(base), vertical = thin(base)), "headerRow" to SheetDxf(fill = base, color = white, bold = true),
                    "firstRowStripe" to SheetDxf(fill = t(0.8)), "firstColumn" to SheetDxf(bold = true), "lastColumn" to SheetDxf(bold = true), "totalRow" to total)
                else -> mapOf("wholeTable" to SheetDxf(fill = t(0.8), horizontal = thin(t(0.4)), vertical = thin(t(0.4))), "headerRow" to SheetDxf(bold = true, bottom = SheetLine(base, 2f)),
                    "firstRowStripe" to SheetDxf(fill = t(0.6)), "firstColumn" to SheetDxf(bold = true), "lastColumn" to SheetDxf(bold = true), "totalRow" to total)
            }
            else -> if (group == 0) mapOf("wholeTable" to SheetDxf(fill = SheetColors.tint(base, -0.25).takeIf { k != 0 } ?: 0xFF737373.toInt(), color = white),
                "headerRow" to SheetDxf(fill = black, color = white, bold = true, bottom = SheetLine(white, 2f)), "firstRowStripe" to SheetDxf(fill = SheetColors.tint(base, -0.5).takeIf { k != 0 } ?: 0xFF404040.toInt()),
                "firstColumn" to SheetDxf(bold = true), "lastColumn" to SheetDxf(bold = true), "totalRow" to SheetDxf(fill = black, color = white, bold = true))
            else mapOf("wholeTable" to SheetDxf(fill = t(0.8)), "headerRow" to SheetDxf(fill = SheetColors.tint(base, -0.25), color = white, bold = true),
                "firstRowStripe" to SheetDxf(fill = t(0.6)), "firstColumn" to SheetDxf(bold = true), "lastColumn" to SheetDxf(bold = true), "totalRow" to total)
        }
    }

    /**
     * Each cell of table [ref] and the look its style's [parts] give it: the whole table, stripes, first and last column,
     * header and totals, as Excel lays them ([on]: the table's options, "showRowStripes" and the rest).
     */
    fun lay(parts: Map<String, SheetDxf>, ref: SheetRange, headers: Int, totals: Int, on: (String) -> Boolean): List<Pair<Long, SheetDxf>> {
        val dataTop = ref.r1 + headers; val dataBottom = ref.r2 - totals
        val out = ArrayList<Pair<Long, SheetDxf>>()
        for (r in ref.r1..ref.r2) for (c in ref.c1..ref.c2) {
            val header = r < dataTop; val total = r > dataBottom
            var d = SheetDxf()
            fun lay(type: String) { val e = parts[type] ?: return
                // A part's outer lines go round the whole table (or row, column); its inner ones between cells.
                d += e.copy(left = if (c == ref.c1) e.left else e.vertical, right = if (c == ref.c2) e.right else e.vertical,
                    top = if (r == ref.r1) e.top else e.horizontal, bottom = if (r == ref.r2) e.bottom else e.horizontal)
            }
            lay("wholeTable")
            if (on("showColumnStripes") && !header && !total) lay(if ((c - ref.c1) % 2 == 0) "firstColumnStripe" else "secondColumnStripe")
            if (on("showRowStripes") && !header && !total) lay(if ((r - dataTop) % 2 == 0) "firstRowStripe" else "secondRowStripe")
            if (on("showLastColumn") && c == ref.c2) lay("lastColumn")
            if (on("showFirstColumn") && c == ref.c1) lay("firstColumn")
            if (header) { lay("headerRow"); if (c == ref.c1 && on("showFirstColumn")) lay("firstHeaderCell"); if (c == ref.c2 && on("showLastColumn")) lay("lastHeaderCell") }
            if (total) { lay("totalRow"); if (c == ref.c1) lay("firstTotalCell"); if (c == ref.c2) lay("lastTotalCell") }
            if (d != SheetDxf()) out += Sheet.key(r, c) to d
        }
        return out
    }

    /** Look [st] with a table's [d] put on it (LibreOffice's AutoFormat: the table's parts win). */
    fun over(st: SheetStyle, d: SheetDxf) = st.copy(fill = d.fill ?: st.fill, color = d.color ?: st.color, bold = d.bold ?: st.bold, italic = d.italic ?: st.italic,
        left = d.left ?: st.left, right = d.right ?: st.right, top = d.top ?: st.top, bottom = d.bottom ?: st.bottom)

    /** A table's column names from its header cells, as Excel makes them: an empty one "Column n", one met before numbered on (Name2). */
    fun names(headers: List<String>): List<String> {
        val seen = HashSet<String>()
        return headers.mapIndexed { i, h -> val base = h.trim().ifEmpty { "Column${i + 1}" }
            generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base$it" }.first { seen.add(it.lowercase()) } }
    }

    /** Excel: table [range] (its first row the headers, [names] its columns) made on sheet part [sheetPart] in [style], row stripes on. */
    fun xlsx(source: File, target: File, sheetPart: String, range: SheetRange, names: List<String>, style: String) {
        ZipFile(source).use { zip ->
            fun read(p: String) = zip.getEntry(p)?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }
            val relsPath = sheetPart.substringBeforeLast('/') + "/_rels/" + sheetPart.substringAfterLast('/') + ".rels"
            var rels = read(relsPath) ?: """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"></Relationships>"""
            var types = read("[Content_Types].xml") ?: sheetProblem(R.string.tables_err_not_workbook)
            var sheet = read(sheetPart) ?: sheetProblem(R.string.tables_err_no_sheet)
            // the workbook's tables: their ids and names (each unique in the workbook), this sheet's not overlapped
            val tables = zip.entries().toList().filter { it.name.startsWith("xl/tables/") && it.name.endsWith(".xml") }.map { it.name to read(it.name).orEmpty() }
            val mine = Regex("""<Relationship\b[^>]*/table"[^>]*/>""").findAll(rels).mapNotNull { Regex("""Target="([^"]+)"""").find(it.value)?.groupValues?.get(1)?.substringAfterLast('/') }.toSet()
            fun overlaps(a: SheetRange) = a.r1 <= range.r2 && range.r1 <= a.r2 && a.c1 <= range.c2 && range.c1 <= a.c2
            if (tables.any { (p, x) -> p.substringAfterLast('/') in mine && Regex("""<table\b[^>]*\bref="([^"]+)"""").find(x)?.groupValues?.get(1)?.let(Sheets::rangeRef)?.let(::overlaps) == true })
                sheetProblem(R.string.tables_err_in_table)
            val id = (tables.mapNotNull { Regex("""<table\b[^>]*\bid="(\d+)"""").find(it.second)?.groupValues?.get(1)?.toInt() }.maxOrNull() ?: 0) + 1
            val taken = tables.mapNotNull { Regex("""\bdisplayName="([^"]+)"""").find(it.second)?.groupValues?.get(1)?.lowercase() }.toSet()
            val name = generateSequence(1) { it + 1 }.map { "Table$it" }.first { it.lowercase() !in taken }
            val path = generateSequence(1) { it + 1 }.map { "xl/tables/table$it.xml" }.first { zip.getEntry(it) == null }
            val ref = "${Sheets.colName(range.c1)}${range.r1 + 1}:${Sheets.colName(range.c2)}${range.r2 + 1}"
            val cols = names.mapIndexed { i, n -> """<tableColumn id="${i + 1}" name="${OfficeFormats.esc(n)}"/>""" }.joinToString("")
            val part = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><table xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" id="$id" name="$name" displayName="$name" ref="$ref" totalsRowShown="0"><autoFilter ref="$ref"/><tableColumns count="${names.size}">$cols</tableColumns><tableStyleInfo name="$style" showFirstColumn="0" showLastColumn="0" showRowStripes="1" showColumnStripes="0"/></table>"""
            val rId = "rId" + ((Regex("""Id="rId(\d+)"""").findAll(rels).maxOfOrNull { it.groupValues[1].toInt() } ?: 0) + 1)
            rels = rels.replace("</Relationships>", """<Relationship Id="$rId" Type="$REL/table" Target="../tables/${path.substringAfterLast('/')}"/></Relationships>""")
            types = types.replace("</Types>", """<Override PartName="/$path" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.table+xml"/></Types>""")
            // the sheet's own filter over these cells goes: the table has its own
            sheet = sheet.replace(Regex("""<autoFilter\b[^>]*\bref="([^"]+)"[^>]*?(?:/>|>[\s\S]*?</autoFilter>)""")) { m -> if (Sheets.rangeRef(m.groupValues[1])?.let(::overlaps) == true) "" else m.value }
            val tag = """<tablePart xmlns:r="$REL" r:id="$rId"/>"""
            sheet = if (Regex("""<tableParts\b""").containsMatchIn(sheet)) sheet.replace(Regex("""<tableParts\b[^>]*?count="(\d+)"([^>]*)>""")) { m -> """<tableParts count="${m.groupValues[1].toInt() + 1}"${m.groupValues[2]}>""" }.replace("</tableParts>", "$tag</tableParts>")
                else { val at = Regex("""<extLst\b""").find(sheet)?.range?.first ?: sheet.lastIndexOf("</worksheet>")
                    sheet.substring(0, at) + """<tableParts count="1">$tag</tableParts>""" + sheet.substring(at) }
            SheetSave.rezip(zip, target, mapOf(sheetPart to sheet.toByteArray(), relsPath to rels.toByteArray(), "[Content_Types].xml" to types.toByteArray(), path to part.toByteArray()), null)
        }
    }

}
