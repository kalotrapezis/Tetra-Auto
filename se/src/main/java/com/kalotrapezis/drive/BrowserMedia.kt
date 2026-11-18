package com.kalotrapezis.drive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat

/**
 * Background play (the user, 2 Nov: "can we make it play youtube videos on the background?"): while a page in the Browser
 * plays, this keeps Tetra running with a media notification — ‹ play/pause › — whose keys go to the page as Player mode's do.
 * Note: YouTube's terms keep background play for Premium; this is the user's own, for personal use.
 */
internal object BrowserMedia {
    const val ACTION = "action"
    /** The open page's keys ("toggle", "play", "pause", "next", "prev"); null when no Browser is open. */
    @Volatile var control: ((String) -> Unit)? = null
    @Volatile var title = ""
    @Volatile var playing = false
    @Volatile private var running = false
    @Volatile var service: BrowserMediaService? = null

    fun act(a: String) { val c = control ?: return; Handler(Looper.getMainLooper()).post { c(a) } }
    /** Something plays that is not Tetra's Browser (the car's radio, Spotify): only then is Android's focus asked to pause it. */
    fun othersPlaying(audio: android.media.AudioManager) = audio.isMusicActive && !(control != null && playing)

    /*
     * The music silent while a voice speaks (Maps, AI Assist) and while the user wants it muted: muted, not paused (a live station goes on).
     * Counted, so one voice ending does not bring it back under another, and a mute the user asked for stays through them.
     */
    private val silence = Silence(::act)
    fun duck(on: Boolean) = silence.duck(on)
    /** The user's mute or unmute («σίγαση»); false when no Browser is open to do it. */
    fun mute(on: Boolean): Boolean { if (control == null) return false; silence.mute(on); return true }

    /** A media key (KeyEvent.KEYCODE_MEDIA_…) to the Browser when it has something to play; false: it goes to Android, to whatever plays. */
    fun key(code: Int): Boolean {
        if (service == null || control == null) return false
        act(when (code) { android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause"; android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> "next"; android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "prev"; else -> return false })
        return true
    }

    /** Maps' ♪ (the user, 2 Nov: "pick one FM from the browser as the one in maps, one link just playing music"): a page's address and name. */
    fun station(c: Context): Pair<String, String>? = c.getSharedPreferences("browser", Context.MODE_PRIVATE).let { p ->
        p.getString("maps_station", null)?.takeIf { it.isNotEmpty() }?.let { it to p.getString("maps_station_title", "").orEmpty() } }
    fun setStation(c: Context, url: String, title: String) = c.getSharedPreferences("browser", Context.MODE_PRIVATE).edit()
        .putString("maps_station", url).putString("maps_station_title", title).apply()

    /** Maps' ♪: the station paused or played on; when nothing of it is open, opened behind Maps and played (BrowserActivity). */
    fun mapsMusic(c: Context): Boolean {
        if (service != null && control != null) { act(if (playing) "pause" else "play"); return true }
        val (url, _) = station(c) ?: return false
        openBrowser(c, "browser-play:" + org.json.JSONObject().put("url", url).put("behind", true).put("at", System.currentTimeMillis()))
        return true
    }

    // the car build: always on, no switch (the user, 15 Nov: off, YouTube stopped every play within a second, the page on the screen or not)
    fun background(c: Context) = CarMaps.edition || c.getSharedPreferences("browser", Context.MODE_PRIVATE).getBoolean("background", true)
    fun setBackground(c: Context, on: Boolean) = c.getSharedPreferences("browser", Context.MODE_PRIVATE).edit().putBoolean("background", on).apply()

    /** The notification shown or brought up to date (started while Tetra is in front: Android allows no new one from behind). */
    fun show(c: Context, title: String, playing: Boolean) {
        this.title = title; this.playing = playing
        if (!running && !playing) return
        // already running: brought up to date in place (from behind, Android refuses another start)
        service?.let { s -> Handler(Looper.getMainLooper()).post { s.refresh(false) }; return }
        runCatching { ContextCompat.startForegroundService(c, Intent(c, BrowserMediaService::class.java)); running = true }
            .onFailure { WriterLog.w("Browser: background play not started", it) }
    }

    fun stop(c: Context) { if (running) { running = false; c.stopService(Intent(c, BrowserMediaService::class.java)) } }
}

internal class BrowserMediaService : Service() {
    private lateinit var session: MediaSession

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.browser_media_channel), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        // the headset's and the lock screen's keys come here too
        session = MediaSession(this, "TetraBrowser").apply {
            setCallback(object : MediaSession.Callback() {
                // Android's own keys and the car's (logged: a head unit may send a pause of its own when its audio changes)
                override fun onPlay() { WriterLog.i("Browser: play from Android's media keys"); BrowserMedia.act("play") }
                override fun onPause() { WriterLog.i("Browser: pause from Android's media keys"); BrowserMedia.act("pause") }
                override fun onSkipToNext() = BrowserMedia.act("next")
                override fun onSkipToPrevious() = BrowserMedia.act("prev")
            })
            isActive = true
        }
        BrowserMedia.service = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(BrowserMedia.ACTION)?.let(BrowserMedia::act) // a key on the notification
        refresh(true)
        return START_NOT_STICKY
    }

    /** The session and the notification as BrowserMedia is now; [start] from onStartCommand, else only shown again. */
    fun refresh(start: Boolean) {
        val playing = BrowserMedia.playing
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, BrowserMedia.title).build())
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build())
        fun key(icon: Int, label: Int, action: String) = Notification.Action.Builder(Icon.createWithResource(this, icon), getString(label),
            PendingIntent.getService(this, action.hashCode(), Intent(this, BrowserMediaService::class.java).putExtra(BrowserMedia.ACTION, action), PendingIntent.FLAG_IMMUTABLE)).build()
        val open = PendingIntent.getActivity(this, 0, Intent(this, BrowserActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_play).setColor(BROWSER_COLOR.toArgb())
            .setContentTitle(BrowserMedia.title.ifEmpty { getString(R.string.app_browser) }).setContentText(getString(R.string.app_browser))
            .setContentIntent(open).setOngoing(playing).setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(key(R.drawable.ic_skip_previous, R.string.browser_previous, "prev"))
            .addAction(if (playing) key(R.drawable.ic_pause, R.string.common_pause, "pause") else key(R.drawable.ic_play, R.string.browser_play, "play"))
            .addAction(key(R.drawable.ic_skip_next, R.string.browser_next, "next"))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2)).build()
        if (!start) { getSystemService(NotificationManager::class.java).notify(ID, n); return }
        runCatching { startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }.onFailure { WriterLog.w("Browser: media notification", it); stopSelf() }
    }

    override fun onDestroy() { BrowserMedia.service = null; session.release(); super.onDestroy() }

    companion object {
        private const val CHANNEL = "browser_media"
        private const val ID = 7301
    }
}

/** The Browser's music silent while any voice speaks ([duck], counted) or while the user muted it; [act] told only when that changes. */
internal class Silence(private val act: (String) -> Unit) {
    private var ducks = 0
    private var muted = false
    @Synchronized fun duck(on: Boolean) = change { ducks = maxOf(0, ducks + if (on) 1 else -1) }
    @Synchronized fun mute(on: Boolean) = change { muted = on }
    private fun silent() = ducks > 0 || muted
    private fun change(how: () -> Unit) { val was = silent(); how(); if (silent() != was) act(if (silent()) "duck" else "unduck") }
}
