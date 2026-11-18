package com.kalotrapezis.drive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/*
 * Dialogs that open on a car's screen too (Tetra Auto). There Tetra Car is a panel on a screen of the phone's own, and Android
 * refuses a dialog's window on it: Tetra stopped as one opened (the user, 10 Nov). In Tetra Car drawn for a car they are a layer
 * of the panel itself ([CarOverlayHost]), touched and closed with Back like the rest of it; anywhere else they are Material's.
 * The same parameters as Material's, so a call site changes only its name.
 */

/** The layers open on the car's screen, drawn over Tetra Car; null anywhere else. */
private val LocalCarOverlay = staticCompositionLocalOf<MutableList<State<@Composable () -> Unit>>?> { null }

/** Tetra Car drawn for a car: its dialogs and sheets go on top of [content], inside the same panel. */
@Composable
internal fun CarOverlayHost(content: @Composable () -> Unit) {
    val layers = remember { mutableStateListOf<State<@Composable () -> Unit>>() }
    CompositionLocalProvider(LocalCarOverlay provides layers) {
        Box(Modifier.fillMaxSize()) {
            content()
            layers.forEach { layer -> key(layer) { layer.value() } }
        }
    }
}

/** [body] as a layer over Tetra Car: a dimmed screen (a tap there or Back dismisses) with [body] at [alignment]. */
@Composable
private fun CarLayer(layers: MutableList<State<@Composable () -> Unit>>, onDismissRequest: () -> Unit, alignment: Alignment, body: @Composable BoxScope.() -> Unit) {
    val dismiss = rememberUpdatedState(onDismissRequest)
    val layer = rememberUpdatedState<@Composable () -> Unit> {
        BackHandler { dismiss.value() }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).pointerInput(Unit) { detectTapGestures { dismiss.value() } }, contentAlignment = alignment) {
            Box(Modifier.pointerInput(Unit) { detectTapGestures { } }) { body() } // a tap on the dialog itself stays there
        }
    }
    DisposableEffect(layers) { layers.add(layer); onDispose { layers.remove(layer) } }
}

@Composable
internal fun TetraAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    val layers = LocalCarOverlay.current
    if (layers == null) { androidx.compose.material3.AlertDialog(onDismissRequest, confirmButton, modifier, dismissButton, icon, title, text); return }
    CarLayer(layers, onDismissRequest, Alignment.Center) {
        Surface(modifier.widthIn(min = 280.dp, max = 560.dp).padding(24.dp), shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation) {
            Column(Modifier.padding(24.dp)) {
                icon?.let { CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.iconContentColor) { Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp)) { it() } } }
                title?.let { CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.titleContentColor) {
                    ProvideTextStyle(MaterialTheme.typography.headlineSmall) { Box(Modifier.padding(bottom = 16.dp)) { it() } } } }
                text?.let { CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.textContentColor) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) { Box(Modifier.weight(1f, fill = false).padding(bottom = 24.dp)) { it() } } } }
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) { dismissButton?.invoke(); confirmButton() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TetraSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentColor: Color = contentColorFor(containerColor),
    content: @Composable ColumnScope.() -> Unit,
) {
    val layers = LocalCarOverlay.current
    if (layers == null) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest, modifier, sheetState, containerColor = containerColor, contentColor = contentColor, content = content); return
    }
    CarLayer(layers, onDismissRequest, Alignment.BottomCenter) {
        Surface(modifier.fillMaxWidth().widthIn(max = 640.dp).heightIn(max = 560.dp), shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = containerColor, contentColor = contentColor) {
            Column(Modifier.padding(bottom = 16.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 12.dp).size(width = 32.dp, height = 4.dp)
                    .background(contentColor.copy(alpha = 0.4f), RoundedCornerShape(2.dp)))
                content()
            }
        }
    }
}

@Composable
internal fun TetraDialog(onDismissRequest: () -> Unit, properties: DialogProperties = DialogProperties(), content: @Composable () -> Unit) {
    val layers = LocalCarOverlay.current
    if (layers == null) { Dialog(onDismissRequest, properties, content); return }
    CarLayer(layers, onDismissRequest, Alignment.Center) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TetraDatePickerDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val layers = LocalCarOverlay.current
    if (layers == null) { androidx.compose.material3.DatePickerDialog(onDismissRequest, confirmButton, modifier, dismissButton, content = content); return }
    CarLayer(layers, onDismissRequest, Alignment.Center) {
        Surface(modifier.width(360.dp), shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor, tonalElevation = AlertDialogDefaults.TonalElevation) {
            Column {
                content()
                Row(Modifier.align(Alignment.End).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { dismissButton?.invoke(); confirmButton() }
            }
        }
    }
}
