package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.drawText
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One Wi-Fi reading: dBm, link speed (Mb/s), frequency (MHz). */
internal data class WifiReading(val rssi: Int, val speed: Int, val mhz: Int, val ssid: String?)

/** A saved spot: where (its name), in which round (where the router was), and what was measured there. */
internal data class DataSpot(val at: String, val spot: String, val round: String, val rssi: Int, val speed: Int, val mhz: Int, val down: Double = 0.0, val up: Double = 0.0, val ping: Long = 0)

/**
 * Data › Signal (Plan.md § Data): the Wi-Fi meter, the spots to find the best place without a map (the user, 25 Oct: "make
 * the strength meter, if we can find it that way"), the networks around, and the signal's drops.
 */
internal object DataSignal {
    // ---- the rules, kept pure for the tests ----

    fun quality(c: Context, rssi: Int): String = c.getString(when {
        rssi >= -55 -> R.string.data_q_excellent; rssi >= -67 -> R.string.data_q_good; rssi >= -75 -> R.string.data_q_fair; rssi >= -85 -> R.string.data_q_poor; else -> R.string.data_q_none
    })
    fun band(mhz: Int) = when { mhz >= 5925 -> "6 GHz"; mhz >= 4900 -> "5 GHz"; mhz > 0 -> "2.4 GHz"; else -> "" }
    fun channel(mhz: Int) = when {
        mhz == 2484 -> 14; mhz in 2412..2472 -> (mhz - 2407) / 5; mhz in 5000..5895 -> (mhz - 5000) / 5; mhz >= 5955 -> (mhz - 5950) / 5; else -> 0
    }
    /** The sound mode's pause between beeps: short when strong, long when weak (a metal detector's way). */
    fun beepEvery(rssi: Int) = (150 + (-30 - rssi).coerceIn(0, 60) * 25).toLong()

    /**
     * The 2.4 GHz channels 1, 6 and 11 rated by the networks heard on or near them (within four channels, as they
     * overlap), the stronger counting more; and 5 GHz's by their own channel. Least crowded first.
     */
    private fun weight(rssi: Int) = ((rssi + 100).coerceIn(0, 70)) / 70.0 + 0.2
    /** How crowded channel [c] is: the networks on it or overlapping it (2.4 GHz: within four channels), the stronger counting more. */
    fun crowdingAt(networks: List<Pair<Int, Int>>, c: Int) = networks.filter { (ch, _) -> if (c <= 14) ch in 1..14 && kotlin.math.abs(ch - c) < 5 else ch == c }.sumOf { weight(it.second) }
    fun crowding(networks: List<Pair<Int, Int>>): List<Pair<Int, Double>> { // (channel, rssi)
        val low = listOf(1, 6, 11).map { c -> c to crowdingAt(networks, c) }
        val high = networks.filter { it.first > 14 }.groupBy { it.first }.map { (c, l) -> c to l.sumOf { weight(it.second) } }
        return (low + high).sortedBy { it.second }
    }

    /** Rounds side by side: each round's average over its spots (each spot's newest), best first. */
    fun rounds(spots: List<DataSpot>): List<Pair<String, Double>> = spots.groupBy { it.round }
        .map { (r, l) -> r to l.groupBy { it.spot }.values.map { s -> s.maxBy { it.at }.rssi }.average() }.sortedByDescending { it.second }

    /** A rough distance in metres from the signal, as if nothing stood between (free space): a guide, walls make it look farther. */
    fun distance(dbm: Int, mhz: Int): Int = Math.round(Math.pow(10.0, (27.55 - 20 * Math.log10(mhz.toDouble()) + kotlin.math.abs(dbm)) / 20)).toInt()

    /** A drop worth a line: gone at least [minSecs] and back within ten minutes (longer is leaving, not a drop). */
    fun isDrop(secs: Long, minSecs: Long = 5) = secs in minSecs..600

    // ---- the readings ----

    @Suppress("DEPRECATION") // getConnectionInfo still gives RSSI, speed and frequency without the location permission
    fun wifi(c: Context): WifiReading? {
        val info = c.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo ?: return null
        if (info.rssi <= -127 || info.frequency <= 0) return null
        val ssid = info.ssid?.trim('"')?.takeIf { it.isNotEmpty() && !it.contains("unknown ssid", true) }
        return WifiReading(info.rssi, info.linkSpeed, info.frequency, ssid)
    }

    fun located(c: Context) = c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // ---- spots: Tetra/.System/Data/Shared/spots/<device>.jsonl ----

    private fun spotsFile(device: String = DataFiles.device()) = File(File(DataFiles.root(), "spots"), "$device.jsonl")

    fun spotLine(s: DataSpot): String = JSONObject().put("at", s.at).put("spot", s.spot).put("round", s.round).put("rssi", s.rssi).put("speed", s.speed).put("mhz", s.mhz)
        .apply { if (s.down > 0) put("pc", JSONObject().put("down", s.down).put("up", s.up).put("ms", s.ping)) }.toString()

    fun parseSpot(l: String): DataSpot? = runCatching { JSONObject(l).let { o -> val pc = o.optJSONObject("pc")
        DataSpot(o.getString("at"), o.getString("spot"), o.optString("round"), o.getInt("rssi"), o.optInt("speed"), o.optInt("mhz"), pc?.optDouble("down") ?: 0.0, pc?.optDouble("up") ?: 0.0, pc?.optLong("ms") ?: 0) } }.getOrNull()

    fun spots(): List<DataSpot> = File(DataFiles.root(), "spots").listFiles().orEmpty().filter { it.name.endsWith(".jsonl") }.flatMap { f -> f.readLines().mapNotNull(::parseSpot) }

    private fun rewrite(c: Context, keep: (DataSpot) -> Boolean, add: DataSpot? = null) {
        val f = spotsFile()
        val kept = (if (f.isFile) f.readLines() else emptyList()).filter { l -> parseSpot(l)?.let(keep) == true }
        DataFiles.write(f, (kept + listOfNotNull(add?.let(::spotLine))).joinToString("\n", postfix = "\n"))
        ScopedSync.soon(c, "data")
    }
    fun saveSpot(c: Context, s: DataSpot) = rewrite(c, { it.spot != s.spot || it.round != s.round }, s)
    fun deleteSpot(c: Context, spot: String, round: String) = rewrite(c, { it.spot != spot || it.round != round })

    // ---- drops: watched while Tetra's process lives (the listening service usually keeps it so) ----

    fun summaryOn(c: Context) = c.getSharedPreferences("data", Context.MODE_PRIVATE).getBoolean("dropsSummary", false)
    fun setSummary(c: Context, on: Boolean) = c.getSharedPreferences("data", Context.MODE_PRIVATE).edit().putBoolean("dropsSummary", on).apply()

    private var watching = false
    private fun stamp() = ZonedDateTime.now().toOffsetDateTime().withNano(0).toString()

    /** The network and the mobile signal watched for drops and roaming; Android calls back, nothing polls. */
    fun watch(c: Context) {
        val app = c.applicationContext
        if (watching) return
        watching = true
        val cm = app.getSystemService(ConnectivityManager::class.java)
        runCatching { cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            var wifi = false; var lostAt = 0L; var weakSince = 0L
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val now = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                if (now && !wifi && lostAt > 0) {
                    val secs = (System.currentTimeMillis() - lostAt) / 1000
                    if (isDrop(secs)) DataStats.note(app, DataEvent(stamp(), "drop-wifi", secs, wifi(app)?.ssid.orEmpty()))
                    lostAt = 0
                }
                wifi = now
                if (now) { // very weak for 30 s or more: a line of its own
                    val rssi = caps.signalStrength
                    if (rssi != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED && rssi < -80) { if (weakSince == 0L) weakSince = System.currentTimeMillis() }
                    else if (weakSince > 0) { val secs = (System.currentTimeMillis() - weakSince) / 1000; if (secs >= 30) DataStats.note(app, DataEvent(stamp(), "weak-wifi", secs)); weakSince = 0 }
                }
                runCatching { DataStats.checkRoaming(app) }
            }
            override fun onLost(network: Network) { if (wifi) lostAt = System.currentTimeMillis(); wifi = false }
        }) }.onFailure { WriterLog.w("Data: the network is not watched (${it.message})") }
        if (PhoneRelay.noCalls(app)) return
        val tm = app.getSystemService(TelephonyManager::class.java) ?: return
        runCatching { tm.registerTelephonyCallback(ContextCompat.getMainExecutor(app), Mobile(app, cm)) }.onFailure { WriterLog.w("Data: the mobile signal is not watched (${it.message})") }
    }

    private class Mobile(val c: Context, val cm: ConnectivityManager) : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener, TelephonyCallback.ServiceStateListener,
        TelephonyCallback.DataConnectionStateListener, TelephonyCallback.DisplayInfoListener {
        var noServiceAt = 0L; var dataLostAt = 0L; var noneSince = 0L; var gen = ""
        private fun onWifi() = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        override fun onServiceStateChanged(s: ServiceState) {
            if (s.state == ServiceState.STATE_OUT_OF_SERVICE || s.state == ServiceState.STATE_EMERGENCY_ONLY) { if (noServiceAt == 0L) noServiceAt = System.currentTimeMillis() }
            else if (s.state == ServiceState.STATE_IN_SERVICE && noServiceAt > 0) {
                val secs = (System.currentTimeMillis() - noServiceAt) / 1000
                if (isDrop(secs)) DataStats.note(c, DataEvent(stamp(), "no-service", secs, gen)); noServiceAt = 0
            }
        }
        override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
            if (state == TelephonyManager.DATA_DISCONNECTED && !onWifi()) { if (dataLostAt == 0L) dataLostAt = System.currentTimeMillis() }
            else if (state == TelephonyManager.DATA_CONNECTED && dataLostAt > 0) {
                val secs = (System.currentTimeMillis() - dataLostAt) / 1000
                if (isDrop(secs)) DataStats.note(c, DataEvent(stamp(), "drop-mobile", secs, gen)); dataLostAt = 0
            }
        }
        override fun onSignalStrengthsChanged(s: SignalStrength) {
            if (s.level == 0) { if (noneSince == 0L) noneSince = System.currentTimeMillis() }
            else if (noneSince > 0) { val secs = (System.currentTimeMillis() - noneSince) / 1000; if (secs in 30..3600) DataStats.note(c, DataEvent(stamp(), "weak-mobile", secs, gen)); noneSince = 0 }
        }
        override fun onDisplayInfoChanged(d: TelephonyDisplayInfo) {
            val now = when {
                d.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA || d.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED || d.networkType == TelephonyManager.NETWORK_TYPE_NR -> "5G"
                d.networkType == TelephonyManager.NETWORK_TYPE_LTE -> "4G"
                d.networkType in listOf(TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA) -> "3G"
                d.networkType == TelephonyManager.NETWORK_TYPE_UNKNOWN -> gen
                else -> "2G"
            }
            // only a fall to 3G or 2G is a line (5G ↔ 4G changes all day)
            if (now != gen && (now == "3G" || now == "2G") && gen != "" && gen != "3G" && gen != "2G") DataStats.note(c, DataEvent(stamp(), "fell-to-$now", 0, gen))
            gen = now
        }
    }

    private val DROPS = setOf("drop-mobile", "drop-wifi", "no-service", "weak-mobile", "weak-wifi", "left-hotspot", "fell-to-3G", "fell-to-2G")

    /** The drops of all devices, newest first: (device, event). */
    fun drops(all: Map<String, List<DataMonth>>): List<Pair<String, DataEvent>> =
        all.flatMap { (d, m) -> m.flatMap { it.events }.map { d to it } }.filter { it.second.kind in DROPS }.distinct().sortedByDescending { it.second.at }

    fun describe(c: Context, e: DataEvent, timeOnly: Boolean = false): String {
        val t = runCatching { OffsetDateTime.parse(e.at).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(if (timeOnly) "HH:mm" else "EEE d MMM HH:mm", c.resources.configuration.locales[0])) }.getOrDefault(e.at)
        val len = if (e.secs >= 60) c.getString(R.string.data_len_min, e.secs / 60, e.secs % 60) else c.getString(R.string.data_len_s, e.secs)
        fun noted(plain: Int, withNote: Int) = if (e.note.isNotEmpty()) c.getString(withNote, len, e.note) else c.getString(plain, len)
        return "$t · " + when (e.kind) {
            "drop-mobile" -> noted(R.string.data_drop_mobile, R.string.data_drop_mobile_note)
            "no-service" -> c.getString(R.string.data_no_service, len)
            "weak-mobile" -> c.getString(R.string.data_weak_mobile, len)
            "drop-wifi" -> noted(R.string.data_drop_wifi, R.string.data_drop_wifi_note)
            "weak-wifi" -> c.getString(R.string.data_weak_wifi, len)
            "left-hotspot" -> c.getString(R.string.data_left_hotspot, len)
            else -> c.getString(R.string.data_fell, e.note, e.kind.removePrefix("fell-to-"))
        }
    }

    /** Yesterday's drops in one notice, once a day (Settings › Data › Signal drops, off by default). */
    fun daily(c: Context, all: Map<String, List<DataMonth>>) {
        if (!summaryOn(c)) return
        val p = c.getSharedPreferences("data", Context.MODE_PRIVATE)
        val yesterday = LocalDate.now().minusDays(1)
        if (p.getString("dropsDay", "") == yesterday.toString()) return
        val l = drops(all).filter { runCatching { OffsetDateTime.parse(it.second.at).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate() == yesterday }.getOrDefault(false) }
        p.edit().putString("dropsDay", yesterday.toString()).apply()
        if (l.isNotEmpty()) DataStats.notify(c, 0xDA20, c.getString(R.string.data_drops_yesterday, l.size), l.take(4).joinToString("\n") { (d, e) -> "$d: " + describe(c, e).substringAfter(" · ") })
    }
}

// ---- the Signal tab: its own tabs at the top (the user, 25 Oct: "the signal tab now is a wall of text") ----

private enum class SignalPage(@androidx.annotation.StringRes val label: Int) { Meter(R.string.data_page_meter), Analyzer(R.string.data_page_analyzer), Spots(R.string.data_page_spots), Drops(R.string.data_page_drops) }

/** The locale the screen is shown in, for dates. */
@Composable private fun uiLocale(): Locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]

@Composable
internal fun DataSignalTab(pad: PaddingValues) {
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf(SignalPage.Meter) }
    var version by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.Center) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = CircleShape) {
                Row(Modifier.padding(4.dp)) {
                    SignalPage.entries.forEach { p ->
                        Surface(onClick = { page = p }, shape = CircleShape, color = if (p == page) dataColor() else Color.Transparent,
                            contentColor = if (p == page) onData() else MaterialTheme.colorScheme.onSurface) {
                            Text(stringResource(p.label), Modifier.padding(horizontal = 12.dp, vertical = 8.dp), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        Box(Modifier.weight(1f)) {
            val bottom = PaddingValues(bottom = pad.calculateBottomPadding())
            when (page) {
                SignalPage.Meter -> MeterPage(bottom)
                SignalPage.Analyzer -> AnalyzerPage(bottom)
                SignalPage.Spots -> SpotsPage(bottom, version) { version++ }
                SignalPage.Drops -> DropsPage(bottom, version)
            }
        }
    }
}

/** An explanation behind (i), not on the page. */
@Composable internal fun Info(title: String, text: String) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
        Icon(painterResource(R.drawable.ic_info), contentDescription = stringResource(R.string.data_about_info, title), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(title) }, text = { Text(text) }, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.common_ok), maxLines = 1, overflow = TextOverflow.Ellipsis) } })
}

private fun signalColor(rssi: Int) = when { rssi >= -67 -> Color(0xFF7CC417); rssi >= -75 -> Color(0xFFF2B705); else -> Color(0xFFE53935) }
private fun mobileColor(level: Int) = when { level >= 3 -> Color(0xFF7CC417); level == 2 -> Color(0xFFF2B705); else -> Color(0xFFE53935) }

/** A half-circle gauge with its number in the middle, and its last two minutes as a line under it. */
@Composable private fun Gauge(title: String, value: Int?, min: Int, max: Int, color: Color, words: String, lines: List<String>, history: List<Int>,
                              modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.extraLarge, modifier = modifier) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                action?.invoke()
            }
            Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.BottomCenter) {
                Canvas(Modifier.size(200.dp, 110.dp)) {
                    val w = 16.dp.toPx(); val d = size.width - w
                    val share = if (value == null) 0f else ((value - min).toFloat() / (max - min)).coerceIn(0f, 1f)
                    drawArc(track, 180f, 180f, false, Offset(w / 2, w / 2), androidx.compose.ui.geometry.Size(d, d), style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    if (value != null) drawArc(color, 180f, 180f * share, false, Offset(w / 2, w / 2), androidx.compose.ui.geometry.Size(d, d), style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(value?.toString() ?: "—", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else color)
                    Text("dBm", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(words, style = MaterialTheme.typography.titleSmall)
            lines.filter { it.isNotBlank() }.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (history.size > 1) Canvas(Modifier.fillMaxWidth().height(36.dp)) {
                fun y(v: Int) = size.height * (1 - ((v - min).toFloat() / (max - min)).coerceIn(0f, 1f))
                val step = size.width / 119
                history.zipWithNext().forEachIndexed { i, (a, b) -> drawLine(color, Offset(i * step, y(a)), Offset((i + 1) * step, y(b)), 3f) }
            }
        }
    }
}

// ---- Meter: Wi-Fi and mobile now (as Network Cell Info shows them), the computer, and the speed and loss test ----

@Composable private fun MeterPage(pad: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var wifi by remember { mutableStateOf(DataSignal.wifi(context)) }
    var mobile by remember { mutableStateOf<MobileReading?>(null) }
    var link by remember { mutableStateOf(DataMeter.link(context)) }
    val wifiHistory = remember { mutableStateListOf<Int>() }; val mobileHistory = remember { mutableStateListOf<Int>() }
    val hasMobile = remember { !PhoneRelay.noCalls(context) }
    LaunchedEffect(Unit) {
        while (true) {
            wifi = DataSignal.wifi(context); link = DataMeter.link(context)
            if (hasMobile) mobile = withContext(Dispatchers.IO) { DataMeter.mobile(context) }
            wifi?.let { wifiHistory += it.rssi; if (wifiHistory.size > 120) wifiHistory.removeAt(0) }
            mobile?.let { mobileHistory += it.dbm; if (mobileHistory.size > 120) mobileHistory.removeAt(0) }
            delay(1000)
        }
    }
    var sound by remember { mutableStateOf(false) }
    LaunchedEffect(sound) {
        if (!sound) return@LaunchedEffect
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull() ?: return@LaunchedEffect
        try { while (true) { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 60); delay(DataSignal.beepEvery(wifi?.rssi ?: -100)) } } finally { tone.release() }
    }
    val paired = remember { SyncStore(context).pairing() != null }
    var computer by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(paired) { while (paired) { computer = withContext(Dispatchers.IO) { runCatching { SyncClient(context, SyncStore(context)).computerWifi() }.getOrNull() }; delay(3000) } }
    var testing by remember { mutableStateOf<String?>(null) }
    var tests by remember { mutableStateOf(DataMeter.tests(context)) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                val wifiGauge: @Composable (Modifier) -> Unit = { m ->
                    if (link == "Ethernet") Gauge("Ethernet", null, -100, -30, Color(0xFF7CC417), stringResource(R.string.data_by_cable), listOf(stringResource(R.string.data_cable_best)), emptyList(), m)
                    else Gauge(if (link == "Hotspot") stringResource(R.string.data_wifi_hotspot) else "Wi-Fi", wifi?.rssi, -95, -30, signalColor(wifi?.rssi ?: -100),
                        wifi?.let { DataSignal.quality(context, it.rssi) } ?: stringResource(R.string.data_not_on_wifi),
                        listOfNotNull(wifi?.let { r -> listOfNotNull(r.ssid, DataSignal.band(r.mhz), DataSignal.channel(r.mhz).takeIf { it > 0 }?.let { context.getString(R.string.data_ch, it) }).joinToString(" · ") },
                            wifi?.let { context.getString(R.string.data_link_speed, it.speed) }), wifiHistory, m) {
                        IconButton(onClick = { sound = !sound }, modifier = Modifier.size(36.dp)) {
                            Icon(painterResource(if (sound) R.drawable.ic_volume_up else R.drawable.ic_volume_off), contentDescription = if (sound) stringResource(R.string.data_sound_off) else stringResource(R.string.data_sound_on),
                                tint = if (sound) dataColor() else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                val mobileGauge: @Composable (Modifier) -> Unit = { m ->
                    val r = mobile
                    Gauge(stringResource(R.string.data_mobile) + (r?.gen?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""), r?.dbm, -125, -75, mobileColor(r?.level ?: 0),
                        r?.let { stringResource(listOf(R.string.data_level_0, R.string.data_level_1, R.string.data_level_2, R.string.data_level_3, R.string.data_level_4)[it.level.coerceIn(0, 4)]) } ?: stringResource(R.string.data_no_mobile_signal),
                        listOfNotNull(r?.let { listOfNotNull(it.operator.ifBlank { null }, it.band).joinToString(" · ") }, r?.detail), mobileHistory, m)
                }
                if (wide && hasMobile) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { wifiGauge(Modifier.weight(1f)); mobileGauge(Modifier.weight(1f)) }
                else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { wifiGauge(Modifier.fillMaxWidth()); if (hasMobile) mobileGauge(Modifier.fillMaxWidth()) }
            }
            if (paired) item {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.data_the_computer), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Info(stringResource(R.string.data_the_computer), stringResource(R.string.data_info_computer))
                        }
                        Text(computer?.let { stringResource(R.string.data_connected_by, DataMeter.computerLink(it)) + (it.optString("ssid").takeIf { s -> s.isNotEmpty() && !it.has("ethernet") }?.let { s -> " · $s" } ?: "") }
                            ?: stringResource(R.string.data_not_answering), style = MaterialTheme.typography.bodyMedium)
                        Button(enabled = testing == null && computer != null, onClick = {
                            testing = context.getString(R.string.data_testing)
                            scope.launch {
                                val r = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val client = SyncClient(context, SyncStore(context))
                                        val (down, up, ms) = client.speedTest()
                                        val loss = DataMeter.ping(client.computerHost())
                                        // a phone on mobile data that reaches the computer reaches it over its own hotspot
                                        val here = DataMeter.link(context).let { if (it == "Mobile") context.getString(R.string.data_its_own_hotspot) else DataMeter.linkName(context, it) + (DataSignal.wifi(context)?.let { w -> if (it != "Ethernet") " ${w.rssi} dBm" else "" } ?: "") }
                                        SpeedTest(ZonedDateTime.now().toOffsetDateTime().withNano(0).toString(), here,
                                            DataMeter.computerLink(computer), down, up, loss?.second?.let { Math.round(it) } ?: ms, loss?.first)
                                    }
                                }
                                r.onSuccess { DataMeter.addTest(context, it); tests = DataMeter.tests(context); testing = null }.onFailure { testing = context.getString(R.string.data_computer_no_answer, it.message) }
                            }
                        }, colors = ButtonDefaults.buttonColors(containerColor = dataColor(), contentColor = onData())) { Text(stringResource(R.string.data_test_speed), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        testing?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        tests.forEachIndexed { i, t ->
                            val locale = uiLocale()
                            val time = runCatching { OffsetDateTime.parse(t.at).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM HH:mm", locale)) }.getOrDefault(t.at)
                            Column(Modifier.padding(top = if (i == 0) 6.dp else 2.dp)) {
                                Text("↓ %.0f Mb/s  ↑ %.0f Mb/s  ·  %d ms".format(t.down, t.up, t.ms) + (t.loss?.let { "  ·  " + stringResource(R.string.data_lost, it) } ?: ""),
                                    style = if (i == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
                                Text(stringResource(R.string.data_test_line, time, t.here, t.there), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---- Analyzer: the channels around, as WiFi Analyzer draws them, and a few lines of advice ----

@Composable private fun AnalyzerPage(pad: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var located by remember { mutableStateOf(DataSignal.located(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { located = DataSignal.located(context) }
    var heard by remember { mutableStateOf<List<ScanResult>?>(null) }
    var high by rememberSaveable { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    fun scan() {
        if (scanning) return
        scanning = true
        scope.launch {
            val wm = context.applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION") runCatching { wm.startScan() }
            delay(4000)
            heard = runCatching { wm.scanResults }.getOrNull().orEmpty()
            scanning = false
        }
    }
    LaunchedEffect(located) { if (located) scan() }
    val mine = DataSignal.wifi(context)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!located) item {
            Surface(color = dataColor().copy(alpha = 0.16f), shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.data_hear_networks), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text(stringResource(R.string.common_allow), color = dataColor(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        } else item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = CircleShape) {
                    Row(Modifier.padding(4.dp)) {
                        listOf(false to "2.4 GHz", true to "5 GHz").forEach { (h, label) ->
                            Surface(onClick = { high = h }, shape = CircleShape, color = if (h == high) dataColor() else Color.Transparent,
                                contentColor = if (h == high) onData() else MaterialTheme.colorScheme.onSurface) { Text(label, Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) }
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(if (scanning) stringResource(R.string.data_listening) else heard?.let { pluralStringResource(R.plurals.data_n_networks, it.size, it.size) } ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = ::scan, enabled = !scanning) { Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.data_listen_again)) }
                Info(stringResource(R.string.data_channels_title), stringResource(R.string.data_info_channels))
            }
        }
        heard?.let { list ->
            item { ChannelGraph(list, mine, high) }
            val advice = DataMeter.advice(mine, DataMeter.link(context) == "Hotspot", list.map { @Suppress("DEPRECATION") Triple(it.SSID, it.frequency, it.level) })
            items(advice.size) { i ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(advice[i].title.text(context), style = MaterialTheme.typography.titleSmall, color = dataColor())
                        Text(advice[i].text.text(context), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                var open by remember { mutableStateOf(false) }
                TextButton(onClick = { open = !open }) { Text(if (open) stringResource(R.string.data_hide_networks) else stringResource(R.string.data_all_networks_n, list.size), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (open) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    list.sortedByDescending { it.level }.forEach { s ->
                        @Suppress("DEPRECATION") val name = s.SSID.ifBlank { stringResource(R.string.data_hidden_ssid) }
                        Text("${s.level} dBm · ${stringResource(R.string.data_ch, DataSignal.channel(s.frequency))} · ${DataSignal.band(s.frequency)} · $name" + if (s.is80211mcResponder) " · RTT" else "", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** The networks on their channels: each a hill as wide as its channel width and as tall as its signal, its name and rough distance on top (WiFi Analyzer's picture). */
@Composable private fun ChannelGraph(list: List<ScanResult>, mine: WifiReading?, high: Boolean) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant; val lime = dataColor()
    val measure = androidx.compose.ui.text.rememberTextMeasurer()
    val small = MaterialTheme.typography.labelSmall
    val hiddenName = stringResource(R.string.data_hidden_ssid)
    val nets = list.filter { (DataSignal.channel(it.frequency) > 14) == high && DataSignal.channel(it.frequency) > 0 }
    val lo: Int; val hi: Int
    if (!high) { lo = -1; hi = 16 } else {
        lo = minOf(nets.minOfOrNull { DataSignal.channel(it.frequency) } ?: 36, 36) - 6
        hi = maxOf(nets.maxOfOrNull { DataSignal.channel(it.frequency) } ?: 64, 64) + 6
    }
    val palette = listOf(Color(0xFF4F86E8), Color(0xFFFF9F1C), Color(0xFFE91E63), Color(0xFF26C6DA), Color(0xFFAB47BC), Color(0xFFFFCA28), Color(0xFF8D6E63), Color(0xFF66BB6A))
    Canvas(Modifier.fillMaxWidth().height(320.dp)) {
        val left = 34.dp.toPx(); val bottom = 18.dp.toPx(); val w = size.width - left; val h = size.height - bottom
        fun x(ch: Double) = left + ((ch - lo) / (hi - lo) * w).toFloat()
        fun y(dbm: Int) = h * (1 - ((dbm + 100).coerceIn(0, 80) / 80f))
        // the dBm scale and the channels
        for (db in -90..-30 step 10) {
            drawLine(quiet.copy(alpha = 0.2f), Offset(left, y(db)), Offset(size.width, y(db)), 1f)
            drawText(measure, "$db", Offset(0f, y(db) - 7.dp.toPx()), small.copy(color = quiet))
        }
        val marks = if (!high) (1..14).toList() else listOf(36, 44, 52, 60, 100, 108, 116, 124, 132, 140, 149, 157, 165).filter { it in lo..hi }
        marks.forEach { m ->
            drawLine(quiet.copy(alpha = 0.12f), Offset(x(m.toDouble()), 0f), Offset(x(m.toDouble()), h), 1f)
            val t = measure.measure("$m", small)
            drawText(t, quiet, Offset(x(m.toDouble()) - t.size.width / 2f, h + 2.dp.toPx()))
        }
        // the weakest first, so the strong ones are drawn over them
        nets.sortedBy { it.level }.forEachIndexed { i, s ->
            val ch = DataSignal.channel(s.frequency).toDouble()
            val half = when (s.channelWidth) { ScanResult.CHANNEL_WIDTH_40MHZ -> 4.0; ScanResult.CHANNEL_WIDTH_80MHZ -> 8.0; ScanResult.CHANNEL_WIDTH_160MHZ -> 16.0; else -> 2.0 }
            @Suppress("DEPRECATION") val yours = mine?.ssid != null && s.SSID == mine.ssid && s.frequency == mine.mhz
            val c = if (yours) lime else palette[i % palette.size]
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(x(ch - half), h)
                cubicTo(x(ch - half * 0.7), y(s.level) - 4f, x(ch - half * 0.35), y(s.level), x(ch), y(s.level))
                cubicTo(x(ch + half * 0.35), y(s.level), x(ch + half * 0.7), y(s.level) - 4f, x(ch + half), h)
            }
            drawPath(path, c.copy(alpha = if (yours) 0.35f else 0.22f))
            drawPath(path, c, style = androidx.compose.ui.graphics.drawscope.Stroke(if (yours) 5f else 2.5f))
            @Suppress("DEPRECATION") val name = s.SSID.ifBlank { hiddenName }.take(16) + (if (yours) " ✓" else "") + " (~${DataSignal.distance(s.level, s.frequency)} m)"
            val t = measure.measure(name, small)
            drawText(t, c, Offset((x(ch) - t.size.width / 2f).coerceIn(left, size.width - t.size.width), (y(s.level) - t.size.height - 2.dp.toPx()).coerceAtLeast(0f)))
        }
    }
    if (nets.isEmpty()) Text(stringResource(R.string.data_nothing_heard, if (high) "5 GHz" else "2.4 GHz"), style = MaterialTheme.typography.bodySmall, color = quiet)
}

// ---- Spots ----

@Composable private fun SpotsPage(pad: PaddingValues, version: Int, changed: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val spots by produceState(emptyList<DataSpot>(), version) { value = withContext(Dispatchers.IO) { runCatching { DataSignal.spots() }.getOrDefault(emptyList()) } }
    val prefs = remember { context.getSharedPreferences("data", Context.MODE_PRIVATE) }
    var round by remember { mutableStateOf(prefs.getString("round", "").orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pad, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(round, { round = it; prefs.edit().putString("round", it).apply() }, label = { Text(stringResource(R.string.data_round_label)) }, singleLine = true, modifier = Modifier.weight(1f))
                Info(stringResource(R.string.data_page_spots), stringResource(R.string.data_info_spots))
            }
        }
        item {
            Button(onClick = { saving = true }, enabled = DataSignal.wifi(context) != null, colors = ButtonDefaults.buttonColors(containerColor = dataColor(), contentColor = onData())) { Text(stringResource(R.string.data_save_spot), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        val rounds = DataSignal.rounds(spots)
        if (rounds.size > 1) item {
            Surface(color = dataColor().copy(alpha = 0.16f), shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.data_best, rounds.first().first.ifBlank { stringResource(R.string.data_no_name) }), style = MaterialTheme.typography.titleSmall, color = dataColor())
                    rounds.forEach { Text(stringResource(R.string.data_round_avg, it.first.ifBlank { stringResource(R.string.data_no_name) }, "%.0f".format(it.second)), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (spots.isEmpty()) item { Text(stringResource(R.string.data_no_spots), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        spots.groupBy { it.spot }.toList().sortedByDescending { (_, l) -> l.maxOf { it.rssi } }.forEach { (name, l) -> item(key = "spot:$name") {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    l.sortedByDescending { it.rssi }.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.round.ifBlank { "—" }, Modifier.width(110.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            Box(Modifier.weight(1f).height(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                                Box(Modifier.fillMaxWidth(((s.rssi + 95).coerceIn(0, 65)) / 65f).fillMaxHeight().background(signalColor(s.rssi)))
                            }
                            Text(" ${s.rssi} dBm" + (if (s.down > 0) " · ↓%.0f".format(s.down) else ""), style = MaterialTheme.typography.bodySmall)
                            IconButton(onClick = { scope.launch { withContext(Dispatchers.IO) { DataSignal.deleteSpot(context, s.spot, s.round) }; changed() } }, modifier = Modifier.size(32.dp)) {
                                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_remove), modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        } }
    }
    if (saving) SaveSpot(DataSignal.wifi(context), round, close = { saving = false; changed() })
}

// ---- Drops ----

@Composable private fun DropsPage(pad: PaddingValues, version: Int) {
    val context = LocalContext.current
    val all by produceState(emptyMap<String, List<DataMonth>>(), version) { value = withContext(Dispatchers.IO) { runCatching { DataFiles.all() }.getOrDefault(emptyMap()) } }
    val drops = DataSignal.drops(all).take(80)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pad, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(R.plurals.data_n_recorded, drops.size, drops.size), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Info(stringResource(R.string.data_page_drops), stringResource(R.string.data_info_drops))
            }
        }
        if (drops.isEmpty()) item { Text(stringResource(R.string.data_none_yet), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        drops.groupBy { it.second.at.take(10) }.forEach { (day, l) ->
            item { val locale = uiLocale(); Text(runCatching { LocalDate.parse(day).format(DateTimeFormatter.ofPattern("EEEE d MMMM", locale)) }.getOrDefault(day), color = dataColor(), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
            l.forEach { (device, e) -> item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFE53935)))
                    Text(DataSignal.describe(context, e, timeOnly = true), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(DataFiles.label(all, device), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } }
        }
    }
}

/** Saving a spot: ten seconds of readings averaged, and the speed to the computer if it answers. */
@Composable private fun SaveSpot(start: WifiReading?, round: String, close: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (busy == null) close() }, title = { Text(stringResource(R.string.data_save_spot)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.data_where_label)) }, singleLine = true)
            Text(stringResource(R.string.data_round, round.ifBlank { stringResource(R.string.data_no_name) }), style = MaterialTheme.typography.bodySmall)
            busy?.let { Text(it) }
        }
    }, confirmButton = {
        TextButton(enabled = name.isNotBlank() && busy == null, onClick = {
            scope.launch {
                val readings = mutableListOf<WifiReading>()
                repeat(10) { i -> busy = context.getString(R.string.data_measuring, 10 - i); DataSignal.wifi(context)?.let(readings::add); delay(1000) }
                busy = context.getString(R.string.data_speed_to_computer)
                val pc = withContext(Dispatchers.IO) { runCatching { SyncClient(context, SyncStore(context)).speedTest(8_000_000) }.getOrNull() }
                val r = readings.ifEmpty { listOfNotNull(start) }
                if (r.isNotEmpty()) withContext(Dispatchers.IO) {
                    DataSignal.saveSpot(context, DataSpot(ZonedDateTime.now().toOffsetDateTime().withNano(0).toString(), name.trim(), round.trim(),
                        r.map { it.rssi }.average().toInt(), r.map { it.speed }.average().toInt(), r.last().mhz, pc?.first ?: 0.0, pc?.second ?: 0.0, pc?.third ?: 0))
                }
                close()
            }
        }) { Text(stringResource(R.string.data_measure_save), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }, dismissButton = { TextButton(enabled = busy == null, onClick = close) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) } })
}
