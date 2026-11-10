package com.kalotrapezis.drive

import android.Manifest
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.provider.Settings
import android.provider.Telephony
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

// Messages (the user, 2 Nov): a chat app's form — the conversations, a tap into one; New, Archive and Delete in the island.

internal val SMS_COLOR = Color(0xFF037682) // the teal of the user's icon (Assets/SMS.png)

/** `start`: AI Assist's "sms:{to, text}" (its card's Edit): that conversation, the text waiting in the field. */
@Composable
internal fun SmsScreen(back: () -> Unit, start: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var granted by remember { mutableStateOf(SmsBox.canRead(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = SmsBox.canRead(context); version++ }
    LaunchedEffect(Unit) { if (!granted || !SmsBox.canSend(context)) ask.launch(SmsBox.PERMISSIONS) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle, granted) { // back from Android's settings, and every message that comes or goes: read again
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) { granted = SmsBox.canRead(context); version++ } }
        val sms = object : ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) { override fun onChange(self: Boolean) { version++ } }
        lifecycle.lifecycle.addObserver(o)
        if (granted) runCatching { context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, sms) }
        onDispose { lifecycle.lifecycle.removeObserver(o); context.contentResolver.unregisterContentObserver(sms) }
    }
    val texts by produceState<List<SmsText>?>(null, version, granted) {
        if (granted) value = withContext(Dispatchers.IO) { runCatching { SmsBox.read(context) }.onFailure { WriterLog.w("Messages: not read", it) }.getOrDefault(emptyList()) }
    }
    OpenTiming("Messages", texts != null)
    val threads = remember(texts) { SmsRules.threads(texts.orEmpty()) }
    // names and faces from Tetra's contacts, as the Phone's Recents
    val contacts by produceState(ContactsPreload.all?.map { it.info } ?: emptyList()) {
        value = withContext(Dispatchers.IO) { runCatching { ContactsPreload.load(context, ContactsPrefs.byLast(context)).map { it.info } }.getOrDefault(value) }
    }
    val byKey = remember(contacts) { contacts.flatMap { c -> c.phones.map { ContactsWays.key(it.value) to c } }.filter { it.first.length >= 5 }.toMap() }
    fun who(n: String) = byKey[ContactsWays.key(n)]
    var archive by remember { mutableStateOf(SmsArchive.get(context)) }
    var archivedView by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var open by remember { mutableStateOf<String?>(null) } // the open conversation's number
    var draft by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) } // New: a contact or a number
    var query by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf(false) } // Delete's yes
    var refused by remember { mutableStateOf<List<String>?>(null) } // nothing deleted: only the default app may
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) { if (message != null) { delay(3000); message = null } }
    LaunchedEffect(start) { start?.let { runCatching { JSONObject(it.removePrefix("sms:")) }.getOrNull() }?.let { open = it.optString("to"); draft = it.optString("text") } }

    if (!granted) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_back)) }
            Text(stringResource(R.string.sms_permission_needed))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { ask.launch(SmsBox.PERMISSIONS) }) { Text(stringResource(R.string.common_allow)) }
                // refused twice, Android no longer asks: its settings are the way
                TextButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) } }) { Text(stringResource(R.string.common_settings)) }
            }
        }
        return
    }
    // a tablet held either way: the conversations on the left, the open one beside them (the user, 2 Nov), as Mail's
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 840
    @Composable fun conversation(address: String) = SmsConversation(address, SmsRules.threadOf(threads, address), texts.orEmpty(), who(address), draft, { draft = it },
        say = { message = it }, back = { open = null; draft = "" })
    if (!wide) open?.let { address ->
        Box(Modifier.fillMaxSize()) {
            conversation(address)
            message?.let { SmsToast(it) }
        }
        return
    }

    fun toggle(id: Long) { selected = if (id in selected) selected - id else selected + id }
    fun stopPicking() { picking = false; query = "" }
    BackHandler(selected.isNotEmpty() || picking || archivedView) { when { selected.isNotEmpty() -> selected = emptySet(); picking -> stopPicking(); else -> archivedView = false } }
    val shown = remember(threads, archive, archivedView) { threads.filter { SmsRules.archived(archive, it) == archivedView } }
    val pad = PaddingValues(top = 76.dp, bottom = 110.dp)
    Row(Modifier.fillMaxSize()) {
    Box((if (wide) Modifier.width(420.dp).fillMaxHeight() else Modifier.fillMaxSize()).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Box(Modifier.align(Alignment.TopCenter).widthIn(max = 640.dp).fillMaxSize()) {
            if (picking) SmsPicker(contacts, query, pad) { n -> stopPicking(); open = n }
            else LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
                if (texts == null) item { SmsHint(stringResource(R.string.common_loading)) }
                else if (shown.isEmpty()) item { SmsHint(stringResource(if (archivedView) R.string.sms_none_archived else R.string.sms_none)) }
                items(shown, key = { it.id }) { t -> SmsRow(t, who(t.address), t.id in selected,
                    click = { if (selected.isEmpty()) { if (open != t.address) draft = ""; open = t.address } else toggle(t.id) }, hold = { toggle(t.id) },
                    current = wide && SmsRules.threadOf(threads, open ?: "")?.id == t.id) }
            }
        }
        // the pill at the top: ‹ and the title, or the chosen count; Archived is a way of viewing, so it is up here
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().widthIn(max = 640.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                IconButton(onClick = { when { selected.isNotEmpty() -> selected = emptySet(); picking -> stopPicking(); archivedView -> archivedView = false; else -> back() } }) {
                    Icon(painterResource(if (selected.isNotEmpty()) R.drawable.ic_close else R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_back))
                }
            }
            Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape, modifier = Modifier.weight(1f)) {
                Row(Modifier.padding(start = 18.dp, end = 4.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(when {
                        selected.isNotEmpty() -> "${selected.size}"
                        picking -> stringResource(R.string.sms_new_message)
                        archivedView -> stringResource(R.string.sms_archived)
                        else -> stringResource(R.string.app_sms)
                    }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (selected.isEmpty() && !picking) Surface(color = if (archivedView) driveNavigationSelectedColor() else Color.Transparent,
                        contentColor = if (archivedView) driveNavigationSelectedContentColor() else islandContentColor(), shape = CircleShape) {
                        IconButton(onClick = { archivedView = !archivedView }) { Icon(painterResource(R.drawable.ic_archive), contentDescription = stringResource(R.string.sms_archived)) }
                    }
                }
            }
        }
        // the island: New · Archive · Delete; New slides it into a strip with ‹ and the name or number being typed
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp, start = 16.dp, end = 16.dp)) {
            if (picking) Surface(color = islandColor(), contentColor = islandContentColor(), shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = ::stopPicking) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_back)) }
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                    Box(Modifier.weight(1f).padding(end = 12.dp)) {
                        if (query.isEmpty()) Text(stringResource(R.string.sms_to), color = islandContentColor().copy(alpha = 0.7f), maxLines = 1)
                        BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = islandContentColor(), fontSize = 16.sp),
                            cursorBrush = SolidColor(islandContentColor()), modifier = Modifier.fillMaxWidth().focusRequester(focus))
                    }
                }
            } else Surface(color = islandColor(), contentColor = islandContentColor(), shape = MaterialTheme.shapes.extraLarge) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val none = selected.isEmpty()
                    SmsIslandItem(R.drawable.ic_add, stringResource(R.string.common_new)) { selected = emptySet(); picking = true }
                    SmsIslandItem(if (archivedView) R.drawable.ic_unarchive else R.drawable.ic_archive, stringResource(if (archivedView) R.string.sms_unarchive else R.string.sms_archive), enabled = !none) {
                        if (none) { message = context.getString(R.string.sms_choose_first); return@SmsIslandItem }
                        // kept with the last message's time: one newer brings the conversation back (SmsRules.archived)
                        archive = if (archivedView) archive - selected else archive + threads.filter { it.id in selected }.associate { it.id to it.last.at }
                        SmsArchive.set(context, archive)
                        message = context.getString(if (archivedView) R.string.sms_did_unarchive else R.string.sms_did_archive)
                        selected = emptySet()
                    }
                    SmsIslandItem(R.drawable.ic_delete, stringResource(R.string.common_delete), enabled = !none) {
                        if (none) message = context.getString(R.string.sms_choose_first) else asking = true
                    }
                }
            }
        }
        if (!wide) message?.let { SmsToast(it) }
    }
    if (wide) Box(Modifier.weight(1f).fillMaxHeight()) {
        key(open) { open?.let { conversation(it) } }
        if (open == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { SmsHint(stringResource(R.string.sms_pick)) }
        message?.let { SmsToast(it) }
    }
    }

    if (asking) AlertDialog(onDismissRequest = { asking = false },
        title = { Text(stringResource(R.string.sms_delete_ask)) }, text = { Text(stringResource(R.string.sms_delete_note)) },
        confirmButton = { TextButton(onClick = {
            asking = false
            val ids = selected; selected = emptySet()
            val addresses = threads.filter { it.id in ids }.map { it.address }
            scope.launch {
                val gone = withContext(Dispatchers.IO) { ids.sumOf { SmsBox.delete(context, it) } }
                WriterLog.i("Messages: delete ${ids.size} conversation(s): $gone message(s) gone")
                // Android lets only the default SMS app delete, and says nothing: say it honestly
                if (gone == 0) refused = addresses else { archive = archive - ids; SmsArchive.set(context, archive); message = context.getString(R.string.sms_deleted) }
                version++
            }
        }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.common_cancel)) } })
    refused?.let { r -> AlertDialog(onDismissRequest = { refused = null }, text = { Text(stringResource(R.string.sms_default_only)) },
        confirmButton = { if (r.size == 1) TextButton(onClick = { SmsBox.openInDefault(context, r[0]); refused = null }) { Text(stringResource(R.string.sms_open_default)) }
            else TextButton(onClick = { refused = null }) { Text(stringResource(R.string.common_ok)) } },
        dismissButton = { if (r.size == 1) TextButton(onClick = { refused = null }) { Text(stringResource(R.string.common_close)) } }) }
}

/** One conversation: their bubbles left, the user's right, the newest at the bottom; the field and Send under them. */
@Composable
private fun SmsConversation(address: String, thread: SmsThread?, all: List<SmsText>, c: ContactInfo?, draft: String, setDraft: (String) -> Unit, say: (String) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = back)
    val texts = remember(all, thread) { if (thread == null) emptyList() else all.filter { it.thread == thread.id } } // newest first: the list runs upwards
    // opened is read; Android may keep that to the default app, and then nothing changes
    LaunchedEffect(thread?.id) { thread?.takeIf { it.unread > 0 }?.let { withContext(Dispatchers.IO) { SmsBox.markRead(context, it.id) } } }
    var sending by remember { mutableStateOf(false) }
    fun sendNow() {
        val text = draft.trim().takeIf { it.isNotEmpty() && !sending } ?: return
        sending = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { SmsBox.send(context, address, text) } }
            sending = false
            r.onSuccess { setDraft("") }.onFailure { WriterLog.w("Messages: not sent", it); say(context.getString(R.string.sms_send_failed, it.message ?: it.javaClass.simpleName)) }
        }
    }
    val askSend = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) sendNow() }
    fun send() = if (SmsBox.canSend(context)) sendNow() else askSend.launch(Manifest.permission.SEND_SMS)
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now() }
    val locale = Locale.getDefault()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_back)) }
            }
            CallAvatar(c, 40.dp)
            Column(Modifier.weight(1f)) {
                Text(c?.name ?: address, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (c != null) Text(address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        LazyColumn(Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth(), reverseLayout = true, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (texts.isEmpty()) item { SmsHint(stringResource(R.string.sms_none_here)) }
            items(texts, key = { it.id }) { t -> SmsBubble(t, whenText(t.at, zone, today, locale)) }
        }
        Row(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            Surface(color = islandColor(), contentColor = islandContentColor(), shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f)) {
                Box(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    if (draft.isEmpty()) Text(stringResource(R.string.sms_write), color = islandContentColor().copy(alpha = 0.7f), maxLines = 1)
                    BasicTextField(draft, setDraft, maxLines = 6, textStyle = TextStyle(color = islandContentColor(), fontSize = 16.sp),
                        cursorBrush = SolidColor(islandContentColor()), modifier = Modifier.fillMaxWidth())
                }
            }
            Surface(color = SMS_COLOR, contentColor = Color.White, shape = CircleShape, modifier = Modifier.alpha(if (draft.isBlank() || sending) 0.5f else 1f)) {
                IconButton(onClick = ::send, enabled = draft.isNotBlank() && !sending, modifier = Modifier.size(52.dp)) {
                    if (sending) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                    else Icon(painterResource(R.drawable.ic_send), contentDescription = stringResource(R.string.common_send))
                }
            }
        }
    }
}

@Composable
private fun SmsBubble(t: SmsText, at: String) = Column(Modifier.fillMaxWidth(), horizontalAlignment = if (t.mine) Alignment.End else Alignment.Start) {
    Surface(color = if (t.mine) SMS_COLOR else MaterialTheme.colorScheme.surfaceVariant, contentColor = if (t.mine) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(18.dp, 18.dp, if (t.mine) 4.dp else 18.dp, if (t.mine) 18.dp else 4.dp), modifier = Modifier.widthIn(max = 320.dp)) {
        SelectionContainer { Text(t.body, Modifier.padding(horizontal = 14.dp, vertical = 9.dp), style = MaterialTheme.typography.bodyLarge) }
    }
    Text(when { t.failed -> stringResource(R.string.sms_not_sent); t.sending -> stringResource(R.string.sms_sending); else -> at },
        Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall,
        color = if (t.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A conversation in the list: face, name, the last message, its time, a dot while theirs is unread; held, it is chosen. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SmsRow(t: SmsThread, c: ContactInfo?, selected: Boolean, click: () -> Unit, hold: () -> Unit, current: Boolean = false) {
    val unread = t.unread > 0
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(MaterialTheme.shapes.large)
        .background(if (selected || current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
        .combinedClickable(onClick = click, onLongClick = hold).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (selected) Box(Modifier.size(44.dp).clip(CircleShape).background(SMS_COLOR), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Color.White)
        } else CallAvatar(c, 44.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c?.name ?: t.address, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(whenText(t.last.at, ZoneId.systemDefault(), LocalDate.now(), Locale.getDefault()), style = MaterialTheme.typography.labelSmall,
                    color = if (unread) SMS_COLOR else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (t.last.mine) stringResource(R.string.sms_you) + ": " else "") + t.last.body.replace('\n', ' '), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (unread) Box(Modifier.padding(start = 8.dp).size(10.dp).background(SMS_COLOR, CircleShape))
            }
        }
    }
}

/** New: Tetra's contacts with a number (each number a row), filtered by what is typed; a typed number goes as it is. */
@Composable
private fun SmsPicker(contacts: List<ContactInfo>, query: String, pad: PaddingValues, pick: (String) -> Unit) {
    val rows = remember(contacts, query) {
        contacts.filter { it.phones.isNotEmpty() && ContactsFiles.matches(it, query) }.sortedBy { ContactsFiles.sortKey(it, false) }.flatMap { c -> c.phones.map { c to it } }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
        if (SmsRules.isNumber(query)) item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(MaterialTheme.shapes.large).clickable { pick(query.trim()) }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CallAvatar(null, 40.dp)
                Text(stringResource(R.string.sms_send_to, query.trim()), style = MaterialTheme.typography.bodyLarge)
            }
        } else if (rows.isEmpty() && query.isNotBlank()) item { SmsHint(stringResource(R.string.contacts_no_match, query)) }
        items(rows) { (c, p) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(MaterialTheme.shapes.large).clickable { pick(p.value) }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ContactAvatar(c, 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(p.label, p.value).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

/** An island's action with its word under it, as Mail's. */
@Composable private fun SmsIslandItem(icon: Int, label: String, enabled: Boolean = true, click: () -> Unit) = Surface(
    color = Color.Transparent, contentColor = islandContentColor(), shape = MaterialTheme.shapes.extraLarge,
    modifier = Modifier.heightIn(min = 48.dp).alpha(if (enabled) 1f else 0.45f).clickable(onClick = click),
) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(icon), null, Modifier.size(22.dp)); Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable private fun SmsHint(text: String) = Text(text, Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable private fun BoxScope.SmsToast(text: String) = Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape,
    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp)) { Text(text, Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) }
