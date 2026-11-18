package com.kalotrapezis.drive

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * The scheduled AI actions (the desktop's schedule.js is the spec): a prompt, a time and a repetition. One computer runs
 * them; this phone sees, changes and adds to the same synced file, and asks it to run one now ("runNow"):
 *
 *   Tetra/.System/Assistant/Shared/schedules.json, synced as "AI Assist/schedules.json"
 *   { list: [{ id, kind: prompt|brief, title, prompt, time: HH:MM, repeat: once|daily|weekly|monthly|yearly, days: [0-6, 0 = Sunday],
 *              date: YYYY-MM-DD, paused, created, last, runNow, allow: [calendar|notes|timers|files] }], brief: { sections, place }, runner, removed: { id: ms } }
 *
 * Each change rewrites the whole file and keeps every key it does not know (the computer's own). When both changed it before
 * a sync, the computer merges the two by id (schedule.js mergeSchedules); a deletion is kept in "removed" (id → when) for that.
 */
internal object AssistantSchedules {
    val REPEATS = listOf("once", "daily", "weekly", "monthly", "yearly")

    fun file(tetra: File) = File(TetraSystem.dir(tetra, "Assistant", "Shared"), "schedules.json")

    /** The file, or what the computer starts with: the daily brief at 07:30. */
    fun read(tetra: File): JSONObject = runCatching { JSONObject(file(tetra).readText()) }.getOrNull()?.takeIf { it.has("list") }
        ?: JSONObject().put("list", JSONArray().put(JSONObject().put("id", "brief").put("kind", "brief").put("title", "Η ενημέρωση της ημέρας").put("prompt", "")
            .put("time", "07:30").put("repeat", "daily").put("days", JSONArray()).put("date", JSONObject.NULL).put("paused", false).put("created", System.currentTimeMillis())))
            .put("brief", JSONObject().put("sections", JSONObject()))

    fun list(tetra: File): List<JSONObject> = read(tetra).getJSONArray("list").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    private fun write(tetra: File, o: JSONObject) {
        val f = file(tetra).apply { parentFile?.mkdirs() }
        val part = File(f.parentFile, ".schedules.json.part").apply { writeText(o.toString(2)) }
        check(part.renameTo(f))
    }

    /** [x]'s fields checked and set on [s]: a time HH:MM, a known repetition, weekdays 0–6, a date YYYY-MM-DD. */
    private fun apply(s: JSONObject, x: JSONObject): JSONObject {
        x.optString("title").takeIf { x.has("title") }?.let { s.put("title", it.trim().take(120)) }
        x.optString("prompt").takeIf { x.has("prompt") && s.optString("kind") != "brief" }?.let { s.put("prompt", it.trim().take(4000)) }
        if (x.has("time")) {
            val m = Regex("^(\\d{1,2}):(\\d{2})$").find(x.getString("time").trim()) ?: throw IllegalArgumentException("Not a time: ${x.getString("time")}")
            val (h, mi) = m.destructured
            require(h.toInt() <= 23 && mi.toInt() <= 59) { "Not a time: ${x.getString("time")}" }
            s.put("time", "%02d:%s".format(h.toInt(), mi))
        }
        if (x.has("repeat")) { val r = x.getString("repeat"); require(r in REPEATS) { "Not a repetition: $r" }; s.put("repeat", r) }
        x.optJSONArray("days")?.let { a -> s.put("days", JSONArray((0 until a.length()).map { a.optInt(it, -1) }.filter { it in 0..6 }.distinct().sorted())) }
        if (x.has("date")) { val d = x.optString("date").takeIf { !x.isNull("date") && it.isNotBlank() }; require(d == null || Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(d)) { "Not a date: $d" }; s.put("date", d ?: JSONObject.NULL) }
        x.optJSONArray("allow")?.let { a -> s.put("allow", JSONArray((0 until a.length()).map { a.optString(it) }.distinct().filter { it in AssistantGuard.ALLOW })) }
        if (x.has("until")) { val d = x.optString("until").takeIf { !x.isNull("until") && it.isNotBlank() }; require(d == null || Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(d)) { "Not a date: $d" }; s.put("until", d ?: JSONObject.NULL) }
        if (x.has("paused")) s.put("paused", x.getBoolean("paused"))
        if (x.has("runNow")) s.put("runNow", x.getBoolean("runNow"))
        if (s.optString("repeat") == "weekly") require((s.optJSONArray("days")?.length() ?: 0) > 0) { "Say on which weekdays." }
        if (s.optString("repeat") == "once" && s.isNullOrBlank("date")) s.put("date", LocalDate.now().toString())
        return s
    }
    private fun JSONObject.isNullOrBlank(k: String) = isNull(k) || optString(k).isBlank()

    fun add(tetra: File, x: JSONObject): JSONObject {
        val o = read(tetra)
        val s = apply(JSONObject().put("id", UUID.randomUUID().toString()).put("kind", "prompt").put("title", "").put("prompt", "").put("repeat", "daily")
            .put("days", JSONArray()).put("date", JSONObject.NULL).put("paused", false).put("created", System.currentTimeMillis()), x)
        require(s.optString("prompt").isNotBlank() && s.has("time")) { "Say what to do and when." }
        if (s.optString("title").isBlank()) s.put("title", s.getString("prompt").take(60))
        o.getJSONArray("list").put(s); write(tetra, o)
        return s
    }

    /** One changed; a new time or repetition counts from now, as on the computer, so it does not run at once for a time gone by. */
    fun change(tetra: File, id: String, x: JSONObject): JSONObject {
        val o = read(tetra); val a = o.getJSONArray("list")
        val s = (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("id") == id } ?: throw IllegalArgumentException("No such scheduled action.")
        apply(s, x)
        if (listOf("time", "repeat", "days", "date").any { x.has(it) } || (x.has("paused") && !x.getBoolean("paused"))) s.put("last", System.currentTimeMillis())
        write(tetra, o)
        return s
    }

    /** Deleted; the brief too (the user, 6 Nov), and [addBrief] brings it back. */
    fun remove(tetra: File, id: String) {
        val o = read(tetra); val a = o.getJSONArray("list")
        o.put("list", JSONArray((0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("id") != id }))
        // remembered as gone, so the computer's merge (schedule.js mergeSchedules) does not bring it back from its own copy
        (o.optJSONObject("removed") ?: JSONObject().also { o.put("removed", it) }).put(id, System.currentTimeMillis())
        write(tetra, o)
    }
    fun addBrief(tetra: File) {
        val o = read(tetra); val a = o.getJSONArray("list")
        if ((0 until a.length()).none { a.getJSONObject(it).optString("kind") == "brief" })
            a.put(JSONObject().put("id", "brief").put("kind", "brief").put("title", "Η ενημέρωση της ημέρας").put("prompt", "").put("time", "07:30").put("repeat", "daily")
                .put("days", JSONArray()).put("date", JSONObject.NULL).put("paused", false).put("created", System.currentTimeMillis()))
        write(tetra, o)
    }

    /** Run by this device (AssistantRunner): "Run now" answered, and [at] as its last run unless it was by hand. */
    fun ran(tetra: File, id: String, at: Long?) {
        val o = read(tetra); val a = o.getJSONArray("list")
        (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("id") == id }?.let { s -> s.put("runNow", false); at?.let { s.put("last", it) } }
        write(tetra, o)
    }

    /** Which device runs them: a computer's host name, or "android:<model>" (AssistantRunner.me); null lets the first computer take it. */
    fun runner(tetra: File): String? = read(tetra).optString("runner").takeIf { it.isNotBlank() && it != "null" }
    fun setRunner(tetra: File, who: String?) { val o = read(tetra); o.put("runner", who ?: JSONObject.NULL); write(tetra, o) }

    fun setSections(tetra: File, sections: JSONObject) {
        val o = read(tetra); val b = o.optJSONObject("brief") ?: JSONObject().also { o.put("brief", it) }
        val now = b.optJSONObject("sections") ?: JSONObject().also { b.put("sections", it) }
        sections.keys().forEach { k -> if (sections.opt(k) is Boolean) now.put(k, sections.getBoolean(k)) }
        write(tetra, o)
    }

    /** The brief's weather place ({ name, lat, lon }); null: where the phone is when it runs (the computer asks it). */
    fun place(tetra: File): JSONObject? = read(tetra).optJSONObject("brief")?.optJSONObject("place")
    fun setPlace(tetra: File, place: JSONObject?) {
        val o = read(tetra); val b = o.optJSONObject("brief") ?: JSONObject().also { o.put("brief", it) }
        b.put("place", place ?: JSONObject.NULL)
        write(tetra, o)
    }

    /** A town by name, as the computer's Settings finds it (Open-Meteo's geocoding): { name, lat, lon }, or null. Off the main thread. */
    fun findPlace(name: String, lang: String): JSONObject? {
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=${java.net.URLEncoder.encode(lang, "UTF-8")}&name=${java.net.URLEncoder.encode(name.trim().take(100), "UTF-8")}"
        val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 10_000 }
        val p = try { JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("results")?.optJSONObject(0) } finally { c.disconnect() } ?: return null
        return JSONObject().put("name", listOf(p.optString("name"), p.optString("country")).filter { it.isNotBlank() }.joinToString(", "))
            .put("lat", p.getDouble("latitude")).put("lon", p.getDouble("longitude"))
    }

    /** The first run after [after], as the computer works it out (schedule.js nextRun): local time, a 31st on a short month's last day. */
    fun nextRun(s: JSONObject, after: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(s.optString("time")) ?: return null
        val (h, mi) = m.destructured.toList().map { it.toInt() }
        if (h > 23 || mi > 59) return null
        val from = Instant.ofEpochMilli(after).atZone(zone).toLocalDate()
        val base = s.optString("date").takeIf { !s.isNull("date") && it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: Instant.ofEpochMilli(s.optLong("created", after)).atZone(zone).toLocalDate()
        val days = s.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty()
        for (i in 0..400) {
            val d = from.plusDays(i.toLong())
            val on = when (s.optString("repeat")) {
                "once" -> d == base
                "daily" -> true
                "weekly" -> d.dayOfWeek.value % 7 in days
                "monthly" -> d.dayOfMonth == minOf(base.dayOfMonth, d.lengthOfMonth())
                "yearly" -> d.month == base.month && d.dayOfMonth == minOf(base.dayOfMonth, d.lengthOfMonth())
                else -> false
            }
            if (!on) continue
            val t = LocalDateTime.of(d, java.time.LocalTime.of(h, mi)).atZone(zone).toInstant().toEpochMilli()
            if (t > after) return t
        }
        return null
    }

    /** When it runs next: null when paused or done (past its "until" day too, schedule.js the same). */
    fun due(s: JSONObject): Long? {
        if (s.optBoolean("paused")) return null
        val next = nextRun(s, s.optLong("last", s.optLong("created", 0)).takeIf { it > 0 } ?: s.optLong("created", 0)) ?: return null
        val until = s.optString("until").takeIf { !s.isNull("until") && it.isNotBlank() } ?: return next
        val end = runCatching { LocalDate.parse(until).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull() ?: return next
        return next.takeIf { it < end }
    }

    /** How its last run went (the user, 14 Nov: a green, yellow or red dot): ok, held (waits for a yes) or failed. */
    fun result(tetra: File, id: String, r: String) {
        val o = read(tetra); val a = o.getJSONArray("list")
        (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("id") == id }?.let { s -> s.put("result", r).put("resultAt", System.currentTimeMillis()) } ?: return
        write(tetra, o)
    }
    fun outcome(log: List<JSONObject>) = when { log.any { it.optString("kind") == "failed" } -> "failed"; log.any { it.optString("kind") == "held" } -> "held"; else -> "ok" }
}
