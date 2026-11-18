package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class PhoneTab(val icon: Int, @androidx.annotation.StringRes val label: Int, @androidx.annotation.StringRes val search: Int) {
    Recents(R.drawable.ic_history, R.string.phone_recents, R.string.phone_search_recents), Favourites(R.drawable.ic_favorite, R.string.contacts_favourites, R.string.phone_search_favourites),
    Contacts(R.drawable.ic_contacts, R.string.app_contacts, R.string.phone_search_contacts), Recordings(R.drawable.ic_mic, R.string.contacts_recordings, R.string.phone_search_recordings)
}

/** The Phone's own pages, opened from its settings sheet (the user, 27 Oct: "its own page, not filling up the bottom modal"). */
private enum class PhonePage(@androidx.annotation.StringRes val title: Int) { Blocking(R.string.phone_blocking), SpeedDial(R.string.phone_speed_dial) }

/**
 * The Phone applet (Plan.md § Phone), Tetra's way (the user, 24 Oct: "an island for recents, favourites, contacts,
 * recordings, everything centred"): the list under a floating search pill, the island in the middle at the bottom, the
 * keypad a round button beside it. Also what Android opens for a number (ACTION_DIAL, tel: links): then the keypad.
 */
class PhoneActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AppLocale.wrap(base))
    override fun onResume() { super.onResume(); runCatching { Notices.seen(this, "phone") } } // its missed calls seen
    private val number = mutableStateOf("")
    private val keypad = mutableStateOf(false)
    private val tab = mutableStateOf(PhoneTab.Recents)
    private val missed = mutableIntStateOf(0) // asked to show the missed calls (a missed-call notification's tap)
    private val blocked = mutableStateOf<String?>(null) // asked to show a blocked number's card (its silent notification's tap)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WriterLog.init(this)
        TetraLook.load(this)
        if (PhoneLog.recent == null) PhoneLog.warm(this) // opened from the launcher, not from Tetra's Home: read ahead from here
        if (ContactsPreload.all == null) ContactsPreload.warm(this)
        take(intent)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = TetraLook.light
        setContent { TetraTheme { PhoneApp(keypad, tab, number, missed, blocked, back = { finish() }) } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); take(intent) }
    private fun take(i: Intent) {
        if (i.action == Intent.ACTION_DIAL || i.data?.scheme == "tel") { keypad.value = true; number.value = i.data?.schemeSpecificPart.orEmpty() }
        // the call log asked for (Android's own missed-call notification, or Tetra's): Recents › Missed
        val log = i.action == Intent.ACTION_VIEW && (i.type == CallLog.Calls.CONTENT_TYPE || i.data?.authority == CallLog.AUTHORITY)
        if (log || i.getStringExtra(TAB) == "missed") { keypad.value = false; tab.value = PhoneTab.Recents; missed.intValue++ }
        i.getStringExtra(BLOCKED)?.let { blocked.value = it }
        when (i.getStringExtra(TAB)) { "keypad" -> keypad.value = true; "recents" -> tab.value = PhoneTab.Recents; "contacts" -> tab.value = PhoneTab.Contacts }
    }
    companion object {
        const val TAB = "com.kalotrapezis.drive.phone.TAB" // Home's slots: keypad, recents, contacts
        const val BLOCKED = "com.kalotrapezis.drive.phone.BLOCKED"
    }
}

/** Opens Tetra's Contacts on a person, or at a new contact with this number. */
internal fun openInContacts(c: Context, uid: String? = null, newNumber: String? = null) = openApplet(c, if (uid != null) "contact:$uid" else "contact-new:${newNumber.orEmpty()}")

/** The Phone's own folder, Tetra/.System/Phone/Shared: the recordings made on the computer land in Recordings (Plan.md § Phone). */
internal fun phoneRoot() = java.io.File(TetraFolder.root(android.os.Environment.getExternalStorageDirectory()), TetraSystem.onDisk(PhoneHistory.FOLDER))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneApp(keypad: MutableState<Boolean>, tabState: MutableState<PhoneTab>, number: MutableState<String>, missed: State<Int>, blockedAsk: MutableState<String?>, back: () -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) { // back from a call or Android's settings, and every change to the call log: read again
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) version++ }
        val log = object : ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) { override fun onChange(self: Boolean) { version++ } }
        lifecycle.lifecycle.addObserver(o)
        runCatching { context.contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, log) }
        onDispose { lifecycle.lifecycle.removeObserver(o); context.contentResolver.unregisterContentObserver(log) }
    }
    var tab by tabState
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<CallGroup>()) }
    val tablet = remember { PhoneRelay.noCalls(context) } // no calls of its own: the phone's, through the computer (PhoneRelay)
    val dialer = remember(version) { tablet || PhonePrefs.isDialer(context) }
    val scope = rememberCoroutineScope()
    val askRole = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { version++ }
    val contacts by produceState(ContactsPreload.all?.map { it.info } ?: emptyList(), version) {
        value = withContext(Dispatchers.IO) { runCatching { ContactsPreload.load(context, ContactsPrefs.byLast(context)).map { it.info } }.getOrDefault(value) }
    }
    val calls by produceState(if (tablet) emptyList() else PhoneLog.recent.orEmpty(), version) {
        value = withContext(Dispatchers.IO) { if (tablet) PhoneRelay.calls() else PhoneLog.read(context).also { PhoneLog.recent = it } }
        if (!tablet) withContext(Dispatchers.IO) { PhoneHistory.write(context) } // after the list shows: it reads every call there is
    }
    OpenTiming("Calls", calls.isNotEmpty())
    val spam by produceState(PhoneSpam.read(), version) { value = withContext(Dispatchers.IO) { PhoneSpam.read() } }
    val byKey = remember(contacts) { contacts.flatMap { c -> c.phones.map { ContactsWays.key(it.value) to c } }.filter { it.first.length >= 5 }.toMap() }
    fun who(n: String) = byKey[ContactsWays.key(n)]
    LaunchedEffect(tab, dialer, calls.size) { if (tab == PhoneTab.Recents && dialer && !tablet) withContext(Dispatchers.IO) { PhoneLog.seen(context) } }
    var drawer by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<CallGroup?>(null) }
    var page by remember { mutableStateOf<PhonePage?>(null) }
    var blockedPage by remember { mutableStateOf<PhoneSpam.Entry?>(null) } // a blocked number's card, over Call blocking
    val blockingScroll = rememberScrollState() // Call blocking back where it was after a number's page
    LaunchedEffect(blockedAsk.value) {
        val n = blockedAsk.value ?: return@LaunchedEffect
        blockedAsk.value = null
        PhoneSpam.matches(PhoneSpam.read(), n, PhoneCountry.of(context))?.let { page = PhonePage.Blocking; blockedPage = it }
    }
    val askCall = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun call(n: String) {
        if (n.isBlank()) return
        if (tablet) scope.launch { val said = withContext(Dispatchers.IO) { PhoneRelay.place(context, n, who(n)?.name.orEmpty()) }; android.widget.Toast.makeText(context, said, android.widget.Toast.LENGTH_SHORT).show() }
        else if (!placePhoneCall(context, n)) askCall.launch(Manifest.permission.CALL_PHONE)
    }
    // a tablet: the list on the left and the keypad always open on the right, so no empty sides; a phone as before
    val twoPane = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp >= 600
    val keypadShown = keypad.value && !twoPane
    BackHandler(blockedPage != null || page != null || keypadShown || selected.isNotEmpty() || query.isNotEmpty()) {
        when { blockedPage != null -> blockedPage = null; page != null -> page = null; keypadShown -> keypad.value = false; selected.isNotEmpty() -> selected = emptySet(); else -> query = "" }
    }
    // the list's room under the pill and above the island
    val top = PaddingValues(top = 76.dp, bottom = 96.dp)
    Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.fillMaxHeight().widthIn(max = 560.dp).fillMaxWidth()) {
            val open = page
            val blocked = blockedPage
            if (blocked != null) PhonePageScreen((if (blocked.number.endsWith("*")) null else who(blocked.number)?.name) ?: blocked.number, back = { blockedPage = null }) {
                BlockedCard(blocked, calls.filter { PhoneSpam.matches(listOf(blocked), it.number, PhoneCountry.of(context)) != null }, if (blocked.number.endsWith("*")) null else who(blocked.number)) { blockedPage = null; version++ }
            } else if (open != null) PhonePageScreen(stringResource(open.title), back = { page = null }, if (open == PhonePage.Blocking) blockingScroll else rememberScrollState()) {
                when (open) {
                    PhonePage.Blocking -> CallBlocking(version, ::who, open = { blockedPage = it }) { version++ }
                    PhonePage.SpeedDial -> SpeedDialSettings(contacts)
                }
            } else {
                if (keypadShown) Keypad(number, contacts, call = ::call)
                else when (tab) {
                    PhoneTab.Recents -> Recents(calls, ::who, { PhoneSpam.matches(spam, it, PhoneCountry.of(context)) != null }, dialer, missed.value, query, selected, { if (!tablet) selected = it }, top, call = ::call, open = { details = it }) {
                        askRole.launch(context.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_DIALER))
                    }
                    PhoneTab.Favourites -> Favourites(contacts.filter { it.favourite && (query.isBlank() || it.name.contains(query, true)) }.sortedBy { ContactsFiles.sortKey(it, false) }, top, call = ::call)
                    PhoneTab.Contacts -> PhoneContacts(contacts, query, top, call = ::call)
                    PhoneTab.Recordings -> Recordings(query, top, version)
                }
                // the pill at the top: ‹ and search, or the chosen calls' count and Delete
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                        IconButton(onClick = { when { keypadShown -> keypad.value = false; selected.isNotEmpty() -> selected = emptySet(); else -> back() } }) {
                            Icon(painterResource(if (selected.isNotEmpty()) R.drawable.ic_close else R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_back))
                        }
                    }
                    if (!keypadShown) Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape, modifier = Modifier.weight(1f)) {
                        if (selected.isNotEmpty()) Row(Modifier.padding(start = 18.dp, end = 4.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                            selected.sumOf { it.calls.size }.let { n -> Text(pluralStringResource(R.plurals.phone_n_chosen, n, n), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            IconButton(onClick = { PhoneLog.delete(context, selected.flatMap { g -> g.calls.map { it.id } }); selected = emptySet() }) {
                                Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.common_delete))
                            }
                        } else Row(Modifier.padding(start = 16.dp, end = 4.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(painterResource(R.drawable.ic_search), contentDescription = null, Modifier.size(22.dp))
                            Box(Modifier.weight(1f)) {
                                if (query.isEmpty()) Text(stringResource(tab.search), color = islandContentColor().copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = islandContentColor(), fontSize = 16.sp),
                                    cursorBrush = SolidColor(islandContentColor()), modifier = Modifier.fillMaxWidth())
                            }
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_clear)) }
                            else DictateButton({ query = it.trim() })
                        }
                    }
                }
                // the island in the middle, the keypad's round button beside it
                if (!keypadShown) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IslandBottomBar(expand = { drawer = true }, visible = !drawer) {
                        PhoneTab.entries.forEach { t -> IslandNavigationItem(stringResource(t.label), tab == t, t.icon) { tab = t; selected = emptySet() } }
                    }
                    if (!twoPane) IslandVisibility(!drawer) {
                        Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                            IconButton(onClick = { keypad.value = true }, modifier = Modifier.size(56.dp)) { Icon(painterResource(R.drawable.ic_dialpad), contentDescription = stringResource(R.string.phone_keypad)) }
                        }
                    }
                }
            }
        }
    }
    if (twoPane) Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.weight(1f).fillMaxHeight().padding(12.dp)) {
        Keypad(number, contacts, call = ::call)
    }
    }
    details?.let { g ->
        TetraSheet(onDismissRequest = { details = null }, containerColor = SheetColor, contentColor = islandContentColor()) {
            CallDetails(g, calls.filter { PhoneLog.same(it.number, g.number) }, who(g.number), call = { call(it); details = null },
                delete = { ids -> PhoneLog.delete(context, ids); details = null; version++ }, changed = { version++ })
        }
    }
    if (drawer) TetraSheet(onDismissRequest = { drawer = false }, containerColor = SheetColor, contentColor = islandContentColor()) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.app_phone), style = MaterialTheme.typography.titleLarge)
            PhoneSettings()
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) { // side by side (the user, 27 Oct)
                PageButton(R.drawable.ic_block, stringResource(R.string.phone_blocking), stringResource(R.string.phone_blocking_about), Modifier.weight(1f)) { drawer = false; page = PhonePage.Blocking }
                PageButton(R.drawable.ic_speed_dial, stringResource(R.string.phone_speed_dial), stringResource(R.string.phone_speed_dial_hint), Modifier.weight(1f)) { drawer = false; page = PhonePage.SpeedDial }
            }
        }
    }
}

/** A tile in the settings sheet that opens one of the Phone's pages. */
@Composable
private fun PageButton(icon: Int, title: String, about: String, modifier: Modifier, open: () -> Unit) =
    Column(modifier.fillMaxHeight().clip(MaterialTheme.shapes.large).background(LocalContentColor.current.copy(alpha = 0.08f)).clickable(onClick = open).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(PHONE_COLOR), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(about, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.7f), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }

/** A page in the list's place: ‹ and its title in the pill's row, then what it holds. */
@Composable
private fun PhonePageScreen(title: String, back: () -> Unit, scroll: androidx.compose.foundation.ScrollState = rememberScrollState(), content: @Composable ColumnScope.() -> Unit) = Column(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
            IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_back)) }
        }
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/**
 * Call blocking, one list as Contacts is (the user, 27 Oct: "simplify, one list, contacts like; the calls show in the history of
 * their card"): every number and range on the spam list, A to Ω, whether it ever called or not; a tap opens its card.
 */
@Composable
private fun CallBlocking(version: Int, who: (String) -> ContactInfo?, open: (PhoneSpam.Entry) -> Unit, changed: () -> Unit) {
    SpamSettings(changed)
    val list = remember(version) { PhoneSpam.read().sortedBy { e -> (who(e.number)?.name ?: e.number).lowercase() } }
    list.forEach { e ->
        val c = if (e.number.endsWith("*")) null else who(e.number)
        Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable { open(e) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CallAvatar(c, 40.dp)
            Column(Modifier.weight(1f)) {
                Text(c?.name ?: e.number, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (c != null) Text(e.number, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.7f))
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
        }
    }
}

/** A blocked number's card: who, Unblock, every call with it (or within its range), and the calls recorded with it. */
@Composable
private fun BlockedCard(e: PhoneSpam.Entry, history: List<LoggedCall>, c: ContactInfo?, unblocked: () -> Unit) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val tablet = remember { PhoneRelay.noCalls(context) }
    val range = e.number.endsWith("*")
    val recordings by produceState(emptyList<java.io.File>(), e.number) {
        if (!range) value = withContext(Dispatchers.IO) { runCatching { ContactsRecordings.of(java.io.File(phoneRoot(), "Recordings").listFiles()?.toList().orEmpty(), c?.name.orEmpty(), listOf(e.number)) }.getOrDefault(emptyList()) }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CallAvatar(c, 88.dp)
        Text(e.number, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        (if (range) null else PhoneArea.label(e.number))?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current.copy(alpha = 0.7f)) }
        // the user's own note; not "blocked before", Tetra's mark on the numbers it took from Android's list (SpamSettings)
        if (e.note.isNotBlank() && e.note != "blocked before") Text(e.note, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.7f), textAlign = TextAlign.Center)
        Button(onClick = {
            if (range) PhoneSpam.removeLine(context, e) else { PhoneSpam.remove(context, e.number); if (!tablet) PhoneBlocked.unblock(context, e.number) }
            unblocked()
        }, colors = ButtonDefaults.buttonColors(containerColor = PHONE_COLOR, contentColor = Color.White)) {
            Icon(painterResource(R.drawable.ic_block), contentDescription = null, modifier = Modifier.size(18.dp))
            Text("  " + stringResource(R.string.phone_unblock), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    Text(stringResource(R.string.contacts_history), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
    if (history.isEmpty()) Text(stringResource(R.string.phone_no_calls_yet), style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.7f))
    history.take(100).forEach { l ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CallTypeIcon(l.type, 22.dp)
            Text(Instant.ofEpochMilli(l.at).atZone(zone).format(DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", locale)) + if (range) " · ${l.number}" else "")
        }
    }
    if (recordings.isNotEmpty()) {
        Text(stringResource(R.string.contacts_recordings), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
        recordings.forEach { RecordingRow(it) }
    }
}

private enum class RecentsFilter(@androidx.annotation.StringRes val label: Int) { All(R.string.phone_filter_all), Missed(R.string.phone_filter_missed), Unknown(R.string.phone_filter_unknown) }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Recents(calls: List<LoggedCall>, who: (String) -> ContactInfo?, isSpam: (String) -> Boolean, dialer: Boolean, showMissed: Int, query: String, selected: Set<CallGroup>, select: (Set<CallGroup>) -> Unit,
                    pad: PaddingValues, call: (String) -> Unit, open: (CallGroup) -> Unit, makeDialer: () -> Unit) {
    var filter by remember { mutableStateOf(RecentsFilter.All) }
    LaunchedEffect(showMissed) { if (showMissed > 0) filter = RecentsFilter.Missed }
    val zone = ZoneId.systemDefault()
    val groups = remember(calls, query, filter) {
        val q = query.trim().lowercase()
        PhoneLog.group(calls.filter { c ->
            when (filter) { RecentsFilter.All -> true; RecentsFilter.Missed -> c.missed; RecentsFilter.Unknown -> who(c.number) == null }
        }, zone).filter { g -> q.isEmpty() || g.number.filter(Char::isDigit).contains(q.filter(Char::isDigit).ifEmpty { "\u0000" }) || who(g.number)?.name?.lowercase()?.contains(q) == true }
    }
    val today = LocalDate.now(zone)
    val context = LocalContext.current
    val heads = listOf(stringResource(R.string.common_today), stringResource(R.string.common_yesterday), stringResource(R.string.phone_earlier)) // today, yesterday, earlier: read here, the list's builder is not composable
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
        if (!dialer) item {
            Surface(color = PHONE_COLOR.copy(alpha = 0.18f), shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.phone_make_dialer_hint), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = makeDialer) { Text(stringResource(R.string.phone_make), color = PHONE_COLOR, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                // Contacts' Details | History pill, in the Phone's teal (the user, 25 Oct: "rounded with the accent colour of the applet's icon")
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = CircleShape) {
                    Row(Modifier.padding(4.dp)) {
                        RecentsFilter.entries.forEach { f ->
                            Surface(onClick = { filter = f }, shape = CircleShape, color = if (filter == f) PHONE_COLOR else Color.Transparent,
                                contentColor = if (filter == f) Color.White else MaterialTheme.colorScheme.onSurface) {
                                Text(stringResource(f.label), Modifier.padding(horizontal = 22.dp, vertical = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        if (groups.isEmpty()) item { Hint(if (filter == RecentsFilter.Missed) stringResource(R.string.phone_no_missed) else if (query.isNotEmpty()) stringResource(R.string.phone_no_calls_match, query) else stringResource(R.string.phone_no_calls_yet)) }
        var last: String? = null
        groups.forEach { g ->
            val d = PhoneLog.day(g.newest.at, zone)
            val head = when (d) { today -> heads[0]; today.minusDays(1) -> heads[1]; else -> heads[2] }
            if (head != last) { last = head; item(key = "h:$head") { Text(head, Modifier.padding(start = 24.dp, top = 12.dp, bottom = 2.dp), color = PHONE_COLOR, fontWeight = FontWeight.Bold) } }
            item(key = g.newest.id) {
                val c = who(g.number)
                val chosen = g in selected
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(MaterialTheme.shapes.large).background(if (chosen) PHONE_COLOR.copy(alpha = 0.3f) else Color.Transparent)
                    .combinedClickable(onLongClick = { select(selected + g) }) { if (selected.isEmpty()) open(g) else select(if (chosen) selected - g else selected + g) }
                    .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    CallAvatar(c, 42.dp)
                    Column(Modifier.weight(1f)) {
                        val missed = g.newest.missed
                        Text((c?.name ?: g.number.ifBlank { stringResource(R.string.phone_hidden_number) }) + if (g.calls.size > 1) " (${g.calls.size})" else "",
                            color = if (missed) CALL_RED else MaterialTheme.colorScheme.onSurface, fontWeight = if (missed && g.newest.unseen) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            CallTypeIcon(g.newest.type, 20.dp)
                            val where = c?.phones?.firstOrNull { ContactsWays.key(it.value) == ContactsWays.key(g.number) }?.label?.ifBlank { null }?.let { contactLabel(context, it) } ?: PhoneArea.label(g.number)
                            if (g.number.isNotBlank() && isSpam(g.number)) Text(stringResource(R.string.phone_spam) + " ·", style = MaterialTheme.typography.bodySmall, color = CALL_RED, fontWeight = FontWeight.Bold)
                            Text(listOfNotNull(where, whenText(g.newest.at, zone, today, androidx.compose.ui.platform.LocalConfiguration.current.locales[0])).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                    if (g.number.isNotBlank()) IconButton(onClick = { call(g.number) }) { Icon(painterResource(R.drawable.ic_call), contentDescription = stringResource(R.string.contacts_call), tint = PHONE_COLOR) }
                }
            }
        }
    }
}

@Composable private fun Hint(text: String) = Text(text, Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Tetra's contacts, A to Ω, each with its call button; a tap opens the person in Contacts. */
@Composable
private fun PhoneContacts(contacts: List<ContactInfo>, query: String, pad: PaddingValues, call: (String) -> Unit) {
    val context = LocalContext.current
    val shown = remember(contacts, query) {
        contacts.filter { it.phones.isNotEmpty() && (query.isBlank() || it.name.contains(query, true) || it.phones.any { p -> p.value.filter(Char::isDigit).contains(query.filter(Char::isDigit).ifEmpty { "\u0000" }) }) }
            .sortedBy { ContactsFiles.sortKey(it, false) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
        if (shown.isEmpty()) item { Hint(if (query.isNotEmpty()) stringResource(R.string.contacts_no_match, query) else stringResource(R.string.phone_no_contacts_number)) }
        var last = ""
        shown.forEach { c ->
            val l = ContactsFiles.letter(c, false)
            if (l != last && query.isEmpty()) { last = l; item(key = "h:$l") { Text(l, Modifier.padding(start = 24.dp, top = 12.dp, bottom = 2.dp), color = PHONE_COLOR, fontWeight = FontWeight.Bold) } }
            item(key = c.uid.ifBlank { c.name }) {
                val n = c.defaultPhone ?: c.phones.first().value
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(MaterialTheme.shapes.large).clickable { openInContacts(context, uid = c.uid) }
                    .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ContactAvatar(c, 40.dp)
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(n, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { call(n) }) { Icon(painterResource(R.drawable.ic_call), contentDescription = stringResource(R.string.phone_call_name, c.name), tint = PHONE_COLOR) }
                }
            }
        }
    }
}

/** The calls recorded on the computer (Plan.md § Phone › Call recording), synced here: Tetra/.System/Phone/Shared/Recordings, newest first. */
@Composable
private fun Recordings(query: String, pad: PaddingValues, version: Int) {
    val files by produceState(emptyList<java.io.File>(), version) {
        value = withContext(Dispatchers.IO) { java.io.File(phoneRoot(), "Recordings").listFiles { f -> f.isFile && f.extension.lowercase() in setOf("ogg", "opus", "m4a", "mp3", "wav") }?.sortedByDescending { it.lastModified() }.orEmpty() }
    }
    val shown = files.filter { query.isBlank() || it.name.contains(query, true) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
        if (shown.isEmpty()) item {
            Hint(if (query.isNotEmpty()) stringResource(R.string.phone_no_recording_match, query) else stringResource(R.string.phone_recordings_hint))
        }
        items(shown, key = { it.path }) { f -> RecordingRow(f, Modifier.padding(horizontal = 8.dp)) }
    }
}

/** A recording: its name and size; a tap plays it in the phone's own player. */
@Composable
private fun RecordingRow(f: java.io.File, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable {
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { WriterLog.w("Phone: a recording did not open (${it.message})") }
    }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(PHONE_COLOR.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_mic), contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(f.nameWithoutExtension, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${f.length() / 1024} KB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun whenText(at: Long, zone: ZoneId, today: LocalDate, locale: Locale): String {
    val t = Instant.ofEpochMilli(at).atZone(zone)
    val d = t.toLocalDate()
    return when {
        d == today || d == today.minusDays(1) -> t.format(DateTimeFormatter.ofPattern("HH:mm"))
        d.isAfter(today.minusDays(7)) -> t.format(DateTimeFormatter.ofPattern("EEE HH:mm", locale))
        d.year == today.year -> t.format(DateTimeFormatter.ofPattern("d MMM", locale))
        else -> t.format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))
    }
}

@Composable
internal fun CallTypeIcon(type: Int, size: Dp) {
    // a white arrow on a coloured round (the user, 25 Oct: "barely noticeable what is what"): in green, out blue, missed and
    // declined red, blocked grey
    val (icon, tint, what) = when (type) {
        CallLog.Calls.OUTGOING_TYPE -> Triple(R.drawable.ic_call_made, CALL_BLUE, R.string.phone_type_outgoing)
        CallLog.Calls.MISSED_TYPE -> Triple(R.drawable.ic_call_missed, CALL_RED, R.string.phone_type_missed)
        CallLog.Calls.REJECTED_TYPE -> Triple(R.drawable.ic_call_end, CALL_RED, R.string.phone_type_declined)
        CallLog.Calls.BLOCKED_TYPE -> Triple(R.drawable.ic_block, Color(0xFF7A7F84), R.string.phone_type_blocked)
        else -> Triple(R.drawable.ic_call_received, CALL_GREEN, R.string.phone_type_incoming)
    }
    Box(Modifier.size(size).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = stringResource(what), tint = Color.White, modifier = Modifier.size(size * 0.68f))
    }
}

@androidx.annotation.StringRes private fun typeText(type: Int) = when (type) {
    CallLog.Calls.OUTGOING_TYPE -> R.string.phone_type_outgoing; CallLog.Calls.MISSED_TYPE -> R.string.phone_type_missed; CallLog.Calls.REJECTED_TYPE -> R.string.phone_type_declined
    CallLog.Calls.BLOCKED_TYPE -> R.string.phone_type_blocked; CallLog.Calls.VOICEMAIL_TYPE -> R.string.phone_type_voicemail; else -> R.string.phone_type_incoming
}

@Composable
internal fun CallAvatar(c: ContactInfo?, size: Dp) {
    if (c != null) ContactAvatar(c, size)
    else Box(Modifier.size(size).clip(CircleShape).background(PHONE_COLOR.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_person), contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
    }
}

/** A line of Recents opened: who, the ways to reach them, and every call with that number. */
@Composable
private fun CallDetails(g: CallGroup, all: List<LoggedCall>, c: ContactInfo?, call: (String) -> Unit, delete: (List<Long>) -> Unit, changed: () -> Unit) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val notes = remember { PhoneNotes.all() } // shortcut: read on the main thread, a few KB; move off it if notes grow large
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                CallAvatar(c, 88.dp)
                Text(c?.name ?: g.number.ifBlank { stringResource(R.string.phone_hidden_number) }, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 10.dp), textAlign = TextAlign.Center)
                if (g.number.isNotBlank()) Text(listOfNotNull(c?.let { g.number }, PhoneArea.label(g.number)).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = islandContentColor().copy(alpha = 0.7f))
                if (g.number.isNotBlank()) Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundButton(R.drawable.ic_call, stringResource(R.string.contacts_call), CALL_GREEN, 56.dp) { call(g.number) }
                    RoundButton(R.drawable.ic_chat, stringResource(R.string.contacts_message), null, 56.dp) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(g.number)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    RoundButton(R.drawable.ic_person, if (c != null) stringResource(R.string.phone_contact) else stringResource(R.string.common_add), null, 56.dp) { openInContacts(context, uid = c?.uid?.ifBlank { null }, newNumber = g.number) }
                    RoundButton(R.drawable.ic_copy, stringResource(R.string.common_copy), null, 56.dp) {
                        context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Number", g.number))
                    }
                }
                if (g.number.isNotBlank()) NumberActions(g.number, changed)
            }
        }
        items(all.take(50), key = { it.id }) { l ->
            Column {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CallTypeIcon(l.type, 24.dp)
                val t = Instant.ofEpochMilli(l.at).atZone(zone)
                Text(t.format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", androidx.compose.ui.platform.LocalConfiguration.current.locales[0])), Modifier.weight(1f))
                Text(listOfNotNull(stringResource(typeText(l.type)), l.seconds.takeIf { it > 0 }?.let { PhoneCalls.elapsed(it * 1000) }).joinToString(" · "), color = islandContentColor().copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall)
            }
            PhoneNotes.of(context, l.number, l.at, notes)?.let { Text(it, Modifier.padding(start = 28.dp, bottom = 4.dp), style = MaterialTheme.typography.bodyMedium, color = islandContentColor().copy(alpha = 0.85f)) }
            }
        }
        item {
            TextButton(onClick = { delete(g.calls.map { it.id }) }, modifier = Modifier.padding(top = 8.dp)) {
                Text(pluralStringResource(R.plurals.phone_delete_calls, g.calls.size, g.calls.size), color = CALL_RED, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Favourites(people: List<ContactInfo>, pad: PaddingValues, call: (String) -> Unit) {
    val context = LocalContext.current
    if (people.isEmpty()) { Box(Modifier.padding(pad)) { Hint(stringResource(R.string.phone_favourites_hint)) }; return }
    LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 76.dp, bottom = 96.dp)) {
        items(people, key = { it.uid.ifBlank { it.name } }) { p ->
            val number = p.defaultPhone ?: p.phones.firstOrNull()?.value
            Column(Modifier.clip(MaterialTheme.shapes.medium).combinedClickable(onLongClick = { openInContacts(context, uid = p.uid) }) { if (number != null) call(number) else openInContacts(context, uid = p.uid) }
                .padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ContactAvatar(p, 72.dp)
                Text(p.name, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** The keypad with search as you type (Latin and Greek letters); hold 1 for voicemail, 2–9 for speed dial. */
@Composable
private fun Keypad(number: MutableState<String>, contacts: List<ContactInfo>, call: (String) -> Unit) {
    val context = LocalContext.current
    var assigning by remember { mutableStateOf<Char?>(null) }
    val typed = number.value
    val found = remember(typed, contacts) {
        if (typed.isEmpty()) emptyList() else contacts.filter { PhoneT9.matches(typed, it.name, it.phones.map { p -> p.value }) }
            .sortedWith(compareBy({ !it.favourite }, { it.name.lowercase() })).take(30)
    }
    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true) {
            items(found, key = { it.uid.ifBlank { it.name } }) { p ->
                val n = p.phones.firstOrNull { PhoneT9.matches(typed.filter(Char::isDigit), "", listOf(it.value)) }?.value ?: p.defaultPhone ?: p.phones.firstOrNull()?.value
                Row(Modifier.fillMaxWidth().clickable { if (n != null) number.value = n }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ContactAvatar(p, 36.dp)
                    Column(Modifier.weight(1f)) {
                        Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (n != null) Text(n, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (n != null) IconButton(onClick = { call(n) }) { Icon(painterResource(R.drawable.ic_call), contentDescription = stringResource(R.string.phone_call_name, p.name), tint = PHONE_COLOR) }
                }
            }
        }
        // held: the number on the clipboard pasted in (digits, + * # only)
        @OptIn(ExperimentalFoundationApi::class)
        Text(typed.ifEmpty { " " }, fontSize = if (typed.length > 13) 28.sp else 36.sp, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            .combinedClickable(onLongClickLabel = stringResource(R.string.common_paste), onLongClick = {
                context.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                    ?.filter { it.isDigit() || it in "+*#" }?.takeIf { it.isNotEmpty() }?.let { number.value = it }
            }) {})
        Text(PhoneArea.label(typed)?.takeIf { found.isEmpty() } ?: " ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        DialKeys(press = { ch -> number.value += ch; PhoneCodes.typed(context, number.value)?.let { number.value = "" } }, hold = { ch ->
            when (ch) {
                '0' -> number.value = number.value.dropLast(1) + "+"
                '1' -> if (number.value == "1") { number.value = ""; PhoneSpeed.voicemail(context) }
                in '2'..'9' -> if (number.value == ch.toString()) {
                    number.value = ""
                    PhoneSpeed.get(context, ch)?.let { call(it.second) } ?: run { assigning = ch }
                }
            }
        })
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(56.dp))
            RoundButton(R.drawable.ic_call, stringResource(R.string.contacts_call), CALL_GREEN, 68.dp) { call(number.value.trim()) }
            @OptIn(ExperimentalFoundationApi::class)
            Box(Modifier.size(56.dp).clip(CircleShape).combinedClickable(onClickLabel = stringResource(R.string.common_delete), onLongClick = { number.value = "" }) { number.value = number.value.dropLast(1) },
                contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_backspace), contentDescription = stringResource(R.string.common_delete), modifier = Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    assigning?.let { key -> SpeedDialPicker(key, contacts, dismiss = { assigning = null }) }
}

/** Speed dial: a contact's number on a key, held from the keypad. Kept per phone ("phone" preferences). */
internal object PhoneSpeed {
    private fun p(c: Context) = c.getSharedPreferences("phone", Context.MODE_PRIVATE)
    fun get(c: Context, key: Char): Pair<String, String>? = p(c).getString("speed_$key", null)?.split('\n')?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
    fun set(c: Context, key: Char, name: String?, number: String?) = p(c).edit().apply { if (number == null) remove("speed_$key") else putString("speed_$key", "$name\n$number") }.apply()
    fun voicemail(c: Context) = runCatching { c.getSystemService(android.telecom.TelecomManager::class.java).placeCall(Uri.fromParts("voicemail", "", null), Bundle()) }
        .onFailure { WriterLog.w("Phone: voicemail not called (${it.message})") }
}

@Composable
private fun SpeedDialPicker(key: Char, contacts: List<ContactInfo>, dismiss: () -> Unit) {
    val context = LocalContext.current
    var q by remember { mutableStateOf("") }
    val rows = remember(q, contacts) {
        contacts.filter { q.isBlank() || it.name.contains(q, true) }.flatMap { c -> c.phones.map { c.name to it } }.take(60)
    }
    TetraAlertDialog(onDismissRequest = dismiss, confirmButton = {}, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        title = { Text(stringResource(R.string.phone_speed_dial_key, key.toString())) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text(stringResource(R.string.phone_search_contacts)) }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(rows) { (name, f) ->
                        Column(Modifier.fillMaxWidth().clickable { PhoneSpeed.set(context, key, name, f.value); dismiss() }.padding(vertical = 8.dp)) {
                            Text(name); Text(listOf(contactLabel(context, f.label), f.value).filter { it.isNotBlank() }.joinToString(" "), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        })
}

@Composable
private fun SpeedDialSettings(contacts: List<ContactInfo>) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var assigning by remember { mutableStateOf<Char?>(null) }
    @Suppress("UNUSED_EXPRESSION") version
    Text(stringResource(R.string.phone_speed_dial), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.phone_speed_dial_hint), style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.7f))
    ('2'..'9').forEach { k ->
        val s = PhoneSpeed.get(context, k)
        Row(Modifier.fillMaxWidth().clickable { assigning = k }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$k", Modifier.width(28.dp), fontWeight = FontWeight.Bold)
            Text(s?.let { "${it.first} · ${it.second}" } ?: "—", Modifier.weight(1f))
            if (s != null) TextButton(onClick = { PhoneSpeed.set(context, k, null, null); version++ }) { Text(stringResource(R.string.common_remove), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
    assigning?.let { k -> SpeedDialPicker(k, contacts, dismiss = { assigning = null; version++ }) }
}

/** Block or unblock a number; a person's ringtone (when the phone's contacts know the number). */
@Composable
private fun NumberActions(number: String, changed: () -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val spam = remember(version) { PhoneSpam.matches(PhoneSpam.read(), number, PhoneCountry.of(context)) }
    val tablet = remember { PhoneRelay.noCalls(context) }
    val known = remember { PhoneRingtone.known(context, number) }
    val tone = remember(version) { if (known) PhoneRingtone.get(context, number) else null }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) {
            @Suppress("DEPRECATION") val uri = r.data?.getParcelableExtra<Uri>(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            PhoneRingtone.set(context, number, uri); version++
        }
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        // spam: Tetra's list (every device), and on the phone Android's own blocked list too (its SMS)
        // caught by a range (69800*): that line is the one to change, in the Phone's sheet
        if (spam?.number?.endsWith("*") == true) Text(stringResource(R.string.phone_spam_range, spam.number), color = CALL_RED, modifier = Modifier.padding(12.dp))
        else TextButton(onClick = {
            if (spam != null) { PhoneSpam.remove(context, number); if (!tablet) PhoneBlocked.unblock(context, number) }
            else { PhoneSpam.add(context, number); if (!tablet) PhoneBlocked.block(context, number) }
            version++; changed()
        }) {
            Icon(painterResource(R.drawable.ic_block), contentDescription = null, tint = if (spam != null) islandContentColor() else CALL_RED, modifier = Modifier.size(18.dp))
            Text("  " + if (spam != null) stringResource(R.string.phone_not_spam) else stringResource(R.string.phone_spam), color = if (spam != null) islandContentColor() else CALL_RED, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PhoneSpam.lookUp(number, PhoneCountry.of(context))?.let { url ->
            TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) {
                Text("CallInsider", color = islandContentColor())
            }
        }
        if (known) TextButton(onClick = {
            pick.launch(Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_RINGTONE)
                .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, tone).putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true))
        }) {
            val name = tone?.let { runCatching { android.media.RingtoneManager.getRingtone(context, it)?.getTitle(context) }.getOrNull() } ?: stringResource(R.string.phone_ringtone_default)
            Text(stringResource(R.string.phone_ringtone, name), color = islandContentColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The spam list (Tetra/.System/Phone/Shared/spam.txt): each line with Remove, and a number or range (69800*) to add. */
@Composable
private fun SpamSettings(changed: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { // Android's blocked numbers join the list once, so there is one list to look after
        val p = context.getSharedPreferences("phone", Context.MODE_PRIVATE)
        if (!p.getBoolean("spam_from_blocked", false) && PhoneBlocked.can(context)) {
            withContext(Dispatchers.IO) { PhoneBlocked.all(context).forEach { (_, n) -> PhoneSpam.add(context, n, "blocked before") } }
            p.edit().putBoolean("spam_from_blocked", true).apply()
        }
    }
    // the list itself is not shown (the user, 27 Oct: "its the same" as the blocked calls); a number is unblocked on its page
    var adding by remember { mutableStateOf("") }
    if (!PhoneRelay.noCalls(context)) {
        var notify by remember { mutableStateOf(PhonePrefs.blockedNotify(context)) }
        Row(Modifier.fillMaxWidth().clickable { notify = !notify; PhonePrefs.setBlockedNotify(context, notify) }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.phone_blocked_notify), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.phone_blocked_notify_about), style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.7f))
            }
            Switch(checked = notify, onCheckedChange = { notify = it; PhonePrefs.setBlockedNotify(context, it) })
        }
    }
    Text(stringResource(R.string.phone_blocking_hint), style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.7f))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(adding, { adding = it.filter { ch -> ch.isDigit() || ch in "+* " } }, singleLine = true, placeholder = { Text(stringResource(R.string.phone_spam_placeholder)) }, modifier = Modifier.weight(1f))
        TextButton(onClick = { if (adding.any(Char::isDigit)) { PhoneSpam.add(context, adding.replace(" ", "")); adding = ""; changed() } }) { Text(stringResource(R.string.common_add), color = LocalContentColor.current, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}
