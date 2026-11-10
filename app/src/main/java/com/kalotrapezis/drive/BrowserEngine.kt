package com.kalotrapezis.drive

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.Image
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/** An extension's button: as it is (its badge, its title) and its icon, drawn once. */
internal class ExtAction(val action: WebExtension.Action, val icon: ImageBitmap?)

/** An install (or an update asking for more) waiting for the user: [answer] (allowed, allowed in Private). */
internal class ExtAsk(val ext: WebExtension, val permissions: List<String>, val update: Boolean, val answer: (Boolean, Boolean) -> Unit)

/** The open Browser, for what extensions do with tabs: a new one (its session left unopened, Gecko opens it), one closed, one chosen. */
internal interface ExtTabs {
    fun newTab(url: String?): GeckoSession?
    fun close(session: GeckoSession)
    fun show(session: GeckoSession)
    fun open(url: String)
}

/**
 * The Browser's engine (the user, 3 Nov: Firefox's, "if we can integrate Firefox extensions"): GeckoView (MPL 2.0), one
 * runtime per process, made on first use and never closed. Tetra's own extension (assets/tetra-ext) carries the forms and
 * the media keys; uBlock Origin and Dark Reader come on the first start; the user's extensions are kept by Gecko itself.
 */
internal object BrowserEngine {
    const val BUILT_IN = "browser@tetra" // assets/tetra-ext: raise its manifest's version with every change, or Gecko keeps the old one
    const val UBLOCK = "uBlock0@raymondhill.net"
    const val DARK_READER = "addon@darkreader.org"
    /** The user, 3 Nov: uBlock Origin instead of the EasyList blocker, Dark Reader for dark pages; each installed once, never again once removed. */
    private val AUTO = listOf(UBLOCK to "ublock-origin", DARK_READER to "darkreader")
    const val GET_MORE = "https://addons.mozilla.org/android/"

    private var rt: GeckoRuntime? = null
    private lateinit var app: Context
    fun runtime(c: Context): GeckoRuntime = rt ?: create(c.applicationContext, c.resources.configuration.locales[0].toLanguageTag()).also { rt = it } // c: the Browser's, in Tetra's language

    /** The user's extensions (never Tetra's own), in Gecko's order. */
    val extensions = mutableStateListOf<WebExtension>()
    val icons = mutableStateMapOf<String, ImageBitmap>()
    /** Each extension's button as it is by default, and a tab's own (uBlock's count of that page). */
    val actions = mutableStateMapOf<String, ExtAction>()
    val tabActions = mutableStateMapOf<GeckoSession, Map<String, ExtAction>>()
    var ask by mutableStateOf<ExtAsk?>(null)
    var popup by mutableStateOf<Pair<WebExtension, GeckoSession>?>(null)
    var note by mutableStateOf<String?>(null) // said once by the Browser (installed, failed)
    var tabs: ExtTabs? = null
    private var builtIn: WebExtension? = null
    private val waiting = mutableListOf<(WebExtension) -> Unit>()
    private val silent = mutableSetOf<String>() // being installed by Tetra itself: no question
    private val quiet = mutableMapOf<String, Long>() // installed by Tetra itself, when: its welcome tab is not opened
    private val sessions = mutableSetOf<GeckoSession>() // the tabs' sessions, each told every extension's delegates

    /** Tetra's own extension, once Gecko has it (at once after the first time). */
    fun withBuiltIn(f: (WebExtension) -> Unit) { builtIn?.let(f) ?: waiting.add(f) }

    fun effective(session: GeckoSession?, id: String): ExtAction? {
        val base = actions[id]
        val own = session?.let { tabActions[it]?.get(id) } ?: return base
        return ExtAction(if (base != null) own.action.withDefault(base.action) else own.action, own.icon ?: base?.icon)
    }

    private fun scheme() = if (TetraLook.light) GeckoRuntimeSettings.COLOR_SCHEME_LIGHT else GeckoRuntimeSettings.COLOR_SCHEME_DARK

    private fun create(c: Context, language: String): GeckoRuntime {
        app = c
        val t0 = System.currentTimeMillis()
        val r = GeckoRuntime.create(c, GeckoRuntimeSettings.Builder()
            .extensionsWebAPIEnabled(true) // addons.mozilla.org's own "Add to Firefox"
            .extensionsProcessEnabled(true)
            .preferredColorScheme(scheme()) // pages follow Tetra's look: sites' own dark look in the dark one
            .contentBlocking(ContentBlocking.Settings.Builder() // Firefox's Enhanced Tracking Protection, standard (the user, 3 Nov)
                .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.DEFAULT)
                .antiTracking(ContentBlocking.AntiTracking.DEFAULT or ContentBlocking.AntiTracking.STP or ContentBlocking.AntiTracking.CRYPTOMINING or ContentBlocking.AntiTracking.FINGERPRINTING)
                .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
                .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
                .build())
            .locales(arrayOf(language)) // Gecko's own words (an alert's title) and Accept-Language in Tetra's language
            .loginAutofillEnabled(false) // Tetra's own vault fills (assets/tetra-ext)
            .consoleOutput(false)
            .build())
        WriterLog.i("Browser: Gecko runtime made in ${System.currentTimeMillis() - t0} ms")
        val ctl = r.webExtensionController
        ctl.promptDelegate = prompts
        ctl.setAddonManagerDelegate(object : WebExtensionController.AddonManagerDelegate {
            override fun onInstalled(extension: WebExtension) {
                WriterLog.i("Browser: extension installed ${extension.id} ${extension.metaData.version}"); refresh()
                if (extension.id !in silent && extension.id != BUILT_IN && !extension.isBuiltIn) note = c.getString(R.string.browser_ext_added, extension.metaData.name ?: extension.id)
            }
            override fun onUninstalled(extension: WebExtension) { WriterLog.i("Browser: extension removed ${extension.id}"); refresh() }
            override fun onEnabled(extension: WebExtension) = refresh()
            override fun onDisabled(extension: WebExtension) = refresh()
            override fun onReady(extension: WebExtension) = refresh() // after a start: the list is only whole once each is ready
            override fun onInstallationFailed(extension: WebExtension?, installException: WebExtension.InstallException) {
                WriterLog.w("Browser: an extension not installed (${extension?.id}, code ${installException.code})")
                if (extension?.id !in silent) note = c.getString(R.string.browser_ext_failed)
            }
        })
        ctl.ensureBuiltIn("resource://android/assets/tetra-ext/", BUILT_IN).accept({ ext ->
            ext ?: return@accept
            WriterLog.i("Browser: Tetra's own extension ready")
            builtIn = ext; waiting.forEach { it(ext) }; waiting.clear()
            // its forms stay out of Private (the Browser ignores them there); its media keys work there too
            if (!ext.metaData.allowedInPrivateBrowsing) ctl.setAllowedInPrivateBrowsing(ext, true)
        }, { e -> WriterLog.e("Browser: Tetra's own extension not installed", e); null })
        refresh()
        return r
    }

    /** The list again from Gecko, each with its delegates, icon and button; Dark Reader set to Tetra's look. */
    fun refresh() {
        val r = rt ?: return
        r.webExtensionController.list().accept({ all ->
            val mine = all.orEmpty().filter { !it.isBuiltIn && it.id != BUILT_IN }
            extensions.clear(); extensions.addAll(mine)
            mine.forEach { ext ->
                ext.setActionDelegate(actionDelegate)
                ext.tabDelegate = tabDelegate
                sessions.forEach { wire(it, ext) }
                if (ext.id !in icons) ext.metaData.icon.getBitmap(96).accept({ b -> b?.let { icons[ext.id] = it.asImageBitmap() } }, { null })
            }
            actions.keys.retainAll(mine.filter { it.metaData.enabled }.map { it.id }.toSet())
            followLook()
        }, { e -> WriterLog.e("Browser: the extensions not listed", e); null })
    }

    /** A tab's session: told every extension's delegates (for its own buttons and its tabs); [forget] when the tab closes. */
    fun register(session: GeckoSession) { sessions += session; extensions.forEach { wire(session, it) } }
    fun forget(session: GeckoSession) { sessions -= session; tabActions.remove(session) }
    private fun wire(s: GeckoSession, ext: WebExtension) {
        s.webExtensionController.setActionDelegate(ext, actionDelegate)
        s.webExtensionController.setTabDelegate(ext, sessionTabs)
    }

    private val actionDelegate = object : WebExtension.ActionDelegate {
        override fun onBrowserAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) = act(extension, session, action)
        override fun onPageAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) = act(extension, session, action)
        override fun onTogglePopup(extension: WebExtension, action: WebExtension.Action) = openPopup(extension)
        override fun onOpenPopup(extension: WebExtension, action: WebExtension.Action) = openPopup(extension)
    }

    private fun act(ext: WebExtension, session: GeckoSession?, action: WebExtension.Action) {
        fun put(icon: ImageBitmap?) {
            if (session == null) actions[ext.id] = ExtAction(action, icon ?: actions[ext.id]?.icon)
            else if (session in sessions) tabActions[session] = tabActions[session].orEmpty() + (ext.id to ExtAction(action, icon))
        }
        val image: Image? = action.icon
        if (image == null) put(null) else image.getBitmap(96).accept({ b -> put(b?.asImageBitmap()) }, { put(null); null })
    }

    private fun openPopup(ext: WebExtension): GeckoResult<GeckoSession>? {
        val r = rt ?: return null
        popup?.second?.close()
        val s = GeckoSession(GeckoSessionSettings.Builder().build())
        s.contentDelegate = object : GeckoSession.ContentDelegate { override fun onCloseRequest(session: GeckoSession) { closePopup() } }
        s.open(r)
        WriterLog.i("Browser: ${ext.id}'s popup")
        popup = ext to s
        return GeckoResult.fromValue(s)
    }
    fun closePopup() { popup?.second?.close(); popup = null }

    private val tabDelegate = object : WebExtension.TabDelegate {
        override fun onNewTab(source: WebExtension, createDetails: WebExtension.CreateTabDetails): GeckoResult<GeckoSession>? {
            // Dark Reader's help page right after Tetra installed it on its own: the first start stays where the user is
            if (System.currentTimeMillis() - (quiet[source.id] ?: 0) < 30_000) { WriterLog.i("Browser: ${source.id}'s welcome tab not opened"); return null }
            closePopup()
            return tabs?.newTab(createDetails.url)?.let { GeckoResult.fromValue(it) }
        }
        override fun onOpenOptionsPage(source: WebExtension) { closePopup(); source.metaData.optionsPageUrl?.let { tabs?.open(it) } }
    }
    private val sessionTabs = object : WebExtension.SessionTabDelegate {
        override fun onCloseTab(source: WebExtension?, session: GeckoSession): GeckoResult<AllowOrDeny> { tabs?.close(session); return GeckoResult.allow() }
        override fun onUpdateTab(extension: WebExtension, session: GeckoSession, updateDetails: WebExtension.UpdateTabDetails): GeckoResult<AllowOrDeny> {
            if (updateDetails.active == true) tabs?.show(session)
            return GeckoResult.allow()
        }
    }

    private val prompts = object : WebExtensionController.PromptDelegate {
        override fun onInstallPromptRequest(extension: WebExtension, permissions: Array<String>, origins: Array<String>, dataCollectionPermissions: Array<String>): GeckoResult<WebExtension.PermissionPromptResponse> {
            if (extension.id in silent) return GeckoResult.fromValue(WebExtension.PermissionPromptResponse(true, true, false))
            val r = GeckoResult<WebExtension.PermissionPromptResponse>()
            ask = ExtAsk(extension, (permissions + origins).toList(), false) { ok, private -> ask = null; r.complete(WebExtension.PermissionPromptResponse(ok, private, false)) }
            return r
        }
        override fun onUpdatePrompt(extension: WebExtension, newPermissions: Array<String>, newOrigins: Array<String>, newDataCollectionPermissions: Array<String>) = asked(extension, newPermissions + newOrigins)
        override fun onOptionalPrompt(extension: WebExtension, permissions: Array<String>, origins: Array<String>, dataCollectionPermissions: Array<String>) = asked(extension, permissions + origins)
    }
    private fun asked(ext: WebExtension, what: Array<String>): GeckoResult<AllowOrDeny> {
        val r = GeckoResult<AllowOrDeny>()
        ask = ExtAsk(ext, what.toList(), true) { ok, _ -> ask = null; r.complete(if (ok) AllowOrDeny.ALLOW else AllowOrDeny.DENY) }
        return r
    }

    private fun prefs() = app.getSharedPreferences("browser", Context.MODE_PRIVATE)

    /**
     * The Browser's first start: uBlock Origin and Dark Reader from addons.mozilla.org, in the background, without a
     * question. Each once: remembered when it has come, so one the user removes stays removed; a failure (no network) tries
     * again next time. Then, once a day, every extension's update.
     */
    fun firstStart(c: Context) {
        val ctl = runtime(c).webExtensionController
        followLook() // the look may have changed since the runtime was made
        val done = prefs().getStringSet("ext_auto", null).orEmpty()
        AUTO.filter { it.first !in done && it.first !in silent }.forEach { (id, slug) ->
            silent += id; quiet[id] = System.currentTimeMillis()
            WriterLog.i("Browser: installing $slug")
            ctl.install("https://addons.mozilla.org/firefox/downloads/latest/$slug/latest.xpi", WebExtensionController.INSTALLATION_METHOD_MANAGER).accept({ ext ->
                silent -= id
                quiet[id] = System.currentTimeMillis()
                prefs().edit().putStringSet("ext_auto", prefs().getStringSet("ext_auto", null).orEmpty() + id).apply()
                WriterLog.i("Browser: $slug ${ext?.metaData?.version} installed")
            }, { e -> silent -= id; WriterLog.w("Browser: $slug not installed, again next time", e); null })
        }
        val now = System.currentTimeMillis()
        if (now - prefs().getLong("ext_updated", 0) < 24 * 3600_000L) return
        prefs().edit().putLong("ext_updated", now).apply()
        ctl.list().accept({ all -> all.orEmpty().filter { !it.isBuiltIn && it.id != BUILT_IN }.forEach { ext ->
            ctl.update(ext).accept({ u -> if (u != null) WriterLog.i("Browser: ${ext.id} updated to ${u.metaData.version}") }, { e -> WriterLog.w("Browser: ${ext.id} not updated", e); null })
        } }, { null })
    }

    /** Pages in Tetra's look; Dark Reader on in the dark one, off in the light — set when the look changes, so the user's own switch holds until then. */
    private fun followLook() {
        rt?.settings?.preferredColorScheme = scheme()
        val dr = extensions.firstOrNull { it.id == DARK_READER } ?: return
        val look = if (TetraLook.light) "light" else "dark"
        if (prefs().getString("ext_dark_look", null) == look) return
        prefs().edit().putString("ext_dark_look", look).apply()
        WriterLog.i("Browser: Dark Reader ${if (TetraLook.light) "off" else "on"} for Tetra's $look look")
        setEnabled(dr, !TetraLook.light)
    }

    fun setEnabled(ext: WebExtension, on: Boolean) {
        val ctl = rt?.webExtensionController ?: return
        (if (on) ctl.enable(ext, WebExtensionController.EnableSource.USER) else ctl.disable(ext, WebExtensionController.EnableSource.USER))
            .accept({ refresh() }, { e -> WriterLog.w("Browser: ${ext.id} not switched", e); null })
    }
    fun setPrivate(ext: WebExtension, on: Boolean) =
        rt?.webExtensionController?.setAllowedInPrivateBrowsing(ext, on)?.accept({ refresh() }, { e -> WriterLog.w("Browser: ${ext.id}'s Private not set", e); null })
    fun remove(ext: WebExtension) =
        rt?.webExtensionController?.uninstall(ext)?.accept({ refresh() }, { e -> WriterLog.w("Browser: ${ext.id} not removed", e); null })
    /** An .xpi a page offered outside addons.mozilla.org: installed after the same question. */
    fun install(url: String) = rt?.webExtensionController?.install(url)?.accept({ }, { e -> WriterLog.w("Browser: $url not installed", e); null })
}
