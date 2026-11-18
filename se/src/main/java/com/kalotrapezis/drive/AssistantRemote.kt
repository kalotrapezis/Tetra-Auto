package com.kalotrapezis.drive

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.io.File
import org.json.JSONObject

/**
 * AI Assist across devices (the user, 4 Nov: "play youtube on the tablet … send it to PC or tablet, a result of search, a
 * song, a radio station, or the daily brief"). The phone's send_to_device goes to the computer (sync.js assistDo), which
 * does it or passes it to the device named; here is what a phone or tablet does with one (SyncServer /assist/do).
 */
internal object AssistantRemote {
    /** What to send, from the assistant's tool: { to, what, q, site, list, url, title, text }. */
    fun command(to: String, what: String, args: JSONObject) = JSONObject().put("to", to).put("what", what).apply {
        listOf("q", "site", "url", "title", "text").forEach { k -> args.optString(k).takeIf { it.isNotBlank() }?.let { put(k, it) } }
        if (args.optBoolean("list")) put("list", true)
    }

    /** One from another device. Only a short list of things, each opened as the user would open it here. */
    fun run(c: Context, o: JSONObject): JSONObject {
        if (!AiGate.load(c).enabled) return JSONObject().put("error", "AI Assist is off on this device.")
        val from = o.optString("from").take(60)
        val title = c.getString(R.string.remote_from, from.ifEmpty { "Tetra" })
        when (o.optString("what")) {
            "play" -> show(c, title, o.optString("q").take(200), browser(c, JSONObject().put("site", MediaSites.byId(o.optString("site")).id)
                .put("q", o.optString("q").take(200)).put("list", o.optBoolean("list"))))
            "radio", "link" -> {
                val url = o.optString("url").takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return JSONObject().put("error", "Not a web address.")
                show(c, title, o.optString("title").ifEmpty { url }.take(200), browser(c, JSONObject().put("url", url).put("at", System.currentTimeMillis())))
            }
            "text" -> note(c, title + (o.optString("title").takeIf { it.isNotBlank() }?.let { " · ${it.take(80)}" } ?: ""), o.optString("text").take(4000), null)
            "brief" -> {
                val latest = runCatching { JSONObject(File(AssistantBriefs.dir(), "latest.json").readText()) }.getOrNull()
                val page = latest?.let { File(AssistantBriefs.dir(), File(it.optString("file")).name) }?.takeIf { it.isFile }
                    ?: return JSONObject().put("error", "No daily brief on this device yet.")
                show(c, title, latest.optString("title"), Intent(c, BriefActivity::class.java).putExtra("file", page.absolutePath))
            }
            else -> return JSONObject().put("error", "Unknown command.")
        }
        WriterLog.i("AI Assist: ${o.optString("what")} from $from")
        return JSONObject().put("ok", true)
    }

    private fun browser(c: Context, play: JSONObject) = Intent(c, BrowserActivity::class.java).putExtra(BrowserActivity.PLAY, "browser-play:$play")

    /**
     * Opened at once while Tetra is the app in front (a tablet in Stand mode); else a notification, which Android opens by
     * itself on a dark or locked screen and shows to tap over another app (no app may open a screen from behind).
     */
    private fun show(c: Context, title: String, text: String, open: Intent) {
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val front = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        if (front && runCatching { c.startActivity(open) }.isSuccess) return
        note(c, title, text, open)
    }

    private fun note(c: Context, title: String, text: String, open: Intent?) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL)?.name != c.getString(R.string.remote_channel))
            nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.remote_channel), NotificationManager.IMPORTANCE_HIGH).apply { setShowBadge(false) })
        val tap = PendingIntent.getActivity(c, ID, open ?: Intent(c, AssistantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(ID, NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_auto_fix).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(tap).setAutoCancel(true)
            .apply { if (open != null) setFullScreenIntent(tap, true) }.build())
    }

    const val CHANNEL = "assistant_remote_nobadge"
    private const val ID = 0x5E4D
}
