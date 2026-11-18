package com.kalotrapezis.drive

import org.w3c.dom.Element
import kotlin.math.roundToInt

/**
 * A paragraph style changed from Writer (asked 2026-09-27: "set everything once, then type" — a thesis in its
 * guideline's font, size and spacing): what is set, null where it stays as the style has it. [color] -1: automatic.
 */
internal data class StyleChange(
    val font: String? = null, val sizePt: Float? = null, val bold: Boolean? = null, val italic: Boolean? = null, val color: Int? = null,
    val align: DocAlign? = null, val line: Float? = null, val before: Float? = null, val after: Float? = null,
    val left: Float? = null, val firstLine: Float? = null,
)

/**
 * The style's own definition changed in the document's styles (Word's styles.xml, LibreOffice's styles.xml), so every
 * paragraph with it changes, in Word and LibreOffice too. A style the document has not got yet (Title, a heading) is
 * added first. Everything else in the definition stays as the file had it.
 */
internal object StyleEdit {
    private val STYLE_ORDER = listOf("name", "aliases", "basedOn", "next", "link", "autoRedefine", "hidden", "uiPriority", "semiHidden",
        "unhideWhenUsed", "qFormat", "locked", "personal", "personalCompose", "personalReply", "rsid", "pPr", "rPr", "tblPr", "trPr", "tcPr", "tblStylePr")

    /** Word: the style with [id] (for a heading [heading] 1–6) in [styles] changed; [normal] the plain style's id. */
    fun docx(styles: String, id: String, heading: Int, normal: String?, c: StyleChange): String {
        var xml = styles
        if (find(xml, "w:style", "w:styleId", id) == null) {
            val add = if (heading > 0) docxHeadingStyle(heading, id, normal)
                else DOCX_BUILTIN[id]?.let { d -> if (normal != null) d.replace("NORMAL", normal) else d.replace(Regex("""<w:(basedOn|next) w:val="NORMAL"/>"""), "") }
                ?: """<w:style w:type="paragraph" w:customStyle="1" w:styleId="${OfficeFormats.esc(id)}"><w:name w:val="${OfficeFormats.esc(id)}"/>${normal?.let { "<w:basedOn w:val=\"$it\"/>" } ?: ""}<w:qFormat/></w:style>"""
            xml = xml.replace("</w:styles>", "$add</w:styles>")
        }
        val found = find(xml, "w:style", "w:styleId", id)!!
        val style = parse(found.value, """xmlns:w="$W"""")
        fun kid(parent: Element, name: String, order: List<String>): Element = parent.kids().firstOrNull { it.localName == name } ?: run {
            val e = parent.ownerDocument.createElementNS(W, "w:$name")
            val after = order.indexOf(name)
            parent.insertBefore(e, parent.kids().firstOrNull { order.indexOf(it.localName) > after }); e
        }
        fun set(e: Element, vararg attrs: Pair<String, String?>) = attrs.forEach { (k, v) -> if (v == null) e.removeAttributeNS(W, k) else e.setAttributeNS(W, "w:$k", v) }
        fun on(parent: Element, name: String, value: Boolean) = kid(parent, name, RPR_ORDER).let { set(it, "val" to if (value) null else "0") }
        fun tw(pt: Float) = (pt * 20).roundToInt().toString()
        if (c.align != null || c.line != null || c.before != null || c.after != null || c.left != null || c.firstLine != null) {
            val p = kid(style, "pPr", STYLE_ORDER)
            if (c.line != null || c.before != null || c.after != null) kid(p, "spacing", PPR_ORDER).let { s ->
                c.before?.let { set(s, "before" to tw(it), "beforeAutospacing" to null, "beforeLines" to null) }
                c.after?.let { set(s, "after" to tw(it), "afterAutospacing" to null, "afterLines" to null) }
                c.line?.let { set(s, "line" to (it * 240).roundToInt().toString(), "lineRule" to "auto") }
            }
            if (c.left != null || c.firstLine != null) kid(p, "ind", PPR_ORDER).let { i ->
                c.left?.let { set(i, "left" to tw(it), "start" to null, "leftChars" to null, "startChars" to null) }
                c.firstLine?.let { f -> if (f < 0) set(i, "hanging" to tw(-f), "firstLine" to null, "firstLineChars" to null, "hangingChars" to null)
                    else set(i, "firstLine" to tw(f), "hanging" to null, "firstLineChars" to null, "hangingChars" to null) }
            }
            c.align?.let { a -> set(kid(p, "jc", PPR_ORDER), "val" to when (a) { DocAlign.Start -> "left"; DocAlign.Center -> "center"; DocAlign.End -> "right"; DocAlign.Justify -> "both" }) }
        }
        if (c.font != null || c.sizePt != null || c.bold != null || c.italic != null || c.color != null) {
            val r = kid(style, "rPr", STYLE_ORDER)
            c.font?.let { f -> set(kid(r, "rFonts", RPR_ORDER), "ascii" to f, "hAnsi" to f, "cs" to f, "eastAsia" to f,
                "asciiTheme" to null, "hAnsiTheme" to null, "cstheme" to null, "eastAsiaTheme" to null) }
            c.sizePt?.let { pt -> val h = (pt * 2).roundToInt().toString(); set(kid(r, "sz", RPR_ORDER), "val" to h); set(kid(r, "szCs", RPR_ORDER), "val" to h) }
            c.bold?.let { on(r, "b", it); on(r, "bCs", it) }
            c.italic?.let { on(r, "i", it); on(r, "iCs", it) }
            c.color?.let { k -> set(kid(r, "color", RPR_ORDER), "val" to if (k < 0) "auto" else "%06X".format(k), "themeColor" to null, "themeShade" to null, "themeTint" to null) }
        }
        return xml.replaceRange(found.range, OfficeFormats.xml(style))
    }

    /** LibreOffice: the paragraph style [name] (a heading "Heading_20_n") in [styles] changed. */
    fun odt(styles: String, name: String, heading: Int, c: StyleChange): String {
        var xml = styles
        if (find(xml, "style:style", "style:name", name) == null) {
            val add = if (heading > 0) odtHeadingStyle(heading, if ("Text_20_body" in xml) "Text_20_body" else "Standard")
                else ODT_BUILTIN[name] ?: """<style:style style:name="${OfficeFormats.esc(name)}" style:family="paragraph" style:parent-style-name="Standard"/>"""
            xml = xml.replace("</office:styles>", "$add</office:styles>")
        }
        val found = find(xml, "style:style", "style:name", name)!!
        val style = parse(found.value, """xmlns:style="$STYLE" xmlns:fo="$FO"""")
        fun group(local: String): Element = style.kids().firstOrNull { it.localName == local } ?: run {
            val e = style.ownerDocument.createElementNS(STYLE, "style:$local")
            // Paragraph properties before text properties, as ODF lists them.
            style.insertBefore(e, if (local == "paragraph-properties") style.kids().firstOrNull { it.localName == "text-properties" } else null); e
        }
        fun pt(v: Float) = (if (v == v.roundToInt().toFloat()) v.roundToInt().toString() else "%.1f".format(java.util.Locale.ROOT, v)) + "pt"
        if (c.align != null || c.line != null || c.before != null || c.after != null || c.left != null || c.firstLine != null) group("paragraph-properties").let { p ->
            c.align?.let { p.setAttributeNS(FO, "fo:text-align", when (it) { DocAlign.Start -> "start"; DocAlign.Center -> "center"; DocAlign.End -> "end"; DocAlign.Justify -> "justify" }) }
            c.line?.let { p.setAttributeNS(FO, "fo:line-height", "${(it * 100).roundToInt()}%"); p.removeAttributeNS(STYLE, "line-height-at-least"); p.removeAttributeNS(STYLE, "line-spacing") }
            c.before?.let { p.setAttributeNS(FO, "fo:margin-top", pt(it)) }
            c.after?.let { p.setAttributeNS(FO, "fo:margin-bottom", pt(it)) }
            c.left?.let { p.setAttributeNS(FO, "fo:margin-left", pt(it)) }
            c.firstLine?.let { p.setAttributeNS(FO, "fo:text-indent", pt(it)); p.removeAttributeNS(STYLE, "auto-text-indent") }
        }
        if (c.font != null || c.sizePt != null || c.bold != null || c.italic != null || c.color != null) group("text-properties").let { t ->
            c.font?.let { t.removeAttributeNS(STYLE, "font-name"); t.setAttributeNS(FO, "fo:font-family", "'$it'") }
            c.sizePt?.let { t.setAttributeNS(FO, "fo:font-size", pt(it)) }
            c.bold?.let { t.setAttributeNS(FO, "fo:font-weight", if (it) "bold" else "normal") }
            c.italic?.let { t.setAttributeNS(FO, "fo:font-style", if (it) "italic" else "normal") }
            c.color?.let { k -> if (k < 0) { t.removeAttributeNS(FO, "color"); t.setAttributeNS(STYLE, "style:use-window-font-color", "true") } else t.setAttributeNS(FO, "fo:color", "#%06x".format(k)) }
        }
        return xml.replaceRange(found.range, OfficeFormats.xml(style))
    }

    /** An element [tag] whose [attr] is [value], as written in the file (styles do not nest). */
    private fun find(xml: String, tag: String, attr: String, value: String) =
        Regex("""<$tag\b[^>]*\s$attr="${Regex.escape(OfficeFormats.esc(value))}"[^>]*?(?:/>|>[\s\S]*?</$tag>)""").find(xml)
    private fun parse(xml: String, ns: String) = OfficeFormats.parse("<x $ns>$xml</x>".toByteArray()).documentElement.kids().first()
}

/** Word's heading style n, as Writer adds one a document has not got. */
internal fun docxHeadingStyle(n: Int, id: String, normal: String?) = """<w:style w:type="paragraph" w:styleId="$id"><w:name w:val="heading $n"/>${normal?.let { "<w:basedOn w:val=\"$it\"/><w:next w:val=\"$it\"/>" } ?: ""}<w:uiPriority w:val="9"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="240" w:after="80"/><w:outlineLvl w:val="${n - 1}"/></w:pPr><w:rPr><w:b/><w:sz w:val="${listOf(36, 30, 26, 24, 22, 22)[n - 1]}"/></w:rPr></w:style>"""
/** LibreOffice's likewise. */
internal fun odtHeadingStyle(n: Int, body: String) = """<style:style style:name="Heading_20_$n" style:display-name="Heading $n" style:family="paragraph" style:parent-style-name="Standard" style:next-style-name="$body" style:default-outline-level="$n" style:class="text"><style:paragraph-properties fo:margin-top="0.423cm" fo:margin-bottom="0.212cm" fo:keep-with-next="always"/><style:text-properties fo:font-size="${listOf("130%", "115%", "101%", "95%", "85%", "85%")[n - 1]}" fo:font-weight="bold"/></style:style>"""

/**
 * Design › Style sets (Word's Document Formatting gallery): Normal, Title and Headings 1–3 set together, each a
 * [StyleChange] by the style's name ("Normal", "Title", "Heading 1"…).
 */
internal enum class StyleSet(@androidx.annotation.StringRes val label: Int, val changes: Map<String, StyleChange>) {
    Basic(R.string.words_style_set_basic, mapOf(
        "Normal" to StyleChange(font = "Calibri", sizePt = 11f, color = -1, line = 1.08f, before = 0f, after = 8f),
        "Title" to StyleChange(font = "Calibri Light", sizePt = 28f, bold = false, color = -1, after = 0f),
        "Heading 1" to StyleChange(font = "Calibri Light", sizePt = 16f, bold = false, color = 0x2F5496, before = 12f, after = 0f),
        "Heading 2" to StyleChange(font = "Calibri Light", sizePt = 13f, bold = false, color = 0x2F5496, before = 2f, after = 0f),
        "Heading 3" to StyleChange(font = "Calibri Light", sizePt = 12f, bold = false, color = 0x1F3763, before = 2f, after = 0f))),
    Classic(R.string.words_style_set_classic, mapOf(
        "Normal" to StyleChange(font = "Times New Roman", sizePt = 12f, color = -1, line = 1.15f, before = 0f, after = 6f),
        "Title" to StyleChange(font = "Times New Roman", sizePt = 24f, bold = true, color = -1, align = DocAlign.Center, after = 12f),
        "Heading 1" to StyleChange(font = "Times New Roman", sizePt = 16f, bold = true, italic = false, color = -1, before = 18f, after = 6f),
        "Heading 2" to StyleChange(font = "Times New Roman", sizePt = 14f, bold = true, italic = false, color = -1, before = 12f, after = 6f),
        "Heading 3" to StyleChange(font = "Times New Roman", sizePt = 12f, bold = true, italic = true, color = -1, before = 12f, after = 3f))),
    Modern(R.string.words_style_set_modern, mapOf(
        "Normal" to StyleChange(font = "Arial", sizePt = 10.5f, color = 0x262626, line = 1.2f, before = 0f, after = 8f),
        "Title" to StyleChange(font = "Arial", sizePt = 26f, bold = true, color = 0x1F4E79, after = 6f),
        "Heading 1" to StyleChange(font = "Arial", sizePt = 15f, bold = true, italic = false, color = 0x1F4E79, before = 18f, after = 6f),
        "Heading 2" to StyleChange(font = "Arial", sizePt = 12.5f, bold = true, italic = false, color = 0x2E75B6, before = 12f, after = 4f),
        "Heading 3" to StyleChange(font = "Arial", sizePt = 11f, bold = true, italic = false, color = 0x404040, before = 10f, after = 2f))),
    Elegant(R.string.words_style_set_elegant, mapOf(
        "Normal" to StyleChange(font = "Georgia", sizePt = 11f, color = 0x333333, line = 1.3f, before = 0f, after = 10f),
        "Title" to StyleChange(font = "Georgia", sizePt = 30f, bold = false, italic = true, color = 0x7B2C2C, align = DocAlign.Center, after = 12f),
        "Heading 1" to StyleChange(font = "Georgia", sizePt = 17f, bold = false, italic = false, color = 0x7B2C2C, before = 20f, after = 6f),
        "Heading 2" to StyleChange(font = "Georgia", sizePt = 13.5f, bold = false, italic = true, color = 0x7B2C2C, before = 14f, after = 4f),
        "Heading 3" to StyleChange(font = "Georgia", sizePt = 12f, bold = true, italic = false, color = 0x333333, before = 10f, after = 2f))),
    Word2010(R.string.words_style_set_word2010, mapOf(
        "Normal" to StyleChange(font = "Calibri", sizePt = 11f, color = -1, line = 1.15f, before = 0f, after = 10f),
        "Title" to StyleChange(font = "Cambria", sizePt = 26f, bold = false, color = 0x17365D, after = 15f),
        "Heading 1" to StyleChange(font = "Cambria", sizePt = 14f, bold = true, italic = false, color = 0x365F91, before = 24f, after = 0f),
        "Heading 2" to StyleChange(font = "Cambria", sizePt = 13f, bold = true, italic = false, color = 0x4F81BD, before = 10f, after = 0f),
        "Heading 3" to StyleChange(font = "Cambria", sizePt = 11f, bold = true, italic = false, color = 0x4F81BD, before = 10f, after = 0f))),
    Word2003(R.string.words_style_set_word2003, mapOf(
        "Normal" to StyleChange(font = "Times New Roman", sizePt = 12f, color = -1, line = 1f, before = 0f, after = 0f),
        "Title" to StyleChange(font = "Arial", sizePt = 16f, bold = true, color = -1, align = DocAlign.Center, before = 12f, after = 3f),
        "Heading 1" to StyleChange(font = "Arial", sizePt = 16f, bold = true, italic = false, color = -1, before = 12f, after = 3f),
        "Heading 2" to StyleChange(font = "Arial", sizePt = 14f, bold = true, italic = true, color = -1, before = 12f, after = 3f),
        "Heading 3" to StyleChange(font = "Arial", sizePt = 13f, bold = true, italic = false, color = -1, before = 12f, after = 3f))),
    Centered(R.string.words_style_set_centred, mapOf(
        "Normal" to StyleChange(font = "Calibri", sizePt = 11f, color = -1, line = 1.15f, before = 0f, after = 8f),
        "Title" to StyleChange(font = "Calibri Light", sizePt = 30f, bold = false, color = 0x1F3864, align = DocAlign.Center, after = 12f),
        "Heading 1" to StyleChange(font = "Calibri Light", sizePt = 18f, bold = false, italic = false, color = 0x1F3864, align = DocAlign.Center, before = 18f, after = 6f),
        "Heading 2" to StyleChange(font = "Calibri Light", sizePt = 14f, bold = false, italic = false, color = 0x2F5496, align = DocAlign.Center, before = 12f, after = 4f),
        "Heading 3" to StyleChange(font = "Calibri", sizePt = 12f, bold = true, italic = false, color = 0x2F5496, align = DocAlign.Center, before = 10f, after = 2f))),
    Casual(R.string.words_style_set_casual, mapOf(
        "Normal" to StyleChange(font = "Trebuchet MS", sizePt = 11f, color = 0x3B3838, line = 1.25f, before = 0f, after = 8f),
        "Title" to StyleChange(font = "Trebuchet MS", sizePt = 28f, bold = true, color = 0xC55A11, after = 8f),
        "Heading 1" to StyleChange(font = "Trebuchet MS", sizePt = 16f, bold = true, italic = false, color = 0xC55A11, before = 16f, after = 4f),
        "Heading 2" to StyleChange(font = "Trebuchet MS", sizePt = 13f, bold = true, italic = false, color = 0x7F6000, before = 12f, after = 4f),
        "Heading 3" to StyleChange(font = "Trebuchet MS", sizePt = 11f, bold = true, italic = true, color = 0x7F6000, before = 10f, after = 2f))),
    Minimalist(R.string.words_style_set_minimalist, mapOf(
        "Normal" to StyleChange(font = "Arial", sizePt = 10f, color = 0x404040, line = 1.4f, before = 0f, after = 10f),
        "Title" to StyleChange(font = "Arial", sizePt = 22f, bold = false, color = 0x000000, after = 18f),
        "Heading 1" to StyleChange(font = "Arial", sizePt = 13f, bold = false, italic = false, color = 0x000000, before = 20f, after = 8f),
        "Heading 2" to StyleChange(font = "Arial", sizePt = 11f, bold = true, italic = false, color = 0x404040, before = 14f, after = 4f),
        "Heading 3" to StyleChange(font = "Arial", sizePt = 10f, bold = true, italic = false, color = 0x808080, before = 10f, after = 2f)));

    companion object {
        /** Design › Fonts as a set of fonts only: the headings and the title in its heading font, the text in its body font. */
        fun fontsOnly(f: ThemeFonts): Map<String, StyleChange> = listOf("Title", "Heading 1", "Heading 2", "Heading 3").associateWith { StyleChange(font = f.heading) } + ("Normal" to StyleChange(font = f.body))
        fun apply(changes: Map<String, StyleChange>, xml: String, kind: OfficeKind, styles: DocStyles): String {
            var out = xml
            val entries = styles.paragraphStyles()
            for ((name, edit) in changes) {
                val entry = entries.firstOrNull { it.name == name } ?: continue
                val key = styles.styleKey(entry) ?: continue
                out = if (kind == OfficeKind.Docx) StyleEdit.docx(out, key, entry.heading, styles.normalId, edit) else StyleEdit.odt(out, key, entry.heading, edit)
                // LibreOffice's plain paragraphs are "Standard" (Text body is based on it): both take Normal's look.
                if (kind == OfficeKind.Odt && name == "Normal" && key != "Standard") out = StyleEdit.odt(out, "Standard", 0, edit)
            }
            return out
        }
    }

    /** The document's styles part ([xml]) with this set's styles changed (those it has, and those it lacks added). */
    fun applyTo(xml: String, kind: OfficeKind, styles: DocStyles): String = apply(changes, xml, kind, styles)
}
