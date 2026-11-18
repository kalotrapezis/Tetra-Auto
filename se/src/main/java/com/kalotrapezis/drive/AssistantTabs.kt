package com.kalotrapezis.drive

import kotlinx.coroutines.launch
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.DateFormat
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Date
import org.json.JSONArray
import org.json.JSONObject

/*
 * AI Assist's places besides the chat (the user, 1 Nov 2026: "where is the history, the chats, the memory, the scheduled
 * actions"): History (this phone's own chats), Personal details (AssistantMemory, shared), Scheduled (AssistantSchedules,
 * run by the computer) with the reports they made. And the bubbles, each in its applet's colour.
 */

/** The four places the island's handle (or the tablet's sidebar) shows; a pill bar as Contacts', the label on the chosen one only. */
internal enum class AssistSection(val label: Int, val icon: Int) {
    Scheduled(R.string.assistant_tab_schedules, R.drawable.ic_schedule), History(R.string.assistant_tab_history, R.drawable.ic_history),
    Skills(R.string.assistant_assist_skills, R.drawable.ic_auto_fix), Memory(R.string.ai_memory, R.drawable.ic_person)
}

@Composable
internal fun SectionBar(section: AssistSection, pick: (AssistSection) -> Unit) =
    Surface(shape = MaterialTheme.shapes.extraLarge, color = islandColor(), contentColor = islandContentColor()) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            AssistSection.entries.forEach { s -> IslandNavigationItem(stringResource(s.label), section == s, s.icon) { pick(s) } }
        }
    }

/** What the assistant can do, from its own tools (Assistant.kt): a capability whose app the AI switch keeps out is not shown. */
internal data class Capability(val label: Int, val prompt: Int, val color: Color, val app: String?)
internal val CAPABILITIES = listOf(
    Capability(R.string.assistant_cap_call, R.string.assistant_cap_call_prompt, Color(0xFF2E8B97), "phone"),
    Capability(R.string.assistant_cap_email, R.string.assistant_cap_email_prompt, AppColor.mail, "mail"),
    Capability(R.string.assistant_cap_search, R.string.assistant_cap_search_prompt, Color(0xFF25C4FE), "web"),
    Capability(R.string.assistant_cap_meeting, R.string.assistant_cap_meeting_prompt, AppColor.calendar, "calendar"),
    Capability(R.string.assistant_cap_alarm, R.string.assistant_cap_alarm_prompt, AppColor.timer, "timer"),
    Capability(R.string.assistant_ex_timer, R.string.assistant_ex_timer, AppColor.timer, "timer"),
    Capability(R.string.assistant_cap_remind, R.string.assistant_cap_remind_prompt, AppColor.calendar, "calendar"),
    Capability(R.string.assistant_cap_note, R.string.assistant_cap_note_prompt, AppColor.notes, "notes"),
    Capability(R.string.assistant_ex_notes, R.string.assistant_ex_notes, AppColor.notes, "notes"),
    Capability(R.string.assistant_chip_image, R.string.assistant_chip_image_prompt, AppColor.photos, "web"),
    Capability(R.string.assistant_chip_research, R.string.assistant_chip_research_prompt, AppColor.assistant, "web"),
    Capability(R.string.assistant_chip_brief, R.string.assistant_chip_brief_prompt, AppColor.calendar, null),
    Capability(R.string.assistant_chip_repeat, R.string.assistant_chip_repeat_prompt, AppColor.mail, null),
    Capability(R.string.assistant_ex_mail, R.string.assistant_ex_mail, AppColor.mail, "mail"),
    Capability(R.string.assistant_ex_calendar, R.string.assistant_ex_calendar, AppColor.calendar, "calendar"),
    Capability(R.string.assistant_cap_file, R.string.assistant_cap_file_prompt, Color(0xFF177BFC), "files"),
    Capability(R.string.assistant_cap_file_show, R.string.assistant_cap_file_show_prompt, AppColor.assistant, "files"),
    Capability(R.string.assistant_cap_go, R.string.assistant_cap_go_prompt, Color(0xFF25C4FE), "maps"),
    Capability(R.string.assistant_cap_remember, R.string.assistant_cap_remember_prompt, AppColor.assistant, null),
)
internal fun capabilities() = CAPABILITIES.filter { it.app == null || AiGate.allows(it.app) }

/**
 * The empty chat's word cloud (the user, 1 Nov: "have the actions as a word cloud at the middle of the screen"): a handful
 * of what it can do, in mixed sizes and their apps' colours, a new handful every few seconds (still when the phone asks
 * for no animations). A tap puts the ready prompt in the box; nothing is sent.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WordCloud(offer: (String) -> Unit) {
    val c = LocalContext.current
    val all = remember { capabilities() }
    val still = remember { android.provider.Settings.Global.getFloat(c.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(still) { if (!still) while (true) { kotlinx.coroutines.delay(9_000); round++ } }
    val shown = remember(round) { if (all.size <= 9) all else all.shuffled(java.util.Random(round.toLong())).take(9) }
    androidx.compose.animation.Crossfade(shown, label = "cloud") { caps ->
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            caps.forEachIndexed { i, cap -> val p = stringResource(cap.prompt)
                AppChip(stringResource(cap.label), cap.color, size = listOf(1.25f, 0.95f, 1.1f, 0.9f, 1.35f, 1f, 1.15f, 0.9f, 1.05f)[i % 9]) { offer(p) } }
        }
    }
}

/** Tetra's applet colours (applet_colors.xml): what a bubble is about. */
internal object AppColor {
    val photos = Color(0xFF95DF6B); val calendar = Color(0xFFF41879); val mail = Color(0xFFFD0DA4)
    val notes = Color(0xFFFED00A); val timer = Color(0xFF04D5CC); val assistant = ASSISTANT_COLOR
}

/** A bubble tinted with its applet's colour, readable on light and dark. */
@Composable
internal fun AppChip(text: String, color: Color, size: Float = 1f, onClick: () -> Unit) {
    val surface = MaterialTheme.colorScheme.surface
    Surface(onClick = onClick, shape = CircleShape, color = color.copy(alpha = 0.22f).compositeOver(surface), contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, color.copy(alpha = 0.6f))) {
        Row(Modifier.padding(horizontal = (14 * size).dp, vertical = (8 * size).dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size((10 * size).dp).background(color, CircleShape))
            Text(text, style = MaterialTheme.typography.labelLarge.let { it.copy(fontSize = it.fontSize * size) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppChips(chips: List<Triple<String, Color, () -> Unit>>, modifier: Modifier = Modifier, center: Boolean = false) =
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp, if (center) Alignment.CenterHorizontally else Alignment.Start), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEach { (t, c, go) -> AppChip(t, c, onClick = go) }
    }

private val dateTime: DateFormat get() = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

@Composable
internal fun HistoryTab(offer: (String) -> Unit, open: (AssistantChats.Saved) -> Unit) {
    val c = LocalContext.current
    OpenTiming("AI History", true)
    var chats by remember { mutableStateOf(AssistantChats.list(c)) }
    if (chats.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.assistant_assist_history_hint), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AppChips(listOf(R.string.assistant_ex_calendar to AppColor.calendar, R.string.assistant_ex_mail to AppColor.mail).map { (r, col) -> val t = stringResource(r); Triple(t, col) { offer(t) } })
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(chats, key = { it.id }) { s ->
            Card(Modifier.fillMaxWidth().clickable { open(s) }) {
                Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(dateTime.format(Date(s.at)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { AssistantChats.delete(c, s.id); chats = AssistantChats.list(c) }) { Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.common_delete)) }
                }
            }
        }
    }
}

@Composable
internal fun MemoryTab() {
    OpenTiming("AI Memory", true)
    val tetra = remember { driveRoot() }
    val device = remember { TimerControl.device() }
    var facts by remember { mutableStateOf(AssistantMemory.list(tetra)) }
    var typed by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<AssistantMemory.Fact?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    fun reload() { facts = AssistantMemory.list(tetra) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (facts.isEmpty()) R.string.assistant_assist_mem_empty else R.string.ai_memory_note), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // a few short facts: they go into every request, and the model's window is small
                Text(stringResource(R.string.assistant_assist_mem_count, facts.size, AssistantMemory.MAX), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(typed, { typed = it }, Modifier.weight(1f), placeholder = { Text(stringResource(R.string.ai_memory_hint)) }, shape = RoundedCornerShape(16.dp))
                FilledTonalButton(onClick = { runCatching { AssistantMemory.add(tetra, typed, device) }.onSuccess { typed = ""; problem = null; reload() }.onFailure { problem = it.message } }, enabled = typed.isNotBlank() && facts.size < AssistantMemory.MAX) { Text(stringResource(R.string.common_add)) }
            }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        items(facts, key = { it.id }) { f ->
            Card(Modifier.fillMaxWidth().clickable { editing = f }) {
                Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    IconButton(onClick = { AssistantMemory.change(tetra, f.id, "", device); reload() }) { Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.common_delete)) }
                }
            }
        }
    }
    editing?.let { f ->
        var text by remember(f.id) { mutableStateOf(f.text) }
        TetraAlertDialog(onDismissRequest = { editing = null }, title = { Text(stringResource(R.string.common_edit)) },
            text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth()) },
            confirmButton = { TextButton({ AssistantMemory.change(tetra, f.id, text, device); editing = null; reload() }) { Text(stringResource(R.string.common_save)) } },
            dismissButton = { TextButton({ editing = null }) { Text(stringResource(R.string.common_cancel)) } })
    }
}

private val REPEAT_LABEL = mapOf("once" to R.string.assistant_sched_once, "daily" to R.string.assistant_sched_daily, "weekly" to R.string.assistant_sched_weekly,
    "monthly" to R.string.assistant_sched_monthly, "yearly" to R.string.assistant_sched_yearly)

/** The runner in words: this device, another phone or tablet by its model, or a computer (by its name when it has one). */
private fun runnerName(c: Context, r: String?) = when {
    r == AssistantRunner.me() -> c.getString(R.string.brief_runs_here)
    r?.startsWith("android:") == true -> r.removePrefix("android:")
    r == null -> c.getString(R.string.brief_runs_computer)
    else -> c.getString(R.string.brief_runs_computer) + " (" + r + ")"
}

private fun weekday(c: Context, n: Int) = DayOfWeek.of(if (n == 0) 7 else n).getDisplayName(TextStyle.SHORT, c.uiLocale())

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScheduledTab(offer: (String) -> Unit) {
    val c = LocalContext.current
    OpenTiming("AI Scheduled", true)
    val tetra = remember { driveRoot() }
    var list by remember { mutableStateOf(AssistantSchedules.list(tetra)) }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    var pages by remember { mutableStateOf(AssistantBriefs.pages()) }
    var place by remember { mutableStateOf(AssistantSchedules.place(tetra)) }
    var placing by remember { mutableStateOf(false) }
    var runner by remember { mutableStateOf(AssistantSchedules.runner(tetra)) }
    fun reload() { list = AssistantSchedules.list(tetra); pages = AssistantBriefs.pages(); place = AssistantSchedules.place(tetra); runner = AssistantSchedules.runner(tetra); AssistantRunner.planSoon(c) }
    LaunchedEffect(Unit) { AssistantRunner.planSoon(c) } // a Run now asked here, or one missed while the phone was off
    fun sync() = ScopedSync.soon(c, "assistant", 0, orWhole = true)
    fun change(id: String, x: JSONObject) = runCatching { AssistantSchedules.change(tetra, id, x) }.onSuccess { sync(); reload() }.onFailure { said = it.message }
    val title = { s: JSONObject -> if (s.optString("kind") == "brief") c.getString(R.string.assistant_briefs_channel) else s.optString("title") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.assistant_sched_how), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { editing = JSONObject().put("time", "08:00").put("repeat", "daily").put("days", JSONArray(listOf(1))) }) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.assistant_sched_new))
                }
            }
            said?.let { Text(it, Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.primary) }
            // deleted (the user, 6 Nov: Delete on its card too): one tap brings it back
            if (list.none { it.optString("kind") == "brief" }) TextButton({ AssistantSchedules.addBrief(tetra); sync(); reload() }) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.assistant_briefs_channel))
            }
            // one tap to a ready prompt: an empty list helps make the first one
            AppChips(listOf(Triple(R.string.assistant_sched_ex1, R.string.assistant_chip_brief_prompt, AppColor.calendar), Triple(R.string.assistant_sched_ex2, R.string.assistant_sched_ex2_prompt, AppColor.calendar),
                Triple(R.string.assistant_sched_ex3, R.string.assistant_chip_repeat_prompt, AppColor.mail)).map { (l, pr, col) -> val p = stringResource(pr); Triple(stringResource(l), col) { offer(p) } }, Modifier.padding(top = 10.dp))
        }
        items(list, key = { it.optString("id") }) { s ->
            val id = s.optString("id")
            val how = c.getString(REPEAT_LABEL[s.optString("repeat")] ?: R.string.assistant_sched_daily) + when (s.optString("repeat")) {
                "weekly" -> s.optJSONArray("days")?.let { a -> " (" + (0 until a.length()).joinToString(", ") { weekday(c, a.optInt(it)) } + ")" }.orEmpty()
                "daily" -> ""
                else -> s.optString("date").takeIf { !s.isNull("date") && it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            } + " · " + s.optString("time") + (s.optString("until").takeIf { !s.isNull("until") && it.isNotBlank() }?.let { " → $it" } ?: "")
            val state = if (s.optBoolean("paused")) c.getString(R.string.assistant_sched_paused)
                else AssistantSchedules.due(s)?.let { c.getString(R.string.assistant_sched_next, dateTime.format(Date(it))) } ?: c.getString(R.string.common_done)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // its last run (the user, 14 Nov): green done, yellow waiting for a yes, red failed; none yet, the app's colour
                        Box(Modifier.size(10.dp).background(when (s.optString("result")) { "ok" -> Color(0xFF2E9D4F); "held" -> Color(0xFFE0A800); "failed" -> Color(0xFFD93636)
                            else -> if (s.optString("kind") == "brief") AppColor.calendar else AppColor.mail }, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(title(s), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text("$how · $state", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // the weather's place, in sight (the user, 4 Nov: "where do I declare location"); unset, where the phone is
                    if (s.optString("kind") == "brief") Text(stringResource(R.string.brief_place) + ": " + (place?.optString("name") ?: stringResource(R.string.brief_place_phone)),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.brief_runs_on) + ": " + runnerName(c, runner), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                        TextButton({ change(id, JSONObject().put("runNow", true)).onSuccess { said = c.getString(R.string.assistant_sched_asked) } }) { Text(stringResource(R.string.assistant_sched_run_now)) }
                        TextButton({ change(id, JSONObject().put("paused", !s.optBoolean("paused"))) }) { Text(stringResource(if (s.optBoolean("paused")) R.string.common_resume else R.string.common_pause)) }
                        TextButton({ editing = JSONObject(s.toString()) }) { Text(stringResource(R.string.common_edit)) }
                        if (s.optString("kind") == "brief") TextButton({ placing = true }) { Text(stringResource(R.string.brief_place)) }
                        TextButton({ AssistantSchedules.remove(tetra, id); sync(); reload() }) { Text(stringResource(R.string.common_delete)) }
                    }
                }
            }
        }
        item { Text(stringResource(R.string.assistant_briefs), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium) }
        if (pages.isEmpty()) item { Text(stringResource(R.string.assistant_briefs_none), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(pages, key = { it.name }) { f ->
            Card(Modifier.fillMaxWidth().clickable { c.startActivity(Intent(c, BriefActivity::class.java).putExtra("file", f.absolutePath)) }) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(AssistantBriefs.title(f), style = MaterialTheme.typography.titleSmall)
                    Text(DateFormat.getDateInstance(DateFormat.FULL).format(Date(f.lastModified())), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (placing) PlaceDialog(place, close = { placing = false }) { p -> runCatching { AssistantSchedules.setPlace(tetra, p) }.onSuccess { placing = false; sync(); reload() }.onFailure { said = it.message } }
    editing?.let { e -> ScheduleDialog(e, runner, close = { editing = null }) { x, sections, who ->
        runCatching {
            if (who != runner) AssistantSchedules.setRunner(tetra, who)
            if (e.has("id")) { sections?.let { AssistantSchedules.setSections(tetra, it) }; AssistantSchedules.change(tetra, e.getString("id"), x) } else AssistantSchedules.add(tetra, x)
        }.onSuccess { editing = null; said = null; sync(); reload() }.onFailure { said = it.message }
    } }
}

/** A scheduled action's permission groups (AssistantGuard.ALLOW) and their words. */
internal val ALLOW_LABEL = listOf("calendar" to R.string.assistant_sched_allow_calendar, "notes" to R.string.assistant_sched_allow_notes,
    "timers" to R.string.assistant_sched_allow_timers, "files" to R.string.assistant_sched_allow_files)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleDialog(s: JSONObject, runnerNow: String?, close: () -> Unit, save: (JSONObject, JSONObject?, String?) -> Unit) {
    val c = LocalContext.current
    val brief = s.optString("kind") == "brief"
    var prompt by remember { mutableStateOf(s.optString("prompt")) }
    var time by remember { mutableStateOf(s.optString("time", "08:00")) }
    var repeat by remember { mutableStateOf(s.optString("repeat", "daily")) }
    var days by remember { mutableStateOf(s.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.optInt(it) }.toSet() } ?: setOf(1)) }
    var date by remember { mutableStateOf(s.optString("date").takeIf { !s.isNull("date") }.orEmpty()) }
    // what it may do on its own after mail or the web (the user, 14 Nov: "can I save my permission")
    var allow by remember { mutableStateOf(s.optJSONArray("allow")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()) }
    val tetra = remember { driveRoot() }
    val now = remember { AssistantSchedules.read(tetra).optJSONObject("brief")?.optJSONObject("sections") ?: JSONObject() }
    val sections = remember { mutableStateMapOf<String, Boolean>().apply { listOf("calendar", "mail", "weather", "notes").forEach { put(it, now.optBoolean(it, true)) } } }
    var menu by remember { mutableStateOf(false) }
    // which device makes them (the user, 6 Nov: "the phone is always open"): the computer (null: the first to look takes it) or this one
    var who by remember { mutableStateOf(runnerNow) }
    var whoMenu by remember { mutableStateOf(false) }
    TetraAlertDialog(onDismissRequest = close,
        title = { Text(if (brief) stringResource(R.string.assistant_briefs_channel) else if (s.has("id")) stringResource(R.string.common_edit) else stringResource(R.string.assistant_sched_new)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!brief) OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.assistant_sched_prompt)) }, minLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(time, { time = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.assistant_sched_time)) }, singleLine = true)
                    ExposedDropdownMenuBox(menu, { menu = it }, Modifier.weight(1.4f)) {
                        OutlinedTextField(stringResource(REPEAT_LABEL[repeat]!!), {}, Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable), readOnly = true, label = { Text(stringResource(R.string.assistant_sched_repeat)) })
                        ExposedDropdownMenu(menu, { menu = false }) { AssistantSchedules.REPEATS.forEach { r -> DropdownMenuItem({ Text(stringResource(REPEAT_LABEL[r]!!)) }, { repeat = r; menu = false }) } }
                    }
                }
                ExposedDropdownMenuBox(whoMenu, { whoMenu = it }) {
                    OutlinedTextField(runnerName(c, who), {}, Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable), readOnly = true, label = { Text(stringResource(R.string.brief_runs_on)) })
                    ExposedDropdownMenu(whoMenu, { whoMenu = false }) {
                        listOf(runnerNow?.takeUnless { it.startsWith("android:") }, runnerNow?.takeIf { it.startsWith("android:") && it != AssistantRunner.me() }, AssistantRunner.me())
                            .filterIndexed { i, r -> i == 0 || r != null }.forEach { r ->
                            DropdownMenuItem({ Text(runnerName(c, r)) }, { who = r; whoMenu = false })
                        }
                    }
                }
                if (repeat in listOf("once", "monthly", "yearly")) OutlinedTextField(date, { date = it }, Modifier.fillMaxWidth(), label = { Text("YYYY-MM-DD") }, singleLine = true)
                if (repeat == "weekly") FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 2, 3, 4, 5, 6, 0).forEach { d -> FilterChip(d in days, { days = if (d in days) days - d else days + d }, { Text(weekday(c, d)) }) }
                }
                if (!brief) {
                    Text(stringResource(R.string.assistant_sched_allow), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.assistant_sched_allow_how), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ALLOW_LABEL.forEach { (k, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(k in allow, { allow = if (it) allow + k else allow - k }); Text(stringResource(label)) }
                    }
                }
                if (brief) {
                    Text(stringResource(R.string.assistant_brief_sections), style = MaterialTheme.typography.titleSmall)
                    listOf("calendar" to R.string.app_calendar, "mail" to R.string.app_mail, "weather" to R.string.assistant_brief_weather, "notes" to R.string.assistant_brief_lists).forEach { (k, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(sections[k] == true, { sections[k] = it }); Text(stringResource(label)) }
                    }
                }
            }
        },
        confirmButton = { TextButton({
            val x = JSONObject().put("time", time.trim()).put("repeat", repeat).put("days", JSONArray(days.sorted())).put("date", date.trim().ifEmpty { JSONObject.NULL })
            if (!brief) x.put("prompt", prompt).put("allow", JSONArray(ALLOW_LABEL.map { it.first }.filter { it in allow }))
            save(x, if (brief) JSONObject(sections.toMap() as Map<*, *>) else null, who)
        }, enabled = brief || prompt.isNotBlank()) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(close) { Text(stringResource(R.string.common_cancel)) } })
}

/** Every skill, by app colour, wrapped on rows: a tap puts its ready prompt in the box. */
@Composable
internal fun SkillsTab(offer: (String) -> Unit) = Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(12.dp)) {
    AppChips(capabilities().map { cap -> val p = stringResource(cap.prompt); Triple(stringResource(cap.label), cap.color) { offer(p) } })
}

/** The brief's weather place: a town searched for, or none (where the phone is when the brief is made). */
@Composable
private fun PlaceDialog(now: JSONObject?, close: () -> Unit, set: (JSONObject?) -> Unit) {
    val c = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var missing by remember { mutableStateOf(false) }
    fun find() {
        if (name.isBlank() || busy) return
        busy = true; missing = false
        scope.launch {
            val p = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { AssistantSchedules.findPlace(name, c.uiLocale().language) }.getOrNull() }
            busy = false
            if (p == null) missing = true else set(p)
        }
    }
    TetraAlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.brief_place)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(now?.optString("name") ?: stringResource(R.string.brief_place_phone), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(name, { name = it; missing = false }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(stringResource(R.string.brief_place_hint)) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { find() }))
            if (missing) Text(stringResource(R.string.brief_place_not_found), color = MaterialTheme.colorScheme.error)
            if (now != null) TextButton({ set(null) }) { Text(stringResource(R.string.brief_place_use_phone)) }
        } },
        confirmButton = { TextButton(::find, enabled = name.isNotBlank() && !busy) { Text(stringResource(R.string.common_search)) } },
        dismissButton = { TextButton(close) { Text(stringResource(R.string.common_cancel)) } })
}
