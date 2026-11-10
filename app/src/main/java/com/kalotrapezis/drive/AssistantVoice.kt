package com.kalotrapezis.drive

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The Mistral API key (Settings › Assistant), encrypted by a key that never leaves the phone's keystore. */
internal object AssistantKey {
    private const val ALIAS = "tetra_assistant_key"
    private fun prefs(c: Context) = c.getSharedPreferences("assistant", Context.MODE_PRIVATE)

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getKey(ALIAS, null) as? SecretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    /** Whether a key is set, without reading it. */
    fun has(c: Context) = prefs(c).contains("key")
    fun get(c: Context): String? = prefs(c).getString("key", null)?.let { saved -> runCatching {
        val b = Base64.decode(saved, Base64.NO_WRAP)
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, b, 0, 12)) }.doFinal(b, 12, b.size - 12).decodeToString()
    }.onFailure { WriterLog.e("Assistant: the key could not be read", it) }.getOrNull() }

    fun set(c: Context, key: String) {
        if (key.isBlank()) { prefs(c).edit().remove("key").apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret()) }
        prefs(c).edit().putString("key", Base64.encodeToString(cipher.iv + cipher.doFinal(key.trim().toByteArray()), Base64.NO_WRAP)).apply()
    }
}

/** Mistral over HTTPS; every failure a [DriveProblem] the screen says in the user's language. */
internal object AssistantNet {
    /** The key for a request, only while the AI is on (AiGate, read again here: the switch may have come by sync). */
    fun key(c: Context): String { AiGate.load(c); AiGate.check(); return AssistantKey.get(c) ?: throw DriveProblem("No Mistral key.", R.string.assistant_no_key) }

    fun post(key: String, path: String, body: String): String =
        send(key, path, "application/json", body.toByteArray()).decodeToString()

    fun download(key: String, fileId: String): ByteArray = send(key, "/files/$fileId/content", null, null)

    /** Speech to text with Voxtral, in Greek, spelling [terms] as given (My words). */
    fun transcribe(key: String, pcm: ShortArray, terms: List<String>, bill: Bill? = null): String {
        val b = "tetra" + System.nanoTime()
        val body = ByteArrayOutputStream().apply {
            fun field(name: String, value: String) = write("--$b\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            field("model", Mistral.TRANSCRIBE); field("language", "el")
            terms.forEach { field("context_bias", it) }
            write("--$b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
            write(Vad.wav(pcm)); write("\r\n--$b--\r\n".toByteArray())
        }.toByteArray()
        return JSONObject(send(key, "/audio/transcriptions", "multipart/form-data; boundary=$b", body).decodeToString()).optString("text").trim()
            .also { bill?.minutes(Mistral.TRANSCRIBE, pcm.size.toDouble() / Vad.RATE) }
    }

    /** A failed live connection in the user's words, as [send] says a failed request. */
    fun problem(code: Int?): DriveProblem = when (code) {
        null -> DriveProblem("Offline", R.string.assistant_offline)
        401 -> DriveProblem("Wrong key.", R.string.assistant_bad_key)
        429 -> DriveProblem("Too many requests.", R.string.assistant_busy)
        else -> DriveProblem("Mistral $code", R.string.assistant_server, code)
    }

    /** A server-sent stream, a line at a time as it comes (the chat's answer, Mistral's voice). */
    fun stream(key: String, path: String, body: String, line: (String) -> Unit) =
        request(key, path, "application/json", body.toByteArray()) { it.bufferedReader().forEachLine(line) }

    /** Mistral's preset voices as id to "Name (languages)", every page (the TTS app's mistralVoices()). */
    fun voices(key: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var offset = 0
        while (true) {
            val items = JSONObject(request(key, "/audio/voices?limit=100&offset=$offset", null, null) { it.readBytes().decodeToString() }).optJSONArray("items") ?: return out
            for (i in 0 until items.length()) items.getJSONObject(i).let { v ->
                val langs = v.optJSONArray("languages")?.let { l -> (0 until l.length()).joinToString(", ") { l.getString(it) } }.orEmpty()
                out += v.getString("id") to (v.optString("name").ifBlank { v.getString("id") } + if (langs.isEmpty()) "" else " ($langs)")
            }
            if (items.length() < 100) return out
            offset += 100
        }
    }

    /**
     * [text] read by Mistral's [voice], streamed: [audio] gets 24 kHz mono float samples as they are made, so playing starts
     * before the sentence is whole. Mistral has no Greek voice, but its English ones read Greek well (the TTS app).
     */
    fun speech(key: String, voice: String, text: String, bill: Bill?, audio: (FloatArray) -> Unit) {
        val body = JSONObject().put("model", Mistral.TTS).put("input", text).put("voice_id", voice).put("response_format", "pcm").put("stream", true)
        stream(key, "/audio/speech", body.toString()) { line ->
            val data = line.removePrefix("data:").trim().takeIf { line.startsWith("data:") && it.startsWith("{") } ?: return@stream
            val b64 = JSONObject(data).optString("audio_data").ifEmpty { return@stream }
            val bytes = java.nio.ByteBuffer.wrap(Base64.decode(b64, Base64.DEFAULT)).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            audio(FloatArray(bytes.remaining()).also { bytes.get(it) })
        }
        bill?.chars(text)
    }

    private fun send(key: String, path: String, type: String?, body: ByteArray?): ByteArray = request(key, path, type, body) { it.readBytes() }

    private fun <T> request(key: String, path: String, type: String?, body: ByteArray?, read: (java.io.InputStream) -> T): T {
        AiGate.check() // nothing leaves for the AI while it is off, whoever asks
        val c = URL(Mistral.API + path).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000; c.readTimeout = 120_000 // a picture takes a while
            c.setRequestProperty("Authorization", "Bearer $key")
            if (body != null) { c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", type); c.outputStream.use { it.write(body) } }
            val code = try { c.responseCode } catch (e: IOException) { throw DriveProblem("Offline: ${e.message}", R.string.assistant_offline) }
            if (code in 200..299) return c.inputStream.use(read)
            val said = runCatching { c.errorStream?.use { it.readBytes().decodeToString() } }.getOrNull().orEmpty().take(300)
            WriterLog.w("Assistant: Mistral $path answered $code $said")
            throw problem(code)
        } catch (e: IOException) {
            throw DriveProblem("Offline: ${e.message}", R.string.assistant_offline)
        } finally { c.disconnect() }
    }
}

/** A conversation with Tetra's tools on this phone's files: the key read at each request, so a new one counts at once. */
internal fun assistantChat(c: Context, forgetMs: Long = Long.MAX_VALUE): AssistantChat {
    val app = c.applicationContext
    val tetra = driveRoot()
    val api = Mistral({ path, body -> AssistantNet.post(AssistantNet.key(app), path, body) }, AssistantMeter.bill(app),
        stream = { path, body, line -> AssistantNet.stream(AssistantNet.key(app), path, body, line) })
    val prefs = app.getSharedPreferences("assistant", Context.MODE_PRIVATE)
    val tools = AssistantTools(tetra, (android.os.Build.MODEL ?: "Android"),
        changed = { what -> when (what) {
            "contacts" -> { ScopedSync.soon(app, "contacts", 0, orWhole = true); ContactsAccount.soon(app) }
            "schedules", "memory" -> ScopedSync.soon(app, "assistant", 0, orWhole = true) // the computer runs the schedules: it hears at once
        } },
        search = { q ->
            val (text, sources, _) = Mistral.output(api.tagged("web").conversation(q, "web_search", "Search the web and answer briefly with the facts found."))
            text to sources.map { it.split(" — ").let { p -> ResultCard.Source(p.first(), p.last()) } }.filter { it.url.startsWith("http") }
        },
        image = { prompt ->
            val id = Mistral.output(api.tagged("image").conversation(prompt, "image_generation", "Draw the picture asked for.")).third.firstOrNull() ?: error("No picture came back.")
            File(File(tetra, FOLDER).apply { mkdirs() }, "image-${System.currentTimeMillis()}.png").apply { writeBytes(AssistantNet.download(AssistantNet.key(app), id)) }
        },
        // shortcut: which card is the user's is kept on this phone only (Contacts has no "me" mark yet); move it into the card when the computer needs it
        me = { prefs.getString("me", null) }, setMe = { prefs.edit().putString("me", it).apply() },
        extract = Attach.reader(app),
        texts = { if (SmsBox.canRead(app)) SmsBox.read(app) else null },
        stations = { BrowserRules.bookmarks(app.getSharedPreferences("browser", Context.MODE_PRIVATE).getString("bookmarks", null)) },
        mapsStation = { BrowserMedia.station(app) },
        volume = { change, percent -> AssistantVolume.change(app, change, percent) },
        send = { c -> SyncClient(app, SyncStore(app)).assistDo(c) },
        hotspotDevice = if (PhoneRelay.noCalls(app)) thisDevice() else null, appContext = app)
    return AssistantChat(api, tools, forgetMs)
}

/** "My words" (Settings › AI Assist): what Voxtral should spell right, sent with every recording as its context_bias. */
/**
 * Where the assistant talks and listens. The user's computer holds a Bluetooth link to the phone (Tetra's call relay), so
 * Android sent the answers to the PC: when the only Bluetooth sound device is Tetra's own computer, the phone's speaker
 * and mic (a wired headset's first); headphones, earbuds and the car still win. Null: Android's own choice. Calls keep theirs.
 */
internal object AssistantAudio {
    private val BLUETOOTH = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID)
    private val WIRED = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET)

    fun speaker(c: Context) = device(c, AudioManager.GET_DEVICES_OUTPUTS, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    fun mic(c: Context) = device(c, AudioManager.GET_DEVICES_INPUTS, AudioDeviceInfo.TYPE_BUILTIN_MIC)

    private fun device(c: Context, which: Int, builtIn: Int): AudioDeviceInfo? = runCatching {
        val all = c.getSystemService(AudioManager::class.java).getDevices(which)
        val bluetooth = all.filter { it.type in BLUETOOTH }.ifEmpty { return null }
        val computers = SyncStore(c).peers().map { it.name.trim().lowercase() }.toSet()
        if (bluetooth.any { it.productName?.toString()?.trim()?.lowercase() !in computers }) return null
        all.firstOrNull { it.type in WIRED } ?: all.firstOrNull { it.type == builtIn }
    }.getOrNull()
}

internal fun isDefaultAssistant(c: Context) = c.getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)

/** Android's page where the user picks the digital assistant (no app may ask for the role itself), the most direct one first. */
internal fun openAssistantChoice(c: Context) = listOf(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS,
    android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, android.provider.Settings.ACTION_SETTINGS)
    .firstOrNull { runCatching { c.startActivity(android.content.Intent(it).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }

/** What to tap there; HyperOS also keeps "hold the power button" for Google in its own setting. */
internal fun defaultAssistantHint(c: Context) = c.getString(R.string.assistant_default_hint) +
    if (android.os.Build.MANUFACTURER.equals("xiaomi", true)) " " + c.getString(R.string.assistant_default_xiaomi) else ""

/** YouTube's app on a search (the user: no browser inside Tetra), else its page in the browser. */
internal fun openYouTube(c: Context, query: String) {
    val app = android.content.Intent(android.content.Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", query)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { c.startActivity(app) }.isSuccess) return
    c.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/results?search_query=" + android.net.Uri.encode(query)))
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** How long a pause ends what the user says (Settings › AI Assist; the user: "I can't even pause"). */
internal object AssistantPause {
    const val DEFAULT_MS = 2_500
    private fun prefs(c: Context) = c.getSharedPreferences("assistant", Context.MODE_PRIVATE)
    fun ms(c: Context) = prefs(c).getInt("pauseMs", DEFAULT_MS)
    fun set(c: Context, ms: Int) = prefs(c).edit().putInt("pauseMs", ms).apply()
}

internal object AssistantWords {
    const val DEFAULT = "Καλοτραπέζης, Θεολόγος, Tetra, ΥΠΕΧΩΔΕ"
    private fun prefs(c: Context) = c.getSharedPreferences("assistant", Context.MODE_PRIVATE)
    /** Kept in Tetra, beside the memory, so every device has them (8 Nov: "so I do not type them a hundred times"). */
    fun file(): java.io.File = java.io.File(TetraSystem.dir(driveRoot(), "Assistant", "Shared"), "words.txt")
    /** The shared words; the first time, what this phone had kept on its own goes there. */
    fun words(c: Context): String {
        val f = file()
        runCatching { if (f.isFile) return f.readText().trim() }
        val mine = prefs(c).getString("words", null)
        if (mine != null && mine != DEFAULT) runCatching { f.parentFile?.mkdirs(); f.writeText(mine) }
        return mine ?: DEFAULT
    }
    fun setWords(c: Context, words: String) {
        prefs(c).edit().putString("words", words).apply()
        runCatching { file().apply { parentFile?.mkdirs() }.writeText(words) }.onFailure { WriterLog.w("AI Assist: the words were not kept in Tetra", it) }
        ScopedSync.soon(c, "assistant", delayMs = 2_500, orWhole = true)
    }
    /** Off unless switched on: the favourites' names then go to Mistral too. */
    fun contacts(c: Context) = prefs(c).getBoolean("contactWords", false)
    fun setContacts(c: Context, on: Boolean) = prefs(c).edit().putBoolean("contactWords", on).apply()

    fun terms(c: Context): List<String> = Mistral.terms(words(c), if (!contacts(c)) emptyList() else
        runCatching { ContactsFiles.all(contactsRoot()).map { it.info }.filter { it.favourite }.map { it.name }.take(50) }.getOrDefault(emptyList()))
}

/**
 * Live (the waveform button): the microphone streamed to Voxtral realtime over a WebSocket that carries the key itself,
 * so no server of Tetra's own; Voxtral says when a turn ends ([turn]), and the screen hands it to the same chat and tools
 * as walkie-talkie. While [muted] (thinking, reading the answer aloud) silence is sent instead, so the phone does not
 * hear itself and the stream stays open. Mistral's voice-agent SDK needs a LiveKit server, so it is not used.
 */
internal class AssistantLive(
    private val key: String,
    private val terms: List<String>,
    private val heard: (String) -> Unit,
    private val turn: (String) -> Unit,
    private val level: (Float) -> Unit,
    private val failed: (Throwable) -> Unit,
    private val pauseMs: Int = AssistantPause.DEFAULT_MS,
    private val mic: AudioDeviceInfo? = null,
) {
    @Volatile var muted = false
        set(on) { field = on; if (!on) synchronized(text) { text.clear() } }
    @Volatile private var open = true
    private var socket: okhttp3.WebSocket? = null
    private val text = StringBuilder()

    fun start() {
        AiGate.check() // the live voice too
        val request = okhttp3.Request.Builder().url("wss://api.mistral.ai/v1/audio/transcriptions/realtime?model=${Mistral.REALTIME}")
            .header("Authorization", "Bearer $key").build()
        socket = okhttp3.OkHttpClient().newWebSocket(request, object : okhttp3.WebSocketListener() {
            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) = runCatching { event(webSocket, JSONObject(text)) }
                .onFailure { WriterLog.w("AI Assist live: ${it.message}") }.let { }
            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                WriterLog.w("AI Assist live: closed (${response?.code}) ${t.message}")
                if (open) { open = false; failed(AssistantNet.problem(response?.code)) }
            }
        })
    }

    private fun event(ws: okhttp3.WebSocket, e: JSONObject) {
        when (e.optString("type")) {
            "session.created" -> {
                ws.send(JSONObject().put("type", "session.update").put("session", JSONObject()
                    .put("audio_format", JSONObject().put("encoding", "pcm_s16le").put("sample_rate", Vad.RATE))
                    .put("language", "el").put("context_bias", org.json.JSONArray(terms)).put("target_streaming_delay_ms", 480)
                    .put("turn_detection", JSONObject().put("min_silence_ms", pauseMs).put("end_timeout_ms", maxOf(3_000, pauseMs + 1_500)))).toString())
                Thread(::record, "ai-assist-live").start()
            }
            "transcription.text.delta" -> if (!muted) heard(synchronized(text) { text.append(e.optString("text")).toString() })
            "turn.end" -> if (!muted) synchronized(text) { text.toString().trim().also { text.clear() } }.takeIf { it.isNotEmpty() }?.let(turn)
            "error" -> { WriterLog.w("AI Assist live: $e"); if (open) { end(); failed(IllegalStateException(e.optJSONObject("error")?.optString("message") ?: "Mistral")) } }
        }
    }

    @SuppressLint("MissingPermission")
    private fun record() {
        val size = maxOf(AudioRecord.getMinBufferSize(Vad.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), Vad.FRAME * 2 * 8)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Vad.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        mic?.let(rec::setPreferredDevice)
        val chunk = ShortArray(Vad.FRAME * 4) // 120 ms
        val bytes = java.nio.ByteBuffer.allocate(chunk.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        try {
            rec.startRecording()
            while (open) {
                var n = 0
                while (n < chunk.size) { val r = rec.read(chunk, n, chunk.size - n); if (r <= 0) return; n += r }
                level((Vad.rms(chunk) / 3000).coerceIn(0.0, 1.0).toFloat())
                if (muted) chunk.fill(0)
                bytes.clear(); chunk.forEach { bytes.putShort(it) }
                socket?.send(JSONObject().put("type", "input_audio.append").put("audio", Base64.encodeToString(bytes.array(), Base64.NO_WRAP)).toString())
            }
        } catch (e: Exception) {
            WriterLog.e("AI Assist live: the microphone stopped", e)
        } finally { runCatching { rec.stop() }; rec.release() }
    }

    fun end() {
        if (!open) return
        open = false
        socket?.send("""{"type":"input_audio.end"}""")
        socket?.close(1000, null)
    }
}

/** Pictures the assistant made: Tetra/Assistant, a folder of Files like any other. */
private const val FOLDER = "Assistant"

internal fun hasMic(c: Context) = c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/**
 * Records until the speaker pauses, [stop] says so, or nobody spoke for [waitMs]; null when nothing was said.
 * [speaking] follows whether a phrase has begun, for the screen. A pause of [pauseMs] ends it; a minute at most.
 */
@SuppressLint("MissingPermission")
internal fun listen(waitMs: Long, stop: () -> Boolean, speaking: (Boolean) -> Unit = {}, pauseMs: Int = AssistantPause.DEFAULT_MS, mic: AudioDeviceInfo? = null): ShortArray? {
    val size = maxOf(AudioRecord.getMinBufferSize(Vad.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), Vad.FRAME * 2 * 8)
    val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Vad.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
    mic?.let(rec::setPreferredDevice)
    val vad = Vad(pauseMs / 30, 60 * 1000 / 30)
    val started = System.currentTimeMillis()
    try {
        rec.startRecording()
        while (true) {
            val frame = ShortArray(Vad.FRAME)
            var n = 0
            while (n < frame.size) { val r = rec.read(frame, n, frame.size - n); if (r <= 0) return null; n += r }
            val done = vad.push(frame)
            speaking(vad.speaking)
            if (done || stop()) break
            if (!vad.speaking && System.currentTimeMillis() - started > waitMs) return null
        }
    } finally { runCatching { rec.stop() }; rec.release() }
    return vad.phrase().takeIf { it.isNotEmpty() }
}

/*
 * Tetra as the phone's digital assistant (Settings › Apps › Default apps › Digital assistant app): holding the power
 * button, a swipe up from a bottom corner, or holding Home shows a session, which opens the assistant's sheet over
 * whatever is on the screen, listening at once. Android asks an assistant for a speech recognizer too: Voxtral's.
 */
class AssistantService : VoiceInteractionService()

class AssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = object : VoiceInteractionSession(this) {
        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            runCatching { startAssistantActivity(Intent(context, AssistantSheetActivity::class.java)) }.onFailure { WriterLog.e("Assistant: the sheet did not open", it) }
            hide() // the sheet is its own window; the session's would sit over it
        }
    }
}

/** Speech to text for other apps that ask Android's recognizer, while Tetra is the assistant: one phrase, by Voxtral. */
class AssistantRecognition : RecognitionService() {
    @Volatile private var stop = false

    override fun onStartListening(intent: Intent?, cb: Callback) {
        val key = AssistantKey.get(this) ?: return cb.error(SpeechRecognizer.ERROR_CLIENT)
        if (!hasMic(this)) return cb.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        stop = false
        Thread {
            runCatching {
                cb.readyForSpeech(Bundle())
                val pcm = listen(8_000, { stop }, pauseMs = AssistantPause.ms(this), mic = AssistantAudio.mic(this)) ?: return@Thread cb.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                cb.endOfSpeech()
                cb.results(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(AssistantNet.transcribe(key, pcm, AssistantWords.terms(this@AssistantRecognition), AssistantMeter.bill(this@AssistantRecognition).part("dictation")))) })
            }.onFailure { WriterLog.w("Assistant: recognition for another app failed", it); runCatching { cb.error(SpeechRecognizer.ERROR_NETWORK) } }
        }.start()
    }

    override fun onStopListening(cb: Callback) { stop = true }
    override fun onCancel(cb: Callback) { stop = true }
}

/** Settings › AI Assist's money counter, kept apart from the chats so clearing one loses nothing; prefs survive updates. */
internal object AssistantMeter {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("assistant_meter", Context.MODE_PRIVATE)
    @Synchronized fun read(c: Context): Meter = Meter.of(prefs(c).getString("meter", null), System.currentTimeMillis())
    @Synchronized private fun change(c: Context, how: (Meter) -> Unit) { val m = read(c).apply(how); prefs(c).edit().putString("meter", m.json()).apply() }
    @Synchronized fun add(c: Context, usd: Double) { if (usd > 0) change(c) { it.add(usd) } }
    @Synchronized fun reset(c: Context) = change(c) { it.reset(System.currentTimeMillis()) }
    /** A bill whose every cost is also counted here. */
    fun bill(c: Context): Bill { val app = c.applicationContext; return Bill({ add(app, it) }, { AssistantUsage.record(app, it) }) }
}

/** The voice that reads answers (Settings › AI Assist › Voice): one of Mistral's, or the phone's own ("", free). */
internal object AssistantVoices {
    const val PHONE = ""
    private fun prefs(c: Context) = c.getSharedPreferences("assistant", Context.MODE_PRIVATE)
    fun id(c: Context): String? = prefs(c).getString("voice", null)
    fun name(c: Context): String? = prefs(c).getString("voiceName", null)
    fun set(c: Context, id: String, name: String) = prefs(c).edit().putString("voice", id).putString("voiceName", name).apply()

    /** The chosen voice; never chosen, Mistral's first (the user wanted Mistral's voice), kept from then on. */
    fun chosen(c: Context, key: String): String = id(c) ?: (AssistantNet.voices(key).firstOrNull()?.also { set(c, it.first, it.second) }?.first ?: PHONE)
}

/**
 * One answer read aloud a sentence at a time as it comes ([say], then [finish]): by Mistral's voice, each sentence made
 * while the one before plays, or by the phone's own. A sentence Mistral cannot read (offline, the key) is read by the
 * phone, in its turn.
 */
internal class AnswerVoice(private val c: Context, private val reader: ReadAloud, private val bill: Bill, scope: CoroutineScope) {
    private val sentences = Channel<String>(Channel.UNLIMITED)
    private val audio = Channel<Any>(Channel.UNLIMITED) // samples to play, or a sentence for the phone's voice
    @Volatile private var track: AudioTrack? = null

    private val making = scope.launch(Dispatchers.IO) {
        val key = AssistantKey.get(c)
        var voice = key?.let { k -> runCatching { AssistantVoices.chosen(c, k) }.getOrDefault(AssistantVoices.PHONE) } ?: AssistantVoices.PHONE
        for (s in sentences) {
            if (voice == AssistantVoices.PHONE || key == null) { audio.trySend(s); continue }
            try {
                AssistantNet.speech(key, voice, s, bill) { a -> if (audio.trySend(a).isFailure) throw CancellationException("stopped") }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { WriterLog.w("AI Assist voice: ${e.message}"); voice = AssistantVoices.PHONE; audio.trySend(s) }
        }
        audio.close()
    }

    @Volatile private var player: MediaPlayer? = null

    private val playing = scope.launch(Dispatchers.IO) {
        val speaker = AssistantAudio.speaker(c)
        var written = 0L
        suspend fun drain() { while (isActive && (track?.playbackHeadPosition ?: return) < written) delay(30) }
        try {
            for (a in audio) when (a) {
                is FloatArray -> {
                    val t = track ?: newTrack().also { track = it; speaker?.let(it::setPreferredDevice); it.play() }
                    var at = 0
                    while (at < a.size && isActive) { val n = t.write(a, at, minOf(2_400, a.size - at), AudioTrack.WRITE_BLOCKING); if (n <= 0) break; at += n }
                    written += a.size
                }
                is String -> { drain(); if (speaker == null || !onPhone(a, speaker)) { reader.add(a); while (isActive && reader.speaking) delay(50) } }
            }
            drain()
        } finally { track?.run { runCatching { pause(); flush() }; release() }; track = null }
    }

    /** The phone's voice on [speaker]: Android's text-to-speech plays only where Android routes it, so made into a file first. */
    private suspend fun onPhone(sentence: String, speaker: AudioDeviceInfo): Boolean {
        val f = File(c.cacheDir, "ai-assist-voice.wav")
        val made = CompletableDeferred<Boolean>()
        if (!reader.toFile(sentence, f) { made.complete(it) } || !made.await()) return false
        val done = CompletableDeferred<Unit>()
        val p = MediaPlayer().also { player = it }
        try {
            p.setAudioAttributes(ATTRIBUTES); p.setDataSource(f.path); p.setPreferredDevice(speaker)
            p.setOnCompletionListener { done.complete(Unit) }; p.setOnErrorListener { _, _, _ -> done.complete(Unit); true }
            p.prepare(); p.start(); done.await()
        } finally { player = null; runCatching { p.release() } }
        return true
    }

    private fun newTrack() = AudioTrack.Builder()
        .setAudioAttributes(ATTRIBUTES)
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT), RATE * 4 / 2))
        .setTransferMode(AudioTrack.MODE_STREAM).build()

    fun say(sentence: String) { if (sentence.isNotBlank()) sentences.trySend(sentence) }
    fun finish() { sentences.close() }
    /** Until the last word is heard. */
    suspend fun await() = playing.join()
    fun stop() {
        sentences.close(); audio.cancel(); making.cancel(); playing.cancel()
        track?.runCatching { pause(); flush() } // a blocked write returns
        player?.runCatching { stop() }
        reader.stop()
    }

    private companion object {
        const val RATE = 24_000 // Mistral's voice: 24 kHz float samples
        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    }
}

/** AI Assist's «δυνάμωσε», «χαμήλωσε», «ένταση στο 30» (the user, 4 Nov): the media volume, shown as Android shows a key press. */
internal object AssistantVolume {
    fun change(c: Context, change: String, percent: Int?): Int {
        val am = c.getSystemService(AudioManager::class.java); val s = AudioManager.STREAM_MUSIC; val max = am.getStreamMaxVolume(s)
        val ui = AudioManager.FLAG_SHOW_UI
        when (change.lowercase()) {
            "down" -> repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, ui) }
            "mute" -> am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, ui)
            "unmute" -> am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, ui)
            "set" -> am.setStreamVolume(s, ((percent ?: 50).coerceIn(0, 100) * max + 50) / 100, ui)
            else -> repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, ui) }
        }
        return if (am.isStreamMute(s)) 0 else am.getStreamVolume(s) * 100 / max
    }
}
