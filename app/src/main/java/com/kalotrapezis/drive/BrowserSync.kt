package com.kalotrapezis.drive

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/*
 * The Browser's bookmarks and open tabs between the user's devices (the user, 3 Nov: "bookmarks and tabs"). Tetra's own
 * sync carries the files, as the Timer's: Tetra/.System/Browser/Shared, synced as "Browser/…" (TetraSystem.SHARED,
 * the computer's system.SHARED), part "browser" (DriveScope) so a change goes at once.
 *
 *   Bookmarks/<device>.json   every bookmark and folder this device knows, each with the time it last changed, and the
 *                             deleted ones as tombstones for 30 days. Only that device writes it (Timer's rule), so sync
 *                             never has two versions of one file; every device reads them all and merges.
 *   Tabs/<device>.json        that device's open tabs (never Private's); the others show them, read only.
 *
 * Merge: per bookmark (by its address) and per folder (by an id, so a folder renamed on one device while a page in it is
 * moved on another keeps both), the newer change wins; at the same moment the living one does. A page left in a folder
 * deleted elsewhere keeps that folder. Passwords never come here (BrowserSecrets; BrowserPasswordSync when chosen).
 */

internal data class MarkRec(val url: String, val title: String, val folder: String, val pinned: Boolean, val at: Long, val gone: Boolean = false)
internal data class FolderRec(val id: String, val name: String, val parent: String, val at: Long, val gone: Boolean = false)

internal data class BookmarkSet(val marks: Map<String, MarkRec> = emptyMap(), val folders: Map<String, FolderRec> = emptyMap()) {

    /** Every folder's path ("Imported/News"), the deleted ones too (a page may still be in one); a loop is cut at the top. */
    fun paths(): Map<String, String> {
        val out = HashMap<String, String>()
        fun path(id: String, seen: Set<String>): String = out[id] ?: run {
            val f = folders[id] ?: return ""
            val up = if (f.parent.isEmpty() || f.parent in seen || f.parent !in folders) "" else path(f.parent, seen + id)
            BookmarkLibrary.join(up, f.name)
        }
        folders.keys.forEach { out[it] = path(it, setOf(it)) }
        return out
    }

    /** The two sets as one: per page and per folder, the newer change; at the same moment the living one, then a fixed order. */
    fun merge(o: BookmarkSet): BookmarkSet = BookmarkSet(pick(marks, o.marks) { it.at to it.gone }, pick(folders, o.folders) { it.at to it.gone })

    /** Tombstones older than [days] go (a device away longer may bring a page back: nothing is lost that way). */
    fun pruned(now: Long, days: Int = 30) = (now - days * 86_400_000L).let { old ->
        BookmarkSet(marks.filterValues { !it.gone || it.at >= old }, folders.filterValues { !it.gone || it.at >= old })
    }

    /** The library to show: [prev]'s order kept, what is new after it (oldest change first). */
    fun library(prev: BookmarkLibrary = BookmarkLibrary(emptyList())): BookmarkLibrary {
        val p = paths()
        val order = prev.marks.withIndex().associate { it.value.url to it.index }
        val marks = this.marks.values.filter { !it.gone }.sortedWith(compareBy({ order[it.url] ?: Int.MAX_VALUE }, { it.at }, { it.url }))
            .map { Bookmark(it.url, it.title, it.pinned, p[it.folder].orEmpty()) }
        val forder = prev.folders.withIndex().associate { it.value to it.index }
        val extra = folders.values.filter { !it.gone }.map { p.getValue(it.id) }.filter { it.isNotEmpty() }.distinct().sortedWith(compareBy({ forder[it] ?: Int.MAX_VALUE }, { it }))
        return BookmarkLibrary(marks, extra)
    }

    /**
     * What [lib] changed since this set, stamped [now]: folders renamed or moved ([BookmarkLibrary.moves]) keep their id;
     * new ones are made, gone ones and gone pages become tombstones; a page changed in any way is stamped whole.
     * A first sync (an empty set) records everything the device has.
     */
    fun record(lib: BookmarkLibrary, now: Long): BookmarkSet {
        val folders = this.folders.toMutableMap()
        var p = BookmarkSet(emptyMap(), folders).paths()
        fun live(path: String) = folders.values.filter { !it.gone && p[it.id] == path }.minByOrNull { it.id }?.id
        fun ensure(path: String): String {
            if (path.isEmpty()) return ""
            live(path)?.let { return it }
            val parent = ensure(BookmarkLibrary.parentOf(path))
            val id = folderId(path).takeIf { folders[it]?.gone != false } ?: java.util.UUID.randomUUID().toString().take(12)
            folders[id] = FolderRec(id, BookmarkLibrary.nameOf(path), parent, now)
            p = BookmarkSet(emptyMap(), folders).paths()
            return id
        }
        for ((from, to) in lib.moves) {
            val id = live(from) ?: continue
            val parent = ensure(BookmarkLibrary.parentOf(to))
            folders[id] = folders.getValue(id).copy(name = BookmarkLibrary.nameOf(to), parent = parent, at = now)
            p = BookmarkSet(emptyMap(), folders).paths()
        }
        lib.folders.forEach { ensure(it) }
        val keep = lib.folders.toSet()
        folders.values.filter { !it.gone && p[it.id] !in keep }.forEach { folders[it.id] = it.copy(gone = true, at = now) }

        val marks = this.marks.toMutableMap()
        for (b in lib.marks) {
            val r = marks[b.url]
            if (r == null || r.gone || r.title != b.title || r.pinned != b.pinned || p[r.folder].orEmpty() != b.folder)
                marks[b.url] = MarkRec(b.url, b.title, ensure(b.folder), b.pinned, now)
        }
        val urls = lib.marks.map { it.url }.toSet()
        marks.values.filter { !it.gone && it.url !in urls }.forEach { marks[it.url] = it.copy(gone = true, at = now) }
        return BookmarkSet(marks, folders)
    }

    fun json(): String = JSONObject()
        .put("marks", JSONArray(marks.values.sortedBy { it.url }.map { m -> JSONObject().put("url", m.url).put("title", m.title).put("folder", m.folder)
            .put("pinned", m.pinned).put("at", m.at).apply { if (m.gone) put("gone", true) } }))
        .put("folders", JSONArray(folders.values.sortedBy { it.id }.map { f -> JSONObject().put("id", f.id).put("name", f.name).put("parent", f.parent)
            .put("at", f.at).apply { if (f.gone) put("gone", true) } }))
        .toString(1) + "\n"

    companion object {
        /** A folder's first id: from its path, so two devices that each made "Radios" before they met make one folder. */
        fun folderId(path: String) = MessageDigest.getInstance("SHA-1").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)

        /** A file's set; anything that is not one (half a write, a stray file) is null, never an error. */
        fun parse(text: String): BookmarkSet? = runCatching {
            val o = JSONObject(text)
            val m = o.getJSONArray("marks"); val f = o.getJSONArray("folders")
            BookmarkSet((0 until m.length()).map { m.getJSONObject(it) }.filter { it.optString("url").isNotEmpty() }.associate { j ->
                j.getString("url") to MarkRec(j.getString("url"), j.optString("title"), j.optString("folder"), j.optBoolean("pinned"), j.optLong("at"), j.optBoolean("gone")) },
                (0 until f.length()).map { f.getJSONObject(it) }.filter { it.optString("id").isNotEmpty() }.associate { j ->
                j.getString("id") to FolderRec(j.getString("id"), j.optString("name"), j.optString("parent"), j.optLong("at"), j.optBoolean("gone")) })
        }.getOrNull()

        private fun <T : Any> pick(a: Map<String, T>, b: Map<String, T>, key: (T) -> Pair<Long, Boolean>): Map<String, T> = (a.keys + b.keys).associateWith { k ->
            listOfNotNull(a[k], b[k]).maxWith(compareBy<T>({ key(it).first }, { !key(it).second }, { it.toString() }))
        }
    }
}

/** A device's open tabs as the others show them: its name, when written, and each tab (its group by name). */
internal data class RemoteTab(val url: String, val title: String, val pinned: Boolean = false, val group: String = "")
internal data class DeviceTabs(val id: String, val name: String, val at: Long, val tabs: List<RemoteTab>)

internal object BrowserSync {
    const val FOLDER = "Browser"
    const val BOOKMARKS = "sync_bookmarks"; const val TABS = "sync_tabs"; const val PASSWORDS = "sync_passwords"
    const val TWO_FACTOR = "sync_2fa" // Passwords' 2FA codes (Authenticator.kt), with the passwords' passphrase; off until chosen

    fun root(tetra: File) = File(tetra, TetraSystem.onDisk(FOLDER))
    fun root() = root(TetraFolder.root(android.os.Environment.getExternalStorageDirectory()))
    fun on(c: Context, what: String) = c.getSharedPreferences("browser", Context.MODE_PRIVATE).getBoolean(what, what != PASSWORDS && what != TWO_FACTOR)

    /**
     * This device's file name: its model and a piece of Android's id for this app on it, so two of the same model (or two
     * emulators) never share a file. The same after a reinstall of the same build.
     */
    fun device(c: Context): String = thisDevice() + "-" +
        (runCatching { android.provider.Settings.Secure.getString(c.contentResolver, android.provider.Settings.Secure.ANDROID_ID) }.getOrNull() ?: "0").take(6)

    private fun files(dir: File) = dir.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") && it.name.endsWith(".json") }

    /** [text] into [to], written beside then renamed over: sync never reads half a file. */
    fun save(to: File, text: String) {
        to.parentFile?.mkdirs()
        val part = File(to.parentFile, ".${to.name}.part")
        part.writeText(text)
        check(part.renameTo(to)) { "Could not save ${to.name}" }
        to.setLastModified(System.currentTimeMillis()) // sync's hash cache goes by size and date (TimerFiles.save)
    }

    /**
     * The bookmarks of every device with [lib], this device's own (its prefs): what changed here is recorded, the others'
     * files merged in, the result written as this device's file when it differs. Answers the library to show.
     * [base] is the set [lib] was last shown from, kept in the app's own storage: what changed here is what differs from it.
     * Not the shared file, which sync could bring back older (then every page would look changed now, and win).
     */
    fun bookmarks(root: File, me: String, lib: BookmarkLibrary, base: File, now: Long = System.currentTimeMillis()): BookmarkLibrary {
        val dir = File(root, "Bookmarks")
        val mine = File(dir, "$me.json")
        val shared = mine.takeIf { it.isFile }?.let { BookmarkSet.parse(it.readText()) }
        val own = base.takeIf { it.isFile }?.let { BookmarkSet.parse(it.readText()) } ?: shared // no base yet: the shared file is the best there is
        val recorded = (own ?: BookmarkSet()).record(lib, now)
        val others = files(dir).filter { it.name != mine.name }.mapNotNull { f -> BookmarkSet.parse(f.readText()).also { if (it == null) WriterLog.w("Browser sync: ${f.name} unreadable, left") } }
        val all = others.fold(recorded) { a, b -> a.merge(b) }.pruned(now)
        val out = all.library(lib)
        val changedHere = recorded != (own ?: BookmarkSet())
        val came = out.marks.toSet() != lib.marks.toSet() || out.folders.toSet() != lib.folders.toSet()
        if (all != shared) save(mine, all.json())
        if (all != own) save(base, all.json())
        if (changedHere || came || own == null) {
            val was = own ?: BookmarkSet()
            val here = was.marks.keys.union(recorded.marks.keys).filter { was.marks[it] != recorded.marks[it] }
            val there = recorded.marks.keys.union(all.marks.keys).filter { recorded.marks[it] != all.marks[it] }
            // one page changed here and there since this device last wrote (not an older copy of it): the newer change won
            val conflicts = here.count { k -> others.any { o -> o.marks[k].let { it != null && it.at > (was.marks[k]?.at ?: 0) && it != recorded.marks[k] } } }
            WriterLog.i("Browser sync: bookmarks${if (own == null) " (first sync)" else ""}: ${out.marks.size} pages, ${out.extra.size} folders; ${others.size} other devices; " +
                "${here.size} pages changed here, ${there.size} from the others (${there.count { all.marks[it]?.gone == true }} deleted), $conflicts conflicts; " +
                "folders ${recorded.folders.count { (k, v) -> was.folders[k] != v }} here, ${all.folders.count { (k, v) -> recorded.folders[k] != v }} from the others")
        }
        return out
    }

    fun tabsJson(name: String, tabs: List<RemoteTab>, now: Long = System.currentTimeMillis()): String = JSONObject().put("device", name).put("at", now)
        .put("tabs", JSONArray(tabs.filter { it.url.isNotEmpty() }.map { t -> JSONObject().put("url", t.url).put("title", t.title).put("pinned", t.pinned).apply { if (t.group.isNotEmpty()) put("group", t.group) } }))
        .toString(1) + "\n"

    fun parseTabs(id: String, text: String): DeviceTabs? = runCatching {
        val o = JSONObject(text)
        val a = o.getJSONArray("tabs")
        DeviceTabs(id, o.optString("device").ifEmpty { id }, o.optLong("at"), (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("url").isNotEmpty() }
            .map { RemoteTab(it.getString("url"), it.optString("title"), it.optBoolean("pinned"), it.optString("group")) })
    }.getOrNull()

    /** This device's open tabs, written when they differ from what is there (the time alone is no change). */
    fun writeTabs(root: File, me: String, name: String, tabs: List<RemoteTab>): Boolean {
        val f = File(root, "Tabs/$me.json")
        val old = f.takeIf { it.isFile }?.let { parseTabs(me, it.readText()) }
        if (old != null && old.name == name && old.tabs == tabs.filter { it.url.isNotEmpty() }) return false
        save(f, tabsJson(name, tabs))
        return true
    }

    /** The other devices' tabs, those with any, by name. */
    fun otherTabs(root: File, me: String): List<DeviceTabs> = files(File(root, "Tabs")).filter { it.nameWithoutExtension != me }
        .mapNotNull { parseTabs(it.nameWithoutExtension, it.readText()) }.filter { it.tabs.isNotEmpty() }.sortedBy { it.name.lowercase() }
}
