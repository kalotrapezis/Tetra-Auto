package com.kalotrapezis.drive

import org.json.JSONObject
import java.io.File

/**
 * What the computer's mail AI leaves beside the index (the desktop writes them; this side only reads, and a missing or
 * broken file is today's behaviour):
 *
 *   Mail/Shared/ai/urgency.jsonl  one line per mail: {"id": <index id>, "score": 0–3, "why": "…", "at": ms}; a later line of an id replaces it
 *   Mail/Shared/ai/style.json     {"<address>": {"language", "greeting", "closing", "tone", "phrases": […], "updated": ms}}: how the user writes from it
 */
internal object MailAi {
    data class Urgency(val score: Int, val why: String, val at: Long)
    data class Style(val language: String, val greeting: String, val closing: String, val tone: String, val phrases: List<String>)

    fun dir(root: File) = File(root, "ai")

    /** Every mail's latest score; bad lines are skipped. shortcut: the whole file read per index change; an offset cache if it grows past ~10 000 lines. */
    fun urgency(text: String?): Map<String, Urgency> {
        val out = HashMap<String, Urgency>()
        text?.lineSequence()?.forEach { line ->
            val o = line.trim().takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@forEach
            val id = o.opt("id")?.toString()?.takeIf { it.isNotEmpty() && it != "null" } ?: return@forEach
            out[id] = Urgency(o.optInt("score", 0).coerceIn(0, 3), o.optString("why").trim(), o.optLong("at"))
        }
        return out
    }
    fun urgency(root: File): Map<String, Urgency> = urgency(runCatching { File(dir(root), "urgency.jsonl").readText() }.getOrNull())

    /** The styles by address (lower case). */
    fun styles(text: String?): Map<String, Style> {
        val o = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
        return o.keys().asSequence().mapNotNull { k -> o.optJSONObject(k)?.let { s ->
            val p = s.optJSONArray("phrases")
            k.trim().lowercase() to Style(s.optString("language").trim(), s.optString("greeting").trim(), s.optString("closing").trim(), s.optString("tone").trim(),
                if (p == null) emptyList() else (0 until p.length()).mapNotNull { p.opt(it) as? String }.map { it.trim() }.filter { it.isNotEmpty() }.take(10))
        } }.toMap()
    }
    fun style(root: File, address: String): Style? = styles(runCatching { File(dir(root), "style.json").readText() }.getOrNull())[address.trim().lowercase()]

    /** The style as words for the draft's instructions; empty when nothing is known. */
    fun prompt(s: Style?): String {
        s ?: return ""
        val said = listOfNotNull(
            s.language.takeIf { it.isNotEmpty() }?.let { "usually writes in $it" },
            s.greeting.takeIf { it.isNotEmpty() }?.let { "greets with \"$it\"" },
            s.closing.takeIf { it.isNotEmpty() }?.let { "closes with \"$it\"" },
            s.tone.takeIf { it.isNotEmpty() }?.let { "a $it tone" },
            s.phrases.takeIf { it.isNotEmpty() }?.let { "often says: " + it.joinToString("; ") { p -> "\"$p\"" } })
        return if (said.isEmpty()) "" else " Sound like the user's own mails from this address: " + said.joinToString(", ") + " (the conversation's language still wins)."
    }
}
