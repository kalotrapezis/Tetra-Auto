package com.kalotrapezis.drive

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A sync every [minutes] (Settings › Sync, 30 by default), with the app closed too (asked 2026-09-26, the interval
 * 2026-10-13). Android runs it about then — it may group it with other apps' work, and in Doze it waits for the next
 * maintenance window. It no longer waits for unmetered Wi-Fi: a phone's hotspot is metered, so on one the job never
 * ran, and a sync only ever talks to private LAN addresses, which mobile data does not carry. It syncs in the job itself: a job may not start the foreground service from the background on newer
 * Android, and it has ten minutes, which a sync with nothing new needs a few seconds of.
 */
internal class SyncJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        val store = SyncStore(applicationContext)
        // Data's hourly look (Plan.md § Data): Android's counts into this device's files, then the plan's warnings
        // Mail's quarter-hourly look at the accounts this device fetches itself (MailEngine)
        if (params.jobId == MailEngine.JOB) {
            scope.launch { runCatching { MailEngine.syncAll(applicationContext) }; jobFinished(params, false); MailEngine.schedule(applicationContext, again = true) }
            return true
        }
        if (params.jobId == AssistantRunner.JOB) { // a scheduled AI action due on this device
            scope.launch { runCatching { AssistantRunner.runDue(applicationContext) }.onFailure { WriterLog.e("Scheduled AI: run failed", it) }; jobFinished(params, false) }
            return true
        }
        if (params.jobId == DataStats.JOB) {
            scope.launch { DataStats.refresh(applicationContext); jobFinished(params, false) }
            return true
        }
        // Send to computer's files, waiting for the computer (Outbox): tried again until it has them.
        if (params.extras.getBoolean(Outbox.EXTRA)) {
            scope.launch {
                val error = runCatching { SyncClient(applicationContext, store).sendOutbox() }.exceptionOrNull()
                jobFinished(params, error != null && !ScopedSync.tooOld(error))
            }
            return true
        }
        // A part of Drive waiting to travel (ScopedSync), or the Calendar's quarter-hourly look: that part alone.
        params.extras.getString(ScopedSync.SCOPE)?.let { part ->
            if (SyncService.isRunning || store.pairing() == null) return false // (the whole sync carries it; not paired)
            scope.launch {
                val error = runCatching { SyncClient(applicationContext, store).scopeOnly(part) }.onFailure { WriterLog.w("Scoped sync $part: ${it.message}") }.exceptionOrNull()
                // the look carries the Timer's part too: a timer started on the computer reaches a closed Tetra here
                if (params.jobId == ScopedSync.CALENDAR_LOOK) listOf("timer", "contacts", "data", "pills").forEach { runCatching { SyncClient(applicationContext, store).scopeOnly(it) } }
                if (params.jobId == ScopedSync.CALENDAR_LOOK) runCatching { MapsAge.check(applicationContext) }
                val tooOld = ScopedSync.tooOld(error) // the whole sync carries it
                jobFinished(params, error != null && !tooOld && params.jobId != ScopedSync.CALENDAR_LOOK) // a change waiting is tried again; a look waits for the next
            }
            return true
        }
        // A new photo or video: sync now (asked 2026-09-27) — the computer there or not, the trigger is armed again.
        val newPhoto = params.jobId == NEW_PHOTO
        if (newPhoto) watchNewPhotos(applicationContext)
        val paired = store.pairing() != null
        val sync = !SyncService.isRunning && paired && onLocalNetwork() && (newPhoto || System.currentTimeMillis() - store.lastBackup() >= minutes(applicationContext) * 50_000L)
        // Data's look rides on this one too, the computer there or not (the user, 29 Oct: Data refreshed and sent only when opened)
        val dataLook = params.jobId == ID && DataStats.inUse(applicationContext)
        if (!sync && !dataLook) return false
        scope.launch {
            if (dataLook) {
                DataStats.refresh(applicationContext)
                // sent inside the job: ScopedSync.soon's 2.5 s wait outlives it, and a frozen app sends nothing; the whole sync below carries it otherwise
                if (!sync && paired && !SyncService.isRunning && onLocalNetwork()) runCatching { SyncClient(applicationContext, store).scopeOnly("data") }
                if (!sync) return@launch jobFinished(params, false)
            }
            val failed = runCatching { SyncClient(applicationContext, store).backUp(listPhotos(applicationContext)) {} }.isFailure
            jobFinished(params, failed) // a failure (the computer asleep) is tried again later
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean { scope.cancel(); return true }

    companion object {
        private const val ID = 4130
        private const val NEW_PHOTO = 4131
        private const val MINUTES = "auto_sync_minutes"
        val choices = listOf(0 to R.string.common_off, 15 to R.string.sync_every_15, 30 to R.string.sync_every_30, 60 to R.string.sync_every_60)

        /** 0 is Off. Android runs a periodic job no more often than every 15 minutes. */
        fun minutes(context: Context): Int = context.getSharedPreferences("sync_job", Context.MODE_PRIVATE).getInt(MINUTES, 30)

        fun setMinutes(context: Context, value: Int) {
            context.getSharedPreferences("sync_job", Context.MODE_PRIVATE).edit().putInt(MINUTES, value).apply()
            schedule(context)
        }

        /** On some LAN — Wi-Fi, Ethernet, or this phone's own hotspot, which has no "active network" when data is off. */
        private fun onLocalNetwork(): Boolean = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList().any { i ->
                i.isUp && !i.isLoopback && i.inetAddresses.toList().any { it is java.net.Inet4Address && it.isSiteLocalAddress }
            }
        }.getOrDefault(false)

        /**
         * When Android adds a photo or a video, a sync about ten seconds later (a burst is one sync), on Wi-Fi, with
         * the app closed too. A job watching MediaStore runs once, so it is armed again each time; it does not survive a
         * restart, so opening the app arms it as well.
         */
        fun watchNewPhotos(context: Context) {
            val jobs = context.getSystemService(JobScheduler::class.java) ?: return
            val flags = JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
            jobs.schedule(JobInfo.Builder(NEW_PHOTO, ComponentName(context, SyncJob::class.java))
                .addTriggerContentUri(JobInfo.TriggerContentUri(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, flags))
                .addTriggerContentUri(JobInfo.TriggerContentUri(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, flags))
                .setTriggerContentUpdateDelay(10_000)
                .setTriggerContentMaxDelay(60_000)
                .build())
        }

        /**
         * The schedule lasts across restarts; called when the app opens and when the interval changes. A job from an
         * older build (Wi-Fi only) or another interval is replaced.
         */
        fun schedule(context: Context) {
            val jobs = context.getSystemService(JobScheduler::class.java) ?: return
            ScopedSync.lookEvery(context)
            jobs.getPendingJob(NEW_PHOTO).let { if (it == null || it.networkType != JobInfo.NETWORK_TYPE_NONE) watchNewPhotos(context) }
            val every = minutes(context) * 60_000L
            if (every == 0L) return jobs.cancel(ID)
            jobs.getPendingJob(ID)?.let { if (it.intervalMillis == every && it.networkType == JobInfo.NETWORK_TYPE_NONE) return }
            jobs.schedule(JobInfo.Builder(ID, ComponentName(context, SyncJob::class.java))
                .setPeriodic(every)
                .setPersisted(true)
                .build())
        }
    }
}

/**
 * A part of Drive that travels at once (the user, 18 Oct: "once there is an update on calendar like on notes send it
 * imediatly across devices as soon as connection available"). Notes' way, for files: a change here goes 2.5 s later;
 * the computer away or no network, it waits as a job that Android runs the moment there is one. A change on the
 * computer comes as its nudge (SyncServer's /sync, while Tetra is open here) and, with Tetra closed, within a quarter
 * of an hour. The Timer has its own part ("timer", DriveScope), each part its own wait.
 */
internal object ScopedSync {
    const val SCOPE = "scope"
    private const val CALENDAR_WAITING = 4140
    const val CALENDAR_LOOK = 4141
    private const val TIMER_WAITING = 4142
    private const val CONTACTS_WAITING = 4145 // 4143 is Send to computer's outbox job (SendToComputer.WAITING)
    private const val PHONE_WAITING = 4148
    private const val DATA_WAITING = 4151 // 4150 is Data's hourly look (DataStats.JOB)
    private const val MAIL_WAITING = 4152
    private const val BROWSER_WAITING = 4154 // 4153 is Mail's look (MailEngine.JOB)
    private const val PILLS_WAITING = 4156
    private val running = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = java.util.concurrent.ConcurrentHashMap<String, Job>() // a timer's push never cancels an event's

    /** A computer too old for parts ("Unknown request"), or for this part (a Tetra before the Timer: "Unknown scope"). */
    fun tooOld(e: Throwable?) = e?.message?.let { "Unknown request" in it || "Unknown scope" in it } == true

    /** [orWhole]: a change made here, which an older computer then takes by the whole sync (the computer's own "/sync" arranges its own). */
    fun soon(context: Context, scope: String, delayMs: Long = 2_500, orWhole: Boolean = false) {
        val app = context.applicationContext
        pending[scope]?.cancel()
        pending[scope] = running.launch {
            delay(delayMs)
            if (SyncService.isRunning) return@launch // the whole sync is carrying it now
            runCatching { SyncClient(app, SyncStore(app)).scopeOnly(scope) }.onFailure {
                if (tooOld(it)) { // the computer's Tetra is older: the whole sync, now
                    WriterLog.w("Scoped sync $scope: the computer's Tetra is too old; the whole sync carries it")
                    if (orWhole) runCatching { androidx.core.content.ContextCompat.startForegroundService(app, android.content.Intent(app, SyncService::class.java).setAction(SyncService.ACTION_START)) }
                    return@onFailure
                }
                WriterLog.w("Scoped sync $scope: waiting for a network (${it.message})")
                wait(app, scope)
            }
        }
    }

    /** Run by Android as soon as there is a network, and again until the computer has it. */
    private fun wait(context: Context, scope: String) {
        val jobs = context.getSystemService(JobScheduler::class.java) ?: return
        jobs.schedule(JobInfo.Builder(when (scope) { "timer" -> TIMER_WAITING; "contacts" -> CONTACTS_WAITING; "phone" -> PHONE_WAITING; "data" -> DATA_WAITING; "mail" -> MAIL_WAITING; "browser" -> BROWSER_WAITING; "pills" -> PILLS_WAITING; else -> CALENDAR_WAITING }, ComponentName(context, SyncJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setExtras(android.os.PersistableBundle().apply { putString(SCOPE, scope) })
            .setPersisted(true)
            .build())
    }

    /** A quarter-hourly look at the Calendar's part, so the computer's changes arrive with Tetra closed too. */
    fun lookEvery(context: Context) {
        val jobs = context.getSystemService(JobScheduler::class.java) ?: return
        if (SyncJob.minutes(context) == 0) return jobs.cancel(CALENDAR_LOOK) // sync without asking turned off: so is this
        if (jobs.getPendingJob(CALENDAR_LOOK) != null) return
        jobs.schedule(JobInfo.Builder(CALENDAR_LOOK, ComponentName(context, SyncJob::class.java))
            .setPeriodic(15 * 60_000L)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setExtras(android.os.PersistableBundle().apply { putString(SCOPE, "calendar") })
            .setPersisted(true)
            .build())
    }
}
