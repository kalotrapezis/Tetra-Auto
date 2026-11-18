package com.kalotrapezis.drive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/*
 * Tetra as the default SMS app (the user, 2 Nov: "how do I set the SMS app as the default? It is missing from the settings").
 * Android offers only an app that takes in SMS (SMS_DELIVER), MMS (WAP_PUSH_DELIVER), "reply with a message" from the call
 * screen (RESPOND_VIA_MESSAGE) and sms: links. As the default, Tetra stores what arrives and what it sends itself.
 * shortcut: an MMS is only announced, never downloaded or shown; downloading MMS (SmsManager.downloadMultimediaMessage) when picture messages matter.
 */
internal object SmsRole {
    fun isDefault(c: Context) = runCatching { c.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS) }.getOrDefault(false)
    fun request(c: Context): Intent = c.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_SMS)
    fun defaultApp(c: Context): String = runCatching { Telephony.Sms.getDefaultSmsPackage(c) }.getOrNull() ?: "no app"

    /** sms:, smsto:, mms: or mmsto: from another app, as Messages' "sms:<json>" (MainActivity), or null. */
    fun compose(i: Intent): String? {
        val d = i.data?.takeIf { it.scheme?.lowercase() in setOf("sms", "smsto", "mms", "mmsto") } ?: return null
        val to = d.schemeSpecificPart.substringBefore('?').substringBefore(',').trim()
        val body = i.getStringExtra("sms_body") ?: d.getQueryParameterSafe("body") ?: i.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        return "sms:" + JSONObject().put("to", to).put("text", body)
    }
    private fun android.net.Uri.getQueryParameterSafe(k: String) = runCatching { android.net.Uri.parse("x://x?" + schemeSpecificPart.substringAfter('?', "")).getQueryParameter(k) }.getOrNull()

    const val CHANNEL = "sms_in_nobadge"
    fun notify(c: Context, from: String, text: String) = runCatching {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.app_sms), NotificationManager.IMPORTANCE_HIGH).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(c, from.hashCode(), appletIntent(c, "sms:" + JSONObject().put("to", from).put("text", "")), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(CHANNEL, from.hashCode(), NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_chat).setContentTitle(from).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setCategory(NotificationCompat.CATEGORY_MESSAGE).setContentIntent(open).setAutoCancel(true).build())
    }.onFailure { WriterLog.w("Messages: no notification (${it.message})") }
}

/** An SMS arriving while Tetra is the default app: into the inbox (no one else stores it), then a notification. */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        if (parts.isEmpty()) return
        val from = parts.first().displayOriginatingAddress ?: parts.first().originatingAddress ?: ""
        val body = parts.joinToString("") { it.displayMessageBody ?: "" }
        runCatching {
            c.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, ContentValues().apply {
                put(Telephony.Sms.ADDRESS, from); put(Telephony.Sms.BODY, body); put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.DATE_SENT, parts.first().timestampMillis); put(Telephony.Sms.READ, 0); put(Telephony.Sms.SEEN, 0)
                parts.first().serviceCenterAddress?.let { put(Telephony.Sms.SERVICE_CENTER, it) }
            })
        }.onFailure { WriterLog.e("Messages: an arriving SMS not stored", it) }
        WriterLog.i("Messages: an SMS arrived (${body.length} characters)")
        if (PhoneSpam.matches(PhoneSpam.read(), from, PhoneCountry.of(c)) != null) return // the spam list's: kept, not announced
        SmsRole.notify(c, from, body)
        val wait = goAsync() // in the car: read aloud (CarSmsActivity), once Bluetooth has said whether it is
        CarSmsActivity.arrived(c, from, body) { wait.finish() }
    }
}

/**
 * An SMS arriving while another app is the default (Google Messages on the user's phone, 10 Nov: "read messages on the car"): that
 * app stores and announces it; in Tetra Car (on the car's screen too) it is read aloud there and answered by voice all the same.
 * Outside the car nothing, so a message is never announced twice.
 */
class SmsReceivedReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (SmsRole.isDefault(c) || !Car.running) return // the default's own SMS_DELIVER does it; out of the car the default app does
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        if (parts.isEmpty()) return
        val from = parts.first().displayOriginatingAddress ?: parts.first().originatingAddress ?: ""
        val body = parts.joinToString("") { it.displayMessageBody ?: "" }
        WriterLog.i("Messages: an SMS arrived in the car (${body.length} characters; ${SmsRole.defaultApp(c)} keeps it)")
        if (PhoneSpam.matches(PhoneSpam.read(), from, PhoneCountry.of(c)) != null) return
        val wait = goAsync()
        CarSmsActivity.arrived(c, from, body) { wait.finish() }
    }
}

/** An MMS's notice: Tetra cannot download picture messages, so it says one came. */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        WriterLog.w("Messages: an MMS arrived; Tetra does not download MMS")
        SmsRole.notify(c, c.getString(R.string.sms_mms_title), c.getString(R.string.sms_mms_note))
    }
}

/** "Reply with a message" on an incoming call's screen: sent at once. */
class SmsRespondService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val to = intent?.data?.schemeSpecificPart?.substringBefore('?')?.trim().orEmpty()
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        if (to.isNotEmpty() && text.isNotBlank()) Thread { runCatching { SmsBox.send(this, to, text) }.onFailure { WriterLog.w("Messages: reply not sent", it) }; stopSelf(startId) }.start()
        else stopSelf(startId)
        return START_NOT_STICKY
    }
}
