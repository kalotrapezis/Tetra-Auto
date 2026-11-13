package com.kalotrapezis.tetra.auto

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin

/** A position, [at] on this unit's elapsedRealtime clock (ms) when it was taken; [phone]: it came from the phone over the link. */
internal data class Fix(val lat: Double, val lng: Double, val accuracy: Float, val speed: Float, val bearing: Float, val at: Long, val phone: Boolean) {
    val moving get() = speed > 2.5f && !bearing.isNaN()
}

/** Where the position comes from (Settings › Location; the user, 13 Nov: "the tablet or the phone GPS … can we use both?"). */
internal enum class GpsSource { Best, Car, Phone }

/** A fix this old or older counts as stale for Best of both. */
internal const val FRESH_MS = 2000L

/**
 * The fix to show. Best of both: of the fixes under [FRESH_MS] old, the one with the better accuracy, the car's on a tie (no
 * Wi-Fi in between); with neither fresh, the newer. Two receivers under the same sky do not make a better position together;
 * what both give is a quick start (the phone's assisted GPS) and cover when one loses the sky. A phone fix is moved on by its
 * speed for the time it took to get here, so it does not trail behind at 100 km/h.
 */
internal fun pickFix(car: Fix?, phone: Fix?, now: Long, source: GpsSource): Fix? {
    val p = phone?.let { ahead(it, now) }
    return when (source) {
        GpsSource.Car -> car
        GpsSource.Phone -> p
        GpsSource.Best -> {
            val c = car?.takeIf { now - it.at < FRESH_MS }
            val f = p?.takeIf { now - phone.at < FRESH_MS }
            when {
                c != null && f != null -> if (f.accuracy < c.accuracy) f else c
                c != null -> c
                f != null -> f
                else -> listOfNotNull(car, p).maxByOrNull { it.at }
            }
        }
    }
}

/** [f] moved along its bearing by its speed for its age (only while moving; at most 3 s, past that it is a guess). */
internal fun ahead(f: Fix, now: Long): Fix {
    if (!f.moving) return f
    val metres = f.speed * ((now - f.at).coerceIn(0, 3000) / 1000.0)
    val b = Math.toRadians(f.bearing.toDouble())
    val dLat = metres * cos(b) / 111_320.0
    val dLng = metres * sin(b) / (111_320.0 * cos(Math.toRadians(f.lat)))
    return f.copy(lat = f.lat + dLat, lng = f.lng + dLng)
}

/** The car's GPS and the phone's fixes, and the one shown ([now]). */
internal object Fixes {
    @Volatile var car: Fix? = null
    @Volatile var phone: Fix? = null
    val now = mutableStateOf<Fix?>(null)
    private fun prefs(c: Context) = c.getSharedPreferences("car", Context.MODE_PRIVATE)
    val source = mutableStateOf(GpsSource.Best)
    fun load(c: Context) { source.value = runCatching { GpsSource.valueOf(prefs(c).getString("gps", null)!!) }.getOrDefault(GpsSource.Best) }
    fun setSource(c: Context, s: GpsSource) { source.value = s; prefs(c).edit().putString("gps", s.name).apply(); update() }

    /** Called on the main thread after either source changed. */
    fun update() { now.value = pickFix(car, phone, SystemClock.elapsedRealtime(), source.value) }

    private val listener = LocationListener { l: Location ->
        car = Fix(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else 50f, if (l.hasSpeed()) l.speed else 0f,
            if (l.hasBearing()) l.bearing else Float.NaN, l.elapsedRealtimeNanos / 1_000_000, phone = false)
        update()
    }

    /** The unit's own GPS, twice a second (the permission was asked first). */
    @SuppressLint("MissingPermission")
    fun startCar(c: Context) = runCatching {
        c.getSystemService(LocationManager::class.java).requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, listener, Looper.getMainLooper())
    }.onFailure { android.util.Log.w(TAG, "No GPS here: ${it.message}") }

    fun stopCar(c: Context) = runCatching { c.getSystemService(LocationManager::class.java).removeUpdates(listener) }

    /** The phone's fix (an EVENT fix), on the main thread. */
    fun fromPhone(o: JSONObject) {
        phone = Fix(o.getDouble("lat"), o.getDouble("lng"), o.optDouble("accuracy", 30.0).toFloat(), o.optDouble("speed", 0.0).toFloat(),
            o.optDouble("bearing", Double.NaN).toFloat(), SystemClock.elapsedRealtime() - o.optLong("age", 0).coerceIn(0, 60_000), phone = true)
        update()
    }
}
