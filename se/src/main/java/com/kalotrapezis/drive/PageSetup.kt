package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Layout › Margins, Orientation, Size (asked 2026-09-29, Word's Layout tab): the page of every section set at once, in
 * the file's own words — Word's pgSz/pgMar in each sectPr, ODT's page layouts. Lengths in points.
 */
internal object PageSetup {
    /** Paper sizes, portrait, in points. */
    val SIZES = listOf("A4" to (595.3f to 841.9f), "Letter" to (612f to 792f), "Legal" to (612f to 1008f), "A5" to (419.5f to 595.3f), "A3" to (841.9f to 1190.6f))
    /** Word's margin presets (top, bottom, left, right); Normal is also APA's 1 inch. */
    val MARGINS = listOf(
        R.string.words_margins_normal to floatArrayOf(72f, 72f, 72f, 72f),
        R.string.words_margins_narrow to floatArrayOf(36f, 36f, 36f, 36f),
        R.string.words_margins_moderate to floatArrayOf(72f, 72f, 54f, 54f),
        R.string.words_margins_wide to floatArrayOf(72f, 72f, 144f, 144f),
        R.string.words_margins_25cm to floatArrayOf(70.87f, 70.87f, 70.87f, 70.87f),
    )
    fun sizeName(p: DocPage) = SIZES.firstOrNull { (_, s) -> near(minOf(p.width, p.height), s.first) && near(maxOf(p.width, p.height), s.second) }?.first
    fun landscape(p: DocPage) = p.width > p.height
    private fun near(a: Float, b: Float) = abs(a - b) < 3f

    private fun tw(pt: Float) = (pt * 20).roundToInt().toString()

    /** Every section's page size and margins in a Word document (header/footer distances and the gutter as they were). */
    fun docx(source: Map<String, ByteArray>, page: DocPage): LinkedHashMap<String, ByteArray> {
        val parts = LinkedHashMap(source)
        val docPath = documentPath(parts)
        var xml = String(parts.getValue(docPath), Charsets.UTF_8)
        val sections = Regex("""<w:sectPr\b[^>]*?(?:/>|>[\s\S]*?</w:sectPr>)""")
        if (!sections.containsMatchIn(xml)) xml = xml.replace("</w:body>", "<w:sectPr/></w:body>")
        xml = sections.replace(xml) { docxSection(it.value, page) }
        parts[docPath] = xml.toByteArray()
        return parts
    }

    /** One Word section (its sectPr) set to [page]'s size, margins and columns (header/footer distances and gutter kept). */
    fun docxSection(sect: String, page: DocPage): String {
        val size = """<w:pgSz w:w="${tw(page.width)}" w:h="${tw(page.height)}"${if (landscape(page)) " w:orient=\"landscape\"" else ""}/>"""
        fun margins(old: String?): String {
            val keep = old?.let { o -> Regex("""\s(w:(?:header|footer|gutter))="([^"]*)"""").findAll(o).associate { it.groupValues[1] to it.groupValues[2] } }.orEmpty()
            return """<w:pgMar w:top="${tw(page.top)}" w:right="${tw(page.right)}" w:bottom="${tw(page.bottom)}" w:left="${tw(page.left)}" """ +
                """w:header="${keep["w:header"] ?: tw(page.header)}" w:footer="${keep["w:footer"] ?: tw(page.footer)}" w:gutter="${keep["w:gutter"] ?: "0"}"/>"""
        }
        fun section(sect: String): String {
            val self = sect.endsWith("/>") && !sect.contains("</w:sectPr>")
            val open = if (self) sect.dropLast(2) + ">" else sect.substring(0, sect.indexOf('>') + 1)
            var inner = if (self) "" else sect.substring(sect.indexOf('>') + 1, sect.length - "</w:sectPr>".length)
            val oldMar = Regex("""<w:pgMar\b[^>]*/>""").find(inner)?.value
            inner = inner.replace(Regex("""<w:pgSz\b[^>]*/>"""), "").replace(Regex("""<w:pgMar\b[^>]*/>"""), "")
                .replace(Regex("""<w:cols\b[^>]*?(?:/>|>[\s\S]*?</w:cols>)"""), "")
            // In Word's order: after the header/footer references, footnote/endnote settings and the section's type…
            val later = Regex("""<w:(paperSrc|pgBorders|lnNumType|pgNumType|cols|formProt|vAlign|noEndnote|titlePg|textDirection|bidi|rtlGutter|docGrid|printerSettings)\b""").find(inner)?.range?.first ?: inner.length
            inner = inner.substring(0, later) + size + margins(oldMar) + inner.substring(later)
            // …and the columns after the page numbering, before the rest.
            val cols = """<w:cols w:num="${page.columns}" w:space="${tw(page.columnGap)}"/>"""
            val beforeCols = Regex("""<w:(formProt|vAlign|noEndnote|titlePg|textDirection|bidi|rtlGutter|docGrid|printerSettings)\b""").find(inner)?.range?.first ?: inner.length
            return open + inner.substring(0, beforeCols) + cols + inner.substring(beforeCols) + "</w:sectPr>"
        }
        return section(sect)
    }

    /**
     * Layout › Line numbers: [restart] ("continuous", "newPage", "newSection") or none (null) — in every section of a
     * Word document (w:lnNumType, in Word's place: after the page borders, before the page numbering); in an
     * OpenDocument for the whole of it (LibreOffice's line numbering, in styles.xml; no "each section" there).
     */
    fun lineNumbers(source: Map<String, ByteArray>, kind: OfficeKind, restart: String?): LinkedHashMap<String, ByteArray> {
        val parts = LinkedHashMap(source)
        if (kind == OfficeKind.Odt) {
            var xml = String(parts.getValue("styles.xml"), Charsets.UTF_8).replace(Regex("""<text:linenumbering-configuration\b[^>]*?(?:/>|>[\s\S]*?</text:linenumbering-configuration>)"""), "")
            val conf = """<text:linenumbering-configuration text:number-lines="${restart != null}" text:offset="0.25in" style:num-format="1" text:number-position="left" text:increment="1" text:count-empty-lines="true" text:count-in-text-boxes="false" text:restart-on-page="${restart == "newPage"}"/>"""
            xml = if ("</office:styles>" in xml) xml.replace("</office:styles>", "$conf</office:styles>") else xml.replace(Regex("""<office:styles\s*/>"""), "<office:styles>$conf</office:styles>")
            parts["styles.xml"] = xml.toByteArray()
            return parts
        }
        val docPath = documentPath(parts)
        var xml = String(parts.getValue(docPath), Charsets.UTF_8)
        fun section(sect: String): String {
            val self = sect.endsWith("/>") && !sect.contains("</w:sectPr>")
            val open = if (self) sect.dropLast(2) + ">" else sect.substring(0, sect.indexOf('>') + 1)
            var inner = if (self) "" else sect.substring(sect.indexOf('>') + 1, sect.length - "</w:sectPr>".length)
            inner = inner.replace(Regex("""<w:lnNumType\b[^>]*/>"""), "")
            if (restart != null) {
                val at = Regex("""<w:(pgNumType|cols|formProt|vAlign|noEndnote|titlePg|textDirection|bidi|rtlGutter|docGrid|printerSettings)\b""").find(inner)?.range?.first ?: inner.length
                inner = inner.substring(0, at) + """<w:lnNumType w:countBy="1" w:restart="$restart"/>""" + inner.substring(at)
            }
            return open + inner + "</w:sectPr>"
        }
        val sections = Regex("""<w:sectPr\b[^>]*?(?:/>|>[\s\S]*?</w:sectPr>)""")
        if (!sections.containsMatchIn(xml)) xml = xml.replace("</w:body>", "<w:sectPr/></w:body>")
        xml = sections.replace(xml) { section(it.value) }
        parts[docPath] = xml.toByteArray()
        return parts
    }

    /**
     * The same in an OpenDocument: every page layout. ODF's top margin is where the header starts; the text starts
     * below the header's band, so that band is taken off to put the text where it is asked for ([now]: as it is now).
     */
    fun odt(source: Map<String, ByteArray>, page: DocPage, now: DocPage): LinkedHashMap<String, ByteArray> {
        val parts = LinkedHashMap(source)
        var xml = String(parts.getValue("styles.xml"), Charsets.UTF_8)
        fun pt(v: Float) = "${(v * 100).roundToInt() / 100f}pt"
        val top = (page.top - (now.top - now.header)).coerceAtLeast(5.7f)
        val bottom = (page.bottom - (now.bottom - now.footer)).coerceAtLeast(5.7f)
        val set = mapOf("fo:page-width" to pt(page.width), "fo:page-height" to pt(page.height), "style:print-orientation" to if (landscape(page)) "landscape" else "portrait",
            "fo:margin-top" to pt(top), "fo:margin-bottom" to pt(bottom), "fo:margin-left" to pt(page.left), "fo:margin-right" to pt(page.right))
        val columns = if (page.columns > 1) """<style:columns fo:column-count="${page.columns}" fo:column-gap="${pt(page.columnGap)}"/>""" else ""
        xml = Regex("""<style:page-layout-properties\b[^>]*?(?:/>|>[\s\S]*?</style:page-layout-properties>)""").replace(xml) { m ->
            val self = m.value.endsWith("/>") && m.value.indexOf('>') == m.value.length - 1
            var tag = m.value.substring(0, m.value.indexOf('>')).removeSuffix("/")
            for ((k, v) in set) tag = if (Regex("""\s$k="""").containsMatchIn(tag)) tag.replace(Regex("""\s$k="[^"]*""""), " $k=\"$v\"") else "$tag $k=\"$v\""
            // Its columns: a child, after a background picture and before the footnote separator (ODF's order).
            val inner = (if (self) "" else m.value.substring(m.value.indexOf('>') + 1, m.value.length - "</style:page-layout-properties>".length))
                .replace(Regex("""<style:columns\b[^>]*?(?:/>|>[\s\S]*?</style:columns>)"""), "")
            val at = Regex("""<style:footnote-sep\b""").find(inner)?.range?.first ?: inner.length
            "$tag>" + inner.substring(0, at) + columns + inner.substring(at) + "</style:page-layout-properties>"
        }
        parts["styles.xml"] = xml.toByteArray()
        return parts
    }
}

/** Layout › Margins / Size: the page as it will be, in the chosen unit. */
@Composable
internal fun PageSetupDialog(now: DocPage, dismiss: () -> Unit, apply: (DocPage) -> Unit) {
    var p by remember { mutableStateOf(now) }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 640.dp).systemBarsPadding().imePadding().padding(12.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.words_page_setup), style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().shadedScroll().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Section(stringResource(R.string.words_margins)) {
                        PageSetup.MARGINS.forEach { (name, m) ->
                            Choice(stringResource(name), abs(p.top - m[0]) < 1f && abs(p.bottom - m[1]) < 1f && abs(p.left - m[2]) < 1f && abs(p.right - m[3]) < 1f) {
                                p = p.copy(top = m[0], bottom = m[1], left = m[2], right = m[3])
                            }
                        }
                    }
                    // The four, as numbers (each keyed to the page, so a preset shows at once).
                    androidx.compose.runtime.key(p.top, p.bottom, p.left, p.right) {
                        Section(stringResource(R.string.words_custom_unit, WriterUnits.name)) {
                            CmField(stringResource(R.string.words_top), p.top) { p = p.copy(top = it) }
                            CmField(stringResource(R.string.words_bottom), p.bottom) { p = p.copy(bottom = it) }
                            CmField(stringResource(R.string.words_left), p.left) { p = p.copy(left = it) }
                            CmField(stringResource(R.string.words_right), p.right) { p = p.copy(right = it) }
                        }
                    }
                    Section(stringResource(R.string.words_orientation)) {
                        Choice(stringResource(R.string.words_portrait), !PageSetup.landscape(p)) { if (PageSetup.landscape(p)) p = p.copy(width = p.height, height = p.width) }
                        Choice(stringResource(R.string.words_landscape), PageSetup.landscape(p)) { if (!PageSetup.landscape(p)) p = p.copy(width = p.height, height = p.width) }
                    }
                    Section(stringResource(R.string.words_size)) {
                        PageSetup.SIZES.forEach { (name, s) ->
                            Choice(name, PageSetup.sizeName(p) == name) {
                                p = if (PageSetup.landscape(p)) p.copy(width = s.second, height = s.first) else p.copy(width = s.first, height = s.second)
                            }
                        }
                        if (PageSetup.sizeName(p) == null) Choice("${WriterUnits.text(p.width)} × ${WriterUnits.text(p.height)} ${WriterUnits.name}", true) {}
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) }
                    TextButton(enabled = p.textWidth > 72f, onClick = { apply(p) }) { Text(stringResource(R.string.common_ok)) }
                }
            }
        }
    }
}
