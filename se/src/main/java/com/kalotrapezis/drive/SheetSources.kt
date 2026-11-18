package com.kalotrapezis.drive

import java.io.File

/**
 * References › Sources sheet: where the data came from, each source typed in Words' source dialog and written as APA 7
 * writes it (Words' [CiteStyle], its italics kept as the cell's rich text), under "References", in APA's order (by
 * the words they start with). The sheet ("Sources", last) made the first time, its column wide and its cells wrapping.
 * A change to the file. shortcut: a source is kept as its reference's words only (not Words' fields): to change one,
 * change its cell; upgrade by keeping Word's own sources part beside the sheet.
 */
internal object SheetSources {
    const val NAME = "Sources"

    fun add(source: File, target: File, book: Workbook, entry: List<DocRun>, decimal: Char, group: Char) {
        var from = source; var n = 0
        fun step(op: (File, File) -> Unit) { val to = File(target.parentFile, "${target.nameWithoutExtension}.${n++}.part.${target.extension}"); op(from, to); if (from != source) from.delete(); from = to }
        fun now() = Sheets.read(from, decimal, group)
        if (book.tabs.none { it.name.equals(NAME, true) }) {
            step { a, b -> val w = now(); val tabs = w.tabs.map { it.name }
                if (w.kind == "xlsx") SheetBook.xlsx(a, b, w.parts, tabs, w.tabs.lastIndex, add = NAME) else SheetBook.ods(a, b, tabs, w.tabs.lastIndex, add = NAME) }
            // a column wide enough for a reference (about 16 cm), as a page's text is
            step { a, b -> val w = now(); val i = w.tabs.indexOfFirst { it.name.equals(NAME, true) }
                if (w.kind == "xlsx") SheetSizes.xlsx(a, b, w.parts.getValue("$i"), true, 0, 0, 600f, null) else SheetSizes.ods(a, b, i, true, 0, 0, 600f, null) }
        }
        step { a, b ->
            val w = now(); val i = w.tabs.indexOfFirst { it.name.equals(NAME, true) }; val s = w.sheet(i)
            // the references there (each a cell of column A under the heading), the new one among them in APA's order
            val old = (1 until maxOf(s.rows, 1)).mapNotNull { r -> s[r, 0]?.takeIf { it.text.isNotBlank() } }
            val wrap = w.styleFor(SheetStyle(wrap = true, valign = SheetVAlign.Top))
            val fresh = SheetCell(entry.joinToString("") { it.text }, SheetKind.Text, style = wrap, runs = entry)
            fun key(c: SheetCell) = c.text.trim().trimStart('“', '"', '\'').lowercase()
            val all = (old.map { it.copy(style = wrap) } + fresh).sortedBy(::key)
            val changes = LinkedHashMap<Long, SheetCell>()
            changes[Sheet.key(0, 0)] = SheetCell("References", SheetKind.Text, style = w.styleFor(SheetStyle(bold = true, align = SheetAlign.Center)))
            all.forEachIndexed { k, c -> changes[Sheet.key(k + 1, 0)] = c }
            SheetHistory().apply(w, i, changes)
            SheetSave.save(w, a, b)
        }
        from.copyTo(target, overwrite = true); if (from != source) from.delete()
    }
}
