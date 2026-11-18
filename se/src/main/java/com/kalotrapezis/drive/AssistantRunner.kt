package com.kalotrapezis.drive

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The scheduled AI actions run on this phone or tablet when schedules.json's "runner" names it (the user, 6 Nov: "the phone
 * is always open"; a computer left at home cannot reach a phone that has gone out). The desktop's brief.js and main.js
 * runSchedule are the spec: the same sections, the same page, the same Briefs/latest.json, so every device shows it.
 * An exact alarm at the next one's time (CalendarAlarmReceiver) starts an expedited job (SyncJob) that runs what is due.
 */
internal object AssistantRunner {
    const val JOB = 4161
    const val RUN = "com.kalotrapezis.drive.SCHEDULE_RUN"
    @Volatile private var running = false

    /** This device as the runner: its model, as the computer is its host name. */
    fun me(): String = "android:" + Build.MODEL
    fun mine(tetra: File = driveRoot()) = AssistantSchedules.read(tetra).optString("runner") == me()

    /** The alarm for the next one, and the job now for any due or asked for: after boot, a sync, a change. Off the main thread. */
    fun plan(c: Context) {
        val app = c.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(app, JOB, Intent(app, CalendarAlarmReceiver::class.java).setAction(RUN), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val tetra = driveRoot()
        if (!mine(tetra)) { am.cancel(pi); return }
        val list = AssistantSchedules.list(tetra); val now = System.currentTimeMillis()
        if (list.any { it.optBoolean("runNow") || (AssistantSchedules.due(it) ?: Long.MAX_VALUE) <= now }) start(app)
        val next = list.mapNotNull { AssistantSchedules.due(it) }.filter { it > now }.minOrNull() ?: return am.cancel(pi)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
    }
    fun planSoon(c: Context) { val app = c.applicationContext; Thread { runCatching { plan(app) }.onFailure { WriterLog.e("Scheduled AI: plan failed", it) } }.start() }

    fun start(c: Context) {
        if (running) return // scheduling the same job again would stop the one running
        val jobs = c.getSystemService(JobScheduler::class.java) ?: return
        jobs.schedule(JobInfo.Builder(JOB, ComponentName(c, SyncJob::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .apply { if (Build.VERSION.SDK_INT >= 31) setExpedited(true) }.build())
    }

    /** The job: each one due (less than a day late) or asked for runs, one at a time. */
    fun runDue(c: Context) {
        if (running) return
        running = true
        try {
            val tetra = driveRoot()
            if (!mine(tetra)) return
            val now = System.currentTimeMillis()
            for (s in AssistantSchedules.list(tetra)) {
                val at = AssistantSchedules.due(s)
                val byHand = s.optBoolean("runNow")
                if (!byHand && (at == null || at > now)) continue
                val late = !byHand && now - at!! > 86_400_000
                AssistantSchedules.ran(tetra, s.optString("id"), if (byHand) null else now) // first, so a slow run is not started twice
                if (late) continue
                runCatching { run(c, tetra, s) }.onFailure { e ->
                    WriterLog.e("Scheduled AI: ${s.optString("id")} failed", e)
                    runCatching { AssistantSchedules.result(tetra, s.optString("id"), "failed") }
                    AssistantBriefs.notice(c, title(c, s), c.getString(R.string.brief_failed, e.message ?: e.javaClass.simpleName))
                }
            }
            ScopedSync.soon(c, "assistant", 0, orWhole = true) // the page and "last" to the other devices
        } finally { running = false; runCatching { plan(c) } }
    }

    private fun title(c: Context, s: JSONObject) = if (s.optString("kind") == "brief") c.getString(R.string.assistant_briefs_channel) else s.optString("title")

    /** One run: its page in Briefs, latest.json, the notification here (the others ring after their sync). */
    private fun run(c: Context, tetra: File, s: JSONObject) {
        val gate = AiGate.load(c)
        if (!gate.enabled || !AssistantKey.has(c)) return // AI off or no key: nothing runs, as on the computer
        val title = title(c, s)
        if (AssistantUsage.of(c).capped()) { AssistantBriefs.notice(c, title, c.getString(R.string.usage_capped)); return }
        val now = System.currentTimeMillis()
        val allow = s.optJSONArray("allow")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val chat by lazy { assistantChat(c).also { it.allow = AssistantGuard.allowed(allow) } }
        val day = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val base = if (s.optString("kind") == "brief") day else "$day-${s.optString("id").take(8)}"
        val (html, line) = if (s.optString("kind") == "brief") brief(c, tetra, now, title, gate) { chat.ask(it).text }
            else chat.ask("${s.optString("prompt")}\n\n(Now: ${java.time.ZonedDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", java.util.Locale.ENGLISH))}. This runs by itself on a schedule; your answer is kept as a chat the user reads later, so write the whole result here, as long as it needs, in short paragraphs, with dates, and say what has passed and what is still to come.)").text.let { t ->
                val done = report(c, chat.log)
                // the run as a chat too (the user, 14 Nov: "an actual chat"), named as its page, which offers it
                runCatching { AssistantChats.save(c, base, listOf(JSONObject().put("mine", true).put("text", s.optString("prompt")),
                    JSONObject().put("mine", false).put("text", listOfNotNull(t, done?.second).joinToString("\n\n"))), chat.saved(), title) }
                    .onFailure { WriterLog.e("Scheduled AI: the chat could not be kept", it) }
                page(c, title, heading(c, now) + " " + clock(now), listOfNotNull(Part(c.getString(R.string.home_label_assistant), paragraphs(t)), done?.first)) to t
            }
        val dir = AssistantBriefs.dir().apply { mkdirs() }
        val file = File(dir, "$base.html").apply { writeText(html) }
        File(dir, "latest.json").writeText(JSONObject().put("at", now).put("title", title).put("text", line.take(300)).put("file", file.name).toString())
        AssistantSchedules.result(tetra, s.optString("id"), if (s.optString("kind") == "brief") "ok" else AssistantSchedules.outcome(chat.log))
        AssistantBriefs.shown(c, now)
        AssistantBriefs.post(c, title, line.take(200), file, clock(now))
        WriterLog.i("Scheduled AI: ${file.name} made here")
    }

    /**
     * What a scheduled action did (the user, 14 Nov: "have it actually report back what it can and it couldn't do"), from its
     * chat's log: done, waiting for the user's yes, could not; brief.js report() the same. The page's part and the chat's words.
     */
    internal fun report(c: Context, log: List<JSONObject>): Pair<Part, String>? {
        val group = AssistantGuard.ALLOW.entries.flatMap { (g, ns) -> ns.map { it to g } }.toMap()
        fun label(e: JSONObject) = (group[e.optString("name")]?.let { g -> ALLOW_LABEL.first { it.first == g }.second }?.let(c::getString) ?: e.optString("name")) + ": " + e.optString("what")
        fun why(e: JSONObject) = e.optString("why").take(200)
        val lists = listOf("done" to R.string.assistant_sched_done, "held" to R.string.assistant_sched_waiting, "failed" to R.string.assistant_sched_failed)
            .map { (k, name) -> c.getString(name) to log.filter { it.optString("kind") == k } }.filter { it.second.isNotEmpty() }
        if (lists.isEmpty()) return null
        val html = lists.joinToString("") { (name, l) -> "<h3>${esc(name)}</h3><ul>" + l.joinToString("") { e -> "<li>${esc(label(e))}${why(e).takeIf { it.isNotEmpty() }?.let { " <span class=\"muted\">· ${esc(it)}</span>" }.orEmpty()}</li>" } + "</ul>" }
        val text = lists.joinToString("\n\n") { (name, l) -> "$name:\n" + l.joinToString("\n") { e -> "• ${label(e)}${why(e).takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()}" } }
        return Part(c.getString(R.string.assistant_sched_report), html) to text
    }

    // ---- The brief (brief.js) ----
    class Part(val title: String, val html: String, val line: String? = null)

    /** The phone's own mail tools (Assistant.kt): mail_new, mail_attention; nothing opened on the screen. */
    private const val MAIL_REPORT = """Give me my mail report. Call mail_new for the last 24 hours, then mail_attention for the last 3 days. Do not open any mail.
Then say, briefly and in Greek: what came that matters, what needs an answer (who and about what), and any deadline or date found in a mail. No markdown; it may be read aloud."""

    private val WEATHER = listOf(listOf(0) to (R.string.brief_w_clear to "☀️"), listOf(1, 2) to (R.string.brief_w_partly to "⛅"), listOf(3) to (R.string.brief_w_cloudy to "☁️"),
        listOf(45, 48) to (R.string.brief_w_fog to "🌫️"), listOf(51, 53, 55, 56, 57) to (R.string.brief_w_drizzle to "🌦️"), listOf(61, 63, 65, 66, 67) to (R.string.brief_w_rain to "🌧️"),
        listOf(71, 73, 75, 77, 85, 86) to (R.string.brief_w_snow to "🌨️"), listOf(80, 81, 82) to (R.string.brief_w_showers to "🌦️"), listOf(95, 96, 99) to (R.string.brief_w_storm to "⛈️"))
    private fun weatherOf(c: Context, code: Int) = (WEATHER.firstOrNull { code in it.first } ?: WEATHER[2]).second.let { c.getString(it.first) to it.second }

    private fun brief(c: Context, tetra: File, now: Long, title: String, gate: AiGate.State, ask: (String) -> String): Pair<String, String> {
        val o = AssistantSchedules.read(tetra).optJSONObject("brief") ?: JSONObject()
        val on = o.optJSONObject("sections") ?: JSONObject()
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val sections = listOf<Triple<String, String, () -> Part>>(
            Triple("calendar", "calendar") {
                val cals = CalendarFiles.calendars(CalendarFiles.root(tetra))
                fun day(d: LocalDate) = CalendarFiles.occurrences(cals, d.atStartOfDay(zone).toInstant(), d.plusDays(1).atStartOfDay(zone).toInstant()).sortedBy { it.start }
                fun list(os: List<Occurrence>) = if (os.isEmpty()) "<p class=\"muted\">${esc(c.getString(R.string.brief_nothing))}</p>"
                    else "<ul>" + os.joinToString("") { x -> "<li><span class=\"when\">${if (x.allDay) "—" else esc(clock(x.start.toInstant().toEpochMilli()))}</span> ${esc(x.event.title.ifBlank { c.getString(R.string.calendar_no_title) })}" +
                        (if (x.event.place.isNotBlank()) " <span class=\"muted\">· ${esc(x.event.place)}</span>" else "") + "</li>" } + "</ul>"
                val t = day(today)
                Part(c.getString(R.string.app_calendar), "<h3>${esc(c.getString(R.string.common_today))}</h3>${list(t)}<h3>${esc(c.getString(R.string.common_tomorrow))}</h3>${list(day(today.plusDays(1)))}",
                    c.getString(R.string.brief_today_count, t.size))
            },
            Triple("mail", "mail") { Part(c.getString(R.string.app_mail), paragraphs(ask(MAIL_REPORT))) },
            Triple("weather", "web") {
                val name = c.getString(R.string.assistant_brief_weather)
                val p = o.optJSONObject("place")?.let { Triple(it.optString("name"), it.getDouble("lat"), it.getDouble("lon")) } ?: here(c)
                    ?: return@Triple Part(name, "<p class=\"muted\">${esc(c.getString(R.string.brief_place_missing))}</p>")
                val w = get("https://api.open-meteo.com/v1/forecast?latitude=${p.second}&longitude=${p.third}&current=temperature_2m,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=2")
                val cur = w.getJSONObject("current"); val d = w.getJSONObject("daily"); val (word, sign) = weatherOf(c, cur.getInt("weather_code"))
                val temp = Math.round(cur.getDouble("temperature_2m"))
                fun day(i: Int, label: String): String {
                    val (x, s) = weatherOf(c, d.getJSONArray("weather_code").getInt(i)); val rain = d.getJSONArray("precipitation_probability_max").optInt(i)
                    return "<li><span class=\"when\">${esc(label)}</span> $s ${esc(x)}, ${Math.round(d.getJSONArray("temperature_2m_min").getDouble(i))}°–${Math.round(d.getJSONArray("temperature_2m_max").getDouble(i))}°" +
                        (if (rain > 0) " <span class=\"muted\">· ${esc(c.getString(R.string.brief_rain, rain))}</span>" else "") + "</li>"
                }
                Part(name, "<p class=\"big\">$sign $temp° <span class=\"muted\">${esc(word)} · ${esc(p.first)}</span></p><ul>${day(0, c.getString(R.string.common_today))}${day(1, c.getString(R.string.common_tomorrow))}</ul>", "$temp° $word")
            },
            Triple("notes", "notes") {
                val name = c.getString(R.string.assistant_brief_lists)
                val lists = NotesStore(TetraSystem.dir(tetra, "Notes", "Shared"), "tetra-assistant").list().filter { it.trashedAt == null && it.checklist }
                    .map { it to it.items.filter { i -> !i.checked && i.text.isNotBlank() } }.filter { it.second.isNotEmpty() }.sortedByDescending { it.first.updatedAt }.take(8)
                if (lists.isEmpty()) Part(name, "<p class=\"muted\">${esc(c.getString(R.string.brief_no_lists))}</p>")
                else Part(name, lists.joinToString("") { (n, open) -> "<h3>${esc(n.title.ifBlank { c.getString(R.string.calendar_no_title) })}</h3><ul class=\"checks\">" +
                    open.take(12).joinToString("") { "<li>${esc(it.text)}</li>" } + (if (open.size > 12) "<li class=\"muted\">+${open.size - 12}</li>" else "") + "</ul>" })
            })
        val names = mapOf("calendar" to R.string.app_calendar, "mail" to R.string.app_mail, "weather" to R.string.assistant_brief_weather, "notes" to R.string.assistant_brief_lists)
        val parts = sections.filter { (id, app) -> on.optBoolean(id, true) && gate.allows(app) }.map { (id, _, make) ->
            runCatching { make() }.getOrElse { e -> WriterLog.w("Scheduled AI: $id failed: ${e.message}"); Part(c.getString(names.getValue(id)), "<p class=\"failed\">${esc(c.getString(R.string.brief_failed, e.message ?: ""))}</p>") }
        }
        return page(c, title, heading(c, now), parts) to parts.mapNotNull { it.line }.joinToString(" · ")
    }

    /** Where this device was last: no waiting for a new fix, no question on the screen at seven in the morning. */
    private fun here(c: Context): Triple<String, Double, Double>? = runCatching {
        val lm = c.getSystemService(LocationManager::class.java)
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }.getOrNull()?.let { Triple(c.getString(R.string.brief_place_phone), it.latitude, it.longitude) }

    private fun get(url: String): JSONObject {
        val h = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 15_000 }
        try { if (h.responseCode != 200) error("Open-Meteo ${h.responseCode}"); return JSONObject(h.inputStream.bufferedReader().readText()) } finally { h.disconnect() }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun paragraphs(t: String) = t.trim().split(Regex("\n{2,}")).joinToString("") { "<p>${esc(it).replace("\n", "<br>")}</p>" }
    private fun clock(ms: Long) = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    private fun heading(c: Context, ms: Long) = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(c.uiLocale()).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    /** brief.js page(): Tetra's look, light or dark as the system is. */
    private fun page(c: Context, title: String, sub: String, parts: List<Part>) = """<!doctype html><html lang="${c.uiLocale().language}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><meta name="color-scheme" content="light dark">
<title>${esc(title)}</title><style>
:root { --bg: #f6f4f1; --card: #fff; --ink: #1d1f21; --muted: #6b7075; --line: #e6e2dd; --accent: #4C8DF6; }
@media (prefers-color-scheme: dark) { :root { --bg: #151719; --card: #1f2225; --ink: #eceeef; --muted: #9aa0a6; --line: #2e3236; --accent: #7aa9f8; } }
body { margin: 0; background: var(--bg); color: var(--ink); font: 16px/1.5 system-ui, "Noto Sans", sans-serif; }
main { max-width: 720px; margin: 0 auto; padding: 24px 16px 48px; }
header h1 { margin: 0; font-size: 26px; } header p { margin: 2px 0 20px; color: var(--muted); }
section { background: var(--card); border: 1px solid var(--line); border-radius: 16px; padding: 14px 18px; margin: 0 0 14px; }
h2 { margin: 0 0 6px; font-size: 18px; color: var(--accent); } h3 { margin: 12px 0 4px; font-size: 15px; }
ul { margin: 0; padding: 0 0 0 4px; list-style: none; } li { padding: 3px 0; } ul.checks li::before { content: "☐ "; color: var(--muted); }
.when { display: inline-block; min-width: 4.2em; font-variant-numeric: tabular-nums; font-weight: 600; }
.muted { color: var(--muted); } .big { font-size: 22px; margin: 0 0 6px; } .failed { color: #c62f2f; } p { margin: 6px 0; }
</style></head><body><main><header><h1>${esc(title)}</h1><p>${esc(sub)}</p></header>
${parts.joinToString("\n") { "<section><h2>${esc(it.title)}</h2>${it.html}</section>" }}
</main></body></html>"""
}
