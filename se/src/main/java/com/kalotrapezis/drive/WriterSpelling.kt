package com.kalotrapezis.drive

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream

/*
 * Review › Spelling: Tetra's own dictionaries — LibreOffice's Hunspell ones, Greek (el_GR) and English (en_US), in the
 * app's assets (asked 2026-10-01: the phone's keyboard checker marked English words wrong in a Greek keyboard, and the
 * reverse on the tablet). Read by [Speller]. Greek words are checked in Greek, the rest in English.
 * Nothing leaves the phone; a word asked once is remembered for the session.
 */
internal class WriterSpelling(private val context: Context) {
    /** Word → its suggestions when it looks misspelled, else null (known to be right). */
    private val known = HashMap<String, List<String>?>()
    private val lock = Mutex()

    /** Which of [words] look misspelled, with their suggestions (words already asked answered from memory). */
    suspend fun check(words: Collection<String>): Map<String, List<String>> = lock.withLock {
        withContext(Dispatchers.Default) {
            for ((lang, ask) in words.filter { it !in known }.distinct().groupBy(::langOf)) {
                val speller = Dictionaries.get(context, lang) ?: run { ask.forEach { known[it] = null }; null } ?: continue
                for (w in ask) known[w] = Dictionaries.misspelled(speller, w)
            }
            words.mapNotNull { w -> known[w]?.let { w to it } }.toMap()
        }
    }

    companion object {
        /** The words of a text and where they are: letters, with an apostrophe inside (don't, l'école); no digits. */
        private val WORD = Regex("""\p{L}+(?:['’]\p{L}+)*""")
        fun words(text: String): List<IntRange> = WORD.findAll(text).map { it.range }.filter { it.last > it.first }.toList() // one-letter words skipped

        /** Greek letters: the Greek dictionary; any other: the English one. */
        fun langOf(word: String) = if (word.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.GREEK }) "el" else "en"
    }
}

/** The dictionaries, read once per process (the Greek one, ~10 MB, takes a moment). */
internal object Dictionaries {
    private val loaded = HashMap<String, Speller?>()

    @Synchronized fun get(context: Context, lang: String): Speller? = loaded.getOrPut(lang) {
        val name = if (lang == "el") "el_GR" else "en_US"
        runCatching {
            WriterLog.timed("read the $name dictionary") {
                context.assets.open("dictionaries/$name.aff").use { aff -> context.assets.open("dictionaries/$name.dic").use { dic -> of(aff, dic, lang) } }
            }
        }.onFailure { WriterLog.e("dictionary $name", it) }.getOrNull()
    }

    fun of(aff: InputStream, dic: InputStream, lang: String): Speller = Speller(aff.readBytes(), dic.readBytes(), lang)

    /** null when [word] is right; else up to five suggestions (Hunspell's way: one change away, its REP table, case kept). */
    fun misspelled(speller: Speller, word: String): List<String>? {
        val w = word.replace('’', '\'')
        if (speller.spell(w)) return null
        return speller.suggest(w).take(5)
    }
}

/**
 * A Hunspell dictionary, the little of the format LibreOffice's Greek and English ones use (Lucene's full one needs
 * Java APIs Android lacks): the word list, prefixes and suffixes with their conditions and cross products, REP and TRY
 * for suggestions. Greek is a plain list of 828 000 forms, kept as its sorted bytes (ISO-8859-7) and searched by
 * halves; English, 50 000 roots with affix flags. ponytail: no compounding (English uses it only for "1st", "22nd").
 */
internal class Speller(affBytes: ByteArray, dicBytes: ByteArray, lang: String) {
    private class Affix(val strip: String, val add: String, val condition: Regex?, val cross: Boolean)
    private val greek = lang == "el"
    private val charset = if (greek) charset("ISO-8859-7") else Charsets.UTF_8
    private val prefixes = HashMap<Char, MutableList<Affix>>()
    private val suffixes = HashMap<Char, MutableList<Affix>>()
    private val rep = ArrayList<Pair<String, String>>()
    private var tryChars = ""
    // English: root → its flags. Greek: the sorted list's bytes and where each word starts.
    private val roots = HashMap<String, String>()
    private val bytes: ByteArray
    private val starts: IntArray

    init {
        val crosses = HashMap<String, Boolean>()
        for (line in String(affBytes, charset).lineSequence()) {
            val f = line.trim().split(Regex("\\s+"))
            when (f.firstOrNull()) {
                "TRY" -> tryChars = f.getOrNull(1).orEmpty()
                "REP" -> if (f.size >= 3) rep += f[1].replace('_', ' ') to f[2].replace('_', ' ')
                "PFX", "SFX" -> if (f.size == 4 && f[2].length == 1) crosses[f[0] + f[1]] = f[2] == "Y"
                    else if (f.size >= 5) {
                        val cond = f[4].takeIf { it != "." }?.let { if (f[0] == "SFX") Regex("$it$") else Regex("^$it") }
                        val affix = Affix(f[2].takeIf { it != "0" }.orEmpty(), f[3].substringBefore('/').takeIf { it != "0" }.orEmpty(), cond, crosses[f[0] + f[1]] == true)
                        (if (f[0] == "PFX") prefixes else suffixes).getOrPut(f[1][0]) { ArrayList() } += affix
                    }
            }
        }
        if (greek) {
            // The list as sorted bytes (sorted when added to the app): its first line is the count.
            bytes = dicBytes
            val s = ArrayList<Int>(900_000)
            var i = dicBytes.indexOf('\n'.code.toByte()) + 1
            while (i < dicBytes.size) { s += i; while (i < dicBytes.size && dicBytes[i] != '\n'.code.toByte()) i++; i++ }
            starts = s.toIntArray()
        } else {
            bytes = ByteArray(0); starts = IntArray(0)
            String(dicBytes, charset).lineSequence().drop(1).forEach { l ->
                val w = l.substringBefore('/').trim(); if (w.isNotEmpty()) roots[w] = roots[w].orEmpty() + l.substringAfter('/', "").trim()
            }
        }
    }

    private fun inList(word: String): Boolean {
        val key = word.toByteArray(charset)
        var lo = 0; var hi = starts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            var a = starts[mid]; var k = 0
            var c = 0
            while (true) {
                val end = a >= bytes.size || bytes[a] == '\n'.code.toByte()
                if (k == key.size || end) { c = if (k == key.size && end) 0 else if (end) 1 else -1; break }
                c = (key[k].toInt() and 0xFF) - (bytes[a].toInt() and 0xFF)
                if (c != 0) break
                a++; k++
            }
            if (c == 0) return true
            if (c < 0) hi = mid - 1 else lo = mid + 1
        }
        return false
    }

    /** The word as the dictionary has it: in the list, or a root with its affixes (one prefix, one suffix, crossed). */
    private fun known(w: String): Boolean {
        if (greek) return inList(w)
        if (roots.containsKey(w)) return true
        fun root(r: String, flag: Char, also: Char? = null) = roots[r]?.let { flag in it && (also == null || also in it) } == true
        for ((flag, list) in suffixes) for (s in list) {
            if (!w.endsWith(s.add) || w.length <= s.add.length) continue
            val r = w.dropLast(s.add.length) + s.strip
            if (s.condition?.containsMatchIn(r) == false) continue
            if (root(r, flag)) return true
            // …with a prefix too.
            if (s.cross) for ((pf, pl) in prefixes) for (p in pl) {
                if (!p.cross || !r.startsWith(p.add) || r.length <= p.add.length) continue
                val rr = p.strip + r.drop(p.add.length)
                if (p.condition?.containsMatchIn(rr) != false && root(rr, flag, pf)) return true
            }
        }
        for ((flag, list) in prefixes) for (p in list) {
            if (!w.startsWith(p.add) || w.length <= p.add.length) continue
            val r = p.strip + w.drop(p.add.length)
            if (p.condition?.containsMatchIn(r) != false && root(r, flag)) return true
        }
        return false
    }

    /** Right as written, at a sentence's start (capital first), or in capitals (Greek capitals carry no accents). */
    fun spell(w: String): Boolean {
        if (known(w)) return true
        val lower = w.lowercase()
        val first = w.first().isUpperCase() && w.drop(1).none { it.isUpperCase() }
        val caps = w.length > 1 && w.none { it.isLowerCase() }
        if (first && known(lower)) return true
        if (caps) {
            val cap = lower.replaceFirstChar { it.uppercaseChar() }
            if (known(lower) || known(cap)) return true
            if (greek) return accented(fixSigma(lower)).any { known(it) || known(it.replaceFirstChar { c -> c.uppercaseChar() }) }
        }
        return false
    }

    private fun fixSigma(s: String) = if (s.endsWith('σ')) s.dropLast(1) + 'ς' else s
    /** A Greek word with one accent (or diaeresis) put on each of its vowels in turn. */
    private fun accented(s: String) = s.indices.flatMap { i -> ACCENTS[s[i]].orEmpty().map { a -> s.substring(0, i) + a + s.substring(i + 1) } }

    /** Words one change away (Hunspell's order: its REP table, a letter swapped, changed, missing, extra), case kept. */
    fun suggest(w: String): List<String> {
        val caps = w.length > 1 && w.none { it.isLowerCase() }
        val first = w.first().isUpperCase()
        val base = if (caps || first) w.lowercase() else w
        val alphabet = (tryChars.ifEmpty { if (greek) GREEK_TRY else "esianrtolcdugmphbyfvkwz" }).lowercase().toSet() + (if (greek) ACCENTS.values.flatten().toSet() else emptySet())
        val out = LinkedHashSet<String>()
        fun tryIt(c: String) { if (out.size < 12 && c.isNotEmpty() && c != base && known(c)) out += c }
        if (greek) accented(base).forEach(::tryIt) // an accent missing: the commonest Greek slip
        for ((from, to) in rep) { var i = base.indexOf(from); while (i >= 0) { tryIt(base.substring(0, i) + to + base.substring(i + from.length)); i = base.indexOf(from, i + 1) } }
        for (i in 0 until base.length - 1) tryIt(base.substring(0, i) + base[i + 1] + base[i] + base.substring(i + 2))
        for (i in base.indices) for (c in alphabet) if (c != base[i]) tryIt(base.substring(0, i) + c + base.substring(i + 1))
        for (i in base.indices) tryIt(base.removeRange(i, i + 1))
        for (i in 0..base.length) for (c in alphabet) tryIt(base.substring(0, i) + c + base.substring(i))
        return out.map { s -> when { caps -> s.uppercase(); first -> s.replaceFirstChar { it.uppercaseChar() }; else -> s } }.distinct()
    }

    companion object {
        private const val GREEK_TRY = "αοιεντσρηυπκμλωγδθφχβζξψςάέήίόύώϊϋΐΰ"
        private val ACCENTS = mapOf('α' to listOf("ά"), 'ε' to listOf("έ"), 'η' to listOf("ή"), 'ι' to listOf("ί", "ϊ", "ΐ"), 'ο' to listOf("ό"), 'υ' to listOf("ύ", "ϋ", "ΰ"), 'ω' to listOf("ώ"))
    }
}

/**
 * Review › Spelling as one types, for an editor's [state] (Words' page, a PDF's text): the words that look misspelled
 * ([shown]: not the one being typed, and only as they were checked), the one the cursor is in ([here]) with its
 * suggestions. Made by [rememberSpelling]; [ignored] words are not marked again.
 */
@androidx.compose.runtime.Stable
internal class Spelling(private val state: com.mohamedrejeb.richeditor.model.RichTextState) {
    var ignored by androidx.compose.runtime.mutableStateOf(emptySet<String>())
    internal var misspelled by androidx.compose.runtime.mutableStateOf<List<Triple<IntRange, String, List<String>>>>(emptyList())
    internal var typingAt by androidx.compose.runtime.mutableIntStateOf(-1) // the word being typed is not marked until one leaves it
    /** The marked words now: still as they were checked, and not the one being typed. */
    val shown by androidx.compose.runtime.derivedStateOf {
        val text = state.annotatedString.text
        misspelled.filter { (r, w) -> r.last < text.length && text.regionMatches(r.first, w, 0, w.length) && typingAt !in r.first..r.last + 1 }
    }
    val here by androidx.compose.runtime.derivedStateOf { state.selection.let { sel -> shown.firstOrNull { (r) -> sel.min in r.first..r.last + 1 && sel.max <= r.last + 1 } } }
    fun replace(r: IntRange, with: String) {
        state.selection = androidx.compose.ui.text.TextRange(r.first, r.last + 1)
        state.removeSelectedText(); state.addTextAfterSelection(with)
    }
    /** The next marked word after the cursor (round to the first), selected: false when there is none. */
    fun next(): Boolean {
        val at = state.selection.max
        val n = shown.firstOrNull { it.first.first >= at } ?: shown.firstOrNull() ?: return false
        state.selection = androidx.compose.ui.text.TextRange(n.first.first, n.first.last + 1); return true
    }
}

/** [state]'s spelling, checked while [active] and [on] (half a second after the typing stops). */
@androidx.compose.runtime.Composable
internal fun rememberSpelling(state: com.mohamedrejeb.richeditor.model.RichTextState, speller: WriterSpelling, on: Boolean, active: Boolean): Spelling {
    val sp = androidx.compose.runtime.remember(state) { Spelling(state) }
    androidx.compose.runtime.LaunchedEffect(state, active) {
        var last = ""
        androidx.compose.runtime.snapshotFlow { state.annotatedString.text to state.selection }.collect { (t, sel) -> sp.typingAt = if (t != last) sel.min else -1; last = t }
    }
    androidx.compose.runtime.LaunchedEffect(state, active, on, sp.ignored) {
        if (!active || !on) { sp.misspelled = emptyList(); return@LaunchedEffect }
        androidx.compose.runtime.snapshotFlow { state.annotatedString.text }.collectLatest { text ->
            kotlinx.coroutines.delay(500)
            val ranges = WriterSpelling.words(text)
            val bad = speller.check(ranges.map { text.substring(it) }.toSet())
            sp.misspelled = ranges.mapNotNull { r -> text.substring(r).let { w -> if (w in sp.ignored) null else bad[w]?.let { Triple(r, w, it) } } }
        }
    }
    return sp
}

/** A red wavy line under each of [shown]'s words, in the editor's own layout [l] (its text's top left at 0,0). */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.spellingLines(l: androidx.compose.ui.text.TextLayoutResult, shown: List<Triple<IntRange, String, List<String>>>) {
    val text = l.layoutInput.text.text
    val red = androidx.compose.ui.graphics.Color(0xFFE53935); val step = 3.dp.toPx(); val amp = 1.2.dp.toPx()
    shown.forEach { (r) ->
        if (r.last >= text.length) return@forEach
        for (line in l.getLineForOffset(r.first)..l.getLineForOffset(r.last)) {
            val a = maxOf(r.first, l.getLineStart(line)); val z = minOf(r.last + 1, l.getLineEnd(line, true))
            if (z <= a) continue
            val x1 = l.getBoundingBox(a).left; val x2 = l.getBoundingBox(z - 1).right
            val y = l.getLineBaseline(line) + 2.5.dp.toPx()
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(x1, y); var x = x1; var up = true
                while (x < x2) { val nx = minOf(x + step, x2); lineTo(nx, if (up) y - amp else y + amp); x = nx; up = !up }
            }
            drawPath(path, red, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        }
    }
}

/** The word the cursor is in looks misspelled: its suggestions (a tap puts one in) and Ignore, on the ribbon's dark. */
@androidx.compose.runtime.Composable
internal fun SpellingBar(sp: Spelling) = sp.here?.let { (r, w, suggestions) ->
    androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_spellcheck), contentDescription = androidx.compose.ui.res.stringResource(R.string.words_spelling),
            tint = androidx.compose.ui.graphics.Color(0xFFE57373), modifier = androidx.compose.ui.Modifier.size(20.dp))
        if (suggestions.isEmpty()) androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.words_no_suggestions), color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f),
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, modifier = androidx.compose.ui.Modifier.padding(horizontal = 10.dp))
        suggestions.forEach { sug -> androidx.compose.material3.TextButton(onClick = { sp.replace(r, sug) }) { androidx.compose.material3.Text(sug, color = androidx.compose.ui.graphics.Color.White) } }
        androidx.compose.material3.TextButton(onClick = { sp.ignored = sp.ignored + w }) {
            androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.words_ignore), color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f)) }
    }
}
