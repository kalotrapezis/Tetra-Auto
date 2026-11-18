package com.kalotrapezis.drive

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Data (Plan.md § Data): the mobile plan, its cycle, and what is left of it. The plan is Tetra/.System/Data/Shared/plan.json, the same
 * on the phone, the tablet and the computer (the highest `rev` wins, as a timer's), so any of them may change it.
 */
internal data class DataPlan(
    val kind: String = LIMITED, // limited · fair-use (an "unlimited" plan with a limit in its terms) · unlimited
    val size: Long = 10_000_000_000,
    val every: String = MONTH, // month (on [day]) · week (on weekday [day], 1 = Monday) · days ([days] from [from])
    val day: Int = 1,
    val days: Int = 30,
    val from: LocalDate = LocalDate.of(2026, 1, 1),
    val daily: Long = 0, // a fixed daily quota; 0 = today's share of what is left
    val rollover: Boolean = false,
    val warn: List<Int> = listOf(50, 80, 100),
    val euCounts: Boolean = true, // roaming in the EU counts against the plan, as at home
    val passPrice: Double = 0.0, // outside the EU: a day pass (price, size); 0 = none
    val passSize: Long = 0,
    val currency: String = "€",
    val offsetStart: LocalDate? = null, // "used so far" typed from the carrier's app: the difference, for that cycle only
    val offset: Long = 0,
    val rev: Int = 0,
    val by: String = "",
) {
    val limited get() = kind != UNLIMITED

    fun toJson(): String = JSONObject().apply {
        put("kind", kind); put("size", size); put("every", every); put("day", day); put("days", days); put("from", from.toString())
        put("daily", daily); put("rollover", rollover); put("warn", JSONArray(warn)); put("euCounts", euCounts)
        put("pass", JSONObject().put("price", passPrice).put("size", passSize).put("currency", currency))
        offsetStart?.let { put("carrier", JSONObject().put("cycle", it.toString()).put("offset", offset)) }
        put("rev", rev); put("by", by)
    }.toString(2)

    companion object {
        const val LIMITED = "limited"; const val FAIR_USE = "fair-use"; const val UNLIMITED = "unlimited"
        const val MONTH = "month"; const val WEEK = "week"; const val DAYS = "days"

        fun parse(text: String): DataPlan? = runCatching {
            val o = JSONObject(text)
            val pass = o.optJSONObject("pass"); val carrier = o.optJSONObject("carrier")
            DataPlan(o.optString("kind", LIMITED), o.optLong("size", 10_000_000_000), o.optString("every", MONTH), o.optInt("day", 1), o.optInt("days", 30),
                runCatching { LocalDate.parse(o.optString("from")) }.getOrDefault(LocalDate.of(2026, 1, 1)), o.optLong("daily"), o.optBoolean("rollover"),
                o.optJSONArray("warn")?.let { a -> List(a.length()) { a.getInt(it) } } ?: listOf(50, 80, 100), o.optBoolean("euCounts", true),
                pass?.optDouble("price", 0.0) ?: 0.0, pass?.optLong("size") ?: 0, pass?.optString("currency", "€") ?: "€",
                carrier?.optString("cycle")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }, carrier?.optLong("offset") ?: 0,
                o.optInt("rev"), o.optString("by"))
        }.getOrNull()
    }
}

/** A cycle of the plan: from [start] to the day before [end]. */
internal data class DataCycle(val start: LocalDate, val end: LocalDate) {
    val days get() = ChronoUnit.DAYS.between(start, end).toInt()
    fun daysLeft(today: LocalDate) = ChronoUnit.DAYS.between(today, end).toInt() // today included
    operator fun contains(d: LocalDate) = !d.isBefore(start) && d.isBefore(end)
}

internal object DataRules {
    /** The cycle [today] is in. A month's day past its end (the 31st in February) is that month's last day. */
    fun cycle(p: DataPlan, today: LocalDate): DataCycle = when (p.every) {
        DataPlan.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.of(p.day.coerceIn(1, 7)))).let { DataCycle(it, it.plusDays(7)) }
        DataPlan.DAYS -> {
            val n = p.days.coerceAtLeast(1).toLong()
            val k = Math.floorDiv(ChronoUnit.DAYS.between(p.from, today), n)
            p.from.plusDays(k * n).let { DataCycle(it, it.plusDays(n)) }
        }
        else -> {
            fun on(m: LocalDate) = m.withDayOfMonth(p.day.coerceIn(1, m.lengthOfMonth()))
            val here = on(today)
            val start = if (today.isBefore(here)) on(today.minusMonths(1)) else here
            DataCycle(start, on(start.withDayOfMonth(1).plusMonths(1)))
        }
    }

    /** The plan's size this cycle: with rollover, what was left of the one before is added. */
    fun size(p: DataPlan, previousUsed: Long?): Long = if (p.rollover && previousUsed != null) p.size + max(0, p.size - previousUsed) else p.size

    /** Used this cycle: the counted bytes, and the carrier's difference when it was typed in this cycle. */
    fun used(p: DataPlan, c: DataCycle, counted: Long) = max(0, counted + if (p.offsetStart != null && p.offsetStart in c) p.offset else 0)

    /** Today's budget: what is left over the days left, today included (or the fixed daily quota). */
    fun budget(p: DataPlan, left: Long, daysLeft: Int) = if (p.daily > 0) p.daily else max(0, left) / daysLeft.coerceAtLeast(1)

    private fun median(l: List<Long>) = l.sorted().let { if (it.isEmpty()) 0L else if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }

    /**
     * A day's likely use, from the days before [before] (the user, 25 Oct: weekends differ — no Wi-Fi at work): the median of the
     * same weekday over the last three weeks, previous cycles too, else the median of the last two weeks. Medians, so one huge
     * or empty day does not pull it; three weeks, so a change of habit (the hotspot used daily since October) shows within days.
     */
    fun forecast(history: Map<LocalDate, Long>, day: LocalDate, before: LocalDate): Long {
        val past = history.filterKeys { it.isBefore(before) && !it.isBefore(before.minusDays(21)) }
        val same = past.filterKeys { it.dayOfWeek == day.dayOfWeek }.values.toList()
        return median(if (same.size >= 2) same else past.filterKeys { !it.isBefore(before.minusDays(14)) }.values.toList())
    }

    /** What will have been used by the cycle's end: used so far, today at least its forecast, and each day left its forecast. */
    fun projection(used: Long, today: Long, history: Map<LocalDate, Long>, now: LocalDate, end: LocalDate): Long =
        used - today + max(today, forecast(history, now, now)) +
            generateSequence(now.plusDays(1)) { it.plusDays(1) }.takeWhile { it.isBefore(end) }.sumOf { forecast(history, it, now) }

    /** The warnings now due: each threshold passed and not yet given this cycle. */
    fun due(p: DataPlan, used: Long, size: Long, given: Set<Int>) = if (!p.limited || size <= 0) emptyList()
        else p.warn.sorted().filter { it !in given && used * 100 >= it * size }

    /** The chart's lines: a round 1, 2 or 5 × 10ⁿ apart, at most four over [v] — so 3 MB and 40 GB both fill it. */
    fun axisStep(v: Long): Long {
        if (v <= 4) return 1
        val raw = v / 4.0
        val p = 10.0.pow(floor(log10(raw)))
        return (listOf(1.0, 2.0, 5.0, 10.0).first { it * p >= raw } * p).toLong()
    }
    fun axisLines(v: Long) = ceil(v.toDouble() / axisStep(v)).toInt().coerceAtLeast(1)
    /** The chart's top: the last line, just over [v]. */
    fun axisTop(v: Long) = axisStep(v) * axisLines(v)

    /** Bytes as people say them (thousands, as carriers and Android count): 212 MB, 7.2 GB, 41 GB. */
    fun bytes(b: Long): String {
        val units = listOf("B", "KB", "MB", "GB", "TB", "PB")
        var v = b.toDouble(); var i = 0
        while (v >= 1000 && i < units.lastIndex) { v /= 1000; i++ }
        return if (i == 0 || v >= 10) "${Math.round(v)} ${units[i]}" else "%.1f %s".format(java.util.Locale.ROOT, v, units[i])
    }

    /** "300 GB", "1.5GB", "500 mb" → bytes; null when it is not a size. */
    fun parseSize(s: String): Long? {
        val m = Regex("""^\s*([0-9]+(?:[.,][0-9]+)?)\s*([kmgt]?)b?\s*$""", RegexOption.IGNORE_CASE).find(s) ?: return null
        val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val unit = when (m.groupValues[2].lowercase()) { "k" -> 1e3; "m" -> 1e6; "t" -> 1e12; "" -> 1e9; else -> 1e9 } // a bare number is GB
        return (n * unit).toLong()
    }

    /** What a day's mobile data counts against the plan: roaming outside the EU is the day pass's, and in the EU only if the plan says so. */
    fun counted(p: DataPlan, d: DataDay) = d.mobile - when { d.roamOutsideEu -> d.roaming; !p.euCounts -> d.roaming; else -> 0 }

    /** The plan today, from the phone's days. */
    fun status(p: DataPlan, days: List<DataDay>, today: LocalDate): DataStatus {
        val c = cycle(p, today)
        val byDay = days.associateBy { it.day }
        fun countedIn(cy: DataCycle) = generateSequence(cy.start) { it.plusDays(1) }.takeWhile { it.isBefore(cy.end) && !it.isAfter(today) }
            .map { byDay[it]?.let { d -> counted(p, d) } ?: 0L }.toList()
        val perDay = countedIn(c)
        val before = cycle(p, c.start.minusDays(1)).takeIf { b -> days.any { it.day in b } }?.let { countedIn(it).sum() }
        val size = size(p, before)
        val used = used(p, c, perDay.sum())
        val left = size - used
        val daysLeft = c.daysLeft(today)
        return DataStatus(c, size, used, left, daysLeft, budget(p, left, daysLeft), perDay.lastOrNull() ?: 0,
            projection(used, perDay.lastOrNull() ?: 0, days.associate { it.day to counted(p, it) }, today, c.end), perDay)
    }

    // EU and EEA countries (roaming as at home, by EU rules); anywhere else roaming costs extra
    private val EU = setOf("at", "be", "bg", "hr", "cy", "cz", "dk", "ee", "fi", "fr", "de", "gr", "hu", "ie", "it", "lv", "lt", "lu", "mt",
        "nl", "pl", "pt", "ro", "sk", "si", "es", "se", "is", "li", "no")
    fun inEu(country: String) = country.lowercase() in EU
}

/** The cycle's numbers under the chart: what is usual, when the limit comes, where the data goes. */
internal data class DataMetrics(val average: Long, val workdays: Long, val weekends: Long, val busiest: Pair<LocalDate, Long>?, val hotspotShare: Int,
    val wifiShare: Int, val runsOut: LocalDate?, val forecast: Long)

internal object DataInsights {
    private fun median(l: List<Long>) = l.sorted().let { if (it.isEmpty()) 0L else it[it.size / 2] }

    /**
     * [days] are the device's own (on a phone, its mobile data is what the plan counts); [phone]: the hotspot is what it shared.
     * The cycle is the plan's, or the calendar month without one.
     */
    fun metrics(p: DataPlan?, s: DataStatus?, days: List<DataDay>, today: LocalDate, phone: Boolean): DataMetrics {
        val cycle = s?.cycle ?: DataCycle(today.withDayOfMonth(1), today.withDayOfMonth(1).plusMonths(1))
        fun measure(d: DataDay) = if (phone) (if (p != null) DataRules.counted(p, d) else d.mobile) else d.mobile + d.wifi
        val inCycle = days.filter { it.day in cycle && !it.day.isAfter(today) }
        val done = inCycle.filter { it.day.isBefore(today) }
        val recent = days.filter { it.day.isBefore(today) && !it.day.isBefore(today.minusDays(21)) } // the forecast's three weeks
        val (weekend, work) = recent.partition { it.day.dayOfWeek.value >= 6 }
        val hotspot = inCycle.sumOf { if (phone) it.hotspot else it.viaHotspot }
        val mobile = inCycle.sumOf { it.mobile }; val wifi = inCycle.sumOf { it.wifi }
        val history = days.associate { it.day to measure(it) }
        val used = s?.used ?: inCycle.sumOf { measure(it) }
        var acc = used - (inCycle.lastOrNull { it.day == today }?.let { measure(it) } ?: 0) +
            maxOf(inCycle.lastOrNull { it.day == today }?.let { measure(it) } ?: 0, DataRules.forecast(history, today, today))
        var runsOut: LocalDate? = null
        val size = s?.size?.takeIf { p?.limited == true }
        if (size != null && acc > size) runsOut = today
        var d = today.plusDays(1)
        while (d.isBefore(cycle.end)) { acc += DataRules.forecast(history, d, today); if (size != null && runsOut == null && acc > size) runsOut = d; d = d.plusDays(1) }
        return DataMetrics(if (done.isEmpty()) 0 else done.sumOf { measure(it) } / done.size, median(work.map { measure(it) }), median(weekend.map { measure(it) }),
            inCycle.maxByOrNull { measure(it) }?.let { it.day to measure(it) },
            if (phone) (if (mobile > 0) (hotspot * 100 / mobile).toInt() else 0) else (if (wifi > 0) (hotspot * 100 / wifi).toInt() else 0),
            if (mobile + wifi > 0) (wifi * 100 / (mobile + wifi)).toInt() else 0, runsOut, acc)
    }

    /** Short help for the plan from the numbers — information only, nothing changed for the user. */
    fun tips(p: DataPlan?, s: DataStatus?, m: DataMetrics, apps: List<DataApp>, phone: Boolean): List<Advice> {
        val out = mutableListOf<Advice>()
        val b = DataRules::bytes
        if (p != null && s != null && p.limited) {
            if (s.used >= s.size) out += Advice(DataWords(R.string.data_tip_reached), DataWords(R.string.data_tip_reached_text, listOf(b(s.used), b(s.size), s.daysLeft)))
            else if (m.runsOut != null) out += Advice(DataWords(R.string.data_tip_runs_out, listOf(m.runsOut)), DataWords(R.string.data_tip_runs_out_text, listOf(b(s.size), s.cycle.end, b(s.budget))))
            else out += Advice(DataWords(R.string.data_tip_about, listOf(s.size.let { if (it > 0) m.forecast * 100 / it else 0 })),
                DataWords(R.string.data_tip_about_text, listOf(b(m.forecast), b(s.size), b(maxOf(0, s.size - m.forecast)), b(s.budget))))
        }
        if (phone && m.hotspotShare >= 40) out += Advice(DataWords(R.string.data_tip_hotspot, listOf(m.hotspotShare)), DataWords(R.string.data_tip_hotspot_text))
        if (m.weekends > 0 && m.workdays > 0) {
            val r = m.weekends.toDouble() / m.workdays
            if (r >= 1.3) out += Advice(DataWords(R.string.data_tip_weekends), DataWords(R.string.data_tip_weekends_text, listOf(b(m.weekends), b(m.workdays), "%.1f".format(java.util.Locale.ROOT, r))))
            else if (r <= 0.77) out += Advice(DataWords(R.string.data_tip_workdays), DataWords(R.string.data_tip_workdays_text, listOf(b(m.workdays), b(m.weekends))))
        }
        // the app (not the hotspot) using the most mobile data, and what of it ran in the background
        val mobileApps = apps.filter { it.net == "mobile" && it.app != "hotspot" }.groupBy { it.app }.map { (k, l) -> Triple(k, l.first().name, l.sumOf { it.fg } to l.sumOf { it.bg }) }
        mobileApps.maxByOrNull { it.third.first + it.third.second }?.let { (app, name, fb) ->
            val total = fb.first + fb.second
            if (total >= 300_000_000) out += when {
                app == "com.android.vending" -> Advice(DataWords(R.string.data_tip_app, listOf(name, b(total))), DataWords(R.string.data_tip_app_play))
                fb.second * 2 > total -> Advice(DataWords(R.string.data_tip_app, listOf(name, b(total))), DataWords(R.string.data_tip_app_background, listOf(b(fb.second))))
                else -> Advice(DataWords(R.string.data_tip_app, listOf(name, b(total))), DataWords(R.string.data_tip_app_open))
            }
        }
        m.busiest?.let { (day, v) -> if (m.average > 0 && v > m.average * 2) out += Advice(DataWords(R.string.data_tip_busiest, listOf(day, b(v))),
            DataWords(R.string.data_tip_busiest_text, listOf(b(m.average)))) }
        return out
    }
}

/** The plan's numbers: [perDay] is each day of the cycle so far, today last. */
internal data class DataStatus(val cycle: DataCycle, val size: Long, val used: Long, val left: Long, val daysLeft: Int, val budget: Long, val today: Long,
    val projection: Long, val perDay: List<Long>) {
    val average get() = if (perDay.isEmpty()) 0 else perDay.sum() / perDay.size
}

/** One device's day, as its files keep it. */
internal data class DataDay(val day: LocalDate, val mobile: Long, val wifi: Long, val roaming: Long, val hotspot: Long, val viaHotspot: Long, val roamOutsideEu: Boolean, val ethernet: Long = 0)

internal data class DataApp(val day: LocalDate, val app: String, val name: String, val net: String, val fg: Long, val bg: Long, val roaming: Long) { val total get() = fg + bg }

internal data class DataHour(val hour: String, val net: String, val bytes: Long) // "2026-10-25T14"

internal data class DataEvent(val at: String, val kind: String, val secs: Long = 0, val note: String = "") // a drop, a roaming country, Tetra's own sync

/** A device's month of counts: the lines of Tetra/.System/Data/Shared/usage/<yyyy-mm>.<device>.jsonl; [at] when it was counted (ms, 0 = before 25 Oct). */
internal data class DataMonth(val device: String, val days: List<DataDay>, val apps: List<DataApp>, val hours: List<DataHour>, val events: List<DataEvent>, val name: String = "",
    val at: Long = 0)

/** Tetra/.System/Data/Shared: the plan, and each device's counts in files of its own (Timer's rule: nobody writes another's file). */
internal object DataFiles {
    const val FOLDER = "Data"
    fun root() = File(TetraFolder.root(android.os.Environment.getExternalStorageDirectory()), TetraSystem.onDisk(FOLDER))
    fun device() = (Build.MODEL ?: "Android").filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "Android" }
    /** The name the user gave this device ("Xiaomi 15"), for the others' screens; the file names keep [device]. */
    fun deviceName(c: Context): String = listOf(android.provider.Settings.Global.getString(c.contentResolver, android.provider.Settings.Global.DEVICE_NAME),
        runCatching { android.provider.Settings.Secure.getString(c.contentResolver, "bluetooth_name") }.getOrNull(), Build.MODEL).firstOrNull { !it.isNullOrBlank() }.orEmpty()
    /** A device's name as it wrote it in its files, else its id. */
    fun label(all: Map<String, List<DataMonth>>, id: String) = all[id]?.firstNotNullOfOrNull { it.name.ifEmpty { null } } ?: id
    // a device's month: "2026-10.24129PN74G.jsonl", and sync's copies of one changed on both sides at once ("… (2).jsonl", "… (2) (3).jsonl")
    private val MONTH = Regex("""(\d{4}-\d{2})\.([^ ()]+)((?: \(\d+\))*)\.jsonl""")

    /** [a] is a newer version of a device's month than [b]: counted later, else further into the month (a day's counts only grow). */
    fun newer(a: DataMonth, b: DataMonth) = compareValuesBy(a, b, { it.at }, { m -> m.days.maxOfOrNull { it.day } },
        { m -> m.days.maxByOrNull { it.day }?.let { it.mobile + it.wifi + it.ethernet } }, { m -> m.hours.maxOfOrNull { it.hour } }) > 0

    /**
     * Each device's months: the newest version of each (the file itself or one of sync's copies of it), and the other versions.
     * Not always the file: on 25 Oct the computer's copy of the phone's month stayed at 10:00 while every newer one came as "(2)",
     * "(2) (3)"… (41 % on the phone, 40 there).
     */
    private fun versions(root: File): List<Pair<DataMonth, List<File>>> = File(root, "usage").listFiles().orEmpty()
        .mapNotNull { f -> MONTH.matchEntire(f.name)?.let { m -> Triple(m.groupValues[1], m.groupValues[2], f) } }
        .groupBy({ it.first to it.second }, { it.third }).mapNotNull { (key, files) ->
            files.sortedBy { it.name.length }.mapNotNull { f -> runCatching { parse(key.second, f.readText()) to f }.getOrNull() }
                .reduceOrNull { x, y -> if (newer(y.first, x.first)) y else x } // the month itself first: it stays on a tie
                ?.let { (month, file) -> month to files - file } // the newest, and the versions it beats
        }

    /** Sync's copies of the months removed, here as on the computer — except one newer than its month, until the month catches up. */
    fun dropCopies(root: File = root()): Int = versions(root).sumOf { (_, others) -> others.count { MONTH.matchEntire(it.name)!!.groupValues[3].isNotEmpty() && it.delete() } }

    private val PLAN = Regex("""plan((?: \(\d+\))*)\.json""")

    /**
     * The plan: plan.json or one of sync's copies of it, the highest `rev`; on a tie the one written by the device with mobile data
     * (25 Oct: the tablet's own Setup, rev 1 like the phone's, with the carrier's figure as its offset — 83 % there, 41 % on the phone).
     */
    private fun plans(root: File): List<Pair<DataPlan, File>> {
        val found = root.listFiles().orEmpty().filter { PLAN.matches(it.name) }.sortedBy { it.name.length } // plan.json first: it stays on a tie
            .mapNotNull { f -> runCatching { f.readText() }.getOrNull()?.let(DataPlan::parse)?.let { it to f } }
        if (found.map { it.first.rev to it.first.by }.distinct().size <= 1) return found
        val mobile = all(root).mapValues { (_, l) -> l.sumOf { m -> m.days.sumOf { it.mobile } } }
        return found.sortedWith(compareByDescending<Pair<DataPlan, File>> { it.first.rev }.thenByDescending { mobile[it.first.by] ?: 0L })
    }

    fun plan(root: File = root()): DataPlan? = plans(root).firstOrNull()?.first

    /** Sync's copies of plan.json removed, the winning plan in its place (any device may change the plan, so all agree after). */
    fun settlePlan(root: File = root()): Int {
        val l = plans(root); val file = l.firstOrNull()?.second ?: return 0
        if (file.name != "plan.json") write(File(root, "plan.json"), file.readText())
        return l.count { (_, f) -> f.name != "plan.json" && f.delete() }
    }

    fun savePlan(c: Context, p: DataPlan, root: File = root()) {
        val old = plan(root)
        write(File(root.apply { mkdirs() }, "plan.json"), p.copy(rev = max(p.rev, old?.rev ?: 0) + 1, by = device()).toJson())
        ScopedSync.soon(c, "data")
    }

    /** Written beside, then renamed over: a sync never reads half a file. False when it was already so. */
    fun write(f: File, text: String): Boolean {
        if (f.isFile && f.readText() == text) return false
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, ".${f.name}.tmp"); tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
        f.setLastModified(System.currentTimeMillis()) // its time is now: the phone's storage keeps a renamed-over file's first date, and sync's hash cache goes by size and date
        return true
    }

    fun line(d: DataDay) = "{\"d\":\"${d.day}\",\"mobile\":${d.mobile},\"wifi\":${d.wifi},\"roam\":${d.roaming},\"hotspot\":${d.hotspot},\"via\":${d.viaHotspot}" +
        (if (d.roamOutsideEu) ",\"outsideEu\":true" else "") + (if (d.ethernet > 0) ",\"eth\":${d.ethernet}" else "") + "}"
    fun line(a: DataApp) = "{\"d\":\"${a.day}\",\"app\":${JSONObject.quote(a.app)},\"name\":${JSONObject.quote(a.name)},\"net\":\"${a.net}\",\"fg\":${a.fg},\"bg\":${a.bg},\"roam\":${a.roaming}}"
    fun line(h: DataHour) = "{\"h\":\"${h.hour}\",\"net\":\"${h.net}\",\"bytes\":${h.bytes}}"
    fun line(e: DataEvent) = "{\"at\":\"${e.at}\",\"event\":\"${e.kind}\",\"secs\":${e.secs}" + (if (e.note.isNotEmpty()) ",\"note\":${JSONObject.quote(e.note)}" else "") + "}"

    fun text(m: DataMonth) = buildString {
        if (m.name.isNotEmpty() || m.at > 0) append("{\"deviceName\":${JSONObject.quote(m.name)}" + (if (m.at > 0) ",\"counted\":${m.at}" else "") + "}\n")
        m.days.sortedBy { it.day }.forEach { append(line(it)).append('\n') }
        m.hours.sortedWith(compareBy({ it.hour }, { it.net })).forEach { append(line(it)).append('\n') }
        m.apps.sortedWith(compareBy({ it.day }, { -it.total }, { it.app })).forEach { append(line(it)).append('\n') }
        m.events.sortedBy { it.at }.forEach { append(line(it)).append('\n') }
    }

    fun parse(device: String, text: String): DataMonth {
        val days = mutableListOf<DataDay>(); val apps = mutableListOf<DataApp>(); val hours = mutableListOf<DataHour>(); val events = mutableListOf<DataEvent>()
        var name = ""; var at = 0L
        text.lineSequence().filter { it.isNotBlank() }.forEach { l ->
            val o = runCatching { JSONObject(l) }.getOrNull() ?: return@forEach
            when {
                o.has("deviceName") -> { name = o.optString("deviceName"); at = o.optLong("counted") }
                o.has("event") -> events += DataEvent(o.optString("at"), o.optString("event"), o.optLong("secs"), o.optString("note"))
                o.has("h") -> hours += DataHour(o.optString("h"), o.optString("net"), o.optLong("bytes"))
                o.has("app") -> apps += DataApp(LocalDate.parse(o.getString("d")), o.optString("app"), o.optString("name"), o.optString("net"), o.optLong("fg"), o.optLong("bg"), o.optLong("roam"))
                o.has("d") -> days += DataDay(LocalDate.parse(o.getString("d")), o.optLong("mobile"), o.optLong("wifi"), o.optLong("roam"), o.optLong("hotspot"), o.optLong("via"), o.optBoolean("outsideEu"), o.optLong("eth"))
            }
        }
        return DataMonth(device, days, apps, hours, events, name, at)
    }

    fun monthFile(month: String, device: String = device(), root: File = root()) = File(File(root, "usage"), "$month.$device.jsonl")

    /** Every device's months, by device: this one's and those sync brought (the tablet's, the computer's). */
    fun all(root: File = root()): Map<String, List<DataMonth>> = versions(root).map { it.first }.groupBy { it.device }

    /** One device's days from its months, merged and in order. */
    fun days(months: List<DataMonth>) = months.flatMap { it.days }.associateBy { it.day }.values.sortedBy { it.day }
}
