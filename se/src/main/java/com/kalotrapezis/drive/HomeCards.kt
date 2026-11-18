package com.kalotrapezis.drive

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle

/**
 * Home's live cards (the user, 8 Nov: "cards with active content… today's pills and click I took it, the timer with the
 * favourite timers, the next events, unread mail, a mic for the assistant and the daily report"). What each shows is
 * chosen by the plain functions here (HomeCardsTest); the cards read the same synced files as their apps, on IO, and
 * look again at Home's one tick (a minute, only while Home is on screen).
 */
internal object HomeCards {
    /** Home's clock: one a minute while Home is started (HomeTicker), the cards' cue to look again; nothing runs behind. */
    val tick = androidx.compose.runtime.mutableIntStateOf(0)

    /** A Timer tile: a pinned timer (restarted by [id]) or one of the four times that start at a tap. */
    data class TimerSlot(val id: String?, val label: String, val minutes: Int?)

    /** The user's pinned timers first, then the four times as before, four tiles in all. */
    fun timerSlots(pinned: List<Pair<String, String>>, presets: List<Int> = listOf(6, 15, 20, 30), minutes: (Int) -> String): List<TimerSlot> =
        (pinned.take(4).map { (id, label) -> TimerSlot(id, label, null) } + presets.map { TimerSlot(null, minutes(it), it) }).take(4)

    /** Unread Inbox mail, most urgent first (ai/urgency.jsonl), then newest; the count of all of them. */
    fun unread(entries: List<MailEntry>, urgency: Map<String, Int>, n: Int = 3): Pair<Int, List<MailEntry>> {
        val all = entries.filter { it.folder == "Inbox" && !it.seen && !it.gone }
        return all.size to all.sortedWith(compareByDescending<MailEntry> { urgency[it.id] ?: 0 }.thenByDescending { it.date }).take(n)
    }

    /** How early «Το πήρα» may answer a dose: half an hour before its time, as a reminder would be near. */
    const val EARLY_S = 30 * 60L

    /**
     * The time «Το πήρα» answers: the earliest dose still waiting or missed whose time has come (or is within [EARLY_S]).
     * A later dose of the day is not taken by it (8 Nov: at 11:38 it would have taken the 14:00 one).
     */
    fun toTake(doses: List<Dose>, now: Instant = Instant.now()): Instant? =
        doses.filter { (it.state == Dose.State.Waiting || it.state == Dose.State.Missed) && !it.due.isAfter(now.plusSeconds(EARLY_S)) }.minOfOrNull { it.due }

    /** The header's pill button (8 Nov: "a button with a pill, that I took the next one"): the earliest dose not answered yet. */
    fun nextToTake(doses: List<Dose>): Dose? = doses.filter { it.state == Dose.State.Waiting || it.state == Dose.State.Missed }.minByOrNull { it.due }

    /**
     * The history's squares (8 Nov: "strong when I take them on time, light when later, empty when I missed"): 2 on time
     * (within half an hour), 1 late, 0 missed or skipped; doses still to come are left out.
     */
    fun waffle(doses: List<Dose>): List<Int> = doses.sortedBy { it.due }.mapNotNull { d ->
        when (d.state) { Dose.State.Taken -> if (d.lateMin() <= 30) 2 else 1; Dose.State.Missed, Dose.State.Skipped -> 0; Dose.State.Waiting -> null }
    }

    /** Taken of today's doses, for the header: «1/3». */
    fun takenOf(doses: List<Dose>) = "${doses.count { it.state == Dose.State.Taken }}/${doses.size}"

    /** A dose's line: its time and, unless reminders are private, the medicine × amount. */
    fun doseLine(d: Dose, time: String, private: Boolean) = if (private) time else "$time  ${d.med.title} × ${Medicine.amount(d.med.dose)}"

    /** The urgency dot's colour: none, low, needs attention, urgent. */
    fun urgencyColor(score: Int): Color? = when (score) { 3 -> Color(0xFFE53935); 2 -> Color(0xFFFB8C00); 1 -> Color(0xFFFDD835); else -> null }

    /** How long a missed call stays on Home (8 Nov: yesterday's still showed, as if today's, and could not be cleared). */
    const val MISSED_MS = 2 * 86_400_000L

    /** The missed calls Home shows: of the last [MISSED_MS], after the user last cleared them, the newest [n]. */
    fun missedToShow(calls: List<LoggedCall>, clearedAt: Long, now: Long = System.currentTimeMillis(), n: Int = 2) =
        calls.filter { it.missed && it.at > maxOf(clearedAt, now - MISSED_MS) }.sortedByDescending { it.at }.take(n)

    /** Passwords' tiles: the ♥ logins, by site. */
    fun favouriteLogins(logins: List<SavedLogin>) = logins.filter { it.pinned }.sortedBy { it.host }

    /** The 2FA code Home shows: the first ♥ one, else the first. */
    fun codeToShow(codes: List<OtpEntry>) = codes.firstOrNull { it.pinned } ?: codes.firstOrNull()

    /** Unlocked on Home this long, then locked again (and at once when Home goes). */
    const val UNLOCKED_MS = 60_000L

    /** Text on a card's own colour: dark on a light one (Data's lime, Notes' yellow), white on a deep one. */
    fun onColor(c: Color): Color = if (c.luminance() > 0.45f) Color(0xFF1B1B1B) else Color.White
}

/** Keeps HomeCards.tick going once a minute, on the minute, while Home is started; stopped behind another app or screen. */
@Composable internal fun HomeTicker() {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            HomeCards.tick.intValue++ // back on Home: look at once
            while (true) { delay(60_000 - System.currentTimeMillis() % 60_000); HomeCards.tick.intValue++ }
        }
    }
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")

/**
 * One Home widget (the council, 8 Nov: Opus, Sonnet, Haiku; the user: "an icon at the top corner of the widget and not
 * double the space just for a button"): one surface in the app's colour; a 40 dp header — the app's icon, its name and
 * a count, the whole of it opening the app — with at most one action at its right; the content fills the rest, on a
 * light panel for lists ([panel]) or straight on the colour for tiles. No art half, no card in a card.
 */
@Composable internal fun HomeWidget(
    color: Color, art: Int, title: String, open: () -> Unit, badge: String? = null, panel: Boolean = true,
    action: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit,
) = Surface(shape = MaterialTheme.shapes.extraLarge, color = color, contentColor = HomeCards.onColor(color), modifier = Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.weight(1f).fillMaxHeight().clip(MaterialTheme.shapes.medium).clickable(onClick = open).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArtImage(art, 28.dp)
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (badge != null) Text(badge, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
            action?.invoke()
        }
        Spacer(Modifier.height(6.dp))
        if (panel) Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f), contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
        }
        else Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center) { CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { content() } }
    }
}

/** A Home tile's height (8 Nov, the user: "you make the targets too big, it can fit more"): 50 dp, a 48 dp touch and a line. */
internal val HOME_TILE = 50.dp

/**
 * [items] as tiles in as many columns (each at least [minWidth]) and rows (each [height]) as the widget's room holds; fewer
 * items than columns spread over the width. A square on a tablet takes two rows of five, a wide card more.
 */
@Composable internal fun <T> HomeTiles(items: List<T>, minWidth: androidx.compose.ui.unit.Dp = 72.dp, height: androidx.compose.ui.unit.Dp = HOME_TILE, gap: androidx.compose.ui.unit.Dp = 8.dp,
    maxCols: Int = 8, tile: @Composable (T, Modifier) -> Unit) = androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    val cols = ((maxWidth + gap) / (minWidth + gap)).toInt().coerceIn(1, maxCols)
    val rows = ((maxHeight + gap) / (height + gap)).toInt().coerceIn(1, 6)
    val shown = items.take(cols * rows)
    val across = minOf(cols, shown.size.coerceAtLeast(1))
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        shown.chunked(across).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { tile(it, Modifier.weight(1f).height(height)) }
                repeat(across - row.size) { Spacer(Modifier.weight(1f)) } // the last row keeps the others' width
            }
        }
    }
}

/** The header's one action: a small pill, 36 dp to see, 48 dp to touch. */
@Composable internal fun HomeWidgetAction(label: String, icon: Int? = null, click: () -> Unit) =
    FilledTonalButton(onClick = click, contentPadding = PaddingValues(horizontal = 14.dp), modifier = Modifier.height(36.dp)) {
        if (icon != null) Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp).padding(end = 4.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }

/** As many of [items] as fit in the room left (a line is about 20 dp): a taller widget shows more (8 Nov). */
@Composable private fun <T> Fitting(items: List<T>, line: androidx.compose.ui.unit.Dp = 20.dp, row: @Composable (T) -> Unit) =
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fit = (maxHeight / line).toInt().coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { items.take(fit).forEach { row(it) } }
    }

@Composable private fun PanelLine(text: String, modifier: Modifier = Modifier, bold: Boolean = false) =
    Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = if (bold) FontWeight.SemiBold else null, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)

/**
 * Pills: «1/3» in the header and a pill button that takes the next dose (8 Nov), today's doses below; on the large card the
 * last weeks' doses as squares beside them.
 */
@Composable internal fun HomePillsGroup(open: () -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val changed = PillsAlarms.changed.intValue
    val tick = HomeCards.tick.intValue // waiting turns missed with time alone
    val zone = ZoneId.systemDefault()
    val medium = LocalHomeMedium.current
    val days by produceState(emptyList<Dose>() to emptyList<Dose>(), changed, tick) {
        value = withContext(Dispatchers.IO) { runCatching {
            val dir = Pills.phoneDir(); val log = Pills.log(dir); val meds = Pills.medicines(dir, log); val now = Instant.now()
            val today = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant()
            Pills.today(meds, log, now, zone) to Pills.doses(meds, log, today.minusSeconds(42 * 86_400L), today, now, zone)
        }.getOrDefault(emptyList<Dose>() to emptyList()) }
    }
    val (doses, before) = days
    val private = remember(changed) { PillsAlarms.private(c) }
    val next = HomeCards.nextToTake(doses)
    HomeWidget(PILLS_COLOR, R.drawable.pills_art, stringResource(R.string.app_pills), open, badge = doses.takeIf { it.isNotEmpty() }?.let { HomeCards.takenOf(it) },
        action = next?.let { d -> {
            val said = stringResource(R.string.home_pill_marked, HomeCards.doseLine(d, TIME.format(d.due.atZone(zone)), private))
            FilledTonalButton(onClick = {
                scope.launch(Dispatchers.IO) { runCatching { PillsAlarms.answer(c, PillsAlarms.TAKE, d.due) }.onSuccess {
                    withContext(Dispatchers.Main) { android.widget.Toast.makeText(c, said, android.widget.Toast.LENGTH_SHORT).show() }
                }.onFailure { WriterLog.e("Home: pills not taken", it) } }
            }, contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.height(36.dp)) {
                Icon(painterResource(R.drawable.ic_pill), contentDescription = stringResource(R.string.pills_taken), modifier = Modifier.size(20.dp))
            }
        } }) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                when {
                    doses.isEmpty() -> PanelLine(stringResource(R.string.home_pills_none))
                    doses.all { it.state == Dose.State.Taken || it.state == Dose.State.Skipped } -> PanelLine("✓ " + stringResource(R.string.home_pills_done), bold = true)
                }
                Fitting(doses.sortedBy { it.due }) { d ->
                    Row(Modifier.fillMaxWidth().clickable(onClick = open), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(painterResource(when (d.state) { Dose.State.Taken -> R.drawable.ic_check; Dose.State.Skipped -> R.drawable.ic_block; Dose.State.Missed -> R.drawable.ic_close; else -> R.drawable.ic_schedule }),
                            contentDescription = null, modifier = Modifier.size(14.dp))
                        PanelLine(HomeCards.doseLine(d, TIME.format(d.due.atZone(zone)), private), bold = d.due == next?.due)
                    }
                }
            }
            if (!medium && before.isNotEmpty()) HomeWaffle(HomeCards.waffle(before + doses), PILLS_COLOR, Modifier.weight(1f).fillMaxHeight().clickable(onClick = open))
        }
    }
}

/** Doses as squares, the newest last (bottom right): strong on time, light late, an outline missed; as many as fit. */
@Composable internal fun HomeWaffle(marks: List<Int>, color: Color, modifier: Modifier) = Canvas(modifier) {
    val cell = 11.dp.toPx(); val gap = 3.dp.toPx(); val r = androidx.compose.ui.geometry.CornerRadius(2.5.dp.toPx())
    val cols = ((size.width + gap) / (cell + gap)).toInt().coerceAtLeast(1); val rows = ((size.height + gap) / (cell + gap)).toInt().coerceAtLeast(1)
    marks.takeLast(cols * rows).forEachIndexed { i, m ->
        val at = Offset((i % cols) * (cell + gap), (i / cols) * (cell + gap))
        when (m) {
            2 -> drawRoundRect(color, at, Size(cell, cell), r)
            1 -> drawRoundRect(color.copy(alpha = 0.4f), at, Size(cell, cell), r)
            else -> drawRoundRect(color.copy(alpha = 0.7f), at + Offset(0.5f, 0.5f), Size(cell - 1f, cell - 1f), r, style = Stroke(1.dp.toPx()))
        }
    }
}

/** Mail: the unread count in the header, the four most urgent below (the desktop's ai/urgency.jsonl), each opening its mail. */
@Composable internal fun HomeMailGroup(open: () -> Unit) {
    val c = LocalContext.current
    val tick = HomeCards.tick.intValue / 2 // every other minute
    val shown by produceState(0 to emptyList<Pair<MailEntry, Int>>(), tick) {
        value = withContext(Dispatchers.IO) { runCatching {
            val root = mailRoot()
            val urgency = MailAi.urgency(root).mapValues { it.value.score }
            HomeCards.unread(MailFiles.index(root, MailEngine.device()), urgency, n = 12).let { (n, list) -> n to list.map { it to (urgency[it.id] ?: 0) } }
        }.getOrDefault(0 to emptyList()) }
    }
    val (n, list) = shown
    HomeWidget(Category.Mail.color, R.drawable.mail_art, stringResource(R.string.app_mail), open, badge = if (n > 0) stringResource(R.string.home_mail_unread, n) else null) {
        if (n == 0) PanelLine(stringResource(R.string.home_mail_none), bold = true)
        Fitting(list) { (e, score) ->
            Row(Modifier.fillMaxWidth().clickable { openApplet(c, "mail:${e.id}") }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).background(HomeCards.urgencyColor(score) ?: MaterialTheme.colorScheme.primary, CircleShape))
                PanelLine(e.from.ifBlank { e.fromAddress }, Modifier.weight(0.4f), bold = true)
                PanelLine(e.subject, Modifier.weight(0.6f))
            }
        }
    }
}

/** Calendar: «+» in the header, the next four events of two weeks below; none, then [empty] (the four «New» tiles). */
@Composable internal fun HomeCalendarGroup(open: () -> Unit, newEvent: () -> Unit, empty: @Composable () -> Unit) {
    val tick = HomeCards.tick.intValue / 5 // every five minutes
    val zone = ZoneId.systemDefault()
    val today = java.time.LocalDate.now(zone)
    val monday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
    // this week's (for its dots) and the next two weeks' events
    val all by produceState<List<Occurrence>?>(null, tick) {
        value = withContext(Dispatchers.IO) { runCatching {
            CalendarFiles.occurrences(CalendarFiles.calendars(calendarRoot()), monday.atStartOfDay(zone).toInstant(), Instant.now().plusSeconds(14 * 86_400L))
        }.getOrDefault(emptyList()) }
    }
    // birthdays of the next two weeks (8 Nov: "the nearest birthdays are a month from now, no need to have it"): the card
    // turns between the week and them every eight seconds; none near, the week alone
    val near by produceState(emptyList<Birthday>(), tick) { value = withContext(Dispatchers.IO) { birthdays(14) } }
    var cake by remember { mutableStateOf(false) }
    LaunchedEffect(near.isNotEmpty()) { cake = false; while (near.isNotEmpty()) { delay(8_000); cake = !cake } }
    val now = Instant.now()
    val list = all?.filter { it.end.toInstant() > now }?.take(10)
    val busy = all.orEmpty().map { it.start.withZoneSameInstant(zone).toLocalDate() }.toSet()
    val day = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    HomeWidget(Category.Calendar.color, R.drawable.calendar_art, stringResource(R.string.app_calendar), open, action = { HomeWidgetAction("+", null, newEvent) }) {
        if (cake && near.isNotEmpty()) { BirthdayView(near, open); return@HomeWidget }
        // the week, Monday first, today in a circle, a dot under a day with events
        Row(Modifier.fillMaxWidth().clickable(onClick = open)) {
            (0L..6L).map { monday.plusDays(it) }.forEach { d ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(d.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, locale), style = MaterialTheme.typography.labelSmall)
                    Box(Modifier.size(22.dp).background(if (d == today) Category.Calendar.color else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                        Text("${d.dayOfMonth}", style = MaterialTheme.typography.labelMedium, fontWeight = if (d == today) FontWeight.Bold else null, color = if (d == today) Color.White else Color.Unspecified)
                    }
                    Box(Modifier.size(4.dp).background(if (d in busy) Category.Calendar.color else Color.Transparent, CircleShape))
                }
            }
        }
        if (list == null) return@HomeWidget
        if (list.isEmpty()) { PanelLine(stringResource(R.string.calendar_no_events)); return@HomeWidget }
        Fitting(list) { o ->
            Row(Modifier.fillMaxWidth().clickable(onClick = open), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).background(Color(o.calendar.color), CircleShape))
                PanelLine(if (o.allDay) day.format(o.start) else "${day.format(o.start)} ${TIME.format(o.start)}", bold = true)
                PanelLine(o.event.title, Modifier.weight(1f))
            }
        }
    }
}

/** AI Assist on Home, one row: the latest brief's title (its page opens), and a mic that starts listening at once. */
@Composable internal fun HomeAssistantCard(openChat: () -> Unit) {
    val c = LocalContext.current
    val brief by produceState<Pair<String, File>?>(null, HomeCards.tick.intValue / 15) {
        value = withContext(Dispatchers.IO) { runCatching {
            val o = JSONObject(File(AssistantBriefs.dir(), "latest.json").readText())
            val f = File(AssistantBriefs.dir(), File(o.optString("file")).name).takeIf { it.isFile } ?: AssistantBriefs.pages().first()
            o.optString("title").ifBlank { AssistantBriefs.title(f) } to f
        }.getOrNull() }
    }
    val talking = rememberHomeTalk()
    // a square (medium) card: Live's cassette, still on Home (8 Nov: "this new widget of the assistant is perfect for the cassette"),
    // the latest brief's title under it, the mic in its corner
    if (LocalHomeMedium.current) return Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(10.dp)) {
            val now = talking.phase
            Column(Modifier.fillMaxSize().clickable { if (now != null) talking.end() else brief?.let { b -> c.startActivity(Intent(c, BriefActivity::class.java).putExtra("file", b.second.absolutePath)) } ?: openChat() },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                // talking: the robot on a small TV, on the widget itself (8 Nov); resting: the cassette and the brief's title
                val decorated = remember(now) { AiLook.decorated(c) }
                if (!decorated) { // plain (AI decorations off): the robot, and what it is doing in words
                    ArtImage(R.drawable.assistant_art, 64.dp)
                    if (now != null) Text(stringResource(when (now) { LivePhase.Listening -> R.string.assistant_listening; LivePhase.Thinking -> R.string.assistant_thinking; else -> R.string.app_assistant }),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
                else if (now != null) TalkPlayer(talking, now, Modifier.weight(1f, fill = false).fillMaxWidth(0.9f))
                else MiniTv(Modifier.weight(1f, fill = false).fillMaxWidth(0.8f)) { RestingMascot() } // resting: the mascot on its TV, blinking (8 Nov's new look)
                Text(brief?.first ?: stringResource(R.string.app_assistant), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            }
            IconButton(onClick = { talking.tap { talk(c) } }, modifier = Modifier.align(Alignment.BottomEnd).size(48.dp)) {
                Surface(shape = CircleShape, color = if (now == LivePhase.Listening) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_mic), contentDescription = stringResource(R.string.assistant_tap_to_talk), tint = MaterialTheme.colorScheme.onPrimary) }
                }
            }
            // ✕ at the top right while it talks: stops it (8 Nov)
            if (now != null) IconButton(onClick = { talking.end() }, modifier = Modifier.align(Alignment.TopEnd).size(40.dp)) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_close))
            }
        }
    }
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(MaterialTheme.shapes.medium).clickable(onClick = openChat).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (AiLook.decorated(c)) MiniTv(Modifier.height(52.dp)) { talking.phase?.let { TalkScreen(it, talking.hearing, Modifier.fillMaxSize(), talking.cue, talking.mood, characterColor(AssistantVoices.id(c))) } ?: RestingMascot() }
                else ArtImage(R.drawable.assistant_art, 36.dp)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.app_assistant), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    val b = brief
                    if (b != null) Text(b.first, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { c.startActivity(Intent(c, BriefActivity::class.java).putExtra("file", b.second.absolutePath)) })
                    else Text(stringResource(R.string.assistant_home_detail), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = { talking.tap { talk(c) } }, modifier = Modifier.size(52.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_mic), contentDescription = stringResource(R.string.assistant_tap_to_talk), tint = MaterialTheme.colorScheme.onPrimary) }
                }
            }
        }
    }
}

/** The mascot at rest on the widget's TV, in its voice's colour. */
@Composable private fun RestingMascot() {
    val c = LocalContext.current
    TalkScreen(LivePhase.Stopping, false, Modifier.fillMaxSize(), color = remember { characterColor(AssistantVoices.id(c)) })
}

/** The assist sheet: it listens as soon as it opens, the same as the assist gesture. */
private fun talk(c: Context) = c.startActivity(Intent(c, AssistantSheetActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

/** A share of a plan as a ring (Data's PlanHead's two arcs): red past the whole. */
@Composable internal fun HomeRing(share: Float, color: Color, modifier: Modifier) = Canvas(modifier) {
    val w = 4.dp.toPx(); val inset = w / 2; val arc = Size(size.width - w, size.height - w)
    drawArc(color.copy(alpha = 0.25f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(w))
    drawArc(if (share > 1f) Color(0xFFE53935) else color, -90f, 360f * share.coerceIn(0f, 1f), false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Round))
}

/** Browser: the bookmarks' front (pinned first), as many as fit across, each opening its page (8 Nov: "browser can have the favorites"). */
@Composable internal fun HomeBrowserGroup(open: () -> Unit) {
    val c = LocalContext.current
    val marks by produceState(emptyList<Pair<Bookmark, androidx.compose.ui.graphics.ImageBitmap?>>(), HomeCards.tick.intValue / 5) {
        value = withContext(Dispatchers.IO) { runCatching {
            BrowserRules.front(BrowserRules.bookmarks(c.getSharedPreferences("browser", Context.MODE_PRIVATE).getString("bookmarks", null))).take(24)
                .map { it to runCatching { BrowserIcons.cached(c, it.url) }.getOrNull() }
        }.getOrDefault(emptyList()) }
    }
    HomeWidget(Category.Browser.color, R.drawable.browser_art, stringResource(R.string.app_browser), open, panel = false) {
        if (marks.isEmpty()) { Text(stringResource(R.string.browser_home_detail), style = MaterialTheme.typography.bodySmall); return@HomeWidget }
        HomeTiles(marks, minWidth = 64.dp) { (m, icon), mod ->
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f), modifier = mod.clickable { openBrowserAt(c, m.url) }) {
                Column(Modifier.fillMaxSize().padding(2.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    val name = m.title.ifBlank { BrowserRules.host(m.url).removePrefix("www.") }
                    if (icon != null) androidx.compose.foundation.Image(icon, contentDescription = null, modifier = Modifier.size(22.dp).clip(MaterialTheme.shapes.small))
                    else Box(Modifier.size(22.dp).background(Category.Browser.color, CircleShape), contentAlignment = Alignment.Center) {
                        Text(name.take(1).uppercase(), style = MaterialTheme.typography.labelMedium, color = HomeCards.onColor(Category.Browser.color))
                    }
                    Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

/** A bar a day (Data's cycle on Home), the day's budget as a line; a day past it in red. Nothing when there is no room. */
@Composable internal fun HomeBars(perDay: List<Long>, budget: Long, color: Color, modifier: Modifier) = Canvas(modifier) {
    if (perDay.isEmpty() || size.height < 8.dp.toPx()) return@Canvas
    val top = maxOf(perDay.max(), budget).coerceAtLeast(1).toFloat()
    val w = size.width / perDay.size; val gap = minOf(2.dp.toPx(), w / 4)
    perDay.forEachIndexed { i, v ->
        val h = size.height * v / top
        drawRoundRect(if (budget > 0 && v > budget) Color(0xFFE53935) else color, Offset(i * w + gap / 2, size.height - h), Size(w - gap, h), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
    }
    if (budget > 0) { val y = size.height - size.height * budget / top; drawLine(color.copy(alpha = 0.6f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx()) }
}

/** Which size a widget is drawn at (HomeGrid sets it): a medium one may show a different, smaller thing (Timer: a clock). */
internal val LocalHomeMedium = androidx.compose.runtime.compositionLocalOf { false }

/**
 * Timer at medium size (8 Nov: "when the time is a huge icon it can be just a live clock, with an x if the alarm is
 * ringing"): the time and the date; an alarm or a timer ringing puts «✕ Τέλος» in the header.
 */
@Composable internal fun HomeClockGroup(open: () -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val now by produceState(java.time.ZonedDateTime.now(), HomeCards.tick.intValue) { value = java.time.ZonedDateTime.now() }
    var ringing by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // its id, and whether an alarm (else a timer)
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                ringing = withContext(Dispatchers.IO) { runCatching {
                    val dir = TimerControl.dir(); val t = Instant.now()
                    TimerAlarmFiles.ringing(dir, t, ZoneId.systemDefault()).firstOrNull()?.let { it.first.id to true } ?: TimerFiles.active(dir, t).firstOrNull { it.ringing(t) }?.let { it.id to false }
                }.getOrNull() }
                delay(1_000)
            }
        }
    }
    val ink = HomeCards.onColor(Category.Timer.color)
    HomeWidget(Category.Timer.color, R.drawable.timer_art, stringResource(R.string.app_timer), open, panel = false,
        action = ringing?.let { (id, alarm) -> { HomeWidgetAction("✕ " + stringResource(R.string.alarm_dismiss)) {
            scope.launch(Dispatchers.IO) { runCatching { if (alarm) TimerControl.alarmChange(c, id, TimerService.ALARM_DISMISS) else TimerControl.change(c, id, TimerService.STOP) }.onFailure { WriterLog.e("Home: not stopped", it) } }
        } } }) {
        Column(Modifier.fillMaxSize().clickable(onClick = open), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(TIME.format(now), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1)
            Text(DateTimeFormatter.ofPattern("EEEE d MMMM", androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).format(now), style = MaterialTheme.typography.bodySmall, color = ink, maxLines = 1)
        }
    }
}

/** The next birthdays from the calendar «Γενέθλια» (Contacts write them there): who, when, how old if the year is known. */
private data class Birthday(val name: String, val day: java.time.LocalDate, val turns: Int?)

/** Birthdays within [days] from today, nearest first. */
private fun birthdays(days: Long): List<Birthday> = runCatching {
    val cal = ContactsBirthdays.existing(CalendarFiles.calendars(calendarRoot())) ?: return@runCatching emptyList()
    val zone = ZoneId.systemDefault(); val today = java.time.LocalDate.now(zone)
    CalendarFiles.occurrences(listOf(cal), today.atStartOfDay(zone).toInstant(), today.plusDays(days).atStartOfDay(zone).toInstant()).map { o ->
        val year = ContactsBirthdays.master(o.file)?.prop("X-TETRA-BIRTH-YEAR")?.value?.trim()?.toIntOrNull()
        Birthday(o.event.title, o.start.toLocalDate(), year?.let { o.start.year - it })
    }.distinctBy { it.name to it.day }.sortedBy { it.day }
}.getOrDefault(emptyList())

/** The 🎂 view (8 Nov, the user: "🎂 / ο Γιώργος / έχει γενέθλια στις / 24/02/27"): the nearest one big, the next beside it when wide. */
@Composable private fun BirthdayView(list: List<Birthday>, open: () -> Unit) {
    val date = DateTimeFormatter.ofPattern("dd/MM/yy")
    val first = list.first()
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().clickable(onClick = open)) {
        val wide = maxWidth > 300.dp
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🎂", style = MaterialTheme.typography.headlineSmall)
                Text(first.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.home_birthday_on), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                Text(date.format(first.day) + (first.turns?.let { " · " + stringResource(R.string.calendar_turns, it) } ?: ""), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            if (wide && list.size > 1) Column(Modifier.weight(1f)) { Fitting(list.drop(1)) { b -> PanelLine("${date.format(b.day)}  ${b.name}") } }
        }
    }
}

/** Files, large (8 Nov: "a big one that actually shows the favourites as a list"): each favourite file or folder, opening it. */
@Composable internal fun HomeFavouriteFiles(open: () -> Unit, openItem: (String, Boolean) -> Unit) {
    val c = LocalContext.current
    val favs by produceState(emptyList<DriveItem>(), HomeCards.tick.intValue / 5) {
        value = withContext(Dispatchers.IO) { runCatching { DriveMetadata(c).favorites(driveRoot()) }.getOrDefault(emptyList()) }
    }
    HomeWidget(MaterialTheme.colorScheme.tertiaryContainer, R.drawable.files_art, stringResource(R.string.app_files), open) {
        if (favs.isEmpty()) { PanelLine(stringResource(R.string.home_favorites)); return@HomeWidget }
        Fitting(favs) { f ->
            Row(Modifier.fillMaxWidth().clickable { openItem(f.relativePath, f.isDirectory) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(if (f.isDirectory) R.drawable.ic_folder else R.drawable.ic_file), contentDescription = null, modifier = Modifier.size(14.dp))
                PanelLine(f.file.name)
            }
        }
    }
}

/**
 * The last missed calls of two days (Android's call log, a phone only), a name when the number is a contact's, when each came
 * («13:40», «Χθες 13:40», «Τετ 13:40»); a tap opens Missed, ✕ clears them until the next one.
 */
@Composable internal fun HomeMissedCalls() {
    val c = LocalContext.current
    val prefs = remember(c) { c.getSharedPreferences("home", Context.MODE_PRIVATE) }
    var clearedAt by remember { mutableLongStateOf(prefs.getLong(MISSED_CLEARED, 0L)) }
    val missed by produceState(emptyList<Pair<String, Long>>(), HomeCards.tick.intValue, clearedAt) {
        value = withContext(Dispatchers.IO) { runCatching {
            if (PhoneRelay.noCalls(c)) return@runCatching emptyList()
            val names = ContactsPreload.all.orEmpty().flatMap { ct -> ct.info.phones.map { ContactsWays.key(it.value) to ct.info.name } }.filter { it.first.length >= 5 }.toMap()
            HomeCards.missedToShow(PhoneLog.read(c, 200), clearedAt).map { (names[ContactsWays.key(it.number)] ?: it.number) to it.at }
        }.getOrDefault(emptyList()) }
    }
    if (missed.isEmpty()) return
    val zone = ZoneId.systemDefault(); val today = java.time.LocalDate.now(zone)
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val yesterday = stringResource(R.string.common_yesterday)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            missed.forEach { (who, at) ->
                Row(Modifier.fillMaxWidth().clickable { openPhone(c, "missed") }.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(painterResource(R.drawable.ic_call_missed), contentDescription = null, tint = Color(0xFFE53935), modifier = Modifier.size(14.dp))
                    PanelLine(who, Modifier.weight(1f), bold = true)
                    PanelLine(whenText(at, zone, today, locale).let { if (PhoneLog.day(at, zone) == today.minusDays(1)) "$yesterday $it" else it })
                }
            }
        }
        Box(Modifier.size(32.dp).clip(CircleShape).clickable { System.currentTimeMillis().let { prefs.edit().putLong(MISSED_CLEARED, it).apply(); clearedAt = it } }, contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_clear), modifier = Modifier.size(16.dp))
        }
    }
}

private const val MISSED_CLEARED = "missedClearedAt"

/** A list's leftover scroll and fling stay in it (8 Nov: at the notes' end "everything scrolls together"), Home does not move. */
private val KeepScroll = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = available
    override suspend fun onPostFling(consumed: Velocity, available: Velocity) = available
}

/** Notes, large: tall and narrow (8 Nov: "the pinned notes in the very large one, long and narrow, with a scroll"). */
@Composable internal fun HomePinnedNotes(open: () -> Unit, newNote: () -> Unit) {
    val c = LocalContext.current
    val notes by produceState(emptyList<Note>(), HomeCards.tick.intValue) {
        value = withContext(Dispatchers.IO) { runCatching {
            NotesStore(TetraSystem.dir(driveRoot(), "Notes", "Shared")).list().filter { it.pinned && it.trashedAt == null && it.archivedAt == null && !it.empty }.sortedByDescending { it.updatedAt }
        }.getOrDefault(emptyList()) }
    }
    HomeWidget(Category.Notes.color, R.drawable.notes_art, stringResource(R.string.app_notes), open, panel = false, action = { HomeWidgetAction("+", null, newNote) }) {
        if (notes.isEmpty()) { Text(stringResource(R.string.home_notes_none), style = MaterialTheme.typography.bodySmall); return@HomeWidget }
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().nestedScroll(KeepScroll), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(notes.size) { i ->
                val n = notes[i]
                val tint = n.color?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: MaterialTheme.colorScheme.surface
                Surface(shape = MaterialTheme.shapes.large, color = tint.copy(alpha = 0.85f), contentColor = HomeCards.onColor(tint), modifier = Modifier.fillMaxWidth().clickable { openApplet(c, "note:${n.id}") }) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        if (n.title.isNotBlank()) Text(n.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val body = if (n.checklist) n.items.sortedBy { it.order }.take(4).joinToString("\n") { (if (it.checked) "☑ " else "☐ ") + it.text } else n.content
                        if (body.isNotBlank()) Text(body, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Text editor, large (8 Nov: "unless we can show some recent txt files"): the files it has open, each opening the editor. */
@Composable internal fun HomeRecentTexts(open: () -> Unit, newFile: () -> Unit) {
    val c = LocalContext.current
    val files by produceState(emptyList<String>(), HomeCards.tick.intValue / 5) {
        value = withContext(Dispatchers.IO) { runCatching { TextEditorStore(c).tabs().filter { it.uri != null }.map { it.name } }.getOrDefault(emptyList()) }
    }
    HomeWidget(Category.Text.color, R.drawable.text_editor_art, stringResource(R.string.app_text_editor), open, action = { HomeWidgetAction("+", null, newFile) }) {
        if (files.isEmpty()) { PanelLine(stringResource(R.string.home_recent)); return@HomeWidget }
        Fitting(files) { name ->
            Row(Modifier.fillMaxWidth().clickable(onClick = open), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(R.drawable.ic_file_text), contentDescription = null, modifier = Modifier.size(14.dp))
                PanelLine(name)
            }
        }
    }
}

/**
 * Passwords on Home: the next 2FA code with its countdown and the ♥ logins as tiles (a tap copies the password, a long
 * press opens it). Locked, it shows only sites and names; a tap unlocks first (fingerprint or screen lock), and it locks
 * again after [HomeCards.UNLOCKED_MS] or when Home goes. Unlocked, the window takes no screenshots, as the applet's.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun HomePasswordsGroup(open: () -> Unit) {
    val c = LocalContext.current
    var unlocked by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) unlocked = false }
        lifecycle.addObserver(o); onDispose { lifecycle.removeObserver(o) }
    }
    androidx.compose.runtime.DisposableEffect(unlocked) {
        val w = if (unlocked) c.activity()?.window else null
        w?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { w?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LaunchedEffect(unlocked) { if (unlocked) { delay(HomeCards.UNLOCKED_MS); unlocked = false } }
    val data by produceState(emptyList<SavedLogin>() to emptyList<OtpEntry>(), HomeCards.tick.intValue / 5, unlocked) {
        value = withContext(Dispatchers.IO) { runCatching { HomeCards.favouriteLogins(PasswordStore.load(c).logins) to AuthSecrets.load(c) }.getOrDefault(emptyList<SavedLogin>() to emptyList()) }
    }
    val (favs, codes) = data
    val code = HomeCards.codeToShow(codes)
    val now by produceState(System.currentTimeMillis(), unlocked) { while (unlocked) { value = System.currentTimeMillis(); delay(1_000 - value % 1_000) } }
    fun unlock(then: () -> Unit) {
        val allowed = android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (c.getSystemService(android.hardware.biometrics.BiometricManager::class.java).canAuthenticate(allowed) != android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS) { unlocked = true; then(); return } // no screen lock: as the applet
        val a = c.activity() ?: return
        authenticateVault(a, { unlocked = true; said = null; then() }, { said = it }, c.getString(R.string.pw_unlock), c.getString(R.string.home_vault_unlock_detail))
    }
    fun copy(label: String, text: String, toast: Int) { SecretClip.copy(c, label, text); android.widget.Toast.makeText(c, toast, android.widget.Toast.LENGTH_SHORT).show() }
    HomeWidget(PASSWORDS_COLOR, R.drawable.passwords_art, stringResource(R.string.app_passwords), open, panel = false,
        action = { if (unlocked) HomeWidgetAction(stringResource(R.string.home_pw_lock), R.drawable.ic_lock) { unlocked = false } else HomeWidgetAction(stringResource(R.string.home_pw_unlock), R.drawable.ic_lock_open) { unlock {} } }) {
        said?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 1) }
        val panel = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
        if (code != null) Surface(shape = MaterialTheme.shapes.large, color = panel, modifier = Modifier.fillMaxWidth().height(HOME_TILE)
            .clickable { val go = { copy(code.issuer, Totp.code(code), R.string.pw_copied) }; if (unlocked) go() else unlock(go) }) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(code.issuer.ifBlank { code.account }, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                // locked: never the code
                Text(if (unlocked) Totp.grouped(Totp.code(code, now)) else "••• •••", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                if (unlocked) {
                    val left = Totp.left(code, now)
                    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                        HomeRing(left / code.period.toFloat(), if (left <= 5) Color(0xFFE53935) else PASSWORDS_COLOR, Modifier.fillMaxSize())
                        Text("$left", style = MaterialTheme.typography.labelSmall)
                    }
                } else Icon(painterResource(R.drawable.ic_lock), contentDescription = stringResource(R.string.home_pw_unlock), modifier = Modifier.size(20.dp))
            }
        }
        if (favs.isEmpty()) { if (code == null) Text(stringResource(R.string.home_pw_none), style = MaterialTheme.typography.bodySmall); return@HomeWidget }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().weight(1f)) {
            HomeTiles(favs, minWidth = 96.dp) { l, m ->
                Surface(shape = MaterialTheme.shapes.large, color = panel, modifier = m.clip(MaterialTheme.shapes.large).combinedClickable(
                    onLongClick = { openApplet(c, PasswordsActivity.WORD + l.host) },
                    onClick = { val go = { copy(l.host, l.password, R.string.pw_password_copied) }; if (unlocked) go() else unlock(go) })) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalArrangement = Arrangement.Center) {
                        Text(l.host.removePrefix("www."), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (l.user.isNotBlank()) Text(l.user, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
