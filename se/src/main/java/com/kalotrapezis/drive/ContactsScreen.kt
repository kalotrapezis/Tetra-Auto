package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Contacts' colour: the icon's own brown (Plan.md § Contacts › Home). */
internal val CONTACTS_COLOR = Color(0xFFBE7E4D)

internal fun contactsRoot() = ContactsFiles.root(TetraFolder.root(Environment.getExternalStorageDirectory()))

/** Contacts read ahead when Tetra opens (the user, 24 Oct: "no contacts" showed for a second before the list). */
/**
 * Contacts read ahead when Tetra opens, so the screen has them at once (the user, 25 Oct: "slow; taps don't answer until
 * it's all ready"): first the favourites and the screenful the list began with last time (their files' names are kept),
 * their faces made small at once, then everyone, sorted here and not on the screen's thread. [list] is what the screen shows; [all] everyone.
 */
internal object ContactsPreload {
    private const val FIRST = 24
    @Volatile var all: List<Contact>? = null
    /** The list as the screen shows it, sorted for [byLast]: the first screenful, then everyone. */
    var list by mutableStateOf<List<Contact>?>(null)
        private set
    @Volatile var byLast = false
        private set
    private fun order(c: Context) = File(c.cacheDir, "contacts-first.txt")

    fun warm(context: Context) = Thread { runCatching { load(context.applicationContext, ContactsPrefs.byLast(context)) } }.start()

    /** Reads the folder (only files changed since are read again) and answers everyone; one read at a time. */
    fun load(context: Context, byLast: Boolean): List<Contact> = synchronized(this) {
        if (list == null) runCatching {
            val first = order(context).readLines().map(::File).filter { it.isFile }.map(ContactsFiles::cached).filter { it.lines.isNotEmpty() }
            first.forEach { ContactThumbs.get(it.info.photo, ContactThumbs.SMALL) } // the favourites' faces ready before anyone else is read
            if (first.isNotEmpty()) { this.byLast = byLast; list = ContactsFiles.sorted(first, byLast) }
        }
        val everyone = ContactsFiles.sorted(ContactsFiles.all(contactsRoot()), byLast)
        // the favourites (their faces on top) and the first screenful: read first next time; a face is made again only
        // when its photo changed (ContactThumbs knows a photo by its bytes)
        val ahead = (everyone.filter { it.info.favourite } + everyone.take(FIRST)).distinct()
        ahead.forEach { ContactThumbs.get(it.info.photo, ContactThumbs.SMALL) }
        all = everyone; this.byLast = byLast; list = everyone
        runCatching { order(context).writeText(ahead.mapNotNull { it.file?.path }.joinToString("\n")) }
        everyone
    }
}

/**
 * Contacts' photos made small once, off the screen's thread: a row's face is 40 dp, and decoding the vCard's full photo
 * for each row as it came on screen kept taps waiting.
 */
internal object ContactThumbs {
    const val SMALL = 192
    private const val LARGE = 720
    private val cache = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(400)
    fun size(px: Int) = if (px <= SMALL) SMALL else LARGE
    private fun key(photo: String, size: Int) = "${photo.length}:${photo.hashCode()}:$size"
    fun cached(photo: String?, size: Int) = photo?.let { cache.get(key(it, size)) }
    fun get(photo: String?, size: Int): androidx.compose.ui.graphics.ImageBitmap? {
        photo ?: return null
        cached(photo, size)?.let { return it }
        val bytes = runCatching { java.util.Base64.getDecoder().decode(photo) }.getOrNull() ?: return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        val b = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return b.asImageBitmap().also { cache.put(key(photo, size), it) }
    }
}

internal object ContactsPrefs {
    private fun p(c: Context) = c.getSharedPreferences("contacts", Context.MODE_PRIVATE)
    fun byLast(c: Context) = p(c).getBoolean("byLast", false)
    fun setByLast(c: Context, on: Boolean) = p(c).edit().putBoolean("byLast", on).apply()
    /** Settings › Contacts › Birthdays go into: a calendar's folder; none chosen: Γενέθλια or Birthdays (ContactsBirthdays.calendar). */
    fun birthdays(c: Context) = p(c).getString("birthdays", null)
    fun setBirthdays(c: Context, dir: String) = p(c).edit().putString("birthdays", dir).apply()
    /** Events of the birthdays calendar the person said are no one here ("Not this person" in the review). */
    fun dismissed(c: Context): Set<String> = p(c).getStringSet("notLinked", emptySet())!!
    fun dismiss(c: Context, uids: Collection<String>) = p(c).edit().putStringSet("notLinked", dismissed(c) + uids).apply()
}

/**
 * Every contact's dates made to match its events — except those an unanswered link suggestion names (an event already in
 * the calendar may be theirs: linking it, not a second event). Answers the suggestions left.
 */
internal fun syncBirthdays(context: Context, contacts: List<ContactInfo>): List<ContactsBirthdays.Link> {
    val calRoot = calendarRoot()
    val links = ContactsBirthdays.suggestions(calRoot, contacts, ContactsPrefs.birthdays(context), ContactsPrefs.dismissed(context))
    val waiting = links.map { it.contact.uid }.toSet()
    if (ContactsBirthdays.syncAll(calRoot, contacts.filter { it.uid !in waiting }, ContactsPrefs.birthdays(context)) > 0) CalendarAlarms.planSoon(context)
    return links
}

private enum class ContactsTab(@androidx.annotation.StringRes val label: Int, val icon: Int) { All(R.string.common_all, R.drawable.ic_person), Favourites(R.string.contacts_favourites, R.drawable.ic_favorite), Labels(R.string.contacts_labels, R.drawable.ic_label) }

/** Tetra's own field labels, kept in English in the files (Google's and Apple's words), shown in the UI's language; a typed label as it is. */
private val CONTACT_LABELS = mapOf("Mobile" to R.string.contacts_label_mobile, "Home" to R.string.contacts_label_home, "Work" to R.string.contacts_label_work,
    "Main" to R.string.contacts_label_main, "Home fax" to R.string.contacts_label_home_fax, "Work fax" to R.string.contacts_label_work_fax, "Pager" to R.string.contacts_label_pager,
    "Other" to R.string.contacts_label_other, "Anniversary" to R.string.contacts_label_anniversary, "Date" to R.string.contacts_label_date, "Home page" to R.string.contacts_label_home_page,
    "Blog" to R.string.contacts_label_blog, "Profile" to R.string.contacts_label_profile, "Spouse" to R.string.contacts_label_spouse, "Partner" to R.string.contacts_label_partner,
    "Mother" to R.string.contacts_label_mother, "Father" to R.string.contacts_label_father, "Parent" to R.string.contacts_label_parent, "Brother" to R.string.contacts_label_brother,
    "Sister" to R.string.contacts_label_sister, "Child" to R.string.contacts_label_child, "Friend" to R.string.contacts_label_friend, "Assistant" to R.string.contacts_label_assistant,
    "Manager" to R.string.contacts_label_manager)
internal fun contactLabel(context: Context, id: String): String = CONTACT_LABELS[id]?.let(context::getString) ?: id

/**
 * Contacts (Plan.md § Contacts): the list (search on top, favourites' faces, letters), a contact's page as the iPhone's
 * made Tetra's, Tetra's island at the bottom (All · Favourites · Labels, + at the right) and its drawer (Import, Trash,
 * Settings). A wide screen has the list at the left and the open contact beside it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContactsScreen(back: () -> Unit, hasAccess: Boolean, grant: () -> Unit, start: String? = null, newNumber: String? = null, openMaps: (String) -> Unit = {}) {
    val context = LocalContext.current
    val wide = LocalConfiguration.current.screenWidthDp >= 700
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(ContactsTab.All) }
    var label by remember { mutableStateOf<String?>(null) }
    var openUid by remember { mutableStateOf(start) }
    var drawer by remember { mutableStateOf(false) }
    var trashOpen by remember { mutableStateOf(false) }
    var byLast by remember { mutableStateOf(ContactsPrefs.byLast(context)) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var links by remember { mutableStateOf<List<ContactsBirthdays.Link>>(emptyList()) } // events in the birthdays calendar that seem to be these people
    var reviewing by remember { mutableStateOf(false) }
    var faceMatches by remember { mutableStateOf<List<Pair<ContactInfo, PhotoPerson>>>(emptyList()) } // contacts with no photo Photos knows by name
    var facesOpen by remember { mutableStateOf(false) }
    var mergeOpen by remember { mutableStateOf(false) }
    var exportOpen by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var choosingCalendar by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) } // long press: many at once (the user, 23 Oct: "how do I multi select?")
    var labelling by remember { mutableStateOf(false) }
    var deletingMany by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Pair<Contact?, ContactInfo>?>(newNumber?.let { null to ContactInfo(phones = listOf(VField("Mobile", it))) }) } // the contact (null: a new one) and where its editor starts
    LaunchedEffect(message) { if (message != null) { delay(3500); message = null } }
    val root = remember { contactsRoot() }

    if (!hasAccess) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_back)) }
            Text(stringResource(R.string.contacts_access_why))
            TextButton(onClick = grant) { Text(stringResource(R.string.contacts_allow_access), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        return
    }
    DisposableEffect(root) {
        root.mkdirs()
        val watcher = DriveWatcher(root) { version++ }
        onDispose { watcher.stop() }
    }
    // read ahead when Tetra opens (ContactsPreload): the list shows at once; until the first read, a spinner, never "No contacts"
    // (sorted there too: nothing heavy on this thread, taps answered from the first frame)
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(version, byLast) {
        failed = withContext(Dispatchers.IO) { runCatching { ContactsPreload.load(context, byLast) } }.onFailure { WriterLog.e("Contacts: reading the folder", it) }.isFailure
    }
    val loaded = ContactsPreload.list?.takeIf { ContactsPreload.byLast == byLast } ?: if (failed) emptyList() else null
    val all = loaded.orEmpty()
    OpenTiming("Contacts", all.isNotEmpty())
    val open = all.firstOrNull { it.info.uid == openUid }
    // two columns always show a contact; one column (a phone, split screen, a folded tablet) opens one only when it is
    // chosen, so a contact the two columns opened by themselves closes when the screen narrows (the user, 24 Oct)
    var autoOpened by remember { mutableStateOf(false) }
    LaunchedEffect(wide, all) { if (wide && open == null && all.isNotEmpty()) { openUid = all.first().info.uid; autoOpened = true } }
    LaunchedEffect(wide) { if (!wide && autoOpened) { openUid = null; autoOpened = false } }

    /** A change made here goes to the other devices at once (Contacts' part, SYNC_PLAN 6ap), its birthdays' events with it. */
    fun pushed() { ScopedSync.soon(context, "contacts", 0, orWhole = true); ScopedSync.soon(context, "calendar", 0, orWhole = true) }
    /** Runs [work] on the IO thread, says what it answers, and reads the folder again. */
    fun act(work: () -> String) = scope.launch {
        val r = withContext(Dispatchers.IO) { runCatching(work) }
        message = r.getOrElse { context.getString(R.string.contacts_not_done, it.shownMessage(context) ?: it.javaClass.simpleName) }
        r.exceptionOrNull()?.let { WriterLog.e("Contacts: a change failed", it) }
        version++; pushed()
    }
    /** [c] (null: a new contact) saved as [i]; the page opens on it. */
    fun save(c: Contact?, i: ContactInfo, said: String = context.getString(R.string.contacts_saved)) = scope.launch {
        val r = withContext(Dispatchers.IO) { runCatching { ContactsFiles.save(root, c, i).also { saved ->
            // its birthday and dates into the calendar (the contact is the truth)
            runCatching { val calRoot = calendarRoot(); if (ContactsBirthdays.sync(calRoot, saved.info, ContactsPrefs.birthdays(context), ContactsBirthdays.linked(calRoot)[saved.info.uid].orEmpty()) > 0) CalendarAlarms.planSoon(context) }
                .onFailure { WriterLog.e("Contacts: dates into the calendar", it) }
        } } }
        r.onSuccess { openUid = it.info.uid; message = said }.onFailure { message = context.getString(R.string.contacts_not_saved, it.message); WriterLog.e("Contacts: save", it) }
        version++; pushed()
    }
    fun imported(r: ContactsFiles.Imported) = if (r.same > 0) context.getString(R.string.contacts_imported_same, r.added, r.same) else context.getString(R.string.contacts_imported, r.added)
    /** Contacts with no photo whose faces Photos knows: offered after an import (and in the drawer). */
    suspend fun faces(show: Boolean) {
        faceMatches = withContext(Dispatchers.IO) { runCatching { ContactsFaces.matches(ContactsFiles.all(root).map { it.info }, ContactsFaces.people(context)) { it.name } }.getOrDefault(emptyList()) }
        if (show && faceMatches.isNotEmpty()) facesOpen = true
    }
    /** After an import or a link: the dates into the calendar, and the events there that may be these people offered for linking. */
    suspend fun birthdays(show: Boolean) {
        val l = withContext(Dispatchers.IO) { runCatching { syncBirthdays(context, ContactsFiles.all(root).map { it.info }) }.onFailure { WriterLog.e("Contacts: birthdays", it) }.getOrDefault(emptyList()) }
        links = l
        if (show && l.isNotEmpty()) reviewing = true else faces(show)
    }
    LaunchedEffect(Unit) { birthdays(false) }
    // Import: the phone's own contacts (the Google account's among them), or a file — vCard, Google's CSV, Takeout's zip
    fun importPhone() = scope.launch {
        busy = context.getString(R.string.contacts_reading_phone)
        val r = withContext(Dispatchers.IO) { runCatching { ContactsFiles.import(root, ContactsSystem.phoneCards(context) { n, of -> busy = context.getString(R.string.contacts_reading_phone_n, n, of) }) } }
        busy = null
        message = r.fold(::imported) { context.getString(R.string.contacts_could_not_read_them, it.message) }
        r.exceptionOrNull()?.let { WriterLog.e("Contacts: phone import", it) }
        version++; pushed()
        birthdays(true)
    }
    val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) importPhone() else message = context.getString(R.string.contacts_need_read) }
    fun fromPhone() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) importPhone()
        else askContacts.launch(Manifest.permission.READ_CONTACTS)
    }
    fun importFile(uri: Uri) = scope.launch {
        busy = context.getString(R.string.contacts_importing)
        val r = withContext(Dispatchers.IO) { runCatching {
            val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: ""
            ContactsFiles.import(root, ContactsImport.read(bytes, name))
        } }
        busy = null
        message = r.fold(::imported) { context.getString(R.string.contacts_could_not_read_it, it.message ?: it.javaClass.simpleName) }
        r.exceptionOrNull()?.let { WriterLog.e("Contacts: file import", it) }
        version++; pushed()
        birthdays(true)
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::importFile) }
    // the phone's copy (Tetra's account) follows every change: a save, an import, a merge, a file that came by sync
    // on only when it can be: chosen (the default since the user's yes) and Tetra may write the phone's contacts
    var systemOn by remember { mutableStateOf(ContactsAccount.on(context) && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED) }
    LaunchedEffect(version, systemOn) { ContactsAccount.soon(context) }
    val askWrite = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { ContactsAccount.set(context, true); systemOn = true; message = context.getString(R.string.contacts_system_now_on) }
        else message = context.getString(R.string.contacts_need_write)
    }
    // Export: all, the favourites or a label, as one .vcf saved where the person chooses
    val exportTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/vcard")) { uri ->
        val cs = exporting
        if (uri != null) act { context.contentResolver.openOutputStream(uri)!!.use { it.write(ContactsMerge.export(cs).toByteArray()) }; context.resources.getQuantityString(R.plurals.contacts_n_exported, cs.size, cs.size) }
    }
    val duplicates by produceState(emptyList<Pair<Contact, Contact>>(), all) { value = withContext(Dispatchers.Default) { ContactsMerge.pairs(all) } }

    val shown = remember(all, query, tab, label) {
        all.filter { c -> ContactsFiles.matches(c.info, query) && when {
            tab == ContactsTab.Favourites -> c.info.favourite
            tab == ContactsTab.Labels && label != null -> c.info.labels.any { it.equals(label, true) }
            else -> true
        } }
    }
    BackHandler(editing == null) { when { selected.isNotEmpty() -> selected = emptySet(); drawer -> drawer = false; !wide && open != null -> openUid = null; label != null -> label = null; query.isNotEmpty() -> query = ""; else -> back() } }

    val list: @Composable (Modifier) -> Unit = { m ->
        Box(m) {
            val state = rememberLazyListState()
            LazyColumn(state = state, contentPadding = PaddingValues(top = 84.dp, bottom = 120.dp), modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                if (busy != null) item { Text(busy!!, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (tab == ContactsTab.Labels && label == null && query.isEmpty()) {
                    val labels = all.flatMap { it.info.labels }.groupingBy { it }.eachCount().toSortedMap(String.CASE_INSENSITIVE_ORDER)
                    if (labels.isEmpty()) item { Hint(stringResource(R.string.contacts_no_labels)) }
                    labels.forEach { (name, n) -> item(key = "label:$name") {
                        Row(Modifier.fillMaxWidth().clickable { label = name }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Icon(painterResource(R.drawable.ic_label), contentDescription = null, tint = CONTACTS_COLOR)
                            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Text("$n", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } }
                    return@LazyColumn
                }
                if (label != null) item {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = CONTACTS_COLOR.copy(alpha = 0.18f), shape = CircleShape) {
                            Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(label!!, style = MaterialTheme.typography.labelLarge)
                                IconButton(onClick = { label = null }) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.contacts_all_contacts), Modifier.size(18.dp)) }
                            }
                        }
                    }
                }
                // the favourites' faces on top of All (the user's sketch: round faces, one tap opens)
                val favs = all.filter { it.info.favourite }
                // a grid of two rows, not a row to scroll (the user, 25 Oct); more than fit: the last cell opens Favourites
                if (tab == ContactsTab.All && query.isEmpty() && label == null && favs.isNotEmpty()) item(key = "favs") {
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        val cols = ((maxWidth + 8.dp) / 72.dp).toInt().coerceAtLeast(1)
                        val room = cols * 2
                        val cells = if (favs.size > room) favs.take(room - 1) else favs
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            (cells.map<Contact, Contact?> { it } + if (favs.size > room) listOf(null) else emptyList()).chunked(cols).forEach { row ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    row.forEach { c ->
                                        Column(Modifier.width(64.dp).clip(MaterialTheme.shapes.medium).clickable {
                                            if (c == null) tab = ContactsTab.Favourites else { openUid = c.info.uid; autoOpened = false }
                                        }, horizontalAlignment = Alignment.CenterHorizontally) {
                                            if (c != null) ContactAvatar(c.info, 56.dp)
                                            else Box(Modifier.size(56.dp).clip(CircleShape).background(CONTACTS_COLOR.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                                                Text("+${favs.size - cells.size}", color = CONTACTS_COLOR, fontWeight = FontWeight.Bold)
                                            }
                                            Text(c?.let { it.info.given.ifBlank { it.info.name } } ?: stringResource(R.string.common_all), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                    repeat(cols - row.size) { Spacer(Modifier.width(64.dp)) }
                                }
                            }
                        }
                    }
                }
                if (loaded == null) item {
                    Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = CONTACTS_COLOR)
                        Text("  " + stringResource(R.string.contacts_reading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (loaded != null && shown.isEmpty() && busy == null) item {
                    Hint(when {
                        all.isEmpty() -> stringResource(R.string.contacts_none_yet)
                        query.isNotEmpty() -> stringResource(R.string.contacts_no_match, query)
                        tab == ContactsTab.Favourites -> stringResource(R.string.contacts_no_favourites)
                        else -> stringResource(R.string.contacts_no_one)
                    })
                }
                var last = ""
                shown.forEach { c ->
                    val l = ContactsFiles.letter(c.info, byLast)
                    if (l != last && query.isEmpty()) { last = l; item(key = "h:$l") { Text(l, Modifier.padding(start = 24.dp, top = 12.dp, bottom = 2.dp), color = CONTACTS_COLOR, fontWeight = FontWeight.Bold) } }
                    item(key = c.info.uid) {
                        val picked = c.info.uid in selected
                        val chosen = picked || (wide && selected.isEmpty() && c.info.uid == openUid)
                        fun toggle() { selected = if (picked) selected - c.info.uid else selected + c.info.uid }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(MaterialTheme.shapes.large)
                            .background(if (picked) CONTACTS_COLOR.copy(alpha = 0.4f) else if (chosen) CONTACTS_COLOR.copy(alpha = 0.22f) else Color.Transparent)
                            .combinedClickable(onLongClick = ::toggle) { if (selected.isNotEmpty()) toggle() else { openUid = c.info.uid; autoOpened = false } }.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            if (picked) Box(Modifier.size(40.dp).clip(CircleShape).background(CONTACTS_COLOR), contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_check), contentDescription = stringResource(R.string.contacts_selected), tint = Color.White)
                            } else ContactAvatar(c.info, 40.dp)
                            Column(Modifier.weight(1f)) {
                                Text(c.info.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                // the first number (or e-mail, company) under the name, small and grey, as on the computer (the user, 24 Oct)
                                (c.info.phones.firstOrNull()?.value ?: c.info.emails.firstOrNull()?.value ?: c.info.org.ifBlank { null })?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            // many chosen: their count, Select all and × in the search pill's place
            if (selected.isNotEmpty()) Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                    IconButton(onClick = { selected = emptySet() }) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.contacts_done_choosing)) }
                }
                Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape, modifier = Modifier.weight(1f)) {
                    Row(Modifier.padding(start = 18.dp, end = 4.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(pluralStringResource(R.plurals.contacts_n_selected, selected.size, selected.size), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { selected = if (selected.size == shown.size) emptySet() else shown.map { it.info.uid }.toSet() }) {
                            Text(if (selected.size == shown.size) stringResource(R.string.common_none) else stringResource(R.string.contacts_all_n, shown.size), color = islandContentColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            } else
            // the search pill, floating over the list as Files' top does
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                    IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_back)) }
                }
                Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape, modifier = Modifier.weight(1f)) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(painterResource(R.drawable.ic_search), contentDescription = null, Modifier.size(22.dp))
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) Text(if (all.isEmpty()) stringResource(R.string.app_contacts) else pluralStringResource(R.plurals.contacts_search_n, all.size, all.size), overflow = TextOverflow.Ellipsis, color = islandContentColor().copy(alpha = 0.7f), maxLines = 1)
                            BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = islandContentColor(), fontSize = 16.sp),
                                cursorBrush = SolidColor(islandContentColor()), modifier = Modifier.fillMaxWidth())
                        }
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_clear)) }
                        else DictateButton({ query = it.trim() })
                    }
                }
            }
        }
    }
    val page: @Composable (Modifier) -> Unit = { m ->
        Box(m) {
            val ed = editing
            if (ed != null) ContactEditor(ed.second, ed.first == null, all.flatMap { it.info.labels }.distinctBy { it.lowercase() }, cancel = { editing = null },
                delete = ed.first?.let { c -> { editing = null; openUid = null; act {
                    ContactsFiles.trash(root, c)
                    calendarRoot().let { cr -> ContactsBirthdays.sync(cr, c.info, null, ContactsBirthdays.linked(cr)[c.info.uid].orEmpty(), gone = true) }
                    context.getString(R.string.contacts_moved_to_trash, c.info.name)
                } } },
                photo = { i, set -> ContactPhotoEdit(i, set) }) { i -> editing = null; save(ed.first, i, context.getString(if (ed.first == null) R.string.contacts_created else R.string.contacts_saved)) }
            else if (open != null) ContactPage(open, all, wide, close = { openUid = null }, edit = { editing = open to open.info },
                star = { save(open, open.info.favourite(!open.info.favourite), context.getString(if (open.info.favourite) R.string.contacts_unfavourited else R.string.contacts_favourited)) }, keep = { act { ContactsFiles.copies(root, open).forEach { f -> DriveRules.moveToTrash(root.parentFile!!, f.relativeTo(root.parentFile!!).path) }; context.getString(R.string.contacts_kept_version) } },
                openUid = { openUid = it }, openMaps = openMaps)
            else if (wide) Hint(stringResource(R.string.contacts_choose), Modifier.align(Alignment.Center))
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            if (wide) Row(Modifier.fillMaxSize()) {
                list(Modifier.width(380.dp).fillMaxHeight())
                page(Modifier.weight(1f).fillMaxHeight())
            } else if (open != null || editing != null) page(Modifier.fillMaxSize()) else list(Modifier.fillMaxSize())
            // Tetra's island at the bottom, + at the right (Files', the Calendar's); on a wide screen under the list
            if (wide || (open == null && editing == null)) Row(Modifier.align(Alignment.BottomStart).then(if (wide) Modifier.width(380.dp) else Modifier.fillMaxWidth())
                .navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (selected.isNotEmpty()) {
                    val many = all.filter { it.info.uid in selected }
                    Surface(color = islandColor(), contentColor = islandContentColor(), shape = MaterialTheme.shapes.extraLarge) {
                        Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                            IconButton(onClick = { labelling = true }) { Icon(painterResource(R.drawable.ic_label), contentDescription = stringResource(R.string.contacts_label)) }
                            IconButton(onClick = {
                                val on = !many.all { it.info.favourite }
                                act { many.forEach { c -> ContactsFiles.save(root, c, c.info.favourite(on)) }; context.resources.getQuantityString(if (on) R.plurals.contacts_n_favourited else R.plurals.contacts_n_unfavourited, many.size, many.size) }
                            }) { Icon(painterResource(if (many.all { it.info.favourite }) R.drawable.ic_favorite else R.drawable.ic_favorite_border), contentDescription = stringResource(R.string.contacts_favourites)) }
                            IconButton(onClick = {
                                runCatching {
                                    val f = File(File(context.cacheDir, "share").apply { mkdirs() }, "${many.size} contacts.vcf").apply { writeText(ContactsMerge.export(many)) }
                                    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f)
                                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/x-vcard").putExtra(Intent.EXTRA_STREAM, uri)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), context.resources.getQuantityString(R.plurals.contacts_share_n, many.size, many.size)))
                                }.onFailure { WriterLog.e("Contacts: share many", it) }
                            }) { Icon(painterResource(R.drawable.ic_share), contentDescription = stringResource(R.string.common_share)) }
                            IconButton(onClick = { exporting = many; exportTo.launch("${many.size} contacts.vcf") }) { Icon(painterResource(R.drawable.ic_file_upload), contentDescription = stringResource(R.string.common_export)) }
                            if (many.size == 2) IconButton(onClick = {
                                val (a, b) = many.sortedByDescending { it.lines.size }
                                selected = emptySet()
                                act {
                                    val kept = ContactsMerge.merge(root, a, b)
                                    calendarRoot().let { cr -> runCatching { ContactsBirthdays.moveLinks(cr, b.info.uid, kept.info); ContactsBirthdays.sync(cr, kept.info, ContactsPrefs.birthdays(context), ContactsBirthdays.linked(cr)[kept.info.uid].orEmpty()) } }
                                    openUid = kept.info.uid
                                    context.resources.getQuantityString(R.plurals.contacts_n_merged, 1, 1)
                                }
                            }) { Icon(painterResource(R.drawable.ic_cell_merge), contentDescription = stringResource(R.string.contacts_merge_two)) }
                            IconButton(onClick = { deletingMany = true }) { Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.common_delete)) }
                        }
                    }
                } else IslandBottomBar(expand = { drawer = true }, visible = !drawer) {
                    ContactsTab.entries.forEach { t -> IslandNavigationItem(stringResource(t.label), tab == t, t.icon) { tab = t; label = null } }
                }
                if (selected.isEmpty()) IslandVisibility(!drawer) {
                    Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                        IconButton(onClick = { editing = null to ContactInfo(categories = listOfNotNull(label)) }) { Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.contacts_new)) }
                    }
                }
            }
            message?.let { m ->
                Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp, start = 24.dp, end = 24.dp)) { Text(m, Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) }
            }
        }
    }
    if (drawer) TetraSheet(onDismissRequest = { drawer = false }, containerColor = SheetColor, contentColor = islandContentColor()) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.common_import), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DrawerButton(R.drawable.ic_phone, stringResource(R.string.contacts_from_phone), false, Modifier.weight(1f)) { drawer = false; fromPhone() }
                DrawerButton(R.drawable.ic_file_download, stringResource(R.string.contacts_from_file), false, Modifier.weight(1f)) {
                    drawer = false; pickFile.launch(arrayOf("text/vcard", "text/x-vcard", "text/directory", "text/csv", "text/comma-separated-values", "application/zip", "application/octet-stream", "*/*"))
                }
            }
            Text(stringResource(R.string.contacts_import_hint),
                style = MaterialTheme.typography.bodySmall, color = islandContentColor().copy(alpha = 0.7f))
            HorizontalDivider(color = islandContentColor().copy(alpha = 0.15f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DrawerButton(R.drawable.ic_file_upload, stringResource(R.string.common_export), false, Modifier.weight(1f)) { drawer = false; exportOpen = true }
                DrawerButton(R.drawable.ic_cell_merge, if (duplicates.isEmpty()) stringResource(R.string.contacts_merge_fix) else stringResource(R.string.contacts_merge_fix_n, duplicates.size), false, Modifier.weight(1f)) { drawer = false; mergeOpen = true }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DrawerButton(R.drawable.ic_sort_name, stringResource(if (byLast) R.string.contacts_sort_last else R.string.contacts_sort_first), byLast, Modifier.weight(1f)) {
                    byLast = !byLast; ContactsPrefs.setByLast(context, byLast)
                }
                DrawerButton(R.drawable.ic_delete, stringResource(R.string.contacts_trash), false, Modifier.weight(1f)) { drawer = false; trashOpen = true }
            }
            DrawerButton(R.drawable.ic_phone, stringResource(if (systemOn) R.string.contacts_system_on else R.string.contacts_system_off), systemOn, Modifier.fillMaxWidth()) {
                when {
                    systemOn -> { ContactsAccount.set(context, false); systemOn = false; message = context.getString(R.string.contacts_system_left) }
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED -> { ContactsAccount.set(context, true); systemOn = true }
                    else -> askWrite.launch(Manifest.permission.WRITE_CONTACTS)
                }
            }
            // calls from the computer are asked for in one place, Settings › Phone (the user, 25 Oct), not here
            Text(stringResource(R.string.contacts_system_hint),
                style = MaterialTheme.typography.bodySmall, color = islandContentColor().copy(alpha = 0.7f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val calName = remember(drawer) { runCatching { CalendarFiles.calendars(calendarRoot()).let { cals -> cals.firstOrNull { it.dir.name == ContactsPrefs.birthdays(context) } ?: ContactsBirthdays.existing(cals) }?.name }.getOrNull() ?: ContactsBirthdays.NAME }
                DrawerButton(R.drawable.ic_cake, stringResource(R.string.contacts_birthdays_in, calName), false, Modifier.weight(1f)) { drawer = false; choosingCalendar = true }
                if (faceMatches.isNotEmpty()) DrawerButton(R.drawable.ic_person, stringResource(R.string.contacts_faces_n, faceMatches.size), false, Modifier.weight(1f)) { drawer = false; facesOpen = true }
                if (links.isNotEmpty()) DrawerButton(R.drawable.ic_link, stringResource(R.string.contacts_link_birthdays_n, links.size), false, Modifier.weight(1f)) { drawer = false; reviewing = true }
            }
            Text(stringResource(R.string.contacts_birthdays_hint),
                style = MaterialTheme.typography.bodySmall, color = islandContentColor().copy(alpha = 0.7f))
        }
    }
    if (reviewing) LinkReview(links, { reviewing = false }) { chosen ->
        reviewing = false
        scope.launch {
            busy = context.getString(R.string.contacts_linking)
            val n = withContext(Dispatchers.IO) { runCatching {
                chosen.forEach { ContactsBirthdays.link(calendarRoot(), root, it) }
                ContactsPrefs.dismiss(context, (links - chosen.toSet()).map { it.event.uid })
                chosen.size
            }.onFailure { WriterLog.e("Contacts: link", it) }.getOrDefault(0) }
            busy = null
            version++; pushed()
            birthdays(false)
            faces(true)
            message = context.resources.getQuantityString(R.plurals.contacts_n_linked, n, n)
        }
    }
    if (choosingCalendar) {
        val cals = remember { runCatching { CalendarFiles.calendars(calendarRoot()) }.getOrDefault(emptyList()) }
        TetraSheet(onDismissRequest = { choosingCalendar = false }, containerColor = SheetColor, contentColor = islandContentColor()) {
            Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.contacts_birthdays_go_into), style = MaterialTheme.typography.titleMedium)
                val now = cals.firstOrNull { it.dir.name == ContactsPrefs.birthdays(context) } ?: ContactsBirthdays.existing(cals)
                cals.forEach { c ->
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { ContactsPrefs.setBirthdays(context, c.dir.name); choosingCalendar = false; message = context.getString(R.string.contacts_new_birthdays_into, c.name) }
                        .padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(Color(c.color)))
                        Text(c.name, Modifier.weight(1f))
                        if (c == now) Icon(painterResource(R.drawable.ic_check), contentDescription = stringResource(R.string.common_chosen))
                    }
                }
                if (cals.isEmpty()) Text(stringResource(R.string.contacts_no_calendars, ContactsBirthdays.NAME), color = islandContentColor().copy(alpha = 0.7f))
            }
        }
    }
    if (exportOpen) {
        val choices = listOf(Triple(stringResource(R.string.contacts_all_contacts), "All contacts", all), Triple(stringResource(R.string.contacts_favourites), "Favourites", all.filter { it.info.favourite })) +
            all.flatMap { it.info.labels }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }.map { l -> Triple(l, l, all.filter { c -> c.info.labels.any { it.equals(l, true) } }) }
        TetraSheet(onDismissRequest = { exportOpen = false }, containerColor = SheetColor, contentColor = islandContentColor()) {
            Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.contacts_export_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
                Text(stringResource(R.string.contacts_export_hint), style = MaterialTheme.typography.bodySmall, color = islandContentColor().copy(alpha = 0.7f))
                choices.filter { it.third.isNotEmpty() }.forEach { (name, file, cs) ->
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { exportOpen = false; exporting = cs; exportTo.launch("$file.vcf") }.padding(horizontal = 8.dp, vertical = 14.dp)) {
                        Text(name, Modifier.weight(1f)); Text("${cs.size}", color = islandContentColor().copy(alpha = 0.7f))
                    }
                }
            }
        }
    }
    if (mergeOpen) MergeSheet(duplicates, { mergeOpen = false }, merge = { pairs ->
        act {
            val calRoot = calendarRoot()
            pairs.forEach { (a, b) ->
                val kept = ContactsMerge.merge(root, a, b)
                runCatching { ContactsBirthdays.moveLinks(calRoot, b.info.uid, kept.info); ContactsBirthdays.sync(calRoot, kept.info, ContactsPrefs.birthdays(context), ContactsBirthdays.linked(calRoot)[kept.info.uid].orEmpty()) }
                    .onFailure { WriterLog.e("Contacts: a merge's dates", it) }
            }
            context.resources.getQuantityString(R.plurals.contacts_n_merged, pairs.size, pairs.size)
        }
    }, notSame = { a, b -> act { ContactsMerge.notTheSame(a, b); context.getString(R.string.contacts_kept_apart) } })
    if (facesOpen) FacesForMany(faceMatches, { facesOpen = false }) { chosen ->
        facesOpen = false
        scope.launch {
            busy = context.getString(R.string.contacts_making_photos)
            val n = withContext(Dispatchers.IO) { chosen.count { (c, p) -> runCatching {
                val (photo, face) = p.cover?.let { ContactsFaces.portrait(context, it, p.box) } ?: return@runCatching false
                val now = ContactsFiles.all(root).firstOrNull { it.info.uid == c.uid } ?: return@runCatching false
                ContactsFiles.save(root, now, now.info.copy(photo = photo, face = face, photoUrl = null, person = p.uuid)); true
            }.getOrDefault(false) } }
            busy = null
            message = context.resources.getQuantityString(R.plurals.contacts_n_photos, n, n)
            version++; pushed()
            faces(false)
        }
    }
    if (labelling) {
        val many = all.filter { it.info.uid in selected }
        LabelPick(all.flatMap { it.info.labels }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }, many, { labelling = false }) { l ->
            labelling = false
            // all of them have it: taken off; otherwise given to each
            val off = many.all { c -> c.info.labels.any { it.equals(l, true) } }
            act {
                many.forEach { c -> ContactsFiles.save(root, c, c.info.copy(categories = if (off) c.info.categories.filterNot { it.equals(l, true) } else (c.info.categories + l).distinctBy { it.lowercase() })) }
                context.getString(if (off) R.string.contacts_label_taken_off else R.string.contacts_label_given, l, many.size)
            }
        }
    }
    if (deletingMany) TetraAlertDialog(onDismissRequest = { deletingMany = false }, title = { Text(pluralStringResource(R.plurals.contacts_delete_n, selected.size, selected.size)) },
        text = { Text(stringResource(R.string.contacts_delete_many_hint)) },
        confirmButton = { TextButton(onClick = {
            deletingMany = false
            val many = all.filter { it.info.uid in selected }
            selected = emptySet(); openUid = null
            act {
                val cr = calendarRoot(); val links = ContactsBirthdays.linked(cr)
                many.forEach { c -> ContactsFiles.trash(root, c); runCatching { ContactsBirthdays.sync(cr, c.info, null, links[c.info.uid].orEmpty(), gone = true) } }
                context.resources.getQuantityString(R.plurals.contacts_n_trashed, many.size, many.size)
            }
        }) { Text(stringResource(R.string.common_delete), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        dismissButton = { TextButton(onClick = { deletingMany = false }) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) } })
    if (trashOpen) ContactsTrash(root, version, { trashOpen = false }) { c -> act { ContactsFiles.restore(root, c); context.getString(R.string.contacts_restored, c.info.name) } }
}

@Composable private fun Hint(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)

/** A contact's round face: the photo, or the initial on a soft brown (the plan's page). Decoded once per photo. */
@Composable internal fun ContactAvatar(i: ContactInfo, size: Dp) {
    val px = ContactThumbs.size(with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() })
    val photo by produceState(ContactThumbs.cached(i.photo, px), i.photo, px) { // the initial until it is decoded (off this thread)
        if (value == null && i.photo != null) value = withContext(Dispatchers.Default) { ContactThumbs.get(i.photo, px) }
    }
    val shown = photo
    if (shown != null) Image(shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape))
    else Box(Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFFD7A982), CONTACTS_COLOR, Color(0xFF8D5A3B)))), contentAlignment = Alignment.Center) {
        val first = i.name.trim().firstOrNull()?.takeIf { it.isLetterOrDigit() }
        if (first == null) Icon(painterResource(R.drawable.ic_person), contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
        else Text(first.uppercase(), color = Color.White, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Medium)
    }
}

/** "23 Oct · turns 41", "23 Oct 1985", "23 Oct"; [age] words an age (true: it is today). */
internal fun dateText(d: VDate, birthday: Boolean, today: LocalDate = LocalDate.now(), locale: Locale, age: (Int, Boolean) -> String): String {
    val day = d.inYear(today.year).format(DateTimeFormatter.ofPattern("d MMM", locale))
    if (d.year == null) return day
    val next = d.inYear(today.year).let { if (it.isBefore(today)) d.inYear(today.year + 1) else it }
    val age = next.year - d.year
    return if (birthday && age in 1..130) "$day · ${age(age, next == today)}" else "$day ${d.year}"
}

/**
 * A contact's page (the user's iPhone screenshot in Tetra's dark look): the round face, the name and job, round Message ·
 * Call · Video · Mail buttons, then the details in cards.
 */
@Composable
private fun ContactPage(c: Contact, all: List<Contact>, wide: Boolean, close: () -> Unit, edit: () -> Unit, star: () -> Unit, keep: () -> Unit, openUid: (String) -> Unit, openMaps: (String) -> Unit) {
    val context = LocalContext.current
    val i = c.info
    fun go(uri: String, action: String = Intent.ACTION_VIEW) { runCatching { context.startActivity(Intent(action, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { android.widget.Toast.makeText(context, context.getString(R.string.contacts_no_app), android.widget.Toast.LENGTH_SHORT).show() } }
    val locale = LocalConfiguration.current.locales[0]
    fun date(d: VDate, birthday: Boolean) = dateText(d, birthday, locale = locale) { a, now -> context.getString(if (now) R.string.contacts_age_today else R.string.contacts_turns, a) }
    var strip by remember { mutableStateOf<Pair<String, List<Pair<String, () -> Unit>>>?>(null) }
    var qr by remember { mutableStateOf(false) }
    var more by remember(c.info.uid) { mutableStateOf<Int?>(null) } // History or Recordings (their names' strings): See all's page
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding()
            .padding(top = 72.dp, bottom = 96.dp, start = 16.dp, end = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) { // clear of Edit at the bottom
            Column(Modifier.widthIn(max = 640.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ContactAvatar(i, 120.dp)
                Text(i.name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                if (i.job.isNotEmpty()) Text(i.job, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                // the other apps' rows for these numbers, and the calls with them (both need the phone's permissions)
                var permits by remember { mutableIntStateOf(0) }
                val ways by produceState(emptyList<AppWay>(), i.phones, permits) { value = withContext(Dispatchers.IO) { runCatching { ContactsSystem.appWays(context, i.phones.map { it.value }) }.getOrDefault(emptyList()) } }
                val calls by produceState(emptyList<CallEntry>(), i.phones, permits) { value = withContext(Dispatchers.IO) { runCatching { ContactsSystem.calls(context, i.phones.map { it.value }, limit = 50) }.getOrDefault(emptyList()) } }
                val recordings by produceState(emptyList<File>(), i.name, i.phones) {
                    value = withContext(Dispatchers.IO) { runCatching { ContactsRecordings.of(File(phoneRoot(), "Recordings").listFiles()?.toList().orEmpty(), i) }.getOrDefault(emptyList()) }
                }
                // Google Play: the call log only for the phone's phone app, so History asks for that role, not the permission (Plan.md § Phone)
                val askCalls = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { permits++ }
                fun open(w: AppWay) { runCatching {
                    val i = Intent(Intent.ACTION_VIEW).setDataAndType(android.content.ContentUris.withAppendedId(android.provider.ContactsContract.Data.CONTENT_URI, w.id), w.mime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { context.startActivity(Intent(i).setPackage(ContactsWays.packageOf(w.mime))) } catch (_: android.content.ActivityNotFoundException) { context.startActivity(i) }
                }.onFailure { android.widget.Toast.makeText(context, context.getString(R.string.contacts_app_did_not_open, w.app), android.widget.Toast.LENGTH_SHORT).show() } }
                fun sms(n: String) = go("smsto:${Uri.encode(n)}", Intent.ACTION_SENDTO)
                // Tetra the phone app: the call at once (its own call screen); else the phone app opens with the number
                fun dial(n: String) { if (!(PhonePrefs.isDialer(context) && placePhoneCall(context, n))) go("tel:${Uri.encode(n)}", Intent.ACTION_DIAL) }
                fun mail(m: String) = go("mailto:$m", Intent.ACTION_SENDTO)
                /** One way: at once; several (a number's apps, two numbers): a strip from the bottom to choose. */
                fun choose(title: String, options: List<Pair<String, () -> Unit>>) { if (options.size == 1) options.single().second() else if (options.isNotEmpty()) strip = title to options }
                fun numberText(n: String) = i.phones.firstOrNull { it.value == n }?.label?.ifEmpty { null }?.let { "${contactLabel(context, it)} $n" } ?: n
                // a number starred as the default (on the computer, TYPE=PREF): Message, Call and Video go to it alone
                val nums = i.defaultPhone?.let { d -> i.phones.filter { it.value == d } }?.ifEmpty { null } ?: i.phones
                val numWays = ways.filter { w -> nums.any { it.value == w.number } }
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    RoundAction(R.drawable.ic_chat, stringResource(R.string.contacts_message), i.phones.isNotEmpty()) {
                        choose(context.getString(R.string.contacts_message), nums.map { f -> "SMS · ${numberText(f.value)}" to { sms(f.value) } } + numWays.filter { it.way == Way.Message }.map { w -> "${w.app} · ${w.number}" to { open(w) } })
                    }
                    RoundAction(R.drawable.ic_call, stringResource(R.string.contacts_call), i.phones.isNotEmpty()) {
                        choose(context.getString(R.string.contacts_call), nums.map { f -> context.getString(R.string.contacts_by_phone, numberText(f.value)) to { dial(f.value) } } + numWays.filter { it.way == Way.Call }.map { w -> "${w.app} · ${w.number}" to { open(w) } })
                    }
                    RoundAction(R.drawable.ic_videocam, stringResource(R.string.contacts_video), numWays.any { it.way == Way.Video }) {
                        choose(context.getString(R.string.contacts_video), numWays.filter { it.way == Way.Video }.map { w -> "${w.app} · ${w.number}" to { open(w) } })
                    }
                    RoundAction(R.drawable.ic_mail, stringResource(R.string.contacts_mail), i.emails.isNotEmpty()) { choose(context.getString(R.string.contacts_mail), i.emails.map { e -> e.value to { mail(e.value) } }) }
                }
                if (c.versions > 1) DetailCard {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.contacts_versions, c.versions), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = keep) { Text(stringResource(R.string.contacts_keep_this), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
                if (i.phones.isNotEmpty()) DetailCard { i.phones.forEach { f ->
                    // each number's apps as small chips: a tap writes to them there
                    val apps = ways.filter { it.number == f.value }.groupBy { it.app }
                    val shownLabel = contactLabel(context, f.label).ifEmpty { stringResource(R.string.contacts_phone) }
                    DetailRow(if (i.phones.size > 1 && f.value == i.defaultPhone) stringResource(R.string.contacts_label_default, shownLabel) else shownLabel, f.value, onClick = { dial(f.value) }) {
                        // WhatsApp and Viber as their marks (the user, 24 Oct: "SVGs so it fits on the phone"); the others by name
                        apps.forEach { (app, ws) ->
                            val mark = when { app.startsWith("WhatsApp") -> R.drawable.ic_whatsapp; app == "Viber" -> R.drawable.ic_viber; else -> null }
                            if (mark != null) IconButton(onClick = { open(ws.firstOrNull { it.way == Way.Message } ?: ws.first()) }, modifier = Modifier.size(40.dp)) {
                                Icon(painterResource(mark), contentDescription = app, tint = Color.Unspecified, modifier = Modifier.size(28.dp))
                            } else Surface(onClick = { open(ws.firstOrNull { it.way == Way.Message } ?: ws.first()) }, shape = CircleShape, color = CONTACTS_COLOR.copy(alpha = 0.2f)) {
                                Text(app, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                } }
                if (i.emails.isNotEmpty()) DetailCard { i.emails.forEach { f -> DetailRow(contactLabel(context, f.label).ifEmpty { stringResource(R.string.contacts_email) }, f.value, onClick = { mail(f.value) }) } }
                if (i.birthday != null || i.dates.isNotEmpty()) DetailCard {
                    i.birthday?.let { DetailRow(stringResource(R.string.contacts_birthday), date(it, true)) }
                    i.dates.forEach { f -> VDate.parse(f.value)?.let { DetailRow(contactLabel(context, f.label), date(it, false)) } }
                }
                if (i.addresses.isNotEmpty()) DetailCard { i.addresses.forEach { a ->
                    DetailRow(contactLabel(context, a.label).ifEmpty { stringResource(R.string.contacts_address) }, a.text(), action = stringResource(R.string.contacts_open_in_maps), onClick = { openMaps(listOf(a.street, a.city).filter { it.isNotBlank() }.joinToString(", ").ifEmpty { a.text() }) })
                } }
                if (i.urls.isNotEmpty()) DetailCard { i.urls.forEach { f -> DetailRow(contactLabel(context, f.label).ifEmpty { stringResource(R.string.contacts_website) }, f.value, onClick = { go(if (f.value.contains("://")) f.value else "https://${f.value}") }) } }
                if (i.related.isNotEmpty()) DetailCard { i.related.forEach { f ->
                    val other = all.firstOrNull { it.info.uid != i.uid && ContactsFiles.plain(it.info.name) == ContactsFiles.plain(f.value) }
                    DetailRow(contactLabel(context, f.label).ifEmpty { stringResource(R.string.contacts_related) }, f.value, onClick = other?.let { o -> { openUid(o.info.uid) } })
                } }
                if (i.nickname.isNotEmpty() || i.labels.isNotEmpty() || i.note.isNotEmpty()) DetailCard {
                    if (i.nickname.isNotEmpty()) DetailRow(stringResource(R.string.contacts_nickname), i.nickname)
                    if (i.labels.isNotEmpty()) DetailRow(stringResource(R.string.contacts_labels), i.labels.joinToString(" · "))
                    if (i.note.isNotEmpty()) DetailRow(stringResource(R.string.contacts_notes), i.note)
                }
                // at the end, the calls with them and their recordings (the user, 25 Oct): the latest three of each, See all
                // opens their own page (the last 50 calls, every number of theirs)
                if (i.phones.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.contacts_history), if (calls.size > 3) stringResource(R.string.contacts_see_all, calls.size) else null) { more = R.string.contacts_history }
                    if (context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) DetailCard {
                        // Google Play: the call log only for the phone's phone app, so this asks for that role, not the permission (Plan.md § Phone)
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.contacts_calls_need_role, i.given.ifBlank { i.name }), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = {
                                askCalls.launch(context.getSystemService(android.app.role.RoleManager::class.java).createRequestRoleIntent(android.app.role.RoleManager.ROLE_DIALER))
                            }) { Text(stringResource(R.string.contacts_make_it), color = CONTACTS_COLOR, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    } else if (calls.isEmpty()) DetailCard { Text(stringResource(R.string.contacts_no_calls), Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    else DetailCard { calls.take(3).forEach { call -> CallRow(call, i.phones.size > 1) { dial(call.number) } } }
                }
                SectionTitle(stringResource(R.string.contacts_recordings), if (recordings.size > 3) stringResource(R.string.contacts_see_all, recordings.size) else null) { more = R.string.contacts_recordings }
                DetailCard {
                    if (recordings.isEmpty()) Text(stringResource(R.string.contacts_no_recordings), Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    recordings.take(3).forEach { f -> RecordingRow(f) }
                }
                // See all: its own page, over the contact's
                more?.let { what -> TetraDialog(onDismissRequest = { more = null }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding()
                                .padding(top = 72.dp, bottom = 24.dp, start = 16.dp, end = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("${stringResource(what)} · ${i.name}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
                                    DetailCard {
                                        if (what == R.string.contacts_history) calls.forEach { call -> CallRow(call, i.phones.size > 1) { more = null; dial(call.number) } }
                                        else recordings.forEach { f -> RecordingRow(f) }
                                    }
                                }
                            }
                            Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape,
                                modifier = Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                                IconButton(onClick = { more = null }) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = i.name) }
                            }
                        }
                    }
                } }
            }
        }
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!wide) Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                IconButton(onClick = close) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.app_contacts)) }
            }
        }
        // the contact's own buttons in one island at the bottom right, within the thumb's reach (the user, 24 Oct: Edit
        // there, then "bring the star, maybe share too"): Share · ♥ · Edit (a heart since 25 Oct: the star marks the default number)
        Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp)) {
            Row(Modifier.padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                // Share: as a .vcf (Viber, WhatsApp, mail take it) or as a QR code another phone scans
                IconButton(onClick = { strip = context.getString(R.string.contacts_share_name, i.name) to listOf(
                    context.getString(R.string.contacts_send_vcf) to {
                        runCatching {
                            val f = File(File(context.cacheDir, "share").apply { mkdirs() }, ContactsFiles.fileName(i.name, i.uid))
                            f.writeText(ContactsMerge.export(listOf(c)))
                            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f)
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/x-vcard").putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), context.getString(R.string.contacts_share_name, i.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.onFailure { WriterLog.e("Contacts: share", it) }
                        Unit
                    },
                    context.getString(R.string.contacts_show_qr) to { qr = true },
                ) }) { Icon(painterResource(R.drawable.ic_share), contentDescription = stringResource(R.string.common_share)) }
                IconButton(onClick = star) {
                    Icon(painterResource(if (i.favourite) R.drawable.ic_favorite else R.drawable.ic_favorite_border), contentDescription = stringResource(if (i.favourite) R.string.contacts_remove_favourite else R.string.contacts_add_favourite),
                        tint = if (i.favourite) CONTACTS_COLOR else islandContentColor())
                }
                Surface(onClick = edit, color = Color.White.copy(alpha = 0.12f), contentColor = islandContentColor(), shape = CircleShape, modifier = Modifier.padding(4.dp)) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(painterResource(R.drawable.ic_edit), contentDescription = null)
                        Text(stringResource(R.string.common_edit), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    strip?.let { (title, options) -> WaysStrip(title, options) { strip = null } }
    // the card without its photo (a QR code holds ~3 KB), black on white, for a phone's camera or Tetra's Codes
    if (qr) {
        val bmp = remember(c) { runCatching { qrBitmap(ContactsMerge.export(listOf(c), noPhoto = true), 720, utf8 = true).asImageBitmap() }.getOrNull() }
        TetraDialog(onDismissRequest = { qr = false }) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = Color.White, contentColor = Color.Black) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (bmp != null) Image(bmp, contentDescription = stringResource(R.string.contacts_qr_of, i.name), modifier = Modifier.size(300.dp)) else Text(stringResource(R.string.contacts_qr_too_big))
                    Text(i.name, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.contacts_qr_hint), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** Message, Call, Video or Mail with several ways: a strip from the bottom (no popup lists on a touch screen). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun WaysStrip(title: String, options: List<Pair<String, () -> Unit>>, dismiss: () -> Unit) {
    TetraSheet(onDismissRequest = dismiss, containerColor = SheetColor, contentColor = islandContentColor()) {
        Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            options.forEach { (label, act) ->
                Text(label, Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { dismiss(); act() }.padding(horizontal = 8.dp, vertical = 14.dp))
            }
        }
    }
}

@Composable private fun RoundAction(icon: Int, label: String, enabled: Boolean, click: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(onClick = click, enabled = enabled, shape = CircleShape, color = if (enabled) CONTACTS_COLOR else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(56.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), contentDescription = label) }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (enabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun DetailCard(content: @Composable () -> Unit) =
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) { content() }
    }

@Composable private fun DetailRow(label: String, value: String, action: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    // a long press copies the value (the user, 30 Oct: "when I long press a number copy it"); Android 13+ shows its own "Copied"
    val copy = {
        context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText(label, value))
        if (android.os.Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(context, R.string.photos_copied, android.widget.Toast.LENGTH_SHORT).show()
    }
    Row(Modifier.fillMaxWidth().combinedClickable(onLongClick = copy, onClick = if (onClick != null && action == null) onClick else ({})).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge, color = if (onClick != null && action == null) CONTACTS_COLOR else MaterialTheme.colorScheme.onSurface)
        }
        if (action != null && onClick != null) TextButton(onClick = onClick) { Text(action, color = CONTACTS_COLOR, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { trailing() }
    }
}

/** "Mon 15:36 · in · 2 min": a call in a few words. */
@Composable private fun callText(c: CallEntry): String {
    val at = java.time.Instant.ofEpochMilli(c.at).atZone(java.time.ZoneId.systemDefault())
    val day = if (at.toLocalDate() == LocalDate.now()) stringResource(R.string.common_today) else if (at.toLocalDate() == LocalDate.now().minusDays(1)) stringResource(R.string.common_yesterday)
        else at.format(DateTimeFormatter.ofPattern(if (at.year == LocalDate.now().year) "EEE d MMM" else "d MMM yyyy", LocalConfiguration.current.locales[0]))
    val kind = stringResource(when (c.type) {
        android.provider.CallLog.Calls.INCOMING_TYPE -> R.string.contacts_call_in; android.provider.CallLog.Calls.OUTGOING_TYPE -> R.string.contacts_call_out
        android.provider.CallLog.Calls.MISSED_TYPE -> R.string.contacts_call_missed; android.provider.CallLog.Calls.REJECTED_TYPE -> R.string.contacts_call_declined; else -> R.string.contacts_call_other
    })
    val length = if (c.seconds <= 0) "" else " · " + if (c.seconds < 60) stringResource(R.string.contacts_seconds, c.seconds) else stringResource(R.string.contacts_minutes, (c.seconds + 30) / 60)
    return "$day ${at.format(DateTimeFormatter.ofPattern("HH:mm"))} · $kind$length"
}

/** A section's name over its card on a contact's page, with See all at the right when there is more. */
@Composable private fun SectionTitle(title: String, all: String?, open: () -> Unit) =
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (all != null) TextButton(onClick = open) { Text(all, color = CONTACTS_COLOR, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }

/** A call recorded on the computer: when, its size; a tap plays it in the phone's player. */
@Composable private fun RecordingRow(f: File) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().clickable {
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { WriterLog.w("Contacts: a recording did not open (${it.message})") }
    }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(R.drawable.ic_mic), contentDescription = null, tint = CONTACTS_COLOR, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(f.nameWithoutExtension.take(16).replace('.', ':'))
            Text("${f.length() / 1024} KB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun CallRow(c: CallEntry, showNumber: Boolean, click: () -> Unit) =
    Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val missed = c.type == android.provider.CallLog.Calls.MISSED_TYPE || c.type == android.provider.CallLog.Calls.REJECTED_TYPE
        CallTypeIcon(c.type, 28.dp)
        Column(Modifier.weight(1f)) {
            Text(callText(c), color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (showNumber) Text(c.number, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

/** Contacts' part of Tetra's Trash, newest first, each with Restore. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ContactsTrash(root: File, version: Int, dismiss: () -> Unit, restore: (Contact) -> Unit) {
    val trashed by produceState(emptyList<Contact>(), version) { value = withContext(Dispatchers.IO) { runCatching { ContactsFiles.trashed(root) }.getOrDefault(emptyList()) } }
    TetraSheet(onDismissRequest = dismiss, containerColor = SheetColor, contentColor = islandContentColor()) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.contacts_trash), style = MaterialTheme.typography.titleMedium)
            if (trashed.isEmpty()) Text(stringResource(R.string.contacts_trash_empty), color = islandContentColor().copy(alpha = 0.7f))
            trashed.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ContactAvatar(c.info, 36.dp)
                    Text(c.info.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { restore(c) }) { Text(stringResource(R.string.contacts_restore), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

/**
 * The events already in the birthdays calendar that seem to be these people (Γενέθλια's from Google Calendar): each pair
 * ticked; Link adds the contact to the event (no second event), an unticked one stays as it is and is not asked again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun LinkReview(links: List<ContactsBirthdays.Link>, later: () -> Unit, go: (List<ContactsBirthdays.Link>) -> Unit) {
    var picked by remember(links) { mutableStateOf(links.toSet()) }
    TetraSheet(onDismissRequest = later, containerColor = SheetColor, contentColor = islandContentColor()) {
        Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.contacts_links_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.contacts_links_hint),
                style = MaterialTheme.typography.bodySmall, color = islandContentColor().copy(alpha = 0.7f))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                links.forEach { l ->
                    Row(Modifier.fillMaxWidth().clickable { picked = if (l in picked) picked - l else picked + l }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        androidx.compose.material3.Checkbox(l in picked, { picked = if (l in picked) picked - l else picked + l })
                        ContactAvatar(l.contact, 36.dp)
                        Column(Modifier.weight(1f)) {
                            Text(l.contact.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${l.title} · ${l.event.master?.start?.format(DateTimeFormatter.ofPattern("d MMM", LocalConfiguration.current.locales[0]))}" + if (l.sameDate) " · " + stringResource(R.string.contacts_same_day) else "",
                                style = MaterialTheme.typography.bodySmall, color = islandContentColor().copy(alpha = 0.7f), maxLines = 2)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = later) { Text(stringResource(R.string.contacts_later), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                TextButton(onClick = { go(picked.toList()) }) { Text(if (picked.isEmpty()) stringResource(R.string.contacts_none_of_them) else stringResource(R.string.contacts_link_n, picked.size), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/**
 * The editor's photo: From Photos (the faces Photos found), Choose a picture (any, its middle square), Remove (back to
 * the initial). The photo goes into the file, 512 px.
 */
@Composable private fun ContactPhotoEdit(i: ContactInfo, set: ((ContactInfo) -> ContactInfo) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fromPhotos by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    fun use(uri: Uri, box: android.graphics.Rect?, person: String?) = scope.launch {
        working = true
        val b = withContext(Dispatchers.IO) { ContactsFaces.portrait(context, uri, box) }
        working = false
        // a face from Photos keeps its person; any picture gets the contact's (a new uuid if none): the computer then finds
        // that face in its copy of the picture and puts it in Photos under this contact's name (desktop contactfaces.js)
        if (b != null) set { it.copy(photo = b.first, face = b.second, photoUrl = null, person = person ?: it.person ?: java.util.UUID.randomUUID().toString()) }
        else android.widget.Toast.makeText(context, context.getString(R.string.contacts_picture_unreadable), android.widget.Toast.LENGTH_SHORT).show()
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { use(it, null, null) } }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ContactAvatar(i, 96.dp)
        if (working) Text(stringResource(R.string.contacts_making_photo), style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = { fromPhotos = true }) { Text(stringResource(R.string.contacts_from_photos), color = CONTACTS_COLOR, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            TextButton(onClick = { pick.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text(stringResource(R.string.contacts_choose_picture), color = CONTACTS_COLOR, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (i.photo != null || i.photoUrl != null) TextButton(onClick = { set { it.copy(photo = null, photoUrl = null, face = null) } }) { Text(stringResource(R.string.common_remove), color = CONTACTS_COLOR, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
    if (fromPhotos) PhotosFacePicker(i.name, { fromPhotos = false }) { p, uri, box -> fromPhotos = false; use(uri, box, p.uuid) }
}

/**
 * Merge & fix: the same person twice, side by side, what the second adds marked; Merge keeps every value in one file (the
 * other to the Trash), Not the same remembers it in both files; Merge all at the top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun MergeSheet(pairs: List<Pair<Contact, Contact>>, dismiss: () -> Unit, merge: (List<Pair<Contact, Contact>>) -> Unit, notSame: (Contact, Contact) -> Unit) {
    TetraSheet(onDismissRequest = dismiss, containerColor = SheetColor, contentColor = islandContentColor()) {
        Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.contacts_merge_fix), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (pairs.size > 1) TextButton(onClick = { dismiss(); merge(pairs) }) { Text(stringResource(R.string.contacts_merge_all_n, pairs.size), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (pairs.isEmpty()) Text(stringResource(R.string.contacts_no_twins), color = islandContentColor().copy(alpha = 0.7f))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                pairs.forEach { (a, b) ->
                    Surface(color = Color.White.copy(alpha = 0.08f), shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                listOf(a, b).forEach { c -> Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ContactAvatar(c.info, 36.dp)
                                    Column {
                                        Text(c.info.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text((c.info.phones.map { it.value } + c.info.emails.map { it.value }).take(2).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                            color = islandContentColor().copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                } }
                            }
                            // what the second brings that the first has not (the fields that differ)
                            val m = ContactsMerge.merged(a.info, b.info)
                            val adds = (m.phones - a.info.phones.toSet()).map { it.value } + (m.emails - a.info.emails.toSet()).map { it.value } +
                                listOfNotNull(m.birthday?.takeIf { a.info.birthday == null }?.let { stringResource(R.string.contacts_birthday_on, dateToText(it)) }, stringResource(R.string.contacts_photo).takeIf { a.info.photo == null && m.photo != null }) +
                                (m.labels - a.info.labels.toSet())
                            if (adds.isNotEmpty()) Text(stringResource(R.string.contacts_adds, adds.joinToString(" · ")), style = MaterialTheme.typography.bodySmall, color = CONTACTS_COLOR)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { notSame(a, b) }) { Text(stringResource(R.string.contacts_not_same), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                TextButton(onClick = { merge(listOf(a to b)) }) { Text(stringResource(R.string.contacts_merge), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Many contacts' label: one of those in use (✓ when all the chosen have it: a tap takes it off), or a new one. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable private fun LabelPick(labels: List<String>, many: List<Contact>, dismiss: () -> Unit, pick: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    TetraSheet(onDismissRequest = dismiss, containerColor = SheetColor, contentColor = islandContentColor()) {
        Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(pluralStringResource(R.plurals.contacts_label_n, many.size, many.size), style = MaterialTheme.typography.titleMedium)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                labels.forEach { l ->
                    val all = many.all { c -> c.info.labels.any { it.equals(l, true) } }
                    androidx.compose.material3.FilterChip(all, { pick(l) }, label = { Text(l, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(selectedContainerColor = CONTACTS_COLOR, selectedLabelColor = Color.White))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text(stringResource(R.string.contacts_new_label)) }, modifier = Modifier.weight(1f))
                TextButton(enabled = name.isNotBlank(), onClick = { pick(name.trim()) }) { Text(stringResource(R.string.common_add), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

