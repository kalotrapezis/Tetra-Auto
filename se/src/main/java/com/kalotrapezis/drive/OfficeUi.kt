package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.layout.layoutId
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Words', Tables' and Slides' own settings (asked 16 Oct): where the ribbon stands and how big its tools are — by
 * themselves, at the top while a mouse is in use (as a PC's Office) and at the bottom for a finger (in reach of a hand).
 */
internal object OfficeUi {
    /** "auto": at the top while a mouse is in use, at the bottom for a finger; "bottom"; "top". */
    var place by mutableStateOf("auto"); private set
    /** "auto": compact while a mouse is in use; "normal"; "compact". */
    var size by mutableStateOf("auto"); private set
    /** A mouse moved or clicked last (a finger's or a pen's touch sets it back). */
    var mouse by mutableStateOf(false)
    /** The ribbon's chosen tab: "bubble" behind it or "dash" under it (asked 17 Oct); compact always the dash, for the room. */
    var marker by mutableStateOf("bubble"); private set
    val top get() = place == "top" || (place == "auto" && mouse)
    val compact get() = size == "compact" || (size == "auto" && mouse)

    private fun prefs(context: Context) = context.getSharedPreferences("office", Context.MODE_PRIVATE)
    fun load(context: Context) {
        val p = prefs(context)
        place = p.getString("ribbonPlace", "auto") ?: "auto"; size = p.getString("ribbonSize", "auto") ?: "auto"
        marker = p.getString("tabMarker", "bubble") ?: "bubble"
    }
    fun set(context: Context, place: String = this.place, size: String = this.size, marker: String = this.marker) {
        this.place = place; this.size = size; this.marker = marker
        prefs(context).edit().putString("ribbonPlace", place).putString("ribbonSize", size).putString("tabMarker", marker).apply()
        WriterLog.i("Office settings: ribbon $place, $size, tab marker $marker")
    }
}

/** A mouse or a finger noticed anywhere inside, before the screen's own handling sees it (nothing is consumed). */
internal fun Modifier.noticePointer(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            when (e.changes.firstOrNull()?.type) {
                PointerType.Mouse -> if (!OfficeUi.mouse) { OfficeUi.mouse = true; WriterLog.i("a mouse in use") }
                PointerType.Touch, PointerType.Stylus -> if (OfficeUi.mouse && e.type == PointerEventType.Press) { OfficeUi.mouse = false; WriterLog.i("a finger in use") }
                else -> Unit
            }
        }
    }
}

/** File › Settings: a centred panel — where the ribbon stands and how big its tools are. */
@Composable internal fun OfficeSettingsDialog(dismiss: () -> Unit) {
    val context = LocalContext.current
    @Composable fun Choices(title: String, now: String, choices: List<Pair<String, String>>, pick: (String) -> Unit) = Column(Modifier.padding(bottom = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((key, label) in choices) FilterChip(selected = now == key, onClick = { pick(key) }, label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) })
        }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.common_settings)) }, text = {
        Column {
            Choices(stringResource(R.string.office_settings_ribbon), OfficeUi.place, listOf("auto" to stringResource(R.string.office_by_itself), "bottom" to stringResource(R.string.office_at_bottom), "top" to stringResource(R.string.office_at_top))) { OfficeUi.set(context, place = it) }
            Choices(stringResource(R.string.office_settings_tools), OfficeUi.size, listOf("auto" to stringResource(R.string.office_by_itself), "normal" to stringResource(R.string.office_size_normal), "compact" to stringResource(R.string.office_size_compact))) { OfficeUi.set(context, size = it) }
            Choices(stringResource(R.string.office_settings_marker), OfficeUi.marker, listOf("bubble" to stringResource(R.string.office_marker_bubble), "dash" to stringResource(R.string.office_marker_dash))) { OfficeUi.set(context, marker = it) }
            Text(stringResource(R.string.office_settings_note), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_done)) } })
}

/** The round icon-only tools' size ([Tool]): 40 dp, and Android's 48 dp inside an Office ribbon whose tools are Normal size
 *  (the user, 18 Oct: the Office settings decide — Compact keeps the smaller look). Notes and the text editor keep 40 dp. */
internal val LocalToolSize = androidx.compose.runtime.staticCompositionLocalOf { 40.dp }
/** [content] as a ribbon's tools: icon-only tools 48 dp unless the tools are Compact. */
@Composable internal fun RibbonTools(content: @Composable () -> Unit) =
    androidx.compose.runtime.CompositionLocalProvider(LocalToolSize provides if (OfficeUi.compact) 40.dp else 48.dp, content = content)

/** The ribbon's tab row, one for Words, Tables and Slides: the chosen tab under a bubble or over a dash (OfficeUi.marker),
 *  scrolled clear of the edge's shade. [contextual] (Picture, Column, …) has a colour of its own; [lead] goes before the tabs. */
@Composable internal fun <T> OfficeTabRow(tabs: List<T>, shown: T, w: Color, dash: Color, pick: (T) -> Unit, contextual: T? = null,
    // the contextual tab: last, in a lighter tone of the app's colour, in every app (17 Oct)
    contextualColour: Color = androidx.compose.ui.graphics.lerp(dash, Color.White, 0.45f), label: (T) -> String = { it.toString() }, lead: @Composable () -> Unit = {}) {
    val bubble = OfficeUi.marker == "bubble" && !OfficeUi.compact
    // Normal size: 2 dp under the tabs, so the tools' touch areas below (48 dp round 40 dp buttons) do not reach into them
    Row(Modifier.fillMaxWidth().shadedRow().padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = if (OfficeUi.compact) 0.dp else 2.dp), verticalAlignment = Alignment.CenterVertically) {
        lead()
        tabs.forEach { t ->
            val on = t == shown
            val mark = if (t == contextual) contextualColour else dash
            // The chosen tab scrolls clear of the edge's shade (Design sat half under it on a phone).
            val seen = remember { BringIntoViewRequester() }
            var wide by remember { mutableStateOf(0) }
            val shade = with(LocalDensity.current) { 48.dp.toPx() }
            LaunchedEffect(on, wide) { if (on && wide > 0) seen.bringIntoView(Rect(-shade, 0f, wide + shade, 1f)) }
            val m = Modifier.bringIntoViewRequester(seen).onSizeChanged { wide = it.width }
            // the bubble in the app's colour, as its dash (asked 17 Oct); the light contextual colour takes dark letters
            // Normal size: each tab 48 dp tall, Android's least for a finger (the user, 18 Oct); Compact as small as before
            if (bubble) Box(m.heightIn(min = 48.dp).clip(CircleShape).clickable { pick(t) }, contentAlignment = Alignment.Center) {
                Text(label(t), color = if (!on) w else if (t == contextual) Color.Black else Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(2.dp).clip(CircleShape).background(if (on) mark else Color.Transparent).padding(horizontal = 14.dp, vertical = 7.dp)) }
            else Column(m.heightIn(min = if (OfficeUi.compact) 0.dp else 48.dp).clickable { pick(t) }.padding(horizontal = 10.dp, vertical = if (OfficeUi.compact) 3.dp else 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(label(t), style = MaterialTheme.typography.labelLarge, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, color = if (on) w else w.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.padding(top = 3.dp).width(if (on) 24.dp else 0.dp).height(3.dp).background(mark))
            }
        }
    }
}

/** A message for a moment, the same in Words, Tables and Slides (Words' look, 17 Oct): a black pill at the top for 2.8 s, written in the log. */
@Composable internal fun androidx.compose.foundation.layout.BoxScope.OfficeMessage(m: String?, top: androidx.compose.ui.unit.Dp, gone: () -> Unit) {
    m ?: return
    LaunchedEffect(m) { WriterLog.i("said: $m"); kotlinx.coroutines.delay(2800); gone() }
    androidx.compose.material3.Surface(color = Color.Black, contentColor = Color.White, shape = CircleShape,
        modifier = Modifier.align(Alignment.TopCenter).padding(top = top, start = 16.dp, end = 16.dp)) {
        Text(m, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

/** One set of colours for Words, Tables and Slides (17 Oct): the words' colour (white for dark fills and slides) … */
internal val TEXT_COLOURS = listOf(Color.Black, Color(0xFFC00000), Color(0xFF0070C0), Color(0xFF00B050), Color(0xFF7030A0), Color(0xFFED7D31), Color.White)
/** … the highlighter's … */
internal val HIGHLIGHT_COLOURS = listOf(Color(0xFFFFFF00), Color(0xFF00FF00), Color(0xFF00FFFF), Color(0xFFFF00FF))
/** … and a cell's fill (Words' table shading, Tables' Fill): yellow, Excel's good and bad, and light tints. Shapes: SHAPE_COLOURS. */
internal val CELL_FILLS = listOf(0xFFFFFF00, 0xFFC6EFCE, 0xFFFFC7CE, 0xFFDDEBF7, 0xFFFCE4D6, 0xFFFFF2CC, 0xFFE2EFD9, 0xFFD9D9D9).map { Color(it) }

/** The font in use, its gallery a tap away — one look in every app (Words' outlined name). */
@Composable internal fun FontButton(name: String?, w: Color, enabled: Boolean = true, click: () -> Unit) =
    RibbonButton((name ?: stringResource(R.string.office_font)).take(18), w, enabled = enabled, outlined = true, click = click)

/** The size: − and + a point at a time round the number (fixed width, so two digits fit as one does), as Words' Home. */
@Composable internal fun SizeStepper(size: String, w: Color, smaller: Boolean = true, bigger: Boolean = true, shrink: () -> Unit, grow: () -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically) {
        Tool(R.drawable.ic_remove, stringResource(R.string.office_smaller_text), w, enabled = smaller, click = shrink)
        Text(size, color = w, style = MaterialTheme.typography.labelLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1, modifier = Modifier.width(26.dp))
        Tool(R.drawable.ic_add, stringResource(R.string.office_bigger_text), w, enabled = bigger, click = grow)
    }

/** A strip's head, in the ribbon's place (Tables' Borders and Number, Slides' Options and Effect; 17 Oct: all alike): ‹ back, its name. */
@Composable internal fun StripHead(title: String, w: Color, back: () -> Unit) {
    androidx.compose.material3.IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_back), tint = w) }
    Text(title, color = w, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 8.dp))
}

/**
 * A list of choices' own strip on a tablet or phone (the user, 18 Oct: "I dont like over UI list when not in desktop. Keep it
 * as is for Desktop but tablet and phone make a riben for it at the bottom like the pages one"): the ribbon slides down, the
 * strip slides up in its place, ‹ and its name first. A preset is picked, then ✓ keeps it and × (or ‹, or Back) leaves it;
 * an action strip ([ok] null) acts at the tap and goes back. Opened through [officeStrip] by a ribbon's tool; one at a time.
 */
internal class OfficeStrip(val key: Any, val title: String) {
    var content: @Composable (OfficeStrip) -> Unit by mutableStateOf({ _ -> })
    var ok: ((OfficeStrip) -> Unit)? by mutableStateOf(null)
    var cancel: () -> Unit = {}
    /** the preset tapped, shown chosen until ✓ */
    var picked by mutableStateOf<Any?>(null)
}
internal class OfficeStrips {
    var open by mutableStateOf<OfficeStrip?>(null)
    fun cancel() { open?.cancel?.invoke(); open = null }
    fun ok() { open?.let { s -> s.ok?.invoke(s) }; open = null }
}
internal val LocalOfficeStrips = androidx.compose.runtime.staticCompositionLocalOf<OfficeStrips?> { null }

/**
 * What opens a strip of choices in the ribbon's place — null with the ribbon at the top (a desktop keeps its menus) or with no
 * [OfficeStripFrame] round the ribbon. Its [content] and [ok] are kept fresh while the tool's ribbon is there (hidden under it).
 */
@Composable internal fun officeStrip(title: String, ok: ((OfficeStrip) -> Unit)?, cancel: () -> Unit = {}, content: @Composable (OfficeStrip) -> Unit): (() -> Unit)? {
    val strips = LocalOfficeStrips.current
    val key = remember { Any() }
    val mine = strips?.open?.takeIf { it.key === key }
    androidx.compose.runtime.SideEffect { mine?.let { it.content = content; it.ok = ok; it.cancel = cancel } }
    // its tool gone (another tab, nothing chosen): its strip with it
    androidx.compose.runtime.DisposableEffect(strips) { onDispose { if (strips?.open?.key === key) strips.open = null } }
    if (strips == null || OfficeUi.top) return null
    return { strips.open = OfficeStrip(key, title).also { it.content = content; it.ok = ok; it.cancel = cancel } }
}

/** Round a ribbon (its tab row and tools): a strip of choices ([officeStrip]) slides up in its place while the ribbon slides down. */
@Composable internal fun OfficeStripFrame(w: Color, modifier: Modifier = Modifier, ribbon: @Composable () -> Unit) {
    val strips = remember { OfficeStrips() }
    val open = strips.open
    val last = remember { arrayOfNulls<OfficeStrip>(1) }
    if (open != null) last[0] = open
    val t by androidx.compose.animation.core.animateFloatAsState(if (open != null) 1f else 0f, androidx.compose.animation.core.tween(180), label = "strip")
    androidx.activity.compose.BackHandler(open != null) { strips.cancel() }
    LaunchedEffect(OfficeUi.top) { if (OfficeUi.top) strips.cancel() }
    androidx.compose.runtime.CompositionLocalProvider(LocalOfficeStrips provides strips) {
        // the ribbon stays composed under the strip (its tools keep the strip's choices fresh), only not placed
        androidx.compose.ui.layout.Layout({
            Box { ribbon() }
            Box { val st = open ?: last[0]; if (st != null && t > 0f) {
                @Composable fun choices(m: Modifier) = Box(m) { RibbonLine(androidx.compose.foundation.rememberScrollState()) { st.content(st) } }
                @Composable fun okCancel() { if (st.ok != null) RibbonTools {
                    Tool(R.drawable.ic_close, stringResource(R.string.common_cancel), w) { strips.cancel() }
                    Tool(R.drawable.ic_check, stringResource(R.string.common_apply), w, enabled = st.picked != null) { strips.ok() }
                } }
                // a phone: the name, × and ✓ where the tabs were, the choices under them (all the width for them)
                if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 700) Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { StripHead(st.title, w) { strips.cancel() }; Box(Modifier.weight(1f)); okCancel() }
                    choices(Modifier.fillMaxWidth())
                } else Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    StripHead(st.title, w) { strips.cancel() }
                    choices(Modifier.weight(1f))
                    okCancel()
                }
            } }
        }, modifier.clipToBounds()) { ms, c ->
            val r = ms[0].measure(c); val s = ms[1].measure(c)
            val h = Math.round(r.height + (s.height - r.height) * t)
            layout(c.constrainWidth(maxOf(r.width, s.width)), h) {
                if (t < 1f) r.place(0, Math.round(maxOf(r.height, s.height) * t))
                if (t > 0f) s.place(0, h - Math.round(s.height * t))
            }
        }
    }
}

/** A choice of a [RibbonMenu]: its words, ✓ when it is the one in use; [opens] acts at the tap even in a preset strip (a window:
 *  More…, Custom…; a removal); [detail] a desktop menu's second line; [icon] its icon. */
internal class MenuChoice(val label: String, val checked: Boolean = false, val enabled: Boolean = true, val opens: Boolean = false,
    val detail: String? = null, val icon: Int? = null, val act: () -> Unit)

/**
 * A ribbon tool with a list of choices: a menu under it on a desktop, its own strip on a tablet or phone ([officeStrip]) —
 * [preset]s (sizes, spacings, looks) picked, then ✓; actions (Clear all, Shift cells down…) at the tap, the ribbon back.
 */
@Composable internal fun RibbonMenu(label: String, w: Color, icon: Int? = null, on: Boolean = false, enabled: Boolean = true, preset: Boolean = true,
    title: String = label, choices: List<MenuChoice>) {
    val strips = LocalOfficeStrips.current
    val strip = officeStrip(title, ok = if (preset) { s -> choices.firstOrNull { it.label == s.picked }?.act?.invoke() } else null) { s ->
        choices.forEach { ch -> RibbonButton(ch.label, w, ch.icon, enabled = ch.enabled, on = if (s.picked != null) s.picked == ch.label else ch.checked) {
            if (preset && !ch.opens) s.picked = ch.label else { strips?.open = null; ch.act() } } }
    }
    if (strip != null) RibbonButton(label, w, icon, enabled = enabled, on = on) { strip() }
    else Box {
        var open by remember { mutableStateOf(false) }
        RibbonButton(label, w, icon, enabled = enabled, on = on) { open = true }
        DropdownMenu(open, { open = false }) {
            choices.forEach { ch -> DropdownMenuItem(text = { if (ch.detail == null) Text(ch.label) else Column { Text(ch.label); Text(ch.detail, style = MaterialTheme.typography.bodySmall) } },
                leadingIcon = ch.icon?.let { i -> { Icon(painterResource(i), null, Modifier.size(20.dp)) } },
                trailingIcon = { if (ch.checked) Icon(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp)) },
                enabled = ch.enabled, onClick = { open = false; ch.act() }) }
        }
    }
}

/** File › Versions in Tables and Slides: the file as it was before each save (the last 3, [keepPrevious]); [open] one. */
@Composable internal fun VersionsDialog(file: java.io.File, goBack: String, dismiss: () -> Unit, about: String? = null, open: (java.io.File) -> Unit) {
    val context = LocalContext.current
    val list = remember { versions(context, file) }
    val format = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.office_versions)) },
        text = {
            Column {
                Text(if (list.isEmpty()) stringResource(R.string.office_versions_none) else about ?: stringResource(R.string.office_versions_about, goBack),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                list.forEach { v ->
                    Row(Modifier.fillMaxWidth().clickable { open(v) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_history), contentDescription = null, modifier = Modifier.size(22.dp))
                        Text(stringResource(R.string.office_version_line, format.format(java.util.Date(v.name.substringBefore('.').toLongOrNull() ?: v.lastModified())), (v.length() + 1023) / 1024),
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(start = 12.dp))
                        Text(stringResource(R.string.common_open), color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_close)) } })
}

/** A right-click's menu where it was clicked ([at], from the parent's top left): rounded, its items smaller with a mouse. */
@Composable internal fun OfficeMenu(at: Offset, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) =
    Box(Modifier.offset { IntOffset(at.x.toInt(), at.y.toInt()) }) {
        DropdownMenu(expanded = true, onDismissRequest = dismiss, shape = RoundedCornerShape(12.dp), content = content)
    }

/** An item of [OfficeMenu]: its icon and words. */
@Composable internal fun OfficeMenuItem(label: String, icon: Int, enabled: Boolean = true, click: () -> Unit) {
    val small = OfficeUi.mouse
    DropdownMenuItem(text = { Text(label, style = if (small) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge) },
        leadingIcon = { Icon(painterResource(icon), null, Modifier.size(if (small) 18.dp else 24.dp)) }, enabled = enabled, onClick = click,
        modifier = if (small) Modifier.height(34.dp) else Modifier,
        contentPadding = if (small) PaddingValues(horizontal = 12.dp) else MenuDefaults.DropdownMenuItemContentPadding)
}

/** The ribbon's groups' separator ([RibbonDivider]): where [RibbonRows] may go on to the next row. */
internal const val RIBBON_DIVIDER = "ribbon-divider"

/** A desktop's ribbon (asked 16 Oct): at the top, on a wide screen — its tools on rows, not one sliding line. */
@Composable internal fun ribbonRows() = OfficeUi.top && androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 840

/**
 * A ribbon's tools on as many rows as they take, a group (the tools between two [RIBBON_DIVIDER]s) kept whole: one
 * that does not fit goes to the next row, its separator left out there (asked 16 Oct: "stacked properly", not cut mid-group).
 */
@Composable internal fun RibbonRows(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    androidx.compose.ui.layout.Layout({ RibbonTools(content) }, modifier) { ms, c ->
        val loose = androidx.compose.ui.unit.Constraints(maxHeight = c.maxHeight)
        val ps = ms.map { it.measure(loose) }
        val divider = ms.map { it.layoutId == RIBBON_DIVIDER }
        // the groups, each with the separator before it
        val groups = ArrayList<Pair<Int?, List<Int>>>(); var sep: Int? = null; var items = ArrayList<Int>()
        ps.indices.forEach { i -> if (divider[i]) { if (items.isNotEmpty()) groups += sep to items; sep = i; items = ArrayList() } else items += i }
        if (items.isNotEmpty()) groups += sep to items
        val rows = ArrayList<ArrayList<Pair<Int?, List<Int>>>>(); var x = 0
        for ((s, g) in groups) {
            val w = g.sumOf { ps[it].width }; val sw = s?.let { ps[it].width } ?: 0
            if (rows.isEmpty() || (x > 0 && x + sw + w > c.maxWidth)) { rows += arrayListOf(null to g); x = w } else { rows.last() += s to g; x += sw + w }
        }
        val heights = rows.map { r -> r.maxOf { (s, g) -> maxOf(s?.let { ps[it].height } ?: 0, g.maxOf { ps[it].height }) } }
        layout(c.maxWidth, heights.sum()) {
            var y = 0
            rows.forEachIndexed { k, r ->
                var px = 0
                for ((s, g) in r) for (i in listOfNotNull(s) + g) { ps[i].placeRelative(px, y + (heights[k] - ps[i].height) / 2); px += ps[i].width }
                y += heights[k]
            }
        }
    }

/** A ribbon's tools: rows of whole groups on a desktop ([ribbonRows]), else one sliding line, faded where there is more. */
@Composable internal fun RibbonLine(scroll: androidx.compose.foundation.ScrollState, modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    if (ribbonRows()) RibbonRows(Modifier.fillMaxWidth().then(modifier), content)
    // from the left as Words' and Office's (the tablet walk, 18 Oct: some centred, some not), and as tall in every tab — the tools'
    // height, so the tab row does not move between tabs
    else androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().scrollShade(scroll).horizontalScroll(scroll).then(modifier).heightIn(min = if (OfficeUi.compact) 40.dp else 48.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) { RibbonTools(content) }

/** navigationBarsPadding, plus room for the window's handle: a floating or split window has no navigation bar of its
 *  own, but the system draws its handle over the window's bottom (asked 16 Oct: it sat on the ribbon's tools). */
@Composable internal fun Modifier.bottomBarPadding(): Modifier {
    androidx.compose.ui.platform.LocalConfiguration.current // entering and leaving a window comes as a configuration change
    val windowed = (androidx.compose.ui.platform.LocalContext.current as? android.app.Activity)?.isInMultiWindowMode == true
    val noBar = WindowInsets.navigationBars.getBottom(androidx.compose.ui.platform.LocalDensity.current) == 0
    return navigationBarsPadding().then(if (windowed && noBar) Modifier.padding(bottom = 12.dp) else Modifier)
}

/** Find and Replace's second row (Words', shared with Slides and Tables): the words to put in, Replace and All. */
@androidx.compose.runtime.Composable
internal fun ReplaceRow(w: Color, replacement: String, setReplacement: (String) -> Unit, found: Boolean, replace: () -> Unit, replaceAll: () -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        androidx.compose.material3.OutlinedTextField(replacement, setReplacement, singleLine = true, placeholder = { androidx.compose.material3.Text(stringResource(R.string.office_replace_with)) },
            modifier = androidx.compose.ui.Modifier.weight(1f))
        RibbonButton(stringResource(R.string.office_replace), w, enabled = found) { replace() }
        RibbonButton(stringResource(R.string.common_all), w, enabled = found) { replaceAll() }
    }

/**
 * Find and Replace (Words' bar, shared with Slides): the words looked for with ✕, how many and which, ‹ ›, and Replace's own row
 * ([replacing]) with Replace and All. Tables keeps its own find (all sheets, Go To) with this bar's [ReplaceRow].
 */
@Composable
internal fun FindReplaceBar(w: Color, query: String, setQuery: (String) -> Unit, count: String, found: Boolean, previous: () -> Unit, next: () -> Unit,
    replacing: Boolean, setReplacing: (Boolean) -> Unit, replacement: String, setReplacement: (String) -> Unit, replace: () -> Unit, replaceAll: () -> Unit, close: () -> Unit) {
    Column(androidx.compose.ui.Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tool(R.drawable.ic_close, stringResource(R.string.office_close_find), w) { close() }
            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            androidx.compose.material3.OutlinedTextField(query, setQuery, singleLine = true, placeholder = { androidx.compose.material3.Text(stringResource(R.string.office_find)) },
                modifier = androidx.compose.ui.Modifier.weight(1f).focusRequester(focus), suffix = { androidx.compose.material3.Text(count) })
            Tool(R.drawable.ic_keyboard_arrow_up, stringResource(R.string.common_previous), w, enabled = found) { previous() }
            Box(androidx.compose.ui.Modifier.graphicsLayer { rotationZ = 180f }) { Tool(R.drawable.ic_keyboard_arrow_up, stringResource(R.string.common_next), w, enabled = found) { next() } }
            Tool(R.drawable.ic_swap_horiz, stringResource(R.string.office_replace), w, on = replacing) { setReplacing(!replacing) }
        }
        if (replacing) ReplaceRow(w, replacement, setReplacement, found, replace, replaceAll)
    }
}

/**
 * Review › Spelling's dialog (Tables' and Slides', as Excel's and PowerPoint's): the word not in the dictionary, where it is
 * ([at]), the suggestions to choose from; Change (when [canChange]), Ignore, Ignore all, Close.
 */
@Composable
internal fun SpellingDialog(word: String, at: String, offered: List<String>, canChange: Boolean, change: (String) -> Unit, ignore: () -> Unit, ignoreAll: () -> Unit, close: () -> Unit) {
    var choice by androidx.compose.runtime.remember(word, at) { androidx.compose.runtime.mutableStateOf(offered.firstOrNull()) }
    androidx.compose.material3.AlertDialog(onDismissRequest = close, title = { androidx.compose.material3.Text(stringResource(R.string.office_spelling)) },
        text = { androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.Text(stringResource(R.string.office_not_in_dictionary, word), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            androidx.compose.material3.Text(at, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, maxLines = 3)
            if (offered.isEmpty()) androidx.compose.material3.Text(stringResource(R.string.office_no_suggestions))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                offered.forEach { o -> androidx.compose.material3.FilterChip(choice == o, { choice = o }, { androidx.compose.material3.Text(o, maxLines = 1, overflow = TextOverflow.Ellipsis) }) } } } },
        confirmButton = { androidx.compose.foundation.layout.Row {
            androidx.compose.material3.TextButton(enabled = choice != null && canChange, onClick = { choice?.let(change) }) { androidx.compose.material3.Text(stringResource(R.string.office_change)) }
            androidx.compose.material3.TextButton(onClick = ignore) { androidx.compose.material3.Text(stringResource(R.string.office_ignore)) }
            androidx.compose.material3.TextButton(onClick = ignoreAll) { androidx.compose.material3.Text(stringResource(R.string.office_ignore_all)) } } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = close) { androidx.compose.material3.Text(stringResource(R.string.common_close)) } })
}
