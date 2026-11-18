package com.kalotrapezis.drive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Tetra listening for the paired computer with the app closed. The old rule was "nothing listens once the app is
 * closed"; the user changed it on 23 Oct, because Android cuts a backgrounded app off the network within seconds
 * (`APP_BACKGROUND`, measured on the user's Xiaomi 15) and only a foreground service keeps SyncServer reachable. With
 * it, the computer's "sync now" and desktop Maps' "where is the phone" reach a phone in a pocket.
 *
 * Runs while a computer is paired and Settings › Sync › Listen in the background is on: started when Tetra opens and
 * after a restart. With the location allowed, and started from the open app as Android requires, it is also a
 * location service, so the phone answers where it is without a tap (PhoneLocation).
 */
internal class SyncListenService : Service() {
    companion object {
        private const val ID = 0x5EA7
        private const val CHANNEL = "listening_nobadge" // no count on the launcher icon (4 Nov): it is always there
        private const val ACTION_STOP = "com.kalotrapezis.drive.LISTEN_STOP"
        private const val PREF = "listen_in_background"

        @Volatile private var listening = false
        /** Holding the location right in the background: PhoneLocation needs no tap. */
        @Volatile var withLocation = false
            private set

        private fun prefs(c: Context) = c.getSharedPreferences("sync_listen", Context.MODE_PRIVATE)
        fun enabled(c: Context) = prefs(c).getBoolean(PREF, true)
        fun setEnabled(c: Context, on: Boolean) {
            prefs(c).edit().putBoolean(PREF, on).apply()
            if (on) start(c) else stop(c)
        }

        /** Starts it, or (from the open app) lets it take the location right it may now have. */
        fun start(c: Context) {
            if (!enabled(c) || SyncStore(c.applicationContext).pairing() == null) return
            runCatching { ContextCompat.startForegroundService(c, Intent(c, SyncListenService::class.java)) }
                .onFailure { WriterLog.e("Sync: cannot listen in the background", it) }
        }

        fun stop(c: Context) { c.stopService(Intent(c, SyncListenService::class.java)) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { setEnabled(this, false); return START_NOT_STICKY }
        val n = notification()
        // the location right only from the open app (Android refuses it from a restart or the background): plain then
        val placed = hasLocation(this) && runCatching {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        }.isSuccess
        if (!placed && runCatching { startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }.onFailure { WriterLog.e("Sync: cannot listen in the background", it) }.isFailure) {
            stopSelf()
            return START_NOT_STICKY
        }
        withLocation = placed
        if (!listening) listening = runCatching { SyncServer.acquire(applicationContext, SyncStore(applicationContext)) }.isSuccess
        PhoneCall.Watcher.start(applicationContext) // the phone's calls shown on the computer (its call bubble)
        runCatching { DataStats.startAll(applicationContext) } // Data's drops, roaming and warnings while Tetra lives
        return START_STICKY
    }

    override fun onDestroy() {
        if (listening) runCatching { SyncServer.release() }
        PhoneCall.Watcher.stop(applicationContext)
        listening = false
        withLocation = false
        super.onDestroy()
    }

    private fun notification(): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        // made again when its name is not the one in the language now shown (an existing channel keeps its first name otherwise)
        if (nm.getNotificationChannel(CHANNEL)?.name != getString(R.string.sync_listen_channel)) nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.sync_listen_channel), NotificationManager.IMPORTANCE_MIN).apply { description = getString(R.string.sync_listen_channel_about); setShowBadge(false) })
        val open = PendingIntent.getActivity(this, ID, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, ID, Intent(this, SyncListenService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val computer = SyncStore(applicationContext).pairing()?.name
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_sync)
            .setContentTitle(computer?.let { getString(R.string.sync_listening_for, it) } ?: getString(R.string.sync_listening_computer))
            .setContentText(getString(R.string.sync_listening_text))
            .setOngoing(true).setSilent(true).setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open).addAction(0, getString(R.string.common_stop), stop).build()
    }
}
