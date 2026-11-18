package com.kalotrapezis.drive

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The Timer's changes, from the screen, Home or a notification's button: the file written once, then sent at once. */
internal object TimerControl {
    fun dir() = TimerFiles.root(TetraFolder.root(Environment.getExternalStorageDirectory()))
    fun device() = Build.MODEL ?: "Android"

    /** The clock opens on the length last started here (five minutes the first time). */
    fun last(c: Context) = c.getSharedPreferences("timer", Context.MODE_PRIVATE).getLong("last", 300L)
    private fun used(c: Context, seconds: Long) = c.getSharedPreferences("timer", Context.MODE_PRIVATE).edit().putLong("last", seconds).apply()

    fun start(c: Context, seconds: Long): TimerRecord {
        used(c, seconds)
        return TimerFiles.start(dir(), seconds, device()).also { sent(c) }
    }

    /** Pause, resume, stop, OK, start again, pin, unpin or delete ([TimerService] actions) on the timer [id], as its file is now. */
    fun change(c: Context, id: String, action: String) {
        val r = TimerFiles.read(dir()).firstOrNull { it.id == id } ?: return
        val now = Instant.now()
        val next = when (action) {
            TimerService.PAUSE -> r.pause(now, device())
            TimerService.RESUME -> r.resume(now, device())
            TimerService.SNOOZE -> r.snooze(now, device())
            TimerService.STOP, TimerService.OK -> if (r.state == TimerRecord.STOPPED) r else r.stop(device())
            TimerService.RESTART -> r.restart(now, device()).also { used(c, r.seconds) }
            TimerService.PIN -> if (r.pinned) r else r.pin(true, device())
            TimerService.UNPIN -> if (!r.pinned) r else r.pin(false, device())
            TimerService.TRASH -> r.trash(device())
            else -> r
        }
        if (next === r) return
        TimerFiles.write(dir(), next)
        sent(c)
    }

    fun alarmAdd(c: Context, time: java.time.LocalTime, days: Set<java.time.DayOfWeek>, label: String): AlarmRecord =
        TimerAlarmFiles.add(dir(), time, days, label, device(), Instant.now(), ZoneId.systemDefault()).also { sent(c) }

    /** An alarm's new time, days or label, as edited. */
    fun alarmEdit(c: Context, r: AlarmRecord, time: java.time.LocalTime, days: Set<java.time.DayOfWeek>, label: String) {
        val now = Instant.now(); val zone = ZoneId.systemDefault()
        TimerAlarmFiles.write(dir(), r.copy(time = time, days = days, label = label).switchOn(now, zone, device()))
        sent(c)
    }

    /** Dismiss, Snooze, Skip (the next one), on, off or delete ([TimerService]'s ALARM_ actions) on the alarm [id]. */
    fun alarmChange(c: Context, id: String, action: String) {
        val r = TimerAlarmFiles.alarms(dir()).firstOrNull { it.id == id } ?: return
        val now = Instant.now(); val zone = ZoneId.systemDefault()
        val next = when (action) {
            TimerService.ALARM_DISMISS -> r.ringing(now, zone)?.let { r.dismiss(it, device()) }
            // not ringing yet (the 30-minute notice): the next ring answered, ten minutes later instead
            TimerService.ALARM_SNOOZE -> r.ringing(now, zone)?.let { r.snoozed(it, now, device()) } ?: r.next(now, zone)?.let { r.snoozed(it, it, device()) }
            TimerService.ALARM_SKIP -> r.next(now, zone)?.let { r.dismiss(it, device()) }
            TimerService.ALARM_ON -> r.switchOn(now, zone, device())
            TimerService.ALARM_OFF -> r.switchOff(device())
            TimerService.ALARM_DELETE -> r.delete(device())
            else -> null
        } ?: return
        TimerAlarmFiles.write(dir(), next)
        sent(c)
    }

    /** The stopwatch's Start, Pause, Lap or Reset ([TimerService]'s WATCH_ actions). */
    fun stopwatch(c: Context, action: String) {
        val r = TimerAlarmFiles.stopwatch(dir())
        val now = Instant.now()
        val next = when (action) {
            TimerService.WATCH_START -> r.start(now, device())
            TimerService.WATCH_HOLD -> r.hold(now, device())
            TimerService.WATCH_LAP -> r.lap(now, device())
            TimerService.WATCH_RESET -> r.reset(device())
            else -> r
        }
        if (next === r) return
        TimerAlarmFiles.write(dir(), next)
        sent(c)
    }

    private fun sent(c: Context) {
        ScopedSync.soon(c, "timer", 0, orWhole = true) // to the other devices now; they stop, pause or start with it
        TimerService.check(c)
    }
}

/**
 * Runs while a timer is running, paused or ringing (Plan.md § Timer): its ongoing notification (the Hyper Island on a
 * Xiaomi; a countdown chronometer elsewhere) with Pause/Resume and Stop, the alarm and its OK at the end, and a look
 * at the other devices every 30 s (the user: "sync check every 30 sec"), besides the push each change makes. The end
 * itself is an alarm clock, so it rings on time with the screen off; the files say what is true, read each second.
 * It also rings the alarms (Dismiss, Snooze, the ring screen over the lock screen) and shows the stopwatch while it counts
 * or is held; [AlarmPlan] sets the next alarm's clock and its notification while this is not running.
 */
class TimerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var shown = emptyMap<String, String>() // id → what its notification shows, to post only on a change
    private var player: MediaPlayer? = null
    private var wake: PowerManager.WakeLock? = null
    private var lastLook = 0L
    private var foreground = 0
    private var front = false // Tetra in front: HyperOS gives it no island, so the notifications are posted again when it leaves

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        channels(this)
        scope.launch { while (isActive) { runCatching { tick() }.onFailure { WriterLog.e("Timer: tick failed", it) }; delay(1000) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android wants the notification at once: the first timer's as it is, or a placeholder the tick takes away
        val now = Instant.now()
        val first = runCatching { TimerFiles.active(TimerControl.dir(), now) }.getOrDefault(emptyList()).firstOrNull()
        val n = first?.let { notification(it, now) } ?: NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_timer).setContentTitle(getString(R.string.app_timer)).setSilent(true).build()
        foreground = first?.let { notificationId(it.id) } ?: PLACEHOLDER
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(foreground, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(foreground, n)
        }.onFailure { WriterLog.e("Timer: could not run in the foreground", it) }
        val id = intent?.getStringExtra(ID)
        val action = intent?.action
        if (id != null && action != null && action.startsWith(ALARM)) scope.launch { runCatching { TimerControl.alarmChange(this@TimerService, id, action) }; tick() }
        else if (action != null && action.startsWith(WATCH)) scope.launch { runCatching { TimerControl.stopwatch(this@TimerService, action) }; tick() }
        else if (id != null && action != null && action != CHECK && action != RING) scope.launch { runCatching { TimerControl.change(this@TimerService, id, action) }; tick() }
        if (action == RING || action == ALARM_RING) lastLook = 0 // ringing: ask the others at once whether it was answered there
        if (action == ALARM_RING) scope.launch(Dispatchers.IO) { runCatching { AlarmPlan.plan(this@TimerService) } } // the clock for the one after
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        quiet()
        super.onDestroy()
    }

    /** What is shown now: an id, what it shows (posted again only on a change), and its notification. */
    private class Shown(val id: Int, val says: String, val ringing: Boolean, val make: (announce: Boolean) -> Notification)

    @Synchronized private fun tick() {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val dir = TimerControl.dir()
        val active = TimerFiles.active(dir, now)
        val alarms = TimerAlarmFiles.ringing(dir, now, zone)
        val watch = TimerAlarmFiles.stopwatch(dir)
        val items = active.map { r -> Shown(notificationId(r.id), "${r.rev}|${r.state}|${r.ringing(now)}", r.ringing(now)) { a -> notification(r, now, a) } } +
            alarms.map { (a, at) -> Shown(8000 + (a.id.hashCode() and 0xff), "${a.rev}|$at", true) { _ -> alarmNotification(a, at) } } +
            listOfNotNull(watch.takeIf { it.state != StopwatchRecord.RESET }?.let { w -> Shown(WATCH_ID, "${w.rev}|${w.state}", false) { a -> stopwatchNotification(w, now, a) } })
        val nm = NotificationManagerCompat.from(this)
        if (items.isEmpty()) {
            quiet()
            alarm(null)
            shown.keys.forEach { nm.cancel(it.toInt()) }
            shown = emptyMap()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (items.any { it.ringing }) ring() else quiet()
        // Tetra left the front: everything posted again, quietly, so HyperOS raises the island it gave none while Tetra was open
        val left = front && !CalendarAlarms.front
        front = CalendarAlarms.front
        // what each shows; a change (a pause here or there, a new timer, the end) posts it again
        val now2 = items.associate { it.id.toString() to it.says }
        if (now2 != shown || left) {
            items.forEach { r ->
                if (shown[r.id.toString()] == r.says && !left) return@forEach
                runCatching { nm.notify(r.id, r.make(shown[r.id.toString()] == null || (r.ringing && shown[r.id.toString()] != r.says))) }
            }
            (shown.keys - now2.keys).forEach { nm.cancel(it.toInt()) }
            // the foreground notification is the first one's; the placeholder goes
            val first = items.first()
            if (first.id != foreground) {
                runCatching { if (Build.VERSION.SDK_INT >= 34) startForeground(first.id, first.make(false), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(first.id, first.make(false)) }
                if (foreground == PLACEHOLDER) nm.cancel(PLACEHOLDER)
                foreground = first.id
            }
            shown = now2
            alarm(active.filter { it.state == TimerRecord.RUNNING && !it.ringing(now) }.mapNotNull { it.ends }.minOrNull())
        }
        // the 30 s look at the other devices: Stop or OK there stops it here even if their push was missed
        if (System.currentTimeMillis() - lastLook >= LOOK_MS) {
            lastLook = System.currentTimeMillis()
            scope.launch(Dispatchers.IO) {
                val store = SyncStore(applicationContext)
                if (store.pairing() != null && !SyncService.isRunning) runCatching { SyncClient(applicationContext, store).scopeOnly("timer") }
            }
        }
    }

    /** An alarm ringing: its time and label, Snooze and Dismiss, and the ring screen over the lock screen. */
    private fun alarmNotification(a: AlarmRecord, at: Instant): Notification {
        val time = hhmm(this, at)
        val title = a.label.ifEmpty { getString(R.string.alarm_named) }
        val buttons = listOf(Triple(ALARM_SNOOZE, getString(R.string.calendar_snooze), action(a.id, ALARM_SNOOZE)), Triple(ALARM_DISMISS, getString(R.string.common_stop), action(a.id, ALARM_DISMISS)))
        val icons = listOf(R.drawable.ic_timer_snooze, R.drawable.ic_timer_stop)
        val screen = PendingIntent.getActivity(this, 6, Intent(this, AlarmRingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(this, CHANNEL_DONE).setSmallIcon(R.drawable.ic_timer).setColor(COLOR)
            .setContentTitle("$time · $title").setContentText(getString(R.string.alarm_ringing)).setContentIntent(screen).setFullScreenIntent(screen, true)
            .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_MAX).setOngoing(true).setAutoCancel(false).setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        iconButtons(this, b, "$time · $title", getString(R.string.alarm_ringing), buttons, icons)
        if (HyperIsland.isSupported(this)) HyperIsland.timer(b, title, time, null, time, true, buttons, HyperIsland.ALARM, icons = icons)
        return b.build()
    }

    /** The stopwatch counting (Android's own chronometer, the island's count-up) or held, with its buttons. */
    private fun stopwatchNotification(w: StopwatchRecord, now: Instant, announce: Boolean): Notification {
        val counting = w.state == StopwatchRecord.COUNTING
        val title = getString(R.string.stopwatch)
        val shown = stopwatchText(w.elapsedMs(now), tenths = false)
        val text = if (counting) getString(R.string.stopwatch_counting) else getString(R.string.stopwatch_held, shown)
        val buttons = listOf(if (counting) Triple(WATCH_HOLD, getString(R.string.common_pause), watchAction(WATCH_HOLD)) else Triple(WATCH_START, getString(R.string.common_resume), watchAction(WATCH_START)),
            Triple(WATCH_RESET, getString(R.string.common_stop), watchAction(WATCH_RESET)))
        val icons = listOf(if (counting) R.drawable.ic_timer_pause else R.drawable.ic_timer_play, R.drawable.ic_timer_stop)
        val b = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_timer).setColor(COLOR).setContentTitle(title).setContentText(text)
            .setContentIntent(openTimer()).setCategory(NotificationCompat.CATEGORY_STOPWATCH).setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true).setSilent(true).setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (counting && w.started != null) b.setWhen(w.started.toEpochMilli()).setShowWhen(true).setUsesChronometer(true)
        iconButtons(this, b, title, text, buttons, icons)
        if (HyperIsland.isSupported(this)) HyperIsland.timer(b, title, text, w.started?.toEpochMilli()?.takeIf { counting }, shown, announce, buttons, HyperIsland.STOPWATCH, countUp = true, icons = icons)
        return b.build()
    }

    private fun openTimer() = PendingIntent.getActivity(this, 0, appletIntent(this, SCREEN), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun watchAction(what: String) = PendingIntent.getForegroundService(this, what.hashCode(),
        Intent(this, TimerService::class.java).setAction(what), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notification(r: TimerRecord, now: Instant, announce: Boolean = false): Notification {
        val ringing = r.ringing(now)
        val name = r.label.ifEmpty { getString(R.string.timer_named, durationText(uiWords(this), r.seconds)) }
        val endsAt = r.ends?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a", uiLocale()))
        val title = when { ringing -> getString(R.string.timer_times_up); r.state == TimerRecord.PAUSED -> getString(R.string.timer_paused_left, clockText(r.leftMs(now))); else -> name }
        val text = when { ringing -> name; r.state == TimerRecord.PAUSED -> name; else -> getString(R.string.timer_ends_at, endsAt.toString()) }
        val buttons = when {
            ringing -> listOf(Triple(SNOOZE, getString(R.string.alarm_snooze), action(r.id, SNOOZE)), Triple(OK, getString(R.string.common_stop), action(r.id, OK)))
            r.state == TimerRecord.PAUSED -> listOf(Triple(RESUME, getString(R.string.common_resume), action(r.id, RESUME)), Triple(STOP, getString(R.string.common_stop), action(r.id, STOP)))
            else -> listOf(Triple(PAUSE, getString(R.string.common_pause), action(r.id, PAUSE)), Triple(STOP, getString(R.string.common_stop), action(r.id, STOP)))
        }
        val icons = listOf(when { ringing -> R.drawable.ic_timer_snooze; r.state == TimerRecord.PAUSED -> R.drawable.ic_timer_play; else -> R.drawable.ic_timer_pause }, R.drawable.ic_timer_stop)
        val open = openTimer()
        val b = NotificationCompat.Builder(this, if (ringing) CHANNEL_DONE else CHANNEL)
            .setSmallIcon(R.drawable.ic_timer)
            .setColor(COLOR)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setCategory(if (ringing) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STOPWATCH)
            .setPriority(if (ringing) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(!ringing) // the alarm's own sound plays; the notification only pops up
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (r.state == TimerRecord.RUNNING && !ringing && r.ends != null) // a countdown Android draws itself, no update each second
            b.setWhen(r.ends.toEpochMilli()).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        iconButtons(this, b, title, text, buttons, icons)
        if (HyperIsland.isSupported(this)) HyperIsland.timer(b, title, text, r.ends?.toEpochMilli()?.takeIf { r.state == TimerRecord.RUNNING && !ringing },
            if (ringing) getString(R.string.timer_times_up) else clockText(r.leftMs(now)), announce, buttons, icons = icons)
        return b.build()
    }

    private fun action(id: String, what: String) = PendingIntent.getForegroundService(this, (id + what).hashCode(),
        Intent(this, TimerService::class.java).setAction(what).putExtra(ID, id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** The next end as an alarm clock (on time in Doze, the screen off); none left, none set. */
    private fun alarm(at: Instant?) {
        val am = getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getForegroundService(this, 1, Intent(this, TimerService::class.java).setAction(RING), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (at == null) return am.cancel(pi)
        val show = PendingIntent.getActivity(this, 2, appletIntent(this, SCREEN), PendingIntent.FLAG_IMMUTABLE)
        runCatching { am.setAlarmClock(AlarmManager.AlarmClockInfo(at.toEpochMilli(), show), pi) }.onFailure { WriterLog.e("Timer: no alarm clock", it) }
    }

    private fun ring() {
        if (player != null) return
        WriterLog.i("Timer: ringing")
        wake = (getSystemService(PowerManager::class.java))?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Tetra:timer")?.apply { acquire(TimerRecord.UNANSWERED_MS) }
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(this@TimerService, uri)
                isLooping = true
                prepare()
                start()
            }
        }.onFailure { WriterLog.e("Timer: the alarm sound could not play", it) }.getOrNull()
    }

    private fun quiet() {
        player?.let { runCatching { it.stop(); it.release() } }
        player = null
        wake?.let { if (it.isHeld) it.release() }
        wake = null
    }

    companion object {
        const val PAUSE = "com.kalotrapezis.drive.TIMER_PAUSE"
        const val RESUME = "com.kalotrapezis.drive.TIMER_RESUME"
        const val STOP = "com.kalotrapezis.drive.TIMER_STOP"
        const val OK = "com.kalotrapezis.drive.TIMER_OK"
        const val SNOOZE = "com.kalotrapezis.drive.TIMER_SNOOZE"
        const val RESTART = "com.kalotrapezis.drive.TIMER_RESTART"
        const val PIN = "com.kalotrapezis.drive.TIMER_PIN"
        const val UNPIN = "com.kalotrapezis.drive.TIMER_UNPIN"
        const val TRASH = "com.kalotrapezis.drive.TIMER_TRASH"
        private const val RING = "com.kalotrapezis.drive.TIMER_RING"
        private const val ALARM = "com.kalotrapezis.drive.ALARM_"
        const val ALARM_RING = "com.kalotrapezis.drive.ALARM_RING"
        const val ALARM_DISMISS = "com.kalotrapezis.drive.ALARM_DISMISS"
        const val ALARM_SNOOZE = "com.kalotrapezis.drive.ALARM_SNOOZE"
        const val ALARM_SKIP = "com.kalotrapezis.drive.ALARM_SKIP"
        const val ALARM_ON = "com.kalotrapezis.drive.ALARM_ON"
        const val ALARM_OFF = "com.kalotrapezis.drive.ALARM_OFF"
        const val ALARM_DELETE = "com.kalotrapezis.drive.ALARM_DELETE"
        private const val WATCH = "com.kalotrapezis.drive.WATCH_"
        const val WATCH_START = "com.kalotrapezis.drive.WATCH_START"
        const val WATCH_HOLD = "com.kalotrapezis.drive.WATCH_HOLD"
        const val WATCH_LAP = "com.kalotrapezis.drive.WATCH_LAP"
        const val WATCH_RESET = "com.kalotrapezis.drive.WATCH_RESET"
        private const val WATCH_ID = 7990
        private const val CHECK = "com.kalotrapezis.drive.TIMER_CHECK"
        private const val ID = "id"
        /** MainActivity's word for the Timer screen (Applet.EXTRA), as an applet icon's. */
        const val SCREEN = "Timer"
        const val CHANNEL = "timer_nobadge" // a countdown is no count on the icon (4 Nov); "timer_done" still counts
        private const val CHANNEL_DONE = "timer_done_nobadge"
        const val CHANNEL_SOON = "alarm_soon_nobadge"
        private const val PLACEHOLDER = 7999
        private const val LOOK_MS = 30_000L
        const val COLOR = 0xFF26A69A.toInt()
        @Volatile var running = false; private set

        private fun notificationId(id: String) = 7000 + (id.hashCode() and 0x3ff)

        fun channels(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.app_timer), NotificationManager.IMPORTANCE_LOW).apply { description = c.getString(R.string.timer_channel_about); setShowBadge(false) })
            // the 30-minute notice pops up without a sound: it may come while the user still sleeps
            if (nm.getNotificationChannel(CHANNEL_SOON) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL_SOON, c.getString(R.string.alarm_channel_soon), NotificationManager.IMPORTANCE_HIGH).apply { setShowBadge(false);
                description = c.getString(R.string.alarm_channel_soon_about); setSound(null, null)
            })
            if (nm.getNotificationChannel(CHANNEL_DONE) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, c.getString(R.string.timer_channel_done), NotificationManager.IMPORTANCE_HIGH).apply { setShowBadge(false);
                description = c.getString(R.string.timer_channel_done_about); setSound(null, null) // the alarm plays its own sound
            })
        }

        /**
         * After anything that may have changed the Timer's files (a change here, a sync): the service runs while there is
         * a timer. From the background Android may refuse to start it; an alarm a moment later is allowed to.
         */
        fun check(c: Context) {
            val app = c.applicationContext
            runCatching { AlarmPlan.plan(app) }.onFailure { WriterLog.e("Alarms: plan failed", it) }
            if (running) return // its next tick reads the files
            val dir = TimerControl.dir()
            val now = Instant.now()
            if (runCatching { TimerFiles.active(dir, now).isEmpty() && TimerAlarmFiles.ringing(dir, now, ZoneId.systemDefault()).isEmpty() &&
                    TimerAlarmFiles.stopwatch(dir).state == StopwatchRecord.RESET }.getOrDefault(true)) return
            val intent = Intent(app, TimerService::class.java).setAction(CHECK)
            runCatching { ContextCompat.startForegroundService(app, intent) }.onFailure {
                WriterLog.w("Timer: started by an alarm (${it.javaClass.simpleName})")
                val am = app.getSystemService(AlarmManager::class.java) ?: return
                val pi = PendingIntent.getForegroundService(app, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 500, pi) }
            }
        }
    }
}

/** "07:30" or "7:30 AM", as the phone shows the time. */
internal fun hhmm(c: Context, at: Instant): String = at.atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(c)) "HH:mm" else "h:mm a", c.uiLocale()))

/**
 * The buttons as icons only, zzz or ‖ or ▶, and × (the user, 1 Nov: "no text just the buttons", as Calendar's island ✕): Android draws an
 * action's words, so the body is Tetra's own view; each icon carries its [buttons] label for TalkBack.
 */
internal fun iconButtons(c: Context, b: NotificationCompat.Builder, title: String, text: String, buttons: List<Triple<String, String, PendingIntent>>, icons: List<Int>) {
    val v = android.widget.RemoteViews(c.packageName, R.layout.timer_notification)
    v.setTextViewText(R.id.title, title); v.setTextViewText(R.id.text, text)
    listOf(R.id.button1, R.id.button2).forEachIndexed { i, id ->
        val (_, label, pi) = buttons[i]
        v.setImageViewResource(id, icons[i]); v.setOnClickPendingIntent(id, pi); v.setContentDescription(id, label)
    }
    b.setStyle(NotificationCompat.DecoratedCustomViewStyle()).setCustomContentView(v).setCustomBigContentView(v).setCustomHeadsUpContentView(v)
}

/** "1:02.3", "12:05:41": the stopwatch's time, tenths while it is on the screen. */
internal fun stopwatchText(ms: Long, tenths: Boolean = true): String {
    val t = ms / 1000
    val h = t / 3600; val m = t % 3600 / 60; val s = t % 60
    val main = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    return if (tenths) main + "." + (ms % 1000 / 100) else main
}

/**
 * The next alarm, whether or not the Timer runs (Plan.md § Timer › Alarms): its alarm clock (on time in Doze, the screen
 * off; it starts [TimerService], which rings it), and from [SOON_MS] before it a quiet notification — the island on a
 * Xiaomi — with the time, zzz Snooze and × Skip; from [ASK_MS] before, the same popping up once (the user, 30 Oct:
 * "30 min before and ask if I want to skip or snooze"). Both answers are written in the alarm's file, so they sync and
 * outlive a reboot. Made again at each change, sync, boot, clock or zone change, and at each ring.
 */
internal object AlarmPlan {
    const val LOOK = "com.kalotrapezis.drive.ALARM_LOOK"
    const val SKIP = "com.kalotrapezis.drive.ALARM_PLAN_SKIP"
    const val SNOOZE = "com.kalotrapezis.drive.ALARM_PLAN_SNOOZE"
    private const val SOON_MS = 12 * 3600_000L
    private const val ASK_MS = 30 * 60_000L
    private const val NEXT_ID = 7991

    @Synchronized fun plan(c: Context) {
        val app = c.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val now = Instant.now()
        val next = TimerAlarmFiles.upcoming(TimerControl.dir(), now, ZoneId.systemDefault())
        val ring = PendingIntent.getForegroundService(app, 4, Intent(app, TimerService::class.java).setAction(TimerService.ALARM_RING), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val look = PendingIntent.getBroadcast(app, 5, Intent(app, CalendarAlarmReceiver::class.java).setAction(LOOK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nm = NotificationManagerCompat.from(app)
        am.cancel(look)
        if (next == null) { am.cancel(ring); nm.cancel(NEXT_ID); return }
        val (a, at) = next
        val show = PendingIntent.getActivity(app, 7, appletIntent(app, TimerService.SCREEN), PendingIntent.FLAG_IMMUTABLE)
        runCatching { am.setAlarmClock(AlarmManager.AlarmClockInfo(at.toEpochMilli(), show), ring) }.onFailure { WriterLog.e("Alarms: no alarm clock", it) }
        if (at.toEpochMilli() - now.toEpochMilli() > SOON_MS) {
            nm.cancel(NEXT_ID)
            runCatching { am.set(AlarmManager.RTC, at.toEpochMilli() - SOON_MS, look) }
            return
        }
        val ask = at.toEpochMilli() - now.toEpochMilli() <= ASK_MS
        if (!ask) runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli() - ASK_MS, look) }
        TimerService.channels(app)
        val time = hhmm(app, at)
        val title = app.getString(if (ask) R.string.alarm_soon else R.string.alarm_next, time)
        fun answer(what: String) = PendingIntent.getBroadcast(app, (a.id + what).hashCode(), Intent(app, CalendarAlarmReceiver::class.java).setAction(what).putExtra("id", a.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // zzz and ×, as the ringing alarm's (the user, 1 Nov: the buttons on the coming alarm too); × skips this one
        val buttons = listOf(Triple("snooze", app.getString(R.string.calendar_snooze), answer(SNOOZE)), Triple("skip", app.getString(R.string.alarm_skip), answer(SKIP)))
        val icons = listOf(R.drawable.ic_timer_snooze, R.drawable.ic_timer_stop)
        // pops up once per ring: not again when planned anew (a sync, a reboot) for the same one
        val prefs = app.getSharedPreferences("timer", Context.MODE_PRIVATE)
        val asked = "${a.id}|$at"
        val pop = ask && prefs.getString("asked", null) != asked
        if (pop) prefs.edit().putString("asked", asked).apply()
        val b = NotificationCompat.Builder(app, if (ask) TimerService.CHANNEL_SOON else TimerService.CHANNEL).setSmallIcon(R.drawable.ic_timer).setColor(TimerService.COLOR)
            .setContentTitle(title).setContentText(a.label.ifEmpty { app.getString(R.string.alarm_named) }).setContentIntent(show)
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setPriority(if (ask) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setOngoing(true).setSilent(!pop).setOnlyAlertOnce(true)
        iconButtons(app, b, title, a.label.ifEmpty { app.getString(R.string.alarm_named) }, buttons, icons)
        // the notice is a plain one: HyperOS's card draws one button of the two (seen on the Xiaomi 15, 30 Oct)
        if (!ask && HyperIsland.isSupported(app)) HyperIsland.timer(b, title, a.label, null, time, false, buttons, HyperIsland.ALARM, icons = icons)
        runCatching { nm.notify(NEXT_ID, b.build()) }
    }
}
