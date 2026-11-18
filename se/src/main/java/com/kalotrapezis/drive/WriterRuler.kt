package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import android.content.Context
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import java.util.Locale

/**
 * The unit Writer measures in (asked 2026-09-29): centimetres, as Greece does, or inches, as APA and other American
 * guidelines give their margins and indents. The rulers and every length field follow it.
 */
internal object WriterUnits {
    private const val PT_PER_CM = 28.3465f
    var inches by mutableStateOf(false)
    fun load(context: Context) { inches = context.getSharedPreferences("office_editor", Context.MODE_PRIVATE).getBoolean("writer_inches", false) }
    fun set(context: Context, on: Boolean) {
        inches = on
        context.getSharedPreferences("office_editor", Context.MODE_PRIVATE).edit().putBoolean("writer_inches", on).apply()
    }
    val name get() = if (inches) "in" else "cm"
    /** Points in one unit. */
    val pt get() = if (inches) 72f else PT_PER_CM
    fun of(pt: Float) = pt / this.pt
    fun toPt(v: Float) = v * this.pt
    fun text(pt: Float) = String.format(Locale.ROOT, "%.2f", of(pt))
}

private val RULER_BACK = Color(0xFF2A2E30)
private val RULER_MARGIN = Color(0xFFC9CDD1)
private val RULER_TEXT = Color(0xFFFFFFFF)
private val RULER_INK = Color(0xFF3C4043)
private val RULER_MARK = Color(0xFF1A73E8)

/** A paragraph's indents as the ruler shows them (pt from the text's left and right edges; first line from left). */
internal data class RulerIndents(val left: Float, val firstLine: Float, val right: Float)

/**
 * Word's ruler along the top: the page's width at [perPt] a point, its margins grey, the text white between them,
 * numbered in the chosen unit from the left margin (as Word counts). [pageX] is where the page's left edge is.
 * With [indents] (the paragraph being typed in), its first-line, left and right indent marks.
 */
@Composable
internal fun HorizontalRuler(page: DocPage, perPt: Dp, pageX: Dp, modifier: Modifier, indents: RulerIndents? = null) = Canvas(modifier) {
    drawRect(RULER_BACK)
    val s = perPt.toPx()
    val x0 = pageX.toPx()
    val band = Size(page.width * s, size.height * 0.62f)
    val top = (size.height - band.height) / 2
    drawRect(RULER_MARGIN, Offset(x0, top), band)
    drawRect(RULER_TEXT, Offset(x0 + page.left * s, top), Size(page.textWidth * s, band.height))
    ticks(this, page.left * s + x0, x0, x0 + page.width * s, s, top, band.height, horizontal = true)
    indents?.let { i ->
        val left = x0 + (page.left + i.left) * s
        val first = x0 + (page.left + i.left + i.firstLine) * s
        val right = x0 + (page.width - page.right - i.right) * s
        val w = 5f * density
        // First-line indent: a triangle pointing down at the top; left (hanging) indent: up at the bottom; right: up.
        drawPath(Path().apply { moveTo(first - w, top); lineTo(first + w, top); lineTo(first, top + w * 1.4f); close() }, RULER_MARK)
        for (x in listOf(left, right)) drawPath(Path().apply { moveTo(x - w, top + band.height); lineTo(x + w, top + band.height); lineTo(x, top + band.height - w * 1.4f); close() }, RULER_MARK)
    }
}

/** The ruler down the side of a page: its top and bottom margins grey, numbered from the top margin. */
@Composable
internal fun VerticalRuler(page: DocPage, perPt: Dp, modifier: Modifier) = Canvas(modifier) {
    drawRect(RULER_BACK)
    val s = perPt.toPx()
    val band = Size(size.width * 0.62f, page.height * s)
    val left = (size.width - band.width) / 2
    drawRect(RULER_MARGIN, Offset(left, 0f), band)
    drawRect(RULER_TEXT, Offset(left, page.top * s), Size(band.width, (page.height - page.top - page.bottom) * s))
    ticks(this, page.top * s, 0f, page.height * s, s, left, band.width, horizontal = false)
}

/**
 * Tick marks from [zero] (the margin, where counting starts) out to [from] and [to], across a band at [at] of
 * [thick]: a number each unit, a long tick each half, short ones between (quarter centimetres; eighths of an inch).
 */
private fun ticks(d: DrawScope, zero: Float, from: Float, to: Float, s: Float, at: Float, thick: Float, horizontal: Boolean) = with(d) {
    val unit = WriterUnits.pt * s
    val steps = if (WriterUnits.inches) 8 else 4
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RULER_INK.toArgb(); textSize = 9f * density; textAlign = Paint.Align.CENTER }
    val step = unit / steps
    if (step < 1f) return@with
    for (dir in listOf(1, -1)) {
        var k = if (dir == 1) 0 else 1
        while (true) {
            val p = zero + dir * k * step
            if (p < from || p > to) break
            val whole = k % steps == 0
            val half = !whole && steps % 2 == 0 && k % (steps / 2) == 0
            val len = thick * when { whole -> 0f; half -> 0.4f; else -> 0.2f }
            if (whole) {
                val n = k / steps
                if (n != 0) {
                    val label = n.toString()
                    if (horizontal) drawIntoCanvas { it.nativeCanvas.drawText(label, p, at + thick / 2 + paint.textSize / 3, paint) }
                    else drawIntoCanvas { it.nativeCanvas.drawText(label, at + thick / 2, p + paint.textSize / 3, paint) }
                }
            } else {
                val mid = at + thick / 2
                if (horizontal) drawLine(RULER_INK, Offset(p, mid - len / 2), Offset(p, mid + len / 2), 1f)
                else drawLine(RULER_INK, Offset(mid - len / 2, p), Offset(mid + len / 2, p), 1f)
            }
            k++
        }
    }
}

/**
 * The editor's ruler: [HorizontalRuler] whose marks move, as Word's do — the top one the first line, the bottom left
 * one the rest of the paragraph (the first line staying where it is), the right one the right indent. Let go, [set]
 * gets the indents, snapped to the ruler's ticks.
 */
@Composable
internal fun IndentRuler(page: DocPage, perPt: Dp, pageX: Dp, indents: RulerIndents?, set: (RulerIndents) -> Unit) {
    var drag by remember { mutableStateOf<RulerIndents?>(null) }
    val step = WriterUnits.pt / (if (WriterUnits.inches) 8 else 4)
    fun snap(v: Float) = Math.round(v / step) * step
    fun snapped(i: RulerIndents) = RulerIndents(snap(i.left).coerceIn(-page.left, page.textWidth - 36f), snap(i.left + i.firstLine).coerceIn(-page.left, page.textWidth - 36f) - snap(i.left).coerceIn(-page.left, page.textWidth - 36f),
        snap(i.right).coerceIn(-page.right, page.textWidth - 36f))
    HorizontalRuler(page, perPt, pageX, Modifier.fillMaxWidth().height(26.dp).pointerInput(indents, page, perPt, pageX) {
        var which = 0
        var raw: RulerIndents? = null
        detectHorizontalDragGestures(
            onDragStart = { o ->
                val i = indents ?: return@detectHorizontalDragGestures
                val s = perPt.toPx(); val x0 = pageX.toPx()
                val first = x0 + (page.left + i.left + i.firstLine) * s
                val left = x0 + (page.left + i.left) * s
                val right = x0 + (page.width - page.right - i.right) * s
                val reach = 24.dp.toPx()
                which = listOf(1 to first, 2 to left, 3 to right)
                    .filter { (k, x) -> kotlin.math.abs(o.x - x) < reach && (k == 3 || (k == 1) == (o.y < size.height / 2)) }
                    .minByOrNull { (_, x) -> kotlin.math.abs(o.x - x) }?.first ?: 0
                raw = i.takeIf { which != 0 }
                drag = raw
            },
            onHorizontalDrag = { change, dx ->
                val r = raw ?: return@detectHorizontalDragGestures
                change.consume()
                val d = dx / perPt.toPx()
                raw = when (which) {
                    1 -> r.copy(firstLine = r.firstLine + d)
                    2 -> r.copy(left = r.left + d, firstLine = r.firstLine - d)
                    else -> r.copy(right = r.right - d)
                }
                drag = snapped(raw!!)
            },
            onDragEnd = { drag?.let { if (it != indents) set(it) }; drag = null; raw = null },
            onDragCancel = { drag = null; raw = null },
        )
    }, drag ?: indents)
    drag?.let { d ->
        // While a mark moves, what it will be, in the chosen unit.
        val v = when { d.firstLine != (indents?.firstLine ?: 0f) && d.left == indents?.left -> stringResource(R.string.words_ruler_first_line, WriterUnits.text(d.firstLine))
            d.right != indents?.right -> stringResource(R.string.words_ruler_right, WriterUnits.text(d.right)); else -> stringResource(R.string.words_ruler_left, WriterUnits.text(d.left)) }
        Text("$v ${WriterUnits.name}", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.fillMaxWidth().background(RULER_BACK).padding(horizontal = 12.dp, vertical = 2.dp))
    }
}
