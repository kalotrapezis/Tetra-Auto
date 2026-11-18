package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/*
 * Office › Text editor (asked 2026-09-27): a notepad. It opens any file as text and saves it back, with tabs for the
 * files open at once. No history, tags or colours — that is Notes. Android only; the computer has its own editors.
 */

/**
 * A file's bytes as text, and back. The editor works on '\n' only; a file written with Windows line endings (CRLF)
 * is saved with them again, and a byte-order mark stays if there was one — so saving changes only what was typed.
 */
internal object TextFileRules {
    data class Loaded(val text: String, val crlf: Boolean, val bom: Boolean)

    /** What a file is before it is opened: text, not text at all, or text too big to edit smoothly. */
    enum class Kind { Text, Binary, Huge }
    /** Past this an editor field lays out slowly enough to freeze (8 Nov: a file of slides froze it until reinstalled). */
    const val HUGE = 512 * 1024

    /**
     * Not text (8 Nov, the user: "you can open whatever file you like, even a ppt, and then it crashes and I can't undo"):
     * a ZIP (Word, PowerPoint, Excel, OpenDocument), a PDF, a NUL byte, or more than 1 in 20 control bytes at the start.
     * [size] is the whole file's, when only its start was read.
     */
    fun kind(head: ByteArray, size: Long = head.size.toLong()): Kind {
        val h = head.copyOf(minOf(head.size, 8192))
        fun starts(vararg b: Int) = h.size >= b.size && b.indices.all { h[it] == b[it].toByte() }
        if (starts(0x50, 0x4B, 0x03, 0x04) || starts(0x25, 0x50, 0x44, 0x46) || h.any { it == 0.toByte() }) return Kind.Binary
        val control = h.count { val b = it.toInt() and 0xFF; b < 9 || b in 14..31 || b == 127 }
        if (h.isNotEmpty() && control * 20 > h.size) return Kind.Binary
        return if (size > HUGE) Kind.Huge else Kind.Text
    }

    fun read(bytes: ByteArray): Loaded {
        val bom = bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        val raw = String(bytes, if (bom) 3 else 0, bytes.size - if (bom) 3 else 0, Charsets.UTF_8)
        return Loaded(raw.replace("\r\n", "\n"), crlf = "\r\n" in raw, bom = bom)
    }

    fun write(text: String, crlf: Boolean, bom: Boolean): ByteArray {
        val body = (if (crlf) text.replace("\n", "\r\n") else text).toByteArray(Charsets.UTF_8)
        return if (bom) byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body else body
    }

    /**
     * The file's path inside Tetra, which is how another device names the same file — or null for a file outside
     * it (Downloads, another app's), which Files sync does not carry. Tetra hands a file over as its own FileProvider
     * URI (`content://<app>.files/tetra/…`); Save as writes a `file://` one.
     */
    fun tetraPath(uri: String, authority: String, root: String): String? = runCatching {
        val u = java.net.URI(uri)
        val path = u.path ?: return null
        val rel = when {
            u.scheme == "file" && path.startsWith("$root/") -> path.removePrefix("$root/")
            u.scheme == "content" && u.authority == authority -> path.removePrefix("/").split('/', limit = 2).takeIf { it[0] == "tetra" || it[0] == "drive" }?.getOrNull(1)
            else -> null
        }
        rel?.takeIf { it.isNotEmpty() && it.split('/').none { p -> p.isEmpty() || p == "." || p == ".." } }
    }.getOrNull()

    /** A name for a new file: what was typed, with .txt when it has no extension of its own. */
    fun fileName(typed: String): String? {
        val name = typed.trim()
        if (name.isEmpty() || '/' in name || '\\' in name || name == "." || name == "..") return null
        return if ('.' in name.drop(1)) name else "$name.txt"
    }

    /** A Markdown file opens in View (rendered as in Notes) until Edit is chosen for it. */
    fun isMarkdown(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("md", "markdown")

    /** Where each line starts: line k's number goes beside the row that holds offset [k]. */
    fun lineStarts(text: String): IntArray {
        val out = ArrayList<Int>().apply { add(0) }
        text.forEachIndexed { i, c -> if (c == '\n') out += i + 1 }
        return out.toIntArray()
    }
}

/**
 * The symbols row (asked 2026-09-27: "all the </> weird stuff easily accessible"): what a phone keyboard hides.
 * A bracket or quote around a selection wraps it; </> closes the last HTML tag still open before the cursor.
 */
internal object CodeKeys {
    val keys = listOf("Tab", "</>", "<", ">", "/", "=", "\"", "'", "{", "}", "[", "]", "(", ")", ";", ":", "&", "#", "$", "_", "-", "|", "\\", "`", "!", "?", "@", "%", "*", "+", "~")
    private val pairs = mapOf("(" to ")", "[" to "]", "{" to "}", "\"" to "\"", "'" to "'", "`" to "`", "<" to ">")
    private val void = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr")

    /** The tag to close at [at]: the last one opened before it and not closed since. Null when all are closed. */
    fun openTag(text: String, at: Int): String? {
        val stack = ArrayDeque<String>()
        Regex("""<(/?)([A-Za-z][\w:-]*)[^<>]*?(/?)>""").findAll(text.substring(0, at)).forEach { m ->
            val name = m.groupValues[2].lowercase()
            when {
                m.groupValues[1] == "/" -> stack.indexOfLast { it == name }.takeIf { it >= 0 }?.let { while (stack.size > it) stack.removeLast() }
                m.groupValues[3] != "/" && name !in void -> stack.addLast(name)
            }
        }
        return stack.lastOrNull()
    }

    /** The text and selection after pressing [key]. */
    fun press(key: String, e: NoteFormat.Edit): NoteFormat.Edit {
        val insert = when (key) {
            "Tab" -> "    "
            "</>" -> openTag(e.text, e.start)?.let { "</$it>" } ?: return e
            else -> key
        }
        val close = pairs[key]
        if (close != null && e.end > e.start) // wrap the selection, and keep it selected
            return NoteFormat.Edit(e.text.substring(0, e.start) + key + e.text.substring(e.start, e.end) + close + e.text.substring(e.end), e.start + 1, e.end + 1)
        val at = e.start + insert.length
        return NoteFormat.Edit(e.text.substring(0, e.start) + insert + e.text.substring(e.end), at, at)
    }
}

/** The editor's settings, and its tabs: which files are open, and the unsaved text of each, kept until saved. */
internal class TextEditorStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("office_editor", Context.MODE_PRIVATE)
    private val drafts = File(context.applicationContext.filesDir, "editor-drafts")

    /** [view]: rendered Markdown (true) or the text (false), as last chosen for this tab; null is the file kind's default. */
    data class Tab(val id: String, val uri: String?, val name: String, val crlf: Boolean = false, val bom: Boolean = false, val view: Boolean? = null)

    var wordWrap: Boolean
        get() = prefs.getBoolean("word_wrap", true)
        set(on) { prefs.edit().putBoolean("word_wrap", on).apply() }

    var lineNumbers: Boolean
        get() = prefs.getBoolean("line_numbers", false)
        set(on) { prefs.edit().putBoolean("line_numbers", on).apply() }

    /** The striped theme (asked 1 Nov, to follow a line across): every other line a shade lighter or darker. */
    var stripes: Boolean
        get() = prefs.getBoolean("line_stripes", false)
        set(on) { prefs.edit().putBoolean("line_stripes", on).apply() }

    /** Files opens a .txt here rather than in the app last chosen for it (asked 2026-09-28). */
    var opensTxt: Boolean
        get() = prefs.getBoolean("opens_txt", true)
        set(on) { prefs.edit().putBoolean("opens_txt", on).apply() }

    /** Files opens a Word or LibreOffice document (.docx, .odt) in Writer (asked 2026-10-13) rather than another app. */
    var opensOffice: Boolean
        get() = prefs.getBoolean("opens_office", true)
        set(on) { prefs.edit().putBoolean("opens_office", on).apply() }
    /** Files opens a spreadsheet in Tables, a presentation in Slides (asked 16 Oct: each its own switch). */
    var opensSheets: Boolean
        get() = prefs.getBoolean("opens_sheets", opensOffice)
        set(on) { prefs.edit().putBoolean("opens_sheets", on).apply() }
    var opensSlides: Boolean
        get() = prefs.getBoolean("opens_slides", opensOffice)
        set(on) { prefs.edit().putBoolean("opens_slides", on).apply() }
    var opensPdfs: Boolean
        get() = prefs.getBoolean("opens_pdfs", opensOffice)
        set(on) { prefs.edit().putBoolean("opens_pdfs", on).apply() }
    /** Writer saves over the file by itself after a pause (Word's AutoSave), when it can save over it at all. */
    var autoSave: Boolean
        get() = prefs.getBoolean("office_autosave", true)
        set(on) { prefs.edit().putBoolean("office_autosave", on).apply() }

    /** Tables › Formulas › Recently used: the functions last put in, newest first (Excel keeps ten). */
    var recentFunctions: List<String>
        get() = prefs.getString("recent_functions", "").orEmpty().split(',').filter { it.isNotBlank() }
        set(v) { prefs.edit().putString("recent_functions", v.distinct().take(10).joinToString(",")).apply() }
    fun usedFunction(name: String) { recentFunctions = listOf(name) + recentFunctions }

    /** Review › Read aloud's speed (Android's speech rate: 1 is normal), Settings › Office (asked 15 Oct). */
    var readAloudSpeed: Float
        get() = prefs.getFloat("read_aloud_speed", 1f)
        set(v) { prefs.edit().putFloat("read_aloud_speed", v).apply() }

    /** Where Save puts a new file, under the phone's storage (default Tetra/Documents). */
    var saveFolder: String
        get() = prefs.getString("save_folder", "Tetra/Documents") ?: "Tetra/Documents"
        set(folder) { prefs.edit().putString("save_folder", folder.trim().trim('/').ifEmpty { "Tetra/Documents" }).apply() }

    fun saveDirectory(): File = File(Environment.getExternalStorageDirectory(), saveFolder)

    fun tabs(): List<Tab> = runCatching {
        val a = JSONArray(prefs.getString("tabs", "[]"))
        List(a.length()) { i -> a.getJSONObject(i).let { Tab(it.getString("id"), it.optString("uri").ifEmpty { null }, it.getString("name"), it.optBoolean("crlf"), it.optBoolean("bom"), if (it.has("view")) it.getBoolean("view") else null) } }
    }.getOrDefault(emptyList())

    fun setTabs(tabs: List<Tab>, current: String?) {
        val a = JSONArray()
        tabs.forEach { a.put(JSONObject().put("id", it.id).put("uri", it.uri ?: "").put("name", it.name).put("crlf", it.crlf).put("bom", it.bom).put("view", it.view)) }
        prefs.edit().putString("tabs", a.toString()).putString("current", current).apply()
        drafts.listFiles()?.forEach { f -> if (tabs.none { it.id == f.name }) f.delete() } // a closed tab's draft goes with it
    }

    fun current(): String? = prefs.getString("current", null)

    private val root get() = TetraFolder.root(Environment.getExternalStorageDirectory())
    private val authority = "${context.packageName}.files"
    private fun pathOf(uri: String?) = uri?.let { TextFileRules.tetraPath(it, authority, root.path) }

    /**
     * Open tabs cross to the other devices by their path in Tetra (SYNC_PLAN.md 6al): each open or close is kept
     * with its time, and the newer one of each path wins everywhere. A file outside Tetra stays on this device.
     */
    // shortcut: closed paths are kept forever (a few bytes each); prune ones older than a year if it ever matters.
    private fun records(): JSONObject = runCatching { JSONObject(prefs.getString("synced_tabs", "{}")!!) }.getOrDefault(JSONObject())

    fun mark(uri: String?, open: Boolean) {
        val path = pathOf(uri) ?: return
        prefs.edit().putString("synced_tabs", records().put(path, JSONObject().put("open", open).put("at", System.currentTimeMillis())).toString()).apply()
    }

    /** What a sync sends. A tab open since before this existed counts as opened at the dawn of time: anything newer wins. */
    fun syncPayload(): JSONObject {
        val r = records()
        tabs().forEach { t -> pathOf(t.uri)?.let { if (!r.has(it)) r.put(it, JSONObject().put("open", true).put("at", 1)) } }
        return JSONObject().put("tabs", JSONArray(r.keys().asSequence().map { r.getJSONObject(it).put("path", it) }.toList()))
    }

    /**
     * The computer's answer: the newest open or close of every path, from any device. Opens a file that arrived and
     * is open elsewhere; closes one closed elsewhere — unless it has unsaved text here, which nothing remote may lose.
     */
    fun applySynced(answer: JSONArray) {
        val r = records()
        for (i in 0 until answer.length()) {
            val t = answer.optJSONObject(i) ?: continue
            val path = t.optString("path").ifEmpty { null } ?: continue
            if (t.optLong("at") > (r.optJSONObject(path)?.optLong("at") ?: -1)) r.put(path, JSONObject().put("open", t.optBoolean("open")).put("at", t.optLong("at")))
        }
        prefs.edit().putString("synced_tabs", r.toString()).apply()
        val tabs = tabs().toMutableList()
        val before = tabs.toList()
        tabs.removeAll { t -> pathOf(t.uri)?.let { r.optJSONObject(it)?.optBoolean("open", true) == false } == true && draft(t.id) == null }
        for (path in r.keys()) {
            if (!r.getJSONObject(path).getBoolean("open") || tabs.any { pathOf(it.uri) == path }) continue
            val file = runCatching { DriveRules.file(root, path) }.getOrNull() ?: continue // not here (yet)
            val loaded = runCatching { TextFileRules.read(file.readBytes()) }.getOrNull() ?: continue
            tabs += Tab(UUID.randomUUID().toString(), Uri.fromFile(file).toString(), file.name, loaded.crlf, loaded.bom)
        }
        if (tabs != before) setTabs(tabs, current())
    }

    fun draft(id: String): String? = File(drafts, id).takeIf { it.isFile }?.readText()
    fun setDraft(id: String, text: String?) {
        val f = File(drafts, id)
        if (text == null) f.delete() else { drafts.mkdirs(); f.writeText(text) }
    }
}

/** The Text editor's own window (AppletActivity); a text file handed over by another app ("Open with Tetra") opens as a tab. */
class TextEditorActivity : AppletActivity() {
    override val applet = Applet.TextEditor
    @Composable override fun Content(word: String?, back: () -> Unit) = TextEditorScreen(back = back, open = intent.data, start = word?.takeIf { it.startsWith("text:") }?.removePrefix("text:"))
}

private fun displayName(context: Context, uri: Uri): String =
    runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull()
        ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Text"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TextEditorScreen(back: () -> Unit, open: Uri? = null, start: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { TextEditorStore(context) }
    val tabs = remember { store.tabs().toMutableStateList() }
    var current by remember { mutableStateOf(store.current()) }
    val text = remember { mutableStateMapOf<String, TextFieldValue>() } // the text of each open tab
    val saved = remember { mutableStateMapOf<String, String>() } // as it is in the file: a tab differs → unsaved
    val undo = remember { HashMap<String, NoteUndo<String>>() }
    var undoTick by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    OpenTiming("Text editor", loaded)
    var wrap by remember { mutableStateOf(store.wordWrap) }
    var numbers by remember { mutableStateOf(store.lineNumbers) }
    var stripes by remember { mutableStateOf(store.stripes) }
    var tools by remember { mutableStateOf(false) }
    var symbols by remember { mutableStateOf(false) } // the bar shows the symbols row instead of the controls
    var naming by remember { mutableStateOf<String?>(null) } // a tab being saved under a new name
    var closing by remember { mutableStateOf<String?>(null) } // a tab with unsaved changes being closed
    var warning by remember { mutableStateOf<Triple<Uri, String, Long>?>(null) } // a file not opened yet: not text (-1), or its size when huge
    var message by remember { mutableStateOf<String?>(null) }
    val w = islandContentColor()

    fun persist() = store.setTabs(tabs, current)
    fun untitled(): String = generateSequence(1) { it + 1 }.map { if (it == 1) "Untitled" else "Untitled $it" }.first { n -> tabs.none { it.name == n } }
    fun newTab() {
        val t = TextEditorStore.Tab(UUID.randomUUID().toString(), null, untitled())
        tabs.add(t); text[t.id] = TextFieldValue(""); saved[t.id] = ""; current = t.id; persist()
    }
    suspend fun load(t: TextEditorStore.Tab): TextFileRules.Loaded? = withContext(Dispatchers.IO) {
        runCatching { t.uri?.let { u -> context.contentResolver.openInputStream(Uri.parse(u))?.use { it.readBytes() } } }.getOrNull()
            ?.takeIf { TextFileRules.kind(it) != TextFileRules.Kind.Binary }?.let { TextFileRules.read(it) }
    }
    /** The start of a file and its size, to see what it is before reading it all. */
    suspend fun peek(uri: Uri): Pair<ByteArray, Long>? = withContext(Dispatchers.IO) { runCatching {
        val size = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        context.contentResolver.openInputStream(uri)?.use { s -> val b = ByteArray(8192); val n = s.read(b).coerceAtLeast(0); b.copyOf(n) to size }
    }.getOrNull() }
    suspend fun openUri(uri: Uri, anyway: Boolean = false) {
        tabs.firstOrNull { it.uri == uri.toString() }?.let { current = it.id; persist(); return }
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        val name = withContext(Dispatchers.IO) { displayName(context, uri) }
        val (head, size) = peek(uri) ?: run { message = context.getString(R.string.text_open_failed, name); return }
        when (TextFileRules.kind(head, size)) {
            TextFileRules.Kind.Binary -> { warning = Triple(uri, name, -1L); return }
            TextFileRules.Kind.Huge -> if (!anyway) { warning = Triple(uri, name, size); return }
            TextFileRules.Kind.Text -> {}
        }
        val file = load(TextEditorStore.Tab("", uri.toString(), name)) ?: run { message = context.getString(R.string.text_open_failed, name); return }
        val t = TextEditorStore.Tab(UUID.randomUUID().toString(), uri.toString(), name, file.crlf, file.bom)
        tabs.add(t); text[t.id] = TextFieldValue(file.text); saved[t.id] = file.text; current = t.id; persist(); store.mark(t.uri, true)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch { openUri(uri) } }
    // The open tabs come back as they were: the file's text, or the unsaved draft over it.
    LaunchedEffect(Unit) {
        for (t in tabs.toList()) {
            // a tab of a file that is not text (opened before this check) would freeze the editor at every start: closed
            val head = t.uri?.let { peek(Uri.parse(it)) }
            if (head != null && TextFileRules.kind(head.first, head.second) == TextFileRules.Kind.Binary) {
                tabs.remove(t); withContext(Dispatchers.IO) { store.setDraft(t.id, null) }; persist()
                message = context.getString(R.string.text_closed_not_text, t.name); continue
            }
            val file = load(t)?.text ?: ""
            saved[t.id] = file
            text[t.id] = TextFieldValue(withContext(Dispatchers.IO) { store.draft(t.id) } ?: file)
        }
        loaded = true
        if (open != null) openUri(open)
        if (start == "new") newTab()
        if (tabs.isEmpty()) newTab()
        if (tabs.none { it.id == current }) current = tabs.first().id
        if (start == "open") picker.launch(arrayOf("*/*"))
    }
    val tab = tabs.firstOrNull { it.id == current }
    val viewing = tab != null && (tab.view ?: TextFileRules.isMarkdown(tab.name))
    fun setViewing(on: Boolean) { val i = tabs.indexOfFirst { it.id == current }; if (i >= 0) { tabs[i] = tabs[i].copy(view = on); persist() } }
    val value = tab?.let { text[it.id] } ?: TextFieldValue("")
    fun dirty(id: String) = text[id]?.text != saved[id]
    // Unsaved text is kept as you type (a short pause), so closing the app never loses it.
    LaunchedEffect(tab?.id, value.text) {
        val t = tab ?: return@LaunchedEffect
        if (!loaded) return@LaunchedEffect
        delay(500)
        withContext(Dispatchers.IO) { store.setDraft(t.id, if (dirty(t.id)) value.text else null) }
    }

    fun edit(next: TextFieldValue) {
        val t = tab ?: return
        val before = text[t.id]?.text ?: ""
        if (next.text != before) undo.getOrPut(t.id) { NoteUndo(before) }.push(next.text, wordDone = NoteUndo.endsWord(before, next.text, next.selection.start))
        text[t.id] = next; undoTick++
    }
    fun press(key: String) {
        val t = tab ?: return
        val before = text[t.id] ?: return
        val r = CodeKeys.press(key, NoteFormat.Edit(before.text, before.selection.min, before.selection.max))
        if (r.text != before.text) undo.getOrPut(t.id) { NoteUndo(before.text) }.step(r.text)
        text[t.id] = TextFieldValue(r.text, androidx.compose.ui.text.TextRange(r.start, r.end)); undoTick++
    }
    fun restore(s: String?) { val t = tab ?: return; if (s != null) { text[t.id] = TextFieldValue(s, androidx.compose.ui.text.TextRange(s.length)); undoTick++ } }

    fun write(t: TextEditorStore.Tab, uri: Uri): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(TextFileRules.write(text[t.id]?.text ?: "", t.crlf, t.bom)) }
    }.isSuccess
    fun save(t: TextEditorStore.Tab, then: () -> Unit = {}) {
        if (t.uri == null) { naming = t.id; return }
        scope.launch {
            val ok = withContext(Dispatchers.IO) { write(t, Uri.parse(t.uri)) }
            if (ok) { saved[t.id] = text[t.id]?.text ?: ""; withContext(Dispatchers.IO) { store.setDraft(t.id, null) }; message = context.getString(R.string.text_saved, t.name); then() }
            else message = context.getString(R.string.text_save_failed, t.name)
        }
    }
    // Save as: a new file in the save folder. Never over another file.
    fun saveAs(id: String, typed: String, dir: File) {
        val t = tabs.firstOrNull { it.id == id } ?: return
        val name = TextFileRules.fileName(typed) ?: run { message = context.getString(R.string.text_bad_name); return }
        scope.launch {
            val file = File(dir, name)
            val result = withContext(Dispatchers.IO) {
                when {
                    !dir.isDirectory && !dir.mkdirs() -> context.getString(R.string.text_folder_failed, dir.name)
                    file.exists() -> context.getString(R.string.text_name_taken, name, dir.name)
                    !write(t, Uri.fromFile(file)) -> context.getString(R.string.text_save_failed, name)
                    else -> null
                }
            }
            if (result != null) { message = result; return@launch }
            val renamed = t.copy(uri = Uri.fromFile(file).toString(), name = name)
            tabs[tabs.indexOfFirst { it.id == id }] = renamed
            saved[id] = text[id]?.text ?: ""; withContext(Dispatchers.IO) { store.setDraft(id, null) }
            naming = null; persist(); store.mark(renamed.uri, true); message = context.getString(R.string.text_saved_in, dir.name); WriterLog.i("Text editor: saved as into ${dir.name}")
        }
    }
    fun close(id: String, force: Boolean = false) {
        if (!force && dirty(id)) { closing = id; return }
        val i = tabs.indexOfFirst { it.id == id }
        if (i < 0) return
        store.mark(tabs[i].uri, false)
        tabs.removeAt(i); text.remove(id); saved.remove(id); undo.remove(id)
        if (tabs.isEmpty()) newTab() else if (current == id) current = tabs[(i - 1).coerceAtLeast(0)].id
        persist()
    }

    BackHandler { if (tools) tools = false else back() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            // Tabs: the files open at once, like a browser (asked 2026-09-27). A dot is unsaved; × closes.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEach { t ->
                    val on = t.id == current
                    Row(Modifier.clip(CircleShape).background(if (on) Color.White else Color(0xFF2A2E30)).clickable { current = t.id; persist() }
                        .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        val ink = if (on) Color.Black else Color.White
                        Text((if (dirty(t.id)) "• " else "") + t.name, color = ink, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
                        Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClickLabel = stringResource(R.string.text_close_named, t.name)) { close(t.id) }, contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.text_close_named, t.name), tint = ink, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF2A2E30)).clickable(onClickLabel = stringResource(R.string.text_new_tab)) { newTab() }, contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.text_new_tab), tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
            // Word wrap off: the text is laid out as wide as its longest line and scrolls sideways, like code.
            val ink = MaterialTheme.colorScheme.onSurface
            val style = TextStyle(color = ink, fontSize = 15.sp, lineHeight = 22.sp, fontFamily = FontFamily.Monospace)
            val screen = LocalConfiguration.current.screenWidthDp.dp
            val scroll = rememberScrollState()
            // View: the Markdown rendered by Notes' own renderer, from the same text Edit changes.
            if (tab != null && viewing) Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
                // The renderer keeps one output line per source line, so the stripes follow the file's lines here too.
                var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
                val read = TextStyle(color = ink, fontSize = 16.sp, lineHeight = 24.sp)
                val shown = renderMarkdown(value.text)
                Box(if (stripes) lineDecor(shown.text, layout, scroll, read, numbers = false, stripes = true) else Modifier) {
                    Text(shown, style = read, onTextLayout = { layout = it },
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 120.dp))
                }
            } else if (tab != null) Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
                var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
                Row(if (numbers || stripes) lineDecor(value.text, layout, scroll, style, numbers, stripes) else Modifier) {
                    Box(if (wrap) Modifier.weight(1f) else Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                        BasicTextField(value, ::edit, textStyle = style, cursorBrush = SolidColor(ink), onTextLayout = { layout = it },
                            decorationBox = { inner -> Box { if (value.text.isEmpty()) Text(stringResource(R.string.text_type_here), style = style.copy(color = ink.copy(alpha = 0.4f))); inner() } },
                            // As tall as the page, so a tap anywhere below the text starts typing, like a notepad.
                            modifier = (if (wrap) Modifier.fillMaxWidth() else Modifier.widthIn(min = screen)).heightIn(min = LocalConfiguration.current.screenHeightDp.dp - 140.dp)
                                .padding(start = if (numbers) 8.dp else 16.dp, end = 16.dp, top = 4.dp, bottom = 120.dp))
                    }
                }
            }
        }
        // One island, fewer buttons than Notes (asked 2026-09-27): back, undo, redo, save, open. Pulled up: the rest.
        // The swap button turns it into the symbols, in two fixed rows — no scrolling. With the keyboard up it is one
        // thin full-width strip on the keyboard, as in Notes.
        val imeOpen = WindowInsets.isImeVisible
        val u = tab?.let { undo[it.id] }
        val swap: @Composable (Modifier) -> Unit = { m -> Box(m, contentAlignment = Alignment.Center) {
            Tool(R.drawable.ic_swap_horiz, stringResource(if (symbols) R.string.text_controls else R.string.text_symbols), w, on = symbols) { symbols = !symbols } } }
        val controls: @Composable () -> Unit = {
            undoTick
            Tool(R.drawable.ic_chevron_left, stringResource(R.string.common_back), w) { back() }
            swap(Modifier)
            Tool(R.drawable.ic_undo, stringResource(R.string.common_undo), w, enabled = u?.canUndo == true) { restore(u?.undo()) }
            Tool(R.drawable.ic_redo, stringResource(R.string.common_redo), w, enabled = u?.canRedo == true) { restore(u?.redo()) }
            Tool(R.drawable.ic_save, stringResource(R.string.common_save), w, enabled = tab != null && (tab.uri == null || dirty(tab.id))) { tab?.let { save(it) } }
            Tool(R.drawable.ic_folder, stringResource(R.string.text_open_file), w) { picker.launch(arrayOf("*/*")) }
            Tool(if (viewing) R.drawable.ic_edit else R.drawable.ic_visibility, stringResource(if (viewing) R.string.text_edit_mode else R.string.text_view_mode), w, enabled = tab != null) { setViewing(!viewing) }
        }
        // The swap key and the 31 symbols: two rows of 16, each key an equal share of the width.
        val keys: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth()) {
                (listOf<String?>(null) + CodeKeys.keys).chunked(16).forEach { line ->
                    Row(Modifier.fillMaxWidth()) {
                        line.forEach { k -> if (k == null) swap(Modifier.weight(1f)) else KeyButton(k, w, Modifier.weight(1f)) { press(k) } }
                        repeat(16 - line.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (imeOpen) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black).padding(horizontal = 4.dp, vertical = 2.dp)) {
            if (symbols) keys() else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) { controls() }
        } else Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp)) {
            IslandBottomBar(expand = { tools = true }, visible = !tools) {
                if (symbols) Box(Modifier.widthIn(max = LocalConfiguration.current.screenWidthDp.dp - 44.dp)) { keys() } else controls()
            }
        }
        message?.let { m ->
            LaunchedEffect(m) { delay(2500); message = null }
            Surface(color = Color.Black, contentColor = Color.White, shape = CircleShape, modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp)) {
                Text(m, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }

    if (tools) ModalBottomSheet(onDismissRequest = { tools = false }, containerColor = Color(0xFF16191A), contentColor = w) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tab?.let { t -> DriveWideAction(R.drawable.ic_save, stringResource(R.string.text_save_as_more)) { tools = false; naming = t.id } }
            SwitchRow(stringResource(R.string.text_word_wrap), stringResource(if (wrap) R.string.text_wrap_on else R.string.text_wrap_off), wrap) { wrap = it; store.wordWrap = it }
            SwitchRow(stringResource(R.string.text_line_numbers), stringResource(R.string.text_line_numbers_info), numbers) { numbers = it; store.lineNumbers = it }
            Column {
                Text(stringResource(R.string.text_theme), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!stripes, { stripes = false; store.stripes = false }, { Text(stringResource(R.string.text_theme_plain)) })
                    FilterChip(stripes, { stripes = true; store.stripes = true }, { Text(stringResource(R.string.text_theme_stripes)) })
                }
            }
            if (tab != null) SwitchRow(stringResource(R.string.text_markdown_view), stringResource(R.string.text_markdown_view_info), viewing) { setViewing(it) }
            tab?.let { t -> Text(if (t.uri == null) stringResource(R.string.text_not_saved, store.saveFolder)
                else stringResource(if (t.crlf) R.string.text_crlf else R.string.text_lf), style = MaterialTheme.typography.bodySmall) }
        }
    }

    naming?.let { id ->
        var typed by remember(id) { mutableStateOf(tabs.firstOrNull { it.id == id }?.name?.takeUnless { it.startsWith("Untitled") } ?: "") }
        // the folder of the file it is when that is in Tetra, else the save folder
        var into by remember(id) { mutableStateOf(tabs.firstOrNull { it.id == id }?.uri?.let { TextFileRules.tetraPath(it, "${context.packageName}.files", driveRoot().path) }
            ?.let { File(driveRoot(), it).parentFile } ?: store.saveDirectory()) }
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text(stringResource(R.string.text_save_as)) },
            text = {
                SaveAsWhere(into, { into = it }) {
                    OutlinedTextField(typed, { typed = it }, singleLine = true, label = { Text(stringResource(R.string.scanner_file_name)) })
                }
            },
            confirmButton = { TextButton(onClick = { saveAs(id, typed, into) }) { Text(stringResource(R.string.common_save), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            dismissButton = { TextButton(onClick = { naming = null }) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        )
    }
    warning?.let { (uri, name, size) ->
        val binary = size < 0
        AlertDialog(
            onDismissRequest = { warning = null },
            title = { Text(stringResource(if (binary) R.string.text_not_text_title else R.string.text_huge_title)) },
            text = { Text(if (binary) stringResource(R.string.text_not_text_body, name) else stringResource(R.string.text_huge_body, name, android.text.format.Formatter.formatShortFileSize(context, size))) },
            confirmButton = {
                if (binary) TextButton(onClick = { warning = null
                    runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), name)) }
                }) { Text(stringResource(R.string.text_open_with), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                else TextButton(onClick = { warning = null; scope.launch { openUri(uri, anyway = true) } }) { Text(stringResource(R.string.text_open_anyway), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            },
            dismissButton = { TextButton(onClick = { warning = null }) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        )
    }
    closing?.let { id ->
        val t = tabs.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { closing = null },
            title = { Text(t?.name?.let { stringResource(R.string.text_save_named_ask, it) } ?: stringResource(R.string.text_save_this_ask)) },
            text = { Text(stringResource(R.string.text_unsaved)) },
            confirmButton = { TextButton(onClick = { closing = null; t?.let { save(it) { close(id, force = true) } } }) { Text(stringResource(R.string.common_save), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { closing = null }) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    TextButton(onClick = { closing = null; close(id, force = true) }) { Text(stringResource(R.string.text_dont_save), color = Color(0xFFE35A4F), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            },
        )
    }
}

@Composable private fun SwitchRow(title: String, info: String, on: Boolean, set: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().clickable { set(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(info, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = on, onCheckedChange = set)
    }

/**
 * Line numbers and stripes, drawn behind the row that holds the text: each file line's number beside the first row it
 * occupies, and its stripe over all its rows, so a soft-wrapped line gets one number and one shade. Only what is on
 * screen is drawn, from [scroll]. With [numbers] the row is padded on the left for the gutter.
 */
@Composable private fun lineDecor(text: String, layout: TextLayoutResult?, scroll: androidx.compose.foundation.ScrollState, style: TextStyle, numbers: Boolean, stripes: Boolean): Modifier {
    val measurer = rememberTextMeasurer()
    val starts = remember(text) { TextFileRules.lineStarts(text) }
    val numberStyle = style.copy(color = style.color.copy(alpha = 0.55f))
    val digits = starts.size.toString().length.coerceAtLeast(2)
    val widest = remember(digits, style) { measurer.measure("9".repeat(digits), numberStyle).size }
    val density = LocalDensity.current
    val pad = with(density) { 8.dp.toPx() }
    val top = with(density) { 4.dp.toPx() } // the text's own top padding
    val rule = style.color.copy(alpha = 0.12f)
    // The ink at 7%: on black a dark grey, on white a light grey; the text keeps well over AA contrast on both.
    val shade = style.color.copy(alpha = 0.07f)
    val gutter = if (numbers) widest.width + 2 * pad else 0f
    return Modifier.drawBehind {
        val l = layout ?: return@drawBehind
        val end = l.layoutInput.text.length
        val from = scroll.value.toFloat()
        val until = scroll.value + scroll.viewportSize
        fun rowTop(k: Int) = if (k < starts.size) l.getLineTop(l.getLineForOffset(starts[k].coerceAtMost(end))) + top
            else l.getLineBottom(l.lineCount - 1) + top
        for (k in starts.indices) {
            val y = rowTop(k)
            if (y > until) break
            val next = rowTop(k + 1)
            if (next < from) continue
            if (stripes && k % 2 == 1) drawRect(shade, Offset(0f, y), androidx.compose.ui.geometry.Size(size.width, next - y))
            if (numbers) {
                val n = measurer.measure((k + 1).toString(), numberStyle)
                drawText(n, topLeft = Offset(pad + widest.width - n.size.width, y))
            }
        }
        if (numbers) drawLine(rule, Offset(gutter, 0f), Offset(gutter, size.height))
    }.padding(start = with(density) { gutter.toDp() })
}

/** A key of the symbols row: its character, the size of a Tool. */
@Composable private fun KeyButton(key: String, tint: Color, modifier: Modifier = Modifier, click: () -> Unit) = Box(
    modifier.height(40.dp).clip(CircleShape).clickable(onClickLabel = key, onClick = click),
    contentAlignment = Alignment.Center,
) { Text(key, color = tint, fontFamily = FontFamily.Monospace, fontSize = if (key.length > 1) 11.sp else 17.sp, maxLines = 1) }
