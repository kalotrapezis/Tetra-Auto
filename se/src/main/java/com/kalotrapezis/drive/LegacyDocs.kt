package com.kalotrapezis.drive

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Older documents (Phase 1, item 6): Word 97–2003's .doc and Rich Text's .rtf, read into Writer's document — text,
 * letters' looks, paragraphs' alignment, spacing and indents, headings, lists, page breaks, links and tables (and an
 * .rtf's PNG/JPEG pictures). They are shown and edited as any document; saving writes a .docx beside them (the old file
 * is never written). What could not be read is said in [OfficeDoc.unsupported].
 */
internal object LegacyDocs {
    val EXTENSIONS = setOf("doc", "rtf")
    /** Said for every older file: it saves as a .docx beside it (a copy), never over it. */
    const val OLDER = "an older format (saved as .docx)"

    fun isLegacy(name: String) = name.substringAfterLast('.', "").lowercase() in EXTENSIONS

    fun read(bytes: ByteArray, name: String): OfficeDoc = when {
        bytes.size >= 8 && bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() -> WordBinary(bytes).read()
        String(bytes, 0, minOf(bytes.size, 6), Charsets.ISO_8859_1).startsWith("{\\rtf") -> Rtf(bytes).read()
        else -> name.substringAfterLast('.').let { throw OfficeReadError(R.string.office_error_cannot_read, "$it file Writer cannot read", it) }
    }

    /** Paragraphs and tables as they are read, in order (cells' paragraphs gathered into rows, rows into a table). */
    class Builder {
        val out = ArrayList<DocPara>()
        val notes = LinkedHashSet<String>(listOf(OLDER))
        var runs = ArrayList<DocRun>()
        var align = DocAlign.Start
        var before: Float? = null; var after: Float? = null; var left: Float? = null; var firstLine: Float? = null
        var heading = 0
        var list: DocList? = null
        var pageBreak = false
        private var cell = ArrayList<DocPara>()
        private var row = ArrayList<List<DocPara>>()
        private val rows = ArrayList<List<List<DocPara>>>()

        fun text(t: String, look: DocRun) { if (t.isNotEmpty()) runs += look.copy(text = t) }
        fun picture(img: DocImage, look: DocRun) { runs += look.copy(text = OBJECT_MARK.toString(), image = img) }

        private fun para(): DocPara {
            var keep: DocKeep? = null
            if (before != null || after != null || left != null || firstLine != null)
                keep = OfficeFormats.withSpacing(null, OfficeKind.Docx, before = before, after = after, left = left, firstLine = firstLine)
            if (pageBreak) keep = OfficeFormats.withPageBreak(keep, OfficeKind.Docx, true)
            pageBreak = false
            val p = DocPara(OfficeFormats.tidy(runs), heading, list, align, keep?.takeIf { !it.isEmpty })
            runs = ArrayList()
            return p
        }
        fun endParagraph(inTable: Boolean) { val p = para(); if (inTable) cell += p else { table(); out += p } }
        fun endCell() { if (runs.isNotEmpty()) cell += para(); row += cell.ifEmpty { listOf(DocPara(emptyList())) }; cell = ArrayList() }
        fun endRow() { if (runs.isNotEmpty() || cell.isNotEmpty()) endCell(); if (row.isNotEmpty()) rows += row; row = ArrayList() }
        /** The rows gathered so far, as one Word table (equal columns across the text). */
        fun table() {
            if (rows.isEmpty()) return
            val n = rows.maxOf { it.size }
            val t = TableEdit.newTable(rows.size, n, 451f)
            out += tablePara(t.copy(rows = t.rows.mapIndexed { i, r -> r.copy(cells = r.cells.mapIndexed { k, c -> c.copy(paras = rows[i].getOrNull(k) ?: c.paras) }) }))
            rows.clear()
        }
        fun finish(basePt: Float): OfficeDoc {
            if (runs.isNotEmpty()) endParagraph(false)
            if (row.isNotEmpty() || cell.isNotEmpty()) endRow()
            table()
            // The size most letters have is the document's own: said once, not on each run (letters with none have [basePt]).
            val sizes = out.flatMap { p -> p.runs.map { r -> (r.sizePt ?: basePt) to r.text.length } }.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
            val base = sizes.maxByOrNull { it.value }?.key ?: basePt
            fun plain(ps: List<DocPara>): List<DocPara> = ps.map { p -> p.copy(runs = p.runs.map { if (it.sizePt == base) it.copy(sizePt = null) else it },
                table = p.table?.let { t -> t.copy(rows = t.rows.map { r -> r.copy(cells = r.cells.map { c -> c.copy(paras = plain(c.paras)) }) }) }) }
            return OfficeDoc(plain(out).ifEmpty { listOf(DocPara(emptyList())) }, notes, base)
        }
    }

    // ---------------------------------------------------------------- RTF

    private class Rtf(val src: ByteArray) {
        private var i = 0
        private val b = Builder()
        private val fonts = HashMap<Int, Pair<String, Int>>() // number → (name, charset)
        private val colors = ArrayList<Int?>()
        private val styles = HashMap<Int, String>()
        private var codepage = 1252
        private var defaultFont = -1
        private var pictures = 0

        /** What a group holds and how its letters look; copied for each group, so a closing brace puts the old back. */
        data class State(var dest: String = "", var bold: Boolean = false, var italic: Boolean = false, var underline: Boolean = false, var strike: Boolean = false,
            var size: Int = 24, var color: Int = 0, var highlight: Int = 0, var font: Int = -1, var script: Int = 0, var hidden: Boolean = false, var uc: Int = 1,
            var link: String? = null)
        private var st = State()
        private val stack = ArrayList<State>()
        // Paragraph settings (\pard puts them back).
        private var inTable = false
        private var style = -1
        // A destination's own text: a font's name, a field's instructions.
        private val gathered = StringBuilder()
        private val pending = ByteArrayOutputStream() // \'hh bytes, decoded together (a two-byte code page)
        private var pendingCp = 1252
        private var skipChars = 0
        // A picture's settings and its bytes in hex.
        private var pictKind: String? = null; private var picW = 0; private var picH = 0; private var picWGoal = 0; private var picHGoal = 0
        private val pictHex = StringBuilder()
        private var fieldInst: String? = null

        fun read(): OfficeDoc {
            while (i < src.size) {
                val c = src[i].toInt() and 0xFF
                when (c) {
                    '{'.code -> { flush(); stack += st.copy(); i++ }
                    '}'.code -> { flush(); close(); i++ }
                    '\\'.code -> control()
                    '\r'.code, '\n'.code -> i++
                    else -> { i++; when {
                        skipChars > 0 -> skipChars--
                        st.dest == "" -> plainChar(c)
                        st.dest == "pict" -> pictHex.append(c.toChar())
                        st.dest == "colortbl" -> if (c == ';'.code) colorEnd()
                        else -> gather(c)
                    } }
                }
            }
            flush()
            return b.finish(12f)
        }

        private fun look(): DocRun {
            val f = st.font.takeIf { it >= 0 } ?: defaultFont
            val face = fonts[f]?.first
            val r = DocRun("", bold = st.bold, italic = st.italic, underline = st.underline, strike = st.strike, sizePt = st.size / 2f,
                color = colors.getOrNull(st.color), highlight = colors.getOrNull(st.highlight), script = st.script, link = st.link)
            return if (face != null) OfficeFormats.withFont(r, OfficeKind.Docx, face) else r
        }
        private fun charsetOf(f: Int) = fonts[f]?.second
        private fun cpOf(charset: Int?) = when (charset) { 161 -> 1253; 162 -> 1254; 204 -> 1251; 238 -> 1250; 177 -> 1255; 178 -> 1256; 186 -> 1257; 163 -> 1258; 222 -> 874; 128 -> 932; 134 -> 936; 136 -> 950; 129 -> 949; 0 -> 1252; else -> null }
        private fun currentCp() = cpOf(charsetOf(st.font.takeIf { it >= 0 } ?: defaultFont)) ?: codepage

        private fun plainChar(c: Int) { if (st.hidden) return; pendingByte(c) }
        private fun pendingByte(c: Int) {
            val cp = currentCp()
            if (pending.size() > 0 && cp != pendingCp) flush()
            pendingCp = cp; pending.write(c)
        }
        private fun gather(c: Int) { if (st.dest in GATHERING) gathered.append(decode(byteArrayOf(c.toByte()), currentCp())) }
        private fun decode(bytes: ByteArray, cp: Int) = runCatching { String(bytes, Charset.forName("windows-$cp")) }.getOrElse { String(bytes, Charsets.ISO_8859_1) }
        /** Letters waiting as bytes, put in the text. */
        private fun flush() {
            if (pending.size() == 0) return
            val t = decode(pending.toByteArray(), pendingCp); pending.reset()
            if (st.dest == "") b.text(t, look()) else if (st.dest in GATHERING) gathered.append(t)
        }
        private fun emit(t: String) { flush(); if (st.dest == "" && !st.hidden) b.text(t, look()) else if (st.dest in GATHERING) gathered.append(t) }

        private fun close() {
            val ended = st
            st = stack.removeLastOrNull() ?: State()
            when {
                ended.dest == "fonttbl-entry" || (ended.dest == "fonttbl" && gathered.isNotEmpty()) -> fontEntry()
                ended.dest == "pict" && st.dest != "pict" -> picture()
                ended.dest == "fldinst" -> fieldInst = gathered.toString().also { gathered.setLength(0) }
                ended.dest == "stylesheet-entry" -> { gathered.toString().trim().removeSuffix(";").let { if (styleNo >= 0) styles[styleNo] = it }; gathered.setLength(0); styleNo = -1 }
            }
            if (ended.dest == "fldrslt") fieldInst = null
        }
        private var fontNo = -1; private var fontCharset = 0; private var styleNo = -1
        private fun fontEntry() {
            val name = gathered.toString().substringBefore(';').trim()
            if (fontNo >= 0 && name.isNotEmpty()) fonts[fontNo] = name to fontCharset
            gathered.setLength(0); fontNo = -1; fontCharset = 0
        }
        private fun picture() {
            val kind = pictKind; pictKind = null
            val hex = pictHex.toString().filter { it.isLetterOrDigit() }; pictHex.setLength(0)
            if (kind == null) { b.notes += "pictures (WMF/EMF)"; return }
            val bytes = ByteArray(hex.length / 2) { k -> hex.substring(2 * k, 2 * k + 2).toInt(16).toByte() }
            val w = (if (picWGoal > 0) picWGoal / 20f else picW * 0.75f).coerceAtLeast(8f); val h = (if (picHGoal > 0) picHGoal / 20f else picH * 0.75f).coerceAtLeast(8f)
            b.picture(DocImage("rtf${pictures++}", null, w, h, null, bytes = bytes, extension = kind), look())
            picW = 0; picH = 0; picWGoal = 0; picHGoal = 0
        }

        private fun control() {
            i++ // the backslash
            if (i >= src.size) return
            val c = src[i].toInt().toChar()
            if (!c.isLetter()) {
                i++
                when (c) {
                    '\'' -> { val hex = String(src, i, 2, Charsets.ISO_8859_1); i += 2
                        if (skipChars > 0) { skipChars--; return }
                        hex.toIntOrNull(16)?.let { v -> if (st.dest == "") plainChar(v) else gather(v) } }
                    '\\', '{', '}' -> if (skipChars > 0) skipChars-- else if (st.dest == "") plainChar(c.code) else gather(c.code)
                    '~' -> emit(" ")
                    '_' -> emit("‑")
                    '-' -> Unit
                    '*' -> st.dest = "skip*" // an optional destination (its own group): skipped unless known below
                    '\r', '\n' -> { flush(); if (st.dest == "") b.endParagraph(inTable) }
                }
                return
            }
            val start = i
            while (i < src.size && (src[i].toInt().toChar()).isLetter()) i++
            val word = String(src, start, i - start, Charsets.ISO_8859_1)
            var num: Int? = null
            val ns = i
            if (i < src.size && (src[i] == '-'.code.toByte() || src[i].toInt().toChar().isDigit())) { i++; while (i < src.size && src[i].toInt().toChar().isDigit()) i++; num = String(src, ns, i - ns, Charsets.ISO_8859_1).toIntOrNull() }
            if (i < src.size && src[i] == ' '.code.toByte()) i++
            word(word, num)
        }

        private fun word(w: String, n: Int?) {
            // Destinations.
            if (st.dest == "skip*") { st.dest = if (w in KNOWN_STARRED) w else "skip"; if (w == "shppict") st.dest = ""; return }
            if (st.dest == "skip" || st.dest == "nonshppict") return
            if (w in SKIPPED) { flush(); st.dest = "skip"; if (w == "footnote") b.notes += "footnotes"; if (w.startsWith("header") || w.startsWith("footer")) b.notes += "headers and footers"; return }
            when (st.dest) {
                "fonttbl", "fonttbl-entry" -> { when (w) { "f" -> { if (gathered.isNotEmpty()) fontEntry(); fontNo = n ?: -1; st.dest = "fonttbl-entry" }; "fcharset" -> fontCharset = n ?: 0 }; return }
                "colortbl" -> { colorPart(w, n); return }
                "stylesheet", "stylesheet-entry" -> { when (w) { "s" -> { styleNo = n ?: -1; st.dest = "stylesheet-entry" } }; return }
                "pict" -> { when (w) { "pngblip" -> pictKind = "png"; "jpegblip" -> pictKind = "jpg"; "picw" -> picW = n ?: 0; "pich" -> picH = n ?: 0; "picwgoal" -> picWGoal = n ?: 0; "pichgoal" -> picHGoal = n ?: 0 }; return }
                "fldinst" -> { if (w == "u") n?.let { emitUnicode(it) }; return }
            }
            when (w) {
                "rtf", "ansi" -> Unit
                "ansicpg" -> codepage = n ?: 1252
                "deff" -> defaultFont = n ?: -1
                "fonttbl" -> { flush(); st.dest = "fonttbl" }
                "colortbl" -> { flush(); st.dest = "colortbl"; colors.clear(); colorNow = null }
                "stylesheet" -> { flush(); st.dest = "stylesheet" }
                "pict" -> { flush(); st.dest = "pict"; pictHex.setLength(0); pictKind = null }
                "nonshppict" -> st.dest = "nonshppict"
                "fldinst" -> { flush(); st.dest = "fldinst"; gathered.setLength(0) }
                "fldrslt" -> { flush(); st.link = fieldInst?.let { Regex("""HYPERLINK\s+"([^"]+)"""").find(it)?.groupValues?.get(1) } }
                // Letters.
                "plain" -> { flush(); st.bold = false; st.italic = false; st.underline = false; st.strike = false; st.size = 24; st.color = 0; st.highlight = 0; st.script = 0; st.hidden = false; st.font = -1 }
                "b" -> { flush(); st.bold = n != 0 }
                "i" -> { flush(); st.italic = n != 0 }
                "ul", "uld", "uldb", "uldash", "ulw", "ulth", "ulwave", "uldashd", "ulhwave" -> { flush(); st.underline = n != 0 }
                "ulnone" -> { flush(); st.underline = false }
                "strike", "striked" -> { flush(); st.strike = n != 0 }
                "fs" -> { flush(); st.size = n ?: 24 }
                "cf" -> { flush(); st.color = n ?: 0 }
                "highlight", "cb", "chcbpat" -> { flush(); st.highlight = n ?: 0 }
                "f" -> { flush(); st.font = n ?: -1 }
                "super" -> { flush(); st.script = 1 }
                "sub" -> { flush(); st.script = -1 }
                "nosupersub" -> { flush(); st.script = 0 }
                "v" -> { flush(); st.hidden = n != 0 }
                "uc" -> st.uc = n ?: 1
                "u" -> n?.let { emitUnicode(it) }
                // Special letters.
                "tab" -> emit("\t")
                "line" -> emit(LINE_BREAK.toString())
                "emdash" -> emit("—"); "endash" -> emit("–"); "bullet" -> emit("•")
                "lquote" -> emit("‘"); "rquote" -> emit("’"); "ldblquote" -> emit("“"); "rdblquote" -> emit("”")
                "emspace", "enspace", "qmspace" -> emit(" ")
                // Paragraphs.
                "par" -> { flush(); if (st.dest == "") b.endParagraph(inTable) }
                "sect" -> { flush(); if (st.dest == "") { if (b.runs.isNotEmpty()) b.endParagraph(inTable); b.pageBreak = true } }
                "page" -> { flush(); if (b.runs.isNotEmpty()) b.endParagraph(inTable); b.pageBreak = true }
                "pard" -> { flush(); b.align = DocAlign.Start; b.before = null; b.after = null; b.left = null; b.firstLine = null; b.heading = 0; inTable = false; style = -1 }
                "ql" -> b.align = DocAlign.Start; "qc" -> b.align = DocAlign.Center; "qr" -> b.align = DocAlign.End; "qj" -> b.align = DocAlign.Justify
                "pagebb" -> b.pageBreak = true
                "sb" -> b.before = (n ?: 0) / 20f
                "sa" -> b.after = (n ?: 0) / 20f
                "li" -> b.left = (n ?: 0) / 20f
                "fi" -> b.firstLine = (n ?: 0) / 20f
                "s" -> { style = n ?: -1; b.heading = styles[style]?.let { Regex("""^heading (\d)$""", RegexOption.IGNORE_CASE).find(it.trim())?.groupValues?.get(1)?.toInt() } ?: b.heading }
                "outlinelevel" -> b.heading = ((n ?: 9) + 1).takeIf { it in 1..6 } ?: 0
                // Tables.
                "intbl" -> inTable = true
                "cell", "nestcell" -> { flush(); b.endCell() }
                "row", "nestrow" -> { flush(); b.endRow() }
                "trowd" -> Unit
            }
        }
        private var colorNow: IntArray? = null
        private fun colorPart(w: String, n: Int?) {
            val c = colorNow ?: IntArray(3).also { colorNow = it }
            when (w) { "red" -> c[0] = n ?: 0; "green" -> c[1] = n ?: 0; "blue" -> c[2] = n ?: 0 }
        }
        private fun emitUnicode(v: Int) {
            val ch = (if (v < 0) v + 65536 else v).toChar()
            if (st.dest == "fldinst") gathered.append(ch) else emit(ch.toString())
            skipChars = st.uc
        }

        /** A colour table's entry ends at its ';' (the first, empty, is "auto"). */
        private fun colorEnd() { colors += colorNow?.let { (it[0] shl 16) or (it[1] shl 8) or it[2] }; colorNow = null }

        companion object {
            val GATHERING = setOf("fonttbl", "fonttbl-entry", "stylesheet-entry", "fldinst")
            val KNOWN_STARRED = setOf("fldinst", "shppict")
            val SKIPPED = setOf("info", "header", "footer", "headerl", "headerr", "headerf", "footerl", "footerr", "footerf", "footnote", "annotation", "object",
                "xe", "tc", "txe", "listtable", "listoverridetable", "revtbl", "rsidtbl", "filetbl", "generator", "themedata", "colorschememapping",
                "latentstyles", "datastore", "xmlnstbl", "pgdsctbl", "mmathPr", "pn", "pnseclvl", "bkmkstart", "bkmkend", "fldtype", "userprops", "do")
        }
    }

    // ---------------------------------------------------------------- Word 97–2003 (.doc)

    /** An OLE compound file's streams, by name (the part of it a .doc needs). */
    private class Compound(val b: ByteArray) {
        private val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        private val sector = 1 shl buf.getShort(0x1E).toInt()
        private val miniSector = 1 shl buf.getShort(0x20).toInt()
        private val cutoff = buf.getInt(0x38)
        private val fat: IntArray
        private val miniFat: IntArray
        private val entries = HashMap<String, Pair<Int, Long>>() // name → (start, size)
        private var miniStream = ByteArray(0)

        private fun off(sid: Int) = (sid + 1).toLong() * sector
        private fun chain(start: Int, table: IntArray): List<Int> {
            val out = ArrayList<Int>(); var s = start
            while (s >= 0 && s < table.size && out.size < table.size) { out += s; s = table[s] }
            return out
        }
        private fun read(start: Int, size: Long): ByteArray {
            val out = ByteArrayOutputStream()
            var left = size
            for (s in chain(start, fat)) { if (left <= 0) break; val o = off(s).toInt(); val n = minOf(sector.toLong(), left, (b.size - o).toLong()).toInt(); if (n <= 0) break; out.write(b, o, n); left -= n }
            return out.toByteArray()
        }

        init {
            // The FAT's own sectors: 109 in the header, then the DIFAT's chain.
            val fatSectors = ArrayList<Int>()
            for (k in 0 until 109) buf.getInt(0x4C + 4 * k).takeIf { it >= 0 }?.let { fatSectors += it }
            var difat = buf.getInt(0x44)
            var guard = 0
            while (difat >= 0 && guard++ < 10000 && off(difat) + sector <= b.size) {
                val o = off(difat).toInt()
                for (k in 0 until sector / 4 - 1) buf.getInt(o + 4 * k).takeIf { it >= 0 }?.let { fatSectors += it }
                difat = buf.getInt(o + sector - 4)
            }
            fat = IntArray(fatSectors.size * sector / 4)
            fatSectors.forEachIndexed { k, s -> val o = off(s).toInt(); if (o + sector <= b.size) for (j in 0 until sector / 4) fat[k * sector / 4 + j] = buf.getInt(o + 4 * j) }
            // The directory: each entry's name, start and size.
            val dir = read(buf.getInt(0x30), Long.MAX_VALUE.coerceAtMost(fat.size.toLong() * sector))
            val db = ByteBuffer.wrap(dir).order(ByteOrder.LITTLE_ENDIAN)
            var rootStart = -1; var rootSize = 0L
            for (e in 0 until dir.size / 128) {
                val o = e * 128
                val len = db.getShort(o + 0x40).toInt()
                if (len <= 2) continue
                val name = String(dir, o, len - 2, Charsets.UTF_16LE)
                val type = dir[o + 0x42].toInt()
                val start = db.getInt(o + 0x74); val size = db.getInt(o + 0x78).toLong() and 0xFFFFFFFFL
                if (type == 5) { rootStart = start; rootSize = size } else if (type == 2) entries.putIfAbsent(name, start to size)
            }
            val mf = read(buf.getInt(0x3C), buf.getInt(0x40).toLong() * sector)
            miniFat = IntArray(mf.size / 4) { ByteBuffer.wrap(mf, 4 * it, 4).order(ByteOrder.LITTLE_ENDIAN).int }
            if (rootStart >= 0) miniStream = read(rootStart, rootSize)
        }

        fun stream(name: String): ByteArray? {
            val (start, size) = entries[name] ?: return null
            if (size >= cutoff) return read(start, size)
            val out = ByteArrayOutputStream(); var left = size
            for (s in chain(start, miniFat)) { if (left <= 0) break; val o = s * miniSector; val n = minOf(miniSector.toLong(), left, (miniStream.size - o).toLong()).toInt(); if (n <= 0) break; out.write(miniStream, o, n); left -= n }
            return out.toByteArray()
        }
    }

    /** Word 97–2003's binary format ([MS-DOC]): the piece table's text, the letters' and paragraphs' properties (sprms). */
    private class WordBinary(bytes: ByteArray) {
        private val cf = Compound(bytes)
        private val doc = cf.stream("WordDocument") ?: throw OfficeReadError(R.string.office_error_not_word, "Not a Word document")
        private val d = ByteBuffer.wrap(doc).order(ByteOrder.LITTLE_ENDIAN)
        private val flags = d.getShort(0x0A).toInt()
        private val table = cf.stream(if (flags and 0x0200 != 0) "1Table" else "0Table") ?: throw OfficeReadError(R.string.office_error_table_stream, "Its table stream is missing")
        private val t = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)
        private val b = Builder()

        private fun u8(buf: ByteArray, o: Int) = buf[o].toInt() and 0xFF
        private fun u16(buf: ByteBuffer, o: Int) = buf.getShort(o).toInt() and 0xFFFF
        private val lw: Int // where FibRgLw97 starts
        private val fcLcb: Int // where FibRgFcLcb starts
        private fun fc(index: Int) = d.getInt(fcLcb + index * 8)
        private fun lcb(index: Int) = d.getInt(fcLcb + index * 8 + 4)

        init {
            if (u16(d, 0) != 0xA5EC) throw OfficeReadError(R.string.office_error_not_word97, "Not a Word 97–2003 document")
            if (u16(d, 2) < 0xC1) throw OfficeReadError(R.string.office_error_word95, "A Word 6 or 95 document: open it in Word or LibreOffice and save it as .docx")
            if (flags and 0x0100 != 0) throw OfficeReadError(R.string.office_error_doc_password, "This .doc is password-protected")
            val csw = u16(d, 32)
            lw = 34 + csw * 2 + 2
            val cslw = u16(d, 34 + csw * 2)
            fcLcb = lw + cslw * 4 + 2
        }

        /** Each character of the main text with the place (in WordDocument) its properties are found at. */
        private class Piece(val cp: Int, val end: Int, val fc: Int, val compressed: Boolean)
        private fun pieces(): List<Piece> {
            var o = fc(33); val end = o + lcb(33)
            while (o < end) {
                when (table[o].toInt()) {
                    1 -> o += 3 + t.getShort(o + 1).toInt()
                    2 -> {
                        val size = t.getInt(o + 1); val n = (size - 4) / 12; val cps = o + 5; val pcds = cps + (n + 1) * 4
                        return (0 until n).map { k ->
                            val raw = t.getInt(pcds + k * 8 + 2)
                            val comp = raw and 0x40000000 != 0
                            Piece(t.getInt(cps + 4 * k), t.getInt(cps + 4 * k + 4), if (comp) (raw and 0x3FFFFFFF) / 2 else raw, comp)
                        }
                    }
                    else -> return emptyList()
                }
            }
            return emptyList()
        }

        /** A property table (PlcBteChpx / PlcBtePapx) read into (fc from, fc to, its sprms, its style) for every run of it. */
        private class Props(val from: Int, val to: Int, val sprms: ByteArray, val istd: Int)
        private fun fkps(index: Int, para: Boolean): List<Props> {
            val o = fc(index); val size = lcb(index)
            if (size <= 4) return emptyList()
            val n = (size - 4) / 8
            val out = ArrayList<Props>()
            for (k in 0 until n) {
                val page = (t.getInt(o + (n + 1) * 4 + 4 * k) and 0x3FFFFF) * 512
                if (page + 512 > doc.size) continue
                val count = u8(doc, page + 511)
                for (j in 0 until count) {
                    val from = d.getInt(page + 4 * j); val to = d.getInt(page + 4 * j + 4)
                    if (!para) {
                        val at = u8(doc, page + (count + 1) * 4 + j) * 2
                        val sprms = if (at == 0) ByteArray(0) else doc.copyOfRange(page + at + 1, (page + at + 1 + u8(doc, page + at)).coerceAtMost(doc.size))
                        out += Props(from, to, sprms, 0)
                    } else {
                        val at = u8(doc, page + (count + 1) * 4 + j * 13) * 2
                        if (at == 0) { out += Props(from, to, ByteArray(0), 0); continue }
                        var cb = u8(doc, page + at); var start = page + at + 1
                        val len = if (cb == 0) { cb = u8(doc, start); start++; 2 * cb } else 2 * cb - 1
                        val all = doc.copyOfRange(start, (start + len).coerceAtMost(doc.size))
                        out += Props(from, to, if (all.size > 2) all.copyOfRange(2, all.size) else ByteArray(0), if (all.size >= 2) (all[0].toInt() and 0xFF) or ((all[1].toInt() and 0xFF) shl 8) else 0)
                    }
                }
            }
            return out.sortedBy { it.from }
        }
        private fun find(list: List<Props>, fc: Int): Props? {
            var lo = 0; var hi = list.size - 1
            while (lo <= hi) { val m = (lo + hi) / 2; val p = list[m]; if (fc < p.from) hi = m - 1 else if (fc >= p.to) lo = m + 1 else return p }
            return null
        }

        /** Each sprm in [g]: (its code, where its operand starts). */
        private fun sprms(g: ByteArray): List<Pair<Int, Int>> {
            val out = ArrayList<Pair<Int, Int>>()
            var o = 0
            while (o + 2 <= g.size) {
                val s = (g[o].toInt() and 0xFF) or ((g[o + 1].toInt() and 0xFF) shl 8)
                o += 2
                val size = when (s ushr 13) {
                    0, 1 -> 1; 2 -> 2; 3 -> 4; 4, 5 -> 2; 7 -> 3
                    else -> if (s == 0xD608 || s == 0xD609) { if (o + 2 > g.size) return out; ((g[o].toInt() and 0xFF) or ((g[o + 1].toInt() and 0xFF) shl 8)) + 1 }
                        else if (o < g.size) (g[o].toInt() and 0xFF) + 1 else return out
                }
                out += s to o
                o += size
            }
            return out
        }
        private fun op8(g: ByteArray, o: Int) = g.getOrNull(o)?.toInt()?.and(0xFF) ?: 0
        private fun op16(g: ByteArray, o: Int) = op8(g, o) or (op8(g, o + 1) shl 8)
        private fun on(v: Int) = v == 1 || v == 0x81

        private val ICO = intArrayOf(0, 0x000000, 0x0000FF, 0x00FFFF, 0x00FF00, 0xFF00FF, 0xFF0000, 0xFFFF00, 0xFFFFFF, 0x000080, 0x008080, 0x008000, 0x800080, 0x800000, 0x808000, 0x808080, 0xC0C0C0)
        private fun fonts(): List<String> {
            val o = fc(15); if (lcb(15) < 4) return emptyList()
            val n = u16(t, o); var p = o + 4
            return (0 until n).map {
                val cb = u8(table, p) + 1
                val name = StringBuilder(); var q = p + 40
                while (q + 1 < p + cb && q + 1 < table.size) { val ch = u16(t, q); if (ch == 0) break; name.append(ch.toChar()); q += 2 }
                p += cb
                name.toString()
            }
        }

        /** Letters' sprms over [base] (the paragraph style's look): a toggle's 0x80 keeps the style's, 0x81 turns it over. */
        private fun look(g: ByteArray, fonts: List<String>, base: DocRun = DocRun("")): DocRun {
            var r = base
            fun toggle(v: Int, was: Boolean) = when (v) { 0x80 -> was; 0x81 -> !was; else -> v != 0 }
            for ((s, o) in sprms(g)) when (s) {
                0x0835 -> r = r.copy(bold = toggle(op8(g, o), r.bold))
                0x0836 -> r = r.copy(italic = toggle(op8(g, o), r.italic))
                0x0837 -> r = r.copy(strike = toggle(op8(g, o), r.strike))
                0x2A3E -> r = r.copy(underline = op8(g, o) != 0)
                0x4A43 -> r = r.copy(sizePt = op16(g, o) / 2f)
                0x2A42 -> ICO.getOrNull(op8(g, o))?.takeIf { op8(g, o) > 1 }?.let { r = r.copy(color = it) }
                0x6870 -> if (op8(g, o + 3) == 0) r = r.copy(color = (op8(g, o) shl 16) or (op8(g, o + 1) shl 8) or op8(g, o + 2))
                0x2A48 -> r = r.copy(script = when (op8(g, o)) { 1 -> 1; 2 -> -1; else -> 0 })
                0x2A0C -> ICO.getOrNull(op8(g, o))?.takeIf { op8(g, o) > 0 }?.let { r = r.copy(highlight = it) }
                0x4A4F -> fonts.getOrNull(op16(g, o))?.takeIf { it.isNotEmpty() }?.let { r = OfficeFormats.withFont(r, OfficeKind.Docx, it) }
            }
            return r
        }
        private fun hidden(g: ByteArray) = sprms(g).any { (s, o) -> s == 0x083C && on(op8(g, o)) || s == 0x0800 && on(op8(g, o)) } // hidden, or deleted (tracked)

        /** Lists: each list override (ilfo, from 1) → whether each level is numbered. */
        private fun lists(): List<BooleanArray> {
            return runCatching {
                val lstO = fc(73); val lfoO = fc(74)
                if (lcb(73) < 2 || lcb(74) < 4) return emptyList()
                val n = t.getShort(lstO).toInt()
                val ids = ArrayList<Pair<Int, Boolean>>() // lsid, simple
                for (k in 0 until n) { val o = lstO + 2 + k * 28; ids += t.getInt(o) to (u8(table, o + 26) and 1 != 0) }
                var p = lstO + 2 + n * 28
                val levels = HashMap<Int, BooleanArray>()
                for ((lsid, simple) in ids) {
                    val count = if (simple) 1 else 9
                    val numbered = BooleanArray(9)
                    for (lvl in 0 until count) {
                        val nfc = u8(table, p + 4)
                        numbered[lvl] = nfc != 23 && nfc != 255
                        val cbChpx = u8(table, p + 24); val cbPapx = u8(table, p + 25)
                        p += 28 + cbPapx + cbChpx
                        p += 2 + 2 * u16(t, p)
                    }
                    levels[lsid] = numbered
                }
                val m = t.getInt(lfoO)
                (0 until m).map { k -> levels[t.getInt(lfoO + 4 + k * 16)] ?: BooleanArray(9) }
            }.getOrDefault(emptyList())
        }

        fun read(): OfficeDoc {
            val ccpText = d.getInt(lw + 12)
            val chpx = fkps(12, false); val papx = fkps(13, true)
            val fontNames = fonts(); val listsOf = lists()
            val styleLooks = HashMap<Int, DocRun>()
            val listCounts = HashMap<Int, IntArray>()
            // Fields open: their instructions (hidden), then — after the separator — their result (shown).
            class Field(val inst: StringBuilder = StringBuilder(), var shown: Boolean = false, var link: String? = null)
            val fields = ArrayList<Field>()
            fun inInstructions() = fields.any { !it.shown }
            for (p in pieces()) {
                if (p.cp >= ccpText) break
                val until = minOf(p.end, ccpText)
                for (k in 0 until until - p.cp) {
                    val fcAt = if (p.compressed) p.fc + k else p.fc + 2 * k
                    if (fcAt + (if (p.compressed) 1 else 2) > doc.size) break
                    val c = if (p.compressed) String(byteArrayOf(doc[fcAt]), Charset.forName("windows-1252"))[0] else (u16(d, fcAt)).toChar()
                    when (c) {
                        '\u0013' -> fields += Field()
                        '\u0014' -> fields.lastOrNull()?.let { it.shown = true; it.link = Regex("""HYPERLINK\s+"([^"]+)"""").find(it.inst)?.groupValues?.get(1) }
                        '\u0015' -> fields.removeLastOrNull()
                        '\r', '\u0007' -> if (!inInstructions()) {
                            val pp = find(papx, fcAt)
                            paragraph(pp)
                            // Its sprms, its style's first: (sprm, its operand's first two bytes), the last one said winning.
                            val ps = (chain(pp?.istd ?: 0, false) + listOfNotNull(pp?.sprms)).flatMap { g -> sprms(g).map { (s, o) -> s to op16(g, o) } }
                            fun last(sprm: Int) = ps.lastOrNull { it.first == sprm }?.second
                            val inTable = ((last(0x2416) ?: 0) and 0xFF) != 0 || (last(0x6649) ?: 0) != 0
                            val rowEnd = ((last(0x2417) ?: 0) and 0xFF) != 0 || ((last(0x244C) ?: 0) and 0xFF) != 0
                            val ilfo = last(0x460B) ?: 0
                            val ilvl = (last(0x260A) ?: 0) and 0xFF
                            // A numbered list goes on over the paragraphs between its items (Word's own count): said where it starts again.
                            b.list = if (ilfo in 1..listsOf.size) {
                                val lvl = ilvl.coerceIn(0, 8); val numbered = listsOf[ilfo - 1].getOrElse(lvl) { false }
                                val count = listCounts.getOrPut(ilfo) { IntArray(9) }
                                count[lvl]++; for (deeper in lvl + 1 until 9) count[deeper] = 0
                                DocList(numbered, lvl, count[lvl].takeIf { numbered && it > 1 && b.out.lastOrNull()?.list == null })
                            } else null
                            when {
                                c == '\u0007' && rowEnd -> { b.runs.clear(); b.endRow() }
                                c == '\u0007' -> b.endCell()
                                else -> b.endParagraph(inTable)
                            }
                        }
                        else -> {
                            if (inInstructions()) { fields.last { !it.shown }.inst.append(c); continue }
                            val cp = find(chpx, fcAt)?.sprms ?: ByteArray(0)
                            if (hidden(cp)) continue
                            val istd = find(papx, fcAt)?.istd ?: 0
                            val styleLook = styleLooks.getOrPut(istd) { chain(istd, true).fold(DocRun("")) { r, g -> look(g, fontNames, r) } }
                            when (c) {
                                '\u000C' -> { if (b.runs.isNotEmpty()) b.endParagraph(false); b.pageBreak = true }
                                '\u000B' -> b.text(LINE_BREAK.toString(), look(cp, fontNames, styleLook))
                                '\u001E' -> b.text("‑", look(cp, fontNames))
                                '\u001F', '\u0000' -> Unit
                                '\u0001', '\u0008' -> b.notes += "pictures"
                                '\u0002' -> b.notes += "footnotes"
                                '\u0005' -> b.notes += "comments"
                                else -> b.text(c.toString(), look(cp, fontNames, styleLook).copy(link = fields.lastOrNull { it.link != null }?.link))
                            }
                        }
                    }
                }
            }
            return b.finish(normalSize())
        }

        /** A style of the style sheet: its base, its name, its paragraph's and letters' sprms. */
        private class Style(val base: Int, val name: String, val papx: ByteArray, val chpx: ByteArray)
        private val styles: List<Style?> by lazy {
            runCatching {
                val o = fc(1); if (lcb(1) < 4) return@runCatching emptyList<Style?>()
                val cbStshi = u16(t, o); val count = u16(t, o + 2); val cbBase = u16(t, o + 4)
                var p = o + 2 + cbStshi
                (0 until count).map {
                    val cb = u16(t, p); val std = p + 2; p = std + cb
                    if (cb == 0) return@map null
                    val stk = u16(t, std + 2) and 0xF; val base = u16(t, std + 2) ushr 4
                    var q = std + cbBase
                    val cch = u16(t, q); val name = String(table, q + 2, 2 * cch, Charsets.UTF_16LE); q += 2 + 2 * cch + 2
                    fun even() { if ((q - std) % 2 != 0) q++ }
                    even()
                    var papx = ByteArray(0); var chpx = ByteArray(0)
                    if (stk == 1) { val n = u16(t, q); papx = table.copyOfRange(q + 4, (q + 2 + n).coerceAtLeast(q + 4)); q += 2 + n; even() }
                    if (stk == 1 || stk == 2) { val n = u16(t, q); chpx = table.copyOfRange(q + 2, q + 2 + n) }
                    Style(base, name, papx, chpx)
                }
            }.getOrDefault(emptyList())
        }
        /** A style's sprms, its bases' first (Word's chain, at most 20 deep). */
        private fun chain(istd: Int, letters: Boolean): List<ByteArray> {
            val out = ArrayList<ByteArray>(); var s = istd
            while (s in styles.indices && out.size < 20) { val st = styles[s] ?: break; out.add(0, if (letters) st.chpx else st.papx); if (st.base == 0xFFF || st.base == s) break; s = st.base }
            return out
        }
        private fun normalSize(): Float = chain(0, true).fold(DocRun("")) { r, g -> look(g, emptyList(), r) }.sizePt ?: 10f

        private fun paragraph(p: Props?) {
            b.align = DocAlign.Start; b.before = null; b.after = null; b.left = null; b.firstLine = null
            val istd = p?.istd ?: 0
            b.heading = (styles.getOrNull(istd)?.name?.let { Regex("""^heading (\d)$""", RegexOption.IGNORE_CASE).find(it.trim())?.groupValues?.get(1)?.toInt() }
                ?: istd.takeIf { styles.isEmpty() && it in 1..9 })?.takeIf { it in 1..6 } ?: 0 // Word's built-in styles 1–9 are Heading 1–9
            for (g in chain(istd, false) + listOfNotNull(p?.sprms)) for ((s, o) in sprms(g)) when (s) {
                0x2403, 0x2461 -> b.align = when (op8(g, o)) { 1 -> DocAlign.Center; 2 -> DocAlign.End; 3, 4, 5 -> DocAlign.Justify; else -> DocAlign.Start }
                0xA413 -> b.before = op16(g, o) / 20f
                0xA414 -> b.after = op16(g, o) / 20f
                0x840F, 0x845E -> b.left = op16(g, o).toShort() / 20f
                0x8411, 0x8460 -> b.firstLine = op16(g, o).toShort() / 20f
                0x2407 -> if (op8(g, o) != 0) b.pageBreak = true
                0x2640 -> op8(g, o).takeIf { it in 0..5 }?.let { b.heading = it + 1 }
            }
        }
    }
}
