package com.kalotrapezis.drive

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Custom shapes drawn from their own geometry (Insert › Shapes, files' shapes): formulas over the shape's size and
 * its adjustments, and a path of their results — Word's 187 preset shapes (DrawingML's presetShapeDefinitions, from
 * LibreOffice's copy: resources/shapes/presets.txt, tools/shape-presets.py) and LibreOffice's own custom shapes
 * (draw:enhanced-geometry: draw:equation, draw:enhanced-path). The path comes out as plain moves, lines and curves,
 * arcs made Bézier curves, so it is drawn (and tested) anywhere.
 */
internal class ShapeGeometry(
    val adjustNames: List<String>, val adjustDefaults: List<Double>, val equations: List<String>,
    /** Each point's x and y: a number, "?3" (equation 3), "$0" (adjustment 0) — or, in an ODF path, a formula's name. */
    val coords: List<Pair<String, String>>, val segments: List<Pair<Int, Int>>,
    /** Each sub-path's own coordinate space (0: the shape's), Word's path w/h. */
    val subViews: List<Pair<Double, Double>> = emptyList(),
    /** An ODF shape's view box: its coordinates and "width"/"height" are in it. Null: the shape's own size. */
    val viewBox: Pair<Double, Double>? = null,
    val textFrame: List<String> = emptyList(),
) {
    /** One sub-path, as drawn: its moves, lines and curves; whether it is filled and stroked, its shade (−: darker). */
    class Sub(val ops: List<Op>, val fill: Boolean, val stroke: Boolean, val shade: Float)
    sealed class Op {
        data class Move(val x: Float, val y: Float) : Op()
        data class Line(val x: Float, val y: Float) : Op()
        data class Cubic(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : Op()
        data class Quad(val x1: Float, val y1: Float, val x: Float, val y: Float) : Op()
        object Close : Op()
    }

    private val parsed: List<Expr> by lazy { equations.map { Parser(it, equationNames).parse() } }
    /** An ODF shape's equations by name ("f0" → 0); a preset's are by number. */
    var equationNames: Map<String, Int> = emptyMap()

    /** The shape's sub-paths in a [w]×[h] box from 0,0, with [adjust] (by name) over its defaults. */
    fun paths(w: Float, h: Float, adjust: Map<String, Double> = emptyMap()): List<Sub> {
        val logW = viewBox?.first ?: w.toDouble(); val logH = viewBox?.second ?: h.toDouble()
        val adj = DoubleArray(adjustDefaults.size) { i -> adjust[adjustNames.getOrNull(i)] ?: adjustDefaults[i] }
        val env = Env(logW, logH, adj)
        val values = arrayOfNulls<Double>(parsed.size)
        val busy = BooleanArray(parsed.size)
        fun eq(i: Int): Double {
            values.getOrNull(i)?.let { return it }
            if (i !in parsed.indices || busy[i]) return 0.0
            busy[i] = true
            val v = parsed[i].eval(env, ::eq).takeIf { it.isFinite() } ?: 0.0
            busy[i] = false; values[i] = v
            return v
        }
        env.equation = ::eq
        fun value(p: String): Double = when {
            p.startsWith("?") -> equationNames[p.substring(1)]?.let(::eq) ?: p.substring(1).toIntOrNull()?.let(::eq) ?: 0.0
            p.startsWith("$") -> adj.getOrNull(p.substring(1).toIntOrNull() ?: -1) ?: 0.0
            else -> p.toDoubleOrNull() ?: Parser(p, equationNames).parse().eval(env, ::eq)
        }
        val out = ArrayList<Sub>()
        var ops = ArrayList<Op>(); var fill = true; var stroke = true; var shade = 0f
        var sub = 0
        var cx = 0.0; var cy = 0.0; var startX = 0.0; var startY = 0.0
        var k = 0
        fun scaleX(x: Double): Float { val sw = subViews.getOrNull(sub)?.first?.takeIf { it > 0 }; return ((if (sw != null) x / sw * logW else x) * w / logW).toFloat() }
        fun scaleY(y: Double): Float { val sh = subViews.getOrNull(sub)?.second?.takeIf { it > 0 }; return ((if (sh != null) y / sh * logH else y) * h / logH).toFloat() }
        fun point(): Pair<Double, Double>? { val c = coords.getOrNull(k++) ?: return null; return value(c.first) to value(c.second) }
        fun to(x: Double, y: Double) { cx = x; cy = y }
        fun move(x: Double, y: Double) { ops += Op.Move(scaleX(x), scaleY(y)); to(x, y); startX = x; startY = y }
        fun line(x: Double, y: Double) { if (ops.isEmpty()) move(x, y) else { ops += Op.Line(scaleX(x), scaleY(y)); to(x, y) } }
        /** An arc of the ellipse round (ex, ey), radii rx, ry, from [a0] for [sweep] radians (parametric), as curves. */
        fun arc(ex: Double, ey: Double, rx: Double, ry: Double, a0: Double, sweep: Double, moveFirst: Boolean) {
            val sx = ex + rx * cos(a0); val sy = ey + ry * sin(a0)
            if (moveFirst || ops.isEmpty()) move(sx, sy) else if (abs(sx - cx) > 1e-6 || abs(sy - cy) > 1e-6) line(sx, sy)
            val n = ceil(abs(sweep) / (PI / 2)).toInt().coerceAtLeast(1)
            val step = sweep / n
            val kappa = 4.0 / 3.0 * tan(step / 4)
            var a = a0
            repeat(n) {
                val b = a + step
                val x1 = ex + rx * (cos(a) - kappa * sin(a)); val y1 = ey + ry * (sin(a) + kappa * cos(a))
                val x2 = ex + rx * (cos(b) + kappa * sin(b)); val y2 = ey + ry * (sin(b) - kappa * cos(b))
                val x = ex + rx * cos(b); val y = ey + ry * sin(b)
                ops += Op.Cubic(scaleX(x1), scaleY(y1), scaleX(x2), scaleY(y2), scaleX(x), scaleY(y)); to(x, y)
                a = b
            }
        }
        /** ODF's arcs given by a bounding box and two points: the ellipse's angles at them, the sweep one way or the other. */
        fun boxArc(clockwise: Boolean, moveFirst: Boolean) {
            val (l, t) = point() ?: return; val (r, b) = point() ?: return
            val (x1, y1) = point() ?: return; val (x2, y2) = point() ?: return
            val ex = (l + r) / 2; val ey = (t + b) / 2; val rx = abs(r - l) / 2; val ry = abs(b - t) / 2
            if (rx == 0.0 || ry == 0.0) return
            val a0 = atan2((y1 - ey) / ry, (x1 - ex) / rx); val a1 = atan2((y2 - ey) / ry, (x2 - ex) / rx)
            var sweep = a1 - a0
            if (clockwise) { while (sweep <= 0) sweep += 2 * PI } else { while (sweep >= 0) sweep -= 2 * PI }
            arc(ex, ey, rx, ry, a0, sweep, moveFirst)
        }
        fun end() { if (ops.isNotEmpty()) out += Sub(ops, fill, stroke, shade); ops = ArrayList(); fill = true; stroke = true; shade = 0f; sub++ }
        for ((cmd, count) in segments) {
            val n = count.coerceAtLeast(if (cmd in setOf(1, 2)) 1 else 0)
            when (cmd) {
                1 -> repeat(n) { point()?.let { (x, y) -> move(x, y) } }
                2 -> repeat(n) { point()?.let { (x, y) -> line(x, y) } }
                3 -> repeat(n) { val a = point(); val b = point(); val c = point()
                    if (a != null && b != null && c != null) { ops += Op.Cubic(scaleX(a.first), scaleY(a.second), scaleX(b.first), scaleY(b.second), scaleX(c.first), scaleY(c.second)); to(c.first, c.second) } }
                16 -> repeat(n) { val a = point(); val b = point()
                    if (a != null && b != null) { ops += Op.Quad(scaleX(a.first), scaleY(a.second), scaleX(b.first), scaleY(b.second)); to(b.first, b.second) } }
                4 -> { if (ops.isNotEmpty()) ops += Op.Close; to(startX, startY) }
                5 -> end()
                6 -> fill = false
                7 -> stroke = false
                // ARCANGLETO (Word's arcTo): radii, then the start and swing angles (degrees, as seen on the stretched ellipse).
                17 -> repeat(n) {
                    val (rx, ry) = point() ?: return@repeat; val (st, sw) = point() ?: return@repeat
                    fun parametric(deg: Double) = (deg * PI / 180).let { v -> atan2(rx * sin(v), ry * cos(v)) }
                    val a0 = parametric(st)
                    var sweep = parametric(st + sw) - a0
                    if (abs(sw) >= 360) sweep = if (sw > 0) 2 * PI else -2 * PI
                    else { if (sw > 0) while (sweep < 0) sweep += 2 * PI else if (sw < 0) while (sweep > 0) sweep -= 2 * PI }
                    arc(cx - rx * cos(a0), cy - ry * sin(a0), rx, ry, a0, sweep, false)
                }
                18 -> shade = -0.4f; 19 -> shade = -0.2f; 20 -> shade = 0.4f; 21 -> shade = 0.2f
                // ODF's angle-ellipse(to): centre, radii, start and end angles (degrees, counter-clockwise on screen: y down).
                8, 9 -> repeat(n) { val (x, y) = point() ?: return@repeat; val (rx, ry) = point() ?: return@repeat; val (s0, s1) = point() ?: return@repeat
                    var sweep = (s1 - s0); if (sweep <= 0) sweep += 360
                    arc(x, y, rx, ry, -s0 * PI / 180, -sweep * PI / 180, cmd == 9) }
                10, 11 -> repeat(n) { boxArc(false, cmd == 11) } // arcto, arc: counter-clockwise
                12, 13 -> repeat(n) { boxArc(true, cmd == 13) } // clockwise
                14, 15 -> { var xFirst = cmd == 14
                    repeat(n) { val (x, y) = point() ?: return@repeat
                        // A quarter ellipse to (x, y): first along x, then y (or the other way), then turning each time.
                        val ex = if (xFirst) cx else x; val ey = if (xFirst) y else cy
                        val rx = abs(x - cx); val ry = abs(y - cy)
                        val a0 = atan2((cy - ey) / (if (ry == 0.0) 1.0 else ry), (cx - ex) / (if (rx == 0.0) 1.0 else rx))
                        val a1 = atan2((y - ey) / (if (ry == 0.0) 1.0 else ry), (x - ex) / (if (rx == 0.0) 1.0 else rx))
                        var sweep = a1 - a0; while (sweep > PI) sweep -= 2 * PI; while (sweep < -PI) sweep += 2 * PI
                        if (rx > 0 && ry > 0) arc(ex, ey, rx, ry, a0, sweep, false) else line(x, y)
                        xFirst = !xFirst } }
            }
        }
        end()
        return out
    }

    /** The text's rectangle in the shape (left, top, right, bottom in a [w]×[h] box), or the whole shape. */
    fun textBox(w: Float, h: Float, adjust: Map<String, Double> = emptyMap()): FloatArray {
        if (textFrame.size < 4) return floatArrayOf(0f, 0f, w, h)
        // The frame's numbers through the same evaluation as the path: a tiny geometry of its own two points.
        val g = ShapeGeometry(adjustNames, adjustDefaults, equations, listOf(textFrame[0] to textFrame[1], textFrame[2] to textFrame[3]), listOf(1 to 1, 2 to 1), subViews.take(1), viewBox)
        g.equationNames = equationNames
        val ops = g.paths(w, h, adjust).firstOrNull()?.ops ?: return floatArrayOf(0f, 0f, w, h)
        val a = ops[0] as Op.Move; val b = ops[1] as Op.Line
        return floatArrayOf(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
    }

    // ---- formulas: ODF's enhanced-geometry formula language (LibreOffice writes Word's presets in it)

    class Env(val w: Double, val h: Double, val adj: DoubleArray) { var equation: (Int) -> Double = { 0.0 } }
    sealed class Expr {
        abstract fun eval(e: Env, eq: (Int) -> Double): Double
        class Num(val v: Double) : Expr() { override fun eval(e: Env, eq: (Int) -> Double) = v }
        class Ref(val i: Int) : Expr() { override fun eval(e: Env, eq: (Int) -> Double) = eq(i) }
        class Adj(val i: Int) : Expr() { override fun eval(e: Env, eq: (Int) -> Double) = e.adj.getOrElse(i) { 0.0 } }
        class Name(val n: String) : Expr() { override fun eval(e: Env, eq: (Int) -> Double) = when (n) {
            "pi" -> PI; "width", "logwidth", "right" -> e.w; "height", "logheight", "bottom" -> e.h; "hasstroke", "hasfill" -> 1.0; else -> 0.0 } }
        class Neg(val a: Expr) : Expr() { override fun eval(e: Env, eq: (Int) -> Double) = -a.eval(e, eq) }
        class Bin(val op: Char, val a: Expr, val b: Expr) : Expr() {
            override fun eval(e: Env, eq: (Int) -> Double): Double { val x = a.eval(e, eq); val y = b.eval(e, eq)
                return when (op) { '+' -> x + y; '-' -> x - y; '*' -> x * y; else -> if (y == 0.0) 0.0 else x / y } }
        }
        class Call(val f: String, val args: List<Expr>) : Expr() {
            override fun eval(e: Env, eq: (Int) -> Double): Double {
                fun a(i: Int) = args.getOrNull(i)?.eval(e, eq) ?: 0.0
                return when (f) {
                    "abs" -> abs(a(0)); "sqrt" -> sqrt(maxOf(0.0, a(0))); "sin" -> sin(a(0)); "cos" -> cos(a(0)); "tan" -> tan(a(0))
                    "atan" -> kotlin.math.atan(a(0)); "atan2" -> atan2(a(0), a(1)); "min" -> minOf(a(0), a(1)); "max" -> maxOf(a(0), a(1))
                    "if" -> if (a(0) > 0) a(1) else a(2)
                    else -> 0.0
                }
            }
        }
    }

    private class Parser(val s: String, val names: Map<String, Int>) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun parse(): Expr = runCatching { expr() }.getOrElse { Expr.Num(0.0) }
        fun expr(): Expr { var a = term(); while (true) { ws(); val c = s.getOrNull(i); if (c == '+' || c == '-') { i++; a = Expr.Bin(c, a, term()) } else return a } }
        fun term(): Expr { var a = factor(); while (true) { ws(); val c = s.getOrNull(i); if (c == '*' || c == '/') { i++; a = Expr.Bin(c, a, factor()) } else return a } }
        fun factor(): Expr {
            ws()
            val c = s.getOrNull(i) ?: return Expr.Num(0.0)
            return when {
                c == '-' -> { i++; Expr.Neg(factor()) }
                c == '+' -> { i++; factor() }
                c == '(' -> { i++; val e = expr(); ws(); if (s.getOrNull(i) == ')') i++; e }
                c == '?' -> { i++; val n = word(); Expr.Ref(names[n] ?: n.toIntOrNull() ?: -1) }
                c == '$' -> { i++; Expr.Adj(word().toIntOrNull() ?: -1) }
                c.isDigit() || c == '.' -> { val st = i; while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E')) i++; Expr.Num(s.substring(st, i).toDoubleOrNull() ?: 0.0) }
                c.isLetter() -> { val n = word(); ws()
                    if (s.getOrNull(i) == '(') { i++; val args = ArrayList<Expr>(); ws()
                        if (s.getOrNull(i) != ')') while (true) { args += expr(); ws(); if (s.getOrNull(i) == ',') i++ else break }
                        ws(); if (s.getOrNull(i) == ')') i++
                        Expr.Call(n, args)
                    } else Expr.Name(n) }
                else -> { i++; Expr.Num(0.0) }
            }
        }
        fun word(): String { val st = i; while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++; return s.substring(st, i) }
    }

    companion object {
        /** Word's preset shapes by name (DrawingML's prst), read once. */
        val presets: Map<String, ShapeGeometry> by lazy {
            runCatching {
                ShapeGeometry::class.java.getResourceAsStream("/shapes/presets.txt")!!.bufferedReader(Charsets.UTF_8).readLines()
                    .filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
                        val f = line.split('\t').let { it + List(7 - it.size.coerceAtMost(7)) { "" } }
                        val adj = f[1].split(',').filter { it.contains('=') }.map { it.substringBefore('=') to (it.substringAfter('=').toDoubleOrNull() ?: 0.0) }
                        f[0] to ShapeGeometry(adj.map { it.first }, adj.map { it.second }, f[2].split(';').filter { it.isNotBlank() },
                            f[3].split(' ').filter { it.contains(',') }.map { it.substringBefore(',') to it.substringAfter(',') },
                            f[4].split(',').filter { it.contains(':') }.map { it.substringBefore(':').toInt() to it.substringAfter(':').toInt() },
                            f[5].split(',').filter { it.contains(':') }.map { (it.substringBefore(':').toDoubleOrNull() ?: 0.0) to (it.substringAfter(':').toDoubleOrNull() ?: 0.0) },
                            null, f[6].split(',').filter { it.isNotBlank() })
                    }
            }.onFailure { WriterLog.e("shape presets", it) }.getOrDefault(emptyMap())
        }

        /**
         * A preset as LibreOffice writes one into an .odt (its draw:enhanced-geometry): its type ("ooxml-heart"), the view
         * box the shape's size in 1/100 mm and the equations over it (ODF's width/height: LibreOffice's logwidth in a text
         * document is in twips — shapes came out half size), the adjustments as modifiers, the
         * equations as "f0"…, the path in ODF's letters (G: Word's arcTo, H–K its shades), sub-paths' own sizes.
         */
        fun odfGeometry(name: String, widthPt: Float, heightPt: Float, adjust: Map<String, Double> = emptyMap()): String? {
            val g = presets[name] ?: return null
            fun ref(p: String) = if (p.startsWith("?")) "?f" + p.substring(1) else p
            val letters = mapOf(1 to "M", 2 to "L", 3 to "C", 4 to "Z", 5 to "N", 6 to "F", 7 to "S", 16 to "Q", 17 to "G", 18 to "H", 19 to "I", 20 to "J", 21 to "K")
            val path = StringBuilder(); var k = 0
            for ((cmd, count) in g.segments) {
                path.append(letters[cmd] ?: continue).append(' ')
                val n = count * (POINTS[cmd] ?: 0)
                repeat(n) { g.coords.getOrNull(k++)?.let { (x, y) -> path.append(ref(x)).append(' ').append(ref(y)).append(' ') } }
            }
            val w = Math.round(widthPt * 2540 / 72.0); val h = Math.round(heightPt * 2540 / 72.0)
            fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
            val mods = g.adjustNames.indices.joinToString(" ") { i -> (adjust[g.adjustNames[i]] ?: g.adjustDefaults[i]).let { v -> if (v == Math.floor(v)) v.toLong().toString() else v.toString() } }
            val sub = if (g.subViews.isEmpty()) "" else " drawooo:sub-view-size=\"" + g.subViews.joinToString(" ") { "${it.first.toLong()} ${it.second.toLong()}" } + "\""
            val frame = if (g.textFrame.size == 4) " draw:text-areas=\"" + g.textFrame.joinToString(" ") { ref(it) } + "\"" else ""
            return "<draw:enhanced-geometry xmlns:drawooo=\"http://openoffice.org/2010/draw\" svg:viewBox=\"0 0 $w $h\"$frame draw:type=\"ooxml-$name\"" +
                (if (mods.isEmpty()) "" else " draw:modifiers=\"$mods\"") + "$sub draw:enhanced-path=\"${path.toString().trim()}\">" +
                g.equations.mapIndexed { i, e -> "<draw:equation draw:name=\"f$i\" draw:formula=\"${esc(e.replace(Regex("""\?(\d+)""")) { m -> "?f" + m.groupValues[1] }.replace("logwidth", "width").replace("logheight", "height"))}\"/>" }.joinToString("") +
                "</draw:enhanced-geometry>"
        }

        private val ODF_COMMANDS = mapOf('M' to 1, 'L' to 2, 'C' to 3, 'Z' to 4, 'N' to 5, 'F' to 6, 'S' to 7, 'T' to 8, 'U' to 9, 'A' to 10, 'B' to 11,
            'W' to 12, 'V' to 13, 'X' to 14, 'Y' to 15, 'Q' to 16, 'G' to 17, 'H' to 18, 'I' to 19, 'J' to 20, 'K' to 21)
        /** How many points each ODF command takes per repeat. */
        private val POINTS = mapOf(1 to 1, 2 to 1, 3 to 3, 8 to 3, 9 to 3, 10 to 4, 11 to 4, 12 to 4, 13 to 4, 14 to 1, 15 to 1, 16 to 2, 17 to 2)

        /**
         * A LibreOffice custom shape's own geometry (its draw:enhanced-geometry element's XML): its view box, modifiers,
         * equations and path. Null when it has no path (one of LibreOffice's built-in kinds, drawn by its name).
         */
        fun ofOdf(xml: String): ShapeGeometry? {
            val head = Regex("""<draw:enhanced-geometry\b[^>]*""").find(xml)?.value ?: return null
            fun attr(n: String) = Regex("""\s$n="([^"]*)"""").find(head)?.groupValues?.get(1)?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&quot;", "\"")?.replace("&amp;", "&")
            val path = attr("draw:enhanced-path") ?: return null
            val vb = attr("svg:viewBox")?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 4 }
            val mods = attr("draw:modifiers")?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toDoubleOrNull() }.orEmpty()
            val eqs = Regex("""<draw:equation\b[^>]*?draw:name="([^"]*)"[^>]*?draw:formula="([^"]*)"|<draw:equation\b[^>]*?draw:formula="([^"]*)"[^>]*?draw:name="([^"]*)"""").findAll(xml)
                .map { m -> if (m.groupValues[1].isNotEmpty()) m.groupValues[1] to m.groupValues[2] else m.groupValues[4] to m.groupValues[3] }.toList()
            // The path: commands and their numbers or "?f" / "$" references, counted per command.
            val tokens = Regex("""[A-Za-z](?![a-z])|\?\w+|\$\d+|-?\d*\.?\d+(?:[eE]-?\d+)?""").findAll(path).map { it.value }.toList()
            val coords = ArrayList<Pair<String, String>>(); val segs = ArrayList<Pair<Int, Int>>()
            var cmd = 1; var nums = ArrayList<String>()
            fun flush() {
                val per = POINTS[cmd] ?: 0
                if (per == 0) { segs += cmd to 0 } else if (nums.size >= 2) {
                    val pts = nums.chunked(2).filter { it.size == 2 }
                    pts.forEach { coords += it[0] to it[1] }
                    val n = pts.size / per
                    if (n > 0) segs += cmd to n
                    // Implicit repeats of M are lines (as SVG's).
                }
                nums = ArrayList()
            }
            var seen = false
            for (t in tokens) {
                val c = t.singleOrNull()?.takeIf { it.isLetter() }?.let { ODF_COMMANDS[it] }
                if (c != null) { if (seen) flush(); cmd = c; seen = true } else nums += t
            }
            if (seen) flush()
            // LibreOffice's sizes of each sub-path's own coordinates (Word's path w/h).
            val subs = attr("drawooo:sub-view-size")?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toDoubleOrNull() }?.chunked(2)?.filter { it.size == 2 }?.map { it[0] to it[1] }.orEmpty()
            val g = ShapeGeometry(mods.indices.map { "adj$it" }, mods, eqs.map { it.second }, coords, segs, subs,
                viewBox = vb?.let { it[2] to it[3] })
            g.equationNames = eqs.mapIndexed { i, (n, _) -> n to i }.toMap()
            return g
        }
    }
}
