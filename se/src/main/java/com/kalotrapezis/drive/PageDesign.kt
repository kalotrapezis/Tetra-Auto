package com.kalotrapezis.drive

/**
 * Design › Page colour and Page borders (Word's Design tab, 2026-09-30), written in each format's own words: Word's
 * w:background (shown once settings say displayBackgroundShape) and a pgBorders box in every section, 24 pt in from
 * the page's edges as Word's Page Borders › Box puts it; an OpenDocument's page layouts' background colour and border
 * (LibreOffice draws it round the margins, the text 4 pt inside it). Null takes each away.
 */
internal object PageDesign {
    private fun hex(c: Int) = "%06X".format(c and 0xFFFFFF)

    fun docx(source: Map<String, ByteArray>, color: Int?, border: Border?, sides: String = "tlbr"): LinkedHashMap<String, ByteArray> {
        val parts = LinkedHashMap(source)
        val docPath = documentPath(parts)
        var xml = String(parts.getValue(docPath), Charsets.UTF_8)
        xml = xml.replace(Regex("""<w:background\b[^>]*?(?:/>|>[\s\S]*?</w:background>)"""), "")
        if (color != null) Regex("""<w:document\b[^>]*>""").find(xml)?.let { m -> xml = xml.replaceRange(m.range.last + 1, m.range.last + 1, """<w:background w:color="${hex(color)}"/>""") }
        xml = Regex("""<w:sectPr\b[^>]*?(?:/>|>[\s\S]*?</w:sectPr>)""").replace(xml) { m -> section(m.value, border, sides) }
        parts[docPath] = xml.toByteArray()
        // Word draws the page's colour only when its settings say so.
        val settingsPath = if (color != null) settings(parts, docPath) else docPath.substringBeforeLast('/') + "/settings.xml"
        parts[settingsPath]?.let { String(it, Charsets.UTF_8) }?.takeIf { color != null && "<w:displayBackgroundShape" !in it }?.let { s ->
            // In the schema's order: after writeProtection, view and zoom, before the rest.
            val after = Regex("""<w:(?:writeProtection|view|zoom)\b[^>]*?(?:/>|>[\s\S]*?</w:(?:writeProtection|view|zoom)>)""").findAll(s).lastOrNull()?.range?.last
                ?: Regex("""<w:settings\b[^>]*>""").find(s)?.range?.last ?: return@let
            parts[settingsPath] = (s.substring(0, after + 1) + "<w:displayBackgroundShape/>" + s.substring(after + 1)).toByteArray()
        }
        return parts
    }

    /** A Word document's settings part — made, with its relationship and type, when it has none (Writer's own new one). */
    fun settings(parts: MutableMap<String, ByteArray>, docPath: String): String {
        val settingsPath = docPath.substringBeforeLast('/') + "/settings.xml"
        if (settingsPath !in parts) {
            // A document without settings (Writer's own new one): a settings part, its relationship and its type.
            parts[settingsPath] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"></w:settings>""".toByteArray()
            val relsPath = relsPath(docPath)
            val rels = parts[relsPath]?.let { String(it, Charsets.UTF_8) } ?: """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"></Relationships>"""
            val id = generateSequence(1) { it + 1 }.map { "rIdTetraSettings$it" }.first { "\"$it\"" !in rels }
            parts[relsPath] = rels.replace("</Relationships>", """<Relationship Id="$id" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/></Relationships>""").toByteArray()
            parts["[Content_Types].xml"] = String(parts.getValue("[Content_Types].xml"), Charsets.UTF_8).replace("</Types>",
                """<Override PartName="/$settingsPath" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/></Types>""").toByteArray()
        }
        return settingsPath
    }

    /**
     * Layout › Hyphenation (automatic or none): Word's settings (w:autoHyphenation, in the schema's place — after the
     * default tab stop, before the hyphenation zone and the rest); LibreOffice's fo:hyphenate on its paragraph styles.
     */
    fun hyphenation(source: Map<String, ByteArray>, kind: OfficeKind, on: Boolean): LinkedHashMap<String, ByteArray> {
        val parts = LinkedHashMap(source)
        if (kind == OfficeKind.Odt) {
            for (name in listOf("styles.xml", "content.xml")) {
                var xml = parts[name]?.let { String(it, Charsets.UTF_8) } ?: continue
                xml = xml.replace(Regex("""\sfo:hyphenate="[^"]*""""), " fo:hyphenate=\"$on\"")
                // The default paragraph style says it for all that do not say otherwise.
                if (name == "styles.xml") xml = Regex("""<style:default-style style:family="paragraph">([\s\S]*?)</style:default-style>""").replace(xml) { m ->
                    if ("fo:hyphenate" in m.value) m.value
                    else if ("<style:text-properties" in m.value) m.value.replaceFirst("<style:text-properties", "<style:text-properties fo:hyphenate=\"$on\" fo:hyphenation-remain-char-count=\"2\" fo:hyphenation-push-char-count=\"2\"")
                    else m.value.replace("</style:default-style>", "<style:text-properties fo:hyphenate=\"$on\"/></style:default-style>")
                }
                parts[name] = xml.toByteArray()
            }
            return parts
        }
        val path = settings(parts, documentPath(parts))
        var s = String(parts.getValue(path), Charsets.UTF_8).replace(Regex("""<w:autoHyphenation\b[^>]*/>"""), "")
        if (on) {
            val later = Regex("""<w:(${AFTER_HYPHENATION})\b""").find(s)?.range?.first
                ?: s.lastIndexOf("</w:settings>").takeIf { it >= 0 }
            s = if (later != null) s.substring(0, later) + "<w:autoHyphenation/>" + s.substring(later) else s.replace(Regex("""<w:settings\b([^>]*)/>"""), "<w:settings$1><w:autoHyphenation/></w:settings>")
        }
        parts[path] = s.toByteArray()
        return parts
    }

    /** Word's settings that come after its automatic hyphenation, in the schema's order. */
    private const val AFTER_HYPHENATION = """consecutiveHyphenLimit|hyphenationZone|doNotHyphenateCaps|showEnvelope|summaryLength|clickAndTypeStyle|defaultTableStyle|evenAndOddHeaders|bookFold\w*|drawingGrid\w*|display\w*DrawingGridEvery|doNotUseMarginsForDrawingGridOrigin|doNotShadeFormData|noPunctuationKerning|characterSpacingControl|printTwoOnOne|strictFirstAndLastChars|noLineBreaks\w*|savePreviewPicture|doNotValidateAgainstSchema|saveInvalidXml|ignoreMixedContent|alwaysShowPlaceholderText|doNotDemarcateInvalidXml|saveXmlDataOnly|useXSLTWhenSaving|saveThroughXslt|showXMLTags|alwaysMergeEmptyNamespace|updateFields|hdrShapeDefaults|footnotePr|endnotePr|compat|docVars|rsids|mathPr|attachedSchema|themeFontLang|clrSchemeMapping|doNotIncludeSubdocsInStats|doNotAutoCompressPictures|forceUpgrade|captions|readModeInkLockDown|smartTagType|schemaLibrary|shapeDefaults|doNotEmbedSmartTags|decimalSymbol|listSeparator"""

    /** Whether a Word document says to track changes (its settings' w:trackRevisions): Review › Track changes comes on when it opens. */
    fun tracksRevisions(bytes: ByteArray?): Boolean = runCatching {
        val parts = OfficeFormats.unzip(bytes!!)
        parts[documentPath(parts).substringBeforeLast('/') + "/settings.xml"]?.let { Regex("""<w:trackRevisions\b([^>]*)/>""").find(String(it, Charsets.UTF_8)) }
            ?.let { m -> Regex("""w:val="(0|false|off)"""").find(m.groupValues[1]) == null } ?: false
    }.getOrDefault(false)

    /** Review › Track changes, remembered in a Word document as Word does (w:trackRevisions, in the schema's place: before the tracking options, the protection and the default tab stop). */
    fun trackRevisions(source: Map<String, ByteArray>, on: Boolean): LinkedHashMap<String, ByteArray> {
        val parts = LinkedHashMap(source)
        val path = settings(parts, documentPath(parts))
        var s = String(parts.getValue(path), Charsets.UTF_8).replace(Regex("""<w:trackRevisions\b[^>]*/>"""), "")
        if (on) {
            val later = Regex("""<w:(doNotTrackMoves|doNotTrackFormatting|documentProtection|autoFormatOverride|styleLockTheme|styleLockQFSet|defaultTabStop|autoHyphenation|$AFTER_HYPHENATION)\b""").find(s)?.range?.first
                ?: s.lastIndexOf("</w:settings>").takeIf { it >= 0 }
            s = if (later != null) s.substring(0, later) + "<w:trackRevisions/>" + s.substring(later) else s.replace(Regex("""<w:settings\b([^>]*)/>"""), "<w:settings$1><w:trackRevisions/></w:settings>")
        }
        parts[path] = s.toByteArray()
        return parts
    }

    /** Word's names for the border's line, and LibreOffice's. */
    private fun wordStyle(style: String) = when (style) { "double", "dotted", "dashed" -> style; else -> "single" }
    private fun odfStyle(style: String) = when (style) { "double", "dotted", "dashed" -> style; else -> "solid" }

    private fun section(sect: String, border: Border?, sides: String): String {
        val self = sect.endsWith("/>") && !sect.contains("</w:sectPr>")
        val open = if (self) sect.dropLast(2) + ">" else sect.substring(0, sect.indexOf('>') + 1)
        var inner = if (self) "" else sect.substring(sect.indexOf('>') + 1, sect.length - "</w:sectPr>".length)
        inner = inner.replace(Regex("""<w:pgBorders\b[^>]*?(?:/>|>[\s\S]*?</w:pgBorders>)"""), "")
        if (border != null && sides.isNotEmpty()) {
            // A double line's sz is each of its lines (Word draws two and the gap between, three times as wide).
            val sz = ((if (border.style == "double") border.width / 3 else border.width) * 8).toInt().coerceIn(2, 96)
            val c = if (border.color == 0) "auto" else hex(border.color)
            val box = """<w:pgBorders w:offsetFrom="page">""" + listOf("top", "left", "bottom", "right").filter { it[0] in sides }
                .joinToString("") { """<w:$it w:val="${wordStyle(border.style)}" w:sz="$sz" w:space="24" w:color="$c"/>""" } + "</w:pgBorders>"
            // Word's order: after the page's size, margins and paper source, before line numbers and the rest.
            val at = Regex("""<w:(lnNumType|pgNumType|cols|formProt|vAlign|noEndnote|titlePg|textDirection|bidi|rtlGutter|docGrid|printerSettings)\b""").find(inner)?.range?.first ?: inner.length
            inner = inner.substring(0, at) + box + inner.substring(at)
        }
        return "$open$inner</w:sectPr>"
    }

    fun odt(source: Map<String, ByteArray>, color: Int?, border: Border?, sides: String = "tlbr"): LinkedHashMap<String, ByteArray> {
        val parts = LinkedHashMap(source)
        var xml = String(parts.getValue("styles.xml"), Charsets.UTF_8)
        xml = Regex("""<style:page-layout-properties\b[^>]*>""").replace(xml) { m ->
            val self = m.value.endsWith("/>")
            var tag = m.value.removeSuffix("/>").removeSuffix(">")
            tag = tag.replace(Regex("""\s(fo:background-color|fo:border(?:-top|-bottom|-left|-right)?|style:border-line-width(?:-top|-bottom|-left|-right)?|fo:padding(?:-top|-bottom|-left|-right)?|draw:fill|draw:fill-color)="[^"]*""""), "")
            if (color != null) tag += """ fo:background-color="#${hex(color).lowercase()}" draw:fill="solid" draw:fill-color="#${hex(color).lowercase()}""""
            if (border != null && sides.isNotEmpty()) {
                val line = "${"%.2f".format(java.util.Locale.ROOT, border.width)}pt ${odfStyle(border.style)} #${if (border.color == 0) "000000" else hex(border.color).lowercase()}"
                val third = "%.2fpt".format(java.util.Locale.ROOT, border.width / 3)
                for ((side, c) in listOf("top" to 't', "bottom" to 'b', "left" to 'l', "right" to 'r')) if (c in sides) {
                    tag += """ fo:border-$side="$line" fo:padding-$side="4pt""""
                    if (border.style == "double") tag += """ style:border-line-width-$side="$third $third $third""""
                }
            }
            tag + if (self) "/>" else ">"
        }
        parts["styles.xml"] = xml.toByteArray()
        return parts
    }
}
