package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale

/** The drop cap's colours: the Home tab's text colours. */
private val DROP_COLORS = listOf(0x000000, 0xC00000, 0x0070C0, 0x00B050, 0x7030A0, 0xED7D31)

/**
 * Insert › Drop Cap › Drop cap options (Word's, and OnlyOffice's Αρχίγραμμα the user showed, 2026-09-30): where it
 * stands, how many lines it drops, how far from the text, the letter's font and colour. [odt]: LibreOffice has no drop cap in the margin.
 */
@Composable
internal fun DropCapDialog(now: DropCap?, odt: Boolean, nowFont: String?, nowColor: Int?, dismiss: () -> Unit,
    /** Slides' (17 Oct): None, Dropped (the letter in a box of its own beside the lines) or Raised — the first letter as tall as [DropCap.lines] lines, in its line */ raised: Boolean = false,
    apply: (DropCap?, String?, Int?) -> Unit) {
    var font by remember { mutableStateOf(nowFont) } // null: the text's own
    var color by remember { mutableStateOf(nowColor) } // null: automatic
    var place by remember { mutableIntStateOf(when { now == null -> 0; now.raised -> 3; now.margin && !odt -> 2; else -> 1 }) }
    var lines by remember { mutableIntStateOf(now?.lines ?: 3) }
    var distance by remember { mutableFloatStateOf(now?.distance ?: 0f) } // pt
    val step = if (WriterUnits.inches) 0.05f * 72f else 0.1f * 28.3465f
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.words_drop_cap)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.words_position), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice(stringResource(R.string.common_none), place == 0) { place = 0 }
                    Choice(stringResource(R.string.words_dropped), place == 1) { place = 1 }
                    if (!odt && !raised) Choice(stringResource(R.string.words_in_margin), place == 2) { place = 2 }
                    if (raised) Choice(stringResource(R.string.words_raised), place == 3) { place = 3 }
                }
                if (odt && !raised) Text(stringResource(R.string.words_odt_no_margin_drop), style = MaterialTheme.typography.bodySmall)
                if (raised) Text(stringResource(R.string.words_slides_drop_cap_hint), style = MaterialTheme.typography.bodySmall)
                if (place != 0) {
                    @Composable fun stepper(label: String, value: String, less: () -> Unit, more: () -> Unit) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(label, modifier = Modifier.width(150.dp))
                        Choice("−", false, less)
                        Text(value, textAlign = TextAlign.Center, modifier = Modifier.width(64.dp))
                        Choice("+", false, more)
                    }
                    stepper(stringResource(if (place == 3) R.string.words_lines_tall else R.string.words_lines_to_drop), "$lines", { lines = (lines - 1).coerceAtLeast(2) }, { lines = (lines + 1).coerceAtMost(10) })
                    if (place != 3) stepper(stringResource(R.string.words_distance_from_text), "%.2f".format(Locale.ROOT, WriterUnits.of(distance)).trimEnd('0').trimEnd('.') + " " + WriterUnits.name,
                        { distance = (distance - step).coerceAtLeast(0f) }, { distance = (distance + step).coerceAtMost(72f) })
                    // The letter's own look (asked 2026-09-30): its font and colour, as the text's letters have theirs.
                    Text(stringResource(R.string.words_font), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Choice(stringResource(R.string.words_as_the_text), font == null) { font = null }
                        (STYLE_FONTS + listOfNotNull(font?.takeIf { f -> STYLE_FONTS.none { it.equals(f, true) } })).forEach { f -> Choice(f, font.equals(f, true)) { font = f } }
                    }
                    Text(stringResource(R.string.words_colour), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Choice(stringResource(R.string.words_automatic), color == null) { color = null }
                        DROP_COLORS.forEach { c -> Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF000000 or c.toLong()))
                            .border(3.dp, if (color == c) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape).clickable { color = c }) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { apply(if (place == 0) null else DropCap(lines, place == 2, distance, now?.length ?: 1, place == 3), font, color) }) { Text(stringResource(R.string.common_ok)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
