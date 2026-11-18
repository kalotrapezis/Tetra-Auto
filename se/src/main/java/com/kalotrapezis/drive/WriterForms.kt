package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Forms (asked 2026-10-01: "the next thing missing I have on my words is forms"): Word's content controls in the text —
 * a text field, a drop-down (or combo box), a date, a check box. Each is its letters' [DocRun.control], Word's sdtPr as
 * the file has it; filling one changes its letters and, for a date or a check box, the value the sdtPr holds.
 * ponytail: run-level controls only (inside a paragraph); block-level ones (a table of contents, a control around whole
 * paragraphs or table rows) still make the file copy-only. Controls inside table cells are kept but not filled from
 * the bar.
 */
internal object Forms {
    enum class Kind { Text, List, Date, Check, Picture }

    private fun attr(tag: String, name: String) = Regex("""\b$name="([^"]*)"""").find(tag)?.groupValues?.get(1)?.let(::unesc)
    private fun unesc(s: String) = s.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    private fun tag(pr: String, name: String) = Regex("""<$name\b[^>]*>""").find(pr)?.value

    fun kind(pr: String) = when {
        tag(pr, "w:picture") != null -> Kind.Picture // its letter is a picture, chosen to fill it
        tag(pr, "w14:checkbox") != null -> Kind.Check
        tag(pr, "w:dropDownList") != null || tag(pr, "w:comboBox") != null -> Kind.List
        tag(pr, "w:date") != null -> Kind.Date
        else -> Kind.Text
    }
    /** What Word calls it (its title), else its tag. */
    fun name(pr: String) = tag(pr, "w:alias")?.let { attr(it, "w:val") } ?: tag(pr, "w:tag")?.let { attr(it, "w:val") }
    /** A drop-down's choices, as shown. */
    fun items(pr: String) = Regex("""<w:listItem\b[^>]*>""").findAll(pr).mapNotNull { m -> attr(m.value, "w:displayText") ?: attr(m.value, "w:value") }.toList()
    fun checked(pr: String) = tag(pr, "w14:checked")?.let { attr(it, "w14:val") }.let { it == "1" || it == "true" }
    fun date(pr: String): LocalDate? = tag(pr, "w:date")?.let { attr(it, "w:fullDate") }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

    /** A date as the control's own format says (Word's: dddd, MMMM, yyyy…), in its language. */
    fun dateText(pr: String, d: LocalDate): String {
        val format = tag(pr, "w:dateFormat")?.let { attr(it, "w:val") } ?: "d/M/yyyy"
        val lang = tag(pr, "w:lid")?.let { attr(it, "w:val") }
        val java = format.replace("dddd", "EEEE").replace(Regex("(?<!d)ddd(?!d)"), "EEE").replace("'", "''")
        return runCatching { DateTimeFormatter.ofPattern(java, lang?.let(Locale::forLanguageTag) ?: Locale.getDefault()).format(d) }.getOrElse { d.toString() }
    }

    /** The control filled with [text], its sdtPr changed to [pr] (a placeholder's grey goes, as Word's). */
    /** A picture control filled: the picture in it is [key]'s now, its placeholder state gone. */
    fun pictureFilled(doc: OfficeDoc, key: String): OfficeDoc = doc.copy(paras = doc.paras.map { p ->
        if (p.runs.none { it.image?.key == key && it.control != null }) p
        else p.copy(runs = p.runs.map { r -> if (r.image?.key == key && r.control != null) r.copy(control = r.control.replace(Regex("""<w:showingPlcHdr\s*/>"""), "")) else r })
    })
    fun fill(doc: OfficeDoc, old: String, pr: String, text: String): OfficeDoc {
        val plain = pr.replace(Regex("""<w:showingPlcHdr\s*/>"""), "")
        val wasPlaceholder = plain != pr
        return doc.copy(paras = doc.paras.map { p ->
            val i = p.runs.indexOfFirst { it.control == old }
            if (i < 0) p else {
                val first = p.runs[i]
                val runs = p.runs.filterIndexed { k, r -> k == i || r.control != old }.toMutableList()
                runs[i] = first.copy(text = text, control = plain, color = if (wasPlaceholder) null else first.color, image = null)
                p.copy(runs = OfficeFormats.tidy(runs))
            }
        })
    }
    /** Insert › Content control's kinds, as Word's Developer tab names them. */
    enum class New(val label: String, @androidx.annotation.StringRes val res: Int) { PlainText("Plain text", R.string.words_cc_plain_text), RichText("Rich text", R.string.words_cc_rich_text), CheckBox("Check box", R.string.words_cc_check_box),
        DropDown("Drop-down list", R.string.words_cc_drop_down_list), ComboBox("Combo box", R.string.words_cc_combo_box), Date("Date picker", R.string.words_cc_date_picker), Picture("Picture", R.string.words_picture) }

    /**
     * A new content control as Word writes one: its sdtPr ([title] as its title and tag; a list's [items]; a date's
     * [dateFormat]) and the placeholder its letters show until filled (Word's own words, grey), or the empty box.
     */
    fun newControl(kind: New, title: String, items: List<String> = emptyList(), dateFormat: String = "d/M/yyyy", id: Int = (Math.random() * 2_000_000_000).toInt()): Pair<String, String> {
        fun esc(v: String) = OfficeFormats.esc(v).replace("\"", "&quot;")
        val named = title.trim().takeIf { it.isNotEmpty() }?.let { """<w:alias w:val="${esc(it)}"/><w:tag w:val="${esc(it)}"/>""" }.orEmpty()
        val head = "<w:sdtPr>$named<w:id w:val=\"$id\"/>"
        fun list(tag: String) = "<w:$tag>" + items.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("") { """<w:listItem w:displayText="${esc(it)}" w:value="${esc(it)}"/>""" } + "</w:$tag>"
        return when (kind) {
            New.PlainText -> "$head<w:showingPlcHdr/><w:text/></w:sdtPr>" to "Click or tap here to enter text."
            New.RichText -> "$head<w:showingPlcHdr/></w:sdtPr>" to "Click or tap here to enter text."
            New.DropDown -> "$head<w:showingPlcHdr/>${list("dropDownList")}</w:sdtPr>" to "Choose an item."
            New.ComboBox -> "$head<w:showingPlcHdr/>${list("comboBox")}</w:sdtPr>" to "Choose an item."
            New.Date -> "$head<w:showingPlcHdr/><w:date><w:dateFormat w:val=\"${esc(dateFormat)}\"/><w:lid w:val=\"en-GB\"/><w:storeMappedDataAs w:val=\"dateTime\"/><w:calendar w:val=\"gregorian\"/></w:date></w:sdtPr>" to "Click or tap to enter a date."
            New.Picture -> "$head<w:showingPlcHdr/><w:picture/></w:sdtPr>" to OBJECT_MARK.toString() // the caller puts its placeholder picture there
            New.CheckBox -> """$head<w14:checkbox xmlns:w14="http://schemas.microsoft.com/office/word/2010/wordml"><w14:checked w14:val="0"/><w14:checkedState w14:val="2612" w14:font="MS Gothic"/><w14:uncheckedState w14:val="2610" w14:font="MS Gothic"/></w14:checkbox></w:sdtPr>""" to "☐"
        }
    }

    /**
     * LibreOffice's content control (loext:content-control, its [attrs] without the prefix, its list items as
     * (display text, value)) as the Word sdtPr the rest of Writer fills — LibreOffice maps Word's the same way. Null
     * for a kind Writer does not fill (a picture control).
     */
    fun fromOdt(attrs: Map<String, String>, items: List<Pair<String, String>>): String? {
        fun esc(v: String) = OfficeFormats.esc(v).replace("\"", "&quot;")
        fun on(name: String) = attrs[name] == "true"
        val head = listOfNotNull(attrs["alias"]?.let { "<w:alias w:val=\"${esc(it)}\"/>" }, attrs["tag"]?.let { "<w:tag w:val=\"${esc(it)}\"/>" },
            attrs["id"]?.let { "<w:id w:val=\"${esc(it)}\"/>" }, "<w:showingPlcHdr/>".takeIf { on("showing-place-holder") }).joinToString("")
        fun hex(state: String?, or: Int) = "%04X".format(state?.codePointAt(0) ?: or)
        val body = when {
            on("picture") -> "<w:picture/>"
            on("checkbox") -> """<w14:checkbox xmlns:w14="http://schemas.microsoft.com/office/word/2010/wordml"><w14:checked w14:val="${if (on("checked")) 1 else 0}"/><w14:checkedState w14:val="${hex(attrs["checked-state"], 0x2612)}" w14:font="MS Gothic"/><w14:uncheckedState w14:val="${hex(attrs["unchecked-state"], 0x2610)}" w14:font="MS Gothic"/></w14:checkbox>"""
            on("dropdown") || on("combobox") -> (if (on("combobox")) "comboBox" else "dropDownList").let { t ->
                "<w:$t>" + items.joinToString("") { (shown, value) -> """<w:listItem w:displayText="${esc(shown)}" w:value="${esc(value)}"/>""" } + "</w:$t>" }
            on("date") -> "<w:date${attrs["current-date"]?.let { " w:fullDate=\"${esc(it)}\"" } ?: ""}><w:dateFormat w:val=\"${esc(attrs["date-format"] ?: "d/M/yyyy")}\"/>" +
                (attrs["date-rfc-language-tag"]?.let { "<w:lid w:val=\"${esc(it)}\"/>" } ?: "") + "<w:storeMappedDataAs w:val=\"dateTime\"/><w:calendar w:val=\"gregorian\"/></w:date>"
            on("plain-text") -> "<w:text/>"
            else -> ""
        }
        return "<w:sdtPr>$head$body</w:sdtPr>"
    }

    /** [pr] (a Word sdtPr) as LibreOffice's content control: its opening tag and list items (the letters go after them). */
    fun odt(pr: String): String {
        fun esc(v: String) = OfficeFormats.esc(v).replace("\"", "&quot;")
        val a = StringBuilder()
        if (tag(pr, "w:showingPlcHdr") != null) a.append(" loext:showing-place-holder=\"true\"")
        var items = ""
        when (kind(pr)) {
            Kind.Check -> a.append(" loext:checkbox=\"true\" loext:checked=\"${checked(pr)}\" loext:checked-state=\"${box(pr, true)}\" loext:unchecked-state=\"${box(pr, false)}\"")
            Kind.List -> {
                if (tag(pr, "w:comboBox") != null) a.append(" loext:combobox=\"true\"")
                a.append(" loext:dropdown=\"true\"")
                items = Regex("""<w:listItem\b[^>]*>""").findAll(pr).joinToString("") { m ->
                    val shown = attr(m.value, "w:displayText") ?: attr(m.value, "w:value") ?: ""
                    """<loext:list-item loext:display-text="${esc(shown)}" loext:value="${esc(attr(m.value, "w:value") ?: shown)}"/>""" }
            }
            Kind.Date -> {
                a.append(" loext:date=\"true\" loext:date-format=\"${esc(tag(pr, "w:dateFormat")?.let { attr(it, "w:val") } ?: "d/M/yyyy")}\"")
                tag(pr, "w:lid")?.let { attr(it, "w:val") }?.let { a.append(" loext:date-rfc-language-tag=\"${esc(it)}\"") }
                tag(pr, "w:date")?.let { attr(it, "w:fullDate") }?.let { a.append(" loext:current-date=\"${esc(it)}\"") }
            }
            Kind.Text -> if (tag(pr, "w:text") != null) a.append(" loext:plain-text=\"true\"")
            Kind.Picture -> a.append(" loext:picture=\"true\"")
        }
        tag(pr, "w:alias")?.let { attr(it, "w:val") }?.let { a.append(" loext:alias=\"${esc(it)}\"") }
        tag(pr, "w:tag")?.let { attr(it, "w:val") }?.let { a.append(" loext:tag=\"${esc(it)}\"") }
        tag(pr, "w:id")?.let { attr(it, "w:val") }?.let { a.append(" loext:id=\"${esc(it)}\"") }
        return "<loext:content-control$a>$items"
    }

    fun checkedTo(pr: String, on: Boolean) = pr.replace(Regex("""(<w14:checked\b[^>]*\bw14:val=")[^"]*"""), "$1${if (on) 1 else 0}")
    /** The check box's letter: its own (checkedState / uncheckedState), else Word's ☒ / ☐. */
    fun box(pr: String, on: Boolean) = tag(pr, if (on) "w14:checkedState" else "w14:uncheckedState")?.let { attr(it, "w14:val") }
        ?.toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: if (on) "☒" else "☐"
    fun dateTo(pr: String, d: LocalDate) = pr.replace(Regex("""(<w:date\b[^>]*\bw:fullDate=")[^"]*"""), "$1${d}T00:00:00Z")
        .let { if (it == pr && "w:fullDate" !in pr) it.replaceFirst("<w:date", "<w:date w:fullDate=\"${d}T00:00:00Z\"") else it }
}

/** The form field the cursor is in, above the ribbon: its name, and what fills it — a choice, a date, a tick. */
@Composable
internal fun FormBar(pr: String, text: String, w: Color, choosePicture: () -> Unit = {}, fill: (pr: String, text: String) -> Unit) {
    val context = LocalContext.current
    var typing by remember { mutableStateOf(false) }
    val kind = Forms.kind(pr)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(Forms.name(pr) ?: stringResource(when (kind) { Forms.Kind.Text -> R.string.words_text_field; Forms.Kind.List -> R.string.words_drop_down; Forms.Kind.Date -> R.string.words_date; Forms.Kind.Check -> R.string.words_cc_check_box; Forms.Kind.Picture -> R.string.words_picture }),
            color = w, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 6.dp))
        when (kind) {
            Forms.Kind.Text -> TextButton(onClick = { typing = true }) { Text(stringResource(R.string.words_fill_in), color = Color.White) }
            Forms.Kind.List -> Forms.items(pr).forEach { item ->
                TextButton(onClick = { fill(pr, item) }) { Text(item, color = if (item == text) Color(0xFF81C784) else Color.White) }
            }
            Forms.Kind.Date -> TextButton(onClick = {
                val d = Forms.date(pr) ?: LocalDate.now()
                android.app.DatePickerDialog(context, { _, y, m, day -> LocalDate.of(y, m + 1, day).let { fill(Forms.dateTo(pr, it), Forms.dateText(pr, it)) } },
                    d.year, d.monthValue - 1, d.dayOfMonth).show()
            }) { Text(stringResource(R.string.words_choose_date), color = Color.White) }
            Forms.Kind.Picture -> TextButton(onClick = choosePicture) { Text(stringResource(R.string.words_choose_picture), color = Color.White) }
            Forms.Kind.Check -> {
                val on = Forms.checked(pr)
                TextButton(onClick = { fill(Forms.checkedTo(pr, !on), Forms.box(pr, !on)) }) { Text(stringResource(if (on) R.string.words_untick else R.string.words_tick), color = Color.White, maxLines = 1) }
            }
        }
    }
    if (typing) {
        var value by remember { mutableStateOf(if ("w:showingPlcHdr" in pr) "" else text) }
        AlertDialog(onDismissRequest = { typing = false },
            title = { Text(Forms.name(pr) ?: stringResource(R.string.words_text_field)) },
            text = { OutlinedTextField(value, { value = it }, singleLine = tagOf(pr), modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(enabled = value.isNotEmpty(), onClick = { fill(pr, value); typing = false }) { Text(stringResource(R.string.common_ok)) } },
            dismissButton = { TextButton(onClick = { typing = false }) { Text(stringResource(R.string.common_cancel)) } })
    }
}

/** A plain-text control says whether it may hold more than one line (w:multiLine). */
private fun tagOf(pr: String) = !Regex("""<w:text\b[^>]*w:multiLine="(1|true)"""").containsMatchIn(pr)

/** Insert › Content control (Word's Developer tab): the kind, its title, a list's choices, a date's format. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ContentControlDialog(dismiss: () -> Unit, insert: (Forms.New, String, List<String>, String) -> Unit) {
    var kind by remember { mutableStateOf(Forms.New.PlainText) }
    var title by remember { mutableStateOf("") }
    var items by remember { mutableStateOf("") }
    var format by remember { mutableStateOf("d/M/yyyy") }
    val list = kind == Forms.New.DropDown || kind == Forms.New.ComboBox
    AlertDialog(onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.words_content_control)) },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    Forms.New.entries.forEach { k -> Choice(stringResource(k.res), kind == k) { kind = k } }
                }
                OutlinedTextField(title, { title = it }, singleLine = true, label = { Text(stringResource(R.string.words_title_optional)) }, modifier = Modifier.fillMaxWidth())
                if (list) OutlinedTextField(items, { items = it }, label = { Text(stringResource(R.string.words_choices_per_line)) }, minLines = 3, modifier = Modifier.fillMaxWidth())
                if (kind == Forms.New.Date) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    listOf("d/M/yyyy", "dd/MM/yyyy", "d MMMM yyyy", "MMMM d, yyyy", "yyyy-MM-dd").forEach { f -> Choice(f, format == f) { format = f } }
                }
                Text(stringResource(R.string.words_cc_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = !list || items.isNotBlank(), onClick = { insert(kind, title, items.lines(), format) }) { Text(stringResource(R.string.words_insert)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } })
}
