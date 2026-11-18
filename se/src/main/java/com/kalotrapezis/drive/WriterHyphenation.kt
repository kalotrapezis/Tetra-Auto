package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

/**
 * Layout › Hyphenation options (Word's): the hyphenation zone (how far from the right margin a word may be broken),
 * how many lines in a row may end in a hyphen, and whether words in CAPITALS are broken. Word's settings
 * (hyphenationZone, consecutiveHyphenLimit, doNotHyphenateCaps); LibreOffice's on its default paragraph style
 * (loext:hyphenation-zone, fo:hyphenation-ladder-count, loext:hyphenation-no-caps). The pages break words by Android's
 * own hyphenator, which takes none of them: they are for Word and LibreOffice.
 */
internal data class HyphenOptions(val zonePt: Float = 18f /* Word's 0.25" */, val limit: Int = 0 /* 0: no limit */, val caps: Boolean = true) {
    companion object {
        private fun settingsOf(parts: Map<String, ByteArray>) = parts[documentPath(parts).substringBeforeLast('/') + "/settings.xml"]?.let { String(it, Charsets.UTF_8) }.orEmpty()
        /** Where LibreOffice keeps them: the Standard paragraph style (Word's document-wide settings go there), else the default one. */
        private fun defaultStyle(styles: String) = Regex("""<style:style style:name="Standard"[^>]*?(?:/>|>[\s\S]*?</style:style>)""").find(styles)
            ?: Regex("""<style:default-style style:family="paragraph">[\s\S]*?</style:default-style>""").find(styles)

        fun read(parts: Map<String, ByteArray>, kind: OfficeKind): HyphenOptions = runCatching {
            if (kind == OfficeKind.Odt) {
                val d = defaultStyle(parts["styles.xml"]?.let { String(it, Charsets.UTF_8) }.orEmpty())?.value.orEmpty()
                fun a(name: String) = Regex("""\s$name="([^"]*)"""").find(d)?.groupValues?.get(1)
                // the zone: LibreOffice's own in twips (720), else a length
                return HyphenOptions(a("loext:hyphenation-zone")?.let { z -> z.toFloatOrNull()?.div(20f) ?: odfLength(z) } ?: 18f, a("fo:hyphenation-ladder-count")?.toIntOrNull() ?: 0, a("loext:hyphenation-no-caps") != "true")
            }
            val s = settingsOf(parts)
            fun v(tag: String) = Regex("""<w:$tag\b[^>]*w:val="([^"]*)"""").find(s)?.groupValues?.get(1)
            HyphenOptions(v("hyphenationZone")?.toFloatOrNull()?.div(20f) ?: 18f, v("consecutiveHyphenLimit")?.toIntOrNull() ?: 0,
                Regex("""<w:doNotHyphenateCaps\b([^>]*)/>""").find(s)?.let { m -> Regex("""w:val="(0|false|off)"""").containsMatchIn(m.groupValues[1]) } ?: true)
        }.getOrDefault(HyphenOptions())

        fun write(source: Map<String, ByteArray>, kind: OfficeKind, o: HyphenOptions): LinkedHashMap<String, ByteArray> {
            val parts = LinkedHashMap(source)
            if (kind == OfficeKind.Odt) {
                var styles = parts["styles.xml"]?.let { String(it, Charsets.UTF_8) } ?: return parts
                val m = defaultStyle(styles) ?: return parts
                val end = if (m.value.startsWith("<style:default-style")) "</style:default-style>" else "</style:style>"
                var d = m.value.replace(Regex("""\s(loext:hyphenation-zone|fo:hyphenation-ladder-count|loext:hyphenation-no-caps)="[^"]*""""), "")
                if (d.endsWith("/>")) d = d.removeSuffix("/>") + ">$end"
                val para = """ fo:hyphenation-ladder-count="${if (o.limit > 0) o.limit else "no-limit"}""""
                d = if ("<style:paragraph-properties" in d) d.replaceFirst("<style:paragraph-properties", "<style:paragraph-properties$para")
                    else d.replaceFirst(if ("<style:text-properties" in d) "<style:text-properties" else end, "<style:paragraph-properties$para/>" + if ("<style:text-properties" in d) "<style:text-properties" else end) // before the text's, as the schema says
                val text = """ loext:hyphenation-no-caps="${!o.caps}" loext:hyphenation-zone="${Math.round(o.zonePt * 20)}""""
                d = if ("<style:text-properties" in d) d.replaceFirst("<style:text-properties", "<style:text-properties$text")
                    else d.replace(end, "<style:text-properties$text/>$end")
                styles = styles.replaceRange(m.range, d)
                if ("xmlns:loext=" !in styles.substring(0, styles.indexOf('>', styles.indexOf("<office:document-styles"))))
                    styles = styles.replaceFirst("<office:document-styles", "<office:document-styles xmlns:loext=\"urn:org:documentfoundation:names:experimental:office:xmlns:loext:1.0\"")
                parts["styles.xml"] = styles.toByteArray()
                return parts
            }
            val path = PageDesign.settings(parts, documentPath(parts))
            var s = String(parts.getValue(path), Charsets.UTF_8).replace(Regex("""<w:(consecutiveHyphenLimit|hyphenationZone|doNotHyphenateCaps)\b[^>]*/>"""), "")
            val els = (if (o.limit > 0) """<w:consecutiveHyphenLimit w:val="${o.limit}"/>""" else "") +
                (if (o.zonePt != 18f) """<w:hyphenationZone w:val="${Math.round(o.zonePt * 20)}"/>""" else "") + (if (!o.caps) "<w:doNotHyphenateCaps/>" else "")
            if (els.isNotEmpty()) {
                // after the default tab stop and automatic hyphenation, before what follows them (the schema's order)
                val later = Regex("""<w:(showEnvelope|summaryLength|clickAndTypeStyle|defaultTableStyle|evenAndOddHeaders|bookFold\w*|drawingGrid\w*|display\w*DrawingGridEvery|doNotUseMarginsForDrawingGridOrigin|doNotShadeFormData|noPunctuationKerning|characterSpacingControl|printTwoOnOne|strictFirstAndLastChars|noLineBreaks\w*|savePreviewPicture|doNotValidateAgainstSchema|saveInvalidXml|ignoreMixedContent|alwaysShowPlaceholderText|doNotDemarcateInvalidXml|saveXmlDataOnly|useXSLTWhenSaving|saveThroughXslt|showXMLTags|alwaysMergeEmptyNamespace|updateFields|hdrShapeDefaults|footnotePr|endnotePr|compat|docVars|rsids|mathPr|attachedSchema|themeFontLang|clrSchemeMapping|doNotIncludeSubdocsInStats|doNotAutoCompressPictures|forceUpgrade|captions|readModeInkLockDown|smartTagType|schemaLibrary|shapeDefaults|doNotEmbedSmartTags|decimalSymbol|listSeparator)\b""").find(s)?.range?.first
                    ?: s.lastIndexOf("</w:settings>").takeIf { it >= 0 }
                s = if (later != null) s.substring(0, later) + els + s.substring(later) else s.replace(Regex("""<w:settings\b([^>]*)/>"""), "<w:settings$1>$els</w:settings>")
            }
            parts[path] = s.toByteArray()
            return parts
        }
    }
}

/** Layout › Hyphenation options: Word's three, as choices (a zone in inches, a limit of lines, capitals or not). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HyphenOptionsDialog(now: HyphenOptions, dismiss: () -> Unit, done: (HyphenOptions) -> Unit) {
    var o by remember { mutableStateOf(now) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.words_hyphenation_options)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.words_hyphenation_zone), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(9f to "0.13\"", 18f to "0.25\"", 36f to "0.5\"", 54f to "0.75\"").forEach { (pt, label) -> Choice(label, o.zonePt == pt) { o = o.copy(zonePt = pt) } }
            }
            Text(stringResource(R.string.words_hyphen_limit), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 1, 2, 3).forEach { n -> Choice(if (n == 0) stringResource(R.string.words_no_limit) else "$n", o.limit == n) { o = o.copy(limit = n) } }
            }
            Choice(stringResource(R.string.words_hyphenate_caps), o.caps) { o = o.copy(caps = !o.caps) }
            Text(stringResource(R.string.words_hyphenation_hint), style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = { done(o) }) { Text(stringResource(R.string.common_done)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } })
}
