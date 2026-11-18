package com.kalotrapezis.drive

import org.json.JSONArray
import org.json.JSONObject

/**
 * Tetra's own sources file (asked 2026-10-14): the references found while researching, written once — by the user's AI
 * — and imported into any draft with References › Sources › Import, so every draft gets its APA list in one click.
 * The format is TETRA_SOURCES_FORMAT.md; this reads it, kindly: unknown fields are ignored, a source without a title
 * is skipped and said so.
 */
internal object SourcesImport {
    data class Result(val sources: List<DocSource>, val skipped: List<Skip>)
    /** A source left out: its [number] in the file (from 1), and whether it was [untitled] (else not a source at all). */
    data class Skip(val number: Int, val untitled: Boolean)
    /** Not a Tetra sources file: the message is English, for the log; the app says it in its own words. */
    class NotSourcesFile : IllegalArgumentException("This is not a Tetra sources file (no \"sources\" list).")

    fun read(text: String): Result {
        // Pasted from a chat: the JSON may come inside ``` marks or with a sentence round it — from the first { or [ on.
        val json = text.indexOfFirst { it == '{' || it == '[' }.takeIf { it >= 0 }?.let { a ->
            val z = if (text[a] == '{') text.lastIndexOf('}') else text.lastIndexOf(']')
            if (z > a) text.substring(a, z + 1) else null
        } ?: text
        val root = runCatching { JSONObject(json) }.getOrNull()
        val list = root?.optJSONArray("sources") ?: runCatching { JSONArray(json) }.getOrNull()
            ?: throw NotSourcesFile()
        val out = ArrayList<DocSource>(); val skipped = ArrayList<Skip>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: run { skipped += Skip(i + 1, false); null } ?: continue
            val title = o.optString("title").trim()
            if (title.isEmpty()) { skipped += Skip(i + 1, true); continue }
            fun s(vararg names: String) = names.firstNotNullOfOrNull { n -> o.optString(n).trim().takeIf { it.isNotEmpty() && it != "null" } }.orEmpty()
            val type = when (s("type").lowercase()) {
                "article", "journal", "journal article", "paper" -> DocSource.Type.Article
                "chapter", "book chapter", "incollection" -> DocSource.Type.Chapter
                "web", "webpage", "web page", "website", "online" -> DocSource.Type.Web
                "report", "techreport" -> DocSource.Type.Report
                "thesis", "dissertation", "phdthesis", "mastersthesis" -> DocSource.Type.Thesis
                "video", "film" -> DocSource.Type.Video
                "book" -> DocSource.Type.Book
                else -> if (s("journal").isNotEmpty()) DocSource.Type.Article else DocSource.Type.Book
            }
            out += DocSource(
                tag = "", type = type, authors = people(o.opt("authors")), year = s("year").take(16), title = title,
                container = s("journal", "container", "website", "book", "number", "kind", "platform"), publisher = s("publisher", "university", "institution"),
                volume = s("volume"), issue = s("issue"), pages = s("pages").replace(Regex("""\s*-{1,2}\s*"""), "–"),
                // A DOI stays bare (APA adds https://doi.org/, Word keeps it as its DOI); a web page's address as it is.
                doi = s("doi").replace(Regex("""^(https?://(dx\.)?doi\.org/|doi:\s*)""", RegexOption.IGNORE_CASE), "").ifEmpty { s("url") },
                edition = s("edition"), editors = people(o.opt("editors")),
            )
        }
        return Result(out, skipped)
    }

    /** Authors as objects ({"last","first"} or {"org"}) or as "Last, First" strings. */
    private fun people(v: Any?): List<DocSource.Person> {
        val a = v as? JSONArray ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            when (val p = a.opt(i)) {
                is JSONObject -> p.optString("org").trim().takeIf { it.isNotEmpty() }?.let { DocSource.Person(it) }
                    ?: p.optString("last").trim().takeIf { it.isNotEmpty() }?.let { DocSource.Person(it, p.optString("first").trim()) }
                is String -> p.trim().takeIf { it.isNotEmpty() }?.let { n -> n.split(',', limit = 2).let { DocSource.Person(it[0].trim(), it.getOrElse(1) { "" }.trim()) } }
                else -> null
            }
        }
    }

    /** The same work already among [have]: by DOI, else by title and year (letters only, any case). */
    fun same(a: DocSource, have: List<DocSource>): Boolean {
        fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val doi = key(a.doi.substringAfter("doi.org/"))
        return have.any { b -> (doi.isNotEmpty() && doi == key(b.doi.substringAfter("doi.org/"))) || (key(a.title) == key(b.title) && a.year == b.year) }
    }
}
