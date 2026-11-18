package com.kalotrapezis.drive

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What is being written: new, a reply (to all), a forward, of [of]; what MailScreen hands the compose page. */
internal data class MailDraft(val kind: String, val of: MailEntry? = null, val thread: List<MailEntry> = emptyList(),
    val to: String = "", val subject: String = "", val body: String = "", // a new one's start (AI Assist's mail_write, a mailto:, a share)
    val cc: String = "", val bcc: String = "", val files: List<File> = emptyList()) {
    /** As MailScreen's hand-over carries it ("mail-write:<json>"). */
    fun json(): org.json.JSONObject = org.json.JSONObject().put("to", to).put("cc", cc).put("bcc", bcc).put("subject", subject).put("body", body)
        .put("files", org.json.JSONArray(files.map { it.path }))
    companion object {
        fun of(o: org.json.JSONObject) = MailDraft("new", to = o.optString("to"), subject = o.optString("subject"), body = o.optString("body"),
            cc = o.optString("cc"), bcc = o.optString("bcc"), files = o.optJSONArray("files")?.let { a -> (0 until a.length()).map { File(a.getString(it)) } }.orEmpty())
    }
}

/**
 * A mailto: address (RFC 6068; the user, 31 Oct: Tetra as the default email app): the recipients before "?", then to, cc,
 * bcc, subject and body; percent-encoded UTF-8 ("+" stays a plus), several addresses split by commas, line breaks as \n.
 * Other header fields are left out.
 */
internal object MailTo {
    fun draft(uri: String): MailDraft {
        val rest = uri.substringAfter(':', "")
        val to = list(decode(rest.substringBefore('?'))).toMutableList()
        val cc = mutableListOf<String>(); val bcc = mutableListOf<String>()
        var subject = ""; var body = ""
        for (field in rest.substringAfter('?', "").split('&').filter { it.isNotEmpty() }) {
            val v = decode(field.substringAfter('=', ""))
            when (decode(field.substringBefore('=')).lowercase()) {
                "to" -> to += list(v); "cc" -> cc += list(v); "bcc" -> bcc += list(v)
                "subject" -> subject = v; "body" -> body = v.replace("\r\n", "\n")
            }
        }
        return MailDraft("new", to = to.joinToString(", "), subject = subject, body = body, cc = cc.joinToString(", "), bcc = bcc.joinToString(", "))
    }
    private fun list(s: String) = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    private fun decode(s: String): String {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val hex = if (s[i] == '%' && i + 2 < s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (hex != null) { out.write(hex); i += 3 } else { out.write(s[i].toString().toByteArray()); i++ }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}

/**
 * AI Assist's help with a reply (the user, 31 Oct: "AI assist on the writing when there is a thread going on"): Mistral, as
 * the assistant, drafts a proper mail — not a chat line — from the conversation and the user's own notes.
 */
internal object MailAssist {
    /** The chat for the draft: the conversation, oldest first ([from] to what each said), and what the user typed as notes. */
    /** [style]: how the user writes from [me] (MailAi, the computer's), when it knows. */
    fun messages(thread: List<Pair<String, String>>, me: String, notes: String, style: MailAi.Style? = null): JSONArray = JSONArray()
        .put(JSONObject().put("role", "system").put("content",
            "You write e-mail replies for $me. Write only the body of a proper e-mail reply: a greeting, clear paragraphs, a closing line — not a chat message. " +
            "No subject line and no signature (Tetra adds it). Write in the language of the conversation. If the user's notes are given, the reply says what they say, " +
            "in good form; do not invent facts, dates or promises that are not in the conversation or the notes." + MailAi.prompt(style)))
        .put(JSONObject().put("role", "user").put("content", "The conversation, oldest first:\n\n" +
            thread.joinToString("\n\n") { (from, text) -> "--- $from:\n$text" } +
            (if (notes.isNotBlank()) "\n\nMy notes for the reply:\n$notes" else "\n\nDraft my reply to the last message.")))

    fun draft(c: Context, thread: List<Pair<String, String>>, me: String, notes: String, style: MailAi.Style? = null): String {
        if (!AiGate.allows("mail")) throw DriveProblem("AI Assist may not use Mail.", R.string.ai_is_off)
        return AssistantNet.api(c, Bill()).complete(messages(thread, me, notes, style)).trim() // uncounted, as it always was
    }
}

/** An attachment chosen on this device ([uri]) or carried from the mail being forwarded ([file]). */
internal data class MailAttachment(val name: String, val uri: Uri? = null, val file: File? = null)

/**
 * A mail for an account the PC fetches (a shadow here): written into the outbox for the PC to send or keep as a draft
 * (step 1's format, MAIL_PLAN §12). The attachments chosen here go beside it in <name>.files/, the archive's by their path;
 * sync takes the .json last, which starts it.
 */
internal object MailOutbox {
    fun write(c: Context, root: File, m: MailOut, action: String, html: String, messageId: String, now: Long = System.currentTimeMillis()): File {
        val dir = File(root, "outbox").apply { mkdirs() }
        val device = MailEngine.device()
        var at = now
        while (File(dir, "$device-$at.json").exists()) at++
        val name = "$device-$at"
        val own = m.attachments.filter { it.file == null }
        val names = Mime.diskNames(own.map { it.name }).iterator()
        val files = m.attachments.map { a ->
            if (a.file != null) JSONObject().put("name", a.name).put("file", a.file.relativeTo(root).invariantSeparatorsPath)
            else names.next().let { n ->
                File(dir, "$name.files/$n").apply { parentFile?.mkdirs() }.outputStream().use { out -> c.contentResolver.openInputStream(a.uri!!)!!.use { it.copyTo(out) } }
                JSONObject().put("name", a.name).put("file", "outbox/$name.files/$n")
            }
        }
        fun people(s: String) = JSONArray(if (s.isBlank()) emptyList() else InternetAddress.parse(s.replace(';', ','), false).map { JSONObject().put("name", it.personal ?: "").put("address", it.address) })
        val draft = JSONObject().put("to", people(m.to)).put("cc", people(m.cc)).put("bcc", people(m.bcc)).put("subject", m.subject).put("html", html)
            .put("messageId", messageId).put("attachments", JSONArray(files))
        m.inReplyTo?.let { draft.put("inReplyTo", it) }
        m.references?.let { r -> draft.put("references", JSONArray(r.split(Regex("\\s+")).filter { it.isNotBlank() })) }
        val json = JSONObject().put("account", m.account).put("action", action).put("at", at).put("device", device).put("draft", draft)
        val part = File(dir, ".$name.json.part").apply { writeText(json.toString(2)) } // a dot file: sync never takes it half written
        return File(dir, "$name.json").also { check(part.renameTo(it)) }
    }

    /** What the PC could not send (its <name>.failed.json): (subject, why), and the files to delete once shown. */
    fun failed(root: File): List<Triple<String, String, File>> = File(root, "outbox").listFiles { f -> f.name.endsWith(".failed.json") }.orEmpty().mapNotNull { f ->
        runCatching { JSONObject(f.readText()).let { o -> Triple(o.optJSONObject("draft")?.optString("subject").orEmpty(), o.optString("detail").ifEmpty { o.optString("error") }, f) } }.getOrNull()
    }
}

/** A mail written here, ready to go: what [MailSend] sends or saves. */
internal data class MailOut(val account: String, val to: String, val cc: String, val bcc: String, val subject: String, val body: String,
    val attachments: List<MailAttachment>, val inReplyTo: String? = null, val references: String? = null, val html: String? = null)

/**
 * Sending and drafts for the accounts this device fetches (MAIL_PLAN §10 step 4): SMTP to send, then a copy in Sent
 * (Gmail files its own); a draft into Drafts. The PC's accounts go through its outbox (step 1's format).
 */
internal object MailSend {
    fun message(c: Context, root: File, m: MailOut): MimeMessage {
        val acc = MailEngine.ownAccounts(root).first { it.optString("id") == m.account }
        val server = MailServer.of(acc.optJSONObject("smtp"))!!
        return MimeMessage(MailEngine.session(server, smtp = true)).apply {
            setFrom(InternetAddress(acc.optString("address", m.account), acc.optString("sender").ifEmpty { null }, "UTF-8"))
            if (m.to.isNotBlank()) setRecipients(Message.RecipientType.TO, InternetAddress.parse(m.to.replace(';', ','), false))
            if (m.cc.isNotBlank()) setRecipients(Message.RecipientType.CC, InternetAddress.parse(m.cc.replace(';', ','), false))
            if (m.bcc.isNotBlank()) setRecipients(Message.RecipientType.BCC, InternetAddress.parse(m.bcc.replace(';', ','), false))
            setSubject(m.subject, "UTF-8")
            m.inReplyTo?.let { setHeader("In-Reply-To", it) }
            m.references?.let { setHeader("References", it) }
            setHeader("User-Agent", "Tetra")
            sentDate = java.util.Date()
            // the text and, beside it, the HTML (a designed signature keeps its look)
            val text = if (m.html == null) MimeBodyPart().apply { setText(m.body, "UTF-8") } else MimeBodyPart().apply { setContent(MimeMultipart("alternative").apply {
                addBodyPart(MimeBodyPart().apply { setText(m.body, "UTF-8") })
                addBodyPart(MimeBodyPart().apply { setText(m.html, "UTF-8", "html") })
            }) }
            if (m.attachments.isEmpty()) setContent(MimeMultipart().apply { addBodyPart(text) })
            else setContent(MimeMultipart().apply {
                addBodyPart(text)
                m.attachments.forEach { a ->
                    val bytes = a.file?.readBytes() ?: c.contentResolver.openInputStream(a.uri!!)!!.use { it.readBytes() }
                    addBodyPart(attachment(a.name, bytes, a.uri?.let { c.contentResolver.getType(it) }))
                }
            })
            saveChanges()
            setHeader("Message-ID", "<${java.util.UUID.randomUUID()}@${acc.optString("address", m.account).substringAfter('@')}>") // not "@localhost"
        }
    }

    /**
     * One attached file. The name goes in as it is: the library writes a Greek name as RFC 2231 (filename*=UTF-8''…), as
     * Thunderbird does. Encoding it first (=?UTF-8?B?…?=) had the library cut that in two, and the PDF arrived nameless (4 Nov).
     */
    fun attachment(name: String, bytes: ByteArray, type: String?) = MimeBodyPart().apply {
        val t = type ?: java.net.URLConnection.guessContentTypeFromName(name) ?: if (name.endsWith(".pdf", true)) "application/pdf" else "application/octet-stream"
        dataHandler = jakarta.activation.DataHandler(jakarta.mail.util.ByteArrayDataSource(bytes, t))
        fileName = name
        disposition = jakarta.mail.Part.ATTACHMENT
    }

    private fun appendTo(c: Context, root: File, account: String, role: String, msg: MimeMessage, flags: Flags) {
        val acc = MailEngine.ownAccounts(root).first { it.optString("id") == account }
        val server = MailServer.of(acc.optJSONObject("server")) ?: return
        val store = MailEngine.session(server, oauth = MailEngine.oauth(acc)).getStore("imap").apply { connect(server.host, server.port, server.user, MailEngine.secret(c, acc)) }
        try {
            val folder = store.getFolder(MailEngine.roles(store)[role] ?: return)
            folder.open(Folder.READ_WRITE)
            try { msg.setFlags(flags, true); folder.appendMessages(arrayOf(msg)) } finally { folder.close(false) }
        } finally { runCatching { store.close() } }
    }

    fun send(c: Context, root: File, m: MailOut) {
        val acc = MailEngine.ownAccounts(root).first { it.optString("id") == m.account }
        val smtp = MailServer.of(acc.optJSONObject("smtp"))!!
        val msg = message(c, root, m)
        MailEngine.session(smtp, smtp = true, oauth = MailEngine.oauth(acc)).getTransport("smtp").use { t ->
            t.connect(smtp.host, smtp.port, smtp.user, MailEngine.secret(c, acc))
            t.sendMessage(msg, msg.allRecipients)
        }
        // sent: the copy in Sent and the account's sync after the composer has closed (it waited for both; the user, 4 Nov:
        // "it took its sweet time")
        val app = c.applicationContext
        Thread {
            if (smtp.host != "smtp.gmail.com") runCatching { appendTo(app, root, m.account, "Sent", msg, Flags(Flags.Flag.SEEN)) } // Gmail keeps its own copy
                .onFailure { WriterLog.w("Mail: the copy in Sent failed (${it.message})") }
            runCatching { MailEngine.sync(app, root, acc) }
        }.start()
    }

    fun draft(c: Context, root: File, m: MailOut) {
        appendTo(c, root, m.account, "Drafts", message(c, root, m), Flags(Flags.Flag.DRAFT).apply { add(Flags.Flag.SEEN) })
        runCatching { MailEngine.sync(c, root, MailEngine.ownAccounts(root).first { it.optString("id") == m.account }) }
    }

    /** A reply's, a forward's subject: one "Re: " / "Fwd: ", not "Re: Re: ". */
    fun subject(kind: String, s: String) = when (kind) {
        "forward" -> if (Regex("^\\s*(fwd?|προωθ)\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(s)) s else "Fwd: $s"
        "reply", "replyAll" -> if (Regex("^\\s*(re|απ|σχετ)\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(s)) s else "Re: $s"
        else -> s
    }

    /** The text quoted under a reply, each line with "> ". */
    fun quote(text: String) = text.trimEnd().lines().joinToString("\n") { "> $it" }
}

/**
 * Writing (MAIL_PLAN §9): Από, Προς, Θέμα and the text, the island at the bottom with the two big actions — «Αποστολή»
 * and «Αποθήκευση πρόχειρου» — and the rest pulled up: attachment, Cc/Bcc, signature, send later, discard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MailCompose(draft: MailDraft, accounts: List<MailAccount>, root: File, close: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val of = draft.of
    val original = remember(of) { of?.let { MailFiles.eml(root, it) }?.let { f -> runCatching { Mime.read(f.readBytes()) }.getOrNull() } }
    // from: the account the mail came to, or the first this device sends from
    var from by remember { mutableStateOf(accounts.firstOrNull { it.id == of?.account }?.id ?: accounts.firstOrNull { it.own }?.id ?: accounts.firstOrNull()?.id.orEmpty()) }
    val me = accounts.firstOrNull { it.id == from }
    val isDraft = draft.kind == "draft" // a saved draft, opened from its greyed card to finish and send
    var to by remember { mutableStateOf(when (draft.kind) {
        "draft" -> original?.to.orEmpty()
        "reply" -> of?.fromAddress.orEmpty()
        "replyAll" -> (listOfNotNull(of?.fromAddress) + of?.to.orEmpty()).filter { !it.equals(me?.address, true) }.distinct().joinToString(", ")
        else -> draft.to
    }) }
    var cc by remember { mutableStateOf(if (draft.kind == "replyAll" || isDraft) original?.cc.orEmpty() else draft.cc) }
    var bcc by remember { mutableStateOf(draft.bcc) }
    var showCc by remember { mutableStateOf(cc.isNotEmpty() || bcc.isNotEmpty()) }
    var subject by remember { mutableStateOf(of?.let { MailSend.subject(draft.kind, it.subject) } ?: draft.subject) }
    var signed by remember { mutableStateOf(!isDraft) } // a draft has its signature in it already
    val signatureHtml = remember(me) { MailSignatures.html(root, me?.address.orEmpty()) }
    val signature = remember(signatureHtml) { MailSignatures.plain(signatureHtml) }
    val dash = if (MailSignatures.dashed(signatureHtml)) "" else "-- \n" // one that has its own "-- " line gets no second
    val messageId = remember { "<${java.util.UUID.randomUUID()}@" } // one per mail written: the PC replaces a saved draft by it
    // a draft keeps its own Message-ID, so the PC replaces it (or, sent, removes it) rather than keeping two
    fun messageIdFor() = original?.headers?.get("message-id")?.takeIf { isDraft } ?: (messageId + (me?.address ?: from).substringAfter('@') + ">")
    val at = of?.let { Instant.ofEpochMilli(it.date).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", java.util.Locale.getDefault())) }.orEmpty()
    // the line over the quoted mail: the signature goes above it
    val quoteHead = remember { when (draft.kind) {
        "reply", "replyAll" -> context.getString(R.string.mail_wrote, at, of?.from?.ifEmpty { of.fromAddress }.orEmpty())
        "forward" -> context.getString(R.string.mail_forwarded)
        else -> null
    } }
    var body by remember { mutableStateOf(run {
        val text = original?.text ?: original?.html?.replace(Regex("<br\\s*/?>|</p>", RegexOption.IGNORE_CASE), "\n")?.replace(Regex("<[^>]+>"), "")?.let { android.text.Html.fromHtml(it, 0).toString() } ?: of?.snippet.orEmpty()
        when (draft.kind) {
            "draft" -> text
            "reply", "replyAll" -> "\n\n" + quoteHead + "\n" + MailSend.quote(text)
            "forward" -> "\n\n" + quoteHead + "\n" + listOf(context.getString(R.string.mail_from) + ": " + of?.from + " <" + of?.fromAddress + ">",
                context.getString(R.string.mail_sort_date) + ": " + at, context.getString(R.string.mail_subject) + ": " + of?.subject).joinToString("\n") + "\n\n" + text
            else -> draft.body
        }
    }) }
    var problem by remember { mutableStateOf<String?>(null) }
    var drafting by remember { mutableStateOf(false) }
    /** «✦ Πρόταση από τον βοηθό»: the reply drafted from the conversation; what was typed above the quote is its notes, and is replaced. */
    fun assist() {
        val quoteAt = quoteHead?.let { body.indexOf("\n\n$it") }?.takeIf { it >= 0 } ?: body.length
        val notes = body.substring(0, quoteAt).trim()
        drafting = true; problem = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching {
                val mails = draft.thread.ifEmpty { listOfNotNull(of) }
                val said = mails.map { e -> e.from.ifEmpty { e.fromAddress } + " <" + e.fromAddress + ">" to (MailFiles.eml(root, e)?.let { f -> runCatching { Mime.read(f.readBytes()) }.getOrNull() }
                    ?.let { m -> MailThreads.own(m.text ?: MailSignatures.plain(m.html.orEmpty())) } ?: e.snippet) }
                (me?.address ?: from).let { addr -> MailAssist.draft(context.applicationContext, said, addr, notes, MailAi.style(root, addr)) }
            } }
            drafting = false
            r.onSuccess { text -> body = text + body.substring(quoteAt) }
                .onFailure { WriterLog.w("Mail: the assistant's draft (${it.message})"); problem = it.shownMessage(context) ?: it.message }
        }
    }
    var attachments by remember { mutableStateOf(if (draft.kind == "forward" && of != null) MailFiles.attachments(root, of).map { MailAttachment(it.name, file = it) }
        else draft.files.filter { it.isFile }.map { MailAttachment(it.name, file = it) }) } // a share's files, copied in when they came
    var panel by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // Send waits 5 s, the button a ring running down that takes it back (the user, 4 Nov: "a 5 sec undo, and then send")
    var waiting by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val left = remember { androidx.compose.animation.core.Animatable(1f) }
    val undoSeconds = remember { MailPrefs.undo(context) } // Settings › Mail › Undo send: 5, 3 or no wait
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        attachments = attachments + uris.map { u -> MailAttachment(context.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "attachment", uri = u) }
    }
    // asked before leaving only when something was changed from how the page opened (a reply's prefilled To is not a change)
    val opened = remember { listOf(to, cc, subject, body) }
    val touched = listOf(to, cc, subject, body) != opened || attachments.size != (if (draft.kind == "forward" && of != null) MailFiles.attachments(root, of).size else 0)
    BackHandler { if (panel) panel = false else if (touched) asking = true else close(null) }

    /** The text with the signature (Settings › Υπογραφές) above the quoted mail, as Thunderbird puts it. */
    fun signedBody(): String {
        if (!signed || signature.isBlank()) return body
        val i = quoteHead?.let { body.indexOf("\n\n$it") } ?: -1
        return if (i >= 0) body.substring(0, i).trimEnd() + "\n\n" + dash + signature + body.substring(i) else body.trimEnd() + "\n\n" + dash + signature
    }
    /** The same as HTML for the PC's outbox, the signature in its <div class="tetra-signature"> (MAIL_PLAN §12). */
    fun bodyHtml(): String {
        val esc = MailSignatures::escaped
        if (!signed || signature.isBlank()) return esc(body)
        val sig = "<div class=\"tetra-signature\">${if (dash.isEmpty()) "" else "-- <br>"}$signatureHtml</div>" // the stored HTML as it is
        val i = quoteHead?.let { body.indexOf("\n\n$it") } ?: -1
        return if (i >= 0) esc(body.substring(0, i).trimEnd()) + sig + esc(body.substring(i)) else esc(body.trimEnd()) + sig
    }
    fun out() = MailOut(from, to, cc, bcc, subject, signedBody(), attachments,
        if (isDraft) original?.headers?.get("in-reply-to") else of?.let { original?.headers?.get("message-id") },
        if (isDraft) original?.headers?.get("references") else of?.let { listOfNotNull(original?.headers?.get("references"), original?.headers?.get("message-id")).joinToString(" ").ifEmpty { null } },
        html = bodyHtml())
    BackHandler(waiting != null) { waiting?.cancel(); waiting = null; scope.launch { left.snapTo(1f) } } // Back in the 5 s: taken back too
    fun go(save: Boolean) {
        if (!save && to.isBlank()) { problem = context.getString(R.string.mail_no_recipient); return }
        busy = true; problem = null
        if (me?.own != true) { // the PC's account: its outbox, sent at once by the scoped sync "mail"
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching {
                    MailOutbox.write(context.applicationContext, root, out(), if (save) "draft" else "send", bodyHtml(), messageIdFor())
                    ScopedSync.soon(context.applicationContext, "mail", 0, orWhole = true)
                } }
                busy = false
                r.onSuccess { close(context.getString(if (save) R.string.mail_outbox_draft else R.string.mail_outbox_send)) }
                    .onFailure { WriterLog.e("Mail: the outbox", it); problem = context.getString(R.string.mail_not_sent, it.message.orEmpty()) }
            }
            return
        }
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching {
                if (save) MailSend.draft(context.applicationContext, root, out()) else MailSend.send(context.applicationContext, root, out())
                if (isDraft && of != null) MailEngine.removeDraft(context.applicationContext, root, of) // sent or saved anew: the old version goes
            } }
            busy = false
            r.onSuccess { close(context.getString(if (save) R.string.mail_draft_saved else R.string.mail_sent_ok)) }
                .onFailure { WriterLog.w("Mail: ${if (save) "draft" else "send"} failed (${it.message})"); problem = context.getString(R.string.mail_not_sent, mailWhy(context, it)) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(horizontal = 16.dp).padding(top = if (MailLook.top) 76.dp else 0.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = islandColor(), contentColor = islandContentColor(), shape = CircleShape) {
                    IconButton(onClick = { if (touched) asking = true else close(null) }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.mail_discard)) }
                }
                Text(stringResource(when (draft.kind) { "reply", "replyAll" -> R.string.mail_reply; "forward" -> R.string.mail_forward; else -> R.string.mail_new_message }),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            // Από: the accounts, chips; the PC's say they go through it
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.mail_from), color = MaterialTheme.colorScheme.onSurfaceVariant)
                accounts.sortedBy { it.shadow }.forEach { a -> // this device's own first: they send from here
                    Surface(color = if (a.id == from) MAIL_ACCENT else MaterialTheme.colorScheme.surfaceVariant, contentColor = if (a.id == from) Color.White else MaterialTheme.colorScheme.onSurface,
                        shape = CircleShape, modifier = Modifier.clickable { from = a.id }) {
                        Text(a.address + if (a.shadow) " ⇄ PC" else "", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            OutlinedTextField(to, { to = it; problem = null }, label = { Text(stringResource(R.string.mail_to_field)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
            if (showCc) {
                OutlinedTextField(cc, { cc = it }, label = { Text(stringResource(R.string.mail_cc_field)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(bcc, { bcc = it }, label = { Text(stringResource(R.string.mail_bcc_field)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(subject, { subject = it }, singleLine = true, label = { Text(stringResource(R.string.mail_subject)) }, trailingIcon = { DictateButton({ subject = subject.dictated(it) }) }, modifier = Modifier.fillMaxWidth())
            if (attachments.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                attachments.forEach { a ->
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_attach), null, Modifier.size(16.dp)); Text(" " + a.name, style = MaterialTheme.typography.labelLarge)
                            IconButton(onClick = { attachments = attachments - a }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.common_remove), Modifier.size(16.dp)) }
                        }
                    }
                }
            }
            // hidden while the AI is off or kept out of Mail
            if (of != null && draft.kind != "forward" && AiGate.allows("mail")) Surface(color = MAIL_ACCENT.copy(alpha = 0.35f), shape = CircleShape, modifier = Modifier.clickable(enabled = !drafting) { assist() }) {
                Text(stringResource(if (drafting) R.string.mail_assist_working else R.string.mail_assist), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
            }
            OutlinedTextField(body, { body = it }, label = { Text(stringResource(R.string.mail_body)) }, trailingIcon = { DictateButton({ body = body.dictated(it) }) }, modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp))
            if (signed && signature.isNotBlank()) Text(dash + signature, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Box(Modifier.heightIn(min = 120.dp))
        }
        // the writing island: two big buttons with words; the rest pulled up
        Box(if (MailLook.top) Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp) else Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = 12.dp)) {
            IslandBottomBar(expand = { panel = true }, visible = !panel) {
                Surface(color = MAIL_ACCENT, contentColor = Color.White, shape = CircleShape, modifier = Modifier.clickable(enabled = !busy) {
                    val w = waiting
                    when {
                        w != null -> { w.cancel(); waiting = null; scope.launch { left.snapTo(1f) } } // taken back: the mail stays as written
                        to.isBlank() -> problem = context.getString(R.string.mail_no_recipient)
                        undoSeconds <= 0 -> go(false)
                        else -> waiting = scope.launch {
                            left.snapTo(1f)
                            left.animateTo(0f, androidx.compose.animation.core.tween(undoSeconds * 1000, easing = androidx.compose.animation.core.LinearEasing))
                            waiting = null; go(false)
                        }
                    }
                }) {
                    Row(Modifier.padding(horizontal = 22.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        when {
                            waiting != null -> Box(contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(progress = { left.value }, Modifier.size(26.dp), color = Color.White, strokeWidth = 3.dp, trackColor = Color.White.copy(alpha = 0.25f))
                                Text("${kotlin.math.ceil(left.value * undoSeconds).toInt().coerceAtLeast(1)}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            }
                            busy -> CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.5.dp) // going: the server not answered yet
                            else -> Icon(painterResource(R.drawable.ic_arrow_forward), null)
                        }
                        Text(stringResource(when { waiting != null -> R.string.common_undo; busy -> R.string.mail_sending; else -> R.string.mail_send }), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
                Surface(color = Color.Transparent, contentColor = islandContentColor(), shape = CircleShape, modifier = Modifier.clickable(enabled = !busy && waiting == null) { go(true) }) {
                    Text(stringResource(R.string.mail_save_draft), Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
    if (panel) ModalBottomSheet(onDismissRequest = { panel = false }, containerColor = SheetColor, contentColor = islandContentColor()) {
        Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            ChoiceGroup {
                DrawerRow(R.drawable.ic_attach, stringResource(R.string.mail_attach), true) { panel = false; pick.launch(arrayOf("*/*")) }
                DrawerRow(R.drawable.ic_person, stringResource(R.string.mail_cc_bcc), true) { panel = false; showCc = !showCc }
                DrawerRow(R.drawable.ic_edit_note, stringResource(if (signed) R.string.mail_signature_off else R.string.mail_signature_on), signature.isNotBlank()) { if (signature.isNotBlank()) { panel = false; signed = !signed } }
                DrawerRow(R.drawable.ic_sort_time, stringResource(R.string.mail_send_later), false) {}
                DrawerRow(R.drawable.ic_delete, stringResource(R.string.mail_discard), true) { panel = false; if (touched) asking = true else close(null) }
            }
        }
    }
    if (asking) AlertDialog(onDismissRequest = { asking = false }, title = { Text(stringResource(R.string.mail_discard_ask)) },
        confirmButton = { TextButton(onClick = { asking = false; close(null) }) { Text(stringResource(R.string.mail_discard), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.common_cancel)) } })
}
