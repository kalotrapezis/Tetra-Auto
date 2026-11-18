package com.kalotrapezis.drive

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.TimeZone
import java.util.UUID
import org.dmfs.rfc5545.DateTime as RecurTime
import org.dmfs.rfc5545.recur.RecurrenceRule

/*
 * Tetra's Calendar (Plan.md § Calendar). The calendars are files, so Claude or any person can change them:
 *   Tetra/.System/Calendar/Shared/<calendar>/color         one line, #RRGGBB (vdir's own convention, as khal and vdirsyncer)
 *   Tetra/.System/Calendar/Shared/<calendar>/<event>.ics   one iCalendar (RFC 5545) file per event, its changed days inside it
 * Files sync carries the folder as it carries everything else; nothing here talks to sync.
 */

/** One content line, `NAME;PARAMS:VALUE`, kept exactly as read: an edit only replaces the lines it changes. */
class ICalProp(val name: String, val params: String, val value: String) {
    /** A parameter's value, quotes taken off; params is ";KEY=value;KEY2=\"v\"" as written. */
    fun param(key: String): String? = ICal.splitOutsideQuotes(params, ';').firstNotNullOfOrNull { p ->
        val eq = p.indexOf('=')
        if (eq > 0 && p.substring(0, eq).equals(key, true)) p.substring(eq + 1).trim('"') else null
    }
    val text get() = ICal.unescape(value)
    override fun toString() = "$name$params:$value"
}

class ICalComp(val name: String, val items: MutableList<Any> = mutableListOf()) {
    fun props(n: String) = items.filterIsInstance<ICalProp>().filter { it.name == n }
    fun prop(n: String) = props(n).firstOrNull()
    fun comps(n: String) = items.filterIsInstance<ICalComp>().filter { it.name == n }
    fun remove(n: String) { items.removeAll { it is ICalProp && it.name == n } }
    /** Replaces the first [n] where it stands (the rest go), or adds it before the first inner component. */
    fun set(n: String, value: String?, params: String = "") {
        val at = items.indexOfFirst { it is ICalProp && it.name == n }
        remove(n)
        if (value == null) return
        val prop = ICalProp(n, params, value)
        if (at >= 0) items.add(minOf(at, items.size), prop)
        else items.add(items.indexOfFirst { it is ICalComp }.let { if (it < 0) items.size else it }, prop)
    }
}

object ICal {
    fun parse(text: String): List<ICalComp> {
        val top = mutableListOf<ICalComp>()
        val stack = ArrayDeque<ICalComp>()
        for (line in text.replace("\r\n", "\n").replace("\r", "\n").replace(Regex("\n[ \t]"), "").split('\n')) {
            if (line.isBlank()) continue
            val colon = indexOutsideQuotes(line, ':')
            if (colon < 0) continue // not a content line; a broken file still shows what it can
            val head = line.substring(0, colon)
            val semi = head.indexOf(';').let { if (it < 0) head.length else it }
            val name = head.substring(0, semi).uppercase()
            val value = line.substring(colon + 1)
            when (name) {
                "BEGIN" -> ICalComp(value.trim().uppercase()).also { c -> stack.lastOrNull()?.items?.add(c) ?: top.add(c); stack.addLast(c) }
                "END" -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.lastOrNull()?.items?.add(ICalProp(name, head.substring(semi), value))
            }
        }
        return top
    }

    /** RFC 5545's form: CRLF, lines folded at 75 octets without splitting a letter. */
    fun write(comps: List<ICalComp>): String = buildString {
        fun line(s: String) {
            var bytes = 0
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                val n = String(Character.toChars(cp)).toByteArray().size
                if (bytes + n > 75) { append("\r\n "); bytes = 1 }
                appendCodePoint(cp); bytes += n; i += Character.charCount(cp)
            }
            append("\r\n")
        }
        fun comp(c: ICalComp) {
            line("BEGIN:${c.name}")
            c.items.forEach { if (it is ICalComp) comp(it) else line(it.toString()) }
            line("END:${c.name}")
        }
        comps.forEach(::comp)
    }

    fun escape(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n").replace("\n", "\\n")
    fun unescape(s: String) = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) { val n = s[i + 1]; append(if (n == 'n' || n == 'N') '\n' else n); i += 2 }
            else { append(c); i++ }
        }
    }

    fun indexOutsideQuotes(s: String, ch: Char): Int {
        var quoted = false
        for (i in s.indices) { if (s[i] == '"') quoted = !quoted else if (s[i] == ch && !quoted) return i }
        return -1
    }

    fun splitOutsideQuotes(s: String, ch: Char): List<String> {
        val out = mutableListOf<String>()
        var rest = s
        while (true) {
            val i = indexOutsideQuotes(rest, ch)
            if (i < 0) { if (rest.isNotEmpty()) out.add(rest); return out }
            if (i > 0) out.add(rest.substring(0, i))
            rest = rest.substring(i + 1)
        }
    }

    /** An IANA name as written, Mozilla's "/mozilla.org/…/Europe/Athens", or Outlook's Windows name; else this device's. */
    fun zone(tzid: String?): ZoneId {
        if (tzid.isNullOrBlank()) return ZoneId.systemDefault()
        runCatching { return ZoneId.of(tzid) }
        val parts = tzid.trim('/').split('/')
        for (k in parts.indices) runCatching { return ZoneId.of(parts.drop(k).joinToString("/")) }
        runCatching { android.icu.util.TimeZone.getIDForWindowsID(tzid, "001")?.let { return ZoneId.of(it) } }
        return ZoneId.systemDefault()
    }

    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /** A DATE or DATE-TIME property: the time in its own zone (UTC for "Z", the device's when floating), and whether it is a date. */
    fun time(p: ICalProp, deviceZone: ZoneId = ZoneId.systemDefault()): Pair<ZonedDateTime, Boolean>? = runCatching {
        val v = p.value.trim().substringBefore(',')
        if (p.param("VALUE").equals("DATE", true) || v.length == 8) LocalDate.parse(v.take(8), DATE).atStartOfDay(deviceZone) to true
        else if (v.endsWith("Z", true)) LocalDateTime.parse(v.dropLast(1), DATE_TIME).atZone(ZoneOffset.UTC) to false
        else LocalDateTime.parse(v.take(15), DATE_TIME).atZone(p.param("TZID")?.let(::zone) ?: deviceZone) to false
    }.getOrNull()

    /** The value and params to write [t] as DTSTART/DTEND/EXDATE/RECURRENCE-ID. */
    fun timeValue(t: ZonedDateTime, date: Boolean): Pair<String, String> = when {
        date -> DATE.format(t) to ";VALUE=DATE"
        t.zone == ZoneOffset.UTC -> DATE_TIME.format(t) + "Z" to ""
        else -> DATE_TIME.format(t) to ";TZID=${t.zone.id}"
    }

    /** RFC 5545's duration, e.g. -P2DT15H, as nominal days and seconds (days follow the clock across a DST change). */
    fun duration(s: String): Pair<Long, Long>? {
        val m = Regex("([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?").matchEntire(s.trim().uppercase()) ?: return null
        val sign = if (m.groupValues[1] == "-") -1 else 1
        fun g(i: Int) = m.groupValues[i].toLongOrNull() ?: 0
        return sign * (g(2) * 7 + g(3)) to sign * (g(4) * 3600 + g(5) * 60 + g(6))
    }

    fun durationValue(days: Long, seconds: Long): String {
        val total = days * 86400 + seconds
        val a = Math.abs(total)
        val d = a / 86400; val h = a % 86400 / 3600; val m = a % 3600 / 60; val sec = a % 60
        val time = (if (h > 0) "${h}H" else "") + (if (m > 0) "${m}M" else "") + (if (sec > 0) "${sec}S" else "")
        return (if (total < 0) "-" else "") + "P" + (if (d > 0) "${d}D" else "") + when {
            time.isNotEmpty() -> "T$time"
            d == 0L -> "T0S"
            else -> ""
        }
    }
}

/**
 * A reminder (VALARM): [days] and [seconds] from the start (or the end), or at a set time; [repeat] more after it,
 * [every] seconds apart — RFC 5545's REPEAT/DURATION, which is how "3 days before at 9:00, each day" is written.
 * All day, the hours are the clock's: "on the day at 9:00" (PT9H) is 09:00 on the day the clocks change too.
 */
data class CalAlarm(val days: Long, val seconds: Long, val fromEnd: Boolean = false, val at: Instant? = null, val repeat: Int = 0, val every: Long = 0) {
    fun times(start: ZonedDateTime, end: ZonedDateTime, allDay: Boolean = false): List<ZonedDateTime> {
        val first = at?.atZone(start.zone) ?: if (fromEnd) end.plusDays(days).plusSeconds(seconds)
            else if (allDay) start.toLocalDateTime().plusDays(days).plusSeconds(seconds).atZone(start.zone)
            else start.plusDays(days).plusSeconds(seconds)
        return (0..repeat.coerceIn(0, 100)).map { first.plusSeconds(every * it) }
    }
}

/** One VEVENT: the event itself or, with [recurrenceId], one changed day of a repeating one. Times in their own zone. */
data class CalEvent(
    val uid: String,
    val title: String,
    val place: String,
    val notes: String,
    val allDay: Boolean,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val rrule: String? = null,
    val exdates: List<ZonedDateTime> = emptyList(),
    val recurrenceId: ZonedDateTime? = null,
    val cancelled: Boolean = false,
    val alarms: List<CalAlarm> = emptyList(),
    val modified: Instant? = null,
    val sequence: Int = 0,
)

/**
 * An event file: the event, its changed days, and the parsed file. The calendar's list keeps only the events (thousands
 * of them, CalendarIndex): the whole file is read again the first time something asks for it (export, a birthday's
 * contact and year); writing always reads the file afresh.
 */
class CalFile(val file: File, parsed: List<ICalComp>?, val master: CalEvent?, val overrides: List<CalEvent>) {
    val comps: List<ICalComp> by lazy { parsed ?: runCatching { ICal.parse(file.readText()) }.getOrDefault(emptyList()) }
    val uid get() = master?.uid ?: overrides.firstOrNull()?.uid ?: file.nameWithoutExtension
    val modified: Instant get() = (listOfNotNull(master) + overrides).mapNotNull { it.modified }.maxOrNull() ?: Instant.ofEpochMilli(file.lastModified())
    val sequence: Int get() = (listOfNotNull(master) + overrides).maxOfOrNull { it.sequence } ?: 0
    /** Copies of this event in the same calendar (sync's "x (2).ics" after a change on two devices); 1 when alone. */
    var versions = 1
}

data class CalendarInfo(val name: String, val dir: File, val color: Int)

/** One time an event happens, in the range asked for. */
data class Occurrence(val calendar: CalendarInfo, val file: CalFile, val event: CalEvent, val start: ZonedDateTime, val end: ZonedDateTime) {
    val allDay get() = event.allDay
}

object CalendarFiles {
    const val FOLDER = "Calendar"

    /** Google Calendar's colours, with Tetra's pink first: the picker's palette and new calendars' defaults. */
    val PALETTE = listOf(0xFFE91E63, 0xFFD50000, 0xFFE67C73, 0xFFF4511E, 0xFFF6BF26, 0xFF33B679, 0xFF0B8043,
        0xFF039BE5, 0xFF3F51B5, 0xFF7986CB, 0xFF8E24AA, 0xFF616161).map { it.toInt() }

    fun root(tetra: File) = File(tetra, TetraSystem.onDisk(FOLDER))

    fun calendars(root: File): List<CalendarInfo> = (root.listFiles() ?: emptyArray())
        .filter { it.isDirectory && !it.name.startsWith(".") }
        .sortedBy { it.name.lowercase() }
        .mapIndexed { i, dir ->
            val name = File(dir, "displayname").takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null } ?: dir.name
            CalendarInfo(name, dir, color(dir) ?: PALETTE[i % PALETTE.size])
        }

    fun color(dir: File): Int? = File(dir, "color").takeIf { it.isFile }?.readText()?.trim()?.removePrefix("#")
        ?.take(6)?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }

    fun colorText(color: Int) = "#%06X".format(color and 0xFFFFFF)

    fun newCalendar(root: File, name: String, color: Int): CalendarInfo {
        val clean = name.trim().replace(Regex("[/\\\\:*?\"<>|]"), "-").trimStart('.').ifEmpty { "Calendar" }
        val dir = File(root, clean)
        require(!dir.exists()) { "A calendar named $clean already exists." }
        require(dir.mkdirs()) { "Could not create $clean." }
        setColor(dir, color)
        return CalendarInfo(clean, dir, color)
    }

    fun setColor(dir: File, color: Int) = File(dir, "color").writeText(colorText(color) + "\n")

    private val cache = HashMap<String, Triple<Long, Long, CalFile>>()

    /**
     * Every event file of a calendar; a file is read again only when its size or time changed, one look at each (a calendar
     * of thousands of events), one reader at a time (the reminders and the screen read the same files at start), and
     * what is kept follows the folder (Tetra left open for months holds no deleted event).
     */
    fun files(cal: CalendarInfo): List<CalFile> = synchronized(cache) {
        CalendarIndex.load(cal.dir, cache)
        val all = (cal.dir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".ics", true) && !it.name.startsWith(".") }
            .mapNotNull { f -> runCatching { java.nio.file.Files.readAttributes(f.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java) }.getOrNull()?.takeIf { it.isRegularFile }?.let { f to it } }
        var fresh = false
        val read = all.map { (f, a) ->
            val size = a.size(); val time = a.lastModifiedTime().toMillis()
            cache[f.path]?.takeIf { it.first == size && it.second == time }?.third
                ?: read(f).let { CalFile(f, null, it.master, it.overrides) }.also { cache[f.path] = Triple(size, time, it); fresh = true }
        }
        val here = all.mapTo(HashSet()) { it.first.path }
        if (cache.keys.removeAll { File(it).parentFile?.path == cal.dir.path && it !in here }) fresh = true
        if (fresh) CalendarIndex.save(cal.dir, cache)
        // The same event twice (a conflict in sync): the higher SEQUENCE, then the newer, is shown and says so (RFC 5545's
        // own order, the user 23 Oct); the other stays on disk.
        read.groupBy { it.uid }.values.map { same -> same.maxWith(compareBy<CalFile>({ it.sequence }, { it.modified })).also { it.versions = same.size } }
    }

    fun read(f: File): CalFile = parse(f, runCatching { f.readText() }.getOrDefault(""))

    fun parse(f: File, text: String, deviceZone: ZoneId = ZoneId.systemDefault()): CalFile {
        val comps = ICal.parse(text)
        val events = comps.flatMap { if (it.name == "VEVENT") listOf(it) else it.comps("VEVENT") }.mapNotNull { event(it, deviceZone) }
        return CalFile(f, comps, events.firstOrNull { it.recurrenceId == null }, events.filter { it.recurrenceId != null })
    }

    fun event(c: ICalComp, deviceZone: ZoneId = ZoneId.systemDefault()): CalEvent? {
        val (start, allDay) = c.prop("DTSTART")?.let { ICal.time(it, deviceZone) } ?: return null
        val end = c.prop("DTEND")?.let { ICal.time(it, deviceZone)?.first }
            ?: c.prop("DURATION")?.let { ICal.duration(it.value) }?.let { (d, s) -> start.plusDays(d).plusSeconds(s) }
            ?: if (allDay) start.plusDays(1) else start
        return CalEvent(
            uid = c.prop("UID")?.value?.trim().orEmpty(),
            title = c.prop("SUMMARY")?.text.orEmpty(),
            place = c.prop("LOCATION")?.text.orEmpty(),
            notes = c.prop("DESCRIPTION")?.text.orEmpty(),
            allDay = allDay,
            start = start,
            end = if (end.isAfter(start)) end else if (allDay) start.plusDays(1) else start,
            rrule = c.prop("RRULE")?.value?.trim(),
            exdates = c.props("EXDATE").flatMap { p -> p.value.split(',').mapNotNull { v -> ICal.time(ICalProp(p.name, p.params, v), deviceZone)?.first } },
            recurrenceId = c.prop("RECURRENCE-ID")?.let { ICal.time(it, deviceZone)?.first },
            cancelled = c.prop("STATUS")?.value.equals("CANCELLED", true),
            alarms = c.comps("VALARM").mapNotNull(::alarm),
            modified = (c.prop("LAST-MODIFIED") ?: c.prop("DTSTAMP"))?.let { ICal.time(it, deviceZone)?.first?.toInstant() },
            sequence = c.prop("SEQUENCE")?.value?.trim()?.toIntOrNull() ?: 0,
        )
    }

    private fun alarm(c: ICalComp): CalAlarm? {
        val trigger = c.prop("TRIGGER") ?: return null
        val repeat = c.prop("REPEAT")?.value?.trim()?.toIntOrNull() ?: 0
        val every = c.prop("DURATION")?.let { ICal.duration(it.value) }?.let { (d, s) -> d * 86400 + s } ?: 0
        if (trigger.param("VALUE").equals("DATE-TIME", true)) {
            val at = ICal.time(trigger)?.first?.toInstant() ?: return null
            return CalAlarm(0, 0, at = at, repeat = repeat, every = every)
        }
        val (d, s) = ICal.duration(trigger.value) ?: return null
        return CalAlarm(d, s, fromEnd = trigger.param("RELATED").equals("END", true), repeat = repeat, every = every)
    }

    /** Every time the calendars' events happen between [from] and [to] (an event overlapping the edge counts), by start. */
    fun occurrences(cals: List<CalendarInfo>, from: Instant, to: Instant): List<Occurrence> =
        cals.flatMap { cal -> files(cal).flatMap { occurrences(cal, it, from, to) } }.sortedBy { it.start.toInstant() }

    fun occurrences(cal: CalendarInfo, f: CalFile, from: Instant, to: Instant): List<Occurrence> {
        val out = mutableListOf<Occurrence>()
        fun add(e: CalEvent, s: ZonedDateTime, end: ZonedDateTime) {
            if (!e.cancelled && s.toInstant() < to && (end.toInstant() > from || s.toInstant() >= from)) out.add(Occurrence(cal, f, e, s, end))
        }
        val m = f.master
        val moved = f.overrides.mapNotNull { it.recurrenceId?.toInstant() }.toSet()
        if (m != null) {
            if (m.rrule == null) add(m, m.start, m.end)
            else {
                val length = java.time.Duration.between(m.start, m.end)
                val skip = m.exdates.map { it.toInstant() }.toSet() + moved
                instants(m, from.minus(length), to).forEach { s -> if (s.toInstant() !in skip) add(m, s, s.plus(length)) }
            }
        }
        f.overrides.forEach { add(it, it.start, it.end) }
        return out.sortedBy { it.start.toInstant() }
    }

    /** The starts of a repeating event from [from] on, until [to], in the event's own clock (09:00 stays 09:00 after DST). */
    fun instants(e: CalEvent, from: Instant, to: Instant): List<ZonedDateTime> {
        val rule = runCatching { RecurrenceRule(e.rrule, RecurrenceRule.RfcMode.RFC5545_LAX) }.getOrNull() ?: return listOf(e.start)
        val s = e.start
        val first = if (e.allDay) RecurTime(s.year, s.monthValue - 1, s.dayOfMonth)
            else RecurTime(TimeZone.getTimeZone(s.zone), s.year, s.monthValue - 1, s.dayOfMonth, s.hour, s.minute, s.second)
        val it = rule.iterator(first)
        if (from.isAfter(s.toInstant())) it.fastForward(from.toEpochMilli() - 86_400_000L * 2) // a day's margin for floating dates
        val out = mutableListOf<ZonedDateTime>()
        var guard = 0
        while (it.hasNext() && guard++ < 20_000) {
            val t = it.nextDateTime()
            val z = if (e.allDay) LocalDate.of(t.year, t.month + 1, t.dayOfMonth).atStartOfDay(s.zone)
                else LocalDateTime.of(t.year, t.month + 1, t.dayOfMonth, t.hours, t.minutes, t.seconds).atZone(s.zone)
            if (!z.toInstant().isBefore(to)) break
            if (!z.toInstant().isBefore(from)) out.add(z)
        }
        return out
    }

    // ——— Writing ———

    /**
     * The file name for a new event: its title and eight letters of its UID's hash; never renamed later (a rename is a
     * move in sync). The hash, not the UID's first letters: Google's UIDs of one series share long beginnings, and two
     * such events of the same title once got one name (found importing the user's own calendar, 18 Oct).
     */
    fun fileName(title: String, uid: String): String {
        val slug = title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(40).trimEnd('-').ifEmpty { "event" }
        val hash = java.security.MessageDigest.getInstance("SHA-1").digest(uid.toByteArray()).take(4).joinToString("") { "%02x".format(it) }
        return "$slug-$hash.ics"
    }

    /** A new event's file in [dir]: its name, or "name (2)" if another event has it — a file is never written over another's. */
    fun newFile(dir: File, title: String, uid: String): File {
        val name = fileName(title, uid)
        var f = File(dir, name)
        var n = 2
        while (f.exists()) f = File(dir, name.removeSuffix(".ics") + " (${n++}).ics")
        return f
    }

    fun newUid() = UUID.randomUUID().toString()

    /**
     * Writes [e] as the event of [file] (a new file when it does not exist): what the editor knows is replaced, every
     * other line (attendees, Google's and Outlook's own, changed days) kept as it was.
     */
    fun save(file: File, e: CalEvent, now: Instant = Instant.now()): CalFile {
        val comps = if (file.isFile) ICal.parse(file.readText()) else emptyList()
        val cal = comps.firstOrNull { it.name == "VCALENDAR" } ?: ICalComp("VCALENDAR", mutableListOf(
            ICalProp("VERSION", "", "2.0"), ICalProp("PRODID", "", "-//Tetra//Calendar//EN")))
        val vevent = cal.comps("VEVENT").firstOrNull { it.prop("RECURRENCE-ID") == null }
            ?: ICalComp("VEVENT").also { cal.items.add(cal.items.indexOfFirst { it is ICalComp && it.name == "VEVENT" }.let { i -> if (i < 0) cal.items.size else i }, it) }
        val was = vevent.prop("DTSTART")?.let { ICal.time(it) }
        fill(vevent, e, now, isNew = was == null)
        if (was != null) ICal.time(vevent.prop("DTSTART")!!)?.let { shiftRepeat(cal, vevent, was, it) }
        val all = if (comps.isEmpty()) listOf(cal) else comps
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.part")
        tmp.writeText(ICal.write(all))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        file.setLastModified(System.currentTimeMillis()) // its time is now: the phone's storage keeps a renamed-over file's first date, and sync's hash cache goes by size and date
        return read(file)
    }

    /**
     * A repeat's start moved ([was] → [now], e.g. 11:50 → 11:45): its EXDATEs and its changed days' RECURRENCE-IDs
     * move by as much, or they match no day and a cancelled day comes back (the user's timetable, 18 Oct). The
     * desktop's shiftRepeat the same.
     */
    private fun shiftRepeat(cal: ICalComp, m: ICalComp, was: Pair<ZonedDateTime, Boolean>, now: Pair<ZonedDateTime, Boolean>) {
        val (w, n) = was.first to now.first
        val days = java.time.temporal.ChronoUnit.DAYS.between(w.toLocalDate(), n.toLocalDate())
        val secs = (n.toLocalTime().toSecondOfDay() - w.toLocalTime().toSecondOfDay()).toLong()
        val mixed = was.second != now.second
        if (days == 0L && secs == 0L && !mixed) return
        fun redo(p: ICalProp): ICalProp {
            val out = p.value.split(',').mapNotNull { x -> ICal.time(ICalProp(p.name, p.params, x))?.first?.let { t ->
                // all day ↔ a time: the day kept, at the new start's time
                val moved = if (mixed) t.toLocalDate().plusDays(days).atTime(n.toLocalTime()).atZone(n.zone)
                    else t.toLocalDateTime().plusDays(days).plusSeconds(secs).atZone(t.zone)
                ICal.timeValue(moved, now.second)
            } }
            if (out.isEmpty()) return p
            return ICalProp(p.name, p.params.replace(Regex(";(TZID|VALUE)=(\"[^\"]*\"|[^;]*)", RegexOption.IGNORE_CASE), "") + out[0].second, out.joinToString(",") { it.first })
        }
        m.items.replaceAll { if (it is ICalProp && it.name == "EXDATE") redo(it) else it }
        cal.comps("VEVENT").filter { it !== m }.forEach { v -> v.items.replaceAll { if (it is ICalProp && it.name == "RECURRENCE-ID") redo(it) else it } }
    }

    /** Reads [file], lets [change] work on its components, writes it back whole (a ".part" first: sync skips dot files). */
    private fun rewrite(file: File, change: (ICalComp) -> Unit): CalFile {
        val comps = ICal.parse(file.readText())
        val cal = comps.firstOrNull { it.name == "VCALENDAR" } ?: error("${file.name} is not an iCalendar file.")
        change(cal)
        val tmp = File(file.parentFile, ".${file.name}.part")
        tmp.writeText(ICal.write(comps))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        file.setLastModified(System.currentTimeMillis()) // its time is now: the phone's storage keeps a renamed-over file's first date, and sync's hash cache goes by size and date
        return read(file)
    }

    private fun sameTime(p: ICalProp?, t: ZonedDateTime) = p != null && ICal.time(p, t.zone)?.first?.toInstant() == t.toInstant()

    /** One property of the event (not a changed day) set, as written: e.g. a birthday's X-TETRA-BIRTH-YEAR. */
    fun setProp(file: File, name: String, value: String): CalFile = rewrite(file) { cal ->
        cal.comps("VEVENT").firstOrNull { it.prop("RECURRENCE-ID") == null }?.set(name, value)
    }

    /** One day of a repeating event changed: a VEVENT with its RECURRENCE-ID in the same file (made or replaced). */
    fun saveDay(f: CalFile, day: ZonedDateTime, e: CalEvent, now: Instant = Instant.now()): CalFile = rewrite(f.file) { cal ->
        val allDay = f.master?.allDay ?: e.allDay
        val old = cal.comps("VEVENT").firstOrNull { sameTime(it.prop("RECURRENCE-ID"), day) }
        val v = old ?: ICalComp("VEVENT").also { cal.items.add(it) }
        fill(v, e.copy(uid = f.uid, rrule = null, exdates = emptyList()), now, isNew = old == null)
        ICal.timeValue(day, allDay).let { (value, params) -> v.set("RECURRENCE-ID", value, params) }
    }

    /** One day of a repeating event deleted: an EXDATE, and that day's own changes gone. */
    fun skipDay(f: CalFile, day: ZonedDateTime, now: Instant = Instant.now()): CalFile = rewrite(f.file) { cal ->
        cal.items.removeAll { it is ICalComp && it.name == "VEVENT" && sameTime(it.prop("RECURRENCE-ID"), day) }
        val m = cal.comps("VEVENT").firstOrNull { it.prop("RECURRENCE-ID") == null } ?: return@rewrite
        val allDay = f.master?.allDay ?: false
        ICal.timeValue(day, allDay).let { (value, params) -> m.items.add(m.items.indexOfFirst { it is ICalComp }.let { if (it < 0) m.items.size else it }, ICalProp("EXDATE", params, value)) }
        touch(m, now)
    }

    /** A repeating event ended before [day] (its UNTIL; a COUNT gives way): "this and following" deleted, or split off. */
    fun endBefore(f: CalFile, day: ZonedDateTime, now: Instant = Instant.now()): CalFile = rewrite(f.file) { cal ->
        val m = cal.comps("VEVENT").firstOrNull { it.prop("RECURRENCE-ID") == null } ?: return@rewrite
        val allDay = f.master?.allDay ?: false
        val until = if (allDay) ICal.timeValue(day.minusDays(1), true).first else ICal.timeValue(day.minusSeconds(1).withZoneSameInstant(ZoneOffset.UTC), false).first
        m.prop("RRULE")?.let { r -> m.set("RRULE", (r.value.split(';').filterNot { it.startsWith("UNTIL=", true) || it.startsWith("COUNT=", true) } + "UNTIL=$until").joinToString(";")) }
        cal.items.removeAll { c -> c is ICalComp && c.name == "VEVENT" && c.prop("RECURRENCE-ID")?.let { ICal.time(it, day.zone)?.first?.toInstant()?.let { t -> t >= day.toInstant() } } == true }
        touch(m, now)
    }

    /**
     * "This and following" changed from [day]: [f]'s repeat ends before it, and [e] is written as a new event in [file]
     * with [f]'s cancelled and changed days from [day] on, moved by as much as [day] moved, or they come back. The
     * desktop's split the same.
     */
    fun split(f: CalFile, day: ZonedDateTime, file: File, e: CalEvent, now: Instant = Instant.now()): CalFile {
        fun after(p: ICalProp?) = p != null && ICal.time(p, day.zone)?.first?.toInstant()?.let { it >= day.toInstant() } == true
        val cal = ICal.parse(f.file.readText()).firstOrNull { it.name == "VCALENDAR" }
        val exdates = cal?.comps("VEVENT")?.firstOrNull { it.prop("RECURRENCE-ID") == null }?.items.orEmpty()
            .filterIsInstance<ICalProp>().filter { it.name == "EXDATE" }
            .map { p -> ICalProp(p.name, p.params, p.value.split(',').filter { after(ICalProp(p.name, p.params, it)) }.joinToString(",")) }
            .filter { it.value.isNotEmpty() }
        val days = cal?.comps("VEVENT").orEmpty().filter { after(it.prop("RECURRENCE-ID")) }
        endBefore(f, day, now)
        save(file, e, now)
        return rewrite(file) { c ->
            val v = c.comps("VEVENT").first()
            v.items.addAll(v.items.indexOfFirst { it is ICalComp }.let { if (it < 0) v.items.size else it }, exdates)
            days.forEach { it.set("UID", e.uid); c.items.add(it) }
            ICal.time(v.prop("DTSTART")!!)?.let { shiftRepeat(c, v, day to (f.master?.allDay ?: false), it) }
        }
    }

    private fun touch(v: ICalComp, now: Instant) {
        val stamp = ICal.timeValue(now.atZone(ZoneOffset.UTC), false).first
        v.set("DTSTAMP", stamp)
        v.set("LAST-MODIFIED", stamp)
        v.set("SEQUENCE", ((v.prop("SEQUENCE")?.value?.trim()?.toIntOrNull() ?: 0) + 1).toString())
    }

    /** An event file to Tetra's Trash (sync never deletes; a move into Trash crosses as a move). Returns where it went. */
    fun trash(calendarRoot: File, file: File): String =
        TetraSystem.tetraOf(calendarRoot).let { DriveRules.moveToTrash(it, DriveRules.relative(it, file)) }

    /**
     * The Calendar's part of Tetra's Trash (asked 18 Oct): its event files and when each went in, newest first. A move
     * into Trash/ changes the ctime only, as sync's 30-day sweep reads it (SyncClient, purgatory.js).
     */
    fun trashed(calendarRoot: File): List<Pair<CalFile, Long>> = (File(TetraSystem.tetraOf(calendarRoot), TetraSystem.onDisk("Trash")).listFiles() ?: emptyArray())
        .filter { it.isFile && it.name.endsWith(".ics", true) && !it.name.startsWith(".") }
        .map { read(it) to runCatching { android.system.Os.lstat(it.path).st_ctime * 1000 }.getOrDefault(it.lastModified()) }
        .sortedByDescending { it.second }

    /** The other files of the same event in its calendar: sync's "(2)" copies, for "Keep this one". */
    fun copies(cal: CalendarInfo, f: CalFile): List<File> = (cal.dir.listFiles() ?: emptyArray())
        .filter { it.isFile && it.name.endsWith(".ics", true) && it != f.file && !it.name.startsWith(".") && read(it).uid == f.uid }

    /** An event to another calendar: its file moved to that folder (a name there already: "name (2)"). */
    fun move(file: File, to: CalendarInfo): File {
        var target = File(to.dir, file.name)
        var n = 2
        while (target.exists()) target = File(to.dir, file.nameWithoutExtension + " (${n++}).ics")
        require(file.renameTo(target)) { "Could not move ${file.name}." }
        return target
    }

    private fun fill(v: ICalComp, e: CalEvent, now: Instant, isNew: Boolean) {
        val stamp = ICal.timeValue(now.atZone(ZoneOffset.UTC), false).first
        v.set("UID", e.uid)
        v.set("DTSTAMP", stamp)
        v.set("LAST-MODIFIED", stamp)
        v.set("SEQUENCE", if (isNew) "0" else ((v.prop("SEQUENCE")?.value?.trim()?.toIntOrNull() ?: 0) + 1).toString())
        v.set("SUMMARY", ICal.escape(e.title))
        ICal.timeValue(e.start, e.allDay).let { (value, params) -> v.set("DTSTART", value, params) }
        v.remove("DURATION")
        ICal.timeValue(e.end, e.allDay).let { (value, params) -> v.set("DTEND", value, params) }
        v.set("LOCATION", e.place.ifEmpty { null }?.let(ICal::escape))
        v.set("DESCRIPTION", e.notes.ifEmpty { null }?.let(ICal::escape))
        v.set("RRULE", e.rrule)
        v.remove("EXDATE")
        e.exdates.forEach { x -> ICal.timeValue(x, e.allDay).let { (value, params) -> v.items.add(v.items.indexOfFirst { it is ICalComp }.let { if (it < 0) v.items.size else it }, ICalProp("EXDATE", params, value)) } }
        v.items.removeAll { it is ICalComp && it.name == "VALARM" }
        e.alarms.forEach { v.items.add(alarmComp(it)) }
    }

    fun alarmComp(a: CalAlarm) = ICalComp("VALARM").apply {
        items.add(ICalProp("ACTION", "", "DISPLAY"))
        items.add(ICalProp("DESCRIPTION", "", "Reminder"))
        if (a.at != null) items.add(ICalProp("TRIGGER", ";VALUE=DATE-TIME", ICal.timeValue(a.at.atZone(ZoneOffset.UTC), false).first))
        else items.add(ICalProp("TRIGGER", if (a.fromEnd) ";RELATED=END" else "", ICal.durationValue(a.days, a.seconds)))
        if (a.repeat > 0) {
            items.add(ICalProp("REPEAT", "", a.repeat.toString()))
            items.add(ICalProp("DURATION", "", ICal.durationValue(0, a.every)))
        }
    }
}

/**
 * Import and export (Plan.md § Calendar › Import and export): any .ics — Google's, Outlook's, Apple's, Thunderbird's —
 * and Google Takeout's zip of one .ics per calendar, split into one file per event; out again as one .ics per calendar.
 */
object CalendarTransfer {
    /** A calendar arriving: its name, its colour if the file says, its events by UID (a repeat's changed days with it), the zones they use. */
    class Incoming(val name: String, val color: Int?, val events: Map<String, List<ICalComp>>, val zones: Map<String, ICalComp>)

    fun read(text: String, fallbackName: String): Incoming {
        val cals = ICal.parse(text).filter { it.name == "VCALENDAR" }
        val events = LinkedHashMap<String, MutableList<ICalComp>>()
        val zones = LinkedHashMap<String, ICalComp>()
        cals.forEach { cal ->
            cal.comps("VTIMEZONE").forEach { z -> z.prop("TZID")?.value?.let { zones[it] = z } }
            cal.comps("VEVENT").forEach { v ->
                // an event without a UID gets one from what it is, so importing the same file again finds it again
                val uid = v.prop("UID")?.value?.trim()?.ifEmpty { null }
                    ?: ("import-" + Integer.toHexString((v.prop("DTSTART")?.value + "|" + v.prop("SUMMARY")?.value).hashCode())).also { v.set("UID", it) }
                events.getOrPut(uid) { mutableListOf() }.add(v)
            }
        }
        val name = cals.firstNotNullOfOrNull { it.prop("X-WR-CALNAME")?.text?.trim()?.ifEmpty { null } } ?: fallbackName
        val color = cals.firstNotNullOfOrNull { it.prop("X-APPLE-CALENDAR-COLOR")?.value ?: it.prop("COLOR")?.value }
            ?.trim()?.removePrefix("#")?.take(6)?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
        return Incoming(name, color, events, zones)
    }

    /** Takeout's zip (or any zip): each .ics in it a calendar. */
    fun readZip(bytes: ByteArray): List<Incoming> {
        val out = mutableListOf<Incoming>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory && e.name.endsWith(".ics", true))
                    out.add(read(zip.readBytes().toString(Charsets.UTF_8), e.name.substringAfterLast('/').removeSuffix(".ics").removeSuffix(".ICS")))
            }
        }
        return out
    }

    private fun zonesUsed(comps: List<ICalComp>): Set<String> {
        val ids = mutableSetOf<String>()
        fun walk(c: ICalComp) { c.items.forEach { if (it is ICalComp) walk(it) else (it as ICalProp).param("TZID")?.let(ids::add) } }
        comps.forEach(::walk)
        return ids
    }

    /**
     * Writes [inc]'s events into [into], one file each; an event already there (the same UID) is replaced, so importing
     * the same file twice changes nothing. Returns (new, updated).
     */
    fun write(into: CalendarInfo, inc: Incoming): Pair<Int, Int> {
        val existing = (into.dir.listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(".ics", true) && !it.name.startsWith(".") }
            .associateBy { CalendarFiles.read(it).uid }
        var added = 0; var updated = 0
        inc.events.forEach { (uid, vevents) ->
            val cal = ICalComp("VCALENDAR", mutableListOf(ICalProp("VERSION", "", "2.0"), ICalProp("PRODID", "", "-//Tetra//Calendar//EN")))
            zonesUsed(vevents).forEach { id -> inc.zones[id]?.let(cal.items::add) }
            cal.items.addAll(vevents.sortedBy { it.prop("RECURRENCE-ID") != null })
            val title = vevents.firstNotNullOfOrNull { it.prop("SUMMARY")?.text } ?: ""
            val file = existing[uid] ?: CalendarFiles.newFile(into.dir, title, uid)
            if (file.exists()) updated++ else added++
            file.writeText(ICal.write(listOf(cal)))
        }
        return added to updated
    }

    /** A calendar as one .ics for other apps: its name and colour, each zone it uses described (VTIMEZONE), every event. */
    fun export(cal: CalendarInfo): String {
        val out = ICalComp("VCALENDAR", mutableListOf(ICalProp("VERSION", "", "2.0"), ICalProp("PRODID", "", "-//Tetra//Calendar//EN"),
            ICalProp("X-WR-CALNAME", "", ICal.escape(cal.name)), ICalProp("X-APPLE-CALENDAR-COLOR", "", CalendarFiles.colorText(cal.color))))
        val events = mutableListOf<ICalComp>()
        val zones = LinkedHashMap<String, ICalComp>()
        CalendarFiles.files(cal).forEach { f ->
            f.comps.forEach { c -> c.comps("VTIMEZONE").forEach { z -> z.prop("TZID")?.value?.let { zones.putIfAbsent(it, z) } }; events.addAll(c.comps("VEVENT")) }
        }
        zonesUsed(events).forEach { id -> zones.getOrPut(id) { vtimezone(id) ?: return@forEach } }
        out.items.addAll(zones.values)
        out.items.addAll(events)
        return ICal.write(listOf(out))
    }

    /** Every calendar, a zip of one .ics each (Takeout's own shape, so it comes back in the same way). */
    fun exportAll(cals: List<CalendarInfo>): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { zip ->
            cals.forEach { c -> zip.putNextEntry(java.util.zip.ZipEntry("Calendar/${c.name}.ics")); zip.write(export(c).toByteArray()); zip.closeEntry() }
        }
        return bytes.toByteArray()
    }

    /** A VTIMEZONE for an IANA zone from Java's own rules: its standard and summer time as they are now (Google writes the same). */
    fun vtimezone(id: String): ICalComp? {
        val zone = runCatching { java.time.ZoneId.of(id) }.getOrNull() ?: return null
        val rules = zone.rules
        val tz = ICalComp("VTIMEZONE", mutableListOf(ICalProp("TZID", "", id)))
        fun off(o: java.time.ZoneOffset) = (if (o.totalSeconds < 0) "-" else "+") + "%02d%02d".format(Math.abs(o.totalSeconds) / 3600, Math.abs(o.totalSeconds) % 3600 / 60)
        val transitions = rules.transitionRules
        if (transitions.isEmpty()) {
            val o = rules.getOffset(Instant.now())
            tz.items.add(ICalComp("STANDARD", mutableListOf(ICalProp("TZOFFSETFROM", "", off(o)), ICalProp("TZOFFSETTO", "", off(o)), ICalProp("DTSTART", "", "19700101T000000"))))
            return tz
        }
        transitions.forEach { r ->
            val summer = r.offsetAfter.totalSeconds > r.offsetBefore.totalSeconds
            val t = r.createTransition(1970)
            val local = t.dateTimeBefore
            val day = r.dayOfWeek?.name?.take(2)
            val byDay = when {
                day == null -> null
                r.dayOfMonthIndicator < 0 || r.dayOfMonthIndicator + 6 == r.month.maxLength() -> "-1$day" // "Sunday on or after the 25th" of March is its last
                else -> "${(r.dayOfMonthIndicator - 1) / 7 + 1}$day"
            }
            val rrule = "FREQ=YEARLY;BYMONTH=${r.month.value}" + (byDay?.let { ";BYDAY=$it" } ?: ";BYMONTHDAY=${r.dayOfMonthIndicator}")
            tz.items.add(ICalComp(if (summer) "DAYLIGHT" else "STANDARD", mutableListOf(
                ICalProp("TZOFFSETFROM", "", off(r.offsetBefore)), ICalProp("TZOFFSETTO", "", off(r.offsetAfter)),
                ICalProp("DTSTART", "", local.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))), ICalProp("RRULE", "", rrule))))
        }
        return tz
    }
}

/** An event's place as something to open in the maps app: a link pasted from Google Maps, HERE WeGo and the like, or an address. */
object CalendarPlace {
    private val URL = Regex("""https?://\S+""")
    // lat,lng inside a link: Google's @lat,lng and q=lat,lng, HERE's map=lat,lng, OSM's #map=z/lat/lng
    private val COORDS = Regex("""(-?\d{1,2}\.\d{3,})(?:,|%2C|/)\s*(-?\d{1,3}\.\d{3,})""")

    fun link(place: String): String? = URL.find(place)?.value?.trimEnd('.', ',', ')', ';')

    /** The place without its link, for showing; empty when the place is only a link. */
    fun label(place: String): String = place.replace(URL, "").trim().trim(',', '-', '·').trim()

    /** A geo: URI when the place has coordinates or is plain text, so the default maps app opens it; else the link itself. */
    fun uri(place: String): String? {
        if (place.isBlank()) return null
        val link = link(place) ?: return "geo:0,0?q=" + java.net.URLEncoder.encode(place.trim(), "UTF-8").replace("+", "%20")
        val m = COORDS.find(link)
        val lat = m?.groupValues?.get(1)?.toDoubleOrNull(); val lng = m?.groupValues?.get(2)?.toDoubleOrNull()
        return if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) "geo:$lat,$lng?q=$lat,$lng" else link
    }
}

/**
 * Each calendar's events as last read (not their whole files: title, times, repeat, reminders), one file per calendar in
 * the app's cache (the user, 25 Oct: "the calendar does not change that often… keep it on disk"): after a restart the
 * events come from here, and only an event file whose size or time changed since — a change here, from sync, from
 * Claude's skill — is read again. Without [dir] (the unit tests) every start reads the files.
 */
object CalendarIndex {
    @Volatile var dir: File? = null
    private val loaded = HashSet<String>()
    // a plain line per file and per event: org.json and ZonedDateTime.parse took 1.3 s for one calendar on the tablet
    private const val HEAD = "tetra-calendar-index 3"

    private fun file(calDir: File) = dir?.let { File(it, "calendar-" + Integer.toHexString(calDir.path.hashCode()) + ".txt") }

    /** Into [cache] (path → size, time, events), once per calendar and run; times in this device's zone, or none. */
    fun load(calDir: File, cache: MutableMap<String, Triple<Long, Long, CalFile>>) {
        if (!loaded.add(calDir.path)) return
        val f = file(calDir)?.takeIf { it.isFile } ?: return
        runCatching {
            f.bufferedReader().useLines { seq ->
                val lines = seq.iterator()
                if (!lines.hasNext() || lines.next() != "$HEAD\t${ZoneId.systemDefault().id}") return
                var path: String? = null; var size = 0L; var time = 0L; val events = mutableListOf<CalEvent>()
                fun flush() { path?.let { p -> cache.putIfAbsent(p, Triple(size, time, CalFile(File(p), null, events.firstOrNull { it.recurrenceId == null }, events.filter { it.recurrenceId != null }))) }; events.clear() }
                for (line in lines) {
                    val c = line.split('\t')
                    if (c[0] == "F") { flush(); path = unescape(c[1]); size = c[2].toLong(); time = c[3].toLong() } else events += event(line)
                }
                flush()
            }
        }
    }

    fun save(calDir: File, cache: Map<String, Triple<Long, Long, CalFile>>) {
        val f = file(calDir) ?: return
        runCatching {
            val tmp = File(f.path + ".tmp")
            tmp.bufferedWriter().use { w ->
                w.write("$HEAD\t${ZoneId.systemDefault().id}\n")
                cache.forEach { (path, v) -> if (File(path).parentFile?.path == calDir.path) {
                    w.write("F\t${escape(path)}\t${v.first}\t${v.second}\n")
                    (listOfNotNull(v.third.master) + v.third.overrides).forEach { w.write(line(it)); w.write("\n") }
                } }
            }
            tmp.renameTo(f)
        }
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")
    private fun unescape(s: String): String = if ('\\' !in s) s else buildString {
        var i = 0
        while (i < s.length) { val ch = s[i++]; if (ch != '\\' || i == s.length) append(ch) else when (val n = s[i++]) { 't' -> append('\t'); 'n' -> append('\n'); 'r' -> append('\r'); else -> append(n) } }
    }
    private val zones = java.util.concurrent.ConcurrentHashMap<String, ZoneId>()
    private fun t(z: ZonedDateTime?) = z?.let { "${it.toEpochSecond()}@${it.zone.id}" }.orEmpty()
    private fun z(s: String) = s.takeIf { it.isNotEmpty() }?.let {
        val at = it.indexOf('@')
        ZonedDateTime.ofInstant(Instant.ofEpochSecond(it.substring(0, at).toLong()), zones.getOrPut(it.substring(at + 1)) { ZoneId.of(it.substring(at + 1)) })
    }

    /** One event as one line (tabs between its fields; [event] reads it back). */
    fun line(e: CalEvent): String = listOf("E", escape(e.uid), escape(e.title), escape(e.place), escape(e.notes), if (e.allDay) "1" else "0", t(e.start), t(e.end),
        e.rrule?.let(::escape) ?: "", e.exdates.joinToString(",") { t(it) }, t(e.recurrenceId), if (e.cancelled) "1" else "0",
        e.modified?.toEpochMilli()?.toString() ?: "", "${e.sequence}",
        e.alarms.joinToString(";") { "${it.days},${it.seconds},${if (it.fromEnd) 1 else 0},${it.at?.toEpochMilli() ?: ""},${it.repeat},${it.every}" }).joinToString("\t")

    fun event(line: String): CalEvent {
        val c = line.split('\t')
        return CalEvent(
            uid = unescape(c[1]), title = unescape(c[2]), place = unescape(c[3]), notes = unescape(c[4]), allDay = c[5] == "1",
            start = z(c[6])!!, end = z(c[7])!!, rrule = c[8].ifEmpty { null }?.let(::unescape),
            exdates = c[9].split(',').mapNotNull { z(it) }, recurrenceId = z(c[10]), cancelled = c[11] == "1",
            modified = c[12].ifEmpty { null }?.let { Instant.ofEpochMilli(it.toLong()) }, sequence = c[13].toInt(),
            alarms = c[14].split(';').filter { it.isNotEmpty() }.map { a -> a.split(',').let { p ->
                CalAlarm(p[0].toLong(), p[1].toLong(), p[2] == "1", p[3].ifEmpty { null }?.let { Instant.ofEpochMilli(it.toLong()) }, p[4].toInt(), p[5].toLong()) } },
        )
    }
}
