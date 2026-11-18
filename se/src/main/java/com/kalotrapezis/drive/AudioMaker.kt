package com.kalotrapezis.drive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/*
 * A document made into audio on the phone itself (the user, 1 Nov 2026: «M4A στο κινητό», instead of waiting for the
 * computer; the desktop's audio.js, ported): «Διάβασέ το μου» reads it with one voice, cleaned up only; «Ραδιοφωνική
 * εκπομπή» is a host and an expert talking it through, an example and a recap a section. Mistral writes the script as JSON
 * lines, a cheap second pass takes out what the source does not say, Voxtral reads each line (cached by its hash, so a
 * retry costs nothing), the lines are evened out and joined with pauses, and MediaCodec makes a 64 kbps AAC .m4a.
 */
internal object AudioMaker {
    const val RATE = 24000 // Voxtral's PCM
    private const val LINE_GAP = 0.35; private const val SECTION_GAP = 1.2 // seconds
    private const val WORDS_A_MINUTE = 150
    private const val PARALLEL = 3

    class Line(val speaker: String, val text: String, var section: String) { var pcm = FloatArray(0) }
    class Mixed(val pcm: FloatArray, val chapters: List<AudioJobs.Chapter>, val seconds: Double)

    /** Mistral's answer as script lines: JSON lines (or one JSON array), fences and stray text ignored. */
    fun parseScript(text: String, from: String = ""): List<Line> {
        val raw = text.replace(Regex("```(?:json)?"), "").trim()
        var items = if (raw.startsWith("[")) runCatching { JSONArray(raw).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } }.getOrDefault(emptyList()) else emptyList()
        if (items.isEmpty()) items = raw.lines().map { it.trim().removeSuffix(",") }.filter { it.startsWith("{") }.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        var section = from
        return items.mapNotNull { x ->
            val t = x.optString("text").replace(Regex("\\s+"), " ").trim().ifEmpty { return@mapNotNull null }
            x.optString("section").trim().takeIf { it.isNotEmpty() }?.let { section = it }
            Line(if (x.optString("speaker") == "expert") "expert" else "host", t, section)
        }
    }

    private const val SPOKEN = "Greek, spoken style, short sentences; no markdown, lists, symbols or links; numbers, dates and abbreviations written as they are said."
    private fun showPrompt(name: String, words: Int, first: Boolean, last: Boolean, focus: String, style: String) = """You write part of a Greek radio show about the document «$name», for a student who listens while driving.
Two voices: "host" (the presenter, asks and guides) and "expert" (explains from the document).
Output only JSON lines, one per spoken line: {"speaker":"host"|"expert","text":"…","section":"<this section's short title>"}
$SPOKEN
For each section of the source below: the host introduces it, the expert explains it, one concrete example, then a short recap by the host.
Use only what the source says. An example that is not in the source starts with «Δικό μας παράδειγμα:».
${if (first) "Start with the host welcoming the listener and saying what the document is about." else "Go straight on from the previous part: no welcome."}
${if (last) "End with a short recap of the whole and a goodbye." else "No goodbye: the show goes on after this part."}
About $words words in all: some ${maxOf(8, Math.round(words / 35.0).toInt())} lines of two or three sentences each. Do not stop short.""" +
        (if (focus.isNotBlank()) "\nFocus on: $focus." else "") + (if (style.isNotBlank()) "\nStyle: $style." else "")
    private const val NARRATE = """Prepare this text to be read aloud in Greek, word for word: do not summarise, shorten or explain.
Only clean up: drop page numbers, running headers and footers, reference lists and footnote marks; join lines broken by the page; write abbreviations, symbols and numbers as they are said.
Output only JSON lines, one per paragraph: {"speaker":"host","text":"…","section":"<the heading it falls under>"}"""
    private const val CHECK = """You check a radio script against its source. Give back {"unsupported":[numbers]}: the numbers of the lines that state a fact, figure, name or claim that the source does not support.
Not these: greetings, questions, transitions, recaps of what the source says, and lines starting «Δικό μας παράδειγμα»."""

    /** [n] at a time, in order. */
    private fun <T, R> pool(items: List<T>, n: Int, each: (T, Int) -> R): List<R> {
        val ex = Executors.newFixedThreadPool(maxOf(1, minOf(n, items.size)))
        try { return items.mapIndexed { i, x -> ex.submit<R> { each(x, i) } }.map { f -> runCatching { f.get() }.getOrElse { throw (it as? java.util.concurrent.ExecutionException)?.cause ?: it } } }
        finally { ex.shutdownNow() }
    }
    private fun msgs(system: String, user: String) = JSONArray().put(JSONObject().put("role", "system").put("content", system)).put(JSONObject().put("role", "user").put("content", user))

    /** The script: for a show, a few groups of the text (≈3 minutes each), checked against them; for a reading, every part. */
    fun script(api: Mistral, d: Attach.Doc, mode: String, minutes: Int, focus: String, style: String, progress: (String, Int, Int) -> Unit): Pair<List<Line>, Int> {
        val ps = Attach.parts(d)
        val groups = if (mode == "show") { val g = maxOf(1, minOf(10, Math.ceil(minutes / 3.0).toInt(), ps.size)); ps.chunked(Math.ceil(ps.size / g.toDouble()).toInt()).map { c -> c.joinToString("\n") { it.text } } }
            else ps.map { it.text }
        var done = 0
        val scripts = pool(groups, if (mode == "show") 2 else PARALLEL) { text, i ->
            val sys = if (mode == "show") showPrompt(d.name, Math.round(minutes * WORDS_A_MINUTE / groups.size.toDouble()).toInt(), i == 0, i == groups.size - 1, focus, style) else NARRATE
            var lines = parseScript(api.tagged("script").complete(msgs(sys, text), model = if (mode == "show") Mistral.LARGE else Mistral.CHAT))
            var dropped = 0
            if (mode == "show" && lines.isNotEmpty()) {
                val said = runCatching { api.tagged("verify").complete(msgs(CHECK, "Source:\n<<<\n$text\n>>>\nScript:\n" + lines.mapIndexed { n, l -> "${n + 1}. ${l.text}" }.joinToString("\n")), json = true) }.getOrDefault("{}")
                val drop = runCatching { JSONObject(said).optJSONArray("unsupported")?.let { a -> (0 until a.length()).map { a.optInt(it) } } }.getOrNull().orEmpty().filter { it > 1 }.toSet() // never the opening line
                dropped = drop.size
                lines = lines.filterIndexed { n, _ -> n + 1 !in drop }
            }
            synchronized(this) { progress("script", ++done, groups.size) }
            lines to dropped
        }
        // a section's title carries on from the part before when a part starts without one
        var section = d.name.substringBeforeLast('.')
        val lines = scripts.flatMap { it.first }.onEach { l -> section = l.section.ifEmpty { section }; l.section = section }
        return lines to scripts.sumOf { it.second }
    }

    /** A line cut into requests of at most [max] letters, at sentence ends. */
    fun pieces(text: String, max: Int = 700): List<String> {
        val out = mutableListOf<String>(); var cur = StringBuilder()
        for (s in Regex("[^.!;;…]+[.!;;…]*\\s*").findAll(text).map { it.value }.toList().ifEmpty { listOf(text) }) {
            if (cur.isNotEmpty() && cur.length + s.length > max) { out += cur.toString().trim(); cur = StringBuilder() }
            cur.append(s)
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out
    }

    /** One line read by [voice]: from the cache, else Voxtral through [say] (then kept). */
    fun speak(say: (voice: String, text: String, (FloatArray) -> Unit) -> Unit, voice: String, text: String, cache: File): FloatArray {
        val f = File(cache, MessageDigest.getInstance("SHA-256").digest("$voice\n$text".toByteArray()).joinToString("") { "%02x".format(it) }.take(32) + ".f32")
        if (f.isFile) return java.nio.ByteBuffer.wrap(f.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let { b -> FloatArray(b.remaining()).also { b.get(it) } }
        val chunks = mutableListOf<FloatArray>()
        for (p in pieces(text)) say(voice, p) { chunks += it }
        val all = join(chunks)
        cache.mkdirs()
        f.writeBytes(java.nio.ByteBuffer.allocate(all.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(all) }.array())
        return all
    }
    private fun join(list: List<FloatArray>) = FloatArray(list.sumOf { it.size }).also { out -> var at = 0; list.forEach { it.copyInto(out, at); at += it.size } }

    /** Each line brought to the same loudness (RMS), never past the top; pauses between lines and longer ones between sections. */
    fun mix(lines: List<Line>): Mixed {
        val target = 0.08
        val chapters = mutableListOf<AudioJobs.Chapter>(); val out = mutableListOf<FloatArray>()
        var at = 0.0; var section: String? = null
        lines.forEachIndexed { i, l ->
            if (l.section != section) {
                if (i > 0) { out += FloatArray(Math.round(SECTION_GAP * RATE).toInt()); at += SECTION_GAP }
                chapters += AudioJobs.Chapter(l.section, at); section = l.section
            } else if (i > 0) { out += FloatArray(Math.round(LINE_GAP * RATE).toInt()); at += LINE_GAP }
            val rms = Math.sqrt(l.pcm.sumOf { it.toDouble() * it } / maxOf(1, l.pcm.size))
            val gain = if (rms > 1e-4) minOf(4.0, target / rms) else 1.0
            out += FloatArray(l.pcm.size) { k -> (l.pcm[k] * gain).toFloat().coerceIn(-0.98f, 0.98f) }
            at += l.pcm.size.toDouble() / RATE
        }
        return Mixed(join(out), chapters, at)
    }

    /**
     * Float samples to AAC-LC mono at 24 kHz, about 64 kbps, in an .m4a.
     * shortcut: no title or chapter atoms in the file (MediaMuxer writes neither); the chapters are in the job's JSON and the card. Write a udta/chpl box when a player needs them.
     */
    fun m4a(pcm: FloatArray, out: File) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000); setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
        out.parentFile?.mkdirs()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1; var fed = 0; var inputDone = false; val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                if (!inputDone) codec.dequeueInputBuffer(10_000).takeIf { it >= 0 }?.let { i ->
                    val buf = codec.getInputBuffer(i)!!.apply { clear(); order(java.nio.ByteOrder.nativeOrder()) }
                    val n = minOf(buf.capacity() / 2, pcm.size - fed)
                    for (k in 0 until n) buf.putShort((pcm[fed + k] * 32767).toInt().toShort())
                    val time = fed * 1_000_000L / RATE
                    fed += n
                    if (fed >= pcm.size) { codec.queueInputBuffer(i, 0, n * 2, time, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true } else codec.queueInputBuffer(i, 0, n * 2, time, 0)
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start() }
                    o >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) muxer.writeSampleData(track, codec.getOutputBuffer(o)!!, info)
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally { runCatching { codec.stop() }; codec.release(); runCatching { if (track >= 0) muxer.stop() }; muxer.release() }
    }

    private fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]+"), " ").replace(Regex("\\s+"), " ").trim().take(90).ifEmpty { "Audio" }

    /** The whole job into [dir]: script, voices, mix, .m4a. [progress] gets (stage, done, total). Answers the file, title, chapters, length and how many lines were dropped. */
    fun make(api: Mistral, say: (String, String, (FloatArray) -> Unit) -> Unit, d: Attach.Doc, mode: String, minutes: Int, focus: String, style: String,
        voices: List<String>, dir: File, cache: File, progress: (String, Int, Int) -> Unit): JSONObject {
        val (lines, dropped) = script(api, d, mode, minutes, focus, style, progress)
        if (lines.isEmpty()) error("No script came back.")
        var done = 0
        pool(lines, PARALLEL) { l, _ ->
            l.pcm = speak(say, if (l.speaker == "expert") voices.getOrElse(1) { voices[0] } else voices[0], l.text, cache)
            synchronized(this) { progress("voice", ++done, lines.size) }
        }
        progress("mix", 0, 1)
        val title = d.name.substringBeforeLast('.').replace(Regex("_+"), " ") + " — " + if (mode == "show") "Εκπομπή" else "Ανάγνωση"
        val m = mix(lines)
        dir.mkdirs()
        File(dir, "script.json").writeText(JSONObject().put("mode", mode).put("minutes", minutes).put("dropped", dropped)
            .put("lines", JSONArray(lines.map { JSONObject().put("speaker", it.speaker).put("text", it.text).put("section", it.section) })).toString(1)) // what was said, to look at when it sounds wrong
        val file = File(dir, "${safe(title)}.m4a")
        m4a(m.pcm, file)
        progress("mix", 1, 1)
        return JSONObject().put("file", file.name).put("title", title).put("seconds", m.seconds).put("dropped", dropped)
            .put("chapters", JSONArray(m.chapters.map { JSONObject().put("title", it.title).put("start", it.start) }))
    }
}

/** The audio being made, in the foreground (it survives leaving the app), a notification showing how far; one job at a time, in turn. */
class AudioService : Service() {
    private val work = Executors.newSingleThreadExecutor()
    private var running = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(AudioJobs.EXTRA) ?: return START_NOT_STICKY
        startForeground(NOTE, note(getString(R.string.assistant_attach_stage_script), 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        synchronized(this) { running++ }
        work.execute {
            runCatching { AudioJobs.run(applicationContext, id) { stage, done, total ->
                getSystemService(NotificationManager::class.java)?.notify(NOTE, note(getString(stageText(stage)), done, total))
            } }.onFailure { WriterLog.e("AI Assist: the audio $id failed", it) }
            synchronized(this) { if (--running == 0) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } }
        }
        return START_NOT_STICKY
    }

    private fun stageText(stage: String) = when (stage) { "voice" -> R.string.assistant_attach_stage_voice; "mix" -> R.string.assistant_attach_stage_mix; else -> R.string.assistant_attach_stage_script }

    private fun note(text: String, done: Int, total: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm?.getNotificationChannel(CHANNEL) == null) nm?.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.assistant_attach_show), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(this, NOTE, Intent(this, AssistantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_auto_fix).setContentTitle(getString(R.string.app_assistant)).setContentText(text)
            .setProgress(maxOf(total, 1), done, total == 0).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).build()
    }

    override fun onDestroy() { work.shutdown(); super.onDestroy() }

    companion object {
        private const val CHANNEL = "assistant_audio_nobadge" // progress is no count on the icon (4 Nov)
        private const val NOTE = 7360
        fun start(c: Context, id: String) = ContextCompat.startForegroundService(c, Intent(c, AudioService::class.java).putExtra(AudioJobs.EXTRA, id))
    }
}
