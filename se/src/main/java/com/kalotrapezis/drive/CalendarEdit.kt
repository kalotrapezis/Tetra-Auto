package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/*
 * The Calendar's editor (Plan.md § Calendar › Screens): title, calendar, all day, start and end, repeat, reminders,
 * place, notes. Full screen on a phone, a dialog on a big screen. It only builds a CalEvent; CalendarScreen writes it.
 */

/** Which days of a repeating event a change or a deletion is for (Google's three). */
internal enum class Scope(@androidx.annotation.StringRes val label: Int) { One(R.string.calendar_scope_one), Following(R.string.calendar_scope_following), All(R.string.calendar_scope_all) }

private const val DAY = 86_400L

/** The repeat menu's choices for a start date: (RRULE or null, its words). */
internal fun repeatPresets(w: UiWords, d: LocalDate): List<Pair<String?, String>> {
    val dow = d.dayOfWeek
    val code = dow.name.take(2)
    val nth = (d.dayOfMonth - 1) / 7 + 1
    val name = dow.getDisplayName(TextStyle.FULL, w.locale)
    return listOf(
        null to w.get(R.string.calendar_no_repeat),
        "FREQ=DAILY" to w.get(R.string.calendar_repeat_daily),
        "FREQ=WEEKLY;BYDAY=$code" to w.get(R.string.calendar_repeat_on, w.get(R.string.calendar_repeat_weekly), name),
        "FREQ=MONTHLY;BYDAY=$nth$code" to w.get(R.string.calendar_repeat_on_the, w.get(R.string.calendar_repeat_monthly), nthDay(w, nth, dow)),
        "FREQ=MONTHLY" to w.get(R.string.calendar_repeat_on_day, w.get(R.string.calendar_repeat_monthly), d.dayOfMonth.toString()),
        "FREQ=YEARLY" to w.get(R.string.calendar_repeat_on, w.get(R.string.calendar_repeat_yearly), d.format(DateTimeFormatter.ofPattern("d MMMM", w.locale))),
        "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR" to w.get(R.string.calendar_repeat_weekdays_full),
    )
}

/** A reminder N days (or weeks) before at a clock time, as RFC 5545 has it: from the start, nominal days, then seconds. */
internal fun daysBeforeAt(start: ZonedDateTime, days: Long, at: LocalTime, daily: Boolean) =
    CalAlarm(-days, (at.toSecondOfDay() - start.toLocalTime().toSecondOfDay()).toLong(), repeat = if (daily) (days - 1).toInt().coerceAtLeast(0) else 0, every = if (daily) DAY else 0)

/** A reminder set "N days before at 09:00" stays at 09:00 when its event moves to another hour (asked in Plan.md). */
internal fun keepClock(alarms: List<CalAlarm>, old: ZonedDateTime, new: ZonedDateTime): List<CalAlarm> = alarms.map { a ->
    if (a.at != null || a.fromEnd || a.days * DAY + a.seconds > -DAY) a
    else {
        val fires = old.plusDays(a.days).plusSeconds(a.seconds)
        val before = ChronoUnit.DAYS.between(fires.toLocalDate(), old.toLocalDate())
        daysBeforeAt(new, before, fires.toLocalTime(), false).copy(repeat = a.repeat, every = a.every)
    }
}

private fun presetAlarms(allDay: Boolean, start: ZonedDateTime): List<CalAlarm> = if (allDay)
    listOf(0L, 1L, 2L, 7L).map { daysBeforeAt(start, it, LocalTime.of(9, 0), false) }
else listOf(0L, 5, 10, 15, 30, 60, 1440).map { CalAlarm(0, -it * 60) }

/** New events' reminders: 30 minutes before, or the day before at 9:00 when all day. */
internal fun defaultAlarms(allDay: Boolean, start: ZonedDateTime) =
    listOf(if (allDay) daysBeforeAt(start, 1, LocalTime.of(9, 0), false) else CalAlarm(0, -1800))

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun EventEditor(
    draft: CalEvent,
    calendar: CalendarInfo?,
    calendars: List<CalendarInfo>,
    isNew: Boolean,
    wide: Boolean,
    cancel: () -> Unit,
    heading: String? = null,
    save: (CalEvent, CalendarInfo?) -> Unit,
) {
    val context = LocalContext.current
    val w = rememberUiWords()
    var title by remember { mutableStateOf(draft.title) }
    var place by remember { mutableStateOf(draft.place) }
    var notes by remember { mutableStateOf(draft.notes) }
    var allDay by remember { mutableStateOf(draft.allDay) }
    var start by remember { mutableStateOf(draft.start.withZoneSameInstant(if (draft.allDay) draft.start.zone else ZoneId.systemDefault())) }
    var end by remember { mutableStateOf(draft.end.withZoneSameInstant(start.zone).let { if (draft.allDay) it.minusDays(1) else it }) } // all day: the last day shown, not the day after
    var rrule by remember { mutableStateOf(draft.rrule) }
    var alarms by remember { mutableStateOf(draft.alarms) }
    var cal by remember { mutableStateOf(calendar ?: calendars.firstOrNull()) }
    var picking by remember { mutableStateOf<String?>(null) } // "startDate", "startTime", "endDate", "endTime"
    var customRepeat by remember { mutableStateOf(false) }
    var customAlarm by remember { mutableStateOf(false) }
    BackHandler(onBack = cancel)

    fun setStart(s: ZonedDateTime) {
        val presets = repeatPresets(w, start.toLocalDate()).map { it.first }
        val k = presets.indexOf(rrule)
        if (k > 0) rrule = repeatPresets(w, s.toLocalDate())[k].first // "Weekly on Tuesday" follows the date to Wednesday
        end = end.plusMinutes(ChronoUnit.MINUTES.between(start, s)) // the length kept
        start = s
    }
    fun result(): CalEvent {
        val s = if (allDay) start.toLocalDate().atStartOfDay(ZoneId.systemDefault()) else start
        val e = if (allDay) end.toLocalDate().plusDays(1).atStartOfDay(ZoneId.systemDefault()) else if (end.isAfter(start)) end else start.plusHours(1)
        val kept = if (allDay == draft.allDay) keepClock(alarms, draft.start, s) else alarms
        return draft.copy(title = title.trim(), place = place.trim(), notes = notes.trim(), allDay = allDay, start = s, end = e, rrule = rrule, alarms = kept)
    }
    val dateF = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", w.locale)

    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = cancel) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_cancel)) }
                Text(heading ?: stringResource(if (isNew) R.string.calendar_new_event else R.string.calendar_edit_event), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { save(result(), cal) }) { Text(stringResource(R.string.common_save), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            HorizontalDivider()
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.calendar_title)) }, singleLine = true, textStyle = MaterialTheme.typography.titleLarge)
                // the calendar it goes in
                var calMenu by remember { mutableStateOf(false) }
                Box {
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { calMenu = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.padding(end = 12.dp).size(16.dp).clip(CircleShape).background(Color(cal?.color ?: CalendarFiles.PALETTE[0])))
                        Text(cal?.name ?: stringResource(R.string.calendar_personal_new), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text("▾")
                    }
                    DropdownMenu(expanded = calMenu, onDismissRequest = { calMenu = false }) {
                        calendars.forEach { c ->
                            DropdownMenuItem(text = { Text(c.name) }, leadingIcon = { Box(Modifier.size(14.dp).clip(CircleShape).background(Color(c.color))) }, onClick = { cal = c; calMenu = false })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.calendar_all_day), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = allDay, onCheckedChange = { on ->
                        if (on != allDay) alarms = defaultAlarms(on, start)
                        allDay = on
                        if (!on && !end.isAfter(start)) end = start.plusHours(1)
                    }, colors = SwitchDefaults.colors(checkedTrackColor = CalendarPink))
                }
                listOf(true to start, false to end).forEach { (isStart, t) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(if (isStart) R.string.calendar_starts else R.string.calendar_ends), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(72.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { picking = if (isStart) "startDate" else "endDate" }) { Text(t.format(dateF), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        if (!allDay) TextButton(onClick = { picking = if (isStart) "startTime" else "endTime" }) { Text(timeText(context, t), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
                if (!allDay && !end.isAfter(start)) Text(stringResource(R.string.calendar_end_before_start), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                // repeat
                var repeatMenu by remember { mutableStateOf(false) }
                val presets = repeatPresets(w, start.toLocalDate())
                Box {
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { repeatMenu = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_sync), contentDescription = null, modifier = Modifier.padding(end = 12.dp).size(20.dp))
                        Text(presets.firstOrNull { it.first == rrule }?.second ?: repeatText(w, rrule, start) ?: stringResource(R.string.calendar_no_repeat), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text("▾")
                    }
                    DropdownMenu(expanded = repeatMenu, onDismissRequest = { repeatMenu = false }) {
                        presets.forEach { (r, words) -> DropdownMenuItem(text = { Text(words) }, onClick = { rrule = r; repeatMenu = false }) }
                        DropdownMenuItem(text = { Text(stringResource(R.string.calendar_custom)) }, onClick = { repeatMenu = false; customRepeat = true })
                    }
                }
                // reminders
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    alarms.forEachIndexed { i, a ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_notifications), contentDescription = null, modifier = Modifier.padding(end = 12.dp).size(20.dp))
                            Text(alarmText(w, a, allDay, start), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = { alarms = alarms.toMutableList().also { it.removeAt(i) } }) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.calendar_remove_reminder)) }
                        }
                    }
                    var alarmMenu by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { alarmMenu = true }) { Text(stringResource(R.string.calendar_add_reminder), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        DropdownMenu(expanded = alarmMenu, onDismissRequest = { alarmMenu = false }) {
                            presetAlarms(allDay, start).forEach { a -> DropdownMenuItem(text = { Text(alarmText(w, a, allDay, start)) }, onClick = { alarmMenu = false; if (a !in alarms) alarms = alarms + a }) }
                            DropdownMenuItem(text = { Text(stringResource(R.string.calendar_custom)) }, onClick = { alarmMenu = false; customAlarm = true })
                        }
                    }
                }
                OutlinedTextField(place, { place = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.calendar_place)) }, singleLine = true)
                OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth().heightIn(min = 96.dp), label = { Text(stringResource(R.string.calendar_notes)) })
            }
        }
    }
    if (wide) Dialog(onDismissRequest = cancel, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) { // a tap beside it does not throw away what was typed
        Surface(Modifier.widthIn(max = 600.dp).fillMaxWidth(0.7f).padding(vertical = 24.dp).imePadding(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) { body() }
    } else Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) { body() }
    }

    when (picking) {
        "startDate", "endDate" -> {
            val isStart = picking == "startDate"
            val t = if (isStart) start else end
            val state = rememberDatePickerState(initialSelectedDateMillis = t.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(onDismissRequest = { picking = null }, confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        val moved = t.with(d)
                        if (isStart) setStart(moved) else end = moved
                    }
                    picking = null
                }) { Text(stringResource(R.string.common_ok), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }, dismissButton = { TextButton(onClick = { picking = null }) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) } }) { DatePicker(state) }
        }
        "startTime", "endTime" -> {
            val isStart = picking == "startTime"
            val t = if (isStart) start else end
            val state = rememberTimePickerState(t.hour, t.minute, android.text.format.DateFormat.is24HourFormat(context))
            AlertDialog(onDismissRequest = { picking = null }, confirmButton = {
                TextButton(onClick = {
                    val moved = t.withHour(state.hour).withMinute(state.minute)
                    if (isStart) setStart(moved) else end = moved
                    picking = null
                }) { Text(stringResource(R.string.common_ok), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }, dismissButton = { TextButton(onClick = { picking = null }) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) } }, text = { TimePicker(state) })
        }
    }
    if (customRepeat) CustomRepeat(rrule, start.toLocalDate(), { customRepeat = false }) { rrule = it; customRepeat = false }
    if (customAlarm) CustomAlarm(allDay, start, { customAlarm = false }) { alarms = alarms + it; customAlarm = false }
}

/** Custom…: every N days/weeks/months/years, which weekdays, ending never, on a date or after N times. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable private fun CustomRepeat(rule: String?, start: LocalDate, dismiss: () -> Unit, set: (String) -> Unit) {
    val p = rule.orEmpty().split(';').filter { '=' in it }.associate { it.substringBefore('=').uppercase() to it.substringAfter('=') }
    var every by remember { mutableStateOf(p["INTERVAL"] ?: "1") }
    var unit by remember { mutableStateOf(p["FREQ"] ?: "WEEKLY") }
    var days by remember { mutableStateOf(p["BYDAY"]?.split(',')?.filter { it.length == 2 }?.toSet() ?: setOf(start.dayOfWeek.name.take(2))) }
    var ends by remember { mutableStateOf(when { p["COUNT"] != null -> "count"; p["UNTIL"] != null -> "until"; else -> "never" }) }
    var count by remember { mutableStateOf(p["COUNT"] ?: "10") }
    var until by remember { mutableStateOf(p["UNTIL"]?.take(8)?.let { runCatching { LocalDate.parse(it, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() } ?: start.plusMonths(3)) }
    var pickUntil by remember { mutableStateOf(false) }
    val locale = LocalContext.current.uiLocale()
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.calendar_repeats_title)) }, confirmButton = {
        TextButton(onClick = {
            val n = every.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val parts = mutableListOf("FREQ=$unit")
            if (n > 1) parts.add("INTERVAL=$n")
            if (unit == "WEEKLY" && days.isNotEmpty()) parts.add("BYDAY=" + DayOfWeek.entries.map { it.name.take(2) }.filter { it in days }.joinToString(","))
            // a rule Tetra did not make (BYMONTHDAY, BYSETPOS…) keeps its other parts
            if (p["FREQ"] == unit) p.filterKeys { it !in setOf("FREQ", "INTERVAL", "BYDAY", "COUNT", "UNTIL") }.forEach { (k, v) -> parts.add("$k=$v") }
            if (unit != "WEEKLY" && p["BYDAY"] != null && p["FREQ"] == unit) parts.add("BYDAY=${p["BYDAY"]}")
            when (ends) { "count" -> parts.add("COUNT=${count.toIntOrNull()?.coerceAtLeast(1) ?: 1}"); "until" -> parts.add("UNTIL=" + until.format(DateTimeFormatter.BASIC_ISO_DATE)) }
            set(parts.joinToString(";"))
        }) { Text(stringResource(R.string.common_ok), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) } }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.calendar_every))
                OutlinedTextField(every, { every = it.filter(Char::isDigit).take(3) }, Modifier.width(72.dp), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                var menu by remember { mutableStateOf(false) }
                val names = mapOf("DAILY" to stringResource(R.string.calendar_unit_days), "WEEKLY" to stringResource(R.string.calendar_unit_weeks), "MONTHLY" to stringResource(R.string.calendar_unit_months), "YEARLY" to stringResource(R.string.calendar_unit_years))
                Box {
                    TextButton(onClick = { menu = true }) { Text((names[unit] ?: unit) + " ▾", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) { names.forEach { (k, v) -> DropdownMenuItem(text = { Text(v) }, onClick = { unit = k; menu = false }) } }
                }
            }
            if (unit == "WEEKLY") FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.entries.forEach { d ->
                    val code = d.name.take(2)
                    val on = code in days
                    Box(Modifier.size(36.dp).clip(CircleShape).background(if (on) CalendarPink else Color.Transparent)
                        .border(1.dp, CalendarPink, CircleShape).clickable { days = if (on) days - code else days + code }, contentAlignment = Alignment.Center) {
                        Text(d.getDisplayName(TextStyle.NARROW, locale), color = if (on) Color.White else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            Text(stringResource(R.string.calendar_ends), style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(ends == "never", { ends = "never" }); Text(stringResource(R.string.calendar_never)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(ends == "until", { ends = "until" }); Text(stringResource(R.string.calendar_ends_on) + " ")
                TextButton(onClick = { ends = "until"; pickUntil = true }) { Text(until.format(DateTimeFormatter.ofPattern("d MMM yyyy", locale)), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(ends == "count", { ends = "count" }); Text(stringResource(R.string.calendar_ends_after) + " ")
                OutlinedTextField(count, { count = it.filter(Char::isDigit).take(4); ends = "count" }, Modifier.width(80.dp), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text(" " + stringResource(R.string.calendar_times))
            }
        }
    })
    if (pickUntil) {
        val state = rememberDatePickerState(initialSelectedDateMillis = until.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { pickUntil = false }, confirmButton = {
            TextButton(onClick = { state.selectedDateMillis?.let { until = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }; pickUntil = false }) { Text(stringResource(R.string.common_ok), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }) { DatePicker(state) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
/** Custom…: N minutes, hours, days or weeks before; days and weeks at a time of day, and if wished every day until the event. */
@Composable private fun CustomAlarm(allDay: Boolean, start: ZonedDateTime, dismiss: () -> Unit, add: (CalAlarm) -> Unit) {
    val context = LocalContext.current
    var n by remember { mutableStateOf("3") }
    var unit by remember { mutableStateOf(if (allDay) "days" else "days") } // an ID; its words from [unitWords]
    var at by remember { mutableStateOf(if (allDay) LocalTime.of(9, 0) else start.toLocalTime()) }
    var daily by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    val units = if (allDay) listOf("days", "weeks") else listOf("minutes", "hours", "days", "weeks")
    val clock = unit == "days" || unit == "weeks"
    val unitWords = mapOf("minutes" to stringResource(R.string.calendar_unit_minutes), "hours" to stringResource(R.string.calendar_unit_hours), "days" to stringResource(R.string.calendar_unit_days), "weeks" to stringResource(R.string.calendar_unit_weeks))
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.calendar_reminder)) }, confirmButton = {
        TextButton(onClick = {
            val k = n.toLongOrNull()?.coerceAtLeast(0) ?: 0
            add(when (unit) {
                "minutes" -> CalAlarm(0, -k * 60)
                "hours" -> CalAlarm(0, -k * 3600)
                else -> daysBeforeAt(start, if (unit == "weeks") k * 7 else k, at, daily)
            })
        }) { Text(stringResource(R.string.common_add), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) } }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(n, { n = it.filter(Char::isDigit).take(3) }, Modifier.width(72.dp), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                var menu by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { menu = true }) { Text("${unitWords[unit]} ▾", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) { units.forEach { u -> DropdownMenuItem(text = { Text(unitWords.getValue(u)) }, onClick = { unit = u; menu = false }) } }
                }
                Text(stringResource(R.string.calendar_before))
            }
            if (clock) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.calendar_at) + " ")
                    TextButton(onClick = { pickTime = true }) { Text(timeText(context, start.with(at)), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { daily = !daily }) {
                    Checkbox(daily, { daily = it })
                    Text(stringResource(R.string.calendar_every_day_after), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    })
    if (pickTime) {
        val state = rememberTimePickerState(at.hour, at.minute, android.text.format.DateFormat.is24HourFormat(context))
        AlertDialog(onDismissRequest = { pickTime = false }, confirmButton = {
            TextButton(onClick = { at = LocalTime.of(state.hour, state.minute); pickTime = false }) { Text(stringResource(R.string.common_ok), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }, text = { TimePicker(state) })
    }
}

/** Which days a change or deletion of a repeating event is for. */
@Composable internal fun ScopeDialog(deleting: Boolean, dismiss: () -> Unit, pick: (Scope) -> Unit) {
    var scope by remember { mutableStateOf(Scope.One) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(if (deleting) R.string.calendar_delete_repeating else R.string.calendar_change_repeating)) }, confirmButton = {
        TextButton(onClick = { pick(scope) }) { Text(stringResource(R.string.common_ok), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) } }, text = {
        Column { Scope.entries.forEach { s -> Row(Modifier.fillMaxWidth().clickable { scope = s }, verticalAlignment = Alignment.CenterVertically) { RadioButton(scope == s, { scope = s }); Text(stringResource(s.label)) } } }
    })
}

/** A calendar made or changed: its name and its colour from the palette. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun CalendarDialog(old: CalendarInfo?, taken: Set<String>, dismiss: () -> Unit, delete: () -> Unit = {}, export: () -> Unit = {}, save: (String, Int) -> Unit) {
    var name by remember { mutableStateOf(old?.name ?: "") }
    var color by remember { mutableStateOf(old?.color ?: CalendarFiles.PALETTE[taken.size % CalendarFiles.PALETTE.size]) }
    val clash = name.trim().lowercase() in taken.map { it.lowercase() } && name.trim() != old?.name
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(if (old == null) R.string.calendar_new_calendar else R.string.app_calendar)) }, confirmButton = {
        TextButton(enabled = name.isNotBlank() && !clash, onClick = { save(name.trim(), color) }) { Text(stringResource(if (old == null) R.string.calendar_create else R.string.common_save), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }, dismissButton = {
        Row {
            if (old != null) TextButton(onClick = delete) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (old != null) TextButton(onClick = export) { Text(stringResource(R.string.common_export), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.common_name)) }, singleLine = true, isError = clash,
                supportingText = if (clash) ({ Text(stringResource(R.string.calendar_name_taken)) }) else null)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CalendarFiles.PALETTE.forEach { c ->
                    Box(Modifier.size(36.dp).clip(CircleShape).background(Color(c)).then(if (c == color) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier).clickable { color = c })
                }
            }
        }
    })
}

/** What + and Home's Calendar tiles start (the user, 18 Oct: "New : Shedule : burthday : reminder"). */
// [label] stays the English for Home's tiles (MainActivity) until they read [text]
internal enum class NewKind(val label: String, val icon: Int, @androidx.annotation.StringRes val text: Int) {
    Event("New", R.drawable.ic_add, R.string.common_new), Schedule("Schedule", R.drawable.ic_schedule, R.string.calendar_schedule),
    Birthday("Birthday", R.drawable.ic_cake, R.string.calendar_birthday), Reminder("Reminder", R.drawable.ic_notifications, R.string.calendar_reminder)
}

/** A date to pick: its words on a button, Android's date picker behind it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DateButton(d: LocalDate, pattern: String = "EEE, d MMM yyyy", set: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(d.format(DateTimeFormatter.ofPattern(pattern, LocalContext.current.uiLocale())), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { open = false }, confirmButton = {
            TextButton(onClick = { state.selectedDateMillis?.let { set(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }; open = false }) { Text(stringResource(R.string.common_ok), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }, dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) } }) { DatePicker(state) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun TimeButton(t: LocalTime, set: (LocalTime) -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(timeText(context, t.atDate(LocalDate.now()).atZone(ZoneId.systemDefault())), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    if (open) {
        val state = rememberTimePickerState(t.hour, t.minute, android.text.format.DateFormat.is24HourFormat(context))
        AlertDialog(onDismissRequest = { open = false }, confirmButton = {
            TextButton(onClick = { set(LocalTime.of(state.hour, state.minute)); open = false }) { Text(stringResource(R.string.common_ok), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }, dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) } }, text = { TimePicker(state) })
    }
}

/**
 * A birthday: a name and a date, the year if known. Saved in the Birthdays calendar as an all-day event every year; a
 * known year is the first DTSTART and X-TETRA-BIRTH-YEAR, so the age can show.
 */
@Composable internal fun BirthdayDialog(dismiss: () -> Unit, save: (name: String, date: LocalDate, yearKnown: Boolean, asContact: Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var yearKnown by remember { mutableStateOf(false) }
    var asContact by remember { mutableStateOf(true) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.calendar_birthday)) }, confirmButton = {
        TextButton(enabled = name.isNotBlank(), onClick = { save(name.trim(), date, yearKnown, asContact) }) { Text(stringResource(R.string.common_save), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) } }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.calendar_whose_birthday)) }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.calendar_date), Modifier.width(72.dp), maxLines = 1, overflow = TextOverflow.Ellipsis); DateButton(date, if (yearKnown) "d MMMM yyyy" else "d MMMM") { date = it } }
            Row(Modifier.clickable { yearKnown = !yearKnown }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(yearKnown, { yearKnown = it }); Text(stringResource(R.string.calendar_year_known))
            }
            Row(Modifier.clickable { asContact = !asContact }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(asContact, { asContact = it }); Text(stringResource(R.string.calendar_save_as_contact))
            }
            Text(stringResource(R.string.calendar_birthday_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    })
}

/** A school year's dates (the user, 18 Oct); the high school's end is a guess until the user says. */
internal class ScheduleTemplate(@androidx.annotation.StringRes val label: Int, val startMonth: Int, val startDay: Int, val endMonth: Int, val endDay: Int)
internal val SCHEDULE_TEMPLATES = listOf(ScheduleTemplate(R.string.calendar_elementary_year, 9, 1, 6, 26), ScheduleTemplate(R.string.calendar_high_school_year, 9, 1, 6, 15))

/** The school year that a day falls in, from September: 18 Oct 2026 → 1 Sep 2026 – June 2027. */
internal fun templateDates(t: ScheduleTemplate, today: LocalDate): Pair<LocalDate, LocalDate> {
    val y = if (today.monthValue >= 8) today.year else today.year - 1
    return LocalDate.of(y, t.startMonth, t.startDay) to LocalDate.of(y + 1, t.endMonth, t.endDay)
}

/** A weekly schedule as one repeating event: its first day is the first chosen weekday from [from]; until [to] inclusive. */
internal fun scheduleEvent(uid: String, title: String, place: String, weekdays: Set<java.time.DayOfWeek>, at: LocalTime, until: LocalTime,
                           from: LocalDate, to: LocalDate, skip: List<LocalDate>, zone: ZoneId = ZoneId.systemDefault()): CalEvent {
    val first = generateSequence(from) { it.plusDays(1) }.take(7).first { it.dayOfWeek in weekdays }
    val start = first.atTime(at).atZone(zone)
    val end = first.atTime(if (until.isAfter(at)) until else at.plusHours(1)).atZone(zone)
    val untilUtc = to.atTime(23, 59, 59).atZone(zone).withZoneSameInstant(ZoneOffset.UTC)
    val byDay = java.time.DayOfWeek.entries.filter { it in weekdays }.joinToString(",") { it.name.take(2) }
    return CalEvent(uid, title, place, "", false, start, end, rrule = "FREQ=WEEKLY;BYDAY=$byDay;UNTIL=" + ICal.timeValue(untilUtc, false).first,
        exdates = skip.filter { it.dayOfWeek in weekdays && !it.isBefore(first) && !it.isAfter(to) }.distinct().sorted().map { it.atTime(at).atZone(zone) })
}

/**
 * A schedule (a school timetable, a weekly class): one or more weekdays, a time, a start and an end; templates for a
 * school year; the Holidays calendar's days skipped if wished.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ScheduleDialog(calendars: List<CalendarInfo>, calendar: CalendarInfo?, holidays: Boolean, dismiss: () -> Unit,
                                         save: (title: String, place: String, days: Set<java.time.DayOfWeek>, at: LocalTime, until: LocalTime, from: LocalDate, to: LocalDate, skipHolidays: Boolean, CalendarInfo?) -> Unit) {
    val today = LocalDate.now()
    var title by remember { mutableStateOf("") }
    var place by remember { mutableStateOf("") }
    var days by remember { mutableStateOf(setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY, java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.THURSDAY, java.time.DayOfWeek.FRIDAY)) }
    var at by remember { mutableStateOf(LocalTime.of(8, 15)) }
    var until by remember { mutableStateOf(LocalTime.of(13, 30)) }
    var from by remember { mutableStateOf(today) }
    var to by remember { mutableStateOf(today.plusMonths(3)) }
    var skip by remember { mutableStateOf(holidays) }
    var cal by remember { mutableStateOf(calendar ?: calendars.firstOrNull()) }
    var template by remember { mutableStateOf<ScheduleTemplate?>(null) }
    val locale = LocalContext.current.uiLocale()
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.calendar_schedule)) }, confirmButton = {
        TextButton(enabled = title.isNotBlank() && days.isNotEmpty() && !to.isBefore(from), onClick = { save(title.trim(), place.trim(), days, at, until, from, to, skip, cal) }) { Text(stringResource(R.string.common_save), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) } }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.calendar_schedule_what)) }, singleLine = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SCHEDULE_TEMPLATES.forEach { t ->
                    val on = template == t
                    Box(Modifier.clip(CircleShape).background(if (on) CalendarPink else Color.Transparent).border(1.dp, CalendarPink, CircleShape)
                        .clickable { template = t; val (a, b) = templateDates(t, today); from = a; to = b; days = days.ifEmpty { setOf(java.time.DayOfWeek.MONDAY) } }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(stringResource(t.label), color = if (on) Color.White else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                java.time.DayOfWeek.entries.forEach { d ->
                    val on = d in days
                    Box(Modifier.size(36.dp).clip(CircleShape).background(if (on) CalendarPink else Color.Transparent).border(1.dp, CalendarPink, CircleShape)
                        .clickable { days = if (on) days - d else days + d }, contentAlignment = Alignment.Center) {
                        Text(d.getDisplayName(TextStyle.NARROW, locale), color = if (on) Color.White else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.calendar_from), Modifier.width(56.dp), maxLines = 1, overflow = TextOverflow.Ellipsis); TimeButton(at) { at = it }; Text(stringResource(R.string.calendar_to)); TimeButton(until) { until = it } }
            Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.calendar_starts), Modifier.width(56.dp), maxLines = 1, overflow = TextOverflow.Ellipsis); DateButton(from) { from = it } }
            Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.calendar_ends), Modifier.width(56.dp), maxLines = 1, overflow = TextOverflow.Ellipsis); DateButton(to) { to = it } }
            if (template == SCHEDULE_TEMPLATES[1]) Text(stringResource(R.string.calendar_high_school_guess), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (holidays) Row(Modifier.clickable { skip = !skip }, verticalAlignment = Alignment.CenterVertically) { Checkbox(skip, { skip = it }); Text(stringResource(R.string.calendar_skip_holidays)) }
            OutlinedTextField(place, { place = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.calendar_place)) }, singleLine = true)
            var menu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { menu = true }) { Box(Modifier.padding(end = 8.dp).size(12.dp).clip(CircleShape).background(Color(cal?.color ?: CalendarFiles.PALETTE[0]))); Text((cal?.name ?: stringResource(R.string.calendar_personal_new)) + " ▾", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) { calendars.forEach { c -> DropdownMenuItem(text = { Text(c.name) }, onClick = { cal = c; menu = false }) } }
            }
        }
    })
}
