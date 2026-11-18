package com.kalotrapezis.drive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

internal data class AnalysisState(val done: Int, val total: Int, val paused: Boolean = false, val error: String? = null)

/**
 * Reading photos for documents, faces and tags, outside the Activity.
 *
 * It used to happen only while you were looking at People, Documents or search, in a thread that died with the
 * screen — so a photo taken this morning stayed invisible to search until you happened to open one of those
 * pages. It now runs by itself: when the gallery loads and finds photos nothing has read yet, and after a sync,
 * which is when the computer's photos have just landed here.
 *
 * One read per photo answers all three questions, so there is no separate document pass — see
 * PhotoClassifier.classify. Only one analysis runs at a time, and Pause and Stop reach it from the app, the
 * notification and the Dynamic Island alike, exactly as they do for a backup.
 */
internal class PhotoAnalysisService : Service() {
    companion object {
        private const val CHANNEL_ID = "analysis_nobadge" // progress is no count on the icon (4 Nov)
        private const val NOTIFICATION_ID = 2
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_PAUSE = "com.kalotrapezis.drive.ANALYSIS_PAUSE"
        private const val EXTRA_EVERYTHING = "everything"
        private const val EXTRA_FACES = "faces"

        /** The photos this run has to read: everything, for a rescan, or only what nothing has read yet. */
        private fun pending(context: Context, store: PhotoMetadataStore, everything: Boolean): List<Entry> {
            val read = if (everything) emptySet() else store.analyzedKeys()
            return listPhotos(context).filter { !it.isVideo && it.contentUri != null && it.photoKey !in read }
        }

        private val _state = MutableStateFlow<AnalysisState?>(null)
        val state = _state.asStateFlow()
        val isRunning: Boolean get() = _state.value != null
        private val _lastError = MutableStateFlow<String?>(null)
        val lastError = _lastError.asStateFlow()
        fun clearError() { _lastError.value = null }

        internal val paused = MutableStateFlow(false)
        fun togglePause() { paused.value = !paused.value }

        /**
         * Start reading. `everything` re-reads photos that already have a record (the two Rescan buttons);
         * otherwise only photos no version of the model has seen. Analysis stays opt-in: without consent this
         * does nothing at all, which is what makes it safe to call whenever the gallery reloads.
         */
        fun start(context: Context, everything: Boolean = false, faces: Boolean = true) {
            if (isRunning) return
            val app = context.applicationContext
            val store = PhotoMetadataStore(app)
            if (!store.peopleAnalysisEnabled() && !store.documentsAnalysisEnabled()) return
            // Nothing new means nothing at all: no service, no notification, no island. Worth a look off the
            // main thread first, because otherwise every gallery load would flash "Reading photos" and stop.
            Thread {
                if (everything || pending(app, store, everything = false).isNotEmpty()) runCatching {
                    ContextCompat.startForegroundService(app, Intent(app, PhotoAnalysisService::class.java)
                        .setAction(ACTION_START)
                        .putExtra(EXTRA_EVERYTHING, everything)
                        .putExtra(EXTRA_FACES, faces))
                }
            }.start()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val islandSupported by lazy { HyperIsland.isSupported(applicationContext) }
    private var lastDone = 0
    private var lastTotal = 0
    private var lastShown = 0L
    private var announced = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> job?.cancel()
            else -> analyze(intent?.getBooleanExtra(EXTRA_EVERYTHING, false) == true, intent?.getBooleanExtra(EXTRA_FACES, true) != false)
        }
        return START_NOT_STICKY
    }

    private fun analyze(everything: Boolean, faces: Boolean) {
        if (job != null) return
        announced = false
        paused.value = false
        startForeground(NOTIFICATION_ID, notification(0, 0))
        _state.value = AnalysisState(0, 0)
        scope.launch { paused.collect { value ->
            if (job == null) return@collect
            _state.value = _state.value?.copy(paused = value)
            show(lastDone, lastTotal)
        } }
        job = scope.launch {
            val result = runCatching { run(everything, faces) }
            job = null
            _state.value = null
            _lastError.value = result.fold({ it }, { e ->
                if (e is kotlinx.coroutines.CancellationException) null else e.message ?: getString(R.string.photos_error_read_gallery)
            })
            paused.value = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Returns what could not be read, as a sentence, or null when everything went through. */
    private suspend fun run(everything: Boolean, faces: Boolean): String? {
        val store = PhotoMetadataStore(applicationContext)
        val pending = pending(applicationContext, store, everything)
        if (pending.isEmpty()) return null
        val documentKeys = store.classifiedKeys("document")
        val quality = store.searchQuality()
        val modelVersion = "local-v7-${quality.name.lowercase(Locale.ROOT)}"
        val analyzeFaces = faces && store.peopleAnalysisEnabled()
        var skipped = 0
        _state.value = AnalysisState(0, pending.size)
        show(0, pending.size)
        PhotoClassifier(applicationContext, advancedSceneTags = quality == PhotoSearchQuality.Advanced).use { classifier ->
            pending.forEachIndexed { index, entry ->
                paused.first { !it }
                entry.contentUri?.let { uri -> runCatching {
                    classifier.classify(uri, entry.takenMillis, analyzeFaces = analyzeFaces && !entry.isScreenshot() && entry.photoKey !in documentKeys).also { read ->
                        store.recordClassification(entry.photoKey, read.documentConfidence, read.faces, read.labels, modelVersion, entry.takenMillis)
                    }
                }.onFailure { skipped++ } }
                onProgress(index + 1, pending.size)
            }
        }
        return if (skipped > 0) resources.getQuantityString(R.plurals.photos_skipped, skipped, skipped) else null
    }

    private fun onProgress(done: Int, total: Int) {
        _state.value = AnalysisState(done, total, paused.value)
        // The in-app progress follows every photo; the island must not, or it flashes open on each one.
        if (done >= total || System.currentTimeMillis() - lastShown >= 1_000) show(done, total)
    }

    private fun show(done: Int, total: Int) {
        lastDone = done; lastTotal = total; lastShown = System.currentTimeMillis()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(done, total))
    }

    private fun notification(done: Int, total: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.photos_reading), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }
        val isPaused = paused.value
        val title = getString(if (isPaused) R.string.photos_reading_paused else R.string.photos_reading)
        val text = if (total > 0) getString(R.string.photos_done_of, done, total) else getString(R.string.photos_looking)
        val pausePending = android.app.PendingIntent.getBroadcast(
            this, 3, Intent(ACTION_PAUSE).setPackage(packageName),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopPending = android.app.PendingIntent.getService(
            this, 4, Intent(this, PhotoAnalysisService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tag)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(total, done, total == 0 && !isPaused)
            .setOngoing(true)
            .addAction(0, getString(if (isPaused) R.string.common_resume else R.string.common_pause), pausePending)
            .addAction(0, getString(R.string.common_stop), stopPending)
        if (islandSupported) {
            HyperIsland.decorate(builder, title, text, done, total, announce = !announced, business = HyperIsland.ANALYSIS)
            announced = true
        }
        return builder.build()
    }

    override fun onDestroy() {
        scope.cancel()
        _state.value = null
        super.onDestroy()
    }
}
