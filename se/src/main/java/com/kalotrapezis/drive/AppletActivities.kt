package com.kalotrapezis.drive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat

/**
 * An applet in a window of its own, as the Browser's (the user, 4 Nov: "can we separate the applets like you did with the
 * browser? … so not every activity has the Tetra icon"): its own task in Recents, with the name and icon the manifest gives it
 * (Home's art, res/mipmap-anydpi-v26/applet_*). Opened with Applet.EXTRA's word, as MainActivity reads it ([appletIntent]);
 * a word that asks for something (a note, a mail, a place) opens it afresh, its bare name brings it up as it was left.
 */
abstract class AppletActivity : ComponentActivity() {
    internal abstract val applet: Applet
    protected open val lightBars get() = TetraLook.light
    private val word = mutableStateOf<String?>(null)
    private val opened = mutableIntStateOf(0)
    @Composable protected abstract fun Content(word: String?, back: () -> Unit)

    protected fun grant() = startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.wrap(base))
    // opened: its news seen (Notices.seen), off its number on Home
    override fun onResume() { super.onResume(); runCatching { Notices.seen(this, Notices.homeOf(applet)) } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WriterLog.init(this)
        TetraLook.load(this)
        // the caches MainActivity names at its start: opened from its icon, it may not have run
        CalendarIndex.dir = cacheDir; NotesStore.indexDir = cacheDir
        WindowCompat.getInsetsController(window, window.decorView).run { isAppearanceLightStatusBars = lightBars; isAppearanceLightNavigationBars = lightBars }
        word.value = intent.getStringExtra(Applet.EXTRA)
        WriterLog.i("Applet window: $applet${word.value?.takeIf { it != applet.name }?.let { " (${it.substringBefore(':')})" }.orEmpty()}")
        setContent { TetraTheme { key(opened.intValue) { Content(word.value) { finish() } } } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val w = intent.getStringExtra(Applet.EXTRA)
        if (intent.data != null || (w != null && w != applet.name)) { word.value = w; opened.intValue++ }
    }
}

class NotesActivity : AppletActivity() {
    override val applet = Applet.Notes
    @Composable override fun Content(word: String?, back: () -> Unit) = NotesTab(back = back, hasAccess = storageAllowed(this), grant = ::grant,
        start = when (word) { NEW_NOTE -> false; NEW_CHECKLIST -> true; else -> null }, openId = word?.takeIf { it.startsWith("note:") }?.removePrefix("note:"))
    companion object { const val NEW_NOTE = "note-new"; const val NEW_CHECKLIST = "checklist-new" }
}

class CodesActivity : AppletActivity() {
    override val applet = Applet.Codes
    override val lightBars get() = false // the camera's dark frame in both looks
    @Composable override fun Content(word: String?, back: () -> Unit) = CodeScannerTab(back = back, pickImage = word == IMAGE)
    companion object { const val IMAGE = "codes-image" } // Home's Scanner widget: straight to a picture's code
}

class CalendarActivity : AppletActivity() {
    override val applet = Applet.Calendar
    @Composable override fun Content(word: String?, back: () -> Unit) = CalendarScreen(back = back, hasAccess = storageAllowed(this), grant = ::grant,
        startKind = word?.takeIf { it.startsWith(NEW) }?.let { k -> NewKind.entries.firstOrNull { it.name == k.removePrefix(NEW) } },
        openContact = { uid -> startActivity(appletIntent(this, "contact:$uid")) })
    companion object { const val NEW = "calendar-new:" } // Home's tiles: a new one of that kind
}

class TimerActivity : AppletActivity() {
    override val applet = Applet.Timer
    @Composable override fun Content(word: String?, back: () -> Unit) = TimerScreen(back = back, hasAccess = storageAllowed(this), grant = ::grant)
}

class PillsActivity : AppletActivity() {
    override val applet = Applet.Pills
    @Composable override fun Content(word: String?, back: () -> Unit) = PillsScreen(back = back, hasAccess = storageAllowed(this), grant = ::grant)
}

class MapsActivity : AppletActivity() {
    override val applet = Applet.Maps
    @Composable override fun Content(word: String?, back: () -> Unit) {
        val go = when {
            word == null -> null
            word.startsWith(PLACE) -> Place.parse(word.removePrefix(PLACE)) // a place on Home's Maps card: directions at once
            word.startsWith("maps-go:") -> word.removePrefix("maps-go:").split(",", limit = 3).takeIf { it.size == 3 }?.let { (lat, lng, name) -> Place("", name, lat.toDouble(), lng.toDouble()) }
            else -> null
        }
        MapsScreen(MapsFiles.root(driveRoot()), go, back = back, search = word?.takeIf { it.startsWith("maps-search:") }?.removePrefix("maps-search:"))
    }
    companion object { const val PLACE = "maps-place:" }
}

class ContactsActivity : AppletActivity() {
    override val applet = Applet.Contacts
    @Composable override fun Content(word: String?, back: () -> Unit) = ContactsScreen(back = back, hasAccess = storageAllowed(this), grant = ::grant,
        start = word?.takeIf { it.startsWith("contact:") }?.removePrefix("contact:"), newNumber = word?.takeIf { it.startsWith("contact-new:") }?.removePrefix("contact-new:"),
        openMaps = { q -> startActivity(appletIntent(this, "maps-search:$q")) })
}

class MailActivity : AppletActivity() {
    override val applet = Applet.Mail
    // a file, Settings › Mail and a folder are Tetra's own window's: asked of it, as AI Assist asks
    private fun tetra(word: String) = startActivity(Intent(this, MainActivity::class.java).putExtra(Applet.EXTRA, word)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    @Composable override fun Content(word: String?, back: () -> Unit) = MailScreen(start = word?.takeIf { it.startsWith("mail") }, back = back,
        hasAccess = storageAllowed(this), grant = ::grant,
        openFile = { f -> runCatching { tetra("file:" + DriveRules.relative(driveRoot(), f)) }.onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() } },
        openSettings = { tetra(SETTINGS) }, openFolder = { rel -> tetra("folder:$rel") })
    companion object { const val SETTINGS = "settings-mail" }
}

class SmsActivity : AppletActivity() {
    override val applet = Applet.Messages
    @Composable override fun Content(word: String?, back: () -> Unit) = SmsScreen(back = back, start = word?.takeIf { it.startsWith("sms:") })
}

class PasswordsActivity : AppletActivity() {
    override val applet = Applet.Passwords
    override fun onCreate(savedInstanceState: Bundle?) {
        // no screenshots, and Recents shows no thumbnail of the passwords (the council, 4 Nov)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
    }
    // "passwords:<host>": the Browser's key, a page's logins found at once; "passwords:2fa" the 2FA page
    @Composable override fun Content(word: String?, back: () -> Unit) = PasswordsScreen(back = back, start = word?.takeIf { it.startsWith(WORD) }?.removePrefix(WORD))
    companion object { const val WORD = "passwords:" }
}

/** Which applet window an Applet.EXTRA word opens; null: Tetra's own window (Photos, Files, Office, Settings…). */
private fun appletWindow(word: String): Class<*>? = when {
    word == Applet.Notes.name || word.startsWith("note:") || word == NotesActivity.NEW_NOTE || word == NotesActivity.NEW_CHECKLIST -> NotesActivity::class.java
    word == Applet.Codes.name || word == CodesActivity.IMAGE -> CodesActivity::class.java
    word == Applet.TextEditor.name || word.startsWith("text:") -> TextEditorActivity::class.java
    word == Applet.Calendar.name || word.startsWith(CalendarActivity.NEW) -> CalendarActivity::class.java
    word == Applet.Timer.name -> TimerActivity::class.java
    word == Applet.Pills.name -> PillsActivity::class.java
    word == Applet.Maps.name || word.startsWith("maps-") -> MapsActivity::class.java
    word == Applet.Contacts.name || word.startsWith("contact:") || word.startsWith("contact-new:") -> ContactsActivity::class.java
    word == Applet.Mail.name || word.startsWith("mail:") || word.startsWith("mail-write:") -> MailActivity::class.java
    word == Applet.Messages.name || word.startsWith("sms:") -> SmsActivity::class.java
    word == Applet.Browser.name -> BrowserActivity::class.java
    word == Applet.Passwords.name || word.startsWith(PasswordsActivity.WORD) -> PasswordsActivity::class.java
    word.startsWith("browser-play:") -> BrowserActivity::class.java
    word == Applet.Phone.name -> PhoneActivity::class.java
    word == Applet.Data.name -> DataActivity::class.java
    word == Applet.Assistant.name -> AssistantActivity::class.java
    else -> null
}

internal fun hasAppletWindow(word: String) = appletWindow(word) != null

/** What opens [word] (Applet.EXTRA's): its applet's own window, else Tetra's, which turns to it. */
internal fun appletIntent(c: Context, word: String): Intent {
    val window = appletWindow(word) ?: return Intent(c, MainActivity::class.java).putExtra(Applet.EXTRA, word)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    return Intent(c, window).putExtra(Applet.EXTRA, word).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .apply { if (word.startsWith("browser-play:")) putExtra(BrowserActivity.PLAY, word) }
}

internal fun openApplet(c: Context, word: String) {
    val i = appletIntent(c, word)
    // the car's build leaves the phone's applets out (Passwords, Contacts…): nothing opens rather than a crash, never the phone's screens
    if (CarMaps.edition && (i.component?.className == MainActivity::class.java.name || c.packageManager.resolveActivity(i, 0) == null)) { WriterLog.i("Car: $word is not on the car"); return }
    c.startActivity(i)
}
