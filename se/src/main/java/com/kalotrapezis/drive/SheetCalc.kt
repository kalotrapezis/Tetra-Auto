package com.kalotrapezis.drive

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Excel's formulas, worked out (asked 16 Oct with editing: a cell changed must change the cells that count on it):
 * references (A1, $A$1, A1:B9, A:A, Sheet2!A1, 'A sheet'!A1), + − × ÷ ^ & % and comparisons, and the functions people
 * use most. A function it does not know leaves the formula's value as the file kept it ([Unknown]).
 */
internal object SheetCalc {
    /** An error value: #DIV/0!, #VALUE!, #REF!, #NAME?, #N/A, #NUM!. */
    data class Err(val code: String)
    /** A range given to a function: its sheet (null: the formula's) and cells. */
    data class Area(val sheet: String?, val range: SheetRange)
    /** Values in rows (FILTER's, SORT's, B2:B9>5's): a formula giving one spills it into the cells below and right. */
    data class Arr(val rows: List<List<Any?>>) { val width get() = rows.firstOrNull()?.size ?: 0 }
    /** A formula this engine cannot work out (an unknown function): its file value stays. */
    class Unknown(what: String) : Exception(what)

    // --- newer functions' names in files ---

    /** Excel's newer functions LibreOffice keeps as "COM.MICROSOFT.<name>" (as its 26.2 writes them). */
    private val MICROSOFT = setOf("XLOOKUP", "XMATCH", "IFS", "SWITCH", "MAXIFS", "MINIFS", "CONCAT", "TEXTJOIN", "STDEV.S", "STDEV.P", "VAR.S", "VAR.P",
        "RANK.EQ", "RANK.AVG", "CEILING.MATH", "FLOOR.MATH", "UNIQUE", "SORT", "SORTBY", "FILTER", "SEQUENCE")
    /** And those Excel keeps as "_xlfn.<name>" (FILTER and SORT "_xlfn._xlws."): without it Excel shows #NAME?. */
    private val XLFN = MICROSOFT + setOf("IFNA", "DAYS", "XOR", "ISOWEEKNUM", "NUMBERVALUE")
    private fun outsideQuotes(f: String, change: (String) -> String) = f.split('"').mapIndexed { i, p -> if (i % 2 == 1) p else change(p) }.joinToString("\"")
    private val CALLED = Regex("""(?<![\p{L}\p{N}_.])([A-Za-z][A-Za-z0-9.]*)\(""")

    /** A formula as a file keeps it ("_xlfn.XLOOKUP(", "COM.MICROSOFT.IFS(") as Excel shows it: "XLOOKUP(". */
    fun fromFile(f: String) = outsideQuotes(f) { it.replace(Regex("""(?i)_xlfn\.(?:_xlws\.)?|_xlpm\.|COM\.MICROSOFT\."""), "") }
    /** As Excel's file keeps it. */
    fun toXlsx(f: String) = outsideQuotes(f) { p -> CALLED.replace(p) { m -> val n = m.groupValues[1].uppercase()
        if (n in XLFN) "_xlfn." + (if (n == "FILTER" || n == "SORT") "_xlws." else "") + m.value else m.value } }
    /** As LibreOffice's file keeps it. */
    fun toOds(f: String) = outsideQuotes(f) { p -> CALLED.replace(p) { m -> if (m.groupValues[1].uppercase() in MICROSOFT) "COM.MICROSOFT." + m.value else m.value } }

    // --- the formula as a tree ---

    sealed interface Node
    data class Num(val v: Double) : Node
    data class Str(val v: String) : Node
    data class Bool(val v: Boolean) : Node
    data class ErrNode(val e: Err) : Node
    data class Ref(val sheet: String?, val range: SheetRange, val single: Boolean) : Node
    data class Call(val name: String, val args: List<Node>) : Node
    data class Unary(val op: Char, val x: Node) : Node
    data class Binary(val op: String, val a: Node, val b: Node) : Node
    /** A defined name ("scores"): the range the workbook gives it. */
    data class Name(val name: String) : Node

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun peek(): Char? { ws(); return s.getOrNull(i) }
        fun eat(t: String): Boolean { ws(); return if (s.startsWith(t, i)) { i += t.length; true } else false }

        fun parse(): Node { val n = compare(); ws(); if (i < s.length) throw Unknown("left over: ${s.substring(i).take(10)}"); return n }
        fun compare(): Node {
            var a = concat()
            while (true) {
                val op = listOf("<>", "<=", ">=", "=", "<", ">").firstOrNull { eat(it) } ?: return a
                a = Binary(op, a, concat())
            }
        }
        fun concat(): Node { var a = add(); while (eat("&")) a = Binary("&", a, add()); return a }
        fun add(): Node {
            var a = mul()
            while (true) { val c = peek(); if (c == '+' || c == '-') { i++; a = Binary(c.toString(), a, mul()) } else return a }
        }
        fun mul(): Node {
            var a = pow()
            while (true) { val c = peek(); if (c == '*' || c == '/') { i++; a = Binary(c.toString(), a, pow()) } else return a }
        }
        fun pow(): Node { var a = unary(); while (eat("^")) a = Binary("^", a, unary()); return a }
        fun unary(): Node {
            val c = peek()
            if (c == '-' || c == '+') { i++; return Unary(c, unary()) }
            var x = atom()
            while (eat("%")) x = Binary("/", x, Num(100.0))
            return x
        }
        fun atom(): Node {
            ws()
            val c = s.getOrNull(i) ?: throw Unknown("end")
            if (c == '(') { i++; val n = compare(); if (!eat(")")) throw Unknown("("); return n }
            if (c == '"') {
                val sb = StringBuilder(); i++
                while (i < s.length) { if (s[i] == '"') { if (s.getOrNull(i + 1) == '"') { sb.append('"'); i += 2; continue } else { i++; break } }; sb.append(s[i]); i++ }
                return Str(sb.toString())
            }
            if (c == '#') {
                val e = listOf("#DIV/0!", "#VALUE!", "#REF!", "#NAME?", "#N/A", "#NUM!", "#NULL!").firstOrNull { s.startsWith(it, i, ignoreCase = true) } ?: throw Unknown("error")
                i += e.length; return ErrNode(Err(e))
            }
            if (c.isDigit() || c == '.') {
                // a number, or a whole row's range (1:3)
                val m = Regex("""\d+:\$?\d+""").matchAt(s, i)
                if (m != null && !s.substring(i).startsWith(m.value + ".")) { i += m.value.length; return rows(null, m.value) }
                val n = Regex("""\d*\.?\d+(?:[eE][+-]?\d+)?""").matchAt(s, i) ?: throw Unknown("number")
                i += n.value.length; return Num(n.value.toDouble())
            }
            // a sheet's name: 'A sheet'!A1 or Sheet2!A1
            var sheet: String? = null
            if (c == '\'') {
                val end = s.indexOf("'!", i + 1); if (end < 0) throw Unknown("sheet")
                sheet = s.substring(i + 1, end).replace("''", "'"); i = end + 2
            } else Regex("""[\p{L}_][\p{L}\p{N}_.]*!""").matchAt(s, i)?.let { sheet = it.value.dropLast(1); i += it.value.length }
            Regex("""\$?[A-Za-z]{1,3}\$?\d+(?::\$?[A-Za-z]{1,3}\$?\d+)?(?![\p{L}\p{N}_(])""").matchAt(s, i)?.let { m ->
                val r = Sheets.rangeRef(m.value.replace("$", "")) ?: throw Unknown("ref")
                i += m.value.length; return Ref(sheet, r, ':' !in m.value)
            }
            Regex("""\$?[A-Za-z]{1,3}:\$?[A-Za-z]{1,3}(?![\p{L}\p{N}_(])""").matchAt(s, i)?.let { m ->
                // a whole column: A:A
                val (a, b) = m.value.replace("$", "").split(':').map { col -> col.uppercase().fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1 }
                i += m.value.length; return Ref(sheet, SheetRange(0, min(a, b), 1_048_575, max(a, b)), false)
            }
            Regex("""\d+:\$?\d+""").matchAt(s, i)?.let { m -> i += m.value.length; return rows(sheet, m.value) }
            if (sheet != null) throw Unknown("ref after sheet")
            val name = Regex("""[A-Za-z_][A-Za-z0-9_.]*""").matchAt(s, i)?.value ?: throw Unknown("name at ${s.substring(i).take(5)}")
            i += name.length
            if (peek() == '(') {
                i++
                val args = ArrayList<Node>()
                if (!eat(")")) {
                    while (true) {
                        // an empty argument (IF(A1,,2)) counts as nothing
                        if (peek() == ',' || peek() == ';' || peek() == ')') args += Str("") else args += compare()
                        if (eat(")")) break
                        if (!eat(",") && !eat(";")) throw Unknown("args")
                    }
                }
                return Call(name.uppercase().removePrefix("_XLFN.").removePrefix("_XLWS."), args)
            }
            return when (name.uppercase()) { "TRUE" -> Bool(true); "FALSE" -> Bool(false); else -> Name(name) }
        }
        fun rows(sheet: String?, v: String): Node {
            val (a, b) = v.replace("$", "").split(':').map { it.toInt() - 1 }
            return Ref(sheet, SheetRange(min(a, b), 0, max(a, b), 16383), false)
        }
    }

    private val trees = HashMap<String, Node>()

    /** A formula ("=SUM(A1:A3)" or without "=") as a tree; [Unknown] when it cannot be read. */
    fun parse(formula: String): Node = synchronized(trees) {
        trees.getOrPut(formula) { Parser(formula.removePrefix("=")).parse() }.also { if (trees.size > 50_000) trees.clear() }
    }

    /** Every cell range a formula reads, with its sheet (null: its own). */
    fun refs(n: Node, out: MutableList<Ref> = ArrayList(), names: Map<String, String> = emptyMap(), depth: Int = 0): List<Ref> {
        when (n) {
            is Ref -> out += n
            is Name -> names[n.name.lowercase()]?.takeIf { depth < 10 }?.let { runCatching { refs(parse(it), out, names, depth + 1) } }
            is Call -> n.args.forEach { refs(it, out, names, depth) }
            is Unary -> refs(n.x, out, names, depth)
            is Binary -> { refs(n.a, out, names, depth); refs(n.b, out, names, depth) }
            else -> Unit
        }
        return out
    }

    // --- working a formula out ---

    /** Where a formula's cells come from: a cell's value (Double, String, Boolean, Err, or null when empty). */
    fun interface Cells { fun value(sheet: String?, r: Int, c: Int): Any? }

    class Context(val cells: Cells, val today: LocalDate = LocalDate.now(), val format: SheetFormat = SheetFormat(), val names: Map<String, String> = emptyMap(),
                  /** A cell's formula (SUBTOTAL leaves out cells that are subtotals themselves); null: none or not known. */
                  val formulaOf: (String?, Int, Int) -> String? = { _, _, _ -> null }) {
        fun cell(sheet: String?, r: Int, c: Int): Any? = cells.value(sheet, r, c)
    }

    fun eval(formula: String, ctx: Context): Any = try { value(parse(formula), ctx) } catch (e: Unknown) { throw e } catch (e: StackOverflowError) { Err("#NUM!") }

    /** A formula's whole result: an [Arr] when it gives several values (a range of cells, FILTER…), to spill; else its value. */
    fun evalSpill(formula: String, ctx: Context): Any = try {
        when (val v = calc(parse(formula), ctx)) { is Area -> if (v.range.r1 == v.range.r2 && v.range.c1 == v.range.c2) value(Ref(v.sheet, v.range, true), ctx) else grid(v, ctx); else -> v }
    } catch (e: Unknown) { throw e } catch (e: StackOverflowError) { Err("#NUM!") }

    /**
     * Excel's Evaluate Formula: [formula] as it is worked out, a step at a time — the leftmost innermost part not yet a value
     * (a cell, a function whose arguments are values, an operation on two values) replaced by its result. Ranges stay as
     * they are (a function reads them whole); a part giving several values (FILTER, SORT…) is worked out with what reads it.
     */
    fun steps(formula: String, ctx: Context, max: Int = 60): List<String> {
        var n = try { parse(formula) } catch (_: Unknown) { return listOf(formula) }
        val out = arrayListOf("=" + show(n))
        // a step that reads the same as the last (a sign put on a number) not shown twice
        while (out.size < max) { n = try { reduce(n, ctx) } catch (_: Unknown) { null } ?: break; ("=" + show(n)).let { if (it != out.last()) out += it } }
        return out
    }
    private fun literal(v: Any?): Node? = when (v) { null -> Num(0.0); is Number -> Num(v.toDouble()); is String -> Str(v); is Boolean -> Bool(v); is Err -> ErrNode(v); else -> null }
    private fun reduce(n: Node, ctx: Context): Node? = when (n) {
        is Num, is Str, is Bool, is ErrNode -> null
        is Ref -> if (n.single) literal(value(n, ctx)) else null
        is Name -> literal(value(n, ctx))
        is Unary -> reduce(n.x, ctx)?.let { Unary(n.op, it) } ?: literal(value(n, ctx))
        is Binary -> reduce(n.a, ctx)?.let { Binary(n.op, it, n.b) } ?: reduce(n.b, ctx)?.let { Binary(n.op, n.a, it) } ?: literal(value(n, ctx))
        is Call -> n.args.indices.firstNotNullOfOrNull { i -> reduce(n.args[i], ctx)?.let { r -> Call(n.name, n.args.toMutableList().also { it[i] = r }) } }
            ?: literal(calc(n, ctx).takeIf { it !is Arr && it !is Area })
    }
    private fun rank(n: Node) = when (n) { is Binary -> when (n.op) { "&" -> 2; "+", "-" -> 3; "*", "/" -> 4; "^" -> 5; else -> 1 }; is Unary -> 6; else -> 7 }
    /** A tree written back as a formula (without "="), brackets where the order needs them. */
    fun show(n: Node): String {
        fun wrap(x: Node, at: Int) = show(x).let { if (rank(x) < at || x is Num && x.v < 0) "($it)" else it }
        fun cell(r: Int, c: Int) = Sheets.colName(c) + (r + 1)
        return when (n) {
            is Num -> if (n.v == Math.floor(n.v) && abs(n.v) < 1e15) n.v.toLong().toString() else n.v.toBigDecimal().stripTrailingZeros().toPlainString()
            is Str -> "\"" + n.v.replace("\"", "\"\"") + "\""
            is Bool -> if (n.v) "TRUE" else "FALSE"
            is ErrNode -> n.e.code
            is Name -> n.name
            is Ref -> (n.sheet?.let { SheetBook.quoted(it) + "!" } ?: "") + n.range.let { g -> when {
                n.single -> cell(g.r1, g.c1)
                g.r1 == 0 && g.r2 >= 1_048_575 -> Sheets.colName(g.c1) + ":" + Sheets.colName(g.c2)
                g.c1 == 0 && g.c2 >= 16383 -> "${g.r1 + 1}:${g.r2 + 1}"
                else -> cell(g.r1, g.c1) + ":" + cell(g.r2, g.c2) } }
            is Call -> n.name + "(" + n.args.joinToString(",") { show(it) } + ")"
            is Unary -> n.op + wrap(n.x, 6)
            is Binary -> wrap(n.a, rank(n)) + n.op + wrap(n.b, rank(n) + 1)
        }
    }

    /** A range's values in rows (up to 10,000 rows and 200 columns). */
    private fun grid(a: Area, ctx: Context) = Arr((a.range.r1..min(a.range.r2, a.range.r1 + 9_999)).map { r -> (a.range.c1..min(a.range.c2, a.range.c1 + 199)).map { c -> ctx.cell(a.sheet, r, c) } })
    private fun arr(v: Any?, ctx: Context): Arr = when (v) { is Arr -> v; is Area -> grid(v, ctx); else -> Arr(listOf(listOf(v))) }

    private fun value(n: Node, ctx: Context): Any = when (val v = calc(n, ctx)) {
        is Area -> if (v.range.r1 == v.range.r2 && v.range.c1 == v.range.c2) ctx.cell(v.sheet, v.range.r1, v.range.c1) ?: 0.0 else Err("#VALUE!")
        is Arr -> v.rows.firstOrNull()?.firstOrNull() ?: 0.0 // an array where one value is wanted: its first
        else -> v
    }

    private fun calc(n: Node, ctx: Context): Any = when (n) {
        is Num -> n.v
        is Str -> n.v
        is Bool -> n.v
        is ErrNode -> n.e
        is Name -> ctx.names[n.name.lowercase()]?.let { runCatching { parse(it) }.getOrNull() }?.let { calc(it, ctx) } ?: throw Unknown("name ${n.name}")
        is Ref -> if (n.single) (ctx.cell(n.sheet, n.range.r1, n.range.c1) ?: 0.0) else Area(n.sheet, n.range)
        is Unary -> { val x = num(value(n.x, ctx)); if (x is Err) x else if (n.op == '-') -(x as Double) else x }
        is Binary -> {
            // a range or array on either side: worked out cell by cell (B2:B9>5, A1:A3*2), a single value meeting each
            val x = calc(n.a, ctx); val y = calc(n.b, ctx)
            fun many(v: Any) = v is Arr || (v is Area && (v.range.r1 != v.range.r2 || v.range.c1 != v.range.c2))
            if (many(x) || many(y)) {
                val ax = arr(x, ctx); val ay = arr(y, ctx)
                val rows = max(ax.rows.size, ay.rows.size); val cols = max(ax.width, ay.width)
                fun at(a: Arr, r: Int, c: Int) = a.rows.getOrNull(if (a.rows.size == 1) 0 else r)?.getOrNull(if (a.width == 1) 0 else c)
                Arr((0 until rows).map { r -> (0 until cols).map { c -> binary(n.op, at(ax, r, c) ?: 0.0, at(ay, r, c) ?: 0.0, ctx) } })
            } else binary(n.op, value(n.a, ctx), value(n.b, ctx), ctx)
        }
        is Call -> call(n, ctx)
    }

    /** A value as a number for arithmetic: text that reads as one, TRUE as 1, empty as 0; else #VALUE!. */
    private fun num(v: Any?): Any = when (v) {
        null -> 0.0
        is Double -> v
        is Boolean -> if (v) 1.0 else 0.0
        is Err -> v
        is String -> if (v.isEmpty()) 0.0 else v.trim().replace(',', '.').toDoubleOrNull() ?: Err("#VALUE!")
        else -> Err("#VALUE!")
    }

    private fun text(v: Any?, ctx: Context? = null): String = when (v) {
        null -> ""
        is Double -> (ctx?.format ?: SheetFormat()).general(v)
        is Boolean -> if (v) "TRUE" else "FALSE"
        is Err -> v.code
        else -> v.toString()
    }

    private fun truthy(v: Any?): Any = when (v) {
        null -> false
        is Boolean -> v
        is Double -> v != 0.0
        is String -> when (v.uppercase()) { "TRUE" -> true; "FALSE" -> false; "" -> false; else -> Err("#VALUE!") }
        else -> v
    }

    /** Excel's order: numbers, then words (without case), then TRUE/FALSE; an empty cell is 0 or "". */
    fun compare(a: Any?, b: Any?): Int {
        val x = a ?: (if (b is String) "" else if (b is Boolean) false else 0.0)
        val y = b ?: (if (x is String) "" else if (x is Boolean) false else 0.0)
        fun rank(v: Any) = when (v) { is Double -> 0; is String -> 1; is Boolean -> 2; else -> 3 }
        if (rank(x) != rank(y)) return rank(x).compareTo(rank(y))
        return when (x) {
            is Double -> x.compareTo(y as Double)
            is String -> x.compareTo(y as String, ignoreCase = true)
            is Boolean -> x.compareTo(y as Boolean)
            else -> 0
        }
    }

    private fun binary(op: String, a: Any, b: Any, ctx: Context? = null): Any {
        if (a is Err) return a
        if (b is Err) return b
        if (op == "&") return text(a, ctx) + text(b, ctx) // a number as the phone writes it (12,5), as Excel there
        if (op in setOf("=", "<>", "<", ">", "<=", ">=")) {
            val k = compare(a, b)
            return when (op) { "=" -> k == 0; "<>" -> k != 0; "<" -> k < 0; ">" -> k > 0; "<=" -> k <= 0; else -> k >= 0 }
        }
        val x = num(a); val y = num(b)
        if (x is Err) return x
        if (y is Err) return y
        x as Double; y as Double
        return when (op) {
            "+" -> x + y; "-" -> x - y; "*" -> x * y
            "/" -> if (y == 0.0) Err("#DIV/0!") else x / y
            "^" -> x.pow(y).let { if (it.isNaN()) Err("#NUM!") else it }
            else -> Err("#VALUE!")
        }
    }

    /** Each value a function's arguments give: a range's cells (null for an empty one), or the value itself. */
    private fun flat(args: List<Node>, ctx: Context, each: (Any?, Boolean) -> Unit) {
        for (a in args) when (val v = calc(a, ctx)) {
            is Area -> cellsOf(v, ctx) { x -> each(x, true) }
            is Arr -> v.rows.forEach { row -> row.forEach { x -> each(x, true) } }
            else -> each(v, false)
        }
    }

    /** A range's cells, up to the sheet's used part (a whole column is not a million reads). */
    private fun cellsOf(a: Area, ctx: Context, each: (Any?) -> Unit) {
        val r2 = min(a.range.r2, a.range.r1 + 200_000); val c2 = min(a.range.c2, a.range.c1 + 1_000)
        for (r in a.range.r1..r2) for (c in a.range.c1..c2) each(ctx.cell(a.sheet, r, c))
    }

    /** Numbers as SUM counts them: from a range only numbers; given directly, also TRUE and numbers in words. */
    private fun numbers(args: List<Node>, ctx: Context): Any {
        val out = ArrayList<Double>()
        var err: Err? = null
        flat(args, ctx) { v, inRange ->
            when {
                v is Err -> if (err == null) err = v
                v is Double -> out += v
                !inRange && v is Boolean -> out += if (v) 1.0 else 0.0
                !inRange && v is String -> (num(v) as? Double)?.let { out += it } ?: run { if (err == null) err = Err("#VALUE!") }
            }
        }
        return err ?: out
    }

    @Suppress("UNCHECKED_CAST")
    private fun call(n: Call, ctx: Context): Any {
        val a = n.args
        fun v(i: Int) = value(a[i], ctx)
        fun d(i: Int): Any = num(v(i))
        fun nums(): Any = numbers(a, ctx)
        fun need(k: Int) { if (a.size < k) throw Unknown("${n.name} needs $k") }
        return when (n.name) {
            "SUM" -> (nums() as? List<Double>)?.sum() ?: nums()
            "PRODUCT" -> (nums() as? List<Double>)?.fold(1.0) { x, y -> x * y } ?: nums()
            "AVERAGE" -> (nums() as? List<Double>)?.let { if (it.isEmpty()) Err("#DIV/0!") else it.average() } ?: nums()
            "MIN" -> (nums() as? List<Double>)?.let { it.minOrNull() ?: 0.0 } ?: nums()
            "MAX" -> (nums() as? List<Double>)?.let { it.maxOrNull() ?: 0.0 } ?: nums()
            "MEDIAN" -> (nums() as? List<Double>)?.sorted()?.let { s -> if (s.isEmpty()) Err("#NUM!") else if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 } ?: nums()
            "STDEV", "STDEV.S", "STDEV.P", "STDEVP", "VAR", "VAR.S", "VAR.P", "VARP" -> (nums() as? List<Double>)?.let { s ->
                val population = n.name.endsWith("P")
                if (s.size < (if (population) 1 else 2)) Err("#DIV/0!") else {
                    val m = s.average(); val varr = s.sumOf { (it - m) * (it - m) } / (s.size - if (population) 0 else 1)
                    if (n.name.startsWith("VAR")) varr else sqrt(varr)
                }
            } ?: nums()
            "COUNT" -> { var k = 0.0; flat(a, ctx) { x, inRange -> if (x is Double || (!inRange && (x is Boolean || (x is String && num(x) is Double)))) k++ }; k }
            "COUNTA" -> { var k = 0.0; flat(a, ctx) { x, _ -> if (x != null && x != "") k++ }; k }
            "COUNTBLANK" -> { var k = 0.0; flat(a, ctx) { x, _ -> if (x == null || x == "") k++ }; k }
            "IF" -> { need(1); when (val t = truthy(v(0))) { is Err -> t; true -> if (a.size > 1) v(1) else true; else -> if (a.size > 2) v(2) else false } }
            "IFERROR" -> { need(2); val x = v(0); if (x is Err) v(1) else x }
            "IFNA" -> { need(2); val x = v(0); if (x is Err && x.code == "#N/A") v(1) else x }
            "AND", "OR" -> {
                var all = true; var any = false; var err: Err? = null; var seen = false
                flat(a, ctx) { x, inRange ->
                    if (inRange && (x == null || x is String)) return@flat
                    when (val t = truthy(x)) { is Err -> err = t; true -> { any = true; seen = true }; else -> { all = false; seen = true } }
                }
                err ?: if (!seen) Err("#VALUE!") else if (n.name == "AND") all else any
            }
            "NOT" -> { need(1); when (val t = truthy(v(0))) { is Boolean -> !t; else -> t } }
            "TRUE" -> true; "FALSE" -> false
            "ABS", "INT", "SQRT", "SIGN", "EXP", "LN", "LOG10" -> { need(1); val x = d(0); if (x is Err) x else (x as Double).let { when (n.name) {
                "ABS" -> abs(it); "INT" -> floor(it); "SIGN" -> Math.signum(it); "EXP" -> kotlin.math.exp(it)
                "LN" -> if (it <= 0) Err("#NUM!") else kotlin.math.ln(it); "LOG10" -> if (it <= 0) Err("#NUM!") else kotlin.math.log10(it)
                else -> if (it < 0) Err("#NUM!") else sqrt(it) } } }
            "PI" -> Math.PI
            "MOD" -> { need(2); val x = d(0); val y = d(1); if (x is Err) x else if (y is Err) y else if (y == 0.0) Err("#DIV/0!") else (x as Double) - (y as Double) * floor(x / y) }
            "POWER" -> { need(2); binary("^", v(0), v(1), ctx) }
            "ROUND", "ROUNDUP", "ROUNDDOWN", "TRUNC" -> {
                need(1); val x = d(0); val k = if (a.size > 1) d(1) else 0.0
                if (x is Err) x else if (k is Err) k else {
                    val f = 10.0.pow((k as Double).toInt()); val y = (x as Double) * f
                    // Excel rounds halves away from zero, on the decimal it shows (0.285 → 0.29)
                    val r = java.math.BigDecimal(x.toString()).setScale(k.toInt(), when (n.name) {
                        "ROUND" -> java.math.RoundingMode.HALF_UP; "ROUNDUP" -> java.math.RoundingMode.UP; else -> java.math.RoundingMode.DOWN
                    }).toDouble()
                    if (y.isInfinite()) x else r
                }
            }
            "CEILING", "FLOOR", "CEILING.MATH", "FLOOR.MATH", "MROUND" -> {
                need(1); val x = d(0); val sig = if (a.size > 1) d(1) else 1.0
                if (x is Err) x else if (sig is Err) sig else if (sig == 0.0) 0.0 else (x as Double / sig as Double).let {
                    when { n.name.startsWith("CEILING") -> ceil(it - 1e-12); n.name == "MROUND" -> kotlin.math.round(it); else -> floor(it + 1e-12) } * sig
                }
            }
            "SUMIF", "COUNTIF", "AVERAGEIF" -> {
                need(2)
                val range = calc(a[0], ctx) as? Area ?: return Err("#VALUE!")
                val test = criterion(v(1), ctx)
                val sumArea = (if (n.name != "COUNTIF" && a.size > 2) calc(a[2], ctx) as? Area else null) ?: range
                var sum = 0.0; var k = 0.0; var numbers = 0
                for (r in 0..min(range.range.r2 - range.range.r1, 200_000)) for (c in 0..min(range.range.c2 - range.range.c1, 1_000)) {
                    if (!test(ctx.cell(range.sheet, range.range.r1 + r, range.range.c1 + c))) continue
                    k++
                    // only numbers are summed and averaged ("7" written as words is not)
                    (ctx.cell(sumArea.sheet, sumArea.range.r1 + r, sumArea.range.c1 + c) as? Double)?.let { sum += it; numbers++ }
                }
                when (n.name) { "COUNTIF" -> k; "SUMIF" -> sum; else -> if (numbers == 0) Err("#DIV/0!") else sum / numbers }
            }
            "SUMIFS", "COUNTIFS", "AVERAGEIFS", "MAXIFS", "MINIFS" -> {
                val counting = n.name == "COUNTIFS"
                val target = if (counting) null else calc(a[0], ctx) as? Area ?: return Err("#VALUE!")
                val pairs = (if (counting) a else a.drop(1)).chunked(2).map { (calc(it[0], ctx) as? Area ?: return Err("#VALUE!")) to criterion(value(it[1], ctx), ctx) }
                val first = pairs.firstOrNull()?.first ?: return Err("#VALUE!")
                val hits = ArrayList<Double>(); var k = 0.0
                for (r in 0..min(first.range.r2 - first.range.r1, 200_000)) for (c in 0..min(first.range.c2 - first.range.c1, 1_000)) {
                    if (!pairs.all { (area, test) -> test(ctx.cell(area.sheet, area.range.r1 + r, area.range.c1 + c)) }) continue
                    k++
                    target?.let { t -> (ctx.cell(t.sheet, t.range.r1 + r, t.range.c1 + c) as? Double)?.let { hits += it } }
                }
                when (n.name) {
                    "COUNTIFS" -> k; "SUMIFS" -> hits.sum(); "MAXIFS" -> hits.maxOrNull() ?: 0.0; "MINIFS" -> hits.minOrNull() ?: 0.0
                    else -> if (hits.isEmpty()) Err("#DIV/0!") else hits.average()
                }
            }
            "VLOOKUP", "HLOOKUP" -> {
                need(3)
                val key = v(0); if (key is Err) return key
                val table = calc(a[1], ctx) as? Area ?: return Err("#VALUE!")
                val k = (d(2) as? Double)?.toInt() ?: return Err("#VALUE!")
                val exact = a.size > 3 && truthy(v(3)) == false
                val vertical = n.name == "VLOOKUP"
                val count = if (vertical) min(table.range.r2 - table.range.r1, 200_000) + 1 else min(table.range.c2 - table.range.c1, 16_383) + 1
                fun at(i: Int, j: Int) = if (vertical) ctx.cell(table.sheet, table.range.r1 + i, table.range.c1 + j) else ctx.cell(table.sheet, table.range.r1 + j, table.range.c1 + i)
                if (k < 1 || k > (if (vertical) table.range.c2 - table.range.c1 else table.range.r2 - table.range.r1) + 1) return Err("#REF!")
                val found = find(count, { at(it, 0) }, key, if (exact) 0 else 1) ?: return Err("#N/A")
                at(found, k - 1) ?: 0.0
            }
            "MATCH" -> {
                need(2)
                val key = v(0); if (key is Err) return key
                val area = calc(a[1], ctx) as? Area ?: return Err("#N/A")
                val type = if (a.size > 2) (d(2) as? Double)?.toInt() ?: 1 else 1
                val across = area.range.r1 == area.range.r2
                val count = if (across) area.range.c2 - area.range.c1 + 1 else min(area.range.r2 - area.range.r1, 200_000) + 1
                find(count, { if (across) ctx.cell(area.sheet, area.range.r1, area.range.c1 + it) else ctx.cell(area.sheet, area.range.r1 + it, area.range.c1) }, key, type)?.let { it + 1.0 } ?: Err("#N/A")
            }
            // Excel's SUBTOTAL: 1 AVERAGE, 2 COUNT, 3 COUNTA, 4 MAX, 5 MIN, 6 PRODUCT, 7 STDEV, 8 STDEVP, 9 SUM, 10 VAR, 11 VARP (and
            // 101–111 the same); cells that are subtotals themselves left out, so a grand total counts each value once
            "SUBTOTAL" -> {
                need(2)
                val fn = (d(0) as? Double)?.toInt()?.let { if (it > 100) it - 100 else it } ?: return Err("#VALUE!")
                val name = listOf("AVERAGE", "COUNT", "COUNTA", "MAX", "MIN", "PRODUCT", "STDEV", "STDEVP", "SUM", "VAR", "VARP").getOrNull(fn - 1) ?: return Err("#VALUE!")
                val values = ArrayList<Any?>()
                for (arg in a.drop(1)) when (val x = calc(arg, ctx)) {
                    is Area -> for (r in x.range.r1..min(x.range.r2, x.range.r1 + 200_000)) for (c in x.range.c1..min(x.range.c2, x.range.c1 + 1_000))
                        if (ctx.formulaOf(x.sheet, r, c)?.contains("SUBTOTAL(", ignoreCase = true) != true) values += ctx.cell(x.sheet, r, c)
                    else -> values += x
                }
                val nums = values.filterIsInstance<Double>()
                when (name) {
                    "COUNT" -> nums.size.toDouble(); "COUNTA" -> values.count { it != null && it != "" }.toDouble()
                    "SUM" -> nums.sum(); "PRODUCT" -> nums.fold(1.0) { p, q -> p * q }
                    "AVERAGE" -> if (nums.isEmpty()) Err("#DIV/0!") else nums.average()
                    "MAX" -> nums.maxOrNull() ?: 0.0; "MIN" -> nums.minOrNull() ?: 0.0
                    else -> { val p = name.endsWith("P"); if (nums.size < (if (p) 1 else 2)) Err("#DIV/0!") else { val m = nums.average(); val v = nums.sumOf { (it - m) * (it - m) } / (nums.size - if (p) 0 else 1); if (name.startsWith("VAR")) v else sqrt(v) } }
                }
            }
            // Excel's dynamic arrays: their results spill (see [Arr])
            "SEQUENCE" -> {
                need(1)
                val rows = (d(0) as? Double)?.toInt() ?: return Err("#VALUE!"); val cols = if (a.size > 1) (d(1) as? Double)?.toInt() ?: return Err("#VALUE!") else 1
                val start = if (a.size > 2) d(2) as? Double ?: return Err("#VALUE!") else 1.0; val step = if (a.size > 3) d(3) as? Double ?: return Err("#VALUE!") else 1.0
                if (rows < 1 || cols < 1 || rows.toLong() * cols > 1_000_000) Err("#VALUE!") else Arr((0 until rows).map { r -> (0 until cols).map { c -> start + step * (r * cols + c) } })
            }
            "UNIQUE" -> {
                need(1)
                val rows = arr(calc(a[0], ctx), ctx).rows
                val once = a.size > 2 && truthy(v(2)) == true
                fun key(row: List<Any?>) = row.map { x -> if (x is String) x.lowercase() else x }
                val counts = rows.groupingBy(::key).eachCount()
                rows.distinctBy(::key).filter { !once || counts.getValue(key(it)) == 1 }.let { if (it.isEmpty()) Err("#CALC!") else Arr(it) }
            }
            "SORT" -> {
                need(1)
                val rows = arr(calc(a[0], ctx), ctx).rows
                val col = (if (a.size > 1) (d(1) as? Double)?.toInt() ?: 1 else 1) - 1
                val down = a.size > 2 && (d(2) as? Double ?: 1.0) < 0
                if (col !in 0 until (rows.firstOrNull()?.size ?: 0)) Err("#VALUE!")
                else Arr(rows.sortedWith { x, y -> compare(x[col], y[col]).let { if (down) -it else it } })
            }
            "FILTER" -> {
                need(2)
                val rows = arr(calc(a[0], ctx), ctx).rows; val keep = arr(calc(a[1], ctx), ctx).rows
                if (keep.size != rows.size) return Err("#VALUE!")
                val kept = rows.filterIndexed { i, _ -> keep[i].firstOrNull().let { k -> k == true || (k is Double && k != 0.0) } }
                if (kept.isNotEmpty()) Arr(kept) else if (a.size > 2) v(2) else Err("#CALC!")
            }
            "XLOOKUP" -> {
                need(3)
                val key = v(0); if (key is Err) return key
                val look = calc(a[1], ctx) as? Area ?: return Err("#VALUE!")
                val back = calc(a[2], ctx) as? Area ?: return Err("#VALUE!")
                val across = look.range.r1 == look.range.r2
                val count = if (across) look.range.c2 - look.range.c1 + 1 else min(look.range.r2 - look.range.r1, 200_000) + 1
                val i = find(count, { if (across) ctx.cell(look.sheet, look.range.r1, look.range.c1 + it) else ctx.cell(look.sheet, look.range.r1 + it, look.range.c1) }, key, 0)
                if (i == null) (if (a.size > 3) v(3) else Err("#N/A"))
                else (if (across) ctx.cell(back.sheet, back.range.r1, back.range.c1 + i) else ctx.cell(back.sheet, back.range.r1 + i, back.range.c1)) ?: 0.0
            }
            "INDEX" -> {
                need(2)
                val area = calc(a[0], ctx) as? Area ?: return Err("#VALUE!")
                var r = (d(1) as? Double)?.toInt() ?: return Err("#VALUE!")
                var c = if (a.size > 2) (d(2) as? Double)?.toInt() ?: return Err("#VALUE!") else 1
                if (area.range.r1 == area.range.r2 && a.size == 2) { c = r; r = 1 }
                if (r < 1 || c < 1 || area.range.r1 + r - 1 > area.range.r2 || area.range.c1 + c - 1 > area.range.c2) Err("#REF!")
                else ctx.cell(area.sheet, area.range.r1 + r - 1, area.range.c1 + c - 1) ?: 0.0
            }
            "RANK", "RANK.EQ" -> {
                need(2)
                val x = d(0) as? Double ?: return Err("#VALUE!")
                val list = numbers(listOf(a[1]), ctx) as? List<Double> ?: return Err("#VALUE!")
                val ascending = a.size > 2 && (d(2) as? Double ?: 0.0) != 0.0
                if (x !in list) Err("#N/A") else 1.0 + list.count { if (ascending) it < x else it > x }
            }
            "LARGE", "SMALL" -> {
                need(2)
                val list = (numbers(listOf(a[0]), ctx) as? List<Double>)?.sorted() ?: return Err("#VALUE!")
                val k = (d(1) as? Double)?.toInt() ?: return Err("#VALUE!")
                if (k < 1 || k > list.size) Err("#NUM!") else if (n.name == "SMALL") list[k - 1] else list[list.size - k]
            }
            "CONCATENATE", "CONCAT" -> { val sb = StringBuilder(); var err: Err? = null; flat(a, ctx) { x, _ -> if (x is Err) err = err ?: x else sb.append(text(x, ctx)) }; err ?: sb.toString() }
            "TEXTJOIN" -> {
                need(3)
                val sep = text(v(0), ctx); val skip = truthy(v(1)) == true
                val parts = ArrayList<String>(); flat(a.drop(2), ctx) { x, _ -> val t = text(x, ctx); if (!skip || t.isNotEmpty()) parts += t }
                parts.joinToString(sep)
            }
            "LEN" -> { need(1); val x = v(0); if (x is Err) x else text(x, ctx).length.toDouble() }
            "UPPER", "LOWER", "TRIM", "PROPER" -> { need(1); val x = v(0); if (x is Err) x else text(x, ctx).let { t -> when (n.name) {
                "UPPER" -> t.uppercase(); "LOWER" -> t.lowercase(); "TRIM" -> t.trim().replace(Regex(" +"), " ")
                else -> t.lowercase().replace(Regex("(^|[^\\p{L}])(\\p{L})")) { m -> m.groupValues[1] + m.groupValues[2].uppercase() } } } }
            "LEFT", "RIGHT" -> {
                need(1); val x = v(0); if (x is Err) return x
                val t = text(x, ctx); val k = if (a.size > 1) (d(1) as? Double)?.toInt() ?: return Err("#VALUE!") else 1
                if (k < 0) Err("#VALUE!") else if (n.name == "LEFT") t.take(k) else t.takeLast(k)
            }
            "MID" -> {
                need(3); val x = v(0); if (x is Err) return x
                val t = text(x, ctx); val from = (d(1) as? Double)?.toInt() ?: return Err("#VALUE!"); val k = (d(2) as? Double)?.toInt() ?: return Err("#VALUE!")
                if (from < 1 || k < 0) Err("#VALUE!") else t.drop(from - 1).take(k)
            }
            "SUBSTITUTE" -> { need(3); text(v(0), ctx).replace(text(v(1), ctx), text(v(2), ctx)) }
            "REPT" -> { need(2); text(v(0), ctx).repeat(((d(1) as? Double) ?: 0.0).toInt().coerceIn(0, 32767)) }
            "FIND", "SEARCH" -> {
                need(2)
                val what = text(v(0), ctx); val within = text(v(1), ctx); val from = if (a.size > 2) ((d(2) as? Double) ?: 1.0).toInt() else 1
                val i = within.indexOf(what, from - 1, ignoreCase = n.name == "SEARCH")
                if (i < 0) Err("#VALUE!") else i + 1.0
            }
            "VALUE" -> { need(1); num(v(0)) }
            "TEXT" -> { need(2); val x = num(v(0)); if (x is Err) text(v(0), ctx) else ctx.format.format(x as Double, text(v(1), ctx)).text }
            "EXACT" -> { need(2); text(v(0), ctx) == text(v(1), ctx) }
            "TODAY" -> serial(ctx.today.atStartOfDay())
            "NOW" -> serial(LocalDateTime.now())
            "DATE" -> {
                need(3); val y = d(0) as? Double ?: return Err("#VALUE!"); val m = d(1) as? Double ?: return Err("#VALUE!"); val dd = d(2) as? Double ?: return Err("#VALUE!")
                val year = y.toInt().let { if (it < 1900) it + 1900 else it }
                serial(LocalDate.of(year, 1, 1).plusMonths(m.toLong() - 1).plusDays(dd.toLong() - 1).atStartOfDay())
            }
            "YEAR", "MONTH", "DAY", "WEEKDAY", "HOUR", "MINUTE", "SECOND" -> {
                need(1); val x = d(0) as? Double ?: return Err("#VALUE!")
                val t = ctx.format.dateTime(x)
                when (n.name) {
                    "YEAR" -> t.year; "MONTH" -> t.monthValue; "DAY" -> t.dayOfMonth; "HOUR" -> t.hour; "MINUTE" -> t.minute; "SECOND" -> t.second
                    else -> {
                        val sundayFirst = t.dayOfWeek.value % 7 + 1 // Sunday 1 … Saturday 7
                        when ((if (a.size > 1) d(1) as? Double else null)?.toInt() ?: 1) { 2 -> t.dayOfWeek.value; 3 -> t.dayOfWeek.value - 1; else -> sundayFirst }
                    }
                }.toDouble()
            }
            "DAYS" -> { need(2); binary("-", v(0), v(1), ctx) }
            "EDATE", "EOMONTH" -> {
                need(2); val x = d(0) as? Double ?: return Err("#VALUE!"); val m = (d(1) as? Double)?.toLong() ?: return Err("#VALUE!")
                val t = ctx.format.dateTime(x).toLocalDate().plusMonths(m)
                serial((if (n.name == "EOMONTH") t.withDayOfMonth(t.lengthOfMonth()) else t).atStartOfDay())
            }
            "ISBLANK" -> { need(1); (a[0] as? Ref)?.let { ctx.cell(it.sheet, it.range.r1, it.range.c1) == null } ?: false }
            "ISNUMBER" -> { need(1); v(0) is Double }
            "ISTEXT" -> { need(1); v(0).let { it is String } }
            "ISERROR" -> { need(1); v(0) is Err }
            "ISNA" -> { need(1); (v(0) as? Err)?.code == "#N/A" }
            "NA" -> Err("#N/A")
            "CHOOSE" -> { need(2); val k = (d(0) as? Double)?.toInt() ?: return Err("#VALUE!"); if (k < 1 || k >= a.size) Err("#VALUE!") else v(k) }
            "ROW", "COLUMN" -> (a.firstOrNull() as? Ref)?.let { (if (n.name == "ROW") it.range.r1 else it.range.c1) + 1.0 } ?: throw Unknown("${n.name}() of its own cell")
            "ROWS", "COLUMNS" -> { need(1); (calc(a[0], ctx) as? Area)?.range?.let { (if (n.name == "ROWS") it.r2 - it.r1 else it.c2 - it.c1) + 1.0 } ?: 1.0 }
            else -> throw Unknown(n.name)
        }
    }

    /** An Excel serial number of a time. */
    fun serial(t: LocalDateTime) = ChronoUnit.SECONDS.between(LocalDate.of(1899, 12, 30).atStartOfDay(), t) / 86_400.0

    /** Where [key] is among [count] values: 0 exact (words without case, * and ? as wildcards), 1 the largest ≤, -1 the smallest ≥. */
    private fun find(count: Int, at: (Int) -> Any?, key: Any, type: Int): Int? {
        if (type == 0) {
            val pattern = (key as? String)?.takeIf { '*' in it || '?' in it }?.let { wild(it) }
            for (i in 0 until count) { val x = at(i) ?: continue; if (if (pattern != null) x is String && pattern.matches(x) else compare(x, key) == 0) return i }
            return null
        }
        var best: Int? = null
        for (i in 0 until count) {
            val x = at(i) ?: continue
            if (x::class != key::class) continue
            val k = compare(x, key)
            if (type > 0) { if (k <= 0) best = i else break } else { if (k >= 0) best = i else break }
        }
        return best
    }

    private fun wild(s: String) = Regex(Regex.escape(s).replace("*", "\\E.*\\Q").replace("?", "\\E.\\Q"), RegexOption.IGNORE_CASE)

    /** COUNTIF's test: ">5", "<>x", "=x", "abc*", or a value. */
    private fun criterion(c: Any?, ctx: Context): (Any?) -> Boolean {
        if (c !is String) return { x -> x != null && compare(x, c) == 0 }
        val m = Regex("""^(<>|<=|>=|=|<|>)?(.*)$""", RegexOption.DOT_MATCHES_ALL).find(c)!!
        val op = m.groupValues[1]; val rest = m.groupValues[2]
        val n = rest.trim().replace(',', '.').toDoubleOrNull()
        val target: Any = n ?: rest
        if (op.isEmpty() || op == "=") {
            if (rest.isEmpty()) return { x -> x == null || x == "" }
            if (n == null && ('*' in rest || '?' in rest)) { val w = wild(rest); return { x -> x is String && w.matches(x) } }
            return { x -> x != null && (if (n != null) x is Double && x == n else x is String && x.equals(rest, ignoreCase = true)) }
        }
        if (op == "<>") {
            if (rest.isEmpty()) return { x -> x != null && x != "" }
            if (n == null && ('*' in rest || '?' in rest)) { val w = wild(rest); return { x -> !(x is String && w.matches(x)) } }
            return { x -> x == null || !(if (n != null) x is Double && x == n else x is String && x.equals(rest, ignoreCase = true)) }
        }
        return { x -> x != null && x::class == target::class && compare(x, target).let { k -> when (op) { "<" -> k < 0; ">" -> k > 0; "<=" -> k <= 0; else -> k >= 0 } } }
    }
}
