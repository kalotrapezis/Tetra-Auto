package com.kalotrapezis.drive

import android.content.Context
import android.util.Base64
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONObject

/** Why Microsoft said no, as the screen says it: approval (the organisation must allow Tetra first), auth, noClient, other. */
internal class MailMsError(message: String, val reason: String) : Exception(message)

/**
 * «Σύνδεση με Microsoft» on the phone (Hotmail/Outlook.com, Microsoft 365), the desktop's msauth.js: OAuth 2.0 with the
 * authorization code and PKCE. Microsoft's page opens in the browser, the user signs in there, and the browser comes back to
 * a one-time server on this phone (http://localhost:<port>/) that takes the code. Tetra never sees the password. The
 * refresh token is kept as the account's secret (MailSecret, this device only); an access token lives an hour, in memory.
 * The app id ("client id") is the one registered once for the PC (MAIL_PLAN §13): Microsoft lets a native app answer on
 * any localhost port, so the same id serves both.
 */
internal object MailMicrosoft {
    private const val AUTHORITY = "https://login.microsoftonline.com/common/oauth2/v2.0"
    private val SCOPES = listOf("offline_access", "https://outlook.office.com/IMAP.AccessAsUser.All", "https://outlook.office.com/SMTP.Send").joinToString(" ")

    data class Tokens(val access: String, val refresh: String?, val expires: Long)

    fun isClientId(id: String) = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE).matches(id.trim())
    private fun prefs(c: Context) = c.getSharedPreferences("mail", Context.MODE_PRIVATE)
    fun clientId(c: Context) = prefs(c).getString("msClient", "")!!
    fun setClientId(c: Context, id: String) = prefs(c).edit().putString("msClient", id.trim()).apply()

    private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    /** A PKCE pair: the verifier stays here, its SHA-256 goes to Microsoft. */
    fun pkce(random: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }): Pair<String, String> {
        val verifier = b64url(random)
        return verifier to b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    fun authorizeUrl(clientId: String, redirect: String, challenge: String, state: String, address: String) = "$AUTHORITY/authorize?" +
        listOf("client_id" to clientId, "response_type" to "code", "redirect_uri" to redirect, "response_mode" to "query", "scope" to SCOPES,
            "code_challenge" to challenge, "code_challenge_method" to "S256", "state" to state, "login_hint" to address, "prompt" to "select_account")
            .joinToString("&") { (k, v) -> "$k=${enc(v)}" }

    /** What Microsoft answered, as tokens, or a [MailMsError] with its reason (msauth.js tokens()). */
    fun tokens(json: JSONObject, now: Long = System.currentTimeMillis()): Tokens {
        if (json.has("error") || !json.has("access_token")) {
            val text = "${json.optString("error")} ${json.optString("error_description")}"
            val reason = when {
                Regex("AADSTS(65001|90094|90008|900971)|admin|consent", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "approval" // the organisation must allow Tetra first
                Regex("invalid_grant|AADSTS70008|AADSTS700082|AADSTS50173", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "auth"
                else -> "other"
            }
            throw MailMsError(json.optString("error_description").substringBefore("\r\n").ifEmpty { json.optString("error").ifEmpty { "Microsoft gave no token" } }, reason)
        }
        return Tokens(json.getString("access_token"), json.optString("refresh_token").ifEmpty { null }, now + json.optLong("expires_in", 3600) * 1000 - 60_000)
    }

    private fun post(body: Map<String, String>): JSONObject {
        val c = (java.net.URL("$AUTHORITY/token").openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 20_000; readTimeout = 20_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            c.outputStream.use { it.write(body.entries.joinToString("&") { (k, v) -> "$k=${enc(v)}" }.toByteArray()) }
            val text = (if (c.responseCode < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            return runCatching { JSONObject(text) }.getOrDefault(JSONObject().put("error", "HTTP ${c.responseCode}"))
        } finally { c.disconnect() }
    }

    private fun page(ok: Boolean) = "<!doctype html><meta charset=\"utf-8\"><title>Tetra</title><body style=\"font:16px system-ui;padding:40px;text-align:center\">" +
        "<h2>${if (ok) "✓" else "✗"} Tetra</h2><p>${if (ok) "Μπορείς να γυρίσεις στο Tetra. · You can go back to Tetra." else "Η σύνδεση δεν έγινε. Γύρνα στο Tetra. · Sign-in did not finish. Go back to Tetra."}</p>"

    /** The query of a request line ("GET /?code=…&state=… HTTP/1.1") as a map. */
    fun query(requestLine: String): Map<String, String> = requestLine.split(' ').getOrNull(1)?.substringAfter('?', "").orEmpty().split('&').filter { '=' in it }
        .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    /**
     * The whole sign-in, blocking (run it off the screen's thread): a one-time server on 127.0.0.1, Microsoft's page by
     * [open], the code back, then the tokens. Gives up after five minutes.
     */
    fun signIn(clientId: String, address: String, open: (String) -> Unit, timeoutMs: Int = 5 * 60_000, exchange: (Map<String, String>) -> JSONObject = ::post): Tokens {
        if (!isClientId(clientId)) throw MailMsError("No Microsoft app id set", "noClient")
        val (verifier, challenge) = pkce()
        val state = b64url(ByteArray(16).also { SecureRandom().nextBytes(it) })
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = timeoutMs
            val redirect = "http://localhost:${server.localPort}/"
            open(authorizeUrl(clientId.trim(), redirect, challenge, state, address))
            while (true) {
                val socket = runCatching { server.accept() }.getOrElse { throw MailMsError("Microsoft sign-in took too long", "other") }
                socket.use { s ->
                    val q = query(s.getInputStream().bufferedReader().readLine().orEmpty())
                    if (q["state"] != state) { s.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return@use } // not this sign-in's answer
                    val code = q["code"]
                    val body = page(code != null).toByteArray()
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body)
                    // no code: Microsoft's own refusal, said as tokens() says it
                    val got = code ?: return tokens(JSONObject().put("error", q["error"] ?: "access_denied").put("error_description", q["error_description"].orEmpty()))
                    return tokens(exchange(mapOf("client_id" to clientId.trim(), "grant_type" to "authorization_code", "code" to got, "redirect_uri" to redirect,
                        "code_verifier" to verifier, "scope" to SCOPES)))
                }
            }
        }
    }

    /** The secret kept for a Microsoft account: its refresh token, marked so it is never taken for a password. */
    fun secret(refresh: String) = JSONObject().put("microsoft", true).put("refresh", refresh).toString()

    private val live = HashMap<String, Tokens>()
    /** A Microsoft access token for account [id]: the one in memory while it lasts, else renewed (and a new refresh token kept). */
    @Synchronized fun access(c: Context, id: String): String {
        live[id]?.takeIf { it.expires > System.currentTimeMillis() }?.let { return it.access }
        val refresh = MailSecret.get(c, id)?.let { runCatching { JSONObject(it).optString("refresh") }.getOrNull() }.orEmpty()
        if (refresh.isEmpty()) throw MailMsError("Not signed in with Microsoft on this device", "auth")
        if (!isClientId(clientId(c))) throw MailMsError("No Microsoft app id set", "noClient")
        val t = tokens(post(mapOf("client_id" to clientId(c), "grant_type" to "refresh_token", "refresh_token" to refresh, "scope" to SCOPES)))
        t.refresh?.takeIf { it != refresh }?.let { MailSecret.set(c, id, secret(it)) }
        live[id] = t
        return t.access
    }
    fun remember(id: String, t: Tokens) { synchronized(this) { live[id] = t } }
}
