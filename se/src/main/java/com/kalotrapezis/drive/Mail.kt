package com.kalotrapezis.drive

import android.content.Context
import java.io.File
import java.nio.charset.Charset
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tetra Mail on the phone and tablet, step 2 of MAIL_PLAN.md: read only. The computer fetches the mail (its desktop/mail.js,
 * step 1) and sync brings its archive here, so everything below only reads what it wrote:
 *
 *   Tetra/.System/Mail/Shared/accounts.json        names, addresses, colours; no secrets
 *   Tetra/.System/Mail/Shared/index.jsonl          one line per mail; a later line with the same id replaces it
 *   Tetra/.System/Mail/Shared/<address>/<Folder>/<yyyy>/<yyyy-mm-dd hhmm> <from> - <subject>.eml
 *   … /<same name>.files/                          its attachments, as ordinary files
 *
 * <Folder> is the role (Inbox, Drafts, Sent, Archive, Spam, Trash), the same for every account and language. A mail is
 * green (`saved`) once its .eml holds what the server has, orange until then; a `gone` one left the server and is not listed.
 */
internal data class MailEntry(
    val id: String, val account: String, val folder: String, val date: Long, val from: String, val fromAddress: String,
    val to: List<String>, val subject: String, val seen: Boolean, val flagged: Boolean, val size: Long, val attachments: Int,
    val snippet: String, val saved: Boolean, val file: String?, val gone: Boolean, val messageId: String? = null,
)

/**
 * An account as accounts.json (the computer's) or accounts.<device>.json (a phone's or tablet's own) describes it.
 * [own]: this device fetches it itself (its password is in this device's keystore); otherwise it is a shadow account,
 * its mail through the PC (MAIL_PLAN §1). [json] is the account as written, kept for the engine (server, smtp).
 */
internal data class MailAccount(val id: String, val address: String, val name: String, val color: Long, val addedOn: String,
    val own: Boolean = false, val json: JSONObject? = null) {
    val shadow get() = !own
}

/** A folder's counts for one account: unread, all, and how many are only on the server (orange). */
internal data class MailCount(val unread: Int, val total: Int, val orange: Int)

internal object MailFiles {
    const val FOLDER = "Mail"
    val ROLES = listOf("Inbox", "Drafts", "Sent", "Archive", "Spam", "Trash")

    fun root(tetra: File) = File(tetra, TetraSystem.onDisk(FOLDER))

    /** The computer's accounts.json, then each device's accounts.<device>.json; one account once, [own] those this device fetches. */
    fun accounts(root: File, own: Set<String> = emptySet()): List<MailAccount> {
        val files = listOf(File(root, "accounts.json")) + (root.listFiles { f -> f.name.startsWith("accounts.") && f.name.endsWith(".json") && f.name != "accounts.json" }.orEmpty().sortedBy { it.name })
        val byId = LinkedHashMap<String, MailAccount>()
        for (f in files) runCatching {
            val a = JSONArray(f.readText())
            for (o in (0 until a.length()).map { a.getJSONObject(it) }) {
                val id = o.getString("id")
                if (id in byId && id !in own) continue // the computer's description first; this device's own when it fetches it
                byId[id] = MailAccount(id, o.optString("address", id), o.optString("name", id), color(o.optString("color")), o.optString("addedOn", "desktop"), id in own, o)
            }
        }
        return byId.values.toList()
    }

    private fun color(hex: String) = hex.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { 0xFF000000 or it }?.let(::notMagenta) ?: 0xFF9E9E9E

    /** Magenta is Mail's own, for «Αποθήκη» alone (the user, 31 Oct): an account's colour near it is shown as [INSTEAD]. */
    const val INSTEAD = 0xFFA0785A
    fun notMagenta(argb: Long): Long {
        val r = (argb shr 16 and 0xFF).toInt(); val g = (argb shr 8 and 0xFF).toInt(); val b = (argb and 0xFF).toInt()
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        if (max == 0 || (max - min) * 100 / max < 25) return argb // grey enough: not a colour to mistake
        val h = when (max) { r -> 60f * (((g - b).toFloat() / (max - min)) % 6); g -> 60f * ((b - r).toFloat() / (max - min) + 2); else -> 60f * ((r - g).toFloat() / (max - min) + 4) }.let { if (it < 0) it + 360 else it }
        return if (h in 285f..345f) INSTEAD else argb
    }

    private var cached: Pair<String, List<MailEntry>>? = null
    // at a background priority: the UI thread first, whatever Tetra is showing meanwhile
    private val PARSERS = java.util.concurrent.Executors.newFixedThreadPool(maxOf(2, Runtime.getRuntime().availableProcessors() / 2)) { r -> Thread({ runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }; r.run() }, "mail-index").apply { isDaemon = true } }
    /**
     * Every mail not gone: the computer's index.jsonl, then each device's index.<device>.jsonl (the mail a phone fetched
     * itself), this device's last; the last line for an id wins, and an id is the same mail whoever fetched it. Read
     * again only when a file's size or time changed (they may be long).
     */
    fun index(root: File, own: String? = null): List<MailEntry> = synchronized(this) {
        val files = indexFiles(root, own)
        val stamp = files.joinToString { "${it.name}:${it.length()}:${it.lastModified()}" }
        cached?.takeIf { it.first == stamp }?.let { return it.second }
        val start = System.nanoTime()
        val lines = files.flatMap { f -> runCatching { f.readLines() }.getOrDefault(emptyList()) }
        // the lines parsed in pieces on half the cores, kept in order, so the last line for an id still wins (2.5 s on the
        // phone on one core; on every core the UI's frames went up to 0.5 s)
        val read = merge(lines.chunked(maxOf(1000, lines.size / 16)).map { c -> PARSERS.submit<List<MailEntry?>> { c.map(::line) } }.flatMap { it.get() }.filterNotNull())
        WriterLog.i("Mail: index read, ${lines.size} lines, ${read.size} mails, ${(System.nanoTime() - start) / 1_000_000} ms")
        cached = stamp to read
        read
    }

    /** Read ahead when Tetra starts, at a background priority: Mail then opens on its list, not on a wait. */
    fun warm(own: String?) = Thread {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        Thread.sleep(1000) // after the screen Tetra opened on has settled (started with it, AI Assist's taps waited 1.9 s)
        runCatching { val root = mailRoot(); if (File(root, "index.jsonl").isFile) index(root, own) }
    }.start()

    /**
     * This device's mails held as not saved that the computer has saved since: its file instead. The computer had blanked a
     * saved mail and fetched it again as "1970 - - -" (4 Nov); mended there, this device's copy of its record still said
     * "only on the server".
     */
    fun healed(mine: List<MailEntry>, computer: Map<String, MailEntry>, exists: (String) -> Boolean): List<MailEntry> =
        mine.filter { it.file != null && !it.saved && !it.gone }.mapNotNull { e ->
            computer[e.id]?.takeIf { it.saved && !it.gone && it.file != null && it.file != e.file && exists(it.file) }
                ?.let { s -> e.copy(file = s.file, saved = true, attachments = s.attachments, snippet = s.snippet.ifEmpty { e.snippet }) }
        }

    /** The index files in the order their lines count: the computer's, the other devices', this device's last. */
    fun indexFiles(root: File, own: String?): List<File> {
        val others = root.listFiles { f -> f.name.startsWith("index.") && f.name.endsWith(".jsonl") && f.name != "index.jsonl" }.orEmpty().sortedBy { it.name }
        return listOf(File(root, "index.jsonl")) + others.sortedBy { it.name == "index.$own.jsonl" }
    }

    fun parse(lines: Sequence<String>, keepGone: Boolean = false): List<MailEntry> = merge(lines.mapNotNull(::line).asIterable(), keepGone)

    private fun line(s: String): MailEntry? = if (s.isBlank()) null else runCatching { entry(JSONObject(s)) }.getOrNull() // null: a line cut by a crash on the computer

    private fun merge(entries: Iterable<MailEntry>, keepGone: Boolean = false): List<MailEntry> {
        val byId = LinkedHashMap<String, MailEntry>()
        for (e in entries) {
            val before = byId[e.id]
            // a device that has only the headers does not hide the body another one saved
            byId[e.id] = if (e.file == null && !e.gone && before?.file != null) e.copy(file = before.file, saved = before.saved, attachments = before.attachments, snippet = before.snippet) else e
        }
        return byId.values.filter { keepGone || !it.gone }
    }

    fun json(e: MailEntry): JSONObject = JSONObject().put("id", e.id).put("account", e.account).put("folder", e.folder).put("date", e.date)
        .put("from", e.from).put("fromAddress", e.fromAddress).put("to", JSONArray(e.to)).put("subject", e.subject).put("seen", e.seen)
        .put("flagged", e.flagged).put("size", e.size).put("attachments", e.attachments).put("snippet", e.snippet).put("saved", e.saved)
        .put("file", e.file ?: JSONObject.NULL).put("gone", e.gone).apply { e.messageId?.let { put("messageId", it) } }

    private fun entry(o: JSONObject) = MailEntry(
        id = o.getString("id"), account = o.getString("account"), folder = o.getString("folder"), date = o.optLong("date"),
        from = o.optString("from"), fromAddress = o.optString("fromAddress"),
        to = o.optJSONArray("to")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
        subject = o.optString("subject"), seen = o.optBoolean("seen"), flagged = o.optBoolean("flagged"), size = o.optLong("size"),
        attachments = o.optInt("attachments"), snippet = o.optString("snippet"), saved = o.optBoolean("saved"),
        file = o.optString("file").takeIf { it.isNotEmpty() && !o.isNull("file") }, gone = o.optBoolean("gone"),
        messageId = o.optString("messageId").ifEmpty { null },
    )

    /** Role → account → counts, for the unified folders (MAIL_PLAN §4); only the accounts in accounts.json. */
    fun counts(mails: List<MailEntry>, accounts: List<MailAccount>): Map<String, Map<String, MailCount>> {
        val ids = accounts.map { it.id }.toSet()
        return ROLES.associateWith { role ->
            val here = mails.filter { it.folder == role && it.account in ids }.groupBy { it.account }
            accounts.associate { a -> a.id to (here[a.id].orEmpty().let { m -> MailCount(m.count { !it.seen }, m.size, m.count { !it.saved }) }) }
        }
    }

    /** «Αποθήκη»: what one account's folder ([id]), or the whole of Mail (null), takes here — bytes, and how many mails. */
    fun held(root: File, id: String?): Pair<Long, Int> {
        var bytes = 0L; var mails = 0
        (if (id == null) root else File(root, id)).walkTopDown().filter { it.isFile && !it.name.startsWith(".") }
            .forEach { bytes += it.length(); if (it.name.endsWith(".eml")) mails++ }
        return bytes to mails
    }

    /** The mail's file and its attachments' folder, or null when it is not on this device yet. */
    fun eml(root: File, e: MailEntry) = e.file?.let { File(root, it) }?.takeIf { it.isFile && inside(root, it) }
    fun attachments(root: File, e: MailEntry): List<File> = eml(root, e)?.let { File(it.path.removeSuffix(".eml") + ".files") }
        ?.listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() }.orEmpty()
    private fun inside(root: File, f: File) = f.canonicalPath.startsWith(root.canonicalPath + File.separator) // never a path out of the archive
}

/**
 * «Αποθήκη» (the user, 31 Oct: "the mail app to be the file manager … my local backups, to find an important mail I
 * deleted by mistake"): every mail kept on this device, from its files — the PC's accounts' and this device's own. Each
 * as the index describes it where a line names its file; one the server no longer has (gone), or no line names at all,
 * is local only ([MailEntry.gone]). Read only: nothing here goes to a server.
 */
internal object MailArchive {
    fun entries(root: File, own: String?): List<MailEntry> {
        val indexed = MailFiles.parse(MailFiles.indexFiles(root, own).asSequence().flatMap { f -> runCatching { f.readLines() }.getOrDefault(emptyList()) }, keepGone = true)
        val byFile = HashMap<String, MailEntry>()
        for (e in indexed) e.file?.let { f -> val was = byFile[f]; if (was == null || (was.gone && !e.gone)) byFile[f] = e } // a live line before a gone one
        return root.walkTopDown().onEnter { !it.name.startsWith(".") }.filter { it.isFile && it.name.endsWith(".eml") && !it.name.startsWith(".") }.mapNotNull { f ->
            val rel = f.relativeTo(root).invariantSeparatorsPath
            val parts = rel.split('/')
            if (parts.size < 3 || parts[1] !in MailFiles.ROLES) return@mapNotNull null // <address>/<Role>/…: not the outbox, not a stray
            byFile[rel]?.copy(saved = true) ?: runCatching { fromFile(f, rel, parts[0], parts[1]) }.getOrNull()
        }.sortedByDescending { it.date }.toList()
    }

    /** A mail no index line names: what its own headers say (the first 64 KB), its date from its name when they say none. */
    fun fromFile(f: File, rel: String, account: String, role: String): MailEntry {
        val head = f.inputStream().use { s -> ByteArray(minOf(f.length(), 65_536L).toInt()).also { java.io.DataInputStream(s).readFully(it) } } // readNBytes is Android 13+ (the car's build: 10)
        val h = Mime.headers(head)
        val from = Mime.words(h["from"].orEmpty())
        val address = Regex("<([^>]+)>").find(from)?.groupValues?.get(1) ?: from.trim()
        val date = runCatching { java.time.ZonedDateTime.parse(h["date"].orEmpty().replace(Regex("\\s*\\(.*\\)$"), "").trim(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .getOrElse { Regex("(\\d{4})-(\\d{2})-(\\d{2}) (\\d{2})(\\d{2}) ").find(f.name)?.destructured?.let { (y, mo, d, hh, mm) ->
                java.time.LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), hh.toInt(), mm.toInt()).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() } ?: f.lastModified() }
        val files = File(f.path.removeSuffix(".eml") + ".files").listFiles()?.count { it.isFile } ?: 0
        return MailEntry("file:$rel", account, role, date, from.substringBefore('<').trim().trim('"').ifEmpty { address }, address,
            Mime.words(h["to"].orEmpty()).split(',').map { it.trim() }.filter { it.isNotEmpty() }, Mime.words(h["subject"].orEmpty()), seen = true, flagged = false,
            size = f.length(), attachments = files, snippet = "", saved = true, file = rel, gone = true, messageId = h["message-id"]?.trim())
    }

    /**
     * «Αποθήκη» expanded, as the desktop's: each account with stored mail (a removed one too), and under it each of its
     * folders that holds some, in the roles' order — (all, local only) for each.
     */
    fun tree(entries: List<MailEntry>): Map<String, Map<String, Pair<Int, Int>>> = entries.groupBy { it.account }.toSortedMap().mapValues { (_, ms) ->
        ms.groupBy { it.folder }.toList().sortedBy { MailFiles.ROLES.indexOf(it.first) }.associate { (r, l) -> r to (l.size to l.count { it.gone }) }
    }

    /** The archive's mails matching [query] in sender, subject, recipients or an attachment's name, and within the dates given (ms). */
    fun search(root: File, entries: List<MailEntry>, query: String, from: Long? = null, to: Long? = null): List<MailEntry> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return entries.filter { e ->
            (from == null || e.date >= from) && (to == null || e.date <= to) && words.all { w ->
                listOf(e.from, e.fromAddress, e.subject, e.to.joinToString(" "), e.snippet).any { it.lowercase().contains(w) } ||
                    MailFiles.attachments(root, e).any { it.name.lowercase().contains(w) }
            }
        }
    }
}

/**
 * Copies out of Mail (the user, 31 Oct: "click export to copy an attachment … in Tetra/Documents for me to find"): into
 * Tetra/Documents, which syncs to the PC's, never over a file there — "name (2).pdf" instead.
 */
internal object MailExport {
    fun free(dir: File, name: String): File {
        val clean = name.replace(Regex("[/\\\\\u0000]"), "_").ifBlank { "attachment" }
        val base = clean.substringBeforeLast('.', clean); val ext = clean.substringAfterLast('.', "").let { if (it.isEmpty() || it == clean) "" else ".$it" }
        var f = File(dir, clean); var n = 2
        while (f.exists()) f = File(dir, "$base ($n)$ext").also { n++ }
        return f
    }
    /** [file] copied into [documents] (Tetra/Documents); the copy. */
    fun toDocuments(file: File, documents: File, name: String = file.name): File {
        documents.mkdirs()
        val target = free(documents, name)
        val part = File(documents, ".${target.name}.part")
        file.inputStream().use { input -> part.outputStream().use { input.copyTo(it) } }
        check(part.renameTo(target)) { "Not saved: ${target.name}" }
        return target
    }
    /** A mail's own name for its .eml outside the archive: its subject, else the file's. */
    fun emlName(e: MailEntry, file: File) = Mime.clean(e.subject, 120).ifEmpty { file.nameWithoutExtension } + ".eml"
}

/**
 * What of the Mail part a device keeps (MAIL_PLAN §2, the user's per-device setting, §14): every file without a date
 * (index, accounts, signatures, the devices' settings) always; then mail by the date in its path ("yyyy-mm-dd hhmm …"),
 * newest first — first the mail itself, then, with what is left, the attachments — until the device's limit. The
 * computer offers every Mail file each sync; the rest comes by [SyncClient.mailFile] when opened. Requests and the
 * outbox are this device's own to send, never taken back.
 */
internal object MailSync {
    const val PART = "Mail/"
    const val REQUESTS = "Mail/requests/"
    const val OUTBOX = "Mail/outbox/"
    /** This device's own to send, never taken back: requests and the outbox (but a send the PC gave up on comes back as .failed.json). */
    fun outgoing(rel: String) = rel.startsWith(REQUESTS) || (rel.startsWith(OUTBOX) && !rel.endsWith(".failed.json"))
    private val DATED = Regex("(?:^|/)(\\d{4}-\\d{2}-\\d{2} \\d{4}) ")
    /** A mail or an attachment (its path has the mail's date), rather than one of the part's own files. */
    fun dated(rel: String) = DATED.containsMatchIn(rel)

    /**
     * A Mail file the computer (or another device) writes and this one only reads: never sent back, taken first and
     * replaced here in place. This device's own files and those both write (signatures, settings) go the usual way.
     */
    fun mirrored(rel: String, me: String) = rel.startsWith(PART) && !dated(rel) && !outgoing(rel) && !rel.startsWith(OUTBOX) &&
        rel !in setOf("${PART}signatures.json", "${PART}settings.json", "${PART}index.$me.jsonl", "${PART}accounts.$me.json", "${PART}devices/$me.usage.json")

    /** "index (2).jsonl" and the like: a computer's file that once arrived beside an older copy, read by nobody. */
    fun strays(root: File): List<File> = root.listFiles { f -> f.isFile && Regex("^(index|accounts|signatures)\\b.* \\(\\d+\\)\\.jsonl?$").matches(f.name) }.orEmpty().toList()

    /** Of [files] (path → bytes), those kept within [limit] bytes (-1: all). */
    fun plan(files: Map<String, Long>, limit: Long, since: String? = null): Set<String> {
        // the last year or month: by the date in the path alone ("yyyy-mm-dd hhmm" sorts as text)
        if (since != null) return files.keys.filterTo(HashSet()) { !dated(it) || DATED.find(it)!!.groupValues[1] >= since }
        if (limit < 0) return files.keys
        val keep = files.keys.filterTo(HashSet()) { !dated(it) }
        var left = limit
        val (attachments, bodies) = files.keys.filter(::dated).partition { ".files/" in it }
        for (group in listOf(bodies, attachments)) for (rel in group.sortedByDescending { DATED.find(it)!!.groupValues[1] }) {
            // an attachment only beside its own mail: one huge mail stopping the mails early must not leave older mails'
            // attachments here without them ("only attachments, no mails")
            if (group === attachments && rel.substringBefore(".files/") + ".eml" !in keep) continue
            val size = files.getValue(rel)
            if (size > left) break // oldest dropped first: nothing older than the first that does not fit
            keep += rel; left -= size
        }
        return keep
    }

    /** Whether to take [rel] when the PC offers it: the part's own files; mail only in copy mode and within [keep]. */
    fun take(rel: String, storage: MailStorage, keep: Set<String>) = !rel.startsWith(PART) || rel.endsWith(".failed.json") ||
        (!outgoing(rel) && (!dated(rel) || (storage.mode == MailStorage.COPY && rel in keep)))

    /** With Mail off for this device: the PC's mail it holds (the PC's files, and mail of accounts it does not fetch itself). */
    fun pcMail(rel: String, me: String, own: Set<String>) = mirrored(rel, me) ||
        (rel.startsWith(PART) && dated(rel) && !outgoing(rel) && !rel.startsWith(OUTBOX) && rel.removePrefix(PART).substringBefore('/') !in own)

    /** A mail file this device may let go of: outside [keep], held by the PC ([held]), and not opened here in the last week. */
    fun drop(rel: String, file: File, keep: Set<String>, held: Boolean) = rel.startsWith(PART) && !outgoing(rel) && !rel.startsWith(OUTBOX) &&
        dated(rel) && rel !in keep && held && file.lastModified() < System.currentTimeMillis() - 7 * 86_400_000L
}

/**
 * One device's mail storage, set on the PC's device card, the hub as for files and photos (MAIL_PLAN §14, §16; the user,
 * 31 Oct): Mail/devices/<device>.json = { device, name, mode: "copy" | "move", limit: bytes (-1 everything), off,
 * keepDays (365, 30 or null), auto, setAt, setBy }, written by the PC and only read here; Mail/devices/<device>.usage.json
 * = { used, files, at }, written only by the device. Copy: the device holds the newest mail within what it keeps, filled
 * from the PC too. Move: it takes nothing from the PC, and lets go of what it fetched itself once the PC holds it, keeping
 * what fits. Off: nothing of Mail crosses, and the PC's mail is not kept here. What it keeps: the last [keepDays] days,
 * or with [auto] everything (as photos and files), else [limit] bytes.
 */
internal data class MailStorage(val device: String, val name: String = device, val mode: String = COPY, val limit: Long = DEFAULT,
    val setAt: Long = 0, val setBy: String = "", val off: Boolean = false, val keepDays: Int? = null, val auto: Boolean = false) {

    /** Bytes to keep (-1 all): Automatic keeps everything (the PC suggests a Move at 90 % full). */
    fun budget(): Long = if (auto) -1 else limit
    /** The oldest date kept ("yyyy-mm-dd 0000"), when kept by days. */
    fun since(now: java.time.LocalDate = java.time.LocalDate.now()): String? = keepDays?.let { "${now.minusDays(it.toLong())} 0000" }

    companion object {
        const val COPY = "copy"; const val MOVE = "move"
        const val GB = 1_000_000_000L // as Android and the PC show sizes
        const val DEFAULT = GB
        val LIMITS = listOf(GB / 2, GB, 2 * GB, 5 * GB, 10 * GB, -1L)
        private fun dir(root: File) = File(root, "devices")
        private fun of(o: JSONObject, device: String) = MailStorage(o.optString("device", device), o.optString("name", device),
            o.optString("mode", COPY).takeIf { it == COPY || it == MOVE } ?: COPY, o.optLong("limit", DEFAULT), o.optLong("setAt"), o.optString("setBy"),
            o.optBoolean("off"), o.optInt("keepDays", 0).takeIf { it > 0 && !o.isNull("keepDays") }, o.optBoolean("auto"))

        fun read(root: File, device: String): MailStorage = runCatching { of(JSONObject(File(dir(root), "$device.json").readText()), device) }.getOrDefault(MailStorage(device))
        /** Every device that has a setting or has said how much it uses. */
        fun all(root: File): List<MailStorage> = dir(root).listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .map { it.name.removeSuffix(".json").removeSuffix(".usage") }.distinct().sorted().map { read(root, it) }

        /** (bytes, files) a device said it holds, or null. */
        fun usage(root: File, device: String): Pair<Long, Int>? = runCatching { JSONObject(File(dir(root), "$device.usage.json").readText()).let { it.getLong("used") to it.getInt("files") } }.getOrNull()

        /** What Mail holds on this device now: every file of the part but the devices' own. */
        fun measure(root: File): Pair<Long, Int> {
            var bytes = 0L; var n = 0
            root.walkTopDown().filter { it.isFile && !it.name.startsWith(".") && it.parentFile != dir(root) }.forEach { bytes += it.length(); n++ }
            return bytes to n
        }

        /** This device's usage file, written again only when it changed by a megabyte or more (each write is a file to sync). */
        fun report(root: File, device: String) {
            val (used, files) = measure(root)
            val was = usage(root, device)
            if (was != null && kotlin.math.abs(was.first - used) < (1 shl 20) && was.second == files) return
            val d = dir(root).apply { mkdirs() }
            val part = File(d, ".$device.usage.json.part").apply { writeText(JSONObject().put("used", used).put("files", files).put("at", System.currentTimeMillis()).toString()) }
            check(part.renameTo(File(d, "$device.usage.json")))
        }
    }
}

/**
 * The phone's changes, carried out by the computer on the server (step 1's format, MAIL_PLAN §11): one file per action,
 * Mail/requests/<device>-<ms>.json = { op, ids, to (a move's role), at, device }. Sync takes it there and drops it here.
 */
internal object MailRequests {
    val OPS = setOf("read", "unread", "favorite", "unfavorite", "archive", "delete", "move", "spam", "notSpam")

    fun write(root: File, device: String, op: String, ids: Collection<String>, to: String? = null, now: Long = System.currentTimeMillis()): File {
        require(op in OPS && ids.isNotEmpty() && (op != "move" || to in MailFiles.ROLES))
        return put(root, device, now) { JSONObject().put("op", op).put("ids", JSONArray(ids.toList())).apply { if (op == "move") put("to", to) } }
    }

    /** A PC account's own name, given here (the user, 11 Nov: the university's could not be named from the phone): the PC writes it in accounts.json. */
    fun rename(root: File, device: String, account: String, name: String, now: Long = System.currentTimeMillis()): File {
        require(account.isNotBlank() && name.isNotBlank())
        return put(root, device, now) { JSONObject().put("op", "rename").put("account", account).put("name", name.trim().take(40)) }
    }

    private fun put(root: File, device: String, now: Long, body: () -> JSONObject): File {
        val dir = File(root, "requests").apply { mkdirs() }
        var at = now
        while (File(dir, "$device-$at.json").exists()) at++
        val json = body().put("at", at).put("device", device)
        val part = File(dir, ".$device-$at.json.part").apply { writeText(json.toString()) } // a dot file: sync never takes it half written
        return File(dir, "$device-$at.json").also { check(part.renameTo(it)) }
    }

    /** The mail as it will be once the computer has done [op]; null: it leaves this folder. */
    fun applied(e: MailEntry, op: String): MailEntry? = when (op) {
        "read" -> e.copy(seen = true); "unread" -> e.copy(seen = false)
        "favorite" -> e.copy(flagged = true); "unfavorite" -> e.copy(flagged = false)
        else -> null
    }
}

/**
 * Signatures, shared by every device (step 1's Mail/signatures.json, MAIL_PLAN §12): { "<address>": { html, newMail,
 * replies, position } }. Edited here as plain text; the HTML the PC wrote is shown as its text.
 */
internal object MailSignatures {
    private fun file(root: File) = File(root, "signatures.json")
    private fun all(root: File) = runCatching { JSONObject(file(root).readText()) }.getOrDefault(JSONObject())
    /** The signature as stored: HTML. */
    fun html(root: File, address: String): String = all(root).optJSONObject(address.lowercase())?.optString("html").orEmpty()
    /** Whether it is more than text and line breaks (a designed signature: shown and edited as HTML). */
    fun designed(html: String) = Regex("<(div|p|span|a|img|table|tr|td|b|i|u|strong|em|h[1-6]|font|ul|ol|li|style|center|hr|html|body)\\b[^>]*>", RegexOption.IGNORE_CASE).containsMatchIn(html)
    /** The same as plain text, for a text/plain copy and the preview. */
    fun text(root: File, address: String): String = plain(html(root, address))
    fun plain(html: String) = html.replace(Regex("<(style|script|title)[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<br\\s*/?>|</p>|</div>|</h\\d>|</tr>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]+>"), "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&nbsp;", " ").replace("&amp;", "&")
        .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    /** [input] kept as HTML when it is HTML (a pasted page: only what is in its body), else as text with its lines. */
    fun stored(input: String): String = if (!designed(input)) escaped(input) else
        (Regex("<body[^>]*>([\\s\\S]*)</body>", RegexOption.IGNORE_CASE).find(input)?.groupValues?.get(1) ?: input).trim()
    /** Whether the signature already begins with the "-- " line, so none is added. */
    fun dashed(html: String) = plain(html).startsWith("--")
    fun set(root: File, address: String, input: String) {
        val all = all(root)
        val was = all.optJSONObject(address.lowercase()) ?: JSONObject().put("newMail", true).put("replies", true).put("position", "above")
        all.put(address.lowercase(), was.put("html", stored(input)))
        val part = File(root, ".signatures.json.part").apply { parentFile?.mkdirs(); writeText(all.toString(2)) }
        check(part.renameTo(file(root)))
    }
    /** Plain text as the HTML of a mail's body: escaped, its lines kept. */
    fun escaped(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")
}

/**
 * Conversations (the user, 31 Oct: "every email stacks below the other like chat threads"): the mails of one account with
 * the same subject once Re:/Fwd: are taken off, from every folder but Spam and Trash.
 */
internal object MailThreads {
    // shortcut: by subject, as Gmail's own grouping; In-Reply-To/References would be exact but the index does not carry them
    fun key(e: MailEntry) = e.account + "\n" + MailView.bare(e.subject)
    fun of(mails: List<MailEntry>): Map<String, List<MailEntry>> = mails.filter { it.folder != "Spam" && it.folder != "Trash" }.groupBy(::key)
        .mapValues { (_, l) -> l.distinctBy { it.messageId ?: it.id }.sortedBy { it.date } } // the same mail in Inbox and Archive once

    private val QUOTE_HEAD = Regex("^(On .+ wrote:|Στις .+ έγραψε:|Am .+ schrieb .+:|Le .+ a écrit :|-{2,} ?(Original Message|Forwarded message|Προωθημένο μήνυμα).*|-- ?)$", RegexOption.IGNORE_CASE)
    /** What one message says itself: its text up to what it quotes ("Στις … έγραψε:", "> " lines) or its signature. */
    fun own(text: String): String {
        val lines = text.replace("\r", "").lines()
        val cut = lines.indexOfFirst { QUOTE_HEAD.matches(it.trim()) || it.startsWith(">") }
        val kept = (if (cut > 0) lines.take(cut) else lines).joinToString("\n").trim()
        return kept.ifEmpty { text.trim() }
    }
}

/** The Filter modal's choices (MAIL_PLAN §9), the computer's mailView.ts: quick filters on top, sorting and grouping below. */
internal data class MailView(
    val unread: Boolean = false, val favorites: Boolean = false, val attachments: Boolean = false, val orange: Boolean = false,
    val accounts: Set<String> = emptySet(), val sort: String = "date", val descending: Boolean = true, val group: String = "day",
    val unreadFirst: Boolean = false, val favoritesFirst: Boolean = false, val attachmentsFirst: Boolean = false,
) {
    fun json(): String = JSONObject().put("unread", unread).put("favorites", favorites).put("attachments", attachments).put("orange", orange)
        .put("accounts", JSONArray(accounts.toList())).put("sort", sort).put("descending", descending).put("group", group)
        .put("unreadFirst", unreadFirst).put("favoritesFirst", favoritesFirst).put("attachmentsFirst", attachmentsFirst).toString()

    companion object {
        val SORTS = listOf("date", "from", "subject", "size", "account")
        val GROUPS = listOf("day", "from", "account", "none")
        fun of(json: String?): MailView = json?.let { s -> runCatching { JSONObject(s).let { o -> MailView(o.optBoolean("unread"), o.optBoolean("favorites"),
            o.optBoolean("attachments"), o.optBoolean("orange"), o.optJSONArray("accounts")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty(),
            o.optString("sort", "date").takeIf { it in SORTS } ?: "date", o.optBoolean("descending", true), o.optString("group", "day").takeIf { it in GROUPS } ?: "day",
            o.optBoolean("unreadFirst"), o.optBoolean("favoritesFirst"), o.optBoolean("attachmentsFirst")) } }.getOrNull() } ?: MailView()

        /** "Re: Fwd: Θέμα" sorts as "Θέμα". */
        private val PREFIX = Regex("^\\s*((re|fwd?|aw|απ|σχετ|προωθ)\\s*:\\s*)+", RegexOption.IGNORE_CASE)
        fun bare(s: String) = s.replace(PREFIX, "").trim().lowercase()
        fun dayKey(ms: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()
    }

    /** The mails this view keeps, sorted, in groups (in the order their first mail comes). */
    fun arrange(mails: List<MailEntry>, query: String = ""): List<Pair<String, List<MailEntry>>> {
        val q = query.trim().lowercase()
        val kept = mails.filter { m -> (!unread || !m.seen) && (!favorites || m.flagged) && (!attachments || m.attachments > 0) && (!orange || !m.saved)
            && (accounts.isEmpty() || m.account in accounts) && (q.isEmpty() || "${m.from} ${m.fromAddress} ${m.subject} ${m.snippet}".lowercase().contains(q)) }
        val key: Comparator<MailEntry> = when (sort) {
            "from" -> compareBy { it.from.ifEmpty { it.fromAddress }.lowercase() }
            "subject" -> compareBy { bare(it.subject) }
            "size" -> compareBy { it.size }
            "account" -> compareBy { it.account }
            else -> compareBy { it.date }
        }
        var order = compareBy<MailEntry> { if (unreadFirst) it.seen else false }
            .thenBy { if (favoritesFirst) !it.flagged else false }
            .thenBy { if (attachmentsFirst) it.attachments == 0 else false }
            .then(if (descending) key.reversed() else key)
        order = order.thenByDescending { it.date }
        val group: (MailEntry) -> String = when (group) {
            "from" -> { m -> m.from.ifEmpty { m.fromAddress } }
            "account" -> { m -> m.account }
            "none" -> { _ -> "" }
            else -> { val zone = java.time.ZoneId.systemDefault(); { m -> dayKey(m.date, zone) } } // the zone once, not for each of thousands
        }
        return kept.sortedWith(order).groupBy(group).toList()
    }
}

/** Settings › Αλληλογραφία's switches (MAIL_PLAN §9c) and the Filter modal's views kept per folder; this device's own. */
internal object MailPrefs {
    private fun p(c: Context) = c.getSharedPreferences("mail", Context.MODE_PRIVATE)
    fun remote(c: Context) = p(c).getBoolean("remote", false)
    fun setRemote(c: Context, on: Boolean) = p(c).edit().putBoolean("remote", on).apply()
    /** Undo send: how many seconds Send waits, its button a countdown that takes the mail back (5, 3 or 0; the user, 4 Nov). */
    fun undo(c: Context) = p(c).getInt("undo", 5)
    fun setUndo(c: Context, seconds: Int) = p(c).edit().putInt("undo", seconds).apply()
    fun allowed(c: Context): Set<String> = p(c).getStringSet("allowed", emptySet())!!
    fun allow(c: Context, sender: String, on: Boolean = true) = p(c).edit().putStringSet("allowed", if (on) allowed(c) + sender.lowercase() else allowed(c) - sender).apply()
    fun switch(c: Context, name: String) = p(c).getBoolean(name, true) // tracking, links, risky, realAddress: on by default
    fun setSwitch(c: Context, name: String, on: Boolean) = p(c).edit().putBoolean(name, on).apply()
    /** An account's trouble on this device's last look (auth, appPassword, unreachable, other), or null. */
    fun state(c: Context, id: String): String? = p(c).getString("state.$id", null)
    fun setState(c: Context, id: String, reason: String?) = p(c).edit().apply { if (reason == null) remove("state.$id") else putString("state.$id", reason) }.apply()
    /**
     * «Έλεγχος για νέα», one for every device (the desktop's Mail/settings.json, `check`: "push", 5, 15, 30 or "manual"):
     * [NOW] (IDLE while Mail is open, every 15 minutes otherwise), every 5, 15 or 30 minutes, or [MANUAL].
     */
    const val NOW = 0; const val MANUAL = -1
    val EVERY = listOf(NOW, 5, 15, 30, MANUAL)
    @Suppress("UNUSED_PARAMETER") fun every(c: Context) = every(mailRoot())
    fun every(root: File): Int = when (val v = runCatching { JSONObject(File(root, "settings.json").readText()).get("check") }.getOrNull()) {
        "manual" -> MANUAL; 5, 15, 30 -> v as Int; else -> NOW
    }
    fun setEvery(v: Int, root: File = mailRoot(), by: String = MailEngine.device()) {
        val f = File(root, "settings.json")
        val o = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject()) // the other keys are kept
        o.put("check", when (v) { NOW -> "push"; MANUAL -> "manual"; else -> v }).put("setAt", System.currentTimeMillis()).put("setBy", by)
        root.mkdirs()
        check(File(root, ".settings.json.part").apply { writeText(o.toString(2)) }.renameTo(f))
    }
    fun receipts(c: Context) = p(c).getInt("receipts", 1) // 0 never, 1 ask, 2 always
    fun setReceipts(c: Context, v: Int) = p(c).edit().putInt("receipts", v).apply()
    /** A folder's kept view ("Κράτησε για αυτόν τον φάκελο"), or null. */
    fun view(c: Context, folder: String) = p(c).getString("view.$folder", null)?.let(MailView::of)
    fun setView(c: Context, folder: String, v: MailView?) = p(c).edit().apply { if (v == null) remove("view.$folder") else putString("view.$folder", v.json()) }.apply()
}

/**
 * Enough of MIME (RFC 2045–2047) to show a mail the computer saved: its HTML or text, the pictures inside it, To and Cc.
 * Its attachments are not decoded here: the computer wrote them beside it as ordinary files.
 */
internal object Mime {
    /** [attachments]: the names the computer gave them on disk (its .files folder), in order. */
    class Message(val html: String?, val text: String?, val to: String, val cc: String, val headers: Map<String, String>, val attachments: List<String> = emptyList())

    private class Part(val headers: Map<String, String>, val body: ByteArray) {
        val type = headers["content-type"].orEmpty().substringBefore(';').trim().lowercase().ifEmpty { "text/plain" }
        fun param(name: String) = Mime.param(headers["content-type"].orEmpty(), name)
        val attachment get() = headers["content-disposition"].orEmpty().trim().lowercase().startsWith("attachment")
        fun bytes(): ByteArray = when (headers["content-transfer-encoding"].orEmpty().trim().lowercase()) {
            "base64" -> runCatching { java.util.Base64.getMimeDecoder().decode(body) }.getOrDefault(ByteArray(0))
            "quoted-printable" -> quoted(body, false)
            else -> body
        }
        fun text() = String(bytes(), charset(param("charset")))
        val filename get() = extended(headers["content-disposition"].orEmpty(), "filename") ?: param(headers["content-disposition"].orEmpty(), "filename")?.let(::words)
            ?: extended(headers["content-type"].orEmpty(), "name") ?: param(headers["content-type"].orEmpty(), "name")?.let(::words)
    }

    private class Parts(val top: Part, val leaves: List<Part>) {
        val htmlPart = leaves.firstOrNull { it.type == "text/html" && !it.attachment }
        val textPart = leaves.firstOrNull { it.type == "text/plain" && !it.attachment }
        // what is not the text, nor a picture the HTML shows (mailparser's "related", which the computer leaves out)
        val files by lazy { val h = htmlPart?.text(); leaves.filter { p -> p !== htmlPart && p !== textPart && (p.attachment || p.filename != null) &&
            p.headers["content-id"]?.let { h?.contains("cid:" + it.trim().removePrefix("<").removeSuffix(">")) } != true } }
    }
    private fun parts(raw: ByteArray): Parts {
        val top = part(raw)
        val leaves = mutableListOf<Part>()
        fun walk(p: Part) { if (p.type.startsWith("multipart/")) p.param("boundary")?.let { b -> split(p.body, b).forEach { walk(part(it)) } } else leaves += p }
        walk(top)
        return Parts(top, leaves)
    }

    /** The attachments as the computer writes them into the .files folder: their names on disk and their bytes. */
    fun attachments(raw: ByteArray): List<Pair<String, ByteArray>> = parts(raw).files.let { f -> diskNames(f.map { it.filename }).zip(f.map { it.bytes() }) }

    fun read(raw: ByteArray): Message {
        val ps = parts(raw)
        val top = ps.top; val leaves = ps.leaves
        val htmlPart = ps.htmlPart
        val textPart = ps.textPart
        val html = htmlPart?.text()?.let { h ->
            // pictures inside the mail (cid:) as data: URLs, so nothing is fetched for them
            leaves.filter { it.type.startsWith("image/") && it.headers["content-id"] != null }.fold(h) { acc, img ->
                val cid = img.headers["content-id"]!!.trim().removePrefix("<").removeSuffix(">")
                acc.replace("cid:$cid", "data:${img.type};base64,${java.util.Base64.getEncoder().encodeToString(img.bytes())}")
            }
        }
        val text = textPart?.text()
        val files = ps.files
        return Message(html, text, words(top.headers["to"].orEmpty()), words(top.headers["cc"].orEmpty()), top.headers, diskNames(files.map { it.filename }))
    }

    /** A mail's top headers only (unfolded, names in lower case, the first of each). */
    fun headers(raw: ByteArray): Map<String, String> = part(raw).headers

    /** Headers (unfolded, names in lower case, the first of each) and the body after the empty line. */
    private fun part(raw: ByteArray): Part {
        val s = String(raw, Charsets.ISO_8859_1) // one char per byte: cut here, the body's bytes stay as they were
        val end = Regex("\r?\n\r?\n").find(s)
        val head = if (end == null) s else s.substring(0, end.range.first)
        val body = if (end == null) ByteArray(0) else s.substring(end.range.last + 1).toByteArray(Charsets.ISO_8859_1)
        val headers = LinkedHashMap<String, String>()
        String(head.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8).replace(Regex("\r?\n[ \t]+"), " ").lines().forEach { line ->
            val i = line.indexOf(':')
            if (i > 0) headers.putIfAbsent(line.substring(0, i).trim().lowercase(), line.substring(i + 1).trim())
        }
        return Part(headers, body)
    }

    private fun split(body: ByteArray, boundary: String): List<ByteArray> {
        val s = String(body, Charsets.ISO_8859_1)
        val parts = s.split(Regex("(^|\r?\n)--" + Regex.escape(boundary) + "(--)?[ \t]*(?=\r?\n|$)"))
        val closed = s.contains("--$boundary--") // then the last piece is the epilogue after it
        return parts.drop(1).let { if (closed) it.dropLast(1) else it }.map { it.removePrefix("\r\n").removePrefix("\n").toByteArray(Charsets.ISO_8859_1) }
    }

    fun param(header: String, name: String): String? = Regex(";\\s*" + Regex.escape(name) + "\\s*=\\s*(\"([^\"]*)\"|[^;\\s]+)", RegexOption.IGNORE_CASE)
        .find(header)?.let { m -> m.groups[2]?.value ?: m.groupValues[1] }

    /** RFC 2231's name*=utf-8''%CE%91… (and its name*0*=, name*1*= pieces), as mail programs send non-English names. */
    private fun extended(header: String, name: String): String? {
        val pieces = Regex(";\\s*" + Regex.escape(name) + "\\*(\\d+)?\\*?\\s*=\\s*(\"([^\"]*)\"|[^;\\s]+)", RegexOption.IGNORE_CASE).findAll(header)
            .sortedBy { it.groupValues[1].toIntOrNull() ?: 0 }.map { it.groups[3]?.value ?: it.groupValues[2] }.toList()
        if (pieces.isEmpty()) return null
        val all = pieces.joinToString("")
        val m = Regex("^([^']*)'[^']*'(.*)$", RegexOption.DOT_MATCHES_ALL).find(all) ?: return all
        val out = java.io.ByteArrayOutputStream()
        val v = m.groupValues[2]; var i = 0
        while (i < v.length) {
            val hex = if (v[i] == '%' && i + 2 < v.length) v.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (hex != null) { out.write(hex); i += 3 }
            else { out.write(v[i].code); i++ }
        }
        return String(out.toByteArray(), charset(m.groupValues[1].ifEmpty { null }))
    }

    /** The computer's clean(): a name every file system takes, at most [bytes] bytes of UTF-8 (its desktop/mail.js). */
    fun clean(s: String?, bytes: Int): String {
        var out = (s ?: "").replace(Regex("[/\\\\\u0000:*?\"<>|\r\n\t]"), " ").replace(Regex("\\s+"), " ").trim().replace(Regex("^\\.+"), "")
        // whole characters only: half an emoji became "?" here and a broken one on the PC, two names for one mail (31 Oct)
        while (out.toByteArray().size > bytes) out = out.dropLast(if (out.length >= 2 && out.last().isLowSurrogate()) 2 else 1)
        return out.trim()
    }

    /** The attachments' names on disk as the computer's store() makes them: cleaned, "attachment" when nameless, " (2)" for a repeat. */
    fun diskNames(names: List<String?>): List<String> {
        val taken = HashSet<String>()
        return names.map { name ->
            val raw = clean(name, 150).ifEmpty { "attachment" }
            val dot = raw.lastIndexOf('.')
            var n = raw; var i = 2
            while (n.lowercase() in taken) { n = if (dot > 0) "${raw.substring(0, dot)} ($i)${raw.substring(dot)}" else "$raw ($i)"; i++ }
            taken += n.lowercase(); n
        }
    }

    private fun charset(name: String?): Charset = name?.let { runCatching { Charset.forName(it.trim()) }.getOrNull() } ?: Charsets.UTF_8

    /** Quoted-printable; [header]: the Q of encoded words, where _ is a space. */
    fun quoted(b: ByteArray, header: Boolean): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            val c = b[i].toInt().toChar()
            when {
                c == '=' && i + 1 < b.size && (b[i + 1] == '\n'.code.toByte()) -> i += 2
                c == '=' && i + 2 < b.size && b[i + 1] == '\r'.code.toByte() && b[i + 2] == '\n'.code.toByte() -> i += 3
                c == '=' && i + 2 < b.size && "${b[i + 1].toInt().toChar()}${b[i + 2].toInt().toChar()}".toIntOrNull(16) != null -> {
                    out.write("${b[i + 1].toInt().toChar()}${b[i + 2].toInt().toChar()}".toInt(16)); i += 3
                }
                header && c == '_' -> { out.write(' '.code); i++ }
                else -> { out.write(b[i].toInt()); i++ }
            }
        }
        return out.toByteArray()
    }

    private val WORD = Regex("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=")
    /** RFC 2047's encoded words (=?utf-8?B?…?=) in a header, the space between two of them dropped. */
    fun words(s: String): String = WORD.replace(s.replace(Regex("(\\?=)\\s+(=\\?)"), "$1$2")) { m ->
        val bytes = if (m.groupValues[2].equals("b", true)) runCatching { java.util.Base64.getDecoder().decode(m.groupValues[3]) }.getOrDefault(ByteArray(0))
            else quoted(m.groupValues[3].toByteArray(Charsets.ISO_8859_1), true)
        String(bytes, charset(m.groupValues[1].substringBefore('*')))
    }

    /** Text as HTML for the mail's page: escaped, its lines kept. */
    fun textAsHtml(t: String) = "<pre style=\"white-space:pre-wrap;font-family:sans-serif\">" +
        t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</pre>"

    /** Whether the HTML would load anything from the web (pictures, fonts, styles). */
    fun remote(html: String) = Regex("(src|background)\\s*=\\s*[\"']?\\s*(https?:)?//|url\\(\\s*[\"']?\\s*(https?:)?//|<link\\b", RegexOption.IGNORE_CASE).containsMatchIn(html)

    /** Tracking pixels (a picture 0 or 1 pixel wide or high) taken out, for when pictures load. */
    fun withoutPixels(html: String) = html.replace(Regex("<img\\b[^>]*\\b(width|height)\\s*=\\s*[\"']?[01](px)?[\"'\\s/>][^>]*>", RegexOption.IGNORE_CASE), "")

    /** Attachments Tetra warns about before opening (MAIL_PLAN §9c): programs, scripts, Office files with macros. */
    fun risky(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("exe", "msi", "bat", "cmd", "com", "scr", "js", "jse", "vbs", "vbe",
        "ps1", "jar", "apk", "lnk", "hta", "docm", "xlsm", "pptm", "dotm", "xltm")
}
