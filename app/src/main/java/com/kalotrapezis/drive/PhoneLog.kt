package com.kalotrapezis.drive

import android.content.Context
import android.provider.CallLog
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One line of Android's call log. */
internal data class LoggedCall(val id: Long, val number: String, val at: Long, val seconds: Long, val type: Int, val unseen: Boolean = false) {
    val missed get() = type == CallLog.Calls.MISSED_TYPE
}

/** Calls in a row from one number on one day, shown as one line ("Μαρία (3)"), newest first. */
internal data class CallGroup(val calls: List<LoggedCall>) {
    val newest get() = calls.first()
    val number get() = newest.number
}

/** Android's call log for the Phone's Recents (READ_CALL_LOG / WRITE_CALL_LOG, granted with the Phone app role). */
internal object PhoneLog {
    /** The latest calls read ahead when Tetra opens, so Recents opens full (the user, 25 Oct: "the dialer is slower than Contacts"). */
    @Volatile var recent: List<LoggedCall>? = null
    fun warm(c: Context) = Thread { runCatching { if (!PhoneRelay.noCalls(c)) recent = read(c) } }.start()

    fun read(c: Context, limit: Int = 600): List<LoggedCall> {
        if (c.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) != android.content.pm.PackageManager.PERMISSION_GRANTED) return emptyList()
        val out = mutableListOf<LoggedCall>()
        runCatching {
            c.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.NEW),
                null, null, "${CallLog.Calls.DATE} DESC")?.use { q ->
                while (q.moveToNext() && out.size < limit) out.add(LoggedCall(q.getLong(0), q.getString(1).orEmpty(), q.getLong(2), q.getLong(3), q.getInt(4), q.getInt(5) == 1))
            }
        }.onFailure { WriterLog.w("Phone: the call log was not read (${it.message})") }
        return out
    }

    fun delete(c: Context, ids: Collection<Long>) = runCatching {
        ids.chunked(200).forEach { part -> c.contentResolver.delete(CallLog.Calls.CONTENT_URI, "${CallLog.Calls._ID} IN (${part.joinToString()})", null) }
    }.onFailure { WriterLog.w("Phone: calls not deleted (${it.message})") }

    /** The missed calls seen: Android's missed-call notification goes, and they are no longer "new". */
    fun seen(c: Context) = runCatching {
        c.getSystemService(android.telecom.TelecomManager::class.java).cancelMissedCallsNotification()
        c.contentResolver.update(CallLog.Calls.CONTENT_URI, android.content.ContentValues().apply { put(CallLog.Calls.NEW, 0) },
            "${CallLog.Calls.NEW} = 1 AND ${CallLog.Calls.TYPE} = ${CallLog.Calls.MISSED_TYPE}", null)
    }

    /** Consecutive calls from the same number on the same day become one line. */
    fun group(calls: List<LoggedCall>, zone: ZoneId = ZoneId.systemDefault()): List<CallGroup> {
        val out = mutableListOf<MutableList<LoggedCall>>()
        for (c in calls) {
            val last = out.lastOrNull()?.first()
            if (last != null && same(last.number, c.number) && day(last.at, zone) == day(c.at, zone)) out.last().add(c) else out.add(mutableListOf(c))
        }
        return out.map { CallGroup(it) }
    }

    fun same(a: String, b: String) = ContactsWays.key(a).let { it.length >= 5 && it == ContactsWays.key(b) } || (a.isNotEmpty() && a == b)
    fun day(at: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
}

/** Search as you type on the keypad (Plan.md § Phone): the keys' Latin and Greek letters, accents ignored. */
internal object PhoneT9 {
    private val KEYS = mapOf(
        '2' to "ABCΑΒΓ", '3' to "DEFΔΕΖ", '4' to "GHIΗΘΙ", '5' to "JKLΚΛΜ", '6' to "MNOΝΞΟ",
        '7' to "PQRSΠΡΣ", '8' to "TUVΤΥΦ", '9' to "WXYZΧΨΩ",
    ).flatMap { (d, ls) -> ls.map { it to d } }.toMap()

    /** A name as keys: "Μαρία Π." → "62742 7". Letters off the keys and other marks drop out. */
    fun digits(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        .uppercase().map { ch -> if (ch.isDigit()) ch else KEYS[ch] ?: if (ch.isWhitespace() || ch == '-') ' ' else null }.filterNotNull().joinToString("")

    /** Does what was typed start one of the name's words (or the name run together), or sit inside one of its numbers? */
    fun matches(typed: String, name: String, numbers: List<String>): Boolean {
        if (typed.isEmpty()) return false
        val words = digits(name).split(' ').filter { it.isNotEmpty() }
        if (words.any { it.startsWith(typed) } || words.joinToString("").startsWith(typed)) return true
        val plain = typed.filter(Char::isDigit)
        return plain.length >= 3 && numbers.any { n -> n.filter(Char::isDigit).contains(plain) }
    }
}

/**
 * The phone's own country (the user, 27 Oct: "Greece is +30 or 0030. All the other countries have their own codes"): a
 * number written without + or 00 is read in it, with its calling code and trunk prefix (libphonenumber's metadata).
 */
internal object PhoneCountry {
    private val util get() = com.google.i18n.phonenumbers.PhoneNumberUtil.getInstance()
    @Volatile private var known: String? = null

    /** The SIM's country ("GR"), else the network's, else the system's; read once. */
    fun of(c: Context): String = known ?: c.getSystemService(android.telephony.TelephonyManager::class.java).let { tm ->
        listOf(runCatching { tm?.simCountryIso }.getOrNull(), runCatching { tm?.networkCountryIso }.getOrNull(), java.util.Locale.getDefault().country)
            .firstOrNull { !it.isNullOrEmpty() }.orEmpty().uppercase().also { if (it.isNotEmpty()) known = it }
    }

    /** The number, valid for libphonenumber, read in [region]; null for 112, a number half typed or not a number. */
    fun parse(number: String, region: String) = runCatching { util.parse(number, region) }.getOrNull()?.takeIf { util.isValidNumber(it) }

    /**
     * A number or the start of one (a spam range's 69800) as + its country code and the rest: "6980012345", "00306980012345"
     * and "+30 698 001 2345" alike in Greece; Germany's 0171… loses its trunk 0, Italy's 06… keeps its 0.
     */
    fun international(number: String, region: String): String {
        val t = number.trim(); val d = t.filter(Char::isDigit)
        parse(t, region)?.let { return "+${it.countryCode}${util.getNationalSignificantNumber(it)}" }
        // the start of a range is too short to parse: its trunk prefix comes off by hand
        val cc = util.getCountryCodeForRegion(region)
        return when {
            t.startsWith("+") -> "+$d"
            t.startsWith("00") -> "+" + d.drop(2)
            cc == 0 -> d
            else -> "+$cc" + d.removePrefix(util.getNddPrefixForRegion(region, true).orEmpty())
        }
    }
}

/**
 * What an unknown number is: the place of its area code (Θεσσαλονίκη, …) from the installed maps' phone tables, else
 * Mobile, Landline or Abroad (the words' strings), in the phone's own country ([PhoneCountry]).
 */
internal object PhoneArea {
    /** One map's <id>-phone.tsv (tools/maps-phone.py): its languages, en first, and prefix with the country code → a name in each. */
    class Table(val langs: List<String>, val names: Map<String, List<String>>)
    private var cache: Triple<Long, String, List<Table>>? = null

    /** Mobile, Landline or Abroad (another country than [region]'s); 0 for a number that may be either (the US's): its place or nothing; null for what is not a whole number. */
    @androidx.annotation.StringRes fun of(number: String, region: String): Int? {
        val util = com.google.i18n.phonenumbers.PhoneNumberUtil.getInstance()
        val p = PhoneCountry.parse(number, region) ?: return null
        if (util.getRegionCodeForNumber(p) != region) return R.string.phone_area_abroad
        return when (util.getNumberType(p)) {
            com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType.MOBILE -> R.string.contacts_label_mobile
            com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType.FIXED_LINE -> R.string.phone_area_landline
            com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType.FIXED_LINE_OR_MOBILE -> 0
            else -> null
        }
    }

    /** What [of] says, with a landline's or a foreign number's place when a table has it, in the app's language else English. */
    @androidx.compose.runtime.Composable fun label(number: String): String? {
        val region = PhoneCountry.of(androidx.compose.ui.platform.LocalContext.current)
        val kind = of(number, region) ?: return null
        val lang = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language
        val place = if (kind == R.string.contacts_label_mobile) null
            else place(number, region, lang, tables(MapsFiles.root(TetraFolder.root(android.os.Environment.getExternalStorageDirectory()))))
        return place ?: if (kind == 0) null else androidx.compose.ui.res.stringResource(kind)
    }

    /** The longest prefix any table has, the number read in [region]. */
    fun place(number: String, region: String, lang: String, tables: List<Table>): String? {
        val digits = PhoneCountry.international(number, region).takeIf { it.startsWith("+") }?.drop(1) ?: return null
        for (len in minOf(digits.length, 12) downTo 1) for (t in tables) {
            val names = t.names[digits.take(len)] ?: continue
            return names.getOrNull(t.langs.indexOf(lang))?.ifEmpty { null } ?: names.firstOrNull()?.ifEmpty { null } ?: continue
        }
        return null
    }

    /** The tables beside the maps in [root] (a table without its map is not read), read once and looked at again at most each minute. */
    @Synchronized fun tables(root: java.io.File): List<Table> {
        val now = System.currentTimeMillis()
        cache?.let { (at, _, t) -> if (now - at < 60_000) return t }
        val files = root.listFiles { f -> f.name.endsWith("-phone.tsv") && java.io.File(root, f.name.removeSuffix("-phone.tsv") + ".pmtiles").isFile }.orEmpty().sortedBy { it.name }
        val stamp = files.joinToString { "${it.name}${it.lastModified()}" }
        val t = cache?.takeIf { it.second == stamp }?.third ?: files.mapNotNull { f -> runCatching { read(f.readText()) }.getOrNull() }
        cache = Triple(now, stamp, t)
        return t
    }

    fun read(text: String): Table {
        val lines = text.lineSequence().filter { it.isNotBlank() }
        val langs = lines.first().removePrefix("#").split('\t').drop(1)
        return Table(langs, lines.drop(1).associate { line -> line.split('\t').let { it[0] to it.drop(1) } })
    }
}

/**
 * The call history for the computer and any AI (Plan.md § Phone step 5): Android's call log copied, a month a file, as
 * Tetra/.System/Phone/Shared/calls-2026-10.jsonl — one line per call, oldest first:
 * {"at":"2026-10-24T15:36:02+03:00","number":"+3069…","dir":"in|out|missed|declined|blocked|voicemail","secs":124,"contact":"<UID>","name":"Μαρία","note":"…"}.
 * Written by the phone only, while Tetra is the phone app (Google Play allows the call log to the phone app); the call log
 * stays Android's. A month whose calls were all deleted becomes an empty file (nothing is deleted, so nothing goes to Trash).
 */
internal object PhoneHistory {
    const val FOLDER = "Phone"
    private val MONTH = Regex("""calls-(\d{4}-\d{2})\.jsonl""")

    /** One call as a line, its fields always in this order (written by hand: a JSONObject's order is not promised). */
    fun line(call: LoggedCall, contact: ContactInfo?, note: String?, zone: ZoneId): String {
        val q = org.json.JSONObject::quote
        val dir = when (call.type) {
            CallLog.Calls.OUTGOING_TYPE -> "out"; CallLog.Calls.MISSED_TYPE -> "missed"; CallLog.Calls.REJECTED_TYPE -> "declined"
            CallLog.Calls.BLOCKED_TYPE -> "blocked"; CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"; else -> "in"
        }
        val fields = mutableListOf("at" to q(Instant.ofEpochMilli(call.at).atZone(zone).toOffsetDateTime().withNano(0).toString()), "number" to q(call.number),
            "dir" to q(dir), "secs" to "${call.seconds}")
        contact?.let { c -> c.uid.ifBlank { null }?.let { fields += "contact" to q(it) }; fields += "name" to q(c.name) }
        note?.let { fields += "note" to q(it) }
        return fields.joinToString(",", "{", "}") { (k, v) -> "\"$k\":$v" }
    }

    /** The month files' text from the calls: "2026-10" → its lines. */
    fun months(calls: List<LoggedCall>, who: (String) -> ContactInfo?, note: (LoggedCall) -> String?, zone: ZoneId): Map<String, String> =
        calls.groupBy { Instant.ofEpochMilli(it.at).atZone(zone).let { t -> "%04d-%02d".format(t.year, t.monthValue) } }
            .mapValues { (_, l) -> l.sortedBy { it.at }.joinToString("") { line(it, who(it.number), note(it), zone) + "\n" } }

    fun write(c: Context) {
        if (!PhonePrefs.isDialer(c)) return
        runCatching {
            val zone = ZoneId.systemDefault()
            val calls = PhoneLog.read(c, limit = 50_000)
            val contacts = ContactsPreload.all?.map { it.info } ?: runCatching { ContactsFiles.all(contactsRoot()).map { it.info } }.getOrDefault(emptyList())
            val byKey = contacts.flatMap { p -> p.phones.map { ContactsWays.key(it.value) to p } }.filter { it.first.length >= 5 }.toMap()
            val notes = PhoneNotes.all()
            val want = months(calls, { byKey[ContactsWays.key(it)] }, { PhoneNotes.of(c, it.number, it.at, notes) }, zone)
            val dir = phoneRoot().apply { mkdirs() }
            val old = dir.listFiles().orEmpty().mapNotNull { f -> MONTH.matchEntire(f.name)?.groupValues?.get(1) }
            var changed = false
            (want.keys + old).forEach { m ->
                val f = java.io.File(dir, "calls-$m.jsonl")
                val text = want[m].orEmpty()
                if (!f.exists() || f.readText() != text) { f.writeText(text); changed = true }
            }
            if (changed) { WriterLog.i("Phone: the call history for the computer written (${calls.size} calls)"); ScopedSync.soon(c, "phone") }
        }.onFailure { WriterLog.w("Phone: the call history was not written (${it.message})") }
    }
}

/**
 * The keypad's codes, as every phone app answers them: *#*#4636#*#* and the like go to whatever app listens for them
 * (Android's sendDialerSpecialCode, the phone app's alone), *#06# shows the phone's IMEI or, where Android keeps it from
 * an ordinary app, opens About phone. Others (*100# for the balance) are calls, placed as any number.
 */
internal object PhoneCodes {
    private val SECRET = Regex("""\*#\*#(\d+)#\*#\*""")
    fun secret(typed: String) = SECRET.matchEntire(typed)?.groupValues?.get(1)

    /** A code just completed on the keypad: done here, and true (the number then clears). */
    fun typed(c: Context, typed: String): Boolean? {
        secret(typed)?.let { code ->
            runCatching { c.getSystemService(android.telephony.TelephonyManager::class.java).sendDialerSpecialCode(code) }
                .onFailure { WriterLog.w("Phone: code $code (${it.message})") }
            return true
        }
        if (typed == "*#06#") {
            @Suppress("MissingPermission")
            val ids = runCatching { val tm = c.getSystemService(android.telephony.TelephonyManager::class.java); (0 until tm.activeModemCount).mapNotNull { tm.getImei(it) } }.getOrNull()
            if (ids.isNullOrEmpty()) runCatching { c.startActivity(android.content.Intent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            else android.app.AlertDialog.Builder(c).setTitle("IMEI").setMessage(ids.joinToString("\n")).setPositiveButton(c.getString(R.string.common_ok), null).show()
            return true
        }
        return null
    }
}
