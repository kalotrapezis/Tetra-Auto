package com.kalotrapezis.drive

import android.content.SharedPreferences
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * The Browser's gestures (the user, 13 Nov: "one browser for nerds that you can have no interruptions and still be at your
 * finger tips"), each on unless switched off, in Settings › Browser › Gestures or in their guide; kept in the Browser's prefs.
 */
internal enum class BrowserGesture(val key: String, val title: Int, val text: Int) {
    Knob("tab_knob", R.string.browser_tab_knob, R.string.browser_g_knob_text),
    Swipe("two_swipe", R.string.browser_g_swipe, R.string.browser_g_swipe_text),
    Hold("two_hold", R.string.browser_g_hold, R.string.browser_g_hold_text),
    ListSwipe("list_swipes", R.string.browser_g_list, R.string.browser_g_list_text),
    Pinch("video_pinch", R.string.browser_g_pinch, R.string.browser_g_pinch_text);

    fun on(p: SharedPreferences) = p.getBoolean(key, true)

    companion object {
        const val GUIDE_SEEN = "gestures_guide_seen"
        fun onSet(p: SharedPreferences) = entries.filter { it.on(p) }.toSet()
    }
}

/**
 * The gestures' guide: by itself the first time the Browser opens after they came, and from Settings › Browser any time.
 * A card a gesture: a drawn phone showing it, looping; its name with its switch; two lines; Skip and Next.
 */
@Composable
internal fun BrowserGesturesGuide(prefs: SharedPreferences, close: () -> Unit) {
    var at by rememberSaveable { mutableIntStateOf(0) }
    val all = BrowserGesture.entries
    val g = all[at]
    var on by remember(at) { mutableStateOf(g.on(prefs)) }
    fun done() { prefs.edit().putBoolean(BrowserGesture.GUIDE_SEEN, true).apply(); WriterLog.i("Browser: the gestures' guide closed at ${g.name}"); close() }
    Dialog(onDismissRequest = ::done, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 460.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.browser_gestures) + " · " + stringResource(R.string.guide_step_of, at + 1, all.size),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // the guide's frame (Guide.kt): the picture on the page's own ground
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.background,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f))) {
                    GestureMovie(g, Modifier.fillMaxWidth().height(200.dp).padding(12.dp))
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(stringResource(g.title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    Switch(on, { on = it; prefs.edit().putBoolean(g.key, it).apply(); WriterLog.i("Browser: gesture ${g.name} ${if (it) "on" else "off"}") })
                }
                Text(stringResource(g.text), style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (at < all.size - 1) TextButton(::done, Modifier.heightIn(min = 52.dp)) { Text(stringResource(R.string.guide_skip)) }
                    Spacer(Modifier.weight(1f))
                    Button({ if (at < all.size - 1) at++ else done() }, Modifier.heightIn(min = 52.dp)) {
                        Text(stringResource(if (at < all.size - 1) R.string.common_next else R.string.guide_finish))
                    }
                }
            }
        }
    }
}

/** 0 before [a], 1 after [b], eased between. */
private fun part(t: Float, a: Float, b: Float) = FastOutSlowInEasing.transform(((t - a) / (b - a)).coerceIn(0f, 1f))

/**
 * A gesture drawn, looping (drawn, not GIFs: small, in the theme, the same in every language): a phone, its page, the finger
 * as the guide's bright dot (Guide.kt's mark colour), the tabs' list as it shows.
 */
@Composable
private fun GestureMovie(g: BrowserGesture, modifier: Modifier) {
    val length = when (g) { BrowserGesture.Knob, BrowserGesture.ListSwipe -> 4200; BrowserGesture.Hold -> 3200; else -> 2600 }
    val t by rememberInfiniteTransition(label = "gesture").animateFloat(0f, 1f, infiniteRepeatable(tween(length, easing = LinearEasing), RepeatMode.Restart), label = "t")
    val finger = lookTint(Color(0xFFFFB020), Color(0xFFB25E00))
    val line = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
    val screen = MaterialTheme.colorScheme.surfaceContainerHighest
    val pages = listOf(TILE_COLORS[1], TILE_COLORS[3], TILE_COLORS[5], TILE_COLORS[4])
    val bin = painterResource(R.drawable.ic_delete)
    val pin = painterResource(R.drawable.ic_push_pin)
    Canvas(modifier) {
        val landscape = g == BrowserGesture.Pinch
        val ph = if (landscape) size.height * 0.62f else size.height
        val pw = if (landscape) ph * 2.1f else ph * 0.5f
        val phone = Rect(Offset((size.width - pw) / 2, (size.height - ph) / 2), Size(pw, ph))
        val inset = 5.dp.toPx()
        val glass = Rect(phone.left + inset, phone.top + inset, phone.right - inset, phone.bottom - inset)
        val dot = 7.dp.toPx()
        fun touch(at: Offset, press: Float = 1f) {
            drawCircle(finger.copy(alpha = 0.3f * press), dot * 1.8f, at)
            drawCircle(finger, dot * (0.7f + 0.3f * press), at)
        }
        fun page(c: Color) = drawRoundRect(c.copy(alpha = 0.7f), glass.topLeft, glass.size, CornerRadius(8.dp.toPx()))
        // the tabs' list over the page: New tab, then three tabs, [hi] framed
        fun list(hi: Int, show: Float) {
            if (show <= 0f) return
            drawRect(Color.Black.copy(alpha = 0.45f * show), glass.topLeft, glass.size)
            val w = glass.width * 0.7f; val left = glass.center.x - w / 2; val gap = 4.dp.toPx()
            val bar = glass.height * 0.08f; val h = (glass.height - bar - gap * 6) / 3
            var y = glass.top + gap
            for (i in 0..3) {
                val hh = if (i == 0) bar else h
                drawRoundRect(if (i == 0) line else pages[i - 1], Offset(left, y), Size(w, hh), CornerRadius(4.dp.toPx()), alpha = show)
                if (i == hi) drawRoundRect(BROWSER_COLOR, Offset(left, y), Size(w, hh), CornerRadius(4.dp.toPx()), style = Stroke(2.5.dp.toPx()), alpha = show)
                y += hh + gap
            }
        }
        fun icon(p: Painter, at: Offset, s: Float) = translate(at.x - s / 2, at.y - s / 2) { with(p) { draw(Size(s, s), colorFilter = ColorFilter.tint(Color.White)) } }
        drawRoundRect(screen, phone.topLeft, phone.size, CornerRadius(14.dp.toPx()))
        when (g) {
            BrowserGesture.Knob -> {
                val knob = Rect(glass.right - 6.dp.toPx(), glass.top + glass.height * 0.55f, glass.right, glass.top + glass.height * 0.8f)
                val step = glass.height * 0.12f
                if (t < 0.45f) { // a slide up the knob: the next tab, and the next
                    val k = part(t, 0.08f, 0.4f) * 2
                    page(pages[k.toInt().coerceAtMost(2)])
                    drawRoundRect(Color.Black, knob.topLeft, knob.size, CornerRadius(3.dp.toPx()))
                    touch(Offset(knob.center.x, knob.center.y - k * step), if (t < 0.42f) 1f else 0f)
                } else { // held: the list from the first tab; down a step and let go
                    val held = part(t, 0.48f, 0.58f)
                    val down = part(t, 0.62f, 0.8f)
                    val hi = 1 + (down * 2).toInt()
                    page(pages[if (t > 0.9f) hi - 1 else 2])
                    drawRoundRect(Color.Black, knob.topLeft, knob.size, CornerRadius(3.dp.toPx()))
                    list(hi, if (t > 0.9f) 1f - part(t, 0.9f, 0.95f) else held)
                    if (t < 0.9f) touch(Offset(knob.center.x, knob.center.y + down * step * 2), 0.6f + 0.4f * held)
                }
            }
            BrowserGesture.Swipe -> {
                val up = part(t, 0.15f, 0.6f)
                page(if (up < 0.5f) pages[0] else pages[1])
                if (t < 0.7f) { val y = glass.center.y + glass.height * (0.2f - 0.4f * up)
                    touch(Offset(glass.center.x - glass.width * 0.24f, y)); touch(Offset(glass.center.x + glass.width * 0.24f, y)) }
            }
            BrowserGesture.Hold -> {
                val held = part(t, 0.2f, 0.4f)
                val down = part(t, 0.5f, 0.75f)
                val hi = 1 + (down * 1.99f).toInt()
                page(if (t > 0.85f) pages[hi - 1] else pages[0])
                list(hi, if (t > 0.85f) 1f - part(t, 0.85f, 0.9f) else held)
                if (t < 0.85f) { val y = glass.center.y + down * glass.height * 0.2f
                    if (t < 0.4f) drawCircle(finger.copy(alpha = 0.5f * (1f - held)), dot * (1.8f + 3f * held), Offset(glass.center.x, y), style = Stroke(2.dp.toPx()))
                    touch(Offset(glass.center.x - glass.width * 0.24f, y)); touch(Offset(glass.center.x + glass.width * 0.24f, y)) }
            }
            BrowserGesture.ListSwipe -> {
                page(pages[2]); drawRect(Color.Black.copy(alpha = 0.45f), glass.topLeft, glass.size)
                val w = glass.width * 0.7f; val h = glass.height * 0.3f
                val home = Offset(glass.center.x - w / 2, glass.center.y - h / 2)
                val r = CornerRadius(5.dp.toPx())
                // right: the red strip and its bin, the tab gone; then left: the accent and its pin, springing back pinned
                val (dx, gone) = when {
                    t < 0.45f -> part(t, 0.1f, 0.4f) * w * 0.75f to part(t, 0.38f, 0.45f)
                    t < 0.55f -> 0f to 1f - part(t, 0.48f, 0.55f)
                    else -> -(part(t, 0.6f, 0.78f) - part(t, 0.8f, 0.92f)) * w * 0.55f to 0f
                }
                clipRect(home.x, home.y, home.x + w, home.y + h) {
                    if (dx > 0) { drawRoundRect(Color(0xFFD93025), home, Size(w, h), r); icon(bin, Offset(home.x + 14.dp.toPx(), home.y + h / 2), 16.dp.toPx()) }
                    if (dx < 0) { drawRoundRect(BROWSER_COLOR, home, Size(w, h), r); icon(pin, Offset(home.x + w - 14.dp.toPx(), home.y + h / 2), 16.dp.toPx()) }
                }
                drawRoundRect(pages[1], home + Offset(dx, 0f), Size(w, h), r, alpha = 1f - gone)
                if (t > 0.9f) icon(pin, Offset(home.x + 10.dp.toPx(), home.y + 10.dp.toPx()), 12.dp.toPx())
                if (t in 0.08f..0.42f || t in 0.58f..0.8f) touch(Offset(glass.center.x + dx, glass.center.y))
            }
            BrowserGesture.Pinch -> {
                // a 16:9 video on a wider screen: bars at its sides, filled by a pinch out, whole again by a pinch in
                val fill = part(t, 0.15f, 0.42f) - part(t, 0.6f, 0.85f)
                val fitW = glass.height * 16 / 9
                val vw = fitW + (glass.width - fitW) * fill; val vh = vw * 9 / 16
                drawRect(Color.Black, glass.topLeft, glass.size)
                clipRect(glass.left, glass.top, glass.right, glass.bottom) {
                    drawRect(pages[3], Offset(glass.center.x - vw / 2, glass.center.y - vh / 2), Size(vw, vh))
                    drawCircle(Color.White.copy(alpha = 0.8f), vh * 0.12f, glass.center)
                }
                val apart = glass.width * (if (t < 0.5f) 0.08f + 0.22f * part(t, 0.15f, 0.42f) else 0.3f - 0.22f * part(t, 0.6f, 0.85f))
                if (t in 0.1f..0.45f || t in 0.57f..0.88f) { touch(Offset(glass.center.x - apart, glass.center.y + glass.height * 0.1f)); touch(Offset(glass.center.x + apart, glass.center.y - glass.height * 0.1f)) }
            }
        }
        drawRoundRect(line, phone.topLeft, phone.size, CornerRadius(14.dp.toPx()), style = Stroke(2.dp.toPx()))
    }
}
