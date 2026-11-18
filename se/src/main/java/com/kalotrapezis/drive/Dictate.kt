package com.kalotrapezis.drive

import android.Manifest
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A mic for a text field: tap, speak, and the words come back through [insert]. By the service chosen to listen
 * (Voxtral, AI Assist's key) while the AI is on and the key is there, otherwise by Android's own speech recognizer.
 * A second tap while it listens ends the phrase.
 */
@Composable
internal fun DictateButton(insert: (String) -> Unit, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var listening by remember { mutableStateOf(false) }
    var writing by remember { mutableStateOf(false) }
    val stop = remember { AtomicBoolean(false) }
    fun tell(text: String?) { if (!text.isNullOrBlank()) Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }

    val android = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(insert)
    }
    fun byAndroid() = runCatching {
        // ends on the same pause as Voxtral's (Settings › AI Assist); a recognizer may ignore these, as Android allows
        android.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, AssistantPause.ms(context).toLong())
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, AssistantPause.ms(context).toLong()))
    }.onFailure { tell(context.getString(R.string.dictate_unavailable)) }

    fun byVoxtral() = scope.launch {
        stop.set(false); listening = true
        val pcm = withContext(Dispatchers.IO) { runCatching { listen(8_000, { stop.get() || !isActive }, pauseMs = AssistantPause.ms(context), mic = AssistantAudio.mic(context)) }.getOrNull() }
        listening = false
        if (pcm == null) return@launch tell(context.getString(R.string.assistant_heard_nothing))
        writing = true
        withContext(Dispatchers.IO) { runCatching { AssistantNet.transcribe(context, pcm, AssistantWords.terms(context), AssistantMeter.bill(context).part("dictation")) } }
            .onSuccess { if (it.isBlank()) tell(context.getString(R.string.assistant_heard_nothing)) else insert(it) }
            .onFailure { tell(it.shownMessage(context)) }
        writing = false
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) byVoxtral() else tell(context.getString(R.string.assistant_mic_needed)) }

    IconButton(onClick = {
        if (listening) stop.set(true)
        else if (!writing) scope.launch {
            // the AI's switch is a file (it may have come by sync), so read off the main thread, as AssistantNet.key does
            val voxtral = withContext(Dispatchers.IO) { runCatching { AiGate.load(context); AiGate.check(); AssistantKey.has(context, AiProviders.of(context, AiPart.LISTEN)) }.getOrDefault(false) }
            when { !voxtral -> byAndroid(); hasMic(context) -> byVoxtral(); else -> askMic.launch(Manifest.permission.RECORD_AUDIO) }
        }
    }, modifier) {
        if (writing) CircularProgressIndicator(Modifier.size(20.dp), color = tint, strokeWidth = 2.dp)
        else Icon(painterResource(R.drawable.ic_mic), stringResource(if (listening) R.string.common_stop else R.string.dictate),
            tint = if (listening) Color(0xFFD32F2F) else tint)
    }
}

/** [words] in at the cursor (over a selection, as typing would), a space between them and the text either side. */
internal fun TextFieldValue.dictated(words: String): TextFieldValue {
    val before = text.substring(0, selection.min); val after = text.substring(selection.max)
    val put = (if (before.isNotEmpty() && !before.last().isWhitespace()) " " else "") + words.trim() + (if (after.isNotEmpty() && !after.first().isWhitespace()) " " else "")
    return TextFieldValue(before + put + after, TextRange(before.length + put.length))
}

// shortcut: a String field has no cursor to read, so the words go at its end; hold its value as a TextFieldValue to insert mid-text.
internal fun String.dictated(words: String) = TextFieldValue(this, TextRange(length)).dictated(words).text
