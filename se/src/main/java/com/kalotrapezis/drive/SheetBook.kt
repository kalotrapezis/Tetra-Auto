package com.kalotrapezis.drive

import java.io.File
import java.util.zip.ZipFile

/**
 * A workbook's sheets (asked 16 Oct with editing): a new empty spreadsheet, and sheets added, renamed, moved and
 * deleted — every formula and name that names a sheet following it (a deleted one's references become #REF!).
 */
internal object SheetBook {
    /** A new, empty .xlsx: one sheet ([sheet], Excel's "Sheet1" or the language's own), Calibri 11, as Excel makes one. */
    fun empty(sheet: String = "Sheet1"): ByteArray = OfficeFormats.zip(linkedMapOf(
        "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""".toByteArray(),
        "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""".toByteArray(),
        "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><bookViews><workbookView activeTab="0"/></bookViews><sheets><sheet name="$sheet" sheetId="1" r:id="rId1"/></sheets><calcPr calcId="191029"/></workbook>""".toByteArray(),
        "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""".toByteArray(),
        "xl/styles.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="1"><font><sz val="11"/><color theme="1"/><name val="Calibri"/><family val="2"/><scheme val="minor"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""".toByteArray(),
        "xl/worksheets/sheet1.xml" to SHEET.toByteArray(),
    ))

    private const val SHEET = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><dimension ref="A1"/><sheetViews><sheetView workbookViewId="0"/></sheetViews><sheetFormatPr defaultRowHeight="15"/><sheetData/><pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/></worksheet>"""

    /** A sheet's name as a formula writes it: quoted when it has more than letters, digits and _. */
    fun quoted(name: String) = if (Regex("""^[\p{L}_][\p{L}\p{N}_.]*$""").matches(name) && !Regex("""^[A-Za-z]{1,3}\d+$""").matches(name)) name else "'" + name.replace("'", "''") + "'"

    /** A name a sheet can have: not empty, at most 31 letters, none of : \ / ? * [ ], not another sheet's. */
    fun valid(name: String, others: List<String>): Int? = when { // what is wrong, as a string
        name.isBlank() -> R.string.tables_sheet_needs_name
        name.length > 31 -> R.string.tables_sheet_name_long
        name.any { it in ":\\/?*[]" } -> R.string.tables_sheet_name_letters
        others.any { it.equals(name, ignoreCase = true) } -> R.string.tables_sheet_name_taken
        else -> null
    }

    /** Excel formula text with references to sheet [old] renamed to [new] (null: deleted, its references #REF!). */
    fun renamed(f: String, old: String, new: String?): String {
        val ref = Regex("""('(?:[^']|'')+'|[\p{L}_][\p{L}\p{N}_.]*)!(\$?[A-Za-z]{1,3}\$?\d+(?::\$?[A-Za-z]{1,3}\$?\d+)?|\$?[A-Za-z]{1,3}:\$?[A-Za-z]{1,3}|\$?\d+:\$?\d+)""")
        return f.split('"').mapIndexed { i, part ->
            if (i % 2 == 1) part else ref.replace(part) { m ->
                val name = m.groupValues[1].removeSurrounding("'").replace("''", "'")
                if (!name.equals(old, ignoreCase = true)) m.value else if (new == null) "#REF!" else quoted(new) + "!" + m.groupValues[2]
            }
        }.joinToString("\"")
    }

    private fun unesc(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** Every formula in every part of an .xlsx (sheets, names, charts) passed through [f]. */
    private fun formulas(zip: ZipFile, out: HashMap<String, ByteArray?>, f: (String) -> String) {
        for (e in zip.entries().asSequence()) {
            if (!e.name.endsWith(".xml") || !(e.name.contains("worksheets/") || e.name.contains("charts/") || e.name.endsWith("workbook.xml"))) continue
            val x = (out[e.name]?.let { String(it) } ?: String(zip.getInputStream(e).readBytes()))
            val y = Regex("""(<((?:\w+:)?(?:f|formula|formula1|formula2|definedName))\b[^>]*>)([^<]*)(</\2>)""").replace(x) { m -> m.groupValues[1] + OfficeFormats.esc(f(unesc(m.groupValues[3]))) + m.groupValues[4] }
            if (y != x || e.name in out) out[e.name] = y.toByteArray()
        }
    }

    /**
     * An .xlsx with its sheets changed: [add] a new sheet called that (after sheet [index]), [rename] sheet [index],
     * [delete] it, or [move] it to place [to]. Names kept per sheet (localSheetId) follow their sheet.
     */
    fun xlsx(source: File, target: File, parts: Map<String, String>, tabs: List<String>, index: Int, add: String? = null, rename: String? = null, delete: Boolean = false, to: Int? = null, copy: Boolean = false) {
        val zip = ZipFile(source)
        val out = HashMap<String, ByteArray?>()
        fun read(p: String) = out[p]?.let { String(it) } ?: zip.getEntry(p)?.let { e -> String(zip.getInputStream(e).readBytes()) } ?: sheetProblem(R.string.tables_err_no_part, p)
        val wb = parts.getValue("workbook")
        val rels = wb.substringBeforeLast('/', "").let { if (it.isEmpty()) "_rels/" else "$it/_rels/" } + wb.substringAfterLast('/') + ".rels"
        var x = read(wb)
        val sheets = Regex("""<sheet\b[^>]*/>""").findAll(x).map { it.value }.toMutableList()
        // where each sheet goes (old place → new place, null: gone), for names kept per sheet and the open tab
        val order = tabs.indices.toMutableList<Int?>()
        when {
            add != null -> {
                val ids = Regex("""sheetId="(\d+)"""").findAll(x).map { it.groupValues[1].toInt() }
                val sheetId = (ids.maxOrNull() ?: 0) + 1
                var relsX = read(rels)
                val rid = generateSequence(1) { it + 1 }.map { "rId$it" }.first { "Id=\"$it\"" !in relsX }
                val dir = parts.getValue("0").substringBeforeLast('/')
                val n = generateSequence(1) { it + 1 }.first { zip.getEntry("$dir/sheet$it.xml") == null }
                val path = "$dir/sheet$n.xml"
                // a copy (Move or Copy › Create a copy): the sheet's cells, looks and links (its own pictures, notes and tables not:
                // their parts are its alone); else an empty sheet
                // shortcut: a copy leaves pictures, charts, notes and tables behind; upgrade by copying those parts with new names
                out[path] = if (!copy) SHEET.toByteArray() else {
                    val from = parts.getValue("$index")
                    val fromRels = from.substringBeforeLast('/') + "/_rels/" + from.substringAfterLast('/') + ".rels"
                    zip.getEntry(fromRels)?.let { String(zip.getInputStream(it).readBytes()) }?.let { r ->
                        val links = Regex("""<Relationship\b[^>]*/hyperlink"[^>]*/>""").findAll(r).joinToString("") { it.value }
                        if (links.isNotEmpty()) out["$dir/_rels/sheet$n.xml.rels"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$links</Relationships>""".toByteArray() }
                    read(from).replace(Regex("""\stabSelected="1""""), "").replace(Regex("""<(drawing|legacyDrawing|legacyDrawingHF|picture)\b[^>]*/>"""), "")
                        .replace(Regex("""<(tableParts|oleObjects|controls)\b[\s\S]*?</\1>"""), "").replace(Regex("""<tableParts\b[^>]*/>"""), "").toByteArray()
                }
                relsX = relsX.replace("</Relationships>", """<Relationship Id="$rid" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="${path.removePrefix(wb.substringBeforeLast('/', "") + "/")}"/></Relationships>""")
                out[rels] = relsX.toByteArray()
                out["[Content_Types].xml"] = read("[Content_Types].xml").replace("</Types>", """<Override PartName="/$path" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""").toByteArray()
                sheets.add(index + 1, """<sheet name="${OfficeFormats.esc(add)}" sheetId="$sheetId" r:id="$rid"/>""")
                order.indices.forEach { i -> if (i > index) order[i] = i + 1 }
            }
            rename != null -> {
                sheets[index] = sheets[index].replace(Regex("""name="[^"]*""""), "name=\"${OfficeFormats.esc(rename)}\"")
                formulas(zip, out) { renamed(it, tabs[index], rename) }
                x = String(out[wb] ?: x.toByteArray())
            }
            delete -> {
                val gone = sheets.removeAt(index)
                val rid = Regex("""r:id="([^"]*)"""").find(gone)?.groupValues?.get(1)
                out[rels] = read(rels).replace(Regex("""<Relationship\b[^>]*Id="$rid"[^>]*/>"""), "").toByteArray()
                val path = parts.getValue("$index")
                out[path] = null
                out["[Content_Types].xml"] = read("[Content_Types].xml").replace(Regex("""<Override PartName="/${Regex.escape(path)}"[^>]*/>"""), "").toByteArray()
                formulas(zip, out) { renamed(it, tabs[index], null) }
                x = String(out[wb] ?: x.toByteArray())
                order[index] = null; order.indices.forEach { i -> if (i > index) order[i] = i - 1 }
            }
            to != null -> {
                val s = sheets.removeAt(index); sheets.add(to, s)
                val moved = tabs.indices.toMutableList(); moved.add(to, moved.removeAt(index))
                moved.forEachIndexed { place, old -> order[old] = place }
            }
        }
        // the sheets in their new order; names kept per sheet moved with it (a deleted sheet's go)
        x = x.replace(Regex("""<sheets>.*?</sheets>""", RegexOption.DOT_MATCHES_ALL), "<sheets>" + sheets.joinToString("") + "</sheets>")
        x = Regex("""<definedName\b([^>]*)>([^<]*)</definedName>""").replace(x) { m ->
            val local = Regex("""localSheetId="(\d+)"""").find(m.groupValues[1])?.groupValues?.get(1)?.toInt() ?: return@replace m.value
            val now = order.getOrNull(local) ?: return@replace ""
            m.value.replaceFirst("localSheetId=\"$local\"", "localSheetId=\"$now\"")
        }.replace("<definedNames></definedNames>", "")
        x = x.replace(Regex("""activeTab="\d+""""), "activeTab=\"${(if (add != null) index + 1 else if (to != null) to else if (delete) maxOf(0, index - 1) else index).coerceAtMost(sheets.lastIndex)}\"")
            .replace(Regex("""firstSheet="\d+""""), "firstSheet=\"0\"")
        out[wb] = x.toByteArray()
        // only one sheet may be the one Excel opens on
        if (to != null || delete) parts.filterKeys { it.toIntOrNull() != null }.values.forEach { p ->
            if (out.containsKey(p) && out[p] == null) return@forEach
            val s = read(p); val y = s.replace(Regex("""\stabSelected="1""""), "")
            if (y != s) out[p] = y.toByteArray()
        }
        SheetSave.rezip(zip, target, out, stored = null)
        zip.close()
    }

    /** The same for an .ods: its tables added, renamed, deleted or moved, LibreOffice's references following. */
    fun ods(source: File, target: File, tabs: List<String>, index: Int, add: String? = null, rename: String? = null, delete: Boolean = false, to: Int? = null, copy: Boolean = false) {
        val zip = ZipFile(source)
        val doc = OfficeFormats.parse(zip.getInputStream(zip.getEntry("content.xml")).use { it.readBytes() })
        val T = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
        val tables = doc.getElementsByTagNameNS(T, "table").let { l -> (0 until l.length).map { l.item(it) as org.w3c.dom.Element } }
            .filter { t -> generateSequence(t.parentNode) { it.parentNode }.none { it is org.w3c.dom.Element && it.localName == "table" } }
        val table = tables[index]
        fun refs(old: String, new: String?) {
            val all = doc.getElementsByTagName("*")
            val q = Regex("""(?<![\p{L}\p{N}_])\$?'?${Regex.escape(old)}'?\.""")
            for (k in 0 until all.length) {
                val a = (all.item(k) as org.w3c.dom.Element).attributes
                for (j in 0 until a.length) {
                    val at = a.item(j) as org.w3c.dom.Attr
                    if (at.localName !in setOf("formula", "condition", "value", "target-range-address", "cell-range-address", "base-cell-address", "end-cell-address")) continue
                    if (!at.value.contains(old)) continue
                    at.value = q.replace(at.value) { m -> if (new == null) "#REF!." else (if (m.value.startsWith("$")) "$" else "") + (if (Regex("""^[\p{L}_][\p{L}\p{N}_]*$""").matches(new)) new else "'" + new.replace("'", "''") + "'") + "." }
                }
            }
        }
        when {
            add != null -> {
                // a copy: the whole table (its pictures and notes with it); else an empty one
                val t = if (copy) table.cloneNode(true) as org.w3c.dom.Element else doc.createElementNS(T, "table:table").apply {
                    appendChild(doc.createElementNS(T, "table:table-column"))
                    appendChild(doc.createElementNS(T, "table:table-row").apply { appendChild(doc.createElementNS(T, "table:table-cell")) }) }
                t.setAttributeNS(T, "table:name", add)
                table.parentNode.insertBefore(t, table.nextSibling)
            }
            rename != null -> { refs(tabs[index], rename); table.setAttributeNS(T, "table:name", rename) }
            delete -> { refs(tabs[index], null); table.parentNode.removeChild(table) }
            to != null -> {
                val parent = table.parentNode
                parent.removeChild(table)
                val rest = tables.filter { it !== table }
                if (to >= rest.size) parent.insertBefore(table, rest.last().nextSibling) else parent.insertBefore(table, rest[to])
            }
        }
        val out = HashMap<String, ByteArray?>()
        out["content.xml"] = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + OfficeFormats.xml(doc.documentElement)).toByteArray(Charsets.UTF_8)
        // meta.xml counts the sheets (Tetra reads the names up to that count)
        zip.getEntry("meta.xml")?.let { e ->
            val count = tables.size + (if (add != null) 1 else 0) - (if (delete) 1 else 0)
            out["meta.xml"] = String(zip.getInputStream(e).readBytes()).replace(Regex("""table-count="\d+""""), "table-count=\"$count\"").toByteArray()
        }
        SheetSave.rezip(zip, target, out, stored = "mimetype")
        zip.close()
    }
}
