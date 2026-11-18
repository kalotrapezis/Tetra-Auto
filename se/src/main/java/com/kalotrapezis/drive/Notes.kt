package com.kalotrapezis.drive

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Notes (asked 2026-09-26): a hidden folder of Files, `/sdcard/Tetra/.notes/`, one `<id>.json` per note in the format
 * the Notes apps already write, labels only (no folders). The computer keeps the same folder (desktop/notes.js) and
 * the two meet over `/notes` at each sync: the newer `updatedAt` of each note wins, and a deletion beats any edit.
 *
 * - `deletions.json`: notes deleted for good, so no device brings one back.
 * - `history/<id>/<Title>-YYYY-MM-DD-HH-MM-N.json`: the note each time its editor closed with changes. Undo is the
 *   editor's own, in memory; this is what is left after it.
 *
 * Every write patches the note's own JSON, so fields a newer app wrote are kept.
 */
internal data class CheckItem(val id: String, val text: String, val checked: Boolean, val order: Int)

internal data class Note(
    val id: String, val title: String, val content: String, val checklist: Boolean, val items: List<CheckItem>,
    val labels: List<String>, val color: String?, val pinned: Boolean, val archivedAt: Long?, val trashedAt: Long?,
    val createdAt: Long, val updatedAt: Long,
) {
    val empty get() = title.isBlank() && content.isBlank() && items.none { it.text.isNotBlank() }
}

internal data class NoteVersion(val name: String, val at: Long, val title: String, val content: String, val items: List<CheckItem>, val n: Int = 0)

internal class NotesStore(val root: File, private val deviceId: String = "tetra-android") {
    // Editors and sync create separate stores for the same folder; each transaction must see the other's writes.
    private val lock = locks.computeIfAbsent(root.canonicalPath) { Any() }
    private fun isId(id: String) = id.isNotEmpty() && id.length <= 64 && id.all { it.isLetterOrDigit() || it == '-' }
    private fun file(id: String): File { require(isId(id)) { "Not a note." }; return File(root, "$id.json") }
    private fun versions(id: String): File { require(isId(id)) { "Not a note." }; return File(File(root, "history"), id) }

    // A new note lives here until something is written in it: an empty one is never saved, so never sent (asked 2026-09-26).
    private val drafts = mutableMapOf<String, JSONObject>()

    fun json(id: String): JSONObject? = synchronized(lock) {
        if (id in deletedIds()) null
        else runCatching { JSONObject(file(id).readText()) }.getOrNull() ?: drafts[id]?.let { JSONObject(it.toString()) }
    }
    fun get(id: String): Note? = json(id)?.let(::parse)

    /**
     * Every note as its card shows it ([summary]; [get] reads one whole). A file is read again only when it changed
     * since the last look (the index file keeps that across starts): a list of thousands costs a look at the folder.
     */
    fun list(): List<Note> = synchronized(lock) {
        indexed()
        val deleted = deletedIds()
        val files = root.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name != "deletions.json" }.orEmpty()
        var fresh = false
        val notes = files.mapNotNull { f -> read(f) { fresh = true } }.filterNot { it.id in deleted }
        // what is kept follows the folder as it is now: Tetra left open for months holds no note deleted since
        val here = files.mapTo(HashSet()) { it.path }
        seen.keys.removeIf { File(it).parentFile?.path == root.path && it !in here }
        lists[root.path] = notes
        if (fresh || files.size != indexedCount) saveIndex(files)
        notes
    }

    /** The list as it was last read (Tetra reads it ahead at start): the screen's first frame, without reading anything. */
    fun lastList(): List<Note>? = lists[root.path]

    /** The list as the index file has it — one file, however many notes — before the folder is looked at; null without one. */
    fun indexed(): List<Note>? = lists[root.path] ?: synchronized(seen) {
        val f = indexFile() ?: return null
        val a = runCatching { JSONArray(f.readText()) }.getOrNull() ?: return null
        for (i in 0 until a.length()) runCatching { a.getJSONObject(i).let { o -> seen.putIfAbsent(o.getString("path"), (o.getLong("mod") to o.getLong("len")) to parse(o.getJSONObject("note"))) } }
        indexedCount = a.length()
        val deleted = deletedIds()
        a.let { (0 until it.length()).mapNotNull { i -> seen[it.getJSONObject(i).optString("path")]?.second }.filterNot { n -> n.id in deleted } }
            .also { lists.putIfAbsent(root.path, it) }
    }

    /** The whole of these notes, for search: read from their files, not kept (NotesTab's index while searching). */
    fun whole(ids: List<String>): List<Note> = ids.filter(::isId).mapNotNull { id -> runCatching { parse(JSONObject(file(id).readText())) }.getOrNull() }

    @Volatile private var indexedCount = -1
    private fun indexFile() = indexDir?.let { File(it, "notes-" + Integer.toHexString(root.path.hashCode()) + ".json") }
    private fun saveIndex(files: Array<out File>) {
        val f = indexFile() ?: return
        val a = JSONArray()
        files.forEach { file -> seen[file.path]?.let { (s, n) -> a.put(JSONObject().put("path", file.path).put("mod", s.first).put("len", s.second).put("note", cardJson(n))) } }
        runCatching { File(f.path + ".tmp").apply { writeText(a.toString()) }.renameTo(f) }
        indexedCount = files.size
    }

    private fun read(f: File, fresh: () -> Unit): Note? {
        val stamp = f.lastModified() to f.length()
        seen[f.path]?.let { (s, n) -> if (s == stamp) return n }
        return runCatching { summary(parse(JSONObject(f.readText()))) }.getOrNull()?.also { seen[f.path] = stamp to it; fresh() }
    }

    /** Whole, through a temporary file, so a crash never leaves half a note. */
    fun write(o: JSONObject) = synchronized(lock) {
        val id = o.getString("id")
        check(id !in deletedIds()) { "That note was permanently deleted." }
        writeJson(file(id), o.toString(2))
        drafts.remove(id)
        Unit
    }

    private fun writeJson(target: File, text: String) {
        seen.remove(target.path)
        root.mkdirs()
        val tmp = File(root, "${target.name}.tmp")
        tmp.writeText(text)
        check(tmp.renameTo(target)) { "Could not save ${target.name}; the previous file was kept." }
    }

    fun create(checklist: Boolean, labels: List<String> = emptyList()): Note = synchronized(lock) {
        val now = System.currentTimeMillis()
        val o = JSONObject().put("id", UUID.randomUUID().toString()).put("title", "").put("content", "")
            .put("createdAt", now).put("updatedAt", now).put("deviceId", deviceId).put("syncStatus", "LOCAL_ONLY")
            .put("noteType", if (checklist) "CHECKLIST" else "TEXT").put("isPinned", false).put("labels", JSONArray(labels))
        if (checklist) o.put("checklistItems", JSONArray())
        drafts[o.getString("id")] = o
        parse(o)
    }

    /** A person's edit: the fields given change, everything else stays, and it is now the newest. */
    fun update(id: String, edit: (JSONObject) -> Unit): Note = synchronized(lock) {
        val o = json(id) ?: error("That note is gone.")
        val previous = o.optLong("updatedAt")
        edit(o)
        o.put("updatedAt", maxOf(System.currentTimeMillis(), previous + 1)).put("deviceId", deviceId)
        write(o)
        parse(o)
    }

    fun setText(id: String, title: String, content: String, items: List<CheckItem>?) = update(id) { o ->
        o.put("title", title).put("content", content)
        if (items != null) o.put("checklistItems", itemsJson(items, o.optJSONArray("checklistItems")))
    }

    /**
     * A copy of the note as it is now, in its history: taken before the first change of an opened note and when it is
     * left (asked 2026-09-26: "when I change stuff I can still see my old note before the edits"). The same text as
     * the newest copy is not kept twice, and only the newest [KEEP] copies stay.
     */
    fun snapshot(id: String, now: Long = System.currentTimeMillis()): String? = synchronized(lock) {
        val o = json(id) ?: return null
        val note = parse(o)
        if (note.empty) return null // nothing to go back to
        history(id).firstOrNull()?.let { if (it.title == note.title && it.content == note.content && it.items == note.items) return null }
        val dir = versions(id).apply { mkdirs() }
        val n = (dir.listFiles().orEmpty().mapNotNull { Regex("-(\\d+)\\.json$").find(it.name)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0) + 1
        val c = Calendar.getInstance().apply { timeInMillis = now }
        val stamp = "%04d-%02d-%02d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        val name = "${safeTitle(o.optString("title"))}-$stamp-$n.json"
        File(dir, name).writeText(JSONObject(o.toString()).put("savedAt", now).put("version", n).toString(2))
        history(id).drop(KEEP).forEach { File(dir, it.name).delete() }
        name
    }

    fun history(id: String): List<NoteVersion> = synchronized(lock) { versions(id).listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
        runCatching { JSONObject(f.readText()).let { o -> parse(o).let { NoteVersion(f.name, it.updatedAt, it.title, it.content, it.items, o.optInt("version")) } } }.getOrNull()
    }.sortedWith(compareByDescending<NoteVersion> { it.n }.thenByDescending { it.at }) }

    /** A version comes back as a new edit; what it replaces is kept in the history first. */
    fun restoreVersion(id: String, name: String): Note = synchronized(lock) {
        require(File(name).name == name) { "Not a version." }
        val v = JSONObject(File(versions(id), name).readText())
        snapshot(id)
        update(id) { o ->
            o.put("title", v.optString("title")).put("content", v.optString("content"))
            v.optJSONArray("checklistItems")?.let { o.put("checklistItems", it) }
        }
    }

    fun deletions(): JSONArray = runCatching { JSONArray(File(root, "deletions.json").readText()) }.getOrDefault(JSONArray())
    private fun deletedIds(list: JSONArray = deletions()) = (0 until list.length()).mapTo(HashSet()) { list.getJSONObject(it).optString("id") }

    /** For good: the file goes and the id is remembered. Its history stays, so it can still be read back. */
    fun remove(id: String, at: Long = System.currentTimeMillis()) = synchronized(lock) {
        if (drafts.remove(id) != null && !file(id).exists()) return // never saved: nothing to tell anyone
        val target = file(id)
        val old = deletions()
        val list = JSONArray()
        for (i in 0 until old.length()) if (old.getJSONObject(i).optString("id") != id) list.put(old.getJSONObject(i))
        list.put(JSONObject().put("id", id).put("deletedAt", at).put("deviceId", deviceId))
        writeJson(File(root, "deletions.json"), list.toString())
        check(!target.exists() || target.delete()) { "Could not delete the note file." }
        Unit
    }

    /** Trash keeps a note 30 days, then it is deleted for good. */
    fun sweep(now: Long = System.currentTimeMillis(), notes: List<Note> = list()): Int = synchronized(lock) {
        notes.filter { it.trashedAt != null && it.trashedAt < now - 30 * 86_400_000L }.onEach { remove(it.id, now) }.size
    }

    /** What this phone sends at a sync: every note and every deletion. */
    fun payload(): JSONObject = synchronized(lock) {
        JSONObject().put("notes", JSONArray(list().mapNotNull { json(it.id) })).put("deletions", deletions())
    }

    /** The computer's answer: notes newer there or missing here, and every deletion. Returns how many changed here. */
    fun apply(answer: JSONObject): Int = synchronized(lock) {
        var changed = 0
        val theirs = answer.optJSONArray("deletions") ?: JSONArray()
        val mine = deletions()
        val known = deletedIds(mine)
        for (i in 0 until theirs.length()) {
            val d = theirs.getJSONObject(i)
            val id = d.optString("id")
            if (!isId(id) || id in known) continue
            mine.put(d); known += id
        }
        writeJson(File(root, "deletions.json"), mine.toString())
        // Retry known deletions too: an earlier failed delete or stale writer may have left a file behind.
        for (id in known) {
            if (!isId(id)) continue
            drafts.remove(id)
            val target = file(id)
            if (target.exists()) {
                check(target.delete()) { "Could not delete the note file." }
                changed++
            }
        }
        val notes = answer.optJSONArray("notes") ?: JSONArray()
        for (i in 0 until notes.length()) {
            val n = notes.getJSONObject(i)
            val id = n.optString("id")
            if (!isId(id) || id in known) continue
            val here = json(id)
            if (here == null || n.optLong("updatedAt") > here.optLong("updatedAt")) { write(n); changed++ }
        }
        changed
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
        private val seen = ConcurrentHashMap<String, Pair<Pair<Long, Long>, Note>>()
        private val lists = ConcurrentHashMap<String, List<Note>>()
        /** Where the list's index files go (the app's cache, set at start); none: every start reads the folder. */
        @Volatile var indexDir: File? = null
        private const val PREVIEW = 8 // lines a card shows at most (NoteFormat.preview)
        private const val LINE = 400 // characters of a line kept for a card, which shows three lines of it

        /** A card's worth of a note: what the list keeps in memory (the user, 25 Oct: some have thousands of notes). */
        fun summary(n: Note) = n.copy(
            content = n.content.lineSequence().filter { it.isNotBlank() }.take(PREVIEW).joinToString("\n") { it.take(LINE) },
            items = n.items.sortedForList().take(PREVIEW).map { it.copy(text = it.text.take(LINE)) },
        )

        /** [parse] reads it back. */
        private fun cardJson(n: Note): JSONObject = JSONObject().put("id", n.id).put("title", n.title).put("content", n.content)
            .put("noteType", if (n.checklist) "CHECKLIST" else "TEXT").put("labels", JSONArray(n.labels)).put("color", n.color ?: "")
            .put("isPinned", n.pinned).put("archivedAt", n.archivedAt ?: 0).put("trashedAt", n.trashedAt ?: 0)
            .put("createdAt", n.createdAt).put("updatedAt", n.updatedAt)
            .put("checklistItems", JSONArray(n.items.map { JSONObject().put("id", it.id).put("text", it.text).put("isChecked", it.checked).put("order", it.order) }))
        const val KEEP = 3 // copies of a note in its history
        fun safeTitle(t: String) = t.replace(Regex("[/\\\\\u0000:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim().take(80).ifEmpty { "Untitled" }

        fun parse(o: JSONObject): Note {
            val items = o.optJSONArray("checklistItems")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i) }.map {
                CheckItem(it.optString("id"), it.optString("text"), it.optBoolean("isChecked"), it.optInt("order"))
            } }.orEmpty()
            val labels = o.optJSONArray("labels")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty()
            return Note(
                id = o.getString("id"), title = o.optString("title"), content = o.optString("content"),
                checklist = o.optString("noteType") == "CHECKLIST", items = items, labels = labels,
                color = o.optString("color").takeIf { it.startsWith("#") }, pinned = o.optBoolean("isPinned"),
                archivedAt = o.optLong("archivedAt").takeIf { it > 0 }, trashedAt = o.optLong("trashedAt").takeIf { it > 0 },
                createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"),
            )
        }

        /** Items as the Notes apps write them; an item's other fields (indentation, dates) are kept by its id. */
        fun itemsJson(items: List<CheckItem>, old: JSONArray?): JSONArray {
            val before = old?.let { a -> (0 until a.length()).map(a::getJSONObject).associateBy { it.optString("id") } }.orEmpty()
            return JSONArray(items.map { item ->
                (before[item.id] ?: JSONObject().put("createdAt", System.currentTimeMillis()).put("indentationLevel", 0).put("originalOrder", item.order))
                    .put("id", item.id).put("text", item.text).put("isChecked", item.checked).put("order", item.order)
            })
        }
    }
}

/** Unchecked in their order, then the checked ones below — as every Notes app shows a checklist. */
/** Keep's palette, which the imported notes already use, by Keep's names (AI Assist's note_color says them). */
internal object NoteColors {
    val NAMES = linkedMapOf("red" to "#F28B82", "orange" to "#FBBC04", "yellow" to "#FFF475", "green" to "#CCFF90", "teal" to "#A7FFEB", "blue" to "#CBF0F8",
        "dark blue" to "#AECBFA", "purple" to "#D7AEFB", "pink" to "#FDCFE8", "brown" to "#E6C9A8", "gray" to "#E8EAED")
    fun hex(name: String) = NAMES[name.trim().lowercase().replace('_', ' ').replace("grey", "gray")]
    fun none(name: String) = name.trim().lowercase() in setOf("none", "default", "")
}

internal fun List<CheckItem>.sortedForList() = sortedWith(compareBy<CheckItem> { it.checked }.thenBy { it.order })

/**
 * Undo in memory, per open note, by the word (asked 2026-09-26): typing joins the step it is in until a word ends — a
 * space or a mark typed after it — or it pauses for [quietMs]. A toolbar action is a step of its own.
 */
internal class NoteUndo<T>(private var current: T, private val quietMs: Long = 2000) {
    private val past = ArrayDeque<T>()
    private val future = ArrayDeque<T>()
    private var last = 0L
    private var split = true // the next change starts a step of its own
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()
    fun push(next: T, now: Long = System.currentTimeMillis(), wordDone: Boolean = false) {
        if (split || now - last > quietMs) past.addLast(current)
        if (past.size > 500) past.removeFirst()
        current = next; future.clear(); last = now; split = wordDone
    }
    fun step(next: T) { split = true; push(next); split = true }
    fun undo(): T? = past.removeLastOrNull()?.also { future.addLast(current); current = it; split = true }
    fun redo(): T? = future.removeLastOrNull()?.also { past.addLast(current); current = it; split = true }

    companion object {
        /** One character typed at [at] that is not part of a word: the word before it is done. */
        fun endsWord(old: String, new: String, at: Int) = new.length == old.length + 1 && at in 1..new.length && !new[at - 1].isLetterOrDigit()
    }
}

/** Formatting a selection of the text: the same rules as the computer's editor (desktop/src/notesEdit.ts). */
internal object NoteFormat {
    data class Edit(val text: String, val start: Int, val end: Int)

    fun wrap(e: Edit, mark: String): Edit {
        val before = e.text.substring(0, e.start); val inside = e.text.substring(e.start, e.end); val after = e.text.substring(e.end)
        return if (before.endsWith(mark) && after.startsWith(mark))
            Edit(before.dropLast(mark.length) + inside + after.drop(mark.length), e.start - mark.length, e.end - mark.length)
        else Edit(before + mark + inside + mark + after, e.start + mark.length, e.end + mark.length)
    }

    private fun lines(e: Edit, change: (List<String>) -> List<String>): Edit {
        val from = e.text.lastIndexOf('\n', e.start - 1) + 1
        val nl = e.text.indexOf('\n', e.end)
        val to = if (nl == -1) e.text.length else nl
        val body = change(e.text.substring(from, to).split('\n')).joinToString("\n")
        return Edit(e.text.substring(0, from) + body + e.text.substring(to), from, from + body.length)
    }

    fun prefix(e: Edit, mark: String): Edit = lines(e) { ls ->
        val all = ls.all { it.startsWith(mark) }
        ls.mapIndexed { i, l -> if (all) l.drop(mark.length) else (if (mark == "1. ") "${i + 1}. " else mark) + l.replace(Regex("^(#{1,3} |- \\[[ x]\\] |- |\\d+\\. |> )"), "") }
    }

    fun indent(e: Edit, out: Boolean): Edit = lines(e) { ls -> ls.map { if (out) it.replaceFirst(Regex("^ {1,2}"), "") else "  $it" } }

    // made once: built per line of every card, they kept the first frame of a full Notes waiting (25 Oct)
    private val heading = Regex("^(#{1,3} |> )")
    private val open = Regex("^(\\s*)- \\[ \\] ")
    private val done = Regex("^(\\s*)- \\[[xX]\\] ")
    private val marks = Regex("\\*\\*|~~|`")

    fun preview(n: Note, max: Int = 8): List<String> =
        if (n.checklist) n.items.sortedForList().take(max).map { "${if (it.checked) "☑" else "☐"} ${it.text}" }
        else n.content.lines().filter { it.isNotBlank() }.take(max).map {
            it.replace(heading, "").replace(open, "$1☐ ").replace(done, "$1☑ ").replace(marks, "")
        }
}

/**
 * Notes as Markdown files (the user, 27 Oct: "an export function for notes into md files, categorized by folders with
 * the tags' names"), the same as the computer's (desktop/notes.js). Notes stay JSON inside Tetra; this is a copy out.
 * The title names the file and is its first heading, under a front matter of the labels and dates; the text is
 * Markdown already, and a checklist becomes `- [ ]` / `- [x]` lines. Each export is a new dated folder in Files, so
 * nothing is written over (the user, 27 Oct); a label's "/" nests its folders, as "Home/Garden" → Home/Garden/.
 */
internal object NoteExport {
    /** A file of an export: where it goes in the export's folder, its text, and its time (the note's last change). */
    data class Out(val path: String, val text: String, val at: Long)

    private fun iso(ms: Long) = java.time.Instant.ofEpochMilli(ms).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private val slash = Regex("[/\\\\]")
    private val colon = Regex("\\s*:\\s*")
    private val refused = Regex("[\u0000*?\"<>|]")
    private val spaces = Regex("\\s+")
    /** A name every file system takes: "/" a dash, ":" " - " (the user, 27 Oct); a leading dot would hide the file. */
    fun safeName(s: String, untitled: String) = s.ifBlank { untitled }.replace(slash, "-").replace(colon, " - ").replace(refused, " ")
        .replace(spaces, " ").take(80).trim().trimStart('.').ifEmpty { untitled }
    /** A label's folders: "Home/Garden" is Garden inside Home. */
    private fun folders(label: String, untitled: String) = label.split('/').filter { it.isNotBlank() }.joinToString("/") { safeName(it, untitled) }.ifEmpty { untitled }

    /** The export's own folder, by the local time it was made; the name is English in every language. */
    fun folder(at: java.time.LocalDateTime) = "Tetra-Notes-export-%04d-%02d-%02d-%02d%02d".format(at.year, at.monthValue, at.dayOfMonth, at.hour, at.minute)

    /** One note: its file's name (without .md) and its text. */
    fun markdown(n: Note, untitled: String): Pair<String, String> {
        val front = buildString {
            append("---\n")
            if (n.labels.isNotEmpty()) append("tags: [${n.labels.joinToString(", ", transform = ::quote)}]\n")
            if (n.createdAt > 0) append("created: ${iso(n.createdAt)}\n")
            if (n.updatedAt > 0) append("updated: ${iso(n.updatedAt)}\n")
            append("---\n\n")
        }
        val body = if (n.checklist) n.items.sortedForList().joinToString("") { "- [${if (it.checked) "x" else " "}] ${it.text}\n" }
            else n.content.trimEnd().let { if (it.isEmpty()) "" else "$it\n" }
        val heading = "# ${n.title.trim()}"
        // a note that starts with its own title as a heading keeps just that one
        val title = if (n.title.isBlank() || body.lineSequence().firstOrNull { it.isNotBlank() }?.trim() == heading) "" else "$heading\n\n"
        return safeName(n.title, untitled) to front + title + body
    }

    /**
     * Every note, in a folder per label (in each, if it has several), at the top without one; the archived the same way
     * inside Archive, the trashed inside Trash. The same name twice in a folder (case aside, for the file systems that
     * ignore it) becomes "Name (2)", the older note keeping the plain name.
     */
    fun files(notes: List<Note>, untitled: String): List<Out> {
        val taken = HashSet<String>()
        return notes.sortedBy { it.createdAt }.flatMap { n ->
            val (name, text) = markdown(n, untitled)
            val top = if (n.trashedAt != null) "Trash/" else if (n.archivedAt != null) "Archive/" else ""
            n.labels.map { top + folders(it, untitled) + "/" }.distinct().ifEmpty { listOf(top) }.map { dir ->
                var path = "$dir$name.md"
                var i = 1
                while (!taken.add(path.lowercase())) path = "$dir$name (${++i}).md"
                Out(path, text, n.updatedAt)
            }
        }
    }
}

/**
 * Notes cross on their own, a moment after writing pauses — as the old Notes app did, every two or three seconds
 * (asked 2026-09-26) — not only with the whole sync. Only the notes go: a few kilobytes, a second's work.
 */
internal object NotesSync {
    /** Counts up when notes from the computer land here, so an open list reloads. */
    val changed = MutableStateFlow(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pending: Job? = null

    /** At once, and waits: pull to refresh, and opening Notes. What changed here, or why it could not. */
    suspend fun now(context: Context): Result<Int> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val app = context.applicationContext
        runCatching { SyncClient(app, SyncStore(app)).notesOnly() }.onFailure { android.util.Log.w("NotesSync", "notes sync failed", it) }
    }

    fun soon(context: Context, delayMs: Long = 2_500) {
        val app = context.applicationContext
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            runCatching { SyncClient(app, SyncStore(app)).notesOnly() } // the computer asleep: the next pause, or the next sync
                .onFailure { android.util.Log.w("NotesSync", "notes sync failed", it) }
        }
    }
}
