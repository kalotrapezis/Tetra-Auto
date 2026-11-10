package com.kalotrapezis.drive
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.horizontalScroll
import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.ImageDecoder
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.CancellationSignal
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import android.media.MediaMetadataRetriever
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.FilledIconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

internal const val DRIVE_PATH = "/sdcard/Tetra/"
private const val PHOTO_SETUP_COMPLETED = "photo_setup_completed"
private const val HOME_PHOTO_BACKDROP = "home_photo_backdrop"

internal fun driveRoot(): File = TetraFolder.root(Environment.getExternalStorageDirectory())


/**
 * Light or dark, the user's choice (21 Oct: "polise the light theme of the android app and make it an option light or
 * dark"), Settings › Appearance; dark unless chosen. One choice for the whole app; Maps' map has its own (MapsPrefs).
 */
internal object TetraLook {
    var light by mutableStateOf(false)
        private set
    private fun prefs(c: Context) = c.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    fun load(c: Context) { light = prefs(c).getString("theme", "dark") == "light" }
    fun set(c: Context, light: Boolean) { this.light = light; prefs(c).edit().putString("theme", if (light) "light" else "dark").apply() }
    val colors get() = if (light) TetraLightColors else TetraColors
}

/** The light palette: the dark one's neutral greys turned over, the same blue-green tint in the surfaces. */
internal val TetraLightColors = lightColorScheme(
    primary = Color(0xFF1F2322), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE1DF), onPrimaryContainer = Color(0xFF111413),
    tertiaryContainer = Color(0xFFCFE2F0), onTertiaryContainer = Color(0xFF15232D),
    background = Color(0xFFF6F8F7), onBackground = Color(0xFF181C1B),
    surface = Color(0xFFF6F8F7), onSurface = Color(0xFF181C1B),
    surfaceVariant = Color(0xFFDDE4E1), onSurfaceVariant = Color(0xFF3F4946),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F3F2), surfaceContainer = Color(0xFFEAEFED),
    surfaceContainerHigh = Color(0xFFE4E9E7), surfaceContainerHighest = Color(0xFFDEE4E1),
    outline = Color(0xFF6F7975), outlineVariant = Color(0xFFBEC9C5),
)

/** A colour for the dark look and its darker twin for the light one (a pale tint on a pale card cannot be seen). */
internal fun lookTint(dark: Color, light: Color) = if (TetraLook.light) light else dark

internal val TetraColors = darkColorScheme(
    primary = Color(0xFFE6E6E6), onPrimary = Color.Black,
    primaryContainer = Color(0xFF3A3A3A), onPrimaryContainer = Color.White,
    tertiaryContainer = Color(0xFF2B4A5E), onTertiaryContainer = Color(0xFFE3E4E6),
    background = Color(0xFF0F1312), onBackground = Color(0xFFE3E4E6),
    surface = Color(0xFF0F1312), onSurface = Color(0xFFE3E4E6),
    surfaceVariant = Color(0xFF3E4945), onSurfaceVariant = Color(0xFFE3E4E6),
)

/** Tetra's look, for a screen outside the main app (the text editor opened from another app). */
@Composable internal fun TetraTheme(backdrop: Boolean = true, content: @Composable () -> Unit) = MaterialTheme(colorScheme = TetraLook.colors) {
    // [backdrop] false: see-through, for a window over another app (AI Assist's sheet)
    Surface(Modifier.fillMaxSize(), color = if (backdrop) MaterialTheme.colorScheme.background else Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground) { content() }
}

/** A photo or video another app asked us to show. */

/** The Phone (Plan.md § Phone): its own window, as Android's phone app is (Recents · Favourites · Contacts · Recordings). */
internal fun openPhone(c: Context, tab: String? = null) = c.startActivity(Intent(c, PhoneActivity::class.java).putExtra(PhoneActivity.TAB, tab).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

/** ML Kit reads codes but cannot draw one, so ZXing does — black and white, no colour to misread. */
internal fun qrBitmap(text: String, size: Int = 640, utf8: Boolean = false): android.graphics.Bitmap {
    // utf8: Greek letters (a contact's card); ZXing's default is ISO-8859-1. The pairing code is ASCII and stays as it was.
    val hints = if (utf8) mapOf(com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8") else emptyMap()
    val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
    return android.graphics.Bitmap.createBitmap(matrix.width, matrix.height, android.graphics.Bitmap.Config.ARGB_8888).apply {
        for (x in 0 until matrix.width) for (y in 0 until matrix.height) {
            setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
}

@Composable
internal fun ModuleHeader(title: String, back: () -> Unit, modifier: Modifier = Modifier, art: Int? = null, actions: @Composable () -> Unit = {}) {
    // the page's own colour in light (a dark glass bar over a light page reads as grey); the dark glass in dark
    val bar = if (TetraLook.light) MaterialTheme.colorScheme.background.copy(alpha = 0.95f) else islandColor()
    val on = if (TetraLook.light) MaterialTheme.colorScheme.onBackground else islandContentColor()
    Surface(color = bar, contentColor = on, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.statusBarsPadding().padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.home_back_to_home)) }
                Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                art?.let { ArtImage(it, 34.dp) }
                actions()
            }
            Box(Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 1.dp, max = 1.dp).background(on.copy(alpha = 0.35f)))
        }
    }
}

/**
 * The parts of Tetra, each with its colour and symbol (asked 2026-09-27). The app stays black and white; a category's
 * colour is a label — its icon beside a title, its group on Home — the same in light and dark. The desktop has the same
 * six (styles.css --cat-*).
 */
internal enum class Category(val color: Color, val icon: Int) {
    Gallery(Color(0xFF3DA35D), R.drawable.ic_gallery),
    Files(Color(0xFF4F86E8), R.drawable.ic_folder),
    Notes(Color(0xFFF2B705), R.drawable.ic_edit_note),
    Scanner(Color(0xFFD32F2F), R.drawable.ic_document_scanner),
    Office(Color(0xFFF76002), R.drawable.ic_office),
    Text(Color(0xFF8E5BD8), R.drawable.ic_file_text),
    Calendar(Color(0xFFF41879), R.drawable.ic_calendar_today),
    Timer(Color(0xFF00796B), R.drawable.ic_timer),
    Maps(Color(0xFF25C4FE), R.drawable.ic_map),
    Contacts(CONTACTS_COLOR, R.drawable.ic_contacts),
    Phone(PHONE_COLOR, R.drawable.ic_call),
    Sms(SMS_COLOR, R.drawable.ic_chat),
    Browser(BROWSER_COLOR, R.drawable.ic_open_in_browser),
}

/**
 * All files (the maps, the Browser's and Phone's files in /sdcard/Tetra): Android 11+ asks for "All files access"; a head unit's
 * Android 10 has no such page, its storage permission with requestLegacyExternalStorage reads and writes /sdcard the same.
 */
internal fun hasStorage(): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else Environment.getExternalStorageDirectory().canWrite()

internal fun grantStorage(c: Context) {
    if (Build.VERSION.SDK_INT >= 30) c.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    else (c as? Activity)?.requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 30)
        ?: c.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * The applets' art (627 px PNGs, shown at 34–112 dp) decoded once, off the screen's thread: fifteen decoded full size on
 * the UI thread held Home's first frame ~300 ms (25 Oct). One bitmap per picture, so a Tetra left open for months holds
 * no more than these fifteen.
 */
internal object HomeArt {
    private val cache = java.util.concurrent.ConcurrentHashMap<Int, ImageBitmap>()
    fun cached(art: Int) = cache[art]
    /** Made for the largest it is shown (a medium part's 112 dp), a power-of-two step of the file. */
    fun get(res: android.content.res.Resources, art: Int): ImageBitmap? = cache[art] ?: runCatching {
        val px = 112 * res.displayMetrics.density
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(res, art, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
        BitmapFactory.decodeResource(res, art, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()?.also { cache[art] = it }
    /** Home's pictures ready before Home is drawn. */
    fun warm(c: Context) {
        val res = c.applicationContext.resources
        Thread { (Applet.entries.map { it.art } + R.drawable.settings_art).forEach { get(res, it) } }.start()
    }
}

/** An applet's art ([HomeArt]); the space it takes until it is decoded. */
@Composable internal fun ArtImage(art: Int, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val res = LocalContext.current.resources
    val bitmap by produceState(HomeArt.cached(art), art) { if (value == null) value = withContext(Dispatchers.Default) { HomeArt.get(res, art) } }
    bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier.size(size)) } ?: Spacer(modifier.size(size))
}

@Composable internal fun driveNavigationSelectedColor(): Color = Color.White.copy(alpha = 0.72f)
@Composable internal fun driveNavigationSelectedContentColor(): Color = Color.Black
@Composable internal fun islandColor(): Color = Color.Black.copy(alpha = 0.72f)
@Composable internal fun islandContentColor(): Color = Color.White

internal val SheetColor = Color(0xFF16191A)

@Composable internal fun DrawerButton(icon: Int, label: String, on: Boolean, modifier: Modifier, click: () -> Unit) = Surface(
    color = if (on) driveNavigationSelectedColor() else Color.White.copy(alpha = 0.10f),
    contentColor = if (on) driveNavigationSelectedContentColor() else islandContentColor(),
    shape = MaterialTheme.shapes.large, modifier = modifier.clickable(onClick = click),
) {
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null); Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** This device's id in file names ("Xiaomi15"), as the phone's Mail and Data name theirs. */
internal fun thisDevice() = (Build.MODEL ?: "Android").filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "Android" }
/** The name the user gave this device, for the others' screens. */
internal fun thisDeviceName(c: Context): String = listOf(android.provider.Settings.Global.getString(c.contentResolver, android.provider.Settings.Global.DEVICE_NAME),
    runCatching { android.provider.Settings.Secure.getString(c.contentResolver, "bluetooth_name") }.getOrNull(), Build.MODEL).firstOrNull { !it.isNullOrBlank() }.orEmpty()

internal fun shareDriveFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT)) ?: "application/octet-stream"
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, context.getString(R.string.files_share_named, file.name)))
}
