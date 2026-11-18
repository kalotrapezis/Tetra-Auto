package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A heading in the navigation pane: its paragraph, its level (1–6) and its words. */
internal class NavHeading(val para: Int, val level: Int, val text: String)

/** The document's headings, in order (Word's Navigation pane lists the outline levels; here the heading styles). */
internal fun navHeadings(doc: OfficeDoc) = doc.paras.mapIndexedNotNull { i, p ->
    p.text.replace(LINE_BREAK, ' ').replace(OBJECT_MARK.toString(), "").trim().takeIf { p.heading in 1..6 && it.isNotEmpty() }?.let { NavHeading(i, p.heading, it) }
}

/**
 * View › Navigation (the roadmap's "rest of Word", 2026-10-01): the headings beside the page, indented by level; a tap
 * goes to one — its page in the pages, the cursor to it in the editor.
 */
@Composable
internal fun NavigationPane(headings: List<NavHeading>, w: Color, modifier: Modifier, go: (NavHeading) -> Unit, close: () -> Unit) {
    Column(modifier.fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(Color(0xFF16191A))) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.words_navigation), color = w, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Tool(R.drawable.ic_close, stringResource(R.string.common_close), w) { close() }
        }
        if (headings.isEmpty()) Text(stringResource(R.string.words_no_headings), color = w.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            items(headings, key = { it.para }) { h ->
                Text(h.text, color = w, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = if (h.level == 1) MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold) else MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clickable { go(h) }.padding(start = (16 + (h.level - 1) * 14).dp, end = 12.dp, top = 8.dp, bottom = 8.dp))
            }
        }
    }
}
