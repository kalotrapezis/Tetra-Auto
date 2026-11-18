package com.kalotrapezis.drive

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import com.mohamedrejeb.richeditor.model.RichTextState

/**
 * What Home's tools act on (PDF_LIKE_WORDS.md, E3): Words' editor holding [text]'s words — the one being typed in, or,
 * a text chosen, one with all its words chosen. [done] puts what the editor holds on (a chosen text: again, one undo
 * step; typing: nothing yet); [paras] takes [text]'s paragraphs changed (a style, spacing) and shows them again.
 */
internal class PdfTextTarget(val editor: RichTextState, val text: PdfText, val done: () -> Unit, val paras: (List<DocPara>) -> Unit) {
    /** The paragraphs as the editor has them now, what it does not hold carried over (Words' own step). */
    fun now(): List<DocPara> = WriterBridge.fromEditorKeeping(text.doc.paras, editor.toRichTextDocument(), text.doc.basePt)
    /** The paragraphs the cursor or the chosen words are in. */
    fun chosenParas(): Set<Int> = WriterBridge.paragraphsAt(editor.annotatedString.text, editor.toRichTextDocument(), editor.selection.min, editor.selection.max)
    /** The chosen letters, or (none chosen) the word at the cursor. */
    fun chosenOrWord(): Pair<Int, Int> {
        val sel = editor.selection
        if (!sel.collapsed) return sel.min to sel.max
        val t = editor.annotatedString.text
        var a = sel.min; var b = sel.min
        while (a > 0 && t[a - 1].isLetterOrDigit()) a--
        while (b < t.length && t[b].isLetterOrDigit()) b++
        return a to b
    }
}

/**
 * Words' Home tab for a PDF's text, its items in Words' order: B I U S X² X₂ · font and size · colour, highlight, Aa,
 * Clear formatting · Normal, Title, H1–H3 · bullets, numbering, indents · alignment · line spacing, More…. Nothing
 * chosen: the font, size and colour the next text takes ([look], [setLook]). [font] opens Words' font strip;
 * [paragraph] Words' Paragraph window for the chosen paragraphs.
 */
@Composable
internal fun PdfHomeTools(w: Color, target: PdfTextTarget?, look: PdfTextLook, setLook: (PdfTextLook) -> Unit, font: () -> Unit, paragraph: (Set<Int>, ParaLook) -> Unit) {
    val ed = target?.editor
    val on = ed != null
    val span = ed?.currentSpanStyle ?: SpanStyle()
    val para = ed?.currentParagraphStyle
    fun act(op: RichTextState.() -> Unit) { val t = target ?: return; t.editor.op(); t.done() }
    fun paras(change: (Int, DocPara) -> DocPara) { val t = target ?: return; val at = t.chosenParas(); t.paras(t.now().mapIndexed { i, p -> if (i in at) change(i, p) else p }) }
    val basePt = target?.text?.doc?.basePt ?: look.size

    Tool(R.drawable.ic_format_bold, stringResource(R.string.words_bold), w, enabled = on, on = (span.fontWeight?.weight ?: 400) >= 600) { act { toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold)) } }
    Tool(R.drawable.ic_format_italic, stringResource(R.string.words_italic), w, enabled = on, on = span.fontStyle == FontStyle.Italic) { act { toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic)) } }
    Tool(R.drawable.ic_format_underlined, stringResource(R.string.words_underline), w, enabled = on, on = span.textDecoration?.contains(TextDecoration.Underline) == true) { act { toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline)) } }
    Tool(R.drawable.ic_format_strikethrough, stringResource(R.string.words_strikethrough), w, enabled = on, on = span.textDecoration?.contains(TextDecoration.LineThrough) == true) { act { toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) } }
    // Superscript and subscript shown smaller, as Words' editor shows them (its letters back to their size when taken off)
    listOf("X²" to BaselineShift.Superscript, "X₂" to BaselineShift.Subscript).forEach { (label, shift) ->
        val sign = Math.signum(span.baselineShift?.multiplier ?: 0f)
        RibbonButton(label, w, enabled = on, on = sign == Math.signum(shift.multiplier)) { act {
            val size = WriterBridge.spOf(span.fontSize) ?: BASE_SP
            span.baselineShift?.takeIf { sign != 0f }?.let { was -> removeSpanStyle(SpanStyle(baselineShift = was)); addSpanStyle(SpanStyle(fontSize = (size / SCRIPT_SIZE).sp)) }
            if (sign != Math.signum(shift.multiplier)) { addSpanStyle(SpanStyle(baselineShift = shift)); addSpanStyle(SpanStyle(fontSize = ((if (sign != 0f) size / SCRIPT_SIZE else size) * SCRIPT_SIZE).sp)) }
        } }
    }
    RibbonDivider(w)
    FontButton(look.font, w, click = font)
    val shrink = if ((span.baselineShift?.multiplier ?: 0f) != 0f) SCRIPT_SIZE else 1f
    val pt = if (ed != null) Math.round((WriterBridge.spOf(span.fontSize) ?: BASE_SP) / shrink / BASE_SP * basePt) else Math.round(look.size)
    fun setPt(n: Int) = if (ed != null) act { addSpanStyle(SpanStyle(fontSize = (n.coerceIn(4, 200) * BASE_SP / basePt * shrink).sp)) } else setLook(look.copy(size = n.coerceIn(4, 200).toFloat()))
    SizeStepper("$pt", w, pt > 4, pt < 200, { setPt(pt - 1) }, { setPt(pt + 1) })
    RibbonDivider(w)
    Swatches(w, R.drawable.ic_format_color_text, stringResource(R.string.words_text_colour), TEXT_COLOURS) { c ->
        if (ed == null) setLook(look.copy(color = (c ?: Color.Black).toArgb() and 0xFFFFFF))
        else act { if (c == null) span.color.takeIf { it != Color.Unspecified }?.let { removeSpanStyle(SpanStyle(color = it)) } else addSpanStyle(SpanStyle(color = c)) }
    }
    Swatches(w, R.drawable.ic_highlighter, stringResource(R.string.words_highlight), HIGHLIGHT_COLOURS) { c ->
        act { if (c == null) span.background.takeIf { it != Color.Unspecified }?.let { removeSpanStyle(SpanStyle(background = it)) } else addSpanStyle(SpanStyle(background = c)) }
    }
    RibbonMenu("Aa", w, enabled = on, preset = false, title = stringResource(R.string.words_change_case), choices = WriterBridge.Case.entries.map { c -> MenuChoice(stringResource(c.text)) {
        val t = target ?: return@MenuChoice
        val (from, to) = t.chosenOrWord(); val sel = t.editor.selection
        t.editor.setRichTextDocument(WriterBridge.changeCase(t.editor.annotatedString.text, t.editor.toRichTextDocument(), from, to, c), sel); t.done()
    } })
    Tool(R.drawable.ic_format_clear, stringResource(R.string.words_clear_formatting), w, enabled = on) {
        val t = target ?: return@Tool
        val text = t.editor.annotatedString.text; val rich = t.editor.toRichTextDocument(); val sel = t.editor.selection
        val (from, to) = if (!sel.collapsed) sel.min to sel.max else WriterBridge.blockRange(text, rich, sel.min) ?: (0 to 0)
        t.editor.setRichTextDocument(WriterBridge.clearFormatting(text, rich, from, to, basePt), sel); t.done()
    }
    RibbonDivider(w)
    // the everyday styles, as Words' (its text's own: a new Words document's Title and Headings)
    val here = target?.let { t -> t.chosenParas().minOrNull()?.let { t.text.doc.paras.getOrNull(it) } }
    val styles = target?.text?.styles?.paragraphStyles() ?: DocStyles.Plain.paragraphStyles()
    listOfNotNull(styles.getOrNull(0)?.let { it to stringResource(R.string.words_style_normal) }, styles.getOrNull(1)?.let { it to stringResource(R.string.words_style_title) },
        styles.firstOrNull { it.heading == 1 }?.let { it to "H1" }, styles.firstOrNull { it.heading == 2 }?.let { it to "H2" }, styles.firstOrNull { it.heading == 3 }?.let { it to "H3" }).forEach { (style, label) ->
        RibbonButton(label, w, enabled = on, on = here != null && (here.keep?.style ?: styles[0].id) == style.id && here.heading == style.heading) {
            paras { _, p -> p.copy(heading = style.heading, keep = (p.keep ?: DocKeep()).copy(style = style.id).takeIf { !it.isEmpty }) }
        }
    }
    RibbonDivider(w)
    Tool(R.drawable.ic_format_list_bulleted, stringResource(R.string.words_bullets), w, enabled = on, on = ed?.isUnorderedList == true) { act { toggleUnorderedList() } }
    Tool(R.drawable.ic_format_list_numbered, stringResource(R.string.words_numbering), w, enabled = on, on = ed?.isOrderedList == true) { act { toggleOrderedList() } }
    Tool(R.drawable.ic_format_indent_decrease, stringResource(R.string.words_decrease_indent), w, enabled = ed?.canDecreaseListLevel == true) { act { decreaseListLevel() } }
    Tool(R.drawable.ic_format_indent_increase, stringResource(R.string.words_increase_indent), w, enabled = ed?.canIncreaseListLevel == true) { act { increaseListLevel() } }
    RibbonDivider(w)
    listOf(TextAlign.Start to R.drawable.ic_align_left, TextAlign.Center to R.drawable.ic_align_center, TextAlign.End to R.drawable.ic_align_right, TextAlign.Justify to R.drawable.ic_align_justify).forEach { (a, icon) ->
        val now = para?.textAlign == a || (a == TextAlign.Start && (para?.textAlign == TextAlign.Unspecified || para?.textAlign == TextAlign.Left))
        Tool(icon, stringResource(when (a) { TextAlign.Center -> R.string.words_align_center; TextAlign.End -> R.string.words_align_end; TextAlign.Justify -> R.string.words_align_justify; else -> R.string.words_align_start }),
            w, enabled = on, on = on && now) { act { addParagraphStyle(ParagraphStyle(textAlign = a, textDirection = androidx.compose.ui.text.style.TextDirection.Content)) } }
    }
    RibbonDivider(w)
    fun openParagraph() { val t = target ?: return; val at = t.chosenParas(); val p = at.minOrNull()?.let { t.now().getOrNull(it) } ?: return; paragraph(at, t.text.styles.look(p)) }
    Tool(R.drawable.ic_format_line_spacing, stringResource(R.string.words_line_spacing), w, enabled = on) { openParagraph() }
    LINE_SPACINGS.forEach { m -> RibbonButton(plain(m), w, enabled = on) { paras { _, p -> p.copy(keep = OfficeFormats.withSpacing(p.keep, OfficeKind.Docx, m)) } } }
    RibbonButton(stringResource(R.string.common_more), w, R.drawable.ic_more_vert, enabled = on) { openParagraph() }
}

/**
 * Layout › Paragraph (Words'): the chosen text's paragraphs' indents and the space before and after them, each − value +;
 * off with no text chosen or typed.
 */
@Composable
internal fun PdfParagraphBoxes(w: Color, target: PdfTextTarget?) {
    val here = target?.let { t -> val at = t.chosenParas(); at.minOrNull()?.let { t.now().getOrNull(it) }?.let { p -> at to t.text.styles.look(p) } }
    fun set(f: (ParaLook) -> ParaLook) { val t = target ?: return; val (at, was) = here ?: return; val now = f(was)
        t.paras(t.now().mapIndexed { i, p -> if (i !in at) p else p.copy(keep = OfficeFormats.withSpacing(p.keep, OfficeKind.Docx, null,
            now.before.takeIf { it != was.before }, now.after.takeIf { it != was.after }, now.left.takeIf { it != was.left }, null, now.right.takeIf { it != was.right })) }) }
    val step = if (WriterUnits.name == "in") 7.2f else 0.25f * WriterUnits.pt // a tenth of an inch, a quarter centimetre
    @Composable fun box(label: String, shown: String, down: () -> Unit, up: () -> Unit) = androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.material3.Text(label, color = w, style = androidx.compose.material3.MaterialTheme.typography.labelMedium, modifier = androidx.compose.ui.Modifier.padding(start = 8.dp))
        Tool(R.drawable.ic_remove, stringResource(R.string.words_less_of, label), w, enabled = here != null) { down() }
        androidx.compose.material3.Text(shown, color = w, style = androidx.compose.material3.MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 1,
            modifier = androidx.compose.ui.Modifier.widthIn(min = 44.dp))
        Tool(R.drawable.ic_add, stringResource(R.string.words_more_of, label), w, enabled = here != null) { up() }
    }
    val look = here?.second ?: ParaLook()
    box(stringResource(R.string.words_left), WriterUnits.text(look.left) + " " + WriterUnits.name, { set { it.copy(left = maxOf(0f, it.left - step)) } }, { set { it.copy(left = it.left + step) } })
    box(stringResource(R.string.words_right), WriterUnits.text(look.right) + " " + WriterUnits.name, { set { it.copy(right = maxOf(0f, it.right - step)) } }, { set { it.copy(right = it.right + step) } })
    box(stringResource(R.string.words_before), "${Math.round(look.before)} pt", { set { it.copy(before = maxOf(0f, it.before - 6f)) } }, { set { it.copy(before = it.before + 6f) } })
    box(stringResource(R.string.words_after), "${Math.round(look.after)} pt", { set { it.copy(after = maxOf(0f, it.after - 6f)) } }, { set { it.copy(after = it.after + 6f) } })
}
