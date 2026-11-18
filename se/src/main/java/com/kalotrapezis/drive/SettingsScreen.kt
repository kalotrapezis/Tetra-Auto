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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
/**
 * The car's build (Tetra Auto SE): only what a head unit uses (the user, 13 Nov: "the old settings that don't make sense, hide
 * them"): Tetra Car, Maps, the Browser, AI Assist and the look. No computer sync, Office, mail, photos or the phone's own calls.
 */
internal val CAR_PAGES = listOf(SettingsPage.Car, SettingsPage.Maps, SettingsPage.Browser, SettingsPage.Assistant, SettingsPage.Appearance)

internal enum class SettingsPage(@androidx.annotation.StringRes val title: Int, val icon: Int, val tint: Color?, @androidx.annotation.StringRes val summary: Int, val items: List<Int>) {
    Sync(R.string.app_sync, R.drawable.ic_sync, null, R.string.settings_sync_summary, listOf(R.string.settings_computer, R.string.settings_open_sync, R.string.settings_sync_auto, R.string.settings_listen)),
    Appearance(R.string.settings_appearance, R.drawable.ic_palette, null, R.string.settings_appearance_summary, listOf(R.string.settings_theme, R.string.settings_language, R.string.settings_photo_on_home, R.string.settings_hidden_from_home, R.string.settings_app_icons)),
    Mail(R.string.app_mail, Category.Mail.icon, Category.Mail.color, R.string.settings_mail_summary, listOf(R.string.mail_accounts, R.string.mail_add_account, R.string.mail_check, R.string.mail_undo, R.string.mail_default_app, R.string.mail_threads, R.string.mail_read_shown, R.string.mail_islands, R.string.mail_security,
        R.string.mail_remote, R.string.mail_allowed, R.string.mail_tracking, R.string.mail_link_warning, R.string.mail_risky, R.string.mail_real_address, R.string.mail_receipts)),
    Browser(R.string.app_browser, Category.Browser.icon, Category.Browser.color, R.string.settings_browser_summary, listOf(R.string.browser_history_keep, R.string.browser_top_bar, R.string.browser_sync_bookmarks, R.string.browser_sync_tabs, R.string.browser_gestures)),
    Passwords(R.string.app_passwords, R.drawable.ic_key, PASSWORDS_COLOR, R.string.pw_settings_summary, listOf(R.string.pw_autofill_title, R.string.browser_sync_passwords, R.string.pw_sync_2fa)),
    Gallery(R.string.app_gallery, Category.Gallery.icon, Category.Gallery.color, R.string.settings_gallery_summary, listOf(R.string.settings_photos_videos, R.string.settings_search_quality, R.string.settings_folders)),
    Files(R.string.app_files, Category.Files.icon, Category.Files.color, R.string.settings_files_summary, listOf(R.string.settings_drive_workspace, R.string.settings_all_files)),
    Scanner(R.string.settings_pdf_scanner, Category.Scanner.icon, Category.Scanner.color, R.string.settings_resolution, listOf(R.string.settings_resolution)),
    Office(R.string.app_office, Category.Office.icon, Category.Office.color, R.string.settings_office_summary, listOf(R.string.settings_open_docx, R.string.settings_open_sheets, R.string.settings_open_slides,
        R.string.settings_open_pdfs, R.string.settings_autosave, R.string.settings_lengths, R.string.settings_read_aloud_speed, R.string.settings_the_ribbon, R.string.settings_its_tools, R.string.settings_tab_marker)),
    Text(R.string.app_text_editor, Category.Text.icon, Category.Text.color, R.string.settings_text_summary, listOf(R.string.settings_open_txt, R.string.settings_word_wrap, R.string.settings_save_new_in)),
    Calendar(R.string.app_calendar, Category.Calendar.icon, Category.Calendar.color, R.string.settings_calendar_summary, listOf(R.string.settings_reminders_here, R.string.settings_test_reminder, R.string.settings_notifications,
        R.string.settings_exact_alarms, R.string.settings_battery, R.string.settings_hyper_island, R.string.settings_autostart, R.string.common_import, R.string.settings_export_all, R.string.settings_share_claude)),
    Timer(R.string.app_timer, Category.Timer.icon, Category.Timer.color, R.string.settings_share_claude, listOf(R.string.settings_share_claude)),
    Pills(R.string.app_pills, R.drawable.ic_pill, PILLS_COLOR, R.string.pills_settings_summary, listOf(R.string.pills_voice, R.string.pills_call_name, R.string.pills_try, R.string.pills_checks)),
    // Tetra Car (the user, 9 Nov: "you could make a car category in the settings")
    Car(R.string.app_car, R.drawable.ic_directions_car, CAR_APP_COLOR, R.string.car_settings_summary, listOf(R.string.app_car, R.string.car_open, R.string.car_home, R.string.car_open_boot, R.string.car_open_bt, R.string.car_dock, R.string.car_read_sms, R.string.car_answer_calls,
        R.string.car_quiet, R.string.car_bluetooth, R.string.car_pair_car)),
    Maps(R.string.app_maps, Category.Maps.icon, Category.Maps.color, R.string.settings_maps_summary, listOf(R.string.settings_map, R.string.settings_on_foot, R.string.settings_by_car, R.string.settings_directions_in, R.string.settings_test_voice, R.string.settings_voice_settings, R.string.settings_sharp_bends,
        R.string.settings_save_parking, R.string.settings_open_at_car, R.string.settings_accessible_parking, R.string.settings_export_places, R.string.settings_import_places, R.string.settings_import_google,
        R.string.settings_installed_maps, R.string.settings_import_map, R.string.settings_send_log)),
    Contacts(R.string.app_contacts, Category.Contacts.icon, Category.Contacts.color, R.string.settings_contacts_summary, listOf(R.string.settings_in_phone_contacts, R.string.settings_birthdays_into)),
    Phone(R.string.app_phone, Category.Phone.icon, Category.Phone.color, R.string.settings_phone_summary, listOf(R.string.settings_phone_app, R.string.settings_buzz_answer, R.string.settings_buzz_end,
        R.string.settings_silence_unknown, R.string.settings_decline_hidden, R.string.settings_pc_calls, R.string.settings_phone_permission, R.string.settings_open_phone)),
    Data(R.string.app_data, Category.Data.icon, Category.Data.color, R.string.settings_data_summary, listOf(R.string.settings_data_setup, R.string.settings_usage_access, R.string.settings_your_plan, R.string.settings_warnings, R.string.settings_roaming,
        R.string.settings_live_speed, R.string.settings_signal_drops, R.string.settings_wifi_around, R.string.settings_open_data)),
    Assistant(R.string.app_assistant, R.drawable.ic_chat, ASSISTANT_COLOR, R.string.settings_assistant_summary, listOf(R.string.ai_master, R.string.ai_apps, R.string.ai_memory, R.string.assistant_key, R.string.assistant_words, R.string.assistant_contact_words, R.string.assistant_default, R.string.assistant_open)),
}

@Composable
internal fun SettingsTab(
    back: () -> Unit,
    metadataStore: PhotoMetadataStore,
    photoPermission: String,
    photoAccess: Boolean,
    hasDriveAccess: Boolean,
    showPhotoBackdrop: Boolean,
    setShowPhotoBackdrop: (Boolean) -> Unit,
    managePhotos: () -> Unit,
    manageDrive: () -> Unit,
    openSync: () -> Unit,
    pairedDevice: Pairing?,
    forgetPairedDevice: () -> Unit,
    folders: List<DeviceFolder>,
    setFolderIncluded: (String, Boolean) -> Unit,
    openPart: (String) -> Unit,
    openGuide: () -> Unit,
    start: SettingsPage? = null,
    started: () -> Unit = {},
) {
    val wide = LocalConfiguration.current.screenWidthDp >= 700
    var open by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    LaunchedEffect(start) { start?.let { open = it; started() } } // a notification's page (MapsAge: Maps)
    var query by remember { mutableStateOf("") }
    val shown = open ?: if (!wide) null else if (CarMaps.edition) SettingsPage.Car else SettingsPage.Sync
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
                        else DictateButton({ query = it.trim() })
                    }
                }
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
                @Composable fun PageRow(p: SettingsPage, summary: String) = SettingRow(stringResource(p.title), summary, onClick = { open = p; query = "" },
                    chosen = wide && p == shown, leading = { Icon(painterResource(p.icon), contentDescription = null, tint = p.tint ?: quiet(), modifier = Modifier.size(24.dp)) },
                ) { if (!wide) Chevron() }
                if (query.isBlank() && CarMaps.edition) {
                    SettingsSection(stringResource(R.string.settings_apps)) { CAR_PAGES.forEach { PageRow(it, stringResource(it.summary)) } }
                } else if (query.isBlank()) {
                    SettingsSection(stringResource(R.string.app_name)) {
                        SettingRow(stringResource(R.string.guide_title), stringResource(R.string.guide_summary), onClick = openGuide,
                            leading = { Icon(painterResource(R.drawable.ic_school), contentDescription = null, tint = quiet(), modifier = Modifier.size(24.dp)) }) { if (!wide) Chevron() }
                        listOf(SettingsPage.Sync, SettingsPage.Appearance).forEach { PageRow(it, stringResource(it.summary)) }
                    }
                    SettingsSection(stringResource(R.string.settings_apps)) { SettingsPage.entries.drop(2).forEach { PageRow(it, stringResource(it.summary)) } }
                } else {
                    // the words as shown, in the language shown
                    val res = LocalContext.current.resources
                    val q = query.trim().lowercase()
                    val hits = (if (CarMaps.edition) CAR_PAGES else SettingsPage.entries).flatMap { p -> p.items.map(res::getString).filter { q in it.lowercase() || q in res.getString(p.title).lowercase() }.map { p to it } }
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
                    SettingsPage.Sync -> SyncPage(pairedDevice, forgetPairedDevice, openSync)
                    SettingsPage.Appearance -> AppearancePage(showPhotoBackdrop, setShowPhotoBackdrop, openPart)
                    SettingsPage.Gallery -> GalleryPage(photoPermission, photoAccess, managePhotos, metadataStore, folders, setFolderIncluded)
                    SettingsPage.Files -> FilesPage(hasDriveAccess, manageDrive)
                    SettingsPage.Scanner -> ScannerPage()
                    SettingsPage.Office -> OfficePage()
                    SettingsPage.Text -> TextEditorPage()
                    SettingsPage.Calendar -> CalendarPage()
                    SettingsPage.Timer -> SettingsSection("Claude") { SkillRows("tetra-timer", "Timer", stringResource(R.string.settings_timer_skill)) }
                    SettingsPage.Maps -> MapsPage()
                    SettingsPage.Car -> CarSettingsPage()
                    SettingsPage.Contacts -> ContactsPage()
                    SettingsPage.Mail -> MailPage(openPart)
                    SettingsPage.Browser -> BrowserSettingsPage()
                    SettingsPage.Pills -> PillsSettingsPage()
                    SettingsPage.Passwords -> PasswordsSettingsPage()
                    SettingsPage.Phone -> PhonePage()
                    SettingsPage.Data -> DataPage()
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
    fun change(id: String, text: String) { runCatching { facts = AssistantMemory.change(driveRoot(), id, text, MailEngine.device()) }.onFailure { WriterLog.e("Assistant: personal details", it) } }
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
                runCatching { AssistantMemory.add(driveRoot(), adding, MailEngine.device()) }.onSuccess { facts = AssistantMemory.list(driveRoot()); adding = ""; problem = null }.onFailure { problem = it.message }
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

/** Share with Claude, as two rows: the skill to the share sheet, or saved in Files. */
@Composable private fun SkillRows(skill: String, part: String, what: String) {
    val context = LocalContext.current
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) runCatching { context.contentResolver.openOutputStream(uri)!!.use { it.write(skillZip(context, skill)) } }
    }
    SettingRow(stringResource(R.string.settings_share_claude), what, onClick = { shareSkill(context, skill, part) }) { Icon(painterResource(R.drawable.ic_share), contentDescription = null, tint = quiet(), modifier = Modifier.size(20.dp)) }
    SettingRow(stringResource(R.string.settings_save_to_files), stringResource(R.string.settings_skill_zip), onClick = { save.launch("$skill.zip") })
}

// ---- the pages ----

@Composable private fun SyncPage(paired: Pairing?, forget: () -> Unit, openSync: () -> Unit) {
    val c = LocalContext.current
    SettingsSection(stringResource(R.string.settings_computer), stringResource(R.string.settings_computer_note)) {
        SettingRow(paired?.name ?: stringResource(R.string.settings_no_computer), paired?.hosts?.firstOrNull()) { if (paired != null) TextButton(onClick = forget) { Text(stringResource(R.string.settings_forget), maxLines = 1, overflow = TextOverflow.Ellipsis) } }
        SettingRow(stringResource(R.string.settings_open_sync), stringResource(R.string.settings_open_sync_summary), onClick = openSync) { Chevron() }
    }
    SettingsSection(stringResource(R.string.settings_automatic), stringResource(R.string.settings_automatic_note)) {
        var every by remember { mutableStateOf(SyncJob.minutes(c)) }
        ChoiceRow(stringResource(R.string.settings_sync_auto), stringResource(R.string.settings_sync_auto_summary), SyncJob.choices.map { (m, label) -> m to stringResource(label) }, every) { every = it; SyncJob.setMinutes(c, it) }
        var listen by remember { mutableStateOf(SyncListenService.enabled(c)) }
        SwitchRow(stringResource(R.string.settings_listen),
            if (listen) stringResource(R.string.settings_listen_on)
            else stringResource(R.string.settings_listen_off), listen) { listen = it; SyncListenService.setEnabled(c, it) }
    }
}

@Composable private fun AppearancePage(photoBackdrop: Boolean, setPhotoBackdrop: (Boolean) -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    SettingsSection(null, stringResource(R.string.settings_theme_note)) {
        ChoiceRow(stringResource(R.string.settings_theme), stringResource(R.string.settings_theme_summary), listOf(false to stringResource(R.string.settings_dark), true to stringResource(R.string.settings_light)), TetraLook.light) { TetraLook.set(context, it) }
    }
    SettingsSection(null, stringResource(R.string.settings_language_note)) {
        var language by remember { mutableStateOf(AppLocale.get(context)) }
        ChoiceRow(stringResource(R.string.settings_language), stringResource(R.string.settings_language_summary),
            listOf("" to "${stringResource(R.string.settings_language_system)} (${AppLocale.name(AppLocale.systemTag())})") + AppLocale.tags.map { it to AppLocale.name(it) }, language) { language = it; AppLocale.set(context, it) }
    }
    // what is hidden from Home on this device, each still opened here or shown again
    val wide = LocalConfiguration.current.screenWidthDp >= 700
    var layout by remember(wide) { mutableStateOf(HomeLayout.load(context, wide)) }
    val hidden = layout.filter { it.hidden }
    // the car's build has no phone home screen and no app icons to switch (their components are not in its manifest)
    if (!CarMaps.edition) SettingsSection(stringResource(R.string.app_home), if (hidden.isEmpty()) null else stringResource(R.string.settings_hidden_note)) {
        SwitchRow(stringResource(R.string.settings_photo_on_home), stringResource(R.string.settings_photo_on_home_summary), photoBackdrop, setPhotoBackdrop)
        if (hidden.isEmpty()) SettingRow(stringResource(R.string.settings_hidden_from_home), stringResource(R.string.settings_nothing_hidden))
        hidden.forEach { item ->
            SettingRow(HomeLayout.NAMES[item.id]?.let { stringResource(it) } ?: item.id, stringResource(R.string.settings_hidden_here)) {
                if (item.id == "scanner") { TextButton({ open("scanner") }) { Text(stringResource(R.string.app_scanner), maxLines = 1, overflow = TextOverflow.Ellipsis) }; TextButton({ open("codes") }) { Text(stringResource(R.string.app_codes), maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                else TextButton({ open(item.id) }) { Text(stringResource(R.string.common_open), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                TextButton({ layout = HomeLayout.change(layout, item.copy(hidden = false, col = -1, row = -1), HomeLayout.columns(wide)); HomeLayout.save(context, wide, layout) }) { Text(stringResource(R.string.common_show), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
    // a launcher icon for each part (asked 17 Oct): on the home screen and in the app list, opening straight into it
    if (!CarMaps.edition) SettingsSection(stringResource(R.string.settings_app_icons), stringResource(R.string.settings_app_icons_note)) {
        Applet.entries.forEach { a ->
            var on by remember { mutableStateOf(a.shown(context)) }
            SettingRow(stringResource(a.label), onClick = { on = !on; a.show(context, on) }, leading = { Image(painterResource(a.art), contentDescription = null, modifier = Modifier.size(32.dp)) }) {
                Switch(checked = on, onCheckedChange = { on = it; a.show(context, it) })
            }
        }
    }
}

internal val CAR_APP_COLOR = Color(0xFF1E5AA8) // dark blue: the slate before was black on black in the dark look (the user, 15 Nov)

/** Settings › Tetra Car: its icon, what it does in the car, the car's Bluetooth and the link to the car's screen. */
@Composable private fun CarSettingsPage() {
    val context = LocalContext.current
    val onUnit = CarMaps.edition || PhoneRelay.noCalls(context) // the car's screen: its own Tetra Car, paired with the phone, not the phone's settings for a car
    SettingsSection(stringResource(R.string.app_car)) {
        // Tetra Car: an icon, and a choice of home screen on a head unit
        var car by remember { mutableStateOf(CarIcon.shown(context)) }
        if (!CarMaps.edition) SettingRow(stringResource(R.string.app_car), onClick = { car = !car; CarIcon.show(context, car) }, leading = {
            Icon(painterResource(R.drawable.ic_directions_car), contentDescription = null, tint = Color.White,
                modifier = Modifier.size(32.dp).background(CAR_APP_COLOR, RoundedCornerShape(8.dp)).padding(5.dp))
        }) { Switch(checked = car, onCheckedChange = { car = it; CarIcon.show(context, it) }) }
        // quiet in the car: Android's Do Not Disturb access asked for when it is switched on without it
        var quiet by remember { mutableStateOf(CarQuiet.wanted(context) && CarQuiet.allowed(context)) }
        SwitchRow(stringResource(R.string.car_quiet), stringResource(R.string.car_quiet_summary), quiet) { on ->
            quiet = on; CarQuiet.want(context, on); if (on && !CarQuiet.allowed(context)) CarQuiet.ask(context)
        }
        // which paired Bluetooth device is the car (on the phone: in the car, quiet, calls answered), or the phone (on the car's screen: Tetra Car opens)
        val unit = onUnit
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
        if (!onUnit) SettingRow(stringResource(R.string.car_pair_car), CarWire.link(context)?.let { stringResource(R.string.codes_paired, it.name) } ?: stringResource(R.string.car_pair_car_summary),
            onClick = { openApplet(context, Applet.Codes.name) })
    }
    // Tetra Auto: the car's screen shows Tetra Car as this phone draws it (the user, 10 Nov: "the phone is the server and the car a dumb client")
    if (!onUnit) SettingsSection(stringResource(R.string.auto_title), stringResource(R.string.auto_settings_note)) {
        var cars by remember { mutableStateOf(AutoCars.all(context)) }
        cars.forEach { car -> SettingRow(car.name, stringResource(R.string.auto_paired)) { TextButton({ AutoCars.forget(context, car); cars = AutoCars.all(context) }) { Text(stringResource(R.string.settings_forget), maxLines = 1) } } }
        if (cars.isNotEmpty()) SettingRow(stringResource(R.string.auto_show_now), stringResource(R.string.auto_show_now_summary), onClick = { AutoProjection.start(context) })
    }
    // opening by itself: as the home screen (the surest), at start, or as the chosen Bluetooth joins
    SettingsSection(stringResource(R.string.car_open)) {
        SettingRow(stringResource(R.string.car_home), stringResource(R.string.car_home_summary), onClick = {
            if (!CarMaps.edition && !CarIcon.shown(context)) CarIcon.show(context, true) // the home screen is the icon's alias (the car's build: Tetra Car itself)
            runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        })
        var boot by remember { mutableStateOf(Car.opensOnBoot(context)) }
        SwitchRow(stringResource(R.string.car_open_boot), null, boot) { on -> boot = on; Car.setOpens(context, boot = on); if (on && !Car.mayOpen(context)) Car.askOpen(context) }
        var bt by remember { mutableStateOf(Car.opensOnBluetooth(context)) }
        SwitchRow(stringResource(R.string.car_open_bt), stringResource(if (onUnit) R.string.car_bluetooth_phone else R.string.car_bluetooth), bt) { on ->
            bt = on; Car.setOpens(context, bluetooth = on); if (on && !Car.mayOpen(context)) Car.askOpen(context)
        }
    }
    // where the dock sits (the user, 9 Nov: "a radio buttons group on the settings, not in the layouts")
    SettingsSection(stringResource(R.string.car_dock)) {
        var side by remember { mutableStateOf(Car.side(context)) }
        listOf(DockSide.Auto to R.string.car_dock_auto, DockSide.Bottom to R.string.car_dock_bottom, DockSide.Left to R.string.car_dock_left, DockSide.Right to R.string.car_dock_right).forEach { (d, label) ->
            Row(Modifier.fillMaxWidth().clickable { side = d; Car.setSide(context, d) }.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(side == d, { side = d; Car.setSide(context, d) }); Text(stringResource(label))
            }
        }
        if (CarMaps.edition) {
            var size by remember { mutableStateOf(CarSize.of(context)) }
            ChoiceRow(stringResource(R.string.car_screen_size), stringResource(R.string.car_screen_size_summary), CarSize.STEPS.map { it to "${(it * 100).toInt()} %" }, size) {
                size = it; CarSize.set(context, it); (context as? android.app.Activity)?.recreate()
            }
        }
        // the driver's seat (left-hand or right-hand drive): where Auto puts the dock on a wide screen
        var right by remember { mutableStateOf(Car.driverRight(context)) }
        ChoiceRow(stringResource(R.string.car_driver), stringResource(R.string.car_driver_summary), listOf(false to stringResource(R.string.words_left), true to stringResource(R.string.words_right)), right) {
            right = it; Car.setDriverRight(context, it)
        }
    }
    // the car build's GPS: its own or the phone's, and both side by side (the user, 14 Nov: "add a test page, not on the map")
    if (CarMaps.edition) SettingsSection(stringResource(R.string.car_gps)) {
        var phone by remember { mutableStateOf(CarGps.phone(context)) }
        ChoiceRow(stringResource(R.string.car_gps_source), stringResource(R.string.car_gps_source_summary),
            listOf(false to stringResource(R.string.car_gps_car), true to stringResource(R.string.car_gps_phone)), phone) { phone = it; CarGps.setPhone(context, it) }
        var testing by remember { mutableStateOf(false) }
        SettingRow(stringResource(R.string.car_gps_test), stringResource(R.string.car_gps_test_summary), onClick = { testing = true })
        if (testing) androidx.compose.ui.window.Dialog({ testing = false }, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) { CarGpsTest { testing = false } }
    }
    // what happens in the car (Tetra Car open, the car's Bluetooth, or the car's screen paired): also AI Assist's car_assist
    SettingsSection(stringResource(R.string.car_settings_summary)) {
        var reads by remember { mutableStateOf(Car.reads(context)) }
        SwitchRow(stringResource(R.string.car_read_sms), null, reads) { reads = it; Car.set(context, it, null) }
        var answers by remember { mutableStateOf(Car.answers(context)) }
        SwitchRow(stringResource(R.string.car_answer_calls), null, answers) { answers = it; Car.set(context, null, it) }
    }
}

@Composable private fun GalleryPage(permission: String, granted: Boolean, manage: () -> Unit, store: PhotoMetadataStore, folders: List<DeviceFolder>, setIncluded: (String, Boolean) -> Unit) {
    SettingsSection(stringResource(R.string.settings_access)) { StatusRow(stringResource(R.string.settings_photos_videos), permission, granted, stringResource(R.string.common_settings), manage) }
    var quality by remember { mutableStateOf(store.searchQuality()) }
    SettingsSection(stringResource(R.string.common_search), stringResource(R.string.settings_search_note)) {
        ChoiceRow(stringResource(R.string.settings_search_quality), if (quality == PhotoSearchQuality.Fast) stringResource(R.string.settings_search_fast_summary) else stringResource(R.string.settings_search_advanced_summary),
            listOf(PhotoSearchQuality.Fast to stringResource(R.string.settings_search_fast), PhotoSearchQuality.Advanced to stringResource(R.string.settings_search_advanced)), quality) { quality = it; store.setSearchQuality(it) }
    }
    // every folder you have answered about, while the phone can still see it; a new one asks first, in Help organize
    SettingsSection(stringResource(R.string.settings_folders), stringResource(R.string.settings_folders_note)) {
        val answered = folders.filter { it.included != null }
        answered.forEach { f -> SwitchRow(f.name, pluralStringResource(R.plurals.settings_folder_count, f.entries.size, f.entries.size), f.included == true) { setIncluded(f.name, it) } }
        if (answered.isEmpty()) SettingRow(stringResource(R.string.settings_no_folders), stringResource(R.string.settings_no_folders_summary))
    }
}

@Composable private fun FilesPage(access: Boolean, manage: () -> Unit) {
    SettingsSection(stringResource(R.string.settings_storage), stringResource(R.string.settings_storage_note)) {
        SettingRow(stringResource(R.string.settings_drive_workspace), DRIVE_PATH)
    }
    SettingsSection(stringResource(R.string.settings_access)) { StatusRow(stringResource(R.string.settings_all_files), if (access) stringResource(R.string.settings_granted) else stringResource(R.string.settings_not_granted), access, stringResource(R.string.common_settings), manage) }
}

@Composable private fun ScannerPage() {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("scanner", Context.MODE_PRIVATE) }
    var dpi by remember { mutableStateOf(prefs.getInt(SCANNER_DPI, 300)) }
    SettingsSection(stringResource(R.string.settings_saved_pages), stringResource(R.string.settings_saved_pages_note)) {
        ChoiceRow(stringResource(R.string.settings_resolution), stringResource(R.string.settings_resolution_summary), listOf(300 to "300 dpi", 200 to "200 dpi"), dpi) { dpi = it; prefs.edit().putInt(SCANNER_DPI, it).apply() }
    }
}

@Composable private fun OfficePage() {
    val context = LocalContext.current
    val office = remember { TextEditorStore(context) }
    var opens by remember { mutableStateOf(office.opensOffice) }
    var sheets by remember { mutableStateOf(office.opensSheets) }
    var slides by remember { mutableStateOf(office.opensSlides) }
    var pdfs by remember { mutableStateOf(office.opensPdfs) }
    // Tetra in other apps' Open with (asked 16 Oct): Android's own Always there makes it the default
    SettingsSection(stringResource(R.string.settings_open_from_files), stringResource(R.string.settings_office_open_note)) {
        SwitchRow(stringResource(R.string.settings_open_docx), if (opens) stringResource(R.string.settings_open_docx_on) else stringResource(R.string.settings_open_docx_off), opens) { opens = it; office.opensOffice = it }
        SwitchRow(stringResource(R.string.settings_open_sheets), if (sheets) stringResource(R.string.settings_open_sheets_on) else stringResource(R.string.settings_open_sheets_off), sheets) { sheets = it; office.opensSheets = it }
        SwitchRow(stringResource(R.string.settings_open_slides), if (slides) stringResource(R.string.settings_open_slides_on) else stringResource(R.string.settings_open_slides_off), slides) { slides = it; office.opensSlides = it }
        SwitchRow(stringResource(R.string.settings_open_pdfs), if (pdfs) stringResource(R.string.settings_open_pdfs_on) else stringResource(R.string.settings_open_pdfs_off), pdfs) { pdfs = it; office.opensPdfs = it }
    }
    SettingsSection(stringResource(R.string.settings_editing)) {
        var auto by remember { mutableStateOf(office.autoSave) }
        SwitchRow(stringResource(R.string.settings_autosave), if (auto) stringResource(R.string.settings_autosave_on)
            else stringResource(R.string.settings_autosave_off), auto) { auto = it; office.autoSave = it }
        LaunchedEffect(Unit) { WriterUnits.load(context) }
        ChoiceRow(stringResource(R.string.settings_lengths), stringResource(R.string.settings_lengths_summary), listOf(false to stringResource(R.string.settings_centimetres), true to stringResource(R.string.settings_inches)), WriterUnits.inches) { WriterUnits.set(context, it) }
        var speed by remember { mutableStateOf(office.readAloudSpeed) }
        ChoiceRow(stringResource(R.string.settings_read_aloud_speed), stringResource(R.string.settings_read_aloud_summary), listOf(0.75f to "0.75×", 1f to "1×", 1.25f to "1.25×", 1.5f to "1.5×", 2f to "2×"), speed) { speed = it; office.readAloudSpeed = it }
    }
    // the ribbon's place and size for Words, Tables and Slides (asked 16 Oct: one Office section; File › Settings opens the same)
    SettingsSection(stringResource(R.string.settings_ribbon), stringResource(R.string.settings_ribbon_note)) {
        ChoiceRow(stringResource(R.string.settings_the_ribbon), stringResource(R.string.settings_the_ribbon_summary), listOf("auto" to stringResource(R.string.settings_by_itself), "bottom" to stringResource(R.string.settings_bottom), "top" to stringResource(R.string.settings_top)), OfficeUi.place) { OfficeUi.set(context, place = it) }
        ChoiceRow(stringResource(R.string.settings_its_tools), stringResource(R.string.settings_its_tools_summary), listOf("auto" to stringResource(R.string.settings_by_itself), "normal" to stringResource(R.string.settings_normal), "compact" to stringResource(R.string.settings_compact)), OfficeUi.size) { OfficeUi.set(context, size = it) }
        ChoiceRow(stringResource(R.string.settings_tab_marker), stringResource(R.string.settings_tab_marker_summary), listOf("bubble" to stringResource(R.string.settings_bubble), "dash" to stringResource(R.string.settings_dash)), OfficeUi.marker) { OfficeUi.set(context, marker = it) }
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
    // the Browser's gestures (the user, 13 Nov), each switched here or in their guide, which opens again from here
    var gestures by remember { mutableStateOf(BrowserGesture.onSet(prefs)) }
    var guide by remember { mutableStateOf(false) }
    SettingsSection(stringResource(R.string.browser_gestures)) {
        BrowserGesture.entries.forEach { g ->
            SwitchRow(stringResource(g.title), stringResource(g.text), g in gestures) { on ->
                gestures = if (on) gestures + g else gestures - g; prefs.edit().putBoolean(g.key, on).apply(); WriterLog.i("Browser: gesture ${g.name} ${if (on) "on" else "off"}")
            }
        }
        SettingRow(stringResource(R.string.browser_gestures_guide), onClick = { guide = true }) { Chevron() }
    }
    if (guide) BrowserGesturesGuide(prefs) { guide = false; gestures = BrowserGesture.onSet(prefs) }
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
                if (java.io.File(root, "Tabs/$me.json").isFile && BrowserSync.writeTabs(root, me, DataFiles.deviceName(context), emptyList())) ScopedSync.soon(context.applicationContext, "browser", orWhole = true)
            } }.start()
        }
    }
}

/**
 * Passwords (the user, 4 Nov: "make a category for the passwords app in the main settings page… all the others have a
 * section"): Android's autofill, and sync of the passwords and the 2FA codes (from Browser › Sync, where it was first).
 */
@Composable private fun PasswordsSettingsPage() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("browser", Context.MODE_PRIVATE) }
    val back = resumes() // back from Android's question: read again
    val autofill = remember { context.getSystemService(android.view.autofill.AutofillManager::class.java) }
    val filling = remember(back.intValue) { autofill?.hasEnabledAutofillServices() == true }
    SettingsSection {
        SettingRow(stringResource(R.string.pw_autofill_title), stringResource(if (filling) R.string.pw_autofill_on else R.string.pw_autofill_note),
            onClick = if (filling) null else ({
                runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, android.net.Uri.parse("package:${context.packageName}"))) }
                    .onFailure { WriterLog.e("Autofill: Android's chooser not opened", it) }
            })) { if (filling) Icon(painterResource(R.drawable.ic_check), null, tint = PASSWORDS_COLOR) else Chevron() }
        if (filling) Text(stringResource(R.string.pw_autofill_off_how), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = quiet())
    }
    SettingsSection(stringResource(R.string.browser_sync_title), stringResource(R.string.browser_sync_note)) {
        // passwords (the user, 3 Nov: "sync passwords as well but have some encryption"): off until a passphrase is given here
        val scope = rememberCoroutineScope()
        var pw by remember { mutableStateOf(PasswordSync.State.OFF) }
        var ask by remember { mutableStateOf<String?>(null) } // "new", "enter", "forgot"
        var recipe by remember { mutableStateOf<BrowserPasswords.Recipe?>(null) }
        LaunchedEffect(ask) { if (ask == null) pw = withContext(Dispatchers.IO) { runCatching { PasswordSync.state(context) }.getOrDefault(PasswordSync.State.OFF) } }
        fun begin() = scope.launch { recipe = withContext(Dispatchers.IO) { runCatching { PasswordSync.recipe() }.getOrNull() }; ask = if (recipe == null) "new" else "enter" }
        SettingRow(stringResource(R.string.browser_sync_passwords), stringResource(when (pw) {
            PasswordSync.State.OFF -> R.string.browser_sync_passwords_off; PasswordSync.State.ON -> R.string.browser_sync_passwords_on; else -> R.string.browser_sync_passwords_need }),
            onClick = { if (pw == PasswordSync.State.OFF) begin() else if (pw == PasswordSync.State.NEED) begin() }, warn = pw == PasswordSync.State.NEED) {
            Switch(checked = pw != PasswordSync.State.OFF, onCheckedChange = { on ->
                if (on) begin() else { PasswordSync.disable(context); pw = PasswordSync.State.OFF }
            })
        }
        // the Passwords applet's 2FA codes (the user, 4 Nov: opt-in, off), with the passwords' passphrase: only once that is on here
        var twoFa by remember { mutableStateOf(BrowserSync.on(context, BrowserSync.TWO_FACTOR)) }
        SettingRow(stringResource(R.string.pw_sync_2fa), stringResource(if (pw == PasswordSync.State.ON) R.string.pw_sync_2fa_note else R.string.pw_sync_2fa_needs)) {
            Switch(checked = twoFa && pw == PasswordSync.State.ON, enabled = pw == PasswordSync.State.ON, onCheckedChange = { on ->
                twoFa = on
                if (!on) AuthSync.off(context) else {
                    prefs.edit().putBoolean(BrowserSync.TWO_FACTOR, true).apply(); WriterLog.i("2FA sync: on")
                    val app = context.applicationContext
                    Thread { runCatching { PasswordStore.keep2fa(app, AuthSecrets.load(app)) }.onFailure { WriterLog.e("2FA sync: first merge failed", it) } }.start()
                }
            })
        }
        when (ask) {
            "new", "enter" -> PassphraseDialog(if (ask == "new") null else recipe, close = { ask = null }, forgot = { ask = "forgot" }) { r, key, isNew ->
                withContext(Dispatchers.IO) { PasswordSync.enable(context.applicationContext, r, key, isNew) }
                key.fill(0)
                ScopedSync.soon(context.applicationContext, "browser", orWhole = true)
                ask = null
            }
            "forgot" -> AlertDialog(onDismissRequest = { ask = null }, title = { Text(stringResource(R.string.browser_pass_reset_title)) },
                text = { Text(stringResource(R.string.browser_pass_reset_note)) },
                confirmButton = { TextButton(onClick = { WriterLog.i("Browser sync: passwords started again with a new passphrase"); ask = "new" }) { Text(stringResource(R.string.browser_pass_reset)) } },
                dismissButton = { TextButton(onClick = { ask = null }) { Text(stringResource(R.string.common_cancel)) } })
        }
    }
}

/**
 * The sync passphrase: a new one twice ([existing] null: no device has one, or "forgot it"), or the one in force once,
 * refused when its check box does not open. Argon2id takes a moment: worked out away from the screen's thread.
 */
@Composable private fun PassphraseDialog(existing: BrowserPasswords.Recipe?, close: () -> Unit, forgot: () -> Unit,
    done: suspend (BrowserPasswords.Recipe, ByteArray, Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var one by remember { mutableStateOf("") }
    var two by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    var working by remember { mutableStateOf(false) }
    val hidden = androidx.compose.ui.text.input.PasswordVisualTransformation()
    val keys = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password)
    fun go() {
        error = when { one.length < 8 -> R.string.browser_pass_short; existing == null && one != two -> R.string.browser_pass_mismatch; else -> null }
        if (error != null) return
        working = true
        scope.launch {
            val chars = one.toCharArray()
            val made = withContext(Dispatchers.Default) { runCatching { if (existing == null) BrowserPasswords.create(chars) else existing to BrowserPasswords.unlock(chars, existing) } }
            chars.fill(' ')
            made.onSuccess { (r, key) -> done(r, key, existing == null) }
                .onFailure { error = if (it is BrowserPasswords.WrongPassphrase) R.string.browser_pass_wrong else R.string.browser_pass_failed
                    if (it !is BrowserPasswords.WrongPassphrase) WriterLog.e("Browser sync: the passphrase not taken", it) else WriterLog.i("Browser sync: a wrong passphrase refused") }
            working = false
        }
    }
    AlertDialog(onDismissRequest = { if (!working) close() },
        title = { Text(stringResource(if (existing == null) R.string.browser_pass_new_title else R.string.browser_pass_enter_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(if (existing == null) R.string.browser_pass_new_note else R.string.browser_pass_enter_note), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(one, { one = it; error = null }, singleLine = true, label = { Text(stringResource(R.string.browser_pass_field)) },
                visualTransformation = hidden, keyboardOptions = keys, isError = error != null, enabled = !working)
            if (existing == null) OutlinedTextField(two, { two = it; error = null }, singleLine = true, label = { Text(stringResource(R.string.browser_pass_repeat)) },
                visualTransformation = hidden, keyboardOptions = keys, isError = error == R.string.browser_pass_mismatch, enabled = !working)
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (existing != null) TextButton(onClick = forgot, enabled = !working) { Text(stringResource(R.string.browser_pass_forgot)) }
        } },
        confirmButton = { TextButton(onClick = ::go, enabled = !working && one.isNotEmpty()) { Text(stringResource(R.string.common_ok)) } },
        dismissButton = { TextButton(onClick = close, enabled = !working) { Text(stringResource(R.string.common_cancel)) } })
}

@Composable private fun TextEditorPage() {
    val context = LocalContext.current
    val editor = remember { TextEditorStore(context) }
    var txt by remember { mutableStateOf(editor.opensTxt) }
    var wrap by remember { mutableStateOf(editor.wordWrap) }
    var folder by remember { mutableStateOf(editor.saveFolder) }
    SettingsSection(stringResource(R.string.settings_open_from_files), stringResource(R.string.settings_text_open_note)) {
        SwitchRow(stringResource(R.string.settings_open_txt), if (txt) stringResource(R.string.settings_open_txt_on) else stringResource(R.string.settings_open_txt_off), txt) { txt = it; editor.opensTxt = it }
    }
    SettingsSection(stringResource(R.string.settings_editing)) {
        SwitchRow(stringResource(R.string.settings_word_wrap), if (wrap) stringResource(R.string.settings_word_wrap_on) else stringResource(R.string.settings_word_wrap_off), wrap) { wrap = it; editor.wordWrap = it }
        Column(Modifier.fillMaxWidth().background(rowColor()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_save_new_in), style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(folder, { folder = it; editor.saveFolder = it }, singleLine = true, prefix = { Text("/sdcard/") }, modifier = Modifier.fillMaxWidth())
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
    // the service for each part (the user, 12 Nov: drop-in replacements like OpenAI and ElevenLabs); Mistral unless chosen
    var providers by remember { mutableStateOf(AiPart.entries.map { AiProviders.of(context, it) }) }
    var pickingPart by remember { mutableStateOf<AiPart?>(null) }
    SettingsSection(stringResource(R.string.ai_providers), stringResource(R.string.ai_providers_note)) {
        AiPart.entries.forEach { part ->
            SettingRow(stringResource(PART_NAMES.getValue(part)), providers[part.ordinal].name, onClick = { pickingPart = part }) { Chevron() }
        }
    }
    pickingPart?.let { part ->
        AlertDialog(onDismissRequest = { pickingPart = null }, confirmButton = { TextButton(onClick = { pickingPart = null }) { Text(stringResource(R.string.common_done)) } },
            title = { Text(stringResource(PART_NAMES.getValue(part))) },
            text = { Column { AiProviders.choices(part).forEach { p ->
                SettingRow(p.name, chosen = p == providers[part.ordinal], onClick = { AiProviders.set(context, part, p); providers = AiPart.entries.map { AiProviders.of(context, it) }; pickingPart = null })
            } } })
    }
    // a key for each service in use, Mistral's always (live talk, web search and pictures are its own)
    (listOf(AiProviders.MISTRAL) + providers).distinct().forEach { p -> key(p.id) { SettingsSection { AssistantKeyField(p = p) } } }
    // AI decorations (8 Nov): the record, the cassette, the TV with the robot, the snow and the CRT; off, the plain conversation
    var decorations by remember { mutableStateOf(AiLook.decorated(context)) }
    if (!CarMaps.edition) SettingsSection(stringResource(R.string.ai_decorations), null) { // the car has none (AiLook)
        SwitchRow(stringResource(R.string.ai_decorations), stringResource(R.string.ai_decorations_note), decorations) { decorations = it; AiLook.set(context, it); TvWidget.refresh(context) }
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
    if (AiGate.on && !CarMaps.edition) WakeSection() // the car has its steering-wheel button instead
    // the voice that reads answers: Mistral's (the user: the phone's was bad and slow), the phone's kept as the free one
    var voiceName by remember(providers) { mutableStateOf(AssistantVoices.id(context)?.let { if (it == AssistantVoices.PHONE) context.getString(R.string.assistant_voice_phone) else AssistantVoices.name(context) }) }
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


/** The speaking service's voices (Mistral's: all pages), the phone's first: a tap chooses one and reads a sentence in it. */
private val PART_NAMES = mapOf(AiPart.CHAT to R.string.ai_part_chat, AiPart.LISTEN to R.string.ai_part_listen, AiPart.VOICE to R.string.ai_part_voice)

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
        withContext(Dispatchers.IO) { runCatching { AssistantNet.voices(context) } }
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

@Composable private fun CalendarPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version = resumes()
    fun say(s: String) = Toast.makeText(context, s, Toast.LENGTH_LONG).show()
    fun open(i: Intent) = runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    val pkg = Uri.parse("package:${context.packageName}")
    val appDetails = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
    val notificationSettings = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { version.intValue++ }
    @Suppress("UNUSED_EXPRESSION") version.intValue // read again on each change
    val notifications = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    val exact = android.os.Build.VERSION.SDK_INT < 31 || context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()
    val battery = context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    val xiaomi = android.os.Build.MANUFACTURER.equals("Xiaomi", true)
    val island = remember(version.intValue) { HyperIsland.isSupported(context) }
    SettingsSection(stringResource(R.string.settings_reminders), stringResource(R.string.settings_reminders_note)) {
        var on by remember { mutableStateOf(CalendarAlarms.enabled(context)) }
        SwitchRow(stringResource(R.string.settings_reminders_here), if (on) stringResource(R.string.common_on) else stringResource(R.string.common_off), on) { on = it; CalendarAlarms.setEnabled(context, it) }
        SettingRow(stringResource(R.string.settings_test_reminder), stringResource(R.string.settings_test_reminder_summary), onClick = { CalendarAlarms.test(context); say(context.getString(R.string.settings_test_reminder_coming)) })
    }
    SettingsSection(stringResource(R.string.settings_reminders_need)) {
        StatusRow(stringResource(R.string.settings_notifications), if (notifications) stringResource(R.string.settings_notifications_on) else stringResource(R.string.settings_notifications_off), notifications, if (notifications) stringResource(R.string.common_settings) else stringResource(R.string.common_allow)) {
            if (!notifications && android.os.Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS) else open(notificationSettings)
        }
        StatusRow(stringResource(R.string.settings_exact_alarms), if (exact) stringResource(R.string.settings_exact_alarms_on) else stringResource(R.string.settings_exact_alarms_off), exact, if (exact) null else stringResource(R.string.common_allow)) {
            if (android.os.Build.VERSION.SDK_INT >= 31) open(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
        }
        StatusRow(stringResource(R.string.settings_battery), if (battery) stringResource(R.string.settings_battery_on) else stringResource(R.string.settings_battery_off), battery, stringResource(R.string.common_settings)) {
            open(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) || open(appDetails)
        }
        if (xiaomi) {
            StatusRow(stringResource(R.string.settings_hyper_island), if (island) stringResource(R.string.settings_hyper_island_on) else stringResource(R.string.settings_hyper_island_off), island, stringResource(R.string.common_settings)) { open(notificationSettings) }
            StatusRow(stringResource(R.string.settings_autostart), stringResource(R.string.settings_autostart_summary), true, stringResource(R.string.common_settings)) {
                open(Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")) || open(appDetails)
            }
        }
    }
    // place reminders (LocationReminders): off at first; Android asks the location, then "all the time" on a page of its own
    val askAlways = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { version.intValue++; LocationReminders.planSoon(context) }
    val askPlace = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        version.intValue++; if (LocationReminders.located(context)) askAlways.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
    fun allowPlaces() = if (!LocationReminders.located(context)) askPlace.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        else askAlways.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    SettingsSection(stringResource(R.string.place_reminders), stringResource(R.string.place_reminders_note)) {
        var placesOn by remember { mutableStateOf(LocationReminders.enabled(context)) }
        SwitchRow(stringResource(R.string.place_reminders_on), if (placesOn) stringResource(R.string.common_on) else stringResource(R.string.common_off), placesOn) {
            placesOn = it; LocationReminders.setEnabled(context, it); if (it && !LocationReminders.always(context)) allowPlaces()
        }
        if (placesOn) {
            val always = LocationReminders.always(context)
            StatusRow(stringResource(R.string.place_reminders_location), if (always) stringResource(R.string.place_reminders_location_on) else stringResource(R.string.place_reminders_location_off),
                always, if (always) null else stringResource(R.string.common_allow)) { allowPlaces() }
        }
        var places by remember { mutableStateOf(LocationReminders.list(driveRoot())) }
        if (places.isEmpty()) SettingRow(stringResource(R.string.place_reminders_none))
        places.forEach { r ->
            SettingRow(if (r.kind.isNotEmpty()) MapsSearch.label(r.kind, context) else r.name, r.note.ifEmpty { stringResource(R.string.place_reminders_any) }) {
                IconButton(onClick = { LocationReminders.remove(context, driveRoot(), r.id); places = LocationReminders.list(driveRoot()) }) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.common_delete))
                }
            }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { say(withContext(Dispatchers.IO) { runCatching { importCalendars(context, uri) }.getOrElse { context.getString(R.string.settings_not_imported, it.message.orEmpty()) } }) }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch { say(withContext(Dispatchers.IO) { runCatching {
            context.contentResolver.openOutputStream(uri)!!.use { it.write(CalendarTransfer.exportAll(CalendarFiles.calendars(calendarRoot()))) }; context.getString(R.string.settings_exported)
        }.getOrElse { context.getString(R.string.settings_not_exported, it.message.orEmpty()) } }) }
    }
    SettingsSection(stringResource(R.string.settings_your_calendars), stringResource(R.string.settings_your_calendars_note)) {
        SettingRow(stringResource(R.string.common_import), stringResource(R.string.settings_calendar_import_summary),
            onClick = { pick.launch(arrayOf("text/calendar", "application/zip", "application/octet-stream", "*/*")) })
        SettingRow(stringResource(R.string.settings_export_all), stringResource(R.string.settings_export_all_summary), onClick = { save.launch("Tetra calendars.zip") })
    }
    SettingsSection("Claude") { SkillRows("tetra-calendar", "Calendar", stringResource(R.string.settings_calendar_skill)) }
}

/** Settings › Maps (21 Oct): what is installed, where updates come from, the directions' language and voice, parking, places. */
@Composable private fun MapsPage() {
    val mapsContext = LocalContext.current
    // the car build: Tetra Car's map view as a page of its own (the user, 14 Nov: "a separate page, it has too many settings (map view ›)")
    if (CarMaps.edition) SettingsSection(stringResource(R.string.app_car)) {
        var viewing by remember { mutableStateOf(false) }
        SettingRow(stringResource(R.string.car_map_view) + "  ›", onClick = { viewing = true })
        if (viewing) androidx.compose.ui.window.Dialog({ viewing = false }, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            CarViewPage({}, {}, startSettings = true, inSettings = true) { viewing = false }
        }
    }
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
    // a map out as one .tetramap file and in again, for a device that does not sync: the car's head unit (the user, 10 Nov)
    var exporting by remember { mutableStateOf<MapsFiles.Installed?>(null) }
    var work by remember { mutableStateOf<String?>(null) }
    var workDone by remember { mutableFloatStateOf(0f) }
    fun archive(title: String, failed: Int, job: ((Float) -> Unit) -> () -> Unit) {
        work = title; workDone = 0f
        removeScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { job { workDone = it } } }
            work = null; mapsVersion++
            r.onSuccess { it() }.onFailure { WriterLog.e("Maps: $title", it); Toast.makeText(mapsContext, mapsContext.getString(failed, it.message.orEmpty()), Toast.LENGTH_LONG).show() }
        }
    }
    val saveMap = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MapsArchive.MIME)) { uri ->
        val m = exporting ?: return@rememberLauncherForActivityResult
        exporting = null
        if (uri == null) return@rememberLauncherForActivityResult
        archive(mapsContext.getString(R.string.settings_exporting_map, m.name), R.string.settings_not_exported_maps) { p ->
            runCatching { mapsContext.contentResolver.openOutputStream(uri)!!.use { MapsArchive.export(mapsRoot, m.id, it, p) } }
                .onFailure { runCatching { android.provider.DocumentsContract.deleteDocument(mapsContext.contentResolver, uri) } }.getOrThrow() // no half map left behind
            ({ Toast.makeText(mapsContext, mapsContext.getString(R.string.settings_map_exported, m.name), Toast.LENGTH_LONG).show() })
        }
    }
    val pickMap = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        archive(mapsContext.getString(R.string.settings_importing_map), R.string.settings_map_not_imported) { p ->
            val id = mapsContext.contentResolver.openInputStream(uri)!!.use { MapsArchive.import(mapsRoot, it, p) }
            val name = MapsFiles.maps(mapsRoot).firstOrNull { it.id == id }?.name ?: id
            ({ Toast.makeText(mapsContext, mapsContext.getString(R.string.settings_map_imported, name), Toast.LENGTH_LONG).show() })
        }
    }
    SettingsSection(stringResource(R.string.settings_installed_maps), stringResource(R.string.settings_installed_maps_note)) {
        if (files?.isEmpty() == true) SettingRow(stringResource(R.string.maps_no_map))
        // an old map says so beside its button: the computer remakes it (Settings › Maps there)
        files?.forEach { m -> SettingRow(m.name, if (m.old) m.about + "\n" + stringResource(R.string.maps_old_remake) else m.about) {
            if (m.old) Text(stringResource(R.string.maps_old), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            TextButton(onClick = { exporting = m }, enabled = work == null) { Text(stringResource(R.string.common_export), maxLines = 1) }
            TextButton(onClick = { removing = m.id to m.name }) { Text(stringResource(R.string.settings_remove_map), maxLines = 1) }
        } }
        SettingRow(stringResource(R.string.settings_import_map), stringResource(R.string.settings_import_map_summary),
            onClick = { if (work == null) pickMap.launch(arrayOf(MapsArchive.MIME, "application/zip", "application/octet-stream", "*/*")) })
    }
    exporting?.let { m -> AlertDialog(onDismissRequest = { exporting = null }, title = { Text(stringResource(R.string.settings_export_map_title, m.name)) },
        text = { Text(stringResource(R.string.settings_export_map_body)) },
        confirmButton = { Row {
            TextButton(onClick = { saveMap.launch("${m.id}.${MapsArchive.EXTENSION}") }) { Text(stringResource(R.string.common_save), maxLines = 1) }
            TextButton(onClick = {
                exporting = null
                archive(mapsContext.getString(R.string.settings_exporting_map, m.name), R.string.settings_not_exported_maps) { p ->
                    // in the cache, for the app it goes to; the one shared before goes first (hundreds of MB)
                    val dir = java.io.File(mapsContext.cacheDir, "share").apply { mkdirs() }
                    dir.listFiles().orEmpty().filter { it.name.endsWith(".${MapsArchive.EXTENSION}") }.forEach { it.delete() }
                    val file = java.io.File(dir, "${m.id}.${MapsArchive.EXTENSION}")
                    runCatching { file.outputStream().use { MapsArchive.export(mapsRoot, m.id, it, p) } }.onFailure { file.delete() }.getOrThrow()
                    val uri = androidx.core.content.FileProvider.getUriForFile(mapsContext, "${mapsContext.packageName}.files", file)
                    ({ mapsContext.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(MapsArchive.MIME).putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), mapsContext.getString(R.string.files_share_named, file.name))) })
                }
            }) { Text(stringResource(R.string.common_share), maxLines = 1) }
        } },
        dismissButton = { TextButton(onClick = { exporting = null }) { Text(stringResource(R.string.common_cancel), maxLines = 1) } }) }
    work?.let { t -> AlertDialog(onDismissRequest = {}, title = { Text(t) },
        text = { LinearProgressIndicator(progress = { workDone }, modifier = Modifier.fillMaxWidth()) }, confirmButton = {}) }
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
    val cals = remember(version.intValue) { runCatching { CalendarFiles.calendars(calendarRoot()) }.getOrDefault(emptyList()) }
    val chosen = cals.firstOrNull { it.dir.name == ContactsPrefs.birthdays(context) } ?: ContactsBirthdays.existing(cals)
    SettingsSection(null, stringResource(R.string.settings_contacts_note)) {
        SwitchRow(stringResource(R.string.settings_in_phone_contacts), if (copy) stringResource(R.string.settings_in_phone_contacts_on)
            else stringResource(R.string.settings_in_phone_contacts_off), copy) { on ->
            when { !on -> { ContactsAccount.set(context, false); version.intValue++ }; writes -> { ContactsAccount.set(context, true); version.intValue++ }; else -> askWrite.launch(Manifest.permission.WRITE_CONTACTS) }
        }
        ChoiceRow(stringResource(R.string.settings_birthdays_into), if (cals.isEmpty()) stringResource(R.string.settings_no_calendars) else stringResource(R.string.settings_birthdays_summary),
            cals.map { it to it.name }, chosen) { c -> c?.let { ContactsPrefs.setBirthdays(context, it.dir.name) }; version.intValue++ }
    }
}

/**
 * Settings › Αλληλογραφία (MAIL_PLAN §9c): the accounts as the computer has them, each green when all its mail is here and
 * orange while some is only on the server; and Ασφάλεια και απόρρητο. Accounts are added on the computer for now (step 2).
 */
@Composable private fun MailPage(openPart: (String) -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val data by produceState<Pair<List<MailAccount>, List<MailEntry>>?>(null, version) {
        value = withContext(Dispatchers.IO) { runCatching { mailRoot().let { MailFiles.accounts(it, MailSecret.ids(context)) to MailFiles.index(it, MailEngine.device()) } }.getOrNull() }
    }
    var adding by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf<MailAccount?>(null) }
    var using by remember { mutableStateOf<MailAccount?>(null) }
    var storing by remember { mutableStateOf<String?>(null) } // «Αποθήκη» for one account
    storing?.let { id -> MailStorageDialog(id, openPart) { storing = null } }
    SettingsSection(stringResource(R.string.mail_accounts)) {
        data?.first?.forEach { a ->
            val orange = data!!.second.count { it.account == a.id && !it.saved }
            val trouble = if (a.own) MailPrefs.state(context, a.id) else null
            SettingRow(a.name, listOfNotNull(a.address, stringResource(if (a.own) R.string.mail_own_device else R.string.mail_via_pc),
                trouble?.let { stringResource(when (it) { "auth" -> R.string.mail_why_auth; "appPassword" -> R.string.mail_why_app_password; "unreachable" -> R.string.mail_why_unreachable; else -> R.string.mail_why_other }) }
                    ?: if (orange == 0) "● " + stringResource(R.string.mail_all_local) else "● " + pluralStringResource(R.plurals.mail_n_server_only, orange, orange)).joinToString(" · "),
                onClick = { if (a.own) managing = a else using = a },
                warn = orange > 0 || trouble != null, leading = { Box(Modifier.size(12.dp).clip(CircleShape).background(Color(a.color))) }) { Chevron() }
        }
        SettingRow(stringResource(R.string.mail_add_account), onClick = { adding = true }) { Icon(painterResource(R.drawable.ic_add), contentDescription = null) }
        var every by remember { mutableIntStateOf(MailPrefs.every(context)) }
        ChoiceRow(stringResource(R.string.mail_check), stringResource(R.string.mail_check_summary), MailPrefs.EVERY.map { it to when (it) {
            MailPrefs.NOW -> stringResource(R.string.mail_check_now); MailPrefs.MANUAL -> stringResource(R.string.mail_check_manual)
            else -> stringResource(R.string.mail_check_every, it) } }, every) { every = it; MailPrefs.setEvery(it); MailEngine.schedule(context.applicationContext) }
        var undo by remember { mutableIntStateOf(MailPrefs.undo(context)) }
        ChoiceRow(stringResource(R.string.mail_undo), stringResource(R.string.mail_undo_summary), listOf(5, 3, 0).map { it to if (it == 0) stringResource(R.string.mail_undo_none) else stringResource(R.string.mail_undo_seconds, it) },
            undo) { undo = it; MailPrefs.setUndo(context, it) }
        // Tetra as the email app (the user, 31 Oct): which one opens mailto: links now; Android's default apps to change it
        val resumed = resumes()
        val mailApp = remember(resumed.intValue) { context.packageManager.resolveActivity(android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:")),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.takeIf { it.packageName != "android" && "Resolver" !in it.name }?.loadLabel(context.packageManager)?.toString() }
        SettingRow(stringResource(R.string.mail_default_app), mailApp?.let { stringResource(R.string.mail_default_is, it) } ?: stringResource(R.string.mail_default_none), onClick = {
            runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
                .onFailure { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS)) }
        }) { Chevron() }
    }
    // Υπογραφές (MAIL_PLAN §9c): one per address; plain text for now
    var signing by remember { mutableStateOf<MailAccount?>(null) }
    if (!data?.first.isNullOrEmpty()) SettingsSection(stringResource(R.string.mail_signatures)) {
        data!!.first.forEach { a ->
            SettingRow(a.address, MailSignatures.text(mailRoot(), a.address).lineSequence().firstOrNull()?.ifBlank { null } ?: stringResource(R.string.common_none), onClick = { signing = a }) { Chevron() }
        }
    }
    signing?.let { a ->
        // a designed signature as its HTML (to edit or paste over), a simple one as its text
        var text by remember { mutableStateOf(MailSignatures.html(mailRoot(), a.address).let { if (MailSignatures.designed(it)) it else MailSignatures.plain(it) }) }
        AlertDialog(onDismissRequest = { signing = null }, title = { Text(stringResource(R.string.mail_signature_for, a.address)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(text, { text = it }, minLines = 4, maxLines = 12, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.mail_signature_hint), style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(onClick = { runCatching { MailSignatures.set(mailRoot(), a.address, text.trimEnd()) }.onFailure { WriterLog.e("Mail: a signature", it) }; signing = null; version++ }) { Text(stringResource(R.string.common_save)) } },
            dismissButton = { TextButton(onClick = { signing = null }) { Text(stringResource(R.string.common_cancel)) } })
    }
    // conversations, and where the islands stand as Office's ribbon (the user, 31 Oct)
    SettingsSection(stringResource(R.string.settings_appearance)) {
        LaunchedEffect(Unit) { MailLook.load(context) }
        SwitchRow(stringResource(R.string.mail_threads), stringResource(R.string.mail_threads_summary), MailLook.threads) { MailLook.setThreads(context, it) }
        // only a screen with folders · list · mail side by side opens a mail by itself
        if (LocalConfiguration.current.screenWidthDp >= 1000) SwitchRow(stringResource(R.string.mail_read_shown), stringResource(R.string.mail_read_shown_summary), MailLook.readShown) { MailLook.setReadShown(context, it) }
        // the islands' place: a tablet's or a big screen's choice only
        if (LocalConfiguration.current.screenWidthDp >= 700) ChoiceRow(stringResource(R.string.mail_islands), stringResource(R.string.mail_islands_summary), listOf("auto" to stringResource(R.string.settings_by_itself),
            "bottom" to stringResource(R.string.settings_bottom), "top" to stringResource(R.string.settings_top)), MailLook.place) { MailLook.set(context, it) }
    }
    SettingsSection(stringResource(R.string.mail_security)) {
        var remote by remember { mutableStateOf(MailPrefs.remote(context)) }
        SwitchRow(stringResource(R.string.mail_remote), stringResource(R.string.mail_remote_summary), remote) { remote = it; MailPrefs.setRemote(context, it) }
        var allowed by remember { mutableStateOf(MailPrefs.allowed(context)) }
        var listing by remember { mutableStateOf(false) }
        SettingRow(stringResource(R.string.mail_allowed), allowed.sorted().joinToString(", ").ifEmpty { stringResource(R.string.common_none) }, onClick = { listing = true }) { Chevron() }
        @Composable fun Switched(key: String, title: Int, summary: Int?) {
            var on by remember { mutableStateOf(MailPrefs.switch(context, key)) }
            SwitchRow(stringResource(title), summary?.let { stringResource(it) }, on) { on = it; MailPrefs.setSwitch(context, key, it) }
        }
        Switched("tracking", R.string.mail_tracking, R.string.mail_tracking_summary)
        if (listing) AlertDialog(onDismissRequest = { listing = false }, title = { Text(stringResource(R.string.mail_allowed)) },
            text = { Column {
                if (allowed.isEmpty()) Text(stringResource(R.string.mail_allowed_none))
                allowed.sorted().forEach { who -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(who, Modifier.weight(1f))
                    IconButton(onClick = { MailPrefs.allow(context, who, false); allowed = MailPrefs.allowed(context) }) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_remove)) }
                } }
            } }, confirmButton = { TextButton(onClick = { listing = false }) { Text(stringResource(R.string.common_ok)) } })
    }
    SettingsSection(stringResource(R.string.mail_warnings)) {
        @Composable fun Switched(key: String, title: Int, summary: Int?) {
            var on by remember { mutableStateOf(MailPrefs.switch(context, key)) }
            SwitchRow(stringResource(title), summary?.let { stringResource(it) }, on) { on = it; MailPrefs.setSwitch(context, key, it) }
        }
        Switched("links", R.string.mail_link_warning, R.string.mail_link_warning_summary)
        Switched("risky", R.string.mail_risky, R.string.mail_risky_summary)
        Switched("realAddress", R.string.mail_real_address, null)
        var receipts by remember { mutableStateOf(MailPrefs.receipts(context)) }
        ChoiceRow(stringResource(R.string.mail_receipts), null, listOf(0 to stringResource(R.string.mail_receipts_never), 1 to stringResource(R.string.mail_receipts_ask), 2 to stringResource(R.string.mail_receipts_always)),
            receipts) { receipts = it; MailPrefs.setReceipts(context, it) }
    }
    if (adding) MailAddAccount { adding = false; version++ }
    // a PC account fetched here too: the PC gives its password over the paired connection; Microsoft signs in again here
    using?.let { a ->
        var busy by remember { mutableStateOf(false) }
        var problem by remember { mutableStateOf<String?>(null) }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val microsoft = a.json?.optString("provider") == "microsoft" || a.json?.optString("auth").let { it != null && it != "" && it != "password" }
        var name by remember { mutableStateOf(a.name) }
        AlertDialog(onDismissRequest = { if (!busy) using = null }, title = { Text(a.name) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(a.address)
                // its name, as the PC's own accounts are named there (the user, 11 Nov: "I can't name the account like the others"): the PC writes it
                OutlinedTextField(name, { name = it.take(40) }, singleLine = true, label = { Text(stringResource(R.string.common_name)) }, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { if (name.isNotBlank() && name.trim() != a.name) IconButton(onClick = {
                        runCatching { MailRequests.rename(mailRoot(), MailEngine.device(), a.id, name.trim()); ScopedSync.soon(context.applicationContext, "mail", 0, orWhole = true) }
                            .onFailure { WriterLog.e("Mail: renaming a PC account", it) }
                        WriterLog.i("Mail: ${a.id} to be named on the PC"); using = null
                    }) { Icon(painterResource(R.drawable.ic_check), stringResource(R.string.common_save)) } })
                Text(stringResource(if (microsoft) R.string.mail_use_here_microsoft else R.string.mail_use_here_summary), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { using = null; storing = a.id }) { Icon(painterResource(R.drawable.ic_folder), contentDescription = null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.mail_storage)) }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                if (microsoft) { using = null; adding = true; return@TextButton }
                busy = true; problem = null
                scope.launch {
                    val app = context.applicationContext
                    val r = withContext(Dispatchers.IO) { runCatching {
                        val given = SyncClient(app, SyncStore(app)).mailCredentials(a.id)
                        if (given.optString("signIn") == "microsoft" || !given.has("password")) return@runCatching false // signs in with Microsoft: here too
                        MailEngine.adopt(app, mailRoot(), a, given)
                        Thread { MailEngine.syncAll(app) }.start()
                        true
                    } }
                    busy = false
                    r.onSuccess { using = null; if (!it) adding = true; version++ }.onFailure {
                        WriterLog.w("Mail: using ${a.id} here: ${it.message}")
                        problem = when (mailReason(it)) { "auth" -> context.getString(R.string.mail_why_auth); "appPassword" -> context.getString(R.string.mail_why_app_password)
                            "unreachable" -> context.getString(R.string.mail_why_unreachable); else -> it.message ?: context.getString(R.string.mail_why_other) }
                    }
                }
            }) { Text(stringResource(if (busy) R.string.mail_use_here_busy else R.string.mail_use_here)) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { using = null }) { Text(stringResource(R.string.common_cancel)) } })
    }
    managing?.let { a ->
        var pass by remember { mutableStateOf("") }
        var name by remember { mutableStateOf(a.name) } // Work, Personal, Masters…: what the folders and the list call it
        val microsoft = a.json?.optString("provider") == "microsoft" // signs in with Microsoft: no password here
        AlertDialog(onDismissRequest = { managing = null }, title = { Text(a.name) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(a.address)
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.common_name)) }, modifier = Modifier.fillMaxWidth())
                if (!microsoft) OutlinedTextField(pass, { pass = it }, singleLine = true, label = { Text(stringResource(R.string.mail_new_password)) },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { managing = null; storing = a.id }) { Icon(painterResource(R.drawable.ic_folder), contentDescription = null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.mail_storage)) }
                TextButton(onClick = { MailEngine.remove(context.applicationContext, mailRoot(), a.id); MailEngine.schedule(context.applicationContext); managing = null; version++ }) {
                    Text(stringResource(R.string.mail_remove_here), color = MaterialTheme.colorScheme.error)
                }
            } },
            confirmButton = { TextButton(enabled = pass.isNotBlank() || (name.isNotBlank() && name.trim() != a.name), onClick = {
                if (name.isNotBlank() && name.trim() != a.name) runCatching { MailEngine.rename(mailRoot(), a.id, name.trim()) }.onFailure { WriterLog.e("Mail: renaming an account", it) }
                if (pass.isNotBlank()) {
                    MailSecret.set(context, a.id, pass.trim()); MailPrefs.setState(context, a.id, null)
                    val server = a.json?.optJSONObject("server"); val p = pass.trim()
                    Thread { MailPasswords.keep(context.applicationContext, server?.optString("host").orEmpty(), server?.optString("user").orEmpty().ifEmpty { a.address }, p); MailEngine.syncAll(context.applicationContext) }.start()
                }
                managing = null; version++ }) { Text(stringResource(R.string.common_save)) } },
            dismissButton = { TextButton(onClick = { managing = null }) { Text(stringResource(R.string.common_cancel)) } })
    }
}

/** Settings › Data (Plan.md § Data): the plan is set up in Data's own Setup; here its state and the switches. */
@Composable private fun DataPage() {
    val context = LocalContext.current
    val back = resumes()
    val access = remember(back.intValue) { DataStats.hasAccess(context) }
    val plan = remember(back.intValue) { DataFiles.plan() }
    val prefs = remember { context.getSharedPreferences("data", android.content.Context.MODE_PRIVATE) }
    SettingsSection(null, stringResource(R.string.settings_data_note)) {
        SettingRow(stringResource(R.string.settings_data_setup), stringResource(R.string.settings_data_setup_summary), onClick = { openData(context, "setup") }) { Chevron() }
        StatusRow(stringResource(R.string.settings_usage_access), if (access) stringResource(R.string.common_on) else stringResource(R.string.settings_usage_access_off), access, stringResource(R.string.common_settings)) {
            runCatching { context.startActivity(DataStats.accessIntent(context)) }.onFailure { context.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }
        SettingRow(stringResource(R.string.settings_your_plan), plan?.let { p -> stringResource(R.string.settings_plan_renews, when (p.kind) {
            DataPlan.UNLIMITED -> stringResource(R.string.settings_unlimited); DataPlan.FAIR_USE -> stringResource(R.string.settings_fair_use, DataRules.bytes(p.size)); else -> DataRules.bytes(p.size)
        }, DataRules.cycle(p, java.time.LocalDate.now()).end.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.getDefault()))) } ?: stringResource(R.string.settings_not_set_up), onClick = { openData(context, "setup") }) { Chevron() }
    }
    SettingsSection(stringResource(R.string.settings_warnings)) {
        SettingRow(stringResource(R.string.settings_warnings), plan?.takeIf { it.limited }?.warn?.joinToString(" · ") { "$it %" } ?: stringResource(R.string.common_none), onClick = { openData(context, "setup") }) { Chevron() }
        var budget by remember { mutableStateOf(prefs.getBoolean("warnBudget", false)) }
        SwitchRow(stringResource(R.string.settings_day_budget), null, budget) { budget = it; prefs.edit().putBoolean("warnBudget", it).apply() }
        SettingRow(stringResource(R.string.settings_roaming), plan?.let { p -> stringResource(R.string.settings_roaming_summary, if (p.passPrice > 0) stringResource(R.string.settings_day_pass, p.currency + "%.0f".format(p.passPrice), DataRules.bytes(p.passSize)) else stringResource(R.string.settings_no_day_pass)) } ?: stringResource(R.string.settings_not_set_up), onClick = { openData(context, "setup") }) { Chevron() }
    }
    SettingsSection(stringResource(R.string.settings_live)) {
        var live by remember { mutableStateOf(DataSpeed.enabled(context)) }
        SwitchRow(stringResource(R.string.settings_live_speed), stringResource(R.string.settings_live_speed_summary), live) { live = it; DataSpeed.setEnabled(context, it) }
        var drops by remember { mutableStateOf(DataSignal.summaryOn(context)) }
        SwitchRow(stringResource(R.string.settings_signal_drops), stringResource(R.string.settings_signal_drops_summary), drops) { drops = it; DataSignal.setSummary(context, it) }
        val located = remember(back.intValue) { context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        StatusRow(stringResource(R.string.settings_wifi_around), if (located) stringResource(R.string.settings_wifi_around_on) else stringResource(R.string.settings_wifi_around_off), located, if (located) null else stringResource(R.string.common_settings)) {
            context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null)))
        }
        SettingRow(stringResource(R.string.settings_open_data), null, onClick = { openData(context) }) { Chevron() }
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

/** "Hey Tetra" (Settings › AI Assist): the switch, off unless turned on, and the five takes of the user's voice it matches. */
@Composable private fun WakeSection() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(Wake.on(context)) }
    var recording by remember { mutableStateOf(false) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) recording = true }
    fun record() = if (hasMic(context)) recording = true else askMic.launch(Manifest.permission.RECORD_AUDIO)
    SettingsSection(stringResource(R.string.wake_title), stringResource(R.string.wake_note)) {
        SwitchRow(stringResource(R.string.wake_title), stringResource(if (on) R.string.wake_on else R.string.wake_off), on) { want ->
            if (want && !Wake.hasTakes(context)) record()
            else { on = want; Wake.set(context, want); if (want && !Car.mayOpen(context)) Car.askOpen(context) }
        }
        SettingRow(stringResource(R.string.wake_record), onClick = { record() }) { Chevron() }
    }
    if (recording) WakeTakes(onDone = { saved -> recording = false
        if (saved) { if (!on) { on = true; Wake.set(context, true) } else Wake.reload(context); if (!Car.mayOpen(context)) Car.askOpen(context) }
    })
}

/**
 * Recording the takes: four of each phrase, the phrases one after another on this one screen; a take kept or recorded again
 * (a tap on it), an odd one marked; then "Try it" says which phrase one more was taken for, and how near.
 */
@Composable private fun WakeTakes(onDone: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val phrases = WakeWord.PHRASES
    // the kept takes to start from: only what is missing (a phrase added later) or tapped is recorded
    val takes = remember { mutableStateListOf<ShortArray?>().apply { phrases.forEach { p -> (1..Wake.TAKES).forEach { k ->
        add(Wake.take(context, p, k).takeIf { it.isFile }?.let { f -> runCatching { Wake.read(f) }.getOrNull() }) } } } }
    var busy by remember { mutableStateOf<Int?>(null) } // the take being recorded; takes.size: the try
    var message by remember { mutableStateOf<String?>(null) }
    val features = remember(takes.toList()) { phrases.indices.map { p -> takes.subList(p * Wake.TAKES, (p + 1) * Wake.TAKES).map { it?.let(WakeWord::features) } } }
    val odd = remember(features) { features.flatMap { f ->
        if (f.any { it == null }) List(f.size) { false } else WakeWord.leaveOneOut(f.map { it!! }).let { loo -> loo.map { it > WakeWord.ODD * WakeWord.median(loo) } }
    } }
    fun phraseOf(i: Int) = phrases[i / Wake.TAKES]
    fun record(i: Int) {
        busy = i; message = null
        scope.launch {
            val pcm = withContext(Dispatchers.IO) { Wake.record(if (i < takes.size) 2500 else 3000) }
            if (pcm == null) { busy = null; message = context.getString(R.string.assistant_mic_closed); return@launch }
            if (i < takes.size) {
                val speech = WakeWord.trim(WakeWord.floats(pcm)).size / WakeWord.RATE.toDouble()
                WriterLog.i("Hey Tetra: ${phraseOf(i)} take ${i % Wake.TAKES + 1}: ${"%.2f".format(speech)} s of speech")
                if (speech !in 0.25..1.6) message = context.getString(R.string.wake_not_heard) else takes[i] = pcm
            } else {
                val ready = withContext(Dispatchers.Default) { phrases.mapIndexed { p, name -> WakePhrase(name, features[p].map { it!! }) } }
                var best: Pair<WakePhrase, Double>? = null
                withContext(Dispatchers.Default) { WakeDetector(ready) { p, r -> if (best == null || r < best!!.second) best = p to r }.push(pcm + ShortArray(WakeWord.RATE * 17 / 10)) }
                val b = best
                WriterLog.i("Hey Tetra: tried: ${b?.first?.name} at ${b?.second?.let { "%.2f".format(it) }} of its threshold")
                message = if (b == null) context.getString(R.string.wake_not_heard)
                    else context.getString(if (b.second < 1) R.string.wake_try_caught else R.string.wake_try_missed, b.first.name, "%.0f%%".format(b.second * 100))
            }
            busy = null
        }
    }
    val next = takes.indexOfFirst { it == null }
    AlertDialog(onDismissRequest = { if (busy == null) onDone(false) }, title = { Text(stringResource(R.string.wake_record)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.wake_record_note), style = MaterialTheme.typography.bodyMedium)
            phrases.forEachIndexed { p, name ->
                Text("“$name”", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (0 until Wake.TAKES).forEach { k ->
                        val i = p * Wake.TAKES + k
                        val color = when { busy == i -> MaterialTheme.colorScheme.primary; odd[i] -> MaterialTheme.colorScheme.error; takes[i] != null -> MaterialTheme.colorScheme.secondaryContainer; else -> rowColor() }
                        // a kept take: a tap records it again
                        Box(Modifier.size(48.dp).clip(CircleShape).background(color).clickable(enabled = busy == null && takes[i] != null) { record(i) },
                            contentAlignment = Alignment.Center) {
                            Text(if (takes[i] != null && !odd[i]) "✓" else "${k + 1}", style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { contentDescription = context.getString(R.string.wake_take, k + 1) })
                        }
                    }
                }
                if ((p * Wake.TAKES until (p + 1) * Wake.TAKES).any { odd[it] }) Text(stringResource(R.string.wake_take_odd), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (busy != null) Text(stringResource(R.string.wake_speak, if (busy!! < takes.size) phraseOf(busy!!) else phrases.joinToString(" / ")), style = MaterialTheme.typography.bodyLarge)
            else if (next >= 0) Button(onClick = { record(next) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.wake_record_take, phraseOf(next), next % Wake.TAKES + 1)) }
            else OutlinedButton(onClick = { record(takes.size) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.wake_try)) }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        } },
        confirmButton = { TextButton(enabled = busy == null && next < 0, onClick = {
            takes.forEachIndexed { i, t -> Wake.write(Wake.take(context, phraseOf(i), i % Wake.TAKES + 1), t!!) }
            Wake.keepTakes(context)
            WriterLog.i("Hey Tetra: takes kept, odd: ${odd.withIndex().filter { it.value }.joinToString { "${phraseOf(it.index)} ${it.index % Wake.TAKES + 1}" }.ifEmpty { "none" }}")
            onDone(true)
        }) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(enabled = busy == null, onClick = { onDone(false) }) { Text(stringResource(R.string.common_cancel)) } })
}
