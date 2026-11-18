package com.kalotrapezis.drive

import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/*
 * Birthdays and other dates into the Calendar (Plan.md § Contacts › Birthdays). The contact is the truth; the event follows
 * it: one yearly all-day .ics per date in the chosen calendar (Γενέθλια by default), carrying X-TETRA-CONTACT:<UID> (and
 * X-TETRA-KIND for a date other than the birthday, X-TETRA-BIRTH-YEAR when the year is known, as the Calendar's own
 * Birthday action). A changed date rewrites the event, a removed one or a contact in the Trash takes it to the Trash.
 * Events Contacts made have UIDs "tetra-contact-…"; an event already there and linked to a contact keeps its own title.
 */
object ContactsBirthdays {
    const val NAME = "Γενέθλια"
    private const val OURS = "tetra-contact-"
    const val BIRTHDAY = "Birthday"

    /** The calendar birthdays go into: the chosen one (its folder), else Γενέθλια or Birthdays, else a new Γενέθλια. */
    fun calendar(calRoot: File, chosen: String?): CalendarInfo {
        val cals = CalendarFiles.calendars(calRoot)
        return cals.firstOrNull { it.dir.name == chosen } ?: existing(cals) ?: CalendarFiles.newCalendar(calRoot, NAME, 0xFF8E24AA.toInt())
    }
    fun existing(cals: List<CalendarInfo>) = cals.firstOrNull { it.name.equals(NAME, true) } ?: cals.firstOrNull { it.name.equals("Birthdays", true) }

    fun master(f: CalFile): ICalComp? = f.comps.flatMap { if (it.name == "VEVENT") listOf(it) else it.comps("VEVENT") }.firstOrNull { it.prop("RECURRENCE-ID") == null }
    fun contactOf(f: CalFile) = master(f)?.prop("X-TETRA-CONTACT")?.value?.trim()?.ifEmpty { null }
    private fun kind(f: CalFile) = master(f)?.prop("X-TETRA-KIND")?.let { ICal.unescape(it.value).trim() } ?: BIRTHDAY

    /** Every linked event, by contact UID: one pass over the calendars. */
    fun linked(calRoot: File): Map<String, List<CalFile>> = CalendarFiles.calendars(calRoot).flatMap { CalendarFiles.files(it) }
        .mapNotNull { f -> contactOf(f)?.let { it to f } }.groupBy({ it.first }, { it.second })

    /** The events a contact should have: (kind, date, title). */
    fun wanted(i: ContactInfo): List<Triple<String, VDate, String>> =
        listOfNotNull(i.birthday?.let { Triple(BIRTHDAY, it, i.name) }) +
            i.dates.mapNotNull { f -> VDate.parse(f.value)?.let { Triple(f.label.ifBlank { "Date" }, it, "${i.name} · ${f.label.ifBlank { "Date" }}") } }.distinctBy { it.first }

    /** The reminders Γενέθλια's events have now: 9:00 on the day and 9:00 the day before (the plan's question 4). */
    fun alarms(start: java.time.ZonedDateTime) = listOf(daysBeforeAt(start, 0, LocalTime.of(9, 0), false), daysBeforeAt(start, 1, LocalTime.of(9, 0), false))

    /**
     * [i]'s events made to match it ([have]: its linked events now). New ones go into the chosen calendar; answers how many
     * files changed. A contact with no dates (or [gone]) has its events moved to the Trash.
     */
    fun sync(calRoot: File, i: ContactInfo, chosen: String?, have: List<CalFile>, gone: Boolean = false, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now()): Int {
        val want = if (gone) emptyList() else wanted(i)
        var n = 0
        for ((kind, d, title) in want) {
            val start = d.inYear(d.year ?: today.year).atStartOfDay(zone)
            val f = have.firstOrNull { kind(it).equals(kind, true) }
            val year = if (kind == BIRTHDAY) d.year?.toString() else null
            if (f == null) {
                val cal = calendar(calRoot, chosen)
                val uid = OURS + i.uid + if (kind == BIRTHDAY) "" else "-" + kind.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-")
                var made = CalendarFiles.save(CalendarFiles.newFile(cal.dir, title, uid), CalEvent(uid, title, "", "", true, start, start.plusDays(1), rrule = "FREQ=YEARLY", alarms = alarms(start)))
                made = CalendarFiles.setProp(made.file, "X-TETRA-CONTACT", i.uid)
                if (kind != BIRTHDAY) made = CalendarFiles.setProp(made.file, "X-TETRA-KIND", ICal.escape(kind))
                if (year != null) CalendarFiles.setProp(made.file, "X-TETRA-BIRTH-YEAR", year)
                n++
                continue
            }
            val m = f.master ?: continue
            val sameDay = m.start.monthValue == d.month && m.start.dayOfMonth == d.day
            val ours = m.uid.startsWith(OURS)
            if (!sameDay || (ours && m.title != title)) {
                CalendarFiles.save(f.file, m.copy(title = if (ours) title else m.title, allDay = true, start = if (sameDay) m.start else start,
                    end = if (sameDay) m.end else start.plusDays(1), rrule = if (sameDay) m.rrule else "FREQ=YEARLY", exdates = if (sameDay) m.exdates else emptyList()))
                n++
            }
            if (kind == BIRTHDAY && master(f)?.prop("X-TETRA-BIRTH-YEAR")?.value?.trim() != year) {
                if (year != null) CalendarFiles.setProp(f.file, "X-TETRA-BIRTH-YEAR", year) else removeProp(f.file, "X-TETRA-BIRTH-YEAR")
                n++
            }
        }
        have.filter { f -> want.none { it.first.equals(kind(f), true) } }.forEach { CalendarFiles.trash(calRoot, it.file); n++ }
        return n
    }

    private fun removeProp(file: File, name: String) {
        val comps = ICal.parse(file.readText())
        comps.forEach { c -> (if (c.name == "VEVENT") listOf(c) else c.comps("VEVENT")).forEach { it.remove(name) } }
        file.writeText(ICal.write(comps))
    }

    /** Every contact in [all] made to match, in one pass (after an import, a link). */
    fun syncAll(calRoot: File, all: List<ContactInfo>, chosen: String?): Int {
        val have = linked(calRoot)
        return all.sumOf { i -> runCatching { sync(calRoot, i, chosen, have[i.uid].orEmpty()) }.onFailure { WriterLog.e("Contacts: ${i.name}'s dates", it) }.getOrDefault(0) }
    }

    /** One suggested pair: an event already in the birthdays calendar, and the contact it seems to be. */
    data class Link(val event: CalFile, val title: String, val contact: ContactInfo, val sameDate: Boolean)

    private val STOP = setOf("γενεθλια", "birthday", "birthdays", "bday", "της", "του", "των", "τον", "την", "χρονια", "πολλα", "happy", "s")

    private fun words(s: String) = ContactsFiles.plain(s).split(Regex("[^\\p{L}]+")).filter { it.length >= 3 && it !in STOP }

    /** Two forms of one name: "βασιλη"/"βασιλης", "σοφιας"/"σοφια", "νικου"/"νικος". */
    private fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        val n = minOf(a.length, b.length)
        val common = a.zip(b).takeWhile { it.first == it.second }.size
        return if (n >= 5) common >= maxOf(3, n - 2) else n >= 3 && common >= n - 1 && common >= 3
    }

    /**
     * The birthdays calendar's events no contact is linked to, each with the contact its title names (Γενέθλια's 11 from
     * Google Calendar): the best match by name, one with the same date first; one contact once. Unclear ones are left out.
     */
    fun suggestions(calRoot: File, contacts: List<ContactInfo>, chosen: String?, dismissed: Set<String> = emptySet()): List<Link> {
        val cals = CalendarFiles.calendars(calRoot)
        val cal = cals.firstOrNull { it.dir.name == chosen } ?: existing(cals) ?: return emptyList()
        val taken = linked(calRoot).keys.toMutableSet()
        val out = mutableListOf<Link>()
        for (f in CalendarFiles.files(cal).filter { contactOf(it) == null && it.uid !in dismissed }) {
            val m = f.master ?: continue
            val ew = words(m.title)
            if (ew.isEmpty()) continue
            val scored = contacts.filter { it.uid !in taken }.map { c ->
                val cw = words(listOf(c.given, c.family, c.nickname, c.name).joinToString(" ")).distinct()
                val score = cw.count { w -> ew.any { similar(it, w) } }
                val same = c.birthday?.let { it.month == m.start.monthValue && it.day == m.start.dayOfMonth } == true
                Triple(c, score, same)
            }.filter { it.second > 0 && (it.first.birthday == null || it.third) }
            val best = scored.sortedWith(compareByDescending<Triple<ContactInfo, Int, Boolean>> { it.third }.thenByDescending { it.second }).firstOrNull() ?: continue
            if (scored.count { it.second == best.second && it.third == best.third } > 1) continue // two people fit as well: left to the person
            taken.add(best.first.uid)
            out.add(Link(f, m.title, best.first, best.third))
        }
        return out
    }

    /**
     * A pair linked: the event gets the contact's UID (no second event is made), and a contact with no birthday takes the
     * event's day (and its X-TETRA-BIRTH-YEAR): the birthdays move out of Google into the contacts. Answers the contact.
     */
    fun link(calRoot: File, contactsDir: File, l: Link): ContactInfo {
        CalendarFiles.setProp(l.event.file, "X-TETRA-CONTACT", l.contact.uid)
        if (l.contact.birthday != null) return l.contact
        val m = l.event.master!!
        val year = master(l.event)?.prop("X-TETRA-BIRTH-YEAR")?.value?.trim()?.toIntOrNull()
        val c = ContactsFiles.all(contactsDir).firstOrNull { it.info.uid == l.contact.uid } ?: return l.contact
        return ContactsFiles.save(contactsDir, c, c.info.copy(birthday = VDate(year, m.start.monthValue, m.start.dayOfMonth))).info
    }

    /** After a merge: [from]'s events become [to]'s, unless [to] has one of that kind already (then to the Trash). */
    fun moveLinks(calRoot: File, from: String, to: ContactInfo) {
        val have = linked(calRoot)
        val kinds = have[to.uid].orEmpty().map { kind(it).lowercase() }
        have[from].orEmpty().forEach { f -> if (kind(f).lowercase() in kinds) CalendarFiles.trash(calRoot, f.file) else CalendarFiles.setProp(f.file, "X-TETRA-CONTACT", to.uid) }
    }
}
