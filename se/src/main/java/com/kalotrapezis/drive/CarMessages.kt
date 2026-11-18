package com.kalotrapezis.drive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * A message in the car (the user, 9 Nov): read aloud, «θέλεις να απαντήσεις;», the reply said and read back, «να το στείλω;»;
 * a no closes it. Its own small screen, over the lock screen as a call's, with no AI model behind it: only the words heard
 * (AI Assist's transcription) and Tetra's own SMS. Without AI Assist's key, the microphone or SMS permission it only reads.
 */
class CarSmsActivity : ComponentActivity() {
    private val step = mutableIntStateOf(0) // 0 reading, 1 a reply?, 2 the reply, 3 send it?
    private val reply = mutableStateOf("")
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.wrap(base))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WriterLog.init(this)
        TetraLook.load(this)
        getSystemService(NotificationManager::class.java).cancel(NOTICE)
        val from = intent.getStringExtra(FROM) ?: return finish()
        val name = intent.getStringExtra(NAME) ?: from
        val body = intent.getStringExtra(BODY).orEmpty()
        setContent { TetraTheme(backdrop = false) { CarSmsCard(name, body, step.intValue, reply.value) { finish() } } }
        lifecycleScope.launch { carTalk(this@CarSmsActivity, from, name, body, step, reply, music = true); finish() }
    }

    companion object {
        const val FROM = "com.kalotrapezis.drive.car.FROM"
        const val NAME = "com.kalotrapezis.drive.car.NAME"
        const val BODY = "com.kalotrapezis.drive.car.BODY"
        private const val CHANNEL = "car_sms"
        private const val NOTICE = 7301

        /** An SMS arrived (SmsDeliverReceiver): in the car, this screen; [done] when that is decided. */
        fun arrived(c: Context, from: String, body: String, done: () -> Unit) {
            if (!Car.reads(c)) return done()
            if (CarWire.alive()) { // Tetra Car on the car's screen: it reads it there, the reply comes back here (CarWire)
                Thread { CarWire.tell(c, JSONObject().put("type", "sms").put("from", from).put("name", PhoneCalls.contactFor(from)?.name ?: from).put("body", body)); done() }.start()
                return
            }
            Car.inCar(c) { yes ->
                if (!yes) return@inCar done()
                Thread {
                    runCatching {
                        val name = PhoneCalls.contactFor(from)?.name ?: from
                        val open = Intent(c, CarSmsActivity::class.java).putExtra(FROM, from).putExtra(NAME, name).putExtra(BODY, body)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        WriterLog.i("Car: a message to read aloud")
                        // Tetra Car in front: its side pane (CarScreen), the map kept; a phone in the car: as a call's screen, over the lock screen
                        val pane = Car.message
                        if (Car.running && pane != null) android.os.Handler(android.os.Looper.getMainLooper()).post { pane(CarMessage(from, name, body)) }
                        else if (Car.running) c.startActivity(open) else notice(c, name, body, open)
                    }.onFailure { WriterLog.e("Car: the message not read", it) }
                    done()
                }.start()
            }
        }

        private fun notice(c: Context, name: String, body: String, open: Intent) {
            val nm = c.getSystemService(NotificationManager::class.java)
            // through the car's own quiet (CarQuiet): Android allows it once Tetra has Do Not Disturb access
            nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.app_car), NotificationManager.IMPORTANCE_HIGH).apply { setBypassDnd(true) })
            val pi = PendingIntent.getActivity(c, NOTICE, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            nm.notify(NOTICE, NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_chat).setContentTitle(name).setContentText(body)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE).setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(pi)
                .setFullScreenIntent(pi, true).setAutoCancel(true).setTimeoutAfter(60_000).build())
        }
    }
}

/** [remote]: arrived on the paired phone (CarWire), and its reply is sent there. */
internal data class CarMessage(val from: String, val name: String, val body: String, val remote: Boolean = false)

/**
 * The talk about a message (CarSmsActivity, or Tetra Car's side pane): read aloud, «θέλεις να απαντήσεις;», the reply heard and
 * read back, «να το στείλω;», sent on a yes; anything else ends it. [music]: the Browser's sound paused here, and played after.
 */
internal suspend fun carTalk(c: Context, from: String, name: String, body: String, step: androidx.compose.runtime.MutableIntState,
                             reply: androidx.compose.runtime.MutableState<String>, music: Boolean, remote: Boolean = false) {
    val audio = c.getSystemService(AudioManager::class.java)
    val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).build()
    val paused = music && BrowserMedia.control != null && BrowserMedia.playing // the user, 9 Nov: "stop the music on call or message"
    if (paused) BrowserMedia.act("pause")
    audio.requestAudioFocus(focus)
    suspend fun heard(waitMs: Long): String? = withContext(Dispatchers.IO) {
        val pcm = runCatching { listen(waitMs, { false }, {}, AssistantPause.ms(c), AssistantAudio.mic(c)) }.getOrNull() ?: return@withContext null
        runCatching { AssistantNet.transcribe(c, pcm, AssistantWords.terms(c)) }.onFailure { WriterLog.w("Car: a reply not heard", it) }.getOrNull()
    }
    try {
        CarSpeech.say(c, c.getString(R.string.car_sms_said, name, body))
        val key = runCatching { AssistantNet.key(c, AiProviders.of(c, AiPart.LISTEN)) }.getOrNull()
        if (key == null || !hasMic(c) || !(remote || SmsBox.canSend(c))) { WriterLog.i("Car: a message read, no reply asked (key, microphone or SMS)"); return }
        step.intValue = 1
        if (AssistantChat.yesNo(heard(6_000) ?: return) != true) return
        step.intValue = 2
        CarSpeech.say(c, c.getString(R.string.car_sms_say_it))
        val text = heard(20_000)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        reply.value = text; step.intValue = 3
        CarSpeech.say(c, c.getString(R.string.car_sms_send_it, text))
        if (AssistantChat.yesNo(heard(6_000) ?: return) == true) {
            val sent = if (!remote) { SmsBox.send(c, from, text); true }
                else withContext(Dispatchers.IO) { !CarWire.ask(c, JSONObject().put("sms", JSONObject().put("to", from).put("text", text))).has("error") }
            if (sent) CarSpeech.say(c, c.getString(R.string.car_sms_sent)) else WriterLog.w("Car: the reply did not reach the phone")
        }
    } finally {
        audio.abandonAudioFocusRequest(focus)
        if (paused) BrowserMedia.act("play")
    }
}

/** A message in Tetra Car's side pane: the talk, then [done] gives the pane back to its page. */
@Composable
internal fun CarSmsPane(m: CarMessage, done: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val step = androidx.compose.runtime.remember(m) { androidx.compose.runtime.mutableIntStateOf(0) }
    val reply = androidx.compose.runtime.remember(m) { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(m) { carTalk(context, m.from, m.name, m.body, step, reply, music = false, remote = m.remote); done() }
    CarSmsCard(m.name, m.body, step.intValue, reply.value, dim = false, close = done)
}

@Composable
private fun CarSmsCard(name: String, body: String, step: Int, reply: String, dim: Boolean = true, close: () -> Unit) {
    Box(Modifier.fillMaxSize().background(if (dim) Color.Black.copy(alpha = 0.4f) else MaterialTheme.colorScheme.background),
        contentAlignment = if (dim) Alignment.BottomCenter else Alignment.Center) {
        Column(Modifier.padding(16.dp).widthIn(max = 640.dp).fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_chat), null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                Text(name, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleLarge, maxLines = 1)
                IconButton(close) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.common_close)) }
            }
            Text(body, fontSize = 20.sp)
            if (reply.isNotEmpty()) Text("«$reply»", fontSize = 20.sp, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.primary)
            if (step > 0) Box(Modifier.align(Alignment.CenterHorizontally).size(56.dp).background(ASSISTANT_COLOR, RoundedCornerShape(28.dp)), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_mic), null, Modifier.size(30.dp), tint = Color.White) // listening
            }
        }
    }
}

/** Android's own voice for the car (as Maps' directions): a line said, the music lowered by the caller, waited for to its end. */
internal object CarSpeech {
    private var tts: TextToSpeech? = null
    private var ready = CompletableDeferred<Boolean>()
    private val done = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    @Synchronized private fun start(c: Context) {
        if (tts != null) return
        ready = CompletableDeferred()
        val r = ready
        tts = TextToSpeech(c.applicationContext) { ok -> r.complete(ok == TextToSpeech.SUCCESS) }.apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) {}
                override fun onDone(id: String) { done.remove(id)?.complete(Unit) }
                @Deprecated("Android's own") override fun onError(id: String) { done.remove(id)?.complete(Unit) }
            })
        }
    }

    suspend fun say(c: Context, text: String) {
        start(c)
        if (!ready.await()) { WriterLog.w("Car: no voice on this device"); return }
        val t = tts ?: return
        t.language = AppLocale.wrap(c).resources.configuration.locales[0]
        val id = java.util.UUID.randomUUID().toString()
        val d = CompletableDeferred<Unit>().also { done[id] = it }
        t.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        withTimeoutOrNull(60_000) { d.await() }
    }
}

/**
 * Quiet in the car (the user, 9 Nov: "when in car mode we should block notifications and take over"): Android's Do Not Disturb as
 * a driving rule of Tetra's own, so the user's own DND settings stay as they are. Calls (Tetra answers them), repeat callers and
 * alarms come through, and Tetra's car channel (a message read aloud); other apps' notifications wait. On while Tetra Car is open
 * or the phone is on a car's Bluetooth, off after. Needs Android's "Do Not Disturb access", asked once in Settings › Tetra Car.
 */
internal object CarQuiet {
    private const val RULE = "car_quiet_rule"
    private val CONDITION = android.net.Uri.parse("condition://com.kalotrapezis.drive/car")
    private fun prefs(c: Context) = c.getSharedPreferences("car", Context.MODE_PRIVATE)
    fun wanted(c: Context) = prefs(c).getBoolean("quiet", true)
    fun allowed(c: Context) = c.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
    fun ask(c: Context) = c.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    fun want(c: Context, on: Boolean) { prefs(c).edit().putBoolean("quiet", on).apply(); if (!on) set(c, false) }

    /** The rule on or off; nothing without the access or when the user switched it off. */
    @Synchronized fun set(c: Context, on: Boolean) = runCatching {
        if (!allowed(c) || (on && !wanted(c))) return@runCatching
        val nm = c.getSystemService(NotificationManager::class.java)
        // media through (the user, 10 Nov, the car's first drive: "no sound" — Do Not Disturb mutes media unless it is let through:
        // the music, the route's voice and AI Assist went silent on the car's speakers); calls and alarms as before
        val policy = android.service.notification.ZenPolicy.Builder().disallowAllSounds().hideAllVisualEffects().allowMedia(true)
            .allowCalls(android.service.notification.ZenPolicy.PEOPLE_TYPE_ANYONE).allowRepeatCallers(true).allowAlarms(true).build()
        val car = android.content.ComponentName(c, CarActivity::class.java)
        val rule = if (android.os.Build.VERSION.SDK_INT >= 35) // Android's own driving rule, shown as such in its settings
            android.app.AutomaticZenRule.Builder(c.getString(R.string.app_car), CONDITION).setType(android.app.AutomaticZenRule.TYPE_DRIVING)
                .setConfigurationActivity(car).setZenPolicy(policy).setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY).setEnabled(true).build()
        else android.app.AutomaticZenRule(c.getString(R.string.app_car), null, car, CONDITION, policy, NotificationManager.INTERRUPTION_FILTER_PRIORITY, true)
        val kept = prefs(c).getString(RULE, null)?.takeIf { nm.getAutomaticZenRule(it) != null }
        if (kept != null && on && nm.getAutomaticZenRule(kept).zenPolicy?.priorityCategoryMedia != android.service.notification.ZenPolicy.STATE_ALLOW)
            runCatching { nm.updateAutomaticZenRule(kept, rule); WriterLog.i("Car: quiet lets media through now") }.onFailure { WriterLog.w("Car: quiet not updated", it) }
        val id = kept ?: run {
            if (!on) return@runCatching
            nm.addAutomaticZenRule(rule).also { prefs(c).edit().putString(RULE, it).apply() }
        }
        nm.setAutomaticZenRuleState(id, android.service.notification.Condition(CONDITION, c.getString(R.string.app_car),
            if (on) android.service.notification.Condition.STATE_TRUE else android.service.notification.Condition.STATE_FALSE))
        WriterLog.i("Car: quiet ${if (on) "on" else "off"}")
    }.onFailure { WriterLog.w("Car: quiet not set", it) }
}

/** The phone joining or leaving a car's Bluetooth: quiet on or off (Android lets these two through to a sleeping app). */
class CarBluetoothReceiver : android.content.BroadcastReceiver() {
    @android.annotation.SuppressLint("MissingPermission") // a class read without the permission is null: not a car
    override fun onReceive(c: Context, intent: Intent) {
        val device = if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE, android.bluetooth.BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
        val car = runCatching { Car.isCar(c, device?.address) }.getOrDefault(false)
        if (!car || Car.running) return
        val on = intent.action == android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED
        WriterLog.i("Car: the ${if (PhoneRelay.noCalls(c)) "phone" else "car"}'s Bluetooth ${if (on) "joined" else "left"}")
        CarQuiet.set(c, on)
        if (on) AutoProjection.startIfPaired(c) // a car with Tetra Auto: ready for it as its Bluetooth joins
        if (on && Car.opensOnBluetooth(c)) Car.open(c) // the user, 9 Nov: "open Tetra Car … on connection with phone"
    }
}

/** The device started (a head unit with the ignition): Tetra Car opened, when asked to (Settings › Tetra Car). */
class CarBootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (Car.opensOnBoot(c) && !Car.running) { WriterLog.init(c); WriterLog.i("Car: opened at start"); Car.open(c) }
    }
}
