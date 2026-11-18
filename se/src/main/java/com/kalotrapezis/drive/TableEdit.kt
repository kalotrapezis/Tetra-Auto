package com.kalotrapezis.drive

import kotlin.math.roundToInt

/**
 * Changing a Word table's structure (asked 2026-09-28): rows and columns in at the chosen cell, out again, cells merged
 * and split. Word keeps a table as rows of cells, a cell spanning grid columns (gridSpan) and continuing the cell above
 * (vMerge); here it is turned into blocks on the grid — each merged cell one block, however many rows and columns it
 * covers — changed as blocks, and written back as Word's rows. Every cell keeps its own properties and text; the
 * table keeps its width (the grid's columns and each cell's width are written to match).
 */
internal object TableEdit {
    private val GRID_COL = Regex("""<w:gridCol\b[^>]*?w:w="(\d+)"[^>]*/>""")
    private val GRID_SPAN = Regex("""<w:gridSpan\b[^>]*?w:val="(\d+)"[^>]*/>""")
    private val V_MERGE = Regex("""<w:vMerge\b[^>]*/>""")
    private val TC_W = Regex("""<w:tcW\b[^>]*/>""")
    private val HEADER = Regex("""<w:tblHeader\b[^>]*/>""")
    private val GRID_OUT = Regex("""<w:grid(?:Before|After)\b""")
    // OpenDocument (2026-09-29): a merged cell says how many columns and rows it spans, the places it covers are
    // covered-table-cells; the columns are table-column elements (a style each, sometimes repeated).
    private val ODT_SPAN_C = Regex("""\stable:number-columns-spanned="(\d+)"""")
    private val ODT_SPAN_R = Regex("""\stable:number-rows-spanned="(\d+)"""")
    private val ODT_COL = Regex("""<table:table-column\b[^>]*/>""")
    private val ODT_REPEAT = Regex("""\stable:number-columns-repeated="(\d+)"""")
    fun isOdt(t: DocTable) = !t.props.startsWith("<w:")

    fun gridColumns(t: DocTable) = GRID_COL.findAll(t.grid).map { it.groupValues[1].toFloat() }.toList()
    /** Grid columns a Word cell spans (an OpenDocument cell stands in one place: the places it covers are cells too). */
    fun span(c: DocCell) = if (!c.props.startsWith("<w:")) 1 else GRID_SPAN.find(c.props)?.groupValues?.get(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    /** The grid column a row's [cell] starts at. */
    fun gridStart(row: DocRow, cell: Int) = row.cells.take(cell).sumOf { span(it) }
    fun gridCount(t: DocTable) = maxOf(gridColumns(t).size, t.rows.maxOfOrNull { r -> r.cells.sumOf { span(it) } } ?: 1)

    // The order Word's schema keeps a cell's properties in; Word calls a file damaged when they are out of it.
    private val TC_ORDER = listOf("cnfStyle", "tcW", "gridSpan", "hMerge", "vMerge", "tcBorders", "shd", "noWrap", "tcMar", "textDirection", "tcFitText", "vAlign", "hideMark")
    /** [xml] (one property) put into a cell's properties in its place. */
    fun prop(raw: String, xml: String): String {
        if ("</w:tcPr>" !in raw) return "<w:tcPr>$xml</w:tcPr>"
        val name = Regex("""<w:(\w+)""").find(xml)!!.groupValues[1]
        val later = TC_ORDER.drop(TC_ORDER.indexOf(name) + 1).mapNotNull { n -> Regex("""<w:$n[\s/>]""").find(raw)?.range?.first }.minOrNull()
        val at = later ?: raw.lastIndexOf("</w:tcPr>")
        return raw.substring(0, at) + xml + raw.substring(at)
    }
    private fun withSpan(props: String, n: Int) = GRID_SPAN.replace(props, "").let { if (n > 1) prop(it, "<w:gridSpan w:val=\"$n\"/>") else it }
    private fun withMerge(props: String, kind: String?) = V_MERGE.replace(props, "").let {
        when (kind) { null -> it; "restart" -> prop(it, "<w:vMerge w:val=\"restart\"/>"); else -> prop(it, "<w:vMerge/>") }
    }

    /** A plain Word table, [widthPt] wide in equal columns (Word needs the grid to lay it out). */
    fun newTable(rows: Int, columns: Int, widthPt: Float): DocTable {
        val border = """<w:tblBorders><w:top w:val="single" w:sz="4" w:color="000000"/><w:left w:val="single" w:sz="4" w:color="000000"/><w:bottom w:val="single" w:sz="4" w:color="000000"/><w:right w:val="single" w:sz="4" w:color="000000"/><w:insideH w:val="single" w:sz="4" w:color="000000"/><w:insideV w:val="single" w:sz="4" w:color="000000"/></w:tblBorders>"""
        val n = columns.coerceAtLeast(1)
        val cell = DocCell("<w:tcPr/>", listOf(DocPara(listOf(DocRun("")))))
        val table = DocTable("<w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/>$border<w:tblLook w:val=\"04A0\"/></w:tblPr>", "<w:tblGrid/>",
            List(rows.coerceAtLeast(1)) { DocRow("", List(n) { cell.copy() }) })
        return withGrid(table, List(n) { widthPt * 20 / n })
    }

    /** A plain OpenDocument table: as wide as the text, equal columns, every cell lined (Writer's own styles). */
    fun newOdtTable(rows: Int, columns: Int, name: String) = DocTable(" table:name=\"${OfficeFormats.esc(name)}\" table:style-name=\"$ODT_TETRA_TABLE\"",
        "<table:table-column table:number-columns-repeated=\"${columns.coerceAtLeast(1)}\"/>",
        List(rows.coerceAtLeast(1)) { DocRow("", List(columns.coerceAtLeast(1)) { DocCell(" table:style-name=\"$ODT_TETRA_CELL\" office:value-type=\"string\"", listOf(DocPara(listOf(DocRun(""))))) }) })

    /** [cols] as the table's grid, and every cell as wide as the columns it spans (Word reads both). */
    private fun withGrid(t: DocTable, cols: List<Float>): DocTable {
        val grid = "<w:tblGrid>" + cols.joinToString("") { "<w:gridCol w:w=\"${it.roundToInt()}\"/>" } + "</w:tblGrid>"
        return t.copy(grid = grid, rows = t.rows.map { row ->
            var g = 0
            row.copy(cells = row.cells.map { c ->
                val w = cols.subList(g.coerceAtMost(cols.size), (g + span(c)).coerceAtMost(cols.size)).sum().roundToInt()
                g += span(c)
                c.copy(props = prop(TC_W.replace(c.props, ""), "<w:tcW w:w=\"$w\" w:type=\"dxa\"/>"))
            })
        })
    }

    /** A cell's first paragraph and first letter's look, with no text: what a new cell beside it starts as. */
    private fun blank(paras: List<DocPara>): List<DocPara> {
        val para = paras.firstOrNull { it.table == null } ?: DocPara(emptyList())
        val run = para.runs.firstOrNull { it.image == null } ?: DocRun("")
        return listOf(para.copy(runs = listOf(run.copy(text = "", image = null)), table = null))
    }
    private fun hasText(p: DocPara) = p.table != null || p.text.isNotEmpty() || p.runs.any { it.image != null }

    /** One cell on the grid: rows [r0] ‥ r0+h, grid columns [g0] ‥ g0+w; [cont] the continuing cells' own properties. */
    private class Block(var r0: Int, var g0: Int, var h: Int, var w: Int, var props: String, var paras: List<DocPara>,
        var cont: Map<Int, String> = emptyMap()) {
        fun covers(r: Int, g: Int) = r in r0 until r0 + h && g in g0 until g0 + w
    }
    private class Model(val table: DocTable, val rows: MutableList<String>, val blocks: MutableList<Block>, var n: Int, val cols: MutableList<Float>?,
        val odt: Boolean = false, val odtCols: MutableList<String>? = null /* one table-column each */) {
        fun at(r: Int, g: Int) = blocks.firstOrNull { it.covers(r, g) }
        fun inRow(r: Int) = blocks.filter { r in it.r0 until it.r0 + it.h }.sortedBy { it.g0 }
        /** The cells of row [r] the continuing rows keep, shifted when rows go in or out at [at] by [by]. */
        fun shiftRows(at: Int, by: Int) = blocks.forEach { b ->
            if (b.r0 >= at) b.r0 += by
            b.cont = b.cont.mapKeys { (k, _) -> if (k >= at) k + by else k }
        }
        /** A new row's properties, to go in at [at], from row [from]'s: not a heading row (in an .odt, one between heading rows is). */
        fun newRow(from: Int, at: Int) = if (!odt) HEADER.replace(rows[from], "")
            else rows[from].removePrefix(ODT_HEADER).let { if (rows.getOrNull(at)?.startsWith(ODT_HEADER) == true) ODT_HEADER + it else it }
        /** Column [k] of an .odt written twice (a new one beside it), or false when its columns are not plain ones. */
        fun copyColumn(k: Int, at: Int): Boolean { if (!odt) return true; val c = odtCols ?: return false; c.add(at, c[k.coerceIn(0, c.lastIndex)]); return true }
    }

    private fun odtModel(t: DocTable): Model? {
        val blocks = ArrayList<Block>()
        t.rows.forEachIndexed { r, row ->
            row.cells.forEachIndexed { g, c ->
                if (c.props.startsWith(ODT_COVERED)) return@forEachIndexed
                fun n(re: Regex) = re.find(c.props)?.groupValues?.get(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                blocks += Block(r, g, n(ODT_SPAN_R).coerceAtMost(t.rows.size - r), n(ODT_SPAN_C).coerceAtMost(row.cells.size - g),
                    ODT_SPAN_R.replace(ODT_SPAN_C.replace(c.props, ""), ""), c.paras)
            }
        }
        // Two cells claiming one place: not guessed at.
        val taken = HashSet<Pair<Int, Int>>()
        for (b in blocks) for (r in b.r0 until b.r0 + b.h) for (g in b.g0 until b.g0 + b.w) if (!taken.add(r to g)) return null
        val cols = ODT_COL.findAll(t.grid).flatMap { m ->
            val k = ODT_REPEAT.find(m.value)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 64) ?: 1
            List(k) { ODT_REPEAT.replace(m.value, "") }
        }.toMutableList().takeIf { ODT_COL.replace(t.grid, "").isBlank() && it.size == gridCount(t) }
        return Model(t, t.rows.map { it.props }.toMutableList(), blocks, gridCount(t), null, true, cols)
    }

    private fun model(t: DocTable): Model? {
        if (isOdt(t)) return odtModel(t)
        if (t.rows.any { GRID_OUT.containsMatchIn(it.props) }) return null // rows that start or end off the grid: not guessed at
        val blocks = ArrayList<Block>()
        t.rows.forEachIndexed { r, row ->
            var g = 0
            row.cells.forEach { c ->
                val s = span(c)
                val continuing = V_MERGE.find(c.props)?.value?.let { "restart" !in it } == true
                val above = if (continuing) blocks.firstOrNull { it.g0 == g && it.w == s && it.r0 + it.h == r } else null
                if (above != null) { above.h++; above.cont = above.cont + (r to c.props) }
                else blocks += Block(r, g, 1, s, c.props, c.paras)
                g += s
            }
        }
        val n = gridCount(t)
        return Model(t, t.rows.map { it.props }.toMutableList(), blocks, n, gridColumns(t).takeIf { it.size == n }?.toMutableList())
    }

    private fun Model.write(): DocTable {
        if (odt) return table.copy(grid = odtCols?.joinToString("") ?: table.grid, rows = rows.indices.map { r ->
            DocRow(rows[r], (0 until n).map { g ->
                val b = at(r, g)
                when {
                    b == null -> DocCell("", listOf(DocPara(emptyList())))
                    b.r0 == r && b.g0 == g -> DocCell(b.props + (if (b.w > 1) " table:number-columns-spanned=\"${b.w}\"" else "") +
                        (if (b.h > 1) " table:number-rows-spanned=\"${b.h}\"" else ""), b.paras)
                    else -> DocCell(ODT_COVERED, emptyList())
                }
            })
        })
        val out = rows.indices.map { r ->
            DocRow(rows[r], inRow(r).map { b ->
                if (r == b.r0) DocCell(withMerge(withSpan(b.props, b.w), if (b.h > 1) "restart" else null), b.paras)
                else DocCell(withMerge(withSpan(b.cont[r] ?: b.props, b.w), "continue"), blank(b.paras))
            })
        }
        val t = table.copy(rows = out)
        return cols?.let { withGrid(t, it) } ?: t
    }

    /** The block holding a row's cell (as the editor knows cells: row, and place in that row). */
    private fun Model.cell(row: Int, cell: Int) = table.rows.getOrNull(row)?.let { at(row, gridStart(it, cell.coerceAtMost(it.cells.size))) }

    /** A row put in above or below the row of [cell]; a cell merged across that line grows by one row. */
    fun insertRow(t: DocTable, row: Int, cell: Int, below: Boolean): DocTable? {
        val m = model(t) ?: return null
        val b = m.cell(row, cell) ?: return null
        val at = if (below) b.r0 + b.h else b.r0
        val like = if (below) at - 1 else at // the row the new one looks like
        val template = m.inRow(like)
        m.shiftRows(at, 1)
        val fresh = ArrayList<Block>()
        for (x in template) {
            if (x.r0 < at && x.r0 + x.h > at) { x.h++; x.cont = x.cont + (at to (x.cont[at + 1] ?: x.props)) } // straddles: taller
            else fresh += Block(at, x.g0, 1, x.w, x.props, blank(x.paras))
        }
        m.blocks += fresh
        m.rows.add(at, m.newRow(like, at))
        return m.write()
    }

    /** The row of [cell] taken out; a cell merged down from it keeps its text in the row below. */
    fun deleteRow(t: DocTable, row: Int, cell: Int): DocTable? {
        val m = model(t) ?: return null
        if (m.rows.size < 2) return null
        val r = m.cell(row, cell)?.let { row } ?: return null
        for (x in m.inRow(r)) {
            if (x.h == 1) m.blocks.remove(x) else { x.h--; x.cont = x.cont - r } // merged down: its text stays, one row shorter
        }
        m.blocks.forEach { x -> if (x.r0 > r) x.r0--; x.cont = x.cont.mapKeys { (k, _) -> if (k > r) k - 1 else k } }
        m.rows.removeAt(r)
        return m.write()
    }

    /** A column put in left or right of [cell], as wide as a share of the table (the others narrow to make room). */
    fun insertColumn(t: DocTable, row: Int, cell: Int, right: Boolean): DocTable? {
        val m = model(t) ?: return null
        val b = m.cell(row, cell) ?: return null
        val at = if (right) b.g0 + b.w else b.g0
        if (!m.copyColumn(if (right) at - 1 else at, at)) return null
        val straddling = m.blocks.filter { it.g0 < at && it.g0 + it.w > at }
        m.blocks.forEach { if (it.g0 >= at) it.g0++ }
        straddling.forEach { it.w++ }
        for (r in m.rows.indices) {
            if (straddling.any { r in it.r0 until it.r0 + it.h }) continue
            val row = m.inRow(r)
            val like = row.lastOrNull { it.g0 + it.w <= at } ?: row.firstOrNull() ?: continue
            m.blocks += Block(r, at, 1, 1, V_MERGE.replace(like.props, ""), blank(like.paras))
        }
        m.cols?.let { c -> val total = c.sum(); val k = m.n; c.replaceAll { it * k / (k + 1) }; c.add(at, total / (k + 1)) }
        m.n++
        return m.write()
    }

    /** The column of [cell] taken out (a cell spanning more loses one); the others widen to keep the table's width. */
    fun deleteColumn(t: DocTable, row: Int, cell: Int): DocTable? {
        val m = model(t) ?: return null
        if (m.n < 2) return null
        val g = m.cell(row, cell)?.let { if (it.w == 1) it.g0 else gridStart(t.rows[row], cell) } ?: return null
        m.blocks.filter { it.g0 <= g && it.g0 + it.w > g }.forEach { if (it.w == 1) m.blocks.remove(it) else it.w-- }
        m.blocks.forEach { if (it.g0 > g) it.g0-- }
        if (m.rows.indices.any { m.inRow(it).isEmpty() }) return null
        if (m.odt) m.odtCols?.removeAt(g) ?: return null
        m.cols?.let { c -> val total = c.sum(); c.removeAt(g); val left = c.sum(); if (left > 0f) c.replaceAll { it * total / left } }
        m.n--
        return m.write()
    }

    /** The grid rectangle from one cell to another, grown until no merged cell is cut through: rows, then columns. */
    private fun Model.rect(a: Block, b: Block): IntArray {
        val r = intArrayOf(minOf(a.r0, b.r0), minOf(a.g0, b.g0), maxOf(a.r0 + a.h, b.r0 + b.h), maxOf(a.g0 + a.w, b.g0 + b.w))
        do {
            var grew = false
            for (x in blocks) if (x.r0 < r[2] && x.r0 + x.h > r[0] && x.g0 < r[3] && x.g0 + x.w > r[1]) {
                if (x.r0 < r[0]) { r[0] = x.r0; grew = true }; if (x.g0 < r[1]) { r[1] = x.g0; grew = true }
                if (x.r0 + x.h > r[2]) { r[2] = x.r0 + x.h; grew = true }; if (x.g0 + x.w > r[3]) { r[3] = x.g0 + x.w; grew = true }
            }
        } while (grew)
        return r
    }

    /** The cells (row, place in row) from one cell to another, as merging them would take them — to show before merging. */
    fun cellsBetween(t: DocTable, from: Pair<Int, Int>, to: Pair<Int, Int>): Set<Pair<Int, Int>> {
        val m = model(t) ?: return emptySet()
        val a = m.cell(from.first, from.second) ?: return emptySet()
        val b = m.cell(to.first, to.second) ?: return emptySet()
        val r = m.rect(a, b)
        return t.rows.flatMapIndexed { ri, row -> row.cells.indices.filter { ci ->
            val g = gridStart(row, ci); ri in r[0] until r[2] && g in r[1] until r[3]
        }.map { ri to it } }.toSet()
    }

    /** Every cell from one cell to another made one, holding all their text in order; its look is the first one's. */
    fun merge(t: DocTable, from: Pair<Int, Int>, to: Pair<Int, Int>): DocTable? {
        val m = model(t) ?: return null
        val a = m.cell(from.first, from.second) ?: return null
        val b = m.cell(to.first, to.second) ?: return null
        val r = m.rect(a, b)
        val inside = m.blocks.filter { it.r0 >= r[0] && it.r0 + it.h <= r[2] && it.g0 >= r[1] && it.g0 + it.w <= r[3] }.sortedWith(compareBy({ it.r0 }, { it.g0 }))
        if (inside.size < 2) return null
        val first = inside.first()
        val text = inside.flatMap { x -> x.paras.filter(::hasText) }.ifEmpty { first.paras }
        // The rows below keep their own look for the merged cell's continuing part (its left cell's).
        val cont = (r[0] + 1 until r[2]).associateWith { row -> inside.first { it.covers(row, r[1]) }.let { x -> if (x.r0 == row) x.props else x.cont[row] ?: x.props } }
        m.blocks.removeAll(inside.toSet())
        m.blocks += Block(r[0], r[1], r[2] - r[0], r[3] - r[1], first.props, text, cont)
        return m.write()
    }

    /** How the cell of [cell] is merged: grid columns across, rows down. */
    fun size(t: DocTable, row: Int, cell: Int): Pair<Int, Int> = model(t)?.cell(row, cell)?.let { it.w to it.h } ?: (1 to 1)

    /**
     * The cell of [cell] split into [columns] × [rows] cells, its text in the first. A merged cell splits back along
     * the table's own lines; more columns or rows than it covers divide it evenly, the cells beside it growing across
     * the new lines so nothing else in the table moves. As Word's Split Cells.
     */
    fun split(t: DocTable, row: Int, cell: Int, columns: Int, rows: Int): DocTable? {
        val m = model(t) ?: return null
        val b = m.cell(row, cell) ?: return null
        if (columns < 1 || rows < 1 || columns * rows < 2) return null
        // Columns: new lines where the cell divides evenly; a grid column a line falls inside is divided there, every
        // cell covering it widening by one (so it looks the same) and the ones after it moving over.
        val colsW = m.cols ?: MutableList(m.n) { 1f } // no grid: taken as equal columns
        if (columns > b.w) {
            val x0 = colsW.take(b.g0).sum(); val width = colsW.subList(b.g0, b.g0 + b.w).sum()
            for (cut in (1 until columns).map { x0 + width * it / columns }) {
                var left = 0f
                for (k in colsW.indices) {
                    val right = left + colsW[k]
                    if (cut > left + 0.5f && cut < right - 0.5f) {
                        if (!m.copyColumn(k, k + 1)) return null
                        m.blocks.forEach { x -> if (x.g0 > k) x.g0++ else if (x.g0 + x.w > k) x.w++ }
                        colsW[k] = cut - left; colsW.add(k + 1, right - cut); m.n++
                        break
                    }
                    left = right
                }
            }
        }
        // Rows: more than it covers — rows added under it, the cells beside it growing down across them.
        while (b.h < rows) {
            val at = b.r0 + b.h
            val beside = m.inRow(at - 1)
            m.shiftRows(at, 1)
            beside.forEach { x -> x.h++; x.cont = x.cont + (at to (x.cont[at - 1] ?: x.props)) }
            m.rows.add(at, m.newRow(at - 1, at))
        }
        // The cell's lines now: grid columns grouped as evenly as its width allows; rows the same.
        val x0 = colsW.take(b.g0).sum(); val width = colsW.subList(b.g0, b.g0 + b.w).sum()
        val edges = (0..columns).map { i -> if (i == columns) b.g0 + b.w else {
            val x = x0 + width * i / columns
            (b.g0..b.g0 + b.w).minBy { g -> kotlin.math.abs(colsW.take(g).sum() - x) } } }.distinct()
        val rowEdges = (0..rows).map { b.r0 + b.h * it / rows }.distinct()
        if (edges.size < 2 || rowEdges.size < 2) return null
        m.blocks.remove(b)
        val empty = blank(b.paras)
        rowEdges.zipWithNext().forEachIndexed { i, (ra, rb) ->
            edges.zipWithNext().forEachIndexed { j, (ga, gb) ->
                val props = if (ra == b.r0) b.props else b.cont[ra] ?: b.props
                m.blocks += Block(ra, ga, rb - ra, gb - ga, props, if (i == 0 && j == 0) b.paras else empty,
                    (ra + 1 until rb).associateWith { b.cont[it] ?: b.props })
            }
        }
        return m.write()
    }

    // ——— Properties: the ⋯ page's Table · Row · Column · Cell · Borders tabs, and the ribbon's styles and shading ———

    private fun without(raw: String, name: String) = raw.replace(Regex("""<w:$name\b[^>]*/>|<w:$name\b[^>]*>[\s\S]*?</w:$name>"""), "")
    /** A cell's property [name] set to [xml] (or taken off when null), in its place among the others. */
    fun cellProp(props: String, name: String, xml: String?): String {
        val rest = without(props, name).let { if (it.isEmpty() || it == "<w:tcPr/>") "<w:tcPr></w:tcPr>" else it }
        return (if (xml == null) rest else prop(rest, xml)).replace("<w:tcPr></w:tcPr>", "<w:tcPr/>")
    }
    fun cellPropOf(props: String, name: String): String? = Regex("""<w:$name\b[^>]*/>|<w:$name\b[^>]*>[\s\S]*?</w:$name>""").find(props)?.value
    private fun attr(xml: String?, name: String) = xml?.let { Regex("""w:$name="([^"]*)"""").find(it)?.groupValues?.get(1) }

    /** A row's property: any order is Word's for these. */
    fun rowProp(props: String, name: String, xml: String?): String {
        val inner = without(props.removePrefix("<w:trPr>").removeSuffix("</w:trPr>").let { if (it == "<w:trPr/>") "" else it }, name) + (xml ?: "")
        return if (inner.isEmpty()) "" else "<w:trPr>$inner</w:trPr>"
    }
    fun rowPropOf(props: String, name: String) = cellPropOf(props, name)

    private val TBL_ORDER = listOf("tblStyle", "tblpPr", "tblOverlap", "bidiVisual", "tblStyleRowBandSize", "tblStyleColBandSize", "tblW", "jc", "tblCellSpacing", "tblInd", "tblBorders", "shd", "tblLayout", "tblCellMar", "tblLook", "tblCaption", "tblDescription")
    /** The table's own property, in Word's order. */
    fun tableProp(props: String, name: String, xml: String?): String {
        var raw = without(props, name).let { if (it.isEmpty() || it == "<w:tblPr/>") "<w:tblPr></w:tblPr>" else it }
        if (xml != null) {
            val later = TBL_ORDER.drop(TBL_ORDER.indexOf(name) + 1).mapNotNull { n -> Regex("""<w:$n[\s/>]""").find(raw)?.range?.first }.minOrNull()
            val at = later ?: raw.lastIndexOf("</w:tblPr>")
            raw = raw.substring(0, at) + xml + raw.substring(at)
        }
        return raw
    }

    /** Every cell in [cells] (row, place in row) changed by [f]; a merged cell's continuing parts go with it. */
    fun withCells(t: DocTable, cells: Set<Pair<Int, Int>>, f: (DocCell) -> DocCell): DocTable =
        t.copy(rows = t.rows.mapIndexed { r, row -> row.copy(cells = row.cells.mapIndexed { c, cell -> if ((r to c) in cells) f(cell) else cell }) })

    /** The cells a cell stands for on the page: itself and, merged down, the parts under it. */
    fun wholeCell(t: DocTable, row: Int, cell: Int): Set<Pair<Int, Int>> = cellsBetween(t, row to cell, row to cell).ifEmpty { setOf(row to cell) }
    fun allCells(t: DocTable) = t.rows.flatMapIndexed { r, row -> row.cells.indices.map { r to it } }.toSet()
    fun rowCells(t: DocTable, row: Int) = t.rows.getOrNull(row)?.cells?.indices?.map { row to it }?.toSet().orEmpty()
    fun columnCells(t: DocTable, row: Int, cell: Int): Set<Pair<Int, Int>> {
        val g = t.rows.getOrNull(row)?.let { gridStart(it, cell) } ?: return emptySet()
        return t.rows.flatMapIndexed { r, rw -> rw.cells.indices.filter { c -> val s = gridStart(rw, c); g in s until s + span(rw.cells[c]) }.map { r to it } }.toSet()
    }

    fun vAlign(props: String) = attr(cellPropOf(props, "vAlign"), "val") ?: "top"
    fun withVAlign(props: String, v: String) = cellProp(props, "vAlign", if (v == "top") null else "<w:vAlign w:val=\"$v\"/>")
    /** Text direction: null across, "btLr" read bottom to top, "tbRl" top to bottom. */
    fun textDirection(props: String) = attr(cellPropOf(props, "textDirection"), "val")?.takeIf { it != "lrTb" }
    fun withTextDirection(props: String, dir: String?) = cellProp(props, "textDirection", dir?.let { "<w:textDirection w:val=\"$it\"/>" })
    fun shading(props: String): Int? = attr(cellPropOf(props, "shd"), "fill")?.takeIf { it.length == 6 }?.toIntOrNull(16)
    fun withShading(props: String, fill: Int?) = cellProp(props, "shd", fill?.let { "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"%06X\"/>".format(it) })

    /** A border line in Word's words: [sz] eighths of a point; null no line. */
    data class Line(val color: Int, val sz: Int)
    private fun edge(name: String, l: Line?) = if (l == null) "<w:$name w:val=\"nil\"/>" else "<w:$name w:val=\"single\" w:sz=\"${l.sz}\" w:space=\"0\" w:color=\"%06X\"/>".format(l.color)
    /** The table's lines: its outside and the lines between its cells. The cells' own lines give way to them. */
    fun withTableBorders(t: DocTable, outside: Line?, inside: Line?, insideH: Boolean = true, insideV: Boolean = true): DocTable {
        val xml = "<w:tblBorders>" + edge("top", outside) + edge("left", outside) + edge("bottom", outside) + edge("right", outside) +
            edge("insideH", inside.takeIf { insideH }) + edge("insideV", inside.takeIf { insideV }) + "</w:tblBorders>"
        return withCells(t.copy(props = tableProp(t.props, "tblBorders", xml)), allCells(t)) { it.copy(props = cellProp(it.props, "tcBorders", null)) }
    }
    /** One cell's lines on its four sides, over the table's. */
    fun withCellBorders(props: String, line: Line?) = cellProp(props, "tcBorders",
        "<w:tcBorders>" + edge("top", line) + edge("left", line) + edge("bottom", line) + edge("right", line) + "</w:tcBorders>")

    fun rowHeight(props: String): Pair<Float, String>? = rowPropOf(props, "trHeight")?.let { x -> attr(x, "val")?.toFloatOrNull()?.let { it / 20 to (attr(x, "hRule") ?: "atLeast") } }
    /** A row's height in points: [rule] "atLeast" (grows with its text) or "exact"; null as tall as its text. */
    fun withRowHeight(props: String, pt: Float?, rule: String) = rowProp(props, "trHeight", pt?.let { "<w:trHeight w:val=\"${(it * 20).roundToInt()}\" w:hRule=\"$rule\"/>" })
    fun isHeader(props: String) = rowPropOf(props, "tblHeader").let { it != null && attr(it, "val").let { v -> v == null || v == "1" || v == "true" } }
    fun withHeader(props: String, on: Boolean) = rowProp(props, "tblHeader", if (on) "<w:tblHeader/>" else null)
    fun keepsTogether(props: String) = rowPropOf(props, "cantSplit") != null
    fun withKeepTogether(props: String, on: Boolean) = rowProp(props, "cantSplit", if (on) "<w:cantSplit/>" else null)

    /** Left, center or right on the page. */
    fun align(t: DocTable) = attr(cellPropOf(t.props, "jc"), "val")?.let { if (it == "start") "left" else if (it == "end") "right" else it } ?: "left"
    fun withAlign(t: DocTable, a: String) = t.copy(props = tableProp(t.props, "jc", if (a == "left") null else "<w:jc w:val=\"$a\"/>"))

    /** The width (pt) of the grid column [cell] starts in; set, the other columns share the difference. */
    fun columnWidth(t: DocTable, row: Int, cell: Int): Float? = gridColumns(t).getOrNull(t.rows.getOrNull(row)?.let { gridStart(it, cell) } ?: -1)?.div(20)
    fun withColumnWidth(t: DocTable, row: Int, cell: Int, pt: Float): DocTable {
        val cols = gridColumns(t).toMutableList()
        val g = t.rows.getOrNull(row)?.let { gridStart(it, cell) } ?: return t
        if (g !in cols.indices || cols.size < 2) return t
        val total = cols.sum(); val want = (pt * 20).coerceIn(200f, total - 200f * (cols.size - 1))
        val others = total - cols[g]; val left = total - want
        cols.indices.forEach { if (it == g) cols[it] = want else cols[it] = cols[it] * left / others }
        return withGrid(t, cols)
    }

    /**
     * Ready-made looks (a first set; asked 2026-09-28 "styles, colours"): written as the cells' own shading and lines
     * and the first row's text, so Word, LibreOffice and the pages all show the same without the table style part.
     */
    class Look(val name: String, @androidx.annotation.StringRes val labelRes: Int, val header: Int?, val headerText: Int?, val band: Int?, val line: Int, val inside: Boolean = true)
    val LOOKS = listOf(
        Look("Plain", R.string.office_look_plain, null, null, null, 0x000000),
        Look("Blue", R.string.office_look_blue, 0x4472C4, 0xFFFFFF, 0xD9E2F3, 0x8EAADB),
        Look("Green", R.string.office_look_green, 0x70AD47, 0xFFFFFF, 0xE2EFD9, 0xA8D08D),
        Look("Orange", R.string.office_look_orange, 0xED7D31, 0xFFFFFF, 0xFBE4D5, 0xF4B083),
        Look("Grey", R.string.office_look_grey, 0x7F7F7F, 0xFFFFFF, 0xEDEDED, 0xBFBFBF),
        Look("Lines", R.string.office_look_lines, null, null, null, 0x7F7F7F, inside = false),
    )
    fun withLook(t: DocTable, look: Look): DocTable {
        val line = Line(look.line, 4)
        val lined = withTableBorders(t, line, line, insideH = true, insideV = look.inside)
        return lined.copy(rows = lined.rows.mapIndexed { r, row -> row.copy(cells = row.cells.map { c ->
            val fill = if (r == 0) look.header else if (r % 2 == 1) look.band else null // the first row under the header banded, as Word
            val paras = if (r != 0) c.paras else c.paras.map { p -> p.copy(runs = p.runs.map { it.copy(bold = look.header != null || it.bold, color = look.headerText) }) }
            c.copy(props = withShading(c.props, fill), paras = paras)
        }) })
    }
}
