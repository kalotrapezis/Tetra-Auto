package com.kalotrapezis.drive

import org.json.JSONObject
import java.io.File
import java.time.Instant

/*
 * Tetra's Timer (Plan.md § Timer). A timer is one small JSON file in Tetra/.System/Timer/Shared (~/Tetra/Files/Timer on the computer),
 * synced as files are, at once (DriveScope "timer"). Each device counts down by itself from the end time in it, so they
 * all show the same time left without any traffic while it runs; Start, Pause, Resume, Stop and OK each write the file
 * once and push it. The same format on the desktop (desktop/timer.js), so Claude can read or start one by hand:
 *
 *   {"id":"k3f9a2","seconds":300,"label":"","state":"running","ends":"2026-10-18T16:05:00Z","left":0,"rev":1,"by":"Tablet","pinned":false}
 *
 * state: running (counts down to `ends`), paused (`left` seconds remain), stopped. `pinned`: the card stays when it is
 * stopped or done, ready to start again (the user, 18 Oct: "a pin at the top left"); the trash takes it away. `rev` goes up by one at each change and
 * the highest wins (equal: stopped, then paused, then running), so clocks a few seconds apart never decide anything.
 */
internal data class TimerRecord(
    val id: String,
    val seconds: Long,
    val state: String,
    val ends: Instant?,
    val left: Double,
    val rev: Int,
    val label: String = "",
    val by: String = "",
    val file: File? = null,
    val pinned: Boolean = false,
) {
    /** Time left in ms by this device's clock: none once it has run out (it rings) or stopped. */
    fun leftMs(now: Instant): Long = when (state) {
        RUNNING -> ends?.let { (it.toEpochMilli() - now.toEpochMilli()).coerceAtLeast(0) } ?: 0
        PAUSED -> (left * 1000).toLong()
        else -> 0
    }
    fun ringing(now: Instant) = state == RUNNING && ends != null && !now.isBefore(ends) && !over(now)
    /** Stopped, or ran out long ago with nobody there to tap OK: shown nowhere, its file free for the next timer. */
    fun over(now: Instant) = state == STOPPED || (state == RUNNING && (ends == null || now.isAfter(ends.plusMillis(UNANSWERED_MS))))

    /** On the screen: counting, paused or ringing, or pinned (then ready to start again when over). */
    fun card(now: Instant) = !over(now) || pinned
    /** Free for the next new timer: over, and not kept by a pin. */
    fun free(now: Instant) = over(now) && !pinned

    fun pause(now: Instant, by: String) = if (state != RUNNING) this else copy(state = PAUSED, left = leftMs(now) / 1000.0, ends = null, rev = rev + 1, by = by)
    fun resume(now: Instant, by: String) = if (state != PAUSED) this else copy(state = RUNNING, ends = now.plusMillis((left * 1000).toLong()), left = 0.0, rev = rev + 1, by = by)
    /** A ringing timer's zzz (as an alarm's Snooze, 1 Nov): it rings again in a minute. */
    fun snooze(now: Instant, by: String) = if (!ringing(now)) this else copy(ends = now.plusSeconds(60), rev = rev + 1, by = by)
    fun stop(by: String) = copy(state = STOPPED, ends = null, left = 0.0, rev = rev + 1, by = by)
    /** A pinned card's ▶: the same timer from the start again. */
    fun restart(now: Instant, by: String) = copy(state = RUNNING, ends = now.plusSeconds(seconds), left = 0.0, rev = rev + 1, by = by)
    fun pin(on: Boolean, by: String) = copy(pinned = on, rev = rev + 1, by = by)
    /** The trash: the card goes, on every device (a running one stops). */
    fun trash(by: String) = copy(state = STOPPED, ends = null, left = 0.0, pinned = false, rev = rev + 1, by = by)

    fun json(): String = JSONObject().put("id", id).put("seconds", seconds).put("label", label).put("state", state)
        .put("ends", ends?.toString() ?: JSONObject.NULL).put("left", left).put("rev", rev).put("by", by).put("pinned", pinned).toString(1) + "\n"

    companion object {
        const val RUNNING = "running"
        const val PAUSED = "paused"
        const val STOPPED = "stopped"
        /** A timer nobody answered rings for ten minutes at most, then is over. */
        const val UNANSWERED_MS = 10 * 60_000L
        private val ORDER = listOf(RUNNING, PAUSED, STOPPED)

        /** A file's record; anything that is not one (a stray file, half a write) is null, never an error. */
        fun parse(text: String, file: File? = null): TimerRecord? = runCatching {
            val o = JSONObject(text)
            if (o.optString("kind", "timer") != "timer") return null // an alarm or the stopwatch (TimerAlarms.kt)
            val state = o.optString("state", RUNNING).lowercase().takeIf { it in ORDER } ?: return null
            val ends = o.optString("ends", "").takeIf { it.isNotEmpty() && it != "null" }?.let { Instant.parse(it) }
            TimerRecord(o.optString("id").ifEmpty { file?.nameWithoutExtension ?: return null }, o.optLong("seconds"), state, ends,
                o.optDouble("left", 0.0).takeIf { !it.isNaN() } ?: 0.0, o.optInt("rev", 0), o.optString("label"), o.optString("by"), file, o.optBoolean("pinned", false))
        }.getOrNull()

        /**
         * One record per timer: a timer changed on two devices between syncs arrives as "x (2).json" beside "x.json",
         * both with its id; the higher rev is it, and on a tie stopping wins (the user: "stopping the one device stops all").
         */
        fun winners(all: List<TimerRecord>): List<TimerRecord> = all.groupBy { it.id }.values.map { same ->
            same.maxWith(compareBy<TimerRecord>({ it.rev }, { ORDER.indexOf(it.state) }, { -(it.file?.name?.length ?: 0) }))
        }
    }
}

internal object TimerFiles {
    const val FOLDER = "Timer"

    fun root(tetra: File) = File(tetra, TetraSystem.onDisk(FOLDER))

    fun read(dir: File): List<TimerRecord> = TimerRecord.winners(dir.listFiles().orEmpty()
        .filter { it.isFile && !it.name.startsWith(".") && it.name.endsWith(".json", true) }
        .mapNotNull { f -> runCatching { TimerRecord.parse(f.readText(), f) }.getOrNull() })

    /** The timers to show: running, paused, or ringing, the one ending first on top. */
    fun active(dir: File, now: Instant = Instant.now()): List<TimerRecord> =
        read(dir).filterNot { it.over(now) }.sortedBy { it.leftMs(now) }

    /** The cards on the screen: the active ones, then the pinned ones ready to start (no limit since 30 Oct). */
    fun cards(dir: File, now: Instant = Instant.now()): List<TimerRecord> =
        read(dir).filter { it.card(now) }.sortedWith(compareBy({ it.over(now) }, { it.leftMs(now) }, { it.seconds }))

    /**
     * Written beside, then renamed over: a sync or another device never reads half a timer. Each device writes only its
     * own file of a timer, `<id>.<device>.json` ([TimerRecord.by]), and the highest rev among them is the timer: two
     * devices never change the same file, so sync never has two versions of one to keep side by side (18 Oct: an OK and
     * a new start at once made "x (3) (3).json"… on every change).
     */
    fun write(dir: File, r: TimerRecord): TimerRecord = r.copy(file = save(dir, r.id, r.by, r.json()))

    /** [text] as `<id>.<by>.json`, beside then renamed; the alarms' and stopwatch's files too. */
    fun save(dir: File, id: String, by: String, text: String): File {
        dir.mkdirs()
        val to = File(dir, fileName(id, by))
        val part = File(dir, ".${to.name}.part")
        part.writeText(text)
        check(part.renameTo(to)) { "Could not save the timer." }
        to.setLastModified(System.currentTimeMillis()) // its time is now: the phone's storage keeps a renamed-over file's first date, and sync's hash cache goes by size and date
        return to
    }

    /**
     * A new timer, with the id of one that is over when there is one (its id carried on with a higher rev, which also tells
     * a device that missed the stop that the old one is over): the folder holds as many files per device as timers ever ran at once,
     * not one per timer ever started.
     */
    fun start(dir: File, seconds: Long, by: String, now: Instant = Instant.now(), label: String = ""): TimerRecord {
        val all = read(dir)
        val free = all.filter { it.free(now) }.minByOrNull { it.file?.name ?: "" }
        val r = TimerRecord(free?.id ?: newId(), seconds, TimerRecord.RUNNING, now.plusSeconds(seconds), 0.0, (free?.rev ?: 0) + 1, label, by)
        return write(dir, r)
    }

    private val COPY = Regex(""" \(\d+\)\.json$""", RegexOption.IGNORE_CASE)

    /**
     * The waste (the user, 21 Oct: "the timer app creates a lot of duplicat files what is nessesary and what is waste"): sync's
     * "x (2).json" copies, left from before each device wrote only its own file (18 Oct). Every timer's copies go; when the
     * files that stay would not hold the timer as it is (its newest state, pin and all, was in a copy, or an older paused one
     * would come back), this device first writes it into its own file. Sync never deletes, so the computer does the same
     * (desktop/timer.js clean): a copy one side still has would otherwise come back. Answers how many went.
     */
    fun clean(dir: File, by: String): Int {
        val all = dir.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") && it.name.endsWith(".json", true) }
            .mapNotNull { f -> runCatching { TimerRecord.parse(f.readText(), f) }.getOrNull() }
        var removed = 0
        for (group in all.groupBy { it.id }.values) {
            val w = TimerRecord.winners(group).single()
            val copies = group.filter { COPY.containsMatchIn(it.file!!.name) }
            if (copies.isEmpty()) continue
            val rest = TimerRecord.winners(group - copies.toSet()).singleOrNull()
            if (rest == null || rest.rev < w.rev || rest.state != w.state || rest.pinned != w.pinned || rest.ends != w.ends || rest.left != w.left)
                write(dir, w.copy(by = by))
            copies.forEach { if (it.file!!.delete()) removed++ }
        }
        return removed
    }

    /** "k3f9a2.24091RPADG.json"; a record with no device (one written by hand) keeps "<id>.json". */
    fun fileName(id: String, by: String) = by.replace(Regex("[^A-Za-z0-9_-]"), "").let { if (it.isEmpty()) "$id.json" else "$id.$it.json" }

    fun newId(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(6)
}

/** "5 min", "1 h 30 min", "45 s": a duration in words. */
internal fun durationText(w: UiWords, seconds: Long): String {
    val h = seconds / 3600; val m = seconds % 3600 / 60; val s = seconds % 60
    return listOfNotNull(h.takeIf { it > 0 }?.let { w.get(R.string.timer_hours, it) }, m.takeIf { it > 0 }?.let { w.get(R.string.timer_minutes, it) },
        s.takeIf { it > 0 || seconds == 0L }?.let { w.get(R.string.timer_seconds, it) }).joinToString(" ")
}

/** "4:05", "1:02:00": the time left on the clock face, rounded up so 0:00 is the end. */
internal fun clockText(ms: Long): String {
    val t = (ms + 999) / 1000
    val h = t / 3600; val m = t % 3600 / 60; val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
