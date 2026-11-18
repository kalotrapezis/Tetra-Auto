package com.kalotrapezis.drive

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.activity.compose.setContent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.io.File
import java.util.Date

/*
 * The scheduled AI actions' pages, shown here: made by the computer (schedule.js, brief.js) or by this device when it is the runner (AssistantRunner).
 * Each run writes a page into .System/Assistant/Shared/Briefs, which syncs as "AI Assist/Briefs", and latest.json says
 * which is new: after a sync, a new one rings like a Calendar reminder (the island on HyperOS), and a tap opens the page.
 */
internal object AssistantBriefs {
    const val CHANNEL = "assistant_briefs_nobadge"
    private const val ID = 7301

    fun dir(): File = File(TetraSystem.dir(TetraFolder.root(Environment.getExternalStorageDirectory()), "Assistant", "Shared"), "Briefs")

    /** The pages, newest first. */
    fun pages(): List<File> = dir().listFiles { f -> f.name.endsWith(".html") }.orEmpty().sortedByDescending { it.lastModified() }

    /** The page's own title (its <title>), else its name. */
    fun title(f: File): String = runCatching { Regex("<title>([^<]*)</title>").find(f.bufferedReader().use { r -> CharArray(2000).let { String(it, 0, r.read(it).coerceAtLeast(0)) } })?.groupValues?.get(1) }
        .getOrNull()?.replace("&amp;", "&")?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&quot;", "\"") ?: f.nameWithoutExtension

    /** After a sync: latest.json newer than the last one shown rings once; nothing while the AI is switched off. */
    fun check(c: Context) {
        val o = runCatching { JSONObject(File(dir(), "latest.json").readText()) }.getOrNull() ?: return
        val prefs = c.getSharedPreferences("assistant_briefs", Context.MODE_PRIVATE)
        val at = o.optLong("at")
        // the first look ever only notes where things are: the old pages that came with the first sync do not ring
        if (!prefs.contains("seen")) { prefs.edit().putLong("seen", at).apply(); return }
        if (at <= prefs.getLong("seen", 0)) return
        prefs.edit().putLong("seen", at).apply()
        if (!AiGate.load(c).enabled) return
        val page = File(dir(), File(o.optString("file")).name)
        if (page.isFile) post(c, o.optString("title"), o.optString("text"), page, java.text.SimpleDateFormat(if (android.text.format.DateFormat.is24HourFormat(c)) "HH:mm" else "h:mm a", c.uiLocale()).format(Date(at)) /* the phone's clock, as Calendar (6 Nov: 7:30 read as evening) */)
    }

    /** A page made on this device: the sync that brings latest.json back does not ring it again. */
    fun shown(c: Context, at: Long) = c.getSharedPreferences("assistant_briefs", Context.MODE_PRIVATE).edit().putLong("seen", at).apply()

    /** A word without a page (a run that failed or could not start), on the same channel. */
    fun notice(c: Context, title: String, text: String) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.assistant_briefs_channel), NotificationManager.IMPORTANCE_HIGH).apply { setShowBadge(false) })
        runCatching { NotificationManagerCompat.from(c).notify(ID + 2, NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_auto_fix)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).build()) }
    }

    fun post(c: Context, title: String, text: String, page: File, time: String) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.assistant_briefs_channel), NotificationManager.IMPORTANCE_HIGH).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(c, ID, Intent(c, BriefActivity::class.java).putExtra("file", page.absolutePath)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // ✕: the island goes (the Calendar's Done does that, and for a time with no event, nothing more)
        val done = PendingIntent.getBroadcast(c, ID + 1, Intent(c, CalendarAlarmReceiver::class.java).setAction(CalendarAlarms.ACTION_DONE).putExtra("id", ID),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_auto_fix)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setOngoing(true) // HyperOS gives the island to an ongoing notification, as the Calendar's reminders
            .setAutoCancel(true)
            .addAction(R.drawable.ic_close, c.getString(R.string.calendar_reminder_done), done)
        if (HyperIsland.isSupported(c)) HyperIsland.reminder(n, title, text, time, done, android.graphics.drawable.Icon.createWithResource(c, R.drawable.ic_reminder_done), null)
        runCatching { NotificationManagerCompat.from(c).notify(ID, n.build()) }
    }
}

/** One page of the briefs, as it is: light or dark as the phone is (the page's own CSS). */
class BriefActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationManagerCompat.from(this).cancel(7301)
        val f = File(intent.getStringExtra("file").orEmpty())
        // only a page of the briefs' folder: an intent cannot point this at another file
        val html = if (f.parentFile?.canonicalPath == AssistantBriefs.dir().canonicalPath) runCatching { f.readText() }.getOrNull() else null
        // the page, and a way on to AI Assist from it (8 Nov, the user: opened from Home's cassette, "there is no way to open
        // the Assistant from inside the report")
        val title = runCatching { AssistantBriefs.title(f) }.getOrNull().orEmpty()
        setContent {
            TetraTheme {
                // the presenter (AI decorations): the page as a small news show first; Page shows it as it is
                val asPage = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(html == null || !Presenter.on(this@BriefActivity)) }
                // a scheduled action's run is a chat as well, named as its page (14 Nov, the user: "an actual chat"): it goes on there
                val chatId = f.nameWithoutExtension.takeIf { html != null && AssistantChats.has(it) }
                if (!asPage.value) NewsShow(title.ifBlank { getString(R.string.app_assistant) }, html!!, { asPage.value = true }, { finish() }, chatId)
                else androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                    androidx.compose.ui.viewinterop.AndroidView(factory = { c -> WebView(c).apply {
                        settings.javaScriptEnabled = false
                        fitsSystemWindows = true
                        loadDataWithBaseURL(null, html ?: "<p>…</p>", "text/html", "utf-8", null)
                    } }, modifier = androidx.compose.ui.Modifier.fillMaxSize())
                    androidx.compose.material3.ExtendedFloatingActionButton(
                        onClick = { chatId?.let { id -> AssistantChats.get(this@BriefActivity, id)?.let { SharedChat.open(this@BriefActivity, it) } }; openAssistant(this@BriefActivity) },
                        icon = { ArtImage(R.drawable.assistant_art, 28.dp) },
                        text = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(if (chatId != null) R.string.assistant_sched_continue else R.string.app_assistant)) },
                        modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
                    )
                }
            }
        }
    }
}
