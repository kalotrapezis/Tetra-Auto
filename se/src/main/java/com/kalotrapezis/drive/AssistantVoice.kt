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

/** Each service's API key (Settings › Assistant; Mistral's the first), encrypted by a key that never leaves the phone's keystore. */
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

    private fun name(p: AiProvider) = if (p.isMistral) "key" else "key_${p.id}" // Mistral's where it always was

    /** Whether the chosen chat service has a key (AI Assist can answer), without reading it. */
    fun has(c: Context) = has(c, AiProviders.of(c, AiPart.CHAT))
    fun has(c: Context, p: AiProvider) = prefs(c).contains(name(p))
    fun get(c: Context, p: AiProvider = AiProviders.MISTRAL): String? = prefs(c).getString(name(p), null)?.let { saved -> runCatching {
        val b = Base64.decode(saved, Base64.NO_WRAP)
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, b, 0, 12)) }.doFinal(b, 12, b.size - 12).decodeToString()
    }.onFailure { WriterLog.e("Assistant: the key could not be read", it) }.getOrNull() }

    fun set(c: Context, key: String, p: AiProvider = AiProviders.MISTRAL) {
        if (key.isBlank()) { prefs(c).edit().remove(name(p)).apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret()) }
        prefs(c).edit().putString(name(p), Base64.encodeToString(cipher.iv + cipher.doFinal(key.trim().toByteArray()), Base64.NO_WRAP)).apply()
    }
}

/** Mistral, or the service chosen for a part (AiProviders), over HTTPS; every failure a [DriveProblem] the screen says in the user's language. */
internal object AssistantNet {
    /** [p]'s key for a request, only while the AI is on (AiGate, read again here: the switch may have come by sync). */
    fun key(c: Context, p: AiProvider = AiProviders.MISTRAL): String { AiGate.load(c); AiGate.check(); return AssistantKey.get(c, p)
        ?: throw if (p.isMistral) DriveProblem("No Mistral key.", R.string.assistant_no_key) else DriveProblem("No ${p.name} key.", R.string.ai_no_key, p.name) }

    /** The chat of the service chosen to think, its key read at each request so a new one counts at once. */
    fun api(c: Context, bill: Bill = AssistantMeter.bill(c)): Mistral {
        val app = c.applicationContext; val p = AiProviders.of(app, AiPart.CHAT)
        return Mistral({ path, body -> post(key(app, p), path, body, p) }, bill, { path, body, line -> stream(key(app, p), path, body, p, line) }, p)
    }

    fun post(key: String, path: String, body: String, p: AiProvider = AiProviders.MISTRAL): String =
        send(key, path, "application/json", body.toByteArray(), p).decodeToString()

    fun download(key: String, fileId: String): ByteArray = send(key, "/files/$fileId/content", null, null)

    /** Speech to text by the service chosen to listen (Voxtral unless chosen), in Greek, spelling [terms] as given (My words). */
    fun transcribe(c: Context, pcm: ShortArray, terms: List<String>, bill: Bill? = null): String {
        val p = AiProviders.of(c, AiPart.LISTEN); val key = key(c, p)
        // AAC, a tenth of the WAV (car.log, 17 Nov: 10 s of speech took 40 s to go up the car's weak uplink); WAV where the service refused it
        val prefs = c.getSharedPreferences("assistant", Context.MODE_PRIVATE); val refused = "aac_refused_${p.id}"
        val aac = if (prefs.getBoolean(refused, false)) null else m4a(pcm, c.cacheDir)
        if (aac != null) try { return transcribe(p, key, aac, "speech.m4a", "audio/mp4", terms).also { bill?.minutes(p.listen!!, pcm.size.toDouble() / Vad.RATE) } }
        catch (e: DriveProblem) {
            // shortcut: a refused file is told by the status in the message ("Mistral 400"); a code on DriveProblem would be cleaner if this grows
            if (e.message?.contains(Regex(" (400|415|422)$")) != true) throw e
            WriterLog.w("AI Assist: ${p.name} refused AAC (${e.message}), WAV from now on"); prefs.edit().putBoolean(refused, true).apply()
        }
        return transcribe(p, key, Vad.wav(pcm), "speech.wav", "audio/wav", terms).also { bill?.minutes(p.listen!!, pcm.size.toDouble() / Vad.RATE) }
    }

    private fun transcribe(p: AiProvider, key: String, audio: ByteArray, name: String, type: String, terms: List<String>): String {
        val scribe = p == AiProviders.ELEVENLABS // its /speech-to-text names the fields its own way
        val b = "tetra" + System.nanoTime()
        val body = ByteArrayOutputStream().apply {
            fun field(name: String, value: String) = write("--$b\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            field(if (scribe) "model_id" else "model", p.listen!!); field(if (scribe) "language_code" else "language", "el")
            when {
                p.isMistral -> terms.forEach { field("context_bias", it) }
                scribe -> terms.filter { it.length <= 50 && it.split(' ').size <= 5 && it.none { ch -> ch in "<>{}[]\\" } }.forEach { field("keyterms", it) } // its limits on a term
                terms.isNotEmpty() -> field("prompt", terms.joinToString(", ")) // Whisper's way to the spellings
            }
            write("--$b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: $type\r\n\r\n".toByteArray())
            write(audio); write("\r\n--$b--\r\n".toByteArray())
        }.toByteArray()
        return JSONObject(send(key, if (scribe) "/speech-to-text" else "/audio/transcriptions", "multipart/form-data; boundary=$b", body, p).decodeToString()).optString("text").trim()
    }

    /** A failed live connection in the user's words, as [send] says a failed request. */
    fun problem(code: Int?, p: AiProvider = AiProviders.MISTRAL): DriveProblem = when (code) {
        null -> DriveProblem("Offline", R.string.assistant_offline)
        401 -> if (p.isMistral) DriveProblem("Wrong key.", R.string.assistant_bad_key) else DriveProblem("Wrong ${p.name} key.", R.string.ai_bad_key, p.name)
        429 -> if (p.isMistral) DriveProblem("Too many requests.", R.string.assistant_busy) else DriveProblem("${p.name}: too many requests.", R.string.ai_busy, p.name)
        else -> if (p.isMistral) DriveProblem("Mistral $code", R.string.assistant_server, code) else DriveProblem("${p.name} $code", R.string.ai_server, p.name, code)
    }

    /** A server-sent stream, a line at a time as it comes (the chat's answer, Mistral's voice). */
    fun stream(key: String, path: String, body: String, p: AiProvider = AiProviders.MISTRAL, line: (String) -> Unit) =
        request(key, path, "application/json", body.toByteArray(), p) { it.bufferedReader().forEachLine(line) }

    /** OpenAI's voices for /audio/speech, its best two first (its text-to-speech guide, Nov 2026); it has no list to ask. */
    private val OPENAI_VOICES = listOf("marin", "cedar", "alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer", "verse")

    /** The voices of the service chosen to speak as id to name: Mistral's presets "Name (languages)", every page (the TTS app's mistralVoices()). */
    fun voices(c: Context): List<Pair<String, String>> {
        val p = AiProviders.of(c, AiPart.VOICE); val key = key(c, p)
        if (p == AiProviders.OPENAI) return OPENAI_VOICES.map { it to it.replaceFirstChar(Char::uppercase) }
        if (p == AiProviders.ELEVENLABS) return JSONObject(request(key, "/voices", null, null, p) { it.readBytes().decodeToString() }).getJSONArray("voices").let { a ->
            (0 until a.length()).map { a.getJSONObject(it).let { v -> v.getString("voice_id") to v.optString("name").ifBlank { v.getString("voice_id") } } } }
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
     * [text] read by [voice] of the service chosen to speak, streamed: [audio] gets 24 kHz mono float samples as they are made,
     * so playing starts before the sentence is whole. Mistral has no Greek voice, but its English ones read Greek well (the TTS
     * app); OpenAI's and ElevenLabs' come as 16-bit samples at the same rate.
     */
    fun speech(c: Context, voice: String, text: String, bill: Bill?, audio: (FloatArray) -> Unit) {
        val p = AiProviders.of(c, AiPart.VOICE); val key = key(c, p)
        if (!p.isMistral) {
            val (path, body) = if (p == AiProviders.ELEVENLABS) "/text-to-speech/$voice/stream?output_format=pcm_24000" to JSONObject().put("text", text).put("model_id", p.voice)
                else "/audio/speech" to JSONObject().put("model", p.voice).put("input", text).put("voice", voice).put("response_format", "pcm")
            return request(key, path, "application/json", body.toString().toByteArray(), p) { input ->
                val buf = ByteArray(9_600); var kept = 0
                while (true) {
                    val n = input.read(buf, kept, buf.size - kept).takeIf { it >= 0 }?.plus(kept) ?: break
                    pcm16(buf, n).takeIf { it.isNotEmpty() }?.let(audio)
                    kept = n % 2; if (kept == 1) buf[0] = buf[n - 1] // half a sample waits for its other byte
                }
            } // shortcut: another service's voice is not priced in Bill, so it counts as nothing
        }
        val body = JSONObject().put("model", Mistral.TTS).put("input", text).put("voice_id", voice).put("response_format", "pcm").put("stream", true)
        stream(key, "/audio/speech", body.toString()) { line ->
            val data = line.removePrefix("data:").trim().takeIf { line.startsWith("data:") && it.startsWith("{") } ?: return@stream
            val b64 = JSONObject(data).optString("audio_data").ifEmpty { return@stream }
            val bytes = java.nio.ByteBuffer.wrap(Base64.decode(b64, Base64.DEFAULT)).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            audio(FloatArray(bytes.remaining()).also { bytes.get(it) })
        }
        bill?.chars(text)
    }

    /** The whole 16-bit little-endian samples among [bytes]' first [n] as floats (an odd last byte left out). */
    fun pcm16(bytes: ByteArray, n: Int) = FloatArray(n / 2) { i -> ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort() / 32768f }

    private fun send(key: String, path: String, type: String?, body: ByteArray?, p: AiProvider = AiProviders.MISTRAL): ByteArray = request(key, path, type, body, p) { it.readBytes() }

    /** No answer came at all (the connection dropped before it): safe to ask again, nothing was read yet. */
    private class NoAnswer(e: IOException) : IOException(e)
    private const val RETRIES = 3
    private const val RETRY_MS = 2_000L

    /** Again when the connection drops before an answer: three more tries, two seconds apart (the user, 14 Nov: "3 tries of 2 sec"). */
    private fun <T> request(key: String, path: String, type: String?, body: ByteArray?, p: AiProvider = AiProviders.MISTRAL, read: (java.io.InputStream) -> T): T {
        var tries = 0
        while (true) {
            try { return attempt(key, path, type, body, p, read) } catch (e: NoAnswer) {
                if (tries++ >= RETRIES) throw DriveProblem("Offline: ${e.cause?.message}", R.string.assistant_offline)
                WriterLog.w("Assistant: ${p.name} $path did not go (${e.cause?.message}), again in 2 s")
                Thread.sleep(RETRY_MS)
            }
        }
    }

    private fun <T> attempt(key: String, path: String, type: String?, body: ByteArray?, p: AiProvider, read: (java.io.InputStream) -> T): T {
        AiGate.check() // nothing leaves for the AI while it is off, whoever asks
        val c = URL(p.api + path).openConnection() as HttpURLConnection
        val t0 = System.currentTimeMillis(); var sent = t0; var answered = t0
        try {
            c.connectTimeout = 15_000; c.readTimeout = 120_000 // a picture takes a while
            if (p == AiProviders.ELEVENLABS) c.setRequestProperty("xi-api-key", key) else c.setRequestProperty("Authorization", "Bearer $key")
            val code = try {
                if (body != null) { c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", type); c.setFixedLengthStreamingMode(body.size); c.outputStream.use { it.write(body) } }
                sent = System.currentTimeMillis()
                c.responseCode.also { answered = System.currentTimeMillis() }
            } catch (e: IOException) { throw NoAnswer(e) }
            // each request's size and time, to tell a slow uplink from a slow service (car.log, 17 Nov: 40 s for one phrase)
            if (code in 200..299) return c.inputStream.use(read).also {
                WriterLog.i("Assistant: ${p.name} $path ${(body?.size ?: 0) / 1024} KB sent in ${sent - t0} ms, answer began ${answered - sent} ms later, done ${System.currentTimeMillis() - answered} ms after")
            }
            val said = runCatching { c.errorStream?.use { it.readBytes().decodeToString() } }.getOrNull().orEmpty().take(300)
            WriterLog.w("Assistant: ${p.name} $path answered $code $said")
            throw problem(code, p)
        } catch (e: NoAnswer) { throw e } catch (e: IOException) {
            throw DriveProblem("Offline: ${e.message}", R.string.assistant_offline)
        } finally { c.disconnect() }
    }
}

/** A conversation with Tetra's tools on this phone's files: the key read at each request, so a new one counts at once. */
internal fun assistantChat(c: Context, forgetMs: Long = Long.MAX_VALUE): AssistantChat {
    val app = c.applicationContext
    val tetra = driveRoot()
    val api = AssistantNet.api(app)
    // web search and pictures are Mistral's own tools, whichever service thinks
    val mistral = Mistral({ path, body -> AssistantNet.post(AssistantNet.key(app), path, body) }, api.bill)
    val prefs = app.getSharedPreferences("assistant", Context.MODE_PRIVATE)
    val tools = AssistantTools(tetra, TimerControl.device(),
        changed = { what -> when (what) {
            "calendar" -> { ScopedSync.soon(app, "calendar", 0, orWhole = true); CalendarAlarms.planSoon(app) }
            "timer" -> { ScopedSync.soon(app, "timer", 0, orWhole = true); TimerService.check(app) }
            "pills" -> { ScopedSync.soon(app, "pills", 0, orWhole = true); PillsAlarms.planSoon(app); android.os.Handler(android.os.Looper.getMainLooper()).post { PillsAlarms.changed.intValue++ } }
            "notes" -> NotesSync.soon(app)
            "places" -> LocationReminders.planSoon(app)
            "contacts" -> { ScopedSync.soon(app, "contacts", 0, orWhole = true); ContactsAccount.soon(app) }
            "mail" -> ScopedSync.soon(app, "mail", 0, orWhole = true) // a rename or an action for the computer: it hears at once
            "schedules", "memory" -> ScopedSync.soon(app, "assistant", 0, orWhole = true) // the computer runs the schedules: it hears at once
        } },
        search = { q ->
            val (text, sources, _) = Mistral.output(// the search is a request of its own: it gets today too (the user, 14 Nov: "it does not seem to know date and time")
            mistral.tagged("web").conversation(q, "web_search", "Today is ${java.time.ZonedDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", java.util.Locale.ENGLISH))}. Search the web and answer briefly with the facts found, each with its date; say plainly what has already passed and what is still to come."))
            text to sources.map { it.split(" — ").let { p -> ResultCard.Source(p.first(), p.last()) } }.filter { it.url.startsWith("http") }
        },
        image = { prompt ->
            val id = Mistral.output(mistral.tagged("image").conversation(prompt, "image_generation", "Draw the picture asked for.")).third.firstOrNull() ?: error("No picture came back.")
            File(File(tetra, FOLDER).apply { mkdirs() }, "image-${System.currentTimeMillis()}.png").apply { writeBytes(AssistantNet.download(AssistantNet.key(app), id)) }
        },
        // shortcut: which card is the user's is kept on this phone only (Contacts has no "me" mark yet); move it into the card when the computer needs it
        me = { prefs.getString("me", null) }, setMe = { prefs.edit().putString("me", it).apply() },
        photos = { place, at -> AssistantPhotos.at(app, place, at) },
        placesOn = { LocationReminders.ready(app) }, extract = Attach.reader(app),
        texts = { if (SmsBox.canRead(app)) SmsBox.read(app) else null },
        here = { runCatching { app.getSystemService(android.location.LocationManager::class.java).let { lm ->
            lm.allProviders.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }?.let { it.latitude to it.longitude } } }.getOrNull() },
        muteMusic = { on -> if (!BrowserMedia.mute(on)) AssistantVolume.change(app, if (on) "mute" else "unmute", null) }, // no Browser open: the whole media volume
        mediaKey = { key -> if (!BrowserMedia.key(key)) app.getSystemService(android.media.AudioManager::class.java).let { am ->
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, key)); am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, key)) } },
        quiet = { on, until -> AssistantQuiet.set(app, on, until) },
        photosBetween = { from, to -> android.os.Environment.getExternalStorageDirectory().let { shared ->
            listDeviceMedia(app).filter { !it.isVideo && it.relativePath != null && it.takenMillis in from..to }.sortedByDescending { it.takenMillis }
                .map { File(File(shared, it.relativePath!!), it.name) }.filter { it.isFile } } },
        calls = { if (PhoneRelay.noCalls(app) || app.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) != android.content.pm.PackageManager.PERMISSION_GRANTED) null else PhoneLog.read(app, 400) },
        stations = { BrowserRules.bookmarks(app.getSharedPreferences("browser", Context.MODE_PRIVATE).getString("bookmarks", null)) },
        mapsStation = { BrowserMedia.station(app) },
        volume = { change, percent -> AssistantVolume.change(app, change, percent) },
        send = { c -> SyncClient(app, SyncStore(app)).assistDo(c) },
        hotspotDevice = if (PhoneRelay.noCalls(app)) DataFiles.device() else null, appContext = app)
    return AssistantChat(api, tools, forgetMs)
}

/**
 * Do Not Disturb by voice («μην με ενοχλείς μέχρι τις 9»): Android's own, as the user set it up; off by itself at the time
 * asked ([QuietOff], an alarm). Without the access, Android's page for it opens.
 */
internal object AssistantQuiet {
    private fun off(c: Context) = android.app.PendingIntent.getBroadcast(c, 7341, Intent(c, QuietOff::class.java),
        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)

    fun set(c: Context, on: Boolean, until: Long?): String {
        val nm = c.getSystemService(android.app.NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) { CarQuiet.ask(c); return "Tetra may not change Do Not Disturb yet: Android's page for it is open, where the user allows Tetra; then ask again. Say so." }
        nm.setInterruptionFilter(if (on) android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY else android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
        val am = c.getSystemService(android.app.AlarmManager::class.java)
        am.cancel(off(c))
        if (on && until != null) am.set(android.app.AlarmManager.RTC_WAKEUP, until, off(c))
        WriterLog.i("AI Assist: Do Not Disturb ${if (on) "on" + (until?.let { " until ${java.time.Instant.ofEpochMilli(it)}" } ?: "") else "off"}")
        return if (!on) "Do Not Disturb is off." else "Do Not Disturb is on" + (until?.let { " until " + java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withSecond(0) } ?: "") + "."
    }
}

/** The time asked for has come: Do Not Disturb off again. */
class QuietOff : android.content.BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        runCatching { c.getSystemService(android.app.NotificationManager::class.java).takeIf { it.isNotificationPolicyAccessGranted }
            ?.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL) }
        WriterLog.i("AI Assist: Do Not Disturb off, its time came")
    }
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

/** The library's photos from a place, newest first: by the town each photo keeps (Photos' map), else within 15 km of [at]. */
internal object AssistantPhotos {
    // shortcut: only photos Photos has already read the place of (its map or the background scan); unscanned ones are missed
    fun at(c: Context, place: String, at: () -> Pair<Double, Double>?): List<File> {
        val photos = listDeviceMedia(c).filter { !it.isVideo && it.relativePath != null }
        val store = PhotoMetadataStore(c)
        val where = try { photos.map { it.photoKey }.chunked(500).flatMap { store.locations(it).entries }.associate { it.key to it.value } } finally { store.close() } // SQLite's limit on ? per query
        val q = ContactsFiles.plain(place)
        var hits = photos.filter { e -> where[e.photoKey]?.placeName?.let { ContactsFiles.plain(it).contains(q) } == true }
        if (hits.isEmpty()) at()?.let { (lat, lng) -> hits = photos.filter { e -> where[e.photoKey]?.let { km(it.latitude, it.longitude, lat, lng) < 15 } == true } }
        val shared = android.os.Environment.getExternalStorageDirectory()
        return hits.sortedByDescending { it.takenMillis }.map { File(File(shared, it.relativePath!!), it.name) }.filter { it.isFile }
    }
    private fun km(a: Double, b: Double, c: Double, d: Double): Double {
        val x = Math.toRadians(d - b) * Math.cos(Math.toRadians((a + c) / 2)); val y = Math.toRadians(c - a)
        return Math.sqrt(x * x + y * y) * 6371
    }
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

/** Why the last [listen] gave nothing, as words to say (null: it heard sound but nobody spoke). */
@Volatile internal var micTrouble: Int? = null
/** The source that last gave sound: the next [listen] starts with it. */
@Volatile private var goodSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
private val SOURCES = mapOf(MediaRecorder.AudioSource.VOICE_RECOGNITION to "voice recognition", MediaRecorder.AudioSource.MIC to "mic",
    MediaRecorder.AudioSource.VOICE_COMMUNICATION to "voice communication")

/**
 * Records until the speaker pauses, [stop] says so, or nobody spoke for [waitMs]; null when nothing was said, [micTrouble] why.
 * [speaking] follows whether a phrase has begun, for the screen. A pause of [pauseMs] ends it; a minute at most (the car: 15 s, a lower bar).
 * A source that cannot be opened, fails, or gives only zeros (another app holds the mic, or the unit routes it elsewhere) gives way
 * to the next. Each try is one line in the log: the source, where Android routed it, the noise, the loudest and the bar speech must pass.
 */
@SuppressLint("MissingPermission")
internal fun listen(waitMs: Long, stop: () -> Boolean, speaking: (Boolean) -> Unit = {}, pauseMs: Int = AssistantPause.DEFAULT_MS, mic: AudioDeviceInfo? = null): ShortArray? {
    micTrouble = null
    val size = maxOf(AudioRecord.getMinBufferSize(Vad.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), Vad.FRAME * 2 * 8)
    // a car's microphone is far and its road noise loud (the user, 14 Nov: 4-5 tries speaking loudly, nothing heard): a lower bar over the noise
    val car = CarMaps.edition
    for (source in (listOf(goodSource) + SOURCES.keys).distinct()) {
        val rec = runCatching { AudioRecord(source, Vad.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size) }.getOrNull()
        if (rec?.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release(); micTrouble = R.string.assistant_mic_closed
            WriterLog.w("AI Assist: the microphone (${SOURCES[source]}) did not open"); continue
        }
        mic?.let(rec::setPreferredDevice)
        // the car: a quiet cabin, its microphone up in the door, far from the driver (the user, 14 Nov: "don't have too high a threshold, if it
        // needs to be higher I will tell you"): a low bar, and all it heard kept for a voice still under it (sent to be heard anyway)
        val vad = if (car) Vad(pauseMs / 30, 15 * 1000 / 30, factor = 1.3, least = 60.0, byVoice = true) else Vad(pauseMs / 30, 60 * 1000 / 30)
        val all = if (car) ArrayList<ShortArray>() else null
        val started = System.currentTimeMillis()
        var frames = 0; var peak = 0
        var how = "nobody spoke"; var routed = ""
        try {
            rec.startRecording()
            while (true) {
                val frame = ShortArray(Vad.FRAME)
                var n = 0
                while (n < frame.size) { val r = rec.read(frame, n, frame.size - n); if (r <= 0) { how = "read $r"; micTrouble = R.string.assistant_mic_closed; break }; n += r }
                if (n < frame.size) break
                if (frames++ == 3) routed = rec.routedDevice?.let { "${it.productName} (type ${it.type})" } ?: "?"
                frame.forEach { val a = kotlin.math.abs(it.toInt()); if (a > peak) peak = a }
                all?.add(frame)
                // 0.6 s of exact zeros: nothing reaches this source (a real microphone always hisses a little)
                if (frames == 20 && peak == 0) { how = "only zeros"; micTrouble = R.string.assistant_mic_silent; break }
                val done = vad.push(frame)
                speaking(vad.speaking)
                if (done || stop()) { how = if (done) "heard" else "stopped"; goodSource = source; return vad.phrase().takeIf { it.isNotEmpty() }?.let { if (car) louder(it) else it } }
                if (!vad.speaking && System.currentTimeMillis() - started > waitMs) {
                    goodSource = source
                    // something above the road was there, under the bar: the whole of it goes to be heard; "nothing" comes back if it was nothing
                    if (all != null && vad.loudest > vad.noise * 1.2) { how = "quiet, sent anyway"; return louder(ShortArray(all.size * Vad.FRAME).also { o -> all.forEachIndexed { i, f -> f.copyInto(o, i * Vad.FRAME) } }) }
                    return null
                }
            }
        } finally {
            runCatching { rec.stop() }; rec.release()
            WriterLog.i("AI Assist: mic ${SOURCES[source]} on $routed, ${frames * 30} ms, noise ${vad.noise.toInt()}, loudest ${vad.loudest.toInt()}, bar ${vad.threshold().toInt()}, peak $peak: $how")
        }
    }
    return null
}

/**
 * [pcm] (16 kHz mono) as AAC in an .m4a, about a tenth of the WAV, by Android's own encoder; null when this phone or unit cannot
 * (WAV goes instead). The car's uplink is slow (car.log, 17 Nov: 10 s of speech, 330 KB of WAV, took 40 s to send).
 */
internal fun m4a(pcm: ShortArray, dir: File): ByteArray? = runCatching {
    val f = File.createTempFile("speech", ".m4a", dir)
    try {
        val fmt = android.media.MediaFormat.createAudioFormat(android.media.MediaFormat.MIMETYPE_AUDIO_AAC, Vad.RATE, 1).apply {
            setInteger(android.media.MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(android.media.MediaFormat.KEY_BIT_RATE, 32_000)
        }
        val codec = android.media.MediaCodec.createEncoderByType(android.media.MediaFormat.MIMETYPE_AUDIO_AAC)
        val mux = android.media.MediaMuxer(f.path, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        try {
            codec.configure(fmt, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
            val info = android.media.MediaCodec.BufferInfo()
            var pos = 0; var fed = false; var done = false
            val until = System.currentTimeMillis() + 15_000 // a codec that never ends: WAV instead
            while (!done) {
                check(System.currentTimeMillis() < until) { "the encoder took too long" }
                if (!fed) codec.dequeueInputBuffer(10_000).takeIf { it >= 0 }?.let { i ->
                    val buf = codec.getInputBuffer(i)!!.apply { clear(); order(java.nio.ByteOrder.LITTLE_ENDIAN) }
                    val n = minOf(buf.remaining() / 2, pcm.size - pos)
                    buf.asShortBuffer().put(pcm, pos, n)
                    val us = pos * 1_000_000L / Vad.RATE
                    pos += n; fed = pos >= pcm.size
                    codec.queueInputBuffer(i, 0, n * 2, us, if (fed) android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = mux.addTrack(codec.outputFormat); mux.start() }
                else if (o >= 0) {
                    val out = codec.getOutputBuffer(o)!!
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) {
                        out.position(info.offset); out.limit(info.offset + info.size); mux.writeSampleData(track, out, info)
                    }
                    codec.releaseOutputBuffer(o, false)
                    done = info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                }
            }
        } finally { runCatching { codec.stop() }; codec.release(); runCatching { if (track >= 0) mux.stop() }; mux.release() }
        check(track >= 0) { "no audio came out" }
        f.readBytes()
    } finally { f.delete() }
}.onFailure { WriterLog.w("AI Assist: no AAC here, WAV sent (${it.message})") }.getOrNull()

/** A far voice made louder for the listening service (peak to about 70%, at most 8x). */
internal fun louder(pcm: ShortArray): ShortArray {
    val peak = pcm.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: return pcm
    val gain = minOf(8.0, 23_000.0 / maxOf(peak, 1))
    return if (gain < 1.2) pcm else ShortArray(pcm.size) { (pcm[it] * gain).toInt().coerceIn(-32768, 32767).toShort() }
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
        if (!AssistantKey.has(this, AiProviders.of(this, AiPart.LISTEN))) return cb.error(SpeechRecognizer.ERROR_CLIENT)
        if (!hasMic(this)) return cb.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        stop = false
        Thread {
            runCatching {
                cb.readyForSpeech(Bundle())
                val pcm = listen(8_000, { stop }, pauseMs = AssistantPause.ms(this), mic = AssistantAudio.mic(this)) ?: return@Thread cb.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                cb.endOfSpeech()
                cb.results(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(AssistantNet.transcribe(this@AssistantRecognition, pcm, AssistantWords.terms(this@AssistantRecognition), AssistantMeter.bill(this@AssistantRecognition).part("dictation")))) })
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

/** The voice that reads answers (Settings › AI Assist › Voice): one of the speaking service's, or the phone's own ("", free); one kept for each service. */
internal object AssistantVoices {
    const val PHONE = ""
    private fun prefs(c: Context) = c.getSharedPreferences("assistant", Context.MODE_PRIVATE)
    private fun of(c: Context) = AiProviders.of(c, AiPart.VOICE).let { if (it.isMistral) "" else "_${it.id}" } // Mistral's where it always was
    fun id(c: Context): String? = prefs(c).getString("voice" + of(c), null)
    fun name(c: Context): String? = prefs(c).getString("voiceName" + of(c), null)
    fun set(c: Context, id: String, name: String) = prefs(c).edit().putString("voice" + of(c), id).putString("voiceName" + of(c), name).apply()

    /** The chosen voice; never chosen, the service's first (the user wanted Mistral's voice), kept from then on. */
    fun chosen(c: Context): String = id(c) ?: (AssistantNet.voices(c).firstOrNull()?.also { set(c, it.first, it.second) }?.first ?: PHONE)
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
    // samples made and not yet played: a sentence is made only while less than AHEAD waits, so a long reading (a whole file) is
    // paid as it is heard and a stop stops its cost
    private val waiting = java.util.concurrent.atomic.AtomicLong()

    private val making = scope.launch(Dispatchers.IO) {
        var voice = if (!AssistantKey.has(c, AiProviders.of(c, AiPart.VOICE))) AssistantVoices.PHONE else runCatching { AssistantVoices.chosen(c) }.getOrDefault(AssistantVoices.PHONE)
        for (s in sentences) {
            if (voice == AssistantVoices.PHONE) { audio.trySend(s); continue }
            while (isActive && waiting.get() > AHEAD) delay(200)
            try {
                AssistantNet.speech(c, voice, s, bill) { a -> waiting.addAndGet(a.size.toLong()); if (audio.trySend(a).isFailure) throw CancellationException("stopped") }
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
                    if (track == null) while (isActive && reader.speaking) delay(50) // "one moment", "searching for …" (the car) finish first
                    val t = track ?: newTrack().also { track = it; speaker?.let(it::setPreferredDevice); it.play() }
                    var at = 0
                    while (at < a.size && isActive) { val n = t.write(a, at, minOf(2_400, a.size - at), AudioTrack.WRITE_BLOCKING); if (n <= 0) break; at += n }
                    written += a.size; waiting.addAndGet(-a.size.toLong())
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
        const val AHEAD = 20L * RATE // seconds of voice made ahead of what is heard
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
