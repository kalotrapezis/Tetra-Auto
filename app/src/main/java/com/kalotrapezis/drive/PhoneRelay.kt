package com.kalotrapezis.drive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/**
 * The Phone on a tablet (Plan.md § Phone, step 6): a tablet has no calls of its own, so its calls are the phone's,
 * through the computer — the computer passes on each call's state (desktop sync.js toTablets → SyncServer /call/state),
 * shown here as a notification with Answer · Decline, or Mute · Hold · End; Call places one on the phone (/call/place).
 * The voice stays on the phone (or its headset, or the computer): an Android tablet cannot be a hands-free kit for an app.
 */
internal object PhoneRelay {
    private const val CHANNEL = "calls_on_phone_nobadge"
    private const val NOTE = 4149
    const val ACT = "com.kalotrapezis.drive.phone.RELAY"

    /** A device with no calls of its own: the tablet. */
    fun noCalls(c: Context) = !c.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    /** Call on the phone, asked through the computer. The answer's text for the screen. */
    fun place(c: Context, number: String, name: String): String = runCatching {
        val r = SyncClient(c, SyncStore(c)).phoneViaComputer("/call/place?number=${Uri.encode(number)}&name=${Uri.encode(name.take(80))}")
        r.optString("error").ifBlank { null } ?: if (r.optString("how") == "notification") c.getString(R.string.phone_relay_tap) else c.getString(R.string.phone_relay_calling_on, r.optString("device").ifBlank { c.getString(R.string.phone_relay_the_phone) })
    }.getOrElse { (it as? SyncException)?.message ?: c.getString(R.string.phone_relay_no_answer) }

    fun show(c: Context, s: JSONObject) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val state = s.optString("state")
        val number = s.optString("number")
        val who = number.ifBlank { null }?.let { PhoneCalls.contactFor(it)?.name } ?: number.ifBlank { c.getString(R.string.phone_hidden_number) }
        val device = s.optString("device").ifBlank { c.getString(R.string.phone_relay_the_phone) }
        if (state == "ended" && !s.optBoolean("missed")) { nm.cancel(NOTE); return }
        nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.phone_channel_relay), NotificationManager.IMPORTANCE_HIGH).apply { setShowBadge(false) }) // again: its name follows the language
        fun act(what: String, label: String, code: Int) = NotificationCompat.Action(0, label,
            PendingIntent.getBroadcast(c, code, Intent(c, PhoneRelayReceiver::class.java).setAction(ACT).putExtra("do", what), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        val open = PendingIntent.getActivity(c, 0, Intent(c, PhoneActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_call).setContentTitle(who).setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_CALL).setOnlyAlertOnce(state != "ringing").setOngoing(state != "ended")
        when (state) {
            "ringing" -> b.setContentText(c.getString(R.string.phone_relay_ringing, device)).addAction(act("decline", c.getString(R.string.phone_decline), 1)).addAction(act("answer", c.getString(R.string.phone_answer), 2))
            "calling" -> b.setContentText(c.getString(R.string.phone_relay_calling, device)).addAction(act("end", c.getString(R.string.phone_end), 3))
            "active", "held" -> {
                b.setContentText(c.getString(if (state == "held") R.string.phone_relay_held else if (s.optBoolean("muted")) R.string.phone_relay_on_muted else R.string.phone_relay_on, device))
                if (state == "active") b.setUsesChronometer(true)
                if (s.optBoolean("phoneApp")) {
                    b.addAction(act(if (s.optBoolean("muted")) "unmute" else "mute", c.getString(if (s.optBoolean("muted")) R.string.phone_unmute else R.string.phone_mute), 4))
                    b.addAction(act(if (state == "held") "unhold" else "hold", c.getString(if (state == "held") R.string.phone_resume else R.string.phone_hold), 5))
                }
                b.addAction(act("end", c.getString(R.string.phone_end), 3))
            }
            else -> b.setContentText(c.getString(R.string.phone_relay_missed, device)).setAutoCancel(true)
        }
        runCatching { nm.notify(NOTE, b.build()) }.onFailure { WriterLog.w("Phone: the call from the computer was not shown (${it.message})") }
    }

    /** The phone's calls as the computer has them (Tetra/.System/Phone/Shared/calls-*.jsonl), newest first, for the tablet's Recents. */
    fun calls(): List<LoggedCall> = phoneRoot().listFiles { f -> f.name.matches(Regex("""calls-\d{4}-\d{2}\.jsonl""")) }.orEmpty()
        .sortedByDescending { it.name }.flatMap { f -> runCatching { f.readLines() }.getOrDefault(emptyList()).reversed() }
        .mapNotNull { l -> runCatching {
            val o = JSONObject(l)
            val at = java.time.OffsetDateTime.parse(o.getString("at")).toInstant().toEpochMilli()
            val type = when (o.optString("dir")) {
                "out" -> android.provider.CallLog.Calls.OUTGOING_TYPE; "missed" -> android.provider.CallLog.Calls.MISSED_TYPE
                "declined" -> android.provider.CallLog.Calls.REJECTED_TYPE; "blocked" -> android.provider.CallLog.Calls.BLOCKED_TYPE
                "voicemail" -> android.provider.CallLog.Calls.VOICEMAIL_TYPE; else -> android.provider.CallLog.Calls.INCOMING_TYPE
            }
            LoggedCall(at xor o.optString("number").hashCode().toLong(), o.optString("number"), at, o.optLong("secs"), type)
        }.getOrNull() }.take(2000)
}

/** The tablet's call notification's buttons: done by the phone, through the computer. */
class PhoneRelayReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val what = i.getStringExtra("do") ?: return
        val app = c.applicationContext
        val done = goAsync()
        Thread {
            runCatching { SyncClient(app, SyncStore(app)).phoneViaComputer("/call/act?do=${Uri.encode(what)}") }.onFailure { WriterLog.w("Phone: $what through the computer (${it.message})") }
            done.finish()
        }.start()
    }
}
