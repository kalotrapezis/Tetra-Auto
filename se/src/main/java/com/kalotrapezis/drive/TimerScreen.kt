package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

internal val TIMER_COLOR = Color(0xFF26A69A)

/**
 * The Timer (Plan.md § Timer): the timers' cards and Add new (the user, 30 Oct: the clock went into a modal, ✓ starts the
 * timer, to save the room the island takes); Countdown, Alarms and Stopwatch in the island at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimerScreen(back: () -> Unit, hasAccess: Boolean, grant: () -> Unit) {
    val context = LocalContext.current
    val wide = LocalConfiguration.current.screenWidthDp >= 700
    val side = wide || LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp // landscape or a tablet: the actions beside the island
    val scope = rememberCoroutineScope()
    BackHandler { back() }

    if (!hasAccess) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_back)) }
            Text(stringResource(R.string.timer_access_needed))
            TextButton(onClick = grant) { Text(stringResource(R.string.timer_allow_access), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        return
    }
    // each device counts by itself: the files read twice a second, the time left worked out from them
    var now by remember { mutableStateOf(Instant.now()) }
    var timers by remember { mutableStateOf(emptyList<TimerRecord>()) }
    var read by remember { mutableStateOf(false) }
    OpenTiming("Timer", read)
    LaunchedEffect(Unit) {
        var first = true
        while (true) {
            timers = withContext(Dispatchers.IO) { runCatching { TimerFiles.cards(TimerControl.dir()) }.getOrDefault(emptyList()) }
            read = true
            now = Instant.now()
            // after the cards show (the same cards: they are the winners among the copies), off the screen's thread: the
            // notification of a running timer, and sync's old copies cleared each time the Timer opens (TimerFiles.clean)
            if (first) { first = false; withContext(Dispatchers.IO) {
                runCatching { TimerService.check(context) }
                runCatching { TimerFiles.clean(TimerControl.dir(), TimerControl.device()) }
                    .onSuccess { if (it > 0) WriterLog.i("Timer: cleared $it old copies") }.onFailure { WriterLog.e("Timer: clean", it) }
            } }
            delay(500)
        }
    }
    var adding by remember { mutableStateOf(false) }
    fun start(seconds: Long) = scope.launch {
        if (seconds <= 0) return@launch
        val done = withContext(Dispatchers.IO) { runCatching { TimerControl.start(context, seconds) }.onFailure { WriterLog.e("Timer: could not start", it) } }
        done.exceptionOrNull()?.let { android.widget.Toast.makeText(context, it.message ?: context.getString(R.string.timer_could_not_start), android.widget.Toast.LENGTH_SHORT).show() }
    }
    fun change(r: TimerRecord, action: String) = scope.launch(Dispatchers.IO) { runCatching { TimerControl.change(context, r.id, action) }.onFailure { WriterLog.e("Timer: $action failed", it) } }

    var tab by remember { mutableStateOf(lastTab) }
    val choose = { t: Int -> tab = t; lastTab = t }
    // the cards, three to a row on a big screen, two on a phone, as many as there are
    val per = if (wide) 3 else 2
    val timersPane: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = TOP),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = if (wide) 900.dp else 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (timers.isEmpty()) Text(stringResource(R.string.timer_list_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    timers.chunked(per).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { r -> Box(Modifier.weight(1f)) { TimerCard(r, now, ::change) } }
                            repeat(per - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    Spacer(Modifier.navigationBarsPadding().height(listEnd(side)))
                }
            }
            TimerBar(0, choose, side) { IslandAction(R.drawable.ic_add, stringResource(R.string.timer_add_new)) { adding = true } }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            if (tab == 1) AlarmsPane(wide, side, choose)
            else if (tab == 2) StopwatchPane(side, choose)
            else timersPane()
            FilesPageHeader(title = stringResource(R.string.app_timer), icon = R.drawable.ic_timer, back = back, island = true, trailing = { StandButton() },
                modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp))
        }
    }
    if (adding) {
        val last = TimerControl.last(context)
        DialDialog((last / 3600).toInt().coerceAtMost(23), (last % 3600 / 60).toInt(), close = { adding = false }) { h, m -> adding = false; start(h * 3600L + m * 60L) }
    }
}

/**
 * The clock in a modal, ✓ takes the time and × closes (the user, 30 Oct: "the clock takes too much space now with the
 * island"): a new timer's length, an alarm's time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DialDialog(hour: Int, minute: Int, close: () -> Unit, done: (Int, Int) -> Unit) {
    val clock = rememberTimePickerState(hour, minute, is24Hour = true)
    // × and ✓ in the middle, for either hand (the user, 30 Oct), so not in AlertDialog's own buttons at the end
    AlertDialog(onDismissRequest = close, confirmButton = {},
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TimePicker(clock)
                CrossCheck(close) { done(clock.hour, clock.minute) }
            }
        })
}

/** × and ✓ side by side in the middle of a Clock dialog, icons without words (the user, 30 Oct). */
@Composable internal fun CrossCheck(close: () -> Unit, done: () -> Unit) =
    Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = close, modifier = Modifier.size(56.dp)) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_cancel)) }
        FilledIconButton(onClick = done, modifier = Modifier.size(56.dp)) { Icon(painterResource(R.drawable.ic_check), contentDescription = stringResource(R.string.common_save)) }
    }

/**
 * A timer's card: the time left, its buttons under it; the pin at the top left keeps the card when it is stopped or done
 * (ready to start again), the trash at the top right takes it away — on every device.
 */
@Composable private fun TimerCard(r: TimerRecord, now: Instant, change: (TimerRecord, String) -> Unit) {
    val ringing = r.ringing(now)
    val ready = r.over(now) // a pinned card waiting to start again
    val w = rememberUiWords()
    Surface(shape = MaterialTheme.shapes.extraLarge, color = if (ringing) TIMER_COLOR else MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Box {
            IconButton(onClick = { change(r, if (r.pinned) TimerService.UNPIN else TimerService.PIN) }, modifier = Modifier.align(Alignment.TopStart)) {
                Icon(painterResource(R.drawable.ic_push_pin), contentDescription = stringResource(if (r.pinned) R.string.timer_unpin else R.string.timer_pin),
                    tint = if (r.pinned) (if (ringing) Color.White else TIMER_COLOR) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            }
            IconButton(onClick = { change(r, TimerService.TRASH) }, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(when { ringing -> stringResource(R.string.timer_times_up); ready -> clockText(r.seconds * 1000); else -> clockText(r.leftMs(now)) },
                    style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace, maxLines = 1)
                Text(r.label.ifEmpty { durationText(w, r.seconds) }.let { if (r.state == TimerRecord.PAUSED) stringResource(R.string.timer_paused_suffix, it) else it },
                    style = MaterialTheme.typography.bodySmall, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    when {
                        ringing -> Button(onClick = { change(r, TimerService.OK) }, modifier = Modifier.widthIn(min = 120.dp)) { Text(stringResource(R.string.common_ok), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        ready -> FilledTonalButton(onClick = { change(r, TimerService.RESTART) }) { Icon(painterResource(R.drawable.ic_play), contentDescription = null); Text(" " + stringResource(R.string.common_start), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        r.state == TimerRecord.PAUSED -> IconButton(onClick = { change(r, TimerService.RESUME) }) { Icon(painterResource(R.drawable.ic_play), contentDescription = stringResource(R.string.common_resume)) }
                        else -> IconButton(onClick = { change(r, TimerService.PAUSE) }) { Icon(painterResource(R.drawable.ic_pause), contentDescription = stringResource(R.string.common_pause)) }
                    }
                    if (!ringing && !ready) IconButton(onClick = { change(r, TimerService.STOP) }) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_stop)) }
                }
            }
        }
    }
}
