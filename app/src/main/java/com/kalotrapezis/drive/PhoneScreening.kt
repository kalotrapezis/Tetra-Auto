package com.kalotrapezis.drive

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.BlockedNumberContract
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.TelecomManager

/**
 * Screening before a call rings (Plan.md § Phone, step 4): Telecom asks the phone app's screening service about every
 * incoming call. Settings › Phone: numbers not in Contacts ring silently (still in Recents as missed), hidden numbers are
 * declined. Blocked numbers are Android's own list, which Telecom applies before this.
 */
class PhoneScreening : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        val ok = CallResponse.Builder().build()
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) return respondToCall(details, ok)
        val number = details.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() && details.handlePresentation == TelecomManager.PRESENTATION_ALLOWED }
        val spam = number?.let { PhoneSpam.matches(PhoneSpam.read(), it, PhoneCountry.of(this)) }
        val response = when {
            // spam and unwanted (Tetra/.System/Phone/Shared/spam.txt, the same list on every device): declined before it rings, no notice; Recents keeps it
            spam != null -> CallResponse.Builder().setDisallowCall(true).setRejectCall(true).setSkipNotification(true).build()
            number == null && PhonePrefs.declineHidden(this) -> CallResponse.Builder().setDisallowCall(true).setRejectCall(true).build()
            number != null && PhonePrefs.silenceUnknown(this) && PhoneCalls.contactFor(number) == null -> CallResponse.Builder().setSilenceCall(true).build()
            else -> ok
        }
        if (spam != null && number != null && PhonePrefs.blockedNotify(this)) PhoneBlockedNote.show(this, number)
        if (response !== ok) WriterLog.i("Phone: a call ${when { spam != null -> "on the spam list declined"; number == null -> "from a hidden number declined"; else -> "from a number not in Contacts silenced" }}")
        respondToCall(details, response)
    }
}

/** A blocked number called (Call blocking › Notify silently): a quiet notification, a tap opening the number's card. */
internal object PhoneBlockedNote {
    private const val CHANNEL = "phone_blocked_nobadge"
    fun show(c: Context, number: String) = runCatching {
        val nm = c.getSystemService(android.app.NotificationManager::class.java)
        // made again each time: an existing channel takes only the name, in the language now
        nm.createNotificationChannel(android.app.NotificationChannel(CHANNEL, c.getString(R.string.phone_channel_blocked), android.app.NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val open = android.app.PendingIntent.getActivity(c, number.hashCode(), android.content.Intent(c, PhoneActivity::class.java).putExtra(PhoneActivity.BLOCKED, number)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(CHANNEL, number.hashCode(), androidx.core.app.NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_block)
            .setContentTitle(c.getString(R.string.phone_blocked_from, number)).setWhen(System.currentTimeMillis()).setShowWhen(true)
            .setSilent(true).setContentIntent(open).setAutoCancel(true).build())
    }.onFailure { WriterLog.w("Phone: the blocked call's notice (${it.message})") }
}

/**
 * Spam and unwanted callers (the user, 24 Oct: "a list; when they call and are in it, the call is closed and never rings"):
 * Tetra/.System/Phone/Shared/spam.txt, plain text any AI can read — one number a line, a note after #, a * at the end for every number
 * that starts so (69800* for a whole range). The same file on the phone, the tablet and the computer (scope phone); the
 * phone app's screening declines a match before it rings. Marking on the phone also puts the number in Android's own
 * blocked list (its SMS are blocked too).
 */
internal object PhoneSpam {
    const val FILE = "spam.txt"
    private const val HEAD = "# Spam and unwanted (Tetra Phone): one number a line, a note after #; a * at the end takes every number that starts so.\n" +
        "# Calls from these are declined before they ring. The same list on the phone, the tablet and the computer.\n"
    data class Entry(val number: String, val note: String)

    fun parse(text: String): List<Entry> = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { l -> Entry(l.substringBefore('#').trim().replace(" ", ""), l.substringAfter('#', "").trim()) }.filter { e -> e.number.any(Char::isDigit) }

    /** The list's line that takes this number, if any. */
    fun matches(list: List<Entry>, number: String, region: String): Entry? = list.firstOrNull { e ->
        if (e.number.endsWith("*")) e.number.dropLast(1).takeIf { it.count(Char::isDigit) >= 2 }?.let { PhoneCountry.international(number, region).startsWith(PhoneCountry.international(it, region)) } == true
        else PhoneLog.same(e.number, number)
    }

    private fun file() = java.io.File(phoneRoot(), FILE)
    fun read(): List<Entry> = runCatching { parse(file().readText()) }.getOrDefault(emptyList())

    fun add(c: Context, number: String, note: String = "") = runCatching {
        if (matches(read(), number, PhoneCountry.of(c)) != null) return@runCatching
        val f = file().apply { parentFile?.mkdirs() }
        val was = if (f.exists()) f.readText() else HEAD
        f.writeText(was.trimEnd('\n') + "\n" + number.trim() + (if (note.isNotBlank()) "  # ${note.trim()}" else "") + "\n")
        ScopedSync.soon(c, "phone")
    }.onFailure { WriterLog.w("Phone: not marked as spam (${it.message})") }

    /** The lines for exactly this number go; notes, ranges and every other line stay as they were. */
    fun remove(c: Context, number: String) = runCatching {
        val f = file().takeIf { it.exists() } ?: return@runCatching
        f.writeText(f.readLines().filterNot { l -> !l.trim().startsWith("#") && parse(l).firstOrNull()?.let { !it.number.endsWith("*") && PhoneLog.same(it.number, number) } == true }
            .joinToString("\n") + "\n")
        ScopedSync.soon(c, "phone")
    }.onFailure { WriterLog.w("Phone: not taken off the spam list (${it.message})") }

    /** A range's line (69800*) taken off as written. */
    fun removeLine(c: Context, e: Entry) = runCatching {
        val f = file().takeIf { it.exists() } ?: return@runCatching
        f.writeText(f.readLines().filterNot { l -> parse(l).firstOrNull() == e }.joinToString("\n") + "\n")
        ScopedSync.soon(c, "phone")
    }

    /** CallInsider's page for a Greek number, opened in the browser as a person would (its terms forbid automated look-ups). */
    fun lookUp(number: String, region: String): String? = PhoneCountry.international(number, region).takeIf { it.startsWith("+30") && it.length == 13 }?.let { "https://www.callinsider.gr/gr/${it.drop(3)}" }
}

/** Android's blocked numbers (BlockedNumberContract): only the phone app, the SMS app and the carrier may read and change them. */
internal object PhoneBlocked {
    fun can(c: Context) = runCatching { BlockedNumberContract.canCurrentUserBlockNumbers(c) }.getOrDefault(false)
    fun all(c: Context): List<Pair<Long, String>> = runCatching {
        c.contentResolver.query(BlockedNumberContract.BlockedNumbers.CONTENT_URI, arrayOf(BlockedNumberContract.BlockedNumbers.COLUMN_ID, BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER),
            null, null, null)?.use { q -> buildList { while (q.moveToNext()) add(q.getLong(0) to q.getString(1).orEmpty()) } }.orEmpty()
    }.getOrDefault(emptyList())
    fun isBlocked(c: Context, number: String) = runCatching { BlockedNumberContract.isBlocked(c, number) }.getOrDefault(false)
    fun block(c: Context, number: String) = runCatching {
        c.contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, ContentValues().apply { put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number) })
    }.onFailure { WriterLog.w("Phone: not blocked (${it.message})") }
    fun unblock(c: Context, number: String) = runCatching { BlockedNumberContract.unblock(c, number) }.onFailure { WriterLog.w("Phone: not unblocked (${it.message})") }
}

/**
 * A person's ringtone: Android's own CUSTOM_RINGTONE on the phone's contact for that number (Tetra's copy in the phone's
 * contacts, or Google's), which Telecom plays whichever app is the phone app.
 */
internal object PhoneRingtone {
    private fun contactId(c: Context, number: String): Long? = runCatching {
        c.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)), arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)
            ?.use { q -> if (q.moveToFirst()) q.getLong(0) else null }
    }.getOrNull()
    fun known(c: Context, number: String) = contactId(c, number) != null
    fun get(c: Context, number: String): Uri? = contactId(c, number)?.let { id ->
        runCatching {
            c.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.CUSTOM_RINGTONE), "${ContactsContract.Contacts._ID} = ?", arrayOf("$id"), null)
                ?.use { q -> if (q.moveToFirst()) q.getString(0)?.let(Uri::parse) else null }
        }.getOrNull()
    }
    fun set(c: Context, number: String, tone: Uri?) = contactId(c, number)?.let { id ->
        runCatching {
            c.contentResolver.update(ContactsContract.Contacts.CONTENT_URI, ContentValues().apply { put(ContactsContract.Contacts.CUSTOM_RINGTONE, tone?.toString()) },
                "${ContactsContract.Contacts._ID} = ?", arrayOf("$id"))
        }.onFailure { WriterLog.w("Phone: the ringtone was not set (${it.message})") }
    }
}

/** Declining with a message: Telecom sends it as an SMS (Call.reject with a text), as Android's own phone app does. */
internal val QUICK_REPLIES = listOf(R.string.phone_reply_cant_talk, R.string.phone_reply_right_back, R.string.phone_reply_meeting, R.string.phone_reply_on_my_way)
