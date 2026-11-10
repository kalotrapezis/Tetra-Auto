package com.kalotrapezis.drive
import androidx.compose.runtime.mutableStateOf
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import android.content.Intent
import androidx.compose.runtime.key
import androidx.core.view.WindowCompat
import android.os.Environment
import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import android.os.Bundle
import android.app.Activity
import android.content.ComponentName
import android.widget.Toast
import androidx.activity.compose.setContent
import android.content.pm.PackageManager
import android.net.Uri

/** Tetra Auto's parts: Maps, Phone (and its Contacts), Messages, the Browser and AI Assist; each in a window of its own. */
internal enum class Applet(@androidx.annotation.StringRes val label: Int, val art: Int) {
    Maps(R.string.app_maps, R.drawable.maps_art),
    Contacts(R.string.app_contacts, R.drawable.contacts_art),
    Phone(R.string.app_phone, R.drawable.phone_art),
    Assistant(R.string.app_assistant, R.drawable.assistant_art),
    Messages(R.string.app_sms, R.drawable.sms_art),
    Browser(R.string.app_browser, R.drawable.browser_art);

    companion object {
        const val EXTRA = "com.kalotrapezis.drive.APPLET"
        fun of(name: String?) = entries.firstOrNull { it.name == name }
    }
}
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

    protected fun grant() = grantStorage(this)
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.wrap(base))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WriterLog.init(this)
        TetraLook.load(this)
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

class SmsActivity : AppletActivity() {
    override val applet = Applet.Messages
    @Composable override fun Content(word: String?, back: () -> Unit) = SmsScreen(back = back, start = word?.takeIf { it.startsWith("sms:") })
}


/** Which applet window an Applet.EXTRA word opens; null: Tetra's own window (Photos, Files, Office, Settings…). */
private fun appletWindow(word: String): Class<*>? = when {
    word == Applet.Maps.name || word.startsWith("maps-") -> MapsActivity::class.java
    word == Applet.Contacts.name || word.startsWith("contact:") || word.startsWith("contact-new:") -> PhoneActivity::class.java // no Contacts applet in the car: the Phone has them
    word == Applet.Messages.name || word.startsWith("sms:") -> SmsActivity::class.java
    word == Applet.Browser.name -> BrowserActivity::class.java
    word.startsWith("browser-play:") -> BrowserActivity::class.java
    word == Applet.Phone.name -> PhoneActivity::class.java
    word == Applet.Assistant.name -> AssistantActivity::class.java
    else -> null
}

internal fun hasAppletWindow(word: String) = appletWindow(word) != null

/** What opens [word] (Applet.EXTRA's): its applet's own window, else Tetra's, which turns to it. */
internal fun appletIntent(c: Context, word: String): Intent {
    val window = appletWindow(word) ?: return Intent(c, CarActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return Intent(c, window).putExtra(Applet.EXTRA, word).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .apply { if (word.startsWith("browser-play:")) putExtra(BrowserActivity.PLAY, word) }
}

internal fun openApplet(c: Context, word: String) = c.startActivity(appletIntent(c, word))

/** Settings in a window of its own: Tetra Auto has no Home to hold it. [PAGE]: a page to open at (a SettingsPage name). */
class SettingsActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.wrap(base))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WriterLog.init(this)
        TetraLook.load(this)
        val start = SettingsPage.entries.firstOrNull { it.name == intent.getStringExtra(PAGE) }
        setContent { TetraTheme { SettingsTab(back = ::finish, start = start) } }
    }
    companion object { const val PAGE = "page" }
}
