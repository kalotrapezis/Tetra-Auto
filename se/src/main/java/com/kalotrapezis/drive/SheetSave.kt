package com.kalotrapezis.drive

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Saving a spreadsheet's edits back into its file (asked 16 Oct): only what changed is written — in an .xlsx the rows
 * of the edited cells, in an .ods the edited cells — and every other part is copied as it was, so nothing the viewer
 * does not draw is lost. Excel and LibreOffice are asked to work the formulas out again when they open it.
 */
internal object SheetSave {
    /** Writes [book]'s edits into [target] (a new file), reading the parts from [source]. */
    fun save(book: Workbook, source: File, target: File) {
        when (book.kind) {
            "xlsx" -> xlsx(book, source, target)
            "ods" -> ods(book, source, target)
            else -> csv(book, target)
        }
    }

    // --- .xlsx ---

    private fun esc(s: String) = OfficeFormats.esc(s)
    private fun unesc(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** A number as the file keeps it: 12, 0.1, 1.0E20. */
    private fun num(v: Double) = if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()

    /** A cell as SpreadsheetML: its value by kind, a formula with its value, words inline; null: no cell at all. */
    fun cellXml(ref: String, style: Int, cell: SheetCell): String? {
        val s = if (style > 0) " s=\"$style\"" else ""
        // a spilling formula as Excel's array formula over its cells (Excel shows {=…}; LibreOffice its matrix)
        val array = cell.spill?.let { g -> " t=\"array\" ref=\"${Sheets.colName(g.c1)}${g.r1 + 1}:${Sheets.colName(g.c2)}${g.r2 + 1}\"" } ?: ""
        val f = cell.formula?.removePrefix("=")?.let { "<f$array>${esc(SheetCalc.toXlsx(it))}</f>" } ?: ""
        return when {
            cell.formula != null -> when (cell.kind) {
                SheetKind.Number -> "<c r=\"$ref\"$s>$f<v>${num(cell.value ?: 0.0)}</v></c>"
                SheetKind.Bool -> "<c r=\"$ref\"$s t=\"b\">$f<v>${if (cell.value == 1.0) 1 else 0}</v></c>"
                SheetKind.Error -> "<c r=\"$ref\"$s t=\"e\">$f<v>${esc(cell.text)}</v></c>"
                SheetKind.Text -> "<c r=\"$ref\"$s t=\"str\">$f<v>${esc(cell.text)}</v></c>"
                SheetKind.Empty -> "<c r=\"$ref\"$s t=\"str\">$f<v></v></c>"
            }
            cell.kind == SheetKind.Number -> "<c r=\"$ref\"$s><v>${num(cell.value ?: 0.0)}</v></c>"
            cell.kind == SheetKind.Bool -> "<c r=\"$ref\"$s t=\"b\"><v>${if (cell.value == 1.0) 1 else 0}</v></c>"
            cell.kind == SheetKind.Error -> "<c r=\"$ref\"$s t=\"e\"><v>${esc(cell.text)}</v></c>"
            cell.kind == SheetKind.Text -> "<c r=\"$ref\"$s t=\"inlineStr\"><is>${cell.rich?.let(::runsXml) ?: "<t xml:space=\"preserve\">${esc(cell.text)}</t>"}</is></c>"
            style > 0 -> "<c r=\"$ref\"$s/>"
            else -> null
        }
    }

    /** Rich text as Excel writes it: a run each, its look in rPr (font, bold, italic, strike, colour, size, underline: Excel's order). */
    fun runsXml(runs: List<DocRun>): String = runs.joinToString("") { r ->
        val pr = listOfNotNull(DocStyles.Plain.fontName(r.font)?.let { "<rFont val=\"${esc(it)}\"/>" }, "<b/>".takeIf { r.bold }, "<i/>".takeIf { r.italic }, "<strike/>".takeIf { r.strike },
            r.color?.let { "<color rgb=\"FF%06X\"/>".format(it and 0xFFFFFF) }, r.sizePt?.let { "<sz val=\"${num(it.toDouble())}\"/>" }, "<u/>".takeIf { r.underline }).joinToString("")
        "<r>${if (pr.isEmpty()) "" else "<rPr>$pr</rPr>"}<t xml:space=\"preserve\">${esc(r.text)}</t></r>"
    }

    private val CELL = Regex("""<c\b[^>]*?(?:/>|>.*?</c>)""", RegexOption.DOT_MATCHES_ALL)
    private fun attr(tag: String, name: String) = Regex("""\s$name="([^"]*)"""").find(tag)?.groupValues?.get(1)

    /**
     * A sheet's XML with its edited cells: each row with an edit written again (its other cells as they were), new
     * rows where they go; [styleOf] the style to save a cell with (its old one: [old]); a shared formula an edit
     * breaks spelled out in the cells that shared it ([formulaAt]).
     */
    fun patchSheet(xml: String, edits: Map<Long, SheetCell>, styleOf: (SheetCell, Int?) -> Int, formulaAt: (Int, Int) -> String?, rows: Int, cols: Int): String {
        val open = Regex("""<sheetData\s*/>|<sheetData[^>]*>""").find(xml) ?: sheetProblem(R.string.tables_err_no_sheet_data)
        val body0 = if (open.value.endsWith("/>")) open.range.last + 1 else open.range.last + 1
        val end = if (open.value.endsWith("/>")) body0 else xml.indexOf("</sheetData>", body0)
        val data = xml.substring(body0, end)
        // the rows: where each one is in sheetData, by number
        class Row(val r: Int, val start: Int, val end: Int)
        val list = ArrayList<Row>()
        var i = 0; var next = 0
        while (true) {
            val at = data.indexOf("<row", i); if (at < 0) break
            if (data.getOrNull(at + 4)?.let { it == ' ' || it == '>' || it == '/' || it == '\n' } != true) { i = at + 4; continue }
            val tagEnd = data.indexOf('>', at)
            val tag = data.substring(at, tagEnd + 1)
            val r = attr(tag, "r")?.toIntOrNull()?.minus(1) ?: next
            val close = if (tag.endsWith("/>")) tagEnd + 1 else data.indexOf("</row>", tagEnd).let { if (it < 0) sheetProblem(R.string.tables_err_row_end) else it + 6 }
            list += Row(r, at, close); next = r + 1; i = close
        }
        val byRow = list.associateBy { it.r }
        val editRows = edits.keys.groupBy { Sheet.row(it) }
        // shared formulas an edit breaks: the masters among the edited cells, and the cells of their groups
        val broken = HashMap<String, SheetRange>()
        for ((r, keys) in editRows) {
            val row = byRow[r] ?: continue
            for (m in CELL.findAll(data.substring(row.start, row.end))) {
                val ref = attr(m.value.substringBefore('>'), "r")?.let(Sheets::cellRef) ?: continue
                if (Sheet.key(ref.first, ref.second) !in keys) continue
                Regex("""<f\b([^>]*)>""").find(m.value)?.groupValues?.get(1)?.let { a ->
                    if (attr(a, "t") == "shared") { val si = attr(a, "si"); val g = attr(a, "ref")?.let(Sheets::rangeRef); if (si != null && g != null) broken[si] = g }
                }
            }
        }
        val rewrite = HashSet(editRows.keys)
        broken.values.forEach { g -> for (r in g.r1..g.r2) if (r in byRow) rewrite += r }

        fun rowXml(r: Int, old: String?): String {
            val tag = old?.let { it.substring(0, it.indexOf('>') + 1) } ?: "<row r=\"${r + 1}\">"
            val start = tag.removeSuffix("/>").removeSuffix(">").replace(Regex("""\sspans="[^"]*""""), "")
            val cells = java.util.TreeMap<Int, String>()
            var sequential = 0
            if (old != null && !tag.endsWith("/>")) for (m in CELL.findAll(old)) {
                val c = attr(m.value.substringBefore('>'), "r")?.let(Sheets::cellRef)?.second ?: sequential
                sequential = c + 1
                var x = m.value
                // a broken shared formula: written out in full here
                Regex("""<f\b([^>]*?)(?:/>|>([^<]*)</f>)""").find(x)?.let { f ->
                    if (attr(f.groupValues[1], "t") == "shared" && attr(f.groupValues[1], "si") in broken) formulaAt(r, c)?.let { full -> x = x.replace(f.value, "<f>${esc(SheetCalc.toXlsx(full.removePrefix("=")))}</f>") }
                }
                cells[c] = x
            }
            for (k in editRows[r].orEmpty()) {
                val c = Sheet.col(k)
                val oldStyle = cells[c]?.let { attr(it.substringBefore('>'), "s")?.toIntOrNull() }
                val x = cellXml(Sheets.colName(c) + (r + 1), styleOf(edits.getValue(k), oldStyle), edits.getValue(k))
                if (x == null) cells.remove(c) else cells[c] = x
            }
            return if (cells.isEmpty()) "$start/>" else "$start>${cells.values.joinToString("")}</row>"
        }

        val out = StringBuilder(xml.length + 1024)
        out.append(xml, 0, open.range.first).append("<sheetData>")
        var at = 0
        val newRows = (rewrite - byRow.keys).sorted().toMutableList()
        for (row in list) {
            while (newRows.isNotEmpty() && newRows.first() < row.r) out.append(data, at, row.start).also { at = row.start }.append(rowXml(newRows.removeAt(0), null))
            if (row.r in rewrite) { out.append(data, at, row.start).append(rowXml(row.r, data.substring(row.start, row.end))); at = row.end }
        }
        out.append(data, at, data.length)
        newRows.forEach { out.append(rowXml(it, null)) }
        out.append("</sheetData>")
        out.append(xml, if (open.value.endsWith("/>")) open.range.last + 1 else end + "</sheetData>".length, xml.length)
        // the used range, grown to the edits
        return out.toString().replaceFirst(Regex("""<dimension ref="[^"]*"\s*/>"""), "<dimension ref=\"A1:${Sheets.colName(maxOf(cols, 1) - 1)}${maxOf(rows, 1)}\"/>")
    }

    /** Excel's own number format ids for formats a typed value takes; others are added to the file. */
    private val BUILT = mapOf("0%" to 9, "0.00%" to 10, "d/m/yyyy" to 14, "h:mm" to 20, "h:mm:ss" to 21, "d/m/yyyy h:mm" to 22)

    private fun xlsx(book: Workbook, source: File, target: File) {
        val zip = ZipFile(source)
        val replaced = HashMap<String, ByteArray?>()
        fun read(path: String) = zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes() } } ?: sheetProblem(R.string.tables_err_no_part, path)
        // styles for typed formats: (the cell's style, a format) → a new xf
        val wanted = LinkedHashMap<Pair<Int, String>, Int>()
        var styles = book.parts["styles"]?.let { String(read(it)) }
        val xfCount = styles?.let { st -> Regex("""<cellXfs[^>]*>(.*?)</cellXfs>""", RegexOption.DOT_MATCHES_ALL).find(st)?.groupValues?.get(1)?.let { Regex("""<xf\b""").findAll(it).count() } } ?: 0
        fun styleOf(cell: SheetCell, @Suppress("UNUSED_PARAMETER") old: Int?): Int {
            // the cell's look: the file's own, or one formatting made (numbered after the file's)
            val base = cell.fileStyle
            val code = cell.format
            if (styles == null) return if (base < book.fileStyles) base else 0
            if (base < book.fileStyles && code == null) return base
            return wanted.getOrPut(base to (code ?: "")) { xfCount + wanted.size }
        }
        for ((i, edits) in book.edits) {
            if (edits.isEmpty()) continue
            val path = book.parts["$i"] ?: sheetProblem(R.string.tables_err_no_sheet_n, i + 1)
            val sheet = book.sheet(i)
            val xml = String(read(path), Charsets.UTF_8)
            replaced[path] = patchSheet(xml, edits, ::styleOf, { r, c -> sheet[r, c]?.formula }, sheet.rows, sheet.cols).toByteArray(Charsets.UTF_8)
        }
        if (wanted.isNotEmpty() && styles != null) {
            // the formats: the file's own, Excel's own ids, or new ones in numFmts
            var st: String = styles
            val ids = HashMap<String, Int>()
            Regex("""<numFmt\b[^>]*numFmtId="(\d+)"[^>]*formatCode="([^"]*)"""").findAll(st).forEach { ids.putIfAbsent(unesc(it.groupValues[2]), it.groupValues[1].toInt()) }
            SheetFormat.BUILT_IN.forEach { (id, code) -> ids.putIfAbsent(code, id) }
            var nextId = maxOf(163, Regex("""numFmtId="(\d+)"""").findAll(st).maxOfOrNull { it.groupValues[1].toInt() } ?: 163) + 1
            val newFmts = StringBuilder()
            fun codeOf(key: Pair<Int, String>) = key.second.ifEmpty { book.styles.getOrNull(key.first)?.format ?: "General" }
            for (key in wanted.keys) { val code = codeOf(key); if (code !in ids) ids[code] = BUILT[code] ?: (nextId++).also { newFmts.append("<numFmt numFmtId=\"$it\" formatCode=\"${esc(code)}\"/>") } }
            if (newFmts.isNotEmpty()) st = if ("<numFmts" in st) st.replace(Regex("""<numFmts[^>]*>"""), "$0$newFmts").replace(Regex("""(<numFmts[^>]*count=")(\d+)""")) { m -> m.groupValues[1] + (m.groupValues[2].toInt() + newFmts.split("<numFmt ").size - 1) }
                else st.replaceFirst(Regex("""(<styleSheet[^>]*>)"""), "$1<numFmts count=\"${newFmts.split("<numFmt ").size - 1}\">$newFmts</numFmts>")
            // each new xf: the cell's own, its number format changed
            val xfs = Regex("""(<cellXfs[^>]*>)(.*?)(</cellXfs>)""", RegexOption.DOT_MATCHES_ALL).find(st)!!
            val list = Regex("""<xf\b[^>]*?(?:/>|>.*?</xf>)""", RegexOption.DOT_MATCHES_ALL).findAll(xfs.groupValues[2]).map { it.value }.toList()
            // looks formatting made: their fonts, fills and borders added to the file's
            fun add(list: String, item: String): Int {
                val m = Regex("""(<$list\b[^>]*>)(.*?)(</$list>)""", RegexOption.DOT_MATCHES_ALL).find(st) ?: run {
                    st = st.replaceFirst(Regex("""(<styleSheet[^>]*>)"""), "$1<$list count=\"0\"></$list>"); return add(list, item)
                }
                val n = Regex("""<${list.dropLast(1)}\b""").findAll(m.groupValues[2]).count()
                st = st.replaceRange(m.range, m.groupValues[1].replace(Regex("""count="\d+""""), "count=\"${n + 1}\"") + m.groupValues[2] + item + m.groupValues[3])
                return n
            }
            fun rgb(c: Int) = "FF%06X".format(c and 0xFFFFFF)
            fun line(name: String, l: SheetLine?) = if (l == null) "<$name/>" else "<$name style=\"${when {
                l.double -> "double"; l.dots -> "dotted"; l.dashed && l.width >= 2 -> "mediumDashed"; l.dashed -> "dashed"; l.width >= 3 -> "thick"; l.width >= 2 -> "medium"; else -> "thin" }}\"><color rgb=\"${rgb(l.color)}\"/></$name>"
            val added = wanted.keys.joinToString("") { key ->
                val base = key.first
                val code = codeOf(key)
                if (base < book.fileStyles) {
                    val xf = list.getOrElse(base) { list.first() }
                    return@joinToString xf.replaceFirst(Regex("""numFmtId="\d+""""), "numFmtId=\"${ids[code]}\"").let { if ("numFmtId=" in it) it else it.replaceFirst("<xf ", "<xf numFmtId=\"${ids[code]}\" ") }
                        .replace(Regex("""\sapplyNumberFormat="[^"]*""""), "").replaceFirst("<xf ", "<xf applyNumberFormat=\"1\" ")
                }
                val look = book.styles[base]
                val font = add("fonts", "<font>" + (if (look.bold) "<b/>" else "") + (if (look.italic) "<i/>" else "") + (if (look.strike) "<strike/>" else "") +
                    (if (look.underline) "<u/>" else "") + "<sz val=\"${num(look.size.toDouble())}\"/>" + (look.color?.let { "<color rgb=\"${rgb(it)}\"/>" } ?: "") +
                    "<name val=\"${esc(look.font ?: "Calibri")}\"/></font>")
                val fill = look.fill?.let { add("fills", "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"${rgb(it)}\"/><bgColor indexed=\"64\"/></patternFill></fill>") } ?: 0
                val border = if (look.left == null && look.right == null && look.top == null && look.bottom == null) 0
                    else add("borders", "<border>" + line("left", look.left) + line("right", look.right) + line("top", look.top) + line("bottom", look.bottom) + "<diagonal/></border>")
                val h = when (look.align) { SheetAlign.Left -> "left"; SheetAlign.Center -> "center"; SheetAlign.Right -> "right"; SheetAlign.Justify -> "justify"; else -> null }
                val v = when (look.valign) { SheetVAlign.Top -> "top"; SheetVAlign.Center -> "center"; else -> null }
                val align = if (h == null && v == null && !look.wrap && !look.shrink && look.indent == 0 && look.rotation == 0) "" else "<alignment" + (h?.let { " horizontal=\"$it\"" } ?: "") +
                    (v?.let { " vertical=\"$it\"" } ?: "") + (if (look.wrap) " wrapText=\"1\"" else "") + (if (look.shrink) " shrinkToFit=\"1\"" else "") + (if (look.indent > 0) " indent=\"${look.indent}\"" else "") +
                    (if (look.rotation != 0) " textRotation=\"${look.rotation}\"" else "") + "/>"
                // an unlocked cell (Excel's Format › Lock Cell off): typed in while the sheet is protected
                val protection = if (look.locked) "" else "<protection locked=\"0\"/>"
                "<xf numFmtId=\"${ids[code]}\" fontId=\"$font\" fillId=\"$fill\" borderId=\"$border\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"" +
                    (if (align.isNotEmpty()) " applyAlignment=\"1\"" else "") + (if (protection.isNotEmpty()) " applyProtection=\"1\"" else "") +
                    (if (align.isEmpty() && protection.isEmpty()) "/>" else ">$align$protection</xf>")
            }
            val xfsNow = Regex("""(<cellXfs[^>]*>)(.*?)(</cellXfs>)""", RegexOption.DOT_MATCHES_ALL).find(st)!!
            st = st.replaceRange(xfsNow.range, xfsNow.groupValues[1].replace(Regex("""count="\d+""""), "count=\"${list.size + wanted.size}\"") + xfsNow.groupValues[2] + added + xfsNow.groupValues[3])
            styles = st
            replaced[book.parts.getValue("styles")] = st.toByteArray(Charsets.UTF_8)
        }
        // formulas worked out again on opening; Excel's calculation chain dropped (it lists the old formulas)
        book.parts["workbook"]?.let { wb ->
            var x = String(read(wb), Charsets.UTF_8)
            x = if (Regex("""<calcPr\b""").containsMatchIn(x)) x.replace(Regex("""<calcPr\b([^>]*?)(/?)>""")) { m -> "<calcPr" + m.groupValues[1].replace(Regex("""\sfullCalcOnLoad="[^"]*""""), "") + " fullCalcOnLoad=\"1\"" + m.groupValues[2] + ">" }
            else listOf("</definedNames>", "</externalReferences>", "</sheets>").firstNotNullOfOrNull { t -> x.indexOf(t).takeIf { it >= 0 }?.let { it + t.length } }
                ?.let { at -> x.substring(0, at) + "<calcPr fullCalcOnLoad=\"1\"/>" + x.substring(at) } ?: x
            replaced[wb] = x.toByteArray(Charsets.UTF_8)
            val rels = wb.substringBeforeLast('/', "").let { if (it.isEmpty()) "_rels/" else "$it/_rels/" } + wb.substringAfterLast('/') + ".rels"
            zip.getEntry(rels)?.let { replaced[rels] = String(read(rels)).replace(Regex("""<Relationship\b[^>]*calcChain[^>]*/>"""), "").toByteArray() }
        }
        zip.entries().asSequence().firstOrNull { it.name.endsWith("calcChain.xml") }?.let { e ->
            replaced[e.name] = null
            replaced["[Content_Types].xml"] = String(read("[Content_Types].xml")).replace(Regex("""<Override\b[^>]*calcChain[^>]*/>"""), "").toByteArray()
        }
        rezip(zip, target, replaced, stored = null)
        zip.close()
    }

    /** The zip again into [target]: each part as it was, or [replaced] (null: left out); [stored] first and not compressed. */
    fun rezip(zip: ZipFile, target: File, replaced: Map<String, ByteArray?>, stored: String?) {
        ZipOutputStream(target.outputStream().buffered()).use { out ->
            val entries = zip.entries().asSequence().toList().sortedBy { if (it.name == stored) 0 else 1 }
            for (e in entries) {
                if (e.isDirectory) continue
                val data = if (e.name in replaced) replaced[e.name] ?: continue else null
                val ne = ZipEntry(e.name)
                if (e.name == stored) {
                    val bytes = data ?: zip.getInputStream(e).use { it.readBytes() }
                    ne.method = ZipEntry.STORED; ne.size = bytes.size.toLong(); ne.compressedSize = bytes.size.toLong()
                    ne.crc = CRC32().apply { update(bytes) }.value
                    out.putNextEntry(ne); out.write(bytes); out.closeEntry(); continue
                }
                out.putNextEntry(ne)
                if (data != null) out.write(data) else zip.getInputStream(e).use { it.copyTo(out) }
                out.closeEntry()
            }
            // parts the file did not have (a new sheet)
            for ((name, data) in replaced) if (data != null && zip.getEntry(name) == null) { out.putNextEntry(ZipEntry(name)); out.write(data); out.closeEntry() }
        }
    }

    // --- .ods ---

    private const val TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
    private const val OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    private const val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    private const val CALCEXT = "urn:org:documentfoundation:names:experimental:calc:xmlns:calcext:1.0"

    /** An Excel formula in LibreOffice's way: "of:=SUM([.A1:.B2];[$Other.C3])". */
    fun odfFormula(f: String): String {
        val body = SheetCalc.toOds(f.removePrefix("="))
        val ref = Regex("""(?:('(?:[^']|'')+'|[\p{L}_][\p{L}\p{N}_.]*)!)?(\$?[A-Za-z]{1,3}\$?\d+)(?::(\$?[A-Za-z]{1,3}\$?\d+))?(?![\p{L}\p{N}_(])""")
        val out = StringBuilder()
        // outside words in quotes: references bracketed, commas as semicolons
        val parts = body.split('"')
        parts.forEachIndexed { i, part ->
            if (i > 0) out.append('"')
            if (i % 2 == 1) { out.append(part); return@forEachIndexed }
            out.append(ref.replace(part.replace(',', ';')) { m ->
                val sheet = m.groupValues[1].takeIf { it.isNotEmpty() }?.let { s -> "$$s." } ?: "."
                "[" + sheet + m.groupValues[2] + (m.groupValues[3].takeIf { it.isNotEmpty() }?.let { ":" + "." + it } ?: "") + "]"
            })
        }
        return "of:=$out"
    }

    private fun ods(book: Workbook, source: File, target: File) {
        val zip = ZipFile(source)
        val entry = zip.getEntry("content.xml") ?: sheetProblem(R.string.tables_err_not_spreadsheet)
        // shortcut: content.xml is changed as a whole document; a giant one (hundreds of MB) is too big to save here
        if (entry.size !in 0..80_000_000) sheetProblem(R.string.tables_too_big_to_save)
        val doc = OfficeFormats.parse(zip.getInputStream(entry).use { it.readBytes() })
        val tables = doc.getElementsByTagNameNS(TABLE, "table").let { l -> (0 until l.length).map { l.item(it) as org.w3c.dom.Element } }
            .filter { t -> generateSequence(t.parentNode) { it.parentNode }.none { it is org.w3c.dom.Element && it.localName == "table" } }
        val looks = OdsLooks(doc, book)
        for ((i, edits) in book.edits) {
            val table = tables.getOrNull(i) ?: continue
            for ((k, cell) in edits) odsCell(table, Sheet.row(k), Sheet.col(k), cell, book, looks)
        }
        rezip(zip, target, mapOf("content.xml" to ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + OfficeFormats.xml(doc.documentElement)).toByteArray(Charsets.UTF_8)), stored = "mimetype")
        zip.close()
    }

    private fun kids(e: org.w3c.dom.Element, vararg names: String): List<org.w3c.dom.Element> {
        val out = ArrayList<org.w3c.dom.Element>()
        var n = e.firstChild
        while (n != null) { if (n is org.w3c.dom.Element && n.namespaceURI == TABLE && n.localName in names) out += n; n = n.nextSibling }
        return out
    }

    /** A table's rows in order (inside header rows and row groups too). */
    private fun rows(e: org.w3c.dom.Element): List<org.w3c.dom.Element> = kids(e, "table-row", "table-header-rows", "table-row-group", "table-rows")
        .flatMap { if (it.localName == "table-row") listOf(it) else rows(it) }

    /** The element that is the [index]th of its repeated run, split from it ([attr]: the repeat attribute). */
    private fun single(e: org.w3c.dom.Element, offset: Int, attr: String): org.w3c.dom.Element {
        val n = e.getAttributeNS(TABLE, attr).toIntOrNull() ?: 1
        if (n <= 1) return e
        val parent = e.parentNode
        fun copy(count: Int) = (e.cloneNode(true) as org.w3c.dom.Element).apply { if (count > 1) setAttributeNS(TABLE, "table:$attr", "$count") else removeAttributeNS(TABLE, attr) }
        val one = copy(1)
        if (offset > 0) parent.insertBefore(copy(offset), e)
        parent.insertBefore(one, e)
        if (n - offset - 1 > 0) parent.insertBefore(copy(n - offset - 1), e)
        parent.removeChild(e)
        return one
    }

    private fun odsCell(table: org.w3c.dom.Element, r: Int, c: Int, cell: SheetCell, book: Workbook, looks: OdsLooks) {
        val doc = table.ownerDocument
        // the row: found through the repeats, or added at the end
        var at = 0; var row: org.w3c.dom.Element? = null
        for (e in rows(table)) {
            val n = e.getAttributeNS(TABLE, "number-rows-repeated").toIntOrNull() ?: 1
            if (r < at + n) { row = single(e, r - at, "number-rows-repeated"); break }
            at += n
        }
        if (row == null) {
            if (r > at) table.appendChild(doc.createElementNS(TABLE, "table:table-row").apply { setAttributeNS(TABLE, "table:number-rows-repeated", "${r - at}"); appendChild(doc.createElementNS(TABLE, "table:table-cell")) })
            row = doc.createElementNS(TABLE, "table:table-row") as org.w3c.dom.Element
            table.appendChild(row)
        }
        var col = 0; var target: org.w3c.dom.Element? = null
        for (e in kids(row, "table-cell", "covered-table-cell")) {
            val n = e.getAttributeNS(TABLE, "number-columns-repeated").toIntOrNull() ?: 1
            if (c < col + n) { target = single(e, c - col, "number-columns-repeated"); break }
            col += n
        }
        if (target == null) {
            if (c > col) row.appendChild(doc.createElementNS(TABLE, "table:table-cell").apply { if (c - col > 1) setAttributeNS(TABLE, "table:number-columns-repeated", "${c - col}") })
            target = doc.createElementNS(TABLE, "table:table-cell") as org.w3c.dom.Element
            row.appendChild(target)
        }
        if (target.localName == "covered-table-cell") return // inside a merged cell: nothing of its own
        // its look: the file's style by its name, or one formatting made
        val style = cell.fileStyle
        val name = if (style < book.fileStyles) book.styleNames.getOrNull(style) else looks.name(style, cell.format)
        if (name == null || name == "Default") target.removeAttributeNS(TABLE, "style-name") else target.setAttributeNS(TABLE, "table:style-name", name)
        // its value: what it was goes, the edit comes; its look, comments and pictures stay
        for (a in listOf("value-type", "value", "date-value", "time-value", "boolean-value", "string-value", "currency")) target.removeAttributeNS(OFFICE, a)
        target.removeAttributeNS(CALCEXT, "value-type"); target.removeAttributeNS(TABLE, "formula")
        var child = target.firstChild
        while (child != null) { val nx = child.nextSibling; if (child is org.w3c.dom.Element && child.namespaceURI == TEXT && child.localName == "p") target.removeChild(child); child = nx }
        cell.formula?.let { target.setAttributeNS(TABLE, "table:formula", odfFormula(it)) }
        // a spilling formula as LibreOffice's matrix over its cells
        target.removeAttributeNS(TABLE, "number-matrix-rows-spanned"); target.removeAttributeNS(TABLE, "number-matrix-columns-spanned")
        cell.spill?.takeIf { cell.formula != null }?.let { g -> target.setAttributeNS(TABLE, "table:number-matrix-rows-spanned", "${g.r2 - g.r1 + 1}"); target.setAttributeNS(TABLE, "table:number-matrix-columns-spanned", "${g.c2 - g.c1 + 1}") }
        fun type(t: String) { target.setAttributeNS(OFFICE, "office:value-type", t); target.setAttributeNS(CALCEXT, "calcext:value-type", t) }
        when (cell.kind) {
            SheetKind.Number -> {
                val v = cell.value ?: 0.0
                val code = cell.format ?: book.styles.getOrNull(style)?.format ?: "General"
                if (book.format.isDate(code) && (cell.format != null || style >= book.fileStyles)) { type("date"); target.setAttributeNS(OFFICE, "office:date-value", book.format.dateTime(v).toString().let { if (it.endsWith("T00:00")) it.substringBefore('T') else it }) }
                else if ('%' in SheetFormat.strip(code)) { type("percentage"); target.setAttributeNS(OFFICE, "office:value", num(v)) }
                else { type("float"); target.setAttributeNS(OFFICE, "office:value", num(v)) }
            }
            SheetKind.Bool -> { type("boolean"); target.setAttributeNS(OFFICE, "office:boolean-value", if (cell.value == 1.0) "true" else "false") }
            SheetKind.Text -> type("string")
            SheetKind.Error -> type("error")
            SheetKind.Empty -> Unit
        }
        val rich = cell.rich
        if (rich != null) {
            // rich text: LibreOffice's spans, each with a text style of its runs' look; a line break a new paragraph
            var p = doc.createElementNS(TEXT, "text:p").also { target.appendChild(it) }
            for (run in rich) run.text.split('\n').forEachIndexed { k, piece ->
                if (k > 0) p = doc.createElementNS(TEXT, "text:p").also { target.appendChild(it) }
                if (piece.isEmpty()) return@forEachIndexed
                val span = looks.span(run)
                p.appendChild(if (span == null) doc.createTextNode(piece) else doc.createElementNS(TEXT, "text:span").apply { setAttributeNS(TEXT, "text:style-name", span); textContent = piece })
            }
        } else if (cell.text.isNotEmpty()) cell.text.split('\n').forEach { line -> target.appendChild(doc.createElementNS(TEXT, "text:p").apply { textContent = line }) }
    }

    /** Looks formatting made, as LibreOffice's automatic cell styles (with a number style for their format). */
    private class OdsLooks(val doc: org.w3c.dom.Document, val book: Workbook) {
        val STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
        val FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
        val NUMBER = "urn:oasis:names:tc:opendocument:xmlns:datastyle:1.0"
        val auto: org.w3c.dom.Element = (doc.documentElement.getElementsByTagNameNS(OFFICE, "automatic-styles").item(0) as? org.w3c.dom.Element)
            ?: doc.createElementNS(OFFICE, "office:automatic-styles").also { a -> doc.documentElement.insertBefore(a, doc.documentElement.getElementsByTagNameNS(OFFICE, "body").item(0)) }
        val made = HashMap<Pair<Int, String?>, String>()
        /** Every style name the file uses, so a new one is never one of them. */
        val taken = HashSet<String>().also { t -> val all = doc.getElementsByTagName("*"); for (i in 0 until all.length) (all.item(i) as org.w3c.dom.Element).getAttributeNS(STYLE, "name").takeIf { it.isNotEmpty() }?.let { t += it } }
        fun fresh(prefix: String) = generateSequence(1) { it + 1 }.map { "$prefix$it" }.first { it !in taken }.also { taken += it }
        private val spans = HashMap<DocRun, String?>()
        /** A rich text run's look as a text style (LibreOffice's for a span); null: no look of its own. */
        fun span(run: DocRun): String? = spans.getOrPut(run.copy(text = "")) {
            if (run.copy(text = "") == DocRun("")) return@getOrPut null
            val e = doc.createElementNS(STYLE, "style:style"); val name = fresh("Tsp")
            e.setAttributeNS(STYLE, "style:name", name); e.setAttributeNS(STYLE, "style:family", "text")
            e.appendChild(doc.createElementNS(STYLE, "style:text-properties").apply {
                if (run.bold) setAttributeNS(FO, "fo:font-weight", "bold"); if (run.italic) setAttributeNS(FO, "fo:font-style", "italic")
                if (run.underline) { setAttributeNS(STYLE, "style:text-underline-style", "solid"); setAttributeNS(STYLE, "style:text-underline-width", "auto"); setAttributeNS(STYLE, "style:text-underline-color", "font-color") }
                if (run.strike) setAttributeNS(STYLE, "style:text-line-through-style", "solid")
                run.sizePt?.let { setAttributeNS(FO, "fo:font-size", "${num(it.toDouble())}pt") }
                run.color?.let { setAttributeNS(FO, "fo:color", "#%06x".format(it and 0xFFFFFF)) }
                DocStyles.Plain.fontName(run.font)?.let { setAttributeNS(FO, "fo:font-family", it) }
            })
            auto.appendChild(e); name
        }

        fun hex(c: Int) = "#%06x".format(c and 0xFFFFFF)
        // a double as LibreOffice writes Excel's (a bare "double" it takes as thin)
        fun line(l: SheetLine?) = if (l == null) "none" else if (l.double) "1.76pt double-thin ${hex(l.color)}" else "${if (l.width >= 3) "2.5pt" else if (l.width >= 2) "1.5pt" else "0.74pt"} ${if (l.dots) "dotted" else if (l.dashed) "dashed" else "solid"} ${hex(l.color)}"

        fun name(style: Int, format: String?): String = made.getOrPut(style to format) {
            val look = book.styles[style]
            val e = doc.createElementNS(STYLE, "style:style")
            val name = fresh("Tce")
            e.setAttributeNS(STYLE, "style:name", name); e.setAttributeNS(STYLE, "style:family", "table-cell"); e.setAttributeNS(STYLE, "style:parent-style-name", "Default")
            dataStyle(format ?: look.format)?.let { e.setAttributeNS(STYLE, "style:data-style-name", it) }
            e.appendChild(doc.createElementNS(STYLE, "style:table-cell-properties").apply {
                look.fill?.let { setAttributeNS(FO, "fo:background-color", hex(it)) }
                setAttributeNS(FO, "fo:border-left", line(look.left)); setAttributeNS(FO, "fo:border-right", line(look.right))
                setAttributeNS(FO, "fo:border-top", line(look.top)); setAttributeNS(FO, "fo:border-bottom", line(look.bottom))
                if (look.wrap) setAttributeNS(FO, "fo:wrap-option", "wrap")
                if (look.shrink) setAttributeNS(STYLE, "style:shrink-to-fit", "true")
                if (!look.locked) setAttributeNS(STYLE, "style:cell-protect", "none")
                // Excel's angle (91–180: down) as LibreOffice's degrees counterclockwise
                if (look.rotation in 1..180) { setAttributeNS(STYLE, "style:rotation-angle", "${if (look.rotation <= 90) look.rotation else 360 - (look.rotation - 90)}"); setAttributeNS(STYLE, "style:rotation-align", "none") }
                setAttributeNS(STYLE, "style:vertical-align", when (look.valign) { SheetVAlign.Top -> "top"; SheetVAlign.Center -> "middle"; else -> "bottom" })
                if (look.align != SheetAlign.General) setAttributeNS(STYLE, "style:text-align-source", "fix")
            })
            if (look.align != SheetAlign.General || look.indent > 0) e.appendChild(doc.createElementNS(STYLE, "style:paragraph-properties").apply {
                if (look.align != SheetAlign.General) setAttributeNS(FO, "fo:text-align", when (look.align) { SheetAlign.Left -> "start"; SheetAlign.Center -> "center"; SheetAlign.Right -> "end"; else -> "justify" })
                if (look.indent > 0) setAttributeNS(FO, "fo:margin-left", String.format(java.util.Locale.ROOT, "%.3fcm", look.indent * 0.2965))
            })
            e.appendChild(doc.createElementNS(STYLE, "style:text-properties").apply {
                if (look.bold) setAttributeNS(FO, "fo:font-weight", "bold")
                if (look.italic) setAttributeNS(FO, "fo:font-style", "italic")
                if (look.underline) { setAttributeNS(STYLE, "style:text-underline-style", "solid"); setAttributeNS(STYLE, "style:text-underline-width", "auto"); setAttributeNS(STYLE, "style:text-underline-color", "font-color") }
                if (look.strike) setAttributeNS(STYLE, "style:text-line-through-style", "solid")
                look.color?.let { setAttributeNS(FO, "fo:color", hex(it)) }
                setAttributeNS(FO, "fo:font-size", "${num(look.size.toDouble())}pt")
                look.font?.let { setAttributeNS(FO, "fo:font-family", it) }
            })
            auto.appendChild(e)
            name
        }

        /** A number style for an Excel format: decimals, thousands, percent, currency, a day-first date, a time, words. */
        fun dataStyle(code: String): String? {
            if (code.equals("General", true)) return null
            val f = SheetFormat()
            val name = fresh("TN")
            fun el(tag: String) = doc.createElementNS(NUMBER, "number:$tag")
            val e: org.w3c.dom.Element
            if (f.isDate(code)) {
                val time = Regex("[hs]", RegexOption.IGNORE_CASE).containsMatchIn(SheetFormat.strip(code))
                val day = Regex("[dy]", RegexOption.IGNORE_CASE).containsMatchIn(SheetFormat.strip(code))
                e = el(if (day) "date-style" else "time-style")
                fun text(t: String) = el("text").apply { textContent = t }
                if (day) {
                    e.appendChild(el("day").apply { if ("dd" in code) setAttributeNS(NUMBER, "number:style", "long") }); e.appendChild(text("/"))
                    e.appendChild(el("month").apply { if ("mm" in code.substringBefore('h')) setAttributeNS(NUMBER, "number:style", "long") }); e.appendChild(text("/"))
                    e.appendChild(el("year").apply { setAttributeNS(NUMBER, "number:style", "long") })
                    if (time) e.appendChild(text(" "))
                }
                if (time) { e.appendChild(el("hours")); e.appendChild(text(":")); e.appendChild(el("minutes").apply { setAttributeNS(NUMBER, "number:style", "long") })
                    if (Regex("s", RegexOption.IGNORE_CASE).containsMatchIn(SheetFormat.strip(code))) { e.appendChild(text(":")); e.appendChild(el("seconds").apply { setAttributeNS(NUMBER, "number:style", "long") }) } }
            } else if (code == "@") {
                e = el("text-style"); e.appendChild(el("text-content"))
            } else {
                val first = SheetFormat.split(code).first()
                val decimals = first.substringAfter('.', "").count { it == '0' }
                val percent = '%' in SheetFormat.strip(first)
                val currency = Regex("\"([^\"]*[€$£][^\"]*)\"|\\[\\$([€$£])").find(first)?.let { m -> m.groupValues.drop(1).first { it.isNotEmpty() }.trim() }
                e = el(if (percent) "percentage-style" else if (currency != null) "currency-style" else "number-style")
                e.appendChild(el("number").apply {
                    setAttributeNS(NUMBER, "number:decimal-places", "$decimals"); setAttributeNS(NUMBER, "number:min-decimal-places", "$decimals")
                    setAttributeNS(NUMBER, "number:min-integer-digits", "1"); if (',' in first.substringBefore('.')) setAttributeNS(NUMBER, "number:grouping", "true")
                })
                if (percent) e.appendChild(el("text").apply { textContent = "%" })
                if (currency != null) { e.appendChild(el("text").apply { textContent = " " }); e.appendChild(el("currency-symbol").apply { textContent = currency }) }
            }
            e.setAttributeNS(STYLE, "style:name", name)
            auto.insertBefore(e, auto.firstChild)
            return name
        }
    }

    // --- .csv ---

    /** The cells as a table of words in its own separator and letters (a CSV keeps no formulas: their values). */
    private fun csv(book: Workbook, target: File) {
        val (sep, charset) = book.csv ?: (',' to Charsets.UTF_8)
        val sheet = book.sheet(0)
        val text = buildString {
            for (r in 0 until sheet.rows) {
                if (r > 0) append("\r\n")
                for (c in 0 until sheet.cols) {
                    if (c > 0) append(sep)
                    val cell = sheet[r, c] ?: continue
                    // a number as typed (the formula bar's way), words quoted when they hold the separator, a quote or a line
                    val t = if (cell.kind == SheetKind.Number && cell.value != null) SheetInput.plain(cell.value, book.format) else cell.text
                    append(if (t.any { it == sep || it == '"' || it == '\n' || it == '\r' }) "\"" + t.replace("\"", "\"\"") + "\"" else t)
                }
            }
            append("\r\n")
        }
        target.writeBytes(text.toByteArray(charset))
    }
}
