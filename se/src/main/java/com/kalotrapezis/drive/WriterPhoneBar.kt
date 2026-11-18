package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * The phone's bar on the screen's keyboard, as Word's phone app has it: Bold, Italic, Underline, the highlighter,
 * dictation, ⋯ (the whole ribbon over the keyboard) and the keyboard put away.
 */
@Composable
internal fun KeyboardBar(tint: Color, bold: Boolean, italic: Boolean, underline: Boolean, toggleBold: () -> Unit, toggleItalic: () -> Unit, toggleUnderline: () -> Unit,
    highlight: (Color?) -> Unit, dictate: () -> Unit, more: () -> Unit, hideKeyboard: () -> Unit) {
    val scroll = rememberScrollState()
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 6.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Tool(R.drawable.ic_format_bold, stringResource(R.string.words_bold), tint, on = bold, click = toggleBold)
        Tool(R.drawable.ic_format_italic, stringResource(R.string.words_italic), tint, on = italic, click = toggleItalic)
        Tool(R.drawable.ic_format_underlined, stringResource(R.string.words_underline), tint, on = underline, click = toggleUnderline)
        Swatches(tint, R.drawable.ic_highlighter, stringResource(R.string.words_highlight), HIGHLIGHT_COLOURS, highlight)
        Tool(R.drawable.ic_mic, stringResource(R.string.words_dictate), tint, click = dictate)
        Tool(R.drawable.ic_more_horiz, stringResource(R.string.words_more_tools), tint, click = more)
        Tool(R.drawable.ic_keyboard_hide, stringResource(R.string.words_hide_keyboard), tint, click = hideKeyboard)
    }
}

/**
 * Dictation: Android's voice typing (the phone's speech recognizer, in its own window), what was said put in at the
 * cursor. Null when the phone has no recognizer.
 */
@Composable
internal fun rememberDictation(said: (String) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(said)
    }
    val intent = remember { Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.words_dictate_prompt)) }
    val available = remember { intent.resolveActivity(context.packageManager) != null }
    return if (available) { { WriterLog.i("dictation"); launcher.launch(intent) } } else null
}

/** Read aloud (Review): Android's text-to-speech, a paragraph at a time, each in its own language (Greek or English). */
internal class ReadAloud(context: android.content.Context) {
    private val store = TextEditorStore(context)
    /** Settings › Office › Read aloud speed. */
    private val speed get() = store.readAloudSpeed
    var speaking by mutableStateOf(false); private set
    /** The paragraph being read (an index into what was given). */
    var at by mutableIntStateOf(-1); private set
    private var ready = false
    private var last = -1 // the last paragraph queued: when it is done, so is reading
    private val tts = TextToSpeech(context.applicationContext) { status -> ready = status == TextToSpeech.SUCCESS; if (!ready) WriterLog.w("read aloud: no text-to-speech") }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) { at = id.toIntOrNull() ?: at }
            override fun onDone(id: String) { files.remove(id)?.invoke(true); if (id.toIntOrNull() == last) { speaking = false; at = -1 } }
            @Deprecated("Deprecated in Java") override fun onError(id: String) { files.remove(id)?.invoke(false) ?: run { speaking = false } }
        })
    }


    /** [paragraphs] read from [from] on. False when the phone cannot speak. */
    fun read(paragraphs: List<String>, from: Int): Boolean {
        if (!ready) return false
        tts.stop()
        tts.setSpeechRate(speed)
        last = paragraphs.indices.lastOrNull { it >= from && paragraphs[it].isNotBlank() } ?: return false
        var language: Locale? = null
        paragraphs.forEachIndexed { i, t ->
            if (i < from || t.isBlank()) return@forEachIndexed
            val l = languageOf(t)
            if (l != language) { tts.language = l; language = l }
            tts.speak(t, TextToSpeech.QUEUE_ADD, null, i.toString())
        }
        speaking = true
        WriterLog.i("read aloud from paragraph $from")
        return true
    }
    private var added = 100_000 // the ids of the pieces given one at a time, beyond any paragraph's

    /** [text] read after what is being read: an answer (AI Assist) coming a sentence at a time. */
    fun add(text: String): Boolean {
        if (!ready || text.isBlank()) return false
        tts.setSpeechRate(speed)
        tts.language = languageOf(text)
        last = ++added
        speaking = true
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, last.toString())
        return true
    }
    private val files = java.util.concurrent.ConcurrentHashMap<String, (Boolean) -> Unit>()

    /** [text] made into [file] (a wave) and not played, [done] when it is: AI Assist plays it on the speaker it picks. */
    fun toFile(text: String, file: java.io.File, done: (Boolean) -> Unit): Boolean {
        if (!ready || text.isBlank()) return false
        tts.setSpeechRate(speed)
        tts.language = languageOf(text)
        val id = "file${++added}"
        files[id] = done
        if (tts.synthesizeToFile(text, null, file, id) == TextToSpeech.SUCCESS) return true
        files.remove(id); return false
    }
    fun stop() { tts.stop(); speaking = false; at = -1 }
    fun shutdown() { tts.stop(); tts.shutdown() }
}

/** The language a paragraph is read in: Greek when it has Greek letters, else English. */
internal fun languageOf(text: String): Locale = if (text.any { it in 'Ͱ'..'Ͽ' || it in 'ἀ'..'῾' }) Locale("el", "GR") else Locale.UK

@Composable
internal fun rememberReadAloud(): ReadAloud {
    val context = LocalContext.current
    val reader = remember { ReadAloud(context) }
    DisposableEffect(reader) { onDispose { reader.shutdown() } }
    return reader
}

/** While reading aloud: the paragraph being read, and Stop. */
@Composable
internal fun ReadAloudBar(reader: ReadAloud, paragraph: String?, tint: Color) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.words_reading_aloud), color = tint, style = MaterialTheme.typography.labelLarge)
        Text(paragraph.orEmpty(), color = tint.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
        TextButton(onClick = { reader.stop() }) { Text(stringResource(R.string.common_stop), color = tint, maxLines = 1) }
    }
}
