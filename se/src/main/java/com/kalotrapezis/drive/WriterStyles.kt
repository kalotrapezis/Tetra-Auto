package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Home's Styles… page (asked 2026-09-29): every paragraph style — Normal, Title, Subtitle, the six headings, Quote,
 * then the document's own — each written in its own look, the paragraph's own marked. A tap puts it on the paragraph;
 * Change… opens the style itself (every paragraph with it changes).
 */
@Composable
internal fun StylesDialog(styles: DocStyles, here: StyleEntry?, basePt: Float, dismiss: () -> Unit, edit: (StyleEntry) -> Unit, choose: (StyleEntry) -> Unit) {
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 640.dp).systemBarsPadding().imePadding().padding(12.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(vertical = 20.dp)) {
                Text(stringResource(R.string.words_styles), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
                // On white, as on the page: a style's own colour shows as it will print.
                Surface(color = Color.White, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f, fill = false).padding(12.dp)) {
                Column(Modifier.fillMaxWidth().shadedScroll().padding(vertical = 4.dp)) {
                    styles.paragraphStyles().forEach { entry ->
                        val look = styles.look(DocPara(emptyList(), heading = entry.heading, keep = entry.id?.let { DocKeep(style = it) }))
                        val on = entry == here
                        Row(Modifier.fillMaxWidth().background(if (on) Color(0xFFE8F0FE) else Color.Transparent).clickable { choose(entry) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            // The name in the style's own size (within reason), weight, slant and colour.
                            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                fontSize = ((look.sizePt ?: basePt) / basePt * 16f).coerceIn(12f, 30f).sp,
                                fontWeight = if (look.bold == true) FontWeight.Bold else FontWeight.Normal,
                                fontStyle = if (look.italic == true) FontStyle.Italic else FontStyle.Normal,
                                color = look.color?.takeIf { it != 0 }?.let { Color(0xFF000000 or it.toLong()) } ?: Color.Black)
                            if (on) Text("✓", color = Color(0xFF1A73E8), style = MaterialTheme.typography.titleMedium)
                            if (styles.styleKey(entry) != null) TextButton(onClick = { edit(entry) }) { Text(stringResource(R.string.words_change_more), color = Color(0xFF1A73E8), maxLines = 1) }
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = Color(0x1F000000))
                    }
                }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.common_close)) }
                }
            }
        }
    }
}
