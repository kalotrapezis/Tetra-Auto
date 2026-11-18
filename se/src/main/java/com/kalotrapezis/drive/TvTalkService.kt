package com.kalotrapezis.drive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * AI Assist talking on the home screen's TV itself (8 Nov: "the conversation does not start on the widget, it opens the panel
 * from below"): a tap starts this, in the foreground with the microphone, the same talk as Home's widget in the app (HomeTalk);
 * the TV shows each phase — rings while it listens, a thought cloud, its voice, snow on a long think. An answer that needs a
 * screen leaves a notification to open it. A tap while it talks stops it.
 */
class TvTalkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var reader: ReadAloud? = null
    private var talk: HomeTalk? = null
    private var standby: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val types = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        runCatching { if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notice(), types) else startForeground(ID, notice()) }
            .onFailure { WriterLog.w("AI Assist TV: the microphone was not allowed from the home screen: ${it.message}"); stopSelf(); return START_NOT_STICKY }
        val t = talk ?: HomeTalk(applicationContext, scope, ReadAloud(applicationContext).also { reader = it }, openScreen = ::offerScreen).also { t ->
            talk = t
            t.changed = { follow(t) }
        }
        t.tap { stopSelf() } // no key or no microphone yet: the widget opens the sheet instead (TvWidget), nothing to do here
        return START_NOT_STICKY
    }

    /** The TV follows the talk; resting again, this ends. */
    private fun follow(t: HomeTalk) {
        standby?.cancel()
        val p = t.phase
        TvWidget.showPhase(this, p)
        if (p == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
        if (p == LivePhase.Thinking) standby = scope.launch { delay(STANDBY_MS); TvWidget.showPhase(this@TvTalkService, p, snow = true) }
    }

    /** An answer with a call, choices, cards or a file: a tap on this opens it in AI Assist, the same conversation. */
    private fun offerScreen() {
        val open = PendingIntent.getActivity(this, ID + 1, Intent(this, AssistantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, channel(this)).setSmallIcon(R.drawable.ic_mic).setContentTitle(getString(R.string.app_assistant))
            .setContentText(getString(R.string.tv_talk_open)).setContentIntent(open).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
        runCatching { NotificationManagerCompat.from(this).notify(ID + 1, n) }
    }

    private fun notice() = NotificationCompat.Builder(this, channel(this)).setSmallIcon(R.drawable.ic_mic).setContentTitle(getString(R.string.app_assistant))
        .setContentText(getString(R.string.assistant_listening)).setOngoing(true).setSilent(true).build()

    override fun onDestroy() { standby?.cancel(); talk?.end(); talk = null; reader?.shutdown(); scope.cancel(); TvWidget.showPhase(this, null); super.onDestroy() }

    companion object {
        private const val ID = 7411
        private fun channel(c: Context): String {
            val id = "tv_talk"
            c.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(id, c.getString(R.string.tv_widget_label), NotificationManager.IMPORTANCE_DEFAULT))
            return id
        }
    }
}
