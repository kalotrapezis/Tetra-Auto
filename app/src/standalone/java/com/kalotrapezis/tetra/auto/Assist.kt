package com.kalotrapezis.tetra.auto

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AI Assist on the car with no key here (the plan, agreed 13 Nov): the unit's mic hears the words (16 kHz, until a second of
 * quiet or 10 s, or the mic tapped again), the phone's AI Assist — its keys, its services, its tools — works them out and
 * answers ({text, go?, play?}); the answer is shown and said here, a place goes to the map.
 */
internal object Assist {
    enum class Phase { Idle, Listening, Thinking }
    val phase = mutableStateOf(Phase.Idle)
    val answer = mutableStateOf<String?>(null)
    @Volatile private var stop = false

    fun listen(c: Context) {
        when (phase.value) {
            Phase.Listening -> { stop = true; return }
            Phase.Thinking -> return
            Phase.Idle -> Unit
        }
        if (c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { answer.value = c.getString(R.string.se_assist_no_mic); return }
        if (PhoneLink.link?.connected != true) { answer.value = c.getString(R.string.se_assist_no_phone); return }
        stop = false; answer.value = null; phase.value = Phase.Listening
        Thread({ record(c) }, "tetra-car-mic").start()
    }

    @SuppressLint("MissingPermission") // checked in listen
    private fun record(c: Context) {
        val rate = 16_000
        val size = maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), rate / 5)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        val pcm = ByteArrayOutputStream()
        val buf = ShortArray(rate / 10) // 100 ms
        var heard = false; var quiet = 0
        runCatching {
            rec.startRecording()
            while (!stop && pcm.size() < rate * 2 * 10) {
                val n = rec.read(buf, 0, buf.size); if (n <= 0) break
                val bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN).also { b -> for (i in 0 until n) b.putShort(buf[i]) }
                pcm.write(bytes.array())
                val loud = (0 until n).maxOf { kotlin.math.abs(buf[it].toInt()) } > 1500
                if (loud) { heard = true; quiet = 0 } else if (heard && ++quiet >= 10) break // a second of quiet after speech
            }
        }.onFailure { android.util.Log.w(TAG, "Mic: ${it.message}") }
        runCatching { rec.stop() }; rec.release()
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        if (!heard) { main.post { phase.value = Phase.Idle }; return }
        main.post {
            phase.value = Phase.Thinking
            PhoneLink.ask(JSONObject().put("assist", JSONObject().put("wav", Base64.encodeToString(wav(pcm.toByteArray(), rate), Base64.NO_WRAP)))) { a ->
                phase.value = Phase.Idle
                val text = a.optString("text").ifBlank { a.optString("error").ifBlank { null }?.let { c.getString(R.string.se_assist_failed, it) } }
                answer.value = text
                a.optJSONObject("go")?.let { g -> PhoneLink.event(g.put("type", "go")) }
                a.optJSONObject("play")?.let { p -> PhoneLink.event(p.put("type", "play")) }
                if (!a.has("error") && !text.isNullOrBlank()) Speech.say(text)
            }
        }
    }

    /** 16-bit mono PCM as a .wav (what every speech-to-text service takes). */
    fun wav(pcm: ByteArray, rate: Int): ByteArray = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(pcm.size); put(pcm)
    }.array()
}
