package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import android.content.Context
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The Calendar's screens (Plan.md § Calendar › Screens). A big screen as Google Calendar on the web: a side panel
 * (a small month, the calendars with their coloured boxes) and a month, week or day grid. A phone as Outlook: the week
 * row (pulled down: the month) over the agenda of the days. Everything is read from Tetra/.System/Calendar/Shared's files.
 */

internal enum class CalView(@androidx.annotation.StringRes val label: Int) { Agenda(R.string.calendar_view_agenda), Day(R.string.calendar_view_day), ThreeDays(R.string.calendar_view_three_days), Week(R.string.calendar_view_week), Month(R.string.calendar_view_month), Year(R.string.calendar_view_year) }

/** The room the floating top (Files' pills) takes; the views start under it, the scrolling ones scroll beneath it. */
private val HEADER = 72.dp

internal fun calendarRoot() = CalendarFiles.root(TetraFolder.root(Environment.getExternalStorageDirectory()))

/** Which calendars this device hides (the checkboxes) and the last view: this screen's choices, not the files'. */
private object CalendarPrefs {
    private fun prefs(c: Context) = c.getSharedPreferences("calendar", Context.MODE_PRIVATE)
    fun hidden(c: Context): Set<String> = prefs(c).getStringSet("hidden", emptySet())!!.toSet()
    fun setHidden(c: Context, s: Set<String>) = prefs(c).edit().putStringSet("hidden", s).apply()
    fun view(c: Context, wide: Boolean): CalView = runCatching { CalView.valueOf(prefs(c).getString(if (wide) "viewWide" else "view", null)!!) }
        .getOrDefault(if (wide) CalView.Month else CalView.Agenda)
    fun last(c: Context): String? = prefs(c).getString("lastCalendar", null)
    fun setLast(c: Context, name: String) = prefs(c).edit().putString("lastCalendar", name).apply()
    fun stripWeeks(c: Context) = prefs(c).getInt("stripWeeks", 1)
    fun setStripWeeks(c: Context, n: Int) = prefs(c).edit().putInt("stripWeeks", n).apply()
    fun setView(c: Context, wide: Boolean, v: CalView) = prefs(c).edit().putString(if (wide) "viewWide" else "view", v.name).apply()
}

/** The editor open: on an occurrence (null: a new event), its draft and calendar. */
private class Editing(val occ: Occurrence?, val draft: CalEvent, val calendar: CalendarInfo?, val heading: String? = null)
private class Asking(val deleting: Boolean, val then: (Scope) -> Unit)

/** "All events" from one day: the change of that day's time moved onto the series' own start. */
private fun saveAll(f: CalFile, m: CalEvent?, o: Occurrence, e: CalEvent, moveTo: CalendarInfo?) {
    val base = m ?: return run { CalendarFiles.save(f.file, e) }
    val was = o.event.recurrenceId ?: o.start
    val shift = ChronoUnit.DAYS.between(was.toLocalDate(), e.start.toLocalDate())
    val start = base.start.toLocalDate().plusDays(shift).atTime(e.start.toLocalTime()).atZone(e.start.zone)
    CalendarFiles.save(f.file, base.copy(title = e.title, place = e.place, notes = e.notes, allDay = e.allDay, rrule = e.rrule, alarms = e.alarms,
        start = start, end = start.plus(java.time.Duration.between(e.start, e.end))))
    if (moveTo != null) CalendarFiles.move(f.file, moveTo)
}

/** The calendars, their events from a year before [anchor] to a year after, and by day without the [hidden] calendars. */
internal class CalData(val cals: List<CalendarInfo>, val occ: List<Occurrence>, val anchor: Int, val hidden: Set<String>, val days: Map<LocalDate, List<Occurrence>>, val made: Long = System.nanoTime())

/**
 * The Calendar's events read ahead when Tetra starts (25 Oct): it opened empty, composed, then read and composed again
 * with the events. One read at a time; the last one kept (a three-year window, so it does not grow with the years).
 */
internal object CalendarPreload {
    @Volatile var last: CalData? = null
        private set
    fun load(root: File, anchor: Int, hidden: Set<String>, zone: ZoneId = ZoneId.systemDefault()): CalData {
        val asked = System.nanoTime()
        synchronized(this) {
            // the read-ahead finished while this waited for it: that read is as new as this one would be
            last?.takeIf { it.made >= asked && it.anchor == anchor && it.hidden == hidden }?.let { return it }
            val cals = CalendarFiles.calendars(root)
            val from = LocalDate.of(anchor - 1, 1, 1).atStartOfDay(zone).toInstant()
            val to = LocalDate.of(anchor + 2, 1, 1).atStartOfDay(zone).toInstant()
            val occ = CalendarFiles.occurrences(cals, from, to)
            // nothing changed: the same data, so an open Calendar is not composed all over again
            val same = last?.takeIf { it.anchor == anchor && it.hidden == hidden && it.cals == cals && it.occ == occ }
            return (same ?: CalData(cals, occ, anchor, hidden, byDay(occ.filter { it.calendar.dir.name !in hidden }, zone))).also { last = it }
        }
    }
    fun warm(c: Context) { val app = c.applicationContext; Thread {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) // Home's first frames come first
        if (storageAllowed(app)) runCatching { load(calendarRoot(), LocalDate.now().year, CalendarPrefs.hidden(app)) }
    }.start() }
}

/** The days an occurrence covers on this device's clock: an all-day trip each of its days, a late party the next too. */
internal fun daysOf(o: Occurrence, zone: ZoneId): List<LocalDate> {
    val s = if (o.allDay) o.start.toLocalDate() else o.start.withZoneSameInstant(zone).toLocalDate()
    val e = if (o.allDay) o.end.toLocalDate().minusDays(1) else o.end.withZoneSameInstant(zone).minusNanos(1).toLocalDate()
    return generateSequence(s) { it.plusDays(1) }.takeWhile { !it.isAfter(e) }.take(366).toList().ifEmpty { listOf(s) }
}

internal fun byDay(occ: List<Occurrence>, zone: ZoneId): Map<LocalDate, List<Occurrence>> {
    val map = HashMap<LocalDate, MutableList<Occurrence>>()
    occ.forEach { o -> daysOf(o, zone).forEach { map.getOrPut(it) { mutableListOf() }.add(o) } }
    return map.mapValues { (_, l) -> l.sortedWith(compareBy<Occurrence>({ !it.allDay }, { it.start.toInstant() }, { it.event.title })) }
}

private fun firstDay(): DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
private fun weekOf(d: LocalDate): List<LocalDate> = d.with(TemporalAdjusters.previousOrSame(firstDay())).let { s -> (0L..6).map { s.plusDays(it) } }
private fun monthCells(ym: YearMonth): List<LocalDate> = ym.atDay(1).with(TemporalAdjusters.previousOrSame(firstDay())).let { s -> (0L..41).map { s.plusDays(it) } }

/**
 * Words from the string resources outside Compose too, and the language's [locale] for dates; a JVM test gives the
 * English ones from values/strings_notes.xml (it has no resources).
 */
internal interface UiWords {
    val locale: Locale
    fun get(@androidx.annotation.StringRes id: Int, vararg args: Any): String
    fun plural(@androidx.annotation.PluralsRes id: Int, n: Int, vararg args: Any): String
}

internal fun uiWords(c: Context): UiWords = object : UiWords {
    override val locale: Locale get() = c.resources.configuration.locales[0]
    override fun get(id: Int, vararg args: Any) = c.getString(id, *args)
    override fun plural(id: Int, n: Int, vararg args: Any) = c.resources.getQuantityString(id, n, *args)
}

@Composable internal fun rememberUiWords(): UiWords {
    val c = LocalContext.current
    val conf = LocalConfiguration.current
    return remember(c, conf) { uiWords(c) }
}

/** Tetra's language, for dates and day names. */
internal fun Context.uiLocale(): Locale = resources.configuration.locales[0]

/** "October 2026", "Οκτώβριος 2026" (the month on its own, which Greek says differently from "20 Οκτωβρίου"). */
internal fun monthTitle(d: LocalDate, locale: Locale, year: Boolean = true): String =
    (if (year) android.icu.text.DateFormat.getInstanceForSkeleton("MMMMy", locale) else android.icu.text.SimpleDateFormat("LLLL", locale))
        .format(java.util.Date.from(d.atStartOfDay(ZoneId.systemDefault()).toInstant()))

internal fun timeText(context: Context, t: ZonedDateTime): String =
    t.withZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", context.uiLocale()))

internal fun lengthText(w: UiWords, minutes: Long): String = when {
    minutes <= 0 -> ""
    minutes % 1440 == 0L -> w.get(R.string.calendar_len_days, minutes / 1440)
    minutes < 60 -> w.get(R.string.calendar_len_minutes, minutes)
    minutes % 60 == 0L -> w.get(R.string.calendar_len_hours, minutes / 60)
    else -> w.get(R.string.calendar_len_hours_minutes, minutes / 60, minutes % 60)
}

/** "third Tuesday": Greek's ordinal follows the day (τρίτη Τρίτη, τρίτο Σάββατο). */
private val ORDINAL = mapOf(1 to R.string.calendar_nth_first, 2 to R.string.calendar_nth_second, 3 to R.string.calendar_nth_third,
    4 to R.string.calendar_nth_fourth, 5 to R.string.calendar_nth_fifth, -1 to R.string.calendar_nth_last, -2 to R.string.calendar_nth_second_last)
private val ORDINAL_SATURDAY = mapOf(1 to R.string.calendar_nth_first_sat, 2 to R.string.calendar_nth_second_sat, 3 to R.string.calendar_nth_third_sat,
    4 to R.string.calendar_nth_fourth_sat, 5 to R.string.calendar_nth_fifth_sat, -1 to R.string.calendar_nth_last_sat, -2 to R.string.calendar_nth_second_last_sat)
internal fun nthDay(w: UiWords, k: Int?, d: DayOfWeek): String {
    val name = d.getDisplayName(TextStyle.FULL, w.locale)
    return (if (d == DayOfWeek.SATURDAY) ORDINAL_SATURDAY else ORDINAL)[k]?.let { w.get(it, name) } ?: name
}
private fun dayOf(code: String) = DayOfWeek.entries.firstOrNull { it.name.take(2) == code.takeLast(2) }
private fun dayName(w: UiWords, code: String) = dayOf(code)?.getDisplayName(TextStyle.FULL, w.locale) ?: code

/** An RRULE in words, as Google writes it: "Weekly on Tuesday", "Monthly on the third Tuesday", ", 6 times". */
internal fun repeatText(w: UiWords, rule: String?, start: ZonedDateTime): String? {
    if (rule.isNullOrBlank()) return null
    val p = rule.split(';').associate { it.substringBefore('=').uppercase() to it.substringAfter('=') }
    val n = p["INTERVAL"]?.toIntOrNull() ?: 1
    val unit = when (p["FREQ"]) { "DAILY" -> "day"; "WEEKLY" -> "week"; "MONTHLY" -> "month"; "YEARLY" -> "year"; else -> return w.get(R.string.calendar_repeats) }
    val byDay = p["BYDAY"]?.split(',').orEmpty()
    val weekdays = listOf("MO", "TU", "WE", "TH", "FR")
    val everyWeekday = unit == "week" && n == 1 && byDay.sorted() == weekdays.sorted()
    val head = when {
        everyWeekday -> w.get(R.string.calendar_repeat_weekdays)
        n == 1 -> w.get(mapOf("day" to R.string.calendar_repeat_daily, "week" to R.string.calendar_repeat_weekly, "month" to R.string.calendar_repeat_monthly, "year" to R.string.calendar_repeat_yearly)[unit]!!)
        else -> w.get(mapOf("day" to R.string.calendar_repeat_every_days, "week" to R.string.calendar_repeat_every_weeks, "month" to R.string.calendar_repeat_every_months, "year" to R.string.calendar_repeat_every_years)[unit]!!, n)
    }
    val on = when {
        everyWeekday -> head
        unit == "week" && byDay.isNotEmpty() -> w.get(R.string.calendar_repeat_on, head, byDay.joinToString(", ") { dayName(w, it) })
        unit == "week" -> w.get(R.string.calendar_repeat_on, head, start.dayOfWeek.getDisplayName(TextStyle.FULL, w.locale))
        unit == "month" && byDay.isNotEmpty() -> byDay.first().let { d ->
            val k = d.dropLast(2).toIntOrNull() ?: p["BYSETPOS"]?.toIntOrNull()
            w.get(R.string.calendar_repeat_on_the, head, dayOf(d)?.let { nthDay(w, k, it) } ?: d)
        }
        unit == "month" -> w.get(R.string.calendar_repeat_on_day, head, p["BYMONTHDAY"] ?: start.dayOfMonth.toString())
        unit == "year" -> w.get(R.string.calendar_repeat_on, head, start.format(DateTimeFormatter.ofPattern("d MMMM", w.locale)))
        else -> head
    }
    return p["COUNT"]?.toIntOrNull()?.let { w.plural(R.plurals.calendar_repeat_times, it, on, it) } ?: p["UNTIL"]?.let { u ->
        runCatching { w.get(R.string.calendar_repeat_until, on, LocalDate.parse(u.take(8), DateTimeFormatter.BASIC_ISO_DATE).format(DateTimeFormatter.ofPattern("d MMM yyyy", w.locale))) }.getOrNull()
    } ?: on
}

private fun spanText(w: UiWords, seconds: Long): String {
    val s = Math.abs(seconds)
    fun n(v: Long) = v.toInt()
    val parts = listOfNotNull((s / 604800).takeIf { it > 0 && s % 604800 == 0L }?.let { w.plural(R.plurals.calendar_weeks, n(it), it) })
        .ifEmpty {
            listOfNotNull((s / 86400).takeIf { it > 0 }?.let { w.plural(R.plurals.calendar_days, n(it), it) },
                (s % 86400 / 3600).takeIf { it > 0 }?.let { w.plural(R.plurals.calendar_hours, n(it), it) },
                (s % 3600 / 60).takeIf { it > 0 }?.let { w.plural(R.plurals.calendar_minutes, n(it), it) })
        }
    return parts.joinToString(" ")
}

/**
 * A reminder in words: "30 minutes before", "The day before at 09:00", "3 days before at 09:00 · every day, 3 times".
 * Anything a day or more before says the clock time it rings at, worked out from [start].
 */
internal fun alarmText(w: UiWords, a: CalAlarm, allDay: Boolean, start: ZonedDateTime): String {
    val t = a.days * 86400 + a.seconds
    val base = when {
        a.at != null -> w.get(R.string.calendar_alarm_at, a.at.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM HH:mm", w.locale)))
        a.fromEnd -> if (t == 0L) w.get(R.string.calendar_alarm_at_end) else w.get(if (t < 0) R.string.calendar_alarm_before_end else R.string.calendar_alarm_after_end, spanText(w, t))
        // all day starts at midnight, so "on the day at 09:00" is PT9H, after the start: still said by its day
        allDay || t <= -86400 -> {
            val fires = a.times(start, start, allDay)[0]
            val before = ChronoUnit.DAYS.between(fires.toLocalDate(), start.toLocalDate())
            val clock = fires.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
            when (before) { 0L -> w.get(R.string.calendar_alarm_on_day, clock); 1L -> w.get(R.string.calendar_alarm_day_before, clock)
                7L -> w.get(R.string.calendar_alarm_week_before, clock); -1L -> w.get(R.string.calendar_alarm_day_after, clock)
                else -> if (before > 0) w.get(R.string.calendar_alarm_days_before, before, clock) else w.get(R.string.calendar_alarm_days_after, -before, clock) }
        }
        t == 0L -> w.get(R.string.calendar_alarm_at_time)
        else -> w.get(if (t < 0) R.string.calendar_alarm_before else R.string.calendar_alarm_after, spanText(w, t))
    }
    return if (a.repeat > 0) w.get(R.string.calendar_alarm_repeat, base, spanText(w, a.every).removePrefix("1 "), a.repeat + 1) else base
}

/** The Calendar's own pink (its icon's, Home's group): today and the chosen day, as Google's blue. */
internal val CalendarPink = Color(0xFFF41879)

private fun onColor(c: Color) = if (c.luminance() > 0.55f) Color.Black else Color.White

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalendarScreen(back: () -> Unit, hasAccess: Boolean, grant: () -> Unit, startKind: NewKind? = null, openContact: (String) -> Unit = {}) {
    val context = LocalContext.current
    val w = rememberUiWords()
    val wide = LocalConfiguration.current.screenWidthDp >= 700
    val zone = remember { ZoneId.systemDefault() }
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); today = LocalDate.now() } }
    var selected by remember { mutableStateOf(LocalDate.now()) }
    var view by remember(wide) { mutableStateOf(CalendarPrefs.view(context, wide)) }
    var version by remember { mutableIntStateOf(0) }
    var hidden by remember { mutableStateOf(CalendarPrefs.hidden(context)) }
    var drawer by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<Occurrence?>(null) }
    var editing by remember { mutableStateOf<Editing?>(null) }
    var asking by remember { mutableStateOf<Asking?>(null) } // a repeating event's This / Following / All
    var calDialog by remember { mutableStateOf<CalendarInfo?>(null) }
    var newCal by remember { mutableStateOf(false) }
    var trashOpen by remember { mutableStateOf(false) }
    var deleteCal by remember { mutableStateOf<CalendarInfo?>(null) }
    var birthday by remember { mutableStateOf(false) }
    var schedule by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) { if (message != null) { delay(3000); message = null } }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val root = remember { calendarRoot() }

    if (!hasAccess) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_back)) }
            Text(stringResource(R.string.calendar_access_needed))
            TextButton(onClick = grant) { Text(stringResource(R.string.calendar_allow_access), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        return
    }
    // A file changed by sync, by another app or by Claude on the computer shows as soon as it lands.
    DisposableEffect(root) {
        root.mkdirs()
        val watcher = DriveWatcher(root) { version++; CalendarAlarms.planSoon(context) }
        onDispose { watcher.stop() }
    }
    val anchor = selected.year
    // read ahead (CalendarPreload): the first frame has the events; read again behind it
    val data by produceState(CalendarPreload.last?.takeIf { it.anchor == anchor }, version, anchor) {
        value = withContext(Dispatchers.IO) { CalendarPreload.load(root, anchor, hidden, zone) }
    }
    OpenTiming("Calendar", data != null)
    val cals = data?.cals.orEmpty()
    // by day off the screen's thread; made again here only when a calendar is hidden or shown
    val days = remember(data, hidden) { data?.takeIf { it.hidden == hidden }?.days ?: byDay(data?.occ.orEmpty().filter { it.calendar.dir.name !in hidden }, zone) }
    fun toggle(c: CalendarInfo) {
        hidden = if (c.dir.name in hidden) hidden - c.dir.name else hidden + c.dir.name
        CalendarPrefs.setHidden(context, hidden)
    }
    fun setView(v: CalView) { view = v; CalendarPrefs.setView(context, wide, v) }
    /** Writes on the IO thread and says what [work] answers; the folder's watcher shows the result, the reminders follow. */
    fun writeSaying(work: () -> String) = scope.launch {
        val done = withContext(Dispatchers.IO) { runCatching(work).also { runCatching { CalendarAlarms.plan(context) } } }
        if (done.isSuccess) ScopedSync.soon(context, "calendar", 0, orWhole = true) // to the other devices at once (the user, 18 Oct: "no delay")
        message = done.getOrElse { context.getString(R.string.calendar_not_saved, it.shownMessage(context) ?: it.javaClass.simpleName) }
        done.exceptionOrNull()?.let { WriterLog.e("Calendar: a change failed", it) }
        version++
    }
    fun write(said: String, work: () -> Unit) = writeSaying { work(); said }
    // Refresh (the user, 18 Oct: "add a refresh button at the top right"): the Calendar's part with the computer now
    var syncing by remember { mutableStateOf(false) }
    fun refresh() = scope.launch {
        if (syncing) return@launch
        syncing = true
        val r = withContext(Dispatchers.IO) { runCatching { SyncClient(context.applicationContext, SyncStore(context.applicationContext)).scopeOnly("calendar") } }
        syncing = false
        message = r.fold({ n -> if (n == 0) context.getString(R.string.calendar_up_to_date) else context.resources.getQuantityString(R.plurals.calendar_changes, n, n) }) { e ->
            if (e.message?.contains("Unknown request") == true) { // the computer's Tetra is older: the whole sync, as Sync now
                runCatching { androidx.core.content.ContextCompat.startForegroundService(context, android.content.Intent(context, SyncService::class.java).setAction(SyncService.ACTION_START)) }
                context.getString(R.string.calendar_syncing_everything)
            } else if (SyncStore(context).pairing() == null) context.getString(R.string.calendar_not_paired) else context.getString(R.string.calendar_sync_failed, e.message.toString())
        }
        version++
    }
    val refreshButton: @Composable () -> Unit = {
        val turn by androidx.compose.animation.core.rememberInfiniteTransition(label = "sync").animateFloat(0f, if (syncing) 360f else 0f,
            androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing)), label = "turn")
        IconButton(onClick = { refresh() }) { Icon(painterResource(R.drawable.ic_sync), contentDescription = stringResource(if (syncing) R.string.calendar_syncing else R.string.calendar_sync_now), modifier = Modifier.graphicsLayer { rotationZ = if (syncing) -turn else 0f }) }
    }
    // Import: an .ics or Takeout's zip, from the picker or opened from another app
    var incoming by remember { mutableStateOf<List<CalendarTransfer.Incoming>?>(null) }
    fun readImport(uri: android.net.Uri) = scope.launch {
        val got = withContext(Dispatchers.IO) { runCatching {
            val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?: uri.lastPathSegment.orEmpty()
            if (bytes.size > 1 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) CalendarTransfer.readZip(bytes)
            else listOf(CalendarTransfer.read(bytes.toString(Charsets.UTF_8), name.substringAfterLast('/').removeSuffix(".ics").ifEmpty { "Imported" }))
        } }
        got.onSuccess { l -> l.filter { it.events.isNotEmpty() }.let { if (it.isEmpty()) message = context.getString(R.string.calendar_no_events_in_file) else incoming = it } }
            .onFailure { message = context.getString(R.string.calendar_read_failed, it.message ?: it.javaClass.simpleName); WriterLog.e("Calendar: import failed", it) }
    }
    val pickImport = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { it?.let(::readImport) }
    LaunchedEffect(Unit) { CalendarInbox.pending.collect { u -> if (u != null) { CalendarInbox.pending.value = null; readImport(u) } } }
    // Export: one calendar as an .ics, or all as a zip, saved where the person chooses
    var exporting by remember { mutableStateOf<CalendarInfo?>(null) }
    fun saveTo(uri: android.net.Uri?, bytes: () -> ByteArray) { if (uri != null) writeSaying { context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes()) }; context.getString(R.string.calendar_exported) } }
    val exportOne = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        exporting?.let { c -> saveTo(uri) { CalendarTransfer.export(c).toByteArray() } }; exporting = null
    }
    val exportAll = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        saveTo(uri) { CalendarTransfer.exportAll(CalendarFiles.calendars(root)) }
    }
    fun create(day: LocalDate, minute: Int? = null) {
        val now = java.time.LocalTime.now()
        val at = minute?.let { java.time.LocalTime.of(it / 60, it % 60) }
            ?: if (day == today) now.plusMinutes(60L - now.minute).withSecond(0).withNano(0).takeIf { it.isAfter(now) } ?: java.time.LocalTime.of(23, 0) else java.time.LocalTime.of(9, 0)
        val start = day.atTime(at).atZone(zone)
        val cal = cals.firstOrNull { it.dir.name == CalendarPrefs.last(context) && it.dir.name !in hidden } ?: cals.firstOrNull { it.dir.name !in hidden } ?: cals.firstOrNull()
        editing = Editing(null, CalEvent(CalendarFiles.newUid(), "", "", "", false, start, start.plusHours(1), alarms = defaultAlarms(false, start)), cal)
    }
    // + and Home's tiles: New, Schedule, Birthday, Reminder (the user, 18 Oct)
    fun start(k: NewKind) {
        when (k) {
            NewKind.Event -> create(selected)
            NewKind.Reminder -> { create(selected); editing = editing?.let { Editing(null, it.draft.copy(end = it.draft.start.plusMinutes(15), alarms = listOf(CalAlarm(0, 0))), it.calendar, context.getString(R.string.calendar_new_reminder)) } }
            NewKind.Birthday -> birthday = true
            NewKind.Schedule -> schedule = true
        }
    }
    LaunchedEffect(startKind, data != null) { if (startKind != null && data != null && editing == null) start(startKind) }
    fun saveEvent(ed: Editing, e: CalEvent, target: CalendarInfo?, scopeChoice: Scope?) = write(context.getString(if (ed.occ == null) R.string.calendar_event_created else R.string.calendar_saved)) {
        val to = target ?: CalendarFiles.calendars(root).firstOrNull() ?: CalendarFiles.newCalendar(root, "Personal", CalendarFiles.PALETTE[0])
        CalendarPrefs.setLast(context, to.dir.name)
        val o = ed.occ
        if (o == null) { CalendarFiles.save(CalendarFiles.newFile(to.dir, e.title, e.uid), e); return@write }
        val f = o.file
        val m = f.master
        val day = o.event.recurrenceId ?: o.start
        val moveTo = to.takeIf { it.dir != o.calendar.dir }
        when (if (m?.rrule == null && o.event.recurrenceId == null) null else scopeChoice) {
            null -> CalendarFiles.save(f.file, e).also { if (moveTo != null) CalendarFiles.move(f.file, moveTo) }
            Scope.One -> CalendarFiles.saveDay(f, day, e)
            Scope.Following -> if (m == null || day.toInstant() == m.start.toInstant()) saveAll(f, m, o, e, moveTo) else {
                val uid = CalendarFiles.newUid()
                CalendarFiles.split(f, day, CalendarFiles.newFile(to.dir, e.title, uid), e.copy(uid = uid, recurrenceId = null, exdates = emptyList(),
                    rrule = e.rrule?.split(';')?.filterNot { it.startsWith("COUNT=", true) }?.joinToString(";")))
            }
            Scope.All -> saveAll(f, m, o, e, moveTo)
        }
    }
    fun deleteEvent(o: Occurrence, scopeChoice: Scope?) = write(context.getString(R.string.calendar_deleted)) {
        val f = o.file
        val m = f.master
        val day = o.event.recurrenceId ?: o.start
        when (if (m?.rrule == null && o.event.recurrenceId == null) null else scopeChoice) {
            null, Scope.All -> CalendarFiles.trash(root, f.file)
            Scope.One -> CalendarFiles.skipDay(f, day)
            Scope.Following -> if (m == null || day.toInstant() <= m.start.toInstant()) CalendarFiles.trash(root, f.file) else CalendarFiles.endBefore(f, day)
        }
    }
    // the user, 18 Oct: on a wide screen the island holds only Day · Week · Month · Year
    val views = if (wide) listOf(CalView.Day, CalView.Week, CalView.Month, CalView.Year) else listOf(CalView.Agenda, CalView.Day, CalView.ThreeDays, CalView.Month)
    LaunchedEffect(wide) { if (view !in views) setView(views.first()) }
    BackHandler { when { shown != null -> shown = null; drawer -> drawer = false; else -> back() } }
    editing?.let { ed ->
        val repeating = ed.occ != null && (ed.occ.file.master?.rrule != null || ed.occ.event.recurrenceId != null)
        EventEditor(ed.draft, ed.calendar, cals, isNew = ed.occ == null, wide = wide, cancel = { editing = null }, heading = ed.heading) { e, target ->
            if (repeating) asking = Asking(false) { sc -> saveEvent(ed, e, target, sc); editing = null }
            else { saveEvent(ed, e, target, null); editing = null }
        }
        asking?.let { a -> ScopeDialog(a.deleting, { asking = null }) { sc -> asking = null; a.then(sc) } }
        if (!wide) return
    }
    val open: (Occurrence) -> Unit = { shown = it }

    val body: @Composable (Modifier) -> Unit = { m ->
        Box(m) {
            when (view) {
                CalView.Month -> Box(Modifier.padding(top = HEADER)) { MonthGrid(YearMonth.from(selected), days, today, selected, compact = !wide, pick = { selected = it; if (!wide) setView(CalView.Agenda) }, open = open) }
                CalView.Week -> Box(Modifier.padding(top = HEADER)) { TimeGrid(weekOf(selected), days, today, open, ::create) }
                CalView.ThreeDays -> Box(Modifier.padding(top = HEADER)) { TimeGrid((0L..2).map { selected.plusDays(it) }, days, today, open, ::create) }
                CalView.Day -> Box(Modifier.padding(top = HEADER)) { TimeGrid(listOf(selected), days, today, open, ::create) }
                CalView.Agenda -> Agenda(anchor, days, today, selected, { selected = it }, open, top = HEADER)
                CalView.Year -> YearGrid(selected, today, days.keys) { selected = it; setView(CalView.Day) }
            }
        }
    }

    // Tetra's own island at the bottom (Files', Notes'): the views and New; pulled up, the drawer with the rest (the user,
    // 18 Oct: "an island and bottom drawer that opens for the rest like what calendars to see", on the big screen too).
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                if (wide) Row(Modifier.weight(1f)) {
                    // the user, 18 Oct: on a wide screen the small month and the calendars stay in sight, beside the grid
                    Column(Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()).navigationBarsPadding()
                        .padding(start = 16.dp, end = 8.dp, top = HEADER, bottom = 84.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        MiniMonth(selected, today, days.keys) { selected = it }
                        CalendarList(cals, hidden, ::toggle, { calDialog = it }, { newCal = true },
                            { pickImport.launch(arrayOf("text/calendar", "application/zip", "application/octet-stream", "*/*")) }, { exportAll.launch("Tetra calendars.zip") }, settings = false)
                    }
                    // the island floats over the last row; the views stop above it
                    body(Modifier.weight(1f).fillMaxHeight().navigationBarsPadding().padding(end = 8.dp, bottom = 92.dp)) // clear of the islands
                } else {
                    // the user, 18 Oct: the events on top, the small calendar at the bottom within the thumb's reach
                    body(Modifier.weight(1f).fillMaxWidth())
                    if (view != CalView.Month) BottomStrip(selected, today, days) { selected = it }
                    Spacer(Modifier.navigationBarsPadding().height(92.dp)) // the island and its gap below
                }
            }
            // Files' bottom: the island at the left, the one round action at the right (here New event)
            // the user, 18 Oct: the pills float over the content, as in Files — no solid band behind them
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
                // Files' own top: Back and the title in islands, the dates' controls beside them
                FilesPageHeader(
                    title = if (wide) title(selected, view, w.locale) else monthTitle(selected, w.locale, year = selected.year != today.year),
                    icon = R.drawable.ic_calendar_today, back = back, island = true,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    // a wide screen has Today and the arrows at the bottom, by the + (the user, 18 Oct); the top keeps the title
                    // the Trash beside sync (the user, 18 Oct: "not something people press regularly"); a phone's is in the drawer
                    trailing = if (wide) ({ Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) { Row {
                        IconButton(onClick = { trashOpen = true }) { Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.calendar_trash)) }
                        refreshButton()
                    } } }) else ({
                        Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                refreshButton()
                                TextButton(onClick = { selected = today }) { Text(stringResource(R.string.common_today), color = islandContentColor(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                if (wide || view != CalView.Agenda) {
                                    IconButton(onClick = { selected = step(selected, view, -1) }) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_previous)) }
                                    IconButton(onClick = { selected = step(selected, view, 1) }) { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = stringResource(R.string.common_next)) }
                                }
                            }
                        }
                    }),
                )
                ReminderNotes()
            }
            // on a wide screen it starts at the grid, the side column left in sight
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(start = if (wide) 300.dp else 12.dp, end = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IslandBottomBar(expand = { drawer = true }, visible = !drawer) {
                    views.forEach { v -> IslandNavigationItem(stringResource(v.label), view == v, viewIcon(v)) { setView(v) } }
                }
                IslandVisibility(!drawer) { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // the user, 18 Oct: Today and ‹ › in their own island beside the +, within the thumb's reach
                    if (wide) Surface(color = islandColor(), contentColor = islandContentColor(), shape = MaterialTheme.shapes.extraLarge) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(Modifier.height(18.dp)) // the main island's handle row, so both stand the same height
                            Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { selected = step(selected, view, -1) }) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_previous)) }
                                TextButton(onClick = { selected = today }) { Text(stringResource(R.string.common_today), color = islandContentColor(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                IconButton(onClick = { selected = step(selected, view, 1) }) { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = stringResource(R.string.common_next)) }
                            }
                        }
                    }
                    Box {
                        var kinds by remember { mutableStateOf(false) }
                        Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                            IconButton(onClick = { kinds = true }) { Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.common_new)) }
                        }
                        DropdownMenu(expanded = kinds, onDismissRequest = { kinds = false }) {
                            NewKind.entries.forEach { k -> DropdownMenuItem(text = { Text(stringResource(k.text)) }, leadingIcon = { Icon(painterResource(k.icon), contentDescription = null) }, onClick = { kinds = false; start(k) }) }
                        }
                    }
                } }
            }
            message?.let { m ->
                Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape,
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp)) { Text(m, Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) }
            }
        }
    }
    if (drawer) ModalBottomSheet(onDismissRequest = { drawer = false }, containerColor = SheetColor, contentColor = islandContentColor()) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (wide) { RemindersSwitch(); ShareWithClaude() }
            else Surface(color = Color.White.copy(alpha = 0.08f), shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    CalendarList(cals, hidden, ::toggle, { drawer = false; calDialog = it }, { drawer = false; newCal = true },
                        { drawer = false; pickImport.launch(arrayOf("text/calendar", "application/zip", "application/octet-stream", "*/*")) }, { drawer = false; exportAll.launch("Tetra calendars.zip") },
                        trash = { drawer = false; trashOpen = true })
                }
            }
        }
    }
    shown?.let { o ->
        val repeating = o.file.master?.rrule != null || o.event.recurrenceId != null
        EventCard(o, close = { shown = null },
            edit = {
                shown = null
                val m = o.file.master
                // a day of a repeat edits with the repeat shown; the editor's date is that day's
                val draft = if (m != null && o.event.recurrenceId == null) m.copy(start = o.start, end = o.end) else o.event.copy(rrule = m?.rrule)
                // a contact's birthday or date is changed on the contact (one place to change it)
                val contact = ContactsBirthdays.contactOf(o.file)
                if (contact != null) openContact(contact) else editing = Editing(o, draft, o.calendar)
            },
            delete = { shown = null; if (repeating) asking = Asking(true) { sc -> deleteEvent(o, sc) } else deleteEvent(o, null) },
            keep = { shown = null; write(context.getString(R.string.calendar_kept_version)) { CalendarFiles.copies(o.calendar, o.file).forEach { CalendarFiles.trash(root, it) } } })
    }
    if (editing == null) asking?.let { a -> ScopeDialog(a.deleting, { asking = null }) { sc -> asking = null; a.then(sc) } }
    if (trashOpen) CalendarTrash(root, cals, version, { trashOpen = false }) { f, to -> write(context.getString(R.string.calendar_restored_to, to.name)) { CalendarFiles.move(f.file, to) } }
    if (newCal) CalendarDialog(null, cals.map { it.name }.toSet(), { newCal = false }) { name, color ->
        newCal = false
        write(context.getString(R.string.calendar_created, name)) { CalendarFiles.newCalendar(root, name, color) }
    }
    calDialog?.let { c ->
        CalendarDialog(c, cals.map { it.name }.toSet(), { calDialog = null }, delete = { calDialog = null; deleteCal = c },
            export = { calDialog = null; exporting = c; exportOne.launch("${c.name}.ics") }) { name, color ->
            calDialog = null
            write(context.getString(R.string.calendar_saved)) {
                CalendarFiles.setColor(c.dir, color)
                if (name != c.name) {
                    val to = java.io.File(root, name)
                    require(!to.exists() && c.dir.renameTo(to)) { context.getString(R.string.calendar_rename_failed, c.name) }
                    if (c.dir.name in hidden) { hidden = hidden - c.dir.name + name; CalendarPrefs.setHidden(context, hidden) }
                }
            }
        }
    }
    if (birthday) BirthdayDialog({ birthday = false }) { name, date, yearKnown, asContact ->
        birthday = false
        write(context.getString(R.string.calendar_birthday_saved, name)) {
            // a contact too (23 Oct): Contacts writes its event, into the same calendar, and keeps it in step
            if (asContact) {
                val c = ContactsFiles.save(contactsRoot(), null, ContactInfo(formatted = name, given = name.substringBefore(' '), family = name.substringAfter(' ', ""),
                    birthday = VDate(date.year.takeIf { yearKnown }, date.monthValue, date.dayOfMonth)))
                ContactsBirthdays.sync(root, c.info, ContactsPrefs.birthdays(context), emptyList())
                ScopedSync.soon(context, "contacts", 0, orWhole = true)
                return@write
            }
            // Contacts' setting (Γενέθλια), not a second birthdays calendar
            val cal = ContactsBirthdays.calendar(root, ContactsPrefs.birthdays(context))
            val start = (if (yearKnown) date else date.withYear(today.year)).atStartOfDay(zone)
            val uid = CalendarFiles.newUid()
            val f = CalendarFiles.save(CalendarFiles.newFile(cal.dir, "$name birthday", uid),
                CalEvent(uid, context.getString(R.string.calendar_birthday_title, name), "", "", true, start, start.plusDays(1), rrule = "FREQ=YEARLY", alarms = listOf(daysBeforeAt(start, 0, java.time.LocalTime.of(9, 0), false))))
            if (yearKnown) CalendarFiles.setProp(f.file, "X-TETRA-BIRTH-YEAR", date.year.toString())
        }
    }
    if (schedule) {
        val holidays = cals.firstOrNull { it.name.equals("Holidays", true) }
        ScheduleDialog(cals, cals.firstOrNull { it.dir.name == CalendarPrefs.last(context) } ?: cals.firstOrNull(), holidays != null, { schedule = false }) { title, place, weekdays, at, until, from, to, skip, cal ->
            schedule = false
            write(context.getString(R.string.calendar_schedule_saved)) {
                val into = cal ?: CalendarFiles.calendars(root).firstOrNull() ?: CalendarFiles.newCalendar(root, "Personal", CalendarFiles.PALETTE[0])
                // the Holidays calendar's days (all day) between the two dates, as exceptions
                val skipDays = if (skip && holidays != null) CalendarFiles.occurrences(listOf(holidays), from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant())
                    .filter { it.allDay }.flatMap { daysOf(it, zone) } else emptyList()
                val e = scheduleEvent(CalendarFiles.newUid(), title, place, weekdays, at, until, from, to, skipDays, zone)
                CalendarFiles.save(CalendarFiles.newFile(into.dir, title, e.uid), e)
            }
        }
    }
    incoming?.let { list ->
        ImportDialog(list, cals, { incoming = null }) { choices ->
            incoming = null
            writeSaying {
                var added = 0; var updated = 0
                choices.forEach { (inc, chosen) ->
                    val all = CalendarFiles.calendars(root)
                    val to = chosen ?: all.firstOrNull { it.name.equals(inc.name, true) }
                        ?: CalendarFiles.newCalendar(root, inc.name, inc.color ?: CalendarFiles.PALETTE[all.size % CalendarFiles.PALETTE.size])
                    CalendarTransfer.write(to, inc).let { (a, u) -> added += a; updated += u }
                }
                importedText(w, added, updated)
            }
        }
    }
    deleteCal?.let { c ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { deleteCal = null }, title = { Text(stringResource(R.string.calendar_delete_title, c.name)) },
            text = { Text(stringResource(R.string.calendar_delete_note)) },
            confirmButton = { TextButton(onClick = { deleteCal = null; write(context.getString(R.string.calendar_moved_to_trash, c.name)) { TetraSystem.tetraOf(root).let { DriveRules.moveToTrash(it, DriveRules.relative(it, c.dir)) } } }) { Text(stringResource(R.string.common_delete), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            dismissButton = { TextButton(onClick = { deleteCal = null }) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) } })
    }
}

private fun step(d: LocalDate, v: CalView, n: Long): LocalDate = when (v) {
    CalView.Year -> d.plusYears(n)
    CalView.Day -> d.plusDays(n)
    CalView.ThreeDays -> d.plusDays(3 * n)
    CalView.Week, CalView.Agenda -> d.plusWeeks(n)
    CalView.Month -> d.plusMonths(n)
}

private fun title(d: LocalDate, v: CalView, locale: Locale): String = when (v) {
    CalView.Day -> d.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", locale))
    CalView.Week, CalView.ThreeDays -> {
        val span = if (v == CalView.Week) weekOf(d) else (0L..2).map { d.plusDays(it) }
        val a = span.first(); val b = span.last()
        if (a.month == b.month) monthTitle(a, locale)
        else a.format(DateTimeFormatter.ofPattern("MMM", locale)) + " – " + b.format(DateTimeFormatter.ofPattern("MMM yyyy", locale))
    }
    CalView.Year -> d.year.toString()
    else -> monthTitle(d, locale)
}

private fun viewIcon(v: CalView) = when (v) {
    CalView.Agenda -> R.drawable.ic_view_list
    CalView.Day -> R.drawable.ic_view_day
    CalView.ThreeDays, CalView.Week -> R.drawable.ic_view_column
    CalView.Month -> R.drawable.ic_view_grid
    CalView.Year -> R.drawable.ic_calendar_today
}

@Composable private fun CalendarList(cals: List<CalendarInfo>, hidden: Set<String>, toggle: (CalendarInfo) -> Unit, edit: (CalendarInfo) -> Unit, add: () -> Unit, import: () -> Unit, exportAll: () -> Unit, settings: Boolean = true, trash: (() -> Unit)? = null) {
    Column {
        Text(stringResource(R.string.calendar_my_calendars), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 6.dp))
        if (cals.isEmpty()) Text(stringResource(R.string.calendar_none_yet), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        cals.forEach { c ->
            val color = Color(c.color)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { toggle(c) }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = c.dir.name !in hidden, onCheckedChange = { toggle(c) },
                    colors = CheckboxDefaults.colors(checkedColor = color, uncheckedColor = color, checkmarkColor = onColor(color)))
                Text(c.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = { edit(c) }) { Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.calendar_options, c.name)) }
            }
        }
        TextButton(onClick = add) { Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.padding(end = 8.dp)); Text(stringResource(R.string.calendar_new_calendar), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Row {
            TextButton(onClick = import) { Text(stringResource(R.string.common_import), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (cals.isNotEmpty()) TextButton(onClick = exportAll) { Text(stringResource(R.string.calendar_export_all), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            trash?.let { TextButton(onClick = it) { Text(stringResource(R.string.calendar_trash), maxLines = 1, overflow = TextOverflow.Ellipsis) } }
        }
        if (settings) { RemindersSwitch(); ShareWithClaude() }
    }
}

/** Deleted events (asked 18 Oct): each with its day and the days it has left, and Restore into a calendar. */
@Composable private fun CalendarTrash(root: java.io.File, cals: List<CalendarInfo>, version: Int, dismiss: () -> Unit, restore: (CalFile, CalendarInfo) -> Unit) {
    val items by produceState(emptyList<Pair<CalFile, Long>>(), version) { value = withContext(Dispatchers.IO) { CalendarFiles.trashed(root) } }
    val w = rememberUiWords()
    androidx.compose.material3.AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.calendar_trash)) },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_close), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.calendar_trash_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (items.isEmpty()) Text(stringResource(R.string.calendar_trash_empty), Modifier.padding(top = 16.dp))
                items.forEach { (f, at) ->
                    val e = f.master ?: f.overrides.firstOrNull()
                    val left = (30 - (System.currentTimeMillis() - at) / 86_400_000L).coerceAtLeast(0)
                    var pick by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e?.title?.ifBlank { null } ?: stringResource(R.string.calendar_no_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(e?.start?.format(DateTimeFormatter.ofPattern(if (e.allDay) "EEE d MMM yyyy" else "EEE d MMM yyyy, HH:mm", w.locale)),
                                w.plural(R.plurals.calendar_days_left, left.toInt(), left)).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box {
                            TextButton(enabled = cals.isNotEmpty(), onClick = { if (cals.size == 1) restore(f, cals[0]) else pick = true }) { Text(stringResource(R.string.calendar_restore), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            DropdownMenu(pick, { pick = false }) {
                                cals.forEach { c -> DropdownMenuItem(text = { Text(c.name) }, onClick = { pick = false; restore(f, c) },
                                    leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(Color(c.color))) }) }
                            }
                        }
                    }
                }
            }
        })
}

/**
 * Share with Claude (asked 18 Oct): a skill the app was built with (assets/<skill>.zip, from skills/<skill>), to send to
 * the Claude app, mail or Drive, or to save in Files. The Calendar's and the Timer's (19 Oct).
 */
@Composable internal fun ShareWithClaude(skill: String = "tetra-calendar", part: String = "Calendar", what: String = stringResource(R.string.calendar_share_what)) {
    val context = LocalContext.current
    val save = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) runCatching { context.contentResolver.openOutputStream(uri)!!.use { it.write(skillZip(context, skill)) } }
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(stringResource(R.string.calendar_share_with_claude), style = MaterialTheme.typography.titleSmall)
        Text(what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { shareSkill(context, skill, part) }) { Text(stringResource(R.string.common_share), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            TextButton(onClick = { save.launch("$skill.zip") }) { Text(stringResource(R.string.calendar_save_to_files), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

internal fun skillZip(context: android.content.Context, skill: String) = context.assets.open("$skill.zip").use { it.readBytes() }

/** The skill to the share sheet: the Claude app, mail or Drive. */
internal fun shareSkill(context: android.content.Context, skill: String, part: String) {
    runCatching {
        val file = java.io.File(context.cacheDir, "share/$skill.zip").apply { parentFile?.mkdirs(); writeBytes(skillZip(context, skill)) }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/zip")
            .putExtra(android.content.Intent.EXTRA_STREAM, uri).putExtra(android.content.Intent.EXTRA_SUBJECT, context.getString(R.string.calendar_skill_subject, part))
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), context.getString(R.string.calendar_share_skill)))
    }.onFailure { WriterLog.e("$part: sharing the skill failed", it) }
}

/** A reminder rung while Tetra is in front pops up here, as HyperOS shows none for the app in front (the user, 24 Oct). */
@Composable internal fun CalendarRinging() {
    val r = CalendarAlarms.ringing.value ?: return
    val c = LocalContext.current
    androidx.compose.material3.AlertDialog(onDismissRequest = { CalendarAlarms.ringing.value = null }, // beside it: the notification stays
        icon = { Icon(painterResource(R.drawable.ic_calendar_today), contentDescription = null, tint = Color(r.color)) },
        title = { Text(r.title) }, text = { Text(r.text) },
        confirmButton = { TextButton(onClick = { CalendarAlarms.answer(c, r, done = true) }) { Text(stringResource(R.string.calendar_reminder_done), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        dismissButton = { TextButton(onClick = { CalendarAlarms.answer(c, r, done = false) }) { Text(stringResource(R.string.calendar_snooze), maxLines = 1, overflow = TextOverflow.Ellipsis) } })
}

@Composable internal fun RemindersSwitch() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(CalendarAlarms.enabled(context)) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.calendar_reminders_here), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(on, { on = it; CalendarAlarms.setEnabled(context, it) }, colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = CalendarPink))
    }
}

/** The Year view: the twelve months small, a day tapped opens it. */
@Composable private fun YearGrid(selected: LocalDate, today: LocalDate, busy: Set<LocalDate>, pick: (LocalDate) -> Unit) {
    val locale = LocalContext.current.uiLocale()
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(androidx.compose.foundation.lazy.grid.GridCells.Adaptive(250.dp), Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 8.dp, end = 8.dp, top = HEADER, bottom = 84.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(12) { m ->
            val month = YearMonth.of(selected.year, m + 1)
            Column {
                Text(monthTitle(month.atDay(1), locale, year = false), style = MaterialTheme.typography.titleSmall,
                    color = if (YearMonth.from(today) == month) CalendarPink else MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
                Row { weekOf(today).forEach { Text(it.dayOfWeek.getDisplayName(TextStyle.NARROW, locale), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                monthCells(month).chunked(7).forEach { week ->
                    Row { week.forEach { d -> if (d.month == month.month) DayDot(d, Modifier.weight(1f), d == selected, d == today, true, d in busy, 28.dp) { pick(d) } else Spacer(Modifier.weight(1f)) } }
                }
            }
        }
    }
}

/** The side panel's small month: ‹ › to look around without moving the main view; a day tapped goes there. */
@Composable private fun MiniMonth(selected: LocalDate, today: LocalDate, busy: Set<LocalDate>, pick: (LocalDate) -> Unit) {
    var month by remember { mutableStateOf(YearMonth.from(selected)) }
    LaunchedEffect(selected) { month = YearMonth.from(selected) }
    val locale = LocalContext.current.uiLocale()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(monthTitle(month.atDay(1), locale), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(start = 6.dp))
            IconButton(onClick = { month = month.minusMonths(1) }, Modifier.size(36.dp)) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.calendar_previous_month)) }
            IconButton(onClick = { month = month.plusMonths(1) }, Modifier.size(36.dp)) { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = stringResource(R.string.calendar_next_month)) }
        }
        Row { weekOf(today).forEach { Text(it.dayOfWeek.getDisplayName(TextStyle.NARROW, locale), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        monthCells(month).chunked(7).forEach { week ->
            Row { week.forEach { d -> DayDot(d, Modifier.weight(1f), d == selected, d == today, d.month == month.month, d in busy, 32.dp) { pick(d) } } }
        }
    }
}

/** A day's number in a circle: filled when chosen, ringed when today, dimmed outside its month, a dot under when busy. */
@Composable private fun DayDot(d: LocalDate, modifier: Modifier, chosen: Boolean, isToday: Boolean, inMonth: Boolean, busy: Boolean, size: Dp, click: () -> Unit) {
    val primary = CalendarPink
    Column(modifier.clickable(onClick = click).padding(vertical = 1.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size).clip(CircleShape)
            .background(if (chosen) primary else Color.Transparent)
            .then(if (isToday && !chosen) Modifier.border(1.5.dp, primary, CircleShape) else Modifier), contentAlignment = Alignment.Center) {
            Text("${d.dayOfMonth}", style = MaterialTheme.typography.bodySmall, fontWeight = if (isToday || busy) FontWeight.Bold else null,
                color = when { chosen -> Color.White; !inMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f); else -> MaterialTheme.colorScheme.onSurface })
        }
        Box(Modifier.padding(top = 1.dp).size(4.dp).clip(CircleShape).background(if (busy) MaterialTheme.colorScheme.onSurfaceVariant else Color.Transparent))
    }
}

/**
 * The narrow screen's small calendar, at the bottom above the island (the user, 18 Oct): 1, 2 or 3 weeks from the chosen
 * day's, or its whole month — the handle dragged up for more, down for fewer, the choice kept; swiped sideways for the
 * next ones; a day tapped moves the events above to it.
 */
@Composable private fun BottomStrip(selected: LocalDate, today: LocalDate, days: Map<LocalDate, List<Occurrence>>, pick: (LocalDate) -> Unit) {
    val context = LocalContext.current
    var weeks by remember { mutableIntStateOf(CalendarPrefs.stripWeeks(context)) }
    fun set(n: Int) { weeks = n; CalendarPrefs.setStripWeeks(context, n) }
    val sizes = listOf(1, 2, 3, 6) // 6: the month
    var drag by remember { mutableStateOf(Offset.Zero) }
    val sel = rememberUpdated(selected)
    val month = weeks == 6
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).pointerInput(Unit) {
        detectDragGestures(onDragStart = { drag = Offset.Zero }, onDrag = { _, d -> drag += d }, onDragEnd = {
            val k = sizes.indexOf(weeks)
            if (Math.abs(drag.y) > Math.abs(drag.x)) { if (drag.y < -30) set(sizes[minOf(k + 1, sizes.lastIndex)]) else if (drag.y > 30) set(sizes[maxOf(k - 1, 0)]) }
            else if (Math.abs(drag.x) > 60) {
                val n = if (drag.x < 0) 1L else -1L
                pick(if (weeks == 6) sel.value.plusMonths(n) else sel.value.plusWeeks(n * weeks))
            }
        })
    }) {
        HorizontalDivider()
        Box(Modifier.fillMaxWidth().clickable { set(sizes[(sizes.indexOf(weeks) + 1) % sizes.size]) }.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(36.dp).height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)))
        }
        if (month) Text(monthTitle(selected, context.uiLocale()), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, bottom = 2.dp))
        Row { weekOf(today).forEach { Text(it.dayOfWeek.getDisplayName(TextStyle.SHORT, context.uiLocale()), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        val rows = if (month) monthCells(YearMonth.from(selected)).chunked(7).filter { w -> w.any { it.month == selected.month } }
            else (0 until weeks).map { i -> weekOf(selected.plusWeeks(i.toLong())) }
        rows.forEach { week ->
            Row { week.forEach { d -> DayDot(d, Modifier.weight(1f), d == selected, d == today, !month || d.month == selected.month, !days[d].isNullOrEmpty(), 34.dp) { pick(d) } } }
        }
    }
}

@Composable private fun <T> rememberUpdated(v: T) = remember { mutableStateOf(v) }.also { it.value = v }

private sealed interface AgendaRow { val day: LocalDate }
private data class AgendaHead(override val day: LocalDate) : AgendaRow
private data class AgendaItem(override val day: LocalDate, val o: Occurrence) : AgendaRow
private data class AgendaNone(override val day: LocalDate) : AgendaRow

/** Outlook's agenda: every day from a year before to a year after, its events or "No events"; the week row follows it. */
@Composable private fun Agenda(anchor: Int, days: Map<LocalDate, List<Occurrence>>, today: LocalDate, selected: LocalDate, select: (LocalDate) -> Unit, open: (Occurrence) -> Unit, top: Dp = 0.dp) {
    val context = LocalContext.current
    val w = rememberUiWords()
    val rows = remember(anchor, days) {
        val out = mutableListOf<AgendaRow>()
        var d = LocalDate.of(anchor - 1, 1, 1)
        val end = LocalDate.of(anchor + 2, 1, 1)
        while (d < end) {
            out.add(AgendaHead(d))
            days[d].orEmpty().ifEmpty { null }?.forEach { out.add(AgendaItem(d, it)) } ?: out.add(AgendaNone(d))
            d = d.plusDays(1)
        }
        out
    }
    val state = rememberLazyListState()
    val head = { day: LocalDate -> rows.indexOfFirst { it is AgendaHead && it.day == day }.coerceAtLeast(0) }
    LaunchedEffect(selected, rows) {
        if (rows.getOrNull(state.firstVisibleItemIndex)?.day != selected || state.firstVisibleItemIndex == 0) state.scrollToItem(head(selected))
    }
    LaunchedEffect(state, rows) {
        snapshotFlow { state.firstVisibleItemIndex to state.isScrollInProgress }.collect { (i, moving) -> if (moving) rows.getOrNull(i)?.day?.let(select) }
    }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = top)) {
        itemsIndexed(rows, key = { i, _ -> i }) { _, r ->
            when (r) {
                is AgendaHead -> Text(
                    r.day.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", w.locale)).let { if (r.day == today) w.get(R.string.calendar_today_on, it) else if (r.day == today.plusDays(1)) w.get(R.string.calendar_tomorrow_on, it) else it },
                    style = MaterialTheme.typography.labelLarge, color = if (r.day == today) CalendarPink else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 16.dp, vertical = 6.dp))
                is AgendaNone -> Text(stringResource(R.string.calendar_no_events), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                is AgendaItem -> {
                    val o = r.o
                    val color = Color(o.calendar.color)
                    Row(Modifier.fillMaxWidth().clickable { open(o) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(64.dp)) {
                            if (o.allDay) Text(stringResource(R.string.calendar_all_day), style = MaterialTheme.typography.bodyMedium)
                            else {
                                Text(if (o.start.withZoneSameInstant(ZoneId.systemDefault()).toLocalDate() == r.day) timeText(context, o.start) else "…", style = MaterialTheme.typography.bodyMedium)
                                Text(lengthText(w, ChronoUnit.MINUTES.between(o.start, o.end)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Box(Modifier.padding(end = 12.dp).size(10.dp).clip(CircleShape).background(color))
                        Column(Modifier.weight(1f)) {
                            Text(o.event.title.ifEmpty { stringResource(R.string.calendar_no_title) }, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (o.event.place.isNotEmpty()) Text(o.event.place, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** An event as a chip: all-day filled with its colour, timed a dot and its time (Google's month grid). */
@Composable private fun Chip(o: Occurrence, day: LocalDate, compact: Boolean, open: (Occurrence) -> Unit) {
    val context = LocalContext.current
    val color = Color(o.calendar.color)
    val style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium
    if (o.allDay) Text(o.event.title.ifEmpty { stringResource(R.string.calendar_no_title) }, style = style, color = onColor(color), maxLines = 1, overflow = TextOverflow.Clip,
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(RoundedCornerShape(4.dp)).background(color).clickable { open(o) }.padding(horizontal = 4.dp))
    else Row(Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(RoundedCornerShape(4.dp)).clickable { open(o) }.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.padding(end = 3.dp).size(6.dp).clip(CircleShape).background(color))
        val starts = o.start.withZoneSameInstant(ZoneId.systemDefault()).toLocalDate() == day
        Text((if (compact || !starts) "" else timeText(context, o.start) + " ") + o.event.title.ifEmpty { stringResource(R.string.calendar_no_title) }, style = style, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

// shortcut: an event of several days shows as a chip on each day, not one bar across them (Google's); a bar when asked.
@Composable private fun MonthGrid(month: YearMonth, days: Map<LocalDate, List<Occurrence>>, today: LocalDate, selected: LocalDate, compact: Boolean, pick: (LocalDate) -> Unit, open: (Occurrence) -> Unit) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxSize()) {
        Row { weekOf(today).forEach { Text(it.dayOfWeek.getDisplayName(TextStyle.SHORT, LocalContext.current.uiLocale()).uppercase(LocalContext.current.uiLocale()), Modifier.weight(1f).padding(vertical = 4.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val rowH = maxHeight / 6
            val chipH = if (compact) 16.dp else 20.dp
            val fit = ((rowH - 28.dp) / chipH).toInt().coerceAtLeast(0)
            Column {
                monthCells(month).chunked(7).forEach { week ->
                    Row(Modifier.height(rowH)) {
                        week.forEach { d ->
                            val list = days[d].orEmpty()
                            Column(Modifier.weight(1f).fillMaxHeight().border(0.5.dp, line).clickable { pick(d) }.padding(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                val isToday = d == today
                                Box(Modifier.size(24.dp).clip(CircleShape).background(if (isToday) CalendarPink else Color.Transparent), contentAlignment = Alignment.Center) {
                                    Text("${d.dayOfMonth}", style = MaterialTheme.typography.labelMedium, fontWeight = if (d == selected) FontWeight.Bold else null,
                                        color = when { isToday -> Color.White; d.month != month.month -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f); else -> MaterialTheme.colorScheme.onSurface })
                                }
                                val n = if (list.size <= fit) list.size else (fit - 1).coerceAtLeast(0)
                                list.take(n).forEach { Chip(it, d, compact, open) }
                                if (list.size > n) Text(if (n == 0 && compact) "•${list.size}" else stringResource(R.string.calendar_more, list.size - n), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Day, 3 days and Week: hours down the side, events as blocks side by side where they overlap, a red line at now. */
@Composable private fun TimeGrid(dates: List<LocalDate>, days: Map<LocalDate, List<Occurrence>>, today: LocalDate, open: (Occurrence) -> Unit, slot: (LocalDate, Int) -> Unit) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val hourH = 52.dp
    val gutter = 52.dp
    val line = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(gutter))
            dates.forEach { d ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(d.dayOfWeek.getDisplayName(TextStyle.SHORT, context.uiLocale()).uppercase(context.uiLocale()), style = MaterialTheme.typography.labelSmall, color = if (d == today) CalendarPink else MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.size(32.dp).clip(CircleShape).background(if (d == today) CalendarPink else Color.Transparent), contentAlignment = Alignment.Center) {
                        Text("${d.dayOfMonth}", style = MaterialTheme.typography.titleMedium, color = if (d == today) Color.White else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        val allDay = dates.map { d -> days[d].orEmpty().filter { it.allDay } }
        if (allDay.any { it.isNotEmpty() }) Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Spacer(Modifier.width(gutter))
            allDay.forEachIndexed { i, list -> Column(Modifier.weight(1f).padding(horizontal = 1.dp)) { list.take(4).forEach { Chip(it, dates[i], false, open) }; if (list.size > 4) Text(stringResource(R.string.calendar_more, list.size - 4), style = MaterialTheme.typography.labelSmall) } }
        }
        HorizontalDivider()
        val scroll = rememberScrollState()
        val density = androidx.compose.ui.platform.LocalDensity.current
        LaunchedEffect(Unit) { scroll.scrollTo(with(density) { (hourH * 7.5f).roundToPx() }) }
        Row(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
            Column(Modifier.width(gutter)) {
                (0..23).forEach { h -> Box(Modifier.height(hourH).fillMaxWidth().padding(end = 6.dp), contentAlignment = Alignment.TopEnd) {
                    if (h > 0) Text(hourText(context, h), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.offset(y = (-7).dp))
                } }
            }
            dates.forEach { d ->
                BoxWithConstraints(Modifier.weight(1f).height(hourH * 24).pointerInput(d) {
                    // an empty hour tapped: a new event there (Google's)
                    detectTapGestures { p -> slot(d, (p.y / size.height * 24).toInt().coerceIn(0, 23) * 60) }
                }.drawBehind {
                    val step = size.height / 24
                    for (h in 0..23) drawLine(line, Offset(0f, h * step), Offset(size.width, h * step), 1f)
                    drawLine(line, Offset(0f, 0f), Offset(0f, size.height), 1f)
                }) {
                    val w = maxWidth
                    val blocks = dayBlocks(d, days[d].orEmpty().filter { !it.allDay }, zone)
                    blocks.forEach { b ->
                        val color = Color(b.o.calendar.color)
                        val bw = w * b.width
                        val narrow = bw < 72.dp
                        // a thin edge in the page's colour keeps a cascaded card apart from the one under it
                        Column(Modifier.offset(x = w * b.left, y = hourH * (b.from / 60f)).width(bw).height(if (b.short) 20.dp else hourH * ((b.to - b.from) / 60f))
                            .border(1.dp, MaterialTheme.colorScheme.background, RoundedCornerShape(5.dp)).padding(1.dp).clip(RoundedCornerShape(4.dp))
                            .background(color).clickable { open(b.o) }.padding(horizontal = 4.dp, vertical = if (b.short) 0.dp else 2.dp)) {
                            Text(b.o.event.title.ifEmpty { stringResource(R.string.calendar_no_title) } + if (b.short && !narrow) ", " + timeText(context, b.o.start) else "",
                                style = if (b.short) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium, color = onColor(color),
                                maxLines = if (narrow || b.short) 1 else 2, softWrap = !narrow && !b.short, overflow = TextOverflow.Ellipsis)
                            if (!b.short && !narrow && b.to - b.from >= 45) Text(timeText(context, b.o.start) + " – " + timeText(context, b.o.end), style = MaterialTheme.typography.labelSmall, color = onColor(color).copy(alpha = 0.85f), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (d == today) {
                        val now = LocalTime.now()
                        Box(Modifier.offset(y = hourH * ((now.hour * 60 + now.minute) / 60f) - 1.dp).fillMaxWidth().height(2.dp).background(Color(0xFFEA4335)))
                    }
                }
            }
        }
    }
}

private fun hourText(context: Context, h: Int) =
    if (android.text.format.DateFormat.is24HourFormat(context)) "%02d:00".format(h) else LocalTime.of(h, 0).format(DateTimeFormatter.ofPattern("h a", context.uiLocale()))

private class TimeBlock(val o: Occurrence, val from: Int, val to: Int, val short: Boolean, var left: Float = 0f, var width: Float = 1f)

/**
 * The day's timed events laid out as Google does, so every title stays readable: events starting within a quarter of
 * an hour of each other (less than a title's height) share the width side by side; one starting later over a longer one is drawn on top of it, indented
 * (cascading), its own title clear. A very short one (under half an hour) is a small chip.
 */
private fun dayBlocks(d: LocalDate, occ: List<Occurrence>, zone: ZoneId): List<TimeBlock> {
    val dayStart = d.atStartOfDay(zone)
    val blocks = occ.map { o ->
        val from = ChronoUnit.MINUTES.between(dayStart, o.start).coerceIn(0, 1440).toInt()
        val to = ChronoUnit.MINUTES.between(dayStart, o.end).coerceIn(0, 1440).toInt()
        TimeBlock(o, from, minOf(maxOf(to, from + 22), 1440), short = to - from < 30)
    }.sortedWith(compareBy({ it.from }, { -it.to }))
    val groups = mutableListOf<MutableList<TimeBlock>>()
    for (b in blocks) {
        val g = groups.lastOrNull()
        // a short one never takes width from a long one: it sits on top, indented, as a chip
        if (g != null && b.short == g[0].short && b.from - g.last().from < 15 && b.from < g.maxOf { it.to }) g.add(b) else groups.add(mutableListOf(b))
    }
    groups.forEachIndexed { i, g ->
        val depth = groups.take(i).count { e -> e.maxOf { it.to } > g[0].from }.coerceAtMost(3)
        val left = depth * 0.14f
        g.forEachIndexed { k, b -> b.left = left + (1 - left) * k / g.size; b.width = (1 - left) / g.size }
    }
    return blocks
}

/** Text with its web links tappable (they open in the browser or the app that owns them). */
private fun linked(text: String, color: Color) = androidx.compose.ui.text.buildAnnotatedString {
    var i = 0
    Regex("""https?://\S+""").findAll(text).forEach { m ->
        append(text.substring(i, m.range.first))
        withLink(androidx.compose.ui.text.LinkAnnotation.Url(m.value, androidx.compose.ui.text.TextLinkStyles(androidx.compose.ui.text.SpanStyle(color = color,
            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)))) { append(m.value) }
        i = m.range.last + 1
    }
    append(text.substring(i))
}

/** An event's card: when, how often, which calendar, its reminders, place and notes. */
@Composable private fun EventCard(o: Occurrence, close: () -> Unit, edit: () -> Unit, delete: () -> Unit, keep: () -> Unit) {
    val context = LocalContext.current
    val w = rememberUiWords()
    val e = o.event
    val zone = ZoneId.systemDefault()
    val whenText = if (o.allDay) {
        val last = o.end.toLocalDate().minusDays(1)
        val f = DateTimeFormatter.ofPattern("EEEE, d MMMM", w.locale)
        if (last <= o.start.toLocalDate()) o.start.format(f) else o.start.format(f) + " – " + last.format(f)
    } else {
        val s = o.start.withZoneSameInstant(zone); val en = o.end.withZoneSameInstant(zone)
        s.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", w.locale)) + " · " + timeText(context, s) + " – " +
            (if (en.toLocalDate() != s.toLocalDate()) en.format(DateTimeFormatter.ofPattern("d MMM ", w.locale)) else "") + timeText(context, en)
    }
    Dialog(onDismissRequest = close) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 8.dp, end = 12.dp).size(14.dp).clip(RoundedCornerShape(4.dp)).background(Color(o.calendar.color)))
                    Text(e.title.ifEmpty { stringResource(R.string.calendar_no_title) }, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = edit) { Icon(painterResource(R.drawable.ic_edit), contentDescription = stringResource(R.string.common_edit)) }
                    IconButton(onClick = delete) { Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.common_delete)) }
                    IconButton(onClick = close) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_close)) }
                }
                Text(whenText, style = MaterialTheme.typography.bodyLarge)
                (o.file.master ?: e).let { m -> repeatText(w, m.rrule, m.start) }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (e.place.isNotEmpty()) CalendarPlace.label(e.place).takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                CalendarPlace.uri(e.place)?.let { uri ->
                    OutlinedButton(onClick = {
                        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri))) }
                            .onFailure { android.widget.Toast.makeText(context, context.getString(R.string.calendar_no_maps_app), android.widget.Toast.LENGTH_SHORT).show() }
                    }) {
                        Icon(painterResource(R.drawable.ic_map), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.calendar_open_in_maps), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // a birthday with its year: the age that day
                o.file.comps.flatMap { c -> c.comps("VEVENT") }.firstOrNull { it.prop("RECURRENCE-ID") == null }?.prop("X-TETRA-BIRTH-YEAR")?.value?.trim()?.toIntOrNull()
                    ?.let { y -> if (o.start.year > y) Text(stringResource(R.string.calendar_turns, o.start.year - y), style = MaterialTheme.typography.bodyMedium) }
                Text(o.calendar.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (ContactsBirthdays.contactOf(o.file) != null) TextButton(onClick = edit) { Text(stringResource(R.string.calendar_from_contacts)) }
                e.alarms.forEach { Text("🔔 " + alarmText(w, it, e.allDay, o.start), style = MaterialTheme.typography.bodyMedium) }
                if (e.notes.isNotEmpty()) Text(linked(e.notes, MaterialTheme.colorScheme.primary), style = MaterialTheme.typography.bodyMedium)
                if (o.file.versions > 1) {
                    Text(stringResource(R.string.calendar_versions, o.file.versions), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = keep) { Text(stringResource(R.string.calendar_keep_this), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Text(o.file.file.relativeTo(o.calendar.dir.parentFile ?: File("/")).path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}


/**
 * What stands between a reminder and the person: notifications off, and on a Xiaomi its own Autostart and battery
 * saver (a restart reaches Tetra only with Autostart). Asked once; a line stays while notifications are off.
 */
@Composable private fun ReminderNotes() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("calendar", Context.MODE_PRIVATE) }
    fun allowed() = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    var notifications by remember { mutableStateOf(allowed()) }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { notifications = allowed() }
    LaunchedEffect(Unit) {
        if (!notifications && android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("askedNotifications", false)) {
            prefs.edit().putBoolean("askedNotifications", true).apply()
            ask.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    fun open(i: android.content.Intent) = runCatching { context.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    val appSettings = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
    // the rest (exact alarms, Hyper Island, Xiaomi's Autostart and battery) is in Settings › Calendar (the user, 18 Oct)
    if (!notifications) Note(stringResource(R.string.calendar_notifications_off), stringResource(R.string.calendar_turn_on) to { open(appSettings) })
}

/** What a calendar's import says when done: "Imported: 12 new, 3 updated". Each calendar into the one of its name. */
internal fun importCalendars(context: Context, uri: android.net.Uri): String {
    val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "Imported"
    val incoming = (if (bytes.size > 1 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) CalendarTransfer.readZip(bytes)
        else listOf(CalendarTransfer.read(bytes.toString(Charsets.UTF_8), name.removeSuffix(".ics")))).filter { it.events.isNotEmpty() }
    if (incoming.isEmpty()) return context.getString(R.string.calendar_no_events_in_file)
    val root = calendarRoot().apply { mkdirs() }
    var added = 0; var updated = 0
    incoming.forEach { inc ->
        val all = CalendarFiles.calendars(root)
        val to = all.firstOrNull { it.name.equals(inc.name, true) } ?: CalendarFiles.newCalendar(root, inc.name, inc.color ?: CalendarFiles.PALETTE[all.size % CalendarFiles.PALETTE.size])
        CalendarTransfer.write(to, inc).let { (a, u) -> added += a; updated += u }
    }
    runCatching { CalendarAlarms.plan(context) }
    ScopedSync.soon(context, "calendar", 0, orWhole = true)
    return importedText(uiWords(context), added, updated)
}

private fun importedText(w: UiWords, added: Int, updated: Int) =
    if (updated > 0) w.get(R.string.calendar_imported_updated, added, updated) else w.get(R.string.calendar_imported, added)

@Composable private fun Note(text: String, vararg actions: Pair<String, () -> Unit>) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 2.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions.forEach { (label, act) -> TextButton(onClick = act) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) } } }
        }
    }
}

/** What another app asked the Calendar to import (an .ics opened with Tetra); the Calendar takes it when it shows. */
internal object CalendarInbox { val pending = kotlinx.coroutines.flow.MutableStateFlow<android.net.Uri?>(null) }

/** Import: one calendar into a new one or an existing one; Takeout's several each into its own (by name). */
@Composable private fun ImportDialog(list: List<CalendarTransfer.Incoming>, cals: List<CalendarInfo>, dismiss: () -> Unit, go: (List<Pair<CalendarTransfer.Incoming, CalendarInfo?>>) -> Unit) {
    val single = list.singleOrNull()
    var target by remember { mutableStateOf(single?.let { s -> cals.firstOrNull { it.name.equals(s.name, true) } }) }
    androidx.compose.material3.AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.common_import)) }, confirmButton = {
        TextButton(onClick = { go(if (single != null) listOf(single to target) else list.map { it to null }) }) { Text(stringResource(R.string.common_import), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) } }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (single != null) {
                Text(pluralStringResource(R.plurals.calendar_import_events, single.events.size, single.events.size, single.name))
                var menu by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { menu = true }) { Text((target?.name ?: stringResource(R.string.calendar_import_new, single.name)) + "  ▾", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.calendar_import_new, single.name)) }, onClick = { target = null; menu = false })
                        cals.forEach { c -> DropdownMenuItem(text = { Text(c.name) }, leadingIcon = { Box(Modifier.size(14.dp).clip(CircleShape).background(Color(c.color))) }, onClick = { target = c; menu = false }) }
                    }
                }
                Text(stringResource(R.string.calendar_import_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(R.string.calendar_import_many, list.size))
                list.forEach { Text(pluralStringResource(R.plurals.calendar_import_line, it.events.size, it.name, it.events.size), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    })
}
