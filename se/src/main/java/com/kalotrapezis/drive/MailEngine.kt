package com.kalotrapezis.drive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import jakarta.mail.FetchProfile
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.UIDFolder
import jakarta.mail.internet.InternetAddress
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.time.Instant
import java.time.ZoneId
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.eclipse.angus.mail.imap.IMAPFolder
import org.json.JSONArray
import org.json.JSONObject

/** A server as accounts.json writes it: host, port, user; [secure] TLS from the start, [starttls] TLS after hello, neither: plain (a test server only). */
internal data class MailServer(val host: String, val port: Int, val user: String, val secure: Boolean, val starttls: Boolean = false) {
    fun json(): JSONObject = JSONObject().put("host", host).put("port", port).put("user", user).put("secure", secure).put("starttls", starttls)
    companion object {
        fun of(o: JSONObject?) = o?.takeIf { it.has("host") }?.let { MailServer(it.getString("host"), it.optInt("port", 993), it.optString("user"), it.optBoolean("secure", true), it.optBoolean("starttls")) }
    }
}

/** Passwords of the accounts this device fetches. */
internal object MailSecret : KeystoreSecrets("tetra_mail_key", "mail_secrets")

/** Secrets each encrypted by a key that never leaves the keystore; never synced (AssistantKey's way): Mail's passwords, the Browser's vault. */
internal open class KeystoreSecrets(private val alias: String, private val file: String) {
    private fun prefs(c: Context) = c.getSharedPreferences(file, Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getKey(alias, null) as? SecretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun ids(c: Context): Set<String> = prefs(c).all.keys
    fun get(c: Context, id: String): String? = prefs(c).getString(id, null)?.let { saved -> runCatching {
        val b = Base64.decode(saved, Base64.NO_WRAP)
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12)) }.doFinal(b, 12, b.size - 12).decodeToString()
    }.onFailure { WriterLog.e("$file: a secret could not be read", it) }.getOrNull() }
    fun set(c: Context, id: String, secret: String?) {
        if (secret == null) { prefs(c).edit().remove(id).apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        prefs(c).edit().putString(id, Base64.encodeToString(cipher.iv + cipher.doFinal(secret.toByteArray()), Base64.NO_WRAP)).apply()
    }
}

/**
 * Finding an address's servers (MAIL_PLAN §9b), as the computer's mail.js does: known providers, Thunderbird's database,
 * the domain's own autoconfig, then the usual names. Microsoft's accounts need its sign-in (step 5): [microsoft].
 */
internal object MailDetect {
    data class Found(val imap: MailServer, val smtp: MailServer, val users: List<String>, val microsoft: Boolean = false)
    private val MICROSOFT = setOf("outlook.com", "hotmail.com", "hotmail.gr", "live.com", "msn.com")

    fun known(address: String): Found? {
        val domain = address.substringAfter('@').lowercase()
        return when {
            domain == "gmail.com" || domain == "googlemail.com" -> Found(MailServer("imap.gmail.com", 993, address, true), MailServer("smtp.gmail.com", 465, address, true), listOf(address))
            domain == "sch.gr" -> address.substringBefore('@').let { local -> Found(MailServer("mail.sch.gr", 993, local, true), MailServer("mail.sch.gr", 465, local, true), listOf(local, address)) }
            domain in MICROSOFT -> Found(MailServer("outlook.office365.com", 993, address, true), MailServer("smtp.office365.com", 587, address, false, true), listOf(address), microsoft = true)
            else -> null
        }
    }

    /** The IMAP and SMTP servers in a Thunderbird autoconfig file, or null. */
    fun parse(xml: String, address: String): Found? {
        fun server(type: String, tag: String): MailServer? {
            val m = Regex("<$tag\\s+type=\"$type\">([\\s\\S]*?)</$tag>").find(xml) ?: return null
            fun t(n: String) = Regex("<$n>([^<]*)</$n>").find(m.groupValues[1])?.groupValues?.get(1)?.trim()
            val host = t("hostname") ?: return null
            val port = t("port")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val user = (t("username") ?: "%EMAILADDRESS%").replace("%EMAILADDRESS%", address).replace("%EMAILLOCALPART%", address.substringBefore('@')).replace("%EMAILDOMAIN%", address.substringAfter('@'))
            return MailServer(host, port, user, t("socketType") == "SSL", t("socketType") == "STARTTLS")
        }
        val imap = server("imap", "incomingServer") ?: return null
        val microsoft = Regex("office365\\.com$|outlook\\.com$", RegexOption.IGNORE_CASE).containsMatchIn(imap.host)
        return Found(imap, server("smtp", "outgoingServer") ?: MailServer(imap.host, 465, imap.user, true), listOf(imap.user), microsoft)
    }

    private fun get(url: String): String? = runCatching {
        (java.net.URL(url).openConnection() as java.net.HttpURLConnection).run {
            connectTimeout = 6000; readTimeout = 6000
            try { if (responseCode == 200) inputStream.bufferedReader().readText() else null } finally { disconnect() }
        }
    }.getOrNull()

    private fun answers(host: String, port: Int) = runCatching {
        (javax.net.ssl.SSLSocketFactory.getDefault().createSocket() as javax.net.ssl.SSLSocket).use { s ->
            s.connect(java.net.InetSocketAddress(host, port), 4000); s.soTimeout = 4000; s.startHandshake(); true
        }
    }.getOrDefault(false)

    // shortcut: no MX lookup (Android has no DNS API for it); the PC's detection has it, and «Ρυθμίσεις χειροκίνητα» covers the rest
    fun find(address: String): Found? {
        known(address)?.let { return it }
        val domain = address.substringAfter('@').lowercase()
        get("https://autoconfig.thunderbird.net/v1.1/$domain")?.let { parse(it, address) }?.let { return it }
        get("https://autoconfig.$domain/mail/config-v1.1.xml?emailaddress=${java.net.URLEncoder.encode(address, "UTF-8")}")?.let { parse(it, address) }?.let { return it }
        for (host in listOf("imap.$domain", "mail.$domain")) if (answers(host, 993))
            return Found(MailServer(host, 993, address, true), MailServer(host.replace("imap.", "smtp."), 465, address, true), listOf(address, address.substringBefore('@')))
        return null
    }
}

/** What went wrong, as the screen says it: auth, appPassword, unreachable, other (the computer's mail.js reason()). */
internal fun mailReason(e: Throwable): String {
    (generateSequence(e) { it.cause }.firstOrNull { it is MailMsError } as MailMsError?)?.let { return it.reason }
    val text = generateSequence(e) { it.cause }.joinToString(" ") { it.message.orEmpty() }
    return when {
        Regex("application-specific password|app password", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "appPassword"
        e is jakarta.mail.AuthenticationFailedException || Regex("AUTHENTICATIONFAILED|invalid credentials|login failed|Invalid login", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "auth"
        generateSequence(e) { it.cause }.any { it is java.net.UnknownHostException || it is java.net.ConnectException || it is java.net.SocketTimeoutException || it is jakarta.mail.MessagingException && it.message?.contains("connect", true) == true } -> "unreachable"
        else -> "other"
    }
}

/**
 * The phone's own mail engine (MAIL_PLAN §1: every account the phone can sign in to it fetches itself; the PC's others are
 * shadows). The same files as the computer's mail.js, so the two never disagree on where a mail lives: <address>/<Role>/
 * <yyyy>/<yyyy-mm-dd hhmm> <from> - <subject>.eml with its .files/, and an id of account/role/uidvalidity/uid, the same
 * mail with the same id whoever fetched it. Its lines go to index.<device>.jsonl and its accounts to accounts.<device>.json,
 * this device's own files (nobody writes another's), which sync takes to the PC like any other.
 */
internal object MailEngine {
    const val JOB = 4153
    private const val CHANNEL = "mail_nobadge"
    private val lock = Any()

    fun device() = DataFiles.device()
    private fun indexFile(root: File) = File(root, "index.${device()}.jsonl")
    private fun accountsFile(root: File) = File(root, "accounts.${device()}.json")

    /** This device's accounts as written in accounts.<device>.json. */
    fun ownAccounts(root: File): List<JSONObject> = runCatching { JSONArray(accountsFile(root).readText()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } }.getOrDefault(emptyList())
    private fun saveAccounts(root: File, list: List<JSONObject>) {
        root.mkdirs()
        val part = File(root, ".${accountsFile(root).name}.part").apply { writeText(JSONArray(list).toString(2)) }
        check(part.renameTo(accountsFile(root)))
    }

    /** An account signed in with Microsoft (MailMicrosoft): its secret is a token, given to the server by XOAUTH2. */
    fun oauth(acc: JSONObject) = acc.optString("provider") == "microsoft"
    /** What an account signs in with: its password, or for Microsoft an access token renewed from the kept refresh token. */
    fun secret(c: Context, acc: JSONObject): String? = if (oauth(acc)) MailMicrosoft.access(c, acc.getString("id")) else MailSecret.get(c, acc.getString("id"))

    fun session(server: MailServer, smtp: Boolean = false, oauth: Boolean = false): Session = Session.getInstance(Properties().apply {
        val p = if (smtp) "mail.smtp" else "mail.imap"
        put("$p.connectiontimeout", "15000"); put("$p.timeout", "30000"); put("$p.writetimeout", "30000")
        if (server.secure) { put("$p.ssl.enable", "true"); put("$p.ssl.checkserveridentity", "true") }
        if (server.starttls) { put("$p.starttls.enable", "true"); put("$p.starttls.required", "true") }
        put("mail.imap.peek", "true") // reading a mail here never marks it read on the server; "read" is its own request
        if (smtp) put("mail.smtp.auth", "true")
        if (oauth) put("$p.auth.mechanisms", "XOAUTH2")
    })

    private fun connect(server: MailServer, pass: String, oauth: Boolean = false): Store = session(server, oauth = oauth).getStore("imap").apply { connect(server.host, server.port, server.user, pass) }

    /** Signs in with each user name the server may take; the one that worked, or throws (its reason by [mailReason]). */
    fun check(server: MailServer, users: List<String>, pass: String, oauth: Boolean = false): MailServer {
        var last: Throwable? = null
        for (u in users.distinct()) {
            val s = server.copy(user = u)
            try { connect(s, pass, oauth).close(); return s } catch (e: Exception) { last = e; if (mailReason(e) != "auth") break }
        }
        throw last ?: IllegalStateException("no user name")
    }

    /** An account checked and its password kept: written, synced at once, looked at every quarter hour. */
    fun add(c: Context, root: File, address: String, name: String, imap: MailServer, smtp: MailServer, pass: String, provider: String? = null, color: String? = null): JSONObject = synchronized(lock) {
        val colors = listOf("#e5534b", "#4f8ff7", "#e0a43a", "#3fb27f", "#b77ce6", "#a0785a", "#3bb8c4") // no pink: magenta is «Αποθήκη»'s
        val used = MailFiles.accounts(root).map { it.color }.toSet()
        val acc = JSONObject().put("id", address).put("address", address).put("name", name)
            .put("color", color ?: colors.firstOrNull { (0xFF000000 or it.removePrefix("#").toLong(16)) !in used } ?: colors[0])
            .put("server", imap.json()).put("smtp", smtp.json()).put("archive", true).put("notify", true)
            .put("addedOn", "phone").put("device", device()).put("added", System.currentTimeMillis()).apply { provider?.let { put("provider", it) } }
        MailSecret.set(c, address, pass)
        if (provider == null) MailPasswords.keep(c, imap.host, imap.user.ifEmpty { address }, pass) // a password, not a sign-in's token
        saveAccounts(root, ownAccounts(root).filter { it.optString("id") != address } + acc)
        schedule(c)
        acc
    }

    /** A new name for an account this device fetches (Work, Personal, Masters…), in its accounts.<device>.json; the PC shows it too. */
    fun rename(root: File, id: String, name: String) = synchronized(lock) {
        saveAccounts(root, ownAccounts(root).map { if (it.optString("id") == id) JSONObject(it.toString()).put("name", name) else it })
    }

    /**
     * One of the PC's accounts fetched here too («Χρήση και σε αυτή τη συσκευή»): the PC's servers, name and colour, the
     * password the PC gave ([SyncClient.mailCredentials]) checked at the server first. Same id, so its mail stays one list.
     */
    fun adopt(c: Context, root: File, pc: MailAccount, given: JSONObject): JSONObject {
        val o = pc.json ?: JSONObject()
        val pass = given.getString("password")
        val imap = MailServer.of(given.optJSONObject("server") ?: o.optJSONObject("server")) ?: throw IllegalStateException("No IMAP server for ${pc.address}")
        val checked = check(imap, listOfNotNull(given.optString("user").ifEmpty { null }, imap.user.ifEmpty { null }, pc.address), pass)
        val smtp = MailServer.of(given.optJSONObject("smtp") ?: o.optJSONObject("smtp"))?.let { it.copy(user = it.user.ifEmpty { checked.user }) }
            ?: throw IllegalStateException("No SMTP server for ${pc.address}")
        val provider = given.optString("provider").takeIf { it.isNotEmpty() && !given.isNull("provider") }
        return add(c, root, pc.address, given.optString("name").ifEmpty { pc.name }, checked, smtp, pass, provider, o.optString("color").ifEmpty { null })
    }

    /** Out of this device: its password, its account line and its index lines (the mail files stay; the PC keeps its own). */
    fun remove(c: Context, root: File, id: String) = synchronized(lock) {
        MailSecret.set(c, id, null)
        saveAccounts(root, ownAccounts(root).filter { it.optString("id") != id })
        val f = indexFile(root)
        if (f.isFile) rewrite(root, MailFiles.parse(f.readLines().asSequence(), keepGone = true).filter { it.account != id })
    }

    private fun rewrite(root: File, entries: List<MailEntry>) {
        val part = File(root, ".${indexFile(root).name}.part").apply { writeText(entries.joinToString("") { MailFiles.json(it).toString() + "\n" }) }
        check(part.renameTo(indexFile(root)))
    }
    private fun append(root: File, entries: List<MailEntry>) { if (entries.isNotEmpty()) indexFile(root).appendText(entries.joinToString("") { MailFiles.json(it).toString() + "\n" }) }
    private fun mine(root: File) = indexFile(root).takeIf { it.isFile }?.let { MailFiles.parse(it.readLines().asSequence(), keepGone = true) }.orEmpty()

    /** Each role's folder on the server: SPECIAL-USE first, then the usual names; INBOX is the Inbox. */
    fun roles(store: Store): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val all = store.defaultFolder.list("*").filterIsInstance<IMAPFolder>().filter { it.type and Folder.HOLDS_MESSAGES != 0 }
        val special = mapOf("\\Sent" to "Sent", "\\Drafts" to "Drafts", "\\Archive" to "Archive", "\\Junk" to "Spam", "\\Trash" to "Trash")
        all.firstOrNull { it.fullName.equals("INBOX", true) }?.let { out["Inbox"] = it.fullName }
        for (f in all) f.attributes.firstNotNullOfOrNull { special[it] }?.let { out.putIfAbsent(it, f.fullName) }
        val names = mapOf("Sent" to listOf("sent", "sent items", "sent mail", "απεσταλμένα"), "Drafts" to listOf("drafts", "πρόχειρα"),
            "Archive" to listOf("archive", "archives", "αρχειοθήκη"), "Spam" to listOf("junk", "spam", "ανεπιθύμητα"), "Trash" to listOf("trash", "deleted items", "bin", "κάδος"))
        for ((role, ns) in names) if (role !in out) all.firstOrNull { it.name.lowercase() in ns }?.let { out[role] = it.fullName }
        return out
    }

    /** `2026/2026-10-31 0912 Γραμματεία ΜΠΣ - Προθεσμία εργασίας`, by local time, without the extension (mail.js mailName). */
    fun mailName(date: Long, from: String, subject: String): String {
        val d = Instant.ofEpochMilli(date).atZone(ZoneId.systemDefault())
        return "%d/%d-%02d-%02d %02d%02d %s - %s".format(d.year, d.year, d.monthValue, d.dayOfMonth, d.hour, d.minute, Mime.clean(from, 60).ifEmpty { "-" }, Mime.clean(subject, 120).ifEmpty { "-" })
    }

    /** Writes one mail and its attachments as mail.js store() does; what the index learns from it. */
    fun store(root: File, e: MailEntry, raw: ByteArray): MailEntry {
        val folder = File(root, "${e.account}/${e.folder}")
        val base = mailName(e.date, e.from.ifEmpty { e.fromAddress }, e.subject)
        var name = base; var i = 2
        while (File(folder, "$name.eml").exists()) name = "$base (${i++})"
        val file = File(folder, "$name.eml")
        file.parentFile?.mkdirs()
        val part = File(file.parentFile, ".${file.name}.part").apply { writeBytes(raw) }
        check(part.renameTo(file))
        val m = runCatching { Mime.read(raw) }.getOrNull()
        val files = m?.let { Mime.attachments(raw) }.orEmpty()
        if (files.isNotEmpty()) {
            val dir = File(file.path.removeSuffix(".eml") + ".files").apply { mkdirs() }
            files.forEach { (n, bytes) -> File(dir, n).writeBytes(bytes) }
        }
        if (e.date > 0) file.setLastModified(e.date)
        return e.copy(file = file.relativeTo(root).invariantSeparatorsPath, saved = raw.size.toLong() == e.size || e.size <= 0,
            attachments = if (m != null) files.size else e.attachments,
            snippet = (m?.text ?: m?.html?.replace(Regex("<[^>]+>"), " ")).orEmpty().replace(Regex("\\s+"), " ").trim().take(160))
    }

    private fun address(a: jakarta.mail.Address?) = (a as? InternetAddress)?.let { utf8(it.personal ?: "") to (it.address ?: "") } ?: ("" to "")

    /** A header a server sent as raw UTF-8 (no =?utf-8?…?=), which reads as Latin-1: its letters back. */
    fun utf8(s: String): String {
        if (s.all { it.code < 0x80 } || s.any { it.code > 0xFF }) return s
        return runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(s.toByteArray(Charsets.ISO_8859_1))).toString() }.getOrDefault(s)
    }

    private val accountLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /**
     * One account: every role's list (new mail's headers, flags of the known, the gone marked), then the bodies this
     * device keeps (MailPrefs.bodies), newest first. New unread mail in the Inbox is announced after the first look.
     */
    fun sync(c: Context, root: File, acc: JSONObject) = synchronized(accountLocks.getOrPut(acc.getString("id")) { Any() }) { syncNow(c, root, acc) } // one look at a time per account

    private fun syncNow(c: Context, root: File, acc: JSONObject) {
        val id = acc.getString("id")
        val server = MailServer.of(acc.optJSONObject("server")) ?: return
        val pass = secret(c, acc) ?: return
        val store = connect(server, pass, oauth(acc))
        try {
            val boxes = roles(store)
            val fresh = mutableListOf<MailEntry>()
            synchronized(lock) {
                val known = mine(root).filter { it.account == id }
                val first = known.isEmpty()
                val out = mutableListOf<MailEntry>()
                for ((role, path) in boxes) {
                    val f = store.getFolder(path) as IMAPFolder
                    f.open(Folder.READ_ONLY)
                    try {
                        val validity = f.uidValidity.toString()
                        val messages = f.messages
                        f.fetch(messages, FetchProfile().apply { add(UIDFolder.FetchProfileItem.UID); add(FetchProfile.Item.FLAGS) })
                        val byUid = messages.associateBy { f.getUID(it) }
                        val mineHere = known.filter { it.folder == role }.associateBy { it.id }
                        for (e in mineHere.values) { // gone from the server (or the folder renumbered): hidden, its file kept
                            val p = e.id.split('/')
                            if (!e.gone && (p[p.size - 2] != validity || p.last().toLongOrNull() !in byUid)) out += e.copy(gone = true)
                        }
                        for ((uid, m) in byUid) {
                            val e = mineHere["$id/$role/$validity/$uid"] ?: continue
                            val seen = m.isSet(Flags.Flag.SEEN); val flagged = m.isSet(Flags.Flag.FLAGGED)
                            if (e.seen != seen || e.flagged != flagged) out += e.copy(seen = seen, flagged = flagged)
                        }
                        val newOnes = byUid.filterKeys { "$id/$role/$validity/$it" !in mineHere }.values.toTypedArray()
                        if (newOnes.isNotEmpty()) f.fetch(newOnes, FetchProfile().apply { add(FetchProfile.Item.ENVELOPE); add(FetchProfile.Item.SIZE) })
                        for (m in newOnes) {
                            val (fromName, fromAddress) = address(m.from?.firstOrNull())
                            val e = MailEntry("$id/$role/$validity/${f.getUID(m)}", id, role, (m.sentDate ?: m.receivedDate)?.time ?: 0, fromName.ifEmpty { fromAddress }, fromAddress,
                                m.getRecipients(Message.RecipientType.TO).orEmpty().map { address(it).second }, utf8(m.subject.orEmpty()), m.isSet(Flags.Flag.SEEN), m.isSet(Flags.Flag.FLAGGED),
                                m.size.toLong().coerceAtLeast(0), 0, "", false, null, false, (m as? jakarta.mail.internet.MimeMessage)?.messageID)
                            out += e
                            if (!first && role == "Inbox" && !e.seen && acc.optBoolean("notify", true)) fresh += e
                        }
                    } finally { f.close(false) }
                }
                append(root, out)
            }
            bodies(c, root, store, boxes, id)
            if (fresh.isNotEmpty()) announce(c, acc, fresh)
            MailPrefs.setState(c, id, null)
        } catch (e: Exception) {
            MailPrefs.setState(c, id, mailReason(e))
            throw e
        } finally { runCatching { store.close() } }
    }

    private fun bodies(c: Context, root: File, store: Store, boxes: Map<String, String>, id: String) {
        val all = synchronized(lock) { mine(root) }.filter { it.account == id && it.file == null && !it.gone }
        // one the PC (or another device) already saved, here or not: its file, not a second download (opened, it comes from the PC)
        val saved = MailFiles.index(root, device()).filter { it.file != null }.associateBy { it.id }
        synchronized(lock) { append(root, all.mapNotNull { e -> saved[e.id]?.let { s -> e.copy(file = s.file, saved = s.saved, attachments = s.attachments, snippet = s.snippet) } }) }
        // one held here as not saved that the computer has saved since: its file (MailFiles.healed); the computer's index read only then
        val unsaved = synchronized(lock) { mine(root) }.filter { it.account == id && it.file != null && !it.saved && !it.gone }
        if (unsaved.isNotEmpty()) File(root, "index.jsonl").takeIf { it.isFile }?.let { f ->
            val ids = unsaved.map { it.id }.toSet()
            val computer = f.useLines { l -> MailFiles.parse(l.filter { s -> ids.any { s.contains(it) } }) }.associateBy { it.id }
            val mended = MailFiles.healed(unsaved, computer) { File(root, it).isFile }
            if (mended.isNotEmpty()) { WriterLog.i("Mail: ${mended.size} taken from the computer's saved copy"); synchronized(lock) { append(root, mended) } }
        }
        // one that moved on the server (archived, deleted, a move): its file follows it, as the PC's does, not a second download
        fun same(e: MailEntry) = e.messageId ?: "${e.date}|${e.fromAddress}|${e.subject}|${e.size}" // a mail with no Message-ID: by what it is
        val left = synchronized(lock) { mine(root) }.filter { it.account == id && it.gone && it.file != null && File(root, it.file).isFile }.associateBy(::same)
        val moved = all.filter { it.id !in saved }.mapNotNull { e -> left[same(e)]?.let { old -> runCatching { relocate(root, old, e) }.getOrNull() } }
        synchronized(lock) { append(root, moved) }
        // an account only this device fetches: every body (until the PC has them this is the only copy; the limit frees the space
        // after); one the PC fetches too: what fits this device's limit, newest first (MAIL_PLAN §14)
        val pcToo = MailFiles.accounts(root).any { it.id == id && it.json?.optString("addedOn", "desktop") != "phone" }
        val waiting = all.filter { it.id !in saved && it.id !in moved.map { m -> m.id } }
        val pseudo = waiting.associateBy { "x/${mailName(it.date, "x", "x")} ${it.id.hashCode()}.eml" }
        // ... and with no PC paired to take them (Tetra on the phone alone), what fits too: the rest stays on the server, opened from there
        val keepsAll = !pcToo && SyncStore(c).pairing() != null
        val rule = MailStorage.read(root, device()) // the PC's card: the last year or month, Automatic (all), or N GB — one rule for all mail here
        val fits = if (keepsAll) pseudo.keys else MailSync.plan(pseudo.mapValues { it.value.size } + localMail(root), rule.budget(), rule.since())
        val todo = pseudo.filterKeys { it in fits }.values.sortedByDescending { it.date }
        for ((role, list) in todo.groupBy { it.folder }) {
            val f = store.getFolder(boxes[role] ?: continue) as IMAPFolder
            f.open(Folder.READ_ONLY)
            try {
                for (e in list) runCatching {
                    if (e.id.split('/').let { it[it.size - 2] } != f.uidValidity.toString()) return@runCatching
                    val m = f.getMessageByUID(e.id.substringAfterLast('/').toLong()) ?: return@runCatching
                    val raw = ByteArrayOutputStream().also { m.writeTo(it) }.toByteArray()
                    val stored = store(root, e, raw)
                    synchronized(lock) { append(root, listOf(stored)) }
                }.onFailure { WriterLog.w("Mail: ${e.id} not fetched (${it.message})") } // one mail the server will not give stays orange
            } finally { f.close(false) }
        }
    }

    /** The mail files on this device, by their sync path (Mail/…) and size: what the limit is counted against. */
    private fun localMail(root: File): Map<String, Long> = root.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }
        .associate { MailSync.PART + it.relativeTo(root).invariantSeparatorsPath to it.length() }.filterKeys { MailSync.dated(it) }

    /** [old]'s .eml and .files into [now]'s folder, under the name the PC's store() would give it there. */
    private fun relocate(root: File, old: MailEntry, now: MailEntry): MailEntry {
        val from = File(root, old.file!!)
        val folder = File(root, "${now.account}/${now.folder}")
        val base = mailName(now.date, now.from.ifEmpty { now.fromAddress }, now.subject)
        var name = base; var i = 2
        while (File(folder, "$name.eml").exists()) name = "$base (${i++})"
        val to = File(folder, "$name.eml").also { it.parentFile?.mkdirs() }
        check(from.renameTo(to))
        File(from.path.removeSuffix(".eml") + ".files").takeIf { it.isDirectory }?.renameTo(File(to.path.removeSuffix(".eml") + ".files"))
        return now.copy(file = to.relativeTo(root).invariantSeparatorsPath, saved = old.saved, attachments = old.attachments, snippet = old.snippet)
    }

    /** One mail's body now, for a mail this device has only the headers of (an older one, opened). */
    fun body(c: Context, root: File, e: MailEntry): MailEntry? {
        val acc = ownAccounts(root).firstOrNull { it.optString("id") == e.account } ?: return null
        val server = MailServer.of(acc.optJSONObject("server")) ?: return null
        val store = connect(server, secret(c, acc) ?: return null, oauth(acc))
        try {
            val f = store.getFolder(roles(store)[e.folder] ?: return null) as IMAPFolder
            f.open(Folder.READ_ONLY)
            try {
                val m = f.getMessageByUID(e.id.substringAfterLast('/').toLong()) ?: return null
                val stored = store(root, e, ByteArrayOutputStream().also { m.writeTo(it) }.toByteArray())
                synchronized(lock) { append(root, listOf(stored)) }
                return stored
            } finally { f.close(false) }
        } finally { runCatching { store.close() } }
    }

    /**
     * [op] on the server at once, for mail of an account this device fetches (the same ops as the PC's requests,
     * MAIL_PLAN §11); its lines in the index follow. A move is followed by a look at the account, so the mail appears
     * where it went.
     */
    fun act(c: Context, root: File, op: String, ids: Collection<String>, to: String? = null) {
        val entries = synchronized(lock) { mine(root) }.filter { it.id in ids }
        for ((accId, list) in entries.groupBy { it.account }) {
            val acc = ownAccounts(root).firstOrNull { it.optString("id") == accId } ?: continue
            val server = MailServer.of(acc.optJSONObject("server")) ?: continue
            val store = connect(server, secret(c, acc) ?: continue, oauth(acc))
            try {
                val boxes = roles(store)
                val target = when (op) { "archive" -> "Archive"; "delete" -> "Trash"; "spam" -> "Spam"; "notSpam" -> "Inbox"; "move" -> to; else -> null }
                val changed = mutableListOf<MailEntry>()
                for ((role, here) in list.groupBy { it.folder }) {
                    val f = store.getFolder(boxes[role] ?: continue) as IMAPFolder
                    f.open(Folder.READ_WRITE)
                    try {
                        val ms = here.mapNotNull { e -> f.getMessageByUID(e.id.substringAfterLast('/').toLong())?.let { e to it } }
                        when (op) {
                            "read", "unread" -> f.setFlags(ms.map { it.second }.toTypedArray(), Flags(Flags.Flag.SEEN), op == "read").also { changed += ms.map { it.first.copy(seen = op == "read") } }
                            "favorite", "unfavorite" -> f.setFlags(ms.map { it.second }.toTypedArray(), Flags(Flags.Flag.FLAGGED), op == "favorite").also { changed += ms.map { it.first.copy(flagged = op == "favorite") } }
                            else -> {
                                if (op == "delete" && role == "Trash") { f.setFlags(ms.map { it.second }.toTypedArray(), Flags(Flags.Flag.DELETED), true); f.expunge() } // from the Trash: for good on the server
                                else {
                                    // shortcut: an account with no Archive folder is not given one here (the PC's engine makes it); later, with archiveBy
                                    val dest = boxes[target] ?: continue
                                    f.moveMessages(ms.map { it.second }.toTypedArray(), store.getFolder(dest))
                                }
                                changed += ms.map { it.first.copy(gone = true) }
                            }
                        }
                    } finally { f.close(op == "delete" && role == "Trash") }
                }
                synchronized(lock) { append(root, changed) }
            } finally { runCatching { store.close() } }
            if (op !in setOf("read", "unread", "favorite", "unfavorite")) runCatching { sync(c, root, acc) }
        }
    }

    /** A draft that was sent or saved anew: out of Drafts on the server for good (not to the Trash), and out of the lists. */
    fun removeDraft(c: Context, root: File, e: MailEntry) {
        val acc = ownAccounts(root).firstOrNull { it.optString("id") == e.account } ?: return
        val server = MailServer.of(acc.optJSONObject("server")) ?: return
        val store = connect(server, secret(c, acc) ?: return, oauth(acc))
        try {
            val f = store.getFolder(roles(store)["Drafts"] ?: return) as IMAPFolder
            f.open(Folder.READ_WRITE)
            try { f.getMessageByUID(e.id.substringAfterLast('/').toLong())?.setFlag(Flags.Flag.DELETED, true) } finally { f.close(true) }
        } finally { runCatching { store.close() } }
        synchronized(lock) { append(root, listOf(e.copy(gone = true))) }
    }

    /** Every account this device fetches; the quarter-hourly job and the Sync button. */
    fun syncAll(c: Context, root: File = mailRoot()) {
        for (acc in ownAccounts(root)) runCatching { sync(c, root, acc) }.onFailure { WriterLog.w("Mail: ${acc.optString("id")}: ${it.message}") }
    }

    /**
     * While Mail is in front: with «Αμέσως» IDLE in each account's Inbox and a look whenever it changes, otherwise a single
     * look (Settings › Έλεγχος για νέα). The function returned stops it, closing the connections so none waits in IDLE.
     */
    fun watch(c: Context, root: File): () -> Unit {
        if (MailPrefs.every(c) != MailPrefs.NOW) { Thread { syncAll(c, root) }.start(); return {} }
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        val open = java.util.Collections.synchronizedSet(HashSet<Store>())
        for (acc in ownAccounts(root)) Thread {
            var delay = 5_000L
            while (!stopped.get()) runCatching {
                sync(c, root, acc)
                val server = MailServer.of(acc.optJSONObject("server")) ?: return@Thread
                val store = connect(server, secret(c, acc) ?: return@Thread, oauth(acc))
                open += store
                try {
                    if (stopped.get()) return@Thread
                    val inbox = store.getFolder("INBOX") as IMAPFolder
                    inbox.open(Folder.READ_ONLY)
                    while (!stopped.get()) { inbox.idle(true); if (!stopped.get()) sync(c, root, acc) } // idle(true): back after the first change
                } finally { open -= store; runCatching { store.close() } }
                delay = 5_000L
            }.onFailure { if (!stopped.get()) { WriterLog.w("Mail: watching ${acc.optString("id")}: ${it.message}"); Thread.sleep(delay); delay = minOf(delay * 2, 15 * 60_000L) } }
        }.apply { isDaemon = true; start() }
        return { stopped.set(true); Thread { synchronized(open) { open.toList() }.forEach { runCatching { it.close() } } }.start() }
    }

    private var askedPc = 0L
    /**
     * Mail opened with the PC's accounts here: the PC looks at their servers now (POST /mail/sync), and this device takes
     * what it found shortly after rather than at the next 30-second round. At most once a minute.
     */
    fun askPc(c: Context, root: File) {
        if (SyncStore(c).pairing() == null || MailFiles.accounts(root, MailSecret.ids(c)).none { it.shadow }) return
        val now = System.currentTimeMillis()
        if (now - askedPc < 60_000) return
        askedPc = now
        runCatching { SyncClient(c, SyncStore(c)).mailCheckNow() }.onFailure { WriterLog.w("Mail: asking the PC to look: ${it.message}"); return } // away: the next sync
        android.os.Handler(android.os.Looper.getMainLooper()).post { mailPcSeen.longValue = System.currentTimeMillis() } // the PC answered: Mail's ⇄ PC green
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            runCatching { androidx.core.content.ContextCompat.startForegroundService(c, Intent(c, SyncService::class.java).setAction(SyncService.ACTION_START)) }
                .onFailure { WriterLog.w("Mail: the sync after asking the PC: ${it.message}") }
        }, 10_000)
    }

    /**
     * The look with Tetra closed (JobScheduler, as Data's hourly one), while this device has an account of its own: every
     * 15 minutes with «Αμέσως», else every 5, 15 or 30; none when checked by hand. One job at a time, set again when it
     * ran ([again]) — a periodic job cannot run every 5 minutes.
     */
    fun schedule(c: Context, again: Boolean = false) {
        val jobs = c.getSystemService(JobScheduler::class.java) ?: return
        val every = MailPrefs.every(c).let { if (it == MailPrefs.NOW) 15 else it } * 60_000L
        if (every <= 0 || ownAccounts(mailRoot()).isEmpty()) return jobs.cancel(JOB)
        if (!again && jobs.getPendingJob(JOB)?.let { !it.isPeriodic && it.minLatencyMillis == every } == true) return
        jobs.schedule(JobInfo.Builder(JOB, ComponentName(c, SyncJob::class.java)).setMinimumLatency(every)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build())
    }

    private fun announce(c: Context, acc: JSONObject, mails: List<MailEntry>) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.app_mail), NotificationManager.IMPORTANCE_DEFAULT).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(c, JOB, appletIntent(c, Applet.Mail.name), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        mails.takeLast(5).forEach { e ->
            runCatching {
                nm.notify(e.id.hashCode(), android.app.Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_mail)
                    .setContentTitle(e.from.ifEmpty { e.fromAddress }).setContentText(e.subject).setSubText(acc.optString("name"))
                    .setWhen(e.date).setShowWhen(true).setContentIntent(open).setAutoCancel(true).setGroup(CHANNEL).build())
            }
        }
    }
}
