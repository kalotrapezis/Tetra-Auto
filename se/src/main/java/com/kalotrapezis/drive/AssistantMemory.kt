package com.kalotrapezis.drive

import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * «Προσωπικά στοιχεία» (1 Nov 2026): a few facts the user told the assistant to keep («θυμήσου ότι…»), put into every
 * chat's instructions. One small file every device shares, like ai.json (the desktop's memory.js the same):
 *
 *   Tetra/.System/Assistant/Shared/memory.json = { facts: [{ id, text, at, source? }], setAt, setBy } — source "auto": kept by
 *   AssistantAutoMemory, not said with «θυμήσου»; any other source a file has is kept as it is, synced as "AI Assist/memory.json"
 *
 * shortcut: whoever changes it writes the whole file, the later one winning; merge by id if two devices ever both add before syncing.
 */
internal object AssistantMemory {
    data class Fact(val id: String, val text: String, val at: Long, val source: String = "")
    const val MAX = 20 // the same as memory.js: they go into every request, and the model's window is small
    private const val LONG = 300

    fun file(tetra: File) = File(TetraSystem.dir(tetra, "Assistant", "Shared"), "memory.json")

    fun list(tetra: File): List<Fact> = runCatching {
        val a = JSONObject(file(tetra).readText()).optJSONArray("facts") ?: JSONArray()
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Fact(it.optString("id"), it.optString("text").trim(), it.optLong("at"), it.optString("source")) }.filter { it.text.isNotEmpty() }
    }.getOrDefault(emptyList())

    private fun write(tetra: File, facts: List<Fact>, by: String): List<Fact> {
        val f = file(tetra).apply { parentFile?.mkdirs() }
        val json = JSONObject().put("facts", JSONArray(facts.map { JSONObject().put("id", it.id).put("text", it.text).put("at", it.at).apply { if (it.source.isNotEmpty()) put("source", it.source) } }))
            .put("setAt", System.currentTimeMillis()).put("setBy", by)
        val part = File(f.parentFile, ".memory.json.part").apply { writeText(json.toString(2)) }
        check(part.renameTo(f))
        return facts
    }

    private fun clean(text: String) = text.replace(Regex("\\s+"), " ").trim().take(LONG)

    /** A new fact, or the one already saved with the same words. */
    fun add(tetra: File, text: String, by: String, source: String = ""): Fact {
        val t = clean(text); val facts = list(tetra)
        require(t.isNotEmpty()) { "Say what to remember." }
        facts.firstOrNull { it.text.equals(t, ignoreCase = true) }?.let { return it }
        require(facts.size < MAX) { "Already $MAX facts kept: forget one first." }
        return Fact(UUID.randomUUID().toString(), t, System.currentTimeMillis(), source).also { write(tetra, facts + it, by) }
    }

    /** Its words changed; empty words forget it. */
    fun change(tetra: File, id: String, text: String, by: String): List<Fact> {
        val t = clean(text)
        return write(tetra, list(tetra).mapNotNull { if (it.id != id) it else if (t.isEmpty()) null else it.copy(text = t) }, by)
    }
}
