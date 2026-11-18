package com.kalotrapezis.drive

import java.io.File
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/*
 * Tetra's Contacts (Plan.md § Contacts). One contact is one vCard 3.0 file (RFC 2426), so Claude, any app or a person can
 * read and change it:
 *   Tetra/.System/Contacts/Shared/<name>-<6 hex>.vcf    the file name means nothing; the UID inside is the contact
 * Read forgiving (2.1's QUOTED-PRINTABLE and CHARSET, 3.0, 4.0; Samsung's, Google's, Apple's and Android's own), written
 * strict (CRLF, folded at 75 octets). As the Calendar's files: a line Tetra does not understand is kept as it was, and an
 * edit changes only the lines of what changed. Files sync carries the folder as everything else; nothing here talks to sync.
 */

/** A birthday or another date; a year-less one (Google's "--10-23", Apple's 1604) has no [year]. */
data class VDate(val year: Int?, val month: Int, val day: Int) {
    /** vCard's form: "1985-03-14", or "--03-14" with no year (vCard 4's, which Google and Apple read). */
    fun value() = if (year != null) "%04d-%02d-%02d".format(year, month, day) else "--%02d-%02d".format(month, day)
    /** This date in [year] (29 Feb → 28 Feb in a common year). */
    fun inYear(y: Int): LocalDate = LocalDate.of(y, month, 1).let { it.withDayOfMonth(minOf(day, it.lengthOfMonth())) }

    companion object {
        private val FULL = Regex("""^(\d{4})-?(\d{2})-?(\d{2})""")
        private val NO_YEAR = Regex("""^--(\d{2})-?(\d{2})""")
        /** "1985-03-14", "19850314", "1985-03-14T00:00:00Z", "--03-14", "--0314"; Apple's 1604 (X-APPLE-OMIT-YEAR) is no year. */
        fun parse(s: String): VDate? {
            val t = s.trim()
            NO_YEAR.find(t)?.let { m -> return of(null, m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
            val m = FULL.find(t) ?: return null
            val y = m.groupValues[1].toInt()
            return of(y.takeIf { it != 1604 && it > 0 }, m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        private fun of(y: Int?, mo: Int, d: Int) = if (mo in 1..12 && d in 1..31) VDate(y, mo, d) else null
    }
}

/** A value with its label: a phone, an e-mail, a website, a related person, a date's name. */
data class VField(val label: String, val value: String)

/** An address's parts as vCard orders them: P.O. box, extended, street, city, region, postcode, country. */
data class VAddress(val label: String, val parts: List<String>) {
    val street get() = parts.getOrElse(2) { "" }
    val city get() = parts.getOrElse(3) { "" }
    val region get() = parts.getOrElse(4) { "" }
    val postcode get() = parts.getOrElse(5) { "" }
    val country get() = parts.getOrElse(6) { "" }
    /** "Οδός Παραδείγματος 3, 12345 Άγιος Λουκάς": as one line, for the page and Maps. */
    fun text() = listOf(listOf(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" }, street).filter { it.isNotBlank() }.joinToString(" "),
        listOf(postcode, city).filter { it.isNotBlank() }.joinToString(" "), region, country).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
}

/** What Tetra reads and edits of a contact; the rest of its lines stay in [Contact.lines]. */
data class ContactInfo(
    val uid: String = "",
    val prefix: String = "",
    val given: String = "",
    val middle: String = "",
    val family: String = "",
    val suffix: String = "",
    val formatted: String = "",
    val nickname: String = "",
    val org: String = "",
    val department: String = "",
    val title: String = "",
    val phones: List<VField> = emptyList(),
    /** The number starred as the default (TYPE=PREF on its TEL line, as Google and Android write it): Call and the apps go to it. */
    val defaultPhone: String? = null,
    val emails: List<VField> = emptyList(),
    val addresses: List<VAddress> = emptyList(),
    val urls: List<VField> = emptyList(),
    val birthday: VDate? = null,
    /** Anniversary and other dates: label and the date's value ("2010-06-12", "--06-12"). */
    val dates: List<VField> = emptyList(),
    val related: List<VField> = emptyList(),
    val note: String = "",
    /** CATEGORIES as written: Google's "myContacts" and "starred" among the labels. */
    val categories: List<String> = emptyList(),
    /** The photo, a JPEG in base64 as the file has it (a string, so two contacts compare by content). */
    val photo: String? = null,
    /** An imported photo given as a link (Google's googleusercontent ones). */
    val photoUrl: String? = null,
    /** The Photos person the photo came from (X-TETRA-PERSON): one link between a contact and a person, the same uuid everywhere. */
    val person: String? = null,
    /**
     * The face the portrait shows (X-TETRA-FACE): "<the photo's SHA-256>;left,top,right,bottom", fractions of the upright
     * photo. The computer finds that face in its copy of the photo and puts it in Photos under [person] (ContactsFaceLink).
     */
    val face: String? = null,
    val rev: String = "",
) {
    /** The name as written by the person: FN, else the parts, else the company, a phone, an e-mail. */
    val name: String get() = formatted.ifBlank { composed() }.ifBlank { org }.ifBlank { phones.firstOrNull()?.value ?: emails.firstOrNull()?.value ?: "" }
    fun composed() = listOf(prefix, given, middle, family, suffix).filter { it.isNotBlank() }.joinToString(" ")
    val favourite get() = categories.any { it.equals(STARRED, true) }
    /** The labels a person made (Google's own "myContacts" and the star are not labels). */
    val labels get() = categories.filterNot { it.equals(STARRED, true) || it.equals(MY_CONTACTS, true) || it.equals(STARRED_GROUP, true) || it.isBlank() }
    fun favourite(on: Boolean) = copy(categories = if (on) (categories.filterNot { it.equals(STARRED, true) } + STARRED) else categories.filterNot { it.equals(STARRED, true) })
    /** The second line of the page: "Teacher · 3rd Primary". */
    val job get() = listOf(title, listOf(org, department).filter { it.isNotBlank() }.joinToString(", ")).filter { it.isNotBlank() }.joinToString(" · ")

    companion object {
        const val STARRED = "starred"
        const val MY_CONTACTS = "myContacts"
        /** Android's own group for the star, which the phone's import brought in as a label (23 Oct): not a label. */
        const val STARRED_GROUP = "Starred in Android"
    }
}

/** A contact as stored: its file and its lines in the order read (BEGIN and END are not among them). */
class Contact(val file: File?, val lines: List<ICalProp>) {
    val info: ContactInfo by lazy { VCard.info(lines) }
    /** Copies of this contact in the folder (sync's "x (2).vcf" after a change on two devices); 1 when alone. */
    var versions = 1
}

object VCard {
    // -------- reading --------

    /** Every card in a .vcf, its lines in 3.0's form (2.1's quoted-printable decoded, CHARSET dropped, bare types as TYPE=). */
    fun cards(text: String): List<List<ICalProp>> {
        val out = mutableListOf<List<ICalProp>>()
        var cur: MutableList<ICalProp>? = null
        var depth = 0
        for (line in logicalLines(text)) {
            val colon = ICal.indexOutsideQuotes(line, ':')
            if (colon < 0) continue
            val head = line.substring(0, colon)
            val semi = head.indexOf(';').let { if (it < 0) head.length else it }
            val name = head.substring(0, semi)
            val value = line.substring(colon + 1)
            when {
                name.equals("BEGIN", true) && value.trim().equals("VCARD", true) -> { if (depth++ == 0) cur = mutableListOf() }
                name.equals("END", true) && value.trim().equals("VCARD", true) -> { if (--depth == 0) { cur?.let(out::add); cur = null }; depth = depth.coerceAtLeast(0) }
                depth == 1 -> cur?.add(normal(ICalProp(name, head.substring(semi), value)))
            }
        }
        return out
    }

    /** Unfolded lines: 2.1's quoted-printable soft breaks ("=" at the end) joined, then RFC folding (a leading space or tab). */
    private fun logicalLines(text: String): List<String> {
        val raw = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = mutableListOf<String>()
        var i = 0
        while (i < raw.size) {
            var l = raw[i++]
            if ((l.startsWith(" ") || l.startsWith("\t")) && out.isNotEmpty()) { out[out.size - 1] = out.last() + l.substring(1); continue }
            if (QP.containsMatchIn(l.substringBefore(':'))) while (l.endsWith("=") && i < raw.size) l = l.dropLast(1) + raw[i++]
            out.add(l)
        }
        return out.filter { it.isNotBlank() }
    }

    private val QP = Regex("ENCODING=QUOTED-PRINTABLE|;QUOTED-PRINTABLE", RegexOption.IGNORE_CASE)
    private val HEX = Regex("[0-9A-Fa-f]{2}")

    /** One line in 3.0's form. A line already in it comes back unchanged (the file's own lines are kept as they were). */
    fun normal(p: ICalProp): ICalProp {
        val key = p.name.substringAfter('.').uppercase()
        if (key == "VERSION") return ICalProp(p.name, "", "3.0")
        val params = ICal.splitOutsideQuotes(p.params, ';')
        var value = p.value
        val kept = mutableListOf<String>()
        val types = mutableListOf<String>()
        var changed = false
        var charset = "UTF-8"
        var qp = false
        for (raw in params) {
            val eq = raw.indexOf('=')
            val k = if (eq < 0) "" else raw.substring(0, eq).uppercase()
            val v = if (eq < 0) raw else raw.substring(eq + 1)
            when {
                eq < 0 && raw.uppercase() in setOf("BASE64", "B") -> { kept.add("ENCODING=b"); changed = true }
                eq < 0 && raw.uppercase() == "QUOTED-PRINTABLE" -> { qp = true; changed = true }
                eq < 0 -> { types.add(raw); changed = true } // 2.1's bare "CELL", "JPEG"
                k == "CHARSET" -> { charset = v.trim('"'); changed = true }
                k == "ENCODING" && v.equals("QUOTED-PRINTABLE", true) -> { qp = true; changed = true }
                k == "ENCODING" && v.equals("BASE64", true) -> { kept.add("ENCODING=b"); changed = true }
                else -> kept.add(raw)
            }
        }
        if (qp) value = quotedPrintable(value, charset)
        // vCard 4's photo as a data: link
        if (key == "PHOTO" && value.startsWith("data:", true) && value.contains(";base64,", true)) {
            val type = value.substringAfter('/').substringBefore(';').uppercase()
            return ICalProp(p.name, ";ENCODING=b;TYPE=$type", value.substringAfter(','))
        }
        if (!changed) return p
        if (types.isNotEmpty()) kept.add("TYPE=" + types.joinToString(","))
        return ICalProp(p.name, kept.joinToString("") { ";$it" }, value)
    }

    private fun quotedPrintable(s: String, charset: String): String {
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '=' && i + 3 <= s.length && HEX.matches(s.substring(i + 1, i + 3))) {
                bytes.write(s.substring(i + 1, i + 3).toInt(16)); i += 3
            } else { bytes.write(c.toString().toByteArray(Charsets.UTF_8)); i++ }
        }
        return String(bytes.toByteArray(), runCatching { charset(charset) }.getOrDefault(Charsets.UTF_8))
    }

    fun ICalProp.key() = name.substringAfter('.').uppercase()
    fun ICalProp.group() = if (name.contains('.')) name.substringBefore('.') else ""
    private fun ICalProp.types(): List<String> = ICal.splitOutsideQuotes(params, ';').flatMap { p ->
        val eq = p.indexOf('=')
        if (eq > 0 && p.substring(0, eq).equals("TYPE", true)) p.substring(eq + 1).trim('"').split(',') else emptyList()
    }.map { it.trim().uppercase() }.filter { it.isNotEmpty() }

    /** A structured value's parts, each unescaped: N's five, ADR's seven, ORG's. */
    fun parts(value: String, sep: Char = ';'): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '\\' && i + 1 < value.length -> { cur.append(c).append(value[i + 1]); i += 2; continue }
                c == sep -> { out.add(ICal.unescape(cur.toString())); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out.add(ICal.unescape(cur.toString()))
        return out
    }

    /** Apple's and Google's own labels ("_$!<Mobile>!$_") as words. */
    private fun cleanLabel(s: String) = s.removePrefix("_$!<").removeSuffix(">!\$_").trim()

    /** A line's label: its group's X-ABLabel (Google's and Apple's custom ones), else its TYPE as Google names them. */
    private fun label(p: ICalProp, lines: List<ICalProp>): String {
        val g = p.group()
        if (g.isNotEmpty()) lines.firstOrNull { it.group().equals(g, true) && it.key() == "X-ABLABEL" }?.let { return cleanLabel(ICal.unescape(it.value)) }
        val t = p.types() - setOf("PREF", "VOICE", "INTERNET", "X-INTERNET")
        return when {
            "CELL" in t || "MOBILE" in t -> "Mobile"
            "FAX" in t && "WORK" in t -> "Work fax"
            "FAX" in t -> "Home fax"
            "PAGER" in t -> "Pager"
            "MAIN" in t -> "Main"
            "HOME" in t -> "Home"
            "WORK" in t -> "Work"
            "OTHER" in t -> "Other"
            else -> t.firstOrNull { !it.startsWith("X-") }?.lowercase()?.replaceFirstChar { it.uppercase() }
                ?: t.firstOrNull()?.removePrefix("X-")?.let(::cleanLabel) ?: ""
        }
    }

    fun info(lines: List<ICalProp>): ContactInfo {
        fun one(k: String) = lines.firstOrNull { it.key() == k }
        fun text(k: String) = one(k)?.let { ICal.unescape(it.value) }?.trim() ?: ""
        val n = one("N")?.let { parts(it.value) } ?: emptyList()
        val org = one("ORG")?.let { parts(it.value) } ?: emptyList()
        val photoLine = one("PHOTO")
        val photo = photoLine?.takeIf { it.param("ENCODING")?.equals("b", true) == true || it.param("ENCODING")?.equals("BASE64", true) == true }
            ?.value?.filterNot(Char::isWhitespace)?.ifEmpty { null }
        val dates = lines.filter { it.key() == "X-ABDATE" || it.key() == "ANNIVERSARY" }.map { p ->
            VField(if (p.key() == "ANNIVERSARY") "Anniversary" else label(p, lines).ifEmpty { "Other" }, p.value.trim())
        }
        return ContactInfo(
            uid = text("UID"),
            family = n.getOrElse(0) { "" }.trim(), given = n.getOrElse(1) { "" }.trim(), middle = n.getOrElse(2) { "" }.trim(),
            prefix = n.getOrElse(3) { "" }.trim(), suffix = n.getOrElse(4) { "" }.trim(),
            formatted = text("FN"),
            nickname = text("NICKNAME"),
            org = org.getOrElse(0) { "" }.trim(), department = org.drop(1).joinToString(", ") { it.trim() }.trim(',', ' '),
            title = text("TITLE"),
            phones = lines.filter { it.key() == "TEL" }.map { VField(label(it, lines), ICal.unescape(it.value).trim()) }.filter { it.value.isNotEmpty() },
            defaultPhone = lines.firstOrNull { it.key() == "TEL" && "PREF" in it.types() }?.let { ICal.unescape(it.value).trim() }?.ifEmpty { null },
            emails = lines.filter { it.key() == "EMAIL" }.map { VField(label(it, lines), ICal.unescape(it.value).trim()) }.filter { it.value.isNotEmpty() },
            addresses = lines.filter { it.key() == "ADR" }.map { p -> VAddress(label(p, lines), parts(p.value).map { it.trim() }.let { it + List((7 - it.size).coerceAtLeast(0)) { "" } }) }
                .filter { a -> a.parts.any { it.isNotBlank() } },
            urls = lines.filter { it.key() == "URL" }.map { VField(label(it, lines), ICal.unescape(it.value).trim()) }.filter { it.value.isNotEmpty() },
            birthday = one("BDAY")?.let { VDate.parse(it.value) },
            dates = dates.filter { VDate.parse(it.value) != null },
            related = lines.filter { it.key() == "X-ABRELATEDNAMES" || it.key() == "RELATED" }.map { VField(label(it, lines), ICal.unescape(it.value).trim()) }.filter { it.value.isNotEmpty() },
            note = text("NOTE"),
            categories = lines.filter { it.key() == "CATEGORIES" }.flatMap { parts(it.value, ',') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            photo = photo,
            photoUrl = photoLine?.takeIf { photo == null && it.value.startsWith("http", true) }?.value?.trim(),
            person = text("X-TETRA-PERSON").ifEmpty { null },
            face = text("X-TETRA-FACE").ifEmpty { null },
            rev = text("REV"),
        )
    }

    // -------- writing --------

    /** One card as a 3.0 file: CRLF, folded at 75 octets (ICal's writer: the same content-line rules). */
    fun write(lines: List<ICalProp>): String {
        val body = lines.filterNot { it.key() == "VERSION" }
        return ICal.write(listOf(ICalComp("VCARD", (listOf(ICalProp("VERSION", "", "3.0")) + body).toMutableList<Any>())))
    }

    /** Escapes a text value for 3.0 (backslash, comma, semicolon, new lines). */
    fun esc(s: String) = ICal.escape(s)

    /**
     * [was] edited into [now], on [lines]: the lines of what changed are replaced (where they stood, else at the end), every
     * other line kept as it was. UID is set when missing and REV is the time of the change.
     */
    fun apply(lines: List<ICalProp>, was: ContactInfo, now: ContactInfo, at: Instant = Instant.now()): List<ICalProp> {
        val out = lines.toMutableList()
        var nextGroup = (out.mapNotNull { it.group().removePrefix("item").removePrefix("ITEM").toIntOrNull() }.maxOrNull() ?: 0) + 1
        /** Takes away the lines of [keys] (and the labels of their groups), puts [add] where the first of them was. */
        fun replace(keys: Set<String>, add: List<ICalProp>) {
            // a group's label goes with its line, unless another line of the group stays
            val groups = out.filter { it.key() in keys }.map { it.group().lowercase() }.filter { it.isNotEmpty() }
                .filter { g -> out.none { o -> o.group().equals(g, true) && o.key() !in keys && o.key() != "X-ABLABEL" } }.toSet()
            fun goes(p: ICalProp) = p.key() in keys || (p.key() == "X-ABLABEL" && p.group().lowercase() in groups)
            val first = out.indexOfFirst { it.key() in keys }
            val at = if (first < 0) out.size else first - out.subList(0, first).count(::goes)
            out.removeAll(::goes)
            out.addAll(at.coerceIn(0, out.size), add)
        }
        fun labelled(key: String, f: VField, value: String = esc(f.value), types: Map<String, String>): List<ICalProp> {
            val t = types.entries.firstOrNull { it.key.equals(f.label, true) }?.value
            return when {
                f.label.isBlank() -> listOf(ICalProp(key, "", value))
                t != null -> listOf(ICalProp(key, ";TYPE=$t", value))
                else -> { val g = "item${nextGroup++}"; listOf(ICalProp("$g.$key", "", value), ICalProp("$g.X-ABLabel", "", esc(f.label))) }
            }
        }
        val phoneTypes = mapOf("Mobile" to "CELL", "Home" to "HOME", "Work" to "WORK", "Main" to "MAIN", "Home fax" to "HOME,FAX", "Work fax" to "WORK,FAX", "Pager" to "PAGER", "Other" to "OTHER")
        val placeTypes = mapOf("Home" to "HOME", "Work" to "WORK", "Other" to "OTHER")
        fun single(key: String, value: String) = if (value.isBlank()) emptyList() else listOf(ICalProp(key, "", esc(value)))

        if (listOf(was.prefix, was.given, was.middle, was.family, was.suffix, was.formatted) != listOf(now.prefix, now.given, now.middle, now.family, now.suffix, now.formatted)) {
            replace(setOf("N"), listOf(ICalProp("N", "", listOf(now.family, now.given, now.middle, now.prefix, now.suffix).joinToString(";") { esc(it) })))
            replace(setOf("FN"), listOf(ICalProp("FN", "", esc(now.formatted.ifBlank { now.composed() }.ifBlank { now.org }))))
        }
        if (was.nickname != now.nickname) replace(setOf("NICKNAME"), single("NICKNAME", now.nickname))
        if (was.org != now.org || was.department != now.department)
            replace(setOf("ORG"), if (now.org.isBlank() && now.department.isBlank()) emptyList() else listOf(ICalProp("ORG", "", esc(now.org) + if (now.department.isBlank()) "" else ";" + esc(now.department))))
        if (was.title != now.title) replace(setOf("TITLE"), single("TITLE", now.title))
        if (was.phones != now.phones || was.defaultPhone != now.defaultPhone) replace(setOf("TEL"), now.phones.flatMap { f ->
            labelled("TEL", f, types = phoneTypes).map { p -> // the default number: PREF added to its TYPE
                if (f.value != now.defaultPhone || p.name.endsWith("X-ABLabel")) p
                else ICalProp(p.name, if (p.params.startsWith(";TYPE=")) p.params + ",PREF" else ";TYPE=PREF", p.value)
            }
        })
        if (was.emails != now.emails) replace(setOf("EMAIL"), now.emails.flatMap { f ->
            labelled("EMAIL", f, types = placeTypes).map { if (it.params.isEmpty() && !it.name.contains('.')) ICalProp(it.name, ";TYPE=INTERNET", it.value) else if (it.params.startsWith(";TYPE=")) ICalProp(it.name, ";TYPE=INTERNET," + it.params.removePrefix(";TYPE="), it.value) else it }
        })
        if (was.addresses != now.addresses) replace(setOf("ADR"), now.addresses.flatMap { a ->
            labelled("ADR", VField(a.label, ""), (a.parts + List((7 - a.parts.size).coerceAtLeast(0)) { "" }).take(7).joinToString(";") { esc(it) }, placeTypes)
        })
        if (was.urls != now.urls) replace(setOf("URL"), now.urls.flatMap { labelled("URL", it, types = emptyMap()) })
        if (was.birthday != now.birthday) replace(setOf("BDAY"), listOfNotNull(now.birthday?.let { ICalProp("BDAY", "", it.value()) }))
        if (was.dates != now.dates) replace(setOf("X-ABDATE", "ANNIVERSARY"), now.dates.flatMap { f ->
            val label = if (f.label.equals("Anniversary", true)) "_$!<Anniversary>!\$_" else f.label.ifBlank { "Other" }
            val g = "item${nextGroup++}"
            listOf(ICalProp("$g.X-ABDATE", "", f.value), ICalProp("$g.X-ABLabel", "", esc(label)))
        })
        if (was.related != now.related) replace(setOf("X-ABRELATEDNAMES", "RELATED"), now.related.flatMap { f ->
            val g = "item${nextGroup++}"
            listOf(ICalProp("$g.X-ABRELATEDNAMES", "", esc(f.value)), ICalProp("$g.X-ABLabel", "", esc(if (f.label in RELATIONS) "_$!<${f.label}>!\$_" else f.label)))
        })
        if (was.note != now.note) replace(setOf("NOTE"), single("NOTE", now.note))
        if (was.categories != now.categories) replace(setOf("CATEGORIES"), if (now.categories.isEmpty()) emptyList() else listOf(ICalProp("CATEGORIES", "", now.categories.joinToString(",") { esc(it) })))
        if (was.photo != now.photo || was.photoUrl != now.photoUrl) replace(setOf("PHOTO"), when {
            now.photo != null -> listOf(ICalProp("PHOTO", ";ENCODING=b;TYPE=JPEG", now.photo))
            now.photoUrl != null -> listOf(ICalProp("PHOTO", ";VALUE=uri", now.photoUrl))
            else -> emptyList()
        })
        if (was.person != now.person) replace(setOf("X-TETRA-PERSON"), listOfNotNull(now.person?.let { ICalProp("X-TETRA-PERSON", "", it) }))
        if (was.face != now.face) replace(setOf("X-TETRA-FACE"), listOfNotNull(now.face?.let { ICalProp("X-TETRA-FACE", "", it) }))
        if (out.none { it.key() == "UID" }) out.add(ICalProp("UID", "", now.uid.ifBlank { UUID.randomUUID().toString() }))
        if (out.none { it.key() == "FN" }) out.add(ICalProp("FN", "", esc(now.name)))
        if (out.none { it.key() == "N" }) out.add(ICalProp("N", "", listOf(now.family, now.given, now.middle, now.prefix, now.suffix).joinToString(";") { esc(it) }))
        replace(setOf("REV"), listOf(ICalProp("REV", "", DateTimeFormatter.ISO_INSTANT.format(at.atOffset(ZoneOffset.UTC).withNano(0)))))
        return out
    }

    /** Apple's relation names, written as Apple and Google do ("_$!<Spouse>!$_"). */
    val RELATIONS = listOf("Spouse", "Partner", "Mother", "Father", "Parent", "Brother", "Sister", "Child", "Friend", "Assistant", "Manager")

    /** A new card's lines from what was filled in (an import from CSV, the New page). */
    fun build(info: ContactInfo, at: Instant = Instant.now()): List<ICalProp> = apply(emptyList(), ContactInfo(), info.copy(uid = info.uid.ifBlank { UUID.randomUUID().toString() }), at)
}

object ContactsFiles {
    const val FOLDER = "Contacts"

    fun root(tetra: File) = File(tetra, TetraSystem.onDisk(FOLDER))

    private fun vcfs(dir: File) = (dir.listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(".vcf", true) && !it.name.startsWith(".") }

    private val cache = HashMap<String, Triple<Long, Long, Contact>>()

    /** [f] read, or as read before when its size and time are the same. */
    fun cached(f: File): Contact = synchronized(cache) { cache[f.path]?.takeIf { it.first == f.length() && it.second == f.lastModified() }?.third }
        ?: read(f).also { synchronized(cache) { cache[f.path] = Triple(f.length(), f.lastModified(), it) } }

    fun read(f: File): Contact = Contact(f, VCard.cards(runCatching { f.readText() }.getOrDefault("")).firstOrNull() ?: emptyList())

    /**
     * Every contact in the folder, one per UID: sync's "x (2).vcf" copies show as one, the newer REV (then the newer file),
     * saying how many versions there are. A file is read again only when its size or time changed.
     */
    fun all(dir: File): List<Contact> {
        val read = vcfs(dir).map(::cached).filter { it.lines.isNotEmpty() }
        return read.groupBy { it.info.uid.ifEmpty { it.file!!.name } }.values.map { same ->
            same.maxWith(compareBy<Contact>({ it.info.rev }, { it.file?.lastModified() ?: 0 })).also { it.versions = same.size }
        }
    }

    /** Sorted for the list, by first name or by last name (Settings), letters' accents ignored. */
    fun sorted(all: List<Contact>, byLast: Boolean = false) = all.sortedWith(compareBy({ sortKey(it.info, byLast).isEmpty() }, { sortKey(it.info, byLast) }))

    fun sortKey(i: ContactInfo, byLast: Boolean): String = plain(if (byLast && i.family.isNotBlank()) "${i.family} ${i.given}" else i.name)
        .trimStart { !it.isLetterOrDigit() } // a name written with a direction mark or a quote first sorts by its first letter

    /** Lower case, accents off: "Άννα" and "αννα" sort and search together. */
    fun plain(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARKS, "").trim()
    private val MARKS = Regex("\\p{Mn}+") // made once: the list asks for every contact's letter as it draws

    /** The list's letter for a contact: "Ά" → "Α"; digits and signs under "#". */
    fun letter(i: ContactInfo, byLast: Boolean): String = sortKey(i, byLast).firstOrNull()?.takeIf { it.isLetter() }?.uppercase() ?: "#"

    /** A contact matches a search by any word of its name, company, a phone (digits only) or an e-mail. */
    fun matches(i: ContactInfo, query: String): Boolean {
        val q = plain(query)
        if (q.isEmpty()) return true
        val digits = query.filter(Char::isDigit)
        return plain(listOf(i.name, i.nickname, i.org, i.composed()).joinToString(" ")).contains(q) ||
            i.emails.any { plain(it.value).contains(q) } || (digits.length >= 3 && i.phones.any { it.value.filter(Char::isDigit).contains(digits) })
    }

    /** "maria-papadopoulou-3f9a2c.vcf": the name and six letters of the UID's hash; never renamed later (Calendar's rule). */
    fun fileName(name: String, uid: String): String {
        val slug = name.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(40).trimEnd('-').ifEmpty { "contact" }
        val hash = java.security.MessageDigest.getInstance("SHA-1").digest(uid.toByteArray()).take(3).joinToString("") { "%02x".format(it) }
        return "$slug-$hash.vcf"
    }

    private fun newFile(dir: File, name: String, uid: String): File {
        val n = fileName(name, uid)
        var f = File(dir, n)
        var k = 2
        while (f.exists()) f = File(dir, n.removeSuffix(".vcf") + " (${k++}).vcf")
        return f
    }

    /** Written beside, then renamed over: sync or another app never reads half a contact. */
    fun writeFile(f: File, lines: List<ICalProp>) {
        f.parentFile?.mkdirs()
        val part = File(f.parentFile, ".${f.name}.part")
        part.writeText(VCard.write(lines))
        if (!part.renameTo(f)) { f.writeText(part.readText()); part.delete() }
        f.setLastModified(System.currentTimeMillis()) // its time is now: the phone's storage keeps a renamed-over file's first date, and sync's hash cache goes by size and date
    }

    /** [now] saved: over [c]'s file (only what changed rewritten), or a new file in [dir]. */
    fun save(dir: File, c: Contact?, now: ContactInfo, at: Instant = Instant.now()): Contact {
        val lines = if (c == null) VCard.build(now, at) else VCard.apply(c.lines, c.info, now, at)
        val f = c?.file ?: newFile(dir, now.name, VCard.info(lines).uid)
        writeFile(f, lines)
        return Contact(f, lines)
    }

    /** A contact to Tetra's Trash (sync never deletes; a move into Trash crosses as a move), its "(2)" copies with it. */
    fun trash(dir: File, c: Contact): List<String> = TetraSystem.tetraOf(dir).let { tetra -> (listOfNotNull(c.file) + copies(dir, c)).map { DriveRules.moveToTrash(tetra, DriveRules.relative(tetra, it)) } }

    /** The other files of the same contact: sync's "(2)" copies, for "Keep this one". */
    fun copies(dir: File, c: Contact): List<File> = vcfs(dir).filter { it != c.file && read(it).info.uid == c.info.uid && c.info.uid.isNotEmpty() }

    /** The contacts in Tetra's Trash, newest first, for Restore. */
    fun trashed(dir: File): List<Contact> = vcfs(File(TetraSystem.tetraOf(dir), TetraSystem.onDisk("Trash"))).map(::read).filter { it.lines.isNotEmpty() }
        .sortedByDescending { runCatching { android.system.Os.lstat(it.file!!.path).st_ctime * 1000 }.getOrDefault(it.file!!.lastModified()) }

    fun restore(dir: File, c: Contact): File {
        dir.mkdirs()
        var to = File(dir, c.file!!.name)
        var k = 2
        while (to.exists()) to = File(dir, c.file.nameWithoutExtension + " (${k++}).vcf")
        require(c.file.renameTo(to)) { "Could not restore ${c.file.name}." }
        return to
    }

    /** One import's result: how many came in new, how many were already here (the same person: nothing written). */
    data class Imported(val added: Int, val same: Int, val uids: List<String>)

    /**
     * [cards] into the folder: a card whose UID is here, or the same name with a phone or e-mail in common (or neither
     * having any), is already here and is left alone, so importing twice gives no doubles. Each new one gets a UID.
     */
    fun import(dir: File, cards: List<List<ICalProp>>, at: Instant = Instant.now()): Imported {
        val here = all(dir).map { it.info }.toMutableList()
        var same = 0
        val uids = mutableListOf<String>()
        for (lines in cards) {
            val i = VCard.info(lines)
            if (i.name.isBlank() && i.photo == null) continue
            if (here.any { sameOne(it, i) }) { same++; continue }
            val withUid = if (i.uid.isBlank()) lines + ICalProp("UID", "", UUID.randomUUID().toString()) else lines
            val got = VCard.apply(withUid, VCard.info(withUid), VCard.info(withUid), at)
            val c = Contact(newFile(dir, i.name, VCard.info(got).uid), got)
            writeFile(c.file!!, got)
            here.add(c.info)
            uids.add(c.info.uid)
        }
        return Imported(uids.size, same, uids)
    }

    /** The same person: one UID, or one name and a phone or e-mail in common (or no phone and no e-mail on either). */
    fun sameOne(a: ContactInfo, b: ContactInfo): Boolean {
        if (a.uid.isNotEmpty() && a.uid == b.uid) return true
        if (plain(a.name) != plain(b.name) || a.name.isBlank()) return false
        val ka = keys(a); val kb = keys(b)
        return (ka.isEmpty() && kb.isEmpty()) || ka.intersect(kb).isNotEmpty()
    }

    /** A contact's phones (their last nine digits: +30 and 0030 alike) and e-mails, to tell one person. */
    fun keys(i: ContactInfo): Set<String> = (i.phones.map { it.value.filter(Char::isDigit).takeLast(9) }.filter { it.length >= 5 } + i.emails.map { it.value.lowercase().trim() }).toSet()
}

/** Contacts from other places: a Google Takeout zip, Google's CSV (both of its forms). */
object ContactsImport {
    /** Any of what Import takes, by its bytes: a zip (Takeout), a CSV, or vCard text (one or many cards). */
    fun read(bytes: ByteArray, name: String = ""): List<List<ICalProp>> = when {
        bytes.size > 1 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() -> zip(bytes)
        name.endsWith(".csv", true) || (!String(bytes.copyOf(minOf(bytes.size, 4000))).contains("BEGIN:VCARD", true)) -> csv(String(bytes, Charsets.UTF_8))
        else -> VCard.cards(String(bytes, Charsets.UTF_8))
    }

    /**
     * Google Takeout's Contacts: one .vcf per label ("Contacts/Family/Family.vcf", "All Contacts/All Contacts.vcf"), the
     * same person in several. Each person once, with the labels of every folder they were in.
     */
    fun zip(bytes: ByteArray): List<List<ICalProp>> {
        val got = mutableListOf<Pair<String, List<ICalProp>>>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { z ->
            generateSequence { z.nextEntry }.forEach { e ->
                if (e.isDirectory) return@forEach
                val text = z.readBytes()
                val label = e.name.substringBeforeLast('/', "").substringAfterLast('/')
                when {
                    e.name.endsWith(".vcf", true) -> VCard.cards(String(text, Charsets.UTF_8)).forEach { got.add(label to it) }
                    e.name.endsWith(".csv", true) -> csv(String(text, Charsets.UTF_8)).forEach { got.add(label to it) }
                }
            }
        }
        val out = mutableListOf<Pair<ContactInfo, List<ICalProp>>>()
        for ((label, lines) in got) {
            val i = VCard.info(lines)
            val extra = when {
                label.isEmpty() || label.equals("All Contacts", true) || label.equals("Contacts", true) || label.equals("My Contacts", true) -> null
                label.equals("Starred", true) || label.equals("Starred in Android", true) -> ContactInfo.STARRED
                else -> label
            }
            val at = out.indexOfFirst { ContactsFiles.sameOne(it.first, i) }
            val base = if (at >= 0) out[at] else (i to lines)
            val cats = base.first.categories.let { if (extra != null && it.none { c -> c.equals(extra, true) }) it + extra else it }
            val merged = if (cats == base.first.categories) base else base.first.copy(categories = cats).let { n -> n to VCard.apply(base.second, base.first, n).filterNot { l -> with(VCard) { l.key() } == "REV" } }
            if (at >= 0) out[at] = merged else out.add(merged)
        }
        return out.map { it.second }
    }

    /** CSV's rows; quotes, doubled quotes and new lines inside quotes as RFC 4180. */
    fun rows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        val s = text.removePrefix("﻿")
        while (i < s.length) {
            val c = s[i]
            when {
                quoted && c == '"' && i + 1 < s.length && s[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row.add(cell.toString()); cell.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < s.length && s[i + 1] == '\n') i++
                    row.add(cell.toString()); cell.clear(); if (row.any { it.isNotEmpty() }) rows.add(row); row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        row.add(cell.toString()); if (row.any { it.isNotEmpty() }) rows.add(row)
        return rows
    }

    /**
     * Google Contacts' CSV, its 2024 form ("First Name", "Phone 1 - Label", "Labels") and its older one ("Given Name",
     * "Phone 1 - Type", "Group Membership"); several values in one cell are " ::: " apart, as Google writes them.
     */
    fun csv(text: String): List<List<ICalProp>> {
        val all = rows(text)
        if (all.isEmpty()) return emptyList()
        val head = all.first().map { it.trim() }
        fun col(row: List<String>, vararg names: String) = names.firstNotNullOfOrNull { n -> head.indexOfFirst { it.equals(n, true) }.takeIf { it >= 0 } }
            ?.let { row.getOrElse(it) { "" }.trim() } ?: ""
        fun many(v: String) = v.split(" ::: ").map { it.trim() }.filter { it.isNotEmpty() }
        fun numbered(row: List<String>, what: String, valueName: String = "Value"): List<VField> = (1..20).flatMap { k ->
            val label = col(row, "$what $k - Label", "$what $k - Type").removePrefix("* ")
            many(col(row, "$what $k - $valueName")).map { VField(label.let(::labelWord), it) }
        }
        return all.drop(1).map { row ->
            val labels = many(col(row, "Labels", "Group Membership")).map { it.removePrefix("* ").trim() }
                .map { if (it.equals("My Contacts", true)) ContactInfo.MY_CONTACTS else if (it.equals("Starred", true)) ContactInfo.STARRED else it }
            val addresses = (1..10).mapNotNull { k ->
                val parts = listOf(col(row, "Address $k - PO Box"), col(row, "Address $k - Extended Address"), col(row, "Address $k - Street"),
                    col(row, "Address $k - City"), col(row, "Address $k - Region"), col(row, "Address $k - Postal Code"), col(row, "Address $k - Country"))
                val formatted = col(row, "Address $k - Formatted")
                val label = labelWord(col(row, "Address $k - Label", "Address $k - Type").removePrefix("* "))
                when {
                    parts.any { it.isNotEmpty() } -> VAddress(label, parts)
                    formatted.isNotEmpty() -> VAddress(label, listOf("", "", formatted.replace('\n', ' '), "", "", "", ""))
                    else -> null
                }
            }
            val events = numbered(row, "Event")
            val info = ContactInfo(
                prefix = col(row, "Name Prefix"), given = col(row, "First Name", "Given Name"), middle = col(row, "Middle Name", "Additional Name"),
                family = col(row, "Last Name", "Family Name"), suffix = col(row, "Name Suffix"), formatted = col(row, "Name"),
                nickname = col(row, "Nickname"), org = col(row, "Organization Name", "Organization 1 - Name"),
                department = col(row, "Organization Department", "Organization 1 - Department"), title = col(row, "Organization Title", "Organization 1 - Title"),
                phones = numbered(row, "Phone"), emails = numbered(row, "E-mail"), addresses = addresses, urls = numbered(row, "Website"),
                birthday = VDate.parse(col(row, "Birthday")),
                dates = events.filter { VDate.parse(it.value) != null }.map { VField(it.label.ifEmpty { "Other" }, VDate.parse(it.value)!!.value()) },
                related = numbered(row, "Relation"), note = col(row, "Notes"), categories = labels,
                photoUrl = col(row, "Photo").takeIf { it.startsWith("http") },
            )
            VCard.build(info.copy(formatted = info.formatted.ifBlank { info.composed() }))
        }.filter { VCard.info(it).name.isNotBlank() }
    }

    /** Google CSV's "Mobile", "* Home", "Work Fax" as Tetra's labels. */
    private fun labelWord(s: String) = when (s.trim().lowercase()) {
        "mobile", "cell" -> "Mobile"
        "home" -> "Home"
        "work" -> "Work"
        "main" -> "Main"
        "other" -> "Other"
        "work fax" -> "Work fax"
        "home fax" -> "Home fax"
        else -> s.trim()
    }
}

/** Merge & fix (Plan.md § Contacts): the same person twice, merged into one file; a pair said not to be is never offered again. */
object ContactsMerge {
    private val KNOWN = setOf("VERSION", "PRODID", "N", "FN", "NICKNAME", "ORG", "TITLE", "TEL", "EMAIL", "ADR", "URL", "BDAY", "X-ABDATE", "ANNIVERSARY",
        "X-ABRELATEDNAMES", "RELATED", "NOTE", "CATEGORIES", "PHOTO", "X-TETRA-PERSON", "X-TETRA-FACE", "UID", "REV", "X-ABLABEL", "X-TETRA-NOT-DUPLICATE")

    private fun notSame(c: Contact, uid: String) = with(VCard) { c.lines.any { it.key() == "X-TETRA-NOT-DUPLICATE" && it.value.trim() == uid } }

    /** Suggested duplicates: the same name, or a phone or e-mail in common; a pair said not to be the same is left out. */
    fun pairs(all: List<Contact>): List<Pair<Contact, Contact>> {
        val names = all.map { ContactsFiles.plain(it.info.name) }
        val keys = all.map { ContactsFiles.keys(it.info) }
        val out = mutableListOf<Pair<Contact, Contact>>()
        for (i in all.indices) for (j in i + 1 until all.size) {
            if (!(names[i].isNotEmpty() && names[i] == names[j]) && keys[i].none { it in keys[j] }) continue
            if (notSame(all[i], all[j].info.uid) || notSame(all[j], all[i].info.uid)) continue
            // the one with more in it first: it is the one kept
            out += if (all[j].lines.size > all[i].lines.size) all[j] to all[i] else all[i] to all[j]
        }
        return out
    }

    /** [a] with what [b] adds: [a]'s single values first (else [b]'s), every phone, e-mail, address, date and label of both. */
    fun merged(a: ContactInfo, b: ContactInfo): ContactInfo {
        fun s(x: String, y: String) = x.ifBlank { y }
        return a.copy(
            prefix = s(a.prefix, b.prefix), given = s(a.given, b.given), middle = s(a.middle, b.middle), family = s(a.family, b.family), suffix = s(a.suffix, b.suffix),
            formatted = s(a.formatted, b.formatted), nickname = s(a.nickname, b.nickname), org = s(a.org, b.org), department = s(a.department, b.department), title = s(a.title, b.title),
            phones = (a.phones + b.phones).distinctBy { ContactsWaysKey.key(it.value) }, defaultPhone = a.defaultPhone ?: b.defaultPhone,
            emails = (a.emails + b.emails).distinctBy { it.value.lowercase().trim() },
            addresses = (a.addresses + b.addresses).distinctBy { ContactsFiles.plain(it.text()) },
            urls = (a.urls + b.urls).distinctBy { it.value.trim().lowercase() },
            birthday = a.birthday?.let { x -> if (x.year == null && b.birthday?.month == x.month && b.birthday.day == x.day) b.birthday else x } ?: b.birthday,
            dates = (a.dates + b.dates).distinctBy { it.label.lowercase() to it.value },
            related = (a.related + b.related).distinctBy { it.label.lowercase() to ContactsFiles.plain(it.value) },
            note = listOf(a.note, b.note).filter { it.isNotBlank() }.distinct().joinToString("\n\n"),
            categories = (a.categories + b.categories).distinctBy { it.lowercase() },
            photo = a.photo ?: b.photo, photoUrl = if (a.photo != null) a.photoUrl else a.photoUrl ?: b.photoUrl, person = a.person ?: b.person,
            face = if (a.photo != null) a.face else a.face ?: b.face,
        )
    }

    /**
     * [b] merged into [a]: one file with every value of both ([b]'s lines Tetra does not read come along, if not in a group —
     * shortcut: a grouped unknown line of [b] is dropped, its itemN could clash with [a]'s), [b]'s file to the Trash.
     */
    fun merge(dir: File, a: Contact, b: Contact, at: Instant = Instant.now()): Contact {
        val saved = ContactsFiles.save(dir, a, merged(a.info, b.info), at)
        val extra = with(VCard) { b.lines.filter { it.key() !in KNOWN && it.group().isEmpty() && saved.lines.none { s -> s.toString() == it.toString() } } }
        val out = if (extra.isEmpty()) saved else Contact(saved.file, saved.lines + extra).also { ContactsFiles.writeFile(it.file!!, it.lines) }
        ContactsFiles.trash(dir, b)
        return out
    }

    /** "Not the same": remembered in both files (X-TETRA-NOT-DUPLICATE), so every device stops offering the pair. */
    fun notTheSame(a: Contact, b: Contact) {
        listOf(a to b, b to a).forEach { (x, y) -> ContactsFiles.writeFile(x.file!!, x.lines + ICalProp("X-TETRA-NOT-DUPLICATE", "", y.info.uid)) }
    }

    /** Cards for a .vcf: each as its file has it, one after another. [noPhoto]: the photo and Tetra's own lines left out (a QR code). */
    fun export(cs: List<Contact>, noPhoto: Boolean = false): String = cs.joinToString("") { c ->
        VCard.write(if (noPhoto) with(VCard) { c.lines.filterNot { it.key() == "PHOTO" || it.key().startsWith("X-TETRA") || it.key() == "REV" } } else c.lines)
    }
}

private object ContactsWaysKey { fun key(n: String) = n.filter(Char::isDigit).takeLast(9).ifEmpty { n.trim() } }
