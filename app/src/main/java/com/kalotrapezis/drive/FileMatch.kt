package com.kalotrapezis.drive

/**
 * Finding a file by what was said (8 Nov: "a file that is there three times, and AI Assist does not find it, even by its
 * exact name"): a spoken name loses its dots, dashes and underscores and may come in another order or with another ending,
 * so the name is matched word by word — each word of the query must start a word of the name (its first five letters: Greek
 * endings change), whatever lies between them.
 */
internal object FileMatch {
    private val WORD = Regex("[\\p{L}\\p{N}]+")

    /** The words of [s]: lower case, no accents, final ς as σ. */
    fun words(s: String): List<String> = WORD.findAll(ContactsFiles.plain(s).replace('ς', 'σ')).map { it.value }.toList()

    /** The query's words worth matching: single letters only when they are digits. */
    fun query(s: String): List<String> = words(s).filter { it.length > 1 || it[0].isDigit() }

    private fun stem(w: String) = if (w.length > 5) w.take(5) else w

    /** How many of [query]'s words [name] has. */
    fun score(query: List<String>, name: String): Int {
        val n = words(name)
        return query.count { q -> val s = stem(q); n.any { it.startsWith(s) } }
    }
}
