package com.kalotrapezis.drive

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/**
 * Review › ✦ Suggestions: AI Assist (mistral-small) reads the selection or the whole document and answers edits as
 * find/replace pairs, each with a short reason; the page marks each one and nothing changes until the user accepts it.
 * Sent only on the user's press, and only while AI Assist and its Words switch are on (AiGate "writer").
 */
internal object WriterSuggest {
    /** [find] stands at [at] in the editor's text; [replace] would take its place. */
    data class Suggestion(val at: Int, val find: String, val replace: String, val reason: String) {
        val end get() = at + find.length
    }

    /** A piece sent at a time, and the most pieces one press sends (the cost's cap: ~12 × 4000 letters). */
    const val CHUNK = 4000
    const val MAX_CHUNKS = 12

    /** [text] cut at paragraph ends into pieces of at most [size] letters (a longer paragraph at a space): (start, piece). */
    fun chunks(text: String, size: Int = CHUNK): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var a = 0
        while (a < text.length) {
            var z = minOf(text.length, a + size)
            if (z < text.length) {
                val nl = text.lastIndexOf('\n', z - 1)
                z = if (nl >= a + size / 4) nl + 1 else text.lastIndexOf(' ', z - 1).takeIf { it > a }?.plus(1) ?: z
            }
            out += a to text.substring(a, z); a = z
        }
        return out.filter { it.second.isNotBlank() }
    }

    fun messages(piece: String, ask: String): JSONArray = JSONArray()
        .put(JSONObject().put("role", "system").put("content",
            "You are a careful editor. You get a text and maybe the user's instruction. Answer only JSON: " +
            "{\"suggestions\":[{\"find\":\"...\",\"replace\":\"...\",\"reason\":\"...\"}]}. " +
            "find is copied character for character from the text (a word, a phrase or at most one sentence, long enough to be found once); " +
            "replace is what should stand there instead; reason says why in a few words, in the language of the text. " +
            "Only real improvements, at most 20; keep the author's meaning and voice. Nothing to change: {\"suggestions\":[]}. " +
            "Without an instruction: correct spelling, grammar, punctuation and clear mistakes of style."))
        .put(JSONObject().put("role", "user").put("content",
            (if (ask.isNotBlank()) "Instruction: ${ask.trim()}\n\n" else "") + "Text:\n<<<\n$piece\n>>>"))

    /**
     * The model's [reply] for [piece] (which starts at [base] in the editor's text): its suggestions placed where their
     * find is, in order; one not found exactly, one that changes nothing, or one over another's letters is dropped.
     * A reply that is not the JSON asked for gives none (a ```json fence or words around it are allowed).
     */
    fun parse(reply: String, piece: String, base: Int = 0): List<Suggestion> {
        val list = runCatching {
            val t = reply.trim()
            val a = t.indexOfFirst { it == '{' || it == '[' }; if (a < 0) return emptyList()
            val v = if (t[a] == '[') JSONArray(t.substring(a, t.lastIndexOf(']') + 1)) else JSONObject(t.substring(a, t.lastIndexOf('}') + 1)).optJSONArray("suggestions")
            v ?: return emptyList()
        }.getOrElse { return emptyList() }
        val out = mutableListOf<Suggestion>()
        var from = 0
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val find = o.optString("find"); val replace = o.optString("replace"); val reason = o.optString("reason").trim()
            if (find.isEmpty() || find == replace || !o.has("replace")) continue
            // the first place after the one before (the model goes in order), else anywhere
            val at = piece.indexOf(find, from).takeIf { it >= 0 } ?: piece.indexOf(find).takeIf { it >= 0 } ?: continue
            if (out.any { at < it.end - base && it.at - base < at + find.length }) continue
            out += Suggestion(base + at, find, replace, reason); from = at + find.length
        }
        return out.sortedBy { it.at }
    }

    /** What is still waiting after [done] was put in: those after it moved by the length it changed. */
    fun after(list: List<Suggestion>, done: Suggestion): List<Suggestion> {
        val d = done.replace.length - done.find.length
        return list.filter { it != done }.map { if (it.at >= done.end) it.copy(at = it.at + d) else it }
    }

    /** [list] as it stands in [text] now: in place, else the nearest place its find is (the user typed meanwhile), else gone. */
    fun locate(text: String, list: List<Suggestion>): List<Suggestion> = list.mapNotNull { s ->
        if (text.regionMatches(s.at, s.find, 0, s.find.length)) return@mapNotNull s
        var best = -1; var i = text.indexOf(s.find)
        while (i >= 0) { if (best < 0 || kotlin.math.abs(i - s.at) < kotlin.math.abs(best - s.at)) best = i; i = text.indexOf(s.find, i + 1) }
        if (best < 0) null else s.copy(at = best)
    }.distinctBy { it.at }

    /** Every piece of [text] asked about [ask], at most [MAX_CHUNKS]; the suggestions and whether the text went on past the cap. */
    fun ask(c: Context, text: String, base: Int, ask: String, progress: (Int, Int) -> Unit): Pair<List<Suggestion>, Boolean> {
        if (!AiGate.allows("writer")) throw DriveProblem("AI Assist may not use Words.", R.string.ai_is_off)
        AssistantNet.key(c, AiProviders.of(c, AiPart.CHAT)) // no key: said at once
        val api = AssistantNet.api(c).tagged("writer")
        val all = chunks(text); val sent = all.take(MAX_CHUNKS)
        val out = sent.flatMapIndexed { n, (start, piece) ->
            progress(n + 1, sent.size)
            val reply = api.complete(messages(piece, ask), json = true)
            parse(reply, piece, base + start).also { WriterLog.i("suggestions: piece ${n + 1}/${sent.size}, ${it.size} kept") }
        }
        return out to (all.size > sent.size)
    }
}

/** What to ask for (empty: proofreading), for the selection when [selected], else the whole document; nothing is sent until Send. */
@Composable
internal fun SuggestDialog(selected: Boolean, dismiss: () -> Unit, send: (String) -> Unit) {
    var ask by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.words_suggest)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if (selected) R.string.words_suggest_for_selection else R.string.words_suggest_for_document))
            OutlinedTextField(ask, { ask = it }, label = { Text(stringResource(R.string.words_suggest_what)) },
                placeholder = { Text(stringResource(R.string.words_suggest_hint)) }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.words_suggest_note), style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = { send(ask) }) { Text(stringResource(R.string.common_send)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } })
}

/** On the ribbon's dark: [busy] while asking, else suggestion [s] ([n] of [of]): its letters struck, the replacement, the reason, and what to do. */
@Composable
internal fun SuggestBar(busy: String?, s: WriterSuggest.Suggestion?, n: Int, of: Int, w: Color, go: (Int) -> Unit, accept: () -> Unit, reject: () -> Unit,
    acceptAll: () -> Unit, close: () -> Unit) = Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_sparkle), contentDescription = stringResource(R.string.words_suggest), tint = Color(0xFFCE93D8), modifier = Modifier.size(20.dp))
        if (busy != null || s == null) Text(busy.orEmpty(), color = w.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp))
        else Text(buildAnnotatedString {
            withStyle(SpanStyle(color = Color(0xFFE57373), textDecoration = TextDecoration.LineThrough)) { append(s.find.replace('\n', ' ')) }
            append("  →  ")
            withStyle(SpanStyle(color = Color(0xFF81C784))) { append(s.replace.replace('\n', ' ').ifEmpty { "∅" }) }
            if (s.reason.isNotEmpty()) withStyle(SpanStyle(color = w.copy(alpha = 0.7f))) { append("  ·  " + s.reason) }
        }, color = w, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 6.dp))
    }
    if (busy == null && s != null) Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        Text("${n + 1}/$of", color = w.copy(alpha = 0.7f), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 30.dp, end = 4.dp))
        Tool(R.drawable.ic_chevron_left, stringResource(R.string.words_suggest_previous), w, enabled = of > 1) { go(-1) }
        Tool(R.drawable.ic_chevron_right, stringResource(R.string.words_suggest_next), w, enabled = of > 1) { go(1) }
        TextButton(onClick = accept) { Text(stringResource(R.string.words_accept), color = w) }
        TextButton(onClick = reject) { Text(stringResource(R.string.words_reject), color = w.copy(alpha = 0.8f)) }
        if (of > 1) TextButton(onClick = acceptAll) { Text(stringResource(R.string.words_accept_all), color = w.copy(alpha = 0.8f)) }
        Tool(R.drawable.ic_close, stringResource(R.string.common_close), w) { close() }
    }
}
