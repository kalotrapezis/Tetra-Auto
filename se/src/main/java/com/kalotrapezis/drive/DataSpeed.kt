package com.kalotrapezis.drive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.TrafficStats
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * Data's live speed (the user, 25 Oct: "ideally yes"): ↓ and ↑ as the status bar's icon, read once a second from
 * TrafficStats, and only while the screen is on — in a pocket it does nothing. Its own low notification, because the
 * listening one is "min" and Android shows no status-bar icon for those (the plan had hoped to share it).
 */
internal object DataSpeed {
    private const val PREF = "liveSpeed"
    fun enabled(c: Context) = c.getSharedPreferences("data", Context.MODE_PRIVATE).getBoolean(PREF, false)
    fun setEnabled(c: Context, on: Boolean) {
        c.getSharedPreferences("data", Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply()
        if (on) start(c) else c.stopService(Intent(c, DataSpeedService::class.java))
    }
    fun start(c: Context) {
        if (!enabled(c)) return
        runCatching { ContextCompat.startForegroundService(c, Intent(c, DataSpeedService::class.java)) }.onFailure { WriterLog.w("Data: live speed not started (${it.message})") }
    }

    /** Bytes a second as the icon says it: "850K", "1.2M", "12M". */
    fun short(perSecond: Long): String = when {
        perSecond < 1000 -> "${perSecond}B"
        perSecond < 1_000_000 -> "${perSecond / 1000}K"
        perSecond < 10_000_000 -> "%.1fM".format(java.util.Locale.ROOT, perSecond / 1e6)
        else -> "${perSecond / 1_000_000}M"
    }
}

internal class DataSpeedService : Service() {
    companion object {
        private const val ID = 0xDA5
        private const val CHANNEL = "data_speed"
        private const val ACTION_STOP = "com.kalotrapezis.drive.SPEED_STOP"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastRx = 0L; private var lastTx = 0L; private var lastAt = 0L
    private val tick = object : Runnable {
        override fun run() { update(); handler.postDelayed(this, 1000) }
    }
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            handler.removeCallbacks(tick)
            if (i.action == Intent.ACTION_SCREEN_ON) { lastAt = 0; handler.post(tick) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(this, screen, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { DataSpeed.setEnabled(this, false); stopSelf(); return START_NOT_STICKY }
        if (runCatching { startForeground(ID, notification(0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }.isFailure) { stopSelf(); return START_NOT_STICKY }
        handler.removeCallbacks(tick)
        if (getSystemService(PowerManager::class.java).isInteractive) handler.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        runCatching { unregisterReceiver(screen) }
        super.onDestroy()
    }

    private fun update() {
        val rx = TrafficStats.getTotalRxBytes(); val tx = TrafficStats.getTotalTxBytes(); val now = System.nanoTime()
        if (lastAt != 0L && rx >= lastRx && tx >= lastTx) {
            val secs = (now - lastAt) / 1e9
            runCatching { getSystemService(NotificationManager::class.java).notify(ID, notification(((rx - lastRx) / secs).toLong(), ((tx - lastTx) / secs).toLong())) }
        }
        lastRx = rx; lastTx = tx; lastAt = now
    }

    /** The icon: the download speed, large, the upload under it — white on clear, as Android draws status-bar icons. */
    private fun icon(down: Long, up: Long): IconCompat {
        val s = (24 * resources.displayMetrics.density).toInt()
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        val text = DataSpeed.short(down)
        p.textSize = s * if (text.length > 4) 0.42f else 0.52f
        c.drawText(text, s / 2f, s * 0.55f, p)
        p.typeface = Typeface.DEFAULT; p.textSize = s * 0.34f
        c.drawText("↑" + DataSpeed.short(up), s / 2f, s * 0.95f, p)
        return IconCompat.createWithBitmap(b)
    }

    private fun notification(down: Long, up: Long): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        val name = getString(R.string.data_live_speed) // made again when the language changed: an existing channel takes only its name and description
        if (nm.getNotificationChannel(CHANNEL)?.name?.toString() != name)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, name, NotificationManager.IMPORTANCE_LOW).apply { description = getString(R.string.data_channel_speed_about); setShowBadge(false) })
        val open = PendingIntent.getActivity(this, ID, Intent(this, DataActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, ID, Intent(this, DataSpeedService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(icon(down, up))
            .setContentTitle("↓ ${DataRules.bytes(down)}/s   ↑ ${DataRules.bytes(up)}/s")
            .setOngoing(true).setSilent(true).setOnlyAlertOnce(true).setShowWhen(false).setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open).addAction(0, getString(R.string.data_turn_off), stop).build()
    }
}
