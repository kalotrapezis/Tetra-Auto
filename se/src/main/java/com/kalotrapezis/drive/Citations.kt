package com.kalotrapezis.drive

/**
 * References › Citations and the reference list (asked 2026-09-27: "bibliography / citations, APA etc."; APA 7 first).
 * A source is what a citation points at; its [tag] is Word's (Smi20) and LibreOffice's identifier.
 */
internal data class DocSource(
    val tag: String,
    val type: Type = Type.Book,
    val authors: List<Person> = emptyList(), // in order; an organisation as a person with only a last name
    val year: String = "", // "" → n.d.
    val title: String = "",
    val container: String = "", // the journal, the website, the book a chapter is in
    val publisher: String = "",
    val volume: String = "",
    val issue: String = "",
    val pages: String = "", // "12–19"
    val doi: String = "", // or a URL
    val edition: String = "",
    val editors: List<Person> = emptyList(), // a chapter's book's editors
    val wordType: String? = null, // Word's own source type when it is one Writer shows as another (Report as a book)
    val extra: String = "", // what Word's source has that Writer does not edit (its Guid, city, …), as the file had it
) {
    /** [label]: English (an ID); [res]: the shown words. */
    enum class Type(val label: String, @androidx.annotation.StringRes val res: Int) { Book("Book", R.string.words_source_book), Article("Journal article", R.string.words_source_article),
        Web("Web page", R.string.words_web_page), Chapter("Book chapter", R.string.words_source_chapter), Report("Report", R.string.words_source_report),
        Thesis("Thesis", R.string.words_source_thesis), Video("Video", R.string.words_video) }
    data class Person(val last: String, val first: String = "") {
        /** "Κ. Μ." / "J. R." from the given names, as APA writes them. */
        val initials get() = first.split(' ', '-').filter { it.isNotBlank() }.joinToString(" ") { it.take(1).uppercase() + "." }
    }
}

/** APA 7: in-text citations and reference-list entries. Italics come back as runs. */
internal object Apa {
    private fun yearOf(s: DocSource) = s.year.ifBlank { "n.d." }

    /** The names in a citation: one, two with "&", three or more "et al." (APA 7); an organisation by its name. */
    fun citeNames(s: DocSource): String = when (s.authors.size) {
        0 -> s.title.split(' ').take(4).joinToString(" ").ifBlank { "Anonymous" }
        1 -> s.authors[0].last
        2 -> "${s.authors[0].last} & ${s.authors[1].last}"
        else -> "${s.authors[0].last} et al."
    }

    /** Parenthetical: (Smith, 2020), (Smith & Jones, 2020; Brown et al., 2019), with pages: (Smith, 2020, p. 12). */
    fun parenthetical(sources: List<DocSource>, page: String = "", hide: Set<Char> = emptySet()): String {
        val each = sources.joinToString("; ") { listOfNotNull(citeNames(it).takeIf { 'n' !in hide }, yearOf(it).takeIf { 'y' !in hide }).joinToString(", ") }
        return "(" + listOf(each, if (page.isNotBlank() && sources.size == 1) pageLabel(page) else "").filter { it.isNotEmpty() }.joinToString(", ") + ")"
    }
    /** Narrative: Smith (2020). */
    fun narrative(s: DocSource, page: String = "") = "${citeNames(s)} (${yearOf(s)}${if (page.isNotBlank()) ", ${pageLabel(page)}" else ""})"
    private fun pageLabel(p: String) = if (p.contains('-') || p.contains('–')) "pp. ${p.replace('-', '–')}" else "p. $p"

    /** Authors as the reference list writes them: Smith, J. R., Jones, A., & Brown, K. (up to 20; then the first 19, …, the last). */
    fun authorList(people: List<DocSource.Person>): String {
        fun one(p: DocSource.Person) = if (p.first.isBlank()) p.last else "${p.last}, ${p.initials}"
        val names = people.map(::one)
        return when {
            names.isEmpty() -> ""
            names.size == 1 -> names[0]
            names.size == 2 -> "${names[0]}, & ${names[1]}"
            names.size <= 20 -> names.dropLast(1).joinToString(", ") + ", & " + names.last()
            else -> names.take(19).joinToString(", ") + ", . . . " + names.last()
        }
    }
    private fun end(s: String) = if (s.endsWith('.') || s.endsWith('?') || s.endsWith('!')) s else "$s."

    /** One reference-list entry, as runs (italic where APA italicises). */
    fun entry(s: DocSource): List<DocRun> {
        val out = ArrayList<DocRun>()
        fun plain(t: String) { if (t.isNotEmpty()) out += DocRun(t) }
        fun italic(t: String) { if (t.isNotEmpty()) out += DocRun(t, italic = true) }
        val who = authorList(s.authors)
        val date = "(${yearOf(s)})."
        // With no author, the title takes the author's place (APA 7).
        if (who.isNotEmpty()) plain("${end(who)} $date ") else if (s.type == DocSource.Type.Article || s.type == DocSource.Type.Chapter) plain("${end(s.title)} $date ")
        val link = s.doi.trim().let { if (it.isEmpty()) "" else if (it.startsWith("http")) it else "https://doi.org/$it" }
        when (s.type) {
            DocSource.Type.Book -> {
                if (who.isNotEmpty()) { italic(s.title); if (s.edition.isNotBlank()) plain(" (${s.edition} ed.)"); plain(". ") }
                else { italic(s.title); if (s.edition.isNotBlank()) plain(" (${s.edition} ed.)"); plain(". $date ") }
                if (s.publisher.isNotBlank()) plain(end(s.publisher) + (if (link.isNotEmpty()) " " else ""))
            }
            DocSource.Type.Article -> {
                if (who.isNotEmpty()) plain(end(s.title) + " ")
                italic(s.container.ifBlank { "" }.let { if (s.volume.isNotBlank()) "$it, ${s.volume}" else it })
                if (s.issue.isNotBlank()) plain("(${s.issue})")
                if (s.pages.isNotBlank()) plain(", ${s.pages.replace('-', '–')}")
                plain(if (link.isNotEmpty()) ". " else ".")
            }
            DocSource.Type.Chapter -> {
                if (who.isNotEmpty()) plain(end(s.title) + " ")
                val eds = s.editors.map { if (it.first.isBlank()) it.last else "${it.initials} ${it.last}" }
                plain("In ")
                if (eds.isNotEmpty()) plain(when (eds.size) { 1 -> eds[0]; 2 -> "${eds[0]} & ${eds[1]}"; else -> eds.dropLast(1).joinToString(", ") + ", & " + eds.last() } + " (Ed${if (eds.size > 1) "s" else ""}.), ")
                italic(s.container)
                if (s.pages.isNotBlank()) plain(" (pp. ${s.pages.replace('-', '–')})")
                plain(". ")
                if (s.publisher.isNotBlank()) plain(end(s.publisher) + (if (link.isNotEmpty()) " " else ""))
            }
            // Report: *Title* (Report No. 12). Publisher. — Thesis: *Title* [Master's thesis, University]. — Video: *Title* [Video]. YouTube.
            DocSource.Type.Report, DocSource.Type.Thesis, DocSource.Type.Video -> {
                italic(s.title)
                when (s.type) {
                    DocSource.Type.Report -> if (s.container.isNotBlank()) plain(" (${s.container})")
                    DocSource.Type.Thesis -> plain(" [" + listOf(s.container.ifBlank { "Thesis" }, s.publisher).filter { it.isNotBlank() }.joinToString(", ") + "]")
                    else -> plain(" [Video]")
                }
                plain(if (who.isNotEmpty()) ". " else ". $date ")
                val by = if (s.type == DocSource.Type.Video) s.container else if (s.type == DocSource.Type.Report) s.publisher else ""
                if (by.isNotBlank() && by != s.authors.firstOrNull()?.last) plain(end(by) + " ")
            }
            DocSource.Type.Web -> {
                if (who.isNotEmpty()) { italic(s.title); plain(". ") } else { italic(s.title); plain(". $date ") }
                if (s.container.isNotBlank() && s.container != s.authors.firstOrNull()?.last) plain(end(s.container) + " ")
            }
        }
        plain(link)
        return OfficeFormats.tidy(out.mapIndexed { i, r -> if (i == out.lastIndex) r.copy(text = r.text.trimEnd()) else r })
    }

    /** The reference list's order: by the first author's surname (or the title), then the year. */
    fun sorted(sources: List<DocSource>) = sources.sortedWith(compareBy({ (it.authors.firstOrNull()?.last ?: it.title).lowercase() }, { it.year }))

    /** A tag no other source has: Smi20, Smi201, … (Word's way). */
    fun newTag(s: DocSource, taken: Collection<String>): String {
        val base = (s.authors.firstOrNull()?.last ?: s.title).filter { it.isLetter() }.take(3).ifEmpty { "Src" } + s.year.takeLast(2)
        return generateSequence(0) { it + 1 }.map { if (it == 0) base else "$base$it" }.first { it !in taken }
    }
}


/**
 * Word's sources (its Source Manager's): the bibliography custom XML part of the package — each b:Source by its tag,
 * authors and editors as name lists (an organisation as Corporate).
 */
internal object WordSources {
    private const val B = "http://schemas.openxmlformats.org/officeDocument/2006/bibliography"
    private const val REL_CUSTOM = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/customXml"

    fun path(parts: Map<String, ByteArray>): String? = parts.keys.filter { Regex("""customXml/item\d+\.xml""").matches(it) }
        .firstOrNull { String(parts.getValue(it), Charsets.UTF_8).let { x -> B in x && "Sources" in x } }

    private fun typeOf(w: String) = when (w) {
        "JournalArticle", "ArticleInAPeriodical" -> DocSource.Type.Article
        "InternetSite", "DocumentFromInternetSite" -> DocSource.Type.Web
        "BookSection" -> DocSource.Type.Chapter
        "Report" -> DocSource.Type.Report
        "Film" -> DocSource.Type.Video
        else -> DocSource.Type.Book
    }
    private fun wordOf(t: DocSource.Type) = when (t) {
        DocSource.Type.Book -> "Book"; DocSource.Type.Article -> "JournalArticle"; DocSource.Type.Web -> "InternetSite"; DocSource.Type.Chapter -> "BookSection"
        DocSource.Type.Report, DocSource.Type.Thesis -> "Report"; DocSource.Type.Video -> "Film"
    }
    /** Where each kind keeps what it is in: a journal's name, a web site's, a chapter's book's title. */
    private fun containerTag(t: DocSource.Type) = when (t) { DocSource.Type.Article -> "JournalName"; DocSource.Type.Web -> "InternetSiteTitle"; DocSource.Type.Chapter -> "BookTitle"
        // A report's number and a thesis's kind are Word's report type; a video's platform its distributor.
        DocSource.Type.Report, DocSource.Type.Thesis -> "ThesisType"; DocSource.Type.Video -> "Distributor"; else -> null }

    fun read(parts: Map<String, ByteArray>): List<DocSource> {
        val xml = path(parts)?.let { parts[it] } ?: return emptyList()
        val root = runCatching { OfficeFormats.parse(xml).documentElement }.getOrNull() ?: return emptyList()
        fun kids(e: org.w3c.dom.Element) = (0 until e.childNodes.length).mapNotNull { e.childNodes.item(it) as? org.w3c.dom.Element }
        fun kid(e: org.w3c.dom.Element?, name: String) = e?.let { kids(it).firstOrNull { k -> k.localName == name } }
        fun people(e: org.w3c.dom.Element?): List<DocSource.Person> {
            e ?: return emptyList()
            kid(e, "Corporate")?.let { return listOf(DocSource.Person(it.textContent.trim())) }
            return kids(kid(e, "NameList") ?: return emptyList()).filter { it.localName == "Person" }.map { p ->
                DocSource.Person(kid(p, "Last")?.textContent?.trim().orEmpty(), listOfNotNull(kid(p, "First")?.textContent, kid(p, "Middle")?.textContent).joinToString(" ").trim())
            }
        }
        return kids(root).filter { it.localName == "Source" }.mapNotNull { src ->
            fun v(name: String) = kid(src, name)?.textContent?.trim().orEmpty()
            val tag = v("Tag").ifBlank { return@mapNotNull null }
            val word = v("SourceType")
            // Word keeps a thesis as a report whose type says so.
            val type = typeOf(word).let { if (it == DocSource.Type.Report && THESIS.containsMatchIn(v("ThesisType"))) DocSource.Type.Thesis else it }
            val known = setOf("Tag", "SourceType", "Author", "Title", "Year", "Publisher", "JournalName", "InternetSiteTitle", "BookTitle", "ThesisType", "Distributor", "Volume", "Issue", "Pages", "DOI", "URL", "Edition")
            val author = kid(src, "Author")
            DocSource(tag, type, people(kid(author, "Author")), v("Year"), v("Title"), containerTag(type)?.let { v(it) }.orEmpty(), v("Publisher"), v("Volume"), v("Issue"),
                v("Pages"), v("DOI").ifBlank { v("URL") }, v("Edition"), people(kid(author, "Editor")),
                wordType = word.takeIf { it.isNotBlank() && it != wordOf(type) },
                extra = kids(src).filter { it.localName !in known }.joinToString("") { OfficeFormats.xml(it) } +
                    (author?.let { a -> kids(a).filter { it.localName != "Author" && it.localName != "Editor" }.joinToString("") { OfficeFormats.xml(it) } }?.takeIf { it.isNotEmpty() }?.let { "<b:Author>$it</b:Author>" } ?: ""))
        }
    }

    private val THESIS = Regex("thesis|dissertation|διατριβή|διπλωματική|εργασία", RegexOption.IGNORE_CASE)
    private fun esc(s: String) = OfficeFormats.esc(s)
    private fun names(tag: String, people: List<DocSource.Person>) = if (people.isEmpty()) "" else if (people.size == 1 && people[0].first.isBlank())
        "<b:$tag><b:Corporate>${esc(people[0].last)}</b:Corporate></b:$tag>"
        else "<b:$tag><b:NameList>" + people.joinToString("") { p -> "<b:Person><b:Last>${esc(p.last)}</b:Last>${if (p.first.isNotBlank()) "<b:First>${esc(p.first)}</b:First>" else ""}</b:Person>" } + "</b:NameList></b:$tag>"

    fun source(s: DocSource): String {
        fun el(name: String, v: String) = if (v.isBlank()) "" else "<b:$name>${esc(v)}</b:$name>"
        val guid = if ("<b:Guid>" in s.extra || "Guid>" in s.extra) "" else "<b:Guid>{${java.util.UUID.randomUUID().toString().uppercase()}}</b:Guid>"
        val link = s.doi.trim().let { d -> if (d.startsWith("http")) el("URL", d) else el("DOI", d) }
        // An author list of Word's own besides (e.g. a translator) stays with it; the authors and editors are Writer's.
        val otherRoles = Regex("""^<b:Author>([\s\S]*)</b:Author>$""").find(Regex("""<b:Author>[\s\S]*</b:Author>""").find(s.extra)?.value ?: "")?.groupValues?.get(1) ?: ""
        val rest = s.extra.replace(Regex("""<b:Author>[\s\S]*</b:Author>"""), "")
        return "<b:Source>${el("Tag", s.tag)}${el("SourceType", s.wordType?.takeIf { typeOf(it) == s.type } ?: wordOf(s.type))}$guid" +
            "<b:Author>${names("Author", s.authors)}${names("Editor", s.editors)}$otherRoles</b:Author>" +
            el("Title", s.title) + el("Year", s.year) + (containerTag(s.type)?.let { el(it, s.container) } ?: "") + el("Publisher", s.publisher) +
            el("Volume", s.volume) + el("Issue", s.issue) + el("Pages", s.pages) + el("Edition", s.edition) + link + rest + "</b:Source>"
    }

    /** The style Word's citations are in (the part's StyleName: "APA", "MLA", "Chicago"…). */
    fun styleName(parts: Map<String, ByteArray>): String? = path(parts)?.let { String(parts.getValue(it), Charsets.UTF_8) }
        ?.let { Regex("""<b:Sources\b[^>]*\bStyleName="([^"]*)"""").find(it)?.groupValues?.get(1) }

    /** [sources] into the package: the part rewritten (its other settings kept, its style [style]), or made with its properties and links. */
    fun write(parts: MutableMap<String, ByteArray>, sources: List<DocSource>, style: CiteStyle = CiteStyle.APA) {
        val at = path(parts)
        if (at == null && sources.isEmpty()) return
        val old = at?.let { String(parts.getValue(it), Charsets.UTF_8) }
        // Word's own name for the style it is in, unless the file already says one Writer reads as the same.
        val head = (old?.let { Regex("""<b:Sources\b[^>]*>""").find(it)?.value }
            ?: """<b:Sources xmlns:b="$B" xmlns="$B" SelectedStyle="${style.wordXsl}" StyleName="${style.wordName}" Version="6">""")
            .let { h -> if (CiteStyle.ofWord(Regex("""\bStyleName="([^"]*)"""").find(h)?.groupValues?.get(1)) == style) h
                else h.replace(Regex("""\s(StyleName|SelectedStyle|Version)="[^"]*""""), "").removeSuffix(">") + """ SelectedStyle="${style.wordXsl}" StyleName="${style.wordName}">""" }
        val xml = """<?xml version="1.0" standalone="no"?>""" + head + sources.joinToString("") { source(it) } + "</b:Sources>"
        if (at != null) { parts[at] = xml.toByteArray(); return }
        val n = generateSequence(1) { it + 1 }.first { "customXml/item$it.xml" !in parts }
        parts["customXml/item$n.xml"] = xml.toByteArray()
        parts["customXml/itemProps$n.xml"] = ("""<?xml version="1.0" encoding="UTF-8" standalone="no"?><ds:datastoreItem ds:itemID="{${java.util.UUID.randomUUID().toString().uppercase()}}" xmlns:ds="http://schemas.openxmlformats.org/officeDocument/2006/customXml"><ds:schemaRefs><ds:schemaRef ds:uri="$B"/></ds:schemaRefs></ds:datastoreItem>""").toByteArray()
        parts["customXml/_rels/item$n.xml.rels"] = ("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/customXmlProps" Target="itemProps$n.xml"/></Relationships>""").toByteArray()
        var types = String(parts.getValue("[Content_Types].xml"), Charsets.UTF_8)
        if (!Regex("""Extension="xml"""", RegexOption.IGNORE_CASE).containsMatchIn(types)) types = types.replace("</Types>", """<Default Extension="xml" ContentType="application/xml"/></Types>""")
        types = types.replace("</Types>", """<Override PartName="/customXml/itemProps$n.xml" ContentType="application/vnd.openxmlformats-officedocument.customXmlProperties+xml"/></Types>""")
        parts["[Content_Types].xml"] = types.toByteArray()
        val docPath = documentPath(parts)
        val relsPath = relsPath(docPath)
        val rels = parts[relsPath]?.let { String(it, Charsets.UTF_8) } ?: """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"></Relationships>"""
        val id = generateSequence(1) { it + 1 }.map { "rIdTetraSources$it" }.first { "\"$it\"" !in rels }
        val up = "../".repeat(docPath.count { it == '/' })
        parts[relsPath] = rels.replace("</Relationships>", """<Relationship Id="$id" Type="$REL_CUSTOM" Target="${up}customXml/item$n.xml"/></Relationships>""").toByteArray()
    }
}

/**
 * Citations in the text (References › Insert citation, asked 2026-09-27; APA 7): each is letters that carry
 * [DocRun.cite] — Word's CITATION field, or in an OpenDocument the source's tag (a bibliography mark holding the
 * source) — shown as APA's parenthetical citation. The reference list: plain paragraphs in the file's own bibliography
 * style (Word's Bibliography, LibreOffice's Bibliography 1), the heading "References" before them; Update rewrites
 * both from the sources.
 */
internal object Citations {
    fun entryStyle(kind: OfficeKind) = if (kind == OfficeKind.Docx) "Bibliography" else "Bibliography_20_1"
    const val HEADING = "References"

    /** A citation's sources' tags and its page: Word's "CITATION Smi20 \l 1033 \m Jon19 \p 12"; an ODT mark's tag. */
    fun parse(cite: String): Pair<List<String>, String> {
        if (!cite.startsWith("CITATION")) return listOf(cite) to ""
        val words = Regex("""\\\w|"[^"]*"|\S+""").findAll(cite.removePrefix("CITATION").trim()).map { it.value.trim('"') }.toList()
        val tags = ArrayList<String>(); var page = ""
        var k = 0
        if (words.isNotEmpty() && !words[0].startsWith("\\")) { tags += words[0]; k = 1 }
        while (k < words.size) {
            when (words[k]) { "\\m" -> words.getOrNull(k + 1)?.let { tags += it }; "\\p" -> page = words.getOrNull(k + 1).orEmpty() }
            k += if (words[k] in setOf("\\m", "\\p", "\\l", "\\s", "\\v", "\\f")) 2 else 1 // \n, \y, \t stand alone
        }
        return tags to page
    }
    /** Word's citation options: 'n' its author left out, 'y' its year (Edit Citation › Suppress). */
    fun hidden(cite: String): Set<Char> = if (!cite.startsWith("CITATION")) emptySet()
        else Regex("""\\([ny])(?=\s|$)""").findAll(cite).map { it.groupValues[1][0] }.toSet()
    /** The citation as each kind keeps it: Word's field instruction, or the one source's tag. */
    fun cite(kind: OfficeKind, tags: List<String>, page: String, hide: Set<Char> = emptySet()): String = if (kind == OfficeKind.Odt) tags.first()
        else "CITATION ${tags.first()} \\l 1033" + tags.drop(1).joinToString("") { " \\m $it" } + (if (page.isNotBlank()) " \\p $page" else "") + hide.sorted().joinToString("") { " \\$it" }

    /** What a citation shows: its [style]'s parenthetical, from its sources (a missing one by its tag). */
    fun text(cite: String, sources: List<DocSource>, style: CiteStyle = CiteStyle.APA, order: List<String> = emptyList()): String {
        val (tags, page) = parse(cite)
        val found = tags.mapNotNull { t -> sources.firstOrNull { it.tag == t } }
        return if (found.isEmpty()) "(${tags.joinToString("; ")})" else style.parenthetical(found, page, hidden(cite), order)
    }
    /** The sources' tags in the order they are first cited (IEEE's numbers). */
    fun order(doc: OfficeDoc): List<String> = doc.paras.flatMap { p -> p.runs.mapNotNull { it.cite?.takeIf { c -> !isField(c) } } }.flatMap { parse(it).first }.distinct()

    /** Where the reference list is (its heading, if it has one, and its entries), or null. */
    fun find(doc: OfficeDoc, kind: OfficeKind): IntRange? {
        val first = doc.paras.indexOfFirst { it.keep?.style == entryStyle(kind) }.takeIf { it >= 0 } ?: return null
        var last = first
        while (last + 1 < doc.paras.size && doc.paras[last + 1].keep?.style == entryStyle(kind)) last++
        val start = if (first > 0 && doc.paras[first - 1].text.trim() in CiteStyle.HEADINGS) first - 1 else first
        return start..last
    }

    /** The reference list's paragraphs: the heading (APA 7: bold, centred, the text's size), then the entries in APA's order. */
    fun paragraphs(doc: OfficeDoc, kind: OfficeKind): List<DocPara> {
        val used = usedTags(doc)
        // The sources cited, in APA's order; with none cited yet, every source (a list made first, cited after).
        val list = doc.sources.filter { it.tag in used }.ifEmpty { doc.sources }
        // APA, MLA and Chicago alike: on a new page, its heading centred (APA's bold; MLA's and Chicago's plain).
        val heading = DocPara(listOf(DocRun(doc.citeStyle.heading, bold = doc.citeStyle == CiteStyle.APA)), align = DocAlign.Center, keep = OfficeFormats.withPageBreak(null, kind, true))
        val order = order(doc)
        val entries = doc.citeStyle.sorted(list, order).mapIndexed { i, s ->
            // IEEE: each entry its number first, as cited
            val runs = doc.citeStyle.entry(s).let { if (doc.citeStyle == CiteStyle.IEEE) listOf(DocRun("[${i + 1}]\t")) + it else it }
            DocPara(runs, keep = DocKeep(style = entryStyle(kind)))
        }
        return listOf(heading) + entries.ifEmpty { listOf(DocPara(listOf(DocRun("No sources yet — References › Insert citation adds them")), keep = DocKeep(style = entryStyle(kind)))) }
    }

    fun usedTags(doc: OfficeDoc): Set<String> = doc.paras.flatMap { p -> p.runs.mapNotNull { it.cite?.takeIf { c -> !isField(c) } } }.flatMap { parse(it).first }.toSet()

    /**
     * A new source's tag: Word's (Smi20); in an OpenDocument the citation itself ("Smith & Jones, 2019"; 2019a, 2019b
     * when two are alike), which is what LibreOffice shows for a mark.
     */
    fun tagFor(kind: OfficeKind, s: DocSource, taken: Collection<String>, style: CiteStyle = CiteStyle.APA): String {
        if (kind == OfficeKind.Docx) return Apa.newTag(s, taken)
        val base = when (style) {
            CiteStyle.APA -> "${Apa.citeNames(s)}, ${s.year.ifBlank { "n.d." }}"
            CiteStyle.MLA -> Mla.citeNames(s)
            CiteStyle.Chicago -> "${ChicagoAD.citeNames(s)} ${s.year.ifBlank { "n.d." }}"
            CiteStyle.Harvard -> "${HarvardCtr.citeNames(s)}, ${s.year.ifBlank { "n.d." }}"
            CiteStyle.IEEE -> "${Apa.citeNames(s)}, ${s.year.ifBlank { "n.d." }}" // IEEE is offered for Word files only
        }
        return (sequenceOf(base) + ('a'..'z').asSequence().map { "$base$it" }).first { it !in taken }
    }

    /** [doc] with every citation's text from its sources again, and its reference list (if it has one) rewritten. */
    fun update(doc0: OfficeDoc, kind: OfficeKind): OfficeDoc {
        // An OpenDocument's tags are the citations themselves: made again from the sources, the marks renamed with them.
        val doc = if (kind == OfficeKind.Docx) doc0 else {
            val taken = HashSet<String>()
            val renamed = doc0.sources.associate { s -> s.tag to tagFor(kind, s, taken, doc0.citeStyle).also { taken += it } }
            doc0.copy(sources = doc0.sources.map { it.copy(tag = renamed.getValue(it.tag)) },
                paras = doc0.paras.map { p -> if (p.runs.none { it.cite in renamed }) p else p.copy(runs = p.runs.map { r -> r.cite?.let { c -> renamed[c]?.let { r.copy(cite = it) } } ?: r }) })
        }
        val order = order(doc)
        val paras = doc.paras.map { p ->
            if (p.runs.none { it.cite != null }) p else {
                val out = ArrayList<DocRun>()
                var k = 0
                val runs = p.runs
                while (k < runs.size) {
                    val c = runs[k].cite
                    if (c == null || isField(c)) { out += runs[k]; k++; continue }
                    var e = k
                    while (e < runs.size && runs[e].cite == c) e++
                    out += runs[k].copy(text = text(c, doc.sources, doc.citeStyle, order))
                    k = e
                }
                p.copy(runs = out)
            }
        }
        val fresh = doc.copy(paras = paras)
        val at = find(fresh, kind) ?: return fresh
        return fresh.copy(paras = fresh.paras.subList(0, at.first) + paragraphs(fresh, kind) + fresh.paras.subList(at.last + 1, fresh.paras.size))
    }

    /** The reference list put at paragraph [at] (an empty paragraph there becomes it), or rewritten where it is. */
    fun insertList(doc: OfficeDoc, kind: OfficeKind, at: Int): OfficeDoc {
        find(doc, kind)?.let { return update(doc, kind) }
        val paras = doc.paras.toMutableList()
        var here = at.coerceIn(0, paras.size)
        // An empty paragraph at the cursor becomes the list; one with text keeps it, the list after it.
        if (here < paras.size && paras[here].text.isEmpty() && paras[here].table == null) paras.removeAt(here) else if (here < paras.size) here++
        paras.addAll(here, paragraphs(doc, kind))
        return doc.copy(paras = paras)
    }

    // --- OpenDocument: a source is kept in the bibliography marks that cite it ---
    private val ODT_TYPES = mapOf(DocSource.Type.Book to "book", DocSource.Type.Article to "article", DocSource.Type.Web to "www", DocSource.Type.Chapter to "incollection",
        DocSource.Type.Report to "techreport", DocSource.Type.Thesis to "phdthesis", DocSource.Type.Video to "misc")
    private fun names(people: List<DocSource.Person>) = people.joinToString("; ") { if (it.first.isBlank()) it.last else "${it.last}, ${it.first}" }
    private fun people(s: String) = s.split(';').map { it.trim() }.filter { it.isNotEmpty() }.map { n -> n.split(',', limit = 2).let { DocSource.Person(it[0].trim(), it.getOrElse(1) { "" }.trim()) } }

    /** The mark's attributes (text:…) for [s]. */
    fun odtAttributes(s: DocSource): String {
        fun a(name: String, v: String) = if (v.isBlank()) "" else " text:$name=\"${OfficeFormats.esc(v)}\""
        val container = when (s.type) { DocSource.Type.Article -> a("journal", s.container); DocSource.Type.Chapter -> a("booktitle", s.container); DocSource.Type.Web, DocSource.Type.Video -> a("note", s.container)
            DocSource.Type.Report, DocSource.Type.Thesis -> a("report-type", s.container); else -> "" }
        return a("identifier", s.tag) + " text:bibliography-type=\"${ODT_TYPES.getValue(s.type)}\"" + a("author", names(s.authors)) + a("title", s.title) + a("year", s.year) +
            container + a("publisher", s.publisher) + a("volume", s.volume) + a("number", s.issue) + a("pages", s.pages) + a("edition", s.edition) +
            a("editor", names(s.editors)) + a("url", s.doi.let { d -> if (d.isBlank() || d.startsWith("http")) d else "https://doi.org/$d" })
    }
    /** A source from a bibliography mark's attributes. */
    fun odtSource(attr: (String) -> String?): DocSource? {
        val tag = attr("identifier")?.takeIf { it.isNotBlank() } ?: return null
        val type = if (attr("bibliography-type") == "mastersthesis") DocSource.Type.Thesis else ODT_TYPES.entries.firstOrNull { it.value == attr("bibliography-type") }?.key ?: DocSource.Type.Book
        val container = when (type) { DocSource.Type.Article -> attr("journal"); DocSource.Type.Chapter -> attr("booktitle"); DocSource.Type.Web, DocSource.Type.Video -> attr("note")
            DocSource.Type.Report, DocSource.Type.Thesis -> attr("report-type"); else -> null }.orEmpty()
        return DocSource(tag, type, people(attr("author").orEmpty()), attr("year").orEmpty(), attr("title").orEmpty(), container, attr("publisher").orEmpty(),
            attr("volume").orEmpty(), attr("number").orEmpty(), attr("pages").orEmpty(), attr("url").orEmpty().removePrefix("https://doi.org/"), attr("edition").orEmpty(), people(attr("editor").orEmpty()))
    }
}
