package com.kalotrapezis.drive

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager

/*
 * Messages (the user, 2 Nov: "SMS in Tetra: read and send them, with the AI too; a chat app's form"): the phone's own SMS,
 * read from Android's provider and sent with SmsManager. While another app is the default SMS app, Android writes what
 * Tetra sends into Sent itself and only that app may delete; Tetra as the default (SmsDeliver.kt) stores both itself.
 */

/** One SMS as the provider keeps it; [type] is Telephony's: 1 received, 2 sent, 4 outbox, 5 failed, 6 queued. */
internal data class SmsText(val id: Long, val thread: Long, val address: String, val body: String, val at: Long, val type: Int, val read: Boolean) {
    val mine get() = type != Telephony.Sms.MESSAGE_TYPE_INBOX
    val failed get() = type == Telephony.Sms.MESSAGE_TYPE_FAILED
    val sending get() = type == Telephony.Sms.MESSAGE_TYPE_OUTBOX || type == Telephony.Sms.MESSAGE_TYPE_QUEUED
}

/** A conversation: its last message and how many of theirs are still unread. */
internal data class SmsThread(val id: Long, val address: String, val last: SmsText, val unread: Int)

/** The pure part: conversations from the messages, a number's conversation, and the archived ones. */
internal object SmsRules {
    /** One a thread, newest first. */
    fun threads(texts: List<SmsText>): List<SmsThread> = texts.groupBy { it.thread }.map { (id, l) ->
        val last = l.maxBy { it.at }
        SmsThread(id, l.firstOrNull { it.address.isNotBlank() }?.address ?: last.address, last, l.count { !it.read && !it.mine })
    }.sortedByDescending { it.last.at }

    /** The conversation with a number, however it was written (+30 69…, 69…, spaces). */
    fun threadOf(threads: List<SmsThread>, address: String) = threads.firstOrNull { PhoneLog.same(it.address, address) }

    /** A number the user typed, not a name: digits, with + ( ) - and spaces. */
    fun isNumber(s: String) = s.isNotBlank() && s.trim().all { it.isDigit() || it in "+()- " } && s.count(Char::isDigit) >= 3

    /**
     * Archived is Tetra's own (the provider has no such mark): thread → when it was archived. A message newer than that
     * brings it back to the list, as Google's Messages does.
     */
    fun archived(map: Map<Long, Long>, t: SmsThread) = map[t.id]?.let { t.last.at <= it } == true
    fun parseArchive(s: String?): Map<Long, Long> = s.orEmpty().split(',').mapNotNull { p -> p.split(':').takeIf { it.size == 2 }?.let { (t, at) -> t.toLongOrNull()?.let { id -> at.toLongOrNull()?.let { id to it } } } }.toMap()
    fun formatArchive(map: Map<Long, Long>) = map.entries.joinToString(",") { "${it.key}:${it.value}" }
}

/** The phone's SMS: read, marked read, deleted (only the default app may) and sent. */
internal object SmsBox {
    val PERMISSIONS = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS)
    fun canRead(c: Context) = c.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    fun canSend(c: Context) = c.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    // shortcut: every SMS read at once (fine for thousands); page by date when a phone with a very long history is slow
    fun read(c: Context, thread: Long? = null): List<SmsText> = c.contentResolver.query(Telephony.Sms.CONTENT_URI,
        arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ),
        thread?.let { "${Telephony.Sms.THREAD_ID}=?" }, thread?.let { arrayOf("$it") }, "${Telephony.Sms.DATE} DESC")?.use { q ->
        buildList { while (q.moveToNext()) add(SmsText(q.getLong(0), q.getLong(1), q.getString(2).orEmpty(), q.getString(3).orEmpty(), q.getLong(4), q.getInt(5), q.getInt(6) != 0)) }
    }.orEmpty()

    /** Theirs marked read; a provider that keeps this to the default app changes nothing, and that is all right. */
    fun markRead(c: Context, thread: Long) = runCatching {
        c.contentResolver.update(Telephony.Sms.CONTENT_URI, ContentValues().apply { put(Telephony.Sms.READ, 1) }, "${Telephony.Sms.THREAD_ID}=? AND ${Telephony.Sms.READ}=0", arrayOf("$thread"))
    }.onFailure { WriterLog.w("Messages: not marked read", it) }

    /** How many messages went: 0 when Android keeps deleting to the default SMS app (it does, without an error). */
    fun delete(c: Context, thread: Long): Int = runCatching {
        c.contentResolver.delete(ContentUris.withAppendedId(Telephony.Sms.Conversations.CONTENT_URI, thread), null, null)
    }.onFailure { WriterLog.w("Messages: not deleted", it) }.getOrDefault(0)

    /** Long text in parts, joined again on their phone; Android writes it into Sent. */
    fun send(c: Context, to: String, text: String) {
        @Suppress("DEPRECATION") val sms = if (android.os.Build.VERSION.SDK_INT >= 31) c.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        val parts = sms.divideMessage(text)
        if (parts.size <= 1) sms.sendTextMessage(to, null, text, null, null) else sms.sendMultipartTextMessage(to, null, parts, null, null)
        if (SmsRole.isDefault(c)) runCatching { // the default app keeps its own Sent
            c.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, ContentValues().apply {
                put(Telephony.Sms.ADDRESS, to); put(Telephony.Sms.BODY, text); put(Telephony.Sms.DATE, System.currentTimeMillis()); put(Telephony.Sms.READ, 1)
            })
        }.onFailure { WriterLog.w("Messages: not kept in Sent", it) }
        WriterLog.i("Messages: sent ${text.length} characters in ${parts.size} part(s)")
    }

    /** The conversation in the phone's default messaging app (where deleting is allowed). */
    /**
     * The computer's Messages (SyncServer /sms/…): `list` the newest [LIMIT] (one answer, under sync's 1 MB), `send` to a
     * number, `read` a conversation. Nothing is kept on the computer: it asks again.
     */
    fun forComputer(c: Context, url: Uri): org.json.JSONObject = runCatching {
        if (!canRead(c)) return org.json.JSONObject().put("error", c.getString(R.string.sms_permission_needed))
        when (url.path) {
            "/sms/list" -> org.json.JSONObject().put("messages", org.json.JSONArray(read(c).take(LIMIT).map { t ->
                org.json.JSONObject().put("id", t.id).put("thread", t.thread).put("address", t.address).put("body", t.body).put("at", t.at)
                    .put("mine", t.mine).put("read", t.read).put("failed", t.failed).put("sending", t.sending) }))
            "/sms/send" -> {
                val to = url.getQueryParameter("to").orEmpty().filter { it.isDigit() || it == '+' }.take(32)
                val text = url.getQueryParameter("text").orEmpty().take(2000)
                if (to.count(Char::isDigit) < 3 || text.isBlank()) org.json.JSONObject().put("error", "No number or text.")
                else if (!canSend(c)) org.json.JSONObject().put("error", c.getString(R.string.sms_permission_needed))
                else { send(c, to, text); org.json.JSONObject().put("ok", true) }
            }
            "/sms/read" -> { url.getQueryParameter("thread")?.toLongOrNull()?.let { markRead(c, it) }; org.json.JSONObject().put("ok", true) }
            else -> org.json.JSONObject().put("error", "Unknown request.")
        }
    }.getOrElse { WriterLog.w("Messages: the computer's request failed", it); org.json.JSONObject().put("error", it.message ?: "Failed.") }
    private const val LIMIT = 1500

    fun openInDefault(c: Context, address: String) = runCatching {
        c.startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", address, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}

/** The archived conversations, on this phone only (SmsRules.archived). */
internal object SmsArchive {
    private fun p(c: Context) = c.getSharedPreferences("sms", Context.MODE_PRIVATE)
    fun get(c: Context) = SmsRules.parseArchive(p(c).getString("archived", null))
    fun set(c: Context, map: Map<Long, Long>) = p(c).edit().putString("archived", SmsRules.formatArchive(map)).apply()
}
