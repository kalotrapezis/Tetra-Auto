package com.kalotrapezis.drive

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A map as one file, <id>.tetramap, for a device that does not sync: the car's head unit gets its maps from the phone
 * (the user, 10 Nov). A ZIP whose first entry is the manifest:
 *
 *   tetramap.json  {"format":1,"id":"greece","name":"Greece","built":"2026-10-21","bounds":[w,s,e,n],
 *                   "files":[{"path":"greece.pmtiles","size":508325761,"sha256":"…"},…]}
 *
 * then the map's files under their paths in the Maps folder, as [files] finds them: <id>.pmtiles (stored: it is compressed
 * already), <id>.json, <id>-gh/… (the routing graph), <id>-<kind>.tsv (search lists, lanes) and each <a>_<b>-crossings.tsv
 * it is in. An import is a file from another device: every path is checked to be one of the map's own, every file's size
 * and SHA-256 against the manifest, in a hidden folder, and only then is the map put in place (an older one of the same
 * id replaced). Copied as it is into the car app: MapsFiles, WriterLog and the JDK only, no Android UI.
 */
internal object MapsArchive {
    const val FORMAT = 1
    const val EXTENSION = "tetramap"
    const val MIME = "application/x-tetramap"
    private const val MANIFEST = "tetramap.json"
    private const val MANIFEST_MAX = 4 shl 20
    private const val TEMP = ".tetramap-"

    class Item(val path: String, val size: Long, val sha256: String)
    class Manifest(val format: Int, val id: String, val name: String, val built: String, val bounds: DoubleArray?, val files: List<Item>) {
        val bytes get() = files.sumOf { it.size }
    }

    private val ID = Regex("[a-z0-9]+(-[a-z0-9]+)*")
    private val SHA = Regex("[0-9a-f]{64}")
    /** A map's own files, as [MapsFiles.removeMap] finds them. */
    private fun own(id: String) = Regex(Regex.escape(id) + "(\\.pmtiles|\\.json|-gh|-[a-z]+\\.tsv)")
    private val PAIR = Regex("([a-z0-9-]+)_([a-z0-9-]+)-crossings\\.tsv")
    private fun crossing(id: String, name: String) = PAIR.matchEntire(name)?.groupValues?.let { id == it[1] || id == it[2] } == true

    /** The files of map [id], relative to [root]: its own and the border crossings it is in (an older builder's pair graphs are not used). */
    fun files(root: File, id: String): List<String> = root.list().orEmpty().sorted().flatMap { n ->
        val f = File(root, n)
        when {
            n.endsWith("-gh") && own(id).matches(n) && f.isDirectory -> f.list().orEmpty().filter { !it.startsWith(".") && File(f, it).isFile }.sorted().map { "$n/$it" }
            (own(id).matches(n) || crossing(id, n)) && f.isFile -> listOf(n)
            else -> emptyList()
        }
    }

    /** Whether [path] from an archive of map [id] is one of its files: nothing outside the Maps folder, nothing of another map's. */
    fun allowed(id: String, path: String): Boolean {
        if ('\\' in path || ':' in path || '\u0000' in path || path.startsWith("/")) return false
        val parts = path.split('/')
        if (parts.any { it.isEmpty() || it.startsWith(".") }) return false // ".", ".." and hidden files
        val top = parts[0]
        return if (top.endsWith("-gh")) parts.size == 2 && own(id).matches(top) else parts.size == 1 && (own(id).matches(top) || crossing(id, top))
    }

    /** Map [id] of [root] into [out] (left open), [progress] 0–1. */
    fun export(root: File, id: String, out: OutputStream, progress: (Float) -> Unit = {}) {
        val pack = MapsFiles.maps(root).firstOrNull { it.id == id } ?: error("There is no map $id.")
        val paths = files(root, id)
        val meter = Meter(paths.sumOf { File(root, it).length() } * 2, progress) // each file read twice: hashed first, as the manifest goes first
        WriterLog.i("Maps: exporting $id, ${paths.size} files, ${meter.total / 2_000_000} MB")
        val crcs = HashMap<String, Long>()
        val items = paths.map { p ->
            val crc = CRC32()
            val (size, sha) = read(File(root, p).inputStream(), meter) { b, n -> crc.update(b, 0, n) }
            crcs[p] = crc.value
            Item(p, size, sha)
        }
        val m = Manifest(FORMAT, id, pack.name, MapsFiles.built(root, id).toString(), pack.bounds, items)
        val zip = ZipOutputStream(out)
        zip.putNextEntry(ZipEntry(MANIFEST))
        zip.write(json(m).toByteArray())
        zip.closeEntry()
        for (item in items) {
            val f = File(root, item.path)
            zip.putNextEntry(ZipEntry(item.path).apply {
                time = f.lastModified() // Greece's older pack has no <id>.json: its file's date is the day it was built
                if (item.path.endsWith(".pmtiles")) { method = ZipEntry.STORED; size = item.size; compressedSize = item.size; crc = crcs.getValue(item.path) }
            })
            val (size, sha) = read(f.inputStream(), meter) { b, n -> zip.write(b, 0, n) }
            check(size == item.size && sha == item.sha256) { "${item.path} changed while it was exported." }
            zip.closeEntry()
        }
        zip.finish(); zip.flush()
        WriterLog.i("Maps: exported $id")
    }

    /** The manifest of the archive in [input], read and checked; nothing written. */
    fun peek(input: InputStream): Manifest = manifest(ZipInputStream(input))

    /** The map in [input] into [root], in place only once every file is checked; its id. */
    fun import(root: File, input: InputStream, progress: (Float) -> Unit = {}): String {
        root.mkdirs()
        root.listFiles().orEmpty().filter { it.name.startsWith(TEMP) }.forEach { WriterLog.w("Maps: an earlier import's leftovers removed, ${it.name}"); it.deleteRecursively() }
        val temp = File(root, TEMP + System.currentTimeMillis())
        try {
            val zip = ZipInputStream(input)
            val m = manifest(zip)
            WriterLog.i("Maps: importing ${m.id} (${m.name}, built ${m.built}), ${m.files.size} files, ${m.bytes / 1_000_000} MB")
            check(root.usableSpace >= m.bytes) { "Not enough space: the map needs ${m.bytes / 1_000_000} MB." }
            check(temp.mkdirs()) { "Could not make ${temp.name}." }
            val want = m.files.associateBy { it.path }
            val got = HashSet<String>()
            val meter = Meter(m.bytes, progress)
            while (true) {
                val e = zip.nextEntry ?: break
                val item = want[e.name] ?: error("${e.name} is not in the manifest.")
                check(got.add(e.name)) { "${e.name} is in the file twice." }
                val f = File(temp, e.name)
                check(f.canonicalPath.startsWith(temp.canonicalPath + File.separator)) { "${e.name} is outside the map." }
                f.parentFile!!.mkdirs()
                val (size, sha) = f.outputStream().use { o ->
                    read(zip, meter, close = false) { b, n -> check(meter.entry <= item.size) { "${e.name} is larger than its manifest says." }; o.write(b, 0, n) }
                }
                check(size == item.size) { "${e.name} is ${size} bytes, its manifest says ${item.size}." }
                check(sha == item.sha256) { "${e.name} is damaged: its SHA-256 is not the manifest's." }
                if (e.time > 0) f.setLastModified(e.time)
            }
            (want.keys - got).firstOrNull()?.let { error("$it is missing.") }
            WriterLog.i("Maps: ${m.id}'s ${got.size} files checked")
            place(root, temp, m)
            WriterLog.i("Maps: imported ${m.id}")
            return m.id
        } catch (e: Throwable) {
            WriterLog.e("Maps: import failed", e)
            throw e
        } finally {
            temp.deleteRecursively()
        }
    }

    /** The checked files of [m] from [temp] into [root]: the old map's own files set aside first, back if a move fails. */
    private fun place(root: File, temp: File, m: Manifest) {
        val tops = m.files.map { it.path.substringBefore('/') }.distinct().sortedBy { it.endsWith(".pmtiles") } // the map shows when its .pmtiles is there: last
        // all of the old map's own files go (a newer map may have fewer lists); of its crossings only those coming new
        val old = root.list().orEmpty().filter { own(m.id).matches(it) || it in tops }.sortedByDescending { it.endsWith(".pmtiles") }
        if (old.isNotEmpty()) WriterLog.i("Maps: ${m.id} replaces ${old.size} older files")
        val aside = File(temp, ".old").apply { mkdirs() }
        val setAside = ArrayList<String>(); val placed = ArrayList<String>()
        try {
            for (n in old) { check(File(root, n).renameTo(File(aside, n))) { "Could not move the old $n." }; setAside += n }
            for (n in tops) { check(File(temp, n).renameTo(File(root, n))) { "Could not put $n in place." }; placed += n }
        } catch (e: Throwable) {
            WriterLog.e("Maps: ${m.id} not put in place, the old files back", e)
            placed.forEach { File(root, it).deleteRecursively() }
            setAside.forEach { if (!File(aside, it).renameTo(File(root, it))) WriterLog.e("Maps: the old $it not put back") }
            throw e
        }
    }

    private fun manifest(zip: ZipInputStream): Manifest {
        val e = zip.nextEntry ?: error("The file is empty.")
        check(e.name == MANIFEST) { "Not a Tetra map: its first entry is ${e.name}." }
        val bytes = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        while (true) { val n = zip.read(buf); if (n < 0) break; bytes.write(buf, 0, n); check(bytes.size() <= MANIFEST_MAX) { "The manifest is too large." } }
        val o = runCatching { JSONObject(bytes.toString(Charsets.UTF_8.name())) }.getOrElse { throw IllegalStateException("The manifest is unreadable.", it) }
        val format = o.optInt("format", -1)
        check(format == FORMAT) { "This map is in format $format; this Tetra reads format $FORMAT. Update Tetra." }
        val id = o.optString("id")
        check(ID.matches(id)) { "The map's id \"$id\" is not valid." }
        val a = o.optJSONArray("files") ?: error("The manifest lists no files.")
        val files = (0 until a.length()).map { i ->
            val f = a.getJSONObject(i)
            val path = f.getString("path"); val size = f.getLong("size"); val sha = f.getString("sha256")
            check(allowed(id, path)) { "$path is not a file of map $id." }
            check(size >= 0 && SHA.matches(sha)) { "$path's size or SHA-256 is not valid." }
            Item(path, size, sha)
        }
        check(files.map { it.path }.toSet().size == files.size) { "The manifest lists a file twice." }
        check(files.any { it.path == "$id.pmtiles" }) { "The file has no $id.pmtiles." }
        val bounds = o.optJSONArray("bounds")?.takeIf { it.length() == 4 }?.let { b -> DoubleArray(4) { b.getDouble(it) } }
        return Manifest(format, id, o.optString("name").ifEmpty { id }, o.optString("built"), bounds, files)
    }

    private fun json(m: Manifest): String = JSONObject().put("format", m.format).put("id", m.id).put("name", m.name).put("built", m.built)
        .apply { m.bounds?.let { b -> put("bounds", JSONArray().apply { b.forEach { put(it) } }) } }
        .put("files", JSONArray(m.files.map { JSONObject().put("path", it.path).put("size", it.size).put("sha256", it.sha256) }))
        .toString(1) + "\n"

    /** All of [input] through [each], counted on [meter]: its size and SHA-256. */
    private inline fun read(input: InputStream, meter: Meter, close: Boolean = true, each: (ByteArray, Int) -> Unit): Pair<Long, String> {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        meter.entry = 0
        try {
            while (true) {
                val n = input.read(buf); if (n < 0) break
                md.update(buf, 0, n); meter.add(n); each(buf, n)
            }
        } finally { if (close) input.close() }
        return meter.entry to md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Bytes done of [total], told to [progress] at each whole percent; [entry]: of the file being read. */
    private class Meter(val total: Long, val progress: (Float) -> Unit) {
        var done = 0L; var entry = 0L; private var shown = -1
        fun add(n: Int) {
            done += n; entry += n
            val p = if (total <= 0) 100 else (done * 100 / total).toInt().coerceAtMost(100)
            if (p != shown) { shown = p; progress(p / 100f) }
        }
    }
}
