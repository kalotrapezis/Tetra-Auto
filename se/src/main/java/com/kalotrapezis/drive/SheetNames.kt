package com.kalotrapezis.drive

import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile

/**
 * Formulas › Name Manager (Excel's; LibreOffice's Sheet › Named Ranges): the workbook's names, what each stands for in
 * Excel's form ("'Rows'!$B$3:$B$12"), a name defined for cells or taken away. Excel's definedNames, LibreOffice's
 * table:named-range and named-expression (read too, so formulas on an .ods can use them). A change to the file.
 */
internal object SheetNames {
    /** Excel's rule for a name: a letter, _ or \ first, then letters, digits, _ . \; not a cell's name; at most 255. */
    fun valid(name: String, taken: Collection<String>): Int? = when { // what is wrong, as a string
        name.isBlank() -> R.string.tables_name_give
        name.length > 255 -> R.string.tables_name_too_long
        !Regex("""^[\p{L}_\\][\p{L}\p{N}_.\\]*$""").matches(name) -> R.string.tables_name_letter_first
        Regex("""^[A-Za-z]{1,3}\d+$""").matches(name) || name.equals("R", true) || name.equals("C", true) -> R.string.tables_name_is_cell
        taken.any { it.equals(name, true) } -> R.string.tables_name_taken_already
        else -> null
    }

    /** Cells [g] of sheet [sheet] as a name stands for them: "'Sheet'!$A$1:$B$5". */
    fun refersTo(sheet: String, g: SheetRange) = SheetBook.quoted(sheet) + "!" + "$" + Sheets.colName(g.c1) + "$" + (g.r1 + 1) +
        (if (g.r2 > g.r1 || g.c2 > g.c1) ":$" + Sheets.colName(g.c2) + "$" + (g.r2 + 1) else "")

    /** LibreOffice's named ranges and expressions in [content] (its content.xml), by name in small letters, in Excel's form. */
    fun ods(content: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun unesc(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
        for (m in Regex("""<table:named-(range|expression)\b([^>]*)/?>""").findAll(content)) {
            val a = Regex("""table:([\w-]+)="([^"]*)"""").findAll(m.groupValues[2]).associate { it.groupValues[1] to unesc(it.groupValues[2]) }
            val name = a["name"] ?: continue
            out[name.lowercase()] = if (m.groupValues[1] == "range") a["cell-range-address"]?.let(::fromOds) ?: continue
                else a["expression"]?.let { Sheets.odfFormula(it).removePrefix("=") } ?: continue
        }
        return out
    }

    /** LibreOffice's "$Sheet1.$A$1:.$B$5" (or "$'My x'.$A$1") as Excel's "'Sheet1'!$A$1:$B$5". */
    fun fromOds(address: String): String {
        val first = address.split(':')[0]
        val sheet = first.substringBeforeLast('.', "").removePrefix("$").trim('\'').replace("''", "'")
        val cells = address.split(':').joinToString(":") { it.substringAfterLast('.') }
        return (if (sheet.isNotEmpty()) SheetBook.quoted(sheet) + "!" else "") + cells
    }

    /** Excel: name [name] defined as [refersTo] (null: taken away) in the workbook part [workbook]. */
    fun xlsx(source: File, target: File, workbook: String, name: String, refersTo: String?) {
        ZipFile(source).use { zip ->
            var x = zip.getEntry(workbook)?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } } ?: sheetProblem(R.string.tables_err_not_workbook)
            // the workbook's own name (not a sheet's: localSheetId) of that name goes
            x = Regex("""<definedName\b([^>]*)>[^<]*</definedName>""").replace(x) { m ->
                val n = Regex("""\bname="([^"]*)"""").find(m.groupValues[1])?.groupValues?.get(1)
                if (n != null && n.equals(OfficeFormats.esc(name), true) && "localSheetId" !in m.groupValues[1]) "" else m.value }
            if (refersTo != null) { val d = """<definedName name="${OfficeFormats.esc(name)}">${OfficeFormats.esc(refersTo)}</definedName>"""
                x = if ("</definedNames>" in x) x.replace("</definedNames>", "$d</definedNames>") else x.replace("</sheets>", "</sheets><definedNames>$d</definedNames>") }
            x = x.replace(Regex("""<definedNames>\s*</definedNames>"""), "")
            SheetSave.rezip(zip, target, mapOf(workbook to x.toByteArray()), null)
        }
    }

    /** LibreOffice: name [name] as a named range of [sheet]'s cells [g] (null: taken away). */
    fun ods(source: File, target: File, name: String, sheet: String?, g: SheetRange?) {
        ZipFile(source).use { zip ->
            val doc = OfficeFormats.parse(zip.getInputStream(zip.getEntry("content.xml") ?: sheetProblem(R.string.tables_err_not_spreadsheet)).use { it.readBytes() })
            val T = SheetMerge.T
            val tables = doc.getElementsByTagNameNS(T, "table").let { l -> (0 until l.length).map { l.item(it) as Element } }
                .filter { t -> generateSequence(t.parentNode) { it.parentNode }.none { it is Element && it.localName == "table" } }
            val book = tables.first().parentNode as Element
            val all = SheetMerge.kids(book, "named-expressions").firstOrNull()
            all?.let { a -> SheetMerge.kids(a, "named-range", "named-expression").filter { it.getAttributeNS(T, "name").equals(name, true) }.forEach { a.removeChild(it) } }
            if (sheet != null && g != null) {
                val names = all ?: doc.createElementNS(T, "table:named-expressions").also { book.insertBefore(it, tables.last().nextSibling) }
                val q = "$" + (if (Regex("""^\w+$""").matches(sheet)) sheet else "'" + sheet.replace("'", "''") + "'")
                fun cell(r: Int, c: Int) = "$" + Sheets.colName(c) + "$" + (r + 1)
                names.appendChild(doc.createElementNS(T, "table:named-range").also { e ->
                    e.setAttributeNS(T, "table:name", name); e.setAttributeNS(T, "table:base-cell-address", "$q.${cell(g.r1, g.c1)}")
                    e.setAttributeNS(T, "table:cell-range-address", "$q.${cell(g.r1, g.c1)}" + (if (g.r2 > g.r1 || g.c2 > g.c1) ":.${cell(g.r2, g.c2)}" else "")) })
            }
            SheetSave.rezip(zip, target, mapOf("content.xml" to SlidesEdit.domXml(doc)), "mimetype")
        }
    }
}
