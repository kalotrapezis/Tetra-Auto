package com.kalotrapezis.drive

import java.text.Collator
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The viewer's own ways of looking at a sheet (asked 30 Sep): rows filtered by a column's values, sorted by a column,
 * and columns pinned side by side at the start — none of them changes the file. A [SheetLayout] is the order the rows
 * and columns are drawn in.
 */
internal data class SheetSort(val col: Int, val ascending: Boolean)

internal class SheetLayout(
    /** The sheet's rows and columns in the order drawn (rows a filter leaves out are not in it). */
    val rows: IntArray, val cols: IntArray,
    val frozenRows: Int, val frozenCols: Int,
) {
    private val rowAt = reverse(rows); private val colAt = reverse(cols)
    /** Where sheet row [r] is drawn; -1: not drawn. */
    fun row(r: Int) = if (r in rowAt.indices) rowAt[r] else -1
    fun col(c: Int) = if (c in colAt.indices) colAt[c] else -1

    companion object {
        private fun reverse(a: IntArray) = IntArray((a.maxOrNull() ?: -1) + 1) { -1 }.also { r -> a.forEachIndexed { i, v -> r[v] = i } }

        /** Empty cells past the used ones, as Excel's sheet goes on. */
        const val MORE_ROWS = 60
        const val MORE_COLS = 12

        /** [freeze]: the rows kept at the top, as the viewer chose them (null: the file's frozen rows). */
        fun of(sheet: Sheet, pins: List<Int> = emptyList(), filters: Map<Int, Set<String>> = emptyMap(), sort: SheetSort? = null, freeze: Int? = null): SheetLayout {
            val frozenRows = freeze ?: sheet.frozenRows
            // As far as the cells and the drawings over them go.
            val drawnCols = sheet.drawings.maxOfOrNull { drawingEnd(sheet, it).second + 1 } ?: 0
            val drawnRows = sheet.drawings.maxOfOrNull { drawingEnd(sheet, it).first + 1 } ?: 0
            val totalCols = maxOf(sheet.cols, sheet.frozenCols, drawnCols) + MORE_COLS
            val totalRows = maxOf(sheet.rows, sheet.frozenRows, drawnRows) + MORE_ROWS
            // Columns: the pinned ones in the order pinned, then the file's frozen ones, then the rest.
            val pinned = pins.filter { it in 0 until totalCols }.distinct()
            val frozen = (0 until sheet.frozenCols).filter { it !in pinned }
            val cols = (pinned + frozen + (sheet.frozenCols until totalCols).filter { it !in pinned }).toIntArray()
            val frozenCols = pinned.size + frozen.size
            if (filters.isEmpty() && sort == null) return SheetLayout(IntArray(totalRows) { it }, cols, frozenRows, frozenCols)
            // Rows: the header and what is above it stay; the table's rows below it are filtered, then sorted.
            val header = header(sheet)
            val end = sheet.autoFilter?.r2?.plus(1)?.coerceAtMost(totalRows) ?: sheet.rows
            val data = (header + 1 until end).filter { r -> filters.all { (c, keep) -> value(sheet[r, c]) in keep } }
            val sorted = sort?.let { s -> data.sortedWith(order(sheet, s)) } ?: data
            val rows = ((0..header).toList() + sorted + (end until totalRows)).toIntArray()
            return SheetLayout(rows, cols, min(frozenRows, rows.size), frozenCols)
        }

        /**
         * The table's header row: the file's AutoFilter's first row, else the last frozen row, else the first of the
         * top 50 rows that is nearly as full as the fullest (a title above a table has one cell, its header many).
         */
        fun header(sheet: Sheet): Int {
            sheet.autoFilter?.let { return it.r1 }
            if (sheet.frozenRows > 0) return sheet.frozenRows - 1
            val counts = IntArray(min(50, sheet.rows))
            sheet.cells.forEach { k, cell -> val r = Sheet.row(k); if (r < counts.size && cell.text.isNotBlank()) counts[r]++ }
            val most = counts.maxOrNull() ?: return 0
            return counts.indexOfFirst { it * 10 >= most * 6 }.coerceAtLeast(0)
        }

        /** A cell as a filter lists it: what it shows, trimmed; "" for an empty one. */
        fun value(cell: SheetCell?) = cell?.text?.trim().orEmpty()

        /** Numbers by value, words as the phone's language orders them (Greek and English), empty cells last. */
        fun order(sheet: Sheet, s: SheetSort): Comparator<Int> {
            val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.SECONDARY }
            return Comparator { a, b ->
                val x = sheet[a, s.col]; val y = sheet[b, s.col]
                val ex = value(x).isEmpty(); val ey = value(y).isEmpty()
                if (ex || ey) return@Comparator if (ex && ey) 0 else if (ex) 1 else -1 // empty last, either way
                val nx = x?.value?.takeIf { x.kind == SheetKind.Number }; val ny = y?.value?.takeIf { y.kind == SheetKind.Number }
                val c = when {
                    nx != null && ny != null -> nx.compareTo(ny)
                    nx != null -> -1 // numbers before words, as Excel
                    ny != null -> 1
                    else -> collator.compare(value(x), value(y))
                }
                if (s.ascending) c else -c
            }
        }

        /** A column's values under the header for its filter: each with how many rows have it, in the sort's order. */
        fun values(sheet: Sheet, col: Int): List<Pair<String, Int>> {
            val header = header(sheet)
            val end = sheet.autoFilter?.r2?.plus(1) ?: sheet.rows
            val counts = LinkedHashMap<String, Int>()
            val first = HashMap<String, Int>()
            for (r in header + 1 until end) { val v = value(sheet[r, col]); counts[v] = (counts[v] ?: 0) + 1; first.putIfAbsent(v, r) }
            val byRow = order(sheet, SheetSort(col, true))
            return counts.entries.sortedWith { a, b -> byRow.compare(first.getValue(a.key), first.getValue(b.key)) }.map { it.key to it.value }
        }
    }
}

/**
 * How a sheet prints (Excel's Page Layout): portrait or landscape, a scale or "fit to N pages wide/tall", the margins in
 * inches (left, right, top, bottom), gridlines or not (Excel prints none unless asked), the print area, the rows
 * printed at the top of every page.
 */
internal data class SheetPrint(
    val landscape: Boolean = false, val scale: Int = 100, val fitWidth: Int? = null, val fitHeight: Int? = null,
    val grid: Boolean = false, val margins: List<Float> = listOf(0.7f, 0.7f, 0.75f, 0.75f),
    val area: SheetRange? = null, val titles: IntRange? = null, val letter: Boolean = false,
    /** The pages' header and footer, as Excel's codes ("&CPage &P"): [SheetHeaderFooter]. */
    val header: String? = null, val footer: String? = null,
)

/** The last row and column a drawing covers: its end cell, or as far as its size reaches through the rows' heights and columns' widths. */
internal fun drawingEnd(sheet: Sheet, d: SheetDrawing): Pair<Int, Int> {
    if (d.toRow >= 0) return d.toRow to d.toCol
    var r = d.row; var h = d.dy + d.height
    while (h > sheet.height(r) && r < d.row + 10_000) { h -= sheet.height(r); r++ }
    var c = d.col; var w = d.dx + d.width
    while (w > sheet.width(c) && c < 16383) { w -= sheet.width(c); c++ }
    return r to c
}

/** A printed page: the rows and columns on it (as drawn), and the title rows above them. */
internal data class SheetPage(val rows: IntRange, val cols: IntRange, val titles: IntRange?)

/** The pages: their size and margins in points, how many points a sheet pixel takes, each page's cells. */
internal class SheetPages(val width: Float, val height: Float, val left: Float, val top: Float, val k: Float, val pages: List<SheetPage>)

/**
 * A sheet cut into pages as Excel prints it: the print area (else every used cell, as drawn: a filter's hidden rows are
 * not printed), at the file's scale or fitted to its pages, cut at whole columns and rows, down then over.
 */
internal fun sheetPages(sheet: Sheet, layout: SheetLayout, print: SheetPrint = sheet.print): SheetPages {
    var w = if (print.letter) 612f else 595f; var h = if (print.letter) 792f else 842f
    if (print.landscape) { val t = w; w = h; h = t }
    val (ml, mr, mt, mb) = print.margins.map { it * 72 }
    val pw = w - ml - mr; val ph = h - mt - mb
    // The cells to print, as drawn.
    val area = print.area
    val usedRows = max(sheet.rows, sheet.drawings.maxOfOrNull { drawingEnd(sheet, it).first + 1 } ?: 0)
    val usedCols = max(sheet.cols, sheet.drawings.maxOfOrNull { drawingEnd(sheet, it).second + 1 } ?: 0)
    val rows = layout.rows.indices.filter { d -> val r = layout.rows[d]; if (area != null) r in area.r1..area.r2 else r < usedRows }
    val cols = layout.cols.indices.filter { d -> val c = layout.cols[d]; if (area != null) c in area.c1..area.c2 else c < usedCols }
    if (rows.isEmpty() || cols.isEmpty()) return SheetPages(w, h, ml, mt, 0.75f, emptyList())
    val titles = print.titles?.let { t -> (t.first..t.last).map(layout::row).filter { it >= 0 } }?.takeIf { it.isNotEmpty() }?.let { it.min()..it.max() }
    fun width(d: Int) = sheet.width(layout.cols[d])
    fun height(d: Int) = sheet.height(layout.rows[d])
    val body = rows.filter { titles == null || it !in titles }
    val titleH = titles?.sumOf { height(it).toDouble() }?.toFloat() ?: 0f
    // Points a sheet pixel takes: 3/4 at 100 %; fitted, as small as the pages asked for need (never under 10 %).
    var k = 0.75f * print.scale / 100
    if (print.fitWidth != null || print.fitHeight != null) {
        k = 0.75f
        print.fitWidth?.takeIf { it > 0 }?.let { n -> k = min(k, pw * n / cols.sumOf { width(it).toDouble() }.toFloat()) }
        print.fitHeight?.takeIf { it > 0 }?.let { n -> k = min(k, ph * n / (body.sumOf { height(it).toDouble() }.toFloat() + titleH * n)) }
        k = max(k, 0.075f)
    }
    /** Whole columns or rows to a page: as many as fit, at least one. */
    fun bands(list: List<Int>, size: (Int) -> Float, room: Float): List<IntRange> {
        val out = ArrayList<IntRange>(); var start = 0; var used = 0f
        for (i in list.indices) {
            val s = size(list[i]) * k
            if (i > start && used + s > room + 0.01f) { out += list[start]..list[i - 1]; start = i; used = 0f }
            used += s
        }
        if (start < list.size) out += list[start]..list.last()
        return out
    }
    val colBands = bands(cols, ::width, pw)
    val rowBands = bands(body, ::height, ph - titleH * k)
    return SheetPages(w, h, ml, mt, k, colBands.flatMap { c -> rowBands.map { r -> SheetPage(r, c, titles) } })
}

/** The chosen cells as text to paste: a tab between cells, a new line between rows (Excel's and LibreOffice's own way). */
internal fun sheetText(sheet: Sheet, layout: SheetLayout, range: SheetRange): String = buildString {
    for (r in range.r1..min(range.r2, layout.rows.lastIndex)) {
        if (r > range.r1) append('\n')
        for (c in range.c1..min(range.c2, layout.cols.lastIndex)) {
            if (c > range.c1) append('\t')
            append(sheet[layout.rows[r], layout.cols[c]]?.text.orEmpty().replace('\n', ' ').replace('\t', ' '))
        }
    }
}.trimEnd('\n', '\t')

/** What Excel's status bar says of the chosen cells: how many have something, and of the numbers their sum, average, least and most. */
internal data class SheetStats(val count: Int, val numbers: Int, val sum: Double, val min: Double, val max: Double) {
    val average get() = if (numbers == 0) 0.0 else sum / numbers

    companion object {
        /** The cells of a range as drawn ([layout]'s rows and columns: a filter's hidden rows are not counted). */
        fun of(sheet: Sheet, layout: SheetLayout, range: SheetRange): SheetStats {
            var count = 0; var numbers = 0; var sum = 0.0; var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
            for (r in range.r1..min(range.r2, layout.rows.lastIndex)) for (c in range.c1..min(range.c2, layout.cols.lastIndex)) {
                val cell = sheet[layout.rows[r], layout.cols[c]] ?: continue
                if (cell.text.isEmpty()) continue
                count++
                if (cell.kind == SheetKind.Number) cell.value?.let { numbers++; sum += it; lo = min(lo, it); hi = max(hi, it) }
            }
            return SheetStats(count, numbers, sum, if (numbers > 0) lo else 0.0, if (numbers > 0) hi else 0.0)
        }
    }
}

/**
 * Search across a sheet's cells (asked 30 Sep: "typos and accents forgiven, Greek and English"): letters compared
 * without accents or case (ς as σ), every word of the search must be in the cell, and a word of four letters or more
 * may be one letter off (seven or more: two) — missing, extra, wrong or swapped.
 */
internal object SheetSearch {
    private val MARKS = Regex("\\p{Mn}+")
    private val WORD = Regex("[\\p{L}\\p{N}]+")

    private val PLAIN = HashMap<Char, Char>().apply {
        "άα έε ήη ίι όο ύυ ώω ϊι ϋυ ΐι ΰυ ςσ".split(' ').forEach { put(it[0], it[1]) }
    }

    /**
     * Letters as search compares them: small, without accents, ς as σ. Greek and plain Latin by a table (a sheet has
     * millions of cells: Unicode's decomposition for each took most of a search); other letters by decomposition.
     */
    fun norm(s: String): String {
        var plain = true
        val out = CharArray(s.length)
        for (i in s.indices) {
            val ch = Character.toLowerCase(s[i])
            out[i] = PLAIN[ch] ?: ch
            if (out[i].code in 0xC0..0x24F || out[i].code in 0x1E00..0x1EFF) plain = false // Latin with accents
        }
        val t = String(out)
        return if (plain) t else Normalizer.normalize(t, Normalizer.Form.NFD).replace(MARKS, "")
    }

    fun words(query: String): List<String> = WORD.findAll(norm(query)).map { it.value }.toList()

    /** Whether a cell's (normalised) text has every one of the (normalised) [words]. */
    fun matches(text: String, words: List<String>): Boolean {
        if (words.isEmpty()) return false
        var cellWords: List<String>? = null
        return words.all { w ->
            if (text.contains(w)) return@all true
            // Numbers are exact: a typo forgiven in 17351 would find 17951.
            val allowed = if (w.any { it.isDigit() }) 0 else if (w.length >= 7) 2 else if (w.length >= 4) 1 else 0
            if (allowed == 0) return@all false
            (cellWords ?: WORD.findAll(text).map { it.value }.toList().also { cellWords = it }).any { t ->
                // the whole word, or its start (a word being typed: "παπαδοπ" finds "Παπαδόπουλος") — after a quick
                // count of shared letters, which turns most words away before the full comparison
                t.length >= w.length - allowed && shared(w, t) >= w.length - allowed &&
                    ((abs(t.length - w.length) <= allowed && distance(w, t) <= allowed) || (t.length > w.length && distance(w, t.substring(0, w.length)) <= allowed))
            }
        }
    }

    /** The cells of [sheet] that match [query], row by row. */
    fun find(sheet: Sheet, query: String): List<Long> {
        val words = words(query)
        if (words.isEmpty()) return emptyList()
        val out = ArrayList<Long>()
        val general = SheetFormat()
        sheet.cells.forEach { k, cell ->
            if (cell.text.isEmpty()) return@forEach
            // A number is found by its digits as well as as it is shown ("1234" finds "1.234,50").
            val text = norm(cell.text) + (cell.value?.takeIf { cell.kind == SheetKind.Number }?.let { " " + general.general(it) } ?: "")
            if (matches(text, words)) out += k
        }
        out.sort() // the key is the row, then the column
        return out
    }

    /** A found cell's row for a list of finds: the row's other written cells, left to right (a name's surname, rank…). */
    fun context(sheet: Sheet, key: Long, most: Int = 4): String {
        val r = Sheet.row(key); val col = Sheet.col(key)
        val out = ArrayList<String>()
        for (c in 0 until sheet.cols) {
            if (c == col) continue
            val t = sheet[r, c]?.text?.trim().orEmpty()
            if (t.isNotEmpty()) out += t.replace('\n', ' ')
            if (out.size >= most) break
        }
        return out.joinToString(" · ")
    }

    /** How many of [w]'s letters [t] has too (each counted as often as it is in both). */
    private fun shared(w: String, t: String): Int {
        val counts = IntArray(64)
        for (ch in t) counts[ch.code and 63]++
        var n = 0
        for (ch in w) { val k = ch.code and 63; if (counts[k] > 0) { counts[k]--; n++ } }
        return n
    }

    /** Letters to change, add, drop or swap to make [a] into [b] (optimal string alignment). */
    fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = min(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }
}
