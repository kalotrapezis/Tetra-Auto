package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.height
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Draw (asked 2026-09-29, Word's and OnlyOffice's Draw tab — a drawing box first): a box to draw in with a finger or
 * the tablet's pen — two pens and a highlighter, each its colour and width in mm, an eraser that takes a whole stroke —
 * put in front of the text as a picture (a PNG, see-through where nothing is drawn), so Word and LibreOffice show it too.
 * Strokes are kept in the box's own units: its width is 1.
 */
internal class InkStroke(val points: List<Offset>, val color: Int, val widthMm: Float, val highlighter: Boolean)

internal object InkPicture {
    /** The strokes cut to what they cover (their width round them) in a box [widthPt] wide: moved into it, and where it is in the box. */
    fun cut(strokes: List<InkStroke>, aspect: Float, widthPt: Float): Pair<List<InkStroke>, androidx.compose.ui.geometry.Rect> {
        val mm = widthPt / 72f * 25.4f // millimetres across the box
        var x0 = 1f; var y0 = aspect; var x1 = 0f; var y1 = 0f
        for (s in strokes) { val r = s.widthMm / mm * 0.75f; for (p in s.points) { x0 = minOf(x0, p.x - r); y0 = minOf(y0, p.y - r); x1 = maxOf(x1, p.x + r); y1 = maxOf(y1, p.y + r) } }
        val box = androidx.compose.ui.geometry.Rect(x0.coerceAtLeast(0f), y0.coerceAtLeast(0f), x1.coerceAtMost(1f), y1.coerceAtMost(aspect))
        val bw = box.width.coerceAtLeast(1e-4f)
        return strokes.map { s -> InkStroke(s.points.map { Offset((it.x - box.left) / bw, (it.y - box.top) / bw) }, s.color, s.widthMm, s.highlighter) } to box
    }

    /** The drawing as a PNG [px] wide ([aspect] = height / width), strokes at their width for a box [widthPt] wide. */
    fun png(strokes: List<InkStroke>, aspect: Float, widthPt: Float, px: Int = 1600): ByteArray {
        val w = px; val h = (px * aspect).toInt().coerceAtLeast(1)
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val perMm = px / (widthPt / 72f * 25.4f) // pixels in a millimetre of the page
        for (s in strokes) {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE; strokeCap = if (s.highlighter) android.graphics.Paint.Cap.SQUARE else android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND; strokeWidth = s.widthMm * perMm
                color = if (s.highlighter) (s.color and 0xFFFFFF) or 0x66000000 else s.color
            }
            val path = android.graphics.Path()
            s.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x * w, p.y * w) else path.lineTo(p.x * w, p.y * w) }
            if (s.points.size == 1) path.lineTo(s.points[0].x * w + 0.1f, s.points[0].y * w)
            canvas.drawPath(path, paint)
        }
        return withStrokes(java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray(), strokes, aspect)
    }

    /*
     * The strokes ride inside the PNG (a tEXt chunk Word and LibreOffice keep with the picture), so a drawing can be
     * drawn on again after the file is closed: "aspect|color,mm,h:x y x y…;…" in the picture's widths.
     */
    private const val KEY = "tetra-ink"
    fun withStrokes(png: ByteArray, strokes: List<InkStroke>, aspect: Float): ByteArray {
        val text = "%.5f".format(java.util.Locale.ROOT, aspect) + "|" + strokes.joinToString(";") { s ->
            "${s.color},${s.widthMm},${if (s.highlighter) 1 else 0}:" + s.points.joinToString(" ") { "%.4f %.4f".format(java.util.Locale.ROOT, it.x, it.y) } }
        val data = (KEY + "\u0000" + text).toByteArray(Charsets.ISO_8859_1)
        val body = "tEXt".toByteArray(Charsets.ISO_8859_1) + data
        val crc = java.util.zip.CRC32().apply { update(body) }.value
        val chunk = java.nio.ByteBuffer.allocate(4 + body.size + 4).putInt(data.size).put(body).putInt(crc.toInt()).array()
        val afterHeader = 8 + 25 // the signature, then IHDR (13 bytes and its length, type and CRC)
        return png.copyOfRange(0, afterHeader) + chunk + png.copyOfRange(afterHeader, png.size)
    }
    /** The strokes a drawing made in Writer keeps, in its widths, and the height / width it was drawn at; null: not one. */
    fun strokesOf(png: ByteArray): Pair<List<InkStroke>, Float>? {
        if (png.size < 8 || png[1] != 'P'.code.toByte()) return null
        val b = java.nio.ByteBuffer.wrap(png)
        var at = 8
        while (at + 8 <= png.size) {
            val len = b.getInt(at); val type = String(png, at + 4, 4, Charsets.ISO_8859_1)
            if (len < 0 || at + 12 + len > png.size) return null
            if (type == "tEXt") {
                val t = String(png, at + 8, len, Charsets.ISO_8859_1)
                if (t.startsWith(KEY + "\u0000")) return runCatching {
                    val (aspect, list) = t.substring(KEY.length + 1).split('|', limit = 2)
                    list.split(';').filter { it.isNotEmpty() }.map { one ->
                        val (head, pts) = one.split(':', limit = 2)
                        val (c, mm, h) = head.split(',')
                        val n = pts.split(' ').map { it.toFloat() }
                        InkStroke(n.chunked(2).map { Offset(it[0], it[1]) }, c.toInt(), mm.toFloat(), h == "1")
                    } to aspect.toFloat()
                }.getOrNull()
            }
            if (type == "IDAT" || type == "IEND") return null // written before the picture's data
            at += 12 + len
        }
        return null
    }
}

private enum class Tool { Pen, Pen2, Highlighter, Eraser, Move }

/**
 * The drawing box ([boxAspect] = height / width, [boxPt] wide), or with [pages] a page itself as the pages show it
 * (asked 2026-09-30: drawn on the document, the page underneath to see how big it comes out), [startPage] first, the
 * others a tap away while nothing is drawn. [initial]: the strokes of a drawing drawn on again, in the box's (or the
 * page's) widths. [insert] gets the PNG, its size in points and, on a page, its top left there — the drawing cut to
 * what was drawn — and the page it was drawn on.
 */
@Composable
internal fun DrawDialog(boxPt: Float, dismiss: () -> Unit, pages: List<LaidPage> = emptyList(), startPage: Int = 0, boxAspect: Float = 0.6f,
    initial: List<InkStroke> = emptyList(),
    /** Slides (17 Oct): a slide to draw on as a page is — its width, height (points) and drawing */ slide: Triple<Float, Float, (android.graphics.Canvas) -> Unit>? = null,
    insert: (ByteArray, Float, Float, Pair<Float, Float>?, Int) -> Unit) {
    var pageAt by remember { mutableIntStateOf(startPage) }
    val page = pages.getOrNull(pageAt)
    val onPage = page != null || slide != null
    val widthPt = page?.page?.width ?: slide?.first ?: boxPt
    val aspect = page?.let { it.page.height / it.page.width } ?: slide?.let { it.second / it.first } ?: boxAspect
    val strokes = remember { mutableStateListOf<InkStroke>().apply { addAll(initial) } }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var tool by remember { mutableStateOf(Tool.Pen) }
    // Each pen keeps its own colour and width (Word's pens).
    // Remembered from one drawing to the next, as Word keeps its pens.
    val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("writer", android.content.Context.MODE_PRIVATE)
    val kept = remember { prefs.getString("pens", null)?.split(';')?.mapNotNull { runCatching { it.split(',').let { (c, w) -> c.toInt() to w.toFloat() } }.getOrNull() }?.takeIf { it.size == 3 } }
    val colors = remember { mutableStateListOf(*(kept?.map { it.first } ?: listOf(0xFF000000.toInt(), 0xFF1A73E8.toInt(), 0xFFFFC114.toInt())).toTypedArray()) }
    val widths = remember { mutableStateListOf(*(kept?.map { it.second } ?: listOf(0.5f, 1f, 5f)).toTypedArray()) }
    androidx.compose.runtime.LaunchedEffect(colors.toList(), widths.toList()) { prefs.edit().putString("pens", colors.indices.joinToString(";") { "${colors[it]},${widths[it]}" }).apply() }
    var last by remember { mutableStateOf(Offset.Zero) } // Move: where the finger was
    val palette = listOf(0xFF000000, 0xFF1A73E8, 0xFFE53935, 0xFF43A047, 0xFF8E24AA, 0xFFFF9800, 0xFFFFC114, 0xFF00BCD4).map { it.toInt() }
    val slot = when (tool) { Tool.Pen -> 0; Tool.Pen2 -> 1; Tool.Highlighter -> 2; Tool.Eraser, Tool.Move -> -1 }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.widthIn(max = 900.dp).systemBarsPadding().padding(12.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!onPage) Text(stringResource(R.string.words_tab_draw), style = MaterialTheme.typography.titleLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Choice(stringResource(R.string.words_pen), tool == Tool.Pen) { tool = Tool.Pen }
                    Choice(stringResource(R.string.words_pen_2), tool == Tool.Pen2) { tool = Tool.Pen2 }
                    Choice(stringResource(R.string.words_highlighter), tool == Tool.Highlighter) { tool = Tool.Highlighter }
                    Choice(stringResource(R.string.words_eraser), tool == Tool.Eraser) { tool = Tool.Eraser }
                    Choice(stringResource(R.string.common_move), tool == Tool.Move) { tool = Tool.Move } // the whole drawing, dragged
                    Choice(stringResource(R.string.common_undo), false) { strokes.removeLastOrNull() }
                    // Another page: while nothing is drawn on this one (a drawing stays on its page).
                    if (pages.size > 1) {
                        Choice("‹", false) { if (strokes.isEmpty() && pageAt > 0) { pageAt--; zoom = 1f; pan = Offset.Zero } }
                        Text(stringResource(R.string.words_page_of, pageAt + 1, pages.size), style = MaterialTheme.typography.labelLarge)
                        Choice("›", false) { if (strokes.isEmpty() && pageAt < pages.lastIndex) { pageAt++; zoom = 1f; pan = Offset.Zero } }
                    }
                }
                if (slot >= 0) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    palette.forEach { c -> Box(Modifier.size(32.dp).clip(CircleShape).background(Color(c))
                        .border(3.dp, if (colors[slot] == c) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape).clickable { colors[slot] = c }) }
                    // Width in mm, as Word's (− 1 mm +).
                    Choice("−", false) { widths[slot] = (widths[slot] - if (widths[slot] <= 1f) 0.25f else 1f).coerceAtLeast(0.25f) }
                    Text("${if (widths[slot] % 1f == 0f) widths[slot].toInt().toString() else widths[slot].toString()} mm", style = MaterialTheme.typography.labelLarge)
                    Choice("+", false) { widths[slot] = (widths[slot] + if (widths[slot] < 1f) 0.25f else 1f).coerceAtMost(12f) }
                }
                // As big as fits with the tools and the buttons (a landscape tablet is short).
                val room = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp - if (!onPage) 360.dp else 280.dp).coerceAtLeast(160.dp)
                // On a page: the page across the whole width, two fingers to zoom and move it, one (or the pen) to draw.
                val area = if (!onPage) Modifier.widthIn(max = room / aspect).fillMaxWidth().aspectRatio(1 / aspect).background(Color.White)
                    else Modifier.fillMaxWidth().height(room).background(Color(0xFF5F6368))
                Box(Modifier.align(Alignment.CenterHorizontally).then(area).clip(RoundedCornerShape(8.dp)).border(1.dp, Color(0xFFDADCE0), RoundedCornerShape(8.dp))) {
                    Canvas(Modifier.matchParentSize().clipToBounds().pointerInput(tool, pageAt) {
                        fun at(p: Offset) = (p - pan) / (size.width * zoom) // in the box's widths
                        detectDraw(
                            start = { p -> val q = at(p)
                                when (tool) { Tool.Eraser -> erase(strokes, q, 12f / (size.width * zoom)); Tool.Move -> last = q; else -> current = listOf(q) } },
                            move = { p -> val q = at(p)
                                when (tool) {
                                    Tool.Eraser -> erase(strokes, q, 12f / (size.width * zoom))
                                    Tool.Move -> { val d = q - last; last = q
                                        val moved = strokes.map { s -> InkStroke(s.points.map { it + d }, s.color, s.widthMm, s.highlighter) }
                                        strokes.clear(); strokes.addAll(moved) }
                                    else -> current = current + q
                                } },
                            end = { if (tool != Tool.Eraser && tool != Tool.Move && current.isNotEmpty()) strokes += InkStroke(current, colors[slot], widths[slot], tool == Tool.Highlighter); current = emptyList() },
                            cancel = { current = emptyList() },
                            transform = if (!onPage) null else { centre, move, by ->
                                val z = (zoom * by).coerceIn(1f, 6f)
                                val w = size.width * z
                                val p = centre - (centre - pan) * (z / zoom) + move
                                // The page kept in view: no further than its edges.
                                pan = Offset(p.x.coerceIn(size.width - w, 0f), p.y.coerceIn(minOf(0f, size.height - w * aspect), 0f))
                                zoom = z
                            })
                    }) {
                        val across = size.width * zoom // the box's width on screen
                        page?.let { laid -> drawIntoCanvas { c -> val n = c.nativeCanvas; val k = across / laid.page.width
                            n.save(); n.translate(pan.x, pan.y); n.scale(k, k)
                            n.drawRect(0f, 0f, laid.page.width, laid.page.height, android.graphics.Paint().apply { color = android.graphics.Color.WHITE })
                            laid.draw(n); n.restore() } }
                        slide?.let { (sw, _, draw) -> drawIntoCanvas { c -> val n = c.nativeCanvas; n.save(); n.translate(pan.x, pan.y); n.scale(across / sw, across / sw); draw(n); n.restore() } }
                        val perMm = across / (widthPt / 72f * 25.4f)
                        fun draw(points: List<Offset>, color: Int, mm: Float, highlighter: Boolean) {
                            if (points.isEmpty()) return
                            fun x(p: Offset) = pan.x + p.x * across
                            fun y(p: Offset) = pan.y + p.y * across
                            val path = Path().apply { points.forEachIndexed { i, p -> if (i == 0) moveTo(x(p), y(p)) else lineTo(x(p), y(p)) }
                                if (points.size == 1) lineTo(x(points[0]) + 0.1f, y(points[0])) }
                            drawPath(path, if (highlighter) Color(color).copy(alpha = 0.4f) else Color(color),
                                style = Stroke(mm * perMm, cap = if (highlighter) StrokeCap.Square else StrokeCap.Round, join = StrokeJoin.Round))
                        }
                        strokes.forEach { draw(it.points, it.color, it.widthMm, it.highlighter) }
                        if (slot >= 0) draw(current, colors[slot], widths[slot], tool == Tool.Highlighter)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) }
                    TextButton(enabled = strokes.isNotEmpty(), onClick = {
                        if (!onPage) insert(InkPicture.png(strokes, aspect, widthPt), widthPt, widthPt * aspect, null, 0)
                        else { val (cut, box) = InkPicture.cut(strokes, aspect, widthPt)
                            val w = box.width * widthPt
                            insert(InkPicture.png(cut, box.height / box.width, w, (w * 4).toInt().coerceIn(64, 2400)), w, box.height * widthPt, box.left * widthPt to box.top * widthPt, pageAt) }
                    }) { Text(stringResource(if (initial.isEmpty()) R.string.words_insert else R.string.common_done)) }
                }
            }
        }
    }
}

/** The eraser takes a whole stroke it touches (Word's stroke eraser). */
private fun erase(strokes: MutableList<InkStroke>, at: Offset, radius: Float) {
    strokes.removeAll { s -> s.points.any { (it - at).getDistance() < radius + s.widthMm * 0.002f } }
}

/**
 * Down, move, up of one finger or pen — the drawing's own, so the dialog does not scroll or close under it. With
 * [transform], a second finger turns it into zoom and move (centre, moved by, zoomed by); the stroke begun is dropped.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectDraw(start: (Offset) -> Unit, move: (Offset) -> Unit, end: () -> Unit,
    cancel: () -> Unit = {}, transform: ((Offset, Offset, Float) -> Unit)? = null) {
    awaitPointerEventScope {
        while (true) {
            val down = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: continue
            start(down.position); down.consume()
            var drawing = true
            while (true) {
                val e = awaitPointerEvent()
                if (e.changes.none { it.pressed }) { if (drawing) end(); break }
                if (transform != null && e.changes.count { it.pressed } >= 2) {
                    if (drawing) { cancel(); drawing = false }
                    transform(e.calculateCentroid(), e.calculatePan(), e.calculateZoom())
                    e.changes.forEach { it.consume() }
                    continue
                }
                if (!drawing) continue
                val c = e.changes.firstOrNull { it.id == down.id }
                if (c == null || !c.pressed) { end(); drawing = false; continue }
                move(c.position); c.consume()
            }
        }
    }
}

/**
 * Picture › Position (the plan, 2026-10-01: floating objects placed freely on the page, as in Word): [page] as the pages
 * lay it out and the picture [key] on it, dragged with a finger or the pen; let go and it is [place]d there (pt from the
 * page's top left) — the page comes back laid out again, the text around it. Two fingers zoom and move the page.
 */
@Composable
internal fun PositionDialog(page: LaidPage, key: String, dismiss: () -> Unit, place: (Float, Float) -> Unit) {
    val float = page.floats.firstOrNull { it.key == key }
    PositionDialog(page.page.width, page.page.height, float?.let { android.graphics.RectF(it.x, it.y, it.x + it.w, it.y + it.h) }, page,
        { n -> page.draw(n, skip = key) }, { n, x, y -> float?.drawAt(n, x, y) }, dismiss, place)
}

/**
 * Position's page, whatever draws it (Words' laid page, a PDF's): a page [pageW] × [pageH] points drawn by [drawPage]
 * without the object, the object's place [box] on it and [drawObject] drawing it with its top left at a point; [again]
 * changes when the page comes back after a [place].
 */
@Composable
internal fun PositionDialog(pageW: Float, pageH: Float, box: android.graphics.RectF?, again: Any?, drawPage: (android.graphics.Canvas) -> Unit,
    drawObject: (android.graphics.Canvas, Float, Float) -> Unit, dismiss: () -> Unit, place: (Float, Float) -> Unit) {
    class Float_(val x: Float, val y: Float, val w: Float, val h: Float)
    val float = box?.let { Float_(it.left, it.top, it.width(), it.height()) }
    val aspect = pageH / pageW
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var moved by remember { mutableStateOf(Offset.Zero) } // pt, while dragging and until the page comes back
    var last by remember { mutableStateOf<Offset?>(null) }
    androidx.compose.runtime.LaunchedEffect(again) { moved = Offset.Zero }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 900.dp).systemBarsPadding().padding(12.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.words_drag_where_it_goes), style = MaterialTheme.typography.titleMedium)
                val room = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp - 200.dp).coerceAtLeast(160.dp)
                // The whole page in view (two fingers zoom in).
                Box(Modifier.align(Alignment.CenterHorizontally).height(room).aspectRatio(1 / aspect).clip(RoundedCornerShape(8.dp)).background(Color(0xFF5F6368))) {
                    Canvas(Modifier.matchParentSize().clipToBounds().pointerInput(again) {
                        fun at(p: Offset) = (p - pan) / (size.width * zoom) * pageW // in points on the page
                        detectDraw(
                            start = { p -> val q = at(p); last = q.takeIf { float != null && it.x in float.x..float.x + float.w && it.y in float.y..float.y + float.h } },
                            move = { p -> last?.let { l -> val q = at(p); moved += q - l; last = q } },
                            end = { if (last != null && float != null && moved != Offset.Zero) place(float.x + moved.x, float.y + moved.y); last = null },
                            cancel = { last = null; moved = Offset.Zero },
                            transform = { centre, move, by ->
                                val z = (zoom * by).coerceIn(1f, 6f)
                                val w = size.width * z
                                val p = centre - (centre - pan) * (z / zoom) + move
                                pan = Offset(p.x.coerceIn(size.width - w, 0f), p.y.coerceIn(minOf(0f, size.height - w * aspect), 0f))
                                zoom = z
                            })
                    }) {
                        drawIntoCanvas { c -> val n = c.nativeCanvas; val k = size.width * zoom / pageW
                            n.save(); n.translate(pan.x, pan.y); n.scale(k, k)
                            n.drawRect(0f, 0f, pageW, pageH, android.graphics.Paint().apply { color = android.graphics.Color.WHITE })
                            drawPage(n)
                            float?.let { f ->
                                val x = f.x + moved.x; val y = f.y + moved.y
                                drawObject(n, x, y)
                                n.drawRect(x, y, x + f.w, y + f.h, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                    style = android.graphics.Paint.Style.STROKE; strokeWidth = 1.5f / k * 2; color = 0xFF1A73E8.toInt() })
                            }
                            n.restore() }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.common_done)) }
                }
            }
        }
    }
}
