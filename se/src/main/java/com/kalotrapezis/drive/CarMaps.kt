package com.kalotrapezis.drive

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Tetra Auto SE's maps, from the phone (the user, 13 Nov: "a guide in the first run to download the map info from the phone … or
 * add or update the maps from the phone if there are more countries on the phone"). The phone lists what its Maps folder has
 * (/car/maps) and gives each file (GET /car/map) to the paired unit only, over Tetra Car's own pinned link (CarWire); the unit
 * takes what it does not have or has older — a country more, a newer map, its routing graph, the saved places — over the
 * hotspot's Wi-Fi, never the phone's mobile data being needed for it. Only on the car's own build (BuildConfig .se).
 */
internal object CarMaps {
    /** Tetra Auto SE: Tetra for Android built for a head unit (the car module). */
    val edition: Boolean get() = BuildConfig.APPLICATION_ID.endsWith(".se")

    private fun root() = MapsFiles.root(driveRoot())
    /**
     * Besides the maps, under these prefixes: the Browser's bookmarks (each device's file, BrowserSync; never its passwords or tabs)
     * and AI Assist's shared folder (its switches, its memory, the user's words).
     */
    private val MORE = mapOf("@browser/Bookmarks/" to { File(BrowserSync.root(), "Bookmarks") }, "@assistant/" to { AiGate.file(driveRoot()).parentFile!! }, CarShare.PREFIX to { CarShare.dir() })

    /** Where [path] is on this device: in the Maps folder or one of [MORE] (no way out of any). */
    private fun local(path: String): File? {
        val more = MORE.entries.firstOrNull { path.startsWith(it.key) }
        val (dir, rel) = if (more != null) more.value() to path.removePrefix(more.key) else root() to path
        val r = dir.canonicalFile
        return File(r, rel).canonicalFile.takeIf { it.path.startsWith(r.path + File.separator) }
    }

    /**
     * What both sides keep changing on their own, small: saved places, lists, trips and the bookmarks. Taken from the phone when
     * its copy is newer, without asking (a "new maps (0 MB)" offer at every opening, 13 Nov); the car's own parking stays its own.
     */
    internal fun personal(path: String) = path.startsWith("@") || path == MapsSearch.ADDED ||
        path.substringBefore('/') in setOf(MapsFiles.PLACES, MapsFiles.LISTS, MapsFiles.TRIPS)
    private fun parking(path: String) = path == "${MapsFiles.PLACES}/${MapsFiles.PARKING}.json"

    /** What of the phone's [theirs] (path → {size, modified}) this unit takes: a map it lacks or has different; a place or bookmark only newer. */
    internal fun wanted(theirs: JSONObject, local: (String) -> File?) = theirs.keys().asSequence().map { it to theirs.getJSONObject(it) }.filter { (path, o) ->
        val mine = local(path) ?: return@filter false
        val older = mine.lastModified() < o.optLong("modified") - 2000
        if (personal(path)) !parking(path) && (!mine.isFile || older) else !mine.isFile || mine.length() != o.optLong("size") || older
    }.sortedBy { it.first }.toList()

    // ---- the phone ----

    /** Every file under the phone's Maps folder and its bookmarks: path → {size, modified}. */
    fun list(): JSONObject = JSONObject().apply {
        for ((dir, prefix) in listOf(root() to "") + MORE.map { (prefix, dir) -> dir() to prefix })
            dir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".part") }
                .forEach { put(prefix + it.relativeTo(dir).invariantSeparatorsPath, JSONObject().put("size", it.length()).put("modified", it.lastModified())) }
    }

    /** A path the unit asked for, if it is a file in the Maps folder or the bookmarks'. */
    fun file(path: String): File? = local(path)?.takeIf { it.isFile }

    // ---- the unit ----

    sealed interface State {
        data object Idle : State
        data object Checking : State
        /** The phone has [files] this unit lacks or has older: [countries] among them, [bytes] in all; [first]: the unit has no map yet. */
        data class Offer(val files: List<Pair<String, JSONObject>>, val countries: List<String>, val bytes: Long, val first: Boolean) : State
        data class Moving(val done: Long, val total: Long, val name: String) : State
        data class Failed(val why: String) : State
    }
    val state = mutableStateOf<State>(State.Idle)
    /** Bumped when maps came: Maps draws them again. */
    val version = mutableIntStateOf(0)
    /** A file of Share's coming from the phone (the user, 15 Nov: "no progress bar, I don't know if it is receiving or not"): its name,
     *  the bytes so far and in all; [failed]: why it did not arrive. Null when nothing comes. */
    data class Receiving(val name: String, val got: Long, val size: Long, val failed: String? = null)
    val receiving = androidx.compose.runtime.mutableStateOf<Receiving?>(null)
    private val main by lazy { Handler(Looper.getMainLooper()) } // lazy: the JVM tests have no Looper
    /** "Not now" for this offer: asked again when the phone has something else. */
    private var declined: Set<String> = emptySet()

    fun haveMap() = MapsFiles.ids(root()).isNotEmpty()

    fun storageOk(c: Context) = storageAllowed(c)

    /** The car's own bookmarks file to the phone (the user, 17 Nov: "sync between bookmarks on the phone and the car"); the phone's come back in [check]. */
    fun sendBookmarks(c: Context, me: String) = runCatching {
        val f = File(BrowserSync.root(), "Bookmarks/$me.json").takeIf { it.isFile } ?: return@runCatching
        val r = CarWire.ask(c, JSONObject().put("bookmarks", JSONObject().put("name", f.name).put("text", f.readText())))
        if (r.has("error")) WriterLog.w("Car: bookmarks not sent to the phone (${r.optString("error")})")
    }.onFailure { WriterLog.w("Car: bookmarks not sent to the phone (${it.message})") }

    /** Asks the phone what maps it has (on a thread of its own); an offer when it has more or newer. */
    fun check(c: Context) {
        if (state.value is State.Moving || state.value is State.Checking) return
        state.value = State.Checking
        Thread {
            // AI Assist as on the phone: its settings and keys (the user, 13 Nov: "the Mistral key is a pain to write on a touchscreen")
            runCatching { CarAssistant.take(c.applicationContext, CarWire.assistant(c.applicationContext)) }.onFailure { WriterLog.w("Car: AI Assist's settings not taken (${it.message})") }
            runCatching { // the Browser's logins as on the phone: the phone's wins for the same site and user
                val mine = BrowserSecrets.vault(c.applicationContext)
                val both = mine.with(CarWire.vault(c.applicationContext))
                if (both != mine) { BrowserSecrets.save(c.applicationContext, both); WriterLog.i("Car: the Browser's logins from the phone, ${both.logins.size} now") }
            }.onFailure { WriterLog.w("Car: the Browser's logins not taken (${it.message})") }
            runCatching { CarWire.log(c.applicationContext) }.onFailure { WriterLog.w("Car: the log not sent to the phone (${it.message})") }
            val me = BrowserSync.device(c)
            sendBookmarks(c.applicationContext, me)
            val next: State = runCatching {
                val theirs = CarWire.maps(c.applicationContext)
                val all = wanted(theirs, ::local).filter { it.first != "@browser/Bookmarks/$me.json" } // the car's own: written here only
                val (mine, want) = all.partition { personal(it.first) }
                if (mine.isNotEmpty()) { // places, bookmarks, AI Assist's and Share's: just taken, never offered
                    var now: Receiving? = null
                    runCatching { fetch(c, mine, each = { path, got, size -> if (path.startsWith(CarShare.PREFIX)) Receiving(path.removePrefix(CarShare.PREFIX), got, size).let { r -> now = r; main.post { receiving.value = r } } }) { } }
                        .onSuccess { WriterLog.i("Car maps: ${mine.size} places, bookmarks or shared files from the phone"); main.post { receiving.value = null } }
                        .onFailure { e -> WriterLog.w("Car maps: places, bookmarks or shared files not taken (${e.message})"); now?.let { r -> main.post { receiving.value = r.copy(failed = e.message.orEmpty()) } } }
                    main.post { version.intValue++ }
                }
                if (want.isEmpty() || want.map { it.first }.toSet() == declined) State.Idle
                else State.Offer(want, want.map { it.first }.filter { it.endsWith(".pmtiles") }.map { it.removeSuffix(".pmtiles").replaceFirstChar(Char::uppercase) },
                    want.sumOf { it.second.optLong("size") }, !haveMap())
            }.getOrElse { State.Failed(it.message ?: "") }
            main.post { state.value = next }
        }.start()
    }

    /** [files] from the phone, one after another, each kept only once it has all arrived; [moved]: the bytes so far, now and then. */
    private fun fetch(c: Context, files: List<Pair<String, JSONObject>>, each: (String, Long, Long) -> Unit = { _, _, _ -> }, moved: (Long) -> Unit) {
        var before = 0L
        for ((path, info) in files) {
            val f = checkNotNull(local(path)) { "$path is not a map." }.also { it.parentFile?.mkdirs() }
            val part = File(f.parentFile, ".${f.name}.part")
            val size = info.optLong("size")
            // three tries, each from the start: a dropped Wi-Fi halfway through a 260 MB app left it "incomplete" (the user, 15 Nov)
            for (attempt in 1..3) {
                var last = 0L; var told = 0L
                each(path, 0, size)
                val failed = runCatching {
                    part.outputStream().buffered(1 shl 16).use { out ->
                        CarWire.download(c.applicationContext, path, out) { got ->
                            if (got - last > (4L shl 20)) { last = got; moved(before + got) }
                            if (got - told > (1L shl 20)) { told = got; each(path, got, size) } // each MB: a bar that moves
                        }
                    }
                }.exceptionOrNull()
                each(path, part.length(), size)
                if (failed == null && part.length() == size) break
                WriterLog.w("Car maps: $path stopped at ${part.length()} of $size bytes, try $attempt of 3 (${failed?.message ?: "short"})")
                if (attempt == 3) { part.delete(); throw failed ?: IllegalStateException("$path came incomplete.") }
            }
            f.delete(); check(part.renameTo(f)) { "$path not kept." }
            f.setLastModified(info.optLong("modified"))
            before += info.optLong("size")
        }
        // Share's: off the phone's To car now that the car has them, and said on the car's screen
        val shared = files.map { it.first }.filter { it.startsWith(CarShare.PREFIX) }.map { it.removePrefix(CarShare.PREFIX) }
        if (shared.isNotEmpty()) {
            runCatching { CarWire.ask(c.applicationContext, JSONObject().put("taken", JSONArray(shared))) }
            main.post { Toast.makeText(c.applicationContext, c.getString(R.string.car_share_arrived, shared.joinToString(", ")), Toast.LENGTH_LONG).show() }
        }
    }

    fun decline(o: State.Offer) { declined = o.files.map { it.first }.filterNot(::personal).toSet(); state.value = State.Idle }

    /** The offered files from the phone, one after another, each kept only once it has all arrived. */
    fun download(c: Context, o: State.Offer) {
        val root = root()
        val free = runCatching { StatFs(root.apply { mkdirs() }.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (free < o.bytes + (200L shl 20)) { state.value = State.Failed(c.getString(R.string.car_maps_no_space, o.bytes shr 20, free shr 20)); return }
        state.value = State.Moving(0, o.bytes, "")
        WriterLog.i("Car maps: ${o.files.size} files from the phone, ${o.bytes shr 20} MB")
        Thread {
            val failed = runCatching { fetch(c, o.files) { at -> main.post { state.value = State.Moving(at, o.bytes, "") } } }.exceptionOrNull()
            WriterLog.i("Car maps: ${if (failed == null) "all came" else "stopped (${failed.message})"}")
            main.post {
                version.intValue++
                state.value = if (failed == null) State.Idle else State.Failed(failed.message ?: "")
            }
        }.start()
    }
}

/**
 * AI Assist's settings and each service's key, from the phone to the paired unit over Tetra Car's pinned link (the user, 13 Nov:
 * "the settings about the assistant, everything, to sync over with the first transfer"). Each key is read out of the phone's
 * keystore and kept in the unit's own (AssistantKey); never written to a file. The phone's choices win each time the car starts.
 */
internal object CarAssistant {
    private fun prefs(c: Context) = c.getSharedPreferences("assistant", Context.MODE_PRIVATE)
    private fun isKey(name: String) = name == "key" || name.startsWith("key_") // AssistantKey's, encrypted for this device only

    /** On the phone: every setting, its type with it, and the keys in the clear (for the link only). */
    fun give(c: Context): JSONObject {
        val settings = JSONObject()
        prefs(c).all.forEach { (k, v) ->
            if (isKey(k) || v == null) return@forEach
            val (t, value) = when (v) { is Boolean -> "b" to v; is Int -> "i" to v; is Long -> "l" to v; is Float -> "f" to v.toDouble()
                is Set<*> -> "set" to org.json.JSONArray(v.map { it.toString() }); else -> "s" to v.toString() }
            settings.put(k, JSONObject().put("t", t).put("v", value))
        }
        val keys = JSONObject().apply { AiProviders.ALL.forEach { p -> AssistantKey.get(c, p)?.let { put(p.id, it) } } }
        return JSONObject().put("settings", settings).put("keys", keys)
    }

    /** On the unit: the phone's [o] over this unit's; what the phone has not stays. */
    fun take(c: Context, o: JSONObject) {
        val settings = o.optJSONObject("settings") ?: return
        val e = prefs(c).edit()
        settings.keys().forEach { k ->
            if (isKey(k)) return@forEach
            val s = settings.getJSONObject(k)
            when (s.optString("t")) {
                "b" -> e.putBoolean(k, s.getBoolean("v")); "i" -> e.putInt(k, s.getInt("v")); "l" -> e.putLong(k, s.getLong("v"))
                "f" -> e.putFloat(k, s.getDouble("v").toFloat())
                "set" -> e.putStringSet(k, s.getJSONArray("v").let { a -> (0 until a.length()).map(a::getString).toSet() })
                else -> e.putString(k, s.optString("v"))
            }
        }
        e.apply()
        val keys = o.optJSONObject("keys")
        AiProviders.ALL.forEach { p -> keys?.optString(p.id)?.takeIf { it.isNotBlank() && it != AssistantKey.get(c, p) }?.let { AssistantKey.set(c, it, p) } }
        WriterLog.i("Car: AI Assist's settings from the phone, ${settings.length()} settings, ${keys?.length() ?: 0} keys")
    }
}

/**
 * The guide over Tetra Car's map, on the car's build only: with no map yet, the first run's three steps — Tetra's storage, the
 * phone paired, the maps from it; after that a line when the phone has more countries or newer maps.
 */
@Composable
internal fun CarMapsGuide(modifier: Modifier = Modifier, pair: () -> Unit) {
    if (!CarMaps.edition) return
    val context = LocalContext.current
    val state by CarMaps.state
    val reached by CarWire.reached
    var storage by remember { mutableStateOf(CarMaps.storageOk(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { storage = CarMaps.storageOk(context) }
    val paired = CarWire.link(context) != null
    LaunchedEffect(reached, storage) { if (reached && storage) CarMaps.check(context) }
    val first = !CarMaps.haveMap()
    val s = state
    if (!first && (s is CarMaps.State.Idle || s is CarMaps.State.Checking)) return // nothing to say over a map that is there
    Box(modifier.fillMaxSize().then(if (first) Modifier.background(MaterialTheme.colorScheme.background) else Modifier).padding(16.dp), contentAlignment = if (first) Alignment.Center else Alignment.TopCenter) {
        Surface(Modifier.widthIn(max = 620.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 8.dp) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { // scrolls when a big size leaves too little height
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(painterResource(R.drawable.ic_map), null, Modifier.size(24.dp), tint = MAPS_COLOR)
                    Text(stringResource(if (first) R.string.car_maps_title else R.string.car_maps_update_title), style = MaterialTheme.typography.titleLarge)
                }
                if (first) {
                    Text(stringResource(R.string.car_maps_intro), style = MaterialTheme.typography.bodyMedium)
                    Step(1, stringResource(R.string.car_maps_step_storage), storage) {
                        if (Build.VERSION.SDK_INT >= 30) context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
                        else ask.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                    Step(2, stringResource(R.string.car_maps_step_pair), paired && reached, pair)
                }
                when (s) {
                    is CarMaps.State.Offer -> {
                        Text(stringResource(R.string.car_maps_offer, s.countries.ifEmpty { listOf(stringResource(R.string.car_maps_updates)) }.joinToString(", "), s.bytes shr 20),
                            style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button({ CarMaps.download(context, s) }, Modifier.height(36.dp), contentPadding = SMALL) { Text(stringResource(if (s.first) R.string.car_maps_download else R.string.car_maps_update)) }
                            if (!s.first) TextButton({ CarMaps.decline(s) }, Modifier.height(36.dp), contentPadding = SMALL) { Text(stringResource(R.string.photos_not_now)) }
                        }
                    }
                    is CarMaps.State.Moving -> {
                        Text(stringResource(R.string.car_maps_moving, s.done shr 20, s.total shr 20), style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f }, Modifier.fillMaxWidth())
                    }
                    is CarMaps.State.Failed -> {
                        Text(stringResource(R.string.car_maps_failed, s.why), color = MaterialTheme.colorScheme.error)
                        TextButton({ CarMaps.check(context) }, Modifier.height(36.dp), contentPadding = SMALL) { Text(stringResource(R.string.common_retry)) }
                    }
                    CarMaps.State.Checking -> Text(stringResource(R.string.car_maps_checking))
                    CarMaps.State.Idle -> if (first && storage && paired && reached) Text(stringResource(R.string.car_maps_phone_none))
                }
            }
        }
    }
}

/** One step of the guide: its number (a tick when done) and what to do, a tap to do it. */
@Composable
private fun Step(n: Int, text: String, done: Boolean, act: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(28.dp).background(if (done) MAPS_COLOR else MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Text(if (done) "✓" else "$n", style = MaterialTheme.typography.titleSmall, color = androidx.compose.ui.graphics.Color.White)
        }
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (!done) Button(act, Modifier.height(36.dp), contentPadding = SMALL) { Text(stringResource(R.string.car_maps_do)) }
    }
}

/** The guide's buttons: small (at the car's 150 % Material's own were too big, 13 Nov). */
private val SMALL = PaddingValues(horizontal = 14.dp, vertical = 0.dp)

/** Whether Tetra may use its folder: all files access (Android 11 and later), the storage permission on Android 10 (Tetra Auto SE's head units). */
internal fun storageAllowed(c: Context) = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

/**
 * Tetra Auto SE's size on the car's screen (the user, 13 Nov: "the arrow … and the UI elements big enough … scaling settings in
 * 25% increments"): a head unit says 160 dpi, so Tetra drew at a phone's size there — the arrow a dot, the names tiny. Every
 * screen of Tetra is drawn this much bigger (AppLocale.wrap sets the density), the map's names and arrow with it, as Android
 * itself does on a denser screen. 150 % until chosen; 1 on the phone, which is never scaled.
 */
internal object CarSize {
    // 10 % steps (the user, 13 Nov: "I don't want to go from too small to too big"), 120 % at first: 125 % looked right in the car
    val STEPS = (10..20).map { it / 10f }
    fun of(c: Context): Float = if (!CarMaps.edition) 1f else c.getSharedPreferences("car", Context.MODE_PRIVATE).getFloat("screen_scale", 1.2f).let { v -> STEPS.minBy { kotlin.math.abs(it - v) } }
    fun set(c: Context, v: Float) = c.getSharedPreferences("car", Context.MODE_PRIVATE).edit().putFloat("screen_scale", v).commit()
}
