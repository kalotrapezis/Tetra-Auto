package com.kalotrapezis.drive

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Conditional formatting (Excel's Home › Conditional Formatting, LibreOffice's Format › Conditional): a cell's look
 * from its value — highlight rules (greater than, between, equal to, text that contains, blanks, errors), top/bottom
 * N, above/below average, duplicates and uniques, simple formulas, colour scales and data bars. Rules are laid on the
 * cells when a sheet is read, so drawing stays as it is.
 */
internal class SheetCfvo(val type: String, val value: String? = null)

internal class SheetRule(
    val ranges: List<SheetRange>, val priority: Int, val type: String,
    val operator: String? = null, val formulas: List<String> = emptyList(), val dxf: SheetDxf? = null,
    val text: String? = null, val rank: Int = 10, val percent: Boolean = false, val bottom: Boolean = false,
    val above: Boolean = true, val equalAverage: Boolean = false, val stop: Boolean = false,
    /** A colour scale's points: where (min, max, number, percent, percentile) and which colour. */
    val scale: List<Pair<SheetCfvo, Int>> = emptyList(),
    /** A data bar's colour and its two ends. */
    val bar: Int? = null, val barEnds: List<SheetCfvo> = emptyList(),
    /** An icon set's name ("3Arrows"), its thresholds (the first is its least), whether the value shows, reversed. */
    val icons: String? = null, val iconAt: List<SheetCfvo> = emptyList(), val showValue: Boolean = true, val reverse: Boolean = false,
)

/** An icon set's icon in a cell: the set, which of its icons (0: the least), and whether the cell's value shows beside it. */
internal data class SheetIcon(val set: String, val index: Int, val showValue: Boolean)

/** A data bar in a cell: how much of the cell's width, in which colour. */
internal data class SheetBar(val fraction: Float, val color: Int)

internal object SheetRules {
    /** A cell's value as rules compare it: its number, else its words; "" for an empty cell. */
    private fun valueOf(cell: SheetCell?): Any = when {
        cell == null || cell.kind == SheetKind.Empty -> ""
        (cell.kind == SheetKind.Number || cell.kind == SheetKind.Bool) && cell.value != null -> cell.value
        else -> cell.text
    }

    /** Excel's comparison: numbers by value (an empty cell is 0 then), words without case, words after numbers. */
    private fun compare(a: Any, b: Any): Int = when {
        a is Double && b is Double -> a.compareTo(b)
        a == "" && b is Double -> 0.0.compareTo(b)
        a is Double && b == "" -> a.compareTo(0.0)
        a is Double -> -1
        b is Double -> 1
        else -> (a as String).compareTo(b as String, ignoreCase = true)
    }

    private val ORDER = Comparator<Any> { x, y -> compare(x, y) }
    private val REF = Regex("""^(\$?)([A-Z]{1,3})(\$?)(\d+)$""")

    /** A rule's operand: a number, "words", TRUE/FALSE, or a cell (moved with the cell when relative). */
    private fun operand(f: String, r: Int, c: Int, origin: SheetRange, cells: CellMap): Any? {
        val s = f.trim().removePrefix("=").trim()
        s.toDoubleOrNull()?.let { return it }
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length - 1).replace("\"\"", "\"")
        if (s.equals("TRUE", true)) return 1.0
        if (s.equals("FALSE", true)) return 0.0
        REF.matchEntire(s.uppercase().replace(Regex("^.*!"), ""))?.let { m ->
            val (cAbs, col, rAbs, row) = m.destructured
            val c0 = col.fold(0) { a, ch -> a * 26 + (ch - 'A' + 1) } - 1
            val rr = if (rAbs.isEmpty()) row.toInt() - 1 + (r - origin.r1) else row.toInt() - 1
            val cc = if (cAbs.isEmpty()) c0 + (c - origin.c1) else c0
            return valueOf(cells[Sheet.key(rr, cc)])
        }
        return null
    }

    fun argsOf(s: String) = args(s)

    /** Splits a formula's arguments at the commas (or semicolons) not inside brackets or quotes. */
    private fun args(s: String): List<String> {
        val out = ArrayList<String>(); var depth = 0; var quote = false; var start = 0
        for ((i, ch) in s.withIndex()) when {
            ch == '"' -> quote = !quote
            quote -> Unit
            ch == '(' -> depth++
            ch == ')' -> depth--
            (ch == ',' || ch == ';') && depth == 0 -> { out += s.substring(start, i); start = i + 1 }
        }
        out += s.substring(start)
        return out.map { it.trim() }
    }

    /**
     * A rule's formula, as far as a viewer needs: comparisons of cells and values, AND/OR/NOT, ISBLANK, ISNUMBER,
     * ISTEXT, MOD(ROW(),n)=k for stripes. Null: a formula it cannot work out (the rule is then left out, and logged).
     */
    fun formula(f: String, r: Int, c: Int, origin: SheetRange, cells: CellMap): Boolean? {
        var s = f.trim().removePrefix("=").trim()
        while (s.startsWith("(") && s.endsWith(")") && args(s.substring(1, s.length - 1)).size == 1 && balanced(s.substring(1, s.length - 1))) s = s.substring(1, s.length - 1).trim()
        val upper = s.uppercase()
        fun call(name: String) = if (upper.startsWith("$name(") && upper.endsWith(")")) args(s.substring(name.length + 1, s.length - 1)) else null
        call("AND")?.let { a -> return a.map { formula(it, r, c, origin, cells) ?: return null }.all { it } }
        call("OR")?.let { a -> return a.map { formula(it, r, c, origin, cells) ?: return null }.any { it } }
        call("NOT")?.let { a -> return formula(a.single(), r, c, origin, cells)?.not() }
        call("ISBLANK")?.let { a -> return operand(a.single(), r, c, origin, cells)?.let { it == "" } }
        call("ISNUMBER")?.let { a -> return operand(a.single(), r, c, origin, cells)?.let { it is Double } }
        call("ISTEXT")?.let { a -> return operand(a.single(), r, c, origin, cells)?.let { it is String && it != "" } }
        Regex("""^MOD\((ROW|COLUMN)\(\)\s*(?:-\s*(\d+))?\s*[,;]\s*(\d+)\)\s*(=|<>)\s*(\d+)$""").matchEntire(upper)?.let { m ->
            val at = (if (m.groupValues[1] == "ROW") r else c) + 1 - (m.groupValues[2].toIntOrNull() ?: 0)
            val yes = Math.floorMod(at, m.groupValues[3].toInt()) == m.groupValues[5].toInt()
            return if (m.groupValues[4] == "=") yes else !yes
        }
        // a comparison: the first operator outside quotes and brackets
        var depth = 0; var quote = false
        for (i in s.indices) {
            val ch = s[i]
            if (ch == '"') quote = !quote
            if (quote) continue
            if (ch == '(') depth++; if (ch == ')') depth--
            if (depth != 0 || ch !in "<>=") continue
            val op = when { s.startsWith("<>", i) -> "<>"; s.startsWith(">=", i) -> ">="; s.startsWith("<=", i) -> "<="; else -> ch.toString() }
            val a = operand(s.substring(0, i), r, c, origin, cells) ?: return null
            val b = operand(s.substring(i + op.length), r, c, origin, cells) ?: return null
            val k = compare(a, b)
            return when (op) { "=" -> k == 0; "<>" -> k != 0; ">" -> k > 0; "<" -> k < 0; ">=" -> k >= 0; else -> k <= 0 }
        }
        return when (upper) { "TRUE" -> true; "FALSE" -> false; else -> null }
    }

    private fun balanced(s: String): Boolean { var d = 0; for (ch in s) { if (ch == '(') d++; if (ch == ')') d--; if (d < 0) return false }; return d == 0 }

    /** Where a colour scale's or data bar's point falls among the range's [numbers] (sorted). */
    private fun at(v: SheetCfvo, numbers: List<Double>, fallback: Double): Double {
        if (numbers.isEmpty()) return fallback
        val n = v.value?.toDoubleOrNull()
        return when (v.type) {
            "min", "minimum", "auto-minimum" -> numbers.first()
            "max", "maximum", "auto-maximum" -> numbers.last()
            "num", "number" -> n ?: fallback
            "percent" -> numbers.first() + (numbers.last() - numbers.first()) * (n ?: 50.0) / 100
            "percentile" -> numbers[((numbers.size - 1) * (n ?: 50.0) / 100).roundToInt().coerceIn(0, numbers.lastIndex)]
            else -> fallback
        }
    }

    private fun mix(a: Int, b: Int, t: Double): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(a shr 16 and 255, b shr 16 and 255) shl 16) or (ch(a shr 8 and 255, b shr 8 and 255) shl 8) or ch(a and 255, b and 255)
    }

    /**
     * The sheet's rules laid again after its cells changed (an edit, an undo): each cell given back the look under its
     * rule, then the rules worked out on the new values.
     */
    fun again(sheet: Sheet, styles: List<SheetStyle>) {
        if (sheet.rules.isEmpty()) return
        for ((k, style) in sheet.ruleBase) sheet.cells[k]?.let { cell ->
            when {
                // formatted since (a look of the workbook's, not a rule's): that is now the look under the rule
                cell.style < Sheet.LOOKS -> sheet.ruleBase[k] = cell.style
                // a rule's look again (an undo): the file's look under it, unless a table's look is (kept)
                style < Sheet.LOOKS && cell.own >= 0 -> { sheet.ruleBase[k] = cell.own; sheet.cells[k] = cell.copy(style = cell.own) }
                cell.style != style -> sheet.cells[k] = cell.copy(style = style)
            }
        }
        val looks = sheet.looks as? ArrayList<SheetStyle> ?: return
        val icons = HashMap<Long, SheetIcon>()
        sheet.bars = apply(sheet.rules, sheet.cells, { i -> sheet.look(i, styles) ?: SheetStyle() }, looks, icons, sheet.ruleBase)
        sheet.icons = icons
    }

    /**
     * Lays [rules] on [cells]: each cell's new look goes in [looks] (numbered from [Sheet.LOOKS], as an Excel Table's);
     * the data bars are returned. A rule's look wins over the cell's own, a higher priority's over a lower's.
     */
    fun apply(rules: List<SheetRule>, cells: CellMap, styleOf: (Int) -> SheetStyle, looks: ArrayList<SheetStyle>, icons: MutableMap<Long, SheetIcon> = HashMap(),
              base: MutableMap<Long, Int>? = null): Map<Long, SheetBar> {
        if (rules.isEmpty()) return emptyMap()
        class Hit(val priority: Int, val dxf: SheetDxf, val stop: Boolean)
        val hits = HashMap<Long, MutableList<Hit>>()
        val bars = HashMap<Long, SheetBar>()
        var skipped = 0
        for (rule in rules) {
            val origin = rule.ranges.firstOrNull() ?: continue
            val keys = rule.ranges.flatMap { g -> if ((g.r2 - g.r1 + 1L) * (g.c2 - g.c1 + 1L) > 200_000) emptyList() else (g.r1..g.r2).flatMap { r -> (g.c1..g.c2).map { c -> Sheet.key(r, c) } } }
            val values = keys.associateWith { valueOf(cells[it]) }
            val numbers by lazy { values.values.filterIsInstance<Double>().sorted() }
            fun hit(k: Long, d: SheetDxf) { hits.getOrPut(k) { ArrayList() } += Hit(rule.priority, d, rule.stop) }
            when (rule.type) {
                "colorScale" -> {
                    if (rule.scale.size < 2 || numbers.isEmpty()) continue
                    val points = rule.scale.map { (v, color) -> at(v, numbers, numbers.first()) to color }
                    for (k in keys) {
                        val v = values[k] as? Double ?: continue
                        val i = points.indexOfLast { it.first <= v }.coerceIn(0, points.size - 2)
                        val (lo, a) = points[i]; val (hi, b) = points[i + 1]
                        hit(k, SheetDxf(fill = mix(a, b, if (hi > lo) ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0) else 0.0)))
                    }
                }
                "iconSet" -> {
                    val set = rule.icons ?: "3TrafficLights1"
                    val n = set.firstOrNull()?.digitToIntOrNull() ?: 3
                    if (numbers.isEmpty()) continue
                    // Excel's thresholds: an icon for each one passed (≥), from the least.
                    val at = (rule.iconAt.takeIf { it.size == n } ?: (0 until n).map { SheetCfvo("percent", "${it * 100 / n}") }).map { at(it, numbers, numbers.first()) }
                    for (k in keys) {
                        val v = values[k] as? Double ?: continue
                        val i = at.indices.last { it == 0 || v >= at[it] }
                        icons.putIfAbsent(k, SheetIcon(set, if (rule.reverse) n - 1 - i else i, rule.showValue))
                    }
                }
                "dataBar" -> {
                    val color = rule.bar ?: continue
                    if (numbers.isEmpty()) continue
                    val lo = rule.barEnds.getOrNull(0)?.let { at(it, numbers, numbers.first()) } ?: numbers.first()
                    val hi = rule.barEnds.getOrNull(1)?.let { at(it, numbers, numbers.last()) } ?: numbers.last()
                    for (k in keys) {
                        val v = values[k] as? Double ?: continue
                        // Excel's bar is never empty: a tenth for the least, the whole cell for the most.
                        val t = if (hi > lo) ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0) else 1.0
                        bars[k] = SheetBar((0.1 + 0.9 * t).toFloat(), color)
                    }
                }
                else -> {
                    val d = rule.dxf ?: continue
                    val counts by lazy { values.values.filter { it != "" }.groupingBy { if (it is String) it.lowercase() else it }.eachCount() }
                    val average by lazy { numbers.average() }
                    val cut by lazy {
                        val n = if (rule.percent) max(1, (numbers.size * rule.rank / 100.0).toInt()) else rule.rank
                        if (rule.bottom) numbers.getOrNull(min(n, numbers.size) - 1) else numbers.getOrNull(max(0, numbers.size - n))
                    }
                    for (k in keys) {
                        val v = values.getValue(k)
                        val r = Sheet.row(k); val c = Sheet.col(k)
                        val text = (v as? String) ?: cells[k]?.text.orEmpty()
                        val yes: Boolean? = when (rule.type) {
                            "cellIs" -> {
                                val a = rule.formulas.getOrNull(0)?.let { operand(it, r, c, origin, cells) }
                                val b = rule.formulas.getOrNull(1)?.let { operand(it, r, c, origin, cells) }
                                if (a == null) null else when (rule.operator) {
                                    "equal" -> compare(v, a) == 0; "notEqual" -> compare(v, a) != 0
                                    "greaterThan" -> compare(v, a) > 0; "greaterThanOrEqual" -> compare(v, a) >= 0
                                    "lessThan" -> compare(v, a) < 0; "lessThanOrEqual" -> compare(v, a) <= 0
                                    "between" -> b?.let { compare(v, minOf(a, b, ORDER)) >= 0 && compare(v, maxOf(a, b, ORDER)) <= 0 }
                                    "notBetween" -> b?.let { compare(v, minOf(a, b, ORDER)) < 0 || compare(v, maxOf(a, b, ORDER)) > 0 }
                                    else -> null
                                }
                            }
                            "expression" -> rule.formulas.firstOrNull()?.let { formula(it, r, c, origin, cells) }
                            "containsText" -> rule.text?.let { text.contains(it, ignoreCase = true) }
                            "notContainsText" -> rule.text?.let { !text.contains(it, ignoreCase = true) }
                            "beginsWith" -> rule.text?.let { text.startsWith(it, ignoreCase = true) }
                            "endsWith" -> rule.text?.let { text.endsWith(it, ignoreCase = true) }
                            "containsBlanks" -> text.isBlank()
                            "notContainsBlanks" -> text.isNotBlank()
                            "containsErrors" -> cells[k]?.kind == SheetKind.Error
                            "notContainsErrors" -> cells[k]?.kind != SheetKind.Error
                            "duplicateValues" -> v != "" && (counts[if (v is String) v.lowercase() else v] ?: 0) > 1
                            "uniqueValues" -> v != "" && (counts[if (v is String) v.lowercase() else v] ?: 0) == 1
                            "top10" -> (v as? Double)?.let { x -> cut?.let { if (rule.bottom) x <= it else x >= it } ?: false } ?: false
                            "aboveAverage" -> (v as? Double)?.let { x -> if (rule.above) (x > average || (rule.equalAverage && x == average)) else (x < average || (rule.equalAverage && x == average)) } ?: false
                            else -> null
                        }
                        if (yes == null) { skipped++; break }
                        if (yes) hit(k, d)
                    }
                }
            }
        }
        if (skipped > 0) WriterLog.i("Sheets: $skipped conditional rules not worked out (their formulas)")
        val index = HashMap<SheetStyle, Int>()
        for ((k, list) in hits) {
            // The highest priority first; one that stops ends the list; each lower one fills what is still unset.
            var d = SheetDxf()
            for (h in list.sortedBy { it.priority }) { d = h.dxf + d; if (h.stop) break }
            val cell = cells[k] ?: SheetCell("", SheetKind.Empty)
            val own = styleOf(cell.style)
            val look = own.copy(
                fill = d.fill ?: own.fill, color = d.color ?: own.color, bold = d.bold ?: own.bold, italic = d.italic ?: own.italic,
                left = d.left ?: own.left, right = d.right ?: own.right, top = d.top ?: own.top, bottom = d.bottom ?: own.bottom,
            )
            if (look == own) continue
            base?.putIfAbsent(k, cell.style) // the look under the rule, to lay the rules again after an edit
            cells[k] = cell.copy(style = Sheet.LOOKS + (looks.indexOf(look).takeIf { it >= 0 } ?: index.getOrPut(look) { looks += look; looks.lastIndex }), own = cell.fileStyle)
        }
        return bars
    }
}
