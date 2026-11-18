package com.kalotrapezis.drive

/**
 * Word's fields Writer keeps (a date, a reference, a sequence number: [FIELD_CITE]) brought up to date — what Word's
 * F9 does: each SEQ field ("Figure 3", "Table 2") counted again in document order, so deleting a figure renumbers the
 * ones after it, and each cross-reference (REF, PAGEREF) shows its caption or page as they are now (asked 2026-10-13,
 * Phase 2: captions and cross-references).
 */
internal object Fields {
    /** The instruction of a kept field ("SEQ Figure \* ARABIC"), or null for a run that is none. */
    fun instr(r: DocRun): String? = r.cite?.takeIf { isField(it) }?.drop(1)?.trim()

    /**
     * [doc] with its fields brought up to date: every SEQ number counted again, then each REF showing its bookmarked
     * paragraph's words as they are now and each PAGEREF its page ([pageOf] a paragraph's page; null: left as it was).
     */
    fun update(doc: OfficeDoc, pageOf: (Int) -> Int? = { null }): OfficeDoc {
        val numbered = renumber(doc)
        val marked = HashMap<String, Int>() // bookmark → its paragraph
        val labels = HashSet<String>() // those round a caption's label and number only
        numbered.paras.forEachIndexed { i, p ->
            p.keep?.parts?.firstOrNull { it.first == BOOKMARKS }?.second?.split(' ')?.forEach { marked[it] = i }
            p.keep?.parts?.firstOrNull { it.first == LABEL_BOOKMARKS }?.second?.split(' ')?.forEach { marked[it] = i; labels += it }
        }
        return numbered.copy(paras = numbered.paras.map { p ->
            if (p.runs.none { instr(it)?.let { i -> i.startsWith("REF ", true) || i.startsWith("PAGEREF ", true) } == true }) p
            else p.copy(runs = fieldsOf(p.runs) { instr ->
                val words = instr.split(Regex("\\s+"))
                val target = words.getOrNull(1)?.let { marked[it] } ?: return@fieldsOf null
                when (words[0].uppercase()) {
                    "REF" -> if (words[1] in labels) labelAndNumber(numbered.paras[target]) else numbered.paras[target].text.trim()
                    "PAGEREF" -> pageOf(target)?.toString()
                    else -> null
                }
            })
        })
    }

    /** A caption's label and number ("Figure 3"): its letters up to the end of its first SEQ field. */
    fun labelAndNumber(p: DocPara): String {
        val f = p.runs.indexOfFirst { instr(it)?.startsWith("SEQ ", true) == true }.takeIf { it >= 0 } ?: return p.text.trim()
        var e = f
        while (e + 1 < p.runs.size && p.runs[e + 1].cite == p.runs[f].cite) e++ // the field's letters may be several runs
        return p.runs.take(e + 1).joinToString("") { it.text }.trim()
    }

    /** [runs] with each kept field's letters (its runs, one field) replaced by [shown] of its instruction (null: kept). */
    private fun fieldsOf(runs: List<DocRun>, shown: (String) -> String?): List<DocRun> {
        val out = ArrayList<DocRun>()
        var k = 0
        while (k < runs.size) {
            val r = runs[k]
            val text = instr(r)?.let(shown)
            if (text == null) { out += r; k++; continue }
            var e = k + 1
            while (e < runs.size && runs[e].cite == r.cite && runs[e].image == null) e++
            out += r.copy(text = text)
            k = e
        }
        return out
    }

    /** [doc] with every SEQ field's shown number counted again. */
    // shortcut: body paragraphs only (not inside tables, notes, headers); SEQ's \s (restart at a heading level) is not followed.
    fun renumber(doc: OfficeDoc): OfficeDoc {
        val counts = HashMap<String, Int>()
        val paras = doc.paras.map { p ->
            if (p.runs.none { instr(it)?.startsWith("SEQ ", ignoreCase = true) == true }) return@map p
            val out = ArrayList<DocRun>()
            var k = 0
            while (k < p.runs.size) {
                val r = p.runs[k]
                val instr = instr(r)
                if (instr == null || !instr.startsWith("SEQ ", ignoreCase = true)) { out += r; k++; continue }
                var e = k + 1
                while (e < p.runs.size && p.runs[e].cite == r.cite && p.runs[e].image == null) e++ // one field's letters
                val words = Regex("""\\\S|"[^"]*"|\S+""").findAll(instr).map { it.value }.toList()
                val name = words.getOrNull(1)?.uppercase() ?: ""
                fun switch(s: String) = words.indexOfFirst { it.equals(s, ignoreCase = s != "\\*") }
                val n = when {
                    switch("\\r") >= 0 -> words.getOrNull(switch("\\r") + 1)?.toIntOrNull() ?: 1
                    switch("\\c") >= 0 -> counts[name] ?: 0
                    else -> (counts[name] ?: 0) + 1
                }
                counts[name] = n
                val format = words.getOrNull(switch("\\*") + 1).takeIf { switch("\\*") >= 0 }
                val shown = if (switch("\\h") >= 0) "" else number(n, format)
                out += r.copy(text = shown)
                k = e
            }
            p.copy(runs = out)
        }
        return doc.copy(paras = paras)
    }

    /** A number as a field's \* switch asks: ARABIC (1), ROMAN (IV), roman (iv), ALPHABETIC (D), alphabetic (d). */
    fun number(n: Int, format: String?): String = when (format) {
        "ROMAN" -> roman(n)
        "roman" -> roman(n).lowercase()
        "ALPHABETIC" -> letters(n)
        "alphabetic" -> letters(n).lowercase()
        else -> n.toString()
    }

    private fun letters(n: Int): String = if (n <= 0) "" else ('A' + (n - 1) % 26).toString().repeat((n - 1) / 26 + 1)
    private fun roman(n: Int): String {
        var v = n; val out = StringBuilder()
        for ((value, s) in listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC", 50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I"))
            while (v >= value) { out.append(s); v -= value }
        return out.toString()
    }
}
