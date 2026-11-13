package com.kalotrapezis.tetra.auto

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject

/** The pages that open beside the map (the map stays, large). */
internal enum class Page { Media, Phone, Messages }

/**
 * Tetra Car on the unit (as Tetra Car on the phone, the user's choices of 9–13 Nov): the dock down a side with the clock on top
 * (a tap: the recent messages), the map large behind everything, a page beside it, and ☰ — Android Auto's launcher in Tetra's
 * rounded squares, Settings among them.
 */
@Composable
internal fun CarScreen(link: Link, share: Share) {
    val v by CarView.state
    var page by remember { mutableStateOf<Page?>(null) }
    var menu by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    LaunchedEffect(link.shareAsked) { if (link.shareAsked > 0) sharing = true }
    LaunchedEffect(share.newApp) { if (share.newApp != null) sharing = true }
    val call by PhoneLink.call
    LaunchedEffect(call) { if (call != null && call?.optString("state") != "ended") page = Page.Phone } // a call takes the page beside the map
    BackHandler(page != null || menu || settings) { when { settings -> settings = false; menu -> menu = false; else -> page = null } }
    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(base.density * v.ui, base.fontScale)) { // the interface size
        Row(Modifier.fillMaxSize().background(Color.Black)) {
            val dock = @Composable {
                Dock(link, page, open = { p -> menu = false; page = if (page == p) null else p }, maps = { menu = false; page = null }, menu = { menu = !menu })
            }
            if (!v.dockRight) dock()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                // the map's own size, apart from the interface's
                CompositionLocalProvider(LocalDensity provides base) { MapPage(share.maps, share.mapsChanged, if (page == null) 0f else if (v.dockRight) -0.42f else 0.42f, Modifier.fillMaxSize()) }
                page?.let { p ->
                    Surface(Modifier.align(if (v.dockRight) Alignment.CenterStart else Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.42f).padding(8.dp),
                        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                        when (p) { Page.Media -> MediaPage(); Page.Phone -> PhonePage(); Page.Messages -> MessagesPage() }
                    }
                }
                if (link.state is LinkState.Looking && (link.state as LinkState.Looking).pairCode != null && !pairing) Banner(stringResource(R.string.se_not_paired), Modifier.align(Alignment.TopCenter)) { pairing = true }
                else if (link.connected && !link.standalone) Banner(stringResource(R.string.se_update_phone), Modifier.align(Alignment.TopCenter)) {}
                link.notice?.let { n -> Banner(n, Modifier.align(Alignment.TopCenter)) { link.notice = null } }
                Arrived(Modifier.align(Alignment.TopCenter))
                AssistCard(Modifier.align(Alignment.BottomCenter))
                if (menu) Menu(link, open = { p -> menu = false; page = p }, settings = { settings = true }, pair = { menu = false; pairing = true },
                    share = { menu = false; sharing = true }, close = { menu = false })
                if (settings) Settings { settings = false }
                share.said?.let { s ->
                    LaunchedEffect(s) { delay(4000); if (share.said == s) share.said = null }
                    Surface(Modifier.align(Alignment.BottomCenter).padding(24.dp), shape = RoundedCornerShape(24.dp), color = Color(0xEE222222), contentColor = Color.White) {
                        Text(s, Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                    }
                }
            }
            if (v.dockRight) dock()
        }
        if (pairing) Box(Modifier.fillMaxSize().background(Color.Black)) {
            BackHandler { pairing = false }
            when (val s = link.state) {
                is LinkState.Looking -> Looking(s, link) { pairing = false; sharing = true }
                is LinkState.Connected -> LaunchedEffect(Unit) { pairing = false }
            }
            IconButton({ pairing = false }, Modifier.align(Alignment.TopEnd).padding(8.dp)) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close), tint = Color.White) }
        }
        if (sharing) SharePage(link, share) { sharing = false; share.newApp = null }
    }
}

@Composable
private fun Banner(text: String, modifier: Modifier, tap: () -> Unit) =
    Surface(modifier.padding(12.dp).clickable(onClick = tap), shape = RoundedCornerShape(16.dp), color = Color(0xF0302010), contentColor = Color.White) {
        Text(text, Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
    }

/** The dock: the clock, Maps, Media, Phone, Messages, AI Assist's mic, ☰ (the user: "the dock down a side"). */
@Composable
private fun Dock(link: Link, page: Page?, open: (Page) -> Unit, maps: () -> Unit, menu: () -> Unit) {
    val context = LocalContext.current
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(60_000 - value % 60_000) } }
    Column(Modifier.fillMaxHeight().width(92.dp).background(Color(0xFF111316)).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // the clock opens the recent messages (the user, 13 Nov); the dot: the phone there or not
            Text(android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(now)), Modifier.clip(RoundedCornerShape(8.dp)).clickable { open(Page.Messages) }.padding(6.dp),
                fontSize = 22.sp, color = Color.White, maxLines = 1)
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (link.state is LinkState.Connected) Color(0xFF43A047) else Color(0xFF616161)))
        }
        DockArt(R.drawable.maps_art, R.string.se_maps, page == null, maps)
        DockIcon(R.drawable.ic_play, R.string.se_media, Color(0xFFE53935), page == Page.Media) { open(Page.Media) }
        DockArt(R.drawable.phone_art, R.string.se_phone, page == Page.Phone) { open(Page.Phone) }
        DockArt(R.drawable.sms_art, R.string.se_messages, page == Page.Messages) { open(Page.Messages) }
        DockIcon(R.drawable.ic_mic, R.string.se_assist, Color(0xFF7E57C2), false) { Assist.listen(context) }
        DockIcon(R.drawable.ic_apps, R.string.se_menu, Color(0xFF455A64), false, menu)
    }
}

@Composable
private fun DockArt(art: Int, label: Int, shown: Boolean, tap: () -> Unit) = DockButton(label, shown, tap) { Image(painterResource(art), stringResource(label), Modifier.size(52.dp)) }

@Composable
private fun DockIcon(icon: Int, label: Int, color: Color, shown: Boolean, tap: () -> Unit) = DockButton(label, shown, tap) {
    Box(Modifier.size(52.dp).background(color), contentAlignment = Alignment.Center) { Icon(painterResource(icon), stringResource(label), Modifier.size(28.dp), tint = Color.White) }
}

/** Tetra's rounded square, never a circle (the user, 13 Nov). */
@Composable
private fun DockButton(label: Int, shown: Boolean, tap: () -> Unit, art: @Composable () -> Unit) =
    Box(Modifier.clip(RoundedCornerShape(16.dp)).background(if (shown) Color(0xFF2A2F36) else Color.Transparent).clickable(onClick = tap).padding(6.dp)) {
        Box(Modifier.clip(RoundedCornerShape(14.dp))) { art() }
    }

/** ☰: Android Auto's launcher, an icon and its name four to a row in Tetra's rounded squares; Settings among them. */
@Composable
private fun Menu(link: Link, open: (Page) -> Unit, settings: () -> Unit, pair: () -> Unit, share: () -> Unit, close: () -> Unit) {
    @Composable fun tile(label: String, tap: () -> Unit, modifier: Modifier, art: @Composable () -> Unit) {
        Column(modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = tap).padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(80.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF2A2F36)), contentAlignment = Alignment.Center) { art() }
            Text(label, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    @Composable fun icon(icon: Int, color: Color) = Box(Modifier.size(80.dp).background(color), contentAlignment = Alignment.Center) { Icon(painterResource(icon), null, Modifier.size(40.dp), tint = Color.White) }
    Surface(Modifier.fillMaxSize().clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) {}, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.app_name), Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.headlineSmall)
                IconButton(close) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close)) }
            }
            FlowRow(maxItemsInEachRow = 4) {
                val w = Modifier.weight(1f)
                tile(stringResource(R.string.se_maps), close, w) { Image(painterResource(R.drawable.maps_art), null, Modifier.size(80.dp)) }
                tile(stringResource(R.string.se_media), { open(Page.Media) }, w) { icon(R.drawable.ic_play, Color(0xFFE53935)) }
                tile(stringResource(R.string.se_phone), { open(Page.Phone) }, w) { Image(painterResource(R.drawable.phone_art), null, Modifier.size(80.dp)) }
                tile(stringResource(R.string.se_messages), { open(Page.Messages) }, w) { Image(painterResource(R.drawable.sms_art), null, Modifier.size(80.dp)) }
                tile(stringResource(R.string.share_title), share, w) { icon(R.drawable.ic_share, Color(0xFF00897B)) }
                tile((link.state as? LinkState.Connected)?.phone ?: stringResource(R.string.se_pair), pair, w) { icon(R.drawable.ic_qr_code, Color(0xFF5E35B1)) }
                tile(stringResource(R.string.se_settings), settings, w) { icon(R.drawable.ic_settings, Color(0xFF1A73E8)) }
                Spacer(w)
            }
        }
    }
}

/** Settings: the map's size, zoom, tilt, where the arrow sits, the interface's size, the dock's side, where the position comes from. */
@Composable
private fun Settings(close: () -> Unit) {
    val context = LocalContext.current
    val v by CarView.state
    val gps by Fixes.source
    var reads by remember { mutableStateOf(PhoneLink.readAloud) }
    fun pct(f: Float) = "${(f * 100).toInt()} %"
    @Composable fun <T> row(label: Int, options: List<T>, chosen: T, name: @Composable (T) -> String, pick: (T) -> Unit) {
        Text(stringResource(label), Modifier.padding(top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o -> FilterChip(o == chosen, { pick(o) }, { Text(name(o), style = MaterialTheme.typography.titleMedium) }) }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = close), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(16.dp).widthIn(max = 640.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surface)
            .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) {}.verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.se_settings), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                IconButton(close) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close)) }
            }
            row(R.string.se_map_size, CarView.MAP, v.map, { pct(it) }) { CarView.set(context, v.copy(map = it)) }
            row(R.string.se_map_zoom, CarView.ZOOM, v.zoom, { if (it == 0f) "0" else "%+.1f".format(it) }) { CarView.set(context, v.copy(zoom = it)) }
            row(R.string.se_map_tilt, CarView.TILT, v.tilt, { if (it < 0) stringResource(R.string.se_auto) else "$it°" }) { CarView.set(context, v.copy(tilt = it)) }
            row(R.string.se_arrow, listOf(true, false), v.low, { stringResource(if (it) R.string.se_arrow_low else R.string.se_arrow_middle) }) { CarView.set(context, v.copy(low = it)) }
            row(R.string.se_ui_size, CarView.UI, v.ui, { pct(it) }) { CarView.set(context, v.copy(ui = it)) }
            row(R.string.se_dock, listOf(false, true), v.dockRight, { stringResource(if (it) R.string.se_dock_right else R.string.se_dock_left) }) { CarView.set(context, v.copy(dockRight = it)) }
            row(R.string.se_location, GpsSource.entries, gps, { stringResource(when (it) { GpsSource.Best -> R.string.se_gps_best; GpsSource.Car -> R.string.se_gps_car; GpsSource.Phone -> R.string.se_gps_phone }) }) { Fixes.setSource(context, it) }
            row(R.string.se_read_aloud, listOf(true, false), reads, { stringResource(if (it) R.string.se_on else R.string.se_off) }) { reads = it; PhoneLink.readAloud = it }
        }
    }
}

/** A message just come: over the map, read aloud already; Reply offers a few ready answers; gone after 20 s. */
@Composable
private fun Arrived(modifier: Modifier) {
    val m = PhoneLink.arrived.value ?: return
    var replying by remember(m) { mutableStateOf(false) }
    LaunchedEffect(m, replying) { if (!replying) { delay(20_000); if (PhoneLink.arrived.value === m) PhoneLink.arrived.value = null } }
    Surface(modifier.padding(12.dp).widthIn(max = 560.dp), shape = RoundedCornerShape(20.dp), color = Color(0xF0263238), contentColor = Color.White) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.sms_art), null, Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)))
                Text(m.name, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton({ PhoneLink.arrived.value = null }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close)) }
            }
            Text(m.body, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
            if (!replying) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton({ Speech.say("${m.name}: ${m.body}") }) { Text(stringResource(R.string.se_read)) }
                if (m.from.isNotBlank()) FilledTonalButton({ replying = true }) { Text(stringResource(R.string.se_reply)) }
            } else QuickReplies(m) { PhoneLink.arrived.value = null }
        }
    }
}

/** Ready answers for a message, sent by the phone. */
@Composable
private fun QuickReplies(m: Message, sent: () -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(R.string.se_reply_driving, R.string.se_reply_way, R.string.se_reply_ok).forEach { r ->
            val text = stringResource(r)
            FilledTonalButton({ PhoneLink.sms(m.from, text) { a -> if (a.has("error")) android.widget.Toast.makeText(context, a.optString("error"), android.widget.Toast.LENGTH_LONG).show() }; sent() },
                Modifier.fillMaxWidth()) { Text(text) }
        }
    }
}

/** Media: the radio, its own sound out of the car's speakers. */
@Composable
private fun MediaPage() {
    val now by Radio.now
    val playing by Radio.playing
    val title by Radio.title
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.se_media), style = MaterialTheme.typography.headlineSmall)
        now?.let { s ->
            Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF3A1F1F)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Surface(onClick = Radio::toggle, modifier = Modifier.size(64.dp), shape = RoundedCornerShape(18.dp), color = Color(0xFFE53935)) {
                        Box(contentAlignment = Alignment.Center) { Icon(painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play), null, Modifier.size(36.dp), tint = Color.White) }
                    }
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(Radio.stations, key = { it.url }) { s ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (s == now) Color(0xFF2A2F36) else Color.Transparent).clickable { Radio.play(s) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_play), null, Modifier.padding(end = 12.dp))
                    Text(s.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Phone: the call now (answer, hang up; its voice is the phone's and the unit's Bluetooth hands-free), and the recent calls to call back. */
@Composable
private fun PhonePage() {
    val call by PhoneLink.call
    var recent by remember { mutableStateOf<List<JSONObject>?>(null) }
    LaunchedEffect(Unit) { PhoneLink.recentCalls { recent = it } }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.se_phone), style = MaterialTheme.typography.headlineSmall)
        call?.let { c ->
            Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF1F3A2B)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(c.optString("name").ifBlank { c.optString("number") }, style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(when (c.optString("state")) { "ringing" -> R.string.se_call_ringing; "active" -> R.string.se_call_active; "ended" -> R.string.se_call_ended; else -> R.string.se_call_calling }))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (c.optString("state") == "ringing") RoundAction(R.drawable.ic_call, Color(0xFF43A047)) { PhoneLink.callDo("answer") }
                        if (c.optString("state") != "ended") RoundAction(R.drawable.ic_call_end, Color(0xFFE53935)) { PhoneLink.callDo(if (c.optString("state") == "ringing") "decline" else "end") }
                    }
                }
            }
        }
        when (val r = recent) {
            null -> Text(stringResource(R.string.se_asking_phone), color = Color(0xFFAAAAAA))
            else -> if (r.isEmpty()) Text(stringResource(R.string.se_no_calls), color = Color(0xFFAAAAAA)) else LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(r) { o ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { PhoneLink.place(o.optString("number")) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(when (o.optString("type")) { "missed" -> R.drawable.ic_call_missed; "out" -> R.drawable.ic_call_made; else -> R.drawable.ic_call_received }), null,
                            Modifier.padding(end = 12.dp), tint = if (o.optString("type") == "missed") Color(0xFFE57373) else Color.White)
                        Text(o.optString("name").ifBlank { o.optString("number") }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(painterResource(R.drawable.ic_call), null)
                    }
                }
            }
        }
    }
}

/** A call's button: a rounded square, as everything in Tetra Car. */
@Composable
private fun RoundAction(icon: Int, color: Color, tap: () -> Unit) = Surface(onClick = tap, modifier = Modifier.size(64.dp), shape = RoundedCornerShape(18.dp), color = color) {
    Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), null, Modifier.size(32.dp), tint = Color.White) }
}

/** Messages: what came while Tetra Car was open, newest first; a tap reads it aloud, Reply offers the ready answers. */
@Composable
private fun MessagesPage() {
    var open by remember { mutableStateOf<Message?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.se_messages), style = MaterialTheme.typography.headlineSmall)
        if (PhoneLink.messages.isEmpty()) Text(stringResource(R.string.se_no_messages), color = Color(0xFFAAAAAA))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(PhoneLink.messages) { m ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF22262B)).clickable { open = if (open == m) null else m; Speech.say("${m.name}: ${m.body}") }.padding(12.dp)) {
                    Row {
                        Text(m.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(android.text.format.DateFormat.getTimeFormat(LocalContext.current).format(java.util.Date(m.at)), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(m.body, maxLines = if (open == m) 10 else 2, overflow = TextOverflow.Ellipsis)
                    if (open == m && m.from.isNotBlank()) QuickReplies(m) { open = null }
                }
            }
        }
    }
}

/** AI Assist at work, then its answer (gone after 15 s or a tap). */
@Composable
private fun AssistCard(modifier: Modifier) {
    val phase by Assist.phase
    val answer by Assist.answer
    if (phase == Assist.Phase.Idle && answer == null) return
    LaunchedEffect(answer) { if (answer != null) { delay(15_000); Assist.answer.value = null } }
    Surface(modifier.padding(16.dp).widthIn(max = 640.dp).clickable { Assist.answer.value = null }, shape = RoundedCornerShape(20.dp), color = Color(0xF0311B92), contentColor = Color.White) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.ic_mic), null, Modifier.size(32.dp))
            Text(when (phase) { Assist.Phase.Listening -> stringResource(R.string.se_assist_listening); Assist.Phase.Thinking -> stringResource(R.string.se_assist_thinking); else -> answer.orEmpty() },
                style = MaterialTheme.typography.titleMedium, maxLines = 5, overflow = TextOverflow.Ellipsis)
        }
    }
}
