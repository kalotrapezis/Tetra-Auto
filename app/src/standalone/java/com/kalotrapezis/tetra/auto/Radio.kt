package com.kalotrapezis.tetra.auto

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONArray
import org.json.JSONObject

internal data class Station(val name: String, val url: String)

/**
 * Media's radio (the user, 13 Nov: "plays its own sound through the car"): live streams played here by ExoPlayer, out of the
 * unit's own speakers, no Bluetooth. Audio focus is ExoPlayer's: it ducks under the directions and stops for another app.
 * The stations: ERT's to start with, and each one the phone sends (an EVENT play) is kept.
 */
internal object Radio {
    private val DEFAULTS = listOf(
        Station("ΕΡΤ Πρώτο", "https://radiostreaming.ert.gr/ert-proto"),
        Station("ΕΡΤ Δεύτερο", "https://radiostreaming.ert.gr/ert-deftero"),
        Station("ΕΡΤ Τρίτο", "https://radiostreaming.ert.gr/ert-trito"),
        Station("ΕΡΤ Κόσμος", "https://radiostreaming.ert.gr/ert-kosmos"),
    )
    val stations = mutableStateListOf<Station>()
    val now = mutableStateOf<Station?>(null)
    val playing = mutableStateOf(false)
    /** What the stream says it plays now (ICY title), when it says. */
    val title = mutableStateOf("")
    private var player: ExoPlayer? = null
    private lateinit var app: Context

    fun init(c: Context) {
        app = c.applicationContext
        if (stations.isEmpty()) stations += runCatching {
            JSONArray(prefs().getString("stations", null)!!).let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> Station(o.getString("name"), o.getString("url")) } } }
        }.getOrDefault(DEFAULTS)
    }
    private fun prefs() = app.getSharedPreferences("car", Context.MODE_PRIVATE)
    private fun save() = prefs().edit().putString("stations", JSONArray(stations.map { JSONObject().put("name", it.name).put("url", it.url) }).toString()).apply()

    private fun player(): ExoPlayer = player ?: ExoPlayer.Builder(app).build().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        setHandleAudioBecomingNoisy(true)
        addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing.value = isPlaying }
            override fun onMediaMetadataChanged(m: MediaMetadata) { title.value = m.title?.toString().orEmpty() }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { android.util.Log.w(TAG, "Radio: ${error.message}"); playing.value = false }
        })
    }.also { player = it }

    fun play(s: Station) {
        if (stations.none { it.url == s.url }) { stations += s; save() }
        now.value = s; title.value = ""
        player().run { setMediaItem(MediaItem.fromUri(s.url)); prepare(); play() }
        android.util.Log.i(TAG, "Radio: ${s.name}")
    }
    fun toggle() { val p = player ?: return now.value?.let(::play) ?: Unit; if (p.isPlaying) p.pause() else { p.seekToDefaultPosition(); p.play() } } // a live stream: back to now
    fun remove(s: Station) { stations.remove(s); save() }
    fun release() { player?.release(); player = null; playing.value = false }
}

/**
 * The unit's own voice (Android's TTS) for directions and messages read aloud: as navigation guidance, so the radio ducks under
 * it (ExoPlayer lowers itself for a transient "may duck" focus).
 */
internal object Speech {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val queued = mutableListOf<String>()
    private lateinit var audio: android.media.AudioManager
    private val attrs = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()

    fun init(c: Context) {
        if (tts != null) return
        audio = c.getSystemService(android.media.AudioManager::class.java)
        tts = TextToSpeech(c.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.setAudioAttributes(attrs)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) { if (tts?.isSpeaking != true) audio.abandonAudioFocusRequest(focus) }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) { audio.abandonAudioFocusRequest(focus) }
                })
                synchronized(queued) { queued.forEach(::say); queued.clear() }
            }
        }
    }

    fun say(text: String) {
        val t = tts ?: return
        if (!ready) { synchronized(queued) { queued += text }; return }
        audio.requestAudioFocus(focus)
        t.speak(text, TextToSpeech.QUEUE_ADD, null, "car${System.nanoTime()}")
    }

    fun release() { tts?.shutdown(); tts = null; ready = false }
}
