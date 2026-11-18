package com.kalotrapezis.drive

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/**
 * Tetra's notifications by applet (the user, 4 Nov: "I don't know what is what, every icon gets a bunch of numbers"):
 * Android gives every launcher icon of one app the same count, so Home shows each applet's own, and the bell lists them
 * under the applet's icon. Read from what Tetra has in the shade now; nothing kept, so nothing to go stale.
 */
internal object Notices {
    /** Each channel's applet, by Home's ids (HomeLayout). */
    private val APPLET = mapOf(
        "mail_nobadge" to "mail", "sms_in_nobadge" to "sms", "calendar_reminders_nobadge" to "calendar",
        "timer_nobadge" to "timer", "timer_done_nobadge" to "timer", "alarm_soon_nobadge" to "timer",
        "phone_missed_nobadge" to "phone", "phone_ringing_nobadge" to "phone", "phone_call_nobadge" to "phone", "calls_from_computer_nobadge" to "phone",
        "calls_on_phone_nobadge" to "phone", "phone_blocked_nobadge" to "phone", "where_nobadge" to "phone",
        "assistant_briefs_nobadge" to "assistant", "assistant_audio_nobadge" to "assistant", "data_warnings_nobadge" to "data", "data_speed" to "data",
        "maps_old_nobadge" to "maps", "analysis_nobadge" to "gallery", "sync_nobadge" to "sync", "listening_nobadge" to "sync", "files_from_computer_nobadge" to "sync",
        "browser_media" to "browser", AssistantRemote.CHANNEL to "assistant", PillsAlarms.CHANNEL to "pills",
    )
    /**
     * What a number on Home counts (the user, 4 Nov: "for example the phone I don't have a call and I have a 1, I go in,
     * nothing"): news to read only; a call asked from the PC, "where are you", a blocked caller are listed, not counted.
     */
    private val COUNTED = setOf("mail_nobadge", "sms_in_nobadge", "phone_missed_nobadge", "calendar_reminders_nobadge", "timer_done_nobadge",
        "assistant_briefs_nobadge", AssistantRemote.CHANNEL, "data_warnings_nobadge", "maps_old_nobadge", "files_from_computer_nobadge")
    /** Seen when its applet opens, as a phone app clears its missed calls; a reminder and a timer ringing stay until answered. */
    private val CLEARED = COUNTED - setOf("calendar_reminders_nobadge", "timer_done_nobadge")
    /** Home's art for each (MainActivity's icons()). */
    val ART = mapOf("sync" to R.drawable.sync_art, "gallery" to R.drawable.gallery_art, "notes" to R.drawable.notes_art, "calendar" to R.drawable.calendar_art,
        "timer" to R.drawable.timer_art, "contacts" to R.drawable.contacts_art, "phone" to R.drawable.phone_art, "data" to R.drawable.data_art,
        "assistant" to R.drawable.assistant_art, "mail" to R.drawable.mail_art, "sms" to R.drawable.sms_art, "browser" to R.drawable.browser_art, "maps" to R.drawable.maps_art, "pills" to R.drawable.pills_art)

    class Notice(val applet: String, val title: String, val text: String, val at: Long, val ongoing: Boolean, val open: PendingIntent?, val tag: String?, val id: Int,
        val channel: String? = null)

    /**
     * The channels these replaced (4 Nov): Android keeps a channel's "count on the icon" as first made, so the always-there
     * ones (Listening, sync, a countdown…) became new channels without it. One still holding a running service's
     * notification cannot go yet; the next start takes it.
     */
    fun dropOld(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        // and every other (the user, 4 Nov): Android puts one app's count on all its icons, Phone's showing Mail's, so none at all
        for (id in listOf("listening", "sync", "analysis", "timer", "phone_call", "assistant_audio", "mail", "sms_in", "calendar_reminders", "timer_done",
            "alarm_soon", "phone_missed", "phone_ringing", "calls_from_computer", "calls_on_phone", "phone_blocked", "where", "assistant_briefs",
            "assistant_remote", "data_warnings", "maps_old", "files_from_computer")) runCatching { nm.deleteNotificationChannel(id) }
    }

    fun applet(channel: String?) = APPLET[channel] ?: "sync"

    /** An applet window's Home id (Applet's name, but Messages is "sms" and Photos "gallery" on Home). */
    fun homeOf(a: Applet) = when (a) { Applet.Messages -> "sms"; Applet.Photos -> "gallery"; else -> a.name.lowercase() }

    /** Tetra's notifications in the shade, newest first, group summaries left out (their children are listed). */
    fun now(c: Context): List<Notice> = runCatching { c.getSystemService(NotificationManager::class.java).activeNotifications }.getOrNull().orEmpty()
        .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
        .map { s -> val n = s.notification
            Notice(applet(n.channelId), n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty(),
                s.postTime, s.isOngoing, n.contentIntent, s.tag, s.id, n.channelId) }
        .sortedByDescending { it.at }

    /** What each applet has to read: its count on Home. A running timer or sync is not news. */
    fun counts(list: List<Notice>) = list.filter { !it.ongoing && it.channel in COUNTED }.groupingBy { it.applet }.eachCount()

    /** [applet] (Home's id) opened: its news seen, gone from the shade and from its number. */
    fun seen(c: Context, applet: String) = now(c).filter { it.applet == applet && !it.ongoing && it.channel in CLEARED }.forEach { dismiss(c, it) }

    fun dismiss(c: Context, n: Notice) = c.getSystemService(NotificationManager::class.java).cancel(n.tag, n.id)
}

/** Tetra's notifications, refreshed every two seconds while [on] (the shade tells an app nothing when they change). */
@Composable internal fun rememberNotices(on: Boolean = true): List<Notices.Notice> {
    val context = LocalContext.current
    val list by produceState(emptyList<Notices.Notice>(), on) { while (on) { value = Notices.now(context); delay(2000) } }
    return list
}

/** The bell's list: each applet's notifications under its icon and name; a tap opens it, × clears it. */
@Composable internal fun NoticeCenter(close: () -> Unit) {
    val context = LocalContext.current
    val all = rememberNotices()
    var gone by remember { mutableStateOf(setOf<Pair<String?, Int>>()) }
    LaunchedEffect(all) { gone = emptySet() }
    val list = all.filter { (it.tag to it.id) !in gone }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = close) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.common_close)) }
                    Text(stringResource(R.string.settings_notifications), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    if (list.any { !it.ongoing }) TextButton(onClick = { list.filter { !it.ongoing }.forEach { Notices.dismiss(context, it) }; gone = gone + list.filter { !it.ongoing }.map { it.tag to it.id } }) {
                        Text(stringResource(R.string.common_clear))
                    }
                }
                if (list.isEmpty()) Text(stringResource(R.string.notices_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.groupBy { it.applet }.forEach { (applet, notes) ->
                        item(key = "head:$applet") {
                            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Image(painterResource(Notices.ART[applet] ?: R.drawable.sync_art), null, Modifier.size(32.dp))
                                Text(HomeLayout.NAMES[applet]?.let { stringResource(it) } ?: applet, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text("${notes.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items(notes, key = { "${it.tag}:${it.id}" }) { n ->
                            Surface(shape = MaterialTheme.shapes.large, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().clickable(enabled = n.open != null) {
                                runCatching { n.open!!.send() }; close()
                            }) {
                                Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Image(painterResource(Notices.ART[applet] ?: R.drawable.sync_art), null, Modifier.size(24.dp))
                                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                        Text(n.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (n.text.isNotBlank()) Text(n.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                        Text(DateUtils.getRelativeTimeSpanString(n.at).toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (!n.ongoing) IconButton(onClick = { Notices.dismiss(context, n); gone = gone + (n.tag to n.id) }) {
                                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.common_remove), Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
