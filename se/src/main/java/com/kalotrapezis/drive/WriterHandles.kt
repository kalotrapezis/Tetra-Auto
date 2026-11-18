package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** [v] turned by [deg] clockwise (the screen's y goes down). */
internal fun turnedBy(v: Offset, deg: Float): Offset {
    val a = Math.toRadians(deg.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat()
    return Offset(v.x * c - v.y * s, v.x * s + v.y * c)
}

/** The angle, clockwise from straight up, of [v]; near a multiple of 45° it snaps to it. */
internal fun angleOf(v: Offset): Float {
    val d = Math.toDegrees(atan2(v.x, -v.y).toDouble()).toFloat().let { if (it < 0) it + 360f else it }
    val near = (d / 45f).roundToInt() * 45f
    return (if (abs(d - near) < 4f) near else d) % 360f
}

/**
 * Handles on the chosen picture, shape or text box (asked 15 Oct): the corner stretches it (a photo keeps its
 * proportions), the right and bottom sides stretch it one way, the knob above turns it. Drawn beside the object in
 * the page's own frame — [left], [top], [w], [h] in pixels as it is shown, [angle] how far it is turned — so a drag is
 * the finger's own path. [size] and [turn] follow the finger; [sized] and [turned] when it lifts.
 */
@Composable
internal fun ObjectHandles(left: Float, top: Float, w: Float, h: Float, angle: Float, rotatable: Boolean, keepAspect: Boolean,
    size: (Float, Float) -> Unit, sized: () -> Unit, turn: (Float) -> Unit, turned: () -> Unit) {
    val density = LocalDensity.current
    val grip = 32.dp; val half = with(density) { (grip / 2).toPx() }
    val lift = with(density) { 36.dp.toPx() }; val least = with(density) { 16.dp.toPx() }
    val now by rememberUpdatedState(Triple(w, h, angle))
    val centre = Offset(left + w / 2, top + h / 2)
    val blue = Color(0xFF1A73E8)
    @Composable fun handle(local: Offset, round: Boolean, drag: (start: Triple<Float, Float, Float>, moved: Offset) -> Unit, end: () -> Unit) {
        val at = centre + turnedBy(local, angle)
        Box(Modifier.offset { IntOffset((at.x - half).roundToInt(), (at.y - half).roundToInt()) }.size(grip)
            .pointerInput(Unit) {
                var start = now; var moved = Offset.Zero
                detectDragGestures(onDragStart = { start = now; moved = Offset.Zero },
                    onDrag = { change, d -> change.consume(); moved += d; drag(start, moved) },
                    onDragEnd = end, onDragCancel = end)
            }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(if (round) 18.dp else 14.dp).clip(if (round) CircleShape else androidx.compose.ui.graphics.RectangleShape)
                .background(Color.White).border(2.dp, blue, if (round) CircleShape else androidx.compose.ui.graphics.RectangleShape))
        }
    }
    // The stem from the top's middle up to the knob.
    if (rotatable) Canvas(Modifier.size(1.dp)) {
        val from = centre + turnedBy(Offset(0f, -h / 2), angle); val to = centre + turnedBy(Offset(0f, -h / 2 - lift), angle)
        drawLine(blue, from, to, 2.dp.toPx())
    }
    // A stretch is measured along the object's own sides, however it is turned.
    fun stretched(start: Triple<Float, Float, Float>, moved: Offset, across: Boolean, down: Boolean, aspect: Boolean) {
        val m = turnedBy(moved, -start.third)
        var nw = if (across) (start.first + m.x).coerceAtLeast(least) else start.first
        var nh = if (down) (start.second + m.y).coerceAtLeast(least) else start.second
        if (aspect) { val k = maxOf(nw / start.first, nh / start.second); nw = start.first * k; nh = start.second * k }
        size(nw, nh)
    }
    handle(Offset(w / 2, h / 2), false, { s, m -> stretched(s, m, true, true, keepAspect) }, sized)
    handle(Offset(w / 2, 0f), false, { s, m -> stretched(s, m, true, false, false) }, sized)
    handle(Offset(0f, h / 2), false, { s, m -> stretched(s, m, false, true, false) }, sized)
    if (rotatable) handle(Offset(0f, -h / 2 - lift), true, { s, m ->
        turn(angleOf(turnedBy(Offset(0f, -s.second / 2 - lift), s.third) + m))
    }, turned)
}
