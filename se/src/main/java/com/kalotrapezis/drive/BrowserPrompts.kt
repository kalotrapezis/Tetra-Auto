package com.kalotrapezis.drive

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate.*
import java.time.LocalDate
import java.time.temporal.IsoFields

/**
 * A page's own questions — alert, confirm, prompt, a <select>, a date, a colour, a site's password, a window it wants to
 * open, leaving a page — answered in Tetra's dialogs and sheets (GeckoView draws none of its own). One at a time; a new one
 * dismisses the one before. Files go to Android's picker through [pick].
 */
internal class BrowserPrompter(private val context: Context, private val pick: (Intent, (Array<Uri>?) -> Unit) -> Unit) : GeckoSession.PromptDelegate {
    class Pending(val prompt: BasePrompt, val result: GeckoResult<PromptResponse>)
    var shown by mutableStateOf<Pending?>(null)

    private fun ask(p: BasePrompt): GeckoResult<PromptResponse> {
        shown?.let { it.result.complete(it.prompt.dismiss()) }
        val r = GeckoResult<PromptResponse>()
        p.setDelegate(object : PromptInstanceDelegate { override fun onPromptDismiss(prompt: BasePrompt) { if (shown?.prompt === prompt) shown = null } })
        shown = Pending(p, r)
        WriterLog.i("Browser: a page asks (${p.javaClass.simpleName})")
        return r
    }
    fun answer(r: PromptResponse) { val s = shown ?: return; shown = null; s.result.complete(r) }

    override fun onAlertPrompt(session: GeckoSession, prompt: AlertPrompt) = ask(prompt)
    override fun onButtonPrompt(session: GeckoSession, prompt: ButtonPrompt) = ask(prompt)
    override fun onTextPrompt(session: GeckoSession, prompt: TextPrompt) = ask(prompt)
    override fun onAuthPrompt(session: GeckoSession, prompt: AuthPrompt) = ask(prompt)
    override fun onChoicePrompt(session: GeckoSession, prompt: ChoicePrompt) = ask(prompt)
    override fun onColorPrompt(session: GeckoSession, prompt: ColorPrompt) = ask(prompt)
    override fun onDateTimePrompt(session: GeckoSession, prompt: DateTimePrompt) = ask(prompt)
    override fun onPopupPrompt(session: GeckoSession, prompt: PopupPrompt) = ask(prompt)
    override fun onBeforeUnloadPrompt(session: GeckoSession, prompt: BeforeUnloadPrompt) = ask(prompt)
    override fun onRepostConfirmPrompt(session: GeckoSession, prompt: RepostConfirmPrompt) = ask(prompt)

    override fun onFilePrompt(session: GeckoSession, prompt: FilePrompt): GeckoResult<PromptResponse> {
        val r = GeckoResult<PromptResponse>()
        val many = prompt.type == FilePrompt.Type.MULTIPLE
        val types = prompt.mimeTypes.orEmpty().filter { '/' in it }.distinct()
        val intent = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType(types.singleOrNull() ?: "*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, many).apply { if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray()) }
        pick(intent) { uris ->
            if (uris.isNullOrEmpty()) { r.complete(prompt.dismiss()); return@pick }
            // Gecko reads files, not content:// documents: each copied into the cache first, off the main thread
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            Thread {
                val files = runCatching { uris.map(::copy).toTypedArray() }.onFailure { WriterLog.e("Browser: a file for a page not read", it) }.getOrNull()
                main.post { r.complete(when { files == null -> prompt.dismiss(); many -> prompt.confirm(context, files); else -> prompt.confirm(context, files[0]) }) }
            }.start()
        }
        return r
    }

    private fun copy(uri: Uri): Uri {
        val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?.replace('/', '_')?.takeIf { it.isNotBlank() } ?: "file"
        val dir = java.io.File(context.cacheDir, "uploads/${System.nanoTime()}").apply { mkdirs() }
        val f = java.io.File(dir, name)
        context.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        WriterLog.i("Browser: ${f.length()} bytes given to a page (${name.substringAfterLast('.', "")})")
        return Uri.fromFile(f)
    }
    /** What earlier uploads left in the cache (the Browser opening again). */
    fun clean() = Thread { java.io.File(context.cacheDir, "uploads").deleteRecursively() }.start()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserPromptView(p: BrowserPrompter) {
    val s = p.shown ?: return
    val ok = stringResource(R.string.common_ok)
    val cancel = stringResource(R.string.common_cancel)
    @Composable fun title(q: BasePrompt, fallback: String = "") = (q.title?.takeIf { it.isNotBlank() } ?: fallback).takeIf { it.isNotEmpty() }?.let { t -> @Composable { Text(t, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
    when (val q = s.prompt) {
        is AlertPrompt -> TetraAlertDialog(onDismissRequest = { p.answer(q.dismiss()) }, title = title(q), text = { Text(q.message.orEmpty()) },
            confirmButton = { TextButton(onClick = { p.answer(q.dismiss()) }) { Text(ok) } })
        is ButtonPrompt -> TetraAlertDialog(onDismissRequest = { p.answer(q.dismiss()) }, title = title(q), text = { Text(q.message.orEmpty()) },
            confirmButton = { TextButton(onClick = { p.answer(q.confirm(ButtonPrompt.Type.POSITIVE)) }) { Text(ok) } },
            dismissButton = { TextButton(onClick = { p.answer(q.confirm(ButtonPrompt.Type.NEGATIVE)) }) { Text(cancel) } })
        is TextPrompt -> {
            var text by remember(q) { mutableStateOf(q.defaultValue.orEmpty()) }
            TetraAlertDialog(onDismissRequest = { p.answer(q.dismiss()) }, title = title(q),
                text = { Column { Text(q.message.orEmpty()); OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) } },
                confirmButton = { TextButton(onClick = { p.answer(q.confirm(text)) }) { Text(ok) } },
                dismissButton = { TextButton(onClick = { p.answer(q.dismiss()) }) { Text(cancel) } })
        }
        is AuthPrompt -> {
            val onlyPassword = q.authOptions.flags and AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD != 0
            var user by remember(q) { mutableStateOf(q.authOptions.username.orEmpty()) }
            var pass by remember(q) { mutableStateOf(q.authOptions.password.orEmpty()) }
            TetraAlertDialog(onDismissRequest = { p.answer(q.dismiss()) }, title = title(q),
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(q.message.orEmpty())
                    if (!onlyPassword) OutlinedTextField(user, { user = it }, singleLine = true, label = { Text(stringResource(R.string.browser_auth_user)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(pass, { pass = it }, singleLine = true, label = { Text(stringResource(R.string.browser_auth_password)) }, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                } },
                confirmButton = { TextButton(onClick = { p.answer(if (onlyPassword) q.confirm(pass) else q.confirm(user, pass)) }) { Text(ok) } },
                dismissButton = { TextButton(onClick = { p.answer(q.dismiss()) }) { Text(cancel) } })
        }
        // a <select>: its options from the bottom, as Tetra's other choices (a group's name over its options)
        is ChoicePrompt -> {
            val many = q.type == ChoicePrompt.Type.MULTIPLE
            val rows = remember(q) { q.choices.flatMap { c -> if (c.items != null) listOf(c) + c.items!!.toList() else listOf(c) }.filter { !it.separator } }
            var picked by remember(q) { mutableStateOf(rows.filter { it.selected && it.items == null }.map { it.id }.toSet()) }
            TetraSheet(onDismissRequest = { p.answer(q.dismiss()) }) {
                Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                    (q.title ?: q.message)?.takeIf { it.isNotBlank() }?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium) }
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(rows) { c ->
                            if (c.items != null) Text(c.label, Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = BROWSER_COLOR)
                            else Row(Modifier.fillMaxWidth().clickable(enabled = !c.disabled) {
                                if (many) picked = if (c.id in picked) picked - c.id else picked + c.id else p.answer(q.confirm(c))
                            }.padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (many) Checkbox(c.id in picked, null, enabled = !c.disabled) else RadioButton(c.selected, null, enabled = !c.disabled)
                                Text(c.label, Modifier.padding(start = 8.dp, top = 12.dp, bottom = 12.dp), color = if (c.disabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f) else Color.Unspecified)
                            }
                        }
                    }
                    if (many) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { p.answer(q.dismiss()) }) { Text(cancel) }
                        TextButton(onClick = { p.answer(q.confirm(picked.toTypedArray())) }) { Text(ok) }
                    }
                }
            }
        }
        is ColorPrompt -> {
            var hex by remember(q) { mutableStateOf(q.defaultValue ?: "#000000") }
            val colours = (q.predefinedValues?.toList().orEmpty() + TILE_COLORS.map { String.format("#%06X", it.toArgb() and 0xFFFFFF) } + listOf("#000000", "#FFFFFF")).distinct()
            fun parse(h: String) = runCatching { Color(android.graphics.Color.parseColor(h)) }.getOrNull()
            TetraAlertDialog(onDismissRequest = { p.answer(q.dismiss()) }, title = title(q, stringResource(R.string.browser_color)),
                text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        colours.forEach { h -> parse(h)?.let { c -> Box(Modifier.size(36.dp).clip(CircleShape).background(c).border(if (h.equals(hex, true)) 3.dp else 1.dp, MaterialTheme.colorScheme.outline, CircleShape).clickable { hex = h }) } }
                    }
                    OutlinedTextField(hex, { hex = it }, singleLine = true, leadingIcon = { Box(Modifier.size(20.dp).clip(CircleShape).background(parse(hex) ?: Color.Transparent)) })
                } },
                confirmButton = { TextButton(onClick = { p.answer(q.confirm(hex)) }, enabled = parse(hex) != null && hex.startsWith("#") && hex.length == 7) { Text(ok) } },
                dismissButton = { TextButton(onClick = { p.answer(q.dismiss()) }) { Text(cancel) } })
        }
        is DateTimePrompt -> DateTimeAsk(q, p)
        is PopupPrompt -> TetraAlertDialog(onDismissRequest = { p.answer(q.confirm(AllowOrDeny.DENY)) }, title = { Text(stringResource(R.string.browser_popup_title)) },
            text = { Text(q.targetUri.orEmpty(), maxLines = 3, overflow = TextOverflow.Ellipsis) },
            confirmButton = { TextButton(onClick = { p.answer(q.confirm(AllowOrDeny.ALLOW)) }) { Text(stringResource(R.string.browser_popup_open)) } },
            dismissButton = { TextButton(onClick = { p.answer(q.confirm(AllowOrDeny.DENY)) }) { Text(cancel) } })
        is BeforeUnloadPrompt -> TetraAlertDialog(onDismissRequest = { p.answer(q.confirm(AllowOrDeny.DENY)) }, title = { Text(stringResource(R.string.browser_leave_title)) },
            confirmButton = { TextButton(onClick = { p.answer(q.confirm(AllowOrDeny.ALLOW)) }) { Text(stringResource(R.string.browser_leave)) } },
            dismissButton = { TextButton(onClick = { p.answer(q.confirm(AllowOrDeny.DENY)) }) { Text(stringResource(R.string.browser_stay)) } })
        is RepostConfirmPrompt -> TetraAlertDialog(onDismissRequest = { p.answer(q.confirm(AllowOrDeny.DENY)) }, title = { Text(stringResource(R.string.browser_repost)) },
            confirmButton = { TextButton(onClick = { p.answer(q.confirm(AllowOrDeny.ALLOW)) }) { Text(stringResource(R.string.browser_resend)) } },
            dismissButton = { TextButton(onClick = { p.answer(q.confirm(AllowOrDeny.DENY)) }) { Text(cancel) } })
        else -> LaunchedEffect(q) { p.answer(q.dismiss()) }
    }
}

/** <input type=date|month|week|time|datetime-local>: Material's date picker, then its clock for a time; Clear empties the field. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeAsk(q: DateTimePrompt, p: BrowserPrompter) {
    val type = q.type
    val ok = stringResource(R.string.common_ok)
    val clear = @Composable { TextButton(onClick = { p.answer(q.confirm("")) }) { Text(stringResource(R.string.common_clear)) } }
    val value = q.defaultValue.orEmpty()
    val initial = remember(q) { BrowserDates.date(value, type) }
    var date by remember(q) { mutableStateOf<LocalDate?>(null) }
    if (type != DateTimePrompt.Type.TIME && date == null) {
        val state = rememberDatePickerState(initialSelectedDateMillis = initial?.toEpochDay()?.times(86_400_000L))
        TetraDatePickerDialog(onDismissRequest = { p.answer(q.dismiss()) }, dismissButton = clear, confirmButton = {
            TextButton(onClick = {
                val d = state.selectedDateMillis?.let { LocalDate.ofEpochDay(it / 86_400_000L) } ?: return@TextButton
                if (type == DateTimePrompt.Type.DATETIME_LOCAL) date = d else p.answer(q.confirm(BrowserDates.format(type, d, 0, 0)))
            }) { Text(ok) }
        }) { DatePicker(state) }
    } else {
        val (h, m) = remember(q) { BrowserDates.time(value) }
        val state = rememberTimePickerState(h, m, true)
        TetraAlertDialog(onDismissRequest = { p.answer(q.dismiss()) }, text = { TimePicker(state) }, dismissButton = clear,
            confirmButton = { TextButton(onClick = { p.answer(q.confirm(BrowserDates.format(type, date, state.hour, state.minute))) }) { Text(ok) } })
    }
}

/** The HTML forms of a date input's value (BrowserTest). */
internal object BrowserDates {
    fun format(type: Int, d: LocalDate?, h: Int, m: Int): String = when (type) {
        DateTimePrompt.Type.MONTH -> "%04d-%02d".format(d!!.year, d.monthValue)
        DateTimePrompt.Type.WEEK -> "%04d-W%02d".format(d!!.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
        DateTimePrompt.Type.TIME -> "%02d:%02d".format(h, m)
        DateTimePrompt.Type.DATETIME_LOCAL -> "%sT%02d:%02d".format(d.toString(), h, m)
        else -> d.toString()
    }
    /** The day a value stands for (a week: its Monday), or null. */
    fun date(v: String, type: Int): LocalDate? = runCatching {
        when (type) {
            DateTimePrompt.Type.MONTH -> LocalDate.parse("$v-01")
            DateTimePrompt.Type.WEEK -> v.split("-W").let { (y, w) -> LocalDate.of(y.toInt(), 1, 4).with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, w.toLong()).with(java.time.DayOfWeek.MONDAY) }
            else -> LocalDate.parse(v.take(10))
        }
    }.getOrNull()
    fun time(v: String): Pair<Int, Int> = Regex("(\\d{2}):(\\d{2})").find(v)?.destructured?.let { (h, m) -> h.toInt() to m.toInt() } ?: (12 to 0)
}
