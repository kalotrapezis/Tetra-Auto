package com.kalotrapezis.drive

import android.graphics.Canvas
import android.graphics.Paint
import org.w3c.dom.Element
import kotlin.math.max
import kotlin.math.sign

/**
 * PowerPoint (the plan: after Excel; a viewer first, its animations later with the user watching): a deck's slides as
 * they show — text in placeholders and boxes with the looks their layout, master and theme give them, shapes in their
 * outlines, pictures, charts, tables, groups, backgrounds — and each slide's speaker notes. Sizes in points.
 */
/** A slide's bullet: its character or number, its colour (0xAARRGGBB), its size a share of its letters' (buSzPct), its own face (buFont). */
internal class SlideBullet(val text: String, val color: Int? = null, val size: Float = 1f, val font: String? = null,
    /** a number's form ('1', 'a', 'A', 'i', 'I'), counted by [SlidePara.numbered]; null: a character (or "1." as read before forms) */ val num: Char? = null) {
    companion object {
        /** PowerPoint's buAutoNum type, LibreOffice's num-format or Word's numFmt as a number's form. */
        fun form(kind: String?): Char = when {
            kind == null -> '1'
            kind.startsWith("alphaLc") || kind == "a" || kind == "lowerLetter" -> 'a'; kind.startsWith("alphaUc") || kind == "A" || kind == "upperLetter" -> 'A'
            kind.startsWith("romanLc") || kind == "i" || kind == "lowerRoman" -> 'i'; kind.startsWith("romanUc") || kind == "I" || kind == "upperRoman" -> 'I'
            else -> '1'
        }
        /** The [n]th number in [form]: 3., c., C., iii., III. */
        fun text(form: Char, n: Int): String = when (form) {
            'a', 'A' -> { var k = n; val sb = StringBuilder(); while (k > 0) { k--; sb.insert(0, 'a' + k % 26); k /= 26 }; if (form == 'A') sb.toString().uppercase() else sb.toString() }
            'i', 'I' -> { var k = n; val sb = StringBuilder()
                for ((v, s) in listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC", 50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")) while (k >= v) { sb.append(s); k -= v }
                if (form == 'i') sb.toString().lowercase() else sb.toString() }
            else -> "$n"
        } + "."
        /** PowerPoint's buAutoNum type for a form. */
        fun pptx(form: Char) = when (form) { 'a' -> "alphaLcPeriod"; 'A' -> "alphaUcPeriod"; 'i' -> "romanLcPeriod"; 'I' -> "romanUcPeriod"; else -> "arabicPeriod" }
    }
}
/**
 * A slide's paragraph (the Office engine, step 3): Words' paragraph and runs — [para]'s level in its list, its alignment,
 * its runs with every look Words draws (a run's face and letter looks in Word's form, in [DocRun.font]) — with its
 * spacing and indents as the file says them ([look]: lineMultiple a share of PowerPoint's single line, 1.2 × the letters'
 * size; [SlidesText] turns it into Words') and its [bullet].
 */
internal class SlidePara(val para: DocPara, val look: ParaLook = ParaLook(listIndented = true), val bullet: SlideBullet? = null,
    /** its letters' look before their own (the level's, the placeholder's, the master's): what Clear formatting leaves */ val base: DocRun? = null) {
    val runs get() = para.runs
    val level get() = para.list?.level ?: 0
    companion object {
        /**
         * Numbered paragraphs counted as PowerPoint and LibreOffice count them: each level on its own, a deeper level
         * starting again after a shallower one, an empty paragraph not counted.
         */
        fun numbered(paras: List<SlidePara>): List<SlidePara> {
            val counts = IntArray(10)
            return paras.map { p ->
                val l = p.level.coerceIn(0, 9)
                if (p.runs.any { it.text.isNotEmpty() }) for (k in l + 1..9) counts[k] = 0
                val b = p.bullet?.takeIf { (it.num != null || it.text.firstOrNull()?.isDigit() == true) && p.runs.any { r -> r.text.isNotEmpty() } } ?: return@map p
                val form = b.num ?: '1'
                SlidePara(p.para, p.look, SlideBullet(SlideBullet.text(form, ++counts[l]), b.color, b.size, b.font, form), p.base)
            }
        }
        /** Words in one look ([size] pt, [color] 0xRRGGBB, [face]), aligned [align]: a hint, a new table's cell. */
        fun plain(words: String, size: Float, color: Int, face: String? = null, bold: Boolean = false, align: DocAlign = DocAlign.Start) =
            SlidePara(DocPara(listOf(DocRun(words, bold = bold, sizePt = size, color = color and 0xFFFFFF, font = face?.let(Slides::face))), list = DocList(false, 0), align = align))
    }
}
/** [shrink]: "shrink text on overflow" (PowerPoint's normAutofit, LibreOffice's shrink-to-fit): made smaller until it fits its box; [warp]: WordArt's bend (Words' TextWarp). */
internal class SlideText(val paras: List<SlidePara>, val anchor: Char, val insets: FloatArray, val scale: Float = 1f, val wrap: Boolean = true, val shrink: Boolean = false,
    val warp: TextWarp? = null, /** its columns (PowerPoint's numCol, LibreOffice's column-count) and the room between them (pt) */ val cols: Int = 1, val colGap: Float = 0f,
    /** Text direction: its words turned 90° (PowerPoint's vert) or 270° (vert270); 0 across */ val vert: Int = 0)

/**
 * A shape's own outline (DrawingML's custGeom): its moves (0), lines (1), curves (2) and closes (4), the points from 0 to 1
 * of its box; filled and stroked as it says.
 */
internal class SlidePath(val ops: List<FloatArray>, val fill: Boolean, val stroke: Boolean)

/** A gradient fill: its colours at their stops (0–1), along [angle] degrees (0: left to right, 90: top to bottom). */
internal class SlideGradient(val colors: IntArray, val stops: FloatArray, val angle: Float)

/** A picture's look: see-through [alpha] (1: not), an outline [line] (0xRRGGBB) [lineW] pt wide, a soft shadow, corners rounded by [round] of its shorter side. */
internal data class PictureLook(val alpha: Float = 1f, val line: Int? = null, val lineW: Float = 0f, val shadow: Boolean = false, val round: Float = 0f)

internal sealed class SlideItem(val x: Float, val y: Float, val w: Float, val h: Float, val rotation: Float) {
    /** The id of the shape, picture or group it belongs to on the slide (cNvPr id): what an animation names. */
    var anim: String? = null
    /** In a group: it moves with the group (not chosen to move on its own). */
    var grouped = false
    /** In a group: its own id (cNvPr id, an .odp's place), what it is chosen by once the group is taken apart. */
    var own: String? = null
    /** Its words for screen readers (alt text: PowerPoint's cNvPr descr, LibreOffice's svg:desc). */
    var alt: String? = null
    /** Its name in the Selection pane (PowerPoint's cNvPr name, LibreOffice's draw:name) and whether it is hidden there (not drawn: hidden="1", drawooo:display="none"). */
    var name: String? = null
    var hidden = false
    /** On every slide (the slide master's own, Arrange › On every slide): its key there — PowerPoint's master part and shape id,
     *  LibreOffice's master page and the shape's place in it; "added <id>" one put on here. Chosen by "master:" and it. */
    var master: String? = null
    /** A picture's style and transparency (PowerPoint's Picture Styles, LibreOffice's image opacity, line and shadow); null: plain. */
    var pic: PictureLook? = null
    /** Where a tap on it goes in a show (Insert › Link on a picture or shape): a slide's part (an .odp's "#page"), a web address, or "back" (the slide it came from). */
    var link: String? = null
    /** A video (Insert › Video, asked 17 Oct): a part of the file (its path), a copy on the phone, or a web address — this item its picture, played when tapped in a show. */
    var media: String? = null
    /** A film's start and end (PowerPoint's Trim Video, p14:trim): ms left out at its start and at its end; null: all of it. */
    var trim: List<Int>? = null
    class Shape(x: Float, y: Float, w: Float, h: Float, rotation: Float, val shape: ShapePicture?, val text: SlideText?,
        val paths: List<SlidePath> = emptyList(), val flipH: Boolean = false, val flipV: Boolean = false, val gradient: SlideGradient? = null,
        /** The slide's own shape's id (cNvPr id), its words editable; null: the master's or layout's. */ val id: String? = null,
        /** An empty placeholder's grey hint (Title, Text): shown only while editing. */ val hint: Boolean = false) : SlideItem(x, y, w, h, rotation)
    /** [crop]: the part of the picture shown (a:srcRect) — how much is cut from its left, top, right and bottom, 0–1 of its size. */
    class Picture(x: Float, y: Float, w: Float, h: Float, rotation: Float, val crop: FloatArray? = null,
        /** An equation shown as this picture (PowerPoint's fallback, LibreOffice Math's replacement): Edit equation opens it */ val math: MathNode? = null,
        /** mirrored (Arrange › Flip) */ val flipH: Boolean = false, val flipV: Boolean = false,
        val bytes: () -> ByteArray?) : SlideItem(x, y, w, h, rotation)
    class Chart(x: Float, y: Float, w: Float, h: Float, val chart: DocChart) : SlideItem(x, y, w, h, 0f)
    /** A table: its columns' widths and rows' heights, each cell's fill and words. */
    class Table(x: Float, y: Float, w: Float, h: Float, val cols: List<Float>, val rows: List<Float>, val cells: List<List<Pair<Int?, SlideText?>>>,
        val line: Int?) : SlideItem(x, y, w, h, 0f)
}

/** How a slide comes in when presenting (PowerPoint's transition): [kind] fade, push, wipe, cover…; [dir] l, r, u, d; [ms] how long. */
internal data class SlideTransition(val kind: String, val dir: String?, val ms: Int,
    /** the slide goes on by itself this long after it is shown (its effects done) — PowerPoint's "After"; null: on a click; [kind] "" then: no transition */ val after: Int? = null)
/** An effect on a click: [target] the shape's id; [kind] appear, fade, fly; [from] fly's side (l, r, u, d); its delay and length; [exit]: it goes (disappear, fade out, fly out to [from]). */
internal data class SlideEffect(val target: String, val kind: String, val from: Char, val delay: Int, val ms: Int, val exit: Boolean = false,
    /** when it starts — 'c' on a click, 'w' with the one before, 'a' after it (PowerPoint's Start) */ val start: Char = 'c',
    /** while editing: how long it waits from then (PowerPoint's Delay) */ val wait: Int = 0) {
    /** An emphasis (pulse, spin, grow), or a film or sound played ("play": PowerPoint's media call): on something already there, neither bringing it nor taking it away. */
    val emphasis get() = kind in EMPHASES || kind == "play"
    companion object { val EMPHASES = setOf("pulse", "spin", "grow") }
}
/** One click's effects ([auto]: they start by themselves, with the slide or after the step before). */
internal class SlideStep(val auto: Boolean, val effects: List<SlideEffect>)

/** [part]: the slide's part in the file (ppt/slides/slide3.xml), for saving into. */
internal class Slide(val items: List<SlideItem>, val background: Int, val notes: String, val hidden: Boolean = false, val part: String = "",
    val transition: SlideTransition? = null, val steps: List<SlideStep> = emptyList(),
    /** Insert › Header & footer: its layout's date ("dt"), footer ("ftr") and number ("sldNum") boxes as they would show on it; the ids of
     *  its own ones' ids and kinds; whether it is a title slide (Don't show on title slide) */
    val footers: Map<String, SlideItem.Shape> = emptyMap(), val footerIds: Map<String, String> = emptyMap(), val titleSlide: Boolean = false,
    /** Review › Comments: its comments (Words' [DocComment]s, a reply naming its [DocComment.parent]) */ val comments: List<SlideComment> = emptyList(),
    /** a picture over its [background], stretched to the slide (PowerPoint's blipFill background, LibreOffice's bitmap fill) */ val backPicture: (() -> ByteArray?)? = null,
    /** its own placeholders' ids (Home › Reset puts them back): as read, not kept by the edited slide */ val placeholders: Set<String> = emptySet(),
    /** a gradient over its [background] (PowerPoint's gradFill background, LibreOffice's gradient fill) */ val backGradient: SlideGradient? = null)
/** A comment on a slide: Words' [comment], its marker at [x], [y] (points). */
internal data class SlideComment(val comment: DocComment, val x: Float, val y: Float)
internal class Deck(val width: Float, val height: Float, val slides: List<Slide>, val layouts: List<SlideLayout> = emptyList(),
    /** Home › Layout: slide n read again on layout k (PowerPoint's layout, LibreOffice's master page); null: the file cannot say it */
    /** A slide read again: on another layout (its index; null its own), reset (Home › Reset) or not. */
    val relaid: ((Int, Int?, Boolean) -> Slide?)? = null,
    /** PowerPoint's sections: each one's name and its first slide; null: the file cannot hold them (an .odp) */ val sections: List<Pair<String, Int>>? = null,
    /** Slide show › Loop until Esc (PowerPoint's showPr loop, LibreOffice's endless) */ val loop: Boolean = false,
    /** Set Up Slide Show: shown without animation (showPr showAnimation="0", LibreOffice's animations="disabled"); timings not used (useTimings="0", force-manual) */
    val noAnimation: Boolean = false, val manual: Boolean = false,
    /** References (Words' sources): the deck's sources and their citation style */ val sources: List<DocSource> = emptyList(), val citeStyle: CiteStyle = CiteStyle.APA)

/** A layout a new slide can take: its name, its part, a slide of it with grey hints (Title, Text), the empty slide saved. */
internal class SlideLayout(val name: String, val part: String, val preview: Slide, val xml: ByteArray)

internal object Slides {
    /** An empty placeholder's grey words (only shown, never saved): English here and in the tests, the app's language from SlidesScreen. */
    @Volatile var hintTitle = "Title"; @Volatile var hintSubtitle = "Subtitle"; @Volatile var hintText = "Text"; @Volatile var hintPicture = "Picture"
    /** A face as a run's raw settings name it (Word's rFonts, which [DocStyles.fontName] reads). */
    fun face(name: String) = """<w:rFonts w:ascii="${name.replace("&", "&amp;").replace("\"", "&quot;")}"/>"""
    fun isSlides(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("pptx", "ppsx", "odp")

    private const val EMU = 12700f // EMUs in a point

    private fun Element.kids(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
    private fun Element.kids(name: String) = kids().filter { it.localName == name }
    private fun Element.kid(name: String): Element? = kids().firstOrNull { it.localName == name }
    private fun Element.below(name: String): List<Element> = getElementsByTagNameNS("*", name).let { l -> (0 until l.length).map { l.item(it) as Element } }
    private fun Element.attr(name: String): String? = getAttribute(name).takeIf { it.isNotEmpty() }
    private fun Element.rid(name: String): String? = getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", name).takeIf { it.isNotEmpty() }

    /** A slide's own date, footer or number placeholder ([type] "dt", "ftr", "sldNum"; [idx] and [sz] its layout's) holding [runs] (a:r, a:fld). */
    fun footerSp(type: String, idx: String?, sz: String?, id: String, runs: String): String {
        val name = when (type) { "dt" -> "Date Placeholder"; "ftr" -> "Footer Placeholder"; else -> "Slide Number Placeholder" }
        return """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="$name $id"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr><p:ph type="$type"${sz?.let { " sz=\"$it\"" }.orEmpty()}${idx?.let { " idx=\"$it\"" }.orEmpty()}/></p:nvPr></p:nvSpPr><p:spPr/>""" +
            """<p:txBody><a:bodyPr/><a:lstStyle/><a:p>$runs</a:p></p:txBody></p:sp>"""
    }

    /** A deck read from its parts. */
    fun read(file: java.io.File): Deck {
        if (file.extension.equals("odp", true)) return SlidesOdp.read(file)
        val zip = java.util.zip.ZipFile(file)
        val cache = HashMap<String, ByteArray?>()
        fun part(name: String): ByteArray? = synchronized(cache) { cache.getOrPut(name) { zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { it.readBytes() } } } }
        fun xml(name: String) = part(name)?.let { OfficeFormats.parse(it).documentElement }
        return try { Pptx(::part, ::xml).read() } finally { /* parts are read as slides are drawn: the zip stays open for pictures */ }
    }

    /** A part's relationships: id → the part's path. */
    private fun rels(xml: (String) -> Element?, path: String): Map<String, String> {
        val dir = path.substringBeforeLast('/', "")
        val r = xml((if (dir.isEmpty()) "" else "$dir/") + "_rels/" + path.substringAfterLast('/') + ".rels") ?: return emptyMap()
        return r.kids("Relationship").associate { e ->
            val t = e.attr("Target").orEmpty()
            e.attr("Id").orEmpty() to (if (e.attr("TargetMode") == "External") t else if (t.startsWith("/")) t.drop(1) else normalize("$dir/$t"))
        }
    }
    private fun relType(xml: (String) -> Element?, path: String, type: String): String? {
        val dir = path.substringBeforeLast('/', "")
        val r = xml((if (dir.isEmpty()) "" else "$dir/") + "_rels/" + path.substringAfterLast('/') + ".rels") ?: return null
        return r.kids("Relationship").firstOrNull { it.attr("Type")?.endsWith("/$type") == true }?.attr("Target")?.let { if (it.startsWith("/")) it.drop(1) else normalize("$dir/$it") }
    }
    private fun normalize(p: String): String { val out = ArrayList<String>(); for (s in p.split('/')) when (s) { ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex); ".", "" -> Unit; else -> out += s }; return out.joinToString("/") }

    private class Pptx(val part: (String) -> ByteArray?, val xml: (String) -> Element?) {
        // the theme's colours and fonts, the master's map of them (bg1 → lt1…)
        var scheme = HashMap<String, Int>(); var major = "Calibri Light"; var minor = "Calibri"; var map = HashMap<String, String>()
        var fillStyles = emptyList<Element>() // the theme's fills a shape style's fillRef names (1, 2, 3)
        var bgFillStyles = emptyList<Element>() // the theme's background fills a slide's bgRef names (1001, 1002, 1003)
        var ph: Int? = null // the style's colour, for the theme's fills that say "phClr"

        fun read(): Deck {
            val pres = xml("ppt/presentation.xml") ?: error("Not a presentation.")
            val size = pres.kid("sldSz")
            val w = (size?.attr("cx")?.toFloatOrNull() ?: 9144000f) / EMU; val h = (size?.attr("cy")?.toFloatOrNull() ?: 6858000f) / EMU
            val rels = rels(xml, "ppt/presentation.xml")
            val defaults = pres.kid("defaultTextStyle")
            val slides = pres.kid("sldIdLst")?.kids("sldId").orEmpty().mapNotNull { it.rid("id")?.let(rels::get) }
            val read = slides.mapNotNull { path -> runCatching { slide(path, defaults) }.onFailure { WriterLog.e("Slides: $path", it) }.getOrNull() }
            val kinds = runCatching { layouts(pres, rels, defaults) }.onFailure { WriterLog.w("Slides: layouts", it) }.getOrDefault(emptyList())
            val again = HashMap<Triple<Int, Int?, Boolean>, Slide?>()
            // PowerPoint 2010's sections: each names its slides by their sldId
            val sldIds = pres.kid("sldIdLst")?.kids("sldId").orEmpty().map { it.attr("id") }
            val sections = pres.below("section").filter { it.namespaceURI?.contains("powerpoint/2010") == true }.mapNotNull { sec ->
                sec.below("sldId").firstOrNull()?.attr("id")?.let { sldIds.indexOf(it) }?.takeIf { it >= 0 }?.let { (sec.attr("name") ?: "") to it } }
            val showPr = relType(xml, "ppt/presentation.xml", "presProps")?.let(xml)?.kid("showPr")
            val loop = showPr?.attr("loop").let { it == "1" || it == "true" }
            val noAnimation = showPr?.attr("showAnimation").let { it == "0" || it == "false" }; val manual = showPr?.attr("useTimings").let { it == "0" || it == "false" }
            // References: Word's bibliography part, which PowerPoint keeps too (customXml)
            val custom = (1..24).mapNotNull { n -> "customXml/item$n.xml".let { p -> part(p)?.let { p to it } } }.toMap()
            return Deck(w, h, read, kinds, sections = sections, loop = loop, noAnimation = noAnimation, manual = manual, sources = WordSources.read(custom), citeStyle = CiteStyle.ofWord(WordSources.styleName(custom)), relaid = { n, k, reset -> synchronized(again) { again.getOrPut(Triple(n, k, reset)) {
                slides.getOrNull(n)?.let { path -> runCatching { slide(path, defaults, layoutOf = k?.let { kinds[it].part }, fix = if (reset) SlidesEdit::resetSlide else null) }.onFailure { WriterLog.w("Slides: $path on another layout", it) }.getOrNull() } } } })
        }

        /**
         * The first master's layouts, each as a new slide would be: its title, body and other placeholders (not the date,
         * footer or number), empty to save, with grey hints to show.
         */
        fun layouts(pres: Element, rels: Map<String, String>, defaults: Element?): List<SlideLayout> {
            val masterPath = pres.kid("sldMasterIdLst")?.kids("sldMasterId")?.firstOrNull()?.rid("id")?.let(rels::get) ?: return emptyList()
            val master = xml(masterPath) ?: return emptyList()
            val mrels = rels(xml, masterPath)
            return master.kid("sldLayoutIdLst")?.kids("sldLayoutId").orEmpty().mapNotNull { it.rid("id")?.let(mrels::get) }.mapNotNull { path ->
                val layout = xml(path) ?: return@mapNotNull null
                val name = layout.kid("cSld")?.attr("name") ?: "Layout"
                val phs = layout.kid("cSld")?.kid("spTree")?.kids("sp").orEmpty().mapNotNull { sp ->
                    sp.kid("nvSpPr")?.kid("nvPr")?.kid("ph")?.takeIf { it.attr("type") !in setOf("dt", "ftr", "sldNum", "hdr", "sldImg") } }
                fun slideXml(hints: Boolean) = buildString {
                    append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>""")
                    phs.forEachIndexed { i, ph ->
                        val type = ph.attr("type"); val idx = ph.attr("idx")
                        val hint = when (type) { "title", "ctrTitle" -> "Title"; "subTitle" -> "Subtitle"; "pic" -> "Picture"; else -> "Text" }
                        append("""<p:sp><p:nvSpPr><p:cNvPr id="${i + 2}" name="$hint ${i + 1}"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr><p:ph${type?.let { " type=\"$it\"" }.orEmpty()}${idx?.let { " idx=\"$it\"" }.orEmpty()}/></p:nvPr></p:nvSpPr><p:spPr/>""")
                        append(if (hints) """<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:pPr><a:buNone/></a:pPr><a:r><a:rPr lang="el-GR"><a:solidFill><a:srgbClr val="8C8C8C"/></a:solidFill></a:rPr><a:t>${when (type) { "title", "ctrTitle" -> hintTitle; "subTitle" -> hintSubtitle; "pic" -> hintPicture; else -> hintText }}</a:t></a:r></a:p></p:txBody></p:sp>"""
                            else """<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang="el-GR"/></a:p></p:txBody></p:sp>""")
                    }
                    append("""</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>""")
                }
                val preview = slide(path, defaults, OfficeFormats.parse(slideXml(true).toByteArray()).documentElement, path)
                SlideLayout(name, path, Slide(preview.items.map { i -> if (i is SlideItem.Shape && i.id != null) SlideItem.Shape(i.x, i.y, i.w, i.h, i.rotation, i.shape, i.text, i.paths, i.flipH, i.flipV, i.gradient, i.id, hint = true) else i },
                    preview.background, ""), slideXml(false).toByteArray())
            }
        }

        /** A colour as DrawingML gives it: its own, a scheme's (through the master's map), changed by lumMod/lumOff, shade, tint, alpha. */
        fun color(e: Element?): Int? {
            val c = e?.kids()?.firstOrNull { it.localName in setOf("srgbClr", "schemeClr", "sysClr", "prstClr") } ?: return null
            var rgb = when (c.localName) {
                "srgbClr" -> c.attr("val")?.toIntOrNull(16)
                "sysClr" -> (c.attr("lastClr") ?: "000000").toIntOrNull(16)
                "prstClr" -> mapOf("black" to 0, "white" to 0xFFFFFF, "red" to 0xFF0000, "green" to 0x008000, "blue" to 0x0000FF, "yellow" to 0xFFFF00)[c.attr("val")]
                else -> c.attr("val")?.let { n -> if (n == "phClr") ph?.and(0xFFFFFF) else scheme[map[n] ?: n] ?: scheme[n] }
            } ?: return null
            var a = 255
            for (m in c.kids()) {
                val v = (m.attr("val")?.toFloatOrNull() ?: continue) / 100_000f
                when (m.localName) {
                    "lumMod", "lumOff" -> {
                        val hsl = FloatArray(3); androidx.core.graphics.ColorUtils.colorToHSL(rgb or 0xFF000000.toInt(), hsl)
                        hsl[2] = if (m.localName == "lumMod") hsl[2] * v else (hsl[2] + v).coerceAtMost(1f)
                        rgb = androidx.core.graphics.ColorUtils.HSLToColor(hsl) and 0xFFFFFF
                    }
                    "shade" -> rgb = mix(rgb, 0, 1 - v)
                    "tint" -> rgb = mix(rgb, 0xFFFFFF, 1 - v)
                    "alpha" -> a = (255 * v).toInt().coerceIn(0, 255)
                }
            }
            return (a shl 24) or (rgb and 0xFFFFFF)
        }
        private fun mix(c: Int, to: Int, f: Float): Int {
            fun ch(s: Int) = (((c shr s) and 255) + (((to shr s) and 255) - ((c shr s) and 255)) * f).toInt().coerceIn(0, 255) shl s
            return ch(16) or ch(8) or ch(0)
        }
        /** A gradient: its stops' colours (the style's colour where they say phClr) along its angle. */
        fun gradient(g: Element?, base: Int? = null): SlideGradient? {
            g ?: return null
            ph = base
            val stops = g.kid("gsLst")?.kids("gs").orEmpty().mapNotNull { gs -> color(gs)?.let { (gs.attr("pos")?.toFloatOrNull() ?: 0f) / 100_000f to it } }.sortedBy { it.first }
            ph = null
            if (stops.size < 2) return null
            return SlideGradient(stops.map { it.second }.toIntArray(), stops.map { it.first }.toFloatArray(), (g.kid("lin")?.attr("ang")?.toFloatOrNull() ?: 5_400_000f) / 60_000f)
        }
        /** A shape style's fill (fillRef idx 1–3: the theme's fills, in the style's colour): a colour or a gradient. */
        fun styleFill(ref: Element?): Pair<Int?, SlideGradient?> {
            val base = color(ref) ?: return null to null
            val idx = ref?.attr("idx")?.toIntOrNull() ?: return base to null
            val theme = fillStyles.getOrNull((if (idx > 1000) idx - 1001 else idx - 1))
            return when (theme?.localName) {
                "gradFill" -> base to gradient(theme, base)
                "noFill" -> null to null
                else -> base to null
            }
        }
        fun fill(spPr: Element?): Int? = spPr?.kid("solidFill")?.let(::color)
            ?: spPr?.kid("gradFill")?.below("gs")?.firstOrNull()?.let(::color) // shortcut: a gradient as its first colour; draw gradients if decks need them

        /** One slide: its layout's and master's placeholders under it, its background, its notes. */
        /** [own]: a slide not in the file yet (a layout's new slide), [layoutOf] its layout. */
        fun slide(path: String, defaults: Element?, own: Element? = null, layoutOf: String? = null, fix: ((Element) -> Unit)? = null): Slide {
            val s = own ?: xml(path)?.let { x -> if (fix == null) x else (x.cloneNode(true) as Element).also(fix) } ?: error("No $path.")
            val rels = if (own != null) emptyMap() else rels(xml, path)
            val layoutPath = layoutOf ?: relType(xml, path, "slideLayout"); val layout = layoutPath?.let(xml)
            val masterPath = layoutPath?.let { relType(xml, it, "slideMaster") }; val master = masterPath?.let(xml)
            masterPath?.let { relType(xml, it, "theme") }?.let(xml)?.let(::theme)
            map = HashMap<String, String>().also { m -> master?.kid("clrMap")?.let { cm -> for (i in 0 until cm.attributes.length) cm.attributes.item(i).let { a -> m[a.localName ?: a.nodeName] = a.nodeValue } } }
            val bg = listOfNotNull(s, layout, master).firstNotNullOfOrNull { e -> e.kid("cSld")?.kid("bg")?.let { b -> b.kid("bgPr")?.let(::fill) ?: b.kid("bgRef")?.let(::color) } }
                ?: 0xFFFFFFFF.toInt()
            // a picture background: the nearest that has a background says it (its own relationships name the picture)
            val backPicture = listOf(s to path, layout to layoutPath, master to masterPath).firstOrNull { (e, _) -> e?.kid("cSld")?.kid("bg") != null }?.let { (e, p) ->
                e!!.kid("cSld")!!.kid("bg")!!.kid("bgPr")?.kid("blipFill")?.kid("blip")?.rid("embed")?.let { id -> p?.let { rels(xml, it)[id] } } }?.let { pic -> { part(pic) } }
            // a gradient background: the nearest's own, or its theme's (bgRef 1001 on: the theme's background fills)
            // (one that cannot be read leaves the slide its colour: never the slide gone)
            val backGradient = runCatching { listOfNotNull(s, layout, master).firstNotNullOfOrNull { e -> e.kid("cSld")?.kid("bg") }?.let { b -> b.kid("bgPr")?.kid("gradFill")?.let { gradient(it) }
                ?: b.kid("bgRef")?.let { r -> r.attr("idx")?.toIntOrNull()?.takeIf { it > 1000 }?.let { bgFillStyles.getOrNull(it - 1001) }?.takeIf { it.localName == "gradFill" }?.let { g -> gradient(g, color(r)) } } } }
                .onFailure { WriterLog.w("Slides: $path's background gradient", it) }.getOrNull()
            val context = Placeholders(layout, master, defaults)
            val items = ArrayList<SlideItem>()
            fun tree(e: Element?, from: String, ctx: Placeholders?) = e?.kid("cSld")?.kid("spTree")?.let { shapes(it, rels(xml, from), ctx, items, null) }
            // what the master and the layout show on every slide (a logo, a line), under the slide's own
            if (s.attr("showMasterSp") != "0" && layout?.attr("showMasterSp") != "0") master?.kid("cSld")?.kid("spTree")?.let { shapes(it, rels(xml, masterPath!!), null, items, null, masterKey = masterPath) }
            if (s.attr("showMasterSp") != "0") tree(layout, layoutPath!!, null)
            s.kid("cSld")?.kid("spTree")?.let { shapes(it, rels, context, items, null) }
            val notes = (if (own != null) null else relType(xml, path, "notesSlide"))?.let(xml)?.below("sp")?.firstOrNull { it.below("ph").firstOrNull()?.attr("type") == "body" }
                ?.below("p")?.joinToString("\n") { p -> p.below("*").joinToString("") { if (it.localName == "br") "\n" else if (it.localName == "t") it.textContent else "" } }?.trim().orEmpty() // a line break inside a paragraph kept
            // Header & footer: the layout's boxes, each read as a slide's own placeholder of it would be (its place and looks from the layout and master)
            val kinds = setOf("dt", "ftr", "sldNum")
            fun phOf(sp: Element) = sp.kid("nvSpPr")?.kid("nvPr")?.kid("ph")
            val footers = layout?.kid("cSld")?.kid("spTree")?.kids("sp").orEmpty().mapNotNull { phOf(it)?.takeIf { p -> p.attr("type") in kinds } }.mapNotNull { ph ->
                val t = ph.attr("type")!!
                val tree = OfficeFormats.parse(("""<p:spTree xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">""" +
                    footerSp(t, ph.attr("idx"), ph.attr("sz"), "1", """<a:r><a:rPr lang="en-US"/><a:t>$t</a:t></a:r>""") + "</p:spTree>").toByteArray()).documentElement
                val got = ArrayList<SlideItem>(); runCatching { shapes(tree, emptyMap(), context, got, null) }
                (got.lastOrNull() as? SlideItem.Shape)?.let { t to it }
            }.toMap()
            val own = s.kid("cSld")?.kid("spTree")?.kids("sp").orEmpty().mapNotNull { sp -> phOf(sp)?.attr("type")?.takeIf { it in kinds }?.let { k -> sp.kid("nvSpPr")?.kid("cNvPr")?.attr("id")?.let { it to k } } }.toMap()
            val title = layout?.attr("type") == "title" || s.kid("cSld")?.kid("spTree")?.kids("sp").orEmpty().any { phOf(it)?.attr("type") == "ctrTitle" }
            val phIds = s.below("ph").mapNotNull { (it.parentNode?.parentNode as? Element)?.kid("cNvPr")?.attr("id") }.toSet()
            return Slide(items, bg, notes, s.attr("show") == "0", path, transition(s), steps(s), footers, own, title, comments(path), backPicture, phIds, backGradient)
        }

        /** Who wrote comments: the older list's authors (by number) and PowerPoint 365's (by their GUID) — name and initials. */
        val authors by lazy {
            listOfNotNull(relType(xml, "ppt/presentation.xml", "commentAuthors"), relType(xml, "ppt/presentation.xml", "authors")).mapNotNull(xml)
                .flatMap { it.kids().filter { a -> a.localName in setOf("cmAuthor", "author") } }.associate { a -> a.attr("id").orEmpty() to (a.attr("name").orEmpty() to a.attr("initials")) }
        }
        /**
         * A slide's comments: PowerPoint's older list (which LibreOffice writes too; a reply names its parent in p15:parentCm, its
         * place in eighths of a point) or PowerPoint 365's threads (replies inside, resolved or not).
         */
        fun comments(path: String): List<SlideComment> {
            val list = relType(xml, path, "comments")?.let(xml) ?: return emptyList()
            fun words(e: Element?) = e?.below("p")?.joinToString("\n") { p -> p.below("t").joinToString("") { it.textContent } }.orEmpty()
            fun who(id: String?) = authors[id.orEmpty()] ?: ("" to null)
            return list.kids("cm").flatMap { cm ->
                val pos = cm.kid("pos")
                if (cm.kid("text") != null) {
                    val (name, initials) = who(cm.attr("authorId"))
                    val parent = cm.below("parentCm").firstOrNull()?.let { "${it.attr("authorId")}-${it.attr("idx")}" }
                    listOf(SlideComment(DocComment("${cm.attr("authorId")}-${cm.attr("idx")}", name, cm.attr("dt"), cm.kid("text")!!.textContent, initials = initials, parent = parent),
                        (pos?.attr("x")?.toFloatOrNull() ?: 0f) / 8f, (pos?.attr("y")?.toFloatOrNull() ?: 0f) / 8f))
                } else {
                    // shortcut: a threaded comment's place taken as EMU (most anchor to an object instead); upgrade when a deck shows them astray
                    val x = (pos?.attr("x")?.toFloatOrNull() ?: 0f) / EMU; val y = (pos?.attr("y")?.toFloatOrNull() ?: 0f) / EMU
                    val id = cm.attr("id").orEmpty()
                    fun one(e: Element, parent: String?) = who(e.attr("authorId")).let { (name, initials) ->
                        SlideComment(DocComment(e.attr("id").orEmpty(), name, e.attr("created"), words(e.kid("txBody")), initials = initials, parent = parent, done = e.attr("status") == "resolved"), x, y) }
                    listOf(one(cm, null)) + cm.kid("replyLst")?.kids("reply").orEmpty().map { one(it, id) }
                }
            }
        }

        /** The slide's transition (PowerPoint 2010's own, in AlternateContent, or the plain one). */
        fun transition(s: Element): SlideTransition? {
            val t = s.kid("transition") ?: s.kid("AlternateContent")?.let { a -> a.kid("Choice")?.kid("transition") ?: a.kid("Fallback")?.kid("transition") } ?: return null
            // going on by itself after a time (advTm), with or without a transition of its own
            val after = t.attr("advTm")?.toIntOrNull()
            val kind = t.kids().firstOrNull()?.localName ?: if (after != null) "" else return null
            val ms = t.attributes.let { a -> (0 until a.length).map { a.item(it) }.firstOrNull { it.localName == "dur" }?.nodeValue?.toIntOrNull() }
                ?: when (t.attr("spd")) { "fast" -> 500; "slow" -> 1000; else -> 750 }
            return SlideTransition(kind, t.kids().firstOrNull()?.attr("dir"), if (kind.isEmpty()) 0 else ms, after)
        }

        /**
         * The slide's click animations: its main sequence's groups, each a click (or starting by itself), each effect an
         * entrance or an exit — appear (1), fly (2, its subtype the side: 1 top, 2 right, 4 bottom, 8 left), the rest as a fade.
         */
        // shortcut: emphasis read as pulse, spin or grow (others as a pulse); paragraph by paragraph builds animate the whole box
        fun steps(s: Element): List<SlideStep> {
            val main = s.kid("timing")?.below("cTn")?.firstOrNull { it.attr("nodeType") == "mainSeq" } ?: return emptyList()
            fun delay(c: Element?) = c?.kid("stCondLst")?.kids("cond")?.firstNotNullOfOrNull { it.attr("delay")?.toIntOrNull() } ?: 0
            return main.kid("childTnLst")?.kids("par").orEmpty().mapNotNull { g ->
                val gc = g.kid("cTn") ?: return@mapNotNull null
                // by itself: PowerPoint's onBegin, or (LibreOffice's) a start that waits for no click
                val conds = gc.kid("stCondLst")?.kids("cond").orEmpty()
                val auto = conds.any { it.attr("evt") == "onBegin" } || (conds.isNotEmpty() && conds.none { it.attr("delay") == "indefinite" })
                val effects = gc.kid("childTnLst")?.kids("par").orEmpty().flatMap { inner ->
                    val ic = inner.kid("cTn"); val at = delay(ic)
                    ic?.below("cTn").orEmpty().filter { it.attr("presetClass") in setOf("entr", "exit", "emph", "mediacall") }.mapNotNull { e ->
                        val target = e.below("spTgt").firstOrNull()?.attr("spid") ?: return@mapNotNull null
                        // a behaviour played there and back (autoRev) lasts twice its length
                        val ms = e.below("cTn").mapNotNull { c -> c.attr("dur")?.toIntOrNull()?.let { if (c.attr("autoRev") == "1") it * 2 else it } }.filter { it > 1 }.maxOrNull() ?: 500
                        // a film or sound played (Video › Start: In Click Sequence, Automatically); its length the film's
                        val kind = if (e.attr("presetClass") == "mediacall") "play" else if (e.attr("presetClass") == "emph") when (e.attr("presetID")) { "8" -> "spin"; "6" -> "grow"; else -> "pulse" }
                            else when (e.attr("presetID")) { "1" -> "appear"; "2" -> "fly"; "22" -> "wipe"; "23", "53" -> "zoom"; else -> "fade" }
                        val from = when (e.attr("presetSubtype")) { "1" -> 'u'; "2" -> 'r'; "4" -> 'd'; "8" -> 'l'; else -> 'd' }
                        val start = when (e.attr("nodeType")) { "withEffect" -> 'w'; "afterEffect" -> 'a'; else -> 'c' }
                        SlideEffect(target, kind, from, at + delay(e), ms, exit = e.attr("presetClass") == "exit", start = start)
                    }
                }
                if (effects.isEmpty()) null else SlideStep(auto, effects)
            }
        }

        fun theme(t: Element) {
            scheme = HashMap()
            t.below("clrScheme").firstOrNull()?.kids()?.forEach { c -> color(c)?.let { scheme[c.localName] = it and 0xFFFFFF } }
            fillStyles = t.below("fillStyleLst").firstOrNull()?.kids().orEmpty()
            bgFillStyles = t.below("bgFillStyleLst").firstOrNull()?.kids().orEmpty()
            t.below("majorFont").firstOrNull()?.kid("latin")?.attr("typeface")?.let { major = it }
            t.below("minorFont").firstOrNull()?.kid("latin")?.attr("typeface")?.let { minor = it }
        }

        /** The layout's and master's shapes a placeholder takes its place and looks from, and the deck's text defaults. */
        inner class Placeholders(val layout: Element?, val master: Element?, val defaults: Element?) {
            private fun phOf(sp: Element) = sp.kids().firstOrNull { it.localName.startsWith("nv") }?.kid("nvPr")?.kid("ph")
            private fun find(tree: Element?, type: String, idx: String?): Element? {
                // only placeholders: a master's own drawing (a band, a logo) is no placeholder, though "no type" means body
                val shapes = tree?.kid("cSld")?.kid("spTree")?.kids("sp").orEmpty().filter { phOf(it) != null }
                fun kind(t: String?) = when (t) { null, "", "obj", "body", "subTitle" -> "body"; "ctrTitle", "title" -> "title"; else -> t }
                return idx?.let { i -> shapes.firstOrNull { phOf(it)?.attr("idx") == i } } ?: shapes.firstOrNull { kind(phOf(it)?.attr("type")) == kind(type) }
            }
            fun chain(sp: Element): List<Element> {
                val ph = phOf(sp) ?: return emptyList()
                val type = ph.attr("type") ?: "body"; val idx = ph.attr("idx")
                return listOfNotNull(find(layout, type, idx), find(master, type, null))
            }
            /** The master's text style for this kind of shape: titles', the body's, or other text's. */
            fun style(sp: Element): Element? {
                val type = phOf(sp)?.attr("type")
                val styles = master?.kid("txStyles") ?: return defaults
                return when {
                    phOf(sp) == null -> defaults ?: styles.kid("otherStyle")
                    type == "title" || type == "ctrTitle" -> styles.kid("titleStyle")
                    type in setOf("dt", "ftr", "sldNum") -> styles.kid("otherStyle")
                    else -> styles.kid("bodyStyle")
                }
            }
        }

        /** The shapes of a tree, [group] placing a group's children in its box. */
        fun shapes(tree: Element, rels: Map<String, String>, ctx: Placeholders?, out: MutableList<SlideItem>, group: ((FloatArray) -> FloatArray)?, groupId: String? = null,
                   /** the master's part: its own shapes keyed, to be chosen on a slide and taken off every slide */ masterKey: String? = null) {
            for (e in tree.kids()) runCatching {
                val before = out.size
                val ownId = groupId ?: e.kids().firstOrNull { it.localName.startsWith("nv") }?.kid("cNvPr")?.attr("id")
                // a placeholder on the master or layout shows its words only on slides that fill it: left out there
                if (ctx == null && e.localName == "sp" && e.below("ph").isNotEmpty()) return@runCatching
                val chain = if (ctx != null && e.localName == "sp") ctx.chain(e) else emptyList()
                val spPr = e.kid("spPr") ?: e.kid("grpSpPr")
                val xfrm = (spPr?.kid("xfrm") ?: e.kid("xfrm")) ?: chain.firstNotNullOfOrNull { it.kid("spPr")?.kid("xfrm") }
                val off = xfrm?.kid("off"); val ext = xfrm?.kid("ext")
                var box = floatArrayOf((off?.attr("x")?.toFloatOrNull() ?: 0f) / EMU, (off?.attr("y")?.toFloatOrNull() ?: 0f) / EMU,
                    (ext?.attr("cx")?.toFloatOrNull() ?: 0f) / EMU, (ext?.attr("cy")?.toFloatOrNull() ?: 0f) / EMU)
                if (group != null) box = group(box)
                val rot = (xfrm?.attr("rot")?.toFloatOrNull() ?: 0f) / 60_000f
                when (e.localName) {
                    "sp" -> {
                        val style = e.kid("style")
                        val geom = spPr?.kid("prstGeom")?.attr("prst") ?: chain.firstNotNullOfOrNull { it.kid("spPr")?.kid("prstGeom")?.attr("prst") }
                        val noFill = spPr?.kid("noFill") != null
                        val styled = if (noFill || spPr?.kid("solidFill") != null || spPr?.kid("gradFill") != null) null to null
                            else style?.kid("fillRef")?.takeIf { it.attr("idx") != "0" }?.let(::styleFill) ?: (null to null)
                        val grad = if (noFill) null else gradient(spPr?.kid("gradFill")) ?: styled.second
                        val fillC = if (noFill) null else fill(spPr) ?: chain.firstNotNullOfOrNull { fill(it.kid("spPr")) } ?: styled.first
                        val ln = spPr?.kid("ln")
                        val lineC = if (ln?.kid("noFill") != null) null else ln?.kid("solidFill")?.let(::color) ?: style?.kid("lnRef")?.takeIf { it.attr("idx") != "0" }?.let(::color)
                        val width = (ln?.attr("w")?.toFloatOrNull() ?: 12700f) / EMU
                        val shape = if (fillC != null || lineC != null) ShapePicture(geom?.takeIf { it in ShapeGeometry.presets || it in ShapePicture.KINDS } ?: "rect", fillC, lineC, width,
                            adjust = spPr?.kid("prstGeom")?.below("gd")?.mapNotNull { g -> g.attr("fmla")?.removePrefix("val ")?.trim()?.toDoubleOrNull()?.let { (g.attr("name") ?: "") to it } }?.toMap().orEmpty(),
                            dash = ln?.kid("prstDash")?.attr("val")?.takeIf { it != "solid" }, shadow = spPr?.kid("effectLst")?.kid("outerShdw") != null) else null
                        val fontColor = style?.kid("fontRef")?.let(::color)
                        // a slide's own empty placeholder: a grey hint to tap and type into (PowerPoint's "Click to add title"), never shown in a show
                        val ph = if (ctx != null) e.kid("nvSpPr")?.kid("nvPr")?.kid("ph") else null
                        val empty = ph != null && ph.attr("type") !in setOf("dt", "ftr", "sldNum", "hdr", "sldImg") &&
                            e.kid("txBody")?.below("t")?.none { it.textContent.isNotEmpty() } != false
                        val paths = spPr?.kid("custGeom")?.let(::custom).orEmpty()
                        val hintBody = if (empty) OfficeFormats.parse(("""<p:txBody xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:bodyPr/><a:lstStyle/><a:p><a:pPr><a:buNone/></a:pPr><a:r><a:rPr><a:solidFill><a:srgbClr val="8C8C8C"/></a:solidFill></a:rPr><a:t>""" +
                            (when (ph?.attr("type")) { "title", "ctrTitle" -> hintTitle; "subTitle" -> hintSubtitle; else -> hintText }) + "</a:t></a:r></a:p></p:txBody>").toByteArray()).documentElement else null
                        val text = (hintBody ?: e.kid("txBody"))?.let { text(it, chain.mapNotNull { c -> c.kid("txBody") }, ctx?.style(e) ?: ctx?.defaults, fontColor, rels = rels) }
                        if (shape != null || text != null) out += SlideItem.Shape(box[0], box[1], box[2], box[3], rot, shape, text, paths,
                            xfrm?.attr("flipH") == "1", xfrm?.attr("flipV") == "1", grad,
                            id = if (ctx != null) e.kid("nvSpPr")?.kid("cNvPr")?.attr("id") else null, hint = empty)
                    }
                    "pic" -> e.below("blip").firstOrNull()?.rid("embed")?.let(rels::get)?.let { p ->
                        val crop = srcRect(e.kid("blipFill")?.let(OfficeFormats::xml))
                        val flips = e.kid("spPr")?.kid("xfrm")
                        // a video's picture: its film embedded (PowerPoint 2010's p14:media) or linked (a:videoFile: a part, or a web address)
                        val nv = e.kid("nvPicPr")?.kid("nvPr")
                        val trim = nv?.below("media")?.firstOrNull()?.kid("trim")?.let { t -> listOf(t.attr("st")?.toDoubleOrNull()?.toInt() ?: 0, t.attr("end")?.toDoubleOrNull()?.toInt() ?: 0) }?.takeIf { it.any { v -> v > 0 } }
                        val film = nv?.below("media")?.firstOrNull()?.rid("embed")?.let(rels::get) ?: (nv?.kid("videoFile") ?: nv?.kid("audioFile"))?.rid("link")?.let(rels::get)
                        // its style and transparency: the blip's alphaModFix, the outline, the outer shadow, a rounded rectangle
                        val sp = e.kid("spPr"); val ln = sp?.kid("ln")
                        val look = PictureLook(e.below("blip").firstOrNull()?.kid("alphaModFix")?.attr("amt")?.toFloatOrNull()?.div(100_000f)?.coerceIn(0f, 1f) ?: 1f,
                            ln?.kid("solidFill")?.kid("srgbClr")?.attr("val")?.toIntOrNull(16), ln?.attr("w")?.toFloatOrNull()?.div(EMU) ?: 0f,
                            sp?.kid("effectLst")?.kid("outerShdw") != null, if (sp?.kid("prstGeom")?.attr("prst") == "roundRect") ROUND else 0f)
                        out += SlideItem.Picture(box[0], box[1], box[2], box[3], rot, crop, flipH = flips?.attr("flipH") == "1", flipV = flips?.attr("flipV") == "1") { part(p) }
                            .also { it.media = film; it.trim = trim; it.pic = look.takeIf { l -> l != PictureLook() } }
                    }
                    "graphicFrame" -> {
                        e.below("chart").firstOrNull()?.rid("id")?.let(rels::get)?.let(part)?.let(OfficeCharts::readDrawingml)?.let { out += SlideItem.Chart(box[0], box[1], box[2], box[3], it) }
                        e.below("tbl").firstOrNull()?.let { out += table(it, box) }
                        // a film or sound embedded as an object (Insert › Object › Create from file): its icon, played from its package ("part#name")
                        e.below("oleObj").firstNotNullOfOrNull { it.rid("id") }?.let(rels::get)?.let { p ->
                            part(p)?.let { OfficeVideo.ole(it, nameOnly = true) }?.first?.takeIf(OfficeVideo::media)?.let { name ->
                                val icon = e.below("blip").firstOrNull()?.rid("embed")?.let(rels::get)
                                out += SlideItem.Picture(box[0], box[1], box[2], box[3], rot) { icon?.let(part) }.also { it.media = "$p#$name" } } }
                    }
                    "grpSp" -> {
                        val ch = xfrm?.kid("chOff"); val che = xfrm?.kid("chExt")
                        val cx = (ch?.attr("x")?.toFloatOrNull() ?: 0f) / EMU; val cy = (ch?.attr("y")?.toFloatOrNull() ?: 0f) / EMU
                        val cw = ((che?.attr("cx")?.toFloatOrNull() ?: 0f) / EMU).takeIf { it > 0 } ?: box[2]; val chh = ((che?.attr("cy")?.toFloatOrNull() ?: 0f) / EMU).takeIf { it > 0 } ?: box[3]
                        val kx = if (cw > 0) box[2] / cw else 1f; val ky = if (chh > 0) box[3] / chh else 1f
                        val g0 = box
                        shapes(e, rels, ctx, out, { b -> floatArrayOf(g0[0] + (b[0] - cx) * kx, g0[1] + (b[1] - cy) * ky, b[2] * kx, b[3] * ky) }, ownId)
                    }
                    "AlternateContent" -> e.kid("Fallback")?.let { fb ->
                        val before = out.size
                        shapes(fb, rels, ctx, out, group, groupId)
                        // an equation (PowerPoint's a14:m, a text box of one): drawn from the equation, its fallback picture kept for saving
                        val sp = e.kid("Choice")?.kid("sp")
                        val math = sp?.let { it.below("oMathPara").firstOrNull() ?: it.below("oMath").firstOrNull() }?.let { runCatching { OfficeMath.read(it) }.getOrNull() }
                        val off = sp?.kid("spPr")?.kid("xfrm")?.let { x -> listOfNotNull(x.kid("off")?.attr("x"), x.kid("off")?.attr("y"), x.kid("ext")?.attr("cx"), x.kid("ext")?.attr("cy")).mapNotNull { it.toFloatOrNull()?.div(EMU) } }
                        if (math != null && off?.size == 4 && group == null) {
                            val fell = out.subList(before, out.size).toList(); fell.forEach { out.remove(it) }
                            val p = fell.firstOrNull() as? SlideItem.Picture
                            out += SlideItem.Picture(off[0], off[1], off[2], off[3], 0f, null, math, bytes = p?.bytes ?: { null }).also { if (ctx != null) it.anim = groupId ?: sp.kid("nvSpPr")?.kid("cNvPr")?.attr("id") }
                        }
                    }
                }
                val cNvPr = e.kids().firstOrNull { it.localName.startsWith("nv") }?.kid("cNvPr")
                val descr = cNvPr?.attr("descr")
                // its own click (PowerPoint's action on the object): a slide, a web page, or the slide it came from
                val click = cNvPr?.kid("hlinkClick")?.let { h -> if (h.attr("action")?.contains("lastslideviewed") == true) "back" else h.rid("id")?.let(rels::get) }
                if (masterKey != null) cNvPr?.attr("id")?.let { id -> for (k in before until out.size) { out[k].master = "$masterKey $id"; out[k].anim = "master:$masterKey $id" } }
                if (ctx != null) for (k in before until out.size) { if (out[k].anim == null) out[k].anim = ownId; if (group != null && out[k].own == null) out[k].own = cNvPr?.attr("id"); if (group != null) out[k].grouped = true else { out[k].alt = descr; out[k].link = click; out[k].name = cNvPr?.attr("name")?.ifEmpty { null }; out[k].hidden = cNvPr?.attr("hidden").let { it == "1" || it == "true" } } }
            }.onFailure { WriterLog.w("Slides: a shape could not be read", it) }
        }

        /**
         * A custom outline's paths (custGeom), in its path's own units made 0..1: lines, curves, arcs (as curves).
         * Null points (a guide's name, not a number): the shape is left a box.
         */
        // shortcut: guides (gdLst) are not worked out — their shapes stay boxes; work them out if decks show many
        fun custom(g: Element): List<SlidePath> = g.kid("pathLst")?.kids("path").orEmpty().mapNotNull { p ->
            val pw = p.attr("w")?.toFloatOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val ph = p.attr("h")?.toFloatOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val ops = ArrayList<FloatArray>(); var cx = 0f; var cy = 0f; var sx = 0f; var sy = 0f; var named = false
            fun pts(e: Element) = e.kids("pt").map { pt -> val x = pt.attr("x")?.toFloatOrNull(); val y = pt.attr("y")?.toFloatOrNull(); if (x == null || y == null) named = true; (x ?: 0f) to (y ?: 0f) }
            for (c in p.kids()) when (c.localName) {
                "moveTo" -> pts(c).firstOrNull()?.let { (x, y) -> ops += floatArrayOf(0f, x / pw, y / ph); cx = x; cy = y; sx = x; sy = y }
                "lnTo" -> pts(c).firstOrNull()?.let { (x, y) -> ops += floatArrayOf(1f, x / pw, y / ph); cx = x; cy = y }
                "cubicBezTo" -> pts(c).takeIf { it.size == 3 }?.let { q -> ops += floatArrayOf(2f, q[0].first / pw, q[0].second / ph, q[1].first / pw, q[1].second / ph, q[2].first / pw, q[2].second / ph); cx = q[2].first; cy = q[2].second }
                "quadBezTo" -> pts(c).takeIf { it.size == 2 }?.let { q ->
                    // a quadratic as a cubic: its control point two thirds of the way from each end
                    val c1x = cx + 2f / 3 * (q[0].first - cx); val c1y = cy + 2f / 3 * (q[0].second - cy)
                    val c2x = q[1].first + 2f / 3 * (q[0].first - q[1].first); val c2y = q[1].second + 2f / 3 * (q[0].second - q[1].second)
                    ops += floatArrayOf(2f, c1x / pw, c1y / ph, c2x / pw, c2y / ph, q[1].first / pw, q[1].second / ph); cx = q[1].first; cy = q[1].second }
                "arcTo" -> {
                    val wr = c.attr("wR")?.toFloatOrNull() ?: return@mapNotNull null; val hr = c.attr("hR")?.toFloatOrNull() ?: return@mapNotNull null
                    val st = Math.toRadians((c.attr("stAng")?.toDoubleOrNull() ?: 0.0) / 60_000); val sw = Math.toRadians((c.attr("swAng")?.toDoubleOrNull() ?: 0.0) / 60_000)
                    // the ellipse's centre: the pen is on it at the start angle; the sweep in pieces of at most a quarter turn
                    val ox = cx - wr * Math.cos(st); val oy = cy - hr * Math.sin(st)
                    val n = maxOf(1, Math.ceil(Math.abs(sw) / (Math.PI / 2)).toInt()); val step = sw / n
                    var a = st
                    repeat(n) {
                        val k = 4.0 / 3 * Math.tan(step / 4)
                        val x1 = ox + wr * Math.cos(a); val y1 = oy + hr * Math.sin(a); val b = a + step
                        val x2 = ox + wr * Math.cos(b); val y2 = oy + hr * Math.sin(b)
                        ops += floatArrayOf(2f, ((x1 - k * wr * Math.sin(a)) / pw).toFloat(), ((y1 + k * hr * Math.cos(a)) / ph).toFloat(),
                            ((x2 + k * wr * Math.sin(b)) / pw).toFloat(), ((y2 - k * hr * Math.cos(b)) / ph).toFloat(), (x2 / pw).toFloat(), (y2 / ph).toFloat())
                        a = b
                    }
                    cx = (ox + wr * Math.cos(a)).toFloat(); cy = (oy + hr * Math.sin(a)).toFloat()
                }
                "close" -> { ops += floatArrayOf(4f); cx = sx; cy = sy }
            }
            if (ops.isEmpty() || named) null else SlidePath(ops, p.attr("fill") != "none", p.attr("stroke") != "0" && p.attr("stroke") != "false")
        }

        /** A table: its grid, each cell's fill (the table style's banded look when it has none) and words. */
        fun table(t: Element, box: FloatArray): SlideItem.Table {
            val cols = t.kid("tblGrid")?.kids("gridCol").orEmpty().map { (it.attr("w")?.toFloatOrNull() ?: 0f) / EMU }
            val trs = t.kids("tr")
            val pr = t.kid("tblPr")
            // PowerPoint's Medium Style 2 (its default, banded in an accent) by its id; any other style: plain, with grid lines
            val id = pr?.kid("tableStyleId")?.textContent?.trim()?.uppercase()
            val medium = mapOf("{073A0DAA-6AF3-43AB-8588-CEC1D06C72B9}" to "dk1", "{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}" to "accent1",
                "{21E4AEA4-8DFA-4A89-87EB-49C32662AFE8}" to "accent2", "{F5AB1C69-6EDB-4FF4-983F-18BD219EF322}" to "accent3",
                "{00A15C55-8517-42AA-B614-E9B94910E393}" to "accent4", "{7DF18680-E054-41AD-8BC1-D1AEF772440D}" to "accent5",
                "{93296810-A885-4BE3-A3E7-6D5BEEA58F35}" to "accent6")[id]
            val styled = medium != null
            val first = pr?.attr("firstRow") == "1"; val banded = pr?.attr("bandRow") == "1"
            val accent = scheme[medium ?: "accent1"] ?: 0x4472C4
            val cells = trs.mapIndexed { r, tr -> tr.kids("tc").map { tc ->
                val own = fill(tc.kid("tcPr"))
                val look = when { own != null -> own; !styled -> null; first && r == 0 -> accent or 0xFF000000.toInt()
                    banded && (r - if (first) 1 else 0) % 2 == 0 -> 0xFF000000.toInt() or mix(accent, 0xFFFFFF, 0.6f) else -> 0xFF000000.toInt() or mix(accent, 0xFFFFFF, 0.8f) }
                look to tc.kid("txBody")?.let { text(it, emptyList(), null, if (styled && first && r == 0 && own == null) 0xFFFFFFFF.toInt() else null, defaultSize = 18f, boldAll = styled && first && r == 0) }
            } }
            return SlideItem.Table(box[0], box[1], box[2], box[3], cols, trs.map { (it.attr("h")?.toFloatOrNull() ?: 0f) / EMU }, cells, if (styled) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
        }

        /** A text body: its paragraphs with the looks of their level from the shape, its placeholders and the master. */
        fun text(body: Element, inherited: List<Element>, master: Element?, fontColor: Int?, defaultSize: Float = 18f, boldAll: Boolean = false, rels: Map<String, String> = emptyMap()): SlideText? {
            val paras = body.kids("p")
            if (paras.none { p -> p.below("t").any { it.textContent.isNotEmpty() } }) return null
            // a level's pPr from the nearest: the shape's lstStyle, its placeholders', the master's style
            val lists = listOfNotNull(body.kid("lstStyle")) + inherited.mapNotNull { it.kid("lstStyle") } + listOfNotNull(master)
            fun level(n: Int) = lists.mapNotNull { it.kid("lvl${n + 1}pPr") }
            val bodyPr = (listOf(body) + inherited).mapNotNull { it.kid("bodyPr") }
            fun bodyAttr(a: String) = bodyPr.firstNotNullOfOrNull { it.attr(a) }
            val anchor = when (bodyAttr("anchor")) { "ctr" -> 'c'; "b" -> 'b'; else -> 't' }
            fun inset(a: String, d: Float) = (bodyAttr(a)?.toFloatOrNull()?.div(EMU)) ?: d
            val scale = bodyPr.firstNotNullOfOrNull { it.kid("normAutofit")?.attr("fontScale")?.toFloatOrNull() }?.div(100_000f) ?: 1f
            // shrunk on overflow, lines closer too: PowerPoint takes this off each line's spacing (20%: 100% → 80%)
            val reduce = bodyPr.firstNotNullOfOrNull { it.kid("normAutofit")?.attr("lnSpcReduction")?.toFloatOrNull() }?.div(100_000f) ?: 0f
            val out = paras.map { p ->
                val pPr = p.kid("pPr")
                val lvl = pPr?.attr("lvl")?.toIntOrNull() ?: 0
                val looks = listOfNotNull(pPr) + level(lvl)
                fun pAttr(a: String) = looks.firstNotNullOfOrNull { it.attr(a) }
                fun pKid(n: String) = looks.firstNotNullOfOrNull { it.kid(n) }
                val defR = looks.mapNotNull { it.kid("defRPr") }
                fun runOf(r: Element?, t: String): DocRun {
                    val rPr = listOfNotNull(r?.kid("rPr")) + defR
                    fun rAttr(a: String) = rPr.firstNotNullOfOrNull { it.attr(a) }
                    val size = (rAttr("sz")?.toFloatOrNull()?.div(100f) ?: defaultSize) * scale
                    // its own colour, its paragraph's, the shape style's (white words on Office's blue shapes), then the inherited styles'
                    val own = listOfNotNull(r?.kid("rPr"), pPr?.kid("defRPr")).firstNotNullOfOrNull { it.kid("solidFill")?.let(::color) }
                    val color = own ?: fontColor ?: rPr.firstNotNullOfOrNull { it.kid("solidFill")?.let(::color) } ?: scheme[map["tx1"] ?: "dk1"]?.or(0xFF000000.toInt()) ?: 0xFF000000.toInt()
                    val face = rPr.firstNotNullOfOrNull { it.kid("latin")?.attr("typeface") }?.let { if (it == "+mj-lt") major else if (it == "+mn-lt") minor else it }
                        ?: if (master?.localName == "titleStyle") major else minor
                    // the nearest that says any (an empty effectLst takes the inherited ones away)
                    val fx = rPr.firstOrNull { it.kid("effectLst") != null }?.kid("effectLst")
                    val ln = rPr.firstNotNullOfOrNull { it.kid("ln") }?.takeIf { it.kid("noFill") == null }
                    val effects = TextEffects(ln?.kid("solidFill")?.let(::color)?.and(0xFFFFFF), fx?.kid("outerShdw") != null, fx?.kid("glow")?.let(::color)?.and(0xFFFFFF))
                    // a link in the theme's link colour unless it has its own, as PowerPoint shows it
                    val link = r?.kid("rPr")?.kid("hlinkClick")?.rid("id")?.let { rels[it] }
                    val u = rAttr("u"); val strike = rAttr("strike"); val cap = rAttr("cap")
                    // the letters' looks Words reads from a run's raw settings, in Word's form
                    // character spacing: PowerPoint's spc (hundredths of a point) as Word's w:spacing (twentieths), which Words' drawing reads
                    val looks = (rAttr("spc")?.toIntOrNull()?.takeIf { it != 0 }?.let { "<w:spacing w:val=\"${it / 5}\"/>" } ?: "") +
                        (if (cap == "all") "<w:caps/>" else if (cap == "small") "<w:smallCaps/>" else "") +
                        (if (strike == "dblStrike") "<w:dstrike/>" else "") + (if (u == "dbl") """<w:u w:val="double"/>""" else "") + (if (effects.any) effects.docx() else "")
                    return DocRun(t, bold = boldAll || rAttr("b") == "1", italic = rAttr("i") == "1", underline = u != null && u != "none" && u != "dbl", strike = strike == "sngStrike",
                        sizePt = size, color = (if (link != null && own == null) scheme["hlink"] ?: color else color) and 0xFFFFFF, link = link,
                        highlight = rPr.firstNotNullOfOrNull { it.kid("highlight") }?.let(::color)?.and(0xFFFFFF), font = Slides.face(face) + looks,
                        script = (rAttr("baseline")?.toIntOrNull() ?: 0).sign)
                }
                val runs = p.kids().mapNotNull { r -> when (r.localName) { "r", "fld" -> runOf(r, r.kid("t")?.textContent.orEmpty()); "br" -> runOf(r, LINE_BREAK.toString()); else -> null } }
                    .ifEmpty { listOf(runOf(null, "")) }
                // the nearest that says (the paragraph's own before its level's and the master's): none, a character or a number
                val bu = looks.firstNotNullOfOrNull { l -> l.kids().firstOrNull { it.localName in setOf("buNone", "buChar", "buAutoNum", "buBlip") } }
                val bullet = when (bu?.localName) { "buChar" -> bu.attr("char"); "buAutoNum" -> "${paras.indexOf(p) + 1}."; else -> null }
                val num = bu?.takeIf { it.localName == "buAutoNum" }?.let { SlideBullet.form(it.attr("type")) }
                fun pt(a: String) = pAttr(a)?.toFloatOrNull()?.div(EMU) ?: 0f
                fun spacing(n: String) = pKid(n)?.let { s -> s.kid("spcPts")?.attr("val")?.toFloatOrNull()?.div(100f) ?: s.kid("spcPct")?.attr("val")?.toFloatOrNull()?.let { it / 100_000f * (runs.firstOrNull()?.sizePt ?: defaultSize) } } ?: 0f
                val line = pKid("lnSpc")?.kid("spcPct")?.attr("val")?.toFloatOrNull()?.div(100_000f) ?: 1f
                // exact line spacing (spcPts): shrinking on overflow takes the same share off it
                val exact = pKid("lnSpc")?.kid("spcPts")?.attr("val")?.toFloatOrNull()?.div(100f)?.times(1 - reduce)
                val tabs = pKid("tabLst")?.kids("tab").orEmpty().mapNotNull { t -> t.attr("pos")?.toFloatOrNull()?.div(EMU)?.let { TabStop(it, when (t.attr("algn")) { "r" -> 'r'; "ctr" -> 'c'; else -> 'l' }) } }
                SlidePara(DocPara(runs, list = DocList(bullet?.firstOrNull()?.isDigit() == true, lvl), align = when (pAttr("algn")) { "ctr" -> DocAlign.Center; "r" -> DocAlign.End; "just" -> DocAlign.Justify; else -> DocAlign.Start }),
                    ParaLook(before = spacing("spcBef") * scale, after = spacing("spcAft") * scale, lineMultiple = (line - reduce).coerceAtLeast(0.5f), lineExact = exact,
                        left = pt("marL"), firstLine = pt("indent"), listIndented = true, tabs = tabs),
                    bullet?.let { SlideBullet(it, pKid("buClr")?.let(::color), pKid("buSzPct")?.attr("val")?.toFloatOrNull()?.div(100_000f) ?: 1f, pKid("buFont")?.attr("typeface"), num) },
                    runOf(null, ""))
            }
            return SlideText(SlidePara.numbered(out), anchor, floatArrayOf(inset("lIns", 7.2f), inset("tIns", 3.6f), inset("rIns", 7.2f), inset("bIns", 3.6f)), scale, bodyAttr("wrap") != "none",
                bodyPr.any { it.kid("normAutofit") != null },
                bodyPr.firstNotNullOfOrNull { it.kid("prstTxWarp")?.attr("prst") }?.let { w -> TextWarp.entries.firstOrNull { it.word == w } },
                bodyAttr("numCol")?.toIntOrNull()?.coerceIn(1, 16) ?: 1, bodyAttr("spcCol")?.toFloatOrNull()?.div(EMU) ?: 0f,
                vert = when (bodyAttr("vert")) { "vert", "eaVert", "mongolianVert", "wordArtVert", "wordArtVertRtl" -> 90; "vert270" -> 270; else -> 0 })
        }
    }

    // --- drawing ---

    private val pictures = android.util.LruCache<Any, android.graphics.Bitmap>(24)
    /** Animated GIFs (asked 16 Oct, as Google Slides): played where they show; their frames drawn as time goes. */
    private val gifs = android.util.LruCache<Any, android.graphics.drawable.AnimatedImageDrawable>(8)
    /** An equation's drawing (Words' MathDrawable), laid out once: slides are drawn often. */
    private val maths = java.util.WeakHashMap<MathNode, MathDrawable>()
    private val kinds = java.util.WeakHashMap<SlideItem.Picture, Boolean>() // whether a picture is a GIF
    fun isGif(p: SlideItem.Picture): Boolean = synchronized(kinds) { kinds.getOrPut(p) { p.bytes()?.let { it.size > 6 && String(it, 0, 4, Charsets.ISO_8859_1) == "GIF8" } == true } }
    /** Whether [slide] has a GIF, so its view is drawn again each frame. */
    fun moves(slide: Slide) = slide.items.any { it is SlideItem.Picture && isGif(it) }

    /** A slide drawn on [c] at [k] screen pixels a point, from 0,0. */
    /** [look]: an item's state in an animation — null hidden, else its opacity and how far it is moved (points), and (an emphasis) its scale and turn in degrees about a point. */
    /** File › Export PDF and Print: a page a slide, the deck's size in points, the hidden slides left out (as PowerPoint). */
    fun pdf(deck: Deck, out: java.io.OutputStream, handouts: Int = 1): Int {
        val pdf = android.graphics.pdf.PdfDocument()
        val shown = deck.slides.filter { !it.hidden }
        // Print › handouts (PowerPoint's): 2, 3 (lines beside each for notes) or 6 slides on an A4 page, each framed
        if (handouts > 1) {
            val pw = 595f; val ph = 842f; val m = 36f
            val cols = if (handouts == 6) 2 else 1; val rows = handouts / cols
            val cellW = (pw - 2 * m - (cols - 1) * 18f) / cols; val cellH = (ph - 2 * m - (rows - 1) * 18f) / rows
            val line = android.graphics.Paint().apply { color = 0xFF9E9E9E.toInt(); strokeWidth = 0.5f; style = android.graphics.Paint.Style.STROKE }
            shown.chunked(handouts).forEachIndexed { n, group ->
                val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(pw.toInt(), ph.toInt(), n + 1).create())
                group.forEachIndexed { k, s ->
                    val cx = m + (k % cols) * (cellW + 18f); val cy = m + (k / cols) * (cellH + 18f)
                    // three to a page: the slide on the left half, lines for notes on the right
                    val boxW = if (handouts == 3) cellW / 2 - 9f else cellW
                    val scale = minOf(boxW / deck.width, cellH / deck.height)
                    val w = deck.width * scale; val h = deck.height * scale
                    val x = cx + if (handouts == 3) 0f else (boxW - w) / 2; val y = cy + (cellH - h) / 2
                    page.canvas.save(); page.canvas.translate(x, y); draw(page.canvas, deck, s, scale); page.canvas.restore()
                    page.canvas.drawRect(x, y, x + w, y + h, line)
                    if (handouts == 3) { var ly = y + 18f; while (ly < y + h) { page.canvas.drawLine(cx + cellW / 2 + 9f, ly, cx + cellW, ly, line); ly += 22f } }
                }
                pdf.finishPage(page)
            }
            pdf.writeTo(out); pdf.close()
            return shown.size
        }
        shown.forEachIndexed { i, s ->
            val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(deck.width.toInt(), deck.height.toInt(), i + 1).create())
            draw(page.canvas, deck, s, 1f)
            pdf.finishPage(page)
        }
        pdf.writeTo(out); pdf.close()
        return shown.size
    }

    /** [g] over the box [x], [y], [w] x [h]: along its angle through the box's middle, as far as the box reaches that way. */
    private fun shader(g: SlideGradient, x: Float, y: Float, w: Float, h: Float): android.graphics.Shader {
        val a = Math.toRadians(g.angle.toDouble()); val cx = x + w / 2; val cy = y + h / 2
        val r = (Math.abs(Math.cos(a)) * w / 2 + Math.abs(Math.sin(a)) * h / 2).toFloat()
        return android.graphics.LinearGradient(cx - r * Math.cos(a).toFloat(), cy - r * Math.sin(a).toFloat(), cx + r * Math.cos(a).toFloat(), cy + r * Math.sin(a).toFloat(),
            g.colors, g.stops, android.graphics.Shader.TileMode.CLAMP)
    }
    fun draw(c: Canvas, deck: Deck, slide: Slide, k: Float, hints: Boolean = false, look: ((SlideItem) -> FloatArray?)? = null) {
        c.save(); c.scale(k, k)
        c.clipRect(0f, 0f, deck.width, deck.height)
        c.drawColor(slide.background)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        slide.backGradient?.let { g -> paint.shader = shader(g, 0f, 0f, deck.width, deck.height); c.drawRect(0f, 0f, deck.width, deck.height, paint); paint.shader = null }
        // a picture background, stretched to the slide
        slide.backPicture?.let { pic -> (pictures.get(pic) ?: runCatching { pic()?.let { b ->
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }; android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, o)
            var n = 1; while (o.outWidth / (n * 2) >= deck.width * 3) n *= 2
            android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = n }) } }.getOrNull()?.also { pictures.put(pic, it) })
            ?.let { c.drawBitmap(it, null, android.graphics.RectF(0f, 0f, deck.width, deck.height), paint) } }
        for (item in slide.items) {
            if (item is SlideItem.Shape && item.hint && !hints) continue
            if (item.hidden) continue // the Selection pane's eye shut
            val state = if (look != null) look(item) ?: continue else null
            val saved = c.save()
            if (state != null) {
                c.translate(state[1], state[2])
                if (state.size >= 7) { c.rotate(state[4], state[5], state[6]); c.scale(state[3], state[3], state[5], state[6]) }
                if (state.size >= 11) c.clipRect(state[7], state[8], state[9], state[10]) // a wipe: the part shown so far
                if (state[0] < 1f) c.saveLayerAlpha(item.x - 4, item.y - 4, item.x + item.w + 4, item.y + item.h + 4, (state[0] * 255).toInt().coerceIn(0, 255))
            }
            if (item.rotation != 0f) c.rotate(item.rotation, item.x + item.w / 2, item.y + item.h / 2)
            if (item is SlideItem.Picture && (item.flipH || item.flipV)) c.scale(if (item.flipH) -1f else 1f, if (item.flipV) -1f else 1f, item.x + item.w / 2, item.y + item.h / 2)
            when (item) {
                is SlideItem.Shape -> {
                    if (item.flipH || item.flipV) c.scale(if (item.flipH) -1f else 1f, if (item.flipV) -1f else 1f, item.x + item.w / 2, item.y + item.h / 2)
                    if (item.paths.isNotEmpty() || item.gradient != null) outline(c, item, paint) else item.shape?.drawAt(c, item.x, item.y, item.w, item.h)
                    if (item.flipH || item.flipV) c.scale(if (item.flipH) -1f else 1f, if (item.flipV) -1f else 1f, item.x + item.w / 2, item.y + item.h / 2) // words not mirrored
                    item.text?.let { SlidesText.draw(c, it, item.x, item.y, item.w, item.h) }
                }
                // an equation: drawn by Words' engine, sharp at any size (its picture is for the apps that cannot draw one)
                is SlideItem.Picture -> if (item.math != null) synchronized(maths) { maths.getOrPut(item.math) { MathDrawable(item.math, 28f) } }.let { m ->
                    c.save(); c.translate(item.x, item.y); c.scale(item.w / m.widthPt.coerceAtLeast(0.1f), item.h / m.heightPt.coerceAtLeast(0.1f))
                    m.setBounds(0, 0, Math.ceil(m.widthPt.toDouble()).toInt(), Math.ceil(m.heightPt.toDouble()).toInt()); m.draw(c); c.restore()
                } else {
                    val gif = if (isGif(item)) gifs.get(item) ?: runCatching { item.bytes()?.let { b ->
                        android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(b))) as? android.graphics.drawable.AnimatedImageDrawable
                    } }.getOrNull()?.also { it.repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE; it.start(); gifs.put(item, it) } else null
                    if (gif != null) {
                        val gw = gif.intrinsicWidth.coerceAtLeast(1); val gh = gif.intrinsicHeight.coerceAtLeast(1)
                        val src = crop(gw, gh, item.crop)
                        c.save(); c.clipRect(item.x, item.y, item.x + item.w, item.y + item.h); c.translate(item.x, item.y)
                        c.scale(item.w / (src[2] - src[0]).coerceAtLeast(1), item.h / (src[3] - src[1]).coerceAtLeast(1)); c.translate(-src[0].toFloat(), -src[1].toFloat())
                        gif.setBounds(0, 0, gw, gh); gif.draw(c); c.restore()
                        c.restoreToCount(saved); continue
                    }
                    val bmp = pictures.get(item) ?: runCatching { item.bytes()?.let { b ->
                        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, o)
                        var sample = 1; while (o.outWidth / (sample * 2) >= item.w * 3) sample *= 2
                        android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                    } }.getOrNull()?.let { cropped(it, item.crop) }?.also { pictures.put(item, it) }
                    // a film's or sound's icon Android cannot draw (an embedded object's EMF): a dark frame, a speaker
                    bmp?.let { framed(c, it, item, paint) } ?: item.media?.let(OfficeVideo::stand)?.run { setBounds(item.x.toInt(), item.y.toInt(), (item.x + item.w).toInt(), (item.y + item.h).toInt()); draw(c) }
                }
                is SlideItem.Chart -> ChartDrawable(item.chart).apply { setBounds(item.x.toInt(), item.y.toInt(), (item.x + item.w).toInt(), (item.y + item.h).toInt()) }.draw(c)
                is SlideItem.Table -> table(c, item, paint)
            }
            c.restoreToCount(saved)
            // a video's picture: a play mark in its middle (round, so its turn does not matter)
            if (item.media?.let(OfficeVideo::audio) == false) OfficeVideo.mark(c, item.x, item.y, item.w, item.h)
        }
        c.restore()
    }

    /** PowerPoint's rounded rectangle's corner (its adjustment 16667: a sixth of the shorter side). */
    const val ROUND = 0.16667f
    /** [b] in [item]'s box in its look: its shadow under it, see-through, its corners rounded, its outline round it. */
    private fun framed(c: Canvas, b: android.graphics.Bitmap, item: SlideItem, paint: Paint) {
        val r = android.graphics.RectF(item.x, item.y, item.x + item.w, item.y + item.h)
        val pl = item.pic ?: return c.drawBitmap(b, null, r, paint)
        val side = minOf(item.w, item.h); val rad = pl.round * side
        if (pl.shadow) c.drawRoundRect(android.graphics.RectF(r).apply { offset(side * 0.025f + 1f, side * 0.025f + 1f) }, rad, rad,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000; maskFilter = android.graphics.BlurMaskFilter(maxOf(2f, side * 0.03f), android.graphics.BlurMaskFilter.Blur.NORMAL) })
        c.save(); if (rad > 0f) c.clipPath(android.graphics.Path().apply { addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW) })
        c.drawBitmap(b, null, r, Paint(paint).apply { alpha = Math.round(pl.alpha * 255).coerceIn(0, 255) }); c.restore()
        pl.line?.takeIf { pl.lineW > 0f }?.let { col -> c.drawRoundRect(r, rad, rad, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = pl.lineW; color = 0xFF000000.toInt() or col }) }
    }

    /**
     * The part of a [w] × [h] picture that [crop] leaves (left, top, right, bottom in its pixels), stretched to the frame as
     * PowerPoint and LibreOffice draw it. shortcut: a negative crop (the frame larger than the picture) is drawn as none on that side.
     */
    internal fun crop(w: Int, h: Int, crop: FloatArray?): IntArray {
        if (crop == null) return intArrayOf(0, 0, w, h)
        val (l, t, r, b) = crop.map { it.coerceIn(0f, 1f) }
        // rounded: 2/9 of 900 written as 22222 is 199.998, and pixel 199 is the cut part's last
        val left = Math.round(w * l).coerceIn(0, w - 1); val top = Math.round(h * t).coerceIn(0, h - 1)
        return intArrayOf(left, top, Math.round(w * (1 - r)).coerceIn(left + 1, w), Math.round(h * (1 - b)).coerceIn(top + 1, h))
    }

    /** Only the part of [bmp] that [crop] leaves, as a bitmap of its own (a source rectangle drawn scaled lets the cut-off edge pixels blend in). */
    internal fun cropped(bmp: android.graphics.Bitmap, crop: FloatArray?): android.graphics.Bitmap {
        if (crop == null) return bmp
        val r = crop(bmp.width, bmp.height, crop)
        return android.graphics.Bitmap.createBitmap(bmp, r[0], r[1], r[2] - r[0], r[3] - r[1])
    }

    /** A DrawingML picture's crop in [xml] (its blipFill's a:srcRect: l, t, r, b in thousandths of a percent) as [crop] takes it; null: none. */
    internal fun srcRect(xml: String?): FloatArray? {
        val attrs = Regex("""<(?:\w+:)?srcRect\b([^>]*)>""").find(xml ?: return null)?.groupValues?.get(1) ?: return null
        return listOf("l", "t", "r", "b").map { k -> (Regex("""\b$k="(-?\d+)"""").find(attrs)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f) / 100_000f }
            .toFloatArray().takeIf { c -> c.any { it != 0f } }
    }

    /**
     * An OpenDocument picture's crop (fo:clip="rect(top, right, bottom, left)", lengths of the picture at its own size) as
     * [crop] takes it, for a picture of [bytes]. Null: none, or a kind whose size is not read here.
     */
    internal fun clip(clip: String?, bytes: ByteArray?): FloatArray? {
        val sides = Regex("""rect\(([^)]*)\)""").find(clip ?: return null)?.groupValues?.get(1)?.split(Regex("""[,\s]+"""))?.filter { it.isNotEmpty() }?.takeIf { it.size == 4 } ?: return null
        val (w, h) = picturePt(bytes ?: return null) ?: return null
        val (t, r, b, l) = sides.map { s -> odfLength(s) ?: 0f }
        return floatArrayOf(l / w, t / h, r / w, b / h).takeIf { c -> c.any { it != 0f } }
    }

    /**
     * A PNG's or JPEG's size in points at its own resolution (PNG pHYs, JPEG JFIF density; 96 dpi when it says none, as
     * LibreOffice takes it). shortcut: other kinds (GIF, WebP, EXIF-only resolution) are null, so their clip is not drawn.
     */
    internal fun picturePt(b: ByteArray): Pair<Float, Float>? {
        fun u16(i: Int) = (b[i].toInt() and 255 shl 8) or (b[i + 1].toInt() and 255)
        fun u32(i: Int) = (u16(i).toLong() shl 16) or u16(i + 2).toLong()
        var dpiX = 96f; var dpiY = 96f
        if (b.size > 24 && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()) {
            var i = 8
            while (i + 8 <= b.size) {
                val len = u32(i).toInt(); val type = String(b, i + 4, 4, Charsets.ISO_8859_1)
                if (type == "pHYs" && len >= 9 && i + 17 <= b.size && b[i + 16].toInt() == 1) { dpiX = (u32(i + 8) * 0.0254f).coerceAtLeast(1f); dpiY = (u32(i + 12) * 0.0254f).coerceAtLeast(1f) }
                if (type == "IDAT" || len < 0) break
                i += 12 + len
            }
            return (u32(16) * 72f / dpiX) to (u32(20) * 72f / dpiY)
        }
        if (b.size < 4 || u16(0) != 0xFFD8) return null
        var i = 2
        while (i + 9 < b.size && b[i] == 0xFF.toByte()) {
            val marker = b[i + 1].toInt() and 255; val len = u16(i + 2)
            if (marker == 0xE0 && String(b, i + 4, 4, Charsets.ISO_8859_1) == "JFIF" && i + 16 <= b.size) when (b[i + 11].toInt()) {
                1 -> { dpiX = u16(i + 12).toFloat(); dpiY = u16(i + 14).toFloat() }
                2 -> { dpiX = u16(i + 12) * 2.54f; dpiY = u16(i + 14) * 2.54f }
            }
            // the frame's header (SOF0–SOF15, not DHT/JPG/DAC): height, then width
            if (marker in 0xC0..0xCF && marker !in setOf(0xC4, 0xC8, 0xCC)) return (u16(i + 7) * 72f / dpiX.coerceAtLeast(1f)) to (u16(i + 5) * 72f / dpiY.coerceAtLeast(1f))
            i += 2 + len
        }
        return null
    }

    /**
     * A shape drawn here (its own outline, or a preset filled with a gradient): each path filled with its fill or gradient
     * and stroked with its line.
     */
    private fun outline(c: Canvas, item: SlideItem.Shape, paint: Paint) {
        val fill = item.shape?.fill; val line = item.shape?.line
        val paths: List<Pair<android.graphics.Path, SlidePath?>> = if (item.paths.isNotEmpty()) item.paths.map { sp ->
            val path = android.graphics.Path()
            fun x(v: Float) = item.x + v * item.w
            fun y(v: Float) = item.y + v * item.h
            for (o in sp.ops) when (o[0].toInt()) {
                0 -> path.moveTo(x(o[1]), y(o[2]))
                1 -> path.lineTo(x(o[1]), y(o[2]))
                2 -> path.cubicTo(x(o[1]), y(o[2]), x(o[3]), y(o[4]), x(o[5]), y(o[6]))
                4 -> path.close()
            }
            path to sp
        } else {
            // a preset's outline from its geometry, placed in the box
            val kind = item.shape?.geometry ?: "rect"
            val geo = ShapeGeometry.presets[kind]
            if (geo != null) geo.paths(item.w, item.h, item.shape?.adjust.orEmpty()).map { sub ->
                val path = android.graphics.Path()
                for (op in sub.ops) when (op) {
                    is ShapeGeometry.Op.Move -> path.moveTo(item.x + op.x, item.y + op.y)
                    is ShapeGeometry.Op.Line -> path.lineTo(item.x + op.x, item.y + op.y)
                    is ShapeGeometry.Op.Cubic -> path.cubicTo(item.x + op.x1, item.y + op.y1, item.x + op.x2, item.y + op.y2, item.x + op.x, item.y + op.y)
                    is ShapeGeometry.Op.Quad -> path.quadTo(item.x + op.x1, item.y + op.y1, item.x + op.x, item.y + op.y)
                    ShapeGeometry.Op.Close -> path.close()
                }
                path to SlidePath(emptyList(), sub.fill, sub.stroke)
            } else listOf(ShapePicture.path(kind, item.w, item.h).apply { offset(item.x, item.y) } to null)
        }
        val g = item.gradient
        item.shape?.let { s -> for ((path, sp) in paths) s.shadowOf(c, path, (sp?.fill ?: true) && (fill != null || g != null)) }
        for ((path, sp) in paths) {
            if ((sp?.fill ?: true) && g != null) {
                paint.shader = shader(g, item.x, item.y, item.w, item.h)
                paint.style = Paint.Style.FILL; paint.color = 0xFF000000.toInt(); c.drawPath(path, paint); paint.shader = null
            } else if ((sp?.fill ?: true) && fill != null) { paint.style = Paint.Style.FILL; paint.color = fill; c.drawPath(path, paint) }
            if ((sp?.stroke ?: true) && line != null) { paint.style = Paint.Style.STROKE; paint.strokeWidth = item.shape?.lineWidth ?: 1f; paint.strokeJoin = Paint.Join.ROUND; paint.color = line
                paint.pathEffect = item.shape?.dashes(); c.drawPath(path, paint); paint.pathEffect = null }
        }
    }

    private fun table(c: Canvas, t: SlideItem.Table, paint: Paint) {
        var y = t.y
        t.cells.forEachIndexed { r, row ->
            // a row grows to its words, as PowerPoint's
            val need = row.mapIndexed { i, (_, text) -> text?.let { SlidesText.height(it, t.cols.getOrElse(i) { 0f }) } ?: 0f }.maxOrNull() ?: 0f
            val h = max(t.rows.getOrElse(r) { 0f }, need)
            var x = t.x
            row.forEachIndexed { i, (fill, text) ->
                val w = t.cols.getOrElse(i) { 0f }
                fill?.let { paint.style = Paint.Style.FILL; paint.color = it; c.drawRect(x, y, x + w, y + h, paint) }
                t.line?.let { paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f; paint.color = it; c.drawRect(x, y, x + w, y + h, paint) }
                text?.let { SlidesText.draw(c, it, x, y, w, h) }
                x += w
            }
            y += h
        }
    }
}
