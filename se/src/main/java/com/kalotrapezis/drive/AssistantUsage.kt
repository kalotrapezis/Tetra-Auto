package com.kalotrapezis.drive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * What AI Assist spends, call by call (the user, 1 Nov 2026: "a token dashboard… statistics and billing in the settings"; "and
 * kept, we change it fast: updates must not reset it"; the desktop's usage.js, the same files). Each paid call is one JSON
 * line in .System/Assistant/Shared/usage, a file a device a month ("Xiaomi-15-2026-11.jsonl"): synced, so the computer's and
 * the phone's add up and a reinstall gets them back, and no two devices ever write one file. Lines carry "v"; older lines
 * are read, a migration only adds fields. The costs are estimates: tokens and characters times Mistral's published prices.
 *
 * A line: { v, t (ms), f (feature), m (model or tool), i / o (tokens), c (characters read aloud), s (seconds), p (pages),
 *   usd (€ in practice), file, job; an "mp3" line has title, s and cost (the job's whole cost, already in its other lines) }.
 */
internal class Usage(val dir: File, device: String, private val zone: ZoneId = ZoneId.systemDefault(), private val now: () -> Long = { System.currentTimeMillis() }) {
    val device = device.replace(Regex("[^\\p{L}\\p{N}_.-]+"), "-").ifEmpty { "phone" }

    class Line(val t: Long, val f: String, val m: String, val usd: Double, val cost: Double, val s: Double, val file: String, val title: String, val device: String, val v: Int)
    class Group(val key: String, var usd: Double = 0.0, var n: Int = 0)
    class Mp3(val title: String, val seconds: Double, val usd: Double, val at: Long, val device: String)
    class Budget(val monthly: Double?, val warned: String)
    class Summary(val today: Double, val month: Double, val all: Double, val since: Long, val days: List<Pair<LocalDate, Double>>, val byFeature: List<Group>,
        val byModel: List<Group>, val byDevice: List<Group>, val files: List<Group>, val mp3s: List<Mp3>, val budget: Budget, val history: History = History(emptyList(), emptyList(), emptyList()))
    /** One row of the history: a day ("2026-11-08"), a month ("2026-11") or a year ("2026"), what it cost, how many uses. */
    class Period(val key: String, val usd: Double, val n: Int)
    /** Every day, month and year with spending, newest first: the month files are all kept, nothing ends after 30 days (8 Nov). */
    class History(val days: List<Period>, val months: List<Period>, val years: List<Period>)

    /** The history from [all] lines; an MP3's line repeats its job's cost, so it is left out. */
    fun history(all: List<Line>): History {
        val paid = all.filter { it.f != "mp3" }
        fun by(key: (Line) -> String) = paid.groupBy(key).map { (k, xs) -> Period(k, xs.sumOf { it.usd }, xs.size) }.filter { it.usd > 0 || it.n > 0 }.sortedByDescending { it.key }
        return History(by { day(it.t).toString() }, by { monthKey(it.t) }, by { monthKey(it.t).take(4) })
    }

    private fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    private fun monthKey(ms: Long) = day(ms).toString().take(7)

    fun add(entry: Map<String, Any?>) {
        val t = now()
        val line = JSONObject().put("v", V).put("t", t)
        for ((k, x) in entry) if (x != null && x != "" && !(x is Number && x.toDouble() == 0.0)) line.put(k, x)
        if (!line.has("usd")) line.put("usd", 0)
        runCatching { dir.mkdirs(); File(dir, "$device-${monthKey(t)}.jsonl").appendText(line.toString() + "\n") }.onFailure { WriterLog.w("AI Assist: usage not written", it) }
    }

    /** Every device's lines, [months] ("2026-11") only when given; a broken line (cut by sync mid-write) skipped. */
    fun lines(months: List<String>? = null): List<Line> = dir.listFiles { f -> FILE.matches(f.name) }.orEmpty()
        .filter { f -> months == null || months.any { f.name.endsWith("-$it.jsonl") } }.flatMap { f ->
            val device = f.name.replace(Regex("-\\d{4}-\\d{2}\\.jsonl$"), "")
            runCatching { f.readLines() }.getOrDefault(emptyList()).filter { it.isNotBlank() }.mapNotNull { runCatching { upgrade(JSONObject(it), device) }.getOrNull() }
        }

    fun month(at: Long = now()) = lines(listOf(monthKey(at))).sumOf { it.usd }

    // ---- The monthly budget: budget.json beside the lines, the same on every device ----
    private val budgetFile get() = File(dir, "budget.json")
    private fun budgetJson() = runCatching { JSONObject(budgetFile.readText()) }.getOrDefault(JSONObject())
    fun budget() = budgetJson().let { Budget(if (it.isNull("monthly") || !it.has("monthly")) null else it.getDouble("monthly"), it.optString("warned")) }
    fun setBudget(monthly: Double): Budget {
        val o = budgetJson().put("v", V).put("monthly", if (monthly > 0) Math.round(monthly * 100) / 100.0 else JSONObject.NULL)
        dir.mkdirs(); budgetFile.writeText(o.toString())
        return budget()
    }
    /** Over the month's budget: what costs by itself (an MP3) does not start. */
    fun capped() = budget().monthly?.let { month() >= it } == true
    /** True once a month, when the month's spending first passes 80 % of the budget. */
    fun warnNow(): Boolean {
        val b = budget(); val m = monthKey(now())
        if (b.monthly == null || b.warned == m || month() < 0.8 * b.monthly) return false
        budgetFile.writeText(budgetJson().put("v", V).put("warned", m).toString())
        return true
    }

    /** The dashboard: today, this month, all time; the last 30 days; by feature, model and device; the files and MP3s. */
    fun summary(): Summary {
        val now = now(); val all = lines(); val today = day(now)
        fun group(xs: List<Line>, key: (Line) -> String) = xs.fold(LinkedHashMap<String, Group>()) { g, x ->
            key(x).takeIf { it.isNotEmpty() }?.let { k -> g.getOrPut(k) { Group(k) }.apply { usd += x.usd; n++ } }; g }.values.sortedByDescending { it.usd }
        val paid = all.filter { it.f != "mp3" }
        val days = (29 downTo 0).map { today.minusDays(it.toLong()) }.let { ds -> val by = all.groupBy { day(it.t) }; ds.map { d -> d to (by[d]?.sumOf { it.usd } ?: 0.0) } }
        return Summary(all.filter { day(it.t) == today }.sumOf { it.usd }, all.filter { monthKey(it.t) == monthKey(now) }.sumOf { it.usd }, all.sumOf { it.usd },
            all.minOfOrNull { it.t }?.coerceAtMost(now) ?: now, days, group(paid) { it.f }, group(paid) { it.m }, group(all) { it.device }, group(paid) { it.file }.take(10),
            all.filter { it.f == "mp3" }.sortedByDescending { it.t }.take(20).map { Mp3(it.title.ifEmpty { it.file }, it.s, it.cost, it.t, it.device) }, budget(), history(all))
    }

    companion object {
        const val V = 1
        private val FILE = Regex(".+-\\d{4}-\\d{2}\\.jsonl$")
        /** A line as this version knows it, from any older one (v0: at, feature, model): nothing is dropped. */
        fun upgrade(x: JSONObject, device: String) = Line(x.optLong("t").takeIf { it > 0 } ?: x.optLong("at"), x.optString("f", x.optString("feature", "chat")),
            x.optString("m", x.optString("model")), x.optDouble("usd", 0.0).takeIf { !it.isNaN() } ?: 0.0, x.optDouble("cost", 0.0).takeIf { !it.isNaN() } ?: 0.0,
            x.optDouble("s", 0.0).takeIf { !it.isNaN() } ?: 0.0, x.optString("file"), x.optString("title"), device, x.optInt("v", 0))
    }
}

/** This phone's usage record, and the 80 % notification once a month. */
internal object AssistantUsage {
    fun of(c: Context) = Usage(File(TetraSystem.dir(driveRoot(), "Assistant", "Shared"), "usage"), TimerControl.device())
    fun record(c: Context, line: Map<String, Any>) {
        val u = of(c)
        u.add(line)
        WriterLog.i("AI Assist: usage ${line["f"]} ${line["m"] ?: ""} ${Bill.text(line["usd"] as? Double ?: 0.0)}")
        if (u.warnNow()) runCatching {
            val b = u.budget()
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(AssistantBriefs.CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(AssistantBriefs.CHANNEL, c.getString(R.string.assistant_briefs_channel), NotificationManager.IMPORTANCE_HIGH).apply { setShowBadge(false) })
            val open = PendingIntent.getActivity(c, 7350, Intent(c, UsageActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            NotificationManagerCompat.from(c).notify(7350, NotificationCompat.Builder(c, AssistantBriefs.CHANNEL).setSmallIcon(R.drawable.ic_auto_fix)
                .setContentTitle(c.getString(R.string.usage_warn_title)).setContentText(c.getString(R.string.usage_warn_body, Bill.text(u.month()), Bill.text(b.monthly ?: 0.0)))
                .setContentIntent(open).setAutoCancel(true).build())
        }
    }
}

internal fun openUsage(c: Context) = c.startActivity(Intent(c, UsageActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

/** Settings › AI Assist › Usage and cost, its own window (the chat's € chip opens it too). */
class UsageActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.wrap(base))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WriterLog.init(this)
        TetraLook.load(this)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = TetraLook.light
        setContent { TetraTheme { UsageScreen { finish() } } }
    }
}

private val BAR = Color(0xFFE05A00) // AI Assist's orange, darkened to read in light and dark (the desktop's)

/** The desktop's Usage.tsx: tiles, the 30 days, the budget, what it went on, the files and MP3s, the prices used. */
@Composable
private fun UsageScreen(back: () -> Unit) {
    val context = LocalContext.current
    val usage = remember { AssistantUsage.of(context) }
    var version by remember { mutableIntStateOf(0) }
    val u by produceState<Usage.Summary?>(null, version) { value = withContext(Dispatchers.IO) { usage.summary() } }
    val meter = remember { AssistantMeter.read(context).total }
    var budget by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(u?.budget?.monthly) { budget = u?.budget?.monthly?.let { "%.2f".format(java.util.Locale.ROOT, it).removeSuffix(".00") } ?: "" }
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    fun date(d: LocalDate) = d.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale))
    fun ms(t: Long) = date(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate())
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_back)) }
                Text(stringResource(R.string.usage_title), style = MaterialTheme.typography.titleLarge)
            }
            val s = u ?: return@Column
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(stringResource(R.string.usage_today) to s.today, stringResource(R.string.usage_month) to s.month, stringResource(R.string.usage_all, ms(s.since)) to s.all).forEach { (label, x) ->
                        Surface(Modifier.weight(1f), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Column(Modifier.padding(12.dp)) {
                                Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(Bill.text(x), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
                // the meter's odometer is older than this record: what came before it, without a breakdown
                (meter - s.all).takeIf { it > 0.00005 }?.let { Text(stringResource(R.string.usage_before, Bill.text(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Days(s.days) { date(it) }
                HistoryTable(s.history, locale) { date(it) }
                s.budget.monthly?.let { b ->
                    Text(stringResource(R.string.usage_budget_used, Bill.text(s.month), Bill.text(b)), style = MaterialTheme.typography.labelMedium)
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        Box(Modifier.fillMaxWidth((s.month / b).toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(when {
                            s.month >= b -> Color(0xFFD32F2F); s.month >= 0.8 * b -> Color(0xFFE8A100); else -> Color.Gray }))
                    }
                }
                // Save only when it changes, and «Saved ✓» after (8 Nov: "I pressed save and it still says save, as if not pressed")
                val typed = budget.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
                val changed = typed != s.budget.monthly
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(budget, { budget = it; saved = false }, Modifier.weight(1f), label = { Text(stringResource(R.string.usage_budget)) }, placeholder = { Text(stringResource(R.string.usage_budget_none)) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    Button(enabled = changed, onClick = { runCatching { usage.setBudget(typed ?: 0.0) }.onSuccess { saved = true }.onFailure { WriterLog.e("AI Assist: the budget was not kept", it) }
                        ScopedSync.soon(context, "assistant", 0, orWhole = true); version++ }) { Text(stringResource(if (saved && !changed) R.string.usage_saved else R.string.common_save)) }
                }
                Text(stringResource(R.string.usage_budget_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (s.byFeature.isEmpty()) Text(stringResource(R.string.usage_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    Breakdown(stringResource(R.string.usage_by_feature), s.byFeature) { feature(context, it) }
                    Breakdown(stringResource(R.string.usage_by_model), s.byModel) { it }
                    if (s.byDevice.size > 1) Breakdown(stringResource(R.string.usage_by_device), s.byDevice) { it }
                    if (s.files.isNotEmpty()) Breakdown(stringResource(R.string.usage_files), s.files) { it }
                }
                if (s.mp3s.isNotEmpty()) {
                    Text(stringResource(R.string.usage_mp3s), style = MaterialTheme.typography.titleSmall)
                    s.mp3s.forEach { m -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${ms(m.at)} · %d:%02d".format(java.util.Locale.ROOT, (m.seconds / 60).toInt(), Math.round(m.seconds % 60).toInt()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(Bill.text(m.usd), style = MaterialTheme.typography.labelLarge)
                    } }
                }
                var prices by remember { mutableStateOf(false) }
                Text(stringResource(R.string.usage_prices, date(LocalDate.parse(Bill.PRICES_CHECKED))) + if (prices) " ▴" else " ▾",
                    Modifier.fillMaxWidth().clickable { prices = !prices }.padding(vertical = 6.dp), style = MaterialTheme.typography.titleSmall)
                if (prices) Bill.PRICES.forEach { (model, price, per) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(model, Modifier.weight(1.4f), style = MaterialTheme.typography.bodySmall); Text(price, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(when (per) { "tokens" -> R.string.usage_per_tokens; "minute" -> R.string.usage_per_minute; "chars" -> R.string.usage_per_chars; "page" -> R.string.usage_per_page; else -> R.string.usage_per_call }),
                        Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                Text(stringResource(R.string.usage_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun feature(c: Context, f: String): String = when (f) {
    "chat" -> R.string.usage_f_chat; "live" -> R.string.usage_f_live; "brief" -> R.string.usage_f_brief; "schedule" -> R.string.usage_f_schedule
    "dictation" -> R.string.usage_f_dictation; "voice" -> R.string.usage_f_voice; "ocr" -> R.string.usage_f_ocr; "script" -> R.string.usage_f_script
    "verify" -> R.string.usage_f_verify; "tts" -> R.string.usage_f_tts; "web" -> R.string.usage_f_web; "image" -> R.string.usage_f_image; else -> null
}?.let(c::getString) ?: f

/** A bar a day, the last 30: thin bars rounded at the top, the highest labelled, the first and last day under the axis; a tap shows a day. */
@Composable
private fun Days(days: List<Pair<LocalDate, Double>>, date: (LocalDate) -> String) {
    val max = days.maxOf { it.second }
    if (!(max > 0)) return
    var picked by remember { mutableStateOf(days.indexOfLast { it.second == max }) }
    val axis = MaterialTheme.colorScheme.outline
    Text(stringResource(R.string.usage_days), style = MaterialTheme.typography.titleSmall)
    Text("${date(days[picked].first)}: ${Bill.text(days[picked].second)}", style = MaterialTheme.typography.labelLarge, color = BAR)
    Canvas(Modifier.fillMaxWidth().height(120.dp).pointerInput(days) { detectTapGestures(onTap = { o -> picked = (o.x / size.width * days.size).toInt().coerceIn(0, days.size - 1) }) }) {
        val step = size.width / days.size; val w = step * 0.6f; val h0 = size.height - 2f
        days.forEachIndexed { i, (_, usd) ->
            if (usd <= 0) return@forEachIndexed
            val h = maxOf(4f, (usd / max).toFloat() * (h0 - 8f)); val x = i * step + (step - w) / 2
            val r = minOf(w / 2, 6f)
            drawPath(Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(x, h0 - h, x + w, h0, CornerRadius(r), CornerRadius(r), CornerRadius.Zero, CornerRadius.Zero)) },
                if (i == picked) BAR else BAR.copy(alpha = 0.75f))
        }
        drawLine(axis, Offset(0f, h0), Offset(size.width, h0), 1.5f)
    }
    Row(Modifier.fillMaxWidth()) {
        Text(date(days.first().first), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(date(days.last().first), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


@Composable
private fun Breakdown(title: String, rows: List<Usage.Group>, label: (String) -> String) {
    val max = maxOf(rows.maxOfOrNull { it.usd } ?: 0.0, 1e-9)
    Text(title, style = MaterialTheme.typography.titleSmall)
    rows.take(10).forEach { r -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1.3f)) {
            Text(label(r.key), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(pluralStringResource(R.plurals.usage_calls, r.n, r.n), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth((r.usd / max).toFloat()).fillMaxHeight().background(BAR))
        }
        Text(Bill.text(r.usd), style = MaterialTheme.typography.labelLarge)
    } }
}

/** The history (8 Nov: "a table that keeps the days, months and year"): every day, month or year with spending, newest first. */
@Composable
private fun HistoryTable(h: Usage.History, locale: java.util.Locale, date: (LocalDate) -> String) {
    if (h.days.isEmpty()) return
    var tab by remember { mutableIntStateOf(0) }
    Text(stringResource(R.string.usage_history), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(R.string.usage_tab_days, R.string.usage_tab_months, R.string.usage_tab_years).forEachIndexed { i, label ->
            FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(stringResource(label)) })
        }
    }
    val rows = when (tab) { 1 -> h.months; 2 -> h.years; else -> h.days }
    var all by remember(tab) { mutableStateOf(false) }
    val month = java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", locale)
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
            (if (all) rows else rows.take(31)).forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(when (tab) {
                        1 -> runCatching { java.time.YearMonth.parse(p.key).format(month).replaceFirstChar { it.titlecase(locale) } }.getOrDefault(p.key)
                        2 -> p.key
                        else -> runCatching { date(LocalDate.parse(p.key)) }.getOrDefault(p.key)
                    }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("× ${p.n}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
                    Text(Bill.text(p.usd), style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                }
            }
            if (!all && rows.size > 31) TextButton(onClick = { all = true }) { Text(stringResource(R.string.usage_more, rows.size - 31)) }
        }
    }
}
