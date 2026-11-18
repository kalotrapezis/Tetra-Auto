package com.kalotrapezis.drive

import org.w3c.dom.Element

/**
 * A printed page's header and footer (Page Layout › Header & Footer): kept as Excel keeps them, a code ("&LDraft&CPage
 * &P of &N"), its left, centre and right parts; shown and typed with readable fields ([Page], [Pages], [Sheet], [File],
 * [Date], [Time]). LibreOffice's master page header (its regions, its text:page-number and the rest) read and written
 * as the same.
 */
internal object SheetHeaderFooter {
    /** The readable fields (as typed, in every language), Excel's codes for them, and their chips' names. */
    val FIELDS = listOf(Triple("[Page]", "P", R.string.tables_field_page), Triple("[Pages]", "N", R.string.tables_field_pages), Triple("[Sheet]", "A", R.string.tables_sheet),
        Triple("[File]", "F", R.string.tables_tab_file), Triple("[Date]", "D", R.string.tables_format_date), Triple("[Time]", "T", R.string.tables_format_time))
    /** Excel's ready-made ones (Page Layout › Header ▾): a name (a string) and its code, its words ([page], [of]) in Tetra's language; null: none. */
    fun presets(page: String = "Page", of: String = "of") = listOf(R.string.common_none to null, R.string.tables_hf_page to "&C$page &P",
        R.string.tables_hf_page_of to "&C$page &P $of &N", R.string.tables_hf_sheet_name to "&C&A", R.string.tables_hf_file_name to "&C&F",
        R.string.tables_hf_sheet_page to "&L&A&R$page &P")

    /** Excel's [code] as its left, centre and right parts' words, its fields as [Page]…, its font codes left out. */
    fun parts(code: String?): List<String> {
        val out = arrayOf(StringBuilder(), StringBuilder(), StringBuilder()); var at = 1 // words before a section's code: the centre
        val s = code.orEmpty(); var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch != '&' || i + 1 >= s.length) { out[at].append(ch); i++; continue }
            val n = s[i + 1]
            when {
                n == '&' -> { out[at].append('&'); i += 2 }
                n == 'L' -> { at = 0; i += 2 }; n == 'C' -> { at = 1; i += 2 }; n == 'R' -> { at = 2; i += 2 }
                n == '"' -> { val end = s.indexOf('"', i + 2); i = if (end < 0) s.length else end + 1 } // a font
                n.isDigit() -> { i += 1; while (i < s.length && s[i].isDigit()) i++ } // a size
                n == 'K' -> i += 8 // a colour: &KRRGGBB
                else -> { FIELDS.firstOrNull { it.second == n.toString() }?.let { out[at].append(it.first) }; i += 2
                    if (n == 'P') while (i < s.length && (s[i] == '+' || s[i] == '-' || s[i].isDigit())) i++ } // &P+1
            }
        }
        return out.map { it.toString() }
    }

    /** The parts as Excel's code: & doubled, fields as codes; null when all are empty. */
    fun code(parts: List<String>): String? {
        if (parts.all { it.isBlank() }) return null
        return parts.mapIndexedNotNull { k, p -> if (p.isBlank()) null else "&" + "LCR"[k] + FIELDS.fold(p.replace("&", "&&")) { t, (f, c) -> t.replace(f, "&$c") } }.joinToString("")
    }

    /** A part as printed on page [page] of [pages]. */
    fun shown(part: String, page: Int, pages: Int, sheet: String, file: String, now: java.time.LocalDateTime = java.time.LocalDateTime.now()) =
        part.replace("[Pages]", "$pages").replace("[Page]", "$page").replace("[Sheet]", sheet).replace("[File]", file)
            .replace("[Date]", now.toLocalDate().format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.SHORT)))
            .replace("[Time]", now.toLocalTime().withNano(0).withSecond(0).toString())

    private const val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    private const val S = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    private fun kids(e: Element) = (0 until e.childNodes.length).mapNotNull { e.childNodes.item(it) as? Element }
    private val ODS_FIELDS = mapOf("page-number" to "[Page]", "page-count" to "[Pages]", "sheet-name" to "[Sheet]", "file-name" to "[File]", "date" to "[Date]", "time" to "[Time]")

    /** LibreOffice's header or footer ([e]: style:header, style:footer) as Excel's code; null when it shows none. */
    fun fromOds(e: Element?): String? {
        if (e == null || e.getAttributeNS(S, "display") == "false") return null
        fun words(n: org.w3c.dom.Node): String = when {
            n is Element && n.namespaceURI == TEXT && n.localName in ODS_FIELDS -> ODS_FIELDS.getValue(n.localName)
            n is Element && n.namespaceURI == TEXT && n.localName == "s" -> " ".repeat(n.getAttributeNS(TEXT, "c").toIntOrNull() ?: 1)
            n is Element -> (0 until n.childNodes.length).joinToString("") { words(n.childNodes.item(it)) }
            else -> n.textContent.orEmpty()
        }
        fun region(r: Element?) = r?.let { kids(it).filter { p -> p.localName == "p" }.joinToString("\n") { p -> words(p) } }.orEmpty()
        val regions = kids(e)
        val parts = if (regions.any { it.localName.startsWith("region-") }) listOf("region-left", "region-center", "region-right").map { n -> region(regions.firstOrNull { it.localName == n }) }
            else listOf("", region(e), "")
        return code(parts)
    }

    /** [master]'s header or footer ([kind]) made Excel's [code] (null: none), as LibreOffice's three regions. */
    fun intoOds(master: Element, kind: String, code: String?) {
        val doc = master.ownerDocument
        kids(master).filter { it.localName == kind || it.localName == "$kind-left" || it.localName == "$kind-first" }.forEach { master.removeChild(it) }
        code ?: return
        val e = doc.createElementNS(S, "style:$kind")
        parts(code).zip(listOf("left", "center", "right")).forEach { (part, side) ->
            val r = doc.createElementNS(S, "style:region-$side")
            part.split('\n').forEach { line ->
                val p = doc.createElementNS(TEXT, "text:p")
                // the words between fields as text, each field LibreOffice's own
                Regex("""\[(Page|Pages|Sheet|File|Date|Time)]""").let { re ->
                    var last = 0
                    re.findAll(line).forEach { m -> if (m.range.first > last) p.appendChild(doc.createTextNode(line.substring(last, m.range.first)))
                        val local = ODS_FIELDS.entries.first { it.value == m.value }.key
                        p.appendChild(doc.createElementNS(TEXT, "text:$local").also { it.textContent = when (local) { "page-number", "page-count" -> "1"; else -> "???" } })
                        last = m.range.last + 1 }
                    if (last < line.length) p.appendChild(doc.createTextNode(line.substring(last)))
                }
                r.appendChild(p)
            }
            e.appendChild(r)
        }
        // header before footer, as the schema has them
        val footer = kids(master).firstOrNull { it.localName.startsWith("footer") }
        if (kind == "header" && footer != null) master.insertBefore(e, footer) else master.appendChild(e)
    }
}
