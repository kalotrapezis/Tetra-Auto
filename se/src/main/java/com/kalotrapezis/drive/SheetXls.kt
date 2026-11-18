package com.kalotrapezis.drive

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * Excel 97–2003 workbooks (.xls, asked 16 Oct: everything on the list in place): the compound file's "Workbook" stream,
 * its BIFF8 records — sheets, the shared strings, numbers (RK too), formulas' results, fonts and number formats, merges,
 * column widths, row heights and frozen panes. Shown, not saved: an .xls opens to read (Export PDF, another app).
 */
// shortcut: a formula shows its result only (its tokens are not turned back into =text); read them if .xls files get edited
internal object SheetXls {
    fun isXls(bytes: ByteArray) = bytes.size > 512 && ByteBuffer.wrap(bytes, 0, 8).long == 0xD0CF11E0A1B11AE1uL.toLong()

    /** The compound file's stream called [name] ("Workbook", or "Book" in older files). */
    fun stream(bytes: ByteArray, vararg name: String): ByteArray? {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val size = 1 shl b.getShort(0x1E).toInt()
        val mini = 1 shl b.getShort(0x20).toInt()
        val fatCount = b.getInt(0x2C); val dirStart = b.getInt(0x30); val cutoff = b.getInt(0x38)
        val miniFatStart = b.getInt(0x3C); var difatNext = b.getInt(0x44)
        fun at(sector: Int) = (sector + 1) * size
        // the FAT's sectors: 109 in the header, the rest in the DIFAT chain
        val fatSectors = ArrayList<Int>()
        for (i in 0 until 109) { val s = b.getInt(0x4C + i * 4); if (s >= 0) fatSectors += s }
        var guard = 0
        while (difatNext >= 0 && fatSectors.size < fatCount && guard++ < 10_000) {
            val base = at(difatNext)
            for (i in 0 until size / 4 - 1) { val s = b.getInt(base + i * 4); if (s >= 0) fatSectors += s }
            difatNext = b.getInt(base + size - 4)
        }
        val fat = IntArray(fatSectors.size * size / 4) { i -> b.getInt(at(fatSectors[i / (size / 4)]) + (i % (size / 4)) * 4) }
        fun chain(start: Int, table: IntArray): List<Int> { val out = ArrayList<Int>(); var s = start; while (s >= 0 && s < table.size && out.size < table.size) { out += s; s = table[s] }; return out }
        fun read(start: Int, length: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream(length)
            for (s in chain(start, fat)) out.write(bytes, at(s), minOf(size, bytes.size - at(s)))
            return out.toByteArray().copyOf(length)
        }
        // the directory: 128 bytes an entry
        val dir = chain(dirStart, fat).flatMap { s -> (0 until size / 128).map { at(s) + it * 128 } }
        fun nameOf(e: Int): String { val n = b.getShort(e + 0x40).toInt(); return String(bytes, e, maxOf(0, n - 2), Charsets.UTF_16LE) }
        val root = dir.firstOrNull { bytes[it + 0x42].toInt() == 5 } ?: return null
        val entry = dir.firstOrNull { bytes[it + 0x42].toInt() == 2 && nameOf(it) in name } ?: return null
        val start = b.getInt(entry + 0x74); val length = b.getInt(entry + 0x78)
        if (length >= cutoff) return read(start, length)
        // a small stream lives in the mini stream (the root's), its own sectors in the mini FAT
        val miniStream = read(b.getInt(root + 0x74), b.getInt(root + 0x78))
        val miniFat = read(miniFatStart, chain(miniFatStart, fat).size * size).let { m -> ByteBuffer.wrap(m).order(ByteOrder.LITTLE_ENDIAN).let { mb -> IntArray(m.size / 4) { mb.getInt(it * 4) } } }
        val out = java.io.ByteArrayOutputStream(length)
        for (s in chain(start, miniFat)) out.write(miniStream, s * mini, minOf(mini, miniStream.size - s * mini))
        return out.toByteArray().copyOf(length)
    }

    private class Rec(val type: Int, val data: ByteBuffer)

    /** The BIFF records of a stream from [from], CONTINUE records kept apart (the shared strings read across them). */
    private fun records(w: ByteArray, from: Int = 0): Sequence<Rec> = sequence {
        var p = from
        while (p + 4 <= w.size) {
            val type = (w[p].toInt() and 255) or ((w[p + 1].toInt() and 255) shl 8)
            val len = (w[p + 2].toInt() and 255) or ((w[p + 3].toInt() and 255) shl 8)
            if (p + 4 + len > w.size) break
            yield(Rec(type, ByteBuffer.wrap(w, p + 4, len).slice().order(ByteOrder.LITTLE_ENDIAN)))
            p += 4 + len
            if (type == 0x000A && from > 0) break // a sheet's EOF
        }
    }

    /** An RK number: 30 bits of a double's top or an integer, maybe ÷100. */
    private fun rk(v: Int): Double {
        val n = if (v and 2 != 0) (v shr 2).toDouble() else java.lang.Double.longBitsToDouble((v.toLong() and 0xFFFFFFFCL) shl 32)
        return if (v and 1 != 0) n / 100 else n
    }

    /** XLUnicodeString's characters: [n] of them, one byte each or two ([wide]). */
    private fun chars(d: ByteBuffer, n: Int, wide: Boolean): String {
        val sb = StringBuilder(n)
        repeat(n) { if (d.remaining() < (if (wide) 2 else 1)) return sb.toString(); sb.append(if (wide) d.short.toInt().toChar() else (d.get().toInt() and 255).toChar()) }
        return sb.toString()
    }

    private fun shortString(d: ByteBuffer): String { val n = d.get().toInt() and 255; val f = d.get().toInt(); return chars(d, n, f and 1 != 0) }
    private fun longString(d: ByteBuffer): String { val n = d.short.toInt() and 0xFFFF; val f = d.get().toInt(); return chars(d, n, f and 1 != 0) }

    /** The shared strings: their characters may run on into CONTINUE records, which start with their own width flag. */
    private fun sst(parts: List<ByteBuffer>): List<String> {
        val out = ArrayList<String>()
        var part = 0; var d = parts[0]
        d.position(8)
        val unique = parts[0].getInt(4)
        fun next(): Boolean { part++; if (part >= parts.size) return false; d = parts[part]; return true }
        fun ensure(n: Int): Boolean { while (d.remaining() < n) { if (d.remaining() > 0 || !next()) return d.remaining() >= n }; return true }
        repeat(unique) {
            if (!ensure(3)) return out
            val n = d.short.toInt() and 0xFFFF; val f = d.get().toInt()
            var wide = f and 1 != 0
            val runs = if (f and 8 != 0) d.short.toInt() and 0xFFFF else 0
            val ext = if (f and 4 != 0) d.int else 0
            val sb = StringBuilder(n)
            while (sb.length < n) {
                if (d.remaining() == 0) { if (!next()) return out; wide = d.get().toInt() and 1 != 0 }
                sb.append(if (wide) d.short.toInt().toChar() else (d.get().toInt() and 255).toChar())
            }
            // the rich text runs and the phonetic block after it: skipped, across records as they fall
            var skip = runs * 4 + ext
            while (skip > 0) { if (d.remaining() == 0 && !next()) return out; val k = minOf(skip, d.remaining()); d.position(d.position() + k); skip -= k }
            out += sb.toString()
        }
        return out
    }

    /** Excel's colour palette (BIFF8's default; a file's PALETTE record replaces 8–63). */
    private val PALETTE = intArrayOf(0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF, 0x800000, 0x008000, 0x000080, 0x808000,
        0x800080, 0x008080, 0xC0C0C0, 0x808080, 0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF,
        0x000080, 0xFF00FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x800000, 0x008080, 0x0000FF, 0x00CCFF, 0xCCFFFF, 0xCCFFCC, 0xFFFF99,
        0x99CCFF, 0xFF99CC, 0xCC99FF, 0xFFCC99, 0x3366FF, 0x33CCCC, 0x99CC00, 0xFFCC00, 0xFF9900, 0xFF6600, 0x666699, 0x969696,
        0x003366, 0x339966, 0x003300, 0x333300, 0x993300, 0x993366, 0x333399, 0x333333)

    private class Font(val name: String, val size: Float, val bold: Boolean, val italic: Boolean, val underline: Boolean, val strike: Boolean, val color: Int)

    fun read(bytes: ByteArray, decimal: Char = '.', group: Char = ','): Workbook {
        val w = stream(bytes, "Workbook", "Book") ?: sheetProblem(R.string.tables_err_not_excel)
        val sheets = ArrayList<Triple<String, Int, Boolean>>() // name, its BOF's place, hidden
        val sstParts = ArrayList<ByteBuffer>()
        val fonts = ArrayList<Font>(); val formats = HashMap<Int, String>(); val xfs = ArrayList<Pair<Int, Int>>() // font, format
        val aligns = ArrayList<Int>(); val fills = ArrayList<Int?>()
        val palette = PALETTE.copyOf()
        var date1904 = false
        var inSst = false
        for (r in records(w)) {
            val d = r.data
            if (r.type != 0x003C) inSst = false
            when (r.type) {
                0x0085 -> { val pos = d.int; val hidden = d.get().toInt() != 0; val type = d.get().toInt(); if (type == 0) sheets += Triple(shortString(d), pos, hidden) }
                0x00FC -> { sstParts += d; inSst = true }
                0x003C -> if (inSst) sstParts += d
                0x0022 -> date1904 = d.short.toInt() == 1
                0x0092 -> { val n = d.short.toInt(); for (i in 0 until n) if (d.remaining() >= 4) { val c = d.int; palette[8 + i] = ((c and 255) shl 16) or (c and 0xFF00) or ((c shr 16) and 255) } }
                0x0031 -> {
                    val h = d.short.toInt() and 0xFFFF; val grbit = d.short.toInt(); val icv = d.short.toInt() and 0xFFFF; val weight = d.short.toInt() and 0xFFFF
                    d.short; val u = d.get().toInt(); d.get(); d.get(); d.get()
                    fonts += Font(shortString(d), h / 20f, weight >= 700, grbit and 2 != 0, u != 0, grbit and 8 != 0, icv)
                    if (fonts.size == 4) fonts += fonts[0] // BIFF has no font 4: the numbers skip it
                }
                0x041E -> { val i = d.short.toInt() and 0xFFFF; formats[i] = longString(d) }
                0x00E0 -> {
                    // BIFF8's XF: font, format, kind, alignment, rotation, indent, used, borders (4), their colours and the fill's pattern (4), its colours (2)
                    val font = d.short.toInt() and 0xFFFF; val fmt = d.short.toInt() and 0xFFFF; d.short
                    val align = d.get().toInt() and 255; d.get(); d.get(); d.get(); d.int
                    val pattern = if (d.remaining() >= 4) d.int ushr 26 else 0
                    val fore = if (d.remaining() >= 2) d.short.toInt() and 0x7F else 64
                    xfs += font to fmt; aligns += align
                    fills += fore.takeIf { pattern != 0 && it < 64 }?.let { palette[it] or 0xFF000000.toInt() }
                }
            }
        }
        val strings = if (sstParts.isEmpty()) emptyList() else sst(sstParts)
        val format = SheetFormat(decimal, group, date1904)
        // the cells' looks: an XF each — its font, number format, alignment, fill
        val styles = xfs.mapIndexed { i, (fi, fmt) ->
            val f = fonts.getOrNull(fi)
            val code = formats[fmt] ?: SheetFormat.BUILT_IN[fmt] ?: "General"
            SheetStyle(font = f?.name, size = f?.size ?: 10f, bold = f?.bold == true, italic = f?.italic == true, underline = f?.underline == true, strike = f?.strike == true,
                color = f?.color?.takeIf { it in 8..63 && it != 8 }?.let { palette[it] or 0xFF000000.toInt() }, fill = fills.getOrNull(i),
                align = when (aligns.getOrNull(i)?.and(7)) { 1 -> SheetAlign.Left; 2 -> SheetAlign.Center; 3 -> SheetAlign.Right; else -> SheetAlign.General },
                wrap = (aligns.getOrNull(i) ?: 0) and 8 != 0, format = code)
        }.toMutableList().ifEmpty { mutableListOf(SheetStyle()) }
        fun sheet(i: Int): Sheet = sheetAt(w, sheets[i], strings, styles, format)
        return Workbook(sheets.map { SheetTab(it.first, it.third) }, styles, kind = "xls", format = format) { i -> sheet(i) }
    }

    private fun sheetAt(w: ByteArray, at: Triple<String, Int, Boolean>, strings: List<String>, styles: List<SheetStyle>, format: SheetFormat): Sheet {
        val cells = CellMap()
        val widths = HashMap<Int, Float>(); val heights = HashMap<Int, Float>(); val hiddenCols = HashSet<Int>(); val hiddenRows = HashSet<Int>()
        val merges = ArrayList<SheetRange>()
        var rows = 0; var cols = 0; var frozenRows = 0; var frozenCols = 0; var frozen = false
        var pending: Pair<Int, Int>? = null // a formula whose words come in the next STRING record
        fun put(r: Int, c: Int, cell: SheetCell) { cells[Sheet.key(r, c)] = cell; rows = maxOf(rows, r + 1); cols = maxOf(cols, c + 1) }
        fun number(r: Int, c: Int, xf: Int, v: Double) {
            val shown = format.format(v, styles.getOrNull(xf)?.format ?: "General")
            put(r, c, SheetCell(shown.text, SheetKind.Number, v, style = xf.coerceIn(0, styles.lastIndex), color = shown.color))
        }
        for (rec in records(w, at.second)) {
            val d = rec.data
            when (rec.type) {
                0x00FD -> { val r = d.short.toInt() and 0xFFFF; val c = d.short.toInt() and 0xFFFF; val xf = d.short.toInt() and 0xFFFF; val s = d.int
                    put(r, c, SheetCell(strings.getOrNull(s).orEmpty(), SheetKind.Text, style = xf.coerceIn(0, styles.lastIndex))) }
                0x0204 -> { val r = d.short.toInt() and 0xFFFF; val c = d.short.toInt() and 0xFFFF; val xf = d.short.toInt() and 0xFFFF
                    put(r, c, SheetCell(longString(d), SheetKind.Text, style = xf.coerceIn(0, styles.lastIndex))) }
                0x0203 -> { val r = d.short.toInt() and 0xFFFF; val c = d.short.toInt() and 0xFFFF; val xf = d.short.toInt() and 0xFFFF; number(r, c, xf, d.double) }
                0x027E -> { val r = d.short.toInt() and 0xFFFF; val c = d.short.toInt() and 0xFFFF; val xf = d.short.toInt() and 0xFFFF; number(r, c, xf, rk(d.int)) }
                0x00BD -> { val r = d.short.toInt() and 0xFFFF; var c = d.short.toInt() and 0xFFFF
                    while (d.remaining() >= 8) { val xf = d.short.toInt() and 0xFFFF; number(r, c++, xf, rk(d.int)) } }
                0x0205 -> { val r = d.short.toInt() and 0xFFFF; val c = d.short.toInt() and 0xFFFF; val xf = d.short.toInt() and 0xFFFF; val v = d.get().toInt(); val err = d.get().toInt() != 0
                    put(r, c, if (err) SheetCell(errorText(v), SheetKind.Error, style = xf.coerceIn(0, styles.lastIndex))
                        else SheetCell(if (v != 0) "TRUE" else "FALSE", SheetKind.Bool, if (v != 0) 1.0 else 0.0, style = xf.coerceIn(0, styles.lastIndex))) }
                0x0006 -> {
                    val r = d.short.toInt() and 0xFFFF; val c = d.short.toInt() and 0xFFFF; val xf = d.short.toInt() and 0xFFFF
                    val res = ByteArray(8).also { d.get(it) }
                    val st = xf.coerceIn(0, styles.lastIndex)
                    if ((res[6].toInt() and 255) == 0xFF && (res[7].toInt() and 255) == 0xFF) when (res[0].toInt()) {
                        0 -> pending = r to c // its words: the STRING record after it
                        1 -> put(r, c, SheetCell(if (res[2].toInt() != 0) "TRUE" else "FALSE", SheetKind.Bool, if (res[2].toInt() != 0) 1.0 else 0.0, style = st))
                        2 -> put(r, c, SheetCell(errorText(res[2].toInt()), SheetKind.Error, style = st))
                        else -> put(r, c, SheetCell("", SheetKind.Text, style = st))
                    } else number(r, c, xf, ByteBuffer.wrap(res).order(ByteOrder.LITTLE_ENDIAN).double)
                    if (pending != null) put(r, c, SheetCell("", SheetKind.Text, style = st))
                }
                0x0207 -> pending?.let { (r, c) -> pending = null; put(r, c, (cells[Sheet.key(r, c)] ?: SheetCell("", SheetKind.Text)).copy(text = longString(d), kind = SheetKind.Text)) }
                0x00E5 -> { val n = d.short.toInt() and 0xFFFF
                    repeat(n) { if (d.remaining() >= 8) { val r1 = d.short.toInt() and 0xFFFF; val r2 = d.short.toInt() and 0xFFFF; val c1 = d.short.toInt() and 0xFFFF; val c2 = d.short.toInt() and 0xFFFF; merges += SheetRange(r1, c1, r2, c2) } } }
                0x007D -> { val c1 = d.short.toInt() and 0xFFFF; val c2 = d.short.toInt() and 0xFFFF; val wd = d.short.toInt() and 0xFFFF; d.short; val grbit = d.short.toInt()
                    // a width in 256ths of a character, as Excel's .xlsx columns (in pixels the same way)
                    val px = floor(((wd + floor(128.0 / 7)) / 256) * 7).toFloat()
                    for (c in c1..minOf(c2, c1 + 1024)) { widths[c] = px; if (grbit and 1 != 0) hiddenCols += c } }
                0x0208 -> { val r = d.short.toInt() and 0xFFFF; d.short; d.short; val h = d.short.toInt() and 0xFFFF; d.short; d.short; val grbit = d.short.toInt()
                    if (grbit and 0x40 != 0 || h and 0x8000 == 0) heights[r] = (h and 0x7FFF) / 20f * 4 / 3
                    if (grbit and 0x20 != 0) hiddenRows += r }
                0x023E -> { frozen = (d.short.toInt() and 8) != 0 }
                0x0041 -> if (frozen) { frozenCols = d.short.toInt() and 0xFFFF; frozenRows = d.short.toInt() and 0xFFFF }
            }
        }
        return Sheet(at.first, cells, rows, cols, widths, heights, 64f, 20f, hiddenCols, hiddenRows, merges, frozenRows, frozenCols)
    }

    private fun errorText(code: Int) = when (code) { 0x00 -> "#NULL!"; 0x07 -> "#DIV/0!"; 0x0F -> "#VALUE!"; 0x17 -> "#REF!"; 0x1D -> "#NAME?"; 0x24 -> "#NUM!"; else -> "#N/A" }
}
