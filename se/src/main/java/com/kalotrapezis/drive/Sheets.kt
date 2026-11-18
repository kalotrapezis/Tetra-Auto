package com.kalotrapezis.drive

import org.w3c.dom.Element
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import javax.xml.parsers.SAXParserFactory
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A failure the user is told of in the screen's language: its string and that string's arguments ([said] makes the words). */
internal class SheetProblem(@androidx.annotation.StringRes val text: Int, vararg val args: Any) : IllegalStateException("Tables problem, string $text ${args.joinToString()}")
internal fun sheetProblem(@androidx.annotation.StringRes text: Int, vararg args: Any): Nothing = throw SheetProblem(text, *args)
/** What to tell the user of a failure: a [SheetProblem]'s words, else its own message. */
internal fun Throwable.said(context: android.content.Context): String? = if (this is SheetProblem) context.getString(text, *args) else officeMessage(context)

/**
 * Tetra's spreadsheets (the user, 15 Oct: "start with the viewer and let's complete it like we did with Word"): an
 * .xlsx, .ods or .csv read into sheets of cells, each cell as the file shows it — its text already formatted (Excel's
 * number formats, [SheetFormat]; an .ods carries the shown text itself), its looks, the columns' widths and rows'
 * heights, merged cells, frozen panes. Sizes are in pixels at 100 % (Excel's own unit: a default column is 64, a row 20).
 */
internal enum class SheetAlign { General, Left, Center, Right, Justify }
internal enum class SheetVAlign { Top, Center, Bottom }
internal enum class SheetKind { Number, Text, Bool, Error, Empty }

/**
 * A differential look (Excel's dxf): what a table's style changes in a cell — null leaves it be. [horizontal] and
 * [vertical] are a table's inner lines.
 */
internal data class SheetDxf(
    val fill: Int? = null, val color: Int? = null, val bold: Boolean? = null, val italic: Boolean? = null,
    val left: SheetLine? = null, val right: SheetLine? = null, val top: SheetLine? = null, val bottom: SheetLine? = null,
    val horizontal: SheetLine? = null, val vertical: SheetLine? = null,
) {
    /** [over] laid on this one: its set parts win. */
    operator fun plus(over: SheetDxf) = SheetDxf(
        over.fill ?: fill, over.color ?: color, over.bold ?: bold, over.italic ?: italic, over.left ?: left, over.right ?: right,
        over.top ?: top, over.bottom ?: bottom, over.horizontal ?: horizontal, over.vertical ?: vertical,
    )
}

/**
 * A picture, chart or text box over the cells: from a cell ([row], [col]) and a distance into it, to another cell and a
 * distance into that ([toRow] -1: then [width] × [height] from the start), in pixels. A picture's bytes are read when
 * it is first drawn.
 */
internal class SheetDrawing(
    val row: Int, val col: Int, val dx: Float, val dy: Float,
    val toRow: Int = -1, val toCol: Int = -1, val toDx: Float = 0f, val toDy: Float = 0f,
    val width: Float = 0f, val height: Float = 0f,
    val chart: DocChart? = null, val image: (() -> ByteArray?)? = null, val text: String? = null, val fill: Int? = null, val line: Int? = null,
    /** A shape in its own outline (an arrow, a star: Excel's presets, LibreOffice's paths); null: a box. */
    val shape: ShapePicture? = null, val textColor: Int? = null, /** its words in the middle (Excel's shapes), not from the top left (a text box) */ val centred: Boolean = false,
    /** A picture's crop (a:srcRect), as [Slides.crop] takes it. */ val crop: FloatArray? = null,
) {
    /** Its place among the file's drawings (Excel's anchors in the sheet's drawing; LibreOffice's frames and shapes in the sheet), to change it by. */
    var ordinal = -1
    /** Its words' size (pt) and weight, and WordArt's effects and bend (Excel's run and bodyPr; LibreOffice's text style and fontwork). */
    var textPt: Float? = null; var bold = false; var effects = TextEffects(); var warp: TextWarp? = null
}

/**
 * A drop-down list on cells (Excel's Data Validation › List, LibreOffice's Validity › Cell range or list): its values, or
 * where they are ([source], Excel's "Sheet!A1:A5" form) when another sheet holds them.
 */
internal class SheetList(val ranges: List<SheetRange>, val items: List<String>, val source: String? = null) {
    companion object {
        /** LibreOffice's list validity (of:cell-content-is-in-list("a";"b") or a range) in Excel's formula1 form; null for any other. */
        fun odsList(condition: String): String? {
            val inner = Regex("cell-content-is-in-list\\((.*)\\)").find(condition)?.groupValues?.get(1)?.trim() ?: return null
            if (inner.startsWith("\"")) return "\"" + Regex("\"((?:[^\"]|\"\")*)\"").findAll(inner).joinToString(",") { it.groupValues[1] } + "\""
            val parts = inner.trim('[', ']').split(':').map { it.replace("$", "") }
            val sheet = parts[0].substringBeforeLast('.', "").trim('\'')
            return (if (sheet.isNotEmpty()) "'$sheet'!" else "") + parts.joinToString(":") { it.substringAfterLast('.') }
        }

        /** Excel's formula1 ("\"Yes,No\"", or a range) as values, the range's read from [cells] when it is this sheet's ([own] its name). */
        fun of(ranges: List<SheetRange>, formula: String, own: String, cells: (Int, Int) -> String?): SheetList? {
            val f = formula.trim().removePrefix("=")
            if (f.startsWith("\"")) return SheetList(ranges, f.removePrefix("\"").removeSuffix("\"").split(',').map { it.trim().replace("\"\"", "\"") }.filter { it.isNotEmpty() })
            val sheet = f.substringBefore('!', "").trim('\'').replace("''", "'")
            val g = Sheets.rangeRef(f.substringAfter('!').replace("$", "")) ?: return null
            // shortcut: another sheet's values are not looked up (no chips, nothing refused); upgrade if such lists are met
            if (sheet.isNotEmpty() && !sheet.equals(own, true)) return SheetList(ranges, emptyList(), f)
            return SheetList(ranges, (g.r1..minOf(g.r2, g.r1 + 999)).flatMap { r -> (g.c1..g.c2).mapNotNull { c -> cells(r, c)?.takeIf { it.isNotBlank() } } }, f)
        }
    }
}

/** A cell's border edge: colour, width in pixels, double or dashed. */
internal data class SheetLine(val color: Int, val width: Float, val double: Boolean = false, val dashed: Boolean = false, val dots: Boolean = false)

internal data class SheetStyle(
    val font: String? = null, val size: Float = 11f, val bold: Boolean = false, val italic: Boolean = false,
    val underline: Boolean = false, val strike: Boolean = false, val color: Int? = null, val fill: Int? = null,
    val align: SheetAlign = SheetAlign.General, val valign: SheetVAlign = SheetVAlign.Bottom, val wrap: Boolean = false,
    /** Excel's Shrink to fit, LibreOffice's shrink-to-fit: its words made smaller until they fit the cell's width */ val shrink: Boolean = false,
    /** Excel's and LibreOffice's Locked (on unless a cell's look says not): its cell not to be changed while the sheet is protected */ val locked: Boolean = true,
    val indent: Int = 0, val rotation: Int = 0, val format: String = "General",
    val left: SheetLine? = null, val top: SheetLine? = null, val right: SheetLine? = null, val bottom: SheetLine? = null,
) {
    val boxed get() = fill != null || left != null || top != null || right != null || bottom != null
}

/** A cell: the text shown, what it is, its number, its formula ("=SUM(A1:A3)"), its look ([style] in the workbook's list). */
internal data class SheetCell(
    val text: String, val kind: SheetKind, val value: Double? = null, val formula: String? = null, val style: Int = 0,
    val color: Int? = null, val link: String? = null,
    /** Its comment (Excel's note, a threaded comment, LibreOffice's annotation): "Author: words". */
    val note: String? = null,
    /** The file's style when [style] is a look of the sheet's own (a table's stripe, a rule's colour): what is saved. */
    val own: Int = -1,
    /** A number format the cell took when it was typed in (a date, a percent in a General cell): saved with it. */
    val format: String? = null,
    /** Rich text (Excel's runs): its words in pieces of their own looks, drawn by the Office engine; null: one look, the style's. */
    val runs: List<DocRun>? = null,
    /** A formula whose result spills (FILTER, SORT…; Excel's array formula): the cells it fills, itself first. */
    val spill: SheetRange? = null,
    /** A cell holding part of another's spilled result: that cell's key (-1: none). */
    val spilledFrom: Long = -1,
) {
    /** Its runs while they still are its words (a cell whose text was changed another way has its style's look only). */
    val rich get() = runs?.takeIf { rs -> rs.joinToString("") { it.text } == text }
    /** The style the file gives the cell, to write back. */
    val fileStyle get() = if (own >= 0) own else if (style < Sheet.LOOKS) style else 0
}

/** Rows and columns from 0, both ends in. */
internal data class SheetRange(val r1: Int, val c1: Int, val r2: Int, val c2: Int) {
    operator fun contains(rc: Pair<Int, Int>) = rc.first in r1..r2 && rc.second in c1..c2
}

/**
 * A sheet's cells by [Sheet.key] — an open-addressed table of plain longs, as a sheet can hold hundreds of thousands
 * (the user's "Εκκαθαρισμένοι" workbook: 1.8 million in 12 sheets; a HashMap's boxed keys and entries took ~150 bytes each).
 */
internal class CellMap(expected: Int = 64) {
    private var keys = LongArray(capacity(expected)) { EMPTY }
    private var values = arrayOfNulls<SheetCell>(keys.size)
    var size = 0; private set

    private fun capacity(n: Int): Int { var c = 16; while (c * 0.6 < n) c = c shl 1; return c }
    private fun slot(k: Long, mask: Int): Int = ((k * -0x61c8864680b583ebL) ushr 33).toInt() and mask

    operator fun get(k: Long): SheetCell? {
        val mask = keys.size - 1
        var i = slot(k, mask)
        while (true) { val at = keys[i]; if (at == EMPTY) return null; if (at == k) return values[i]; i = (i + 1) and mask }
    }

    operator fun set(k: Long, v: SheetCell) {
        if ((size + 1) > keys.size * 0.6) grow()
        val mask = keys.size - 1
        var i = slot(k, mask)
        while (true) {
            val at = keys[i]
            if (at == EMPTY) { keys[i] = k; values[i] = v; size++; return }
            if (at == k) { values[i] = v; return }
            i = (i + 1) and mask
        }
    }

    private fun grow() {
        val oldK = keys; val oldV = values
        keys = LongArray(oldK.size shl 1) { EMPTY }; values = arrayOfNulls(keys.size); size = 0
        for (i in oldK.indices) if (oldK[i] != EMPTY) set(oldK[i], oldV[i]!!)
    }

    fun forEach(action: (Long, SheetCell) -> Unit) { for (i in keys.indices) if (keys[i] != EMPTY) action(keys[i], values[i]!!) }

    companion object { private const val EMPTY = -1L }
}

internal class Sheet(
    val name: String,
    val cells: CellMap,
    /** The used rows and columns: one past the last cell with anything in it. */
    var rows: Int, var cols: Int,
    val colWidths: Map<Int, Float>, val rowHeights: Map<Int, Float>,
    val defaultColWidth: Float, val defaultRowHeight: Float,
    val hiddenCols: Set<Int>, val hiddenRows: Set<Int>,
    val merges: List<SheetRange>,
    val frozenRows: Int, val frozenCols: Int,
    val grid: Boolean = true,
    val colStyles: Map<Int, Int> = emptyMap(), val rowStyles: Map<Int, Int> = emptyMap(),
    val tab: Int? = null, val hidden: Boolean = false,
    /** The file's AutoFilter: the table its header buttons filter. */
    val autoFilter: SheetRange? = null,
    /** Looks only this sheet's cells have (an Excel Table's stripes, a rule's colour): style indices from [LOOKS] on. */
    val looks: List<SheetStyle> = emptyList(),
    /** Pictures, charts and text boxes over the cells. */
    val drawings: List<SheetDrawing> = emptyList(),
    /** Conditional formatting's data bars, by cell. */
    var bars: Map<Long, SheetBar> = emptyMap(),
    /** Conditional formatting's icons, by cell. */
    var icons: Map<Long, SheetIcon> = emptyMap(),
    /** How the file prints it: page setup, print area, rows on every page. */
    val print: SheetPrint = SheetPrint(),
) {
    /** Review › Protect Sheet (Excel's sheetProtection, LibreOffice's table:protected): its locked cells are not to be changed. */
    var protected = false
    /** Its protection has a password (asked for to unprotect it). */
    var guarded = false
    /** Its rows' outline levels (Data › Group; Excel's outlineLevel, LibreOffice's row groups): a grouped row's depth. */
    var rowLevels: Map<Int, Int> = emptyMap()
    /** Its AutoFilter's kept values by column (Excel's filterColumn; "" an empty cell): the view's filter when opened. */
    var filterKeep: Map<Int, Set<String>> = emptyMap()
    /** View › Headings: its column letters and row numbers shown (Excel's showRowColHeaders, LibreOffice's HasColumnRowHeaders). */
    var headings = true
    /** Its drop-down lists (data validation). */
    var lists: List<SheetList> = emptyList()
    fun listAt(r: Int, c: Int) = lists.firstOrNull { l -> l.ranges.any { (r to c) in it } }
    /** Its conditional formatting, and the looks its rules covered (to lay them again after an edit). */
    var rules: List<SheetRule> = emptyList()
    val ruleBase = HashMap<Long, Int>()

    /** A cell's look: the workbook's [styles], or this sheet's own [looks]. */
    fun look(i: Int, styles: List<SheetStyle>): SheetStyle? = if (i >= LOOKS) looks.getOrNull(i - LOOKS) else styles.getOrNull(i)

    operator fun get(r: Int, c: Int): SheetCell? = cells[key(r, c)]

    /** A cell set (an edit): the used range grows to hold it. */
    fun put(k: Long, cell: SheetCell) {
        cells[k] = cell
        if (cell.kind != SheetKind.Empty || cell.formula != null) { rows = max(rows, row(k) + 1); cols = max(cols, col(k) + 1) }
    }
    fun width(c: Int) = if (c in hiddenCols) 0f else colWidths[c] ?: defaultColWidth
    fun height(r: Int) = if (r in hiddenRows) 0f else rowHeights[r] ?: defaultRowHeight
    /** The merged range [r],[c] is in, if any. */
    fun merge(r: Int, c: Int): SheetRange? = mergeAt[key(r, c)]
    private val mergeAt: Map<Long, SheetRange> by lazy {
        HashMap<Long, SheetRange>().also { m -> merges.forEach { g -> for (r in g.r1..g.r2) for (c in g.c1..g.c2) m[key(r, c)] = g } }
    }

    companion object {
        /** Where a sheet's own looks are numbered from: past any workbook's styles, which an .ods adds to as it reads. */
        const val LOOKS = 1 shl 24
        fun key(r: Int, c: Int) = (r.toLong() shl 16) or c.toLong()
        fun row(key: Long) = (key shr 16).toInt()
        fun col(key: Long) = (key and 0xFFFF).toInt()
    }
}

/** A sheet's tab: its name, and whether the file hides it. */
internal data class SheetTab(val name: String, val hidden: Boolean = false)

/**
 * A workbook: its tabs, its looks, the sheet it was left on — and each sheet read only when it is opened ([sheet]),
 * the last two kept, so a workbook of a dozen big sheets never holds them all.
 */
/** A cell search found in a sheet not opened: where, its words, and its row's other words. */
internal class SheetFind(val key: Long, val text: String, val context: String)

internal class Workbook(
    val tabs: List<SheetTab>, val styles: List<SheetStyle>, val active: Int = 0,
    /** A quick search of one sheet without reading it whole (an .xlsx's bytes scanned); null: read it and search. */
    val scan: ((Int, String) -> List<SheetFind>)? = null,
    /** "xlsx", "ods" or "csv": how it is saved. */
    val kind: String = "xlsx",
    /** Numbers as this workbook shows them (its 1904 dates, the phone's separators). */
    val format: SheetFormat = SheetFormat(),
    /** A .csv's separator and letters, to save it as it came. */
    val csv: Pair<Char, java.nio.charset.Charset>? = null,
    /** An .xlsx's parts: "workbook", "styles", and each sheet's by its number ("0", "1"…), to save into. */
    val parts: Map<String, String> = emptyMap(),
    /** Defined names (in small letters) and what they stand for ("rows!$b$3:$b$12"), for formulas. */
    val names: Map<String, String> = emptyMap(),
    /** An .ods's cell styles' names by index (to write a cell's look back by name). */
    val styleNames: List<String> = emptyList(),
    /** Its names as Formulas › Name Manager lists them: each in its own letters, and what it stands for (Excel's form). */
    val nameList: () -> List<Pair<String, String>> = { emptyList() },
    /** Its theme's colours (an .xlsx's own, else Office's): Format as table's gallery. */
    val theme: IntArray = SheetTables.OFFICE,
    /** Review › Protect workbook: its sheets not added, deleted, renamed, moved or hidden. */
    val structureLocked: Boolean = false,
    private val load: (Int) -> Sheet,
) {
    private val kept = LinkedHashMap<Int, Sheet>()
    /** How many looks the file has; looks made by formatting are numbered after them. */
    val fileStyles = styles.size

    /** A look's index: one the workbook has, or a new one added (formatting). */
    fun styleFor(st: SheetStyle): Int = synchronized(styles) {
        val i = styles.indexOf(st)
        if (i >= 0) return i
        (styles as MutableList<SheetStyle>) += st
        return styles.lastIndex
    }
    /** Cells changed and not yet saved, by sheet: laid again on a sheet read again (only two are kept). */
    val edits = HashMap<Int, LinkedHashMap<Long, SheetCell>>()
    /** After the edits are laid on a sheet (formulas worked out again). */
    var laid: ((Int, Sheet) -> Unit)? = null

    fun sheet(i: Int): Sheet = synchronized(kept) {
        kept.remove(i)?.let { kept[i] = it; return it }
        WriterLog.timed("Sheets: sheet ${i + 1} of ${tabs.size} read") { load(i) }.also { s ->
            edits[i]?.forEach { (k, c) -> s.put(k, c) }
            // formulas a file keeps no value for (openpyxl and other programs write none): worked out, as Excel does on opening
            val missing = ArrayList<Long>()
            s.cells.forEach { k, c -> if (c.formula != null && c.kind == SheetKind.Empty) missing += k }
            if (missing.isNotEmpty()) runCatching { SheetRecalc.run(this, i, s, missing).forEach { (k, c) -> s.put(k, c) } }
                .onSuccess { WriterLog.i("Sheets: ${missing.size} formulas without a value worked out") }.onFailure { WriterLog.w("Sheets: formulas without a value", it) }
            if (edits.isNotEmpty()) laid?.invoke(i, s)
            kept[i] = s; while (kept.size > 2) kept.remove(kept.keys.first())
        }
    }

    /** A cell changed: kept with the edits, and on the sheet when it is read. */
    fun edit(i: Int, key: Long, cell: SheetCell) = synchronized(kept) {
        edits.getOrPut(i) { LinkedHashMap() }[key] = cell
        kept[i]?.put(key, cell)
    }
    /** For the tests: every sheet. */
    val sheets: List<Sheet> get() = tabs.indices.map(::sheet)
}

/** Excel's 64 indexed colours, and its theme colours lightened or darkened by a tint. */
internal object SheetColors {
    private val INDEXED = intArrayOf(
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x800000, 0x008000, 0x000080, 0x808000, 0x800080, 0x008080, 0xC0C0C0, 0x808080,
        0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF,
        0x000080, 0xFF00FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x800000, 0x008080, 0x0000FF,
        0x00CCFF, 0xCCFFFF, 0xCCFFCC, 0xFFFF99, 0x99CCFF, 0xFF99CC, 0xCC99FF, 0xFFCC99,
        0x3366FF, 0x33CCCC, 0x99CC00, 0xFFCC00, 0xFF9900, 0xFF6600, 0x666699, 0x969696,
        0x003366, 0x339966, 0x003300, 0x333300, 0x993300, 0x993366, 0x333399, 0x333333,
    )

    /** 64: the system's text colour (black), 65: its window (white). */
    fun indexed(i: Int, custom: List<Int>? = null): Int? = when {
        custom != null && i in custom.indices -> custom[i]
        i in INDEXED.indices -> INDEXED[i] or 0xFF000000.toInt()
        i == 64 -> 0xFF000000.toInt()
        i == 65 -> 0xFFFFFFFF.toInt()
        else -> null
    }

    /** Excel's tint: towards white (positive) or black (negative), on the colour's lightness. */
    fun tint(argb: Int, tint: Double): Int {
        if (tint == 0.0) return argb
        val r = (argb shr 16 and 0xFF) / 255.0; val g = (argb shr 8 and 0xFF) / 255.0; val b = (argb and 0xFF) / 255.0
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
        var l = (mx + mn) / 2
        val d = mx - mn
        val s = if (d == 0.0) 0.0 else d / (1 - kotlin.math.abs(2 * l - 1))
        val h = when {
            d == 0.0 -> 0.0
            mx == r -> 60 * (((g - b) / d).mod(6.0))
            mx == g -> 60 * ((b - r) / d + 2)
            else -> 60 * ((r - g) / d + 4)
        }
        l = if (tint < 0) l * (1 + tint) else l * (1 - tint) + tint
        val c = (1 - kotlin.math.abs(2 * l - 1)) * s
        val x = c * (1 - kotlin.math.abs((h / 60).mod(2.0) - 1))
        val m = l - c / 2
        val (r1, g1, b1) = when ((h / 60).toInt()) {
            0 -> Triple(c, x, 0.0); 1 -> Triple(x, c, 0.0); 2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c); 4 -> Triple(x, 0.0, c); else -> Triple(c, 0.0, x)
        }
        fun ch(v: Double) = ((v + m) * 255).let { kotlin.math.round(it).toInt().coerceIn(0, 255) }
        return (argb and 0xFF000000.toInt()) or (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
    }
}

internal object Sheets {
    fun isSheet(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("xlsx", "xlsm", "ods", "csv", "tsv", "xls")

    /** Reads a spreadsheet; [decimal] and [group] are the separators numbers are shown with (the phone's). */
    fun read(bytes: ByteArray, name: String, decimal: Char = '.', group: Char = ','): Workbook =
        when (name.substringAfterLast('.', "").lowercase()) {
            "ods" -> OfficeFormats.unzip(bytes).let { m -> Ods(m::get, { n -> m[n]?.let(::ByteArrayInputStream) }, decimal to group).read() }
            "csv", "tsv" -> csv(bytes, decimal, group)
            "xls" -> SheetXls.read(bytes, decimal, group)
            else -> OfficeFormats.unzip(bytes).let { m -> Xlsx(m::get, { n -> m[n]?.let(::ByteArrayInputStream) }, decimal, group).read() }
        }

    /**
     * A spreadsheet file, each part taken from the zip on disk when it is needed (the user's 20 MB workbook is 178 MB
     * unzipped: holding it all took the phone's heap to 271 MB).
     */
    fun read(file: java.io.File, decimal: Char = '.', group: Char = ','): Workbook {
        val ext = file.extension.lowercase()
        if (ext == "csv" || ext == "tsv") return csv(file.readBytes(), decimal, group)
        if (ext == "xls") return SheetXls.read(file.readBytes(), decimal, group)
        val names = java.util.zip.ZipFile(file).use { z -> z.entries().asSequence().map { it.name }.toHashSet() }
        val part = { name: String ->
            if (name !in names) null else java.util.zip.ZipFile(file).use { z -> z.getEntry(name)?.let { e -> z.getInputStream(e).use { it.readBytes() } } }
        }
        // A part to read as it streams (an .ods's content.xml holds every sheet: 360 MB for the user's 46 tables).
        val stream = { name: String ->
            if (name !in names) null else java.util.zip.ZipFile(file).let { z ->
                z.getEntry(name)?.let { e -> object : java.io.FilterInputStream(java.io.BufferedInputStream(z.getInputStream(e), 1 shl 16)) { override fun close() { super.close(); z.close() } } }
                    ?: run { z.close(); null }
            }
        }
        return if (ext == "ods") Ods(part, stream, decimal to group).read() else Xlsx(part, stream, decimal, group).read()
    }

    /** A HYPERLINK formula's address: =HYPERLINK("https://…", "words"). */
    fun hyperlink(formula: String): String? = Regex("""^=?\s*HYPERLINK\(\s*"([^"]+)"""", RegexOption.IGNORE_CASE).find(formula)?.groupValues?.get(1)

    /** "B12" → (11, 1); null when it is not a cell's name. */
    fun cellRef(ref: String): Pair<Int, Int>? {
        // By hand: every cell of a sheet has one (a regular expression here took most of a big sheet's reading).
        var i = 0; val s = ref.trim(); var col = 0; var row = 0; var letters = 0; var digits = 0
        if (i < s.length && s[i] == '$') i++
        while (i < s.length && s[i].isLetter()) { val ch = s[i].uppercaseChar(); if (ch !in 'A'..'Z') return null; col = col * 26 + (ch - 'A' + 1); letters++; i++ }
        if (i < s.length && s[i] == '$') i++
        while (i < s.length && s[i] in '0'..'9') { row = row * 10 + (s[i] - '0'); digits++; i++; if (digits > 7) return null }
        if (i != s.length || letters !in 1..3 || digits == 0) return null
        return if (row < 1 || col - 1 > 16383) null else row - 1 to col - 1
    }

    fun rangeRef(ref: String): SheetRange? {
        val parts = ref.split(':')
        val a = cellRef(parts[0]) ?: return null
        val b = parts.getOrNull(1)?.let(::cellRef) ?: a
        return SheetRange(min(a.first, b.first), min(a.second, b.second), max(a.first, b.first), max(a.second, b.second))
    }

    fun colName(c: Int): String = ChartWriter.column(c)

    /**
     * A shared formula as it reads in another cell: its relative references moved by [dr] rows and [dc] columns, as
     * Excel stores one formula for a block of cells and leaves the rest empty.
     */
    fun shift(formula: String, dr: Int, dc: Int): String {
        if (dr == 0 && dc == 0) return formula
        val ref = Regex("""(?<![A-Za-z0-9_.$])(\$?)([A-Z]{1,3})(\$?)([0-9]{1,7})(?![0-9A-Za-z_(])""")
        return formula.split('"').mapIndexed { i, part ->
            if (i % 2 == 1) part else ref.replace(part) { m ->
                val (cAbs, col, rAbs, row) = m.destructured
                val c = col.fold(0) { a, ch -> a * 26 + (ch - 'A' + 1) } - 1
                if (c > 16383) return@replace m.value
                val nc = if (cAbs.isEmpty()) c + dc else c
                val nr = if (rAbs.isEmpty()) row.toInt() + dr else row.toInt()
                if (nc < 0 || nr < 1) "#REF!" else cAbs + colName(nc) + rAbs + nr
            }
        }.joinToString("\"")
    }

    /** Thrown by a handler that has what it came for: the rest of the file is not read. */
    private class Enough : RuntimeException() { override fun fillInStackTrace() = this }

    internal fun sax(input: java.io.InputStream, handler: DefaultHandler) {
        val f = SAXParserFactory.newInstance()
        f.isNamespaceAware = true
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        // A parser may wrap the handler's Enough in its own exception: still the handler's stop.
        try { input.use { f.newSAXParser().parse(it, handler) } } catch (e: Exception) { if (generateSequence<Throwable>(e) { it.cause }.none { it is Enough }) throw e }
    }

    internal fun sax(bytes: ByteArray, handler: DefaultHandler) {
        val f = SAXParserFactory.newInstance()
        f.isNamespaceAware = true
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        f.newSAXParser().parse(ByteArrayInputStream(bytes), handler)
    }

    /** An attribute by its local name, whatever its prefix. */
    internal fun Attributes.a(local: String): String? {
        for (i in 0 until length) if (getLocalName(i) == local) return getValue(i)
        return null
    }

    private fun Element.kids(name: String): List<Element> {
        val out = ArrayList<Element>()
        var n = firstChild
        while (n != null) { if (n is Element && n.localName == name) out += n; n = n.nextSibling }
        return out
    }
    private fun Element.kid(name: String): Element? = kids(name).firstOrNull()
    private fun Element.elements(): List<Element> {
        val out = ArrayList<Element>()
        var n = firstChild
        while (n != null) { if (n is Element) out += n; n = n.nextSibling }
        return out
    }
    private fun Element.below(name: String): List<Element> = getElementsByTagNameNS("*", name).let { l -> (0 until l.length).map { l.item(it) as Element } }
    private fun Element.attr(name: String): String? = getAttribute(name).takeIf { it.isNotEmpty() }
    private fun Element.flag(name: String): Boolean = kid(name)?.let { it.attr("val")?.let { v -> v != "0" && v != "false" && v != "none" } ?: true } ?: false

    // --- .xlsx ---

    private class Xlsx(val parts: (String) -> ByteArray?, val stream: (String) -> java.io.InputStream?, decimal: Char, group: Char) {
        val strings = ArrayList<String>()
        /** The shared strings that are rich text, by their index: their runs. */
        val richStrings = HashMap<Int, List<DocRun>>()
        /** Excel's rich text being read (an <r> each, its <rPr> look) as Words' runs; [text] is where its words gather. */
        inner class RichRuns(val text: StringBuilder) {
            val runs = ArrayList<DocRun>(); private var start = -1; private var look = DocRun(""); var inPr = false
            fun reset() { runs.clear(); start = -1; inPr = false }
            fun open() { start = text.length; look = DocRun("") }
            fun prop(local: String, a: Attributes) {
                fun on() = a.getValue("val").let { it == null || it == "1" || it == "true" }
                look = when (local) {
                    "b" -> look.copy(bold = on()); "i" -> look.copy(italic = on()); "strike" -> look.copy(strike = on())
                    "u" -> look.copy(underline = a.getValue("val") != "none"); "sz" -> look.copy(sizePt = a.getValue("val")?.toFloatOrNull())
                    "rFont" -> a.getValue("val")?.let { look.copy(font = Slides.face(it)) } ?: look
                    "color" -> (a.getValue("rgb")?.takeLast(6)?.toIntOrNull(16) ?: a.getValue("theme")?.toIntOrNull()?.let { theme.getOrNull(it) })?.let { look.copy(color = it and 0xFFFFFF) } ?: look
                    else -> look
                }
            }
            fun close() { if (start >= 0) runs += look.copy(text = text.substring(start)); start = -1 }
            fun done() = runs.toList().takeIf { it.isNotEmpty() }
        }
        val styles = ArrayList<SheetStyle>()
        val dxfs = ArrayList<SheetDxf>()
        val printNames = HashMap<String, Pair<String?, String?>>()
        val tableStyles = HashMap<String, Map<String, SheetDxf>>()
        var theme = IntArray(0)
        var customIndexed: List<Int>? = null
        var date1904 = false
        lateinit var format: SheetFormat
        val sep = decimal to group

        fun rels(part: String): Map<String, String> {
            val dir = part.substringBeforeLast('/', "")
            val relsPath = (if (dir.isEmpty()) "" else "$dir/") + "_rels/" + part.substringAfterLast('/') + ".rels"
            val bytes = parts(relsPath) ?: return emptyMap()
            val out = HashMap<String, String>()
            val doc = OfficeFormats.parse(bytes)
            val list = doc.getElementsByTagNameNS("*", "Relationship")
            for (i in 0 until list.length) {
                val e = list.item(i) as Element
                val target = e.getAttribute("Target")
                out[e.getAttribute("Id")] = if (e.getAttribute("TargetMode") == "External") "ext:$target" else resolve(dir, target)
            }
            return out
        }

        fun resolve(dir: String, target: String): String {
            if (target.startsWith("/")) return target.removePrefix("/")
            val path = ArrayList(if (dir.isEmpty()) emptyList() else dir.split('/'))
            target.split('/').forEach { if (it == "..") path.removeLastOrNull() else if (it != ".") path += it }
            return path.joinToString("/")
        }

        fun read(): Workbook {
            val book = rels("").values.firstOrNull { it.endsWith("workbook.xml") } ?: "xl/workbook.xml"
            val bookRels = rels(book)
            val wb = OfficeFormats.parse(parts(book) ?: sheetProblem(R.string.tables_err_not_spreadsheet)).documentElement
            date1904 = wb.kid("workbookPr")?.attr("date1904")?.let { it == "1" || it == "true" } ?: false
            format = SheetFormat(sep.first, sep.second, date1904)
            bookRels.values.firstOrNull { it.contains("theme/") }?.let(parts)?.let(::readTheme)
            bookRels.values.firstOrNull { it.endsWith("sharedStrings.xml") }?.let(stream)?.let(::readStrings)
            bookRels.values.firstOrNull { it.endsWith("styles.xml") }?.let(parts)?.let(::readStyles)
            if (styles.isEmpty()) styles += SheetStyle()
            val active = wb.kid("bookViews")?.kid("workbookView")?.attr("activeTab")?.toIntOrNull() ?: 0
            // Print areas and rows printed on every page: workbook names kept for each sheet (localSheetId).
            val names = wb.kid("definedNames")?.kids("definedName").orEmpty()
            fun named(name: String, sheet: Int) = names.firstOrNull { it.attr("name") == name && it.attr("localSheetId")?.toIntOrNull() == sheet }?.textContent
            val found = wb.kid("sheets")?.kids("sheet").orEmpty().mapIndexedNotNull { i, s ->
                val rid = s.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id").ifEmpty { s.attr("r:id") ?: "" }
                val path = bookRels[rid] ?: return@mapIndexedNotNull null
                printNames[path] = named("_xlnm.Print_Area", i) to named("_xlnm.Print_Titles", i)
                SheetTab(s.attr("name") ?: "Sheet", s.attr("state").let { it == "hidden" || it == "veryHidden" }) to path
            }
            if (found.isEmpty()) sheetProblem(R.string.tables_err_no_sheets)
            WriterLog.i("Sheets: xlsx, ${found.size} sheets, ${strings.size} strings, ${styles.size} looks")
            return Workbook(found.map { it.first }, styles, active.coerceIn(0, found.lastIndex), scan = { i, q -> stream(found[i].second)?.use { scan(it, q) }.orEmpty() },
                kind = "xlsx", format = format, theme = theme.takeIf { it.size >= 10 } ?: SheetTables.OFFICE,
                structureLocked = wb.kid("workbookProtection")?.attr("lockStructure").let { it == "1" || it == "true" },
                names = names.filter { it.attr("name")?.startsWith("_xlnm.") == false }.associate { it.attr("name")!!.lowercase() to it.textContent.trim() },
                nameList = { names.filter { it.attr("name")?.startsWith("_xlnm.") == false && it.attr("localSheetId") == null }.map { it.attr("name")!! to it.textContent.trim() } },
                parts = mapOf("workbook" to book) + listOfNotNull(bookRels.values.firstOrNull { it.endsWith("styles.xml") }?.let { "styles" to it }) +
                    found.mapIndexed { i, f -> "$i" to f.second }) { i ->
                val (tab, path) = found[i]
                runCatching { readSheet(tab.name, path, stream(path) ?: sheetProblem(R.string.tables_err_no_such_part), tab.hidden) }.getOrElse {
                    WriterLog.w("Sheets: sheet ${i + 1} could not be read", it)
                    Sheet(tab.name, CellMap(), 0, 0, emptyMap(), emptyMap(), 64f, 20f, emptySet(), emptySet(), emptyList(), 0, 0, hidden = tab.hidden)
                }
            }
        }

        fun readTheme(bytes: ByteArray) {
            val doc = OfficeFormats.parse(bytes)
            val scheme = doc.getElementsByTagNameNS("*", "clrScheme").item(0) as? Element ?: return
            val order = listOf("lt1", "dk1", "lt2", "dk2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink")
            theme = order.map { n ->
                val e = scheme.kid(n)
                val c = e?.kid("srgbClr")?.attr("val") ?: e?.kid("sysClr")?.attr("lastClr") ?: "000000"
                (c.toLongOrNull(16)?.toInt() ?: 0) or 0xFF000000.toInt()
            }.toIntArray()
        }

        fun readStrings(input: java.io.InputStream) = sax(input, object : DefaultHandler() {
            val sb = StringBuilder(); var inT = false; var phonetic = 0
            val rich = RichRuns(sb)
            override fun startElement(uri: String?, local: String, q: String?, at: Attributes) {
                when (local) { "si" -> { sb.setLength(0); rich.reset() }; "t" -> inT = phonetic == 0; "rPh" -> phonetic++
                    "r" -> if (phonetic == 0) rich.open(); "rPr" -> rich.inPr = true; else -> if (rich.inPr) rich.prop(local, at) }
            }
            override fun endElement(uri: String?, local: String, q: String?) {
                when (local) { "si" -> { strings += sb.toString(); rich.done()?.let { richStrings[strings.lastIndex] = it } }; "t" -> inT = false; "rPh" -> phonetic--
                    "r" -> if (phonetic == 0) rich.close(); "rPr" -> rich.inPr = false }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { if (inT) sb.appendRange(ch, start, start + length) }
        })

        fun color(e: Element?): Int? {
            e ?: return null
            if (e.attr("auto") == "1") return null
            val base = e.attr("rgb")?.let { (it.takeLast(6).toLongOrNull(16)?.toInt() ?: return null) or 0xFF000000.toInt() }
                ?: e.attr("theme")?.toIntOrNull()?.let { theme.getOrNull(it) }
                ?: e.attr("indexed")?.toIntOrNull()?.let { SheetColors.indexed(it, customIndexed) }
                ?: return null
            return SheetColors.tint(base, e.attr("tint")?.toDoubleOrNull() ?: 0.0)
        }

        fun line(e: Element?): SheetLine? {
            val style = e?.attr("style") ?: return null
            if (style == "none") return null
            val c = color(e.kid("color")) ?: 0xFF000000.toInt()
            return when (style) {
                "medium", "mediumDashed", "mediumDashDot", "mediumDashDotDot", "slantDashDot" -> SheetLine(c, 2f, dashed = style != "medium")
                "thick" -> SheetLine(c, 3f)
                "double" -> SheetLine(c, 3f, double = true)
                "hair", "dotted" -> SheetLine(c, 1f, dots = true)
                "dashed", "dashDot", "dashDotDot" -> SheetLine(c, 1f, dashed = true)
                else -> SheetLine(c, 1f)
            }
        }

        fun readStyles(bytes: ByteArray) {
            val root = OfficeFormats.parse(bytes).documentElement
            root.kid("colors")?.kid("indexedColors")?.kids("rgbColor")?.map { (it.attr("rgb")?.takeLast(6)?.toLongOrNull(16)?.toInt() ?: 0) or 0xFF000000.toInt() }
                ?.takeIf { it.isNotEmpty() }?.let { customIndexed = it }
            val formats = HashMap<Int, String>(SheetFormat.BUILT_IN)
            root.kid("numFmts")?.kids("numFmt")?.forEach { f -> f.attr("numFmtId")?.toIntOrNull()?.let { formats[it] = f.getAttribute("formatCode") } }
            val fonts = root.kid("fonts")?.kids("font").orEmpty()
            val fills = root.kid("fills")?.kids("fill").orEmpty().map { f ->
                f.kid("patternFill")?.let { p ->
                    when (p.attr("patternType") ?: if (p.kid("fgColor") != null) "solid" else "none") {
                        "none" -> null
                        "gray125", "gray0625" -> color(p.kid("fgColor")) ?: 0xFFE0E0E0.toInt()
                        else -> color(p.kid("fgColor")) ?: color(p.kid("bgColor"))
                    }
                } ?: f.kid("gradientFill")?.kids("stop")?.firstOrNull()?.let { color(it.kid("color")) }
            }
            val borders = root.kid("borders")?.kids("border").orEmpty()
            root.kid("dxfs")?.kids("dxf")?.forEach { d ->
                val font = d.kid("font"); val p = d.kid("fill")?.kid("patternFill"); val b = d.kid("border")
                dxfs += SheetDxf(
                    // A dxf's solid fill is its bgColor (fgColor is the pattern's), as Excel reads it.
                    fill = p?.takeIf { it.attr("patternType") != "none" }?.let { color(it.kid("bgColor")) ?: color(it.kid("fgColor")) },
                    color = color(font?.kid("color")), bold = font?.kid("b")?.let { font.flag("b") }, italic = font?.kid("i")?.let { font.flag("i") },
                    left = line(b?.kid("left") ?: b?.kid("start")), right = line(b?.kid("right") ?: b?.kid("end")), top = line(b?.kid("top")),
                    bottom = line(b?.kid("bottom")), horizontal = line(b?.kid("horizontal")), vertical = line(b?.kid("vertical")),
                )
            }
            root.kid("tableStyles")?.kids("tableStyle")?.forEach { t ->
                val name = t.attr("name") ?: return@forEach
                tableStyles[name] = t.kids("tableStyleElement").mapNotNull { e -> e.attr("type")?.let { it to (dxfs.getOrNull(e.attr("dxfId")?.toIntOrNull() ?: -1) ?: SheetDxf()) } }.toMap()
            }
            root.kid("cellXfs")?.kids("xf")?.forEach { xf ->
                val font = fonts.getOrNull(xf.attr("fontId")?.toIntOrNull() ?: 0)
                val border = borders.getOrNull(xf.attr("borderId")?.toIntOrNull() ?: 0)
                val al = xf.kid("alignment")
                styles += SheetStyle(
                    font = font?.kid("name")?.attr("val"),
                    size = font?.kid("sz")?.attr("val")?.toFloatOrNull() ?: 11f,
                    bold = font?.flag("b") == true, italic = font?.flag("i") == true,
                    underline = font?.flag("u") == true, strike = font?.flag("strike") == true,
                    color = color(font?.kid("color")),
                    fill = fills.getOrNull(xf.attr("fillId")?.toIntOrNull() ?: 0),
                    align = when (al?.attr("horizontal")) {
                        "left" -> SheetAlign.Left; "center", "centerContinuous" -> SheetAlign.Center; "right" -> SheetAlign.Right
                        "justify", "distributed" -> SheetAlign.Justify; else -> SheetAlign.General
                    },
                    valign = when (al?.attr("vertical")) { "top" -> SheetVAlign.Top; "center", "justify", "distributed" -> SheetVAlign.Center; else -> SheetVAlign.Bottom },
                    wrap = al?.attr("wrapText")?.let { it == "1" || it == "true" } == true,
                    shrink = al?.attr("shrinkToFit")?.let { it == "1" || it == "true" } == true,
                    locked = xf.kid("protection")?.attr("locked").let { it != "0" && it != "false" },
                    indent = al?.attr("indent")?.toIntOrNull() ?: 0,
                    rotation = al?.attr("textRotation")?.toIntOrNull() ?: 0,
                    format = formats[xf.attr("numFmtId")?.toIntOrNull() ?: 0] ?: "General",
                    left = line(border?.kid("left") ?: border?.kid("start")), right = line(border?.kid("right") ?: border?.kid("end")),
                    top = line(border?.kid("top")), bottom = line(border?.kid("bottom")),
                )
            }
        }

        /** Excel's column width (characters of the default font's digit, 7 px in Calibri 11) in pixels. */
        fun colPx(w: Double) = floor(((256 * w + floor(128.0 / 7)) / 256) * 7).toFloat()

        /** Shared strings that match the last query: worked out once for every sheet's scan. */
        private var matching: Pair<String, java.util.BitSet>? = null

        /**
         * Search's quick pass over a sheet (asked for the user's tables: a name in any of 46 sheets): its bytes scanned
         * for <c> cells — a shared string matches if its words do (worked out once a search), a number by its digits
         * or as it is shown, inline words by themselves — with no cells made; a row with a find gets its words for the
         * list. The same finds as [SheetSearch.find] on the read sheet.
         */
        fun scan(input: java.io.InputStream, query: String): List<SheetFind> {
            val words = SheetSearch.words(query)
            if (words.isEmpty()) return emptyList()
            val strings = matching?.takeIf { it.first == query }?.second ?: java.util.BitSet().also { set ->
                this.strings.forEachIndexed { i, t -> if (SheetSearch.matches(SheetSearch.norm(t), words)) set.set(i) }
                matching = query to set
            }
            val general = SheetFormat(format.decimalSeparator(), format.groupSeparator())
            val out = ArrayList<SheetFind>()
            // the row being read: each cell's column, its shown words (worked out for rows with a find), and the finds
            class Cell(val col: Int, val type: String?, val style: Int, val raw: String)
            val row = ArrayList<Cell>(); val finds = ArrayList<Cell>()
            var rowNumber = -1
            fun shown(c: Cell): String = when (c.type) {
                "s" -> this.strings.getOrNull(c.raw.toIntOrNull() ?: -1).orEmpty()
                "inlineStr", "str", "e" -> c.raw
                "b" -> if (c.raw == "1") "TRUE" else "FALSE"
                else -> c.raw.toDoubleOrNull()?.let { format.format(it, styles.getOrNull(c.style)?.format ?: "General").text } ?: c.raw
            }
            fun flush() {
                for (f in finds) {
                    val context = row.asSequence().filter { it !== f }.map { shown(it).trim().replace('\n', ' ') }.filter { it.isNotEmpty() }.take(4).joinToString(" · ")
                    out += SheetFind(Sheet.key(rowNumber, f.col), shown(f), context)
                }
                row.clear(); finds.clear()
            }
            fun matches(c: Cell): Boolean = when (c.type) {
                "s" -> c.raw.toIntOrNull()?.let { strings[it] } == true
                "inlineStr", "str" -> SheetSearch.matches(SheetSearch.norm(c.raw), words)
                "b", "e" -> false
                // a number by its digits; as it is shown only when its look formats it (most are General: no work)
                else -> c.raw.toDoubleOrNull()?.let { v ->
                    SheetSearch.matches(c.raw + " " + general.general(v), words) ||
                        (styles.getOrNull(c.style)?.format?.takeIf { !it.equals("General", true) } != null && SheetSearch.matches(SheetSearch.norm(shown(c)), words))
                } == true
            }
            fun unescape(v: String) = if ('&' !in v) v else v.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
            // Plain byte arrays, not ByteArrayOutputStream (its writes are synchronized: most of the time on a phone).
            val buf = ByteArray(1 shl 16)
            val tag = ByteArray(4096); var tagLen = 0
            var body = ByteArray(1024); var bodyLen = 0
            var state = 0 // 0: between tags, 1: a tag, 2: a cell's body (until </c>)
            var cellRow = -1; var cellCol = -1; var cellType: String? = null; var cellStyle = 0
            /** Where [name]="…" starts in the tag (the value's first byte), or -1. */
            fun attr(name: String): Int {
                outer@ for (i in 1 until tagLen - name.length - 1) {
                    if (tag[i - 1] != ' '.code.toByte()) continue
                    for (k in name.indices) if (tag[i + k] != name[k].code.toByte()) continue@outer
                    if (tag[i + name.length] == '='.code.toByte() && tag[i + name.length + 1] == '"'.code.toByte()) return i + name.length + 2
                }
                return -1
            }
            fun number(at: Int): Int { var i = at; var n = 0; while (i < tagLen && tag[i] >= '0'.code.toByte() && tag[i] <= '9'.code.toByte()) { n = n * 10 + (tag[i] - '0'.code.toByte()); i++ }; return n }
            fun startCell() {
                cellType = attr("t").takeIf { it >= 0 }?.let { i -> var j = i; while (j < tagLen && tag[j] != '"'.code.toByte()) j++; String(tag, i, j - i, Charsets.US_ASCII) }
                cellStyle = attr("s").takeIf { it >= 0 }?.let(::number) ?: 0
                val r = attr("r")
                if (r >= 0) {
                    var i = r; var col = 0
                    while (i < tagLen && tag[i].toInt().toChar().let { it in 'A'..'Z' || it in 'a'..'z' }) { col = col * 26 + (tag[i].toInt().toChar().uppercaseChar() - 'A' + 1); i++ }
                    cellCol = col - 1; cellRow = number(i) - 1
                } else { cellCol = (row.lastOrNull()?.col ?: -1) + 1; cellRow = rowNumber }
            }
            fun endCell() {
                val raw = if (cellType == "inlineStr") Regex("<t[^>]*>([^<]*)</t>").findAll(String(body, 0, bodyLen, Charsets.UTF_8)).joinToString("") { it.groupValues[1] }
                else {
                    // the value: between <v> and </v>
                    var i = 0; var start = -1
                    while (i + 2 < bodyLen) { if (body[i] == '<'.code.toByte() && body[i + 1] == 'v'.code.toByte() && body[i + 2] == '>'.code.toByte()) { start = i + 3; break }; i++ }
                    if (start < 0) return
                    var end = start; while (end < bodyLen && body[end] != '<'.code.toByte()) end++
                    String(body, start, end - start, Charsets.UTF_8)
                }
                if (raw.isEmpty() && cellType != "inlineStr") return
                if (cellRow != rowNumber) { flush(); rowNumber = cellRow }
                val c = Cell(cellCol, cellType, cellStyle, unescape(raw))
                row += c
                if (matches(c)) finds += c
            }
            while (true) {
                val n = input.read(buf); if (n < 0) break
                var i = 0
                while (i < n) {
                    val b = buf[i]
                    when (state) {
                        0 -> if (b == '<'.code.toByte()) { tagLen = 0; state = 1 }
                        1 -> if (b == '>'.code.toByte()) {
                            state = 0
                            if (tagLen >= 1 && tag[0] == 'c'.code.toByte() && (tagLen == 1 || tag[1] == ' '.code.toByte())) {
                                if (tag[tagLen - 1] != '/'.code.toByte()) { startCell(); bodyLen = 0; state = 2 }
                            } else if (tagLen >= 3 && tag[0] == 'r'.code.toByte() && tag[1] == 'o'.code.toByte() && tag[2] == 'w'.code.toByte() && (tagLen == 3 || tag[3] == ' '.code.toByte())) {
                                flush(); rowNumber = attr("r").takeIf { it >= 0 }?.let { number(it) - 1 } ?: (rowNumber + 1)
                            }
                        } else if (tagLen < tag.size) tag[tagLen++] = b
                        else -> {
                            // the cell's end: "</c>"
                            if (b == '>'.code.toByte() && bodyLen >= 3 && body[bodyLen - 3] == '<'.code.toByte() && body[bodyLen - 2] == '/'.code.toByte() && body[bodyLen - 1] == 'c'.code.toByte()) {
                                bodyLen -= 3; endCell(); state = 0
                            } else {
                                if (bodyLen == body.size) body = body.copyOf(body.size * 2)
                                body[bodyLen++] = b
                            }
                        }
                    }
                    i++
                }
            }
            flush()
            return out
        }

        /** A sheet's part, streamed from the zip (a sheet's XML can be tens of MB). */
        fun readSheet(name: String, path: String, input: java.io.InputStream, hidden: Boolean): Sheet {
            val rels = rels(path)
            val cells = CellMap(1 shl 12)
            val colWidths = HashMap<Int, Float>(); val rowHeights = HashMap<Int, Float>()
            val hiddenCols = HashSet<Int>(); val hiddenRows = HashSet<Int>()
            val colStyles = HashMap<Int, Int>(); val rowStyles = HashMap<Int, Int>()
            val merges = ArrayList<SheetRange>()
            val links = HashMap<Long, String>()
            val tableParts = ArrayList<String>()
            val empties = HashMap<Int, SheetCell>()
            var drawingPart: String? = null
            val rules = ArrayList<SheetRule>()
            val page = HashMap<String, String>() // pageSetup, pageSetUpPr, printOptions and pageMargins, as they are
            val shared = HashMap<String, Triple<String, Int, Int>>()
            var defaultCol = 64f; var defaultRow = 20f
            var frozenRows = 0; var frozenCols = 0; var grid = true; var views = 0; var protectedSheet = false; var guardedSheet = false; var headings = true
            val dvs = ArrayList<Pair<List<SheetRange>, String>>() // data validation lists: their cells and formula
            val filterKeep = HashMap<Int, MutableSet<String>>(); var filterCol = 0 // the AutoFilter's kept values by column
            val rowLevels = HashMap<Int, Int>() // grouped rows' outline levels
            var tab: Int? = null; var autoFilter: SheetRange? = null
            var maxRow = -1; var maxCol = -1
            val boxed = Extent()
            sax(input, object : DefaultHandler() {
                var row = -1; var col = -1
                var r = 0; var c = 0; var s = 0; var t: String? = null
                val v = StringBuilder(); val f = StringBuilder(); val inline = StringBuilder()
                var into: StringBuilder? = null
                var hasV = false; var fAttrs: Triple<String?, String?, String?>? = null; var inIs = false; var phonetic = 0
                val rich = RichRuns(inline)
                // conditional formatting: the ranges, the rule being read, its formulas and its scale's or bar's points
                var ext = 0; var cfRanges = emptyList<SheetRange>(); var rule: Map<String, String>? = null
                // data validation lists: their cells and formula
                var dvRanges: List<SheetRange>? = null; val dvFormula = StringBuilder(); val headerFooter = StringBuilder()
                val formula = StringBuilder(); val formulas = ArrayList<String>(); val cfvos = ArrayList<SheetCfvo>(); val colors = ArrayList<Int>()
                fun color(at: Attributes): Int? {
                    val base = at.a("rgb")?.let { (it.takeLast(6).toLongOrNull(16)?.toInt() ?: return null) or 0xFF000000.toInt() }
                        ?: at.a("theme")?.toIntOrNull()?.let { theme.getOrNull(it) } ?: at.a("indexed")?.toIntOrNull()?.let { SheetColors.indexed(it, customIndexed) } ?: return null
                    return SheetColors.tint(base, at.a("tint")?.toDoubleOrNull() ?: 0.0)
                }
                override fun startElement(uri: String?, local: String, q: String?, at: Attributes) {
                    if (local == "extLst") ext++
                    if (ext > 0) return // Excel 2010's copies of rules (and other extensions) are left out
                    when (local) {
                        "conditionalFormatting" -> cfRanges = at.a("sqref").orEmpty().split(' ').mapNotNull(::rangeRef)
                        "dataValidation" -> dvRanges = at.a("sqref").orEmpty().split(' ').mapNotNull(::rangeRef).takeIf { at.a("type") == "list" }?.also { dvFormula.setLength(0) }
                        "formula1" -> if (dvRanges != null) into = dvFormula
                        "oddHeader", "oddFooter" -> { headerFooter.setLength(0); into = headerFooter }
                        "pageSetup", "pageSetUpPr", "printOptions", "pageMargins" -> for (i in 0 until at.length) page["$local.${at.getLocalName(i)}"] = at.getValue(i)
                        "cfRule" -> { rule = (0 until at.length).associate { at.getLocalName(it) to at.getValue(it) }; formulas.clear(); cfvos.clear(); colors.clear() }
                        "formula" -> if (rule != null) { formula.setLength(0); into = formula }
                        "cfvo" -> if (rule != null) cfvos += SheetCfvo(at.a("type") ?: "min", at.a("val"))
                        "iconSet" -> rule?.let { rule = it + mapOf("iconSet" to (at.a("iconSet") ?: "3TrafficLights1"), "showValue" to (at.a("showValue") ?: "1"), "reverse" to (at.a("reverse") ?: "0")) }
                        "color" -> if (rule != null) color(at)?.let { colors += it } else if (inIs && rich.inPr) rich.prop(local, at)
                        "sheetView" -> { views++; if (views == 1 && at.a("showGridLines").let { it == "0" || it == "false" }) grid = false
                            if (views == 1 && at.a("showRowColHeaders").let { it == "0" || it == "false" }) headings = false }
                        "pane" -> if (views == 1 && at.a("state")?.startsWith("frozen") == true) {
                            frozenCols = at.a("xSplit")?.toDoubleOrNull()?.toInt() ?: 0
                            frozenRows = at.a("ySplit")?.toDoubleOrNull()?.toInt() ?: 0
                        }
                        "tabColor" -> tab = at.a("rgb")?.let { (it.takeLast(6).toLongOrNull(16)?.toInt() ?: 0) or 0xFF000000.toInt() }
                            ?: at.a("theme")?.toIntOrNull()?.let { theme.getOrNull(it) }
                        "sheetFormatPr" -> {
                            at.a("defaultRowHeight")?.toFloatOrNull()?.let { defaultRow = it * 4 / 3 }
                            defaultCol = at.a("defaultColWidth")?.toDoubleOrNull()?.let(::colPx)
                                ?: at.a("baseColWidth")?.toIntOrNull()?.let { (kotlin.math.ceil((it * 7 + 5) / 8.0) * 8).toFloat() } ?: 64f
                        }
                        "col" -> {
                            val a = (at.a("min")?.toIntOrNull() ?: return) - 1
                            val b = min((at.a("max")?.toIntOrNull() ?: (a + 1)) - 1, 16383)
                            val w = at.a("width")?.toDoubleOrNull()?.let(::colPx)
                            val hide = at.a("hidden").let { it == "1" || it == "true" }
                            val style = at.a("style")?.toIntOrNull()
                            for (k in a..b) {
                                if (w != null) colWidths[k] = w
                                if (hide) hiddenCols += k
                                if (style != null && style != 0 && b - a < 1024) colStyles[k] = style
                            }
                        }
                        "row" -> {
                            row = at.a("r")?.toIntOrNull()?.minus(1) ?: (row + 1); col = -1
                            at.a("ht")?.toFloatOrNull()?.let { rowHeights[row] = it * 4 / 3 }
                            if (at.a("hidden").let { it == "1" || it == "true" }) hiddenRows += row
                            at.a("outlineLevel")?.toIntOrNull()?.takeIf { it > 0 }?.let { rowLevels[row] = it }
                            if (at.a("customFormat").let { it == "1" || it == "true" }) at.a("s")?.toIntOrNull()?.takeIf { it != 0 }?.let { rowStyles[row] = it }
                        }
                        "c" -> {
                            val ref = at.a("r")?.let(::cellRef)
                            r = ref?.first ?: max(row, 0); c = ref?.second ?: (col + 1); col = c
                            s = at.a("s")?.toIntOrNull() ?: 0; t = at.a("t")
                            v.setLength(0); f.setLength(0); inline.setLength(0); hasV = false; fAttrs = null; rich.reset()
                        }
                        "v" -> { into = v; hasV = true }
                        "f" -> { into = f; fAttrs = Triple(at.a("t"), at.a("si"), at.a("ref")) }
                        "is" -> inIs = true
                        "rPh" -> phonetic++
                        "t" -> if (inIs && phonetic == 0) into = inline
                        "r" -> if (inIs && phonetic == 0) rich.open()
                        "rPr" -> if (inIs) rich.inPr = true
                        "b", "i", "u", "strike", "sz", "rFont" -> if (inIs && rich.inPr) rich.prop(local, at)
                        "mergeCell" -> at.a("ref")?.let(::rangeRef)?.let { merges += it }
                        "sheetProtection" -> { protectedSheet = at.a("sheet").let { it == "1" || it == "true" }; guardedSheet = at.a("password") != null || at.a("hashValue") != null }
                        "autoFilter" -> autoFilter = at.a("ref")?.let(::rangeRef)
                        "filterColumn" -> filterCol = (at.a("colId")?.toIntOrNull() ?: 0) + (autoFilter?.c1 ?: 0)
                        "filters" -> if (at.a("blank").let { it == "1" || it == "true" }) filterKeep.getOrPut(filterCol) { LinkedHashSet() } += ""
                        "filter" -> at.a("val")?.let { filterKeep.getOrPut(filterCol) { LinkedHashSet() } += it.trim() }
                        "tablePart" -> at.a("id")?.let { rels[it] }?.let { tableParts += it }
                        "drawing" -> drawingPart = at.a("id")?.let { rels[it] }
                        "hyperlink" -> {
                            val range = at.a("ref")?.let(::rangeRef) ?: return
                            val target = at.a("id")?.let { rels[it] }?.removePrefix("ext:") ?: at.a("location")?.let { "#$it" } ?: return
                            for (rr in range.r1..min(range.r2, range.r1 + 1000)) for (cc in range.c1..min(range.c2, range.c1 + 100)) links[Sheet.key(rr, cc)] = target
                        }
                    }
                }
                override fun endElement(uri: String?, local: String, q: String?) {
                    if (local == "extLst") { ext--; return }
                    if (ext > 0) return
                    when (local) {
                        "formula" -> if (rule != null) { formulas += formula.toString(); into = null }
                        "cfRule" -> rule?.let { a ->
                            val type = a["type"] ?: return@let
                            fun flag(n: String, default: Boolean) = a[n]?.let { it == "1" || it == "true" } ?: default
                            rules += SheetRule(cfRanges, a["priority"]?.toIntOrNull() ?: 1000, type, a["operator"], formulas.toList(),
                                a["dxfId"]?.toIntOrNull()?.let { dxfs.getOrNull(it) }, a["text"], a["rank"]?.toIntOrNull() ?: 10,
                                flag("percent", false), flag("bottom", false), flag("aboveAverage", true), flag("equalAverage", false), flag("stopIfTrue", false),
                                scale = if (type == "colorScale") cfvos.zip(colors) else emptyList(),
                                bar = colors.firstOrNull().takeIf { type == "dataBar" }, barEnds = if (type == "dataBar") cfvos.toList() else emptyList(),
                                icons = a["iconSet"], iconAt = if (type == "iconSet") cfvos.toList() else emptyList(),
                                showValue = a["showValue"] != "0" && a["showValue"] != "false", reverse = a["reverse"] == "1" || a["reverse"] == "true")
                            rule = null
                        }
                        "v", "f", "t", "formula1" -> into = null
                        "oddHeader", "oddFooter" -> { page[local] = headerFooter.toString(); into = null }
                        "dataValidation" -> { dvRanges?.let { dvs += it to dvFormula.toString() }; dvRanges = null }
                        "is" -> inIs = false
                        "r" -> if (inIs && phonetic == 0) rich.close()
                        "rPr" -> rich.inPr = false
                        "rPh" -> phonetic--
                        "c" -> cell()
                    }
                }
                override fun characters(ch: CharArray, start: Int, length: Int) { into?.appendRange(ch, start, start + length) }

                fun cell() {
                    var formula = f.toString().takeIf { it.isNotEmpty() }
                    fAttrs?.let { (type, si, ref) ->
                        if (type == "shared" && si != null) {
                            if (formula != null && ref != null) shared[si] = Triple(formula, r, c)
                            else shared[si]?.let { (base, br, bc) -> formula = shift(base, r - br, c - bc) }
                        }
                    }
                    val style = styles.getOrNull(s) ?: styles.firstOrNull() ?: SheetStyle()
                    val raw = v.toString()
                    val cell = when (t) {
                        "s" -> (raw.trim().toIntOrNull() ?: -1).let { k -> SheetCell(strings.getOrNull(k) ?: "", SheetKind.Text, style = s, runs = richStrings[k]) }
                        "inlineStr" -> SheetCell(inline.toString(), SheetKind.Text, style = s, runs = rich.done())
                        "str" -> SheetCell(raw, SheetKind.Text, style = s)
                        "b" -> SheetCell(if (raw.trim() == "1") "TRUE" else "FALSE", SheetKind.Bool, if (raw.trim() == "1") 1.0 else 0.0, style = s)
                        "e" -> SheetCell(raw, SheetKind.Error, style = s)
                        "d" -> runCatching { LocalDateTime.parse(raw.trim().let { if ('T' in it) it else "${it}T00:00" }) }.getOrNull()?.let { dt ->
                            val serial = ChronoUnit.SECONDS.between(LocalDate.of(1899, 12, 30).atStartOfDay(), dt) / 86_400.0
                            val shown = format.format(serial, style.format.takeIf { format.isDate(it) } ?: "d/m/yyyy")
                            SheetCell(shown.text, SheetKind.Number, serial, style = s, color = shown.color)
                        } ?: SheetCell(raw, SheetKind.Text, style = s)
                        else -> raw.trim().toDoubleOrNull()?.let { n ->
                            val shown = runCatching { format.format(n, style.format) }.getOrElse { SheetFormat.Shown(format.general(n)) }
                            SheetCell(shown.text, SheetKind.Number, n, style = s, color = shown.color)
                        } ?: empties.getOrPut(s) { SheetCell("", SheetKind.Empty, style = s) } // one for every empty cell of a look
                    }.let { if (formula != null) it.copy(formula = "=" + SheetCalc.fromFile(formula!!), link = hyperlink(formula!!),
                        spill = fAttrs?.takeIf { a -> a.first == "array" }?.third?.let(::rangeRef)?.takeIf { g -> g.r2 > g.r1 || g.c2 > g.c1 }) else it }
                    if (cell.kind == SheetKind.Empty && cell.formula == null && !style.boxed) return
                    cells[Sheet.key(r, c)] = cell
                    if (cell.kind != SheetKind.Empty) { maxRow = max(maxRow, r); maxCol = max(maxCol, c) } else boxed.add(r, c)
                }
            })
            links.forEach { (k, target) -> cells[k]?.let { cells[k] = it.copy(link = target) } }
            Sheets.markSpills(cells)
            runCatching { notes(rels).forEach { (k, note) -> cells[k] = (cells[k] ?: SheetCell("", SheetKind.Empty)).copy(note = note) } }
                .onFailure { WriterLog.w("Sheets: comments could not be read", it) }
            val looks = ArrayList<SheetStyle>()
            tableParts.forEach { p -> runCatching { parts(p)?.let { table(it, cells, looks) } }.onFailure { WriterLog.w("Sheets: a table could not be read", it) } }
            val icons = HashMap<Long, SheetIcon>(); val ruleBase = HashMap<Long, Int>()
            val bars = runCatching { SheetRules.apply(rules, cells, { i -> (if (i >= Sheet.LOOKS) looks.getOrNull(i - Sheet.LOOKS) else styles.getOrNull(i)) ?: SheetStyle() }, looks, icons, ruleBase) }
                .onFailure { WriterLog.w("Sheets: conditional formatting could not be laid", it) }.getOrDefault(emptyMap())
            if (rules.isNotEmpty()) WriterLog.i("Sheets: ${rules.size} conditional rules (${rules.map { it.type }.distinct().joinToString()})")
            merges.forEach { maxRow = max(maxRow, it.r2); maxCol = max(maxCol, it.c2) }
            maxRow = boxed.rows(maxRow); maxCol = boxed.cols(maxCol)
            WriterLog.i("Sheets: \"${name.length} letters\" ${cells.size} cells, ${maxRow + 1}×${maxCol + 1}, ${merges.size} merged, frozen $frozenRows/$frozenCols")
            // rows an AutoFilter hides are the view's filter's to hide (Clear filter shows them), not hidden rows
            if (filterKeep.isNotEmpty()) autoFilter?.let { g -> hiddenRows.removeAll { it in g.r1 + 1..g.r2 } }
            return Sheet(name, cells, maxRow + 1, maxCol + 1, colWidths, rowHeights, defaultCol, defaultRow, hiddenCols, hiddenRows,
                merges, frozenRows, frozenCols, grid, colStyles, rowStyles, tab, hidden, autoFilter, looks,
                drawingPart?.let { p -> runCatching { drawings(p, name, cells) }.onFailure { WriterLog.w("Sheets: drawings could not be read", it) }.getOrNull() }.orEmpty(), bars, icons,
                printOf(page, printNames[path])).also { it.rules = rules; it.ruleBase += ruleBase; it.protected = protectedSheet; it.guarded = guardedSheet; it.headings = headings; it.filterKeep = filterKeep; it.rowLevels = rowLevels
                    it.lists = dvs.mapNotNull { (ranges, f) -> SheetList.of(ranges, f, name) { r, c -> cells[Sheet.key(r, c)]?.text } } }
        }

        /** Excel's page setup and the sheet's print names as a [SheetPrint]. */
        fun printOf(page: Map<String, String>, names: Pair<String?, String?>?): SheetPrint {
            fun on(k: String) = page[k].let { it == "1" || it == "true" }
            val fit = on("pageSetUpPr.fitToPage")
            val area = names?.first?.split(',')?.firstOrNull()?.substringAfterLast('!')?.replace("$", "")?.let(::rangeRef)
            val titles = names?.second?.split(',')?.firstNotNullOfOrNull { Regex("""!\$?(\d+):\$?(\d+)$""").find(it) }?.let { it.groupValues[1].toInt() - 1..it.groupValues[2].toInt() - 1 }
            return SheetPrint(
                landscape = page["pageSetup.orientation"] == "landscape",
                scale = page["pageSetup.scale"]?.toIntOrNull()?.coerceIn(10, 400) ?: 100,
                fitWidth = if (fit) page["pageSetup.fitToWidth"]?.toIntOrNull() ?: 1 else null,
                fitHeight = if (fit) page["pageSetup.fitToHeight"]?.toIntOrNull() ?: 1 else null,
                grid = on("printOptions.gridLines"),
                margins = listOf("left", "right", "top", "bottom").map { page["pageMargins.$it"]?.toFloatOrNull() ?: if (it == "left" || it == "right") 0.7f else 0.75f },
                area = area, titles = titles, letter = page["pageSetup.paperSize"] == "1",
                header = page["oddHeader"]?.takeIf { it.isNotEmpty() }, footer = page["oddFooter"]?.takeIf { it.isNotEmpty() },
            )
        }

        /** The sheet's drawing part: each anchor's picture, chart or text box, where it sits. */
        fun drawings(path: String, sheetName: String, cells: CellMap): List<SheetDrawing> {
            val bytes = parts(path) ?: return emptyList()
            val rels = rels(path)
            val emu = 9525f // EMUs in a pixel
            val out = ArrayList<SheetDrawing>()
            var ordinal = -1
            for (a in OfficeFormats.parse(bytes).documentElement.elements()) {
                if (a.localName !in setOf("twoCellAnchor", "oneCellAnchor", "absoluteAnchor")) continue
                ordinal++
                fun at(e: Element?, name: String) = e?.kid(name)?.textContent?.trim()?.toLongOrNull() ?: 0L
                val from = a.kid("from"); val to = a.kid("to"); val ext = a.kid("ext"); val pos = a.kid("pos")
                val body = a.elements().firstOrNull { it.localName in setOf("pic", "graphicFrame", "sp") } ?: continue
                val chart = if (body.localName == "graphicFrame") body.below("chart").firstOrNull()?.let { c ->
                    val id = c.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
                    rels[id]?.let(parts)?.let { b -> OfficeCharts.readDrawingml(b)?.let { ch -> withValues(ch, b, sheetName, cells) } }
                } ?: continue else null
                val image = if (body.localName == "pic") body.below("blip").firstOrNull()?.let { b ->
                    val p = rels[b.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "embed")] ?: return@let null
                    { parts(p) }
                } ?: continue else null
                val crop = if (image != null) Slides.srcRect(body.kid("blipFill")?.let(OfficeFormats::xml)) else null
                val text = if (body.localName == "sp") body.below("p").joinToString("\n") { p -> p.below("t").joinToString("") { it.textContent } }.trim() else null
                // a colour: its own, or the theme's (accent1, lt1…), darker or lighter as it says (shade, tint)
                fun scheme(c: Element?): Int? {
                    val own = c?.kid("srgbClr")?.attr("val")?.toLongOrNull(16)?.toInt()?.or(0xFF000000.toInt())
                    val s = c?.kid("schemeClr")
                    val base = own ?: s?.attr("val")?.let { n ->
                        val i = when (n) { "lt1", "bg1" -> 0; "dk1", "tx1" -> 1; "lt2", "bg2" -> 2; "dk2", "tx2" -> 3; else -> n.removePrefix("accent").toIntOrNull()?.plus(3) } ?: return@let null
                        theme.getOrNull(i) ?: when (i) { 0 -> 0xFFFFFFFF.toInt(); 1 -> 0xFF000000.toInt(); else -> OfficeCharts.PALETTE.getOrNull(i - 4)?.or(0xFF000000.toInt()) }
                    } ?: return null
                    val k = (c?.kid("srgbClr") ?: s)!!
                    k.kid("shade")?.attr("val")?.toIntOrNull()?.let { v -> val f = v / 100_000f
                        fun ch(x: Int) = (x * f).toInt().coerceIn(0, 255)
                        return (base and 0xFF000000.toInt()) or (ch(base shr 16 and 255) shl 16) or (ch(base shr 8 and 255) shl 8) or ch(base and 255) }
                    k.kid("tint")?.attr("val")?.toIntOrNull()?.let { v -> val f = v / 100_000f
                        fun ch(x: Int) = (255 - (255 - x) * f).toInt().coerceIn(0, 255)
                        return (base and 0xFF000000.toInt()) or (ch(base shr 16 and 255) shl 16) or (ch(base shr 8 and 255) shl 8) or ch(base and 255) }
                    return base
                }
                fun solid(e: Element?) = e?.kid("solidFill")?.let(::scheme)
                val spPr = body.kid("spPr")
                // A shape's look: its own fill and outline, else its style's (Excel's default shape: the theme's accent, the outline darker).
                val style = body.kid("style")
                val fillC = if (spPr?.kid("noFill") != null) null else solid(spPr) ?: scheme(style?.kid("fillRef"))
                val ln = spPr?.kid("ln")
                val lineC = if (ln?.kid("noFill") != null) null else solid(ln) ?: scheme(style?.kid("lnRef"))
                val prst = spPr?.kid("prstGeom")?.attr("prst")
                val shape = if (body.localName == "sp" && prst != null && prst != "rect" && prst in ShapeGeometry.presets)
                    ShapePicture(prst, fillC, lineC, ((ln?.attr("w")?.toFloatOrNull() ?: 9525f) / emu).coerceAtLeast(0.5f),
                        adjust = spPr?.kid("prstGeom")?.below("gd")?.mapNotNull { g -> g.attr("fmla")?.removePrefix("val ")?.trim()?.toDoubleOrNull()?.let { (g.attr("name") ?: "") to it } }?.toMap().orEmpty())
                    else null
                val textColor = body.kid("txBody")?.below("rPr")?.firstNotNullOfOrNull { solid(it) } ?: scheme(style?.kid("fontRef"))
                val centred = body.kid("txBody")?.kid("bodyPr")?.attr("anchor") == "ctr"
                val rPr = body.kid("txBody")?.below("rPr")?.firstOrNull(); val fx = rPr?.kid("effectLst")
                fun words(d: SheetDrawing) { d.textPt = rPr?.attr("sz")?.toFloatOrNull()?.div(100); d.bold = rPr?.attr("b") == "1"
                    d.effects = TextEffects(rPr?.kid("ln")?.let(::solid)?.and(0xFFFFFF), fx?.kid("outerShdw") != null, fx?.kid("glow")?.let(::scheme)?.and(0xFFFFFF))
                    d.warp = body.kid("txBody")?.kid("bodyPr")?.kid("prstTxWarp")?.attr("prst")?.let { p -> TextWarp.entries.firstOrNull { it.word == p } } }
                if (body.localName == "sp" && text.isNullOrEmpty() && fillC == null && shape == null) continue
                val x = at(from, "colOff") / emu; val y = at(from, "rowOff") / emu
                out += (if (a.localName == "absoluteAnchor") SheetDrawing(0, 0, (pos?.attr("x")?.toLongOrNull() ?: 0) / emu, (pos?.attr("y")?.toLongOrNull() ?: 0) / emu,
                    width = (ext?.attr("cx")?.toLongOrNull() ?: 0) / emu, height = (ext?.attr("cy")?.toLongOrNull() ?: 0) / emu, chart = chart, image = image, text = text, fill = fillC, line = lineC, shape = shape, textColor = textColor, centred = centred, crop = crop)
                else if (to != null) SheetDrawing(at(from, "row").toInt(), at(from, "col").toInt(), x, y, at(to, "row").toInt(), at(to, "col").toInt(), at(to, "colOff") / emu, at(to, "rowOff") / emu,
                    chart = chart, image = image, text = text, fill = fillC, line = lineC, shape = shape, textColor = textColor, centred = centred, crop = crop)
                else SheetDrawing(at(from, "row").toInt(), at(from, "col").toInt(), x, y, width = (ext?.attr("cx")?.toLongOrNull() ?: 0) / emu, height = (ext?.attr("cy")?.toLongOrNull() ?: 0) / emu,
                    chart = chart, image = image, text = text, fill = fillC, line = lineC, shape = shape, textColor = textColor, centred = centred, crop = crop)).also { it.ordinal = ordinal; words(it) }
            }
            if (out.isNotEmpty()) WriterLog.i("Sheets: ${out.size} drawings (${out.count { it.chart != null }} charts, ${out.count { it.image != null }} pictures)")
            return out
        }

        /**
         * A chart whose file keeps no numbers of its own (Excel and LibreOffice keep them; some programs only name the
         * cells): its series read from this sheet's cells.
         */
        fun withValues(chart: DocChart, bytes: ByteArray, sheetName: String, cells: CellMap): DocChart {
            if (chart.series.any { s -> s.values.any { it != null } }) return chart
            fun range(f: String?): SheetRange? {
                val ref = f?.trim() ?: return null
                val sheet = ref.substringBeforeLast('!', "").trim('\'')
                if (sheet.isNotEmpty() && sheet != sheetName) return null
                return rangeRef(ref.substringAfterLast('!').replace("$", ""))
            }
            fun cellsOf(g: SheetRange) = (g.r1..g.r2).flatMap { r -> (g.c1..g.c2).map { c -> cells[Sheet.key(r, c)] } }
            val sers = OfficeFormats.parse(bytes).documentElement.below("ser")
            var categories = chart.categories
            val series = chart.series.mapIndexed { i, s ->
                val e = sers.getOrNull(i) ?: return@mapIndexed s
                val values = range(e.kid("val")?.below("f")?.firstOrNull()?.textContent ?: e.kid("yVal")?.below("f")?.firstOrNull()?.textContent)
                    ?.let(::cellsOf)?.map { it?.value } ?: return@mapIndexed s
                range((e.kid("cat") ?: e.kid("xVal"))?.below("f")?.firstOrNull()?.textContent)?.let(::cellsOf)?.map { it?.text.orEmpty() }?.let { if (it.size >= categories.size) categories = it }
                val name = range(e.kid("tx")?.below("f")?.firstOrNull()?.textContent)?.let(::cellsOf)?.firstOrNull()?.text
                val sizes = if (chart.kind == ChartKind.Bubble) range(e.kid("bubbleSize")?.below("f")?.firstOrNull()?.textContent)?.let(::cellsOf)?.map { it?.value } else null
                s.copy(values = values, name = name ?: s.name, sizes = sizes ?: s.sizes)
            }
            if (categories.size < (series.maxOfOrNull { it.values.size } ?: 0)) categories = (1..series.maxOf { it.values.size }).map { "$it" }
            return chart.copy(categories = categories, series = series)
        }

        /**
         * A sheet's comments: Excel's notes (comments1.xml, "Author:" as Excel writes it), and its threaded comments with
         * their replies, which take the place of the note Excel keeps for older versions.
         */
        fun notes(rels: Map<String, String>): Map<Long, String> {
            val out = HashMap<Long, String>()
            rels.values.firstOrNull { it.substringAfterLast('/').let { n -> n.startsWith("comment") && n.endsWith(".xml") } }?.let(parts)?.let { bytes ->
                val root = OfficeFormats.parse(bytes).documentElement
                val authors = root.kid("authors")?.kids("author")?.map { it.textContent }.orEmpty()
                root.kid("commentList")?.kids("comment")?.forEach { c ->
                    val at = c.attr("ref")?.let(::rangeRef) ?: return@forEach
                    val text = c.getElementsByTagNameNS("*", "t").let { l -> (0 until l.length).joinToString("") { l.item(it).textContent } }.trim()
                    val author = authors.getOrNull(c.attr("authorId")?.toIntOrNull() ?: -1)?.takeIf { it.isNotBlank() && !text.startsWith("$it:") }
                    out[Sheet.key(at.r1, at.c1)] = (author?.let { "$it: " } ?: "") + text
                }
            }
            rels.values.firstOrNull { it.substringAfterLast('/').startsWith("threadedComment") }?.let(parts)?.let { bytes ->
                val people = rels.values.firstOrNull { it.contains("person") }?.let(parts)?.let { p ->
                    val l = OfficeFormats.parse(p).getElementsByTagNameNS("*", "person")
                    (0 until l.length).associate { i -> (l.item(i) as Element).let { it.getAttribute("id") to it.getAttribute("displayName") } }
                } ?: parts("xl/persons/person.xml")?.let { p ->
                    val l = OfficeFormats.parse(p).getElementsByTagNameNS("*", "person")
                    (0 until l.length).associate { i -> (l.item(i) as Element).let { it.getAttribute("id") to it.getAttribute("displayName") } }
                }.orEmpty()
                val thread = LinkedHashMap<Long, MutableList<String>>()
                val l = OfficeFormats.parse(bytes).getElementsByTagNameNS("*", "threadedComment")
                for (i in 0 until l.length) {
                    val c = l.item(i) as Element
                    val at = c.attr("ref")?.let(::rangeRef) ?: continue
                    val who = people[c.getAttribute("personId")]?.takeIf { it.isNotBlank() }
                    val text = c.getElementsByTagNameNS("*", "text").item(0)?.textContent?.trim().orEmpty()
                    thread.getOrPut(Sheet.key(at.r1, at.c1)) { ArrayList() } += (who?.let { "$it: " } ?: "") + text
                }
                thread.forEach { (k, list) -> out[k] = list.joinToString("\n") }
            }
            return out
        }

        /**
         * An Excel Table (Insert › Table, and Google Sheets' alternating colours): its style's header, stripes, first
         * and last column and totals laid on its cells — under each cell's own look, as Excel does. The cells' new looks
         * go in [looks], numbered after the workbook's.
         */
        fun table(bytes: ByteArray, cells: CellMap, looks: ArrayList<SheetStyle>) {
            val t = OfficeFormats.parse(bytes).documentElement
            val ref = t.attr("ref")?.let(::rangeRef) ?: return
            val info = t.kid("tableStyleInfo") ?: return
            val name = info.attr("name") ?: return
            val parts = tableStyles[name] ?: SheetTables.builtIn(name, theme) ?: return WriterLog.i("Sheets: table style not known: built-in ${name.startsWith("TableStyle")}")
            if ((ref.r2 - ref.r1 + 1L) * (ref.c2 - ref.c1 + 1L) > 500_000) return
            fun on(a: String) = info.attr(a).let { it == "1" || it == "true" }
            val headers = t.attr("headerRowCount")?.toIntOrNull() ?: 1
            val totals = t.attr("totalsRowCount")?.toIntOrNull() ?: 0
            val index = HashMap<SheetStyle, Int>()
            for ((k, d) in SheetTables.lay(parts, ref, headers, totals, ::on)) {
                val cell = cells[k] ?: SheetCell("", SheetKind.Empty)
                val own = (if (cell.style >= Sheet.LOOKS) looks.getOrNull(cell.style - Sheet.LOOKS) else styles.getOrNull(cell.style)) ?: SheetStyle()
                val look = own.copy(
                    fill = own.fill ?: d.fill, color = own.color ?: d.color, bold = own.bold || d.bold == true, italic = own.italic || d.italic == true,
                    left = own.left ?: d.left, right = own.right ?: d.right, top = own.top ?: d.top, bottom = own.bottom ?: d.bottom,
                )
                if (look == own) continue
                cells[k] = cell.copy(style = Sheet.LOOKS + index.getOrPut(look) { looks += look; looks.lastIndex }, own = cell.fileStyle)
            }
        }
    }

    /**
     * Cells with only a fill or a border: they widen the used range only near the written ones (the user's school
     * timetable formats 1,024 columns × 65,536 rows of empty cells; Excel would let it be scrolled to the end, which is no
     * use to look at), so an empty bordered form still shows whole.
     */
    internal class Extent {
        private val rowsAt = java.util.BitSet(); private val colsAt = java.util.BitSet()
        fun add(r: Int, c: Int) { rowsAt.set(r); colsAt.set(c) }
        fun rows(written: Int) = max(written, rowsAt.previousSetBit(written + 100))
        fun cols(written: Int) = max(written, colsAt.previousSetBit(written + 26))
    }

    // --- .ods ---

    /** LibreOffice's length ("2.258cm", "0.1736in", "10pt") in pixels at 96 per inch. */
    fun odfPx(length: String?): Float? {
        val m = Regex("""^(-?[0-9.]+)\s*(cm|mm|in|pt|pc|px)?$""").find(length?.trim() ?: return null) ?: return null
        val n = m.groupValues[1].toFloatOrNull() ?: return null
        return when (m.groupValues[2]) { "cm" -> n / 2.54f * 96; "mm" -> n / 25.4f * 96; "in" -> n * 96; "pc" -> n * 16; "px" -> n; else -> n * 4 / 3 }
    }

    private class Ods(val parts: (String) -> ByteArray?, val stream: (String) -> java.io.InputStream?, val sep: Pair<Char, Char> = '.' to ',') {
        /** Every style:style by name: its family, parent, and the properties ("c.", "t.", "p.", "col.", "row.", "tab." + name). */
        class Raw(val family: String, val parent: String?, val props: HashMap<String, String> = HashMap())
        val raw = HashMap<String, Raw>()
        /** A style's name by the name people see ("ConditionalStyle_1" for "ConditionalStyle_5f_1"): conditions use it. */
        val shown = HashMap<String, String>()
        var defaultCell: Raw? = null
        val styles = ArrayList<SheetStyle>()
        val styleIndex = HashMap<String, Int>()

        /** A style's properties with its parents' under them ("Default" under every cell style, as LibreOffice). */
        fun props(name: String?, seen: Int = 0): Map<String, String> {
            val r = raw[name ?: "Default"] ?: return defaultCell?.props.orEmpty()
            val parent = r.parent ?: if (r.family == "table-cell" && name != null && name != "Default") "Default" else null
            val base = if (parent == null || seen > 20) defaultCell?.props.orEmpty() else props(parent, seen + 1)
            return base + r.props
        }

        /** A number style's colour ([Red] in LibreOffice) and its maps: "value()>=0" → the style used then. */
        class DataStyle(var color: Int? = null, val maps: ArrayList<Pair<String, String>> = ArrayList(), val code: StringBuilder = StringBuilder(), val kind: String = "")
        val dataStyles = HashMap<String, DataStyle>()
        private val dataStyleOf = HashMap<String?, String?>()

        /** The colour a number's data style gives it ([value] picks the map), if any. */
        fun numberColor(styleName: String?, value: Double): Int? {
            val name = dataStyleOf.getOrPut(styleName) { props(styleName)["data-style"] } ?: return null
            var d = dataStyles[name] ?: return null
            for ((condition, apply) in d.maps) {
                val m = Regex("""value\(\)\s*(<=|>=|!=|<|>|=)\s*(-?[0-9.]+)""").find(condition) ?: continue
                val n = m.groupValues[2].toDoubleOrNull() ?: continue
                val yes = when (m.groupValues[1]) { "<" -> value < n; ">" -> value > n; "<=" -> value <= n; ">=" -> value >= n; "=" -> value == n; else -> value != n }
                if (yes) { d = dataStyles[apply] ?: d; break }
            }
            return d.color
        }

        /**
         * A number style as an Excel format code (asked 16 Oct: an edited .ods number shown as LibreOffice shows it):
         * its own code, after the code of the style its map uses for value()>=0 (LibreOffice's positive;negative).
         */
        fun formatCode(name: String?, seen: Int = 0): String {
            val d = dataStyles[name ?: return "General"] ?: return "General"
            val own = d.code.toString().ifBlank { return "General" }
            val positive = d.maps.firstOrNull { it.first.replace(" ", "") == "value()>=0" }?.second
            return if (positive != null && seen < 3 && positive != name) formatCode(positive, seen + 1) + ";" + own else own
        }

        fun styleHandler() = object : DefaultHandler() {
            var current: Raw? = null
            var data: DataStyle? = null
            var literal: StringBuilder? = null // number:text or a currency symbol being read
            override fun characters(ch: CharArray, start: Int, length: Int) { literal?.appendRange(ch, start, start + length) }
            /** One part of a number style, in Excel's words. */
            fun part(local: String, at: Attributes) {
                val d = data ?: return
                val long = at.a("style") == "long"
                when (local) {
                    "number" -> {
                        val places = at.a("decimal-places")?.toIntOrNull() ?: 0
                        val min = at.a("min-decimal-places")?.toIntOrNull() ?: places
                        val ints = at.a("min-integer-digits")?.toIntOrNull() ?: 1
                        d.code.append(if (at.a("grouping") == "true") "#,##0" else if (ints == 0) "#" else "0".repeat(ints))
                        if (places > 0) d.code.append('.').append("0".repeat(min)).append("#".repeat(places - min))
                    }
                    "scientific-number" -> d.code.append("0").append(at.a("decimal-places")?.toIntOrNull()?.takeIf { it > 0 }?.let { "." + "0".repeat(it) } ?: "").append("E+").append("0".repeat(at.a("min-exponent-digits")?.toIntOrNull() ?: 2))
                    "fraction" -> d.code.append("# ").append("?".repeat(at.a("min-numerator-digits")?.toIntOrNull() ?: 1)).append('/').append("?".repeat(at.a("min-denominator-digits")?.toIntOrNull() ?: 1))
                    "day" -> d.code.append(if (long) "dd" else "d")
                    "month" -> d.code.append(if (at.a("textual") == "true") (if (long) "mmmm" else "mmm") else if (long) "mm" else "m")
                    "year" -> d.code.append(if (long) "yyyy" else "yy")
                    "day-of-week" -> d.code.append(if (long) "dddd" else "ddd")
                    "hours" -> d.code.append(if (long) "hh" else "h")
                    "minutes" -> d.code.append(if (long) "mm" else "m")
                    "seconds" -> d.code.append(if (long) "ss" else "s").append(at.a("decimal-places")?.toIntOrNull()?.takeIf { it > 0 }?.let { "." + "0".repeat(it) } ?: "")
                    "am-pm" -> d.code.append("AM/PM")
                    "text-content" -> d.code.append("@")
                    "text", "currency-symbol" -> literal = StringBuilder()
                }
            }
            override fun startElement(uri: String?, local: String, q: String?, at: Attributes) {
                if (data != null && uri == "urn:oasis:names:tc:opendocument:xmlns:datastyle:1.0") part(local, at)
                when (local) {
                    "style" -> current = Raw(at.a("family") ?: "", at.a("parent-style-name")).also { s ->
                        at.a("name")?.let { raw[it] = s; at.a("display-name")?.let { d -> shown[d] = it } }
                        at.a("data-style-name")?.let { s.props["data-style"] = it }
                        at.a("master-page-name")?.let { s.props["master-page"] = it }
                    }
                    "number-style", "currency-style", "percentage-style", "date-style", "time-style", "boolean-style", "text-style" ->
                        if (uri == "urn:oasis:names:tc:opendocument:xmlns:datastyle:1.0") data = DataStyle(kind = local).also { d -> at.a("name")?.let { dataStyles[it] = d } }
                    "map" -> data?.let { d -> val c = at.a("condition"); val a = at.a("apply-style-name"); if (c != null && a != null) d.maps += c to a }
                    "default-style" -> current = if (at.a("family") == "table-cell") Raw("table-cell", null).also { defaultCell = it } else null
                    "table-cell-properties", "text-properties", "paragraph-properties", "table-column-properties", "table-row-properties", "table-properties", "graphic-properties" -> {
                        val p = when (local) {
                            "table-cell-properties" -> "c."; "text-properties" -> "t."; "paragraph-properties" -> "p."
                            "table-column-properties" -> "col."; "table-row-properties" -> "row."; "graphic-properties" -> "g."; else -> "tab."
                        }
                        if (local == "text-properties") data?.let { d -> d.color = hex(at.a("color")) }
                        current?.let { s -> for (i in 0 until at.length) s.props[p + at.getLocalName(i)] = at.getValue(i) }
                    }
                }
            }
            override fun endElement(uri: String?, local: String, q: String?) {
                literal?.takeIf { local == "text" || local == "currency-symbol" }?.let { t ->
                    literal = null
                    val d = data
                    // a percent's %, as Excel's (it scales); other words in quotes
                    if (d != null && t.isNotEmpty()) d.code.append(if (d.kind == "percentage-style" && t.toString() == "%") "%" else "\"" + t.toString().replace("\"", "") + "\"")
                }
                if (local == "style" || local == "default-style") current = null
                if (local.endsWith("-style") && local != "default-style" && uri == "urn:oasis:names:tc:opendocument:xmlns:datastyle:1.0") data = null
            }
        }

        fun hex(s: String?): Int? = s?.takeIf { it.startsWith("#") && it.length == 7 }?.substring(1)?.toLongOrNull(16)?.toInt()?.or(0xFF000000.toInt())

        fun border(s: String?): SheetLine? {
            if (s == null || s == "none" || s.startsWith("0 ") || s.startsWith("0pt")) return null
            val parts = s.split(' ')
            val w = odfPx(parts.firstOrNull())?.let { if (it < 1.3f) 1f else if (it < 2.6f) 2f else 3f } ?: 1f
            val color = parts.firstNotNullOfOrNull { hex(it) } ?: 0xFF000000.toInt()
            return SheetLine(color, w, double = parts.any { it == "double" || it == "double-thin" }, dashed = parts.any { it in setOf("dashed", "dash-dot", "dash-dot-dot", "fine-dashed") }, dots = "dotted" in parts)
        }

        /** After the first pass every look is made: a name not seen then is the default (formatting adds after them). */
        var sealed = false
        fun style(name: String?): Int = if (sealed) styleIndex[name ?: ""] ?: 0 else styleIndex.getOrPut(name ?: "") {
            val p = props(name)
            val fix = p["c.text-align-source"] != "value-type"
            val all = p["c.border"]
            styles += SheetStyle(
                font = p["t.font-name"] ?: p["t.font-family"], size = odfPx(p["t.font-size"])?.let { it * 3 / 4 } ?: 10f,
                bold = p["t.font-weight"].let { it == "bold" || (it?.toIntOrNull() ?: 400) >= 600 },
                italic = p["t.font-style"] == "italic" || p["t.font-style"] == "oblique",
                underline = p["t.text-underline-style"].let { it != null && it != "none" },
                strike = p["t.text-line-through-style"].let { it != null && it != "none" },
                color = hex(p["t.color"]), fill = hex(p["c.background-color"]),
                align = if (!fix) SheetAlign.General else when (p["p.text-align"]) {
                    "start", "left" -> SheetAlign.Left; "center" -> SheetAlign.Center; "end", "right" -> SheetAlign.Right
                    "justify" -> SheetAlign.Justify; else -> SheetAlign.General
                },
                valign = when (p["c.vertical-align"]) { "top" -> SheetVAlign.Top; "middle" -> SheetVAlign.Center; else -> SheetVAlign.Bottom },
                wrap = p["c.wrap-option"] == "wrap", shrink = p["c.shrink-to-fit"] == "true", locked = p["c.cell-protect"].let { it == null || "protected" in it },
                // as Excel numbers it: 0–90 up, 91–180 down (90 + degrees); LibreOffice's other angles (upside down) not kept
                rotation = p["c.rotation-angle"]?.toFloatOrNull()?.toInt()?.let { a -> when (a) { in 0..90 -> a; in 270..359 -> 90 + (360 - a); else -> 0 } } ?: 0,
                // LibreOffice's indent as Excel's levels: 0.2965 cm (8.4 pt) each, as LibreOffice turns Excel's into its own
                indent = odfPx(p["p.margin-left"])?.let { (it * 3 / 4 / 8.4f).roundToInt() } ?: 0,
                left = border(p["c.border-left"] ?: all), right = border(p["c.border-right"] ?: all),
                top = border(p["c.border-top"] ?: all), bottom = border(p["c.border-bottom"] ?: all),
                format = formatCode(p["data-style"]),
            )
            styles.lastIndex
        }

        lateinit var settings: Settings

        /** The looks and the sheets' names, in one pass that skips every row; [table] reads one sheet when it is opened. */
        fun read(): Workbook {
            parts("styles.xml")?.let { sax(it, styleHandler()) }
            settings = readSettings()
            // The automatic styles, at the head of content.xml: read until the body begins.
            var locked = false
            sax(stream("content.xml") ?: sheetProblem(R.string.tables_err_not_spreadsheet), object : DefaultHandler() {
                val autoStyles = styleHandler()
                var inStyles = false
                override fun startElement(uri: String?, local: String, q: String?, at: Attributes) {
                    // the body's spreadsheet: whether its structure is protected (Review › Protect workbook)
                    if (local == "spreadsheet") { locked = at.getValue("urn:oasis:names:tc:opendocument:xmlns:table:1.0", "structure-protected") == "true"; throw Enough() }
                    if (local == "automatic-styles") inStyles = true
                    if (inStyles) autoStyles.startElement(uri, local, q, at)
                }
                override fun endElement(uri: String?, local: String, q: String?) {
                    if (inStyles) { autoStyles.endElement(uri, local, q); if (local == "automatic-styles") inStyles = false }
                }
                override fun characters(ch: CharArray, start: Int, length: Int) { if (inStyles) autoStyles.characters(ch, start, length) }
            })
            // The sheets' names: each <table:table …> found in the bytes, not parsed (a 360 MB body takes a second, not a minute).
            // meta.xml says how many there are, so the scan stops at the last one's start.
            // shortcut: a giant .ods (LibreOffice's copy of the user's 46 tables: 360 MB of content) takes ~9 s here on
            // the phone, inflating; upgrade to tabs that arrive while the first sheet shows if such files are opened.
            val count = parts("meta.xml")?.let { Regex("""table-count="(\d+)"""").find(String(it))?.groupValues?.get(1)?.toIntOrNull() }
            val tabs = (stream("content.xml") ?: sheetProblem(R.string.tables_err_not_spreadsheet)).use { tables(it, count) }.map { (name, style) -> SheetTab(name, props(style)["tab.display"] == "false") }
            if (tabs.isEmpty()) sheetProblem(R.string.tables_err_no_sheets)
            // Every cell look made now, so the list does not grow while a sheet is drawn.
            style(null) // index 0: the default look
            raw.filter { it.value.family == "table-cell" }.keys.sorted().forEach(::style)
            sealed = true
            WriterLog.i("Sheets: ods, ${tabs.size} sheets, ${styles.size} looks")
            val active = tabs.indexOfFirst { it.name == settings.active }.coerceAtLeast(0)
            val names = Array(styles.size) { "Default" }.also { a -> styleIndex.forEach { (n, i) -> if (n.isNotEmpty()) a[i] = n } }.toList()
            // the named ranges are at content.xml's end: read when a formula first needs one (a giant file opens without it)
            val named by lazy { runCatching { parts("content.xml")?.let { SheetNames.ods(String(it, Charsets.UTF_8)) } }.getOrNull().orEmpty() }
            val lazyNames = object : AbstractMap<String, String>() { override val entries get() = named.entries }
            val listNames = { runCatching { parts("content.xml")?.let { c -> Regex("""<table:named-(?:range|expression)\b[^>]*\btable:name="([^"]*)"""").findAll(String(c, Charsets.UTF_8)).map { it.groupValues[1] }.toList() } }.getOrNull().orEmpty()
                .mapNotNull { n -> named[n.lowercase()]?.let { n to it } } }
            return Workbook(tabs, styles, active, kind = "ods", format = SheetFormat(sep.first, sep.second, calc = true), styleNames = names, names = lazyNames, nameList = listNames,
                structureLocked = locked) { i -> table(stream("content.xml") ?: sheetProblem(R.string.tables_err_not_spreadsheet), i) }
        }

        /** Each <table:table> start tag's name and style, found in the bytes (tables inside cells are not sheets: skipped). */
        fun tables(input: java.io.InputStream, count: Int? = null): List<Pair<String, String?>> {
            val out = ArrayList<Pair<String, String?>>()
            val buf = ByteArray(1 shl 16); val name = java.io.ByteArrayOutputStream(); val attrs = java.io.ByteArrayOutputStream()
            var state = 0 // 0: words, 1: a tag's name, 2: a table's attributes
            var depth = 0
            fun unescape(v: String) = v.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
            fun start(a: String) {
                if (depth == 0) out += unescape(Regex("""table:name="([^"]*)"""").find(a)?.groupValues?.get(1) ?: "Sheet") to
                    Regex("""table:style-name="([^"]*)"""").find(a)?.groupValues?.get(1)?.let(::unescape)
                if (!a.trimEnd().endsWith("/")) depth++
            }
            while (true) {
                val n = input.read(buf); if (n < 0) break
                for (i in 0 until n) {
                    val b = buf[i].toInt()
                    when (state) {
                        0 -> if (b == '<'.code) { name.reset(); state = 1 }
                        1 -> if (b == ' '.code || b == '\n'.code || b == '\r'.code || b == '\t'.code || b == '>'.code || (b == '/'.code && name.size() > 0)) {
                            when (name.toString("UTF-8")) {
                                "table:table" -> if (b == '>'.code) { start(""); state = 0 } else { attrs.reset(); if (b == '/'.code) attrs.write(b); state = 2 }
                                "/table:table" -> { depth--; state = 0 }
                                else -> state = 0
                            }
                        } else if (name.size() > 16) state = 0 else name.write(b)
                        else -> if (b == '>'.code) { start(attrs.toString("UTF-8")); state = 0; if (out.size == count) return out } else attrs.write(b)
                    }
                }
            }
            return out
        }

        fun table(content: java.io.InputStream, target: Int): Sheet {
            var result: Sheet? = null
            var tableIndex = -1
            sax(content, object : DefaultHandler() {
                var inStyles = false
                var name = ""; var tableStyle: String? = null; var tableProtected = false; var tableGuarded = false; var titlesFrom = -1; var titlesTo = -1; var groupDepth = 0; val rowLevels = HashMap<Int, Int>(); var printRanges: String? = null
                var cells = CellMap()
                var colWidths = HashMap<Int, Float>(); var hiddenCols = HashSet<Int>(); var colDefaults = HashMap<Int, String>()
                var rowRuns = ArrayList<Triple<Int, Int, Float>>(); var hiddenRuns = ArrayList<Pair<Int, Int>>()
                var merges = ArrayList<SheetRange>()
                var col = 0; var row = 0; var rowRepeat = 1; var rowDefault: String? = null
                var maxRow = -1; var maxCol = -1
                var boxed = Extent()
                var rowCells = ArrayList<Pair<Int, SheetCell>>()
                // the cell being read
                var inCell = false; var cellRepeat = 1; var cellAttrs: Map<String, String> = emptyMap()
                val text = StringBuilder(); var paras = 0; var skip = 0; var inP = false; var link: String? = null
                // a cell's annotation: its author and words
                var inNote = false; var inNoteP = false; var inCreator = false; val note = StringBuilder(); val author = StringBuilder()
                // a picture or chart: where it is, and what it holds
                var frameDepth = 0; var frame: SheetDrawing? = null; var frameObject: String? = null; var frameImage: String? = null; var drawOrdinal = -1
                // a shape (draw:custom-shape): its style, its geometry's attributes and equations, its words
                var shapeStyle: String? = null; var shapeGeo: HashMap<String, String>? = null; val shapeEqs = ArrayList<Pair<String, String>>()
                val shapeText = StringBuilder(); var shapeSpan: String? = null
                // a cell's rich text: where each look starts in its words, the spans open now
                val cuts = ArrayList<Pair<Int, DocRun>>(); val spanLooks = ArrayList<DocRun>()
                /** A text style's look over [base] (LibreOffice's span): its own properties only. */
                fun spanLook(base: DocRun, name: String?): DocRun {
                    val p = raw[name ?: return base]?.props ?: return base
                    return base.copy(bold = p["t.font-weight"]?.let { it == "bold" || (it.toIntOrNull() ?: 400) >= 600 } ?: base.bold,
                        italic = p["t.font-style"]?.let { it == "italic" || it == "oblique" } ?: base.italic,
                        underline = p["t.text-underline-style"]?.let { it != "none" } ?: base.underline,
                        strike = p["t.text-line-through-style"]?.let { it != "none" } ?: base.strike,
                        sizePt = p["t.font-size"]?.takeIf { it.endsWith("pt") }?.removeSuffix("pt")?.toFloatOrNull() ?: base.sizePt,
                        color = hex(p["t.color"])?.and(0xFFFFFF) ?: base.color,
                        font = (p["t.font-name"] ?: p["t.font-family"])?.let { Slides.face(it.trim('\'')) } ?: base.font)
                }
                var drawings = ArrayList<SheetDrawing>()
                // LibreOffice's conditional formats (calcext:), in the order they come: the first wins
                var rules = ArrayList<SheetRule>(); var cfRanges = emptyList<SheetRange>(); val entries = ArrayList<Pair<SheetCfvo, Int?>>(); var barColor: Int? = null
                var iconSet: String? = null; var iconShow = true
                // LibreOffice's validities (before the tables) and the cells that take each: drop-down lists
                val validities = HashMap<String, String>(); val validCells = HashMap<String, ArrayList<SheetRange>>()
                fun ranges(address: String?) = address.orEmpty().split(' ').mapNotNull { a -> rangeRef(a.split(':').joinToString(":") { it.substringAfterLast('.').replace("$", "") }) }

                override fun startElement(uri: String?, local: String, q: String?, at: Attributes) {
                    if (local == "automatic-styles") inStyles = true
                    if (inStyles) return
                    if (skip > 0) { skip++; return }
                    if (frameDepth > 0) {
                        frameDepth++
                        when (local) {
                            "object" -> frameObject = frameObject ?: at.a("href"); "image" -> frameImage = frameImage ?: at.a("href")
                            "enhanced-geometry" -> shapeGeo = HashMap<String, String>().also { m -> for (i in 0 until at.length) m[at.getLocalName(i)] = at.getValue(i) }
                            "equation" -> { val n = at.a("name"); val f = at.a("formula"); if (n != null && f != null) shapeEqs += n to f }
                            "p" -> if (shapeStyle != null && shapeText.isNotEmpty()) shapeText.append('\n')
                            "span" -> if (shapeStyle != null) shapeSpan = shapeSpan ?: at.a("style-name")
                        }
                        return
                    }
                    when (local) {
                        "content-validation" -> { val n = at.a("name"); val c = at.a("condition"); if (n != null && c != null) validities[n] = c }
                        "table" -> {
                            tableIndex++
                            if (tableIndex != target) { skip = 1; return }
                            name = at.a("name") ?: "Sheet"; tableStyle = at.a("style-name"); tableProtected = at.a("protected") == "true"; tableGuarded = !at.a("protection-key").isNullOrEmpty(); printRanges = at.a("print-ranges")
                            cells = CellMap(); colWidths = HashMap(); hiddenCols = HashSet(); colDefaults = HashMap()
                            rowRuns = ArrayList(); hiddenRuns = ArrayList(); merges = ArrayList()
                            col = 0; row = 0; maxRow = -1; maxCol = -1; boxed = Extent(); drawings = ArrayList(); rules = ArrayList()
                        }
                        "table-column" -> {
                            val n = at.a("number-columns-repeated")?.toIntOrNull() ?: 1
                            val w = odfPx(props(at.a("style-name"))["col.column-width"])
                            val hide = at.a("visibility").let { it == "collapse" || it == "filter" }
                            val def = at.a("default-cell-style-name")?.takeIf { it != "Default" }
                            for (k in col until min(col + n, 16384)) {
                                if (k > col + 1024) break
                                if (w != null) colWidths[k] = w
                                if (hide) hiddenCols += k
                                if (def != null) colDefaults[k] = def
                            }
                            col += n
                        }
                        "table-header-rows" -> titlesFrom = row // rows printed on every page
                        "table-row-group" -> groupDepth++
                        // rich text: a span's look from where its words start
                        "span" -> if (inP) { val look = spanLook(spanLooks.lastOrNull() ?: DocRun(""), at.a("style-name")); spanLooks += look; cuts += text.length to look }
                        "table-row" -> {
                            rowRepeat = at.a("number-rows-repeated")?.toIntOrNull() ?: 1
                            if (groupDepth > 0) for (k in row until row + minOf(rowRepeat, 1000)) rowLevels[k] = groupDepth
                            rowDefault = at.a("default-cell-style-name")
                            odfPx(props(at.a("style-name"))["row.row-height"])?.let { rowRuns += Triple(row, rowRepeat, it) }
                            if (at.a("visibility").let { it == "collapse" || it == "filter" }) hiddenRuns += row to rowRepeat
                            col = 0; rowCells = ArrayList()
                        }
                        "table-cell", "covered-table-cell" -> {
                            cellRepeat = at.a("number-columns-repeated")?.toIntOrNull() ?: 1
                            if (local == "covered-table-cell") { col += cellRepeat; skip = 1; return }
                            inCell = true; text.setLength(0); paras = 0; link = null; note.setLength(0); author.setLength(0); cuts.clear(); spanLooks.clear()
                            cellAttrs = HashMap<String, String>().also { m -> for (i in 0 until at.length) m[at.getLocalName(i)] = at.getValue(i) }
                        }
                        "annotation" -> if (inCell) inNote = true else skip = 1
                        "creator" -> if (inNote) inCreator = true
                        // In a cell, its place is from the cell's corner; in the sheet's shapes, from the sheet's.
                        "frame" -> {
                            frameDepth = 1; frameObject = null; frameImage = null
                            frame = SheetDrawing(if (inCell) row else 0, if (inCell) col else 0, odfPx(at.a("x")) ?: 0f, odfPx(at.a("y")) ?: 0f,
                                width = odfPx(at.a("width")) ?: 0f, height = odfPx(at.a("height")) ?: 0f).also { it.ordinal = ++drawOrdinal }
                        }
                        // a shape: in its own outline (Excel's presets, LibreOffice's kinds and paths), its words in it
                        "custom-shape" -> {
                            frameDepth = 1; frameObject = null; frameImage = null
                            shapeStyle = at.a("style-name") ?: "Default"; shapeGeo = null; shapeEqs.clear(); shapeText.setLength(0); shapeSpan = null
                            frame = SheetDrawing(if (inCell) row else 0, if (inCell) col else 0, odfPx(at.a("x")) ?: 0f, odfPx(at.a("y")) ?: 0f,
                                width = odfPx(at.a("width")) ?: 0f, height = odfPx(at.a("height")) ?: 0f).also { it.ordinal = ++drawOrdinal }
                        }
                        "line", "rect", "ellipse", "g" -> skip = 1
                        "conditional-format" -> cfRanges = ranges(at.a("target-range-address"))
                        "condition" -> odsRule(at.a("value").orEmpty(), at.a("apply-style-name"), rules.size)?.let { rules += it }
                        "color-scale", "data-bar", "icon-set" -> {
                            entries.clear(); barColor = if (local == "data-bar") hex(at.a("positive-color")) ?: 0xFF638EC6.toInt() else null
                            iconSet = if (local == "icon-set") at.a("icon-set-type") ?: "3TrafficLights1" else null; iconShow = at.a("show-value") != "false"
                        }
                        "color-scale-entry", "formatting-entry" -> {
                            entries += SheetCfvo(at.a("type") ?: "minimum", at.a("value")) to hex(at.a("color"))
                            if (at.a("show-value") == "false") iconShow = false // LibreOffice writes it on the first entry
                        }
                        "p", "h" -> if (inNote) { if (note.isNotEmpty()) note.append('\n'); inNoteP = true } else if (inCell) { if (paras > 0) text.append('\n'); paras++; inP = true }
                        "s" -> if (inP) repeat(at.a("c")?.toIntOrNull() ?: 1) { text.append(' ') }
                        "tab" -> if (inP) text.append('\t')
                        "line-break" -> if (inP) text.append('\n')
                        // LibreOffice's link to a cell is "#Sheet.B5": as Excel's, "#Sheet!B5".
                        "a" -> if (inCell) link = at.a("href")?.let { if (it.startsWith("#") && '!' !in it && '.' in it) "#" + it.drop(1).substringBeforeLast('.') + "!" + it.substringAfterLast('.') else it }
                    }
                }

                override fun endElement(uri: String?, local: String, q: String?) {
                    if (inStyles) { if (local == "automatic-styles") inStyles = false; return }
                    if (skip > 0) { skip--; return }
                    if (frameDepth > 0) { frameDepth--; if (frameDepth == 0) endFrame(); return }
                    when (local) {
                        "color-scale" -> rules += SheetRule(cfRanges, rules.size, "colorScale", scale = entries.mapNotNull { (v, c) -> c?.let { v to it } })
                        "data-bar" -> rules += SheetRule(cfRanges, rules.size, "dataBar", bar = barColor, barEnds = entries.map { it.first })
                        "icon-set" -> rules += SheetRule(cfRanges, rules.size, "iconSet", icons = iconSet, iconAt = entries.map { it.first }, showValue = iconShow)
                    }
                    when (local) {
                        "p", "h" -> { inP = false; inNoteP = false }
                        "span" -> if (inP && spanLooks.isNotEmpty()) { spanLooks.removeAt(spanLooks.lastIndex); cuts += text.length to (spanLooks.lastOrNull() ?: DocRun("")) }
                        "annotation" -> inNote = false
                        "creator" -> inCreator = false
                        "table-cell" -> { endCell(); inCell = false }
                        "table-row" -> endRow()
                        "table-header-rows" -> if (titlesFrom >= 0) titlesTo = row - 1
                        "table-row-group" -> groupDepth--
                        "table" -> endTable()
                    }
                }

                /** A LibreOffice condition ("cell-content()>5", "cell-content-is-between(1;5)", "is-true-formula(…)", "duplicate"…) as Excel's rule. */
                fun odsRule(value: String, styleName: String?, priority: Int): SheetRule? {
                    val st = styleName?.let { styles[style(if (it in raw) it else shown[it] ?: it)] } ?: return null
                    val d = SheetDxf(fill = st.fill, color = st.color, bold = st.bold.takeIf { it }, italic = st.italic.takeIf { it },
                        left = st.left, right = st.right, top = st.top, bottom = st.bottom)
                    fun inner(name: String) = value.removePrefix(name).removePrefix("(").removeSuffix(")")
                    fun quoted(x: String) = x.trim().removeSurrounding("\"")
                    fun rule(type: String, operator: String? = null, formulas: List<String> = emptyList(), text: String? = null, rank: Int = 10, percent: Boolean = false, bottom: Boolean = false, above: Boolean = true, equal: Boolean = false) =
                        SheetRule(cfRanges, priority, type, operator, formulas.map { odfFormula(it).removePrefix("=") }, d, text, rank, percent, bottom, above, equal)
                    // calcext's ">7", "between(2,3)", "formula-is(…)"; the older style:map's "cell-content()>7", "is-true-formula(…)".
                    Regex("""^(?:cell-content\(\)\s*)?(<=|>=|!=|<|>|=)\s*(.+)$""").matchEntire(value)?.let { m ->
                        val op = mapOf("=" to "equal", "!=" to "notEqual", ">" to "greaterThan", ">=" to "greaterThanOrEqual", "<" to "lessThan", "<=" to "lessThanOrEqual").getValue(m.groupValues[1])
                        return rule("cellIs", op, listOf(m.groupValues[2]))
                    }
                    return when {
                        value.startsWith("between(") -> rule("cellIs", "between", SheetRules.argsOf(inner("between")))
                        value.startsWith("not-between(") -> rule("cellIs", "notBetween", SheetRules.argsOf(inner("not-between")))
                        value.startsWith("cell-content-is-between(") -> rule("cellIs", "between", SheetRules.argsOf(inner("cell-content-is-between")))
                        value.startsWith("cell-content-is-not-between(") -> rule("cellIs", "notBetween", SheetRules.argsOf(inner("cell-content-is-not-between")))
                        value.startsWith("formula-is(") -> rule("expression", formulas = listOf(inner("formula-is")))
                        value.startsWith("is-true-formula(") -> rule("expression", formulas = listOf(inner("is-true-formula")))
                        value.startsWith("contains-text(") -> rule("containsText", text = quoted(inner("contains-text")))
                        value.startsWith("not-contains-text(") -> rule("notContainsText", text = quoted(inner("not-contains-text")))
                        value.startsWith("begins-with(") -> rule("beginsWith", text = quoted(inner("begins-with")))
                        value.startsWith("ends-with(") -> rule("endsWith", text = quoted(inner("ends-with")))
                        value == "duplicate" -> rule("duplicateValues"); value == "unique" -> rule("uniqueValues")
                        value == "is-error" -> rule("containsErrors"); value == "is-no-error" -> rule("notContainsErrors")
                        value.startsWith("top-elements(") -> rule("top10", rank = inner("top-elements").trim().toIntOrNull() ?: 10)
                        value.startsWith("bottom-elements(") -> rule("top10", rank = inner("bottom-elements").trim().toIntOrNull() ?: 10, bottom = true)
                        value.startsWith("top-percent(") -> rule("top10", rank = inner("top-percent").trim().toIntOrNull() ?: 10, percent = true)
                        value.startsWith("bottom-percent(") -> rule("top10", rank = inner("bottom-percent").trim().toIntOrNull() ?: 10, percent = true, bottom = true)
                        value == "above-average" -> rule("aboveAverage"); value == "below-average" -> rule("aboveAverage", above = false)
                        value == "above-equal-average" -> rule("aboveAverage", equal = true); value == "below-equal-average" -> rule("aboveAverage", above = false, equal = true)
                        else -> { WriterLog.i("Sheets: a LibreOffice condition not known (${value.substringBefore('(').take(30)})"); null }
                    }
                }

                fun endFrame() {
                    val f = frame ?: return
                    shapeStyle?.let { st ->
                        shapeStyle = null
                        val g = props(st)
                        val geo = shapeGeo.orEmpty()
                        // Excel's shapes come back as "ooxml-<preset>" with their adjustments; LibreOffice's own by name, else their path
                        val preset = geo["type"]?.let { ShapePicture.presetOf(it) }
                        val mods = geo["modifiers"]?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toDoubleOrNull() }.orEmpty()
                        val adjust = preset?.let { ShapeGeometry.presets[it]?.adjustNames?.zip(mods)?.toMap() }.orEmpty()
                        fun esc(v: String) = v.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
                        val own = if (preset == null) ShapeGeometry.ofOdf("<draw:enhanced-geometry" +
                            listOf("enhanced-path" to "draw", "viewBox" to "svg", "modifiers" to "draw").joinToString("") { (n, ns) -> geo[n]?.let { " $ns:$n=\"${esc(it)}\"" }.orEmpty() } +
                            ">" + shapeEqs.joinToString("") { (n, fm) -> "<draw:equation draw:name=\"${esc(n)}\" draw:formula=\"${esc(fm)}\"/>" } + "</draw:enhanced-geometry>") else null
                        val fill = if (g["g.fill"] == "none") null else hex(g["g.fill-color"]) ?: 0xFF729FCF.toInt() // LibreOffice's own blue
                        val line = if (g["g.stroke"] == "none") null else hex(g["g.stroke-color"]) ?: 0xFF3465A4.toInt()
                        val width = (odfPx(g["g.stroke-width"]) ?: 1f).coerceAtLeast(0.5f)
                        // WordArt bent (fontwork): its letters painted in the shape's fill, outlined in its line — no box
                        val warp = geo["type"]?.let { t -> TextWarp.entries.firstOrNull { it.fontwork == t } }
                        val shape = if (warp == null && (preset != null && preset != "rect" || own != null)) ShapePicture(preset ?: "custom", fill, line, width, own, adjust) else null
                        val t = shapeSpan?.let { props(it) }.orEmpty()
                        drawings += SheetDrawing(f.row, f.col, f.dx, f.dy, width = f.width, height = f.height, text = shapeText.toString().trim().ifEmpty { null },
                            fill = fill.takeIf { warp == null }, line = line.takeIf { warp == null }, shape = shape, textColor = if (warp != null) fill else hex(t["t.color"]),
                            centred = g["g.textarea-vertical-align"] == "middle" || warp != null).also { d -> d.ordinal = f.ordinal; d.warp = warp
                            d.textPt = t["t.font-size"]?.removeSuffix("pt")?.toFloatOrNull(); d.bold = t["t.font-weight"] == "bold"
                            d.effects = TextEffects(if (warp != null) line?.and(0xFFFFFF) else if (t["t.text-outline"] == "true") 0 else null, t["t.text-shadow"].let { it != null && it != "none" }) }
                        return
                    }
                    // A chart is LibreOffice's object ("./Object 1"); a picture its file; a chart we cannot read is left out.
                    val chart = frameObject?.removePrefix("./")?.let { parts("$it/content.xml") }?.let { OfficeCharts.readOdf(it) }
                    val image = frameImage?.removePrefix("./")?.takeIf { chart == null && frameObject == null }?.let { p -> { parts(p) } }
                    if (chart != null || image != null) drawings += SheetDrawing(f.row, f.col, f.dx, f.dy, width = f.width, height = f.height, chart = chart, image = image).also { it.ordinal = f.ordinal }
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    if (frameDepth > 0 && shapeStyle != null && skip == 0) { shapeText.appendRange(ch, start, start + length); return }
                    if (skip > 0 || frameDepth > 0) return
                    when { inCreator -> author.appendRange(ch, start, start + length); inNoteP -> note.appendRange(ch, start, start + length); inP -> text.appendRange(ch, start, start + length) }
                }

                fun endCell() {
                    val a = cellAttrs
                    val styleName = a["style-name"] ?: rowDefault?.takeIf { it != "Default" } ?: colDefaults[col]
                    val s = style(styleName)
                    val type = a["value-type"]
                    val shown = text.toString()
                    val value = when (type) {
                        "float", "percentage", "currency" -> a["value"]?.toDoubleOrNull()
                        "date" -> a["date-value"]?.let { d ->
                            runCatching { LocalDateTime.parse(if ('T' in d) d else "${d}T00:00") }.getOrNull()
                                ?.let { ChronoUnit.SECONDS.between(LocalDate.of(1899, 12, 30).atStartOfDay(), it) / 86_400.0 }
                        }
                        "time" -> a["time-value"]?.let { t ->
                            Regex("""(-?)PT(\d+)H(\d+)M([0-9.]+)S""").find(t)?.destructured?.let { (minus, h, m, sec) -> (if (minus.isEmpty()) 1 else -1) * (h.toDouble() * 3600 + m.toDouble() * 60 + sec.toDouble()) / 86_400 }
                        }
                        "boolean" -> if (a["boolean-value"] == "true") 1.0 else 0.0
                        else -> null
                    }
                    val kind = when {
                        a["value-type"] == "error" -> SheetKind.Error
                        type == "boolean" -> SheetKind.Bool
                        value != null -> SheetKind.Number
                        shown.isNotEmpty() -> SheetKind.Text
                        else -> SheetKind.Empty
                    }
                    val formula = a["formula"]?.let(::odfFormula)
                    // an array formula's cells (LibreOffice's matrix; FILTER's spill as LibreOffice keeps it)
                    val matrix = if (formula == null) null else (a["number-matrix-rows-spanned"]?.toIntOrNull() ?: 1).let { mr -> (a["number-matrix-columns-spanned"]?.toIntOrNull() ?: 1).let { mc ->
                        if (mr > 1 || mc > 1) SheetRange(row, col, row + mr - 1, col + mc - 1) else null } }
                    // its runs, when a span gave some of its words a look of their own
                    val runs = if (cuts.none { it.second != DocRun("") }) null else (listOf(0 to DocRun("")) + cuts + listOf(shown.length to DocRun(""))).zipWithNext { (from, look), (to, _) ->
                        look.copy(text = shown.substring(from.coerceAtMost(shown.length), to.coerceIn(from.coerceAtMost(shown.length), shown.length))) }
                        .filter { it.text.isNotEmpty() }.fold(ArrayList<DocRun>()) { out, r -> if (out.isNotEmpty() && out.last().sameLook(r)) out[out.lastIndex] = out.last().copy(text = out.last().text + r.text) else out += r; out }
                    val cell = SheetCell(shown, kind, value, formula, s, link = link, color = value?.let { numberColor(styleName, it) }, runs = runs, spill = matrix,
                        note = note.toString().trim().takeIf { it.isNotEmpty() }?.let { n -> author.toString().trim().takeIf { it.isNotEmpty() }?.let { "$it: $n" } ?: n })
                    val boxed = styles[s].boxed
                    val n = min(cellRepeat, 16384 - col)
                    if (kind != SheetKind.Empty || formula != null || cell.note != null || (boxed && n <= 256)) {
                        for (k in 0 until n) rowCells += (col + k) to cell
                    }
                    val rs = a["number-rows-spanned"]?.toIntOrNull() ?: 1; val cs = a["number-columns-spanned"]?.toIntOrNull() ?: 1
                    if (rs > 1 || cs > 1) merges += SheetRange(row, col, row + rs - 1, col + cs - 1)
                    a["content-validation-name"]?.let { validCells.getOrPut(it) { ArrayList() } += SheetRange(row, col, row + min(rowRepeat, 1000) - 1, col + n - 1) }
                    col += cellRepeat
                }

                fun endRow() {
                    // A long run of repeated rows is LibreOffice's way to the sheet's end: only what is written in it counts.
                    if (rowRepeat > 100) rowCells.removeAll { it.second.kind == SheetKind.Empty && it.second.formula == null }
                    if (rowCells.isNotEmpty()) {
                        for (k in 0 until min(rowRepeat, 1000)) for ((c, cell) in rowCells) {
                            cells[Sheet.key(row + k, c)] = cell
                            if (cell.kind != SheetKind.Empty) { maxRow = max(maxRow, row + k); maxCol = max(maxCol, c) } else boxed.add(row + k, c)
                        }
                    }
                    row += rowRepeat
                }

                fun endTable() {
                    Sheets.markSpills(cells)
                    val looks = ArrayList<SheetStyle>()
                    val icons = HashMap<Long, SheetIcon>(); val ruleBase = HashMap<Long, Int>()
                    val bars = runCatching { SheetRules.apply(rules, cells, { i -> (if (i >= Sheet.LOOKS) looks.getOrNull(i - Sheet.LOOKS) else styles.getOrNull(i)) ?: SheetStyle() }, looks, icons, ruleBase) }
                        .onFailure { WriterLog.w("Sheets: conditional formatting could not be laid", it) }.getOrDefault(emptyMap())
                    if (rules.isNotEmpty()) WriterLog.i("Sheets: ${rules.size} conditional rules (${rules.map { it.type }.distinct().joinToString()})")
                    merges.forEach { maxRow = max(maxRow, it.r2); maxCol = max(maxCol, it.c2) }
                    maxRow = boxed.rows(maxRow); maxCol = boxed.cols(maxCol)
                    val rowHeights = HashMap<Int, Float>(); val hiddenRows = HashSet<Int>()
                    val limit = maxRow + 200
                    rowRuns.forEach { (start, n, h) -> for (r in start until min(start + n, limit)) rowHeights[r] = h }
                    hiddenRuns.forEach { (start, n) -> for (r in start until min(start + n, limit)) hiddenRows += r }
                    // LibreOffice's default row (0.452 cm) and column (2.258 cm).
                    val defaultRow = rowHeights.values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 17f
                    val defaultCol = colWidths.values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 85f
                    val view = settings.tables[name].orEmpty()
                    val tabProps = props(tableStyle)
                    result = Sheet(
                        name, cells, maxRow + 1, maxCol + 1, colWidths, rowHeights, defaultCol, defaultRow, hiddenCols, hiddenRows, merges,
                        frozenRows = if (view["VerticalSplitMode"] == "2") view["VerticalSplitPosition"]?.toIntOrNull() ?: 0 else 0,
                        frozenCols = if (view["HorizontalSplitMode"] == "2") view["HorizontalSplitPosition"]?.toIntOrNull() ?: 0 else 0,
                        grid = (view["ShowGrid"] ?: settings.showGrid) != "false",
                        colStyles = colDefaults.mapValues { style(it.value) }.filterValues { styles[it].boxed },
                        tab = hex(tabProps["tab.tab-color"]), hidden = tabProps["tab.display"] == "false", drawings = drawings, looks = looks, bars = bars, icons = icons,
                        print = (runCatching { SheetPageSetup.readOds(parts("styles.xml")?.let(OfficeFormats::parse), props(tableStyle)["master-page"], printRanges) }.getOrNull() ?: SheetPrint())
                            .let { if (titlesFrom in 0..titlesTo) it.copy(titles = titlesFrom..titlesTo) else it },
                    ).also { it.rules = rules; it.ruleBase += ruleBase; it.protected = tableProtected; it.guarded = tableGuarded; it.headings = settings.headings != "false"; it.rowLevels = rowLevels
                        it.lists = validCells.mapNotNull { (v, at) -> validities[v]?.let { SheetList.odsList(it) }?.let { f -> SheetList.of(at, f, name) { r, c -> cells[Sheet.key(r, c)]?.text } } } }
                    WriterLog.i("Sheets: ods sheet ${cells.size} cells, ${maxRow + 1}×${maxCol + 1}, ${merges.size} merged")
                    throw Enough() // the sheets after it are not read
                }
            })
            return result ?: sheetProblem(R.string.tables_err_no_sheet_n, target + 1)
        }

        class Settings(val tables: Map<String, Map<String, String>>, val active: String?, val showGrid: String?, val headings: String? = null)

        /** LibreOffice's view settings: each sheet's frozen rows and columns, the grid, the sheet it was left on. */
        fun readSettings(): Settings {
            val bytes = parts("settings.xml") ?: return Settings(emptyMap(), null, null)
            return runCatching {
                val doc = OfficeFormats.parse(bytes)
                val tables = HashMap<String, Map<String, String>>()
                var active: String? = null; var grid: String? = null; var headings: String? = null
                val named = doc.getElementsByTagNameNS("*", "config-item-map-named")
                for (i in 0 until named.length) {
                    val m = named.item(i) as Element
                    if (m.getAttributeNS("*", "name").ifEmpty { m.getAttribute("config:name") } != "Tables") continue
                    for (entry in m.kids("config-item-map-entry")) {
                        val items = entry.kids("config-item").associate { (it.getAttribute("config:name")) to it.textContent }
                        tables[entry.getAttribute("config:name")] = items
                    }
                    // The view these tables belong to: its active table and grid.
                    (m.parentNode as? Element)?.kids("config-item")?.forEach {
                        when (it.getAttribute("config:name")) { "ActiveTable" -> active = it.textContent; "ShowGrid" -> grid = it.textContent; "HasColumnRowHeaders" -> headings = it.textContent }
                    }
                }
                // a view without a Tables list (a sheet's own settings): its own ShowGrid
                if (grid == null) doc.getElementsByTagNameNS("*", "config-item").let { l -> (0 until l.length).map { l.item(it) as Element } }
                    .firstOrNull { it.getAttribute("config:name") == "ShowGrid" }?.let { grid = it.textContent }
                if (headings == null) doc.getElementsByTagNameNS("*", "config-item").let { l -> (0 until l.length).map { l.item(it) as Element } }
                    .firstOrNull { it.getAttribute("config:name") == "HasColumnRowHeaders" }?.let { headings = it.textContent }
                Settings(tables, active, grid, headings)
            }.getOrElse { Settings(emptyMap(), null, null) }
        }
    }

    /** Each spilling formula's other cells marked as its ([SheetCell.spilledFrom]): a file's array formulas as Tetra's spills. */
    fun markSpills(cells: CellMap) {
        val marks = ArrayList<Pair<Long, Long>>()
        cells.forEach { k, c -> c.spill?.let { g -> for (r in g.r1..minOf(g.r2, g.r1 + 9_999)) for (cc in g.c1..minOf(g.c2, g.c1 + 199)) Sheet.key(r, cc).let { t -> if (t != k) marks += t to k } } }
        marks.forEach { (t, k) -> cells[t]?.let { cells[t] = it.copy(spilledFrom = k) } }
    }

    /** LibreOffice's "of:=SUM([.A1:.B2])" as Excel writes it: "=SUM(A1:B2)". */
    fun odfFormula(f: String): String {
        val body0 = f.substringAfter(':', f).let { if (f.startsWith("of:") || f.startsWith("oooc:") || f.startsWith("msoxl:")) it else f }
        // ; between arguments (LibreOffice's) as Excel's commas, outside words in quotes
        val body = body0.split('"').mapIndexed { i, p -> if (i % 2 == 1) p else p.replace(';', ',') }.joinToString("\"")
        return body.replace(Regex("""\[([^\]]*)]""")) { m ->
            m.groupValues[1].split(':').joinToString(":") { part ->
                val sheet = part.substringBefore('.', "").removePrefix("$").trim('\'')
                val cell = part.substringAfter('.')
                if (sheet.isEmpty()) cell else "${SheetBook.quoted(sheet)}!$cell"
            }
        }.let { if (it.startsWith("=")) it else "=$it" }.let(SheetCalc::fromFile)
    }

    // --- .csv ---

    /** A text table: UTF-8 (or Windows Greek, as Excel saves a CSV here), the separator the first lines use most. */
    fun csv(bytes: ByteArray, decimal: Char = '.', group: Char = ','): Workbook {
        var charset = Charsets.UTF_8
        val text = runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrElse { charset = charset("windows-1253"); String(bytes, charset) }.removePrefix("﻿")
        val head = text.lineSequence().take(5).joinToString("\n")
        val sep = listOf(',', ';', '\t', '|').maxByOrNull { s -> head.count { it == s } } ?: ','
        val cells = CellMap()
        var r = 0; var c = 0; var maxCol = -1
        val field = StringBuilder(); var quoted = false; var i = 0
        fun put() {
            val s = field.toString()
            if (s.isNotEmpty()) {
                val n = s.trim().takeIf { Regex("""-?\d+([.,]\d+)?""").matches(it) }?.replace(',', '.')?.toDoubleOrNull()
                cells[Sheet.key(r, c)] = SheetCell(s, if (n != null) SheetKind.Number else SheetKind.Text, n)
                maxCol = max(maxCol, c)
            }
            field.setLength(0); c++
        }
        while (i < text.length) {
            val ch = text[i]
            when {
                quoted && ch == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                ch == '"' && (quoted || field.isEmpty()) -> quoted = !quoted
                !quoted && ch == sep -> put()
                !quoted && (ch == '\n' || ch == '\r') -> { put(); if (ch == '\r' && text.getOrNull(i + 1) == '\n') i++; r++; c = 0 }
                else -> field.append(ch)
            }
            i++
        }
        if (field.isNotEmpty() || c > 0) put()
        var rows = 0
        cells.forEach { k, _ -> rows = max(rows, Sheet.row(k) + 1) }
        val sheet = Sheet("Sheet1", cells, rows, maxCol + 1, emptyMap(), emptyMap(), 64f, 20f, emptySet(), emptySet(), emptyList(), 0, 0)
        return Workbook(listOf(SheetTab("Sheet1")), listOf(SheetStyle()), kind = "csv", format = SheetFormat(decimal, group), csv = sep to charset) { sheet }
    }
}
