package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.UUID

// Notes (asked 2026-09-26): the same notes as the computer's, in Files' hidden .notes folder (Notes.kt). No folders,
// labels only; an island for Home / Pinned / Archived / Trash, pulled up for the pins and labels, and an editor with
// one line of tools whose full set slides up from the bottom.

private enum class NotesView(@androidx.annotation.StringRes val label: Int) { Home(R.string.app_home), Archived(R.string.notes_archived), Trash(R.string.notes_trash) }

private val NOTE_COLORS = NoteColors.NAMES.values.toList()
private fun noteColor(hex: String?): Color? = hex?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
private val InkOnColor = Color(0xFF1B1D1F)
// Sheets are solid: over a page of coloured notes a see-through one was hard to read (asked 2026-09-26).
internal val SheetColor = Color(0xFF16191A)

internal fun notesStore() = NotesStore(TetraSystem.dir(TetraFolder.root(Environment.getExternalStorageDirectory()), "Notes", "Shared"))

private fun inView(n: Note, v: NotesView) = when (v) {
    NotesView.Trash -> n.trashedAt != null
    NotesView.Archived -> n.trashedAt == null && n.archivedAt != null
    NotesView.Home -> n.trashedAt == null && n.archivedAt == null
}
private val accents = Regex("\\p{M}")
private fun fold(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(accents, "").lowercase()
private fun searchText(n: Note) = fold(listOf(n.title, n.content, n.labels.joinToString(" "), n.items.joinToString(" ") { it.text }).joinToString(" "))

/** `start`: true opens a new checklist, false a new note, null the list (the home card's three doors). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun NotesTab(back: () -> Unit, start: Boolean?, hasAccess: Boolean, grant: () -> Unit, openId: String? = null) {
    val store = remember { notesStore() }
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var notes by remember { mutableStateOf(store.lastList()) } // read ahead when Tetra started; cards only (NotesStore.summary)
    OpenTiming("Notes", notes != null)
    var view by remember { mutableStateOf(NotesView.Home) }
    var label by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var drawer by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<Note?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val remote by NotesSync.changed.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(version) { if (version > 0) LocationReminders.tidySoon(context) } // a list deleted, trashed, archived or ticked: its place reminder goes
    LaunchedEffect(version, remote, hasAccess) {
        if (!hasAccess) return@LaunchedEffect
        // opened before the read-ahead finished: the list as the index file has it (one file), then the folder's
        if (notes == null) withContext(Dispatchers.IO) { runCatching { store.indexed() }.getOrNull() }?.let { if (notes == null) notes = it }
        val got = withContext(Dispatchers.IO) { store.list() }
        notes = got
        if (withContext(Dispatchers.IO) { store.sweep(notes = got) } > 0) notes = withContext(Dispatchers.IO) { store.list() } // after the list shows
    }
    // search's index: each note's whole text without accents, read from the files while search is open and let go after
    // (thousands of notes stay out of memory); until it is ready, what the cards hold
    val index by produceState(emptyMap<String, String>(), searching, notes) {
        value = if (!searching) emptyMap() else withContext(Dispatchers.IO) { store.whole(notes.orEmpty().map { it.id }).associate { n -> n.id to searchText(n) } }
    }
    LaunchedEffect(version) { if (version > 0) NotesSync.soon(context) } // a change made here, in the list or an editor
    // Opening Notes is the moment to catch up — the whole sync may have been skipped (asked 2026-09-26).
    var refreshing by remember { mutableStateOf(false) }
    fun refresh() = scope.launch {
        refreshing = true
        val got = NotesSync.now(context) // what arrives reloads the list through NotesSync.changed
        refreshing = false
        got.exceptionOrNull()?.let { message = context.getString(R.string.notes_sync_failed, it.message ?: it.javaClass.simpleName) }
    }
    LaunchedEffect(hasAccess) { if (hasAccess) NotesSync.now(context) }
    fun create(checklist: Boolean) = scope.launch {
        open = withContext(Dispatchers.IO) { store.create(checklist, listOfNotNull(label)) }
    }
    LaunchedEffect(start) { if (hasAccess && start != null) create(start) }
    LaunchedEffect(openId) { if (hasAccess && openId != null) withContext(Dispatchers.IO) { store.get(openId) }?.let { open = it } }
    // Export as Markdown (the user, 27 Oct): every note, into a new folder at the top of Files, so nothing is written over
    fun exportAll() = scope.launch {
        message = withContext(Dispatchers.IO) { runCatching {
            val all = store.whole(store.list().map { it.id })
            val name = NoteExport.folder(java.time.LocalDateTime.now())
            val tetra = TetraFolder.root(Environment.getExternalStorageDirectory())
            val dir = generateSequence(1) { it + 1 }.map { File(tetra, if (it == 1) name else "$name ($it)") }.first { !it.exists() } // two in one minute
            NoteExport.files(all, context.getString(R.string.notes_untitled)).forEach { f -> File(dir, f.path).apply { parentFile?.mkdirs(); writeText(f.text); setLastModified(f.at) } }
            context.getString(R.string.notes_exported, all.size, dir.name)
        }.getOrElse { context.getString(R.string.settings_not_exported, it.message ?: it.javaClass.simpleName) } }
    }
    LaunchedEffect(message) { if (message != null) { delay(3000); message = null } }

    if (!hasAccess) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_back)) }
            Text(stringResource(R.string.notes_access_needed))
            TextButton(onClick = grant) { Text(stringResource(R.string.notes_allow_access), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        return
    }
    open?.let { note ->
        NoteEditor(store, note, onClose = { saved, said ->
            open = null; version++
            message = said
            // Nothing written: no note.
            if (said == null && saved != null && saved.empty) scope.launch(Dispatchers.IO) { store.remove(saved.id); withContext(Dispatchers.Main) { version++ } }
        })
        return
    }
    BackHandler { when { selected.isNotEmpty() -> selected = emptySet(); drawer -> drawer = false; searching -> { searching = false; query = "" }; label != null -> label = null; view != NotesView.Home -> view = NotesView.Home; else -> back() } }
    // A keyboard (the user, 25 Oct), the desktop's keys: Ctrl+N a note, Ctrl+Shift+N a checklist, Ctrl+F search, Esc a step back
    val keys = remember { FocusRequester() }
    LaunchedEffect(searching) { if (!searching) runCatching { keys.requestFocus() } } // the search field gone: the keys come here again
    fun key(e: KeyEvent): Boolean = e.type == KeyEventType.KeyDown && when {
        e.isCtrlPressed && e.key == Key.N -> { create(e.isShiftPressed); true }
        e.isCtrlPressed && e.key == Key.F -> { searching = true; true }
        e.key == Key.Escape -> { when { selected.isNotEmpty() -> selected = emptySet(); searching -> { searching = false; query = "" }; label != null -> label = null; view != NotesView.Home -> view = NotesView.Home; else -> back() }; true }
        else -> false
    }

    val all = notes.orEmpty()
    val q = fold(query.trim())
    // A search or a label looks through every note but the Trash, whichever page it starts from (asked 2026-09-28).
    val everywhere = q.isNotEmpty() || label != null
    val trash = view == NotesView.Trash && !everywhere
    val shown = remember(all, view, label, q, index) { all.filter { n -> (if (everywhere) n.trashedAt == null else inView(n, view)) && (label == null || label in n.labels) &&
        (q.isEmpty() || (index[n.id] ?: searchText(n)).contains(q)) }
        .sortedWith(if (trash) compareByDescending { it.trashedAt } else compareByDescending { it.updatedAt }) }
    // Pinned on top of Home, the rest below them (asked 2026-09-26).
    val pinned = remember(shown, view, everywhere) { if (view == NotesView.Home || everywhere) shown.filter { it.pinned } else emptyList() }
    val others = remember(shown, pinned) { shown - pinned.toSet() }
    val labels = remember(all) { all.filter { it.trashedAt == null }.flatMap { it.labels }.groupingBy { it }.eachCount().toSortedMap() }
    // A label no note carries any more is gone, so it cannot stay chosen either.
    LaunchedEffect(labels.keys, notes) { if (notes != null && label != null && label !in labels) label = null }
    val wide = LocalConfiguration.current.screenWidthDp >= 700
    val picked = all.filter { it.id in selected }
    // A page of its own: the search and the label, which look everywhere, give way to it.
    fun go(v: NotesView) { view = v; label = null; query = ""; searching = false; drawer = false }
    fun act(said: String, change: (org.json.JSONObject) -> Unit) = scope.launch(Dispatchers.IO) {
        picked.forEach { store.update(it.id, change) }
        withContext(Dispatchers.Main) { selected = emptySet(); version++; message = said }
    }
    val card: @Composable (Note) -> Unit = { n ->
        NoteCard(n, selected = n.id in selected, selecting = selected.isNotEmpty(), longPress = { selected = if (n.id in selected) selected - n.id else selected + n.id }) {
            if (selected.isEmpty()) scope.launch { withContext(Dispatchers.IO) { store.get(n.id) }?.let { open = it } } // the whole note: the list holds its card else selected = if (n.id in selected) selected - n.id else selected + n.id
        }
    }
    val header: @Composable (String) -> Unit = { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, top = 8.dp)) }

    androidx.compose.material3.pulltorefresh.PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refresh() }, modifier = Modifier.fillMaxSize().onPreviewKeyEvent(::key).focusRequester(keys).focusable()) {
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Adaptive(165.dp),
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 140.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalItemSpacing = 10.dp,
        ) {
            item(span = StaggeredGridItemSpan.FullLine) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_back)) }
                        Text(label ?: if (everywhere) stringResource(R.string.notes_all) else if (view == NotesView.Home) stringResource(R.string.app_notes) else stringResource(view.label), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (trash && shown.isNotEmpty()) TextButton(onClick = {
                            scope.launch(Dispatchers.IO) { shown.forEach { store.remove(it.id) }; withContext(Dispatchers.Main) { version++; message = context.getString(R.string.notes_trash_emptied) } }
                        }) { Text(stringResource(R.string.notes_empty_trash), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    if (searching) NoteField(query, { query = it }, stringResource(R.string.notes_search), Modifier.fillMaxWidth(), autofocus = true)
                    if (label != null) Chip("$label  ✕", on = true) { label = null }
                }
            }
            if (notes == null) item(span = StaggeredGridItemSpan.FullLine) { Text(stringResource(R.string.common_loading), Modifier.padding(24.dp)) }
            else if (shown.isEmpty()) item(span = StaggeredGridItemSpan.FullLine) {
                Text(when {
                    q.isNotEmpty() -> stringResource(R.string.notes_no_match)
                    trash -> stringResource(R.string.notes_trash_empty)
                    view == NotesView.Archived -> stringResource(R.string.notes_nothing_archived)
                    else -> stringResource(R.string.notes_none_yet)
                }, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (pinned.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine) { header(stringResource(R.string.notes_pinned_header)) }
                items(pinned, key = { it.id }) { card(it) }
                if (others.isNotEmpty()) item(span = StaggeredGridItemSpan.FullLine) { header(stringResource(R.string.notes_others)) }
            }
            items(others, key = { it.id }) { card(it) }
        }

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom,
        ) {
            if (selected.isNotEmpty()) Surface(shape = MaterialTheme.shapes.extraLarge, color = islandColor(), contentColor = islandContentColor()) {
                // What you picked: the navigation gives way to what can be done with it.
                Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selected = emptySet() }) { Icon(painterResource(R.drawable.ic_cancel), contentDescription = stringResource(R.string.notes_clear_selection)) }
                    Text("${selected.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 8.dp))
                    if (trash) {
                        IconButton(onClick = { act(context.getString(R.string.notes_did_restore)) { it.remove("trashedAt") } }) { Icon(painterResource(R.drawable.ic_restore), contentDescription = stringResource(R.string.notes_restore)) }
                        IconButton(onClick = { scope.launch(Dispatchers.IO) { picked.forEach { store.remove(it.id) }; withContext(Dispatchers.Main) { selected = emptySet(); version++; message = context.getString(R.string.notes_did_delete) } } }) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.notes_delete_forever), tint = Color(0xFFE35A4F)) }
                    } else {
                        val allPinned = picked.all { it.pinned }
                        IconButton(onClick = { act(context.getString(if (allPinned) R.string.notes_did_unpin else R.string.notes_did_pin)) { it.put("isPinned", !allPinned) } }) { Icon(painterResource(R.drawable.ic_push_pin), contentDescription = stringResource(if (allPinned) R.string.notes_unpin else R.string.notes_pin)) }
                        if (picked.all { it.archivedAt != null }) IconButton(onClick = { act(context.getString(R.string.notes_did_unarchive)) { it.remove("archivedAt") } }) { Icon(painterResource(R.drawable.ic_unarchive), contentDescription = stringResource(R.string.notes_unarchive)) }
                        else IconButton(onClick = { act(context.getString(R.string.notes_did_archive)) { it.put("archivedAt", System.currentTimeMillis()) } }) { Icon(painterResource(R.drawable.ic_archive), contentDescription = stringResource(R.string.notes_archive)) }
                        IconButton(onClick = { act(context.getString(R.string.notes_did_trash)) { it.put("trashedAt", System.currentTimeMillis()) } }) { Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.notes_move_to_trash)) }
                    }
                }
            } else IslandBottomBar(expand = { drawer = true }, visible = !drawer) {
                // Home, then the two ways to start; a tablet has room for Archive and Trash too, a phone keeps them in the drawer.
                IslandNavigationItem(stringResource(R.string.app_home), view == NotesView.Home, R.drawable.ic_home) { go(NotesView.Home) }
                IslandNavigationItem(stringResource(R.string.notes_new_note), false, R.drawable.ic_note_add) { create(false) }
                IslandNavigationItem(stringResource(R.string.notes_checklist), false, R.drawable.ic_checklist) { create(true) }
                if (wide) {
                    IslandNavigationItem(stringResource(R.string.notes_archived), view == NotesView.Archived, R.drawable.ic_archive) { go(NotesView.Archived) }
                    IslandNavigationItem(stringResource(R.string.notes_trash), view == NotesView.Trash, R.drawable.ic_delete) { go(NotesView.Trash) }
                }
            }
            // Search where it is everywhere else in the app: bottom right.
            if (selected.isEmpty() && !drawer) RoundIsland(R.drawable.ic_search, stringResource(R.string.notes_search)) { searching = !searching; if (!searching) query = "" }
        }
        message?.let { Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp)) { Text(it, Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) } }
    }

    if (drawer) ModalBottomSheet(onDismissRequest = { drawer = false }, containerColor = SheetColor, contentColor = islandContentColor()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!wide) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DrawerButton(R.drawable.ic_archive, stringResource(R.string.notes_archived), view == NotesView.Archived, Modifier.weight(1f)) { go(NotesView.Archived) }
                DrawerButton(R.drawable.ic_delete, stringResource(R.string.notes_trash), view == NotesView.Trash, Modifier.weight(1f)) { go(NotesView.Trash) }
            }
            // The labels, one under another in their own panel (the user's sketch, 2026-09-26).
            Surface(color = Color.White.copy(alpha = 0.08f), shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                    LabelRow(stringResource(R.string.notes_all), null, label == null) { label = null; drawer = false }
                    labels.forEach { (l, count) -> LabelRow(l, count, label == l) { label = l; drawer = false } }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DriveWideAction(R.drawable.ic_file_text, stringResource(R.string.notes_export_markdown)) { drawer = false; exportAll() }
                Text(stringResource(R.string.notes_export_hint), style = MaterialTheme.typography.bodySmall, color = islandContentColor().copy(alpha = 0.7f), modifier = Modifier.padding(horizontal = 22.dp))
            }
        }
    }
}

@Composable private fun LabelRow(name: String, count: Int?, on: Boolean, click: () -> Unit) = Row(
    Modifier.fillMaxWidth().clickable(onClick = click).background(if (on) Color.White.copy(alpha = 0.14f) else Color.Transparent).padding(horizontal = 18.dp, vertical = 12.dp),
    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
) {
    Icon(painterResource(R.drawable.ic_label), contentDescription = null, modifier = Modifier.size(20.dp))
    Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (count != null) Text("$count", style = MaterialTheme.typography.labelMedium, color = islandContentColor().copy(alpha = 0.6f))
}

@Composable internal fun DrawerButton(icon: Int, label: String, on: Boolean, modifier: Modifier, click: () -> Unit) = Surface(
    color = if (on) driveNavigationSelectedColor() else Color.White.copy(alpha = 0.10f),
    contentColor = if (on) driveNavigationSelectedContentColor() else islandContentColor(),
    shape = MaterialTheme.shapes.large, modifier = modifier.clickable(onClick = click),
) {
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null); Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun RoundIsland(icon: Int, description: String, click: () -> Unit) =
    Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
        IconButton(onClick = click) { Icon(painterResource(icon), contentDescription = description) }
    }

@Composable private fun Chip(text: String, on: Boolean, click: () -> Unit) = Surface(
    color = if (on) driveNavigationSelectedColor() else Color.White.copy(alpha = 0.10f),
    contentColor = if (on) driveNavigationSelectedContentColor() else islandContentColor(),
    shape = CircleShape, modifier = Modifier.clickable(onClick = click),
) { Text(text, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) }

@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun NoteCard(n: Note, small: Boolean = false, selected: Boolean = false, selecting: Boolean = false, longPress: (() -> Unit)? = null, open: () -> Unit) {
    val tint = noteColor(n.color)
    Surface(
        color = tint ?: MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        contentColor = if (tint != null) InkOnColor else MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
            .then(if (selected) Modifier.border(3.dp, Color.White, MaterialTheme.shapes.large) else Modifier)
            .combinedClickable(onClick = open, onLongClick = longPress),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                // A check while choosing, as on photos.
                if (selecting) Box(
                    Modifier.padding(end = 8.dp).size(22.dp).then(if (selected) Modifier.background(Color.White, CircleShape) else Modifier.border(2.dp, LocalContentColor.current.copy(alpha = 0.6f), CircleShape)),
                    contentAlignment = Alignment.Center,
                ) { if (selected) Text("✓", color = Color.Black, style = MaterialTheme.typography.labelMedium) }
                if (n.title.isNotBlank()) Text(n.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                else Spacer(Modifier.weight(1f))
                if (n.pinned && !small) Icon(painterResource(R.drawable.ic_push_pin), contentDescription = stringResource(R.string.notes_card_pinned), modifier = Modifier.size(16.dp))
            }
            remember(n, small) { NoteFormat.preview(n, if (small) 3 else 8) }.forEach { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            if (!small && n.labels.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                n.labels.forEach { Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.background(Color(0x33888888), CircleShape).padding(horizontal = 8.dp, vertical = 2.dp)) }
            }
        }
    }
}

@Composable private fun NoteField(value: String, change: (String) -> Unit, hint: String, modifier: Modifier = Modifier, autofocus: Boolean = false) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (autofocus) focus.requestFocus() }
    Surface(color = Color.White.copy(alpha = 0.08f), shape = CircleShape, modifier = modifier) {
        Box(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            if (value.isEmpty()) Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            BasicTextField(value, change, singleLine = true, textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface), modifier = Modifier.fillMaxWidth().focusRequester(focus))
        }
    }
}

private data class Snap(val title: String, val body: String, val items: List<CheckItem>)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NoteEditor(store: NotesStore, initial: Note, onClose: (Note?, String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(initial.title) }
    var body by remember { mutableStateOf(TextFieldValue(initial.content)) }
    var items by remember { mutableStateOf(initial.items) }
    var labels by remember { mutableStateOf(initial.labels) }
    var color by remember { mutableStateOf(initial.color) }
    var pinned by remember { mutableStateOf(initial.pinned) }
    var archived by remember { mutableStateOf(initial.archivedAt != null) }
    var reading by remember { mutableStateOf(false) }
    var tools by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf<List<NoteVersion>?>(null) }
    var tagsOpen by remember { mutableStateOf(false) }
    var formatting by remember { mutableStateOf(false) }
    val bodyFocus = remember { FocusRequester() }
    val imeOpen = WindowInsets.isImeVisible
    var toBody by remember { mutableIntStateOf(0) } // a checklist's first item takes the focus when this counts up
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    var changed by remember { mutableStateOf(false) }
    var snapped by remember { mutableStateOf(false) }
    var undoTick by remember { mutableIntStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val undo = remember { NoteUndo(Snap(title, body.text, items)) }
    val trashed = initial.trashedAt != null
    val tint = noteColor(color)
    val ink = if (tint != null) InkOnColor else MaterialTheme.colorScheme.onSurface
    val noteLabels: @Composable (Modifier) -> Unit = { modifier ->
        // The tags, and a + that opens them (asked 2026-09-27: on a phone the island had no room left for Pin).
        if (!trashed) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
            // Solid, so text scrolled under them never shows through (asked 2026-09-27).
            labels.forEach { Text(it, color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clip(CircleShape).background(Color.Black).clickable { tagsOpen = true }.padding(horizontal = 10.dp, vertical = 5.dp)) }
            Row(Modifier.clip(CircleShape).background(Color.Black).clickable(onClickLabel = stringResource(R.string.notes_labels)) { tagsOpen = true }.padding(start = 10.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.notes_label) + " ", color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.notes_labels), tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }

    // Saved when typing stops for half a second, the whole text each time; synced only on leaving, since a sync
    // while typing made the keyboard lag (asked 2026-11-02).
    LaunchedEffect(title, body.text, items) {
        if (!changed) return@LaunchedEffect
        delay(500)
        withContext(Dispatchers.IO) {
            // The note as it was when opened goes to its history before the first change is written over it.
            if (!snapped) { store.snapshot(initial.id); snapped = true }
            store.setText(initial.id, title, body.text, if (initial.checklist) items else null)
        }
    }
    fun edit(t: String = title, b: TextFieldValue = body, i: List<CheckItem> = items, step: Boolean = false) {
        val snap = Snap(t, b.text, i)
        val done = NoteUndo.endsWord(title, t, t.length) || NoteUndo.endsWord(body.text, b.text, b.selection.start)
        if (step) undo.step(snap) else if (snap != Snap(title, body.text, items)) undo.push(snap, wordDone = done)
        title = t; body = b; items = i; changed = true; undoTick++
    }
    fun apply(s: Snap?) { if (s == null) return; title = s.title; body = TextFieldValue(s.body, TextRange(s.body.length)); items = s.items; changed = true; undoTick++ }
    fun meta(change: (org.json.JSONObject) -> Unit) { changed = true; scope.launch(Dispatchers.IO) { store.update(initial.id, change); NotesSync.soon(context) } }
    // Leaving: what is pending is saved, and the note as it now is goes to its history, if anything changed.
    fun leave(said: String? = null, last: ((org.json.JSONObject) -> Unit)? = null) = scope.launch {
        val saved = withContext(Dispatchers.IO) {
            if (changed) { if (!snapped) { store.snapshot(initial.id); snapped = true }; store.setText(initial.id, title, body.text, if (initial.checklist) items else null) }
            last?.let { store.update(initial.id, it) }
            if (changed) store.snapshot(initial.id)
            store.get(initial.id)
        }
        NotesSync.soon(context, 500)
        onClose(saved, said)
    }
    // Export as Markdown: this note as it is on screen, one .md saved where the person picks
    var exporting by remember { mutableStateOf<NoteExport.Out?>(null) }
    val exportOne = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val out = exporting ?: return@rememberLauncherForActivityResult
        exporting = null
        if (uri != null) scope.launch {
            val said = withContext(Dispatchers.IO) { runCatching {
                context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(out.text.toByteArray()) }
                OfficeOpen.localPath(uri, Environment.getExternalStorageDirectory())?.setLastModified(out.at) // a cloud app's file keeps its own time
                context.getString(R.string.notes_note_exported)
            }.getOrElse { context.getString(R.string.settings_not_exported, it.message ?: it.javaClass.simpleName) } }
            android.widget.Toast.makeText(context, said, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    fun export() {
        val now = initial.copy(title = title, content = body.text, items = items, labels = labels, updatedAt = if (changed) System.currentTimeMillis() else initial.updatedAt)
        val (name, text) = NoteExport.markdown(now, context.getString(R.string.notes_untitled))
        exporting = NoteExport.Out("$name.md", text, now.updatedAt)
        exportOne.launch("$name.md")
    }
    fun archive() { if (archived) { archived = false; meta { it.remove("archivedAt") } } else leave(context.getString(R.string.notes_note_archived)) { it.put("archivedAt", System.currentTimeMillis()) } }
    fun toTrash() { leave(context.getString(R.string.notes_note_trashed)) { it.put("trashedAt", System.currentTimeMillis()) } }
    fun format(f: (NoteFormat.Edit) -> NoteFormat.Edit) {
        val r = f(NoteFormat.Edit(body.text, body.selection.min, body.selection.max))
        edit(b = TextFieldValue(r.text, TextRange(r.start, r.end)), step = true)
    }
    BackHandler { if (tools) tools = false else leave() }
    // the desktop's keys: Esc leaves, Ctrl+Z / Ctrl+Y (Ctrl+Shift+Z) undo and redo, Ctrl+B / Ctrl+I bold and italic
    val keys = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { keys.requestFocus() } }
    fun key(e: KeyEvent): Boolean = e.type == KeyEventType.KeyDown && when {
        e.key == Key.Escape -> { if (tools) tools = false else leave(); true }
        trashed || !e.isCtrlPressed -> false
        e.key == Key.Z && !e.isShiftPressed -> { apply(undo.undo()); true }
        e.key == Key.Y || e.key == Key.Z -> { apply(undo.redo()); true }
        initial.checklist || reading -> false
        e.key == Key.B -> { format { NoteFormat.wrap(it, "**") }; true }
        e.key == Key.I -> { format { NoteFormat.wrap(it, "*") }; true }
        else -> false
    }

    Box(Modifier.fillMaxSize().background(tint ?: MaterialTheme.colorScheme.background).statusBarsPadding().imePadding().onPreviewKeyEvent(::key).focusRequester(keys).focusable()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = if (imeOpen) 64.dp else if (!trashed) 170.dp else 120.dp)) {
            // Enter in the title goes on to the body (asked 2026-09-26): the text, or a checklist's first item.
            BasicTextField(title, { t ->
                if ('\n' in t) { if (initial.checklist) toBody++ else bodyFocus.requestFocus() } else edit(t = t)
            }, readOnly = trashed, singleLine = false, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { if (initial.checklist) toBody++ else bodyFocus.requestFocus() }),
                textStyle = TextStyle(color = ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold), cursorBrush = SolidColor(ink),
                decorationBox = { inner -> Box { if (title.isEmpty()) Text(stringResource(R.string.notes_title_hint), color = ink.copy(alpha = 0.45f), fontSize = 24.sp); inner() } },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            when {
                initial.checklist -> ChecklistEditor(items, ink, trashed, toBody) { next, step -> edit(i = next, step = step) }
                reading -> Text(renderMarkdown(body.text), color = ink, style = TextStyle(fontSize = 16.sp, lineHeight = 24.sp), modifier = Modifier.padding(bottom = 80.dp))
                else -> BasicTextField(body, { edit(b = it) }, readOnly = trashed,
                    textStyle = TextStyle(color = ink, fontSize = 16.sp, lineHeight = 24.sp), cursorBrush = SolidColor(ink),
                    visualTransformation = markdownVisualTransformation(),
                    decorationBox = { inner -> Box { if (body.text.isEmpty()) Text(stringResource(R.string.notes_body_hint), color = ink.copy(alpha = 0.45f)); inner() } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp).padding(bottom = 80.dp).focusRequester(bodyFocus))
            }
        }
        // The note's tags float at the bottom, over the island, when the keyboard is down; while typing they are out of the way.
        if (!imeOpen) noteLabels(Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 96.dp))
        // The tools are an island at the bottom, like the rest of Tetra: one line of the basics, pulled up (or its grip
        // tapped) for everything else (asked 2026-09-26: not a bar at the top with the rest at the bottom).
        val w = islandContentColor()
        // With the keyboard up the island would take half of what is left: like Google Keep, it becomes one thin strip
        // on the keyboard — the tools for writing, and the rest one tap away (asked 2026-09-26).
        // Two islands in one: the note's own controls, or the formatting tools, swapped by a button (asked 2026-09-26).
        // With the keyboard up it is one thin strip on the keyboard, as in Google Keep; otherwise a floating island,
        // out of the way while a sheet or dialog is up.
        val canFormat = !initial.checklist && !reading && !trashed
        val row: @Composable () -> Unit = {
            undoTick // read, so undo and redo follow the history, which is not state itself
            if (formatting && canFormat) {
                Tool(R.drawable.ic_title, stringResource(R.string.notes_heading), w) { format { NoteFormat.prefix(it, "# ") } }
                Tool(R.drawable.ic_format_bold, stringResource(R.string.notes_bold), w) { format { NoteFormat.wrap(it, "**") } }
                Tool(R.drawable.ic_format_italic, stringResource(R.string.notes_italic), w) { format { NoteFormat.wrap(it, "*") } }
                Tool(R.drawable.ic_format_strikethrough, stringResource(R.string.notes_strikethrough), w) { format { NoteFormat.wrap(it, "~~") } }
                Tool(R.drawable.ic_format_list_bulleted, stringResource(R.string.notes_bulleted_list), w) { format { NoteFormat.prefix(it, "- ") } }
                Tool(R.drawable.ic_format_list_numbered, stringResource(R.string.notes_numbered_list), w) { format { NoteFormat.prefix(it, "1. ") } }
                Tool(R.drawable.ic_check_box, stringResource(R.string.notes_checkbox_list), w) { format { NoteFormat.prefix(it, "- [ ] ") } }
                Tool(R.drawable.ic_format_quote, stringResource(R.string.notes_quote), w) { format { NoteFormat.prefix(it, "> ") } }
                Tool(R.drawable.ic_code, stringResource(R.string.notes_code), w) { format { NoteFormat.wrap(it, "`") } }
                val linkWord = stringResource(R.string.notes_link_placeholder)
                Tool(R.drawable.ic_link, stringResource(R.string.notes_link), w) { format { e -> val t = e.text.substring(e.start, e.end).ifEmpty { linkWord }; val md = "[$t](https://)"; NoteFormat.Edit(e.text.substring(0, e.start) + md + e.text.substring(e.end), e.start + t.length + 3, e.start + md.length - 1) } }
                Tool(R.drawable.ic_horizontal_rule, stringResource(R.string.notes_divider), w) { format { e -> NoteFormat.Edit(e.text.substring(0, e.end) + "\n\n---\n" + e.text.substring(e.end), e.end + 6, e.end + 6) } }
                Tool(R.drawable.ic_format_indent_increase, stringResource(R.string.notes_indent), w) { format { NoteFormat.indent(it, false) } }
                Tool(R.drawable.ic_format_indent_decrease, stringResource(R.string.notes_outdent), w) { format { NoteFormat.indent(it, true) } }
            } else if (!trashed) {
                Tool(R.drawable.ic_undo, stringResource(R.string.common_undo), w, enabled = undo.canUndo) { apply(undo.undo()) }
                Tool(R.drawable.ic_redo, stringResource(R.string.common_redo), w, enabled = undo.canRedo) { apply(undo.redo()) }
                if (wide) Tool(R.drawable.ic_label, stringResource(R.string.notes_labels), w, on = labels.isNotEmpty()) { tagsOpen = true } // a phone opens them from the + by the tags
                Tool(R.drawable.ic_history, stringResource(R.string.notes_history), w) { scope.launch { history = withContext(Dispatchers.IO) { store.history(initial.id) } } }
                if (!initial.checklist) Tool(if (reading) R.drawable.ic_edit_note else R.drawable.ic_visibility, stringResource(if (reading) R.string.common_edit else R.string.notes_read), w, on = reading) { reading = !reading }
                Tool(R.drawable.ic_push_pin, stringResource(if (pinned) R.string.notes_unpin else R.string.notes_pin), w, on = pinned) { pinned = !pinned; meta { it.put("isPinned", pinned) } }
                if (wide) {
                    Tool(if (archived) R.drawable.ic_unarchive else R.drawable.ic_archive, stringResource(if (archived) R.string.notes_unarchive else R.string.notes_archive), w) { archive() }
                    Tool(R.drawable.ic_delete, stringResource(R.string.notes_move_to_trash), w) { toTrash() }
                }
            }
        }
        val swap: @Composable () -> Unit = { if (canFormat) Tool(R.drawable.ic_swap_horiz, stringResource(if (formatting) R.string.notes_note_controls else R.string.notes_formatting_tools), w, on = formatting) { formatting = !formatting } }
        val screen = LocalConfiguration.current.screenWidthDp.dp
        if (imeOpen && !trashed) Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(islandColor()).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            swap()
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) { row() }
            Tool(R.drawable.ic_keyboard_arrow_up, stringResource(R.string.notes_more_tools), w) { tools = true }
        } else Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp)) {
            IslandBottomBar(expand = { if (!trashed) tools = true }, visible = !tools && !tagsOpen && history == null) {
                Tool(R.drawable.ic_chevron_left, stringResource(R.string.common_back), w) { leave() }
                if (trashed) {
                    TextButton(onClick = { leave(context.getString(R.string.notes_note_restored)) { it.remove("trashedAt") } }) { Text(stringResource(R.string.notes_restore), color = w, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    TextButton(onClick = { scope.launch(Dispatchers.IO) { store.remove(initial.id); withContext(Dispatchers.Main) { onClose(null, context.getString(R.string.notes_note_deleted)) } } }) { Text(stringResource(R.string.common_delete), color = Color(0xFFE35A4F), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                } else {
                    swap()
                    // The note's controls fit as they are; the longer formatting row scrolls sideways.
                    if (formatting && canFormat) Row(Modifier.widthIn(max = screen - 150.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) { row() }
                    else row()
                }
            }
        }
    }

    if (tools) ModalBottomSheet(onDismissRequest = { tools = false }, containerColor = SheetColor, contentColor = islandContentColor()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // The formatting tools are on the island now (its swap button); the sheet keeps colour, and Archive and Trash on a phone.
            Text(stringResource(R.string.notes_colour), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                (listOf<String?>(null) + NOTE_COLORS).forEach { hex ->
                    Box(Modifier.size(44.dp).background(noteColor(hex) ?: Color(0xFF3A3F43), CircleShape)
                        .then(if (color == hex) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
                        .clickable { color = hex; meta { if (hex == null) it.remove("color") else it.put("color", hex) } })
                }
            }
            // A tablet has Archive and Trash on the island; a phone has them here.
            if (!wide) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { DriveWideAction(if (archived) R.drawable.ic_unarchive else R.drawable.ic_archive, stringResource(if (archived) R.string.notes_unarchive else R.string.notes_archive)) { tools = false; archive() } }
                Box(Modifier.weight(1f)) { DriveWideAction(R.drawable.ic_delete, stringResource(R.string.notes_trash)) { tools = false; toTrash() } }
            }
            DriveWideAction(R.drawable.ic_file_text, stringResource(R.string.notes_export_markdown)) { tools = false; export() }
        }
    }

    if (tagsOpen) NoteTagsSheet(store, labels, dismiss = { tagsOpen = false }) { chosen ->
        labels = chosen; meta { it.put("labels", org.json.JSONArray(chosen)) }
    }

    history?.let { versions ->
        AlertDialog(
            onDismissRequest = { history = null },
            title = { Text(stringResource(R.string.notes_history)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.notes_history_note), style = MaterialTheme.typography.bodySmall)
                    if (versions.isEmpty()) Text(stringResource(R.string.notes_no_versions))
                    versions.forEach { v ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(v.at), style = MaterialTheme.typography.titleSmall)
                                Text(v.title.ifBlank { stringResource(R.string.notes_untitled) } + " · " + (v.content.lines().firstOrNull { it.isNotBlank() } ?: v.items.firstOrNull()?.text.orEmpty()),
                                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    val n = withContext(Dispatchers.IO) { store.restoreVersion(initial.id, v.name) }
                                    edit(t = n.title, b = TextFieldValue(n.content), i = n.items, step = true)
                                    history = null; tools = false
                                }
                            }) { Text(stringResource(R.string.notes_restore), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { history = null }) { Text(stringResource(R.string.common_close), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        )
    }
}

// A plain 40 dp round button: an IconButton is always at least 48 dp, and eight of them did not fit a phone's island.
// In an Office ribbon of Normal size it is 48 dp ([LocalToolSize]), its icon the same.
@Composable internal fun Tool(icon: Int, description: String, tint: Color, enabled: Boolean = true, on: Boolean = false, click: () -> Unit) = Box(
    Modifier.size(LocalToolSize.current).clip(CircleShape).then(if (on) Modifier.background(tint.copy(alpha = 0.15f)) else Modifier)
        .focusProperties { canFocus = false } // tapped, it leaves the focus (and the keyboard) where the typing is
        .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClickLabel = description, onClick = click),
    contentAlignment = Alignment.Center,
) { Icon(painterResource(icon), contentDescription = description, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(21.dp)) }

/** Open items in their order, then the done ones under a line; Next on the keyboard makes the next item. */
@Composable private fun ChecklistEditor(items: List<CheckItem>, ink: Color, readOnly: Boolean, focusFirst: Int, change: (List<CheckItem>, Boolean) -> Unit) {
    val sorted = items.sortedForList()
    val open = sorted.filter { !it.checked }
    val done = sorted.filter { it.checked }
    var focus by remember { mutableStateOf<String?>(null) }
    fun insertAfter(id: String?) {
        val at = if (id == null) open.size else open.indexOfFirst { it.id == id } + 1
        val added = CheckItem(UUID.randomUUID().toString(), "", false, at)
        val list = (open.take(at) + added + open.drop(at)).mapIndexed { n, i -> i.copy(order = n) }
        change(list + done, true); focus = added.id
    }
    LaunchedEffect(focusFirst) { if (focusFirst > 0) { if (open.isEmpty()) insertAfter(null) else focus = open.first().id } }
    @Composable fun row(i: CheckItem) {
        val requester = remember(i.id) { FocusRequester() }
        LaunchedEffect(focus) { if (focus == i.id) { requester.requestFocus(); focus = null } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { change(items.map { if (it.id == i.id) it.copy(checked = !it.checked, order = if (it.checked) open.size else it.order) else it }, true) }, enabled = !readOnly) {
                Icon(painterResource(if (i.checked) R.drawable.ic_check_box else R.drawable.ic_check_box_outline_blank), contentDescription = stringResource(if (i.checked) R.string.notes_item_done else R.string.notes_item_not_done), tint = ink)
            }
            BasicTextField(i.text, { t -> change(items.map { if (it.id == i.id) it.copy(text = t) else it }, false) }, readOnly = readOnly, singleLine = true,
                textStyle = TextStyle(color = ink.copy(alpha = if (i.checked) 0.55f else 1f), fontSize = 16.sp, textDecoration = if (i.checked) TextDecoration.LineThrough else null),
                cursorBrush = SolidColor(ink), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { insertAfter(i.id) }),
                modifier = Modifier.weight(1f).focusRequester(requester).padding(vertical = 10.dp))
            if (!readOnly) IconButton(onClick = { change(items.filter { it.id != i.id }, true) }) { Icon(painterResource(R.drawable.ic_cancel), contentDescription = stringResource(R.string.notes_remove_item), tint = ink.copy(alpha = 0.6f), modifier = Modifier.size(20.dp)) }
        }
    }
    Column {
        open.forEach { row(it) }
        if (!readOnly) TextButton(onClick = { insertAfter(null) }) { Icon(painterResource(R.drawable.ic_add), null, tint = ink); Text("  " + stringResource(R.string.notes_add_item), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (done.isNotEmpty()) {
            Text(pluralStringResource(R.plurals.notes_done_count, done.size, done.size), color = ink.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            done.forEach { row(it) }
        }
        Spacer(Modifier.heightIn(min = 80.dp))
    }
}

/**
 * Tags for a note (asked 2026-09-26): only the tags, to browse and tap — a tap puts one on or takes it off, saved at
 * once. "Add label" leads the list and opens its own small window, so the keyboard comes up only to type a new one.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable private fun NoteTagsSheet(store: NotesStore, initial: List<String>, dismiss: () -> Unit, change: (List<String>) -> Unit) {
    var selected by remember { mutableStateOf(initial) }
    var adding by remember { mutableStateOf(false) }
    // Tags are the labels (asked 2026-09-28): one that no note outside the Trash carries is gone the next time this opens.
    val known = remember { store.list().filter { it.trashedAt == null }.flatMap { it.labels } }
    val all = (known + selected).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    fun set(next: List<String>) { selected = next; change(next) }
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = SheetColor, contentColor = islandContentColor()) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.notes_labels), style = MaterialTheme.typography.titleLarge)
            FlowRow(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("+  " + stringResource(R.string.notes_add_label), on = false) { adding = true }
                all.forEach { tag -> Chip(tag, on = tag in selected) { set(if (tag in selected) selected - tag else selected + tag) } }
            }
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        fun add() {
            val t = name.trim().removePrefix("#").trim().take(60)
            if (t.isEmpty()) return
            val tag = all.firstOrNull { it.equals(t, ignoreCase = true) } ?: t
            if (tag !in selected) set(selected + tag)
            adding = false
        }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.notes_add_label)) },
            text = {
                androidx.compose.material3.OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth().focusRequester(focus), placeholder = { Text(stringResource(R.string.common_name)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }))
            },
            confirmButton = { TextButton(onClick = { add() }, enabled = name.isNotBlank()) { Text(stringResource(R.string.common_add), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        )
    }
}
