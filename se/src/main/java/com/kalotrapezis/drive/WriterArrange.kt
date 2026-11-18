package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Arrange › Rotate: any angle (Word's free rotation), by the slider, the steps or typed; 0 straightens it. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun RotateDialog(now: Float, dismiss: () -> Unit, set: (Float) -> Unit) {
    var deg by remember { mutableFloatStateOf(if (now > 180f) now - 360f else now) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.words_rotate)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.words_degrees_clockwise, deg.roundToInt()))
                Slider(deg, { deg = it.roundToInt().toFloat() }, valueRange = -180f..180f)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-90, -15, -1, 1, 15, 90).forEach { step -> Choice(if (step > 0) "+$step°" else "$step°", false) { deg = ((deg + step + 540f) % 360f) - 180f } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Choice("0°", deg == 0f) { deg = 0f }; Choice("180°", deg == 180f || deg == -180f) { deg = 180f } }
                OutlinedTextField(deg.roundToInt().toString(), { t -> t.toIntOrNull()?.let { deg = (((it % 360) + 540) % 360 - 180).toFloat() } }, singleLine = true,
                    label = { Text(stringResource(R.string.words_degrees)) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { set(deg) }) { Text(stringResource(R.string.words_rotate)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/**
 * Picture › Size (Words' and the PDF's): in pixels, horizontal × vertical (asked 2026-09-27), as Word counts them: 96 to
 * the inch. Or dragged by its side or corner, the numbers following (asked 2026-09-28); the chain keeps its proportions.
 * [set] gets the new size in points.
 */
@Composable
internal fun PictureSizeDialog(widthPt: Float, heightPt: Float, painter: androidx.compose.ui.graphics.painter.Painter, dismiss: () -> Unit, set: (Float, Float) -> Unit) {
    val ratio = heightPt / widthPt
    var a by remember { mutableFloatStateOf(widthPt / 0.75f) }
    var d by remember { mutableFloatStateOf(heightPt / 0.75f) }
    var across by remember { mutableStateOf(a.roundToInt().toString()) }
    var down by remember { mutableStateOf(d.roundToInt().toString()) }
    var chained by remember { mutableStateOf(true) }
    fun setA(v: Float, typed: String? = null) { a = v.coerceIn(1f, 10000f); across = typed ?: a.roundToInt().toString(); if (chained) { d = (a * ratio).coerceAtLeast(1f); down = d.roundToInt().toString() } }
    fun setD(v: Float, typed: String? = null) { d = v.coerceIn(1f, 10000f); down = typed ?: d.roundToInt().toString(); if (chained) { a = (d / ratio).coerceAtLeast(1f); across = a.roundToInt().toString() } }
    val ok = across.toIntOrNull() in 1..10000 && down.toIntOrNull() in 1..10000
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.words_picture_size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // The picture, its top left fixed; handles on its right side, bottom side and corner.
                BoxWithConstraints(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF303030)).padding(12.dp)) {
                    val frameW = constraints.maxWidth.toFloat(); val frameH = constraints.maxHeight.toFloat()
                    val shown = remember { minOf(frameW * 0.7f / a, frameH * 0.7f / d) } // screen px per picture px, at first
                    fun fit() = minOf(shown, frameW / a, frameH / d)
                    val density = LocalDensity.current
                    val bw = a * fit(); val bh = d * fit()
                    with(density) {
                        Image(painter, contentDescription = stringResource(R.string.words_picture), contentScale = ContentScale.FillBounds, modifier = Modifier.size(bw.toDp(), bh.toDp()).border(1.dp, Color(0xFF1A73E8)))
                        @Composable fun Handle(x: Float, y: Float, drag: (Float, Float) -> Unit) = Box(
                            Modifier.offset { IntOffset((x - 22.dp.toPx()).roundToInt(), (y - 22.dp.toPx()).roundToInt()) }.size(44.dp)
                                .pointerInput(Unit) { detectDragGestures { change, delta -> change.consume(); drag(delta.x / fit(), delta.y / fit()) } },
                            contentAlignment = Alignment.Center,
                        ) { Box(Modifier.size(14.dp).clip(CircleShape).background(Color(0xFF1A73E8)).border(2.dp, Color.White, CircleShape)) }
                        Handle(bw, bh / 2) { dx, _ -> setA(a + dx) }
                        Handle(bw / 2, bh) { _, dy -> setD(d + dy) }
                        Handle(bw, bh) { dx, dy -> if (!chained) { setA(a + dx); setD(d + dy) } else if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) setA(a + dx) else setD(d + dy) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(across, { t -> val v = t.filter(Char::isDigit).take(5); v.toIntOrNull()?.let { setA(it.toFloat(), v) } ?: run { across = v } },
                            singleLine = true, label = { Text(stringResource(R.string.words_horizontal)) }, suffix = { Text("px") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                        OutlinedTextField(down, { t -> val v = t.filter(Char::isDigit).take(5); v.toIntOrNull()?.let { setD(it.toFloat(), v) } ?: run { down = v } },
                            singleLine = true, label = { Text(stringResource(R.string.words_vertical)) }, suffix = { Text("px") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                    }
                    // The chain: on, the two sides change together.
                    Tool(R.drawable.ic_link, stringResource(if (chained) R.string.words_proportions_kept else R.string.words_proportions_free), MaterialTheme.colorScheme.onSurface, on = chained) {
                        chained = !chained
                        if (chained) setA(a) // back to the picture's own proportions
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { set(across.toInt() * 0.75f, down.toInt() * 0.75f) }) { Text(stringResource(R.string.common_ok)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
