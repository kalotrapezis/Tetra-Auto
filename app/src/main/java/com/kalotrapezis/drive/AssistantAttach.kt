package com.kalotrapezis.drive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/*
 * A file attached to AI Assist (the user, 1 Nov 2026: "the chat needs a send File button"; the desktop's attach.js, ported):
 * its text read here, in place, and what the model is given of it. A small file goes in once, whole; a larger one as its
 * name, pages, headings and first page, the rest read on demand (attachment_read). The text never stays in the chat's
 * history: AssistantChat takes it out after the turn.
 */
internal object Attach {
    const val MAX_BYTES = 50L * 1024 * 1024
    const val MAX_CHARS = 600_000 // ~150k tokens, a 300-page book; past it only the start is kept, and said
    const val INLINE = 32_000 // ~8,000 tokens: goes in whole
    const val PART = 6_000 // what one attachment_read gives
    private const val OCR_PAGES = 40 // a scan is read on the phone, seconds a page: past this only the start (shortcut: the computer's OCR when it is longer)

    /** A file's text as pages; [kind] text, docx, pptx, odf, pdf or image. */
    class Doc(val name: String, val path: String, val kind: String, val pages: List<String>, val headings: List<String>, val cut: Boolean, val scanned: Boolean = false) {
        val text = pages.joinToString("\n\n")
    }
    /** What a bubble and a kept chat hold of it: never its text. */
    data class Ref(val name: String, val path: String, val pages: Int)
    class Part(val text: String, val from: Int, val to: Int)

    private val KINDS = mapOf("txt" to "text", "md" to "text", "csv" to "text", "tsv" to "text", "docx" to "docx", "odt" to "docx", "pptx" to "pptx", "odp" to "odf",
        "pdf" to "pdf", "png" to "image", "jpg" to "image", "jpeg" to "image", "webp" to "image")
    fun kindOf(name: String) = KINDS[name.substringAfterLast('.', "").lowercase()]
    /** What Android's picker shows. */
    val TYPES = arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.oasis.opendocument.text",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/vnd.oasis.opendocument.presentation", "text/*", "image/png", "image/jpeg", "image/webp")

    private fun mdHeadings(text: String) = text.lines().filter { Regex("^#{1,3}\\s").containsMatchIn(it) }.map { it.replace(Regex("^#+\\s*"), "") }

    /**
     * The file's text. [pdf] gives a PDF's pages' words, [ocr] reads a scan or a picture (both the phone's: MuPDF and Tesseract);
     * a PDF with almost no text is a scan. Throws a sentence for the user when it cannot.
     */
    fun extract(file: File, pdf: ((File) -> List<String>)? = null, ocr: ((File, Int) -> List<String>)? = null): Doc {
        val kind = kindOf(file.name)
        need(kind != null, R.string.assistant_attach_unknown) { "This kind of file cannot be read." }
        need(file.length() <= MAX_BYTES, R.string.assistant_attach_too_big, (MAX_BYTES shr 20).toInt()) { "The file is over 50 MB." }
        var headings = emptyList<String>()
        var pages: List<String>? = when (kind) {
            "text" -> listOf(file.readText()).also { headings = mdHeadings(it[0]) }
            "pdf" -> pdf?.invoke(file) ?: emptyList()
            else -> null // a picture
        }
        val scanned = pages == null || (kind == "pdf" && pages.joinToString("").count { !it.isWhitespace() } < 20 * pages.size)
        var cut = false
        if (scanned) {
            need(ocr != null, R.string.assistant_attach_scan) { "It is a scan with no text in it." }
            WriterLog.i("AI Assist: ${file.name} is a scan or a picture: reading its letters")
            cut = kind == "pdf" && (pages?.size ?: 0) > OCR_PAGES
            pages = ocr!!(file, OCR_PAGES)
            headings = emptyList()
        }
        var total = 0
        val kept = pages!!.map { it.trim() }.filter { p -> if (total >= MAX_CHARS) { cut = true; false } else { total += p.length; true } }.toMutableList()
        if (total > MAX_CHARS) { cut = true; kept[kept.size - 1] = kept.last().dropLast(total - MAX_CHARS) }
        val d = Doc(file.name, file.path, kind!!, kept, headings.take(60), cut, scanned)
        need(d.text.isNotBlank(), R.string.assistant_attach_empty) { "The file has no text." }
        WriterLog.i("AI Assist: read ${file.name}: ${d.pages.size} page(s), ${d.text.length} characters${if (cut) ", cut" else ""}")
        return d
    }

    private fun unxml(s: String) = s.replace(Regex("&(#x[0-9a-fA-F]+|#\\d+|\\w+);")) { m -> val e = m.groupValues[1]
        when { e.startsWith("#x") || e.startsWith("#X") -> String(Character.toChars(e.drop(2).toInt(16))); e.startsWith("#") -> String(Character.toChars(e.drop(1).toInt()))
            else -> mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'")[e] ?: m.value } }
    private fun tags(s: String) = unxml(s.replace(Regex("<[^>]+>"), ""))


    /** The text in parts of about [PART] letters, cut at paragraphs; each knows its pages. */
    fun parts(d: Doc): List<Part> {
        val out = mutableListOf<Part>()
        var cur = StringBuilder(); var from = 1; var to = 1
        d.pages.forEachIndexed { i, page ->
            for (para in page.split(Regex("\n(?=\\S)"))) {
                if (cur.length + para.length > PART && cur.isNotEmpty()) { out += Part(cur.toString(), from, to); cur = StringBuilder(); from = i + 1 }
                if (cur.isNotEmpty()) cur.append('\n')
                cur.append(para); to = i + 1
            }
        }
        if (cur.isNotEmpty()) out += Part(cur.toString(), from, to)
        // a paragraph longer than a part (a PDF without line ends) cut by length
        return out.flatMap { p -> if (p.text.length <= PART * 1.5) listOf(p) else p.text.chunked(PART).map { Part(it, p.from, p.to) } }
    }

    private fun pagesSaid(d: Doc) = if (d.kind == "docx" || d.kind == "text") "" else ", ${d.pages.size} page(s)"

    /** What goes with the user's message, this turn only. */
    fun forModel(d: Doc): String {
        val head = "[Attached file: ${d.name}${pagesSaid(d)}${if (d.cut) "; only its first part was read (it is longer)" else ""}]"
        if (d.text.length <= INLINE) return "$head\n<<<\n${d.text}\n>>>"
        return "$head\nIt is long (${d.text.length} characters), so only its start is here. Read more with attachment_read: part 1–${parts(d).size}, or a query for the passages about something." +
            (if (d.headings.isNotEmpty()) "\nHeadings: ${d.headings.joinToString(" · ")}" else "") + "\nFirst page:\n<<<\n${d.pages[0].take(3000)}\n>>>"
    }
    /** What stays in the history instead. */
    fun kept(d: Doc) = "[Attached file: ${d.name}${pagesSaid(d)}; its text is not kept here: attachment_read reads it again]"

    private fun plain(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    /** One part, or the paragraphs that have most of the query's words (their first five letters: Greek endings change). */
    fun read(d: Doc, part: Int?, query: String?): String {
        val ps = parts(d)
        if (part != null && query.isNullOrBlank()) return ps.getOrNull(part - 1)?.let { "Part $part of ${ps.size} (pages ${it.from}–${it.to}):\n${it.text}" } ?: "No part $part: there are ${ps.size}."
        val words = plain(query.orEmpty()).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.map { it.take(5) }
        if (words.isEmpty()) return "Say a part (1–${ps.size}) or what to look for."
        val hits = ps.flatMapIndexed { n, p -> p.text.split(Regex("\n+")).map { para -> Triple(para, n, words.count { plain(para).contains(it) }) } }.filter { it.third > 0 }.sortedByDescending { it.third }
        val out = StringBuilder()
        for ((para, n) in hits) { if (out.length + para.length > PART) break; out.append("[part ${n + 1}] ").append(para).append('\n') }
        return out.toString().trim().ifEmpty { "Nothing about \"$query\" in ${d.name}." }
    }

    /** «Κάνε το «X» …»: the name as said, without its ending, underscores as spaces. */
    fun quoted(name: String) = name.substringBeforeLast('.').replace(Regex("_+"), " ")

    // ---- On the phone ----

    private val cache = LinkedHashMap<String, Doc>() // path → its text, while the chat may ask again
    /** [f] read, the last few kept. */
    fun reader(c: Context): (File) -> Doc = { f -> synchronized(cache) { cache["${f.path}@${f.lastModified()}"] } ?: extract(f).also { d -> // the car reads text files only: no PDF engine, no OCR
        synchronized(cache) { if (cache.size > 8) cache.remove(cache.keys.first()); cache["${f.path}@${f.lastModified()}"] = d } } }



    /**
     * The picked file to read: one in Tetra's folder where it is (never copied into Files); any other copied to
     * .System/Assistant/Data/Attachments/<when>/<name>.
     */
    fun fileOf(c: Context, uri: Uri): File {
        val storage = android.os.Environment.getExternalStorageDirectory(); val tetra = driveRoot()
        val into = File(TetraSystem.dir(tetra, "Assistant", "Data"), "Attachments/" + java.time.LocalDateTime.now().toString().replace(Regex("[:.]"), "-"))
        val name = c.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
        val f = uri.path?.takeIf { uri.scheme == "file" }?.let(::File) ?: File(into.apply { mkdirs() }, name.replace('/', '_')).also { out ->
            c.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: error("The file cannot be read.") }
        if (f.canonicalPath.startsWith(tetra.canonicalPath + File.separator)) return f.also { WriterLog.i("AI Assist: attached ${f.name}, read where it is") }
        need(f.length() <= MAX_BYTES, R.string.assistant_attach_too_big, (MAX_BYTES shr 20).toInt()) { "The file is over 50 MB." }
        return File(into.apply { mkdirs() }, f.name).also { f.copyTo(it, overwrite = true); WriterLog.i("AI Assist: attached ${f.name}, copied to .System/Assistant/Data/Attachments") }
    }
}

/** audio_make's estimate, waiting for the user's yes: nothing is spent until then. */
internal data class AudioAsk(val doc: Attach.Doc, val mode: String, val minutes: Int, val focus: String, val style: String, val perSection: Boolean, val estimate: AudioJobs.Estimate)

/**
 * Audio of a file (the user, 1 Nov 2026: "I give the file and it gives me an mp3"; «M4A στο κινητό»), made on the phone
 * (AudioMaker, in AudioService): a job is a folder .System/Assistant/Data/Audio/<id>/ with job.json, its state ("asked",
 * "running" with stage, done, total, then "done" with the file, title, seconds and chapters, or "failed"), which the chat's
 * card follows, and source.txt, the text read (pages split by \f).
 */
internal object AudioJobs {
    data class Estimate(val minutes: Int, val usd: Double, val ready: Int)
    data class Chapter(val title: String, val start: Double)
    data class Job(val id: String, val state: String, val stage: String, val done: Int, val total: Int, val name: String, val mode: String, val title: String,
        val seconds: Double, val chapters: List<Chapter>, val audio: File?, val error: String, val changed: Long)

    private const val CHARS_A_MINUTE = 900 // Greek read aloud: ~150 words of ~6 letters
    private const val TOKEN = 3.5 // letters a token, Greek

    /** How long, how much, how soon (the desktop's audio.js estimate(), the same figures): said before anything is spent. */
    fun estimate(chars: Int, mode: String, minutes: Int = 0): Estimate {
        val show = mode == "show"
        // a show runs about as long as one and a half readings of its source (tried: two pages made 2 minutes, not the 6 asked)
        val min = if (show) maxOf(3L, minOf(60L, Math.round(chars / 600.0), Math.round(if (minutes > 0) minutes.toDouble() else minOf(20.0, maxOf(8.0, chars / 2500.0))))).toInt()
            else maxOf(1L, Math.round(chars.toDouble() / CHARS_A_MINUTE)).toInt()
        val spoken = min * CHARS_A_MINUTE.toDouble()
        fun tok(model: String, inChars: Double, outChars: Double) = Bill.TOKENS.getValue(model).let { (i, o) -> (inChars / TOKEN * i + outChars / TOKEN * o) / 1e6 }
        val usd = spoken * Bill.PER_CHAR + if (show) tok(Mistral.BUILT_IN, chars.toDouble(), spoken) + tok(Mistral.CHAT, chars + spoken, 200.0) else tok(Mistral.CHAT, chars.toDouble(), chars.toDouble())
        return Estimate(min, usd, maxOf(2, Math.ceil(min / 4.0).toInt() + if (show) 1 else 0))
    }

    fun dir(tetra: File) = File(TetraSystem.dir(tetra, "Assistant", "Data"), "Audio")

    /** The job written, waiting for AudioService; answers its id. */
    fun ask(tetra: File, a: AudioAsk, now: Long = System.currentTimeMillis()): String {
        val id = java.util.UUID.randomUUID().toString()
        val d = File(dir(tetra), id).apply { mkdirs() }
        File(d, "source.txt").writeText(a.doc.pages.joinToString("\u000c"))
        File(d, "job.json").writeText(JSONObject().put("id", id).put("asked", now).put("name", a.doc.name).put("mode", a.mode).put("minutes", a.minutes)
            .put("focus", a.focus).put("style", a.style).put("state", "asked").toString())
        WriterLog.i("AI Assist: audio asked (${a.mode}, ${a.minutes} min) of ${a.doc.name}: $id")
        return id
    }

    /** A job as its file says now; null when it is gone. */
    fun read(tetra: File, id: String): Job? {
        val f = File(dir(tetra), "${File(id).name}/job.json")
        val o = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return null
        return job(o, f)
    }
    fun job(o: JSONObject, f: File): Job {
        val ch = o.optJSONArray("chapters") ?: JSONArray()
        return Job(o.optString("id", f.parentFile?.name.orEmpty()), o.optString("state", "asked"), o.optString("stage"), o.optInt("done"), o.optInt("total", 1), o.optString("name"),
            o.optString("mode"), o.optString("title"), o.optDouble("seconds", 0.0), (0 until ch.length()).mapNotNull { ch.optJSONObject(it) }.map { Chapter(it.optString("title"), it.optDouble("start")) },
            o.optString("file").takeIf { it.isNotBlank() }?.let { File(f.parentFile, File(it).name) }?.takeIf { it.isFile }, o.optString("error"), f.lastModified())
    }

    /** How far, 0–1: the script a tenth, the voices most of it, the file the end (the desktop's card). */
    fun share(j: Job) = when (j.stage) { "script" -> 0.1f * j.done / maxOf(1, j.total); "voice" -> 0.1f + 0.85f * j.done / maxOf(1, j.total); "mix" -> 0.97f; else -> 0f }

    /** Into Files › Assistant (it reaches the computer), never over a file already there: "name (2).m4a". */
    fun save(tetra: File, j: Job): File {
        val into = File(tetra, "Assistant").apply { mkdirs() }
        val ext = j.audio!!.extension
        val base = j.title.ifBlank { j.name.substringBeforeLast('.') }.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]+"), " ").trim().take(90).ifEmpty { "Audio" }
        var to = File(into, "$base.$ext"); var n = 2
        while (to.exists()) to = File(into, "$base (${n++}).$ext")
        return j.audio.copyTo(to).also { WriterLog.i("AI Assist: the audio saved as Assistant/${it.name}") }
    }



    const val EXTRA = "audio_job"
}
