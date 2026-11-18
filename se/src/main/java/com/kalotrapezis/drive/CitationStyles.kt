package com.kalotrapezis.drive

/**
 * References › Style: APA 7 (Writer's first), MLA 9 and Chicago author-date (17th) — each document's own, kept as Word
 * keeps it (its sources part's StyleName); in an OpenDocument told by its list's heading and its citations' shape.
 */
internal enum class CiteStyle(val label: String, val heading: String, val wordName: String, val wordXsl: String) {
    APA("APA 7", "References", "APA", "\\APASixthEditionOfficeOnline.xsl"),
    MLA("MLA 9", "Works Cited", "MLA", "\\MLASeventhEditionOfficeOnline.xsl"),
    Chicago("Chicago (author-date)", "References", "Chicago", "\\CHICAGO.XSL"),
    Harvard("Harvard", "Reference list", "Harvard - Anglia", "\\HarvardAnglia2008OfficeOnline.xsl"),
    /** Numbered in the order first cited: Word files only (LibreOffice's marks show their names). */
    IEEE("IEEE", "References", "IEEE", "\\IEEE2006OfficeOnline.xsl");

    /** [hide]: Word's citation options — 'n' no author, 'y' no year; [order]: the sources' tags as first cited (IEEE's numbers). */
    fun parenthetical(sources: List<DocSource>, page: String = "", hide: Set<Char> = emptySet(), order: List<String> = emptyList()): String = when (this) {
        APA -> Apa.parenthetical(sources, page, hide)
        MLA -> Mla.parenthetical(sources, page, hide)
        Chicago -> ChicagoAD.parenthetical(sources, page, hide)
        Harvard -> HarvardCtr.parenthetical(sources, page, hide)
        IEEE -> Ieee.parenthetical(sources, page, order)
    }
    fun entry(s: DocSource): List<DocRun> = when (this) { APA -> Apa.entry(s); MLA -> Mla.entry(s); Chicago -> ChicagoAD.entry(s); Harvard -> HarvardCtr.entry(s); IEEE -> Ieee.entry(s) }
    /** The list's order: by the first author's surname (or the title), then the year; IEEE's as first cited. */
    fun sorted(sources: List<DocSource>, order: List<String> = emptyList()) =
        if (this == IEEE) sources.sortedBy { s -> order.indexOf(s.tag).let { if (it < 0) Int.MAX_VALUE else it } } else Apa.sorted(sources)

    companion object {
        val HEADINGS = entries.map { it.heading }.toSet()
        /** A Word file's: its sources part's StyleName ("APA", "MLA", "Chicago"…); APA when it says another. */
        fun ofWord(styleName: String?) = entries.firstOrNull { styleName?.startsWith(it.wordName, ignoreCase = true) == true } ?: APA
        /**
         * An OpenDocument's, from what it shows: a list headed "Works Cited" is MLA's; a citation with a year after the
         * name without a comma, Chicago's ("(Smith 2020, 47)"); else APA's.
         */
        fun infer(doc: OfficeDoc): CiteStyle {
            if (doc.paras.any { it.text.trim() == MLA.heading }) return MLA
            if (doc.paras.any { it.text.trim() == Harvard.heading }) return Harvard
            val shown = doc.paras.asSequence().flatMap { p -> p.runs.asSequence().filter { it.cite != null && !isField(it.cite) }.map { it.text } }.firstOrNull() ?: return APA
            return when {
                Regex("""\p{L}, (\d{4}|n\.d\.)""").containsMatchIn(shown) -> APA
                Regex("""\p{L} (\d{4}|n\.d\.)""").containsMatchIn(shown) -> Chicago
                else -> MLA
            }
        }
    }
}

/** The pieces the two styles share: names as the list writes them, an ending's full stop, pages with an en dash. */
private object Cite {
    fun full(p: DocSource.Person) = if (p.first.isBlank()) p.last else "${p.first} ${p.last}"
    fun inverted(p: DocSource.Person) = if (p.first.isBlank()) p.last else "${p.last}, ${p.first}"
    fun end(s: String) = if (s.endsWith('.') || s.endsWith('?') || s.endsWith('!')) s else "$s."
    fun pages(p: String) = p.replace('-', '–')
    fun link(s: DocSource) = s.doi.trim().let { if (it.isEmpty()) "" else if (it.startsWith("http")) it else "https://doi.org/$it" }
    fun year(s: DocSource) = s.year.ifBlank { "n.d." }
    /** A title in quotation marks, its full stop inside them (American style, as MLA and Chicago write it). */
    fun quoted(t: String, mark: String = ".") = "“" + (if (t.endsWith('?') || t.endsWith('!')) t else t + mark) + "”"
    fun runs(build: (plain: (String) -> Unit, italic: (String) -> Unit) -> Unit): List<DocRun> {
        val out = ArrayList<DocRun>()
        build({ if (it.isNotEmpty()) out += DocRun(it) }, { if (it.isNotEmpty()) out += DocRun(it, italic = true) })
        return OfficeFormats.tidy(out.mapIndexed { i, r -> if (i == out.lastIndex) r.copy(text = r.text.trimEnd()) else r })
    }
}

/** MLA 9: (Smith 47), the Works Cited list. */
internal object Mla {
    fun citeNames(s: DocSource): String = when (s.authors.size) {
        0 -> "“" + s.title.split(' ').take(4).joinToString(" ") + "”"
        1 -> s.authors[0].last
        2 -> "${s.authors[0].last} and ${s.authors[1].last}"
        else -> "${s.authors[0].last} et al."
    }
    fun parenthetical(sources: List<DocSource>, page: String = "", hide: Set<Char> = emptySet()): String =
        "(" + listOf(if ('n' in hide) "" else sources.joinToString("; ") { citeNames(it) }, if (page.isNotBlank() && sources.size == 1) Cite.pages(page) else "").filter { it.isNotEmpty() }.joinToString(" ") + ")"
    /** Smith, John. / Smith, John, and Ann Jones. / Smith, John, et al. */
    fun authors(people: List<DocSource.Person>) = when (people.size) {
        0 -> ""
        1 -> Cite.inverted(people[0])
        2 -> "${Cite.inverted(people[0])}, and ${Cite.full(people[1])}"
        else -> "${Cite.inverted(people[0])}, et al."
    }
    fun entry(s: DocSource): List<DocRun> = Cite.runs { plain, italic ->
        val who = authors(s.authors)
        if (who.isNotEmpty()) plain(Cite.end(who) + " ")
        val link = Cite.link(s).removePrefix("https://").removePrefix("http://") // MLA leaves out "https://"
        val tail = ArrayList<String>()
        when (s.type) {
            DocSource.Type.Book, DocSource.Type.Report -> {
                italic(s.title); plain(". ")
                if (s.edition.isNotBlank()) tail += "${s.edition} ed."
                tail += listOf(s.publisher, s.year).filter { it.isNotBlank() }
            }
            DocSource.Type.Thesis -> { italic(s.title); plain(". "); tail += listOf(s.year, s.publisher, s.container.ifBlank { "Thesis" }).filter { it.isNotBlank() } }
            else -> {
                plain(Cite.quoted(s.title) + " ")
                if (s.container.isNotBlank()) { italic(s.container); plain(", ") }
                if (s.type == DocSource.Type.Chapter && s.editors.isNotEmpty()) tail += "edited by " + s.editors.joinToString(" and ") { Cite.full(it) }
                if (s.volume.isNotBlank()) tail += "vol. ${s.volume}"
                if (s.issue.isNotBlank()) tail += "no. ${s.issue}"
                if (s.type == DocSource.Type.Chapter && s.publisher.isNotBlank()) tail += s.publisher
                if (s.year.isNotBlank()) tail += s.year
                if (s.pages.isNotBlank()) tail += (if ('-' in s.pages || '–' in s.pages) "pp. " else "p. ") + Cite.pages(s.pages)
            }
        }
        if (link.isNotEmpty()) tail += link
        plain(Cite.end(tail.joinToString(", ")))
    }
}

/** Chicago author-date (17th): (Smith 2020, 47), the reference list. */
internal object ChicagoAD {
    fun citeNames(s: DocSource): String = when (s.authors.size) {
        0 -> "“" + s.title.split(' ').take(4).joinToString(" ") + "”"
        1 -> s.authors[0].last
        2 -> "${s.authors[0].last} and ${s.authors[1].last}"
        3 -> "${s.authors[0].last}, ${s.authors[1].last}, and ${s.authors[2].last}"
        else -> "${s.authors[0].last} et al."
    }
    fun parenthetical(sources: List<DocSource>, page: String = "", hide: Set<Char> = emptySet()): String {
        val each = sources.joinToString("; ") { listOfNotNull(citeNames(it).takeIf { 'n' !in hide }, Cite.year(it).takeIf { 'y' !in hide }).joinToString(" ") }
        return "(" + listOf(each, if (page.isNotBlank() && sources.size == 1) Cite.pages(page) else "").filter { it.isNotEmpty() }.joinToString(", ") + ")"
    }
    /** Smith, John, Ann Jones, and Bo Lee — up to ten; more: the first seven, then "et al." */
    fun authors(people: List<DocSource.Person>): String {
        val names = (if (people.size > 10) people.take(7) else people).mapIndexed { i, p -> if (i == 0) Cite.inverted(p) else Cite.full(p) }
        return when {
            names.isEmpty() -> ""
            people.size > 10 -> names.joinToString(", ") + ", et al."
            names.size == 1 -> names[0]
            names.size == 2 -> "${names[0]}, and ${names[1]}"
            else -> names.dropLast(1).joinToString(", ") + ", and " + names.last()
        }
    }
    fun entry(s: DocSource): List<DocRun> = Cite.runs { plain, italic ->
        val who = authors(s.authors)
        plain(if (who.isNotEmpty()) "${Cite.end(who)} ${Cite.year(s)}. " else "")
        val link = Cite.link(s)
        when (s.type) {
            DocSource.Type.Book, DocSource.Type.Report -> {
                italic(s.title); plain(". ")
                if (who.isEmpty()) plain("${Cite.year(s)}. ")
                if (s.edition.isNotBlank()) plain("${s.edition} ed. ")
                if (s.publisher.isNotBlank()) plain(Cite.end(s.publisher) + " ")
            }
            DocSource.Type.Article -> {
                plain(Cite.quoted(s.title) + " ")
                if (who.isEmpty()) plain("${Cite.year(s)}. ")
                italic(s.container)
                if (s.volume.isNotBlank()) plain(" ${s.volume}")
                if (s.issue.isNotBlank()) plain(" (${s.issue})")
                plain(if (s.pages.isNotBlank()) ": ${Cite.pages(s.pages)}. " else ". ")
            }
            DocSource.Type.Chapter -> {
                plain(Cite.quoted(s.title) + " In ")
                italic(s.container)
                if (s.editors.isNotEmpty()) plain(", edited by " + s.editors.joinToString(" and ") { Cite.full(it) })
                if (s.pages.isNotBlank()) plain(", ${Cite.pages(s.pages)}")
                plain(". ")
                if (s.publisher.isNotBlank()) plain(Cite.end(s.publisher) + " ")
            }
            DocSource.Type.Thesis -> {
                plain(Cite.quoted(s.title) + " ")
                plain(Cite.end(listOf(s.container.ifBlank { "PhD diss." }, s.publisher).filter { it.isNotBlank() }.joinToString(", ")) + " ")
            }
            DocSource.Type.Web, DocSource.Type.Video -> {
                plain(Cite.quoted(s.title) + " ")
                if (s.container.isNotBlank()) plain(Cite.end(s.container) + " ")
                if (s.type == DocSource.Type.Video) plain("Video. ")
            }
        }
        plain(link)
    }
}

/** Harvard (Cite Them Right, the UK universities' usual): (Smith and Jones, 2020, p. 12), the reference list. */
internal object HarvardCtr {
    fun citeNames(s: DocSource): String = when (s.authors.size) {
        0 -> s.title.split(' ').take(4).joinToString(" ").ifBlank { "Anon." }
        1 -> s.authors[0].last
        2 -> "${s.authors[0].last} and ${s.authors[1].last}"
        3 -> "${s.authors[0].last}, ${s.authors[1].last} and ${s.authors[2].last}"
        else -> "${s.authors[0].last} et al."
    }
    fun parenthetical(sources: List<DocSource>, page: String = "", hide: Set<Char> = emptySet()): String {
        val each = sources.joinToString("; ") { listOfNotNull(citeNames(it).takeIf { 'n' !in hide }, Cite.year(it).takeIf { 'y' !in hide }).joinToString(", ") }
        val p = if (page.isNotBlank() && sources.size == 1) (if ('-' in page || '–' in page) "pp. " else "p. ") + Cite.pages(page) else ""
        return "(" + listOf(each, p).filter { it.isNotEmpty() }.joinToString(", ") + ")"
    }
    /** Smith, J.R., Jones, A. and Brown, K. */
    fun authors(people: List<DocSource.Person>): String {
        val names = people.map { p -> if (p.first.isBlank()) p.last else "${p.last}, ${p.initials.replace(" ", "")}" }
        return if (names.size <= 1) names.firstOrNull().orEmpty() else names.dropLast(1).joinToString(", ") + " and " + names.last()
    }
    fun entry(s: DocSource): List<DocRun> = Cite.runs { plain, italic ->
        val who = authors(s.authors)
        plain("${who.ifEmpty { s.title }} (${Cite.year(s)}) ")
        val link = Cite.link(s)
        val title = if (who.isEmpty()) "" else s.title
        when (s.type) {
            DocSource.Type.Book, DocSource.Type.Report, DocSource.Type.Thesis -> {
                italic(title); plain(if (title.isEmpty()) "" else ". ")
                if (s.edition.isNotBlank()) plain("${s.edition} edn. ")
                if (s.type == DocSource.Type.Thesis) plain(Cite.end(s.container.ifBlank { "PhD thesis" }) + " ")
                if (s.publisher.isNotBlank()) plain(Cite.end(s.publisher) + " ")
            }
            DocSource.Type.Article -> {
                plain("‘$title’, "); italic(s.container)
                if (s.volume.isNotBlank()) plain(", ${s.volume}" + (if (s.issue.isNotBlank()) "(${s.issue})" else ""))
                plain(if (s.pages.isNotBlank()) ", pp. ${Cite.pages(s.pages)}. " else ". ")
            }
            DocSource.Type.Chapter -> {
                plain("‘$title’, in ")
                if (s.editors.isNotEmpty()) plain(authors(s.editors) + (if (s.editors.size > 1) " (eds) " else " (ed.) "))
                italic(s.container); plain(". ")
                if (s.publisher.isNotBlank()) plain(s.publisher + (if (s.pages.isNotBlank()) ", pp. ${Cite.pages(s.pages)}. " else ". "))
            }
            DocSource.Type.Web, DocSource.Type.Video -> {
                italic(title); plain(if (title.isEmpty()) "" else ". ")
                if (s.type == DocSource.Type.Video) plain("[Video]. ")
                if (s.container.isNotBlank()) plain(Cite.end(s.container) + " ")
            }
        }
        if (link.isNotEmpty()) plain("Available at: $link")
    }
}

/** IEEE: [1], [1, p. 12], [1], [3]; the list numbered as first cited. */
internal object Ieee {
    fun parenthetical(sources: List<DocSource>, page: String = "", order: List<String> = emptyList()): String {
        fun n(s: DocSource) = (order.indexOf(s.tag) + 1).takeIf { it > 0 }?.toString() ?: "?"
        if (sources.size == 1 && page.isNotBlank()) return "[${n(sources[0])}, ${if ('-' in page || '–' in page) "pp." else "p."} ${Cite.pages(page)}]"
        return sources.joinToString(", ") { "[${n(it)}]" }
    }
    /** J. R. Smith, A. Jones, and K. Brown; more than six: the first, et al. */
    fun authors(people: List<DocSource.Person>): String {
        val names = people.map { p -> if (p.first.isBlank()) p.last else "${p.initials} ${p.last}" }
        return when {
            names.isEmpty() -> ""
            names.size > 6 -> "${names[0]} et al."
            names.size == 1 -> names[0]
            names.size == 2 -> "${names[0]} and ${names[1]}"
            else -> names.dropLast(1).joinToString(", ") + ", and " + names.last()
        }
    }
    fun entry(s: DocSource): List<DocRun> = Cite.runs { plain, italic ->
        val who = authors(s.authors)
        if (who.isNotEmpty()) plain("$who, ")
        val link = Cite.link(s)
        when (s.type) {
            DocSource.Type.Book, DocSource.Type.Report -> {
                italic(s.title); plain(if (s.edition.isNotBlank()) ", ${s.edition} ed. " else ". ")
                plain(Cite.end(listOf(s.publisher, s.year).filter { it.isNotBlank() }.joinToString(", ")) + " ")
            }
            DocSource.Type.Article -> {
                plain("“${s.title},” "); italic(s.container)
                val tail = listOfNotNull(s.volume.takeIf { it.isNotBlank() }?.let { "vol. $it" }, s.issue.takeIf { it.isNotBlank() }?.let { "no. $it" },
                    s.pages.takeIf { it.isNotBlank() }?.let { "pp. ${Cite.pages(it)}" }, s.year.takeIf { it.isNotBlank() })
                plain((if (tail.isEmpty()) "" else ", " + tail.joinToString(", ")) + (if (link.isNotEmpty()) ", doi: ${s.doi.removePrefix("https://doi.org/")}." else ". "))
                return@runs
            }
            DocSource.Type.Chapter -> {
                plain("“${s.title},” in "); italic(s.container)
                if (s.editors.isNotEmpty()) plain(", ${authors(s.editors)}, Ed${if (s.editors.size > 1) "s" else ""}.")
                plain(" " + Cite.end(listOf(s.publisher, s.year, s.pages.takeIf { it.isNotBlank() }?.let { "pp. ${Cite.pages(it)}" } ?: "").filter { it.isNotBlank() }.joinToString(", ")) + " ")
            }
            DocSource.Type.Thesis -> plain("“${s.title},” " + Cite.end(listOf(s.container.ifBlank { "Ph.D. dissertation" }, s.publisher, s.year).filter { it.isNotBlank() }.joinToString(", ")) + " ")
            DocSource.Type.Web, DocSource.Type.Video -> {
                plain("“${s.title}.” ")
                if (s.container.isNotBlank()) italic(s.container + ". ")
                plain("[Online]. ")
            }
        }
        if (link.isNotEmpty()) plain("Available: $link")
    }
}
