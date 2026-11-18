package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** Word's first chart: three series over four categories (Insert › Chart starts from it, as Word's does). */
internal val WORD_SAMPLE_CHART = DocChart(ChartKind.Column, "Chart title", (1..4).map { "Category $it" }, listOf(
    ChartSeries("Series 1", listOf(4.3, 2.5, 3.5, 4.5)), ChartSeries("Series 2", listOf(2.4, 4.4, 1.8, 2.8)), ChartSeries("Series 3", listOf(2.0, 2.0, 3.0, 5.0))))

/**
 * Insert › Chart and Picture › Edit data (the plan, 2026-10-01): its kind, title and data — a grid of categories
 * (rows) by series (columns), as Word's little sheet — with the chart drawn above as it will look. Both screens: the
 * grid scrolls sideways on a phone.
 */
@Composable
internal fun ChartDialog(start: DocChart, editing: Boolean, dismiss: () -> Unit, done: (DocChart) -> Unit) {
    var kind by remember { mutableStateOf(start.kind) }
    var title by remember { mutableStateOf(start.title.orEmpty()) }
    var stacked by remember { mutableStateOf(start.stacked) }
    var catTitle by remember { mutableStateOf(start.catTitle.orEmpty()) }
    var valTitle by remember { mutableStateOf(start.valTitle.orEmpty()) }
    var labels by remember { mutableStateOf(start.dataLabels) }
    val categories = remember { mutableStateListOf(*start.categories.toTypedArray()) }
    val names = remember { mutableStateListOf(*start.series.map { it.name }.toTypedArray()) }
    // The numbers as typed (a comma or a point for decimals), a list per series.
    fun shown(v: Double?) = v?.let { if (it == Math.rint(it)) it.toLong().toString() else it.toString() } ?: ""
    val cells = remember { mutableStateListOf(*start.series.map { s -> mutableStateListOf(*start.categories.indices.map { shown(s.values.getOrNull(it)) }.toTypedArray()) }.toTypedArray()) }
    fun chart() = DocChart(kind, title.ifBlank { null }, categories.toList(), names.indices.map { k ->
        ChartSeries(names[k], cells[k].map { it.trim().replace(',', '.').toDoubleOrNull() }, start.series.getOrNull(k)?.color, start.series.getOrNull(k)?.pointColors.orEmpty())
    }, stacked && kind != ChartKind.Pie && kind != ChartKind.Doughnut && kind != ChartKind.Scatter, start.legend,
        catTitle.ifBlank { null }, valTitle.ifBlank { null }, labels)
    AlertDialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false), modifier = Modifier.widthIn(max = 720.dp).padding(12.dp),
        title = { Text(stringResource(if (editing) R.string.words_edit_chart else R.string.words_chart)) },
        text = {
            Column(Modifier.shadedScroll(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // As it will look.
                val preview = chart()
                Canvas(Modifier.fillMaxWidth().height(180.dp).background(Color.White)) { drawIntoCanvas { c ->
                    val k = size.height / 230f
                    val n = c.nativeCanvas
                    n.save(); n.scale(k, k)
                    ChartDrawable(preview).apply { setBounds(0, 0, (size.width / k).toInt(), 230) }.draw(n)
                    n.restore()
                } }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChartKind.entries.filter { it.made || it == kind }.forEach { k -> FilterChip(kind == k, { kind = k }, label = { Text(k.label) }) }
                }
                OutlinedTextField(title, { title = it }, singleLine = true, label = { Text(stringResource(R.string.words_title)) }, modifier = Modifier.fillMaxWidth())
                // Word's Chart elements: axis titles (not for a pie), data labels; and stacked.
                if (kind != ChartKind.Pie && kind != ChartKind.Doughnut) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val bar = kind == ChartKind.Bar
                    OutlinedTextField(catTitle, { catTitle = it }, singleLine = true, label = { Text(stringResource(if (bar) R.string.words_vertical_axis_title else R.string.words_horizontal_axis_title)) }, modifier = Modifier.weight(1f))
                    OutlinedTextField(valTitle, { valTitle = it }, singleLine = true, label = { Text(stringResource(if (bar) R.string.words_horizontal_axis_title else R.string.words_vertical_axis_title)) }, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.words_data_labels), style = MaterialTheme.typography.labelLarge); Switch(labels, { labels = it })
                    if (kind != ChartKind.Pie && kind != ChartKind.Doughnut && kind != ChartKind.Scatter) { Text(stringResource(R.string.words_stacked), style = MaterialTheme.typography.labelLarge); Switch(stacked, { stacked = it }) }
                }
                if (kind == ChartKind.Scatter) Text(stringResource(R.string.words_scatter_hint), style = MaterialTheme.typography.bodySmall)
                // The data: a row per category, a column per series (a pie shows the first).
                val cell = Modifier.width(96.dp)
                val first = Modifier.width(128.dp) // the categories' names
                Column(Modifier.horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("", modifier = first)
                        names.indices.forEach { k -> OutlinedTextField(names[k], { names[k] = it }, singleLine = true, modifier = cell, textStyle = MaterialTheme.typography.labelLarge) }
                    }
                    categories.indices.forEach { i ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedTextField(categories[i], { categories[i] = it }, singleLine = true, modifier = first, textStyle = MaterialTheme.typography.labelLarge)
                            names.indices.forEach { k -> OutlinedTextField(cells[k][i], { cells[k][i] = it }, singleLine = true, modifier = cell,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), textStyle = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { categories += "Category ${categories.size + 1}"; cells.forEach { it += "" } }) { Text(stringResource(R.string.words_add_category)) }
                    TextButton(enabled = categories.size > 1, onClick = { categories.removeAt(categories.lastIndex); cells.forEach { it.removeAt(it.lastIndex) } }) { Text(stringResource(R.string.words_remove_category)) }
                    TextButton(onClick = { names += "Series ${names.size + 1}"; cells += mutableStateListOf(*Array(categories.size) { "" }) }) { Text(stringResource(R.string.words_add_series)) }
                    TextButton(enabled = names.size > 1, onClick = { names.removeAt(names.lastIndex); cells.removeAt(cells.lastIndex) }) { Text(stringResource(R.string.words_remove_series)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { done(chart()) }) { Text(stringResource(if (editing) R.string.common_done else R.string.words_insert)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } })
}
