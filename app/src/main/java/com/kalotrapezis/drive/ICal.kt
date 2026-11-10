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
