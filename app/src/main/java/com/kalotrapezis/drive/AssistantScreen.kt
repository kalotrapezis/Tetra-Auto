package com.kalotrapezis.drive

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** The assistant's chat (Home › Assistant): type or speak, answers as bubbles, read aloud when the speaker is on. */
open class AssistantActivity : ComponentActivity() {

    protected open val sheet = false
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.wrap(base))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WriterLog.init(this)
        TetraLook.load(this)
        // AI off (Settings › Βοηθός AI): no assistant at all, from Home, its icon or the phone's assistant gesture
        if (!AiGate.load(this).enabled) { android.widget.Toast.makeText(this, R.string.ai_is_off, android.widget.Toast.LENGTH_LONG).show(); finish(); return }
        if (!sheet) androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = TetraLook.light
        // a tap on "your audio is ready" (AudioJobs): the chat opens on its card
        val job = intent.getStringExtra(AudioJobs.EXTRA)
        // the sheet: the app the user was in shows behind it, dimmed (the user: "the rest of the screen is black")
        setContent { TetraTheme(backdrop = !sheet) { AssistantApp(sheet, job) { finish() } } }
    }
}

/** The small sheet over any app (the phone's assistant gesture, or its own icon): listens at once, answers, and goes. */
class AssistantSheetActivity : AssistantActivity() { override val sheet = true }

internal val ASSISTANT_COLOR = Color(0xFFFD6A04)

internal fun openAssistant(c: Context) = c.startActivity(Intent(c, AssistantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

/**
 * AI Assist's one open conversation, the same from every way in (the sheet, a notification, the applet's icon), bubbles and
 * all, forgotten after ten minutes without a word (the user, 7 Nov: "it may have asked me something"); a pill reminder
 * writes its line into it and starts the ten minutes again (8 Nov), so «το πήρα» right after is that dose. Kept in
 * files/assistant_open.json as well: a ring while the app's process slept is there when the user answers. History keeps
 * each one as before.
 */
internal object SharedChat {
    const val FORGET_MS = 10 * 60_000L
    var chat: AssistantChat? = null
    var id = java.util.UUID.randomUUID().toString()
    val bubbles = androidx.compose.runtime.snapshots.SnapshotStateList<Bubble>() // one list, cleared in place: an open screen sees a ring's line
    var at = 0L
    private var messages = org.json.JSONArray() // the model's turns while this process has made no chat yet
    private var loaded: File? = null

    fun file(c: Context) = File(c.filesDir, "assistant_open.json")
    fun stale(at: Long, now: Long) = at > 0 && now - at > FORGET_MS

    /** What was kept, read once per process; past the ten minutes, a new conversation. */
    @Synchronized fun load(f: File, now: Long = System.currentTimeMillis()) {
        if (loaded != f) {
            loaded = f; chat = null; forget()
            runCatching { val o = org.json.JSONObject(f.readText()); val b = o.getJSONArray("bubbles")
                id = o.getString("id"); at = o.getLong("at"); messages = o.getJSONArray("messages")
                bubbles += (0 until b.length()).map { Bubble.of(b.getJSONObject(it)) } }
        }
        if (stale(at, now)) forget()
    }
    @Synchronized fun forget() { chat?.clear(); bubbles.clear(); messages = org.json.JSONArray(); id = java.util.UUID.randomUUID().toString(); at = 0 }
    /** A reminder rung: [forModel] for the model, [shown] as AI Assist's bubble. */
    @Synchronized fun ring(f: File, forModel: String, shown: String, now: Long = System.currentTimeMillis()) {
        load(f, now)
        chat?.note(forModel, now) ?: messages.put(AssistantChat.noteMessage(forModel))
        bubbles += Bubble(false, shown); at = now; save(f)
    }
    fun ring(c: Context, forModel: String, shown: String) = ring(file(c), forModel, shown)
    @Synchronized fun save(f: File) = runCatching {
        f.writeText(org.json.JSONObject().put("id", id).put("at", at).put("bubbles", org.json.JSONArray(bubbles.map { it.json() })).put("messages", chat?.saved() ?: messages).toString())
    }.onFailure { WriterLog.e("AI Assist: could not keep the open chat", it) }
    /** The open chat, made from what was kept when this process has none yet. */
    @Synchronized fun chat(c: Context, now: Long = System.currentTimeMillis(), make: () -> AssistantChat = { assistantChat(c) }): AssistantChat {
        load(file(c), now); return chat ?: make().also { it.restore(messages); chat = it }
    }
    /** Messages for the model, when no chat is live (the tests). */
    @Synchronized fun kept(): org.json.JSONArray = chat?.saved() ?: org.json.JSONArray(messages.toString())
}

internal data class Bubble(val mine: Boolean, val text: String, val choices: List<AssistantChoice> = emptyList(), val call: PendingCall? = null,
    val images: List<File> = emptyList(), val error: Boolean = false, val files: List<Attach.Ref> = emptyList(), val audio: AudioAsk? = null, val job: String? = null,
    val cards: List<ResultCard> = emptyList(), val confirm: AssistantGuard.Confirm? = null) {
    /** As History keeps it: the file's name and path, never its text. */
    fun json(): org.json.JSONObject = org.json.JSONObject().put("mine", mine).put("text", text).put("error", error).apply {
        if (files.isNotEmpty()) put("files", org.json.JSONArray(files.map { org.json.JSONObject().put("name", it.name).put("path", it.path).put("pages", it.pages) }))
        job?.let { put("job", it) }
    }
    companion object {
        fun of(o: org.json.JSONObject) = Bubble(o.optBoolean("mine"), o.optString("text"), error = o.optBoolean("error"), job = o.optString("job").takeIf { it.isNotEmpty() },
            files = o.optJSONArray("files")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Attach.Ref(it.optString("name"), it.optString("path"), it.optInt("pages")) } }.orEmpty())
    }
}

private enum class Phase { Idle, Listening, Thinking }

/** The live screen's record: what it is doing decides how it turns. */
internal enum class LivePhase(val degreesPerSecond: Float) { Connecting(20f), Listening(40f), Thinking(90f), Speaking(200f), Stopping(0f) }

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun AssistantApp(sheet: Boolean, openJob: String? = null, closeNow: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val decorated = remember { AiLook.decorated(context) }
    // the sheet's TV switches off before it goes: the line, the dot, its «fsst»
    var tvOff by remember { mutableStateOf(false) }
    val close: () -> Unit = { if (!sheet || !decorated || AssistantKey.get(context) == null) closeNow() else if (!tvOff) { tvOff = true; Zap.play(); scope.launch { delay(520); closeNow() } } }
    LaunchedEffect(Unit) { if (sheet && decorated && AssistantKey.get(context) != null) Zap.play() } // and on, as it opens
    // every way in goes on with the open conversation (the user, 8 Nov), the sheet's or the full screen's
    val chat = remember { SharedChat.chat(context) }
    val bubbles = remember { SharedChat.bubbles.apply { openJob?.let { add(Bubble(false, "", job = it)) } } }
    OpenTiming("AI Assist", true)
    var typed by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) } // the island opened by its handle (the phone)
    fun offer(t: String) { typed = t; expanded = false }
    fun keep() { SharedChat.at = System.currentTimeMillis(); runCatching { AssistantChats.save(context, SharedChat.id, bubbles.map { it.json() }, chat.saved()) }; SharedChat.save(SharedChat.file(context)) }
    var phase by remember { mutableStateOf(Phase.Idle) }
    var hearing by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingCall?>(null) }
    var pendingAudio by remember { mutableStateOf<AudioAsk?>(null) } // audio_make's estimate, waiting for the user's yes
    var confirming by remember { mutableStateOf<AssistantGuard.Confirm?>(null) } // a change asked after outside text (AssistantGuard)
    var attached by remember { mutableStateOf<Attach.Doc?>(null) } // goes with the next message
    var attaching by remember { mutableStateOf(false) }
    var hasKey by remember { mutableStateOf(AssistantKey.get(context) != null) }
    val prefs = remember { context.getSharedPreferences("assistant", Context.MODE_PRIVATE) }
    var aloud by remember { mutableStateOf(sheet || prefs.getBoolean("aloud", false)) }
    val reader = rememberReadAloud()
    val stopNow = remember { AtomicBoolean(false) }
    var hear: (Long) -> Unit = {}
    var spent by remember { mutableDoubleStateOf(chat.bill.usd) }
    // live (the waveform button): one at a time, its record and captions over the chat
    var live by remember { mutableStateOf<AssistantLive?>(null) }
    var liveShown by remember { mutableStateOf(false) }
    var livePhase by remember { mutableStateOf(LivePhase.Connecting) }
    var liveYou by remember { mutableStateOf("") }
    var liveAi by remember { mutableStateOf("") }
    var liveLevel by remember { mutableFloatStateOf(0f) }
    var liveStart by remember { mutableLongStateOf(0L) }
    var liveLast by remember { mutableLongStateOf(0L) }
    var liveNow by remember { mutableLongStateOf(0L) }
    var voice by remember { mutableStateOf<AnswerVoice?>(null) } // the answer being read aloud
    // the sheet's TV (8 Nov): what the robot shows while the answer is heard
    var talking by remember { mutableStateOf(false) }
    var tvCue by remember { mutableStateOf(TalkCue.None) }
    var tvMood by remember { mutableStateOf(TalkMood.Normal) }
    fun hush() { reader.stop(); voice?.stop(); voice = null; talking = false }
    fun endLive(@androidx.annotation.StringRes why: Int? = null) {
        val l = live ?: return
        live = null; l.end(); hush()
        chat.bill.part("live").minutes(Mistral.REALTIME, (System.currentTimeMillis() - liveStart) / 1000.0); spent = chat.bill.usd
        why?.let { bubbles += Bubble(false, context.getString(it)) }
        livePhase = LivePhase.Stopping
        scope.launch { delay(1_500); if (live == null) liveShown = false } // the record slows to a stop first
    }
    DisposableEffect(Unit) { onDispose { live?.end(); AssistantAutoMemory.soon(context, chat.saved()) } }
    BackHandler { if (live != null) endLive() else close() }

    fun say(text: String, error: Boolean = false) { bubbles += Bubble(false, text, error = error) }
    fun open(f: File) = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase(Locale.ROOT)) ?: "application/octet-stream"
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { WriterLog.e("Assistant: could not open ${f.name}", it); say(context.getString(R.string.assistant_cannot_open), true) }
    fun go(v: String) = runCatching {
        if (v.startsWith("youtube:")) openYouTube(context, v.removePrefix("youtube:"))
        else if (v.startsWith("app:")) { if (!carOpenApp(context, v.removePrefix("app:"))) say(context.getString(R.string.assistant_cannot_open), true) }
        else context.startActivity(appletIntent(context, v))
    }.onFailure { WriterLog.e("Assistant: could not open $v", it); say(context.getString(R.string.assistant_cannot_open), true) }
    var dialing by remember { mutableStateOf<PendingCall?>(null) } // a call asked by voice, placed after its name is said
    var dialLeft by remember { mutableIntStateOf(0) }
    var wantCall by remember { mutableStateOf(false) } // the user asked to call: the contact picked next is called too
    fun call(p: PendingCall) {
        pending = null; dialing = null; wantCall = false
        endLive()
        WriterLog.i("Assistant: calling ${p.name}")
        if (!placePhoneCall(context, p.number, p.speaker)) context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", p.number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (sheet) close()
    }
    fun choose(c: AssistantChoice) { c.call?.let(::call); c.file?.let { open(it); if (sheet) close() }; c.go?.let { go(it); if (sheet) close() } }

    fun listenAgain() { live?.let { it.muted = false; livePhase = LivePhase.Listening; liveLast = System.currentTimeMillis() } }
    /** The user's yes to the audio: made on the phone (AudioService), its card following the job here. */
    fun startAudio(a: AudioAsk) = scope.launch {
        pendingAudio = null
        // past the month's budget nothing runs by itself, and it says so
        if (withContext(Dispatchers.IO) { AssistantUsage.of(context).capped() }) { say(context.getString(R.string.usage_capped), true); return@launch }
        val id = withContext(Dispatchers.IO) { runCatching { AudioJobs.ask(driveRoot(), a) } }
            .getOrElse { WriterLog.e("AI Assist: the audio could not be asked for", it); say(it.message ?: "", true); return@launch }
        bubbles += Bubble(false, context.getString(R.string.assistant_attach_started), job = id)
        keep()
    }
    /** The user's yes or no to a held change: run exactly as it was asked, never decided by the model. */
    fun decide(c: AssistantGuard.Confirm, yes: Boolean) = scope.launch {
        confirming = null
        if (!yes) { say(context.getString(R.string.assistant_confirm_cancelled)); keep(); return@launch }
        val reply = withContext(Dispatchers.IO) { runCatching { chat.approve(c) } }.getOrElse { say(it.shownMessage(context) ?: it.message.orEmpty(), true); return@launch }
        bubbles += Bubble(false, context.getString(R.string.assistant_confirm_done), reply.choices, null, reply.images, cards = reply.cards)
        keep()
        reply.call?.let(::call) // the yes was the user's own
        reply.open?.let(::open); reply.go?.let(::go)
    }
    fun skipAudio() { pendingAudio = null; say(context.getString(R.string.assistant_attach_skipped)) }
    fun attachFrom(uri: Uri) = scope.launch {
        attaching = true
        val r = withContext(Dispatchers.IO) { runCatching { Attach.reader(context)(Attach.fileOf(context, uri)) } }
        attaching = false
        r.onSuccess { attached = it }.onFailure { WriterLog.w("AI Assist: the file was not attached", it); say(it.shownMessage(context) ?: "", true) }
    }

    val handsFree = remember { HandsFree { p -> scope.launch { call(p) } } }
    fun cancelDial() { if (dialing == null) return; handsFree.cancel(); dialing = null; wantCall = false; say(context.getString(R.string.assistant_call_cancelled)); WriterLog.i("AI Assist: the call was cancelled") }
    /** The name said aloud (even with the voice off: the user may be driving), then the call by itself unless cancelled. */
    fun dial(p: PendingCall, inLive: Boolean) = scope.launch {
        pending = null; dialing = p
        WriterLog.i("AI Assist: calling ${p.name} in ${HandsFree.DELAY} ms unless cancelled")
        hush()
        AnswerVoice(context, reader, chat.bill.part("voice"), scope).also { voice = it }.apply { say(context.getString(R.string.assistant_calling, p.name)); finish(); await() }
        if (dialing != p) return@launch
        handsFree.start(p, System.currentTimeMillis())
        if (!inLive) hear(3_500) // «όχι», «άκυρο» within those seconds
        while (dialing == p) {
            dialLeft = handsFree.left(System.currentTimeMillis())
            // what is being said is heard out first: it may be «άκυρο»
            if (handsFree.tick(System.currentTimeMillis(), busy = phase == Phase.Thinking || (phase == Phase.Listening && hearing))) break
            delay(200)
        }
    }

    fun send(text: String) = scope.launch {
        if (text.isBlank()) return@launch
        // a call about to go: «όχι», «άκυρο», «σταμάτα», «μη» stop it; anything else lets it go
        dialing?.let { if (AssistantChat.yesNo(text) == false) cancelDial(); return@launch }
        val file = attached; attached = null
        bubbles += Bubble(true, text, files = listOfNotNull(file?.let { Attach.Ref(it.name, it.path, it.pages.size) }))
        val inLive = live != null
        if (inLive) { liveYou = text; liveAi = "" }
        // a yes or no to a waiting call is heard by the phone, never decided by the model
        pending?.let { p -> AssistantChat.yesNo(text)?.let { yes -> if (yes) call(p) else { pending = null; say(context.getString(R.string.assistant_call_cancelled)) }; return@launch } }
        // and to the audio's estimate (a short yes only: "ναι, αλλά 10 λεπτά" goes to the model): nothing is spent before it
        confirming?.let { c -> AssistantChat.yesNo(text)?.let { yes -> decide(c, yes); return@launch } }
        pendingAudio?.let { a -> (if (text.trim().split(Regex("\\s+")).size <= 3) AssistantChat.yesNo(text) else null)?.let { yes -> if (yes) startAudio(a) else skipAudio(); listenAgain(); return@launch } }
        phase = Phase.Thinking
        live?.muted = true; if (inLive) livePhase = LivePhase.Thinking
        // the answer comes a few words at a time and is read a sentence at a time: the first is heard while the rest is made
        hush()
        val speech = if (aloud || inLive) AnswerVoice(context, reader, chat.bill.part("voice"), scope).also { voice = it } else null
        val cut = Sentences()
        AssistantTools.beside = false // Tetra Car puts pages beside the map itself
        val said = StringBuilder(); tvCue = TalkCue.None; tvMood = TalkMood.Normal
        chat.onTool = { name -> TalkCue.ofTool(name).takeIf { it != TalkCue.None }?.let { found -> scope.launch { tvCue = found } } }
        val r = withContext(Dispatchers.IO) { runCatching { try { chat.ask(text, file = file) { words ->
            said.append(words); val cue = TalkCue.of(said); val mood = TalkMood.of(said)
            scope.launch { talking = speech != null; if (cue != TalkCue.None || tvCue.weather) tvCue = cue.takeIf { it != TalkCue.None } ?: tvCue; tvMood = mood }
            cut.push(words).forEach { speech?.say(it) }
            if (inLive) scope.launch { liveAi += words; if (speech != null) livePhase = LivePhase.Speaking }
        } } finally { chat.onTool = null } } }
        speech?.say(cut.rest()); speech?.finish()
        scope.launch { speech?.await(); talking = false }
        phase = Phase.Idle; spent = chat.bill.usd
        val reply = r.getOrElse { speech?.stop(); say(it.shownMessage(context) ?: context.getString(R.string.assistant_server, 0), true); WriterLog.w("Assistant: ${it.message}"); listenAgain(); return@launch }
        bubbles += Bubble(false, reply.text, reply.choices, reply.call, reply.images, audio = reply.audio, cards = reply.cards, confirm = reply.confirm)
        keep()
        pendingAudio = reply.audio; confirming = reply.confirm
        // a call asked for by voice goes without a tap; one only found waits for the card's Call
        wantCall = HandsFree.asked(text) || (wantCall && reply.call == null && reply.cards.any { it is ResultCard.Contact })
        reply.call?.let { it.copy(speaker = it.speaker || HandsFree.loud(text)) }?.let { p -> if (wantCall) { speech?.await(); dial(p, inLive); return@launch } else pending = p }
        // beside (open_beside): a second Tetra window in split screen, this one staying as it is; a file outside the library opens as usual
        if (reply.beside) { reply.open?.let { open(it) }; reply.go?.let { openApplet(context, it) } }
        else reply.open?.let(::open)
        if (inLive && liveAi.isBlank()) liveAi = reply.text
        speech?.await(); spent = chat.bill.usd // Mistral's voice is billed as it reads
        // Maps, a note, YouTube: once the answer is said, there (live ends: it is paid by the minute)
        reply.go?.takeIf { !reply.beside }?.let { if (inLive) endLive(); go(it); if (sheet) close(); return@launch }
        if (inLive) { listenAgain(); return@launch }
        if (!sheet) return@launch
        // the sheet: a question or a list waits for the answer by voice (a few seconds); anything else is done
        when {
            reply.open != null -> close()
            reply.text.trimEnd().endsWith('?') || reply.choices.isNotEmpty() || reply.cards.isNotEmpty() || reply.call != null || reply.confirm != null -> hear(6_000)
            reply.images.isEmpty() -> { delay(1_500); close() }
        }
    }

    // Android's picker for an attachment, opening in Tetra's folder for «Από τα Αρχεία του Tetra» (as Scan's import)
    val pickFromFiles = remember { AtomicBoolean(false) }
    val pickAttach = rememberLauncherForActivityResult(object : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: Context, input: Array<String>) = super.createIntent(context, input).apply {
            if (pickFromFiles.get()) putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",
                "primary:" + driveRoot().relativeTo(android.os.Environment.getExternalStorageDirectory()).path))
        }
    }) { uri -> uri?.let { attachFrom(it) } }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) hear(10_000) else say(context.getString(R.string.assistant_mic_needed), true) }
    hear = { waitMs -> scope.launch {
        if (!hasKey) return@launch
        if (!hasMic(context)) { askMic.launch(Manifest.permission.RECORD_AUDIO); return@launch }
        hush(); stopNow.set(false); phase = Phase.Listening
        val pcm = withContext(Dispatchers.IO) { runCatching { listen(waitMs, { stopNow.get() || !isActive }, { hearing = it }, AssistantPause.ms(context), AssistantAudio.mic(context)) }.getOrNull() }
        hearing = false
        if (pcm == null) { phase = Phase.Idle; if (sheet && pending == null && confirming == null && dialing == null && bubbles.lastOrNull()?.images.isNullOrEmpty() && bubbles.lastOrNull()?.cards.isNullOrEmpty()) close(); return@launch }
        phase = Phase.Thinking
        val text = withContext(Dispatchers.IO) { runCatching { AssistantNet.transcribe(AssistantNet.key(context), pcm, AssistantWords.terms(context), chat.bill.part("dictation")) } }
        phase = Phase.Idle; spent = chat.bill.usd
        text.onSuccess { if (it.isBlank()) say(context.getString(R.string.assistant_heard_nothing)) else send(it) }
            .onFailure { say(it.shownMessage(context) ?: "", true) }
    } }
    fun mic() = when (phase) { Phase.Idle -> hear(10_000); Phase.Listening -> stopNow.set(true); Phase.Thinking -> Unit }
    LaunchedEffect(Unit) { if (sheet && hasKey) hear(8_000) }

    /** [opening]: said for the user as soon as it listens (a talk about the attached file starts with the AI). */
    fun startLive(opening: String? = null) = scope.launch {
        if (!hasKey || live != null || phase != Phase.Idle) return@launch
        if (!hasMic(context)) { askMic.launch(Manifest.permission.RECORD_AUDIO); return@launch }
        hush()
        liveYou = ""; liveAi = ""; liveLevel = 0f; livePhase = LivePhase.Connecting; liveShown = true
        liveStart = System.currentTimeMillis(); liveLast = liveStart; liveNow = liveStart
        val terms = withContext(Dispatchers.IO) { AssistantWords.terms(context) }
        live = AssistantLive(AssistantNet.key(context), terms,
            heard = { t -> scope.launch { liveYou = t; liveAi = ""; liveLast = System.currentTimeMillis(); if (livePhase == LivePhase.Connecting) livePhase = LivePhase.Listening } },
            turn = { t -> scope.launch { send(t) } },
            level = { liveLevel = it },
            failed = { e -> scope.launch { say(e.shownMessage(context) ?: "", true); endLive() } },
            pauseMs = AssistantPause.ms(context), mic = withContext(Dispatchers.IO) { AssistantAudio.mic(context) },
        ).also { it.start(); livePhase = LivePhase.Listening }
        WriterLog.i("AI Assist: live on")
        opening?.let { send(it) }
    }
    // the bill guard (live is paid by the minute): 20 s with nobody talking, or 5 minutes, and it ends by itself
    LaunchedEffect(liveShown) {
        while (liveShown) {
            liveNow = System.currentTimeMillis()
            if (live != null && liveNow - liveStart >= LIVE_MAX_MS) endLive(R.string.assistant_live_cap)
            else if (live != null && livePhase == LivePhase.Listening && liveNow - liveLast >= LIVE_SILENCE_MS) endLive(R.string.assistant_live_silence)
            delay(250)
        }
    }

    val status = when (phase) {
        Phase.Listening -> stringResource(if (hearing) R.string.assistant_hearing else R.string.assistant_listening)
        Phase.Thinking -> stringResource(R.string.assistant_thinking)
        Phase.Idle -> null
    }
    val list: @Composable (Modifier) -> Unit = { m ->
        val state = rememberLazyListState()
        LaunchedEffect(bubbles.size) { if (bubbles.isNotEmpty()) state.animateScrollToItem(bubbles.size - 1) }
        LazyColumn(m, state = state, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!hasKey) item { AssistantKeyField { hasKey = true; if (sheet) hear(8_000) } }
            if (!sheet && hasKey) item { DefaultAssistantCard() }
            // a welcome with something to try, each in its applet's colour; a tap fills the input, it does not send
            if (!sheet && hasKey && bubbles.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(painterResource(R.drawable.assistant_art), contentDescription = null, Modifier.size(64.dp))
                    Text(stringResource(R.string.assistant_home_detail), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    // what it can do, as a word cloud; gone while something is typed
                    androidx.compose.animation.AnimatedVisibility(typed.isEmpty() && attached == null && !attaching, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) { WordCloud(::offer) }
                }
            }
            val actions = CardActions({ go(it); if (sheet) close() }, { open(it); if (sheet) close() }, ::call,
                { f -> scope.launch { attaching = true; withContext(Dispatchers.IO) { runCatching { Attach.reader(context)(f) } }.onSuccess { attached = it }.onFailure { say(it.shownMessage(context) ?: "", true) }; attaching = false } },
                ::say, dialing, dialLeft, ::cancelDial)
            itemsIndexed(bubbles) { i, b -> BubbleView(b, pending.takeIf { i == bubbles.size - 1 }, ::choose, ::call, { pending = null; say(context.getString(R.string.assistant_call_cancelled)) }, ::open,
                pendingAudio.takeIf { i == bubbles.size - 1 }, { startAudio(it) }, ::skipAudio, actions, confirming.takeIf { i == bubbles.size - 1 }, ::decide) }
        }
    }

    if (sheet) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(remember { MutableInteractionSource() }, null) { close() }, contentAlignment = Alignment.BottomCenter) {
            Surface(Modifier.fillMaxWidth().widthIn(max = 640.dp).clickable(remember { MutableInteractionSource() }, null) {}, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), tonalElevation = 6.dp) {
                Column(Modifier.navigationBarsPadding().imePadding()) {
                    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Image(painterResource(R.drawable.assistant_art), contentDescription = null, Modifier.size(28.dp))
                        Text(stringResource(R.string.app_assistant), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (spent > 0) Text(Bill.text(spent), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    }
                    // the plain mic (the user, 8 Nov): Live's cassette while it listens and thinks, a VHS-era TV with the answer on its screen
                    val still = remember { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
                    // the TV, bigger, all along (8 Nov): no text on it, the robot showing what goes on, and pixel buttons for what can be
                    // tapped; the words stay in the full AI Assist and its History, the same conversation
                    if (hasKey && decorated) {
                        val shown = when { phase == Phase.Listening -> LivePhase.Listening; phase == Phase.Thinking -> LivePhase.Thinking; talking -> LivePhase.Speaking; else -> LivePhase.Stopping }
                        val color = remember { characterColor(AssistantVoices.id(context)) }
                        // switched on: it walks in and says hello for a moment (8 Nov: "say hi, what's going on?")
                        val hello = remember { context.resources.getStringArray(R.array.tv_hello).random() }
                        var greeting by remember { mutableStateOf(!still) }
                        LaunchedEffect(Unit) { delay(3_000); greeting = false }
                        // the TV keeps its shape; a wide screen (a tablet) gets the room around it, a nightstand with its lamp and a wardrobe (8 Nov)
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val wide = maxWidth > 600.dp; val tvWidth = minOf(maxWidth, 460.dp)
                            Box(Modifier.fillMaxWidth()) {
                                if (wide) Room(Modifier.matchParentSize())
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) {
                                    if (wide) Furniture(left = true, Modifier.weight(1f).height(260.dp))
                                    VhsTv(0, still, Modifier.padding(horizontal = 12.dp, vertical = 8.dp).width(tvWidth), off = tvOff) {
                            Box {
                                TalkScreen(shown, hearing, Modifier.fillMaxWidth().height(230.dp), tvCue, tvMood, color, enter = !still)
                                if (greeting) Text(hello, Modifier.align(Alignment.TopCenter).padding(top = 14.dp).background(Color(0xFFF4F1EA)).border(2.dp, Color(0xFF2A2420)).padding(horizontal = 10.dp, vertical = 4.dp),
                                    color = Color(0xFF2A2420), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                                    if (wide) Furniture(left = false, Modifier.weight(1f).height(260.dp))
                                }
                            }
                        }
                        val last = bubbles.lastOrNull()?.takeIf { !it.mine && phase == Phase.Idle }
                        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                            dialing?.let { PixelButton(context.getString(R.string.common_cancel) + " · $dialLeft", Color(0xFFEF9A9A), R.drawable.ic_call_end) { cancelDial() } }
                            if (last != null) {
                                pending?.let { p -> PixelButton(p.name, Color(0xFFA5D6A7), R.drawable.ic_call) { call(p) } }
                                confirming?.let { c -> PixelButton(context.getString(R.string.common_yes), Color(0xFFA5D6A7)) { decide(c, true) }; PixelButton(context.getString(R.string.common_no), Color(0xFFEF9A9A)) { decide(c, false) } }
                                pendingAudio?.let { a -> PixelButton(context.getString(R.string.common_yes), Color(0xFFA5D6A7), R.drawable.ic_play) { startAudio(a) }; PixelButton(context.getString(R.string.common_no), Color(0xFFEF9A9A)) { skipAudio() } }
                                last.choices.forEach { ch -> PixelButton(ch.label, color.copy(alpha = 1f)) { choose(ch) } }
                                last.cards.forEach { card -> when (card) {
                                    is ResultCard.Web -> card.sources.take(4).forEach { src -> PixelButton(src.title.ifBlank { runCatching { Uri.parse(src.url).host }.getOrNull() ?: src.url }, Color(0xFF81D4FA), R.drawable.ic_link) { go(AssistantTools.browserAt(src.url)); close() } }
                                    is ResultCard.Files -> card.files.take(4).forEach { f -> PixelButton(f.name, Color(0xFFFFE082), if (f.extension.lowercase() in setOf("mp3", "m4a", "wav", "ogg", "opus")) R.drawable.ic_play else R.drawable.ic_file) { open(f); close() } }
                                    else -> Unit
                                } }
                                // the words, and anything else it made: the full AI Assist, the same conversation
                                PixelButton(context.getString(R.string.common_open), Color(0xFFE0E0E0), R.drawable.ic_chat) { openAssistant(context); close() }
                            }
                        }
                    } else list(Modifier.fillMaxWidth().heightIn(max = 420.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MicButton(phase, hearing, ::mic)
                        Text(status ?: stringResource(R.string.assistant_tap_to_talk), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = close) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_close)) }
                    }
                }
            }
        }
        return
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = close) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_back)) }
                Image(painterResource(R.drawable.assistant_art), contentDescription = null, Modifier.size(30.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.app_assistant), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // the thread's running total (the user: "the bill came")
                Surface(onClick = { openUsage(context) }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(Bill.text(spent), Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
                IconButton(onClick = { aloud = !aloud; prefs.edit().putBoolean("aloud", aloud).apply(); if (!aloud) hush() }) {
                    Icon(painterResource(if (aloud) R.drawable.ic_volume_up else R.drawable.ic_volume_off), contentDescription = stringResource(R.string.assistant_read_aloud))
                }
                IconButton(onClick = { AssistantAutoMemory.soon(context, chat.saved()); SharedChat.forget(); SharedChat.file(context).delete(); pending = null; pendingAudio = null; confirming = null; attached = null; hush(); spent = 0.0 }) { Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.assistant_new_chat)) }
            }
            // the phone: the input is the island, its handle opens Scheduled, History, Skills and Memory (the user, 1 Nov: "not
            // much, it does not have a big context window"); a tablet has the room for them in a sidebar beside the chat
            // the window's own width, not the device's: a tablet in split screen, or a narrow window, gets the phone's island
            val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 720
            var section by remember { mutableStateOf(AssistSection.History) } // the drawer opens on Scheduled (8 Nov), not the chips of what it can do
            val sectionContent: @Composable (Modifier) -> Unit = { m -> Box(m) { when (section) {
                AssistSection.History -> HistoryTab(::offer) { saved ->
                    AssistantAutoMemory.soon(context, chat.saved()); confirming = null
                    chat.restore(saved.messages); bubbles.clear(); bubbles += saved.bubbles.map(Bubble::of)
                    // its file is read again only if the chat asks for it
                    chat.attach(bubbles.flatMap { it.files }.lastOrNull()?.let { File(it.path) })
                    SharedChat.id = saved.id; SharedChat.at = System.currentTimeMillis(); pending = null; pendingAudio = null; expanded = false }
                AssistSection.Skills -> SkillsTab(::offer)
                AssistSection.Memory -> MemoryTab()
            } } }
            fun sendTyped() { val t = typed.trim(); typed = ""; send(t) }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (wide) Column(Modifier.width(380.dp).fillMaxHeight().padding(start = 12.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    SectionBar(section) { section = it }
                    Surface(Modifier.padding(top = 8.dp).weight(1f).fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) { sectionContent(Modifier.fillMaxSize()) }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    list(Modifier.weight(1f).fillMaxWidth())
                    status?.let { Text(it, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                    // what can be done with a file, as soon as it is attached and nothing is typed (the user: "so users know what they can do with the file")
                    attached?.takeIf { typed.isBlank() && phase == Phase.Idle }?.let { d -> val name = Attach.quoted(d.name)
                        val show = stringResource(R.string.assistant_attach_show_prompt, name); val talk = stringResource(R.string.assistant_attach_live_prompt, name); val read = stringResource(R.string.assistant_attach_read_prompt, name)
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            AppChip(stringResource(R.string.assistant_attach_pill_show), AppColor.assistant) { offer(show) }
                            AppChip(stringResource(R.string.assistant_attach_pill_live), Color(0xFF25C4FE)) { startLive(talk) }
                            AppChip(stringResource(R.string.assistant_attach_pill_read), AppColor.timer) { offer(read) }
                        }
                    }
                    val dragThreshold = with(androidx.compose.ui.platform.LocalDensity.current) { 24.dp.toPx() }
                    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), shape = MaterialTheme.shapes.extraLarge, color = islandColor(), contentColor = islandContentColor()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (!wide) Box(Modifier.fillMaxWidth().height(22.dp).clickable { expanded = !expanded }.pointerInput(Unit) {
                                var dragged = 0f
                                detectVerticalDragGestures(onDragStart = { dragged = 0f }, onVerticalDrag = { _, a -> dragged += a },
                                    onDragEnd = { if (dragged < -dragThreshold) expanded = true else if (dragged > dragThreshold) expanded = false })
                            }, contentAlignment = Alignment.Center) { Box(Modifier.width(32.dp).height(4.dp).background(islandContentColor().copy(alpha = 0.65f), CircleShape)) }
                            if (!wide && expanded) {
                                SectionBar(section) { section = it }
                                Surface(Modifier.padding(8.dp).fillMaxWidth().heightIn(max = 460.dp).height(460.dp), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
                                    sectionContent(Modifier.fillMaxSize())
                                }
                            }
                            // the attached file, until it goes with the next message
                            if (attached != null || attaching) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val d = attached
                                if (d == null) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = islandContentColor()); Text(stringResource(R.string.assistant_attach_reading), style = MaterialTheme.typography.labelLarge) }
                                else {
                                    Icon(painterResource(if (d.kind == "docx") R.drawable.ic_file_word else R.drawable.ic_file_text), contentDescription = null, Modifier.size(20.dp))
                                    Text(d.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(listOfNotNull(pluralStringResource(R.plurals.assistant_attach_pages, d.pages.size, d.pages.size).takeIf { d.pages.size > 1 }, stringResource(R.string.assistant_attach_cut).takeIf { d.cut }).joinToString(" · "),
                                        style = MaterialTheme.typography.labelMedium, color = islandContentColor().copy(alpha = 0.7f), maxLines = 1)
                                    Spacer(Modifier.weight(1f))
                                    IconButton(onClick = { attached = null }, Modifier.size(32.dp)) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_remove), Modifier.size(18.dp)) }
                                }
                            }
                            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp, top = if (wide) 10.dp else 0.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // the paperclip: Tetra's Files (Android's picker opening there) or anywhere on the phone
                                Box {
                                    var clip by remember { mutableStateOf(false) }
                                    IconButton(onClick = { clip = true }, enabled = hasKey && !attaching, modifier = Modifier.size(40.dp)) { Icon(painterResource(R.drawable.ic_attach), contentDescription = stringResource(R.string.assistant_attach_button)) }
                                    DropdownMenu(clip, { clip = false }) {
                                        DropdownMenuItem({ Text(stringResource(R.string.assistant_attach_from_files)) }, { clip = false; pickFromFiles.set(true); pickAttach.launch(Attach.TYPES) },
                                            leadingIcon = { Icon(painterResource(R.drawable.ic_folder), contentDescription = null) })
                                        DropdownMenuItem({ Text(stringResource(R.string.assistant_attach_from_device)) }, { clip = false; pickFromFiles.set(false); pickAttach.launch(Attach.TYPES) },
                                            leadingIcon = { Icon(painterResource(R.drawable.ic_devices), contentDescription = null) })
                                    }
                                }
                                TextField(typed, { typed = it }, Modifier.weight(1f), placeholder = { Text(stringResource(R.string.assistant_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    shape = RoundedCornerShape(24.dp), maxLines = 5, enabled = hasKey,
                                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.White.copy(alpha = 0.12f), unfocusedContainerColor = Color.White.copy(alpha = 0.12f), disabledContainerColor = Color.White.copy(alpha = 0.06f),
                                        focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Color.White, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                                        disabledIndicatorColor = Color.Transparent, focusedPlaceholderColor = Color.White.copy(alpha = 0.6f), unfocusedPlaceholderColor = Color.White.copy(alpha = 0.6f)),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { sendTyped() }))
                                if (typed.isNotBlank()) FilledIconButton(onClick = ::sendTyped, enabled = phase == Phase.Idle) { Icon(painterResource(R.drawable.ic_arrow_upward), contentDescription = stringResource(R.string.common_send)) }
                                else {
                                    MicButton(phase, hearing, ::mic)
                                    // light with a black wave on the dark island, in either theme (the user: "white on white")
                                    FilledTonalIconButton(onClick = { startLive() }, enabled = hasKey && phase == Phase.Idle, modifier = Modifier.size(52.dp),
                                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color.White.copy(alpha = 0.85f), contentColor = Color.Black)) {
                                        Icon(painterResource(R.drawable.ic_graphic_eq), contentDescription = stringResource(R.string.assistant_live))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (liveShown) LiveScreen(livePhase, liveLevel, liveYou, liveAi,
            Bill.text(spent + Bill.perMinute(Mistral.REALTIME) * (liveNow - liveStart).coerceAtLeast(0) / 60_000.0), clockOf(liveNow - liveStart),
            pending, bubbles.lastOrNull()?.choices.orEmpty(), ::choose, ::call, { pending = null; say(context.getString(R.string.assistant_call_cancelled)) }, { endLive() }, dialing, dialLeft, ::cancelDial)
    }
}

private const val LIVE_MAX_MS = 5 * 60_000L
private const val LIVE_SILENCE_MS = 20_000L

private fun clockOf(ms: Long) = (ms.coerceAtLeast(0) / 1000).let { "%d:%02d:%02d".format(Locale.ROOT, it / 3600, it / 60 % 60, it % 60) }

/**
 * Live, as the user drew it: a record (or a cassette, a tap away) that turns with the talk (steady while the AI speaks, slowly with its grooves glowing
 * to the voice while it listens, a slow half-turn while it thinks, slowing to a stop at X), the last exchange as captions,
 * and X beside what it has cost and how long it has been on.
 */
@Composable
private fun LiveScreen(phase: LivePhase, level: Float, you: String, ai: String, cost: String, clock: String, pending: PendingCall?,
    choices: List<AssistantChoice>, choose: (AssistantChoice) -> Unit, call: (PendingCall) -> Unit, cancel: () -> Unit, end: () -> Unit,
    dialing: PendingCall?, dialLeft: Int, cancelDial: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.app_assistant), style = MaterialTheme.typography.titleLarge)
            // the record, the cassette or the TV (8 Nov: "an option in Live: vinyl, cassette or TV"): chosen under it, or a tap on it for the next, remembered
            val prefs = LocalContext.current.getSharedPreferences("assistant", android.content.Context.MODE_PRIVATE)
            var style by remember { mutableIntStateOf(prefs.getInt("liveStyle", if (prefs.getBoolean("cassette", false)) 1 else 0)) }
            fun pick(i: Int) { style = i; prefs.edit().putInt("liveStyle", i).apply() }
            Box(Modifier.weight(1f).fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) { pick((style + 1) % 3) },
                contentAlignment = Alignment.Center) {
                when (style) {
                    1 -> Cassette(phase, level, Modifier.fillMaxWidth(0.92f).widthIn(max = 520.dp))
                    2 -> VhsTv(0, true, Modifier.fillMaxWidth(0.92f).widthIn(max = 520.dp)) { TalkScreen(phase, level > 0.05f, Modifier.fillMaxWidth().height(200.dp), TalkCue.of(ai), TalkMood.of(ai), characterColor(AssistantVoices.id(LocalContext.current))) }
                    else -> Vinyl(phase, level, Modifier.fillMaxWidth(0.82f).widthIn(max = 420.dp))
                }
            }
            Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.assistant_live_vinyl, R.string.assistant_live_cassette, R.string.assistant_live_tv).forEachIndexed { i, label ->
                    FilterChip(selected = style == i, onClick = { pick(i) }, label = { Text(stringResource(label)) })
                }
            }
            Surface(Modifier.fillMaxWidth().widthIn(max = 560.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(18.dp).heightIn(min = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(caption(stringResource(R.string.assistant_you), you.ifBlank { if (phase == LivePhase.Connecting) stringResource(R.string.assistant_live_connecting) else "…" }),
                        style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    if (ai.isNotBlank() || phase == LivePhase.Thinking) Text(caption(stringResource(R.string.assistant_ai), ai.ifBlank { stringResource(R.string.assistant_thinking) }),
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    choices.take(5).forEachIndexed { i, c -> Text("${i + 1}. ${c.label}", Modifier.fillMaxWidth().clickable { choose(c) }.padding(vertical = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (dialing != null) {
                        Text(stringResource(R.string.assistant_calling, dialing.name) + " · " + stringResource(R.string.assistant_calling_in, dialLeft), style = MaterialTheme.typography.titleMedium)
                        Button(onClick = cancelDial, Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.common_cancel)) }
                    }
                    if (pending != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { call(pending) }) { Text(stringResource(R.string.assistant_call, pending.name), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        TextButton(onClick = cancel) { Text(stringResource(R.string.common_cancel), maxLines = 1) }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().widthIn(max = 560.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledIconButton(onClick = end, enabled = phase != LivePhase.Stopping, modifier = Modifier.size(64.dp)) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.assistant_live_end), Modifier.size(28.dp))
                }
                Surface(Modifier.weight(1f).height(64.dp), shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Box(contentAlignment = Alignment.Center) { Text("$cost / $clock", style = MaterialTheme.typography.titleLarge, maxLines = 1) }
                }
            }
        }
    }
}

private fun caption(who: String, said: String) = androidx.compose.ui.text.buildAnnotatedString {
    pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)); append("$who: "); pop(); append(said)
}

private const val SIDE_DEGREES = 360f * 8 // a side's worth of turning: a few sentences of the AI, half a minute of the user

/** How far the player has turned and how brightly it glows: eased up and down with [phase], so X slows it to a stop. */
private class Turn {
    var angle by mutableFloatStateOf(0f); var pulse by mutableFloatStateOf(0f)
    /** Degrees turned, back when the AI talks: how much tape has gone from the left reel to the right. */
    var wound by mutableFloatStateOf(0f)
}

@Composable
private fun rememberTurn(phase: LivePhase, level: Float): Turn {
    val turn = remember { Turn() }
    val target by rememberUpdatedState(phase.degreesPerSecond)
    val glow by rememberUpdatedState(if (phase == LivePhase.Listening) level else 0f)
    val way by rememberUpdatedState(if (phase == LivePhase.Listening) 1f else -1f) // the user winds one way, the AI the other
    LaunchedEffect(Unit) {
        var speed = 0f; var last = 0L
        while (true) withFrameNanos { t ->
            if (last != 0L) {
                val dt = (t - last) / 1e9f
                speed += (target - speed) * (dt * 1.5f).coerceAtMost(1f)
                turn.angle = (turn.angle + speed * dt) % 360f
                turn.wound += way * speed * dt
                turn.pulse += (glow - turn.pulse) * (dt * 10f).coerceAtMost(1f)
            }
            last = t
        }
    }
    return turn
}

/**
 * The record, with its tonearm: the needle sits on one track while the user talks and moves to another when the AI
 * answers, as a record changes song; off the record while connecting and when it stops.
 */
@Composable
internal fun Vinyl(phase: LivePhase, level: Float, modifier: Modifier) {
    val label = androidx.compose.ui.graphics.ImageBitmap.imageResource(R.drawable.assistant_art)
    val turn = rememberTurn(phase, level)
    // the needle's distance from the middle, as a share of the record's radius
    val needle by animateFloatAsState(when (phase) { LivePhase.Listening -> 0.86f; LivePhase.Thinking, LivePhase.Speaking -> 0.6f; else -> 1.14f },
        androidx.compose.animation.core.tween(1_100), label = "needle")
    androidx.compose.foundation.Canvas(modifier.aspectRatio(1f)) {
        val r = size.minDimension * 0.42f
        val c = androidx.compose.ui.geometry.Offset(size.width * 0.45f, size.height * 0.52f)
        drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color(0xFF2C2C31), Color(0xFF0E0E10)), c, r), r, c)
        val groove = Color(0xFFFFB74D)
        for (i in 0 until 26) {
            val wave = 0.5f + 0.5f * kotlin.math.sin(i * 0.9f + turn.angle / 25f)
            val gap = i == 10 || i == 19 // the quiet rings between songs
            drawCircle(if (gap) Color.Black.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.05f).compositeOver(groove.copy(alpha = (turn.pulse * 0.6f * wave).coerceIn(0f, 0.6f))),
                radius = r * (0.40f + i * 0.022f), center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(if (gap) 3f else 1.2f))
        }
        rotate(turn.angle, c) {
            drawArc(Color.White.copy(alpha = 0.07f), -100f, 30f, useCenter = true, topLeft = c - androidx.compose.ui.geometry.Offset(r, r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2))
            drawArc(Color.White.copy(alpha = 0.07f), 80f, 30f, useCenter = true, topLeft = c - androidx.compose.ui.geometry.Offset(r, r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2))
            val lr = r * 0.36f
            clipPath(androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, lr)) }) {
                // the icon's middle (its robot) fills the label
                drawImage(label, srcOffset = androidx.compose.ui.unit.IntOffset((label.width * 0.12f).toInt(), (label.height * 0.12f).toInt()),
                    srcSize = androidx.compose.ui.unit.IntSize((label.width * 0.76f).toInt(), (label.height * 0.76f).toInt()),
                    dstOffset = androidx.compose.ui.unit.IntOffset((c.x - lr).toInt(), (c.y - lr).toInt()), dstSize = androidx.compose.ui.unit.IntSize((lr * 2).toInt(), (lr * 2).toInt()))
            }
            drawCircle(Color(0xFF0E0E10), radius = r * 0.025f, center = c) // the spindle hole
        }
        // the tonearm, from its pivot at the top right: the angle that puts the needle [needle]·r from the middle (law of cosines)
        val pivot = androidx.compose.ui.geometry.Offset(size.width * 0.9f, size.height * 0.1f)
        val toMiddle = c - pivot
        val d = toMiddle.getDistance(); val arm = d * 0.97f; val rho = needle * r
        val bend = kotlin.math.acos(((arm * arm + d * d - rho * rho) / (2 * arm * d)).coerceIn(-1f, 1f))
        val a = kotlin.math.atan2(toMiddle.y, toMiddle.x) - bend
        val dir = androidx.compose.ui.geometry.Offset(kotlin.math.cos(a), kotlin.math.sin(a))
        val metal = Color(0xFFB0B4BA)
        drawCircle(Color(0xFF3A3D42), radius = r * 0.11f, center = pivot)
        drawLine(metal, pivot - dir * (r * 0.16f), pivot + dir * (arm * 0.86f), strokeWidth = r * 0.035f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawCircle(Color(0xFF55595F), radius = r * 0.075f, center = pivot - dir * (r * 0.16f)) // the counterweight
        drawCircle(metal, radius = r * 0.05f, center = pivot)
        // the headshell, bent a little inwards, and the needle on the record
        val head = androidx.compose.ui.geometry.Offset(kotlin.math.cos(a + 0.35f), kotlin.math.sin(a + 0.35f))
        val shellStart = pivot + dir * (arm * 0.86f)
        drawLine(Color(0xFFE0E0E0), shellStart, shellStart + head * (arm * 0.16f), strokeWidth = r * 0.07f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawCircle(ASSISTANT_COLOR, radius = r * 0.02f, center = shellStart + head * (arm * 0.15f))
    }
}

/**
 * The cassette (a tap on the player switches): a see-through shell, the label with the robot, two reels turning with the
 * talk, the tape going from one reel to the other: left to right while the user talks, back while the AI does, and
 * turning round at either end, as an auto-reverse deck does.
 */
@Composable
internal fun Cassette(phase: LivePhase, level: Float, modifier: Modifier) {
    val label = androidx.compose.ui.graphics.ImageBitmap.imageResource(R.drawable.assistant_art)
    val turn = rememberTurn(phase, level)
    androidx.compose.foundation.Canvas(modifier.aspectRatio(1.58f)) {
        val w = size.width; val h = size.height
        val corner = androidx.compose.ui.geometry.CornerRadius(w * 0.04f)
        drawRoundRect(Color(0xFF2A2C30).copy(alpha = 0.92f), cornerRadius = corner)
        drawRoundRect(Color.White.copy(alpha = 0.25f), cornerRadius = corner, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
        // the label strip and the window
        drawRoundRect(Color(0xFFF4F1EA), androidx.compose.ui.geometry.Offset(w * 0.08f, h * 0.08f), androidx.compose.ui.geometry.Size(w * 0.84f, h * 0.17f), androidx.compose.ui.geometry.CornerRadius(6f))
        drawRect(ASSISTANT_COLOR, androidx.compose.ui.geometry.Offset(w * 0.08f, h * 0.22f), androidx.compose.ui.geometry.Size(w * 0.84f, h * 0.03f))
        val iconSide = (h * 0.15f).toInt()
        drawImage(label, dstOffset = androidx.compose.ui.unit.IntOffset((w * 0.1f).toInt(), (h * 0.09f).toInt()), dstSize = androidx.compose.ui.unit.IntSize(iconSide, iconSide))
        val window = androidx.compose.ui.geometry.Rect(w * 0.14f, h * 0.32f, w * 0.86f, h * 0.72f)
        drawRoundRect(Color.Black.copy(alpha = 0.35f), window.topLeft, window.size, androidx.compose.ui.geometry.CornerRadius(h * 0.2f))
        drawRoundRect(ASSISTANT_COLOR.copy(alpha = (turn.pulse * 0.8f).coerceIn(0f, 0.8f)), window.topLeft, window.size, androidx.compose.ui.geometry.CornerRadius(h * 0.2f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(4f)) // glows with the user's voice
        // the reels: tape wound on each as the minutes go
        val hub = h * 0.1f; val full = h * 0.19f
        val side = turn.wound / SIDE_DEGREES
        val p = (side - 2 * kotlin.math.floor(side / 2)).let { if (it > 1) 2 - it else it } // 0 → 1 → 0 …
        listOf(androidx.compose.ui.geometry.Offset(w * 0.32f, h * 0.52f) to (1 - p), androidx.compose.ui.geometry.Offset(w * 0.68f, h * 0.52f) to p).forEach { (at, share) ->
            drawCircle(Color(0xFF4A3426), radius = hub + (full - hub) * share, center = at)
            rotate(turn.wound * 2f, at) {
                drawCircle(Color(0xFFF4F1EA), radius = hub, center = at)
                drawCircle(Color(0xFF2A2C30), radius = hub * 0.45f, center = at)
                for (k in 0 until 6) { // the hub's teeth
                    val t = Math.toRadians(k * 60.0).toFloat()
                    drawLine(Color(0xFFF4F1EA), at, at + androidx.compose.ui.geometry.Offset(kotlin.math.cos(t), kotlin.math.sin(t)) * (hub * 0.62f), strokeWidth = hub * 0.18f)
                }
                drawCircle(Color(0xFF2A2C30), radius = hub * 0.22f, center = at)
            }
        }
        // the tape running past the heads at the bottom, and the shell's lower part
        drawLine(Color(0xFF4A3426), androidx.compose.ui.geometry.Offset(w * 0.2f, h * 0.84f), androidx.compose.ui.geometry.Offset(w * 0.8f, h * 0.84f), strokeWidth = 4f)
        val bottom = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.18f, h * 0.98f); lineTo(w * 0.24f, h * 0.78f); lineTo(w * 0.76f, h * 0.78f); lineTo(w * 0.82f, h * 0.98f); close()
        }
        drawPath(bottom, Color.White.copy(alpha = 0.08f))
        listOf(0.3f, 0.5f, 0.7f).forEach { x -> drawCircle(Color.Black.copy(alpha = 0.5f), radius = h * 0.03f, center = androidx.compose.ui.geometry.Offset(w * x, h * 0.9f)) }
    }
}

/**
 * A VHS-era television around the sheet's answer: rabbit ears, a cabinet with knobs and a grille, a rounded CRT whose
 * picture opens from a line with a tracking band rolling down as it switches on ([answer] changes: on again), scan lines
 * over it, and a small stand. [still] (animations off in the phone's settings): on at once.
 */
@Composable
internal fun VhsTv(answer: Int, still: Boolean, modifier: Modifier, off: Boolean = false, screen: @Composable () -> Unit) {
    val on = remember(answer) { androidx.compose.animation.core.Animatable(if (still) 1f else 0f) }
    LaunchedEffect(answer) { if (!still) on.animateTo(1f, androidx.compose.animation.core.tween(700)) }
    // switching off (8 Nov: "the CRT's eye when the talk ends"): the picture folds to a line, the line to a dot, the dot fades
    val gone = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(off) { if (off) gone.animateTo(1f, androidx.compose.animation.core.tween(480)) }
    val cabinet = Color(0xFF3B3430); val trim = Color(0xFF5A4E46)
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.foundation.Canvas(Modifier.width(120.dp).height(26.dp)) { // the rabbit ears
            val foot = androidx.compose.ui.geometry.Offset(size.width / 2, size.height)
            drawLine(Color(0xFF9A9A9A), foot, androidx.compose.ui.geometry.Offset(size.width * 0.12f, 0f), strokeWidth = 4f)
            drawLine(Color(0xFF9A9A9A), foot, androidx.compose.ui.geometry.Offset(size.width * 0.88f, size.height * 0.1f), strokeWidth = 4f)
            drawCircle(cabinet, radius = size.height * 0.3f, center = foot)
        }
        Surface(shape = RoundedCornerShape(26.dp), color = cabinet, border = androidx.compose.foundation.BorderStroke(2.dp, trim)) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val glass = Color(0xFF12201D)
                Box(Modifier.weight(1f).clip(RoundedCornerShape(30.dp)).background(glass)
                    .drawWithContent {
                        drawContent()
                        val lines = Color.Black.copy(alpha = 0.16f); var y = 0f
                        while (y < size.height) { drawLine(lines, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 1.5f); y += 6f }
                        // the glass's curve: darker at the edges
                        drawRect(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)), radius = size.maxDimension * 0.75f))
                        if (gone.value > 0.35f) { val k = ((gone.value - 0.35f) / 0.65f).coerceIn(0f, 1f) // the dot
                            drawCircle(Color.White.copy(alpha = 1 - k * 0.9f), radius = size.minDimension * 0.05f * (1 - k * 0.7f), center = center)
                            drawCircle(Color.White.copy(alpha = 0.25f * (1 - k)), radius = size.minDimension * 0.14f, center = center) }
                        if (on.value < 1f) { // switching on: the tracking band rolls down
                            val band = size.height * on.value
                            drawRect(Color.White.copy(alpha = 0.22f * (1 - on.value)), androidx.compose.ui.geometry.Offset(0f, band - 18f), androidx.compose.ui.geometry.Size(size.width, 36f))
                        }
                    }) {
                    Box(Modifier.graphicsLayer { val t = on.value; val g = gone.value
                        scaleY = (0.02f + 0.98f * t * (2 - t)) * (1 - (g / 0.35f).coerceAtMost(1f) * 0.98f)
                        scaleX = 1 - ((g - 0.2f) / 0.3f).coerceIn(0f, 1f)
                        alpha = (t * 2).coerceAtMost(1f) }) { screen() }
                }
                androidx.compose.foundation.Canvas(Modifier.width(30.dp).height(150.dp)) { // the knobs and the speaker grille
                    val x = size.width / 2
                    listOf(0.12f, 0.34f).forEach { k -> val c = androidx.compose.ui.geometry.Offset(x, size.height * k)
                        drawCircle(trim, radius = size.width * 0.42f, center = c); drawCircle(Color(0xFF2A2420), radius = size.width * 0.3f, center = c)
                        drawLine(Color(0xFFBFB3A6), c, c + androidx.compose.ui.geometry.Offset(0f, -size.width * 0.28f), strokeWidth = 3f) }
                    var y = size.height * 0.56f
                    while (y < size.height * 0.95f) { drawLine(Color.Black.copy(alpha = 0.45f), androidx.compose.ui.geometry.Offset(size.width * 0.15f, y), androidx.compose.ui.geometry.Offset(size.width * 0.85f, y), strokeWidth = 3f); y += 9f }
                    drawCircle(if (on.value > 0f) Color(0xFFE53935) else Color(0xFF552222), radius = 3.5f, center = androidx.compose.ui.geometry.Offset(x, size.height * 0.5f)) // the power light
                }
            }
        }
        androidx.compose.foundation.Canvas(Modifier.width(160.dp).height(14.dp)) { // the stand
            drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(size.width * 0.3f, 0f); lineTo(size.width * 0.7f, 0f); lineTo(size.width * 0.82f, size.height); lineTo(size.width * 0.18f, size.height); close() }, trim)
        }
    }
}

@Composable
private fun MicButton(phase: Phase, hearing: Boolean, tap: () -> Unit) {
    val on = phase == Phase.Listening
    FilledIconButton(onClick = tap, enabled = phase != Phase.Thinking, modifier = Modifier.size(52.dp),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (on) (if (hearing) Color(0xFFD32F2F) else Color(0xFFE57373)) else MaterialTheme.colorScheme.primary)) {
        if (phase == Phase.Thinking) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        else Icon(painterResource(R.drawable.ic_mic), contentDescription = stringResource(if (on) R.string.common_stop else R.string.assistant_tap_to_talk))
    }
}

@Composable
private fun BubbleView(b: Bubble, pending: PendingCall?, choose: (AssistantChoice) -> Unit, call: (PendingCall) -> Unit, cancel: () -> Unit, open: (File) -> Unit,
    audio: AudioAsk?, startAudio: (AudioAsk) -> Unit, skipAudio: () -> Unit, actions: CardActions,
    confirming: AssistantGuard.Confirm? = null, decide: (AssistantGuard.Confirm, Boolean) -> Unit = { _, _ -> }) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (b.mine) Alignment.End else Alignment.Start) {
        // the file that went with it: its name and pages, never its text
        b.files.forEach { f -> Surface(Modifier.padding(bottom = 4.dp).widthIn(max = 560.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.ic_file_text), contentDescription = null, Modifier.size(22.dp))
                Text(f.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (f.pages > 1) Text(pluralStringResource(R.plurals.assistant_attach_pages, f.pages, f.pages), style = MaterialTheme.typography.labelMedium)
            }
        } }
        if (b.text.isNotBlank()) Surface(shape = RoundedCornerShape(20.dp), color = when {
            b.error -> MaterialTheme.colorScheme.errorContainer
            b.mine -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        }, modifier = Modifier.widthIn(max = 560.dp)) { Text(b.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge) }
        if (b.choices.isNotEmpty()) Column(Modifier.padding(top = 6.dp).widthIn(max = 560.dp).clip(RoundedCornerShape(16.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            b.choices.forEachIndexed { i, c ->
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)).clickable { choose(c) }.padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(c.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (c.call != null) Icon(painterResource(R.drawable.ic_call), contentDescription = null, Modifier.size(20.dp))
                }
            }
        }
        b.images.forEach { f ->
            val bmp = remember(f) { runCatching {
                // a camera photo is 12 MP or more: read at about a screen's width
                val w = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(f.path, it) }.outWidth
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = Integer.highestOneBit((w / 1080).coerceAtLeast(1)) })?.asImageBitmap()
            }.getOrNull() }
            bmp?.let { Image(it, contentDescription = f.name, Modifier.padding(top = 6.dp).widthIn(max = 360.dp).clip(RoundedCornerShape(16.dp)).clickable { open(f) }, contentScale = ContentScale.FillWidth) }
        }
        // audio_make's estimate: the user's «Ξεκίνα» (or a spoken yes) starts it
        if (audio != null && b.audio == audio) Surface(Modifier.padding(top = 6.dp).widthIn(max = 560.dp), shape = RoundedCornerShape(16.dp), color = AppColor.assistant.copy(alpha = 0.16f).compositeOver(MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(if (audio.mode == "show") R.string.assistant_attach_show else R.string.assistant_attach_read) + " · " + audio.doc.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.assistant_attach_estimate, audio.estimate.minutes, "%.2f".format(Locale.ROOT, audio.estimate.usd), audio.estimate.ready), style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { startAudio(audio) }) { Icon(painterResource(R.drawable.ic_play), contentDescription = null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.assistant_attach_start), maxLines = 1) }
                    TextButton(onClick = skipAudio) { Text(stringResource(R.string.common_cancel), maxLines = 1) }
                }
            }
        }
        b.job?.let { AudioJobCard(it) }
        if (b.cards.isNotEmpty()) ResultCards(b.cards, actions)
        // a change asked after outside text (a mail, the web): what it would do, and the user's own yes
        if (confirming != null && b.confirm == confirming) Surface(Modifier.padding(top = 6.dp).widthIn(max = 560.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.assistant_confirm_title), style = MaterialTheme.typography.labelMedium)
                if (confirming.what.isNotBlank()) Text(confirming.what, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { decide(confirming, true) }) { Text(stringResource(R.string.assistant_confirm_yes), maxLines = 1) }
                    TextButton(onClick = { decide(confirming, false) }) { Text(stringResource(R.string.common_cancel), maxLines = 1) }
                }
            }
        }
        if (pending != null && b.call == pending) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { call(pending) }) {
                Icon(painterResource(R.drawable.ic_call), contentDescription = null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.assistant_call, pending.name), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = cancel) { Text(stringResource(R.string.common_cancel), maxLines = 1) }
        }
    }
}

/**
 * Audio being made on the phone (AudioJobs, AudioService): how far it has got, read from its job file every second while it
 * shows; then played here, its chapters a tap away, saved in Files or shared.
 */
@Composable
private fun AudioJobCard(id: String) {
    val context = LocalContext.current
    val tetra = remember { driveRoot() }
    var job by remember(id) { mutableStateOf(AudioJobs.read(tetra, id)) }
    LaunchedEffect(id) {
        while (job?.state.let { it == null || it == "asked" || it == "running" }) {
            delay(1_000)
            job = withContext(Dispatchers.IO) { AudioJobs.read(tetra, id) }
        }
    }
    val j = job
    Surface(Modifier.padding(top = 6.dp).widthIn(max = 560.dp).fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                j == null -> Text(stringResource(R.string.assistant_attach_job_gone), color = MaterialTheme.colorScheme.onSurfaceVariant)
                j.state == "failed" -> Text(j.error.ifBlank { stringResource(R.string.assistant_attach_failed_title) }, color = MaterialTheme.colorScheme.error)
                j.state != "done" || j.audio == null -> {
                    Text(j.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val stage = when (j.stage) { "script" -> R.string.assistant_attach_stage_script; "voice" -> R.string.assistant_attach_stage_voice; "mix" -> R.string.assistant_attach_stage_mix; else -> R.string.assistant_attach_stage_script }
                    Text(stringResource(stage) + if (j.state == "running") " · ${Math.round(AudioJobs.share(j) * 100)}%" else "", style = MaterialTheme.typography.labelLarge)
                    LinearProgressIndicator(progress = { AudioJobs.share(j) }, Modifier.fillMaxWidth())
                }
                else -> AudioPlayer(j, tetra)
            }
        }
    }
}

@Composable
private fun AudioPlayer(j: AudioJobs.Job, tetra: File) {
    val context = LocalContext.current
    val player = remember(j.audio) { runCatching { android.media.MediaPlayer().apply { setDataSource(j.audio!!.path); prepare() } }.onFailure { WriterLog.w("AI Assist: the audio cannot be played", it) }.getOrNull() }
    DisposableEffect(player) { onDispose { player?.release() } }
    var playing by remember { mutableStateOf(false) }
    var at by remember { mutableIntStateOf(0) }
    LaunchedEffect(playing) { while (playing) { at = player?.currentPosition ?: 0; if (player?.isPlaying != true) playing = false; delay(500) } }
    var saved by remember { mutableStateOf<String?>(null) }
    val total = (j.seconds * 1000).toInt().coerceAtLeast(1)
    fun clock(ms: Int) = "%d:%02d".format(Locale.ROOT, ms / 60_000, ms / 1000 % 60)
    Text(j.title.ifBlank { j.name }, style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledIconButton(onClick = { player ?: return@FilledIconButton; if (player.isPlaying) { player.pause(); playing = false } else { player.start(); playing = true } }, enabled = player != null) {
            Icon(painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play), contentDescription = null)
        }
        LinearProgressIndicator(progress = { at.toFloat() / total }, Modifier.weight(1f))
        Text("${clock(at)} / ${clock(total)}", style = MaterialTheme.typography.labelMedium)
    }
    if (j.chapters.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        j.chapters.forEach { c -> AssistChip(onClick = { player?.let { it.seekTo((c.start * 1000).toInt()); it.start(); playing = true; at = it.currentPosition } },
            label = { Text("${clock((c.start * 1000).toInt())} ${c.title}", maxLines = 1) }) }
    }
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { saved = runCatching { AudioJobs.save(tetra, j) }.map { context.getString(R.string.assistant_attach_saved_in, "Assistant/${it.name}") }
            .getOrElse { WriterLog.e("AI Assist: the audio was not saved", it); it.message } }) { Text(stringResource(R.string.assistant_attach_save), maxLines = 1) }
        TextButton(onClick = { runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", j.audio!!)
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
        }.onFailure { WriterLog.e("AI Assist: the audio was not shared", it) } }) {
            Icon(painterResource(R.drawable.ic_share), contentDescription = null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.common_share), maxLines = 1)
        }
    }
    saved?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

/** Until Tetra is the phone's assistant (the user: a clear button, so the power button opens the sheet); then nothing. */
@Composable
internal fun DefaultAssistantCard() {
    val context = LocalContext.current
    val version = resumes()
    if (remember(version.intValue) { isDefaultAssistant(context) }) return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.assistant_default_make), style = MaterialTheme.typography.titleMedium)
        Text(defaultAssistantHint(context), style = MaterialTheme.typography.bodySmall)
        Button(onClick = { openAssistantChoice(context) }) { Text(stringResource(R.string.assistant_choose), maxLines = 1) }
    }
}

/** The Mistral key: here when it is missing, and in Settings › Assistant. */
@Composable
internal fun AssistantKeyField(saved: () -> Unit = {}) {
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    var has by remember { mutableStateOf(AssistantKey.get(context) != null) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.assistant_key), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(if (has) R.string.assistant_key_saved else R.string.assistant_key_missing), style = MaterialTheme.typography.bodySmall,
            color = if (has) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(key, { key = it }, Modifier.weight(1f), singleLine = true, visualTransformation = PasswordVisualTransformation(),
                placeholder = { Text(stringResource(R.string.assistant_key_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) })
            Button(onClick = { runCatching { AssistantKey.set(context, key) }.onFailure { WriterLog.e("Assistant: the key was not saved", it) }
                key = ""; has = AssistantKey.get(context) != null; if (has) saved() }, enabled = key.isNotBlank()) { Text(stringResource(R.string.common_save), maxLines = 1) }
        }
        Text(stringResource(R.string.assistant_key_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    }
}
