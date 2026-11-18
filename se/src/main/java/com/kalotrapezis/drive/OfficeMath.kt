package com.kalotrapezis.drive

import org.w3c.dom.Element

/**
 * Word's equations (OMML, Phase 2): read into a small tree, laid out and drawn as an inline object (as a chart is), the
 * file's own XML written back untouched; Insert › Equation and Edit equation go through a linear form ("x^2", "a/b",
 * "sqrt(x)", "∑_(i=1)^n i") as Word's own linear format does.
 */
internal sealed class MathNode {
    data class Row(val items: List<MathNode>) : MathNode()
    /** Letters: [plain] upright (Word's sty="p": function names, normal text); else letters italic, as Word's maths. */
    data class Text(val text: String, val plain: Boolean = false) : MathNode()
    data class Frac(val num: MathNode, val den: MathNode, val linear: Boolean = false) : MathNode()
    data class Script(val base: MathNode, val sup: MathNode?, val sub: MathNode?, val pre: Boolean = false) : MathNode()
    data class Radical(val body: MathNode, val degree: MathNode?) : MathNode()
    /** ∑ ∫ ∏…: limits under and over ([under]) or beside it. */
    data class Nary(val op: String, val sub: MathNode?, val sup: MathNode?, val body: MathNode, val under: Boolean) : MathNode()
    data class Delim(val open: String, val close: String, val items: List<MathNode>, val sep: String = "|") : MathNode()
    data class Func(val name: MathNode, val body: MathNode) : MathNode()
    data class Accent(val char: String, val body: MathNode) : MathNode()
    data class Bar(val body: MathNode, val top: Boolean) : MathNode()
    /** Something under or over a base (Word's limLow / limUpp: "lim" with "x→0" under it). */
    data class Limit(val base: MathNode, val lim: MathNode, val low: Boolean) : MathNode()
    data class Matrix(val rows: List<List<MathNode>>) : MathNode()
    /** Lines under one another (Word's eqArr; several equations in one display). */
    data class Stack(val rows: List<MathNode>) : MathNode()
}

internal object OfficeMath {
    const val M = "http://schemas.openxmlformats.org/officeDocument/2006/math"
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    // ---- OMML → tree
    private fun Element.kids() = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
    private fun Element.kid(name: String) = kids().firstOrNull { it.localName == name }
    private fun Element.prop(pr: String, name: String) = kid(pr)?.kid(name)?.getAttributeNS(M, "val")
    private fun row(nodes: List<MathNode>) = nodes.singleOrNull() ?: MathNode.Row(nodes)

    /** An m:oMath, m:oMathPara or any argument (m:e, m:num…): what it holds. */
    fun read(e: Element): MathNode = when (e.localName) {
        "oMathPara" -> e.kids().filter { it.localName == "oMath" }.map(::read).let { if (it.size == 1) it[0] else MathNode.Stack(it) }
        else -> row(e.kids().mapNotNull(::node))
    }

    private fun arg(e: Element, name: String) = e.kid(name)?.let(::read)
    private fun on(v: String?) = v != null && v != "0" && v != "off" && v != "false"

    private fun node(e: Element): MathNode? = if (e.namespaceURI == W) when (e.localName) {
        "r" -> e.kids().filter { it.localName == "t" }.joinToString("") { it.textContent }.takeIf { it.isNotEmpty() }?.let { MathNode.Text(it, plain = true) }
        else -> null
    } else when (e.localName) {
        "r" -> {
            val text = e.kids().filter { it.localName == "t" }.joinToString("") { it.textContent }
            val pr = e.kid("rPr")
            if (text.isEmpty()) null else MathNode.Text(text, plain = pr?.kid("nor") != null || pr?.kid("sty")?.getAttributeNS(M, "val") == "p")
        }
        "f" -> MathNode.Frac(arg(e, "num") ?: MathNode.Row(emptyList()), arg(e, "den") ?: MathNode.Row(emptyList()), e.prop("fPr", "type") == "lin")
        "sSup" -> MathNode.Script(arg(e, "e") ?: MathNode.Row(emptyList()), arg(e, "sup"), null)
        "sSub" -> MathNode.Script(arg(e, "e") ?: MathNode.Row(emptyList()), null, arg(e, "sub"))
        "sSubSup" -> MathNode.Script(arg(e, "e") ?: MathNode.Row(emptyList()), arg(e, "sup"), arg(e, "sub"))
        "sPre" -> MathNode.Script(arg(e, "e") ?: MathNode.Row(emptyList()), arg(e, "sup"), arg(e, "sub"), pre = true)
        "rad" -> MathNode.Radical(arg(e, "e") ?: MathNode.Row(emptyList()), if (on(e.prop("radPr", "degHide"))) null else arg(e, "deg")?.takeIf { it != MathNode.Row(emptyList()) })
        "nary" -> MathNode.Nary(e.prop("naryPr", "chr") ?: "∫",
            if (on(e.prop("naryPr", "subHide"))) null else arg(e, "sub")?.takeIf { it != MathNode.Row(emptyList()) },
            if (on(e.prop("naryPr", "supHide"))) null else arg(e, "sup")?.takeIf { it != MathNode.Row(emptyList()) },
            arg(e, "e") ?: MathNode.Row(emptyList()),
            // Word: limits under and over a ∑ by default, beside an ∫ (its own default is the document's; this is Word's usual).
            e.prop("naryPr", "limLoc")?.let { it == "undOvr" } ?: (e.prop("naryPr", "chr") ?: "∫") !in INTEGRALS)
        "d" -> MathNode.Delim(e.prop("dPr", "begChr") ?: "(", e.prop("dPr", "endChr") ?: ")", e.kids().filter { it.localName == "e" }.map(::read), e.prop("dPr", "sepChr") ?: "|")
        "func" -> MathNode.Func(arg(e, "fName")?.let(::plainText) ?: MathNode.Row(emptyList()), arg(e, "e") ?: MathNode.Row(emptyList()))
        "acc" -> MathNode.Accent(e.prop("accPr", "chr") ?: "\u0302", arg(e, "e") ?: MathNode.Row(emptyList()))
        "bar" -> MathNode.Bar(arg(e, "e") ?: MathNode.Row(emptyList()), e.prop("barPr", "pos") == "top")
        "limLow", "limUpp" -> MathNode.Limit(arg(e, "e") ?: MathNode.Row(emptyList()), arg(e, "lim") ?: MathNode.Row(emptyList()), e.localName == "limLow")
        "groupChr" -> (e.prop("groupChrPr", "pos") == "top").let { top -> MathNode.Limit(arg(e, "e") ?: MathNode.Row(emptyList()), MathNode.Text(e.prop("groupChrPr", "chr") ?: "⏟", true), !top) }
        "m" -> MathNode.Matrix(e.kids().filter { it.localName == "mr" }.map { r -> r.kids().filter { it.localName == "e" }.map(::read) })
        "eqArr" -> MathNode.Stack(e.kids().filter { it.localName == "e" }.map(::read))
        "box", "borderBox", "phant", "oMath" -> arg(e, "e") ?: read(e)
        else -> if (e.localName.endsWith("Pr")) null else e.kids().mapNotNull(::node).takeIf { it.isNotEmpty() }?.let(::row)
    }

    /** A function's name is upright even where the file left it italic ("sin", "lim"). */
    private fun plainText(n: MathNode): MathNode = when (n) {
        is MathNode.Text -> n.copy(plain = true)
        is MathNode.Row -> MathNode.Row(n.items.map(::plainText))
        is MathNode.Limit -> n.copy(base = plainText(n.base))
        else -> n
    }

    /** How letters are measured when equations are read: the phone's fonts once Writer has started (MathDrawable). */
    @Volatile var measure: MathMeasure = MathBox.GUESS

    /** An equation as Writer holds it: an object in the text as big as it is drawn at [sizePt], its XML [raw]. */
    fun image(key: String, math: MathNode, raw: String, sizePt: Float): DocImage {
        val box = MathBox.of(math, sizePt, measure)
        return DocImage(key, null, box.width.coerceAtLeast(1f), box.height.coerceAtLeast(1f), raw, math = math)
    }

    /** OMML's text, parsed (a part of a document's XML, with or without its m: namespace declared). */
    fun parse(omml: String): MathNode? = runCatching {
        val head = omml.substringBefore('>')
        val xml = omml.replaceFirst(Regex("""^<(m:\w+)"""), "<$1" + (if ("xmlns:m=" in head) "" else " xmlns:m=\"$M\"") + (if ("xmlns:w=" in head) "" else " xmlns:w=\"$W\""))
        val f = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        read(f.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(xml))).documentElement)
    }.getOrNull()

    val INTEGRALS = setOf("∫", "∬", "∭", "∮", "∯", "∰")
    val NARY_OPS = setOf("∑", "∏", "∐", "⋃", "⋂", "⋀", "⋁") + INTEGRALS

    // ---- tree → OMML
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun v(name: String, value: String) = """<m:$name m:val="${esc(value).replace("\"", "&quot;")}"/>"""
    private const val FONT = """<w:rPr><w:rFonts w:ascii="Cambria Math" w:hAnsi="Cambria Math"/></w:rPr>"""

    fun omml(n: MathNode): String = when (n) {
        is MathNode.Row -> n.items.joinToString("") { omml(it) }
        is MathNode.Text -> "<m:r>" + (if (n.plain) "<m:rPr>${v("sty", "p")}</m:rPr>" else "") + FONT + "<m:t xml:space=\"preserve\">${esc(n.text)}</m:t></m:r>"
        is MathNode.Frac -> "<m:f>" + (if (n.linear) "<m:fPr>${v("type", "lin")}</m:fPr>" else "") + "<m:num>${omml(n.num)}</m:num><m:den>${omml(n.den)}</m:den></m:f>"
        is MathNode.Script -> when {
            n.pre -> "<m:sPre><m:sub>${n.sub?.let(::omml).orEmpty()}</m:sub><m:sup>${n.sup?.let(::omml).orEmpty()}</m:sup><m:e>${omml(n.base)}</m:e></m:sPre>"
            n.sup != null && n.sub != null -> "<m:sSubSup><m:e>${omml(n.base)}</m:e><m:sub>${omml(n.sub)}</m:sub><m:sup>${omml(n.sup)}</m:sup></m:sSubSup>"
            n.sup != null -> "<m:sSup><m:e>${omml(n.base)}</m:e><m:sup>${omml(n.sup)}</m:sup></m:sSup>"
            else -> "<m:sSub><m:e>${omml(n.base)}</m:e><m:sub>${n.sub?.let(::omml).orEmpty()}</m:sub></m:sSub>"
        }
        is MathNode.Radical -> "<m:rad>" + (if (n.degree == null) "<m:radPr>${v("degHide", "1")}</m:radPr><m:deg/>" else "<m:deg>${omml(n.degree)}</m:deg>") + "<m:e>${omml(n.body)}</m:e></m:rad>"
        is MathNode.Nary -> "<m:nary><m:naryPr>" + v("chr", n.op) + v("limLoc", if (n.under) "undOvr" else "subSup") +
            (if (n.sub == null) v("subHide", "1") else "") + (if (n.sup == null) v("supHide", "1") else "") +
            "</m:naryPr><m:sub>${n.sub?.let(::omml).orEmpty()}</m:sub><m:sup>${n.sup?.let(::omml).orEmpty()}</m:sup><m:e>${omml(n.body)}</m:e></m:nary>"
        is MathNode.Delim -> "<m:d><m:dPr>" + (if (n.open != "(") v("begChr", n.open) else "") + (if (n.close != ")") v("endChr", n.close) else "") +
            (if (n.sep != "|") v("sepChr", n.sep) else "") + "</m:dPr>" + n.items.joinToString("") { "<m:e>${omml(it)}</m:e>" } + "</m:d>"
        is MathNode.Func -> "<m:func><m:fName>${omml(n.name)}</m:fName><m:e>${omml(n.body)}</m:e></m:func>"
        is MathNode.Accent -> "<m:acc><m:accPr>${v("chr", n.char)}</m:accPr><m:e>${omml(n.body)}</m:e></m:acc>"
        is MathNode.Bar -> "<m:bar><m:barPr>${v("pos", if (n.top) "top" else "bot")}</m:barPr><m:e>${omml(n.body)}</m:e></m:bar>"
        is MathNode.Limit -> (if (n.low) "limLow" else "limUpp").let { t -> "<m:$t><m:e>${omml(n.base)}</m:e><m:lim>${omml(n.lim)}</m:lim></m:$t>" }
        is MathNode.Matrix -> "<m:m>" + n.rows.joinToString("") { r -> "<m:mr>" + r.joinToString("") { "<m:e>${omml(it)}</m:e>" } + "</m:mr>" } + "</m:m>"
        is MathNode.Stack -> "<m:eqArr>" + n.rows.joinToString("") { "<m:e>${omml(it)}</m:e>" } + "</m:eqArr>"
    }

    /** A whole equation as Word writes one: in a line of text, or a display of its own (centred, its own paragraph). */
    fun equation(n: MathNode, display: Boolean): String {
        val math = "<m:oMath>${omml(n)}</m:oMath>"
        return if (display) """<m:oMathPara xmlns:m="$M" xmlns:w="$W">$math</m:oMathPara>""" else math.replaceFirst("<m:oMath>", """<m:oMath xmlns:m="$M" xmlns:w="$W">""")
    }

    // ---- LibreOffice's formulas (an object holding MathML) ↔ tree

    /** A formula object's content.xml (MathML) as the tree; null when it is not MathML. */
    fun fromMathML(xml: ByteArray): MathNode? = runCatching {
        val f = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val root = f.newDocumentBuilder().parse(java.io.ByteArrayInputStream(xml)).documentElement
        if (root.localName != "math") null else mml(root)
    }.getOrNull()

    private val LARGE = NARY_OPS
    private fun mml(e: Element): MathNode {
        fun kids() = e.kids().filter { it.localName != "annotation" && it.localName != "annotation-xml" }.map(::mml)
        fun k(i: Int) = e.kids().getOrNull(i)?.let(::mml) ?: MathNode.Row(emptyList())
        fun text() = e.textContent.trim().replace("\u2061", "").replace("\u2062", "").replace("\u2063", "")
        fun op(n: MathNode) = (n as? MathNode.Text)?.text?.takeIf { it in LARGE }
        return when (e.localName) {
            "math", "semantics", "mstyle", "mpadded", "mphantom", "menclose", "mrow", "merror" -> {
                val items = kids()
                // A large operator with its limits takes what follows it as its body; a fenced row is brackets.
                val out = ArrayList<MathNode>()
                var i = 0
                while (i < items.size) {
                    val it = items[i]
                    if (it is MathNode.Nary && it.body == MathNode.Row(emptyList()) && i + 1 < items.size) { out += it.copy(body = items[i + 1]); i += 2; continue }
                    if (items.getOrNull(i + 1) == MathNode.Text("\u2061", true) && i + 2 < items.size) { out += MathNode.Func(it, items[i + 2]); i += 3; continue }
                    if (it == MathNode.Text("\u2061", true)) { i++; continue }
                    if (it is MathNode.Text && it.plain && it.text in LARGE && i + 1 < items.size) { out += MathNode.Nary(it.text, null, null, items[i + 1], it.text !in INTEGRALS); i += 2; continue }
                    // Letters MathML gives one each (italic) run together again, as Word holds them.
                    val last = out.lastOrNull()
                    if (it is MathNode.Text && !it.plain && last is MathNode.Text && !last.plain) out[out.size - 1] = MathNode.Text(last.text + it.text) else out += it
                    i++
                }
                val first = e.kids().firstOrNull(); val last = e.kids().lastOrNull()
                if (e.localName == "mrow" && out.size >= 2 && first?.localName == "mo" && last?.localName == "mo" && first.getAttribute("fence") == "true" && last.getAttribute("fence") == "true")
                    MathNode.Delim(first.textContent.trim(), last.textContent.trim(), listOf(row(out.subList(1, out.size - 1))))
                else row(out)
            }
            "mi" -> MathNode.Text(text(), plain = e.getAttribute("mathvariant") == "normal" || text().length > 1)
            "mn" -> MathNode.Text(text())
            "mo" -> if (e.textContent.trim() == "\u2061") MathNode.Text("\u2061", plain = true) // function application: joined below
                else text().takeIf { it.isNotEmpty() }?.let { MathNode.Text(it, plain = true) } ?: MathNode.Row(emptyList())
            "mtext", "ms" -> MathNode.Text(e.textContent, plain = true)
            "mspace" -> MathNode.Text(" ", plain = true)
            "mfrac" -> MathNode.Frac(k(0), k(1), linear = e.getAttribute("bevelled") == "true")
            "msup" -> op(k(0))?.let { MathNode.Nary(it, null, k(1), MathNode.Row(emptyList()), false) } ?: MathNode.Script(k(0), k(1), null)
            "msub" -> op(k(0))?.let { MathNode.Nary(it, k(1), null, MathNode.Row(emptyList()), false) } ?: MathNode.Script(k(0), null, k(1))
            "msubsup" -> op(k(0))?.let { MathNode.Nary(it, k(1), k(2), MathNode.Row(emptyList()), false) } ?: MathNode.Script(k(0), k(2), k(1))
            "munderover" -> op(k(0))?.let { MathNode.Nary(it, k(1), k(2), MathNode.Row(emptyList()), true) } ?: MathNode.Limit(MathNode.Limit(k(0), k(1), true), k(2), false)
            "munder" -> op(k(0))?.let { MathNode.Nary(it, k(1), null, MathNode.Row(emptyList()), true) }
                ?: if ((k(1) as? MathNode.Text)?.text.let { it == "_" || it == "\u0332" || it == "‾" }) MathNode.Bar(k(0), false) else MathNode.Limit(k(0), k(1), true)
            "mover" -> op(k(0))?.let { MathNode.Nary(it, null, k(1), MathNode.Row(emptyList()), true) } ?: run {
                val mark = (k(1) as? MathNode.Text)?.text
                when {
                    mark == "¯" || mark == "‾" || mark == "\u0305" -> MathNode.Bar(k(0), true)
                    e.getAttribute("accent") == "true" || mark != null && mark.length == 1 && mark in ACCENT_MARKS -> MathNode.Accent(ACCENT_MARKS[mark] ?: mark ?: "\u0302", k(0))
                    else -> MathNode.Limit(k(0), k(1), false)
                }
            }
            "msqrt" -> MathNode.Radical(row(kids()), null)
            "mroot" -> MathNode.Radical(k(0), k(1))
            "mfenced" -> MathNode.Delim(e.getAttribute("open").ifEmpty { "(" }, e.getAttribute("close").ifEmpty { ")" }, kids(), e.getAttribute("separators").trim().take(1).ifEmpty { "," })
            "mtable" -> e.kids().filter { it.localName == "mtr" || it.localName == "mlabeledtr" }.map { r -> r.kids().filter { it.localName == "mtd" }.map(::mml) }.let { rows ->
                if (rows.all { it.size == 1 }) MathNode.Stack(rows.map { it[0] }) else MathNode.Matrix(rows) }
            "mtd", "mtr" -> row(kids())
            "mmultiscripts" -> {
                val all = e.kids(); val pre = all.indexOfFirst { it.localName == "mprescripts" }
                if (pre >= 0) MathNode.Script(mml(all[0]), all.getOrNull(pre + 2)?.let(::mml), all.getOrNull(pre + 1)?.let(::mml), pre = true)
                else MathNode.Script(mml(all[0]), all.getOrNull(2)?.let(::mml), all.getOrNull(1)?.let(::mml))
            }
            else -> row(kids())
        }
    }
    /** Spacing marks MathML puts over a letter, as Word's combining accents. */
    private val ACCENT_MARKS = mapOf("^" to "\u0302", "ˆ" to "\u0302", "~" to "\u0303", "˜" to "\u0303", "˙" to "\u0307", "¨" to "\u0308", "→" to "\u20D7", "⃗" to "\u20D7",
        "´" to "\u0301", "`" to "\u0300", "ˇ" to "\u030C", "˘" to "\u0306", "\u0302" to "\u0302", "\u0303" to "\u0303", "\u0307" to "\u0307", "\u0308" to "\u0308")

    /** The tree as a formula object's content.xml (MathML, as LibreOffice reads it); [display]: a block of its own. */
    fun mathml(n: MathNode, display: Boolean): String =
        """<?xml version="1.0" encoding="UTF-8"?><math xmlns="http://www.w3.org/1998/Math/MathML" display="${if (display) "block" else "inline"}"><semantics>${mrow(n)}</semantics></math>"""

    private fun mrow(n: MathNode): String = "<mrow>${ml(n)}</mrow>"
    private fun ml(n: MathNode): String = when (n) {
        is MathNode.Row -> n.items.joinToString("") { ml(it) }
        is MathNode.Text -> tokens(n)
        is MathNode.Frac -> if (n.linear) ml(n.num) + "<mo>/</mo>" + ml(n.den) else "<mfrac>${mrow(n.num)}${mrow(n.den)}</mfrac>"
        is MathNode.Script -> when {
            n.pre -> "<mmultiscripts>${mrow(n.base)}<mprescripts/>${n.sub?.let(::mrow) ?: "<none/>"}${n.sup?.let(::mrow) ?: "<none/>"}</mmultiscripts>"
            n.sup != null && n.sub != null -> "<msubsup>${mrow(n.base)}${mrow(n.sub)}${mrow(n.sup)}</msubsup>"
            n.sup != null -> "<msup>${mrow(n.base)}${mrow(n.sup)}</msup>"
            else -> "<msub>${mrow(n.base)}${n.sub?.let(::mrow) ?: "<mrow/>"}</msub>"
        }
        is MathNode.Radical -> if (n.degree == null) "<msqrt>${ml(n.body)}</msqrt>" else "<mroot>${mrow(n.body)}${mrow(n.degree)}</mroot>"
        is MathNode.Nary -> {
            val op = "<mo stretchy=\"false\">${esc(n.op)}</mo>"
            val limits = when {
                n.sub != null && n.sup != null -> (if (n.under) "munderover" else "msubsup").let { "<$it>$op${mrow(n.sub)}${mrow(n.sup)}</$it>" }
                n.sub != null -> (if (n.under) "munder" else "msub").let { "<$it>$op${mrow(n.sub)}</$it>" }
                n.sup != null -> (if (n.under) "mover" else "msup").let { "<$it>$op${mrow(n.sup)}</$it>" }
                else -> op
            }
            "<mrow>$limits${mrow(n.body)}</mrow>"
        }
        is MathNode.Delim -> "<mrow><mo fence=\"true\" form=\"prefix\" stretchy=\"true\">${esc(n.open)}</mo>" + n.items.joinToString("<mo separator=\"true\">${esc(n.sep)}</mo>") { mrow(it) } +
            "<mo fence=\"true\" form=\"postfix\" stretchy=\"true\">${esc(n.close)}</mo></mrow>"
        is MathNode.Func -> "<mrow>${ml(plainText(n.name))}<mo>\u2061</mo>${mrow(n.body)}</mrow>"
        is MathNode.Accent -> "<mover accent=\"true\">${mrow(n.body)}<mo>${esc(ACCENT_MARKS.entries.firstOrNull { it.value == n.char && it.key.length == 1 && it.key[0].code < 0x300 }?.key ?: n.char)}</mo></mover>"
        is MathNode.Bar -> if (n.top) "<mover accent=\"true\">${mrow(n.body)}<mo>¯</mo></mover>" else "<munder accentunder=\"true\">${mrow(n.body)}<mo>_</mo></munder>"
        is MathNode.Limit -> (if (n.low) "munder" else "mover").let { "<$it>${mrow(n.base)}${mrow(n.lim)}</$it>" }
        is MathNode.Matrix -> "<mtable>" + n.rows.joinToString("") { r -> "<mtr>" + r.joinToString("") { "<mtd>${mrow(it)}</mtd>" } + "</mtr>" } + "</mtable>"
        is MathNode.Stack -> "<mtable>" + n.rows.joinToString("") { "<mtr><mtd>${mrow(it)}</mtd></mtr>" } + "</mtable>"
    }
    /** Letters, numbers and signs as MathML's tokens: a word upright ([MathNode.Text.plain]) one mi; else a letter each. */
    private fun tokens(t: MathNode.Text): String {
        val out = StringBuilder()
        Regex("""\d+(?:\.\d+)?|\p{L}+|\s+|.""").findAll(t.text).forEach { m ->
            val v = m.value
            when {
                v.isBlank() -> out.append("<mspace width=\"0.25em\"/>")
                v[0].isDigit() -> out.append("<mn>${esc(v)}</mn>")
                v[0].isLetter() -> if (t.plain) out.append("<mi mathvariant=\"normal\">${esc(v)}</mi>") else v.forEach { out.append("<mi>${esc(it.toString())}</mi>") }
                else -> out.append("<mo>${esc(v)}</mo>")
            }
        }
        return out.toString()
    }

    // ---- tree → words (Review › Read aloud says an equation as Word's Read Aloud does)

    /** [n] in words, Greek ([greek]) or English: "x ίσον a συν b διά 2". */
    fun spoken(n: MathNode, greek: Boolean): String {
        fun w(en: String, el: String) = if (greek) el else en
        fun say(n: MathNode): String = when (n) {
            is MathNode.Row -> n.items.joinToString(" ") { say(it) }
            is MathNode.Text -> n.text.map { c -> SPOKEN[c]?.let { (en, el) -> " ${w(en, el)} " } ?: c.toString() }.joinToString("")
            is MathNode.Frac -> "${say(n.num)} ${w("over", "διά")} ${say(n.den)}"
            is MathNode.Script -> say(n.base) + (n.sub?.let { " ${w("sub", "δείκτης")} ${say(it)}" } ?: "") + (n.sup?.let { sup ->
                when ((sup as? MathNode.Text)?.text) { "2" -> " " + w("squared", "στο τετράγωνο"); "3" -> " " + w("cubed", "στον κύβο"); else -> " ${w("to the power of", "εις την")} ${say(sup)}" } } ?: "")
            is MathNode.Radical -> if (n.degree == null) "${w("the square root of", "η τετραγωνική ρίζα του")} ${say(n.body)}" else "${w("the root", "η ρίζα τάξης")} ${say(n.degree)} ${w("of", "του")} ${say(n.body)}"
            is MathNode.Nary -> (if (n.op in INTEGRALS) w("the integral", "το ολοκλήρωμα") else if (n.op == "∏") w("the product", "το γινόμενο") else w("the sum", "το άθροισμα")) +
                (n.sub?.let { " ${w("from", "από")} ${say(it)}" } ?: "") + (n.sup?.let { " ${w("to", "έως")} ${say(it)}" } ?: "") + " ${w("of", "του")} ${say(n.body)}"
            is MathNode.Delim -> n.items.joinToString(", ") { say(it) }
            is MathNode.Func -> "${say(n.name)} ${say(n.body)}"
            is MathNode.Accent -> say(n.body)
            is MathNode.Bar -> say(n.body)
            is MathNode.Limit -> "${say(n.base)} ${say(n.lim)}"
            is MathNode.Matrix -> n.rows.joinToString("; ") { r -> r.joinToString(", ") { say(it) } }
            is MathNode.Stack -> n.rows.joinToString("; ") { say(it) }
        }
        return say(n).replace(Regex("\\s+"), " ").trim()
    }
    private val SPOKEN = mapOf('=' to ("equals" to "ίσον"), '+' to ("plus" to "συν"), '-' to ("minus" to "πλην"), '−' to ("minus" to "πλην"),
        '±' to ("plus or minus" to "συν πλην"), '×' to ("times" to "επί"), '⋅' to ("times" to "επί"), '÷' to ("divided by" to "διά"), '/' to ("over" to "διά"),
        '<' to ("is less than" to "μικρότερο από"), '>' to ("is greater than" to "μεγαλύτερο από"), '≤' to ("is at most" to "μικρότερο ή ίσο του"),
        '≥' to ("is at least" to "μεγαλύτερο ή ίσο του"), '≠' to ("is not equal to" to "διάφορο του"), '≈' to ("is about" to "περίπου ίσο με"),
        '→' to ("tends to" to "τείνει στο"), '∞' to ("infinity" to "άπειρο"), '∈' to ("in" to "ανήκει στο"))

    // ---- tree → linear form (what Edit equation shows)
    private fun atom(n: MathNode) = n is MathNode.Text && (n.text.length == 1 || n.text.all { it.isDigit() || it == '.' } || n.text.all { it.isLetter() }) ||
        n is MathNode.Delim || n is MathNode.Radical || n is MathNode.Accent || n is MathNode.Matrix
    private fun group(n: MathNode) = linear(n).let { if (atom(n) || it.isEmpty()) it else "($it)" }

    fun linear(n: MathNode): String = when (n) {
        is MathNode.Row -> n.items.joinToString("") { linear(it) }
        is MathNode.Text -> n.text
        is MathNode.Frac -> group(n.num) + "/" + group(n.den)
        is MathNode.Script -> if (n.pre) "_" + (n.sub?.let(::group) ?: "()") + "^" + (n.sup?.let(::group) ?: "()") + group(n.base)
            else group(n.base) + (n.sub?.let { "_" + group(it) } ?: "") + (n.sup?.let { "^" + group(it) } ?: "")
        is MathNode.Radical -> if (n.degree == null) "sqrt(${linear(n.body)})" else "root(${linear(n.degree)},${linear(n.body)})"
        is MathNode.Nary -> n.op + (n.sub?.let { "_" + group(it) } ?: "") + (n.sup?.let { "^" + group(it) } ?: "") + " " + linear(n.body)
        is MathNode.Delim -> n.open + n.items.joinToString(n.sep) { linear(it) } + n.close
        is MathNode.Func -> linear(n.name) + (if (n.body is MathNode.Delim) "" else " ") + linear(n.body)
        is MathNode.Accent -> (ACCENTS.entries.firstOrNull { it.value == n.char }?.key ?: "hat") + "(${linear(n.body)})"
        is MathNode.Bar -> (if (n.top) "overline" else "underline") + "(${linear(n.body)})"
        is MathNode.Limit -> linear(n.base) + (if (n.low) "_" else "^") + group(n.lim)
        is MathNode.Matrix -> "matrix(" + n.rows.joinToString("@") { r -> r.joinToString("&") { linear(it) } } + ")"
        is MathNode.Stack -> "eqarray(" + n.rows.joinToString("@") { linear(it) } + ")"
    }

    // ---- linear form → tree
    val ACCENTS = mapOf("hat" to "\u0302", "tilde" to "\u0303", "dot" to "\u0307", "ddot" to "\u0308", "vec" to "\u20D7", "acute" to "\u0301", "grave" to "\u0300", "check" to "\u030C", "breve" to "\u0306")
    private val NAMES = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "zeta" to "ζ", "eta" to "η", "theta" to "θ", "iota" to "ι",
        "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π", "rho" to "ρ", "sigma" to "σ", "tau" to "τ",
        "upsilon" to "υ", "phi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω", "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ",
        "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω", "infty" to "∞", "pm" to "±", "mp" to "∓", "times" to "×",
        "cdot" to "⋅", "div" to "÷", "le" to "≤", "ge" to "≥", "ne" to "≠", "approx" to "≈", "equiv" to "≡", "to" to "→", "rightarrow" to "→",
        "leftarrow" to "←", "Rightarrow" to "⇒", "in" to "∈", "notin" to "∉", "subset" to "⊂", "cup" to "∪", "cap" to "∩", "partial" to "∂",
        "nabla" to "∇", "forall" to "∀", "exists" to "∃", "degree" to "°", "sum" to "∑", "prod" to "∏", "int" to "∫", "iint" to "∬", "oint" to "∮")
    private val SYMBOLS = listOf("<=" to "≤", ">=" to "≥", "!=" to "≠", "+-" to "±", "->" to "→", "~=" to "≅", "*" to "⋅")
    private val NARY = setOf("∑", "∏", "∐", "⋃", "⋂") + INTEGRALS
    private val FUNCS = setOf("sin", "cos", "tan", "cot", "sec", "csc", "sinh", "cosh", "tanh", "arcsin", "arccos", "arctan", "log", "ln", "exp", "lim", "max", "min", "det", "sup", "inf", "gcd", "arg", "deg", "dim", "lg")
    private val OPENS = mapOf('(' to ')', '[' to ']', '{' to '}')
    private val SIGNS = setOf('+', '-', '−', '=', '<', '>', '≤', '≥', '≠', '±', '∓', '×', '⋅', '÷', '→', '≈', ',', '(')
    private val ENDS = setOf('+', '-', '=', '<', '>', ',', '≤', '≥', '≠', '±', '→', '≈', '−', '∓', '×', '⋅', '÷')

    /** Word's linear form, a useful part of it: x^2, x_i, a/b, sqrt(x), root(n,x), ∑_(i=1)^n, \alpha, sin x, lim_(x→0), hat(x), matrix(a&b@c&d). */
    fun fromLinear(src: String): MathNode {
        var s = src.trim()
        for ((a, b) in SYMBOLS) s = s.replace(a, b)
        val p = LinearParser(s)
        return p.expression(null)
    }

    private class LinearParser(val s: String) {
        var i = 0
        fun peek() = s.getOrNull(i)
        fun skipSpaces() { while (peek() == ' ') i++ }

        /** Terms up to [stop] (a closing bracket, ',', '&', '@') or the end. */
        fun expression(stop: Set<Char>?): MathNode {
            val items = ArrayList<MathNode>()
            while (true) {
                skipSpaces()
                val c = peek() ?: break
                if (stop != null && c in stop) break
                if (stop == null && c in ")]}" ) { items += MathNode.Text(c.toString()); i++; continue } // a stray closing bracket: kept as a letter
                items += term() ?: break
            }
            return merge(items)
        }

        /** An operand with its scripts and fraction bar: x^2, a/b, x_i^2. */
        fun term(): MathNode? {
            var base = primary() ?: return null
            while (true) {
                val c = peek()
                when {
                    c == '^' || c == '_' -> {
                        i++
                        val arg = operand()
                        base = when {
                            base is MathNode.Nary && base.body == EMPTY -> if (c == '^') base.copy(sup = arg) else base.copy(sub = arg)
                            base is MathNode.Text && base.plain && base.text in FUNCS && c == '_' -> MathNode.Limit(base, arg, true)
                            base is MathNode.Script && !base.pre && (if (c == '^') base.sup == null else base.sub == null) -> if (c == '^') base.copy(sup = arg) else base.copy(sub = arg)
                            else -> MathNode.Script(base, if (c == '^') arg else null, if (c == '_') arg else null)
                        }
                    }
                    c == '/' -> { i++; base = MathNode.Frac(ungroup(base), ungroup(term() ?: EMPTY)) } // (a+b)/(c+d): the brackets only group
                    else -> break
                }
            }
            // ∑ and a function's name take what follows as their body: the terms up to a +, − or = at this level
            // (sin x, log(x), lim_(x→0) f(x)).
            fun body(): MathNode? {
                val items = ArrayList<MathNode>()
                while (true) { skipSpaces(); val c = peek() ?: break; if (c in ENDS || c in ")]}&@,") break; items += term() ?: break }
                return if (items.isEmpty()) null else merge(items)
            }
            if (base is MathNode.Nary && base.body == EMPTY) base = base.copy(body = body() ?: EMPTY)
            else if ((base as? MathNode.Text)?.let { it.plain && it.text in FUNCS } == true || (base as? MathNode.Limit)?.base?.let { it is MathNode.Text && it.plain && it.text in FUNCS } == true)
                body()?.let { base = MathNode.Func(base, it) }
            return base
        }

        /** What ^ _ / take: one letter, a number, a word's letter, or a bracket's inside (the brackets only group). */
        fun operand(): MathNode = ungroup(primary() ?: EMPTY)
        fun ungroup(n: MathNode) = if (n is MathNode.Delim && n.open == "(" && n.close == ")" && n.items.size == 1) n.items[0] else n

        fun primary(): MathNode? {
            skipSpaces()
            val c = peek() ?: return null
            if (c in OPENS) {
                i++
                val close = OPENS.getValue(c)
                val inside = expression(setOf(close, ')', ']', '}'))
                if (peek() != null) i++
                return MathNode.Delim(c.toString(), close.toString(), listOf(inside))
            }
            if (c == '|') { // |x| when another bar closes it
                val end = s.indexOf('|', i + 1)
                if (end > i) { i++; val inside = LinearParser(s.substring(i, end)).expression(null); i = end + 1; return MathNode.Delim("|", "|", listOf(inside)) }
            }
            if (c == '√' || c == '∛' || c == '∜') { i++; val deg = when (c) { '∛' -> MathNode.Text("3"); '∜' -> MathNode.Text("4"); else -> null }; return MathNode.Radical(operand(), deg) }
            if (c.toString() in NARY) { i++; return MathNode.Nary(c.toString(), null, null, EMPTY, c.toString() !in INTEGRALS) }
            if (c == '\\') { i++; return word(true) }
            if (c.isLetter()) return word(false)
            if (c.isDigit() || c == '.' && s.getOrNull(i + 1)?.isDigit() == true) {
                val a = i; while (peek()?.let { it.isDigit() || it == '.' } == true) i++
                return MathNode.Text(s.substring(a, i))
            }
            i++
            return MathNode.Text(if (c == '-') "−" else c.toString(), plain = true)
        }

        /** A word: a command (sqrt, root, matrix, hat…), a function's name, a Greek letter's name, or letters one by one. */
        fun word(escaped: Boolean): MathNode {
            val a = i; while (peek()?.isLetter() == true) i++
            val w = s.substring(a, i)
            fun args(): List<MathNode> { // name( a , b ) or name(a & b @ c & d)
                if (peek() != '(') return listOf(operand())
                i++
                val out = ArrayList<MathNode>()
                while (true) { out += expression(setOf(',', ')', '&', '@')); val c = peek() ?: break; i++; if (c == ')') break; if (c == '@') out += SEP }
                return out
            }
            when {
                w == "sqrt" -> return MathNode.Radical(args().first(), null)
                w == "root" -> args().let { return if (it.size >= 2) MathNode.Radical(it[1], it[0]) else MathNode.Radical(it[0], null) }
                w == "matrix" || w == "eqarray" -> {
                    val rows = ArrayList<List<MathNode>>(); var row = ArrayList<MathNode>()
                    for (x in args()) if (x === SEP) { rows += row; row = ArrayList() } else row += x
                    rows += row
                    return if (w == "matrix") MathNode.Matrix(rows) else MathNode.Stack(rows.map { merge(it) })
                }
                w in ACCENTS -> return MathNode.Accent(ACCENTS.getValue(w), args().first())
                w == "overline" || w == "underline" || w == "bar" -> return MathNode.Bar(args().first(), w != "underline")
                w in FUNCS -> return MathNode.Text(w, plain = true)
                w in NAMES -> NAMES.getValue(w).let { g -> return if (g in NARY) MathNode.Nary(g, null, null, EMPTY, g !in INTEGRALS) else MathNode.Text(g) }
            }
            // Letters one by one (Word's ab is a times b); an unknown \name stays as its letters, upright.
            if (escaped) return MathNode.Text(w, plain = true)
            i = a + 1
            return MathNode.Text(w.take(1))
        }

        /** Neighbouring letters and numbers joined into one run, as Word keeps them. */
        fun merge(items: List<MathNode>): MathNode {
            val out = ArrayList<MathNode>()
            for ((k, n0) in items.withIndex()) {
                // A minus or plus that starts a term (−b, after =, after another sign) is the number's sign: written with it, not spaced.
                val prev = out.lastOrNull()
                val n = if (n0 is MathNode.Text && n0.plain && n0.text in setOf("−", "+", "±") && items.getOrNull(k + 1).let { it is MathNode.Text && !it.plain } &&
                    (prev == null || prev is MathNode.Text && prev.text.last() in SIGNS)) MathNode.Text(n0.text) else n0
                val last = out.lastOrNull()
                if (n is MathNode.Text && last is MathNode.Text && n.plain == last.plain && !(n.plain && (n.text in FUNCS || last.text in FUNCS))) out[out.lastIndex] = MathNode.Text(last.text + n.text, n.plain)
                else out += n
            }
            return out.singleOrNull() ?: MathNode.Row(out)
        }
    }
    private val EMPTY = MathNode.Row(emptyList())
    private val SEP = MathNode.Text("@") // a matrix's next row (only ever compared by identity)
}

/** How wide letters are, in points at a size: the phone's fonts on Android (MathDrawable); a fair guess in tests. */
internal fun interface MathMeasure { fun width(text: String, size: Float, italic: Boolean): Float }

/**
 * An equation laid out: its size in points (width; ascent above the baseline, descent below) and what to draw, each piece
 * placed from the equation's left edge and baseline.
 */
internal class MathBox(val width: Float, val ascent: Float, val descent: Float, val ops: List<Op>) {
    sealed class Op {
        data class Letters(val text: String, val x: Float, val y: Float, val size: Float, val italic: Boolean, val stretch: Float = 1f) : Op()
        data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val width: Float) : Op()
        data class Path(val points: List<Pair<Float, Float>>, val width: Float) : Op()
    }
    val height get() = ascent + descent
    fun moved(dx: Float, dy: Float): List<Op> = ops.map { o -> when (o) {
        is Op.Letters -> o.copy(x = o.x + dx, y = o.y + dy)
        is Op.Line -> o.copy(x1 = o.x1 + dx, y1 = o.y1 + dy, x2 = o.x2 + dx, y2 = o.y2 + dy)
        is Op.Path -> o.copy(points = o.points.map { (x, y) -> x + dx to y + dy })
    } }

    companion object {
        val GUESS = MathMeasure { t, size, _ -> t.length * size * 0.52f }

        /** [n] laid out at [size] points. */
        fun of(n: MathNode, size: Float, measure: MathMeasure = GUESS): MathBox = Layout(measure).box(n, size)
    }

    private class Layout(val m: MathMeasure) {
        fun line(size: Float) = (size * 0.05f).coerceAtLeast(0.4f)
        fun axis(size: Float) = size * 0.25f // where a fraction's bar sits above the baseline (the minus sign's height)

        fun box(n: MathNode, s: Float): MathBox = when (n) {
            is MathNode.Row -> row(n.items.map { box(it, s) }, s, n.items)
            is MathNode.Text -> text(n, s)
            is MathNode.Frac -> if (n.linear) row(listOf(box(n.num, s), text(MathNode.Text("/", true), s), box(n.den, s)), s) else {
                val k = s * 0.92f
                val top = box(n.num, k); val bottom = box(n.den, k)
                val w = maxOf(top.width, bottom.width) + s * 0.3f
                val gap = s * 0.12f; val a = axis(s)
                val ops = top.moved((w - top.width) / 2, -(a + gap + top.descent)) + bottom.moved((w - bottom.width) / 2, -a + gap + bottom.ascent) +
                    MathBox.Op.Line(s * 0.08f, -a, w - s * 0.08f, -a, line(s))
                MathBox(w, a + gap + top.height, bottom.height + gap - a, ops)
            }
            is MathNode.Script -> {
                val base = box(n.base, s); val k = s * 0.7f
                val sup = n.sup?.let { box(it, k) }; val sub = n.sub?.let { box(it, k) }
                val up = maxOf(base.ascent - (sup?.ascent ?: 0f) * 0.6f, s * 0.38f) // a script's baseline above the base's
                val down = maxOf(base.descent * 0.8f, s * 0.18f) + (sub?.ascent ?: 0f) * 0.55f
                val scripts = maxOf(sup?.width ?: 0f, sub?.width ?: 0f) + s * 0.05f
                val ops = ArrayList<MathBox.Op>()
                val baseX = if (n.pre) scripts else 0f
                val scriptX = if (n.pre) 0f else base.width + s * 0.03f
                ops += base.moved(baseX, 0f)
                sup?.let { ops += it.moved(scriptX, -up) }
                sub?.let { ops += it.moved(scriptX, down) }
                MathBox(base.width + scripts + s * 0.03f, maxOf(base.ascent, up + (sup?.ascent ?: 0f)), maxOf(base.descent, down + (sub?.descent ?: 0f)), ops)
            }
            is MathNode.Radical -> {
                val body = box(n.body, s); val deg = n.degree?.let { box(it, s * 0.55f) }
                val gap = s * 0.12f
                val top = body.ascent + gap; val bottom = body.descent
                val tick = s * 0.55f; val lead = maxOf(deg?.width?.minus(tick * 0.45f) ?: 0f, 0f)
                val x0 = lead
                // The sign: a short rise, a long fall to the bottom, up to the top, then the bar over the body.
                val path = listOf(x0 to -top * 0.35f, x0 + tick * 0.2f to -top * 0.45f, x0 + tick * 0.5f to bottom, x0 + tick to -top, x0 + tick + body.width + s * 0.1f to -top)
                val ops = ArrayList<MathBox.Op>(); ops += MathBox.Op.Path(path, line(s)); ops += body.moved(x0 + tick + s * 0.05f, 0f)
                deg?.let { ops += it.moved(0f, -top * 0.5f) }
                MathBox(x0 + tick + body.width + s * 0.15f, maxOf(top + line(s), (deg?.let { top * 0.5f + it.ascent }) ?: 0f), bottom, ops)
            }
            is MathNode.Nary -> {
                val big = s * if (n.under) 1.9f else 2.1f // the phone's ∑ and ∫ are small letters next to Cambria Math's big operators
                val op = text(MathNode.Text(n.op, true), big)
                val k = s * 0.7f
                val sub = n.sub?.let { box(it, k) }; val sup = n.sup?.let { box(it, k) }
                val body = box(n.body, s)
                val ops = ArrayList<MathBox.Op>()
                val opY = axis(s) - axis(big) // centred on the axis
                val w: Float; val asc: Float; val desc: Float
                if (n.under) {
                    val cw = maxOf(op.width, sub?.width ?: 0f, sup?.width ?: 0f)
                    ops += op.moved((cw - op.width) / 2, opY)
                    sup?.let { ops += it.moved((cw - it.width) / 2, opY - op.ascent - s * 0.08f - it.descent) }
                    sub?.let { ops += it.moved((cw - it.width) / 2, opY + op.descent + s * 0.08f + it.ascent) }
                    asc = maxOf(op.ascent - opY, sup?.let { -opY + op.ascent + s * 0.08f + it.height } ?: 0f)
                    desc = maxOf(op.descent + opY, sub?.let { opY + op.descent + s * 0.08f + it.height } ?: 0f)
                    w = cw
                } else {
                    ops += op.moved(0f, opY)
                    val sx = op.width * 0.9f
                    sup?.let { ops += it.moved(sx, opY - op.ascent * 0.75f) }
                    sub?.let { ops += it.moved(sx - op.width * 0.25f, opY + op.descent * 0.9f) }
                    asc = maxOf(op.ascent - opY, sup?.let { -opY + op.ascent * 0.75f + it.ascent } ?: 0f)
                    desc = maxOf(op.descent + opY, sub?.let { opY + op.descent * 0.9f + it.descent } ?: 0f)
                    w = sx + maxOf(sup?.width ?: 0f, (sub?.width ?: 0f) - op.width * 0.25f, 0f)
                }
                val bx = w + s * 0.15f
                ops += body.moved(bx, 0f)
                MathBox(bx + body.width, maxOf(asc, body.ascent), maxOf(desc, body.descent), ops)
            }
            is MathNode.Delim -> {
                val inner = ArrayList<MathBox>()
                n.items.forEachIndexed { j, e -> if (j > 0) inner += text(MathNode.Text(n.sep, true), s); inner += box(e, s) }
                val body = row(inner, s)
                // The brackets as tall as what they hold, centred on the axis.
                val half = maxOf(body.ascent - axis(s), body.descent + axis(s), s * 0.5f) + s * 0.08f
                fun bracket(c: String, x: Float): Pair<Float, List<MathBox.Op>> {
                    if (c.isEmpty()) return 0f to emptyList()
                    val plain = text(MathNode.Text(c, true), s)
                    val stretch = (2 * half / (plain.height * 0.95f)).coerceAtLeast(1f)
                    // Drawn [stretch] times taller: its middle on the axis.
                    val mid = (plain.descent - plain.ascent) / 2
                    return plain.width to listOf(MathBox.Op.Letters(c, x, -axis(s) - mid * stretch, s, false, stretch))
                }
                val (lw, lops) = bracket(n.open, 0f)
                val ops = ArrayList<MathBox.Op>(lops); ops += body.moved(lw, 0f)
                val (rw, rops) = bracket(n.close, lw + body.width); ops += rops
                MathBox(lw + body.width + rw, maxOf(body.ascent, half + axis(s)), maxOf(body.descent, half - axis(s)), ops)
            }
            is MathNode.Func -> row(listOf(box(n.name, s), MathBox(s * 0.17f, 0f, 0f, emptyList()), box(n.body, s)), s)
            is MathNode.Accent -> {
                val body = box(n.body, s)
                val shown = if (n.char.length == 1 && Character.getType(n.char[0]) == Character.NON_SPACING_MARK.toInt()) SPACING[n.char] ?: n.char else n.char
                if (n.char == "\u20D7") { // an arrow over it
                    val y = -body.ascent - s * 0.15f; val w = maxOf(body.width, s * 0.5f)
                    MathBox(w, body.ascent + s * 0.3f, body.descent, body.moved((w - body.width) / 2, 0f) +
                        MathBox.Op.Line(0f, y, w, y, line(s)) + MathBox.Op.Path(listOf(w - s * 0.15f to y - s * 0.1f, w to y, w - s * 0.15f to y + s * 0.1f), line(s)))
                } else {
                    val mark = text(MathNode.Text(shown, true), s)
                    val y = -body.ascent - s * 0.05f + mark.ascent * 0.45f
                    MathBox(maxOf(body.width, mark.width), body.ascent + mark.ascent * 0.55f, body.descent,
                        body.moved(maxOf(0f, (mark.width - body.width) / 2), 0f) + mark.moved((maxOf(body.width, mark.width) - mark.width) / 2 + if (body.ascent > 0) s * 0.06f else 0f, y))
                }
            }
            is MathNode.Bar -> {
                val body = box(n.body, s); val y = if (n.top) -body.ascent - s * 0.1f else body.descent + s * 0.1f
                MathBox(body.width, body.ascent + if (n.top) s * 0.15f else 0f, body.descent + if (n.top) 0f else s * 0.15f, body.ops + MathBox.Op.Line(0f, y, body.width, y, line(s)))
            }
            is MathNode.Limit -> {
                val base = box(n.base, s); val lim = box(n.lim, s * 0.7f)
                val w = maxOf(base.width, lim.width)
                val y = if (n.low) base.descent + s * 0.05f + lim.ascent else -base.ascent - s * 0.05f - lim.descent
                MathBox(w, if (n.low) base.ascent else base.ascent + s * 0.05f + lim.height, if (n.low) base.descent + s * 0.05f + lim.height else base.descent,
                    base.moved((w - base.width) / 2, 0f) + lim.moved((w - lim.width) / 2, y))
            }
            is MathNode.Matrix -> grid(n.rows.map { r -> r.map { box(it, s) } }, s, center = true)
            is MathNode.Stack -> grid(n.rows.map { listOf(box(it, s)) }, s, center = true)
        }

        fun grid(cells: List<List<MathBox>>, s: Float, center: Boolean): MathBox {
            if (cells.isEmpty()) return MathBox(0f, 0f, 0f, emptyList())
            val cols = cells.maxOf { it.size }
            val widths = (0 until cols).map { c -> cells.maxOf { it.getOrNull(c)?.width ?: 0f } }
            val gapX = s * 0.6f; val gapY = s * 0.25f
            val heights = cells.map { r -> (r.maxOfOrNull { it.ascent } ?: s * 0.7f) to (r.maxOfOrNull { it.descent } ?: s * 0.2f) }
            val total = heights.sumOf { (a, d) -> (a + d).toDouble() }.toFloat() + gapY * (cells.size - 1)
            val top = -total / 2 - axis(s) // centred on the axis
            val ops = ArrayList<MathBox.Op>()
            var y = top
            cells.forEachIndexed { r, row ->
                val (a, d) = heights[r]
                var x = 0f
                row.forEachIndexed { c, b -> ops += b.moved(x + if (center) (widths[c] - b.width) / 2 else 0f, y + a); x += widths[c] + gapX }
                y += a + d + gapY
            }
            return MathBox(widths.sum() + gapX * (cols - 1), -top, total + top, ops)
        }

        fun text(n: MathNode.Text, s: Float): MathBox {
            // Letters italic (unless plain); digits, signs and Greek capitals upright, as Word draws its maths.
            val ops = ArrayList<MathBox.Op>()
            var x = 0f
            val pieces = if (n.plain) listOf(n.text to false) else Regex("""\p{L}+|[^\p{L}]+""").findAll(n.text).map { it.value to (it.value[0].isLetter() && !it.value[0].isUpperCase() || it.value[0] in 'A'..'Z') }.toList()
            for ((j, piece) in pieces.withIndex()) {
                val (t, italic) = piece
                val operator = t.length == 1 && t[0] in OPERATORS && !(j == 0 && pieces.size > 1) // a leading sign (−b) is not spaced
                val pad = if (operator) s * 0.22f else 0f
                ops += MathBox.Op.Letters(t, x + pad, 0f, s, italic)
                x += m.width(t, s, italic) + 2 * pad + if (italic) s * 0.03f else 0f
            }
            return MathBox(x, s * 0.75f, s * 0.22f, ops)
        }

        fun row(boxes: List<MathBox>, s: Float, nodes: List<MathNode> = emptyList()): MathBox {
            if (boxes.isEmpty()) return MathBox(s * 0.3f, s * 0.75f, s * 0.22f, emptyList())
            val ops = ArrayList<MathBox.Op>()
            var x = 0f
            for (b in boxes) { ops += b.moved(x, 0f); x += b.width }
            return MathBox(x, boxes.maxOf { it.ascent }, boxes.maxOf { it.descent }, ops)
        }
    }
}

private val OPERATORS = setOf('+', '−', '-', '=', '<', '>', '≤', '≥', '≠', '±', '∓', '×', '÷', '⋅', '→', '≈', '≡', '∈', '∉', '⊂', '∪', '∩', '⇒', '←', '≅')
/** Combining accents drawn on their own (Word's accent characters are combining marks). */
private val SPACING = mapOf("\u0302" to "ˆ", "\u0303" to "˜", "\u0307" to "˙", "\u0308" to "¨", "\u0301" to "´", "\u0300" to "`", "\u030C" to "ˇ", "\u0306" to "˘", "\u0305" to "¯")

/** An equation drawn (pages, editor, PDF): laid out with the phone's own letters (Caladea, Cambria's metrics). */
internal class MathDrawable(val math: MathNode, val size: Float) : android.graphics.drawable.Drawable() {
    private val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK }
    private fun face(italic: Boolean) = android.graphics.Typeface.create(OfficeFonts.typeface("Cambria") ?: android.graphics.Typeface.SERIF, if (italic) android.graphics.Typeface.ITALIC else android.graphics.Typeface.NORMAL)
    private val upright = face(false); private val italicFace = face(true)
    val box = MathBox.of(math, size, measurer())
    private fun measurer() = MathMeasure { t, s, it -> paint.typeface = if (it) italicFace else upright; paint.textSize = s; paint.measureText(t) }
    companion object {
        /** The phone's own measure for reading equations (set once Writer has its fonts). */
        fun measure(): MathMeasure = MathDrawable(MathNode.Row(emptyList()), 10f).measurer()
    }
    /** Points: as wide as laid out, as tall as its ascent and descent. */
    val widthPt get() = box.width
    val heightPt get() = box.height
    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        val k = if (box.width > 0) b.width() / box.width else 1f
        canvas.save(); canvas.translate(b.left.toFloat(), b.top.toFloat()); canvas.scale(k, k); canvas.translate(0f, box.ascent)
        val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND }
        for (o in box.ops) when (o) {
            is MathBox.Op.Letters -> {
                paint.typeface = if (o.italic) italicFace else upright; paint.textSize = o.size
                if (o.stretch == 1f) canvas.drawText(o.text, o.x, o.y, paint) else { canvas.save(); canvas.translate(o.x, o.y); canvas.scale(1f, o.stretch); canvas.drawText(o.text, 0f, 0f, paint); canvas.restore() }
            }
            is MathBox.Op.Line -> { line.strokeWidth = o.width; canvas.drawLine(o.x1, o.y1, o.x2, o.y2, line) }
            is MathBox.Op.Path -> { line.strokeWidth = o.width; canvas.drawPath(android.graphics.Path().apply { o.points.forEachIndexed { j, (x, y) -> if (j == 0) moveTo(x, y) else lineTo(x, y) } }, line) }
        }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
