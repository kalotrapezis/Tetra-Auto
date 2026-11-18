package com.kalotrapezis.drive

import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Process
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Data's counts (Plan.md § Data): Android's own per-app network statistics (NetworkStatsManager, with the user's Usage
 * access) copied hourly into this device's files in Tetra/.System/Data/Shared/usage, so the history outlives Android's ~90 days and the
 * computer and the tablet see it; then the plan's warnings and the roaming notices.
 */
internal object DataStats {
    const val JOB = 4150
    private const val CHANNEL = "data_warnings_nobadge"
    private const val BUCKET = 2 * 3600_000L // Android keeps per-app counts in 2-hour buckets
    private const val KEEP_DAYS = 85L // older days are kept as the files have them: Android has forgotten them
    private val HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH")

    fun hasAccess(c: Context): Boolean {
        val ops = c.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        return if (mode == AppOpsManager.MODE_DEFAULT) c.checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
            else mode == AppOpsManager.MODE_ALLOWED
    }

    /** Data is in use here once it is set up or may count: only then does Tetra watch, look hourly, and write Tetra/.System/Data/Shared. */
    fun inUse(c: Context) = hasAccess(c) || DataFiles.plan() != null

    /** Everything Data runs in the background, started when Tetra opens or its listening service starts. */
    fun startAll(c: Context) {
        if (!inUse(c)) { // not (or no longer) used: nothing of Data left running
            c.getSystemService(JobScheduler::class.java)?.cancel(JOB)
            if (DataSpeed.enabled(c)) DataSpeed.setEnabled(c, false)
            return
        }
        schedule(c); watchUsage(c); DataSignal.watch(c); DataSpeed.start(c)
    }

    /**
     * Nothing left behind (the user, 25 Oct: "a cleanup, no garbage"): sync's copies of the months, half-written files of an
     * interrupted write, the memory of warnings and day passes past, and — after 13 months — a month's per-app and hourly
     * detail (its days stay, for the year view). Once a day.
     */
    fun clean(c: Context, today: LocalDate = LocalDate.now()) {
        val p = prefs(c)
        if (p.getString("cleaned", "") == today.toString()) return
        p.edit().putString("cleaned", today.toString()).apply()
        runCatching { DataFiles.dropCopies().takeIf { it > 0 }?.let { WriterLog.i("Data: $it sync copies of the months removed") } }
        runCatching { DataFiles.settlePlan().takeIf { it > 0 }?.let { WriterLog.i("Data: $it sync copies of the plan removed"); ScopedSync.soon(c, "data") } }
        val root = DataFiles.root()
        listOf(root, File(root, "usage"), File(root, "spots")).flatMap { it.listFiles().orEmpty().toList() }
            .filter { it.name.startsWith(".") && it.name.endsWith(".tmp") && System.currentTimeMillis() - it.lastModified() > 3600_000 }.forEach { it.delete() }
        // the warnings given in cycles before the last two, the day passes before the last month
        val e = p.edit()
        p.all.keys.filter { it.startsWith("warned.") }.sortedDescending().drop(2).forEach { e.remove(it) }
        p.all.keys.filter { it.startsWith("pass.") && runCatching { LocalDate.parse(it.removePrefix("pass.")).isBefore(today.minusDays(30)) }.getOrDefault(true) }.forEach { e.remove(it) }
        e.apply()
        // this device's months over 13 months old: their days and events only
        val device = DataFiles.device()
        val old = today.minusMonths(13).withDayOfMonth(1).toString().take(7)
        File(root, "usage").listFiles().orEmpty().filter { it.name.endsWith(".$device.jsonl") && it.name.take(7) < old }.forEach { f ->
            runCatching {
                val m = DataFiles.parse(device, f.readText())
                if (m.apps.isNotEmpty() || m.hours.isNotEmpty()) DataFiles.write(f, DataFiles.text(m.copy(apps = emptyList(), hours = emptyList())))
            }
        }
    }

    fun accessIntent(c: Context) = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, android.net.Uri.fromParts("package", c.packageName, null))

    private fun prefs(c: Context) = c.getSharedPreferences("data", Context.MODE_PRIVATE)

    /** The look: what opening Data does, the hourly job, a 50 MB step and the 30-minute sync (SyncJob) too. Without usage access [record] leaves the files as they are. */
    fun refresh(c: Context) { runCatching { record(c); checkRoaming(c) }.onFailure { WriterLog.w("Data: the look failed (${it.message})") } }

    /** The hourly look, every hour Android lets it run (no network needed). */
    fun schedule(c: Context) {
        val jobs = c.getSystemService(JobScheduler::class.java) ?: return
        if (jobs.getPendingJob(JOB) != null) return
        jobs.schedule(JobInfo.Builder(JOB, ComponentName(c, SyncJob::class.java)).setPeriodic(3600_000L).setPersisted(true).build())
    }

    private fun floorBucket(t: Long) = t - Math.floorMod(t, BUCKET)
    private fun at(d: LocalDate, zone: ZoneId) = d.atStartOfDay(zone).toInstant().toEpochMilli()

    private data class Uid(var fg: Long = 0, var bg: Long = 0, var roam: Long = 0, var hotFg: Long = 0, var hotBg: Long = 0, var car: Long = 0)

    /** A query's buckets: each one's UID, bytes, foreground and roaming. */
    private inline fun each(s: NetworkStats?, f: (NetworkStats.Bucket) -> Unit) {
        s ?: return
        val b = NetworkStats.Bucket()
        try { while (s.hasNextBucket()) { s.getNextBucket(b); f(b) } } finally { s.close() }
    }

    private fun name(c: Context, uid: Int): Pair<String, String> = when (uid) {
        NetworkStats.Bucket.UID_TETHERING -> "hotspot" to "Hotspot"
        NetworkStats.Bucket.UID_REMOVED -> "removed" to "Removed apps"
        Process.SYSTEM_UID, 0 -> "android" to "Android system"
        else -> {
            val pm = c.packageManager
            val pkg = pm.getPackagesForUid(uid)?.firstOrNull()
            // an app with no icon in the launcher is hidden from Tetra (no QUERY_ALL_PACKAGES): Android may still say its name
            if (pkg == null) (if (uid < Process.FIRST_APPLICATION_UID) "android" to "Android system" else pm.getNameForUid(uid)?.let { it.substringBefore(':') to it.substringBefore(':') } ?: ("uid:$uid" to "Other apps"))
            else pkg to (runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg))
        }
    }

    /** Roaming countries this device saw, by day (the events), for the day pass. */
    private fun roamingOutsideEu(events: List<DataEvent>, zone: ZoneId): Set<LocalDate> = events.filter { it.kind == "roaming" && it.note.isNotEmpty() && !DataRules.inEu(it.note) }
        .mapNotNull { runCatching { java.time.OffsetDateTime.parse(it.at).atZoneSameInstant(zone).toLocalDate() }.getOrNull() }.toSet()

    /** One day from Android's counts: [s]..[e] snapped to its buckets, so none is lost between two days. */
    private fun day(c: Context, nsm: NetworkStatsManager, d: LocalDate, zone: ZoneId, now: Long, outsideEu: Set<LocalDate>): Pair<DataDay, List<DataApp>> {
        val s = floorBucket(at(d, zone))
        val e = minOf(floorBucket(at(d.plusDays(1), zone)), floorBucket(now) + BUCKET)
        val apps = mutableListOf<DataApp>()
        var mobile = 0L; var wifi = 0L; var roam = 0L; var hotspot = 0L; var via = 0L
        val client = PhoneRelay.noCalls(c) // a tablet: its metered Wi-Fi is the phone's hotspot; a phone's is the car's (Android Auto)
        for ((type, net) in listOf(ConnectivityManager.TYPE_MOBILE to "mobile", ConnectivityManager.TYPE_WIFI to "wifi")) {
            val byUid = HashMap<Int, Uid>()
            each(runCatching { nsm.querySummary(type, null, s, e) }.getOrNull()) { b ->
                val n = b.rxBytes + b.txBytes
                val u = byUid.getOrPut(b.uid) { Uid() }
                val fg = b.state == NetworkStats.Bucket.STATE_FOREGROUND
                // a metered Wi-Fi is a phone's hotspot (Android's hotspots say so); Tetra's own there is its sync with the phone: Wi-Fi
                if (client && net == "wifi" && b.metered == NetworkStats.Bucket.METERED_YES && b.uid != Process.myUid()) { if (fg) u.hotFg += n else u.hotBg += n }
                else { if (fg) u.fg += n else u.bg += n; if (!client && net == "wifi" && b.metered == NetworkStats.Bucket.METERED_YES) u.car += n } // a phone's metered Wi-Fi: the car's
                if (b.roaming == NetworkStats.Bucket.ROAMING_YES) u.roam += n
            }
            byUid.forEach { (uid, u) ->
                val total = u.fg + u.bg; val hot = u.hotFg + u.hotBg
                if (net == "mobile") { mobile += total; roam += u.roam; if (uid == NetworkStats.Bucket.UID_TETHERING) hotspot += total } else { wifi += total + hot; via += hot + u.car } // via: a tablet's hotspot use, a phone's Android Auto link
                val (app, label) = name(c, uid)
                if (total > 0) apps += DataApp(d, app, label, net, u.fg, u.bg, u.roam)
                if (hot > 0) apps += DataApp(d, app, label, "hotspot", u.hotFg, u.hotBg, 0) // the tablet's or a phone's use of another phone's hotspot
            }
        }
        // two UIDs of one package (or several system ones) are one row
        val merged = apps.groupBy { it.app to it.net }.map { (_, l) -> l.reduce { a, b -> a.copy(fg = a.fg + b.fg, bg = a.bg + b.bg, roaming = a.roaming + b.roaming) } }
        return DataDay(d, mobile, wifi, roam, hotspot, via, d in outsideEu) to merged
    }

    /** The day's hours (Android's 2-hour buckets), all apps together; the phone's hotspot (what it shared) apart as "tether". */
    private fun hours(nsm: NetworkStatsManager, s: Long, e: Long, zone: ZoneId): List<DataHour> {
        val out = HashMap<Pair<String, String>, Long>()
        for ((type, net) in listOf(ConnectivityManager.TYPE_MOBILE to "mobile", ConnectivityManager.TYPE_WIFI to "wifi"))
            each(runCatching { nsm.queryDetails(type, null, s, e) }.getOrNull()) { b ->
                val h = Instant.ofEpochMilli(b.startTimeStamp).atZone(zone).format(HOUR)
                out.merge(h to (if (net == "mobile" && b.uid == NetworkStats.Bucket.UID_TETHERING) "tether" else net), b.rxBytes + b.txBytes, Long::plus)
            }
        return out.filter { it.value > 0 }.map { (k, v) -> DataHour(k.first, k.second, v) }
    }

    /**
     * The hotspot's share of Wi-Fi by 2-hour slot, [from]..[e]: Android's per-hour history does not keep the metered flag, so
     * one summary per slot (24 a day); asked only for yesterday and today, older slots stay as written.
     */
    private fun hotspotHours(nsm: NetworkStatsManager, from: Long, e: Long, zone: ZoneId): List<DataHour> {
        val out = mutableListOf<DataHour>()
        var t = from
        while (t < e) {
            var n = 0L
            each(runCatching { nsm.querySummary(ConnectivityManager.TYPE_WIFI, null, t, t + BUCKET) }.getOrNull()) { b ->
                if (b.metered == NetworkStats.Bucket.METERED_YES && b.uid != Process.myUid()) n += b.rxBytes + b.txBytes
            }
            if (n > 0) out += DataHour(Instant.ofEpochMilli(t).atZone(zone).format(HOUR), "hotspot", n)
            t += BUCKET
        }
        return out
    }

    /** Tetra's own notes (drops, roaming countries, Tetra's sync over the hotspot), kept until written into a month. */
    private fun eventsFile(c: Context) = File(c.filesDir, "data-events.jsonl")

    fun note(c: Context, e: DataEvent) = synchronized(this) { runCatching { eventsFile(c).appendText(DataFiles.line(e) + "\n") } }

    private fun events(c: Context) = runCatching { DataFiles.parse("", eventsFile(c).readText()).events }.getOrDefault(emptyList())

    /**
     * The look: this month's file (and, the first time, the months Android still has) written again from Android's counts;
     * days older than Android keeps stay as they were. Then the warnings. Returns this device's months.
     */
    @Synchronized fun record(c: Context): List<DataMonth> {
        // without the access Android gives no counts: the files are left exactly as they are (25 Oct: once rewritten without their
        // days when the access was taken away); the drops and roaming notes wait in the events file until it comes back
        if (!hasAccess(c)) { runCatching { clean(c) }; return DataFiles.all()[DataFiles.device()].orEmpty() }
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val today = LocalDate.now(zone)
        val device = DataFiles.device()
        val nsm = c.getSystemService(NetworkStatsManager::class.java)
        val events = events(c)
        val outsideEu = roamingOutsideEu(events, zone)
        val first = !DataFiles.monthFile(today.toString().take(7)).exists()
        val months = (if (first) (0L..3L) else (0L..(if (today.dayOfMonth <= 1) 1L else 0L))).map { today.minusMonths(it).withDayOfMonth(1) }
        var changed = false
        val written = months.mapNotNull { m ->
            val file = DataFiles.monthFile(m.toString().take(7))
            val old = runCatching { DataFiles.parse(device, file.readText()) }.getOrNull()
            val keepBefore = today.minusDays(KEEP_DAYS)
            val days = mutableListOf<DataDay>(); val apps = mutableListOf<DataApp>()
            var d = m
            while (d.month == m.month && !d.isAfter(today)) {
                if (d.isBefore(keepBefore) && old != null) { days += old.days.filter { it.day == d }; apps += old.apps.filter { it.day == d } }
                else if (hasAccess(c)) day(c, nsm, d, zone, now, outsideEu).let { (dd, aa) -> if (dd.mobile + dd.wifi > 0 || old?.days?.any { it.day == d } == true) { days += dd; apps += aa } }
                d = d.plusDays(1)
            }
            val s = floorBucket(at(maxOf(m, keepBefore), zone)); val e = floorBucket(now) + BUCKET
            val hs = maxOf(s, floorBucket(at(today.minusDays(1), zone)))
            val sKey = Instant.ofEpochMilli(s).atZone(zone).format(HOUR); val hsKey = Instant.ofEpochMilli(hs).atZone(zone).format(HOUR)
            // only a device without a SIM uses another phone's hotspot; a phone's metered Wi-Fi is the car's (Android Auto) and stays Wi-Fi
            val hotThen = PhoneRelay.noCalls(c) && days.any { it.viaHotspot > 0 && !it.day.isBefore(today.minusDays(1)) }
            // a phone's own "hotspot" hours (its car link, before 25 Oct) are not kept: on a phone the hotspot is "tether"
            val hrs = old?.hours.orEmpty().filter { it.hour < sKey || (PhoneRelay.noCalls(c) && it.net == "hotspot" && it.hour < hsKey) } +
                (if (hasAccess(c)) hours(nsm, s, e, zone) + (if (hotThen && hs < e) hotspotHours(nsm, hs, e, zone) else emptyList()) else emptyList())
            val ev = (old?.events.orEmpty() + events.filter { it.at.startsWith(m.toString().take(7)) }).distinct()
            val month = DataMonth(device, days, apps, hrs.filter { it.hour.startsWith(m.toString().take(7)) }, ev, DataFiles.deviceName(c))
            if (month.days.isEmpty() && month.events.isEmpty() && old == null) return@mapNotNull null
            // stamped with when it was counted (the others say how old it is); counts unchanged keep the old stamp: no write, no sync
            val same = old != null && runCatching { file.readText() }.getOrNull() == DataFiles.text(month.copy(at = old.at))
            if (DataFiles.write(file, DataFiles.text(month.copy(at = if (same) old!!.at else now)))) changed = true
            month
        }
        runCatching { clean(c) }
        // the events now in their months' files
        if (events.isNotEmpty()) runCatching { eventsFile(c).delete() }
        if (changed) ScopedSync.soon(c, "data")
        runCatching { warn(c, written + DataFiles.all()[device].orEmpty()) }.onFailure { WriterLog.w("Data: warnings not checked (${it.message})") }
        runCatching { DataSignal.daily(c, DataFiles.all()) }
        return written
    }

    // ---- the warnings ----

    private fun channel(c: Context): NotificationManager {
        val nm = c.getSystemService(NotificationManager::class.java)
        // made again each time: an existing channel takes only its name and description, in the language now
        nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.data_channel_warnings), NotificationManager.IMPORTANCE_DEFAULT).apply { setShowBadge(false); description = c.getString(R.string.data_channel_warnings_about) })
        return nm
    }

    private fun open(c: Context, req: Int) = PendingIntent.getActivity(c, req, Intent(c, DataActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun settings(c: Context, action: String, req: Int) = PendingIntent.getActivity(c, req, Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun notify(c: Context, id: Int, title: String, text: String, button: Pair<String, PendingIntent>? = null) {
        val n = NotificationCompat.Builder(c, CHANNEL.also { channel(c) }).setSmallIcon(R.drawable.ic_data_usage).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open(c, id)).setAutoCancel(true)
            .apply { button?.let { (label, pi) -> addAction(0, label, pi) } }.build()
        runCatching { c.getSystemService(NotificationManager::class.java).notify(id, n) }
    }

    private fun warn(c: Context, months: List<DataMonth>) {
        val plan = DataFiles.plan() ?: return
        val today = LocalDate.now()
        val st = DataRules.status(plan, DataFiles.days(months), today)
        val p = prefs(c)
        val key = "warned.${st.cycle.start}"
        val given = p.getStringSet(key, emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
        val due = DataRules.due(plan, st.used, st.size, given)
        due.lastOrNull()?.let { w ->
            val fair = plan.kind == DataPlan.FAIR_USE
            val text = if (w >= 100) c.getString(if (fair) R.string.data_warn_all_fair else R.string.data_warn_all, DataRules.bytes(st.size), st.daysLeft)
                else c.getString(if (fair) R.string.data_warn_used_fair else R.string.data_warn_used, DataRules.bytes(st.used), DataRules.bytes(st.size), DataRules.bytes(st.left), st.daysLeft)
            notify(c, 0xDA70 + w, c.getString(R.string.data_warn_title, w), text, if (w >= 80) c.getString(R.string.data_android_data_settings) to settings(c, Settings.ACTION_DATA_USAGE_SETTINGS, 0xDA01) else null)
            p.edit().putStringSet(key, (given + due).map { it.toString() }.toSet()).apply()
        }
        if (p.getBoolean("warnBudget", false) && plan.limited && st.today > st.budget && p.getString("budgetDay", "") != today.toString()) {
            notify(c, 0xDA02, c.getString(R.string.data_budget_title), c.getString(R.string.data_budget_text, DataRules.bytes(st.today), DataRules.bytes(st.budget)))
            p.edit().putString("budgetDay", today.toString()).apply()
        }
        roamingPass(c, plan, months.flatMap { it.days }.firstOrNull { it.day == today })
    }

    // ---- roaming (the user, 25 Oct: "I roam with a day pass, €15 for 300 MB; it could notify to turn roaming off") ----

    /** Where the phone is now: the network's country, and whether it is roaming (no permission needed). */
    fun roaming(c: Context): String? {
        val tm = c.getSystemService(TelephonyManager::class.java) ?: return null
        return if (runCatching { tm.isNetworkRoaming }.getOrDefault(false)) tm.networkCountryIso.orEmpty().ifEmpty { "?" } else null
    }

    private fun dataRoamingOn(c: Context) = runCatching { c.getSystemService(TelephonyManager::class.java).isDataRoamingEnabled }.getOrDefault(true)

    private fun country(c: Context, iso: String) = java.util.Locale("", iso.uppercase()).getDisplayCountry(c.resources.configuration.locales[0]).ifEmpty { iso.uppercase() }

    /** A look at roaming (the network watch calls it on every change, the hourly look too): its notices, once each. */
    fun checkRoaming(c: Context) {
        val p = prefs(c)
        val now = roaming(c)
        val was = p.getString("roamingIn", null)
        if (now == was) return
        p.edit().putString("roamingIn", now).apply()
        val stamp = ZonedDateTime.now().toOffsetDateTime().withNano(0).toString()
        val plan = DataFiles.plan()
        val turnOff = c.getString(R.string.data_turn_off_roaming) to settings(c, Settings.ACTION_DATA_ROAMING_SETTINGS, 0xDA03)
        if (now != null) {
            note(c, DataEvent(stamp, "roaming", note = now))
            if (DataRules.inEu(now)) notify(c, 0xDA10, c.getString(R.string.data_roaming_in, country(c, now)), c.getString(if (plan?.euCounts == false) R.string.data_roaming_eu_free else R.string.data_roaming_eu_counted))
            else {
                val pass = plan?.takeIf { it.passPrice > 0 }?.let { c.getString(R.string.data_roaming_pass, "${it.currency}${"%.0f".format(it.passPrice)}", DataRules.bytes(it.passSize)) } ?: c.getString(R.string.data_roaming_extra)
                notify(c, 0xDA10, c.getString(R.string.data_roaming_outside, country(c, now)), pass + " " + c.getString(if (dataRoamingOn(c)) R.string.data_roaming_on else R.string.data_roaming_off), if (dataRoamingOn(c)) turnOff else null)
            }
        } else if (was != null) {
            note(c, DataEvent(stamp, "home"))
            notify(c, 0xDA10, c.getString(R.string.data_roaming_ended), c.getString(if (dataRoamingOn(c) && !DataRules.inEu(was)) R.string.data_back_home_on else R.string.data_back_home),
                if (dataRoamingOn(c) && !DataRules.inEu(was)) turnOff else null)
        }
    }

    /** Outside the EU with a day pass: the day's first MB (that day costs the pass), then 80 % and 100 % of it. */
    private fun roamingPass(c: Context, plan: DataPlan, today: DataDay?) {
        val where = roaming(c) ?: return
        if (DataRules.inEu(where) || plan.passPrice <= 0 || today == null || today.roaming <= 0) return
        val p = prefs(c); val key = "pass.${today.day}"
        val given = p.getStringSet(key, emptySet())!!
        val turnOff = c.getString(R.string.data_turn_off_roaming) to settings(c, Settings.ACTION_DATA_ROAMING_SETTINGS, 0xDA03)
        val price = "${plan.currency}${"%.0f".format(plan.passPrice)}"
        val steps = listOf("first" to 0L, "80" to plan.passSize * 8 / 10, "100" to plan.passSize).filter { (k, at) -> k !in given && today.roaming > at }
        steps.lastOrNull()?.let { (k, _) ->
            when (k) {
                "first" -> notify(c, 0xDA11, c.getString(R.string.data_pass_first_title, price), c.getString(R.string.data_pass_first_text, price, DataRules.bytes(plan.passSize)), turnOff)
                else -> notify(c, 0xDA11, c.getString(R.string.data_pass_title, k.toInt()), c.getString(if (k == "100") R.string.data_pass_full_text else R.string.data_pass_text,
                    DataRules.bytes(today.roaming), DataRules.bytes(plan.passSize)), turnOff)
            }
            p.edit().putStringSet(key, given + steps.map { it.first }).apply()
        }
    }

    // ---- a warning on time: Android calls back when mobile data passes the next threshold (while Tetra lives) ----

    private var callback: NetworkStatsManager.UsageCallback? = null

    fun watchUsage(c: Context) {
        val app = c.applicationContext
        if (!hasAccess(app) || callback != null) return
        val nsm = app.getSystemService(NetworkStatsManager::class.java)
        val cb = object : NetworkStatsManager.UsageCallback() {
            override fun onThresholdReached(networkType: Int, subscriberId: String?) {
                // registered again from here, so the next 50 MB count from now
                runCatching { nsm.unregisterUsageCallback(this) }; callback = null
                Thread { refresh(app); watchUsage(app) }.start()
            }
        }
        // shortcut: one fixed step (Android's least is 2 MiB); a look at every 50 MB is enough to warn within that of a threshold
        runCatching { nsm.registerUsageCallback(ConnectivityManager.TYPE_MOBILE, null, 50_000_000L, cb) }.onSuccess { callback = cb }
    }
}
