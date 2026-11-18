package com.kalotrapezis.drive

/**
 * Footnotes' and endnotes' numbers (Word's Footnote and Endnote › Number format; LibreOffice's Footnotes and Endnotes
 * settings): 1, 2, 3 / a, b, c / A, B, C / i, ii, iii / I, II, III, and Word's *, †, ‡. Held in Word's names
 * ([OfficeDoc.footFormat], [OfficeDoc.endFormat]); read from and written to each kind's own place — Word's settings
 * (and a section's own footnotePr, which outranks them), LibreOffice's text:notes-configuration in styles.xml.
 */
internal object NoteFormats {
    const val FOOT = "decimal"
    const val END = "lowerRoman"
    /** Word's format names, each with how it starts; [CHICAGO] Word only (LibreOffice has no such numbers). */
    const val CHICAGO = "chicago"
    val ALL = listOf("decimal" to "1, 2, 3", "lowerLetter" to "a, b, c", "upperLetter" to "A, B, C", "lowerRoman" to "i, ii, iii", "upperRoman" to "I, II, III", CHICAGO to "*, †, ‡")
    private val ODF = mapOf("decimal" to "1", "lowerLetter" to "a", "upperLetter" to "A", "lowerRoman" to "i", "upperRoman" to "I")

    /** Note [n]'s number in [format]. */
    fun number(n: Int, format: String): String =
        if (format == CHICAGO) "*†‡§"[(n - 1) % 4].toString().repeat((n - 1) / 4 + 1) else DocStyles.Plain.number(n, format).ifEmpty { "$n" }

    /** The file's formats (footnotes, endnotes), Word's names; the defaults where it says none. */
    fun read(parts: Map<String, ByteArray>, kind: OfficeKind): Pair<String, String> = runCatching {
        if (kind == OfficeKind.Odt) {
            val styles = parts["styles.xml"]?.let { String(it, Charsets.UTF_8) }.orEmpty()
            fun odf(cls: String, or: String) = Regex("""<text:notes-configuration\b[^>]*>""").findAll(styles).map { it.value }.firstOrNull { "text:note-class=\"$cls\"" in it }
                ?.let { Regex("""style:num-format="([^"]*)"""").find(it)?.groupValues?.get(1) }?.let { f -> ODF.entries.firstOrNull { it.value == f }?.key } ?: or
            return odf("footnote", FOOT) to odf("endnote", END)
        }
        val doc = String(parts.getValue(documentPath(parts)), Charsets.UTF_8)
        val settings = parts[documentPath(parts).substringBeforeLast('/') + "/settings.xml"]?.let { String(it, Charsets.UTF_8) }.orEmpty()
        fun word(tag: String, or: String): String {
            fun inside(xml: String) = Regex("""<w:$tag>([\s\S]*?)</w:$tag>""").findAll(xml).lastOrNull()?.groupValues?.get(1)
                ?.let { Regex("""<w:numFmt w:val="([^"]+)"""").find(it)?.groupValues?.get(1) }
            // the document's last section (the body's own) outranks the settings
            return (inside(Regex("""<w:sectPr\b[\s\S]*?</w:sectPr>""").findAll(doc).lastOrNull()?.value.orEmpty()) ?: inside(settings))?.takeIf { f -> ALL.any { it.first == f } } ?: or
        }
        word("footnotePr", FOOT) to word("endnotePr", END)
    }.getOrDefault(FOOT to END)

    /** Word: each format into the settings (made if missing), and into every section that says its own. */
    fun docx(parts: LinkedHashMap<String, ByteArray>, doc: OfficeDoc) {
        val docPath = documentPath(parts)
        if (doc.footFormat == FOOT && doc.endFormat == END && read(parts, OfficeKind.Docx) == FOOT to END) return
        fun put(xml: String, tag: String, format: String): String {
            val numFmt = """<w:numFmt w:val="$format"/>"""
            val found = Regex("""<w:$tag>([\s\S]*?)</w:$tag>|<w:$tag/>""").find(xml) ?: return xml
            val inner = found.groupValues[1].replace(Regex("""<w:numFmt\b[^>]*/>"""), "")
            // after its position, before its start, restart and separators (the schema's order)
            val at = Regex("""^<w:pos\b[^>]*/>""").find(inner)?.range?.last?.plus(1) ?: 0
            return xml.replaceRange(found.range, "<w:$tag>" + inner.substring(0, at) + numFmt + inner.substring(at) + "</w:$tag>")
        }
        // a section's own: where it has one already, and in the body's last section as Word writes it (LibreOffice reads it there)
        var body = String(parts.getValue(docPath), Charsets.UTF_8)
        body = Regex("""<w:sectPr\b[\s\S]*?</w:sectPr>""").replace(body) { m ->
            put(put(m.value, "footnotePr", doc.footFormat), "endnotePr", doc.endFormat)
        }
        // the last one (a section break's earlier ones are in their paragraphs)
        Regex("""<w:sectPr\b[^>]*>[\s\S]*?</w:sectPr>""").findAll(body).lastOrNull()?.takeIf { body.substring(it.range.last + 1).trimStart().startsWith("</w:body>") }?.let { m ->
            var sect = m.value
            for ((tag, format, default) in listOf(Triple("footnotePr", doc.footFormat, FOOT), Triple("endnotePr", doc.endFormat, END))) {
                if (format == default || "<w:$tag" in sect) continue
                // after the headers' and footers' references, footnotePr before endnotePr (the schema's order)
                val at = Regex("""<w:(?:headerReference|footerReference|footnotePr)\b[^>]*?(?:/>|>[\s\S]*?</w:(?:footnotePr)>)""").findAll(sect).lastOrNull()?.range?.last?.plus(1)
                    ?: (sect.indexOf('>') + 1)
                sect = sect.substring(0, at) + """<w:$tag><w:numFmt w:val="$format"/></w:$tag>""" + sect.substring(at)
            }
            body = body.replaceRange(m.range, sect)
        }
        parts[docPath] = body.toByteArray()
        val path = PageDesign.settings(parts, docPath)
        var s = String(parts.getValue(path), Charsets.UTF_8)
        for ((tag, format, default) in listOf(Triple("footnotePr", doc.footFormat, FOOT), Triple("endnotePr", doc.endFormat, END))) {
            if (Regex("""<w:$tag\b""").containsMatchIn(s)) { s = put(s, tag, format); continue }
            if (format == default) continue
            val el = """<w:$tag><w:numFmt w:val="$format"/></w:$tag>"""
            // footnotePr, then endnotePr, before compat and what follows it
            val later = Regex("""<w:(${if (tag == "footnotePr") "endnotePr|" else ""}compat|docVars|rsids|mathPr|attachedSchema|themeFontLang|clrSchemeMapping|doNotIncludeSubdocsInStats|doNotAutoCompressPictures|forceUpgrade|captions|readModeInkLockDown|smartTagType|schemaLibrary|shapeDefaults|doNotEmbedSmartTags|decimalSymbol|listSeparator)\b""").find(s)?.range?.first
                ?: s.lastIndexOf("</w:settings>").takeIf { it >= 0 }
            s = if (later != null) s.substring(0, later) + el + s.substring(later) else s.replace(Regex("""<w:settings\b([^>]*)/>"""), "<w:settings$1>$el</w:settings>")
        }
        parts[path] = s.toByteArray()
    }

    /** LibreOffice: each format on its notes' configuration in styles.xml (one made where there is none). */
    fun odt(parts: LinkedHashMap<String, ByteArray>, doc: OfficeDoc) {
        var styles = parts["styles.xml"]?.let { String(it, Charsets.UTF_8) } ?: return
        for ((cls, format, default) in listOf(Triple("footnote", doc.footFormat, FOOT), Triple("endnote", doc.endFormat, END))) {
            val f = ODF[format] ?: ODF.getValue(default)
            val found = Regex("""<text:notes-configuration\b[^>]*>""").findAll(styles).firstOrNull { "text:note-class=\"$cls\"" in it.value }
            styles = if (found != null) styles.replaceRange(found.range, found.value.replace(Regex("""\sstyle:num-format="[^"]*""""), "").replaceFirst("<text:notes-configuration", "<text:notes-configuration style:num-format=\"$f\""))
                else if (format == default || "</office:styles>" !in styles) styles
                else styles.replace("</office:styles>", """<text:notes-configuration text:note-class="$cls" style:num-format="$f" text:start-value="0"${if (cls == "footnote") " text:footnotes-position=\"page\" text:start-numbering-at=\"document\"" else ""}/></office:styles>""")
        }
        parts["styles.xml"] = styles.toByteArray()
    }
}
