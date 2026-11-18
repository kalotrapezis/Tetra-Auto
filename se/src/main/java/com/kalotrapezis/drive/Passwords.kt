package com.kalotrapezis.drive

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.security.SecureRandom
import kotlin.math.ln

/*
 * The Passwords applet's workings (the user, 4 Nov: "create a password manager in tetra, one page with two tabs Passwords
 * and create a password, one page 2FA"). Its logins, addresses and cards are the Browser's vault (BrowserSecrets), so the
 * Browser fills what is kept here and its sync carries them; 2FA is Authenticator.kt.
 */

internal object PasswordGen {
    const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"; const val LOWER = "abcdefghijklmnopqrstuvwxyz"; const val DIGITS = "0123456789"
    const val SYMBOLS = "!@#$%^&*-_=+?.,:;~"
    private const val SIMILAR = "Il1O0o|"
    private val random = SecureRandom()

    data class Options(val length: Int = 20, val upper: Boolean = true, val lower: Boolean = true, val digits: Boolean = true,
                       val symbols: Boolean = true, val noSimilar: Boolean = false) {
        fun sets() = listOfNotNull(UPPER.takeIf { upper }, LOWER.takeIf { lower }, DIGITS.takeIf { digits }, SYMBOLS.takeIf { symbols })
            .map { s -> if (noSimilar) s.filter { it !in SIMILAR } else s }.ifEmpty { listOf(LOWER) }
        /** The guesses it would take, in bits: the generator's own count, not a guess at a typed one's. */
        val bits get() = length * ln(sets().sumOf { it.length }.toDouble()) / ln(2.0)
    }

    /** [o.length] characters, at least one of each chosen set (sites ask that), shuffled. */
    fun password(o: Options, rnd: java.util.Random = random): String {
        val sets = o.sets(); val all = sets.joinToString("")
        val out = (sets.map { it[rnd.nextInt(it.length)] } + List(maxOf(0, o.length - sets.size)) { all[rnd.nextInt(all.length)] }).toMutableList()
        for (i in out.indices.reversed()) { val j = rnd.nextInt(i + 1); val t = out[i]; out[i] = out[j]; out[j] = t }
        return out.take(o.length).joinToString("")
    }

    /** [n] words of [list] with [sep] between; its strength is n·log2(the list's size). */
    fun passphrase(list: List<String>, n: Int, sep: String = "-", rnd: java.util.Random = random) = List(n) { list[rnd.nextInt(list.size)] }.joinToString(sep)
    fun passphraseBits(list: List<String>, n: Int) = if (list.isEmpty()) 0.0 else n * ln(list.size.toDouble()) / ln(2.0)

    /** EFF's large word list (CC BY 3.0, eff.org), in assets as "11111\tabacus" lines; empty when it is not there. */
    @Volatile private var words: List<String>? = null
    fun words(c: Context): List<String> = words ?: runCatching {
        c.assets.open("eff_large_wordlist.txt").bufferedReader().readLines().mapNotNull { it.substringAfter('\t', "").trim().takeIf(String::isNotEmpty) }
    }.getOrDefault(emptyList()).also { words = it }
}

/** A secret on the clipboard: marked sensitive (keyboards' clipboard strips hide it) and taken off after 30 s if still there. */
internal object SecretClip {
    private val main = Handler(Looper.getMainLooper())
    fun copy(c: Context, label: String, text: String) {
        val cm = c.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(label, text)
        if (Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        cm.setPrimaryClip(clip)
        main.postDelayed({
            runCatching { if (cm.primaryClip?.getItemAt(0)?.text?.toString() == text) cm.clearPrimaryClip() }
        }, 30_000)
    }
}

/** The vault kept as the Browser keeps it: saved under the Keystore, then merged with the other devices' when sync is on. */
internal object PasswordStore {
    fun load(c: Context) = BrowserSecrets.vault(c)

    /** Saves [v]; the merged vault when sync changed it (to show), else null. Call off the main thread. */
    fun keep(c: Context, v: BrowserVault): BrowserVault? {
        BrowserSecrets.save(c, v)
        val r = runCatching { PasswordSync.run(c, v) }.onFailure { WriterLog.e("Passwords: sync not merged", it) }.getOrNull() ?: return null
        ScopedSync.soon(c, "browser", orWhole = true)
        if (r != v) BrowserSecrets.save(c, r)
        return r.takeIf { it != v }
    }

    fun keep2fa(c: Context, e: List<OtpEntry>): List<OtpEntry>? {
        AuthSecrets.save(c, e)
        val r = runCatching { AuthSync.run(c, e) }.onFailure { WriterLog.e("2FA: sync not merged", it) }.getOrNull() ?: return null
        ScopedSync.soon(c, "browser", orWhole = true)
        if (r != e) AuthSecrets.save(c, r)
        return r.takeIf { it != e }
    }
}

/**
 * Mail's account passwords kept in Passwords too (the user, 4 Nov: "where else do we need passwords? mail?"): a login of the
 * account's IMAP server, noted "Tetra Mail", so it syncs with the vault and the other device's Mail setup offers it.
 */
internal object MailPasswords {
    const val NOTE = "Tetra Mail"

    /**
     * The saved logins that could be [address]'s mail password: one kept for its server first, then the same user at that
     * server's site (a provider's own login). Google's account password is not Gmail's (an app password is): another site.
     */
    fun candidates(logins: List<SavedLogin>, address: String, imapHost: String): List<SavedLogin> {
        val a = address.trim().lowercase(); val local = a.substringBefore('@'); val host = imapHost.trim().lowercase()
        if (host.isEmpty()) return emptyList()
        val mine = logins.filter { it.user.equals(a, true) || it.user.equals(local, true) }
        val server = mine.filter { it.host == host }
        return server + mine.filter { it !in server && BrowserRules.site(it.host) == BrowserRules.site(host) }
    }

    /** [pass] kept for [imapHost] and [user]; never fails Mail (a vault not written is logged). Call off the main thread. */
    fun keep(c: Context, imapHost: String, user: String, pass: String) {
        val host = imapHost.trim().lowercase()
        if (host.isEmpty() || pass.isEmpty()) return
        runCatching {
            val v = BrowserSecrets.vault(c)
            if (v.logins.none { it.host == host && it.user == user && it.password == pass }) PasswordStore.keep(c, v.with(BrowserVault(listOf(SavedLogin(host, user, pass, NOTE)))))
        }.onFailure { WriterLog.e("Mail: the password not kept in Passwords", it) }
    }
}
