package com.kalotrapezis.drive

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.dreams.DreamService
import android.view.View
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.graphics.ColorUtils
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Stand mode (the user, 30 Oct: "for amoled, super low light clock thing when charging"): Android's own screen saver, which
 * starts by itself while the phone charges, showing a dim grey clock on black with the screen at its lowest. Burn-in: the
 * clock moves a little every minute, and nothing else is lit. Before an alarm the screen rises as a sunrise ("simulate the
 * sunrise with warm light … so I may wake up 1-2 min earlier with just the light"): [SUNRISE_MS] from deep red through orange
 * to warm yellow, the brightness with it, full at the ring. No alarm, or a skipped one: no light.
 */
class StandDream : DreamService() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.wrap(base)) // Tetra's language, not only the phone's
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var next = 0L // the next alarm to ring (snoozes counted, skips not), epoch ms; 0: none
    private var ticks = 0
    private lateinit var clock: StandClockView
    private val tick = object : Runnable {
        override fun run() {
            if (ticks++ % 6 == 0) Thread { // the alarms' files once a minute, off the screen's thread
                next = runCatching { TimerAlarmFiles.upcoming(TimerControl.dir(), Instant.now(), ZoneId.systemDefault())?.second?.toEpochMilli() }.getOrNull() ?: 0L
            }.start()
            val t = sunrise(next, System.currentTimeMillis())
            clock.glow = t
            window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = MIN_LIGHT + (1f - MIN_LIGHT) * t * t } }
            main.postDelayed(this, 10_000)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false // a touch ends it
        isFullscreen = true
        clock = StandClockView(this)
        setContentView(clock)
    }
    override fun onDreamingStarted() { super.onDreamingStarted(); main.post(tick) }
    override fun onDreamingStopped() { main.removeCallbacks(tick); super.onDreamingStopped() }

    private companion object { const val MIN_LIGHT = 0.01f }
}

/** The sunrise starts this long before an alarm. */
internal const val SUNRISE_MS = 20 * 60_000L

/** How far the sunrise is, 0 (none) to 1 (the ring), for an alarm at [next] (0: none). */
internal fun sunrise(next: Long, now: Long): Float =
    if (next <= 0L || next - now >= SUNRISE_MS) 0f else (1f - (next - now).toFloat() / SUNRISE_MS).coerceIn(0f, 1f)

/** The sky at [t]: deep red, orange, warm yellow. */
private fun sky(t: Float): Int {
    val stops = listOf(0f to 0xFF300000.toInt(), 0.35f to 0xFF9A1800.toInt(), 0.7f to 0xFFFF7A10.toInt(), 1f to 0xFFFFC870.toInt())
    val i = stops.indexOfLast { it.first <= t }.coerceIn(0, stops.size - 2)
    val (t0, c0) = stops[i]; val (t1, c1) = stops[i + 1]
    return ColorUtils.blendARGB(c0, c1, ((t - t0) / (t1 - t0)).coerceIn(0f, 1f))
}

/** The time and the date, dim grey on black (on the sunrise's colour before an alarm), a little elsewhere each minute. */
internal class StandClockView(context: Context) : View(context) {
    private val time = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL); textAlign = Paint.Align.CENTER }
    private val date = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); textAlign = Paint.Align.CENTER }
    private val hhmm = DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm", context.uiLocale())
    private val day = DateTimeFormatter.ofPattern("EEEE d MMMM", context.uiLocale())
    private val tick = object : Runnable {
        override fun run() { invalidate(); postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000) }
    }
    var glow = 0f
        set(v) { if (v != field) { field = v; invalidate() } }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val sky = if (glow > 0f) sky(glow) else Color.BLACK
        canvas.drawColor(sky)
        time.color = if (glow > 0f) ColorUtils.blendARGB(sky, Color.BLACK, 0.55f) else 0xFF505050.toInt()
        date.color = if (glow > 0f) ColorUtils.blendARGB(sky, Color.BLACK, 0.4f) else 0xFF383838.toInt()
        val now = LocalDateTime.now()
        time.textSize = minOf(width, height) * 0.24f
        date.textSize = time.textSize * 0.2f
        // a 5 × 5 walk around the middle, one step a minute, so no pixel stays lit for long
        val m = now.minute
        val x = width / 2f + (m % 5 - 2) * width * 0.04f
        val y = height / 2f + (m / 5 % 5 - 2) * height * 0.04f
        canvas.drawText(now.format(hhmm), x, y, time)
        canvas.drawText(now.format(day), x, y + date.textSize * 2f, date)
    }
}

/** The Clock's header button: what stand mode is, and Android's screen saver page, where it is chosen. */
@Composable internal fun StandButton() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
        IconButton(onClick = { open = true }) { Icon(painterResource(R.drawable.ic_bedtime), contentDescription = stringResource(R.string.stand_mode)) }
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(R.string.stand_mode)) },
        text = { Text(stringResource(R.string.stand_about)) },
        confirmButton = { Button(onClick = {
            open = false
            runCatching { context.startActivity(Intent(Settings.ACTION_DREAM_SETTINGS)) }.onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) } }
        }) { Text(stringResource(R.string.stand_settings)) } },
        dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.common_cancel)) } })
}
