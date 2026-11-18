package com.kalotrapezis.drive

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt

/*
 * Office › Writer (asked 2026-09-27): .docx and .odt, read into what Writer can show and edit, and written back.
 *
 * What Writer keeps: the text; bold, italic, underline, strikethrough, text size, text colour, highlight and links;
 * headings 1–6, bulleted and numbered lists, and alignment. Everything outside the body — page size and margins,
 * headers and footers, the document's own styles and fonts — is kept by writing the new body into the original
 * package, never by rebuilding the file. Anything in the body Writer cannot keep (images, tables, comments, tracked
 * changes…) is named in [OfficeDoc.unsupported]; such a file opens, and is saved only as a copy — the original is
 * never overwritten (the user's rule). Nothing here depends on Android, so it is tested on the computer.
 */

internal enum class DocAlign { Start, Center, End, Justify }

internal data class DocRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    // Not bold / not italic though the paragraph's style is (Word's w:b w:val="0", ODF's font-weight normal): a heading's
    // words unbolded on purpose (asked 14 Oct).
    val unbold: Boolean = false,
    val unitalic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val sizePt: Float? = null, // null: the document's own size
    val color: Int? = null, // 0xRRGGBB
    val highlight: Int? = null, // 0xRRGGBB
    val link: String? = null,
    val image: DocImage? = null, // then [text] is OBJECT_MARK
    val font: String? = null, // the file's own letter settings Writer does not edit, raw: fonts, small caps, spacing, language… (Word's rPr elements; ODF's attributes)
    val script: Int = 0, // 1 superscript, -1 subscript
    val comments: List<String> = emptyList(), // the ids of the comments over these letters (OfficeDoc.comments)
    val cite: String? = null, // a citation's letters: Word's field ("CITATION Smi20 \l 1033"), or an ODT bibliography mark's source tag
    val change: DocChange? = null, // Review › Track changes: letters someone inserted or deleted
    val note: DocNote? = null, // a footnote's or endnote's reference: its number (superscript), the note itself here
    val control: String? = null, // a form's content control the letters fill (Word's w:sdtPr, raw): text, drop-down, date, check box
) {
    fun sameLook(o: DocRun) = image == null && o.image == null && copy(text = "") == o.copy(text = "")
}

/**
 * A comment on some letters (asked 2026-09-29): who wrote it, when (ISO), what it says. One from the file goes back as
 * the file had it ([raw]: Word's w:comment, or ODT's annotation contents) — replies and their links stay; a new one is
 * written from its fields.
 */
internal data class DocComment(val id: String, val author: String, val date: String?, val text: String, val raw: String? = null, val initials: String? = null,
    val parent: String? = null /* a reply: the comment it answers */, val done: Boolean = false /* resolved (Word's done, LibreOffice's resolved) */)

/**
 * Review › Track changes (asked 2026-09-30): letters [deleted] or inserted by [author] at [date] (ISO). Word's w:ins /
 * w:del (a move counts as a deletion and an insertion, as Word's own Accept treats it).
 */
internal data class DocChange(val deleted: Boolean, val author: String, val date: String?)

/**
 * A footnote or endnote (asked 2026-09-30): its paragraphs. In the text it is its reference, a run whose letters are its
 * number — worked out from the order, as Word does ([OfficeFormats.numberNotes]). ponytail: two notes alike side by side,
 * nothing between them, merge into one; give notes an id if that ever happens.
 */
internal data class DocNote(val end: Boolean, val paras: List<DocPara>)

/** A list that tags every run added to it with the comments open at that moment (the readers' ranges). */
internal class CommentedRuns(private val open: Collection<String>, private val cite: () -> String? = { null }, private val change: () -> DocChange? = { null }, private val control: () -> String? = { null }) : ArrayList<DocRun>() {
    override fun add(element: DocRun) = super.add((if (open.isEmpty()) element else element.copy(comments = open.toList())).let { r -> cite()?.let { r.copy(cite = it) } ?: r }.let { r -> control()?.let { r.copy(control = it) } ?: r }
        .let { r -> change()?.let { r.copy(change = it) } ?: r })
}

/** A picture, a chart or a shape stands in the text as this one character. */
internal const val OBJECT_MARK = '\uFFFC'

/**
 * A text box's words read for the pages only: what the reading notes (features Writer lacks — a file may become
 * copy-only) and the comments it is inside stay as they were; null if they cannot be read.
 */
private fun <T> aside(notes: MutableSet<String>, comments: MutableSet<String>, read: () -> T): T? {
    val n = notes.toList(); val c = comments.toList()
    return runCatching(read).getOrNull().also { notes.clear(); notes.addAll(n); comments.clear(); comments.addAll(c) }
}

/**
 * A picture (or chart, shape, embedded object) in the text. One from the file goes back exactly as it was ([raw], the
 * file's own XML, which points at its [part] inside the package); a new one has its [bytes]. [part] is null for what
 * is not a picture — it shows as a grey box. Sizes in points.
 */
/** Insert › Signature's pictures: their keys start so until the file is saved (then their name, "Signature n", says it). */
internal const val SIGNATURE_KEY = "sig-"

internal class DocImage(val key: String, val part: String?, val widthPt: Float, val heightPt: Float, val raw: String? = null,
    val bytes: ByteArray? = null, val extension: String = "png", val wrap: PictureWrap? = null /* null: as the file has it */,
    val table: DocTable? = null /* the editor's stand-in for a table */,
    val onPage: Pair<Float, Float>? = null /* a new floating one's top left on its page (pt): Draw › Draw on page */,
    val box: List<DocPara>? = null /* a text box's words, as read from [raw] (drawn on the pages; the file keeps [raw]) */,
    val chart: DocChart? = null /* a chart: what it shows, read from its part (drawn on the pages; the file keeps it) */,
    val look: String? = null /* an OpenDocument frame's style, its graphic properties as attributes: where it goes, its fill and line (shown only) */,
    val math: MathNode? = null /* an equation (Word's OMML, [raw]): what it shows, drawn by MathDrawable */,
    val group: List<GroupPart>? = null /* a group (Word's wpg): its pieces where they are in it, drawn one by one */,
    val film: String? = null /* Insert › Video: a new one's web address or copy on the phone ([PictureXml.video] finds a saved one's in [raw]) */) {
    fun with(raw: String? = this.raw, widthPt: Float = this.widthPt, heightPt: Float = this.heightPt, wrap: PictureWrap? = this.wrap,
        table: DocTable? = this.table, box: List<DocPara>? = this.box, key: String = this.key) = DocImage(key, part, widthPt, heightPt, raw, bytes, extension, wrap, table, onPage, box, chart, look, math, group, film)
    override fun equals(other: Any?) = other is DocImage && other.key == key && other.part == part && other.raw == raw && other.widthPt == widthPt && other.heightPt == heightPt && other.wrap == wrap && other.table == table && other.onPage == onPage
    override fun hashCode() = key.hashCode()
    override fun toString() = "DocImage($key, $part, ${widthPt}x$heightPt)"
}

/** How the text goes around a picture — Word's Wrap Text (asked 2026-09-27). */
internal enum class PictureWrap(val label: String, @androidx.annotation.StringRes val labelRes: Int) {
    InLine("In line with text", com.kalotrapezis.drive.R.string.office_wrap_inline), Square("Square", com.kalotrapezis.drive.R.string.office_wrap_square), TopBottom("Top and bottom", com.kalotrapezis.drive.R.string.office_wrap_top_bottom),
    Behind("Behind text", com.kalotrapezis.drive.R.string.office_wrap_behind), InFront("In front of text", com.kalotrapezis.drive.R.string.office_wrap_in_front)
}

/** A picture's own XML, changed by the Picture tab: its size and its wrapping, in each format's words. */
internal object PictureXml {
    private val DOCX_FRAME = Regex("""<wp:(inline|anchor)(\s[^>]*)?>([\s\S]*)</wp:\1>""")

    /** A picture Writer can resize and re-wrap: a new one, or a file's picture — not a chart, shape or old VML drawing. */
    fun editable(img: DocImage): Boolean {
        val raw = img.raw ?: return img.bytes != null || img.chart != null
        if (raw.startsWith("<draw:custom-shape") || raw.startsWith("<draw:g")) return true
        return if (raw.startsWith("<draw:frame")) "<draw:image" in raw || "<draw:text-box" in raw || img.chart != null
            else ("<pic:pic" in raw || "<wps:wsp" in raw || img.chart != null) && !raw.startsWith("<mc:") && DOCX_FRAME.containsMatchIn(raw)
    }

    /** A video's film (Insert › Video): a new one's, Word's Online Video (its player's address, YouTube's as its watch page) or
     *  LibreOffice's media frame (a web address, or the film's part in the file); null: not a video. */
    fun video(img: DocImage): String? = img.film ?: img.raw?.let { raw ->
        fun plain(t: String) = t.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
        Regex("""<draw:plugin\b[^>]*xlink:href="([^"]+)"""").find(raw)?.let { return plain(it.groupValues[1]).removePrefix("./") }
        // Word's Online Video as LibreOffice keeps it: its picture linked to the address
        Regex("""^<draw:a\b[^>]*xlink:href="(https?:[^"]+)"""").find(raw)?.let { m -> plain(m.groupValues[1]).takeIf { OfficeVideo.youtube(it) != null }?.let { return it } }
        Regex("""webVideoPr\b[^>]*embeddedHtml="([^"]*)"""").find(raw)?.let { m -> Regex("""src="([^"]+)"""").find(plain(m.groupValues[1]))?.groupValues?.get(1)?.let(::plain) }
            ?.let { src -> OfficeVideo.youtube(src)?.let { "https://www.youtube.com/watch?v=$it" } ?: src }
    }

    /** Word's Online Video kept in a new picture's drawing (wp15:webVideoPr): its player, as Word writes it — YouTube's own, another site's address framed. */
    fun webVideo(url: String, w: Float, h: Float): String {
        fun esc(t: String) = t.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        val src = OfficeVideo.youtube(url)?.let { "https://www.youtube.com/embed/$it?feature=oembed" } ?: url
        val html = """<iframe width="${w.toInt()}" height="${h.toInt()}" src="$src" frameborder="0" allowfullscreen></iframe>"""
        return """<a:extLst><a:ext uri="{C809E66F-F1BF-436E-b5F7-EEA9579F0CBA}"><wp15:webVideoPr xmlns:wp15="http://schemas.microsoft.com/office/word/2012/wordprocessingDrawing" embeddedHtml="${esc(html)}" h="${h.toInt()}" w="${w.toInt()}"/></a:ext></a:extLst>"""
    }

    /** [kind]'s word as shown (kind's English stays the ID, compared and logged). */
    @androidx.annotation.StringRes fun kindRes(kind: String): Int = when (kind) {
        "Signature" -> com.kalotrapezis.drive.R.string.office_kind_signature; "Table" -> com.kalotrapezis.drive.R.string.office_kind_table; "Equation" -> com.kalotrapezis.drive.R.string.office_kind_equation
        "Video" -> com.kalotrapezis.drive.R.string.office_kind_video; "Audio" -> com.kalotrapezis.drive.R.string.slides_audio; "Chart" -> com.kalotrapezis.drive.R.string.office_kind_chart; "SmartArt" -> com.kalotrapezis.drive.R.string.office_kind_smartart
        "WordArt" -> com.kalotrapezis.drive.R.string.office_kind_wordart; "Text box" -> com.kalotrapezis.drive.R.string.office_kind_text_box; "Embedded object" -> com.kalotrapezis.drive.R.string.office_kind_object
        "Shape" -> com.kalotrapezis.drive.R.string.office_kind_shape; else -> com.kalotrapezis.drive.R.string.office_kind_image
    }

    /** What it is, in a word, for the grey box shown in place of what Writer cannot draw. */
    fun kind(img: DocImage): String {
        if (img.key.startsWith(SIGNATURE_KEY) || img.raw?.let { Regex("""<wp:docPr\b[^>]*\bname="Signature""").containsMatchIn(it) } == true) return "Signature"
        if (img.table != null) return "Table"
        if (img.math != null) return "Equation"
        video(img)?.let { return if (OfficeVideo.audio(it)) "Audio" else "Video" }
        val raw = img.raw ?: return "Image"
        return when {
            "drawingml/2006/chart" in raw || "<draw:object" in raw && "chart" in raw -> "Chart"
            "diagram" in raw -> "SmartArt"
            // Words with no box round them, bent or in Writer's WordArt style: Word's and LibreOffice's WordArt (fontwork).
            "prstTxWarp" in raw || "draw:fontwork" in raw || "Tetra_20_art_" in raw || "txbx" in raw && Regex("""<wps:bodyPr\b[^>]*wrap="none"""").containsMatchIn(raw) -> "WordArt"
            "txbx" in raw || "<draw:text-box" in raw -> "Text box"
            "OLEObject" in raw || "<draw:object" in raw -> "Embedded object"
            "<wps:" in raw || "<v:" in raw || "<draw:custom-shape" in raw || "<draw:rect" in raw || "<draw:line" in raw -> "Shape"
            else -> "Image"
        }
    }

    /**
     * Where a floating picture stands, from what (page, margin — a column counts as the margin — or its paragraph),
     * in points or by alignment ("left", "center", "right", "top", "bottom"). Null: it stands in the line of text.
     */
    data class Place(val fromH: String, val x: Float?, val alignH: String?, val fromV: String, val y: Float?, val alignV: String?, val wrap: PictureWrap)

    fun placeOf(img: DocImage): Place? {
        val wrap = wrapOf(img) ?: PictureWrap.Square
        if (wrap == PictureWrap.InLine) return null
        // A new one: where it was drawn on its page, or (as it is written) at its paragraph's top left.
        val raw = img.raw ?: return img.onPage?.let { (x, y) -> Place("page", x, null, "page", y, null, wrap) } ?: Place("margin", 0f, null, "paragraph", 0f, null, wrap)
        if (raw.startsWith("<draw:frame") || raw.startsWith("<draw:custom-shape") || raw.startsWith("<draw:g")) {
            val head = raw.substring(0, raw.indexOf('>'))
            val group = if (raw.startsWith("<draw:g")) Arrange.odtGroupBox(raw) else null // a group stands where its pieces are
            fun attr(n: String) = Regex("""\s$n="([^"]*)"""").find(head)?.groupValues?.get(1)
            // Its style may place it on the page (a letterhead "from the top of the page, centred" — asked 2026-10-13: the
            // box came down to its paragraph): style:vertical-rel / horizontal-rel / horizontal-pos.
            fun styled(n: String) = img.look?.let { Regex("""\s$n="([^"]*)"""").findAll(it).lastOrNull()?.groupValues?.get(1) } // the frame's own, after its parents' 
            val pageV = styled("style:vertical-rel")?.startsWith("page") == true
            val pageH = styled("style:horizontal-rel")?.startsWith("page") == true
            val from = if (attr("text:anchor-type") == "page" || ODT_PAGE_REL in head || pageV) "page" else "paragraph"
            val alignH = styled("style:horizontal-pos")?.takeIf { it in setOf("center", "left", "right") }
            val turned = Arrange.odtTransform(head)?.second // turned: its place is in the transform
            return Place(if (from == "page" || pageH) "page" else "margin", group?.get(0) ?: turned?.first ?: odfLength(attr("svg:x")) ?: 0f, alignH, from, group?.get(1) ?: turned?.second ?: odfLength(attr("svg:y")) ?: 0f, null, wrap)
        }
        if ("<wp:anchor" !in raw) return null
        fun axis(tag: String): Triple<String, Float?, String?> {
            val m = Regex("""<wp:$tag relativeFrom="([^"]*)"[^>]*>([\s\S]*?)</wp:$tag>""").find(raw) ?: return Triple("margin", 0f, null)
            val from = when (m.groupValues[1]) { "page" -> "page"; "paragraph", "line", "character" -> "paragraph"; else -> "margin" }
            val off = Regex("""<wp:posOffset>(-?\d+)</wp:posOffset>""").find(m.groupValues[2])?.groupValues?.get(1)?.toFloatOrNull()?.div(12700f)
            val align = Regex("""<wp:align>(\w+)</wp:align>""").find(m.groupValues[2])?.groupValues?.get(1)
            return Triple(from, off, align)
        }
        val (fh, x, ah) = axis("positionH"); val (fv, y, av) = axis("positionV")
        // Pen strokes: placed by their picture's box (the fallback's margins), from the same origin.
        if ("wordprocessingInk" in raw) {
            val style = Regex("""<v:shape\b[^>]*\bstyle="([^"]*)"""").find(raw)?.groupValues?.get(1)
            fun pt(key: String) = style?.let { Regex("""(?:^|;)$key:(-?[\d.]+)pt""").find(it)?.groupValues?.get(1)?.toFloatOrNull() }
            if (pt("margin-left") != null && pt("margin-top") != null) return Place(if (fh == "paragraph") "margin" else fh, pt("margin-left"), ah, fv, pt("margin-top"), av, wrap)
        }
        return Place(if (fh == "paragraph") "margin" else fh, x, ah, fv, y, av, wrap)
    }

    /** Its wrapping as the file has it (null: an ODT frame's, which lives in a style Writer does not look up). */
    fun wrapOf(img: DocImage): PictureWrap? {
        img.wrap?.let { return it }
        val raw = img.raw ?: return PictureWrap.InLine
        if (raw.startsWith("<draw:frame") || raw.startsWith("<draw:custom-shape") || raw.startsWith("<draw:g")) return if ("text:anchor-type=\"as-char\"" in raw.substring(0, raw.indexOf('>'))) PictureWrap.InLine
            else Regex("""Tetra_20_wrap_20_(\w+?)(?:$ODT_PAGE_REL)?"""").find(raw)?.groupValues?.get(1)?.let { n -> PictureWrap.entries.firstOrNull { it.name == n } }
            ?: Regex("""Tetra_20_shape_\w+?_\w+?_\d+(?:-[A-Za-z]+)*_([A-Za-z]+)(?:$ODT_PAGE_REL)?"""").find(raw)?.groupValues?.get(1)?.let { n -> PictureWrap.entries.firstOrNull { it.name == n } }
            // Another's frame: its style's wrap (a letterhead the text runs through no longer pushed the text down).
            ?: img.look?.let { l -> fun v(n: String) = Regex("""\s$n="([^"]*)"""").findAll(l).lastOrNull()?.groupValues?.get(1)
                when (v("style:wrap")) { "run-through" -> if (v("style:run-through") == "background") PictureWrap.Behind else PictureWrap.InFront; "none" -> PictureWrap.TopBottom; null -> null; else -> PictureWrap.Square } }
        return when {
            "<wp:inline" in raw -> PictureWrap.InLine
            "<wp:wrapTopAndBottom" in raw -> PictureWrap.TopBottom
            "<wp:wrapNone" in raw -> if (Regex("""behindDoc="(1|true)"""").containsMatchIn(raw)) PictureWrap.Behind else PictureWrap.InFront
            "<wp:anchor" in raw -> PictureWrap.Square
            else -> null
        }
    }

    fun resized(img: DocImage, widthPt: Float, heightPt: Float): DocImage {
        val raw = img.raw ?: return img.with(widthPt = widthPt, heightPt = heightPt)
        val out = if (raw.startsWith("<draw:g")) Arrange.odtGroupMoved(raw, null, widthPt, heightPt) else if (raw.startsWith("<draw:frame") || raw.startsWith("<draw:custom-shape")) {
            val end = raw.indexOf('>')
            raw.substring(0, end).replace(Regex("""svg:width="[^"]*""""), "svg:width=\"${OdtWriter.fmt(widthPt)}pt\"")
                .replace(Regex("""svg:height="[^"]*""""), "svg:height=\"${OdtWriter.fmt(heightPt)}pt\"") + raw.substring(end)
        } else {
            // The frame's extent and the picture's own (a:ext) are the same numbers: both change.
            val m = Regex("""<wp:extent cx="(\d+)" cy="(\d+)"""").find(raw) ?: return img
            raw.replace("cx=\"${m.groupValues[1]}\" cy=\"${m.groupValues[2]}\"", "cx=\"${(widthPt * 12700).toLong()}\" cy=\"${(heightPt * 12700).toLong()}\"")
        }
        return img.with(raw = out, widthPt = widthPt, heightPt = heightPt)
    }

    /** Word: wp:inline for in line; otherwise wp:anchor at the paragraph's top left (or where it already was). */
    fun docx(xml: String, wrap: PictureWrap): String {
        val m = DOCX_FRAME.find(xml) ?: return xml
        val inner = m.groupValues[3]
        val ns = Regex("""\sxmlns:\w+="[^"]*"""").findAll(m.groupValues[2]).joinToString("") { it.value }
        val extent = Regex("""<wp:extent[^>]*/>""").find(inner)?.value ?: return xml
        val effect = Regex("""<wp:effectExtent[^>]*/>""").find(inner)?.value ?: ""
        val rest = inner.indexOf("<wp:docPr").takeIf { it >= 0 }?.let { inner.substring(it) } ?: return xml
        val frame = if (wrap == PictureWrap.InLine)
            """<wp:inline distT="0" distB="0" distL="0" distR="0"$ns>$extent$effect${rest.replace(Regex("""<wp14:sizeRel[HV][\s\S]*?</wp14:sizeRel[HV]>"""), "")}</wp:inline>"""
        else {
            val place = (if (m.groupValues[1] == "anchor") Regex("""<wp:simplePos[^>]*/>[\s\S]*</wp:positionV>""").find(inner)?.value else null)
                ?: """<wp:simplePos x="0" y="0"/><wp:positionH relativeFrom="column"><wp:posOffset>0</wp:posOffset></wp:positionH><wp:positionV relativeFrom="paragraph"><wp:posOffset>0</wp:posOffset></wp:positionV>"""
            val how = when (wrap) { PictureWrap.Square -> """<wp:wrapSquare wrapText="bothSides"/>"""; PictureWrap.TopBottom -> "<wp:wrapTopAndBottom/>"; else -> "<wp:wrapNone/>" }
            """<wp:anchor distT="0" distB="0" distL="114300" distR="114300" simplePos="0" relativeHeight="${Regex("""relativeHeight="(\d+)"""").find(m.groupValues[2])?.groupValues?.get(1) ?: "251659264"}" behindDoc="${if (wrap == PictureWrap.Behind) 1 else 0}" locked="0" layoutInCell="1" allowOverlap="1"$ns>$place$extent$effect$how$rest</wp:anchor>"""
        }
        return xml.replaceRange(m.range, frame)
    }

    /** ODF: the frame's anchor, and a graphic style of Writer's own saying how the text goes around it. */
    fun odt(xml: String, wrap: PictureWrap, onPage: Pair<Float, Float>? = null): String {
        // Turned (Arrange › Rotate): its place is in its draw:transform — set straight, placed, turned again.
        val turn = Arrange.odtTransform(xml.substring(0, xml.indexOf('>').coerceAtLeast(0)))?.first
        if (turn != null) return Arrange.odtTurned(odt(Arrange.odtTurned(xml, 0f), wrap, onPage), turn)
        // A group (LibreOffice's draw:g): its anchor and wrap on it, its place in its pieces (moved together).
        if (xml.startsWith("<draw:g")) {
            val moved = onPage?.takeIf { wrap != PictureWrap.InLine }?.let { Arrange.odtGroupMoved(xml, it) } ?: xml
            val e = moved.indexOf('>')
            val head = moved.substring(0, e).replace(Regex("""\s(text:anchor-type|draw:style-name)="[^"]*""""), "")
            return head + " draw:style-name=\"${odtStyleName(wrap, onPage != null && wrap != PictureWrap.InLine)}\" text:anchor-type=\"${if (wrap == PictureWrap.InLine) "as-char" else "char"}\"" + moved.substring(e)
        }
        val end = xml.indexOf('>')
        val at = onPage?.takeIf { wrap != PictureWrap.InLine }?.let { (x, y) -> " svg:x=\"${OdtWriter.fmt(x)}pt\" svg:y=\"${OdtWriter.fmt(y)}pt\"" } ?: ""
        // A shape Writer made keeps its look: its style's name changes only in how the text goes around it (and whether
        // it is placed on the page).
        if (xml.startsWith("<draw:custom-shape") && "Tetra_20_shape_" in xml) {
            val head = xml.substring(0, end).replace(Regex("""(Tetra_20_shape_\w+?_\w+?_\d+(?:-[A-Za-z]+)*_)[A-Za-z]+(?:$ODT_PAGE_REL)?"""), "$1${wrap.name}" + if (at.isEmpty()) "" else ODT_PAGE_REL)
                .replace(Regex("""\s(text:anchor-type|svg:x|svg:y)="[^"]*""""), "") + " text:anchor-type=\"${if (wrap == PictureWrap.InLine) "as-char" else "char"}\"" + at
            return head + xml.substring(end)
        }
        val head = xml.substring(0, end).replace(Regex("""\s(text:anchor-type|draw:style-name|svg:x|svg:y)="[^"]*""""), "")
        return head + " draw:style-name=\"${odtStyleName(wrap, at.isNotEmpty())}\" text:anchor-type=\"${if (wrap == PictureWrap.InLine) "as-char" else "char"}\"" + at + xml.substring(end)
    }
    /** Placed from the page's corner (Draw on page) rather than its paragraph's: a style of its own. */
    const val ODT_PAGE_REL = "_20_page"
    fun odtStyleName(wrap: PictureWrap, onPage: Boolean = false) = "Tetra_20_wrap_20_${wrap.name}" + if (onPage) ODT_PAGE_REL else ""
    /** Word: placed [onPage] (pt from the page's top left) instead of where it was. */
    fun docxOnPage(xml: String, onPage: Pair<Float, Float>): String = xml.replace(Regex("""<wp:positionH[\s\S]*?</wp:positionV>"""),
        """<wp:positionH relativeFrom="page"><wp:posOffset>${(onPage.first * 12700).toLong()}</wp:posOffset></wp:positionH><wp:positionV relativeFrom="page"><wp:posOffset>${(onPage.second * 12700).toLong()}</wp:posOffset></wp:positionV>""")
    /** [img] wrapped as [wrap] — its XML too, so the pages show it at once (a new picture's is written on saving). */
    fun withWrap(img: DocImage, wrap: PictureWrap, file: OfficeKind): DocImage {
        val raw = img.raw ?: return img.with(wrap = wrap)
        val place = img.onPage?.takeIf { wrap != PictureWrap.InLine } // placed on its page: it stays there
        return img.with(raw = if (file == OfficeKind.Docx) docx(raw, wrap).let { d -> place?.let { docxOnPage(d, it) } ?: d } else odt(raw, wrap, place), wrap = wrap)
    }
    /** Its place among the floating ones (higher in front): Word's relativeHeight, LibreOffice's draw:z-index; 0: not said. */
    fun zOf(img: DocImage): Long {
        val raw = img.raw ?: return 0
        if (raw.startsWith("<draw:")) return Regex("""\sdraw:z-index="(\d+)"""").find(raw.substring(0, raw.indexOf('>').coerceAtLeast(0)))?.groupValues?.get(1)?.toLongOrNull() ?: 0
        return Regex("""<wp:anchor\b[^>]*\brelativeHeight="(\d+)"""").find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0
    }
    /** [img] at [z] among the floating ones (Arrange › Bring to front / Send to back); null: it has no XML to say it in yet. */
    fun withZ(img: DocImage, z: Long): DocImage? {
        val raw = img.raw ?: return null
        val out = if (raw.startsWith("<draw:")) {
            val end = raw.indexOf('>')
            raw.substring(0, end).replace(Regex("""\sdraw:z-index="[^"]*""""), "") + " draw:z-index=\"$z\"" + raw.substring(end)
        } else Regex("""(<wp:anchor\b[^>]*\brelativeHeight=")\d+""").find(raw)?.let { raw.replaceRange(it.range, it.groupValues[1] + z) } ?: return null
        return img.with(raw = out)
    }
    /** [img] moved to [x], [y] (pt from its page's top left) — Picture › Position — floating as it was. */
    fun placed(img: DocImage, x: Float, y: Float, file: OfficeKind): DocImage {
        val wrap = wrapOf(img)?.takeIf { it != PictureWrap.InLine } ?: PictureWrap.Square
        return withWrap(DocImage(img.key, img.part, img.widthPt, img.heightPt, img.raw, img.bytes, img.extension, wrap, img.table, x to y, img.box, img.chart, img.look, img.math, img.group, img.film), wrap, file)
    }

    /**
     * Insert › Shapes (asked 2026-09-29): a new shape of [kind] (Word's preset name), [fill] (ARGB, null: none) and
     * [line] (null: none) [lineWidth] pt wide, in line with the text — Word's wps:wsp, or LibreOffice's custom shape
     * whose automatic style (named after its look) the writer adds.
     */
    fun newShape(kind: String, file: OfficeKind, fill: Int?, line: Int?, lineWidth: Float, widthPt: Float, heightPt: Float): String {
        fun hex(c: Int) = "%06X".format(c and 0xFFFFFF)
        if (file == OfficeKind.Odt) {
            val style = odtShapeName(fill, line, lineWidth, PictureWrap.InLine, false)
            return """<draw:custom-shape draw:style-name="$style" text:anchor-type="as-char" svg:width="${OdtWriter.fmt(widthPt)}pt" svg:height="${OdtWriter.fmt(heightPt)}pt" draw:z-index="0"><text:p/>${ShapeGeometry.odfGeometry(kind, widthPt, heightPt)?.takeIf { kind != "line" } ?: """<draw:enhanced-geometry svg:viewBox="0 0 21600 21600" draw:type="${ShapePicture.odfType(kind)}"/>"""}</draw:custom-shape>"""
        }
        val cx = (widthPt * 12700).toLong(); val cy = (heightPt * 12700).toLong()
        val id = (System.nanoTime() % 900000 + 100000)
        val fillXml = fill?.let { c -> """<a:solidFill><a:srgbClr val="${hex(c)}">${Math.round((c ushr 24) * 100000 / 255f).takeIf { it < 100000 }?.let { "<a:alpha val=\"$it\"/>" } ?: ""}</a:srgbClr></a:solidFill>""" } ?: "<a:noFill/>"
        // an arrow: Word's line with a triangle at its end (both ends: a double arrow)
        val arrows = when (kind) { "lineArrow" -> """<a:tailEnd type="triangle"/>"""; "lineArrows" -> """<a:headEnd type="triangle"/><a:tailEnd type="triangle"/>"""; else -> "" }
        val lineXml = """<a:ln w="${(lineWidth * 12700).toLong()}">${line?.let { """<a:solidFill><a:srgbClr val="${hex(it)}"/></a:solidFill>""" } ?: "<a:noFill/>"}$arrows</a:ln>"""
        return """<w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"><wp:extent cx="$cx" cy="$cy"/><wp:effectExtent l="0" t="0" r="0" b="0"/><wp:docPr id="$id" name="Shape $id"/><a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:graphicData uri="http://schemas.microsoft.com/office/word/2010/wordprocessingShape"><wps:wsp xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape"><wps:cNvSpPr/><wps:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="${if (kind.startsWith("lineArrow")) "line" else kind}"><a:avLst/></a:prstGeom>$fillXml$lineXml</wps:spPr><wps:bodyPr/></wps:wsp></a:graphicData></a:graphic></wp:inline></w:drawing>"""
    }
    /**
     * Insert › Text box (the plan, 2026-10-01): Word's text box (a rectangle shape holding paragraphs: white, a thin black
     * line), or in an .odt LibreOffice's rectangle with its words in it (its look in Writer's shape style) — in line with
     * the text as made; the caller floats it. Its words are written by [boxText].
     */
    fun newTextBox(file: OfficeKind, widthPt: Float, heightPt: Float, lines: List<String>, art: Int? = null, effects: TextEffects = TextEffects(), warp: TextWarp? = null): String =
        newTextBox(file, widthPt, heightPt, lines, art, effects).let { x -> if (warp == null) x else TextWarp.applied(x, warp, file == OfficeKind.Odt) }
    private fun newTextBox(file: OfficeKind, widthPt: Float, heightPt: Float, lines: List<String>, art: Int?, effects: TextEffects): String {
        // WordArt ([art]: its colour): no box round it, its words big and bold in that colour, on one line.
        val shape = if (art != null) newShape("rect", file, null, null, 0f, widthPt, heightPt) else newShape("rect", file, 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0.75f, widthPt, heightPt)
        return if (file == OfficeKind.Odt) boxText(shape, file, lines, art, effects)
            else boxText(shape.replace("<wps:cNvSpPr/>", """<wps:cNvSpPr txBox="1"/>""")
                .replace("<wps:bodyPr/>", """<wps:txbx><w:txbxContent></w:txbxContent></wps:txbx><wps:bodyPr rot="0" vert="horz" wrap="${if (art != null) "none" else "square"}" lIns="91440" tIns="45720" rIns="91440" bIns="45720" anchor="t"><a:noAutofit/></wps:bodyPr>"""), file, lines, art, effects)
    }
    /** WordArt's words: bold, [ART_PT], its colour. */
    const val ART_PT = 36f
    /** An .odt's WordArt words are in a text style of Writer's, named for their colour; [odtArtStyle] writes it. */
    fun odtArtStyle(name: String): String? = Regex("""Tetra_20_art_([0-9A-F]{6})(_o)?(_s)?""").matchEntire(name)?.let { m ->
        val effects = TextEffects(outline = if (m.groupValues[2].isNotEmpty()) 0 else null, shadow = m.groupValues[3].isNotEmpty()).odt()
        """<style:style style:name="$name" style:family="text"><style:text-properties fo:font-size="${OdtWriter.fmt(ART_PT)}pt" fo:font-weight="bold" style:font-weight-asian="bold" style:font-weight-complex="bold" fo:color="#${m.groupValues[1]}"$effects/></style:style>""" }
    /**
     * A text box's words made [lines], a paragraph each: each line keeps its paragraph's style and each letter its run's
     * look (a letter typed takes the look of the one before it); a new line takes the look of the one before it. When it
     * has no words yet, WordArt's in [art] (its colour).
     */
    fun boxText(xml: String, file: OfficeKind, lines: List<String>, art: Int? = null, effects: TextEffects = TextEffects()): String {
        fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        fun unesc(t: String) = t.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
        if (file == OfficeKind.Odt) {
            // Each paragraph: its opening tag's style, its letters as (span style or null, text).
            val area = Regex("""(<draw:text-box\b[^>]*>)([\s\S]*?)(</draw:text-box>)""").find(xml)
            val inner = area?.groupValues?.get(2) ?: xml.substring(xml.indexOf('>') + 1, xml.indexOf("<draw:enhanced-geometry").coerceAtLeast(xml.indexOf('>') + 1))
            val old = Regex("""<text:p\b([^>]*?)(/>|>([\s\S]*?)</text:p>)""").findAll(inner).map { m ->
                val style = Regex("""\stext:style-name="[^"]*"""").find(m.groupValues[1])?.value ?: ""
                val runs = Regex("""<text:span\b[^>]*?text:style-name="([^"]*)"[^>]*>([\s\S]*?)</text:span>|([^<]+)|<text:tab/>|<text:s(?: text:c="(\d+)")?/>""").findAll(m.groupValues[3]).map { r -> when {
                    r.groupValues[1].isNotEmpty() -> r.groupValues[1] to unesc(r.groupValues[2].replace("<text:tab/>", "\t").replace(Regex("<[^>]+>"), ""))
                    r.groupValues[3].isNotEmpty() -> null to unesc(r.groupValues[3])
                    r.value == "<text:tab/>" -> null to "\t"
                    else -> null to " ".repeat(r.groupValues[4].toIntOrNull() ?: 1)
                } }.toList()
                style to runs
            }.toList()
            val artSpan = art?.let { "Tetra_20_art_%06X".format(it and 0xFFFFFF) + effects.odtName() }
            // a WordArt's look given (made, or changed after): every letter takes it
            val paras = relaid(if (artSpan == null) old else old.map { (st, runs) -> st to runs.map { artSpan to it.second } }, lines, "", artSpan ?: Regex("""<text:span\b[^>]*?\stext:style-name="([^"]*)"""").find(xml)?.groupValues?.get(1)).joinToString("") { (style, runs) ->
                "<text:p$style>" + runs.joinToString("") { (span, t) -> esc(t).replace("\t", "<text:tab/>").let { w -> span?.let { "<text:span text:style-name=\"$it\">$w</text:span>" } ?: w } } + "</text:p>"
            }
            area?.let { m -> return xml.replaceRange(m.range, m.groupValues[1] + paras + m.groupValues[3]) }
            // A shape: its words before its geometry.
            val start = xml.indexOf('>') + 1
            val end = xml.indexOf("<draw:enhanced-geometry").takeIf { it >= 0 } ?: return xml
            val between = xml.substring(start, end).replace(Regex("""<text:(p|list|h)\b[\s\S]*?</text:\1>|<text:p\b[^>]*/>"""), "")
            return xml.substring(0, start) + between + paras + xml.substring(end)
        }
        // A shape with no words yet (asked 16 Oct: a shape's own text): a text area of its own, its words in the middle, centred
        val shaped = "<w:txbxContent>" !in xml
        if (shaped && "<wps:bodyPr" !in xml) return xml
        val src = if (!shaped) xml else xml.replaceFirst("<wps:bodyPr", "<wps:txbx><w:txbxContent></w:txbxContent></wps:txbx><wps:bodyPr")
            .replace("<wps:bodyPr/>", """<wps:bodyPr rot="0" vert="horz" wrap="square" lIns="91440" tIns="45720" rIns="91440" bIns="45720" anchor="ctr"><a:noAutofit/></wps:bodyPr>""")
        // Word: every copy of its words (the older-Word fallback has one too), from the first copy's paragraphs and runs.
        val first = Regex("""<w:txbxContent>([\s\S]*?)</w:txbxContent>""").find(src)?.groupValues?.get(1) ?: return xml
        val old = Regex("""<w:p\b[^>]*?(/>|>([\s\S]*?)</w:p>)""").findAll(first).map { m ->
            val pPr = Regex("""<w:pPr>[\s\S]*?</w:pPr>""").find(m.groupValues[2])?.value ?: ""
            val runs = Regex("""<w:r\b[^>]*>([\s\S]*?)</w:r>""").findAll(m.groupValues[2]).mapNotNull { r ->
                val rPr = Regex("""<w:rPr>[\s\S]*?</w:rPr>""").find(r.groupValues[1])?.value ?: ""
                val text = Regex("""<w:t\b[^>]*>([^<]*)</w:t>|<w:tab/>""").findAll(r.groupValues[1]).joinToString("") { if (it.value == "<w:tab/>") "\t" else unesc(it.groupValues[1]) }
                (rPr to text).takeIf { text.isNotEmpty() }
            }.toList()
            pPr to runs
        }.toList()
        val artPr = art?.let { """<w:rPr><w:b/><w:bCs/><w:color w:val="${"%06X".format(it and 0xFFFFFF)}"/><w:sz w:val="${(ART_PT * 2).toInt()}"/><w:szCs w:val="${(ART_PT * 2).toInt()}"/>${effects.docx()}</w:rPr>""" } ?: ""
        val paras = relaid(if (artPr.isEmpty()) old else old.map { (pPr, runs) -> pPr to runs.map { artPr to it.second } }, lines, if (shaped) """<w:pPr><w:jc w:val="center"/></w:pPr>""" else "", artPr.ifEmpty { null }).joinToString("") { (pPr, runs) ->
            "<w:p>$pPr" + runs.joinToString("") { (rPr, t) -> "<w:r>${rPr ?: ""}" + t.split('\t').joinToString("<w:tab/>") { "<w:t xml:space=\"preserve\">${esc(it)}</w:t>" } + "</w:r>" } + "</w:p>"
        }
        return src.replace(Regex("""<w:txbxContent>[\s\S]*?</w:txbxContent>"""), Regex.escapeReplacement("<w:txbxContent>$paras</w:txbxContent>"))
    }

    /**
     * [lines] laid over the paragraphs a text box had, [old] (each its paragraph look and its runs as (look, letters)):
     * a line keeps the paragraph it came from (matched as the editor matches paragraphs; a new one takes the one
     * before it), each letter left as it was its run's look, a typed one the look of the letter before it (at the start,
     * after it). Lines with nothing to come from take the first paragraph's, [fresh] for their letters.
     */
    fun <P, R> relaid(old: List<Pair<P, List<Pair<R?, String>>>>, lines: List<String>, freshPara: P, fresh: R?): List<Pair<P, List<Pair<R?, String>>>> {
        val from = OfficeFormats.pairParagraphs(old.map { (_, runs) -> DocPara(listOf(DocRun(runs.joinToString("") { it.second }))) }, lines.map { DocPara(listOf(DocRun(it))) })
        // An empty paragraph's letters: the box's first words' look.
        val firstLook = old.firstNotNullOfOrNull { it.second.firstOrNull() }?.first ?: fresh
        var last = 0
        return lines.mapIndexed { j, line ->
            val i = from[j].takeIf { it >= 0 } ?: last
            last = i
            val (look, runs) = old.getOrNull(i) ?: (freshPara to emptyList())
            look to relook(runs.ifEmpty { listOf(firstLook to "") }, line)
        }
    }

    /** [text] in the looks of [runs] (look, letters): letters kept keep theirs, typed ones the replaced one's or the one before (at the start, after). */
    fun <R> relook(runs: List<Pair<R?, String>>, text: String): List<Pair<R?, String>> {
        val was = runs.joinToString("") { it.second }
        val owner = runs.flatMap { (look, t) -> List(t.length) { look } }
        var pre = 0
        while (pre < was.length && pre < text.length && was[pre] == text[pre]) pre++
        var suf = 0
        while (suf < was.length - pre && suf < text.length - pre && was[was.length - 1 - suf] == text[text.length - 1 - suf]) suf++
        // Letters typed over others take the first replaced one's look (as Word's typing over a selection); else the one before.
        val typed = when { pre + suf < was.length -> owner[pre]; pre > 0 -> owner[pre - 1]; owner.isNotEmpty() -> owner[0]; else -> runs.firstOrNull()?.first }
        // the words a piece of what was there (a paragraph split, its first letter taken off): each letter keeps its own
        val inside = if (pre + suf < text.length && text.length > 1) was.indexOf(text) else -1
        val looks = List(text.length) { k -> when {
            inside >= 0 -> owner[inside + k]
            k < pre -> owner[k]
            k >= text.length - suf -> owner[was.length - (text.length - k)]
            else -> typed
        } }
        val out = ArrayList<Pair<R?, String>>()
        text.forEachIndexed { k, c -> if (out.isNotEmpty() && out.last().first == looks[k]) out[out.size - 1] = looks[k] to out.last().second + c else out += looks[k] to c.toString() }
        return out.ifEmpty { listOf(typed to "") }
    }

    /** A text box's words for the pages, until the file is read again: [lines] with the looks [boxText] gives them. */
    fun boxParas(img: DocImage, lines: List<String>): List<DocPara> {
        val old = img.box.orEmpty().map { p -> p to p.runs.filter { it.image == null && it.note == null }.map { r -> r to r.text } }
        return relaid<DocPara?, DocRun>(old, lines, null, null).map { (p, runs) ->
            p?.copy(runs = runs.filter { it.second.isNotEmpty() }.map { (r, t) -> (r ?: DocRun("")).copy(text = t, comments = emptyList(), change = null, cite = null, control = null) })
                ?: DocPara(listOf(DocRun(runs.joinToString("") { it.second })))
        }
    }

    /**
     * Picture › Shape settings: a shape's fill (null: none) and outline (null: none) and its width — Word's spPr (its
     * fill and a:ln replaced, the geometry kept); in an .odt Writer's own shape style named for them (and its wrap, as
     * placed). Null: not a shape.
     */
    fun shapeLook(img: DocImage, fill: Int?, line: Int?, lineWidth: Float, dash: String? = null, shadow: Boolean = false): DocImage? {
        val raw = img.raw ?: return null
        fun hex(c: Int) = "%06X".format(c and 0xFFFFFF)
        if (raw.startsWith("<draw:custom-shape")) {
            val end = raw.indexOf('>')
            val wrap = wrapOf(img) ?: PictureWrap.InLine
            val page = placeOf(img)?.fromV == "page"
            val style = odtShapeName(fill, line, lineWidth, wrap, page, dash, shadow)
            val head = raw.substring(0, end).replace(Regex("""\sdraw:style-name="[^"]*""""), "") + " draw:style-name=\"$style\""
            return img.with(raw = head + raw.substring(end))
        }
        val sp = Regex("""<wps:spPr\b[^>]*>([\s\S]*?)</wps:spPr>""").find(raw) ?: return null
        var inner = sp.groupValues[1]
        inner = inner.replace(Regex("""<a:ln\b[^>]*?(?:/>|>[\s\S]*?</a:ln>)"""), "")
            .replace(Regex("""<a:(noFill|solidFill|gradFill|blipFill|pattFill|grpFill)\b[^>]*?(?:/>|>[\s\S]*?</a:\1>)"""), "")
        val fillXml = fill?.let { c -> """<a:solidFill><a:srgbClr val="${hex(c)}">${Math.round((c ushr 24) * 100000 / 255f).takeIf { it < 100000 }?.let { "<a:alpha val=\"$it\"/>" } ?: ""}</a:srgbClr></a:solidFill>""" } ?: "<a:noFill/>"
        // the old outline's dash and arrowheads kept (only its colour and width are chosen here)
        val kept = Regex("""<a:ln\b[^>]*>([\s\S]*?)</a:ln>""").find(sp.groupValues[1])?.groupValues?.get(1)?.let { o -> Regex("""<a:(headEnd|tailEnd)\b[^>]*/>""").findAll(o).joinToString("") { it.value } }.orEmpty()
        val lineXml = """<a:ln w="${(lineWidth * 12700).toLong()}">${line?.let { """<a:solidFill><a:srgbClr val="${hex(it)}"/></a:solidFill>""" } ?: "<a:noFill/>"}${dash?.let { """<a:prstDash val="$it"/>""" } ?: ""}$kept</a:ln>"""
        // the shadow: Word's "Offset: bottom right" in its effects (others there kept)
        val had = Regex("""<a:effectLst>([\s\S]*?)</a:effectLst>|<a:effectLst/>""").find(inner)
        val effects = had?.groupValues?.get(1).orEmpty().replace(Regex("""<a:outerShdw\b[\s\S]*?</a:outerShdw>|<a:outerShdw\b[^>]*/>"""), "")
        if (had != null) inner = inner.removeRange(had.range)
        val cut = Regex("""<a:(prstShdw|reflection|softEdge)\b""").find(effects)?.range?.first ?: effects.length
        val effectXml = if (shadow) "<a:effectLst>${effects.substring(0, cut)}$OUTER_SHADOW${effects.substring(cut)}</a:effectLst>" else if (had != null) "<a:effectLst>$effects</a:effectLst>" else ""
        // In spPr's order: the transform, the geometry, then the fill, then the outline.
        val at = Regex("""</a:prstGeom>|</a:custGeom>|<a:prstGeom\b[^>]*/>""").find(inner)?.range?.last?.plus(1) ?: Regex("""</a:xfrm>""").find(inner)?.range?.last?.plus(1) ?: 0
        inner = inner.substring(0, at) + fillXml + lineXml + effectXml + inner.substring(at)
        val open = Regex("""<wps:spPr\b[^>]*>""").find(sp.value)!!.value
        return img.with(raw = raw.replaceRange(sp.range, "$open$inner</wps:spPr>"))
    }
    /** Its description for screen readers (Word's docPr descr, LibreOffice's svg:desc). */
    fun altOf(img: DocImage): String = img.raw?.let { r -> Regex("""<wp:docPr\b[^>]*\bdescr="([^"]*)"""").find(r)?.groupValues?.get(1)
        ?: Regex("""<svg:desc>([^<]*)</svg:desc>""").find(r)?.groupValues?.get(1) }
        ?.replace("&quot;", "\"")?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&amp;", "&").orEmpty()
    fun withAlt(img: DocImage, alt: String): DocImage? {
        val raw = img.raw ?: return null
        val e = OfficeFormats.esc(alt).replace("\"", "&quot;")
        if (raw.startsWith("<draw:")) {
            val bare = raw.replace(Regex("""<svg:desc>[^<]*</svg:desc>"""), "")
            val end = bare.indexOf('>') + 1
            return img.with(raw = if (alt.isBlank()) bare else bare.substring(0, end) + "<svg:desc>$e</svg:desc>" + bare.substring(end))
        }
        val doc = Regex("""<wp:docPr\b[^>]*?(/?)>""").find(raw) ?: return null
        val head = doc.value.replace(Regex("""\sdescr="[^"]*""""), "").let { h -> if (alt.isBlank()) h else h.replace(Regex("""(/?)>$"""), " descr=\"$e\"$1>") }
        return img.with(raw = raw.replaceRange(doc.range, head))
    }

    /** Writer's own shape's style, named for its look: Tetra_20_shape_<fill|none>_<line|none>_<width×100>[-<dash>][-shadow]_<wrap>[_20_page]. */
    fun odtShapeName(fill: Int?, line: Int?, lineWidth: Float, wrap: PictureWrap, page: Boolean, dash: String? = null, shadow: Boolean = false) =
        "Tetra_20_shape_${fill?.let { "%08X".format(it) } ?: "none"}_${line?.let { "%06X".format(it and 0xFFFFFF) } ?: "none"}_${(lineWidth * 100).toInt()}" +
            "${dash?.let { "-$it" } ?: ""}${if (shadow) "-shadow" else ""}_${wrap.name}${if (page && wrap != PictureWrap.InLine) ODT_PAGE_REL else ""}"
    private const val OUTER_SHADOW = """<a:outerShdw blurRad="50800" dist="38100" dir="2700000" algn="tl" rotWithShape="0"><a:srgbClr val="000000"><a:alpha val="40000"/></a:srgbClr></a:outerShdw>"""
    /** The automatic graphic style a shape Writer made is named for: its fill, outline and wrap. */
    fun odtShapeStyle(name: String): String? {
        val m = Regex("""Tetra_20_shape_(\w+?)_(\w+?)_(\d+)((?:-[A-Za-z]+)*)_([A-Za-z]+)($ODT_PAGE_REL)?""").matchEntire(name) ?: return null
        val wrap = PictureWrap.entries.firstOrNull { it.name == m.groupValues[5] } ?: PictureWrap.InLine
        val base = Regex("""<style:graphic-properties ([^/]*)/>""").find(odtStyle(wrap, m.groupValues[6].isNotEmpty()))?.groupValues?.get(1) ?: ""
        val extra = m.groupValues[4].split('-').filter { it.isNotEmpty() }
        val dash = extra.firstOrNull { it != "shadow" }?.let(LineDash::odfName)
        val fill = m.groupValues[1].takeIf { it != "none" }?.toLong(16)
        val fillXml = if (fill == null) """draw:fill="none"""" else """draw:fill="solid" draw:fill-color="#${"%06X".format(fill and 0xFFFFFF)}"""" +
            Math.round((fill ushr 24) * 100 / 255f).let { if (it < 100) """ draw:opacity="$it%"""" else "" }
        val lineXml = (m.groupValues[2].takeIf { it != "none" }?.let { """draw:stroke="${if (dash != null) "dash\" draw:stroke-dash=\"$dash" else "solid"}" svg:stroke-color="#$it" svg:stroke-width="${m.groupValues[3].toInt() / 100f}pt"""" } ?: """draw:stroke="none"""") +
            if ("shadow" in extra) """ draw:shadow="visible" draw:shadow-offset-x="0.075cm" draw:shadow-offset-y="0.075cm" draw:shadow-color="#000000" draw:shadow-opacity="40%"""" else ""
        // Words in it (a text box) fill its width from the top, Word's insets round them; it keeps its size.
        val textArea = """draw:textarea-horizontal-align="justify" draw:textarea-vertical-align="top" draw:auto-grow-height="false" draw:auto-grow-width="false" fo:wrap-option="wrap" fo:padding-left="7.2pt" fo:padding-right="7.2pt" fo:padding-top="3.6pt" fo:padding-bottom="3.6pt""""
        return """<style:style style:name="$name" style:family="graphic"><style:graphic-properties $fillXml $lineXml $textArea $base/></style:style>"""
    }
    fun odtStyle(wrap: PictureWrap, onPage: Boolean = false): String {
        val props = if (onPage) when (wrap) {
            PictureWrap.Square -> """style:wrap="parallel" style:number-wrapped-paragraphs="no-limit""""
            PictureWrap.TopBottom -> """style:wrap="none""""
            PictureWrap.Behind -> """style:wrap="run-through" style:run-through="background""""
            else -> """style:wrap="run-through" style:run-through="foreground""""
        } + """ style:vertical-pos="from-top" style:vertical-rel="page" style:horizontal-pos="from-left" style:horizontal-rel="page"""" else when (wrap) {
            PictureWrap.InLine -> """style:vertical-pos="top" style:vertical-rel="baseline""""
            else -> when (wrap) {
                PictureWrap.Square -> """style:wrap="parallel" style:number-wrapped-paragraphs="no-limit""""
                PictureWrap.TopBottom -> """style:wrap="none""""
                PictureWrap.Behind -> """style:wrap="run-through" style:run-through="background""""
                else -> """style:wrap="run-through" style:run-through="foreground""""
            } + """ style:vertical-pos="top" style:vertical-rel="paragraph" style:horizontal-pos="left" style:horizontal-rel="paragraph""""
        }
        return """<style:style style:name="${odtStyleName(wrap, onPage)}" style:family="graphic"><style:graphic-properties $props/></style:style>"""
    }
}

/** In a header or footer laid out on the pages: the page's number, the number of pages (private-use characters). */
internal const val PAGE_FIELD = '\uE001'
internal const val PAGES_FIELD = '\uE002'

/** A line break inside a paragraph (Shift+Enter in Word): Unicode's line separator, in a run's text. */
internal const val LINE_BREAK = '\u2028'
/**
 * A field kept with its shown letters (a date, a reference, a sequence number — asked 2026-10-13): stored in [DocRun.cite]
 * after this mark, so the citations' own code passes it by and Word gets the field back as it was.
 */
internal const val FIELD_CITE = '\u0002'
internal fun isField(cite: String?) = cite?.firstOrNull() == FIELD_CITE
/** Fields whose letters are whole paragraphs: kept only inside a content control (sdtBlock), else a copy. */

/** A list item: bulleted or numbered, how deep (0 = top), and the number it starts at when that is not 1. */
internal data class DocList(val ordered: Boolean, val level: Int, val start: Int? = null)

/**
 * A paragraph's own formatting that Writer does not edit — spacing, indents, tab stops, borders, a page break before it,
 * a style such as Title or Quote, one font for the whole paragraph — carried through as the file had it (asked
 * 2026-09-27), raw: [style] is the file's own style name, [parts] its other properties as (name, XML) in the file's own
 * words, [list] the file's own list it belongs to ("id|pattern", the pattern "n"/"b" for each level).
 */
internal data class DocKeep(val style: String? = null, val parts: List<Pair<String, String>> = emptyList(), val list: String? = null) {
    val isEmpty get() = style == null && parts.isEmpty() && list == null
}

/**
 * Insert › Drop Cap (asked 2026-09-30): the paragraph's first [length] letters [lines] lines tall, beside its first
 * lines ([margin]: out in the margin), [distance] pt from the text. Kept with the paragraph's own formatting: an ODT's
 * style:drop-cap; in Word the letters are a framed paragraph of their own before it — read in, written out again.
 */
internal data class DropCap(val lines: Int = 3, val margin: Boolean = false, val distance: Float = 0f, val length: Int = 1,
    /** Slides' (17 Oct): a raised first letter, in its line, not dropped */ val raised: Boolean = false) {
    companion object {
        /** The paragraph's drop cap, from either kind's words; null: none. */
        fun of(keep: DocKeep?): DropCap? {
            val parts = keep?.parts ?: return null
            parts.firstOrNull { it.first == "dropCap" }?.second?.let { v ->
                val (n, xml) = v.split('|', limit = 2)
                fun a(name: String) = Regex("""\sw:$name="([^"]*)"""").find(xml)?.groupValues?.get(1)
                return DropCap(a("lines")?.toIntOrNull()?.coerceIn(1, 10) ?: 3, a("dropCap") == "margin", (a("hSpace")?.toFloatOrNull() ?: 0f) / 20f, n.toIntOrNull()?.coerceAtLeast(1) ?: 1)
            }
            parts.firstOrNull { it.first == "child" && it.second.startsWith("<style:drop-cap") }?.second?.let { xml ->
                fun a(name: String) = Regex("""\sstyle:$name="([^"]*)"""").find(xml)?.groupValues?.get(1)
                val lines = a("lines")?.toIntOrNull() ?: 1
                if (lines < 2) return null // one line: no drop cap
                return DropCap(lines.coerceIn(2, 10), false, odfLength(a("distance")) ?: 0f, a("length")?.toIntOrNull()?.coerceAtLeast(1) ?: 1)
            }
            return null
        }
        /** Is this one of a drop cap's own parts (not carried to a new paragraph)? */
        fun isPart(part: Pair<String, String>) = part.first == "dropCap" || (part.first == "child" && part.second.startsWith("<style:drop-cap"))
    }
}

internal data class DocPara(val runs: List<DocRun>, val heading: Int = 0, val list: DocList? = null, val align: DocAlign = DocAlign.Start, val keep: DocKeep? = null,
    val table: DocTable? = null /* then this "paragraph" is a table, its one run OBJECT_MARK */) {
    val text: String get() = runs.joinToString("") { it.text }
}

/**
 * A table (2026-09-28): its rows of cells, each cell ordinary paragraphs (a table in a cell too). What Writer does not
 * edit is kept raw, in the file's own words: the table's properties and column grid, each row's and cell's properties.
 */
internal data class DocTable(val props: String, val grid: String, val rows: List<DocRow>)
internal data class DocRow(val props: String, val cells: List<DocCell>)
internal data class DocCell(val props: String, val paras: List<DocPara>)

/** A table standing in the text as a paragraph of its own. */
internal fun tablePara(t: DocTable) = DocPara(listOf(DocRun(OBJECT_MARK.toString())), table = t)
/** An OpenDocument table's marks, before a row's or cell's own attributes: a header row, a merged-away cell. */
internal const val ODT_HEADER = "<header/>"
internal const val ODT_COVERED = "<covered/>"
/**
 * A paragraph style Writer offers (asked 2026-09-29): its name as people see it, and how a paragraph points to it —
 * [heading] 1–6 for a heading, else [id] (the file's own; null: the document's plain text, "Normal").
 */
internal data class StyleEntry(val name: String, val id: String?, val heading: Int = 0)

/**
 * Word's Title, Subtitle and Quote, for a document that has not got them: shown at once, and written into its
 * styles.xml when a paragraph takes one ("NORMAL" becomes the document's own plain style). As Word 2016 makes them.
 */
internal val DOCX_BUILTIN = mapOf(
    // Footnotes and endnotes (Word 2016's): smaller, single-spaced, no space after.
    "FootnoteText" to """<w:style w:type="paragraph" w:styleId="FootnoteText"><w:name w:val="footnote text"/><w:basedOn w:val="NORMAL"/><w:uiPriority w:val="99"/><w:unhideWhenUsed/><w:pPr><w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="auto"/><w:ind w:firstLine="0"/></w:pPr><w:rPr><w:sz w:val="20"/><w:szCs w:val="20"/></w:rPr></w:style>""",
    "EndnoteText" to """<w:style w:type="paragraph" w:styleId="EndnoteText"><w:name w:val="endnote text"/><w:basedOn w:val="NORMAL"/><w:uiPriority w:val="99"/><w:unhideWhenUsed/><w:pPr><w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="auto"/><w:ind w:firstLine="0"/></w:pPr><w:rPr><w:sz w:val="20"/><w:szCs w:val="20"/></w:rPr></w:style>""",
    // A reference list's entries (APA: a hanging indent of half an inch).
    "Bibliography" to """<w:style w:type="paragraph" w:styleId="Bibliography"><w:name w:val="Bibliography"/><w:basedOn w:val="NORMAL"/><w:next w:val="NORMAL"/><w:uiPriority w:val="37"/><w:unhideWhenUsed/><w:pPr><w:ind w:left="720" w:hanging="720"/></w:pPr></w:style>""",
    "Title" to """<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="NORMAL"/><w:next w:val="NORMAL"/><w:uiPriority w:val="10"/><w:qFormat/><w:pPr><w:spacing w:after="80" w:line="240" w:lineRule="auto"/><w:contextualSpacing/></w:pPr><w:rPr><w:spacing w:val="-10"/><w:kern w:val="28"/><w:sz w:val="56"/><w:szCs w:val="56"/></w:rPr></w:style>""",
    "Subtitle" to """<w:style w:type="paragraph" w:styleId="Subtitle"><w:name w:val="Subtitle"/><w:basedOn w:val="NORMAL"/><w:next w:val="NORMAL"/><w:uiPriority w:val="11"/><w:qFormat/><w:pPr><w:spacing w:after="160"/></w:pPr><w:rPr><w:color w:val="595959"/><w:spacing w:val="15"/><w:sz w:val="28"/><w:szCs w:val="28"/></w:rPr></w:style>""",
    "TOCHeading" to """<w:style w:type="paragraph" w:styleId="TOCHeading"><w:name w:val="TOC Heading"/><w:basedOn w:val="NORMAL"/><w:next w:val="NORMAL"/><w:uiPriority w:val="39"/><w:unhideWhenUsed/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="240" w:after="120"/></w:pPr><w:rPr><w:b/><w:sz w:val="32"/><w:szCs w:val="32"/></w:rPr></w:style>""",
    "TOC1" to """<w:style w:type="paragraph" w:styleId="TOC1"><w:name w:val="toc 1"/><w:basedOn w:val="NORMAL"/><w:next w:val="NORMAL"/><w:uiPriority w:val="39"/><w:unhideWhenUsed/><w:pPr><w:spacing w:after="100"/></w:pPr></w:style>""",
    "TOC2" to """<w:style w:type="paragraph" w:styleId="TOC2"><w:name w:val="toc 2"/><w:basedOn w:val="NORMAL"/><w:next w:val="NORMAL"/><w:uiPriority w:val="39"/><w:unhideWhenUsed/><w:pPr><w:spacing w:after="100"/><w:ind w:left="220"/></w:pPr></w:style>""",
    "TOC3" to """<w:style w:type="paragraph" w:styleId="TOC3"><w:name w:val="toc 3"/><w:basedOn w:val="NORMAL"/><w:next w:val="NORMAL"/><w:uiPriority w:val="39"/><w:unhideWhenUsed/><w:pPr><w:spacing w:after="100"/><w:ind w:left="440"/></w:pPr></w:style>""",
    "TOC4" to """<w:style w:type="paragraph" w:styleId="TOC4"><w:name w:val="toc 4"/><w:basedOn w:val="NORMAL"/><w:next w:val="NORMAL"/><w:uiPriority w:val="39"/><w:unhideWhenUsed/><w:pPr><w:spacing w:after="100"/><w:ind w:left="660"/></w:pPr></w:style>""",
    // A figure's or table's caption (Word 2016's): small, italic, blue-grey.
    "Caption" to """<w:style w:type="paragraph" w:styleId="Caption"><w:name w:val="caption"/><w:basedOn w:val="NORMAL"/><w:next w:val="NORMAL"/><w:uiPriority w:val="35"/><w:unhideWhenUsed/><w:qFormat/><w:pPr><w:spacing w:after="200" w:line="240" w:lineRule="auto"/></w:pPr><w:rPr><w:i/><w:iCs/><w:color w:val="44546A"/><w:sz w:val="18"/><w:szCs w:val="18"/></w:rPr></w:style>""",
    "Quote" to """<w:style w:type="paragraph" w:styleId="Quote"><w:name w:val="Quote"/><w:basedOn w:val="NORMAL"/><w:next w:val="NORMAL"/><w:uiPriority w:val="29"/><w:qFormat/><w:pPr><w:spacing w:before="200" w:after="160"/><w:ind w:left="864" w:right="864"/><w:jc w:val="center"/></w:pPr><w:rPr><w:i/><w:iCs/><w:color w:val="404040"/></w:rPr></w:style>""",
)
/** LibreOffice's Title, Subtitle and Quotations, likewise. */
internal val ODT_BUILTIN = mapOf(
    // Footnotes and endnotes (LibreOffice's): smaller, the number hanging.
    "Footnote" to """<style:style style:name="Footnote" style:family="paragraph" style:parent-style-name="Standard" style:class="extra"><style:paragraph-properties fo:margin-left="0.598cm" fo:margin-right="0cm" fo:margin-top="0cm" fo:margin-bottom="0cm" fo:line-height="100%" fo:text-indent="-0.598cm"/><style:text-properties fo:font-size="10pt"/></style:style>""",
    "Endnote" to """<style:style style:name="Endnote" style:family="paragraph" style:parent-style-name="Standard" style:class="extra"><style:paragraph-properties fo:margin-left="0.598cm" fo:margin-right="0cm" fo:margin-top="0cm" fo:margin-bottom="0cm" fo:line-height="100%" fo:text-indent="-0.598cm"/><style:text-properties fo:font-size="10pt"/></style:style>""",
    "Bibliography_20_1" to """<style:style style:name="Bibliography_20_1" style:display-name="Bibliography 1" style:family="paragraph" style:parent-style-name="Standard" style:class="index"><style:paragraph-properties fo:margin-left="1.27cm" fo:margin-right="0cm" fo:text-indent="-1.27cm"/></style:style>""",
    "Title" to """<style:style style:name="Title" style:family="paragraph" style:parent-style-name="Standard" style:next-style-name="Standard" style:class="chapter"><style:paragraph-properties fo:margin-top="0.423cm" fo:margin-bottom="0.212cm" fo:text-align="center" fo:keep-with-next="always"/><style:text-properties fo:font-size="28pt" fo:font-weight="bold"/></style:style>""",
    "Subtitle" to """<style:style style:name="Subtitle" style:family="paragraph" style:parent-style-name="Standard" style:next-style-name="Standard" style:class="chapter"><style:paragraph-properties fo:margin-top="0.106cm" fo:margin-bottom="0.212cm" fo:text-align="center"/><style:text-properties fo:font-size="18pt"/></style:style>""",
    "Contents_20_Heading" to """<style:style style:name="Contents_20_Heading" style:display-name="Contents Heading" style:family="paragraph" style:parent-style-name="Standard" style:next-style-name="Standard" style:class="index"><style:paragraph-properties fo:margin-top="0.423cm" fo:margin-bottom="0.212cm" fo:keep-with-next="always"/><style:text-properties fo:font-size="16pt" fo:font-weight="bold"/></style:style>""",
    "Contents_20_1" to """<style:style style:name="Contents_20_1" style:display-name="Contents 1" style:family="paragraph" style:parent-style-name="Standard" style:class="index"><style:paragraph-properties fo:margin-left="0cm" fo:margin-bottom="0.176cm"/></style:style>""",
    "Contents_20_2" to """<style:style style:name="Contents_20_2" style:display-name="Contents 2" style:family="paragraph" style:parent-style-name="Standard" style:class="index"><style:paragraph-properties fo:margin-left="0.388cm" fo:margin-bottom="0.176cm"/></style:style>""",
    "Contents_20_3" to """<style:style style:name="Contents_20_3" style:display-name="Contents 3" style:family="paragraph" style:parent-style-name="Standard" style:class="index"><style:paragraph-properties fo:margin-left="0.776cm" fo:margin-bottom="0.176cm"/></style:style>""",
    "Contents_20_4" to """<style:style style:name="Contents_20_4" style:display-name="Contents 4" style:family="paragraph" style:parent-style-name="Standard" style:class="index"><style:paragraph-properties fo:margin-left="1.164cm" fo:margin-bottom="0.176cm"/></style:style>""",
    "Quotations" to """<style:style style:name="Quotations" style:family="paragraph" style:parent-style-name="Standard" style:class="html"><style:paragraph-properties fo:margin-left="1cm" fo:margin-right="1cm" fo:margin-top="0cm" fo:margin-bottom="0.283cm"/></style:style>""",
)

/**
 * A table Writer puts into an OpenDocument file: as wide as the text, its cells lined as a new Word table's are. The
 * styles go into the file with it (automatic styles, written only when a table uses them).
 */
internal const val ODT_TETRA_TABLE = "Tetra_20_table"
internal const val ODT_TETRA_CELL = "Tetra_20_cell"
internal const val ODT_TETRA_STYLES = """<style:style style:name="$ODT_TETRA_TABLE" style:family="table"><style:table-properties style:width="17cm" table:align="margins"/></style:style>""" +
    """<style:style style:name="$ODT_TETRA_CELL" style:family="table-cell"><style:table-cell-properties fo:padding="0.1cm" fo:border="0.5pt solid #000000"/></style:style>"""

/**
 * A document as Writer holds it. [basePt] is the document's normal text size; [unsupported] names, in plain words,
 * what the file has that Writer cannot keep — empty means saving over the file loses nothing.
 */
internal data class OfficeDoc(
    val paras: List<DocPara>,
    val unsupported: Set<String> = emptySet(),
    val basePt: Float = 11f,
    val comments: List<DocComment> = emptyList(),
    val sources: List<DocSource> = emptyList(), // References: Word's sources part, or an ODT's bibliography marks' sources
    val numbering: List<Pair<String, String>>? = null, // Writer's own numbered lists, each level's (numFmt, lvlText) (Home › Multilevel list); null: 1. 2. 3.
    val citeStyle: CiteStyle = CiteStyle.APA, // References › Style: the citations' and the list's (Word's sources part's StyleName)
    val footFormat: String = NoteFormats.FOOT, val endFormat: String = NoteFormats.END, // the notes' numbers, Word's names (NoteFormats)
    // letters joined where a font joins them (fi, ll): drawn so; a PDF's texts never, so their words copy and are found as typed
    val ligatures: Boolean = true,
) {
    /** The comments some letters still carry (the text of one deleted, it goes, as in Word). */
    val liveComments: List<DocComment> get() {
        val used = HashSet<String>()
        fun walk(ps: List<DocPara>) { ps.forEach { p -> p.runs.forEach { used += it.comments }; p.table?.rows?.forEach { r -> r.cells.forEach { walk(it.paras) } } } }
        walk(paras)
        return comments.filter { it.id in used }
    }
    /** What would go missing on saving over the file: content, not just its look. Any of it → Save as a copy only. */
    val blocking: Set<String> get() = unsupported - SIMPLIFIED
    /** What saving over the file simplifies to Writer's own (agreed 2026-09-27, option b): said, not a reason for a copy. */
    val simplified: Set<String> get() = unsupported intersect SIMPLIFIED

    companion object {
        val SIMPLIFIED = setOf("paragraph spacing and indents", "fonts", "tab stops")
        /** An [unsupported] note as shown (the English phrase stays the ID, compared in tests and logged); null: none known. */
        @androidx.annotation.StringRes fun noteRes(note: String): Int? = NOTES[note]
        /** [notes] as shown, joined as joinToString does. */
        fun notesText(context: android.content.Context, notes: Collection<String>): String = notes.joinToString { n -> noteRes(n)?.let(context::getString) ?: n }
        private val NOTES = mapOf(
            "paragraph spacing and indents" to com.kalotrapezis.drive.R.string.office_note_spacing, "fonts" to com.kalotrapezis.drive.R.string.office_note_fonts, "tab stops" to com.kalotrapezis.drive.R.string.office_note_tab_stops,
            LegacyDocs.OLDER to com.kalotrapezis.drive.R.string.office_note_older, "an unusual file layout" to com.kalotrapezis.drive.R.string.office_note_layout, "character styles" to com.kalotrapezis.drive.R.string.office_note_char_styles,
            "columns" to com.kalotrapezis.drive.R.string.office_note_columns, "comments" to com.kalotrapezis.drive.R.string.office_note_comments, "content controls" to com.kalotrapezis.drive.R.string.office_note_content_controls,
            "embedded objects" to com.kalotrapezis.drive.R.string.office_note_objects, "equations" to com.kalotrapezis.drive.R.string.office_note_equations, "fields (page numbers, dates, contents)" to com.kalotrapezis.drive.R.string.office_note_fields,
            "footnotes" to com.kalotrapezis.drive.R.string.office_note_footnotes, "headers and footers" to com.kalotrapezis.drive.R.string.office_note_headers, "headings below level 6" to com.kalotrapezis.drive.R.string.office_note_deep_headings,
            "letter or roman numbering" to com.kalotrapezis.drive.R.string.office_note_letter_numbering, "links inside the document" to com.kalotrapezis.drive.R.string.office_note_inner_links,
            "numbered headings" to com.kalotrapezis.drive.R.string.office_note_numbered_headings, "other content" to com.kalotrapezis.drive.R.string.office_note_other_content, "other list formats" to com.kalotrapezis.drive.R.string.office_note_list_formats,
            "other text effects" to com.kalotrapezis.drive.R.string.office_note_text_effects, "paragraphs inside list items" to com.kalotrapezis.drive.R.string.office_note_list_paragraphs, "pictures" to com.kalotrapezis.drive.R.string.office_note_pictures,
            "pictures (WMF/EMF)" to com.kalotrapezis.drive.R.string.office_note_wmf, "sections" to com.kalotrapezis.drive.R.string.office_note_sections, "shapes and text boxes" to com.kalotrapezis.drive.R.string.office_note_shapes,
            "symbols" to com.kalotrapezis.drive.R.string.office_note_symbols, "formatting it cannot show exactly" to com.kalotrapezis.drive.R.string.office_note_formatting)
    }
}

/** A file Office cannot read, said to the user: [res] (with [args]) on the screen, the English [english] in logs and tests. */
internal class OfficeReadError(@androidx.annotation.StringRes val res: Int, english: String, vararg val args: Any) : IllegalStateException(english)
/** What to show for an error opening or saving a file: an [OfficeReadError]'s words in the app's language, else its message. */
internal fun Throwable.officeMessage(context: android.content.Context): String? = if (this is OfficeReadError) context.getString(res, *args) else message

internal enum class OfficeKind(val extension: String, val mime: String) {
    Docx("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    Odt("odt", "application/vnd.oasis.opendocument.text");

    companion object {
        fun of(name: String): OfficeKind? = entries.firstOrNull { name.endsWith(".${it.extension}", ignoreCase = true) }
    }
}

internal object OfficeFormats {
    /** Reads a document. Throws when the bytes are not a document Writer can read at all (encrypted, damaged…). */
    /** A Word header's or footer's paragraphs, for the pages (page-number fields as [PAGE_FIELD] / [PAGES_FIELD]). */
    internal fun docxPart(parts: Map<String, ByteArray>, path: String): List<DocPara> =
        runCatching { DocxReader(parts).apply { fieldMarks = true }.readPart(path) }.getOrDefault(emptyList())

    /**
     * An OpenDocument's header and footer (its main page style's: Standard, else the first), each "default" and
     * "first" (a title page's), for the pages — page-number fields as [PAGE_FIELD] / [PAGES_FIELD].
     */
    internal fun odtMaster(parts: Map<String, ByteArray>): Pair<Map<String, List<DocPara>>, Map<String, List<DocPara>>> =
        runCatching { OdtReader(parts).apply { fieldMarks = true }.readMaster() }.getOrDefault(emptyMap<String, List<DocPara>>() to emptyMap())

    fun read(bytes: ByteArray, kind: OfficeKind): OfficeDoc {
        val parts = unzip(bytes)
        return numberNotes(when (kind) {
            OfficeKind.Docx -> DocxReader(parts).read()
            OfficeKind.Odt -> OdtReader(parts).read().let { it.copy(citeStyle = CiteStyle.infer(it)) }
        }.let { d -> NoteFormats.read(parts, kind).let { (foot, end) -> d.copy(footFormat = foot, endFormat = end) } })
    }

    /** The file's bytes: [doc] written into [original] (the file it came from), or into a new blank document. */
    fun write(doc: OfficeDoc, kind: OfficeKind, original: ByteArray? = null): ByteArray = when (kind) {
        OfficeKind.Docx -> DocxWriter.write(doc, original?.let { runCatching { unzip(it) }.getOrNull() }?.takeIf { DocxWriter.canReuse(it) } ?: DocxWriter.blank())
        OfficeKind.Odt -> OdtWriter.write(doc, original?.let { runCatching { unzip(it) }.getOrNull() }?.takeIf { OdtWriter.canReuse(it) } ?: OdtWriter.blank())
    }

    /** Adjacent runs that look the same become one, and empty runs go: what the editor gives back is compared cleanly. */
    /** An empty paragraph's mark size (pt): Word's w:sz in its paragraph mark's rPr; null when it has none. */
    fun markSize(p: DocPara): Float? = p.keep?.parts?.firstOrNull { it.first == "rPr" }?.second
        ?.let { Regex("""<w:sz w:val="(\d+)"""").find(it)?.groupValues?.get(1)?.toFloatOrNull()?.div(2) }
    /** Paragraphs' words, to tell whether a block kept from the file was changed. */
    fun blockText(ps: List<DocPara>) = ps.joinToString("\n") { it.text }.hashCode().toString()
    fun tidy(runs: List<DocRun>): List<DocRun> {
        val out = ArrayList<DocRun>()
        for (r in runs) {
            if (r.text.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && last.sameLook(r)) out[out.size - 1] = last.copy(text = last.text + r.text) else out.add(r)
        }
        return out
    }

    /**
     * What the editor gives back has lost each paragraph's [DocKeep]; it is put back from [old] (as read from the file).
     * Paragraphs whose text is unchanged are matched first (longest common run); between two matches, changed
     * paragraphs pair up in order; a new paragraph takes the formatting of the one before it, as Enter does in Word —
     * except a page break before it.
     */
    fun carryKeep(old: List<DocPara>, new: List<DocPara>): List<DocPara> {
        val from = pairParagraphs(old, new)
        val m = new.size
        val matched = BooleanArray(m) { from[it] >= 0 }
        val source = Array(m) { k -> from[k].takeIf { it >= 0 }?.let { old[it].keep } }
        return new.mapIndexed { k, p ->
            val keep = if (matched[k]) source[k] else (k - 1 downTo 0).firstOrNull { matched[it] }?.let { source[it] }?.let { inherited ->
                inherited.copy(parts = inherited.parts.filter { (name, xml) -> name != "pageBreakBefore" && name != "sectPr" && name != "master" && !("break-before" in xml && "page" in xml) && !DropCap.isPart(name to xml) })
            } ?: (k + 1 until m).firstOrNull { matched[it] }?.let { source[it] }?.copy(parts = emptyList())
            p.copy(keep = keep?.takeIf { !it.isEmpty })
        }
    }

    /**
     * A paragraph's spacing set from Writer, in the file's own words (the rest of its formatting as it was): line spacing
     * as a multiple, space before and after, left, first-line and right indent (pt; first line < 0: hanging). null: unchanged.
     */
    fun withSpacing(keep: DocKeep?, kind: OfficeKind, line: Float? = null, before: Float? = null, after: Float? = null,
        left: Float? = null, firstLine: Float? = null, right: Float? = null): DocKeep {
        val k = keep ?: DocKeep()
        val parts = k.parts.toMutableList()
        fun set(xml: String, name: String, value: String?, drop: List<String> = emptyList()): String {
            if (value == null) return xml
            var x = xml
            for (a in drop + name) x = x.replace(Regex("""\s$a="[^"]*""""), "")
            return x.replaceFirst(Regex("""\s*/>$"""), """ $name="$value"/>""")
        }
        if (kind == OfficeKind.Docx) {
            fun tw(pt: Float?) = pt?.let { Math.round(it * 20).toString() }
            fun edit(name: String, change: (String) -> String) {
                val i = parts.indexOfFirst { it.first == name }
                val was = if (i >= 0) parts[i].second else "<w:$name/>"
                if (i >= 0) parts[i] = name to change(was) else parts += name to change(was)
            }
            if (line != null || before != null || after != null) edit("spacing") { x ->
                var s = set(x, "w:before", tw(before), listOf("w:beforeAutospacing", "w:beforeLines"))
                s = set(s, "w:after", tw(after), listOf("w:afterAutospacing", "w:afterLines"))
                if (line != null) s = set(set(s, "w:line", Math.round(line * 240).toString()), "w:lineRule", "auto")
                s
            }
            if (left != null || firstLine != null || right != null) edit("ind") { x ->
                var s = set(x, "w:left", tw(left), listOf("w:start", "w:leftChars", "w:startChars"))
                s = set(s, "w:right", tw(right), listOf("w:end", "w:rightChars", "w:endChars"))
                if (firstLine != null) s = if (firstLine < 0) set(s, "w:hanging", tw(-firstLine), listOf("w:firstLine", "w:hangingChars", "w:firstLineChars"))
                    else set(s, "w:firstLine", tw(firstLine), listOf("w:hanging", "w:hangingChars", "w:firstLineChars"))
                s
            }
        } else {
            fun put(name: String, value: String?, drop: List<String> = emptyList()) {
                if (value == null) return
                parts.removeAll { (kind, xml) -> kind == "@" && (drop + name).any { xml.startsWith(" $it=") } }
                parts += "@" to " $name=\"$value\""
            }
            fun pt(v: Float?) = v?.let { OdtWriter.fmt(it) + "pt" }
            put("fo:line-height", line?.let { "${Math.round(it * 100)}%" }, listOf("style:line-height-at-least", "style:line-spacing"))
            put("fo:margin-top", pt(before)); put("fo:margin-bottom", pt(after))
            put("fo:margin-left", pt(left)); put("fo:text-indent", pt(firstLine)); put("fo:margin-right", pt(right))
        }
        return k.copy(parts = parts)
    }

    /** A paragraph's borders set ([box] null or empty: none), in the file's own words: Word's pBdr, ODT's fo:border-*. */
    fun withBorders(keep: DocKeep?, kind: OfficeKind, box: ParaBox?): DocKeep? {
        val k = keep ?: DocKeep()
        val b = box?.takeIf { !it.isEmpty }
        val rest = k.parts.filter { (name, xml) -> name != "pBdr" && !(name == "@" && (xml.startsWith(" fo:border") || xml.startsWith(" fo:padding") || xml.startsWith(" style:join-border"))) }
        val set = if (b == null) emptyList() else if (kind == OfficeKind.Docx) {
            // its line as chosen (solid, double, dotted, dashed); a double's width is its whole, Word's size one of its three lines
            fun side(tag: String, e: Border?, pad: Float) = e?.let { val double = it.style == "double"
                """<w:$tag w:val="${if (it.style == "dashed") "dashed" else it.style}" w:sz="${Math.round((if (double) it.width / 3 else it.width) * 8).coerceIn(2, 96)}" w:space="${Math.round(pad)}" w:color="${if (it.color == 0) "auto" else "%06X".format(it.color and 0xFFFFFF)}"/>""" }.orEmpty()
            listOf("pBdr" to "<w:pBdr>" + side("top", b.top, b.padTop) + side("left", b.left, b.padLeft) + side("bottom", b.bottom, b.padBottom) +
                side("right", b.right, b.padRight) + side("between", b.between, b.padTop) + "</w:pBdr>")
        } else {
            fun side(name: String, e: Border?) = e?.let { "@" to " fo:border-$name=\"${"%.2f".format(java.util.Locale.ROOT, it.width).trimEnd('0').trimEnd('.')}pt ${if (it.style == "single") "solid" else it.style} #${"%06X".format(it.color and 0xFFFFFF)}\"" }
            listOfNotNull(side("top", b.top ?: b.between), side("left", b.left), side("bottom", b.bottom ?: b.between), side("right", b.right)) +
                listOf("@" to " fo:padding-top=\"${OdtWriter.fmt(b.padTop)}pt\"", "@" to " fo:padding-bottom=\"${OdtWriter.fmt(b.padBottom)}pt\"",
                    "@" to " fo:padding-left=\"${OdtWriter.fmt(b.padLeft)}pt\"", "@" to " fo:padding-right=\"${OdtWriter.fmt(b.padRight)}pt\"") +
                // A line between paragraphs: LibreOffice joins two alike into one box unless told not to.
                listOfNotNull(("@" to " style:join-border=\"false\"").takeIf { b.between != null })
        }
        return k.copy(parts = rest + set).takeIf { !it.isEmpty }
    }

    /** [c] put on the letters [spans] (paragraph, from, to — in the paragraph's own text): its runs cut there, tagged. */
    fun withComment(doc: OfficeDoc, spans: List<Triple<Int, Int, Int>>, c: DocComment): OfficeDoc {
        val byPara = spans.groupBy { it.first }
        return doc.copy(comments = doc.comments.filter { it.id != c.id } + c, paras = doc.paras.mapIndexed { i, p ->
            val mine = byPara[i] ?: return@mapIndexed p
            var at = 0
            val runs = ArrayList<DocRun>()
            for (r in p.runs) {
                val cuts = sortedSetOf(0, r.text.length)
                mine.forEach { (_, a, b) -> cuts += (a - at).coerceIn(0, r.text.length); cuts += (b - at).coerceIn(0, r.text.length) }
                cuts.toList().zipWithNext().filter { (x, y) -> y > x }.forEach { (x, y) ->
                    val inside = mine.any { (_, a, b) -> at + x >= a && at + y <= b }
                    runs += r.copy(text = r.text.substring(x, y), comments = if (inside && c.id !in r.comments) r.comments + c.id else r.comments)
                }
                at += r.text.length
            }
            p.copy(runs = tidy(runs))
        })
    }
    /** The letters [spans] (paragraph, from, to) made the citation [cite] (References › Insert citation). */
    /** Letters of [spans] made a note's reference (References › Insert footnote): superscript, the note on them. */
    fun withNote(doc: OfficeDoc, spans: List<Triple<Int, Int, Int>>, note: DocNote): OfficeDoc {
        val byPara = spans.groupBy { it.first }
        return doc.copy(paras = doc.paras.mapIndexed { i, p ->
            val mine = byPara[i] ?: return@mapIndexed p
            var at = 0
            val runs = ArrayList<DocRun>()
            for (r in p.runs) {
                val cuts = sortedSetOf(0, r.text.length)
                mine.forEach { (_, a, b) -> cuts += (a - at).coerceIn(0, r.text.length); cuts += (b - at).coerceIn(0, r.text.length) }
                cuts.toList().zipWithNext().filter { (x, y) -> y > x }.forEach { (x, y) ->
                    val inside = mine.any { (_, a, b) -> at + x >= a && at + y <= b }
                    runs += if (inside) r.copy(text = r.text.substring(x, y), note = note, script = 1, change = null) else r.copy(text = r.text.substring(x, y))
                }
                at += r.text.length
            }
            p.copy(runs = tidy(runs))
        })
    }
    /** [old] changed to [new] wherever it is (null: its reference taken out, the note with it). */
    /** A note's words changed in its dialog: each line keeps the paragraph it came from, each letter kept its run's look, typed ones the look beside them (as a text box's words). */
    fun noteEdited(old: DocNote, text: String): DocNote {
        if (text == old.paras.joinToString("\n") { it.text }) return old
        val fresh = (old.paras.firstOrNull() ?: DocPara(emptyList())).copy(runs = emptyList())
        val laid = PictureXml.relaid(old.paras.map { p -> p to p.runs.map { it to it.text } }, text.split('\n'), fresh, null)
        return DocNote(old.end, laid.map { (p, runs) -> p.copy(runs = tidy(runs.filter { it.second.isNotEmpty() }.map { (r, t) -> (r ?: DocRun("")).copy(text = t) })) })
    }
    fun replaceNote(doc: OfficeDoc, old: DocNote, new: DocNote?): OfficeDoc {
        fun walk(ps: List<DocPara>): List<DocPara> = ps.map { p ->
            if (p.table != null) p.copy(table = p.table.copy(rows = p.table.rows.map { row -> row.copy(cells = row.cells.map { c -> c.copy(paras = walk(c.paras)) }) }))
            else if (p.runs.none { it.note == old }) p
            else p.copy(runs = tidy(p.runs.mapNotNull { r -> if (r.note != old) r else new?.let { r.copy(note = it) } }))
        }
        return numberNotes(doc.copy(paras = walk(doc.paras)))
    }

    fun withCite(doc: OfficeDoc, spans: List<Triple<Int, Int, Int>>, cite: String): OfficeDoc {
        val byPara = spans.groupBy { it.first }
        return doc.copy(paras = doc.paras.mapIndexed { i, p ->
            val mine = byPara[i] ?: return@mapIndexed p
            var at = 0
            val runs = ArrayList<DocRun>()
            for (r in p.runs) {
                val cuts = sortedSetOf(0, r.text.length)
                mine.forEach { (_, a, b) -> cuts += (a - at).coerceIn(0, r.text.length); cuts += (b - at).coerceIn(0, r.text.length) }
                cuts.toList().zipWithNext().filter { (x, y) -> y > x }.forEach { (x, y) ->
                    val inside = mine.any { (_, a, b) -> at + x >= a && at + y <= b }
                    runs += r.copy(text = r.text.substring(x, y), cite = if (inside) cite else r.cite)
                }
                at += r.text.length
            }
            p.copy(runs = tidy(runs))
        })
    }
    /** A reply to [parentId], on the same letters as it. */
    fun withReply(doc: OfficeDoc, parentId: String, reply: DocComment): OfficeDoc {
        fun walk(ps: List<DocPara>): List<DocPara> = ps.map { p -> p.copy(runs = p.runs.map { r -> if (parentId in r.comments) r.copy(comments = r.comments + reply.id) else r },
            table = p.table?.let { t -> t.copy(rows = t.rows.map { row -> row.copy(cells = row.cells.map { c -> c.copy(paras = walk(c.paras)) }) }) }) }
        return doc.copy(paras = walk(doc.paras), comments = doc.comments + reply.copy(parent = parentId))
    }
    /** A comment (and its thread's replies) resolved or open again. */
    fun withDone(doc: OfficeDoc, id: String, done: Boolean): OfficeDoc =
        doc.copy(comments = doc.comments.map { if (it.id == id || it.parent == id) it.copy(done = done) else it })

    /**
     * A comment with its words changed (Review: Edit) and the rest kept: Word's comment keeps its own tag and its last
     * paragraph's id (replies and "resolved" are said by it); LibreOffice's keeps its author and date.
     */
    fun withCommentText(c: DocComment, text: String): DocComment {
        val raw = c.raw ?: return c.copy(text = text)
        val lines = text.split('\n')
        val out = if (raw.startsWith("<w:comment")) {
            val para = Regex("""w14:paraId="([0-9A-Fa-f]+)"""").findAll(raw).lastOrNull()?.groupValues?.get(1)
            raw.substring(0, raw.indexOf('>') + 1) + lines.mapIndexed { i, l ->
                "<w:p${if (i == lines.lastIndex && para != null) " w14:paraId=\"$para\"" else ""}>${if (i == 0) "<w:r><w:annotationRef/></w:r>" else ""}<w:r><w:t xml:space=\"preserve\">${esc(l)}</w:t></w:r></w:p>"
            }.joinToString("") + "</w:comment>"
        } else raw.replace(Regex("""<text:(p|list)\b[^>]*/>|<text:(p|list)\b[\s\S]*?</text:\2>"""), "") + lines.joinToString("") { "<text:p>${esc(it)}</text:p>" }
        return c.copy(text = text, raw = out)
    }

    /** A comment taken off (its letters stay). */
    fun withoutComment(doc: OfficeDoc, id: String): OfficeDoc {
        val ids = setOf(id) + doc.comments.filter { it.parent == id }.map { it.id } // its replies go with it (Word's)
        fun strip(ps: List<DocPara>): List<DocPara> = ps.map { p -> p.copy(runs = tidy(p.runs.map { r -> if (r.comments.any { it in ids }) r.copy(comments = r.comments - ids) else r }),
            table = p.table?.let { t -> t.copy(rows = t.rows.map { row -> row.copy(cells = row.cells.map { cell -> cell.copy(paras = strip(cell.paras)) }) }) }) }
        return doc.copy(paras = strip(doc.paras), comments = doc.comments.filter { it.id !in ids })
    }
    /** An id no comment of [doc] has: Word's are numbers, ODT's names. */
    fun newCommentId(doc: OfficeDoc, kind: OfficeKind): String = if (kind == OfficeKind.Docx)
        ((doc.comments.mapNotNull { it.id.toIntOrNull() }.maxOrNull() ?: -1) + 1).toString()
        else generateSequence(1) { it + 1 }.map { "__Annotation__Tetra_$it" }.first { n -> doc.comments.none { it.id == n } }

    /** A drop cap put on the paragraph (null: taken off), in the file's own words (see [DropCap]). */
    fun withDropCap(keep: DocKeep?, kind: OfficeKind, drop: DropCap?): DocKeep? {
        val k = keep ?: DocKeep()
        val rest = k.parts.filter { !DropCap.isPart(it) }
        val set = when {
            drop == null -> emptyList()
            kind == OfficeKind.Docx -> listOf("dropCap" to "${drop.length}|<w:framePr w:dropCap=\"${if (drop.margin) "margin" else "drop"}\" w:lines=\"${drop.lines}\" w:hSpace=\"${Math.round(drop.distance * 20)}\" w:wrap=\"around\" w:vAnchor=\"text\" w:hAnchor=\"${if (drop.margin) "page" else "text"}\"/>")
            else -> listOf("child" to "<style:drop-cap style:lines=\"${drop.lines}\" style:length=\"${drop.length}\" style:distance=\"${OdtWriter.fmt(drop.distance)}pt\"/>")
        }
        // ODT's order inside the paragraph's properties: tab stops, then the drop cap, then a background picture.
        val at = rest.indexOfLast { it.first == "child" && it.second.startsWith("<style:tab-stops") } + 1
        val parts = if (kind == OfficeKind.Odt && set.isNotEmpty() && at > 0) rest.toMutableList().apply { addAll(at, set) } else rest + set
        return k.copy(parts = parts).takeIf { !it.isEmpty }
    }

    /** [r] in the font [font] (Home › Font), in the file's own words: Word's rFonts for every script, ODF's fo:font-family. */
    fun withFont(r: DocRun, kind: OfficeKind, font: String): DocRun = r.copy(font = if (kind == OfficeKind.Docx)
        (r.font.orEmpty().replace(Regex("""<w:rFonts\b[^>]*/>"""), "") + """<w:rFonts w:ascii="${esc(font)}" w:hAnsi="${esc(font)}" w:eastAsia="${esc(font)}" w:cs="${esc(font)}"/>""")
        else Regex(""" (\S+)="([^"]*)"""").findAll(r.font.orEmpty()).map { it.groupValues[1] to it.groupValues[2] }.filter { (n, _) -> n != "style:font-name" && n != "fo:font-family" }
            .joinToString("") { (n, v) -> " $n=\"$v\"" } + " fo:font-family=\"'${esc(font)}'\"")

    /**
     * The first [length] letters of [p] in [font] (null: as they are) and [color] (null: as they are; -1 automatic) —
     * a drop cap's own look: in both kinds it is its letters' (Word's letter paragraph; LibreOffice draws the letters).
     */
    fun withLettersLook(p: DocPara, length: Int, kind: OfficeKind, font: String?, color: Int?): DocPara {
        if (font == null && color == null) return p
        fun restyle(r: DocRun) = (if (font == null) r else withFont(r, kind, font)).let { it.copy(color = when (color) { null -> it.color; -1 -> null; else -> color }) }
        val out = ArrayList<DocRun>()
        var left = length
        for (r in p.runs) when {
            left <= 0 || r.image != null -> out += r
            r.text.length <= left -> { out += restyle(r); left -= r.text.length }
            else -> { out += restyle(r.copy(text = r.text.take(left))); out += r.copy(text = r.text.drop(left)); left = 0 }
        }
        return p.copy(runs = out)
    }

    /** A page break before the paragraph put in ([on]) or taken out, in the file's own words (Word's pageBreakBefore, ODT's fo:break-before). */
    /** Layout › Breaks › Column: the paragraph starts the next column (Word's column break at its start; ODT's fo:break-before). */
    fun withColumnBreak(keep: DocKeep?, kind: OfficeKind, on: Boolean): DocKeep? {
        val k = keep ?: DocKeep()
        val rest = k.parts.filter { (name, xml) -> name != COLUMN_BREAK && !(name == "@" && xml.startsWith(" fo:break-before=\"column")) }
        val set = when { !on -> emptyList(); kind == OfficeKind.Docx -> listOf(COLUMN_BREAK to "<w:columnBreak/>"); else -> listOf("@" to " fo:break-before=\"column\"") }
        return k.copy(parts = rest + set).takeIf { !it.isEmpty }
    }
    fun withPageBreak(keep: DocKeep?, kind: OfficeKind, on: Boolean): DocKeep? {
        val k = keep ?: DocKeep()
        val rest = k.parts.filter { (name, xml) -> name != "pageBreakBefore" && !(name == "@" && xml.startsWith(" fo:break-before=")) }
        val set = when { !on -> emptyList(); kind == OfficeKind.Docx -> listOf("pageBreakBefore" to "<w:pageBreakBefore/>"); else -> listOf("@" to " fo:break-before=\"page\"") }
        return k.copy(parts = rest + set).takeIf { !it.isEmpty }
    }

    /**
     * Which paragraph of [old] each paragraph of [new] is (-1: a new one). Unchanged paragraphs are matched first
     * (longest common run); between two matches, changed paragraphs pair up in order. Empty paragraphs are no sign
     * of which is which (found 2026-09-30: after Blank page, text typed on the page after it — two empty paragraphs
     * were matched crosswise and the page break went to the wrong one): they pair in order with the changed ones.
     */
    fun pairParagraphs(old: List<DocPara>, new: List<DocPara>): IntArray {
        val n = old.size; val m = new.size
        fun same(i: Int, j: Int) = old[i].text == new[j].text && old[i].text.isNotBlank()
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
            lcs[i][j] = if (same(i, j)) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        val from = IntArray(m) { -1 }
        var i = 0; var j = 0
        val gapOld = ArrayList<Int>(); val gapNew = ArrayList<Int>()
        fun flush() { gapNew.forEachIndexed { k, jj -> gapOld.getOrNull(k)?.let { from[jj] = it } }; gapOld.clear(); gapNew.clear() }
        while (i < n && j < m) {
            when {
                same(i, j) && lcs[i][j] == lcs[i + 1][j + 1] + 1 -> { flush(); from[j] = i; i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> gapOld += i++
                else -> gapNew += j++
            }
        }
        while (i < n) gapOld += i++
        while (j < m) gapNew += j++
        flush()
        return from
    }

    /**
     * The editor has no fonts; each letter's font is put back from [old] (asked 2026-09-27: a font may change anywhere,
     * even inside a word). Letters left as they were keep theirs; a typed letter takes the font of the one before it,
     * as in Word; a new paragraph, the font its previous paragraph ended with.
     */
    /**
     * …and each letter's comments the same way (2026-09-29): a letter typed inside a comment's letters joins it (both its
     * neighbours carry it), one typed at its edge does not; all its letters deleted, the comment is gone.
     */
    fun carryFonts(old: List<DocPara>, new: List<DocPara>): List<DocPara> {
        if (old.none { p -> p.runs.any { it.font != null || it.comments.isNotEmpty() || it.cite != null || it.change != null || it.note != null || it.control != null } }) return new
        val from = pairParagraphs(old, new)
        var carried: String? = null // the font the previous paragraph ended with
        return new.mapIndexed { j, p ->
            val text = p.text
            val fonts = arrayOfNulls<String>(text.length)
            val notes = arrayOfNulls<List<String>>(text.length)
            val cites = arrayOfNulls<String>(text.length)
            val changes = arrayOfNulls<DocChange>(text.length)
            val noteAt = arrayOfNulls<DocNote>(text.length)
            val controls = arrayOfNulls<String>(text.length)
            val known = BooleanArray(text.length)
            from[j].takeIf { it >= 0 }?.let { old[it] }?.let { o ->
                val oldText = o.text
                val oldFonts = ArrayList<String?>(oldText.length).apply { o.runs.forEach { r -> repeat(r.text.length) { add(r.font) } } }
                val oldNotes = ArrayList<List<String>>(oldText.length).apply { o.runs.forEach { r -> repeat(r.text.length) { add(r.comments) } } }
                val oldCites = ArrayList<String?>(oldText.length).apply { o.runs.forEach { r -> repeat(r.text.length) { add(r.cite) } } }
                val oldChanges = ArrayList<DocChange?>(oldText.length).apply { o.runs.forEach { r -> repeat(r.text.length) { add(r.change) } } }
                val oldNoteAt = ArrayList<DocNote?>(oldText.length).apply { o.runs.forEach { r -> repeat(r.text.length) { add(r.note) } } }
                val oldControls = ArrayList<String?>(oldText.length).apply { o.runs.forEach { r -> repeat(r.text.length) { add(r.control) } } }
                fun take(y: Int, x: Int) { fonts[y] = oldFonts[x]; notes[y] = oldNotes[x]; cites[y] = oldCites[x]; changes[y] = oldChanges[x]; noteAt[y] = oldNoteAt[x]; controls[y] = oldControls[x]; known[y] = true }
                // Common start and end first; the changed middle by longest common subsequence (small, where one typed).
                var a = 0
                while (a < text.length && a < oldText.length && text[a] == oldText[a]) { take(a, a); a++ }
                var z = 0
                while (z < text.length - a && z < oldText.length - a && text[text.length - 1 - z] == oldText[oldText.length - 1 - z]) {
                    take(text.length - 1 - z, oldText.length - 1 - z); z++
                }
                val nn = text.length - a - z; val on = oldText.length - a - z
                if (nn > 0 && on > 0 && nn.toLong() * on <= 4_000_000) {
                    val l = Array(on + 1) { IntArray(nn + 1) }
                    for (x in on - 1 downTo 0) for (y in nn - 1 downTo 0)
                        l[x][y] = if (oldText[a + x] == text[a + y]) l[x + 1][y + 1] + 1 else maxOf(l[x + 1][y], l[x][y + 1])
                    var x = 0; var y = 0
                    while (x < on && y < nn) when {
                        oldText[a + x] == text[a + y] -> { take(a + y, a + x); x++; y++ }
                        l[x + 1][y] >= l[x][y + 1] -> x++
                        else -> y++
                    }
                }
            }
            // Typed letters: the font before them; at the start of the paragraph, the first known one after.
            var before: String? = if (known.isNotEmpty() && !known[0]) (known.indices.firstOrNull { known[it] }?.let { fonts[it] } ?: carried) else carried
            for (k in text.indices) if (known[k]) before = fonts[k] else fonts[k] = before
            carried = fonts.lastOrNull() ?: carried
            // Typed letters: the comments both known neighbours share.
            run {
                var left: List<String> = emptyList()
                var k = 0
                while (k < text.length) {
                    if (known[k]) { left = notes[k].orEmpty(); k++; continue }
                    var e = k
                    while (e < text.length && !known[e]) e++
                    val right = if (e < text.length) notes[e].orEmpty() else emptyList()
                    val both = left.filter { it in right }
                    for (q in k until e) notes[q] = both
                    k = e
                }
            }
            // …and the citation both known neighbours are in (letters typed at a citation's edge are not in it).
            run {
                var k = 0
                while (k < text.length) {
                    if (known[k]) { k++; continue }
                    var e = k
                    while (e < text.length && !known[e]) e++
                    val left = if (k > 0) cites[k - 1] else null
                    val right = if (e < text.length) cites[e] else null
                    for (q in k until e) cites[q] = left.takeIf { it == right }
                    k = e
                }
            }
            // …and the insertion both known neighbours are in (typed inside someone's inserted words: theirs too).
            run {
                var k = 0
                while (k < text.length) {
                    if (known[k]) { k++; continue }
                    var e = k
                    while (e < text.length && !known[e]) e++
                    val left = if (k > 0) changes[k - 1] else null
                    for (q in k until e) changes[q] = left?.takeIf { !it.deleted && it == (if (e < text.length) changes[e] else null) }
                    k = e
                }
            }
            // …and a form's field either known neighbour is in (typed at its edge fills it, as Word's cursor is inside it there).
            run {
                var k = 0
                while (k < text.length) {
                    if (known[k]) { k++; continue }
                    var e = k
                    while (e < text.length && !known[e]) e++
                    val c = (if (k > 0) controls[k - 1] else null) ?: (if (e < text.length) controls[e] else null)
                    for (q in k until e) controls[q] = c
                    k = e
                }
            }
            // The runs again, cut where the font or the comments change.
            var at = 0
            val runs = ArrayList<DocRun>()
            for (r in p.runs) {
                var start = 0
                for (k in 1..r.text.length) if (k == r.text.length || fonts[at + k] != fonts[at + start] || notes[at + k].orEmpty() != notes[at + start].orEmpty() || cites[at + k] != cites[at + start] || changes[at + k] != changes[at + start] || noteAt[at + k] != noteAt[at + start] || controls[at + k] != controls[at + start]) {
                    // A note's reference keeps its note (typed letters never join one).
                    if (k > start) runs += r.copy(text = r.text.substring(start, k), font = fonts[at + start], comments = notes[at + start].orEmpty(), cite = cites[at + start], change = changes[at + start], note = noteAt[at + start], control = controls[at + start])
                    start = k
                }
                at += r.text.length
            }
            p.copy(runs = tidy(runs))
        }
    }

    // --- shared helpers ---

    /** An element as XML, with the prefixes the file uses (the file's root declares them, and it is kept). */
    internal fun xml(e: Node): String = when (e.nodeType) {
        Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> esc(e.nodeValue)
        Node.ELEMENT_NODE -> buildString {
            append('<').append(e.nodeName)
            val a = e.attributes
            // Namespace declarations made here (Word declares a: on a picture's graphic) go with it.
            for (k in 0 until a.length) a.item(k).let { append(' ').append(it.nodeName).append("=\"").append(esc(it.nodeValue)).append('"') }
            if (!e.hasChildNodes()) append("/>") else {
                append('>')
                var c = e.firstChild
                while (c != null) { append(xml(c)); c = c.nextSibling }
                append("</").append(e.nodeName).append('>')
            }
        }
        else -> ""
    }

    internal fun unzip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val parts = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory) parts[e.name] = zip.readBytes()
            }
        }
        if (parts.isEmpty()) throw OfficeReadError(com.kalotrapezis.drive.R.string.office_error_not_document, "Not a document.")
        return parts
    }

    /** A zip of [parts] in their order; [stored] is written first and uncompressed (ODF's mimetype must be). */
    internal fun zip(parts: Map<String, ByteArray>, stored: String? = null): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val names = listOfNotNull(stored?.takeIf { it in parts }) + parts.keys.filter { it != stored }
            for (name in names) {
                val data = parts.getValue(name)
                val e = ZipEntry(name)
                if (name == stored) {
                    e.method = ZipEntry.STORED; e.size = data.size.toLong(); e.compressedSize = data.size.toLong()
                    e.crc = CRC32().apply { update(data) }.value
                }
                zip.putNextEntry(e); zip.write(data); zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    internal fun parse(bytes: ByteArray): org.w3c.dom.Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        f.isExpandEntityReferences = false
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        return try { f.newDocumentBuilder().parse(ByteArrayInputStream(bytes)) }
        catch (e: org.xml.sax.SAXParseException) {
            // Google Slides' and Docs' exports write an emoji as its two halves, "&#55357;&#56397;", which XML forbids
            // (LibreOffice lets it pass; a user's .odp, 17 Oct): read again with the halves joined, else the error as it was
            val text = String(bytes, Charsets.UTF_8); val joined = joinHalves(text)
            if (joined == text) throw e
            f.newDocumentBuilder().parse(ByteArrayInputStream(joined.toByteArray()))
        }
    }

    private val HALVES = Regex("""&#(x?)([0-9A-Fa-f]+);&#(x?)([0-9A-Fa-f]+);""")
    /** "&#55357;&#56420;" (or in hex) → the one letter the two references halve; any other reference as it was. */
    internal fun joinHalves(xml: String): String = HALVES.replace(xml) { m ->
        val hi = m.groupValues[2].toIntOrNull(if (m.groupValues[1] == "x") 16 else 10) ?: return@replace m.value
        val lo = m.groupValues[4].toIntOrNull(if (m.groupValues[3] == "x") 16 else 10) ?: return@replace m.value
        if (hi in 0xD800..0xDBFF && lo in 0xDC00..0xDFFF) String(charArrayOf(hi.toChar(), lo.toChar())) else m.value
    }

    internal fun esc(s: String): String = buildString(s.length) {
        for (c in s) when {
            c == '&' -> append("&amp;")
            c == '<' -> append("&lt;")
            c == '>' -> append("&gt;")
            c == '"' -> append("&quot;")
            c == '\t' || c == '\n' || c == '\r' || c >= ' ' -> if (c != '￾' && c != '￿') append(c)
        }
    }

    internal fun hex(rgb: Int) = "%06X".format(rgb and 0xFFFFFF)
    /**
     * The notes numbered in the order they stand (tables' too), each kind on its own — by default footnotes 1, 2, 3;
     * endnotes i, ii, iii, as Word and LibreOffice number them; else in the document's formats. Their letters are the numbers.
     */
    fun numberNotes(doc: OfficeDoc): OfficeDoc {
        var foot = 0; var end = 0
        fun walk(ps: List<DocPara>): List<DocPara> = ps.map { p ->
            if (p.table != null) p.copy(table = p.table.copy(rows = p.table.rows.map { row -> row.copy(cells = row.cells.map { c -> c.copy(paras = walk(c.paras)) }) }))
            else if (p.runs.none { it.note != null }) p
            else p.copy(runs = p.runs.map { r -> r.note?.let { n -> r.copy(text = if (n.end) NoteFormats.number(++end, doc.endFormat) else NoteFormats.number(++foot, doc.footFormat), script = 1) } ?: r })
        }
        return doc.copy(paras = walk(doc.paras))
    }
    /** Every note, in order. */
    fun notesOf(doc: OfficeDoc): List<DocNote> {
        val out = ArrayList<DocNote>()
        fun walk(ps: List<DocPara>) { ps.forEach { p -> p.runs.forEach { r -> r.note?.let { out += it } }; p.table?.rows?.forEach { row -> row.cells.forEach { walk(it.paras) } } } }
        walk(doc.paras)
        return out
    }

    /** Word letter formatting from an rPr's XML (a tracked change's formatting before it). */
    internal fun docxLook(rPr: String): DocRun = runCatching {
        val root = parse("""<x xmlns:w="$W" xmlns:w14="http://schemas.microsoft.com/office/word/2010/wordml" xmlns:w15="http://schemas.microsoft.com/office/word/2012/wordml">$rPr</x>""".toByteArray()).documentElement
        DocxReader(emptyMap()).lookOf(root.kids().firstOrNull { it.localName == "rPr" }, null)
    }.getOrDefault(DocRun(""))

    internal fun parseHex(s: String?): Int? = s?.removePrefix("#")?.takeIf { it.length == 6 }?.toIntOrNull(16)
}

internal fun Element.kids(): List<Element> {
    val out = ArrayList<Element>()
    var n = firstChild
    while (n != null) { if (n is Element) out.add(n); n = n.nextSibling }
    return out
}
internal fun Element.kid(ns: String, name: String): Element? = kids().firstOrNull { it.namespaceURI == ns && it.localName == name }
internal fun Element.attr(ns: String, name: String): String? = getAttributeNS(ns, name).takeIf { hasAttributeNS(ns, name) }

// ============================================================================================ Word (.docx)

internal const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
internal const val RELATIONSHIPS = R
private const val REL_HYPERLINK = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink"
private const val REL_NUMBERING = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering"
private const val REL_STYLES = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles"
private const val REL_IMAGE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"
private const val REL_CHART = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart"
/** A film in the package (PowerPoint's media relationship): a Words video from the phone, its picture's ext naming it (Word keeps only online videos) */
/** Tetra's own ext in a picture's cNvPr: the film of a video from the phone (Word reads past it, keeping the picture). */
internal const val TETRA_VIDEO = "urn:kalotrapezis:tetra:video"
private const val REL_MEDIA = "http://schemas.microsoft.com/office/2007/relationships/media"
private const val REL_OLE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/oleObject"
private const val REL_COMMENTS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments"
private const val REL_COMMENTS_EX = "http://schemas.microsoft.com/office/2011/relationships/commentsExtended"
private const val W14 = "http://schemas.microsoft.com/office/word/2010/wordml"
private const val W15 = "http://schemas.microsoft.com/office/word/2012/wordml"
internal const val LOEXT = "urn:org:documentfoundation:names:experimental:office:xmlns:loext:1.0"
private const val REL_FOOTNOTES = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/footnotes"
private const val REL_ENDNOTES = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/endnotes"

/** A relationship's target, from the folder of the part that points at it, as a path in the package. */
internal fun resolve(dir: String, target: String): String {
    if (target.startsWith("/")) return target.removePrefix("/")
    val out = ArrayList(dir.split('/').filter { it.isNotEmpty() })
    target.split('/').forEach { if (it == "..") out.removeLastOrNull() else if (it != "." && it.isNotEmpty()) out += it }
    return out.joinToString("/")
}

/** Word's fixed highlight colours; a highlight is written as the nearest of them. */
internal val WORD_HIGHLIGHTS = linkedMapOf(
    "yellow" to 0xFFFF00, "green" to 0x00FF00, "cyan" to 0x00FFFF, "magenta" to 0xFF00FF, "blue" to 0x0000FF,
    "red" to 0xFF0000, "darkBlue" to 0x000080, "darkCyan" to 0x008080, "darkGreen" to 0x008000, "darkMagenta" to 0x800080,
    "darkRed" to 0x800000, "darkYellow" to 0x808000, "darkGray" to 0x808080, "lightGray" to 0xC0C0C0, "black" to 0x000000, "white" to 0xFFFFFF,
)

internal fun documentPath(parts: Map<String, ByteArray>): String {
    val rels = parts["_rels/.rels"] ?: return "word/document.xml"
    val root = OfficeFormats.parse(rels).documentElement
    return root.kids().firstOrNull { it.getAttribute("Type").endsWith("/officeDocument") }?.getAttribute("Target")?.removePrefix("/") ?: "word/document.xml"
}
/** Where a document keeps its named styles. */
internal fun stylesPath(parts: Map<String, ByteArray>, kind: OfficeKind) = if (kind == OfficeKind.Odt) "styles.xml"
    else documentPath(parts).substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } + "styles.xml"
internal fun relsPath(part: String) = part.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } + "_rels/" + part.substringAfterLast('/') + ".rels"

private class DocxReader(val parts: Map<String, ByteArray>) {
    val notes = linkedSetOf<String>()
    val docPath = documentPath(parts)
    val dir = docPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
    val links = HashMap<String, String>()
    val images = HashMap<String, String>() // relationship id → the picture's part in the package
    val charts = HashMap<String, String>() // relationship id → a chart's part
    val films = HashMap<String, String>() // relationship id → a video's film (a part)
    val styleName = HashMap<String, String>() // styleId → name, lowercased
    /** Character styles that change nothing (no formatting of their own, on the plain font): web pages' names pasted along. */
    val emptyCharStyles = HashSet<String>()
    var defaultParaStyle: String? = null
    var basePt = 10f
    val numAbstract = HashMap<String, String>() // numId → abstractNumId
    val numStart = HashMap<Pair<String, Int>, Int>() // (numId, level) → startOverride
    val levels = HashMap<Pair<String, Int>, Pair<String, Int>>() // (abstractNumId, level) → (numFmt, start)
    val counters = HashMap<String, IntArray>() // numId → the count at each level, as Word numbers them
    val openComments = LinkedHashSet<String>() // the comments whose range the reading is in
    val comments = LinkedHashMap<String, DocComment>()
    private val commentByPara = HashMap<String, String>() // a comment's last paragraph's w14:paraId → its id
    /** The document's comments (comments.xml), and which have a range in the text; one without is not kept yet. */
    fun readComments(body: Element) {
        val ranged = HashSet<String>()
        val starts = body.getElementsByTagNameNS(W, "commentRangeStart")
        for (i in 0 until starts.length) (starts.item(i) as Element).attr(W, "id")?.let { ranged += it }
        val refs = body.getElementsByTagNameNS(W, "commentReference")
        for (i in 0 until refs.length) if ((refs.item(i) as Element).attr(W, "id") !in ranged) notes += "comments"
        val path = parts[relsPath(docPath)]?.let { r -> OfficeFormats.parse(r).documentElement.kids().firstOrNull { it.getAttribute("Type") == REL_COMMENTS }?.getAttribute("Target") }
            ?.let { resolve(dir, it) } ?: return
        val root = parts[path]?.let { OfficeFormats.parse(it).documentElement } ?: return
        for (c in root.kids().filter { it.localName == "comment" }) {
            val id = c.attr(W, "id") ?: continue
            if (id !in ranged) continue
            comments[id] = DocComment(id, c.attr(W, "author") ?: "", c.attr(W, "date"),
                c.kids().filter { it.localName == "p" }.joinToString("\n") { it.textContent }, OfficeFormats.xml(c), c.attr(W, "initials"))
            c.kids().lastOrNull { it.localName == "p" }?.getAttributeNS(W14, "paraId")?.takeIf { it.isNotEmpty() }?.let { commentByPara[it] = id }
        }
        // Replies and resolved comments (Word 2013+'s commentsExtended): by each comment's last paragraph's id.
        val ex = parts[relsPath(docPath)]?.let { r -> OfficeFormats.parse(r).documentElement.kids().firstOrNull { it.getAttribute("Type") == REL_COMMENTS_EX }?.getAttribute("Target") }
            ?.let { parts[resolve(dir, it)] }?.let { OfficeFormats.parse(it).documentElement } ?: return
        for (e in ex.kids().filter { it.localName == "commentEx" }) {
            val id = commentByPara[e.getAttributeNS(W15, "paraId")] ?: continue
            val parent = e.getAttributeNS(W15, "paraIdParent").takeIf { it.isNotEmpty() }?.let { commentByPara[it] }
            comments[id] = comments.getValue(id).copy(parent = parent, done = e.getAttributeNS(W15, "done") == "1")
        }
    }

    /** The document's footnotes and endnotes (footnotes.xml, endnotes.xml): each one's paragraphs, by its id. */
    val footnotes = HashMap<String, DocNote>()
    val endnotes = HashMap<String, DocNote>()
    fun readNotes() {
        val rels = parts[relsPath(docPath)]?.let { OfficeFormats.parse(it).documentElement.kids() }.orEmpty()
        for ((type, end) in listOf(REL_FOOTNOTES to false, REL_ENDNOTES to true)) {
            val path = rels.firstOrNull { it.getAttribute("Type") == type }?.getAttribute("Target")?.let { resolve(dir, it) } ?: continue
            val root = parts[path]?.let { OfficeFormats.parse(it).documentElement } ?: continue
            for (n in root.kids().filter { it.localName == "footnote" || it.localName == "endnote" }) {
                if (n.attr(W, "type").let { it != null && it != "normal" }) continue // the separator lines: Word's own, written again
                val id = n.attr(W, "id") ?: continue
                val paras = ArrayList<DocPara>().also { block(n, it) }
                // The space after the note's number (Word's): not its text — written again with the number.
                paras.firstOrNull()?.let { p -> p.runs.firstOrNull()?.takeIf { it.text.startsWith(" ") }?.let { r ->
                    paras[0] = p.copy(runs = OfficeFormats.tidy(listOf(r.copy(text = r.text.drop(1))) + p.runs.drop(1))) } }
                (if (end) endnotes else footnotes)[id] = DocNote(end, paras.ifEmpty { listOf(DocPara(emptyList())) })
            }
        }
    }

    /** Reading a header or footer for the pages: its page-number fields become [PAGE_FIELD] / [PAGES_FIELD]. */
    var fieldMarks = false
    private var fieldInstr = StringBuilder()
    private var fieldState = 0 // 0 outside a field, 1 in its instruction, 2 in its shown result
    private var fieldDepth = 0 // fields inside fields
    private var citeNow: String? = null // in a citation's shown text: its field (References › Citations)
    private var controlNow: String? = null // in a form's content control: its sdtPr
    private var changeNow: DocChange? = null // inside a w:ins / w:del
    private fun <T> changed(e: Element, deleted: Boolean, f: () -> T): T {
        val outer = changeNow
        changeNow = DocChange(deleted, e.attr(W, "author") ?: "", e.attr(W, "date"))
        try { return f() } finally { changeNow = outer }
    }
    private fun fieldMark(instr: String): Char? = instr.trim().substringBefore(' ').uppercase().let { if (it == "PAGE") PAGE_FIELD else if (it == "NUMPAGES" || it == "SECTIONPAGES") PAGES_FIELD else null }

    /** A header's or footer's paragraphs, with their own relationships (a logo in a header is the header's picture). */
    fun readPart(path: String): List<DocPara> {
        val root = OfficeFormats.parse(parts[path] ?: return emptyList()).documentElement
        val partDir = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        images.clear(); links.clear()
        parts[relsPath(path)]?.let { r -> OfficeFormats.parse(r).documentElement.kids().forEach {
            when (it.getAttribute("Type")) {
                REL_HYPERLINK -> links[it.getAttribute("Id")] = it.getAttribute("Target")
                REL_IMAGE -> if (it.getAttribute("TargetMode") != "External") images[it.getAttribute("Id")] = resolve(partDir, it.getAttribute("Target"))
                REL_MEDIA, REL_OLE -> films[it.getAttribute("Id")] = resolve(partDir, it.getAttribute("Target"))
            }
        } }
        readStyles(); readNumbering()
        return ArrayList<DocPara>().also { block(root, it) }
    }

    fun read(): OfficeDoc {
        val xml = parts[docPath] ?: throw OfficeReadError(com.kalotrapezis.drive.R.string.office_error_not_word, "Not a Word document.")
        val root = OfficeFormats.parse(xml).documentElement
        if (root.namespaceURI != W) throw OfficeReadError(com.kalotrapezis.drive.R.string.office_error_strict, "This Word document is in a format Writer does not read (Strict Open XML).")
        if ("<w:body>" !in String(xml, Charsets.UTF_8)) notes += "an unusual file layout"
        parts[relsPath(docPath)]?.let { r ->
            OfficeFormats.parse(r).documentElement.kids().forEach {
                when (it.getAttribute("Type")) {
                    REL_HYPERLINK -> links[it.getAttribute("Id")] = it.getAttribute("Target")
                    REL_IMAGE -> if (it.getAttribute("TargetMode") != "External") images[it.getAttribute("Id")] = resolve(dir, it.getAttribute("Target"))
                    REL_CHART -> charts[it.getAttribute("Id")] = resolve(dir, it.getAttribute("Target"))
                    REL_MEDIA, REL_OLE -> films[it.getAttribute("Id")] = resolve(dir, it.getAttribute("Target"))
                }
            }
        }
        readStyles(); readNumbering()
        val body = root.kid(W, "body") ?: throw OfficeReadError(com.kalotrapezis.drive.R.string.office_error_not_word, "Not a Word document.")
        columnsArePages = root.getElementsByTagNameNS(W, "cols").let { l -> (0 until l.length).none { ((l.item(it) as Element).attr(W, "num")?.toIntOrNull() ?: 1) > 1 } }
        readComments(body)
        readNotes()
        val paras = ArrayList<DocPara>()
        block(body, paras)
        return OfficeDoc(joinDropCaps(paras).ifEmpty { listOf(DocPara(emptyList())) }, notes, basePt, comments.values.toList(), WordSources.read(parts), citeStyle = CiteStyle.ofWord(WordSources.styleName(parts)))
    }

    /** A drop cap's framed letter paragraph and the paragraph it drops into, as one paragraph with a [DropCap]. */
    private fun joinDropCaps(paras: List<DocPara>): List<DocPara> {
        val out = ArrayList<DocPara>()
        var i = 0
        while (i < paras.size) {
            val p = paras[i]
            val frame = p.keep?.parts?.firstOrNull { it.first == "dropFrame" }
            val next = paras.getOrNull(i + 1)
            if (frame == null || next == null || next.table != null || p.text.isEmpty()) {
                out += if (frame != null) p.copy(keep = p.keep.copy(parts = p.keep.parts - frame).takeIf { !it.isEmpty }) else p
                i++; continue
            }
            // The letters in the look of the text they begin (their own great size is the drop cap's).
            val letters = p.runs.filter { it.image == null }.map { it.copy(sizePt = null, script = 0) }
            val keep = next.keep ?: DocKeep()
            out += next.copy(runs = OfficeFormats.tidy(letters + next.runs), keep = keep.copy(parts = keep.parts + ("dropCap" to "${p.text.length}|${frame.second}")))
            i += 2
        }
        return out
    }

    /** Paragraph styles that number their paragraphs themselves (Word's List Bullet, List Number…): styleId → numPr. */
    val styleLists: Map<String, Element> by lazy { docxStyleLists(parts, dir) }

    fun readStyles() {
        val root = parts[dir + "styles.xml"]?.let { OfficeFormats.parse(it).documentElement } ?: return
        val plainBased = HashMap<String, String>() // a character style with no formatting → the style it is based on
        root.kid(W, "docDefaults")?.kid(W, "rPrDefault")?.kid(W, "rPr")?.kid(W, "sz")?.attr(W, "val")?.toFloatOrNull()?.let { basePt = it / 2 }
        for (s in root.kids().filter { it.localName == "style" }) {
            val id = s.attr(W, "styleId") ?: continue
            styleName[id] = (s.kid(W, "name")?.attr(W, "val") ?: id).lowercase()
            if (s.attr(W, "type") == "character" && s.kid(W, "rPr")?.kids().isNullOrEmpty() && s.kid(W, "pPr") == null) plainBased[id] = s.kid(W, "basedOn")?.attr(W, "val") ?: ""
            if (s.attr(W, "type") == "paragraph" && s.attr(W, "default").let { it == "1" || it == "true" }) {
                defaultParaStyle = id
                s.kid(W, "rPr")?.kid(W, "sz")?.attr(W, "val")?.toFloatOrNull()?.let { basePt = it / 2 }
            }
        }
        emptyCharStyles += plainBased.filter { (_, base) -> base.isEmpty() || styleName[base] == "default paragraph font" || plainBased[base]?.isEmpty() == true }.keys
    }

    fun readNumbering() {
        val root = parts[dir + "numbering.xml"]?.let { OfficeFormats.parse(it).documentElement } ?: return
        for (e in root.kids()) when (e.localName) {
            "abstractNum" -> {
                val id = e.attr(W, "abstractNumId") ?: continue
                if (e.kid(W, "numStyleLink") != null || e.kid(W, "styleLink") != null) notes += "other list formats"
                for (lvl in e.kids().filter { it.localName == "lvl" }) {
                    val i = lvl.attr(W, "ilvl")?.toIntOrNull() ?: continue
                    levels[id to i] = (lvl.kid(W, "numFmt")?.attr(W, "val") ?: "decimal") to (lvl.kid(W, "start")?.attr(W, "val")?.toIntOrNull() ?: 1)
                }
            }
            "num" -> {
                val id = e.attr(W, "numId") ?: continue
                numAbstract[id] = e.kid(W, "abstractNumId")?.attr(W, "val") ?: continue
                e.kids().filter { it.localName == "lvlOverride" }.forEach { o ->
                    val i = o.attr(W, "ilvl")?.toIntOrNull() ?: return@forEach
                    o.kid(W, "startOverride")?.attr(W, "val")?.toIntOrNull()?.let { numStart[id to i] = it }
                    if (o.kid(W, "lvl") != null) notes += "other list formats"
                }
            }
        }
    }

    /** The body, a table cell or a content control: paragraphs, and what Writer cannot keep, named. */
    fun block(parent: Element, out: MutableList<DocPara>) {
        val kids = parent.kids()
        // A field that runs on over paragraphs (a contents list or a list of references written as a bare field; Zotero's
        // and Mendeley's bibliographies): from its first paragraph to its last, kept whole as the file has it — as a
        // content control's paragraphs are ([sdtBlock]) — and written back so while their words are unchanged.
        var fieldBlock: Triple<Int, Int, Set<String>>? = null // where it starts in [out] and in [kids]; the notes before it
        for ((ki, e) in kids.withIndex()) {
            if (e.namespaceURI != W) { if (e.localName != "AlternateContent") notes += "other content" ; continue }
            when (e.localName) {
                "p" -> {
                    val open = fieldDepth > 0
                    val start = out.size
                    val notesBefore = notes.toSet()
                    paragraph(e, out)
                    if (sdtDepth == 0 && fieldBlock == null && !open && fieldDepth > 0) fieldBlock = Triple(start, ki, notesBefore)
                    else if (fieldBlock != null && fieldDepth == 0) {
                        val (from, fromKid, before) = fieldBlock
                        fieldBlock = null
                        val mine = out.subList(from, out.size)
                        // Its field rode on its letters as one paragraph's would: off them (edited, they are written plain).
                        val field = mine.firstNotNullOfOrNull { p -> p.runs.firstNotNullOfOrNull { r -> r.cite?.takeIf { isField(it) } } }
                        for (i in mine.indices) mine[i] = mine[i].let { p -> p.copy(runs = p.runs.map { r -> if (r.cite == field) r.copy(cite = null) else r }) }
                        var xml = kids.subList(fromKid, ki + 1).joinToString("") { OfficeFormats.xml(it) }
                        if (mine.first().keep?.parts?.any { it.first == "pageBreakBefore" } == true && "<w:pageBreakBefore" !in xml.substringBefore("</w:p>"))
                            Regex("""<w:p\b[^>]*>(<w:pPr>(<w:pStyle\b[^>]*/>)?)?""").find(xml)?.let { m ->
                                xml = xml.replaceRange(m.range, if (m.groupValues[1].isEmpty()) m.value + "<w:pPr><w:pageBreakBefore/></w:pPr>" else m.value + "<w:pageBreakBefore/>") }
                        val raw = "${OfficeFormats.blockText(mine)}|$xml"
                        mine.forEachIndexed { i, p -> val k = p.keep ?: DocKeep(); mine[i] = p.copy(keep = k.copy(parts = k.parts.filter { it.first != SDT_BLOCK && it.first != SDT_PART } + (if (i == 0) SDT_BLOCK to raw else SDT_PART to ""))) }
                        // What its paragraphs could not keep as paragraphs is kept in the XML: the copy-only notes they made go.
                        notes.retainAll(before)
                        citeNow = null
                    }
                }
                "tbl" -> { fieldBlock = null; out += tablePara(table(e)) } // a field round a table: not kept whole (a copy, as before)
                "sdt" -> if (e.kid(W, "sdtPr")?.kid(W, "docPartObj") != null) sdtBlock(e, out)
                    else { notes += "content controls"; e.kid(W, "sdtContent")?.let { block(it, out) } }
                "customXml" -> { notes += "content controls"; block(e, out) }
                "ins", "moveTo" -> { notes += "tracked changes"; block(e, out) }
                "del", "moveFrom" -> notes += "tracked changes"
                "sectPr", "bookmarkStart", "bookmarkEnd", "proofErr", "permStart", "permEnd", "tcPr", "trPr", "tblPr", "tblGrid" -> Unit
                "commentRangeStart" -> e.attr(W, "id")?.takeIf { it in comments }?.let { openComments += it } ?: run { notes += "comments" }
                "commentRangeEnd" -> e.attr(W, "id")?.let { openComments -= it }
                "altChunk" -> notes += "embedded objects"
                else -> if (e.localName.endsWith("RangeStart") || e.localName.endsWith("RangeEnd")) notes += "tracked changes" else notes += "other content"
            }
        }
    }

    /**
     * Word's table of contents, bibliography (or another building block — docPartObj): shown as its paragraphs, kept
     * whole as the file has it (its fields, links and page references) and written back so while its words are unchanged
     * (asked 2026-10-01: the user's theses were copy-only for their contents). Changed — edited, or made again by
     * References › Contents — its paragraphs are written as they are now, plain.
     */
    fun sdtBlock(e: Element, out: MutableList<DocPara>) {
        val start = out.size
        val pageBefore = pageBreakPending // a page break ended the paragraph before: the block's first paragraph starts a page
        sdtDepth++
        aside(notes, openComments) { e.kid(W, "sdtContent")?.let { block(it, out) } }
        sdtDepth--
        fieldDepth = 0; fieldState = 0; citeNow = null; pageBreakPending = false
        if (out.size == start) { notes += "content controls"; return }
        val mine = out.subList(start, out.size)
        var xml = OfficeFormats.xml(e)
        if (pageBefore) xml.substringBefore("</w:p>").let { first -> xml = first.replaceFirst(PAGE_BREAK_OFF, "") + xml.substring(first.length) } // Google Docs says "no break" on every paragraph
        if (pageBefore && "<w:pageBreakBefore" !in xml.substringBefore("</w:p>")) Regex("""<w:p\b[^>]*>(<w:pPr>(<w:pStyle\b[^>]*/>)?)?""").find(xml)?.let { m ->
            xml = xml.replaceRange(m.range, if (m.groupValues[1].isEmpty()) m.value + "<w:pPr><w:pageBreakBefore/></w:pPr>" else m.value + "<w:pageBreakBefore/>")
        }
        val raw = "${OfficeFormats.blockText(mine)}|$xml"
        mine.forEachIndexed { i, p -> val k = p.keep ?: DocKeep(); mine[i] = p.copy(keep = k.copy(parts = k.parts + (if (i == 0) SDT_BLOCK to raw else SDT_PART to ""))) }
    }

    /** A table: rows of cells of paragraphs; its, its rows' and its cells' properties kept raw. */
    fun table(e: Element): DocTable {
        fun cells(parent: Element): List<DocCell> = parent.kids().flatMap { c ->
            when (c.localName) {
                "tc" -> listOf(DocCell(c.kid(W, "tcPr")?.let { OfficeFormats.xml(it) } ?: "", ArrayList<DocPara>().also { block(c, it) }.ifEmpty { listOf(DocPara(emptyList())) }))
                "sdt" -> { notes += "content controls"; c.kid(W, "sdtContent")?.let { cells(it) }.orEmpty() }
                "customXml" -> { notes += "content controls"; cells(c) }
                else -> emptyList()
            }
        }
        val rows = e.kids().filter { it.localName == "tr" || it.localName == "sdt" || it.localName == "customXml" }.flatMap { tr ->
            if (tr.localName != "tr") { notes += "content controls"; (tr.kid(W, "sdtContent") ?: tr).kids().filter { it.localName == "tr" } } else listOf(tr)
        }.map { tr -> DocRow(tr.kids().filter { it.localName == "tblPrEx" || it.localName == "trPr" }.joinToString("") { OfficeFormats.xml(it) }, cells(tr)) }
        return DocTable(e.kid(W, "tblPr")?.let { OfficeFormats.xml(it) } ?: "", e.kid(W, "tblGrid")?.let { OfficeFormats.xml(it) } ?: "", rows)
    }

    fun paragraph(p: Element, out: MutableList<DocPara>) {
        var heading = 0
        var list: DocList? = null
        var align = DocAlign.Start
        var keepStyle: String? = null
        var ownNumbering = false // the paragraph says itself whether it is a list item (numId 0: it is not)
        val keepParts = ArrayList<Pair<String, String>>()
        val bookmarkNames = HashMap<String, String>() // a bookmark's id → its name, while its paragraph is read
        p.kid(W, "pPr")?.let { pPr ->
            for (e in pPr.kids()) when (e.localName) {
                "pStyle" -> e.attr(W, "val")?.let { id -> heading = paragraphStyle(id); if (heading == 0 && !plainStyle(id)) keepStyle = id }
                "numPr" -> { list = listItem(e); ownNumbering = true
                    // A heading whose style numbers it, said here to have no number: kept, or saving numbers it (a thesis's
                    // "Περίληψη" became "2 Περίληψη" and every chapter after it moved on by one — 2026-10-13).
                    if (list == null && heading > 0 && e.kid(W, "numId")?.attr(W, "val") == "0") keepParts += e.localName to OfficeFormats.xml(e) }
                "jc" -> align = when (e.attr(W, "val")) { "center" -> DocAlign.Center; "right", "end" -> DocAlign.End; "both", "distribute", "thaiDistribute" -> DocAlign.Justify
                    // Left said on purpose overrides a centred style (a heading): kept, or the heading centres on saving.
                    else -> { keepParts += e.localName to OfficeFormats.xml(e); DocAlign.Start } }
                // A section break (page size, margins, headers of the part before it): kept as the file has it (2026-09-28).
                "sectPr" -> keepParts += e.localName to OfficeFormats.xml(e)
                // A drop cap's letter (a framed paragraph of its own): joined to the paragraph after it, below.
                "framePr" -> if (e.attr(W, "dropCap").let { it == "drop" || it == "margin" }) keepParts += "dropFrame" to OfficeFormats.xml(e) else notes += "shapes and text boxes"
                // A paragraph's formatting changed (tracked): kept, for Accept or Reject.
                "pPrChange" -> keepParts += e.localName to OfficeFormats.xml(e)
                // The rest — spacing, indents, tab stops, borders, a page break before it… — goes back as it was.
                else -> keepParts += e.localName to OfficeFormats.xml(e)
            }
        }
        // A list numbered by its style shows as the list it is (asked 2026-09-28: the test suite's bullets did not show).
        if (!ownNumbering && heading == 0) keepStyle?.let { styleLists[it] }?.let { list = listItem(it) }
        val listSource = lastListSource.takeIf { list != null }
        val runs = CommentedRuns(openComments, { citeNow }, { changeNow }, { controlNow })
        // A page break inside a paragraph: the paragraph is split there and the part after it starts a page — Word's
        // own "page break before", which saves and shows the same (2026-09-28; it made files copy-only before).
        // A break at the paragraph's start: the paragraph starts a page. At its end (or alone in it): the next paragraph
        // does, nothing else is kept (LibreOffice shows nothing there either). In the middle: the part after it starts a
        // page, with no space before it (it is the same paragraph going on, in the file).
        var afterPageBreak = pageBreakPending.also { pageBreakPending = false }
        val brokenBefore = afterPageBreak
        columnBreak = false
        val done = ArrayList<DocPara>()
        fun keep(): DocKeep? {
            var parts: List<Pair<String, String>> = if (done.isEmpty()) keepParts else keepParts.filter { it.first != BOOKMARKS && it.first != LABEL_BOOKMARKS } // a bookmark once
            // Not "parts has one": Google Docs writes <w:pageBreakBefore w:val="0"/> on every paragraph, and a break before
            // such a paragraph was lost (a paper's contents moved up onto its cover page — 2026-10-13).
            if (afterPageBreak && parts.none { it.first == "pageBreakBefore" && !PAGE_BREAK_OFF.matches(it.second) }) parts = parts.filter { it.first != "pageBreakBefore" } + ("pageBreakBefore" to "<w:pageBreakBefore/>")
            // No space before at the new page's top either when the break was the paragraph before's: LibreOffice gives a
            // paragraph its space only when it breaks the page itself (a heading came two lines lower — 02_Tables).
            val breaksItself = keepParts.any { it.first == "pageBreakBefore" && !PAGE_BREAK_OFF.matches(it.second) }
            if (afterPageBreak && (done.isNotEmpty() || (brokenBefore && !breaksItself && sdtDepth == 0))) parts = parts.filter { it.first != "spacing" } +
                ("spacing" to (parts.firstOrNull { it.first == "spacing" }?.second?.let(::noSpaceBefore) ?: "<w:spacing w:before=\"0\"/>"))
            return DocKeep(keepStyle, parts.sortedBy { PPR_ORDER.indexOf(it.first).let { i -> if (i < 0) PPR_ORDER.indexOf("rPr") else i } }, listSource.takeIf { list != null }).takeIf { !it.isEmpty }
        }
        fun breakLine(page: Boolean = false) {
            if (page && runs.isEmpty() && done.isEmpty()) { afterPageBreak = true; return }
            done += DocPara(OfficeFormats.tidy(runs), heading, list, align, keep()); runs.clear(); list = list?.let { null }; afterPageBreak = page
        }
        fun breakColumn() {
            if (runs.isNotEmpty() || done.isNotEmpty()) { done += DocPara(OfficeFormats.tidy(runs), heading, list, align, keep()); runs.clear(); list = list?.let { null } }
            if (keepParts.none { it.first == COLUMN_BREAK }) keepParts += COLUMN_BREAK to "<w:columnBreak/>"
        }
        fun content(parent: Element, link: String?) {
            for (e in parent.kids()) {
                if (e.namespaceURI != W) {
                    // An equation (Phase 2): drawn from what it shows, its XML written back as it was.
                    if (e.localName == "oMath" || e.localName == "oMathPara") {
                        val math = runCatching { OfficeMath.read(e) }.getOrNull()
                        if (math == null) { notes += "equations"; continue }
                        if (e.localName == "oMathPara" && runs.none { it.text.isNotBlank() } && align == DocAlign.Start) align = DocAlign.Center // a display: centred, as Word shows it
                        runs += DocRun(OBJECT_MARK.toString(), image = OfficeMath.image("f${imageCount++}", math, OfficeFormats.xml(e), basePt))
                    } else notes += "other content"
                    continue
                }
                when (e.localName) {
                    "pPr" -> Unit
                    "r" -> run(e, link, runs, ::breakLine)
                    "hyperlink" -> {
                        // a link inside the document: to a bookmark (#name), as Word's contents' entries are
                        val url = e.attr(R, "id")?.let { links[it] } ?: e.attr(W, "anchor")?.let { "#$it" }
                        if (url == null) notes += "links inside the document"
                        content(e, url ?: link)
                    }
                    "ins", "moveTo" -> changed(e, false) { content(e, link) }
                    "del", "moveFrom" -> changed(e, true) { content(e, link) }
                    "fldSimple" -> { notes += "fields (page numbers, dates, contents)"
                        val mark = if (fieldMarks) fieldMark(e.attr(W, "instr") ?: "") else null
                        if (mark != null) runs += DocRun(mark.toString()) else content(e, link) }
                    "smartTag", "customXml" -> content(e, link)
                    // A citation's content control (Word's): only its field is kept. A form's (text, drop-down, date,
                    // check box — asked 2026-10-01): kept on its letters, filled in by the editor.
                    "sdt" -> {
                        val pr = e.kid(W, "sdtPr")
                        val form = pr != null && pr.kid(W, "citation") == null && controlNow == null && pr.kids().none { it.localName in OTHER_CONTROLS }
                        if (form) controlNow = OfficeFormats.xml(pr!!) else if (pr?.kid(W, "citation") == null) notes += "content controls"
                        e.kid(W, "sdtContent")?.let { content(it, link) }
                        if (form) controlNow = null
                    }
                    "commentRangeStart" -> e.attr(W, "id")?.takeIf { it in comments }?.let { openComments += it } ?: run { notes += "comments" }
                    "commentRangeEnd" -> e.attr(W, "id")?.let { openComments -= it }
                    // A bookmark (the contents' links and page numbers go to the headings' _Toc ones): kept on its paragraph.
                    "bookmarkStart" -> e.attr(W, "name")?.takeIf { it != "_GoBack" }?.let { n ->
                        e.attr(W, "id")?.let { bookmarkNames[it] = n }
                        keepParts.indexOfFirst { it.first == BOOKMARKS }.let { i -> if (i < 0) keepParts += BOOKMARKS to n else keepParts[i] = BOOKMARKS to keepParts[i].second + " " + n } }
                    // One that ends just after a caption's number (Word's "Only label and number"): kept as that.
                    "bookmarkEnd" -> e.attr(W, "id")?.let { bookmarkNames[it] }?.let { n ->
                        val i = keepParts.indexOfFirst { it.first == BOOKMARKS }
                        val names = keepParts.getOrNull(i)?.second?.split(' ').orEmpty()
                        if (n in names && runs.lastOrNull()?.cite?.let { isField(it) && it.drop(1).trimStart().startsWith("SEQ ", true) } == true) {
                            if (names.size == 1) keepParts.removeAt(i) else keepParts[i] = BOOKMARKS to (names - n).joinToString(" ")
                            keepParts.indexOfFirst { it.first == LABEL_BOOKMARKS }.let { j -> if (j < 0) keepParts += LABEL_BOOKMARKS to n else keepParts[j] = LABEL_BOOKMARKS to keepParts[j].second + " " + n }
                        }
                    }
                    "proofErr", "permStart", "permEnd", "lastRenderedPageBreak" -> Unit
                    // A move's two ends are named: its deletion and insertion are kept, the pairing is not.
                    "moveFromRangeStart", "moveFromRangeEnd", "moveToRangeStart", "moveToRangeEnd" -> Unit
                    else -> if (e.localName.endsWith("RangeStart") || e.localName.endsWith("RangeEnd")) notes += "tracked changes" else notes += "other content"
                }
            }
        }
        val outerBreak = columnBreakHere // a text box's paragraph read inside this one gives it back
        columnBreakHere = ::breakColumn
        content(p, null)
        columnBreakHere = outerBreak
        if (fieldDepth > 0 && isField(citeNow)) notes += "fields (page numbers, dates, contents)" // it goes on past this paragraph
        // A column break leaves an empty line at the top of the next page, a page break does not (LibreOffice, measured
        // 2026-10-13): that line is kept as an empty paragraph starting the page, so a paper keeps its page ends.
        if (afterPageBreak && runs.isEmpty() && (done.isNotEmpty() || p.getElementsByTagNameNS(W, "br").length > 0) && !columnBreak) {
            // A page break alone in its paragraph still has that paragraph's line on the page before (Word and LibreOffice):
            // kept as an empty paragraph, or a full page's last table no longer spills onto a page of its own (2026-10-13).
            if (done.isEmpty() && p.getElementsByTagNameNS(W, "br").length > 0) { afterPageBreak = brokenBefore; done += DocPara(emptyList(), heading, list, align, keep()) }
            pageBreakPending = true
        }
        else done += DocPara(OfficeFormats.tidy(runs), heading, list, align, keep())
        out += done
    }
    /** A page break ended the paragraph before: this one starts a page. */
    var pageBreakPending = false
    /** In a paragraph of the body: a column break there splits it, the part after it marked to start the next column. */
    var columnBreakHere: (() -> Unit)? = null
    /**
     * No section has more than one column: a column break goes on at the next page, in Word and LibreOffice alike, so
     * it is read as the page break it looks like and saves as one (2026-10-13: it made 10 of the user's papers copy-only).
     */
    var columnsArePages = false
    /** Inside a content control kept whole (its XML written back as it was): its paragraphs' spacing is the XML's. */
    var sdtDepth = 0
    /** The paragraph being read broke with a column break: its empty rest is a line of its own on the next page. */
    var columnBreak = false

    /** Reads a run into [runs]; returns its font (the rFonts element) when it names one. */
    fun run(r: Element, link: String?, runs: MutableList<DocRun>, breakLine: (page: Boolean) -> Unit) {
        val look = lookOf(r.kid(W, "rPr"), link)
        for (e in r.kids()) {
            if (e.namespaceURI != W) {
                // Word wraps newer drawings in mc:AlternateContent (a shape, and a fallback for older readers): kept whole.
                if (e.localName == "AlternateContent") runs += look.copy(text = OBJECT_MARK.toString(), image = drawing(e)) else notes += "other content"
                continue
            }
            when (e.localName) {
                "rPr" -> Unit
                "t" -> if (!(fieldMarks && fieldState == 2 && fieldMark(fieldInstr.toString()) != null)) runs += look.copy(text = e.textContent) // a page number's old value: the page's own instead
                "tab", "ptab" -> runs += look.copy(text = "\t")
                "noBreakHyphen" -> runs += look.copy(text = "‑")
                // an optional hyphen (Word's Ctrl + -): kept as the soft hyphen it is, written back as Word's
                "softHyphen" -> runs += look.copy(text = "\u00AD")
                "lastRenderedPageBreak" -> Unit
                "br", "cr" -> when (e.attr(W, "type")) {
                    "page" -> breakLine(true)
                    // In columns (Phase 2): the part after it starts the next column — a paragraph of its own marked so,
                    // written back with the break at its start (no longer copy-only).
                    "column" -> if (columnsArePages) { columnBreak = true; breakLine(true) } else columnBreakHere?.invoke() ?: run { notes += "columns"; breakLine(false) }
                    else -> runs += look.copy(text = LINE_BREAK.toString())
                }
                "drawing", "pict", "object" -> if (!(e.localName == "pict" && e.getElementsByTagNameNS("urn:schemas-microsoft-com:vml", "shape").let { l ->
                        (0 until l.length).any { Watermark.isOne((l.item(it) as Element).getAttribute("id")) } })) // a watermark: the pages draw it
                    runs += look.copy(text = OBJECT_MARK.toString(), image = drawing(e))
                // Fields: a citation's is kept with its letters (written again as it was); other fields' shown text is
                // read as text and the file said to have fields (saving makes a copy).
                "fldChar", "instrText" -> when {
                    e.localName == "instrText" -> if (fieldDepth == 1) fieldInstr.append(e.textContent) else notes += "fields (page numbers, dates, contents)"
                    e.attr(W, "fldCharType") == "begin" -> { if (++fieldDepth == 1) { fieldState = 1; fieldInstr.clear() } else notes += "fields (page numbers, dates, contents)" }
                    e.attr(W, "fldCharType") == "separate" -> if (fieldDepth == 1) {
                        fieldState = 2
                        val instr = fieldInstr.toString().trim()
                        if (!fieldMarks && instr.startsWith("CITATION ")) citeNow = instr
                        // Any other field: its letters kept, the field written back round them — a contents list or a list of
                        // references too, when it is in one paragraph (lines broken inside it). One that runs on over
                        // paragraphs is kept whole by [block] (its paragraphs' XML).
                        else if (!fieldMarks && instr.isNotEmpty()) citeNow = FIELD_CITE + instr
                        else { notes += "fields (page numbers, dates, contents)"; if (fieldMarks) fieldMark(fieldInstr.toString())?.let { runs += look.copy(text = it.toString()) } }
                    }
                    e.attr(W, "fldCharType") == "end" -> {
                        if (fieldDepth == 1) { if (fieldState == 1) notes += "fields (page numbers, dates, contents)"; fieldState = 0; citeNow = null }
                        fieldDepth = (fieldDepth - 1).coerceAtLeast(0)
                    }
                    else -> notes += "fields (page numbers, dates, contents)"
                }
                "delText" -> runs += look.copy(text = e.textContent)
                "delInstrText" -> if (fieldDepth == 1) fieldInstr.append(e.textContent) else notes += "fields (page numbers, dates, contents)"
                "footnoteReference", "endnoteReference" -> (if (e.localName == "endnoteReference") endnotes else footnotes)[e.attr(W, "id")]
                    ?.let { n -> runs += look.copy(text = "1", note = n, script = 1) } ?: run { notes += "footnotes" }
                "footnoteRef", "endnoteRef" -> Unit // a note's own number: written again
                "commentReference" -> Unit // written again after the comment's range
                "annotationRef" -> notes += "comments"
                "sym" -> notes += "symbols"
                else -> notes += "other content"
            }
        }
    }
    /** A run's letter formatting (its rPr): what Writer edits, and the rest carried raw in [DocRun.font]. */
    fun lookOf(rPr: Element?, link: String?): DocRun {
        var look = DocRun("", link = link)
        val kept = StringBuilder() // what Writer carries without editing, as the file has it
        rPr?.let { rPr ->
            for (e in rPr.kids()) {
                val on = e.attr(W, "val").let { it == null || it == "1" || it == "true" || it == "on" }
                when (e.localName) {
                    "b" -> look = look.copy(bold = on, unbold = !on)
                    "i" -> look = look.copy(italic = on, unitalic = !on)
                    "strike" -> look = look.copy(strike = on)
                    "u" -> {
                        look = look.copy(underline = e.attr(W, "val") != "none")
                        if (e.attr(W, "val") !in setOf(null, "single", "none")) kept.append(OfficeFormats.xml(e)) // double, dotted, wavy…
                    }
                    "sz" -> e.attr(W, "val")?.toFloatOrNull()?.let { look = look.copy(sizePt = it / 2) } // a size set on purpose stays, even the normal one (inside a heading it matters)
                    "color" -> look = look.copy(color = OfficeFormats.parseHex(e.attr(W, "val")))
                    "highlight" -> look = look.copy(highlight = WORD_HIGHLIGHTS[e.attr(W, "val")])
                    "rStyle" -> when (styleName[e.attr(W, "val")] ?: e.attr(W, "val")?.lowercase()) {
                        "strong" -> look = look.copy(bold = true)
                        "emphasis" -> look = look.copy(italic = true)
                        "placeholder text" -> look = look.copy(color = 0x808080) // a form's empty field: Word's grey
                        "hyperlink", "internet link", "default paragraph font", "footnote reference", "endnote reference" -> Unit
                        else -> if (e.attr(W, "val") !in emptyCharStyles) notes += "character styles"
                    }
                    // Kept exactly, letter by letter (asked 2026-09-27 for fonts; 2026-09-28 the rest, from the user's test suite).
                    in RPR_CARRIED -> kept.append(OfficeFormats.xml(e))
                    "vertAlign" -> look = look.copy(script = when (e.attr(W, "val")) { "superscript" -> 1; "subscript" -> -1; else -> 0 })
                    // Hidden text: kept with its letters (the pages leave it out, the editor greys it), no reason for a copy.
                    "vanish" -> kept.append(OfficeFormats.xml(e))
                    "rPrChange" -> kept.append(OfficeFormats.xml(e)) // formatting changed (tracked): carried, for Accept or Reject
                    else -> if (e.namespaceURI != W) kept.append(OfficeFormats.xml(e)) // Word 2010's own (w14:ligatures…), last in the list
                        else if (on) notes += "other text effects"
                }
            }
        }
        if (kept.isNotEmpty()) look = look.copy(font = kept.toString())
        return look
    }

    fun paragraphStyle(id: String): Int {
        val name = styleName[id] ?: id.lowercase()
        Regex("""heading ?([1-9])""").matchEntire(name)?.let { m -> return m.groupValues[1].toInt().coerceAtMost(6).also { if (m.groupValues[1].toInt() > 6) notes += "headings below level 6" } }
        return 0
    }

    /** A picture, chart or shape, kept as the file has it: its XML, the picture it shows (if any) and its size. */
    fun drawing(e: Element): DocImage {
        val blip = e.getElementsByTagNameNS("*", "blip").let { if (it.length > 0) it.item(0) as Element else null }
        val relId = blip?.getAttributeNS(R, "embed")?.takeIf { it.isNotEmpty() }
            ?: e.getElementsByTagNameNS("*", "imagedata").let { if (it.length > 0) (it.item(0) as Element).getAttributeNS(R, "id").takeIf { id -> id.isNotEmpty() } else null }
        val part = relId?.let { images[it] }
        val extent = e.getElementsByTagNameNS("*", "extent").let { if (it.length > 0) it.item(0) as Element else null }
        // Word's older drawings (VML) give their size in a style: "width:120pt;height:1.5pt"; a horizontal rule spans the page.
        val vml = e.getElementsByTagNameNS("*", "*").let { all -> (0 until all.length).map { all.item(it) as Element }.firstOrNull { it.hasAttribute("style") && "width" in it.getAttribute("style") } }
        fun vmlLength(key: String): Float? = vml?.getAttribute("style")?.let { Regex("""(?:^|;)\s*$key\s*:\s*([\d.]+)(pt|in|px|cm|mm)?""").find(it) }?.let { m ->
            val n = m.groupValues[1].toFloatOrNull() ?: return@let null
            when (m.groupValues[2]) { "in" -> n * 72; "px" -> n * 0.75f; "cm" -> n * 28.3465f; "mm" -> n * 2.83465f; else -> n }
        }
        val rule = vml?.getAttributeNS("urn:schemas-microsoft-com:office:office", "hr") == "t"
        // Pen strokes (Word's ink): the box is the line's centre, a flat stroke is 0 tall; its picture (the older-Word
        // fallback) is the box with the pen's width around it, so that box is the one it is drawn in (2026-09-29).
        val ink = e.getElementsByTagNameNS("*", "contentPart").length > 0
        val w = (if (ink) vmlLength("width") else null) ?: extent?.getAttribute("cx")?.toLongOrNull()?.let { it / 12700f } ?: vmlLength("width")?.takeIf { it > 0 && !rule } ?: if (rule) 450f else 144f
        val h = (if (ink) vmlLength("height") else null) ?: extent?.getAttribute("cy")?.toLongOrNull()?.let { it / 12700f } ?: vmlLength("height")?.takeIf { it > 0 } ?: 96f
        // A text box: its words (Word's own, not the older-Word copy in the fallback), for the pages to show.
        val box = (e.getElementsByTagNameNS("*", "txbxContent").item(0) as Element?)?.let { t -> aside(notes, openComments) { ArrayList<DocPara>().also { block(t, it) } } }
        val chart = (e.getElementsByTagNameNS("*", "chart").item(0) as Element?)?.getAttributeNS(R, "id")?.let { charts[it] }?.let { parts[it] }?.let { OfficeCharts.readDrawingml(it) }
        // A group (Word's wpg): its pieces, each drawn in its place (only the first piece's picture or words showed before).
        val group = (e.getElementsByTagNameNS("*", "wgp").item(0) as Element?)?.let { groupParts(it, w, h) }
            ?: (e.getElementsByTagNameNS("*", "relIds").item(0) as Element?)?.let { smartArt(it) }
        // a video from the phone (Words' Insert › Video): its film in the package, named in its picture's ext
        val film = (e.getElementsByTagNameNS(TETRA_VIDEO, "film").item(0) as Element?)?.getAttributeNS(R, "embed")?.let { films[it] }
            // a film or a sound embedded as an object (Word's Insert › Object › Create from file): "its part#its file's name"
            ?: (e.getElementsByTagNameNS("*", "OLEObject").item(0) as Element?)?.getAttributeNS(R, "id")?.let { films[it] }?.let { p ->
                parts[p]?.let { OfficeVideo.ole(it, nameOnly = true) }?.first?.takeIf(OfficeVideo::media)?.let { "$p#$it" } }
        return DocImage("f${imageCount++}", part, w, h, OfficeFormats.xml(e), box = box.takeIf { group == null }, chart = chart.takeIf { group == null }, group = group, film = film)
    }
    /**
     * SmartArt, shown (not edited): the drawing Word saves beside its data (word/diagrams/drawing*.xml — the data part
     * names it in dsp:dataModelExt), each shape where it stands with its words, as a group's pieces. The SmartArt itself
     * is kept in the file as it was. Null: no saved drawing (an old file; shown as a grey box).
     */
    fun smartArt(ids: Element): List<GroupPart>? {
        val docRels = parts[relsPath(docPath)]?.let { OfficeFormats.parse(it).documentElement.kids() }.orEmpty()
        fun target(id: String) = docRels.firstOrNull { it.getAttribute("Id") == id }?.getAttribute("Target")?.let { resolve(dir, it) }
        val data = ids.getAttributeNS(R, "dm").takeIf { it.isNotEmpty() }?.let(::target)?.let { parts[it] } ?: return null
        val drawingId = Regex("""dataModelExt\b[^>]*\brelId="([^"]+)"""").find(String(data, Charsets.UTF_8))?.groupValues?.get(1) ?: return null
        val root = target(drawingId)?.let { parts[it] }?.let { OfficeFormats.parse(it).documentElement } ?: return null
        val out = ArrayList<GroupPart>()
        fun num(x: Element?, kid: String, a: String) = x?.kids()?.firstOrNull { it.localName == kid }?.getAttribute(a)?.toFloatOrNull()?.div(12700f) ?: 0f
        val all = root.getElementsByTagNameNS("*", "sp")
        for (i in 0 until all.length) {
            val sp = all.item(i) as Element
            val xfrm = sp.kids().firstOrNull { it.localName == "spPr" }?.kids()?.firstOrNull { it.localName == "xfrm" }
            val w = num(xfrm, "ext", "cx"); val h = num(xfrm, "ext", "cy")
            if (w <= 0f || h <= 0f) continue
            // As a Word shape (wps) for the shape's drawing: its geometry, fill, outline and style's theme colours.
            val xml = OfficeFormats.xml(sp).replace("<dsp:spPr", "<wps:spPr").replace("</dsp:spPr>", "</wps:spPr>").replace("<dsp:style", "<wps:style").replace("</dsp:style>", "</wps:style>")
            val fontColor = Regex("""<a:fontRef\b[^>]*>([\s\S]*?)</a:fontRef>""").find(xml)?.groupValues?.get(1)?.let { ShapePicture.themeColor(it) }?.and(0xFFFFFF)
            val paras = sp.kids().firstOrNull { it.localName == "txBody" }?.kids()?.filter { it.localName == "p" }?.map { p ->
                val algn = p.kids().firstOrNull { it.localName == "pPr" }?.getAttribute("algn")
                val runs = p.kids().filter { it.localName == "r" }.map { r ->
                    val pr = r.kids().firstOrNull { it.localName == "rPr" }
                    val color = pr?.kids()?.firstOrNull { it.localName == "solidFill" }?.let { ShapePicture.themeColor(OfficeFormats.xml(it)) }?.and(0xFFFFFF) ?: fontColor
                    DocRun(r.kids().firstOrNull { it.localName == "t" }?.textContent.orEmpty(), bold = pr?.getAttribute("b") == "1", italic = pr?.getAttribute("i") == "1",
                        sizePt = pr?.getAttribute("sz")?.toFloatOrNull()?.div(100f), color = color)
                }
                DocPara(OfficeFormats.tidy(runs), align = when (algn) { "ctr" -> DocAlign.Center; "r" -> DocAlign.End; "just" -> DocAlign.Justify; else -> DocAlign.Start })
            }?.takeIf { ps -> ps.any { it.text.isNotBlank() } }
            // Its words in their own rectangle (dsp:txXfrm: inside a chevron's body, not over its notch), the shape apart.
            val tx = sp.kids().firstOrNull { it.localName == "txXfrm" }
            val tw = num(tx, "ext", "cx"); val th = num(tx, "ext", "cy")
            // Words wider than their rectangle made smaller to fit (Word shrinks SmartArt's text so; ~0.56 of a letter's size is its width).
            fun fitted(ps: List<DocPara>, room: Float): List<DocPara> {
                val need = ps.flatMap { p -> p.runs.flatMap { r -> r.text.split(' ').map { it.length * (r.sizePt ?: 11f) * 0.56f } } }.maxOrNull() ?: return ps
                val k = (room / need).takeIf { it < 1f } ?: return ps
                return ps.map { p -> p.copy(runs = p.runs.map { r -> r.copy(sizePt = ((r.sizePt ?: 11f) * k * 2).toInt() / 2f) }) }
            }
            if (paras != null && tx != null && tw > 0f && th > 0f) {
                out += GroupPart(num(xfrm, "off", "x"), num(xfrm, "off", "y"), w, h, DocImage("f${imageCount++}", null, w, h, xml))
                val body = Regex("""<a:bodyPr\b[^>]*?/?>""").find(xml)?.value?.replace("<a:bodyPr", "<wps:bodyPr")?.let { if (it.endsWith("/>")) it else it.dropLast(1) + "/>" } ?: """<wps:bodyPr anchor="ctr"/>"""
                val words = """<wps:wsp><wps:spPr><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln></wps:spPr>$body</wps:wsp>"""
                out += GroupPart(num(tx, "off", "x"), num(tx, "off", "y"), tw, th, DocImage("f${imageCount++}", null, tw, th, words, box = fitted(paras, tw)))
            } else out += GroupPart(num(xfrm, "off", "x"), num(xfrm, "off", "y"), w, h, DocImage("f${imageCount++}", null, w, h, xml, box = paras))
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** A group's pieces in its box ([w] × [h] pt): pictures, shapes and text boxes, and groups inside it. */
    fun groupParts(g: Element, w: Float, h: Float): List<GroupPart>? {
        fun xfrm(e: Element) = e.kids().firstOrNull { it.localName.endsWith("spPr") || it.localName == "grpSpPr" }?.kids()?.firstOrNull { it.localName == "xfrm" }
        fun num(x: Element?, kid: String, a: String) = x?.kids()?.firstOrNull { it.localName == kid }?.getAttribute(a)?.toFloatOrNull() ?: 0f
        val gx = xfrm(g)
        val sx = w / num(gx, "chExt", "cx").takeIf { it > 0 }.let { it ?: (w * 12700) }; val sy = h / num(gx, "chExt", "cy").takeIf { it > 0 }.let { it ?: (h * 12700) }
        val out = ArrayList<GroupPart>()
        for (k in g.kids().filter { it.localName in setOf("wsp", "pic", "grpSp") }) {
            val kx = xfrm(k)
            val x = (num(kx, "off", "x") - num(gx, "chOff", "x")) * sx; val y = (num(kx, "off", "y") - num(gx, "chOff", "y")) * sy
            val pw = num(kx, "ext", "cx") * sx; val ph = num(kx, "ext", "cy") * sy
            val relId = (k.getElementsByTagNameNS("*", "blip").item(0) as Element?)?.getAttributeNS(R, "embed")?.takeIf { it.isNotEmpty() }
            val box = if (k.localName == "wsp") (k.getElementsByTagNameNS("*", "txbxContent").item(0) as Element?)?.let { t -> aside(notes, openComments) { ArrayList<DocPara>().also { block(t, it) } } } else null
            val inner = if (k.localName == "grpSp") groupParts(k, pw, ph) else null
            out += GroupPart(x, y, pw, ph, DocImage("f${imageCount++}", relId?.let { images[it] }, pw, ph, OfficeFormats.xml(k), box = box, group = inner))
        }
        return out.takeIf { it.isNotEmpty() }
    }
    var imageCount = 0

    /** Normal text: the paragraph needs no style of its own to look as it did. */
    // Not List Paragraph: it spaces and indents differently from Normal (lost, a table of contents lost its gaps — 2026-09-28).
    fun plainStyle(id: String): Boolean = (styleName[id] ?: id.lowercase()).let { id == defaultParaStyle || it == "normal" || it == "default paragraph style" }

    var lastListSource: String? = null

    fun listItem(numPr: Element): DocList? {
        val numId = numPr.kid(W, "numId")?.attr(W, "val") ?: return null
        if (numId == "0") return null
        val level = numPr.kid(W, "ilvl")?.attr(W, "val")?.toIntOrNull() ?: 0
        val abstract = numAbstract[numId] ?: return null
        val (fmt, start) = levels[abstract to level] ?: ("decimal" to 1)
        lastListSource = numId + "|" + (0..8).joinToString("") { l -> if ((levels[abstract to l]?.first ?: "decimal") == "bullet") "b" else "n" }
        val ordered = when (fmt) {
            "bullet" -> false
            "decimal" -> true
            "none" -> { notes += "other list formats"; false }
            else -> true // a), iii., B.: drawn as the file says on the pages and in the editor, and the file's list is kept on saving
        }
        // Word counts on through the whole document for one list; a number is kept by saying where each part starts.
        val c = counters.getOrPut(numId) { IntArray(9) { Int.MIN_VALUE } }
        val first = numStart[numId to level] ?: start
        c[level.coerceIn(0, 8)] = if (c[level.coerceIn(0, 8)] == Int.MIN_VALUE) first else c[level.coerceIn(0, 8)] + 1
        for (deeper in level + 1 until 9) c[deeper] = Int.MIN_VALUE
        return DocList(ordered, level.coerceIn(0, 8), c[level.coerceIn(0, 8)].takeIf { ordered })
    }
}

/** Paragraph styles that number their paragraphs (their own numPr, or one they are based on): styleId → numPr. */
private fun docxStyleLists(parts: Map<String, ByteArray>, dir: String): Map<String, Element> {
    val root = parts[dir + "styles.xml"]?.let { runCatching { OfficeFormats.parse(it).documentElement }.getOrNull() } ?: return emptyMap()
    val styles = root.kids().filter { it.localName == "style" && it.attr(W, "type") == "paragraph" }.associateBy { it.attr(W, "styleId") }
    fun numPr(id: String?, depth: Int = 0): Element? {
        val s = styles[id] ?: return null
        return s.kid(W, "pPr")?.kid(W, "numPr") ?: if (depth < 10) numPr(s.kid(W, "basedOn")?.attr(W, "val"), depth + 1) else null
    }
    return styles.keys.filterNotNull().mapNotNull { id -> numPr(id)?.takeIf { it.kid(W, "numId")?.attr(W, "val").let { n -> n != null && n != "0" } }?.let { id to it } }.toMap()
}

/** The order of a paragraph's properties in Word's schema; Word refuses a file with them out of order. */
/** Content controls Writer does not fill in (a form's own are text, rich text, drop-down, combo box, date, check box, picture). */
internal val OTHER_CONTROLS = setOf("docPartObj", "docPartList", "bibliography", "equation", "group", "citation", "repeatingSection", "repeatingSectionItem")
/** A paragraph's kept parts that are not its properties: the building block it begins or goes on, its bookmarks. */
internal const val SDT_BLOCK = "sdtBlock"
internal const val SDT_PART = "sdtPart"
internal const val BOOKMARKS = "bookmarks"
/** Bookmarks round a caption's label and number only ("Figure 1"): from the paragraph's start to its first SEQ field's end. */
internal const val LABEL_BOOKMARKS = "labelBookmarks"
/** A column break before the paragraph (Word: a w:br type="column" run at its start), as its pages lay it out. */
internal const val COLUMN_BREAK = "columnBreak"
internal val NOT_PPR = setOf("runFonts", SDT_BLOCK, SDT_PART, BOOKMARKS, LABEL_BOOKMARKS, COLUMN_BREAK)
/** A spacing element with no space before, its attributes in the order the parser gives them back (so it reads back the same). */
private fun noSpaceBefore(spacing: String): String {
    val attrs = Regex("""\s([\w:]+)="[^"]*"""").findAll(spacing).filter { it.groupValues[1] !in setOf("w:before", "w:beforeAutospacing") }.map { it.value.trim() }.toList()
    return "<w:spacing " + (attrs + "w:before=\"0\"").sortedBy { it.substringBefore('=') }.joinToString(" ") + "/>"
}

/** Word's "no page break before", as Google Docs writes it on every paragraph. */
private val PAGE_BREAK_OFF = Regex("""<w:pageBreakBefore\s+w:val="(0|false|off)"\s*/>""")

internal val PPR_ORDER = listOf("pStyle", "keepNext", "keepLines", "pageBreakBefore", "framePr", "widowControl", "numPr", "suppressLineNumbers", "pBdr",
    "shd", "tabs", "suppressAutoHyphens", "kinsoku", "wordWrap", "overflowPunct", "topLinePunct", "autoSpaceDE", "autoSpaceDN", "bidi", "adjustRightInd",
    "snapToGrid", "spacing", "ind", "contextualSpacing", "mirrorIndents", "suppressOverlap", "jc", "textDirection", "textAlignment", "textboxTightWrap",
    "outlineLvl", "divId", "cnfStyle", "rPr", "sectPr", "pPrChange")

/** The text properties Writer writes itself in ODF (from bold, italic, underline, strike, size, colours, super/subscript). */
private val ODF_WRITTEN = setOf("font-weight", "font-weight-asian", "font-weight-complex", "font-style", "font-style-asian", "font-style-complex",
    "text-underline-style", "text-underline-width", "text-underline-color", "text-line-through-style", "text-line-through-type",
    "font-size", "font-size-asian", "font-size-complex", "color", "background-color", "text-position")

/** A run's properties in the order Word's schema requires. */
internal val RPR_ORDER = listOf("rStyle", "rFonts", "b", "bCs", "i", "iCs", "caps", "smallCaps", "strike", "dstrike", "outline", "shadow", "emboss",
    "imprint", "noProof", "snapToGrid", "vanish", "webHidden", "color", "spacing", "w", "kern", "position", "sz", "szCs", "highlight", "u", "effect",
    "bdr", "shd", "fitText", "vertAlign", "rtl", "cs", "em", "lang", "eastAsianLayout", "specVanish", "oMath")
/** The run properties Writer carries as the file has them, without editing them. */
private val RPR_CARRIED = setOf("rFonts", "bCs", "iCs", "caps", "smallCaps", "dstrike", "outline", "shadow", "emboss", "imprint", "noProof", "snapToGrid",
    "webHidden", "spacing", "w", "kern", "position", "szCs", "effect", "bdr", "shd", "fitText", "rtl", "cs", "em", "lang", "eastAsianLayout", "specVanish")
/** One element of carried XML, with its children. */
private val RAW_ELEMENT = Regex("""<(\w+:\w+)\b[^>]*?(?:/>|>[\s\S]*?</\1>)""")

private object DocxWriter {
    fun canReuse(parts: Map<String, ByteArray>): Boolean = runCatching {
        String(parts.getValue(documentPath(parts)), Charsets.UTF_8).let { "<w:body>" in it && "</w:body>" in it }
    }.getOrDefault(false)

    fun blank(): LinkedHashMap<String, ByteArray> {
        val ns = """xmlns:w="$W" xmlns:r="$R""""
        fun heading(n: Int, halfPoints: Int) = """<w:style w:type="paragraph" w:styleId="Heading$n"><w:name w:val="heading $n"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:uiPriority w:val="9"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="240" w:after="80"/><w:outlineLvl w:val="${n - 1}"/></w:pPr><w:rPr><w:b/><w:sz w:val="$halfPoints"/></w:rPr></w:style>"""
        val m = linkedMapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
            "word/_rels/document.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$REL_STYLES" Target="styles.xml"/><Relationship Id="rId2" Type="$REL_NUMBERING" Target="numbering.xml"/></Relationships>""",
            "word/document.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document $ns><w:body><w:p/><w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>""",
            "word/styles.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles $ns><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Times New Roman" w:hAnsi="Times New Roman" w:eastAsia="Times New Roman" w:cs="Times New Roman"/><w:sz w:val="24"/><w:szCs w:val="24"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="259" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style><w:style w:type="character" w:default="1" w:styleId="DefaultParagraphFont"><w:name w:val="Default Paragraph Font"/><w:uiPriority w:val="1"/><w:semiHidden/></w:style><w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/><w:basedOn w:val="Normal"/><w:qFormat/><w:pPr><w:ind w:left="720"/><w:contextualSpacing/></w:pPr></w:style>${heading(1, 36)}${heading(2, 30)}${heading(3, 26)}${heading(4, 24)}${heading(5, 22)}${heading(6, 22)}<w:style w:type="character" w:styleId="Hyperlink"><w:name w:val="Hyperlink"/><w:basedOn w:val="DefaultParagraphFont"/><w:rPr><w:color w:val="0563C1"/><w:u w:val="single"/></w:rPr></w:style></w:styles>""",
            "word/numbering.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:numbering $ns></w:numbering>""",
        )
        return LinkedHashMap(m.mapValues { it.value.toByteArray() })
    }

    fun write(doc: OfficeDoc, source: LinkedHashMap<String, ByteArray>): ByteArray {
        val parts = LinkedHashMap(source)
        val docPath = documentPath(parts)
        val dir = docPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val styleLists = docxStyleLists(parts, dir)
        val relsPath = relsPath(docPath)
        var rels = parts[relsPath]?.let { String(it, Charsets.UTF_8) }
            ?: """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"></Relationships>"""
        var types = String(parts.getValue("[Content_Types].xml"), Charsets.UTF_8)
        val usedIds = Regex("""Id="([^"]+)"""").findAll(rels).map { it.groupValues[1] }.toMutableSet()
        fun newRel(type: String, target: String, external: Boolean): String {
            val id = generateSequence(1) { it + 1 }.map { "rIdT$it" }.first { it !in usedIds }
            usedIds += id
            rels = rels.replace("</Relationships>", """<Relationship Id="$id" Type="$type" Target="${OfficeFormats.esc(target)}"${if (external) " TargetMode=\"External\"" else ""}/></Relationships>""")
            return id
        }
        fun ensurePart(name: String, relType: String, contentType: String, empty: String) {
            if (dir + name in parts) return
            parts[dir + name] = empty.toByteArray()
            if (!rels.contains("Target=\"$name\"")) newRel(relType, name, false)
            if (!types.contains("PartName=\"/$dir$name\"")) types = types.replace("</Types>", """<Override PartName="/$dir$name" ContentType="$contentType"/></Types>""")
        }
        val ns = """xmlns:w="$W" xmlns:r="$R""""
        ensurePart("styles.xml", REL_STYLES, "application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles $ns></w:styles>""")
        if (doc.paras.any { it.list != null })
            ensurePart("numbering.xml", REL_NUMBERING, "application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:numbering $ns></w:numbering>""")

        // Styles: the headings and Hyperlink this document already has, found by name; any missing are added.
        var styles = String(parts.getValue(dir + "styles.xml"), Charsets.UTF_8)
        val styleRoot = OfficeFormats.parse(styles.toByteArray()).documentElement
        val byName = styleRoot.kids().filter { it.localName == "style" }.associate { (it.kid(W, "name")?.attr(W, "val") ?: "").lowercase() to (it.attr(W, "styleId") ?: "") }
        val ids = byName.values.toMutableSet()
        val normal = styleRoot.kids().firstOrNull { it.localName == "style" && it.attr(W, "type") == "paragraph" && it.attr(W, "default").let { d -> d == "1" || d == "true" } }?.attr(W, "styleId")
        val allParas = ArrayList<DocPara>().also { all ->
            fun add(paras: List<DocPara>) {
                paras.forEach { p -> all += p; p.table?.rows?.forEach { row -> row.cells.forEach { cell -> add(cell.paras) } } }
            }
            add(doc.paras)
            OfficeFormats.notesOf(doc).forEach { add(it.paras) }
        }
        fun styleId(name: String, preferred: String, definition: (String) -> String): String = byName[name] ?: run {
            val id = if (preferred in ids) "Tetra$preferred" else preferred
            ids += id
            styles = styles.replace("</w:styles>", definition(id) + "</w:styles>")
            id
        }
        val headingIds = (1..6).filter { n -> allParas.any { it.heading == n } }.associateWith { n ->
            styleId("heading $n", "Heading$n") { id -> docxHeadingStyle(n, id, normal) }
        }
        // Title, Subtitle, Quote: added when a paragraph took one the document had not got.
        allParas.mapNotNull { it.keep?.style }.toSet().filter { it in DOCX_BUILTIN && it !in ids }.forEach { id ->
            ids += id
            styles = styles.replace("</w:styles>", DOCX_BUILTIN.getValue(id).let { d -> if (normal != null) d.replace("NORMAL", normal) else d.replace(Regex("""<w:(basedOn|next) w:val="NORMAL"/>"""), "") } + "</w:styles>")
        }
        val hyperlinkId = if (allParas.any { p -> p.runs.any { it.link != null && !it.link.startsWith("#") } })
            styleId("hyperlink", "Hyperlink") { id -> """<w:style w:type="character" w:styleId="$id"><w:name w:val="Hyperlink"/><w:rPr><w:color w:val="0563C1"/><w:u w:val="single"/></w:rPr></w:style>""" } else null
        parts[dir + "styles.xml"] = styles.toByteArray()

        // Lists: two new list definitions (bullets, numbers) and one numbering instance per list, so each numbered
        // list counts from its own start, as it did in the editor.
        val numIds = HashMap<Int, String>() // paragraph index → numId
        if (doc.paras.any { it.list != null }) {
            var numbering = String(parts.getValue(dir + "numbering.xml"), Charsets.UTF_8)
            val nroot = OfficeFormats.parse(numbering.toByteArray()).documentElement
            var nextAbstract = (nroot.kids().filter { it.localName == "abstractNum" }.mapNotNull { it.attr(W, "abstractNumId")?.toIntOrNull() }.maxOrNull() ?: 0) + 1
            var nextNum = (nroot.kids().filter { it.localName == "num" }.mapNotNull { it.attr(W, "numId")?.toIntOrNull() }.maxOrNull() ?: 0) + 1
            val bulletChars = listOf("•", "◦", "▪")
            fun abstractNum(id: Int, ordered: Boolean) = buildString {
                append("""<w:abstractNum w:abstractNumId="$id"><w:multiLevelType w:val="hybridMultilevel"/>""")
                for (l in 0..8) {
                    val indent = 720 * (l + 1)
                    val (fmt, text) = doc.numbering?.getOrNull(l) ?: ("decimal" to "%${l + 1}.")
                    val hanging = if (text.count { it == '%' } > 1) 360 + 144 * l else 360 // "1.1.1." needs more room than "1." (the pages: OfficePages.paragraph)
                    if (ordered) append("""<w:lvl w:ilvl="$l"><w:start w:val="1"/><w:numFmt w:val="$fmt"/><w:lvlText w:val="$text"/><w:lvlJc w:val="left"/><w:pPr><w:ind w:left="$indent" w:hanging="$hanging"/></w:pPr></w:lvl>""")
                    else append("""<w:lvl w:ilvl="$l"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="${bulletChars[l % 3]}"/><w:lvlJc w:val="left"/><w:pPr><w:ind w:left="$indent" w:hanging="360"/></w:pPr></w:lvl>""")
                }
                append("</w:abstractNum>")
            }
            val bulletAbstract = nextAbstract++
            val orderedAbstract = nextAbstract++
            val nums = StringBuilder()
            fun num(abstract: Int, start: Int?): String {
                val id = nextNum++
                nums.append("""<w:num w:numId="$id"><w:abstractNumId w:val="$abstract"/>""")
                if (start != null && start != 1) nums.append("""<w:lvlOverride w:ilvl="0"><w:startOverride w:val="$start"/></w:lvlOverride>""")
                nums.append("</w:num>")
                return id.toString()
            }
            val bulletNum = num(bulletAbstract, null)
            var i = 0
            while (i < doc.paras.size) {
                if (doc.paras[i].list == null) { i++; continue }
                var end = i
                while (end < doc.paras.size && doc.paras[end].list != null) end++
                val start = doc.paras.subList(i, end).firstOrNull { it.list!!.ordered && it.list.level == 0 }?.list?.start
                // Each item keeps the file's own list when it is still of that list's kind at its level (Word then numbers
                // it as before); any other item gets Writer's own list.
                fun own(k: Int): String? = doc.paras[k].keep?.list?.takeIf { src ->
                    val l = doc.paras[k].list!!
                    src.substringAfter('|').getOrNull(l.level)?.let { c -> (c == 'n') == l.ordered } == true
                }?.substringBefore('|')
                val orderedNum = if ((i until end).any { doc.paras[it].list!!.ordered && own(it) == null }) num(orderedAbstract, start) else null
                for (k in i until end) numIds[k] = own(k) ?: if (doc.paras[k].list!!.ordered) orderedNum!! else bulletNum
                i = end
            }
            val abstracts = abstractNum(bulletAbstract, false) + abstractNum(orderedAbstract, true)
            // Schema order: every abstractNum before the first num.
            val firstNum = Regex("""<w:num[\s>]""").find(numbering)?.range?.first
            numbering = if (firstNum != null) numbering.substring(0, firstNum) + abstracts + numbering.substring(firstNum) else numbering.replace("</w:numbering>", "$abstracts</w:numbering>")
            val cleanup = numbering.indexOf("<w:numIdMacAtCleanup").takeIf { it >= 0 } ?: numbering.lastIndexOf("</w:numbering>")
            numbering = numbering.substring(0, cleanup) + nums + numbering.substring(cleanup)
            parts[dir + "numbering.xml"] = numbering.toByteArray()
        }

        // Pictures added in Writer: each a new part in word/media, a relationship, and an inline drawing.
        var docPrId = 9000
        var changeId = 90000 // a tracked change's id (Word's own are renumbered on its next save)
        var bookmarkId = 80000
        /**
         * A group's pictures that carry their own bytes (one a PDF grouped: not parts of this file yet): each one's bytes a
         * part of the file and its r:embed to it, in the order the group has them. Words' groups' pictures are the file's own.
         */
        fun groupMedia(raw: String, pieces: List<GroupPart>): String {
            val pics = pieces.filter { it.img.raw?.contains("r:embed=") == true }
            if (pics.none { it.img.bytes != null }) return raw
            val embeds = Regex("""r:embed="[^"]*"""").findAll(raw).toList()
            val out = StringBuilder(); var at = 0
            embeds.zip(pics).forEach { (m, piece) ->
                val bytes = piece.img.bytes ?: return@forEach
                val ext = piece.img.extension
                val name = generateSequence(1) { it + 1 }.map { "${dir}media/tetra-$it.$ext" }.first { it !in parts }
                parts[name] = bytes
                if (!Regex("""Extension="$ext"""", RegexOption.IGNORE_CASE).containsMatchIn(types))
                    types = types.replace("</Types>", """<Default Extension="$ext" ContentType="image/${if (ext == "jpg") "jpeg" else ext}"/></Types>""")
                out.append(raw, at, m.range.first).append("""r:embed="${newRel(REL_IMAGE, name.removePrefix(dir), false)}"""")
                at = m.range.last + 1
            }
            return out.append(raw.substring(at)).toString()
        }
        // a picture whose drawing came from another file, its bytes with it (a PDF's group taken apart): the same, alone
        picture = { img -> (img.raw?.let { r -> img.group?.let { groupMedia(r, it) } ?: if (img.part == null && img.bytes != null) groupMedia(r, listOf(GroupPart(0f, 0f, 0f, 0f, img))) else r } ?: img.chart?.let { ch ->
                // A new chart (Insert › Chart, or one whose data Writer changed): its own part, as Word keeps one.
                val name = generateSequence(1) { it + 1 }.map { "${dir}charts/chart$it.xml" }.first { it !in parts }
                parts[name] = ChartWriter.drawingml(ch, workbook = true).toByteArray()
                types = types.replace("</Types>", """<Override PartName="/$name" ContentType="application/vnd.openxmlformats-officedocument.drawingml.chart+xml"/></Types>""")
                // Its numbers in an embedded workbook, as Word keeps them: Word's Edit Data opens it.
                val book = generateSequence(1) { it + 1 }.map { "${dir}embeddings/Microsoft_Excel_Worksheet_tetra$it.xlsx" }.first { it !in parts }
                parts[book] = ChartWriter.workbook(ch)
                parts[name.substringBeforeLast('/') + "/_rels/" + name.substringAfterLast('/') + ".rels"] = ("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/package" Target="../embeddings/${book.substringAfterLast('/')}"/></Relationships>""").toByteArray()
                if (!Regex("""Extension="xlsx"""", RegexOption.IGNORE_CASE).containsMatchIn(types))
                    types = types.replace("</Types>", """<Default Extension="xlsx" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"/></Types>""")
                val rel = newRel(REL_CHART, name.removePrefix(dir), false)
                val cx = (img.widthPt * 12700).toLong(); val cy = (img.heightPt * 12700).toLong(); val id = docPrId++
                """<w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"><wp:extent cx="$cx" cy="$cy"/><wp:effectExtent l="0" t="0" r="0" b="0"/><wp:docPr id="$id" name="Chart $id"/><wp:cNvGraphicFramePr/><a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/chart"><c:chart xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" xmlns:r="$R" r:id="$rel"/></a:graphicData></a:graphic></wp:inline></w:drawing>"""
            } ?: run {
                val bytes = img.bytes ?: return@run ""
                val name = generateSequence(1) { it + 1 }.map { "${dir}media/tetra-$it.${img.extension}" }.first { it !in parts }
                parts[name] = bytes
                if (!Regex("""Extension="${img.extension}"""", RegexOption.IGNORE_CASE).containsMatchIn(types))
                    types = types.replace("</Types>", """<Default Extension="${img.extension}" ContentType="image/${if (img.extension == "jpg") "jpeg" else img.extension}"/></Types>""")
                val rel = newRel(REL_IMAGE, name.removePrefix(dir), false)
                val cx = (img.widthPt * 12700).toLong(); val cy = (img.heightPt * 12700).toLong(); val id = docPrId++
                // an online video as Word's Insert › Online Video makes one: its picture, a click going to the address, the player kept in webVideoPr
                val web = img.film?.takeIf { it.startsWith("http") }
                val click = web?.let { """<a:hlinkClick xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="$R" r:id="${newRel(REL_HYPERLINK, it, true)}"/>""" }
                // a film from the phone: kept in word/media, PowerPoint's media relationship to it, named in Tetra's ext (Word shows the picture)
                val own = img.film?.takeIf { web == null && java.io.File(it).isFile }?.let { f -> runCatching {
                    val ext = f.substringAfterLast('.', "mp4").lowercase()
                    val film = generateSequence(1) { it + 1 }.map { "${dir}media/tetra-film-$it.$ext" }.first { it !in parts }
                    parts[film] = java.io.File(f).readBytes()
                    if (!Regex("""Extension="$ext"""", RegexOption.IGNORE_CASE).containsMatchIn(types))
                        types = types.replace("</Types>", """<Default Extension="$ext" ContentType="video/${if (ext == "mov") "quicktime" else ext}"/></Types>""")
                    """<a:extLst><a:ext uri="$TETRA_VIDEO"><tv:film xmlns:tv="$TETRA_VIDEO" xmlns:r="$R" r:embed="${newRel(REL_MEDIA, film.removePrefix(dir), false)}"/></a:ext></a:extLst>"""
                }.getOrNull() }
                // a signature (Insert › Signature) keeps its name, so it opens again as one (and Word shows it so)
                val named = if (web != null || own != null) "Video $id" else if (img.key.startsWith(SIGNATURE_KEY)) "Signature $id" else "Picture $id"
                """<w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"><wp:extent cx="$cx" cy="$cy"/>""" +
                    (if (click == null) """<wp:docPr id="$id" name="$named"/>""" else """<wp:docPr id="$id" name="$named">$click</wp:docPr>""") +
                    """<a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:nvPicPr>""" +
                    (if (web != null) """<pic:cNvPr id="$id" name="$named">$click${PictureXml.webVideo(web, img.widthPt, img.heightPt)}</pic:cNvPr>""" else if (own != null) """<pic:cNvPr id="$id" name="$named">$own</pic:cNvPr>"""
                        else """<pic:cNvPr id="$id" name="$named"/>""") + """<pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="$rel" xmlns:r="$R"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing>"""
            }).let { x -> img.wrap?.let { w -> PictureXml.docx(x, w).let { d -> img.onPage?.takeIf { w != PictureWrap.InLine }?.let { PictureXml.docxOnPage(d, it) } ?: d } } ?: x }
        }

        // The body: the new paragraphs, then the original page setup (the last sectPr).
        val xml = String(parts.getValue(docPath), Charsets.UTF_8)
        val bodyStart = xml.indexOf("<w:body>") + "<w:body>".length
        val bodyEnd = xml.lastIndexOf("</w:body>")
        val sect = xml.lastIndexOf("<w:sectPr", bodyEnd).takeIf { it >= bodyStart }?.let { xml.substring(it, bodyEnd) } ?: ""
        val linkIds = HashMap<String, String>()
        val body = StringBuilder()
        /** A table: its rows and cells, their properties as the file had them, the cells' paragraphs written as the body's. */
        fun table(t: DocTable, paragraph: (DocPara) -> Unit) {
            body.append("<w:tbl>").append(t.props).append(t.grid)
            for (row in t.rows) {
                body.append("<w:tr>").append(row.props)
                for (cell in row.cells) {
                    body.append("<w:tc>").append(cell.props)
                    cell.paras.forEach(paragraph)
                    // Word wants a paragraph last in a cell (after a table in it, too).
                    if (cell.paras.lastOrNull()?.let { it.table != null } != false) body.append("<w:p/>")
                    body.append("</w:tc>")
                }
                body.append("</w:tr>")
            }
            body.append("</w:tbl>")
        }
        val footList = ArrayList<DocNote>(); val endList = ArrayList<DocNote>()
        // Comments: their ranges around the letters they are on, then their part.
        val live = doc.liveComments.associateBy { it.id }
        val openComments = LinkedHashSet<String>()
        val closedComments = HashSet<String>() // started once: never again
        val nextFirst = java.util.IdentityHashMap<DocPara, DocPara?>().also { m ->
            var prev: DocPara? = null
            fun walk(ps: List<DocPara>) { ps.forEach { q -> if (q.table != null) q.table.rows.forEach { r -> r.cells.forEach { walk(it.paras) } } else { prev?.let { m[it] = q }; prev = q } } }
            walk(doc.paras)
        }
        /** Before letters carrying [now]: the comments they leave end (and are referenced), those they enter start. */
        fun commentMarks(now: List<String>) {
            for (id in openComments.filter { it !in now }) {
                body.append("""<w:commentRangeEnd w:id="$id"/><w:r><w:commentReference w:id="$id"/></w:r>""")
                openComments -= id
            }
            // One stretch each: a comment that ended is not started again (its letters further on lose it).
            for (id in now) if (id !in openComments && id in live && closedComments.add(id)) { body.append("""<w:commentRangeStart w:id="$id"/>"""); openComments += id }
        }
        fun paragraph(index: Int?, p: DocPara) {
            p.table?.let { t -> table(t) { paragraph(null, it) }; return }
            // A drop cap: its letters first, in a framed paragraph of their own as Word writes one.
            p.keep?.parts?.firstOrNull { it.first == "dropCap" }?.let { part ->
                val drop = DropCap.of(p.keep)!!
                val frame = part.second.substringAfter('|')
                val runs = OfficeFormats.tidy(p.runs)
                val letters = ArrayList<DocRun>(); val rest = ArrayList<DocRun>()
                var left = drop.length
                for (r in runs) when {
                    left <= 0 || r.image != null -> rest += r
                    r.text.length <= left -> { letters += r; left -= r.text.length }
                    else -> { letters += r.copy(text = r.text.take(left)); rest += r.copy(text = r.text.drop(left)); left = 0 }
                }
                if (letters.isNotEmpty()) {
                    // Word's sizes: the letter's capitals as tall as the lines it drops through, a line of that height.
                    val base = letters.first().sizePt ?: doc.basePt
                    val line = base * 1.2f * 1.08f
                    val size = ((drop.lines - 1) * line + base * 0.66f) / 0.66f
                    val style = (if (p.heading > 0) headingIds.getValue(p.heading) else p.keep.style)?.let { """<w:pStyle w:val="${OfficeFormats.esc(it)}"/>""" } ?: ""
                    body.append("<w:p><w:pPr>$style<w:keepNext/>$frame<w:spacing w:before=\"0\" w:after=\"0\" w:line=\"${Math.round(drop.lines * line * 20)}\" w:lineRule=\"exact\"/><w:textAlignment w:val=\"baseline\"/></w:pPr>")
                    for (r in letters) body.append(run(r.copy(sizePt = Math.round(size * 2) / 2f), null, doc.basePt).replaceFirst("<w:sz ", "<w:position w:val=\"-${Math.round(size * 0.2f)}\"/><w:sz "))
                    body.append("</w:p>")
                }
                return paragraph(index, p.copy(runs = rest, keep = p.keep.copy(parts = p.keep.parts - part).takeIf { !it.isEmpty }))
            }
            body.append("<w:p>")
            // Writer's own properties and the ones carried from the file, in the order Word requires.
            val props = ArrayList(p.keep?.parts.orEmpty().filter { it.first !in NOT_PPR && !(it.first == "jc" && p.align != DocAlign.Start) && !(it.first == "numPr" && p.list != null) }) // the file's own left and "no number", unless changed here
            (if (p.heading > 0) headingIds.getValue(p.heading) else p.keep?.style)?.let { props += "pStyle" to """<w:pStyle w:val="${OfficeFormats.esc(it)}"/>""" }
            // A list item in a table's cell keeps the file's own list (Writer's own lists are numbered for the body's).
            p.list?.let { l -> (index?.let { numIds[it] } ?: p.keep?.list?.substringBefore('|'))?.let { props += "numPr" to """<w:numPr><w:ilvl w:val="${l.level}"/><w:numId w:val="$it"/></w:numPr>""" } }
            // A list taken off a paragraph whose style numbers it: said so, or its style would number it still.
                ?: p.keep?.style?.takeIf { it in styleLists }?.let { props += "numPr" to """<w:numPr><w:numId w:val="0"/></w:numPr>""" }
            when (p.align) {
                DocAlign.Center -> props += "jc" to """<w:jc w:val="center"/>"""
                DocAlign.End -> props += "jc" to """<w:jc w:val="right"/>"""
                DocAlign.Justify -> props += "jc" to """<w:jc w:val="both"/>"""
                DocAlign.Start -> Unit
            }
            if (props.isNotEmpty()) {
                body.append("<w:pPr>")
                props.sortedBy { (name, _) -> PPR_ORDER.indexOf(name).let { if (it < 0) PPR_ORDER.indexOf("rPr") else it } }.forEach { body.append(it.second) }
                body.append("</w:pPr>")
            }
            val marks = p.keep?.parts?.firstOrNull { it.first == BOOKMARKS }?.second?.split(' ')?.map { it to bookmarkId++ }.orEmpty()
            var labelMarks = p.keep?.parts?.firstOrNull { it.first == LABEL_BOOKMARKS }?.second?.split(' ')?.map { it to bookmarkId++ }.orEmpty()
            (marks + labelMarks).forEach { (n, id) -> body.append("""<w:bookmarkStart w:id="$id" w:name="${OfficeFormats.esc(n)}"/>""") }
            fun endLabels() { labelMarks.forEach { (_, id) -> body.append("""<w:bookmarkEnd w:id="$id"/>""") }; labelMarks = emptyList() }
            if (p.keep?.parts?.any { it.first == COLUMN_BREAK } == true) body.append("""<w:r><w:br w:type="column"/></w:r>""")
            var k = 0
            val runs = OfficeFormats.tidy(p.runs)
            // A citation: its letters inside its field, as Word writes one (begin, instruction, separate, text, end).
            var cite: String? = null
            fun citeTo(c: String?) {
                if (c == cite) return
                if (cite != null) body.append("""<w:r><w:fldChar w:fldCharType="end"/></w:r>""")
                // The caption's number ends its "label and number" bookmarks.
                if (cite?.drop(1)?.trimStart()?.startsWith("SEQ ", true) == true) endLabels()
                if (c != null) body.append("""<w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> ${OfficeFormats.esc(c.removePrefix(FIELD_CITE.toString()))} </w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r>""")
                cite = c
            }
            // Tracked changes: the letters inside Word's w:ins / w:del.
            var change: DocChange? = null
            fun changeTo(c: DocChange?) {
                if (c == change) return
                if (change != null) body.append(if (change!!.deleted) "</w:del>" else "</w:ins>")
                if (c != null) body.append("""<w:${if (c.deleted) "del" else "ins"} w:id="${changeId++}" w:author="${OfficeFormats.esc(c.author)}"${c.date?.let { " w:date=\"$it\"" } ?: ""}>""")
                change = c
            }
            // A form's content control: its letters inside Word's w:sdt (outside a link, the link inside it).
            var control: String? = null
            fun controlTo(c: String?) {
                if (c == control) return
                changeTo(null); citeTo(null)
                if (control != null) body.append("</w:sdtContent></w:sdt>")
                if (c != null) body.append("<w:sdt>$c<w:sdtContent>")
                control = c
            }
            while (k < runs.size) {
                val link = runs[k].link
                var end = k
                while (end < runs.size && runs[end].link == link && runs[end].control == runs[k].control) end++
                controlTo(runs[k].control)
                // a link inside the document (#name: a bookmark, as the contents' entries) goes by its anchor, not a relationship
                if (link != null) { changeTo(null); citeTo(null); body.append(if (link.startsWith("#")) """<w:hyperlink w:anchor="${OfficeFormats.esc(link.substring(1))}" w:history="1">"""
                    else """<w:hyperlink r:id="${linkIds.getOrPut(link) { newRel(REL_HYPERLINK, link, true) }}">""") }
                for (r in runs.subList(k, end)) {
                    if (r.comments != openComments.toList() || link == null && r.cite != cite) changeTo(null)
                    commentMarks(r.comments); if (link == null) citeTo(r.cite); changeTo(r.change)
                    // A note's reference: Word's, numbered by Word; the note itself into footnotes.xml / endnotes.xml.
                    r.note?.let { n ->
                        val list = if (n.end) endList else footList
                        list += n
                        body.append("""<w:r><w:rPr><w:vertAlign w:val="superscript"/></w:rPr><w:${if (n.end) "endnote" else "footnote"}Reference w:id="${list.size}"/></w:r>""")
                    } ?: body.append(run(r, if (link != null && !link.startsWith("#")) hyperlinkId else null, doc.basePt))
                }
                changeTo(null)
                if (link != null) body.append("</w:hyperlink>")
                k = end
            }
            citeTo(null); controlTo(null)
            // A comment ends here unless the next paragraph goes on in it.
            commentMarks(nextFirst[p]?.runs?.firstOrNull()?.comments.orEmpty().filter { it in openComments })
            endLabels()
            marks.forEach { (_, id) -> body.append("""<w:bookmarkEnd w:id="$id"/>""") }
            body.append("</w:p>")
        }
        var index = 0
        while (index < doc.paras.size) {
            // A table of contents (or another building block) kept from the file: as it was, while its words are.
            val raw = doc.paras[index].keep?.parts?.firstOrNull { it.first == SDT_BLOCK }?.second
            if (raw != null) {
                var end = index + 1
                while (end < doc.paras.size && doc.paras[end].keep?.parts?.any { it.first == SDT_PART } == true) end++
                if (raw.substringBefore('|') == OfficeFormats.blockText(doc.paras.subList(index, end))) { body.append(raw.substringAfter('|')); index = end; continue }
            }
            paragraph(index, doc.paras[index]); index++
        }
        if (openComments.isNotEmpty()) { body.append("<w:p>"); commentMarks(emptyList()); body.append("</w:p>") }
        writeComments(parts, dir, rels, live.values.toList(), ::ensurePart)
        // Footnotes and endnotes: their paragraphs written as the body's, each note's number (Word's) first.
        val docRoot = Regex("""<w:document\b[^>]*>""").find(xml)?.value.orEmpty()
        for ((end, list) in listOf(false to footList, true to endList)) {
            val tag = if (end) "endnote" else "footnote"
            val name = "${tag}s.xml"
            val target = Regex("""<Relationship\b[^>]*Type="${if (end) REL_ENDNOTES else REL_FOOTNOTES}"[^>]*>""").find(rels)?.value?.let { Regex("""Target="([^"]+)"""").find(it)?.groupValues?.get(1) }
            if (target == null && list.isEmpty()) continue
            if (target == null) ensurePart(name, if (end) REL_ENDNOTES else REL_FOOTNOTES, "application/vnd.openxmlformats-officedocument.wordprocessingml.$name".removeSuffix(".xml") + "+xml", "")
            val path = resolve(dir, target ?: name)
            val old = parts[path]?.let { String(it, Charsets.UTF_8) }.orEmpty()
            val root = Regex("""<w:${tag}s\b[^>]*>""").find(old)?.value ?: docRoot.replace("<w:document", "<w:${tag}s").ifEmpty { """<w:${tag}s xmlns:w="$W" xmlns:r="$R">""" }
            val separators = Regex("""<w:$tag\b[^>]*w:type="(separator|continuationSeparator|continuationNotice)"[\s\S]*?</w:$tag>""").findAll(old).joinToString("") { it.value }
                .ifEmpty { """<w:$tag w:type="separator" w:id="-1"><w:p><w:pPr><w:spacing w:after="0" w:line="240" w:lineRule="auto"/></w:pPr><w:r><w:separator/></w:r></w:p></w:$tag><w:$tag w:type="continuationSeparator" w:id="0"><w:p><w:pPr><w:spacing w:after="0" w:line="240" w:lineRule="auto"/></w:pPr><w:r><w:continuationSeparator/></w:r></w:p></w:$tag>""" }
            val notesXml = StringBuilder()
            list.forEachIndexed { i, n ->
                val start = body.length
                n.paras.forEach { paragraph(null, it) }
                val text = body.substring(start).let { t -> body.setLength(start); t }
                // The note's own number, first in its first paragraph (after its properties).
                val mark = """<w:r><w:rPr><w:vertAlign w:val="superscript"/></w:rPr><w:${tag}Ref/></w:r><w:r><w:t xml:space="preserve"> </w:t></w:r>"""
                val at = Regex("""^<w:p>(<w:pPr>[\s\S]*?</w:pPr>)?""").find(text)?.range?.last?.plus(1) ?: 0
                notesXml.append("""<w:$tag w:id="${i + 1}">""").append(text.substring(0, at)).append(mark).append(text.substring(at)).append("</w:$tag>")
            }
            parts[path] = ("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" + root + separators + notesXml + "</w:${tag}s>").toByteArray()
        }
        var out = xml.substring(0, bodyStart) + body + sect + xml.substring(bodyEnd)
        if (linkIds.isNotEmpty() && !Regex("""<w:document[^>]*xmlns:r=""").containsMatchIn(out)) out = out.replaceFirst("<w:document", "<w:document xmlns:r=\"$R\"")
        parts[docPath] = out.toByteArray()
        parts[relsPath] = rels.toByteArray()
        parts["[Content_Types].xml"] = types.toByteArray()
        WordSources.write(parts, doc.sources, doc.citeStyle)
        NoteFormats.docx(parts, doc)
        return OfficeFormats.zip(parts)
    }

    /**
     * comments.xml: the comments still on some letters — each from the file as it was, a new one written out. What the
     * file says about a comment gone (Word's replies/resolved in commentsExtended, commentsIds) is taken out with it.
     */
    private fun writeComments(parts: LinkedHashMap<String, ByteArray>, dir: String, rels: String, live: List<DocComment>,
        ensurePart: (String, String, String, String) -> Unit) {
        val target = Regex("""<Relationship\b[^>]*Type="$REL_COMMENTS"[^>]*>""").find(rels)?.value?.let { Regex("""Target="([^"]+)"""").find(it)?.groupValues?.get(1) }
        if (target == null && live.isEmpty()) return
        if (target == null) ensurePart("comments.xml", REL_COMMENTS, "application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml", "")
        val path = resolve(dir, target ?: "comments.xml")
        val old = parts[path]?.let { String(it, Charsets.UTF_8) }.orEmpty()
        val root = Regex("""<w:comments\b[^>]*>""").find(old)?.value ?: """<w:comments xmlns:w="$W">"""
        val gone = Regex("""<w:comment\b[^>]*w:id="([^"]+)"[\s\S]*?</w:comment>""").findAll(old).filter { m -> live.none { it.id == m.groupValues[1] } }
            .flatMap { m -> Regex("""w14:paraId="([0-9A-Fa-f]+)"""").findAll(m.value).map { it.groupValues[1] } }.toSet()
        // Each comment's last paragraph has an id (w14:paraId): replies and resolved ones are said by it in commentsExtended.
        val used = Regex("""w14:paraId="([0-9A-Fa-f]+)"""").findAll(old).map { it.groupValues[1].uppercase() }.toMutableSet()
        fun freshPara() = generateSequence(0x1A2B0001) { it + 1 }.map { "%08X".format(it) }.first { it !in used }.also { used += it }
        val paraOf = HashMap<String, String>()
        val xmlOf = live.map { c ->
            var x = c.raw ?: ("""<w:comment w:id="${c.id}" w:author="${OfficeFormats.esc(c.author)}"${c.date?.let { " w:date=\"$it\"" } ?: ""}${c.initials?.let { " w:initials=\"${OfficeFormats.esc(it)}\"" } ?: ""}>""" +
                c.text.split('\n').joinToString("") { line -> """<w:p><w:r><w:t xml:space="preserve">${OfficeFormats.esc(line)}</w:t></w:r></w:p>""" } + "</w:comment>")
            val last = Regex("""<w:p\b[^>]*>""").findAll(x).lastOrNull()
            val para = last?.value?.let { Regex("""w14:paraId="([0-9A-Fa-f]+)"""").find(it)?.groupValues?.get(1) }
            if (para != null) paraOf[c.id] = para
            else if (last != null && (c.parent != null || c.done || live.any { it.parent == c.id })) {
                val id = freshPara(); paraOf[c.id] = id
                x = x.replaceRange(last.range, last.value.replaceFirst("<w:p", """<w:p w14:paraId="$id""""))
            }
            x
        }
        val rootWithW14 = if (paraOf.isNotEmpty() && "xmlns:w14=" !in root) root.replaceFirst("<w:comments", """<w:comments xmlns:w14="$W14"""") else root
        parts[path] = ("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" + rootWithW14 + xmlOf.joinToString("") + "</w:comments>").toByteArray()
        // commentsExtended: which is a reply to which, which are resolved.
        val exTarget = Regex("""<Relationship\b[^>]*Type="$REL_COMMENTS_EX"[^>]*>""").find(rels)?.value?.let { Regex("""Target="([^"]+)"""").find(it)?.groupValues?.get(1) }
        if (exTarget != null || live.any { it.parent != null || it.done }) {
            if (exTarget == null) ensurePart("commentsExtended.xml", REL_COMMENTS_EX, "application/vnd.openxmlformats-officedocument.wordprocessingml.commentsExtended+xml", "")
            val exPath = resolve(dir, exTarget ?: "commentsExtended.xml")
            val exOld = parts[exPath]?.let { String(it, Charsets.UTF_8) }.orEmpty()
            val exRoot = Regex("""<w15:commentsEx\b[^>]*>""").find(exOld)?.value
                ?: """<w15:commentsEx xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006" xmlns:w15="$W15" mc:Ignorable="w15">"""
            parts[exPath] = ("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" + exRoot + live.mapNotNull { c -> paraOf[c.id]?.let { p ->
                """<w15:commentEx w15:paraId="$p"${c.parent?.let { paraOf[it] }?.let { " w15:paraIdParent=\"$it\"" } ?: ""} w15:done="${if (c.done) 1 else 0}"/>""" } }.joinToString("") + "</w15:commentsEx>").toByteArray()
        }
        if (gone.isNotEmpty()) for (name in listOf("commentsIds.xml", "commentsExtensible.xml")) { // commentsExtended: written anew above
            val p = parts.keys.firstOrNull { it.endsWith("/$name") || it == name } ?: continue
            parts[p] = String(parts.getValue(p), Charsets.UTF_8).replace(Regex("""<w1[56]\w*:comment\w*\b[^>]*?(?:paraId|durableId)="([0-9A-Fa-f]+)"[^>]*?/>""")) { m ->
                if (gone.any { it in m.value }) "" else m.value }.toByteArray()
        }
    }

    /** A picture's XML: the file's own for one from the file; a new one, written in full, for one added in Writer. */
    var picture: (DocImage) -> String = { it.raw ?: "" }

    private fun run(r: DocRun, style: String?, basePt: Float): String = buildString {
        if (r.image?.math != null) { append(r.image.raw.orEmpty()); return@buildString } // an equation stands beside the runs, not in one
        // Writer's own settings and the ones carried from the file, in the order Word requires.
        val props = ArrayList<Pair<String, String>>()
        r.font?.let { raw -> RAW_ELEMENT.findAll(raw).forEach { m -> val name = m.groupValues[1].removePrefix("w:"); if (name != "u" || r.underline) props += name to m.value } }
        style?.let { props += "rStyle" to """<w:rStyle w:val="$it"/>""" }
        if (r.bold) props += "b" to "<w:b/>" else if (r.unbold) props += "b" to """<w:b w:val="0"/>"""
        if (r.italic) props += "i" to "<w:i/>" else if (r.unitalic) props += "i" to """<w:i w:val="0"/>"""
        if (r.strike) props += "strike" to "<w:strike/>"
        r.color?.let { props += "color" to """<w:color w:val="${OfficeFormats.hex(it)}"/>""" }
        r.sizePt?.let { props += "sz" to """<w:sz w:val="${(it * 2).roundToInt()}"/>""" }
        r.highlight?.let { props += "highlight" to """<w:highlight w:val="${nearestHighlight(it)}"/>""" }
        if (r.underline && props.none { it.first == "u" }) props += "u" to """<w:u w:val="single"/>"""
        if (r.script != 0) props += "vertAlign" to """<w:vertAlign w:val="${if (r.script > 0) "superscript" else "subscript"}"/>"""
        val rPr = props.sortedBy { if (it.first == "rPrChange") RPR_ORDER.size + 1 else RPR_ORDER.indexOf(it.first).let { i -> if (i < 0) RPR_ORDER.size else i } }.joinToString("") { it.second } // extensions, then a tracked change, last
        append("<w:r>")
        if (rPr.isNotEmpty()) append("<w:rPr>").append(rPr).append("</w:rPr>")
        if (r.image != null) { append(picture(r.image)); append("</w:r>"); return@buildString }
        Regex("""[\t$LINE_BREAK\u00AD]|[^\t$LINE_BREAK\u00AD]+""").findAll(r.text).forEach { m ->
            when (m.value) {
                "\t" -> append("<w:tab/>")
                "\u00AD" -> append("<w:softHyphen/>")
                LINE_BREAK.toString() -> append("<w:br/>")
                else -> (if (r.change?.deleted == true) "delText" else "t").let { t -> append("""<w:$t xml:space="preserve">""").append(OfficeFormats.esc(m.value)).append("</w:$t>") }
            }
        }
        append("</w:r>")
    }

    fun nearestHighlight(rgb: Int): String = WORD_HIGHLIGHTS.minBy { (_, c) ->
        val dr = (c shr 16 and 255) - (rgb shr 16 and 255); val dg = (c shr 8 and 255) - (rgb shr 8 and 255); val db = (c and 255) - (rgb and 255)
        dr * dr + dg * dg + db * db
    }.key
}

// ============================================================================================ OpenDocument (.odt)

internal const val OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
internal const val DC = "http://purl.org/dc/elements/1.1/"
internal const val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
internal const val STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
internal const val FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
private const val XLINK = "http://www.w3.org/1999/xlink"
internal const val TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
internal const val SVG = "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"
internal const val DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"

/** LibreOffice's name for a style ("Heading_20_1") as people see it ("Heading 1"). */
internal fun odfDisplayName(name: String) = Regex("""_([0-9a-fA-F]{2})_""").replace(name) { it.groupValues[1].toInt(16).toChar().toString() }

private class OdtStyle(val name: String, val family: String, val parent: String?, val listStyle: String?, val masterPage: String?, val text: Element?, val paragraph: Element?, val automatic: Boolean,
    val graphic: Element? = null)

private class OdtReader(val parts: Map<String, ByteArray>) {
    val notes = linkedSetOf<String>()
    val styles = HashMap<String, OdtStyle>() // "family/name"
    val listStyles = HashMap<String, Element>()
    var basePt = 12f
    val listCounters = HashMap<String, IntArray>() // list style → the count at each level, for a list that continues
    val openComments = LinkedHashSet<String>()
    val comments = LinkedHashMap<String, DocComment>()
    var ended = emptySet<String>() // the annotations with an end: a comment on some letters (one without is on a point)

    fun read(): OfficeDoc {
        val content = OfficeFormats.parse(parts["content.xml"] ?: throw OfficeReadError(com.kalotrapezis.drive.R.string.office_error_not_odt, "Not an OpenDocument text.")).documentElement
        parts["styles.xml"]?.let { OfficeFormats.parse(it).documentElement }?.let { root ->
            root.kid(OFFICE, "styles")?.let { collect(it, false) }
            root.kid(OFFICE, "styles")?.kids()?.firstOrNull { it.localName == "default-style" && it.attr(STYLE, "family") == "paragraph" }
                ?.kid(STYLE, "text-properties")?.attr(FO, "font-size")?.let { size(it, 12f) }?.let { basePt = it }
            styles["paragraph/Standard"]?.text?.attr(FO, "font-size")?.let { size(it, basePt) }?.let { basePt = it }
        }
        content.kid(OFFICE, "automatic-styles")?.let { collect(it, true) }
        val text = content.kid(OFFICE, "body")?.kid(OFFICE, "text") ?: throw OfficeReadError(com.kalotrapezis.drive.R.string.office_error_not_odt, "Not an OpenDocument text.")
        ended = text.getElementsByTagNameNS(OFFICE, "annotation-end").let { l -> (0 until l.length).mapNotNull { (l.item(it) as Element).attr(OFFICE, "name") }.toSet() }
        val out = ArrayList<DocPara>()
        block(text, out, null, -1)
        return OfficeDoc(out.ifEmpty { listOf(DocPara(emptyList())) }, notes, basePt, comments.values.toList(), sources.values.toList())
    }

    /** Reading a header or footer: its page number and count become [PAGE_FIELD] / [PAGES_FIELD]. */
    var fieldMarks = false
    private val sources = LinkedHashMap<String, DocSource>() // the bibliography marks' sources, by tag

    fun readMaster(): Pair<Map<String, List<DocPara>>, Map<String, List<DocPara>>> {
        val root = OfficeFormats.parse(parts["styles.xml"] ?: return emptyMap<String, List<DocPara>>() to emptyMap()).documentElement
        root.kid(OFFICE, "styles")?.let { collect(it, false) }
        root.kid(OFFICE, "automatic-styles")?.let { collect(it, true) }
        val master = root.kid(OFFICE, "master-styles")?.kids()?.let { ms -> ms.firstOrNull { it.attr(STYLE, "name") == "Standard" } ?: ms.firstOrNull() }
            ?: return emptyMap<String, List<DocPara>>() to emptyMap()
        fun read(tag: String) = master.kid(STYLE, tag)?.takeIf { it.attr(STYLE, "display") != "false" }?.let { ArrayList<DocPara>().also { out -> block(it, out, null, -1) } }
        // LibreOffice's left pages are Word's even ones.
        fun both(kind: String) = listOfNotNull(read(kind)?.let { "default" to it }, read("$kind-first")?.let { "first" to it }, read("$kind-left")?.let { "even" to it }).toMap()
        return both("header") to both("footer")
    }

    fun collect(parent: Element, automatic: Boolean) {
        for (e in parent.kids()) when (e.localName) {
            "style" -> {
                val family = e.attr(STYLE, "family") ?: continue
                val name = e.attr(STYLE, "name") ?: continue
                styles["$family/$name"] = OdtStyle(name, family, e.attr(STYLE, "parent-style-name"), e.attr(STYLE, "list-style-name"),
                    e.attr(STYLE, "master-page-name"), e.kid(STYLE, "text-properties"), e.kid(STYLE, "paragraph-properties"), automatic, e.kid(STYLE, "graphic-properties"))
            }
            "list-style" -> e.attr(STYLE, "name")?.let { listStyles[it] = e }
        }
    }

    fun size(v: String, base: Float): Float? = when {
        v.endsWith("pt") -> v.removeSuffix("pt").toFloatOrNull()
        v.endsWith("%") -> v.removeSuffix("%").toFloatOrNull()?.let { base * it / 100 }
        else -> null
    }

    /** A value that changes nothing ("none", "0cm", "normal"…), so leaving it out loses nothing. */
    fun neutral(v: String) = v.isEmpty() || v in setOf("none", "normal", "auto", "false", "transparent", "100%", "0%", "0% 100%", "start", "page", "0")
        || Regex("""^-?0+(\.0+)?(cm|mm|in|pt|pc|px)?$""").matches(v)

    /** Applies a style's text properties to [look]; what Writer cannot keep is named. */
    fun textLook(props: Element?, look: DocRun, inLink: Boolean = false, fonts: MutableList<Pair<String, String>>? = null): DocRun {
        props ?: return look
        var l = look
        val a = props.attributes
        for (i in 0 until a.length) {
            val n = a.item(i)
            val ns = n.namespaceURI ?: continue
            val local = n.localName; val v = n.nodeValue.trim()
            // Carried as the file has it, letter by letter (fonts asked 2026-09-27; the rest 2026-09-28, from the
            // user's test suite): small caps, letter spacing, language, a double underline… — never what Writer writes.
            fun carry() {
                if (local in ODF_WRITTEN) return
                if (fonts != null) fonts += "text@" to " ${n.nodeName}=\"${OfficeFormats.esc(n.nodeValue)}\"" else l = l.copy(font = withFont(l.font, n.nodeName, n.nodeValue))
            }
            if (!(ns == FO || ns == STYLE)) continue // another program's own (revision ids…)
            if (local.endsWith("-asian") || local.endsWith("-complex")) { carry(); continue }
            when (local) {
                "font-weight" -> (v == "bold" || (v.toIntOrNull() ?: 400) >= 600).let { b -> l = l.copy(bold = b, unbold = !b) }
                "font-style" -> (v == "italic" || v == "oblique").let { i -> l = l.copy(italic = i, unitalic = !i) }
                "text-underline-style" -> if (!inLink) l = l.copy(underline = v != "none")
                "text-line-through-style" -> l = l.copy(strike = v != "none")
                "font-size" -> size(v, basePt)?.let { l = l.copy(sizePt = it) } ?: run { notes += "other text effects" }
                "color" -> if (!inLink) l = l.copy(color = OfficeFormats.parseHex(v))
                "background-color" -> l = l.copy(highlight = if (v == "transparent") null else OfficeFormats.parseHex(v))
                "font-name", "font-family" -> carry()
                // "super 58%", "sub", "33% 58%", "-33% 58%", "0% 100%"
                "text-position" -> v.substringBefore(' ').let { p -> l = l.copy(script = when { p == "super" -> 1; p == "sub" -> -1; else -> p.removeSuffix("%").toFloatOrNull()?.let { Math.signum(it).toInt() } ?: 0 }) }
                "display" -> carry() // hidden text (none): kept with its letters, as in Word files
                else -> carry()
            }
        }
        return l
    }

    /** A run's ODF font attributes, one more set (a later style's setting replaces an earlier one's). */
    fun withFont(font: String?, name: String, value: String): String {
        val attrs = LinkedHashMap<String, String>()
        font?.let { f -> Regex(""" (\S+)="([^"]*)"""").findAll(f).forEach { attrs[it.groupValues[1]] = it.groupValues[2] } }
        attrs[name] = OfficeFormats.esc(value)
        return attrs.entries.joinToString("") { " ${it.key}=\"${it.value}\"" }
    }

    /** The look a named character style (and the styles it is based on) gives. */
    fun charStyle(name: String, look: DocRun, inLink: Boolean): DocRun {
        val chain = generateSequence(styles["text/$name"]) { s -> s.parent?.let { styles["text/$it"] } }.take(10).toList().asReversed()
        if (chain.isEmpty()) return look
        if (inLink && chain.any { !it.automatic && odfDisplayName(it.name).lowercase().let { n -> "internet link" in n || "visited" in n } }) return look
        return chain.fold(look) { l, s -> textLook(s.text, l, inLink) }
    }

    /** A paragraph's alignment; its other properties (spacing, indents, borders, breaks, tab stops…) are carried, raw. */
    fun paragraphLook(p: OdtStyle?, parts: MutableList<Pair<String, String>>): DocAlign {
        val props = p?.paragraph ?: return DocAlign.Start
        var align = DocAlign.Start
        val a = props.attributes
        for (i in 0 until a.length) {
            val n = a.item(i)
            if (n.nodeName.startsWith("xmlns")) continue
            if (n.namespaceURI == FO && n.localName == "text-align") {
                align = when (n.nodeValue.trim()) { "center" -> DocAlign.Center; "end", "right" -> DocAlign.End; "justify" -> DocAlign.Justify; else -> DocAlign.Start }
            } else if (!(n.namespaceURI == STYLE && n.localName == "justify-single-word")) parts += "@" to " ${n.nodeName}=\"${OfficeFormats.esc(n.nodeValue)}\""
        }
        props.kids().forEach { parts += "child" to OfficeFormats.xml(it) }
        return align
    }

    /** Plain body text: a paragraph needs no style of its own to look as it did. */
    fun plainParagraph(name: String?, isHeading: Boolean): Boolean {
        name ?: return true
        val n = odfDisplayName(name).lowercase()
        if (n in setOf("standard", "default", "default paragraph style", "text body", "body text", "list paragraph", "list contents", "list", "normal", "heading")) return true
        return isHeading && Regex("""heading ?\d""").matches(n)
    }

    fun block(parent: Element, out: MutableList<DocPara>, list: String?, level: Int) {
        for (e in parent.kids()) when {
            e.namespaceURI == TEXT -> when (e.localName) {
                "p", "h" -> paragraph(e, out, null)
                "list" -> list(e, out, null, 0, null)
                "sequence-decls", "variable-decls", "user-field-decls", "dde-connection-decls", "soft-page-break", "bookmark", "bookmark-start", "bookmark-end" -> Unit
                "tracked-changes" -> notes += "tracked changes"
                // A section (LibreOffice's columns in part of the text, a protected or named part): each of its paragraphs
                // carries its opening tag (an outer one's first), written round them again (OdtSections).
                "section" -> {
                    val start = out.size
                    block(e, out, list, level)
                    if (out.size == start) notes += "sections"
                    val tag = "<text:section${attrs(e)}>"
                    for (k in start until out.size) out[k] = OdtSections.within(out[k], tag)
                }
                "table-of-content", "alphabetical-index", "illustration-index", "table-index", "object-index", "user-index", "bibliography" ->
                    { notes += "fields (page numbers, dates, contents)"; e.kid(TEXT, "index-body")?.let { block(it, out, list, level) } }
                "numbered-paragraph" -> { notes += "other list formats"; block(e, out, list, level) }
                else -> notes += "other content"
            }
            e.namespaceURI == TABLE && e.localName == "table" -> out += tablePara(table(e))
            e.namespaceURI == OFFICE && e.localName == "forms" -> Unit
            e.localName == "frame" || e.namespaceURI?.contains("drawing") == true -> notes += "shapes and text boxes"
            else -> notes += "other content"
        }
    }

    /** An element's attributes as the file wrote them (" table:style-name=\"A1\"…"), less [drop]. */
    private fun attrs(e: Element, vararg drop: String): String {
        val a = e.attributes
        return (0 until a.length).map { a.item(it) }.filter { !it.nodeName.startsWith("xmlns") && it.nodeName !in drop }
            .joinToString("") { " ${it.nodeName}=\"${OfficeFormats.esc(it.nodeValue)}\"" }
    }

    /**
     * A table (2026-09-28): kept in the file's own words, as a Word table is — the table's, rows' and cells' attributes
     * (their styles, spans) and its columns raw; each cell's paragraphs read as the body's. A merged-away cell
     * (covered-table-cell) stays a cell, marked; a header row is marked; repeated rows and cells are written out.
     */
    fun table(e: Element): DocTable {
        val rows = ArrayList<DocRow>()
        val grid = StringBuilder()
        fun rowsIn(parent: Element, header: Boolean) {
            for (c in parent.kids()) when (c.localName) {
                "table-column", "table-columns", "table-header-columns", "table-column-group" -> grid.append(OfficeFormats.xml(c))
                "table-header-rows" -> rowsIn(c, true)
                "table-rows", "table-row-group" -> rowsIn(c, header)
                "table-row" -> {
                    val cells = ArrayList<DocCell>()
                    for (cell in c.kids()) {
                        val covered = cell.localName == "covered-table-cell"
                        if (!covered && cell.localName != "table-cell") continue
                        val paras = ArrayList<DocPara>().also { block(cell, it, null, -1) }
                        val n = cell.attr(TABLE, "number-columns-repeated")?.toIntOrNull()?.coerceIn(1, 64) ?: 1
                        repeat(n) { cells += DocCell((if (covered) ODT_COVERED else "") + attrs(cell, "table:number-columns-repeated"), paras) }
                    }
                    val n = c.attr(TABLE, "number-rows-repeated")?.toIntOrNull()?.coerceIn(1, 1000) ?: 1
                    repeat(n) { rows += DocRow((if (header) ODT_HEADER else "") + attrs(c, "table:number-rows-repeated"), cells) }
                }
                else -> if (c.namespaceURI == TABLE) notes += "other content"
            }
        }
        rowsIn(e, false)
        return DocTable(attrs(e), grid.toString(), rows)
    }

    fun list(e: Element, out: MutableList<DocPara>, inherited: String?, level: Int, parentCounter: IntArray?) {
        val styleName = e.attr(TEXT, "style-name") ?: inherited
        val counter = if (level == 0) {
            val cont = e.attr(TEXT, "continue-numbering") == "true" || e.attr(TEXT, "continue-list") != null
            if (cont) listCounters.getOrPut(styleName ?: "") { IntArray(10) } else IntArray(10).also { listCounters[styleName ?: ""] = it }
        } else parentCounter!!
        for (d in level + 1 until 10) counter[d] = 0
        for (item in e.kids()) {
            if (item.localName == "list-header") notes += "other list formats"
            if (item.localName != "list-item" && item.localName != "list-header") continue
            var numbered = false
            for (c in item.kids()) when (c.localName) {
                "p", "h" -> {
                    val style = styleName ?: c.attr(TEXT, "style-name")?.let { styles["paragraph/$it"]?.listStyle }
                    val (ordered, start) = levelStyle(style, level)
                    val value = if (!numbered) {
                        item.attr(TEXT, "start-value")?.toIntOrNull()?.let { counter[level] = it } ?: run { counter[level] = if (counter[level] == 0) start else counter[level] + 1 }
                        for (d in level + 1 until 10) counter[d] = 0
                        numbered = true
                        counter[level]
                    } else null
                    val source = style?.let { st -> st + "|" + (0..9).joinToString("") { l -> if (levelStyle(st, l).first) "n" else "b" } }
                    paragraph(c, out, if (value == null) null else DocList(ordered, level.coerceAtMost(8), value.takeIf { ordered }), source)
                    if (value == null) { notes += "paragraphs inside list items" }
                }
                "list" -> list(c, out, styleName, level + 1, counter)
                "soft-page-break" -> Unit
                else -> notes += "other content"
            }
        }
    }

    /** Whether a list level is numbered, and where it starts. */
    fun levelStyle(name: String?, level: Int): Pair<Boolean, Int> {
        val style = name?.let { listStyles[it] } ?: return false to 1
        val l = style.kids().firstOrNull { it.attr(TEXT, "level")?.toIntOrNull() == level + 1 } ?: return false to 1
        return when (l.localName) {
            "list-level-style-number" -> {
                val format = l.attr(STYLE, "num-format") ?: "1"
                if (format.isEmpty()) { notes += "other list formats"; false to 1 }
                else { if (format != "1") notes += "letter or roman numbering"; true to (l.attr(TEXT, "start-value")?.toIntOrNull() ?: 1) }
            }
            else -> false to 1
        }
    }

    /** A picture, shape or text box in a paragraph, kept as the file has it. */
    fun frame(e: Element): DocImage {
        val href = e.getElementsByTagNameNS("*", "image").let { if (it.length > 0) (it.item(0) as Element).getAttributeNS(XLINK, "href").takeIf { h -> h.isNotEmpty() && !h.contains(':') } else null }
        fun length(v: String?): Float? = v?.let { Regex("""^([\d.]+)(cm|mm|in|pt|pc|px)$""").find(it.trim()) }?.let { m ->
            val n = m.groupValues[1].toFloat()
            when (m.groupValues[2]) { "cm" -> n * 28.3465f; "mm" -> n * 2.83465f; "in" -> n * 72; "pc" -> n * 12; "px" -> n * 0.75f; else -> n }
        }
        // A text box: a frame's, or a shape's own words (LibreOffice's shapes hold paragraphs; Writer's text boxes are
        // rectangles with words) — a shape only when it has some.
        val box = (e.getElementsByTagNameNS("urn:oasis:names:tc:opendocument:xmlns:drawing:1.0", "text-box").item(0) as Element?)?.let { t -> aside(notes, openComments) { ArrayList<DocPara>().also { block(t, it, null, 0) } } }
            ?: e.takeIf { it.localName == "custom-shape" }?.let { t -> aside(notes, openComments) { ArrayList<DocPara>().also { block(t, it, null, 0) } } }?.takeIf { ps -> ps.any { it.text.isNotBlank() } }
        val objectXml = (e.getElementsByTagNameNS("*", "object").item(0) as Element?)?.getAttributeNS(XLINK, "href")?.removePrefix("./")?.let { parts["$it/content.xml"] }
        val chart = objectXml?.let { OfficeCharts.readOdf(it) }
        // A formula (LibreOffice Math's object, MathML): drawn as Word's equations are; the file's object kept as it is.
        if (chart == null && e.localName == "frame") objectXml?.let { OfficeMath.fromMathML(it) }?.let { m -> return OfficeMath.image("f${imageCount++}", m, OfficeFormats.xml(e), basePt) }
        // Its graphic style, parents first so the frame's own settings win: as attributes, for where it goes and how it looks.
        val look = generateSequence(e.getAttributeNS(DRAW, "style-name").takeIf { it.isNotEmpty() }?.let { styles["graphic/$it"] }) { s -> s.parent?.let { styles["graphic/$it"] } }
            .take(10).toList().asReversed().mapNotNull { it.graphic }.joinToString("") { g -> (0 until g.attributes.length).joinToString("") { i -> g.attributes.item(i).let { a -> " ${a.nodeName}=\"${a.nodeValue}\"" } } }
            .takeIf { it.isNotEmpty() }
        // A group: each piece read as one, in the group's box (LibreOffice's pieces stand where its anchor puts them).
        if (e.localName == "g") {
            val raw = OfficeFormats.xml(e)
            val gb = Arrange.odtGroupBox(raw) ?: floatArrayOf(0f, 0f, 144f, 96f)
            val parts = e.kids().filter { it.namespaceURI == DRAW }.mapNotNull { k ->
                val pb = Arrange.odtGroupBox(OfficeFormats.xml(k)) ?: return@mapNotNull null
                GroupPart(pb[0] - gb[0], pb[1] - gb[1], pb[2], pb[3], frame(k))
            }
            return DocImage("f${imageCount++}", null, gb[2], gb[3], raw, look = look, group = parts.ifEmpty { null })
        }
        // a film or sound embedded as an object (LibreOffice keeps Word's package as it was): "its part#its file's name"
        val film = (e.getElementsByTagNameNS("*", "object-ole").item(0) as Element?)?.getAttributeNS(XLINK, "href")?.removePrefix("./")?.let { o ->
            parts[o]?.let { OfficeVideo.ole(it, nameOnly = true) }?.first?.takeIf(OfficeVideo::media)?.let { "$o#$it" } }
        return DocImage("f${imageCount++}", href, length(e.getAttributeNS(SVG, "width")) ?: 144f, length(e.getAttributeNS(SVG, "height")) ?: 96f, OfficeFormats.xml(e), box = box, chart = chart, look = look, film = film)
    }
    var imageCount = 0

    fun paragraph(p: Element, out: MutableList<DocPara>, list: DocList?, listSource: String? = null) {
        val isHeading = p.localName == "h"
        val heading = if (isHeading) (p.attr(TEXT, "outline-level")?.toIntOrNull() ?: 1).also { if (it > 6) notes += "headings below level 6" }.coerceIn(1, 6) else 0
        val styleName = p.attr(TEXT, "style-name")
        val style = styleName?.let { styles["paragraph/$it"] }
        var base = DocRun("")
        var align = DocAlign.Start
        val parts = ArrayList<Pair<String, String>>()
        var keepStyle: String? = null
        if (style != null && style.automatic) {
            align = paragraphLook(style, parts)
            base = textLook(style.text, base)
            // The paragraph's own text settings stay on the paragraph too: they make an empty line as tall as it was.
            style.text?.attributes?.let { at -> for (k in 0 until at.length) at.item(k).let { n ->
                if (!n.nodeName.startsWith("xmlns") && !n.nodeName.startsWith("officeooo:")) parts += "text@" to " ${n.nodeName}=\"${OfficeFormats.esc(n.nodeValue)}\""
            } }
            if (!isHeading || !plainParagraph(style.parent, true)) keepStyle = style.parent // the exact one: Standard and Text body space differently
            style.masterPage?.let { if (it.isNotEmpty()) parts += "master" to it }
        } else if (!isHeading || !plainParagraph(styleName, true)) keepStyle = styleName
        // its bookmarks (what links inside the document go to, as the contents' entries): kept on it
        (listOf("bookmark", "bookmark-start").flatMap { n -> p.getElementsByTagNameNS(TEXT, n).let { l -> (0 until l.length).mapNotNull { (l.item(it) as Element).attr(TEXT, "name") } } })
            .takeIf { it.isNotEmpty() }?.let { parts += BOOKMARKS to it.joinToString(" ") }
        val keep = DocKeep(keepStyle, parts, listSource.takeIf { list != null }).takeIf { !it.isEmpty }
        if (isHeading && p.attr(TEXT, "is-list-header") == null && p.parentNode.let { it is Element && it.localName == "list-item" }) notes += "numbered headings"
        val runs = CommentedRuns(openComments)
        val currentList = list
        fun content(parent: Element, look: DocRun, inLink: Boolean) {
            var n: Node? = parent.firstChild
            while (n != null) {
                if (n.nodeType == Node.TEXT_NODE || n.nodeType == Node.CDATA_SECTION_NODE) {
                    // ODF collapses runs of white space like HTML; <text:s/> carries the spaces that count.
                    val t = n.nodeValue.replace(Regex("""[ \t\r\n]+"""), " ")
                    if (t.isNotEmpty()) runs += look.copy(text = t)
                } else if (n is Element) {
                    val e = n
                    if (e.namespaceURI == TEXT) when (e.localName) {
                        "span" -> content(e, e.attr(TEXT, "style-name")?.let { charStyle(it, look, inLink) } ?: look, inLink)
                        "a" -> {
                            val href = e.attr(XLINK, "href")
                            // one inside the document (#name: to a bookmark, as the contents' entries) is kept as it is
                            if (href == null) { notes += "links inside the document"; content(e, look, inLink) }
                            else content(e, look.copy(link = href), true)
                        }
                        // A citation: the source it holds, its letters tagged with it.
                        "bibliography-mark" -> Citations.odtSource { e.attr(TEXT, it) }?.let { src ->
                            sources.putIfAbsent(src.tag, src)
                            runs += look.copy(text = e.textContent, cite = src.tag)
                        } ?: run { runs += look.copy(text = e.textContent) }
                        "s" -> runs += look.copy(text = " ".repeat(e.attr(TEXT, "c")?.toIntOrNull()?.coerceIn(1, 1000) ?: 1))
                        "tab" -> runs += look.copy(text = "\t")
                        "line-break" -> runs += look.copy(text = LINE_BREAK.toString())
                        "soft-page-break", "bookmark", "bookmark-start", "bookmark-end", "reference-mark", "reference-mark-start", "reference-mark-end", "alphabetical-index-mark", "toc-mark" -> Unit
                        // A footnote or endnote: its body's paragraphs; its number is worked out, not read.
                        "note" -> e.kid(TEXT, "note-body")?.let { b ->
                            val paras = ArrayList<DocPara>().also { block(b, it, null, 0) }
                            runs += look.copy(text = "1", note = DocNote(e.attr(TEXT, "note-class") == "endnote", paras.ifEmpty { listOf(DocPara(emptyList())) }), script = 1)
                        } ?: run { notes += "footnotes" }
                        "change", "change-start", "change-end" -> notes += "tracked changes"
                        "ruby" -> { notes += "other text effects"; e.kid(TEXT, "ruby-base")?.let { content(it, look, inLink) } }
                        "meta", "meta-field" -> content(e, look, inLink)
                        "page-number", "page-count" -> if (fieldMarks) runs += look.copy(text = (if (e.localName == "page-number") PAGE_FIELD else PAGES_FIELD).toString())
                            else { notes += "fields (page numbers, dates, contents)"; content(e, look, inLink) }
                        else -> { notes += "fields (page numbers, dates, contents)"; content(e, look, inLink) }
                    } else if (e.namespaceURI == OFFICE && e.localName == "annotation") {
                        val name = e.attr(OFFICE, "name")
                        if (name == null || name !in ended) notes += "comments" // on a point, not some letters: not kept yet
                        else {
                            comments[name] = DocComment(name, e.kid(DC, "creator")?.textContent ?: "", e.kid(DC, "date")?.textContent,
                                e.kids().filter { it.localName == "p" }.joinToString("\n") { it.textContent }, e.kids().joinToString("") { OfficeFormats.xml(it) },
                                // A reply, and whether it is resolved (LibreOffice 7's).
                                parent = e.getAttributeNS(LOEXT, "parent-name").takeIf { it.isNotEmpty() }, done = e.getAttributeNS(LOEXT, "resolved") == "true")
                            openComments += name
                        }
                    } else if (e.namespaceURI == OFFICE && e.localName == "annotation-end") e.attr(OFFICE, "name")?.let { openComments -= it }
                    // LibreOffice's content control (a form's field): its letters carry it, as Word's sdtPr (Forms.fromOdt).
                    else if (e.namespaceURI == LOEXT && e.localName == "content-control") {
                        val attrs = (0 until e.attributes.length).map { e.attributes.item(it) }.filter { it.namespaceURI == LOEXT }.associate { it.localName to it.nodeValue }
                        val items = e.kids().filter { it.namespaceURI == LOEXT && it.localName == "list-item" }.map { (it.getAttributeNS(LOEXT, "display-text").ifEmpty { it.getAttributeNS(LOEXT, "value") }) to it.getAttributeNS(LOEXT, "value") }
                        val pr = Forms.fromOdt(attrs, items).takeIf { look.control == null }
                        if (pr == null) notes += "content controls"
                        content(e, if (pr != null) look.copy(control = pr) else look, inLink)
                    } else if (e.namespaceURI == LOEXT && e.localName == "list-item") Unit
                    else if ((e.localName == "custom-shape" || e.localName == "frame") && Watermark.isOne(e.getAttributeNS(e.namespaceURI, "name"))) Unit // a watermark: the pages draw it
                    else if (e.localName == "frame" || e.namespaceURI?.contains("drawing") == true) runs += look.copy(text = OBJECT_MARK.toString(), image = frame(e))
                    else notes += "other content"
                }
                n = n.nextSibling
            }
        }
        content(p, base, false)
        // Leading white space in an ODF paragraph is not shown; a trailing space is kept (LibreOffice keeps it).
        if (runs.isNotEmpty() && p.firstChild?.nodeType == Node.TEXT_NODE) runs[0] = runs[0].copy(text = runs[0].text.trimStart(' '))
        // An empty paragraph is as tall as what it holds (an empty 16 pt span makes a 16 pt line): that stays.
        val finalKeep = if (runs.all { it.text.isEmpty() } && p.kids().isNotEmpty())
            (keep ?: DocKeep()).let { it.copy(parts = it.parts + ("empty" to p.kids().joinToString("") { k -> OfficeFormats.xml(k) })) } else keep
        out += DocPara(OfficeFormats.tidy(runs), heading, currentList, align, finalKeep)
    }
}

private object OdtWriter {
    val NS = """xmlns:office="$OFFICE" xmlns:style="$STYLE" xmlns:text="$TEXT" xmlns:table="$TABLE" xmlns:fo="$FO" xmlns:xlink="$XLINK" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0""""

    fun canReuse(parts: Map<String, ByteArray>): Boolean = runCatching {
        String(parts.getValue("content.xml"), Charsets.UTF_8).let { Regex("""<office:text[\s>/]""").containsMatchIn(it) && "xmlns:text=" in it && "xmlns:style=" in it && "xmlns:fo=" in it }
    }.getOrDefault(false)

    fun blank(): LinkedHashMap<String, ByteArray> {
        fun heading(n: Int, size: String) = """<style:style style:name="Heading_20_$n" style:display-name="Heading $n" style:family="paragraph" style:parent-style-name="Heading" style:next-style-name="Text_20_body" style:default-outline-level="$n" style:class="text"><style:text-properties fo:font-size="$size" fo:font-weight="bold"/></style:style>"""
        val m = linkedMapOf(
            "mimetype" to "application/vnd.oasis.opendocument.text",
            "META-INF/manifest.xml" to """<?xml version="1.0" encoding="UTF-8"?><manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.3"><manifest:file-entry manifest:full-path="/" manifest:version="1.3" manifest:media-type="application/vnd.oasis.opendocument.text"/><manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/><manifest:file-entry manifest:full-path="styles.xml" manifest:media-type="text/xml"/></manifest:manifest>""",
            "content.xml" to """<?xml version="1.0" encoding="UTF-8"?><office:document-content $NS office:version="1.3"><office:automatic-styles/><office:body><office:text><text:p text:style-name="Text_20_body"/></office:text></office:body></office:document-content>""",
            "styles.xml" to """<?xml version="1.0" encoding="UTF-8"?><office:document-styles $NS office:version="1.3"><office:styles><style:default-style style:family="paragraph"><style:paragraph-properties style:writing-mode="page"/><style:text-properties fo:font-size="12pt" fo:font-family="'Times New Roman'" style:font-family-generic="roman" fo:language="none"/></style:default-style><style:style style:name="Standard" style:family="paragraph" style:class="text"/><style:style style:name="Text_20_body" style:display-name="Text body" style:family="paragraph" style:parent-style-name="Standard" style:class="text"><style:paragraph-properties fo:margin-top="0cm" fo:margin-bottom="0.247cm" fo:line-height="115%"/></style:style><style:style style:name="Heading" style:family="paragraph" style:parent-style-name="Standard" style:next-style-name="Text_20_body" style:class="text"><style:paragraph-properties fo:margin-top="0.423cm" fo:margin-bottom="0.212cm" fo:keep-with-next="always"/></style:style>${heading(1, "130%")}${heading(2, "115%")}${heading(3, "101%")}${heading(4, "95%")}${heading(5, "85%")}${heading(6, "85%")}</office:styles><office:automatic-styles><style:page-layout style:name="pm1"><style:page-layout-properties fo:page-width="21.001cm" fo:page-height="29.7cm" fo:margin-top="2cm" fo:margin-bottom="2cm" fo:margin-left="2cm" fo:margin-right="2cm"/></style:page-layout></office:automatic-styles><office:master-styles><style:master-page style:name="Standard" style:page-layout-name="pm1"/></office:master-styles></office:document-styles>""",
        )
        return LinkedHashMap(m.mapValues { it.value.toByteArray() })
    }

    fun write(doc: OfficeDoc, source: LinkedHashMap<String, ByteArray>): ByteArray {
        val parts = LinkedHashMap(source)
        var stylesXml = parts["styles.xml"]?.let { String(it, Charsets.UTF_8) }
        val named = stylesXml?.let { s -> Regex("""style:name="([^"]+)"""").findAll(s).map { it.groupValues[1] }.toSet() } ?: emptySet()
        // Plain paragraphs keep the style this document uses for its text (Text body in LibreOffice, else Standard).
        val body = if ("Text_20_body" in named) "Text_20_body" else "Standard"
        // A heading style the document does not have is added, so its headings still look like headings.
        if (stylesXml != null && "</office:styles>" in stylesXml) {
            val add = (1..6).filter { n -> doc.paras.any { it.heading == n } && "Heading_20_$n" !in named }.joinToString("") { n -> odtHeadingStyle(n, body) }
            val builtin = (doc.paras + OfficeFormats.notesOf(doc).flatMap { it.paras }).mapNotNull { it.keep?.style }.toSet().filter { it in ODT_BUILTIN && it !in named }.joinToString("") { ODT_BUILTIN.getValue(it) }
            if (add.isNotEmpty() || builtin.isNotEmpty()) { stylesXml = stylesXml.replace("</office:styles>", "$add$builtin</office:styles>"); parts["styles.xml"] = stylesXml.toByteArray() }
            // Citations: LibreOffice shows a mark as its identifier between the document's brackets — round ones, so
            // "Smith & Jones, 2019" reads as APA's (Smith & Jones, 2019).
            if (doc.paras.any { p -> p.runs.any { it.cite != null && !isField(it.cite) } }) {
                val xml = stylesXml!!
                val conf = Regex("""<text:bibliography-configuration\b[^>]*>""").find(xml)
                stylesXml = if (conf != null) xml.replaceRange(conf.range, conf.value.replace(Regex("""\stext:(prefix|suffix)="[^"]*""""), "").replaceFirst("<text:bibliography-configuration", """<text:bibliography-configuration text:prefix="(" text:suffix=")""""))
                    else { val at = xml.indexOf("<text:linenumbering-configuration").takeIf { it >= 0 } ?: xml.indexOf("</office:styles>")
                        xml.substring(0, at) + """<text:bibliography-configuration text:prefix="(" text:suffix=")" text:numbered-entries="false" text:sort-by-position="true"/>""" + xml.substring(at) }
                parts["styles.xml"] = stylesXml.toByteArray()
            }
        }

        // The file's own automatic styles: what the new text still refers to stays (a picture's position and wrap, a
        // text box's paragraphs, a list); Writer's own get names the file does not use.
        val content = String(parts.getValue("content.xml"), Charsets.UTF_8)
        val ownAuto = runCatching { OfficeFormats.parse(parts.getValue("content.xml")).documentElement.kid(OFFICE, "automatic-styles")?.kids().orEmpty() }.getOrDefault(emptyList())
            .map { (it.attr(STYLE, "name") ?: "") to OfficeFormats.xml(it) }
        val usedNames = ownAuto.map { it.first }.toMutableSet()
        fun fresh(prefix: String) = generateSequence(1) { it + 1 }.map { "$prefix$it" }.first { it !in usedNames }.also { usedNames += it }
        val auto = StringBuilder()
        val paraStyles = HashMap<Triple<String, DocAlign, List<Pair<String, String>>>, String>()
        val textStyles = HashMap<DocRun, String>()
        val listStyles = HashMap<String, String>()
        /** The paragraph's own style: its parent, its alignment, and what it carries from the file (spacing, indents…). */
        fun paraStyle(parent: String, align: DocAlign, parts: List<Pair<String, String>>): String {
            if (align == DocAlign.Start && parts.isEmpty()) return parent
            return paraStyles.getOrPut(Triple(parent, align, parts)) {
                val name = fresh("TP")
                val master = parts.firstOrNull { it.first == "master" }?.let { " style:master-page-name=\"${OfficeFormats.esc(it.second)}\"" } ?: ""
                val attrs = parts.filter { it.first == "@" }.joinToString("") { it.second }
                val children = parts.filter { it.first == "child" }.joinToString("") { it.second }
                val text = parts.filter { it.first == "text@" }.joinToString("") { it.second }.let { if (it.isEmpty()) "" else "<style:text-properties$it/>" }
                val a = when (align) {
                    DocAlign.Start -> ""
                    DocAlign.Center -> " fo:text-align=\"center\""; DocAlign.End -> " fo:text-align=\"end\""
                    DocAlign.Justify -> " fo:text-align=\"justify\" style:justify-single-word=\"false\""
                }
                val props = if (attrs.isEmpty() && a.isEmpty() && children.isEmpty()) "" else if (children.isEmpty()) "<style:paragraph-properties$attrs$a/>" else "<style:paragraph-properties$attrs$a>$children</style:paragraph-properties>"
                auto.append("""<style:style style:name="$name" style:family="paragraph" style:parent-style-name="$parent"$master>$props$text</style:style>""")
                name
            }
        }
        fun textStyle(r: DocRun): String? {
            val look = r.copy(text = "", link = null, image = null, comments = emptyList(), control = null)
            if (look == DocRun("")) return null
            return textStyles.getOrPut(look) {
                val name = fresh("TT")
                val p = buildString {
                    if (r.bold) append(""" fo:font-weight="bold" style:font-weight-asian="bold" style:font-weight-complex="bold"""")
                    else if (r.unbold) append(""" fo:font-weight="normal" style:font-weight-asian="normal" style:font-weight-complex="normal"""")
                    if (r.italic) append(""" fo:font-style="italic" style:font-style-asian="italic" style:font-style-complex="italic"""")
                    else if (r.unitalic) append(""" fo:font-style="normal" style:font-style-asian="normal" style:font-style-complex="normal"""")
                    if (r.underline) append(""" style:text-underline-style="solid" style:text-underline-width="auto" style:text-underline-color="font-color"""")
                    if (r.strike) append(""" style:text-line-through-style="solid" style:text-line-through-type="single"""")
                    r.sizePt?.let { val s = fmt(it); append(""" fo:font-size="${s}pt" style:font-size-asian="${s}pt" style:font-size-complex="${s}pt"""") }
                    r.color?.let { append(""" fo:color="#${OfficeFormats.hex(it).lowercase()}"""") }
                    r.highlight?.let { append(""" fo:background-color="#${OfficeFormats.hex(it).lowercase()}"""") }
                    r.font?.let { append(it) }
                    if (r.script != 0) append(""" style:text-position="${if (r.script > 0) "super" else "sub"} 58%"""")
                }
                auto.append("""<style:style style:name="$name" style:family="text"><style:text-properties$p/></style:style>""")
                name
            }
        }
        /** A list style whose levels are numbered or bulleted as this list's items are. */
        fun listStyle(items: List<DocPara>): String {
            val pattern = (0..9).joinToString("") { l -> if (items.firstOrNull { it.list!!.level == l }?.list?.ordered == true) "n" else "b" }
            return listStyles.getOrPut(pattern) {
                val name = fresh("TL")
                auto.append("""<text:list-style style:name="$name">""")
                pattern.forEachIndexed { i, c ->
                    val indent = "%.3fcm".format(java.util.Locale.ROOT, 0.635 * (i + 1))
                    val props = """<style:list-level-properties text:list-level-position-and-space-mode="label-alignment"><style:list-level-label-alignment text:label-followed-by="listtab" text:list-tab-stop-position="$indent" fo:text-indent="-0.635cm" fo:margin-left="$indent"/></style:list-level-properties>"""
                    val (fmt, text) = doc.numbering?.getOrNull(i) ?: ("decimal" to "%${i + 1}.")
                    val format = when (fmt) { "lowerLetter" -> "a"; "upperLetter" -> "A"; "lowerRoman" -> "i"; "upperRoman" -> "I"; else -> "1" }
                    val shown = Regex("%[1-9]").findAll(text).count().coerceAtLeast(1) // "%1.%2." → two levels, joined by "." (ODF's way)
                    val prefix = OfficeFormats.esc(text.substringBefore('%')); val suffix = OfficeFormats.esc(text.substringAfterLast('%').drop(1))
                    if (c == 'n') auto.append("""<text:list-level-style-number text:level="${i + 1}" style:num-prefix="$prefix" style:num-suffix="$suffix" style:num-format="$format"${if (shown > 1) " text:display-levels=\"$shown\"" else ""}>$props</text:list-level-style-number>""")
                    else auto.append("""<text:list-level-style-bullet text:level="${i + 1}" text:bullet-char="${listOf("•", "◦", "▪")[i % 3]}">$props</text:list-level-style-bullet>""")
                }
                auto.append("</text:list-style>")
                name
            }
        }

        // Pictures added in Writer: each a new file in Pictures/, listed in the manifest, and a frame in the text.
        var manifest = parts["META-INF/manifest.xml"]?.let { String(it, Charsets.UTF_8) }
        var frames = 0
        val wraps = HashSet<Pair<PictureWrap, Boolean>>()
        val shapeStyles = HashSet<String>()
        fun frame(img: DocImage): String = (img.raw ?: img.chart?.let { ch ->
            // A new chart: an object of its own in the package (its content.xml), as LibreOffice keeps one.
            val name = generateSequence(1) { it + 1 }.map { "Object tetra-$it" }.first { "$it/content.xml" !in parts }
            parts["$name/content.xml"] = ChartWriter.odf(ch, img.widthPt, img.heightPt).toByteArray()
            manifest = manifest?.replace("</manifest:manifest>", """<manifest:file-entry manifest:full-path="$name/" manifest:version="1.3" manifest:media-type="application/vnd.oasis.opendocument.chart"/><manifest:file-entry manifest:full-path="$name/content.xml" manifest:media-type="text/xml"/></manifest:manifest>""")
            val n = ++frames
            """<draw:frame draw:name="Tetra chart $n" text:anchor-type="as-char" svg:width="${fmt(img.widthPt)}pt" svg:height="${fmt(img.heightPt)}pt" draw:z-index="0"><draw:object xlink:href="./$name" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad"/></draw:frame>"""
        } ?: img.math?.let { m ->
            // A new or rewritten equation: LibreOffice Math's object (MathML), in the line ("as-char"), sized as drawn.
            val name = generateSequence(1) { it + 1 }.map { "Object tetra-math-$it" }.first { "$it/content.xml" !in parts }
            parts["$name/content.xml"] = OfficeMath.mathml(m, true).toByteArray() // LibreOffice's own formulas are "block" in a line too
            if ("Tetra_20_formula" !in usedNames && shapeStyles.add("Tetra_20_formula")) auto.append("""<style:style style:name="Tetra_20_formula" style:family="graphic"><style:graphic-properties fo:margin-left="0cm" fo:margin-right="0cm" fo:margin-top="0cm" fo:margin-bottom="0cm" style:vertical-pos="middle" style:vertical-rel="text"/></style:style>""")
            manifest = manifest?.replace("</manifest:manifest>", """<manifest:file-entry manifest:full-path="$name/" manifest:version="1.3" manifest:media-type="application/vnd.oasis.opendocument.formula"/><manifest:file-entry manifest:full-path="$name/content.xml" manifest:media-type="text/xml"/></manifest:manifest>""")
            val n = ++frames
            """<draw:frame draw:style-name="Tetra_20_formula" draw:name="Tetra equation $n" text:anchor-type="as-char" svg:width="${fmt(img.widthPt)}pt" svg:height="${fmt(img.heightPt)}pt" draw:z-index="0"><draw:object xlink:href="./$name" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad"/></draw:frame>"""
        } ?: run {
            val bytes = img.bytes ?: return@run ""
            val name = generateSequence(1) { it + 1 }.map { "Pictures/tetra-$it.${img.extension}" }.first { it !in parts }
            parts[name] = bytes
            manifest = manifest?.replace("</manifest:manifest>", """<manifest:file-entry manifest:full-path="$name" manifest:media-type="image/${if (img.extension == "jpg") "jpeg" else img.extension}"/></manifest:manifest>""")
            val n = ++frames
            // a video as LibreOffice's Insert › Media keeps one: a plugin playing the film (a web address, or the film in the package's Media), its picture beside
            val film = img.film?.let { v -> if (v.startsWith("http")) v else runCatching { val ext = v.substringAfterLast('.', "mp4").lowercase()
                val path = generateSequence(1) { it + 1 }.map { "Media/tetra-$it.$ext" }.first { it !in parts }; parts[path] = java.io.File(v).readBytes()
                manifest = manifest?.replace("</manifest:manifest>", """<manifest:file-entry manifest:full-path="$path" manifest:media-type="video/${if (ext == "mov") "quicktime" else ext}"/></manifest:manifest>"""); path }.getOrNull() }
            if (film != null) """<draw:frame draw:name="Tetra video $n" text:anchor-type="as-char" svg:width="${fmt(img.widthPt)}pt" svg:height="${fmt(img.heightPt)}pt" draw:z-index="0"><draw:plugin xlink:href="${OfficeFormats.esc(film)}" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad" draw:mime-type="application/vnd.sun.star.media"><draw:param draw:name="Loop" draw:value="false"/><draw:param draw:name="Mute" draw:value="false"/><draw:param draw:name="VolumeDB" draw:value="0"/><draw:param draw:name="Zoom" draw:value="fit"/></draw:plugin><draw:image xlink:href="$name" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad"/></draw:frame>"""
            else """<draw:frame draw:name="Tetra picture $n" text:anchor-type="as-char" svg:width="${fmt(img.widthPt)}pt" svg:height="${fmt(img.heightPt)}pt" draw:z-index="0"><draw:image xlink:href="$name" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad"/></draw:frame>"""
        }).let { x -> img.wrap?.let { w -> val page = img.onPage != null && w != PictureWrap.InLine
            if (wraps.add(w to page)) auto.append(PictureXml.odtStyle(w, page)); PictureXml.odt(x, w, img.onPage.takeIf { page }) } ?: x }
            .also { x -> Regex("""draw:style-name="(Tetra_20_shape_[^"]+)"""").findAll(x).forEach { m -> m.groupValues[1].let { n -> if (n !in usedNames && shapeStyles.add(n)) PictureXml.odtShapeStyle(n)?.let(auto::append) } } }
            .also { x -> Regex("""text:style-name="(Tetra_20_art_[^"]+)"""").findAll(x).forEach { m -> m.groupValues[1].let { n -> if (n !in usedNames && shapeStyles.add(n)) PictureXml.odtArtStyle(n)?.let(auto::append) } } }
            // A group's pieces may name Writer's wrap styles of their own (a picture's).
            .also { x -> Regex("""draw:style-name="Tetra_20_wrap_20_(\w+?)(${PictureXml.ODT_PAGE_REL})?"""").findAll(x).forEach { m ->
                PictureWrap.entries.firstOrNull { it.name == m.groupValues[1] }?.let { w -> val page = m.groupValues[2].isNotEmpty()
                    if (PictureXml.odtStyleName(w, page) !in usedNames && wraps.add(w to page)) auto.append(PictureXml.odtStyle(w, page)) } } }

        // Comments: an annotation where one starts (its contents as the file had them, or written out), its end where it ends.
        val live = doc.liveComments.associateBy { it.id }
        val openComments = LinkedHashSet<String>()
        val closedComments = HashSet<String>() // started once: never again
        val nextFirst = java.util.IdentityHashMap<DocPara, DocPara?>().also { m ->
            var prev: DocPara? = null
            fun walk(ps: List<DocPara>) { ps.forEach { q -> if (q.table != null) q.table.rows.forEach { r -> r.cells.forEach { walk(it.paras) } } else { prev?.let { m[it] = q }; prev = q } } }
            walk(doc.paras)
        }
        fun StringBuilder.commentMarks(now: List<String>) {
            for (id in openComments.filter { it !in now }) { append("""<office:annotation-end office:name="${OfficeFormats.esc(id)}"/>"""); openComments -= id }
            for (id in now) if (id !in openComments && closedComments.add(id)) live[id]?.let { c ->
                append("""<office:annotation office:name="${OfficeFormats.esc(id)}"${c.parent?.let { " loext:parent-name=\"${OfficeFormats.esc(it)}\"" } ?: ""}${if (c.parent != null || c.done) " loext:resolved=\"${c.done}\"" else ""}>""")
                append(c.raw ?: ("<dc:creator>${OfficeFormats.esc(c.author)}</dc:creator>" + (c.date?.let { "<dc:date>$it</dc:date>" } ?: "") +
                    c.text.split('\n').joinToString("") { "<text:p>${spaces(it, true)}</text:p>" }))
                append("</office:annotation>")
                openComments += id
            }
        }
        var noteCount = 0
        fun paragraphXml(p: DocPara): String = buildString {
            val tag = if (p.heading > 0) "text:h" else "text:p"
            val parent = p.keep?.style?.takeIf { p.heading == 0 || !plainNamed(it) } ?: if (p.heading > 0) "Heading_20_${p.heading}" else body
            val style = paraStyle(parent, p.align, p.keep?.parts.orEmpty().filter { it.first != "empty" && it.first != OdtSections.PART && it.first != BOOKMARKS })
            append("<$tag text:style-name=\"$style\"")
            if (p.heading > 0) append(" text:outline-level=\"${p.heading}\"")
            append(">")
            // its bookmarks, where links inside the document go
            p.keep?.parts?.firstOrNull { it.first == BOOKMARKS }?.second?.split(' ')?.filter { it.isNotEmpty() }?.forEach { append("""<text:bookmark text:name="${OfficeFormats.esc(it)}"/>""") }
            var k = 0
            var prev: String? = null
            val runs = OfficeFormats.tidy(p.runs)
            if (runs.isEmpty()) p.keep?.parts?.firstOrNull { it.first == "empty" }?.let { append(it.second) }
            while (k < runs.size) {
                val link = runs[k].link
                var end = k
                while (end < runs.size && runs[end].link == link) end++
                if (link != null) append("""<text:a xlink:type="simple" xlink:href="${OfficeFormats.esc(link)}">""")
                var q = k
                var control: String? = null // a form's content control open here (LibreOffice's, from Word's sdtPr)
                while (q < end) {
                    val r = runs[q++]
                    if (r.control != control) {
                        if (control != null) append("</loext:content-control>")
                        control = r.control
                        control?.let { append(Forms.odt(it)) }
                    }
                    commentMarks(r.comments)
                    if (r.image != null) { append(frame(r.image)); prev = "x"; continue }
                    // A note, where its reference is: LibreOffice's, its number shown, its paragraphs inside.
                    r.note?.let { n ->
                        noteCount++
                        append("""<text:note text:id="ftn$noteCount" text:note-class="${if (n.end) "endnote" else "footnote"}"><text:note-citation>${OfficeFormats.esc(r.text)}</text:note-citation><text:note-body>""")
                        n.paras.forEach { append(paragraphXml(it)) }
                        append("</text:note-body></text:note>")
                        prev = "x"; continue
                    }
                    // A citation: a bibliography mark holding its source, its letters inside as plain text.
                    r.cite?.takeIf { !isField(it) }?.let { tag -> // a Word field: its letters only (LibreOffice has its own fields)
                        val text = StringBuilder(r.text)
                        while (q < end && runs[q].cite == tag && runs[q].image == null) text.append(runs[q++].text)
                        val src = doc.sources.firstOrNull { it.tag == tag }
                        append("<text:bibliography-mark${src?.let { Citations.odtAttributes(it) } ?: " text:identifier=\"${OfficeFormats.esc(tag)}\" text:bibliography-type=\"book\""}>${OfficeFormats.esc(text.toString())}</text:bibliography-mark>")
                        prev = text.toString()
                        continue
                    }
                    val s = textStyle(r)
                    if (s != null) append("<text:span text:style-name=\"$s\">")
                    append(spaces(r.text, afterSpace = prev?.endsWith(' ') ?: true))
                    prev = r.text
                    if (s != null) append("</text:span>")
                }
                if (control != null) { append("</loext:content-control>"); control = null }
                if (link != null) append("</text:a>")
                k = end
            }
            // A comment ends here unless the next paragraph goes on in it.
            commentMarks(nextFirst[p]?.runs?.firstOrNull()?.comments.orEmpty().filter { it in openComments })
            append("</$tag>")
        }

        /** Paragraphs, lists and tables as the body has them; a table's cells hold the same (a table in a cell too). */
        fun blockXml(paras: List<DocPara>, text: StringBuilder) {
        var i = 0
        var sections = emptyList<String>() // the sections open here, outermost first
        fun sectionTo(p: DocPara?) {
            val want = p?.let(OdtSections::tags).orEmpty()
            val same = sections.zip(want).takeWhile { (a, b) -> a == b }.size
            repeat(sections.size - same) { text.append("</text:section>") }
            want.drop(same).forEach { text.append(it); Regex("""text:style-name="(${OdtSections.TETRA}\d+)"""").find(it)?.groupValues?.get(1)?.let { n -> if (n !in usedNames && shapeStyles.add(n)) auto.append(OdtSections.style(n)) } }
            sections = want
        }
        while (i < paras.size) {
            val p = paras[i]
            sectionTo(p)
            p.table?.let { t ->
                // A table as the file had it: its attributes and columns raw, header rows grouped, merged-away cells kept.
                text.append("<table:table${t.props}>").append(t.grid)
                var inHeader = false
                for (row in t.rows) {
                    val header = row.props.startsWith(ODT_HEADER)
                    if (header != inHeader) { text.append(if (header) "<table:table-header-rows>" else "</table:table-header-rows>"); inHeader = header }
                    text.append("<table:table-row${row.props.removePrefix(ODT_HEADER)}>")
                    for (cell in row.cells) {
                        val covered = cell.props.startsWith(ODT_COVERED)
                        val tag = if (covered) "table:covered-table-cell" else "table:table-cell"
                        val inner = StringBuilder().also { blockXml(cell.paras, it) }
                        text.append("<$tag${cell.props.removePrefix(ODT_COVERED)}")
                        if (inner.isEmpty()) text.append("/>") else text.append(">").append(inner).append("</$tag>")
                    }
                    text.append("</table:table-row>")
                }
                if (inHeader) text.append("</table:table-header-rows>")
                text.append("</table:table>")
                i++; continue
            }
            if (p.list == null) { text.append(paragraphXml(p)); i++; continue }
            var end = i
            // one list while each level keeps its kind: a bulleted item after numbered ones (or back) starts a list of its own
            val kinds = HashMap<Int, Boolean>()
            while (end < paras.size && paras[end].list != null && paras[end].table == null && OdtSections.tags(paras[end]) == sections &&
                kinds.getOrPut(paras[end].list!!.level) { paras[end].list!!.ordered } == paras[end].list!!.ordered) end++
            val items = paras.subList(i, end)
            // The file's own list style, when every item still belongs to it and is still of its kind at its level.
            val source = items.first().keep?.list
            val reuse = source != null && items.all { it.keep?.list == source && source.substringAfterLast('|').getOrNull(it.list!!.level)?.let { c -> (c == 'n') == it.list.ordered } == true }
            text.append("<text:list text:style-name=\"${if (reuse) OfficeFormats.esc(source!!.substringBeforeLast('|')) else listStyle(items)}\">")
            var depth = 0 // lists open below the top one
            var itemOpen = false
            for ((n, item) in items.withIndex()) {
                val level = item.list!!.level
                while (depth > level) { text.append("</text:list-item></text:list>"); depth-- }
                if (itemOpen && depth == level) text.append("</text:list-item>")
                while (depth < level) { if (!itemOpen || n == 0) text.append("<text:list-item>"); text.append("<text:list>"); depth++; itemOpen = false }
                val start = item.list.start?.takeIf { item.list.ordered && (n == 0 || items[n - 1].list!!.level < level) && it != 1 }
                text.append(if (start != null) "<text:list-item text:start-value=\"$start\">" else "<text:list-item>")
                text.append(paragraphXml(item))
                itemOpen = true
            }
            text.append("</text:list-item>")
            while (depth > 0) { text.append("</text:list></text:list-item>"); depth-- }
            text.append("</text:list>")
            i = end
        }
        sectionTo(null)
        }
        val text = StringBuilder().also { blockXml(doc.paras, it) }

        val open = Regex("""<office:text(\s[^>]*)?(/?)>""").find(content)!!
        val decls: String
        val rest: String
        if (open.groupValues[2] == "/") { decls = ""; rest = content.substring(open.range.last + 1) }
        else {
            val close = content.indexOf("</office:text>", open.range.last)
            val inner = content.substring(open.range.last + 1, close)
            // Declarations (sequences, variables, forms) stay; the text is replaced.
            decls = Regex("""<(text:[a-z-]+-decls|office:forms)(\s[^>]*)?(/>|>[\s\S]*?</\1>)""").findAll(inner).joinToString("") { it.value }
            rest = content.substring(close + "</office:text>".length)
        }
        var out = content.substring(0, open.range.first) + "<office:text${open.groupValues[1]}>" + decls + text + "</office:text>" + rest
        if (ODT_TETRA_CELL in text && ownAuto.none { it.first == ODT_TETRA_CELL }) auto.append(ODT_TETRA_STYLES)
        val autoRe = Regex("""<office:automatic-styles\s*/>|<office:automatic-styles>[\s\S]*?</office:automatic-styles>""")
        val kept = LinkedHashSet<String>()
        var scan = text.toString() + auto
        while (true) {
            val named = Regex("""[a-z-]*style-name="([^"]+)"""").findAll(scan).map { it.groupValues[1] }.toSet()
            val more = ownAuto.filter { it.first in named && it.first !in kept }
            if (more.isEmpty()) break
            more.forEach { kept += it.first }
            scan = more.joinToString("") { it.second }
        }
        val ownKept = ownAuto.filter { it.first in kept }.joinToString("") { it.second }
        val autoXml = "<office:automatic-styles>$ownKept$auto</office:automatic-styles>"
        out = if (autoRe.containsMatchIn(out)) out.replaceFirst(autoRe, Regex.escapeReplacement(autoXml)) else out.replaceFirst("<office:body>", "$autoXml<office:body>")
        if ("xmlns:xlink=" !in out.substring(0, out.indexOf('>', out.indexOf("<office:document-content")))) out = out.replaceFirst("<office:document-content", "<office:document-content xmlns:xlink=\"$XLINK\"")
        if ("<office:annotation" in text && "xmlns:dc=" !in out.substring(0, out.indexOf('>', out.indexOf("<office:document-content")))) out = out.replaceFirst("<office:document-content", "<office:document-content xmlns:dc=\"$DC\"")
        if ("loext:" in text && "xmlns:loext=" !in out.substring(0, out.indexOf('>', out.indexOf("<office:document-content")))) out = out.replaceFirst("<office:document-content", "<office:document-content xmlns:loext=\"$LOEXT\"")
        manifest?.let { parts["META-INF/manifest.xml"] = it.toByteArray() }
        if (frames > 0) listOf("draw" to "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0", "svg" to SVG).forEach { (prefix, ns) ->
            val head = out.substring(0, out.indexOf('>', out.indexOf("<office:document-content")))
            if ("xmlns:$prefix=" !in head) out = out.replaceFirst("<office:document-content", "<office:document-content xmlns:$prefix=\"$ns\"")
        }
        parts["content.xml"] = out.toByteArray()
        // a dashed outline names its dash, which LibreOffice keeps in styles.xml
        shapeStyles.mapNotNull { n -> Regex("""Tetra_20_shape_\w+?_\w+?_\d+-([A-Za-z]+)""").find(n)?.groupValues?.get(1)?.takeIf { it != "shadow" } }.takeIf { it.isNotEmpty() }?.let { dashes ->
            parts["styles.xml"]?.let { parts["styles.xml"] = LineDash.intoStyles(String(it, Charsets.UTF_8), dashes).toByteArray() } }
        NoteFormats.odt(parts, doc)
        return OfficeFormats.zip(parts, stored = "mimetype")
    }

    /** A style that is only plain text, which a heading must not take instead of its heading style. */
    private fun plainNamed(name: String) = odfDisplayName(name).lowercase() in setOf("standard", "default", "text body", "body text", "normal")

    fun fmt(pt: Float): String = if (pt == pt.toInt().toFloat()) pt.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, pt)

    /** Text with ODF's spaces: a space after a space (or at the start of a paragraph) is a <text:s/>; a tab a <text:tab/>. */
    fun spaces(s: String, afterSpace: Boolean): String = buildString {
        var waiting = 0
        fun flush() { if (waiting > 0) append(if (waiting == 1) "<text:s/>" else "<text:s text:c=\"$waiting\"/>"); waiting = 0 }
        for ((i, c) in s.withIndex()) {
            if (c == ' ') {
                if (if (i == 0) afterSpace else s[i - 1] == ' ') waiting++ else append(' ')
                continue
            }
            flush()
            when (c) {
                '\t' -> append("<text:tab/>")
                LINE_BREAK -> append("<text:line-break/>")
                else -> append(OfficeFormats.esc(c.toString()))
            }
        }
        flush()
    }
}
