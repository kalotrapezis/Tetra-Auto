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
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.camera.core.CameraSelector
import androidx.camera.core.Camera
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
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

/** Photos may be read: all of them, or (Android 14+) the ones the user chose. */
internal fun hasVisualAccess(c: Context): Boolean = when {
    Build.VERSION.SDK_INT >= 34 ->
        (c.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
            c.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED) ||
            c.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
    Build.VERSION.SDK_INT == 33 ->
        c.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
            c.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
    else -> c.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
}

internal fun photoPermissions() = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT == 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

class MainActivity : ComponentActivity() {
    private companion object { var resumed = 0 }
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AppLocale.wrap(base))
    /** The part an applet icon asked for (Settings › App icons), until Tetra has turned to it. */
    private val applet = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        mailWrite(intent)
        (intent.getStringExtra(Applet.EXTRA) ?: SmsRole.compose(intent))?.let { applet.value = it }
        calendarFile(intent)?.let { CalendarInbox.pending.value = it; applet.value = Applet.Calendar.name }
    }

    /**
     * Tetra as the email app (the user, 31 Oct): a mailto: link (SENDTO, VIEW) or «Email (Tetra)» in the share menu (SEND,
     * SEND_MULTIPLE, through the MailShare alias) opens Mail's compose page with what it brings. Shared files are copied
     * in at once, off the screen's thread: another app's content:// address stops working once it is done with us.
     */
    private fun mailWrite(i: Intent): Boolean {
        val mailto = i.data?.takeIf { it.scheme.equals("mailto", true) && (i.action == Intent.ACTION_SENDTO || i.action == Intent.ACTION_VIEW) }
        val share = (i.action == Intent.ACTION_SEND || i.action == Intent.ACTION_SEND_MULTIPLE) && i.component?.className?.endsWith(".MailShare") == true
        if (mailto == null && !share) return false
        Thread {
            val base = mailto?.let { MailTo.draft(it.toString()) } ?: MailDraft("new")
            fun list(key: String) = i.getStringArrayExtra(key)?.filter { it.isNotBlank() }.orEmpty()
            val streams = @Suppress("DEPRECATION") (i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: listOfNotNull(i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)))
            val dir = File(cacheDir, "mail-share/${System.currentTimeMillis()}")
            val files = streams.mapNotNull { u -> runCatching {
                val name = contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    ?: u.lastPathSegment ?: "attachment"
                File(dir, name.replace('/', '_')).also { f -> dir.mkdirs(); contentResolver.openInputStream(u)!!.use { input -> f.outputStream().use { input.copyTo(it) } } }
            }.onFailure { WriterLog.w("Mail: a shared file not copied (${it.message})") }.getOrNull() }
            val draft = base.copy(to = (listOf(base.to) + list(Intent.EXTRA_EMAIL)).filter { it.isNotBlank() }.joinToString(", "),
                cc = (listOf(base.cc) + list(Intent.EXTRA_CC)).filter { it.isNotBlank() }.joinToString(", "),
                bcc = (listOf(base.bcc) + list(Intent.EXTRA_BCC)).filter { it.isNotBlank() }.joinToString(", "),
                subject = base.subject.ifEmpty { i.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty() },
                body = base.body.ifEmpty { i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty() }, files = files)
            runOnUiThread { applet.value = "mail-write:" + draft.json() }
        }.start()
        return true
    }

    /** An .ics another app opened with Tetra (Open with › Tetra Calendar): the Calendar imports it. */
    private fun calendarFile(i: Intent): Uri? = i.takeIf { it.action == Intent.ACTION_VIEW }?.data?.takeIf { u ->
        val type = i.type ?: runCatching { contentResolver.getType(u) }.getOrNull()
        type in setOf("text/calendar", "text/x-vcalendar", "application/ics") || u.lastPathSegment.orEmpty().endsWith(".ics", true)
    }

    // two windows side by side (Beside): Tetra leaves the front when the last one pauses
    override fun onResume() { super.onResume(); resumed++; CalendarAlarms.front = true; Wake.ensure(this) } // "Hey Tetra" listens again once Tetra is on the screen
    override fun onPause() {
        super.onPause(); resumed--; CalendarAlarms.front = resumed > 0
        Thread { runCatching { AlarmPlan.plan(this) } }.start() // the next alarm posted again out of the front, where HyperOS gives it its island
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WriterLog.init(this) // the log from the start: a crash anywhere is written down
        CalendarIndex.dir = cacheDir // the Calendar's events kept between starts
        CalendarAlarms.planSoon(this) // the Calendar's next reminder, from the files as they are now
        PillsAlarms.planSoon(this) // and the next pill
        LocationReminders.planSoon(this) // the places watched again: a force stop or a permission taken back ends them
        ContactsPreload.warm(this) // Contacts open full, not "no contacts" for a second
        PhoneLog.warm(this) // and the Phone's Recents
        NotesStore.indexDir = cacheDir
        HomeArt.warm(this) // Home's pictures made small before Home is drawn
        Notices.dropOld(this) // the old channels that put a count on every icon (4 Nov)
        // the Calendar's events and Notes once Home's first frame is drawn, at a background priority: started with it, they
        // slowed that frame on the phone (25 Oct)
        android.view.Choreographer.getInstance().postFrameCallback { window.decorView.post {
            CalendarPreload.warm(this)
            if (storageAllowed(this)) MailFiles.warm(MailEngine.device()) // Mail's index (tens of MB)
            Thread { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); if (storageAllowed(this)) runCatching { notesStore().list() } }.start()
        } }
        Thread { runCatching { TimerService.check(this) } }.start() // a timer running (here or started elsewhere): its notification
        // transfers cut off earlier leave hidden .part files; none is running at start but a sync's own
        Thread { runCatching { if (storageAllowed(this) && !SyncService.isRunning) SyncRules.sweepParts(TetraFolder.root(Environment.getExternalStorageDirectory())) } }.start()
        Thread { runCatching { if (storageAllowed(this)) MapsAge.check(this) } }.start() // a map turned old: said once
        // Compose's new text menu bypasses LocalTextToolbar; the old path lets Writer show its Selection tab instead (14 Oct).
        @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class) androidx.compose.foundation.ComposeFoundationFlags.isNewContextMenuEnabled = false
        // a document, spreadsheet or presentation another app opens here (Open with › Tetra Office); else a photo or video
        val type = intent.data?.let { intent.type ?: runCatching { contentResolver.getType(it) }.getOrNull() }
        val office = intent.takeIf { it.action == Intent.ACTION_VIEW || it.action == Intent.ACTION_EDIT }?.data
            ?.takeIf { OfficeOpen.extensionOf(type) != null || OfficeOpen.isOffice(it.lastPathSegment.orEmpty()) }?.let { ExternalMedia(it, type) }
        val calendar = calendarFile(intent)?.also { CalendarInbox.pending.value = it }
        val mail = savedInstanceState == null && mailWrite(intent)
        val external = intent.takeIf { !mail && it.data?.scheme?.equals("mailto", true) != true && SmsRole.compose(it) == null && office == null && calendar == null && it.action in EXTERNAL_VIEW_ACTIONS }?.data?.let { ExternalMedia(it, type) }
        OfficeUi.load(this)
        TetraLook.load(this)
        SystemFolderChoice.load(this)
        // a mouse or a finger noticed anywhere, for Office's ribbon (at the top with a mouse)
        if (savedInstanceState == null) applet.value = intent.getStringExtra(Applet.EXTRA) ?: SmsRole.compose(intent) ?: calendar?.let { Applet.Calendar.name } ?: applet.value // a mail to write may be there already
        // states made here, before any screen reads them: AI's switch first made by a sync job mid-composition crashed Home (8 Nov)
        AiGate.on; HomeCards.tick.intValue
        setContent { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.noticePointer()) { LocalDriveApp(external, office, applet) } }
    }
}

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
internal data class ExternalMedia(val uri: Uri, val mimeType: String?)
private val EXTERNAL_VIEW_ACTIONS = setOf(Intent.ACTION_VIEW, "android.provider.action.REVIEW", "com.android.camera.action.REVIEW")

internal fun authenticateVault(activity: Activity, success: () -> Unit, failure: (String) -> Unit,
                               title: String = activity.getString(R.string.home_vault_unlock), subtitle: String = activity.getString(R.string.home_vault_unlock_detail),
                               cancelled: () -> Unit = {}) {
    val allowed = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    val manager = activity.getSystemService(BiometricManager::class.java)
    if (manager.canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
        failure(activity.getString(R.string.home_vault_no_lock))
        return
    }
    BiometricPrompt.Builder(activity)
        .setTitle(title)
        .setSubtitle(subtitle)
        .setAllowedAuthenticators(allowed)
        .build()
        .authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = success()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED && errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED) failure(errString.toString()) else cancelled()
            }
        })
}

internal data class Entry(
    val name: String,
    val detail: String,
    val relativePath: String? = null,
    val contentUri: Uri? = null,
    val takenMillis: Long = 0,
    val sizeBytes: Long = 0,
    val photoKey: String = "",
    val isVideo: Boolean = false,
    // Pixel size of the upright photo. Sync turns face boxes into fractions with it.
    val width: Int = 0,
    val height: Int = 0,
    /** A motion photo's video half (MotionRules): played by the viewer's Motion button; never an item of its own. */
    val motion: Uri? = null,
)
private sealed interface ListState {
    data object Idle : ListState
    data object Loading : ListState
    data class Items(val entries: List<Entry>) : ListState
    data class Error(val message: String) : ListState
}
private sealed interface DriveListState {
    data object Idle : DriveListState
    data object Loading : DriveListState
    data class Items(val folder: String, val entries: List<DriveItem>) : DriveListState
    data class Error(val message: String) : DriveListState
}

@Composable
private fun LocalDriveApp(external: ExternalMedia? = null, office: ExternalMedia? = null, applet: androidx.compose.runtime.MutableState<String?>) {
    val context = LocalContext.current
    // A MediaStore item opens in the full Gallery viewer; anything else (another app's file) in a single-item viewer.
    val externalMediaId = external?.uri?.takeIf { it.authority == MediaStore.AUTHORITY }?.lastPathSegment
    var externalSingle by remember { mutableStateOf(false) }
    fun closeExternal() { (context as? Activity)?.finish() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferences = remember(context) { context.getSharedPreferences("onboarding", Context.MODE_PRIVATE) }
    // the first start opens the guide (Guide.kt), which can be skipped; Settings › Guide opens it again
    var screen by remember { mutableStateOf(if (AppLocale.reopenSettings || CarMaps.edition /* the car: only Settings, never the phone's home (its window brought back after Android closed it) */) Screen.Settings.also { AppLocale.reopenSettings = false } else if (preferences.getBoolean(PHOTO_SETUP_COMPLETED, false)) Screen.Home else Screen.Guide) }
    var settingsStart by remember { mutableStateOf<SettingsPage?>(null) }
    var guideAt by remember { mutableStateOf(if (screen == Screen.Guide) 0 else null) } // the guide's step; docked under the screen when screen isn't Guide
    var writerOpen by remember { mutableStateOf<String?>(null) } // the document Writer edits; null a new one
    var writerBack by remember { mutableStateOf(Screen.Office) } // where Writer's back goes: Office, or Files it was opened from
    var sheetOpen by remember { mutableStateOf<String?>(null) } // the spreadsheet Excel's viewer shows (its back: writerBack)
    var slidesOpen by remember { mutableStateOf<String?>(null) } // the presentation PowerPoint's viewer shows (its back: writerBack)
    var pdfOpen by remember { mutableStateOf<String?>(null) } // the PDF the viewer shows (its back: writerBack)
    var officeVersion by remember { mutableStateOf(0) } // the Office home lists again after Writer saved
    val syncStore = remember(context) { SyncStore(context.applicationContext) }
    var pairedDevice by remember { mutableStateOf(syncStore.pairing()) }
    // Opening the app is the moment to catch up with the computer, if it is cheap to (see syncInBackground).
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { SyncService.syncInBackground(context); SyncJob.schedule(context) } }
    // And for as long as it is open, the computer may say "there is something new here" and this phone will go
    // and fetch it (SYNC_PLAN.md 6i). It answers only devices it is already paired with. With a computer paired it
    // goes on listening when the app closes (SyncListenService, the user's choice of 23 Oct), and opening the app is
    // the moment that service may take the location right too.
    androidx.compose.runtime.DisposableEffect(Unit) {
        runCatching { SyncServer.acquire(context.applicationContext, SyncStore(context.applicationContext)) }
        SyncListenService.start(context)
        Thread { runCatching { DataStats.startAll(context.applicationContext) } }.start()
        Thread { runCatching { AiGate.load(context.applicationContext) } }.start() // whether Home shows AI Assist
        onDispose { runCatching { SyncServer.release() } }
    }
    // the computer asks where the phone is (desktop Maps) and Tetra may not use the location yet: Android's question, here
    val askLocation = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { SyncListenService.start(context) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        PhoneLocation.askPermission = { askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
        onDispose { PhoneLocation.askPermission = null }
    }
    LaunchedEffect(screen) { if (screen == Screen.Settings) pairedDevice = syncStore.pairing() }
    var homePhotoBackdrop by remember { mutableStateOf(preferences.getBoolean(HOME_PHOTO_BACKDROP, true)) }
    var photosPane by remember { mutableStateOf(PhotosPane.Timeline) }
    val currentScreen by rememberUpdatedState(screen)
    var driveState by remember { mutableStateOf<DriveListState>(DriveListState.Idle) }
    var driveFolder by remember { mutableStateOf("") }
    var driveDiskVersion by remember { mutableStateOf(0) } // counts changes under Tetra; search runs again on each
    var drivePane by remember { mutableStateOf(DrivePane.Home) }
    val currentDrivePane by rememberUpdatedState(drivePane)
    val driveRecents = remember(context) { DriveRecents(context) }
    val driveOpeners = remember(context) { DriveOpeners(context) }
    var recentsVersion by remember { mutableStateOf(0) }
    var photosState by remember { mutableStateOf<ListState>(ListState.Idle) }
    var photoFolders by remember { mutableStateOf(emptyList<DeviceFolder>()) }
    var photoFilter by remember { mutableStateOf<PhotoFilter>(PhotoFilter.Timeline) }
    val photoMetadata = remember(context) { PhotoMetadataStore(context.applicationContext) }
    var metadataVersion by remember { mutableStateOf(0) }
    var scanPages by remember { mutableStateOf<List<CapturedPage>>(emptyList()) }
    var scanSelected by remember { mutableStateOf(0) }
    var scanRetake by remember { mutableStateOf<Int?>(null) }
    var scannerError by remember { mutableStateOf<String?>(null) }
    var scanSaving by remember { mutableStateOf(false) }

    fun lockScannerPortrait() { (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    fun unlockScannerOrientation() { (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }

    fun hasAllFilesAccess() = storageAllowed(context)
    fun quarantineScanFiles(page: CapturedPage) {
        val trash = File(context.cacheDir, "scans-trash").also { it.mkdirs() }
        if (page.file.exists()) runCatching { Files.move(page.file.toPath(), File(trash, "${UUID.randomUUID()}-${page.file.name}").toPath()) }
    }
    fun clearScanPages() {
        scanPages.forEach(::quarantineScanFiles)
        scanPages = emptyList()
        scanSelected = 0
        scanRetake = null
    }
    fun loadDrive(folder: String = driveFolder, quiet: Boolean = false) {
        if (!hasAllFilesAccess()) {
            driveState = DriveListState.Error(context.getString(R.string.home_files_access_needed, DRIVE_PATH))
            return
        }
        if (!quiet) driveState = DriveListState.Loading
        Thread {
            val result = runCatching {
                val root = driveRoot()
                check((root.exists() && root.isDirectory) || root.mkdirs()) { context.getString(R.string.home_drive_not_created) }
                DriveRules.ensureSystemFolders(root)
                if (folder == "Trash" && !File(root, TetraSystem.onDisk(folder)).exists()) emptyList() else listDriveFolder(root, folder)
            }
            (context as MainActivity).runOnUiThread {
                if (quiet && folder != driveFolder) return@runOnUiThread // moved on while it read; that folder loads itself
                driveState = result.fold(
                    onSuccess = { DriveListState.Items(folder, it) },
                    onFailure = { DriveListState.Error(context.getString(R.string.home_drive_unreadable, DRIVE_PATH, it.message ?: context.getString(R.string.home_storage_unavailable))) },
                )
            }
        }.start()
    }
    fun hasVisualAccess(): Boolean = hasVisualAccess(context)
    fun photoPermission(): String = when {
        Build.VERSION.SDK_INT >= 34 && context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED && context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED -> context.getString(R.string.home_photos_full)
        Build.VERSION.SDK_INT >= 34 && context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED -> context.getString(R.string.home_photos_selected)
        Build.VERSION.SDK_INT == 33 && context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED && context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED -> context.getString(R.string.home_photos_granted)
        Build.VERSION.SDK_INT <= 32 && context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED -> context.getString(R.string.home_photos_granted)
        else -> context.getString(R.string.home_photos_not_granted)
    }
    fun canReadPhotos() = hasVisualAccess()
    /** [quiet]: the photos already shown stay while they are read again (Photos opened again, back to the app). */
    fun loadPhotos(quiet: Boolean = false) {
        if (!canReadPhotos()) { photosState = ListState.Error(context.getString(R.string.home_photos_allow)); return }
        val shown = (photosState as? ListState.Items)?.entries?.mapTo(HashSet(), Entry::photoKey)
        if (!quiet || shown == null) photosState = ListState.Loading
        Thread {
            val read = runCatching {
                val all = listDeviceMedia(context)
                val choices = photoMetadata.folderChoices()
                Triple(all, choices, showMotionAsOne(all.filter { FolderRules.isIncluded(it.relativePath, choices) }))
            }
            val result = read.map { it.third }
            (context as MainActivity).runOnUiThread {
                photosState = result.fold(
                    onSuccess = { ListState.Items(it) },
                    onFailure = { ListState.Error(context.getString(R.string.home_photos_unreadable, it.message ?: context.getString(R.string.home_permission_lost))) },
                )
                // Whatever is new here has never been read: do it now, not the next time People is opened.
                if (result.isSuccess) PhotoAnalysisService.start(context)
                // A photo or video that was not here a moment ago goes to the computer now (asked 2026-10-01), not
                // only from SyncJob's trigger, which MIUI cancels once the app is in the background.
                if (shown != null && result.getOrNull().orEmpty().any { it.photoKey !in shown }) Thread { SyncService.syncInBackground(context, gap = 0) }.start()
            }
            // after the timeline shows (25 Oct: it waited for this): the folders, each included one's album kept full as new photos land in it
            read.getOrNull()?.let { (all, choices) -> runCatching {
                val folders = deviceFolders(all, choices)
                folders.filter { it.included == true }.forEach { folder ->
                    photoMetadata.fillFolderAlbum(folder.name, folder.entries.map(Entry::photoKey))
                    photoMetadata.pruneFolderAlbum(folder.name, all.filter { !FolderRules.albumName(it.relativePath).equals(folder.name, ignoreCase = true) }.map(Entry::photoKey))
                }
                (context as MainActivity).runOnUiThread { photoFolders = folders }
            } }
        }.start()
    }
    val requestPhotos = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { loadPhotos() }
    val requestTrash = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) loadPhotos()
    }
    fun closeGuide() {
        preferences.edit().putBoolean(PHOTO_SETUP_COMPLETED, true).apply()
        guideAt = null
        if (screen == Screen.Guide) screen = Screen.Home
    }
    // Moving another app's photo to another folder needs Android's own write consent first (asked once per move).
    var pendingMove by remember { mutableStateOf<Pair<List<Entry>, String>?>(null) }
    val requestMove = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val move = pendingMove
        pendingMove = null
        if (result.resultCode == Activity.RESULT_OK && move != null) Thread {
            movePhotosToFolder(context, move.first, move.second, photoMetadata)
            (context as MainActivity).runOnUiThread { metadataVersion++; loadPhotos() }
        }.start()
    }
    fun moveSelectedToFolder(uris: Set<Uri>, dest: String) {
        val chosen = (photosState as? ListState.Items)?.entries.orEmpty().filter { it.contentUri in uris }
        if (chosen.isEmpty()) return
        pendingMove = chosen to dest
        runCatching { MediaStore.createWriteRequest(context.contentResolver, chosen.mapNotNull(Entry::contentUri)) }
            .onSuccess { requestMove.launch(IntentSenderRequest.Builder(it.intentSender).build()) }
            .onFailure { pendingMove = null; photosState = ListState.Error(context.getString(R.string.home_photos_not_moved, it.message ?: context.getString(R.string.home_request_failed))) }
    }
    fun movePhotosToTrash(uris: Set<Uri>) {
        if (uris.isEmpty()) return
        runCatching { MediaStore.createTrashRequest(context.contentResolver, uris.toList(), true) }
            .onSuccess { requestTrash.launch(IntentSenderRequest.Builder(it.intentSender).build()) }
            .onFailure { photosState = ListState.Error(context.getString(R.string.home_photos_not_trashed, it.message ?: context.getString(R.string.home_request_failed))) }
    }
    // A finished sync has brought photos, files and other devices' edits in. Show them without being asked:
    // nothing is more confusing than a sync that says it is done over a page that still shows the old contents.
    val appScope = rememberCoroutineScope()
    val syncState by SyncService.state.collectAsState()
    LaunchedEffect(syncState?.result) {
        if (syncState?.result == null) return@LaunchedEffect
        metadataVersion++
        recentsVersion++
        loadPhotos()
        loadDrive()
    }
    fun selectedEntries(uris: Set<Uri>) = (photosState as? ListState.Items)?.entries.orEmpty().filter { it.contentUri in uris }
    fun metadataAction(action: () -> Unit): String? = runCatching(action).fold(
        onSuccess = { metadataVersion++; null },
        onFailure = { it.message ?: context.getString(R.string.home_metadata_not_saved) },
    )
    fun openDriveFile(relative: String, forceChooser: Boolean): String? = runCatching {
        val file = DriveRules.file(driveRoot(), relative)
        // a mail of Mail's folder opens in Mail (reached by «Αποθήκη» › «Άνοιγμα φακέλου»)
        if (!forceChooser && relative.startsWith(MailSync.PART) && file.extension.equals("eml", true)) {
            MailFiles.index(MailFiles.root(driveRoot()), MailEngine.device()).firstOrNull { it.file == relative.removePrefix(MailSync.PART) }
                ?.let { openApplet(context, "mail:${it.id}"); return@runCatching }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT)) ?: "application/octet-stream"
        val viewIntent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .also { it.clipData = ClipData.newRawUri(file.name, uri) }
        fun chooser() {
            val callback = Intent(context, DriveOpenerReceiver::class.java)
                .setAction(FILE_OPENER_CHOSEN)
                .setData(Uri.Builder().scheme("localdrive").authority("opener").appendPath(relative).build())
                .putExtra(FILE_OPENER_PATH, relative)
            val pending = PendingIntent.getBroadcast(
                context,
                relative.hashCode(),
                callback,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            context.startActivity(Intent.createChooser(viewIntent, context.getString(R.string.home_open_with), pending.intentSender))
        }
        // A Word or LibreOffice document opens in Writer (Settings › Office); Open with… still offers other apps.
        if (!forceChooser && file.extension.lowercase(Locale.ROOT) in setOf("docx", "odt", "doc", "rtf") && TextEditorStore(context).opensOffice) {
            WriterLog.i("open from Files: ${file.name}")
            driveRecents.record(driveRoot(), relative, System.currentTimeMillis()); recentsVersion++
            writerOpen = Uri.fromFile(file).toString(); writerBack = screen; screen = Screen.Writer
            return@runCatching
        }
        // A spreadsheet opens in Tables (Settings › Office: its own switch).
        if (!forceChooser && Sheets.isSheet(file.name) && TextEditorStore(context).opensSheets) {
            WriterLog.i("open from Files: a spreadsheet")
            driveRecents.record(driveRoot(), relative, System.currentTimeMillis()); recentsVersion++
            sheetOpen = Uri.fromFile(file).toString(); writerBack = screen; screen = Screen.Sheet
            return@runCatching
        }
        // A presentation opens in Slides (its own switch).
        if (!forceChooser && Slides.isSlides(file.name) && TextEditorStore(context).opensSlides) {
            WriterLog.i("open from Files: a presentation")
            driveRecents.record(driveRoot(), relative, System.currentTimeMillis()); recentsVersion++
            slidesOpen = Uri.fromFile(file).toString(); writerBack = screen; screen = Screen.Slides
            return@runCatching
        }
        // A PDF opens in Office's viewer (its own switch): the scanner's too, as they are saved into Files.
        if (!forceChooser && Pdf.isPdf(file.name) && TextEditorStore(context).opensPdfs) {
            WriterLog.i("open from Files: a PDF")
            driveRecents.record(driveRoot(), relative, System.currentTimeMillis()); recentsVersion++
            pdfOpen = Uri.fromFile(file).toString(); writerBack = screen; screen = Screen.Pdf
            return@runCatching
        }
        val opener = if (file.extension.equals("txt", ignoreCase = true) && TextEditorStore(context).opensTxt) ComponentName(context, TextEditorActivity::class.java)
            else driveOpeners.component(relative)
        if (forceChooser || opener == null) chooser() else runCatching {
            context.startActivity(Intent(viewIntent).setComponent(opener))
        }.getOrElse {
            driveOpeners.clear(relative)
            chooser()
        }
        driveRecents.record(driveRoot(), relative, System.currentTimeMillis())
        recentsVersion++
    }.fold(onSuccess = { null }, onFailure = { it.message ?: context.getString(R.string.home_file_not_opened) })
    fun saveScan(name: String) {
        if (scanSaving) return
        scanSaving = true
        scannerError = null
        val pages = scanPages
        Thread {
            val result = runCatching {
                val dpi = context.getSharedPreferences("scanner", Context.MODE_PRIVATE).getInt(SCANNER_DPI, 300)
                ScanFiles.savePdf(driveRoot(), name, pages.size) { index ->
                    checkNotNull(renderScanPage(pages[index], 1, scanMaxEdge(dpi))) { context.getString(R.string.home_scan_page_unreadable, index + 1) }
                }
            }
            (context as MainActivity).runOnUiThread {
                scanSaving = false
                result.fold(
                    onSuccess = {
                        // A new scan is worth sending straight away, once the page has settled and the file is
                        // on disk — off the UI thread, because it reads preferences and asks about the network.
                        appScope.launch { delay(1_500); withContext(Dispatchers.IO) { SyncService.syncInBackground(context, gap = 0) } }
                        clearScanPages()
                        unlockScannerOrientation()
                        screen = Screen.Drive
                        drivePane = DrivePane.Files
                        driveFolder = "Documents/Scanned Documents"
                        loadDrive("Documents/Scanned Documents")
                    },
                    onFailure = { scannerError = it.message ?: context.getString(R.string.home_scan_not_saved) },
                )
            }
        }.start()
    }
    /**
     * Pages from files into the scanner ([load] writes them in [into], the scanner's cache): added to the scan open
     * now, or a new scan when [fresh] (the PDF editor's Open in scanner, Files' own).
     */
    /** The auto fix's straightening of [pages], in the background; a page changed by hand meanwhile keeps the hand's. */
    fun straightenScans(pages: List<CapturedPage>) {
        val todo = pages.filter { it.autoFix }
        if (todo.isEmpty()) return
        Thread {
            todo.forEach { before ->
                val after = runCatching { autoStraightened(before) }.onFailure { WriterLog.w("Scanner: straighten", it) }.getOrNull() ?: return@forEach
                (context as MainActivity).runOnUiThread {
                    scanPages = scanPages.map { if (it == before) after else it }
                }
            }
        }.start()
    }
    fun importScan(fresh: Boolean, load: (File) -> List<File>) {
        if (fresh) { clearScanPages(); lockScannerPortrait() }
        scannerError = null
        Toast.makeText(context, R.string.scanner_importing, Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching { load(File(context.cacheDir, "scans")).map { CapturedPage(it) } }
            (context as MainActivity).runOnUiThread {
                result.onSuccess { added ->
                    if (added.isEmpty()) return@onSuccess
                    scanSelected = scanPages.size
                    scanPages = scanPages + added
                    straightenScans(added)
                }.onFailure { WriterLog.e("Scanner: import", it); scannerError = context.getString(R.string.scanner_import_failed) }
                if (scanPages.isNotEmpty()) screen = Screen.ScanDocument
                else if (fresh) Toast.makeText(context, R.string.scanner_import_failed, Toast.LENGTH_LONG).show()
            }
        }.start()
    }
    // Home's Scanner widget: a PDF or pictures opened into the scanner's pages, as the scanner's own Open does
    val homeScanPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importScan(true) { into -> uris.flatMap { ScanFiles.importPages(context, it, into) } }
    }
    // Back from the camera returns to the document while it has pages; only an empty scan leaves the scanner.
    fun leaveScanner() {
        scanRetake = null
        if (scanPages.isNotEmpty()) screen = Screen.ScanDocument
        else { unlockScannerOrientation(); screen = Screen.Home }
    }
    /** [f] in Words, Tables, Slides or the PDF viewer, by its kind; Back goes to [from]. */
    fun openOfficeFile(f: File, from: Screen) {
        val at = Uri.fromFile(f).toString(); writerBack = from
        when {
            Sheets.isSheet(f.name) -> { sheetOpen = at; screen = Screen.Sheet }
            Slides.isSlides(f.name) -> { slidesOpen = at; screen = Screen.Slides }
            Pdf.isPdf(f.name) -> { pdfOpen = at; screen = Screen.Pdf }
            else -> { writerOpen = at; screen = Screen.Writer }
        }
    }
    // Home's Office card: Open Words, Tables, Slides, PDFs — each Android's picker for that kind only (asked 17 Oct)
    val pickOffice = rememberOfficePicker { openOfficeFile(it, Screen.Home) }
    // an applet icon's part (Settings › App icons), as its card on Home opens it; Back goes to Home (first-run photo setup stays first)
    LaunchedEffect(applet.value) {
        // an applet with a window of its own (AppletActivities.kt): asked of Tetra (a notification, AI Assist, a mailto: link), it opens there
        applet.value?.takeIf { hasAppletWindow(it) && screen != Screen.Guide }?.let { v -> applet.value = null; openApplet(context, v); return@LaunchedEffect }
        // "2 files from <computer>": its tap opens Files at From computer
        if (applet.value == FromComputer.SCREEN && screen != Screen.Guide) {
            applet.value = null; screen = Screen.Drive; drivePane = DrivePane.Files; driveFolder = FromComputer.FOLDER; loadDrive(FromComputer.FOLDER); return@LaunchedEffect
        }
        // a map turned old (MapsAge): Settings › Maps
        if (applet.value == MapsAge.SCREEN && screen != Screen.Guide) { applet.value = null; settingsStart = SettingsPage.Maps; screen = Screen.Settings; return@LaunchedEffect }
        if (applet.value == PILLS_SETTINGS && screen != Screen.Guide) { applet.value = null; settingsStart = SettingsPage.Pills; screen = Screen.Settings; return@LaunchedEffect }
        if (applet.value == BROWSER_SETTINGS && screen != Screen.Guide) { applet.value = null; settingsStart = SettingsPage.Browser; screen = Screen.Settings; return@LaunchedEffect }
        if (applet.value == PASSWORDS_SETTINGS && screen != Screen.Guide) { applet.value = null; settingsStart = SettingsPage.Passwords; screen = Screen.Settings; return@LaunchedEffect }
        if (applet.value == LocationReminders.SCREEN && screen != Screen.Guide) { applet.value = null; settingsStart = SettingsPage.Calendar; screen = Screen.Settings; return@LaunchedEffect }
        if (applet.value == MailActivity.SETTINGS && screen != Screen.Guide) { applet.value = null; settingsStart = SettingsPage.Mail; screen = Screen.Settings; return@LaunchedEffect }
        // Mail's «Άνοιγμα φακέλου»: its folder in Files
        applet.value?.takeIf { it.startsWith("folder:") && screen != Screen.Guide }?.let { v ->
            applet.value = null; val rel = v.removePrefix("folder:"); screen = Screen.Drive; drivePane = DrivePane.Files; driveFolder = rel; loadDrive(rel); return@LaunchedEffect
        }
        // AI Assist's file: opened beside (Beside)
        applet.value?.takeIf { it.startsWith("file:") && screen != Screen.Guide }?.let { v ->
            applet.value = null; openDriveFile(v.removePrefix("file:"), false)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }; return@LaunchedEffect
        }
        val a = Applet.of(applet.value) ?: return@LaunchedEffect
        applet.value = null
        if (screen == Screen.Guide) return@LaunchedEffect
        WriterLog.i("App icon: $a")
        when (a) {
            Applet.Photos -> { screen = Screen.Photos; photosPane = PhotosPane.Timeline; photoFilter = PhotoFilter.Timeline; loadPhotos() }
            Applet.Files -> { screen = Screen.Drive; drivePane = DrivePane.Files; driveFolder = ""; loadDrive("") }
            Applet.Scanner -> { lockScannerPortrait(); clearScanPages(); scannerError = null; screen = Screen.Scanner }
            Applet.Office -> screen = Screen.Office
            else -> openApplet(context, a.name)
        }
    }
    // a file another app opened here: in Words, Tables, Slides or the PDF viewer, its back to Office
    LaunchedEffect(office) {
        val o = office ?: return@LaunchedEffect
        val f = withContext(Dispatchers.IO) { runCatching { OfficeOpen.file(context, o.uri, Environment.getExternalStorageDirectory(), File(driveRoot(), "Documents/From other apps"), o.mimeType) }
            .onFailure { WriterLog.e("Office: a file from another app could not be read", it) }.getOrNull() }
        if (f == null) { Toast.makeText(context, context.getString(R.string.home_that_file_not_opened), Toast.LENGTH_LONG).show(); return@LaunchedEffect }
        WriterLog.i("Office: opened from another app (${f.extension})")
        openOfficeFile(f, Screen.Office)
    }
    LaunchedEffect(external) {
        if (external == null) return@LaunchedEffect
        if (externalMediaId != null && canReadPhotos()) {
            screen = Screen.Photos
            photosPane = PhotosPane.Timeline
            photoFilter = PhotoFilter.Timeline
            loadPhotos()
        } else externalSingle = true
    }
    fun guideTry(go: GuideGo) { when (go) {
        GuideGo.Home -> screen = Screen.Home
        GuideGo.Files -> { screen = Screen.Drive; drivePane = DrivePane.Files; driveFolder = ""; loadDrive("") }
        GuideGo.Settings -> screen = Screen.Settings
        GuideGo.Sync -> screen = Screen.Sync
        GuideGo.None -> screen = Screen.Guide
    } }
    // the docked step's Back/Next: a step on another screen goes there, a step with nothing to try back to the guide
    fun guideTo(at: Int) {
        val was = guideAt?.let(GuidePlace::step)?.go
        guideAt = at
        GuidePlace.step(at).go.let { if (it != was || it == GuideGo.None) guideTry(it) }
    }
    BackHandler(enabled = screen != Screen.Home && screen != Screen.ScanDocument && screen != Screen.Guide) {
        when (screen) {
            Screen.Photos -> screen = Screen.Home
            Screen.Scanner -> leaveScanner()
            Screen.Drive -> when {
                drivePane == DrivePane.Files && driveFolder.isNotEmpty() -> {
                    driveFolder = DriveRules.parent(driveFolder)
                    loadDrive()
                }
                drivePane == DrivePane.Files -> drivePane = DrivePane.Home
                else -> screen = Screen.Home
            }
            else -> screen = Screen.Home
        }
    }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) when (currentScreen) {
                Screen.Drive -> if (currentDrivePane == DrivePane.Files) loadDrive()
                Screen.Photos -> loadPhotos(quiet = true)
                Screen.Guide -> Unit
                Screen.Home -> Unit
                Screen.Sync -> Unit
                Screen.Settings -> Unit
                Screen.Scanner -> Unit
                Screen.ScanDocument -> Unit
                Screen.Office -> Unit
                Screen.Writer -> Unit
                Screen.Sheet -> Unit
                Screen.Slides -> Unit
                Screen.Pdf -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // While Files is open, what changes on disk shows at once: the folder list reloads, and search, which reads
    // the disk afresh, runs again with it. Quiet, so a file arriving does not flash the list.
    androidx.compose.runtime.DisposableEffect(screen) {
        val watcher = if (screen == Screen.Drive && hasAllFilesAccess()) DriveWatcher(driveRoot()) { driveDiskVersion++; loadDrive(quiet = true) } else null
        onDispose { watcher?.stop() }
    }

    // One palette, on every device (24 September). Dynamic colour read the wallpaper, so the same home screen
    // was blue on the phone and purple on the tablet; following the system's light mode gave a half-built light
    // theme where the red Scanner card had dark text on it. These are the phone's own colours, measured off it,
    // held fixed. Light (Roadmap F) is the user's choice since 21 Oct: TetraLook, the one place that decides.
    val view = androidx.compose.ui.platform.LocalView.current
    // dark icons on light pages; Office's editors and the cameras keep their dark frames in both looks, so light icons there
    val lightBars = TetraLook.light && screen !in setOf(Screen.Writer, Screen.Sheet, Screen.Slides, Screen.Pdf, Screen.Scanner, Screen.ScanDocument)
    androidx.compose.runtime.SideEffect {
        (view.context as? Activity)?.window?.let { w -> WindowCompat.getInsetsController(w, view).run {
            isAppearanceLightStatusBars = lightBars; isAppearanceLightNavigationBars = lightBars } }
    }
    MaterialTheme(colorScheme = TetraLook.colors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
        CalendarRinging()
        Column(Modifier.fillMaxSize()) {
            if (externalSingle && external != null) ExternalMediaViewer(external, ::closeExternal)
            // a step's Try it: the real screen, the step docked under it
            else GuideDock(guideAt.takeIf { screen != Screen.Guide }, go = ::guideTo, full = { screen = Screen.Guide }, close = ::closeGuide) { when (screen) {
                Screen.Guide -> GuideScreen(guideAt ?: 0, go = { guideAt = it }, tryIt = ::guideTry, close = ::closeGuide)
                Screen.Home -> Home(
                    showPhotoBackdrop = homePhotoBackdrop,
                    hasPhotoAccess = canReadPhotos(),
                    photoMetadata = photoMetadata,
                    openPhotos = { screen = Screen.Photos; photosPane = PhotosPane.Timeline; photoFilter = PhotoFilter.Timeline; loadPhotos(quiet = true) },
                    openScreenshots = { screen = Screen.Photos; photosPane = PhotosPane.Timeline; photoFilter = PhotoFilter.Screenshots; loadPhotos(quiet = true) },
                    openDocuments = { screen = Screen.Photos; photosPane = PhotosPane.Timeline; photoFilter = PhotoFilter.Documents; loadPhotos(quiet = true) },
                    // opened again: the list as it was at once, read again behind it
                    openFiles = { screen = Screen.Drive; drivePane = DrivePane.Files; driveFolder = ""; loadDrive("", quiet = (driveState as? DriveListState.Items)?.folder == "") },
                    openFavorites = { screen = Screen.Drive; drivePane = DrivePane.Favorites; driveFolder = "" },
                    openRecent = { screen = Screen.Drive; drivePane = DrivePane.Home; driveFolder = "" },
                    openSync = { screen = Screen.Sync },
                    openSettings = { screen = Screen.Settings },
                    openScanner = { lockScannerPortrait(); clearScanPages(); scannerError = null; screen = Screen.Scanner },
                    openCodes = { openApplet(context, Applet.Codes.name) },
                    openNotes = { openApplet(context, Applet.Notes.name) },
                    newNote = { checklist -> openApplet(context, if (checklist) NotesActivity.NEW_CHECKLIST else NotesActivity.NEW_NOTE) },
                    openTextEditor = { start -> openApplet(context, start?.let { "text:$it" } ?: Applet.TextEditor.name) },
                    openOffice = { screen = Screen.Office },
                    pickOffice = pickOffice,
                    openCalendar = { openApplet(context, Applet.Calendar.name) },
                    newEvent = { kind -> openApplet(context, CalendarActivity.NEW + kind.name) },
                    openTimer = { openApplet(context, Applet.Timer.name) },
                    openMaps = { place -> openApplet(context, place?.let { MapsActivity.PLACE + it.json() } ?: Applet.Maps.name) },
                    openContacts = { uid -> openApplet(context, uid?.let { "contact:$it" } ?: Applet.Contacts.name) },
                    openMail = { openApplet(context, Applet.Mail.name) },
                    openSms = { openApplet(context, Applet.Messages.name) },
                    openBrowser = { openBrowser(context) },
                    // Home's Files favourites and Scanner's Open file (8 Nov)
                    openDriveItem = { rel, dir -> if (dir) { screen = Screen.Drive; drivePane = DrivePane.Files; driveFolder = rel; loadDrive(rel) } else openDriveFile(rel, false)?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } },
                    openScanFile = { homeScanPicker.launch(arrayOf("application/pdf", "image/*")) },
                )
                Screen.Drive -> DriveTab(
                    home = { screen = Screen.Home },
                    openInScanner = { f -> importScan(true) { into -> ScanFiles.importPages(context, Uri.fromFile(f), into) } },
                    pane = drivePane,
                    setPane = { drivePane = it },
                    folder = driveFolder,
                    state = driveState,
                    hasAccess = hasAllFilesAccess(),
                    grant = {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
                    },
                    refresh = ::loadDrive,
                    openFolder = { relative -> driveFolder = relative; drivePane = DrivePane.Files; loadDrive(relative) },
                    goParent = {
                        if (driveFolder.isEmpty()) drivePane = DrivePane.Home else {
                            driveFolder = DriveRules.parent(driveFolder)
                            loadDrive()
                        }
                    },
                    recents = { driveRecents.read(driveRoot()) },
                    recentsVersion = recentsVersion,
                    diskVersion = driveDiskVersion,
                    openFile = ::openDriveFile,
                )
                Screen.Photos -> PhotoTab({ screen = Screen.Home }, photosPane, { photosPane = it }, photoFilter, { photoFilter = it }, photoPermission(), photosState, photoMetadata, metadataVersion, {
                    requestPhotos.launch(photoPermissions())
                }, ::loadPhotos, ::movePhotosToTrash,
                    { uris, favorite -> metadataAction { photoMetadata.setFavorite(selectedEntries(uris).map { it.photoKey }, favorite) } },
                    { collectionId, uris -> metadataAction { photoMetadata.addToCollection(collectionId, selectedEntries(uris).map { it.photoKey }) } },
                    { collectionId, uris -> metadataAction { photoMetadata.removeFromCollection(collectionId, selectedEntries(uris).map { it.photoKey }) } },
                    { name -> runCatching { photoMetadata.createCollection(name) }.onSuccess { metadataVersion++ } },
                    { collectionId -> metadataAction { photoMetadata.deleteCollection(collectionId) } },
                    folders = photoFolders,
                    moveToFolder = ::moveSelectedToFolder,
                    setFolderIncluded = { name, on -> photoMetadata.setFolderIncluded(name, on); loadPhotos() },
                    externalMediaId = externalMediaId,
                    externalMissing = { externalSingle = true },
                    closeExternal = if (external != null) ::closeExternal else null,
                )
                Screen.Sync -> SyncTab(back = { screen = Screen.Home })
                Screen.Office -> androidx.compose.runtime.key(officeVersion) {
                    OfficeHome(back = { screen = Screen.Home }, hasAccess = hasAllFilesAccess(), grant = {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
                    }, open = { writerOpen = it; writerBack = Screen.Office; screen = Screen.Writer },
                    openSheet = { sheetOpen = it; writerBack = Screen.Office; screen = Screen.Sheet },
                    openSlides = { slidesOpen = it; writerBack = Screen.Office; screen = Screen.Slides },
                    openPdf = { pdfOpen = it; writerBack = Screen.Office; screen = Screen.Pdf })
                }
                Screen.Writer -> WriterScreen(back = { officeVersion++; screen = writerBack }, open = writerOpen)
                Screen.Sheet -> sheetOpen?.let { androidx.compose.runtime.key(it) { SheetScreen(back = { screen = writerBack }, open = it) } }
                Screen.Slides -> slidesOpen?.let { androidx.compose.runtime.key(it) { SlidesScreen(back = { screen = writerBack }, open = it) } }
                Screen.Pdf -> pdfOpen?.let { androidx.compose.runtime.key(it) { PdfScreen(back = { screen = writerBack }, open = it, openWords = { w -> writerOpen = w; screen = Screen.Writer },
                    openInScanner = { d -> importScan(true) { into -> ScanFiles.pdfPages(d, into.also { f -> f.mkdirs() }) } }) } }
                Screen.Scanner -> CameraScanTab(
                    back = ::leaveScanner,
                    error = scannerError,
                    pages = scanPages,
                    openPages = { scanRetake = null; screen = Screen.ScanDocument },
                    captured = { page ->
                        val retakeIndex = scanRetake?.takeIf { it in scanPages.indices }
                        if (retakeIndex != null) {
                            val replaced = scanPages[retakeIndex]
                            quarantineScanFiles(replaced)
                            scanPages = scanPages.toMutableList().also { it[retakeIndex] = if (replaced.filterByHand) page.copy(filter = replaced.filter, filterByHand = true) else page }
                            scanSelected = retakeIndex
                        } else {
                            scanPages = scanPages + page
                            scanSelected = scanPages.lastIndex
                        }
                        scannerError = null
                        straightenScans(listOf(scanPages[scanSelected]))
                        // A retake returns to its page; normal captures stay in the camera for the next page.
                        if (retakeIndex != null) screen = Screen.ScanDocument
                        scanRetake = null
                    },
                )
                Screen.ScanDocument -> ScanDocumentTab(
                    pages = scanPages,
                    selected = scanSelected,
                    select = { scanSelected = it },
                    update = { index, page -> scanPages = scanPages.toMutableList().also { it[index] = page } },
                    move = { from, to ->
                        scanPages = scanPages.toMutableList().also { it.add(to, it.removeAt(from)) }
                        if (scanSelected == from) scanSelected = to
                    },
                    retake = { index -> scanRetake = index; screen = Screen.Scanner },
                    addPage = { scanRetake = null; screen = Screen.Scanner },
                    importFiles = { uris -> importScan(false) { into -> uris.flatMap { ScanFiles.importPages(context, it, into) } } },
                    discard = { clearScanPages(); unlockScannerOrientation(); screen = Screen.Home },
                    saving = scanSaving,
                    error = scannerError,
                    save = ::saveScan,
                )
                Screen.Settings -> SettingsTab(
                    back = { if (CarMaps.edition) (context as? Activity)?.finish() else screen = Screen.Home },
                    start = settingsStart, started = { settingsStart = null },
                    metadataStore = photoMetadata,
                    photoPermission = photoPermission(),
                    photoAccess = hasVisualAccess(),
                    hasDriveAccess = hasAllFilesAccess(),
                    showPhotoBackdrop = homePhotoBackdrop,
                    setShowPhotoBackdrop = { enabled ->
                        homePhotoBackdrop = enabled
                        preferences.edit().putBoolean(HOME_PHOTO_BACKDROP, enabled).apply()
                    },
                    managePhotos = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    },
                    manageDrive = {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
                    },
                    openSync = { screen = Screen.Sync },
                    pairedDevice = pairedDevice,
                    forgetPairedDevice = { syncStore.forgetPairing(); pairedDevice = null; SyncListenService.stop(context) },
                    folders = photoFolders,
                    setFolderIncluded = { name, on -> photoMetadata.setFolderIncluded(name, on); loadPhotos() },
                    // a part hidden from Home opens from Settings › Home, as Home's own card would
                    openPart = { id -> when (id) {
                        "sync" -> screen = Screen.Sync
                        "gallery" -> { screen = Screen.Photos; photosPane = PhotosPane.Timeline; photoFilter = PhotoFilter.Timeline; loadPhotos() }
                        "files" -> { screen = Screen.Drive; drivePane = DrivePane.Files; driveFolder = ""; loadDrive("") }
                        "notes" -> openApplet(context, Applet.Notes.name)
                        "scanner" -> { lockScannerPortrait(); clearScanPages(); scannerError = null; screen = Screen.Scanner }
                        "codes" -> openApplet(context, Applet.Codes.name)
                        "office" -> screen = Screen.Office
                        "text" -> openApplet(context, Applet.TextEditor.name)
                        "calendar" -> openApplet(context, Applet.Calendar.name)
                        "timer" -> openApplet(context, Applet.Timer.name)
                        "pills" -> openApplet(context, Applet.Pills.name)
                        "maps" -> openApplet(context, Applet.Maps.name)
                        "contacts" -> openApplet(context, Applet.Contacts.name)
                        "phone" -> openPhone(context)
                        "data" -> openData(context)
                        "mail" -> openApplet(context, Applet.Mail.name)
                        "sms" -> openApplet(context, Applet.Messages.name)
                        "browser" -> openBrowser(context)
                        "passwords" -> openApplet(context, Applet.Passwords.name)
                        // «Αποθήκη» › «Άνοιγμα φακέλου»: Mail's folder in Files, though it is under .System
                        else -> if (id == MailFiles.FOLDER || id.startsWith(MailFiles.FOLDER + "/")) { screen = Screen.Drive; drivePane = DrivePane.Files; driveFolder = id; loadDrive(id) }
                    } },
                    openGuide = { guideAt = 0; screen = Screen.Guide },
                )
            } }
        }
        }
    }
}

private enum class Screen { Guide, Home, Drive, Photos, Sync, Settings, Scanner, ScanDocument, Office, Writer, Sheet, Slides, Pdf }
private enum class DrivePane { Home, Favorites, Files }
private enum class DriveSort { Name, Modified }
private enum class PhotosPane { Timeline, Collections }
private sealed interface PhotoFilter {
    data object Timeline : PhotoFilter
    data object Favorites : PhotoFilter
    data object People : PhotoFilter
    data object Documents : PhotoFilter
    data object Screenshots : PhotoFilter
    data object Videos : PhotoFilter
    data object Review : PhotoFilter
    data object Hidden : PhotoFilter
    data object Trash : PhotoFilter
    data object Map : PhotoFilter
    data class Collection(val id: Long) : PhotoFilter
}

@Composable
private fun Home(
    showPhotoBackdrop: Boolean,
    hasPhotoAccess: Boolean,
    photoMetadata: PhotoMetadataStore,
    openPhotos: () -> Unit,
    openScreenshots: () -> Unit,
    openDocuments: () -> Unit,
    openFiles: () -> Unit,
    openFavorites: () -> Unit,
    openRecent: () -> Unit,
    openSync: () -> Unit,
    openSettings: () -> Unit,
    openScanner: () -> Unit,
    openCodes: () -> Unit,
    openNotes: () -> Unit,
    openTextEditor: (String?) -> Unit,
    openOffice: () -> Unit,
    pickOffice: (OfficePick) -> Unit,
    newNote: (checklist: Boolean) -> Unit,
    openCalendar: () -> Unit,
    newEvent: (NewKind) -> Unit,
    openTimer: () -> Unit,
    openMaps: (Place?) -> Unit,
    openContacts: (String?) -> Unit,
    openMail: () -> Unit,
    openSms: () -> Unit,
    openBrowser: () -> Unit,
    openDriveItem: (String, Boolean) -> Unit = { _, _ -> },
    openScanFile: () -> Unit = {},
) {
    // A tablet shows the groups two by two (asked 2026-09-26); a phone one under another.
    val wide = LocalConfiguration.current.screenWidthDp >= 700
    val context = LocalContext.current
    // its database read off the screen's thread; the last line said until it answers
    val syncSummary by produceState(homeSyncSummary, context) { value = withContext(Dispatchers.IO) { SyncStore(context.applicationContext).summary() }.also { homeSyncSummary = it } }
    OpenTiming("Home", true)
    // each part's group, as Home has always shown them
    // Each large part one HomeWidget (the council, 8 Nov): the app's icon and name in the header open it, the content fills
    // the rest; tiles in one row, lists on a panel. Photos keep their picture (it is the content) and Scanner its two apps.
    val large: @Composable (String) -> Unit = { id -> when (id) {
        "gallery" -> {
            // Gallery's green, as the new Gallery picture in the Photos card (asked 16 Oct), the way Notes and Office have theirs
            // the photo is the content (8 Nov: "nice that it shows the photos, but the buttons next to it too big"): two thirds of
            // the width, Screenshots and Documents small beside it; a narrow (medium) card shows the photo alone
            HomeGroup(Category.Gallery.color) {
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val beside = maxWidth > 320.dp
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        HomePhotosCard(openPhotos, showPhotoBackdrop, hasPhotoAccess, photoMetadata, Modifier.weight(2f))
                        if (beside) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            HomeMiniTile(stringResource(R.string.home_screenshots), R.drawable.ic_screenshot, MaterialTheme.colorScheme.onSurface, Modifier.fillMaxWidth(), openScreenshots)
                            HomeMiniTile(stringResource(R.string.home_documents), R.drawable.ic_file, MaterialTheme.colorScheme.onSurface, Modifier.fillMaxWidth(), openDocuments)
                        }
                    }
                }
            }
        }
        "files" -> if (!LocalHomeMedium.current) HomeFavouriteFiles(openFiles, openDriveItem) else HomeWidget(MaterialTheme.colorScheme.tertiaryContainer, R.drawable.files_art, stringResource(R.string.app_files), openFiles, panel = false) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeTile(stringResource(R.string.home_favorites), R.drawable.ic_favorite_border, MaterialTheme.colorScheme.tertiary, Modifier.weight(1f), openFavorites)
                HomeTile(stringResource(R.string.home_recent), R.drawable.ic_home, MaterialTheme.colorScheme.tertiary, Modifier.weight(1f), openRecent)
            }
        }
        "notes" -> if (!LocalHomeMedium.current) HomePinnedNotes(openNotes) { newNote(false) } else HomeWidget(Category.Notes.color, R.drawable.notes_art, stringResource(R.string.app_notes), openNotes, panel = false) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeTile(stringResource(R.string.home_checklist), R.drawable.ic_checklist, lookTint(Color(0xFFFFE082), Color(0xFF8D6E00)), Modifier.weight(1f)) { newNote(true) }
                HomeTile(stringResource(R.string.home_new_note), R.drawable.ic_note_add, lookTint(Color(0xFFFFE082), Color(0xFF8D6E00)), Modifier.weight(1f)) { newNote(false) }
            }
        }
        // Scanner & Codes (8 Nov): scan, codes, and «Άνοιγμα αρχείου» for a PDF or pictures, into the scanner's pages
        "scanner" -> HomeWidget(Category.Scanner.color, R.drawable.scanner_art, stringResource(R.string.app_scanner), openScanner, panel = false) {
            HomeTiles(listOf(Triple(R.string.app_scanner, R.drawable.scanner_art, openScanner), Triple(R.string.app_codes, R.drawable.codes_art, openCodes), Triple(R.string.home_open_file, R.drawable.ic_folder, openScanFile),
                Triple(R.string.codes_from_image, R.drawable.ic_gallery, { openApplet(context, CodesActivity.IMAGE) }))) { (label, icon, go), m ->
                HomeTile(stringResource(label), icon, if (icon == R.drawable.ic_folder || icon == R.drawable.ic_gallery) MaterialTheme.colorScheme.onSurface else Color.Unspecified, m, go)
            }
        }
        // Office (asked 2026-09-27): orange; the four apps' Open in one row (asked 17 Oct), each the picker for its own kind
        "office" -> HomeWidget(Category.Office.color, R.drawable.office_art, stringResource(R.string.app_office), openOffice, panel = false) {
            HomeTiles(listOf(OfficePick.Words, OfficePick.Tables, OfficePick.Slides, OfficePick.Pdf)) { k, m -> HomeOpenTile(k, wide, m) { pickOffice(k) } }
        }
        // The text editor: purple (asked 2026-09-27).
        "text" -> if (!LocalHomeMedium.current) HomeRecentTexts({ openTextEditor(null) }) { openTextEditor("new") } else HomeWidget(Category.Text.color, R.drawable.text_editor_art, stringResource(R.string.app_text_editor), { openTextEditor(null) }, panel = false) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeTile(stringResource(R.string.home_new_file), R.drawable.ic_note_add, lookTint(Color(0xFFD1B3FF), Color(0xFF5E35B1)), Modifier.weight(1f)) { openTextEditor("new") }
                HomeTile(stringResource(R.string.home_open_file), R.drawable.ic_folder, lookTint(Color(0xFFD1B3FF), Color(0xFF5E35B1)), Modifier.weight(1f)) { openTextEditor("open") }
            }
        }
        // The Calendar: the next events (8 Nov), «+» in the header; none, then the four kinds (18 Oct: "New : Shedule : burthday : reminder")
        "calendar" -> HomeCalendarGroup(openCalendar, { newEvent(NewKind.Event) }) {
            HomeTiles(listOf(NewKind.Event, NewKind.Schedule, NewKind.Birthday, NewKind.Reminder)) { k, m -> HomeTile(stringResource(k.text), k.icon, lookTint(Color(0xFFF77FB3), Color(0xFFC2185B)), m) { newEvent(k) } }
        }
        // The Timer (18 Oct): teal; four that start at a tap, Tetra staying on Home (the user: "add 6m 15m 20m 30m options to
        // imediatly start"), the user's pinned timers first (8 Nov: "one or more of the favourite timers to start immediately")
        "timer" -> if (LocalHomeMedium.current) HomeClockGroup(openTimer) else {
            val words = rememberUiWords()
            var started by remember { androidx.compose.runtime.mutableIntStateOf(0) }
            val pinned by produceState(emptyList<Pair<String, String>>(), HomeCards.tick.intValue, started) {
                value = withContext(Dispatchers.IO) { runCatching { TimerFiles.cards(TimerControl.dir()).filter { it.pinned }.map { it.id to it.label.ifEmpty { durationText(words, it.seconds) } } }.getOrDefault(emptyList()) }
            }
            HomeWidget(Category.Timer.color, R.drawable.timer_art, stringResource(R.string.app_timer), openTimer, panel = false) {
                HomeTiles(HomeCards.timerSlots(pinned) { context.getString(R.string.home_minutes, it) }) { s, m ->
                        HomeTile(s.label, if (s.id != null) R.drawable.ic_push_pin else R.drawable.ic_play, lookTint(Color(0xFF80CBC4), Color(0xFF00796B)), m) {
                            val m = s.minutes ?: 0
                            Thread {
                                if (s.id != null) { runCatching { TimerControl.change(context, s.id, TimerService.RESTART) }.onFailure { WriterLog.e("Timer: Home's pinned ${s.id} failed", it) }; started++; return@Thread }
                                val failed = runCatching { TimerControl.start(context, m * 60L) }.onFailure { WriterLog.e("Timer: Home's $m min failed", it) }.exceptionOrNull()
                                android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(context, failed?.let { it.message ?: context.getString(R.string.home_timer_failed) } ?: context.getString(R.string.home_timer_started, m), Toast.LENGTH_SHORT).show() }
                            }.start()
                        }
                }
            }
        }
        "sync" -> HomeWideCard(stringResource(R.string.home_local_sync), syncSummary, R.drawable.sync_art, openSync)
        "settings" -> HomeWideCard(stringResource(R.string.common_settings), stringResource(R.string.home_settings_detail), R.drawable.settings_art, openSettings)
        // Contacts and Maps (23 Oct, the user: Maps one card with 4 slots, Contacts beside it)
        "contacts" -> HomeContactsGroup(openContacts)
        "phone" -> HomePhoneGroup()
        "data" -> HomeDataGroup()
        "assistant" -> if (AiGate.on) HomeAssistantCard { openAssistant(context) }
        "mail" -> HomeMailGroup(openMail)
        "sms" -> HomeWideCard(stringResource(R.string.app_sms), stringResource(R.string.sms_home_detail), R.drawable.sms_art, openSms)
        "browser" -> HomeBrowserGroup(openBrowser)
        "pills" -> HomePillsGroup { openApplet(context, Applet.Pills.name) }
        "passwords" -> HomePasswordsGroup { openApplet(context, Applet.Passwords.name) }
        "maps" -> HomeMapsGroup(wide, openMaps)
    } }
    // each part small: just its icon, as a launcher's (Scanner and Codes are two apps, so two icons)
    fun icons(id: String): List<HomeIcon> = when (id) {
        "sync" -> listOf(HomeIcon(R.string.home_local_sync, R.drawable.sync_art, openSync))
        "settings" -> listOf(HomeIcon(R.string.common_settings, R.drawable.settings_art, openSettings))
        "gallery" -> listOf(HomeIcon(R.string.app_photos, R.drawable.gallery_art, openPhotos))
        "files" -> listOf(HomeIcon(R.string.app_files, R.drawable.files_art, openFiles))
        "notes" -> listOf(HomeIcon(R.string.app_notes, R.drawable.notes_art, openNotes))
        "scanner" -> listOf(HomeIcon(R.string.app_scanner, R.drawable.scanner_art, openScanner), HomeIcon(R.string.app_codes, R.drawable.codes_art, openCodes))
        "office" -> listOf(HomeIcon(R.string.app_office, R.drawable.office_art, openOffice))
        "text" -> listOf(HomeIcon(R.string.app_text_editor, R.drawable.text_editor_art, { openTextEditor(null) }))
        "calendar" -> listOf(HomeIcon(R.string.app_calendar, R.drawable.calendar_art, openCalendar))
        "timer" -> listOf(HomeIcon(R.string.app_timer, R.drawable.timer_art, openTimer))
        "contacts" -> listOf(HomeIcon(R.string.app_contacts, R.drawable.contacts_art, { openContacts(null) }))
        "phone" -> listOf(HomeIcon(R.string.app_phone, R.drawable.phone_art, { openPhone(context) }))
        "data" -> listOf(HomeIcon(R.string.app_data, R.drawable.data_art, { openData(context) }))
        "assistant" -> if (AiGate.on) listOf(HomeIcon(R.string.app_assistant, R.drawable.assistant_art, { openAssistant(context) })) else emptyList() // AI off: not on Home
        "mail" -> listOf(HomeIcon(R.string.app_mail, R.drawable.mail_art, openMail))
        "sms" -> listOf(HomeIcon(R.string.app_sms, R.drawable.sms_art, openSms))
        "browser" -> listOf(HomeIcon(R.string.app_browser, R.drawable.browser_art, openBrowser))
        "pills" -> listOf(HomeIcon(R.string.app_pills, R.drawable.pills_art, { openApplet(context, Applet.Pills.name) }))
        "passwords" -> listOf(HomeIcon(R.string.app_passwords, R.drawable.passwords_art, { openApplet(context, Applet.Passwords.name) }))
        "maps" -> listOf(HomeIcon(R.string.app_maps, R.drawable.maps_art, { openMaps(null) }))
        else -> emptyList()
    }
    // Home's own layout on this device (the user, 24 Oct: "a grid that expands, to drag and drop where I want them"): hold
    // any part to move it, as on a launcher; the pencil shows each part's resize and hide buttons
    val cols = HomeLayout.columns(wide)
    var layout by remember(wide) { mutableStateOf(HomeLayout.load(context, wide)) }
    var editing by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    fun change(items: List<HomeItem>) { layout = items; HomeLayout.save(context, wide, items) }
    // what × goes back to: the layout as it was when editing began (pencil or a long press)
    var before by remember(wide) { mutableStateOf(layout) }
    LaunchedEffect(editing) { if (editing) before = layout }
    BackHandler(editing) { editing = false }
    var showNotices by remember { mutableStateOf(false) }
    val noticeCounts = Notices.counts(rememberNotices(!showNotices))
    val noticeTotal = noticeCounts.values.sum()
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(scroll).padding(start = 10.dp, top = 16.dp, end = 10.dp, bottom = 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // the launcher icon before the name (asked 17 Oct); an adaptive icon, so drawn by Android, not painterResource
            val icon = remember(context) { context.getDrawable(R.mipmap.ic_launcher)?.toBitmap(144, 144)?.asImageBitmap() }
            icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(36.dp)) }
            Text(
                if (editing) stringResource(R.string.home_edit) else stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge.copy(
                    shadow = Shadow(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.24f), Offset(2f, 3f), 2f),
                ),
                modifier = Modifier.weight(1f),
                // while editing, "Edit Home" shrinks to fit one line in any language (the user, 30 Oct)
                maxLines = if (editing) 1 else Int.MAX_VALUE,
                autoSize = if (editing) androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = MaterialTheme.typography.headlineMedium.fontSize) else null,
            )
            if (editing) {
                // ✓ and × (the user, 30 Oct: the two text buttons left the title no room)
                IconButton(onClick = { change(before); editing = false }) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_cancel)) }
                FilledIconButton(onClick = { editing = false }) { Icon(painterResource(R.drawable.ic_check), contentDescription = stringResource(R.string.common_done)) }
            } else {
                // the bell: Tetra's notifications by applet (4 Nov), its number what is there to read
                BadgedBox(badge = { if (noticeTotal > 0) Badge { Text("$noticeTotal") } }) {
                    IconButton(onClick = { showNotices = true }) { Icon(painterResource(R.drawable.ic_notifications), contentDescription = stringResource(R.string.settings_notifications)) }
                }
                IconButton(onClick = { editing = true }) { Icon(painterResource(R.drawable.ic_edit), contentDescription = stringResource(R.string.home_edit)) }
            }
        }
        if (showNotices) NoticeCenter { showNotices = false }
        if (editing) {
            Text(stringResource(R.string.home_edit_hint),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 6.dp, end = 6.dp))
            TextButton(onClick = { HomeLayout.reset(context, wide); layout = HomeLayout.load(context, wide) }) { Text(stringResource(R.string.home_reset)) }
        }
        HomeGrid(layout, cols, editing, { editing = true }, ::change, scroll, large, ::icons, noticeCounts)
        if (editing) {
            val hidden = layout.filter { it.hidden }
            Text(if (hidden.isEmpty()) stringResource(R.string.home_hidden_none) else stringResource(R.string.home_hidden_tap),
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 4.dp))
            androidx.compose.foundation.layout.FlowRow {
                hidden.forEach { item -> icons(item.id).first().let { HomeSmallIcon(HomeIcon(HomeLayout.NAMES[item.id] ?: it.label, it.art) { change(HomeLayout.change(layout, item.copy(hidden = false, col = -1, row = -1), cols)) }, Modifier.width(96.dp).height(88.dp).graphicsLayer { alpha = 0.55f }) } }
            }
        }
    }
}

/**
 * Home's grid (24 Oct): each shown part at its cell, sized by HomeLayout; a part held is dragged over the grid, a frame
 * showing where it will land, the page scrolling when it nears the top or the bottom. While editing, the parts' own
 * buttons rest (a tap opens nothing) and the empty cells show, two spare rows under the last part to drop into.
 */
/** Medium parts that stay a big icon (none now: a medium part is its widget, smaller). */
private val ICON_MEDIUM = emptySet<String>() // 8 Nov: "the big icons, make them all widgets"

@Composable private fun HomeGrid(
    layout: List<HomeItem>, cols: Int, editing: Boolean, edit: () -> Unit, set: (List<HomeItem>) -> Unit, scroll: androidx.compose.foundation.ScrollState,
    large: @Composable (String) -> Unit, icons: (String) -> List<HomeIcon>, counts: Map<String, Int> = emptyMap(),
) = BoxWithConstraints(Modifier.fillMaxWidth()) {
    HomeTicker() // the live cards' one clock, only while Home is on screen
    val density = LocalDensity.current
    val cellW = maxWidth / cols; val cellH = 47.dp // half the old 94 dp (8 Nov: the grid twice as fine); an icon is 2 by 2, as before
    val cw = with(density) { cellW.toPx() }; val ch = with(density) { cellH.toPx() }
    val screenH = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val edge = with(density) { 96.dp.toPx() }
    val current by rememberUpdatedState(layout)
    val save by rememberUpdatedState(set)
    val startEdit by rememberUpdatedState(edit)
    var dragging by remember { mutableStateOf<String?>(null) }
    var drag by remember { mutableStateOf(Offset.Zero) }
    var finger by remember { mutableStateOf(0f) } // the finger's height in the window, for scrolling at the edges
    var gridTop by remember { mutableStateOf(0f) }
    fun target(item: HomeItem) = HomeLayout.size(item, cols).let { (w, _) ->
        ((item.col * cw + drag.x) / cw).roundToInt().coerceIn(0, cols - w) to ((item.row * ch + drag.y) / ch).roundToInt().coerceAtLeast(0)
    }
    LaunchedEffect(dragging) {
        while (dragging != null) {
            val v = when { finger < edge -> -(edge - finger) / 4; finger > screenH - edge -> (finger - (screenH - edge)) / 4; else -> 0f }
            if (v != 0f) drag += Offset(0f, scroll.scrollBy(v))
            delay(16)
        }
    }
    val rows = HomeLayout.height(layout, cols) + if (editing) 4 else 0
    Box(Modifier.fillMaxWidth().height(cellH * rows).onGloballyPositioned { gridTop = it.positionInRoot().y }) {
        if (editing) {
            val cellColour = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)
            val frame = MaterialTheme.colorScheme.primary
            val held = layout.firstOrNull { it.id == dragging }
            Canvas(Modifier.matchParentSize()) {
                val gap = 6.dp.toPx(); val r = CornerRadius(16.dp.toPx())
                // only the empty cells: a small or medium part has no card of its own to hide them
                val covered = layout.filter { !it.hidden }.flatMap { p -> HomeLayout.size(p, cols).let { (w, h) -> (0 until w).flatMap { dx -> (0 until h).map { dy -> p.col + dx to p.row + dy } } } }.toSet()
                // the free room in blocks of 2 by 2 (an icon's), not every fine cell
                for (row in 0 until rows step 2) for (col in 0 until cols step 2) if ((0..1).all { dx -> (0..1).all { dy -> (col + dx to row + dy) !in covered } })
                    drawRoundRect(cellColour, Offset(col * cw + gap, row * ch + gap), Size(2 * cw - 2 * gap, 2 * ch - 2 * gap), r)
                held?.let { h ->
                    val (c, rw) = target(h); val (w, hh) = HomeLayout.size(h, cols)
                    drawRoundRect(frame.copy(alpha = 0.18f), Offset(c * cw + gap, rw * ch + gap), Size(w * cw - 2 * gap, hh * ch - 2 * gap), r)
                }
            }
        }
        layout.filter { !it.hidden }.forEach { item -> key(item.id) {
            val (w, h) = HomeLayout.size(item, cols)
            val moving = dragging == item.id
            Box(
                Modifier.offset(cellW * item.col, cellH * item.row).size(cellW * w, cellH * h).zIndex(if (moving) 1f else 0f)
                    .graphicsLayer { if (moving) { translationX = drag.x; translationY = drag.y; scaleX = 1.03f; scaleY = 1.03f } }
                    .holdToDrag(item.id,
                        start = { at -> startEdit(); dragging = item.id; drag = Offset.Zero; finger = gridTop + (current.first { it.id == item.id }.row) * ch + at.y },
                        move = { d -> drag += d; finger += d.y },
                        end = { current.firstOrNull { it.id == item.id }?.let { now -> val (c, r) = target(now); save(HomeLayout.drop(current, now.id, c, r, cols)) }; dragging = null; drag = Offset.Zero })
                    .padding(6.dp),
            ) {
                if (item.size == HomeSize.Large || (item.size == HomeSize.Medium && item.id !in ICON_MEDIUM)) androidx.compose.runtime.CompositionLocalProvider(LocalHomeMedium provides (item.size == HomeSize.Medium)) { large(item.id) }
                else Row(Modifier.fillMaxSize()) { icons(item.id).forEach { HomeSmallIcon(it, Modifier.weight(1f).fillMaxHeight(), if (item.small) 52.dp else 112.dp) } }
                // its own count, not the whole app's (the user, 4 Nov)
                counts[item.id]?.let { n -> if (!editing && item.small) Badge(Modifier.align(Alignment.TopEnd)) { Text("$n") } } // a widget shows its own in its header
                if (editing) Box(Modifier.matchParentSize().pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } }) {
                    // on top of the part, so its own buttons get no tap; the frame says it can be moved
                    Box(Modifier.matchParentSize().border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), MaterialTheme.shapes.large))
                    // a small icon: the two in its top corners, smaller, so the icon still shows
                    Row(if (item.small) Modifier.fillMaxWidth().padding(1.dp) else Modifier.align(Alignment.TopEnd).padding(2.dp),
                        horizontalArrangement = if (item.small) Arrangement.SpaceBetween else Arrangement.spacedBy(4.dp)) {
                        // one button through the three sizes: large, medium, small, large again
                        val next = when (item.size) { HomeSize.Large -> HomeSize.Medium; HomeSize.Medium -> HomeSize.Small; HomeSize.Small -> HomeSize.Large }
                        HomeEditButton(if (item.small) R.drawable.ic_fullscreen else R.drawable.ic_fullscreen_exit, stringResource(when (next) { HomeSize.Large -> R.string.home_make_large; HomeSize.Medium -> R.string.home_make_medium; HomeSize.Small -> R.string.home_make_small }), if (item.small) 24.dp else 30.dp) {
                            save(HomeLayout.change(current, item.copy(size = next), cols))
                        }
                        if (item.id != HomeLayout.SETTINGS) HomeEditButton(R.drawable.ic_visibility_off, stringResource(R.string.home_hide), if (item.small) 24.dp else 30.dp) { save(HomeLayout.change(current, item.copy(hidden = true), cols)) }
                    }
                }
            }
        } }
    }
}

/**
 * Held still for a long press, then dragged: Home's parts, as a launcher's icons. Watched before the part's own buttons
 * see the touch, and taken once held, so a held part never also opens; moved before the long press, it is the page's scroll.
 */
private fun Modifier.holdToDrag(key: Any, start: (Offset) -> Unit, move: (Offset) -> Unit, end: () -> Unit) = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
        val letGo = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val c = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                if (c == null || !c.pressed || c.isConsumed || (c.position - down.position).getDistance() > viewConfiguration.touchSlop) break
            }
            true
        }
        if (letGo == true) return@awaitEachGesture
        start(down.position)
        while (true) {
            val c = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            if (!c.pressed) { c.consume(); break }
            val d = c.position - c.previousPosition
            c.consume()
            if (d != Offset.Zero) move(d)
        }
        end()
    }
}

@Composable private fun HomeEditButton(icon: Int, label: String, size: androidx.compose.ui.unit.Dp, click: () -> Unit) = Surface(
    shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
    shadowElevation = 2.dp, modifier = Modifier.size(size).clickable(onClick = click),
) { Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(size * 0.6f)) } }

private class HomeIcon(@androidx.annotation.StringRes val label: Int, val art: Int, val open: () -> Unit)

/** A part shown small or medium on Home: its icon and name, as a launcher's; medium's icon big, in four cells. */
@Composable private fun HomeSmallIcon(icon: HomeIcon, modifier: Modifier, art: androidx.compose.ui.unit.Dp = 52.dp) = Column(
    modifier.clip(MaterialTheme.shapes.large).clickable(onClick = icon.open).padding(vertical = 4.dp),
    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
) {
    HomeIconArt(icon, art)
    Text(stringResource(icon.label), style = if (art > 52.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
}

// the user's art is itself a tile, Sync's and Settings' too (24 Oct)
@Composable private fun HomeIconArt(icon: HomeIcon, size: androidx.compose.ui.unit.Dp) = ArtImage(icon.art, size)

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
        Thread { (Applet.entries.map { it.art } + R.drawable.sync_art + R.drawable.settings_art).forEach { get(res, it) } }.start()
    }
}

/** An applet's art ([HomeArt]); the space it takes until it is decoded. */
@Composable internal fun ArtImage(art: Int, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val res = LocalContext.current.resources
    val bitmap by produceState(HomeArt.cached(art), art) { if (value == null) value = withContext(Dispatchers.Default) { HomeArt.get(res, art) } }
    bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier.size(size)) } ?: Spacer(modifier.size(size))
}

/**
 * Maps (21 Oct): saved places beside the map, each one tap to directions there (the user, 21 Oct: "favorite locations
 * navigation with one click"), an empty slot opening the map to save one. A tablet: one group like the others, four places
 * two by two, beside Contacts (the user, 23 Oct: "4 slots"; the twelve stay in the app); a phone: the first eight, half-size.
 * Sky blue, the new icon's colour.
 */
@Composable private fun HomeMapsGroup(wide: Boolean, openMaps: (Place?) -> Unit) {
    val slots by produceState(List<Place?>(MapsFiles.SLOTS) { null }) {
        value = withContext(Dispatchers.IO) { MapsFiles.slots(MapsFiles.places(MapsFiles.root(driveRoot()))) }
    }
    HomeWidget(Category.Maps.color, R.drawable.maps_art, stringResource(R.string.app_maps), { openMaps(null) }, panel = false) {
        val tint = lookTint(Color(0xFF81D4FA), Color(0xFF0277BD))
        // eight places, four a row (8 Nov: "the large one takes 8, not 6, as on the phone, a little narrower")
        HomeTiles(slots.take(MapsFiles.PHONE_SLOTS), minWidth = 70.dp, height = 34.dp, gap = 6.dp, maxCols = 4) { p, m ->
            if (p == null) HomeMiniTile(stringResource(R.string.common_add), R.drawable.ic_add, tint.copy(alpha = 0.6f), m) { openMaps(null) }
            else HomeMiniTile(p.short, placeIcon(p.icon), tint, m) { openMaps(p) }
        }
    }
}

private var homeSyncSummary = ""

/** Four favourites for Home, from Contacts' read-ahead (ContactsPreload): no second read of every contact's file. */
private fun homeFavourites(all: List<Contact>?, phones: Boolean) =
    all.orEmpty().map { it.info }.filter { it.favourite && (!phones || it.phones.isNotEmpty()) }.sortedBy { ContactsFiles.sortKey(it, false) }.take(8)
// read as the read-ahead reads (a file again only when it changed), not through it: its load published the whole list
// again to an open Contacts, which then kept taps waiting ~1.3 s on the phone (25 Oct)
private suspend fun homeFavourites(phones: Boolean, or: List<ContactInfo>) = withContext(Dispatchers.IO) {
    runCatching { homeFavourites(ContactsFiles.all(contactsRoot()), phones) }.getOrDefault(or)
}

/**
 * Contacts (23 Oct): the icon's brown; four favourites' faces beside it, one tap opens that person (the plan's question 7,
 * its recommended answer), an empty one "Add" opening Contacts.
 */
@Composable private fun HomeContactsGroup(open: (String?) -> Unit) {
    val favs by produceState(remember { homeFavourites(ContactsPreload.all, false) }) { value = homeFavourites(false, value) }
    HomeWidget(Category.Contacts.color, R.drawable.contacts_art, stringResource(R.string.app_contacts), { open(null) }, panel = false) {
        HomeTiles(favs + null) { f, m ->
            if (f != null) HomeFaceTile(f, m) { open(f.uid) }
            else HomeTile(stringResource(R.string.common_add), R.drawable.ic_add, lookTint(Color(0xFFD7A982), Color(0xFF8D5A3B)), m) { open(null) }
        }
    }
}

/** The Phone (Plan.md § Phone): its own window, as Android's phone app is (Recents · Favourites · Contacts · Recordings). */
internal fun openPhone(c: Context, tab: String? = null) = c.startActivity(Intent(c, PhoneActivity::class.java).putExtra(PhoneActivity.TAB, tab).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

/** The Phone on Home: its art, and four favourites to call with one tap (the plan's question 7, its recommended answer). */
@Composable private fun HomePhoneGroup() {
    val context = LocalContext.current
    val favs by produceState(remember { homeFavourites(ContactsPreload.all, true) }) { value = homeFavourites(true, value) }
    val scope = rememberCoroutineScope()
    fun call(f: ContactInfo) {
        val n = f.defaultPhone ?: f.phones.first().value
        if (PhoneRelay.noCalls(context)) scope.launch { val said = withContext(Dispatchers.IO) { PhoneRelay.place(context, n, f.name) }; Toast.makeText(context, said, Toast.LENGTH_SHORT).show() }
        else if (!placePhoneCall(context, n)) openPhone(context)
    }
    // the favourites first, then Keypad, Recents, Contacts, as many as fit
    val spare = listOf(Triple(R.string.home_keypad, R.drawable.ic_dialpad, "keypad"), Triple(R.string.home_recents, R.drawable.ic_history, "recents"), Triple(R.string.app_contacts, R.drawable.ic_contacts, "contacts"))
    HomeWidget(Category.Phone.color, R.drawable.phone_art, stringResource(R.string.app_phone), { openPhone(context) }, panel = false) {
        HomeMissedCalls() // 8 Nov: "the phone can show actual missed calls"
        HomeTiles(favs.map { it to null } + spare.map { null to it }) { (f, s), m ->
            if (f != null) HomeFaceTile(f, m) { call(f) }
            else HomeTile(stringResource(s!!.first), s.second, MaterialTheme.colorScheme.onSurface, m) { openPhone(context, s.third) }
        }
    }
}

/**
 * Data on Home (8 Nov, the user: "if it is just info the only thing it does is open… the percentage ring, the day's
 * consumption or the forecast, the cycle graph"): one card that shows the plan and opens Data; no shortcut tiles.
 */
@Composable private fun HomeDataGroup() {
    val context = LocalContext.current
    val tick = HomeCards.tick.intValue / 5
    // a phone's limited plan: its status; otherwise one line (a tablet's hotspot use, an unlimited plan's use, or Set up)
    val st by produceState<Pair<DataStatus?, String>>(null to "", tick) {
        value = withContext(Dispatchers.IO) { runCatching {
            val plan = DataFiles.plan()
            val phone = DataFiles.all().values.maxByOrNull { m -> DataFiles.days(m).sumOf { it.mobile } }.orEmpty()
            // a tablet: the phone's plan as well (8 Nov: "Data just writes it, it has no pie"), its own hotspot use the line under it
            val hotspot = if (!PhoneRelay.noCalls(context)) "" else DataFiles.days(DataFiles.all()[DataFiles.device()].orEmpty()).filter { it.day.withDayOfMonth(1) == java.time.LocalDate.now().withDayOfMonth(1) }
                .sumOf { it.viaHotspot }.let { context.getString(R.string.home_data_hotspot, DataRules.bytes(it)) }
            when {
                plan == null -> null to hotspot.ifEmpty { context.getString(R.string.home_data_set_up) }
                else -> DataRules.status(plan, DataFiles.days(phone), java.time.LocalDate.now()).let { s -> if (plan.limited) s to hotspot else null to hotspot.ifEmpty { DataRules.bytes(s.used) } }
            }
        }.getOrDefault(null to "") }
    }
    val (s, line) = st
    val ink = lookTint(Color(0xFFC5F27A), Color(0xFF4E8A0A))
    val pct = s?.let { (it.used * 100 / it.size.coerceAtLeast(1)).toInt() }
    HomeWidget(Color(0xFF8ED11F), R.drawable.data_art, stringResource(R.string.app_data), { openData(context) }, badge = pct?.let { "$it %" }) {
        Column(Modifier.fillMaxSize().clickable { openData(context) }) {
            if (s == null) { Text(line.ifEmpty { stringResource(R.string.home_data_usage) }, style = MaterialTheme.typography.titleMedium); return@Column }
            val day = DateTimeFormatter.ofPattern("d MMM", LocalConfiguration.current.locales[0])
            val runsOut = s.projection > s.size
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    HomeRing(s.used.toFloat() / s.size.coerceAtLeast(1), ink, Modifier.matchParentSize())
                    Text("$pct", style = MaterialTheme.typography.labelLarge)
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.data_today_budget, DataRules.bytes(s.today), DataRules.bytes(s.budget)), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.data_forecast, DataRules.bytes(s.projection), s.cycle.end.minusDays(1).format(day)) + if (runsOut) " ⚠" else " ✓",
                        style = MaterialTheme.typography.bodySmall, color = if (runsOut) MaterialTheme.colorScheme.error else Color.Unspecified, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.data_days_left, s.daysLeft, s.cycle.end.format(day)), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // the cycle so far, a bar a day, against the day's budget (the line)
            if (!LocalHomeMedium.current) HomeBars(s.perDay, s.budget, ink, Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp)) // the large one: the cycle
        }
    }
}

@Composable internal fun HomeGroup(color: Color, content: @Composable () -> Unit) = Surface(
    shape = MaterialTheme.shapes.extraLarge, color = color,
    modifier = Modifier.fillMaxWidth(),
) { Box(Modifier.padding(10.dp)) { content() } }

@Composable
private fun HomePhotosCard(click: () -> Unit, showBackdrop: Boolean, hasPhotoAccess: Boolean, photoMetadata: PhotoMetadataStore, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val photo by produceState<Bitmap?>(initialValue = null, showBackdrop, hasPhotoAccess) {
        value = if (showBackdrop && hasPhotoAccess) withContext(Dispatchers.IO) {
            randomGalleryThumbnail(context, runCatching { photoMetadata.classifiedKeys("document") }.getOrDefault(emptySet()))
        } else null
    }
    val hasBackdrop = photo != null
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        modifier = modifier.height(156.dp).clickable(onClick = click),
    ) {
        Box(Modifier.fillMaxSize()) {
            photo?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            if (hasBackdrop) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)))
            // Laid out like HomeArtCard: the Gallery tile top-right (only without the photo backdrop), the title at the bottom.
            Box(Modifier.fillMaxSize().padding(18.dp)) {
                if (!hasBackdrop) ArtImage(R.drawable.gallery_art, 76.dp, Modifier.align(Alignment.TopEnd))
                Text(stringResource(R.string.app_photos), color = if (hasBackdrop) Color.White else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.BottomStart))
            }
        }
    }
}

@Composable private fun HomeCompactCard(label: String, icon: Int, click: () -> Unit) = Surface(
    shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
    modifier = Modifier.fillMaxWidth().height(73.dp).clickable(onClick = click),
) { Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(24.dp))
    Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
} }

/** One of Home's Office Open tiles: the app's name over its icon in its colour; "Open …" where there is room. */
@Composable private fun HomeOpenTile(kind: OfficePick, wide: Boolean, modifier: Modifier, click: () -> Unit) {
    val (name, icon, tint) = when (kind) {
        OfficePick.Words -> Triple(stringResource(if (wide) R.string.home_open_words else R.string.app_words), R.drawable.kind_docs_word, Color.Unspecified)
        OfficePick.Tables -> Triple(stringResource(if (wide) R.string.home_open_tables else R.string.app_tables), R.drawable.kind_excel, Color.Unspecified)
        OfficePick.Slides -> Triple(stringResource(if (wide) R.string.home_open_slides else R.string.app_slides), R.drawable.kind_slides, Color.Unspecified)
        OfficePick.Pdf -> Triple(stringResource(if (wide) R.string.home_open_pdfs else R.string.home_pdfs), R.drawable.kind_pdf, Color.Unspecified)
    } // the user's tiles in their own colours (24 Oct)
    HomeTile(name, icon, tint, modifier, click)
}

/** One of Home's small tiles (Office's Open, Calendar's New…): a name under its icon in its colour. */
@Composable private fun HomeTile(label: String, icon: Int, tint: Color, modifier: Modifier, click: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f), modifier = modifier.height(HOME_TILE).clickable(onClick = click)) {
        Column(Modifier.fillMaxSize().padding(2.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            // a tile in its own colours (tint Unspecified) a little bigger than a plain icon, so its picture reads
            if (tint == Color.Unspecified) Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
            else Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** A favourite on Home: their face (or initial) over their first name, one tap to call or open them. */
@Composable private fun HomeFaceTile(f: ContactInfo, modifier: Modifier, click: () -> Unit) =
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f), modifier = modifier.clickable(onClick = click)) {
        Column(Modifier.fillMaxSize().padding(2.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            ContactAvatar(f, 26.dp)
            Text(f.given.ifBlank { f.name }, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

/** A HomeTile with a ring in place of its icon: the plan's share used, its percent inside. */
@Composable private fun HomeRingTile(label: String, share: Float, tint: Color, modifier: Modifier, click: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f), modifier = modifier.height(HOME_TILE).clickable(onClick = click)) {
        Column(Modifier.fillMaxSize().padding(2.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                HomeRing(share, tint, Modifier.matchParentSize())
                Text("${(share * 100).roundToInt()}", style = MaterialTheme.typography.labelSmall)
            }
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
        }
    }
}

/** Half a HomeTile, its icon beside its name: Maps' places on a phone, eight beside the art. */
@Composable private fun HomeMiniTile(label: String, icon: Int, tint: Color, modifier: Modifier, click: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f), modifier = modifier.height(34.dp).clickable(onClick = click)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A part's own card on Home: its art at the top, its name at the bottom (Notes, Text editor). */
@Composable internal fun HomeArtCard(title: String, art: Int, click: () -> Unit, modifier: Modifier = Modifier, artSize: Int = 100) = Surface(
    shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
    modifier = modifier.height(156.dp).clickable(onClick = click),
) { Box(Modifier.fillMaxSize().padding(18.dp)) {
    ArtImage(art, artSize.dp, Modifier.align(Alignment.TopEnd))
    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart))
} }

@Composable private fun HomePdfToolsCard(openScanner: () -> Unit, openCodes: () -> Unit) = HomeGroup(Category.Scanner.color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // the user's own tiles for both (17 Oct), as Office's and the Text editor's art
        PdfToolCard(stringResource(R.string.app_scanner), R.drawable.scanner_art, Modifier.weight(1f), openScanner)
        PdfToolCard(stringResource(R.string.app_codes), R.drawable.codes_art, Modifier.weight(1f), openCodes)
    }
}

@Composable
private fun PdfToolCard(label: String, art: Int, modifier: Modifier = Modifier, click: (() -> Unit)? = null) = Surface(
    shape = MaterialTheme.shapes.extraLarge,
    color = if (TetraLook.light) MaterialTheme.colorScheme.surface.copy(alpha = 0.72f) else Color(0xFF651B1B), // as the other cards in light
    modifier = modifier.height(156.dp).then(if (click == null) Modifier else Modifier.clickable(onClick = click)),
) {
    Box(Modifier.fillMaxSize().padding(18.dp)) {
        ArtImage(art, 76.dp, Modifier.align(Alignment.TopEnd))
        Text(label, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart))
    }
}

/** Local Sync's and Settings' card: the user's tile (24 Oct), the name and a line under it. */
@Composable private fun HomeWideCard(label: String, detail: String, art: Int, click: () -> Unit, modifier: Modifier = Modifier) = Surface(
    shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceVariant,
    modifier = modifier.fillMaxWidth().heightIn(min = 78.dp).clickable(onClick = click),
) { Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
    ArtImage(art, 44.dp)
    Column {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodySmall)
    }
} }

@Composable
private fun SyncTab(back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { SyncStore(context.applicationContext) }
    val client = remember(context) { SyncClient(context.applicationContext, store) }
    var pairing by remember { mutableStateOf(store.pairing()) }
    var scanning by remember { mutableStateOf(false) }
    var showing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var pairMessage by remember { mutableStateOf<String?>(null) }
    val requestNotifications = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var received by remember { mutableStateOf(0) }
    var overview by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var lastBackup by remember { mutableStateOf(0L) }
    var connections by remember { mutableStateOf(SyncConnection.defaults) }
    var waitingToMove by remember { mutableStateOf(emptySet<String>()) }
    var moveError by remember { mutableStateOf<String?>(null) }
    // Android's own request, with Android's own confirmation and its own 30-day Trash. A sync never takes a
    // photo off this phone; a verified receipt only earns the right to ask.
    // Android takes at most 2,000 items per request (4,217 were asked for at once on 25 September), so they go in
    // batches: each is forgotten only once Android said yes, and the next one starts by itself.
    var batch by remember { mutableStateOf<Set<String>>(emptySet()) }
    lateinit var removeNext: () -> Unit
    val removeRequest = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val done = batch
        batch = emptySet()
        if (result.resultCode == Activity.RESULT_OK) {
            waitingToMove = waitingToMove - done
            scope.launch { withContext(Dispatchers.IO) { store.forgetQueued(done) }; if (waitingToMove.isNotEmpty()) removeNext() }
        }
    }
    removeNext = {
        scope.launch {
            val found = withContext(Dispatchers.IO) { photoUrisByKey(context, waitingToMove) }
            // Queued photos that are no longer here at all (removed some other way) are simply forgotten.
            val gone = waitingToMove - found.keys
            if (gone.isNotEmpty()) { withContext(Dispatchers.IO) { store.forgetQueued(gone) }; waitingToMove = waitingToMove - gone }
            val next = found.entries.take(MAX_MEDIA_REQUEST)
            if (next.isNotEmpty()) {
                batch = next.mapTo(HashSet()) { it.key }
                // Deleted, not trashed: the computer holds each one, checked in this sync, and Android's Trash would
                // keep the room for 30 days — the room the Move is for (asked 2026-09-25).
                runCatching { MediaStore.createDeleteRequest(context.contentResolver, next.map { it.value }) }
                    .onSuccess { removeRequest.launch(IntentSenderRequest.Builder(it.intentSender).build()) }
                    .onFailure { batch = emptySet(); moveError = it.message ?: context.getString(R.string.sync_request_refused) }
            }
        }
    }
    // Deleted on the computer, waiting for Android's yes to go to this phone's Trash too (Both ways trash).
    var waitingToTrash by remember { mutableStateOf(emptySet<String>()) }
    var trashBatch by remember { mutableStateOf<Set<String>>(emptySet()) }
    val trashRequest = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val done = trashBatch
        trashBatch = emptySet()
        if (result.resultCode == Activity.RESULT_OK) { waitingToTrash = waitingToTrash - done; scope.launch { withContext(Dispatchers.IO) { store.forgetTrash(done) } } }
    }
    val trashNext: () -> Unit = {
        scope.launch {
            val found = withContext(Dispatchers.IO) { photoUrisByKey(context, waitingToTrash) }
            val gone = waitingToTrash - found.keys
            if (gone.isNotEmpty()) { withContext(Dispatchers.IO) { store.forgetTrash(gone) }; waitingToTrash = waitingToTrash - gone }
            val next = found.entries.take(MAX_MEDIA_REQUEST)
            if (next.isNotEmpty()) {
                trashBatch = next.mapTo(HashSet()) { it.key }
                runCatching { MediaStore.createTrashRequest(context.contentResolver, next.map { it.value }, true) }
                    .onSuccess { trashRequest.launch(IntentSenderRequest.Builder(it.intentSender).build()) }
                    .onFailure { trashBatch = emptySet(); moveError = it.message ?: context.getString(R.string.sync_request_refused) }
            }
        }
    }
    val backup by SyncService.state.collectAsState()
    val running = SyncService.isRunning
    LaunchedEffect(backup) { withContext(Dispatchers.IO) {
        received = store.receiptCount(); lastBackup = store.lastBackup(); connections = store.connections(); overview = store.overview()
        waitingToMove = store.queuedForRemoval()
        waitingToTrash = store.queuedForTrash()
    } }
    // A long backup must not be cut off by the screen turning off; the foreground service itself
    // keeps running once the app is backgrounded or closed.
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(running) { view.keepScreenOn = running; onDispose { view.keepScreenOn = false } }

    if (showing) {
        PairingQrScreen(store, back = { showing = false; pairing = store.pairing() })
        return
    }
    if (scanning) {
        CodeScannerTab(back = { scanning = false }, onCode = { value ->
            val qr = SyncRules.parseQr(value) ?: return@CodeScannerTab false
            if (!scanning || busy != null) return@CodeScannerTab true // the next camera frames see the same code: send it once
            scanning = false
            busy = context.getString(R.string.sync_pairing_with, qr.name)
            scope.launch {
                pairMessage = runCatching { withContext(Dispatchers.IO) { client.pair(qr) } }
                    .fold({ pairing = it; context.getString(R.string.sync_paired_back_up, it.name) }, { it.message ?: context.getString(R.string.sync_pairing_failed) })
                busy = null
            }
            true
        })
        return
    }

    val p = pairing
    val result = backup?.result
    val summary = result?.let { r ->
        if (r.received > 0) stringResource(R.string.sync_result_received, r.checked, r.sent, r.alreadyThere, r.received)
        else stringResource(R.string.sync_result, r.checked, r.sent, r.alreadyThere)
    }
    val message = backup?.error ?: summary ?: pairMessage
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item { FilesPageHeader(stringResource(R.string.app_sync), R.drawable.ic_sync, back) }
        item {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ConnectionCard(paired = p != null, deviceName = p?.name, reachable = backup?.unreachable != true)
                    backup?.progress?.let { pr ->
                        val isPaused = backup?.paused == true
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LinearProgressIndicator(progress = { if (pr.total == 0) 0f else pr.done / pr.total.toFloat() }, trackColor = Color.Black, modifier = Modifier.weight(1f))
                            IconButton(onClick = { pauseSync(context) }) {
                                Icon(painterResource(if (isPaused) R.drawable.ic_sync else R.drawable.ic_pause), contentDescription = stringResource(if (isPaused) R.string.common_resume else R.string.common_pause))
                            }
                        }
                        Text(stringResource(R.string.sync_stage_progress, if (isPaused) stringResource(R.string.sync_paused) else pr.stage, pr.done, pr.total), style = MaterialTheme.typography.bodyMedium)
                    }
                    // Pairing goes both ways now: this device can scan another, or be scanned by it. The two
                    // read as one choice stacked, and carry different symbols — scanning is a camera pointed at
                    // a code, showing one is this device's own identity card.
                    Button(onClick = { scanning = true }, enabled = busy == null, colors = neutralButtonColors(), modifier = Modifier.fillMaxWidth()) {
                        Icon(painterResource(R.drawable.ic_qr_code), contentDescription = null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.sync_scan_code), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Button(onClick = { showing = true }, enabled = busy == null, colors = neutralButtonColors(), modifier = Modifier.fillMaxWidth()) {
                        Icon(painterResource(R.drawable.ic_id_card), contentDescription = null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.sync_show_device), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    when {
                        p == null -> Unit // the two buttons above are the whole offer until something is paired
                        !running -> Button(onClick = {
                            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            }
                            startSync(context)
                        }, colors = neutralButtonColors(), modifier = Modifier.fillMaxWidth()) {
                            Icon(painterResource(R.drawable.ic_sync), contentDescription = null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.sync_back_up_now), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        else -> Button(
                            onClick = { stopSync(context) },
                            shape = RoundedCornerShape(percent = 50),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(painterResource(R.drawable.ic_cancel), contentDescription = null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.common_stop), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        // A Move that has been earned but not yet agreed to. It says what will happen and where they go, because
        // "moved off this phone" is the one sentence in this app that has to be impossible to misread.
        if (p != null && waitingToMove.isNotEmpty()) item {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(pluralStringResource(R.plurals.sync_to_move, waitingToMove.size, waitingToMove.size, p.name), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.sync_move_explained, p.name),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { moveError = null; removeNext() }, enabled = batch.isEmpty(), colors = neutralButtonColors(), modifier = Modifier.fillMaxWidth()) {
                        Icon(painterResource(R.drawable.ic_delete), contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.sync_free_room), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    moveError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (p != null && waitingToTrash.isNotEmpty()) item {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(pluralStringResource(R.plurals.sync_to_trash, waitingToTrash.size, waitingToTrash.size, p.name), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.sync_trash_explained), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { moveError = null; trashNext() }, enabled = trashBatch.isEmpty(), colors = neutralButtonColors(), modifier = Modifier.fillMaxWidth()) {
                        Icon(painterResource(R.drawable.ic_delete), contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.sync_trash_here), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!MediaStore.canManageMedia(context)) TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:${context.packageName}"))) }
                    }) { Text(stringResource(R.string.sync_allow_media), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        // What this sync will do, in the computer's words: it owns the rules, this phone reads and obeys them.
        if (p != null) item {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.sync_what_it_does), style = MaterialTheme.typography.labelLarge)
                    connections.forEach { row -> Text(connectionSentence(row, p.name), style = MaterialTheme.typography.bodyMedium) }
                    Text(stringResource(R.string.sync_set_on_computer), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f))
                }
            }
        }
        overview?.let { o -> if (p != null) item { LibraryOverview(o) } }
        if (p != null) item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SyncStat(stringResource(R.string.sync_photos_in_library), overview?.optInt("known")?.toString() ?: "—", Modifier.weight(1f))
                SyncStat(stringResource(R.string.sync_last_backup), if (lastBackup == 0L) stringResource(R.string.sync_never) else DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(lastBackup)), Modifier.weight(1f))
            }
        }
        message?.let { m -> item {
            Surface(shape = MaterialTheme.shapes.large, color = islandColor(), contentColor = islandContentColor(), modifier = Modifier.fillMaxWidth()) {
                Text(m, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
            }
        } }
        result?.failed?.let { failures -> items(failures) { f -> SyncFailureCard(f) } }
        item {
            Text(
                if (p == null) stringResource(R.string.sync_how_to_pair)
                else stringResource(R.string.sync_what_a_sync_does),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/**
 * The gallery uris behind a set of photo keys. A key is this app's own name for a photo — it survives the app
 * being reinstalled, where a MediaStore id does not — so the way back to Android's own id is to look for it.
 * A photo already gone (deleted by hand, or on another device) simply has no uri, and is quietly forgotten.
 */
/** Every photo on this device, shown or not, by key: a queued photo in a hidden folder is still on this device. */
private fun photoUrisByKey(context: android.content.Context, keys: Set<String>): Map<String, Uri> =
    listDeviceMedia(context).filter { it.photoKey in keys }.mapNotNull { e -> e.contentUri?.let { e.photoKey to it } }.toMap()

/** A connection row as the Sync page says it: "Photos ⇄ Desk · Copy"; the computer configures, the phone says what it was told. */
@Composable
private fun connectionSentence(row: SyncConnection, computer: String): String {
    val what = stringResource(when (row.content) { "files" -> R.string.app_files; "mail" -> R.string.app_mail; "other" -> R.string.sync_others; else -> R.string.app_photos })
    val arrow = row.arrow ?: return stringResource(R.string.sync_not_synced, what)
    val keep = if (row.keepAuto) stringResource(R.string.sync_keep_auto) // everything, until it is 90 % full
        else if (!row.moves) stringResource(R.string.sync_copy)
        else SyncRules.span(row.keepDays).let { (n, unit) -> stringResource(R.string.sync_move_keeping, pluralStringResource(unit, n, n)) }
    return stringResource(R.string.sync_connection, what, arrow, computer, keep)
}

/** Android's limit on how many items one MediaStore request may name. */
private const val MAX_MEDIA_REQUEST = 2000

private fun startSync(context: android.content.Context) {
    val intent = android.content.Intent(context, SyncService::class.java).setAction(SyncService.ACTION_START)
    androidx.core.content.ContextCompat.startForegroundService(context, intent)
}

private fun pauseSync(context: android.content.Context) {
    SyncService.togglePause()
}

private fun stopSync(context: android.content.Context) {
    context.startService(android.content.Intent(context, SyncService::class.java).setAction(SyncService.ACTION_STOP))
}

/** This phone → paired computer, the one connection the phone ever has (see SYNC_PLAN.md for the multi-device note). */
/**
 * This device's own pairing code (SYNC_PLAN.md 6l). Two phones have no computer between them, so each one has
 * to be able to be *found*, not only to look: while this screen is open a server answers on Wi-Fi, and whoever
 * scans the code pins this device's certificate. Closing the screen stops it — something listening all day is
 * a decision to make out loud, not by leaving a screen behind.
 */
@Composable
private fun PairingQrScreen(store: SyncStore, back: () -> Unit) {
    val context = LocalContext.current
    var qr by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var pairedWith by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        var server: SyncServer? = null
        runCatching {
            server = SyncServer.acquire(context.applicationContext, store)
            server.onPaired = { peer -> pairedWith = peer.name }
            qr = server.pairingQr(server.startPairing())
        }.onFailure { failure = it.message ?: context.getString(R.string.sync_no_code) }
        onDispose {
            server?.let { it.onPaired = {} }
            if (server != null) runCatching { SyncServer.release() }
        }
    }
    val bitmap = remember(qr) { qr?.let { runCatching { qrBitmap(it) }.getOrNull() } }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FilesPageHeader(stringResource(R.string.sync_pair_this_device), R.drawable.ic_id_card, back)
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    failure != null -> Text(failure!!, style = MaterialTheme.typography.bodyMedium)
                    bitmap != null -> Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.sync_pairing_code),
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
                        modifier = Modifier.fillMaxWidth(0.8f).aspectRatio(1f).background(Color.White).padding(12.dp),
                    )
                    else -> CircularProgressIndicator()
                }
                Text(
                    pairedWith?.let { stringResource(R.string.sync_paired_with, it) } ?: stringResource(R.string.sync_point_here),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(stringResource(R.string.sync_code_once), style = MaterialTheme.typography.bodySmall)
            }
        }
        val peers = remember(pairedWith) { runCatching { store.peers() }.getOrDefault(emptyList()) }
        if (peers.isNotEmpty()) Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.sync_paired_devices), style = MaterialTheme.typography.titleMedium)
                peers.forEach { peer ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_phone), contentDescription = null, modifier = Modifier.size(20.dp))
                        Text(peer.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(peer.hosts.firstOrNull().orEmpty(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

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
private fun ConnectionCard(paired: Boolean, deviceName: String?, reachable: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = islandColor(), contentColor = islandContentColor()) {
            Icon(painterResource(R.drawable.ic_phone), contentDescription = stringResource(R.string.sync_this_phone), modifier = Modifier.padding(12.dp).size(22.dp))
        }
        Icon(painterResource(R.drawable.ic_sync), contentDescription = null, modifier = Modifier.size(16.dp).alpha(if (paired) 1f else 0.4f))
        Surface(shape = CircleShape, color = if (paired) driveNavigationSelectedColor() else islandColor(), contentColor = if (paired) driveNavigationSelectedContentColor() else islandContentColor()) {
            Icon(painterResource(R.drawable.ic_computer), contentDescription = deviceName ?: stringResource(R.string.sync_no_computer), modifier = Modifier.padding(12.dp).size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(deviceName ?: stringResource(R.string.sync_no_computer_yet_short), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    !paired -> stringResource(R.string.sync_pair_once)
                    !reachable -> stringResource(R.string.sync_not_reachable)
                    else -> stringResource(R.string.sync_paired)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SyncFailureCard(failure: String) {
    val (name, reason) = failure.split(": ", limit = 2).let { it.getOrElse(0) { failure } to it.getOrNull(1).orEmpty() }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_cancel), contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Column {
                Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (reason.isNotEmpty()) Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

/**
 * The library, and where it is — the computer's own overview (GET /overview, fetched at the end of each sync), so
 * this page says what the Devices page says: how much there is, on which machine, and how many places hold each.
 */
@Composable
private fun LibraryOverview(o: org.json.JSONObject) {
    val known = o.optInt("known")
    val here = o.optJSONObject("here")?.optInt("files") ?: 0
    val stored = o.optJSONArray("stored")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
    val onlyDevice = known - here - stored.sumOf { it.optInt("files") }
    val copies = o.optJSONArray("copies")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty().filter { it.optInt("copies") > 0 }
    val devices = o.optJSONArray("devices")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
    val you = o.optString("you")
    val color = { n: Int -> SyncRules.copiesColor(n).let { (h, s, l) -> Color.hsl(h, s, l) } }
    val words = listOf(0, R.string.sync_copies_1, R.string.sync_copies_2, R.string.sync_copies_3, R.string.sync_copies_4, R.string.sync_copies_5, R.string.sync_copies_6)
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.sync_library), style = MaterialTheme.typography.titleMedium)
            Text((listOf(pluralStringResource(R.plurals.sync_known, known, "%,d".format(known)), stringResource(R.string.sync_on_device, "%,d".format(here), o.optString("name"))) +
                stored.map { stringResource(R.string.sync_on_device, "%,d".format(it.optInt("files")), it.optString("name")) } +
                listOfNotNull(if (onlyDevice > 0) stringResource(R.string.sync_only_on_a_device, "%,d".format(onlyDevice)) else null)).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            if (known > 0) Row(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape)) {
                copies.forEach { c -> Box(Modifier.weight(c.optInt("files").coerceAtLeast(1).toFloat()).fillMaxHeight().background(color(c.optInt("copies")))) }
            }
            copies.forEach { c ->
                val n = c.optInt("copies")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(color(n)))
                    Text(words.getOrNull(n)?.takeIf { it != 0 }?.let { stringResource(it) } ?: stringResource(R.string.sync_copies_n, n), style = MaterialTheme.typography.bodyMedium)
                    Text("%,d".format(c.optInt("files")) + " · " + stringResource(if (n == 1) R.string.sync_one_place else if (n == 2) R.string.sync_one_could_fail else R.string.sync_safe),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(stringResource(R.string.sync_devices_header), style = MaterialTheme.typography.labelMedium)
            devices.forEach { d ->
                val only = d.optInt("onlyThere")
                Text(
                    "${if (d.optString("id") == you) stringResource(R.string.sync_this_device_name, d.optString("name")) else d.optString("name")} · ${"%,d".format(d.optInt("holds"))} · " +
                        "${"%,d".format(d.optInt("alsoHere"))} · ${"%,d".format(only)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (only > 0) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
            Text(stringResource(R.string.sync_as_seen), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SyncStat(label: String, value: String, modifier: Modifier = Modifier) = Surface(
    shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier,
) {
    Column(Modifier.padding(16.dp)) {
        Text(value, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = MaterialTheme.typography.bodySmall)
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
    Data(DATA_COLOR, R.drawable.ic_data_usage),
    Mail(MAIL_COLOR, R.drawable.ic_mail),
    Sms(SMS_COLOR, R.drawable.ic_chat),
    Browser(BROWSER_COLOR, R.drawable.ic_open_in_browser),
}

@Composable
private fun DriveTab(
    home: () -> Unit,
    openInScanner: (File) -> Unit,
    pane: DrivePane,
    setPane: (DrivePane) -> Unit,
    folder: String,
    state: DriveListState,
    hasAccess: Boolean,
    grant: () -> Unit,
    refresh: () -> Unit,
    openFolder: (String) -> Unit,
    goParent: () -> Unit,
    recents: () -> List<DriveRecent>,
    recentsVersion: Int,
    diskVersion: Int,
    openFile: (String, Boolean) -> String?,
) {
    val context = LocalContext.current
    val root = remember { driveRoot() }
    val metadata = remember(context) { DriveMetadata(context) }
    val driveOpeners = remember(context) { DriveOpeners(context) }
    OpenTiming("Files", state is DriveListState.Items)
    var grid by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(DriveSort.Name) }
    var toolsOpen by remember { mutableStateOf(false) }
    var systemFoldersOpen by remember { mutableStateOf(false) }
    var leftOutAsk by remember { mutableStateOf<Pair<Set<String>, List<String>>?>(null) } // left-out folders with files in them
    var tagsOpen by remember { mutableStateOf(false) }
    var emptyTrashConfirm by remember { mutableStateOf(false) }
    var newOpen by remember { mutableStateOf(false) } // New: a folder, a template's copy, a text file or a Word document here
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var metadataVersion by remember { mutableStateOf(0) }
    // each checked on disk (50 recents): off the screen's thread, the last ones shown until then
    val recentItems by produceState(lastRecents, recentsVersion, hasAccess) { value = if (hasAccess) withContext(Dispatchers.IO) { recents() }.also { lastRecents = it } else emptyList() }
    val favoriteItems by produceState(lastFavorites, metadataVersion, hasAccess) { value = if (hasAccess) withContext(Dispatchers.IO) { metadata.favorites(root) }.also { lastFavorites = it } else emptyList() }
    val filtering = searchQuery.isNotBlank() || selectedTag != null
    val searchItems by produceState(emptyList<DriveItem>(), searchQuery, selectedTag, metadataVersion, state, hasAccess, diskVersion) {
        value = if (!hasAccess || !filtering) emptyList() else withContext(Dispatchers.IO) {
            runCatching {
                DriveRules.allItems(root).filter { item ->
                    val itemTags = metadata.tags(item.relativePath)
                    (selectedTag == null || selectedTag in itemTags) &&
                        (searchQuery.isBlank() || item.file.name.contains(searchQuery.trim(), ignoreCase = true) || itemTags.any { it.contains(searchQuery.trim(), ignoreCase = true) })
                }
            }.getOrDefault(emptyList())
        }
    }
    val spaceUsage by produceState<DriveSpaceUsage?>(null, toolsOpen, metadataVersion, state) {
        value = if (toolsOpen && hasAccess) withContext(Dispatchers.IO) { runCatching { DriveRules.spaceUsage(root) }.getOrNull() } else null
    }
    fun perform(item: DriveItem, action: DriveItemAction) {
        if (action == DriveItemAction.Scan) { openInScanner(item.file); return }
        Thread {
            val result = runCatching {
                when (action) {
                    is DriveItemAction.Rename -> DriveRules.rename(root, item.relativePath, action.name).also { metadata.rewritePath(item.relativePath, it); driveOpeners.rewritePath(item.relativePath, it) }
                    is DriveItemAction.Copy -> DriveRules.copy(root, item.relativePath, action.destination)
                    is DriveItemAction.Move -> DriveRules.move(root, item.relativePath, action.destination).also { metadata.rewritePath(item.relativePath, it); driveOpeners.rewritePath(item.relativePath, it) }
                    is DriveItemAction.Favorite -> metadata.setFavorite(item.relativePath, action.add)
                    is DriveItemAction.Color -> metadata.setColor(item.relativePath, action.color)
                    is DriveItemAction.Tags -> metadata.setTags(item.relativePath, action.names)
                    DriveItemAction.Trash -> DriveRules.moveToTrash(root, item.relativePath).also { metadata.rewritePath(item.relativePath, it); driveOpeners.rewritePath(item.relativePath, it) }
                    DriveItemAction.Scan -> Unit
                }
            }
            Handler(Looper.getMainLooper()).post {
                result.onSuccess { metadataVersion++; refresh() }.onFailure { error = it.shownMessage(context) ?: context.getString(R.string.files_error_change) }
            }
        }.start()
    }
    /** The same actions, applied to a whole selection in one pass, with one refresh at the end. */
    fun performAll(items: List<DriveItem>, action: DriveItemAction) {
        if (items.isEmpty()) return
        Thread {
            val failures = items.mapNotNull { item ->
                runCatching {
                    when (action) {
                        is DriveItemAction.Favorite -> metadata.setFavorite(item.relativePath, action.add)
                        is DriveItemAction.Move -> DriveRules.move(root, item.relativePath, action.destination)
                            .also { metadata.rewritePath(item.relativePath, it); driveOpeners.rewritePath(item.relativePath, it) }
                        DriveItemAction.Trash -> DriveRules.moveToTrash(root, item.relativePath)
                            .also { metadata.rewritePath(item.relativePath, it); driveOpeners.rewritePath(item.relativePath, it) }
                        is DriveItemAction.Copy -> DriveRules.copy(root, item.relativePath, action.destination)
                        is DriveItemAction.Tags -> metadata.setTags(item.relativePath, action.names)
                        else -> Unit // rename and folder colour are about one item by nature
                    }
                }.exceptionOrNull()?.let { "${item.file.name}: ${it.shownMessage(context) ?: context.getString(R.string.files_failed)}" }
            }
            Handler(Looper.getMainLooper()).post {
                metadataVersion++
                refresh()
                if (failures.isNotEmpty()) error = failures.joinToString("\n")
            }
        }.start()
    }
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var bulkPanel by remember { mutableStateOf<DriveBulkPanel?>(null) }
    LaunchedEffect(folder, pane) { selection = emptySet() } // leaving the folder ends the selection
    val chosen = (state as? DriveListState.Items)?.entries.orEmpty().filter { it.relativePath in selection }
    fun createTag(name: String): String? = runCatching { metadata.createTag(name) }.fold(
        onSuccess = { metadataVersion++; null },
        onFailure = { it.shownMessage(context) ?: context.getString(R.string.files_error_tag) },
    )
    fun emptyTrash() {
        Thread {
            val result = runCatching { DriveRules.emptyTrash(root) }
            Handler(Looper.getMainLooper()).post {
                result.onSuccess { metadataVersion++; refresh() }.onFailure { error = it.shownMessage(context) ?: context.getString(R.string.files_error_empty_trash) }
            }
        }.start()
    }
    Box(Modifier.fillMaxSize()) {
        when {
            !hasAccess -> Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.files_access_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.files_access_body))
                Button(onClick = grant) { Text(stringResource(R.string.files_access_allow), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            filtering -> DriveFiles("", DriveListState.Items("", searchItems), grid, sort, metadata, { grid = !grid }, { relative -> searchQuery = ""; selectedTag = null; openFolder(relative) }, { relative -> error = openFile(relative, false) }, { relative -> error = openFile(relative, true) }, { searchQuery = ""; selectedTag = null }, refresh, ::perform, ::performAll, title = selectedTag?.let { "#$it" } ?: stringResource(R.string.files_search), emptyMessage = stringResource(R.string.files_search_none), metadataVersion = metadataVersion)
            pane == DrivePane.Home -> DriveHome(root, recentItems, metadata, metadataVersion, home, { relative -> error = openFile(relative, false) }, { relative -> error = openFile(relative, true) }, { setPane(DrivePane.Files); refresh() }, ::perform)
            pane == DrivePane.Favorites -> DriveFavorites(favoriteItems, metadata, home, { relative -> error = openFile(relative, false) }, { relative -> error = openFile(relative, true) }, openFolder, ::perform)
            pane == DrivePane.Files -> DriveFiles(folder, state, grid, sort, metadata, { grid = !grid }, openFolder, { relative -> error = openFile(relative, false) }, { relative -> error = openFile(relative, true) }, goParent, refresh, ::perform, ::performAll, selection, { selection = it }, emptyTrash = if (folder == "Trash") { { emptyTrashConfirm = true } } else null, metadataVersion = metadataVersion)
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DriveBottomBar(
                pane = pane,
                home = { searchQuery = ""; selectedTag = null; setPane(DrivePane.Home) },
                favorites = { searchQuery = ""; selectedTag = null; setPane(DrivePane.Favorites) },
                files = { searchQuery = ""; selectedTag = null; setPane(DrivePane.Files); refresh() },
                new = { newOpen = true },
                expand = { toolsOpen = true },
                visible = !toolsOpen && selection.isEmpty(),
            )
            SearchButton(visible = !toolsOpen && selection.isEmpty(), description = stringResource(R.string.files_search)) {
                setPane(DrivePane.Files)
                searchOpen = true
            }
        }
        // The two islands trade places rather than stack: navigation fades out, the actions for what you picked
        // fade in where it was.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
            IslandVisibility(selection.isNotEmpty()) {
                DriveSelectionActions(
                    count = chosen.size,
                    inTrash = folder == "Trash",
                    anyFile = chosen.any { !it.isDirectory },
                    share = { shareDriveFiles(context, chosen.filterNot(DriveItem::isDirectory).map(DriveItem::file)) },
                    favorite = { performAll(chosen, DriveItemAction.Favorite(true)); selection = emptySet() },
                    tags = { bulkPanel = DriveBulkPanel.Tags },
                    copy = { bulkPanel = DriveBulkPanel.Copy },
                    move = { bulkPanel = DriveBulkPanel.Move },
                    trash = { performAll(chosen, DriveItemAction.Trash); selection = emptySet() },
                    restore = { performAll(chosen, DriveItemAction.Move("")); selection = emptySet() },
                    cancel = { selection = emptySet() },
                )
            }
        }
        error?.let { message ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 84.dp, start = 20.dp, end = 20.dp)) {
                Text(message, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
    if (searchOpen) SearchSheet(searchQuery, { searchQuery = it }, { searchQuery = "" }, { searchOpen = false }, title = stringResource(R.string.files_search), hint = stringResource(R.string.files_search_hint))
    if (toolsOpen) DriveToolsSheet(
        sort = sort,
        setSort = { sort = it },
        openTags = { toolsOpen = false; tagsOpen = true },
        openTrash = { selectedTag = null; searchQuery = ""; setPane(DrivePane.Files); toolsOpen = false; openFolder("Trash") },
        openSystemFolders = { toolsOpen = false; systemFoldersOpen = true },
        usage = spaceUsage,
        dismiss = { toolsOpen = false },
    )
    fun saveSystemFolders(chosen: Set<String>, toTrash: Boolean) {
        val leftOut = DriveRules.chosenFolders - chosen
        SystemFolderChoice.choose(context, chosen)
        Thread {
            val result = runCatching {
                leftOut.flatMap { DriveRules.clearLeftOut(root, it, toTrash) }.forEach { (from, to) -> metadata.rewritePath(from, to); driveOpeners.rewritePath(from, to) }
            }
            Handler(Looper.getMainLooper()).post {
                result.onFailure { error = it.shownMessage(context) ?: context.getString(R.string.files_error_move_out) }
                metadataVersion++
                refresh() // makes the chosen ones (ensureSystemFolders) and shows them
            }
        }.start()
    }
    if (systemFoldersOpen) SystemFoldersSheet(dismiss = { systemFoldersOpen = false }) { chosen ->
        systemFoldersOpen = false
        // A folder left out goes; one with something in it asks first where that goes (asked 17 Oct).
        val full = (DriveRules.chosenFolders - chosen).filter { File(root, it).list().orEmpty().isNotEmpty() }
        if (full.isEmpty()) saveSystemFolders(chosen, toTrash = false) else leftOutAsk = chosen to full
    }
    leftOutAsk?.let { (chosen, full) ->
        val one = full.size == 1
        AlertDialog(
            onDismissRequest = { leftOutAsk = null },
            title = { Text(if (one) stringResource(R.string.files_left_out_one, full[0]) else stringResource(R.string.files_left_out_many, full.dropLast(1).joinToString(", "), full.last())) },
            text = { Text(stringResource(if (one) R.string.files_left_out_body_one else R.string.files_left_out_body_many)) },
            confirmButton = { TextButton(onClick = { leftOutAsk = null; saveSystemFolders(chosen, toTrash = false) }) { Text(stringResource(R.string.files_move_out), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { leftOutAsk = null }) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    TextButton(onClick = { leftOutAsk = null; saveSystemFolders(chosen, toTrash = true) }) { Text(stringResource(R.string.files_move_to_trash), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            },
        )
    }
    if (tagsOpen) DriveTagsSheet(
        tags = metadata.allTags(root),
        selectedTag = selectedTag,
        chooseTag = { selectedTag = it; searchQuery = ""; setPane(DrivePane.Files); tagsOpen = false },
        createTag = ::createTag,
        dismiss = { tagsOpen = false },
    )
    bulkPanel?.let { panel ->
        DriveBulkSheet(panel, chosen, metadata, { items, action -> performAll(items, action); selection = emptySet() }) { bulkPanel = null }
    }
    // New (asked 14 Oct): made in the folder open in Files (Drive's top elsewhere, never in Trash), then opened.
    if (newOpen) {
        val here = if (pane == DrivePane.Files && folder != "Trash" && !folder.startsWith("Trash/")) folder else ""
        NewItemDialog(here.ifEmpty { "Tetra" }, remember { DriveRules.newKinds(root, runCatching { context.assets.open("blank.pptx").use { it.readBytes() } }.getOrNull()) }, { newOpen = false }) { kind, name ->
            val made = runCatching { DriveRules.create(root, here, kind, name, context.getString(R.string.tables_sheet_n, 1)) }.getOrElse { return@NewItemDialog it.shownMessage(context) ?: context.getString(R.string.files_error_new) }
            newOpen = false
            if (kind.extension != null) JustMade.mark(File(root, made)) // it opens editing
            refresh()
            if (kind.extension == null) openFolder(made) else error = openFile(made, false)
            null
        }
    }
    if (emptyTrashConfirm) EmptyTrashSheet(
        dismiss = { emptyTrashConfirm = false },
        emptyTrash = { emptyTrashConfirm = false; emptyTrash() },
    )
}

@Composable
private fun DriveFavorites(items: List<DriveItem>, metadata: DriveMetadata, home: () -> Unit, openFile: (String) -> Unit, openWith: (String) -> Unit, openFolder: (String) -> Unit, perform: (DriveItem, DriveItemAction) -> Unit) {
    val textColor = MaterialTheme.colorScheme.onBackground
    var moreItem by remember { mutableStateOf<DriveItem?>(null) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FilesPageHeader(stringResource(R.string.files_favorites), R.drawable.ic_favorite_border, home)
        if (items.isEmpty()) {
            Icon(painterResource(R.drawable.ic_favorite_border), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(42.dp))
            Text(stringResource(R.string.files_favorites_none), color = textColor, style = MaterialTheme.typography.titleMedium)
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(items, key = { it.relativePath }) { item ->
                DriveRowItem(item, metadata.color(item.relativePath), metadata.tags(item.relativePath), openFolder, openFile) { moreItem = item }
            }
            item { Box(Modifier.heightIn(min = 92.dp)) }
        }
    }
    moreItem?.let { item -> DriveItemMoreSheet(item, metadata, { if (item.isDirectory) openFolder(item.relativePath) else openFile(item.relativePath) }, { openWith(item.relativePath) }, perform, { moreItem = null }) }
}

private var lastRecents = emptyList<DriveRecent>()
private var lastFavorites = emptyList<DriveItem>()
private var lastRecentItems = emptyList<DriveItem>()

@Composable
private fun DriveHome(root: File, recents: List<DriveRecent>, metadata: DriveMetadata, metadataVersion: Int, home: () -> Unit, openFile: (String) -> Unit, openWith: (String) -> Unit, openFiles: () -> Unit, perform: (DriveItem, DriveItemAction) -> Unit) {
    var moreItem by remember { mutableStateOf<DriveItem?>(null) }
    val items by produceState(lastRecentItems, recents, metadataVersion) {
        value = withContext(Dispatchers.IO) { recents.mapNotNull { recent -> runCatching { DriveRules.item(root, recent.relativePath) }.getOrNull()?.let { DriveItem(it, recent.relativePath, false) } } }.also { lastRecentItems = it }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            FilesPageHeader(stringResource(R.string.files_recent), R.drawable.ic_home, home)
        }
        item { Box(Modifier.heightIn(min = 6.dp)) }
        if (items.isEmpty()) item {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.files_recent_none))
                    Text(stringResource(R.string.files_recent_none_body), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = openFiles) { Text(stringResource(R.string.files_browse), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        items(items, key = { it.relativePath }) { item ->
            DriveRowItem(item, metadata.color(item.relativePath), metadata.tags(item.relativePath), {}, openFile) { moreItem = item }
        }
        item { Box(Modifier.heightIn(min = 92.dp)) }
    }
    moreItem?.let { item -> DriveItemMoreSheet(item, metadata, { openFile(item.relativePath) }, { openWith(item.relativePath) }, perform, { moreItem = null }) }
}

@Composable
private fun DriveFiles(
    folder: String,
    state: DriveListState,
    grid: Boolean,
    sort: DriveSort,
    metadata: DriveMetadata,
    toggleGrid: () -> Unit,
    openFolder: (String) -> Unit,
    openFile: (String) -> Unit,
    openWith: (String) -> Unit,
    goParent: () -> Unit,
    refresh: () -> Unit,
    perform: (DriveItem, DriveItemAction) -> Unit,
    performAll: (List<DriveItem>, DriveItemAction) -> Unit,
    selected: Set<String> = emptySet(),
    setSelected: (Set<String>) -> Unit = {},
    title: String? = null,
    emptyMessage: String = stringResource(R.string.files_folder_empty),
    emptyTrash: (() -> Unit)? = null,
    metadataVersion: Int = 0,
) {
    val context = LocalContext.current
    var moreItem by remember { mutableStateOf<DriveItem?>(null) }
    val isTrash = folder == "Trash"
    // read once for the whole list, not for each row as it scrolls in (a folder of thousands)
    val colors = remember(metadataVersion) { metadata.colorMap() }
    val tags = remember(metadataVersion) { metadata.tagMap() }
    val topPadding = if (isTrash) 206.dp else 132.dp
    PullToRefreshBox(isRefreshing = state is DriveListState.Loading, onRefresh = refresh, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            when (state) {
                DriveListState.Idle, DriveListState.Loading -> TimelineMessage(stringResource(R.string.files_loading))
                is DriveListState.Error -> TimelineMessage(state.message, MaterialTheme.colorScheme.error)
                is DriveListState.Items -> {
                    val entries = remember(state.entries, sort) {
                        when (sort) {
                            DriveSort.Name -> state.entries.sortedWith(compareBy<DriveItem>({ !DriveRules.isSystem(it.relativePath) }, { !it.isDirectory }, { it.file.name.lowercase(Locale.ROOT) }))
                            DriveSort.Modified -> state.entries.sortedWith(compareBy<DriveItem>({ !DriveRules.isSystem(it.relativePath) }, { !it.isDirectory }).thenByDescending { it.lastModified }.thenBy { it.file.name.lowercase(Locale.ROOT) })
                        }
                    }
                    if (state.entries.isEmpty()) TimelineMessage(emptyMessage)
                    else if (grid) LazyVerticalGrid(
                        gridColumns(2),
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, top = topPadding, end = 16.dp, bottom = 164.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(entries, key = { it.relativePath }) { item -> DriveGridItem(
                            item, colors[item.relativePath], tags[item.relativePath].orEmpty(), openFolder, openFile,
                            selected = item.relativePath in selected, selecting = selected.isNotEmpty(),
                            toggle = { setSelected(if (item.relativePath in selected) selected - item.relativePath else selected + item.relativePath) },
                        ) { moreItem = item } }
                    } else LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, top = topPadding, end = 16.dp, bottom = 164.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(entries, key = { it.relativePath }) { item -> DriveRowItem(
                            item, colors[item.relativePath], tags[item.relativePath].orEmpty(), openFolder, openFile,
                            selected = item.relativePath in selected, selecting = selected.isNotEmpty(),
                            toggle = { setSelected(if (item.relativePath in selected) selected - item.relativePath else selected + item.relativePath) },
                        ) { moreItem = item } }
                    }
                }
            }
            FilesPageHeader(
                title = title ?: if (folder.isEmpty()) stringResource(R.string.app_files) else folder.substringAfterLast('/'),
                icon = if (isTrash) R.drawable.ic_delete else R.drawable.ic_folder,
                back = goParent,
                emptyTrash = emptyTrash,
                modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp),
                island = true,
                trailing = {
                    Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                        IconButton(onClick = toggleGrid) {
                            Icon(painterResource(if (grid) R.drawable.ic_view_list else R.drawable.ic_view_grid), contentDescription = stringResource(if (grid) R.string.files_show_list else R.string.files_show_grid))
                        }
                    }
                },
            )
            if (isTrash) TrashWarning(modifier = Modifier.align(Alignment.TopCenter).padding(start = 16.dp, top = 112.dp, end = 16.dp))
            moreItem?.let { item ->
                DriveItemMoreSheet(item, metadata, { if (item.isDirectory) openFolder(item.relativePath) else openFile(item.relativePath) }, { openWith(item.relativePath) }, perform, { moreItem = null })
            }
        }
    }
}

@Composable
internal fun FilesPageHeader(
    title: String,
    icon: Int,
    back: () -> Unit,
    emptyTrash: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    island: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
            IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_back)) }
        }
        // A header floating over a scrolling list sits in the same island as Gallery's date, so it stays legible.
        if (island) Box(Modifier.weight(1f)) {
            Surface(color = islandColor(), contentColor = islandContentColor(), shape = MaterialTheme.shapes.extraLarge) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(28.dp))
            Text(title, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // The one destructive action on the page sits where the page's actions are, and is the only red thing.
        if (emptyTrash != null) Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = CircleShape) {
            IconButton(onClick = emptyTrash) { Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.files_empty_trash)) }
        }
        trailing?.invoke()
    }
}

@Composable private fun TrashWarning(modifier: Modifier = Modifier) = Surface(
    color = MaterialTheme.colorScheme.errorContainer,
    contentColor = MaterialTheme.colorScheme.onErrorContainer,
    shape = MaterialTheme.shapes.large,
    modifier = modifier.fillMaxWidth(),
) { Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(painterResource(R.drawable.ic_delete), contentDescription = null, modifier = Modifier.size(20.dp))
    Text(stringResource(R.string.files_trash_warning), style = MaterialTheme.typography.bodySmall)
} }

private sealed interface DriveItemAction {
    data class Rename(val name: String) : DriveItemAction
    data class Copy(val destination: String) : DriveItemAction
    data class Move(val destination: String) : DriveItemAction
    data class Favorite(val add: Boolean) : DriveItemAction
    data class Color(val color: DriveFolderColor?) : DriveItemAction
    data class Tags(val names: Set<String>) : DriveItemAction
    data object Trash : DriveItemAction
    /** A PDF's pages or a picture into the scanner, to edit as a scan. */
    data object Scan : DriveItemAction
}

private enum class DriveItemSheetPanel { Menu, Rename, Copy, Move, Tags, Color, Properties, Trash, Sync }

internal enum class DriveBulkPanel { Copy, Move, Tags }

/**
 * Copy, Move and Tags for a whole selection. The same rules and the same pickers as one item — a destination
 * out of `DriveRules.destinations`, tags out of the ones already in use — applied to everything picked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DriveBulkSheet(
    panel: DriveBulkPanel,
    items: List<DriveItem>,
    metadata: DriveMetadata,
    performAll: (List<DriveItem>, DriveItemAction) -> Unit,
    dismiss: () -> Unit,
) {
    var selectedTags by remember { mutableStateOf(emptySet<String>()) }
    var newTag by remember { mutableStateOf("") }
    var tagError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    fun submit(action: DriveItemAction) { performAll(items, action); dismiss() }
    // open all the way: half open, a folder list pushed Move here and Cancel off the screen
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = islandColor(), contentColor = islandContentColor()) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(pluralStringResource(R.plurals.files_selected, items.size, items.size), style = MaterialTheme.typography.titleLarge)
            when (panel) {
                DriveBulkPanel.Copy, DriveBulkPanel.Move -> {
                    val copying = panel == DriveBulkPanel.Copy
                    DriveFolderPicker(DriveRules.parent(items.first().relativePath), copying, items, dismiss) { destination ->
                        submit(if (copying) DriveItemAction.Copy(destination) else DriveItemAction.Move(destination))
                    }
                }
                DriveBulkPanel.Tags -> {
                    val availableTags = metadata.allTags(driveRoot()).plus(selectedTags).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
                    Text(stringResource(R.string.files_tags_replace))
                    if (availableTags.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(availableTags, key = { it }) { tag ->
                            Surface(
                                color = if (tag in selectedTags) driveNavigationSelectedColor() else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (tag in selectedTags) driveNavigationSelectedContentColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                                shape = CircleShape,
                                modifier = Modifier.clickable { selectedTags = selectedTags.let { tags -> if (tag in tags) tags - tag else tags + tag } },
                            ) { Text("#$tag", modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) }
                        }
                    }
                    OutlinedTextField(newTag, { newTag = it; tagError = null }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.files_new_tag)) }, singleLine = true, isError = tagError != null, supportingText = tagError?.let { { Text(it) } })
                    DriveWideAction(R.drawable.ic_tag, stringResource(R.string.files_add_tag)) {
                        runCatching { DriveTagRules.name(newTag) }.onSuccess { tag -> selectedTags += tag; newTag = "" }.onFailure { tagError = it.shownMessage(context) }
                    }
                    DriveWideAction(R.drawable.ic_check, stringResource(R.string.files_save_tags)) { submit(DriveItemAction.Tags(selectedTags)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DriveItemMoreSheet(item: DriveItem, metadata: DriveMetadata, open: () -> Unit, openWith: () -> Unit, perform: (DriveItem, DriveItemAction) -> Unit, dismiss: () -> Unit) {
    var panel by remember(item.relativePath) { mutableStateOf(DriveItemSheetPanel.Menu) }
    var rename by remember(item.relativePath) { mutableStateOf(item.file.name) }
    var selectedTags by remember(item.relativePath) { mutableStateOf(metadata.tags(item.relativePath)) }
    var newTag by remember(item.relativePath) { mutableStateOf("") }
    var tagError by remember(item.relativePath) { mutableStateOf<String?>(null) }
    fun submit(action: DriveItemAction) { perform(item, action); dismiss() }
    val context = LocalContext.current
    // open all the way: half open, Move's folder list pushed its buttons off the screen
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = islandColor(), contentColor = islandContentColor()) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (panel != DriveItemSheetPanel.Menu) IconButton(onClick = { panel = DriveItemSheetPanel.Menu }) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.files_back_to_actions)) }
            Text(item.file.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            when (panel) {
                DriveItemSheetPanel.Menu -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DriveActionTile(R.drawable.ic_copy, stringResource(R.string.common_copy)) { panel = DriveItemSheetPanel.Copy }
                        if (!DriveRules.isSystem(item.relativePath)) {
                            DriveActionTile(R.drawable.ic_move, stringResource(R.string.common_move)) { panel = DriveItemSheetPanel.Move }
                            DriveActionTile(R.drawable.ic_edit, stringResource(R.string.common_rename)) { panel = DriveItemSheetPanel.Rename }
                        }
                        if (!item.isDirectory) DriveActionTile(R.drawable.ic_share, stringResource(R.string.common_share)) { dismiss(); shareDriveFile(context, item.file) }
                    }
                    if (!item.isDirectory) DriveWideAction(R.drawable.ic_open_with, stringResource(R.string.files_open_with)) { dismiss(); openWith() }
                    if (!item.isDirectory && item.file.extension.lowercase() in setOf("pdf", "jpg", "jpeg", "png", "heic", "heif", "webp")) DriveWideAction(R.drawable.ic_perspective, stringResource(R.string.pdf_open_in_scanner)) { submit(DriveItemAction.Scan) }
                    if (!item.isDirectory && Beside.can(context)) DriveWideAction(R.drawable.ic_call_split, stringResource(R.string.files_open_beside)) { dismiss(); Beside.open(context, "file:${item.relativePath}") }
                    DriveWideAction(if (metadata.isFavorite(item.relativePath)) R.drawable.ic_remove_favorite else R.drawable.ic_favorite_border, stringResource(if (metadata.isFavorite(item.relativePath)) R.string.files_favorite_remove else R.string.files_favorite_add)) { submit(DriveItemAction.Favorite(!metadata.isFavorite(item.relativePath))) }
                    DriveWideAction(R.drawable.ic_tag, stringResource(R.string.files_tags)) { panel = DriveItemSheetPanel.Tags }
                    DriveWideAction(R.drawable.ic_info, stringResource(R.string.files_properties)) { panel = DriveItemSheetPanel.Properties }
                    if (item.isDirectory) DriveWideAction(R.drawable.ic_palette, stringResource(R.string.files_folder_color)) { panel = DriveItemSheetPanel.Color }
                    if (item.isDirectory) DriveWideAction(R.drawable.ic_sync, stringResource(R.string.files_sync_now)) { panel = DriveItemSheetPanel.Sync }
                    // Already in Trash: the useful action is the opposite one — put it back where Drive keeps things.
                    if (item.relativePath.startsWith("Trash/")) DriveWideAction(R.drawable.ic_restore, stringResource(R.string.files_restore)) { submit(DriveItemAction.Move("")) }
                    else if (item.relativePath != "Trash" && !DriveRules.isSystem(item.relativePath)) DriveWideAction(R.drawable.ic_delete, stringResource(R.string.files_move_to_trash)) { panel = DriveItemSheetPanel.Trash }
                    if (DriveRules.isSystem(item.relativePath)) Text(stringResource(R.string.files_system_note), style = MaterialTheme.typography.bodySmall)
                }
                DriveItemSheetPanel.Rename -> {
                    Text(stringResource(if (item.isDirectory) R.string.files_rename_folder else R.string.files_rename_file))
                    OutlinedTextField(rename, { rename = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.files_new_name)) }, singleLine = true)
                    DriveWideAction(R.drawable.ic_check, stringResource(R.string.common_rename)) { submit(DriveItemAction.Rename(rename)) }
                }
                DriveItemSheetPanel.Copy, DriveItemSheetPanel.Move -> {
                    val copying = panel == DriveItemSheetPanel.Copy
                    DriveFolderPicker(DriveRules.parent(item.relativePath), copying, listOf(item), dismiss) { destination ->
                        submit(if (copying) DriveItemAction.Copy(destination) else DriveItemAction.Move(destination))
                    }
                }
                DriveItemSheetPanel.Tags -> {
                    val availableTags = metadata.allTags(driveRoot()).plus(selectedTags).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
                    Text(stringResource(R.string.files_tags_pick))
                    if (availableTags.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(availableTags, key = { it }) { tag ->
                            Surface(
                                color = if (tag in selectedTags) driveNavigationSelectedColor() else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (tag in selectedTags) driveNavigationSelectedContentColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                                shape = CircleShape,
                                modifier = Modifier.clickable { selectedTags = selectedTags.let { tags -> if (tag in tags) tags - tag else tags + tag } },
                            ) { Text("#$tag", modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) }
                        }
                    }
                    OutlinedTextField(newTag, { newTag = it; tagError = null }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.files_new_tag)) }, singleLine = true, isError = tagError != null, supportingText = tagError?.let { { Text(it) } })
                    DriveWideAction(R.drawable.ic_tag, stringResource(R.string.files_add_tag)) {
                        runCatching { DriveTagRules.name(newTag) }.onSuccess { tag -> selectedTags += tag; newTag = "" }.onFailure { tagError = it.shownMessage(context) }
                    }
                    DriveWideAction(R.drawable.ic_check, stringResource(R.string.files_save_tags)) { submit(DriveItemAction.Tags(selectedTags)) }
                }
                DriveItemSheetPanel.Color -> {
                    Text(stringResource(R.string.files_folder_color_pick))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DriveFolderColor.entries.forEach { color -> DriveColorCircle(color, metadata.color(item.relativePath) == color) { submit(DriveItemAction.Color(color)) } }
                    }
                    DriveNoColorCircle(metadata.color(item.relativePath) == null) { submit(DriveItemAction.Color(null)) }
                }
                DriveItemSheetPanel.Properties -> {
                    Text(if (item.isDirectory) stringResource(R.string.files_folder) else fileTypeLabel(item.file.name))
                    Text(stringResource(R.string.files_location, "$DRIVE_PATH${item.relativePath}"), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.files_bytes, item.file.length()), style = MaterialTheme.typography.bodyMedium)
                    if (metadata.tags(item.relativePath).isNotEmpty()) Text(stringResource(R.string.files_tags_list, metadata.tags(item.relativePath).joinToString()), style = MaterialTheme.typography.bodyMedium)
                    DriveWideAction(if (item.isDirectory) R.drawable.ic_folder else R.drawable.ic_file, stringResource(if (item.isDirectory) R.string.files_open_folder else R.string.files_open_file)) { dismiss(); open() }
                }
                DriveItemSheetPanel.Trash -> {
                    Text(stringResource(R.string.files_trash_ask))
                    DriveWideAction(R.drawable.ic_delete, stringResource(R.string.files_move_to_trash)) { submit(DriveItemAction.Trash) }
                }
                DriveItemSheetPanel.Sync -> {
                    Text(stringResource(R.string.files_sync_none))
                    DriveWideAction(R.drawable.ic_check, stringResource(R.string.common_ok), dismiss)
                }
            }
        }
    }
}

private val DriveTrashAccent = Color(0xFFE3685F)

@Composable internal fun RowScope.DriveActionTile(icon: Int, description: String, click: () -> Unit) = Surface(
    color = driveNavigationSelectedColor(), contentColor = driveNavigationSelectedContentColor(), shape = CircleShape,
    modifier = Modifier.weight(1f).height(64.dp),
) { IconButton(onClick = click, modifier = Modifier.fillMaxWidth().height(64.dp)) { Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size(26.dp)) } }

/**
 * Where to move or copy to, one level at a time (asked 17 Oct: every folder of every level at once was too much): the
 * folders inside the one you are in, a tap goes in, Up goes back out; Move here (or Copy here) acts on the folder you
 * are in, Cancel leaves. A folder being moved is not offered as a way in (not into itself); Move here is off where
 * everything already is.
 */
@Composable
private fun DriveFolderPicker(start: String, copying: Boolean, moving: List<DriveItem>, cancel: () -> Unit, pick: (String) -> Unit) {
    var at by remember { mutableStateOf(start) }
    val inside = remember(at) { DriveRules.subfolders(driveRoot(), at).filterNot { f -> moving.any { it.isDirectory && it.relativePath == f } } }
    Text(stringResource(if (copying) R.string.files_copy_into else R.string.files_move_into), style = MaterialTheme.typography.bodyMedium)
    Text(listOf("Tetra").plus(at.split('/').filter { it.isNotEmpty() }).joinToString(" › "), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (at.isNotEmpty()) item("up") {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().clickable { at = DriveRules.parent(at) }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(painterResource(R.drawable.ic_keyboard_arrow_up), contentDescription = null, modifier = Modifier.size(22.dp))
                    Text(stringResource(R.string.files_up_to, DriveRules.parent(at).substringAfterLast('/').ifEmpty { "Tetra" }), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        items(inside, key = { it }) { f ->
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().clickable { at = f }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(painterResource(driveItemIcon(DriveItem(File(driveRoot(), f), f, true))), contentDescription = null, modifier = Modifier.size(22.dp))
                    Text(f.substringAfterLast('/'), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = stringResource(R.string.common_open), modifier = Modifier.size(20.dp))
                }
            }
        }
        if (inside.isEmpty()) item("none") { Text(stringResource(R.string.files_no_folders), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp)) }
    }
    val already = !copying && moving.all { DriveRules.parent(it.relativePath) == at }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = cancel, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Button(onClick = { pick(at) }, enabled = !already, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
            Icon(painterResource(if (copying) R.drawable.ic_copy else R.drawable.ic_move), contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(if (copying) R.string.files_copy_here else R.string.files_move_here), modifier = Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (already) Text(stringResource(R.string.files_already_here), style = MaterialTheme.typography.bodySmall)
}

@Composable internal fun DriveWideAction(icon: Int, description: String, click: () -> Unit) = Surface(
    color = driveNavigationSelectedColor(), contentColor = driveNavigationSelectedContentColor(), shape = CircleShape,
    modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp).clickable(onClick = click),
) { Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
    Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size(24.dp))
    Text(description, modifier = Modifier.padding(start = 10.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
} }

@Composable private fun DriveColorCircle(color: DriveFolderColor, selected: Boolean, click: () -> Unit) = Surface(
    color = driveFolderAccent(color).copy(alpha = 0.68f), contentColor = if (color == DriveFolderColor.Yellow) Color.Black else Color.White, shape = CircleShape,
    modifier = Modifier.size(44.dp).then(if (selected) Modifier.border(2.dp, islandContentColor(), CircleShape) else Modifier).clickable(onClick = click),
) { if (selected) Icon(painterResource(R.drawable.ic_check), contentDescription = stringResource(when (color) {
    DriveFolderColor.Blue -> R.string.files_color_blue; DriveFolderColor.Green -> R.string.files_color_green; DriveFolderColor.Yellow -> R.string.files_color_yellow
    DriveFolderColor.Red -> R.string.files_color_red; DriveFolderColor.Purple -> R.string.files_color_purple
}), modifier = Modifier.padding(10.dp)) }

@Composable private fun DriveNoColorCircle(selected: Boolean, click: () -> Unit) = Surface(
    color = driveNavigationSelectedColor(), contentColor = driveNavigationSelectedContentColor(), shape = CircleShape,
    modifier = Modifier.size(44.dp).then(if (selected) Modifier.border(2.dp, islandContentColor(), CircleShape) else Modifier).clickable(onClick = click),
) { Icon(painterResource(R.drawable.ic_no_color), contentDescription = stringResource(R.string.files_color_none), modifier = Modifier.padding(10.dp), tint = DriveTrashAccent) }

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun DriveRowItem(
    item: DriveItem, folderColor: DriveFolderColor?, tags: Set<String>, openFolder: (String) -> Unit, openFile: (String) -> Unit,
    selected: Boolean = false, selecting: Boolean = false, toggle: () -> Unit = {}, more: () -> Unit,
) = Surface(
    shape = MaterialTheme.shapes.medium,
    color = if (selected) driveSelectionColor() else MaterialTheme.colorScheme.surfaceVariant,
    contentColor = if (selected) driveSelectionContentColor() else MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.fillMaxWidth().combinedClickable(
        onLongClick = toggle,
        onClick = { if (selecting) toggle() else if (item.isDirectory) openFolder(item.relativePath) else openFile(item.relativePath) },
    ),
) {
    Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (item.isDirectory || selected) DriveItemIcon(item, folderColor, selected, 32.dp)
        else FileThumb(item.file, Modifier.size(36.dp).clip(MaterialTheme.shapes.small), item.lastModified, item.length) { DriveItemIcon(item, folderColor, false, 32.dp) }
        Column(Modifier.weight(1f)) {
            Text(item.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(driveItemTypeLabel(item) + if (tags.isEmpty()) "" else " · ${tags.joinToString(" ") { "#$it" }}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = more) { Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.files_more_options)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun DriveGridItem(
    item: DriveItem, folderColor: DriveFolderColor?, tags: Set<String>, openFolder: (String) -> Unit, openFile: (String) -> Unit,
    selected: Boolean, selecting: Boolean, toggle: () -> Unit, more: () -> Unit,
) = Surface(
    shape = MaterialTheme.shapes.large,
    color = if (selected) driveSelectionColor() else MaterialTheme.colorScheme.surfaceVariant,
    contentColor = if (selected) driveSelectionContentColor() else MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.aspectRatio(1f).combinedClickable(
        onLongClick = toggle,
        onClick = { if (selecting) toggle() else if (item.isDirectory) openFolder(item.relativePath) else openFile(item.relativePath) },
    ),
) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // a file's own picture fills the tile above its name (a document its first page, Office's glyph on it)
            val thumb = !item.isDirectory && !selected && FileThumbs.kind(item.file.name) != null
            if (!thumb) DriveItemIcon(item, folderColor, selected, 64.dp)
            else FileThumb(item.file, Modifier.matchParentSize().clip(MaterialTheme.shapes.medium), item.lastModified, item.length) { DriveItemIcon(item, folderColor, false, 64.dp) }
            Surface(color = if (thumb) islandColor().copy(alpha = 0.7f) else Color.Transparent, contentColor = islandContentColor(), shape = CircleShape, modifier = Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = more) { Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.files_more_options)) }
            }
        }
        Column {
            Text(item.file.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(driveItemTypeLabel(item) + if (tags.isEmpty()) "" else " · ${tags.joinToString(" ") { "#$it" }}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable private fun DriveItemIcon(item: DriveItem, folderColor: DriveFolderColor?, selected: Boolean, size: androidx.compose.ui.unit.Dp) {
    // a file of a known category: its icon in its own colours (file-kinds.json, the user's tiles of 24 Oct)
    val kind = if (selected || item.isDirectory) null else FileKinds.icon(item.file.name)
    if (kind != null) Image(painterResource(kind), contentDescription = driveItemIconDescription(item), modifier = Modifier.size(size))
    else DriveItemGlyph(item, folderColor, selected, size)
}

@Composable private fun DriveItemGlyph(item: DriveItem, folderColor: DriveFolderColor?, selected: Boolean, size: androidx.compose.ui.unit.Dp) = Icon(
    painterResource(if (selected) R.drawable.ic_check else driveItemIcon(item)),
    contentDescription = if (selected) stringResource(R.string.files_selected_one) else driveItemIconDescription(item),
    tint = if (selected) driveSelectionContentColor() else driveItemIconColor(item, folderColor),
    modifier = Modifier.size(size),
)

private fun driveItemIcon(item: DriveItem): Int = when {
    item.relativePath == "Trash" -> R.drawable.ic_delete
    // System folders carry their emblem in the folder itself, so they read as different at a glance.
    item.relativePath == "Documents" -> R.drawable.ic_folder_documents
    item.relativePath == "Documents/Scanned Documents" -> R.drawable.ic_folder_scans
    DriveRules.isSystem(item.relativePath) -> systemFolderLook(item.relativePath)?.first ?: R.drawable.ic_folder
    item.isDirectory -> R.drawable.ic_folder
    else -> R.drawable.ic_file
}

@OptIn(ExperimentalLayoutApi::class)
/** New: what to make ([kinds]) and its name; [create] gives back a problem to show, or null when done. */
@Composable private fun NewItemDialog(where: String, kinds: List<DriveNew>, dismiss: () -> Unit, create: (DriveNew, String) -> String?) {
    var kind by remember { mutableStateOf(kinds.first()) }
    var name by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.common_new)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.files_new_in, where), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                kinds.forEach { k ->
                    // Each kind in its own colour and picture, as Files shows it: Word blue, text purple (asked 14 Oct).
                    val ext = k.extension?.lowercase(Locale.ROOT)
                    val tint = when (ext) { null -> Color(0xFFE0A030); "txt", "md" -> Category.Text.color; else -> driveExtensionColor(ext).takeIf { it != MaterialTheme.colorScheme.onSurfaceVariant } ?: Category.Text.color }
                    val icon = when (ext) { null -> R.drawable.ic_folder; "docx", "doc", "odt", "rtf" -> R.drawable.ic_file_word; "txt", "md" -> R.drawable.ic_file_text; else -> R.drawable.ic_file }
                    androidx.compose.material3.FilterChip(selected = kind == k, onClick = { kind = k; problem = null },
                        label = { Text(if (k.template != null && !k.extension.isNullOrEmpty()) "${k.label} (.${k.extension})" else k.shown?.let { stringResource(it) } ?: k.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(painterResource(icon), null, Modifier.size(20.dp), tint = if (kind == k) Color.White else tint) },
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(selectedContainerColor = tint, selectedLabelColor = Color.White,
                            containerColor = tint.copy(alpha = 0.12f), labelColor = MaterialTheme.colorScheme.onSurface),
                        border = androidx.compose.material3.FilterChipDefaults.filterChipBorder(enabled = true, selected = kind == k, borderColor = tint.copy(alpha = 0.5f), selectedBorderColor = tint))
                }
            }
            OutlinedTextField(name, { name = it; problem = null }, singleLine = true, label = { Text(stringResource(R.string.common_name)) },
                suffix = { kind.extension?.takeIf { it.isNotEmpty() && !name.endsWith(".$it", ignoreCase = true) }?.let { Text(".$it") } },
                isError = problem != null, supportingText = problem?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth().focusRequester(focus))
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { problem = create(kind, name.trim()) }) { Text(stringResource(R.string.files_create), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) } })
}

@Composable private fun driveItemIconDescription(item: DriveItem): String = stringResource(if (item.relativePath == "Trash") R.string.files_trash else if (item.isDirectory) R.string.files_folder else R.string.files_file)

@Composable private fun driveItemTypeLabel(item: DriveItem): String = if (item.relativePath == "Trash") stringResource(R.string.files_trash) else if (DriveRules.isSystem(item.relativePath)) stringResource(R.string.files_system_folder) else if (item.isDirectory) stringResource(R.string.files_folder) else fileTypeLabel(item.file.name)

@Composable private fun driveItemIconColor(item: DriveItem, folderColor: DriveFolderColor?): Color = when {
    item.relativePath == "Trash" -> DriveTrashAccent
    folderColor == null && DriveRules.isSystem(item.relativePath) -> systemFolderLook(item.relativePath)?.second ?: driveItemColor(item, null)
    else -> driveItemColor(item, folderColor)
}

/** A chosen system folder's emblem and colour, told apart at a glance (asked 16 Oct). */
private fun systemFolderLook(name: String): Pair<Int, Color>? = when (name) {
    "Music" -> R.drawable.ic_folder_music to Color(0xFFE5739D)
    "Videos" -> R.drawable.ic_folder_videos to Color(0xFFE3685F)
    "Finance" -> R.drawable.ic_folder_finance to Color(0xFF55A96B)
    "Work" -> R.drawable.ic_folder_work to Color(0xFFA1887F)
    "Education" -> R.drawable.ic_folder_education to Color(0xFF4F86E8)
    "Games" -> R.drawable.ic_folder_games to Color(0xFF9C7BE0)
    "Development" -> R.drawable.ic_folder_development to Color(0xFF4DB6AC)
    "AI" -> R.drawable.ic_folder_ai to Color(0xFFD08CE8)
    "Health" -> R.drawable.ic_folder_health to Color(0xFFEF5350)
    "Travel" -> R.drawable.ic_folder_travel to Color(0xFF29B6F6)
    "Family" -> R.drawable.ic_folder_family to Color(0xFFE0A030)
    "Books" -> R.drawable.ic_folder_books to Color(0xFFC0884F)
    "Others" -> R.drawable.ic_folder_others to Color(0xFF9AA3A4)
    CalendarFiles.FOLDER -> R.drawable.ic_folder_calendar to Color(0xFFEC407A)
    else -> null
}

/** Files tools › System folders: every folder's emblem, tap to choose or leave it, OK to keep the choice (asked 16 Oct). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable private fun SystemFoldersSheet(dismiss: () -> Unit, save: (Set<String>) -> Unit) {
    var chosen by remember { mutableStateOf(DriveRules.chosenFolders) }
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = islandColor(), contentColor = islandContentColor()) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.files_system_folders), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.files_system_folders_body), style = MaterialTheme.typography.bodyMedium)
            androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DriveRules.OPTIONAL_FOLDERS.forEach { name ->
                    val (icon, tint) = systemFolderLook(name) ?: (R.drawable.ic_folder to Color.Gray)
                    val on = name in chosen
                    Surface(color = if (on) tint.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.06f), shape = MaterialTheme.shapes.large,
                        border = androidx.compose.foundation.BorderStroke(if (on) 2.dp else 1.dp, if (on) tint else Color.White.copy(alpha = 0.15f)),
                        modifier = Modifier.width(96.dp).clickable { chosen = if (on) chosen - name else chosen + name }) {
                        Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box {
                                Icon(painterResource(icon), null, tint = tint, modifier = Modifier.size(44.dp))
                                if (on) Icon(painterResource(R.drawable.ic_check), stringResource(R.string.common_chosen), tint = Color.White,
                                    modifier = Modifier.align(Alignment.TopEnd).size(18.dp).background(tint, CircleShape).padding(2.dp))
                            }
                            Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel), color = islandContentColor(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                TextButton(onClick = { save(chosen) }) { Text(stringResource(R.string.common_ok), color = islandContentColor(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable private fun DriveBottomBar(pane: DrivePane, home: () -> Unit, favorites: () -> Unit, files: () -> Unit, new: () -> Unit, expand: () -> Unit, visible: Boolean, modifier: Modifier = Modifier) {
    IslandBottomBar(expand, visible, modifier) {
        IslandNavigationItem(stringResource(R.string.app_home), pane == DrivePane.Home, R.drawable.ic_home, home)
        IslandNavigationItem(stringResource(R.string.files_favorites), pane == DrivePane.Favorites, if (pane == DrivePane.Favorites) R.drawable.ic_favorite else R.drawable.ic_favorite_border, favorites)
        IslandNavigationItem(stringResource(R.string.app_files), pane == DrivePane.Files, R.drawable.ic_folder, files)
        IslandNavigationItem(stringResource(R.string.common_new), false, R.drawable.ic_add, new)
    }
}

@Composable internal fun IslandBottomBar(expand: () -> Unit, visible: Boolean, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val dragThreshold = with(LocalDensity.current) { 24.dp.toPx() }
    IslandVisibility(visible) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge, color = islandColor(), contentColor = islandContentColor(),
            modifier = modifier.pointerInput(expand) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onVerticalDrag = { _, amount -> dragged += amount },
                    onDragEnd = { if (dragged < -dragThreshold) expand() },
                )
            },
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.width(48.dp).height(18.dp).clickable(onClick = expand), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(28.dp).height(3.dp).background(islandContentColor().copy(alpha = 0.65f), CircleShape))
                }
                Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    content()
                }
            }
        }
    }
}

/** Selection is a neutral state, not a category: a picked item goes pale, it does not change colour. */
@Composable private fun driveSelectionColor(): Color = Color(0xFFE3E4E6)

@Composable private fun driveSelectionContentColor(): Color = Color(0xFF1B1D1F)

@Composable internal fun IslandVisibility(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(90)) + slideInVertically(tween(120)) { -it / 3 },
        exit = fadeOut(tween(80)),
    ) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DriveToolsSheet(
    sort: DriveSort,
    setSort: (DriveSort) -> Unit,
    openTags: () -> Unit,
    openTrash: () -> Unit,
    openSystemFolders: () -> Unit,
    usage: DriveSpaceUsage?,
    dismiss: () -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = islandColor(), contentColor = islandContentColor()) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.files_tools), style = MaterialTheme.typography.titleLarge) }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DriveActionTile(R.drawable.ic_tag, stringResource(R.string.files_tags)) { openTags() }
                DriveActionTile(R.drawable.ic_delete, stringResource(R.string.files_trash)) { openTrash() }
                DriveActionTile(R.drawable.ic_folder_others, stringResource(R.string.files_system_folders)) { openSystemFolders() }
            } }
            item { Text(stringResource(R.string.files_sort), style = MaterialTheme.typography.titleMedium) }
            item { DriveWideAction(R.drawable.ic_sort_name, stringResource(R.string.common_name).let { if (sort == DriveSort.Name) stringResource(R.string.files_checked, it) else it }) { setSort(DriveSort.Name); dismiss() } }
            item { DriveWideAction(R.drawable.ic_sort_time, stringResource(R.string.files_date_modified).let { if (sort == DriveSort.Modified) stringResource(R.string.files_checked, it) else it }) { setSort(DriveSort.Modified); dismiss() } }
            item { Text(stringResource(R.string.files_space), style = MaterialTheme.typography.titleMedium) }
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(156.dp)) {
                        val stroke = 16.dp.toPx()
                        val arcSize = Size(size.width - stroke, size.height - stroke)
                        val topLeft = Offset(stroke / 2f, stroke / 2f)
                        val total = usage?.totalBytes ?: 0L
                        if (total == 0L) drawArc(Color.Gray.copy(alpha = 0.55f), 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
                        else {
                            var start = -90f
                            usage?.bytesByType.orEmpty().forEach { (type, bytes) ->
                                val sweep = bytes.toFloat() / total.toFloat() * 360f
                                drawArc(driveSpaceColor(type), start, sweep, false, topLeft, arcSize, style = Stroke(stroke))
                                start += sweep
                            }
                        }
                    }
                    Text(if (usage == null) "…" else Formatter.formatShortFileSize(context, usage.totalBytes), style = MaterialTheme.typography.titleMedium)
                }
            }
            usage?.bytesByType?.entries?.sortedByDescending { it.value }?.forEach { (type, bytes) ->
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = driveSpaceColor(type), shape = CircleShape, modifier = Modifier.size(12.dp)) {}
                            Text(spaceTypeLabel(type))
                        }
                        Text(Formatter.formatShortFileSize(context, bytes))
                    }
                }
            }
            item { Box(Modifier.height(28.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DriveTagsSheet(
    tags: List<String>,
    selectedTag: String?,
    chooseTag: (String?) -> Unit,
    createTag: (String) -> String?,
    dismiss: () -> Unit,
) {
    var newTag by remember { mutableStateOf("") }
    var tagError by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = islandColor(), contentColor = islandContentColor()) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.files_tags), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.files_tags_body), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(newTag, { newTag = it; tagError = null }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.files_create_a_tag)) }, singleLine = true, isError = tagError != null, supportingText = tagError?.let { { Text(it) } })
            DriveWideAction(R.drawable.ic_tag, stringResource(R.string.files_create_tag)) { tagError = createTag(newTag); if (tagError == null) newTag = "" }
            if (selectedTag != null) DriveWideAction(R.drawable.ic_tag, stringResource(R.string.files_clear_tag)) { chooseTag(null) }
            if (tags.isEmpty()) Text(stringResource(R.string.files_tags_none), style = MaterialTheme.typography.bodyMedium)
            else LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tags, key = { it }) { tag ->
                    Surface(
                        color = if (selectedTag == tag) driveNavigationSelectedColor() else islandColor(),
                        contentColor = if (selectedTag == tag) driveNavigationSelectedContentColor() else islandContentColor(),
                        shape = CircleShape,
                        modifier = Modifier.clickable { chooseTag(tag) },
                    ) { Text("#$tag", modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun EmptyTrashSheet(dismiss: () -> Unit, emptyTrash: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = islandColor(), contentColor = islandContentColor()) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.files_empty_trash_ask), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.files_empty_trash_body), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            DriveWideAction(R.drawable.ic_delete, stringResource(R.string.files_empty_trash_now), emptyTrash)
        }
    }
}

/** [fileTypeGroup]'s kind (also the key of its colour) in the user's words. */
@Composable private fun spaceTypeLabel(type: String): String = when (type) {
    "Documents" -> stringResource(R.string.files_kind_documents)
    "Spreadsheets" -> stringResource(R.string.files_kind_spreadsheets)
    "Text" -> stringResource(R.string.files_kind_text)
    "Drawings" -> stringResource(R.string.files_kind_drawings)
    "Pictures" -> stringResource(R.string.files_kind_pictures)
    "Videos" -> stringResource(R.string.files_kind_videos)
    "Other" -> stringResource(R.string.common_other)
    else -> type // PDF
}

private fun driveSpaceColor(type: String): Color = when (type) {
    "Documents" -> Color(0xFF4F86E8)
    "Spreadsheets" -> Color(0xFF55A96B)
    "PDF" -> Color(0xFFE3685F)
    "Text" -> Color(0xFF9AA3A4)
    "Drawings" -> Color(0xFFAD68CF)
    "Pictures" -> Color(0xFF88BA6A)
    "Videos" -> Color(0xFFE887A8)
    else -> Color(0xFFB0B8B8)
}

@Composable internal fun IslandNavigationItem(label: String, selected: Boolean, icon: Int, click: () -> Unit) = Surface(
    color = if (selected) driveNavigationSelectedColor() else Color.Transparent,
    contentColor = if (selected) driveNavigationSelectedContentColor() else islandContentColor(),
    shape = MaterialTheme.shapes.extraLarge,
    modifier = Modifier.heightIn(min = 48.dp).clickable(onClick = click).semantics { this.selected = selected },
) {
    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(22.dp))
        if (selected) Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable internal fun driveNavigationSelectedColor(): Color = Color.White.copy(alpha = 0.72f)
@Composable internal fun driveNavigationSelectedContentColor(): Color = Color.Black

@Composable private fun fileTypeLabel(name: String): String = name.substringAfterLast('.', "").takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT)?.let { stringResource(R.string.files_type_file, it) } ?: stringResource(R.string.files_file)
@Composable private fun driveItemColor(item: DriveItem, folderColor: DriveFolderColor?): Color {
    if (item.isDirectory) return folderColor?.let(::driveFolderAccent) ?: MaterialTheme.colorScheme.onSurfaceVariant
    return driveExtensionColor(item.file.extension)
}

/** A kind of file's colour, the same in the list and wherever its kind is named (New's choices). */
@Composable internal fun driveExtensionColor(extension: String): Color {
    return when (extension.lowercase(Locale.ROOT)) {
        "doc", "docx", "odt", "rtf" -> Color(0xFF4F86E8)
        "xls", "xlsx", "xlsm", "ods", "csv" -> Color(0xFF55A96B)
        "ppt", "pptx", "ppsx", "odp" -> Color(0xFFE0784F) // PowerPoint's orange, light enough for the dark list
        "pdf" -> PDF_RED
        "txt", "md" -> Color(0xFF9AA3A4)
        "excalidraw" -> Color(0xFFAD68CF)
        "jpg", "jpeg", "png", "webp", "gif", "heic", "heif" -> Color(0xFF88BA6A)
        "mp4", "mov", "mkv", "avi", "webm" -> Color(0xFFE887A8)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
private fun driveFolderAccent(color: DriveFolderColor): Color = when (color) {
    DriveFolderColor.Blue -> Color(0xFF4F86E8)
    DriveFolderColor.Green -> Color(0xFF55A96B)
    DriveFolderColor.Yellow -> Color(0xFFF2B544)
    DriveFolderColor.Red -> Color(0xFFE3685F)
    DriveFolderColor.Purple -> Color(0xFFAD68CF)
}
private fun formatOpenedTime(time: Long): String = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))
/**
 * A grid that keeps its phone-sized cells on a wider screen (SYNC_PLAN.md D2). Pass what the phone shows and
 * the cell keeps roughly that size everywhere: the tablet gets more of them, a narrow phone never gets fewer.
 */
@Composable internal fun gridColumns(phoneColumns: Int): GridCells =
    GridCells.Fixed(TimelineRules.columns(phoneColumns, PHONE_WIDTH_DP / phoneColumns, LocalConfiguration.current.screenWidthDp))

/** The width the phone layouts were drawn against, and the only thing the cell sizes are relative to. */
private const val PHONE_WIDTH_DP = 406

@Composable internal fun islandColor(): Color = Color.Black.copy(alpha = 0.72f)
@Composable internal fun islandContentColor(): Color = Color.White

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoTab(
    home: () -> Unit,
    pane: PhotosPane,
    setPane: (PhotosPane) -> Unit,
    filter: PhotoFilter,
    setFilter: (PhotoFilter) -> Unit,
    permission: String,
    state: ListState,
    metadataStore: PhotoMetadataStore,
    metadataVersion: Int,
    grant: () -> Unit,
    refresh: () -> Unit,
    moveToTrash: (Set<Uri>) -> Unit,
    setFavorite: (Set<Uri>, Boolean) -> String?,
    addToCollection: (Long, Set<Uri>) -> String?,
    removeFromCollection: (Long, Set<Uri>) -> String?,
    createCollection: (String) -> Result<PhotoCollection>,
    deleteCollection: (Long) -> String?,
    folders: List<DeviceFolder> = emptyList(),
    moveToFolder: (Set<Uri>, String) -> Unit = { _, _ -> },
    setFolderIncluded: (String, Boolean) -> Unit = { _, _ -> },
    externalMediaId: String? = null,
    externalMissing: () -> Unit = {},
    closeExternal: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val reviewScope = rememberCoroutineScope()
    val vault = remember(context) { SecureVault(context.applicationContext) }
    var vaultVersion by remember { mutableStateOf(0) }
    var vaultUnlocked by remember { mutableStateOf(false) }
    var stagedHide by remember { mutableStateOf<Pair<List<VaultItem>, List<Uri>>?>(null) }
    var openMapAfterPermission by remember { mutableStateOf(false) }
    var hasMediaLocationAccess by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    var locationVersion by remember { mutableStateOf(0) }
    var mapFocusPhotoKey by remember { mutableStateOf<String?>(null) }
    var hideWarningOpen by remember { mutableStateOf(false) }
    var toolsOpen by remember { mutableStateOf(false) }
    var collectionToolsOpen by remember { mutableStateOf(false) }
    var scale by remember { mutableStateOf(TimelineScale.Month) }
    var viewerUri by remember { mutableStateOf<Uri?>(null) }
    // A MediaStore id to open in the viewer once it is loaded: a photo opened from another app, or a just-saved edit.
    var pendingViewerId by remember { mutableStateOf(externalMediaId) }
    var editingEntry by remember { mutableStateOf<Entry?>(null) }
    var editingHide by remember { mutableStateOf<PhotoHide?>(null) } // opened from the viewer's Hide eyes / Hide text
    // Opened from another app: closing the viewer returns there instead of to the Gallery.
    fun closeViewer() { if (closeExternal != null) closeExternal() else viewerUri = null }
    var viewingFaceGroup by remember { mutableStateOf<FaceGroup?>(null) }
    var choosingCoverFor by remember { mutableStateOf<FaceGroup?>(null) }
    var renamingGroup by remember { mutableStateOf<FaceGroup?>(null) }
    var peopleHistoryOpen by remember { mutableStateOf(false) }
    var selectedUris by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var returnToCollections by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var recentTags by remember { mutableStateOf(emptyList<String>()) }
    var collectionSheetFor by remember { mutableStateOf<Set<Uri>?>(null) }
    // Photos to move to another folder, and the folder album they are being taken out of (null: a plain move).
    var moveSheetFor by remember { mutableStateOf<Pair<Set<Uri>, String?>?>(null) }
    var newCollectionOpen by remember { mutableStateOf(false) }
    var selectedCollection by remember { mutableStateOf<PhotoCollection?>(null) }
    var collectionPendingDelete by remember { mutableStateOf<PhotoCollection?>(null) }
    var analysisConsentFor by remember { mutableStateOf<PhotoFilter?>(null) }
    val analysis by PhotoAnalysisService.state.collectAsState()
    val analysisRunning = analysis != null
    val analysisPaused = analysis?.paused == true
    val analysisDone = analysis?.done ?: 0
    val analysisTotal = analysis?.total ?: 0
    var analysisVersion by remember { mutableStateOf(0) }
    val analysisError by PhotoAnalysisService.lastError.collectAsState()
    // The reading happens in a service now, so what it found is only on screen once it has stopped.
    LaunchedEffect(analysisRunning) { if (!analysisRunning) analysisVersion++ }
    var actionError by remember { mutableStateOf<String?>(null) }
    var emptyTrashConfirm by remember { mutableStateOf(false) }
    // Android owns its trash: restoring and deleting for good are both its own requests, with its own dialog.
    val trashRequest = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        selectedUris = emptySet()
        analysisVersion++
    }
    fun restoreFromTrash(uris: Set<Uri>) {
        if (uris.isEmpty()) return
        runCatching { MediaStore.createTrashRequest(context.contentResolver, uris.toList(), false) }
            .onSuccess { trashRequest.launch(IntentSenderRequest.Builder(it.intentSender).build()) }
            .onFailure { actionError = context.getString(R.string.photos_cannot_restore, it.message ?: context.getString(R.string.photos_request_failed)) }
    }
    fun emptyPhotoTrash(all: List<Entry>) {
        val uris = all.mapNotNull(Entry::contentUri)
        if (uris.isEmpty()) return
        runCatching { MediaStore.createDeleteRequest(context.contentResolver, uris) }
            .onSuccess { trashRequest.launch(IntentSenderRequest.Builder(it.intentSender).build()) }
            .onFailure { actionError = context.getString(R.string.photos_cannot_empty_trash, it.message ?: context.getString(R.string.photos_request_failed)) }
    }
    val deleteHiddenOriginals = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val staged = stagedHide
        stagedHide = null
        if (staged != null && result.resultCode != Activity.RESULT_OK) Thread {
            vault.remove(staged.first)
            Handler(Looper.getMainLooper()).post { vaultVersion++; actionError = context.getString(R.string.photos_hide_cancelled) }
        }.start()
        if (result.resultCode == Activity.RESULT_OK) {
            Thread {
                staged?.first?.let { metadataStore.forgetPhotos(it.map(VaultItem::photoKey)) }
                Handler(Looper.getMainLooper()).post {
                    vaultVersion++
                    analysisVersion++
                    selectedUris = emptySet()
                    refresh()
                }
            }.start()
        }
    }
    val requestMediaLocation = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasMediaLocationAccess = context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasMediaLocationAccess && openMapAfterPermission) {
            setFilter(PhotoFilter.Map)
            returnToCollections = true
            setPane(PhotosPane.Timeline)
        } else if (!hasMediaLocationAccess) actionError = context.getString(R.string.photos_map_needs_locations)
        openMapAfterPermission = false
    }
    fun requestPhotoLocations() = requestMediaLocation.launch(when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        Build.VERSION.SDK_INT == 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
    })
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, filter) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) {
            vaultUnlocked = false
            if (filter == PhotoFilter.Hidden) {
                viewerUri = null
                selectedUris = emptySet()
            }
        } }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val timelineState = rememberLazyGridState()
    val allEntries = (state as? ListState.Items)?.entries.orEmpty()
    val vaultItems = remember(vaultVersion) { vault.items() }
    LaunchedEffect(vaultItems) {
        withContext(Dispatchers.IO) { metadataStore.forgetPhotos(vaultItems.map(VaultItem::photoKey)) }
    }
    val vaultEntries = remember(vaultItems, vaultUnlocked) { if (!vaultUnlocked) emptyList() else vaultItems.map { item ->
        Entry(
            name = item.displayName,
            detail = "Hidden · ${item.sizeBytes} bytes",
            relativePath = item.relativePath,
            contentUri = vault.contentUri(item),
            takenMillis = item.takenMillis,
            sizeBytes = item.sizeBytes,
            photoKey = "vault:${item.id}",
            isVideo = item.isVideo,
        )
    } }
    val metadataRevision = metadataVersion + analysisVersion
    var hiddenAlbums by remember { mutableStateOf(metadataStore.albumsHiddenFromGallery()) }
    // What the photo database says of these photos, read together off the screen's thread (25 Oct: ten queries over every
    // photo's key ran on it as the timeline arrived); the timeline waits for it, so nothing hidden shows for a moment.
    val meta by produceState<PhotoMeta?>(null, allEntries, metadataRevision, hiddenAlbums) {
        value = withContext(Dispatchers.IO) { PhotoMeta.read(context, metadataStore, allEntries, hiddenAlbums) }
    }
    OpenTiming("Photos", state is ListState.Items && meta != null)
    val metadata = meta?.states.orEmpty()
    val collections = meta?.collections.orEmpty()
    val collectionPreviews = meta?.previews.orEmpty()
    val documentKeys = meta?.documentKeys.orEmpty()
    val peopleKeys = meta?.peopleKeys.orEmpty()
    val labelsByPhoto = meta?.labels.orEmpty()
    val peopleNamesByPhoto = meta?.peopleNames.orEmpty()
    val locationsByPhoto by produceState(emptyMap<String, PhotoLocation?>(), allEntries, locationVersion) {
        value = withContext(Dispatchers.IO) { metadataStore.locations(allEntries.map(Entry::photoKey)) }
    }
    val faceGroups = meta?.faceGroups.orEmpty()
    val reviewKeys = meta?.reviewKeys.orEmpty()
    val pendingReviews = remember(metadataRevision, filter) { if (filter == PhotoFilter.Review) metadataStore.pendingReviews() else emptyList() }
    var hideScreenshots by remember { mutableStateOf(metadataStore.hidesScreenshotsFromGallery()) }
    var motionMode by remember { mutableStateOf(metadataStore.motionPhotos()) }
    var motionAutoplay by remember { mutableStateOf(metadataStore.motionPlays()) }
    var hideDocuments by remember { mutableStateOf(metadataStore.hidesDocumentsFromGallery()) }
    val hiddenAlbumKeys = meta?.hiddenAlbumKeys.orEmpty()
    var hidePeopleFromCollections by remember { mutableStateOf(metadataStore.hidesPeopleFromCollections()) }
    var hideDocumentsFromCollections by remember { mutableStateOf(metadataStore.hidesDocumentsFromCollections()) }
    val activeCollection = (filter as? PhotoFilter.Collection)?.let { chosen -> collections.firstOrNull { it.id == chosen.id } }
    // A collection that is a folder is not a grouping: taking a photo out of it moves the file (asked 2026-09-25).
    val folderAlbum = activeCollection?.name?.takeIf { name -> folders.any { it.included == true && it.name.equals(name, ignoreCase = true) } }
    val moveDestinations = remember(allEntries, folders) {
        (allEntries + folders.flatMap { it.entries }).mapNotNull { it.relativePath?.trim('/')?.takeIf(String::isNotEmpty) }
            .plus("DCIM/Camera").groupingBy { it }.eachCount().entries
            // One row per album: DCIM/Camera/Camera (an old SyncThing copy) is not a second "Camera", and Movies/Viber
            // not a second "Viber" (a photo cannot go under Movies). The shallowest folder wins, Pictures over Movies.
            .groupBy { FolderRules.albumName(it.key)?.lowercase() ?: it.key }.values
            .map { same -> same.minWith(compareBy({ it.key.count { c -> c == '/' } }, { it.key.startsWith("Movies/", ignoreCase = true) })).key to same.sumOf { it.value } }
            .sortedByDescending { it.second }
    }
    @Composable fun MoveSheet() {
        val (targets, leaving) = moveSheetFor ?: return
        FolderMoveSheet(
            count = targets.size, leaving = leaving,
            destinations = moveDestinations.filter { (dest, _) -> leaving == null || !FolderRules.albumName(dest).equals(leaving, ignoreCase = true) },
            onDismiss = { moveSheetFor = null },
            onPick = { dest -> moveSheetFor = null; selectedUris = emptySet(); moveToFolder(targets, dest) },
        )
    }
    val collectionKeys = remember(filter, metadataRevision) {
        (filter as? PhotoFilter.Collection)?.let { metadataStore.collectionKeys(it.id) }.orEmpty()
    }
    // Trash and Hidden are their own lists: neither is in the ordinary MediaStore listing the timeline reads.
    val trashedEntries = remember(metadataRevision, filter) { if (filter == PhotoFilter.Trash) runCatching { listTrashedPhotos(context) }.getOrDefault(emptyList()) else emptyList() }
    val entries = if (filter == PhotoFilter.Hidden) vaultEntries else if (filter == PhotoFilter.Trash) trashedEntries else allEntries.filter { entry -> when (filter) {
        PhotoFilter.Timeline -> PhotoMetadataRules.visibleInGallery(entry.isScreenshot(), entry.photoKey in documentKeys, hideScreenshots, hideDocuments, entry.photoKey in hiddenAlbumKeys)
        PhotoFilter.Favorites -> metadata[entry.photoKey].orDefault().favorite
        PhotoFilter.People -> entry.photoKey in peopleKeys
        PhotoFilter.Documents -> entry.photoKey in documentKeys
        PhotoFilter.Screenshots -> entry.isScreenshot()
        PhotoFilter.Videos -> entry.isVideo
        PhotoFilter.Review -> entry.photoKey in reviewKeys
        PhotoFilter.Map -> true
        PhotoFilter.Hidden, PhotoFilter.Trash -> false
        is PhotoFilter.Collection -> entry.photoKey in collectionKeys
    } }.filter { entry ->
        searchQuery.isBlank() || entry.name.contains(searchQuery, ignoreCase = true) || entry.relativePath.orEmpty().contains(searchQuery, ignoreCase = true) ||
            PhotoSearchRules.matches(searchQuery, labelsByPhoto[entry.photoKey].orEmpty() + peopleNamesByPhoto[entry.photoKey].orEmpty() + listOfNotNull(locationsByPhoto[entry.photoKey]?.placeName))
    }
    LaunchedEffect(state, pendingViewerId, entries.size) {
        val id = pendingViewerId ?: return@LaunchedEffect
        if (state !is ListState.Items) return@LaunchedEffect
        val match = entries.firstOrNull { it.contentUri?.lastPathSegment == id }
        pendingViewerId = null
        if (match != null) viewerUri = match.contentUri
        else if (id == externalMediaId) externalMissing()
    }
    val suggestedTags = remember(entries, labelsByPhoto, recentTags) {
        (recentTags + PhotoSearchRules.frequentTags(entries.flatMap { labelsByPhoto[it.photoKey].orEmpty() })).distinct().take(5)
    }
    /**
     * `everything` re-reads photos that were analysed before, which is what a rescan is for: the thresholds or
     * the rules have changed and the old answers were reached under the old ones.
     */
    fun scanUnclassified(everything: Boolean = false, faces: Boolean = true) =
        PhotoAnalysisService.start(context, everything, faces)
    fun performHide(targets: Set<Uri>) {
        val selected = entries.filter { it.contentUri in targets && it.contentUri != null }
        if (selected.isEmpty()) return
        authenticateVault(context as Activity, success = {
            vaultUnlocked = true
            Thread {
                val result = runCatching { vault.stage(selected.map { entry -> VaultImport(
                    photoKey = entry.photoKey,
                    uri = requireNotNull(entry.contentUri),
                    displayName = entry.name,
                    mimeType = context.contentResolver.getType(entry.contentUri) ?: if (entry.isVideo) "video/*" else "image/*",
                    relativePath = entry.relativePath.orEmpty(),
                    takenMillis = entry.takenMillis,
                    isVideo = entry.isVideo,
                ) }) }
                Handler(Looper.getMainLooper()).post {
                    result.onSuccess { hidden ->
                        val uris = selected.mapNotNull(Entry::contentUri)
                        stagedHide = hidden to uris
                        runCatching { MediaStore.createDeleteRequest(context.contentResolver, uris) }
                            .onSuccess { deleteHiddenOriginals.launch(IntentSenderRequest.Builder(it.intentSender).build()) }
                            .onFailure {
                                stagedHide = null
                                Thread { vault.remove(hidden) }.start()
                                actionError = context.getString(R.string.photos_error_remove_originals)
                            }
                    }.onFailure { actionError = it.message ?: context.getString(R.string.photos_error_private_copies) }
                }
            }.start()
        }, failure = { actionError = it })
    }
    fun hideSelected(targets: Set<Uri> = selectedUris) {
        selectedUris = targets
        if (vault.warningAccepted()) performHide(targets) else hideWarningOpen = true
    }
    fun restoreHidden(targets: Set<Uri> = selectedUris) {
        val selected = vaultItems.filter { vault.contentUri(it) in targets }
        if (selected.isEmpty()) return
        Thread {
            val result = runCatching { vault.restore(selected) }
            Handler(Looper.getMainLooper()).post {
                result.onSuccess { vaultVersion++; selectedUris = emptySet(); refresh() }
                    .onFailure {
                        vaultVersion++
                        selectedUris = emptySet()
                        refresh()
                        actionError = it.message ?: context.getString(R.string.photos_error_restore_hidden)
                    }
            }
        }.start()
    }
    fun openCollection(chosen: PhotoFilter) {
        if (chosen == PhotoFilter.Hidden) {
            authenticateVault(context as Activity, success = {
                vaultUnlocked = true
                setFilter(chosen)
                returnToCollections = true
                setPane(PhotosPane.Timeline)
            }, failure = { actionError = it })
        } else if (chosen == PhotoFilter.Map) {
            mapFocusPhotoKey = null
            searchOpen = false
            toolsOpen = false
            collectionToolsOpen = false
            setFilter(chosen)
            returnToCollections = true
            setPane(PhotosPane.Timeline)
        } else if ((chosen == PhotoFilter.People || chosen == PhotoFilter.Documents) && !metadataStore.peopleAnalysisEnabled()) {
            analysisConsentFor = chosen
        } else {
            if (chosen == PhotoFilter.People || chosen == PhotoFilter.Documents) scanUnclassified()
            setFilter(chosen)
            returnToCollections = true
            setPane(PhotosPane.Timeline)
        }
    }
    fun navigateBack() {
        when {
            selectedCollection != null -> selectedCollection = null
            viewingFaceGroup != null -> viewingFaceGroup = null
            viewerUri != null -> closeViewer()
            selectedUris.isNotEmpty() -> selectedUris = emptySet()
            searchOpen -> searchOpen = false
            toolsOpen -> toolsOpen = false
            collectionToolsOpen -> collectionToolsOpen = false
            pane == PhotosPane.Timeline && filter != PhotoFilter.Timeline && returnToCollections -> {
                setFilter(PhotoFilter.Timeline)
                returnToCollections = false
                setPane(PhotosPane.Collections)
            }
            else -> home()
        }
    }
    BackHandler(onBack = ::navigateBack)
    choosingCoverFor?.let { group ->
        ChooseCoverSheet(
            group = group,
            faces = remember(group.id, analysisVersion) { metadataStore.faceGroupFaces(group.id) },
            entriesByKey = allEntries.associateBy(Entry::photoKey),
            dismiss = { choosingCoverFor = null },
            choose = { face ->
                metadataStore.setFaceGroupCover(group.id, face?.uuid)
                choosingCoverFor = null
                analysisVersion++
            },
        )
    }
    renamingGroup?.let { group ->
        var name by remember(group.id) { mutableStateOf(if (isGeneratedPersonName(group.name)) "" else group.name) }
        AlertDialog(
            onDismissRequest = { renamingGroup = null },
            title = { Text(stringResource(R.string.photos_name_person)) },
            text = { OutlinedTextField(name, { name = it }, placeholder = { Text(personName(group.name)) }, singleLine = true) },
            confirmButton = { Button(onClick = {
                metadataStore.renameFaceGroup(group.id, name); renamingGroup = null; analysisVersion++
            }, enabled = name.isNotBlank(), colors = neutralButtonColors()) { Text(stringResource(R.string.common_save), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            dismissButton = { Button(onClick = { renamingGroup = null }, colors = neutralButtonColors()) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        )
    }
    if (peopleHistoryOpen) MergeHistorySheet(
        title = stringResource(R.string.photos_history),
        merges = remember(analysisVersion) { metadataStore.mergeHistory() },
        entriesByKey = allEntries.associateBy(Entry::photoKey),
        dismiss = { peopleHistoryOpen = false },
        restore = { merge -> metadataStore.restoreMerge(merge); analysisVersion++ },
        forgotten = remember(allEntries, analysisVersion) { metadataStore.faceGroups(allEntries.mapTo(HashSet(), Entry::photoKey), forgotten = true) },
        unforget = { group -> metadataStore.setFaceGroupHidden(group.id, false); analysisVersion++ },
    )
    viewingFaceGroup?.let { group ->
        PersonGroupScreen(
            group = group,
            entries = allEntries.filter { it.photoKey in metadataStore.faceGroupKeys(group.id) },
            allGroups = faceGroups,
            entriesByKey = allEntries.associateBy(Entry::photoKey),
            back = { viewingFaceGroup = null },
            openPhoto = { entry ->
                viewingFaceGroup = null
                viewerUri = entry.contentUri
            },
            rename = { name ->
                metadataStore.renameFaceGroup(group.id, name)
                viewingFaceGroup = group.copy(name = PhotoMetadataRules.collectionName(name))
                analysisVersion++
            },
            merge = { source -> metadataStore.mergeFaceGroups(source.id, group.id).also { analysisVersion++ } },
            undoMerge = { undo -> metadataStore.undoFaceMerge(undo); analysisVersion++ },
            history = { metadataStore.mergeHistory(group.id) },
            restore = { merge -> metadataStore.restoreMerge(merge); analysisVersion++ },
            detach = { keys -> metadataStore.detachPhotosFromGroup(group.id, keys).also { if (it) analysisVersion++ } },
        )
        return
    }
    editingEntry?.let { entry ->
        PhotoEditorScreen(entry, close = { editingEntry = null }, saved = { uri, newPhotoKey ->
            // "Save" changed the photo's size and key: its favorite, collections and location follow it.
            if (newPhotoKey != null) metadataStore.rekeyPhoto(entry.photoKey, newPhotoKey)
            editingEntry = null
            pendingViewerId = uri.lastPathSegment
            refresh()
        }, hide = editingHide)
        return
    }
    val openViewerUri = viewerUri
    if (openViewerUri != null) {
        PhotoViewer(entries, openViewerUri, { uri ->
            viewerUri = uri
            allEntries.firstOrNull { it.contentUri == uri }?.let { recentTags = labelsByPhoto[it.photoKey].orEmpty() }
        }, ::closeViewer,
            { entriesForAction, favorite -> setFavorite(entriesForAction.mapNotNullTo(mutableSetOf()) { it.contentUri }, favorite) },
            if (activeCollection == null) { uris -> collectionSheetFor = uris } else null,
            if (filter == PhotoFilter.Hidden) { entry ->
                entry.contentUri?.let { uri ->
                    viewerUri = null
                    restoreHidden(setOf(uri))
                }
            } else null,
            activeCollection?.let { collection -> { entry ->
                entry.contentUri?.let { uri ->
                    if (folderAlbum != null) moveSheetFor = setOf(uri) to folderAlbum
                    else {
                        actionError = removeFromCollection(collection.id, setOf(uri))
                        if (actionError == null) viewerUri = null
                    }
                }
            } },
            metadata,
            labelsByPhoto,
            peopleNamesByPhoto,
            metadataStore,
            hasMediaLocationAccess,
            { openMapAfterPermission = true; requestPhotoLocations() },
            { locationVersion++ },
            { entry ->
                viewerUri = null
                mapFocusPhotoKey = entry.photoKey
                setFilter(PhotoFilter.Map)
                returnToCollections = true
                setPane(PhotosPane.Timeline)
            },
            // Hidden items live in the private vault; an edited copy would publish them, so no editing there.
            if (filter == PhotoFilter.Hidden) null else { entry, hide -> editingEntry = entry; editingHide = hide },
        )
        MoveSheet()
        collectionSheetFor?.let { targets -> CollectionPickerSheet(
            collections = collections,
            previews = collectionPreviews,
            onDismiss = { collectionSheetFor = null },
            onHide = {
                collectionSheetFor = null
                viewerUri = null
                hideSelected(targets)
            },
            onAdd = { collectionId ->
                actionError = addToCollection(collectionId, targets)
                if (actionError == null) collectionSheetFor = null
            },
        )
        }
        return
    }
    LaunchedEffect(entries) {
        val available = entries.mapNotNullTo(mutableSetOf()) { it.contentUri }
        selectedUris = selectedUris.intersect(available)
    }
    val back = { navigateBack() }
    val timelineTitle = when (filter) {
        PhotoFilter.Timeline -> null
        PhotoFilter.Favorites -> stringResource(R.string.files_favorites)
        PhotoFilter.People -> stringResource(R.string.photos_people)
        PhotoFilter.Documents -> stringResource(R.string.files_kind_documents)
        PhotoFilter.Screenshots -> stringResource(R.string.photos_screenshots)
        PhotoFilter.Videos -> stringResource(R.string.files_kind_videos)
        PhotoFilter.Review -> stringResource(R.string.photos_help_organize)
        PhotoFilter.Hidden -> stringResource(R.string.photos_hidden)
        PhotoFilter.Trash -> stringResource(R.string.files_trash)
        PhotoFilter.Map -> stringResource(R.string.photos_map)
        is PhotoFilter.Collection -> collections.firstOrNull { it.id == filter.id }?.name
    }
    val timelineIcon = when (filter) {
        PhotoFilter.Favorites -> R.drawable.ic_favorite_border
        PhotoFilter.Documents -> R.drawable.ic_file
        PhotoFilter.Screenshots -> R.drawable.ic_screenshot
        PhotoFilter.Videos -> R.drawable.ic_video
        PhotoFilter.Review -> R.drawable.ic_tag
        PhotoFilter.Hidden -> R.drawable.ic_lock
        PhotoFilter.Trash -> R.drawable.ic_delete
        PhotoFilter.Map -> R.drawable.ic_map
        is PhotoFilter.Collection, PhotoFilter.People -> R.drawable.ic_collections
        else -> R.drawable.ic_gallery
    }
    Box(Modifier.fillMaxSize()) {
        val mapCollection = pane == PhotosPane.Timeline && filter == PhotoFilter.Map
        when (pane) {
            PhotosPane.Timeline -> when {
                filter == PhotoFilter.Review -> ReviewPage(pendingReviews, folders.filter { it.included == null }, setFolderIncluded, allEntries.associateBy(Entry::photoKey), metadataStore::faceGroup, back) { review, answer ->
                    // null is Skip. Answers travel on the next sync; once the last card is gone, that sync is now.
                    if (answer == null) metadataStore.skipReview(review) else metadataStore.resolveReview(review, answer)
                    analysisVersion++
                    if (metadataStore.nextReview() == null) reviewScope.launch {
                        withContext(Dispatchers.IO) { SyncService.syncInBackground(context, gap = 0) }
                    }
                }
                filter == PhotoFilter.People -> PeopleGroups(
                    faceGroups, allEntries.associateBy(Entry::photoKey),
                    open = { viewingFaceGroup = it },
                    chooseFace = { choosingCoverFor = it },
                    rename = { renamingGroup = it },
                    forget = { metadataStore.setFaceGroupHidden(it.id, true); analysisVersion++ },
                    history = { peopleHistoryOpen = true },
                    back = back,
                )
                filter == PhotoFilter.Map -> PhotoMapScreen(
                    entries = allEntries,
                    metadataStore = metadataStore,
                    hasLocationAccess = hasMediaLocationAccess,
                    requestLocationAccess = { openMapAfterPermission = true; requestPhotoLocations() },
                    locationsUpdated = { locationVersion++ },
                    focusPhotoKey = mapFocusPhotoKey,
                    back = back,
                    openPhoto = { viewerUri = it.contentUri },
                )
                filter == PhotoFilter.Hidden && !vaultUnlocked -> LockedVaultScreen(back) {
                    authenticateVault(context as Activity, success = { vaultUnlocked = true }, failure = { actionError = it })
                }
                else -> PhotoTimeline(
                if (state is ListState.Items) (if (meta == null) ListState.Loading else ListState.Items(entries)) else state,
                scale, { scale = it }, timelineState, selectedUris,
                { entry -> entry.contentUri?.let { selectedUris = selectedUris + it } },
                { entry -> entry.contentUri?.let { uri -> selectedUris = if (uri in selectedUris) selectedUris - uri else selectedUris + uri } },
                { entry ->
                    recentTags = labelsByPhoto[entry.photoKey].orEmpty()
                    viewerUri = entry.contentUri
                },
                refresh,
                title = timelineTitle,
                icon = timelineIcon,
                back = back,
                showTimelineIsland = !searchOpen,
                // With nothing selected the one action Trash offers belongs in the header, as it does in Files.
                emptyTrash = if (filter == PhotoFilter.Trash && selectedUris.isEmpty()) ({ emptyTrashConfirm = true }) else null,
                emptyMessage = when (filter) {
                    PhotoFilter.Documents -> stringResource(R.string.photos_empty_documents)
                    PhotoFilter.Videos -> stringResource(R.string.photos_empty_videos)
                    PhotoFilter.Review -> stringResource(R.string.photos_empty_review)
                    PhotoFilter.Hidden -> stringResource(R.string.photos_empty_hidden)
                    PhotoFilter.Trash -> stringResource(R.string.photos_empty_trash)
                    else -> activeCollection?.takeIf { it.elsewhere > 0 }
                        ?.let { pluralStringResource(R.plurals.photos_all_elsewhere, it.elsewhere, it.elsewhere) }
                        ?: stringResource(R.string.photos_empty_collection)
                },
            )
            }
            PhotosPane.Collections -> PullToRefreshBox(isRefreshing = state is ListState.Loading, onRefresh = refresh, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().padding(bottom = 76.dp)) {
                    Collections(
                        // A collection with nothing on this device is not shown here — after a Move its photos are on
                        // the computer — and comes back with its first photo (asked 2026-09-25). A new, still empty one
                        // stays, so it can be filled. "Add to collection" still lists them all.
                        allEntries, metadata, collections.filter { it.here > 0 || it.storedCount == 0 }, collectionPreviews, documentKeys, faceGroups.size, reviewKeys, folders.count { it.included == null }, vaultItems.size,
                        !hidePeopleFromCollections, !hideDocumentsFromCollections, analysisRunning, analysisPaused,
                        analysisDone, analysisTotal, PhotoAnalysisService::togglePause, back, { newCollectionOpen = true }, ::openCollection,
                        selectedCollection?.id,
                        { collection -> selectedCollection = if (selectedCollection?.id == collection.id) null else collection },
                    )
                }
            }
        }
        if (pane == PhotosPane.Timeline && !mapCollection && timelineTitle == null && !searchOpen) Surface(
            color = islandColor(),
            contentColor = islandContentColor(),
            shape = CircleShape,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 12.dp, top = 12.dp),
        ) {
            IconButton(onClick = back) {
                Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.photos_back_home))
            }
        }
        if (pane == PhotosPane.Timeline && !mapCollection && searchQuery.isNotBlank() && !searchOpen) Surface(
            color = islandColor(),
            contentColor = islandContentColor(),
            shape = CircleShape,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = 12.dp, top = 12.dp),
        ) {
            IconButton(onClick = { searchQuery = "" }) {
                Icon(
                    painterResource(R.drawable.ic_clear_search),
                    contentDescription = stringResource(R.string.photos_clear_search),
                    tint = Color.Unspecified,
                )
            }
        }
        if (selectedUris.isNotEmpty()) {
            if (filter == PhotoFilter.Hidden) HiddenSelectionActions(
                count = selectedUris.size,
                restore = { restoreHidden() },
                cancel = { selectedUris = emptySet() },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp).fillMaxWidth(),
            ) else if (filter == PhotoFilter.Trash) TrashSelectionActions(
                count = selectedUris.size,
                restore = { restoreFromTrash(selectedUris) },
                emptyTrash = { emptyTrashConfirm = true },
                cancel = { selectedUris = emptySet() },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp).fillMaxWidth(),
            ) else SelectionActions(
                count = selectedUris.size,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
                share = { sharePhotos(context, entries.filter { it.contentUri in selectedUris }) },
                addToCollection = if (activeCollection == null) ({ collectionSheetFor = selectedUris }) else null,
                removeFromCollection = activeCollection?.let { collection -> {
                    if (folderAlbum != null) moveSheetFor = selectedUris to folderAlbum
                    else {
                        actionError = removeFromCollection(collection.id, selectedUris)
                        if (actionError == null) selectedUris = emptySet()
                    }
                } },
                removeLabel = folderAlbum?.let { stringResource(R.string.photos_move_out_of, it) } ?: stringResource(R.string.photos_remove_from_collection),
                moveToFolder = { moveSheetFor = selectedUris to null },
                toggleFavorite = {
                    val allFavorite = entries.filter { it.contentUri in selectedUris }.all { metadata[it.photoKey].orDefault().favorite }
                    actionError = setFavorite(selectedUris, !allFavorite)
                    if (actionError == null) selectedUris = emptySet()
                },
                moveToTrash = { moveToTrash(selectedUris) },
                hide = ::hideSelected,
                cancel = { selectedUris = emptySet() },
            )
        } else {
            AnimatedVisibility(
                visible = !mapCollection,
                enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it },
                exit = fadeOut(tween(100)),
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp).fillMaxWidth(),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                PhotoBottomBar(
                    pane = pane,
                    gallery = { setFilter(PhotoFilter.Timeline); returnToCollections = false; setPane(PhotosPane.Timeline) },
                    collections = { setPane(PhotosPane.Collections) },
                    expand = { if (pane == PhotosPane.Timeline) toolsOpen = true else collectionToolsOpen = true },
                    visible = !toolsOpen && !collectionToolsOpen && !searchOpen,
                )
                SearchButton(visible = !toolsOpen && !collectionToolsOpen && !searchOpen && selectedCollection == null) {
                    setFilter(PhotoFilter.Timeline)
                    returnToCollections = false
                    setPane(PhotosPane.Timeline)
                    searchOpen = true
                    if (metadataStore.peopleAnalysisEnabled()) scanUnclassified() else analysisConsentFor = PhotoFilter.Timeline
                }
                IslandVisibility(!toolsOpen && !collectionToolsOpen && !searchOpen && selectedCollection != null) {
                    Surface(color = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError, shape = CircleShape) {
                        IconButton(onClick = { collectionPendingDelete = selectedCollection }) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.photos_delete_selected_collection))
                        }
                    }
                }
                }
            }
        }
        AnimatedVisibility(
            visible = searchOpen && !mapCollection,
            enter = fadeIn(tween(150)) + slideInVertically(tween(150)) { it / 2 },
            exit = fadeOut(tween(100)),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 12.dp).padding(top = 12.dp).fillMaxWidth(),
        ) {
            PhotoSearchPanel(
                query = searchQuery,
                setQuery = { searchQuery = it },
                clear = { searchQuery = "" },
                dismiss = { searchOpen = false },
                suggestions = suggestedTags,
            )
        }
    }
    if (toolsOpen) GalleryToolsSheet(
        permission = permission,
        scale = scale,
        allowPhotos = grant,
        refresh = refresh,
        selectScale = { scale = it },
        hideScreenshots = hideScreenshots,
        hideDocuments = hideDocuments,
        setHideScreenshots = { hide -> metadataStore.setHidesScreenshotsFromGallery(hide); hideScreenshots = hide },
        setHideDocuments = { hide -> metadataStore.setHidesDocumentsFromGallery(hide); hideDocuments = hide },
        motionMode = motionMode,
        setMotionMode = { mode -> metadataStore.setMotionPhotos(mode); motionMode = mode },
        motionAutoplay = motionAutoplay,
        setMotionAutoplay = { on -> metadataStore.setMotionPlays(on); motionAutoplay = on },
        motionHalves = allEntries.count { it.motion != null },
        removeMotionHalves = { moveToTrash(allEntries.mapNotNullTo(HashSet()) { it.motion }); toolsOpen = false },
        albums = collections,
        hiddenAlbums = hiddenAlbums,
        setAlbumHidden = { album, hide -> album.uuid?.let { metadataStore.setAlbumHiddenFromGallery(it, hide) }; hiddenAlbums = metadataStore.albumsHiddenFromGallery() },
        dismiss = { toolsOpen = false },
    )
    if (collectionToolsOpen) CollectionsToolsSheet(
        hidePeople = hidePeopleFromCollections,
        hideDocuments = hideDocumentsFromCollections,
        setHidePeople = { hide -> metadataStore.setHidesPeopleFromCollections(hide); hidePeopleFromCollections = hide },
        setHideDocuments = { hide -> metadataStore.setHidesDocumentsFromCollections(hide); hideDocumentsFromCollections = hide },
        busy = analysisRunning,
        rescanFaces = {
            collectionToolsOpen = false
            metadataStore.forgetUnnamedFaces() // groups nobody named are guesses, and guesses are what a rescan redoes
            analysisVersion++
            scanUnclassified(everything = true, faces = true)
        },
        rescanDocuments = {
            collectionToolsOpen = false
            scanUnclassified(everything = true, faces = false)
        },
        dismiss = { collectionToolsOpen = false },
    )
    if (emptyTrashConfirm) EmptyTrashSheet(
        dismiss = { emptyTrashConfirm = false },
        emptyTrash = { emptyTrashConfirm = false; emptyPhotoTrash(trashedEntries) },
    )
    analysisConsentFor?.let { requested ->
        AlertDialog(
            onDismissRequest = { analysisConsentFor = null },
            title = { Text(stringResource(R.string.photos_analyze_ask)) },
            text = { Text(stringResource(R.string.photos_analyze_body)) },
            confirmButton = {
                Button(onClick = {
                    metadataStore.setPeopleAnalysisEnabled(true)
                    metadataStore.setDocumentsAnalysisEnabled(true)
                    analysisConsentFor = null
                    scanUnclassified()
                    if (requested != PhotoFilter.Timeline) {
                        setFilter(requested)
                        returnToCollections = true
                        setPane(PhotosPane.Timeline)
                    }
                }) { Text(stringResource(R.string.photos_analyze_now), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            },
            dismissButton = { Button(onClick = { analysisConsentFor = null }) { Text(stringResource(R.string.photos_not_now), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        )
    }
    MoveSheet()
    collectionSheetFor?.let { targets -> CollectionPickerSheet(
        collections = collections,
        previews = collectionPreviews,
        onDismiss = { collectionSheetFor = null },
        onHide = {
            collectionSheetFor = null
            hideSelected(targets)
        },
        onAdd = { collectionId ->
            actionError = addToCollection(collectionId, targets)
            if (actionError == null) {
                selectedUris = emptySet()
                collectionSheetFor = null
            }
        },
    )
    }
    if (newCollectionOpen) NewCollectionSheet(
        dismiss = { newCollectionOpen = false },
        create = { name ->
            createCollection(name).onSuccess { newCollectionOpen = false }
        },
    )
    collectionPendingDelete?.let { collection -> AlertDialog(
        onDismissRequest = { collectionPendingDelete = null },
        title = { Text(stringResource(R.string.photos_delete_collection_ask, collection.name)) },
        text = { Text(stringResource(R.string.photos_delete_collection_body)) },
        confirmButton = { Button(onClick = {
            actionError = deleteCollection(collection.id)
            if (actionError == null) selectedCollection = null
            collectionPendingDelete = null
        }, colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        )) {
            Icon(painterResource(R.drawable.ic_delete), contentDescription = null)
            Text(stringResource(R.string.photos_delete_collection), modifier = Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        } },
        dismissButton = { Button(onClick = { collectionPendingDelete = null }, colors = neutralButtonColors()) {
            Icon(painterResource(R.drawable.ic_cancel), contentDescription = null)
            Text(stringResource(R.string.common_cancel), modifier = Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        } },
    ) }
    if (hideWarningOpen) AlertDialog(
        onDismissRequest = { hideWarningOpen = false },
        title = { Text(stringResource(R.string.photos_hidden_before)) },
        text = { Text(stringResource(R.string.photos_hidden_before_body)) },
        confirmButton = { Button(onClick = {
            vault.acceptWarning()
            hideWarningOpen = false
                    performHide(selectedUris)
        }, colors = neutralButtonColors()) { Text(stringResource(R.string.photos_understand), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        dismissButton = { Button(onClick = { hideWarningOpen = false }, colors = neutralButtonColors()) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
    )
    actionError?.let { error -> Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
    analysisError?.let { error -> Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
}

internal fun analysisProgressFraction(done: Int, total: Int): Float = if (total <= 0) 0f else done.toFloat().coerceIn(0f, total.toFloat()) / total

@Composable
private fun AnalysisProgress(done: Int, total: Int, paused: Boolean, togglePause: () -> Unit, modifier: Modifier = Modifier) = Surface(
    shape = MaterialTheme.shapes.large,
    color = islandColor(),
    contentColor = islandContentColor(),
    modifier = modifier.fillMaxWidth(),
) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.photos_analyzing), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.photos_analyzing_count, done, total), style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(progress = { analysisProgressFraction(done, total) }, modifier = Modifier.weight(1f))
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(40.dp).clickable(onClick = togglePause)) {
                Icon(painterResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause), contentDescription = stringResource(if (paused) R.string.photos_resume_analysis else R.string.photos_pause_analysis), modifier = Modifier.padding(9.dp))
            }
        }
    }
}

/**
 * Help organize is its questions: one card per question, answered in place, and a line at the end that says
 * how many are left — or that there are none.
 */
@Composable
private fun ReviewPage(
    reviews: List<PendingReview>,
    folders: List<DeviceFolder>,
    includeFolder: (String, Boolean) -> Unit,
    entriesByKey: Map<String, Entry>,
    faceGroup: (Long) -> FaceGroup?,
    back: () -> Unit,
    answer: (PendingReview, Boolean?) -> Unit,
) {
    // A question about a photo that is not on this device cannot be shown here; another device will ask it.
    val shown = reviews.filter { it.photoKey in entriesByKey }
    val left = folders.size + shown.size
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { FilesPageHeader(stringResource(R.string.photos_help_organize), R.drawable.ic_tag, back) }
        items(folders, key = { "folder/${it.name}" }) { folder -> FolderCard(folder) { includeFolder(folder.name, it) } }
        items(shown, key = { "${it.photoKey}/${it.faceSampleId}/${it.candidateGroupId}" }) { review ->
            ReviewCard(entriesByKey.getValue(review.photoKey), review, review.candidateGroupId?.let(faceGroup), entriesByKey) { answer(review, it) }
        }
        item {
            Text(
                if (left == 0) stringResource(R.string.photos_review_done)
                else pluralStringResource(R.plurals.photos_questions_left, left, left),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            )
        }
    }
}

/** "Include Viber in Tetra?" — a folder found on this phone, shown by its newest photos. */
@Composable
private fun FolderCard(folder: DeviceFolder, answer: (Boolean) -> Unit) = Surface(
    shape = MaterialTheme.shapes.extraLarge,
    color = MaterialTheme.colorScheme.surfaceVariant,
    modifier = Modifier.fillMaxWidth(),
) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.photos_include_folder, folder.name), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.photos_include_folder_body, folder.countText(LocalContext.current.resources), folder.name), style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            folder.entries.take(4).forEach { entry -> PhotoThumbnail(entry, false, Modifier.weight(1f).aspectRatio(1f).clip(MaterialTheme.shapes.medium)) {} }
            repeat(4 - minOf(4, folder.entries.size)) { Spacer(Modifier.weight(1f)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { answer(true) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_yes), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Button(onClick = { answer(false) }, colors = neutralButtonColors(), modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_no), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun ReviewCard(entry: Entry, review: PendingReview, candidate: FaceGroup?, entriesByKey: Map<String, Entry>, answer: (Boolean?) -> Unit) = Surface(
    shape = MaterialTheme.shapes.extraLarge,
    color = MaterialTheme.colorScheme.surfaceVariant,
    modifier = Modifier.fillMaxWidth(),
) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(review.name?.let { stringResource(R.string.photos_is_this, personName(it)) } ?: stringResource(R.string.photos_is_document), style = MaterialTheme.typography.titleLarge)
        if (review.candidateGroupId == null) ViewerImage(entry, Modifier.fillMaxWidth().height(240.dp).clip(MaterialTheme.shapes.large))
        else if (review.faceSample != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FaceReviewCrop(stringResource(R.string.photos_this_photo), entry, review.faceSample.bounds, Modifier.weight(1f))
            // A person whose every face went elsewhere has nothing to compare with; the name still asks.
            if (candidate != null) FaceReviewCrop(personName(candidate.name), entriesByKey[candidate.photoKey], candidate.bounds(), Modifier.weight(1f))
            else Spacer(Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { answer(true) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_yes), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Button(onClick = { answer(false) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_no), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Button(onClick = { answer(null) }, colors = neutralButtonColors(), modifier = Modifier.weight(1f)) { Text(stringResource(R.string.photos_skip), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun FaceReviewCrop(label: String, entry: Entry?, bounds: Rect, modifier: Modifier = Modifier) = Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    FaceCrop(entry, bounds, Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.large))
    Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun PeopleGroups(
    groups: List<FaceGroup>, entries: Map<String, Entry>, open: (FaceGroup) -> Unit, chooseFace: (FaceGroup) -> Unit,
    rename: (FaceGroup) -> Unit, forget: (FaceGroup) -> Unit, history: () -> Unit, back: () -> Unit,
) {
    val header = @Composable {
        FilesPageHeader(stringResource(R.string.photos_people), R.drawable.ic_people, back) {
            Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                IconButton(onClick = history) { Icon(painterResource(R.drawable.ic_restore), contentDescription = stringResource(R.string.photos_people_history)) }
            }
        }
    }
    if (groups.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            header()
            Text(stringResource(R.string.photos_people_none))
        }
        return
    }
    // People whose photos all went to the computer are drawn by the computer's own crop of their face
    val context = LocalContext.current
    val away = remember(groups) { groups.filter { !it.here }.mapNotNull { it.coverUuid } }
    val crops by produceState(emptyMap<String, File>(), away) {
        if (away.isNotEmpty()) value = withContext(Dispatchers.IO) { SyncClient(context.applicationContext, SyncStore(context.applicationContext)).faceCrops(away) }
    }
    LazyVerticalGrid(gridColumns(2), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { header() }
        items(groups, key = { it.id }) { group ->
            FaceGroupCard(group, entries[group.photoKey], crops[group.coverUuid], chooseFace = { chooseFace(group) }, rename = { rename(group) }, forget = { forget(group) }) { open(group) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FaceGroupCard(group: FaceGroup, entry: Entry?, crop: File?, chooseFace: () -> Unit, rename: () -> Unit, forget: () -> Unit, open: () -> Unit) = Surface(
    shape = MaterialTheme.shapes.extraLarge,
    color = MaterialTheme.colorScheme.surfaceVariant,
    modifier = Modifier.aspectRatio(1f).combinedClickable(onClick = open, onLongClick = chooseFace),
) { Box(Modifier.fillMaxSize()) {
    val picture = remember(crop) { crop?.let { runCatching { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }.getOrNull() } }
    if (entry == null && picture != null) Image(picture, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    else FaceCrop(entry, group, Modifier.fillMaxSize())
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.align(Alignment.TopStart).padding(8.dp)) {
        Surface(color = Color.Black.copy(alpha = 0.55f), contentColor = Color.White, shape = CircleShape) {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                Icon(painterResource(R.drawable.ic_edit), contentDescription = stringResource(R.string.photos_edit_named, personName(group.name)), modifier = Modifier.size(20.dp))
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.common_rename)) }, leadingIcon = { Icon(painterResource(R.drawable.ic_edit), null) }, onClick = { menu = false; rename() })
            DropdownMenuItem(text = { Text(stringResource(R.string.photos_choose_face)) }, leadingIcon = { Icon(painterResource(R.drawable.ic_people), null) }, onClick = { menu = false; chooseFace() })
            DropdownMenuItem(text = { Text(stringResource(R.string.photos_forget_person)) }, leadingIcon = { Icon(painterResource(R.drawable.ic_visibility_off), null) }, onClick = { menu = false; forget() })
        }
    }
    Surface(color = Color.Black.copy(alpha = 0.55f), contentColor = Color.White, modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text(personName(group.name), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (group.here) group.count.toString() else stringResource(R.string.photos_on_computer, group.count), style = MaterialTheme.typography.labelSmall)
        }
    }
} }

@Composable
private fun FaceCrop(entry: Entry?, group: FaceGroup, modifier: Modifier = Modifier) = FaceCrop(entry, group.bounds(), modifier)

@Composable
private fun FaceCrop(entry: Entry?, bounds: Rect, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, entry?.contentUri, bounds) {
        value = entry?.contentUri?.let { uri -> withContext(Dispatchers.IO) { runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val scale = minOf(1f, 1280f / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }.let { source ->
                bounds.let { rect ->
                    Bitmap.createBitmap(source, rect.left.coerceIn(0, source.width - 1), rect.top.coerceIn(0, source.height - 1), rect.width().coerceAtMost(source.width - rect.left.coerceIn(0, source.width - 1)), rect.height().coerceAtMost(source.height - rect.top.coerceIn(0, source.height - 1)))
                }
            }
        }.getOrNull() } }
    }
    bitmap?.let { Image(it.asImageBitmap(), null, modifier = modifier, contentScale = ContentScale.Crop) }
        ?: Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_collections), null) }
}

@Composable
private fun PersonGroupScreen(
    group: FaceGroup, entries: List<Entry>, allGroups: List<FaceGroup>, entriesByKey: Map<String, Entry>,
    back: () -> Unit, openPhoto: (Entry) -> Unit, rename: (String) -> Unit,
    merge: (FaceGroup) -> FaceMergeUndo, undoMerge: (FaceMergeUndo) -> Unit,
    history: () -> List<FaceMerge>, restore: (FaceMerge) -> Unit, detach: (Set<String>) -> Boolean,
) {
    var picked by remember(group.id) { mutableStateOf(emptySet<String>()) }
    var detachFailed by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var combining by remember { mutableStateOf(false) }
    var mergeTargets by remember { mutableStateOf<List<FaceGroup>?>(null) }
    var recentMerges by remember { mutableStateOf(emptyList<FaceMergeUndo>()) }
    var name by remember(group.id, group.name) { mutableStateOf(group.name) }
    var historyOpen by remember { mutableStateOf(false) }
    LaunchedEffect(recentMerges) {
        if (recentMerges.isNotEmpty()) {
            delay(8_000)
            recentMerges = emptyList()
        }
    }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FilesPageHeader(personName(group.name), R.drawable.ic_collections, back)
        if (editing) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(name, { name = it }, modifier = Modifier.weight(1f), singleLine = true)
            Button(onClick = { rename(name); editing = false }) { Text(stringResource(R.string.common_save), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        recentMerges.takeIf { it.isNotEmpty() }?.let { undos -> Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(R.plurals.photos_people_combined, undos.size, undos.size), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.common_undo), modifier = Modifier.clickable { undos.asReversed().forEach(undoMerge); recentMerges = emptyList() }.padding(8.dp), style = MaterialTheme.typography.labelLarge)
            }
        } }
        LazyVerticalGrid(gridColumns(3), contentPadding = PaddingValues(bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            items(entries, key = { it.photoKey }) { entry ->
                PhotoThumbnail(entry, entry.photoKey in picked, longPress = { picked = picked + entry.photoKey }) {
                    if (picked.isEmpty()) openPhoto(entry)
                    else picked = if (entry.photoKey in picked) picked - entry.photoKey else picked + entry.photoKey
                }
            }
        }
    }
    // Everything you can do to a person, in one island. It must be laid over the page, not placed after it: the
    // whole app sits in one Column, so a second full-height sibling is pushed off the bottom of the screen.
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(12.dp), contentAlignment = Alignment.BottomCenter) {
        Surface(color = islandColor(), contentColor = islandContentColor(), shape = MaterialTheme.shapes.extraLarge) {
            if (picked.isEmpty()) Row(Modifier.padding(horizontal = 4.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { combining = true }) { Icon(painterResource(R.drawable.ic_collections), contentDescription = stringResource(R.string.photos_combine_with)) }
                IconButton(onClick = { editing = true }) { Icon(painterResource(R.drawable.ic_edit), contentDescription = stringResource(R.string.photos_rename_person)) }
                IconButton(onClick = { historyOpen = true }) { Icon(painterResource(R.drawable.ic_restore), contentDescription = stringResource(R.string.photos_combined_into)) }
            } else Column(Modifier.padding(horizontal = 4.dp, vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Button(
                    onClick = { detachFailed = !detach(picked); if (!detachFailed) picked = emptySet() },
                    colors = neutralButtonColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_remove_from_collection), contentDescription = null)
                    Text(stringResource(R.string.photos_not_this_person), modifier = Modifier.padding(start = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pluralStringResource(R.plurals.files_selected, picked.size, picked.size), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.common_cancel), modifier = Modifier.clickable { picked = emptySet() }.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    }
    if (detachFailed) AlertDialog(
        onDismissRequest = { detachFailed = false },
        title = { Text(stringResource(R.string.photos_keep_one)) },
        text = { Text(stringResource(R.string.photos_keep_one_body, personName(group.name))) },
        confirmButton = { Button(onClick = { detachFailed = false }, colors = neutralButtonColors()) { Text(stringResource(R.string.common_ok), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
    )
    if (historyOpen) MergeHistorySheet(
        merges = remember(historyOpen, group.id) { history() },
        entriesByKey = entriesByKey,
        dismiss = { historyOpen = false },
        restore = { merge -> restore(merge); historyOpen = false },
    )
    if (combining) CombineFaceGroupsSheet(group, allGroups.filter { it.id != group.id }, entriesByKey, dismiss = { combining = false }, combine = { mergeTargets = it })
    mergeTargets?.let { sources -> AlertDialog(
        onDismissRequest = { mergeTargets = null },
        title = { Text(stringResource(R.string.photos_combine_ask)) },
        text = { Text(pluralStringResource(R.plurals.photos_combine_body, sources.size, sources.size, personName(group.name))) },
        confirmButton = { Button(onClick = { recentMerges = sources.map(merge); mergeTargets = null; combining = false }, colors = neutralButtonColors()) { Text(stringResource(R.string.photos_combine), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        dismissButton = { Button(onClick = { mergeTargets = null }, colors = neutralButtonColors()) { Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
    ) }
}


/**
 * Every group that was combined into this person, with the head and the number it had at the time. Combining is
 * the one action here that throws a grouping away, and the person it was wrong about cannot be reached
 * afterwards — so they are kept, and putting one back is one tap, not an eight-second window you had to catch.
 */
/**
 * Which face a person is shown by. The best one the scores can find is a guess, and the one you would have
 * picked is often not it — a good photo of someone is not the sharpest crop of them. Holding a person opens
 * this; everything they appear in is here, newest first, and the choice travels to the other devices.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChooseCoverSheet(
    group: FaceGroup,
    faces: List<FaceOfPerson>,
    entriesByKey: Map<String, Entry>,
    dismiss: () -> Unit,
    choose: (FaceOfPerson?) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = islandColor(), contentColor = islandContentColor()) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.photos_show_by, personName(group.name)), style = MaterialTheme.typography.titleLarge)
            if (faces.isEmpty()) Text(stringResource(R.string.photos_faces_none))
            else {
                Text(stringResource(R.string.photos_pick_face))
                LazyVerticalGrid(
                    GridCells.Adaptive(88.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(faces, key = { it.uuid }) { face ->
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.aspectRatio(1f)
                                .then(if (face.chosen) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium) else Modifier)
                                .clickable { choose(face) },
                        ) { FaceCrop(entriesByKey[face.photoKey], face.bounds, Modifier.fillMaxSize()) }
                    }
                }
                if (faces.any { it.chosen }) Text(
                    stringResource(R.string.photos_use_best),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clickable { choose(null) }.padding(vertical = 8.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MergeHistorySheet(
    merges: List<FaceMerge>, entriesByKey: Map<String, Entry>, dismiss: () -> Unit, restore: (FaceMerge) -> Unit,
    // The People page's History also lists everyone forgotten; a person's own History is only what was combined.
    title: String = stringResource(R.string.photos_combined_into), forgotten: List<FaceGroup>? = null, unforget: (FaceGroup) -> Unit = {},
) {
    val when_ = remember { java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()) }
    @Composable fun HistoryRow(entry: Entry?, bounds: Rect?, name: String, detail: String, restore: () -> Unit) =
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                bounds?.let { FaceCrop(entry, it, Modifier.size(72.dp).clip(MaterialTheme.shapes.medium)) }
                Column(Modifier.weight(1f)) {
                    Text(personName(name), style = MaterialTheme.typography.titleMedium)
                    Text(detail, style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = restore, colors = neutralButtonColors()) { Text(stringResource(R.string.photos_restore), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = islandColor(), contentColor = islandContentColor()) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.photos_restore_body))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (forgotten != null) {
                    item { Text(stringResource(R.string.photos_forgotten), style = MaterialTheme.typography.labelLarge) }
                    if (forgotten.isEmpty()) item { Text(stringResource(R.string.photos_forgotten_none)) }
                    items(forgotten, key = { "f${it.id}" }) { group ->
                        HistoryRow(entriesByKey[group.photoKey], group.bounds(), group.name, pluralStringResource(R.plurals.photos_photos, group.count, group.count)) { unforget(group) }
                    }
                    item { Text(stringResource(R.string.photos_combined), style = MaterialTheme.typography.labelLarge) }
                }
                if (merges.isEmpty()) item { Text(stringResource(R.string.photos_combined_none)) }
                items(merges, key = { "m${it.id}" }) { merge ->
                    HistoryRow(
                        merge.head?.let { entriesByKey[it.photoKey] }, merge.head?.let { Rect(it.left, it.top, it.right, it.bottom) }, merge.sourceName,
                        "${pluralStringResource(R.plurals.photos_faces, merge.sampleIds.size, merge.sampleIds.size)} · ${when_.format(merge.mergedAt)}",
                    ) { restore(merge) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CombineFaceGroupsSheet(current: FaceGroup, choices: List<FaceGroup>, entriesByKey: Map<String, Entry>, dismiss: () -> Unit, combine: (List<FaceGroup>) -> Unit) {
    var query by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    val visibleChoices = remember(choices, query) { choices.filter { it.name.contains(query, ignoreCase = true) } }
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = islandColor(), contentColor = islandContentColor()) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.photos_combine_manually, personName(current.name)), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.photos_combine_manually_body))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.photos_find_person)) }, modifier = Modifier.weight(1f), singleLine = true)
                Button(onClick = { combine(choices.filter { it.id in selectedIds }) }, enabled = selectedIds.isNotEmpty(), colors = neutralButtonColors()) { Text(stringResource(R.string.photos_combine), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (choices.isEmpty()) Text(stringResource(R.string.photos_no_other_people))
            else if (visibleChoices.isEmpty()) Text(stringResource(R.string.photos_no_people_match))
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(visibleChoices, key = { it.id }) { choice ->
                    val selected = choice.id in selectedIds
                    Surface(shape = MaterialTheme.shapes.large, color = if (selected) driveNavigationSelectedColor() else MaterialTheme.colorScheme.surfaceVariant, contentColor = if (selected) driveNavigationSelectedContentColor() else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().clickable {
                        selectedIds = if (selected) selectedIds - choice.id else selectedIds + choice.id
                    }) {
                    Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        FaceCrop(entriesByKey[choice.photoKey], choice, Modifier.size(72.dp).clip(MaterialTheme.shapes.medium))
                        Column(Modifier.weight(1f)) {
                            Text(personName(choice.name), style = MaterialTheme.typography.titleMedium)
                            Text(pluralStringResource(R.plurals.photos_faces, choice.count, choice.count), style = MaterialTheme.typography.bodyMedium)
                        }
                        if (selected) Icon(painterResource(R.drawable.ic_check), contentDescription = stringResource(R.string.files_selected_one))
                    }
                } }
            }
        }
    }
}

@Composable
private fun LockedVaultScreen(back: () -> Unit, unlock: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        FilesPageHeader(stringResource(R.string.photos_hidden), R.drawable.ic_lock, back)
        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(painterResource(R.drawable.ic_lock), contentDescription = null, modifier = Modifier.size(54.dp))
            Text(stringResource(R.string.photos_hidden_locked), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
            Text(stringResource(R.string.photos_unlock_body), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Button(onClick = unlock, colors = neutralButtonColors(), modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.photos_unlock), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun Collections(entries: List<Entry>, metadata: Map<String, PhotoState>, custom: List<PhotoCollection>, previews: Map<Long, Entry?>, documentKeys: Set<String>, peopleCount: Int, reviewKeys: Set<String>, folderQuestions: Int, hiddenCount: Int, showPeople: Boolean, showDocuments: Boolean, analysisRunning: Boolean, analysisPaused: Boolean, analysisDone: Int, analysisTotal: Int, toggleAnalysisPause: () -> Unit, back: () -> Unit, create: () -> Unit, open: (PhotoFilter) -> Unit, selectedCollectionId: Long?, selectCollection: (PhotoCollection) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { FilesPageHeader(stringResource(R.string.photos_collections), R.drawable.ic_collections, back, trailing = {
            Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                IconButton(onClick = create) { Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.photos_create_collection)) }
            }
        }) }
        if (analysisRunning) item { AnalysisProgress(analysisDone, analysisTotal, analysisPaused, toggleAnalysisPause) }
        item { Text(stringResource(R.string.photos_system_collections), style = MaterialTheme.typography.labelLarge) }
        if (showPeople || showDocuments) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (showPeople) SystemCollectionButton(stringResource(R.string.photos_people), peopleCount, R.drawable.ic_people, { open(PhotoFilter.People) }, Modifier.weight(1f))
            if (showDocuments) SystemCollectionButton(stringResource(R.string.files_kind_documents), entries.count { it.photoKey in documentKeys }, R.drawable.ic_file, { open(PhotoFilter.Documents) }, Modifier.weight(1f))
            if (!showPeople || !showDocuments) Box(Modifier.weight(1f))
        } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SystemCollectionButton(stringResource(R.string.photos_screenshots), entries.count(Entry::isScreenshot), R.drawable.ic_screenshot, { open(PhotoFilter.Screenshots) }, Modifier.weight(1f))
            SystemCollectionButton(stringResource(R.string.files_kind_videos), entries.count(Entry::isVideo), R.drawable.ic_video, { open(PhotoFilter.Videos) }, Modifier.weight(1f))
        } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SystemCollectionButton(stringResource(R.string.photos_hidden), hiddenCount, R.drawable.ic_lock, { open(PhotoFilter.Hidden) }, Modifier.weight(1f))
            SystemCollectionButton(stringResource(R.string.photos_map), null, R.drawable.ic_map, { open(PhotoFilter.Map) }, Modifier.weight(1f))
        } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SystemCollectionButton(stringResource(R.string.photos_help_organize), entries.count { it.photoKey in reviewKeys } + folderQuestions, R.drawable.ic_tag, { open(PhotoFilter.Review) }, Modifier.weight(1f))
            SystemCollectionButton(stringResource(R.string.files_favorites), entries.count { metadata[it.photoKey].orDefault().favorite }, R.drawable.ic_favorite_border, { open(PhotoFilter.Favorites) }, Modifier.weight(1f))
        } }
        // No count: Android holds the trash, and asking it for one on every Collections draw is a query per draw.
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SystemCollectionButton(stringResource(R.string.files_trash), null, R.drawable.ic_delete, { open(PhotoFilter.Trash) }, Modifier.weight(1f))
            Box(Modifier.weight(1f))
        } }
        item { Text(stringResource(R.string.photos_my_collections), style = MaterialTheme.typography.labelLarge) }
        if (custom.isEmpty()) item { Text(stringResource(R.string.photos_collections_none), style = MaterialTheme.typography.bodyMedium) }
        custom.chunked(2).forEach { row -> item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { collection -> MyCollectionCard(
                    collection = collection,
                    preview = previews[collection.id],
                    selected = selectedCollectionId == collection.id,
                    open = { if (selectedCollectionId == null) open(PhotoFilter.Collection(collection.id)) else selectCollection(collection) },
                    select = { selectCollection(collection) },
                    modifier = Modifier.weight(1f),
                ) }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        } }
        item { Text(stringResource(R.string.photos_collections_note), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun RowScope.SystemCollectionButton(name: String, count: Int?, icon: Int, open: () -> Unit, modifier: Modifier = Modifier) = Surface(
    shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant,
    modifier = modifier.height(68.dp).clickable(onClick = open),
) { Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(painterResource(icon), contentDescription = name, modifier = Modifier.size(22.dp))
    Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    count?.let { Text(it.toString(), style = MaterialTheme.typography.labelSmall) }
} }

@Composable private fun MyCollectionCard(collection: PhotoCollection, preview: Entry?, selected: Boolean = false, open: () -> Unit, select: () -> Unit = {}, modifier: Modifier = Modifier) = Surface(
    shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceVariant,
    modifier = modifier.aspectRatio(1f).pointerInput(collection.id) {
        detectTapGestures(onTap = { open() }, onLongPress = { select() })
    }.then(if (selected) Modifier.border(3.dp, islandContentColor(), MaterialTheme.shapes.extraLarge) else Modifier),
) { Box(Modifier.fillMaxSize()) {
    CollectionCover(preview, Modifier.fillMaxSize())
    if (selected) Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
        Icon(painterResource(R.drawable.ic_check), contentDescription = stringResource(R.string.photos_selected_collection), modifier = Modifier.padding(8.dp).size(22.dp))
    }
    Surface(
        color = Color.Black.copy(alpha = 0.55f), contentColor = Color.White,
        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
    ) { Column(Modifier.padding(12.dp)) {
        Text(collection.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (collection.elsewhere > 0) stringResource(R.string.photos_here_and_computer, collection.here, collection.elsewhere) else collection.here.toString(),
            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    } }
} }

@Composable private fun CollectionCover(entry: Entry?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, entry?.contentUri) {
        value = entry?.contentUri?.let { uri -> withContext(Dispatchers.IO) { runCatching { context.contentResolver.loadThumbnail(uri, android.util.Size(360, 360), null) }.getOrNull() } }
    }
    val image = bitmap
    if (image == null) Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_collections), contentDescription = null, modifier = Modifier.size(44.dp))
    } else Image(bitmap = image.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
}

/** Where to move photos: a real folder on this device, not a collection. A new folder goes under Pictures. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderMoveSheet(count: Int, leaving: String?, destinations: List<Pair<String, Int>>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (leaving != null) pluralStringResource(R.plurals.photos_move_out_title, count, count, leaving) else pluralStringResource(R.plurals.photos_move_title, count, count), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.photos_move_body), style = MaterialTheme.typography.bodySmall)
            destinations.forEach { (path, n) ->
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().clickable { onPick(path) }) {
                    Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_folder), contentDescription = null)
                        Column(Modifier.weight(1f)) {
                            Text(FolderRules.albumName(path) ?: path)
                            Text("$path · $n", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            OutlinedTextField(name, { name = it.replace("/", "").replace("\\", "") }, label = { Text(stringResource(R.string.photos_new_folder)) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { onPick("Pictures/${name.trim()}") }, enabled = name.isNotBlank(), colors = neutralButtonColors(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_move), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewCollectionSheet(dismiss: () -> Unit, create: (String) -> Result<PhotoCollection>) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.photos_new_collection), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(name, { name = it; error = null }, label = { Text(stringResource(R.string.common_name)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, isError = error != null)
            Button(onClick = { create(name).onFailure { error = it.shownMessage(context) ?: context.getString(R.string.photos_error_collection) } }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.files_create), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectionPickerSheet(
    collections: List<PhotoCollection>,
    previews: Map<Long, Entry?>,
    onDismiss: () -> Unit,
    onHide: () -> Unit,
    onAdd: (Long) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.photos_add_to_collection), style = MaterialTheme.typography.titleMedium)
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onHide),
            ) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_lock), contentDescription = null)
                    Column {
                        Text(stringResource(R.string.photos_hidden), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.photos_hidden_lock), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_map), contentDescription = null)
                    Column {
                        Text(stringResource(R.string.photos_map), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.photos_map_auto), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text(stringResource(R.string.photos_my_collections), style = MaterialTheme.typography.labelLarge)
            if (collections.isEmpty()) Text(stringResource(R.string.photos_personal_none), style = MaterialTheme.typography.bodySmall)
            collections.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { collection ->
                        MyCollectionCard(collection, previews[collection.id], open = { onAdd(collection.id) }, modifier = Modifier.weight(1f))
                    }
                    if (row.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
    }
}

private fun PhotoState?.orDefault() = this ?: PhotoState()

/** A person's name as shown: one Tetra numbered itself ("Person 3", kept so in the database and on the computer) in the user's words. */
@Composable private fun personName(name: String): String = if (isGeneratedPersonName(name)) stringResource(R.string.photos_person_n, name.removePrefix("Person ")) else name
internal fun Entry.isScreenshot() = relativePath?.startsWith("Pictures/Screenshots/") == true || relativePath?.startsWith("DCIM/Screenshots/") == true

@Composable
private fun PhotoBottomBar(
    pane: PhotosPane,
    gallery: () -> Unit,
    collections: () -> Unit,
    expand: () -> Unit,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    IslandBottomBar(expand, visible, modifier) {
        IslandNavigationItem(stringResource(R.string.app_gallery), pane == PhotosPane.Timeline, R.drawable.ic_gallery, gallery)
        IslandNavigationItem(stringResource(R.string.photos_collections), pane == PhotosPane.Collections, R.drawable.ic_collections, collections)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryToolsSheet(
    permission: String,
    scale: TimelineScale,
    allowPhotos: () -> Unit,
    refresh: () -> Unit,
    selectScale: (TimelineScale) -> Unit,
    hideScreenshots: Boolean,
    hideDocuments: Boolean,
    setHideScreenshots: (Boolean) -> Unit,
    setHideDocuments: (Boolean) -> Unit,
    motionMode: String,
    setMotionMode: (String) -> Unit,
    motionAutoplay: Boolean,
    setMotionAutoplay: (Boolean) -> Unit,
    motionHalves: Int,
    removeMotionHalves: () -> Unit,
    albums: List<PhotoCollection>,
    hiddenAlbums: Set<String>,
    setAlbumHidden: (PhotoCollection, Boolean) -> Unit,
    dismiss: () -> Unit,
) {
    // every photo and video readable (not only the ones picked), asked of Android rather than read off [permission]'s words
    val context = LocalContext.current
    fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    val fullAccess = if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO)
        else granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = islandColor(), contentColor = islandContentColor()) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.photos_gallery_tools), style = MaterialTheme.typography.titleLarge)
            Text(permission, style = MaterialTheme.typography.bodyMedium)
            if (!fullAccess) {
                DriveWideAction(R.drawable.ic_check, stringResource(R.string.photos_allow)) { allowPhotos(); dismiss() }
            }
            DriveWideAction(R.drawable.ic_refresh, stringResource(R.string.photos_refresh)) { refresh(); dismiss() }
            Text(stringResource(R.string.photos_hide_from_gallery), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.photos_hide_from_gallery_body), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.photos_system_albums), style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { setHideScreenshots(!hideScreenshots) }) {
                Checkbox(checked = hideScreenshots, onCheckedChange = setHideScreenshots, colors = neutralCheckboxColors())
                Text(stringResource(R.string.photos_screenshots))
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { setHideDocuments(!hideDocuments) }) {
                Checkbox(checked = hideDocuments, onCheckedChange = setHideDocuments, colors = neutralCheckboxColors())
                Text(stringResource(R.string.files_kind_documents))
            }
            Text(stringResource(R.string.photos_my_albums), style = MaterialTheme.typography.labelLarge)
            if (albums.isEmpty()) Text(stringResource(R.string.photos_albums_none), style = MaterialTheme.typography.bodySmall)
            albums.forEach { album ->
                val hidden = album.uuid in hiddenAlbums
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { setAlbumHidden(album, !hidden) }) {
                    Checkbox(checked = hidden, onCheckedChange = { setAlbumHidden(album, it) }, colors = neutralCheckboxColors())
                    Text(album.name, modifier = Modifier.weight(1f))
                    Text("${album.here}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(stringResource(R.string.photos_motion), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("one" to stringResource(R.string.photos_motion_one), "remove" to stringResource(R.string.common_remove)).forEach { (mode, label) ->
                    Surface(
                        color = if (motionMode == mode) driveNavigationSelectedColor() else islandColor(),
                        contentColor = if (motionMode == mode) driveNavigationSelectedContentColor() else islandContentColor(),
                        shape = CircleShape,
                        modifier = Modifier.weight(1f).clickable { setMotionMode(mode) },
                    ) { Text(label, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            Text(stringResource(if (motionMode == "remove") R.string.photos_motion_remove_body else R.string.photos_motion_one_body),
                style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { setMotionAutoplay(!motionAutoplay) }) {
                Checkbox(checked = motionAutoplay, onCheckedChange = setMotionAutoplay, colors = neutralCheckboxColors())
                Text(stringResource(R.string.photos_motion_autoplay))
            }
            if (motionMode == "remove" && motionHalves > 0) DriveWideAction(R.drawable.ic_delete, pluralStringResource(R.plurals.photos_motion_trash, motionHalves, motionHalves), removeMotionHalves)
            Text(stringResource(R.string.photos_timeline_size), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimelineScale.entries.forEach { option ->
                    Surface(
                        color = if (scale == option) driveNavigationSelectedColor() else islandColor(),
                        contentColor = if (scale == option) driveNavigationSelectedContentColor() else islandContentColor(),
                        shape = CircleShape,
                        modifier = Modifier.weight(1f).clickable { selectScale(option); dismiss() },
                    ) { Text(stringResource(option.label), modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectionsToolsSheet(
    hidePeople: Boolean,
    hideDocuments: Boolean,
    setHidePeople: (Boolean) -> Unit,
    setHideDocuments: (Boolean) -> Unit,
    busy: Boolean,
    rescanFaces: () -> Unit,
    rescanDocuments: () -> Unit,
    dismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = islandColor(), contentColor = islandContentColor()) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.photos_collections_tools), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.photos_hide_system), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { setHidePeople(!hidePeople) }) {
                Checkbox(checked = hidePeople, onCheckedChange = setHidePeople, colors = neutralCheckboxColors())
                Text(stringResource(R.string.photos_people))
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { setHideDocuments(!hideDocuments) }) {
                Checkbox(checked = hideDocuments, onCheckedChange = setHideDocuments, colors = neutralCheckboxColors())
                Text(stringResource(R.string.files_kind_documents))
            }
            Text(stringResource(R.string.photos_look_again), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.photos_look_again_body))
            CollectionsToolButton(R.drawable.ic_people, stringResource(R.string.photos_rescan_faces), busy, Modifier.fillMaxWidth(), rescanFaces)
            CollectionsToolButton(R.drawable.ic_file, stringResource(R.string.photos_rescan_documents), busy, Modifier.fillMaxWidth(), rescanDocuments)
        }
    }
}

@Composable
private fun CollectionsToolButton(icon: Int, label: String, busy: Boolean, modifier: Modifier = Modifier, click: () -> Unit) = Button(
    onClick = click, enabled = !busy, colors = neutralButtonColors(), modifier = modifier,
) {
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
    Spacer(Modifier.width(8.dp))
    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun neutralCheckboxColors() = CheckboxDefaults.colors(
    checkedColor = driveNavigationSelectedColor(),
    checkmarkColor = driveNavigationSelectedContentColor(),
    uncheckedColor = islandContentColor().copy(alpha = 0.72f),
)

@Composable
private fun neutralButtonColors() = ButtonDefaults.buttonColors(
    containerColor = driveNavigationSelectedColor(),
    contentColor = driveNavigationSelectedContentColor(),
    disabledContainerColor = islandContentColor().copy(alpha = 0.12f),
    disabledContentColor = islandContentColor().copy(alpha = 0.38f),
)

@Composable
private fun SearchButton(visible: Boolean = true, description: String = stringResource(R.string.photos_search), open: () -> Unit) {
    IslandVisibility(visible) {
        Surface(
            color = islandColor(),
            contentColor = islandContentColor(),
            shape = CircleShape,
        ) {
            IconButton(onClick = open) {
                Icon(
                    painterResource(R.drawable.ic_search),
                    contentDescription = description,
                    tint = islandContentColor(),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchSheet(query: String, setQuery: (String) -> Unit, clear: () -> Unit, dismiss: () -> Unit, title: String = stringResource(R.string.photos_search), hint: String = stringResource(R.string.photos_search_hint), suggestions: List<String> = emptyList()) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = query,
                onValueChange = setQuery,
                label = { Text(hint) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            if (suggestions.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(suggestions) { suggestion ->
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.clickable { setQuery(suggestion) }) {
                        Text(suggestion, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (query.isNotBlank()) Text(stringResource(R.string.common_clear), modifier = Modifier.clickable(onClick = clear).padding(vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun PhotoSearchPanel(query: String, setQuery: (String) -> Unit, clear: () -> Unit, dismiss: () -> Unit, suggestions: List<String>) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val glass = islandColor()
    val ink = islandContentColor()
    Surface(shape = MaterialTheme.shapes.extraLarge, color = glass, contentColor = ink, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = setQuery,
                label = { Text(stringResource(R.string.photos_search_panel_hint)) },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ink.copy(alpha = 0.65f), unfocusedBorderColor = ink.copy(alpha = 0.35f),
                    focusedLabelColor = ink, unfocusedLabelColor = ink.copy(alpha = 0.72f), cursorColor = ink,
                    focusedTextColor = ink, unfocusedTextColor = ink,
                ),
            )
            if (suggestions.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(suggestions) { suggestion ->
                    Surface(shape = MaterialTheme.shapes.small, color = Color.White.copy(alpha = 0.62f), modifier = Modifier.clickable { setQuery(suggestion) }) {
                        Text(suggestion, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.photos_english_tags), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                if (query.isNotBlank()) Text(stringResource(R.string.common_clear), modifier = Modifier.clickable(onClick = clear).padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = dismiss) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.photos_close_search)) }
            }
        }
    }
}

@Composable
private fun SelectionActions(
    count: Int,
    share: () -> Unit,
    addToCollection: (() -> Unit)?,
    removeFromCollection: (() -> Unit)?,
    toggleFavorite: () -> Unit,
    moveToTrash: () -> Unit,
    hide: () -> Unit,
    cancel: () -> Unit,
    modifier: Modifier = Modifier,
    removeLabel: String = stringResource(R.string.photos_remove_from_collection),
    moveToFolder: (() -> Unit)? = null,
) {
    Surface(
        color = islandColor(),
        contentColor = islandContentColor(),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            removeFromCollection?.let { remove ->
                Button(onClick = remove, colors = neutralButtonColors(), modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
                    Icon(painterResource(R.drawable.ic_remove_from_collection), contentDescription = null)
                    Text(removeLabel, modifier = Modifier.padding(start = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(onClick = share) { Icon(painterResource(R.drawable.ic_share), contentDescription = stringResource(R.string.photos_share_selected)) }
                addToCollection?.let { add -> IconButton(onClick = add) {
                    Icon(painterResource(R.drawable.ic_add_to_collection), contentDescription = stringResource(R.string.photos_add_selected))
                } }
                IconButton(onClick = toggleFavorite) { Icon(painterResource(R.drawable.ic_favorite_border), contentDescription = stringResource(R.string.photos_favorite_selected)) }
                IconButton(onClick = moveToTrash) { Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.photos_trash_selected)) }
                moveToFolder?.let { move -> IconButton(onClick = move) { Icon(painterResource(R.drawable.ic_move), contentDescription = stringResource(R.string.photos_folder_selected)) } }
                IconButton(onClick = hide) { Icon(painterResource(R.drawable.ic_lock), contentDescription = stringResource(R.string.photos_hide_selected)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(R.plurals.files_selected, count, count), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.common_cancel), modifier = Modifier.clickable(onClick = cancel).padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** In Trash there are only two things worth offering: undoing the delete, and finishing it for everything. */
@Composable
private fun TrashSelectionActions(count: Int, restore: () -> Unit, emptyTrash: () -> Unit, cancel: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = islandColor(), contentColor = islandContentColor(), shape = MaterialTheme.shapes.extraLarge, modifier = modifier) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = restore, colors = neutralButtonColors(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(painterResource(R.drawable.ic_restore), contentDescription = null)
                Text(stringResource(R.string.photos_restore), modifier = Modifier.padding(start = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(
                onClick = emptyTrash,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = null)
                Text(stringResource(R.string.photos_empty_trash_button), modifier = Modifier.padding(start = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(R.plurals.files_selected, count, count), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.common_cancel), modifier = Modifier.clickable(onClick = cancel).padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The same island Photos uses, with the actions that make sense for several files or folders at once. */
@Composable
private fun DriveSelectionActions(
    count: Int,
    inTrash: Boolean,
    anyFile: Boolean,
    share: () -> Unit,
    favorite: () -> Unit,
    tags: () -> Unit,
    copy: () -> Unit,
    move: () -> Unit,
    trash: () -> Unit,
    restore: () -> Unit,
    cancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = islandColor(), contentColor = islandContentColor(), shape = MaterialTheme.shapes.extraLarge, modifier = modifier) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.SpaceEvenly) {
                if (!inTrash) {
                    if (anyFile) IconButton(onClick = share) { Icon(painterResource(R.drawable.ic_share), contentDescription = stringResource(R.string.files_share_selected)) }
                    IconButton(onClick = copy) { Icon(painterResource(R.drawable.ic_copy), contentDescription = stringResource(R.string.files_copy_selected)) }
                    IconButton(onClick = move) { Icon(painterResource(R.drawable.ic_move), contentDescription = stringResource(R.string.files_move_selected)) }
                    IconButton(onClick = tags) { Icon(painterResource(R.drawable.ic_tag), contentDescription = stringResource(R.string.files_tag_selected)) }
                    IconButton(onClick = favorite) { Icon(painterResource(R.drawable.ic_favorite_border), contentDescription = stringResource(R.string.files_favorite_selected)) }
                    IconButton(onClick = trash) { Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.files_trash_selected)) }
                } else {
                    IconButton(onClick = restore) { Icon(painterResource(R.drawable.ic_restore), contentDescription = stringResource(R.string.files_restore_selected)) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(R.plurals.files_selected, count, count), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.common_cancel), modifier = Modifier.clickable(onClick = cancel).padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun HiddenSelectionActions(count: Int, restore: () -> Unit, cancel: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = islandColor(), contentColor = islandContentColor(), shape = MaterialTheme.shapes.extraLarge, modifier = modifier) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = restore, colors = neutralButtonColors(), modifier = Modifier.fillMaxWidth()) {
                Icon(painterResource(R.drawable.ic_restore), contentDescription = null)
                Text(stringResource(R.string.photos_restore), modifier = Modifier.padding(start = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(R.plurals.files_selected, count, count), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.common_cancel), modifier = Modifier.clickable(onClick = cancel).padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun PhotoTimeline(
    state: ListState,
    scale: TimelineScale,
    setScale: (TimelineScale) -> Unit,
    gridState: LazyGridState,
    selectedUris: Set<Uri>,
    addSelection: (Entry) -> Unit,
    toggleSelection: (Entry) -> Unit,
    openPhoto: (Entry) -> Unit,
    refresh: () -> Unit,
    title: String?,
    icon: Int,
    back: () -> Unit,
    showTimelineIsland: Boolean,
    emptyMessage: String,
    emptyTrash: (() -> Unit)? = null,
) {
    PullToRefreshBox(isRefreshing = state is ListState.Loading, onRefresh = refresh, modifier = Modifier.fillMaxSize()) {
        when (state) {
            ListState.Idle -> TimelineMessage(stringResource(R.string.photos_idle))
            ListState.Loading -> TimelineMessage(stringResource(R.string.common_loading))
            is ListState.Error -> TimelineMessage(state.message, MaterialTheme.colorScheme.error)
            is ListState.Items -> if (state.entries.isEmpty()) TimelineMessage(emptyMessage) else {
            val grouped = state.entries.groupBy { TimelineRules.groupKey(it.takenMillis, scale) }
            val photosByKey = state.entries.associateBy { "photo:${it.contentUri}" }
            // The photo a long press just selected: lifting the finger is a tap on it, which must not deselect it.
            val longPressed = remember { java.util.concurrent.atomic.AtomicReference<Uri?>(null) }
            val itemGroupKeys = remember(grouped, scale) {
                val groups = TimelineRules.itemGroupKeys(grouped.map { (key, photos) -> key to photos.size }, firstGroupHasHeader = false)
                listOf(groups.firstOrNull() ?: "unknown") + groups
            }
            val activeGroup = TimelineRules.visibleGroupKey(itemGroupKeys, gridState.firstVisibleItemIndex)
            val context = LocalContext.current
            val unknownDate = stringResource(R.string.photos_unknown_date)
            fun groupLabel(key: String) = TimelineRules.groupLabel(key, scale, unknownDate, { week, year -> context.getString(R.string.photos_week_of, week, year) })
            var scrollbarLabel by remember { mutableStateOf<String?>(null) }
            var scrollbarInteracting by remember { mutableStateOf(false) }
            var scrollbarVisible by remember { mutableStateOf(false) }
            LaunchedEffect(scrollbarLabel) {
                if (scrollbarLabel != null) {
                    delay(900)
                    scrollbarLabel = null
                }
            }
            LaunchedEffect(scrollbarInteracting) {
                if (scrollbarInteracting) scrollbarVisible = true else {
                    delay(700)
                    if (!scrollbarInteracting) scrollbarVisible = false
                }
            }
            val widthDp = LocalConfiguration.current.screenWidthDp
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(TimelineRules.columns(scale, widthDp)),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxSize()
                        .timelinePinch(scale, setScale)
                        .timelineSelection(gridState, photosByKey, addSelection, longPressed),
                ) {
                    if (title == null) item(key = "timeline-top-inset", span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.heightIn(min = 140.dp))
                    } else {
                        item(key = "timeline-page-header", span = { GridItemSpan(maxLineSpan) }) {
                            FilesPageHeader(title, icon, back, emptyTrash = emptyTrash)
                        }
                        item(key = "timeline-title-gap", span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.height(64.dp))
                        }
                    }
                    grouped.entries.forEachIndexed { index, (key, entries) ->
                        if (index > 0 || title != null) item(key = "date:$key", span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 20.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    groupLabel(key),
                                    color = MaterialTheme.colorScheme.onBackground,
                                    style = MaterialTheme.typography.titleLarge,
                                )
                            }
                        }
                        items(entries, key = { "photo:${it.contentUri}" }) { entry ->
                            val selected = entry.contentUri in selectedUris
                            PhotoThumbnail(entry, selected) {
                                if (longPressed.getAndSet(null) == entry.contentUri) return@PhotoThumbnail
                                if (selectedUris.isEmpty()) openPhoto(entry) else toggleSelection(entry)
                            }
                        }
                    }
                }
                AnimatedVisibility(
                    visible = showTimelineIsland && (title == null || gridState.firstVisibleItemIndex > 0),
                    enter = fadeIn(tween(150)) + slideInVertically(tween(150)) { -it / 3 },
                    exit = fadeOut(tween(100)),
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp),
                ) {
                    Surface(color = islandColor(), contentColor = islandContentColor(), shape = MaterialTheme.shapes.extraLarge) {
                        Text(
                            groupLabel(activeGroup),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                if (title != null) AnimatedVisibility(
                    visible = gridState.firstVisibleItemIndex > 0,
                    enter = fadeIn(tween(150)) + slideInVertically(tween(150)) { -it / 3 },
                    exit = fadeOut(tween(100)),
                    modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 12.dp, top = 12.dp),
                ) {
                    Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                        IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_back)) }
                    }
                }
                if (itemGroupKeys.size > 8) TimelineFastScrollbar(
                    itemCount = itemGroupKeys.size,
                    firstVisibleItem = gridState.firstVisibleItemIndex,
                    visible = scrollbarVisible,
                    setInteracting = { scrollbarInteracting = it },
                    onScrollTo = { index ->
                        val group = TimelineRules.visibleGroupKey(itemGroupKeys, index)
                        scrollbarLabel = "${TimelineRules.yearForGroup(group, unknownDate)} · ${groupLabel(group)}"
                        gridState.scrollToItem(index)
                    },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
                scrollbarLabel?.let { label ->
                    Surface(
                        color = islandColor(),
                        contentColor = islandContentColor(),
                        modifier = Modifier.align(Alignment.Center),
                        shape = MaterialTheme.shapes.extraLarge,
                    ) { Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) }
                }
            }
            }
        }
    }
}

@Composable
private fun TimelineMessage(message: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), shape = MaterialTheme.shapes.large) {
            Text(message, color = color, modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun TimelineFastScrollbar(
    itemCount: Int,
    firstVisibleItem: Int,
    visible: Boolean,
    setInteracting: (Boolean) -> Unit,
    onScrollTo: suspend (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var size by remember { mutableStateOf(IntSize.Zero) }
    var lastRequestedIndex by remember(itemCount) { mutableStateOf(-1) }
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    val ratio = firstVisibleItem.coerceIn(0, itemCount - 1).toFloat() / (itemCount - 1).coerceAtLeast(1)
    val thumbHeight = 48.dp
    val maxThumbOffset = with(LocalDensity.current) {
        (size.height - thumbHeight.roundToPx()).coerceAtLeast(0).toDp()
    }
    fun scrollAt(y: Float) {
        val index = ((y / size.height.coerceAtLeast(1)) * (itemCount - 1)).roundToInt().coerceIn(0, itemCount - 1)
        if (index == lastRequestedIndex) return
        lastRequestedIndex = index
        scrollJob?.cancel()
        scrollJob = scope.launch { onScrollTo(index) }
    }
    // Only as tall as the track it draws: a full-height strip sat over the top bar's buttons and took their taps —
    // Empty Trash, top right, scrolled the list instead (2026-09-25).
    val fastScroll = stringResource(R.string.photos_fast_scroll)
    Box(
        modifier
            .fillMaxHeight(0.64f)
            .width(48.dp)
            .onSizeChanged { size = it }
            .semantics { contentDescription = fastScroll }
            .pointerInput(itemCount, size) {
                detectTapGestures(onPress = { position ->
                    setInteracting(true)
                    scrollAt(position.y)
                    tryAwaitRelease()
                    setInteracting(false)
                })
            }
            .pointerInput(itemCount, size) {
                detectDragGestures(
                    onDragStart = { setInteracting(true); scrollAt(it.y) },
                    onDragEnd = { setInteracting(false) },
                    onDragCancel = { setInteracting(false) },
                ) { change, _ ->
                    scrollAt(change.position.y)
                    change.consume()
                }
            },
    ) {
        if (visible) {
            Box(
                Modifier.align(Alignment.Center).fillMaxHeight().width(12.dp).clip(CircleShape)
                    .background(islandColor()),
            )
            Box(
                Modifier.align(Alignment.TopCenter).padding(top = maxThumbOffset * ratio)
                    .size(width = 20.dp, height = thumbHeight).clip(CircleShape).background(islandContentColor().copy(alpha = 0.88f)),
            )
        }
    }
}

private fun Modifier.timelinePinch(scale: TimelineScale, setScale: (TimelineScale) -> Unit) = pointerInput(scale) {
    awaitEachGesture {
        var firstDistance: Float? = null
        var lastDistance = 0f
        do {
            val event = awaitPointerEvent()
            val fingers = event.changes.filter { it.pressed }
            if (fingers.size >= 2) {
                val distance = (fingers[0].position - fingers[1].position).getDistance()
                if (firstDistance == null) firstDistance = distance
                lastDistance = distance
            }
        } while (event.changes.any { it.pressed })
        val start = firstDistance
        if (start != null && start > 0f && lastDistance / start > 1.15f) setScale(scale.finer())
        if (start != null && start > 0f && lastDistance / start < 0.85f) setScale(scale.broader())
    }
}

/** What the photo database says of the photos on the device, read in one go off the screen's thread ([PhotoTab]). */
private class PhotoMeta(
    val states: Map<String, PhotoState>, val collections: List<PhotoCollection>, val previews: Map<Long, Entry?>, val documentKeys: Set<String>,
    val peopleKeys: Set<String>, val labels: Map<String, List<String>>, val peopleNames: Map<String, List<String>>, val faceGroups: List<FaceGroup>,
    val reviewKeys: Set<String>, val hiddenAlbumKeys: Set<String>,
) {
    companion object {
        fun read(context: Context, store: PhotoMetadataStore, all: List<Entry>, hiddenAlbums: Set<String>): PhotoMeta {
            val keys = all.map(Entry::photoKey)
            val onDevice = keys.toHashSet()
            val byKey = all.associateBy(Entry::photoKey)
            val keysOf = store.collections().associateWith { store.collectionKeys(it.id) }
            // A collection counts what is on this device: after a Move its other photos are on the computer, and a tile that
            // said 52 over an empty grid read as a broken library (25 September).
            val collections = keysOf.map { (c, k) -> val here = k.count { it in onDevice }; c.copy(here = here, elsewhere = k.size - here) }
            return PhotoMeta(
                states = store.states(keys), collections = collections,
                previews = keysOf.entries.associate { (c, k) -> c.id to k.firstNotNullOfOrNull(byKey::get) },
                documentKeys = store.classifiedKeys("document"), peopleKeys = store.peopleKeys(),
                labels = store.labelsByPhoto(keys), peopleNames = store.peopleNamesByPhoto(keys),
                // Only people who still have a photo here: delete or move every photo of someone and they stop being listed.
                faceGroups = store.faceGroups(onDevice, onComputer = SyncStore(context.applicationContext).use { it.receiptKeys() }),
                reviewKeys = store.reviewKeys(),
                hiddenAlbumKeys = keysOf.filterKeys { it.uuid in hiddenAlbums }.values.flatMapTo(mutableSetOf()) { it },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PhotoThumbnail(entry: Entry, selected: Boolean, modifier: Modifier = Modifier, longPress: (() -> Unit)? = null, open: () -> Unit) {
    val context = LocalContext.current
    val halfPixel = with(LocalDensity.current) { (0.5f / density).dp }
    val bitmap by produceState<Bitmap?>(initialValue = null, entry.contentUri) {
        value = entry.contentUri?.let { uri ->
            withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.loadThumbnail(uri, android.util.Size(240, 240), null) }.getOrNull()
                    ?: runCatching {
                        if (entry.isVideo) MediaMetadataRetriever().let { retriever ->
                            try {
                                retriever.setDataSource(context, uri)
                                retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            } finally { retriever.release() }
                        } else ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
                            decoder.setTargetSize(240, 240)
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        }
                    }.getOrNull()
            }
        }
    }
    val described = stringResource(if (entry.isVideo) R.string.photos_video_named else R.string.photos_photo_named, entry.name)
    val selectedWords = stringResource(if (selected) R.string.files_selected_one else R.string.photos_not_selected)
    Box(
        modifier
            .padding(halfPixel)
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary) else Modifier)
            .then(if (longPress == null) Modifier.clickable(onClick = open) else Modifier.combinedClickable(onClick = open, onLongClick = longPress))
            .semantics {
                contentDescription = described
                this.selected = selected
                stateDescription = selectedWords
            },
    ) {
        val image = bitmap
        if (image != null) {
            androidx.compose.foundation.Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (entry.isVideo) Surface(
            color = Color.Black.copy(alpha = 0.65f),
            contentColor = Color.White,
            shape = CircleShape,
            modifier = Modifier.align(Alignment.Center),
        ) { Text("▶", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) }
        if (selected) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)))
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) { Text("✓", color = MaterialTheme.colorScheme.onPrimary) }
        }
    }
}

private fun Modifier.timelineSelection(
    gridState: LazyGridState,
    photosByKey: Map<String, Entry>,
    addSelection: (Entry) -> Unit,
    longPressed: java.util.concurrent.atomic.AtomicReference<Uri?>,
) = pointerInput(gridState, photosByKey) {
    kotlinx.coroutines.coroutineScope {
        var startedOnPhoto = false
        var lastPosition: androidx.compose.ui.geometry.Offset? = null
        var autoScroll: Job? = null
        var autoScrollDirection = 0f
        fun entryAt(position: androidx.compose.ui.geometry.Offset): Entry? = gridState.layoutInfo.visibleItemsInfo
            .firstOrNull { info ->
                val key = info.key as? String
                key != null && key.startsWith("photo:") &&
                    position.x >= info.offset.x && position.x < info.offset.x + info.size.width &&
                    position.y >= info.offset.y && position.y < info.offset.y + info.size.height
            }
            ?.let { photosByKey[it.key as String] }
        fun stopAutoScroll() {
            autoScroll?.cancel()
            autoScroll = null
            autoScrollDirection = 0f
        }
        fun updateAutoScroll(position: androidx.compose.ui.geometry.Offset) {
            val layout = gridState.layoutInfo
            val direction = when {
                position.y < layout.viewportStartOffset + 72f -> -1f
                position.y > layout.viewportEndOffset - 72f -> 1f
                else -> 0f
            }
            if (direction == 0f) {
                stopAutoScroll()
            } else if (autoScroll == null || autoScrollDirection != direction) {
                stopAutoScroll()
                autoScrollDirection = direction
                autoScroll = launch {
                    try {
                        while (isActive) {
                            val moved = gridState.scrollBy(direction * 24f)
                            lastPosition?.let(::entryAt)?.let(addSelection)
                            if (moved == 0f) break
                            delay(16)
                        }
                    } finally {
                        if (autoScrollDirection == direction) {
                            autoScroll = null
                            autoScrollDirection = 0f
                        }
                    }
                }
            }
        }
        detectDragGesturesAfterLongPress(
            onDragStart = {
                val entry = entryAt(it)
                startedOnPhoto = entry != null
                lastPosition = it
                longPressed.set(entry?.contentUri)
                entry?.let(addSelection)
            },
            onDrag = { change, _ ->
                longPressed.set(null) // a drag cancels the tap, so there is no release to swallow
                if (startedOnPhoto) {
                    lastPosition = change.position
                    entryAt(change.position)?.let(addSelection)
                    updateAutoScroll(change.position)
                }
                change.consume()
            },
            onDragEnd = { startedOnPhoto = false; lastPosition = null; stopAutoScroll() },
            onDragCancel = { startedOnPhoto = false; lastPosition = null; longPressed.set(null); stopAutoScroll() },
        )
    }
}

private class ViewerZoomState {
    var scale by mutableStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }
}

private const val NO_TEXT = "\u0000none"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoViewer(
    entries: List<Entry>, selectedUri: Uri, select: (Uri) -> Unit, close: () -> Unit,
    setFavorite: (List<Entry>, Boolean) -> String?, addToCollection: ((Set<Uri>) -> Unit)?,
    restore: ((Entry) -> Unit)?,
    removeFromCollection: ((Entry) -> Unit)?,
    metadata: Map<String, PhotoState>, labelsByPhoto: Map<String, List<String>>, peopleNamesByPhoto: Map<String, List<String>>,
    metadataStore: PhotoMetadataStore, hasLocationAccess: Boolean, requestLocationAccess: () -> Unit,
    locationsUpdated: () -> Unit, openMap: (Entry) -> Unit,
    editPhoto: ((Entry, PhotoHide?) -> Unit)?,
) {
    val context = LocalContext.current
    val filmstripState = rememberLazyListState()
    val selected = entries.firstOrNull { it.contentUri == selectedUri }
    if (selected == null) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = close) { Text(stringResource(R.string.common_back), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(stringResource(R.string.photos_unavailable), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.photos_unavailable_body))
        }
        return
    }
    val initialPage = entries.indexOfFirst { it.contentUri == selectedUri }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialPage) { entries.size }
    val currentPage = pagerState.currentPage
    var detailsOpen by remember(selectedUri) { mutableStateOf(false) }
    var photoText by remember(selectedUri) { mutableStateOf<String?>(null) } // "" while reading
    val ocrScope = rememberCoroutineScope()
    photoText?.let { t ->
        val pick = remember(t) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(t)) }
        AlertDialog(onDismissRequest = { photoText = null }, title = { Text(stringResource(R.string.photos_text_in_photo)) },
            text = { if (t.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp)); Text(stringResource(R.string.photos_reading_text)) }
                else if (t == NO_TEXT) Text(stringResource(R.string.photos_no_text))
                else ChoosableText(pick) },
            confirmButton = { if (t.isNotEmpty() && t != NO_TEXT) TextButton(onClick = {
                context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("Text", pick.value.chosen()))
                Toast.makeText(context, context.getString(R.string.photos_copied), Toast.LENGTH_SHORT).show(); photoText = null }) { Text(stringResource(if (pick.value.selection.collapsed) R.string.photos_copy_all else R.string.photos_copy_selected), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            dismissButton = { TextButton(onClick = { photoText = null }) { Text(stringResource(R.string.common_close), maxLines = 1, overflow = TextOverflow.Ellipsis) } })
    }
    var actionError by remember(selectedUri) { mutableStateOf<String?>(null) }
    var manualFullscreen by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    val videoPlayback = remember { mutableMapOf<String, VideoPlaybackState>() }
    val imageZoom = remember { mutableMapOf<String, ViewerZoomState>() }
    val selectedZoom = imageZoom.getOrPut(selected.photoKey) { ViewerZoomState() }
    val location by produceState<PhotoLocation?>(initialValue = null, selected.photoKey, hasLocationAccess) {
        value = if (hasLocationAccess) withContext(Dispatchers.IO) { loadPhotoLocation(context, selected, metadataStore) } else null
        if (hasLocationAccess) locationsUpdated()
    }
    // Motion photos: the paired video (selected.motion) or the one inside the file, played in place of the picture.
    // The Motion button plays it now; Gallery tools › Autoplay motion photos (off by default, asked 2026-09-27) on opening.
    val autoplayMotion = remember { metadataStore.motionPlays() }
    var motionPlaying by remember(selectedUri) { mutableStateOf(autoplayMotion) }
    val motionUri by produceState<Uri?>(initialValue = null, selected.photoKey) {
        value = if (selected.isVideo) null else selected.motion ?: withContext(Dispatchers.IO) { embeddedMotion(context, selected) }
    }
    val zoomMode = !selected.isVideo && selectedZoom.scale > 1f
    val rotatedLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val fullscreen = zoomMode || manualFullscreen || rotatedLandscape
    BackHandler {
        when {
            zoomMode -> selectedZoom.reset()
            manualFullscreen -> manualFullscreen = false
            else -> close()
        }
    }
    LaunchedEffect(zoomMode) { if (!zoomMode) controlsVisible = true }
    val activity = context as? Activity
    androidx.compose.runtime.DisposableEffect(fullscreen, activity) {
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
        if (fullscreen) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (fullscreen) controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    LaunchedEffect(selectedUri, entries) {
        val target = entries.indexOfFirst { it.contentUri == selectedUri }
        if (target >= 0 && target != pagerState.currentPage) pagerState.scrollToPage(target)
    }
    LaunchedEffect(currentPage, entries) {
        entries.getOrNull(currentPage)?.contentUri?.takeIf { it != selectedUri }?.let(select)
    }
    LaunchedEffect(selectedUri, entries) {
        val selectedIndex = entries.indexOfFirst { it.contentUri == selectedUri }
        if (selectedIndex >= 0) {
            filmstripState.scrollToItem(selectedIndex)
            val item = filmstripState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == selectedIndex }
            if (item != null) {
                val viewportCenter = (filmstripState.layoutInfo.viewportStartOffset + filmstripState.layoutInfo.viewportEndOffset) / 2
                filmstripState.scrollBy(item.offset + item.size / 2f - viewportCenter)
            }
        }
    }
    val pager: @Composable (Modifier) -> Unit = { modifier ->
        ViewerPager(
            entries = entries,
            state = pagerState,
            playback = videoPlayback,
            imageZoom = imageZoom,
            zoomMode = zoomMode,
            modifier = modifier,
            background = if (fullscreen) Color.Black else MaterialTheme.colorScheme.surfaceVariant,
            toggleControls = { if (fullscreen && !zoomMode) controlsVisible = !controlsVisible },
            showDetails = { detailsOpen = true },
            motion = motionUri.takeIf { motionPlaying },
            motionEnded = { motionPlaying = false },
        )
    }
    val readText: () -> Unit = { selected.contentUri?.let { uri ->
                // the photo's words, Greek and English (Ocr, the PDF editor's reader; the user, 22 Oct: "OCR would be nice for photos")
                photoText = "" ; ocrScope.launch {
                    photoText = withContext(Dispatchers.Default) { runCatching {
                        val bmp = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(context.contentResolver, uri)) { dec, info, _ ->
                            val k = minOf(3f, 2400f / maxOf(info.size.width, info.size.height)); dec.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1)) // small pictures enlarged: Tesseract misses letters under ~20 px
                            dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE }
                        Ocr.text(context, bmp).trim()
                    }.onFailure { WriterLog.e("Photos: text", it) }.getOrDefault("") }.ifEmpty { NO_TEXT }
                } } }
    val actions: @Composable (Modifier) -> Unit = { modifier ->
        ViewerActionsIsland(
            favorite = metadata[selected.photoKey].orDefault().favorite,
            isVideo = selected.isVideo && (!rotatedLandscape || manualFullscreen),
            fullscreen = fullscreen,
            share = { sharePhotos(context, listOf(selected)) },
            details = { detailsOpen = true },
            toggleFavorite = { actionError = setFavorite(listOf(selected), !metadata[selected.photoKey].orDefault().favorite) },
            add = addToCollection?.let { action -> { selected.contentUri?.let { action(setOf(it)) } } },
            restore = restore?.let { action -> { action(selected) } },
            removeFromCollection = removeFromCollection?.let { action -> { action(selected) } },
            edit = editPhoto?.takeIf { !selected.isVideo }?.let { open -> { open(selected, null) } },
            toggleFullscreen = { manualFullscreen = !manualFullscreen },
            modifier = modifier,
        )
    }
    val toggleMotion: (() -> Unit)? = motionUri?.let { { motionPlaying = !motionPlaying } }
    if (fullscreen) Box(Modifier.fillMaxSize().background(Color.Black)) {
        pager(Modifier.fillMaxSize())
        AnimatedVisibility(controlsVisible && !zoomMode, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Surface(
                    color = islandColor(), contentColor = islandContentColor(), shape = CircleShape,
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                ) { IconButton(onClick = { if (manualFullscreen) manualFullscreen = false else close() }) {
                    Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_back))
                } }
                toggleMotion?.let { toggle -> Surface(
                    color = islandColor(), contentColor = islandContentColor(), shape = CircleShape,
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                ) { MotionSwitch(motionPlaying, toggle) } }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    ViewerFilmstrip(entries, selectedUri, filmstripState, select)
                    actions(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
    } else Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ViewerHeader(selected, close) { toggleMotion?.let { MotionSwitch(motionPlaying, it) } }
        pager(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp))
        ViewerFilmstrip(entries, selectedUri, filmstripState, select)
        actions(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 12.dp))
    }
    val aiTags = labelsByPhoto[selected.photoKey].orEmpty().joinToString(" · ").ifBlank { stringResource(R.string.photos_not_analyzed) }
    if (detailsOpen) ModalBottomSheet(onDismissRequest = { detailsOpen = false }) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // the photo's own tools, under the island (the user, 22 Oct: "Place ocr and reduct eyes and text options")
            if (!selected.isVideo) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { detailsOpen = false; readText() }) { Icon(painterResource(R.drawable.ic_text_fields), null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.photos_text_in_photo), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                editPhoto?.let { open ->
                    OutlinedButton(onClick = { detailsOpen = false; open(selected, PhotoHide.Eyes) }) { Icon(painterResource(R.drawable.ic_visibility_off), null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.photos_hide_eyes), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    OutlinedButton(onClick = { detailsOpen = false; open(selected, PhotoHide.Text) }) { Icon(painterResource(R.drawable.ic_text_fields), null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.photos_hide_text), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            Text(stringResource(R.string.photos_details), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.photos_detail_name, selected.name))
            Text(stringResource(R.string.photos_detail_file, selected.relativePath ?: stringResource(R.string.photos_detail_unavailable)))
            if (!hasLocationAccess) Button(onClick = requestLocationAccess) { Text(stringResource(R.string.photos_allow_locations), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            else location?.let { photoLocation ->
                Text(stringResource(R.string.photos_detail_place, photoLocation.placeName ?: stringResource(R.string.photos_detail_not_named)))
                PhotoLocationPreview(photoLocation) { openMap(selected) }
                Text(stringResource(R.string.photos_detail_coordinates, "${"%.5f".format(photoLocation.latitude)}, ${"%.5f".format(photoLocation.longitude)}"))
                Button(onClick = { openMap(selected) }) { Text(stringResource(R.string.photos_show_on_map), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            } ?: Text(stringResource(R.string.photos_detail_no_location))
            Text(stringResource(R.string.photos_detail_date, formatPhotoDateTime(selected.takenMillis)))
            // the size the listing gave, in the user's words (the entry's detail ends "· N bytes"; a shared one has none)
            Text(stringResource(R.string.photos_detail_size, if (selected.detail.isEmpty()) "" else stringResource(R.string.files_bytes, selected.sizeBytes)))
            Text(stringResource(R.string.photos_detail_tags, aiTags))
            peopleNamesByPhoto[selected.photoKey]?.takeIf { it.isNotEmpty() }?.let { Text(stringResource(R.string.photos_detail_people, it.map { n -> personName(n) }.joinToString(" · "))) }
        }
    }
    actionError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
}

@Composable
private fun MotionSwitch(on: Boolean, toggle: () -> Unit) = IconButton(onClick = toggle) {
    Icon(painterResource(if (on) R.drawable.ic_motion else R.drawable.ic_motion_off), contentDescription = stringResource(if (on) R.string.photos_motion_stop else R.string.photos_motion_play),
        tint = if (on) MaterialTheme.colorScheme.primary else LocalContentColor.current)
}

@Composable
private fun ViewerHeader(entry: Entry, close: () -> Unit, end: @Composable () -> Unit = {}) = Surface(
    color = islandColor(), contentColor = islandContentColor(), shape = MaterialTheme.shapes.extraLarge,
    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp),
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = close) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_back)) }
        Column(Modifier.weight(1f)) {
            Text(formatPhotoDateTime(entry.takenMillis), style = MaterialTheme.typography.titleSmall)
            Text(entry.name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        end()
    }
}

@Composable
private fun ViewerFilmstrip(entries: List<Entry>, selectedUri: Uri, state: androidx.compose.foundation.lazy.LazyListState, select: (Uri) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        state = state,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(entries, key = { it.contentUri.toString() }) { entry ->
            FilmstripThumbnail(entry, entry.contentUri == selectedUri) { entry.contentUri?.let(select) }
        }
    }
}

@Composable
private fun ViewerPager(
    entries: List<Entry>, state: PagerState, playback: MutableMap<String, VideoPlaybackState>,
    imageZoom: MutableMap<String, ViewerZoomState>, zoomMode: Boolean, modifier: Modifier,
    background: Color, toggleControls: () -> Unit, showDetails: () -> Unit,
    motion: Uri? = null, motionEnded: () -> Unit = {},
) {
    HorizontalPager(state = state, modifier = modifier, userScrollEnabled = !zoomMode, key = { entries[it].contentUri.toString() }) { page ->
        val entry = entries[page]
        if (entry.isVideo) {
            if (page == state.currentPage) ViewerVideo(entry, Modifier.fillMaxSize(), playback.getOrPut(entry.photoKey) { VideoPlaybackState() }, toggleControls)
            else Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_play), contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
            }
        } else if (motion != null && page == state.currentPage) MotionVideo(motion, Modifier.fillMaxSize(), motionEnded)
        else ViewerImage(entry, Modifier.fillMaxSize(), toggleControls, showDetails, background, imageZoom.getOrPut(entry.photoKey) { ViewerZoomState() })
    }
}

@Composable
private fun ViewerActionsIsland(
    favorite: Boolean, isVideo: Boolean, fullscreen: Boolean,
    share: () -> Unit, details: () -> Unit, toggleFavorite: () -> Unit, add: (() -> Unit)?,
    restore: (() -> Unit)?, removeFromCollection: (() -> Unit)?, edit: (() -> Unit)?,
    toggleFullscreen: () -> Unit, modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(true) }
    Surface(color = islandColor(), contentColor = islandContentColor(), shape = MaterialTheme.shapes.extraLarge, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(22.dp).clickable { expanded = !expanded }, contentAlignment = Alignment.Center) {
                Box(Modifier.width(34.dp).height(4.dp).clip(CircleShape).background(islandContentColor().copy(alpha = 0.72f)))
            }
            AnimatedVisibility(expanded) {
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton(onClick = share) { Icon(painterResource(R.drawable.ic_share), contentDescription = stringResource(R.string.common_share)) }
                    IconButton(onClick = details) { Icon(painterResource(R.drawable.ic_info), contentDescription = stringResource(R.string.photos_details)) }
                    IconButton(onClick = toggleFavorite) { Icon(painterResource(if (favorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border), contentDescription = stringResource(R.string.photos_toggle_favorite)) }
                    add?.let { action -> IconButton(onClick = action) {
                        Icon(painterResource(R.drawable.ic_add_to_collection), contentDescription = stringResource(R.string.photos_add_to_collection))
                    } }
                    restore?.let { action -> IconButton(onClick = action) { Icon(painterResource(R.drawable.ic_restore), contentDescription = stringResource(R.string.photos_restore_hidden)) } }
                    removeFromCollection?.let { action -> IconButton(onClick = action) {
                        Icon(painterResource(R.drawable.ic_remove_from_collection), contentDescription = stringResource(R.string.photos_remove_from_collection))
                    } }
                    edit?.let { IconButton(onClick = it) { Icon(painterResource(R.drawable.ic_edit), contentDescription = stringResource(R.string.common_edit)) } }
                    if (isVideo) IconButton(onClick = toggleFullscreen) {
                        Icon(painterResource(if (fullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen), contentDescription = stringResource(if (fullscreen) R.string.photos_exit_fullscreen else R.string.photos_fullscreen))
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerImage(
    entry: Entry,
    modifier: Modifier = Modifier,
    toggleControls: () -> Unit = {},
    showDetails: () -> Unit = {},
    background: Color = Color.Unspecified,
    zoom: ViewerZoomState? = null,
) {
    val context = LocalContext.current
    val zoomState = zoom ?: remember(entry.contentUri) { ViewerZoomState() }
    val viewerBackground = if (background == Color.Unspecified) MaterialTheme.colorScheme.surfaceVariant else background
    BoxWithConstraints(modifier.background(viewerBackground)) {
        val density = LocalDensity.current
        val targetWidth = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val targetHeight = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)
        val transform = rememberTransformableState { _, zoomChange, pan, _ ->
            val nextScale = (zoomState.scale * zoomChange).coerceIn(1f, 5f)
            val maxX = (nextScale - 1f) * targetWidth / 2f
            val maxY = (nextScale - 1f) * targetHeight / 2f
            zoomState.scale = nextScale
            zoomState.offset = if (nextScale == 1f) Offset.Zero else Offset(
                (zoomState.offset.x + pan.x).coerceIn(-maxX, maxX),
                (zoomState.offset.y + pan.y).coerceIn(-maxY, maxY),
            )
        }
        val bitmap by produceState<Bitmap?>(initialValue = null, entry.contentUri, entry.sizeBytes, targetWidth, targetHeight) {
            value = entry.contentUri?.let { uri ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                            val source = info.size
                            val scale = minOf(1f, targetWidth.toFloat() / source.width, targetHeight.toFloat() / source.height)
                            decoder.setTargetSize(
                                (source.width * scale).roundToInt().coerceAtLeast(1),
                                (source.height * scale).roundToInt().coerceAtLeast(1),
                            )
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        }
                    }.getOrNull()
                }
            }
        }
        val infoGesture = if (zoomState.scale == 1f) Modifier.pointerInput(entry.contentUri) {
            var verticalDrag = 0f
            detectVerticalDragGestures(
                onVerticalDrag = { _, amount -> verticalDrag += amount },
                onDragEnd = { if (verticalDrag < -80f) showDetails() },
            )
        } else Modifier
        val image = bitmap
        if (image == null) Text(stringResource(R.string.photos_photo_unavailable), modifier = Modifier.padding(16.dp)) else androidx.compose.foundation.Image(
            bitmap = image.asImageBitmap(),
            contentDescription = entry.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
                .graphicsLayer(scaleX = zoomState.scale, scaleY = zoomState.scale, translationX = zoomState.offset.x, translationY = zoomState.offset.y)
                .transformable(state = transform, canPan = { zoomState.scale > 1f })
                .then(infoGesture)
                .pointerInput(entry.contentUri) {
                    detectTapGestures(
                        onTap = { if (zoomState.scale == 1f) toggleControls() },
                        onDoubleTap = {
                            if (zoomState.scale > 1f) zoomState.reset() else zoomState.scale = 2f
                        },
                    )
                },
        )
    }
}

/** A motion photo's few seconds, played once in place of the picture; a tap or the end brings the picture back. */
@Composable
private fun MotionVideo(uri: Uri, modifier: Modifier, ended: () -> Unit) {
    key(uri) {
        AndroidView(
            factory = { viewContext ->
                VideoView(viewContext).apply {
                    setVideoURI(uri)
                    setOnPreparedListener { start() }
                    setOnCompletionListener { ended() }
                    setOnErrorListener { _, _, _ -> ended(); true }
                    setOnClickListener { ended() }
                }
            },
            modifier = modifier.background(Color.Black),
            onRelease = { it.stopPlayback() },
        )
    }
}

/**
 * The video inside a motion photo (Pixel, Samsung, Xiaomi), cut from the end of the picture into the app's cache —
 * one file at a time, replaced by the next photo's. Null when the picture has none.
 */
internal fun embeddedMotion(context: Context, entry: Entry): Uri? = runCatching {
    if (!Regex("""\.(jpe?g|heic|heif)$""", RegexOption.IGNORE_CASE).containsMatchIn(entry.name)) return null
    val bytes = context.contentResolver.openInputStream(entry.contentUri ?: return null)?.use { it.readBytes() } ?: return null
    val at = MotionRules.embeddedVideoOffset(bytes)
    if (at <= 0) return null
    val dir = File(context.cacheDir, "motion").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
    val file = File(dir, "${entry.photoKey.hashCode()}.mp4")
    file.outputStream().use { it.write(bytes, at, bytes.size - at) }
    Uri.fromFile(file)
}.getOrNull()

private class VideoPlaybackState(var positionMs: Int = 0, var playWhenReady: Boolean = true)

@Composable
private fun ViewerVideo(entry: Entry, modifier: Modifier = Modifier, playback: VideoPlaybackState, toggleControls: () -> Unit = {}) {
    val uri = entry.contentUri ?: return
    key(uri) {
        AndroidView(
            factory = { viewContext ->
                VideoView(viewContext).apply {
                    val controller = MediaController(viewContext)
                    controller.setAnchorView(this)
                    setMediaController(controller)
                    setOnClickListener { toggleControls() }
                    setVideoURI(uri)
                    setOnPreparedListener {
                        if (playback.positionMs > 0) seekTo(playback.positionMs)
                        if (playback.playWhenReady) start()
                    }
                    setOnCompletionListener {
                        playback.positionMs = 0
                        playback.playWhenReady = false
                    }
                }
            },
            modifier = modifier.background(Color.Black),
            onRelease = { video ->
                playback.positionMs = video.currentPosition
                playback.playWhenReady = video.isPlaying
                video.stopPlayback()
            },
        )
    }
}

@Composable
private fun FilmstripThumbnail(entry: Entry, selected: Boolean, choose: () -> Unit) {
    val context = LocalContext.current
    // Keyed on size too: a photo replaced by the editor keeps its URI.
    val bitmap by produceState<Bitmap?>(initialValue = null, entry.contentUri, entry.sizeBytes) {
        value = entry.contentUri?.let { uri ->
            withContext(Dispatchers.IO) { runCatching { context.contentResolver.loadThumbnail(uri, android.util.Size(120, 120), null) }.getOrNull() }
        }
    }
    val described = stringResource(R.string.photos_view_named, entry.name)
    val selectedWords = stringResource(if (selected) R.string.files_selected_one else R.string.photos_not_selected)
    Box(
        Modifier.size(52.dp).clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = choose)
            .semantics {
                contentDescription = described
                this.selected = selected
                stateDescription = selectedWords
            },
    ) {
        val image = bitmap
        if (image != null) androidx.compose.foundation.Image(
            bitmap = image.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        )
        if (selected) Box(Modifier.fillMaxSize().border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small))
    }
}

/** Several files at once; one file still uses ACTION_SEND, which is what most apps handle best. */
private fun shareDriveFiles(context: Context, files: List<File>) {
    val real = files.filter { it.isFile }
    if (real.isEmpty()) return
    if (real.size == 1) return shareDriveFile(context, real.first())
    val uris = ArrayList(real.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) })
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        clipData = ClipData.newRawUri(real.first().name, uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, context.resources.getQuantityString(R.plurals.files_share_files, real.size, real.size)))
}

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

/** Shows one photo or video handed over by another app that is not in the Gallery (e.g. a chat attachment). */
@Composable
private fun ExternalMediaViewer(media: ExternalMedia, close: () -> Unit) {
    val context = LocalContext.current
    val entry = remember(media) {
        Entry(name = media.uri.lastPathSegment ?: context.getString(R.string.photos_photo), detail = "", contentUri = media.uri, photoKey = media.uri.toString(), isVideo = media.mimeType?.startsWith("video/") == true)
    }
    val playback = remember { VideoPlaybackState() }
    BackHandler(onBack = close)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (entry.isVideo) ViewerVideo(entry, Modifier.fillMaxSize(), playback)
        else ViewerImage(entry, Modifier.fillMaxSize(), background = Color.Black)
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                IconButton(onClick = close) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_back)) }
            }
            Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                IconButton(onClick = { sharePhotos(context, listOf(entry)) }) { Icon(painterResource(R.drawable.ic_share), contentDescription = stringResource(R.string.common_share)) }
            }
        }
    }
}

private fun sharePhotos(context: Context, entries: List<Entry>) {
    val uris = entries.mapNotNull { it.contentUri }
    if (uris.isEmpty()) return
    context.startActivity(Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
        type = context.contentResolver.getType(uris.first()) ?: "image/*"
        if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        clipData = ClipData.newRawUri("photo", uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }.let { Intent.createChooser(it, context.getString(R.string.photos_share)) })
}

@Composable private fun formatPhotoDateTime(takenMillis: Long): String = if (takenMillis > 0) {
    DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.getDefault())
        .format(Instant.ofEpochMilli(takenMillis).atZone(ZoneId.systemDefault()))
} else stringResource(R.string.photos_date_unavailable)

/** A random real photo for the Home card: never a screenshot or a photo the local analysis filed as a document. */
private fun randomGalleryThumbnail(context: Context, documentKeys: Set<String>): Bitmap? = runCatching {
    listPhotos(context)
        .filter { !it.isVideo && !it.isScreenshot() && it.photoKey !in documentKeys }
        .randomOrNull()
        ?.contentUri
        ?.let { context.contentResolver.loadThumbnail(it, android.util.Size(720, 720), null) }
}.getOrNull()

/**
 * What Android is holding in its trash for this app. Trashed media is deliberately absent from the ordinary
 * MediaStore listing, so Trash is its own query rather than a filter over the timeline. Android keeps these for
 * 30 days and deletes them itself; restoring and deleting early both go through its own confirmation.
 */
internal fun listTrashedPhotos(context: Context): List<Entry> = PhotoMetadataStore(context).use { it.folderChoices() }.let { choices ->
    (listGalleryMedia(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, trashed = true) +
        listGalleryMedia(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, trashed = true))
        .filter { FolderRules.isIncluded(it.relativePath, choices) }
        .sortedByDescending { it.takenMillis } // newest first, like the timeline (MediaStore hands them oldest first)
}

/** Every photo and video in a photo folder of this phone, included or not: what the folder questions are about. */
internal fun listDeviceMedia(context: Context): List<Entry> = (
    listGalleryMedia(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false) +
        listGalleryMedia(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
).sortedByDescending { it.takenMillis }

/** The Gallery's view: a motion photo's video half leaves the list and rides on its picture as `motion`. */
internal fun showMotionAsOne(entries: List<Entry>): List<Entry> {
    val pairs = MotionRules.pairs(entries.map { Triple(it.relativePath, it.name, it.isVideo) })
    if (pairs.isEmpty()) return entries
    val videoOf = pairs.entries.associate { (video, picture) -> picture to entries[video].contentUri }
    return entries.mapIndexedNotNull { i, e -> if (i in pairs) null else videoOf[i]?.let { e.copy(motion = it) } ?: e }
}

/** The library: the default folders and the ones you said yes to. Gallery, backup and analysis all read this. */
internal fun listPhotos(context: Context): List<Entry> = PhotoMetadataStore(context).use { it.folderChoices() }.let { choices ->
    listDeviceMedia(context).filter { FolderRules.isIncluded(it.relativePath, choices) }
}

private fun listGalleryMedia(context: Context, collection: Uri, isVideo: Boolean, trashed: Boolean = false): List<Entry> {
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.RELATIVE_PATH,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.DATE_MODIFIED,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
        MediaStore.MediaColumns.ORIENTATION,
    )
    // Everything on the phone's own storage; which folders count is FolderRules' decision, made once for every
    // caller. A real SD card or USB stick is another volume and is never read: it will not always be there.
    val arguments = android.os.Bundle().apply {
        putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.VOLUME_NAME} = ?")
        putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(MediaStore.VOLUME_EXTERNAL_PRIMARY))
        if (trashed) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
    }
    return context.contentResolver.query(collection, projection, arguments, null)?.use { cursor ->
        buildList { while (cursor.moveToNext()) {
            val path = cursor.string(2)
            if (FolderRules.albumName(path) == null) continue // files, not photos
            val sizeBytes = cursor.long(3)
            val uri = android.content.ContentUris.withAppendedId(collection, cursor.long(0))
            val takenMillis = cursor.long(4).takeIf { it > 0 } ?: cursor.long(5) * 1000
            val upright = cursor.long(8) % 180L == 0L
            add(Entry(
                name = cursor.string(1),
                detail = "$path · $sizeBytes bytes",
                relativePath = path,
                contentUri = uri,
                takenMillis = takenMillis,
                sizeBytes = sizeBytes,
                photoKey = PhotoMetadataRules.stableKey(uri.toString(), path, cursor.string(1), sizeBytes),
                isVideo = isVideo,
                // MediaStore reports the size as the file stores it; a quarter-turn in EXIF means the photo every
                // other part of the app sees (and every face box) is the other way round.
                width = if (upright) cursor.long(6).toInt() else cursor.long(7).toInt(),
                height = if (upright) cursor.long(7).toInt() else cursor.long(6).toInt(),
            ))
        } }
    } ?: error("MediaStore query failed")
}

private fun Cursor.string(index: Int) = getString(index) ?: "Unnamed"
private fun Cursor.long(index: Int) = if (isNull(index)) 0L else getLong(index)
