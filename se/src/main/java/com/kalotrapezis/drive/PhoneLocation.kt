package com.kalotrapezis.drive

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * "Where is the phone?" from the paired computer (desktop Maps' where-I-am button; SyncServer's /location). The
 * computer has no GPS, and a guess from the internet is off by half a county (the user, 21 Oct), so this phone
 * takes one fix and answers with it. Android gives the location only to the app on screen, so a Tetra in the
 * background first asks for a tap on a notification; no permission yet, it is asked for on the phone's screen. The
 * computer waits for both.
 */
internal object PhoneLocation {
    /** Set by the open app: shows Android's location question. The server's thread cannot ask by itself. */
    @Volatile var askPermission: (() -> Unit)? = null

    private const val CHANNEL = "where_nobadge"
    private const val NOTE = 0x10CA7E

    data class Fix(val lat: Double, val lng: Double, val accuracy: Float, val at: Long, val provider: String)

    /** The answer the computer gets: 200 and the fix, or why there is none, in words for its screen. */
    fun reply(granted: Boolean, enabled: Boolean, fix: Fix?, onScreen: Boolean = true): Pair<Int, JSONObject> = when {
        !onScreen -> 409 to JSONObject().put("error", "Tetra is in the background on the phone. Open it there, then try again.")
        !granted -> 403 to JSONObject().put("error", "Tetra on the phone may not use the location. Allow it on the phone, then try again.")
        !enabled -> 503 to JSONObject().put("error", "Location is off on the phone.")
        fix == null -> 504 to JSONObject().put("error", "The phone found no fix in time. Try again near a window or outside.")
        else -> 200 to JSONObject().put("lat", fix.lat).put("lng", fix.lng).put("accuracy", fix.accuracy.toDouble())
            .put("at", fix.at).put("provider", fix.provider)
    }

    /** GPS when it has one; the network's fix (Wi-Fi, tens of metres) only when GPS found nothing, as Maps does. */
    fun best(gps: Fix?, network: Fix?): Fix? = gps ?: network

    fun answer(context: Context, waitMs: Long = 45_000): Pair<Int, JSONObject> {
        val until = SystemClock.elapsedRealtime() + waitMs
        val lm = context.getSystemService(LocationManager::class.java)
        if (lm != null && hasLocation(context)) recent(lm)?.let { return reply(true, true, it) }
        if (!onScreen() && !SyncListenService.withLocation) {
            if (!tapToAnswer(context)) return reply(true, true, null, onScreen = false) // notifications are off: nothing to wait for
            while (!onScreen() && SystemClock.elapsedRealtime() < until) Thread.sleep(300)
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTE)
            if (!onScreen()) return reply(true, true, null, onScreen = false)
        }
        if (!hasLocation(context)) {
            askPermission?.let { Handler(Looper.getMainLooper()).post(it) }
            while (!hasLocation(context) && askPermission != null && SystemClock.elapsedRealtime() < until) Thread.sleep(300)
            if (!hasLocation(context)) return reply(false, true, null)
        }
        if (lm == null || !lm.isLocationEnabled) return reply(true, false, null)
        return reply(true, true, fix(lm, until))
    }

    private fun onScreen() = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState).importance <=
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND

    /** "The computer asks where this phone is": a tap opens Tetra, and the answer goes as soon as it is on screen. */
    private fun tapToAnswer(c: Context): Boolean {
        val nm = c.getSystemService(NotificationManager::class.java)?.takeIf { it.areNotificationsEnabled() } ?: return false
        nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.phone_channel_where), NotificationManager.IMPORTANCE_HIGH).apply { setShowBadge(false); description = c.getString(R.string.phone_channel_where_about) }) // again: its name follows the language
        val open = PendingIntent.getActivity(c, NOTE, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(NOTE, NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_my_location)
            .setContentTitle(c.getString(R.string.phone_where_title)).setContentText(c.getString(R.string.phone_where_text))
            .setCategory(NotificationCompat.CATEGORY_STATUS).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open).setAutoCancel(true).setTimeoutAfter(60_000).build())
        return true
    }

    /** A GPS fix under 30 s old (Maps is open, or was a moment ago) goes at once. */
    @Suppress("MissingPermission") // answer() checks it first
    private fun recent(lm: LocationManager): Fix? = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
        ?.takeIf { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < 30_000_000_000L }?.toFix()

    @Suppress("MissingPermission") // answer() checks it first
    private fun fix(lm: LocationManager, until: Long): Fix? {
        val found = HashMap<String, Location?>()
        val waits = HashMap<String, CountDownLatch>()
        val cancel = CancellationSignal()
        val pool = Executors.newSingleThreadExecutor()
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { lm.isProviderEnabled(it) }) {
            val done = CountDownLatch(1).also { waits[provider] = it }
            // getCurrentLocation is Android 11+; a head unit on Android 10 asks for one update instead
            runCatching { if (android.os.Build.VERSION.SDK_INT >= 30) lm.getCurrentLocation(provider, cancel, pool) { synchronized(found) { found[provider] = it }; done.countDown() }
                else @Suppress("DEPRECATION") lm.requestSingleUpdate(provider, locationListener { l -> synchronized(found) { found[provider] = l }; done.countDown() }, android.os.Looper.getMainLooper()) }
                .onFailure { done.countDown() }
        }
        try {
            // GPS is waited for; the network's fix is what is left when GPS gave nothing
            waits[LocationManager.GPS_PROVIDER]?.await(until - SystemClock.elapsedRealtime(), TimeUnit.MILLISECONDS)
            if (synchronized(found) { found[LocationManager.GPS_PROVIDER] } == null) {
                waits[LocationManager.NETWORK_PROVIDER]?.await((until - SystemClock.elapsedRealtime()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            }
        } finally { cancel.cancel(); pool.shutdown() }
        return synchronized(found) { best(found[LocationManager.GPS_PROVIDER]?.toFix(), found[LocationManager.NETWORK_PROVIDER]?.toFix()) }
    }

    private fun Location.toFix() = Fix(latitude, longitude, accuracy, time, provider ?: "")
}
