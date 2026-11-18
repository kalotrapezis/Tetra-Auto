package com.kalotrapezis.drive

/**
 * Word's typing shortcut (asked 2026-10-14): "2^2" becomes 2² and "H_2O" becomes H₂O when the space after the word is
 * typed; Backspace straight after brings the letters back as typed. Finds what to raise or lower in the word just ended.
 */
internal object ScriptShortcut {
    /** One mark in a word: the ^ or _ at [mark], the letters after it [from] until [to] (a "(…)" without its brackets). */
    data class Raise(val mark: Int, val from: Int, val to: Int, val up: Boolean, val bracketed: Boolean)

    // After the mark: digits, or letters, or "(…)"; something must stand before it (a lone "^2" or "a_" is left alone).
    private val PATTERN = Regex("""(?<=\S)([\^_])(\d+|\p{L}+|\([^()\s]+\))""")

    /** The marks to turn into superscript / subscript in [word] (no spaces in it), left to right; empty: nothing to do. */
    fun find(word: String): List<Raise> {
        if (word.isEmpty() || word.any { it.isWhitespace() }) return emptyList()
        // Web addresses, file names and code keep their _ (snake_case, my_file_2): only math-like words are touched.
        if ("://" in word || "@" in word || word.count { it == '_' } > 2) return emptyList()
        return PATTERN.findAll(word).map { m ->
            val group = m.groups[2]!!
            val bracketed = group.value.startsWith("(")
            Raise(m.range.first, group.range.first + if (bracketed) 1 else 0, group.range.last + 1 - if (bracketed) 1 else 0, m.groupValues[1] == "^", bracketed)
        }.filter { r ->
            // "first_name" is a name, not a subscript: a _ between two words of two letters or more stays (x_i, H_2O go).
            val before = word.substring(0, r.mark).takeLastWhile { it.isLetter() }
            r.up || !(before.length > 1 && r.to - r.from > 1 && word.substring(r.from, r.to).all { it.isLetter() })
        }.toList()
    }

    /** [word] as it reads once [raises] are applied: the marks (and a group's brackets) gone. */
    fun shown(word: String, raises: List<Raise>): String {
        val drop = raises.flatMap { r -> listOf(r.mark) + if (r.bracketed) listOf(r.from - 1, r.to) else emptyList() }.toSet()
        return word.filterIndexed { i, _ -> i !in drop }
    }
}
