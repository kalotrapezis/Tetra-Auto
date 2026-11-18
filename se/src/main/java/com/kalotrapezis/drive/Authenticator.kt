package com.kalotrapezis.drive

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/*
 * Passwords' 2FA page (the user, 4 Nov: "one page 2FA"): time codes (RFC 6238) from secrets kept as the Browser's vault is,
 * under the Android Keystore. Added by a QR (otpauth://, or Google Authenticator's export, otpauth-migration://) or typed.
 */

/** One 2FA account: [secret] in Base32 as sites give it; SHA1/6/30 unless the site said otherwise; [pinned] ♥, at the top. */
internal data class OtpEntry(val issuer: String, val account: String, val secret: String, val algorithm: String = "SHA1",
                             val digits: Int = 6, val period: Int = 30, val pinned: Boolean = false) {
    val key get() = "${issuer.lowercase()}|${account.lowercase()}|${Totp.clean(secret)}"
    fun json(): JSONObject = JSONObject().put("issuer", issuer).put("account", account).put("secret", secret)
        .put("algorithm", algorithm).put("digits", digits).put("period", period).apply { if (pinned) put("pinned", true) }

    companion object {
        fun of(o: JSONObject) = OtpEntry(o.optString("issuer"), o.optString("account"), o.getString("secret"),
            o.optString("algorithm", "SHA1"), o.optInt("digits", 6), o.optInt("period", 30), o.optBoolean("pinned"))
    }
}

internal object Totp {
    val ALGORITHMS = listOf("SHA1", "SHA256", "SHA512")
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** As typed or printed: spaces, dashes and padding gone, upper case. */
    fun clean(s: String) = s.uppercase().filter { it != ' ' && it != '-' && it != '=' }

    /** Base32 (RFC 4648) to bytes; null when a letter is not of it or nothing is left. */
    fun base32(s: String): ByteArray? {
        val t = clean(s)
        if (t.isEmpty()) return null
        val out = java.io.ByteArrayOutputStream()
        var buf = 0; var bits = 0
        for (c in t) {
            val v = ALPHABET.indexOf(c); if (v < 0) return null
            buf = (buf shl 5) or v; bits += 5
            if (bits >= 8) { bits -= 8; out.write((buf shr bits) and 0xFF) }
        }
        return out.toByteArray()
    }

    fun base32(b: ByteArray): String {
        val sb = StringBuilder(); var buf = 0; var bits = 0
        for (x in b) {
            buf = (buf shl 8) or (x.toInt() and 0xFF); bits += 8
            while (bits >= 5) { bits -= 5; sb.append(ALPHABET[(buf shr bits) and 31]) }
        }
        if (bits > 0) sb.append(ALPHABET[(buf shl (5 - bits)) and 31])
        return sb.toString()
    }

    /** RFC 4226's HOTP: HMAC of the counter, dynamic truncation, [digits] long. */
    fun hotp(key: ByteArray, counter: Long, digits: Int = 6, algorithm: String = "SHA1"): String {
        val mac = Mac.getInstance("Hmac$algorithm").apply { init(SecretKeySpec(key, "RAW")) }
        val h = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val o = h.last().toInt() and 0x0F
        val bin = ((h[o].toInt() and 0x7F) shl 24) or ((h[o + 1].toInt() and 0xFF) shl 16) or ((h[o + 2].toInt() and 0xFF) shl 8) or (h[o + 3].toInt() and 0xFF)
        var mod = 1L; repeat(digits) { mod *= 10 }
        return (bin % mod).toString().padStart(digits, '0')
    }

    /** RFC 6238: the code at [millis]; "" when the secret is not Base32. */
    fun code(e: OtpEntry, millis: Long = System.currentTimeMillis()): String =
        base32(e.secret)?.let { hotp(it, millis / 1000 / e.period, e.digits, e.algorithm) }.orEmpty()

    /** Seconds left of the code shown at [millis]. */
    fun left(e: OtpEntry, millis: Long = System.currentTimeMillis()) = e.period - ((millis / 1000) % e.period).toInt()

    /** "123 456" / "1234 5678": read aloud in halves, as every authenticator shows them. */
    fun grouped(code: String) = if (code.length < 6) code else code.substring(0, code.length / 2) + " " + code.substring(code.length / 2)
}

/** otpauth:// (Google's Key Uri Format), the QR every site shows; and Google Authenticator's export, otpauth-migration://. */
internal object OtpUri {
    class Read(val entries: List<OtpEntry>, val skipped: Int = 0)

    fun parse(text: String): Read? {
        val t = text.trim()
        return when {
            t.startsWith("otpauth-migration://", true) -> migration(t)
            t.startsWith("otpauth://", true) -> totp(t)?.let { Read(listOf(it)) } ?: Read(emptyList(), 1)
            else -> null
        }
    }

    /**
     * A text file of otpauth:// links, one a line: GNOME Authenticator's backup (the user, 4 Nov: "Gnome athenticator it
     * outputs a txt"; its FreeOTP+ format), Aegis's and Ente's plain text export. Null when no line is a link.
     */
    fun lines(text: String): Read? {
        val reads = text.lineSequence().map { it.trim() }.filter { it.startsWith("otpauth", true) }.mapNotNull(::parse).toList()
        return if (reads.isEmpty()) null else Read(reads.flatMap { it.entries }, reads.sumOf { it.skipped })
    }

    private fun query(uri: String): Map<String, String> = uri.substringAfter('?', "").split('&').filter { '=' in it }
        .associate { it.substringBefore('=').lowercase() to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    /** otpauth://totp/Issuer:account?secret=…&issuer=…; null for HOTP (a counter Tetra does not keep) or a broken one. */
    fun totp(uri: String): OtpEntry? {
        val rest = uri.substringAfter("://")
        if (!rest.startsWith("totp/", true)) return null
        val q = query(uri)
        val secret = q["secret"]?.let(Totp::clean)?.takeIf { Totp.base32(it) != null } ?: return null
        val label = URLDecoder.decode(rest.substring(5).substringBefore('?').replace("+", "%2B"), "UTF-8")
        val issuer = q["issuer"]?.trim()?.takeIf { it.isNotEmpty() } ?: label.substringBefore(':', "").trim()
        val account = label.substringAfter(':').trim()
        val algorithm = q["algorithm"]?.uppercase()?.takeIf { it in Totp.ALGORITHMS } ?: "SHA1"
        val digits = q["digits"]?.toIntOrNull()?.takeIf { it in 6..8 } ?: 6
        val period = q["period"]?.toIntOrNull()?.takeIf { it in 1..300 } ?: 30
        return OtpEntry(issuer, account, secret, algorithm, digits, period)
    }

    fun uri(e: OtpEntry): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        val label = if (e.issuer.isEmpty()) enc(e.account) else enc(e.issuer) + ":" + enc(e.account)
        return "otpauth://totp/$label?secret=${e.secret}" + (if (e.issuer.isNotEmpty()) "&issuer=${enc(e.issuer)}" else "") +
            (if (e.algorithm != "SHA1") "&algorithm=${e.algorithm}" else "") + (if (e.digits != 6) "&digits=${e.digits}" else "") + (if (e.period != 30) "&period=${e.period}" else "")
    }

    /**
     * Google Authenticator › Transfer accounts › Export: a protobuf in Base64 (MigrationPayload: 1 = OtpParameters repeated,
     * each 1 secret bytes, 2 name, 3 issuer, 4 algorithm 1 SHA1 2 SHA256 3 SHA512 4 MD5, 5 digits 1 six 2 eight, 6 type 1 HOTP 2 TOTP).
     * A big export is several QRs; each is read on its own. HOTP and MD5 ones are counted as skipped.
     */
    fun migration(uri: String): Read? = runCatching {
        val data = query(uri)["data"] ?: return null
        val bytes = Base64.getDecoder().decode(data.replace('-', '+').replace('_', '/').trim().let { it + "=".repeat((4 - it.length % 4) % 4) })
        val out = mutableListOf<OtpEntry>(); var skipped = 0
        Proto(bytes).fields { n, v -> if (n == 1 && v is ByteArray) {
            var secret = ByteArray(0); var name = ""; var issuer = ""; var alg = 1; var dig = 1; var type = 2
            Proto(v).fields { f, x -> when (f) {
                1 -> secret = x as ByteArray; 2 -> name = (x as ByteArray).decodeToString(); 3 -> issuer = (x as ByteArray).decodeToString()
                4 -> alg = (x as Long).toInt(); 5 -> dig = (x as Long).toInt(); 6 -> type = (x as Long).toInt()
            } }
            val algorithm = when (alg) { 0, 1 -> "SHA1"; 2 -> "SHA256"; 3 -> "SHA512"; else -> null }
            if (type == 1 || algorithm == null || secret.isEmpty()) skipped++
            else out += OtpEntry(issuer, if (issuer.isNotEmpty() && name.startsWith("$issuer:")) name.substring(issuer.length + 1).trim() else name,
                Totp.base32(secret), algorithm, if (dig == 2) 8 else 6)
        } }
        Read(out, skipped)
    }.getOrNull()

    /** The little protobuf reading the export needs: varints (Long) and length-delimited (ByteArray); others refused. */
    private class Proto(val b: ByteArray) {
        var i = 0
        fun varint(): Long { var r = 0L; var s = 0; while (true) { val x = b[i++].toInt(); r = r or ((x and 0x7F).toLong() shl s); if (x and 0x80 == 0) return r; s += 7 } }
        fun fields(each: (Int, Any) -> Unit) {
            while (i < b.size) {
                val tag = varint(); val n = (tag shr 3).toInt()
                when ((tag and 7).toInt()) {
                    0 -> each(n, varint())
                    2 -> { val len = varint().toInt(); require(len >= 0 && i + len <= b.size); each(n, b.copyOfRange(i, i + len)); i += len }
                    1 -> i += 8
                    5 -> i += 4
                    else -> error("protobuf wire type")
                }
            }
        }
    }
}

/** The 2FA secrets in the Keystore's encryption, as the Browser's vault; between devices only when chosen ([AuthSync]). */
internal object AuthSecrets : KeystoreSecrets("tetra_2fa_key", "authenticator_secrets") {
    fun load(c: Context): List<OtpEntry> = runCatching { JSONArray(get(c, "entries")!!).let { a -> (0 until a.length()).map { OtpEntry.of(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
    fun save(c: Context, e: List<OtpEntry>) = set(c, "entries", if (e.isEmpty()) null else JSONArray(e.map { it.json() }).toString())

    /** [add] after [have], the same issuer, account and secret not twice; how many were new. */
    fun merge(have: List<OtpEntry>, add: List<OtpEntry>): Pair<List<OtpEntry>, Int> {
        val keys = have.map { it.key }.toMutableSet()
        val new = add.filter { keys.add(it.key) }
        return (have + new) to new.size
    }
}

/**
 * 2FA between the phone and the tablet, when chosen (the user, 4 Nov: opt-in, off) in Settings › Browser › Sync: the
 * passwords' passphrase and key, its own folder (Browser/TwoFactor/<device>.enc) and "2fa" bound into each file, the
 * passwords' merge (newer wins, deletions as tombstones). Needs password sync on and unlocked; otherwise nothing is read or written.
 */
internal object AuthSync {
    const val DIR = "TwoFactor"

    fun keyed(e: List<OtpEntry>) = e.associate { it.key to it.json().toString() }

    /** The set's living entries, [prev]'s order kept. */
    fun entries(set: SecretSet, prev: List<OtpEntry>) = set.live(prev.map { it.key }).values.mapNotNull { runCatching { OtpEntry.of(JSONObject(it)) }.getOrNull() }

    /** [here] merged with the other devices' when on and unlocked: the list to keep, else null. */
    fun run(c: Context, here: List<OtpEntry>, root: java.io.File = BrowserSync.root(), now: Long = System.currentTimeMillis()): List<OtpEntry>? {
        if (!BrowserSync.on(c, BrowserSync.TWO_FACTOR) || PasswordSync.state(c, root) != PasswordSync.State.ON) return null
        val (r, key) = PasswordSync.stored(c) ?: return null
        val base = AuthSecrets.get(c, "sync_base")?.let(SecretSet::parse)
        val m = BrowserPasswords.merged(java.io.File(root, DIR), "2fa", BrowserSync.device(c), keyed(here), base, key, r, now)
        if (m.all != base) AuthSecrets.set(c, "sync_base", m.all.json())
        val out = entries(m.all, here)
        if (out != here || base == null) WriterLog.i("2FA sync: ${out.size} codes, ${m.others} other devices${if (m.unreadable > 0) ", ${m.unreadable} of another passphrase left" else ""}")
        return out
    }

    fun off(c: Context) {
        c.getSharedPreferences("browser", Context.MODE_PRIVATE).edit().putBoolean(BrowserSync.TWO_FACTOR, false).apply()
        AuthSecrets.set(c, "sync_base", null)
        WriterLog.i("2FA sync: off; the codes stay on this device")
    }
}
