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
    History(R.string.assistant_tab_history, R.drawable.ic_history),
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
    val device = remember { (android.os.Build.MODEL ?: "Android") }
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
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(stringResource(R.string.common_edit)) },
            text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth()) },
            confirmButton = { TextButton({ AssistantMemory.change(tetra, f.id, text, device); editing = null; reload() }) { Text(stringResource(R.string.common_save)) } },
            dismissButton = { TextButton({ editing = null }) { Text(stringResource(R.string.common_cancel)) } })
    }
}

/** Every skill, by app colour, wrapped on rows: a tap puts its ready prompt in the box. */
@Composable
internal fun SkillsTab(offer: (String) -> Unit) = Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(12.dp)) {
    AppChips(capabilities().map { cap -> val p = stringResource(cap.prompt); Triple(stringResource(cap.label), cap.color) { offer(p) } })
}
