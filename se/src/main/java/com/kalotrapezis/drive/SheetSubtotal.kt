package com.kalotrapezis.drive

import java.io.File

/**
 * Data › Subtotal (Excel's): in cells [g] (its first row the headings), at each change in column [by], a row after the
 * group with "<value> Total" and SUBTOTAL([fn], …) of each column in [cols], and a Grand Total row after them all; each
 * group's rows then grouped (Data › Group), so they can be collapsed to the totals. Rows go in as Insert row puts them
 * (every reference following). A change to the file.
 */
internal object SheetSubtotal {
    /** Excel's functions for it: name (a string) and SUBTOTAL's number. */
    val FUNCTIONS = listOf(R.string.tables_sub_sum to 9, R.string.tables_count to 3, R.string.tables_average to 1, R.string.tables_max to 4, R.string.tables_min to 5, R.string.tables_sub_product to 6)

    /** The runs of data rows of [g] alike in column [by] (what the cells show, any case). */
    fun groups(sheet: Sheet, g: SheetRange, by: Int): List<IntRange> {
        val out = ArrayList<IntRange>(); var start = g.r1 + 1
        fun key(r: Int) = sheet[r, by]?.text?.trim()?.lowercase().orEmpty()
        for (r in g.r1 + 2..g.r2 + 1) if (r > g.r2 || key(r) != key(start)) { out += start..r - 1; start = r }
        return out.filter { !it.isEmpty() }
    }

    /** The cells written after the rows went in (row numbers as they are then): each group's total row, then the Grand Total. */
    fun cells(sheet: Sheet, groups: List<IntRange>, by: Int, cols: List<Int>, fn: Int): Map<Pair<Int, Int>, String> {
        val out = LinkedHashMap<Pair<Int, Int>, String>()
        fun col(c: Int) = Sheets.colName(c)
        groups.forEachIndexed { j, rows ->
            val total = rows.last + j + 1
            out[total to by] = (sheet[rows.first, by]?.text?.trim().orEmpty() + " Total").trim()
            cols.forEach { c -> out[total to c] = "=SUBTOTAL($fn,${col(c)}${rows.first + j + 1}:${col(c)}${rows.last + j + 1})" }
        }
        val first = groups.first().first; val last = groups.last().last + groups.size // the last total row
        out[last + 1 to by] = "Grand Total"
        cols.forEach { c -> out[last + 1 to c] = "=SUBTOTAL($fn,${col(c)}${first + 1}:${col(c)}${last + 1})" }
        return out
    }

    /** [source] into [target]: sheet [index]'s subtotals made, as above. */
    fun apply(source: File, target: File, book: Workbook, index: Int, g: SheetRange, by: Int, cols: List<Int>, fn: Int, decimal: Char, group: Char) {
        val sheet = book.sheet(index); val name = book.tabs[index].name; val tabs = book.tabs.map { it.name }
        val groups = groups(sheet, g, by); if (groups.isEmpty()) sheetProblem(R.string.tables_err_no_rows_total)
        val cells = cells(sheet, groups, by, cols, fn)
        var from = source; var n = 0
        fun step(op: (File, File) -> Unit) { val to = File(target.parentFile, "${target.nameWithoutExtension}.${n++}.part.${target.extension}"); op(from, to); if (from != source) from.delete(); from = to }
        fun insert(at: Int) = step { a, b -> SheetShift(name, true, at, 1).let { if (book.kind == "xlsx") it.xlsx(a, b, index, book.parts, tabs) else it.ods(a, b, index, tabs) } }
        // a row after each group, the lowest first (the rows above keep their numbers), then the Grand Total's
        groups.reversed().forEach { insert(it.last + 1) }
        insert(groups.last().last + groups.size + 1)
        // the totals' words and formulas, worked out
        step { a, b ->
            val wb = Sheets.read(a, decimal, group); val s = wb.sheet(index)
            SheetHistory().apply(wb, index, cells.entries.associate { (rc, text) -> Sheet.key(rc.first, rc.second) to SheetInput.cell(text, s[rc.first, rc.second], s[rc.first, rc.second]?.style ?: 0, "General", wb.format) })
            SheetSave.save(wb, a, b)
        }
        // each group's rows a level deeper, to collapse to the totals
        groups.forEachIndexed { j, rows -> step { a, b -> if (book.kind == "xlsx") SheetOutline.xlsx(a, b, book.parts.getValue("$index"), rows.first + j, rows.last + j, 1, null) else SheetOutline.ods(a, b, index, rows.first + j, rows.last + j, 1, null) } }
        from.copyTo(target, overwrite = true); if (from != source) from.delete()
    }
}
