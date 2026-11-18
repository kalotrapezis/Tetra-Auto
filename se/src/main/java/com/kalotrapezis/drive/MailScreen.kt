package com.kalotrapezis.drive

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.FileObserver
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Mail's colour: the icon's own pink (tools/applet-icons.py: applet_mail). */
internal val MAIL_COLOR = Color(0xFFFD0DA4)
/** What is chosen or the main action: a dark pink, the tone of a picked row (the user, 31 Oct: the icon's pink is too bright and too like the Calendar's). */
internal val MAIL_ACCENT = Color(0xFF6E1B50)
/** Pink words (day headings, text buttons): muted, readable on the dark ground. */
internal val MAIL_TEXT = Color(0xFFD27AAE)
private val GREEN = Color(0xFF3FB27F)
private val ORANGE = Color(0xFFE0A43A)

/**
 * Where Mail's islands stand (the user, 31 Oct: "UI options as the Office has, top or bottom or automatic"): Office's own
 * rule — "auto" at the top while a mouse is in use and at the bottom for a finger (OfficeUi.mouse), or always one.
 */
internal object MailLook {
    var place by mutableStateOf("auto"); private set
    /** At the top only on a tablet or a big screen: on a phone they stay at the bottom (the user, 31 Oct: "that breaks the UI"). */
    val top: Boolean @Composable get() = LocalConfiguration.current.screenWidthDp >= 700 && (place == "top" || (place == "auto" && OfficeUi.mouse))
    private fun p(c: android.content.Context) = c.getSharedPreferences("mail", android.content.Context.MODE_PRIVATE)
    /** Conversations: the list one row per conversation, its mails stacked like a chat (Settings › Εμφάνιση › Συζητήσεις). */
    var threads by mutableStateOf(false); private set
    /** The mail a wide screen shows by itself counts as read too (Settings › Εμφάνιση; the user, 1 Nov). */
    var readShown by mutableStateOf(false); private set
    fun load(c: android.content.Context) { place = p(c).getString("islands", "auto") ?: "auto"; threads = p(c).getBoolean("threads", false); readShown = p(c).getBoolean("readShown", false) }
    fun set(c: android.content.Context, where: String) { place = where; p(c).edit().putString("islands", where).apply() }
    fun setThreads(c: android.content.Context, on: Boolean) { threads = on; p(c).edit().putBoolean("threads", on).apply() }
    fun setReadShown(c: android.content.Context, on: Boolean) { readShown = on; p(c).edit().putBoolean("readShown", on).apply() }
}

internal fun mailRoot() = MailFiles.root(TetraFolder.root(Environment.getExternalStorageDirectory()))

private val ROLE_NAMES = mapOf("Inbox" to R.string.mail_inbox, "Drafts" to R.string.mail_drafts, "Sent" to R.string.mail_sent,
    "Archive" to R.string.mail_archive, "Spam" to R.string.mail_spam, "Trash" to R.string.mail_trash)
private val ROLE_ICONS = mapOf("Inbox" to R.drawable.ic_mail, "Drafts" to R.drawable.ic_edit_note, "Sent" to R.drawable.ic_arrow_forward,
    "Archive" to R.drawable.ic_archive, "Spam" to R.drawable.ic_report, "Trash" to R.drawable.ic_delete)

/**
 * Mail (MAIL_PLAN §9), read only: the computer's archive as sync brought it. Folders at the side (the unified ones
 * expanding into the accounts), the list, the open mail; a tablet has all three side by side with two islands (the
 * list's actions, the mail's one big Απάντηση), a phone goes Mailboxes → list → mail. What would change the server is
 * shown but waits for step 3 (actions through the PC) and 4 (writing).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MailScreen(start: String? = null, back: () -> Unit, hasAccess: Boolean, grant: () -> Unit, openFile: (File) -> Unit, openSettings: () -> Unit,
    openFolder: (String) -> Unit = {}) {
    val context = LocalContext.current
    val width = LocalConfiguration.current.screenWidthDp
    val three = width >= 1000 // folders · list · mail
    val wide = width >= 700   // folders beside the list (or the mail)
    if (!hasAccess) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_back)) }
            Text(stringResource(R.string.mail_access_why))
            TextButton(onClick = grant) { Text(stringResource(R.string.contacts_allow_access)) }
        }
        return
    }
    val root = remember { mailRoot() }
    var version by remember { mutableIntStateOf(0) }
    var storage by remember { mutableStateOf(false) } // «Αποθήκη»
    // the computer's index and accounts arriving by sync: read again
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(root, lifecycle) {
        root.mkdirs()
        val watch = object : FileObserver(root, CLOSE_WRITE or MOVED_TO or DELETE) {
            override fun onEvent(event: Int, path: String?) { if (path != null && (path.startsWith("index.") || path.startsWith("accounts.")) && !path.endsWith(".part")) android.os.Handler(android.os.Looper.getMainLooper()).post { version++ } }
        }
        watch.startWatching()
        // opened or come back to: a look at once (Settings › Έλεγχος για νέα), this device's accounts at their servers and
        // the PC's through the PC; IDLE while in front with «Αμέσως»
        var stop: () -> Unit = {}
        val seen = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_START && MailPrefs.every(context) != MailPrefs.MANUAL) {
                stop(); stop = MailEngine.watch(context.applicationContext, root)
                Thread { runCatching { MailEngine.askPc(context.applicationContext, root) } }.start()
            }
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) { stop(); stop = {} }
        }
        lifecycle.lifecycle.addObserver(seen)
        MailEngine.schedule(context.applicationContext)
        onDispose { watch.stopWatching(); lifecycle.lifecycle.removeObserver(seen); stop() }
    }
    val data by produceState<Pair<List<MailAccount>, List<MailEntry>>?>(null, version) {
        value = withContext(Dispatchers.IO) { runCatching { MailFiles.accounts(root, MailSecret.ids(context)) to MailFiles.index(root, MailEngine.device()) }.onFailure { WriterLog.e("Mail: reading the archive", it) }.getOrDefault(emptyList<MailAccount>() to emptyList()) }
    }
    val accounts = data?.first.orEmpty()
    // the computer's mail AI (MailAi): each mail's urgency, read again with the index; no file, no dots
    val urgency by produceState(emptyMap<String, MailAi.Urgency>(), version) { value = withContext(Dispatchers.IO) { MailAi.urgency(root) } }
    // what was asked here and the computer has not done yet, shown as done (step 1: show it, then trust index.jsonl)
    var changed by remember { mutableStateOf(mapOf<String, MailEntry?>()) }
    // «Αποθήκη»: the same folders, list and pages over every mail kept here, deleted ones too (MailArchive); read only
    var stored by remember { mutableStateOf(false) }
    var storeOpen by remember { mutableStateOf(false) } // «Αποθήκη» expanded into its accounts and their folders
    var storeAccount by remember { mutableStateOf<String?>(null) } // null: every account's
    var storeRole by remember { mutableStateOf<String?>(null) } // null: every folder's
    val archive by produceState<List<MailEntry>?>(null, stored || storeOpen, version) {
        if (stored || storeOpen) value = withContext(Dispatchers.IO) { runCatching { MailArchive.entries(root, MailEngine.device()) }.onFailure { WriterLog.e("Mail: reading the stored mail", it) }.getOrDefault(emptyList()) }
    }
    val mails = remember(data, changed, stored, archive) { if (stored) archive.orEmpty() else data?.second.orEmpty().mapNotNull { e -> if (e.id in changed) changed[e.id] else e } }
    OpenTiming("Mail", data != null)
    val counts = remember(mails, accounts) { MailFiles.counts(mails, accounts) }
    val accountOf = remember(accounts) { accounts.associateBy { it.id } }

    var role by remember { mutableStateOf("Inbox") }
    var account by remember { mutableStateOf<String?>(null) } // null: the unified folder
    val folderKey = role + (account?.let { "/$it" } ?: "")
    var expanded by remember { mutableStateOf(setOf("Inbox")) }
    var mailboxes by remember { mutableStateOf(false) } // a phone's first level
    var openId by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var query by remember { mutableStateOf("") }
    var view by remember(folderKey) { mutableStateOf(MailPrefs.view(context, folderKey) ?: MailView()) }
    var keep by remember(folderKey) { mutableStateOf(MailPrefs.view(context, folderKey) != null) }
    var filterOpen by remember { mutableStateOf(false) }
    var listDrawer by remember { mutableStateOf(false) }
    var mailDrawer by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) { if (message != null) { delay(3500); message = null } }
    var exported by remember { mutableStateOf<File?>(null) } // a copy just put in Tetra/Documents: said, with «Εμφάνιση»
    LaunchedEffect(exported) { if (exported != null) { delay(6000); exported = null } }
    val later = { message = context.getString(R.string.mail_later) }
    val writeLater = { message = context.getString(R.string.mail_write_later) }
    var moving by remember { mutableStateOf<Collection<String>?>(null) }
    var writing by remember { mutableStateOf<MailDraft?>(null) } // the compose page, over the mail (MailCompose)
    var adding by remember { mutableStateOf(false) } // «Προσθήκη λογαριασμού» here: Tetra on the phone alone needs no PC
    var single by remember { mutableStateOf<String?>(null) } // one mail of a conversation, opened from its card
    LaunchedEffect(openId) { single = null }
    // what the PC could not send from the outbox: said once, then gone (MAIL_PLAN §12)
    val failedSends by produceState(emptyList<Triple<String, String, File>>(), version) { value = withContext(Dispatchers.IO) { MailOutbox.failed(root) } }
    var failedSeen by remember { mutableStateOf(emptySet<File>()) }
    var autoOpened by remember { mutableStateOf(false) }
    // AI Assist's hand-over: a mail it found (its folder, then the mail), or one it wrote, open in the compose page to send
    LaunchedEffect(start, data) {
        when {
            data == null -> Unit // the accounts first: «Από» is chosen from them
            start?.startsWith("mail-write:") == true -> if (writing == null) runCatching { org.json.JSONObject(start.removePrefix("mail-write:")) }.getOrNull()?.let { o ->
                writing = MailDraft.of(o) }
            start?.startsWith("mail:") == true -> mails.firstOrNull { it.id == start.removePrefix("mail:") }?.let { e -> if (openId != e.id) { role = e.folder; account = null; mailboxes = false; autoOpened = false; openId = e.id } }
        }
    }
    /** [op] on these mails: a request for the computer, sent at once; shown done here meanwhile. */
    fun act(op: String, ids: Collection<String>, to: String? = null) {
        if (ids.isEmpty()) return
        if (stored) { if (op != "read") message = context.getString(R.string.mail_stored_read_only); return } // the archive is never the server's
        val now = mails.filter { it.id in ids }
        changed = changed + now.associate { it.id to MailRequests.applied(it, op) }
        if (MailRequests.applied(now.first(), op) == null) { selected = emptySet(); if (openId in ids) openId = null }
        // this device's own accounts: on the server at once; the PC's: a request it carries out
        val own = now.filter { accountOf[it.account]?.own == true }.map { it.id }
        val theirs = ids - own.toSet()
        Thread {
            if (own.isNotEmpty()) runCatching { MailEngine.act(context.applicationContext, root, op, own, to) }.onFailure {
                WriterLog.e("Mail: $op on the server", it)
                android.os.Handler(android.os.Looper.getMainLooper()).post { changed = changed - own.toSet(); message = context.getString(R.string.mail_not_done, mailWhy(context, it)) }
            }
            if (theirs.isNotEmpty()) {
                runCatching { MailRequests.write(root, MailEngine.device(), op, theirs, to) }.onFailure { WriterLog.e("Mail: a request", it) }
                ScopedSync.soon(context.applicationContext, "mail", 0, orWhole = true)
            }
        }.start()
        if (op in setOf("archive", "delete", "move", "spam", "notSpam")) message = context.getString(R.string.mail_done_on_pc)
    }
    val spamOp = if (role == "Spam") "notSpam" else "spam"

    val inFolder = remember(mails, role, account, stored, storeAccount, storeRole) {
        if (stored) mails.filter { (storeAccount == null || it.account == storeAccount) && (storeRole == null || it.folder == storeRole) } // a removed account's too
        else mails.filter { it.folder == role && it.account in accountOf && (account == null || it.account == account) }
    }
    // only with conversations on: every subject through a regex, on the UI thread, at each change (most of a 0.5 s frame)
    val conversations = remember(mails, accountOf, MailLook.threads) { if (MailLook.threads) MailThreads.of(mails.filter { it.account in accountOf }) else emptyMap() }
    // conversations on: each one once, where its first mail in this order stands
    val groups = remember(inFolder, view, query, MailLook.threads) { view.arrange(inFolder, query).let { gs ->
        if (!MailLook.threads) gs else { val seen = HashSet<String>(); gs.map { (k, ms) -> k to ms.filter { seen.add(MailThreads.key(it)) } }.filter { it.second.isNotEmpty() } }
    } }
    val shown = remember(groups) { groups.flatMap { it.second } }
    val open = mails.firstOrNull { it.id == openId }
    // a wide screen always shows a mail: the newest, until one is chosen
    LaunchedEffect(three, shown) { if (three && (open == null || open !in shown) && shown.isNotEmpty()) { openId = shown.first().id; autoOpened = true } }
    // a mail the person opened is read (Thunderbird's way); the one a wide screen showed by itself only with readShown on
    // the open mail, or with conversations on all of its conversation (oldest first): what the island and the panel act on
    val current = open?.let { o -> conversations[MailThreads.key(o)]?.takeIf { MailLook.threads && it.size > 1 } ?: listOf(o) }.orEmpty()
    // what a reply answers: the newest mail from someone else (not one's own reply, which would address oneself), and no draft
    val newest = current.lastOrNull { it.folder != "Drafts" && accounts.none { a -> a.address.equals(it.fromAddress, true) } } ?: current.lastOrNull { it.folder != "Drafts" } ?: current.lastOrNull()
    LaunchedEffect(openId) { if (!autoOpened || MailLook.readShown) current.filter { !it.seen }.map { it.id }.takeIf { it.isNotEmpty() }?.let { act("read", it) } }
    fun choose(r: String, a: String?) { stored = false; role = r; account = a; openId = null; selected = emptySet(); mailboxes = false }
    fun chooseStored(a: String?, r: String?) { stored = true; storeAccount = a; storeRole = r; openId = null; selected = emptySet(); mailboxes = false }

    BackHandler { when {
        selected.isNotEmpty() -> selected = emptySet()
        stored && open == null -> { stored = false; openId = null }
        single != null -> single = null // from a conversation's mail back to the conversation
        !three && open != null -> openId = null
        query.isNotEmpty() -> query = ""
        !wide && !mailboxes -> mailboxes = true
        else -> back()
    } }

    // ---- the folders (MAIL_PLAN §4): unified, each expanding into the accounts; counts green or orange ----
    val folders: @Composable (Modifier) -> Unit = { m ->
        Column(m.statusBarsPadding()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                    IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.home_back_to_home)) }
                }
                Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape, modifier = Modifier.weight(1f)) {
                    Text(stringResource(if (stored) R.string.mail_storage else R.string.app_mail), Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 13.dp), fontWeight = FontWeight.Bold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (data != null && accounts.isEmpty()) item { NoAccounts { adding = true } }
                if (accounts.isNotEmpty()) MailFiles.ROLES.forEach { r ->
                    val per = counts[r].orEmpty()
                    item(key = r) {
                        val on = !stored && role == r && account == null
                        Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(if (on) MAIL_COLOR.copy(alpha = 0.22f) else Color.Transparent)
                            .clickable { choose(r, null) }.padding(end = 10.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { expanded = if (r in expanded) expanded - r else expanded + r }) {
                                Icon(painterResource(R.drawable.ic_chevron_right), stringResource(R.string.mail_show_accounts), Modifier.size(20.dp).rotate(if (r in expanded) 90f else 0f))
                            }
                            Icon(painterResource(ROLE_ICONS.getValue(r)), null, Modifier.size(20.dp))
                            Text(stringResource(ROLE_NAMES.getValue(r)), Modifier.weight(1f).padding(start = 10.dp), fontWeight = if (on) FontWeight.Bold else null, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            CountBadge(per.values.sumOf { it.unread }, per.values.sumOf { it.orange })
                        }
                    }
                    if (r in expanded) accounts.forEach { a ->
                        val c = per[a.id] ?: MailCount(0, 0, 0)
                        item(key = "$r/${a.id}") {
                            val on = !stored && role == r && account == a.id
                            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(if (on) MAIL_COLOR.copy(alpha = 0.22f) else Color.Transparent)
                                .clickable { choose(r, a.id) }.padding(start = 52.dp, end = 10.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(9.dp).clip(CircleShape).background(Color(a.color)))
                                // a shadow account (the PC's) dimmed, with ⇄ PC (MAIL_PLAN §1)
                                Text(a.name, Modifier.weight(1f).alpha(if (a.shadow) 0.6f else 1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (a.shadow) PcBadge()
                                CountBadge(c.unread, c.orange)
                            }
                        }
                    }
                }
                // «Αποθήκη» at the bottom: the mail kept on this device; it expands as the desktop's — each account (a removed
                // one by its address), each of its stored folders; itself, all of it
                if (accounts.isNotEmpty()) item(key = "stored") {
                    val on = stored && storeAccount == null && storeRole == null
                    Row(Modifier.padding(top = 8.dp).fillMaxWidth().clip(MaterialTheme.shapes.large).background(if (on) MAIL_COLOR.copy(alpha = 0.22f) else Color.Transparent)
                        .clickable { chooseStored(null, null) }.padding(end = 10.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { storeOpen = !storeOpen }) {
                            Icon(painterResource(R.drawable.ic_chevron_right), stringResource(R.string.mail_show_accounts), Modifier.size(20.dp).rotate(if (storeOpen) 90f else 0f))
                        }
                        Icon(painterResource(R.drawable.ic_folder), null, Modifier.size(20.dp), tint = MAIL_COLOR)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(stringResource(R.string.mail_storage), fontWeight = if (on) FontWeight.Bold else null)
                            Text(stringResource(R.string.mail_stored_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (accounts.isNotEmpty() && storeOpen) {
                    val tree = MailArchive.tree(archive.orEmpty())
                    if (archive == null) item(key = "stored:reading") { MailHint(stringResource(R.string.mail_reading)) }
                    tree.forEach { (id, roles) ->
                        val a = accountOf[id]
                        item(key = "stored/$id") {
                            val on = stored && storeAccount == id && storeRole == null
                            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(if (on) MAIL_COLOR.copy(alpha = 0.22f) else Color.Transparent)
                                .clickable { chooseStored(id, null) }.padding(start = 52.dp, end = 10.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(9.dp).clip(CircleShape).background(Color(a?.color ?: 0xFF9E9E9E)))
                                Text(a?.name ?: id, Modifier.weight(1f), fontWeight = if (on) FontWeight.Bold else null, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                StoredCount(roles.values.sumOf { it.first }, roles.values.sumOf { it.second })
                            }
                        }
                        roles.forEach { (r, n) -> item(key = "stored/$id/$r") {
                            val on = stored && storeAccount == id && storeRole == r
                            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(if (on) MAIL_COLOR.copy(alpha = 0.22f) else Color.Transparent)
                                .clickable { chooseStored(id, r) }.padding(start = 76.dp, end = 10.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(painterResource(ROLE_ICONS[r] ?: R.drawable.ic_folder), null, Modifier.size(18.dp))
                                Text(stringResource(ROLE_NAMES[r] ?: R.string.mail_storage), Modifier.weight(1f), fontWeight = if (on) FontWeight.Bold else null, maxLines = 1)
                                StoredCount(n.first, n.second)
                            }
                        } }
                    }
                }
                if (accounts.isNotEmpty()) item {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(Modifier.padding(start = 16.dp, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Legend(GREEN, stringResource(R.string.mail_local)); Legend(GREEN, stringResource(R.string.mail_on_pc), ring = true); Legend(ORANGE, stringResource(R.string.mail_server_only))
                    }
                }
            }
        }
    }

    // ---- the list ----
    val list: @Composable (Modifier) -> Unit = { m ->
        Box(m) {
            LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(top = if (MailLook.top) 150.dp else 72.dp, bottom = if (MailLook.top) 24.dp else 120.dp, start = 8.dp, end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (data == null || (stored && archive == null)) item { MailHint(stringResource(R.string.mail_reading)) }
                else if (accounts.isEmpty()) item { NoAccounts { adding = true } }
                else if (shown.isEmpty()) item { MailHint(stringResource(when {
                    query.isNotEmpty() -> R.string.mail_no_match
                    view != MailView() -> R.string.mail_none_filtered
                    else -> R.string.mail_none_here
                })) }
                groups.forEach { (key, ms) ->
                    if (key.isNotEmpty()) item(key = "g:$key") {
                        Text(groupName(view.group, key, accountOf), Modifier.padding(start = 12.dp, top = 8.dp), color = MAIL_TEXT, style = MaterialTheme.typography.labelLarge)
                    }
                    items(ms, key = { it.id }) { e ->
                        MailRow(e, accountOf[e.account], stored = stored, count = if (MailLook.threads) conversations[MailThreads.key(e)]?.size ?: 1 else 1, picked = e.id in selected, choosing = selected.isNotEmpty(), chosen = wide && selected.isEmpty() && e.id == openId,
                            click = { if (selected.isNotEmpty()) selected = if (e.id in selected) selected - e.id else selected + e.id else { autoOpened = false; openId = e.id } },
                            long = { selected = if (e.id in selected) selected - e.id else selected + e.id },
                            here = remember(e.file, version) { e.file != null && File(root, e.file).isFile },
                            heart = { act(if (e.flagged) "unfavorite" else "favorite", listOf(e.id)) }, urgency = urgency[e.id]?.score ?: 0)
                    }
                }
            }
            // the top: search, or while choosing ✕ · N επιλεγμένα · Όλα
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selected.isNotEmpty()) {
                    Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                        IconButton(onClick = { selected = emptySet() }) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.contacts_done_choosing)) }
                    }
                    Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape, modifier = Modifier.weight(1f)) {
                        Text(pluralStringResource(R.plurals.mail_n_selected, selected.size, selected.size), Modifier.padding(horizontal = 18.dp, vertical = 13.dp), fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                        TextButton(onClick = { selected = if (selected.size == shown.size) emptySet() else shown.map { it.id }.toSet() }) {
                            Text(stringResource(if (selected.size == shown.size) R.string.common_none else R.string.common_all), color = islandContentColor())
                        }
                    }
                } else {
                    if (!wide) Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                        IconButton(onClick = { mailboxes = true }) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.mail_mailboxes)) }
                    }
                    Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape, modifier = Modifier.weight(1f)) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(painterResource(R.drawable.ic_search), contentDescription = null, Modifier.size(22.dp))
                            Box(Modifier.weight(1f)) {
                                // where it searches: in «Αποθήκη», never the live folder's name
                                if (query.isEmpty()) Text(when {
                                    stored -> listOfNotNull(storeAccount?.let { accountOf[it]?.name ?: it }, storeRole?.let { stringResource(ROLE_NAMES[it] ?: R.string.mail_storage) })
                                        .joinToString(" › ").let { if (it.isEmpty()) stringResource(R.string.mail_search_stored) else stringResource(R.string.mail_search_stored_in, it) }
                                    wide -> stringResource(R.string.mail_search)
                                    else -> folderName(role, account, accountOf)
                                }, color = islandContentColor().copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = islandContentColor(), fontSize = 16.sp),
                                    cursorBrush = SolidColor(islandContentColor()), modifier = Modifier.fillMaxWidth())
                            }
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_clear)) }
                        }
                    }
                }
            }
            // the list's island: New · Sync · Filter · Archive, the rest pulled up; while choosing, the selection island
            Box(if (MailLook.top) Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 72.dp) else Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp)) {
                IslandBottomBar(expand = { listDrawer = true }, visible = !listDrawer && !filterOpen) {
                    if (selected.isNotEmpty()) {
                        MailIslandItem(R.drawable.ic_archive, stringResource(R.string.mail_do_archive)) { act("archive", selected) }
                        MailIslandItem(R.drawable.ic_delete, stringResource(R.string.common_delete)) { act("delete", selected) }
                        val allRead = mails.filter { it.id in selected }.all { it.seen }
                        MailIslandItem(R.drawable.ic_done_all, stringResource(if (allRead) R.string.mail_mark_unread else R.string.mail_mark_read)) { act(if (allRead) "unread" else "read", selected); selected = emptySet() }
                    } else {
                        MailIslandItem(R.drawable.ic_add, stringResource(R.string.common_new)) { writing = MailDraft("new") }
                        MailIslandItem(R.drawable.ic_sync, stringResource(R.string.mail_sync)) {
                            Thread { MailEngine.syncAll(context.applicationContext, root) }.start() // this device's own accounts, at their servers
                            val paired = SyncStore(context).pairing() != null // with a PC, its mail too; alone, only the servers
                            if (paired) runCatching { androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, SyncService::class.java).setAction(SyncService.ACTION_START)) }
                                .onFailure { WriterLog.e("Mail: sync", it) }
                            message = context.getString(if (paired) R.string.mail_syncing else R.string.mail_checking_servers)
                        }
                        MailIslandItem(R.drawable.ic_filter, stringResource(R.string.mail_filter), on = view != MailView()) { filterOpen = true }
                        MailIslandItem(R.drawable.ic_archive, stringResource(R.string.mail_do_archive), enabled = open != null) { open?.let { act("archive", listOf(it.id)) } }
                    }
                }
            }
        }
    }

    // ---- the open mail, or on a tablet what is chosen ----
    val page: @Composable (Modifier) -> Unit = { m ->
        Box(m) {
            val many = mails.filter { it.id in selected }
            if (wide && many.size > 1) SelectionPane(many, accountOf, Modifier.statusBarsPadding().padding(16.dp), later)
            else if (current.size > 1 && single == null) MailThreadPage(current, accounts.map { it.address.lowercase() }.toSet(), accountOf, root, MailLook.top, showBack = !three, back = { openId = null }, openFile = openFile) { e -> if (e.folder == "Drafts") writing = MailDraft("draft", e, current) else single = e.id }
            else if (single != null && current.any { it.id == single }) MailPage(current.first { it.id == single }, accountOf[current.first { it.id == single }.account], root, islandTop = MailLook.top, showBack = true,
                back = { single = null }, openFile = openFile, say = { message = it }, fetched = { version++ }, write = { writing = it }, exported = { exported = it }, stored = stored)
            else if (open != null) MailPage(open, accountOf[open.account], root, islandTop = MailLook.top, showBack = !three, back = { openId = null }, openFile = openFile, say = { message = it }, fetched = { version++ }, write = { writing = it }, exported = { exported = it }, stored = stored)
            else if (wide) MailHint(stringResource(R.string.mail_choose), Modifier.align(Alignment.Center))
            // the mail's island: one big Απάντηση; the rest pulled up from it
            if (open != null && !(wide && many.size > 1)) Box(if (MailLook.top) Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 8.dp, end = 16.dp) else Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp)) {
                IslandBottomBar(expand = { mailDrawer = true }, visible = !mailDrawer) {
                    Surface(color = MAIL_ACCENT, contentColor = Color.White, shape = CircleShape, modifier = Modifier.clickable { writing = MailDraft("reply", current.firstOrNull { it.id == single } ?: newest, current) }) {
                        Row(Modifier.padding(horizontal = 26.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_reply), null); Text(stringResource(R.string.mail_reply), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) { MailLook.load(context) }
    Surface(Modifier.fillMaxSize().noticePointer(), color = MaterialTheme.colorScheme.background) { // a mouse or a finger: where "auto" puts the islands
        Box(Modifier.fillMaxSize()) {
            val compose: @Composable (Modifier) -> Unit = { m -> Box(m) { MailCompose(writing!!, accounts, root) { said -> writing = null; said?.let { message = it } } } }
            when {
                writing != null && three -> Row(Modifier.fillMaxSize()) { folders(Modifier.width(270.dp).fillMaxHeight()); list(Modifier.width(400.dp).fillMaxHeight()); compose(Modifier.weight(1f).fillMaxHeight()) }
                writing != null -> compose(Modifier.fillMaxSize())
                three -> Row(Modifier.fillMaxSize()) { folders(Modifier.width(270.dp).fillMaxHeight()); list(Modifier.width(400.dp).fillMaxHeight()); page(Modifier.weight(1f).fillMaxHeight()) }
                wide -> Row(Modifier.fillMaxSize()) { folders(Modifier.width(260.dp).fillMaxHeight()); if (open != null || selected.size > 1) page(Modifier.weight(1f).fillMaxHeight()) else list(Modifier.weight(1f).fillMaxHeight()) }
                open != null -> page(Modifier.fillMaxSize())
                mailboxes -> folders(Modifier.fillMaxSize())
                else -> list(Modifier.fillMaxSize())
            }
            exported?.let { f ->
                Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 100.dp, start = 16.dp, end = 16.dp)) {
                    Row(Modifier.padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.mail_exported, f.name), Modifier.weight(1f, fill = false), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { exported = null; openFolder("Documents") }) { Text(stringResource(R.string.mail_show), color = MAIL_TEXT) }
                    }
                }
            }
            message?.let { t ->
                Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 100.dp, start = 24.dp, end = 24.dp)) { Text(t, Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) }
            }
        }
    }

    if (storage) MailStorageDialog(null, openFolder) { storage = false }
    // a mail to write (a mailto:, a share) and no account at all: said plainly; one added here, or the PC's (⇄, sent through the outbox), and it goes on
    if (writing != null && data != null && accounts.isEmpty() && !adding) AlertDialog(onDismissRequest = { writing = null },
        title = { Text(stringResource(R.string.mail_no_sender)) }, text = { Text(stringResource(R.string.mail_no_sender_how)) },
        confirmButton = { TextButton(onClick = { adding = true }) { Text(stringResource(R.string.mail_add_account)) } },
        dismissButton = { TextButton(onClick = { writing = null }) { Text(stringResource(R.string.common_cancel)) } })
    if (filterOpen) FilterSheet(view, accounts, keep, set = { v -> view = v; if (keep) MailPrefs.setView(context, folderKey, v) },
        setKeep = { k -> keep = k; MailPrefs.setView(context, folderKey, if (k) view else null) }) { filterOpen = false }
    if (listDrawer) ModalBottomSheet(onDismissRequest = { listDrawer = false }, containerColor = SheetColor, contentColor = islandContentColor()) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (selected.isNotEmpty()) {
                val allFav = mails.filter { it.id in selected }.all { it.flagged }
                ChoiceGroup {
                DrawerRow(R.drawable.ic_favorite, stringResource(if (allFav) R.string.mail_from_favourites else R.string.mail_to_favourites), true) { listDrawer = false; act(if (allFav) "unfavorite" else "favorite", selected) }
                DrawerRow(R.drawable.ic_move, stringResource(R.string.mail_move), true) { listDrawer = false; moving = selected }
                DrawerRow(R.drawable.ic_report, stringResource(if (role == "Spam") R.string.mail_not_spam else R.string.mail_spam), true) { listDrawer = false; act(spamOp, selected) }
                DrawerRow(R.drawable.ic_label, stringResource(R.string.mail_tag), false, later)
                DrawerRow(R.drawable.ic_arrow_forward, stringResource(R.string.mail_forward_attached), false, writeLater)
                DrawerRow(R.drawable.ic_file_download, stringResource(R.string.mail_fetch_local), false, later)
                DrawerRow(R.drawable.ic_auto_fix, stringResource(R.string.mail_summarise), false, later)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // on the open mail
                    DrawerButton(R.drawable.ic_report, stringResource(if (role == "Spam") R.string.mail_not_spam else R.string.mail_spam_one), false, Modifier.weight(1f).alpha(if (open != null) 1f else 0.45f)) { open?.let { listDrawer = false; act(spamOp, listOf(it.id)) } }
                    DrawerButton(R.drawable.ic_move, stringResource(R.string.mail_move), false, Modifier.weight(1f).alpha(if (open != null) 1f else 0.45f)) { open?.let { listDrawer = false; moving = listOf(it.id) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DrawerButton(R.drawable.ic_label, stringResource(R.string.mail_tag), false, Modifier.weight(1f).alpha(0.45f)) { later() }
                    DrawerButton(R.drawable.ic_delete, stringResource(R.string.mail_clean_now), false, Modifier.weight(1f).alpha(0.45f)) { later() }
                }
                DrawerButton(R.drawable.ic_filter, stringResource(R.string.mail_rules), false, Modifier.fillMaxWidth().alpha(0.45f)) { later() }
                HorizontalDivider(color = islandContentColor().copy(alpha = 0.15f))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DrawerButton(R.drawable.ic_person, stringResource(R.string.mail_accounts), false, Modifier.weight(1f)) { listDrawer = false; openSettings() }
                    DrawerButton(R.drawable.ic_folder, stringResource(R.string.mail_storage), false, Modifier.weight(1f)) { listDrawer = false; storage = true }
                }
                DrawerButton(R.drawable.ic_tune, stringResource(R.string.common_settings), false, Modifier.fillMaxWidth()) { listDrawer = false; openSettings() }
            }
            Text(stringResource(R.string.mail_read_only_note), style = MaterialTheme.typography.bodySmall, color = islandContentColor().copy(alpha = 0.7f))
        }
    }
    if (mailDrawer && open != null) ModalBottomSheet(onDismissRequest = { mailDrawer = false }, containerColor = SheetColor, contentColor = islandContentColor()) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ChoiceGroup {
            DrawerRow(R.drawable.ic_reply_all, stringResource(R.string.mail_reply_all), true) { mailDrawer = false; writing = MailDraft("replyAll", current.firstOrNull { it.id == single } ?: newest, current) }
            DrawerRow(R.drawable.ic_arrow_forward, stringResource(R.string.mail_forward), true) { mailDrawer = false; writing = MailDraft("forward", newest) }
            DrawerRow(R.drawable.ic_archive, stringResource(R.string.mail_do_archive), true) { mailDrawer = false; act("archive", current.map { it.id }) }
            DrawerRow(R.drawable.ic_delete, stringResource(R.string.common_delete), true) { mailDrawer = false; act("delete", current.map { it.id }) }
            DrawerRow(R.drawable.ic_report, stringResource(if (role == "Spam") R.string.mail_not_spam else R.string.mail_spam_one), true) { mailDrawer = false; act(spamOp, current.map { it.id }) }
            DrawerRow(R.drawable.ic_move, stringResource(R.string.mail_move), true) { mailDrawer = false; moving = current.map { it.id } }
            DrawerRow(R.drawable.ic_done_all, stringResource(R.string.mail_mark_unread), true) { mailDrawer = false; act("unread", current.map { it.id }); openId = null }
            DrawerRow(R.drawable.ic_label, stringResource(R.string.mail_tag), false, later)
            DrawerRow(R.drawable.ic_print, stringResource(R.string.mail_print), false, later)
            // the whole mail as a .eml in Tetra/Documents (synced to the PC's)
            DrawerRow(R.drawable.ic_file_download, stringResource(R.string.mail_export_message), MailFiles.eml(root, open) != null) {
                mailDrawer = false
                runCatching { MailFiles.eml(root, open)!!.let { f -> MailExport.toDocuments(f, documentsDir(), MailExport.emlName(open, f)) } }
                    .onSuccess { exported = it }.onFailure { message = it.message; WriterLog.e("Mail: exporting a mail", it) }
            }
            DrawerRow(R.drawable.ic_code, stringResource(R.string.mail_source), MailFiles.eml(root, open) != null) {
                mailDrawer = false
                source = runCatching { MailFiles.eml(root, open)!!.readText(Charsets.ISO_8859_1).take(200_000) }.getOrNull()
            }
            }
            Text(stringResource(R.string.mail_read_only_note), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = islandContentColor().copy(alpha = 0.7f))
        }
    }
    failedSends.filter { it.third !in failedSeen }.takeIf { it.isNotEmpty() }?.let { list ->
        AlertDialog(onDismissRequest = {}, title = { Text(stringResource(R.string.mail_outbox_failed)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { list.forEach { (subject, why, _) -> Text("«${subject.ifEmpty { "—" }}»: $why") } } },
            confirmButton = { TextButton(onClick = { list.forEach { it.third.delete() }; failedSeen = failedSeen + list.map { it.third } }) { Text(stringResource(R.string.common_ok)) } })
    }
    if (adding) MailAddAccount { adding = false; version++ }
    moving?.let { ids ->
        AlertDialog(onDismissRequest = { moving = null }, title = { Text(stringResource(R.string.mail_move_to)) },
            text = { Column { MailFiles.ROLES.filter { it != role }.forEach { r ->
                Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { moving = null; act("move", ids, r) }.padding(vertical = 12.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(ROLE_ICONS.getValue(r)), null); Text(stringResource(ROLE_NAMES.getValue(r)))
                }
            } } },
            confirmButton = {}, dismissButton = { TextButton(onClick = { moving = null }) { Text(stringResource(R.string.common_cancel)) } })
    }
    source?.let { s ->
        AlertDialog(onDismissRequest = { source = null }, title = { Text(stringResource(R.string.mail_source)) },
            text = { SelectionContainer { Text(s, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)) } },
            confirmButton = { TextButton(onClick = { source = null }) { Text(stringResource(R.string.common_ok)) } })
    }
}

/** No account yet: what Mail is, and the button to add one here (with a PC, its accounts also come by themselves). */
@Composable private fun NoAccounts(add: () -> Unit) = Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(stringResource(R.string.mail_no_accounts), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
    Surface(color = MAIL_ACCENT, contentColor = Color.White, shape = CircleShape, modifier = Modifier.clickable(onClick = add)) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_add), null); Text(stringResource(R.string.mail_add_account), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable private fun MailHint(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)

/** A folder's unread count: green when every mail in it is saved here, orange while any is only on the server. */
@Composable private fun CountBadge(unread: Int, orange: Int) {
    val color = if (orange > 0) ORANGE else GREEN
    if (unread > 0) Surface(color = color, contentColor = Color.Black, shape = CircleShape) {
        Text("$unread", Modifier.padding(horizontal = 8.dp, vertical = 1.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    } else if (orange > 0) Box(Modifier.size(9.dp).clip(CircleShape).background(ORANGE))
}

/** When the PC last answered «look now» (MailEngine.askPc): ⇄ PC is green for 5 minutes after (the user, 10 Nov: "so I know what is happening"). */
internal val mailPcSeen = androidx.compose.runtime.mutableLongStateOf(0L)

@Composable private fun PcBadge() {
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    val active = maxOf(now, System.currentTimeMillis()) - mailPcSeen.longValue < 5 * 60_000 // reading now: grey again without another sync
    val tint = if (active) GREEN else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = if (active) GREEN.copy(alpha = 0.15f) else Color.Transparent, contentColor = tint, shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, tint.copy(alpha = if (active) 0.9f else 0.5f))) {
        Text("⇄ PC", Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable private fun Legend(color: Color, text: String, ring: Boolean = false) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
    StateDot(color, ring); Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun folderName(role: String, account: String?, accounts: Map<String, MailAccount>) =
    stringResource(ROLE_NAMES.getValue(role)) + (account?.let { " · " + (accounts[it]?.name ?: it) } ?: "")

@Composable private fun groupName(group: String, key: String, accounts: Map<String, MailAccount>): String = when (group) {
    "day" -> LocalDate.parse(key).let { d -> when (d) {
        LocalDate.now() -> stringResource(R.string.mail_today)
        LocalDate.now().minusDays(1) -> stringResource(R.string.mail_yesterday)
        else -> d.format(DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.getDefault()))
    } }
    "account" -> accounts[key]?.name ?: key
    else -> key
}

/** A row's time: today the hour, this week the day, before that the date. */
private fun shortTime(ms: Long): String {
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val days = java.time.temporal.ChronoUnit.DAYS.between(t.toLocalDate(), LocalDate.now())
    return t.format(DateTimeFormatter.ofPattern(if (days == 0L) "HH:mm" else if (days < 7) "EEE" else "d MMM", java.util.Locale.getDefault()))
}

/** What went wrong with a server, in one plain sentence (MAIL_PLAN §9b: never a raw error). */
internal fun mailWhy(c: android.content.Context, e: Throwable): String = c.getString(when (mailReason(e)) {
    "auth" -> R.string.mail_why_auth; "appPassword" -> R.string.mail_why_app_password; "unreachable" -> R.string.mail_why_unreachable
    "approval" -> R.string.mail_why_approval; "noClient" -> R.string.mail_ms_client_hint; else -> R.string.mail_why_other
})

/** A mail's state: green saved here, a green ring saved on the PC (this device keeps it there), orange only on the server. */
@Composable private fun StateDot(color: Color, ring: Boolean = false) =
    Box(Modifier.size(8.dp).clip(CircleShape).then(if (ring) Modifier.border(1.5.dp, color, CircleShape) else Modifier.background(color)))

/** An unread mail's urgency by the computer's mail AI (MailAi): 1 worth a look, 2 important, 3 urgent. */
private val URGENCY = listOf(Color(0xFF4BA3D3), Color(0xFFD9668A), Color(0xFFE5484D))
private val URGENCY_NAMES = listOf(R.string.mail_ai_urgency_1, R.string.mail_ai_urgency_2, R.string.mail_ai_urgency_3)
@Composable private fun UrgencyDot(score: Int) {
    val name = stringResource(URGENCY_NAMES[score - 1])
    Box(Modifier.size(10.dp).clip(CircleShape).background(URGENCY[score - 1]).semantics { contentDescription = name })
}

/** One mail in the list (MAIL_PLAN §9): the account's colour as a bar, ♥ at the right, its state's dot. */
@Composable private fun MailRow(e: MailEntry, account: MailAccount?, count: Int = 1, picked: Boolean, choosing: Boolean, chosen: Boolean, click: () -> Unit, long: () -> Unit, here: Boolean, heart: () -> Unit,
    stored: Boolean = false, urgency: Int = 0) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
        .background(if (picked) MAIL_COLOR.copy(alpha = 0.32f) else if (chosen) MAIL_COLOR.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
        .combinedClickable(onLongClick = long, onClick = click).heightIn(min = 72.dp)) {
        Box(Modifier.width(4.dp).fillMaxHeight().heightIn(min = 72.dp).background(Color(account?.color ?: 0xFF9E9E9E)))
        Column(Modifier.weight(1f).padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (choosing) Box(Modifier.size(20.dp).clip(CircleShape).background(if (picked) MAIL_ACCENT else Color.Transparent)
                    .then(if (picked) Modifier else Modifier.background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f), CircleShape)), contentAlignment = Alignment.Center) {
                    if (picked) Icon(painterResource(R.drawable.ic_check), stringResource(R.string.contacts_selected), Modifier.size(14.dp), tint = Color.White)
                }
                if (!e.seen && urgency > 0) UrgencyDot(urgency)
                Text(e.from.ifEmpty { e.fromAddress } + if (count > 1) "  ($count)" else "", Modifier.weight(1f), // (3): a conversation of three
                    fontWeight = if (e.seen) null else FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(shortTime(e.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (e.attachments > 0) Icon(painterResource(R.drawable.ic_attach), stringResource(R.string.mail_has_attachments), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                StateDot(if (e.saved) GREEN else ORANGE, ring = e.saved && !here)
            }
            if (stored || e.gone) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (stored) StoredTag()
                if (e.gone) Text(stringResource(R.string.mail_local_only), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) // no longer on the server
            }
            Text(e.subject.ifEmpty { stringResource(R.string.mail_no_subject) }, fontWeight = if (e.seen) null else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (e.snippet.isNotEmpty()) Text(e.snippet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = heart, modifier = Modifier.align(Alignment.CenterVertically)) {
            Icon(painterResource(if (e.flagged) R.drawable.ic_favorite else R.drawable.ic_favorite_border), stringResource(R.string.mail_favourite),
                tint = if (e.flagged) Color(0xFFE5534B) else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

/** An island's action with its word under it (the user, 31 Oct: "the buttons are small, add words"); four fit a phone and the tablet's list. */
@Composable private fun MailIslandItem(icon: Int, label: String, enabled: Boolean = true, on: Boolean = false, click: () -> Unit) = Surface(
    color = if (on) driveNavigationSelectedColor() else Color.Transparent, contentColor = if (on) driveNavigationSelectedContentColor() else islandContentColor(),
    shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.heightIn(min = 48.dp).alpha(if (enabled) 1f else 0.45f).clickable(onClick = click),
) {
    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(icon), null, Modifier.size(22.dp)); Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** A panel's choices on a ground of their own, one rounded block, so where they end and the words under them begin shows (as Settings' sections). */
@Composable internal fun ChoiceGroup(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)

@Composable internal fun DrawerRow(icon: Int, label: String, enabled: Boolean, click: () -> Unit) =
    Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.08f)).clickable(onClick = click).alpha(if (enabled) 1f else 0.45f).padding(vertical = 14.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(22.dp)); Text(label, style = MaterialTheme.typography.bodyLarge)
    }

/** A tablet's right pane while several are chosen: what they are, and what the assistant could do with them (MAIL_PLAN §9). */
@Composable private fun SelectionPane(many: List<MailEntry>, accounts: Map<String, MailAccount>, modifier: Modifier, later: () -> Unit) {
    val context = LocalContext.current
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(pluralStringResource(R.plurals.mail_n_messages, many.size, many.size), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        many.forEach { e ->
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = MaterialTheme.shapes.medium) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(accounts[e.account]?.color ?: 0xFF9E9E9E)))
                    Text(e.subject, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(android.text.format.Formatter.formatShortFileSize(context, e.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (e.saved) GREEN else ORANGE))
                }
            }
        }
        Text(stringResource(R.string.mail_selection_summary, pluralStringResource(R.plurals.mail_n_accounts, many.map { it.account }.distinct().size, many.map { it.account }.distinct().size),
            android.text.format.Formatter.formatShortFileSize(context, many.sumOf { it.size }), many.count { !it.saved }), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = CircleShape, modifier = Modifier.alpha(0.6f).clickable(onClick = later)) { // magenta is «Αποθήκη»'s
            Text(stringResource(R.string.mail_summarise_n, many.size), Modifier.padding(horizontal = 16.dp, vertical = 10.dp), fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * The open mail: sender and subject on top, its attachments as cards (opened in Files, Office or the PDF viewer), and its
 * text. Pictures and anything else from the web load only when allowed (MAIL_PLAN §9c): once, always from this sender,
 * or always (Settings).
 */
@Composable private fun MailPage(e: MailEntry, account: MailAccount?, root: File, islandTop: Boolean, showBack: Boolean, back: () -> Unit, openFile: (File) -> Unit, say: (String) -> Unit, fetched: () -> Unit,
    write: (MailDraft) -> Unit = {}, exported: (File) -> Unit = {}, stored: Boolean = false) {
    val context = LocalContext.current
    var failed by remember(e.id) { mutableStateOf<String?>(null) }
    var got by remember(e.id) { mutableIntStateOf(0) } // an attachment fetched: the cards again
    val urgent by produceState<MailAi.Urgency?>(null, e.id) { value = withContext(Dispatchers.IO) { MailAi.urgency(root)[e.id] } }
    /** A Mail file this device does not keep, from the computer (MAIL_PLAN §2: older mail on demand). */
    fun fetch(rel: String): File = SyncClient(context.applicationContext, SyncStore(context.applicationContext)).mailFile(rel)
    val message by produceState<Mime.Message?>(null, e.id, e.file) {
        value = withContext(Dispatchers.IO) {
            // not here: from the server when this device fetches the account itself, else from the PC
            val f = MailFiles.eml(root, e) ?: if (account?.own == true) runCatching { MailEngine.body(context.applicationContext, root, e)?.file?.let { File(root, it) } }
                .onSuccess { fetched() }.onFailure { failed = mailWhy(context, it); WriterLog.w("Mail: from the server (${it.message})") }.getOrNull()
            else e.file?.takeIf { e.saved }?.let { rel ->
                runCatching { fetch(MailSync.PART + rel) }.onSuccess { fetched() }.onFailure { failed = it.shownMessage(context) ?: it.message; WriterLog.w("Mail: fetching a mail (${it.message})") }.getOrNull()
            }
            f?.setLastModified(System.currentTimeMillis()) // opened: kept here at least another week (MailSync.drop)
            f?.let { runCatching { Mime.read(it.readBytes()) }.onFailure { WriterLog.e("Mail: reading a mail", it) }.getOrNull() }
        }
    }
    // the attachments here, and those the mail names that this device left on the PC (fetched when tapped)
    val files = remember(e.id, e.file, message, got) {
        val here = MailFiles.attachments(root, e)
        here.map { it.name to it } + message?.attachments.orEmpty().filter { n -> here.none { it.name == n } }.map { it to null }
    }
    fun open(name: String, f: File?) {
        if (f != null) return openFile(f)
        say(context.getString(R.string.mail_fetching))
        Thread {
            val r = runCatching { fetch(MailSync.PART + e.file!!.removeSuffix(".eml") + ".files/" + name) }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                r.onSuccess { got++; openFile(it) }.onFailure { say(context.getString(R.string.mail_fetch_failed, it.shownMessage(context) ?: it.message.orEmpty())) }
            }
        }.start()
    }
    var once by remember(e.id) { mutableStateOf(false) }
    var allowedVersion by remember { mutableIntStateOf(0) }
    val sender = e.fromAddress.lowercase()
    val allowed = remember(allowedVersion, sender) { MailPrefs.remote(context) || sender in MailPrefs.allowed(context) || sender.substringAfter('@') in MailPrefs.allowed(context) }
    var risky by remember { mutableStateOf<Pair<String, File?>?>(null) }
    // «Αποθήκευση ως…»: Android's own picker says where; the attachment's bytes go there
    var savingAs by remember { mutableStateOf<File?>(null) }
    val saveAs = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val file = savingAs; savingAs = null
        if (uri != null && file != null) runCatching { context.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } } }
            .onSuccess { say(context.getString(R.string.mail_saved)) }.onFailure { say(it.message.orEmpty()) }
    }
    val html = message?.let { it.html ?: it.text?.let(Mime::textAsHtml) }
    val blocked = html != null && !(once || allowed) && Mime.remote(html)
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = if (islandTop) 84.dp else 12.dp)) { // under an island at the top
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (showBack) Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_back)) }
            }
            Text(e.subject.ifEmpty { stringResource(R.string.mail_no_subject) }, Modifier.weight(1f).padding(top = 6.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color(account?.color ?: 0xFF9E9E9E)), contentAlignment = Alignment.Center) {
                Text(e.from.ifEmpty { e.fromAddress }.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text(e.from.ifEmpty { e.fromAddress }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val parts = listOfNotNull(e.fromAddress.takeIf { MailPrefs.switch(context, "realAddress") && it.isNotEmpty() },
                    message?.to?.ifBlank { null }?.let { stringResource(R.string.mail_to, it) },
                    Instant.ofEpochMilli(e.date).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", java.util.Locale.getDefault())))
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                message?.cc?.takeIf { it.isNotBlank() }?.let { Text(stringResource(R.string.mail_cc, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (!e.saved) Text("● " + stringResource(R.string.mail_server_only), style = MaterialTheme.typography.bodySmall, color = ORANGE)
                // why the computer's mail AI thinks it matters (MailAi)
                urgent?.takeIf { it.score > 0 && it.why.isNotEmpty() }?.let { u -> Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    UrgencyDot(u.score)
                    Text(stringResource(URGENCY_NAMES[u.score - 1]) + " · " + u.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                if (stored || e.gone) Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (stored) StoredTag()
                    if (e.gone) Text(stringResource(R.string.mail_local_only), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (files.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            files.forEach { (name, f) ->
                var menu by remember { mutableStateOf(false) }
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.clickable { if (Mime.risky(name) && MailPrefs.switch(context, "risky")) risky = name to f else open(name, f) }) {
                    Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_attach), null, Modifier.size(16.dp))
                        Text(if (f != null) "$name · ${android.text.format.Formatter.formatShortFileSize(context, f.length())}" else "$name · ⇄ PC", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        Box {
                            IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.common_more), Modifier.size(18.dp)) }
                            androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
                                // each on the file here; one left on the PC is fetched first
                                fun withFile(then: (File) -> Unit) { menu = false; if (f != null) then(f) else Thread {
                                    val r = runCatching { fetch(MailSync.PART + e.file!!.removeSuffix(".eml") + ".files/" + name) }
                                    android.os.Handler(android.os.Looper.getMainLooper()).post { r.onSuccess { got++; then(it) }.onFailure { say(context.getString(R.string.mail_fetch_failed, it.message.orEmpty())) } }
                                }.start() }
                                androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.mail_export_documents)) }, leadingIcon = { Icon(painterResource(R.drawable.ic_file_download), null) }, onClick = {
                                    withFile { file -> runCatching { MailExport.toDocuments(file, documentsDir()) }.onSuccess(exported).onFailure { say(it.message.orEmpty()) } } })
                                androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.mail_save_as)) }, leadingIcon = { Icon(painterResource(R.drawable.ic_save), null) }, onClick = {
                                    withFile { file -> savingAs = file; saveAs.launch(file.name) } })
                                androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.common_share)) }, leadingIcon = { Icon(painterResource(R.drawable.ic_share), null) }, onClick = {
                                    withFile { file -> runCatching { shareFile(context, file) }.onFailure { say(it.message.orEmpty()) } } })
                            }
                        }
                    }
                }
            }
        }
        if (blocked) Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.padding(bottom = 10.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🛡 " + stringResource(R.string.mail_images_blocked), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { once = true }) { Text(stringResource(R.string.mail_show_images), color = MAIL_TEXT) }
                TextButton(onClick = { MailPrefs.allow(context, sender); allowedVersion++ }) { Text(stringResource(R.string.mail_always_sender), color = MAIL_TEXT) }
            }
        }
        when {
            failed != null || (account?.own != true && (e.file == null || !e.saved)) -> {
                MailHint(failed?.let { stringResource(R.string.mail_fetch_failed, it) } ?: stringResource(R.string.mail_not_here))
                if (e.snippet.isNotEmpty()) Text(e.snippet, Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // its first lines at once while the rest comes from the computer (the user, 2 Nov: «load the text as soon as available»)
            message == null -> {
                MailHint(stringResource(if (MailFiles.eml(root, e) == null) R.string.mail_fetching else R.string.mail_reading))
                if (e.snippet.isNotEmpty()) Text(e.snippet, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodyLarge)
            }
            message!!.html == null -> SelectionContainer(Modifier.weight(1f)) {
                Text(message!!.text.orEmpty(), Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 110.dp), style = MaterialTheme.typography.bodyLarge)
            }
            else -> MailHtml(html!!.let { if (!blocked && MailPrefs.switch(context, "tracking")) Mime.withoutPixels(it) else it }, load = !blocked,
                Modifier.weight(1f).fillMaxWidth().padding(bottom = 96.dp).clip(MaterialTheme.shapes.large), write)
        }
    }
    risky?.let { (name, f) ->
        AlertDialog(onDismissRequest = { risky = null }, title = { Text(stringResource(R.string.mail_risky_title)) }, text = { Text(stringResource(R.string.mail_risky_text, name)) },
            confirmButton = { TextButton(onClick = { risky = null; open(name, f) }) { Text(stringResource(R.string.mail_open_anyway)) } },
            dismissButton = { TextButton(onClick = { risky = null }) { Text(stringResource(R.string.common_cancel)) } })
    }
}

/**
 * A conversation (MailLook.threads): its mails one under another, oldest first, each a card with its header — this
 * device's accounts' own at the right, the others' at the left, in the account's colour — and only what it says itself.
 */
@Composable private fun MailThreadPage(thread: List<MailEntry>, mine: Set<String>, accounts: Map<String, MailAccount>, root: File, islandTop: Boolean, showBack: Boolean,
    back: () -> Unit, openFile: (File) -> Unit, openMail: (MailEntry) -> Unit) {
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(thread.size) { state.scrollToItem(thread.size) } // the newest in view, as a chat opens
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), state = state, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = if (islandTop) 84.dp else 12.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (showBack) Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                    IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_back)) }
                }
                Text(thread.last().subject.ifEmpty { stringResource(R.string.mail_no_subject) }, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
        items(thread, key = { it.id }) { e ->
            val me = e.fromAddress.lowercase() in mine
            val text by produceState<String?>(null, e.id, e.file) {
                value = withContext(Dispatchers.IO) { MailFiles.eml(root, e)?.let { f -> runCatching { Mime.read(f.readBytes()) }.getOrNull() }
                    ?.let { m -> MailThreads.own(m.text ?: MailSignatures.plain(m.html.orEmpty())) } ?: e.snippet }
            }
            val files = remember(e.id, e.file) { MailFiles.attachments(root, e) }
            val color = Color(accounts[e.account]?.color ?: 0xFF9E9E9E)
            // a mail's card, not a chat bubble (the user, 31 Oct): its header on top; this device's own at the right, the others' at
            // the left, each with its account's colour as a bar on its side
            val draft = e.folder == "Drafts" // greyed: tap to finish it and send (the user, 31 Oct)
            Box(Modifier.fillMaxWidth().alpha(if (draft) 0.55f else 1f), contentAlignment = if (me) Alignment.CenterEnd else Alignment.CenterStart) {
                Surface(color = if (me) color.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(0.92f).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).clickable { openMail(e) }) { // the whole mail
                    Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                        if (!me) Box(Modifier.width(4.dp).fillMaxHeight().background(color))
                        Column(Modifier.weight(1f).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (draft) Text(stringResource(R.string.mail_draft_card), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(e.subject.ifEmpty { stringResource(R.string.mail_no_subject) }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                                Text(e.from.ifEmpty { e.fromAddress }, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(listOfNotNull(e.fromAddress.ifEmpty { null }, e.to.joinToString(", ").ifEmpty { null }?.let { stringResource(R.string.mail_to, it) },
                                Instant.ofEpochMilli(e.date).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", java.util.Locale.getDefault()))).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                            SelectionContainer { Text(text ?: "…", style = MaterialTheme.typography.bodyLarge) }
                            files.forEach { f ->
                                Row(Modifier.clip(MaterialTheme.shapes.small).clickable { openFile(f) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(painterResource(R.drawable.ic_attach), null, Modifier.size(14.dp)); Text(" " + f.name, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        if (me) Box(Modifier.width(4.dp).fillMaxHeight().background(color))
                    }
                }
            }
        }
    }
}

/** A mail's HTML on its own white page, no scripts, nothing from the web unless [load]; a link opens in the browser. */
@Composable private fun MailHtml(html: String, load: Boolean, modifier: Modifier, write: (MailDraft) -> Unit = {}) {
    AndroidView(factory = { c -> android.webkit.WebView(c).apply {
        settings.javaScriptEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        setBackgroundColor(android.graphics.Color.WHITE)
        webViewClient = object : android.webkit.WebViewClient() {
            // shortcut: no check that a link's text and its address agree (Settings' warning) yet; step 3 with the rest of the safety bar
            override fun shouldOverrideUrlLoading(view: android.webkit.WebView, request: android.webkit.WebResourceRequest): Boolean {
                // a mailto: link writes here, not through Android's chooser
                if (request.url.scheme.equals("mailto", true)) { write(MailTo.draft(request.url.toString())); return true }
                runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                return true
            }
        }
    } }, update = { w ->
        w.settings.blockNetworkLoads = !load
        w.settings.blockNetworkImage = !load
        val page = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><style>body{margin:12px;word-wrap:break-word}img{max-width:100%;height:auto}*{max-width:100%!important;box-sizing:border-box}</style>$html"
        if (w.tag != page) { w.tag = page; w.loadDataWithBaseURL(null, page, "text/html", "utf-8", null) }
    }, onRelease = { it.destroy() }, modifier = modifier)
}

/** The Filter modal (MAIL_PLAN §9): quick filters on top, sorting and grouping below, kept for this folder when asked. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable private fun FilterSheet(v: MailView, accounts: List<MailAccount>, keep: Boolean, set: (MailView) -> Unit, setKeep: (Boolean) -> Unit, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = SheetColor, contentColor = islandContentColor()) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_filter), null); Text("  " + stringResource(R.string.mail_filter), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (v != MailView()) TextButton(onClick = { set(MailView()) }) { Text(stringResource(R.string.common_clear), color = MAIL_TEXT) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(stringResource(R.string.mail_unread), v.unread) { set(v.copy(unread = !v.unread)) }
                FilterChip("♥ " + stringResource(R.string.mail_favourites), v.favorites) { set(v.copy(favorites = !v.favorites)) }
                FilterChip("📎 " + stringResource(R.string.mail_attachments), v.attachments) { set(v.copy(attachments = !v.attachments)) }
                FilterChip("● " + stringResource(R.string.mail_server_only), v.orange) { set(v.copy(orange = !v.orange)) }
                accounts.forEach { a -> FilterChip(a.name, a.id in v.accounts) { set(v.copy(accounts = if (a.id in v.accounts) v.accounts - a.id else v.accounts + a.id)) } }
            }
            Text(stringResource(R.string.mail_sort), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("date" to R.string.mail_sort_date, "from" to R.string.mail_sort_from, "subject" to R.string.mail_sort_subject, "size" to R.string.mail_sort_size, "account" to R.string.mail_sort_account)
                    .forEach { (k, s) -> FilterChip(stringResource(s), v.sort == k) { set(v.copy(sort = k)) } }
                FilterChip(stringResource(if (v.descending) R.string.mail_descending else R.string.mail_ascending), true) { set(v.copy(descending = !v.descending)) }
            }
            Text(stringResource(R.string.mail_group), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("day" to R.string.mail_group_day, "from" to R.string.mail_sort_from, "account" to R.string.mail_sort_account, "none" to R.string.mail_group_none)
                    .forEach { (k, s) -> FilterChip(stringResource(s), v.group == k) { set(v.copy(group = k)) } }
            }
            ChoiceGroup {
                SheetSwitch(stringResource(R.string.mail_unread_first), v.unreadFirst) { set(v.copy(unreadFirst = it)) }
                SheetSwitch(stringResource(R.string.mail_favourites_first), v.favoritesFirst) { set(v.copy(favoritesFirst = it)) }
                SheetSwitch(stringResource(R.string.mail_attachments_first), v.attachmentsFirst) { set(v.copy(attachmentsFirst = it)) }
                SheetSwitch(stringResource(R.string.mail_keep_for_folder), keep, setKeep)
            }
        }
    }
}

@Composable private fun FilterChip(text: String, on: Boolean, click: () -> Unit) = Surface(
    color = if (on) MAIL_ACCENT else Color.White.copy(alpha = 0.10f), contentColor = islandContentColor(), shape = CircleShape, modifier = Modifier.clickable(onClick = click),
) { Text(text, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1) }

@Composable private fun SheetSwitch(text: String, on: Boolean, set: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.08f)).clickable { set(!on) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f)); Switch(checked = on, onCheckedChange = set)
    }

/**
 * «Αποθήκη» (the user, 31 Oct: "how can I access my mails if the folder is hidden in the system folder?"): where each
 * account's mail is kept on this device, how much it takes and how many mails, and «Άνοιγμα φακέλου» in Tetra's Files
 * (which shows Mail's folder, though it is under .System). The desktop's Mail says the same in the same words. [only]: one account.
 */
@Composable internal fun MailStorageDialog(only: String?, openFolder: (String) -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    val root = remember { mailRoot() }
    val info by produceState<Pair<List<Triple<MailAccount, Long, Int>>, Pair<Long, Int>>?>(null) {
        value = withContext(Dispatchers.IO) {
            MailFiles.accounts(root, MailSecret.ids(context)).filter { only == null || it.id == only }
                .map { a -> MailFiles.held(root, a.id).let { (b, n) -> Triple(a, b, n) } } to MailFiles.held(root, null)
        }
    }
    val number = java.text.NumberFormat.getInstance()
    // as the desktop shows sizes, decimal: GB with one decimal from a gigabyte, else whole MB, else whole KB
    fun size(n: Long) = when {
        n >= 1_000_000_000 -> "${java.text.DecimalFormat("#,##0.#").format(n / 1e9)} GB"
        n >= 1_000_000 -> "${number.format(Math.round(n / 1e6))} MB"
        else -> "${number.format(Math.round(n / 1e3))} KB"
    }
    AlertDialog(onDismissRequest = dismiss, icon = { Icon(painterResource(R.drawable.ic_folder), contentDescription = null) }, title = { Text(stringResource(R.string.mail_storage)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val got = info
            if (got == null) androidx.compose.material3.CircularProgressIndicator()
            else {
                got.first.forEach { (a, bytes, mails) -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(a.color)))
                        Text(a.name, fontWeight = FontWeight.Bold)
                        Text(a.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(stringResource(R.string.mail_storage_has, size(bytes), number.format(mails)))
                    Text(File(root, a.id).path, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    androidx.compose.material3.OutlinedButton(onClick = { dismiss(); openFolder(MailFiles.FOLDER + "/" + a.id) }) {
                        Icon(painterResource(R.drawable.ic_folder), contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.mail_open_folder))
                    }
                } }
                if (only == null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.mail_storage_total, size(got.second.first), number.format(got.second.second)), Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    TextButton(onClick = { dismiss(); openFolder(MailFiles.FOLDER) }) { Text(stringResource(R.string.mail_open_folder)) }
                }
            }
        } },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_close)) } })
}

/** Tetra/Documents: where Mail's exports go (synced to the PC's ~/Tetra/Documents). */
internal fun documentsDir() = File(TetraFolder.root(Environment.getExternalStorageDirectory()), "Documents")

/** A file of Tetra's to another app (Android's share sheet). */
internal fun shareFile(context: android.content.Context, f: File) {
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f)
    val type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** The «Αποθήκη» tag: on every mail shown from the mail kept here, in Mail's magenta — that colour is for it alone. */
@Composable internal fun StoredTag() = Surface(color = MAIL_COLOR.copy(alpha = 0.18f), contentColor = MAIL_COLOR, shape = CircleShape) {
    Text(stringResource(R.string.mail_storage), Modifier.padding(horizontal = 8.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
}

/** A stored folder's count, as the desktop's "N · M": all of it, and how many are here only. */
@Composable private fun StoredCount(all: Int, only: Int) = Text(if (only > 0) "$all · $only" else "$all", style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant)

