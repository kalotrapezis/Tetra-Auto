package com.kalotrapezis.drive

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/*
 * The Timer's alarms and stopwatch (Plan.md § Timer › Alarms and stopwatch), files beside the timers in Tetra/.System/Timer/Shared and
 * kept by the same rules: one file per item per device (`<id>.<device>.json`, written beside then renamed), the highest
 * `rev` among an id's files is it. A `kind` tells them from timers; their states are words the timers' readers (older
 * builds, desktop/timer.js) do not know, so those skip them rather than show them as timers.
 *
 *   {"kind":"alarm","id":"alarm-k3f9a2","time":"07:30","days":["MO","TU"],"date":null,"label":"Σχολείο","state":"on",
 *    "answered":null,"snooze":null,"rev":1,"by":"24091RPADG"}
 *   {"kind":"stopwatch","id":"stopwatch","state":"counting","started":"2026-10-29T16:00:00Z","elapsed":0,"laps":[62.4],"rev":3,"by":"Tablet"}
 */

/**
 * An alarm: [time] on the [days] of the week, or once (no days) on [date] (none: the next [time]). Each device rings it in
 * its own time zone; [answered] is the last ring stopped (Dismiss or Snooze, on any device), so a device that hears of it
 * stops ringing; [snooze] rings it once more.
 */
internal data class AlarmRecord(
    val id: String,
    val time: LocalTime,
    val days: Set<DayOfWeek> = emptySet(),
    val date: LocalDate? = null,
    val label: String = "",
    val state: String = ON,
    val answered: Instant? = null,
    val snooze: Instant? = null,
    val rev: Int = 1,
    val by: String = "",
    val file: File? = null,
) {
    val repeats get() = days.isNotEmpty()

    /** The first time it is set for after [after] by the clock (snoozes aside), or none: a single one already past. */
    fun occurrence(after: Instant, zone: ZoneId): Instant? {
        val from = after.atZone(zone).toLocalDate()
        if (!repeats && date != null) return date.atTime(time).atZone(zone).toInstant().takeIf { it > after }
        return (0L..8L).asSequence().map { from.plusDays(it) }.filter { !repeats || it.dayOfWeek in days }
            .map { it.atTime(time).atZone(zone).toInstant() }.firstOrNull { it > after }
    }

    /** The ring going on now, if one is: set for at most ten minutes ago and not answered anywhere. */
    fun ringing(now: Instant, zone: ZoneId): Instant? {
        if (state != ON) return null
        val since = now.minusMillis(TimerRecord.UNANSWERED_MS)
        return listOfNotNull(occurrence(maxOf(since, answered ?: Instant.EPOCH), zone), snooze)
            .filter { it <= now && it > since && (answered == null || it > answered) }.minOrNull()
    }

    /** When it rings next (not counting one ringing now): none when off or a single one gone by. */
    fun next(now: Instant, zone: ZoneId): Instant? {
        if (state != ON) return null
        val after = maxOf(now, answered ?: Instant.EPOCH)
        return listOfNotNull(occurrence(after, zone), snooze?.takeIf { it > after }).minOrNull()
    }

    /** Shown switched on: it will ring, or rings now. */
    fun on(now: Instant, zone: ZoneId) = ringing(now, zone) != null || next(now, zone) != null

    /** Dismiss (on the ring [at], or Skip on the next one): a single alarm is then off. */
    fun dismiss(at: Instant, by: String) = copy(answered = at, snooze = null, state = if (repeats) state else OFF, rev = rev + 1, by = by)
    fun snoozed(at: Instant, now: Instant, by: String) = copy(answered = at, snooze = now.plusMillis(SNOOZE_MS), rev = rev + 1, by = by)
    /** Switched on: a single one for the next [time] from now, nothing from before ringing. */
    fun switchOn(now: Instant, zone: ZoneId, by: String) = copy(state = ON, answered = now, snooze = null,
        date = if (repeats) null else copy(date = null).occurrence(now, zone)?.atZone(zone)?.toLocalDate(), rev = rev + 1, by = by)
    fun switchOff(by: String) = copy(state = OFF, snooze = null, rev = rev + 1, by = by)
    fun delete(by: String) = copy(state = DELETED, snooze = null, rev = rev + 1, by = by)

    fun json(): String = JSONObject().put("kind", KIND).put("id", id).put("time", "%02d:%02d".format(time.hour, time.minute))
        .put("days", JSONArray(WEEK.filter { it in days }.map { DAY_CODES[it.ordinal] })).put("date", date?.toString() ?: JSONObject.NULL)
        .put("label", label).put("state", state).put("answered", answered?.toString() ?: JSONObject.NULL)
        .put("snooze", snooze?.toString() ?: JSONObject.NULL).put("rev", rev).put("by", by).toString(1) + "\n"

    companion object {
        const val KIND = "alarm"
        const val ON = "on"
        const val OFF = "off"
        const val DELETED = "deleted"
        const val SNOOZE_MS = 10 * 60_000L
        private val ORDER = listOf(ON, OFF, DELETED) // equal rev: deleted, then off, wins
        val WEEK = DayOfWeek.entries
        /** RFC 5545's day codes, as the Calendar's RRULE: MO TU WE TH FR SA SU. */
        val DAY_CODES = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

        fun parse(text: String, file: File? = null): AlarmRecord? = runCatching {
            val o = JSONObject(text)
            if (o.optString("kind") != KIND) return null
            val state = o.optString("state", ON).lowercase().takeIf { it in ORDER } ?: return null
            fun instant(k: String) = o.optString(k, "").takeIf { it.isNotEmpty() && it != "null" }?.let { Instant.parse(it) }
            val days = o.optJSONArray("days")?.let { a -> (0 until a.length()).mapNotNull { DAY_CODES.indexOf(a.optString(it).uppercase().take(2)).takeIf { i -> i >= 0 }?.let { i -> WEEK[i] } } }.orEmpty().toSet()
            AlarmRecord(o.optString("id").ifEmpty { file?.name?.substringBefore('.') ?: return null }, LocalTime.parse(o.getString("time").trim().padStart(5, '0')),
                days, o.optString("date", "").takeIf { it.isNotEmpty() && it != "null" }?.let { LocalDate.parse(it) }, o.optString("label"), state,
                instant("answered"), instant("snooze"), o.optInt("rev", 0), o.optString("by"), file)
        }.getOrNull()

        fun winners(all: List<AlarmRecord>) = all.groupBy { it.id }.values.map { same -> same.maxWith(compareBy({ it.rev }, { ORDER.indexOf(it.state) })) }
    }
}

/**
 * The one stopwatch, shared by the devices: counting since [started] (the moment it would have started had it never been
 * held), or held at [elapsed] seconds; [laps] are the times at each Lap.
 */
internal data class StopwatchRecord(
    val state: String = RESET,
    val started: Instant? = null,
    val elapsed: Double = 0.0,
    val laps: List<Double> = emptyList(),
    val rev: Int = 0,
    val by: String = "",
    val file: File? = null,
) {
    fun elapsedMs(now: Instant): Long = when (state) {
        COUNTING -> started?.let { now.toEpochMilli() - it.toEpochMilli() }?.coerceAtLeast(0) ?: 0
        HELD -> (elapsed * 1000).toLong()
        else -> 0
    }
    fun start(now: Instant, by: String) = if (state == COUNTING) this else copy(state = COUNTING, started = now.minusMillis(elapsedMs(now)), elapsed = 0.0, rev = rev + 1, by = by)
    fun hold(now: Instant, by: String) = if (state != COUNTING) this else copy(state = HELD, elapsed = elapsedMs(now) / 1000.0, started = null, rev = rev + 1, by = by)
    fun lap(now: Instant, by: String) = if (state != COUNTING) this else copy(laps = laps + elapsedMs(now) / 1000.0, rev = rev + 1, by = by)
    fun reset(by: String) = if (state == RESET) this else copy(state = RESET, started = null, elapsed = 0.0, laps = emptyList(), rev = rev + 1, by = by)

    fun json(): String = JSONObject().put("kind", KIND).put("id", ID).put("state", state).put("started", started?.toString() ?: JSONObject.NULL)
        .put("elapsed", elapsed).put("laps", JSONArray(laps)).put("rev", rev).put("by", by).toString(1) + "\n"

    companion object {
        const val KIND = "stopwatch"
        const val ID = "stopwatch"
        const val COUNTING = "counting"
        const val HELD = "held"
        const val RESET = "reset"
        private val ORDER = listOf(COUNTING, HELD, RESET) // equal rev: reset, then held, wins

        fun parse(text: String, file: File? = null): StopwatchRecord? = runCatching {
            val o = JSONObject(text)
            if (o.optString("kind") != KIND) return null
            val state = o.optString("state", RESET).lowercase().takeIf { it in ORDER } ?: return null
            StopwatchRecord(state, o.optString("started", "").takeIf { it.isNotEmpty() && it != "null" }?.let { Instant.parse(it) },
                o.optDouble("elapsed", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                o.optJSONArray("laps")?.let { a -> (0 until a.length()).map { a.optDouble(it) } }.orEmpty(), o.optInt("rev", 0), o.optString("by"), file)
        }.getOrNull()

        fun winner(all: List<StopwatchRecord>) = all.maxWithOrNull(compareBy({ it.rev }, { ORDER.indexOf(it.state) })) ?: StopwatchRecord()
    }
}

/** The alarms' and stopwatch's files in the Timer's folder ([TimerFiles] keeps the timers'). */
internal object TimerAlarmFiles {
    private fun texts(dir: File) = dir.listFiles().orEmpty()
        .filter { it.isFile && !it.name.startsWith(".") && it.name.endsWith(".json", true) }
        .mapNotNull { f -> runCatching { f to f.readText() }.getOrNull() }

    /** Every alarm not deleted, by the time of day. */
    fun alarms(dir: File): List<AlarmRecord> = AlarmRecord.winners(texts(dir).mapNotNull { (f, t) -> AlarmRecord.parse(t, f) })
        .filter { it.state != AlarmRecord.DELETED }.sortedWith(compareBy({ it.time }, { it.id }))

    fun stopwatch(dir: File): StopwatchRecord = StopwatchRecord.winner(texts(dir).mapNotNull { (f, t) -> StopwatchRecord.parse(t, f) })

    fun write(dir: File, r: AlarmRecord): AlarmRecord = r.copy(file = TimerFiles.save(dir, r.id, r.by, r.json()))
    fun write(dir: File, r: StopwatchRecord): StopwatchRecord = r.copy(file = TimerFiles.save(dir, StopwatchRecord.ID, r.by, r.json()))

    /** A new alarm, in the file of a deleted one when there is one (as a new timer takes an old one's). */
    fun add(dir: File, time: LocalTime, days: Set<DayOfWeek>, label: String, by: String, now: Instant, zone: ZoneId, date: LocalDate? = null): AlarmRecord {
        val gone = AlarmRecord.winners(texts(dir).mapNotNull { (f, t) -> AlarmRecord.parse(t, f) }).filter { it.state == AlarmRecord.DELETED }.minByOrNull { it.id }
        val fresh = AlarmRecord(gone?.id ?: "alarm-" + TimerFiles.newId(), time, days, null, label, AlarmRecord.ON, now, null, (gone?.rev ?: 0) + 1, by)
        val r = if (days.isNotEmpty()) fresh else fresh.copy(date = date ?: fresh.occurrence(now, zone)?.atZone(zone)?.toLocalDate())
        return write(dir, r)
    }

    /** The alarm that rings next, and when (a ringing one first). */
    fun upcoming(dir: File, now: Instant, zone: ZoneId): Pair<AlarmRecord, Instant>? =
        alarms(dir).mapNotNull { a -> a.next(now, zone)?.let { a to it } }.minByOrNull { it.second }

    fun ringing(dir: File, now: Instant, zone: ZoneId): List<Pair<AlarmRecord, Instant>> =
        alarms(dir).mapNotNull { a -> a.ringing(now, zone)?.let { a to it } }
}
