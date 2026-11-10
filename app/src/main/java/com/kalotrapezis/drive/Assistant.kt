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
    data class Event(val title: String, val start: ZonedDateTime, val end: ZonedDateTime, val allDay: Boolean, val calendar: String, val place: String) : ResultCard()
    data class Note(val id: String, val title: String, val lines: List<String>) : ResultCard()
    data class Timer(val label: String, val endsAt: Long) : ResultCard()
    /** An SMS written, waiting for Send: to [name] ([number] itself when it is no contact). */
    data class Sms(val name: String, val number: String, val text: String) : ResultCard()

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
        val TOKENS = mapOf(Mistral.CHAT to (0.1 to 0.3), Mistral.BUILT_IN to (0.4 to 2.0))
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
    private val stream: ((path: String, body: String, line: (String) -> Unit) -> Unit)? = null) {
    /** One chat turn; with [onText] (and a [stream]) its words come as they are made, so the voice can start on the first sentence. */
    fun chat(messages: JSONArray, tools: JSONArray, onText: ((String) -> Unit)? = null, choice: String = "auto"): JSONObject {
        val body = JSONObject().put("model", CHAT).put("messages", messages).put("tools", tools).put("tool_choice", choice).put("temperature", 0.2)
        if (onText == null || stream == null) return JSONObject(http("/chat/completions", body.toString()))
            .also { bill.tokens(CHAT, it.optJSONObject("usage")) }
            .getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val text = StringBuilder(); val calls = sortedMapOf<Int, JSONObject>()
        stream.invoke("/chat/completions", body.put("stream", true).toString()) { line ->
            val data = line.removePrefix("data:").trim().takeIf { line.startsWith("data:") && it.startsWith("{") } ?: return@invoke
            val chunk = JSONObject(data)
            chunk.optJSONObject("usage")?.let { bill.tokens(CHAT, it) }
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
    fun complete(messages: JSONArray, json: Boolean = false, model: String = CHAT): String {
        val body = JSONObject().put("model", model).put("messages", messages).put("temperature", 0.4).apply { if (json) put("response_format", JSONObject().put("type", "json_object")) }
        return JSONObject(http("/chat/completions", body.toString())).also { bill.tokens(model, it.optJSONObject("usage")) }
            .getJSONArray("choices").getJSONObject(0).getJSONObject("message").let(AssistantChat::words)
    }

    /** The same API, its costs recorded as [feature] (the desktop's Mistral.as). */
    fun tagged(feature: String, more: Map<String, Any> = emptyMap()) = Mistral(http, bill.part(feature, more), stream)

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
    private var factsShown = emptyList<AssistantMemory.Fact>()
    private var smsShown = emptyList<SmsThread>()

    /** The personal details (AssistantMemory): in every chat's instructions. */
    fun facts() = AssistantMemory.list(tetra)

    /** What a tool's choice points at in the last list, for a confirmation's card (AssistantGuard). */
    fun picked(name: String, args: JSONObject): String? = args.optInt("choice", 0).takeIf { it > 0 }?.let { n -> when (name) {
        "contact_call" -> contacts.getOrNull(n - 1)?.name
        "memory_forget" -> factsShown.getOrNull(n - 1)?.text
        else -> null
    } }

    fun run(name: String, args: JSONObject): ToolResult = runCatching {
        // an app the user keeps the assistant out of (Settings › Βοηθός AI): neither read nor acted in
        APP_OF[name]?.takeIf { !AiGate.state.keeps(it) }?.let { return@runCatching ToolResult("The user does not let the assistant use their $it (Settings › AI Assist). Say so; do not try another way.") }
        when (name) {
            // a page in Tetra's own Browser: opened, nothing played
            "open_url" -> args.optString("url").trim().let { u -> webAddress(u)?.let { ToolResult("Opened $it in Tetra's Browser.", go = browserAt(it)) } ?: ToolResult("Only a web address (http or https) opens there.") }
            "navigate" -> navigate(args)
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
            "volume" -> volume(args.optString("change", "up"), if (args.has("percent")) args.optInt("percent") else null).let { ToolResult("The volume is at $it%.") }
            "files_find" -> filesFind(args)
            "file_open" -> pick(files, args) { f -> ToolResult("Opened ${f.name}.", open = f, cards = listOf(ResultCard.Files(listOf(f), tetra))) }
            "contacts_find" -> contactsFind(args)
            "my_card" -> myCard(args)
            "relation_remember" -> relationRemember(args)
            "relation_forget" -> relationForget(args)
            "contact_call" -> pick(contacts, args) { c -> ToolResult("Tetra says \"${c.name}\" and calls in 3 seconds${if (args.optBoolean("speaker")) ", on the loudspeaker," else ""} unless the user stops it. Say nothing more.", call = c.copy(speaker = args.optBoolean("speaker")),
                cards = found.firstOrNull { i -> i.phones.any { it.value == c.number } }?.let { listOf(ResultCard.contact(it)) }.orEmpty()) }
            "sms_list" -> smsList(args)
            "sms_read" -> smsRead(args)
            "sms_write" -> smsWrite(args)
            "memory_add" -> AssistantMemory.add(tetra, args.getString("text"), device).let { changed("memory"); ToolResult("Kept: \"${it.text}\". Settings › AI Assist › Personal details shows it.") }
            "memory_list" -> facts().also { factsShown = it }.let { l -> ToolResult(if (l.isEmpty()) "Nothing is kept." else l.mapIndexed { i, f -> "${i + 1}. ${f.text}" }.joinToString("\n")) }
            "memory_forget" -> pick(factsShown, args) { f -> AssistantMemory.change(tetra, f.id, "", device); factsShown = factsShown - f; changed("memory"); ToolResult("Forgot \"${f.text}\".") }
            "web_search" -> search(args.getString("query")).let { (text, sources) ->
                ToolResult(text + if (sources.isEmpty()) "" else "\nSources: " + sources.take(3).joinToString("; ") { it.title }, cards = if (sources.isEmpty()) emptyList() else listOf(ResultCard.Web(sources, args.getString("query")))) }
            "make_image" -> image(args.getString("prompt")).let { ToolResult("The picture is on the screen, saved as ${it.name}.", image = it) }
            "attachment_read" -> ToolResult(attached()?.let { Attach.read(it, args.optInt("part", 0).takeIf { n -> n > 0 }, args.optString("query")) } ?: "No file is attached in this chat.")
            else -> phone(name, args) // the phone's apps: done there (CarWire)
        }
    }.getOrElse { ToolResult("Failed: ${it.message}") }

    /**
     * A tool of the phone's apps (calendar, timers, notes, mail, pills, schedules, another device): the car runs only the map and
     * the music, so the phone's Tetra does it (CarWire's /car/do "tool") and its answer is the model's.
     */
    private fun phone(name: String, args: JSONObject): ToolResult {
        val c = appContext ?: return ToolResult("No tool called $name.")
        if (CarWire.link(c) == null) return ToolResult("That is done on the phone, and this car is not paired with the phone yet (Settings › Tetra Car). Say so.")
        val r = CarWire.ask(c, JSONObject().put("tool", name).put("args", args))
        WriterLog.i("AI Assist: $name on the phone: ${if (r.has("text")) "done" else r.optString("error")}")
        return ToolResult(r.optString("text").ifEmpty { "The phone did not answer (${r.optString("error").ifEmpty { "no answer" }}). Say so." })
    }

    private fun <T> pick(list: List<T>, args: JSONObject, use: (T) -> ToolResult): ToolResult {
        val n = args.optInt("choice", 0)
        return list.getOrNull(n - 1)?.let(use) ?: ToolResult("No choice $n: the last list had ${list.size}.")
    }

    private fun time(s: String): ZonedDateTime = LocalDateTime.parse(s.trim().replace(' ', 'T').take(16)).atZone(zone)
    private fun day(s: String): LocalDate = LocalDate.parse(s.trim().take(10))























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
            "time" to p("string", "Local time, HH:MM."), "repeat" to JSONObject().put("type", "string").put("enum", JSONArray(listOf("once", "daily", "weekly", "monthly", "yearly"))).put("description", "How often."),
            "days" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "integer")).put("description", "For weekly: the weekdays, 0 = Sunday, 1 = Monday … 6 = Saturday."),
            "date" to p("string", "YYYY-MM-DD: for once, the day; for monthly, a day of the month it runs on; for yearly, its day and month. Leave out for today."))

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
            "navigate" to "maps", "place_reminder" to "maps", "photos_place" to "photos", "mail_new" to "mail", "mail_attention" to "mail", "mail_find" to "mail", "mail_open" to "mail", "mail_write" to "mail", "mail_stored_find" to "mail", "mail_export" to "mail",
            "sms_list" to "sms", "sms_read" to "sms", "sms_write" to "sms",
            "web_search" to "web", "make_image" to "web", "youtube" to "web", "media_play" to "browser", "spotify_mix" to "browser", "app_open" to "browser", "car_assist" to "phone", "media_like" to "browser", "radio_play" to "browser", "open_url" to "browser", "send_to_device" to "browser", "attachment_read" to "files", "audio_make" to "files")
        /** The tools of the apps the assistant may use now: the others are not even offered. */
        fun allowed(): JSONArray = JSONArray((0 until SCHEMA.length()).map { SCHEMA.getJSONObject(it) }
            .filter { AiGate.state.keeps(APP_OF[it.getJSONObject("function").getString("name")]) && (beside || it.getJSONObject("function").getString("name") != "open_beside") })
        /** Split screen is there (Beside.can): the screen says so before each turn; open_beside is offered only then. */
        @Volatile var beside = false

        /** What the model may call, in Mistral's function-calling form. */
        val SCHEMA: JSONArray = JSONArray(listOf(
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
            fn("web_search", "Search the web for current facts, news, opening hours, prices.", listOf("query"), "query" to p("string", "What to search for.")),
            fn("make_image", "Draw a picture from a description.", listOf("prompt"), "prompt" to p("string", "What the picture shows, in detail.")),
            fn("attachment_read", "Read more of the file attached in this chat: one part by its number, or the passages about something.", emptyList(),
                "part" to p("integer", "The part's number (1 = the start)."), "query" to p("string", "What to look for in it, in its language.")),
            // done on the phone (the user, 10 Nov: "the self running is the map and the music, nothing else"): relayed over the car link
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
                "days" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "string").put("enum", JSONArray(listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")))).put("description", "Only some weekdays; leave out for every day."),
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
                "days" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "string").put("enum", JSONArray(listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU"))))
                    .put("description", "The weekdays it repeats on (all seven for every day, MO–FR for weekdays); leave out for once."),
                "date" to p("string", "For a single alarm, the day it rings, YYYY-MM-DD; leave out for the next time that clock time comes."),
                "label" to p("string", "What it is for, if said.")),
            fn("alarm_list", "The alarms, a numbered list with their times and days.", emptyList()),
            fn("alarm_delete", "Delete an alarm from the last alarm_list.", listOf("choice"), CHOICE),
            fn("stopwatch", "The stopwatch: start it, pause it, reset it, or read how long it has run.", listOf("action"),
                "action" to JSONObject().put("type", "string").put("enum", JSONArray(listOf("start", "pause", "reset", "read")))),
            fn("notes_search", "Find notes with some words anywhere in them; answers a numbered list of titles and the matching line.", listOf("query"), "query" to p("string", "Words to look for.")),
            fn("note_pin", "Pin a note to the top of Notes, or unpin it: one from the last notes_search list, or without a choice the note just made.", emptyList(),
                CHOICE, "pinned" to p("boolean", "false to unpin; true if not said.")),
            fn("note_color", "Colour a note as Notes does: one from the last notes_search list, or without a choice the note just made.", listOf("color"),
                CHOICE, "color" to JSONObject().put("type", "string").put("enum", JSONArray(listOf("red", "orange", "yellow", "green", "teal", "blue", "dark blue", "purple", "pink", "brown", "gray") + "none")).put("description", "The colour; none for the plain one.")),
            fn("note_new", "Make a new text note with what the user dictates.", listOf("text"),
                "text" to p("string", "The note's text, as said."), "title" to p("string", "A title, only if the user gave one.")),
            fn("shopping_list", "Make a new checklist note (a shopping list).", listOf("items"),
                "title" to p("string", "The note's title; Ψώνια if not said."), "items" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "One entry per item.")),
            fn("place_reminder", "Show a checklist from Notes when the user arrives at a place: any shop of a kind (every supermarket), or one place.", emptyList(),
                "kind" to JSONObject().put("type", "string").put("enum", JSONArray(MapsSearch.PILLS.map { it.key })).put("description", "A kind of place, for any of them (supermarket)."),
                "place" to p("string", "One place instead: a saved place's name, a shop's name or an address."),
                "note" to p("string", "Words of the checklist's title (Ψώνια for shopping); leave out for any checklist with items left.")),
            fn("mail_new", "The new mail in the Inbox: what came in the last hours, and anything still unread; a numbered list with senders, subjects and the start of each.", emptyList(),
                "hours" to p("integer", "How far back; 24 if not said."), "unread" to p("boolean", "Also older mail that is still unread; true if not said.")),
            fn("mail_attention", "The recent Inbox mail that is unread or marked ♥, with the start of each, to judge which needs an answer or an action.", emptyList(),
                "days" to p("integer", "How far back; 14 if not said.")),
            fn("mail_find", "Find mail from someone (a contact's name, a name or an address) and/or with some words; a numbered list, newest first.", emptyList(),
                "from" to p("string", "Who it is from, as said."), "query" to p("string", "Words in the subject or the text.")),
            fn("mail_stored_find", "Search the mail kept on this device (Mail › Αποθήκη, Storage): every account, mail deleted from the server too, with their attachments' names; a numbered list.", emptyList(),
                "from" to p("string", "Who it is from, as said."), "query" to p("string", "Words in the subject, the recipients or an attachment's name."),
                "from_date" to p("string", "Earliest day, YYYY-MM-DD."), "to_date" to p("string", "Latest day, YYYY-MM-DD.")),
            fn("mail_write", "Write an e-mail: Tetra's compose page opens with it filled in, and the user sends it. It is never sent by you.", listOf("to", "subject", "body"),
                "to" to p("string", "An e-mail address, or a contact's name as said."), "subject" to p("string", "A short subject."),
                "body" to p("string", "The whole text as a proper e-mail: a greeting, what the user wants said, a closing line; no signature (Tetra adds it).")),
            fn("data_status", "This device's mobile data: used and left of the plan, days left in the cycle, today, the daily average, the forecast for the cycle's end and how much a day keeps it within the plan; on a tablet without a SIM, its use over the phone's hotspot this month.", emptyList())
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
            A file attached to the user's message: answer from it; attachment_read the parts not given, never guess them. «Κάνε το ραδιοφωνική εκπομπή», «διάβασέ το μου», an MP3 of it: call audio_make at once, before saying anything; never make up minutes, cost or time yourself.
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
    @Volatile var onTool: ((String) -> Unit)? = null

    fun ask(text: String, at: Long = System.currentTimeMillis(), file: Attach.Doc? = null, onText: ((String) -> Unit)? = null): AssistantReply {
        if (at - lastAt > forgetMs) messages.clear()
        lastAt = at
        file?.let(tools::attach)
        val from = messages.size
        messages += JSONObject().put("role", "user").put("content", if (file != null) "$text\n\n${AssistantGuard.wrap("attachment", Attach.forModel(file))}" else text)
        // an MP3 of the file asked for: mistral-small sometimes makes up the estimate instead of asking audio_make, so it is made to
        val must: String? = null // no audio_make in the car
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
            val offered = AssistantTools.allowed().let { all -> if (!findOnly) all else JSONArray((0 until all.length()).map { all.getJSONObject(it) }.filter { it.getJSONObject("function").getString("name") != "contact_call" }) }
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
                onTool?.invoke(name)
                // after outside text, a change waits for the user's yes (AssistantGuard); shortcut: one held call per turn, the last, until the model ever asks several at once
                val r = if (outside && name in AssistantGuard.ACTS) { confirm = AssistantGuard.Confirm(name, args, AssistantGuard.what(name, args, tools.picked(name, args))); ToolResult(AssistantGuard.held(name)) }
                    else tools.run(name, args).let { if (name in AssistantGuard.EXTERNAL) { outside = true; it.copy(forModel = AssistantGuard.wrap(name, it.forModel)) } else it }
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
    fun clear() { messages.clear(); bill.reset(); tools.attach(null as File?) }
    /** The conversation as it is, for the History (AssistantChats); [restore] takes one back to go on with it. */
    fun saved() = JSONArray(messages.map { JSONObject(it.toString()) })
    /** A line said to the user outside a turn (a pill reminder), so the next words are read with it in mind. */
    fun note(text: String, at: Long = System.currentTimeMillis()) { messages += noteMessage(text); lastAt = at }
    fun restore(a: JSONArray) { messages.clear(); for (i in 0 until a.length()) a.optJSONObject(i)?.let { messages += it }; lastAt = System.currentTimeMillis() }

    companion object {
        fun noteMessage(text: String): JSONObject = JSONObject().put("role", "assistant").put("content", text)
        const val ROUNDS = 5 // tool rounds in one turn at most: find, then act, with room to spare
        private val FIND = Regex("βρ[εέ]ς|δε[ιί]ξε|ψ[αά]ξε|\\bfind\\b|\\bshow\\b", RegexOption.IGNORE_CASE)
        private val MP3 = Regex("\\bmp3\\b|\\bm4a\\b|ήχο|εκπομπ|radio show|διάβασ[εέ] (?:το|μου)|read it to me", RegexOption.IGNORE_CASE)

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
internal class Vad(private val pauseFrames: Int = 30, private val maxFrames: Int = 20 * 1000 / 30) {
    private var noise = 200.0
    private val calib = mutableListOf<Double>()
    private val pre = ArrayDeque<ShortArray>()
    private val seg = mutableListOf<ShortArray>()
    private var silence = 0
    private var speech = 0
    var speaking = false; private set

    /** True when a phrase has ended: [phrase] holds it. */
    fun push(frame: ShortArray): Boolean {
        val r = rms(frame)
        if (calib.size < 13) { calib += r; noise = calib.average(); return false }
        val th = maxOf(noise * 2.5 + 120, 280.0)
        if (!speaking) {
            pre.addLast(frame); if (pre.size > 6) pre.removeFirst()
            if (r > th) { speaking = true; silence = 0; speech = 1; seg.clear(); seg += pre } else noise = 0.95 * noise + 0.05 * r
            return false
        }
        seg += frame
        if (r > th) { silence = 0; speech++ } else silence++
        return (silence >= pauseFrames || seg.size >= maxFrames) && speech >= 7
    }

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
