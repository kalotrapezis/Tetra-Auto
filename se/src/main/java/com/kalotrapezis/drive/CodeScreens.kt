package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.rememberCoroutineScope

private enum class CodeMode { Codes, Text }

private sealed interface CodeResult {
    data class Payment(val code: String) : CodeResult
    data class Code(val value: String, val url: String?) : CodeResult
    data class Text(val text: String, val payment: String?) : CodeResult
    /** Tetra's own pairing QR from the computer: pairs right away. */
    data class Pairing(val raw: String, val computer: String) : CodeResult
}

/** An RF payment code takes priority; otherwise a link gets an Open action. Only http(s) links are opened. */
private fun codeResult(value: String, url: String?): CodeResult = PaymentCodes.findRf(value)?.let(CodeResult::Payment)
    ?: CodeResult.Code(value, (url ?: value.trim()).let { link ->
        when {
            link.startsWith("http://", ignoreCase = true) || link.startsWith("https://", ignoreCase = true) -> link
            link.startsWith("www.", ignoreCase = true) && ' ' !in link -> "https://$link"
            else -> null
        }
    })

/** QR/barcode scanning (continuous) and text recognition (on shutter), both on-device. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
// [onCode]: only the codes [takes] wants go to it (Sync's pairing by default; Passwords' 2FA otpauth codes), [hint] over the camera
internal fun CodeScannerTab(back: () -> Unit, onCode: ((String) -> Boolean)? = null, takes: (String) -> Boolean = { SyncRules.parseQr(it) != null }, hint: String? = null, pickImage: Boolean = false) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    var hasCamera by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var mode by remember { mutableStateOf(CodeMode.Codes) }
    var result by remember { mutableStateOf<CodeResult?>(null) }
    var reading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    OpenTiming("Codes", capture != null)
    // A dismissed code is ignored for a moment so the sheet does not pop straight back up.
    var ignoredValue by remember { mutableStateOf<String?>(null) }
    var ignoredUntil by remember { mutableStateOf(0L) }
    val scanning by rememberUpdatedState(hasCamera && mode == CodeMode.Codes && result == null)
    val previewView = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val barcodes = remember { BarcodeScanning.getClient() }
    val scope = rememberCoroutineScope()
    // a code in a picture (8 Nov, the user: "an open button with the barcode scanner to open images"): the same reader, the same result sheet
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { InputImage.fromFilePath(context, uri) }.onSuccess { image ->
            barcodes.process(image).addOnSuccessListener(ContextCompat.getMainExecutor(context)) { found ->
                val code = found.firstOrNull { !it.rawValue.isNullOrBlank() }
                if (code == null) Toast.makeText(context, R.string.codes_none_in_image, Toast.LENGTH_LONG).show()
                else result = codeResult(code.rawValue!!, code.url?.url?.takeIf { code.valueType == Barcode.TYPE_URL })
            }.addOnFailureListener { WriterLog.e("Codes: a picture not read", it); Toast.makeText(context, R.string.codes_none_in_image, Toast.LENGTH_LONG).show() }
        }.onFailure { WriterLog.e("Codes: a picture not opened", it) }
    }
    LaunchedEffect(pickImage) { if (pickImage) imagePicker.launch("image/*") } // Home's «Κωδικός από εικόνα»
    var pairStatus by remember { mutableStateOf<String?>(null) }
    // Tetra Auto: the car connects with the code just read; then said, and the scanner goes
    var autoWaiting by remember { mutableStateOf(false) }
    val autoPaired = AutoCars.justPaired.value
    LaunchedEffect(autoPaired, autoWaiting) {
        if (!autoWaiting || autoPaired == null) return@LaunchedEffect
        pairStatus = context.getString(R.string.auto_paired_done, autoPaired)
        kotlinx.coroutines.delay(1500)
        back()
    }
    var pairing by remember { mutableStateOf(false) }
    fun pair(raw: String, qr: PairingQr) {
        result = CodeResult.Pairing(raw, qr.name)
        pairStatus = context.getString(R.string.codes_pairing)
        pairing = true
        scope.launch {
            // Tetra Car's QR: the car's own link (CarWire), Sync's pairing left as it is
            pairStatus = runCatching { withContext(Dispatchers.IO) { if (CarWire.isCarQr(raw)) CarWire.pair(context.applicationContext, qr) else SyncStore(context.applicationContext).let { SyncClient(context.applicationContext, it).pair(qr).name } } }
                .fold({ context.getString(R.string.codes_paired, it) }, { it.message ?: context.getString(R.string.codes_pairing_failed) })
            pairing = false
        }
    }
    val texts = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val requestCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCamera = granted
        if (!granted) error = context.getString(R.string.codes_camera_needed)
    }
    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            barcodes.close()
            texts.close()
        }
    }

    LaunchedEffect(hasCamera, lifecycleOwner) {
        if (!hasCamera) return@LaunchedEffect
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
                // Small or dense barcodes need more than the default 640x480 analysis frames.
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { frame ->
                    val image = frame.image
                    if (!scanning || image == null) {
                        frame.close()
                        return@setAnalyzer
                    }
                    barcodes.process(InputImage.fromMediaImage(image, frame.imageInfo.rotationDegrees))
                        .addOnSuccessListener(ContextCompat.getMainExecutor(context)) { found ->
                            // Several codes can be in view (a QR on the screen, a barcode on a can): Tetra's own
                            // pairing code wins over everything else.
                            val codes = found.filter { !it.rawValue.isNullOrBlank() }
                            val pairing = codes.firstNotNullOfOrNull { c -> SyncRules.parseQr(c.rawValue!!)?.let { c.rawValue!! to it } }
                            if (onCode != null) { // Sync's pairing mode (or Passwords' 2FA): every other code is ignored
                                val taken = codes.firstOrNull { takes(it.rawValue!!) }?.rawValue
                                if (scanning && taken != null && onCode(taken)) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                return@addOnSuccessListener
                            }
                            val code = codes.firstOrNull() ?: return@addOnSuccessListener
                            val value = pairing?.first ?: code.rawValue!!
                            if (!scanning || (value == ignoredValue && SystemClock.elapsedRealtime() < ignoredUntil)) return@addOnSuccessListener
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (pairing != null) { pair(pairing.first, pairing.second); return@addOnSuccessListener }
                            // Tetra Auto's QR on the car's screen: that car pairs as it next says hello (AutoCars)
                            AutoCars.code(value)?.let { (code, name) ->
                                result = CodeResult.Pairing(value, name); pairStatus = context.getString(R.string.auto_pairing, name)
                                AutoCars.justPaired.value = null; autoWaiting = true
                                AutoCars.expect(context.applicationContext, code, name); return@addOnSuccessListener
                            }
                            result = codeResult(value, code.url?.url?.takeIf { code.valueType == Barcode.TYPE_URL })
                        }
                        .addOnCompleteListener { frame.close() }
                }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis)
                capture = imageCapture
            }.onFailure { error = it.message ?: context.getString(R.string.scanner_camera_failed) }
        }, ContextCompat.getMainExecutor(context))
    }
    DisposableEffect(Unit) { onDispose { runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() } } }

    fun readText() {
        val imageCapture = capture ?: return
        reading = true
        error = null
        imageCapture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val media = image.image
                if (media == null) { image.close(); reading = false; return }
                texts.process(InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees))
                    .addOnSuccessListener { text ->
                        val all = text.textBlocks.joinToString("\n\n") { it.text }
                        if (all.isBlank()) error = context.getString(R.string.codes_no_text)
                        else result = CodeResult.Text(all, PaymentCodes.findRf(all))
                    }
                    .addOnFailureListener { error = it.message ?: context.getString(R.string.codes_read_failed) }
                    .addOnCompleteListener { image.close(); reading = false }
            }
            override fun onError(exception: ImageCaptureException) {
                reading = false
                error = exception.message ?: context.getString(R.string.scanner_capture_failed)
            }
        })
    }
    fun dismiss() {
        (result as? CodeResult.Code)?.value?.let { ignoredValue = it }
        (result as? CodeResult.Payment)?.code?.let { ignoredValue = it }
        (result as? CodeResult.Pairing)?.raw?.let { ignoredValue = it }
        ignoredUntil = SystemClock.elapsedRealtime() + 3_000
        result = null
    }

    Box(Modifier.fillMaxSize()) {
        if (hasCamera) AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        if (hasCamera && mode == CodeMode.Codes) Canvas(Modifier.fillMaxSize()) {
            val side = size.minDimension * 0.66f
            drawRoundRect(
                Color.White, topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2.4f),
                size = androidx.compose.ui.geometry.Size(side, side), cornerRadius = CornerRadius(28.dp.toPx()), style = Stroke(3.dp.toPx()),
            )
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_back)) }
            }
            Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                Text(
                    when {
                        onCode != null -> hint ?: stringResource(R.string.codes_point_pairing)
                        mode == CodeMode.Codes -> stringResource(R.string.codes_point_code)
                        else -> stringResource(R.string.codes_frame_text)
                    },
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                )
            }
        }
        Surface(
            color = islandColor(), contentColor = islandContentColor(), shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
        ) {
            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!hasCamera) Button(onClick = { requestCamera.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.scanner_allow_camera), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                else if (mode == CodeMode.Text) Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                    if (reading) CircularProgressIndicator()
                    else Surface(shape = CircleShape, color = Color.White, modifier = Modifier.size(68.dp).clickable(enabled = capture != null, onClick = ::readText)) {}
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScanPill(stringResource(R.string.app_codes), R.drawable.ic_qr_code, mode == CodeMode.Codes) { mode = CodeMode.Codes; error = null }
                    ScanPill(stringResource(R.string.codes_text), R.drawable.ic_text_scan, mode == CodeMode.Text) { mode = CodeMode.Text; error = null }
                    if (onCode == null) ScanPill(stringResource(R.string.codes_image), R.drawable.ic_gallery, false) { imagePicker.launch("image/*") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    result?.let { current ->
        ModalBottomSheet(onDismissRequest = ::dismiss, containerColor = islandColor(), contentColor = islandContentColor()) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (current) {
                    is CodeResult.Payment -> PaymentCodeBlock(context, current.code, large = true)
                    is CodeResult.Code -> {
                        Text(stringResource(if (current.url != null) R.string.codes_link else R.string.codes_code), style = MaterialTheme.typography.titleLarge)
                        val pick = remember(current) { mutableStateOf(TextFieldValue(current.value)) }
                        ChoosableText(pick)
                        current.url?.let { url -> DriveWideAction(R.drawable.ic_open_in_browser, stringResource(R.string.codes_open_browser)) { openLink(context, url); dismiss() } }
                        // a contact's card (Contacts › Share › QR code, or anyone's vCard QR): into Tetra's Contacts
                        if (current.value.trimStart().startsWith("BEGIN:VCARD", true)) DriveWideAction(R.drawable.ic_person, stringResource(R.string.codes_add_contacts)) {
                            val r = runCatching { ContactsFiles.import(contactsRoot(), VCard.cards(current.value)) }.onSuccess { ScopedSync.soon(context, "contacts", 0, orWhole = true) }
                            android.widget.Toast.makeText(context, r.fold({ context.getString(if (it.added > 0) R.string.codes_added_contacts else R.string.codes_already_contacts) }) { context.getString(R.string.codes_not_added, it.message.orEmpty()) }, android.widget.Toast.LENGTH_SHORT).show()
                            dismiss()
                        }
                        DriveWideAction(R.drawable.ic_copy, stringResource(if (pick.value.selection.collapsed) R.string.common_copy else R.string.photos_copy_selected)) { copyText(context, pick.value.chosen()); dismiss() }
                        DriveWideAction(R.drawable.ic_share, stringResource(R.string.common_share)) { shareText(context, pick.value.chosen()) }
                    }
                    is CodeResult.Pairing -> {
                        Text(stringResource(R.string.codes_tetra_computer), style = MaterialTheme.typography.titleLarge)
                        Text(current.computer, style = MaterialTheme.typography.titleMedium)
                        Text(pairStatus.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                        if (pairing) CircularProgressIndicator()
                    }
                    is CodeResult.Text -> {
                        current.payment?.let { PaymentCodeBlock(context, it, large = false) }
                        Text(stringResource(R.string.codes_text), style = MaterialTheme.typography.titleLarge)
                        val pick = remember(current) { mutableStateOf(TextFieldValue(current.text)) }
                        ChoosableText(pick)
                        DriveWideAction(R.drawable.ic_copy, stringResource(if (pick.value.selection.collapsed) R.string.photos_copy_all else R.string.photos_copy_selected)) { copyText(context, pick.value.chosen()); dismiss() }
                        DriveWideAction(R.drawable.ic_share, stringResource(R.string.common_share)) { shareText(context, pick.value.chosen()) }
                    }
                }
            }
        }
    }
}

/** Read text the user can pick part of (and fix) before copying; the hint says how (the user, 22 Oct: "I want to be able to choose what I want to copy"). */
@Composable
internal fun ChoosableText(value: MutableState<TextFieldValue>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.codes_choose_hint), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value.value, { value.value = it }, Modifier.fillMaxWidth().heightIn(max = 360.dp), textStyle = MaterialTheme.typography.bodyLarge)
    }
}

/** The chosen part, or all of it when nothing is chosen. */
internal fun TextFieldValue.chosen() = if (selection.collapsed) text else text.substring(selection.min, selection.max)

@Composable
private fun PaymentCodeBlock(context: Context, code: String, large: Boolean) {
    Text(stringResource(R.string.codes_payment), style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
    SelectionContainer {
        Text(PaymentCodes.format(code), fontFamily = FontFamily.Monospace, style = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium)
    }
    DriveWideAction(R.drawable.ic_copy, stringResource(R.string.codes_copy_payment)) { copyText(context, code) }
    if (large) DriveWideAction(R.drawable.ic_share, stringResource(R.string.codes_share_payment)) { shareText(context, code) }
}

private fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Scanned text", text))
    // Android 13+ shows its own copied confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, context.getString(R.string.photos_copied), Toast.LENGTH_SHORT).show()
}

private fun shareText(context: Context, text: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
}

private fun openLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { Toast.makeText(context, context.getString(R.string.codes_no_browser), Toast.LENGTH_SHORT).show() }
}
