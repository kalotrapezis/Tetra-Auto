package com.kalotrapezis.drive

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/*
 * Tetra Assistant (Plan.md § Assistant): walkie-talkie, not live. What was said is recorded on the phone, written out
 * by Mistral's Voxtral, and sent with the conversation so far to mistral-small, which may call Tetra's tools; the
 * tools run here, on the phone's own files, and hand back only what the model needs (names and titles, never a
 * whole calendar, contact list or note). A call waits for the user's own yes (a tap, or a spoken "ναι" heard by the
 * phone, not the model). Web search and pictures are Mistral's own tools, through its Conversations API: the chat
 * API does not run them (docs.mistral.ai, Oct 2026).
 */

/** A call the model asked for, waiting for the user's yes. */
/** [speaker]: on the loudspeaker from the start («στο μεγάφωνο», «σε ανοιχτή ακρόαση»). */
internal data class PendingCall(val name: String, val number: String, val speaker: Boolean = false)

/** Something the screen offers to tap: a contact to call, a file to open, or a place in Tetra to go ([go]). */
internal data class AssistantChoice(val label: String, val call: PendingCall? = null, val file: File? = null, val go: String? = null)

/**
 * What an action found or will do, shown as a card under the answer (the user, 1 Nov 2026: "show the info in a card instead:
 * if it's a contact, … name, number, recent calls and the option to open or call … the email, the receiver and my account as
 * the sender, to spot any wrong info"); the model's words stay short.
 */
internal sealed class ResultCard {
    data class Number(val label: String, val value: String, val default: Boolean)
    data class Contact(val uid: String, val name: String, val photo: String?, val numbers: List<Number>) : ResultCard()
    data class Source(val title: String, val url: String)
    /** [query]: what was searched, for the card's «search in the Browser». */
    data class Web(val sources: List<Source>, val query: String = "") : ResultCard()
    data class Files(val files: List<File>, val root: File) : ResultCard()
    /** A mail written, waiting for Send: from the user's [account] ([from], its address), to [to]. */
    data class Mail(val account: String, val from: String, val to: String, val subject: String, val body: String) : ResultCard()
    data class Mails(val mails: List<MailEntry>) : ResultCard()
    data class Event(val title: String, val start: ZonedDateTime, val end: ZonedDateTime, val allDay: Boolean, val calendar: String, val place: String) : ResultCard()
    data class Note(val id: String, val title: String, val lines: List<String>) : ResultCard()
    data class Timer(val label: String, val endsAt: Long) : ResultCard()
    /** The reminders by place, each with an × that deletes it (the user, 15 Nov: "show me a list with an x so I can select what to delete"). */
    data class Reminders(val items: List<PlaceReminder>) : ResultCard()
    /** An SMS written, waiting for Send: to [name] ([number] itself when it is no contact). */
    data class Sms(val name: String, val number: String, val text: String) : ResultCard()
    /**
     * This device's mobile data in the cycle ([size] 0: an unlimited plan); [cut] the most a day to stay within the plan when the
     * forecast goes over it, else 0. [hotspot]: a tablet without a SIM, [used] what it took over the phone's hotspot this month.
     */
    data class Data(val used: Long, val size: Long, val left: Long, val daysLeft: Int, val today: Long, val average: Long, val projection: Long,
        val cut: Long, val hotspot: Boolean = false) : ResultCard()
    /** The latest daily brief (latest.json): its title, first lines and time; a tap opens [file]. */
    data class Brief(val title: String, val lines: List<String>, val at: Long, val file: File) : ResultCard()

    companion object {
        /** A contact's numbers, the default marked (none marked: the first, when it has one only). */
        fun numbers(i: ContactInfo) = i.phones.map { Number(it.label, it.value, it.value == i.defaultPhone || (i.phones.size == 1)) }
        fun contact(i: ContactInfo) = Contact(i.uid, i.name, i.photo, numbers(i))
    }
}

/** What a tool hands back: words for the model (as few as it needs), and what the screen shows besides. */
internal data class ToolResult(val forModel: String, val choices: List<AssistantChoice> = emptyList(), val call: PendingCall? = null,
    val image: File? = null, val open: File? = null, val go: String? = null, val photos: List<File> = emptyList(), val beside: Boolean = false, val audio: AudioAsk? = null,
    val cards: List<ResultCard> = emptyList())

/** The model's answer to one turn, with everything the tools left for the screen. */
internal data class AssistantReply(val text: String, val choices: List<AssistantChoice> = emptyList(), val call: PendingCall? = null,
    val images: List<File> = emptyList(), val open: File? = null, val go: String? = null, val beside: Boolean = false, val audio: AudioAsk? = null,
    val cards: List<ResultCard> = emptyList(), val confirm: AssistantGuard.Confirm? = null)

/**
 * What a conversation has cost so far, by Mistral's list prices (the user: "the bill came"; shown in the chat's top bar and
 * on the live screen). [record] gets each call's line for the usage record (AssistantUsage), tagged with what it was for
 * ([tag] f: chat, live, dictation, voice, web, image…); [part] makes a bill for one part of the work, whose costs are also
 * its owner's (the desktop's Bill.for).
 */
internal class Bill(private val counted: (Double) -> Unit = {}, private val record: ((Map<String, Any>) -> Unit)? = null,
    val tag: Map<String, Any> = mapOf("f" to "chat"), private val owner: Bill? = null) {
    @Volatile var usd = 0.0; private set
    /** [counted] gets each cost too: the meter in Settings sums every chat's from here, so nothing is counted twice. */
    fun add(x: Double, line: Map<String, Any> = emptyMap()) {
        if (!(x > 0)) return
        var b: Bill? = this
        while (b != null) { synchronized(b) { b.usd += x }; b = b.owner }
        counted(x)
        record?.invoke(tag + line + ("usd" to x))
    }
    @Synchronized fun reset() { usd = 0.0 }
    fun part(f: String, more: Map<String, Any> = emptyMap()) = Bill(counted, record, tag + more + ("f" to f), this)
    fun tokens(model: String, usage: JSONObject?) {
        val (inPrice, outPrice) = TOKENS[model] ?: return
        usage ?: return
        val i = usage.optLong("prompt_tokens"); val o = usage.optLong("completion_tokens")
        add((i * inPrice + o * outPrice) / 1e6, mapOf("m" to model, "i" to i, "o" to o))
    }
    fun minutes(model: String, seconds: Double) = add((PER_MINUTE[model] ?: 0.0) * seconds / 60, mapOf("m" to model, "s" to Math.round(seconds * 10) / 10.0))
    /** Mistral's voice is paid by the character read. */
    fun chars(text: String) = add(text.length * PER_CHAR, mapOf("m" to Mistral.TTS, "c" to text.length))

    companion object {
        // shortcut: Mistral's list prices in $ as published Oct 2026 (tools' from price tables, not Mistral's page); update when they change
        val TOKENS = mapOf(Mistral.CHAT to (0.1 to 0.3), Mistral.BUILT_IN to (0.4 to 2.0), Mistral.LARGE to (0.5 to 1.5))
        private val PER_MINUTE = mapOf(Mistral.TRANSCRIBE to 0.004, Mistral.REALTIME to 0.006)
        fun perMinute(model: String) = PER_MINUTE[model] ?: 0.0
        val PER_CALL = mapOf("web_search" to 0.03, "image_generation" to 0.10)
        const val PER_CHAR = 0.016 / 1000 // voxtral-mini-tts-3: $0.016 a thousand characters (Mistral's Voxtral TTS page)
        const val PER_PAGE = 0.001 // mistral-ocr: $1 a thousand pages (the computer's; Tesseract on the phone is free)

        /** The prices the costs are worked out with, for the dashboard: when they were last checked, and each one (model, price, per). */
        const val PRICES_CHECKED = "2026-10-31"
        val PRICES: List<Triple<String, String, String>> get() {
            fun n(x: Double) = java.math.BigDecimal(x).round(java.math.MathContext(6)).stripTrailingZeros().toPlainString()
            return TOKENS.map { (m, p) -> Triple(m, "€${n(p.first)} / €${n(p.second)}", "tokens") } + PER_MINUTE.map { (m, x) -> Triple(m, "€${n(x)}", "minute") } +
                Triple(Mistral.TTS, "€${n(PER_CHAR * 1000)}", "chars") + Triple("mistral-ocr-latest", "€${n(PER_PAGE)}", "page") + PER_CALL.map { (m, x) -> Triple(m, "€${n(x)}", "call") }
        }

        /** "€0.0123": a cent's fractions while it is small (the user pays Mistral in euros; the same figures as the dollar list). */
        fun text(usd: Double) = if (usd < 1) "€%.4f".format(java.util.Locale.ROOT, usd) else "€%.2f".format(java.util.Locale.ROOT, usd)
    }
}

/**
 * Every AI Assist cost, like a car's odometer and trip meter: [total] never goes back, [reset] starts a new trip from
 * [since] and keeps the one before in [past], newest first.
 */
internal class Meter(var total: Double = 0.0, var trip: Double = 0.0, var since: Long = 0L, val past: MutableList<Trip> = mutableListOf()) {
    data class Trip(val from: Long, val to: Long, val usd: Double)
    fun add(x: Double) { total += x; trip += x }
    fun reset(at: Long) { past.add(0, Trip(since, at, trip)); trip = 0.0; since = at }
    fun json(): String = JSONObject().put("total", total).put("trip", trip).put("since", since)
        .put("past", JSONArray(past.map { JSONObject().put("from", it.from).put("to", it.to).put("usd", it.usd) })).toString()

    companion object {
        /** The saved meter, or a new one counting from [now]. */
        fun of(json: String?, now: Long): Meter = runCatching {
            val o = JSONObject(json!!); val p = o.optJSONArray("past") ?: JSONArray()
            Meter(o.optDouble("total", 0.0), o.optDouble("trip", 0.0), o.optLong("since", now),
                (0 until p.length()).map { p.getJSONObject(it).let { t -> Trip(t.getLong("from"), t.getLong("to"), t.getDouble("usd")) } }.toMutableList())
        }.getOrElse { Meter(since = now) }
    }
}

/**
 * Mistral's REST API; [http] posts a path and a body and answers the response's text, [stream] posts and hands back each
 * line of a server-sent stream (tests give their own).
 */
internal class Mistral(private val http: (path: String, body: String) -> String, val bill: Bill = Bill(),
    private val stream: ((path: String, body: String, line: (String) -> Unit) -> Unit)? = null, val provider: AiProvider = AiProviders.MISTRAL) {
    /** One chat turn; with [onText] (and a [stream]) its words come as they are made, so the voice can start on the first sentence. */
    fun chat(messages: JSONArray, tools: JSONArray, onText: ((String) -> Unit)? = null, choice: String = "auto"): JSONObject {
        val body = adapt(JSONObject().put("model", CHAT).put("messages", messages).put("tools", tools).put("tool_choice", choice).put("temperature", 0.2))
        val model = body.getString("model") // the chosen service's, for the bill
        if (onText == null || stream == null) return JSONObject(http("/chat/completions", body.toString()))
            .also { bill.tokens(model, it.optJSONObject("usage")) }
            .getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val text = StringBuilder(); val calls = sortedMapOf<Int, JSONObject>()
        stream.invoke("/chat/completions", body.put("stream", true).toString()) { line ->
            val data = line.removePrefix("data:").trim().takeIf { line.startsWith("data:") && it.startsWith("{") } ?: return@invoke
            val chunk = JSONObject(data)
            chunk.optJSONObject("usage")?.let { bill.tokens(model, it) }
            val delta = chunk.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: return@invoke
            AssistantChat.words(delta).takeIf { it.isNotEmpty() }?.let { text.append(it); onText(it) }
            // a call comes whole or in pieces by its index: the pieces' arguments are joined
            delta.optJSONArray("tool_calls")?.let { tc -> for (i in 0 until tc.length()) {
                val piece = tc.getJSONObject(i); val f = piece.optJSONObject("function") ?: JSONObject()
                val c = calls.getOrPut(piece.optInt("index", calls.size)) { JSONObject().put("type", "function").put("function", JSONObject().put("name", "").put("arguments", "")) }
                piece.optString("id").takeIf { it.isNotEmpty() }?.let { c.put("id", it) }
                val cf = c.getJSONObject("function")
                f.optString("name").takeIf { it.isNotEmpty() }?.let { cf.put("name", it) }
                when (val a = f.opt("arguments")) { is String -> cf.put("arguments", cf.getString("arguments") + a); is JSONObject -> cf.put("arguments", a.toString()) }
            } }
        }
        return JSONObject().put("role", "assistant").put("content", text.toString()).apply { if (calls.isNotEmpty()) put("tool_calls", JSONArray(calls.values)) }
    }

    /** A plain completion without tools ([json]: one JSON object back), by [model]; its text. */
    fun complete(messages: JSONArray, json: Boolean = false, model: String = CHAT, temperature: Double = 0.4): String {
        val body = adapt(JSONObject().put("model", model).put("messages", messages).put("temperature", temperature).apply { if (json) put("response_format", JSONObject().put("type", "json_object")) })
        return JSONObject(http("/chat/completions", body.toString())).also { bill.tokens(body.getString("model"), it.optJSONObject("usage")) }
            .getJSONArray("choices").getJSONObject(0).getJSONObject("message").let(AssistantChat::words)
    }

    /**
     * [b] as the chosen service takes it: Mistral's unchanged; another's model and extras, "required" for Mistral's "any", no
     * temperature for a reasoning model, no name on a tool's answer, and the word JSON said when JSON is asked (OpenAI's rule).
     * shortcut: another service's prices are not in [Bill], so its costs count as nothing; add them when one is used much.
     */
    private fun adapt(b: JSONObject): JSONObject {
        if (provider.isMistral) return b
        b.put("model", provider.chat)
        provider.chatExtra.forEach { (k, v) -> b.put(k, v) }
        if (!provider.temperature) b.remove("temperature")
        if (b.optString("tool_choice") == "any") b.put("tool_choice", "required")
        val m = b.getJSONArray("messages")
        b.put("messages", JSONArray((if (b.has("response_format")) listOf(JSONObject().put("role", "system").put("content", "Answer in JSON.")) else emptyList()) +
            (0 until m.length()).map { i -> m.getJSONObject(i).let { o -> if (o.optString("role") == "tool") JSONObject(o.toString()).apply { remove("name") } else o } }))
        return b
    }

    /** The same API, its costs recorded as [feature] (the desktop's Mistral.as). */
    fun tagged(feature: String, more: Map<String, Any> = emptyMap()) = Mistral(http, bill.part(feature, more), stream, provider)

    /** One stateless turn of the Conversations API with one of Mistral's own tools (web_search, image_generation). */
    fun conversation(input: String, tool: String, instructions: String): JSONArray = JSONObject(http("/conversations", JSONObject()
        .put("model", BUILT_IN).put("inputs", input).put("instructions", instructions).put("store", false)
        .put("tools", JSONArray().put(JSONObject().put("type", tool))).toString()))
        .also { bill.tokens(BUILT_IN, it.optJSONObject("usage")); bill.add(Bill.PER_CALL[tool] ?: 0.0, mapOf("m" to tool)) }
        .getJSONArray("outputs")

    companion object {
        const val API = "https://api.mistral.ai/v1"
        const val CHAT = "mistral-small-latest"
        const val BUILT_IN = "mistral-medium-latest" // the model Mistral's web search and image examples use
        const val LARGE = "mistral-large-latest" // a show's script (the user, 14 Nov 2026: "when it creates a podcast can we use the bigger model")
        const val TRANSCRIBE = "voxtral-small-transcribe-3"
        const val REALTIME = "voxtral-mini-transcribe-realtime-3"
        const val TTS = "voxtral-mini-tts-3"

        /**
         * Voxtral's context_bias from "My words" (comma-separated, Settings › AI Assist) and the extra names: at most 100 terms,
         * as Voxtral takes (the VOICE app's terms()).
         */
        fun terms(words: String, more: List<String> = emptyList()): List<String> =
            (words.split(',') + more).map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(100)

        /** A conversation's last answer: its text pieces joined, the sources' titles and links, the files it made. */
        fun output(outputs: JSONArray): Triple<String, List<String>, List<String>> {
            val text = StringBuilder(); val sources = mutableListOf<String>(); val files = mutableListOf<String>()
            for (i in 0 until outputs.length()) {
                val o = outputs.getJSONObject(i)
                if (o.optString("type") != "message.output") continue
                when (val c = o.opt("content")) {
                    is String -> text.append(c)
                    is JSONArray -> for (j in 0 until c.length()) c.optJSONObject(j)?.let { k -> when (k.optString("type")) {
                        "text" -> text.append(k.optString("text"))
                        "tool_reference" -> sources += listOf(k.optString("title"), k.optString("url")).filter { it.isNotBlank() }.joinToString(" — ")
                        "tool_file" -> files += k.optString("file_id")
                    } }
                }
            }
            return Triple(text.toString().trim(), sources.distinct(), files.filter { it.isNotBlank() })
        }
    }
}

/**
 * The tools, each a thin bridge into Tetra's own code on the files under [tetra]. [changed] tells the app what was
 * written ("calendar", "timer", "notes") so it syncs and plans reminders; [search] and [image] are Mistral's tools.
 * One per conversation: the numbered lists it last showed are what "the first one" means.
 */
internal class AssistantTools(
    private val tetra: File,
    private val device: String,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> ZonedDateTime = { ZonedDateTime.now(zone) },
    private val changed: (String) -> Unit = {},
    /** The answer's text and its sources (title, link). */
    private val search: (String) -> Pair<String, List<ResultCard.Source>> = { error("No web search here.") },
    private val image: (String) -> File = { error("No pictures here.") },
    /** The UID of the user's own card in Contacts (Apple's "My Card"), where their relations are kept; asked once. */
    private val me: () -> String? = { null },
    private val setMe: (String) -> Unit = {},
    /** The library's photos taken at a place: by the place name each keeps, else near where [at] finds it. */
    private val photos: (place: String, at: () -> Pair<Double, Double>?) -> List<File> = { _, _ -> emptyList() },
    /** Place reminders on and the location allowed all the time (LocationReminders.ready). */
    private val placesOn: () -> Boolean = { false },
    /** A file's text (Attach.extract; the phone's with its PDF and OCR): the attached one read again when a kept chat asks. */
    private val extract: (File) -> Attach.Doc = { Attach.extract(it) },
    /** The phone's SMS (SmsBox.read), newest first; null while Tetra may not read them. */
    private val texts: () -> List<SmsText>? = { null },
    /** The phone's call log (PhoneLog.read), newest first; null while Tetra may not read it or on a tablet without a SIM. */
    private val calls: () -> List<LoggedCall>? = { null },
    /** The photos taken between two moments (ms), newest first (the phone's own folders). */
    private val photosBetween: (Long, Long) -> List<File> = { _, _ -> emptyList() },
    /** Where the phone was last (lat, lng); null without a fix or the permission. */
    private val here: () -> Pair<Double, Double>? = { null },
    /** A media key (KeyEvent.KEYCODE_MEDIA_…) to whatever plays. */
    private val mediaKey: (Int) -> Unit = {},
    /** The music muted or heard again, the voices still heard. */
    private val muteMusic: (Boolean) -> Unit = {},
    /** Do Not Disturb on (until a time, ms) or off: what happened, for the model. */
    private val quiet: (Boolean, Long?) -> String = { _, _ -> "Do Not Disturb cannot be set here." },
    /** A web page's HTML (web read). */
    private val fetchPage: (String) -> String = { PageText.fetch(it) },
    /** The Browser's bookmarks (a station is one) and Maps' ♪ station (address, name). */
    private val stations: () -> List<Bookmark> = { emptyList() },
    private val mapsStation: () -> Pair<String, String>? = { null },
    /** The media volume: "up", "down", "mute", "unmute" or "set" (with a percent); the percent it is now. */
    private val volume: (String, Int?) -> Int = { _, _ -> 0 },
    /** A command to another device through the computer (AssistantRemote): { ok, device } or { error, devices }. */
    private val send: (JSONObject) -> JSONObject = { JSONObject().put("error", "This phone is not paired with a computer.") },
    /** A tablet without a SIM: its own id in Data's files, whose hotspot use data_status answers; null on a phone. */
    private val hotspotDevice: String? = null,
    /** Tetra on the phone, for the car's settings (Car); null in the tests. */
    private val appContext: android.content.Context? = null,
) {
    /** The file attached in this chat: read, or only its path until attachment_read needs it again (a chat from History). */
    private var doc: Attach.Doc? = null
    var docFile: File? = null; private set
    fun attach(d: Attach.Doc) { doc = d; docFile = File(d.path) }
    fun attach(f: File?) { doc = null; docFile = f?.takeIf { it.isFile } }
    private fun attached(): Attach.Doc? = doc ?: docFile?.let(extract)?.also { doc = it }

    private var contacts = emptyList<PendingCall>()
    private var found = emptyList<ContactInfo>()
    private var files = emptyList<File>()
    private var notesFound = emptyList<Note>()
    private var alarmsShown = emptyList<AlarmRecord>()
    private var mailsShown = emptyList<MailEntry>()
    private var factsShown = emptyList<AssistantMemory.Fact>()
    private var schedulesShown = emptyList<JSONObject>()
    private var smsShown = emptyList<SmsThread>()
    private var callsShown = emptyList<LoggedCall>()
    private var accountsShown = emptyList<MailAccount>()
    private var audioShown = emptyList<AudioJobs.Job>()
    private var eventsShown = emptyList<Occurrence>()
    private var nearShown = emptyList<Poi>()
    /** The note note_new or shopping_list just made: note_pin and note_color without a choice mean it. */
    private var noteMade: String? = null

    /** The personal details (AssistantMemory): in every chat's instructions. */
    fun facts() = AssistantMemory.list(tetra)

    /** What a tool's choice points at in the last list, for a confirmation's card (AssistantGuard). */
    fun picked(name: String, args: JSONObject): String? = if (name == "tetra") AssistantCommands.of(args)?.takeIf { it.first.key == "calls back" }?.second?.firstOrNull()
        ?.toIntOrNull()?.let { callsShown.getOrNull(it - 1) }?.let { nameOf(it.number) } else args.optInt("choice", 0).takeIf { it > 0 }?.let { n -> when (name) {
        "contact_call" -> contacts.getOrNull(n - 1)?.name
        "alarm_delete" -> alarmsShown.getOrNull(n - 1)?.time?.toString()
        "memory_forget" -> factsShown.getOrNull(n - 1)?.text
        "schedule_change", "schedule_delete" -> schedulesShown.getOrNull(n - 1)?.optString("title")
        "mail_export" -> mailsShown.getOrNull(n - 1)?.subject
        "note_pin", "note_color" -> notesFound.getOrNull(n - 1)?.title
        else -> null
    } }

    fun run(name: String, args: JSONObject): ToolResult = runCatching {
        // an app the user keeps the assistant out of (Settings › Βοηθός AI): neither read nor acted in
        APP_OF[name]?.takeIf { !AiGate.state.keeps(it) }?.let { return@runCatching ToolResult("The user does not let the assistant use their $it (Settings › AI Assist). Say so; do not try another way.") }
        // the phone locked (AI Assist over the lock screen, e.g. woken by "Hey Tetra"): personal things only after Android's unlock
        if (personal(name)) appContext?.let { if (!WakeUnlock.ok(it)) return@runCatching ToolResult("The phone is locked and the user did not unlock it, so this cannot be done now. Say they need to unlock the phone for it; do not try another way.") }
        when (name) {
            "calendar_add" -> calendarAdd(args)
            "calendar_list" -> calendarList(args)
            "calendar_free" -> calendarFree(args)
            "pills_list" -> pillsList()
            "pills_save" -> pillsSave(args)
            "pills_taken" -> pillsTaken(args)
            "pills_later" -> pillsLater(args)
            "timer_start" -> timerStart(args)
            "timer_list" -> timerList()
            "timer_stop" -> timerStop(args)
            "alarm_add" -> alarmAdd(args)
            "alarm_list" -> alarmList()
            "alarm_delete" -> pick(alarmsShown, args) { a -> TimerAlarmFiles.write(timers(), a.delete(device)); changed("timer"); ToolResult("Deleted the alarm at ${a.time}.") }
            "stopwatch" -> stopwatch(args)
            "notes_search" -> notesSearch(args)
            "note_new" -> noteNew(args)
            "note_open" -> pick(notesFound, args) { n -> ToolResult("Opened the note.", go = "note:${n.id}") }
            "note_pin" -> noteChange(args) { n -> args.optBoolean("pinned", true).let { on ->
                notes().update(n.id) { it.put("isPinned", on) }; "${if (on) "Pinned" else "Unpinned"} \"${noteTitle(n)}\"." } }
            "note_color" -> NoteColors.hex(args.optString("color")).let { hex -> if (hex == null && !NoteColors.none(args.optString("color"))) ToolResult("Notes has these colours: ${NoteColors.NAMES.keys.joinToString()}, or none.")
                else noteChange(args) { n -> notes().update(n.id) { if (hex == null) it.remove("color") else it.put("color", hex) }; "\"${noteTitle(n)}\" is ${args.optString("color").trim().lowercase()} now." } }
            "data_status" -> dataStatus()
            "brief_show" -> briefShow()
            // a page in Tetra's own Browser: opened, nothing played
            "open_url" -> args.optString("url").trim().let { u -> webAddress(u)?.let { ToolResult("Opened $it in Tetra's Browser.", go = browserAt(it)) } ?: ToolResult("Only a web address (http or https) opens there.") }
            "navigate" -> navigate(args)
            "photos_place" -> photosPlace(args)
            "place_reminder" -> placeReminder(args)
            "reminders_list" -> remindersList()
            "youtube" -> args.getString("query").trim().let { q -> ToolResult("YouTube is open on a search for \"$q\".", go = "youtube:$q") }
            // Tetra's Browser in Player mode: the site's search, its first result played (BrowserScreen's play)
            "media_play" -> args.getString("query").trim().let { q -> MediaSites.byId(args.optString("site")).let { site -> args.optBoolean("playlist").let { list ->
                ToolResult("Playing the first ${if (list && site.lists.isNotEmpty()) "playlist" else "result"} for \"$q\" on ${site.id.replace('_', ' ')} in Tetra's Browser.",
                    go = "browser-play:" + JSONObject().put("site", site.id).put("q", q).put("list", list)) } } }
            // «άνοιξε …», «φέρε μπροστά …» (the user, 9 Nov): in Tetra Car beside the map (carOpenApp)
            "app_open" -> args.getString("name").trim().let { n -> ToolResult("Opening $n${if (Car.running) " beside the map in Tetra Car" else ""}.", go = "app:$n") }
            "car_assist" -> args.let { a ->
                val read = if (a.has("read_messages")) a.getBoolean("read_messages") else null
                val answer = if (a.has("answer_calls")) a.getBoolean("answer_calls") else null
                appContext?.let { c -> Car.set(c, read, answer)
                    ToolResult("In the car: messages ${if (Car.reads(c)) "read aloud" else "not read"}, calls ${if (Car.answers(c)) "answered after 2 seconds" else "not answered by themselves"}.")
                } ?: ToolResult("Not on this device.")
            }
            // Spotify (the user, 9 Nov): «παίξε Spotify» one of their Daily Mixes; what plays added to their Liked Songs
            "spotify_mix" -> ToolResult("Playing one of the user's Spotify Daily Mixes in Tetra's Browser.", go = "browser-play:" + JSONObject().put("site", "spotify").put("mix", true))
            "media_like" -> if (BrowserMedia.control == null || BrowserMedia.title.isEmpty()) ToolResult("Nothing is playing in Tetra's Browser.")
                else { BrowserMedia.act("like"); ToolResult("Asked the site to add \"${BrowserMedia.title}\" to the user's favourites (Spotify's Liked Songs); it works on Spotify only.") }
            // the radio (the user, 4 Nov: "play radio and play the driving radio"): a saved station by name, else Maps' ♪
            "radio_play" -> args.optString("name").trim().let { said ->
                val pick = if (said.isEmpty()) null else MediaSites.station(stations(), said)
                val (url, title) = pick?.let { it.url to it.title } ?: mapsStation() ?: return@let ToolResult(
                    if (said.isEmpty()) "No station is chosen for Maps yet: the user hearts one in the Browser's Player and picks it for Maps." else "No saved station matches \"$said\".")
                ToolResult("Playing ${title.ifEmpty { url }}.", go = "browser-play:" + JSONObject().put("url", url).put("at", System.currentTimeMillis()))
            }
            // to another device (the user, 4 Nov: "play youtube on the tablet", "send it to the PC … the daily brief")
            "send_to_device" -> sendTo(args)
            "volume" -> volume(args.optString("change", "up"), if (args.has("percent")) args.optInt("percent") else null).let { ToolResult("The volume is at $it%.") }
            "shopping_list" -> shoppingList(args)
            "files_find" -> filesFind(args)
            "file_open" -> pick(files, args) { f -> ToolResult("Opened ${f.name}.", open = f, cards = listOf(ResultCard.Files(listOf(f), tetra))) }
            "contacts_find" -> contactsFind(args)
            "my_card" -> myCard(args)
            "relation_remember" -> relationRemember(args)
            "relation_forget" -> relationForget(args)
            "contact_call" -> pick(contacts, args) { c -> ToolResult("Tetra says \"${c.name}\" and calls in 3 seconds${if (args.optBoolean("speaker")) ", on the loudspeaker," else ""} unless the user stops it. Say nothing more.", call = c.copy(speaker = args.optBoolean("speaker")),
                cards = found.firstOrNull { i -> i.phones.any { it.value == c.number } }?.let { listOf(ResultCard.contact(it)) }.orEmpty()) }
            "mail_new" -> mailNew(args)
            "mail_attention" -> mailAttention(args)
            "mail_find" -> mailFind(args)
            "mail_open" -> pick(mailsShown, args) { e -> if (e.gone) ToolResult("Opened the mail (kept only on this device).", open = MailFiles.eml(MailFiles.root(tetra), e))
                else ToolResult("Opened the mail.", go = "mail:${e.id}") }
            // split screen (Beside): the same open, in a window beside AI Assist
            "open_beside" -> args.optString("kind").takeIf { it in setOf("note", "file", "mail") }?.let { k -> run("${k}_open", args).let { r ->
                if (r.open == null && r.go == null) r else r.copy(forModel = r.forModel + " It is beside AI Assist, in split screen.", beside = true) } }
                ?: ToolResult("kind must be note, file or mail.")
            "mail_stored_find" -> mailStoredFind(args)
            "mail_export" -> pick(mailsShown, args) { e -> mailExport(e, args.optString("attachment").trim()) }
            "mail_write" -> mailWrite(args)
            "sms_list" -> smsList(args)
            "sms_read" -> smsRead(args)
            "sms_write" -> smsWrite(args)
            "memory_add" -> AssistantMemory.add(tetra, args.getString("text"), device).let { changed("memory"); ToolResult("Kept: \"${it.text}\". Settings › AI Assist › Personal details shows it.") }
            "memory_list" -> facts().also { factsShown = it }.let { l -> ToolResult(if (l.isEmpty()) "Nothing is kept." else l.mapIndexed { i, f -> "${i + 1}. ${f.text}" }.joinToString("\n")) }
            "memory_forget" -> pick(factsShown, args) { f -> AssistantMemory.change(tetra, f.id, "", device); factsShown = factsShown - f; changed("memory"); ToolResult("Forgot \"${f.text}\".") }
            "schedule_add" -> AssistantSchedules.add(tetra, args).let { changed("schedules"); ToolResult(checked(it, "Scheduled")) }
            "schedule_list" -> AssistantSchedules.list(tetra).also { schedulesShown = it }.let { l -> ToolResult(l.mapIndexed { i, x -> "${i + 1}. ${scheduleLine(x)}" }.joinToString("\n").ifEmpty { "Nothing is scheduled." }) }
            "schedule_change" -> pick(schedulesShown, args) { x ->
                args.optJSONObject("sections")?.takeIf { x.optString("kind") == "brief" }?.let { AssistantSchedules.setSections(tetra, it) }
                val c = AssistantSchedules.change(tetra, x.getString("id"), JSONObject(args.toString()).apply { remove("choice"); remove("sections") })
                changed("schedules"); ToolResult(checked(c, "Changed")) }
            "schedule_delete" -> pick(schedulesShown, args) { x ->
                if (x.optString("kind") == "brief") ToolResult("The daily brief cannot be deleted; pause it instead (schedule_change with paused).")
                else { AssistantSchedules.remove(tetra, x.getString("id")); schedulesShown = schedulesShown - x; changed("schedules"); ToolResult("Deleted \"${x.optString("title")}\".") } }
            "web_search" -> search(args.getString("query")).let { (text, sources) ->
                ToolResult(text + if (sources.isEmpty()) "" else "\nSources: " + sources.take(3).joinToString("; ") { it.title }, cards = if (sources.isEmpty()) emptyList() else listOf(ResultCard.Web(sources, args.getString("query")))) }
            "make_image" -> image(args.getString("prompt")).let { ToolResult("The picture is on the screen, saved as ${it.name}.", image = it) }
            "attachment_read" -> ToolResult(attached()?.let { Attach.read(it, args.optInt("part", 0).takeIf { n -> n > 0 }, args.optString("query")) } ?: "No file is attached in this chat.")
            "audio_make" -> audioMake(args)
            "tetra" -> command(args)
            else -> ToolResult("No tool called $name.")
        }
    }.getOrElse { ToolResult("Failed: ${it.message}") }

    private fun <T> pick(list: List<T>, args: JSONObject, use: (T) -> ToolResult): ToolResult {
        val n = args.optInt("choice", 0)
        return list.getOrNull(n - 1)?.let(use) ?: ToolResult("No choice $n: the last list had ${list.size}.")
    }

    // ---- Calendar ----
    private fun calendars() = CalendarFiles.calendars(CalendarFiles.root(tetra))
    private fun time(s: String): ZonedDateTime = LocalDateTime.parse(s.trim().replace(' ', 'T').take(16)).atZone(zone)
    private fun day(s: String): LocalDate = LocalDate.parse(s.trim().take(10))

    private fun calendarAdd(a: JSONObject): ToolResult {
        val title = a.getString("title").trim()
        val allDay = a.optBoolean("all_day", false)
        val start = if (allDay) day(a.getString("start")).atStartOfDay(zone) else time(a.getString("start"))
        val end = a.optString("end").takeIf { it.isNotBlank() }?.let { if (allDay) day(it).plusDays(1).atStartOfDay(zone) else time(it) }
            ?: if (allDay) start.plusDays(1) else start.plusHours(1)
        val root = CalendarFiles.root(tetra)
        val cal = calendars().firstOrNull() ?: CalendarFiles.newCalendar(root.apply { mkdirs() }, "Calendar", CalendarFiles.PALETTE[0])
        val uid = CalendarFiles.newUid()
        CalendarFiles.save(CalendarFiles.newFile(cal.dir, title, uid),
            CalEvent(uid, title, a.optString("place"), "", allDay, start, end, alarms = defaultAlarms(allDay, start)))
        changed("calendar")
        return ToolResult("Added \"$title\" on ${start.format(SHOWN)} to the calendar ${cal.name}.", go = null,
            cards = listOf(ResultCard.Event(title, start, end, allDay, cal.name, a.optString("place"))))
    }

    private fun calendarList(a: JSONObject): ToolResult {
        val from = day(a.getString("from")).atStartOfDay(zone)
        val to = a.optString("to").takeIf { it.isNotBlank() }?.let { day(it) }?.plusDays(1)?.atStartOfDay(zone) ?: from.plusDays(1)
        val found = CalendarFiles.occurrences(calendars(), from.toInstant(), to.toInstant())
        if (found.isEmpty()) return ToolResult("Nothing in the calendar then.")
        return ToolResult(found.take(30).joinToString("\n") { o ->
            (if (o.allDay) o.start.toLocalDate().toString() + " all day" else o.start.withZoneSameInstant(zone).format(SHOWN) + "–" + o.end.withZoneSameInstant(zone).toLocalTime().format(HM)) + ": " + o.event.title +
                o.event.place.takeIf { it.isNotBlank() }?.let { " @ $it" }.orEmpty()
        })
    }

    /** The free stretches of a day between 08:00 and 21:00 of at least [minutes]. */
    private fun calendarFree(a: JSONObject): ToolResult {
        val d = day(a.getString("date"))
        val need = Duration.ofMinutes(a.optLong("minutes", 60))
        val dayStart = d.atTime(DAY_FROM).atZone(zone); val dayEnd = d.atTime(DAY_TO).atZone(zone)
        val busy = CalendarFiles.occurrences(calendars(), dayStart.toInstant(), dayEnd.toInstant()).filter { !it.allDay }
            .map { it.start.toInstant() to it.end.toInstant() }.sortedBy { it.first }
        val free = mutableListOf<String>()
        var at = maxOf(dayStart.toInstant(), now().toInstant().takeIf { d == now().toLocalDate() } ?: dayStart.toInstant())
        for ((s, e) in busy + (dayEnd.toInstant() to dayEnd.toInstant())) {
            if (Duration.between(at, s) >= need) free += at.atZone(zone).toLocalTime().format(HM) + "–" + s.atZone(zone).toLocalTime().format(HM)
            if (e > at) at = e
        }
        return ToolResult(if (free.isEmpty()) "No free time of that length on $d." else "Free on $d: " + free.joinToString(", "))
    }

    // ---- Timer ----
    // Pills (Pills.kt): the user's medicines, their times, stock and today's doses
    private fun pills() = Pills.dir(tetra).also { runCatching { Pills.migrate(tetra) } }
    private fun pillLine(m: Medicine): String {
        val left = m.daysLeft()
        return "${m.title}: ${Medicine.amount(m.dose)} pill(s) at " + (m.times.joinToString(", ") { it.format(HM) }.ifEmpty { "NO TIME SET" }) +
            (if (m.days.isNotEmpty()) " on " + AlarmRecord.WEEK.filter { it in m.days }.joinToString(",") { AlarmRecord.DAY_CODES[it.ordinal] } else "") +
            (if (m.counted) "; stock ${Medicine.amount(m.stock)}" else "; stock not counted") + (if (m.perBox > 0) " (boxes of ${m.perBox})" else "") +
            (left?.let { "; lasts $it day(s), till ${m.lastDay(now().toLocalDate())}" + if (m.low()) ", LOW: time to order more" else "" } ?: "")
    }

    private fun pillsList(): ToolResult {
        val meds = Pills.medicines(pills()).filter { !it.archived }
        if (meds.isEmpty()) return ToolResult("No medicines in Tetra's Pills yet.")
        val at = now().toInstant()
        val today = Pills.today(meds, Pills.log(pills()), at, zone)
        val stats = Pills.slots(meds, Pills.log(pills()), at, zone).filter { it.due > 0 }.joinToString("\n") { s ->
            "- ${s.med.title} at ${s.time.format(HM)}: of ${s.due} doses ${s.onTime} on time, ${s.late} late, ${s.missed} missed or skipped; ${s.lateMinutes} min late on average" }
        return ToolResult(meds.joinToString("\n") { "- " + pillLine(it) } + (if (stats.isNotEmpty()) "\nThe last 30 days:\n$stats" else "") + "\nToday's doses:\n" +
            today.joinToString("\n") { "- ${it.due.atZone(zone).format(HM)} ${it.med.title}: ${it.state.name.lowercase()}" }.ifEmpty { "none" })
    }

    /** A new box ("I got a new box of X 120 mg, 90 tablets"), a new medicine, or its times changed: one tool. */
    private fun pillsSave(a: JSONObject): ToolResult {
        val dir = pills(); val name = a.optString("name").trim()
        if (name.isEmpty()) return ToolResult("Say which medicine.")
        val strength = a.optString("strength").trim()
        val found = Pills.find(Pills.medicines(dir), name, strength)
        if (found.size > 1) return ToolResult("More than one medicine matches: ${found.joinToString { it.title }}. Ask the user which, then call again with its strength.")
        val times = a.optJSONArray("times")?.let { t -> (0 until t.length()).mapNotNull { runCatching { java.time.LocalTime.parse(t.getString(it).trim().padStart(5, '0')) }.getOrNull() } }
        val days = a.optJSONArray("days")?.let { d -> (0 until d.length()).mapNotNull { AlarmRecord.DAY_CODES.indexOf(d.optString(it).uppercase().take(2)).takeIf { i -> i >= 0 }?.let { i -> AlarmRecord.WEEK[i] } }.toSet() }
        val perBox = a.optInt("per_box", 0).takeIf { it > 0 }
        var m = found.firstOrNull()
        val made = m == null
        if (m == null) m = Medicine(Pills.newId(), name.replaceFirstChar { it.uppercase() }, strength, perBox = perBox ?: 0, since = now().toInstant())
        m = m.copy(
            strength = strength.ifEmpty { m.strength },
            dose = a.optDouble("dose", Double.NaN).takeIf { !it.isNaN() && it > 0 }?.let(Medicine::halves) ?: m.dose,
            times = times?.distinct()?.sorted() ?: m.times, days = days ?: m.days,
            until = a.optString("until").takeIf { it.length == 10 }?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: m.until)
        Pills.save(dir, m)
        val boxes = a.optInt("boxes", 0).coerceAtLeast(0); val loose = a.optDouble("pills", 0.0).takeIf { !it.isNaN() } ?: 0.0
        if (boxes > 0 && (perBox ?: m.perBox) <= 0) { changed("pills"); return ToolResult("${if (made) "Added the medicine ${m.title}, but" else "For ${m.title}"} Tetra does not know how many pills a box holds. Ask the user, then call again with per_box.") }
        if (boxes > 0 || loose > 0) m = Pills.addStock(dir, m, boxes, loose, perBox, now().toInstant(), "AI Assist")
        else if (perBox != null) m = m.copy(perBox = perBox).also { Pills.save(dir, it) }
        changed("pills")
        val what = (if (made) "Added a new medicine. " else "Saved. ") + pillLine(m)
        return ToolResult(what + if (m.times.isEmpty()) "\nIt has no time set, so nothing will remind the user: ask at what time(s) it is taken and call again with times." else "")
    }

    /**
     * "I can't now, I'm driving: tell me in 20 minutes" or "when I get home" (the user, 7 Nov): the doses waiting now rung
     * again then, and not by the clock before. Home is a place saved in Maps (Σπίτι, home), watched by the place reminders.
     */
    private fun pillsLater(a: JSONObject): ToolResult {
        val dir = pills(); val at = now().toInstant()
        val open = Pills.today(Pills.medicines(dir), Pills.log(dir), at, zone).filter { (it.state == Dose.State.Waiting || it.state == Dose.State.Missed) && it.due <= at.plusSeconds(3600) }
        val due = open.minByOrNull { kotlin.math.abs(it.due.epochSecond - at.epochSecond) }?.due ?: return ToolResult(noneWaiting(dir, at, "put off"))
        val names = open.filter { it.due == due }.joinToString { it.med.title }
        val minutes = a.optInt("minutes", 0)
        val placeSaid = a.optString("place").trim()
        if (minutes <= 0 && placeSaid.isEmpty()) return ToolResult("Say in how many minutes, or at which place (home).")
        if (minutes > 0) {
            val until = at.plusSeconds(minutes * 60L)
            Pills.setLater(dir, due, until.toEpochMilli()); changed("pills")
            if (Pills.later(dir)[due] != until.toEpochMilli()) return ToolResult("NOT SAVED: read back from Pills, the dose of ${due.atZone(zone).format(HM)} is not put off. Tell the user plainly it did not work.")
            return ToolResult("$names (dose ${due.atZone(zone).format(HM)}) will be reminded again at ${until.atZone(zone).format(HM)}; read back from Pills: put off.")
        }
        // only the user's own saved places: "home" must not find a taverna called Σπίτι
        val words = MapsSearch.fold(placeSaid).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val home = words.any { it in setOf("σπιτι", "home", "σπιτακι") }
        val saved = MapsFiles.places(MapsFiles.root(tetra)).firstOrNull { p -> MapsSearch.fold("${p.name} ${p.label}").let { n ->
            if (home) n.contains("σπιτι") || n.contains("home") else words.all { it in n } } }
            ?: return ToolResult("There is no place called $placeSaid saved in Maps. Ask the user to save it in Maps (★ on the place), or offer to remind in some minutes instead.")
        LocationReminders.add(tetra, PlaceReminder(MapsFiles.newId(), "", saved.name, saved.lat, saved.lng, LocationReminders.PILLS + due.toEpochMilli()))
        Pills.setLater(dir, due, Pills.AT_PLACE)
        changed("places"); changed("pills")
        if (Pills.later(dir)[due] != Pills.AT_PLACE) return ToolResult("NOT SAVED: read back from Pills, the dose of ${due.atZone(zone).format(HM)} is not put off. Tell the user plainly it did not work.")
        return if (placesOn()) ToolResult("$names will be reminded on arriving at ${saved.name}.")
        else ToolResult("Saved: $names will be reminded on arriving at ${saved.name}, but place reminders are off, so it cannot ring yet. Settings › Calendar is open: the user turns them on there and allows the location all the time.", go = LocationReminders.SCREEN)
    }

    /** "I took it" / "took my pills" (after the reminder): the doses waiting now, or [name]'s; skip when the user says so. */
    private fun pillsTaken(a: JSONObject): ToolResult {
        val dir = pills(); val meds = Pills.medicines(dir); val at = now().toInstant()
        val name = a.optString("name").trim()
        val want = if (name.isEmpty()) meds else Pills.find(meds, name, a.optString("strength"))
        if (want.isEmpty()) return ToolResult("No medicine called $name in Pills.")
        val skip = a.optBoolean("skip")
        val open = Pills.today(want, Pills.log(dir), at, zone).filter { it.state == Dose.State.Waiting || it.state == Dose.State.Missed }
            .filter { it.due <= at.plusSeconds(3 * 3600) } // a dose up to three hours early, not tomorrow's
        val doses = if (name.isEmpty()) open.filter { d -> open.minByOrNull { kotlin.math.abs(it.due.epochSecond - at.epochSecond) }?.due == d.due } else open.groupBy { it.med.id }.mapNotNull { (_, l) -> l.minByOrNull { kotlin.math.abs(it.due.epochSecond - at.epochSecond) } }
        if (doses.isEmpty()) {
            if (name.isEmpty() || skip) return ToolResult(noneWaiting(dir, at, if (skip) "skipped" else "marked taken"))
            val m = Pills.take(dir, want.first(), null, at); changed("pills")
            return ToolResult("Logged one dose of ${m.title} now, outside its times; stock ${Medicine.amount(m.stock)}.")
        }
        val after = doses.map { d -> if (skip) Pills.skip(dir, d.med, d.due, at) else Pills.take(dir, d.med, d.due, at) }
        changed("pills")
        // read back from the files: the answer says what is so, never what was meant (the user, 8 Nov)
        val want2 = if (skip) Dose.State.Skipped else Dose.State.Taken
        val back = Pills.medicines(dir).let { ms -> Pills.log(dir).let { log -> doses.map { d -> d to Pills.doses(ms, log, d.due, d.due.plusSeconds(60), at, zone).firstOrNull { it.med.id == d.med.id }?.state } } }
        val failed = back.filter { it.second != want2 }
        val done = doses.zip(after).filter { (d, _) -> failed.none { it.first == d } }
        return ToolResult(listOfNotNull(
            done.takeIf { it.isNotEmpty() }?.let { l -> (if (skip) "Skipped (read back from Pills): " else "Marked taken (read back from Pills): ") + l.joinToString("; ") { (d, m) -> "${m.title} of ${d.due.atZone(zone).format(HM)}, stock ${Medicine.amount(m.stock)}" + if (m.low()) " (LOW, lasts ${m.daysLeft()} days)" else "" } },
            failed.takeIf { it.isNotEmpty() }?.let { l -> "NOT MARKED: read back from Pills, " + l.joinToString("; ") { (d, st) -> "${d.med.title} of ${d.due.atZone(zone).format(HM)} is ${st?.name?.lowercase() ?: "not found"}" } + ". Tell the user plainly it did not get marked." }
        ).joinToString("\n"))
    }

    /** Nothing waiting, so nothing was [what]: said so, with the dose last answered today (the user took it already, maybe). */
    private fun noneWaiting(dir: File, at: java.time.Instant, what: String): String {
        val last = Pills.today(Pills.medicines(dir), Pills.log(dir), at, zone).filter { it.state == Dose.State.Taken || it.state == Dose.State.Skipped }.maxByOrNull { it.due }
        return "No dose is waiting now, so nothing was $what. Tell the user so plainly; never say it was done." +
            (last?.let { " The last dose answered today: ${it.med.title} of ${it.due.atZone(zone).format(HM)}, ${it.state.name.lowercase()}" + (it.at?.let { a -> " at ${a.atZone(zone).format(HM)}" } ?: "") + "." } ?: "")
    }

    private fun timers() = TimerFiles.root(tetra)

    private fun timerStart(a: JSONObject): ToolResult {
        val seconds = a.optLong("minutes", 0) * 60 + a.optLong("seconds", 0)
        if (seconds <= 0) return ToolResult("Say how long.")
        val r = TimerFiles.start(timers(), seconds, device, now().toInstant(), a.optString("label"))
        changed("timer")
        return ToolResult("Started a timer of ${seconds / 60} min ${seconds % 60} s" + r.label.let { if (it.isBlank()) "." else " ($it)." },
            cards = listOf(ResultCard.Timer(r.label, now().toInstant().toEpochMilli() + seconds * 1000)))
    }

    private fun timerList(): ToolResult {
        val at = now().toInstant()
        val list = TimerFiles.active(timers(), at)
        if (list.isEmpty()) return ToolResult("No timer is running.")
        return ToolResult(list.mapIndexed { i, r -> "${i + 1}. ${r.label.ifBlank { "timer" }}: ${r.leftMs(at) / 60000} min ${r.leftMs(at) / 1000 % 60} s left, ${r.state}" }.joinToString("\n"))
    }

    private fun timerStop(a: JSONObject): ToolResult {
        val list = TimerFiles.active(timers(), now().toInstant())
        val n = a.optInt("choice", 0)
        val stop = if (n == 0) list else listOfNotNull(list.getOrNull(n - 1))
        if (stop.isEmpty()) return ToolResult("No such timer is running.")
        stop.forEach { TimerFiles.write(timers(), it.stop(device)) }
        changed("timer")
        return ToolResult("Stopped ${stop.size} timer(s).")
    }

    // ---- Alarms and the stopwatch ----
    private fun alarmAdd(a: JSONObject): ToolResult {
        val time = LocalTime.parse(a.getString("time").trim().padStart(5, '0').take(5))
        val days = a.optJSONArray("days")?.let { d -> (0 until d.length()).mapNotNull { AlarmRecord.DAY_CODES.indexOf(d.optString(it).uppercase().take(2)).takeIf { i -> i >= 0 }?.let { i -> AlarmRecord.WEEK[i] } } }.orEmpty().toSet()
        val date = if (days.isEmpty()) a.optString("date").takeIf { it.isNotBlank() }?.let { day(it) } else null
        val r = TimerAlarmFiles.add(timers(), time, days, a.optString("label").trim(), device, now().toInstant(), zone, date)
        changed("timer")
        val at = r.next(now().toInstant(), zone)?.atZone(zone)
        return ToolResult("Alarm set for ${time.format(HM)}, " + (if (r.repeats) alarmDays(r) else "once, ${at?.toLocalDate()}") + ".")
    }

    private fun alarmDays(r: AlarmRecord) = if (r.days.size == 7) "every day" else "on " + AlarmRecord.WEEK.filter { it in r.days }.joinToString(",") { AlarmRecord.DAY_CODES[it.ordinal] }

    private fun alarmList(): ToolResult {
        val at = now().toInstant()
        alarmsShown = TimerAlarmFiles.alarms(timers())
        if (alarmsShown.isEmpty()) return ToolResult("No alarms.")
        return ToolResult(alarmsShown.mapIndexed { i, r ->
            "${i + 1}. ${r.time.format(HM)} " + (if (r.repeats) alarmDays(r) else "once") + r.label.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty() + if (r.on(at, zone)) "" else ", off"
        }.joinToString("\n"))
    }

    private fun stopwatch(a: JSONObject): ToolResult {
        val at = now().toInstant()
        val w = TimerAlarmFiles.stopwatch(timers())
        val next = when (a.getString("action")) {
            "start" -> w.start(at, device)
            "pause", "stop" -> w.hold(at, device)
            "reset" -> w.reset(device)
            else -> w
        }
        if (next !== w) { TimerAlarmFiles.write(timers(), next); changed("timer") }
        val t = next.elapsedMs(at) / 1000
        return ToolResult("Stopwatch ${next.state} at ${t / 3600} h ${t % 3600 / 60} min ${t % 60} s.")
    }

    // ---- Notes ----
    private fun notes() = NotesStore(TetraSystem.dir(tetra, "Notes", "Shared"), "tetra-assistant")

    // shortcut: reads every note whole for each search (the user: find what a note has inside); an on-disk text index past a few thousand notes
    private fun notesSearch(a: JSONObject): ToolResult {
        val q = ContactsFiles.plain(a.getString("query"))
        val store = notes()
        val hits = store.whole(store.list().filter { it.trashedAt == null }.map { it.id })
            .filter { ContactsFiles.plain(it.title + "\n" + it.content + "\n" + it.items.joinToString("\n") { i -> i.text }).contains(q) }
            .sortedByDescending { it.updatedAt }.take(9)
        notesFound = hits
        if (hits.isEmpty()) return ToolResult("No note has that.")
        fun title(n: Note) = n.title.ifBlank { n.content.lineSequence().firstOrNull { it.isNotBlank() }?.take(40) ?: "(untitled)" }
        return ToolResult(hits.mapIndexed { i, n ->
            val line = (n.content.lines() + n.items.map { it.text }).firstOrNull { ContactsFiles.plain(it).contains(q) }.orEmpty().trim().take(80)
            "${i + 1}. ${title(n)}" + if (line.isNotBlank()) ": $line" else ""
        }.joinToString("\n"), choices = hits.map { AssistantChoice(title(it), go = "note:${it.id}") })
    }

    private fun noteTitle(n: Note) = n.title.ifBlank { n.content.lineSequence().firstOrNull { it.isNotBlank() }?.take(40) ?: "(untitled)" }

    /** The note from the last notes_search ([a]'s choice), else the one just made; [change] writes it and says what it did. */
    private fun noteChange(a: JSONObject, change: (Note) -> String): ToolResult {
        val n = a.optInt("choice", 0)
        val note = if (n > 0) notesFound.getOrNull(n - 1) ?: return ToolResult("No choice $n: the last notes list had ${notesFound.size}.")
            else noteMade?.let { notes().get(it) } ?: return ToolResult("Which note? Find it with notes_search first.")
        return ToolResult(change(note)).also { changed("notes") }
    }

    private fun noteNew(a: JSONObject): ToolResult {
        val text = a.getString("text").trim().ifEmpty { return ToolResult("Say what the note says.") }
        val store = notes()
        val n = store.create(checklist = false)
        store.setText(n.id, a.optString("title").trim(), text, null)
        noteMade = n.id
        changed("notes")
        return ToolResult("Saved the note in Notes.", cards = listOf(ResultCard.Note(n.id, a.optString("title").trim(), text.lines().filter { it.isNotBlank() }.take(4))))
    }

    // ---- Maps and Photos: the installed map, offline ----
    // shortcut: ranks matches from the middle of Greece, not from where the phone is; pass the fix when "the nearest Lidl" matters
    private fun locate(text: String): Place? {
        val root = MapsFiles.root(tetra)
        // the user's own places first (the user, 30 Oct: "Άγιος Θωμάς, Σπίτι" found nothing): every word in its name or
        // label, commas and the like aside
        val words = MapsSearch.fold(text).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        if (words.isNotEmpty()) MapsFiles.places(root).firstOrNull { p -> MapsSearch.fold("${p.name} ${p.label}").let { n -> words.all { it in n } } }
            ?.let { return Place("", it.name, it.lat, it.lng) }
        val pois = runCatching { MapsSearch.load(root) }.getOrDefault(emptyList())
        runCatching { MapsAddresses.locate(root, text, pois) }.getOrNull()?.let { (lat, lng) -> return Place("", text, lat, lng) }
        return MapsSearch.search(pois, text, 38.4, 23.8, limit = 1).firstOrNull()?.let { Place("", it.name, it.lat, it.lng) }
    }

    private fun navigate(a: JSONObject): ToolResult {
        val contact = a.optString("contact").trim()
        // a contact's address stays on the phone: the model says whose, Tetra looks it up
        val where = if (contact.isNotEmpty()) named(contact).firstNotNullOfOrNull { it.addresses.firstOrNull()?.text()?.takeIf { t -> t.isNotBlank() } }
            ?: return ToolResult("No address is saved for $contact.")
            else a.optString("place").trim().ifEmpty { return ToolResult("Say where to.") }
        val p = locate(where) ?: return ToolResult("Not on the map; Tetra Maps is open on a search for it.", go = "maps-search:$where")
        return ToolResult("Directions to ${if (contact.isNotEmpty()) contact else p.name} started in Tetra Maps.", go = "maps-go:${p.lat},${p.lng},${p.name}")
    }

    private fun placeReminder(a: JSONObject): ToolResult {
        val kind = a.optString("kind").trim().takeIf { k -> MapsSearch.PILLS.any { it.key == k } }.orEmpty()
        val note = a.optString("note").trim()
        val r = if (kind.isNotEmpty()) PlaceReminder(MapsFiles.newId(), kind, "", 0.0, 0.0, note) else {
            val where = a.optString("place").trim().ifEmpty { return ToolResult("Say which place or which kind of shop.") }
            val p = locate(where) ?: return ToolResult("$where is not on the map.")
            PlaceReminder(MapsFiles.newId(), "", p.name, p.lat, p.lng, note)
        }
        LocationReminders.add(tetra, r)
        changed("places")
        val what = if (kind.isNotEmpty()) "any $kind" else r.name
        val list = if (note.isEmpty()) "the newest checklist with items left" else "the newest checklist titled \"$note\""
        return if (placesOn()) ToolResult("Set: arriving at $what shows $list.")
        else ToolResult("Saved: arriving at $what will show $list, but place reminders are off. Settings › Calendar is open: the user turns them on there and allows the location all the time.", go = LocationReminders.SCREEN)
    }

    private fun remindersList(): ToolResult {
        val all = LocationReminders.list(tetra).filter { !it.note.startsWith(LocationReminders.PILLS) } // a pill put off is Pills', not a list's
        if (all.isEmpty()) return ToolResult("No reminders by place.")
        return ToolResult("${all.size} reminders by place, on the screen with an × each to delete it (deleting is the user's tap):\n" +
            all.joinToString("\n") { r -> "- arriving at ${if (r.kind.isNotEmpty()) "any ${r.kind}" else r.name}: ${if (r.note.isEmpty()) "the newest checklist with items left" else "the checklist \"${r.note}\""}" },
            cards = listOf(ResultCard.Reminders(all)))
    }

    private fun photosPlace(a: JSONObject): ToolResult {
        val place = a.getString("place").trim()
        val found = photos(place) { locate(place)?.let { it.lat to it.lng } }
        if (found.isEmpty()) return ToolResult("No photo from $place.")
        return ToolResult("${found.size} photos from $place; the newest are on the screen.", photos = found.take(SHOWN_PHOTOS))
    }

    private fun sendTo(a: JSONObject): ToolResult {
        val what = a.optString("what")
        val args = JSONObject(a.toString())
        if (what == "radio") { // the station found here, from this phone's saved ones (they sync to the other devices too)
            val said = a.optString("station").trim()
            val (url, title) = (if (said.isEmpty()) null else MediaSites.station(stations(), said))?.let { it.url to it.title } ?: mapsStation()
                ?: return ToolResult(if (said.isEmpty()) "No station is chosen for Maps yet." else "No saved station matches \"$said\".")
            args.put("url", url).put("title", title)
        }
        if (what == "play") args.put("q", a.optString("query")).put("list", a.optBoolean("playlist"))
        val r = runCatching { send(AssistantRemote.command(a.optString("device"), what, args)) }
            .getOrElse { return ToolResult("Could not reach the computer: ${it.message}") }
        return if (r.optBoolean("ok")) ToolResult("Sent to ${r.optString("device")}.")
        else ToolResult("Not sent: ${r.optString("error")}" + (r.optJSONArray("devices")?.let { d -> " The devices are: " + (0 until d.length()).joinToString(", ") { d.getString(it) } + "." } ?: ""))
    }

    private fun shoppingList(a: JSONObject): ToolResult {
        val items = a.getJSONArray("items").let { arr -> (0 until arr.length()).map { arr.getString(it).trim() }.filter { it.isNotEmpty() } }
        val store = notes()
        val n = store.create(checklist = true)
        store.setText(n.id, a.optString("title").ifBlank { "Ψώνια" }, "", items.mapIndexed { i, t -> CheckItem(java.util.UUID.randomUUID().toString(), t, false, i) })
        noteMade = n.id
        changed("notes")
        return ToolResult("Made the list \"${a.optString("title").ifBlank { "Ψώνια" }}\" with ${items.size} items in Notes.",
            cards = listOf(ResultCard.Note(n.id, a.optString("title").ifBlank { "Ψώνια" }, items.take(6).map { "☐ $it" })))
    }

    // ---- Data: as Home's Data group reads it ----
    private fun dataStatus(): ToolResult {
        val root = File(tetra, TetraSystem.onDisk(DataFiles.FOLDER))
        val all = DataFiles.all(root)
        val today = now().toLocalDate()
        hotspotDevice?.let { me ->
            val used = DataFiles.days(all[me].orEmpty()).filter { it.day.withDayOfMonth(1) == today.withDayOfMonth(1) }.sumOf { it.viaHotspot }
            return ToolResult("This tablet has no SIM: this month it used ${DataRules.bytes(used)} over the phone's hotspot.", cards = listOf(ResultCard.Data(used, 0, 0, 0, 0, 0, 0, 0, hotspot = true)))
        }
        val plan = DataFiles.plan(root) ?: return ToolResult("No data plan is set up: the user sets one in Tetra's Data.")
        val phone = all.values.maxByOrNull { m -> DataFiles.days(m).sumOf { it.mobile } }.orEmpty()
        val s = DataRules.status(plan, DataFiles.days(phone), today)
        fun b(n: Long) = DataRules.bytes(n)
        val pace = "today ${b(s.today)}, on average ${b(s.average)} a day; ${s.daysLeft} days left in the cycle (it renews on ${s.cycle.end})"
        if (!plan.limited) return ToolResult("Unlimited plan: ${b(s.used)} used this cycle; $pace.", cards = listOf(ResultCard.Data(s.used, 0, 0, s.daysLeft, s.today, s.average, s.projection, 0)))
        val over = s.projection > s.size
        val cut = if (over) s.budget else 0L
        val verdict = when { s.left <= 0 -> "The plan is used up."; over -> "Forecast ${b(s.projection)} by the cycle's end, over the plan: to stay within it, use at most ${b(cut)} a day for the ${s.daysLeft} days left."
            else -> "Forecast ${b(s.projection)} by the cycle's end: on track." }
        return ToolResult("${b(s.used)} of ${b(s.size)} used (${s.used * 100 / s.size.coerceAtLeast(1)}%), ${b(maxOf(0, s.left))} left; $pace. $verdict",
            cards = listOf(ResultCard.Data(s.used, s.size, s.left, s.daysLeft, s.today, s.average, s.projection, cut)))
    }

    // ---- The daily brief: latest.json in AI Assist/Briefs (AssistantBriefs) ----
    private fun briefShow(): ToolResult {
        val dir = File(TetraSystem.dir(tetra, "Assistant", "Shared"), "Briefs")
        val o = runCatching { JSONObject(File(dir, "latest.json").readText()) }.getOrNull() ?: return ToolResult("No daily brief has come yet.")
        val text = o.optString("text").trim(); val at = o.optLong("at")
        val shown = Instant.ofEpochMilli(at).atZone(zone).format(SHOWN)
        return ToolResult("The brief \"${o.optString("title")}\" of $shown is on the screen. It says:\n${text.take(1500)}",
            cards = listOf(ResultCard.Brief(o.optString("title"), text.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(4), at, File(dir, File(o.optString("file")).name))))
    }

    // ---- Files ----
    /** Word by word (FileMatch): the name has every word; else its folders count too; else the closest, said so. */
    private fun filesFind(a: JSONObject): ToolResult {
        val said = a.getString("query")
        val q = FileMatch.query(said)
        WriterLog.i("AI Assist: files_find \"$said\" → words $q")
        if (q.isEmpty()) return ToolResult("Say part of the file's name.")
        val all = DriveRules.allItems(tetra).filter { !it.isDirectory }
        val scored = all.map { it to FileMatch.score(q, it.file.name) }
        var found = scored.filter { it.second == q.size }.map { it.first }
        var near = false
        if (found.isEmpty()) found = all.filter { FileMatch.score(q, it.relativePath) == q.size }
        if (found.isEmpty()) {
            val best = scored.maxOfOrNull { it.second } ?: 0
            if (best > 0 && best * 2 >= q.size) { found = scored.filter { it.second == best }.map { it.first }; near = true }
        }
        val total = found.size
        found = found.sortedByDescending { it.lastModified }.take(8)
        files = found.map { it.file }
        if (found.isEmpty()) return ToolResult("No file's name has that.")
        val head = (if (near) "No name has all those words; the closest:\n" else "") + (if (total > found.size) "$total found, the newest ${found.size}:\n" else "")
        return ToolResult(head + found.mapIndexed { i, it -> "${i + 1}. ${it.relativePath}" }.joinToString("\n"), cards = listOf(ResultCard.Files(found.map { it.file }, tetra)))
    }

    /** An estimate and a card that waits for the user's yes: nothing is spent until then (the computer makes it: AudioJobs). */
    private fun audioMake(a: JSONObject): ToolResult {
        val n = a.optInt("choice", 0)
        // a choice with no files list (the model sends choice 1 for the attached file, seen on the phone): the attached one
        val d = if (n > 0 && files.isNotEmpty()) files.getOrNull(n - 1)?.let(extract) ?: return ToolResult("No choice $n: the last files list had ${files.size}.") else attached()
        d ?: return ToolResult("No file: the user attaches one (the paperclip), or files_find one first.")
        val mode = if (a.optString("mode") == "show") "show" else "narration"
        val length = a.optString("length").takeIf { it in AudioJobs.LENGTHS } ?: "medium"
        // each length's estimate, so the card's short / medium / long shows its own; the minutes said go with the length said
        val estimates = AudioJobs.LENGTHS.keys.associateWith { AudioJobs.estimate(d.text.length, mode, if (it == length) a.optInt("minutes", 0) else 0, it) }
        val est = estimates.getValue(length)
        return ToolResult("Estimate: ≈ ${est.minutes} minutes of audio, ≈ €${"%.2f".format(java.util.Locale.ROOT, est.usd)}, ready in ~${est.ready} minutes; the phone makes it in the background and a notification says when it is ready.${if (mode == "show") " The card lets the user make it short, medium or long." else ""} Say this in one sentence in the user's language and ask whether to start.",
            audio = AudioAsk(d, mode, est.minutes, a.optString("focus"), a.optString("style"), a.optBoolean("per_section", false), est, length, estimates))
    }

    // ---- Mail (the user, 31 Oct: new mail, what needs attention, a mail from someone, write one) ----
    private fun mails() = MailFiles.index(MailFiles.root(tetra), MailEngine.device())
    private fun mailAccounts() = MailFiles.root(tetra).let { r -> MailFiles.accounts(r, MailEngine.ownAccounts(r).map { it.optString("id") }.toSet()) }
    /**
     * The accounts a request names by "account" (its name or its address, or a part: «Πανεπιστήμιο», "nup.ac.cy"), null for every
     * one; none like it: the accounts there are, for the model to ask again (the user, 11 Nov: AI Assist could not see the university's).
     */
    private fun mailAccountIds(a: JSONObject): Result<Set<String>?> {
        val words = ContactsFiles.plain(a.optString("account")).split(Regex("[\\s@.]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return Result.success(null)
        val all = mailAccounts()
        val hit = all.filter { acc -> ContactsFiles.plain("${acc.name} ${acc.address}").let { t -> words.all { it in t } } }
        return if (hit.isNotEmpty()) Result.success(hit.map { it.id }.toSet())
        else Result.failure(IllegalArgumentException("No mail account is called \"${a.optString("account")}\". The accounts: " +
            all.joinToString("; ") { "${it.name} <${it.address}>" } + ". Ask again with one of them (its name or address), or without an account for all of them."))
    }
    /** A numbered list for the model (sender, subject, when, unread/♥, the start of the text) and the screen (each opens the mail). */
    private fun mailList(found: List<MailEntry>, empty: String, stored: Boolean = false): ToolResult {
        mailsShown = found
        if (found.isEmpty()) return ToolResult(empty)
        val root = MailFiles.root(tetra)
        val names = mailAccounts().associate { it.id to it.name }
        return ToolResult(found.mapIndexed { i, e ->
            "${i + 1}. ${e.from.ifEmpty { e.fromAddress }} <${e.fromAddress}> — ${e.subject} (${Instant.ofEpochMilli(e.date).atZone(zone).format(SHOWN)}, ${e.folder}, ${names[e.account] ?: e.account})" +
                (if (!e.seen) " unread" else "") + (if (e.flagged) " ♥" else "") + (if (e.gone) " local only (no longer on the server)" else "") +
                // the stored ones: their attachments by name, to export one
                (if (stored) MailFiles.attachments(root, e).takeIf { it.isNotEmpty() }?.joinToString(", ", " 📎 ") { it.name }.orEmpty() else if (e.attachments > 0) " 📎" else "") +
                e.snippet.take(160).let { if (it.isBlank()) "" else ": $it" }
        }.joinToString("\n"), cards = listOf(ResultCard.Mails(found)))
    }

    /**
     * The mail kept on this device («Αποθήκη», the user, 31 Oct: "search the local storage and find files"): every
     * account's, deleted ones too, by sender, words (subject, recipients, attachments' names) and dates.
     */
    private fun mailStoredFind(a: JSONObject): ToolResult {
        val root = MailFiles.root(tetra)
        fun day(key: String) = a.optString(key).takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val from = day("from_date")?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
        val to = day("to_date")?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()?.minus(1)
        val words = listOf(a.optString("from"), a.optString("query")).joinToString(" ").trim()
        val ids = mailAccountIds(a).getOrElse { return ToolResult(it.message!!) }
        if (words.isEmpty() && from == null && to == null && ids == null) return ToolResult("Say who it is from, some words, the dates or the account.")
        return mailList(MailArchive.search(root, MailArchive.entries(root, device).filter { ids == null || it.account in ids }, words, from, to).take(15),
            "Nothing like that in the mail kept on this device.", stored = true)
    }

    /** One mail of the last list, or one of its attachments, copied into Tetra/Documents (never over a file there). */
    private fun mailExport(e: MailEntry, attachment: String): ToolResult {
        val root = MailFiles.root(tetra)
        val eml = MailFiles.eml(root, e) ?: return ToolResult("That mail is not on this device (only on the PC or the server); it cannot be copied from here.")
        val documents = File(tetra, "Documents")
        if (attachment.isEmpty()) return MailExport.toDocuments(eml, documents, MailExport.emlName(e, eml)).let { ToolResult("Copied the mail to Tetra/Documents/${it.name}.", open = null) }
        val all = MailFiles.attachments(root, e)
        val f = all.firstOrNull { it.name.equals(attachment, true) } ?: all.firstOrNull { it.name.contains(attachment, true) }
            ?: return ToolResult(if (all.isEmpty()) "That mail has no attachments on this device." else "No attachment like \"$attachment\"; it has: ${all.joinToString(", ") { it.name }}.")
        return MailExport.toDocuments(f, documents).let { ToolResult("Copied ${f.name} to Tetra/Documents/${it.name}.") }
    }

    private fun mailNew(a: JSONObject): ToolResult {
        val since = now().minusHours(a.optLong("hours", 24)).toInstant().toEpochMilli()
        val ids = mailAccountIds(a).getOrElse { return ToolResult(it.message!!) }
        return mailList(mails().filter { it.folder == "Inbox" && (ids == null || it.account in ids) && (it.date >= since || (!it.seen && a.optBoolean("unread", true))) }
            .sortedByDescending { it.date }.take(15), "No new mail.")
    }

    /** The recent Inbox mail that is unread or ♥, with the start of each: the model says which need an answer or an action. */
    private fun mailAttention(a: JSONObject): ToolResult {
        val since = now().minusDays(a.optLong("days", 14)).toInstant().toEpochMilli()
        val ids = mailAccountIds(a).getOrElse { return ToolResult(it.message!!) }
        return mailList(mails().filter { it.folder == "Inbox" && (ids == null || it.account in ids) && it.date >= since && (!it.seen || it.flagged) }.sortedByDescending { it.date }.take(25),
            "Nothing unread or marked ♥ in the last ${a.optLong("days", 14)} days.")
    }

    /** Mail from someone (a name, an address, or a contact's addresses) and/or with some words, in every folder but Spam and Trash. */
    private fun mailFind(a: JSONObject): ToolResult {
        val who = ContactsFiles.plain(a.optString("from")).trim()
        val addresses = if (who.isEmpty()) emptySet() else named(a.optString("from")).flatMap { c -> c.emails.map { it.value.lowercase() } }.toSet()
        val words = ContactsFiles.plain(a.optString("query")).split(' ').filter { it.length >= 2 }
        val ids = mailAccountIds(a).getOrElse { return ToolResult(it.message!!) }
        if (who.isEmpty() && words.isEmpty() && ids == null) return ToolResult("Say who it is from, some words or the account.")
        return mailList(mails().filter { e -> e.folder != "Spam" && e.folder != "Trash" && (ids == null || e.account in ids) &&
            (who.isEmpty() || e.fromAddress.lowercase() in addresses || who.split(' ').all { w -> ContactsFiles.plain("${e.from} ${e.fromAddress}").contains(w) }) &&
            words.all { w -> ContactsFiles.plain("${e.subject} ${e.snippet}").contains(w) } }.sortedByDescending { it.date }.take(15), "No mail found.")
    }

    /** A mail written for the user: Tetra's compose page opens filled in, and waits for their «Αποστολή» (never sent from here). */
    private fun mailWrite(a: JSONObject): ToolResult {
        val said = a.getString("to").trim()
        val to = if ('@' in said) said else named(said).flatMap { it.emails }.firstOrNull()?.value
            ?: return ToolResult(if (!AiGate.state.keeps("contacts")) "The assistant may not look in Contacts. Ask the user for $said's address."
                else "$said has no e-mail address in Contacts. Ask the user for the address.")
        val acc = MailFiles.accounts(MailFiles.root(tetra), MailEngine.ownAccounts(MailFiles.root(tetra)).map { it.optString("id") }.toSet()).let { all -> all.firstOrNull { it.own } ?: all.firstOrNull() }
            ?: return ToolResult("There is no mail account in Tetra's Mail. Say so.")
        return ToolResult("The mail to $to is on the screen, written, from ${acc.address}; it waits for the user to check it and press Send.",
            cards = listOf(ResultCard.Mail(acc.id, acc.address, to, a.optString("subject"), a.optString("body"))))
    }

    // ---- Tetra's command line (AssistantCommands): one tool, a command each ----
    private fun command(a: JSONObject): ToolResult {
        val (c, rest) = AssistantCommands.of(a) ?: return ToolResult("No command \"${a.optString("command")}\". " + AssistantCommands.description())
        if (!AiGate.state.keeps(c.gate)) return ToolResult("The user does not let the assistant use their ${c.gate} (Settings › AI Assist). Say so; do not try another way.")
        val n = rest.firstOrNull()?.toIntOrNull()
        return when (c.key) {
            "calls missed" -> callsMissed((n ?: 7).coerceIn(1, 60))
            "calls recent" -> callsRecent((n ?: 15).coerceIn(1, 50))
            "calls back" -> callBack(n)
            "mail accounts" -> mailAccountsList()
            "mail name" -> mailName(n, rest.drop(1).joinToString(" "))
            "calendar find" -> calendarFind(rest.joinToString(" "))
            "notes recent" -> notesRecent((n ?: 8).coerceIn(1, 30))
            "notes add" -> notesAdd(n, rest.drop(1).joinToString(" "))
            "notes trash" -> notesTrash(n)
            "files recent" -> filesRecent(rest.firstOrNull()?.lowercase() ?: "all")
            "audio list" -> audioList()
            "audio play" -> audioPlay(rest.firstOrNull())
            "photos recent" -> (n ?: 1).coerceIn(1, 365).let { d -> photosShown(now().minusDays(d.toLong()).toInstant().toEpochMilli(), now().toInstant().toEpochMilli(), "in the last $d days") }
            "photos day" -> rest.firstOrNull()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { d ->
                photosShown(d.atStartOfDay(zone).toInstant().toEpochMilli(), d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1, "on $d") }
                ?: ToolResult("Say the day as YYYY-MM-DD.")
            "maps places" -> mapsPlaces()
            "contacts add" -> contactsAdd(rest)
            "web read" -> webRead(rest.firstOrNull().orEmpty())
            "media pause" -> media(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, "Paused.")
            "media play" -> media(android.view.KeyEvent.KEYCODE_MEDIA_PLAY, "Playing again.")
            "media next" -> media(android.view.KeyEvent.KEYCODE_MEDIA_NEXT, "The next one.")
            "media previous" -> media(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS, "The one before.")
            "media mute" -> { muteMusic(true); ToolResult("The music is muted. Say nothing more.") }
            "media unmute" -> { muteMusic(false); ToolResult("The music is heard again. Say nothing more.") }
            "quiet on" -> ToolResult(quiet(true, rest.lastOrNull()?.let { t -> clock(t)?.let { at(it).toInstant().toEpochMilli() } })) // "until 21:00" or "21:00"
            "quiet off" -> ToolResult(quiet(false, null))
            "mail read", "mail archive", "mail delete" -> mailAct(c.key.substringAfter(' '), rest)
            "mail reply" -> mailReply(n, rest.drop(1).joinToString(" "))
            "calendar move" -> calendarMove(n, rest.drop(1))
            "calendar cancel" -> calendarCancel(n)
            "reminder add" -> reminderAdd(rest)
            "notes tick" -> notesTick(n, rest.drop(1).joinToString(" "))
            "audio delete" -> audioDelete(n)
            "audio rename" -> audioRename(n, rest.drop(1).joinToString(" "))
            "maps nearest" -> mapsNearest(rest.joinToString(" "))
            "maps go" -> n?.let { nearShown.getOrNull(it - 1) }?.let { p -> ToolResult("Directions to ${p.name} started in Tetra Maps.", go = "maps-go:${p.lat},${p.lng},${p.name}") }
                ?: ToolResult(if (nearShown.isEmpty()) "Run maps nearest first." else "No number ${n ?: ""} in the last list; it has ${nearShown.size}.")
            "contacts birthdays" -> birthdays((n ?: 30).coerceIn(1, 366))
            "contacts phone", "contacts email" -> contactAddWay(c.key == "contacts phone", rest)
            "data tips" -> dataTips()
            "data apps" -> dataApps(n)
            "data days" -> dataDays((n ?: 14).coerceIn(1, 62))
            else -> ToolResult("Not here yet: ${c.key}.")
        }
    }

    // ---- Mail accounts (the user, 11 Nov: "if an account does not have a name, show me the accounts and let me choose to name it") ----
    private fun mailAccountsList(): ToolResult {
        val all = mailAccounts()
        accountsShown = all
        if (all.isEmpty()) return ToolResult("There is no mail account in Tetra's Mail.")
        val unread = mails().filter { it.folder == "Inbox" && !it.seen }.groupingBy { it.account }.eachCount()
        return ToolResult(all.mapIndexed { i, a ->
            "${i + 1}. ${a.name} <${a.address}>${if (a.name.equals(a.address, true)) " (no name of its own)" else ""} — " +
                "${if (a.own) "fetched by this device" else "through the computer"}, ${unread[a.id] ?: 0} unread in the Inbox"
        }.joinToString("\n"))
    }

    private fun mailName(n: Int?, said: String): ToolResult {
        val acc = n?.let { accountsShown.getOrNull(it - 1) } ?: return ToolResult(if (accountsShown.isEmpty()) "No accounts list yet: run mail accounts first."
            else "No number ${n ?: ""} in the accounts list; it has ${accountsShown.size}.")
        val name = said.trim().take(40).ifEmpty { return ToolResult("Say the new name.") }
        val root = MailFiles.root(tetra)
        if (acc.own) MailEngine.rename(root, acc.id, name) else MailRequests.rename(root, MailEngine.device(), acc.id, name)
        changed("mail")
        return ToolResult(if (acc.own) "Named ${acc.address} «$name»." else "Asked the computer to name ${acc.address} «$name»; every device shows it once the computer has saved it.")
    }

    // ---- Calendar: events by their words ----
    private fun calendarFind(said: String): ToolResult {
        val words = ContactsFiles.plain(said).split(' ').filter { it.length >= 2 }.ifEmpty { return ToolResult("Say some words of the event.") }
        val found = CalendarFiles.occurrences(calendars(), now().minusDays(30).toInstant(), now().plusDays(365).toInstant())
            .filter { o -> ContactsFiles.plain("${o.event.title} ${o.event.place} ${o.event.notes}").let { t -> words.all { it in t } } }.take(15)
        eventsShown = found
        if (found.isEmpty()) return ToolResult("No event has those words, from 30 days ago to a year ahead.")
        return ToolResult(found.mapIndexed { i, o -> "${i + 1}. " +
            (if (o.allDay) o.start.toLocalDate().toString() + " all day" else o.start.withZoneSameInstant(zone).format(SHOWN) + "–" + o.end.withZoneSameInstant(zone).toLocalTime().format(HM)) +
            ": " + o.event.title + o.event.place.takeIf { it.isNotBlank() }?.let { " @ $it" }.orEmpty()
        }.joinToString("\n"))
    }

    // ---- Notes: the latest, a line added, one to the Trash ----
    private fun notesRecent(count: Int): ToolResult {
        val list = notes().list().filter { it.trashedAt == null && it.archivedAt == null && !it.empty }.sortedByDescending { it.updatedAt }.take(count)
        notesFound = list
        if (list.isEmpty()) return ToolResult("Notes is empty.")
        return ToolResult(list.mapIndexed { i, n -> "${i + 1}. ${noteTitle(n)} (${Instant.ofEpochMilli(n.updatedAt).atZone(zone).format(SHOWN)})" }.joinToString("\n"),
            choices = list.map { AssistantChoice(noteTitle(it), go = "note:${it.id}") })
    }

    private fun notesAdd(n: Int?, text: String): ToolResult {
        val line = text.trim().ifEmpty { return ToolResult("Say what to add.") }
        val shown = n?.let { notesFound.getOrNull(it - 1) } ?: return ToolResult("Which note? Run notes recent or notes_search first.")
        val store = notes()
        val note = store.get(shown.id) ?: return ToolResult("That note is gone.")
        if (note.checklist) store.setText(note.id, note.title, note.content, note.items + CheckItem(java.util.UUID.randomUUID().toString(), line, false, (note.items.maxOfOrNull { it.order } ?: -1) + 1))
        else store.setText(note.id, note.title, note.content.trimEnd().let { if (it.isEmpty()) line else "$it\n$line" }, null)
        changed("notes")
        return ToolResult("Added to «${noteTitle(note)}»: $line")
    }

    private fun notesTrash(n: Int?): ToolResult {
        val shown = n?.let { notesFound.getOrNull(it - 1) } ?: return ToolResult("Which note? Run notes recent or notes_search first.")
        notes().update(shown.id) { it.put("trashedAt", now().toInstant().toEpochMilli()) }
        changed("notes")
        return ToolResult("«${noteTitle(shown)}» is in the Trash of Notes for 30 days.")
    }

    // ---- Files of a kind, and the audio Library ----
    private val KINDS = mapOf("documents" to setOf("docx", "doc", "odt", "rtf", "txt", "md", "xlsx", "xls", "ods", "csv", "pptx", "ppt", "odp"),
        "pdf" to setOf("pdf"), "pictures" to setOf("jpg", "jpeg", "png", "webp", "heic", "gif"), "audio" to setOf("mp3", "m4a", "ogg", "opus", "wav", "flac", "aac"),
        "video" to setOf("mp4", "mkv", "webm", "mov", "3gp"))

    private fun filesRecent(kind: String): ToolResult {
        val exts = KINDS[kind.removeSuffix("s").let { k -> KINDS.keys.firstOrNull { it.startsWith(k) } }]
        val found = DriveRules.allItems(tetra).filter { !it.isDirectory && (exts == null || it.file.extension.lowercase() in exts) }.sortedByDescending { it.lastModified }.take(10)
        files = found.map { it.file }
        if (found.isEmpty()) return ToolResult("No ${if (exts == null) "" else "$kind "}files in Tetra.")
        return ToolResult(found.mapIndexed { i, it -> "${i + 1}. ${it.relativePath} (${Instant.ofEpochMilli(it.lastModified).atZone(zone).format(SHOWN)})" }.joinToString("\n"),
            cards = listOf(ResultCard.Files(found.map { it.file }, tetra)))
    }

    private fun audioList(): ToolResult {
        val jobs = AudioJobs.dir(tetra).listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { AudioJobs.read(tetra, it.name) }.sortedByDescending { it.changed }.take(15)
        audioShown = jobs
        if (jobs.isEmpty()) return ToolResult("The Library is empty: no audio made yet. audio_make makes one from a file.")
        return ToolResult(jobs.mapIndexed { i, j ->
            "${i + 1}. ${j.title.ifBlank { j.name }} — ${if (j.mode == "show") "show" else "narration"}" +
                (if (j.audio != null) ", ${Math.round(j.seconds / 60).coerceAtLeast(1)} min, ready" else if (j.error.isNotBlank()) ", failed" else ", being made (${Math.round(AudioJobs.share(j) * 100)}%)") +
                ", ${Instant.ofEpochMilli(j.changed).atZone(zone).format(SHOWN)}"
        }.joinToString("\n"))
    }

    private fun audioPlay(which: String?): ToolResult {
        if (audioShown.isEmpty()) audioList()
        val n = which?.toIntOrNull()
        val j = (if (n != null) audioShown.getOrNull(n - 1) else audioShown.firstOrNull { it.audio != null })
            ?: return ToolResult(if (audioShown.isEmpty()) "The Library is empty: no audio made yet." else if (n != null) "No number $n in the Library list." else "Nothing in the Library is ready yet.")
        val f = j.audio ?: return ToolResult("«${j.title.ifBlank { j.name }}» is not ready yet.")
        return ToolResult("Playing «${j.title.ifBlank { j.name }}».", open = f)
    }

    // ---- Photos by day ----
    private fun photosShown(from: Long, to: Long, said: String): ToolResult {
        val found = photosBetween(from, to)
        if (found.isEmpty()) return ToolResult("No photos $said.")
        return ToolResult("${found.size} photos $said; the newest are on the screen.", photos = found.take(SHOWN_PHOTOS))
    }

    // ---- Maps: the user's own places ----
    private fun mapsPlaces(): ToolResult {
        val list = MapsFiles.places(MapsFiles.root(tetra))
        if (list.isEmpty()) return ToolResult("No saved places in Tetra Maps.")
        return ToolResult(list.mapIndexed { i, p -> "${i + 1}. ${p.name}${p.label.takeIf { it.isNotBlank() && it != p.name }?.let { " ($it)" }.orEmpty()}" }.joinToString("\n"))
    }

    // ---- Contacts: a new one ----
    private fun contactsAdd(rest: List<String>): ToolResult {
        // the number as said may come in pieces ("6944 123 456"): every trailing piece of digits is the number
        val tail = rest.takeLastWhile { w -> w.isNotEmpty() && w.all { c -> c.isDigit() || c in "+-()" } }
        val number = tail.joinToString("").takeIf { it.count(Char::isDigit) >= 5 } ?: return ToolResult("Say the name and then the phone number.")
        val name = rest.dropLast(tail.size).joinToString(" ").trim().ifEmpty { return ToolResult("Say the contact's name.") }
        if (allContacts().any { c -> c.info.phones.any { PhoneLog.same(it.value, number) } }) return ToolResult("$number is already in Contacts as ${nameOf(number)}.")
        val words = name.split(' ')
        ContactsFiles.save(ContactsFiles.root(tetra), null, ContactInfo(given = words.first(), family = words.drop(1).joinToString(" "), formatted = name, phones = listOf(VField("cell", number))))
        changed("contacts")
        return ToolResult("Saved $name, $number, in Contacts.")
    }

    // ---- Playing, and Do Not Disturb ----
    private fun media(key: Int, said: String): ToolResult { mediaKey(key); return ToolResult("$said Say nothing more.") }
    private fun clock(t: String) = runCatching { LocalTime.parse(t.padStart(5, '0')) }.getOrNull()
    /** [t] today, or tomorrow when it has passed. */
    private fun at(t: LocalTime) = now().with(t).withSecond(0).withNano(0).let { if (it.isAfter(now())) it else it.plusDays(1) }

    // ---- Mail: mark read, archive, delete (the computer does them for its accounts, as Mail's own buttons), and a reply ----
    private fun picks(rest: List<String>, from: Int): List<Int>? =
        if (rest.firstOrNull()?.lowercase() == "all") (1..from).toList() else rest.mapNotNull { it.trim(',').toIntOrNull() }.filter { it in 1..from }.distinct().ifEmpty { null }

    private fun mailAct(op: String, rest: List<String>): ToolResult {
        if (mailsShown.isEmpty()) return ToolResult("No mail list yet: list or find the mails first.")
        val chosen = picks(rest, mailsShown.size)?.map { mailsShown[it - 1] } ?: return ToolResult("Say which, by their numbers in the last list (1 2 3), or all.")
        val root = MailFiles.root(tetra)
        val own = mailAccounts().filter { it.own }.map { it.id }.toSet()
        val (here, theirs) = chosen.partition { it.account in own }
        if (here.isNotEmpty()) appContext?.let { MailEngine.act(it, root, op, here.map { m -> m.id }) } ?: error("This device cannot reach the mail server from here.")
        if (theirs.isNotEmpty()) MailRequests.write(root, MailEngine.device(), op, theirs.map { it.id })
        changed("mail")
        val what = mapOf("read" to "Marked read", "archive" to "Archived", "delete" to "Moved to the Trash")[op]
        return ToolResult("$what: ${chosen.size} mail${if (chosen.size > 1) "s" else ""}" + if (theirs.isNotEmpty()) " (the computer does it for its accounts at the next sync)." else ".")
    }

    private fun mailReply(n: Int?, body: String): ToolResult {
        val m = n?.let { mailsShown.getOrNull(it - 1) } ?: return ToolResult(if (mailsShown.isEmpty()) "No mail list yet: find the mail first." else "No number ${n ?: ""} in the last mail list.")
        val acc = mailAccounts().firstOrNull { it.id == m.account } ?: mailAccounts().firstOrNull() ?: return ToolResult("There is no mail account in Tetra's Mail. Say so.")
        val subject = if (m.subject.trimStart().startsWith("re:", true)) m.subject else "Re: ${m.subject}"
        return ToolResult("The reply to ${m.from.ifEmpty { m.fromAddress }} is on the screen, written, from ${acc.address}; it waits for the user to check it and press Send.",
            cards = listOf(ResultCard.Mail(acc.id, acc.address, m.fromAddress, subject, body.trim())))
    }

    // ---- Calendar: move, cancel, a reminder ----
    private fun shownEvent(n: Int?): Occurrence? = n?.let { eventsShown.getOrNull(it - 1) }
    private fun repeats(o: Occurrence) = o.event.rrule != null || o.event.recurrenceId != null || o.file.overrides.isNotEmpty()

    private fun calendarMove(n: Int?, rest: List<String>): ToolResult {
        val o = shownEvent(n) ?: return ToolResult("Which event? Run calendar find first and give its number.")
        if (repeats(o)) return ToolResult("«${o.event.title}» repeats: change it in Calendar, where Tetra asks whether this one or all. Say so.")
        val day = rest.firstOrNull()?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: return ToolResult("Say the new day as YYYY-MM-DD.")
        val time = (rest.getOrNull(1) ?: rest.first().substringAfter('T', "")).takeIf { it.isNotBlank() }?.let { clock(it) ?: return ToolResult("Say the time as HH:MM.") }
        val e = o.event
        val start = if (e.allDay) day.atStartOfDay(e.start.zone) else day.atTime(time ?: e.start.withZoneSameInstant(zone).toLocalTime()).atZone(zone)
        CalendarFiles.save(o.file.file, e.copy(start = start, end = start.plus(Duration.between(e.start, e.end))))
        changed("calendar")
        return ToolResult("Moved «${e.title}» to ${if (e.allDay) day.toString() else start.format(SHOWN)}.")
    }

    private fun calendarCancel(n: Int?): ToolResult {
        val o = shownEvent(n) ?: return ToolResult("Which event? Run calendar find first and give its number.")
        if (repeats(o)) return ToolResult("«${o.event.title}» repeats: cancel it in Calendar, where Tetra asks whether this one or all. Say so.")
        CalendarFiles.trash(CalendarFiles.root(tetra), o.file.file)
        changed("calendar")
        return ToolResult("«${o.event.title}» is deleted (in Tetra's Trash for 30 days).")
    }

    private fun reminderAdd(rest: List<String>): ToolResult {
        val w = rest.firstOrNull() ?: return ToolResult("Say when (HH:MM or YYYY-MM-DDTHH:MM) and what.")
        val start = (if ('T' in w) runCatching { LocalDateTime.parse(w).atZone(zone) }.getOrNull() else clock(w)?.let(::at)) ?: return ToolResult("Say when as HH:MM or YYYY-MM-DDTHH:MM.")
        val what = rest.drop(1).joinToString(" ").trim().ifEmpty { return ToolResult("Say what to be reminded of.") }
        val root = CalendarFiles.root(tetra)
        val cal = calendars().firstOrNull() ?: CalendarFiles.newCalendar(root.apply { mkdirs() }, "Calendar", CalendarFiles.PALETTE[0])
        val uid = CalendarFiles.newUid()
        CalendarFiles.save(CalendarFiles.newFile(cal.dir, what, uid), CalEvent(uid, what, "", "", false, start, start.plusMinutes(15), alarms = listOf(CalAlarm(0, 0))))
        changed("calendar")
        return ToolResult("Reminder at ${start.format(SHOWN)}: $what.")
    }

    // ---- Notes: an item ticked ----
    private fun notesTick(n: Int?, said: String): ToolResult {
        val shown = n?.let { notesFound.getOrNull(it - 1) } ?: return ToolResult("Which note? Run notes recent or notes_search first.")
        val store = notes()
        val note = store.get(shown.id) ?: return ToolResult("That note is gone.")
        if (!note.checklist) return ToolResult("«${noteTitle(note)}» is not a checklist.")
        val words = ContactsFiles.plain(said).split(' ').filter { it.length >= 2 }.ifEmpty { return ToolResult("Say which item.") }
        val hits = note.items.filter { i -> !i.checked && ContactsFiles.plain(i.text).let { t -> words.all { it.take(4) in t } } }
        val item = hits.singleOrNull() ?: return ToolResult(if (hits.isEmpty()) "No open item like «$said» in «${noteTitle(note)}»; it has: ${note.items.filter { !it.checked }.joinToString(", ") { it.text }}."
            else "More than one item is like that: ${hits.joinToString(", ") { it.text }}. Say which.")
        store.setText(note.id, note.title, note.content, note.items.map { if (it.id == item.id) it.copy(checked = true) else it })
        changed("notes")
        return ToolResult("Ticked «${item.text}». Still open: ${note.items.count { !it.checked } - 1}.")
    }

    // ---- The Library: delete, rename ----
    private fun audioShownAt(n: Int?) = n?.let { audioShown.getOrNull(it - 1) }
    private fun audioDelete(n: Int?): ToolResult {
        val j = audioShownAt(n) ?: return ToolResult("Which? Run audio list first and give its number.")
        File(AudioJobs.dir(tetra), File(j.id).name).deleteRecursively()
        audioShown = audioShown - j
        return ToolResult("Deleted «${j.title.ifBlank { j.name }}» from the Library.")
    }
    private fun audioRename(n: Int?, title: String): ToolResult {
        val j = audioShownAt(n) ?: return ToolResult("Which? Run audio list first and give its number.")
        val name = title.trim().take(90).ifEmpty { return ToolResult("Say the new title.") }
        val f = File(AudioJobs.dir(tetra), "${File(j.id).name}/job.json")
        f.writeText(JSONObject(f.readText()).put("title", name).toString())
        return ToolResult("Renamed it «$name».")
    }

    // ---- Maps: the nearest of a kind, from where the phone is ----
    private fun mapsNearest(said: String): ToolResult {
        val q = said.trim().ifEmpty { return ToolResult("Say what: a kind (fuel, pharmacy…) or words.") }
        val (lat, lng) = here() ?: return ToolResult("Tetra does not know where the phone is (no location yet, or not allowed). Say so.")
        val kind = MapsSearch.PILLS.firstOrNull { it.key == q.lowercase() }?.key
        val found = MapsSearch.search(runCatching { MapsSearch.load(MapsFiles.root(tetra)) }.getOrDefault(emptyList()), if (kind != null) "" else q, lat, lng, kind, limit = 20)
            .sortedBy { metresBetween(lat, lng, it.lat, it.lng) }.take(5)
        nearShown = found
        if (found.isEmpty()) return ToolResult("Nothing like «$q» on the map near here.")
        return ToolResult(found.mapIndexed { i, p ->
            val m = metresBetween(lat, lng, p.lat, p.lng)
            "${i + 1}. ${p.name.ifBlank { "a ${p.kind.replace('_', ' ')} without a name" }}${p.more.takeIf { it.isNotBlank() }?.let { " (${it.take(60)})" }.orEmpty()} — ${if (m < 1000) "${(m / 10).toInt() * 10} m" else "%.1f km".format(java.util.Locale.ROOT, m / 1000)}" +
                p.hours.takeIf { it.isNotBlank() }?.let { ", open $it" }.orEmpty()
        }.joinToString("\n"))
    }

    // ---- Contacts: birthdays, a number or an address added ----
    private fun birthdays(days: Int): ToolResult {
        val today = now().toLocalDate()
        val soon = allContacts().mapNotNull { c -> c.info.birthday?.let { b ->
            val next = b.inYear(today.year).let { if (it.isBefore(today)) b.inYear(today.year + 1) else it }
            Triple(c.info, next, b.year?.let { next.year - it })
        } }.filter { !it.second.isAfter(today.plusDays(days.toLong())) }.sortedBy { it.second }
        if (soon.isEmpty()) return ToolResult("No birthdays in the next $days days.")
        return ToolResult(soon.joinToString("\n") { (i, d, age) -> "${d} ${d.dayOfWeek.toString().take(3).lowercase()}: ${i.name}${age?.let { " turns $it" }.orEmpty()}" +
            if (d == today) " (today)" else "" })
    }

    private fun contactAddWay(phone: Boolean, rest: List<String>): ToolResult {
        val value = rest.firstOrNull()?.trim() ?: return ToolResult("Say the ${if (phone) "number" else "address"} and then the name.")
        if (phone && value.count(Char::isDigit) < 5 || !phone && '@' !in value) return ToolResult("Say the ${if (phone) "number" else "address"} first, then the contact's name.")
        val name = rest.drop(1).joinToString(" ").trim().ifEmpty { return ToolResult("Say the contact's name.") }
        val words = ContactsFiles.plain(name).split(' ').filter { it.isNotBlank() }
        val hits = allContacts().filter { c -> words.all { w -> ContactsFiles.matches(c.info, w) } }
        val c = hits.singleOrNull() ?: return ToolResult(if (hits.isEmpty()) "No contact called $name." else "More than one contact is like $name: ${hits.take(5).joinToString(", ") { it.info.name }}. Say which.")
        val i = c.info
        if (phone && i.phones.any { PhoneLog.same(it.value, value) } || !phone && i.emails.any { it.value.equals(value, true) }) return ToolResult("${i.name} has it already.")
        ContactsFiles.save(ContactsFiles.root(tetra), c, if (phone) i.copy(phones = i.phones + VField("cell", value)) else i.copy(emails = i.emails + VField("home", value)))
        changed("contacts")
        return ToolResult("Added $value to ${i.name}.")
    }

    // ---- Data: the numbers behind the Data card (DataInsights), for AI Assist to explain ----
    private class DataView(val plan: DataPlan?, val status: DataStatus?, val months: List<DataMonth>, val phone: Boolean)
    private fun dataView(): DataView {
        val root = File(tetra, TetraSystem.onDisk(DataFiles.FOLDER))
        val all = DataFiles.all(root)
        val today = now().toLocalDate()
        val plan = DataFiles.plan(root)
        hotspotDevice?.let { return DataView(plan, null, all[it].orEmpty(), false) } // a tablet: its own use, over the phone's hotspot
        val months = all.values.maxByOrNull { m -> DataFiles.days(m).sumOf { it.mobile } }.orEmpty()
        return DataView(plan, plan?.let { DataRules.status(it, DataFiles.days(months), today) }, months, true)
    }
    private fun words(w: DataWords) = appContext?.let { w.text(it) } ?: w.args.joinToString(", ", "${w.id}: ")

    private fun dataTips(): ToolResult {
        val v = dataView()
        if (v.months.isEmpty()) return ToolResult("Tetra's Data has no counts yet on this device.")
        val today = now().toLocalDate()
        val m = DataInsights.metrics(v.plan, v.status, DataFiles.days(v.months), today, v.phone)
        val tips = DataInsights.tips(v.plan, v.status, m, v.months.flatMap { it.apps }.filter { v.status == null || it.day in v.status.cycle }, v.phone)
        fun b(n: Long) = DataRules.bytes(n)
        val numbers = listOfNotNull("on average ${b(m.average)} a day this cycle", "a usual workday ${b(m.workdays)}, a weekend day ${b(m.weekends)} (the last three weeks)",
            m.busiest?.let { "the busiest day ${it.first} with ${b(it.second)}" },
            if (v.phone) "the hotspot shared ${m.hotspotShare}% of the mobile data" else "${m.hotspotShare}% of the Wi-Fi came over the phone's hotspot",
            "Wi-Fi ${m.wifiShare}% of all", "forecast ${b(m.forecast)} by the cycle's end" + (v.status?.takeIf { v.plan?.limited == true }?.let { " of a ${b(it.size)} plan" } ?: ""),
            m.runsOut?.let { "the plan runs out on $it" })
        return ToolResult((if (tips.isEmpty()) "The card has no advice now." else "The card «For your plan» says:\n" + tips.joinToString("\n") { "- ${words(it.title)}: ${words(it.text)}" }) +
            "\nThe numbers: " + numbers.joinToString("; ") + ".")
    }

    private fun dataApps(days: Int?): ToolResult {
        val v = dataView()
        val from = days?.let { now().toLocalDate().minusDays(it.toLong() - 1) } ?: v.status?.cycle?.start ?: now().toLocalDate().withDayOfMonth(1)
        val apps = v.months.flatMap { it.apps }.filter { !it.day.isBefore(from) && it.app != "hotspot" }
        if (apps.isEmpty()) return ToolResult("No app's data is counted since $from.")
        fun b(n: Long) = DataRules.bytes(n)
        val wifi = apps.filter { it.net == "wifi" }.groupBy { it.app }.mapValues { (_, l) -> l.sumOf { it.total } }
        val rows = apps.filter { it.net == "mobile" }.groupBy { it.app }.map { (k, l) -> Triple(l.first().name, l.sumOf { it.fg }, l.sumOf { it.bg }) to k }
            .sortedByDescending { it.first.second + it.first.third }.take(10)
        val hotspot = v.months.flatMap { it.apps }.filter { !it.day.isBefore(from) && it.app == "hotspot" }.sumOf { it.total }
        return ToolResult("Mobile data since $from, by app:\n" + rows.mapIndexed { i, (r, k) ->
            "${i + 1}. ${r.first}: ${b(r.second + r.third)} (open ${b(r.second)}, in the background ${b(r.third)}); Wi-Fi ${b(wifi[k] ?: 0)}"
        }.joinToString("\n") + (if (hotspot > 0) "\nThe hotspot shared ${b(hotspot)}." else ""))
    }

    private fun dataDays(count: Int): ToolResult {
        val v = dataView()
        val days = DataFiles.days(v.months).filter { !it.day.isAfter(now().toLocalDate()) }.takeLast(count)
        if (days.isEmpty()) return ToolResult("Tetra's Data has no counts yet on this device.")
        fun b(n: Long) = DataRules.bytes(n)
        return ToolResult(days.reversed().joinToString("\n") { d ->
            "${d.day} ${d.day.dayOfWeek.toString().take(3).lowercase()}: mobile ${b(d.mobile)}" + (v.plan?.let { p -> DataRules.counted(p, d).takeIf { it != d.mobile }?.let { " (the plan counts ${b(it)})" } } ?: "") +
                ", Wi-Fi ${b(d.wifi)}" + (if (d.hotspot > 0) ", hotspot shared ${b(d.hotspot)}" else "") + (if (d.viaHotspot > 0) ", over the phone's hotspot ${b(d.viaHotspot)}" else "")
        })
    }

    // ---- The web: a page as text ----
    private fun webRead(url: String): ToolResult {
        if (!url.startsWith("http")) return ToolResult("Give the page's address (https://…).")
        val html = fetchPage(url)
        val text = PageText.text(html)
        if (text.length < 200) return ToolResult("The page gave almost no text (it may need a sign-in, or it builds itself with scripts). Offer to open it with open_url instead.")
        return ToolResult(PageText.title(html).let { if (it.isEmpty()) "" else "$it\n\n" } + text.take(12_000) + if (text.length > 12_000) "\n[… the page goes on]" else "")
    }

    // ---- Calls (the user, 11 Nov: "check the phone for any missed calls and ask if I want to call them back") ----
    private fun callLog() = calls() ?: error(if (hotspotDevice != null) "This tablet has no phone of its own: the calls are on the phone. Say so."
        else "Tetra may not read the call log yet: the user opens Tetra's Phone once and allows it. Say so.")
    private fun who(number: String) = if (number.isBlank()) "Hidden number" else nameOf(number).let { if (it == number) number else "$it ($number)" }
    private fun kind(c: LoggedCall) = when (c.type) {
        android.provider.CallLog.Calls.MISSED_TYPE -> "missed"; android.provider.CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        android.provider.CallLog.Calls.REJECTED_TYPE -> "declined"; android.provider.CallLog.Calls.BLOCKED_TYPE -> "blocked"
        android.provider.CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"; else -> "incoming" }
    private fun talked(c: LoggedCall) = c.seconds > 0 && (c.type == android.provider.CallLog.Calls.OUTGOING_TYPE || c.type == android.provider.CallLog.Calls.INCOMING_TYPE)
    /** A row's buttons: call back each one not called back yet (a tap is the user's own yes). */
    private fun backButtons(rows: List<LoggedCall>) = rows.filter { it.number.isNotBlank() }.distinctBy { it.number }.take(4)
        .map { c -> nameOf(c.number).let { AssistantChoice("☎ $it", call = PendingCall(it, c.number)) } }

    /** The missed calls of the last [days], one row per number: how many, the last, and whether a talk with them came since. */
    private fun callsMissed(days: Int): ToolResult {
        val all = callLog()
        val since = now().minusDays(days.toLong()).toInstant().toEpochMilli()
        val rows = all.filter { it.type == android.provider.CallLog.Calls.MISSED_TYPE && it.at >= since }.groupBy { c -> ContactsWays.key(c.number).takeIf { it.length >= 5 } ?: c.number } // +30… and 69… one row, as PhoneLog.same
            .map { (_, list) -> list.maxBy { it.at } to list.size }.sortedByDescending { it.first.at }
        callsShown = rows.map { it.first }
        if (rows.isEmpty()) return ToolResult("No missed calls in the last $days days.")
        val open = mutableListOf<LoggedCall>()
        return ToolResult(rows.mapIndexed { i, (c, times) ->
            val since2 = all.filter { PhoneLog.same(it.number, c.number) && it.at > c.at && talked(it) }.minByOrNull { it.at }
            if (since2 == null) open += c
            "${i + 1}. ${who(c.number)} — ${if (times > 1) "$times missed calls, the last" else "missed"} ${Instant.ofEpochMilli(c.at).atZone(zone).format(SHOWN)}" +
                (since2?.let { "; ${if (it.type == android.provider.CallLog.Calls.OUTGOING_TYPE) "called back" else "they called again"} ${Instant.ofEpochMilli(it.at).atZone(zone).format(SHOWN)}" } ?: "; not called back")
        }.joinToString("\n"), choices = backButtons(open))
    }

    private fun callsRecent(count: Int): ToolResult {
        val list = callLog().take(count)
        callsShown = list
        if (list.isEmpty()) return ToolResult("The call log is empty.")
        return ToolResult(list.mapIndexed { i, c ->
            "${i + 1}. ${who(c.number)} — ${kind(c)}${if (c.seconds > 0) ", ${(c.seconds + 59) / 60} min" else ""}, ${Instant.ofEpochMilli(c.at).atZone(zone).format(SHOWN)}"
        }.joinToString("\n"))
    }

    private fun callBack(n: Int?): ToolResult {
        val c = n?.let { callsShown.getOrNull(it - 1) } ?: return ToolResult(if (callsShown.isEmpty()) "No calls list yet: run calls missed or calls recent first."
            else "No number ${n ?: ""} in the last calls list; it has ${callsShown.size}.")
        if (c.number.isBlank()) return ToolResult("That call came from a hidden number: it cannot be called back. Say so.")
        val name = nameOf(c.number)
        return ToolResult("Tetra says \"$name\" and calls in 3 seconds unless the user stops it. Say nothing more.", call = PendingCall(name, c.number))
    }

    // ---- Messages (SMS): read for the model, never sent by it; the card's Send is the user's ----
    private fun smsThreads() = texts()?.let { SmsRules.threads(it) }
    /** The contact's name for a number, or the number (the names only while the assistant may look in Contacts). */
    private fun nameOf(number: String) = allContacts().firstOrNull { c -> c.info.phones.any { PhoneLog.same(it.value, number) } }?.info?.name ?: number
    private fun smsWhen(at: Long) = Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    private val SMS_OFF = "Tetra may not read the SMS yet: the user opens Messages in Tetra once and allows it. Say so."

    private fun smsList(a: JSONObject): ToolResult {
        val all = smsThreads() ?: return ToolResult(SMS_OFF)
        smsShown = all.filter { !a.optBoolean("unread") || it.unread > 0 }.take(a.optInt("count", 10).coerceIn(1, 30))
        if (smsShown.isEmpty()) return ToolResult(if (a.optBoolean("unread")) "No unread SMS." else "No SMS on this phone.")
        return ToolResult(smsShown.mapIndexed { n, t -> "${n + 1}. ${nameOf(t.address)}${if (t.unread > 0) " (${t.unread} unread)" else ""}, ${smsWhen(t.last.at)}: ${if (t.last.mine) "you: " else ""}${t.last.body.take(120)}" }.joinToString("\n"))
    }

    /** A conversation's last messages, oldest first, from the last list or by a contact's name or a number. */
    private fun smsRead(a: JSONObject): ToolResult {
        val all = smsThreads() ?: return ToolResult(SMS_OFF)
        val who = a.optString("contact").trim()
        val thread = if (a.optInt("choice", 0) > 0) smsShown.getOrNull(a.optInt("choice") - 1) ?: return ToolResult("No choice ${a.optInt("choice")}: the last list had ${smsShown.size}.")
            else if (who.isEmpty()) return ToolResult("Say whose messages, or a number from the last sms_list.")
            else (if (SmsRules.isNumber(who)) listOf(who) else named(who).flatMap { c -> c.phones.map { it.value } }).firstNotNullOfOrNull { SmsRules.threadOf(all, it) }
                ?: return ToolResult("No SMS with $who.")
        val name = nameOf(thread.address)
        val texts = texts().orEmpty().filter { it.thread == thread.id }.take(a.optInt("count", 15).coerceIn(1, 50)).reversed()
        return ToolResult("SMS with $name, oldest first:\n" + texts.joinToString("\n") { "${smsWhen(it.at)} ${if (it.mine) "you" else name}: ${it.body}" })
    }

    private fun smsWrite(a: JSONObject): ToolResult {
        val to = a.getString("to").trim()
        val text = a.getString("text").trim().ifEmpty { return ToolResult("Say what the message says.") }
        if (SmsRules.isNumber(to)) return ToolResult("The SMS to $to is on the screen; it waits for the user to check it and press Send.", cards = listOf(ResultCard.Sms(nameOf(to), to, text)))
        val found = named(to).filter { it.phones.isNotEmpty() }
        val c = when (found.size) {
            0 -> return ToolResult(if (!AiGate.state.keeps("contacts")) "The assistant may not look in Contacts. Ask the user for $to's number." else "No contact called $to with a phone number. Ask for the number.")
            1 -> found[0]
            else -> return ToolResult("Several contacts match $to: ${found.take(9).joinToString("; ") { it.name }}. Ask which, then call again with the full name.")
        }
        // the default number, else the mobile, else the first (the user picks another in Messages with Edit)
        val number = c.defaultPhone ?: c.phones.firstOrNull { it.label.contains("cell", true) || it.label.contains("mobile", true) || it.label.contains("κινητ", true) }?.value ?: c.phones.first().value
        return ToolResult("The SMS to ${c.name} is on the screen; it waits for the user to check it and press Send.", cards = listOf(ResultCard.Sms(c.name, number, text)))
    }

    // ---- Contacts ----
    /** Each match's name and the label of each of its numbers: the numbers themselves stay on the phone. */
    private fun contactsFind(a: JSONObject): ToolResult {
        val relation = a.optString("relation").trim()
        val found = if (relation.isNotEmpty()) {
            val card = myContact() ?: return ToolResult(NO_ME)
            val names = card.info.related.filter { sameLabel(it.label, relation) }.map { it.value }
            if (names.isEmpty()) return ToolResult("No $relation is saved on the user's card. Ask who it is, then relation_remember.")
            names.flatMap { n -> named(n) }.distinct().filter { it.phones.isNotEmpty() }
                .ifEmpty { return ToolResult("The user's $relation is ${names.joinToString(", ")}, but no contact by that name has a phone number.") }
        } else {
            val keys = a.optJSONArray("keywords")?.let { k -> (0 until k.length()).map { ContactsFiles.plain(k.optString(it)).trim() } }.orEmpty().filter { it.length >= 3 }
            val q = a.optString("name").ifBlank { if (keys.isEmpty()) return ToolResult("Say a name, a relation or some words.") else "" }
            val words = ContactsFiles.plain(q).split(' ').filter { it.isNotBlank() }
            val all = allContacts().map { it.info }
            val byName = if (words.isEmpty()) emptyList() else all.filter { i -> i.phones.isNotEmpty() && words.all { w -> ContactsFiles.matches(i, w) } }
            // "η κομμώτρια": the words the model gives (κομμωτ, κουρε, hair) in anything the card says, and the user's own relations' labels
            byName.ifEmpty {
                val mine = myContact()?.info?.related.orEmpty().filter { r -> keys.any { ContactsFiles.plain(r.label).contains(it) } }.flatMap { named(it.value) }
                (mine + all.filter { i -> keys.any { about(i).contains(it) } }).distinct().filter { it.phones.isNotEmpty() }
            }.sortedBy { ContactsFiles.sortKey(it, false) }.take(9)
        }
        contacts = found.flatMap { i ->
            // the number marked as the default is the one called, without asking (the user); all of them only when none is
            val phones = i.phones.filter { it.value == i.defaultPhone }.ifEmpty { i.phones }
            phones.map { p -> PendingCall(if (phones.size > 1) "${i.name} (${p.label.ifBlank { "phone" }})" else i.name, p.value) }
        }
        if (contacts.isEmpty()) return ToolResult("No contact like that with a phone number.")
        this.found = found
        return ToolResult(contacts.mapIndexed { n, c -> "${n + 1}. ${c.name}" }.joinToString("\n"), cards = found.map(ResultCard::contact))
    }

    // ---- Relations: on the user's own card, as Apple and Google write them (X-ABRELATEDNAMES with its X-ABLabel), so they
    // sync and show in Contacts, where they can be changed; no file of the assistant's own ----
    /** Every card, unless the user keeps the assistant out of Contacts: then no tool (mail, maps) looks anyone up either. */
    private fun allContacts() = if (!AiGate.state.keeps("contacts")) emptyList() else ContactsFiles.all(ContactsFiles.root(tetra))
    private fun myContact() = me()?.let { uid -> allContacts().firstOrNull { it.info.uid == uid } }
    private fun sameLabel(a: String, b: String) = ContactsFiles.plain(a) == ContactsFiles.plain(b)
    /** All a card says about who someone is, without accents: what keywords are looked for in. */
    private fun about(i: ContactInfo) = ContactsFiles.plain((listOf(i.name, i.nickname, i.org, i.department, i.title, i.note) + i.categories +
        i.related.flatMap { listOf(it.label, it.value) }).joinToString("\n"))

    /** The contacts a name means: the one with that very name, else those with all its words. */
    private fun named(name: String): List<ContactInfo> {
        val all = allContacts().map { it.info }
        all.filter { ContactsFiles.plain(it.name) == ContactsFiles.plain(name) }.takeIf { it.isNotEmpty() }?.let { return it }
        val words = ContactsFiles.plain(name).split(' ').filter { it.isNotBlank() }
        return all.filter { i -> words.isNotEmpty() && words.all { w -> ContactsFiles.matches(i, w) } }.sortedBy { ContactsFiles.sortKey(it, false) }
    }
    private fun one(name: String, use: (ContactInfo) -> ToolResult): ToolResult {
        val found = named(name)
        return when (found.size) {
            0 -> ToolResult("No contact called $name.")
            1 -> use(found.single())
            else -> ToolResult("Several contacts match $name: ${found.take(9).joinToString("; ") { it.name }}. Ask which, then call again with the full name.")
        }
    }

    private fun myCard(a: JSONObject): ToolResult {
        val name = a.getString("name").trim().ifEmpty { return ToolResult("Ask the user's own name.") }
        // no card of their own (the user, 30 Oct: "I don't have a contact"): one with just the name, for the relations
        if (named(name).isEmpty()) {
            val c = ContactsFiles.save(ContactsFiles.root(tetra), null, ContactInfo(formatted = name, given = name.substringBefore(' '), family = name.substringAfter(' ', "")))
            setMe(c.info.uid); changed("contacts")
            return ToolResult("Made the user's own card in Contacts, with just their name: $name.")
        }
        return one(name) { i -> setMe(i.uid); ToolResult("Saved: the user's own card is ${i.name}.") }
    }

    private fun relationRemember(a: JSONObject): ToolResult {
        val relation = a.getString("relation").trim().ifEmpty { return ToolResult("Say the relation.") }
        val label = VCard.RELATIONS.firstOrNull { sameLabel(it, relation) } ?: relation
        val card = myContact() ?: return ToolResult(NO_ME)
        return one(a.getString("name")) { person ->
            val related = card.info.related
            if (related.any { sameLabel(it.label, label) && sameLabel(it.value, person.name) }) return@one ToolResult("Already saved: ${person.name} is the user's $label.")
            // a spouse or a mother is one; brothers, sisters, children and friends may be several
            val replaced = if (label in ONLY_ONE || label !in VCard.RELATIONS) related.filter { sameLabel(it.label, label) } else emptyList()
            ContactsFiles.save(ContactsFiles.root(tetra), card, card.info.copy(related = related - replaced.toSet() + VField(label, person.name)))
            changed("contacts")
            ToolResult("Saved on the user's card: ${person.name} is their $label." +
                if (replaced.isEmpty()) "" else " It replaced ${replaced.joinToString(", ") { it.value }}; tell the user.")
        }
    }

    private fun scheduleLine(x: JSONObject): String {
        val days = x.optJSONArray("days")?.let { a -> (0 until a.length()).joinToString(", ") { listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")[a.optInt(it).coerceIn(0, 6)] } }.orEmpty()
        val how = when (val r = x.optString("repeat")) { "weekly" -> "weekly on $days"; "once" -> "once on ${x.optString("date")}"; else -> r }
        val next = AssistantSchedules.due(x)?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).format(SHOWN) }
        val until = x.optString("until").takeIf { !x.isNull("until") && it.isNotBlank() }?.let { " until $it" }.orEmpty()
        val allow = x.optJSONArray("allow")?.let { a -> (0 until a.length()).joinToString(", ") { a.optString(it) } }?.ifBlank { null } ?: "nothing"
        return "${x.optString("title")} — $how at ${x.optString("time")}$until" + when { x.optBoolean("paused") -> ", paused"; next != null -> ", next $next"; else -> ", done" } +
            if (x.optString("kind") == "brief") " (the daily brief)" else ": \"${x.optString("prompt")}\"; may do on its own: $allow"
    }

    /** A schedule just made or changed, read back from the file (the user, 14 Nov: "make it check, and ask for permissions or say all is OK"). */
    private fun checked(s: JSONObject, did: String): String {
        val now = AssistantSchedules.list(tetra).firstOrNull { it.optString("id") == s.optString("id") } ?: return "Failed: it is not in the list after saving. Tell the user it was not set."
        return "$did and checked, it is in the list: ${scheduleLine(now)}.\nTell the user in one or two sentences that it is set: when it runs first" +
            (if (now.has("until") && !now.isNull("until")) " and its last day" else "") + ". Then, if the task will want to add to the calendar, make notes, set timers or save files after reading the web or mail " +
            "and it may not do that on its own yet, ask whether it may (then schedule_change with allow); sending, calling and deleting always wait for them. Otherwise say all is OK."
    }

    private fun relationForget(a: JSONObject): ToolResult {
        val relation = a.getString("relation").trim()
        val card = myContact() ?: return ToolResult(NO_ME)
        val name = a.optString("name").trim()
        val gone = card.info.related.filter { sameLabel(it.label, relation) && (name.isEmpty() || ContactsFiles.plain(it.value).contains(ContactsFiles.plain(name))) }
        if (gone.isEmpty()) return ToolResult("No $relation was saved.")
        ContactsFiles.save(ContactsFiles.root(tetra), card, card.info.copy(related = card.info.related - gone.toSet()))
        changed("contacts")
        return ToolResult("Forgot: ${gone.joinToString(", ") { it.value }} as the user's $relation.")
    }

    companion object {
        private val SHOWN = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm")
        private val HM = DateTimeFormatter.ofPattern("HH:mm")
        private val DAY_FROM = LocalTime.of(8, 0)
        private val DAY_TO = LocalTime.of(21, 0)
        private const val SHOWN_PHOTOS = 6

        private fun fn(name: String, what: String, required: List<String>, vararg props: Pair<String, JSONObject>) = JSONObject().put("type", "function")
            .put("function", JSONObject().put("name", name).put("description", what).put("parameters", JSONObject().put("type", "object")
                .put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } }).put("required", JSONArray(required))))
        private fun p(type: String, what: String) = JSONObject().put("type", type).put("description", what)
        private val CHOICE = "choice" to p("integer", "The number of the item in the list last shown (1 = the first).")
        private val SCHEDULE = arrayOf("prompt" to p("string", "What to do then, as a request to AI Assist in the user's words (\"πες μου τι έχω την εβδομάδα\")."), "title" to p("string", "A short name for it."),
            "time" to p("string", "Local time, HH:MM."), "repeat" to JSONObject().put("type", "string").put("enum", JSONArray(AssistantSchedules.REPEATS)).put("description", "How often."),
            "days" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "integer")).put("description", "For weekly: the weekdays, 0 = Sunday, 1 = Monday … 6 = Saturday."),
            "date" to p("string", "YYYY-MM-DD: for once, the day; for monthly, a day of the month it runs on; for yearly, its day and month. Leave out for today."),
            "until" to p("string", "YYYY-MM-DD: the last day it runs (\"for the next 15 days\"); leave out for no end."),
            "allow" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "string").put("enum", JSONArray(listOf("calendar", "notes", "timers", "files"))))
                .put("description", "Only what the user said it may do on its own after reading the web or mail: add to the calendar, make notes, set timers, save files. Sending, calling and deleting always wait for the user."))

        private const val NO_ME = "Tetra does not know which contact is the user. Ask the user for their own name as saved in Contacts, then call my_card, then try again."
        private const val RELATION = "Spouse, Partner, Mother, Father, Parent, Brother, Sister, Child, Friend, Assistant or Manager in English; any other in the user's own word (κουμπάρος)."
        private val ONLY_ONE = setOf("Spouse", "Partner", "Mother", "Father", "Assistant", "Manager")

        /** A page opened in Tetra's Browser and nothing played ("open": true; the Browser's PLAY word). */
        fun browserAt(url: String) = "browser-play:" + JSONObject().put("url", url).put("open", true).put("at", System.currentTimeMillis())
        /** [u] as a web address: http(s) as it is, a bare "site.gr/…" with https; null for any other scheme. */
        fun webAddress(u: String): String? = when { Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(u) -> u
            !u.contains("://") && !u.contains(' ') && Regex("^[\\w-]+(\\.[\\w-]+)+(/.*)?$").matches(u) -> "https://$u"; else -> null }

        /** Each tool's app (AiGate.APPS). */
        val APP_OF = mapOf("calendar_add" to "calendar", "calendar_list" to "calendar", "calendar_free" to "calendar",
            "pills_list" to "pills", "pills_save" to "pills", "pills_taken" to "pills", "pills_later" to "pills",
            "timer_start" to "timer", "timer_list" to "timer", "timer_stop" to "timer", "alarm_add" to "timer", "alarm_list" to "timer", "alarm_delete" to "timer", "stopwatch" to "timer",
            "notes_search" to "notes", "note_new" to "notes", "note_open" to "notes", "note_pin" to "notes", "note_color" to "notes", "shopping_list" to "notes", "files_find" to "files", "file_open" to "files",
            "contacts_find" to "contacts", "my_card" to "contacts", "relation_remember" to "contacts", "relation_forget" to "contacts", "contact_call" to "phone",
            "navigate" to "maps", "place_reminder" to "maps", "reminders_list" to "maps", "photos_place" to "photos", "mail_new" to "mail", "mail_attention" to "mail", "mail_find" to "mail", "mail_open" to "mail", "mail_write" to "mail", "mail_stored_find" to "mail", "mail_export" to "mail",
            "sms_list" to "sms", "sms_read" to "sms", "sms_write" to "sms",
            "web_search" to "web", "make_image" to "web", "youtube" to "web", "media_play" to "browser", "spotify_mix" to "browser", "app_open" to "browser", "car_assist" to "phone", "media_like" to "browser", "radio_play" to "browser", "open_url" to "browser", "send_to_device" to "browser", "attachment_read" to "files", "audio_make" to "files")
        private val PERSONAL_APPS = setOf("mail", "calendar", "notes", "files", "photos", "contacts", "phone", "sms", "writer", "pills")
        /** What asks for the unlock on a locked phone (WakeUnlock); timers, maps, music, the web and questions run locked. */
        fun personal(name: String) = APP_OF[name] in PERSONAL_APPS || name == "tetra" || name == "brief_show" || name.startsWith("memory_") || name.startsWith("schedule_")
        /** The tools of the apps the assistant may use now: the others are not even offered. */
        fun allowed(): JSONArray = JSONArray((0 until SCHEMA.length()).map { SCHEMA.getJSONObject(it) }
            .filter { AiGate.state.keeps(APP_OF[it.getJSONObject("function").getString("name")]) && (beside || it.getJSONObject("function").getString("name") != "open_beside") })
        /** Split screen is there (Beside.can): the screen says so before each turn; open_beside is offered only then. */
        @Volatile var beside = false

        /** What the model may call, in Mistral's function-calling form. */
        val SCHEMA: JSONArray = JSONArray(listOf(
            fn("calendar_add", "Add an event to the user's calendar.", listOf("title", "start"),
                "title" to p("string", "The event's name, in the user's words."), "start" to p("string", "Local start, YYYY-MM-DDTHH:MM (a date alone when all_day)."),
                "end" to p("string", "Local end, same form; leave out for one hour."), "all_day" to p("boolean", "A whole-day event."), "place" to p("string", "Where, if said.")),
            fn("calendar_list", "What is in the calendar from one day to another.", listOf("from"),
                "from" to p("string", "First day, YYYY-MM-DD."), "to" to p("string", "Last day, YYYY-MM-DD; leave out for one day.")),
            fn("calendar_free", "Free times on a day between 08:00 and 21:00.", listOf("date"),
                "date" to p("string", "YYYY-MM-DD."), "minutes" to p("integer", "How long the free time must be; 60 if not said.")),
            fn("pills_list", "The user's medicines in Tetra's Pills: dose, times, pills in stock, how many days they last, and today's doses (taken, waiting, missed).", emptyList()),
            fn("pills_save", "Add a medicine to Pills, add a new box or pills to its stock (\"I got a new box of X 120 mg, 90 tablets\": name X, strength 120 mg, boxes 1, per_box 90), or change its dose or times. Creates the medicine when there is none by that name.", listOf("name"),
                "name" to p("string", "The medicine's name, without the strength."), "strength" to p("string", "As on the box: \"120 mg\", \"5 mg/ml\"; leave out if not said."),
                "boxes" to p("integer", "Unopened boxes bought or in hand."), "per_box" to p("integer", "Pills (tablets, capsules) in a new, full box, if said."),
                "pills" to p("number", "Pills left in an opened box, or loose, besides the boxes (\"I have 48, a new box has 50\": per_box 50, pills 48)."),
                "dose" to p("number", "Pills each time (0.5 for half); only if said."),
                "times" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "Every time of day it is taken, HH:MM; the whole new list. Only if said."),
                "days" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "string").put("enum", JSONArray(AlarmRecord.DAY_CODES))).put("description", "Only some weekdays; leave out for every day."),
                "until" to p("string", "The last day of a course, YYYY-MM-DD, if said.")),
            fn("pills_taken", "Mark the user's dose as taken (\"I took it\", \"took my pills\") or skipped. Without a name: every dose of the nearest time still waiting.", emptyList(),
                "name" to p("string", "One medicine, if named."), "strength" to p("string", "Its strength, if two share the name."), "skip" to p("boolean", "Skipped, not taken.")),
            fn("pills_later", "Put off the user's pill reminder now (\"I can't take them now, I'm driving: tell me in 20 minutes\", \"remind me when I get home\"): the doses waiting now ring again then.", emptyList(),
                "minutes" to p("integer", "In how many minutes."), "place" to p("string", "Instead of minutes: the place to be reminded at, as the user said it (home, σπίτι, work).")),
            fn("timer_start", "Start a countdown timer in Tetra.", emptyList(),
                "minutes" to p("integer", "Minutes."), "seconds" to p("integer", "Seconds, besides the minutes."), "label" to p("string", "What it is for, if said.")),
            fn("timer_list", "The running and paused timers and the time left.", emptyList()),
            fn("timer_stop", "Stop a timer from the last timer_list, or all of them.", emptyList(), "choice" to p("integer", "Its number in timer_list; leave out to stop all.")),
            fn("alarm_add", "Set an alarm clock in Tetra; it rings on all the user's devices.", listOf("time"),
                "time" to p("string", "Local time, HH:MM."),
                "days" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "string").put("enum", JSONArray(AlarmRecord.DAY_CODES)))
                    .put("description", "The weekdays it repeats on (all seven for every day, MO–FR for weekdays); leave out for once."),
                "date" to p("string", "For a single alarm, the day it rings, YYYY-MM-DD; leave out for the next time that clock time comes."),
                "label" to p("string", "What it is for, if said.")),
            fn("alarm_list", "The alarms, a numbered list with their times and days.", emptyList()),
            fn("alarm_delete", "Delete an alarm from the last alarm_list.", listOf("choice"), CHOICE),
            fn("stopwatch", "The stopwatch: start it, pause it, reset it, or read how long it has run.", listOf("action"),
                "action" to JSONObject().put("type", "string").put("enum", JSONArray(listOf("start", "pause", "reset", "read")))),
            fn("notes_search", "Find notes with some words anywhere in them; answers a numbered list of titles and the matching line.", listOf("query"), "query" to p("string", "Words to look for.")),
            fn("note_open", "Open a note from the last notes_search list in Tetra's Notes.", listOf("choice"), CHOICE),
            fn("open_beside", "Open a note, file or mail from the last list beside AI Assist, in split screen, when the user asks for it beside (\"δίπλα\"); AI Assist stays on its side.", listOf("kind", "choice"),
                "kind" to JSONObject().put("type", "string").put("enum", JSONArray(listOf("note", "file", "mail"))).put("description", "The last list it is from: notes_search, files_find or a mail list."), CHOICE),
            fn("note_pin", "Pin a note to the top of Notes, or unpin it: one from the last notes_search list, or without a choice the note just made.", emptyList(),
                CHOICE, "pinned" to p("boolean", "false to unpin; true if not said.")),
            fn("note_color", "Colour a note as Notes does: one from the last notes_search list, or without a choice the note just made.", listOf("color"),
                CHOICE, "color" to JSONObject().put("type", "string").put("enum", JSONArray(NoteColors.NAMES.keys.toList() + "none")).put("description", "The colour; none for the plain one.")),
            fn("note_new", "Make a new text note with what the user dictates.", listOf("text"),
                "text" to p("string", "The note's text, as said."), "title" to p("string", "A title, only if the user gave one.")),
            fn("shopping_list", "Make a new checklist note (a shopping list).", listOf("items"),
                "title" to p("string", "The note's title; Ψώνια if not said."), "items" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "One entry per item.")),
            fn("files_find", "Find files in Tetra by words of their name (any order, without the dots or the extension); answers a numbered list.", listOf("query"), "query" to p("string", "Words of the file's name, as the user said them.")),
            fn("file_open", "Open a file from the last files_find list.", listOf("choice"), CHOICE),
            fn("contacts_find", "Find the user's contacts by name, by their relation to the user (\"my sister\"), or by what they do (keywords); answers a numbered list of names (with the kind of number when one has several).", emptyList(),
                "name" to p("string", "The name as said."), "relation" to p("string", RELATION),
                "keywords" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
                    .put("description", "For a person known by a job or a role (η κομμώτρια, ο υδραυλικός): several related words and stems in Greek and English (κομμωτ, κουρε, κομμωτηριο, hair), looked for in names, companies, notes and labels.")),
            fn("relation_remember", "Save who someone is to the user (\"Maria K. is my sister\") on the user's own card in Contacts. No need to ask first.", listOf("relation", "name"),
                "relation" to p("string", RELATION), "name" to p("string", "The contact's name.")),
            fn("relation_forget", "Forget a saved relation.", listOf("relation"), "relation" to p("string", RELATION), "name" to p("string", "Only this person, when several have the relation.")),
            fn("my_card", "Say which contact is the user themself, once, when a relation tool asks for it.", listOf("name"), "name" to p("string", "The user's own name as in Contacts; a card with just that name is made if they have none.")),
            fn("contact_call", "Call a contact from the last contacts_find list, only when the user asked to call («πάρε», «κάλεσε», «τηλεφώνησε»), never after a request only to find or show one. Tetra says the name and dials by itself a moment later; do not ask «να καλέσω;» first.", listOf("choice"), CHOICE,
                "speaker" to p("boolean", "True only when the user asked for the loudspeaker («στο μεγάφωνο», «σε ανοιχτή ακρόαση», «on speaker»).")),
            fn("navigate", "Start driving directions in Tetra Maps (offline map), to a place or to a contact's saved address.", emptyList(),
                "place" to p("string", "A place, a town or an address (\"Ερμού 10, Αθήνα\")."), "contact" to p("string", "A contact's name, to go to their address.")),
            fn("place_reminder", "Show a checklist from Notes when the user arrives at a place: any shop of a kind (every supermarket), or one place.", emptyList(),
                "kind" to JSONObject().put("type", "string").put("enum", JSONArray(MapsSearch.PILLS.map { it.key })).put("description", "A kind of place, for any of them (supermarket)."),
                "place" to p("string", "One place instead: a saved place's name, a shop's name or an address."),
                "note" to p("string", "Words of the checklist's title (Ψώνια for shopping); leave out for any checklist with items left.")),
            fn("reminders_list", "The user's reminders by place (place_reminder's) on the screen, each with an × to delete it: «τι υπενθυμίσεις έχω;», «σβήσε μια υπενθύμιση». Deleting is the user's tap on the ×.", emptyList()),
            fn("photos_place", "Show the user's photos taken at a place.", listOf("place"), "place" to p("string", "A town or a place.")),
            fn("youtube", "Open the YouTube app on a search, only when the user names the YouTube app.", listOf("query"), "query" to p("string", "The song or video, with the artist if said.")),
            fn("media_play", "Play a song, a video or a playlist in Tetra's Browser: the site's search opens and its first result plays, in Player mode.", listOf("query"),
                "query" to p("string", "The song or video, with the artist if said; for a playlist the kind of music (disco, 80s rock, chill)."),
                "site" to p("string", "youtube (the default), youtube_music when the user says YouTube Music, spotify when the user says Spotify."),
                "playlist" to p("boolean", "true for a kind of music rather than one song: a genre, a mood, an era, an artist's songs in general, or when the user says playlist or music (\"play disco\", \"βάλε χαλαρή μουσική\"); false for one named song or video.")),
            fn("app_open", "Open an app or bring it to the front («άνοιξε το Spotify», «φέρε μπροστά τα μηνύματα»). In Tetra Car, Maps, Phone, Messages and the Browser open in a pane beside the map, another app beside Tetra Car.", listOf("name"),
                "name" to p("string", "The app's name as the user said it.")),
            fn("car_assist", "In the car (Tetra Car open, or the phone on the car's Bluetooth) new messages are read aloud with a spoken reply offered, and calls are answered by themselves after 2 seconds; switch either on or off («μη διαβάζεις τα μηνύματα», «μη σηκώνεις τις κλήσεις»).", emptyList(),
                "read_messages" to p("boolean", "Read new messages aloud in the car."), "answer_calls" to p("boolean", "Answer calls by themselves in the car.")),
            fn("spotify_mix", "Play one of the user's own Spotify Daily Mixes (Spotify makes up to six), when they ask for Spotify without a song («παίξε Spotify», «βάλε Spotify»).", emptyList()),
            fn("media_like", "Add the song playing now to the user's favourites on Spotify (Liked Songs) («βάλ' το στα αγαπημένα», «μου αρέσει αυτό»).", emptyList()),
            fn("radio_play", "Play the radio: a station the user saved (by its name), or with no name the station chosen for driving (Maps).", emptyList(),
                "name" to p("string", "The station's name if said (Kosmos, Μελωδία, ΕΡΤ); leave out for \"the radio\" or \"the driving radio\".")),
            fn("send_to_device", "Do something on another of the user's devices (the computer, the tablet): play a song, a video or a playlist, a radio station, open a link (a search result), show a text (an answer just given), or open the daily brief.", listOf("device", "what"),
                "device" to p("string", "The device as the user says it: computer, PC, tablet, or its name."),
                "what" to p("string", "play, radio, link, text or brief."),
                "query" to p("string", "With play: the song or video, or the kind of music."), "playlist" to p("boolean", "With play: true for a kind of music, as media_play."),
                "site" to p("string", "With play: youtube or youtube_music."), "station" to p("string", "With radio: the station's name; leave out for the driving one."),
                "url" to p("string", "With link: the address (a search result's)."), "title" to p("string", "With link or text: a few words saying what it is."),
                "text" to p("string", "With text: the words to show there (an answer, in full).")),
            fn("volume", "Change the phone's media volume.", listOf("change"),
                "change" to p("string", "up, down, mute, unmute, or set (with percent)."), "percent" to p("integer", "With set: 0–100.")),
            fn("mail_new", "The new mail in the Inbox: what came in the last hours, and anything still unread; a numbered list with senders, subjects and the start of each.", emptyList(),
                "hours" to p("integer", "How far back; 24 if not said."), "unread" to p("boolean", "Also older mail that is still unread; true if not said."), "account" to p("string", "Only this mail account, by its name or address as the user says it (Δουλειά, Gmail, the university's); left out: every account.")),
            fn("mail_attention", "The recent Inbox mail that is unread or marked ♥, with the start of each, to judge which needs an answer or an action.", emptyList(),
                "days" to p("integer", "How far back; 14 if not said."), "account" to p("string", "Only this mail account, by its name or address as the user says it (Δουλειά, Gmail, the university's); left out: every account.")),
            fn("mail_find", "Find mail from someone (a contact's name, a name or an address), with some words and/or in one account; a numbered list, newest first, each with its account.", emptyList(),
                "from" to p("string", "Who it is from, as said."), "query" to p("string", "Words in the subject or the text."), "account" to p("string", "Only this mail account, by its name or address as the user says it (Δουλειά, Gmail, the university's); left out: every account.")),
            fn("mail_open", "Open a mail from the last mail list in Tetra's Mail.", listOf("choice"), CHOICE),
            fn("mail_stored_find", "Search the mail kept on this device (Mail › Αποθήκη, Storage): every account, mail deleted from the server too, with their attachments' names; a numbered list.", emptyList(),
                "from" to p("string", "Who it is from, as said."), "query" to p("string", "Words in the subject, the recipients or an attachment's name."),
                "from_date" to p("string", "Earliest day, YYYY-MM-DD."), "to_date" to p("string", "Latest day, YYYY-MM-DD."), "account" to p("string", "Only this mail account, by its name or address as the user says it (Δουλειά, Gmail, the university's); left out: every account.")),
            fn("mail_export", "Only when the user asks: copy a mail from the last mail list, or one of its attachments, into Tetra/Documents.", listOf("choice"),
                "choice" to p("integer", "The number in the last list."), "attachment" to p("string", "The attachment's name, or part of it; empty for the whole mail (.eml).")),
            fn("mail_write", "Write an e-mail: Tetra's compose page opens with it filled in, and the user sends it. It is never sent by you.", listOf("to", "subject", "body"),
                "to" to p("string", "An e-mail address, or a contact's name as said."), "subject" to p("string", "A short subject."),
                "body" to p("string", "The whole text as a proper e-mail: a greeting, what the user wants said, a closing line; no signature (Tetra adds it).")),
            fn("sms_list", "The SMS conversations on this phone, newest first: a numbered list with who, how many unread, and the last message.", emptyList(),
                "unread" to p("boolean", "Only those with unread messages."), "count" to p("integer", "How many; 10 if not said.")),
            fn("sms_read", "Read the last SMS with someone, oldest first: by a contact's name or a number, or a conversation from the last sms_list.", emptyList(),
                "contact" to p("string", "A contact's name as said, or a phone number."), "choice" to p("integer", "Its number in the last sms_list."), "count" to p("integer", "How many messages; 15 if not said.")),
            fn("sms_write", "Write an SMS: a card shows it with its receiver and the user sends it with Send. It is never sent by you.", listOf("to", "text"),
                "to" to p("string", "A contact's name as said, or a phone number."), "text" to p("string", "The message, short, in the user's words and language; no greeting unless they said one.")),
            fn("memory_add", "Keep a lasting fact about the user that they asked you to remember («θυμήσου ότι…»): it is given to you in every later chat, on every device.", listOf("text"),
                "text" to p("string", "The fact, short, in the user's language and in the third person (\"Διδάσκει στο 3ο Δημοτικό Καβάλας\").")),
            fn("memory_list", "The facts kept about the user, a numbered list.", emptyList()),
            fn("memory_forget", "Forget a fact from the last memory_list.", listOf("choice"), CHOICE),
            fn("schedule_add", "Schedule something for AI Assist to do by itself at a time, once or again and again (\"κάθε Δευτέρα στις 8 πες μου τι έχω την εβδομάδα\"). The user's computer runs the prompt then and the answer comes to this phone as a notification.", listOf("prompt", "time", "repeat"), *SCHEDULE),
            fn("schedule_list", "The scheduled actions (the daily brief among them), each with its next run; a numbered list.", emptyList()),
            fn("schedule_change", "Change, pause or resume a scheduled action from the last schedule_list; give only what changes.", listOf("choice"), CHOICE, *SCHEDULE,
                "paused" to p("boolean", "true to pause, false to resume."),
                "sections" to JSONObject().put("type", "object").put("description", "Only for the daily brief: which sections it has (calendar, mail, weather, notes = open checklists), as true/false; only those that change.")
                    .put("properties", JSONObject().apply { listOf("calendar", "mail", "weather", "notes").forEach { put(it, JSONObject().put("type", "boolean")) } })),
            fn("schedule_delete", "Delete a scheduled action from the last schedule_list.", listOf("choice"), CHOICE),
            fn("open_url", "Open a web page in Tetra's own Browser: an address the user says, or a source of a web_search.", listOf("url"), "url" to p("string", "The address (https://…).")),
            fn("data_status", "This device's mobile data: used and left of the plan, days left in the cycle, today, the daily average, the forecast for the cycle's end and how much a day keeps it within the plan; on a tablet without a SIM, its use over the phone's hotspot this month.", emptyList()),
            fn("brief_show", "Show the latest daily brief (η αναφορά, η ενημέρωση της ημέρας) as a card the user taps to read it whole; answers its text.", emptyList()),
            fn("web_search", "Search the web for current facts, news, opening hours, prices.", listOf("query"), "query" to p("string", "What to search for.")),
            fn("make_image", "Draw a picture from a description.", listOf("prompt"), "prompt" to p("string", "What the picture shows, in detail.")),
            fn("attachment_read", "Read more of the file attached in this chat: one part by its number, or the passages about something.", emptyList(),
                "part" to p("integer", "The part's number (1 = the start)."), "query" to p("string", "What to look for in it, in its language.")),
            fn("tetra", AssistantCommands.description(), listOf("command"), "command" to p("string", "One command line, as listed (\"calls missed\", \"calls back 2\").")),
            fn("audio_make", "Make an audio file (.m4a) of the attached file (or of a file from the last files_find list): \"narration\" reads it whole with one voice; \"show\" is a radio show, a host and an expert talking it through with examples and recaps. It answers an estimate (minutes, cost, when ready): say it in one sentence and ask whether to start. It starts only on the user's yes, which Tetra hears; never say it has started or is done.", listOf("mode"),
                "mode" to JSONObject().put("type", "string").put("enum", JSONArray(listOf("narration", "show"))).put("description", "\"narration\": read it to me; \"show\": a radio show."),
                "minutes" to p("integer", "For a show: how many minutes, only if the user said a number."),
                "length" to JSONObject().put("type", "string").put("enum", JSONArray(AudioJobs.LENGTHS.keys.toList())).put("description", "For a show: short («σύντομο»), medium or long («μεγάλο»), if the user said."), "focus" to p("string", "For a show: what to dwell on, if said."),
                "style" to p("string", "For a show: the tone, if said (e.g. \"για εξετάσεις\", \"χαλαρό\")."), "per_section" to p("boolean", "Also one MP3 a section, if asked."),
                "choice" to p("integer", "A file from the last files_find list instead of the attached one.")),
        ))

        fun system(now: ZonedDateTime, facts: List<AssistantMemory.Fact> = emptyList()) = """
            You are Tetra Assistant, on the user's Android phone, inside the app Tetra (calendar, timers, alarms, stopwatch, notes, files, contacts, mail, SMS messages, maps, photos).
            Now: ${now.format(DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd HH:mm"))} (${now.zone}).
            Answer in the user's language (usually Greek), in one or two short plain sentences: answers are often read aloud. No markdown.
            Use the tools for anything about the user's own calendar, timers, notes, files or contacts; never invent what they hold.
            When a search finds several, say how many and ask which one (the screen shows the numbered list); "the first", "η δεύτερη" mean that list's numbers.
            «Πάρε / κάλεσε / τηλεφώνησε τον …»: contacts_find, then contact_call at once (one match, or the one the user picks); Tetra says the name and dials, so say nothing after it and never ask «να καλέσω;». «… στο μεγάφωνο / σε ανοιχτή ακρόαση»: the same, with speaker true. Several matches: ask which one in a few words («τη Μαρία Κ. ή τη Μαρία Π.;»).
            «Βρες / δείξε την επαφή …»: contacts_find only; the screen shows the contact. Never contact_call after a find.
            "My sister", "ο μπαμπάς μου", "η γυναίκα μου": contacts_find with that relation. When the user says who someone is to them, relation_remember it.
            Someone known by a job or role ("πάρε την κομμώτρια"): contacts_find by relation, and if none, by keywords.
            "Ψάξε στο internet", "google it", news, prices, opening hours, anything current: always web_search.
            "Κλείσε ραντεβού" without a time: calendar_free first, offer two or three times, then calendar_add the one chosen.
            "Πάμε εκεί", "βάλε προορισμό": navigate; "there" is the last place in this conversation (an event's place, a search result); for a contact's home, navigate with contact.
            "Χρονόμετρο" is both a timer and a stopwatch: with a length ("βάλε χρονόμετρο 10 λεπτά") timer_start; without ("ξεκίνα χρονόμετρο", "σταμάτα το χρονόμετρο", "πόση ώρα πάει") the stopwatch.
            "Ξυπνητήρι", "ξύπνα με": alarm_add ("κάθε μέρα" all seven days, "τις καθημερινές" MO–FR; "αύριο στις 6" a date). To remove one, alarm_list then alarm_delete.
            "Όταν πάω σούπερ μάρκετ δείξε μου τα ψώνια": place_reminder with kind supermarket and note Ψώνια; then say it rings on arrival.
            A song or a video («βάλε στο YouTube …», «παίξε …», «play … on YouTube»): media_play; a kind of music («play disco on YouTube», «βάλε ελληνικά 80s», «κάτι χαλαρό για διάβασμα»): media_play with playlist true; «βάλε ραδιόφωνο», «το ραδιόφωνο του αυτοκινήτου», «βάλε Kosmos»: radio_play; «δυνάμωσε», «χαμήλωσε», «σίγαση», «ένταση στο 30»: volume; on another device («βάλε disco στο tablet», «στείλε την απάντηση στον υπολογιστή», «άνοιξε την ενημέρωση στο PC», «βάλε ραδιόφωνο στο tablet»): send_to_device; «στο YouTube Music»: media_play with site youtube_music; «στο Spotify»: media_play with site spotify; «παίξε Spotify» with no song: spotify_mix; «βάλ' το στα αγαπημένα» while music plays: media_like; the YouTube app only when named: youtube. "Κάνε σημείωση": note_new.
            Mail: "νέα μηνύματα;", "ήρθε κάτι;": mail_new, then say how many and from whom. "Τι χρειάζεται απάντηση;", "σημαντικά mail": mail_attention, then name
            only those that need an answer or an action and in a few words why (a deadline, a question to the user, a payment). "Βρες το mail από τη Μαρία": mail_find.
            "Στείλε mail στον …": mail_write, written as a proper e-mail in the language the user speaks; after it, say it waits for their Send.
            SMS (the phone's text messages; mail is something else): «στείλε μήνυμα / SMS / γράψε στον …» without "mail": sms_write, then say it waits for their Send.
            «Τι μου έγραψε ο …», «διάβασέ μου τα μηνύματα του …»: sms_read. «Έχω SMS;», «νέα SMS», «ήρθε μήνυμα στο κινητό;»: sms_list with unread, then who wrote and in a few words what.
            An old or deleted mail, a file someone sent long ago: mail_stored_find. "Αντέγραψέ το / βγάλε το συνημμένο στα Έγγραφα": mail_export,
            then say where it went (Tetra/Documents/…). Copy only when asked.
            Work out dates yourself from today's date ("αύριο", "την Πέμπτη").
            «Θυμήσου ότι…», a lasting fact about the user or what they like: memory_add, then say it is kept. «Ξέχασε ότι…»: memory_list, then memory_forget.
            "Κάθε Δευτέρα στις 8 πες μου…", "κάθε μέρα", "μία φορά τον χρόνο": schedule_add, the prompt in the user's words; then say when it runs. To change, pause or delete one: schedule_list first.
            The daily brief («πρωινή αναφορά», «ενημέρωση της ημέρας») is built in: never schedule_add it; schedule_list, then schedule_change it (time, paused: false, sections).
            «Πόσα data έφαγα;», «πόσα μου μένουν;», «θα μου φτάσουν;»: data_status, then the figures that answer it and its verdict (on track, or how much a day to stay within the plan).
            «Η αναφορά», «τι είπε η αναφορά», «δείξε μου την ενημέρωση»: brief_show, then a few words on what it says. «Άνοιξε το …» a site or a source: open_url. «Καρφίτσωσε / χρωμάτισε τη σημείωση»: notes_search (or the note just made), then note_pin or note_color.
            A file attached to the user's message: answer from it; attachment_read the parts not given, never guess them. To go through it («με τη σειρά», «επόμενο», «τι λέει;»): attachment_read the next part and tell it yourself, in a few sentences, then ask; never send the user to an audio file, a radio show or another app for it. A radio show is made only from its own button: when audio_make is offered, call it at once, before saying anything; never make up minutes, cost or time yourself.
        """.trimIndent() + AssistantGuard.RULE + if (facts.isEmpty()) "" else "\nWhat the user asked you to remember about them (use it when it helps; never read it out unasked):\n" + facts.joinToString("\n") { "- ${it.text}" }
    }
}

/**
 * One conversation: the messages so far, forgotten [forgetMs] after the user's last words (the voice sheet: a minute, so
 * "την πρώτη" follows "βρες τη Μαρία"; the chat screen keeps it until New chat).
 */
internal class AssistantChat(private val api: Mistral, private val tools: AssistantTools, private val forgetMs: Long = Long.MAX_VALUE,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() }) {
    private val messages = mutableListOf<JSONObject>()
    private var lastAt = 0L
    private var findOnly = false
    val bill get() = api.bill

    /** The user's [text] answered, the tools run on the way; [onText] gets the answer's words as they come; [file] goes with it, this turn only. */
    /** Each tool as the model calls it (Home's widget: a magnifier while it searches). */
    @Volatile var onTool: ((String, JSONObject) -> Unit)? = null
    /** Tools run after outside text without a yes (a scheduled action's permission, [AssistantGuard.ALLOW]). */
    var allow: Set<String> = emptySet()
    /** What it did, held and could not, for a scheduled action's report: { kind: done|held|failed, name, what, why? }. */
    val log = mutableListOf<JSONObject>()

    /** [show]: sent by the attached file's «Make it a radio show» button, the only way to one. */
    fun ask(text: String, at: Long = System.currentTimeMillis(), file: Attach.Doc? = null, show: Boolean = false, onText: ((String) -> Unit)? = null): AssistantReply {
        if (at - lastAt > forgetMs) messages.clear()
        lastAt = at
        file?.let(tools::attach)
        val from = messages.size
        messages += JSONObject().put("role", "user").put("content", if (file != null) "$text\n\n${AssistantGuard.wrap("attachment", Attach.forModel(file))}" else text)
        // the radio show only from its button (the user, 15 Nov: "the podcast making should only work as a mode"): offered then and
        // made to (mistral-small sometimes makes up the estimate), never otherwise: in a talk about a file it sent the user to audio files
        val must = if (show && tools.docFile != null) "audio_make" else null
        // «βρες την επαφή»: shown, never called (the user: "it found the contact, and the only option was to call")
        findOnly = FIND.containsMatchIn(text) && !HandsFree.asked(text)
        try { return turn(onText, must, outside = file != null) } finally {
            // the file's text goes once: after the turn only its name stays, for the next request and the kept chat
            if (file != null) messages[from].put("content", "$text\n\n${Attach.kept(file)}")
            for (m in messages.drop(from)) if (m.optString("role") == "tool" && m.optString("name") == "attachment_read") m.put("content", "[read from ${tools.docFile?.name ?: "the file"}; read it again if needed]")
        }
    }

    /** [outside]: someone else's text is in this turn already (an attached file); a mail, web or SMS tool's answer makes it so. */
    private fun turn(onText: ((String) -> Unit)?, must: String?, outside: Boolean): AssistantReply {
        var outside = outside; var confirm: AssistantGuard.Confirm? = null
        val choices = mutableListOf<AssistantChoice>(); val images = mutableListOf<File>()
        var call: PendingCall? = null; var open: File? = null; var go: String? = null; var beside = false; var audio: AudioAsk? = null
        var cards = emptyList<ResultCard>()
        repeat(ROUNDS) { round ->
            // forced: only that tool, and a call is a must ("any")
            val offered = AssistantTools.allowed().let { all -> JSONArray((0 until all.length()).map { all.getJSONObject(it) }.filter { val n = it.getJSONObject("function").getString("name")
                !(findOnly && n == "contact_call") && (must != null || n != "audio_make") }) }
            val only = if (must != null && round == 0) JSONArray((0 until offered.length()).map { offered.getJSONObject(it) }.filter { it.getJSONObject("function").getString("name") == must }) else null
            val forced = only != null && only.length() > 0
            val m = api.chat(JSONArray(listOf(JSONObject().put("role", "system").put("content", AssistantTools.system(now(), tools.facts()))) + messages), if (forced) only!! else offered, onText, if (forced) "any" else "auto")
            val calls = m.optJSONArray("tool_calls")
            messages += JSONObject().put("role", "assistant").put("content", m.opt("content")?.takeIf { it != JSONObject.NULL } ?: "")
                .apply { if (calls != null && calls.length() > 0) put("tool_calls", calls) }
            if (calls == null || calls.length() == 0) return AssistantReply(text(m), choices, call, images, open, go, beside, audio, cards, confirm)
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i); val f = c.getJSONObject("function")
                val args = when (val a = f.opt("arguments")) { is JSONObject -> a; is String -> runCatching { JSONObject(a.ifBlank { "{}" }) }.getOrDefault(JSONObject()); else -> JSONObject() }
                val name = f.getString("name")
                onTool?.invoke(name, args)
                // after outside text, a change waits for the user's yes (AssistantGuard); shortcut: one held call per turn, the last, until the model ever asks several at once
                val acts = AssistantGuard.acts(name, args)
                val r = if (outside && acts && name !in allow) { confirm = AssistantGuard.Confirm(name, args, AssistantGuard.what(name, args, tools.picked(name, args))).also { log += entry("held", name, it.what) }; ToolResult(AssistantGuard.held(name)) }
                    else tools.run(name, args).also { if (acts) log += entry(if (AssistantGuard.failed(it.forModel)) "failed" else "done", name, AssistantGuard.what(name, args, tools.picked(name, args)), it.forModel.takeIf { f -> AssistantGuard.failed(f) }) }
                        .let { if (AssistantGuard.external(name, args)) { outside = true; it.copy(forModel = AssistantGuard.wrap(name, it.forModel)) } else it }
                if (r.choices.isNotEmpty()) { choices.clear(); choices += r.choices }
                r.call?.let { call = it }; r.image?.let { images += it }; images += r.photos; r.open?.let { open = it }; r.go?.let { go = it }; if (r.open != null || r.go != null) beside = r.beside
                r.audio?.let { audio = it }
                if (r.cards.isNotEmpty()) cards = cards.filter { c -> r.cards.none { it::class == c::class } } + r.cards // a later card of a kind stands for the earlier one
                WriterLog.i("AI Assist: ${f.getString("name")}${if (forced) " (forced)" else ""} → ${r.forModel.take(160).replace('\n', ' ')}")
                messages += JSONObject().put("role", "tool").put("name", f.getString("name")).put("tool_call_id", c.optString("id")).put("content", r.forModel)
            }
        }
        return AssistantReply("", choices, call, images, open, go, beside, audio, cards, confirm)
    }

    private fun entry(kind: String, name: String, what: String, why: String? = null) = JSONObject().put("kind", kind).put("name", name).put("what", what).apply { why?.let { put("why", it.take(200)) } }

    /** The user's yes to a [AssistantGuard.Confirm]: the tool run as it was asked, and the chat told. */
    fun approve(c: AssistantGuard.Confirm): AssistantReply {
        val r = tools.run(c.name, c.args)
        WriterLog.i("AI Assist: ${c.name} (approved) → ${r.forModel.take(160).replace('\n', ' ')}")
        messages += JSONObject().put("role", "user").put("content", "Yes, do it (${c.name}).")
        messages += JSONObject().put("role", "assistant").put("content", "Done: ${r.forModel}")
        lastAt = System.currentTimeMillis()
        return AssistantReply("", r.choices, r.call, listOfNotNull(r.image) + r.photos, r.open, r.go, r.beside, r.audio, r.cards)
    }

    /** A kept chat's file, read again only if it is asked about. */
    fun attach(f: File?) = tools.attach(f)
    fun clear() { messages.clear(); bill.reset(); tools.attach(null as File?); log.clear() }
    /** The conversation as it is, for the History (AssistantChats); [restore] takes one back to go on with it. */
    fun saved() = JSONArray(messages.map { JSONObject(it.toString()) })
    /** A line said to the user outside a turn (a pill reminder), so the next words are read with it in mind. */
    fun note(text: String, at: Long = System.currentTimeMillis()) { messages += noteMessage(text); lastAt = at }
    fun restore(a: JSONArray) { messages.clear(); for (i in 0 until a.length()) a.optJSONObject(i)?.let { messages += it }; lastAt = System.currentTimeMillis() }

    companion object {
        fun noteMessage(text: String): JSONObject = JSONObject().put("role", "assistant").put("content", text)
        const val ROUNDS = 5 // tool rounds in one turn at most: find, then act, with room to spare
        private val FIND = Regex("βρ[εέ]ς|δε[ιί]ξε|ψ[αά]ξε|\\bfind\\b|\\bshow\\b", RegexOption.IGNORE_CASE)

        /** A message's text: a string, or Mistral's chunks (text pieces among others). */
        fun text(m: JSONObject): String = words(m).trim()
        /** The same, spaces kept: a streamed piece's edges are the spaces between words. */
        fun words(m: JSONObject): String = when (val c = m.opt("content")) {
            is String -> c
            is JSONArray -> (0 until c.length()).mapNotNull { c.optJSONObject(it)?.takeIf { k -> k.optString("type") == "text" }?.optString("text") }.joinToString("")
            else -> ""
        }

        /** A spoken yes or no to a waiting call, heard by the phone itself: true, false, or null for anything else. */
        fun yesNo(said: String): Boolean? {
            val first = ContactsFiles.plain(said).split(Regex("[^\\p{L}]+")).firstOrNull { it.isNotEmpty() } ?: return null
            return when (first) { in YES -> true; in NO -> false; else -> null }
        }
        /**
         * A spoken end to a live talk («οκ τέλος», "ok bye"): nothing but a goodbye, give or take an «οκ» or a "thanks", so
         * "stop at the next gas station" goes on. [started]: something was said already, so «γεια σου» is a goodbye, not a hello.
         */
        fun goodbye(said: String, started: Boolean): Boolean {
            val w = words(said)
            if (w.isEmpty() || w.size > 5) return false
            val rest = w.filter { it !in FILLER }.joinToString(" ")
            return rest in BYE || (started && rest in HELLO_BYE)
        }
        private fun words(s: String) = ContactsFiles.plain(s).split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        private fun phrases(vararg p: String) = p.map { words(it).joinToString(" ") }.toSet()
        private val FILLER = phrases("οκ", "οκέι", "εντάξει", "ευχαριστώ", "ευχαριστούμε", "πολύ", "λοιπόν", "ok", "okay", "thanks", "thank", "you", "very", "much", "so", "well").flatMap { it.split(" ") }.toSet()
        private val BYE = phrases("τέλος", "τέλος συζήτησης", "τέλος η συζήτηση", "αντίο", "σταμάτα", "άκυρο", "τα λέμε", "bye", "bye bye", "goodbye",
            "end", "end conversation", "end the conversation", "stop", "that's all", "that's it")
        private val HELLO_BYE = phrases("γεια", "γεια σου", "γεια σας", "γεια χαρά")
        private val YES = setOf("ναι", "οκ", "εντάξει", "ντάξει", "κάλεσε", "πάρε", "ok", "okay", "yes").map(ContactsFiles::plain).toSet()
        private val NO = setOf("όχι", "άκυρο", "ακύρωσε", "σταμάτα", "μη", "μην", "no", "cancel", "stop").map(ContactsFiles::plain).toSet()
    }
}

/**
 * A call asked for by voice, placed without a tap (the user, 1 Nov 2026: "it finds the contact, says the name and calls. I
 * don't want a button there; I may be in the car"): Tetra says the name, then [place]s it [DELAY] ms later unless cancelled
 * («όχι», «άκυρο», the card's Άκυρο). [tick] dials once the time is up and nothing is being heard.
 */
internal class HandsFree(private val place: (PendingCall) -> Unit) {
    var waiting: PendingCall? = null; private set
    private var at = 0L
    fun start(p: PendingCall, now: Long) { waiting = p; at = now + DELAY }
    fun cancel() { waiting = null }
    fun left(now: Long) = Math.ceil((at - now).coerceAtLeast(0) / 1000.0).toInt()
    /** True when it dialled now. */
    fun tick(now: Long, busy: Boolean = false): Boolean {
        val p = waiting ?: return false
        if (now < at || busy) return false
        waiting = null; place(p); return true
    }
    companion object {
        const val DELAY = 3_000L
        private val CALL = Regex("π[αά]ρε|κ[αά]λεσε|τηλεφ[ωώ]ν|\\bcall\\b|\\bring\\b|\\bphone\\b", RegexOption.IGNORE_CASE)
        /** The user asked to call (not only to find). */
        fun asked(text: String) = CALL.containsMatchIn(text)
        private val LOUD = Regex("μεγ[αά]φων|αν[οό]ιχτ[ηή] ακρ[οό]αση|ανοιχτ[οό] ακουστικ|speaker", RegexOption.IGNORE_CASE)
        /** The user asked for the loudspeaker: heard by the phone too, the model may leave it out. */
        fun loud(text: String) = LOUD.containsMatchIn(text)
    }
}

/**
 * An answer coming a few words at a time, cut into sentences for the voice: each is read as soon as it ends and the next
 * made while it plays (the user: the voice was slow). A cut needs a stop and a space after it ("3.5" stays whole) and a
 * dozen letters before it, so "κ. Νίκος" or "Ναι." do not go alone.
 */
internal class Sentences {
    private val buf = StringBuilder()
    fun push(words: String): List<String> {
        buf.append(words)
        val out = mutableListOf<String>()
        while (true) {
            val end = END.findAll(buf).firstOrNull { buf.substring(0, it.range.first).trim().length >= 12 } ?: break
            out += buf.substring(0, end.range.last + 1).trim()
            buf.delete(0, end.range.last + 1)
        }
        return out
    }
    /** What is left once the answer is whole. */
    fun rest(): String = buf.toString().trim().also { buf.clear() }

    private companion object { val END = Regex("[.!?;…·:]+\\s+|\n+") }
}

/** Voice activity: 30 ms frames of 16 kHz speech in, one phrase out when the speaker pauses (the VOICE app's Vad, ported). */
internal class Vad(private val pauseFrames: Int = 30, private val maxFrames: Int = 20 * 1000 / 30, private val factor: Double = 2.5, private val least: Double = 280.0,
                   private val byVoice: Boolean = false) {
    var noise = 200.0; private set
    /** The loudest 30 ms so far, for the log. */
    var loudest = 0.0; private set
    private val calib = mutableListOf<Double>()
    private val pre = ArrayDeque<ShortArray>()
    private val seg = mutableListOf<ShortArray>()
    private var silence = 0
    private var speech = 0
    private var wasLoud = false
    private var voiced = 0.0 // the frames over the bar, summed: the voice's own level
    var speaking = false; private set

    /** True when a phrase has ended: [phrase] holds it. */
    fun push(frame: ShortArray): Boolean {
        val r = rms(frame)
        loudest = maxOf(loudest, r)
        if (calib.size < 13) { calib += r; noise = calib.average(); return false }
        val th = threshold()
        if (!speaking) {
            pre.addLast(frame); if (pre.size > 6) pre.removeFirst()
            if (r > th) { speaking = true; silence = 0; speech = 1; voiced = r; seg.clear(); seg += pre } else noise = 0.95 * noise + 0.05 * r
            return false
        }
        seg += frame
        // a lone loud frame in road noise does not hold the pause open: speech is two in a row
        if (r > th) { speech++; voiced += r }
        // [byVoice], the car: road noise keeps passing a bar just over it, so the phrase never ended (car.log, 17 Nov: 15 s every time, or
        // closed by hand); once it is speech, a pause is anything under halfway (geometric) between the road and the voice's own level
        val loud = r > th && (!byVoice || speech < 7 || r > Math.sqrt(noise * voiced / speech))
        if (loud) { if (wasLoud) silence = 0 else silence++; wasLoud = true } else { silence++; wasLoud = false }
        // a click or a bump in the road, then quiet: not a phrase, so back to waiting (it used to listen until tapped)
        if (silence >= pauseFrames && speech < 7) { speaking = false; seg.clear(); noise = 0.95 * noise + 0.05 * r; return false }
        return (silence >= pauseFrames || seg.size >= maxFrames) && speech >= 7
    }

    fun threshold() = maxOf(noise * factor + least / 2.3, least)

    /** What was said so far (also when the user taps stop mid-phrase); empty when nothing was. */
    fun phrase(): ShortArray = if (speech < 7) ShortArray(0) else ShortArray(seg.sumOf { it.size }).also { out -> var n = 0; seg.forEach { it.copyInto(out, n); n += it.size } }

    companion object {
        const val RATE = 16000
        const val FRAME = 480
        fun rms(f: ShortArray): Double = if (f.isEmpty()) 0.0 else Math.sqrt(f.sumOf { it.toDouble() * it } / f.size)

        /** 16-bit mono PCM as a WAV file. */
        fun wav(pcm: ShortArray): ByteArray = java.nio.ByteBuffer.allocate(44 + pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size * 2); put("WAVEfmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16); put("data".toByteArray()); putInt(pcm.size * 2)
            pcm.forEach { putShort(it) }
        }.array()
    }
}
