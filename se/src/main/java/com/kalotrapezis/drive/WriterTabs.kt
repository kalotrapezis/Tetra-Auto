package com.kalotrapezis.drive

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import com.mohamedrejeb.richeditor.document.RichTextDocument

internal const val EDITOR_TAB_SPACE = '\u200A'
/** A tab in the editor: where it is, its leader and its width in pixels (the layout reports a widened tab at half its width). */
internal data class EditorTab(val at: Int, val leader: Char?, val widthPx: Float = 0f)

/** A tab is a sized space only in the visual text. The editable text and the saved file keep the tab. */
internal fun editorTabs(shown: AnnotatedString, rich: RichTextDocument, doc: OfficeDoc, styles: DocStyles,
    measurer: TextMeasurer, textStyle: TextStyle, widthPx: Int, pxPerSp: Float, spPerPt: Float): Pair<AnnotatedString, List<EditorTab>> {
    if ('\t' !in shown.text) return shown to emptyList()
    val builder = AnnotatedString.Builder(shown.text.replace('\t', EDITOR_TAB_SPACE)).apply {
        shown.spanStyles.forEach { addStyle(it.item, it.start, it.end) }
        shown.paragraphStyles.forEach { addStyle(it.item, it.start, it.end) }
        shown.getStringAnnotations(0, shown.length).forEach { addStringAnnotation(it.tag, it.item, it.start, it.end) }
    }
    val places = WriterBridge.blockPlaces(shown.text, rich)
    val tabs = ArrayList<EditorTab>()
    val pxPerPt = pxPerSp * spPerPt
    val widthPt = widthPx / pxPerPt
    places.forEach { b ->
        val p = doc.paras.getOrNull(b.para) ?: return@forEach
        val look = styles.look(p)
        val stops = (look.tabs.map { stop ->
            // Mobile view reflows to the screen; a contents line's right edge follows that width.
            if (WriterBridge.pageWidthPt == null && stop.type == 'r') stop.copy(pos = minOf(stop.pos, widthPt - 1f / pxPerPt)) else stop
        } + if (look.firstLine < 0f) listOf(TabStop(look.left, 'l')) else emptyList()).sortedBy { it.pos }
        for (at in b.from until b.to) {
            if (shown.text[at] != '\t') continue
            builder.addStyle(SpanStyle(fontSize = 0.5.sp, letterSpacing = 0.sp), at, at + 1)
            val current = builder.toAnnotatedString()
            val laid = measurer.measure(current, textStyle, constraints = Constraints(maxWidth = widthPx))
            val x = laid.getHorizontalPosition(at, true) / pxPerPt
            val end = (at + 1 until b.to).firstOrNull { shown.text[it] == '\t' } ?: b.to
            // the words after it with their letters' looks only: its paragraph's indent would count into the width (an indented contents line fell short)
            val following = if (end == at + 1) 0f else current.subSequence(at + 1, end).let { piece -> measurer.measure(AnnotatedString(piece.text, piece.spanStyles), textStyle,
                softWrap = false).multiParagraph.maxIntrinsicWidth / pxPerPt }
            val (stop, gap) = tabGap(x, following, stops)
            val glyph = measurer.measure(AnnotatedString(EDITOR_TAB_SPACE.toString()), textStyle.copy(fontSize = 0.5.sp), softWrap = false)
                .multiParagraph.maxIntrinsicWidth / pxPerSp
            builder.addStyle(SpanStyle(fontSize = 0.5.sp, letterSpacing = (gap * spPerPt - glyph).sp), at, at + 1)
            tabs += EditorTab(at, stop.leader, gap * pxPerPt)
        }
    }
    return builder.toAnnotatedString() to tabs
}
