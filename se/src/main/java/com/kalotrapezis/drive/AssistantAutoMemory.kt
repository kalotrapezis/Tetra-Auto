package com.kalotrapezis.drive

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Auto memory: when a chat ends (New chat, History, the screen left), mistral-small reads only what the USER said —
 * never a tool's answer, a mail, a web page or an attached file — and proposes at most three lasting facts about them,
 * kept in memory.json with source "auto" (AssistantMemory); one already kept in nearly the same words is skipped.
 * Settings › AI Assist › Personal details switches it off. The computer's runner merges and tidies them later.
 */
internal object AssistantAutoMemory {
    const val MAX = 3
    private const val PREF = "autoMemory"
    /** User words already looked at in this run of the app: a chat kept open, or reopened, is not read twice. */
    private val seen = HashSet<String>()

    fun on(c: Context) = c.getSharedPreferences("assistant", Context.MODE_PRIVATE).getBoolean(PREF, true)
    fun set(c: Context, on: Boolean) = c.getSharedPreferences("assistant", Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply()

    /** The user's own words in a chat's messages: user lines only, with any wrapped outside text and approvals cut out. */
    fun said(messages: JSONArray): List<String> = (0 until messages.length()).mapNotNull { messages.optJSONObject(it) }
        .filter { it.optString("role") == "user" }
        .map { m -> AssistantChat.text(m).replace(UNTRUSTED, " ").replace(KEPT, " ").trim() }
        .filter { it.isNotEmpty() && !APPROVAL.matches(it) }

    fun prompt(said: List<String>, kept: List<String>): JSONArray = JSONArray()
        .put(JSONObject().put("role", "system").put("content",
            "From the user's own messages below, pick at most $MAX lasting facts about the user worth remembering for later chats: " +
            "who they are, their family, work, places, habits, preferences. Not passing requests, not today's plans, not anything about other people's mail or files. " +
            "Each fact one short sentence, in the user's language, about the user (\"Teaches at…\", \"Η κόρη του λέγεται…\"). Leave out what is already kept. " +
            "Usually there is nothing: then an empty list. Answer JSON only: {\"facts\": [\"…\"]}."))
        .put(JSONObject().put("role", "user").put("content",
            (if (kept.isEmpty()) "" else "Already kept:\n" + kept.joinToString("\n") { "- $it" } + "\n\n") +
            "The user's messages:\n" + said.joinToString("\n") { "- $it" }))

    /** The model's answer as facts: at most [MAX], tolerant of a broken answer (none). */
    fun parse(answer: String): List<String> = runCatching {
        val a = JSONObject(answer.substring(answer.indexOf('{'), answer.lastIndexOf('}') + 1)).optJSONArray("facts") ?: JSONArray()
        (0 until a.length()).mapNotNull { a.opt(it) as? String }.map { it.trim() }.filter { it.length in 3..300 }.take(MAX)
    }.getOrDefault(emptyList())

    /** Nearly the same words: one's words inside the other's, or most words shared. */
    fun near(a: String, b: String): Boolean {
        val x = words(a); val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return false
        val both = x.intersect(y).size
        return both == minOf(x.size, y.size) || both.toDouble() / x.union(y).size >= 0.6
    }
    private fun words(s: String) = ContactsFiles.plain(s).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()

    /** The chat's new user words read and any facts kept; the facts kept. Nothing when off, or the AI or the key is not there. */
    fun after(api: Mistral, tetra: File, messages: JSONArray, device: String): List<AssistantMemory.Fact> {
        val fresh = said(messages).filter { synchronized(seen) { seen.add(it) } }
        if (fresh.sumOf { it.length } < 20) return emptyList() // «ναι», «βάλε χρονόμετρο»: nothing to learn
        val kept = AssistantMemory.list(tetra)
        val facts = parse(api.complete(prompt(fresh, kept.map { it.text }), json = true))
        val out = mutableListOf<AssistantMemory.Fact>()
        for (f in facts) {
            if ((kept + out).any { near(it.text, f) }) continue
            runCatching { AssistantMemory.add(tetra, f, device, source = "auto") }.onSuccess { out += it }.onFailure { WriterLog.i("AI Assist: auto memory stopped (${it.message})"); break }
        }
        if (out.isNotEmpty()) WriterLog.i("AI Assist: auto memory kept ${out.size}")
        return out
    }

    /** From the screen: in the background, when switched on and the AI may run. */
    fun soon(c: Context, messages: JSONArray) {
        val app = c.applicationContext
        if (!on(app) || !AiGate.on || !AssistantKey.has(app) || messages.length() == 0) return
        Thread {
            runCatching {
                val api = AssistantNet.api(app).tagged("memory")
                if (after(api, driveRoot(), messages, TimerControl.device()).isNotEmpty()) ScopedSync.soon(app, "assistant", 0, orWhole = true)
            }.onFailure { WriterLog.w("AI Assist: auto memory (${it.message})") }
        }.start()
    }

    private val UNTRUSTED = Regex("<untrusted[^>]*>[\\s\\S]*?</untrusted>")
    private val KEPT = Regex("\\[Attached file:[^\\]]*\\]") // an attached file's note (Attach.kept)
    private val APPROVAL = Regex("Yes, do it \\([a-z_]+\\)\\.")
}
