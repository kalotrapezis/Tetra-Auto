package com.kalotrapezis.drive

import java.io.File

/**
 * References › Contents sheet: a first sheet, "Contents", listing every other sheet (hidden ones left out) as a link
 * to its A1 — for workbooks with many sheets. Made again when there is one already (its list as the workbook is now).
 * A change to the file: the sheet added (Words' [SheetBook]), its words written, each linked ([SheetLinks]), put first.
 */
internal object SheetContents {
    const val NAME = "Contents"

    fun apply(source: File, target: File, book: Workbook, decimal: Char, group: Char) {
        var from = source; var n = 0
        fun step(op: (File, File) -> Unit) { val to = File(target.parentFile, "${target.nameWithoutExtension}.${n++}.part.${target.extension}"); op(from, to); if (from != source) from.delete(); from = to }
        fun now() = Sheets.read(from, decimal, group)
        fun tabs(b: Workbook) = b.tabs.map { it.name }
        // an old Contents sheet goes; a new one after the last sheet
        book.tabs.indexOfFirst { it.name.equals(NAME, true) }.takeIf { it >= 0 }?.let { old -> step { a, b -> val w = now()
            if (w.kind == "xlsx") SheetBook.xlsx(a, b, w.parts, tabs(w), old, delete = true) else SheetBook.ods(a, b, tabs(w), old, delete = true) } }
        step { a, b -> val w = now(); val last = w.tabs.lastIndex
            if (w.kind == "xlsx") SheetBook.xlsx(a, b, w.parts, tabs(w), last, add = NAME) else SheetBook.ods(a, b, tabs(w), last, add = NAME) }
        val listed = now().let { w -> w.tabs.filter { !it.hidden && !it.name.equals(NAME, true) }.map { it.name } }
        val at = now().tabs.lastIndex
        // the title and each sheet's name
        step { a, b -> val w = now(); val s = w.sheet(at)
            SheetHistory().apply(w, at, (listOf(NAME) + listed).mapIndexed { r, t -> Sheet.key(r, 0) to SheetInput.cell("'$t", s[r, 0], 0, "General", w.format) }.toMap())
            SheetSave.save(w, a, b) }
        // each a link to its sheet
        listed.forEachIndexed { k, name -> step { a, b -> val w = now()
            val to = "#${SheetBook.quoted(name)}!A1"
            if (w.kind == "xlsx") SheetLinks.xlsx(a, b, w.parts.getValue("$at"), k + 1, 0, to) else SheetLinks.ods(a, b, at, k + 1, 0, to) } }
        // first, where a reader looks
        step { a, b -> val w = now(); if (w.kind == "xlsx") SheetBook.xlsx(a, b, w.parts, tabs(w), at, to = 0) else SheetBook.ods(a, b, tabs(w), at, to = 0) }
        from.copyTo(target, overwrite = true); if (from != source) from.delete()
    }
}
