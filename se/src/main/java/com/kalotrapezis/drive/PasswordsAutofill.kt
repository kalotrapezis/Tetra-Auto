package com.kalotrapezis.drive

import android.app.Activity
import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.os.Bundle
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import android.widget.Toast

/*
 * Passwords as Android's autofill service (the user, 4 Nov: "Can I set it as Android autocomplete?"): chosen in Android's
 * settings, it offers the vault's logins in other apps and in browsers, opened only by the fingerprint or the screen lock
 * ([AutofillUnlock]), and keeps a login typed there. Tetra's own Browser has its Fill, so Tetra's windows are left alone.
 */

/** A login form as Android describes it: its fields, which is the user, which the password, and whose logins fit. */
internal object AutofillForm {
    /** A text field: [id] the AutofillId (Any, so tests need no Android), [words] its id, hint and HTML name lower-cased. */
    data class Field(val id: Any?, val hints: List<String> = emptyList(), val htmlType: String = "", val autocomplete: String = "",
                     val inputType: Int = 0, val words: String = "", val value: String = "", val maxLength: Int = -1, val inputMode: String = "")
    data class Form(val user: Field?, val passwords: List<Field>)

    private const val CLASS = 0xF; private const val TEXT = 1; private const val NUMBER = 2; private const val VARIATION = 0xFF0
    private val PASSWORD_VARIATIONS = setOf(0x80, 0x90, 0xE0) // password, visible password, web password
    private val EMAIL_VARIATIONS = setOf(0x20, 0xD0)
    private val USER_WORDS = Regex("user|e-?mail|login|account|username|nickname|χρήστ|όνομα")

    fun isPassword(f: Field) = f.hints.any { it.contains("password", true) } || f.autocomplete in setOf("current-password", "new-password") ||
        f.htmlType == "password" || (f.inputType and CLASS == TEXT && (f.inputType and VARIATION) in PASSWORD_VARIATIONS) ||
        (f.inputType and CLASS == NUMBER && (f.inputType and VARIATION) == 0x10)

    private fun isText(f: Field) = f.inputType and CLASS == TEXT || f.htmlType in setOf("text", "email", "tel", "")

    fun isUser(f: Field) = !isPassword(f) && !isOtp(f) && (f.hints.any { it.lowercase() in setOf("username", "emailaddress", "email", "phone") } ||
        f.autocomplete in setOf("username", "email", "tel") || (f.inputType and CLASS == TEXT && (f.inputType and VARIATION) in EMAIL_VARIATIONS) ||
        (isText(f) && USER_WORDS.containsMatchIn(f.words)))

    private val OTP_WORDS = Regex("code|otp|2fa|mfa|totp|verif|κωδικ")
    private val NOT_OTP_WORDS = Regex("post|zip|promo|coupon|country|area")

    /**
     * A one-time code's field (the user, 10 Nov: "Can Android tell when a field is a 2FA field?"): Android's hint
     * (smsOTPCode, 2faAppOTPCode, and smsOTPCode1… for a box a digit), HTML's autocomplete="one-time-code"; else a field
     * of numbers, 6–8 long or of no stated length, whose id or hint says code, otp, 2fa or κωδικός (a post code is not).
     */
    fun isOtp(f: Field): Boolean {
        if (isPassword(f)) return false
        if (f.hints.any { it.contains("otp", true) } || f.autocomplete == "one-time-code") return true
        val numeric = f.inputType and CLASS == NUMBER || f.htmlType in setOf("number", "tel") || f.inputMode == "numeric"
        return numeric && (f.maxLength == -1 || f.maxLength in 6..8) && OTP_WORDS.containsMatchIn(f.words) && !NOT_OTP_WORDS.containsMatchIn(f.words)
    }

    /** The field to give a 2FA code to: one of [fields] when the page asks no password (a code's page comes after the password's). */
    fun otp(fields: List<Field>): Field? = fields.firstOrNull(::isOtp)?.takeIf { find(fields)?.passwords.isNullOrEmpty() }

    private val GENERIC = setOf("com", "org", "net", "android", "app", "apps", "mobile", "www", "auth", "login", "accounts")

    /**
     * The 2FA codes for this page or app: those whose issuer (or the account's e-mail domain) is the site's name (github
     * for github.com) or a word of the app's package (com.github.android); when none fits, all of them, to choose from.
     * shortcut: a package is matched by its words, not by who signed it; harmless, as every code is offered when unsure.
     */
    fun codesFor(entries: List<OtpEntry>, webDomain: String?, pkg: String): List<OtpEntry> {
        val d = webDomain?.trim()?.lowercase()?.removePrefix("www.").orEmpty()
        val names = (if (pkg in BROWSERS && d.isNotEmpty()) listOf(BrowserRules.site(d).substringBefore('.')) else pkg.lowercase().split('.'))
            .filter { it.length >= 3 && it !in GENERIC }
        fun plain(s: String) = s.lowercase().filter(Char::isLetterOrDigit)
        val fit = entries.filter { e ->
            val i = plain(e.issuer); val mailDomain = e.account.lowercase().substringAfter('@', "")
            names.any { n -> n in i || (i.length >= 3 && i in n) || (mailDomain.isNotEmpty() && n in mailDomain) }
        }
        return fit.ifEmpty { entries }
    }

    /**
     * The form among [fields] (in screen order): its password fields, and the user's field — one that says so, before the
     * password if any, else the text field just before it. A page with a user field and no password (Google's sign-in asks
     * them one at a time) is a form of the user alone. Null: nothing to fill.
     */
    fun find(fields: List<Field>): Form? {
        val passwords = fields.filter(::isPassword)
        if (passwords.isEmpty()) return fields.firstOrNull(::isUser)?.let { Form(it, emptyList()) }
        val before = fields.take(fields.indexOf(passwords.first()))
        return Form(before.lastOrNull(::isUser) ?: fields.firstOrNull(::isUser) ?: before.lastOrNull { isText(it) && !isPassword(it) }, passwords)
    }

    // shortcut: a browser is known by its package; others' web domains are not trusted. Add one here when the user uses another.
    val BROWSERS = setOf("com.android.chrome", "com.chrome.beta", "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix",
        "org.mozilla.focus", "com.brave.browser", "com.microsoft.emmx", "com.vivaldi.browser", "com.opera.browser", "com.duckduckgo.mobile.android",
        "com.sec.android.app.sbrowser", "com.mi.globalbrowser", "com.android.browser", "org.chromium.chrome", "com.kiwibrowser.browser")

    /**
     * The saved logins for this form: in a browser, the page's site (accounts.x.com's for x.com's); in an app, only the logins
     * kept for that very app (its package, as Google's export and a save here name it). An app's word for which site it shows
     * is never taken, nor a package that looks like a site's (com.google.evil is not google.com): it could be anyone's.
     */
    fun matches(logins: List<SavedLogin>, webDomain: String?, pkg: String): List<SavedLogin> {
        val d = webDomain?.trim()?.lowercase()?.removePrefix("www.").orEmpty()
        return if (pkg in BROWSERS && d.isNotEmpty()) logins.filter { BrowserRules.site(it.host) == BrowserRules.site(d) }
        else logins.filter { it.host == pkg }
    }

    /** What a login kept from this form is for: the page's host in a browser, else the app. */
    fun hostFor(webDomain: String?, pkg: String) = webDomain?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && pkg in BROWSERS } ?: pkg

    /** Every visible text field of [s] in screen order, and the web page's domain when a browser gives one. */
    fun read(s: AssistStructure): Pair<List<Field>, String?> {
        val out = mutableListOf<Field>(); var domain: String? = null
        fun walk(n: AssistStructure.ViewNode) {
            if (n.visibility != View.VISIBLE) return
            if (domain == null) n.webDomain?.takeIf { it.isNotEmpty() }?.let { domain = it }
            val tag = n.htmlInfo?.tag?.lowercase()
            if (n.autofillId != null && n.autofillType == View.AUTOFILL_TYPE_TEXT && (tag == null || tag == "input")) {
                val attrs = n.htmlInfo?.attributes.orEmpty().associate { it.first.lowercase() to it.second.orEmpty() }
                out += Field(n.autofillId, n.autofillHints?.filterNotNull().orEmpty(), attrs["type"].orEmpty().lowercase(), attrs["autocomplete"].orEmpty().lowercase(),
                    n.inputType, listOfNotNull(n.idEntry, n.hint, attrs["name"], attrs["id"]).joinToString(" ").lowercase(),
                    n.autofillValue?.takeIf { it.isText }?.textValue?.toString().orEmpty(),
                    attrs["maxlength"]?.toIntOrNull() ?: n.maxTextLength, attrs["inputmode"].orEmpty().lowercase())
            }
            for (i in 0 until n.childCount) walk(n.getChildAt(i))
        }
        for (i in 0 until s.windowNodeCount) walk(s.getWindowNodeAt(i).rootViewNode)
        return out to domain
    }

    /**
     * The same row for the keyboard's strip, when the keyboard asks for one ([spec] its look for this place): Gboard shows
     * no list of Android's own then, so without it nothing is offered at all (seen on an emulator, 4 Nov).
     */
    fun inline(c: Context, spec: android.widget.inline.InlinePresentationSpec?, title: String, text: String): android.service.autofill.InlinePresentation? {
        if (spec == null || androidx.autofill.inline.UiVersions.INLINE_UI_VERSION_1 !in androidx.autofill.inline.UiVersions.getVersions(spec.style)) return null
        val tap = PendingIntent.getActivity(c, 0, Intent(c, PasswordsActivity::class.java), PendingIntent.FLAG_IMMUTABLE) // a long press: whose row it is
        val slice = androidx.autofill.inline.v1.InlineSuggestionUi.newContentBuilder(tap).setTitle(title).setSubtitle(text)
            .setStartIcon(android.graphics.drawable.Icon.createWithResource(c, R.mipmap.applet_passwords)).build().slice
        return android.service.autofill.InlinePresentation(slice, spec, false)
    }
    /** The keyboard's look for its [i]th suggestion (the last one repeats), or null when it asks for none. */
    fun spec(r: android.view.inputmethod.InlineSuggestionsRequest?, i: Int) = r?.inlinePresentationSpecs?.let { it.getOrNull(i) ?: it.lastOrNull() }
        ?.takeIf { i < r.maxSuggestionCount }

    fun row(c: Context, title: String, text: String) = RemoteViews(c.packageName, R.layout.autofill_item).apply {
        setTextViewText(R.id.title, title); setTextViewText(R.id.text, text)
    }

    @Suppress("DEPRECATION") // Dataset.Builder(RemoteViews) and setValue: the forms that work from Android 11 (minSdk 30) on
    fun datasets(c: Context, form: Form, logins: List<SavedLogin>, inlineAsk: android.view.inputmethod.InlineSuggestionsRequest? = null): FillResponse? {
        val b = FillResponse.Builder(); var any = false
        logins.forEachIndexed { i, l ->
            val d = Dataset.Builder(row(c, l.user.ifEmpty { "—" }, l.host))
            inline(c, spec(inlineAsk, i), l.user.ifEmpty { "—" }, l.host)?.let { d.setInlinePresentation(it) }
            form.user?.let { d.setValue(it.id as AutofillId, AutofillValue.forText(l.user)) }
            form.passwords.forEach { d.setValue(it.id as AutofillId, AutofillValue.forText(l.password)) }
            if (form.user != null || form.passwords.isNotEmpty()) { b.addDataset(d.build()); any = true }
        }
        return if (any) b.build() else null
    }

    /** A row a 2FA code ([codes]' issuer and account), its code now in [field]: worked out at the unlock, so it is fresh. */
    @Suppress("DEPRECATION")
    fun codeDatasets(c: Context, field: Field, codes: List<OtpEntry>, inlineAsk: android.view.inputmethod.InlineSuggestionsRequest? = null): FillResponse? {
        if (codes.isEmpty()) return null
        val b = FillResponse.Builder()
        codes.forEachIndexed { i, e ->
            val title = e.issuer.ifEmpty { e.account }.ifEmpty { "2FA" }; val text = e.account.takeIf { e.issuer.isNotEmpty() }.orEmpty()
            val d = Dataset.Builder(row(c, title, text))
            inline(c, spec(inlineAsk, i), title, text)?.let { d.setInlinePresentation(it) }
            b.addDataset(d.setValue(field.id as AutofillId, AutofillValue.forText(Totp.code(e))).build())
        }
        return b.build()
    }
}

class PasswordsAutofill : AutofillService() {
    @Suppress("DEPRECATION") // setAuthentication(…, RemoteViews): the form that works from Android 11 on
    override fun onFillRequest(request: FillRequest, cancellation: CancellationSignal, callback: FillCallback) {
        val r = runCatching {
            val s = request.fillContexts.last().structure
            val pkg = s.activityComponent.packageName
            if (pkg == packageName) return@runCatching null // Tetra's Browser fills its own pages
            val (fields, domain) = AutofillForm.read(s)
            AutofillForm.otp(fields)?.let { otp ->
                // a 2FA code: as the logins, given only after the fingerprint or the screen lock
                val codes = AuthSecrets.load(this).takeIf { it.isNotEmpty() } ?: return@runCatching null
                val n = AutofillForm.codesFor(codes, domain, pkg).size
                WriterLog.i("Autofill: a 2FA code's field, $n of ${codes.size} codes fit")
                val title = getString(R.string.pw_autofill_code_unlock, n); val where = AutofillForm.hostFor(domain, pkg)
                return@runCatching FillResponse.Builder().setAuthentication(arrayOf(otp.id as AutofillId), unlockIntent().intentSender, AutofillForm.row(this, title, where),
                    AutofillForm.inline(this, AutofillForm.spec(request.inlineSuggestionsRequest, 0), title, where)).build()
            }
            val form = AutofillForm.find(fields) ?: return@runCatching null
            val ids = (listOfNotNull(form.user) + form.passwords).map { it.id as AutofillId }
            val found = AutofillForm.matches(BrowserSecrets.vault(this).logins, domain, pkg)
            val b = FillResponse.Builder()
            if (found.isNotEmpty()) {
                // the logins are given only after the fingerprint or the screen lock: until then, one row says how many there are
                val pi = unlockIntent()
                val title = getString(R.string.pw_autofill_unlock, found.size); val where = AutofillForm.hostFor(domain, pkg)
                b.setAuthentication(ids.toTypedArray(), pi.intentSender, AutofillForm.row(this, title, where),
                    AutofillForm.inline(this, AutofillForm.spec(request.inlineSuggestionsRequest, 0), title, where))
            }
            if (form.passwords.isNotEmpty()) b.setSaveInfo(SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_PASSWORD or (if (form.user != null) SaveInfo.SAVE_DATA_TYPE_USERNAME else 0),
                form.passwords.map { it.id as AutofillId }.toTypedArray()).apply { form.user?.let { setOptionalIds(arrayOf(it.id as AutofillId)) } }
                .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE).build())
            if (found.isEmpty() && form.passwords.isEmpty()) null else b.build()
        }.onFailure { WriterLog.e("Autofill: a form not read", it) }.getOrNull()
        callback.onSuccess(r)
    }

    private fun unlockIntent() = PendingIntent.getActivity(this, (System.nanoTime() and 0xFFFFFF).toInt(), Intent(this, AutofillUnlock::class.java),
        PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE)

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        runCatching {
            val reads = request.fillContexts.map { AutofillForm.read(it.structure) }
            val pkg = request.fillContexts.last().structure.activityComponent.packageName
            val forms = reads.mapNotNull { (f, _) -> AutofillForm.find(f) }
            val pass = forms.reversed().firstNotNullOfOrNull { f -> f.passwords.firstOrNull { it.value.isNotEmpty() }?.value }
            // a sign-in asked one page at a time: the user from the page before the password's
            val user = forms.reversed().firstNotNullOfOrNull { f -> f.user?.value?.trim()?.takeIf { it.isNotEmpty() } }.orEmpty()
            val host = AutofillForm.hostFor(reads.firstNotNullOfOrNull { it.second }, pkg)
            if (pass != null) {
                val app = applicationContext
                Thread { runCatching { PasswordStore.keep(app, BrowserSecrets.vault(app).with(BrowserVault(listOf(SavedLogin(host, user, pass))))) }
                    .onFailure { WriterLog.e("Autofill: a login not kept", it) } }.start()
                WriterLog.i("Autofill: a login kept for ${if (host == pkg) "an app" else "a site"}")
                Toast.makeText(this, R.string.pw_autofill_saved, Toast.LENGTH_SHORT).show()
            }
        }.onFailure { WriterLog.e("Autofill: a save not read", it) }
        callback.onSuccess()
    }
}

/** The fingerprint or the screen lock, then this form's logins to choose from (Android shows them where the row was). */
class AutofillUnlock : Activity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.wrap(base))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val can = getSystemService(BiometricManager::class.java).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
        if (!can) { answer(); return } // no screen lock: nothing to ask, as the applet
        authenticateVault(this, ::answer, { Toast.makeText(this, it, Toast.LENGTH_LONG).show(); setResult(RESULT_CANCELED); finish() },
            getString(R.string.pw_unlock), getString(R.string.home_vault_unlock_detail), cancelled = { setResult(RESULT_CANCELED); finish() })
    }

    private fun answer() {
        val r = runCatching {
            val s = intent.getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE, AssistStructure::class.java)!!
            val (fields, domain) = AutofillForm.read(s)
            val pkg = s.activityComponent.packageName
            val ask = intent.getParcelableExtra(AutofillManager.EXTRA_INLINE_SUGGESTIONS_REQUEST, android.view.inputmethod.InlineSuggestionsRequest::class.java)
            val otp = AutofillForm.otp(fields)
            if (otp != null) AutofillForm.codeDatasets(this, otp, AutofillForm.codesFor(AuthSecrets.load(this), domain, pkg), ask)
            else AutofillForm.datasets(this, AutofillForm.find(fields)!!, AutofillForm.matches(BrowserSecrets.vault(this).logins, domain, pkg), ask)
        }.onFailure { WriterLog.e("Autofill: the logins not given", it) }.getOrNull()
        setResult(if (r != null) RESULT_OK else RESULT_CANCELED, Intent().apply { if (r != null) putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, r) })
        finish()
    }
}
