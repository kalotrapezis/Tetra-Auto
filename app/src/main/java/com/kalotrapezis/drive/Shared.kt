package com.kalotrapezis.drive

import android.app.Activity
import android.app.PendingIntent
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.Manifest
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.format.Formatter
import android.util.Base64
import android.view.Surface
import android.webkit.MimeTypeMap
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Secrets each encrypted by a key that never leaves the keystore; never synced (AssistantKey's way): Mail's passwords, the Browser's vault. */
internal open class KeystoreSecrets(private val alias: String, private val file: String) {
    private fun prefs(c: Context) = c.getSharedPreferences(file, Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getKey(alias, null) as? SecretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun ids(c: Context): Set<String> = prefs(c).all.keys
    fun get(c: Context, id: String): String? = prefs(c).getString(id, null)?.let { saved -> runCatching {
        val b = Base64.decode(saved, Base64.NO_WRAP)
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12)) }.doFinal(b, 12, b.size - 12).decodeToString()
    }.onFailure { WriterLog.e("$file: a secret could not be read", it) }.getOrNull() }
    fun set(c: Context, id: String, secret: String?) {
        if (secret == null) { prefs(c).edit().remove(id).apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        prefs(c).edit().putString(id, Base64.encodeToString(cipher.iv + cipher.doFinal(secret.toByteArray()), Base64.NO_WRAP)).apply()
    }
}

/** Read aloud (Review): Android's text-to-speech, a paragraph at a time, each in its own language (Greek or English). */
internal class ReadAloud(context: android.content.Context) {
    private val prefs = context.applicationContext.getSharedPreferences("office_editor", android.content.Context.MODE_PRIVATE)
    private val speed get() = prefs.getFloat("read_aloud_speed", 1f)
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

@Composable internal fun IslandBottomBar(expand: () -> Unit, visible: Boolean, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val dragThreshold = with(LocalDensity.current) { 24.dp.toPx() }
    IslandVisibility(visible) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge, color = islandColor(), contentColor = islandContentColor(),
            modifier = modifier.pointerInput(expand) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onVerticalDrag = { _, amount -> dragged += amount },
                    onDragEnd = { if (dragged < -dragThreshold) expand() },
                )
            },
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.width(48.dp).height(18.dp).clickable(onClick = expand), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(28.dp).height(3.dp).background(islandContentColor().copy(alpha = 0.65f), CircleShape))
                }
                Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable internal fun IslandVisibility(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(90)) + slideInVertically(tween(120)) { -it / 3 },
        exit = fadeOut(tween(80)),
    ) { content() }
}

@Composable internal fun IslandNavigationItem(label: String, selected: Boolean, icon: Int, click: () -> Unit) = Surface(
    color = if (selected) driveNavigationSelectedColor() else Color.Transparent,
    contentColor = if (selected) driveNavigationSelectedContentColor() else islandContentColor(),
    shape = MaterialTheme.shapes.extraLarge,
    modifier = Modifier.heightIn(min = 48.dp).clickable(onClick = click).semantics { this.selected = selected },
) {
    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(22.dp))
        if (selected) Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
