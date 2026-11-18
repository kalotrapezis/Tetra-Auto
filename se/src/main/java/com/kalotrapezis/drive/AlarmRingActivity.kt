package com.kalotrapezis.drive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * An alarm ringing, over the lock screen as a clock's (Plan.md § Timer › Alarms): the time, the label, zzz Snooze and × Stop.
 * [TimerService] rings it; this only shows it, and closes once no alarm rings (answered here, on another device, or ten
 * minutes gone by).
 */
class AlarmRingActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AppLocale.wrap(base))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WriterLog.init(this)
        setShowWhenLocked(true); setTurnScreenOn(true)
        TetraLook.load(this)
        setContent { TetraTheme {
            var ringing by remember { mutableStateOf<Pair<AlarmRecord, Instant>?>(null) }
            LaunchedEffect(Unit) {
                while (true) {
                    ringing = withContext(Dispatchers.IO) { runCatching { TimerAlarmFiles.ringing(TimerControl.dir(), Instant.now(), ZoneId.systemDefault()).firstOrNull() }.getOrNull() }
                    if (ringing == null) { finish(); break }
                    delay(500)
                }
            }
            fun answer(action: String) { val id = ringing?.first?.id ?: return; Thread { runCatching { TimerControl.alarmChange(this, id, action) }; runOnUiThread { finish() } }.start() }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                ringing?.let { (a, at) ->
                    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)) {
                        Text(hhmm(this@AlarmRingActivity, at), fontSize = 72.sp, fontFamily = FontFamily.Monospace)
                        Text(a.label.ifEmpty { stringResource(R.string.alarm_named) }, style = MaterialTheme.typography.headlineSmall)
                        // zzz and ×, icons only, as the notification's (the user, 1 Nov)
                        Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                            IconButton(onClick = { answer(TimerService.ALARM_SNOOZE) }, modifier = Modifier.size(96.dp)) {
                                Icon(painterResource(R.drawable.ic_timer_snooze), stringResource(R.string.calendar_snooze), Modifier.size(96.dp), tint = Color.Unspecified) }
                            IconButton(onClick = { answer(TimerService.ALARM_DISMISS) }, modifier = Modifier.size(96.dp)) {
                                Icon(painterResource(R.drawable.ic_timer_stop), stringResource(R.string.common_stop), Modifier.size(96.dp), tint = Color.Unspecified) }
                        }
                    }
                }
            }
        } }
    }
}
