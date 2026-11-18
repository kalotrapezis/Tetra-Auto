package com.kalotrapezis.drive

import java.io.File
import java.util.zip.ZipFile

/**
 * Inserting and deleting rows and columns (asked 16 Oct): the sheet's cells move, and so does every reference to them —
 * formulas on every sheet, merged cells, rules, validations, links, names, charts, pictures, comments, tables — as
 * Excel moves them. A reference to deleted cells becomes #REF!, a range losing some of its cells shrinks, one with
 * cells inserted inside it grows.
 */
internal class SheetShift(
    /** The sheet whose rows or columns change. */
    val sheet: String,
    val rows: Boolean,
    /** The first row or column (from 0) inserted or deleted. */
    val at: Int,
    /** How many: more than 0 inserted, less than 0 deleted. */
    val count: Int,
    /**
     * Only the cells in these columns (rows moving) or rows (columns moving) move — Excel's Insert ▸ Shift cells down or right,
     * Delete ▸ Shift cells up or left; null: whole rows or columns. A range not wholly in them stays as it is.
     */
    val within: IntRange? = null,
) {
    private val deleted get() = if (count < 0) at until at - count else IntRange.EMPTY

    /** Where index [i] goes; null when it is deleted. */
    fun index(i: Int): Int? = when {
        count > 0 -> if (i >= at) i + count else i
        i < at -> i
        i in deleted -> null
        else -> i + count
    }

    /** Where a span [a]..[b] goes (it grows round inserted ones, shrinks round deleted ones); null when all of it goes. */
    fun span(a: Int, b: Int): IntRange? {
        if (count > 0) return (if (a >= at) a + count else a)..(if (b >= at) b + count else b)
        val na = if (a in deleted) at else index(a)!!
        val nb = if (b in deleted) at - 1 else index(b)!!
        return if (na > nb) null else na..nb
    }

    fun range(g: SheetRange): SheetRange? {
        // shortcut: a range partly in the moving cells' columns (rows) stays; Excel refuses some of those shifts outright
        if (within != null && !(if (rows) g.c1 >= within.first && g.c2 <= within.last else g.r1 >= within.first && g.r2 <= within.last)) return g
        return if (rows) span(g.r1, g.r2)?.let { SheetRange(it.first, g.c1, it.last, g.c2) } else span(g.c1, g.c2)?.let { SheetRange(g.r1, it.first, g.r2, it.last) }
    }

    fun cell(r: Int, c: Int): Pair<Int, Int>? = when {
        within != null && (if (rows) c !in within else r !in within) -> r to c
        rows -> index(r)?.let { it to c }
        else -> index(c)?.let { r to it }
    }

    private fun same(name: String?, own: String) = (name ?: own).trim('\'').replace("''", "'").equals(sheet, ignoreCase = true)

    /** "B2", "$B$2:$C9", "A:A", "1:3" moved (a whole column is not moved by rows, nor a row by columns); null: gone. */
    fun ref(ref: String): String? {
        val parts = ref.split(':')
        if (parts.size > 2) return ref
        val a = cellParts(parts[0]) ?: return ref
        val b = if (parts.size == 2) cellParts(parts[1]) ?: return ref else null
        val bb = b ?: a
        if (within != null && (a.row == null || bb.row == null || a.col == null || bb.col == null)) return ref // whole rows and columns stay
        if (a.row == null && bb.row == null && a.col != null && bb.col != null) { // whole columns: A:C
            if (rows) return ref
            val s = span(a.col, bb.col) ?: return null
            return a.text(s.first, null) + ":" + bb.text(s.last, null)
        }
        if (a.col == null && bb.col == null && a.row != null && bb.row != null) { // whole rows: 1:3
            if (!rows) return ref
            val s = span(a.row, bb.row) ?: return null
            return a.text(null, s.first) + ":" + bb.text(null, s.last)
        }
        if (a.col == null || a.row == null || bb.col == null || bb.row == null) return ref
        if (b == null) return cell(a.row, a.col)?.let { (r, c) -> a.text(c, r) }
        val g = range(SheetRange(a.row, a.col, bb.row, bb.col)) ?: return null
        return a.text(g.c1, g.r1) + ":" + bb.text(g.c2, g.r2)
    }

    /** A cell reference's parts: column and row (null for a whole row or column), and whether each was absolute. */
    private class Parts(val col: Int?, val colAbs: Boolean, val row: Int?, val rowAbs: Boolean) {
        fun text(c: Int?, r: Int?) = (if (c != null) (if (colAbs) "$" else "") + Sheets.colName(c) else "") + (if (r != null) (if (rowAbs) "$" else "") + (r + 1) else "")
    }
    private fun cellParts(s: String): Parts? {
        val m = Regex("""^(\$?)([A-Za-z]{1,3})?(\$?)(\d+)?$""").matchEntire(s.trim()) ?: return null
        val (ca, col, ra, row) = m.destructured
        if (col.isEmpty() && row.isEmpty()) return null
        return Parts(col.takeIf { it.isNotEmpty() }?.uppercase()?.fold(0) { a, ch -> a * 26 + (ch - 'A' + 1) }?.minus(1), ca.isNotEmpty(),
            row.takeIf { it.isNotEmpty() }?.toInt()?.minus(1), ra.isNotEmpty())
    }

    /** A space-separated list of ranges (Excel's sqref) moved; the gone ones left out (null when none is left). */
    fun refs(list: String): String? = list.split(' ').filter { it.isNotEmpty() }.mapNotNull(::ref).takeIf { it.isNotEmpty() }?.joinToString(" ")

    private val EXCEL_REF = Regex("""(?<![\p{L}\p{N}_.$'])(?:('(?:[^']|'')+'|[\p{L}_][\p{L}\p{N}_.]*)!)?(\$?[A-Za-z]{1,3}\$?\d+(?::\$?[A-Za-z]{1,3}\$?\d+)?|\$?[A-Za-z]{1,3}:\$?[A-Za-z]{1,3}|\$?\d+:\$?\d+)(?![\p{L}\p{N}_(!])""")

    /** An Excel formula on sheet [own] with its references to [sheet] moved (words in quotes left alone). */
    fun formula(f: String, own: String): String = f.split('"').mapIndexed { i, part ->
        if (i % 2 == 1) part else EXCEL_REF.replace(part) { m ->
            val name = m.groupValues[1].takeIf { it.isNotEmpty() }
            if (!same(name, own)) return@replace m.value
            val moved = ref(m.groupValues[2])
            (name?.let { "$it!" } ?: "") + (moved ?: "#REF!")
        }
    }.joinToString("\"")

    private val ODF_REF = Regex("""\[(\$?'(?:[^']|'')+'|\$?[^.\[\]':]*)?\.(\$?[A-Za-z]{1,3}\$?\d+)(?::(\$?'(?:[^']|'')+'|\$?[^.\[\]':]*)?\.(\$?[A-Za-z]{1,3}\$?\d+))?]""")

    /** A LibreOffice formula ("of:=SUM([.A1:.B2];[$Other.C3])") on sheet [own] with its references to [sheet] moved. */
    fun odfFormula(f: String, own: String): String = f.split('"').mapIndexed { i, part ->
        if (i % 2 == 1) part else ODF_REF.replace(part) { m ->
            val name = m.groupValues[1].removePrefix("$").takeIf { it.isNotEmpty() }
            if (!same(name, own)) return@replace m.value
            val ref = m.groupValues[2] + (m.groupValues[4].takeIf { it.isNotEmpty() }?.let { ":$it" } ?: "")
            val moved = ref(ref) ?: return@replace "[.#REF!]"
            val (a, b) = moved.split(':').let { it[0] to it.getOrNull(1) }
            "[" + m.groupValues[1] + "." + a + (b?.let { ":" + m.groupValues[3] + "." + it } ?: "") + "]"
        }
    }.joinToString("\"")

    /** LibreOffice's addresses ("Sheet1.A1:Sheet1.B9 Sheet1.C3") on [sheet] moved; null when none is left. */
    fun odfAddresses(list: String): String? = list.split(' ').filter { it.isNotEmpty() }.mapNotNull { a ->
        val parts = a.split(':')
        val name = parts[0].substringBeforeLast('.', "").removePrefix("$")
        if (!same(name.ifEmpty { null }, sheet)) return@mapNotNull a
        val cells = parts.map { it.substringAfterLast('.') }
        val moved = ref(cells.joinToString(":")) ?: return@mapNotNull null
        moved.split(':').mapIndexed { k, cell -> parts[k].substringBeforeLast('.', "").let { if (it.isEmpty()) cell else "$it.$cell" } }.joinToString(":")
    }.takeIf { it.isNotEmpty() }?.joinToString(" ")

    // --- .xlsx ---

    /**
     * [source] (an .xlsx) into [target] with the rows or columns of sheet number [index] inserted or deleted: its cells,
     * rows and column widths, every formula that reads it (on any sheet, in names, charts), and every part that names
     * its cells.
     */
    fun xlsx(source: File, target: File, index: Int, parts: Map<String, String>, tabs: List<String>) {
        val zip = ZipFile(source)
        fun read(p: String) = zip.getEntry(p)?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }
        fun relsOf(p: String) = p.substringBeforeLast('/', "").let { d -> (if (d.isEmpty()) "" else "$d/") + "_rels/" + p.substringAfterLast('/') + ".rels" }
        fun targets(p: String): Map<String, String> = read(relsOf(p))?.let { x ->
            Regex("""<Relationship\b[^>]*>""").findAll(x).associate { m ->
                val id = Regex("""Id="([^"]*)"""").find(m.value)?.groupValues?.get(1).orEmpty()
                val t = Regex("""Target="([^"]*)"""").find(m.value)?.groupValues?.get(1).orEmpty()
                id to if (t.startsWith("/")) t.removePrefix("/") else (p.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() } + t.split('/')).fold(ArrayList<String>()) { acc, s -> if (s == "..") acc.removeLastOrNull() else if (s != ".") acc += s; acc }.joinToString("/")
            }
        }.orEmpty()
        val out = HashMap<String, ByteArray?>()
        val own = parts.getValue("$index")
        for ((k, path) in parts) {
            val i = k.toIntOrNull() ?: continue
            val xml = read(path) ?: continue
            val name = tabs[i]
            out[path] = (if (i == index) targetSheet(xml) else otherSheet(xml, name)).toByteArray(Charsets.UTF_8)
        }
        // names in the workbook (print areas, ranges)
        parts["workbook"]?.let { wb -> read(wb)?.let { x ->
            out[wb] = Regex("""(<definedName\b([^>]*)>)([^<]*)(</definedName>)""").replace(x) { m ->
                val local = Regex("""localSheetId="(\d+)"""").find(m.groupValues[2])?.groupValues?.get(1)?.toIntOrNull()
                m.groupValues[1] + OfficeFormats.esc(formula(unesc(m.groupValues[3]), local?.let { tabs.getOrNull(it) } ?: "\u0000")) + m.groupValues[4]
            }.toByteArray(Charsets.UTF_8)
        } }
        // the sheet's own parts: drawings (pictures, charts' places), comments, tables, notes' boxes
        val rels = targets(own)
        for ((_, p) in rels) {
            val x = read(p) ?: continue
            when {
                p.contains("drawings/drawing") && p.endsWith(".xml") -> out[p] = anchors(x).toByteArray(Charsets.UTF_8)
                p.substringAfterLast('/').let { n -> n.startsWith("comment") && n.endsWith(".xml") } -> out[p] = Regex("""<comment\b[^>]*?ref="([^"]*)"[^>]*>.*?</comment>""", RegexOption.DOT_MATCHES_ALL).replace(x) { m ->
                    ref(m.groupValues[1])?.let { m.value.replaceFirst("ref=\"${m.groupValues[1]}\"", "ref=\"$it\"") } ?: ""
                }.toByteArray(Charsets.UTF_8)
                p.substringAfterLast('/').startsWith("threadedComment") -> out[p] = attrs(x).toByteArray(Charsets.UTF_8)
                p.contains("tables/") -> out[p] = table(x).toByteArray(Charsets.UTF_8)
                p.endsWith(".vml") -> out[p] = vml(x).toByteArray(Charsets.UTF_8)
            }
        }
        // charts anywhere that read the sheet
        zip.entries().asSequence().filter { it.name.contains("charts/chart") && it.name.endsWith(".xml") }.forEach { e ->
            val x = read(e.name)!!
            out[e.name] = Regex("""(<(?:\w+:)?f>)([^<]*)(</(?:\w+:)?f>)""").replace(x) { m -> m.groupValues[1] + OfficeFormats.esc(formula(unesc(m.groupValues[2]), "\u0000")) + m.groupValues[3] }.toByteArray(Charsets.UTF_8)
        }
        SheetSave.rezip(zip, target, out, stored = null)
        zip.close()
    }

    private fun unesc(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** Formulas in a part: <f>, <formula>, <formula1/2>, <xm:f> — moved as formulas of sheet [own]. */
    private fun formulas(x: String, own: String) = Regex("""(<(f|formula|formula1|formula2|xm:f)\b[^>]*>)([^<]*)(</\2>)""").replace(x) { m ->
        m.groupValues[1] + OfficeFormats.esc(formula(unesc(m.groupValues[3]), own)) + m.groupValues[4]
    }

    /** Attributes that name the sheet's cells: ref, sqref, activeCell, topLeftCell (a gone one left as A1). */
    private fun attrs(x: String) = Regex("""\s(ref|sqref|activeCell|topLeftCell)="([^"]*)"""").replace(x) { m ->
        " ${m.groupValues[1]}=\"${refs(m.groupValues[2]) ?: "A1"}\""
    }

    private fun otherSheet(xml: String, name: String) = formulas(xml, name)

    /** The sheet itself: its rows and cells renamed (deleted ones gone), widths, merges, rules, links, the rest. */
    private fun targetSheet(xml0: String): String {
        var xml = xml0
        // cells and rows (each <c r=…> and <row r=…>)
        val open = xml.indexOf("<sheetData")
        val close = xml.indexOf("</sheetData>")
        if (open >= 0 && close > open) {
            val head = xml.substring(0, open); val data = xml.substring(open, close); val tail = xml.substring(close)
            val rowsOut = StringBuilder()
            val rowRe = Regex("""<row\b[^>]*?(?:/>|>.*?</row>)""", RegexOption.DOT_MATCHES_ALL)
            val dataOpen = data.substring(0, data.indexOf('>') + 1)
            var auto = 0
            for (m in rowRe.findAll(data)) {
                val tag = m.value.substring(0, m.value.indexOf('>') + 1)
                val r = Regex("""\sr="(\d+)"""").find(tag)?.groupValues?.get(1)?.toInt()?.minus(1) ?: auto
                auto = r + 1
                val nr = if (rows) index(r) ?: continue else r
                var row = m.value.replaceFirst(Regex("""\sr="\d+""""), " r=\"${nr + 1}\"").replace(Regex("""(<row\b[^>]*?)\sspans="[^"]*""""), "$1")
                row = Regex("""<c\b[^>]*?(?:/>|>.*?</c>)""", RegexOption.DOT_MATCHES_ALL).replace(row) { c ->
                    val ct = c.value.substring(0, c.value.indexOf('>') + 1)
                    val cr = Regex("""\sr="([A-Za-z]+)(\d+)"""").find(ct) ?: return@replace c.value
                    val col = cr.groupValues[1].uppercase().fold(0) { a, ch -> a * 26 + (ch - 'A' + 1) } - 1
                    val (_, nc) = (if (rows) nr to col else cell(r, col)) ?: return@replace ""
                    c.value.replaceFirst(cr.value, " r=\"${Sheets.colName(nc)}${nr + 1}\"")
                }
                rowsOut.append(row)
            }
            xml = head + dataOpen + rowsOut + tail
        }
        // column widths: <col min max>
        if (!rows) xml = Regex("""<col\b([^>]*?)/>""").replace(xml) { m ->
            val a = Regex("""min="(\d+)"""").find(m.groupValues[1])?.groupValues?.get(1)?.toInt()?.minus(1) ?: return@replace m.value
            val b = Regex("""max="(\d+)"""").find(m.groupValues[1])?.groupValues?.get(1)?.toInt()?.minus(1) ?: return@replace m.value
            val s = span(a, minOf(b, 16383 - maxOf(count, 0))) ?: return@replace ""
            m.value.replaceFirst(Regex("""min="\d+""""), "min=\"${s.first + 1}\"").replaceFirst(Regex("""max="\d+""""), "max=\"${minOf(s.last, 16383) + 1}\"")
        }.replace("<cols></cols>", "")
        // merges that lose all their cells go (attrs() below moves the rest, as it does links' — moved here too, a merge
        // under the cut went down twice and covered the row below it); the count follows
        xml = Regex("""<mergeCell ref="([^"]*)"\s*/>""").replace(xml) { m -> if (ref(m.groupValues[1]) == null) "" else m.value }
        xml = xml.replace(Regex("""<mergeCells count="\d+">""")) { "<mergeCells count=\"${Regex("<mergeCell ").findAll(xml).count()}\">" }.replace(Regex("""<mergeCells count="0">\s*</mergeCells>"""), "")
        // rules and validations whose ranges are all gone go
        xml = Regex("""<conditionalFormatting\b[^>]*sqref="([^"]*)"[^>]*>.*?</conditionalFormatting>""", RegexOption.DOT_MATCHES_ALL).replace(xml) { m -> if (refs(m.groupValues[1]) == null) "" else m.value }
        xml = Regex("""<dataValidation\b[^>]*sqref="([^"]*)"[^>]*?(?:/>|>.*?</dataValidation>)""", RegexOption.DOT_MATCHES_ALL).replace(xml) { m -> if (refs(m.groupValues[1]) == null) "" else m.value }
        xml = Regex("""<hyperlink\b[^>]*ref="([^"]*)"[^>]*/>""").replace(xml) { m -> if (refs(m.groupValues[1]) == null) "" else m.value }
        xml = attrs(xml)
        return formulas(xml, sheet)
    }

    /** A drawing's anchors: <xdr:from>/<xdr:to>'s row or column numbers moved (a deleted one to where the cut is). */
    private fun anchors(x: String): String {
        // xdr: as Excel writes it, or the default namespace (openpyxl)
        val tag = if (rows) "row" else "col"
        return Regex("""(<(?:\w+:)?$tag>)(\d+)(</(?:\w+:)?$tag>)""").replace(x) { m ->
            val i = m.groupValues[2].toInt()
            m.groupValues[1] + (index(i) ?: at) + m.groupValues[3]
        }
    }

    /** A table's range, its filter's, and its list of columns (a column inserted inside it is a new column of it). */
    private fun table(x0: String): String {
        val before = Regex("""<table\b[^>]*\sref="([^"]*)"""").find(x0)?.groupValues?.get(1)?.let(Sheets::rangeRef)
        var x = attrs(x0)
        val after = Regex("""<table\b[^>]*\sref="([^"]*)"""").find(x)?.groupValues?.get(1)?.let(Sheets::rangeRef)
        if (!rows && before != null && after != null) {
            val cols = Regex("""<tableColumn\b[^>]*?(?:/>|>.*?</tableColumn>)""", RegexOption.DOT_MATCHES_ALL).findAll(x).map { it.value }.toMutableList()
            if (count > 0 && at > before.c1 && at <= before.c2) {
                var id = Regex("""\sid="(\d+)"""").findAll(cols.joinToString("")).maxOfOrNull { it.groupValues[1].toInt() } ?: 0
                val names = cols.mapNotNull { Regex("""\sname="([^"]*)"""").find(it)?.groupValues?.get(1) }.toMutableSet()
                repeat(count) { k ->
                    id++; var n = id; while ("Column$n" in names) n++; names += "Column$n"
                    cols.add(at - before.c1 + k, "<tableColumn id=\"$id\" name=\"Column$n\"/>")
                }
            } else if (count < 0) {
                for (c in (before.c2 downTo before.c1)) if (c in deleted) cols.removeAt(c - before.c1)
            }
            x = x.replace(Regex("""<tableColumns\b[^>]*>.*?</tableColumns>""", RegexOption.DOT_MATCHES_ALL), "<tableColumns count=\"${cols.size}\">${cols.joinToString("")}</tableColumns>")
        }
        return x
    }

    /** Excel's note boxes (VML): each anchor's row or column moved. */
    private fun vml(x: String): String {
        val tag = if (rows) "x:Row" else "x:Column"
        return Regex("""(<$tag>)(\d+)(</$tag>)""").replace(x) { m -> m.groupValues[1] + (index(m.groupValues[2].toInt()) ?: at) + m.groupValues[3] }
    }

    // --- .ods ---

    /** [source] (an .ods) into [target], LibreOffice's way: rows or cells added or taken out, every address moved. */
    fun ods(source: File, target: File, index: Int, tabs: List<String>) {
        val zip = ZipFile(source)
        val entry = zip.getEntry("content.xml") ?: sheetProblem(R.string.tables_err_not_spreadsheet)
        if (entry.size !in 0..80_000_000) sheetProblem(R.string.tables_too_big_to_change)
        val doc = OfficeFormats.parse(zip.getInputStream(entry).use { it.readBytes() })
        val T = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
        val tables = doc.getElementsByTagNameNS(T, "table").let { l -> (0 until l.length).map { l.item(it) as org.w3c.dom.Element } }
            .filter { t -> generateSequence(t.parentNode) { it.parentNode }.none { it is org.w3c.dom.Element && it.localName == "table" } }
        val table = tables.getOrNull(index) ?: sheetProblem(R.string.tables_err_no_sheet_n, index + 1)
        fun kids(e: org.w3c.dom.Element, vararg names: String): List<org.w3c.dom.Element> {
            val out = ArrayList<org.w3c.dom.Element>(); var n = e.firstChild
            while (n != null) { if (n is org.w3c.dom.Element && n.namespaceURI == T && n.localName in names) out += n; n = n.nextSibling }
            return out
        }
        fun rowsOf(e: org.w3c.dom.Element): List<org.w3c.dom.Element> = kids(e, "table-row", "table-header-rows", "table-row-group", "table-rows").flatMap { if (it.localName == "table-row") listOf(it) else rowsOf(it) }
        /** The run of [list] (each repeated by [attr]) cut so [i] starts an element; the element there (null past the end). */
        fun cut(list: List<org.w3c.dom.Element>, i: Int, attr: String): org.w3c.dom.Element? {
            var at = 0
            for (e in list) {
                val n = e.getAttributeNS(T, attr).toIntOrNull() ?: 1
                if (i < at + n) {
                    if (i == at) return e
                    val first = e.cloneNode(true) as org.w3c.dom.Element
                    if (i - at > 1) first.setAttributeNS(T, "table:$attr", "${i - at}") else first.removeAttributeNS(T, attr)
                    e.parentNode.insertBefore(first, e)
                    if (n - (i - at) > 1) e.setAttributeNS(T, "table:$attr", "${n - (i - at)}") else e.removeAttributeNS(T, attr)
                    return e
                }
                at += n
            }
            return null
        }
        fun take(start: org.w3c.dom.Element?, list: () -> List<org.w3c.dom.Element>, howMany: Int, attr: String) {
            // the elements from [start] covering [howMany] places: removed (the last one cut to fit)
            var left = howMany; var e = start
            while (e != null && left > 0) {
                val n = e.getAttributeNS(T, attr).toIntOrNull() ?: 1
                val next = list().let { l -> l.getOrNull(l.indexOf(e) + 1) }
                if (n <= left) { e.parentNode.removeChild(e); left -= n }
                else { e.setAttributeNS(T, "table:$attr", "${n - left}"); left = 0 }
                e = next
            }
        }
        if (rows) {
            val there = cut(rowsOf(table), at, "number-rows-repeated")
            if (count > 0) {
                // empty rows, in the look of the row they are put before
                val row = doc.createElementNS(T, "table:table-row")
                there?.getAttributeNS(T, "style-name")?.takeIf { it.isNotEmpty() }?.let { row.setAttributeNS(T, "table:style-name", it) }
                if (count > 1) row.setAttributeNS(T, "table:number-rows-repeated", "$count")
                row.appendChild(doc.createElementNS(T, "table:table-cell"))
                if (there != null) there.parentNode.insertBefore(row, there) else table.appendChild(row)
            } else take(there, { rowsOf(table) }, -count, "number-rows-repeated")
        } else {
            val cols = kids(table, "table-column", "table-columns", "table-header-columns", "table-column-group").flatMap { if (it.localName == "table-column") listOf(it) else kids(it, "table-column") }
            val colThere = cut(cols, at, "number-columns-repeated")
            if (count > 0) {
                val c = (colThere ?: cols.lastOrNull())?.cloneNode(false) as? org.w3c.dom.Element ?: doc.createElementNS(T, "table:table-column")
                if (count > 1) c.setAttributeNS(T, "table:number-columns-repeated", "$count") else c.removeAttributeNS(T, "number-columns-repeated")
                if (colThere != null) colThere.parentNode.insertBefore(c, colThere) else (cols.lastOrNull()?.parentNode ?: table).appendChild(c)
            } else take(colThere, { kids(table, "table-column", "table-columns", "table-header-columns", "table-column-group").flatMap { if (it.localName == "table-column") listOf(it) else kids(it, "table-column") } }, -count, "number-columns-repeated")
            for (row in rowsOf(table)) {
                val there = cut(kids(row, "table-cell", "covered-table-cell"), at, "number-columns-repeated")
                if (count > 0) {
                    if (there == null) continue // past the row's cells: nothing to move
                    val cell = doc.createElementNS(T, "table:table-cell")
                    if (count > 1) cell.setAttributeNS(T, "table:number-columns-repeated", "$count")
                    row.insertBefore(cell, there)
                } else take(there, { kids(row, "table-cell", "covered-table-cell") }, -count, "number-columns-repeated")
            }
        }
        // merged cells: a span crossing the cut grows or shrinks
        var r = 0
        for (row in rowsOf(table)) {
            val nr = row.getAttributeNS(T, "number-rows-repeated").toIntOrNull() ?: 1
            var c = 0
            for (cell in kids(row, "table-cell", "covered-table-cell")) {
                val nc = cell.getAttributeNS(T, "number-columns-repeated").toIntOrNull() ?: 1
                val key = if (rows) "number-rows-spanned" else "number-columns-spanned"
                cell.getAttributeNS(T, key).toIntOrNull()?.takeIf { it > 1 }?.let { n ->
                    val start = if (rows) r else c
                    span(start, start + n - 1)?.let { s -> cell.setAttributeNS(T, "table:$key", "${s.last - s.first + 1}") }
                }
                c += nc
            }
            r += nr
        }
        // every formula and address, in every sheet
        val all = doc.getElementsByTagName("*")
        for (k in 0 until all.length) {
            val e = all.item(k) as org.w3c.dom.Element
            val own = generateSequence<org.w3c.dom.Node>(e) { it.parentNode }.firstOrNull { it is org.w3c.dom.Element && it.localName == "table" && it.namespaceURI == T }
                ?.let { (it as org.w3c.dom.Element).getAttributeNS(T, "name") } ?: "\u0000"
            val a = e.attributes
            for (j in 0 until a.length) {
                val at = a.item(j) as org.w3c.dom.Attr
                when (at.localName) {
                    "formula", "condition", "value" -> if (at.value.contains("[")) at.value = odfFormula(at.value, own)
                    "target-range-address", "cell-range-address", "base-cell-address", "end-cell-address", "cell-address" ->
                        at.value = odfAddresses(at.value) ?: at.value
                }
            }
        }
        SheetSave.rezip(zip, target, mapOf("content.xml" to ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + OfficeFormats.xml(doc.documentElement)).toByteArray(Charsets.UTF_8)), stored = "mimetype")
        zip.close()
    }

    // --- .csv ---

    /** A .csv's cells moved in memory: written again by [SheetSave]. */
    fun cells(sheet: Sheet): CellMap {
        val out = CellMap()
        sheet.cells.forEach { k, c -> cell(Sheet.row(k), Sheet.col(k))?.let { (r, cc) -> out[Sheet.key(r, cc)] = c } }
        return out
    }
}
