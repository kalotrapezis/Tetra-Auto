package com.kalotrapezis.drive

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/*
 * What an action found or will do, as compact cards under the answer (the user, 1 Nov 2026: "show the info in a card instead"):
 * a contact with its numbers and last calls, the web's sources, files, a mail with its sender and receiver before it goes,
 * an SMS before it goes, the event, note or timer made. Each in its app's colour, as the skill bubbles are.
 */

/** What the cards can do, the chat's own ways (open, go, call, attach). */
internal class CardActions(val go: (String) -> Unit, val open: (File) -> Unit, val call: (PendingCall) -> Unit, val attach: (File) -> Unit,
    val say: (String, Boolean) -> Unit, val dialing: PendingCall?, val dialLeft: Int, val cancelDial: () -> Unit)

@Composable
internal fun ResultCards(cards: List<ResultCard>, a: CardActions) {
    Column(Modifier.padding(top = 6.dp).widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val contacts = cards.filterIsInstance<ResultCard.Contact>()
        if (contacts.size == 1) ContactCard(contacts[0], a, open = true)
        else if (contacts.size > 1) { var shown by remember(contacts) { mutableStateOf<String?>(null) }
            contacts.forEach { c -> if (shown == c.uid) ContactCard(c, a, open = true) else ContactCard(c, a, open = false) { shown = c.uid } } }
        cards.forEach { c -> when (c) {
            is ResultCard.Web -> WebCard(c, a)
            is ResultCard.Files -> FilesCard(c, a)
            is ResultCard.Sms -> SmsCard(c, a)
            is ResultCard.Event -> CardBox(AppColor.calendar) {
                Text(c.title, style = MaterialTheme.typography.titleSmall)
                val day = c.start.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
                Text(if (c.allDay) day else "$day · ${c.start.toLocalTime()}–${c.end.toLocalTime()}", style = MaterialTheme.typography.bodyMedium)
                Text(listOf(c.calendar, c.place).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is ResultCard.Note -> CardBox(AppColor.notes) {
                if (c.title.isNotBlank()) Text(c.title, style = MaterialTheme.typography.titleSmall)
                c.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                TextButton(onClick = { a.go("note:${c.id}") }) { Text(stringResource(R.string.common_open)) }
            }
            is ResultCard.Timer -> CardBox(AppColor.timer) {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(c) { while (now < c.endsAt) { kotlinx.coroutines.delay(1_000); now = System.currentTimeMillis() } }
                val left = ((c.endsAt - now).coerceAtLeast(0) / 1000)
                Text(c.label.ifBlank { stringResource(R.string.app_timer) }, style = MaterialTheme.typography.titleSmall)
                Text("%d:%02d".format(java.util.Locale.ROOT, left / 60, left % 60), style = MaterialTheme.typography.headlineSmall)
            }
            is ResultCard.Contact -> Unit
        } }
    }
}

@Composable
private fun CardBox(color: Color, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = color.copy(alpha = 0.14f).compositeOver(MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth().let { m -> onClick?.let { m.clickable(onClick = it) } ?: m }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

/** A contact: photo, name, each number (the default marked), the last calls, Open · Call · Message; [open] false: one compact row. */
@Composable
private fun ContactCard(c: ResultCard.Contact, a: CardActions, open: Boolean, expand: () -> Unit = {}) {
    val context = LocalContext.current
    val info = remember(c) { ContactInfo(uid = c.uid, formatted = c.name, photo = c.photo) }
    val main = c.numbers.firstOrNull { it.default } ?: c.numbers.firstOrNull()
    CardBox(Color(0xFF2E8B97), onClick = if (open) null else expand) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ContactAvatar(info, if (open) 48.dp else 32.dp)
            Column(Modifier.weight(1f)) {
                Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!open) main?.let { Text(it.value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (!open) return@CardBox
        c.numbers.forEach { n -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(n.value, style = MaterialTheme.typography.bodyLarge)
            Text(n.label + if (n.default && c.numbers.size > 1) " · " + stringResource(R.string.assistant_card_default) else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        // the last calls with them, from the phone's call log (none without its permission)
        val calls by produceState(emptyList<CallEntry>(), c) { value = withContext(Dispatchers.IO) { runCatching { ContactsSystem.calls(context, c.numbers.map { it.value }, 3) }.getOrDefault(emptyList()) } }
        if (calls.isNotEmpty()) {
            Text(stringResource(R.string.assistant_card_recent), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            calls.forEach { e -> Text((when (e.type) { android.provider.CallLog.Calls.OUTGOING_TYPE -> "↗ "; android.provider.CallLog.Calls.MISSED_TYPE -> "✗ "; else -> "↙ " }) +
                Instant.ofEpochMilli(e.at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)) +
                if (e.seconds > 0) " · %d:%02d".format(java.util.Locale.ROOT, e.seconds / 60, e.seconds % 60) else "", style = MaterialTheme.typography.bodySmall) }
        }
        val dialing = a.dialing?.takeIf { d -> c.numbers.any { it.value == d.number } }
        if (dialing != null) {
            // a call asked by voice: it goes by itself; Άκυρο is big enough to hit while driving
            Text(stringResource(R.string.assistant_calling_in, a.dialLeft), style = MaterialTheme.typography.titleMedium)
            Button(onClick = a.cancelDial, Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(R.string.common_cancel), style = MaterialTheme.typography.titleMedium) }
        } else Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (c.uid.isNotEmpty()) TextButton(onClick = { a.go("contact:${c.uid}") }) { Text(stringResource(R.string.common_open)) }
            main?.let { n -> TextButton(onClick = { a.call(PendingCall(c.name, n.value)) }) { Text(stringResource(R.string.contacts_call)) }
                TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", n.value, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) {
                    Text(stringResource(R.string.contacts_message)) } }
        }
    }
}

/** The web's sources: a tap opens the page in Tetra's Browser; the search itself there too. */
@Composable
private fun WebCard(c: ResultCard.Web, a: CardActions) {
    c.sources.take(5).forEach { s -> CardBox(Color(0xFF25C4FE), onClick = { a.go(AssistantTools.browserAt(s.url)) }) {
        Text(s.title.ifBlank { s.url }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(runCatching { Uri.parse(s.url).host?.removePrefix("www.") }.getOrNull() ?: s.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    } }
    if (c.query.isNotBlank()) TextButton(onClick = { a.go(AssistantTools.browserAt(BrowserRules.SEARCH + java.net.URLEncoder.encode(c.query.trim(), "UTF-8"))) }) {
        Text(stringResource(R.string.assistant_card_search_browser)) }
}

/** Files: a row each (icon, name, folder, size, date); a tap opens it; Beside and «Chat about it». */
@Composable
private fun FilesCard(c: ResultCard.Files, a: CardActions) {
    val context = LocalContext.current
    CardBox(Color(0xFF177BFC)) {
        c.files.forEach { f -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { a.open(f) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(if (f.extension.lowercase() in setOf("docx", "odt", "doc")) R.drawable.ic_file_word else R.drawable.ic_file_text), contentDescription = null, Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val folder = runCatching { f.parentFile!!.relativeTo(c.root).path }.getOrDefault("").ifEmpty { "Tetra" }
                Text("$folder · ${android.text.format.Formatter.formatShortFileSize(context, f.length())} · " +
                    Instant.ofEpochMilli(f.lastModified()).atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (Attach.kindOf(f.name) != null) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (Attach.kindOf(f.name) != null) TextButton(onClick = { a.attach(f) }) { Text(stringResource(R.string.assistant_card_chat)) }
        } }
    }
}

/** An SMS written: To (the name and the number), the text; Send · Edit · Cancel. Sent only by the user's Send. */
@Composable
private fun SmsCard(c: ResultCard.Sms, a: CardActions) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember(c) { mutableStateOf(0) } // 0 waiting, 1 sending, 2 done (sent or cancelled)
    fun send() { state = 1; scope.launch {
        val r = withContext(Dispatchers.IO) { runCatching { SmsBox.send(context.applicationContext, c.number, c.text) } }
        state = 2
        r.onSuccess { a.say(context.getString(R.string.sms_sent), false) }.onFailure { WriterLog.w("AI Assist: the SMS was not sent", it); state = 0; a.say(context.getString(R.string.sms_send_failed, it.message.orEmpty()), true) }
    } }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) send() else a.say(context.getString(R.string.sms_permission_needed), true) }
    CardBox(SMS_COLOR) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.assistant_card_to), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (c.name == c.number) c.number else "${c.name} · ${c.number}", style = MaterialTheme.typography.bodyMedium)
        }
        Text(c.text, style = MaterialTheme.typography.bodyMedium)
        if (state == 0) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { if (SmsBox.canSend(context)) send() else ask.launch(android.Manifest.permission.SEND_SMS) }) { Text(stringResource(R.string.common_send)) }
            TextButton(onClick = { state = 2; a.go("sms:" + org.json.JSONObject().put("to", c.number).put("text", c.text)) }) { Text(stringResource(R.string.common_edit)) }
            TextButton(onClick = { state = 2; a.say(context.getString(R.string.assistant_mail_kept), false) }) { Text(stringResource(R.string.common_cancel)) }
        } else if (state == 1) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
