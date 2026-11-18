package com.kalotrapezis.drive

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/*
 * Reminders by place (the user, 1 Nov: «όταν πάω σούπερ μάρκετ δείξε μου τα ψώνια»): a kind of place from the map's search
 * list (every supermarket) or one spot, and the words of a checklist's title in Notes. On arrival the newest such checklist
 * with something left to tick rings as a Calendar reminder does (the island, ✕ Done, Snooze), and its tap opens the note.
 * Android's own proximity alerts watch the nearest few spots (Tetra has no Google services); a coarse location every
 * kilometre or so picks the nearest again. Off until the user turns it on (Settings › Calendar) and allows the location
 * all the time. The list is this phone's only: .System/Maps/Data, which does not sync.
 */
internal data class PlaceReminder(val id: String, val kind: String, val name: String, val lat: Double, val lng: Double, val note: String) {
    fun json(): JSONObject = JSONObject().put("id", id).put("kind", kind).put("name", name).put("lat", lat).put("lng", lng).put("note", note)
    companion object {
        fun parse(o: JSONObject) = PlaceReminder(o.getString("id"), o.optString("kind"), o.optString("name"), o.optDouble("lat", 0.0), o.optDouble("lng", 0.0), o.optString("note"))
    }
}

/** One spot watched: which reminder, where, by what name. */
internal data class Watched(val reminder: PlaceReminder, val name: String, val lat: Double, val lng: Double)

internal object LocationReminders {
    /** A note naming this, then a dose's time in ms: Pills' dose put off until here, not a checklist. */
    const val PILLS = "pills:"
    const val SCREEN = "settings-calendar" // AI Assist's tap: Settings › Calendar (MainActivity)
    private const val ACTION_NEAR = "com.kalotrapezis.drive.PLACE_NEAR"
    private const val ACTION_MOVED = "com.kalotrapezis.drive.PLACE_MOVED"
    private const val RADIUS = 100f // metres: the shop and its car park, not the next street
    private const val WATCHED = 20 // proximity alerts at once, the nearest
    private const val WITHIN = 15_000.0 // metres: shops further than this wait for the next coarse location
    private const val AGAIN_MS = 2 * 3600_000L // one ring per reminder in two hours: GPS jitter at the door

    fun file(tetra: File) = File(TetraSystem.dir(tetra, "Maps", "Data"), "location-reminders.json")
    fun list(tetra: File): List<PlaceReminder> = runCatching {
        JSONArray(file(tetra).readText()).let { a -> (0 until a.length()).map { PlaceReminder.parse(a.getJSONObject(it)) } }
    }.getOrDefault(emptyList())
    fun save(tetra: File, all: List<PlaceReminder>) = file(tetra).apply { parentFile?.mkdirs() }.writeText(JSONArray(all.map { it.json() }).toString(1))
    fun add(tetra: File, r: PlaceReminder) = save(tetra, list(tetra) + r)
    fun remove(c: Context, tetra: File, id: String) { save(tetra, list(tetra).filter { it.id != id }); planSoon(c) }

    private fun prefs(c: Context) = c.getSharedPreferences("location_reminders", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("on", false)
    fun setEnabled(c: Context, on: Boolean) { prefs(c).edit().putBoolean("on", on).apply(); planSoon(c) }
    fun located(c: Context) = c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun always(c: Context) = located(c) && c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    /** On and allowed: a reminder set now rings. */
    fun ready(c: Context) = enabled(c) && always(c)

    /** The spots of [all] to watch from [lat],[lng], nearest first: each kind's nearest shops, and every single spot. */
    fun targets(all: List<PlaceReminder>, pois: List<Poi>, lat: Double?, lng: Double?): List<Watched> {
        val spots = all.filter { it.kind.isEmpty() }.map { Watched(it, it.name, it.lat, it.lng) }
        val kinds = if (lat == null || lng == null) emptyList() else all.filter { it.kind.isNotEmpty() }.flatMap { r ->
            pois.asSequence().filter { it.kind == r.kind && Math.abs(it.lat - lat) < 0.15 && Math.abs(it.lng - lng) < 0.2 }
                .map { Watched(r, it.name.ifEmpty { it.nameEn }, it.lat, it.lng) }.toList()
        }
        fun far(w: Watched) = if (lat == null || lng == null) 0.0 else metresBetween(lat, lng, w.lat, w.lng)
        return (spots + kinds.filter { far(it) <= WITHIN }).sortedBy(::far).take(WATCHED)
    }

    /** The checklist to show: the newest in use whose title has [words] (any, when none), with something left to tick. */
    fun note(notes: List<Note>, words: String): Note? {
        val w = MapsSearch.fold(words).split(' ').filter { it.isNotBlank() }
        return notes.filter { n -> n.checklist && n.trashedAt == null && n.archivedAt == null && n.items.any { !it.checked && it.text.isNotBlank() } &&
            MapsSearch.fold(n.title).let { t -> w.all { it in t } } }.maxByOrNull { it.updatedAt }
    }

    /** The checklist [noteId] still wants ringing: there, not in the trash or archived, something left to tick. */
    fun wanted(noteId: String): Boolean = runCatching {
        val store = NotesStore(TetraSystem.dir(driveRoot(), "Notes", "Shared"))
        store.whole(listOf(noteId)).firstOrNull()?.let { n -> n.trashedAt == null && n.archivedAt == null && n.items.any { !it.checked && it.text.isNotBlank() } } ?: false
    }.getOrDefault(true) // unreadable now: left as it is

    /** A shown reminder answered (✕ Done) or gone: no longer followed. */
    fun forget(c: Context, notification: Int) = prefs(c).let { p -> p.edit().putStringSet("shown", p.getStringSet("shown", emptySet())!!.filter { !it.startsWith("$notification|") }.toSet()).apply() }

    /** A place reminder still on the screen whose checklist went (deleted, trashed, archived, all ticked; here or on another device):
     *  taken away, its snooze too (the user, 15 Nov: "when I delete the note (Ψώνια) the notification persists"). */
    fun tidySoon(c: Context) { val app = c.applicationContext; Thread { runCatching { tidy(app) }.onFailure { WriterLog.e("Place reminders: tidy failed", it) } }.start() }
    @Synchronized fun tidy(c: Context) {
        val p = prefs(c); val shown = p.getStringSet("shown", emptySet())!!
        if (shown.isEmpty()) return
        val keep = shown.filter { e ->
            val id = e.substringBefore('|').toIntOrNull() ?: return@filter false
            wanted(e.substringAfter('|')).also { if (!it) { CalendarAlarms.dismiss(c, id); WriterLog.i("Place reminders: its list is gone, the reminder taken away") } }
        }
        p.edit().putStringSet("shown", keep.toSet()).apply()
    }

    fun planSoon(c: Context, here: Location? = null) { val app = c.applicationContext; Thread { runCatching { plan(app, here) }.onFailure { WriterLog.e("Place reminders: plan failed", it) } }.start() }

    private fun alert(c: Context, i: Int, w: Watched? = null) = PendingIntent.getBroadcast(c, 9100 + i,
        Intent(c, LocationReminderReceiver::class.java).setAction(ACTION_NEAR).apply { if (w != null) putExtra("id", w.reminder.id).putExtra("name", w.name) },
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) // mutable: Android adds whether it is entering

    /** The nearest spots watched again from [here] (else the last known location); everything stopped when off. */
    @Synchronized fun plan(c: Context, here: Location? = null) {
        val lm = c.getSystemService(LocationManager::class.java) ?: return
        val p = prefs(c)
        val moved = PendingIntent.getBroadcast(c, 9099, Intent(c, LocationReminderReceiver::class.java).setAction(ACTION_MOVED), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        repeat(p.getInt("watched", 0)) { lm.removeProximityAlert(alert(c, it)) }
        lm.removeUpdates(moved)
        p.edit().putInt("watched", 0).apply()
        val tetra = driveRoot()
        val all = list(tetra)
        if (!ready(c) || all.isEmpty()) return
        @Suppress("MissingPermission") // ready() checked it
        val at = here ?: lm.allProviders.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        val pois = if (all.any { it.kind.isNotEmpty() }) runCatching { MapsSearch.load(MapsFiles.root(tetra)) }.getOrDefault(emptyList()) else emptyList()
        val watched = targets(all, pois, at?.latitude, at?.longitude)
        @Suppress("MissingPermission")
        watched.forEachIndexed { i, w -> lm.addProximityAlert(w.lat, w.lng, RADIUS, -1, alert(c, i, w)) }
        p.edit().putInt("watched", watched.size).apply()
        // a coarse fix each kilometre, at most every 10 minutes: the network's, not the GPS, so the battery hardly notices
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER).first { it in lm.allProviders }
        @Suppress("MissingPermission")
        if (all.any { it.kind.isNotEmpty() }) lm.requestLocationUpdates(provider, 10 * 60_000L, 1000f, moved)
        WriterLog.i("Place reminders: watching ${watched.size} spots")
    }

    fun handle(c: Context, i: Intent) {
        if (i.action == ACTION_MOVED) { @Suppress("DEPRECATION") plan(c, i.getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED) as? Location); return }
        if (i.action != ACTION_NEAR || !i.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false) || !enabled(c)) return
        val id = i.getStringExtra("id") ?: return
        val r = list(driveRoot()).firstOrNull { it.id == id } ?: return
        // a pill put off "until I get home" (Pills.AT_PLACE): rung once, then the spot is no longer watched
        if (r.note.startsWith(PILLS)) {
            save(driveRoot(), list(driveRoot()).filter { it.id != id }); planSoon(c)
            r.note.removePrefix(PILLS).toLongOrNull()?.let { PillsAlarms.atPlace(c, java.time.Instant.ofEpochMilli(it)) }
            return
        }
        val p = prefs(c)
        if (System.currentTimeMillis() - p.getLong("rang_$id", 0) < AGAIN_MS) return
        val store = NotesStore(TetraSystem.dir(driveRoot(), "Notes", "Shared"))
        val n = note(store.whole(store.list().map { it.id }), r.note) ?: return // nothing left to buy: no ring
        p.edit().putLong("rang_$id", System.currentTimeMillis()).apply()
        val left = n.items.filter { !it.checked && it.text.isNotBlank() }.sortedBy { it.order }
        val place = i.getStringExtra("name").orEmpty().ifEmpty { r.name }
        val shown = "place|$id".hashCode()
        prefs(c).edit().putStringSet("shown", prefs(c).getStringSet("shown", emptySet())!!.filter { !it.startsWith("$shown|") }.toSet() + "$shown|${n.id}").apply()
        CalendarAlarms.post(c, shown, n.title.ifBlank { c.getString(R.string.place_reminder_title) },
            (if (place.isNotEmpty()) "$place · " else "") + left.joinToString(", ") { it.text.trim() }, CalendarFiles.PALETTE[0],
            time = left.size.toString(), open = "note:${n.id}")
    }
}

/** A place reached (a proximity alert) or a coarse new location (the nearest shops picked again). */
class LocationReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val done = goAsync()
        val app = context.applicationContext
        WriterLog.init(app)
        Thread { runCatching { LocationReminders.handle(app, intent) }.onFailure { WriterLog.e("Place reminders: ${intent.action} failed", it) }; done.finish() }.start()
    }
}
