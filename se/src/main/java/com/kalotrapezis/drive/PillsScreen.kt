package com.kalotrapezis.drive

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

internal val PILLS_COLOR = Color(0xFFE5487A)
private val MISSED_RED = Color(0xFFE24B4A)
internal const val PILLS_SETTINGS = "settings-pills" // Settings › Pills (MainActivity), from the applet's banner and its Medicines tab

/**
 * Pills (Pills.kt), as Tetra's other applets (the user, 7 Nov): Today, Medicines and Progress in the island at the bottom, +
 * beside it; its settings in Settings › Pills, no gear here. Today is a card for each part of the day, each pill drawn in
 * its shape and colour (or its photo); Add is a step-by-step guide that AI Assist can fill from one sentence (AI on only).
 */
@Composable
internal fun PillsScreen(back: () -> Unit, hasAccess: Boolean, grant: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler { back() }
    if (!hasAccess) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_back)) }
            Text(stringResource(R.string.home_files_access_needed, stringResource(R.string.app_pills)))
            TextButton(onClick = grant) { Text(stringResource(R.string.timer_allow_access)) }
        }
        return
    }
    val dir = remember { Pills.phoneDir() }
    val zone = remember { ZoneId.systemDefault() }
    var meds by remember { mutableStateOf(emptyList<Medicine>()) }
    var log by remember { mutableStateOf(emptyList<PillEvent>()) }
    var now by remember { mutableStateOf(Instant.now()) }
    var read by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    OpenTiming("Pills", read)
    val outside = PillsAlarms.changed.intValue
    LaunchedEffect(version, outside) {
        withContext(Dispatchers.IO) { meds = Pills.medicines(dir); log = Pills.log(dir) }
        now = Instant.now(); read = true
    }
    // a reminder needs notifications: asked once, as the Calendar does (Android 13 and later)
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { version++ }
    LaunchedEffect(Unit) {
        val prefs = PillsAlarms.prefs(context)
        if (android.os.Build.VERSION.SDK_INT >= 33 && !androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() && !prefs.getBoolean("askedNotifications", false)) {
            prefs.edit().putBoolean("askedNotifications", true).apply(); askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(Unit) { PillsAlarms.planSoon(context); while (true) { delay(60_000); now = Instant.now() } } // a dose turns missed by itself
    fun reload() { version++; PillsAlarms.planSoon(context); ScopedSync.soon(context, "pills", orWhole = true) } // to the tablet and the computer at once
    fun act(block: () -> Unit) = scope.launch { withContext(Dispatchers.IO) { runCatching(block).onFailure { WriterLog.e("Pills: a change failed", it) } }; reload() }
    fun openSettings() = context.startActivity(Intent(context, MainActivity::class.java).putExtra(Applet.EXTRA, PILLS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))

    var tab by remember { mutableIntStateOf(0) }
    var day by remember { mutableStateOf(now.atZone(zone).toLocalDate()) }
    var editing by remember { mutableStateOf<Medicine?>(null) }
    var shown by remember { mutableStateOf<String?>(null) }
    var undo by remember { mutableStateOf<Dose?>(null) }
    LaunchedEffect(undo) { if (undo != null) { delay(6000); undo = null } }
    val health = remember(version) { pillsHealth(context) }
    val wide = LocalConfiguration.current.screenWidthDp >= 700
    val doses = DoseActions(
        take = { d -> act { Pills.take(dir, d.med, d.due, Instant.now()) }; undo = d },
        skip = { d -> act { Pills.skip(dir, d.med, d.due, Instant.now()) }; undo = d },
        back = { d -> act { Pills.undo(dir, d.med, d.due, Instant.now()) } })

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = TOP),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = if (wide) 900.dp else 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (health.any { !it.ok && it.title != R.string.pills_check_battery } && meds.isNotEmpty())
                        Surface(onClick = ::openSettings, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                            Text(stringResource(R.string.pills_health_banner), Modifier.padding(14.dp).fillMaxWidth())
                        }
                    if (read && meds.none { !it.archived } && tab != 1) EmptyPills { editing = newMedicine() }
                    else when (tab) {
                        0 -> TodayPane(meds, log, dir, now, zone, day, { day = it }, wide, doses)
                        1 -> MedicinesPane(meds, dir, now, zone, wide, open = { shown = it.id }, addBox = { m -> act { Pills.addStock(dir, m, 1, 0.0, null, Instant.now()) } }, settings = ::openSettings)
                        else -> ProgressPane(meds, log, dir, now, zone) { m, t -> act { Pills.save(dir, m.copy(times = m.times.map { if (it == t.first) t.second else it }.distinct().sorted())) } }
                    }
                    Spacer(Modifier.navigationBarsPadding().height(BOTTOM + 24.dp))
                }
            }
            // the island at the bottom, as the Timer's: the three tabs, and + for a new medicine
            Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                undo?.let { d ->
                    Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                        Row(Modifier.padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.pills_answered, d.med.name), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
                            TextButton(onClick = { act { Pills.undo(dir, d.med, d.due, Instant.now()) }; undo = null }) { Text(stringResource(R.string.pills_undo)) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = MaterialTheme.shapes.extraLarge, color = islandColor(), contentColor = islandContentColor()) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(R.string.pills_today to R.drawable.ic_calendar_today, R.string.pills_medicines to R.drawable.ic_pill, R.string.pills_progress to R.drawable.ic_bar_chart)
                                .forEachIndexed { i, (label, icon) -> IslandNavigationItem(stringResource(label), i == tab, icon) { tab = i } }
                        }
                    }
                    IslandAction(R.drawable.ic_add, stringResource(R.string.pills_new)) { editing = newMedicine() }
                }
            }
            FilesPageHeader(title = stringResource(R.string.app_pills), icon = R.drawable.ic_pill, back = back, island = true, modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp))
        }
    }
    shown?.let { id -> meds.firstOrNull { it.id == id }?.let { m ->
        MedicineSheet(m, dir, log, now, zone, close = { shown = null }, edit = { shown = null; editing = m },
            addBox = { n -> act { Pills.addStock(dir, m, n, 0.0, null, Instant.now()) } },
            setStock = { v -> act { Pills.setStock(dir, m, v, Instant.now()) } },
            takeNow = { act { Pills.take(dir, m, null, Instant.now()) } },
            archive = { act { Pills.save(dir, m.copy(archived = !m.archived)) } },
            delete = { shown = null; act { Pills.delete(dir, m) } })
    } }
    editing?.let { m -> MedicineGuide(m, dir, fresh = meds.none { it.id == m.id }, close = { editing = null }) { saved, boxes, loose ->
        editing = null
        act {
            val fresh = meds.none { it.id == saved.id }
            val kept = Pills.save(dir, if (fresh) saved.copy(since = Instant.now()) else saved)
            if (fresh && (boxes > 0 || loose > 0)) Pills.addStock(dir, kept.copy(stock = 0.0), boxes, loose, null, Instant.now(), "first")
            WriterLog.i("Pills: ${if (fresh) "added" else "edited"} ${saved.id}, ${saved.times.size} time(s)")
        }
    } }
}

private fun newMedicine() = Medicine(Pills.newId(), "", times = listOf(LocalTime.of(21, 0)))

private class DoseActions(val take: (Dose) -> Unit, val skip: (Dose) -> Unit, val back: (Dose) -> Unit)

/** No medicine yet: an invitation, the pill drawn big, a button under it. */
@Composable private fun EmptyPills(add: () -> Unit) =
    Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PillShape("capsule", Medicine.WHITE, Medicine.COLORS[3], 96.dp)
        Text(stringResource(R.string.pills_empty), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = add, colors = ButtonDefaults.buttonColors(containerColor = PILLS_COLOR, contentColor = Color.White)) { Text(stringResource(R.string.pills_new)) }
    }

// ——— the pill as an emblem ———

/** A pill drawn: round with its score, oval, a capsule in two colours, a little bottle for a liquid. */
@Composable internal fun PillShape(shape: String, color: Int, color2: Int, size: Dp, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.outline
    Canvas(modifier.size(size)) { drawPill(shape, Color(color), Color(color2), outline) }
}

private fun DrawScope.drawPill(shape: String, c1: Color, c2: Color, outline: Color) {
    val w = size.width; val h = size.height; val line = Stroke(w * 0.035f)
    fun shade(c: Color) = Color(c.red * 0.78f, c.green * 0.78f, c.blue * 0.78f, 1f)
    when (shape) {
        "oval" -> { val s = Size(w * 0.86f, h * 0.56f); val o = Offset(w * 0.07f, h * 0.22f)
            drawOval(c1, o, s); drawOval(outline, o, s, style = line)
            drawOval(Color.White.copy(alpha = 0.45f), Offset(w * 0.2f, h * 0.3f), Size(w * 0.3f, h * 0.12f)) }
        "capsule" -> { val s = Size(w * 0.9f, h * 0.44f); val o = Offset(w * 0.05f, h * 0.28f); val r = CornerRadius(s.height / 2)
            drawRoundRect(c1, o, s, r)
            clipRect(left = w / 2) { drawRoundRect(c2, o, s, r) }
            drawLine(shade(c2), Offset(w / 2, o.y), Offset(w / 2, o.y + s.height), w * 0.025f)
            drawRoundRect(outline, o, s, r, style = line)
            drawRoundRect(Color.White.copy(alpha = 0.4f), Offset(w * 0.14f, h * 0.34f), Size(w * 0.26f, h * 0.08f), CornerRadius(h * 0.04f)) }
        "liquid" -> { val body = Size(w * 0.5f, h * 0.6f); val o = Offset(w * 0.25f, h * 0.3f)
            drawRoundRect(c1, o, body, CornerRadius(w * 0.1f)); drawRoundRect(outline, o, body, CornerRadius(w * 0.1f), style = line)
            drawRect(shade(c1), Offset(w * 0.25f, h * 0.55f), Size(body.width, h * 0.2f))
            drawRoundRect(c2, Offset(w * 0.36f, h * 0.12f), Size(w * 0.28f, h * 0.16f), CornerRadius(w * 0.04f))
            drawRoundRect(outline, Offset(w * 0.36f, h * 0.12f), Size(w * 0.28f, h * 0.16f), CornerRadius(w * 0.04f), style = line) }
        else -> { val r = w * 0.4f; val c = Offset(w / 2, h / 2)
            drawCircle(c1, r, c); drawCircle(outline, r, c, style = line)
            drawLine(shade(c1), Offset(w * 0.24f, h / 2), Offset(w * 0.76f, h / 2), w * 0.04f)
            drawCircle(Color.White.copy(alpha = 0.4f), r * 0.22f, Offset(w * 0.38f, h * 0.34f)) }
    }
}

/** The pill's photo, round, or the pill drawn in its shape and colour. */
@Composable private fun PillEmblem(m: Medicine, dir: File, size: Dp, refresh: Int = 0) {
    val file = Pills.photo(dir, m.id)
    var pic by remember(m.id, refresh) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(m.id, refresh, file.lastModified()) { pic = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull() } }
    val p = pic
    if (p != null) Image(p, null, Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
    else Box(Modifier.size(size).clip(CircleShape).background(PILLS_COLOR.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { PillShape(m.shape, m.color, m.color2, size * 0.78f) }
}

// ——— Today ———

private val PARTS = listOf(R.string.pills_part_morning to R.drawable.ic_sunrise, R.string.pills_part_noon to R.drawable.ic_sun,
    R.string.pills_part_evening to R.drawable.ic_sunset, R.string.pills_part_night to R.drawable.ic_bedtime)

/** A strip of the week, then a card for each part of the day that has doses; yesterday's forgotten ones first, in red. */
@Composable private fun TodayPane(meds: List<Medicine>, log: List<PillEvent>, dir: File, now: Instant, zone: ZoneId, day: LocalDate, choose: (LocalDate) -> Unit, wide: Boolean, act: DoseActions) {
    val today = now.atZone(zone).toLocalDate()
    val locale = LocalContext.current.uiLocale()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        (-3L..3L).map { today.plusDays(it) }.forEach { d ->
            val on = d == day
            Column(Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) PILLS_COLOR.copy(alpha = 0.22f) else Color.Transparent).clickable { choose(d) }.padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(d.dayOfWeek.getDisplayName(TextStyle.SHORT, locale), style = MaterialTheme.typography.labelSmall, color = if (d == today) PILLS_COLOR else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(d.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = if (on) FontWeight.Bold else null)
            }
        }
    }
    val start = day.atStartOfDay(zone).toInstant(); val end = day.plusDays(1).atStartOfDay(zone).toInstant()
    val list = if (day == today) Pills.today(meds, log, now, zone) else Pills.doses(meds, log, start, end, now, zone)
    val earlier = list.filter { it.due < start }
    val byPart = list.filter { it.due >= start }.groupBy { Pills.part(it.due.atZone(zone).toLocalTime()) }
    if (list.isEmpty()) Text(stringResource(R.string.pills_nothing_today), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
    if (earlier.isNotEmpty()) PartCard(stringResource(R.string.pills_forgotten), R.drawable.ic_bedtime, earlier, dir, now, zone, act, alarm = true)
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), maxItemsInEachRow = if (wide) 2 else 1) {
        (0..3).filter { byPart[it] != null }.forEach { p ->
            val (label, icon) = PARTS[p]
            PartCard(stringResource(label), icon, byPart[p]!!, dir, now, zone, act, modifier = Modifier.weight(1f))
        }
    }
}

@Composable private fun PartCard(title: String, icon: Int, doses: List<Dose>, dir: File, now: Instant, zone: ZoneId, act: DoseActions, alarm: Boolean = false, modifier: Modifier = Modifier.fillMaxWidth()) {
    val done = doses.count { it.state == Dose.State.Taken || it.state == Dose.State.Skipped }
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceVariant,
        border = if (alarm) BorderStroke(2.dp, MaterialTheme.colorScheme.error) else null, modifier = modifier) {
        Column(Modifier.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(icon), null, Modifier.size(20.dp), tint = if (alarm) MaterialTheme.colorScheme.error else PILLS_COLOR)
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = if (alarm) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                if (!alarm) Text("$done/${doses.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
            }
            doses.forEach { d -> DoseLine(d, dir, now, zone, act) }
        }
    }
}

@Composable private fun DoseLine(d: Dose, dir: File, now: Instant, zone: ZoneId, act: DoseActions) {
    val open = d.state == Dose.State.Waiting || d.state == Dose.State.Missed
    val due = d.due.atZone(zone)
    val late = due.toLocalDate() != now.atZone(zone).toLocalDate() && d.due < now
    val food = foodText(d.med.food)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(enabled = open) { act.take(d) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillEmblem(d.med, dir, 44.dp)
        Column(Modifier.weight(1f)) {
            Text(d.med.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (d.state == Dose.State.Taken || d.state == Dose.State.Skipped) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            val state = when (d.state) {
                Dose.State.Taken -> stringResource(R.string.pills_state_taken, d.at?.atZone(zone)?.format(HHMM) ?: due.format(HHMM))
                Dose.State.Skipped -> stringResource(R.string.pills_state_skipped)
                Dose.State.Missed -> stringResource(R.string.pills_state_missed)
                Dose.State.Waiting -> stringResource(R.string.pills_state_dose, Medicine.amount(d.med.dose))
            }
            Text(listOfNotNull((if (late) stringResource(R.string.common_yesterday) + " " else "") + due.format(HHMM), state, food.takeIf { d.state != Dose.State.Missed }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, maxLines = 2, color = if (d.state == Dose.State.Missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open && d.due <= now.plusSeconds(3 * 3600)) {
            IconButton(onClick = { act.skip(d) }) { Icon(painterResource(R.drawable.ic_skip_next), contentDescription = stringResource(R.string.pills_skip), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            Button(onClick = { act.take(d) }, colors = ButtonDefaults.buttonColors(containerColor = PILLS_COLOR, contentColor = Color.White)) { Text(stringResource(R.string.pills_taken), maxLines = 1) }
        } else if (!open) IconButton(onClick = { act.back(d) }) {
            Icon(painterResource(if (d.state == Dose.State.Taken) R.drawable.ic_check else R.drawable.ic_undo), contentDescription = stringResource(R.string.pills_undo),
                tint = if (d.state == Dose.State.Taken) PILLS_COLOR else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun foodText(food: String): String? = when (food) {
    "with" -> stringResource(R.string.pills_food_with); "before" -> stringResource(R.string.pills_food_before)
    "after" -> stringResource(R.string.pills_food_after); "empty" -> stringResource(R.string.pills_food_empty); else -> null
}

// ——— Medicines ———

@Composable private fun MedicinesPane(meds: List<Medicine>, dir: File, now: Instant, zone: ZoneId, wide: Boolean, open: (Medicine) -> Unit, addBox: (Medicine) -> Unit, settings: () -> Unit) {
    val today = now.atZone(zone).toLocalDate()
    val per = if (wide) 3 else 2
    val live = meds.filter { !it.archived }
    live.chunked(per).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { m ->
                Surface(onClick = { open(m) }, shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PillEmblem(m, dir, 64.dp)
                        Text(m.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(timesText(m), style = MaterialTheme.typography.bodySmall, maxLines = 3, color = if (m.times.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (m.counted) LinearProgressIndicator(progress = { ((m.daysLeft() ?: 0) / 30f).coerceIn(0.03f, 1f) }, Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                            color = if (m.low()) MaterialTheme.colorScheme.error else PILLS_COLOR, trackColor = MaterialTheme.colorScheme.surface)
                        Text(stockText(m, today), style = MaterialTheme.typography.bodySmall, maxLines = 3, color = if (m.low()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (m.perBox > 0) TextButton(onClick = { addBox(m) }) { Icon(painterResource(R.drawable.ic_add), null, Modifier.size(18.dp)); Text(" " + stringResource(R.string.pills_add_box, m.perBox)) }
                    }
                }
            }
            repeat(per - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    val old = meds.filter { it.archived }
    if (old.isNotEmpty()) {
        Text(stringResource(R.string.pills_archived), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        old.forEach { m -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { open(m) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillEmblem(m, dir, 36.dp); Text(m.title, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
    }
    TextButton(onClick = settings) { Icon(painterResource(R.drawable.ic_settings), null, Modifier.size(18.dp)); Text(" " + stringResource(R.string.pills_settings_open)) }
}

/** "34 left · lasts 17 days (till 24 Nov)", or "not counted". */
@Composable private fun stockText(m: Medicine, today: LocalDate): String {
    if (!m.counted) return stringResource(R.string.pills_not_counted)
    val left = m.daysLeft()
    val stock = stringResource(R.string.pills_stock, Medicine.amount(m.stock))
    return if (left == null) stock else stock + " · " + stringResource(R.string.pills_lasts, left, m.lastDay(today)!!.format(DateTimeFormatter.ofPattern("d MMM", LocalContext.current.uiLocale())))
}

/** "1 at 08:00, 21:00 · Mon, Wed · with food", "every 7 days", or "No time set". */
@Composable private fun timesText(m: Medicine): String {
    if (m.times.isEmpty()) return stringResource(R.string.pills_no_time)
    val locale = LocalContext.current.uiLocale()
    val days = when {
        m.every > 1 -> " · " + stringResource(R.string.pills_every_n, m.every)
        m.days.isEmpty() -> ""
        else -> " · " + AlarmRecord.WEEK.filter { it in m.days }.joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) }
    }
    return stringResource(R.string.pills_times, Medicine.amount(m.dose), m.times.joinToString(", ") { it.format(HHMM) }) + days + (foodText(m.food)?.let { " · $it" } ?: "")
}

private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

// ——— Progress ———

/** The last 30 days: on time, forgotten, how late; what the numbers suggest; each dose time's days as squares. */
@Composable private fun ProgressPane(meds: List<Medicine>, log: List<PillEvent>, dir: File, now: Instant, zone: ZoneId, apply: (Medicine, Pair<LocalTime, LocalTime>) -> Unit) {
    val slots = remember(meds, log, now.epochSecond / 3600) { Pills.slots(meds.filter { !it.archived }, log, now, zone) }
    val due = slots.sumOf { it.due }
    if (due == 0) { Text(stringResource(R.string.pills_progress_empty), color = MaterialTheme.colorScheme.onSurfaceVariant); return }
    // the month at a glance, at the top (8 Nov, the user: "the month's progress with the squares, at the very top"): every dose
    // of every medicine, oldest first, in the same colours as the rows below
    MonthSquares(slots.flatMap { it.doses.takeLast(30) }.filter { it.state != Dose.State.Waiting }.sortedBy { it.due })
    val takenSlots = slots.filter { it.onTime + it.late > 0 }
    val lateAvg = if (takenSlots.isEmpty()) 0L else takenSlots.sumOf { it.lateMinutes * (it.onTime + it.late) } / takenSlots.sumOf { it.onTime + it.late }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Metric("${slots.sumOf { it.onTime + it.late } * 100 / due}%", stringResource(R.string.pills_m_taken), Modifier.weight(1f))
        Metric(slots.sumOf { it.missed }.toString(), stringResource(R.string.pills_m_missed), Modifier.weight(1f))
        Metric("$lateAvg′", stringResource(R.string.pills_m_late), Modifier.weight(1f))
    }
    Pills.advice(slots, zone).forEach { a -> AdviceCard(a, apply) }
    slots.forEach { s ->
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillEmblem(s.med, dir, 32.dp)
                    Text("${s.med.title} · ${s.time.format(HHMM)}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (s.due > 0) "${(s.onTime + s.late) * 100 / s.due}%" else "", style = MaterialTheme.typography.labelLarge)
                }
                // a square a day: dark on time, light late, red forgotten, grey skipped, outlined still to come
                // always 30 places, so every row has the same squares; the days before it was added left empty
                val days = s.doses.takeLast(30)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(30 - days.size) { Spacer(Modifier.weight(1f)) }
                    days.forEach { d ->
                        val (fill, edge) = when (d.state) {
                            Dose.State.Taken -> (if (d.lateMin() > 30) PILLS_COLOR.copy(alpha = 0.4f) else PILLS_COLOR) to Color.Transparent
                            Dose.State.Missed -> Color.Transparent to MISSED_RED
                            Dose.State.Skipped -> MaterialTheme.colorScheme.outline to Color.Transparent
                            Dose.State.Waiting -> Color.Transparent to MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                        }
                        Box(Modifier.weight(1f).aspectRatio(0.7f).clip(RoundedCornerShape(3.dp)).background(fill).border(1.5.dp, edge, RoundedCornerShape(3.dp)))
                    }
                }
            }
        }
    }
    Text(stringResource(R.string.pills_progress_key), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** All doses as a grid of squares: dark on time, light late, red outline forgotten, grey skipped; rows as many as they need. */
@Composable private fun MonthSquares(doses: List<Dose>) {
    if (doses.isEmpty()) return
    val skipped = MaterialTheme.colorScheme.outline
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.padding(14.dp).fillMaxWidth()) {
            val cell = 20.dp; val gap = 4.dp
            val cols = ((maxWidth + gap) / (cell + gap)).toInt().coerceAtLeast(1)
            val rows = (doses.size + cols - 1) / cols
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(cell * rows + gap * (rows - 1).coerceAtLeast(0))) {
                val c = cell.toPx(); val g = gap.toPx(); val r = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
                doses.forEachIndexed { i, d ->
                    val at = androidx.compose.ui.geometry.Offset((i % cols) * (c + g), (i / cols) * (c + g)); val sz = androidx.compose.ui.geometry.Size(c, c)
                    when (d.state) {
                        Dose.State.Taken -> drawRoundRect(if (d.lateMin() > 30) PILLS_COLOR.copy(alpha = 0.4f) else PILLS_COLOR, at, sz, r)
                        Dose.State.Missed -> drawRoundRect(MISSED_RED, at + androidx.compose.ui.geometry.Offset(1f, 1f), androidx.compose.ui.geometry.Size(c - 2f, c - 2f), r, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                        else -> drawRoundRect(skipped, at, sz, r)
                    }
                }
            }
        }
    }
    // the key under it, as the user's picture has it: a square and its word for each kind
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        listOf(PILLS_COLOR to R.string.pills_on_time, PILLS_COLOR.copy(alpha = 0.4f) to R.string.pills_late, MISSED_RED to R.string.pills_state_missed, skipped to R.string.pills_state_skipped).forEach { (color, word) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).then(if (word == R.string.pills_state_missed) Modifier.border(1.5.dp, color, RoundedCornerShape(3.dp)) else Modifier.background(color)))
                Text(stringResource(word), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun Metric(value: String, label: String, modifier: Modifier) =
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier) {
        Column(Modifier.padding(12.dp)) { Text(value, style = MaterialTheme.typography.headlineSmall); Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }

@Composable private fun AdviceCard(a: Pills.Advice, apply: (Medicine, Pair<LocalTime, LocalTime>) -> Unit) {
    val locale = LocalContext.current.uiLocale()
    val m = a.slot.med; val t = a.slot.time.format(HHMM)
    val text = when (a.kind) {
        "move" -> stringResource(R.string.pills_advice_move, m.title, t, a.time!!.format(HHMM))
        "days" -> stringResource(R.string.pills_advice_days, m.title, t, a.days.joinToString(", ") { it.getDisplayName(TextStyle.FULL, locale) })
        "bedside" -> stringResource(R.string.pills_advice_bedside, m.title, t)
        else -> stringResource(R.string.pills_advice_earlier, m.title, t, a.time!!.format(HHMM))
    }
    var gone by remember(a) { mutableStateOf(false) }
    if (gone) return
    Surface(shape = MaterialTheme.shapes.extraLarge, color = PILLS_COLOR.copy(alpha = 0.14f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, top = 6.dp, end = 4.dp, bottom = 10.dp)) {
            Row {
                Icon(painterResource(R.drawable.ic_sparkle), null, tint = PILLS_COLOR, modifier = Modifier.padding(top = 8.dp).size(20.dp))
                Text(text, Modifier.weight(1f).padding(start = 10.dp, top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { gone = true }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.common_close), Modifier.size(18.dp)) }
            }
            a.time?.let { nt -> Button(onClick = { gone = true; apply(m, a.slot.time to nt) }, Modifier.align(Alignment.End).padding(end = 10.dp, top = 4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PILLS_COLOR, contentColor = Color.White)) { Text(stringResource(R.string.pills_advice_apply, nt.format(HHMM))) } }
        }
    }
}

// ——— a medicine's sheet ———

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun MedicineSheet(m: Medicine, dir: File, log: List<PillEvent>, now: Instant, zone: ZoneId, close: () -> Unit, edit: () -> Unit, addBox: (Int) -> Unit,
                                      setStock: (Double) -> Unit, takeNow: () -> Unit, archive: () -> Unit, delete: () -> Unit) {
    var counting by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val locale = LocalContext.current.uiLocale()
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = SheetColor, contentColor = islandContentColor()) {
        Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PillEmblem(m, dir, 72.dp)
                Column(Modifier.weight(1f)) {
                    Text(m.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(timesText(m), style = MaterialTheme.typography.bodyMedium, color = islandContentColor().copy(alpha = 0.75f))
                }
                IconButton(onClick = edit) { Icon(painterResource(R.drawable.ic_edit), contentDescription = stringResource(R.string.common_edit)) }
            }
            Text(stockText(m, now.atZone(zone).toLocalDate()), style = MaterialTheme.typography.titleSmall, color = if (m.low()) MaterialTheme.colorScheme.error else islandContentColor())
            if (m.low()) Text(stringResource(R.string.pills_order_now), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (m.perBox > 0) FilledTonalButton(onClick = { addBox(1) }) { Icon(painterResource(R.drawable.ic_add), null, Modifier.size(18.dp)); Text(" " + stringResource(R.string.pills_add_box, m.perBox)) }
                FilledTonalButton(onClick = { counting = true }) { Text(stringResource(R.string.pills_correct)) }
                FilledTonalButton(onClick = takeNow) { Text(stringResource(R.string.pills_take_now)) }
            }
            HorizontalDivider(color = islandContentColor().copy(alpha = 0.2f))
            Text(stringResource(R.string.pills_history), style = MaterialTheme.typography.titleMedium)
            val missed = Pills.doses(listOf(m), log, now.minusSeconds(30 * 86_400L), now, now, zone).filter { it.state == Dose.State.Missed }.map { it.due to null as PillEvent? }
            val events = log.filter { it.med == m.id }.map { (it.due ?: it.at) to it }
            val rows = (events + missed).sortedByDescending { it.first }.take(80)
            if (rows.isEmpty()) Text(stringResource(R.string.pills_history_empty), color = islandContentColor().copy(alpha = 0.7f))
            val fmt = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", locale)
            rows.forEach { (at, e) ->
                val what = when (e?.kind) {
                    null -> stringResource(R.string.pills_state_missed)
                    PillEvent.TAKEN -> if (e.due == null) stringResource(R.string.pills_h_extra, Medicine.amount(e.amount)) else if (e.at.epochSecond - e.due.epochSecond > 1800) stringResource(R.string.pills_h_late, e.at.atZone(zone).format(HHMM)) else stringResource(R.string.pills_h_taken)
                    PillEvent.SKIPPED -> stringResource(R.string.pills_state_skipped)
                    PillEvent.UNDO -> stringResource(R.string.pills_h_undo)
                    else -> if (e.note == "count") stringResource(R.string.pills_h_count, Medicine.amount(e.stock)) else stringResource(R.string.pills_h_added, Medicine.amount(e.amount), Medicine.amount(e.stock))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(at.atZone(zone).format(fmt), Modifier.width(150.dp), style = MaterialTheme.typography.bodySmall, color = islandContentColor().copy(alpha = 0.7f))
                    Text(what, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = if (e == null) MaterialTheme.colorScheme.error else islandContentColor())
                }
            }
            HorizontalDivider(color = islandContentColor().copy(alpha = 0.2f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = archive) { Icon(painterResource(R.drawable.ic_archive), null, Modifier.size(18.dp)); Text(" " + stringResource(if (m.archived) R.string.pills_unarchive else R.string.pills_archive)) }
                TextButton(onClick = { deleting = true }) { Icon(painterResource(R.drawable.ic_delete), null, Modifier.size(18.dp)); Text(" " + stringResource(R.string.common_delete)) }
            }
        }
    }
    if (counting) NumberDialog(stringResource(R.string.pills_correct_title), Medicine.amount(m.stock), close = { counting = false }) { v -> counting = false; setStock(v) }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text(stringResource(R.string.pills_delete_title, m.title)) }, text = { Text(stringResource(R.string.pills_delete_text)) },
        confirmButton = { TextButton(onClick = { deleting = false; delete() }) { Text(stringResource(R.string.common_delete)) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.common_cancel)) } })
}

@Composable private fun NumberDialog(title: String, start: String, close: () -> Unit, done: (Double) -> Unit) {
    var text by remember { mutableStateOf(start) }
    AlertDialog(onDismissRequest = close, title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)) },
        confirmButton = { TextButton(onClick = { number(text)?.let(done) }) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.common_cancel)) } })
}

private fun number(s: String) = s.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }

// ——— the guide: a new medicine (or one changed) step by step ———

/** What the guide fills in, as typed: the numbers stay text until saved. */
private data class Draft(val m: Medicine, val dose: String, val perBox: String, val boxes: String, val loose: String, val refillDays: String, val refillTimes: String, val forDays: String,
    /** An open box besides the new ones: [loose] is what is left in it (the user, 7 Nov: "I have 48, a new one has 50"). */
    val opened: Boolean = false) {
    /** New boxes (each a new box's pills) and what is left in the open one; the box's size alone adds nothing (90 and 0 boxes: 0). */
    fun stock(): Pair<Int, Double> = (boxes.trim().toIntOrNull() ?: 0) to (if (opened) number(loose) ?: 0.0 else 0.0)
}

/**
 * Five steps (the user, 7 Nov: "a step-by-step guide that takes you by the hand"): the name, how it looks, when, the stock,
 * the refill reminder. «Πες το» (AI on and a key) fills them all from one sentence, the steps then shown to be checked.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun MedicineGuide(start: Medicine, dir: File, fresh: Boolean, close: () -> Unit, save: (Medicine, Int, Double) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // [fresh]: a new medicine (asks the stock); one already there is edited, its stock left alone (the user, 7 Nov: run a second
    // time it added the boxes again, as it looked for the file by its old name and took every medicine for a new one)
    var d by remember { mutableStateOf(Draft(start, Medicine.amount(start.dose), if (start.perBox > 0) start.perBox.toString() else "", if (fresh) "1" else "", "",
        start.refillDays.toString(), start.refillTimes.toString(), "")) }
    var step by remember { mutableIntStateOf(0) }
    var dial by remember { mutableStateOf<Int?>(null) }
    var photoVersion by remember { mutableIntStateOf(0) }
    var listening by remember { mutableStateOf(false) }
    var heard by remember { mutableStateOf<String?>(null) }
    val steps = 5
    fun keepPhoto(b: Bitmap?) = scope.launch {
        if (b == null) return@launch
        withContext(Dispatchers.IO) { runCatching { savePillPhoto(b, Pills.photo(dir, d.m.id)) }.onFailure { WriterLog.e("Pills: photo", it) } }
        photoVersion++
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let { u -> scope.launch { keepPhoto(withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4 }) } }.getOrNull() }) } }
    }
    val shoot = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { b -> keepPhoto(b) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) shoot.launch(null) }
    // «Πες το»: one sentence heard, written down by Mistral, turned into the guide's fields
    val ai = AiGate.allows("pills") && AssistantKey.has(context)
    fun sayIt() = scope.launch {
        listening = true; heard = null
        val r = withContext(Dispatchers.IO) { runCatching { PillsVoiceFill.hear(context) } }
        listening = false
        r.onSuccess { (text, o) ->
            heard = text
            val (m, extra) = PillsVoiceFill.apply(d.m, o)
            d = d.copy(m = m, dose = o.optDouble("dose").takeIf { !it.isNaN() && it > 0 }?.let { Medicine.amount(Medicine.halves(it)) } ?: d.dose,
                // boxes are the new, unopened ones; pills left in an open box tick it, and no new box unless said
                perBox = extra["per_box"] ?: d.perBox, boxes = extra["boxes"] ?: if (extra["pills"] != null) "0" else d.boxes,
                loose = extra["pills"] ?: d.loose, opened = extra["pills"] != null || d.opened, forDays = extra["for_days"] ?: d.forDays)
        }.onFailure { WriterLog.w("Pills: Say it: ${it.message}"); heard = it.shownMessage(context) ?: context.getString(R.string.pills_say_failed) }
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) sayIt() }
    fun finish() {
        val days = d.forDays.trim().toIntOrNull()?.takeIf { it > 0 }
        save(d.m.copy(name = d.m.name.trim(), strength = d.m.strength.trim(), dose = Medicine.halves(number(d.dose) ?: 1.0), perBox = d.perBox.trim().toIntOrNull() ?: 0,
            refillDays = d.refillDays.trim().toIntOrNull()?.coerceIn(0, 90) ?: 7, refillTimes = d.refillTimes.trim().toIntOrNull()?.coerceIn(0, 30) ?: 3,
            until = days?.let { LocalDate.now().plusDays(it - 1L) } ?: d.m.until),
            d.stock().first, d.stock().second)
    }
    val nameOk = d.m.name.isNotBlank()
    val doseOk = number(d.dose)?.let { Medicine.halves(it) > 0 } == true
    androidx.compose.ui.window.Dialog(onDismissRequest = close, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (step > 0) step-- else close() }) { Icon(painterResource(if (step > 0) R.drawable.ic_chevron_left else R.drawable.ic_close), contentDescription = stringResource(R.string.common_back)) }
                    Text(stringResource(if (fresh) R.string.pills_new else R.string.common_edit) + " · " + stringResource(R.string.pills_step, step + 1, steps), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (!fresh) TextButton(onClick = ::finish, enabled = nameOk && doseOk) { Text(stringResource(R.string.common_save)) }
                }
                LinearProgressIndicator(progress = { (step + 1f) / steps }, Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(4.dp).clip(CircleShape), color = PILLS_COLOR)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp).align(Alignment.CenterHorizontally).widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    when (step) {
                        0 -> {
                            StepTitle(R.string.pills_q_name)
                            OutlinedTextField(d.m.name, { d = d.copy(m = d.m.copy(name = it)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.pills_name)) }, singleLine = true)
                            OutlinedTextField(d.m.strength, { d = d.copy(m = d.m.copy(strength = it)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.pills_strength)) }, placeholder = { Text("120 mg") }, singleLine = true)
                            if (ai) Text(stringResource(R.string.pills_say_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        1 -> {
                            StepTitle(R.string.pills_q_look)
                            Box(Modifier.align(Alignment.CenterHorizontally)) { PillEmblem(d.m, dir, 120.dp, photoVersion) }
                            Text(stringResource(R.string.pills_shape), style = MaterialTheme.typography.titleSmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Medicine.SHAPES.forEach { s ->
                                    Surface(onClick = { d = d.copy(m = d.m.copy(shape = s)) }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant,
                                        border = BorderStroke(2.dp, if (d.m.shape == s) PILLS_COLOR else Color.Transparent)) { PillShape(s, d.m.color, d.m.color2, 56.dp, Modifier.padding(6.dp)) }
                                }
                            }
                            Text(stringResource(R.string.pills_color), style = MaterialTheme.typography.titleSmall)
                            Swatches(d.m.color) { d = d.copy(m = d.m.copy(color = it)) }
                            if (d.m.shape == "capsule" || d.m.shape == "liquid") {
                                Text(stringResource(if (d.m.shape == "capsule") R.string.pills_color2 else R.string.pills_color_cap), style = MaterialTheme.typography.titleSmall)
                                Swatches(d.m.color2) { d = d.copy(m = d.m.copy(color2 = it)) }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = { if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) shoot.launch(null) else askCamera.launch(Manifest.permission.CAMERA) }) {
                                    Icon(painterResource(R.drawable.ic_photo_camera), null, Modifier.size(18.dp)); Text(" " + stringResource(R.string.pills_photo_take)) }
                                FilledTonalButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text(stringResource(R.string.pills_photo_pick)) }
                            }
                            if (Pills.photo(dir, d.m.id).exists()) TextButton(onClick = { Pills.photo(dir, d.m.id).delete(); photoVersion++ }) { Text(stringResource(R.string.pills_photo_remove)) }
                        }
                        2 -> {
                            StepTitle(R.string.pills_q_when)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                d.m.times.forEachIndexed { i, t ->
                                    Surface(shape = CircleShape, color = PILLS_COLOR.copy(alpha = 0.16f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(painterResource(PARTS[Pills.part(t)].second), null, Modifier.padding(start = 12.dp).size(18.dp), tint = PILLS_COLOR)
                                            Text(t.format(HHMM), Modifier.clickable { dial = i }.padding(start = 8.dp, top = 8.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium)
                                            IconButton(onClick = { d = d.copy(m = d.m.copy(times = d.m.times - t)) }, Modifier.size(36.dp)) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.common_remove), Modifier.size(16.dp)) }
                                        }
                                    }
                                }
                                FilledTonalButton(onClick = { dial = -1 }) { Icon(painterResource(R.drawable.ic_add), null, Modifier.size(18.dp)); Text(" " + stringResource(R.string.pills_add_time)) }
                            }
                            OutlinedTextField(d.dose, { d = d.copy(dose = it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.pills_dose)) }, singleLine = true,
                                isError = !doseOk, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                            Text(stringResource(R.string.pills_how_often), style = MaterialTheme.typography.titleSmall)
                            val mode = when { d.m.every > 1 -> 2; d.m.days.isNotEmpty() -> 1; else -> 0 }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(mode == 0, { d = d.copy(m = d.m.copy(days = emptySet(), every = 1)) }, { Text(stringResource(R.string.alarm_every_day)) })
                                FilterChip(mode == 1, { d = d.copy(m = d.m.copy(every = 1, days = d.m.days.ifEmpty { setOf(DayOfWeek.MONDAY) })) }, { Text(stringResource(R.string.pills_some_days)) })
                                FilterChip(mode == 2, { d = d.copy(m = d.m.copy(days = emptySet(), every = maxOf(2, d.m.every))) }, { Text(stringResource(R.string.pills_every_n_days)) })
                            }
                            val locale = context.uiLocale()
                            if (mode == 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                AlarmRecord.WEEK.forEach { wd -> FilterChip(wd in d.m.days, { d = d.copy(m = d.m.copy(days = if (wd in d.m.days) d.m.days - wd else d.m.days + wd)) }, { Text(wd.getDisplayName(TextStyle.SHORT, locale)) }) }
                            }
                            if (mode == 2) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IconButton(onClick = { d = d.copy(m = d.m.copy(every = maxOf(2, d.m.every - 1))) }) { Icon(painterResource(R.drawable.ic_remove), null) }
                                Text(stringResource(R.string.pills_every_n, d.m.every), style = MaterialTheme.typography.titleMedium)
                                IconButton(onClick = { d = d.copy(m = d.m.copy(every = minOf(60, d.m.every + 1))) }) { Icon(painterResource(R.drawable.ic_add), null) }
                            }
                            Text(stringResource(R.string.pills_food), style = MaterialTheme.typography.titleSmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(d.m.food.isEmpty(), { d = d.copy(m = d.m.copy(food = "")) }, { Text(stringResource(R.string.pills_food_any)) })
                                Medicine.FOODS.forEach { f -> FilterChip(d.m.food == f, { d = d.copy(m = d.m.copy(food = f)) }, { Text(foodText(f).orEmpty()) }) }
                            }
                            OutlinedTextField(d.forDays, { d = d.copy(forDays = it.filter(Char::isDigit)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.pills_for_days)) },
                                supportingText = { Text(d.m.until?.let { stringResource(R.string.pills_until, it.format(DateTimeFormatter.ofPattern("d MMM", locale))) } ?: stringResource(R.string.pills_for_good)) },
                                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                        3 -> {
                            StepTitle(R.string.pills_q_stock)
                            OutlinedTextField(d.perBox, { d = d.copy(perBox = it.filter(Char::isDigit)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.pills_per_box)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            // a new box's size apart from what is in hand (the user, 7 Nov: "I have 48, but a new one has 50"): the boxes
                            // not opened yet, and what is left in the one being used
                            if (fresh) {
                                OutlinedTextField(d.boxes, { d = d.copy(boxes = it.filter(Char::isDigit)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.pills_boxes_now)) },
                                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                                // ticked: one of them is open, and a field asks how many are left in it
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { d = d.copy(opened = !d.opened) }, verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.material3.Checkbox(d.opened, { d = d.copy(opened = it) })
                                    Text(stringResource(R.string.pills_opened), style = MaterialTheme.typography.bodyLarge)
                                }
                                if (d.opened) OutlinedTextField(d.loose, { d = d.copy(loose = it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.pills_loose_now)) },
                                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                                val (full, left) = d.stock(); val size = d.perBox.toIntOrNull() ?: 0
                                if (full > 0 && size == 0) Text(stringResource(R.string.pills_need_box_size), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                                else if (full > 0 || left > 0) Text(stringResource(R.string.pills_total, Medicine.amount(Medicine.halves(full * size + left))),
                                    style = MaterialTheme.typography.titleMedium, color = PILLS_COLOR)
                            } else Text(stockText(d.m, LocalDate.now()), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.pills_stock_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        else -> {
                            StepTitle(R.string.pills_q_refill)
                            OutlinedTextField(d.refillDays, { d = d.copy(refillDays = it.filter(Char::isDigit)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.pills_refill_days)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            OutlinedTextField(d.refillTimes, { d = d.copy(refillTimes = it.filter(Char::isDigit)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.pills_refill_times)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            Text(stringResource(R.string.pills_refill_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            // the whole of it, to look over before saving
                            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    PillEmblem(d.m, dir, 56.dp, photoVersion)
                                    Column { Text(d.m.title.ifBlank { "—" }, style = MaterialTheme.typography.titleMedium); Text(timesText(d.m.copy(dose = number(d.dose) ?: 1.0)), style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }
                    heard?.let { Surface(shape = MaterialTheme.shapes.large, color = PILLS_COLOR.copy(alpha = 0.12f)) { Text("«$it»", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) } }
                }
                // the actions at the bottom (the user's rule): «Πες το» when AI is on, then Next or Save
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (ai) FilledTonalButton(onClick = { if (hasMic(context)) sayIt() else askMic.launch(Manifest.permission.RECORD_AUDIO) }, enabled = !listening) {
                        Icon(painterResource(R.drawable.ic_mic), null, Modifier.size(18.dp)); Text(" " + stringResource(if (listening) R.string.pills_listening else R.string.pills_say_it))
                    }
                    Spacer(Modifier.weight(1f))
                    val boxSizeOk = !fresh || d.stock().first == 0 || (d.perBox.toIntOrNull() ?: 0) > 0
                    val canGo = when (step) { 0 -> nameOk; 2 -> doseOk; 3 -> boxSizeOk; else -> true }
                    Button(onClick = { if (step < steps - 1) step++ else finish() }, enabled = canGo && (step < steps - 1 || nameOk && doseOk),
                        colors = ButtonDefaults.buttonColors(containerColor = PILLS_COLOR, contentColor = Color.White)) {
                        Text(stringResource(if (step < steps - 1) R.string.pills_next else R.string.common_save))
                    }
                }
            }
        }
    }
    dial?.let { i ->
        val t = d.m.times.getOrNull(i) ?: LocalTime.of(21, 0)
        DialDialog(t.hour, t.minute, close = { dial = null }) { h, min ->
            val nt = LocalTime.of(h, min)
            d = d.copy(m = d.m.copy(times = ((if (i >= 0) d.m.times - t else d.m.times) + nt).distinct().sorted())); dial = null
        }
    }
}

@Composable private fun StepTitle(text: Int) = Text(stringResource(text), style = MaterialTheme.typography.headlineSmall)

/** The colours, onto a second line when the screen is narrow (the user, 7 Nov: the brown did not fit). */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun Swatches(selected: Int, choose: (Int) -> Unit) =
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Medicine.COLORS.forEach { c ->
            Box(Modifier.size(32.dp).clip(CircleShape).background(Color(c)).border(if (c == selected) 3.dp else 1.dp, if (c == selected) PILLS_COLOR else MaterialTheme.colorScheme.outline, CircleShape).clickable { choose(c) })
        }
    }

/** The photo as the emblem: the middle square, 384 px, a JPEG. */
private fun savePillPhoto(b: Bitmap, f: File) {
    val side = minOf(b.width, b.height)
    val square = Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
    val small = Bitmap.createScaledBitmap(square, 384, 384, true)
    f.parentFile?.mkdirs()
    val tmp = File(f.parentFile, ".${f.name}.tmp")
    tmp.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 88, it) }
    if (!tmp.renameTo(f)) { tmp.copyTo(f, true); tmp.delete() }
}

/**
 * The guide's «Πες το»: a sentence heard ([listen]), written down (Mistral's transcription), and read for a medicine's
 * fields by Mistral's chat as JSON. AI only: the button is not there when AI is off.
 */
internal object PillsVoiceFill {
    private const val PROMPT = "Read a medicine from what the user said (Greek or English) and answer only JSON with the fields that were said: " +
        "name (no strength), strength (\"20 mg\"), dose (pills each time; 0.5 for half), times (HH:MM 24 h; \"το πρωί\" alone 08:00, \"μεσημέρι\" 14:00, \"το βράδυ\" 21:00, \"πριν κοιμηθώ\" 23:00), " +
        "days (some weekdays only, as MO TU WE TH FR SA SU), every (every N days), food (with, before, after, empty), for_days (how many days the course lasts), " +
        "per_box (pills in a new, full box), boxes (unopened boxes bought or in hand), pills (pills left in an opened box, or loose; \"I have 48 but a new box has 50\" is per_box 50, pills 48), shape (round, oval, capsule, liquid), color (white, yellow, orange, pink, red, purple, blue, green, brown)."
    private val COLOR_NAMES = listOf("white", "yellow", "orange", "pink", "red", "purple", "blue", "green", "brown")

    fun hear(c: android.content.Context): Pair<String, JSONObject> {
        AssistantNet.key(c, AiProviders.of(c, AiPart.CHAT)) // no key: said before listening
        val bill = AssistantMeter.bill(c)
        val pcm = listen(10_000, { false }) ?: throw DriveProblem("Nothing heard.", R.string.pills_say_nothing)
        val text = AssistantNet.transcribe(c, pcm, emptyList(), bill).trim()
        if (text.isEmpty()) throw DriveProblem("Nothing heard.", R.string.pills_say_nothing)
        return text to read(AssistantNet.api(c, bill), text)
    }

    fun read(api: Mistral, text: String): JSONObject {
        val r = api.complete(JSONArray().put(JSONObject().put("role", "system").put("content", PROMPT)).put(JSONObject().put("role", "user").put("content", text)), json = true, temperature = 0.0)
        WriterLog.i("Pills: Say it read ${text.length} characters")
        return JSONObject(r)
    }

    /** The fields said, over [m]; the numbers the guide keeps as text come back aside. */
    fun apply(m: Medicine, o: JSONObject): Pair<Medicine, Map<String, String>> {
        fun s(k: String) = o.optString(k).trim().takeIf { it.isNotEmpty() && it != "null" }
        val times = o.optJSONArray("times")?.let { a -> (0 until a.length()).mapNotNull { runCatching { LocalTime.parse(a.getString(it).trim().padStart(5, '0')) }.getOrNull() } }?.takeIf { it.isNotEmpty() }
        val days = o.optJSONArray("days")?.let { a -> (0 until a.length()).mapNotNull { AlarmRecord.DAY_CODES.indexOf(a.optString(it).uppercase().take(2)).takeIf { i -> i >= 0 }?.let { i -> AlarmRecord.WEEK[i] } }.toSet() }?.takeIf { it.isNotEmpty() }
        val every = o.optInt("every", 0).takeIf { it > 1 }
        val next = m.copy(name = s("name")?.replaceFirstChar { it.uppercase() } ?: m.name, strength = s("strength") ?: m.strength,
            times = times?.distinct()?.sorted() ?: m.times, days = if (every != null) emptySet() else days ?: m.days, every = every ?: if (days != null) 1 else m.every,
            food = s("food")?.takeIf { it in Medicine.FOODS } ?: m.food, shape = s("shape")?.takeIf { it in Medicine.SHAPES } ?: m.shape,
            color = s("color")?.let { COLOR_NAMES.indexOf(it.lowercase()) }?.takeIf { it >= 0 }?.let { Medicine.COLORS[it] } ?: m.color)
        val extra = listOf("per_box", "boxes", "pills", "for_days").mapNotNull { k -> o.optDouble(k).takeIf { !it.isNaN() && it > 0 }?.let { k to Medicine.amount(it) } }.toMap()
        return next to extra
    }
}

// ——— Settings › Pills ———

/** One check of what a reminder needs, and where to put it right. */
internal class PillsCheck(val title: Int, val ok: Boolean, val fix: Intent?)

internal fun pillsHealth(c: android.content.Context): List<PillsCheck> {
    val pkg = Uri.parse("package:${c.packageName}")
    val power = c.getSystemService(android.os.PowerManager::class.java)
    val am = c.getSystemService(android.app.AlarmManager::class.java)
    return listOf(
        PillsCheck(R.string.pills_check_notifications, androidx.core.app.NotificationManagerCompat.from(c).areNotificationsEnabled() &&
            c.getSystemService(android.app.NotificationManager::class.java).getNotificationChannel(PillsAlarms.CHANNEL)?.importance != android.app.NotificationManager.IMPORTANCE_NONE,
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)),
        PillsCheck(R.string.pills_check_alarms, android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms(), Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)),
        // HyperOS: "No restrictions" in battery saver, and Autostart, both on the app's page
        PillsCheck(R.string.pills_check_battery, power.isIgnoringBatteryOptimizations(c.packageName), Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)),
    )
}

/** Settings › Pills: the voice, how it calls the user, Try it, and the checks (the user, 7 Nov: settings in Settings, not a gear). */
@Composable internal fun PillsSettingsPage() {
    val c = LocalContext.current
    val prefs = remember { PillsAlarms.prefs(c) }
    var voice by remember { mutableStateOf(PillsAlarms.voice(c)) }
    var private by remember { mutableStateOf(PillsAlarms.private(c)) }
    var here by remember { mutableStateOf(PillsAlarms.ringsHere(c)) }
    var call by remember { mutableStateOf(PillsAlarms.callName(c)) }
    var checked by remember { mutableIntStateOf(0) }
    val health = remember(checked) { pillsHealth(c) }
    // back from a fix in Android's settings: checked again
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) checked++ }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.pills_here), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.pills_here_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(here, { here = it; PillsAlarms.setRingsHere(c, it) })
            }
        }
        if (here) Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.pills_private), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.pills_private_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(private, { private = it; prefs.edit().putBoolean("private", it).apply() })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.pills_voice), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.pills_voice_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(voice, { voice = it; prefs.edit().putBoolean("voice", it).apply() })
                }
                if (voice) OutlinedTextField(call, { call = it; prefs.edit().putString("call", it.trim()).apply() }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.pills_call_name)) },
                    placeholder = { Text(stringResource(R.string.pills_call_name_hint)) }, singleLine = true)
                FilledTonalButton(onClick = { PillsAlarms.test(c, Pills.medicines(Pills.phoneDir()).firstOrNull { !it.archived }) }) {
                    Icon(painterResource(R.drawable.ic_volume_up), null, Modifier.size(18.dp)); Text(" " + stringResource(R.string.pills_try))
                }
            }
        }
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.pills_checks), style = MaterialTheme.typography.titleMedium)
                health.forEach { h ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(if (h.ok) R.drawable.ic_check else R.drawable.ic_close), null, tint = if (h.ok) PILLS_COLOR else MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                        Text(stringResource(h.title), Modifier.weight(1f).padding(start = 10.dp))
                        if (!h.ok && h.fix != null) TextButton(onClick = { runCatching { c.startActivity(h.fix.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text(stringResource(R.string.pills_fix)) }
                    }
                }
                Text(stringResource(R.string.pills_checks_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
