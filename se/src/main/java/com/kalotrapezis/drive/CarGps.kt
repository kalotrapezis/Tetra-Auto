package com.kalotrapezis.drive

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The car build's GPS (the user, 14 Nov: "the car unit costs 320 euros, the phone 700, I don't know which is better"): the car's own,
 * or the phone's sent over the car link once a second (Settings › Tetra Car › GPS), and a page with both side by side to compare.
 */
internal object CarGps {
    private fun prefs(c: Context) = c.getSharedPreferences("car", Context.MODE_PRIVATE)
    fun phone(c: Context) = prefs(c).getBoolean("gps_phone", false)
    fun setPhone(c: Context, on: Boolean) = prefs(c).edit().putBoolean("gps_phone", on).apply()

    /** In a Location's extras: the fix is the phone's. */
    const val PHONE = "tetra_phone"

    // ---- the phone: its GPS while the car asks, stopped 15 s after the last ask ----

    private val thread by lazy { HandlerThread("car-gps").apply { start() } }
    private val handler by lazy { Handler(thread.looper) }
    @Volatile private var lm: LocationManager? = null
    @Volatile private var last: Location? = null
    @Volatile private var sats = 0 to 0
    @Volatile private var askedAt = 0L
    private val listener = locationListener { last = it }
    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(s: GnssStatus) { sats = (0 until s.satelliteCount).count(s::usedInFix) to s.satelliteCount }
    }

    /** /car/where on the phone: its latest GPS fix, or why there is none. */
    fun where(c: Context): JSONObject {
        askedAt = SystemClock.elapsedRealtime()
        if (lm == null) handler.post { start(c.applicationContext) }
        val l = last ?: return JSONObject().put("error", "No fix yet on the phone.")
        return fix(l, sats)
    }

    @SuppressLint("MissingPermission") // a SecurityException without it: told, not crashed
    private fun start(c: Context) {
        if (lm != null) return
        val m = c.getSystemService(LocationManager::class.java) ?: return
        runCatching { m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, thread.looper); m.registerGnssStatusCallback(gnss, handler) }
            .onFailure { WriterLog.w("Car GPS: the phone's GPS not started (${it.message})"); return }
        lm = m
        WriterLog.i("Car GPS: the phone's GPS for the car")
        handler.postDelayed(object : Runnable { override fun run() {
            if (SystemClock.elapsedRealtime() - askedAt < 15_000) { handler.postDelayed(this, 5000); return }
            runCatching { m.removeUpdates(listener); m.unregisterGnssStatusCallback(gnss) }
            lm = null; last = null
            WriterLog.i("Car GPS: the phone's GPS stopped (the car stopped asking)")
        } }, 5000)
    }

    /** A fix for the wire: its age, not its time (the two clocks differ), and the satellites used / seen. */
    fun fix(l: Location, sats: Pair<Int, Int>): JSONObject = JSONObject().put("lat", l.latitude).put("lng", l.longitude).put("acc", l.accuracy.toDouble())
        .put("speed", l.speed.toDouble()).put("hasSpeed", l.hasSpeed()).put("bearing", l.bearing.toDouble()).put("hasBearing", l.hasBearing())
        .put("alt", l.altitude).put("t", l.time).put("age", (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000)
        .put("used", sats.first).put("seen", sats.second)

    // ---- the unit ----

    /** The phone's fix [o] on this unit's clock, as a GPS fix (Maps' own rules take it as GPS), marked as the phone's. */
    fun location(o: JSONObject): Location = Location(LocationManager.GPS_PROVIDER).apply {
        val age = o.optLong("age")
        latitude = o.getDouble("lat"); longitude = o.getDouble("lng"); accuracy = o.optDouble("acc").toFloat(); altitude = o.optDouble("alt")
        if (o.optBoolean("hasSpeed")) speed = o.optDouble("speed").toFloat()
        if (o.optBoolean("hasBearing")) bearing = o.optDouble("bearing").toFloat()
        time = System.currentTimeMillis() - age
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - age * 1_000_000
        extras = Bundle().apply { putBoolean(PHONE, true) }
    }
}

/** Settings › Tetra Car › Test the GPS: the car's and the phone's, live, side by side; nothing on the map. */
@SuppressLint("MissingPermission")
@Composable
internal fun CarGpsTest(close: () -> Unit) {
    val context = LocalContext.current
    var mine by remember { mutableStateOf<Location?>(null) }
    var mineSats by remember { mutableStateOf(0 to 0) }
    var theirs by remember { mutableStateOf<JSONObject?>(null) }
    var away by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(SystemClock.elapsedRealtimeNanos()) }
    BackHandler(onBack = close)
    DisposableEffect(Unit) {
        val m = context.getSystemService(LocationManager::class.java)
        val l = locationListener { mine = it }
        val g = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(s: GnssStatus) { mineSats = (0 until s.satelliteCount).count(s::usedInFix) to s.satelliteCount }
        }
        runCatching { m?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l, context.mainLooper); m?.registerGnssStatusCallback(g, Handler(context.mainLooper)) }
            .onFailure { WriterLog.w("Car GPS test: the car's GPS not started (${it.message})") }
        onDispose { runCatching { m?.removeUpdates(l); m?.unregisterGnssStatusCallback(g) } }
    }
    LaunchedEffect(Unit) {
        while (true) {
            val r = withContext(Dispatchers.IO) { runCatching { CarWire.where(context) } }
            r.onSuccess { o -> if (o.has("lat")) { theirs = o; away = null } else away = o.optString("error") }.onFailure { away = it.message }
            mine?.let { m -> theirs?.let { t -> WriterLog.i("Car GPS test: car ±${m.accuracy.toInt()} m, phone ±${t.optDouble("acc").toInt()} m, " +
                "${metresBetween(m.latitude, m.longitude, t.getDouble("lat"), t.getDouble("lng")).toInt()} m apart") } }
            now = SystemClock.elapsedRealtimeNanos()
            delay(1000)
        }
    }
    val car = mine?.let { l -> listOf(l.accuracy.toDouble(), mineSats.first, mineSats.second, if (l.hasSpeed()) l.speed.toDouble() else null, ((now - l.elapsedRealtimeNanos) / 1_000_000).coerceAtLeast(0)) }
    val phone = theirs?.let { o -> listOf(o.optDouble("acc"), o.optInt("used"), o.optInt("seen"), if (o.optBoolean("hasSpeed")) o.optDouble("speed") else null, o.optLong("age")) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(close) { Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.common_back)) }
            Text(stringResource(R.string.car_gps_test), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            GpsColumn(stringResource(R.string.car_gps_car), car, null, Modifier.weight(1f))
            GpsColumn(stringResource(R.string.car_gps_phone), phone, away, Modifier.weight(1f))
        }
        val apart = mine?.let { m -> theirs?.let { t -> metresBetween(m.latitude, m.longitude, t.getDouble("lat"), t.getDouble("lng")) } }
        if (apart != null) Text(stringResource(R.string.car_gps_apart, "${apart.toInt()} m"), Modifier.padding(top = 24.dp), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.car_gps_test_note), style = MaterialTheme.typography.bodyMedium)
    }
}

/** One side: accuracy (big: the one to compare), satellites used of seen, speed, the fix's age; or why there is none. */
@Composable
private fun GpsColumn(title: String, v: List<Any?>?, why: String?, modifier: Modifier) = Column(modifier) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    if (v == null) { Text(why ?: stringResource(R.string.car_gps_none), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge); return@Column }
    val (acc, used, seen, speed, age) = v
    Text("± ${(acc as Double).toInt()} m", style = MaterialTheme.typography.displayMedium)
    Text(stringResource(R.string.car_gps_satellites, used as Int, seen as Int), style = MaterialTheme.typography.bodyLarge)
    Text(stringResource(R.string.car_gps_speed, (speed as Double?)?.let { "${Math.round(it * 3.6)} km/h" } ?: "—"), style = MaterialTheme.typography.bodyLarge)
    Text(stringResource(R.string.car_gps_age, "%.1f s".format((age as Long) / 1000.0)), style = MaterialTheme.typography.bodyLarge)
}
