package com.kalotrapezis.drive

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Excel's number formats (the user, 15 Oct: "you can get everything wrong with numbers") — a cell's number shown as
 * Excel shows it: General, 0.00, #,##0, percent, scientific, fractions, currency and dates/times, with the sections
 * (positive;negative;zero), [Red] colours and [>100] conditions. [decimal] and [group] are the phone's separators, as
 * Excel uses the computer's.
 */
internal class SheetFormat(
    private val decimal: Char = '.', private val group: Char = ',', private val date1904: Boolean = false,
    /** Month and day names when the format names no language ("d-mmm" shows "27-Σεπ" on a Greek phone, as Excel there). */
    private val language: Locale = Locale.getDefault(),
    /** LibreOffice's days (an .ods): from 1899-12-30 without Excel's made-up 29 February 1900, and before 1900 too. */
    private val calc: Boolean = false,
) {
    fun decimalSeparator() = decimal
    fun groupSeparator() = group

    /** A formatted number, and the colour the format asks for ([Red]), if any. */
    data class Shown(val text: String, val color: Int? = null)

    /** A format's section, read once: what is left to draw, its colour, locale, condition, and whether it is a date. */
    private class Section(val body: String, val color: Int?, val locale: String?, val condition: ((Double) -> Boolean)?, val date: Boolean, val general: Boolean)

    private val prepared = HashMap<String, List<Section>>()

    private fun sections(code: String): List<Section> = synchronized(prepared) { prepared.getOrPut(code) { split(code).map(::prepare) } }

    fun format(value: Double, code: String): Shown {
        if (value.isNaN() || value.isInfinite()) return Shown("#NUM!")
        val secs = sections(code)
        val (sec, absolute) = pick(secs, value)
        val v = if (absolute) abs(value) else value
        val text = when {
            sec.general -> general(v)
            sec.date -> date(value, sec.body, sec.locale)
            else -> number(v, sec.body, absolute)
        }
        return Shown(text, sec.color)
    }

    /** Brackets read: colours, conditions, currency and locale ([h] and its kind stay: the date code reads them). */
    private fun prepare(section: String): Section {
        var color: Int? = null
        var locale: String? = null
        val out = StringBuilder()
        var i = 0
        val s = section
        while (i < s.length) {
            val c = s[i]
            if (c == '"') { val end = s.indexOf('"', i + 1).let { if (it < 0) s.length else it }; out.append(s, i, minOf(end + 1, s.length)); i = end + 1; continue }
            if (c == '\\' && i + 1 < s.length) { out.append(s, i, i + 2); i += 2; continue }
            if (c == '[') {
                val end = s.indexOf(']', i).let { if (it < 0) s.length - 1 else it }
                val inner = s.substring(i + 1, end)
                val low = inner.lowercase(Locale.ROOT)
                when {
                    ELAPSED.matches(low) -> out.append('[').append(inner).append(']')
                    low in COLORS -> color = COLORS.getValue(low)
                    low.startsWith("color") -> color = low.removePrefix("color").toIntOrNull()?.let { SheetColors.indexed(it - 1 + 8) }
                    inner.startsWith("$") -> {
                        val symbol = inner.substring(1).substringBefore('-')
                        locale = inner.substringAfter('-', "").lowercase(Locale.ROOT)
                        if (symbol.isNotEmpty()) out.append('"').append(symbol.replace("\"", "")).append('"')
                    }
                    else -> Unit // a condition: [condition] reads it
                }
                i = end + 1; continue
            }
            out.append(c); i++
        }
        var body = out.toString()
        // LibreOffice's and Excel's system long date and time.
        if (locale == "f800") body = "dddd, mmmm d, yyyy"
        if (locale == "f400") body = "h:mm:ss AM/PM"
        val general = body.equals("General", ignoreCase = true)
        val stripped = strip(body)
        val date = !general && (stripped.any { it in "dmyhsDMYHS" } || stripped.contains("AM/PM", ignoreCase = true) || stripped.contains("A/P", ignoreCase = true))
        return Section(body, color, locale, condition(section), date, general)
    }

    /** Whether [code] (its first section) shows a date or a time. */
    fun isDate(code: String): Boolean = sections(code).first().date

    /** The section for [value], and whether its sign is already in the section (so the number goes without one). */
    private fun pick(sections: List<Section>, value: Double): Pair<Section, Boolean> {
        if (sections.any { it.condition != null }) {
            sections.forEach { sec -> sec.condition?.let { if (it(value)) return sec to false } }
            // The section with no condition after the conditional ones takes the rest.
            return (sections.firstOrNull { it.condition == null } ?: sections.last()) to false
        }
        // One empty section is General; an empty one among several shows nothing.
        if (sections.size == 1 && sections[0].body.isBlank()) return Section("General", sections[0].color, null, null, false, true) to false
        return when {
            sections.size == 1 -> sections[0] to false
            value > 0 || (value == 0.0 && sections.size == 2) -> sections[0] to false
            value < 0 -> sections[1] to true
            else -> sections[2] to false
        }
    }

    private fun condition(section: String): ((Double) -> Boolean)? {
        val m = CONDITION.find(section) ?: return null
        val n = m.groupValues[2].toDoubleOrNull() ?: return null
        return when (m.groupValues[1]) {
            "<" -> { v -> v < n }; ">" -> { v -> v > n }; "<=" -> { v -> v <= n }; ">=" -> { v -> v >= n }
            "=" -> { v -> v == n }; else -> { v -> v != n }
        }
    }

    /** General: as many digits as fit an ordinary column (11 letters), then scientific. */
    fun general(value: Double): String {
        if (value == 0.0) return "0"
        val a = abs(value)
        if (a >= 1e11 || a < 1e-9) {
            val exp = floor(log10(a)).toInt()
            var mant = BigDecimal(value / 10.0.pow(exp)).setScale(5, RoundingMode.HALF_UP)
            var e = exp
            if (mant.abs() >= BigDecimal.TEN) { mant = mant.divide(BigDecimal.TEN).setScale(5, RoundingMode.HALF_UP); e++ }
            val m = mant.stripTrailingZeros().toPlainString().replace('.', decimal)
            return m + "E" + (if (e < 0) "-" else "+") + abs(e).toString().padStart(2, '0')
        }
        // Excel's 15 significant digits, then only as many decimals as fit 11 letters.
        var bd = BigDecimal(value).round(MathContext(15, RoundingMode.HALF_UP)).stripTrailingZeros()
        val intDigits = bd.abs().toBigInteger().toString().length
        if (bd.scale() > 0 && intDigits + bd.scale() + 1 > 11) bd = bd.setScale(maxOf(0, 10 - intDigits), RoundingMode.HALF_UP).stripTrailingZeros()
        return bd.toPlainString().replace('.', decimal)
    }

    /**
     * General in a column [chars] digits wide, as Excel shows it there: fewer decimals until it fits, then scientific
     * with fewer digits; null when even that does not fit (Excel's ####).
     */
    fun general(value: Double, chars: Int): String? {
        val full = general(value)
        if (full.length <= chars) return full
        if (chars <= 0) return null
        val a = abs(value)
        val sign = if (value < 0) 1 else 0
        if (a >= 1e-4 && a < 1e11) {
            val intDigits = BigDecimal(a).toBigInteger().toString().length
            val decimals = chars - sign - intDigits - 1
            if (decimals >= 1 || chars - sign >= intDigits) {
                val s = BigDecimal(value).setScale(max(0, decimals), RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', decimal)
                if (s.length <= chars && s.trim('-', '0', decimal).isNotEmpty()) return s
            }
        }
        // 1.2E+10: the mantissa's decimals as room allows
        if (value == 0.0) return "0"
        var exp = floor(log10(a)).toInt()
        val tail = if (abs(exp) >= 100) 5 else 4 // E+12
        val decimals = chars - sign - 1 - tail - 1
        if (decimals < -1) return null
        var mant = BigDecimal(value / 10.0.pow(exp)).setScale(max(0, decimals), RoundingMode.HALF_UP)
        if (mant.abs() >= BigDecimal.TEN) { exp++; mant = BigDecimal(value / 10.0.pow(exp)).setScale(max(0, decimals), RoundingMode.HALF_UP) }
        val s = mant.stripTrailingZeros().toPlainString().replace('.', decimal) + "E" + (if (exp < 0) "-" else "+") + abs(exp).toString().padStart(2, '0')
        return s.takeIf { it.length <= chars }
    }

    // --- numbers ---

    private sealed interface Tok {
        data class Digit(val c: Char) : Tok
        data object Point : Tok
        data object Comma : Tok
        data object Percent : Tok
        data class Exp(val plus: Boolean) : Tok
        data object Slash : Tok
        data class Lit(val s: String) : Tok
        data object At : Tok
    }

    private fun tokens(s: String): List<Tok> {
        val t = ArrayList<Tok>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> { val end = s.indexOf('"', i + 1).let { if (it < 0) s.length else it }; t += Tok.Lit(s.substring(i + 1, end)); i = end + 1; continue }
                c == '\\' && i + 1 < s.length -> { t += Tok.Lit(s[i + 1].toString()); i += 2; continue }
                c == '_' && i + 1 < s.length -> { t += Tok.Lit(" "); i += 2; continue } // the width of the next letter
                c == '*' && i + 1 < s.length -> { i += 2; continue } // fill to the cell's width: not drawn
                c == '0' || c == '#' || c == '?' -> t += Tok.Digit(c)
                c == '.' -> t += Tok.Point
                c == ',' -> t += Tok.Comma
                c == '%' -> t += Tok.Percent
                c == '/' -> t += Tok.Slash
                c == '@' -> t += Tok.At
                (c == 'E' || c == 'e') && i + 1 < s.length && (s[i + 1] == '+' || s[i + 1] == '-') -> { t += Tok.Exp(s[i + 1] == '+'); i += 2; continue }
                else -> t += Tok.Lit(c.toString())
            }
            i++
        }
        return t
    }

    private fun number(value: Double, code: String, signInSection: Boolean): String {
        val toks = tokens(code)
        if (toks.none { it is Tok.Digit }) {
            // Only text ("-", "Zero"): the literals, and General where a number would be.
            return toks.joinToString("") { when (it) { is Tok.Lit -> it.s; Tok.Percent -> "%"; Tok.At -> general(value); else -> "" } }
        }
        var v = value
        repeat(toks.count { it == Tok.Percent }) { v *= 100 }
        if (toks.any { it == Tok.Slash } ) fraction(v, toks)?.let { return it }
        val expAt = toks.indexOfFirst { it is Tok.Exp }
        if (expAt >= 0) return scientific(v, toks, expAt)
        val point = toks.indexOfFirst { it == Tok.Point }
        val intEnd = if (point >= 0) point else toks.size
        val lastIntDigit = (0 until intEnd).lastOrNull { toks[it] is Tok.Digit } ?: -1
        val firstIntDigit = (0 until intEnd).firstOrNull { toks[it] is Tok.Digit } ?: -1
        // Commas after the last digit (before the point) divide by a thousand each; between digits they group.
        var scale = 0
        if (lastIntDigit >= 0) { var k = lastIntDigit + 1; while (k < intEnd && toks[k] == Tok.Comma) { scale++; k++ } }
        repeat(scale) { v /= 1000 }
        val grouping = firstIntDigit >= 0 && (firstIntDigit until lastIntDigit).any { toks[it] == Tok.Comma }
        val decimals = if (point >= 0) toks.drop(point + 1).filterIsInstance<Tok.Digit>() else emptyList()
        val rounded = BigDecimal(abs(v).toString()).setScale(decimals.size, RoundingMode.HALF_UP)
        val negative = v < 0 && rounded.signum() != 0 && !signInSection
        val intText = rounded.toBigInteger().toString()
        val intDigits = (0 until intEnd).map { toks[it] }.filterIsInstance<Tok.Digit>()
        // The integer: at least as many digits as there are 0s, ? as spaces, # as nothing.
        val intShown = buildString {
            val digits = if (intText == "0" && intDigits.none { it.c == '0' }) "" else intText
            val padded = StringBuilder(digits)
            var k = intDigits.size - digits.length - 1
            while (k >= 0) { when (intDigits[k].c) { '0' -> padded.insert(0, '0'); '?' -> padded.insert(0, ' ') }; k-- }
            append(if (grouping) group(padded.toString()) else padded)
        }
        val decShown = run {
            val frac = rounded.toPlainString().substringAfter('.', "").padEnd(decimals.size, '0')
            val chars = frac.toCharArray()
            var k = decimals.size - 1
            while (k >= 0 && chars[k] == '0' && decimals[k].c != '0') { chars[k] = if (decimals[k].c == '?') ' ' else '\u0000'; k-- }
            String(chars).replace("\u0000", "")
        }
        return buildString {
            if (negative) append('-')
            var intDone = false; var decDone = false
            toks.forEachIndexed { k, t ->
                when (t) {
                    is Tok.Digit -> if (k < intEnd) { if (!intDone) { append(intShown); intDone = true } } else if (!decDone) { append(decShown); decDone = true }
                    Tok.Point -> append(decimal)
                    Tok.Comma -> Unit
                    Tok.Percent -> append('%')
                    is Tok.Lit -> append(t.s)
                    Tok.At -> Unit
                    Tok.Slash -> append('/')
                    is Tok.Exp -> Unit
                }
            }
        }
    }

    /** "1,234,567" with the phone's separator (the digits may carry leading spaces from ?). */
    private fun group(s: String): String {
        val lead = s.takeWhile { it == ' ' }
        val digits = s.drop(lead.length)
        val out = StringBuilder()
        digits.forEachIndexed { k, c -> if (k > 0 && (digits.length - k) % 3 == 0) out.append(group); out.append(c) }
        return lead + out
    }

    private fun scientific(v: Double, toks: List<Tok>, expAt: Int): String {
        val mantToks = toks.subList(0, expAt)
        val point = mantToks.indexOfFirst { it == Tok.Point }
        val intCount = mantToks.subList(0, if (point >= 0) point else mantToks.size).count { it is Tok.Digit }
        val decCount = if (point >= 0) mantToks.drop(point + 1).count { it is Tok.Digit } else 0
        val expDigits = toks.drop(expAt + 1).count { it is Tok.Digit }.coerceAtLeast(1)
        val plus = (toks[expAt] as Tok.Exp).plus
        if (v == 0.0) {
            val m = "0" + if (decCount > 0) decimal + "0".repeat(decCount) else ""
            return m + "E" + (if (plus) "+" else "") + "0".repeat(expDigits)
        }
        var exp = floor(log10(abs(v))).toInt()
        // ##0.0E+0: engineering — the exponent a multiple of the integer digits.
        if (intCount > 1 && mantToks.any { it is Tok.Digit && it.c == '#' }) exp = Math.floorDiv(exp, intCount) * intCount
        var mant = BigDecimal(abs(v) / 10.0.pow(exp)).setScale(decCount, RoundingMode.HALF_UP)
        if (intCount <= 1 && mant >= BigDecimal.TEN) { exp++; mant = BigDecimal(abs(v) / 10.0.pow(exp)).setScale(decCount, RoundingMode.HALF_UP) }
        val m = mant.toPlainString().replace('.', decimal)
        val sign = if (exp < 0) "-" else if (plus) "+" else ""
        val lits = toks.drop(expAt + 1).filterIsInstance<Tok.Lit>().joinToString("") { it.s }
        val prefix = mantToks.takeWhile { it is Tok.Lit }.joinToString("") { (it as Tok.Lit).s }
        return (if (v < 0) "-" else "") + prefix + m + "E" + sign + abs(exp).toString().padStart(expDigits, '0') + lits
    }

    /** "# ?/?" and its kind: a whole part (when there is a placeholder before a space), then the closest fraction. */
    private fun fraction(v: Double, toks: List<Tok>): String? {
        val slash = toks.indexOfFirst { it == Tok.Slash }
        val denomToks = toks.drop(slash + 1).takeWhile { it is Tok.Digit || (it is Tok.Lit && it.s.all(Char::isDigit)) }
        val fixed = denomToks.filterIsInstance<Tok.Lit>().joinToString("") { it.s }.toIntOrNull()
        val denomDigits = denomToks.count { it is Tok.Digit }.coerceAtLeast(1)
        val before = toks.subList(0, slash)
        // The numerator's placeholders are the run of digits just before the slash; a whole part comes before a space.
        var k = before.size - 1
        while (k >= 0 && before[k] is Tok.Digit) k--
        val hasWhole = k >= 0 && before.subList(0, k + 1).any { it is Tok.Digit }
        val a = abs(v)
        val whole = if (hasWhole) floor(a).toLong() else 0L
        val rest = a - whole
        var (num, den) = if (fixed != null) (rest * fixed).roundToLong() to fixed.toLong() else best(rest, 10.0.pow(denomDigits).toLong() - 1)
        var w = whole
        if (den > 0 && num == den && hasWhole) { w++; num = 0 }
        val sign = if (v < 0) "-" else ""
        // ? holds a place with a figure space, as LibreOffice does (Excel a space), so fractions line up: "3  3/4 ", "12    "
        val numDigits = before.size - 1 - k
        val fs = '\u2007' // a figure space: a digit's width
        val shownDen = if (fixed != null) den.toString() else den.toString().padEnd(denomDigits, fs)
        val frac = if (num == 0L) fs.toString().repeat(numDigits) + " " + fs.toString().repeat(if (fixed != null) fixed.toString().length else denomDigits)
            else num.toString().padStart(numDigits, fs) + "/" + shownDen
        return when {
            hasWhole && w == 0L && num == 0L -> sign + "0 " + frac
            hasWhole && w == 0L -> sign + " " + frac
            hasWhole -> sign + w + " " + frac
            else -> sign + if (num == 0L) "0" else "$num/$den"
        }
    }

    private fun best(x: Double, maxDen: Long): Pair<Long, Long> {
        var bestN = 0L; var bestD = 1L; var err = Double.MAX_VALUE
        for (d in 1..maxDen) { val n = (x * d).roundToLong(); val e = abs(x - n.toDouble() / d); if (e < err - 1e-12) { err = e; bestN = n; bestD = d } }
        return bestN to bestD
    }

    // --- dates ---

    /** Greek short months as Excel and LibreOffice write them (no accents: Μαϊ, Αυγ; the phone's own have them). */
    private val GREEK_MONTHS = listOf("Ιαν", "Φεβ", "Μαρ", "Απρ", "Μαϊ", "Ιουν", "Ιουλ", "Αυγ", "Σεπ", "Οκτ", "Νοε", "Δεκ")

    /** The date and time of an Excel serial number (days since 1899-12-30; 1900's made-up 29 February respected). */
    fun dateTime(serial: Double): LocalDateTime {
        val day = floor(serial).toLong()
        val base = when {
            date1904 -> LocalDate.of(1904, 1, 1)
            day < 60 && !calc -> LocalDate.of(1899, 12, 31)
            else -> LocalDate.of(1899, 12, 30)
        }
        val ms = ((serial - day) * 86_400_000).roundToLong()
        return base.plusDays(day).atStartOfDay().plusNanos(ms * 1_000_000)
    }

    private fun date(value: Double, code: String, locale: String?): String {
        if (value < 0 && !calc) return "#".repeat(8)
        // [$-409] and its kind name the language; else the phone's.
        val names = when (locale?.removePrefix("x-")) {
            null, "", "f800", "f400", "sysdate", "systime" -> language
            "408", "el", "el-gr" -> Locale.forLanguageTag("el")
            "409", "809", "c09", "1009", "en", "en-us", "en-gb" -> Locale.ENGLISH
            "407", "de", "de-de" -> Locale.GERMAN
            "40c", "fr", "fr-fr" -> Locale.FRENCH
            "410", "it", "it-it" -> Locale.ITALIAN
            "40a", "c0a", "es", "es-es" -> Locale.forLanguageTag("es")
            else -> locale.toIntOrNull(16)?.let { language } ?: runCatching { Locale.forLanguageTag(locale) }.getOrDefault(language)
        }
        val greek = names.language == "el"
        // Tokens, so "m" can be read as minutes after an hour or before seconds.
        data class T(val kind: Char, val n: Int, val lit: String = "")
        val t = ArrayList<T>()
        var i = 0
        val s = code
        var ampm: String? = null
        var fracDigits = 0
        while (i < s.length) {
            val c = s[i]; val low = c.lowercaseChar()
            when {
                c == '"' -> { val end = s.indexOf('"', i + 1).let { if (it < 0) s.length else it }; t += T('L', 0, s.substring(i + 1, end)); i = end + 1 }
                c == '\\' && i + 1 < s.length -> { t += T('L', 0, s[i + 1].toString()); i += 2 }
                c == '_' && i + 1 < s.length -> { t += T('L', 0, " "); i += 2 }
                c == '*' && i + 1 < s.length -> i += 2
                c == '[' -> { val end = s.indexOf(']', i); val inner = s.substring(i + 1, end).lowercase(Locale.ROOT); t += T(inner[0].uppercaseChar(), inner.length); i = end + 1 }
                s.regionMatches(i, "AM/PM", 0, 5, ignoreCase = true) -> { ampm = s.substring(i, i + 5); t += T('a', 5); i += 5 }
                s.regionMatches(i, "A/P", 0, 3, ignoreCase = true) -> { ampm = s.substring(i, i + 3); t += T('a', 3); i += 3 }
                low in "ymdhs" -> { var n = 0; while (i < s.length && s[i].lowercaseChar() == low) { n++; i++ }; t += T(low, n) }
                c == '.' && t.lastOrNull()?.kind == 's' && i + 1 < s.length && s[i + 1] == '0' -> {
                    var n = 0; i++; while (i < s.length && s[i] == '0') { n++; i++ }; fracDigits = n; t += T('f', n)
                }
                low == 'e' -> { t += T('y', 4); i++ } // e: the year (Excel, East Asian calendars aside)
                low == 'g' || low == 'b' -> i++
                else -> { t += T('L', 0, c.toString()); i++ }
            }
        }
        // m → minutes after an h, or before an s.
        val kinds = t.map { it.kind }
        val minute = BooleanArray(t.size)
        t.forEachIndexed { k, tok ->
            if (tok.kind != 'm' || tok.n > 2) return@forEachIndexed
            val prev = (k - 1 downTo 0).firstOrNull { kinds[it] != 'L' }?.let { kinds[it] }
            val next = (k + 1 until t.size).firstOrNull { kinds[it] != 'L' }?.let { kinds[it] }
            if (prev == 'h' || prev == 'H' || next == 's' || next == 'S') minute[k] = true
        }
        // Rounded to the shown precision (a whole second unless fractions are asked).
        // Seconds cut, not rounded, past what is shown (23:23:23.76 is 23:23:23, as Excel and LibreOffice show it) —
        // after a rounding to the millisecond, so a time stored as 11:59:59.9999… is still 12:00:00.
        val unit = 86_400.0 * 10.0.pow(fracDigits)
        val v = floor(kotlin.math.round(value * 86_400_000.0) / 86_400_000.0 * unit + 1e-9) / unit
        val dt = dateTime(v)
        val totalSeconds = (v * 86_400).let { floor(it * 10.0.pow(fracDigits) + 1e-6) / 10.0.pow(fracDigits) }
        return buildString {
            t.forEachIndexed { k, tok ->
                when (tok.kind) {
                    'L' -> append(tok.lit)
                    'y' -> append(if (tok.n <= 2) (dt.year % 100).toString().padStart(2, '0') else dt.year.toString())
                    'm' -> if (minute[k]) append(dt.minute.toString().padStart(tok.n, '0')) else append(when (tok.n) {
                        1, 2 -> dt.monthValue.toString().padStart(tok.n, '0')
                        3 -> if (greek) GREEK_MONTHS[dt.monthValue - 1] else dt.month.getDisplayName(TextStyle.SHORT_STANDALONE, names).trimEnd('.')
                        5 -> dt.month.getDisplayName(TextStyle.FULL_STANDALONE, names).take(1)
                        else -> dt.month.getDisplayName(if (kinds.contains('d') && greek) TextStyle.FULL else TextStyle.FULL_STANDALONE, names)
                    })
                    'd' -> append(when (tok.n) {
                        1, 2 -> dt.dayOfMonth.toString().padStart(tok.n, '0')
                        3 -> dt.dayOfWeek.getDisplayName(TextStyle.SHORT, names).trimEnd('.')
                        else -> dt.dayOfWeek.getDisplayName(TextStyle.FULL, names)
                    })
                    'h' -> append((if (ampm != null) (dt.hour % 12).let { if (it == 0) 12 else it } else dt.hour).toString().padStart(minOf(tok.n, 2), '0'))
                    's' -> append(dt.second.toString().padStart(minOf(tok.n, 2), '0'))
                    'f' -> append(decimal).append(dt.nano.toString().padStart(9, '0').take(tok.n))
                    'H' -> append(floor(totalSeconds / 3600).toLong().toString().padStart(tok.n, '0'))
                    'M' -> append(floor(totalSeconds / 60).toLong().toString().padStart(tok.n, '0'))
                    'S' -> append(floor(totalSeconds).toLong().toString().padStart(tok.n, '0'))
                    'a' -> {
                        val pm = dt.hour >= 12
                        val word = ampm!!
                        // the language's own words (Greek ΠΜ, ΜΜ, as LibreOffice and Excel there), in the format's case
                        val said = java.text.DateFormatSymbols.getInstance(names).amPmStrings[if (pm) 1 else 0].replace(".", "").uppercase(names)
                        append(if (tok.n == 5) said.let { if (word[0].isLowerCase()) it.lowercase(names) else it } else (if (pm) word[2] else word[0]))
                    }
                }
            }
        }
    }

    companion object {
        /** Excel's built-in formats by id (5–8, its own currency, as #,##0.00; 14 and 22, the short date, day first). */
        val BUILT_IN = mapOf(
            0 to "General", 1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00", 5 to "#,##0", 6 to "#,##0;[Red]-#,##0",
            7 to "#,##0.00", 8 to "#,##0.00;[Red]-#,##0.00", 9 to "0%", 10 to "0.00%", 11 to "0.00E+00", 12 to "# ?/?",
            13 to "# ??/??", 14 to "d/m/yyyy", 15 to "d-mmm-yy", 16 to "d-mmm", 17 to "mmm-yy", 18 to "h:mm AM/PM",
            19 to "h:mm:ss AM/PM", 20 to "h:mm", 21 to "h:mm:ss", 22 to "d/m/yyyy h:mm", 37 to "#,##0 ;(#,##0)",
            38 to "#,##0 ;[Red](#,##0)", 39 to "#,##0.00;(#,##0.00)", 40 to "#,##0.00;[Red](#,##0.00)", 45 to "mm:ss",
            46 to "[h]:mm:ss", 47 to "mm:ss.0", 48 to "##0.0E+0", 49 to "@",
        )

        private val ELAPSED = Regex("h+|m+|s+")
        private val CONDITION = Regex("""\[(<=|>=|<>|<|>|=)(-?[0-9.]+)]""")
        private val STRIP = Regex("\"[^\"]*\"|\\\\.|_.|\\*.|\\[(?![hms]+])[^]]*]")

        private val COLORS = mapOf(
            "black" to 0xFF000000.toInt(), "blue" to 0xFF0000FF.toInt(), "cyan" to 0xFF00FFFF.toInt(), "green" to 0xFF00FF00.toInt(),
            "magenta" to 0xFFFF00FF.toInt(), "red" to 0xFFFF0000.toInt(), "white" to 0xFFFFFFFF.toInt(), "yellow" to 0xFFFFFF00.toInt(),
        )

        /** A format's sections, split at the semicolons that are not in quotes or brackets. */
        fun split(code: String): List<String> {
            val out = ArrayList<String>()
            var start = 0; var i = 0; var quote = false; var bracket = false
            while (i < code.length) {
                val c = code[i]
                when {
                    c == '\\' && !quote -> i++
                    c == '"' -> quote = !quote
                    c == '[' && !quote -> bracket = true
                    c == ']' && !quote -> bracket = false
                    c == ';' && !quote && !bracket -> { out += code.substring(start, i); start = i + 1 }
                }
                i++
            }
            out += code.substring(start)
            return out
        }

        /** A section without its quoted words, escapes and brackets — what is left says date or number. */
        fun strip(section: String): String = section.replace(STRIP, "")
    }
}
