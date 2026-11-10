package com.kalotrapezis.drive

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The robot as a presenter (8 Nov: "the report like a small news show, with a paper in front like it reads the news… the same
 * for any scheduled task: the chap appears on the screen and walks it"): a brief or a scheduled result, said in a few
 * spoken lines by a presenter's prompt, read aloud at a desk. Under AI decorations; Page shows the page as before.
 */
internal object Presenter {
    private fun prefs(c: Context) = c.getSharedPreferences("assistant", Context.MODE_PRIVATE)
    fun on(c: Context) = AiLook.decorated(c) && prefs(c).getBoolean("presenter", true)
    fun set(c: Context, on: Boolean) = prefs(c).edit().putBoolean("presenter", on).apply()

    const val PROMPT = "You are the friendly presenter of a tiny TV news show inside the user's phone. Present the page below as spoken words: " +
        "a short greeting, then the three to five most useful points in plain sentences, then a one-line sign-off. No lists, no markdown, " +
        "no links, under 90 words. Speak in the language of the page."

    /** The page's words as the presenter says them; without a key, past the month's budget or on an error, its first lines. */
    fun script(c: Context, title: String, text: String): String {
        val page = text.take(6000)
        if (AssistantKey.get(c) == null || AssistantUsage.of(c).capped()) return opening(page)
        return runCatching {
            val api = Mistral({ path, body -> AssistantNet.post(AssistantNet.key(c), path, body) }, AssistantMeter.bill(c)).tagged("presenter")
            api.complete(JSONArray().put(JSONObject().put("role", "system").put("content", PROMPT)).put(JSONObject().put("role", "user").put("content", "Title: $title\n\n$page")))
        }.onFailure { WriterLog.w("AI Assist presenter: ${it.message}") }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: opening(page)
    }

    /** The first few sentences, up to about 500 letters. */
    fun opening(text: String): String = text.take(500).let { s -> s.lastIndexOfAny(charArrayOf('.', '!', ';', '?')).takeIf { it > 80 }?.let { s.take(it + 1) } ?: s }
}

/** The show: the TV comes on, the presenter walks in and reads; Page for the page itself, AI Assist, ✕ switches it off. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable internal fun NewsShow(title: String, html: String, page: () -> Unit, close: () -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val reader = rememberReadAloud()
    var speaking by remember { mutableStateOf(false) }
    var voice by remember { mutableStateOf<AnswerVoice?>(null) }
    var off by remember { mutableStateOf(false) }
    val color = remember { characterColor(AssistantVoices.id(c)) }
    fun bye() { if (off) return; off = true; voice?.stop(); reader.stop(); Zap.play(); scope.launch { delay(520); close() } }
    LaunchedEffect(Unit) {
        Zap.play()
        val text = android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT).toString().replace(Regex("\\s+"), " ").trim()
        val script = withContext(Dispatchers.IO) { Presenter.script(c.applicationContext, title, text) }
        delay(600) // it is at its desk by now
        val v = AnswerVoice(c.applicationContext, reader, AssistantMeter.bill(c).part("voice"), scope).also { voice = it }
        val cut = Sentences(); cut.push(script).forEach(v::say); v.say(cut.rest()); v.finish()
        speaking = true; v.await(); speaking = false; voice = null
    }
    DisposableEffect(Unit) { onDispose { voice?.stop(); reader.stop() } }
    BackHandler { bye() }
    Box(Modifier.fillMaxSize().background(Color(0xFF101010)).statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 640.dp).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            VhsTv(0, false, Modifier.padding(bottom = 12.dp), off = off) { NewsScreen(speaking, title, color, Modifier.fillMaxWidth().height(260.dp)) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PixelButton(stringResource(R.string.news_page), Color(0xFFFFE082), R.drawable.ic_file) { voice?.stop(); reader.stop(); page() }
                PixelButton(stringResource(R.string.app_assistant), color, R.drawable.ic_chat) { openAssistant(c); bye() }
                PixelButton(stringResource(R.string.common_close), Color(0xFFE0E0E0), R.drawable.ic_close) { bye() }
            }
        }
    }
}

/** The studio: a night-blue wall with a globe, the presenter behind its desk with the paper, the ticker with the title. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun NewsScreen(speaking: Boolean, title: String, color: Color, modifier: Modifier = Modifier) {
    val loop = rememberInfiniteTransition(label = "news")
    val t by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "t")
    val blink by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(3900, easing = LinearEasing)), label = "blink")
    val walk = remember { Animatable(1f) }
    LaunchedEffect(Unit) { walk.animateTo(0f, tween(1100, easing = FastOutSlowInEasing)) }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val side = minOf(size.width, size.height)
            drawRect(Brush.verticalGradient(listOf(Color(0xFF1E3A5F), Color(0xFF0B1A2B))))
            val globe = Offset(size.width * 0.8f, size.height * 0.3f); val gr = side * 0.28f // the globe on the wall, turning slowly
            drawCircle(Color(0xFF2E5C8A).copy(alpha = 0.6f), radius = gr, center = globe)
            for (k in 0 until 4) { val w = gr * kotlin.math.abs(kotlin.math.cos((t + k / 4f) * Math.PI)).toFloat()
                drawOval(Color(0xFF6FA8DC).copy(alpha = 0.5f), Offset(globe.x - w, globe.y - gr), Size(2 * w, 2 * gr), style = Stroke(1.5f)) }
            drawLine(Color(0xFF6FA8DC).copy(alpha = 0.5f), globe - Offset(gr, 0f), globe + Offset(gr, 0f), strokeWidth = 1.5f)
            // the presenter, walking in to its place, then still but for its voice
            val cell = side * 0.55f / 24f
            val step = if (walk.value > 0f) kotlin.math.abs(kotlin.math.sin(walk.value * 6 * Math.PI)).toFloat() * cell * 2 else 0f
            val top = Offset(size.width * 0.36f - 14 * cell - walk.value * size.width * 0.8f, size.height * 0.14f - step)
            drawMascot(top, cell, color, blink > 0.95f, speaking, t)
            // the desk over its lower half, and the paper on it, lifted a little as it reads
            val desk = size.height * 0.68f
            drawRect(Color(0xFF6D4C41), Offset(0f, desk), Size(size.width, size.height - desk))
            drawRect(Color(0xFF8D6E63), Offset(0f, desk), Size(size.width, side * 0.025f))
            val lift = if (speaking) kotlin.math.sin(t * 2 * Math.PI).toFloat() * side * 0.012f else 0f
            val paper = Offset(size.width * 0.36f + 4 * cell - walk.value * size.width * 0.8f, desk - side * 0.1f + lift)
            rotate(-6f, paper) {
                drawRoundRect(Color(0xFFF4F1EA), paper - Offset(side * 0.15f, side * 0.04f), Size(side * 0.3f, side * 0.2f), CornerRadius(3f))
                for (k in 0 until 4) drawLine(Color(0xFF9E9E9E), paper + Offset(-side * 0.11f, k * side * 0.035f), paper + Offset(side * (0.11f - (k % 2) * 0.04f), k * side * 0.035f), strokeWidth = 2f)
            }
        }
        // the ticker
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xFFC62828)), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.news_label), Modifier.background(Color(0xFFF4F1EA)).padding(horizontal = 8.dp, vertical = 3.dp), color = Color(0xFFC62828),
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black)
            Text(title, Modifier.padding(horizontal = 8.dp).basicMarquee(), color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}
