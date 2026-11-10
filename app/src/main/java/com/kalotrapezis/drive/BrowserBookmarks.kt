package com.kalotrapezis.drive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * The bookmarks manager (the user, 3 Nov: "proper bookmarks manager (Firefox-like, in a modal)"): the folders as a tree,
 * opened one inside another (a tablet also has the whole tree at the side), search over all; a tap opens a page, a long
 * press chooses (then a tap chooses more) and the bar at the bottom acts on what is chosen: edit, move, pin, delete.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun BookmarksManager(library: BookmarkLibrary, wide: Boolean, at: MutableState<String>, keep: (BookmarkLibrary) -> Unit, close: () -> Unit,
    others: List<DeviceTabs> = emptyList(), openNew: (String) -> Unit = {}, open: (String) -> Unit) =
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    var folder by at
    var query by remember { mutableStateOf("") }
    var urls by remember { mutableStateOf(setOf<String>()) } // chosen pages
    var dirs by remember { mutableStateOf(setOf<String>()) } // chosen folders
    var ask by remember { mutableStateOf<String?>(null) } // "new", "edit", "rename", "move", "delete"
    var selecting by remember { mutableStateOf(false) } // Select tapped, nothing chosen yet
    // Tabs from other devices (as Firefox's): a place at the top of the bookmarks, each device by its name, read only
    var devices by remember { mutableStateOf(false) }
    if (others.isEmpty()) devices = false
    val choosing = selecting || urls.isNotEmpty() || dirs.isNotEmpty()
    val n = urls.size + dirs.size
    fun clear() { urls = emptySet(); dirs = emptySet(); selecting = false }
    fun go(f: String) { folder = f; query = ""; clear() }
    if (folder.isNotEmpty() && folder !in library.folders) folder = "" // renamed or deleted from under us
    BackHandler { when { devices -> devices = false; choosing -> clear(); query.isNotEmpty() -> query = ""; folder.isNotEmpty() -> go(BookmarkLibrary.parentOf(folder)); else -> close() } }

    val (marks, subs) = if (query.isBlank()) library.marks.filter { it.folder == folder } to library.subfolders(folder) else library.find(query)
    Surface((if (wide) Modifier.widthIn(max = 900.dp).fillMaxWidth(0.92f).fillMaxHeight(0.88f) else Modifier.fillMaxSize()).growFromSide(),
        shape = if (wide) MaterialTheme.shapes.extraLarge else RoundedCornerShapeNone, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.then(if (wide) Modifier else Modifier.systemBarsPadding()).imePadding()) {
            // the top: where we are and the search (viewing); the actions are at the bottom
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (devices) IconButton(onClick = { devices = false }) { Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.common_back)) }
                else if (folder.isNotEmpty() && query.isBlank()) IconButton(onClick = { go(BookmarkLibrary.parentOf(folder)) }) { Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.common_back)) }
                else Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (devices) stringResource(R.string.browser_other_devices) else if (folder.isEmpty() || query.isNotBlank()) stringResource(R.string.browser_bookmarks) else BookmarkLibrary.nameOf(folder),
                        style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (query.isBlank() && '/' in folder) Text(BookmarkLibrary.parentOf(folder).replace("/", " › "), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = close) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.common_close)) }
            }
            if (!devices) OutlinedTextField(query, { query = it; clear() }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), singleLine = true,
                placeholder = { Text(stringResource(R.string.browser_search_bookmarks)) }, shape = CircleShape,
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.common_clear)) } })
            Row(Modifier.weight(1f)) {
                if (wide) { // the whole tree beside the list, as Firefox's Library
                    LazyColumn(Modifier.width(260.dp).fillMaxHeight(), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(listOf("") + library.tree(), key = { "t/$it" }) { f ->
                            val here = f == folder && query.isBlank()
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(MaterialTheme.shapes.medium).background(if (here) BROWSER_COLOR.copy(alpha = 0.15f) else Color.Transparent)
                                .combinedClickable(onClick = { go(f) }).padding(start = (8 + 16 * (if (f.isEmpty()) 0 else f.count { it == '/' } + 1)).dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(if (f.isEmpty()) R.drawable.ic_bookmark else R.drawable.ic_folder), null, Modifier.size(18.dp), tint = BROWSER_COLOR)
                                Text(if (f.isEmpty()) stringResource(R.string.browser_bookmarks) else BookmarkLibrary.nameOf(f), Modifier.padding(start = 10.dp),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    VerticalDivider()
                }
                if (devices) LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(vertical = 4.dp)) {
                    // each device, its tabs under it; a tap opens the page in a new tab here
                    others.forEach { d ->
                        item(key = "d/${d.id}") {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(R.drawable.ic_devices), null, Modifier.size(18.dp), tint = BROWSER_COLOR)
                                Text(d.name, Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.titleSmall, color = BROWSER_COLOR, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${d.tabs.size}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        items(d.tabs, key = { "d/${d.id}/${it.url}/${it.title}" }) { t ->
                            val host = BrowserRules.site(BrowserRules.host(t.url))
                            ManagerRow(false, false, {}, { openNew(t.url) }, {
                                SiteCircle(t.url, host)
                            }, t.title.ifBlank { host }, listOf(t.group, t.url).filter { it.isNotEmpty() }.joinToString(" · ")) {
                                if (t.pinned) Icon(painterResource(R.drawable.ic_push_pin), null, Modifier.size(18.dp), tint = BROWSER_COLOR)
                            }
                        }
                    }
                } else LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(vertical = 4.dp)) {
                    if (folder.isEmpty() && query.isBlank() && others.isNotEmpty()) item(key = "devices") {
                        ManagerRow(false, false, {}, { devices = true; clear() }, {
                            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_devices), null, tint = BROWSER_COLOR) }
                        }, stringResource(R.string.browser_other_devices), others.joinToString(", ") { it.name }) {
                            Text("${others.sumOf { it.tabs.size }}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                            Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.padding(start = 4.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (marks.isEmpty() && subs.isEmpty() && !(folder.isEmpty() && query.isBlank() && others.isNotEmpty())) item {
                        Text(stringResource(if (library.marks.isEmpty() && folder.isEmpty()) R.string.browser_no_bookmarks else R.string.browser_folder_empty),
                            Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(subs, key = { "f/$it" }) { f ->
                        val on = f in dirs
                        ManagerRow(on, choosing, { dirs = if (on) dirs - f else dirs + f }, { go(f) }, {
                            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_folder), null, tint = BROWSER_COLOR) }
                        }, BookmarkLibrary.nameOf(f), if (query.isBlank()) null else BookmarkLibrary.parentOf(f).replace("/", " › ").ifEmpty { null }) {
                            Text("${library.count(f)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                            Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.padding(start = 4.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(marks, key = { "b/" + it.url }) { b ->
                        val on = b.url in urls
                        val host = BrowserRules.site(BrowserRules.host(b.url))
                        ManagerRow(on, choosing, { urls = if (on) urls - b.url else urls + b.url }, { open(b.url) }, {
                            SiteCircle(b.url, host)
                        }, b.title.ifBlank { host }, listOfNotNull(b.folder.takeIf { query.isNotBlank() && it.isNotEmpty() }?.replace("/", " › "), b.url).joinToString(" · ")) {
                            if (b.pinned) Icon(painterResource(R.drawable.ic_push_pin), stringResource(R.string.browser_pin_favorite), Modifier.size(18.dp), tint = BROWSER_COLOR)
                        }
                    }
                }
            }
            // the bar at the bottom: New folder and Select, or what is chosen and what can be done with it
            if (!devices) HorizontalDivider()
            if (!devices) Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!choosing) {
                    BarButton(R.drawable.ic_add, stringResource(R.string.browser_new_folder)) { ask = "new" }
                    BarButton(R.drawable.ic_checklist, stringResource(R.string.browser_select), enabled = marks.isNotEmpty() || subs.isNotEmpty()) { selecting = true }
                } else {
                    IconButton(onClick = ::clear) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.common_cancel)) }
                    Text("$n", Modifier.padding(end = 8.dp), style = MaterialTheme.typography.titleMedium)
                    BarButton(R.drawable.ic_select_all, stringResource(R.string.common_all)) { urls = marks.map { it.url }.toSet(); dirs = subs.toSet() }
                    if (n == 1) BarButton(R.drawable.ic_edit, stringResource(if (dirs.isEmpty()) R.string.common_edit else R.string.common_rename)) { ask = if (dirs.isEmpty()) "edit" else "rename" }
                    BarButton(R.drawable.ic_move, stringResource(R.string.common_move), enabled = n > 0) { ask = "move" }
                    if (urls.isNotEmpty()) { // the start page's front; all pinned already: unpin
                        val pinned = library.marks.filter { it.url in urls }.all { it.pinned }
                        BarButton(R.drawable.ic_push_pin, stringResource(if (pinned) R.string.browser_unpin_short else R.string.browser_pin_short)) { keep(library.pin(urls)) }
                    }
                    BarButton(R.drawable.ic_delete, stringResource(R.string.common_delete), enabled = n > 0) { ask = "delete" }
                }
            }
        }
    }

    when (ask) {
        "new" -> NameDialog(stringResource(R.string.browser_new_folder), "", close = { ask = null }) { keep(library.add(BookmarkLibrary.join(folder, BookmarkLibrary.path(it)))); ask = null }
        "rename" -> dirs.single().let { d -> NameDialog(stringResource(R.string.common_rename), BookmarkLibrary.nameOf(d), close = { ask = null }) {
            keep(library.rename(d, it)); ask = null; clear()
        } }
        "edit" -> library.marks.first { it.url == urls.single() }.let { b -> EditBookmarkDialog(b, taken = { u -> u != b.url && library.marks.any { it.url == u } }, close = { ask = null }) { t, u ->
            keep(library.edit(b.url, t, u)); ask = null; clear()
        } }
        "move" -> FolderDialog(stringResource(R.string.browser_move_to), folder, library.tree().filter { f -> dirs.none { BookmarkLibrary.under(f, it) } }, close = { ask = null }) { to ->
            keep(library.move(urls, dirs, to)); ask = null; clear()
        }
        "delete" -> {
            val gone = library.marks.count { b -> b.url in urls || dirs.any { BookmarkLibrary.under(b.folder, it) } }
            AlertDialog(onDismissRequest = { ask = null }, title = { Text(stringResource(R.string.browser_delete_chosen, gone, dirs.size)) },
                confirmButton = { TextButton(onClick = { keep(library.delete(urls, dirs)); ask = null; clear() }) { Text(stringResource(R.string.common_delete)) } },
                dismissButton = { TextButton(onClick = { ask = null }) { Text(stringResource(R.string.common_cancel)) } })
        }
    }
}

private val RoundedCornerShapeNone = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)

/** A folder or a page: a tap opens it, or chooses it while choosing; a long press starts choosing. */
@OptIn(ExperimentalFoundationApi::class)
/** A bookmark's circle: the site's icon when this device has it, else its letter on its colour. */
@Composable private fun SiteCircle(url: String, host: String) {
    val pic = siteIcon(url, fetch = false)
    Box(Modifier.size(40.dp).clip(CircleShape).background(if (pic != null) Color.White else TILE_COLORS[(host.hashCode() and 0x7fffffff) % TILE_COLORS.size]), contentAlignment = Alignment.Center) {
        if (pic != null) androidx.compose.foundation.Image(pic, null, Modifier.size(26.dp)) else Text(host.take(1).uppercase(), color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable private fun ManagerRow(on: Boolean, choosing: Boolean, toggle: () -> Unit, open: () -> Unit, lead: @Composable () -> Unit, title: String, sub: String?, end: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth().background(if (on) BROWSER_COLOR.copy(alpha = 0.15f) else Color.Transparent)
        .combinedClickable(onClick = { if (choosing) toggle() else open() }, onLongClick = toggle).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (choosing) Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(painterResource(if (on) R.drawable.ic_check_box else R.drawable.ic_check_box_outline_blank), null, tint = if (on) BROWSER_COLOR else LocalContentColor.current)
        } else lead()
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        end()
    }

@Composable private fun RowScope.BarButton(icon: Int, label: String, enabled: Boolean = true, click: () -> Unit) =
    Column(Modifier.weight(1f).clip(MaterialTheme.shapes.medium).clickable(enabled = enabled, onClick = click).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val c = LocalContentColor.current.copy(alpha = if (enabled) 1f else 0.38f)
        Icon(painterResource(icon), null, Modifier.size(22.dp), tint = c)
        Text(label, color = c, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

@Composable private fun NameDialog(title: String, start: String, close: () -> Unit, done: (String) -> Unit) {
    var name by remember { mutableStateOf(start) }
    AlertDialog(onDismissRequest = close, title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.common_name)) }) },
        confirmButton = { TextButton(onClick = { done(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.common_ok)) } },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.common_cancel)) } })
}

/** A page's name and address; an address already kept as another bookmark is refused (a bookmark is known by its address). */
@Composable private fun EditBookmarkDialog(b: Bookmark, taken: (String) -> Boolean, close: () -> Unit, done: (String, String) -> Unit) {
    var title by remember { mutableStateOf(b.title) }
    var url by remember { mutableStateOf(b.url) }
    val to = url.trim().let { if (it.isEmpty()) "" else BrowserRules.target(it) }
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.browser_edit_bookmark)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it }, singleLine = true, label = { Text(stringResource(R.string.common_name)) })
            OutlinedTextField(url, { url = it }, singleLine = true, label = { Text(stringResource(R.string.browser_bookmark_url)) }, isError = to.isNotEmpty() && taken(to))
        } },
        confirmButton = { TextButton(onClick = { done(title.trim(), to) }, enabled = to.isNotEmpty() && !taken(to)) { Text(stringResource(R.string.common_ok)) } },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.common_cancel)) } })
}
