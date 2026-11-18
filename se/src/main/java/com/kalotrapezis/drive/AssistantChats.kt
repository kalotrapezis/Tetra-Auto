package com.kalotrapezis.drive

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI Assist's History (the user, 1 Nov 2026: "where is the history, the chats"): one file a chat. Synced since 14 Nov (the
 * user: "sync the chats as well why not?"), the computer's the same (main.js), so a chat goes on on another device:
 *
 *   Tetra/.System/Assistant/Shared/Chats/<id>.json, synced as "AI Assist/Chats"
 *   = { id, at, title, device, bubbles: [{ mine, text, error, files?: [{ name, path, pages }], job? }], messages: [the model's turns] }
 *
 * An attached file is kept by its name and path only, never its text; an MP3's card by its job's id (AudioJobs). [device]
 * says whose turns they are: another device's tools may not be here, so its tool turns are told as context ([foreign]).
 */
internal object AssistantChats {
    data class Saved(val id: String, val at: Long, val title: String, val bubbles: List<JSONObject>, val messages: JSONArray, val device: String = "") {
        /** The turns to go on with here. */
        val turns: JSONArray get() = if (device.isNotEmpty() && device != AssistantRunner.me()) foreign(messages) else messages
    }
    private const val KEEP = 100

    fun dir(): File = File(AssistantBriefs.dir().parentFile, "Chats")

    /** The chats kept before on this device only (files/assistant_chats) go to the synced place, once. */
    private fun moved(c: Context): File {
        val to = dir().apply { mkdirs() }
        val old = File(c.filesDir, "assistant_chats")
        if (old.isDirectory) runCatching {
            old.listFiles().orEmpty().forEach { f -> File(to, f.name).let { if (!it.exists()) f.copyTo(it) }; f.delete() }
            old.delete()
        }.onFailure { WriterLog.e("AI Assist: the chats could not move", it) }
        return to
    }

    fun save(c: Context, id: String, bubbles: List<JSONObject>, messages: JSONArray, title: String? = null) {
        val first = bubbles.firstOrNull { it.optBoolean("mine") } ?: return
        val dir = moved(c); val f = File(dir, "${File(id).name}.json")
        // a scheduled action's chat keeps its name
        val name = title ?: runCatching { JSONObject(f.readText()).optString("title") }.getOrNull()?.takeIf { it.isNotBlank() } ?: first.optString("text").replace(Regex("\\s+"), " ").take(80)
        val o = JSONObject().put("id", id).put("at", System.currentTimeMillis()).put("title", name).put("device", AssistantRunner.me()).put("messages", messages).put("bubbles", JSONArray(bubbles))
        val part = File(dir, ".${f.name}.part").apply { writeText(o.toString()) } // whole or not at all: the sync may read it any moment
        if (!part.renameTo(f)) { f.writeText(o.toString()); part.delete() }
        list(c).drop(KEEP).forEach { delete(c, it.id) } // the oldest go past a hundred
    }

    fun list(c: Context): List<Saved> = moved(c).listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f -> read(f) }
        .sortedByDescending { it.at }.distinctBy { it.id } // a sync's "(2)" copy: the newest only

    fun get(c: Context, id: String): Saved? = read(File(moved(c), "${File(id).name}.json"))
    fun has(id: String): Boolean = File(dir(), "${File(id).name}.json").isFile

    private fun read(f: File) = runCatching {
        val o = JSONObject(f.readText()); val b = o.getJSONArray("bubbles")
        Saved(o.getString("id"), o.getLong("at"), o.optString("title"), (0 until b.length()).map { b.getJSONObject(it) }, o.optJSONArray("messages") ?: JSONArray(), o.optString("device"))
    }.getOrNull()

    fun delete(c: Context, id: String) { File(moved(c), "${File(id).name}.json").delete() }

    /** Another device's turns as turns this one can go on with: its tool calls go, what they answered comes inside the next answer. */
    fun foreign(messages: JSONArray): JSONArray {
        val out = JSONArray(); val found = mutableListOf<String>()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            when (m.optString("role")) {
                "tool" -> found += "[${m.optString("name", "tool")}]\n${m.optString("content")}"
                "user" -> { found.clear(); out.put(JSONObject().put("role", "user").put("content", m.optString("content"))) }
                "assistant" -> {
                    val said = m.optString("content").takeIf { !m.isNull("content") }.orEmpty()
                    if ((m.optJSONArray("tool_calls")?.length() ?: 0) > 0 && said.isBlank()) continue // its results come in [found]
                    out.put(JSONObject().put("role", "assistant").put("content", if (found.isEmpty()) said else "(Found on the way:\n${found.joinToString("\n\n")})\n\n$said"))
                    found.clear()
                }
            }
        }
        return out
    }
}
