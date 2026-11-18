package com.kalotrapezis.drive

import android.content.Context
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import org.json.JSONObject

/**
 * The AI's switches (the user, 31 Oct: "a general off setting for the AI, because we sell it as a no-internet, no big
 * company thing", and "a switch list of which apps the AI may use"). Off, nothing at all goes to an AI provider — checked
 * where requests leave (AssistantNet, the live voice), not only on the screen — and every AI surface is hidden. Per app:
 * an app switched off is one the assistant may neither read nor act in, and its AI buttons are hidden.
 *
 *   Tetra/.System/Assistant/Shared/ai.json = { enabled, apps: { mail: true, … }, setAt, setBy }, synced as "AI Assist/ai.json"
 *
 * A missing app is allowed. No file: off, unless this install already has an AI key. Whoever changes it writes the whole
 * file, the later setAt winning (sync keeps the newer).
 */
internal object AiGate {
    /** The apps the assistant can be kept out of, as the file names them (the desktop's ai.js the same; "web": search, pictures, YouTube). */
    val APPS = listOf("mail", "calendar", "notes", "files", "photos", "timer", "contacts", "phone", "sms", "maps", "browser", "pills", "writer", "web")

    data class State(val enabled: Boolean, val apps: Map<String, Boolean> = emptyMap()) {
        fun allows(app: String?) = enabled && keeps(app)
        /** The app's own switch alone (the master one is enforced where requests leave). */
        fun keeps(app: String?) = app == null || apps[app] != false
    }

    /** What the switches say now; the screens follow it. */
    var state by mutableStateOf(State(false)); private set
    val on get() = state.enabled
    fun allows(app: String?) = state.allows(app)

    fun file(tetra: File) = File(TetraSystem.dir(tetra, "Assistant", "Shared"), "ai.json")
    private fun tetra() = TetraFolder.root(Environment.getExternalStorageDirectory())

    /** The file's switches; [hasKey] decides when there is no file (an install already using the AI keeps it on). */
    fun parse(text: String?, hasKey: Boolean): State {
        val o = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return State(hasKey)
        val apps = o.optJSONObject("apps")?.let { a -> a.keys().asSequence().associateWith { a.optBoolean(it, true) } }.orEmpty()
        return State(o.optBoolean("enabled", hasKey), apps)
    }

    /** The whole file: every app this device knows, and any other the file named (the PC may know more), kept. */
    fun json(s: State, now: Long, by: String): JSONObject = JSONObject().put("enabled", s.enabled)
        .put("apps", JSONObject().apply { (APPS + s.apps.keys).distinct().forEach { put(it, s.apps[it] != false) } }).put("setAt", now).put("setBy", by)

    /** Read again from the file (it may have come by sync). */
    fun load(c: Context, root: File = tetra()): State = parse(runCatching { file(root).readText() }.getOrNull(), AssistantKey.has(c)).also { state = it }

    fun set(next: State, root: File = tetra()) {
        state = next
        val f = file(root).apply { parentFile?.mkdirs() }
        val part = File(f.parentFile, ".ai.json.part").apply { writeText(json(next, System.currentTimeMillis(), MailEngine.device()).toString(2)) }
        check(part.renameTo(f))
        WriterLog.i("AI: ${if (next.enabled) "on" else "off"}, apps off: ${next.apps.filterValues { !it }.keys}")
    }

    /** Where a request to an AI provider is about to leave: refused while the AI is off. */
    fun check() { if (!state.enabled) throw DriveProblem("AI Assist is off.", R.string.ai_is_off) }
}
