package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** An APA 7 student paper's title page: what it says, top to bottom (blank lines are left out). */
internal data class ApaTitle(val title: String = "", val author: String = "", val department: String = "", val university: String = "",
    val course: String = "", val instructor: String = "", val date: String = "")
/** APA's abstract page: its words (one paragraph, not indented) and keywords (none: no Keywords line). */
internal data class ApaAbstract(val text: String = "", val keywords: String = "")

/**
 * Design › APA 7 paper (asked 2026-09-30: an APA paper in one tap, the user's assignments): the student paper of APA's
 * 7th edition — Times New Roman 12, 1-inch margins, double spacing with no space between paragraphs, first lines
 * indented half an inch, the page number top right on every page, APA's five heading levels as Heading 1–5, the title
 * page's title bold and centred; references on a page of their own ([Citations]).
 */
internal object ApaPaper {
    const val INCH = 72f
    private const val FONT = "Times New Roman"
    private fun body(align: DocAlign, bold: Boolean, italic: Boolean, firstLine: Float) =
        StyleChange(font = FONT, sizePt = 12f, bold = bold, italic = italic, color = -1, align = align, line = 2f, before = 0f, after = 0f, left = 0f, firstLine = firstLine)
    /** Level 1: centred, bold · 2: left, bold · 3: left, bold italic · 4: indented, bold · 5: indented, bold italic. */
    val headings = mapOf(
        1 to body(DocAlign.Center, true, false, 0f), 2 to body(DocAlign.Start, true, false, 0f), 3 to body(DocAlign.Start, true, true, 0f),
        4 to body(DocAlign.Start, true, false, INCH / 2), 5 to body(DocAlign.Start, true, true, INCH / 2))
    val normal = body(DocAlign.Start, false, false, INCH / 2)
    val title = body(DocAlign.Center, true, false, 0f)

    /** The file's side: the styles, the page number in the header, margins of an inch (an ODT's header room allowed for). */
    fun file(source: Map<String, ByteArray>, kind: OfficeKind, styles: DocStyles, runningHead: String? = null): LinkedHashMap<String, ByteArray> {
        var parts = LinkedHashMap(source)
        val path = stylesPath(parts, kind)
        var xml = String(parts.getValue(path), Charsets.UTF_8)
        val entries = styles.paragraphStyles()
        fun edit(e: StyleEntry, c: StyleChange) {
            val key = styles.styleKey(e) ?: return
            xml = if (kind == OfficeKind.Docx) StyleEdit.docx(xml, key, e.heading, styles.normalId, c) else StyleEdit.odt(xml, key, e.heading, c)
        }
        edit(entries[0], normal)
        // LibreOffice's text: Standard and Text body both (the one inherits from the other, with spacing of its own).
        if (kind == OfficeKind.Odt) for (name in listOf("Standard", "Text_20_body")) if ("style:name=\"$name\"" in xml) xml = StyleEdit.odt(xml, name, 0, normal)
        edit(entries[1], title)
        for ((n, c) in headings) edit(StyleEntry("Heading $n", null, n), c)
        parts[path] = xml.toByteArray()
        // The page number top right on every page; a professional paper's running head flush left, in capitals (APA 2.8).
        val hf = HeaderFooter.of(styles.section).copy(header = runningHead?.let(::runningHead).orEmpty(), headerAlign = DocAlign.Start, number = HeaderFooter.NumberPlace.Header, numberAlign = DocAlign.End, numberFormat = "#", firstDifferent = false, oddEven = false)
        parts = if (kind == OfficeKind.Docx) HeaderFooterEdit.docx(parts, hf, styles.page) else HeaderFooterEdit.odt(parts, hf, styles.page)
        val mid = DocStyles.of(kind, OfficeFormats.zip(parts, if (kind == OfficeKind.Odt) "mimetype" else null)).page
        val inch = mid.copy(top = INCH, bottom = INCH, left = INCH, right = INCH)
        return if (kind == OfficeKind.Docx) PageSetup.docx(parts, inch) else PageSetup.odt(parts, inch, mid)
    }

    /** A professional paper's running head: the short title in capitals, at most 50 characters (APA 7, 2.8). */
    fun runningHead(shortTitle: String) = shortTitle.trim().uppercase().take(50).trim()

    /**
     * What was set by hand on single paragraphs and letters, taken off so the styles decide (the file's own spacing,
     * indents, justification; the letters' fonts and sizes). A reference list keeps its hanging indent; tables stay.
     */
    fun clean(doc: OfficeDoc, kind: OfficeKind): OfficeDoc = doc.copy(paras = doc.paras.map { p ->
        if (p.table != null || p.keep?.style == Citations.entryStyle(kind)) p else {
            val keep = p.keep?.let { k -> k.copy(parts = k.parts.filter { (name, xml) ->
                name !in setOf("spacing", "ind", "contextualSpacing") &&
                    !(name == "@" && Regex("""^ (fo:margin-(top|bottom|left|right)|fo:line-height|fo:text-indent|style:line-spacing|style:line-height-at-least|style:auto-text-indent)=""").containsMatchIn(xml))
            }).takeIf { !it.isEmpty } }
            p.copy(keep = keep, align = if (p.align == DocAlign.Justify) DocAlign.Start else p.align, runs = p.runs.map { r ->
                if (r.image != null) r else r.copy(sizePt = null, font = r.font?.let { f ->
                    (if (kind == OfficeKind.Docx) f.replace(Regex("""<w:(rFonts|sz|szCs)\b[^>]*/>"""), "")
                        else f.replace(Regex(""" (style:font-name|fo:font-family|style:font-family-generic|style:font-pitch|fo:font-size)="[^"]*""""), "")).ifBlank { null }
                })
            })
        }
    })

    /**
     * The abstract page (APA 7, 2.9): "Abstract" bold and centred at the top of a new page, the abstract in one
     * paragraph with no first-line indent, then "Keywords:" in italics, indented, and the keywords.
     */
    fun abstractPage(a: ApaAbstract, kind: OfficeKind, labelStyle: String?): List<DocPara> {
        val label = DocPara(listOf(DocRun("Abstract", bold = true)), align = DocAlign.Center,
            keep = OfficeFormats.withPageBreak(OfficeFormats.withSpacing(DocKeep(style = labelStyle), kind, firstLine = 0f), kind, true))
        val text = DocPara(listOf(DocRun(a.text.trim().replace(Regex("""\s*\n\s*"""), " "))), keep = OfficeFormats.withSpacing(null, kind, firstLine = 0f))
        val words = a.keywords.trim().trimEnd('.', ',', ';')
        return listOf(label, text) + if (words.isEmpty()) emptyList() else listOf(DocPara(listOf(DocRun("Keywords:", italic = true), DocRun(" $words"))))
    }

    /**
     * APA's run-in heading (levels 4 and 5): the heading's words bold (5: bold italic) at the start of an indented
     * paragraph, ending in a period, the text going on after it on the same line. [at]: a paragraph; its first
     * [length] letters are the heading (null: a heading paragraph of its own is joined to the paragraph after it, else
     * the words up to the first full stop). Null when there is nothing to make one of.
     */
    fun runIn(doc: OfficeDoc, at: Int, level: Int, length: Int? = null): OfficeDoc? {
        val p = doc.paras.getOrNull(at)?.takeIf { it.table == null } ?: return null
        val next = doc.paras.getOrNull(at + 1)
        val alone = length == null && next != null && next.table == null && next.heading == 0 && next.text.isNotBlank() &&
            (p.heading > 0 || !p.text.trim().let { it.contains(". ") || it.endsWith(".") && it.count { c -> c == ' ' } > 12 })
        val head: List<DocRun>; val rest: List<DocRun>
        if (alone) { head = p.runs; rest = next!!.runs } else {
            val n = (length ?: Regex("""[.?!](\s|$)""").find(p.text)?.let { it.range.first + 1 } ?: p.text.length).coerceIn(0, p.text.length)
            if (n == 0) return null
            val (a, b) = split(p.runs, n); head = a; rest = b
        }
        val words = head.joinToString("") { it.text }.trimEnd()
        if (words.isBlank()) return null
        val stop = if (words.last() in ".?!") "" else "."
        val shaped = trimEnd(head).map { r -> if (r.image != null) r else r.copy(bold = true, italic = level == 5, unbold = false, unitalic = false) } +
            (if (stop.isEmpty()) emptyList() else listOf(DocRun(stop, bold = true, italic = level == 5)))
        val after = rest.dropWhile { it.image == null && it.text.isBlank() }.let { r -> r.firstOrNull()?.let { f -> listOf(f.copy(text = f.text.trimStart())) + r.drop(1) } ?: r }
        val keep = (if (alone) next!!.keep else p.keep)?.let { k -> k.copy(style = if (p.heading > 0 && !alone) null else k.style) }
        val joined = DocPara(OfficeFormats.tidy(shaped + listOf(DocRun(" ")) + after), heading = 0, align = DocAlign.Start, keep = keep?.takeIf { !it.isEmpty })
        return doc.copy(paras = doc.paras.take(at) + joined + doc.paras.drop(at + if (alone) 2 else 1))
    }
    /** Every Heading 4 and 5 paragraph followed by text, made a run-in heading (Design › APA 7 paper). */
    fun runInAll(doc: OfficeDoc): OfficeDoc {
        var d = doc
        var i = 0
        while (i < d.paras.size) { val h = d.paras[i].heading; if (h == 4 || h == 5) d = runIn(d, i, h) ?: d; i++ }
        return d
    }
    private fun split(runs: List<DocRun>, n: Int): Pair<List<DocRun>, List<DocRun>> {
        val a = ArrayList<DocRun>(); val b = ArrayList<DocRun>(); var at = 0
        for (r in runs) {
            val len = r.text.length
            when {
                at + len <= n -> a += r
                at >= n -> b += r
                else -> { a += r.copy(text = r.text.substring(0, n - at)); b += r.copy(text = r.text.substring(n - at)) }
            }
            at += len
        }
        return a to b
    }
    private fun trimEnd(runs: List<DocRun>): List<DocRun> {
        val out = runs.toMutableList()
        while (out.isNotEmpty() && out.last().image == null && out.last().text.isBlank()) out.removeAt(out.lastIndex)
        if (out.isNotEmpty() && out.last().image == null) out[out.lastIndex] = out.last().let { it.copy(text = it.text.trimEnd()) }
        return out
    }

    /** The title page's paragraphs (APA: the title three or four lines down, bold; a blank line; the rest), centred. */
    fun titlePage(t: ApaTitle, kind: OfficeKind, titleStyle: String?): List<DocPara> {
        fun centred(text: String, style: String? = null) = DocPara(if (text.isEmpty()) emptyList() else listOf(DocRun(text)), align = DocAlign.Center,
            keep = OfficeFormats.withSpacing(DocKeep(style = style), kind, firstLine = 0f))
        val rest = listOf(t.author, t.department + (if (t.department.isNotBlank() && t.university.isNotBlank()) ", " else "") + t.university, t.course, t.instructor, t.date)
            .filter { it.isNotBlank() }.map { centred(it.trim()) }
        return List(3) { centred("") } + centred(t.title.trim(), titleStyle) + centred("") + rest
    }
}

/** The APA 7 paper page: what it sets, whether to clear what was set by hand, and a title page to put first. */
@Composable
internal fun ApaPaperDialog(hasText: Boolean, today: String, dismiss: () -> Unit, apply: (clean: Boolean, title: ApaTitle?, abstract: ApaAbstract?, runningHead: String?) -> Unit) {
    var professional by remember { mutableStateOf(false) }
    var head by remember { mutableStateOf("") }
    var clean by remember { mutableStateOf(hasText) }
    var withTitle by remember { mutableStateOf(!hasText) }
    var t by remember { mutableStateOf(ApaTitle(date = today)) }
    var withAbstract by remember { mutableStateOf(false) }
    var ab by remember { mutableStateOf(ApaAbstract()) }
    @Composable fun field(label: String, value: String, set: (String) -> Unit) = OutlinedTextField(value, set, singleLine = true, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
    @Composable fun check(label: String, on: Boolean, set: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth().clickable { set(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(on, set); Text(label, style = MaterialTheme.typography.bodyMedium)
    }
    AlertDialog(
        onDismissRequest = dismiss,
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(if (professional) R.string.words_apa_professional else R.string.words_apa_student)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).shadedScroll(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.words_apa_what_it_does), style = MaterialTheme.typography.bodyMedium)
                check(stringResource(R.string.words_apa_clean), clean) { clean = it }
                // A professional paper (for publication): the running head on every page; its title page without course, instructor, date.
                check(stringResource(R.string.words_apa_professional_check), professional) { professional = it }
                if (professional) {
                    field(stringResource(R.string.words_running_head), head) { head = it.take(50) }
                    Text(ApaPaper.runningHead(head.ifBlank { t.title }).ifBlank { "SHORT TITLE" } + stringResource(R.string.words_running_head_where), style = MaterialTheme.typography.bodySmall)
                }
                check(stringResource(R.string.words_apa_add_title_page), withTitle) { withTitle = it }
                if (withTitle) {
                    field(stringResource(R.string.words_paper_title), t.title) { t = t.copy(title = it) }
                    field(stringResource(R.string.words_your_name), t.author) { t = t.copy(author = it) }
                    field(stringResource(R.string.words_department), t.department) { t = t.copy(department = it) }
                    field(stringResource(R.string.words_university), t.university) { t = t.copy(university = it) }
                    if (!professional) {
                        field(stringResource(R.string.words_course), t.course) { t = t.copy(course = it) }
                        field(stringResource(R.string.words_instructor), t.instructor) { t = t.copy(instructor = it) }
                        field(stringResource(R.string.words_due_date), t.date) { t = t.copy(date = it) }
                    }
                }
                check(stringResource(R.string.words_apa_add_abstract), withAbstract) { withAbstract = it }
                if (withAbstract) {
                    OutlinedTextField(ab.text, { ab = ab.copy(text = it) }, label = { Text(stringResource(R.string.words_abstract_field)) }, minLines = 4, modifier = Modifier.fillMaxWidth())
                    Text(wordsCount(ab.text.split(Regex("""\s+""")).count { it.isNotBlank() }), style = MaterialTheme.typography.bodySmall)
                    field(stringResource(R.string.words_keywords_field), ab.keywords) { ab = ab.copy(keywords = it) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { apply(clean, if (withTitle && t.title.isNotBlank()) (if (professional) t.copy(course = "", instructor = "", date = "") else t) else null, if (withAbstract) ab else null,
            if (professional) head.ifBlank { t.title }.takeIf { it.isNotBlank() } ?: "Short title" else null) }) { Text(stringResource(R.string.common_apply)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
