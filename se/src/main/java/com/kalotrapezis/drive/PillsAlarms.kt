package com.kalotrapezis.drive

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import java.time.Instant
import java.time.ZoneId

/*
 * Pills' reminders. One exact alarm, for the next ring only (a dose's time, or again 30 and 60 minutes after for one not
 * answered: Pills.AGAIN_MS), worked out from the files each time, as the Calendar's: nothing kept aside to go stale, so a
 * restart or a phone off loses nothing. One notification per dose time (its id from the minute), replaced by each ring,
 * never a second beside it. Voice (Settings in the applet, the user, 7 Nov: "a ding ding and then it says…"): the chime
 * and the words on the alarm stream, as an alarm passes Do Not Disturb's "alarms allowed"; the phone on silent or in Do
 * Not Disturb, the notification alone.
 */
internal object PillsAlarms {
    const val CHANNEL = "pills_nobadge"
    const val RING = "com.kalotrapezis.drive.PILLS_RING"
    const val TAKE = "com.kalotrapezis.drive.PILLS_TAKE"
    const val SKIP = "com.kalotrapezis.drive.PILLS_SKIP"
    const val TEST = "com.kalotrapezis.drive.PILLS_TEST"
    const val COLOR = 0xFFE5487A.toInt()

    /** Bumped when the files change behind the screen (a notification's Taken, AI Assist): the applet reads them again. */
    val changed = androidx.compose.runtime.mutableIntStateOf(0)

    fun prefs(c: Context) = c.getSharedPreferences("pills", Context.MODE_PRIVATE)
    fun voice(c: Context) = prefs(c).getBoolean("voice", true)
    /** Reminders ring on this device: a phone's yes, a tablet's no at first, so one dose is not answered twice (the user, 7 Nov). */
    fun ringsHere(c: Context) = prefs(c).getBoolean("here", c.resources.configuration.smallestScreenWidthDp < 600)
    fun setRingsHere(c: Context, on: Boolean) { prefs(c).edit().putBoolean("here", on).apply(); planSoon(c) }
    /** How the voice calls the user, as it should be said ("Θεολόγε": Greek's vocative, typed by the user, never guessed). */
    fun callName(c: Context) = prefs(c).getString("call", "").orEmpty()
    /** Private (the user, 8 Nov): neither the notification nor the voice names the medicine. */
    fun private(c: Context) = prefs(c).getBoolean("private", false)
    fun str(c: Context) = Str { id, a -> c.getString(id, *a) }

    fun planSoon(c: Context) { val app = c.applicationContext; Thread { runCatching { plan(app) }.onFailure { WriterLog.e("Pills: plan failed", it) } }.start() }

    private fun flags() = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    private fun ringIntent(c: Context) = PendingIntent.getBroadcast(c, 31, Intent(c, PillsReceiver::class.java).setAction(RING), flags())

    /**
     * Rings what is due since the last look, drops the notifications of doses answered since, and sets the next alarm.
     * [speak]: false after a boot or an update, when Android lets no media service start (and the moment has gone by).
     */
    @Synchronized fun plan(c: Context, speak: Boolean = true) {
        val dir = Pills.phoneDir()
        val meds = Pills.medicines(dir); val log = Pills.log(dir)
        val now = Instant.now(); val zone = ZoneId.systemDefault()
        val p = prefs(c)
        if (!ringsHere(c)) { // not this device: nothing rings, and a notification it showed goes
            c.getSystemService(AlarmManager::class.java)?.cancel(ringIntent(c)); p.edit().putLong("upTo", now.toEpochMilli()).apply()
            tidy(c, emptyList(), log, now, zone); return
        }
        val upTo = Instant.ofEpochMilli(p.getLong("upTo", now.toEpochMilli())).coerceAtLeast(now.minusMillis(Pills.MISSED_MS))
        // each dose time rung once per look, at its latest reminder since: a phone off all evening says it once
        // the voice once, for the latest; any other dose time rung with it gets its notification only. A dose put off rings
        // at the time it was put off to (and then forgets it), and not again by the clock before
        val later = Pills.later(dir)
        val putOff = later.filterValues { it != Pills.AT_PLACE && it > upTo.toEpochMilli() && it <= now.toEpochMilli() }.keys
        val due = Pills.doses(meds, log, now.minusSeconds(86_400L), now.plusSeconds(1), now, zone)
            .filter { it.state == Dose.State.Waiting || it.due in putOff && it.state == Dose.State.Missed }
            .groupBy { it.due }.filterKeys { d -> d in putOff || later[d] == null && Pills.AGAIN_MS.any { val t = d.plusMillis(it); t > upTo && t <= now } }.toSortedMap()
        putOff.forEach { Pills.setLater(dir, it, null) }
        // put-off doses answered meanwhile, or a day old, are forgotten
        val open = Pills.doses(meds, log, now.minusSeconds(86_400L), now.plusSeconds(1), now, zone).filter { it.state == Dose.State.Waiting || it.state == Dose.State.Missed }.map { it.due }.toSet()
        later.keys.filter { it !in open && it !in putOff }.forEach { Pills.setLater(dir, it, null) }
        due.entries.forEachIndexed { i, (at, doses) -> ring(c, at, doses, again = now.toEpochMilli() - at.toEpochMilli() > 60_000, speak = speak && i == due.size - 1) }
        p.edit().putLong("upTo", now.toEpochMilli()).apply()
        tidy(c, meds, log, now, zone)
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val next = Pills.nextRing(meds, log, now, zone, Pills.later(dir))
        if (next == null) { am.cancel(ringIntent(c)); return }
        val at = next.first.toEpochMilli()
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, ringIntent(c))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, ringIntent(c))
        WriterLog.i("Pills: next ring at ${next.first} for the doses of ${next.second}")
    }

    /** A dose time's notification gone once nothing at it waits (answered here, in the applet or by AI Assist). */
    private fun tidy(c: Context, meds: List<Medicine>, log: List<PillEvent>, now: Instant, zone: ZoneId) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        val waiting = Pills.doses(meds, log, now.minusSeconds(86_400L), now.plusSeconds(60), now, zone) // as long as Today shows it
            .filter { it.state == Dose.State.Waiting || it.state == Dose.State.Missed }.map { idOf(it.due) }.toSet()
        runCatching { nm.activeNotifications }.getOrNull().orEmpty().filter { it.notification.channelId == CHANNEL && it.id !in waiting && it.id != TEST_ID }
            .forEach { nm.cancel(it.id) }
    }

    fun idOf(due: Instant) = 40_000 + ((due.epochSecond / 60) % 50_000).toInt()
    private const val TEST_ID = 39_999

    fun channel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.pills_channel), NotificationManager.IMPORTANCE_HIGH).apply {
            description = c.getString(R.string.pills_channel_about); setShowBadge(false)
            setSound(chime(c), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        })
    }

    fun chime(c: Context): Uri = Uri.parse("android.resource://${c.packageName}/${R.raw.pill_chime}")

    /** The words under the title: each medicine and how many; a low stock's line after it, once a day up to its count. */
    private fun lines(c: Context, doses: List<Dose>, count: Boolean): Pair<String, List<String>> {
        val private = private(c)
        val what = if (private) c.getString(R.string.pills_private_text) else names(doses)
        val dir = Pills.phoneDir(); val today = java.time.LocalDate.now()
        val low = doses.map { it.med }.distinctBy { it.id }.mapNotNull { m0 ->
            val m = Pills.medicines(dir).firstOrNull { it.id == m0.id } ?: m0
            if (!m.low() || m.refillAsked >= m.refillTimes && m.refillOn != today) return@mapNotNull null
            if (count) Pills.refillSaid(dir, m.id, today)
            refillLine(str(c), m, private)
        }
        return what to low
    }

    fun names(doses: List<Dose>) = doses.joinToString(", ") { "${it.med.title} × ${Medicine.amount(it.med.dose)}" }
    fun refillLine(s: Str, m: Medicine, private: Boolean) =
        if (private) s.get(R.string.pills_refill_line_private, m.daysLeft() ?: 0) else s.get(R.string.pills_refill_line, m.title, m.daysLeft() ?: 0)

    fun build(c: Context, due: Instant, doses: List<Dose>, silent: Boolean, count: Boolean = true): android.app.Notification {
        channel(c)
        val id = idOf(due)
        val time = hhmm(c, due)
        val (what, low) = lines(c, doses, count)
        val text = (listOf(what) + low).joinToString("\n")
        fun act(a: String) = PendingIntent.getBroadcast(c, id * 4 + a.length, Intent(c, PillsReceiver::class.java).setAction(a).putExtra("due", due.toEpochMilli()), flags())
        val open = PendingIntent.getActivity(c, id, appletIntent(c, Applet.Pills.name), flags())
        // private: the lock screen shows this one, the time only
        val public = if (!private(c)) null else NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_pill).setColor(COLOR)
            .setContentTitle(c.getString(R.string.pills_reminder_title, time)).setContentText(c.getString(R.string.pills_private_text)).build()
        return NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_pill).setColor(COLOR)
            .setContentTitle(c.getString(R.string.pills_reminder_title, time)).setContentText(what)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setPriority(NotificationCompat.PRIORITY_HIGH)
            .apply { if (public != null) setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public) }
            .setContentIntent(open).setAutoCancel(false).setOngoing(true).setSilent(silent).setOnlyAlertOnce(false)
            .addAction(R.drawable.ic_check, c.getString(if (doses.size > 1) R.string.pills_taken_all else R.string.pills_taken), act(TAKE))
            .addAction(R.drawable.ic_close, c.getString(R.string.pills_skip), act(SKIP))
            .build()
    }

    /** Voice on, the phone neither silent nor in Do Not Disturb. */
    fun canSpeak(c: Context): Boolean {
        if (!voice(c)) return false
        val am = c.getSystemService(AudioManager::class.java) ?: return false
        val nm = c.getSystemService(NotificationManager::class.java)
        return am.ringerMode == AudioManager.RINGER_MODE_NORMAL && (nm == null || nm.currentInterruptionFilter <= NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    /** What the voice says: "Θεολόγε, είναι 21:00. Μην ξεχάσεις να πάρεις: Depon 500 mg." and the low stocks; the time now (21:30 when reminded again). */
    fun words(c: Context, doses: List<Dose>, low: List<String>): String =
        words(str(c), callName(c), hhmm(c, Instant.now()), doses.joinToString(", ") { d -> d.med.title + (foodWords(c, d.med.food)?.let { " $it" } ?: "") }, low, private(c))

    /** Private: "Θεολόγε, είναι 21:00. Πάρε το χάπι σου." and no name at all. */
    fun words(s: Str, call: String, time: String, names: String, low: List<String>, private: Boolean): String {
        val n = call.trim()
        val first = when {
            private && n.isEmpty() -> s.get(R.string.pills_voice_line_private_plain, time)
            private -> s.get(R.string.pills_voice_line_private, n, time)
            n.isEmpty() -> s.get(R.string.pills_voice_line, time, names)
            else -> s.get(R.string.pills_voice_line_named, n, time, names)
        }
        return (listOf(first) + low).joinToString(" ")
    }

    /** The ring's line in AI Assist's chat: the model's keeps the names (it must know which dose "I took it" means; it is never on the lock screen), the bubble the user sees drops them in private mode. */
    fun chatLines(s: Str, due: String, doses: List<Dose>, private: Boolean): Pair<String, String> =
        "Reminder (said to the user just now): time for ${names(doses)} (dose $due). If the user says they took it, put it off or skip it, this is the dose." to
            if (private) s.get(R.string.pills_chat_reminder_private, due) else s.get(R.string.pills_chat_reminder, names(doses), due)

    private fun foodWords(c: Context, food: String) = when (food) {
        "with" -> c.getString(R.string.pills_food_with); "before" -> c.getString(R.string.pills_food_before)
        "after" -> c.getString(R.string.pills_food_after); "empty" -> c.getString(R.string.pills_food_empty); else -> null
    }

    private fun ring(c: Context, due: Instant, doses: List<Dose>, again: Boolean, speak: Boolean) {
        WriterLog.i("Pills: ringing ${doses.size} dose(s) of $due${if (again) " again" else ""}${if (speak) "" else ", no voice"}")
        // into AI Assist's open conversation, so «το πήρα» right after is this dose (and the ten minutes start again)
        runCatching { val (model, shown) = chatLines(str(c), hhmm(c, due), doses, private(c)); SharedChat.ring(c, model, shown) }.onFailure { WriterLog.e("Pills: the chat line failed", it) }
        if (speak && canSpeak(c)) {
            val (_, low) = lines(c, doses, count = false)
            val start = Intent(c, PillsVoiceService::class.java).putExtra("due", due.toEpochMilli()).putExtra("words", words(c, doses, low))
            if (runCatching { c.startForegroundService(start) }.onFailure { WriterLog.e("Pills: the voice could not start", it) }.isSuccess) return
        }
        runCatching { NotificationManagerCompat.from(c).notify(idOf(due), build(c, due, doses, silent = false)) }
    }

    /** A dose put off until a place was reached (LocationReminders): rung now, its voice too, and forgotten. */
    fun atPlace(c: Context, due: Instant) {
        val dir = Pills.phoneDir(); val now = Instant.now()
        Pills.setLater(dir, due, null)
        val doses = Pills.doses(Pills.medicines(dir), Pills.log(dir), due, due.plusSeconds(60), now, ZoneId.systemDefault()).filter { it.state == Dose.State.Waiting || it.state == Dose.State.Missed }
        if (doses.isNotEmpty()) ring(c, due, doses, again = true, speak = true)
        plan(c)
    }

    /** The applet's Try it: the reminder as it will come, now, for the doses of [m] (any medicine), nothing written. */
    fun test(c: Context, m: Medicine?) {
        val due = Instant.now()
        val doses = listOfNotNull(m?.let { Dose(it, due, Dose.State.Waiting) })
        if (canSpeak(c)) {
            val words = if (doses.isEmpty()) c.getString(R.string.pills_voice_test) else words(c, doses, emptyList())
            c.startForegroundService(Intent(c, PillsVoiceService::class.java).putExtra("due", due.toEpochMilli()).putExtra("words", words).putExtra("test", true))
        } else {
            channel(c)
            runCatching { NotificationManagerCompat.from(c).notify(TEST_ID, NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_pill).setColor(COLOR)
                .setContentTitle(c.getString(R.string.pills_reminder_title, hhmm(c, due))).setContentText(c.getString(R.string.pills_test_text)).setAutoCancel(true).build()) }
        }
    }

    fun testNotification(c: Context, due: Instant): android.app.Notification {
        channel(c)
        return NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_pill).setColor(COLOR).setSilent(true)
            .setContentTitle(c.getString(R.string.pills_reminder_title, hhmm(c, due))).setContentText(c.getString(R.string.pills_test_text)).setAutoCancel(true).build()
    }

    /** A notification's Taken (all) or Skip: every dose at that time still waiting. */
    fun answer(c: Context, action: String, due: Instant) {
        val dir = Pills.phoneDir(); val now = Instant.now()
        val waiting = Pills.doses(Pills.medicines(dir), Pills.log(dir), due, due.plusSeconds(60), now, ZoneId.systemDefault()).filter { it.state == Dose.State.Waiting || it.state == Dose.State.Missed }
        waiting.forEach { if (action == TAKE) Pills.take(dir, it.med, it.due, now) else Pills.skip(dir, it.med, it.due, now) }
        NotificationManagerCompat.from(c).cancel(idOf(due))
        android.os.Handler(android.os.Looper.getMainLooper()).post { changed.intValue++ }
        ScopedSync.soon(c, "pills", orWhole = true) // the other devices hear of it at once
        plan(c)
    }
}

class PillsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val done = goAsync()
        val app = context.applicationContext
        WriterLog.init(app)
        Thread {
            runCatching {
                when (intent.action) {
                    PillsAlarms.TAKE, PillsAlarms.SKIP -> PillsAlarms.answer(app, intent.action!!, Instant.ofEpochMilli(intent.getLongExtra("due", 0)))
                    else -> PillsAlarms.plan(app)
                }
            }.onFailure { WriterLog.e("Pills: ${intent.action} failed", it) }
            done.finish()
        }.start()
    }
}

/**
 * The spoken reminder: in the foreground with the dose's own notification (silent: the chime plays here, on the alarm
 * stream), the chime, then the words by AI Assist's voice (Mistral's, when AI is on and there is a key) or the phone's,
 * then the notification left in the shade and the service gone.
 */
class PillsVoiceService : Service() {
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var tts: TextToSpeech? = null
    @Volatile private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.wrap(base))

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val due = Instant.ofEpochMilli(intent?.getLongExtra("due", 0) ?: 0)
        val words = intent?.getStringExtra("words").orEmpty()
        val test = intent?.getBooleanExtra("test", false) == true
        val dir = Pills.phoneDir()
        val doses = if (test) emptyList() else Pills.waitingAt(Pills.medicines(dir), Pills.log(dir), due, Instant.now(), ZoneId.systemDefault()).ifEmpty {
            Pills.doses(Pills.medicines(dir), Pills.log(dir), due, due.plusSeconds(60), Instant.now(), ZoneId.systemDefault()).filter { it.state == Dose.State.Missed } }
        val id = if (test) 39_999 else PillsAlarms.idOf(due)
        if (busy) { // speaking already: this one's notification beside, never in the place of the other
            if (!test && doses.isNotEmpty()) runCatching { NotificationManagerCompat.from(this).notify(id, PillsAlarms.build(this, due, doses, silent = false)) }
            return START_NOT_STICKY
        }
        val n = if (test || doses.isEmpty()) PillsAlarms.testNotification(this, due) else PillsAlarms.build(this, due, doses, silent = true)
        val started = runCatching { ServiceCompat.startForeground(this, id, n, if (Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0) }
        if (started.isFailure) { // Android refused (after a boot, say): the reminder as a plain notification, with its sound
            WriterLog.e("Pills: the voice was not allowed", started.exceptionOrNull())
            if (!test && doses.isNotEmpty()) runCatching { NotificationManagerCompat.from(this).notify(id, PillsAlarms.build(this, due, doses, silent = false, count = false)) }
            stopSelf(); return START_NOT_STICKY
        }
        busy = true
        tts = TextToSpeech(applicationContext) { } // made now: ready by the time the chime ends
        Thread {
            // music or a podcast steps down while it speaks
            val audio = getSystemService(AudioManager::class.java)
            val focus = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).build()
            runCatching { audio.requestAudioFocus(focus) }
            runCatching { chime(); speak(words) }.onFailure { WriterLog.e("Pills: the voice failed", it) }
            runCatching { audio.abandonAudioFocusRequest(focus) }
            // answered while it spoke (Taken on the notification): nothing left to show
            val left = !test && Pills.doses(Pills.medicines(dir), Pills.log(dir), due, due.plusSeconds(60), Instant.now(), ZoneId.systemDefault()).any { it.state == Dose.State.Waiting || it.state == Dose.State.Missed }
            ServiceCompat.stopForeground(this, if (left) ServiceCompat.STOP_FOREGROUND_DETACH else ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() { tts?.shutdown(); super.onDestroy() }

    private fun chime() {
        val done = java.util.concurrent.CountDownLatch(1)
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            p.setDataSource(this, PillsAlarms.chime(this)); p.setOnCompletionListener { done.countDown() }; p.setOnErrorListener { _, _, _ -> done.countDown(); true }
            p.prepare(); p.start(); done.await(5, java.util.concurrent.TimeUnit.SECONDS)
        } finally { runCatching { p.release() } }
        Thread.sleep(250)
    }

    private fun speak(words: String) {
        if (words.isBlank()) return
        if (AiGate.load(this).enabled && AssistantKey.has(this, AiProviders.of(this, AiPart.VOICE)) && runCatching { aiVoice(words) }.onFailure { WriterLog.w("Pills: AI Assist's voice: ${it.message}") }.getOrDefault(false)) return
        phone(words)
    }

    /** AI Assist's own voice, as its answers are read; false when it chose the phone's. */
    private fun aiVoice(words: String): Boolean {
        val voice = AssistantVoices.chosen(this)
        if (voice == AssistantVoices.PHONE) return false
        val rate = 24_000
        val t = AudioTrack.Builder().setAudioAttributes(attributes)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT), rate * 2))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        val written = java.util.concurrent.atomic.AtomicInteger(0)
        val late = java.util.concurrent.atomic.AtomicBoolean(false)
        val first = java.util.concurrent.CountDownLatch(1); val done = java.util.concurrent.CountDownLatch(1)
        try {
            t.play()
            Thread {
                runCatching { AssistantNet.speech(this, voice, words, null) { a -> if (late.get()) throw java.io.InterruptedIOException("too late"); first.countDown(); t.write(a, 0, a.size, AudioTrack.WRITE_BLOCKING); written.addAndGet(a.size) } }
                    .onFailure { if (!late.get()) WriterLog.w("Pills: AI Assist's voice: ${it.message}") }
                first.countDown(); done.countDown()
            }.start()
            // a slow network: the phone's voice after a few seconds, not a minute of silence after the chime
            if (!first.await(6, java.util.concurrent.TimeUnit.SECONDS) || written.get() == 0 && done.await(0, java.util.concurrent.TimeUnit.SECONDS)) { late.set(true); return false }
            done.await(30, java.util.concurrent.TimeUnit.SECONDS)
            val end = System.currentTimeMillis() + 20_000
            while (t.playbackHeadPosition < written.get() && System.currentTimeMillis() < end) Thread.sleep(40)
        } finally { late.set(true); runCatching { t.stop() }; t.release() }
        return written.get() > 0
    }

    private fun phone(words: String) {
        val t = tts ?: return
        val end = System.currentTimeMillis() + 4_000
        val done = java.util.concurrent.CountDownLatch(1)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onDone(id: String) { done.countDown() }
            @Deprecated("Deprecated in Java") override fun onError(id: String) { done.countDown() }
        })
        while (System.currentTimeMillis() < end) {
            t.setAudioAttributes(attributes)
            t.language = resources.configuration.locales[0]
            if (t.speak(words, TextToSpeech.QUEUE_FLUSH, null, "pill") == TextToSpeech.SUCCESS) { done.await(30, java.util.concurrent.TimeUnit.SECONDS); return }
            Thread.sleep(200) // not ready yet
        }
        WriterLog.w("Pills: no text-to-speech")
    }
}

/** A string by its id, as Context.getString; a plain function in the unit tests. */
internal fun interface Str { fun get(id: Int, vararg a: Any): String }
