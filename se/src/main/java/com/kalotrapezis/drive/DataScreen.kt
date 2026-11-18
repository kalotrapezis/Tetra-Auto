package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as DateStyle
import java.util.Locale

/** Data's lime (the user's icon, 25 Oct); a darker lime on the light theme, where the bright one cannot be read on white. */
@Composable internal fun dataColor() = lookTint(Color(0xFFA1F81A), Color(0xFF4E8A0A))
internal val DATA_COLOR = Color(0xFFA1F81A)
/** Text on Data's lime: black on the bright one, white on the light theme's dark one. */
@Composable internal fun onData() = lookTint(Color.Black, Color.White)
private val WIFI_COLOR = Color(0xFF4F86E8)
/** Another phone's hotspot (the tablet's, the computer's use of the phone's): neither mobile nor plain Wi-Fi (the user, 25 Oct). */
private val HOTSPOT_COLOR = Color(0xFFFF9F1C)
/** A phone's metered Wi-Fi: the car's, Android Auto (the user, 25 Oct: "Android Auto has its own Wi-Fi … make it Android Auto"). */
private val CAR_COLOR = Color(0xFF26C6DA)

/** Data, its own window as the Phone is (Plan.md § Data): Usage · Devices · Signal, and Setup the first time. */
class DataActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AppLocale.wrap(base))
    override fun onResume() { super.onResume(); runCatching { Notices.seen(this, "data") } } // its warnings seen
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WriterLog.init(this)
        TetraLook.load(this)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = TetraLook.light
        val tab = intent.getStringExtra(TAB)
        setContent { TetraTheme { DataApp(tab, back = { finish() }) } }
    }
    companion object { const val TAB = "com.kalotrapezis.drive.data.TAB" } // Home's slots: usage, devices, signal
}

internal fun openData(c: Context, tab: String? = null) = c.startActivity(Intent(c, DataActivity::class.java).putExtra(DataActivity.TAB, tab).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

private enum class DataTab(val icon: Int, @androidx.annotation.StringRes val label: Int) { Usage(R.drawable.ic_data_usage, R.string.data_tab_usage), Devices(R.drawable.ic_devices, R.string.data_tab_devices), Signal(R.drawable.ic_wifi, R.string.data_tab_signal) }

/** The locale the screen is shown in, for dates and day names. */
@Composable private fun uiLocale(): Locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]

/** An app's name as shown: Android's own counts (the hotspot, removed apps, the system) in the UI's language; the files keep the English. */
private fun appName(c: Context, key: String, name: String): String = when {
    key == "hotspot" -> c.getString(R.string.data_hotspot); key == "removed" -> c.getString(R.string.data_removed_apps)
    key == "android" -> c.getString(R.string.data_android_system); key.startsWith("uid:") -> c.getString(R.string.data_other_apps); else -> name
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DataApp(start: String?, back: () -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) { // back from Android's settings (Usage access): read again
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) version++ }
        lifecycle.lifecycle.addObserver(o); onDispose { lifecycle.lifecycle.removeObserver(o) }
    }
    val firstPlan = remember { DataFiles.plan() } // read once, not on every recomposition
    val plan by produceState(firstPlan, version) { value = withContext(Dispatchers.IO) { DataFiles.plan() } }
    var setup by remember { mutableStateOf(start == "setup" || (firstPlan == null && !PhoneRelay.noCalls(context))) }
    // the counts as last shown at once (opened again), else as written; today's from Android (a second and more on the phone)
    // follow, and the bars and numbers grow to them (the user, 25 Oct: "use the old data… then update with animations")
    val all by produceState(lastData, version) {
        if (value.isEmpty()) value = withContext(Dispatchers.IO) { DataFiles.all() }
        value = withContext(Dispatchers.IO) {
            DataStats.refresh(context)
            DataStats.schedule(context); DataStats.watchUsage(context); DataSignal.watch(context)
            DataFiles.all()
        }.also { lastData = it }
    }
    OpenTiming("Data", all.isNotEmpty())
    // the plan and the other devices' counts fetched from the computer now, not at the next sync (the tablet has no plan of its own)
    LaunchedEffect(Unit) {
        val got = withContext(Dispatchers.IO) { runCatching { SyncClient(context, SyncStore(context)).scopeOnly("data") }.isSuccess }
        if (got) version++
    }
    var tab by remember { mutableStateOf(DataTab.entries.firstOrNull { it.name.equals(start, true) } ?: DataTab.Usage) }
    var drawer by remember { mutableStateOf(false) }
    if (setup) { DataSetup(plan, all[DataFiles.device()].orEmpty(), done = { setup = false; version++ }, back = { if (plan == null) back() else setup = false }); return }
    val top = PaddingValues(top = 76.dp, bottom = 96.dp)
    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.fillMaxHeight().widthIn(max = 980.dp).fillMaxWidth()) {
            when (tab) {
                DataTab.Usage -> UsageTab(plan, all, top, openSetup = { setup = true }) { version++ }
                DataTab.Devices -> DevicesTab(all, top)
                DataTab.Signal -> DataSignalTab(top)
            }
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                    IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_back)) }
                }
                Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                    Text(stringResource(R.string.app_data) + " · " + stringResource(tab.label), Modifier.padding(horizontal = 18.dp, vertical = 13.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
                IslandBottomBar(expand = { drawer = true }, visible = !drawer) {
                    DataTab.entries.forEach { t -> IslandNavigationItem(stringResource(t.label), tab == t, t.icon) { tab = t } }
                }
            }
        }
    }
    if (drawer) ModalBottomSheet(onDismissRequest = { drawer = false }, containerColor = SheetColor, contentColor = islandContentColor()) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.app_data), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { drawer = false; setup = true }, colors = sheetButton()) { Text(stringResource(R.string.data_setup_button)) }
            var live by remember { mutableStateOf(DataSpeed.enabled(context)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.data_live_speed_bar), Modifier.weight(1f)); Switch(live, { live = it; DataSpeed.setEnabled(context, it) })
            }
            Stopwatch()
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_DATA_USAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, colors = sheetButton()) { Text(stringResource(R.string.data_android_settings)) }
        }
    }
}

/** The sheet is dark on both themes: its buttons in the bright lime. */
@Composable private fun sheetButton() = ButtonDefaults.textButtonColors(contentColor = DATA_COLOR)

// ---- Usage ----

private enum class Span(@androidx.annotation.StringRes val label: Int) { Day(R.string.data_span_day), Week(R.string.data_span_week), Cycle(R.string.data_span_cycle), Month(R.string.data_span_month), Year(R.string.data_span_year) }

/** A bar of the chart: its label, the days it covers ([hour] for a day's hours), and its bytes. */
/** [a] on its way to [b] ([t] from 0 to 1): each count between the two. */
private fun mix(a: Bar, b: Bar, t: Float): Bar {
    if (t >= 1f || a == b) return b
    fun m(x: Long, y: Long) = x + ((y - x) * t).toLong()
    return b.copy(mobile = m(a.mobile, b.mobile), wifi = m(a.wifi, b.wifi), hotspot = m(a.hotspot, b.hotspot), car = m(a.car, b.car),
        parts = if (a.parts.size == b.parts.size) a.parts.zip(b.parts) { x, y -> m(x, y) } else b.parts)
}

private data class Bar(val label: String, val from: LocalDate, val until: LocalDate, val mobile: Long, val wifi: Long, val hotspot: Long = 0, val hour: String? = null, val forecast: Boolean = false,
    val parts: List<Long> = emptyList(), val car: Long = 0) { // by app (the Apps view), in the series' order; Android Auto's link
    val total get() = if (parts.isNotEmpty()) parts.sum() else mobile + wifi + hotspot + car
}

/**
 * A day as (mobile, hotspot, Wi-Fi). On a phone the hotspot is what it shared (part of its mobile data) and a metered Wi-Fi is
 * the car's (Android Auto): Wi-Fi. On a tablet or a computer the hotspot is what it used through the phone (part of its Wi-Fi).
 */
private fun split(d: DataDay?, phone: Boolean): Triple<Long, Long, Long> = when {
    d == null -> Triple(0, 0, 0)
    phone -> Triple(d.mobile - d.hotspot, d.hotspot, d.wifi - d.viaHotspot) // the rest of its metered Wi-Fi is the car's: carOf
    else -> Triple(d.mobile, d.viaHotspot, d.wifi - d.viaHotspot)
}
private fun carOf(d: DataDay?, phone: Boolean) = if (phone && d != null) d.viaHotspot else 0L

private fun bars(span: Span, shift: Int, plan: DataPlan?, months: List<DataMonth>, today: LocalDate, phone: Boolean, locale: Locale): List<Bar> {
    val days = DataFiles.days(months).associateBy { it.day }
    val split = days.mapValues { (_, d) -> split(d, phone) }
    val histories = listOf(split.mapValues { it.value.first }, split.mapValues { it.value.second }, split.mapValues { it.value.third })
    val carHistory = days.mapValues { (_, d) -> carOf(d, phone) }
    fun day(d: LocalDate, label: String): Bar {
        // the days to come: each kind's forecast from the weekdays before (faint bars)
        if (d.isAfter(today)) return histories.map { DataRules.forecast(it, d, today) }.let { (m, h, w) -> Bar(label, d, d.plusDays(1), m, w, h, forecast = true, car = DataRules.forecast(carHistory, d, today)) }
        val (m, h, w) = split(days[d], phone)
        return Bar(label, d, d.plusDays(1), m, w, h, car = carOf(days[d], phone))
    }
    return when (span) {
        Span.Day -> {
            val d = today.plusDays(shift.toLong())
            val hrs = months.flatMap { it.hours }.filter { it.hour.startsWith(d.toString()) }
            // Android's buckets are 2 hours from midnight UTC, so here they may start on odd hours: each goes to its pair of hours
            fun slot(h: DataHour) = (h.hour.substringAfter('T').toIntOrNull() ?: 0) / 2 * 2
            (0 until 24 step 2).map { h ->
                fun sum(net: String) = hrs.filter { slot(it) == h && it.net == net }.sumOf { it.bytes }
                if (phone) Bar("$h", d, d.plusDays(1), sum("mobile"), sum("wifi"), sum("tether"), "$h")
                else sum("hotspot").let { hot -> Bar("$h", d, d.plusDays(1), sum("mobile"), maxOf(0, sum("wifi") - hot), hot, "$h") }
            }
        }
        Span.Week -> (6 downTo 0).map { today.plusWeeks(shift.toLong()).minusDays(it.toLong()) }.map { day(it, it.dayOfWeek.getDisplayName(DateStyle.SHORT, locale).take(2)) }
        Span.Cycle -> {
            val c = DataRules.cycle(plan ?: DataPlan(), today).let { c0 -> var c = c0; repeat(-shift) { c = DataRules.cycle(plan ?: DataPlan(), c.start.minusDays(1)) }; c }
            generateSequence(c.start) { it.plusDays(1) }.takeWhile { it.isBefore(c.end) }.map { day(it, "${it.dayOfMonth}") }.toList()
        }
        Span.Month -> {
            val m = today.withDayOfMonth(1).plusMonths(shift.toLong())
            (0 until m.lengthOfMonth()).map { m.plusDays(it.toLong()) }.map { day(it, "${it.dayOfMonth}") }
        }
        Span.Year -> {
            val y = today.withDayOfYear(1).plusYears(shift.toLong())
            (0 until 12).map { y.plusMonths(it.toLong()) }.map { m ->
                val inM = days.values.filter { it.day.year == m.year && it.day.month == m.month }
                val s = inM.map { split(it, phone) }
                Bar(m.month.getDisplayName(DateStyle.NARROW, locale), m, m.plusMonths(1), s.sumOf { it.first }, s.sumOf { it.third }, s.sumOf { it.second }, car = inM.sumOf { carOf(it, phone) })
            }
        }
    }
}

@Composable
private fun UsageTab(plan: DataPlan?, all: Map<String, List<DataMonth>>, pad: PaddingValues, openSetup: () -> Unit, changed: () -> Unit) {
    val context = LocalContext.current
    val today = LocalDate.now()
    val mine = all[DataFiles.device()].orEmpty()
    // the plan's numbers are the phone's: on the tablet, the phone's files (the one with a hotspot or mobile data)
    val phone = if (PhoneRelay.noCalls(context)) all.values.maxByOrNull { m -> DataFiles.days(m).sumOf { it.mobile } }.orEmpty() else mine
    val status = remember(plan, phone) { plan?.let { DataRules.status(it, DataFiles.days(phone), today) } }
    var span by remember { mutableStateOf(Span.Cycle) }
    var shift by remember(span) { mutableIntStateOf(0) }
    var picked by remember(span, shift) { mutableStateOf<Bar?>(null) }
    var app by remember { mutableStateOf<String?>(null) }
    var allApps by remember { mutableStateOf(false) }
    var byApp by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val isPhone = remember { !PhoneRelay.noCalls(context) }
    // the plan's data by app on every device (the user, 25 Oct: "a clear chart only with apps — Firefox, Claude, Teams, Viber"): on a phone its own apps on
    // mobile and what the tablet's and the computer's apps took through its hotspot; on a tablet its own apps
    val appDays = remember(all, isPhone) {
        val own = mine.flatMap { it.apps }.filter { if (isPhone) it.net == "mobile" && it.app != "hotspot" else true }
        val others = if (isPhone) all.filterKeys { it != DataFiles.device() }.values.flatten().flatMap { it.apps }.filter { it.net == "hotspot" } else emptyList()
        (own + others).map { Triple(it.day, appName(context, it.app, it.name.ifBlank { it.app }), it.total) }
    }
    val locale = uiLocale()
    val shown = remember(span, shift, mine, plan, locale) { bars(span, shift, plan, mine, today, isPhone, locale) }
    val drops = remember(all) { DataSignal.drops(all).mapNotNull { runCatching { java.time.OffsetDateTime.parse(it.second.at).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime() }.getOrNull() } }
    val from = picked?.from ?: shown.first().from; val until = picked?.until ?: shown.last().until
    val apps = remember(mine, from, until) {
        mine.flatMap { it.apps }.filter { !it.day.isBefore(from) && it.day.isBefore(until) }.groupBy { it.app }
            .map { (k, l) ->
                fun sum(net: String) = l.filter { it.net == net }.sumOf { it.total }
                // on a phone "Hotspot" is what it shared (Android's tethering, on mobile); a metered Wi-Fi there is the car's
                val use = if (isPhone) (if (k == "hotspot") Use(0, sum("mobile"), sum("wifi")) else Use(sum("mobile"), 0, sum("wifi") + sum("hotspot")))
                    else Use(sum("mobile"), sum("hotspot"), sum("wifi"))
                Triple(k, appName(context, k, l.first().name), use)
            }
            .filter { it.third.total > 0 }.sortedByDescending { it.third.mobile * 4 + it.third.hotspot * 2 + it.third.wifi } // what costs first
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp
        val head: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (!DataStats.hasAccess(context)) Notice(stringResource(R.string.data_usage_access_notice), stringResource(R.string.common_open)) {
                    runCatching { context.startActivity(DataStats.accessIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                // the plan is the phone's: a tablet has none of its own and only waits for it
                if (plan == null && PhoneRelay.noCalls(context)) Text(stringResource(R.string.data_plan_arrives),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (plan == null) Notice(stringResource(R.string.data_tell_plan), stringResource(R.string.data_setup), openSetup)
                else if (status != null) PlanHead(plan, status)
                SpanChips(span) { span = it }
                if (span != Span.Day) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = CircleShape) {
                        Row(Modifier.padding(3.dp)) {
                            listOf(false to stringResource(R.string.data_kinds), true to stringResource(R.string.data_apps)).forEach { (a, label) ->
                                Surface(onClick = { byApp = a }, shape = CircleShape, color = if (a == byApp) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent) {
                                    Text(label, Modifier.padding(horizontal = 14.dp, vertical = 5.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                val appView = if (byApp && span != Span.Day) byApps(shown, appDays, stringResource(R.string.data_other_apps)) else null
                Chart(appView?.first ?: shown, span, plan, status, picked, shift, { shift += it }, drops, appView?.second) { picked = if (picked == it) null else it }
                val myDays = remember(mine) { DataFiles.days(if (isPhone) mine else phone) }
                if (plan != null && status != null && plan.limited) CycleChart(plan, status, myDays.associate { it.day to DataRules.counted(plan, it) }, today)
                val metrics = remember(plan, status, mine) { DataInsights.metrics(plan, status, DataFiles.days(mine), today, isPhone) }
                MetricsCard(metrics, isPhone)
                val tips = remember(metrics, mine) { DataInsights.tips(plan, status, metrics, mine.flatMap { it.apps }.filter { status == null || it.day in status.cycle }, isPhone) }
                if (tips.isNotEmpty()) TipsCard(tips)
            }
        }
        val list: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
            item { Text(picked?.let { stringResource(R.string.data_apps_at, it.label + (if (span == Span.Day) ":00" else "")) } ?: stringResource(R.string.data_apps), Modifier.padding(vertical = 8.dp),
                color = dataColor(), fontWeight = FontWeight.Bold) }
            if (apps.isEmpty()) item { Text(if (DataStats.hasAccess(context)) stringResource(R.string.data_nothing_used) else stringResource(R.string.data_needs_access), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val most = apps.maxOfOrNull { it.third.total } ?: 1
            // the ten that matter; the rest, a tap away (the user, 25 Oct: "no need to list every app")
            apps.take(10).forEach { (key, name, use) -> item(key = key) {
                // the phone's hotspot opens into each device that used it, and their apps (the user, 25 Oct: "a V expand option on hotspot")
                if (isPhone && key == "hotspot") HotspotRow(name, use, most, all, from, until) else AppRow(name, use, most) { app = key }
            } }
            if (apps.size > 10) item { TextButton(onClick = { allApps = true }) { Text(stringResource(R.string.data_all_apps_n, apps.size), color = dataColor(), maxLines = 1, overflow = TextOverflow.Ellipsis) } }
        }
        if (wide) Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1.3f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(pad)) { head() }
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = pad) { list() }
        } else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = pad) {
            item { head() }
            list()
        }
    }
    app?.let { key -> AppSheet(key, mine) { app = null } }
    if (allApps) AllApps(apps, { allApps = false }) { app = it }
}

/** The counts last shown: Data opened again starts from them (a few months of one file per device: small). */
private var lastData = emptyMap<String, List<DataMonth>>()

/** [v] counting to its new value when it changes (0.7 s), exact once there. */
@Composable private fun animated(v: Long): Long {
    val f by animateFloatAsState(v.toFloat(), tween(700), label = "count")
    return if (f == v.toFloat()) v else f.toLong()
}

@Composable private fun Notice(text: String, action: String, act: () -> Unit) =
    Surface(color = dataColor().copy(alpha = 0.16f), shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = act) { Text(action, color = dataColor(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }

/** The ring (used of the plan) and the numbers beside it. */
@Composable private fun PlanHead(plan: DataPlan, s: DataStatus) {
    val c = dataColor(); val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val used = animated(s.used); val today = animated(s.today)
    val share = if (plan.limited && s.size > 0) (used.toFloat() / s.size).coerceIn(0f, 1f) else 0f
    val over = plan.limited && used > s.size
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 12.dp.toPx(); val inset = w / 2
                drawArc(track, 0f, 360f, false, Offset(inset, inset), Size(size.width - w, size.height - w), style = Stroke(w))
                drawArc(if (over) Color(0xFFE53935) else c, -90f, 360f * share, false, Offset(inset, inset), Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
            }
            Text(if (plan.limited) "${(used * 100 / s.size.coerceAtLeast(1))} %" else "∞", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val usedText = when (plan.kind) {
                DataPlan.FAIR_USE -> stringResource(R.string.data_used_of_fair, DataRules.bytes(used), DataRules.bytes(s.size))
                DataPlan.LIMITED -> stringResource(R.string.data_used_of, DataRules.bytes(used), DataRules.bytes(s.size))
                else -> stringResource(R.string.data_used_cycle, DataRules.bytes(used))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(usedText, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                Info(stringResource(R.string.data_info_plan_title), stringResource(R.string.data_info_plan))
            }
            Text(stringResource(R.string.data_days_left, s.daysLeft, s.cycle.end.format(DateTimeFormatter.ofPattern("d MMM", uiLocale()))), style = MaterialTheme.typography.bodyMedium)
            Text(if (plan.limited) stringResource(R.string.data_today_budget, DataRules.bytes(today), DataRules.bytes(s.budget)) else stringResource(R.string.data_today_average, DataRules.bytes(today), DataRules.bytes(s.average)), style = MaterialTheme.typography.bodyMedium)
            if (plan.limited) {
                val runsOut = s.projection > s.size
                Text(stringResource(R.string.data_forecast, DataRules.bytes(s.projection), s.cycle.end.minusDays(1).format(DateTimeFormatter.ofPattern("d MMM", uiLocale()))) + if (runsOut) " ⚠" else " ✓",
                    color = if (runsOut) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable private fun SpanChips(span: Span, pick: (Span) -> Unit) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = CircleShape) {
        Row(Modifier.padding(4.dp)) {
            Span.entries.forEach { s ->
                Surface(onClick = { pick(s) }, shape = CircleShape, color = if (s == span) dataColor() else Color.Transparent,
                    contentColor = if (s == span) onData() else MaterialTheme.colorScheme.onSurface) { Text(stringResource(s.label), Modifier.padding(horizontal = 11.dp, vertical = 8.dp), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/**
 * Bars of mobile (lime) and Wi-Fi (blue) stacked; the axis's top is a round number just over the tallest, so a 3 MB hour
 * and a 40 GB month both fill it (the old app stopped at 2 GB). In a cycle, the running total and the plan's line too.
 */
@Composable private fun Chart(bars: List<Bar>, span: Span, plan: DataPlan?, status: DataStatus?, picked: Bar?, shift: Int, move: (Int) -> Unit, drops: List<java.time.LocalDateTime> = emptyList(),
                              series: List<Pair<String, Color>>? = null, pick: (Bar) -> Unit) {
    val lime = dataColor(); val ink = MaterialTheme.colorScheme.onSurface; val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    // the same bars with new counts (today's from Android after the written ones) grow to them; another span or time is drawn at once
    val grow = remember { androidx.compose.animation.core.Animatable(1f) }
    var was by remember { mutableStateOf(bars) }
    var now by remember { mutableStateOf(bars) }
    LaunchedEffect(bars) {
        val same = now.size == bars.size && now.zip(bars).all { (a, b) -> a.label == b.label && a.from == b.from && a.parts.size == b.parts.size } && now.any { it.total > 0 }
        was = if (same) now.mapIndexed { i, a -> mix(was.getOrElse(i) { a }, a, grow.value) } else bars
        now = bars
        grow.snapTo(if (same) 0f else 1f)
        if (same) grow.animateTo(1f, tween(700))
    }
    val drawn = now.mapIndexed { i, b -> mix(was.getOrElse(i) { b }, b, grow.value) }
    val step = DataRules.axisStep(bars.maxOf { it.total })
    val lines = DataRules.axisLines(bars.maxOf { it.total }); val top = step * lines
    // the dashed line: the average of the days used so far (not today's, still running), on the bars' own scale
    val used = bars.filter { !it.forecast && it.total > 0 && !(span != Span.Day && span != Span.Year && it.from == LocalDate.now()) }
    val average = if (span == Span.Day || used.isEmpty()) null else used.sumOf { it.total } / used.size
    val title = when (span) {
        Span.Day -> bars.first().from.format(DateTimeFormatter.ofPattern("EEE d MMM", uiLocale()))
        Span.Year -> "${bars.first().from.year}"
        else -> bars.first().from.format(DateTimeFormatter.ofPattern("d MMM", uiLocale())) + " – " + bars.last().from.format(DateTimeFormatter.ofPattern("d MMM", uiLocale()))
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { move(-1) }) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.data_earlier)) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Info(stringResource(R.string.data_info_bars_title), stringResource(R.string.data_info_bars))
                }
                val real = bars.filter { !it.forecast }
                Text(if (series != null) stringResource(R.string.data_apps_total, DataRules.bytes(real.sumOf { it.total })) else Use(real.sumOf { it.mobile }, real.sumOf { it.hotspot }, real.sumOf { it.wifi }, car = real.sumOf { it.car }).shown(always = true),
                    style = MaterialTheme.typography.bodySmall, color = quiet)
            }
            IconButton(onClick = { move(1) }, enabled = shift < 0) { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = stringResource(R.string.data_later)) }
        }
        Row {
            Column(Modifier.height(200.dp).padding(end = 6.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
                (lines downTo 0).forEach { Text(DataRules.bytes(step * it), style = MaterialTheme.typography.labelSmall, color = quiet) }
            }
            Canvas(Modifier.weight(1f).height(200.dp).pointerInput(bars) {
                detectTapGestures { o -> bars.getOrNull((o.x / size.width * bars.size).toInt())?.let(pick) }
            }) {
                val grid = quiet.copy(alpha = 0.25f)
                for (i in 0..lines) { val y = size.height * i / lines; drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f) }
                val slot = size.width / bars.size; val w = slot * 0.7f
                fun y(v: Long) = size.height - size.height * (v.toDouble() / top).toFloat()
                drawn.forEachIndexed { i, b ->
                    val x = i * slot + (slot - w) / 2
                    val dim = picked != null && picked != bars.getOrNull(i)
                    // stacked from the bottom: mobile, the hotspot, Wi-Fi; a forecast day faint, outlined
                    var base = 0L
                    for ((v, color) in if (series != null) b.parts.zip(series.map { it.second }) else listOf(b.mobile to lime, b.hotspot to HOTSPOT_COLOR, b.wifi to WIFI_COLOR, b.car to CAR_COLOR)) {
                        if (v > 0) {
                            val c = if (b.forecast) color.copy(alpha = 0.22f) else if (dim) color.copy(alpha = 0.35f) else color
                            drawRect(c, Offset(x, y(base + v)), Size(w, y(base) - y(base + v)))
                            if (b.forecast) drawRect(color.copy(alpha = 0.6f), Offset(x, y(base + v)), Size(w, y(base) - y(base + v)), style = Stroke(1.5f))
                        }
                        base += v
                    }
                }
                average?.let { a -> drawLine(ink.copy(alpha = 0.7f), Offset(0f, y(a)), Offset(size.width, y(a)), 2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))) }
            }
        }
        // the signal's drops (every device's) as red marks under their bars
        val marked = bars.map { b -> drops.any { t -> !t.toLocalDate().isBefore(b.from) && t.toLocalDate().isBefore(b.until) && (span != Span.Day || t.hour / 2 * 2 == b.label.toIntOrNull()) } }
        if (marked.any { it }) Row(Modifier.padding(start = 44.dp).height(10.dp)) {
            marked.forEach { m -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { if (m) Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFFE53935))) } }
        }
        Row(Modifier.padding(start = 44.dp)) {
            val every = maxOf(1, bars.size / 8)
            // a label wider than its bar's slot spills over its empty neighbours instead of being cut
            bars.forEachIndexed { i, b -> Box(Modifier.weight(1f).wrapContentWidth(unbounded = true)) {
                if (i % every == 0) Text(b.label, style = MaterialTheme.typography.labelSmall, color = quiet, softWrap = false, overflow = TextOverflow.Visible)
            } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            (series ?: listOfNotNull(stringResource(R.string.data_mobile) to lime, (stringResource(R.string.data_hotspot) to HOTSPOT_COLOR).takeIf { bars.any { it.hotspot > 0 } }, "Wi-Fi" to WIFI_COLOR, ("Android Auto" to CAR_COLOR).takeIf { bars.any { it.car > 0 } })).forEach { (n, c) ->
                Box(Modifier.size(10.dp).clip(CircleShape).background(c)); Text(n, style = MaterialTheme.typography.labelSmall, color = quiet, maxLines = 1)
            }
        }
        picked?.let { b -> Text("${b.label}${if (span == Span.Day) ":00" else ""}: " + (if (b.forecast) stringResource(R.string.data_forecast_word) + " " else "") +
            (if (series != null) series.zip(b.parts).filter { it.second > 0 }.joinToString(" · ") { "${it.first.first} ${DataRules.bytes(it.second)}" } else Use(b.mobile, b.hotspot, b.wifi, car = b.car).shown(always = true)),
            style = MaterialTheme.typography.bodySmall) }
        average?.let { a -> Text(stringResource(R.string.data_dashed, DataRules.bytes(a)) + (if (bars.any { it.forecast }) " · " + stringResource(R.string.data_faint) else ""),
            style = MaterialTheme.typography.labelSmall, color = quiet) }
        if (marked.any { it }) Text(stringResource(R.string.data_red_drops), style = MaterialTheme.typography.labelSmall, color = quiet)
    }
}

/** What was used: on mobile, over another phone's hotspot (the tablet's, the computer's), on plain Wi-Fi. */
internal data class Use(val mobile: Long, val hotspot: Long, val wifi: Long, val ethernet: Long = 0, val car: Long = 0) {
    val total get() = mobile + hotspot + wifi + ethernet + car
    /** [word]: a string's words (Mobile, Hotspot; Wi-Fi, Ethernet and Android Auto are the same in every language). */
    fun words(always: Boolean = false, word: (Int) -> String) = listOfNotNull(
        (mobile > 0 || always && hotspot == 0L).takeIf { it }?.let { "${word(R.string.data_mobile)} ${DataRules.bytes(mobile)}" },
        hotspot.takeIf { it > 0 }?.let { "${word(R.string.data_hotspot)} ${DataRules.bytes(it)}" },
        (wifi > 0 || always).takeIf { it }?.let { "Wi-Fi ${DataRules.bytes(wifi)}" },
        ethernet.takeIf { it > 0 }?.let { "Ethernet ${DataRules.bytes(it)}" }, car.takeIf { it > 0 }?.let { "Android Auto ${DataRules.bytes(it)}" }).joinToString(" · ")
}

/** [Use.words] in the UI's language. */
@Composable private fun Use.shown(always: Boolean = false): String { val c = LocalContext.current; return words(always) { c.getString(it) } }

private val APP_COLORS = listOf(Color(0xFFFF9F1C), Color(0xFF4F86E8), Color(0xFFE91E63), Color(0xFF26C6DA), Color(0xFFAB47BC), Color(0xFFFFCA28))

/** The bars by app: the six that used most over the bars shown, each its colour, the rest grey. */
private fun byApps(bars: List<Bar>, appDays: List<Triple<LocalDate, String, Long>>, otherApps: String): Pair<List<Bar>, List<Pair<String, Color>>> {
    val from = bars.first().from; val until = bars.last().until
    val inRange = appDays.filter { !it.first.isBefore(from) && it.first.isBefore(until) }
    val top = inRange.groupBy { it.second }.mapValues { (_, l) -> l.sumOf { it.third } }.entries.sortedByDescending { it.value }.take(6).map { it.key }
    val series = top.mapIndexed { i, n -> n to APP_COLORS[i] } + (otherApps to Color(0xFF8A8F94))
    return bars.map { b ->
        if (b.forecast) b.copy(parts = series.map { 0L })
        else inRange.filter { !it.first.isBefore(b.from) && it.first.isBefore(b.until) }.let { l ->
            b.copy(parts = top.map { n -> l.filter { it.second == n }.sumOf { it.third } } + l.filter { it.second !in top }.sumOf { it.third })
        }
    } to series
}

/** The hotspot's line, opening ▾ into the devices that used it and, in each, its apps — from their own files, as they counted. */
@Composable private fun HotspotRow(name: String, use: Use, most: Long, all: Map<String, List<DataMonth>>, from: LocalDate, until: LocalDate) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val devices = remember(all, from, until) {
        all.filterKeys { it != DataFiles.device() }.map { (d, months) ->
            val apps = months.flatMap { it.apps }.filter { it.net == "hotspot" && !it.day.isBefore(from) && it.day.isBefore(until) }
                .groupBy { appName(context, it.app, it.name.ifBlank { it.app }) }.map { (n, l) -> n to l.sumOf { it.total } }.sortedByDescending { it.second }
            Triple(DataFiles.label(all, d), apps.sumOf { it.second }, apps)
        }.filter { it.second > 0 }.sortedByDescending { it.second }
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { AppRow(name, use, most) { open = !open } }
            IconButton(onClick = { open = !open }) {
                Icon(painterResource(if (open) R.drawable.ic_keyboard_arrow_up else R.drawable.ic_keyboard_arrow_down), contentDescription = if (open) stringResource(R.string.common_close) else stringResource(R.string.data_who_used))
            }
        }
        if (open) Column(Modifier.padding(start = 20.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (devices.isEmpty()) Text(stringResource(R.string.data_counts_not_yet), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            devices.forEach { (device, total, apps) ->
                Text("$device · ${DataRules.bytes(total)}", style = MaterialTheme.typography.titleSmall)
                apps.take(6).forEach { (n, v) ->
                    Row(Modifier.padding(start = 12.dp)) {
                        Text(n, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(DataRules.bytes(v), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (apps.size > 6) Text(stringResource(R.string.data_and_more, apps.size - 6, DataRules.bytes(apps.drop(6).sumOf { it.second })), Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val counted = devices.sumOf { it.second }
            if (devices.isNotEmpty() && use.hotspot > counted) Text(stringResource(R.string.data_not_counted, DataRules.bytes(use.hotspot - counted)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Every app and service of the chosen days, the small ones too. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AllApps(apps: List<Triple<String, String, Use>>, close: () -> Unit, open: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = close) {
        val most = apps.maxOfOrNull { it.third.total } ?: 1
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Text(stringResource(R.string.data_all_apps), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp)) }
            apps.forEach { (key, name, use) -> item(key = key) { AppRow(name, use, most) { open(key) } } }
        }
    }
}

/** The cycle so far against the limit, and the forecast to its end — on a scale of its own, 0 to the limit. */
@Composable private fun CycleChart(plan: DataPlan, s: DataStatus, history: Map<LocalDate, Long>, today: LocalDate) {
    val lime = dataColor(); val ink = MaterialTheme.colorScheme.onSurface; val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val days = s.cycle.days
    val ahead = generateSequence(today.plusDays(1)) { it.plusDays(1) }.takeWhile { it.isBefore(s.cycle.end) }.map { DataRules.forecast(history, it, today) }.toList()
    val top = maxOf(s.size, s.projection)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.data_cycle_so_far), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Info(stringResource(R.string.data_cycle_so_far), stringResource(R.string.data_info_cycle, s.cycle.start.format(DateTimeFormatter.ofPattern("d MMM", uiLocale()))))
            }
            Row {
                Column(Modifier.height(130.dp).padding(end = 6.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
                    Text(DataRules.bytes(top), style = MaterialTheme.typography.labelSmall, color = quiet)
                    Text(DataRules.bytes(top / 2), style = MaterialTheme.typography.labelSmall, color = quiet)
                    Text("0", style = MaterialTheme.typography.labelSmall, color = quiet)
                }
                Canvas(Modifier.weight(1f).height(130.dp)) {
                    fun x(i: Int) = size.width * i / days
                    fun y(v: Long) = size.height - size.height * (v.toDouble() / top).toFloat()
                    drawLine(ink.copy(alpha = 0.5f), Offset(0f, y(s.size)), Offset(size.width, y(s.size)), 2f)
                    var acc = 0L; var last = Offset(0f, size.height)
                    s.perDay.forEachIndexed { i, v -> acc += v; val pt = Offset(x(i + 1), y(acc)); drawLine(lime, last, pt, 5f, StrokeCap.Round); last = pt }
                    var fAcc = s.used; var i = s.perDay.size
                    ahead.forEach { v -> fAcc += v; i++; val pt = Offset(x(i), y(fAcc)); drawLine(lime.copy(alpha = 0.6f), last, pt, 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))); last = pt }
                }
            }
            Text(stringResource(R.string.data_cycle_line, DataRules.bytes(s.used), DataRules.bytes(s.projection), s.cycle.end.minusDays(1).format(DateTimeFormatter.ofPattern("d MMM", uiLocale())), DataRules.bytes(s.size)),
                style = MaterialTheme.typography.bodySmall, color = quiet)
        }
    }
}

/** A few numbers under the charts. */
@Composable private fun MetricsCard(m: DataMetrics, phone: Boolean) {
    val b = DataRules::bytes
    val tiles = listOfNotNull(
        stringResource(if (phone) R.string.data_metric_mobile_day else R.string.data_metric_day) to b(m.average),
        (stringResource(R.string.data_metric_week) to "${b(m.workdays)} · ${b(m.weekends)}").takeIf { m.workdays + m.weekends > 0 },
        m.busiest?.let { (d, v) -> stringResource(R.string.data_metric_busiest) to "${d.format(DateTimeFormatter.ofPattern("d MMM", uiLocale()))} · ${b(v)}" },
        (stringResource(if (phone) R.string.data_metric_through_hotspot else R.string.data_metric_over_hotspot) to "${m.hotspotShare} %").takeIf { m.hotspotShare > 0 },
        stringResource(R.string.data_metric_wifi) to "${m.wifiShare} %",
    )
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.data_in_numbers), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Info(stringResource(R.string.data_in_numbers_title), stringResource(if (phone) R.string.data_info_numbers_phone else R.string.data_info_numbers_device))
            }
            tiles.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (k, v) -> Column(Modifier.weight(1f)) {
                        Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(v, style = MaterialTheme.typography.titleMedium)
                    } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** Help for the plan, from the numbers above: information, nothing changed. */
@Composable private fun TipsCard(tips: List<Advice>) {
    val context = LocalContext.current
    Surface(color = dataColor().copy(alpha = 0.12f), shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.data_for_plan), style = MaterialTheme.typography.titleSmall, color = dataColor(), modifier = Modifier.weight(1f))
                Info(stringResource(R.string.data_for_plan), stringResource(R.string.data_info_tips))
            }
            tips.forEach { t -> Column {
                Text(t.title.text(context), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(t.text.text(context), style = MaterialTheme.typography.bodyMedium)
            } }
            // the same advice, asked of AI Assist, which sees the numbers behind it (data tips, apps, days) and answers about them
            if (AiGate.on) TextButton(onClick = { askAssistant(context, context.getString(R.string.data_ask_prompt)) }) {
                Icon(painterResource(R.drawable.ic_sparkle), null, Modifier.size(18.dp), tint = dataColor()); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.data_ask), color = dataColor())
            }
        }
    }
}

@Composable private fun AppRow(name: String, use: Use, most: Long, open: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = open).padding(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(use.shown(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)) {
            val parts = listOf(use.mobile to dataColor(), use.hotspot to HOTSPOT_COLOR, use.wifi to WIFI_COLOR).map { (v, c) -> v.toFloat() / most.coerceAtLeast(1) to c }
            parts.forEach { (f, c) -> if (f > 0) Box(Modifier.weight(f).fillMaxHeight().background(c)) }
            val rest = 1 - parts.sumOf { it.first.toDouble() }.toFloat()
            if (rest > 0.001f) Spacer(Modifier.weight(rest))
        }
    }
}

/** An app's page: its days, foreground and background, roaming, and Android's settings for it (to restrict its data). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AppSheet(key: String, months: List<DataMonth>, close: () -> Unit) {
    val context = LocalContext.current
    val rows = remember(key, months) { months.flatMap { it.apps }.filter { it.app == key } }
    val today = LocalDate.now()
    val days = (29 downTo 0).map { today.minusDays(it.toLong()) }.map { d -> rows.filter { it.day == d }.let { l ->
        Bar("${d.dayOfMonth}", d, d.plusDays(1), l.filter { it.net == "mobile" }.sumOf { it.total }, l.filter { it.net == "wifi" }.sumOf { it.total }, l.filter { it.net == "hotspot" }.sumOf { it.total }) } }
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(appName(context, key, rows.firstOrNull()?.name ?: key), style = MaterialTheme.typography.titleLarge)
            Chart(days, Span.Month, null, null, null, 0, {}) {}
            val mobile = rows.filter { it.net == "mobile" }
            Text(stringResource(R.string.data_last_30, DataRules.bytes(mobile.filter { !it.day.isBefore(today.minusDays(29)) }.sumOf { it.total })))
            Text(stringResource(R.string.data_fg_bg, DataRules.bytes(mobile.sumOf { it.fg }), DataRules.bytes(mobile.sumOf { it.bg }), DataRules.bytes(mobile.sumOf { it.roaming })),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(Use(0, rows.filter { it.net == "hotspot" }.sumOf { it.total }, rows.filter { it.net == "wifi" }.sumOf { it.total }).shown().ifEmpty { stringResource(R.string.data_no_wifi) }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if ('.' in key) TextButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", key, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text(stringResource(R.string.data_app_settings)) }
        }
    }
}

// ---- Devices: the three together, and the phone's hotspot ----

@Composable private fun DevicesTab(all: Map<String, List<DataMonth>>, pad: PaddingValues) {
    val today = LocalDate.now()
    val plan = DataFiles.plan()
    val cycle = DataRules.cycle(plan ?: DataPlan(), today)
    fun inCycle(m: List<DataMonth>) = DataFiles.days(m).filter { it.day in cycle }
    val devices = all.mapValues { (_, m) -> inCycle(m) }.filterValues { it.isNotEmpty() }
    val phone = devices.maxByOrNull { (_, d) -> d.sumOf { it.mobile } }?.key
    val hotspot = devices[phone].orEmpty().sumOf { it.hotspot }
    val clients = devices.filterKeys { it != phone }.mapValues { (_, d) -> d.sumOf { it.viaHotspot } }.filterValues { it > 0 }
    val thisOne = stringResource(R.string.data_this_one)
    val label = { d: String -> DataFiles.label(all, d) + if (d == DataFiles.device()) " ($thisOne)" else "" }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.data_cycle_from, cycle.start.format(DateTimeFormatter.ofPattern("d MMM", uiLocale()))), color = dataColor(), fontWeight = FontWeight.Bold) }
        if (devices.isEmpty()) item { Text(stringResource(R.string.data_nothing_counted), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        val most = devices.values.maxOfOrNull { d -> d.sumOf { it.mobile + it.wifi + it.ethernet } } ?: 1
        devices.forEach { (d, days) -> item(key = d) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label(d), style = MaterialTheme.typography.titleSmall)
                val parts = days.map { split(it, d == phone) }
                AppRow("", Use(parts.sumOf { it.first }, parts.sumOf { it.second }, parts.sumOf { it.third }, days.sumOf { it.ethernet }, days.sumOf { carOf(it, d == phone) }), most) {}
            }
        } }
        if (hotspot > 0 || clients.isNotEmpty()) item {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.data_phones_hotspot, DataRules.bytes(hotspot)), style = MaterialTheme.typography.titleMedium)
                    val rows: List<Pair<String?, Long>> = clients.entries.sortedByDescending { it.value }.map { it.key to it.value } + (null to (hotspot - clients.values.sum())) // null: the difference
                    rows.forEachIndexed { i, (who, b) ->
                        val share = if (hotspot > 0) (b * 100 / hotspot).toInt() else 0
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text((who?.let(label) ?: stringResource(R.string.data_difference)) + if (i == 0 && who != null) "  · " + stringResource(R.string.data_the_most) else "", Modifier.weight(1f))
                            Text("${DataRules.bytes(maxOf(0, b))}${if (b < 0) " (−${DataRules.bytes(-b)})" else ""} · $share %", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text(stringResource(R.string.data_hotspot_explain), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ---- the stopwatch: data used between Start and Stop (a trip, a film) ----

@Composable private fun Stopwatch() {
    val context = LocalContext.current
    val p = remember { context.getSharedPreferences("data", Context.MODE_PRIVATE) }
    var started by remember { mutableLongStateOf(p.getLong("swAt", 0)) }
    var laps by remember { mutableStateOf(p.getString("swLaps", "").orEmpty().lines().filter { it.isNotBlank() }) }
    fun mobile() = TrafficStats.getMobileRxBytes() + TrafficStats.getMobileTxBytes()
    fun total() = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes()
    val locale = uiLocale()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.data_stopwatch), style = MaterialTheme.typography.titleMedium)
        if (started == 0L) TextButton(onClick = {
            started = System.currentTimeMillis()
            p.edit().putLong("swAt", started).putLong("swMobile", mobile()).putLong("swTotal", total()).apply()
        }, colors = sheetButton()) { Text(stringResource(R.string.data_start_counting), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        else {
            val m = mobile() - p.getLong("swMobile", 0); val t = total() - p.getLong("swTotal", 0)
            Text(stringResource(R.string.data_counting_since, java.time.Instant.ofEpochMilli(started).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM HH:mm", locale)),
                DataRules.bytes(maxOf(0, m)), DataRules.bytes(maxOf(0, t - m))))
            TextButton(onClick = {
                val lap = context.getString(R.string.data_lap, java.time.Instant.ofEpochMilli(started).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM HH:mm", locale)), DataRules.bytes(maxOf(0, m)), DataRules.bytes(maxOf(0, t - m)))
                laps = (listOf(lap) + laps).take(10); started = 0
                p.edit().putLong("swAt", 0).putString("swLaps", laps.joinToString("\n")).apply()
            }, colors = sheetButton()) { Text(stringResource(R.string.common_stop), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        laps.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(stringResource(R.string.data_stopwatch_hint), style = MaterialTheme.typography.labelSmall)
    }
}

// ---- Setup: one step a page (the user, 25 Oct: "a Setup page that takes you by the hand step by step") ----

@Composable
private fun DataSetup(old: DataPlan?, months: List<DataMonth>, done: () -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    var plan by remember { mutableStateOf(old ?: DataPlan(kind = DataPlan.FAIR_USE, size = 300_000_000_000, day = LocalDate.now().dayOfMonth)) }
    var usedSoFar by remember { mutableStateOf("") }
    val prefs = remember { context.getSharedPreferences("data", Context.MODE_PRIVATE) }
    var live by remember { mutableStateOf(DataSpeed.enabled(context)) }
    var budgetWarn by remember { mutableStateOf(prefs.getBoolean("warnBudget", false)) }
    var resumed by remember { mutableIntStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumed++ }
        lifecycle.lifecycle.addObserver(o); onDispose { lifecycle.lifecycle.removeObserver(o) }
    }
    val access = remember(resumed) { DataStats.hasAccess(context) }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumed++ }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resumed++ }
    val steps = listOf(R.string.data_step_welcome, R.string.data_step_access, R.string.data_step_plan, R.string.data_step_renews, R.string.data_step_roaming, R.string.data_step_used,
        R.string.data_step_warnings, R.string.data_live_speed, R.string.data_step_wifi, R.string.common_done)
    fun finish() {
        var p = plan
        DataRules.parseSize(usedSoFar)?.let { typed ->
            val st = DataRules.status(p.copy(offsetStart = null, offset = 0), DataFiles.days(months), LocalDate.now())
            p = p.copy(offsetStart = st.cycle.start, offset = typed - st.used)
        }
        DataFiles.savePlan(context, p)
        prefs.edit().putBoolean("warnBudget", budgetWarn).apply()
        DataSpeed.setEnabled(context, live)
        done()
    }
    BackHandler { if (step > 0) step-- else back() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.data_setup_progress, step + 1, steps.size), style = MaterialTheme.typography.labelLarge, color = dataColor())
            Text(stringResource(steps[step]), style = MaterialTheme.typography.headlineMedium)
            when (step) {
                0 -> {
                    Text(stringResource(R.string.data_setup_welcome))
                    Text(stringResource(R.string.data_setup_private),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                1 -> {
                    Text(stringResource(R.string.data_setup_access))
                    Text(if (access) stringResource(R.string.data_access_on) else stringResource(R.string.data_access_off), color = if (access) dataColor() else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    if (!access) Button(onClick = {
                        runCatching { context.startActivity(DataStats.accessIntent(context)) }.onFailure { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                    }) { Text(stringResource(R.string.data_open_android_page)) }
                    Text(stringResource(R.string.data_skip_access), style = MaterialTheme.typography.bodySmall)
                }
                2 -> {
                    Choice(listOf(DataPlan.LIMITED to stringResource(R.string.data_plan_limited), DataPlan.FAIR_USE to stringResource(R.string.data_plan_fair), DataPlan.UNLIMITED to stringResource(R.string.data_plan_unlimited)), plan.kind) { plan = plan.copy(kind = it) }
                    when (plan.kind) {
                        DataPlan.FAIR_USE -> Text(stringResource(R.string.data_fair_hint), style = MaterialTheme.typography.bodyMedium)
                        DataPlan.UNLIMITED -> Text(stringResource(R.string.data_unlimited_hint), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (plan.limited) SizeField(if (plan.kind == DataPlan.FAIR_USE) stringResource(R.string.data_the_limit) else stringResource(R.string.data_plan_size), plan.size) { plan = plan.copy(size = it) }
                    if (plan.limited) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.data_rollover), Modifier.weight(1f)); Switch(plan.rollover, { plan = plan.copy(rollover = it) })
                    }
                }
                3 -> {
                    Choice(listOf(DataPlan.MONTH to stringResource(R.string.data_monthly), DataPlan.WEEK to stringResource(R.string.data_weekly), DataPlan.DAYS to stringResource(R.string.data_every_n)), plan.every) {
                        plan = plan.copy(every = it, day = if (it == DataPlan.WEEK) LocalDate.now().dayOfWeek.value else LocalDate.now().dayOfMonth, from = LocalDate.now())
                    }
                    when (plan.every) {
                        DataPlan.MONTH -> { Text(stringResource(R.string.data_on_day)); Choice((1..31).map { it to "$it" }, plan.day) { plan = plan.copy(day = it) } }
                        DataPlan.WEEK -> Choice(DayOfWeek.entries.map { it.value to it.getDisplayName(DateStyle.SHORT, uiLocale()) }, plan.day) { plan = plan.copy(day = it) }
                        else -> { Stepper(stringResource(R.string.data_every), plan.days, 1..365, " " + stringResource(R.string.data_days)) { plan = plan.copy(days = it) }; Text(stringResource(R.string.data_counting_from, plan.from.toString())) }
                    }
                    val c = DataRules.cycle(plan, LocalDate.now())
                    Text(stringResource(R.string.data_this_cycle, c.start.format(DateTimeFormatter.ofPattern("d MMM", uiLocale())), c.end.minusDays(1).format(DateTimeFormatter.ofPattern("d MMM", uiLocale()))), color = dataColor())
                    if (plan.limited) SizeField(stringResource(R.string.data_daily_quota), plan.daily, optional = true) { plan = plan.copy(daily = it) }
                }
                4 -> {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.data_eu_counts), Modifier.weight(1f)); Switch(plan.euCounts, { plan = plan.copy(euCounts = it) }) }
                    Text(stringResource(R.string.data_day_pass_q), style = MaterialTheme.typography.titleMedium)
                    var price by remember { mutableStateOf(if (plan.passPrice > 0) "%.0f".format(plan.passPrice) else "") }
                    OutlinedTextField(price, { price = it; plan = plan.copy(passPrice = it.replace(',', '.').toDoubleOrNull() ?: 0.0) }, label = { Text(stringResource(R.string.data_pass_price)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (plan.passPrice > 0) SizeField(stringResource(R.string.data_pass_size), plan.passSize.takeIf { it > 0 } ?: 300_000_000) { plan = plan.copy(passSize = it) }
                    Text(stringResource(R.string.data_roaming_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                5 -> {
                    Text(stringResource(R.string.data_used_so_far_hint))
                    OutlinedTextField(usedSoFar, { usedSoFar = it }, label = { Text(stringResource(R.string.data_used_so_far)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (usedSoFar.isNotBlank() && DataRules.parseSize(usedSoFar) == null) Text(stringResource(R.string.data_write_size_41), color = MaterialTheme.colorScheme.error)
                }
                6 -> {
                    Text(stringResource(R.string.data_warnings_at), style = MaterialTheme.typography.titleMedium)
                    listOf(50, 80, 90, 100).forEach { w -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$w %", Modifier.weight(1f)); Checkbox(w in plan.warn, { on -> plan = plan.copy(warn = (if (on) plan.warn + w else plan.warn - w).sorted()) })
                    } }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.data_budget_passed), Modifier.weight(1f)); Switch(budgetWarn, { budgetWarn = it }) }
                    if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        Button(onClick = { askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text(stringResource(R.string.data_allow_notifications)) }
                }
                7 -> {
                    Text(stringResource(R.string.data_live_speed_hint))
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.data_live_speed), Modifier.weight(1f)); Switch(live, { live = it }) }
                }
                8 -> {
                    Text(stringResource(R.string.data_wifi_around_hint))
                    val has = remember(resumed) { context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED }
                    if (has) Text(stringResource(R.string.data_location_allowed), color = dataColor()) else Button(onClick = { askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text(stringResource(R.string.data_allow_location)) }
                }
                else -> {
                    Text(when (plan.kind) {
                        DataPlan.UNLIMITED -> stringResource(R.string.data_ready_unlimited)
                        else -> stringResource(if (plan.kind == DataPlan.FAIR_USE) R.string.data_ready_fair else R.string.data_ready_limited, DataRules.bytes(plan.size),
                            DataRules.cycle(plan, LocalDate.now()).end.format(DateTimeFormatter.ofPattern("d MMM", uiLocale())))
                    })
                    Text(stringResource(R.string.data_change_later), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (step > 0) step-- else back() }) { Text(if (step == 0) stringResource(R.string.common_cancel) else "‹ " + stringResource(R.string.common_back), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                steps.indices.forEach { i -> Box(Modifier.padding(3.dp).size(if (i == step) 9.dp else 6.dp).clip(CircleShape).background(if (i <= step) dataColor() else MaterialTheme.colorScheme.outlineVariant)) }
            }
            Button(onClick = { if (step == steps.lastIndex) finish() else step++ },
                enabled = !(step == 5 && usedSoFar.isNotBlank() && DataRules.parseSize(usedSoFar) == null),
                colors = ButtonDefaults.buttonColors(containerColor = dataColor(), contentColor = onData())) { Text(if (step == steps.lastIndex) stringResource(R.string.common_done) else stringResource(R.string.common_next), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable private fun <T> Choice(options: List<Pair<T, String>>, chosen: T, pick: (T) -> Unit) =
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, label) -> FilterChip(v == chosen, { pick(v) }, { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = dataColor(), selectedLabelColor = onData())) }
    }

@Composable private fun Stepper(label: String, value: Int, range: IntRange, unit: String = "", set: (Int) -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
    Text(label, Modifier.weight(1f))
    IconButton(onClick = { set((value - 1).coerceIn(range)) }) { Text("−", style = MaterialTheme.typography.titleLarge) }
    Text("$value$unit", style = MaterialTheme.typography.titleMedium)
    IconButton(onClick = { set((value + 1).coerceIn(range)) }) { Text("+", style = MaterialTheme.typography.titleLarge) }
}

@Composable private fun SizeField(label: String, bytes: Long, optional: Boolean = false, set: (Long) -> Unit) {
    var text by remember { mutableStateOf(if (bytes > 0) DataRules.bytes(bytes) else "") }
    val bad = text.isNotBlank() && DataRules.parseSize(text) == null
    OutlinedTextField(text, { text = it; DataRules.parseSize(it)?.let(set) ?: run { if (optional && it.isBlank()) set(0) } }, label = { Text(label) }, singleLine = true,
        isError = bad, supportingText = { Text(if (bad) stringResource(R.string.data_write_size_300) else stringResource(R.string.data_number_is_gb)) }, modifier = Modifier.fillMaxWidth())
}
