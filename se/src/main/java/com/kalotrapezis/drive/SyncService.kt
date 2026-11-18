package com.kalotrapezis.drive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** [unreachable]: the sync ended because the computer did not answer ([SyncException.unreachable]). */
internal data class BackupState(val progress: BackupProgress?, val result: BackupResult?, val error: String?, val paused: Boolean = false, val unreachable: Boolean = false)

/**
 * Runs the backup outside the Activity's lifecycle so it survives the app being closed or backgrounded.
 * ponytail: no restart-on-kill (Android can still kill a foreground service under memory pressure); add
 * WorkManager as a retry layer if that turns out to happen often in practice.
 */
internal class SyncService : Service() {
    companion object {
        private const val CHANNEL_ID = "sync_nobadge" // a running sync is no count on the icon (4 Nov)
        private const val AUTO_SYNC_GAP_MS = 15 * 60_000L
        /** How long the service holds itself open waiting for a network to come back before giving up. */
        private const val WAIT_FOR_NETWORK_MS = 10 * 60_000L

        /**
         * A VPN answers for the connection it hides, and an ad-blocking VPN answers "no idea": its network has
         * no metering to report and names no network underneath it. Asking it alone turned every automatic sync
         * off for anyone running one, so when a VPN is in the way this asks the real networks instead.
         */
        private fun onUnmeteredNetwork(networks: ConnectivityManager): Boolean {
            val active = networks.getNetworkCapabilities(networks.activeNetwork) ?: return false
            if (!active.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return active.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
            @Suppress("DEPRECATION") // allNetworks is how you see past a VPN; there is no replacement that does
            return networks.allNetworks.any { network ->
                networks.getNetworkCapabilities(network)?.let {
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                } == true
            }
        }
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_PAUSE = "com.kalotrapezis.drive.PAUSE"

        private val _state = MutableStateFlow<BackupState?>(null)
        val state = _state.asStateFlow()
        val isRunning: Boolean get() = _state.value != null
        /** Set from the app, the notification and the Dynamic Island alike; the running service watches it. */
        internal val paused = MutableStateFlow(false)
        fun togglePause() { paused.value = !paused.value }

        /**
         * Sync without being asked — when the app opens, and after the scanner saves a PDF. It is deliberately
         * easy to talk out of: no paired computer, a backup already running, a metered connection or a sync a
         * few minutes ago and it simply does not happen. `gap` is 0 for "something just changed, send it now".
         */
        fun syncInBackground(context: Context, gap: Long = AUTO_SYNC_GAP_MS) {
            if (isRunning) return
            val app = context.applicationContext
            val store = SyncStore(app)
            if (store.pairing() == null) return
            if (System.currentTimeMillis() - store.lastBackup() < gap) return
            val networks = app.getSystemService(ConnectivityManager::class.java) ?: return
            // Wi-Fi only: a backup is the user's whole camera roll, never something to put on mobile data by itself.
            if (!onUnmeteredNetwork(networks)) return
            runCatching {
                ContextCompat.startForegroundService(app, Intent(app, SyncService::class.java).setAction(ACTION_START))
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val islandSupported by lazy { HyperIsland.isSupported(applicationContext) }
    private var lastText = ""
    private var lastDone = 0
    private var lastTotal = 0
    private var lastShown = 0L
    private var announced = false
    private var attempt = 0
    private var waiting: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // Stop while it is waiting for a network has nothing to cancel, and the button must still work.
            ACTION_STOP -> job?.cancel() ?: run { stopWaiting(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            else -> startBackup()
        }
        return START_NOT_STICKY
    }

    private fun startBackup() {
        if (job != null) return
        stopWaiting()
        attempt++
        val store = SyncStore(applicationContext)
        val client = SyncClient(applicationContext, store)
        announced = false
        paused.value = false
        startForeground(NOTIFICATION_ID, notification(getString(R.string.sync_checking_photos), 0, 0))
        _state.value = BackupState(null, null, null)
        // Pause can come from the app, the notification or the island; all three end up here.
        scope.launch { paused.collect { value ->
            if (job == null) return@collect
            _state.value = _state.value?.copy(paused = value)
            show(lastText, lastDone, lastTotal)
        } }
        job = scope.launch {
            val result = runCatching { client.backUp(listPhotos(applicationContext), checkpoint = { paused.first { !it } }) { p -> onProgress(p) } }
            _state.value = result.fold(
                { r -> BackupState(null, r, null) },
                { e -> BackupState(null, null, if (e is kotlinx.coroutines.CancellationException) getString(R.string.sync_stopped) else e.message ?: getString(R.string.sync_backup_failed), unreachable = (e as? SyncException)?.unreachable == true) },
            )
            job = null
            // A sync is the other moment photos appear here without anyone taking them.
            PhotoAnalysisService.start(applicationContext)
            if (!paused.value && SyncRules.retriesAfterNetworkLoss(attempt, result.exceptionOrNull())) return@launch waitForNetwork()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * The Wi-Fi moving under a sync — an extender handing over to the main router, 24 September — used to end
     * it for good. The service stays up instead, says so, and starts again when a network is back; after
     * SyncRules.NETWORK_RETRIES it gives up rather than sit here draining the battery, and the next sync waits
     * for the app to be opened.
     */
    private fun waitForNetwork() {
        val networks = getSystemService(ConnectivityManager::class.java)
        if (networks == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
        show(getString(R.string.sync_waiting_wifi), 0, 0)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch { delay(5_000); if (job == null) startBackup() }
            }
        }
        waiting = callback
        runCatching {
            networks.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    .build(),
                callback,
            )
        }.onFailure { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
        scope.launch {
            delay(WAIT_FOR_NETWORK_MS)
            if (waiting === callback && job == null) { stopWaiting(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
    }

    private fun stopWaiting() {
        waiting?.let { callback -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) } }
        waiting = null
    }

    private fun onProgress(p: BackupProgress) {
        _state.value = BackupState(p, null, null, paused.value)
        // The in-app screen follows every file; the notification does not need to, and re-posting it per file
        // makes the Dynamic Island flash open again each time.
        val last = p.done >= p.total
        if (last || System.currentTimeMillis() - lastShown >= 1_000) show(getString(R.string.sync_stage_progress, p.stage, p.done, p.total), p.done, p.total)
    }

    private fun show(text: String, done: Int, total: Int) {
        lastText = text; lastDone = done; lastTotal = total; lastShown = System.currentTimeMillis()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, done, total))
    }

    private fun notification(text: String, done: Int, total: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        // made again when its name is not the one in the language now shown (an existing channel keeps its first name otherwise)
        val channel = getString(R.string.app_sync)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID)?.name != channel) nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, channel, NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val isPaused = paused.value
        val stopPending = service(1, ACTION_STOP)
        val pausePending = android.app.PendingIntent.getBroadcast(
            this, 2, Intent(ACTION_PAUSE).setPackage(packageName),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pauseLabel = getString(if (isPaused) R.string.common_resume else R.string.common_pause)
        val title = getString(if (isPaused) R.string.sync_backup_paused else R.string.sync_backing_up)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(total, done, total == 0 && !isPaused)
            .setOngoing(true)
            .addAction(0, pauseLabel, pausePending)
            .addAction(0, getString(R.string.common_stop), stopPending)
        // Additive: on a Xiaomi with focus notifications this also puts the backup in the Dynamic Island,
        // and everywhere else the extra is ignored and this is the same notification as before.
        if (islandSupported) {
            HyperIsland.decorate(builder, title, text, done, total, announce = !announced)
            announced = true
        }
        return builder.build()
    }

    private fun service(requestCode: Int, action: String): android.app.PendingIntent = android.app.PendingIntent.getService(
        this, requestCode, Intent(this, SyncService::class.java).setAction(action),
        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun onDestroy() {
        stopWaiting()
        scope.cancel()
        _state.value = null
        super.onDestroy()
    }
}
