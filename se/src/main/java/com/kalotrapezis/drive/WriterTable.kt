package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale

/** Colours offered for shading and lines: Word's theme row, light to strong. */
internal val TABLE_COLORS = listOf(0xFFFFFF, 0xF2F2F2, 0xD9D9D9, 0xDEEAF6, 0xE2EFD9, 0xFFF2CC, 0xFBE4D5, 0xEDE2F6,
    0x000000, 0x4472C4, 0x70AD47, 0xFFC000, 0xED7D31, 0xC00000, 0x7030A0, 0x7F7F7F)
private const val CM = 28.3465f

/** Split cells (as Word's): into how many columns and rows. A merged cell starts at what undoes its merge. */
@Composable
internal fun TableSplitDialog(start: Pair<Int, Int>, dismiss: () -> Unit, split: (Int, Int) -> Unit) {
    var columns by remember { mutableIntStateOf(start.first) }
    var rows by remember { mutableIntStateOf(start.second) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.words_split_cell)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Stepper(stringResource(R.string.words_columns), columns, 1..10) { columns = it }
                Stepper(stringResource(R.string.words_rows), rows, 1..10) { rows = it }
                Text(stringResource(R.string.words_split_text_stays), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = columns * rows > 1, onClick = { split(columns, rows) }) { Text(stringResource(R.string.words_split)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable private fun Stepper(label: String, value: Int, range: IntRange, set: (Int) -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        TextButton(enabled = value > range.first, onClick = { set(value - 1) }) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text("$value", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(32.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        TextButton(enabled = value < range.last, onClick = { set(value + 1) }) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }

private enum class PropTab(@androidx.annotation.StringRes val label: Int) { Table(R.string.words_table), Row(R.string.words_row), Column(R.string.words_column), Cell(R.string.words_cell), Borders(R.string.words_borders) }

/**
 * Everything else about a table (asked 2026-09-28: the ribbon keeps the everyday tools, ⋯ opens the rest, in tabs by
 * what they change, as Word's Table Properties). Changes are tried on a copy and kept with OK.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TablePropertiesDialog(table: DocTable, row: Int, cell: Int, dismiss: () -> Unit, apply: (DocTable) -> Unit) {
    var draft by remember { mutableStateOf(table) }
    var tab by remember { mutableStateOf(PropTab.Cell) }
    val r = row.coerceIn(0, draft.rows.lastIndex)
    val c = cell.coerceIn(0, (draft.rows[r].cells.lastIndex).coerceAtLeast(0))
    val here = draft.rows[r].cells.getOrNull(c)
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(12.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.words_table_properties), style = MaterialTheme.typography.titleLarge)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PropTab.entries.forEach { t -> Choice(stringResource(t.label), tab == t) { tab = t } }
                }
                Column(Modifier.weight(1f).fillMaxWidth().shadedScroll(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    when (tab) {
                        PropTab.Table -> {
                            Section(stringResource(R.string.words_alignment_on_page)) {
                                listOf("left" to stringResource(R.string.words_left), "center" to stringResource(R.string.words_centre), "right" to stringResource(R.string.words_right)).forEach { (v, l) ->
                                    Choice(l, TableEdit.align(draft) == v) { draft = TableEdit.withAlign(draft, v) }
                                }
                            }
                            Section(stringResource(R.string.words_style)) {
                                TableEdit.LOOKS.forEach { look -> LookChip(look) { draft = TableEdit.withLook(draft, look) } }
                            }
                        }
                        PropTab.Row -> {
                            val props = draft.rows[r].props
                            val height = TableEdit.rowHeight(props)
                            Section(stringResource(R.string.words_row_height_n, r + 1)) {
                                Choice(stringResource(R.string.words_as_its_text), height == null) { draft = rowSet(draft, r) { TableEdit.withRowHeight(it, null, "atLeast") } }
                                Choice(stringResource(R.string.words_at_least), height?.second == "atLeast") { draft = rowSet(draft, r) { TableEdit.withRowHeight(it, height?.first ?: 20f, "atLeast") } }
                                Choice(stringResource(R.string.words_exactly), height?.second == "exact") { draft = rowSet(draft, r) { TableEdit.withRowHeight(it, height?.first ?: 20f, "exact") } }
                            }
                            if (height != null) CmField(stringResource(R.string.words_height), height.first) { pt -> draft = rowSet(draft, r) { TableEdit.withRowHeight(it, pt, height.second) } }
                            Toggle(stringResource(R.string.words_repeat_header_row), TableEdit.isHeader(props)) { on -> draft = rowSet(draft, r) { TableEdit.withHeader(it, on) } }
                            Toggle(stringResource(R.string.words_keep_row_together), TableEdit.keepsTogether(props)) { on -> draft = rowSet(draft, r) { TableEdit.withKeepTogether(it, on) } }
                        }
                        PropTab.Column -> {
                            val width = TableEdit.columnWidth(draft, r, c)
                            if (width == null) Text(stringResource(R.string.words_no_column_widths))
                            else {
                                CmField(stringResource(R.string.words_column_width), width) { pt -> draft = TableEdit.withColumnWidth(draft, r, c, pt) }
                                Text(stringResource(R.string.words_column_width_hint), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        PropTab.Cell -> if (here != null) {
                            val whole = TableEdit.wholeCell(draft, r, c)
                            fun set(f: (String) -> String) { draft = TableEdit.withCells(draft, whole) { it.copy(props = f(it.props)) } }
                            Section(stringResource(R.string.words_text_in_cell)) {
                                listOf("top" to stringResource(R.string.words_top), "center" to stringResource(R.string.words_centre), "bottom" to stringResource(R.string.words_bottom)).forEach { (v, l) ->
                                    Choice(l, TableEdit.vAlign(here.props) == v) { set { p -> TableEdit.withVAlign(p, v) } }
                                }
                            }
                            Section(stringResource(R.string.words_direction)) {
                                listOf(null to stringResource(R.string.words_across), "btLr" to stringResource(R.string.words_upwards), "tbRl" to stringResource(R.string.words_downwards)).forEach { (v, l) ->
                                    Choice(l, TableEdit.textDirection(here.props) == v) { set { p -> TableEdit.withTextDirection(p, v) } }
                                }
                            }
                            Section(stringResource(R.string.words_shading)) {
                                ColorDots(TableEdit.shading(here.props)) { fill -> set { p -> TableEdit.withShading(p, fill) } }
                            }
                        }
                        PropTab.Borders -> {
                            var color by remember { mutableIntStateOf(0x000000) }
                            var sz by remember { mutableIntStateOf(4) }
                            val line = TableEdit.Line(color, sz)
                            Section(stringResource(R.string.words_whole_table)) {
                                Choice(stringResource(R.string.words_all_lines), false) { draft = TableEdit.withTableBorders(draft, line, line) }
                                Choice(stringResource(R.string.words_outside_only), false) { draft = TableEdit.withTableBorders(draft, line, null) }
                                Choice(stringResource(R.string.words_rows_only), false) { draft = TableEdit.withTableBorders(draft, line, line, insideV = false) }
                                Choice(stringResource(R.string.words_no_lines), false) { draft = TableEdit.withTableBorders(draft, null, null) }
                            }
                            Section(stringResource(R.string.words_this_cell)) {
                                val whole = TableEdit.wholeCell(draft, r, c)
                                Choice(stringResource(R.string.words_box), false) { draft = TableEdit.withCells(draft, whole) { it.copy(props = TableEdit.withCellBorders(it.props, line)) } }
                                Choice(stringResource(R.string.words_no_lines), false) { draft = TableEdit.withCells(draft, whole) { it.copy(props = TableEdit.withCellBorders(it.props, null)) } }
                                Choice(stringResource(R.string.words_as_the_table), false) { draft = TableEdit.withCells(draft, whole) { it.copy(props = TableEdit.cellProp(it.props, "tcBorders", null)) } }
                            }
                            Section(stringResource(R.string.words_line_colour)) { ColorDots(color, none = false) { color = it ?: 0 } }
                            Section(stringResource(R.string.words_line_width)) {
                                listOf(2 to "¼ pt", 4 to "½ pt", 8 to "1 pt", 12 to "1½ pt", 18 to "2¼ pt").forEach { (v, l) -> Choice(l, sz == v) { sz = v } }
                            }
                            Text(stringResource(R.string.words_borders_hint), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) }
                    TextButton(onClick = { apply(draft) }) { Text(stringResource(R.string.common_ok)) }
                }
            }
        }
    }
}

/** The line spacings offered (Word's everyday ones). */
internal val LINE_SPACINGS = listOf(1f, 1.15f, 1.5f, 2f)
/** A number as people write it: 1, 1.15, 6.5. */
internal fun plain(v: Float) = if (v % 1f == 0f) v.toInt().toString() else v.toString()

/**
 * Home's ⋯ page (asked 2026-09-27: "set everything once, then type"): the chosen paragraphs' line spacing, space
 * before and after (pt, as Word counts them) and indents (cm). [apply] gets the new look; what was not touched stays.
 */
@Composable
internal fun ParagraphDialog(look: ParaLook, dismiss: () -> Unit, style: String? = null, editStyle: (() -> Unit)? = null, apply: (ParaLook) -> Unit) {
    var draft by remember { mutableStateOf(look) }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 640.dp).systemBarsPadding().imePadding().padding(12.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.words_paragraph), style = MaterialTheme.typography.titleLarge)
                // what it will look like, in sight while the choices below scroll (asked 16 Oct: a preview)
                ParagraphPreview(draft, draft.box, Modifier.padding(top = 10.dp))
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().shadedScroll().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Section(stringResource(R.string.words_line_spacing)) {
                        LINE_SPACINGS.forEach { m ->
                            Choice(plain(m), draft.lineExact == null && kotlin.math.abs(draft.lineMultiple - m) < 0.01f) {
                                draft = draft.copy(lineMultiple = m, lineExact = null, lineAtLeast = false)
                            }
                        }
                        // What the paragraph has when it is none of these (Word's 1.08, an exact height): shown, and back with a tap.
                        val own = look.lineExact?.let { stringResource(if (look.lineAtLeast) R.string.words_at_least_pt else R.string.words_exactly_pt, plain(Math.round(it * 10) / 10f)) }
                            ?: plain(Math.round(look.lineMultiple * 100) / 100f).takeIf { LINE_SPACINGS.none { m -> kotlin.math.abs(look.lineMultiple - m) < 0.01f } }
                        own?.let { Choice(it, draft.lineExact == look.lineExact && draft.lineAtLeast == look.lineAtLeast && draft.lineMultiple == look.lineMultiple) {
                            draft = draft.copy(lineMultiple = look.lineMultiple, lineExact = look.lineExact, lineAtLeast = look.lineAtLeast)
                        } }
                    }
                    Section(stringResource(R.string.words_space_between)) {
                        PtField(stringResource(R.string.words_before), draft.before) { draft = draft.copy(before = it) }
                        PtField(stringResource(R.string.words_after), draft.after) { draft = draft.copy(after = it) }
                    }
                    Section(stringResource(R.string.words_indent)) {
                        CmField(stringResource(R.string.words_left), draft.left, least = 0f) { draft = draft.copy(left = it) }
                        CmField(stringResource(R.string.words_right), draft.right, least = 0f) { draft = draft.copy(right = it) }
                    }
                    Section(stringResource(R.string.words_first_line)) {
                        Choice(stringResource(R.string.common_none), draft.firstLine == 0f) { draft = draft.copy(firstLine = 0f) }
                        Choice(stringResource(R.string.words_indented), draft.firstLine > 0f) { draft = draft.copy(firstLine = if (draft.firstLine > 0f) draft.firstLine else 1.25f * CM) }
                        Choice(stringResource(R.string.words_hanging), draft.firstLine < 0f) { draft = draft.copy(firstLine = if (draft.firstLine < 0f) draft.firstLine else -1.25f * CM) }
                    }
                    if (draft.firstLine != 0f) CmField(stringResource(if (draft.firstLine > 0f) R.string.words_indented_by else R.string.words_hanging_by), kotlin.math.abs(draft.firstLine)) { v ->
                        draft = draft.copy(firstLine = if (draft.firstLine < 0f) -v else v)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Word's Modify Style from here: the style itself changed, so every paragraph in it follows
                    if (editStyle != null && style != null) TextButton(onClick = editStyle) { Text(stringResource(R.string.words_edit_style, style), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) }
                    TextButton(onClick = { apply(draft) }) { Text(stringResource(R.string.common_ok)) }
                }
            }
        }
    }
}

/**
 * A paragraph as it will look, between the ones before and after it (grey): its space before and after, indents, first
 * line, line spacing and borders, the lines drawn as bars (asked 16 Oct: a preview in a centred panel).
 */
@Composable
internal fun ParagraphPreview(look: ParaLook, box: ParaBox?, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(10.dp)).background(Color.White)) {
        val page = 451f // a page's text width, points
        val k = (size.width - 32.dp.toPx()) / page
        val x0 = 16.dp.toPx()
        val line = (if (look.lineExact != null) look.lineExact else 12f * look.lineMultiple.coerceIn(0.5f, 3f)) * k * 1.4f
        val bar = 6f * k * 1.4f
        val grey = Color(0xFFD0D0D0); val ink = Color(0xFF555555)
        var y = 10.dp.toPx()
        fun lines(n: Int, color: Color, left: Float, right: Float, first: Float, step: Float) { for (i in 0 until n) {
            val start = x0 + (left + if (i == 0) first else 0f) * k
            val end = x0 + (page - right) * k - if (i == n - 1) (page - right - left) * k * 0.35f else 0f
            drawRoundRect(color, androidx.compose.ui.geometry.Offset(start, y), androidx.compose.ui.geometry.Size((end - start).coerceAtLeast(4f), bar), androidx.compose.ui.geometry.CornerRadius(bar / 2))
            y += step } }
        lines(2, grey, 0f, 0f, 0f, 12f * k * 1.4f)
        y += look.before * k * 1.4f
        val top = y - (line - bar) / 2
        lines(4, ink, look.left, look.right, look.firstLine, line)
        val bottom = y - line + bar + (line - bar) / 2
        // its borders, each side in its line (double: two, dotted, dashed)
        box?.let { b ->
            val l = x0 + look.left * k - 4.dp.toPx(); val r = x0 + (page - look.right) * k + 4.dp.toPx()
            fun edge(e: Border?, a: androidx.compose.ui.geometry.Offset, z: androidx.compose.ui.geometry.Offset, out: androidx.compose.ui.geometry.Offset) {
                e ?: return
                val c = if (e.color == 0) Color.Black else Color(0xFF000000.toInt() or e.color)
                // drawn a little bolder than to scale, so a thin one and a double read as what they are
                val w = (e.width * k * 2.5f).coerceIn(2.5f, 12f)
                val fx = when (e.style) { "dotted" -> androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(w, w * 1.5f)); "dashed" -> androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(w * 4, w * 2)); else -> null }
                if (e.style == "double") { val t = (w / 3).coerceAtLeast(2f)
                    drawLine(c, a, z, t); drawLine(c, a + out * (t * 2.5f), z + out * (t * 2.5f), t) }
                else drawLine(c, a, z, w, pathEffect = fx)
            }
            edge(b.top ?: b.between, androidx.compose.ui.geometry.Offset(l, top),androidx.compose.ui.geometry.Offset(r, top),androidx.compose.ui.geometry.Offset(0f, -1f)); edge(b.bottom ?: b.between, androidx.compose.ui.geometry.Offset(l, bottom),androidx.compose.ui.geometry.Offset(r, bottom),androidx.compose.ui.geometry.Offset(0f, 1f))
            edge(b.left, androidx.compose.ui.geometry.Offset(l, top),androidx.compose.ui.geometry.Offset(l, bottom),androidx.compose.ui.geometry.Offset(-1f, 0f)); edge(b.right, androidx.compose.ui.geometry.Offset(r, top),androidx.compose.ui.geometry.Offset(r, bottom),androidx.compose.ui.geometry.Offset(1f, 0f))
        }
        y += look.after * k * 1.4f + (12f * k * 1.4f - line).coerceAtLeast(0f)
        lines(2, grey, 0f, 0f, 0f, 12f * k * 1.4f)
    }
}

/**
 * Home › Borders (asked 16 Oct: too many choices for a strip — a centred panel with a preview): the sides, their
 * line, thickness and colour; the line chosen restyles the sides there and is what a side tapped takes.
 */
@Composable
internal fun BordersDialog(look: ParaLook, dismiss: () -> Unit, line: () -> Unit, apply: (ParaBox) -> Unit) {
    var box by remember { mutableStateOf(look.box ?: ParaBox()) }
    var pen by remember { mutableStateOf(listOfNotNull(look.box?.bottom, look.box?.top, look.box?.left, look.box?.right).firstOrNull() ?: Border(0.5f, 0)) }
    fun restyle(to: Border) {
        pen = to
        fun s(e: Border?) = e?.let { to }
        box = box.copy(top = s(box.top), left = s(box.left), bottom = s(box.bottom), right = s(box.right), between = s(box.between))
    }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 640.dp).systemBarsPadding().padding(12.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.words_borders), style = MaterialTheme.typography.titleLarge)
                ParagraphPreview(look, box, Modifier.padding(top = 10.dp))
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().shadedScroll().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Section(stringResource(R.string.words_sides)) {
                        Choice(stringResource(R.string.words_top), box.top != null) { box = box.copy(top = if (box.top == null) pen else null) }
                        Choice(stringResource(R.string.words_bottom), box.bottom != null) { box = box.copy(bottom = if (box.bottom == null) pen else null) }
                        Choice(stringResource(R.string.words_left), box.left != null) { box = box.copy(left = if (box.left == null) pen else null) }
                        Choice(stringResource(R.string.words_right), box.right != null) { box = box.copy(right = if (box.right == null) pen else null) }
                        Choice(stringResource(R.string.words_between), box.between != null) { box = box.copy(between = if (box.between == null) pen else null) }
                    }
                    Section(stringResource(R.string.words_all_at_once)) {
                        Choice(stringResource(R.string.words_box), box.top != null && box.bottom != null && box.left != null && box.right != null && box.between == null) { box = ParaBox(top = pen, left = pen, bottom = pen, right = pen) }
                        Choice(stringResource(R.string.common_all), box.between != null && box.top != null && box.left != null) { box = ParaBox(top = pen, left = pen, bottom = pen, right = pen, between = pen) }
                        Choice(stringResource(R.string.common_none), box.isEmpty) { box = ParaBox() }
                    }
                    Section(stringResource(R.string.words_line)) {
                        for ((label, st) in listOf(stringResource(R.string.words_line_solid) to "single", stringResource(R.string.words_line_double) to "double", stringResource(R.string.words_line_dotted) to "dotted", stringResource(R.string.words_line_dashed) to "dashed"))
                            Choice(label, pen.style == st) { restyle(pen.copy(style = st, width = if (st == "double") maxOf(pen.width, 1.5f) else pen.width)) }
                    }
                    Section(stringResource(R.string.words_thickness)) {
                        for ((label, pt) in listOf(stringResource(R.string.words_thin) to 0.5f, stringResource(R.string.words_medium) to 1.5f, stringResource(R.string.words_thick) to 3f)) Choice(label, pen.width == pt) { restyle(pen.copy(width = pt)) }
                    }
                    Section(stringResource(R.string.words_colour)) {
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (c in listOf(0x000000, 0x7F7F7F, 0xC00000, 0xED7D31, 0xFFC000, 0x70AD47, 0x4472C4, 0x2F5496, 0x7030A0)) {
                                val on = (pen.color and 0xFFFFFF) == c
                                Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or c))
                                    .border(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                                    .clickable { restyle(pen.copy(color = c)) })
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = line) { Text(stringResource(R.string.words_horizontal_line)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) }
                    TextButton(onClick = { apply(box) }) { Text(stringResource(R.string.common_ok)) }
                }
            }
        }
    }
}

/** The fonts offered for a style: Word's everyday ones (Writer draws them with their metric twins). */
internal val STYLE_FONTS = listOf("Calibri", "Cambria", "Times New Roman", "Arial", "Courier New")
private val STYLE_COLORS = listOf(0x000000, 0x404040, 0x595959, 0x1F3864, 0x2F5496, 0x4472C4, 0xC00000, 0x538135, 0x7030A0)

/**
 * A style's own page (asked 2026-09-27: a style's font, size, bold, italic, colour, alignment, line spacing, space
 * before and after, indents — changes the document's style, so every paragraph with it changes, in Word and
 * LibreOffice too). [apply] gets only what was changed.
 */
@Composable
internal fun StyleDialog(entry: StyleEntry, look: ParaLook, basePt: Float, dismiss: () -> Unit, apply: (StyleChange) -> Unit) {
    var d by remember { mutableStateOf(look.copy(sizePt = look.sizePt ?: basePt, align = look.align ?: DocAlign.Start)) }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 640.dp).systemBarsPadding().imePadding().padding(12.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.words_style_named, entry.name), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.words_style_changes_all), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ParagraphPreview(d, d.box, Modifier.padding(top = 10.dp))
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().shadedScroll().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Section(stringResource(R.string.words_font)) {
                        (STYLE_FONTS + listOfNotNull(d.font?.takeIf { f -> STYLE_FONTS.none { it.equals(f, true) } })).forEach { f -> Choice(f, d.font.equals(f, true)) { d = d.copy(font = f) } }
                    }
                    Section(stringResource(R.string.words_size_and_look)) {
                        PtField(stringResource(R.string.words_size), d.sizePt ?: basePt) { if (it >= 1f) d = d.copy(sizePt = it) }
                        Choice(stringResource(R.string.words_bold), d.bold == true) { d = d.copy(bold = d.bold != true) }
                        Choice(stringResource(R.string.words_italic), d.italic == true) { d = d.copy(italic = d.italic != true) }
                    }
                    Section(stringResource(R.string.words_colour)) {
                        Choice(stringResource(R.string.words_automatic), d.color == null) { d = d.copy(color = null) }
                        STYLE_COLORS.forEach { c ->
                            Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF000000 or c.toLong()))
                                .border(if (d.color == c) 3.dp else 1.dp, if (d.color == c) MaterialTheme.colorScheme.primary else Color(0x55888888), CircleShape)
                                .clickable { d = d.copy(color = c) })
                        }
                    }
                    Section(stringResource(R.string.words_alignment)) {
                        listOf(DocAlign.Start to stringResource(R.string.words_left), DocAlign.Center to stringResource(R.string.words_centre), DocAlign.End to stringResource(R.string.words_right), DocAlign.Justify to stringResource(R.string.words_justify)).forEach { (a, l) -> Choice(l, d.align == a) { d = d.copy(align = a) } }
                    }
                    Section(stringResource(R.string.words_line_spacing)) {
                        LINE_SPACINGS.forEach { m -> Choice(plain(m), d.lineExact == null && kotlin.math.abs(d.lineMultiple - m) < 0.01f) { d = d.copy(lineMultiple = m, lineExact = null) } }
                        if (look.lineExact == null && LINE_SPACINGS.none { kotlin.math.abs(look.lineMultiple - it) < 0.01f })
                            Choice(plain(Math.round(look.lineMultiple * 100) / 100f), d.lineExact == null && d.lineMultiple == look.lineMultiple) { d = d.copy(lineMultiple = look.lineMultiple) }
                    }
                    Section(stringResource(R.string.words_space_between)) {
                        PtField(stringResource(R.string.words_before), d.before) { d = d.copy(before = it) }
                        PtField(stringResource(R.string.words_after), d.after) { d = d.copy(after = it) }
                    }
                    Section(stringResource(R.string.words_indent)) {
                        CmField(stringResource(R.string.words_left), d.left, least = 0f) { d = d.copy(left = it) }
                        Choice(stringResource(R.string.words_first_line_none), d.firstLine == 0f) { d = d.copy(firstLine = 0f) }
                        Choice(stringResource(R.string.words_indented), d.firstLine > 0f) { d = d.copy(firstLine = if (d.firstLine > 0f) d.firstLine else 1.25f * CM) }
                        Choice(stringResource(R.string.words_hanging), d.firstLine < 0f) { d = d.copy(firstLine = if (d.firstLine < 0f) d.firstLine else -1.25f * CM) }
                    }
                    if (d.firstLine != 0f) CmField(stringResource(if (d.firstLine > 0f) R.string.words_indented_by else R.string.words_hanging_by), kotlin.math.abs(d.firstLine)) { v -> d = d.copy(firstLine = if (d.firstLine < 0f) -v else v) }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) }
                    TextButton(onClick = {
                        fun <T> changed(a: T, b: T) = b.takeIf { it != a }
                        apply(StyleChange(font = changed(look.font, d.font), sizePt = changed(look.sizePt ?: basePt, d.sizePt), bold = changed(look.bold == true, d.bold == true),
                            italic = changed(look.italic == true, d.italic == true), color = if (d.color != look.color) d.color ?: -1 else null,
                            align = changed(look.align ?: DocAlign.Start, d.align), line = d.lineMultiple.takeIf { d.lineExact == null && (look.lineExact != null || it != look.lineMultiple) },
                            before = changed(look.before, d.before), after = changed(look.after, d.after), left = changed(look.left, d.left), firstLine = changed(look.firstLine, d.firstLine)))
                    }) { Text(stringResource(R.string.common_ok)) }
                }
            }
        }
    }
}

/**
 * Insert › Header & footer, Page numbers (asked 2026-09-27): lines of text at the top and at the bottom of every
 * page, where each stands, and the page number — for APA, top right on every page.
 */
@Composable
internal fun HeaderFooterDialog(now: HeaderFooter, rich: Boolean, dismiss: () -> Unit, apply: (HeaderFooter) -> Unit) {
    var d by remember { mutableStateOf(now) }
    // Several lines each (Enter): the first carries the page number.
    @Composable fun aligns(on: DocAlign, set: (DocAlign) -> Unit) =
        listOf(DocAlign.Start to stringResource(R.string.words_left), DocAlign.Center to stringResource(R.string.words_centre), DocAlign.End to stringResource(R.string.words_right)).forEach { (a, l) -> Choice(l, on == a) { set(a) } }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 640.dp).systemBarsPadding().imePadding().padding(12.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.words_header_footer_numbers), style = MaterialTheme.typography.titleLarge)
                if (rich) Text(stringResource(R.string.words_header_rich),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().shadedScroll().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    OutlinedTextField(d.header, { d = d.copy(header = it) }, label = { Text(stringResource(if (d.oddEven) R.string.words_header_odd else R.string.words_header)) }, maxLines = 4, modifier = Modifier.fillMaxWidth())
                    Section(stringResource(R.string.words_header_stands)) { aligns(d.headerAlign) { d = d.copy(headerAlign = it) } }
                    OutlinedTextField(d.footer, { d = d.copy(footer = it) }, label = { Text(stringResource(if (d.oddEven) R.string.words_footer_odd else R.string.words_footer)) }, maxLines = 4, modifier = Modifier.fillMaxWidth())
                    Section(stringResource(R.string.words_footer_stands)) { aligns(d.footerAlign) { d = d.copy(footerAlign = it) } }
                    Section(stringResource(R.string.words_page_number)) {
                        Choice(stringResource(R.string.common_none), d.number == null) { d = d.copy(number = null) }
                        Choice(stringResource(R.string.words_at_the_top), d.number == HeaderFooter.NumberPlace.Header) { d = d.copy(number = HeaderFooter.NumberPlace.Header) }
                        Choice(stringResource(R.string.words_at_the_bottom), d.number == HeaderFooter.NumberPlace.Footer) { d = d.copy(number = HeaderFooter.NumberPlace.Footer) }
                    }
                    if (d.number != null) {
                        Section(stringResource(R.string.words_number_stands)) { aligns(d.numberAlign) { d = d.copy(numberAlign = it) } }
                        Section(stringResource(R.string.words_written_as)) { HeaderFooter.FORMATS.forEach { f -> Choice(f.replace("#", "1").replace("N", "3"), d.numberFormat == f) { d = d.copy(numberFormat = f) } } }
                    }
                    Toggle(stringResource(R.string.words_first_page_different), d.firstDifferent) { d = d.copy(firstDifferent = it) }
                    // Word's "Different odd & even pages": even pages their own words, left and right the other way round.
                    Toggle(stringResource(R.string.words_odd_even_different), d.oddEven) { d = d.copy(oddEven = it, evenHeader = d.evenHeader.ifEmpty { d.header }, evenFooter = d.evenFooter.ifEmpty { d.footer }) }
                    if (d.oddEven) {
                        OutlinedTextField(d.evenHeader, { d = d.copy(evenHeader = it) }, label = { Text(stringResource(R.string.words_header_even)) }, maxLines = 4, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(d.evenFooter, { d = d.copy(evenFooter = it) }, label = { Text(stringResource(R.string.words_footer_even)) }, maxLines = 4, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.words_odd_even_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) }
                    TextButton(onClick = { apply(d) }) { Text(stringResource(R.string.common_ok)) }
                }
            }
        }
    }
}

/** Space in points (Word's unit for it), 0 allowed. */
@Composable private fun PtField(label: String, pt: Float, set: (Float) -> Unit) {
    var text by remember(label) { mutableStateOf(plain(pt)) }
    OutlinedTextField(text, { v -> text = v; v.replace(',', '.').toFloatOrNull()?.takeIf { it in 0f..200f }?.let(set) },
        label = { Text(label) }, suffix = { Text("pt") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.widthIn(max = 160.dp))
}

private fun rowSet(t: DocTable, r: Int, f: (String) -> String) = t.copy(rows = t.rows.mapIndexed { i, row -> if (i == r) row.copy(props = f(row.props)) else row })

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun Section(title: String, content: @Composable () -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable internal fun Choice(label: String, on: Boolean, click: () -> Unit) = Surface(
    shape = CircleShape, color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
    contentColor = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.clip(CircleShape).clickable(onClick = click),
) { Text(label, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) }

@Composable private fun Toggle(label: String, on: Boolean, set: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().clickable { set(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(on, set)
    }

@Composable private fun ColorDots(chosen: Int?, none: Boolean = true, choose: (Int?) -> Unit) {
    if (none) Box(Modifier.size(34.dp).clip(CircleShape).border(2.dp, if (chosen == null) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
        .clickable { choose(null) }, contentAlignment = Alignment.Center) { Text("✕", style = MaterialTheme.typography.labelLarge) }
    TABLE_COLORS.forEach { c ->
        Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF000000 or c.toLong()))
            .border(if (chosen == c) 3.dp else 1.dp, if (chosen == c) MaterialTheme.colorScheme.primary else Color(0x55888888), CircleShape)
            .clickable { choose(c) })
    }
}

/** A table style as a small picture of itself: its header, a banded row, its lines. */
@Composable internal fun LookChip(look: TableEdit.Look, click: () -> Unit) = Column(
    Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = click).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally,
) {
    val line = Color(0xFF000000 or look.line.toLong())
    Column(Modifier.width(56.dp).background(Color.White).border(1.dp, line)) {
        listOf(look.header, null, look.band, null).forEach { fill ->
            Box(Modifier.fillMaxWidth().height(9.dp).background(fill?.let { Color(0xFF000000 or it.toLong()) } ?: Color.White).border(0.5.dp, line.copy(alpha = if (look.inside) 1f else 0f)))
        }
    }
    Spacer(Modifier.height(2.dp))
    Text(when (look.name) { // TableEdit.LOOKS' names, as shown
        "Plain" -> stringResource(R.string.words_look_plain); "Blue" -> stringResource(R.string.words_theme_blue); "Green" -> stringResource(R.string.words_theme_green)
        "Orange" -> stringResource(R.string.words_look_orange); "Grey" -> stringResource(R.string.words_look_grey); "Lines" -> stringResource(R.string.words_lines); else -> look.name
    }, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** A length in the chosen unit (centimetres, or inches — Writer › View), kept in points. */
@Composable internal fun CmField(label: String, pt: Float, least: Float = 0.1f, set: (Float) -> Unit) {
    val unit = WriterUnits.name
    var text by remember(label, unit) { mutableStateOf(WriterUnits.text(pt)) }
    OutlinedTextField(text, { v -> text = v; v.replace(',', '.').toFloatOrNull()?.let(WriterUnits::toPt)?.takeIf { it in least * CM..60f * CM }?.let(set) },
        label = { Text(label) }, suffix = { Text(unit) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.widthIn(max = 240.dp))
}

/**
 * Formatting the text of a cell being typed in (asked 2026-09-28: "text format" in tables): the runs from [start] to
 * [end] changed by [f]; nothing chosen, the whole cell (on a phone, choosing letters in a small box is fiddly).
 */
internal fun formatCell(cell: DocCell, start: Int, end: Int, f: (DocRun) -> DocRun): DocCell {
    val length = WriterBridge.cellText(cell).length
    val (a, b) = if (start == end) 0 to length else minOf(start, end) to maxOf(start, end)
    var at = 0
    return cell.copy(paras = cell.paras.map { p ->
        val from = at; at += p.text.length + 1
        val lo = (a - from).coerceIn(0, p.text.length); val hi = (b - from).coerceIn(0, p.text.length)
        if (lo >= hi || p.table != null) p else {
            var x = 0
            p.copy(runs = OfficeFormats.tidy(p.runs.flatMap { r ->
                val s = x; x += r.text.length
                if (r.image != null || hi <= s || lo >= x) listOf(r) else listOfNotNull(
                    r.copy(text = r.text.substring(0, (lo - s).coerceAtLeast(0))).takeIf { it.text.isNotEmpty() },
                    f(r.copy(text = r.text.substring((lo - s).coerceAtLeast(0), (hi - s).coerceAtMost(r.text.length)))),
                    r.copy(text = r.text.substring((hi - s).coerceAtMost(r.text.length))).takeIf { it.text.isNotEmpty() })
            }).ifEmpty { p.runs })
        }
    })
}

/** The paragraphs from [start] to [end] (or the whole cell) aligned. */
internal fun alignCell(cell: DocCell, start: Int, end: Int, align: DocAlign): DocCell {
    val all = start == end
    var at = 0
    return cell.copy(paras = cell.paras.map { p ->
        val from = at; at += p.text.length + 1
        if (all || (minOf(start, end) <= from + p.text.length && maxOf(start, end) >= from)) p.copy(align = align) else p
    })
}

/** The look of the letter at [pos] (the one before it, at the end of a stretch). */
internal fun cellRunAt(cell: DocCell, pos: Int): DocRun? {
    var at = 0
    for (p in cell.paras) {
        var x = at
        for (r in p.runs) { if (r.image == null && pos >= x && pos <= x + r.text.length && r.text.isNotEmpty()) return r; x += r.text.length }
        at += p.text.length + 1
    }
    return cell.paras.firstOrNull()?.runs?.firstOrNull()
}

/** The cell's text as the box shows it: each run's look, sizes at the table's scale ([spPerPt]). */
internal fun cellAnnotated(cell: DocCell, spPerPt: Float, sizeOf: (DocPara, DocRun) -> Float): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        cell.paras.forEachIndexed { i, p ->
            if (i > 0) append('\n')
            p.runs.forEach { r ->
                val deco = listOfNotNull(androidx.compose.ui.text.style.TextDecoration.Underline.takeIf { r.underline || r.link != null },
                    androidx.compose.ui.text.style.TextDecoration.LineThrough.takeIf { r.strike })
                withStyle(androidx.compose.ui.text.SpanStyle(
                    fontWeight = if (r.bold) androidx.compose.ui.text.font.FontWeight.Bold else null,
                    fontStyle = if (r.italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
                    textDecoration = if (deco.isEmpty()) null else androidx.compose.ui.text.style.TextDecoration.combine(deco),
                    color = r.color?.let { Color(0xFF000000 or it.toLong()) } ?: Color.Unspecified,
                    background = r.highlight?.let { Color(0xFF000000 or it.toLong()) } ?: Color.Unspecified,
                    fontSize = androidx.compose.ui.unit.TextUnit(sizeOf(p, r) * spPerPt, androidx.compose.ui.unit.TextUnitType.Sp),
                )) { append(r.text) }
            }
        }
    }

/** Home's tools for the text of a cell being typed in: its looks, sizes, colours and alignment. */
internal fun cellHomePages(tint: Color, cell: DocCell, sel: androidx.compose.ui.text.TextRange, sizeOf: (DocPara, DocRun) -> Float,
    change: (DocCell) -> Unit): List<@Composable () -> Unit> {
    val look = cellRunAt(cell, if (sel.collapsed) sel.min else sel.min + 1) ?: DocRun("")
    fun apply(f: (DocRun) -> DocRun) = change(formatCell(cell, sel.min, sel.max, f))
    val para = cell.paras.firstOrNull() ?: DocPara(emptyList())
    val pt = sizeOf(para, look).let { kotlin.math.round(it).toInt() }
    return listOf({
        Tool(R.drawable.ic_format_bold, stringResource(R.string.words_bold), tint, on = look.bold) { apply { it.copy(bold = !look.bold) } }
        Tool(R.drawable.ic_format_italic, stringResource(R.string.words_italic), tint, on = look.italic) { apply { it.copy(italic = !look.italic) } }
        Tool(R.drawable.ic_format_underlined, stringResource(R.string.words_underline), tint, on = look.underline) { apply { it.copy(underline = !look.underline) } }
        Tool(R.drawable.ic_format_strikethrough, stringResource(R.string.words_strikethrough), tint, on = look.strike) { apply { it.copy(strike = !look.strike) } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tool(R.drawable.ic_remove, stringResource(R.string.words_smaller_text), tint, enabled = pt > 6) { apply { it.copy(sizePt = (pt - 1).toFloat()) } }
            Text("$pt", color = tint, style = MaterialTheme.typography.labelLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1, modifier = Modifier.width(26.dp))
            Tool(R.drawable.ic_add, stringResource(R.string.words_bigger_text), tint, enabled = pt < 72) { apply { it.copy(sizePt = (pt + 1).toFloat()) } }
        }
    }, {
        Swatches(tint, R.drawable.ic_format_color_text, stringResource(R.string.words_text_colour), TEXT_COLOURS) { c ->
            apply { it.copy(color = c?.let { k -> k.toArgb() and 0xFFFFFF }) }
        }
        Swatches(tint, R.drawable.ic_highlighter, stringResource(R.string.words_highlight), HIGHLIGHT_COLOURS) { c ->
            apply { it.copy(highlight = c?.let { k -> k.toArgb() and 0xFFFFFF }) }
        }
    }, {
        listOf(DocAlign.Start to R.drawable.ic_align_left, DocAlign.Center to R.drawable.ic_align_center, DocAlign.End to R.drawable.ic_align_right, DocAlign.Justify to R.drawable.ic_align_justify).forEach { (a, icon) ->
            Tool(icon, stringResource(when (a) { DocAlign.Center -> R.string.words_align_center; DocAlign.End -> R.string.words_align_end; DocAlign.Justify -> R.string.words_align_justify; else -> R.string.words_align_start }), tint, on = para.align == a) { change(alignCell(cell, sel.min, sel.max, a)) }
        }
    })
}

/**
 * A picture turned a quarter ([turn] 90 or -90) or flipped (asked 2026-09-28: pictures had few tools): as with Crop,
 * the result is a new picture, as big on the page (width and height swapped when turned), wrapped as it was.
 * ponytail: Word's own rotation (a:xfrm rot) would keep the original; a turned copy is what LibreOffice and Word show alike.
 */
internal fun transformedPicture(img: DocImage, bytes: ByteArray, turn: Int, flip: Boolean): DocImage? {
    val bitmap = OfficePages.croppedPicture(img, bytes, 2400) ?: return null // a cropped one turns as it shows
    val m = android.graphics.Matrix().apply { if (turn != 0) postRotate(turn.toFloat()); if (flip) postScale(-1f, 1f) }
    val out = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    val png = img.extension == "png" || img.part?.endsWith(".png", ignoreCase = true) == true
    val encoded = java.io.ByteArrayOutputStream().also { out.compress(if (png) android.graphics.Bitmap.CompressFormat.PNG else android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
    val (w, h) = if (turn != 0) img.heightPt to img.widthPt else img.widthPt to img.heightPt
    return DocImage(img.key, null, w, h, null, encoded, if (png) "png" else "jpg", PictureXml.wrapOf(img))
}

/** Insert › Symbol (Word's Ω): what a phone keyboard lacks, a tap puts it at the cursor. */
private val SYMBOLS = listOf(
    R.string.words_symbols_punctuation to "«»“”‘’„…–—•·¶§†‡‰′″",
    R.string.words_symbols_money to "€$£¥¢₿",
    R.string.words_symbols_signs to "©®™°№℮✓✗★☆",
    R.string.words_symbols_maths to "±×÷≠≈≤≥∞√∑∏∫∂∆πµΩ∈∉∩∪⊂⊃∅∀∃¬∧∨½¼¾⅓²³¹",
    R.string.words_symbols_arrows to "←→↑↓↔⇐⇒⇔↵",
)

/**
 * Insert › Link (Words', shared with Slides 17 Oct): [showText] asks for the words to show (nothing chosen to link); [linked] offers Remove link.
 * [slides] (Slides): a slide of the deck can be the link's end instead of a web address — [done] then gets "#" and its number from 0.
 */
@Composable
internal fun LinkDialog(showText: Boolean, startUrl: String?, startText: String?, linked: Boolean, dismiss: () -> Unit, remove: () -> Unit,
    slides: List<String>? = null, /** Slides: "The slide it came from" offered too (PowerPoint's Last Slide Viewed: "#back") */ back: Boolean = false,
    /** what [slides] are called (Tables: its sheets) */ place: String = stringResource(R.string.words_slide_in_deck), done: (String, String) -> Unit) {
    val startSlide = if (startUrl == "#back") -1 else startUrl?.takeIf { it.startsWith("#") }?.drop(1)?.toIntOrNull()
    var url by remember { mutableStateOf(startUrl?.takeIf { startSlide == null } ?: "https://") }
    var text by remember { mutableStateOf(startText ?: "") }
    var slide by remember { mutableStateOf(startSlide) } // Slides: a slide chosen, not a web address
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.words_link)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showText) OutlinedTextField(text, { text = it }, singleLine = true, label = { Text(stringResource(R.string.words_text_to_show)) })
                if (slides != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice(stringResource(R.string.words_web_page), slide == null) { slide = null }
                    Choice(place, slide != null && slide != -1) { slide = slide?.takeIf { it >= 0 } ?: 0 }
                    if (back) Choice(stringResource(R.string.words_slide_came_from), slide == -1) { slide = -1 }
                }
                if (slide == null) OutlinedTextField(url, { url = it }, singleLine = true, label = { Text(stringResource(R.string.words_address)) })
                else if (slide != -1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    slides.orEmpty().forEachIndexed { i, name -> Choice(if (name.isEmpty()) "${i + 1}" else "${i + 1}. $name", slide == i) { slide = i } }
                }
            }
        },
        confirmButton = { TextButton(onClick = { val to = slide?.let { if (it == -1) "#back" else "#$it" } ?: url; done(text.ifBlank { slide?.let { if (it == -1) "Back" else slides?.getOrNull(it) } ?: url }, to) }) { Text(stringResource(R.string.common_ok)) } },
        dismissButton = {
            Row {
                if (linked) TextButton(onClick = remove) { Text(stringResource(R.string.words_remove_link)) }
                TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
}

@Composable
internal fun SymbolDialog(dismiss: () -> Unit, insert: (String) -> Unit) {
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 640.dp).systemBarsPadding().padding(12.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.words_symbol), style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f, fill = false).shadedScroll().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    SYMBOLS.forEach { (title, chars) -> Section(stringResource(title)) {
                        chars.codePoints().toArray().forEach { c -> val s = String(Character.toChars(c))
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable { insert(s) },
                                contentAlignment = Alignment.Center) { Text(s, style = MaterialTheme.typography.titleLarge) }
                        }
                    } }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_close)) } }
            }
        }
    }
}

/**
 * Insert › Date and time: today as it will stand in the text. The long forms are the document's words, not the app's,
 * so they come in the phone's language, and in English too (APA papers are often written in English).
 */
internal fun dateForms(now: java.time.LocalDateTime = java.time.LocalDateTime.now(), locale: java.util.Locale = java.util.Locale.getDefault()): List<String> {
    fun f(p: String, l: java.util.Locale = locale) = now.format(java.time.format.DateTimeFormatter.ofPattern(p, l))
    val long = listOf(locale, java.util.Locale.UK).distinctBy { it.language }.flatMap { l ->
        listOf(f("d MMMM yyyy", l), now.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL).withLocale(l))) }
    return (listOf(f("dd/MM/yyyy"), f("yyyy-MM-dd")) + long + listOf(f("HH:mm"), f("dd/MM/yyyy HH:mm"))).distinct()
}

@Composable
internal fun DateDialog(dismiss: () -> Unit, insert: (String) -> Unit) {
    val forms = remember { dateForms() }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 640.dp).systemBarsPadding().padding(12.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.words_date_time), style = MaterialTheme.typography.titleLarge)
                Column(Modifier.padding(vertical = 12.dp)) { Section(stringResource(R.string.words_put_in_text)) { forms.forEach { s -> Choice(s, false) { insert(s) } } } }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } }
            }
        }
    }
}

/** Insert › Comment: who writes it (remembered) and what it says. */
@Composable
internal fun NewCommentDialog(name: String, dismiss: () -> Unit, title: String = stringResource(R.string.words_comment), start: String = "", confirm: String = stringResource(R.string.common_add), label: String = stringResource(R.string.words_comment), add: (String, String) -> Unit) {
    var author by remember { mutableStateOf(name) }
    var text by remember { mutableStateOf(start) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(author, { author = it.replace("\n", " ") }, label = { Text(stringResource(R.string.words_your_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(text, { text = it }, label = { Text(label) }, minLines = 3, modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(enabled = author.isNotBlank() && text.isNotBlank(), onClick = { add(author.trim(), text.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } })
}

/** A footnote's or endnote's words, to write or change; Delete takes the note out with its number. */
@Composable
internal fun NoteDialog(note: DocNote, number: String, dismiss: () -> Unit, save: (String) -> Unit, delete: () -> Unit) {
    var text by remember { mutableStateOf(note.paras.joinToString("\n") { it.text }) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(if (note.end) R.string.words_endnote_n else R.string.words_footnote_n, number)) },
        text = { OutlinedTextField(text, { text = it }, minLines = 3, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { save(text) }) { Text(stringResource(R.string.common_done)) } },
        dismissButton = { Row {
            TextButton(onClick = delete) { Text(stringResource(R.string.words_delete_note), color = Color(0xFFE57373)) }
            TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) }
        } })
}

/** Review › Track changes, the first time: the name the changes are recorded under (the comments' too). */
@Composable
internal fun ReviewerNameDialog(name: String, dismiss: () -> Unit, done: (String) -> Unit) {
    var author by remember { mutableStateOf(name) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.words_track_changes)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.words_track_name_hint))
            OutlinedTextField(author, { author = it.replace("\n", " ") }, label = { Text(stringResource(R.string.words_your_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(enabled = author.isNotBlank(), onClick = { done(author.trim()) }) { Text(stringResource(R.string.words_track_changes)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } })
}

/** A comment's date as the phone says dates, or null. */
internal fun commentDate(c: DocComment): String? = c.date?.let { d -> runCatching { java.time.OffsetDateTime.parse(if (d.endsWith("Z") || d.contains('+')) d else d + "Z") }.getOrNull()
    ?.atZoneSameInstant(java.time.ZoneId.systemDefault())?.format(java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)) }

/** One comment as a card: who, when, what; tapping it goes to its words; Delete takes it off (the words stay). */
@Composable
internal fun CommentCard(c: DocComment, here: Boolean, act: CommentActions, modifier: Modifier = Modifier) = Surface(
    color = if (c.done) Color(0xFFF1F3F4) else Color.White, contentColor = Color(0xFF202124).copy(alpha = if (c.done) 0.6f else 1f), shape = RoundedCornerShape(10.dp),
    border = androidx.compose.foundation.BorderStroke(if (here) 2.dp else 1.dp, if (here) Color(0xFFFFB300) else Color(0xFFDADCE0)),
    modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = { act.open(c) })) {
    @Composable fun who(x: DocComment) = Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(Color(0xFFFFB300)), contentAlignment = Alignment.Center) {
            Text(x.author.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase().ifEmpty { "?" }, color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(x.author.ifBlank { stringResource(R.string.words_comment) }, style = MaterialTheme.typography.labelLarge)
            commentDate(x)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Color(0xFF5F6368)) }
        }
    }
    Column(Modifier.padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 2.dp)) {
        who(c)
        if (c.done) Text(stringResource(R.string.words_resolved), style = MaterialTheme.typography.labelSmall, color = Color(0xFF188038), modifier = Modifier.padding(top = 2.dp))
        Text(c.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp, end = 8.dp))
        // Its replies, under it (Word's thread).
        act.repliesOf(c).forEach { r ->
            Column(Modifier.padding(start = 12.dp, top = 10.dp, end = 8.dp)) {
                who(r)
                Text(r.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            // Four of them in the pane's width: closer than a button's own padding.
            val tight = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
            TextButton(onClick = { act.edit(c) }, contentPadding = tight) { Text(stringResource(R.string.common_edit), color = Color(0xFF1A73E8), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            TextButton(onClick = { act.reply(c) }, contentPadding = tight) { Text(stringResource(R.string.words_reply), color = Color(0xFF1A73E8), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            // Slides' comments have none (PowerPoint's older list and Impress keep no resolved state)
            if (act.resolve != null) TextButton(onClick = { act.resolve.invoke(c) }, contentPadding = tight) { Text(stringResource(if (c.done) R.string.words_reopen else R.string.words_resolve), color = Color(0xFF1A73E8), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            TextButton(onClick = { act.delete(c) }, contentPadding = tight) { Text(stringResource(R.string.common_delete), color = Color(0xFF1A73E8), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** What a comment card can do: its replies, go to its words, delete it (and its replies), reply, resolve or reopen. */
internal class CommentActions(val repliesOf: (DocComment) -> List<DocComment>, val open: (DocComment) -> Unit, val delete: (DocComment) -> Unit,
    val reply: (DocComment) -> Unit, val resolve: ((DocComment) -> Unit)?, val edit: (DocComment) -> Unit = {})

/**
 * A tablet's comments beside the page (as Word shows them): each card at the height of its words, pushed down only
 * as far as the one above needs.
 */
@Composable
internal fun CommentPane(items: List<Pair<DocComment, androidx.compose.ui.unit.Dp>>, here: DocComment?, modifier: Modifier, act: CommentActions,
    placed: (List<Int>) -> Unit = {} /* each card's top as placed (px), pushed down below the one above: its line goes there */) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gap = with(density) { 8.dp.roundToPx() }
    val slack = with(density) { 48.dp.roundToPx() } // pushed further below its words than this: the pane is crowded
    val tops = items.map { with(density) { it.second.roundToPx() } }
    // Crowded (the user, 2026-10-14): every card but the one at the cursor folds to a line, so each stays by its words;
    // a tap on it (or on its words) opens it.
    androidx.compose.ui.layout.SubcomposeLayout(modifier) { constraints ->
        val loose = constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity)
        fun stack(heights: List<Int>): List<Int> { var bottom = 0; return heights.mapIndexed { i, h -> maxOf(tops[i], bottom).also { bottom = it + h + gap } } }
        val full = subcompose("full") { items.forEach { (c, _) -> CommentCard(c, c == here, act) } }.map { it.measure(loose) }
        val crowded = stack(full.map { it.height }).withIndex().any { (i, y) -> y - tops[i] > slack }
        val placeables = if (!crowded) full else subcompose("folded") {
            items.forEach { (c, _) -> if (c == here) CommentCard(c, true, act) else FoldedComment(c, act) }
        }.map { it.measure(loose) }
        val ys = stack(placeables.map { it.height })
        placed(ys)
        layout(constraints.maxWidth, (ys.lastOrNull() ?: 0) + (placeables.lastOrNull()?.height ?: 0)) { placeables.forEachIndexed { i, p -> p.place(0, ys[i]) } }
    }
}

/** A comment folded to one line (a crowded pane): who, and its words' start. */
@Composable
private fun FoldedComment(c: DocComment, act: CommentActions) = Surface(
    color = if (c.done) Color(0xFFF1F3F4) else Color.White, contentColor = Color(0xFF202124), shape = RoundedCornerShape(8.dp),
    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFDADCE0)),
    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = { act.open(c) })) {
    Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(Color(0xFFFFB300)), contentAlignment = Alignment.Center) {
            Text(c.author.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase().ifEmpty { "?" }, color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
        Text(c.text.replace('\n', ' '), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f).padding(start = 8.dp))
        val replies = act.repliesOf(c).size
        if (replies > 0) Text("+$replies", style = MaterialTheme.typography.labelSmall, color = Color(0xFF5F6368))
    }
}

/** A phone's comments: a sidebar over the page, every comment in the order of its words. */
@Composable
internal fun CommentSidebar(comments: List<DocComment>, here: DocComment?, dismiss: () -> Unit, act: CommentActions) {
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null, onClick = dismiss)) {
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.86f).clickable(interactionSource = null, indication = null) {}) {
                Column(Modifier.systemBarsPadding().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.words_comments), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = dismiss) { Text(stringResource(R.string.common_close)) }
                    }
                    if (comments.isEmpty()) Text(stringResource(R.string.words_no_comments_short), modifier = Modifier.padding(top = 12.dp))
                    Column(Modifier.shadedScroll().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        comments.forEach { c -> CommentCard(c, c == here, act) }
                    }
                }
            }
        }
    }
}

/** [LineDash.CHOICES]' word for [k] as shown ([name]: the English one, kept for anything new). */
@Composable private fun dashName(k: String?, name: String) = when (k) {
    null -> stringResource(R.string.words_line_solid); "sysDot" -> stringResource(R.string.words_dash_round_dot); "sysDash" -> stringResource(R.string.words_dash_square_dot)
    "dash" -> stringResource(R.string.words_dash_dash); "dashDot" -> stringResource(R.string.words_dash_dash_dot); "lgDash" -> stringResource(R.string.words_dash_long)
    "lgDashDot" -> stringResource(R.string.words_dash_long_dot); "lgDashDotDot" -> stringResource(R.string.words_dash_long_dot_dot); else -> name
}

/** Word's Insert › Shapes gallery, its groups in its order (DrawingML's preset names). */
internal val SHAPE_GROUPS = listOf(
    R.string.words_shapes_lines to listOf("line", "lineArrow", "lineArrows"),
    R.string.words_shapes_rectangles to listOf("rect", "roundRect", "snip1Rect", "snip2SameRect", "snip2DiagRect", "snipRoundRect", "round1Rect", "round2SameRect", "round2DiagRect"),
    R.string.words_shapes_basic to listOf("ellipse", "triangle", "rtTriangle", "parallelogram", "trapezoid", "diamond", "pentagon", "hexagon", "heptagon", "octagon", "decagon",
        "dodecagon", "pie", "chord", "teardrop", "frame", "halfFrame", "corner", "diagStripe", "plus", "plaque", "can", "cube", "bevel", "donut", "noSmoking",
        "blockArc", "foldedCorner", "smileyFace", "heart", "lightningBolt", "sun", "moon", "cloud", "arc", "bracketPair", "bracePair", "leftBracket", "rightBracket",
        "leftBrace", "rightBrace"),
    R.string.words_shapes_block_arrows to listOf("rightArrow", "leftArrow", "upArrow", "downArrow", "leftRightArrow", "upDownArrow", "quadArrow", "leftRightUpArrow", "bentArrow",
        "uturnArrow", "leftUpArrow", "bentUpArrow", "curvedRightArrow", "curvedLeftArrow", "curvedUpArrow", "curvedDownArrow", "stripedRightArrow",
        "notchedRightArrow", "homePlate", "chevron", "rightArrowCallout", "downArrowCallout", "leftArrowCallout", "upArrowCallout", "leftRightArrowCallout",
        "quadArrowCallout", "circularArrow"),
    R.string.words_shapes_equation to listOf("mathPlus", "mathMinus", "mathMultiply", "mathDivide", "mathEqual", "mathNotEqual"),
    R.string.words_shapes_flowchart to listOf("flowChartProcess", "flowChartAlternateProcess", "flowChartDecision", "flowChartInputOutput", "flowChartPredefinedProcess",
        "flowChartInternalStorage", "flowChartDocument", "flowChartMultidocument", "flowChartTerminator", "flowChartPreparation", "flowChartManualInput",
        "flowChartManualOperation", "flowChartConnector", "flowChartOffpageConnector", "flowChartPunchedCard", "flowChartPunchedTape", "flowChartSummingJunction",
        "flowChartOr", "flowChartCollate", "flowChartSort", "flowChartExtract", "flowChartMerge", "flowChartOnlineStorage", "flowChartDelay",
        "flowChartMagneticTape", "flowChartMagneticDisk", "flowChartMagneticDrum", "flowChartDisplay"),
    R.string.words_shapes_stars to listOf("irregularSeal1", "irregularSeal2", "star4", "star5", "star6", "star7", "star8", "star10", "star12", "star16", "star24", "star32",
        "ribbon2", "ribbon", "ellipseRibbon2", "ellipseRibbon", "verticalScroll", "horizontalScroll", "wave", "doubleWave"),
    R.string.words_shapes_callouts to listOf("wedgeRectCallout", "wedgeRoundRectCallout", "wedgeEllipseCallout", "cloudCallout", "borderCallout1", "borderCallout2", "borderCallout3",
        "accentCallout1", "accentCallout2", "accentCallout3", "callout1", "callout2", "callout3", "accentBorderCallout1", "accentBorderCallout2", "accentBorderCallout3"),
)

/** The colours a shape's fill and outline are chosen from: Office's theme accents, black, white and its standard ones. */
internal val SHAPE_COLOURS = listOf(0xFF4472C4, 0xFFED7D31, 0xFFA5A5A5, 0xFFFFC000, 0xFF5B9BD5, 0xFF70AD47, 0xFF000000, 0xFFFFFFFF,
    0xFFC00000, 0xFFFF0000, 0xFF00B050, 0xFF7030A0).map { it.toInt() }

/**
 * Word's Shape Styles (asked 16 Oct: more than seven choices): seven colours in four looks — outline only, filled, a
 * light fill with its outline, a dark fill — each tile drawn as the shape will be; a tap sets both fill and outline.
 */
@Composable
internal fun ShapeStyles(fill: Int?, line: Int?, pick: (fill: Int?, line: Int?) -> Unit) {
    fun mix(c: Int, toward: Int, k: Float): Int {
        fun ch(s: Int) = ((((c shr s) and 255) * (1 - k) + ((toward shr s) and 255) * k).toInt() and 255) shl s
        return 0xFF000000.toInt() or ch(16) or ch(8) or ch(0)
    }
    val base = listOf(0xFF000000, 0xFF4472C4, 0xFFED7D31, 0xFFA5A5A5, 0xFFFFC000, 0xFF5B9BD5, 0xFF70AD47).map { it.toInt() }
    val looks = listOf<(Int) -> Pair<Int?, Int?>>(
        { c -> null to c },                                        // outline only
        { c -> c to mix(c, 0xFF000000.toInt(), 0.3f) },            // filled, a darker outline
        { c -> mix(c, 0xFFFFFFFF.toInt(), 0.8f) to c },            // light fill, its outline
        { c -> mix(c, 0xFF000000.toInt(), 0.5f) to null },         // dark fill
    )
    Section(stringResource(R.string.words_styles)) {
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 7) {
            for (look in looks) for (c in base) {
                val (f, l) = look(c)
                val on = f?.or(0xFF000000.toInt()) == fill?.or(0xFF000000.toInt()) && l == line
                // seven to a row: each row one look, each column one colour
                Box(Modifier.weight(1f).widthIn(max = 48.dp).aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Color.White)
                    .border(if (on) 3.dp else 0.dp, if (on) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable { pick(f, l) }.padding(6.dp)) {
                    val preview = remember(f, l) { ShapePicture("roundRect", f, l, 1.5f) }
                    androidx.compose.foundation.Canvas(Modifier.matchParentSize()) { drawIntoCanvas { cv -> preview.drawAt(cv.nativeCanvas, 2f, 2f, size.width - 4f, size.height - 4f) } }
                }
            }
        }
    }
}

/** A shape's size as put in (pt), Word's way: an inch square; a line two inches across; long ones (sideways arrows, banners, scrolls,
 *  waves) half as tall as wide; up and down arrows the other way round (the tablet walk, 18 Oct: ⇔ and ⇕ looked like diamonds). */
internal fun shapeSize(kind: String): Pair<Float, Float> = when {
    kind == "line" || kind.startsWith("lineArrow") -> 144f to 0.75f
    Regex("(?i)^(right|left|leftRight|striped|notched)Arrow$|^(homePlate|chevron|ribbon2?|ellipseRibbon2?|horizontalScroll|wave|doubleWave)$").matches(kind) -> 108f to 54f
    Regex("(?i)^(up|down|upDown)Arrow$|^verticalScroll$").matches(kind) -> 54f to 108f
    else -> 72f to 72f
}

/**
 * Insert › Shapes (asked 2026-09-29, OnlyOffice's and Word's gallery): Word's groups of shapes, the ones used last first,
 * each drawn as it will be in the colours chosen above them — a fill (or none) and an outline. A tap puts it at the cursor.
 */
@Composable
internal fun ShapesDialog(odt: Boolean, dismiss: () -> Unit, insert: (kind: String, fill: Int?, line: Int?) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("writer", android.content.Context.MODE_PRIVATE) }
    val recent = remember { prefs.getString("recentShapes", "").orEmpty().split(',').filter { it in ShapeGeometry.presets || it == "line" || it.startsWith("lineArrow") } }
    val colors = SHAPE_COLOURS
    var fill by remember { mutableStateOf<Int?>(0xFF4472C4.toInt()) }
    var line by remember { mutableStateOf<Int?>(0xFF2F528F.toInt()) }
    fun put(kind: String) {
        prefs.edit().putString("recentShapes", (listOf(kind) + recent.filter { it != kind }).take(10).joinToString(",")).apply()
        insert(kind, fill, line ?: if (fill == null) 0xFF000000.toInt() else null)
    }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 720.dp).systemBarsPadding().padding(12.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.words_shapes), style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f, fill = false).shadedScroll().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    @Composable fun swatches(title: String, chosen: Int?, set: (Int?) -> Unit) = Section(title) {
                        Choice(stringResource(R.string.common_none), chosen == null) { set(null) }
                        colors.forEach { c -> Box(Modifier.size(34.dp).clip(CircleShape).background(Color(c))
                            .border(3.dp, if (chosen?.let { it or 0xFF000000.toInt() } == c) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                            .clickable { set(c) }) }
                    }
                    ShapeStyles(fill, line) { f, l -> fill = f; line = l }
                    swatches(stringResource(R.string.words_fill), fill?.let { it or 0xFF000000.toInt() }) { fill = it }
                    swatches(stringResource(R.string.words_outline), line) { line = it }
                    (listOfNotNull((R.string.words_recently_used to recent).takeIf { recent.isNotEmpty() }) + SHAPE_GROUPS).forEach { (title, kinds) ->
                        val shown = kinds.filter { !odt || (it != "line" && !it.startsWith("lineArrow")) }
                        if (shown.isNotEmpty()) Section(stringResource(title)) {
                            shown.forEach { kind ->
                                Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Color.White).clickable { put(kind) }.padding(7.dp)) {
                                    val preview = remember(kind, fill, line) { ShapePicture(kind, fill, line ?: if (fill == null) 0xFF000000.toInt() else null, 1.5f) }
                                    androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                                        // in its proportions as put in, in the middle of the square
                                        val (sw, sh) = shapeSize(kind).let { (a, b) -> if (kind == "line" || kind.startsWith("lineArrow")) 1f to 1f else a to b }
                                        val k = minOf((size.width - 4f) / sw, (size.height - 4f) / sh); val pw = sw * k; val ph = sh * k
                                        drawIntoCanvas { c -> preview.drawAt(c.nativeCanvas, (size.width - pw) / 2, (size.height - ph) / 2, pw, ph) }
                                    }
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_close)) } }
            }
        }
    }
}

/** What Picture › Shape options sets: fill and outline (null: none), the outline's width and dash ([LineDash]'s names), size, angle, alt text, a shadow. */
internal data class ShapeOptions(val fill: Int?, val line: Int?, val lineWidth: Float, val widthPt: Float, val heightPt: Float, val degrees: Float, val alt: String,
    val dash: String? = null, val shadow: Boolean = false)

/**
 * Picture › Shape options (OnlyOffice's "Advanced settings", Word's Format Shape): the shape's fill and outline, the
 * outline's width, its size (proportions locked or not) in the document's units, its angle, its text for screen readers.
 */
@Composable
internal fun ShapeOptionsDialog(start: ShapeOptions, dismiss: () -> Unit, apply: (ShapeOptions) -> Unit) {
    var o by remember { mutableStateOf(start) }
    var locked by remember { mutableStateOf(true) }
    val colors = SHAPE_COLOURS
    val unit = WriterUnits.pt
    val unitName = WriterUnits.name
    fun shown(pt: Float) = "%.2f".format(java.util.Locale.ROOT, pt / unit).trimEnd('0').trimEnd('.')
    var wText by remember { mutableStateOf(shown(start.widthPt)) }
    var hText by remember { mutableStateOf(shown(start.heightPt)) }
    var degText by remember { mutableStateOf(start.degrees.toInt().toString()) }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 640.dp).systemBarsPadding().imePadding().padding(12.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.words_shape_options), style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().shadedScroll().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    @Composable fun swatches(title: String, chosen: Int?, set: (Int?) -> Unit) = Section(title) {
                        Choice(stringResource(R.string.common_none), chosen == null) { set(null) }
                        colors.forEach { c -> Box(Modifier.size(34.dp).clip(CircleShape).background(Color(c)).border(1.dp, Color(0x55888888), CircleShape)
                            .border(3.dp, if (chosen?.let { it or 0xFF000000.toInt() } == c) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                            .clickable { set(c) }) }
                    }
                    ShapeStyles(o.fill, o.line) { f, l -> o = o.copy(fill = f, line = l) }
                    swatches(stringResource(R.string.words_fill), o.fill) { o = o.copy(fill = it) }
                    swatches(stringResource(R.string.words_outline), o.line) { o = o.copy(line = it) }
                    if (o.line != null) Section(stringResource(R.string.words_outline_width)) {
                        listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2.25f, 3f, 4.5f, 6f).forEach { w -> Choice("${if (w % 1f == 0f) w.toInt() else w} pt", o.lineWidth == w) { o = o.copy(lineWidth = w) } }
                    }
                    if (o.line != null) Section(stringResource(R.string.words_dash_type)) { LineDash.CHOICES.forEach { (k, name) -> Choice(dashName(k, name), o.dash == k) { o = o.copy(dash = k) } } }
                    Section(stringResource(R.string.words_shadow)) { Choice(stringResource(R.string.common_none), !o.shadow) { o = o.copy(shadow = false) }; Choice(stringResource(R.string.words_shadow_offset), o.shadow) { o = o.copy(shadow = true) } }
                    Section(stringResource(R.string.words_size_unit, unitName)) {
                        OutlinedTextField(wText, { v -> wText = v; v.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }?.let { n ->
                            val w = n * unit; o = if (locked) { val h = o.heightPt * w / o.widthPt.coerceAtLeast(0.1f); hText = shown(h); o.copy(widthPt = w, heightPt = h) } else o.copy(widthPt = w) } },
                            label = { Text(stringResource(R.string.words_width)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.widthIn(max = 130.dp))
                        OutlinedTextField(hText, { v -> hText = v; v.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }?.let { n ->
                            val h = n * unit; o = if (locked) { val w = o.widthPt * h / o.heightPt.coerceAtLeast(0.1f); wText = shown(w); o.copy(widthPt = w, heightPt = h) } else o.copy(heightPt = h) } },
                            label = { Text(stringResource(R.string.words_height)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.widthIn(max = 130.dp))
                        Choice(stringResource(R.string.words_lock_proportions), locked) { locked = !locked }
                    }
                    Section(stringResource(R.string.words_rotation)) {
                        OutlinedTextField(degText, { v -> degText = v; v.toFloatOrNull()?.let { o = o.copy(degrees = ((it % 360f) + 360f) % 360f) } }, label = { Text(stringResource(R.string.words_degrees)) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.widthIn(max = 130.dp))
                    }
                    OutlinedTextField(o.alt, { o = o.copy(alt = it) }, label = { Text(stringResource(R.string.words_alt_text)) }, maxLines = 3, modifier = Modifier.fillMaxWidth())
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) }
                    TextButton(onClick = { apply(o) }) { Text(stringResource(R.string.common_ok)) }
                }
            }
        }
    }
}
