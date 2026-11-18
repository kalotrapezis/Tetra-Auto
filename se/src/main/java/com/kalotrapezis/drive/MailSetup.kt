package com.kalotrapezis.drive

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Google's page for app passwords (MAIL_PLAN §9b: Gmail with an app password until «Σύνδεση με Google»). */
private const val GOOGLE_APP_PASSWORDS = "https://myaccount.google.com/apppasswords"
/** Google's app password: 16 letters, shown in four groups. */
private val APP_PASSWORD = Regex("^([a-z]{4}) ?([a-z]{4}) ?([a-z]{4}) ?([a-z]{4})$")

/**
 * Adding an account on this device (MAIL_PLAN §9b): the address → its servers found → the password (Gmail's app password
 * read from the clipboard on coming back from Google's page) → done. The password stays in this device's keystore;
 * the account (no secret) goes into accounts.<device>.json, which the PC shows as one of its shadows.
 */
@Composable
internal fun MailAddAccount(dismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(0) } // 0 the address, 1 the password, 2 done
    var address by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<MailDetect.Found?>(null) }
    var manual by remember { mutableStateOf(false) }
    var imap by remember { mutableStateOf(MailServer("", 993, "", true)) }
    var smtp by remember { mutableStateOf(MailServer("", 465, "", true)) }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val gmail = imap.host == "imap.gmail.com"
    val microsoft = found?.microsoft == true // Hotmail, Outlook.com, Microsoft 365: «Σύνδεση με Microsoft», no password
    var clientId by remember { mutableStateOf(MailMicrosoft.clientId(context)) }
    var msTokens by remember { mutableStateOf<MailMicrosoft.Tokens?>(null) }
    val back = resumes()
    // back from Google's page: its 16 letters from the clipboard, no typing
    LaunchedEffect(back.intValue) {
        if (step == 1 && gmail && password.isEmpty()) clipboardAppPassword(context)?.let { password = it }
    }

    fun next() = scope.launch {
        problem = null
        val a = address.trim().lowercase()
        if (!Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(a)) { problem = context.getString(R.string.mail_bad_address); return@launch }
        name = if (a.substringAfter('@') in setOf("gmail.com", "googlemail.com")) "Gmail" else a.substringAfter('@')
        if (manual) { if (imap.host.isNotEmpty() && imap.port in 1..65535 && smtp.host.isNotEmpty() && smtp.port in 1..65535) step = 1 else problem = context.getString(R.string.mail_not_found); return@launch }
        busy = context.getString(R.string.mail_finding)
        val f = withContext(Dispatchers.IO) { runCatching { MailDetect.find(a) }.getOrNull() }
        busy = null
        when {
            f == null -> { manual = true; imap = MailServer("", 993, a, true); smtp = MailServer("", 465, a, true); problem = context.getString(R.string.mail_not_found) }
            else -> { found = f; imap = f.imap; smtp = f.smtp; step = 1 }
        }
    }
    /** Microsoft's page in the browser, the answer back here, then the same check with the token (MailMicrosoft). */
    fun signInMicrosoft() = scope.launch {
        problem = null
        if (!MailMicrosoft.isClientId(clientId)) { problem = context.getString(R.string.mail_ms_client_hint); return@launch }
        MailMicrosoft.setClientId(context, clientId)
        busy = context.getString(R.string.mail_ms_waiting)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val r = withContext(Dispatchers.IO) { runCatching {
            val t = MailMicrosoft.signIn(clientId, address.trim(), open = { url -> main.post { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } } })
            t to MailEngine.check(imap, listOf(address.trim()), t.access, oauth = true)
        } }
        busy = null
        r.onSuccess { (t, s) -> msTokens = t; imap = s; smtp = smtp.copy(user = s.user); step = 2 }
            .onFailure { problem = mailWhy(context, it); WriterLog.w("Mail: Microsoft sign-in failed (${it.message})") }
    }
    fun signIn() = scope.launch {
        if (microsoft) { signInMicrosoft(); return@launch }
        problem = null; busy = context.getString(R.string.mail_checking)
        val r = withContext(Dispatchers.IO) { runCatching { MailEngine.check(imap, found?.users ?: listOf(imap.user.ifEmpty { address.trim() }), password.trim()) } }
        busy = null
        r.onSuccess { s -> imap = s; if (smtp.user.isEmpty() || smtp.user == found?.imap?.user) smtp = smtp.copy(user = s.user); step = 2 }
            .onFailure { problem = mailWhy(context, it); WriterLog.w("Mail: sign-in to ${imap.host} failed (${it.message})") }
    }

    AlertDialog(onDismissRequest = { if (busy == null) dismiss() },
        title = { Text(stringResource(if (step == 2) R.string.mail_done_title else R.string.mail_add_account)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (step) {
                0 -> {
                    OutlinedTextField(address, { address = it; problem = null }, singleLine = true, label = { Text(stringResource(R.string.mail_address)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                    if (manual) {
                        ServerFields(stringResource(R.string.mail_incoming), imap) { imap = it }
                        ServerFields(stringResource(R.string.mail_outgoing), smtp) { smtp = it }
                    } else TextButton(onClick = { manual = true; imap = imap.copy(user = address.trim()); smtp = smtp.copy(user = address.trim()) }) { Text(stringResource(R.string.mail_manual)) }
                }
                1 -> {
                    Text(address.trim(), style = MaterialTheme.typography.titleMedium)
                    Text("${imap.host}:${imap.port}", style = MaterialTheme.typography.bodySmall)
                    if (microsoft) {
                        Text(stringResource(R.string.mail_ms_hint), style = MaterialTheme.typography.bodyMedium)
                        // the app id registered once with Microsoft (MAIL_PLAN §13), the same as the PC's
                        OutlinedTextField(clientId, { clientId = it.trim(); problem = null }, singleLine = true, label = { Text(stringResource(R.string.mail_ms_client)) }, modifier = Modifier.fillMaxWidth())
                    } else if (gmail) {
                        Text(stringResource(R.string.mail_gmail_hint), style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GOOGLE_APP_PASSWORDS))) } }) { Text(stringResource(R.string.mail_gmail_open)) }
                            TextButton(onClick = { clipboardAppPassword(context, any = true)?.let { password = it } }) { Text(stringResource(R.string.common_paste)) }
                        }
                    }
                    if (!microsoft) OutlinedTextField(password, { password = it; problem = null }, singleLine = true, label = { Text(stringResource(R.string.mail_password)) },
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                    // kept in Passwords on another device (MailPasswords): given after the fingerprint or the screen lock
                    val saved = remember(imap.host, address) { runCatching { MailPasswords.candidates(BrowserSecrets.vault(context).logins, address, imap.host) }.getOrDefault(emptyList()) }
                    if (!microsoft && saved.isNotEmpty()) TextButton(onClick = {
                        val act = context.activity() ?: return@TextButton
                        authenticateVault(act, { password = saved.first().password; problem = null; WriterLog.i("Mail: the password filled from Passwords") }, { problem = it },
                            context.getString(R.string.pw_unlock), context.getString(R.string.home_vault_unlock_detail))
                    }) { Icon(painterResource(R.drawable.ic_key), null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.pw_from_passwords)) }
                }
                else -> {
                    Text(stringResource(R.string.mail_done_text, address.trim()))
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.common_name)) }, modifier = Modifier.fillMaxWidth())
                }
            }
            busy?.let { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { CircularProgressIndicator(Modifier.width(20.dp)); Text(it) } }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = busy == null, onClick = {
            when (step) {
                0 -> next()
                1 -> signIn()
                else -> scope.launch {
                    val a = address.trim().lowercase()
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val t = msTokens
                            if (t != null) { MailEngine.add(context.applicationContext, mailRoot(), a, name.trim().ifEmpty { a }, imap, smtp, MailMicrosoft.secret(t.refresh.orEmpty()), "microsoft"); MailMicrosoft.remember(a, t) }
                            else MailEngine.add(context.applicationContext, mailRoot(), a, name.trim().ifEmpty { a }, imap, smtp, password.trim())
                        }.onFailure { WriterLog.e("Mail: adding an account", it) }
                    }
                    Thread { MailEngine.syncAll(context.applicationContext) }.start()
                    dismiss()
                }
            }
        }) { Text(stringResource(when (step) { 0 -> R.string.common_next; 1 -> if (microsoft) R.string.mail_ms_sign_in else R.string.mail_sign_in; else -> R.string.common_done })) } },
        dismissButton = { if (step < 2) TextButton(onClick = { if (step == 1) { step = 0; password = "" } else dismiss() }) { Text(stringResource(if (step == 1) R.string.common_back else R.string.common_cancel)) } })
}

/** A Google app password on the clipboard (or, [any], whatever is there), without its spaces. */
private fun clipboardAppPassword(c: android.content.Context, any: Boolean = false): String? {
    val text = (c.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(c)?.toString() ?: return null).trim()
    return APP_PASSWORD.matchEntire(text.lowercase())?.groupValues?.drop(1)?.joinToString("") ?: text.takeIf { any && it.isNotEmpty() }
}

/** «Ρυθμίσεις χειροκίνητα»: a server's name, port, user name and security. */
@Composable private fun ServerFields(title: String, s: MailServer, set: (MailServer) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(s.host, { set(s.copy(host = it.trim())) }, singleLine = true, label = { Text(stringResource(R.string.mail_host)) }, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(if (s.port > 0) s.port.toString() else "", { v -> set(s.copy(port = v.filter(Char::isDigit).take(5).toIntOrNull() ?: 0)) }, singleLine = true, label = { Text(stringResource(R.string.mail_port)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(96.dp))
        OutlinedTextField(s.user, { set(s.copy(user = it.trim())) }, singleLine = true, label = { Text(stringResource(R.string.mail_user)) }, modifier = Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(s.secure, { set(s.copy(secure = true, starttls = false)) }, { Text("SSL/TLS") })
        FilterChip(s.starttls, { set(s.copy(secure = false, starttls = true)) }, { Text("STARTTLS") })
        FilterChip(!s.secure && !s.starttls, { set(s.copy(secure = false, starttls = false)) }, { Text(stringResource(R.string.mail_security_none)) })
    }
}
