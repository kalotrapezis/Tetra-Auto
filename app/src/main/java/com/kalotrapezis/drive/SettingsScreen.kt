package com.kalotrapezis.drive

import androidx.compose.ui.res.pluralStringResource
import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings, re-arranged (the user, 24 Oct: "re-arrange it, modernise it … it does not separate a setting from text"):
 * a list of categories, each its own page — side by side on a wide screen, one at a time on a phone. Inside a page,
 * sections under quiet headers, one row per setting (title, a line of secondary text, the control at the end) and the
 * longer explanations under their section. One accent (the theme's own); an applet's colour only tints its icon in
 * the list. [items] are the rows' titles, for the search.
 */
internal enum class SettingsPage(@androidx.annotation.StringRes val title: Int, val icon: Int, val tint: Color?, @androidx.annotation.StringRes val summary: Int, val items: List<Int>) {
    Appearance(R.string.settings_appearance, R.drawable.ic_palette, null, R.string.settings_appearance_summary, listOf(R.string.settings_theme, R.string.settings_language, R.string.settings_all_files)),
    Browser(R.string.app_browser, Category.Browser.icon, Category.Browser.color, R.string.settings_browser_summary, listOf(R.string.browser_history_keep, R.string.browser_top_bar, R.string.browser_sync_bookmarks, R.string.browser_sync_tabs)),
    Car(R.string.app_car, R.drawable.ic_directions_car, CAR_APP_COLOR, R.string.car_settings_summary, listOf(R.string.app_car, R.string.car_open, R.string.car_home, R.string.car_open_boot, R.string.car_open_bt, R.string.car_dock, R.string.car_read_sms, R.string.car_answer_calls,
        R.string.car_quiet, R.string.car_bluetooth, R.string.car_pair_car)),
    Maps(R.string.app_maps, Category.Maps.icon, Category.Maps.color, R.string.settings_maps_summary, listOf(R.string.settings_map, R.string.settings_on_foot, R.string.settings_by_car, R.string.settings_directions_in, R.string.settings_test_voice, R.string.settings_voice_settings, R.string.settings_sharp_bends,
        R.string.settings_save_parking, R.string.settings_open_at_car, R.string.settings_accessible_parking, R.string.settings_export_places, R.string.settings_import_places, R.string.settings_import_google,
        R.string.settings_installed_maps, R.string.settings_send_log)),
    Contacts(R.string.app_contacts, Category.Contacts.icon, Category.Contacts.color, R.string.settings_contacts_summary, listOf(R.string.settings_in_phone_contacts)),
    Phone(R.string.app_phone, Category.Phone.icon, Category.Phone.color, R.string.settings_phone_summary, listOf(R.string.settings_phone_app, R.string.settings_buzz_answer, R.string.settings_buzz_end,
        R.string.settings_silence_unknown, R.string.settings_decline_hidden, R.string.settings_pc_calls, R.string.settings_phone_permission, R.string.settings_open_phone)),
    Assistant(R.string.app_assistant, R.drawable.ic_chat, ASSISTANT_COLOR, R.string.settings_assistant_summary, listOf(R.string.ai_master, R.string.ai_apps, R.string.ai_memory, R.string.assistant_key, R.string.assistant_words, R.string.assistant_contact_words, R.string.assistant_default, R.string.assistant_open))

}

@Composable
internal fun SettingsTab(
    back: () -> Unit,
    start: SettingsPage? = null,
    started: () -> Unit = {},
) {
    val wide = LocalConfiguration.current.screenWidthDp >= 700
    var open by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    LaunchedEffect(start) { start?.let { open = it; started() } } // a notification's page (MapsAge: Maps)
    var query by remember { mutableStateOf("") }
    val shown = open ?: if (wide) SettingsPage.Car else null
    BackHandler { when { !wide && open != null -> open = null; query.isNotEmpty() -> query = ""; else -> back() } }

    val list: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                    IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.home_back_to_home)) }
                }
                Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape, modifier = Modifier.weight(1f)) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(painterResource(R.drawable.ic_search), contentDescription = null, Modifier.size(22.dp))
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) Text(stringResource(R.string.settings_search), color = islandContentColor().copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = islandContentColor(), fontSize = 16.sp),
                                cursorBrush = SolidColor(islandContentColor()), modifier = Modifier.fillMaxWidth())
                        }
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_clear)) }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
                @Composable fun PageRow(p: SettingsPage, summary: String) = SettingRow(stringResource(p.title), summary, onClick = { open = p; query = "" },
                    chosen = wide && p == shown, leading = { Icon(painterResource(p.icon), contentDescription = null, tint = p.tint ?: quiet(), modifier = Modifier.size(24.dp)) },
                ) { if (!wide) Chevron() }
                if (query.isBlank()) {
                    SettingsSection(stringResource(R.string.app_name)) {
                        listOf(SettingsPage.Appearance).forEach { PageRow(it, stringResource(it.summary)) }
                    }
                    SettingsSection(stringResource(R.string.settings_apps)) { SettingsPage.entries.drop(1).forEach { PageRow(it, stringResource(it.summary)) } }
                } else {
                    // the words as shown, in the language shown
                    val res = LocalContext.current.resources
                    val q = query.trim().lowercase()
                    val hits = SettingsPage.entries.flatMap { p -> p.items.map(res::getString).filter { q in it.lowercase() || q in res.getString(p.title).lowercase() }.map { p to it } }
                    SettingsSection(pluralStringResource(R.plurals.settings_found, hits.size, hits.size)) { hits.forEach { (p, item) ->
                        SettingRow(item, stringResource(p.title), onClick = { open = p; query = "" },
                            leading = { Icon(painterResource(p.icon), contentDescription = null, tint = p.tint ?: quiet(), modifier = Modifier.size(24.dp)) }) { Chevron() }
                    } }
                }
            }
        }
    }
    val page: @Composable (SettingsPage, Modifier) -> Unit = { p, m ->
        Column(m.statusBarsPadding()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!wide) Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                    IconButton(onClick = { open = null }) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.settings_back)) }
                }
                Icon(painterResource(p.icon), contentDescription = null, tint = p.tint ?: MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(28.dp))
                Text(stringResource(p.title), style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Column(Modifier.widthIn(max = 760.dp)) { when (p) {
                    SettingsPage.Appearance -> AppearancePage()
                    SettingsPage.Maps -> MapsPage()
                    SettingsPage.Car -> CarSettingsPage()
                    SettingsPage.Contacts -> ContactsPage()
                    SettingsPage.Browser -> BrowserSettingsPage()
                    SettingsPage.Phone -> PhonePage()
                    SettingsPage.Assistant -> AssistantPage()
                } }
            }
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (wide) Row(Modifier.fillMaxSize()) {
            list(Modifier.width(380.dp).fillMaxHeight())
            shown?.let { key(it) { page(it, Modifier.weight(1f).fillMaxHeight()) } }
        } else if (shown != null) key(shown) { page(shown, Modifier.fillMaxSize()) } else list(Modifier.fillMaxSize())
    }
}

// ---- the parts every page is made of ----

/** Secondary text: the content colour, quieter (the dark theme's onSurfaceVariant is as bright as onSurface). */
@Composable private fun quiet() = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.66f)
/**
 * Related people (the user, 30 Oct: "add relationships in the Assistant's settings so I can edit them"): which contact is
 * the user, and who is who to them. They live where the assistant writes them, on the user's own card (X-ABRELATEDNAMES),
 * so Contacts, Google and the computer show the same; which card is the user's is the assistant's "me" on this phone.
 */
@OptIn(ExperimentalLayoutApi::class)
/** Personal details (AssistantMemory): what the assistant was told to remember, each fact editable; the same on every device. */
@Composable private fun AssistantMemorySection(version: Int) {
    var facts by remember(version) { mutableStateOf(AssistantMemory.list(driveRoot())) }
    var adding by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    fun change(id: String, text: String) { runCatching { facts = AssistantMemory.change(driveRoot(), id, text, thisDevice()) }.onFailure { WriterLog.e("Assistant: personal details", it) } }
    val context = LocalContext.current
    var auto by remember { mutableStateOf(AssistantAutoMemory.on(context)) }
    SettingsSection(stringResource(R.string.ai_memory), stringResource(R.string.ai_memory_note)) {
        SwitchRow(stringResource(R.string.ai_memory_auto), stringResource(R.string.ai_memory_auto_note), auto) { auto = it; AssistantAutoMemory.set(context, it) }
        facts.forEach { f ->
            var text by remember(f.id, f.text) { mutableStateOf(f.text) }
            Row(Modifier.fillMaxWidth().background(rowColor()).padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                // saved when the field is left, not on every letter (the file syncs)
                OutlinedTextField(text, { text = it }, Modifier.weight(1f).onFocusChanged { if (!it.isFocused && text.trim() != f.text) change(f.id, text) })
                IconButton(onClick = { change(f.id, "") }) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_remove)) }
            }
        }
        Row(Modifier.fillMaxWidth().background(rowColor()).padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(adding, { adding = it }, Modifier.weight(1f), placeholder = { Text(stringResource(R.string.ai_memory_hint)) })
            IconButton(enabled = adding.isNotBlank(), onClick = {
                runCatching { AssistantMemory.add(driveRoot(), adding, thisDevice()) }.onSuccess { facts = AssistantMemory.list(driveRoot()); adding = ""; problem = null }.onFailure { problem = it.message }
            }) { Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.common_add)) }
        }
        problem?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun AssistantRelations() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("assistant", android.content.Context.MODE_PRIVATE) }
    var meId by remember { mutableStateOf(prefs.getString("me", null)) }
    var cards by remember { mutableStateOf(ContactsFiles.all(contactsRoot())) }
    val me = cards.firstOrNull { it.info.uid == meId }
    var pickingMe by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    fun saveRelated(related: List<VField>) = me?.let {
        runCatching { ContactsFiles.save(contactsRoot(), it, it.info.copy(related = related)) }.onFailure { e -> WriterLog.e("Assistant: related people", e) }
        cards = ContactsFiles.all(contactsRoot())
        ScopedSync.soon(context, "contacts", 0, orWhole = true); ContactsAccount.soon(context)
    }
    SettingsSection(stringResource(R.string.contacts_related_people), stringResource(R.string.assistant_relations_note)) {
        SettingRow(stringResource(R.string.assistant_me), me?.info?.name ?: stringResource(R.string.assistant_me_none), onClick = { pickingMe = true }) { Chevron() }
        me?.info?.related?.forEach { f ->
            SettingRow(contactLabel(context, f.label), f.value) {
                IconButton(onClick = { saveRelated(me.info.related - f) }) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_remove)) }
            }
        }
        if (me != null) SettingRow(stringResource(R.string.contacts_add_person), onClick = { adding = true }) { Icon(painterResource(R.drawable.ic_add), contentDescription = null) }
    }
    /** Contacts whose name has every word typed, a few: what a name field offers. */
    @Composable fun Matches(q: String, pick: (ContactInfo) -> Unit) {
        val words = ContactsFiles.plain(q).split(' ').filter { it.isNotBlank() }
        cards.map { it.info }.filter { i -> words.isNotEmpty() && words.all { w -> ContactsFiles.matches(i, w) } }.take(6).forEach { i ->
            Text(i.name, Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { pick(i) }.padding(vertical = 10.dp, horizontal = 4.dp))
        }
    }
    if (pickingMe) {
        var q by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { pickingMe = false }, title = { Text(stringResource(R.string.assistant_me_pick)) },
            text = { Column {
                fun choose(uid: String) { prefs.edit().putString("me", uid).apply(); meId = uid; pickingMe = false }
                OutlinedTextField(q, { q = it }, singleLine = true, label = { Text(stringResource(R.string.common_name)) }, modifier = Modifier.fillMaxWidth())
                Matches(q) { i -> choose(i.uid) }
                // no card of one's own (the user, 30 Oct: "I don't have a contact"): one with just the name typed
                val name = q.trim()
                if (name.isNotEmpty() && cards.none { ContactsFiles.plain(it.info.name) == ContactsFiles.plain(name) }) TextButton(onClick = {
                    runCatching { ContactsFiles.save(contactsRoot(), null, ContactInfo(formatted = name, given = name.substringBefore(' '), family = name.substringAfter(' ', ""))) }
                        .onSuccess { c -> cards = ContactsFiles.all(contactsRoot()); ScopedSync.soon(context, "contacts", 0, orWhole = true); ContactsAccount.soon(context); choose(c.info.uid) }
                        .onFailure { e -> WriterLog.e("Assistant: my card", e) }
                }) { Text(stringResource(R.string.assistant_me_new, name)) }
            } },
            confirmButton = {}, dismissButton = { TextButton(onClick = { pickingMe = false }) { Text(stringResource(R.string.common_cancel)) } })
    }
    if (adding && me != null) {
        var label by remember { mutableStateOf("Sister") }
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { adding = false }, title = { Text(stringResource(R.string.contacts_add_person)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VCard.RELATIONS.forEach { r -> FilterChip(selected = label == r, onClick = { label = r }, label = { Text(contactLabel(context, r)) }) }
                }
                // or the user's own word (κουμπάρος), as on the card in Contacts
                OutlinedTextField(if (label in VCard.RELATIONS) "" else label, { label = it }, singleLine = true, label = { Text(stringResource(R.string.assistant_relation_own)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.common_name)) }, modifier = Modifier.fillMaxWidth())
                if (cards.none { it.info.name == name }) Matches(name) { name = it.name }
            } },
            confirmButton = { TextButton(enabled = name.isNotBlank() && label.isNotBlank(), onClick = { saveRelated(me.info.related + VField(label.trim(), name.trim())); adding = false }) { Text(stringResource(R.string.common_save)) } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.common_cancel)) } })
    }
}

/** A row's own ground, a step off the page's; the gaps between rows show the page. */
@Composable private fun rowColor() = lookTint(Color(0xFF1C2221), Color(0xFFEAEFED))

/** A section: a quiet header, its rows in one rounded group, and what they need explained under it. */
@Composable private fun SettingsSection(title: String? = null, note: String? = null, content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        title?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = quiet(), modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)) }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = quiet(), modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) }
    }

/** One setting: its title, a line about it (in the error colour when [warn]), and its control at the end. */
@Composable private fun SettingRow(title: String, summary: String? = null, onClick: (() -> Unit)? = null, warn: Boolean = false, chosen: Boolean = false,
    leading: (@Composable () -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) =
    Row(Modifier.fillMaxWidth().background(if (chosen) MaterialTheme.colorScheme.primaryContainer else rowColor())
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).heightIn(min = 60.dp)
        .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (warn) MaterialTheme.colorScheme.error else quiet()) }
        }
        trailing()
    }

@Composable private fun SwitchRow(title: String, summary: String?, on: Boolean, set: (Boolean) -> Unit) =
    SettingRow(title, summary, onClick = { set(!on) }) { Switch(checked = on, onCheckedChange = set) }

/** A setting of a few values: the chosen one at the end; a tap lists them all. */
@Composable private fun <T> ChoiceRow(title: String, summary: String?, options: List<Pair<T, String>>, chosen: T, pick: (T) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    SettingRow(title, summary, onClick = { menu = true }) {
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(options.firstOrNull { it.first == chosen }?.second.orEmpty(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(painterResource(R.drawable.ic_unfold_more), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                options.forEach { (value, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; pick(value) },
                        trailingIcon = if (value == chosen) { { Icon(painterResource(R.drawable.ic_check), contentDescription = stringResource(R.string.common_chosen), modifier = Modifier.size(18.dp)) } } else null)
                }
            }
        }
    }
}

/** A setting that is a permission or a state elsewhere: how it stands, and a button to it. */
@Composable private fun StatusRow(title: String, state: String, ok: Boolean, action: String?, act: () -> Unit) =
    SettingRow(title, state, warn = !ok) { if (action != null) TextButton(onClick = act) { Text(action, maxLines = 1, overflow = TextOverflow.Ellipsis) } }

@Composable private fun Chevron() = Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = quiet(), modifier = Modifier.size(20.dp))

/** A counter that goes up each time the screen comes back (from Android's settings): states read again. */
@Composable internal fun resumes(): MutableIntState {
    val n = remember { mutableIntStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) n.intValue++ }
        lifecycle.lifecycle.addObserver(o); onDispose { lifecycle.lifecycle.removeObserver(o) }
    }
    return n
}

@Composable private fun AppearancePage() {
    val context = LocalContext.current
    SettingsSection(null, stringResource(R.string.settings_theme_note)) {
        ChoiceRow(stringResource(R.string.settings_theme), stringResource(R.string.settings_theme_summary), listOf(false to stringResource(R.string.settings_dark), true to stringResource(R.string.settings_light)), TetraLook.light) { TetraLook.set(context, it) }
    }
    SettingsSection(null, stringResource(R.string.settings_language_note)) {
        var language by remember { mutableStateOf(AppLocale.get(context)) }
        ChoiceRow(stringResource(R.string.settings_language), stringResource(R.string.settings_language_summary),
            listOf("" to "${stringResource(R.string.settings_language_system)} (${AppLocale.name(AppLocale.systemTag())})") + AppLocale.tags.map { it to AppLocale.name(it) }, language) { language = it; AppLocale.set(context, it) }
    }
    // /sdcard/Tetra: the maps the computer makes, the Browser's downloads
    val access = resumes().intValue.let { hasStorage() }
    SettingsSection(null) {
        StatusRow(stringResource(R.string.settings_all_files), stringResource(if (access) R.string.settings_granted else R.string.settings_not_granted), access,
            if (access) null else stringResource(R.string.common_allow)) { grantStorage(context) }
    }
}

internal val CAR_APP_COLOR = Color(0xFF2B3A4A)

/** Settings › Tetra Car: its icon, what it does in the car, the car's Bluetooth and the link to the car's screen. */
@Composable private fun CarSettingsPage() {
    val context = LocalContext.current
    SettingsSection(stringResource(R.string.app_car)) {
        // Tetra Car: an icon, and a choice of home screen on a head unit
        var car by remember { mutableStateOf(CarIcon.shown(context)) }
        SettingRow(stringResource(R.string.app_car), onClick = { car = !car; CarIcon.show(context, car) }, leading = {
            Icon(painterResource(R.drawable.ic_directions_car), contentDescription = null, tint = Color.White,
                modifier = Modifier.size(32.dp).background(CAR_APP_COLOR, RoundedCornerShape(8.dp)).padding(5.dp))
        }) { Switch(checked = car, onCheckedChange = { car = it; CarIcon.show(context, it) }) }
        // quiet in the car: Android's Do Not Disturb access asked for when it is switched on without it
        var quiet by remember { mutableStateOf(CarQuiet.wanted(context) && CarQuiet.allowed(context)) }
        SwitchRow(stringResource(R.string.car_quiet), stringResource(R.string.car_quiet_summary), quiet) { on ->
            quiet = on; CarQuiet.want(context, on); if (on && !CarQuiet.allowed(context)) CarQuiet.ask(context)
        }
        // which paired Bluetooth device is the car (on the phone: in the car, quiet, calls answered), or the phone (on the car's screen: Tetra Car opens)
        val unit = PhoneRelay.noCalls(context)
        run {
            var choosing by remember { mutableStateOf(false) }
            var chosen by remember { mutableStateOf(Car.cars(context)) }
            val askBt = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok -> if (ok) choosing = true }
            val bonded = remember(choosing) { carBluetoothDevices(context) }
            SettingRow(stringResource(if (unit) R.string.car_bluetooth_phone else R.string.car_bluetooth), bonded.filter { it.first in chosen }.joinToString(", ") { it.second }.ifEmpty { stringResource(if (unit) R.string.car_bluetooth_phone_none else R.string.car_bluetooth_none) }, onClick = {
                if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED) choosing = true else askBt.launch(Manifest.permission.BLUETOOTH_CONNECT)
            })
            if (choosing) AlertDialog(onDismissRequest = { choosing = false }, confirmButton = { TextButton({ choosing = false }) { Text(stringResource(R.string.common_close)) } },
                title = { Text(stringResource(if (unit) R.string.car_bluetooth_phone else R.string.car_bluetooth)) },
                text = { Column { bonded.forEach { (address, name) ->
                    val on = address in chosen
                    Row(Modifier.fillMaxWidth().clickable { chosen = if (on) chosen - address else chosen + address; Car.setCars(context, chosen) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(on, { chosen = if (on) chosen - address else chosen + address; Car.setCars(context, chosen) }); Text(name)
                    }
                } } })
        }
        // the car's own link (CarWire): the phone scans the code Tetra Car shows on the car's screen
    }
    // opening by itself: as the home screen (the surest), at start, or as the chosen Bluetooth joins
    SettingsSection(stringResource(R.string.car_open)) {
        SettingRow(stringResource(R.string.car_home), stringResource(R.string.car_home_summary), onClick = {
            if (!CarIcon.shown(context)) CarIcon.show(context, true) // the home screen is the icon's alias
            runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        })
        var boot by remember { mutableStateOf(Car.opensOnBoot(context)) }
        SwitchRow(stringResource(R.string.car_open_boot), null, boot) { on -> boot = on; Car.setOpens(context, boot = on); if (on && !Car.mayOpen(context)) Car.askOpen(context) }
        var bt by remember { mutableStateOf(Car.opensOnBluetooth(context)) }
        SwitchRow(stringResource(R.string.car_open_bt), stringResource(if (PhoneRelay.noCalls(context)) R.string.car_bluetooth_phone else R.string.car_bluetooth), bt) { on ->
            bt = on; Car.setOpens(context, bluetooth = on); if (on && !Car.mayOpen(context)) Car.askOpen(context)
        }
    }
    // where the dock sits (the user, 9 Nov: "a radio buttons group on the settings, not in the layouts")
    SettingsSection(stringResource(R.string.car_dock)) {
        var side by remember { mutableStateOf(Car.side(context)) }
        listOf(DockSide.Bottom to R.string.car_dock_bottom, DockSide.Left to R.string.car_dock_left, DockSide.Right to R.string.car_dock_right).forEach { (d, label) ->
            Row(Modifier.fillMaxWidth().clickable { side = d; Car.setSide(context, d) }.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(side == d, { side = d; Car.setSide(context, d) }); Text(stringResource(label))
            }
        }
    }
    // what happens in the car (Tetra Car open, the car's Bluetooth, or the car's screen paired): also AI Assist's car_assist
    SettingsSection(stringResource(R.string.car_settings_summary)) {
        var reads by remember { mutableStateOf(Car.reads(context)) }
        SwitchRow(stringResource(R.string.car_read_sms), null, reads) { reads = it; Car.set(context, it, null) }
        var answers by remember { mutableStateOf(Car.answers(context)) }
        SwitchRow(stringResource(R.string.car_answer_calls), null, answers) { answers = it; Car.set(context, null, it) }
    }
}

/** Settings › Browser (the user, 2 Nov: "clear history on exit 24h or never"; "the UI thing is at the top … like chrome on PC"). */
@Composable private fun BrowserSettingsPage() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("browser", Context.MODE_PRIVATE) }
    var keep by remember { mutableStateOf(prefs.getString("keep_history", BrowserRules.DAY) ?: BrowserRules.DAY) }
    var top by remember { mutableStateOf(prefs.getBoolean("top_bar", false)) }
    var side by remember { mutableStateOf(prefs.getBoolean("side_bar", true)) }
    var behind by remember { mutableStateOf(prefs.getBoolean("tab_behind", false)) }
    SettingsSection(stringResource(R.string.browser_history)) {
        ChoiceRow(stringResource(R.string.browser_history_keep), null, listOf(BrowserRules.ON_EXIT to stringResource(R.string.browser_keep_exit),
            BrowserRules.DAY to stringResource(R.string.browser_keep_day), BrowserRules.NEVER to stringResource(R.string.browser_keep_never)), keep) {
            keep = it
            prefs.edit().putString("keep_history", it).apply { if (it == BrowserRules.ON_EXIT) remove("history") }.apply() // "on leaving": nothing kept from before either
        }
    }
    // one menu, Top or Bottom, and Vertical tabs under it on a tablet only, as Contacts' (the user, 5 Nov)
    SettingsSection(null, stringResource(R.string.browser_top_bar_note)) {
        ChoiceRow(stringResource(R.string.browser_top_bar), null, listOf(true to stringResource(R.string.settings_top), false to stringResource(R.string.settings_bottom)), top) {
            top = it; prefs.edit().putBoolean("top_bar", it).apply()
        }
        if (LocalConfiguration.current.smallestScreenWidthDp >= 600)
            SwitchRow(stringResource(R.string.browser_side_bar), stringResource(R.string.browser_side_bar_note), side) { side = it; prefs.edit().putBoolean("side_bar", it).apply() }
    }
    SettingsSection {
        SwitchRow(stringResource(R.string.browser_tab_behind), stringResource(R.string.browser_tab_behind_note), behind) { behind = it; prefs.edit().putBoolean("tab_behind", it).apply() }
    }
    // what this device shares with the others through Tetra's sync (the user, 3 Nov: "options in the settings what to sync in the
    // browser"); each switch is this device's own, and off keeps what is here (BrowserSync.kt)
    var marks by remember { mutableStateOf(BrowserSync.on(context, BrowserSync.BOOKMARKS)) }
    var tabs by remember { mutableStateOf(BrowserSync.on(context, BrowserSync.TABS)) }
    SettingsSection(stringResource(R.string.browser_sync_title), stringResource(R.string.browser_sync_note)) {
        SwitchRow(stringResource(R.string.browser_sync_bookmarks), null, marks) {
            marks = it; prefs.edit().putBoolean(BrowserSync.BOOKMARKS, it).apply(); WriterLog.i("Browser sync: bookmarks ${if (it) "on" else "off"}")
        }
        SwitchRow(stringResource(R.string.browser_sync_tabs), null, tabs) { on ->
            tabs = on; prefs.edit().putBoolean(BrowserSync.TABS, on).apply(); WriterLog.i("Browser sync: tabs ${if (on) "on" else "off"}")
            // off: the others stop showing this device's tabs (an empty list, where a list was); on: written when the Browser next opens
            if (!on) Thread { runCatching {
                val root = BrowserSync.root(); val me = BrowserSync.device(context)
                if (java.io.File(root, "Tabs/$me.json").isFile && BrowserSync.writeTabs(root, me, thisDeviceName(context), emptyList())) ScopedSync.soon(context.applicationContext, "browser", orWhole = true)
            } }.start()
        }
    }
}

/**
 * Settings › Calendar (the user, 18 Oct: "We need more permisions in the settings. A calendar section with the import
 * expot"): each permission reminders need, its state and a button to it; the reminders switch; Import and Export all.
 */
@Composable private fun AssistantPage() {
    val context = LocalContext.current
    val version = resumes()
    val held = remember(version.intValue) { isDefaultAssistant(context) }
    // the master switch and the apps it may use (the user, 31 Oct); synced, so every device follows (AiGate)
    LaunchedEffect(version.intValue) { withContext(Dispatchers.IO) { AiGate.load(context) } }
    SettingsSection(note = stringResource(R.string.ai_master_note)) {
        SwitchRow(stringResource(R.string.ai_master), stringResource(if (AiGate.on) R.string.ai_master_on else R.string.ai_master_off), AiGate.on) { on ->
            runCatching { AiGate.set(AiGate.state.copy(enabled = on)) }.onFailure { WriterLog.e("AI: the switch", it) }
        }
    }
    if (AiGate.on) SettingsSection(stringResource(R.string.ai_apps), stringResource(R.string.ai_apps_note)) {
        listOf("mail" to R.string.app_mail, "calendar" to R.string.app_calendar, "notes" to R.string.app_notes, "files" to R.string.app_files, "photos" to R.string.app_photos,
            "timer" to R.string.app_timer, "contacts" to R.string.app_contacts, "phone" to R.string.app_phone, "sms" to R.string.app_sms, "maps" to R.string.app_maps, "browser" to R.string.app_browser, "pills" to R.string.app_pills, "writer" to R.string.app_words, "web" to R.string.ai_app_web).forEach { (app, name) ->
            SwitchRow(stringResource(name), null, AiGate.allows(app)) { on -> runCatching { AiGate.set(AiGate.state.copy(apps = AiGate.state.apps + (app to on))) }.onFailure { WriterLog.e("AI: an app's switch", it) } }
        }
    }
    if (AiGate.on) AssistantMemorySection(version.intValue)
    SettingsSection { AssistantKeyField() }
    // AI decorations (8 Nov): the record, the cassette, the TV with the robot, the snow and the CRT; off, the plain conversation
    var decorations by remember { mutableStateOf(AiLook.decorated(context)) }
    SettingsSection(stringResource(R.string.ai_decorations), null) {
        SwitchRow(stringResource(R.string.ai_decorations), stringResource(R.string.ai_decorations_note), decorations) { decorations = it; AiLook.set(context, it) }
        var presenter by remember { mutableStateOf(Presenter.on(context)) }
        if (decorations) SwitchRow(stringResource(R.string.ai_presenter), stringResource(R.string.ai_presenter_note), presenter) { presenter = it; Presenter.set(context, it) }
    }
    // Voxtral's context_bias (the VOICE app's terms): the user's own spellings, and the favourites' names only when switched on
    var words by remember { mutableStateOf(AssistantWords.words(context)) }
    var contactWords by remember { mutableStateOf(AssistantWords.contacts(context)) }
    SettingsSection(stringResource(R.string.assistant_words), stringResource(R.string.assistant_words_note)) {
        Box(Modifier.fillMaxWidth().background(rowColor()).padding(12.dp)) {
            OutlinedTextField(words, { words = it; AssistantWords.setWords(context, it) }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 5)
        }
        SwitchRow(stringResource(R.string.assistant_contact_words), stringResource(R.string.assistant_contact_words_note), contactWords) { contactWords = it; AssistantWords.setContacts(context, it) }
    }
    var pause by remember { mutableFloatStateOf(AssistantPause.ms(context) / 1000f) }
    SettingsSection(stringResource(R.string.assistant_pause), stringResource(R.string.assistant_pause_note)) {
        Column(Modifier.fillMaxWidth().background(rowColor()).padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(stringResource(R.string.assistant_pause_value, "%.1f".format(pause)), style = MaterialTheme.typography.bodyLarge)
            Slider(pause, { pause = Math.round(it * 2) / 2f }, valueRange = 1f..6f, steps = 9, onValueChangeFinished = { AssistantPause.set(context, (pause * 1000).toInt()) })
        }
    }
    // the voice that reads answers: Mistral's (the user: the phone's was bad and slow), the phone's kept as the free one
    var voiceName by remember { mutableStateOf(AssistantVoices.id(context)?.let { if (it == AssistantVoices.PHONE) context.getString(R.string.assistant_voice_phone) else AssistantVoices.name(context) }) }
    var picking by remember { mutableStateOf(false) }
    SettingsSection(stringResource(R.string.assistant_voice), stringResource(R.string.assistant_voice_note)) {
        SettingRow(stringResource(R.string.assistant_voice), voiceName ?: stringResource(R.string.assistant_voice_first), onClick = { picking = true }) { Chevron() }
    }
    if (picking) VoicePicker({ picking = false }) { id, name -> AssistantVoices.set(context, id, name); voiceName = name }
    AssistantRelations()
    // the money counter (the user: like a car's trip meter beside its odometer); Reset keeps the total and the past trips
    var meter by remember(version.intValue) { mutableStateOf(AssistantMeter.read(context)) }
    var resetting by remember { mutableStateOf(false) }
    fun day(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        .format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
    SettingsSection(stringResource(R.string.assistant_spent), stringResource(R.string.assistant_spent_note)) {
        SettingRow(stringResource(R.string.usage_title), onClick = { openUsage(context) }) { Chevron() }
        SettingRow(stringResource(R.string.assistant_spent_since, day(meter.since)), Bill.text(meter.trip)) {
            TextButton(onClick = { resetting = true }) { Text(stringResource(R.string.assistant_spent_reset)) }
        }
        SettingRow(stringResource(R.string.assistant_spent_total), Bill.text(meter.total))
        meter.past.forEach { SettingRow(stringResource(R.string.assistant_spent_past, day(it.from), day(it.to)), Bill.text(it.usd)) }
    }
    if (resetting) AlertDialog(onDismissRequest = { resetting = false }, title = { Text(stringResource(R.string.assistant_spent_reset_ask)) },
        confirmButton = { TextButton(onClick = { AssistantMeter.reset(context); meter = AssistantMeter.read(context); resetting = false }) { Text(stringResource(R.string.assistant_spent_reset)) } },
        dismissButton = { TextButton(onClick = { resetting = false }) { Text(stringResource(R.string.common_cancel)) } })
    // Android lets no app ask for the assistant role itself (it is not "requestable"): the user picks it on Android's page
    SettingsSection(stringResource(R.string.assistant_default), stringResource(R.string.assistant_default_note) + if (held) "" else "\n" + defaultAssistantHint(context)) {
        StatusRow(stringResource(R.string.assistant_default), stringResource(if (held) R.string.assistant_default_yes else R.string.assistant_default_no), held, stringResource(R.string.assistant_choose)) {
            openAssistantChoice(context)
        }
        SettingRow(stringResource(R.string.assistant_open), onClick = { openAssistant(context) }) { Chevron() }
    }
}


/** Mistral's voices (all pages), the phone's first: a tap chooses one and reads a sentence in it. */
@Composable private fun VoicePicker(close: () -> Unit, chosen: (String, String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reader = rememberReadAloud()
    val phone = stringResource(R.string.assistant_voice_phone)
    var voices by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var current by remember { mutableStateOf(AssistantVoices.id(context)) }
    var sample by remember { mutableStateOf<AnswerVoice?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { AssistantNet.voices(AssistantNet.key(context)) } }
            .onSuccess { voices = it }.onFailure { problem = it.shownMessage(context) ?: it.message; voices = emptyList() }
    }
    DisposableEffect(Unit) { onDispose { sample?.stop() } }
    val rows = listOf(AssistantVoices.PHONE to phone) + voices.orEmpty()
    AlertDialog(onDismissRequest = close, confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.common_done)) } },
        title = { Text(stringResource(R.string.assistant_voice)) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item { problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
                items(rows.size) { n ->
                    val (id, name) = rows[n]
                    SettingRow(name, chosen = id == current, onClick = {
                        current = id; chosen(id, name)
                        sample?.stop()
                        sample = AnswerVoice(context, reader, AssistantMeter.bill(context).part("voice"), scope).apply { say(context.getString(R.string.assistant_voice_sample)); finish() }
                    })
                }
                if (voices == null) item { Text(stringResource(R.string.common_loading), color = quiet(), modifier = Modifier.padding(12.dp)) }
            }
        })
}

/** Settings › Maps (21 Oct): what is installed, where updates come from, the directions' language and voice, parking, places. */
@Composable private fun MapsPage() {
    val mapsContext = LocalContext.current
    var mapTheme by remember { mutableStateOf(MapsPrefs.mapTheme(mapsContext)) }
    var rolling by remember { mutableStateOf(MapsPrefs.rolling(mapsContext)) }
    SettingsSection(stringResource(R.string.settings_map)) {
        ChoiceRow(stringResource(R.string.settings_map), stringResource(R.string.settings_map_summary), listOf("light" to stringResource(R.string.settings_light), "dark" to stringResource(R.string.settings_dark)), mapTheme) { mapTheme = it; MapsPrefs.setMapTheme(mapsContext, it) }
        ChoiceRow(stringResource(R.string.settings_on_foot), if (rolling) stringResource(R.string.settings_rolling_summary) else stringResource(R.string.settings_walking_summary),
            listOf(false to stringResource(R.string.settings_walking), true to stringResource(R.string.settings_rolling)), rolling) { rolling = it; MapsPrefs.setRolling(mapsContext, it) }
        var byCar by remember { mutableStateOf(if (!MapsPrefs.mainRoads(mapsContext)) "shortest" else if (MapsPrefs.noLeft(mapsContext)) "no_left" else "main") }
        ChoiceRow(stringResource(R.string.settings_by_car), stringResource(when (byCar) { "main" -> R.string.settings_main_roads_summary; "no_left" -> R.string.settings_no_left_summary
                else -> R.string.settings_shortest_summary }),
            listOf("main" to stringResource(R.string.settings_main_roads), "no_left" to stringResource(R.string.settings_no_left), "shortest" to stringResource(R.string.settings_shortest)), byCar) {
            byCar = it; MapsPrefs.setMainRoads(mapsContext, it != "shortest"); MapsPrefs.setNoLeft(mapsContext, it == "no_left") }
    }
    var lang by remember { mutableStateOf(MapsPrefs.lang(mapsContext)) }
    var bends by remember { mutableStateOf(MapsPrefs.bends(mapsContext)) }
    SettingsSection(stringResource(R.string.settings_directions), stringResource(R.string.settings_directions_note)) {
        ChoiceRow(stringResource(R.string.settings_directions_in), stringResource(R.string.settings_directions_in_summary), AppLocale.tags.map { it to AppLocale.name(it) }, lang) { lang = it; MapsPrefs.setLang(mapsContext, it) }
        val words = remember(lang) { Words.of(mapsContext, lang) }
        SettingRow(stringResource(R.string.settings_test_voice), MapsPrefs.sample(words), onClick = {
            var t: android.speech.tts.TextToSpeech? = null
            t = android.speech.tts.TextToSpeech(mapsContext.applicationContext) { ok ->
                if (ok == android.speech.tts.TextToSpeech.SUCCESS) {
                    val r = t?.setLanguage(MapsPrefs.ttsLocale(words)) ?: -2
                    if (r < 0) Toast.makeText(mapsContext, mapsContext.getString(R.string.maps_no_voice, java.util.Locale.forLanguageTag(lang).getDisplayLanguage(mapsContext.resources.configuration.locales[0])), Toast.LENGTH_LONG).show()
                    else t?.speak(MapsPrefs.sample(words), android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "test")
                }
            }
        })
        SettingRow(stringResource(R.string.settings_voice_settings), stringResource(R.string.settings_voice_settings_summary), onClick = { MapsPrefs.voiceSettings(mapsContext) }) { Chevron() }
        SwitchRow(stringResource(R.string.settings_sharp_bends), stringResource(R.string.settings_sharp_bends_summary), bends) { bends = it; MapsPrefs.setBends(mapsContext, it) }
    }
    var autoPark by remember { mutableStateOf(MapsPrefs.autoPark(mapsContext)) }
    var openAtCar by remember { mutableStateOf(MapsPrefs.openAtCar(mapsContext)) }
    var radius by remember { mutableStateOf(MapsPrefs.parkingRadius(mapsContext)) }
    SettingsSection(stringResource(R.string.settings_parking), stringResource(R.string.settings_parking_note)) {
        SwitchRow(stringResource(R.string.settings_save_parking), stringResource(R.string.settings_save_parking_summary), autoPark) { autoPark = it; MapsPrefs.setAutoPark(mapsContext, it) }
        SwitchRow(stringResource(R.string.settings_open_at_car), stringResource(R.string.settings_open_at_car_summary), openAtCar) { openAtCar = it; MapsPrefs.setOpenAtCar(mapsContext, it) }
        ChoiceRow(stringResource(R.string.settings_accessible_parking), stringResource(R.string.settings_accessible_parking_summary), MapsPrefs.RADII, radius) { radius = it; MapsPrefs.setParkingRadius(mapsContext, it) }
    }
    // Google Maps' saved places from Takeout (the user, 21 Oct: "be able to bring stuff I saved over")
    val importScope = rememberCoroutineScope()
    var imported by remember { mutableStateOf<MapsImport.Result?>(null) }
    var importing by remember { mutableStateOf(false) }
    val pickTakeout = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        importScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching {
                val name = mapsContext.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "import.zip"
                val entries = mapsContext.contentResolver.openInputStream(uri)!!.use { MapsImport.read(name, it) }
                val root = MapsFiles.root(driveRoot())
                MapsImport.into(root, MapsSearch.load(root), entries)
            } }
            importing = false
            r.onSuccess { imported = it; WriterLog.i("Maps: imported ${it.places} places, ${it.missed.size} not placed") }
                .onFailure { WriterLog.e("Maps: import", it); Toast.makeText(mapsContext, mapsContext.getString(R.string.settings_file_unreadable, it.message.orEmpty()), Toast.LENGTH_LONG).show() }
        }
    }
    // sharing: my places and the map's additions out as GeoJSON and in again (the user, 21 Oct: "μαζική εισαγωγή και εξαγωγή
    // των προσωπικών ή των δημώσιων στειχείων για διαμηρασμό και εμπλούτευση του χάρτη κάθε χρήστη")
    var exportWhat by remember { mutableStateOf<String?>(null) }
    var shared by remember { mutableStateOf<MapsShare.Imported?>(null) }
    val pickShared = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching {
                MapsShare.import(MapsFiles.root(driveRoot()), mapsContext.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) })
            } }
            r.onSuccess { shared = it; WriterLog.i("Maps: imported $it") }
                .onFailure { WriterLog.e("Maps: import of a shared file", it); Toast.makeText(mapsContext, mapsContext.getString(R.string.settings_not_places_file, it.message.orEmpty()), Toast.LENGTH_LONG).show() }
        }
    }
    SettingsSection(stringResource(R.string.settings_your_places), stringResource(R.string.settings_your_places_note)) {
        SettingRow(stringResource(R.string.settings_export_places), stringResource(R.string.settings_export_places_summary), onClick = { exportWhat = "" })
        SettingRow(stringResource(R.string.settings_import_places), stringResource(R.string.settings_import_places_summary), onClick = { pickShared.launch(arrayOf("application/geo+json", "application/json", "*/*")) })
        SettingRow(stringResource(R.string.settings_import_google), if (importing) stringResource(R.string.settings_importing) else stringResource(R.string.settings_import_google_summary),
            onClick = { if (!importing) pickTakeout.launch(arrayOf("application/zip", "text/csv", "text/comma-separated-values", "application/json", "*/*")) })
    }
    exportWhat?.let { _ -> AlertDialog(onDismissRequest = { exportWhat = null }, title = { Text(stringResource(R.string.common_export)) },
        text = { Text(stringResource(R.string.settings_export_what)) },
        confirmButton = { Row {
            listOf("mine" to stringResource(R.string.settings_my_places), "map" to stringResource(R.string.settings_map_additions), "both" to stringResource(R.string.settings_both)).forEach { (k, label) -> TextButton(onClick = {
                exportWhat = null
                runCatching {
                    val root = MapsFiles.root(driveRoot())
                    val text = MapsShare.export(if (k == "map") emptyList() else MapsFiles.places(root), MapsFiles.lists(root), if (k == "mine") emptyList() else MapsSearch.added(root))
                    val file = java.io.File(mapsContext.cacheDir, "share/tetra-places-${java.time.LocalDate.now()}.geojson").apply { parentFile?.mkdirs(); writeText(text) }
                    val uri = androidx.core.content.FileProvider.getUriForFile(mapsContext, "${mapsContext.packageName}.files", file)
                    mapsContext.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/geo+json").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), mapsContext.getString(R.string.settings_share_places)))
                }.onFailure { WriterLog.e("Maps: export", it); Toast.makeText(mapsContext, mapsContext.getString(R.string.settings_not_exported_maps, it.message.orEmpty()), Toast.LENGTH_LONG).show() }
            }) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
        } },
        dismissButton = { TextButton(onClick = { exportWhat = null }) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) } }) }
    shared?.let { r -> AlertDialog(onDismissRequest = { shared = null }, title = { Text(stringResource(R.string.settings_imported)) },
        text = { Text(stringResource(R.string.settings_imported_shared, r.places, r.added, r.skipped)) },
        confirmButton = { TextButton(onClick = { shared = null }) { Text(stringResource(R.string.common_ok), maxLines = 1, overflow = TextOverflow.Ellipsis) } }) }
    imported?.let { r -> AlertDialog(onDismissRequest = { imported = null }, title = { Text(pluralStringResource(R.plurals.settings_imported_places, r.places, r.places)) },
        text = { androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 360.dp)) {
            item { Text(pluralStringResource(R.plurals.settings_into_lists, r.lists, r.lists) + if (r.missed.isEmpty()) "" else " " + stringResource(R.string.settings_not_placed), style = MaterialTheme.typography.bodyMedium) }
            items(r.missed.size) { i -> Text("• " + r.missed[i], style = MaterialTheme.typography.bodySmall) }
        } },
        confirmButton = { TextButton(onClick = { imported = null }) { Text(stringResource(R.string.common_ok), maxLines = 1, overflow = TextOverflow.Ellipsis) } }) }
    // the maps here, one a country (desktop Tetra's map builder makes them; sync brings them), each removable
    val mapsRoot = remember { MapsFiles.root(driveRoot()) }
    var mapsVersion by remember { mutableIntStateOf(0) }
    val files by produceState<List<MapsFiles.Installed>?>(null, mapsVersion) { value = withContext(Dispatchers.IO) { MapsFiles.installed(mapsContext, mapsRoot) } }
    var removing by remember { mutableStateOf<Pair<String, String>?>(null) }
    val removeScope = rememberCoroutineScope()
    SettingsSection(stringResource(R.string.settings_installed_maps), stringResource(R.string.settings_installed_maps_note)) {
        if (files?.isEmpty() == true) SettingRow(stringResource(R.string.maps_no_map))
        // an old map says so beside its button: the computer remakes it (Settings › Maps there)
        files?.forEach { m -> SettingRow(m.name, if (m.old) m.about + "\n" + stringResource(R.string.maps_old_remake) else m.about) {
            if (m.old) Text(stringResource(R.string.maps_old), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            TextButton(onClick = { removing = m.id to m.name }) { Text(stringResource(R.string.settings_remove_map), maxLines = 1) }
        } }
    }
    removing?.let { (id, name) -> AlertDialog(onDismissRequest = { removing = null }, title = { Text(stringResource(R.string.settings_remove_map_title, name)) },
        text = { Text(stringResource(R.string.settings_remove_map_body)) },
        confirmButton = { TextButton(onClick = { removing = null; removeScope.launch { withContext(Dispatchers.IO) { MapsFiles.removeMap(mapsRoot, id) }; mapsVersion++ } }) {
            Text(stringResource(R.string.settings_remove_map), maxLines = 1) } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.common_cancel), maxLines = 1) } }) }
    // the log and the last 7 days' drive traces (MapsTrace), for a problem seen on the road (the user, 23 Oct)
    SettingsSection(stringResource(R.string.settings_problems)) {
        SettingRow(stringResource(R.string.settings_send_log), stringResource(R.string.settings_send_log_summary),
            onClick = { MapsTrace.export(mapsContext)?.let { shareDriveFile(mapsContext, it) } })
    }
}

/**
 * Settings › Contacts (the user, 24 Oct: "a Contacts card in Settings, permission once"): the copy in the phone's own
 * contacts and the calendar birthdays go into. Calls from the computer are under Phone.
 */
@Composable private fun ContactsPage() {
    val context = LocalContext.current
    val version = resumes()
    val askWrite = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) ContactsAccount.set(context, true); version.intValue++ }
    @Suppress("UNUSED_EXPRESSION") version.intValue // read again on each change
    val writes = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
    val copy = ContactsAccount.on(context) && writes
    SettingsSection(null, stringResource(R.string.settings_contacts_note)) {
        SwitchRow(stringResource(R.string.settings_in_phone_contacts), if (copy) stringResource(R.string.settings_in_phone_contacts_on)
            else stringResource(R.string.settings_in_phone_contacts_off), copy) { on ->
            when { !on -> { ContactsAccount.set(context, false); version.intValue++ }; writes -> { ContactsAccount.set(context, true); version.intValue++ }; else -> askWrite.launch(Manifest.permission.WRITE_CONTACTS) }
        }
    }
}

/** Settings › Phone: the phone app's own settings ([PhoneSettings] is the same for the Phone's menu), and calls from the computer. */
@Composable private fun PhonePage() {
    val context = LocalContext.current
    val version = resumes()
    val askRole = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { version.intValue++ }
    val askCalls = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { version.intValue++; PhoneCall.Watcher.start(context.applicationContext) }
    @Suppress("UNUSED_EXPRESSION") version.intValue
    if (PhoneRelay.noCalls(context)) SettingsSection {
        SettingRow(stringResource(R.string.settings_no_calls), stringResource(R.string.settings_no_calls_summary))
    } else {
        val dialer = PhonePrefs.isDialer(context)
        SettingsSection(stringResource(R.string.settings_phone_app)) {
            SettingRow(if (dialer) stringResource(R.string.settings_is_dialer) else stringResource(R.string.settings_not_dialer),
                if (dialer) stringResource(R.string.settings_is_dialer_summary)
                else stringResource(R.string.settings_not_dialer_summary)) {
                TextButton(onClick = {
                    if (dialer) runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    else askRole.launch(context.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_DIALER))
                }) { Text(if (dialer) stringResource(R.string.settings_change) else stringResource(R.string.settings_make_it), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            SettingRow(stringResource(R.string.settings_open_phone), stringResource(R.string.settings_open_phone_summary), onClick = { context.startActivity(Intent(context, PhoneActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Chevron() }
        }
        // the default SMS app (SmsDeliver.kt): Android lists Tetra in its choice only since it takes in SMS itself
        val sms = SmsRole.isDefault(context)
        SettingsSection(stringResource(R.string.app_sms)) {
            SettingRow(stringResource(if (sms) R.string.sms_is_default else R.string.sms_not_default),
                stringResource(if (sms) R.string.sms_is_default_summary else R.string.sms_not_default_summary)) {
                TextButton(onClick = {
                    if (sms) runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    else askRole.launch(SmsRole.request(context))
                }) { Text(if (sms) stringResource(R.string.settings_change) else stringResource(R.string.settings_make_it), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        var answer by remember { mutableStateOf(PhonePrefs.buzzAnswer(context)) }
        var end by remember { mutableStateOf(PhonePrefs.buzzEnd(context)) }
        var unknown by remember { mutableStateOf(PhonePrefs.silenceUnknown(context)) }
        var hidden by remember { mutableStateOf(PhonePrefs.declineHidden(context)) }
        SettingsSection(stringResource(R.string.settings_calls)) {
            SwitchRow(stringResource(R.string.settings_buzz_answer), stringResource(R.string.settings_buzz_answer_summary), answer) { answer = it; PhonePrefs.setBuzzAnswer(context, it) }
            SwitchRow(stringResource(R.string.settings_buzz_end), stringResource(R.string.settings_buzz_end_summary), end) { end = it; PhonePrefs.setBuzzEnd(context, it) }
            SwitchRow(stringResource(R.string.settings_silence_unknown), stringResource(R.string.settings_silence_unknown_summary), unknown) { unknown = it; PhonePrefs.setSilenceUnknown(context, it) }
            SwitchRow(stringResource(R.string.settings_decline_hidden), stringResource(R.string.settings_decline_hidden_summary), hidden) { hidden = it; PhonePrefs.setDeclineHidden(context, it) }
            var raise by remember { mutableStateOf(PhonePrefs.raiseAnswer(context)) }
            var flip by remember { mutableStateOf(PhonePrefs.flipSilence(context)) }
            SwitchRow(stringResource(R.string.phone_raise_answer), stringResource(R.string.phone_raise_answer_about), raise) { raise = it; PhonePrefs.setRaiseAnswer(context, it) }
            SwitchRow(stringResource(R.string.phone_flip_silence), stringResource(R.string.phone_flip_silence_about), flip) { flip = it; PhonePrefs.setFlipSilence(context, it) }
        }
    }
    // calls from the computer (was in Settings › Contacts): one permission for Tetra (Android's "Phone"), not one per contact
    val calls = PhoneCall.allAllowed(context)
    SettingsSection(stringResource(R.string.settings_with_computer), (if (calls) stringResource(R.string.settings_with_computer_on)
        else stringResource(R.string.settings_with_computer_off)) + " " + stringResource(R.string.settings_with_computer_sound)) {
        // the on/off (the user, 25 Oct: "the on/off was a different thing" from the permission below)
        var pc by remember { mutableStateOf(PhonePrefs.pcCalls(context)) }
        SwitchRow(stringResource(R.string.settings_pc_calls), if (pc) stringResource(R.string.settings_pc_calls_on)
            else stringResource(R.string.settings_pc_calls_off), pc) {
            pc = it; PhonePrefs.setPcCalls(context, it)
            if (it && !calls) askCalls.launch(PhoneCall.PERMISSIONS) // turned on: Android's permission asked then (the user, 25 Oct)
        }
        StatusRow(stringResource(R.string.settings_phone_permission), if (calls) stringResource(R.string.settings_phone_permission_on)
            else stringResource(R.string.settings_phone_permission_off), true, if (calls) stringResource(R.string.common_settings) else stringResource(R.string.common_allow)) {
            if (calls) runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            else askCalls.launch(PhoneCall.PERMISSIONS)
        }
    }
}

/** The phone's paired Bluetooth devices, address and name, for Settings › Tetra Car › The car's Bluetooth. */
@android.annotation.SuppressLint("MissingPermission") // asked before the list is opened
private fun carBluetoothDevices(c: Context): List<Pair<String, String>> = runCatching {
    c.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter.bondedDevices.map { it.address to (it.name ?: it.address) }.sortedBy { it.second.lowercase() }
}.getOrDefault(emptyList())
