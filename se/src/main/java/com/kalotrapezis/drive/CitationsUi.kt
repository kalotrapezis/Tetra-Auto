package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * References › a source (Word's Create Source, APA 7's fields): its kind, then what that kind needs — authors (a
 * person's surname and given names; an organisation's name alone), year, title, the journal / web site / book it is
 * in, publisher, volume, issue, pages, edition, a chapter's book's editors, DOI or address.
 */
@Composable
internal fun SourceDialog(start: DocSource?, dismiss: () -> Unit, save: (DocSource) -> Unit) {
    var type by remember { mutableStateOf(start?.type ?: DocSource.Type.Book) }
    val authors = remember { mutableStateListOf<Pair<String, String>>().apply { addAll(start?.authors?.map { it.last to it.first }.orEmpty().ifEmpty { listOf("" to "") }) } }
    val editors = remember { mutableStateListOf<Pair<String, String>>().apply { addAll(start?.editors?.map { it.last to it.first }.orEmpty().ifEmpty { listOf("" to "") }) } }
    var year by remember { mutableStateOf(start?.year.orEmpty()) }
    var title by remember { mutableStateOf(start?.title.orEmpty()) }
    var container by remember { mutableStateOf(start?.container.orEmpty()) }
    var publisher by remember { mutableStateOf(start?.publisher.orEmpty()) }
    var volume by remember { mutableStateOf(start?.volume.orEmpty()) }
    var issue by remember { mutableStateOf(start?.issue.orEmpty()) }
    var pages by remember { mutableStateOf(start?.pages.orEmpty()) }
    var edition by remember { mutableStateOf(start?.edition.orEmpty()) }
    var doi by remember { mutableStateOf(start?.doi.orEmpty()) }
    @Composable fun field(label: String, value: String, set: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) =
        OutlinedTextField(value, set, singleLine = true, label = { Text(label) }, modifier = modifier)
    @Composable fun people(label: String, list: MutableList<Pair<String, String>>) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        list.forEachIndexed { i, (last, first) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                field(stringResource(R.string.words_surname), last, { list[i] = it to list[i].second }, Modifier.weight(1f))
                field(stringResource(R.string.words_given_names), first, { list[i] = list[i].first to it }, Modifier.weight(1f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(stringResource(R.string.words_plus_add), false) { list += "" to "" }
            if (list.size > 1) Choice(stringResource(R.string.words_minus_last), false) { list.removeAt(list.lastIndex) }
        }
    }
    AlertDialog(
        onDismissRequest = dismiss,
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false), // a form half filled is not lost to a stray tap
        title = { Text(stringResource(if (start == null) R.string.words_new_source else R.string.words_edit_source)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).shadedScroll(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DocSource.Type.entries.forEach { t -> Choice(stringResource(t.res), type == t) { type = t } }
                }
                people(stringResource(R.string.words_authors), authors)
                field(stringResource(R.string.words_year), year, { year = it })
                field(stringResource(if (type == DocSource.Type.Chapter) R.string.words_chapter_title else if (type == DocSource.Type.Web) R.string.words_page_title else R.string.words_title), title, { title = it })
                when (type) {
                    DocSource.Type.Article -> {
                        field(stringResource(R.string.words_journal), container, { container = it })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            field(stringResource(R.string.words_volume), volume, { volume = it }, Modifier.weight(1f)); field(stringResource(R.string.words_issue), issue, { issue = it }, Modifier.weight(1f)); field(stringResource(R.string.words_pages), pages, { pages = it }, Modifier.weight(1f))
                        }
                    }
                    DocSource.Type.Chapter -> {
                        field(stringResource(R.string.words_book_title), container, { container = it })
                        people(stringResource(R.string.words_book_editors), editors)
                        field(stringResource(R.string.words_pages), pages, { pages = it }); field(stringResource(R.string.words_publisher), publisher, { publisher = it })
                    }
                    DocSource.Type.Web -> field(stringResource(R.string.words_web_site), container, { container = it })
                    DocSource.Type.Report -> { field(stringResource(R.string.words_report_number), container, { container = it }); field(stringResource(R.string.words_publisher), publisher, { publisher = it }) }
                    DocSource.Type.Thesis -> { field(stringResource(R.string.words_thesis_kind), container, { container = it }); field(stringResource(R.string.words_university), publisher, { publisher = it }) }
                    DocSource.Type.Video -> field(stringResource(R.string.words_video_where), container, { container = it })
                    DocSource.Type.Book -> { field(stringResource(R.string.words_edition), edition, { edition = it }); field(stringResource(R.string.words_publisher), publisher, { publisher = it }) }
                }
                field(stringResource(R.string.words_doi_or_address), doi, { doi = it })
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank() || authors.any { it.first.isNotBlank() }, onClick = {
                fun list(l: List<Pair<String, String>>) = l.filter { it.first.isNotBlank() }.map { DocSource.Person(it.first.trim(), it.second.trim()) }
                save((start ?: DocSource("")).copy(type = type, authors = list(authors), year = year.trim(), title = title.trim(), container = container.trim(),
                    publisher = publisher.trim(), volume = volume.trim(), issue = issue.trim(), pages = pages.trim(), edition = edition.trim(), doi = doi.trim(),
                    editors = if (type == DocSource.Type.Chapter) list(editors) else emptyList()))
            }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/**
 * References › Insert citation: one or more of the document's sources (a new one added here), and a page (Word
 * files: APA's "p. 12"). [multiple]: several sources in one citation (Word's; an ODT's mark holds one).
 */
@Composable
internal fun CitationDialog(sources: List<DocSource>, pages: Boolean, multiple: Boolean, dismiss: () -> Unit, newSource: () -> Unit,
    startTags: List<String> = emptyList(), startPage: String = "", startHide: Set<Char> = emptySet(), insert: (List<DocSource>, String, Set<Char>) -> Unit) {
    val chosen = remember { mutableStateListOf<String>().apply { addAll(startTags) } }
    var page by remember { mutableStateOf(startPage) }
    var hide by remember { mutableStateOf(startHide) } // Word's Suppress: 'n' the author, 'y' the year
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(if (startTags.isEmpty()) R.string.words_insert_citation else R.string.words_change_citation)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).shadedScroll(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (sources.isEmpty()) Text(stringResource(R.string.words_no_sources), style = MaterialTheme.typography.bodyMedium)
                Apa.sorted(sources).forEach { s ->
                    fun pick(on: Boolean) { if (!on) chosen -= s.tag else { if (!multiple) chosen.clear(); chosen += s.tag } }
                    Row(Modifier.fillMaxWidth().clickable { pick(s.tag !in chosen) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(s.tag in chosen, { pick(it) })
                        Column(Modifier.weight(1f)) {
                            Text(Apa.parenthetical(listOf(s)), style = MaterialTheme.typography.bodyLarge)
                            Text(s.title, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                }
                Choice(stringResource(R.string.words_plus_new_source), false, newSource)
                if (pages && chosen.size == 1) OutlinedTextField(page, { page = it }, singleLine = true, label = { Text(stringResource(R.string.words_quote_pages)) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                // Word's Edit Citation › Suppress (the author named in the sentence already, say): Word files' citations only
                if (pages && chosen.isNotEmpty()) Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Choice(stringResource(R.string.words_no_author), 'n' in hide) { hide = if ('n' in hide) hide - 'n' else hide + 'n' }
                    Choice(stringResource(R.string.words_no_year), 'y' in hide) { hide = if ('y' in hide) hide - 'y' else hide + 'y' }
                }
            }
        },
        confirmButton = { TextButton(enabled = chosen.isNotEmpty(), onClick = { insert(chosen.mapNotNull { t -> sources.firstOrNull { it.tag == t } }, page.trim(), hide) }) { Text(stringResource(if (startTags.isEmpty()) R.string.words_insert else R.string.words_change)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** References › Sources: the document's sources — edit one, add one, take one away (a cited one says so). */
@Composable
internal fun SourcesDialog(sources: List<DocSource>, cited: Set<String>, dismiss: () -> Unit, edit: (DocSource?) -> Unit, import: () -> Unit = {},
    paste: () -> Unit = {}, askClaude: () -> Unit = {}, delete: (DocSource) -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.words_sources)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).shadedScroll(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (sources.isEmpty()) Text(stringResource(R.string.words_no_sources), style = MaterialTheme.typography.bodyMedium)
                Apa.sorted(sources).forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { edit(s) }) {
                            Text(Apa.entry(s).joinToString("") { it.text }, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                            if (s.tag in cited) Text(stringResource(R.string.words_cited), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        TextButton(onClick = { edit(s) }) { Text(stringResource(R.string.common_edit)) }
                        if (s.tag !in cited) TextButton(onClick = { delete(s) }) { Text(stringResource(R.string.common_delete)) }
                    }
                }
                Choice(stringResource(R.string.words_plus_new_source), false) { edit(null) }
                // A Tetra sources file (TETRA_SOURCES_FORMAT.md): the research's references, into this draft at once.
                Choice(stringResource(R.string.words_import_sources_file), false) { import() }
                // With Claude (the tetra-sources skill): ask in the Claude app, copy its answer, paste it here.
                Choice(stringResource(R.string.words_ask_claude_sources_more), false) { askClaude() }
                Choice(stringResource(R.string.words_paste_sources_claude), false) { paste() }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_done)) } },
    )
}


/** A citation, the cursor in it: each of its sources (a tap edits it — a missing one is made), and Change (its sources or page). */
@Composable
internal fun CitationBar(cite: String, sources: List<DocSource>, color: androidx.compose.ui.graphics.Color, edit: (DocSource?, String) -> Unit, change: () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.words_citation), color = color, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 4.dp))
        Citations.parse(cite).first.forEach { t ->
            val s = sources.firstOrNull { it.tag == t }
            TextButton(onClick = { edit(s, t) }) {
                Text(if (s != null) stringResource(R.string.words_edit_named, Apa.citeNames(s) + ", " + s.year.ifBlank { "n.d." }) else stringResource(R.string.words_new_source_tag, t), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        TextButton(onClick = change) { Text(stringResource(R.string.words_change_more), color = color, maxLines = 1) }
    }
}
