package com.kalotrapezis.drive

import java.io.File
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.provider.ContactsContract
import java.io.ByteArrayOutputStream
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Contacts and Android (Plan.md § Contacts › Outside Tetra): the phone's own contacts read in (the Google account's ones
 * already on the phone, READ_CONTACTS), and photos made square for a card.
 */
internal object ContactsSystem {
    /**
     * The phone's contacts as cards: Android writes each as a vCard itself (its own exporter), then Tetra adds what that
     * leaves out — the star, the labels (groups) and the full-size photo.
     */
    fun phoneCards(context: Context, progress: (Int, Int) -> Unit = { _, _ -> }): List<List<ICalProp>> {
        val cr = context.contentResolver
        val groups = HashMap<Long, String>()
        cr.query(ContactsContract.Groups.CONTENT_URI, arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE, ContactsContract.Groups.SYSTEM_ID), null, null, null)?.use { c ->
            // Android's own groups ("My Contacts", "Starred in Android", "Family" made by the system) are not labels a person made
            while (c.moveToNext()) if (c.isNull(2)) c.getString(1)?.takeIf { it.isNotBlank() && !it.equals(ContactInfo.STARRED_GROUP, true) }?.let { groups[c.getLong(0)] = it }
        }
        val labels = HashMap<Long, MutableList<String>>()
        cr.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID),
            "${ContactsContract.Data.MIMETYPE} = ?", arrayOf(ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE), null)?.use { c ->
            while (c.moveToNext()) groups[c.getLong(1)]?.let { labels.getOrPut(c.getLong(0)) { mutableListOf() }.add(it) }
        }
        val people = mutableListOf<Triple<Long, String, Boolean>>()
        cr.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.STARRED), null, null, null)?.use { c ->
            while (c.moveToNext()) c.getString(1)?.let { people.add(Triple(c.getLong(0), it, c.getInt(2) != 0)) }
        }
        return people.mapIndexedNotNull { n, (id, lookup, starred) ->
            progress(n, people.size)
            runCatching {
                val text = cr.openInputStream(android.net.Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, lookup))!!.use { String(it.readBytes()) }
                val lines = VCard.cards(text).firstOrNull() ?: return@runCatching null
                val info = VCard.info(lines)
                val full = ContactsContract.Contacts.openContactPhotoInputStream(cr, android.content.ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id), true)
                    ?.use { it.readBytes() }?.let { square(it) }
                val cats = (info.categories + labels[id].orEmpty() + listOfNotNull(ContactInfo.STARRED.takeIf { starred })).distinct()
                VCard.apply(lines, info, info.copy(categories = cats, photo = full ?: info.photo))
            }.onFailure { WriterLog.e("Contacts: the phone's contact $id", it) }.getOrNull()
        }
    }

    /**
     * A photo as a contact's: square, [size] px, a JPEG in base64. [box] (in the picture's pixels) is the part to keep — a
     * face from Photos, made larger by [room] so the head and some round it fit; without one, the middle square.
     */
    fun square(bytes: ByteArray, box: RectF? = null, room: Float = 1.8f, size: Int = 512): String? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { square(it, box, room, size) }

    fun square(bmp: Bitmap, box: RectF? = null, room: Float = 1.8f, size: Int = 512): String = squareAndBox(bmp, box, room, size).first

    /** [square], and the square taken as fractions of the picture (left, top, right, bottom): the X-TETRA-FACE box. */
    fun squareAndBox(bmp: Bitmap, box: RectF? = null, room: Float = 1.8f, size: Int = 512): Pair<String, List<Float>> {
        val w = bmp.width; val h = bmp.height
        val side: Int; val cx: Float; val cy: Float
        if (box != null) {
            side = (maxOf(box.width(), box.height()) * room).toInt().coerceIn(1, minOf(w, h)); cx = box.centerX(); cy = box.centerY()
        } else { side = minOf(w, h); cx = w / 2f; cy = h / 2f }
        val left = (cx - side / 2f).toInt().coerceIn(0, w - side)
        val top = (cy - side / 2f).toInt().coerceIn(0, h - side)
        val out = Bitmap.createScaledBitmap(Bitmap.createBitmap(bmp, left, top, side, side), minOf(size, side), minOf(size, side), true)
        val photo = ByteArrayOutputStream().use { b -> out.compress(Bitmap.CompressFormat.JPEG, 85, b); java.util.Base64.getEncoder().encodeToString(b.toByteArray()) }
        return photo to listOf(left.toFloat() / w, top.toFloat() / h, (left + side).toFloat() / w, (top + side).toFloat() / h)
    }

    /** The other apps' rows for a contact's numbers (Plan.md § Contacts › Outside Tetra), as the phone's own Contacts shows them. */
    fun appWays(context: Context, phones: List<String>): List<AppWay> {
        if (phones.isEmpty() || context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return emptyList()
        val rows = mutableListOf<AppRow>()
        val mimes = ContactsWays.WAYS.keys.map { "vnd.android.cursor.item/$it" }
        context.contentResolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data._ID, ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1, ContactsContract.Data.DATA2),
            "${ContactsContract.Data.MIMETYPE} IN (${mimes.joinToString { "?" }})", mimes.toTypedArray(), null)?.use { c ->
            while (c.moveToNext()) rows.add(AppRow(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: ""))
        }
        return ContactsWays.ways(rows, phones)
    }

    /** The calls to and from a contact's numbers, newest first (READ_CALL_LOG; none without it). */
    fun calls(context: Context, phones: List<String>, limit: Int = 200): List<CallEntry> {
        if (phones.isEmpty() || context.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) != android.content.pm.PackageManager.PERMISSION_GRANTED) return emptyList()
        val keys = phones.map { ContactsWays.key(it) }.filter { it.length >= 5 }.toSet()
        val out = mutableListOf<CallEntry>()
        context.contentResolver.query(android.provider.CallLog.Calls.CONTENT_URI, arrayOf(android.provider.CallLog.Calls.NUMBER, android.provider.CallLog.Calls.DATE,
            android.provider.CallLog.Calls.DURATION, android.provider.CallLog.Calls.TYPE), null, null, "${android.provider.CallLog.Calls.DATE} DESC")?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val n = c.getString(0) ?: continue
                if (ContactsWays.key(n) in keys) out.add(CallEntry(n, c.getLong(1), c.getLong(2), c.getInt(3)))
            }
        }
        return out
    }
}

/** One row another app put in the phone's contacts: its id, MIME type, number (or WhatsApp's jid), app name. */
internal data class AppRow(val id: Long, val mime: String, val data1: String, val app: String)
internal enum class Way { Message, Call, Video }
/** A way to reach a number through another app, opened as the phone's Contacts opens it (ACTION_VIEW on the row). */
internal data class AppWay(val app: String, val way: Way, val number: String, val id: Long, val mime: String)
internal data class CallEntry(val number: String, val at: Long, val seconds: Long, val type: Int)

/**
 * A contact's call recordings, newest first: those the computer made in Tetra/.System/Phone/Shared/Recordings, named
 * "2026-10-25 12.04 <the contact's name, or the number>.ogg" (desktop calls.js recorder), matched by that name or number.
 */
internal object ContactsRecordings {
    private val AUDIO = setOf("ogg", "opus", "m4a", "mp3", "wav")
    private val STAMP = Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}\\.\\d{2} ")
    fun of(files: List<File>, i: ContactInfo): List<File> = of(files, i.name, i.phones.map { it.value })
    fun of(files: List<File>, person: String, numbers: List<String>): List<File> {
        val name = ContactsFiles.plain(person.replace(Regex("[/\\\\:*?\"<>|]"), "-").take(60))
        val keys = numbers.map { ContactsWays.key(it) }.filter { it.length >= 5 }.toSet()
        return files.filter { f ->
            val title = f.nameWithoutExtension.replace(STAMP, "")
            f.extension.lowercase() in AUDIO && ((name.isNotEmpty() && ContactsFiles.plain(title) == name) || ContactsWays.key(title) in keys)
        }.sortedByDescending { it.name }
    }
}

internal object ContactsWays {
    /** The rows Tetra offers, by MIME type (as read on the user's phone, 23 Oct; Viber Out, a paid call, left out). */
    val WAYS = mapOf(
        "vnd.com.whatsapp.profile" to Way.Message, "vnd.com.whatsapp.voip.call" to Way.Call, "vnd.com.whatsapp.video.call" to Way.Video,
        "vnd.com.whatsapp.w4b.profile" to Way.Message, "vnd.com.whatsapp.w4b.voip.call" to Way.Call, "vnd.com.whatsapp.w4b.video.call" to Way.Video,
        "vnd.com.viber.voip.viber_number_message" to Way.Message, "vnd.com.viber.voip.viber_number_call" to Way.Call,
        "vnd.org.thoughtcrime.securesms.contact" to Way.Message, "vnd.org.thoughtcrime.securesms.call" to Way.Call,
        "vnd.org.telegram.messenger.android.profile" to Way.Message, "vnd.org.telegram.messenger.android.call" to Way.Call,
        "vnd.org.telegram.messenger.android.call.video" to Way.Video,
    )
    private val NAMES = mapOf("whatsapp.w4b" to "WhatsApp Business", "whatsapp" to "WhatsApp", "viber" to "Viber", "securesms" to "Signal", "telegram" to "Telegram")
    /** The app a row belongs to: opened in it, not in another that claims the MIME type too (Mindomo did, on the user's phone). */
    private val PACKAGES = mapOf("whatsapp.w4b" to "com.whatsapp.w4b", "whatsapp" to "com.whatsapp", "viber" to "com.viber.voip", "securesms" to "org.thoughtcrime.securesms", "telegram" to "org.telegram.messenger")
    fun packageOf(mime: String) = PACKAGES.entries.firstOrNull { mime.contains(it.key) }?.value

    /** A number's last nine digits: "+30 697 000 0002", "00306970000002" and "6970000002" alike. */
    fun key(n: String) = n.substringBefore('@').filter(Char::isDigit).takeLast(9)

    /** The rows for these numbers, once per app, way and number. */
    fun ways(rows: List<AppRow>, phones: List<String>): List<AppWay> {
        val byKey = phones.associateBy { key(it) }.filterKeys { it.length >= 5 }
        return rows.mapNotNull { r ->
            val mime = r.mime.removePrefix("vnd.android.cursor.item/")
            val way = WAYS[mime] ?: return@mapNotNull null
            val number = byKey[key(r.data1)] ?: return@mapNotNull null
            val app = NAMES.entries.firstOrNull { mime.contains(it.key) }?.value ?: r.app
            AppWay(app, way, number, r.id, r.mime)
        }.distinctBy { Triple(it.app, it.way, key(it.number)) }
    }
}

/**
 * A call asked for by the computer's Contacts (the user, 24 Oct: "phone calls"): a PC cannot place one, so this phone does
 * (SyncServer /call) — at once when Tetra may call (CALL_PHONE, Settings › Phone › Calls on the computer), else a
 * notification "Call Μαρία" that opens the dialer at a tap. The voice is the phone's, or a Bluetooth headset's.
 */
internal object PhoneCall {
    private const val CHANNEL = "calls_from_computer_nobadge"
    private const val NOTE = 4146

    fun allowed(c: Context) = c.checkSelfPermission(android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED
    /** Android's "Phone" permissions the computer's calls need, asked once together (Settings › Phone › Calls on the computer). */
    // not READ_CALL_LOG: Google Play allows it only to the phone app, which is granted it with the role (Plan.md § Phone)
    // BLUETOOTH_CONNECT: the computer told apart on the phone's Bluetooth by its name, so a call taken here stays here (PhoneCalls.keep)
    val PERMISSIONS = arrayOf(android.Manifest.permission.CALL_PHONE, android.Manifest.permission.READ_PHONE_STATE, android.Manifest.permission.ANSWER_PHONE_CALLS) +
        if (android.os.Build.VERSION.SDK_INT >= 31) arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()
    fun allAllowed(c: Context) = PERMISSIONS.all { c.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

    /** The number the computer last asked to call, and when: its call is the one the bubble follows. */
    @Volatile private var asked: Pair<String, Long>? = null

    /** The number the computer asked to call in the last minute, if any: that call is the one its bubble follows. */
    fun computerAsked(number: String?): Boolean = asked?.takeIf { System.currentTimeMillis() - it.second < 60_000 }?.let { a -> number == null || PhoneLog.same(a.first, number) } == true

    /**
     * Answer, Decline or End, from the computer's call bubble (ANSWER_PHONE_CALLS). Holding, muting and the keypad's tones
     * only while Tetra is the phone app (PhoneCalls, Plan.md § Phone step 5).
     */
    @Suppress("DEPRECATION", "MissingPermission")
    fun act(c: Context, what: String): org.json.JSONObject {
        if (!PhonePrefs.pcCalls(c)) return org.json.JSONObject().put("error", OFF)
        PhoneCalls.act(what)?.let { ok -> WriterLog.i("Phone: $what from the computer: $ok")
            return if (ok) org.json.JSONObject().put("ok", true) else org.json.JSONObject().put("error", "The phone did not $what the call.") }
        if (c.checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return org.json.JSONObject().put("error", "Allow it once on the phone: Settings › Phone › Calls on the computer.")
        val tm = c.getSystemService(android.telecom.TelecomManager::class.java)
        val ok = runCatching { when (what) { "answer" -> { tm.acceptRingingCall(); true }; "end", "decline" -> tm.endCall() || runCatching { !tm.isInCall }.getOrDefault(false); else -> false } }
            .onFailure { WriterLog.e("Contacts: $what from the computer", it) }.getOrDefault(false)
        WriterLog.i("Contacts: $what from the computer: $ok")
        return if (ok) org.json.JSONObject().put("ok", true) else org.json.JSONObject().put("error", "The phone did not $what the call.")
    }

    /**
     * The phone's calls told to the computer while Tetra listens (SyncListenService): ringing (with the number), active,
     * ended — for its call bubble. An outgoing call is reported only when the computer asked for it; the phone's own
     * calls stay its own. Needs READ_PHONE_STATE (and READ_CALL_LOG for the ringing number).
     */
    object Watcher : android.content.BroadcastReceiver() {
        @Volatile private var on = false
        private var last = android.telephony.TelephonyManager.EXTRA_STATE_IDLE
        private var number: String? = null
        private var outgoing = false
        fun start(c: Context) {
            if (on || c.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            on = runCatching { ContextCompat.registerReceiver(c, this, android.content.IntentFilter(android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED); true }.getOrDefault(false)
        }
        fun stop(c: Context) { if (on) runCatching { c.unregisterReceiver(this) }; on = false }

        @Suppress("DEPRECATION")
        override fun onReceive(c: Context, i: Intent) {
            // Tetra is the phone app: its call screen tells the computer (PhoneCalls.report). The role, not a bound service: Android may
            // say "ringing" a moment before it binds the call screen, and the computer would hear of the call twice
            if (PhonePrefs.isDialer(c) || !PhonePrefs.pcCalls(c)) return
            val state = i.getStringExtra(android.telephony.TelephonyManager.EXTRA_STATE) ?: return
            i.getStringExtra(android.telephony.TelephonyManager.EXTRA_INCOMING_NUMBER)?.takeIf { it.isNotBlank() }?.let { number = it }
            val report = when (state) {
                android.telephony.TelephonyManager.EXTRA_STATE_RINGING -> { outgoing = false; "ringing" }
                android.telephony.TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    if (last == android.telephony.TelephonyManager.EXTRA_STATE_IDLE) {
                        // dialled: the computer's call when it asked for one in the last minute, else the phone's own
                        val a = asked?.takeIf { System.currentTimeMillis() - it.second < 60_000 }
                        outgoing = true; number = a?.first
                        if (a == null) null else "active"
                    } else "active"
                }
                else -> "ended"
            }
            val wasRinging = last == android.telephony.TelephonyManager.EXTRA_STATE_RINGING
            if (state == last && number == null) return // the same state again, without the number it may bring next
            last = state
            if (report == null || (report == "ended" && outgoing && number == null)) return
            val body = org.json.JSONObject().put("state", report).put("direction", if (outgoing) "out" else "in").put("number", number ?: "")
                .put("missed", report == "ended" && wasRinging).put("at", System.currentTimeMillis())
            if (report == "ended") { number = null; asked = null }
            val app = c.applicationContext
            Thread { runCatching { SyncClient(app, SyncStore(app)).callState(body) }.onFailure { WriterLog.w("Contacts: the call's state did not reach the computer (${it.message})") } }.start()
        }
    }


    private const val OFF = "Calls on the computer are off on the phone: Settings › Phone › With the computer."

    fun fromComputer(c: Context, number: String, name: String): org.json.JSONObject {
        if (!PhonePrefs.pcCalls(c)) return org.json.JSONObject().put("error", OFF)
        val uri = android.net.Uri.fromParts("tel", number, null)
        asked = number to System.currentTimeMillis()
        Watcher.start(c.applicationContext)
        if (allowed(c)) runCatching {
            c.getSystemService(android.telecom.TelecomManager::class.java).placeCall(uri, android.os.Bundle())
            WriterLog.i("Contacts: a call from the computer placed")
            return org.json.JSONObject().put("how", "calling")
        }.onFailure { WriterLog.e("Contacts: a call from the computer could not be placed", it) }
        if (!androidx.core.app.NotificationManagerCompat.from(c).areNotificationsEnabled())
            return org.json.JSONObject().put("error", "Tetra's notifications are off on the phone, and it may not call by itself.")
        val nm = c.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel(CHANNEL, c.getString(R.string.phone_channel_pc_calls), android.app.NotificationManager.IMPORTANCE_HIGH).apply { setShowBadge(false) }) // again: its name follows the language
        val tap = android.app.PendingIntent.getActivity(c, 0, android.content.Intent(android.content.Intent.ACTION_DIAL, uri).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(NOTE, androidx.core.app.NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_call)
            .setContentTitle(c.getString(R.string.phone_pc_call_title, name.ifBlank { number })).setContentText(c.getString(R.string.phone_pc_call_text, number))
            // the way to skip the tap next time, said where it is asked for (the user, 24 Oct)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(c.getString(R.string.phone_pc_call_long, number)))
            .setContentIntent(tap).setAutoCancel(true).setCategory(androidx.core.app.NotificationCompat.CATEGORY_CALL)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH).build())
        return org.json.JSONObject().put("how", "notification")
    }
}

