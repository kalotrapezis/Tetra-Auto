package com.kalotrapezis.drive

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.BasicSelectionActionDelegate
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.MediaSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse

// Browser (the user's sketch, 2 Nov): the page fills the screen; under a line, the address bar, a round button and three
// small stacked ones, and under them three equal buttons. More slides that row into a strip with ‹ (Office's way).

internal const val BROWSER_SETTINGS = "settings-browser" // the sidebar's ⚙: Settings › Browser (MainActivity)
internal val BROWSER_COLOR = Color(0xFF1A87FD) // the blue of the user's icon (Assets/BrowserApp.png)
private val PRIVATE_COLOR = Color(0xFF2B2140)

private enum class BrowserPanel { Bookmarks, History, Import }

/**
 * The Browser in a window of its own (the user, 2 Nov: "I can't use the maps and the browser together, to play music in the
 * back and drive with maps in the front"): its own task, as the Phone's and Data's, so Tetra's Maps stays open beside it.
 * AI Assist's "browser-play:{site, q}" comes as [PLAY].
 */
class BrowserActivity : androidx.activity.ComponentActivity() {
    private val play = mutableStateOf<String?>(null)
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.wrap(base))
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        TetraLook.load(this)
        // dark window in Tetra's dark look: pages load on dark, not white
        if (!TetraLook.light) setTheme(android.R.style.Theme_Material_NoActionBar)
        super.onCreate(savedInstanceState)
        WriterLog.init(this)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = TetraLook.light
        play.value = intent.getStringExtra(PLAY)
        setContent { TetraTheme { BrowserScreen(back = { finish() }, play = play.value, hide = { moveTaskToBack(true) }) } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); intent.getStringExtra(PLAY)?.let { play.value = it } }
    companion object { const val PLAY = "com.kalotrapezis.drive.browser.PLAY" }
}

/** A page opened from Home's Browser widget: in its tab if one has it, else a new one; nothing played. */
internal fun openBrowserAt(c: Context, url: String) = openBrowser(c, "browser-play:" + JSONObject().put("url", url).put("open", true).put("at", System.currentTimeMillis()))

internal fun openBrowser(c: Context, play: String? = null) = c.startActivity(Intent(c, BrowserActivity::class.java).putExtra(BrowserActivity.PLAY, play)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))

/** [play]: AI Assist's "browser-play:{site, q}": that site's search, its first result played, in Player mode. */
@Composable
internal fun BrowserScreen(back: () -> Unit, play: String? = null, hide: () -> Unit = back) {
    val context = LocalContext.current
    var private by remember { mutableStateOf(false) }
    var normalUrl by remember { mutableStateOf("") } // the page Private left, opened again on its way out
    var said by remember { mutableStateOf<String?>(null) }
    key(private) {
        // leaving: behind the other apps with every tab as it is (the user, 2 Nov: "keep sites open on the other tabs"); Private closes, so it keeps nothing
        BrowserPage(private, if (private) "" else normalUrl, said, if (private) null else play, back = if (private) back else hide, setPrivate = { on, url ->
            if (on) { normalUrl = url; said = null; private = true } else { said = context.getString(R.string.browser_private_closed); private = false }
        })
    }
}

private data class PageMedia(val has: Boolean = false, val playing: Boolean = false, val title: String = "", val queue: Boolean = false)

/**
 * One tab (the user, 2 Nov: "the second row are the tabs"): a Gecko session and the view that shows it, and what it shows;
 * kept for the session only. Tetra's own extension talks to its page through [port] (assets/tetra-ext/content.js).
 */
private class BrowserTab(val id: Int, val session: GeckoSession, val view: GeckoView) {
    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    var pull by mutableFloatStateOf(0f) // pull to reload: how far, 1 = enough (half the page's height)
    var scrollY = 0 // the page's scroll, from Gecko: pull to reload only at its top
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var kinds by mutableStateOf(emptySet<String>())
    var media by mutableStateOf(PageMedia())
    var mediaSession: MediaSession? = null // the page's media keys (its own ‹ ›), from Gecko
    var queue = false // the page has ‹ and › of its own
    var first by mutableStateOf<MediaSite?>(null) // AI Assist's search: its first result still to play
    var firstList = false // and that search was for playlists: a playlist's first song, the list going on after it
    var retry = emptyList<String>() // that search again in Greeklish when it finds nothing (Spotify), one spelling after another
    var mix = false // the user's own mixes page: one of them played (Spotify's Daily Mix)
    var autoplay = false // reached by Player mode's ‹ ›: its media started when it has loaded
    var desktop by mutableStateOf(false) // the desktop site: a desktop agent, kept per tab
    var html by mutableStateOf(false) // HTML only: no JavaScript, no autoplay
    var pinned by mutableStateOf(false) // a circle at the row's start, its icon only
    var group by mutableStateOf<Int?>(null) // its tab group, if any
    var pending: String? = null // a kept tab's address, loaded when it is first opened
    var port: WebExtension.Port? = null
    val waiting = mutableListOf<JSONObject>() // said before this page's script was there (a small page stops loading first): given when it comes
    /** The desktop site from its next page on (Spotify's web player); false when it was already. */
    fun desktopOn(): Boolean {
        if (desktop) return false
        desktop = true
        session.settings.userAgentMode = GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
        session.settings.viewportMode = GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
        return true
    }
    /** A word to the page's script (fill, a media key, autoplay); kept for it until it connects, dropped when another page starts. */
    fun send(o: JSONObject) { port?.postMessage(o) ?: run { waiting += o; WriterLog.i("Browser: ${o.keys().asSequence().firstOrNull()} waits for the page script") } }
}

private const val PULL_EDGE_DP = 64f // where a pull to reload may start: the page's top this far

/** Sync's reads and writes of the Browser's files, one at a time, and on past the screen (the tabs written as it closes). */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val BROWSER_SYNC = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
private val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "file", "moz-extension", "resource", "view-source")

@SuppressLint("ClickableViewAccessibility")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun BrowserPage(private: Boolean, start: String, said: String?, play: String?, back: () -> Unit, setPrivate: (Boolean, String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("browser", Context.MODE_PRIVATE) }
    val runtime = remember { BrowserEngine.runtime(context) }
    // Private keeps nothing: no vault, no history; bookmarks are read, never added
    var vault by remember { mutableStateOf(if (private) BrowserVault() else BrowserSecrets.vault(context)) }
    // Settings › Browser: forgotten on leaving, after 24 hours, or never; "on leaving" also forgets what a crash left
    val keepHistory = remember { prefs.getString("keep_history", BrowserRules.DAY) ?: BrowserRules.DAY }
    var visits by remember { mutableStateOf(if (private || keepHistory == BrowserRules.ON_EXIT) emptyList() else BrowserRules.fresh(BrowserRules.visits(prefs.getString("history", null)), System.currentTimeMillis(), keepHistory)) }
    // the bar at the top, as on a computer (the user, 2 Nov: "like chrome on PC"): ☰ at its end instead of the handle's sheet
    val top = remember { prefs.getBoolean("top_bar", false) }
    var bookmarks by remember { mutableStateOf(BrowserRules.bookmarks(prefs.getString("bookmarks", null))) }
    val managerFolder = remember { mutableStateOf("") } // the manager opens where it was left
    var bookFolders by remember { mutableStateOf(BookmarkLibrary.folders(prefs.getString("bookmark_folders", null))) } // the folders, empty ones too
    val library = BookmarkLibrary(bookmarks, bookFolders)
    // sync (BrowserSync.kt): this device's files in .System/Browser/Shared; Private sends no tabs
    val syncRoot = remember { BrowserSync.root() }
    val me = remember { BrowserSync.device(context) }
    // Settings › Browser › Sync, asked each time: the Browser stays open behind Settings
    fun syncBookmarks() = BrowserSync.on(context, BrowserSync.BOOKMARKS)
    fun syncTabs() = !private && BrowserSync.on(context, BrowserSync.TABS)
    fun syncPasswords() = false // Tetra Auto: no Passwords, the vault stays on this unit
    val marksRun = remember { IntArray(1) }
    var others by remember { mutableStateOf(emptyList<DeviceTabs>()) } // the other devices' tabs
    var editing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf(TextFieldValue("")) }
    var menu by remember { mutableStateOf(false) } // the handle's sheet: the tools not on the bar
    var editTools by remember { mutableStateOf(false) } // a long press: tap tools to move them between the bar and the sheet
    var panel by remember { mutableStateOf<BrowserPanel?>(null) }
    var offer by remember { mutableStateOf<BrowserVault?>(null) } // what a sent form could keep
    var lastForm by remember { mutableStateOf("") }
    var filling by remember { mutableStateOf(false) }
    var imported by remember { mutableStateOf<Pair<String, Uri?>?>(null) } // the result, and the file to delete when it held passwords
    var extensions by remember { mutableStateOf(false) } // the puzzle piece's sheet
    var picked by remember { mutableStateOf<((Array<Uri>?) -> Unit)?>(null) } // a page's file input, waiting for Android's picker
    var player by remember { mutableStateOf(false) } // Player mode: the page above, ‹ play › below
    var dismissed by remember { mutableStateOf(setOf<String>()) } // playlists whose Player strip was closed
    var background by remember { mutableStateOf(BrowserMedia.background(context)) }
    var message by remember { mutableStateOf(said) }
    LaunchedEffect(message) { if (message != null) { delay(3000); message = null } }
    LaunchedEffect(BrowserEngine.note) { BrowserEngine.note?.let { message = it; BrowserEngine.note = null } }
    LaunchedEffect(Unit) { BrowserEngine.firstStart(context) } // uBlock Origin and Dark Reader, once; in the background
    OpenTiming("Browser", true)

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data
        val uris = if (r.resultCode != android.app.Activity.RESULT_OK || d == null) null
            else d.clipData?.let { c -> Array(c.itemCount) { c.getItemAt(it).uri } } ?: d.data?.let { arrayOf(it) }
        picked?.invoke(uris); picked = null
    }
    val prompter = remember { BrowserPrompter(context) { intent, done -> picked?.invoke(null); picked = done; runCatching { pick.launch(intent) }.onFailure { picked = null; done(null) } } }
    LaunchedEffect(Unit) { prompter.clean() }
    val density = context.resources.displayMetrics.density
    val pageColor = MaterialTheme.colorScheme.background.toArgb()
    val pageText = MaterialTheme.colorScheme.onBackground.toArgb()
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600 // a tablet: more on the toolbar
    var currentId by remember { mutableIntStateOf(0) }
    val barKey = if (wide) "bar_tablet" else "bar_phone"
    var onBarTools by remember { mutableStateOf(BrowserTools.parse(prefs.getString(barKey, null), wide)) }
    // split screen (the user, 2 Nov): a tablet's two tabs side by side, or one above the other when upright; a tap chooses which the bar drives
    var splitPair by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // tab groups (the user, 2 Nov): a tab dragged onto another makes one, named; one chip that unfolds.
    // Pinned and grouped tabs are kept from one time to the next ("keep them"); Private keeps nothing.
    val kept = remember { if (private) emptyList<SavedTab>() to emptyMap() else SavedTabs.read(prefs.getString("kept_tabs", null), prefs.getString("keep_history", BrowserRules.DAY) ?: BrowserRules.DAY) }
    val groups = remember { mutableStateMapOf<Int, String>().apply { putAll(kept.second) } }
    var openGroups by remember { mutableStateOf(setOf<Int>()) }
    var nextGroup by remember { mutableIntStateOf((kept.second.keys.maxOrNull() ?: 0) + 1) }
    var naming by remember { mutableStateOf<Int?>(null) } // the group whose name is being written
    var tabOptions by remember { mutableStateOf<Int?>(null) } // a long press on a tab
    var groupOptions by remember { mutableStateOf<Int?>(null) } // a long press on a group
    // auto-hide (the user, 2 Nov: "to get full screen, show it on scroll up"): off unless switched on, kept
    var autohide by remember { mutableStateOf(prefs.getBoolean("autohide", false)) }
    var tabsRun by remember { mutableStateOf(prefs.getBoolean("tabs_run", true)) } // tabs not shown go on, sound and all (☰'s switch)
    // a video's full screen (YouTube's ⛶): the page alone, turned to landscape, until its ⛶ or ‹
    var fullTab by remember { mutableStateOf<Int?>(null) }
    // a link or a picture held (the user, 4 Nov: "when I long click replace the tab bar with the right click menu")
    var held by remember { mutableStateOf<GeckoSession.ContentDelegate.ContextElement?>(null) }
    fun hostActivity() = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<android.app.Activity>().firstOrNull()
    var barShown by remember { mutableStateOf(true) }
    var lowered by remember { mutableStateOf(false) } // the handle pulled down: the page full screen over a thin black strip (the user, 2 Nov)
    val drift = remember { IntArray(1) } // the scroll since its last turn, in px: a small threshold, so the bar does not flicker
    // HTML only, per site (Private: for this time only, kept nowhere)
    var htmlSites by remember { mutableStateOf(if (private) emptySet() else prefs.getStringSet("html_sites", null).orEmpty().toSet()) }
    /** The tab set as its next page's site wants: Gecko reads it as that page loads. */
    fun applyHtml(t: BrowserTab, address: String?) {
        val on = BrowserRules.htmlOnly(htmlSites, address)
        if (on == t.html) return
        t.html = on
        t.session.settings.allowJavascript = !on // Tetra's own script (fill, player, media keys) runs still: it is an extension's
        WriterLog.i("Browser: HTML only ${if (on) "on" else "off"} for ${BrowserRules.host(address)}")
    }
    /**
     * Auto-hide, by the finger (the user, 2 Nov: "it does not hide the bar"): [d] pixels the page was pushed up. Pages that
     * scroll inside themselves (most, now that 100vh is the screen) never tell their scroll, so it cannot tell.
     */
    fun dragged(t: BrowserTab, d: Int) {
        if (t.id != currentId || !autohide) return
        drift[0] = if ((d > 0) == (drift[0] > 0)) drift[0] + d else d
        if (drift[0] < -12 * density) barShown = true else if (drift[0] > 48 * density) barShown = false
    }
    /** Downloads (a link the page does not show itself: a file, a blob, an attachment): into Downloads, said when there. */
    fun download(r: WebResponse) {
        val type = r.headers["Content-Type"]?.substringBefore(';')?.trim()?.lowercase()
        if (type == "application/x-xpinstall") { BrowserEngine.install(r.uri); return } // an extension offered outside addons.mozilla.org
        val name = android.webkit.URLUtil.guessFileName(r.uri, r.headers["Content-Disposition"], type)
        val body = r.body ?: run { WriterLog.w("Browser: a download without a body, ${r.uri.take(80)}"); return }
        message = context.getString(R.string.browser_downloading, name)
        WriterLog.i("Browser: downloading $name ($type) from ${BrowserRules.host(r.uri)}")
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching {
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name); type?.let { put(MediaStore.Downloads.MIME_TYPE, it) }; put(MediaStore.Downloads.IS_PENDING, 1)
                })!!
                try { resolver.openOutputStream(uri)!!.use { out -> body.use { it.copyTo(out) } } } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            }.onFailure { WriterLog.e("Browser: $name not downloaded", it) }.isSuccess }
            message = context.getString(if (ok) R.string.browser_downloaded else R.string.browser_download_failed, name)
        }
    }
    /** Gecko's own error, as a small page in Tetra's look (none of Firefox's about:neterror here). */
    fun errorPage(uri: String?, e: WebRequestError): String {
        fun esc(s: String) = android.text.Html.escapeHtml(s)
        val html = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'><body style='font:16px sans-serif;padding:24px;" +
            "background:#%06X;color:#%06X'><h3>%s</h3><p style='word-break:break-all;opacity:.7'>%s</p><p style='opacity:.5'>%d / %d</p>".format(pageColor and 0xFFFFFF, pageText and 0xFFFFFF,
            esc(context.getString(R.string.browser_load_error, BrowserRules.host(uri).ifEmpty { uri.orEmpty() })), esc(uri.orEmpty()), e.category, e.code)
        WriterLog.w("Browser: ${BrowserRules.host(uri)} not opened (${e.category}/${e.code})")
        return "data:text/html;charset=utf-8," + Uri.encode(html)
    }
    var soundId by remember { mutableStateOf<Int?>(null) } // the tab last heard: the notification's and Maps' keys go to it, whichever is shown
    /** A page's script said something (assets/tetra-ext/content.js): the form fields there, a form sent, the media's state. */
    fun heard(tab: BrowserTab, o: JSONObject) {
        o.optString("error", null)?.let { WriterLog.w("Browser: page script, ${it.take(200)}") }
        o.optString("first", null)?.let {
            WriterLog.i("Browser: AI Assist plays ${it.take(120)}")
            tab.retry = emptyList()
            if (tab.first?.press == true) { tab.first = null; tab.autoplay = true } // its page opens paused: Play pressed once it has loaded
        }
        if (o.optBoolean("none")) tab.first?.let { site -> tab.retry.firstOrNull()?.let { q -> // nothing found: the next spelling
            tab.retry = tab.retry.drop(1)
            WriterLog.i("Browser: nothing found on ${site.id}, asked again in Greeklish")
            tab.session.loadUri(MediaSites.searchUrl(site, q, tab.firstList))
        } }
        o.optString("icon", null)?.let { if (!private && tab.url.startsWith("http")) BrowserIcons.learn(context, tab.url, it) } // its circle's picture
        o.optString("kinds", null)?.let { s -> tab.kinds = s.split(',').filter { it.isNotBlank() }.toSet() }
        o.optString("media", null)?.let { s -> runCatching { JSONObject(s) }.getOrNull()?.let { m ->
            val was = tab.media
            tab.media = PageMedia(m.optBoolean("has"), m.optBoolean("playing"), m.optString("title").take(200), tab.queue)
            if (was.has != tab.media.has || was.playing != tab.media.playing) WriterLog.i("Browser: media on ${BrowserRules.host(tab.url)}: ${if (tab.media.playing) "playing" else if (tab.media.has) "paused" else "none"}")
            if (tab.media.playing) soundId = tab.id
        } }
        // Private keeps nothing: a form there is not offered to the vault
        o.optString("form", null)?.takeIf { !private && it.length < 20_000 && it != lastForm }?.let { json -> // a click and a submit send the same form twice
            lastForm = json
            runCatching { vault.news(BrowserVault.fromForm(BrowserRules.host(tab.url), JSONObject(json))) }.getOrNull()?.takeIf { !it.empty }?.let { offer = it }
        }
    }

    /** The tab's session opened and shown by its view (a kept tab's only when first opened: no engine work for it before). */
    fun attach(t: BrowserTab) {
        if (!t.session.isOpen) t.session.open(runtime)
        if (t.view.session !== t.session) { t.view.setSession(t.session); t.view.coverUntilFirstPaint(pageColor) }
    }
    fun reopen(t: BrowserTab) { runCatching { if (!t.session.isOpen) t.session.open(runtime); t.url.takeIf { it.isNotEmpty() }?.let(t.session::loadUri) }.onFailure { WriterLog.e("Browser: a tab not reopened", it) } }
    fun load(t: BrowserTab, address: String) { attach(t); applyHtml(t, address); t.session.loadUri(address) }
    lateinit var openTab: (String, Boolean) -> BrowserTab
    lateinit var closeTab: (BrowserTab) -> Unit
    /** A tab; [gecko]: one a page or an extension opens, its session left for Gecko to open. */
    fun newTab(id: Int, address: String, lazy: Boolean = false, gecko: Boolean = false): BrowserTab {
        val desktop = wide && prefs.getBoolean("desktop", false) // a tablet's default
        val session = GeckoSession(GeckoSessionSettings.Builder().usePrivateMode(private).useTrackingProtection(true)
            .userAgentMode(if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE).build())
        // not the WRAP_CONTENT AndroidView gives a view without any: pages sized in vh would fold away
        val view = GeckoView(context).apply { layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT) }
        val tab = BrowserTab(id, session, view).also { it.desktop = desktop }
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(session: GeckoSession, url: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                tab.url = url.takeUnless { it == "about:blank" }.orEmpty()
                if (tab.first != null && tab.url.contains("watch")) tab.first = null
                if (tab.view.session == null) attach(tab) // a tab Gecko opened
            }
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) { tab.canBack = canGoBack }
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) { tab.canForward = canGoForward }
            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
                val u = Uri.parse(request.uri)
                if (u.scheme?.lowercase() in WEB_SCHEMES) { applyHtml(tab, request.uri); return null }
                // mailto:, tel:, intent:, market: — the app that opens it; a page may start only what a browser may (BROWSABLE)
                val intent = runCatching {
                    if (u.scheme == "intent") Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME).apply { component = null; selector = null } else Intent(Intent.ACTION_VIEW, u)
                }.getOrNull() ?: return GeckoResult.deny()
                runCatching { context.startActivity(intent.addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure {
                    intent.getStringExtra("browser_fallback_url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { f -> session.loadUri(f) }
                        ?: run { message = context.getString(R.string.browser_no_app) }
                }
                return GeckoResult.deny()
            }
            // target=_blank, window.open: a tab of its own
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> = GeckoResult.fromValue(openTab(uri, true).session)
            override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String> = GeckoResult.fromValue(errorPage(uri, error))
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                tab.kinds = emptySet(); tab.media = PageMedia(); tab.progress = 0; tab.waiting.clear()
                if (tab.id == currentId) held = null
                // extensions' buttons speak of the tab shown; told again, as a session reopened after its process was killed is a new window to them
                if (tab.id == currentId) runtime.webExtensionController.setTabActive(session, true)
            }
            override fun onProgressChange(session: GeckoSession, progress: Int) { tab.progress = progress }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                tab.progress = 100
                if (tab.url.isEmpty()) return // a new session's about:blank, before its first page
                if (tab.autoplay) { tab.autoplay = false; WriterLog.i("Browser: autoplay on ${BrowserRules.host(tab.url)}"); tab.send(JSONObject().put("autoplay", true)) }
                tab.first?.takeIf { tab.mix && BrowserRules.host(tab.url) in it.hosts }?.let { site -> tab.mix = false; WriterLog.i("Browser: a mix looked for on ${site.id}")
                    tab.send(JSONObject().put("first", "a[href*=\"/playlist/\"]").put("text", "Daily Mix").put("random", true).put("wait", 30000)) }
                tab.first?.let { site -> if (MediaSites.isSearch(site, tab.url)) { WriterLog.i("Browser: AI Assist's first result looked for on ${BrowserRules.host(tab.url)}"); tab.send(JSONObject().put("first", if (tab.firstList && site.firstList.isNotEmpty()) site.firstList else site.first)
                    .apply { if (tab.retry.isNotEmpty()) put("wait", 15000) }) } } // Greeklish waits: asked after 15 s without a result
                if (private || !success || !tab.url.startsWith("http")) return
                visits = BrowserRules.visit(visits, Visit(tab.url, tab.title, System.currentTimeMillis()), keepHistory)
                prefs.edit().putString("history", BrowserRules.visitsJson(visits)).apply()
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) { tab.title = title.orEmpty() }
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                fullTab = if (fullScreen) tab.id else null
                hostActivity()?.requestedOrientation = if (fullScreen) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) = download(response)
            override fun onCloseRequest(session: GeckoSession) = closeTab(tab) // window.close()
            // a long press on a link or a picture: its menu where the tabs are (a video or plain text: nothing)
            override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, element: GeckoSession.ContentDelegate.ContextElement) {
                if (tab.id != currentId) return
                if (element.linkUri == null && !(element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE && element.srcUri != null)) return
                held = element; barShown = true
            }
            // its page's process gone (killed behind other apps, or crashed): the same page again
            override fun onCrash(session: GeckoSession) { WriterLog.w("Browser: a tab's page crashed, ${BrowserRules.host(tab.url)}"); reopen(tab) }
            override fun onKill(session: GeckoSession) { WriterLog.w("Browser: a tab's page was killed, ${BrowserRules.host(tab.url)}"); reopen(tab) }
        }
        session.scrollDelegate = object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) { tab.scrollY = scrollY; if (scrollY <= 0 && tab.id == currentId) barShown = true } // back at the top: the bar
        }
        session.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(session: GeckoSession, perm: GeckoSession.PermissionDelegate.ContentPermission): GeckoResult<Int> = GeckoResult.fromValue(when (perm.permission) {
                // Player mode's and the notification's Play come from outside the page; HTML only plays nothing by itself
                GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE, GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE ->
                    if (tab.html) GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY else GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                // a site's frame asking for its own cookies (Google's consent and sign-in on YouTube); protected content (music sites)
                GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS, GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                else -> GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY // location, notifications…: none, as before
            })
            override fun onMediaPermissionRequest(session: GeckoSession, uri: String, video: Array<out GeckoSession.PermissionDelegate.MediaSource>?, audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?, callback: GeckoSession.PermissionDelegate.MediaCallback) = callback.reject()
            override fun onAndroidPermissionsRequest(session: GeckoSession, permissions: Array<out String>?, callback: GeckoSession.PermissionDelegate.Callback) = callback.reject()
        }
        session.mediaSessionDelegate = object : MediaSession.Delegate {
            override fun onActivated(session: GeckoSession, mediaSession: MediaSession) { tab.mediaSession = mediaSession }
            override fun onDeactivated(session: GeckoSession, mediaSession: MediaSession) { tab.mediaSession = null; tab.queue = false; tab.media = tab.media.copy(queue = false) }
            override fun onFeatures(session: GeckoSession, mediaSession: MediaSession, features: Long) {
                tab.queue = features and MediaSession.Feature.NEXT_TRACK != 0L && features and MediaSession.Feature.PREVIOUS_TRACK != 0L
                tab.media = tab.media.copy(queue = tab.queue)
            }
        }
        session.promptDelegate = prompter
        hostActivity()?.let { session.selectionActionDelegate = BasicSelectionActionDelegate(it) } // copy, paste, select all over a selection
        BrowserEngine.register(session)
        BrowserEngine.withBuiltIn { ext -> session.webExtensionController.setMessageDelegate(ext, object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                tab.port = port
                WriterLog.i("Browser: page script on ${BrowserRules.host(tab.url).ifEmpty { "a new page" }}")
                port.postMessage(JSONObject().put("bg", background))
                tab.waiting.forEach(port::postMessage); tab.waiting.clear()
                port.setDelegate(object : WebExtension.PortDelegate {
                    override fun onPortMessage(message: Any, port: WebExtension.Port) { (message as? JSONObject)?.let { heard(tab, it) } }
                    override fun onDisconnect(port: WebExtension.Port) { if (tab.port === port) tab.port = null }
                })
            }
        }, "tetra") }
        // pull to reload (the user, 2 Nov), only from the page's top edge and over half its height (5 Nov: "I could pull from
        // anywhere and refresh with the slightest scroll"; pages that scroll inside themselves never say they are not at their top)
        var from = -1f
        var lastY = 0f
        view.setOnTouchListener { _, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) lastY = e.y
            if (e.actionMasked == android.view.MotionEvent.ACTION_MOVE && e.pointerCount == 1) { dragged(tab, (lastY - e.y).toInt()); lastY = e.y }
            val need = view.height / 2f
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> from = if (tab.scrollY > 0 || e.y > PULL_EDGE_DP * density) -1f else e.y
                android.view.MotionEvent.ACTION_MOVE -> if (from >= 0 && e.pointerCount == 1 && tab.scrollY <= 0 && need > 0) tab.pull = ((e.y - from) / need).coerceAtLeast(0f) else { from = -1f; tab.pull = 0f }
                android.view.MotionEvent.ACTION_UP -> { if (tab.pull >= 1f && tab.url.isNotEmpty()) { WriterLog.i("Browser: pulled to reload"); tab.session.reload() }; from = -1f; tab.pull = 0f }
                android.view.MotionEvent.ACTION_CANCEL, android.view.MotionEvent.ACTION_POINTER_DOWN -> { from = -1f; tab.pull = 0f }
            }
            false
        }
        when {
            gecko -> Unit // Gecko opens it and loads its page; attached at its first address
            address.isNotEmpty() && lazy -> { tab.url = address; tab.pending = address } // a kept tab: its name now, its page when opened
            address.isNotEmpty() -> load(tab, address)
            else -> Unit // an empty tab: the start page, its session opened with its first page (a content process the fewer)
        }
        return tab
    }

    var nextId by remember { mutableIntStateOf(kept.first.size + 1) }
    val tabs = remember {
        val back = kept.first.mapIndexed { i, k -> newTab(i + 1, k.url, lazy = true).apply { title = k.title; pinned = k.pinned; group = k.group } }
        mutableStateListOf(*(back + newTab(0, start)).toTypedArray())
    }
    /** A kept tab opened for the first time: its page now. */
    fun wake(t: BrowserTab) { t.pending?.let { u -> t.pending = null; load(t, u) } }
    /** The open tabs as the other devices see them (Private's never). */
    fun remoteTabs() = tabs.map { RemoteTab(it.pending ?: it.url, it.title, it.pinned, it.group?.let { g -> groups[g] }.orEmpty()) }.filter { it.url.isNotEmpty() }
    fun shareTabs(list: List<RemoteTab> = remoteTabs()) {
        if (!syncTabs()) return
        val app = context.applicationContext
        BROWSER_SYNC.launch {
            runCatching { if (BrowserSync.writeTabs(syncRoot, me, thisDeviceName(app), list)) { WriterLog.i("Browser sync: ${list.size} tabs written"); ScopedSync.soon(app, "browser", orWhole = true) } }
                .onFailure { WriterLog.e("Browser sync: tabs not written", it) }
        }
    }
    fun saveTabs() {
        if (!private) prefs.edit().putString("kept_tabs", SavedTabs.json(tabs.map { SavedTab(it.pending ?: it.url, it.title, it.pinned, it.group) }, groups)).apply()
        shareTabs() // and to the other devices, when the Browser is left
    }
    // a change of the tabs (opened, closed, a page, a name, pinned, grouped): to the other devices 2 s after the last
    LaunchedEffect(Unit) { if (!private) snapshotFlow { remoteTabs() }.distinctUntilChanged().collectLatest { delay(2000); shareTabs(it) } }
    val tab = tabs.firstOrNull { it.id == currentId } ?: tabs.first()
    val media = tab.media
    fun switch(to: BrowserTab) {
        if (to.id == currentId) return
        held = null
        val old = tabs.firstOrNull { it.id == currentId }
        old?.let { runtime.webExtensionController.setTabActive(it.session, false) }
        runtime.webExtensionController.setTabActive(to.session, true) // extensions' buttons speak of the tab shown (uBlock's count)
        val pair = splitPair
        if (pair != null && (to.id == pair.first || to.id == pair.second)) { currentId = to.id; editing = false; wake(to); return } // the other pane: only the focus moves
        if (pair != null) splitPair = if (pair.first == currentId) to.id to pair.second else pair.first to to.id // into the chosen pane
        // the tab left goes on as it was, its sound too (the user, 2 Nov: "no, tab switch stops the other page"), unless ☰ says otherwise
        if (!tabsRun) old?.let { o -> o.send(JSONObject().put("media", "pause")); o.session.setActive(false) }
        to.session.setActive(true); wake(to); currentId = to.id; editing = false; player = false; barShown = true
    }
    fun open(address: String = "", gecko: Boolean = false): BrowserTab = newTab(nextId++, address, gecko = gecko).also { tabs += it; switch(it) }
    fun close(t: BrowserTab) {
        if (t !in tabs) return
        if (splitPair?.let { t.id == it.first || t.id == it.second } == true) splitPair = null
        if (tabs.size == 1) open() // never none: a fresh empty tab
        if (t.id == currentId) switch(tabs.getOrNull(tabs.indexOf(t) + 1) ?: tabs[tabs.indexOf(t) - 1])
        tabs.remove(t); BrowserEngine.forget(t.session); t.view.releaseSession(); t.session.close()
    }
    openTab = { u, gecko -> open(u, gecko) }
    closeTab = ::close
    DisposableEffect(Unit) {
        val host = object : ExtTabs {
            override fun newTab(url: String?) = open(gecko = true).session
            override fun close(session: GeckoSession) { tabs.firstOrNull { it.session === session }?.let(::close) }
            override fun show(session: GeckoSession) { tabs.firstOrNull { it.session === session }?.let(::switch) }
            override fun open(url: String) { open(url) }
        }
        BrowserEngine.tabs = host
        runtime.webExtensionController.setTabActive(tab.session, true)
        onDispose { if (BrowserEngine.tabs === host) BrowserEngine.tabs = null }
    }

    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(Unit) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e ->
            val now = tabs.firstOrNull { it.id == currentId }
            // playing in the background: that tab stays active, so its page never learns it is hidden (the notification keeps Tetra running)
            if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) saveTabs() // Tetra may be closed from behind: keep the pinned and grouped tabs now
            if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) tabs.forEach { if (!(background && it.media.playing) && it.session.isOpen) it.session.setActive(false) }
            else if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) now?.session?.setActive(true)
        }
        lifecycle.lifecycle.addObserver(o)
        onDispose {
            lifecycle.lifecycle.removeObserver(o)
            BrowserMedia.control = null; BrowserMedia.stop(context)
            saveTabs()
            if (keepHistory == BrowserRules.ON_EXIT) prefs.edit().remove("history").apply()
            picked?.invoke(null)
            // Private: Gecko forgets everything it had (cookies, storage, cache) when its last private session closes
            tabs.forEach { BrowserEngine.forget(it.session); it.view.releaseSession(); it.session.close() }
            if (private) WriterLog.i("Browser: Private closed, its sessions gone")
        }
    }

    fun mediaKey(a: String, t: BrowserTab = tab) {
        // a page with no ‹ › of its own (a radio station's): the site's other favourites, playing as they open
        if ((a == "next" || a == "prev") && MediaSites.of(BrowserRules.host(t.url)) == null && !t.media.queue)
            BrowserRules.hop(bookmarks, t.url, a == "next")?.let { t.autoplay = true; load(t, it); return }
        // the page's own ‹ › (YouTube's playlist, a music site's queue): through Gecko's media session
        val ms = t.mediaSession
        if (ms != null && t.queue && a == "next") { ms.nextTrack(); return }
        if (ms != null && t.queue && a == "prev") { ms.previousTrack(); return }
        t.send(JSONObject().put("media", a).put("buttons", JSONObject(MediaSites.buttons(MediaSites.of(BrowserRules.host(t.url))))))
    }
    // the notification's keys: the tab heard last, else the one shown
    val sound = tabs.firstOrNull { it.id == soundId && it.media.has } ?: tab
    SideEffect { BrowserMedia.control = { a -> mediaKey(a, sound) } }
    LaunchedEffect(sound.media, background) { if (background && sound.media.has) BrowserMedia.show(context, sound.media.title, sound.media.playing) else BrowserMedia.stop(context) }
    LaunchedEffect(play) {
        val o = play?.let { runCatching { JSONObject(it.removePrefix("browser-play:")) }.getOrNull() } ?: return@LaunchedEffect
        // Tetra Car's Media: Player mode, on the page asked for or on what plays now
        if (o.optBoolean("player") && !o.has("url")) { player = true; menu = false; panel = null; return@LaunchedEffect }
        // Maps' ♪: its station played, then this window goes behind Maps again once the sound has started (or after 8 s)
        o.optString("url").takeIf { it.isNotEmpty() }?.let { address ->
            val t = tabs.firstOrNull { it.url == address } ?: if (tab.url.isEmpty()) tab else open()
            val loaded = t.url == address && t.pending == null // a kept tab not yet opened loads now, and plays once loaded
            val play = !o.optBoolean("open") // Home's favourites (8 Nov) open the page and play nothing
            if (!loaded) { t.pending = null; t.autoplay = play }
            if (t.id != currentId) switch(t)
            if (o.optBoolean("desktop") && t.desktopOn() && loaded) t.session.reload() // Tetra Car's Spotify: its web player is the desktop site's
            if (loaded) { if (play) t.send(JSONObject().put("autoplay", true)) } else load(t, address)
            if (o.optBoolean("player")) { player = true; menu = false; panel = null }
            if (o.optBoolean("behind")) {
                kotlinx.coroutines.withTimeoutOrNull(8000) { while (!t.media.playing) delay(250) }
                (context as? android.app.Activity)?.moveTaskToBack(true)
            }
            return@LaunchedEffect
        }
        val site = MediaSites.byId(o.optString("site"))
        val t = if (tab.url.isEmpty()) tab else open() // in a tab of its own, unless this one is empty
        t.first = site; t.firstList = o.optBoolean("list"); player = true; menu = false; panel = null
        if (site.desktop) t.desktopOn()
        t.mix = o.optBoolean("mix") && site.mixes.isNotEmpty()
        if (t.mix) { t.retry = emptyList(); load(t, site.mixes); return@LaunchedEffect } // «παίξε Spotify»: one of the user's Daily Mixes
        t.retry = if (site.greeklish) MediaSites.retries(o.optString("q")) else emptyList()
        load(t, MediaSites.searchUrl(site, o.optString("q"), t.firstList))
    }

    fun go(input: String) {
        editing = false; panel = null; menu = false
        load(tab, BrowserRules.target(input))
    }
    fun setDesktop(t: BrowserTab, on: Boolean) {
        t.desktop = on
        t.session.settings.userAgentMode = if (on) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        t.session.settings.viewportMode = if (on) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        if (wide) prefs.edit().putBoolean("desktop", on).apply() // a tablet: new tabs open the same way
        message = context.getString(if (on) R.string.browser_desktop else R.string.browser_mobile)
        if (t.url.isNotEmpty()) t.session.reload()
    }
    fun toggleHtml(t: BrowserTab) {
        val site = BrowserRules.site(BrowserRules.host(t.url)).takeIf { t.url.isNotEmpty() } ?: return
        htmlSites = if (t.html) htmlSites - site else htmlSites + site
        if (!private) prefs.edit().putStringSet("html_sites", htmlSites).apply()
        applyHtml(t, t.url)
        message = if (t.html) context.getString(R.string.browser_html_on, site) else context.getString(R.string.browser_html_off)
        t.session.reload()
    }
    /** A tab dropped on another (a new group, to be named) or on a group (into it). */
    fun dropOn(t: BrowserTab, target: String) {
        t.pinned = false
        val g = if (target.startsWith("g")) target.drop(1).toInt() else {
            val u = tabs.firstOrNull { "t${it.id}" == target }?.takeIf { !it.pinned && it.id != t.id } ?: return
            u.group ?: nextGroup++.also { g -> groups[g] = context.getString(R.string.browser_group_default, g); u.group = g; naming = g }
        }
        t.group = g; openGroups = openGroups + g
    }
    fun toggleSplit() {
        if (splitPair != null) { splitPair = null; return }
        val other = tabs.firstOrNull { it.id != currentId } ?: newTab(nextId++, "").also { tabs += it }
        other.session.setActive(true); wake(other)
        splitPair = currentId to other.id
    }
    /** Passwords, addresses and cards between devices, encrypted, when chosen (PasswordSync); a merge after a newer change here is dropped. */
    val vaultRun = remember { IntArray(1) }
    fun syncVault(v: BrowserVault, pushed: Boolean) {
        if (!syncPasswords()) return
        val n = ++vaultRun[0]
        val app = context.applicationContext
        BROWSER_SYNC.launch {
            val r: BrowserVault? = null
            if (pushed && r != null) ScopedSync.soon(app, "browser", orWhole = true)
            withContext(Dispatchers.Main) { if (r != null && n == vaultRun[0] && r != vault) { vault = r; runCatching { BrowserSecrets.save(app, r) } } }
        }
    }
    fun keep(v: BrowserVault) { vault = v; runCatching { BrowserSecrets.save(context, v) }.onFailure { WriterLog.e("Browser: the vault not saved", it) }; syncVault(v, true) }
    fun store(l: BookmarkLibrary) {
        bookmarks = l.marks; bookFolders = l.extra
        prefs.edit().putString("bookmarks", BrowserRules.bookmarksJson(l.marks)).putString("bookmark_folders", BookmarkLibrary.json(l.extra)).apply()
    }
    /**
     * Bookmarks between devices (BrowserSync, Settings › Browser › Sync): what changed here recorded, the others' merged in,
     * shown if it differs; [pushed] a change made here, sent at once. A merge answered after a newer change here is dropped.
     */
    fun syncMarks(l: BookmarkLibrary, pushed: Boolean) {
        if (!syncBookmarks()) return
        val n = ++marksRun[0]
        BROWSER_SYNC.launch {
            val r = runCatching { BrowserSync.bookmarks(syncRoot, me, l, File(context.filesDir, "browser-sync/bookmarks.json")) }.onFailure { WriterLog.e("Browser sync: bookmarks not merged", it) }.getOrNull()
            if (pushed) ScopedSync.soon(context.applicationContext, "browser", orWhole = true)
            withContext(Dispatchers.Main) { if (r != null && n == marksRun[0] && (r.marks != bookmarks || r.extra != bookFolders)) store(r) }
        }
    }
    fun keepLibrary(l: BookmarkLibrary) { store(l); syncMarks(l, true) }
    fun keepBookmarks(b: List<Bookmark>) = keepLibrary(BookmarkLibrary(b, bookFolders))
    // the other devices' bookmarks and tabs: read now, and again when sync brings a file of theirs
    fun readOthers() { if (!syncTabs()) others = emptyList() else BROWSER_SYNC.launch { val o = runCatching { BrowserSync.otherTabs(syncRoot, me) }.getOrDefault(emptyList()); withContext(Dispatchers.Main) { others = o } } }
    DisposableEffect(Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val marksSoon = Runnable { syncMarks(BookmarkLibrary(bookmarks, bookFolders), false) }
        val vaultSoon = Runnable { syncVault(vault, false) }
        val tabsSoon = Runnable { readOthers() }
        val dirs = listOf(File(syncRoot, "Bookmarks"), File(syncRoot, "Tabs")).onEach { runCatching { it.mkdirs() } }
        val watch = object : android.os.FileObserver(dirs, CLOSE_WRITE or MOVED_TO or DELETE) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null || path.startsWith(".") || path.substringBeforeLast('.') == me || !(path.endsWith(".json") || path.endsWith(".enc"))) return
                // which folder is not said: each looked at, half a second after the last
                main.removeCallbacks(marksSoon); main.removeCallbacks(tabsSoon); main.removeCallbacks(vaultSoon)
                if (syncBookmarks()) main.postDelayed(marksSoon, 500)
                main.postDelayed(tabsSoon, 500)
                if (syncPasswords()) main.postDelayed(vaultSoon, 500)
            }
        }
        watch.startWatching()
        // opened, and back from elsewhere (Settings › Browser › Sync, a while away): looked at again, the switches as they are now
        // (an observer added now is told ON_RESUME at once)
        val back = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
            if (!private) vault = BrowserSecrets.vault(context) // the Passwords applet may have changed it meanwhile
            syncMarks(BookmarkLibrary(bookmarks, bookFolders), false); readOthers(); syncVault(vault, false); shareTabs() } }
        lifecycle.lifecycle.addObserver(back)
        onDispose { lifecycle.lifecycle.removeObserver(back); watch.stopWatching(); main.removeCallbacks(marksSoon); main.removeCallbacks(tabsSoon); main.removeCallbacks(vaultSoon) }
    }
    var filing by remember { mutableStateOf<Bookmark?>(null) } // a favourite being put in a folder
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { s -> FirefoxImport.read(s.readBytes().decodeToString()) } }.getOrNull() }
            if (r == null) { imported = context.getString(R.string.browser_import_unknown) to null; return@launch }
            keep(vault.with(BrowserVault(logins = r.logins)))
            keepLibrary(library.imported(r.bookmarks))
            WriterLog.i("Browser: imported ${r.logins.size} logins, ${r.bookmarks.size} bookmarks")
            imported = context.getString(R.string.browser_imported, r.logins.size, r.bookmarks.size) to uri.takeIf { r.logins.isNotEmpty() }
        }
    }

    BackHandler { when { held != null -> held = null; fullTab != null -> tab.session.exitFullScreen(); player -> player = false; editing -> editing = false; panel != null -> panel = null; menu -> menu = false; tab.canBack -> tab.session.goBack(); else -> back() } }

    val url = tab.url
    val host = BrowserRules.host(url)
    val fitting = remember(tab.kinds, vault, host) {
        Triple(if ("password" in tab.kinds) vault.logins.filter { BrowserRules.sameHost(it.host, host) } else emptyList(),
            if (tab.kinds.any { it in BrowserVault.ADDRESS }) vault.addresses else emptyList(),
            if (tab.kinds.any { it.startsWith("cc-") }) vault.cards else emptyList())
    }
    val canFill = fitting.first.isNotEmpty() || fitting.second.isNotEmpty() || fitting.third.isNotEmpty()
    // ♥ (the user, 2 Nov: "a heart for favorites"): the start page's tiles; saved in Private too — a favourite is no history
    val marked = url.isNotEmpty() && bookmarks.any { it.url == url }
    fun heart() {
        keepBookmarks(if (marked) bookmarks.filter { it.url != url } else bookmarks + Bookmark(url, tab.title.ifEmpty { host }))
        message = context.getString(if (marked) R.string.browser_unbookmarked else R.string.browser_bookmarked)
    }
    val imeOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    // lowered stays lowered while typing in the page (the user, 4 Nov): the strip's tap brings the bar, and Fill, back
    val showBar = (!lowered || editing || menu || panel != null || held != null) && (!autohide || barShown || editing || imeOpen || menu || panel != null || held != null || url.isEmpty())
    // full screen while the bar is away: the status bar goes too (a swipe brings it for a moment)
    val activity = remember { generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<android.app.Activity>().firstOrNull() }
    val full = fullTab != null // a video's full screen: no bar, no system bars
    LaunchedEffect(showBar, player, full) {
        val w = activity?.window ?: return@LaunchedEffect
        androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).apply {
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (full) hide(androidx.core.view.WindowInsetsCompat.Type.systemBars()) else show(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            hide(androidx.core.view.WindowInsetsCompat.Type.statusBars()) // always (the user, 5 Nov): the page from the top, a swipe brings the clock
        }
    }
    DisposableEffect(Unit) { onDispose { activity?.window?.let { w -> androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) } } }
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }
    val sidePref = remember { prefs.getBoolean("side_bar", true) }
    val side = wide && landscape && sidePref && !player && !full && androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp >= 600
    // every tool once: the bar shows those chosen, the handle's sheet the rest (BrowserTools)
    @Composable fun toolOf(id: String): BrowserTool? = when (id) {
        "fill" -> BrowserTool(R.drawable.ic_key, stringResource(R.string.browser_fill), canFill, !private) { if (canFill) filling = true else message = context.getString(R.string.browser_nothing_to_fill) }
        // the puzzle piece (the user, 3 Nov): every extension, its switch, its own page
        "extensions" -> BrowserTool(R.drawable.ic_extension, stringResource(R.string.browser_extensions)) { extensions = true }
        "private" -> BrowserTool(R.drawable.ic_visibility_off, stringResource(R.string.browser_private), private) { setPrivate(!private, url) }
        "desktop" -> BrowserTool(R.drawable.ic_computer, stringResource(R.string.browser_desktop), tab.desktop) { setDesktop(tab, !tab.desktop) }
        "html" -> BrowserTool(R.drawable.ic_code, stringResource(R.string.browser_html), tab.html, url.isNotEmpty()) { toggleHtml(tab) }
        "bookmarks" -> BrowserTool(R.drawable.ic_bookmark, stringResource(R.string.browser_bookmarks)) { panel = BrowserPanel.Bookmarks }
        "history" -> if (private) null else BrowserTool(R.drawable.ic_history, stringResource(R.string.browser_history)) { panel = BrowserPanel.History }
        // the Passwords applet (the user, 4 Nov: "Instead of making a better password page, create a password manager in tetra"), this page's site looked up
        "saved" -> null
        "copy" -> BrowserTool(R.drawable.ic_copy, stringResource(R.string.browser_copy_url), enabled = url.isNotEmpty()) {
            context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText(host, url))
            message = context.getString(R.string.browser_copied)
        }
        "share" -> BrowserTool(R.drawable.ic_share, stringResource(R.string.browser_share), enabled = url.isNotEmpty()) {
            runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
                .putExtra(Intent.EXTRA_SUBJECT, tab.title), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        "split" -> if (!wide) null else BrowserTool(R.drawable.ic_view_column, stringResource(R.string.browser_split), splitPair != null) { toggleSplit() }
        "forward" -> if (wide) null else BrowserTool(R.drawable.ic_chevron_right, stringResource(R.string.browser_forward), enabled = tab.canForward) { tab.session.goForward() }
        "player" -> BrowserTool(R.drawable.ic_play, stringResource(R.string.browser_player), enabled = media.has) { player = true }
        "import" -> if (private) null else BrowserTool(R.drawable.ic_file_download, stringResource(R.string.browser_import)) { imported = null; panel = BrowserPanel.Import }
        "tabs_run" -> BrowserTool(R.drawable.ic_tab, stringResource(R.string.browser_tabs_run), tabsRun) {
            tabsRun = !tabsRun; prefs.edit().putBoolean("tabs_run", tabsRun).apply()
            message = context.getString(if (tabsRun) R.string.browser_tabs_run_on else R.string.browser_tabs_run_off)
        }
        "autohide" -> BrowserTool(R.drawable.ic_fullscreen, stringResource(R.string.browser_autohide), autohide) { autohide = !autohide; barShown = true; prefs.edit().putBoolean("autohide", autohide).apply() }
        "background" -> BrowserTool(R.drawable.ic_headphones, stringResource(R.string.browser_background), background) {
            background = !background; BrowserMedia.setBackground(context, background)
            tabs.forEach { it.port?.postMessage(JSONObject().put("bg", background)) }
            message = context.getString(if (background) R.string.browser_background_on else R.string.browser_background_off)
        }
        "close" -> BrowserTool(R.drawable.ic_close, stringResource(R.string.common_close)) { back() }
        // an extension's own button, pinned (uBlock's shield and its count of this page): its icon, its badge, its popup
        else -> id.takeIf { it.startsWith("ext:") }?.removePrefix("ext:")?.let { extId ->
            val ext = BrowserEngine.extensions.firstOrNull { it.id == extId && it.metaData.enabled } ?: return@let null
            val a = BrowserEngine.effective(tab.session, extId)
            BrowserTool(R.drawable.ic_extension, a?.action?.title?.takeIf { it.isNotBlank() } ?: ext.metaData.name ?: extId,
                enabled = (!private || ext.metaData.allowedInPrivateBrowsing) && a?.action?.enabled != false,
                badge = a?.action?.badgeText?.takeIf { it.isNotEmpty() }, image = a?.icon ?: BrowserEngine.icons[extId]) { a?.action?.click() ?: run { extensions = true } }
        }
    }
    val editBar = { editTools = true; menu = true; editing = false }
    val bar = if (private) PRIVATE_COLOR else MaterialTheme.colorScheme.surface
    val onBar = if (private) Color.White else MaterialTheme.colorScheme.onSurface
    /** The address: the host, ♥ at its end; a tap types a new one. The bar's and the sidebar's (the tablet's, 4 Nov). */
    @Composable fun addressPill(modifier: Modifier, withHeart: Boolean = true, typing: Boolean = true) {
        Surface(color = onBar.copy(alpha = 0.08f), shape = CircleShape, modifier = modifier) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (editing && typing) {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                    Box(Modifier.weight(1f)) {
                        if (typed.text.isEmpty()) Text(stringResource(R.string.browser_address), color = onBar.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        BasicTextField(typed, { typed = it }, singleLine = true, textStyle = TextStyle(color = onBar, fontSize = 16.sp), cursorBrush = SolidColor(onBar),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { if (typed.text.isNotBlank()) go(typed.text) }), modifier = Modifier.fillMaxWidth().focusRequester(focus))
                    }
                } else Row(Modifier.weight(1f).fillMaxHeight().clickable { typed = TextFieldValue(url, TextRange(0, url.length)); editing = true; panel = null; menu = false },
                    verticalAlignment = Alignment.CenterVertically) {
                    if (url.startsWith("https://")) Icon(painterResource(R.drawable.ic_lock), null, Modifier.size(16.dp).padding(end = 4.dp))
                    // HTML only on: said on the bar, so a page that looks broken makes sense
                    if (tab.html) Icon(painterResource(R.drawable.ic_code), stringResource(R.string.browser_html), Modifier.padding(end = 6.dp).size(18.dp), tint = BROWSER_COLOR)
                    Text(host.removePrefix("www.").ifEmpty { stringResource(R.string.browser_address) }, Modifier.weight(1f), color = if (host.isEmpty()) onBar.copy(alpha = 0.6f) else onBar,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // ♥ at the end of the address, as Chrome's star, so the row is no wider
                    if (withHeart && url.isNotEmpty()) IconButton(onClick = ::heart, Modifier.size(36.dp)) { BrowserHeart(marked) }
                }
            }
        }
    }
    /** A held link or picture's action (BrowserHeld), from the tab row or the sidebar. */
    fun heldAct(h: GeckoSession.ContentDelegate.ContextElement, what: String) {
        held = null
        val link = h.linkUri; val pic = h.srcUri
        fun copy(s: String) { context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText(BrowserRules.host(s), s)); message = context.getString(R.string.browser_copied) }
        when (what) {
            // Settings › Browser › New tabs in the background (the user, 7 Nov): Open in new tab stays on this page
            "tab" -> link?.let { if (prefs.getBoolean("tab_behind", false)) { tabs += newTab(nextId++, it, lazy = true); message = context.getString(R.string.browser_held_opened_behind) } else open(it) }
            "behind" -> link?.let { tabs += newTab(nextId++, it, lazy = true); message = context.getString(R.string.browser_held_opened_behind) }
            "copy" -> link?.let(::copy)
            "share" -> (link ?: pic)?.let { runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it), null)) } }
            "picture" -> pic?.let { open(it) }
            "save" -> pic?.let { src -> org.mozilla.geckoview.GeckoWebExecutor(runtime).fetch(org.mozilla.geckoview.WebRequest(src))
                .accept({ r -> r?.let(::download) }, { e -> WriterLog.w("Browser: picture not fetched", e); message = context.getString(R.string.browser_download_failed, src.substringAfterLast('/').take(40)); null }) }
            "copyPicture" -> pic?.let(::copy)
        }
    }

    /** |♥ Copy Share| (the user, 5 Nov): beside the address on a tablet's bar, under it in the sidebar. */
    @Composable fun shareGroup() = Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = Color.Transparent, border = androidx.compose.foundation.BorderStroke(1.dp, onBar.copy(alpha = 0.25f))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::heart, enabled = url.isNotEmpty(), modifier = Modifier.size(40.dp)) { BrowserHeart(marked) }
                listOf("copy", "share").mapNotNull { toolOf(it) }.forEach { t ->
                    IconButton(onClick = t.act, enabled = t.enabled, modifier = Modifier.size(40.dp)) { Icon(painterResource(t.icon), t.label, tint = if (t.enabled) onBar else onBar.copy(alpha = 0.3f)) }
                }
            }
        }
    }

    // ☰ at the bar's end when it is at the top: the tools not on the bar, as a computer's browser menu
    @Composable fun topMenu() = Box {
        IconButton(onClick = { menu = true; editing = false }, Modifier.size(44.dp)) { Icon(painterResource(R.drawable.ic_menu), stringResource(R.string.browser_menu)) }
        DropdownMenu(expanded = menu && !editTools, onDismissRequest = { menu = false }) {
            (BrowserTools.ALL - onBarTools.toSet()).forEach { id ->
                val t = toolOf(id) ?: return@forEach
                DropdownMenuItem(text = { Text(t.label) }, onClick = { menu = false; t.act() }, enabled = t.enabled,
                    leadingIcon = { Icon(painterResource(t.icon), null, tint = if (t.on) BROWSER_COLOR else LocalContentColor.current) })
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text(stringResource(R.string.common_edit)) }, onClick = { editTools = true }, leadingIcon = { Icon(painterResource(R.drawable.ic_edit), null) })
        }
    }
    /** The bar, with its line to the page: under the page, or over it when it is at the top. */
    @Composable fun ColumnScope.barArea() {
        @Composable fun line() = if (top && tab.progress < 100 && url.isNotEmpty()) LinearProgressIndicator(progress = { tab.progress / 100f }, Modifier.fillMaxWidth().height(2.dp), color = BROWSER_COLOR)
            else if (showBar) HorizontalDivider(color = onBar.copy(alpha = 0.2f)) else Unit
        if (!top) line()
        androidx.compose.animation.AnimatedVisibility(showBar, enter = androidx.compose.animation.expandVertically(), exit = androidx.compose.animation.shrinkVertically()) {
        CompositionLocalProvider(LocalContentColor provides onBar) {
            val shown = onBarTools.filter { !wide || (it != "copy" && it != "share") }.mapNotNull { id -> toolOf(id) }
            @Composable fun tool(t: BrowserTool, size: Modifier) = BrowserSmall(t.icon, t.image, t.label, t.on, t.enabled, t.badge, size, long = editBar, click = t.act)
            Column {
            // the handle, as Tetra's others (the user, 2 Nov): a tap or a drag up opens the sheet of the other tools
            var drag by remember { mutableFloatStateOf(0f) }
            if (!top) Box(Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.browser_menu)) { menu = true; editing = false }.pointerInput(Unit) {
                detectVerticalDragGestures(onDragStart = { drag = 0f }, onVerticalDrag = { _, d -> drag += d }, onDragEnd = { if (drag < -30) { menu = true; editing = false } else if (drag > 30) { lowered = true; editing = false } })
            }.heightIn(min = 24.dp), contentAlignment = Alignment.Center) { // a finger's worth of height, though the pill is thin
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(onBar.copy(alpha = 0.4f)))
            }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 10.dp, end = 10.dp, bottom = 8.dp, top = if (top) 8.dp else 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    @Composable fun addressRow() {
                    // row 1: Back (a tablet: and Forward), the address, and a tablet's tools in one framed line with ☰ at its end (the user's sketch, 2 Nov)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (wide) {
                            BrowserRound(R.drawable.ic_chevron_left, stringResource(R.string.common_back), tab.canBack, 56) { tab.session.goBack() }
                            BrowserRound(R.drawable.ic_chevron_right, stringResource(R.string.browser_forward), tab.canForward, 44) { tab.session.goForward() }
                        }
                        addressPill(Modifier.weight(if (wide) 0.8f else 1f).height(48.dp), withHeart = !wide)
                        if (wide) shareGroup()
                        if (wide) Surface(shape = MaterialTheme.shapes.large, color = Color.Transparent, border = androidx.compose.foundation.BorderStroke(1.dp, onBar.copy(alpha = 0.25f)),
                            modifier = Modifier.weight(1f).height(56.dp)) {
                            Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                                    shown.forEach { tool(it, Modifier.size(width = 48.dp, height = 44.dp)) }
                                }
                                if (top) topMenu()
                            }
                        }
                        if (!wide) Surface(onClick = { tab.session.goBack() }, enabled = tab.canBack, shape = CircleShape, color = if (tab.canBack) BROWSER_COLOR else onBar.copy(alpha = 0.08f),
                            contentColor = if (tab.canBack) Color.White else onBar.copy(alpha = 0.35f), modifier = Modifier.size(48.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.common_back)) }
                        }
                        if (top && !wide) topMenu()
                    }
                    }
                    @Composable fun tabsRow() {
                    // row 2: the tabs (☰'s rest is a sheet from the bottom, as in Tetra's other apps)
                    val h = held
                    if (h != null) BrowserHeld(h, close = { held = null }) { what ->
                        heldAct(h, what)
                    }
                    else BrowserTabs(tabs, groups, openGroups, currentId, wide, !private, switch = ::switch, close = ::close, add = { open(); editing = true; typed = TextFieldValue("") },
                        fold = { g -> openGroups = if (g in openGroups) openGroups - g else openGroups + g }, options = { tabOptions = it.id }, groupOptions = { groupOptions = it }, dropOn = ::dropOn)
                    }
                    if (top) { tabsRow(); addressRow() } else { addressRow(); tabsRow() }
                }
                // a phone: two columns of three small ones (the user's choice, by a long press)
                if (!wide) shown.chunked(3).forEach { column ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { column.forEach { tool(it, Modifier.size(width = 48.dp, height = 32.dp)) } }
                }
            }
            }
        } }
        if (top) line()
    }


    // the tablet's sidebar (the user, 4 Nov: "vertical layout for the tablet like zen browser does", their sketch): in
    // landscape, the address, Back, Forward, Copy and Share under it, the tools, the favourites, the tabs; Settings and
    // Private at its foot. Hidden by hand to a thin strip, or as the page scrolls (Auto-hide), as the bar is.
    @Composable fun sideIcon(icon: Int, label: String, enabled: Boolean = true, size: Int = 44, act: () -> Unit) =
        IconButton(onClick = act, enabled = enabled, modifier = Modifier.size(size.dp)) { Icon(painterResource(icon), label, tint = if (enabled) onBar else onBar.copy(alpha = 0.3f)) }
    SideEffect { BrowserSideSheets.on = side }
    DisposableEffect(Unit) { onDispose { BrowserSideSheets.on = false } }
    @Composable fun sideBar(modifier: Modifier) = Surface(modifier.width(228.dp).fillMaxHeight().sideTouches().swipeLeft { lowered = true; editing = false }, color = bar, contentColor = onBar) { CompositionLocalProvider(LocalContentColor provides onBar) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                addressPill(Modifier.fillMaxWidth().height(46.dp), withHeart = false, typing = false)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    sideIcon(R.drawable.ic_chevron_left, stringResource(R.string.common_back), tab.canBack, 40) { tab.session.goBack() }
                    sideIcon(R.drawable.ic_chevron_right, stringResource(R.string.browser_forward), tab.canForward, 40) { tab.session.goForward() }
                    Spacer(Modifier.weight(1f))
                    shareGroup()
                }
                Surface(shape = MaterialTheme.shapes.large, color = Color.Transparent, border = androidx.compose.foundation.BorderStroke(1.dp, onBar.copy(alpha = 0.25f)), modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.foundation.layout.FlowRow(Modifier.padding(4.dp), horizontalArrangement = Arrangement.SpaceEvenly, maxItemsInEachRow = 4) {
                        onBarTools.filter { it != "copy" && it != "share" }.mapNotNull { toolOf(it) }.forEach { t -> BrowserSmall(t.icon, t.image, t.label, t.on, t.enabled, t.badge, Modifier.size(width = 46.dp, height = 40.dp), long = editBar, click = t.act) }
                        if (top) topMenu() else IconButton(onClick = { menu = true; editing = false }, Modifier.size(width = 46.dp, height = 40.dp)) { Icon(painterResource(R.drawable.ic_more_horiz), stringResource(R.string.browser_menu)) }
                    }
                }
                val favourites = library.start("").first
                if (favourites.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(favourites, key = { it.url }) { b ->
                        val site = BrowserRules.site(BrowserRules.host(b.url))
                        val pic = siteIcon(b.url, fetch = !private)
                        Box(Modifier.size(36.dp).clip(CircleShape).background(if (pic != null) Color.White else TILE_COLORS[(site.hashCode() and 0x7fffffff) % TILE_COLORS.size])
                            .clickable(onClickLabel = b.title.ifBlank { site }) { go(b.url) }, contentAlignment = Alignment.Center) {
                            if (pic != null) androidx.compose.foundation.Image(pic, null, Modifier.size(24.dp)) else Text(site.take(1).uppercase(), color = Color.White, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                val h = held
                if (h != null) BrowserHeld(h, vertical = true, close = { held = null }) { what -> heldAct(h, what) }
                else {
                    Row(Modifier.fillMaxWidth().height(40.dp).clip(MaterialTheme.shapes.medium).clickable { open(); editing = true; typed = TextFieldValue("") }.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_add), null, tint = BROWSER_COLOR); Text(stringResource(R.string.browser_tab_new), Modifier.padding(start = 10.dp), color = BROWSER_COLOR, style = MaterialTheme.typography.labelLarge)
                    }
                    BrowserSideTabs(tabs, groups, openGroups, currentId, Modifier.weight(1f), !private, switch = ::switch, close = ::close,
                        fold = { g -> openGroups = if (g in openGroups) openGroups - g else openGroups + g }, options = { tabOptions = it.id }, groupOptions = { groupOptions = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sideIcon(R.drawable.ic_settings, stringResource(R.string.common_settings)) {
                        context.startActivity(Intent(context, SettingsActivity::class.java).putExtra(SettingsActivity.PAGE, SettingsPage.Browser.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    // Hide where Private was (the user, 7 Nov: Private is among the tools already)
                    Surface(onClick = { lowered = true; editing = false }, shape = CircleShape, color = if (private) Color.White.copy(alpha = 0.15f) else onBar.copy(alpha = 0.08f), modifier = Modifier.weight(1f).height(40.dp)) {
                        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_chevron_left), null, Modifier.size(18.dp)); Text(stringResource(R.string.browser_hide), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    } }
    /** Hidden by hand (the user, 6 Nov): no strip, only a half circle at the low left, off the system's back-gesture edge
     *  height; a tap or a drag out brings the bar or sidebar back. [upright]: the sidebar's, on the left edge. */
    @Composable fun BoxScope.handle(upright: Boolean) {
        var drag by remember { mutableFloatStateOf(0f) }
        if (upright) HideHint(prefs)
        Box(Modifier.align(Alignment.BottomStart).then(if (upright) Modifier.padding(bottom = 72.dp).size(18.dp, 56.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50))
            else Modifier.padding(start = 24.dp).size(56.dp, 18.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(topStartPercent = 50, topEndPercent = 50)))
            .background(Color.Black.copy(alpha = 0.75f)).clickable(onClickLabel = stringResource(R.string.browser_menu)) { lowered = false }
            .pointerInput(upright) { if (upright) detectHorizontalDragGestures(onDragStart = { drag = 0f }, onHorizontalDrag = { _, d -> drag += d }, onDragEnd = { if (drag > 20) lowered = false })
                else detectVerticalDragGestures(onDragStart = { drag = 0f }, onVerticalDrag = { _, d -> drag += d }, onDragEnd = { if (drag < -20) lowered = false }) },
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(if (upright) 4.dp else 24.dp, if (upright) 24.dp else 4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.6f)))
        }
    }

    Column(Modifier.fillMaxSize().background(bar).then(if (full) Modifier else Modifier.statusBarsPadding().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top)).navigationBarsPadding()).imePadding()) {
        if (top && !player && !full && !side) barArea()
        Row(Modifier.weight(if (player) 0.6f else 1f).fillMaxWidth()) {
        // the sidebar beside the page; with Auto-hide it comes back over the page instead, so the page does not jump
        if (side && !autohide && showBar) sideBar(Modifier)
        Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.background).twoFingerTabs(rightHalf = side || wide) { step ->
            // two fingers up: the next tab, down: the one before (the user, 7 Nov: one finger scrolls, so two are free)
            val i = tabs.indexOfFirst { it.id == currentId }
            tabs.getOrNull(i + step)?.let { t -> switch(t); message = t.title.ifBlank { BrowserRules.host(t.url) }.ifBlank { context.getString(R.string.browser_tab_new) } }
        }) {
            val panes = splitPair?.takeIf { wide && !player }?.let { p -> listOfNotNull(tabs.firstOrNull { it.id == p.first }, tabs.firstOrNull { it.id == p.second }) }?.takeIf { it.size == 2 }
            @Composable fun pane(t: BrowserTab, m: Modifier) = Box(m.then(if (panes != null && t.id == currentId) Modifier.border(2.dp, BROWSER_COLOR) else Modifier)
                .pointerInput(t.id, panes != null) { if (panes != null) awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); if (t.id != currentId) currentId = t.id } }) {
                key(t.id) { AndroidView({ (t.view.parent as? android.view.ViewGroup)?.removeView(t.view); t.view }, Modifier.fillMaxSize()) }
                if (t.pull > 0.05f) Surface(shape = CircleShape, color = if (t.pull >= 1f) BROWSER_COLOR else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (t.pull >= 1f) Color.White else MaterialTheme.colorScheme.onSurface, shadowElevation = 4.dp,
                    modifier = Modifier.align(Alignment.TopCenter).offset(y = (t.pull.coerceAtMost(1f) * 96).dp).size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.browser_reload), Modifier.size(22.dp).graphicsLayer { rotationZ = t.pull * 300 }) }
                }
                if (t.url.isEmpty()) BrowserStart(private, library, file = { filing = it }, search = if (side && !editing) ({ currentId = t.id; typed = TextFieldValue(""); editing = true; held = null }) else null) { u -> currentId = t.id; editing = false; load(t, BrowserRules.target(u)) }
            }
            val upright = androidx.compose.ui.platform.LocalConfiguration.current.let { it.screenHeightDp > it.screenWidthDp }
            when {
                panes == null -> pane(tab, Modifier.fillMaxSize())
                upright -> Column(Modifier.fillMaxSize()) { panes.forEach { pane(it, Modifier.weight(1f).fillMaxWidth()) } }
                else -> Row(Modifier.fillMaxSize()) { panes.forEach { pane(it, Modifier.weight(1f).fillMaxHeight()) } }
            }
            if (editing) {
                val q = typed.text.trim()
                val found = remember(q, visits, bookmarks) {
                    // nothing typed: the start page's favourites, not every page an import brought
                    (bookmarks.filter { q.isNotEmpty() || !BookmarkLibrary.under(it.folder, BookmarkLibrary.IMPORTED) }.map { it.url to it.title } + visits.map { it.url to it.title }).distinctBy { it.first }
                        .filter { q.isEmpty() || it.first.contains(q, true) || it.second.contains(q, true) }.take(8)
                }
                // the sidebar's: the address typed in the middle of the page, its suggestions under it, as Zen's (the user, 4 Nov)
                if (side) {
                    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)).pointerInput(Unit) { detectTapGestures { editing = false } })
                    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 12.dp,
                        modifier = Modifier.align(androidx.compose.ui.BiasAlignment(0f, -0.45f)).widthIn(max = 680.dp).fillMaxWidth(0.66f).pointerInput(Unit) { detectTapGestures { } }) {
                        Column {
                            Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(R.drawable.ic_search), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                val focus = remember { FocusRequester() }
                                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                                Box(Modifier.weight(1f).padding(start = 14.dp)) {
                                    if (typed.text.isEmpty()) Text(stringResource(R.string.browser_address), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp, maxLines = 1)
                                    BasicTextField(typed, { typed = it }, singleLine = true, textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp),
                                        cursorBrush = SolidColor(BROWSER_COLOR), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                                        keyboardActions = KeyboardActions(onGo = { if (typed.text.isNotBlank()) go(typed.text) }), modifier = Modifier.fillMaxWidth().focusRequester(focus))
                                }
                            }
                            if (found.isNotEmpty()) { HorizontalDivider(); found.forEach { (u, t) -> BrowserRow(t, u, click = { go(u) }) } }
                        }
                    }
                }
                else if (found.isNotEmpty()) Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column { found.forEach { (u, t) -> BrowserRow(t, u, click = { go(u) }) } }
                }
            }
            // a playlist or a queue: Player mode offered in a strip from the bottom, until closed
            val list = MediaSites.listId(url) ?: host.takeIf { media.queue }
            androidx.compose.animation.AnimatedVisibility(!player && panel == null && !editing && list != null && list !in dismissed, Modifier.align(Alignment.BottomCenter),
                enter = androidx.compose.animation.slideInVertically { it }, exit = androidx.compose.animation.slideOutVertically { it }) {
                Surface(color = islandColor(), contentColor = islandContentColor(), shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                    Row(Modifier.padding(start = 18.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.browser_playlist_found), Modifier.weight(1f))
                        IconButton(onClick = { player = true }) { Icon(painterResource(R.drawable.ic_play), stringResource(R.string.browser_player)) }
                        IconButton(onClick = { list?.let { dismissed = dismissed + it } }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.common_close)) }
                    }
                }
            }
            // the loading line where the clock was (the bar at the top has its own under it)
            if ((!top || side) && tab.progress < 100 && url.isNotEmpty()) LinearProgressIndicator(progress = { tab.progress / 100f },
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp), color = BROWSER_COLOR, trackColor = Color.Transparent)
            if (lowered && !showBar && !player && !full && (side || !top)) handle(upright = side)
            message?.let {
                Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape, modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp, start = 16.dp, end = 16.dp)) {
                    Text(it, Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
                }
            }
            if (side && autohide && !lowered) {
                androidx.compose.animation.AnimatedVisibility(showBar, Modifier.align(Alignment.CenterStart),
                    enter = androidx.compose.animation.slideInHorizontally { -it }, exit = androidx.compose.animation.slideOutHorizontally { -it }) { sideBar(Modifier.shadow(8.dp)) }
                // hidden: a swipe in from the left edge brings it
                if (!showBar) { var drag by remember { mutableFloatStateOf(0f) }
                    Box(Modifier.align(Alignment.CenterStart).width(20.dp).fillMaxHeight().pointerInput(Unit) {
                        detectHorizontalDragGestures(onDragStart = { drag = 0f }, onHorizontalDrag = { _, d -> drag += d }, onDragEnd = { if (drag > 30) barShown = true }) }) }
            }
        }
        }
        if (player) { HorizontalDivider(color = onBar.copy(alpha = 0.2f)); CompositionLocalProvider(LocalContentColor provides onBar) {
            BrowserPlayer(Modifier.weight(0.4f), media, key = { mediaKey(it) }, marked = marked, heart = ::heart, forMaps = BrowserMedia.station(context)?.first == url,
                setForMaps = { BrowserMedia.setStation(context, url, tab.title.ifEmpty { host }); message = context.getString(R.string.browser_for_maps_set) },
                exit = { player = false }) }
        } else if (!top && !full && !side) barArea()
    }

    offer?.let { o ->
        AlertDialog(onDismissRequest = { offer = null }, title = { Text(stringResource(R.string.browser_save_title)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                o.logins.forEach { Text(stringResource(R.string.browser_save_login, it.host, it.user.ifEmpty { "—" })) }
                o.addresses.forEach { Text(stringResource(R.string.browser_save_address, addressLabel(it))) }
                o.cards.forEach { Text(stringResource(R.string.browser_save_card, it.last4)) }
                Text(stringResource(if (syncPasswords()) R.string.browser_saved_note_synced else R.string.browser_saved_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } },
            confirmButton = { TextButton(onClick = { keep(vault.with(o)); offer = null }) { Text(stringResource(R.string.common_save)) } },
            dismissButton = { TextButton(onClick = { offer = null }) { Text(stringResource(R.string.browser_not_now)) } })
    }
    // the handle's sheet: the tools not on the bar (the user, 2 Nov: "like the rest of the Tetra apps a modal menu from the bottom");
    // a long press, there or on the bar: every tool, a tap moves it between the bar and the sheet
    // open in full and scrolling: every tool is in sight, the last row too
    if (menu && (!top || editTools)) BrowserSheet(onDismissRequest = { menu = false; editTools = false }, full = true) {
        var full by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            if (editTools) Row(Modifier.padding(start = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (full) R.string.browser_bar_full else R.string.browser_edit_tools), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    color = if (full) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { editTools = false }) { Text(stringResource(R.string.common_done)) }
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // in edit mode each extension's button too, to put on the bar (the user, 3 Nov: "pin them in the toolbar")
                (if (editTools) BrowserTools.ALL + BrowserEngine.extensions.map { "ext:${it.id}" } else BrowserTools.ALL - onBarTools.toSet()).forEach { id ->
                    val t = toolOf(id) ?: return@forEach
                    BrowserTile(t.icon, t.image, t.label, t.enabled || editTools, on = !editTools && t.on, pinned = editTools && id in onBarTools, long = { editTools = true }) {
                        if (!editTools) { menu = false; t.act(); return@BrowserTile }
                        val next = BrowserTools.toggle(onBarTools, id, wide)
                        full = next == null
                        if (next != null) { onBarTools = next; prefs.edit().putString(barKey, next.joinToString(",")).apply() }
                    }
                }
            }
        }
    }
    // Bookmarks, History, Passwords and Import: modal sheets too
    if (panel == BrowserPanel.Bookmarks) BookmarksManager(library, wide, managerFolder, keep = ::keepLibrary, close = { panel = null }, others = others,
        openNew = { u -> panel = null; open(u) }) { u -> panel = null; go(u) }
    else panel?.let { p -> BrowserSheet(onDismissRequest = { panel = null }) {
        BrowserPanelView(p, close = { panel = null }, visits = visits, vault = vault, imported = imported, open = ::go,
                    keepHistory = keepHistory,
                    clearHistory = { visits = emptyList(); prefs.edit().remove("history").apply() },
                    keep = ::keep, choose = { importer.launch(arrayOf("*/*")) },
                    deleteFile = { u ->
                        val gone = runCatching { DocumentsContract.deleteDocument(context.contentResolver, u) }.getOrDefault(false)
                        message = context.getString(if (gone) R.string.browser_file_deleted else R.string.browser_file_not_deleted)
                        if (gone) imported = imported?.let { it.first to null }
                    })
    } }
    // a long press on a tab: pin, out of its group, close
    tabOptions?.let { id -> tabs.firstOrNull { it.id == id } }?.let { t ->
        BrowserOptions(t.title.ifBlank { BrowserRules.host(t.url) }.ifBlank { stringResource(R.string.browser_tab_new) }, dismiss = { tabOptions = null }, listOfNotNull(
            stringResource(if (t.pinned) R.string.browser_unpin else R.string.browser_pin) to { t.pinned = !t.pinned; t.group = null },
            t.group?.let { stringResource(R.string.browser_ungroup) to { t.group = null } },
            stringResource(R.string.browser_tab_close) to { close(t) }))
    }
    // a long press on a group: rename, keep its pages as bookmarks, close it all
    groupOptions?.let { g ->
        val members = tabs.filter { it.group == g }
        BrowserOptions(groups[g].orEmpty(), dismiss = { groupOptions = null }, listOf(
            stringResource(R.string.common_rename) to { naming = g },
            stringResource(R.string.browser_group_bookmark) to {
                // shortcut: a group is kept as its pages' bookmarks, not as one item that opens them all; add a bookmark kind if that is wanted
                keepBookmarks((bookmarks + members.filter { it.url.isNotEmpty() }.map { Bookmark(it.url, it.title.ifBlank { BrowserRules.host(it.url) }) }).distinctBy { it.url })
                message = context.getString(R.string.browser_group_bookmarked)
            },
            stringResource(R.string.browser_group_close) to { members.forEach(::close); groups.remove(g) }))
    }
    naming?.let { g ->
        var name by remember(g) { mutableStateOf(groups[g].orEmpty()) }
        AlertDialog(onDismissRequest = { naming = null }, title = { Text(stringResource(R.string.browser_group_name)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) groups[g] = name.trim(); naming = null }) { Text(stringResource(R.string.common_ok)) } },
            dismissButton = { TextButton(onClick = { naming = null }) { Text(stringResource(R.string.common_cancel)) } })
    }
    if (filling) BrowserFillStrip(fitting.first, fitting.second, fitting.third, dismiss = { filling = false },
        manage = { filling = false }) { data, forHost ->
        filling = false
        // the page may have moved on while the strip was open: a password goes only to its own host
        if (forHost == null || BrowserRules.sameHost(BrowserRules.host(tab.url), forHost)) tab.send(JSONObject().put("fill", data))
    }
    if (extensions) ExtensionsSheet(onBarTools, private, close = { extensions = false }, openTab = { u -> open(u) }, press = { e ->
        BrowserEngine.effective(tab.session, e.id)?.action?.click() ?: e.metaData.optionsPageUrl?.let { open(it) }
    }, pin = { id ->
        val next = BrowserTools.toggle(onBarTools, id, wide)
        if (next == null) message = context.getString(R.string.browser_bar_full)
        else { onBarTools = next; prefs.edit().putString(barKey, next.joinToString(",")).apply() }
    })
    BrowserEngine.ask?.let { ExtAskDialog(it) }
    BrowserEngine.popup?.let { ExtPopupSheet(it) }
    BrowserPromptView(prompter)
    filing?.let { b -> FolderDialog(b.title.ifBlank { BrowserRules.host(b.url) }, b.folder, library.tree(), close = { filing = null }) { f ->
        keepLibrary(library.move(setOf(b.url), emptySet(), f)); filing = null
    } }
}

@Composable private fun BrowserHeart(on: Boolean) =
    Icon(painterResource(if (on) R.drawable.ic_favorite else R.drawable.ic_favorite_border), stringResource(R.string.browser_favorite), Modifier.size(22.dp),
        tint = if (on) Color(0xFFE53950) else LocalContentColor.current)

/** A tablet's Back and Forward: round, as the sketch, dimmed when there is nowhere to go. */
@Composable private fun BrowserRound(icon: Int, label: String, enabled: Boolean, size: Int, click: () -> Unit) =
    Surface(onClick = click, enabled = enabled, shape = CircleShape, color = if (enabled) BROWSER_COLOR else LocalContentColor.current.copy(alpha = 0.08f),
        contentColor = if (enabled) Color.White else LocalContentColor.current.copy(alpha = 0.35f), modifier = Modifier.size(size.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), label, Modifier.size((size * 0.5f).dp)) }
    }

/** A tool, wherever it stands: on the bar or in the handle's sheet. */
private class BrowserTool(val icon: Int, val label: String, val on: Boolean = false, val enabled: Boolean = true, val badge: String? = null,
                          val image: androidx.compose.ui.graphics.ImageBitmap? = null, val act: () -> Unit) // image: an extension's own, in its colours

/** One of the handle's sheet: its icon, its name small under it, lit when on; in edit mode, framed when it is on the bar. */
@OptIn(ExperimentalFoundationApi::class)
@Composable private fun BrowserTile(icon: Int, image: androidx.compose.ui.graphics.ImageBitmap?, label: String, enabled: Boolean, on: Boolean, pinned: Boolean, long: () -> Unit, click: () -> Unit) {
    val fg = if (on) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f)
    Box(Modifier.size(width = 96.dp, height = 84.dp).clip(MaterialTheme.shapes.large).background(if (on) BROWSER_COLOR else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
        .then(if (pinned) Modifier.border(2.dp, BROWSER_COLOR, MaterialTheme.shapes.large) else Modifier)
        .combinedClickable(onClick = { if (enabled) click() }, onLongClick = long, onLongClickLabel = stringResource(R.string.browser_edit_tools))) {
        Column(Modifier.fillMaxSize().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (image != null) androidx.compose.foundation.Image(image, null, Modifier.size(26.dp)) else Icon(painterResource(icon), null, Modifier.size(26.dp), tint = fg)
            Text(label, Modifier.padding(top = 6.dp), color = fg, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        if (pinned) Icon(painterResource(R.drawable.ic_push_pin), null, Modifier.align(Alignment.TopEnd).padding(6.dp).size(14.dp), tint = BROWSER_COLOR)
    }
}

/** A choice of actions from the bottom, as Tetra's others (no popup lists on a touch screen). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun BrowserOptions(title: String, dismiss: () -> Unit, options: List<Pair<String, () -> Unit>>) =
    BrowserSheet(onDismissRequest = dismiss) {
        Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 8.dp))
            options.forEach { (label, act) -> Text(label, Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { dismiss(); act() }.padding(horizontal = 8.dp, vertical = 14.dp)) }
        }
    }

/**
 * The open tabs: pinned ones as circles, then each with its initial and a short title, a group as one chip that unfolds;
 * × closes the chosen tab, + opens one. A long press gives a tab's choices; held and dragged onto another tab or a group, it joins it.
 */
/**
 * A held link or picture's menu, in the tab row's place and height: × first, then what it is, then its actions; the
 * tabs come back with ×, Back, an action, or the page moving on.
 */
@Composable private fun BrowserHeld(e: GeckoSession.ContentDelegate.ContextElement, vertical: Boolean = false, close: () -> Unit, act: (String) -> Unit) {
    val picture = e.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE && e.srcUri != null
    val actions = buildList {
        if (e.linkUri != null) { add(Triple("tab", R.drawable.ic_tab, R.string.browser_tab_new)); add(Triple("behind", R.drawable.ic_add, R.string.browser_held_behind)); add(Triple("copy", R.drawable.ic_copy, R.string.browser_copy_url)) }
        if (picture) { add(Triple("picture", R.drawable.ic_open_in_browser, R.string.browser_held_picture)); add(Triple("save", R.drawable.ic_file_download, R.string.browser_held_save)); add(Triple("copyPicture", R.drawable.ic_link, R.string.browser_held_copy_picture)) }
        add(Triple("share", R.drawable.ic_share, R.string.browser_share))
    }
    val name = (e.linkText?.takeIf { it.isNotBlank() } ?: e.title ?: e.altText ?: (e.linkUri ?: e.srcUri)?.let { BrowserRules.host(it) }).orEmpty().trim().take(40)
    if (vertical) return Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = close, Modifier.size(40.dp)) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.common_close)) }
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        actions.forEach { (id, icon, label) ->
            Row(Modifier.fillMaxWidth().height(44.dp).clip(MaterialTheme.shapes.medium).clickable { act(id) }.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(icon), null, Modifier.size(20.dp)); Text(stringResource(label), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = close, Modifier.size(40.dp)) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.common_close)) }
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            item {
                Text((e.linkText?.takeIf { it.isNotBlank() } ?: e.title ?: e.altText ?: (e.linkUri ?: e.srcUri)?.let { BrowserRules.host(it) }).orEmpty().trim().take(40),
                    style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp).padding(end = 4.dp))
            }
            items(actions, key = { it.first }) { (id, icon, label) ->
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, onClick = { act(id) }, modifier = Modifier.height(34.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(icon), null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(label), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** The sidebar's tabs: one under another, a group a folder row that folds; a tap switches, a long press its options. */
@OptIn(ExperimentalFoundationApi::class)
/**
 * Panels while the tablet's sidebar is on (the user, 4 Nov: a sheet from the bottom "does not make sense when the bar is
 * on the side"): a card in the middle that grows out of where the sidebar was touched, and shrinks back into it.
 */
internal object BrowserSideSheets {
    var on by mutableStateOf(false)
    @Volatile var from: Offset? = null // the last touch on the sidebar, in the window
}

/** A swipe to the left anywhere on it (the user, 6 Nov): mostly sideways and over 72 dp, so taps and the tabs' scroll are left alone. */
private fun Modifier.swipeLeft(hide: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val need = 72.dp.toPx()
        while (true) {
            val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            if (!c.pressed) break
            val d = c.position - down.position
            if (-d.x > need && -d.x > 2 * kotlin.math.abs(d.y)) { c.consume(); hide(); break }
        }
    }
}

/**
 * Two fingers moved up or down together (over 96 dp, mostly upright): [step] +1 for up, -1 for down, once a gesture. On a
 * tablet only from the page's right half ([rightHalf]), where the thumb rests; a pinch (fingers apart) is left to the page.
 */
private fun Modifier.twoFingerTabs(rightHalf: Boolean, step: (Int) -> Unit): Modifier = pointerInput(rightHalf) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val need = 96.dp.toPx()
        var start: Offset? = null; var gap = 0f
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            val down = e.changes.filter { it.pressed }
            if (down.isEmpty()) break
            if (down.size != 2) { if (start != null) break else continue }
            val mid = (down[0].position + down[1].position) / 2f
            val apart = (down[0].position - down[1].position).getDistance()
            if (start == null) {
                if (rightHalf && down.any { it.position.x < size.width / 2f }) break
                start = mid; gap = apart; continue
            }
            val d = mid - start
            if (kotlin.math.abs(apart - gap) > need / 2) break // a pinch
            if (kotlin.math.abs(d.y) > need && kotlin.math.abs(d.y) > 2 * kotlin.math.abs(d.x)) {
                down.forEach { it.consume() }; step(if (d.y < 0) 1 else -1); break
            }
        }
    }
}

/** The first time the sidebar is hidden (the user, 7 Nov): the handle glows and a bubble says how to bring it back. */
@Composable private fun BoxScope.HideHint(prefs: android.content.SharedPreferences) {
    var hint by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!prefs.getBoolean("side_hide_hint", false)) { hint = true; prefs.edit().putBoolean("side_hide_hint", true).apply() } }
    if (!hint) return
    val glow by androidx.compose.animation.core.rememberInfiniteTransition(label = "glow").animateFloat(0.15f, 0.75f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(700), androidx.compose.animation.core.RepeatMode.Reverse), label = "glow")
    Box(Modifier.align(Alignment.BottomStart).padding(bottom = 62.dp).size(30.dp, 76.dp)
        .clip(androidx.compose.foundation.shape.RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)).background(BROWSER_COLOR.copy(alpha = glow)))
    Surface(color = islandColor(), contentColor = islandContentColor(), shape = MaterialTheme.shapes.large, shadowElevation = 6.dp,
        modifier = Modifier.align(Alignment.BottomStart).padding(start = 38.dp, bottom = 74.dp).widthIn(max = 260.dp).clickable { hint = false }) {
        Text(stringResource(R.string.browser_hide_hint), Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Every touch on the sidebar, noted (BrowserSideSheets.from), before its buttons see it. */
private fun Modifier.sideTouches(): Modifier = composed {
    var origin by remember { mutableStateOf(Offset.Zero) }
    onGloballyPositioned { origin = it.positionInWindow() }.pointerInput(Unit) {
        awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.pressed }?.let { BrowserSideSheets.from = origin + it.position } } }
    }
}

/** A window grown out of where the sidebar was touched, as BrowserSheet's card (Bookmarks' own window); as it is without the sidebar. */
@Composable internal fun Modifier.growFromSide(): Modifier {
    if (!BrowserSideSheets.on) return this
    val from = remember { BrowserSideSheets.from }
    var go by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; androidx.compose.runtime.withFrameNanos { }; go = true }
    val p by androidx.compose.animation.core.animateFloatAsState(if (go) 1f else 0f,
        androidx.compose.animation.core.tween(320, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)), label = "grow")
    var at by remember { mutableStateOf(Offset.Zero) }
    return onGloballyPositioned { at = it.positionInWindow() }.graphicsLayer {
        transformOrigin = from?.takeIf { size.width > 0 && size.height > 0 }
            ?.let { androidx.compose.ui.graphics.TransformOrigin((it.x - at.x) / size.width, (it.y - at.y) / size.height) } ?: androidx.compose.ui.graphics.TransformOrigin.Center
        val k = 0.12f + 0.88f * p; scaleX = k; scaleY = k; alpha = (p * 1.6f).coerceAtMost(1f)
    }
}

/** A bottom sheet; with the sidebar on, the floating card instead. [full]: the sheet opens all the way. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun BrowserSheet(onDismissRequest: () -> Unit, full: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    if (!BrowserSideSheets.on) { ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = full), content = content); return }
    val from = remember { BrowserSideSheets.from }
    var open by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val dismiss by rememberUpdatedState(onDismissRequest)
    // a third of a second out of the button, a little quicker back into it (Material's emphasized curve)
    val p by androidx.compose.animation.core.animateFloatAsState(if (open && !closing) 1f else 0f,
        androidx.compose.animation.core.tween(if (closing) 220 else 320, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)),
        label = "panel", finishedListener = { if (closing) dismiss() })
    androidx.compose.ui.window.Dialog(onDismissRequest = { closing = true },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // grown only once its window is on the screen: started before, the growing ran unseen and the card just appeared
        LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; androidx.compose.runtime.withFrameNanos { }; open = true }
        var at by remember { mutableStateOf(Offset.Zero) }
        BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { closing = true } }, // the dim is the window's, as Bookmarks' own
            contentAlignment = Alignment.Center) {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, shadowElevation = 12.dp,
                modifier = Modifier.fillMaxWidth(0.62f).widthIn(max = 720.dp).heightIn(max = maxHeight * 0.86f)
                    .onGloballyPositioned { at = it.positionInWindow() }
                    .graphicsLayer {
                        transformOrigin = from?.takeIf { size.width > 0 && size.height > 0 }
                            ?.let { androidx.compose.ui.graphics.TransformOrigin((it.x - at.x) / size.width, (it.y - at.y) / size.height) } ?: androidx.compose.ui.graphics.TransformOrigin.Center
                        val s = 0.12f + 0.88f * p; scaleX = s; scaleY = s; alpha = (p * 1.6f).coerceAtMost(1f)
                    }
                    .pointerInput(Unit) { detectTapGestures { } }) { // a tap on the card stays on it
                Column(Modifier.padding(vertical = 12.dp), content = content)
            }
        }
    }
}

@Composable private fun BrowserSideTabs(tabs: List<BrowserTab>, groups: Map<Int, String>, open: Set<Int>, current: Int, modifier: Modifier, fetch: Boolean, switch: (BrowserTab) -> Unit,
                                        close: (BrowserTab) -> Unit, fold: (Int) -> Unit, options: (BrowserTab) -> Unit, groupOptions: (Int) -> Unit) {
    val items = BrowserTabRow.items(tabs.map { TabInfo(it.id, it.pinned, it.group) }, open)
    val scroll = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(current, items.size) { items.indexOfFirst { it is TabRowItem.Tab && it.id == current }.takeIf { it >= 0 }?.let { scroll.animateScrollToItem(it) } }
    androidx.compose.foundation.lazy.LazyColumn(modifier.fillMaxWidth(), state = scroll, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(items, key = { if (it is TabRowItem.Group) "g${it.id}" else "t${(it as TabRowItem.Tab).id}" }) { item ->
            when (item) {
                is TabRowItem.Group -> Row(Modifier.fillMaxWidth().height(40.dp).clip(MaterialTheme.shapes.medium).combinedClickable(onClick = { fold(item.id) }, onLongClick = { groupOptions(item.id) })
                    .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_folder), null, Modifier.size(18.dp), tint = BROWSER_COLOR)
                    Text(groups[item.id].orEmpty(), Modifier.padding(start = 10.dp).weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${item.count}", Modifier.padding(horizontal = 6.dp), color = BROWSER_COLOR, style = MaterialTheme.typography.labelLarge)
                    Icon(painterResource(if (item.open) R.drawable.ic_chevron_left else R.drawable.ic_chevron_right), null, Modifier.size(18.dp))
                }
                is TabRowItem.Tab -> {
                    val t = tabs.first { it.id == item.id }
                    val on = t.id == current
                    val fg = if (on) Color.White else LocalContentColor.current
                    Row(Modifier.fillMaxWidth().padding(start = if (item.grouped) 16.dp else 0.dp).height(40.dp).clip(MaterialTheme.shapes.medium)
                        .background(if (on) BROWSER_COLOR else Color.Transparent).combinedClickable(onClick = { switch(t) }, onLongClick = { options(t) }).padding(start = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        val pic = siteIcon(t.url, fetch) // said by its page as it loads, else the site's usual places (5 Nov: tabs showed letters)
                        Box(Modifier.size(26.dp).clip(CircleShape).background(if (pic != null) Color.White else fg.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                            if (pic != null) androidx.compose.foundation.Image(pic, null, Modifier.size(18.dp))
                            else Text(BrowserRules.site(BrowserRules.host(t.url)).take(1).uppercase().ifEmpty { "+" }, fontSize = 12.sp, color = fg, maxLines = 1)
                        }
                        Text(t.title.ifBlank { BrowserRules.host(t.url).removePrefix("www.") }.ifBlank { stringResource(R.string.browser_tab_new) }, Modifier.padding(start = 10.dp).weight(1f),
                            color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (t.media.playing) Icon(painterResource(R.drawable.ic_volume_up), null, Modifier.size(16.dp), tint = fg)
                        if (!t.pinned) IconButton(onClick = { close(t) }, Modifier.size(36.dp)) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.browser_tab_close), Modifier.size(16.dp), tint = fg) }
                        else Spacer(Modifier.width(12.dp))
                    }
                }
            }
        }
    }
}

@Composable private fun BrowserTabs(tabs: List<BrowserTab>, groups: Map<Int, String>, open: Set<Int>, current: Int, wide: Boolean, fetch: Boolean, switch: (BrowserTab) -> Unit, close: (BrowserTab) -> Unit,
                                    add: () -> Unit, fold: (Int) -> Unit, options: (BrowserTab) -> Unit, groupOptions: (Int) -> Unit, dropOn: (BrowserTab, String) -> Unit) =
    BoxWithConstraints(Modifier.fillMaxWidth().height(40.dp)) { // a fixed height: the bar measures its rows by their intrinsic height
    // a tablet: equal tabs, four across (more scroll); a phone: each as long as its name, up to 150 dp
    val width = if (wide) (maxWidth - 52.dp) / 4 - 6.dp else 0.dp
    val items = BrowserTabRow.items(tabs.map { TabInfo(it.id, it.pinned, it.group) }, open)
    val bounds = remember { mutableMapOf<String, androidx.compose.ui.geometry.Rect>() } // each chip on the screen, for a drop
    var dragging by remember { mutableStateOf<Int?>(null) }
    var moved by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        val scroll = androidx.compose.foundation.lazy.rememberLazyListState()
        LaunchedEffect(current, items.size) { items.indexOfFirst { it is TabRowItem.Tab && it.id == current }.takeIf { it >= 0 }?.let { scroll.animateScrollToItem(it) } } // a new tab in sight
        LazyRow(Modifier.weight(1f), state = scroll, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(items, key = { if (it is TabRowItem.Group) "g${it.id}" else "t${(it as TabRowItem.Tab).id}" }) { item ->
                when (item) {
                    is TabRowItem.Group -> Surface(shape = CircleShape, color = Color.Transparent, border = androidx.compose.foundation.BorderStroke(1.5.dp, BROWSER_COLOR),
                        modifier = Modifier.height(40.dp).onGloballyPositioned { bounds["g${item.id}"] = it.boundsInRoot() }
                            .pointerInput(item.id) { detectTapGestures(onTap = { fold(item.id) }, onLongPress = { groupOptions(item.id) }) }) {
                        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_folder), null, Modifier.size(18.dp), tint = BROWSER_COLOR)
                            Text(groups[item.id].orEmpty(), Modifier.padding(start = 6.dp).widthIn(max = 120.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${item.count}", Modifier.padding(start = 6.dp), color = BROWSER_COLOR, style = MaterialTheme.typography.labelLarge)
                            Icon(painterResource(if (item.open) R.drawable.ic_chevron_left else R.drawable.ic_chevron_right), null, Modifier.size(16.dp))
                        }
                    }
                    is TabRowItem.Tab -> {
                        val t = tabs.first { it.id == item.id }
                        val on = t.id == current
                        val key = "t${t.id}"
                        val initial = BrowserRules.site(BrowserRules.host(t.url)).take(1).uppercase().ifEmpty { "+" }
                        val gestures = Modifier.onGloballyPositioned { bounds[key] = it.boundsInRoot() }
                            .graphicsLayer { if (dragging == t.id) { translationX = moved.x; translationY = moved.y; alpha = 0.85f } }
                            .pointerInput(t.id) { detectTapGestures(onTap = { switch(t) }, onLongPress = {}) } // a long press is the drag's, below
                            .pointerInput(t.id) {
                                detectDragGesturesAfterLongPress(onDragStart = { dragging = t.id; moved = androidx.compose.ui.geometry.Offset.Zero },
                                    onDrag = { change, d -> change.consume(); moved += d },
                                    onDragEnd = {
                                        val at = bounds[key]?.center?.plus(moved)
                                        val target = at?.let { p -> bounds.entries.firstOrNull { it.key != key && it.value.contains(p) }?.key }
                                        if (moved.getDistance() < 24 * density) options(t) else if (target != null && !t.pinned) dropOn(t, target)
                                        dragging = null; moved = androidx.compose.ui.geometry.Offset.Zero
                                    },
                                    onDragCancel = { dragging = null; moved = androidx.compose.ui.geometry.Offset.Zero })
                            }
                        if (t.pinned) Box(gestures.size(40.dp).clip(CircleShape).background(if (on) BROWSER_COLOR else LocalContentColor.current.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                            val pic = siteIcon(t.url, fetch)
                            if (pic != null) Box(Modifier.size(28.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) { androidx.compose.foundation.Image(pic, null, Modifier.size(20.dp)) }
                            else Text(initial, color = if (on) Color.White else LocalContentColor.current, style = MaterialTheme.typography.labelLarge)
                        } else Surface(shape = CircleShape, color = if (on) BROWSER_COLOR else if (item.grouped) BROWSER_COLOR.copy(alpha = 0.18f) else LocalContentColor.current.copy(alpha = 0.08f),
                            contentColor = if (on) Color.White else LocalContentColor.current,
                            modifier = gestures.height(40.dp).then(if (wide) Modifier.width(width) else Modifier.widthIn(max = 150.dp))) {
                            Row(Modifier.padding(start = 6.dp, end = if (on) 0.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                val pic = siteIcon(t.url, fetch)
                                Box(Modifier.size(26.dp).clip(CircleShape).background(if (pic != null) Color.White else LocalContentColor.current.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                                    if (pic != null) androidx.compose.foundation.Image(pic, null, Modifier.size(18.dp)) else Text(initial, fontSize = 12.sp, maxLines = 1)
                                }
                                val name = t.title.ifBlank { BrowserRules.host(t.url).removePrefix("www.") }.ifBlank { stringResource(R.string.browser_tab_new) }
                                Text(name, Modifier.padding(start = 6.dp).weight(1f, fill = wide), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (on) IconButton(onClick = { close(t) }, Modifier.size(36.dp)) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.browser_tab_close), Modifier.size(16.dp)) }
                            }
                        }
                    }
                }
            }
        }
        IconButton(onClick = add, Modifier.size(40.dp)) { Icon(painterResource(R.drawable.ic_add), stringResource(R.string.browser_tab_new)) }
    }
}

internal fun addressLabel(a: Map<String, String>) = a["name"] ?: listOfNotNull(a["given-name"], a["family-name"]).joinToString(" ").ifEmpty { a["email"] ?: a["street-address"] ?: a.values.first() }

/**
 * The first page: the favourites as tiles (the user, 2 Nov: "or nothing"), then their folders ("so we can have Radios", 2 Nov),
 * a tap opening one; a tile held is put in a folder. Private says what it keeps.
 */
@Composable private fun BrowserStart(private: Boolean, library: BookmarkLibrary, file: (Bookmark) -> Unit, search: (() -> Unit)? = null, open: (String) -> Unit) =
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        var folder by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
        BackHandler(folder.isNotEmpty()) { folder = BookmarkLibrary.parentOf(folder) }
        if (private) Text(stringResource(R.string.browser_private_start), Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (folder.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { folder = BookmarkLibrary.parentOf(folder) }) { Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.common_back)) }
            Text(BookmarkLibrary.nameOf(folder), style = MaterialTheme.typography.titleMedium)
        }
        @Composable fun Tile(label: String, letter: String, color: Color, icon: Int?, click: () -> Unit, hold: (() -> Unit)?, picture: androidx.compose.ui.graphics.ImageBitmap? = null) =
            Column(Modifier.clip(MaterialTheme.shapes.medium).combinedClickable(onClick = click, onLongClick = hold).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(if (picture != null && icon == null) Color.White else color), contentAlignment = Alignment.Center) {
                    when {
                        icon != null -> Icon(painterResource(icon), null, tint = Color.White)
                        picture != null -> androidx.compose.foundation.Image(picture, null, Modifier.size(36.dp)) // the site's own icon (BrowserIcons)
                        else -> Text(letter, color = Color.White, style = MaterialTheme.typography.titleLarge)
                    }
                }
                Text(label, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        val (shown, folders) = library.start(folder)
        // the sidebar's new tab (the user's sketch, 4 Nov): "type an address" in the middle; a tap types it in the sidebar
        if (search != null && folder.isEmpty()) {
            val empty = shown.isEmpty() && folders.isEmpty()
            if (empty) Spacer(Modifier.weight(1f))
            Surface(onClick = search, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 32.dp, bottom = 8.dp).widthIn(max = 560.dp).fillMaxWidth(0.7f).height(52.dp)) {
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_search), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.browser_address), Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (empty) Spacer(Modifier.weight(1.4f))
        }
        if (shown.isNotEmpty() || folders.isNotEmpty()) LazyVerticalGrid(GridCells.Adaptive(88.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            gridItems(shown, key = { it.url }) { b ->
                val host = BrowserRules.site(BrowserRules.host(b.url))
                Tile(b.title.ifBlank { host }, host.take(1).uppercase(), TILE_COLORS[(host.hashCode() and 0x7fffffff) % TILE_COLORS.size], null, { open(b.url) }, { file(b) },
                    picture = siteIcon(b.url, fetch = !private))
            }
            gridItems(folders, key = { "folder:$it" }) { f -> Tile(BookmarkLibrary.nameOf(f), "", BROWSER_COLOR, R.drawable.ic_folder, { folder = f }, null) }
        }
    }

/**
 * Into a folder: one of those there are (the tree, each under its parent), a new one, or none. [tree] is
 * BookmarkLibrary.tree(), less what may not be chosen (a folder moved, and those in it); a new one is made in [current].
 */
@Composable internal fun FolderDialog(title: String, current: String, tree: List<String>, close: () -> Unit, put: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                (listOf("") + tree).forEach { f ->
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { put(f) }.padding(start = (4 + 20 * f.count { it == '/' }).dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = current == f, onClick = { put(f) })
                        if (f.isNotEmpty()) Icon(painterResource(R.drawable.ic_folder), null, Modifier.padding(end = 8.dp).size(18.dp), tint = BROWSER_COLOR)
                        Text(if (f.isEmpty()) stringResource(R.string.browser_no_folder) else BookmarkLibrary.nameOf(f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // "Radios/Greek" makes both; a new folder goes in the one this is in now
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.browser_new_folder)) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { put(BookmarkLibrary.join(current, BookmarkLibrary.path(name))) }, enabled = BookmarkLibrary.path(name).isNotEmpty()) { Text(stringResource(R.string.common_add)) } },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.common_cancel)) } })
}

internal val TILE_COLORS = listOf(Color(0xFF1A87FD), Color(0xFF2E7D32), Color(0xFFC62828), Color(0xFF6A1B9A), Color(0xFFEF6C00), Color(0xFF00838F), Color(0xFF4E342E), Color(0xFFAD1457))

@Composable private fun BrowserRow(title: String, url: String, click: () -> Unit, remove: (() -> Unit)? = null) =
    Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(title.ifEmpty { BrowserRules.host(url) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (remove != null) IconButton(onClick = remove) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.common_delete)) }
    }

@Composable
private fun BrowserPanelView(p: BrowserPanel, close: () -> Unit, visits: List<Visit>, vault: BrowserVault, imported: Pair<String, Uri?>?,
                             open: (String) -> Unit, keepHistory: String, clearHistory: () -> Unit, keep: (BrowserVault) -> Unit, choose: () -> Unit, deleteFile: (Uri) -> Unit) =
    Box(Modifier.navigationBarsPadding()) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(when (p) { BrowserPanel.Bookmarks -> R.string.browser_bookmarks; BrowserPanel.History -> R.string.browser_history
                        BrowserPanel.Import -> R.string.browser_import }), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    if (p == BrowserPanel.History && visits.isNotEmpty()) TextButton(onClick = clearHistory) { Text(stringResource(R.string.common_clear)) }
                    IconButton(onClick = close) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.common_close)) }
                }
            }
            @Composable fun note(text: String) = Text(text, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            @Composable fun head(text: String) = Text(text, Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = BROWSER_COLOR)
            when (p) {
                BrowserPanel.Bookmarks -> Unit // BookmarksManager
                BrowserPanel.History -> {
                    item { note(stringResource(when { visits.isEmpty() -> R.string.browser_no_history; keepHistory == BrowserRules.ON_EXIT -> R.string.browser_history_exit_note
                        keepHistory == BrowserRules.NEVER -> R.string.browser_history_never_note; else -> R.string.browser_history_note })) }
                    items(visits, key = { it.url }) { v -> BrowserRow(v.title, v.url, click = { open(v.url) }) }
                }
                BrowserPanel.Import -> item {
                    note(stringResource(R.string.browser_import_note))
                    Button(onClick = choose, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = BROWSER_COLOR, contentColor = Color.White)) {
                        Text(stringResource(R.string.browser_choose_file))
                    }
                    imported?.let { (said, file) ->
                        Text(said, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyLarge)
                        if (file != null) {
                            note(stringResource(R.string.browser_import_delete))
                            OutlinedButton(onClick = { deleteFile(file) }, Modifier.padding(horizontal = 20.dp)) { Text(stringResource(R.string.browser_delete_file)) }
                        }
                    }
                }
            }
        }
    }

/** Fill: what fits this page, a strip from the bottom (no popup lists on a touch screen); `fill` gets the values and, for a password, its host. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserFillStrip(logins: List<SavedLogin>, addresses: List<Map<String, String>>, cards: List<SavedCard>, dismiss: () -> Unit, manage: () -> Unit, fill: (JSONObject, String?) -> Unit) {
    BrowserSheet(onDismissRequest = dismiss) {
        Column(Modifier.navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            Text(stringResource(R.string.browser_fill_title), Modifier.padding(horizontal = 8.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
            logins.forEach { l -> BrowserRow(l.user.ifEmpty { "—" }, l.host, click = { fill(JSONObject().put("username", l.user).put("password", l.password), l.host) }) }
            addresses.forEach { a -> BrowserRow(addressLabel(a), a.values.joinToString(", "), click = { fill(JSONObject(a), null) }) }
            cards.forEach { c -> BrowserRow("•••• ${c.last4}", c.name, click = {
                fill(JSONObject().put("cc-number", c.number).put("cc-name", c.name).put("cc-exp-month", c.month).put("cc-exp-year", c.year).put("cc-exp", "${c.month}/${c.year.takeLast(2)}"), null)
            }) }
            // the Passwords applet: this site's logins found, to see, change or add one
            TextButton(onClick = manage, Modifier.padding(top = 4.dp)) {
                Icon(painterResource(R.drawable.ic_key), null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.pw_manage))
            }
        }
    }
}

/** A button of ☰'s strip: its icon only (the user, 2 Nov: "SVGs for the buttons not text"); the name is for TalkBack. */
/** A tool on the bar: its icon, lit when on (Fill: when something fits), an extension's badge beside it (uBlock's count); a long press edits the bar. */
@OptIn(ExperimentalFoundationApi::class)
@Composable private fun BrowserSmall(icon: Int, image: androidx.compose.ui.graphics.ImageBitmap?, label: String, on: Boolean, enabled: Boolean, badge: String?, size: Modifier, long: () -> Unit, click: () -> Unit) {
    val fg = if (on) Color.White else LocalContentColor.current.copy(alpha = if (enabled) 1f else 0.35f)
    Row(size.clip(CircleShape).background(if (on) BROWSER_COLOR else LocalContentColor.current.copy(alpha = 0.08f))
        .combinedClickable(onClick = { if (enabled) click() }, onLongClick = long, onClickLabel = label, onLongClickLabel = stringResource(R.string.browser_edit_tools)),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (image != null) androidx.compose.foundation.Image(image, label, Modifier.size(18.dp).graphicsLayer { alpha = if (enabled) 1f else 0.4f })
        else Icon(painterResource(icon), label, Modifier.size(18.dp), tint = fg)
        if (badge != null) Text(badge, Modifier.padding(start = 3.dp), color = fg, fontSize = 11.sp, maxLines = 1)
    }
}

/** Player mode (the user's sketch, 2 Nov): under the page, ‹ a larger play › in a row, and a small exit above ›. */
@Composable private fun BrowserPlayer(modifier: Modifier, media: PageMedia, key: (String) -> Unit, marked: Boolean, heart: () -> Unit, forMaps: Boolean,
    setForMaps: () -> Unit, exit: () -> Unit) =
    Column(modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
        // ♥ here too: a station kept is one ‹ › go through (BrowserRules.hop)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(media.title, Modifier.weight(1f, fill = false), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = heart) {
                Icon(painterResource(if (marked) R.drawable.ic_favorite else R.drawable.ic_favorite_border), stringResource(R.string.browser_favorite), tint = if (marked) BROWSER_COLOR else LocalContentColor.current)
            }
            // 🚗: this page is what Maps' ♪ plays
            IconButton(onClick = setForMaps) {
                Icon(painterResource(R.drawable.ic_directions_car), stringResource(R.string.browser_for_maps), tint = if (forMaps) BROWSER_COLOR else LocalContentColor.current)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            PlayerRound(R.drawable.ic_skip_previous, stringResource(R.string.browser_previous), 72) { key("prev") }
            PlayerRound(if (media.playing) R.drawable.ic_pause else R.drawable.ic_play, stringResource(if (media.playing) R.string.common_pause else R.string.browser_play), 96, lit = true) { key("toggle") }
            Box(contentAlignment = Alignment.Center) {
                PlayerRound(R.drawable.ic_skip_next, stringResource(R.string.browser_next), 72) { key("next") }
                Surface(onClick = exit, shape = CircleShape, color = LocalContentColor.current.copy(alpha = 0.1f), modifier = Modifier.align(Alignment.TopCenter).offset(y = (-48).dp).size(36.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.browser_player_exit), Modifier.size(18.dp)) }
                }
            }
        }
    }

@Composable private fun PlayerRound(icon: Int, label: String, size: Int, lit: Boolean = false, click: () -> Unit) =
    Surface(onClick = click, shape = CircleShape, color = if (lit) BROWSER_COLOR else LocalContentColor.current.copy(alpha = 0.1f),
        contentColor = if (lit) Color.White else LocalContentColor.current, modifier = Modifier.size(size.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), label, Modifier.size((size * 0.45f).dp)) }
    }
