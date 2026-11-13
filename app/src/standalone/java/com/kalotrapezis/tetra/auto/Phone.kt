package com.kalotrapezis.tetra.auto

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal data class Message(val from: String, val name: String, val body: String, val at: Long)

/** A place to drive to, from the phone (AI Assist's «πήγαινέ με…», a place shared to the car) or picked here. */
internal data class Destination(val lat: Double, val lng: Double, val name: String)

/**
 * Tetra Auto SE's side of the phone (the user, 13 Nov: "the only thing we will have to transfer over is signals and commands"):
 * what the phone tells (EVENTs: a message, a call, its GPS, where to go, what to play, something to say) and what this car asks
 * of it (ASKs: send a message, answer or place a call, the recent calls). Calls' own voice stays with the phone and the unit's
 * Bluetooth hands-free: Android lets no app carry it.
 */
internal object PhoneLink {
    val messages = mutableStateListOf<Message>()
    /** The message just come, shown over the map until read. */
    val arrived = mutableStateOf<Message?>(null)
    /** The phone's call now: {state, name, number, incoming}; null with none. */
    val call = mutableStateOf<JSONObject?>(null)
    val going = mutableStateOf<Destination?>(null)
    /** Read messages aloud as they come (Settings). */
    var readAloud = true

    private val main = Handler(Looper.getMainLooper())
    private val ids = AtomicInteger(1)
    private val waiting = ConcurrentHashMap<Int, (JSONObject) -> Unit>()
    @Volatile var link: Link? = null

    /** A frame from the phone (the link's thread). */
    fun signal(type: Byte, body: ByteArray) {
        val o = runCatching { JSONObject(String(body)) }.getOrNull() ?: return
        if (type == Wire.ANSWER) { waiting.remove(o.optInt("id"))?.let { done -> main.post { done(o) } }; return }
        main.post { event(o) }
    }

    /** An EVENT, on the main thread. */
    fun event(o: JSONObject) {
        android.util.Log.i(TAG, "From the phone: ${o.optString("type")}")
        when (o.optString("type")) {
            "sms" -> {
                val m = Message(o.optString("from"), o.optString("name").ifBlank { o.optString("from") }, o.optString("body"), System.currentTimeMillis())
                messages.add(0, m); if (messages.size > 50) messages.removeAt(messages.lastIndex)
                arrived.value = m
                if (readAloud) Speech.say("${m.name}: ${m.body}")
            }
            "call" -> {
                call.value = o
                if (o.optString("state") == "ended") main.postDelayed({ if (call.value === o) call.value = null }, 1500)
            }
            "fix" -> runCatching { Fixes.fromPhone(o) }
            "go" -> runCatching { going.value = Destination(o.getDouble("lat"), o.getDouble("lng"), o.optString("name").ifBlank { "?" }) }
            "play" -> o.optString("url").takeIf { it.startsWith("http") }?.let { Radio.play(Station(o.optString("name").ifBlank { it }, it)) }
            "say" -> o.optString("text").takeIf { it.isNotBlank() }?.let(Speech::say)
        }
    }

    /** Asks the phone; [done] gets its ANSWER on the main thread, or {error} when there is no phone or no answer in 15 s. */
    fun ask(what: JSONObject, done: (JSONObject) -> Unit = {}) {
        val id = ids.getAndIncrement()
        val l = link
        if (l == null || !l.connected) { done(JSONObject().put("error", "no phone")); return }
        waiting[id] = done
        l.send(Wire.ASK, what.put("id", id).toString().toByteArray())
        main.postDelayed({ waiting.remove(id)?.invoke(JSONObject().put("error", "no answer")) }, 15_000)
    }

    fun sms(to: String, text: String, done: (JSONObject) -> Unit = {}) = ask(JSONObject().put("sms", JSONObject().put("to", to).put("text", text)), done)
    fun callDo(what: String) = ask(JSONObject().put("call", what))
    fun place(number: String) = ask(JSONObject().put("place", number))
    /** The phone's recent calls: [{name, number, type: in|out|missed, at}]. */
    fun recentCalls(done: (List<JSONObject>) -> Unit) = ask(JSONObject().put("recent", "calls")) { a -> done(a.optJSONArray("calls").toList()) }

    private fun JSONArray?.toList() = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
}
