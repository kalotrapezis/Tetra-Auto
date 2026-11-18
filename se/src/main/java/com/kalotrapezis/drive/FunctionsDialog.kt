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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Formulas › Insert function (Excel's): the cell bar's functions ([SheetFunctions.all]) by Excel's categories. */
internal object FunctionCategories {
    val ALL: List<Pair<String, Set<String>>> = listOf(
        "Maths" to setOf("SUM", "SEQUENCE", "SUMIF", "SUMIFS", "PRODUCT", "ABS", "ROUND", "ROUNDUP", "ROUNDDOWN", "MROUND", "INT", "TRUNC", "CEILING", "CEILING.MATH", "FLOOR", "FLOOR.MATH",
            "MOD", "POWER", "SQRT", "EXP", "LN", "LOG10", "PI", "SIGN"),
        "Statistics" to setOf("AVERAGE", "AVERAGEIF", "AVERAGEIFS", "MEDIAN", "MAX", "MAXIFS", "MIN", "MINIFS", "LARGE", "SMALL", "COUNT", "COUNTA", "COUNTBLANK", "COUNTIF", "COUNTIFS",
            "RANK", "RANK.EQ", "STDEV", "STDEV.S", "STDEV.P", "STDEVP", "VAR", "VAR.S", "VAR.P", "VARP"),
        "Logical" to setOf("IF", "IFERROR", "IFNA", "AND", "OR", "NOT"),
        "Text" to setOf("CONCAT", "CONCATENATE", "TEXTJOIN", "LEFT", "RIGHT", "MID", "LEN", "FIND", "SEARCH", "SUBSTITUTE", "TRIM", "UPPER", "LOWER", "PROPER", "REPT", "EXACT", "TEXT", "VALUE"),
        "Date and time" to setOf("TODAY", "NOW", "DATE", "YEAR", "MONTH", "DAY", "WEEKDAY", "HOUR", "MINUTE", "SECOND", "DAYS", "EDATE", "EOMONTH"),
        "Lookup" to setOf("XLOOKUP", "VLOOKUP", "HLOOKUP", "INDEX", "MATCH", "CHOOSE", "ROW", "ROWS", "COLUMN", "COLUMNS", "FILTER", "SORT", "UNIQUE"),
        "Information" to setOf("ISBLANK", "ISNUMBER", "ISTEXT", "ISERROR", "ISNA", "NA"),
    )
    /** A category's name as shown (its id, the English name, is what the code goes by). */
    val LABELS = mapOf("Maths" to R.string.tables_cat_maths, "Statistics" to R.string.tables_cat_statistics, "Logical" to R.string.tables_cat_logical,
        "Text" to R.string.tables_cat_text, "Date and time" to R.string.tables_cat_date_time, "Lookup" to R.string.tables_cat_lookup,
        "Information" to R.string.tables_cat_information, "Other" to R.string.tables_cat_other, RECENT to R.string.tables_recently_used)
    /** The bar's functions in [category]; the ones no category names go under "Other". */
    fun of(category: String) = SheetFunctions.all.filter { f -> ALL.firstOrNull { it.first == category }?.second?.contains(f.name) ?: ALL.none { f.name in it.second } }
}

private const val RECENT = "Recently used"

/** Excel's Insert Function: categories, a search, each function's arguments and what it does; [pick] gets its name. */
@Composable
internal fun FunctionsDialog(dismiss: () -> Unit, pick: (String) -> Unit) {
    // Excel's "Most Recently Used" first, when there are any
    val context = androidx.compose.ui.platform.LocalContext.current
    val recent = TextEditorStore(context).recentFunctions.let { r -> remember { r.mapNotNull { n -> SheetFunctions.all.firstOrNull { it.name == n } } } }
    var category by remember { mutableStateOf(if (recent.isNotEmpty()) RECENT else FunctionCategories.ALL.first().first) }
    var query by remember { mutableStateOf("") }
    val shown = if (query.isBlank()) (if (category == RECENT) recent else FunctionCategories.of(category))
        else SheetFunctions.all.filter { f -> f.name.contains(query.trim(), true) || context.getString(f.says).contains(query.trim(), true) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.tables_insert_function)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text(stringResource(R.string.tables_search_functions)) }, modifier = Modifier.fillMaxWidth())
            if (query.isBlank()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOfNotNull(RECENT.takeIf { recent.isNotEmpty() }) + FunctionCategories.ALL.map { it.first } + "Other").forEach { c -> Choice(stringResource(FunctionCategories.LABELS.getValue(c)), c == category) { category = c } } }
            LazyColumn(Modifier.heightIn(max = 360.dp)) { items(shown, key = { it.name }) { f ->
                Column(Modifier.fillMaxWidth().clickable { pick(f.name) }.padding(vertical = 8.dp)) {
                    Text("${f.name}(${if (f.args == 0) "" else stringResource(f.args)})", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(f.says), style = MaterialTheme.typography.bodySmall)
                } } }
        } }, confirmButton = {}, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_close), maxLines = 1, overflow = TextOverflow.Ellipsis) } })
}
