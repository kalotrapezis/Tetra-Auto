@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.magnifier
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.produceState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.BitmapFactory
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.times
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import com.mohamedrejeb.richeditor.document.RichTextBlock
import com.mohamedrejeb.richeditor.document.RichTextBlockType
import com.mohamedrejeb.richeditor.document.RichTextDocument
import com.mohamedrejeb.richeditor.document.RichTextSpanMark
import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.mohamedrejeb.richeditor.model.RichTextState
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextGeometricTransform
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Office (asked 2026-09-27): an orange group of its own. Its home starts a new Word document (Excel and PowerPoint
 * come later) and lists the documents in Tetra's Files; Writer edits one, what you see is what the file holds.
 */

/** Each app's own colour, as across Office and its look-alikes (asked 2026-09-27); the Office group stays orange. */
internal val WORD_BLUE = Color(0xFF185ABD)
internal val EXCEL_GREEN = Color(0xFF107C41)
internal val POWERPOINT_ORANGE = Color(0xFFB7472A) // deeper than the Office group's orange (asked 2026-09-27)

/** The editor's normal text size; the document's own normal size is shown at this size, the rest in proportion. */
internal const val BASE_SP = 16f

/** Pictures are drawn at most this wide (sp) — about the page's width on a phone; the file keeps their real size. */
private const val MAX_PICTURE_SP = 300f
/** Superscript and subscript are shown this much smaller; a size the file sets and the document's own are told apart by
 *  the factor (the editor keeps only the size shown). */
internal const val SCRIPT_SIZE = 0.7f
/** Writer's own changes kept for undo; each is the whole document, so not without end. */
private const val UNDO_STEPS = 50
/** How wide a tablet's comments pane is. */
private val COMMENT_PANE = 260.dp

/** The document as it was before one of Writer's own changes, for undo. */
private class WriterSnapshot(val doc: OfficeDoc, val original: ByteArray?, val styles: DocStyles,
    val pictures: Map<String, DocImage>, val selection: androidx.compose.ui.text.TextRange)
private const val SCRIPT_SIZE_OWN = 0.69f

/**
 * A paragraph's style drawn in the editor (asked 2026-09-29: headings as the document has them, Title as a title):
 * its size, weight, slant and colour put on its letters as marks told apart from the letters' own on the way back —
 * the size by a few hundredths of a point (a letter's own size is in half points), the weight 701 (bold is 700), the
 * colour one step short of opaque, slant with an invisible shadow beside it. So they are never saved as the letters'.
 */
private const val STYLE_SIZE = 0.037f
private const val STYLE_WEIGHT = 701
private const val STYLE_ALPHA = 0xFEL
/** A tracked change's letters: its author's colour, two steps short of opaque (never saved as the letters' own). */
private const val CHANGE_ALPHA = 0xFDL
private const val STYLE_SLANT = 0x01L // the shadow's colour: see-through
/** The letters' own "not bold" / "not italic" under a bold or italic style (DocRun.unbold/unitalic): weight 399, a see-through shadow 0x02. */
private const val UNBOLD_WEIGHT = 399
private const val UNSLANT = 0x02L
/** A paragraph aligned as its style says (not by its own setting); an alignment chosen on the ribbon takes it off. */
private val STYLE_ALIGNED = androidx.compose.ui.text.style.TextDirection.ContentOrRtl

/**
 * The editor numbers a list as the file does — "a.", "iii)", "2.1." — rather than always 1. 2. 3. (the pages already do).
 * The editor gives only an item's own number; it numbers in document order, so each level's last number is the
 * parent's ("2.1."). Labels that order leaves stale (a parent renumbered) are put right by [WriterBridge.staleNumbers].
 */
internal class FileNumbering(val styles: DocStyles, val levels: () -> List<Pair<String, String>?>) : com.mohamedrejeb.richeditor.paragraph.type.OrderedListStyleType {
    val counts = IntArray(9)
    val compound get() = levels().any { it != null && Regex("%[1-9]").findAll(it.second).count() > 1 }
    override fun format(number: Int, listLevel: Int): String {
        val l = (listLevel - 1).coerceIn(0, 8)
        counts[l] = number; for (d in l + 1 until 9) counts[d] = 0
        val all = levels()
        val (fmt, text) = all.getOrNull(l) ?: return number.toString()
        val head = Regex("%[1-9]").findAll(text).lastOrNull()?.let { text.substring(0, it.range.last + 1) } ?: return styles.number(number, fmt)
        return styles.label(all.mapIndexed { i, x -> if (i == l) fmt to head else x }, l, counts) ?: number.toString()
    }
    override fun getSuffix(listLevel: Int): String = levels().getOrNull(listLevel - 1)?.second?.let { Regex("%[1-9]([^%]*)$").find(it)?.groupValues?.get(1) } ?: "."
}
/** Home › Font: Word's everyday fonts, each drawn by a font of the same widths (Carlito, Caladea, Liberation). */
private val WRITER_FONTS = listOf("Calibri", "Cambria", "Times New Roman", "Arial", "Courier New")
/** The ribbon's and a gallery strip's way in and out: quick, from and to the bottom. */
internal val SLIDE_IN = androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(180)) { it } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180))
internal val SLIDE_OUT = androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(140)) { it } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140))
/** Left said in the file itself (Word's jc="left" over a centred style): the paragraph's own, not its style's. */
private fun ownLeft(p: DocPara) = p.align == DocAlign.Start && p.keep?.parts?.any { it.first == "jc" } == true

/** The character the editor holds a picture as (its own choice: U+FFFD, not our U+FFFC). */
private const val EDITOR_PICTURE_CHAR = '\uFFFD'
/** Typed where a picture is to go, then replaced by it (a private-use character: never in a document). */
internal const val PICTURE_SPOT = '\uE000'

/** How the editor names a picture: this and the picture's key. */
internal const val PICTURE_URL = "tetra-picture:"

/** OfficeDoc ↔ the editor's document. Kept apart from the screen so the two directions are easy to compare. */
internal object WriterBridge {
    /**
     * The editor's own heading looks (Heading 1 twice the text's size, and so on) taken away, once: a heading shows as
     * the document's style has it, drawn like any paragraph style's. The library has no setting for them.
     */
    fun documentHeadings() = runCatching {
        val field = HeadingStyle::class.java.getDeclaredField("defaultSpanStyle").apply { isAccessible = true }
        HeadingStyle.entries.filter { it != HeadingStyle.Normal }.forEach { field.set(it, SpanStyle()) }
    }.onFailure { WriterLog.w("heading looks", it) }

    /**
     * How a picture shows in the editor: its size, scaled with the text, and never wider than the page. One in front of
     * or behind the text moves none of it (the pages show it in its place): here a thumbnail a line tall, to tap.
     */
    fun shownSize(img: DocImage, scale: Float): Pair<Float, Float> {
        val w = img.widthPt * scale
        // A table is laid out at the page's width (its columns as the file sets them, as the pages show it) and drawn
        // smaller when the screen is narrower (a phone), as a picture is — laid out narrower, its columns grew to fit
        // their longest words and the others broke words in two (the user's CV, 2026-09-30).
        if (img.table != null) return (editorWidthSp / w).coerceAtMost(1f).let { fit -> w * fit to img.heightPt * scale * fit }
        val over = PictureXml.wrapOf(img).let { it == PictureWrap.InFront || it == PictureWrap.Behind }
        // On the page (a tablet, Print layout) it is drawn in its place over the text: its letter takes no room.
        if (over && pageWidthPt != null) return 0.5f to 0.5f
        // On the page as wide as the page lets it (the pages' rule); in the reflowing editor a screen's worth.
        // Without a page (Mobile view) it goes in the text at its size, fitted to the screen, as Word's mobile view does
        // (asked 2026-10-01: a cover's logos were line-tall marks in the corner).
        val fit = minOf((pageWidthPt?.let { it * scale } ?: minOf(MAX_PICTURE_SP, editorWidthSp)) / w, 1f)
        return w * fit to img.heightPt * scale * fit
    }

    /** [img] where [PICTURE_SPOT] was typed, in the look of its own run (or of the text it was typed in). */
    fun putPicture(doc: OfficeDoc, img: DocImage): OfficeDoc = doc.copy(paras = doc.paras.map { p ->
        if (p.runs.none { PICTURE_SPOT in it.text }) p else p.copy(runs = OfficeFormats.tidy(p.runs.flatMap { r ->
            if (PICTURE_SPOT !in r.text) listOf(r) else r.text.split(PICTURE_SPOT).flatMapIndexed { k, t -> listOfNotNull(
                if (k > 0) (pictureRuns[img.key] ?: r.copy(link = null)).copy(text = OBJECT_MARK.toString(), image = img) else null,
                r.copy(text = t).takeIf { t.isNotEmpty() }) }
        }))
    })

    /** How wide the editor's picture character is (as Display lays it: U+FFFC), for its size (measured by the screen; about 1 elsewhere). */
    var pictureGlyph = 1f
    /** A line of the editor's text, top to bottom, for each point of its size. */
    var lineRatio = 1.17f
    /** A line's top to its baseline, for each point of its size. */
    var letterRise = 0.93f
    /** The size of the letter standing for a picture [h] sp tall (a letter's rise is ~0.9 of its size). */
    fun pictureLetter(h: Float) = (h * 1.12f).coerceAtLeast(4f)
    /** A capital's height for each point of its size (about, in most fonts): a drop cap's letters sized by it. */
    private const val DROP_CAP_RATIO = 0.7f

    /** A picture's own run as the file had it (its letter formatting), by the picture's key. */
    val pictureRuns = HashMap<String, DocRun>()

    /**
     * A paragraph's lines as tall as the pages make them (asked 2026-10-01: a cover's text rode up under its logos):
     * its line spacing (1.15, double, exact) over its biggest letters — an empty one's, its paragraph mark's size, as
     * Word gives an empty line. Left to the editor for single spacing, headings, pictures and tables.
     */
    /** A picture in front of or behind the text, drawn on the page in its place: it takes no room in its line. */
    fun overOnPage(r: DocRun) = pageWidthPt != null && r.image?.let { PictureXml.wrapOf(it) }.let { it == PictureWrap.InFront || it == PictureWrap.Behind }
    private fun lineHeight(p: DocPara, all: List<DocRun>, look: ParaLook, basePt: Float, scale: Float): androidx.compose.ui.unit.TextUnit {
        val runs = all.filterNot { overOnPage(it) }
        // A table's line: exactly the table (on a phone a one-row table is shorter than a line of the editor's own text,
        // which a line never goes below — a gap under each small table, the user's CV).
        runs.firstOrNull { it.image?.table != null }?.image?.let { return shownSize(it, scale).second.sp }
        if (p.table != null || runs.any { it.image != null }) return androidx.compose.ui.unit.TextUnit.Unspecified
        if (DropCap.of(p.keep) != null) return androidx.compose.ui.unit.TextUnit.Unspecified // its first line as tall as the drop cap
        look.lineExact?.takeIf { !look.lineAtLeast }?.let { return (it * scale).sp }
        val mark = if (runs.isEmpty()) OfficeFormats.markSize(p) else null
        if (look.lineMultiple == 1f && mark == null && look.lineExact == null) return androidx.compose.ui.unit.TextUnit.Unspecified
        val size = mark ?: (runs.maxOfOrNull { it.sizePt ?: look.sizePt ?: basePt } ?: look.sizePt ?: basePt)
        return maxOf(size * scale * lineRatio * look.lineMultiple, (look.lineExact ?: 0f) * scale).sp
    }

    /** Visual font and separator styles never enter the editable document or its saved runs. */
    fun displayText(shown: AnnotatedString, rich: RichTextDocument, doc: OfficeDoc, styles: DocStyles,
        fontFamily: (String?) -> FontFamily? = { name -> OfficeFonts.family(name) }): AnnotatedString {
        val places = blockPlaces(shown.text, rich)
        val fonts = AnnotatedString.Builder(shown).apply {
            doc.paras.forEachIndexed { para, p ->
                val look = styles.look(p)
                var at = 0
                p.runs.forEach { run ->
                    run.image?.takeIf { it.table == null }?.let { img ->
                        // Asked 17 Oct: Android 15+ drops letter spacing at a line's ends, so a picture alone on its line took only
                        // its letter's width and a centred one sat right of centre. A stretched letter keeps its width anywhere.
                        val (w, h) = shownSize(img, BASE_SP / doc.basePt)
                        // Alone in its paragraph: its line only the picture and a text line's fall, as LibreOffice lays it (asked
                        // 17 Oct: the letter's own fall left a quarter of a picture's gap under it); drawn from the line's top.
                        val alone = p.runs.size == 1 && run.text.length == 1 && !overOnPage(run)
                        val letter = if (alone) h / lineRatio + ((look.sizePt ?: doc.basePt) * BASE_SP / doc.basePt) * (1f - letterRise / lineRatio) else pictureSp(pictureLetter(h))
                        editorSpans(places, para, at, at + run.text.length).forEach { range ->
                            addStyle(SpanStyle(fontSize = if (alone) letter.sp else androidx.compose.ui.unit.TextUnit.Unspecified, letterSpacing = 0.sp,
                                textGeometricTransform = TextGeometricTransform(scaleX = w / (pictureGlyph * letter))), range.first, range.last + 1)
                        }
                    }
                    if (run.image == null) (styles.fontName(run.font)?.let(fontFamily) ?: fontFamily(look.font))?.let { family ->
                        editorSpans(places, para, at, at + run.text.length).forEach { range ->
                            addStyle(SpanStyle(fontFamily = family), range.first, range.last + 1)
                        }
                    }
                    at += run.text.length
                }
                // A drop cap: its letters as tall as the lines they drop through, standing on the first line — the editor
                // cannot wrap lines beside them (the pages drop them); shown only, never saved as the letters' size
                DropCap.of(p.keep)?.let { drop ->
                    val r0 = p.runs.firstOrNull()?.takeIf { it.image == null && it.text.isNotBlank() } ?: return@let
                    val size = r0.sizePt ?: look.sizePt ?: doc.basePt
                    val pt = ((drop.lines - 1) * size * lineRatio * look.lineMultiple + size * DROP_CAP_RATIO) / DROP_CAP_RATIO
                    editorSpans(places, para, 0, drop.length.coerceAtMost(r0.text.length)).forEach { range ->
                        addStyle(SpanStyle(fontSize = (pt * BASE_SP / doc.basePt).sp), range.first, range.last + 1)
                    }
                }
            }
            places.forEach { b ->
                // The library appends an unstyled space to each block. Its default 16 sp font used to keep
                // a small table taller than the table itself, even with an exact paragraph line height.
                if (doc.paras.getOrNull(b.para)?.table != null && b.to < shown.length)
                    addStyle(SpanStyle(fontSize = 0.5.sp, letterSpacing = 0.sp), b.to, b.to + 1)
            }
        }.toAnnotatedString()
        // The library already owns one separator character between blocks. Give it a separate visual
        // paragraph for the gap, keeping text length and every cursor/selection offset unchanged.
        val gaps = paragraphGaps(doc, styles, places)
        if (gaps.isEmpty()) return fonts
        return AnnotatedString.Builder().apply {
            var from = 0
            gaps.forEach { (at, gap) ->
                append(fonts.subSequence(from, at))
                pushStyle(ParagraphStyle(lineHeight = (gap * BASE_SP / doc.basePt).sp,
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Top, LineHeightStyle.Trim.None)))
                pushStyle(SpanStyle(fontSize = 0.5.sp, color = Color.Transparent))
                append(fonts.text[at])
                pop(); pop()
                from = at + 1
            }
            append(fonts.subSequence(from, fonts.length))
        }.toAnnotatedString()
    }

    /** Space after one paragraph and before the next, never between two lines of the same paragraph. */
    fun paragraphGaps(doc: OfficeDoc, styles: DocStyles, places: List<BlockPlace>): List<Pair<Int, Float>> =
        places.zipWithNext().mapNotNull { (a, b) ->
            if (a.para == b.para) return@mapNotNull null
            val p = doc.paras.getOrNull(a.para) ?: return@mapNotNull null
            // An empty list item holds only its number: a gap of its own would split that line from its cursor.
            if (a.from == a.to && p.list != null) return@mapNotNull null
            val next = doc.paras.getOrNull(b.para) ?: return@mapNotNull null
            val look = if (p.table == null) styles.look(p) else ParaLook()
            val after = if (next.table == null) styles.look(next) else ParaLook()
            if (after.pageBreakBefore) return@mapNotNull null
            val contextual = look.contextual && after.contextual && p.keep?.style == next.keep?.style && p.heading == next.heading
            val gap = if (contextual) 0f else look.after + after.before
            (a.to to gap).takeIf { gap > 0f }
        }

    /** Writer's look of the editor's text (fonts, the space between paragraphs, tabs); [shown] is what it was made from. */
    class Display(val shown: AnnotatedString) : VisualTransformation {
        private val laid = breakablePictures(shown)
        // An IME update can precede the display effect. Always keep the input's length/offsets valid.
        override fun filter(text: AnnotatedString) =
            TransformedText(if (text.text.replace('\n', ' ').replace('\t', EDITOR_TAB_SPACE) == shown.text.replace('\t', EDITOR_TAB_SPACE)) laid else text, OffsetMapping.Identity)
    }

    /**
     * [s] laid with each picture letter as U+FFFC, same length and marks: Android breaks a line before and after that one
     * (U+FFFD, the editor's, is a letter to it, so a picture took the word it touches down with it). Word moves only the picture.
     */
    fun breakablePictures(s: AnnotatedString): AnnotatedString {
        if (EDITOR_PICTURE_CHAR !in s.text) return s
        val marks = ArrayList<AnnotatedString.Range<out AnnotatedString.Annotation>>()
        s.mapAnnotations { marks += it; it }
        return AnnotatedString(s.text.replace(EDITOR_PICTURE_CHAR, OBJECT_MARK), marks)
    }

    fun displayText(state: RichTextState, shown: AnnotatedString) {
        runCatching {
            state.javaClass.getMethod("setVisualTransformation\$com_mohamedrejeb_richeditor_richeditor_compose", VisualTransformation::class.java)
                .invoke(state, Display(shown))
        }.onFailure { WriterLog.w("editor display", it) }
    }

    /** Whether the editor shows Writer's look now (the library puts back its own, without fonts, at every keystroke). */
    fun showsWriters(state: RichTextState): Boolean = runCatching {
        state.javaClass.getMethod("getVisualTransformation\$com_mohamedrejeb_richeditor_richeditor_compose").invoke(state) is Display
    }.getOrDefault(true)

    /**
     * Writer's look [shown] — made from the editor's text [base] — over the editor's text [now], a keystroke or a Bold later:
     * the editor's own styles as they are now, and only what Writer added to [base] (fonts, the space between paragraphs),
     * moved past the letters typed or removed (those typed at a span's end take it on). Null when it cannot be laid so.
     */
    fun carried(shown: AnnotatedString, base: AnnotatedString, now: AnnotatedString): AnnotatedString? {
        fun <T> added(all: List<AnnotatedString.Range<T>>, had: List<AnnotatedString.Range<T>>): List<AnnotatedString.Range<T>> {
            val left = had.groupingBy { it }.eachCount().toMutableMap()
            return all.filter { r -> val n = left[r] ?: 0; if (n > 0) { left[r] = n - 1; false } else true }
        }
        val o = base.text; val t = now.text
        var pre = 0
        while (pre < o.length && pre < t.length && o[pre] == t[pre]) pre++
        var suf = 0
        while (suf < o.length - pre && suf < t.length - pre && o[o.length - 1 - suf] == t[t.length - 1 - suf]) suf++
        val oldEnd = o.length - suf; val delta = t.length - o.length
        fun start(x: Int) = if (x <= pre) x else if (x >= oldEnd) x + delta else pre
        fun end(x: Int) = if (x < pre) x else if (x >= oldEnd && (x > pre || delta > 0)) x + delta else pre
        fun <T> moved(list: List<AnnotatedString.Range<T>>) = list.mapNotNull { r ->
            val a = start(r.start); val z = end(r.end)
            if (z < a || (z == a && r.end > r.start)) null else AnnotatedString.Range(r.item, a, z)
        }
        return runCatching { AnnotatedString(t, now.spanStyles + moved(added(shown.spanStyles, base.spanStyles)),
            now.paragraphStyles + moved(added(shown.paragraphStyles, base.paragraphStyles))) }.getOrNull()
    }

    /** Whether a numbered item's label is not what document order gives it ("1.1." under an item that became 2.). */
    fun staleNumbers(state: RichTextState, numbering: FileNumbering): Boolean {
        val paras = runCatching { state.javaClass.getMethod("getRichParagraphList\$com_mohamedrejeb_richeditor_richeditor_compose").invoke(state) as List<*> }
            .onFailure { WriterLog.w("editor numbers", it) }.getOrNull() ?: return false
        val fresh = FileNumbering(numbering.styles, numbering.levels)
        fun get(o: Any?, name: String) = o?.javaClass?.getMethod(name)?.invoke(o) // the library's paragraph classes are internal
        return runCatching {
            paras.any { p ->
                val t = get(p, "getType")?.takeIf { it.javaClass.simpleName == "OrderedList" } ?: return@any false
                val level = get(t, "getLevel") as Int
                (get(get(t, "getStartRichSpan"), "getText") as String).trimEnd() != (fresh.format(get(t, "getNumber") as Int, level) + fresh.getSuffix(level)).trimEnd()
            }
        }.onFailure { WriterLog.w("editor numbers", it) }.getOrDefault(false)
    }

    /**
     * A list item's number is drawn in its first letter's look (a numbered heading's number as big as the heading) —
     * but when that letter is a picture's, the number took the picture's size and width and pushed the words onto the
     * next line ("P" alone by a WordArt). While an item starts with a picture, numbers are drawn in the text's own look.
     */
    fun plainMarkersIfPictureFirst(state: RichTextState) {
        val paras = runCatching { state.javaClass.getMethod("getRichParagraphList\$com_mohamedrejeb_richeditor_richeditor_compose").invoke(state) as List<*> }
            .onFailure { WriterLog.w("editor markers", it) }.getOrNull() ?: return
        val pictureFirst = runCatching { paras.any { p ->
            p!!.javaClass.getMethod("getType").invoke(p).javaClass.simpleName.endsWith("List") &&
                (p.javaClass.getMethod("getStartTextSpanStyle").invoke(p) as SpanStyle).fontSize.let { it.isSp && isPictureSp(it.value) }
        } }.onFailure { WriterLog.w("editor markers", it) }.getOrDefault(false)
        val want = if (pictureFirst) com.mohamedrejeb.richeditor.paragraph.type.ListMarkerStyleBehavior.AlwaysDefault
            else com.mohamedrejeb.richeditor.paragraph.type.ListMarkerStyleBehavior.InheritFromText
        if (state.config.listMarkerStyleBehavior != want) state.config.listMarkerStyleBehavior = want
    }

    /** Replace only the displayed geometry; setRichTextDocument also clears typing Undo/Redo. */
    fun reflow(state: RichTextState, doc: OfficeDoc, pictures: MutableMap<String, DocImage>, styles: DocStyles) {
        val rich = toEditor(doc, pictures, styles)
        runCatching {
            val decoder = Class.forName("com.mohamedrejeb.richeditor.document.RichTextDocumentDecoder")
            val paragraphs = decoder.getMethod("decode", RichTextDocument::class.java).invoke(decoder.getField("INSTANCE").get(null), rich)
            state.javaClass.getMethod("updateRichParagraphList-psREZIo\$com_mohamedrejeb_richeditor_richeditor_compose", List::class.java,
                androidx.compose.ui.text.TextRange::class.java).invoke(state, paragraphs, state.selection)
        }.onFailure { WriterLog.w("editor reflow", it) }
    }

    /** The letters [spans] (paragraph, from, to) given [look]'s letter formatting, their words and marks kept (Format painter). */
    fun painted(doc: OfficeDoc, spans: List<Triple<Int, Int, Int>>, look: DocRun): OfficeDoc = restyled(doc, spans) { r -> r.copy(bold = look.bold, italic = look.italic,
        underline = look.underline, strike = look.strike, sizePt = look.sizePt, color = look.color, highlight = look.highlight, font = look.font, script = look.script) }

    /** The letters [spans] (paragraph, from, to) changed by [restyle], cut out of their runs where a span begins or ends. */
    fun restyled(doc: OfficeDoc, spans: List<Triple<Int, Int, Int>>, restyle: (DocRun) -> DocRun): OfficeDoc {
        val byPara = spans.groupBy { it.first }
        return doc.copy(paras = doc.paras.mapIndexed { i, p ->
            val mine = byPara[i] ?: return@mapIndexed p
            var at = 0
            val runs = ArrayList<DocRun>()
            for (r in p.runs) {
                val cuts = sortedSetOf(0, r.text.length)
                mine.forEach { (_, a, b) -> cuts += (a - at).coerceIn(0, r.text.length); cuts += (b - at).coerceIn(0, r.text.length) }
                cuts.toList().zipWithNext().filter { (x, y) -> y > x }.forEach { (x, y) ->
                    val inside = r.image == null && r.note == null && mine.any { (_, a, b) -> at + x >= a && at + y <= b }
                    runs += r.copy(text = r.text.substring(x, y)).let { if (inside) restyle(it) else it }
                }
                at += r.text.length
            }
            p.copy(runs = OfficeFormats.tidy(runs))
        })
    }

    /** [pictures] learns every picture shown, by the key the editor knows it by. */
    fun toEditor(doc: OfficeDoc, pictures: MutableMap<String, DocImage> = HashMap(), styles: DocStyles = DocStyles.Plain): RichTextDocument {
        val scale = BASE_SP / doc.basePt
        /** One line of the editor: a paragraph, or (after a line break inside it) the next line of one. */
        fun block(p: DocPara, runs: List<DocRun>, afterBreak: Boolean): RichTextBlock {
            val marks = ArrayList<RichTextSpanMark>()
            val look = styles.look(p)
            var at = 0
            for (r in runs) {
                val range = at until at + r.text.length
                // The paragraph style's look, where the letters have none of their own.
                if (r.image == null && r.text.isNotEmpty()) {
                    if (r.sizePt == null && r.script == 0) look.sizePt?.takeIf { kotlin.math.abs(it - doc.basePt) > 0.01f }?.let { marks += RichTextSpanMark.FontSize(range, ((it + STYLE_SIZE) * scale).sp) }
                    if (!r.bold && !r.unbold && look.bold == true) marks += RichTextSpanMark.FontWeight(range, STYLE_WEIGHT)
                    if (r.unbold) marks += RichTextSpanMark.FontWeight(range, UNBOLD_WEIGHT)
                    if (r.unitalic) marks += RichTextSpanMark.Shadow(range, UNSLANT, 0f, 0f, 0f)
                    if (!r.italic && !r.unitalic && look.italic == true) { marks += RichTextSpanMark.Italic(range); marks += RichTextSpanMark.Shadow(range, STYLE_SLANT, 0f, 0f, 0f) }
                    if (r.color == null) look.color?.takeIf { it != 0 }?.let { marks += RichTextSpanMark.TextColor(range, (STYLE_ALPHA shl 24) or it.toLong()) }
                }
                // Capitals, small capitals, double lines: drawn by the editor too (the letters stay as typed).
                LetterLooks.of(r.font).takeIf { it.any && r.image == null }?.let { marks += RichTextSpanMark.Custom(range, EditorLetters(it, r.sizePt ?: look.sizePt ?: 11f)) }
                if (r.bold) marks += RichTextSpanMark.Bold(range)
                if (r.italic) marks += RichTextSpanMark.Italic(range)
                if (r.underline) marks += RichTextSpanMark.Underline(range)
                if (r.strike) marks += RichTextSpanMark.Strikethrough(range)
                if (r.script != 0) {
                    marks += RichTextSpanMark.BaselineShift(range, if (r.script > 0) 0.4f else -0.15f) // about where Word puts them
                    marks += RichTextSpanMark.FontSize(range, ((r.sizePt ?: doc.basePt) * scale * (if (r.sizePt != null) SCRIPT_SIZE else SCRIPT_SIZE_OWN)).sp)
                } else r.sizePt?.let { marks += RichTextSpanMark.FontSize(range, (it * scale).sp) }
                r.color?.let { marks += RichTextSpanMark.TextColor(range, 0xFF000000L or it.toLong()) }
                r.change?.let { c -> if (r.image == null) marks += RichTextSpanMark.TextColor(range, (CHANGE_ALPHA shl 24) or TrackChanges.color(c.author).toLong()) }
                r.highlight?.let { marks += RichTextSpanMark.Highlight(range, 0xFF000000L or it.toLong()) }
                r.link?.let { marks += RichTextSpanMark.Link(range, it) }
                r.image?.let { img ->
                    pictures[img.key] = img
                    pictureRuns[img.key] = r
                    val (w, h) = shownSize(img, scale)
                    marks += RichTextSpanMark.Image(range, PICTURE_URL + img.key, w.sp, h.sp, "Picture")
                    // The editor cannot draw it (Writer does, over the text): the character is made as tall as the
                    // picture, so its line makes room, and invisible.
                    // A table is a line of its own: its letter's whole line (rise and fall) is the table's height, and it is
                    // drawn from the line's top — standing on the baseline, the fall left a gap a quarter of its height.
                    val size = if (img.table != null) (h / lineRatio).coerceAtLeast(4f) else pictureLetter(h)
                    // ponytail: the space the editor puts after each paragraph keeps the editor's own text size, so in Mobile
                    // view (tables shrunk to the screen) a short table's line stays a text line tall; a mark cannot reach it
                    // (the library refuses a range past the block). Print layout has no gap.
                    marks += RichTextSpanMark.FontSize(range, pictureSp(size).sp)
                    marks += RichTextSpanMark.TextColor(range, 0L)
                    // …and exactly as wide as the picture, so the text after it starts where the picture ends. Android gives
                    // half the spacing before a letter and half after, and none before the first of a line: there, twice —
                    // but not in a list item, whose number the editor puts first (twice there, the picture was a line and a
                    // half wide and "Page" broke after its "P").
                    // A table's a hair narrower than the line: exactly as wide, the paragraph's closing space sometimes went on to
                    // a line of its own — a text line's gap under the table (the user's CV: "gaps between tables").
                    val room = if (img.table != null) w - 1.5f else w
                    marks += RichTextSpanMark.LetterSpacing(range, ((room - pictureGlyph * size) * (if (at == 0 && (p.list == null || afterBreak)) 2 else 1)).sp)
                }
                at += r.text.length
            }
            return RichTextBlock(
                text = runs.joinToString("") { it.text },
                // The line after a break is not a new list item (no bullet of its own).
                type = p.list?.takeIf { !afterBreak }?.let { RichTextBlockType.ListItem(it.ordered, it.level, it.start) } ?: RichTextBlockType.Paragraph,
                spans = marks,
                headingLevel = p.heading,
                // Its own alignment, else its style's — marked (a text direction no paragraph of its own has) as the style's.
                textAlign = when (p.align.takeIf { it != DocAlign.Start || ownLeft(p) } ?: look.align ?: DocAlign.Start) { DocAlign.Start -> TextAlign.Unspecified; DocAlign.Center -> TextAlign.Center; DocAlign.End -> TextAlign.End; DocAlign.Justify -> TextAlign.Justify },
                textDirection = if (p.align == DocAlign.Start && !ownLeft(p) && look.align != null && look.align != DocAlign.Start) STYLE_ALIGNED else androidx.compose.ui.text.style.TextDirection.Unspecified,
                // The paragraph's left and first-line indents shown (a list item's indent is the editor's own).
                textIndent = if (p.list != null || p.table != null) null else look.let { l ->
                    val rest = l.left.coerceAtLeast(0f) * scale
                    val first = if (afterBreak) rest else (l.left + l.firstLine).coerceAtLeast(0f) * scale
                    if (rest == 0f && first == 0f) null else androidx.compose.ui.text.style.TextIndent(first.sp, rest.sp)
                },
                isLineBreak = afterBreak,
                lineHeight = lineHeight(p, runs, look, doc.basePt, scale),
            )
        }
        // A line break inside a paragraph (the editor draws U+2028 as a box): the lines after it are the editor's own
        // "from a line break" lines, joined back into the paragraph by fromEditor.
        val spacing = ArrayList<BlockSpacing?>()
        val looks = doc.paras.map { if (it.table != null) null else styles.look(it) }
        fun styleOf(i: Int) = doc.paras.getOrNull(i)?.let { it.keep?.style + "/" + it.heading }
        fun same(i: Int, j: Int) = looks.getOrNull(i)?.contextual == true && looks.getOrNull(j)?.contextual == true && styleOf(i) == styleOf(j)
        return RichTextDocument(doc.paras.flatMapIndexed { index, p ->
            // A table: one character in the editor, standing for it (as a picture's does); it comes back as it went.
            // Known by its place, not its contents: a cell's edit changed its key under the Table tab (the next change
            // went to a table no longer shown), and two tables alike shared one.
            p.table?.let { t ->
                val (width, height) = tableSize(doc, t, styles)
                val img = DocImage("t$index", null, width, height, table = t)
                spacing += BlockSpacing(0f, 0f, shownSize(img, scale).second) // its line exactly the table's height
                return@flatMapIndexed listOf(block(p, listOf(DocRun(OBJECT_MARK.toString(), image = img)), false))
            }
            val lines = mutableListOf(mutableListOf<DocRun>())
            for (r in OfficeFormats.tidy(p.runs)) r.text.split(LINE_BREAK).forEachIndexed { i, piece ->
                if (i > 0) lines += mutableListOf<DocRun>()
                if (piece.isNotEmpty()) lines.last() += r.copy(text = piece)
            }
            val look = looks[index]!!
            lines.mapIndexed { i, runs ->
                block(p, runs, i > 0).also { b ->
                    // Space before the paragraph's first line and after its last (the pages' rule: none between two of one
                    // style that say so); none round a picture's line.
                    val before = if (i == 0 && (index == 0 || !same(index - 1, index))) look.before else 0f
                    val after = if (i == lines.size - 1 && !same(index, index + 1)) look.after else 0f
                    val shown = runs.filterNot { overOnPage(it) }
                    spacing += if (shown.any { it.image != null } || before + after <= 0f) null else {
                        val size = shown.maxOfOrNull { it.sizePt ?: look.sizePt ?: doc.basePt } ?: (lineMarkSize(p) ?: look.sizePt ?: doc.basePt)
                        val line = b.lineHeight.takeIf { it.isSp }?.value ?: (size * scale * lineRatio)
                        BlockSpacing(before * scale, after * scale, line)
                    }
                }
            }
        }).also { blockSpacing = spacing }
    }
    /** Each block's space before and after and its line (sp), as the last [toEditor] made them: [spaceParagraphs]. */
    var blockSpacing: List<BlockSpacing?> = emptyList()
    private fun lineMarkSize(p: DocPara) = OfficeFormats.markSize(p)

    /**
     * Exact line heights in the editor, including the table's stand-in. Paragraph gaps are visual separator
     * paragraphs in [displayText], so they apply once even when the text wraps over several lines.
     * Returns whether anything changed.
     */
    fun spaceParagraphs(state: RichTextState, layout: TextLayoutResult): Boolean {
        val list = runCatching { state.javaClass.getMethod("getRichParagraphList\$com_mohamedrejeb_richeditor_richeditor_compose").invoke(state) as List<*> }.getOrNull() ?: return false
        val spacing = blockSpacing
        if (list.size != spacing.size) return false
        if (layout.layoutInput.text.length != state.annotatedString.length) return false
        val blocks = state.toRichTextDocument().blocks
        if (blocks.size != list.size) return false
        val cls = list.firstOrNull()?.javaClass ?: return false
        val getStyle = cls.getMethod("getParagraphStyle"); val setStyle = cls.getMethod("setParagraphStyle", ParagraphStyle::class.java)
        var changed = false
        for ((k, para) in list.withIndex()) {
            val sp = spacing[k]
            val style = getStyle.invoke(para) as ParagraphStyle
            val want = if (sp != null) style.copy(lineHeight = sp.line.sp,
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Top, LineHeightStyle.Trim.None)) else style
            if (want != style) { setStyle.invoke(para, want); changed = true }
        }
        // Shown again (the library's own refresh, with its current text and selection: its default argument).
        if (changed) runCatching { state.javaClass.getMethod("updateAnnotatedString\$com_mohamedrejeb_richeditor_richeditor_compose\$default", state.javaClass,
            androidx.compose.ui.text.input.TextFieldValue::class.java, Int::class.javaPrimitiveType, Any::class.java).invoke(null, state, null, 1, null) }
            .onFailure { WriterLog.w("paragraph spacing", it) }
        return changed
    }

    /** The editor's paragraphs with what it does not hold carried over from [old]: each paragraph's own formatting, headings' runs, fonts (Words' and the PDF's texts). */
    fun fromEditorKeeping(old: List<DocPara>, rich: RichTextDocument, basePt: Float, pictures: Map<String, DocImage> = emptyMap()): List<DocPara> =
        OfficeFormats.carryFonts(old, keepHeadingRuns(old, OfficeFormats.carryKeep(old, fromEditor(rich, basePt, pictures))))

    fun fromEditor(rich: RichTextDocument, basePt: Float, pictures: Map<String, DocImage> = emptyMap()): List<DocPara> {
        val scale = BASE_SP / basePt
        return rich.blocks.map { b ->
            val cuts = sortedSetOf(0, b.text.length)
            b.spans.forEach { cuts += it.range.first.coerceIn(0, b.text.length); cuts += (it.range.last + 1).coerceIn(0, b.text.length) }
            val runs = cuts.toList().zipWithNext().filter { (a, z) -> z > a }.map { (a, z) ->
                var r = DocRun(b.text.substring(a, z))
                // Slant drawn for the paragraph's style, not the letters' own.
                val styleSlant = b.spans.any { it is RichTextSpanMark.Shadow && a in it.range && it.argb == STYLE_SLANT }
                for (m in b.spans) if (a in m.range) r = when (m) {
                    is RichTextSpanMark.Bold -> r.copy(bold = true)
                    is RichTextSpanMark.FontWeight -> if (m.weight == STYLE_WEIGHT) r else if (m.weight == UNBOLD_WEIGHT) r.copy(unbold = true) else r.copy(bold = m.weight >= 600)
                    is RichTextSpanMark.Shadow -> if (m.argb == UNSLANT) r.copy(unitalic = true) else r
                    is RichTextSpanMark.Italic -> if (styleSlant) r else r.copy(italic = true)
                    is RichTextSpanMark.Underline -> r.copy(underline = true)
                    is RichTextSpanMark.Strikethrough -> r.copy(strike = true)
                    // A picture's place-holder size is no letter's (typed over a chosen picture, the letters took it: a 127 pt "j").
                    is RichTextSpanMark.FontSize -> if (spOf(m.size)?.let(::isPictureSp) == true) r
                        else r.copy(sizePt = spOf(m.size)?.let { it / scale }?.takeIf { !isStyleSize(it) }) // the style's: none of its own; rounded below
                    is RichTextSpanMark.BaselineShift -> r.copy(script = if (m.multiplier > 0) 1 else if (m.multiplier < 0) -1 else 0)
                    is RichTextSpanMark.TextColor -> if ((m.argb ushr 24).let { it == STYLE_ALPHA || it == CHANGE_ALPHA }) r else r.copy(color = (m.argb and 0xFFFFFF).toInt())
                    is RichTextSpanMark.Highlight -> r.copy(highlight = (m.argb and 0xFFFFFF).toInt())
                    is RichTextSpanMark.Link -> r.copy(link = m.url)
                    is RichTextSpanMark.Image -> pictures[m.url.removePrefix(PICTURE_URL)]?.let { img ->
                        return@map (pictureRuns[img.key] ?: DocRun(OBJECT_MARK.toString())).copy(text = OBJECT_MARK.toString(), image = img)
                    } ?: r
                    else -> r
                }
                val shown = r.sizePt
                r.copy(sizePt = when {
                    shown == null -> null
                    r.script == 0 -> (shown * 2).roundToInt() / 2f
                    kotlin.math.abs(shown - basePt * SCRIPT_SIZE_OWN) < 0.001f * shown -> null // the document's own size
                    else -> ((shown / SCRIPT_SIZE) * 2).roundToInt() / 2f
                })
            }
            val list = (b.type as? RichTextBlockType.ListItem)?.let { DocList(it.ordered, it.indent.coerceIn(0, 8), it.startNumber.takeIf { _ -> it.ordered }) }
            val align = if (b.textDirection == STYLE_ALIGNED) DocAlign.Start // its style's
                else when (b.textAlign) { TextAlign.Center -> DocAlign.Center; TextAlign.End, TextAlign.Right -> DocAlign.End; TextAlign.Justify -> DocAlign.Justify; else -> DocAlign.Start }
            // A picture's place-holder letter without its picture (half undone): not a letter of the document.
            val letters = runs.map { if (it.image == null && EDITOR_PICTURE_CHAR in it.text) it.copy(text = it.text.replace(EDITOR_PICTURE_CHAR.toString(), "")) else it }
            DocPara(OfficeFormats.tidy(letters), b.headingLevel.coerceIn(0, 6), list, align) to b.isLineBreak
        }.fold(ArrayList<DocPara>()) { paras, (p, afterBreak) ->
            val prev = paras.lastOrNull()
            if (afterBreak && prev != null) paras[paras.size - 1] = prev.copy(runs = OfficeFormats.tidy(prev.runs + breakRun(prev.runs, p.runs) + p.runs))
            else paras += p
            paras
        }.flatMap { p ->
            // A table's character: the table again, a paragraph of its own; text typed beside it, paragraphs beside it.
            if (p.runs.none { it.image?.table != null }) return@flatMap listOf(p)
            val out = ArrayList<DocPara>(); var run = ArrayList<DocRun>()
            fun text() { if (run.any { it.text.isNotBlank() }) out += p.copy(runs = OfficeFormats.tidy(run), list = null); run = ArrayList() }
            for (r in p.runs) { val t = r.image?.table; if (t != null) { text(); out += tablePara(t) } else run += r }
            text()
            out
        }
    }

    /**
     * The paragraphs (as fromEditor counts them) the text from [start] to [end] touches. The editor's text is its
     * blocks one after another, a space between, a list item's "• " or "1. " before it: each block is found in turn.
     */
    fun paragraphsAt(text: String, rich: RichTextDocument, start: Int, end: Int): Set<Int> {
        var n = -1
        val out = HashSet<Int>()
        blockSpans(text, rich) { k, from, to -> if (!rich.blocks[k].isLineBreak) n++; if (start <= to && end >= from) out += n }
        return out
    }
    /**
     * Clear formatting (Word's eraser; LibreOffice's "clear direct formatting"): the letters' own looks taken off the
     * text from [start] to [end] — bold, italic, underline, strike, size, super/subscript, colour, highlight. What their
     * paragraph's style draws, links and pictures stay. Shown again through the document, the style's look comes back
     * where the letters' own was.
     */
    fun clearFormatting(text: String, rich: RichTextDocument, start: Int, end: Int, basePt: Float): RichTextDocument {
        val scale = BASE_SP / basePt
        val blocks = rich.blocks.toMutableList()
        blockSpans(text, rich) { k, from, to ->
            val s = (start - from).coerceAtLeast(0); val e = (end - from).coerceAtMost(to - from)
            if (e <= s) return@blockSpans
            val b = rich.blocks[k]
            val pictures = b.spans.filterIsInstance<RichTextSpanMark.Image>().map { it.range }.toSet()
            val slanted = b.spans.filter { it is RichTextSpanMark.Shadow && it.argb == STYLE_SLANT }.map { it.range }.toSet()
            fun own(m: RichTextSpanMark) = m.range !in pictures && when (m) {
                is RichTextSpanMark.Bold, is RichTextSpanMark.Underline, is RichTextSpanMark.Strikethrough,
                is RichTextSpanMark.BaselineShift, is RichTextSpanMark.Highlight -> true
                is RichTextSpanMark.FontWeight -> m.weight != STYLE_WEIGHT
                is RichTextSpanMark.Shadow -> m.argb == UNSLANT
                is RichTextSpanMark.Italic -> m.range !in slanted
                is RichTextSpanMark.FontSize -> spOf(m.size)?.let { !isStyleSize(it / scale) } ?: false
                is RichTextSpanMark.TextColor -> (m.argb ushr 24).let { it != STYLE_ALPHA && it != CHANGE_ALPHA }
                else -> false
            }
            blocks[k] = b.copy(spans = b.spans.flatMap { m ->
                if (!own(m) || m.range.last < s || m.range.first >= e) listOf(m)
                else listOfNotNull((m.range.first until s).takeIf { !it.isEmpty() }?.let { m.withRange(it) },
                    (e..m.range.last).takeIf { !it.isEmpty() }?.let { m.withRange(it) })
            })
        }
        return rich.copy(blocks = blocks)
    }
    /** [label]: English, for Slides' menu; [text]: the shown words. */
    enum class Case(val label: String, @androidx.annotation.StringRes val text: Int) { Sentence("Sentence case.", R.string.words_case_sentence), Lower("lowercase", R.string.words_case_lower), Upper("UPPERCASE", R.string.words_case_upper), Words("Capitalise Each Word", R.string.words_case_words) }

    /** [s] in [case], letter for letter (so each keeps its look); a letter whose case is longer (ß → SS) stays as it was. */
    fun inCase(s: String, case: Case, sentenceStart: Boolean, locale: java.util.Locale = java.util.Locale.getDefault()): String {
        val lower = s.lowercase(locale).takeIf { it.length == s.length } ?: s.map { c -> c.lowercase(locale).singleOrNull() ?: c }.joinToString("")
        fun up(c: Char) = c.toString().uppercase(locale).singleOrNull() ?: c
        // Greek in capitals has no accent (the dialytika stays): ΣΠΙΤΙ, ΑΫΠΝΙΑ — Latin capitals keep theirs.
        fun capital(c: Char): Char {
            if (Character.UnicodeScript.of(c.code) != Character.UnicodeScript.GREEK) return up(c)
            val bare = java.text.Normalizer.normalize(java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD)
                .replace("\u0301", "").replace("\u0342", "").uppercase(locale), java.text.Normalizer.Form.NFC)
            return bare.singleOrNull() ?: up(c)
        }
        return when (case) {
            Case.Lower -> lower
            Case.Upper -> s.map(::capital).joinToString("")
            Case.Words, Case.Sentence -> buildString {
                var start = if (case == Case.Words) true else sentenceStart
                lower.forEachIndexed { i, c ->
                    append(if (start && c.isLetter()) up(c) else c)
                    if (c.isLetter()) start = false
                    if (case == Case.Words && !c.isLetterOrDigit() && c != '\'' && c != '’') start = true
                    if (case == Case.Sentence && c in ".!?;") start = true // ; is the Greek question mark
                }
            }
        }
    }

    /** Change case (Word's Aa) on the text from [start] to [end]: the letters change, their looks stay. */
    fun changeCase(text: String, rich: RichTextDocument, start: Int, end: Int, case: Case): RichTextDocument {
        val blocks = rich.blocks.toMutableList()
        blockSpans(text, rich) { k, from, to ->
            val s = (start - from).coerceAtLeast(0); val e = (end - from).coerceAtMost(to - from)
            if (e <= s) return@blockSpans
            val b = rich.blocks[k]
            // A sentence starts at the paragraph's start, or after . ! ? (then spaces) before the chosen text.
            val sentenceStart = b.text.substring(0, s).trimEnd().let { it.isEmpty() || it.last() in ".!?;" }
            blocks[k] = b.copy(text = b.text.substring(0, s) + inCase(b.text.substring(s, e), case, sentenceStart) + b.text.substring(e))
        }
        return rich.copy(blocks = blocks)
    }

    private fun RichTextSpanMark.withRange(r: IntRange): RichTextSpanMark = when (this) {
        is RichTextSpanMark.Bold -> copy(range = r)
        is RichTextSpanMark.Underline -> copy(range = r)
        is RichTextSpanMark.Strikethrough -> copy(range = r)
        is RichTextSpanMark.BaselineShift -> copy(range = r)
        is RichTextSpanMark.Highlight -> copy(range = r)
        is RichTextSpanMark.FontWeight -> copy(range = r)
        is RichTextSpanMark.Italic -> copy(range = r)
        is RichTextSpanMark.FontSize -> copy(range = r)
        is RichTextSpanMark.TextColor -> copy(range = r)
        else -> this
    }

    /** The editor's block the text at [at] is in. */
    fun blockAt(text: String, rich: RichTextDocument, at: Int): Int? {
        var found: Int? = null
        blockSpans(text, rich) { k, from, to -> if (found == null && at in from..to) found = k }
        return found
    }
    /**
     * Each editor block's place: its span in the editor's text, its paragraph, and where in that paragraph's own text
     * it starts (a line after a break starts after the break's letter).
     */
    data class BlockPlace(val from: Int, val to: Int, val para: Int, val at: Int)
    fun blockPlaces(text: String, rich: RichTextDocument): List<BlockPlace> {
        val out = ArrayList<BlockPlace>()
        var n = -1; var at = 0
        blockSpans(text, rich) { k, from, to ->
            if (!rich.blocks[k].isLineBreak) { n++; at = 0 } else at++
            out += BlockPlace(from, to, n, at)
            at += to - from
        }
        return out
    }
    /** Paragraphs [paras] made a list of the other kind (Bullets on a numbered item, Numbering on a bulleted one), at their own level. */
    fun listKind(doc: OfficeDoc, paras: Set<Int>, ordered: Boolean): OfficeDoc = doc.copy(paras = doc.paras.mapIndexed { i, p ->
        if (i !in paras || p.table != null) p else p.copy(list = p.list?.copy(ordered = ordered, start = null) ?: DocList(ordered, 0))
    })
    /** The letters [from]..[to] of the editor's text as (paragraph, from, to) in the paragraphs' own text. */
    fun paragraphSpans(places: List<BlockPlace>, from: Int, to: Int): List<Triple<Int, Int, Int>> = places.mapNotNull { b ->
        val a = maxOf(from, b.from); val z = minOf(to, b.to)
        if (z <= a) null else Triple(b.para, b.at + a - b.from, b.at + z - b.from)
    }
    /** The editor's text where letters [a]..[b] of paragraph [para] are (the parts on each of its lines). */
    fun editorSpans(places: List<BlockPlace>, para: Int, a: Int, b: Int): List<IntRange> = places.filter { it.para == para }.mapNotNull { p ->
        val x = maxOf(a, p.at); val y = minOf(b, p.at + (p.to - p.from))
        if (y <= x) null else (p.from + x - p.at) until (p.from + y - p.at)
    }

    /** Where each paragraph starts in the editor's text (a line after a break is still its paragraph). */
    fun paragraphStarts(text: String, rich: RichTextDocument): List<Int> {
        val out = ArrayList<Int>()
        blockSpans(text, rich) { k, from, _ -> if (!rich.blocks[k].isLineBreak) out += from }
        return out
    }
    /** Where in the editor's text the block the text at [at] is in stands. */
    fun blockRange(text: String, rich: RichTextDocument, at: Int): Pair<Int, Int>? {
        var found: Pair<Int, Int>? = null
        blockSpans(text, rich) { _, from, to -> if (found == null && at in from..to) found = from to to }
        return found
    }
    private fun blockSpans(text: String, rich: RichTextDocument, each: (Int, Int, Int) -> Unit) {
        var pos = 0
        rich.blocks.forEachIndexed { k, b ->
            // A picture or table is one character in the block and another on the screen.
            val needle = b.text.replace(OBJECT_MARK, EDITOR_PICTURE_CHAR)
            // An empty list item is after its number, not on it ("" is found anywhere): its separator follows the
            // number, which ends at a space — Writer's numbers ("2.") have none of their own, the library's ("2. ") one.
            // shortcut: a number whose own text has a space ("Chapter 1") is passed only to that space.
            val emptyItem = if (needle.isEmpty() && b.type is RichTextBlockType.ListItem) text.indexOf(' ', pos).takeIf { it >= 0 }?.let { sep ->
                val next = rich.blocks.getOrNull(k + 1)
                val nextStartsWithSpace = next == null || next.text.startsWith(' ') || (next.text.isEmpty() && next.type !is RichTextBlockType.ListItem)
                if (text.getOrNull(sep + 1) == ' ' && !nextStartsWithSpace) sep + 1 else sep
            } else null
            val from = emptyItem
                ?: text.indexOf(needle, pos).takeIf { it >= 0 }
                ?: text.indexOf(needle.replace('\t', EDITOR_TAB_SPACE), pos).takeIf { it >= 0 }
                // a picture's mark the editor keeps as it was given (a box alone in its paragraph: the user's "test", 18 Oct — not found, the
                // box was measured from the paragraph before and climbed over its words)
                ?: text.indexOf(b.text.replace('\t', EDITOR_TAB_SPACE), pos).takeIf { it >= 0 } ?: return WriterLog.w("editor block $k not found after $pos")
            each(k, from, from + b.text.length)
            pos = from + b.text.length + 1
        }
    }

    /** The size (sp) a picture's place-holder letter is drawn at: its own, marked by its fraction (.371) as no letter's. */
    fun pictureSp(size: Float) = kotlin.math.floor(size) + PICTURE_FRACTION
    fun isPictureSp(sp: Float) = kotlin.math.abs(sp - kotlin.math.floor(sp) - PICTURE_FRACTION) < 0.0005f
    private const val PICTURE_FRACTION = 0.371f
    /** A size (pt) drawn for a paragraph's style: a few hundredths over half points. */
    fun isStyleSize(pt: Float) = ((pt - STYLE_SIZE) * 2).let { kotlin.math.abs(it - it.roundToInt()) < 0.01f }
    /** Whether a look has a paragraph style's drawn marks in it, and those marks alone (to take them off). */
    fun styleDrawn(s: SpanStyle, basePt: Float) = drawnPart(s, basePt) != SpanStyle()
    fun drawnPart(s: SpanStyle, basePt: Float) = SpanStyle(
        fontSize = s.fontSize.takeIf { it.isSp && isStyleSize(it.value / (BASE_SP / basePt)) } ?: TextUnit.Unspecified,
        fontWeight = s.fontWeight?.takeIf { it.weight == STYLE_WEIGHT },
        color = s.color.takeIf { it != Color.Unspecified && (it.toArgb().toLong() ushr 24 and 0xFF) == STYLE_ALPHA } ?: Color.Unspecified,
        shadow = s.shadow?.takeIf { it.color.toArgb().toLong() and 0xFFFFFFFFL == STYLE_SLANT },
        fontStyle = if (s.shadow?.let { it.color.toArgb().toLong() and 0xFFFFFFFFL == STYLE_SLANT } == true) s.fontStyle else null,
    )


    /** How big a table stands in the editor: exactly the same layout as on its page. */
    fun tableSize(doc: OfficeDoc, table: DocTable, styles: DocStyles) = OfficePages.tableSize(doc, table, styles, styles.page.textWidth)
    /** The text's width (pt) when the editor shows the page itself; null: it fits the screen. */
    var pageWidthPt: Float? = null

    /** How wide the editor's text is (sp), so a table is laid out to fit it; set by the screen. */
    var editorWidthSp = 320f

    fun cellText(cell: DocCell) = cell.paras.joinToString("\n") { it.text }

    /**
     * A cell's text edited in its box: only the stretch that changed is replaced, so every other word keeps its own
     * look and every paragraph its own style (asked 2026-09-28: editing one letter made the whole cell look like its
     * first word). Text typed over text takes the look of what it replaces, else of the letter before it; a new line is a new paragraph like the one it
     * was typed in.
     */
    fun withCellText(cell: DocCell, text: String): DocCell {
        val old = cellText(cell)
        if (old == text) return cell
        var pre = 0
        while (pre < old.length && pre < text.length && old[pre] == text[pre]) pre++
        var suf = 0
        while (suf < old.length - pre && suf < text.length - pre && old[old.length - 1 - suf] == text[text.length - 1 - suf]) suf++
        val paras = cell.paras.ifEmpty { listOf(DocPara(listOf(DocRun("")))) }
        // Where a place in the cell's text falls: which paragraph, how far into it.
        val starts = paras.runningFold(0) { at, p -> at + p.text.length + 1 }
        fun locate(pos: Int) = paras.indices.first { pos <= starts[it] + paras[it].text.length }.let { it to pos - starts[it] }
        fun cut(runs: List<DocRun>, from: Int, to: Int): List<DocRun> {
            var at = 0
            return runs.mapNotNull { r -> val s = at; at += r.text.length
                val a = maxOf(from, s); val b = minOf(to, at)
                if (a < b) r.copy(text = r.text.substring(a - s, b - s)) else null }
        }
        val (a, ao) = locate(pre)
        val (b, bo) = locate(old.length - suf)
        // The look of the letter replaced, or (only typed) of the letter before.
        val runs = paras[a].runs
        val letter = if (b > a || bo > ao) ao else ao - 1
        var at = 0
        val look = (runs.firstOrNull { r -> at += r.text.length; letter >= 0 && letter < at && r.image == null }
            ?: runs.firstOrNull { it.image == null } ?: DocRun("")).copy(image = null)
        fun para(p: DocPara, runs: List<DocRun>) = p.copy(runs = OfficeFormats.tidy(runs).ifEmpty { listOf(look.copy(text = "")) })
        val lines = text.substring(pre, text.length - suf).split('\n')
        val left = cut(paras[a].runs, 0, ao); val right = cut(paras[b].runs, bo, paras[b].text.length)
        val middle = if (lines.size == 1) listOf(para(paras[a], left + look.copy(text = lines[0]) + right))
            else listOf(para(paras[a], left + look.copy(text = lines[0]))) +
                lines.subList(1, lines.size - 1).map { para(paras[a], listOf(look.copy(text = it))) } +
                para(paras[b], listOf(look.copy(text = lines.last())) + right)
        return cell.copy(paras = paras.subList(0, a) + middle + paras.subList(b + 1, paras.size))
    }

    /** The line break itself takes the look of the text after it (or before it, at a paragraph's end). */
    private fun breakRun(before: List<DocRun>, after: List<DocRun>) =
        (after.firstOrNull() ?: before.lastOrNull() ?: DocRun("")).copy(text = LINE_BREAK.toString(), image = null)

    /** Each line break with the look [breakRun] gives it — the look of an invisible character, so files are compared without it. */
    fun breaksLookAround(runs: List<DocRun>): List<DocRun> {
        val pieces = runs.flatMap { r -> if (LINE_BREAK !in r.text) listOf(r) else r.text.split(LINE_BREAK).flatMapIndexed { i, t -> listOfNotNull(if (i > 0) r.copy(text = LINE_BREAK.toString()) else null, r.copy(text = t).takeIf { t.isNotEmpty() }) } }
        return pieces.mapIndexed { i, r ->
            if (r.text != LINE_BREAK.toString()) r
            else breakRun(pieces.subList(0, i).filter { it.text != LINE_BREAK.toString() }, pieces.subList(i + 1, pieces.size).takeWhile { it.text != LINE_BREAK.toString() })
        }
    }

    /** A size in sp: the editor gives some (a heading's, carried on to the next line) relative to the text, in em. */
    fun spOf(size: TextUnit): Float? = when {
        size.isSp -> size.value
        size.isEm -> size.value * BASE_SP
        else -> null
    }

    /**
     * The editor draws every heading bold, so it cannot say which words of a heading the file has bold. A heading left
     * as it was keeps the file's own runs; an edited one takes its heading style's weight.
     */
    fun keepHeadingRuns(old: List<DocPara>, new: List<DocPara>): List<DocPara> {
        val unused = old.filter { it.heading > 0 }.groupBy { it.text }.mapValues { ArrayDeque(it.value) }
        return new.map { p ->
            if (p.heading == 0) return@map p
            val o = unused[p.text]?.removeFirstOrNull() ?: return@map p
            if (o.heading == p.heading && unbold(o.runs) == unbold(p.runs)) p.copy(runs = o.runs) else p
        }
    }
    private fun unbold(runs: List<DocRun>) = OfficeFormats.tidy(runs.map { it.copy(bold = false, font = null, comments = emptyList(), cite = null, change = null, note = null, control = null) })

    /** Paragraphs as they compare: a list's start number counts only where a list begins; bold in a heading, not at all. */
    fun comparable(paras: List<DocPara>) = paras.mapIndexed { i, p ->
        val firstOfList = p.list != null && (i == 0 || paras[i - 1].list == null)
        p.copy(runs = breaksLookAround(if (p.heading > 0) unbold(p.runs) else OfficeFormats.tidy(p.runs.map { it.copy(font = null, comments = emptyList(), cite = null, change = null, note = null, control = null) })).let(OfficeFormats::tidy), keep = null, list = p.list?.let { l -> l.copy(start = if (firstOfList && l.ordered && l.start != 1) l.start else null) })
    }
}

/** A document in Tetra's Files, for the Office home. */
/** [modified] read once: sorting by file.lastModified() asked the disk at every comparison (asked 17 Oct: Recents slow). */
internal data class OfficeFile(val file: File, val relative: String, val modified: Long = file.lastModified())

/** How many documents the Office home lists: the newest. */
private const val OFFICE_RECENT = 10 // of each kind, newest first (asked 17 Oct: 10 each, 40 in all, so the page opens faster; 20 before)

private fun isOfficeFile(name: String) = (OfficeKind.of(name) != null || LegacyDocs.isLegacy(name) || Sheets.isSheet(name) || Slides.isSlides(name) || Pdf.isPdf(name)) && !name.startsWith("~$")

private fun officeFile(root: File, f: File) = OfficeFile(f, runCatching { f.relativeTo(root).path }.getOrNull()?.takeIf { !it.startsWith("..") } ?: f.absolutePath)
private fun newestOfEach(files: List<OfficeFile>, n: Int) = files.groupBy { if (Pdf.isPdf(it.file.name)) 3 else if (Slides.isSlides(it.file.name)) 2 else if (Sheets.isSheet(it.file.name)) 1 else 0 }
    .values.flatMap { it.take(n) }

/** What the Office home's last look found: the newest of each kind and how many in all, in the app's cache (never synced). */
private object OfficeHomeCache {
    private fun file(c: Context) = File(c.cacheDir, "office-home.txt")
    fun read(c: Context, root: File): Pair<List<OfficeFile>, Int>? = runCatching {
        val lines = file(c).readLines()
        lines.drop(1).map(::File).filter { it.isFile }.map { officeFile(root, it) }.sortedByDescending { it.modified } to lines.first().toInt()
    }.getOrNull()
    fun write(c: Context, files: List<OfficeFile>, total: Int) = runCatching { file(c).writeText((listOf("$total") + files.map { it.file.absolutePath }).joinToString("\n")) }
}

internal fun officeFiles(root: File): List<OfficeFile> = root.walkTopDown()
    .onEnter { it == root || (!it.name.startsWith(".") && it.name != "Trash") }
    .filter { isOfficeFile(it.name) && it.isFile } // the name first: most files are not Office ones, and isFile asks the disk
    .map { OfficeFile(it, it.relativeTo(root).path) }
    .sortedByDescending { it.modified }
    .toList()

@Composable
internal fun OfficeHome(back: () -> Unit, hasAccess: Boolean, grant: () -> Unit, open: (String?) -> Unit, openSheet: (String) -> Unit, openSlides: (String) -> Unit, openPdf: (String) -> Unit) {
    val root = remember { TetraFolder.root(Environment.getExternalStorageDirectory()) }
    val context = LocalContext.current
    var files by remember { mutableStateOf<List<OfficeFile>?>(null) }
    OpenTiming("Office", files != null)
    var total by remember { mutableIntStateOf(0) }
    // Only the newest ones (asked 2026-09-28: all of them made the page heavy); the rest are in Files.
    // read again whenever the home shows (back from a document, Files or another app) and when something opens (the user, 18 Oct:
    // the list did not change after opening a file from the picker or from Files)
    var shown by remember { mutableIntStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val watch = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) shown++ }
        lifecycle.addObserver(watch); onDispose { lifecycle.removeObserver(watch) }
    }
    // the files opened first, newest first (however they were opened), then the newest of each kind, one list again by date
    fun shownOf(all: List<OfficeFile>, opened: List<File>): List<OfficeFile> {
        val first = opened.filter { isOfficeFile(it.name) }.take(OFFICE_RECENT).map { officeFile(root, it) }
        val seen = first.map { it.file.absolutePath }.toSet()
        return first + newestOfEach(all.filter { it.file.absolutePath !in seen }, OFFICE_RECENT).sortedByDescending { it.modified }
    }
    LaunchedEffect(hasAccess, shown, OfficeRecents.changes) {
        if (!hasAccess) return@LaunchedEffect
        // at once from what the last look found (the walk of the whole library took 2–3 s on the tablet, 25 Oct), then looked again
        if (files == null) withContext(Dispatchers.IO) { OfficeHomeCache.read(context, root)?.let { (kept, n) -> Triple(kept, n, OfficeRecents.list(context)) } }
            ?.let { (kept, n, opened) -> if (files == null) { files = shownOf(kept, opened); total = n } }
        withContext(Dispatchers.IO) { officeFiles(root) to OfficeRecents.list(context) }.let { (all, opened) ->
            total = all.size
            files = shownOf(all, opened)
            withContext(Dispatchers.IO) { OfficeHomeCache.write(context, newestOfEach(all, 2 * OFFICE_RECENT), all.size) }
        }
    }
    BackHandler { back() }
    var choosingTemplate by remember { mutableStateOf(false) }
    // each card's Open: Android's picker for that kind only (asked 17 Oct); what is chosen opens in its own app
    val photosScope = rememberCoroutineScope()
    val photosContext = LocalContext.current
    val photosToPdf = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        photosScope.launch {
            val made = withContext(Dispatchers.IO) { runCatching {
                val root = TetraFolder.root(android.os.Environment.getExternalStorageDirectory())
                val name = "Photos " + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm"))
                ScanFiles.savePdf(root, name, uris.size, into = "Documents") { i ->
                    // turned as the camera held it (ImageDecoder reads the photo's orientation), no wider than 2000 px
                    android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(photosContext.contentResolver, uris[i])) { dec, info, _ ->
                        val k = minOf(1f, 2000f / maxOf(info.size.width, info.size.height)); dec.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1))
                        dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                }.let { File(root, it) }
            } }
            made.onSuccess { f -> WriterLog.i("PDF: made from ${uris.size} photos"); openPdf(Uri.fromFile(f).toString()) }
                .onFailure { WriterLog.e("PDF: from photos", it); android.widget.Toast.makeText(photosContext, it.message ?: photosContext.getString(R.string.words_pdf_not_made), android.widget.Toast.LENGTH_LONG).show() }
        }
    }
    val pick = rememberOfficePicker { f -> Uri.fromFile(f).toString().let { if (Pdf.isPdf(f.name)) openPdf(it) else if (Slides.isSlides(f.name)) openSlides(it) else if (Sheets.isSheet(f.name)) openSheet(it) else open(it) } }
    var newPdfMenu by remember { mutableStateOf(false) }
    /** A new PDF in Documents, a blank A4 page, opened editing. */
    fun newPdf() {
        runCatching { Pdf.newBlank(File(root, "Documents")).also { JustMade.mark(it); WriterLog.i("PDF: a new blank PDF"); openPdf(Uri.fromFile(it).toString()) } }
            .onFailure { WriterLog.e("PDF: a new PDF could not be made", it); android.widget.Toast.makeText(context, context.getString(R.string.words_pdf_not_made), android.widget.Toast.LENGTH_LONG).show() }
    }
    /** A new presentation in Documents, opened editing: the app's empty deck (a title slide), or one of [template]. */
    fun newPresentation(template: File?) {
        runCatching {
            val dir = File(root, "Documents").apply { mkdirs() }
            val f = generateSequence(1) { it + 1 }.map { File(dir, if (it == 1) "Presentation.pptx" else "Presentation ($it).pptx") }.first { !it.exists() }
            if (template != null) SlidesTemplates.make(template, f) else f.writeBytes(context.assets.open("blank.pptx").use { it.readBytes() })
            JustMade.mark(f); WriterLog.i("Slides: a new presentation${template?.let { " from a template" } ?: ""}")
            openSlides(Uri.fromFile(f).toString())
        }.onFailure { WriterLog.e("Slides: a new presentation could not be made", it) }
    }
    val blank = stringResource(R.string.words_blank)
    if (choosingTemplate) {
        val options = remember { listOf<Pair<File?, SlideTheme?>>(null to SlidesThemes.BUILT_IN.first().copy(name = blank)) + SlidesTemplates.files().map { it to SlidesThemes.fromFile(it) } }
        androidx.compose.ui.window.Dialog(onDismissRequest = { choosingTemplate = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.widthIn(max = 720.dp).padding(12.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(20.dp)) {
                    Text(stringResource(R.string.words_new_presentation), style = MaterialTheme.typography.titleLarge)
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 2) {
                            options.forEach { (f, t) -> ThemeCard(t ?: SlidesThemes.BUILT_IN.first().copy(name = f?.nameWithoutExtension ?: blank), false, Modifier.weight(1f)) { choosingTemplate = false; newPresentation(f) } }
                            if (options.size % 2 == 1) Spacer(Modifier.weight(1f)) // a last one alone keeps its size
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = { choosingTemplate = false }) { Text(stringResource(R.string.common_cancel)) } }
                }
            }
        }
    }
    val metadata = remember(context) { DriveMetadata(context) }
    fun loadFavs() = metadata.favorites(root).filter { !it.isDirectory && isOfficeFile(it.file.name) }
        .map { OfficeFile(it.file, it.relativePath) }
    var favs by remember { mutableStateOf<List<OfficeFile>?>(null) } // null while they load
    LaunchedEffect(hasAccess, shown) { if (hasAccess) favs = withContext(Dispatchers.IO) { loadFavs() } } // a heart set in a document, too
    @Composable fun officeRow(f: OfficeFile) {
        val sheet = Sheets.isSheet(f.file.name); val deck = Slides.isSlides(f.file.name); val pdf = Pdf.isPdf(f.file.name)
        val fav = favs.orEmpty().any { it.relative == f.relative }
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().clickable { Uri.fromFile(f.file).toString().let { if (pdf) openPdf(it) else if (deck) openSlides(it) else if (sheet) openSheet(it) else open(it) } }) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(painterResource(if (deck) R.drawable.ic_play else if (sheet) R.drawable.ic_table else R.drawable.ic_file_text), contentDescription = null,
                    tint = if (pdf) PDF_RED else if (deck) POWERPOINT_ORANGE else if (sheet) EXCEL_GREEN else WORD_BLUE, modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.file.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(f.relative.substringBeforeLast('/', stringResource(R.string.app_files)).let { "$it · " } + java.text.DateFormat.getDateInstance().format(java.util.Date(f.modified)),
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                androidx.compose.material3.IconButton(onClick = { runCatching { metadata.setFavorite(f.relative, !fav) }; favs = loadFavs() }) {
                    Icon(painterResource(if (fav) R.drawable.ic_favorite else R.drawable.ic_favorite_border), contentDescription = if (fav) stringResource(R.string.words_unfavourite) else stringResource(R.string.words_favourite),
                        tint = androidx.compose.material3.LocalContentColor.current) // a white heart, filled when it is one (asked 17 Oct)
                }
            }
        }
    }
        // Favourites beside Recents (asked 17 Oct): a tablet 60/40, a phone Favourites first. Files' own favourites, so a
        // heart here is one in Files and on the computer, and a moved file keeps it; a missing one is dropped by favorites()
        val narrow = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600
        // each list's title apart from its rows, so on a tablet both titles share a row and both lists start level (asked 17 Oct)
        val recentsTitle: @Composable (Modifier) -> Unit = { m ->
            Column(m.padding(top = 14.dp, bottom = 2.dp)) {
                Text(stringResource(R.string.words_recent_documents), style = MaterialTheme.typography.titleMedium)
                if (total > (files?.size ?: 0)) Text(stringResource(R.string.words_recent_about, OFFICE_RECENT, total), style = MaterialTheme.typography.bodySmall)
            }
        }
        val recents: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!hasAccess) {
                    Text(stringResource(R.string.words_needs_access), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = grant) { Text(stringResource(R.string.words_allow_access)) }
                } else when (val list = files) {
                    null -> LoadingSpinner()
                    else -> if (list.isEmpty()) Text(stringResource(R.string.words_no_office_files), style = MaterialTheme.typography.bodyMedium)
                        else list.forEach { f -> androidx.compose.runtime.key(f.relative) { officeRow(f) } }
                }
            }
        }
        val favouritesTitle: @Composable (Modifier) -> Unit = { m ->
            Column(m.padding(top = 14.dp, bottom = 2.dp)) {
                Text(stringResource(R.string.words_favourites), style = MaterialTheme.typography.titleMedium)
                if (hasAccess && favs?.isEmpty() == true) Text(stringResource(R.string.words_favourites_empty), style = MaterialTheme.typography.bodySmall)
            }
        }
        val favourites: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val list = favs
                if (list == null) { if (hasAccess) LoadingSpinner() } else list.forEach { f -> androidx.compose.runtime.key(f.relative) { officeRow(f) } }
            }
        }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 136.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                // a phone: two by two (four in a row left no room for their names); a tablet: one row
                val narrow = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600
                // (plain rows: a FlowRow asks the cards' BoxWithConstraints for intrinsic sizes, which crashes)
                val cards = listOf<@Composable (Modifier) -> Unit>(
                    { m -> NewDocCard(stringResource(R.string.app_words), stringResource(R.string.words_new_document), stringResource(R.string.words_about_words), WORD_BLUE, m, { open(null) }) { pick(OfficePick.Words) } },
                    { m -> NewDocCard(stringResource(R.string.app_tables), stringResource(R.string.words_new_spreadsheet), stringResource(R.string.words_about_tables), EXCEL_GREEN, m, { openSheet(NEW_SHEET) }) { pick(OfficePick.Tables) } },
                    // a new presentation: the app's empty deck (a title slide) in Documents, opened editing
                    // with templates in Documents/Templates: which to start from first (asked 16 Oct: a school's, a company's)
                    { m -> NewDocCard(stringResource(R.string.app_slides), stringResource(R.string.words_new_presentation), stringResource(R.string.words_about_slides), POWERPOINT_ORANGE, m, {
                        if (SlidesTemplates.files().isNotEmpty()) choosingTemplate = true else newPresentation(null)
                    }) { pick(OfficePick.Slides) } },
                    // a new PDF (asked 24 Oct: New opened the photo picker, taken for a broken Open): a blank A4 page, or photos a page each
                    { m -> Box(m) {
                        NewDocCard(stringResource(R.string.app_pdf), stringResource(R.string.words_new_pdf), stringResource(R.string.words_about_pdf), PDF_RED, Modifier.fillMaxWidth(), { newPdfMenu = true }) { pick(OfficePick.Pdf) }
                        DropdownMenu(newPdfMenu, { newPdfMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.words_blank_page_a4)) }, leadingIcon = { Icon(painterResource(R.drawable.ic_note_add), null) }, onClick = { newPdfMenu = false; newPdf() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.words_from_photos)) }, leadingIcon = { Icon(painterResource(R.drawable.ic_gallery), null) }, onClick = { newPdfMenu = false; photosToPdf.launch(arrayOf("image/*")) })
                        }
                    } },
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    cards.chunked(if (narrow) 2 else 4).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { row.forEach { it(Modifier.weight(1f)) } } }
                }
            }
            // a white line between the two lists, short of their ends (asked 17 Oct), as the cards' New | Open line
            if (narrow) {
                item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { favouritesTitle(Modifier); favourites(Modifier) } }
                item { Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) { Box(Modifier.fillMaxWidth(0.6f).height(1.dp).background(Color.White.copy(alpha = 0.5f))) } }
                item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { recentsTitle(Modifier); recents(Modifier) } }
            } else {
                item { Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { recentsTitle(Modifier.weight(0.6f)); Spacer(Modifier.width(1.dp)); favouritesTitle(Modifier.weight(0.4f)) } }
                item { Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    recents(Modifier.weight(0.6f))
                    Box(Modifier.padding(top = 24.dp, bottom = 24.dp).width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.5f)))
                    favourites(Modifier.weight(0.4f))
                } }
            }
        }
        ModuleHeader(stringResource(R.string.app_office), back, Modifier.align(Alignment.TopCenter), art = R.drawable.office_art)
    }
}

/** A spinner in the middle of a list's own area while it loads (asked 17 Oct: Recents take their time). */
@Composable private fun LoadingSpinner() = Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }

/** An Office app's card: New on the left, Open (the picker for its kind of file, asked 17 Oct) on the right; one without New opens all over. */
@Composable private fun NewDocCard(app: String, label: String, about: String, color: Color, modifier: Modifier, new: (() -> Unit)?, open: () -> Unit) = Surface(
    shape = MaterialTheme.shapes.extraLarge, color = color, contentColor = Color.White, modifier = modifier.height(104.dp),
) {
    Row {
        // a wide card (a tablet): the name bigger, and what it opens (asked 16 Oct: the cards have room there)
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().clickable(onClick = new ?: open)) {
            val wide = maxWidth >= 200.dp
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Icon(painterResource(if (new != null) R.drawable.ic_note_add else R.drawable.ic_folder), contentDescription = null, modifier = Modifier.size(24.dp))
                Column {
                    Text(app, style = if (wide) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (wide) Text(about,
                        style = MaterialTheme.typography.labelSmall, color = androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (new != null) {
            Box(Modifier.padding(vertical = 16.dp).width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.3f)))
            Column(Modifier.width(60.dp).fillMaxHeight().clickable(onClick = open), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(painterResource(R.drawable.ic_folder), contentDescription = null, modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.common_open), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Unsaved changes are kept as a file in the app's storage while typing, so closing the app never loses them. */
/**
 * This session's history (asked 2026-10-14: a live copy, as Word and LibreOffice keep one, gone on leaving): the working
 * copy as it was every few minutes, the last 10, in Writer's own storage. Left behind only when Writer did not close.
 */
private fun sessionDir(context: Context, key: String) = File(File(context.filesDir, "writer-session"), key.hashCode().toUInt().toString())
internal fun sessionVersions(context: Context, key: String): List<File> =
    sessionDir(context, key).listFiles()?.filter { it.isFile }?.sortedByDescending { it.name.substringBefore('.').toLongOrNull() ?: 0 }.orEmpty()
private fun keepSessionCopy(context: Context, key: String, kind: OfficeKind, bytes: ByteArray) {
    val last = sessionVersions(context, key).firstOrNull()?.name?.substringBefore('.')?.toLongOrNull() ?: 0
    if (System.currentTimeMillis() - last < 2 * 60_000) return
    val dir = sessionDir(context, key).apply { mkdirs() }
    File(dir, "${System.currentTimeMillis()}.${kind.extension}").writeBytes(bytes)
    sessionVersions(context, key).drop(10).forEach { it.delete() }
}

private fun draftFile(context: Context, key: String, kind: OfficeKind) =
    File(File(context.filesDir, "writer-drafts"), "${key.hashCode().toUInt()}.${kind.extension}")

internal fun writeWriterDraft(file: File, bytes: ByteArray) {
    check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "Could not make the draft folder." }
    val temp = File(file.parentFile, ".${file.name}.saving")
    temp.writeBytes(bytes)
    check(temp.renameTo(file)) { "Could not replace the recovery draft." }
}

/** A document's earlier versions: a folder per document (by its path), a file per version named by its time. */
internal fun versionsDir(context: Context, file: File) = File(File(context.filesDir, "writer-history"), file.absolutePath.hashCode().toUInt().toString())

internal fun versions(context: Context, file: File): List<File> =
    versionsDir(context, file).listFiles()?.filter { it.isFile }?.sortedByDescending { it.name.substringBefore('.').toLongOrNull() ?: 0 }.orEmpty()

/** Before Writer saves over a document, the file as it was is kept (the last 3), so a save can be undone. */
internal fun keepPrevious(context: Context, file: File) {
    val dir = versionsDir(context, file).apply { mkdirs() }
    file.copyTo(File(dir, "${System.currentTimeMillis()}.${file.extension}"), overwrite = true)
    versions(context, file).drop(3).forEach { it.delete() }
}

/** A name not yet taken in [dir]: "name (copy).docx", then "name (copy 2).docx"… */
internal fun freeCopyName(dir: File, stem: String, ext: String): File =
    generateSequence(1) { it + 1 }.map { File(dir, if (it == 1) "$stem (copy).$ext" else "$stem (copy $it).$ext") }.first { !it.exists() }

private enum class WriterTab(@androidx.annotation.StringRes val label: Int) { File(R.string.words_tab_file), Home(R.string.words_tab_home), Insert(R.string.words_tab_insert), Draw(R.string.words_tab_draw), Design(R.string.words_tab_design), Layout(R.string.words_tab_layout), References(R.string.words_tab_references), Review(R.string.words_tab_review), View(R.string.words_tab_view), Picture(R.string.words_tab_image), Table(R.string.words_tab_table), Selection(R.string.words_tab_selection) }
private data class TableCellRef(val key: String, val row: Int, val cell: Int)
/** A cell being typed in: [cell] holds its looks as they are changed, [value] the text in the box. */
private data class TableCellEdit(val ref: TableCellRef, val value: TextFieldValue, val cell: DocCell)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WriterScreen(back: () -> Unit, open: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { RichTextState() }
    var uri by remember { mutableStateOf(open) }
    /** The file as of now ([uri] changes on Save a copy and a first Save; long-lived effects must not keep an old one). */
    fun currentFile(): File? = uri?.let { Uri.parse(it) }?.takeIf { it.scheme == "file" }?.path?.let(::File)
    val file: File? = currentFile()
    RememberOpened(file) // the Office home's Recents
    var kind by remember { mutableStateOf(OfficeKind.of(open ?: "") ?: OfficeKind.Docx) }
    var name by remember { mutableStateOf(file?.name ?: "New document.docx") }
    var original by remember { mutableStateOf<ByteArray?>(null) }
    var styleEdits by remember { mutableIntStateOf(0) } // counted, so the draft is kept after a style (or a picture: its wrap, size, place) changes
    var meta by remember { mutableStateOf(OfficeDoc(emptyList())) }
    var loaded by remember { mutableStateOf(false) }
    OpenTiming("Words", loaded)
    var failed by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(WriterTab.Home) }
    val textToolbar = remember { WriterTextToolbar() }
    var beforeSelection by remember { mutableStateOf<WriterTab?>(null) } // the tab to go back to when the words are let go
    var message by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) } // File › Rename
    var leaving by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var zoom by remember { mutableIntStateOf(100) }
    // A phone's Print layout (the user, 2026-09-30: "there are no pages", zoom "does not go beyond the screen"): the page
    // laid out whole and drawn [pageZoom] times the screen's width — pinched larger it goes past the screen and pans both
    // ways, as the pages view. View › Mobile view keeps the text to the screen instead (remembered).
    var pageZoom by remember { mutableFloatStateOf(1f) }
    var mobileView by remember { mutableStateOf(context.getSharedPreferences("writer", Context.MODE_PRIVATE).getBoolean("mobileView", false)) }
    // View › Reading mode (Word's Read Mode, Phase 2): no ribbon, the text reflowed bigger, nothing typed; its size remembered.
    var reading by remember { mutableStateOf(false) }
    var readingZoom by remember { mutableIntStateOf(context.getSharedPreferences("writer", Context.MODE_PRIVATE).getInt("readingZoom", 140)) }
    var finding by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var replacing by remember { mutableStateOf(false) }
    val draftKey = uri ?: "new"
    var saving by remember { mutableStateOf(false) }
    val draftMutex = remember { Mutex() }
    var autoSaveDue by remember { mutableIntStateOf(0) } // counted up by the draft's pause; saved by the effect after save()
    var versionKept by remember { mutableStateOf(false) } // this session's first save kept the file as it was opened
    var autoSave by remember { mutableStateOf(TextEditorStore(context).autoSave) }
    var cleanDoc by remember { mutableStateOf<OfficeDoc?>(null) }
    var cleanStyleEdits by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) } // a restored version is read in again
    var showVersions by remember { mutableStateOf(false) }
    val pictures = remember { HashMap<String, DocImage>() } // every picture in the editor, by its key
    remember { OfficeFonts.assets = context.applicationContext.assets; OfficeMath.measure = MathDrawable.measure(); WriterUnits.load(context); WriterBridge.documentHeadings() }
    remember { WriterBridge.pictureGlyph = android.graphics.Paint().apply { textSize = 100f }.measureText(OBJECT_MARK.toString()) / 100f } // the letter Display lays it as
    remember { WriterBridge.lineRatio = android.graphics.Paint().apply { textSize = 100f }.fontMetrics.let { it.descent - it.ascent } / 100f }
    remember { WriterBridge.letterRise = -android.graphics.Paint().apply { textSize = 100f }.fontMetrics.ascent / 100f }
    var packageParts by remember { mutableStateOf<Map<String, ByteArray>>(emptyMap()) } // the file's parts, for its pictures
    var documentStyles by remember { mutableStateOf<DocStyles>(DocStyles.Plain) }
    // A tablet edits on the page itself (asked 2026-09-29, as on a desktop): the page's width and margins at the
    // editor's text size, centred, the ruler above. A phone (or a page too wide) keeps the text to the screen's width.
    val sheet = documentStyles.page
    val paper = Color(0xFF000000 or (sheet.color ?: 0xFFFFFF).toLong()) // Design › Page colour, in the editor too
    val screen = LocalConfiguration.current.screenWidthDp.dp
    // A tablet shows a document's comments in a pane beside the page, as Word does (asked 2026-09-29): the page made
    // smaller when both would not fit.
    val tablet = screen >= 700.dp
    var panelHidden by remember { mutableStateOf(false) }
    val paneOn = tablet && meta.comments.isNotEmpty() && !panelHidden
    val perPt100 = with(LocalDensity.current) { (BASE_SP / meta.basePt).sp.toDp() }
    // A tablet keeps the page whole (a landscape page, the pane beside it): shown smaller rather than reflowed.
    val printPhone = !tablet && !mobileView && !reading
    val readingWidth = minOf(screen, 720.dp) // a tablet's reading column, not the whole screen's width
    val shownZoom = if (reading) readingZoom else if (printPhone) 100 else if (!tablet) zoom else minOf(zoom, ((screen - (if (paneOn) COMMENT_PANE + 12.dp else 0.dp) - 44.dp) / (documentStyles.page.width * perPt100) * 100).toInt()).coerceAtLeast(40)
    val perPt = with(LocalDensity.current) { (BASE_SP * shownZoom / 100f / meta.basePt).sp.toDp() }
    val onPage = !reading && (printPhone || screen >= 700.dp && sheet.width * perPt + 24.dp <= screen)
    WriterBridge.pageWidthPt = if (onPage) sheet.textWidth else null
    // Else the page's text is the screen less its margins (10 + 22 dp a side, below).
    WriterBridge.editorWidthSp = if (printPhone) sheet.textWidth * BASE_SP / meta.basePt
        else with(LocalDensity.current) { ((if (reading) readingWidth else screen) - 64.dp).toSp().value }
    // The phone's page drawn this many times smaller or larger than laid out: the screen's width at pageZoom 1.
    val pageScale = if (printPhone) (screen - 20.dp) / (sheet.width * perPt) * pageZoom else 1f
    // A document opens view only, as its pages (asked 2026-09-27, as LibreOffice); Edit switches to the editor.
    var viewing by remember { mutableStateOf(open != null && !JustMade.take(open)) } // a file just made with New: editing at once
    var pages by remember { mutableStateOf<List<LaidPage>?>(null) }
    var galleryViewing by remember { mutableStateOf<Boolean?>(null) } // a page gallery: whether the pages were shown before it
    var pagesIndex by remember { mutableStateOf<IntArray?>(null) } // the page each paragraph starts on, in [pages]
    var navOpen by remember { mutableStateOf(false) }
    var pagesAcross by remember { mutableIntStateOf(1) } // View › Two pages
    var fitWidth by remember { mutableStateOf(false) } // View › Fit width
    var goPage by remember { mutableStateOf<Pair<Int, Long>?>(null) } // a page to scroll to (and when it was asked)
    var goText by remember { mutableStateOf<Pair<Int, Long>?>(null) } // a place in the editor's text to bring to the top
    var sizing by remember { mutableStateOf(false) }
    var wrapping by remember { mutableStateOf(false) }
    var cropping by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var tableDialog by remember { mutableStateOf<String?>(null) } // "split" or "props" (the ⋯ page)
    // Merging: the first cell tapped, then the last; the cells between light up before they are merged.
    var mergeFrom by remember { mutableStateOf<TableCellRef?>(null) }
    var mergeTo by remember { mutableStateOf<TableCellRef?>(null) }
    var selectedCell by remember { mutableStateOf<TableCellRef?>(null) }
    var cellEdit by remember { mutableStateOf<TableCellEdit?>(null) }
    var bordersPage by remember { mutableStateOf<Pair<Set<Int>, ParaLook>?>(null) } // Home › Borders: its panel, for which paragraphs
    var paragraphPage by remember { mutableStateOf<Pair<Set<Int>, ParaLook>?>(null) } // Home's ⋯ page: for which paragraphs, as they look
    // The pictures' keys in the order they stand in the text.
    val keys by remember { derivedStateOf { state.annotatedString.let { state.toRichTextDocument().blocks.flatMap { b -> b.spans.filterIsInstance<RichTextSpanMark.Image>().sortedBy { it.range.first }.map { it.url.removePrefix(PICTURE_URL) } } } } }
    /** The editor's text [from]–[to] counted as Word counts: a table's words where it stands, pictures not at all. */
    fun counted(text: String, from: Int = 0, to: Int = text.length): TextCounts {
        var n = text.substring(0, from).count { it == EDITOR_PICTURE_CHAR }
        val out = StringBuilder()
        for (i in from until to) text[i].let { c -> if (c != EDITOR_PICTURE_CHAR) out.append(c)
            else pictures[keys.getOrNull(n++)]?.table?.let { t -> out.append('\n').append(DocPara(emptyList(), table = t).countedText()).append('\n') } }
        return TextCounts.of(out.toString())
    }
    // The selected picture (a tap on it selects it): its place in the text and its key.
    val selectedPicture by remember { derivedStateOf {
        val text = state.annotatedString.text
        val sel = state.selection
        (if (sel.collapsed) null else (sel.min until sel.max).firstOrNull { text.getOrNull(it) == EDITOR_PICTURE_CHAR }
            ?.let { i -> keys.getOrNull(text.substring(0, i).count { it == EDITOR_PICTURE_CHAR })?.let { i to it } })
            // A cell being typed in keeps its table selected (the Table tab and Make chart): the cell's own field takes the
            // focus, and with a physical keyboard the editor's selection then closed — the Table tab went (2026-10-14).
            ?: cellEdit?.ref?.key?.let { key -> keys.indexOf(key).takeIf { it >= 0 }?.let { n ->
                text.indices.filter { text[it] == EDITOR_PICTURE_CHAR }.getOrNull(n)?.let { i -> i to key } } }
    } }
    LaunchedEffect(selectedPicture) {
        if (selectedPicture == null) {
            if (tab == WriterTab.Picture || tab == WriterTab.Table) tab = WriterTab.Home
            tableDialog = null; mergeFrom = null; mergeTo = null
        }
    }

    // The editor does not hold each paragraph's own formatting (spacing, indents…); it is carried over from the file.
    fun current(shownPictures: Map<String, DocImage> = pictures) = meta.copy(paras = WriterBridge.fromEditorKeeping(meta.paras, state.toRichTextDocument(), meta.basePt, shownPictures))
    // Recovery includes the active cell without committing it or changing the editor's Undo history.
    fun recoverable(): OfficeDoc {
        val edit = cellEdit ?: return current()
        val img = pictures[edit.ref.key] ?: return current()
        val table = img.table ?: return current()
        val typed = WriterBridge.withCellText(edit.cell, edit.value.text)
        val pending = TableEdit.withCells(table, setOf(edit.ref.row to edit.ref.cell)) { typed }
        return current(pictures + (img.key to img.with(table = pending)))
    }
    fun editable() = recoverable().copy(unsupported = emptySet())
    LaunchedEffect(loaded, onPage, WriterBridge.editorWidthSp) {
        if (!loaded) return@LaunchedEffect
        WriterBridge.reflow(state, current(), pictures, documentStyles)
    }
    val editorMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val editorDensity = LocalDensity.current
    var editorTextWidth by remember { mutableIntStateOf(0) }
    var shownTabs by remember { mutableStateOf<List<EditorTab>>(emptyList()) }
    // Writer's last look of the text and how many paragraphs the editor had then (not state: read while composing).
    val lastLook = remember { arrayOfNulls<Pair<AnnotatedString, AnnotatedString>>(1) } // Writer's look and the editor's text it was made from
    LaunchedEffect(loaded, state.annotatedString, documentStyles, styleEdits, onPage, WriterBridge.editorWidthSp, editorTextWidth, shownZoom) {
        if (!loaded) return@LaunchedEffect
        val doc = current()
        val rich = state.toRichTextDocument()
        val shown = WriterBridge.displayText(state.annotatedString, rich, doc, documentStyles)
        val (displayed, tabs) = editorTabs(shown, rich, doc, documentStyles, editorMeasurer,
            TextStyle(fontSize = (BASE_SP * shownZoom / 100).sp, lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.None)),
            editorTextWidth.takeIf { it > 0 } ?: with(editorDensity) { WriterBridge.editorWidthSp.sp.roundToPx() }, with(editorDensity) { 1.sp.toPx() }, BASE_SP * shownZoom / 100f / meta.basePt)
        if (tabs != shownTabs && tabs.isNotEmpty()) WriterLog.i("editor tabs: ${tabs.size}, leaders ${tabs.count { it.leader != null }}, widths px ${tabs.take(6).joinToString { "%.0f".format(it.widthPx) }}")
        shownTabs = tabs
        WriterBridge.displayText(state, displayed)
        lastLook[0] = displayed to state.annotatedString
    }

    // Undo for what Writer changes itself (a style, spacing, a picture, a table, the header…): the editor's own undo
    // knows only typing, and forgets it whenever the document is shown again. Each such change keeps the whole
    // document from before it; typing between two changes is the editor's to undo, then one step back as a whole.
    val undone = remember { mutableStateListOf<WriterSnapshot>() }
    val redone = remember { mutableStateListOf<WriterSnapshot>() }
    var base by remember { mutableStateOf<WriterSnapshot?>(null) } // as it was after the last change (or opening)
    var changing by remember { mutableIntStateOf(0) }
    fun snapshot() = WriterSnapshot(current(), original, documentStyles, HashMap(pictures), state.selection)
    fun show(s: WriterSnapshot) {
        original = s.original; documentStyles = s.styles; meta = s.doc
        pictures.clear(); pictures.putAll(s.pictures)
        state.setRichTextDocument(WriterBridge.toEditor(s.doc, pictures, documentStyles), s.selection)
        state.history.clear()
        base = s; dirty = true; styleEdits++
    }
    /** A change of Writer's own, undone as one step. */
    fun <T> change(block: () -> T): T {
        if (changing++ == 0) {
            if (state.history.canUndo) base?.let { undone.add(it) } // the typing since the last change
            undone.add(snapshot())
            while (undone.size > UNDO_STEPS) undone.removeAt(0)
            redone.clear()
        }
        try { return block() } finally { if (--changing == 0) { state.history.clear(); base = snapshot() } }
    }
    /**
     * Find › Replace: letters [a]..[b] made [with], in their look (Word's: the replacement takes the replaced words' bold,
     * italic, colour…) — put in after the first of them, which it takes its look from, then the old letters taken out.
     * The library's replaceTextRange wrote it plain (found 16 Oct on the emulator: "Bold: Sample" lost its bold).
     */
    fun replaceKeepingLook(a: Int, b: Int, with: String) {
        if (with.isEmpty() || b <= a) { state.removeTextRange(androidx.compose.ui.text.TextRange(a, b)); return }
        state.addTextAtIndex(a + 1, with)
        state.removeTextRange(androidx.compose.ui.text.TextRange(a + 1 + with.length, b + with.length))
        state.removeTextRange(androidx.compose.ui.text.TextRange(a, a + 1))
    }
    fun undo() {
        WriterLog.i("undo")
        if (state.history.canUndo) { state.history.undo(); WriterBridge.reflow(state, current(), pictures, documentStyles); return }
        val s = undone.removeLastOrNull() ?: return
        redone.add(snapshot()); show(s)
    }
    fun redo() {
        WriterLog.i("redo")
        if (state.history.canRedo) { state.history.redo(); WriterBridge.reflow(state, current(), pictures, documentStyles); return }
        val s = redone.removeLastOrNull() ?: return
        undone.add(snapshot()); show(s)
    }
    // Typing after an undo: what was undone cannot come back (as in Word).
    LaunchedEffect(Unit) { snapshotFlow { state.history.canUndo }.collect { if (it) redone.clear() } }
    // "2.1." under an item the editor just renumbered: shown again in document order (once per text, never in a loop).
    LaunchedEffect(Unit) {
        var relabeled: String? = null
        snapshotFlow { state.annotatedString.text }.collect { text ->
            WriterBridge.plainMarkersIfPictureFirst(state)
            val n = state.config.orderedListStyleType as? FileNumbering ?: return@collect
            if (text == relabeled || !n.compound || !WriterBridge.staleNumbers(state, n)) return@collect
            n.counts.fill(0)
            WriterBridge.reflow(state, current(), pictures, documentStyles)
            relabeled = state.annotatedString.text
            WriterLog.i("list labels shown again")
        }
    }

    LaunchedEffect(reload) {
        loaded = false
        WriterLog.init(context)
        WriterLog.i("open ${file?.name ?: "a new document"} (${kind.name}${file?.let { ", ${it.length() / 1024} KB" } ?: ""})")
        val result = withContext(Dispatchers.IO) {
            runCatching {
                // An older file (.doc, .rtf): read by Writer's own readers; it has no package — saving writes a .docx beside it.
                val legacy = file?.let { LegacyDocs.isLegacy(it.name) } == true
                val bytes = file?.readBytes()
                val doc = bytes?.let { WriterLog.timed("read the file") { if (legacy) LegacyDocs.read(it, file!!.name) else OfficeFormats.read(it, kind) } } ?: OfficeDoc(listOf(DocPara(emptyList())), basePt = 12f) // Times New Roman 12, as APA 7 asks (the user's default, 2026-09-30)
                var draftPackage: ByteArray? = null
                val draft = draftFile(context, draftKey, kind).takeIf { it.isFile }?.let { d -> runCatching {
                    val draftBytes = d.readBytes()
                    draftPackage = draftBytes // its styles, header and footer, as changed before it closed
                    OfficeFormats.read(draftBytes, kind)
                }.getOrNull() }
                // With a draft, the file as it will be written starts from the draft (styles, header and footer as changed).
                val start = draftPackage ?: bytes?.takeIf { !legacy }
                packageParts = start?.let { OfficeFormats.unzip(it) }.orEmpty()
                Triple(start, doc, draft)
            }
        }
        result.onSuccess { (bytes, doc, draft) ->
            original = bytes
            documentStyles = DocStyles.of(kind, bytes)
            val fileLevels = documentStyles.orderedLevels(doc.paras)
            state.config.orderedListStyleType = FileNumbering(documentStyles) { meta.numbering ?: fileLevels }
            pictures.clear()
            state.setRichTextDocument(WriterBridge.toEditor(doc, pictures, documentStyles))
            // What the editor shows, read back, must be what the file holds; if not, saving over it could change
            // what was not touched — so that file too is saved only as a copy.
            val shown = WriterBridge.comparable(WriterBridge.fromEditor(state.toRichTextDocument(), doc.basePt, pictures))
            val same = shown == WriterBridge.comparable(doc.paras)
            if (!same) WriterLog.w("shown differs: ${shown.zip(WriterBridge.comparable(doc.paras)).firstOrNull { it.first != it.second }}")
            meta = if (same) doc else doc.copy(unsupported = doc.unsupported + "formatting it cannot show exactly")
            WriterLog.i("read: ${doc.paras.size} paragraphs, ${doc.paras.count { it.table != null }} tables, ${doc.paras.sumOf { p -> p.runs.count { it.image != null } }} pictures, " +
                "${doc.comments.size} comments; not kept: ${meta.unsupported.ifEmpty { setOf("—") }.joinToString()}" + if (meta.blocking.isNotEmpty()) " (saves as a copy)" else "")
            dirty = false
            cleanDoc = editable()
            cleanStyleEdits = styleEdits
            if (draft != null) {
                state.setRichTextDocument(WriterBridge.toEditor(draft.copy(basePt = doc.basePt), pictures, documentStyles))
                meta = meta.copy(paras = draft.paras, comments = draft.comments, sources = draft.sources) // the draft's paragraph formatting, comments and sources, not the file's
                dirty = true; message = context.getString(R.string.words_unsaved_back)
                WriterLog.i("draft restored")
            }
            state.history.clear(); undone.clear(); redone.clear()
            // The cursor at the start (the editor leaves it at the end): the first tap brought the end into view.
            state.selection = androidx.compose.ui.text.TextRange(0)
            base = snapshot()
            loaded = true
        }.onFailure { WriterLog.e("could not open", it); failed = it.officeMessage(context) ?: context.getString(R.string.words_file_could_not_be_read) }
    }
    // The log: the mode, the tab and what Writer said (the taps are the ribbon's; see WriterLog).
    LaunchedEffect(viewing, reading) { WriterLog.i(if (reading) "reading mode at $readingZoom%" else if (viewing) "view mode" else "edit mode") }
    LaunchedEffect(tab) { WriterLog.i("tab ${tab.name}") }
    DisposableEffect(Unit) { onDispose { WriterLog.i("closed${if (dirty) " (changes kept as a draft)" else ""}") } }
    /**
     * The package just written ([bytes], from [saved]) becomes the one Writer works from: a picture or shape added in
     * Writer is in it now, so from here on it goes back as written (its own XML: Arrange, Position… work on it).
     */
    fun adopt(bytes: ByteArray, saved: OfficeDoc) {
        val back = OfficeFormats.read(bytes, kind)
        val before = saved.paras.flatMap { it.runs }.mapNotNull { it.image }
        val after = back.paras.flatMap { it.runs }.mapNotNull { it.image }
        if (before.size == after.size) before.zip(after).forEach { (b, a) ->
            if (pictures[b.key] === b) pictures[b.key] = a.with(key = b.key)
        }
        original = bytes
        packageParts = OfficeFormats.unzip(bytes)
        documentStyles = DocStyles.of(kind, bytes)
        meta = meta.copy(unsupported = back.unsupported)
    }
    // Any change marks the document unsaved, and is kept as a draft after a short pause.
    LaunchedEffect(loaded) {
        if (!loaded) return@LaunchedEffect
        // The editor lays a list item's indent out again once it is drawn (its bullet measured): only its look, so the
        // indents are left out of what counts as a change (01_Text_and_Paragraphs opened as changed). Likewise it joins
        // neighbouring letters that look the same (runs the file kept apart — fonts it does not show): compared joined.
        snapshotFlow {
            // The document decoder reads the editor's ordinary lists. Observe its Compose text
            // explicitly so typing (not only changes to Writer's metadata) refreshes this snapshot.
            state.annotatedString
            editable() to styleEdits
        }
            .drop(1).collectLatest { // meta: a paragraph's spacing set; a style changed
            dirty = it.first != cleanDoc || it.second != cleanStyleEdits
            if (!dirty) {
                val key = uri ?: "new"
                withContext(Dispatchers.IO) { draftMutex.withLock {
                    if (!dirty && key == (uri ?: "new")) draftFile(context, key, kind).delete()
                } }
                return@collectLatest
            }
            delay(1500)
            val doc = recoverable()
            val captured = editable() to styleEdits
            // Read the current URI here: this long-lived effect also survives Save a copy/first Save.
            val key = uri ?: "new"
            val source = original
            val written = withContext(Dispatchers.IO) { draftMutex.withLock {
                if (dirty && key == (uri ?: "new")) runCatching { OfficeFormats.write(doc, kind, source).also { writeWriterDraft(draftFile(context, key, kind), it); keepSessionCopy(context, key, kind, it) } }.onFailure { WriterLog.e("draft", it) }.getOrNull() else null
            } }
            // The working copy is the package from here on when it holds something Writer made that the file has not yet
            // (a new picture or shape: its XML is written only in a package) — nothing waits for a Save (asked 2026-10-14).
            if (written != null && cellEdit == null && (editable() to styleEdits) == captured && pictures.values.any { it.raw == null && it.table == null && it.chart == null }) {
                adopt(written, doc); WriterLog.i("working copy adopted (new pictures have their XML)")
            }
            // AutoSave: a little later, over the file itself — never a new or copy-only one (those Save asks about), nor
            // while a table cell is being typed in (the cell is not in the document until it is left).
            delay(1500)
            if (autoSave && dirty && currentFile() != null && meta.blocking.isEmpty() && cellEdit == null) autoSaveDue++
        }
    }
    // The pages, laid out again whenever they are shown (after editing, they show the edits).
    // A page gallery's choice (styleEdits) shows at once, the pages kept on screen meanwhile.
    LaunchedEffect(viewing, loaded, if (galleryViewing != null) styleEdits else 0) {
        if (!viewing || !loaded) return@LaunchedEffect
        if (galleryViewing == null) pages = null
        // the commented words tinted on the pages, as in the editor (asked: comments on the pages view) — on the screen only:
        // Export PDF and Print lay the document out by themselves, without it, as Word leaves comments out of its PDF
        val doc = current().let { d ->
            val open = d.comments.filter { !it.done }.map { it.id }.toSet()
            fun tint(x: DocPara): DocPara = x.copy(runs = x.runs.map { r -> if (r.highlight == null && r.comments.any { it in open }) r.copy(highlight = 0xFFF2CC) else r },
                table = x.table?.let { t -> t.copy(rows = t.rows.map { row -> row.copy(cells = row.cells.map { c -> c.copy(paras = c.paras.map(::tint)) }) }) })
            if (open.isEmpty()) d else d.copy(paras = d.paras.map(::tint))
        }
        val parts = packageParts
        val index = IntArray(doc.paras.size) { -1 }
        pages = withContext(Dispatchers.Default) { runCatching { WriterLog.timed("laid out the pages") { OfficePages.layout(doc, documentStyles, pageIndexOf = index) { img -> img.bytes ?: img.part?.let { parts[it] } } } }.onFailure { WriterLog.e("pages", it) }.getOrNull() }
        pagesIndex = index
        pages?.let { WriterLog.i("pages: ${it.size}") }
        if (pages == null) viewing = false // could not lay it out: the editor still works
    }
    // Enter after a heading starts a Normal paragraph, as in Word: the editor carries the heading's drawn look (its
    // style's marks) on into the new, empty paragraph — so that look is taken off at once. (After a Title or any other
    // style the new paragraph keeps the style, as it is saved.)
    LaunchedEffect(loaded) {
        if (!loaded) return@LaunchedEffect
        snapshotFlow { state.annotatedString to state.selection }.collect {
            val carried = state.currentSpanStyle
            if (state.currentHeadingStyle != HeadingStyle.Normal || !WriterBridge.styleDrawn(carried, meta.basePt)) return@collect
            val rich = state.toRichTextDocument()
            val b = WriterBridge.blockAt(state.annotatedString.text, rich, state.selection.min) ?: return@collect
            val before = rich.blocks.subList(0, b).lastOrNull { !it.isLineBreak }
            if (rich.blocks[b].text.isEmpty() && (before?.headingLevel ?: 0) > 0) state.removeSpanStyle(WriterBridge.drawnPart(carried, meta.basePt))
        }
    }

    // Word's typing shortcut (ScriptShortcut, asked 2026-10-14): "x^2" then a space → x², "H_2O " → H₂O; Backspace on that
    // space straight after brings the word back as typed.
    var scriptUndo by remember { mutableStateOf<Triple<Int, Int, String>?>(null) } // (word's start, the space's place, the word as typed)
    var scriptKept by remember { mutableStateOf<Pair<Int, String>?>(null) } // a word brought back as typed: left so after the next space (Word's)
    LaunchedEffect(loaded) {
        if (!loaded) return@LaunchedEffect
        var was = state.annotatedString.text
        snapshotFlow { state.annotatedString.text to state.selection }.collect { (text, sel) ->
            val prev = was; was = text
            if (text == prev || !sel.collapsed || cellEdit != null) return@collect
            val undo = scriptUndo; scriptUndo = null
            if (undo != null && text.length == prev.length - 1 && sel.min == undo.second && prev.getOrNull(undo.second) == ' ' &&
                text.startsWith(prev.substring(0, undo.second))) {
                change {
                    state.removeTextRange(androidx.compose.ui.text.TextRange(undo.first, undo.second))
                    state.selection = androidx.compose.ui.text.TextRange(undo.first)
                    state.addTextAfterSelection(undo.third)
                    val typed = androidx.compose.ui.text.TextRange(undo.first, undo.first + undo.third.length)
                    state.selection = typed
                    listOf(androidx.compose.ui.text.style.BaselineShift.Superscript, androidx.compose.ui.text.style.BaselineShift.Subscript).forEach { state.removeSpanStyle(SpanStyle(baselineShift = it)) }
                    state.setRichTextDocument(WriterBridge.toEditor(current(), pictures, documentStyles), androidx.compose.ui.text.TextRange(typed.max))
                    dirty = true
                }
                scriptKept = undo.first to undo.third
                WriterLog.i("script shortcut undone: ${undo.third}")
                was = state.annotatedString.text
                return@collect
            }
            // A space just typed after a word.
            val space = sel.min - 1
            if (text.length != prev.length + 1 || text.getOrNull(space) != ' ' || !text.startsWith(prev.substring(0, space))) return@collect
            val start = (space - 1 downTo 0).firstOrNull { text[it].isWhitespace() || text[it] == EDITOR_PICTURE_CHAR }?.plus(1) ?: 0
            val word = text.substring(start, space)
            if (scriptKept == start to word) return@collect // brought back as typed on purpose
            val raises = ScriptShortcut.find(word).takeIf { it.isNotEmpty() } ?: return@collect
            val drop = raises.flatMap { r -> listOf(r.mark) + if (r.bracketed) listOf(r.from - 1, r.to) else emptyList() }.sortedDescending()
            fun shifted(i: Int) = i - drop.count { it < i }
            change {
                drop.forEach { d -> state.removeTextRange(androidx.compose.ui.text.TextRange(start + d, start + d + 1)) }
                raises.forEach { r ->
                    state.selection = androidx.compose.ui.text.TextRange(start + shifted(r.from), start + shifted(r.to))
                    state.addSpanStyle(SpanStyle(baselineShift = if (r.up) androidx.compose.ui.text.style.BaselineShift.Superscript else androidx.compose.ui.text.style.BaselineShift.Subscript))
                }
                val after = sel.min - drop.size
                state.setRichTextDocument(WriterBridge.toEditor(current(), pictures, documentStyles), androidx.compose.ui.text.TextRange(after))
                dirty = true
                scriptUndo = Triple(start, after - 1, word)
            }
            WriterLog.i("script shortcut: $word → ${ScriptShortcut.shown(word, raises)}")
            was = state.annotatedString.text
        }
    }

    suspend fun finishSave(target: File, bytes: ByteArray, saved: OfficeDoc, savedStyleEdits: Int, oldDraftKey: String) {
        val newer = editable() != saved.copy(unsupported = emptySet()) || styleEdits != savedStyleEdits
        uri = Uri.fromFile(target).toString()
        name = target.name
        if (!newer) adopt(bytes, saved)
        // The target now holds the captured snapshot, even if the user has since typed or undone it.
        cleanDoc = if (newer) saved.copy(unsupported = emptySet()) else editable()
        cleanStyleEdits = savedStyleEdits
        dirty = newer
        // A copy/new document owns its subsequent draft. Keep newer typing before removing the old draft.
        val key = Uri.fromFile(target).toString()
        // Typing may continue during the draft write as well as the file write. Recheck after IO and
        // flush again when necessary instead of waiting for the normal typing debounce.
        do {
            val captured = editable() to styleEdits
            dirty = captured.first != cleanDoc || captured.second != cleanStyleEdits
            val latest = if (dirty) recoverable() else null
            val latestSource = original
            withContext(Dispatchers.IO) { draftMutex.withLock {
                if (latest != null) writeWriterDraft(draftFile(context, key, kind), OfficeFormats.write(latest, kind, latestSource))
                else draftFile(context, key, kind).delete()
                if (oldDraftKey != key || latest == null) draftFile(context, oldDraftKey, kind).delete()
            } }
        } while ((editable() to styleEdits) != captured)
    }
    /** Save over the file — only when nothing in it would be lost; otherwise a copy beside it. */
    fun save(asCopy: Boolean = false, auto: Boolean = false, then: () -> Unit = {}) {
        if (saving) return
        val f = currentFile()
        if (f == null) { if (!auto) naming = true; return }
        saving = true
        scope.launch {
            try {
            val doc = current()
            val savedStyleEdits = styleEdits
            val oldDraftKey = draftKey
            val source = original
            val copy = asCopy || meta.blocking.isNotEmpty()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = WriterLog.timed("wrote the file") { OfficeFormats.write(doc, kind, source) }
                    // An older file (.doc, .rtf) becomes "name.docx" beside it, the first time (then a copy's name).
                    val target = if (!copy) f else File(f.parentFile!!, "${f.nameWithoutExtension}.${kind.extension}").takeIf { f.extension != kind.extension && !it.exists() }
                        ?: freeCopyName(f.parentFile!!, f.nameWithoutExtension, kind.extension)
                    // Written beside it, then renamed over it: the file is either the old one or the new one, never half.
                    val temp = File(target.parentFile, ".${target.name}.tetra-saving")
                    temp.writeBytes(bytes)
                    // AutoSave keeps the file as it was opened, once: three Versions would not outlast a minute of saves.
                    if (!copy && !(auto && versionKept)) keepPrevious(context, f)
                    check(temp.renameTo(target)) { context.getString(R.string.words_could_not_save_name, target.name) }
                    // Its time is now (the phone's storage was seen keeping the old one): sync and the hash cache know it changed.
                    target.setLastModified(System.currentTimeMillis())
                    target to bytes
                }
            }
            result.onSuccess { (target, bytes) ->
                WriterLog.i("${if (auto) "autosaved" else "saved"} ${target.name} (${bytes.size / 1024} KB${if (copy) ", a copy" else ""})")
                if (!copy) versionKept = true
                finishSave(target, bytes, doc, savedStyleEdits, oldDraftKey)
                if (!auto) message = if (dirty) context.getString(R.string.words_saved_newer_unsaved) else if (copy) context.getString(R.string.words_saved_as_copy, target.name) else context.getString(R.string.words_saved)
                if (!dirty) then()
            }.onFailure { WriterLog.e("could not save", it); message = (it.message ?: context.getString(R.string.words_could_not_save)).let { m -> if (auto) context.getString(R.string.words_autosave_failed, m) else m } }
            } catch (error: Exception) { WriterLog.e("finish save", error); message = error.message ?: context.getString(R.string.words_could_not_finish_saving) }
            finally { saving = false }
        }
    }
    LaunchedEffect(autoSaveDue) { if (autoSaveDue > 0 && dirty) save(auto = true) }
    /** A new document's first Save, or Save as (File): beside the document it was, under [typed], and goes on as that one. */
    fun saveNew(typed: String, into: File? = null) {
        if (saving) return
        val clean = typed.trim().removeSuffix(".${kind.extension}")
        if (clean.isEmpty() || '/' in clean) { message = context.getString(R.string.words_not_a_file_name); return }
        saving = true
        scope.launch {
            try {
            val dir = into ?: currentFile()?.parentFile ?: TextEditorStore(context).saveDirectory()
            val target = File(dir, "$clean.${kind.extension}")
            val doc = current()
            val savedStyleEdits = styleEdits
            val oldDraftKey = draftKey
            val source = original
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    check(dir.isDirectory || dir.mkdirs()) { context.getString(R.string.words_could_not_make_folder, dir.name) }
                    check(!target.exists()) { context.getString(R.string.words_name_taken, target.name) }
                    OfficeFormats.write(doc, kind, source).also { bytes ->
                        val temp = File(target.parentFile, ".${target.name}.tetra-saving")
                        temp.writeBytes(bytes)
                        check(temp.renameTo(target)) { context.getString(R.string.words_could_not_save_name, target.name) }
                    }
                }
            }
            result.onSuccess { bytes -> WriterLog.i("saved new ${target.name} (${bytes.size / 1024} KB)"); naming = false; finishSave(target, bytes, doc, savedStyleEdits, oldDraftKey); message = if (dirty) context.getString(R.string.words_saved_newer_unsaved) else context.getString(R.string.words_saved_as, target.name) }
                .onFailure { WriterLog.e("could not save new", it); message = it.message }
            } catch (error: Exception) { WriterLog.e("finish save new", error); message = error.message ?: context.getString(R.string.words_could_not_finish_saving) }
            finally { saving = false }
        }
    }
    /** Export › PDF: beside the document (or in the save folder for a new one), never over another file. */
    fun exportPdf() {
        scope.launch {
            val doc = current()
            val dir = file?.parentFile ?: TextEditorStore(context).saveDirectory()
            val stem = name.substringBeforeLast('.')
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    check(dir.isDirectory || dir.mkdirs()) { context.getString(R.string.words_could_not_make_folder, dir.name) }
                    val target = generateSequence(1) { it + 1 }.map { File(dir, if (it == 1) "$stem.pdf" else "$stem ($it).pdf") }.first { !it.exists() }
                    target.outputStream().use { OfficePdf.write(doc, DocStyles.of(kind, original), it) { img -> img.bytes ?: img.part?.let { p -> packageParts[p] } } }
                    target
                }
            }
            result.onSuccess { message = context.getString(R.string.words_saved_in, it.name, dir.name) }.onFailure { message = it.officeMessage(context) ?: context.getString(R.string.words_pdf_not_made) }
        }
    }
    /** File › Print: the pages as Export PDF makes them, to Android's printing (a printer, or Save as PDF). */
    fun print() {
        scope.launch {
            val doc = current()
            val pdf = withContext(Dispatchers.IO) { runCatching {
                File(context.cacheDir, "print.pdf").also { f -> f.outputStream().use { OfficePdf.write(doc, DocStyles.of(kind, original), it) { img -> img.bytes ?: img.part?.let { p -> packageParts[p] } } } }
            }.onFailure { WriterLog.e("print", it) }.getOrNull() } ?: run { message = context.getString(R.string.words_print_failed); return@launch }
            val manager = context.getSystemService(android.print.PrintManager::class.java) ?: return@launch
            manager.print(name.substringBeforeLast('.'), PdfPrintAdapter(pdf, name.substringBeforeLast('.')), null)
            WriterLog.i("print ${pdf.length() / 1024} KB")
        }
    }
    /** File › Rename: the file itself, its recovery draft and its versions with it. */
    fun rename(typed: String) {
        val f = currentFile() ?: return
        val clean = typed.trim().removeSuffix(".${kind.extension}")
        if (clean.isEmpty() || '/' in clean) { message = context.getString(R.string.words_not_a_file_name); return }
        val target = File(f.parentFile, "$clean.${kind.extension}")
        if (target == f) { renaming = false; return }
        if (target.exists()) { message = context.getString(R.string.words_name_taken, target.name); return }
        scope.launch {
            val ok = withContext(Dispatchers.IO) { draftMutex.withLock { runCatching {
                val oldDraft = draftFile(context, draftKey, kind); val oldVersions = versionsDir(context, f)
                check(f.renameTo(target)) { context.getString(R.string.words_could_not_rename_name, f.name) }
                if (oldDraft.isFile) oldDraft.renameTo(draftFile(context, Uri.fromFile(target).toString(), kind))
                if (oldVersions.isDirectory) oldVersions.renameTo(versionsDir(context, target))
            }.onFailure { WriterLog.e("rename", it) }.isSuccess } }
            if (!ok) { message = context.getString(R.string.words_could_not_rename); return@launch }
            uri = Uri.fromFile(target).toString(); name = target.name; renaming = false
            WriterLog.i("renamed to ${target.name}"); message = context.getString(R.string.words_renamed, target.name)
        }
    }
    /** Versions › This session: the working copy as it was then, back in the editor (one step of Undo). */
    fun restoreSession(copy: File) = change {
        val bytes = runCatching { copy.readBytes() }.getOrNull() ?: return@change
        val doc = runCatching { OfficeFormats.read(bytes, kind) }.onFailure { WriterLog.e("session copy", it) }.getOrNull() ?: run { message = context.getString(R.string.words_copy_unreadable); return@change }
        original = bytes; packageParts = OfficeFormats.unzip(bytes); documentStyles = DocStyles.of(kind, bytes)
        pictures.clear()
        meta = doc.copy(unsupported = meta.unsupported)
        state.setRichTextDocument(WriterBridge.toEditor(meta, pictures, documentStyles))
        dirty = true; styleEdits++
        message = context.getString(R.string.words_version_restored)
        WriterLog.i("version ${copy.name} brought back")
    }
    /**
     * Puts [img] at the cursor and reads the document back in (so its line makes room for it); returns where it is.
     * A mark is typed there and the picture put in its place in Writer's own document — the editor's own way of adding
     * a picture (insertHtml) mixed up the text after it (found 2026-09-28).
     */
    fun placePicture(img: DocImage): Int = change {
        state.addTextAfterSelection(PICTURE_SPOT.toString())
        val at = state.selection.min - 1
        state.setRichTextDocument(WriterBridge.toEditor(WriterBridge.putPicture(current(), img), pictures, documentStyles), androidx.compose.ui.text.TextRange(at, at + 1))
        dirty = true
        at
    }
    /** A picture dragged from [from] to [to] in the text: taken out and put in again there; it stays selected. */
    fun movePicture(from: Int, to: Int, key: String) = change {
        val img = pictures[key] ?: return@change
        if (to == from || to == from + 1) return@change
        state.removeTextRange(androidx.compose.ui.text.TextRange(from, from + 1))
        state.selection = androidx.compose.ui.text.TextRange(if (to > from) to - 1 else to)
        placePicture(img)
    }
    // Insert › Picture: from the phone's photos, at the cursor.
    fun insertPicture(picked: android.net.Uri) {
        scope.launch {
            val img = withContext(Dispatchers.IO) { runCatching { preparePicture(context, picked) }.getOrNull() }
            if (img == null) { message = context.getString(R.string.words_picture_not_added); return@launch }
            pictures[img.key] = img
            val at = placePicture(img)
            state.selection = androidx.compose.ui.text.TextRange(at + 1) // the cursor after it
        }
    }
    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked -> picked?.let(::insertPicture) }

    // Insert › Video (asked 17 Oct, Slides' pieces): [film] a web address (Word's Online Video) or a film from the phone; its picture at the cursor
    fun insertVideo(film: String, poster: android.graphics.Bitmap) {
        val bytes = java.io.ByteArrayOutputStream().also { poster.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        val img = DocImage("n" + java.util.UUID.randomUUID(), null, 360f, 360f * poster.height / poster.width.coerceAtLeast(1), null, bytes, "jpg", film = film)
        pictures[img.key] = img
        state.selection = androidx.compose.ui.text.TextRange(placePicture(img) + 1)
        WriterLog.i("a video put in: ${if (film.startsWith("http")) "online" else "from the phone"}")
    }
    fun insertOnlineVideo(url: String) = scope.launch { insertVideo(url, withContext(Dispatchers.IO) { OfficeVideo.poster(url) }) }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked -> picked ?: return@rememberLauncherForActivityResult
        scope.launch { withContext(Dispatchers.IO) { OfficeVideo.fromPhone(context, picked, File(context.cacheDir, "word-videos")) }?.let { insertVideo(it.first, it.second) } ?: run { message = context.getString(R.string.words_video_unreadable) } }
    }
    var playing by remember { mutableStateOf<Pair<String, String>?>(null) } // Picture › Play: the video playing in its frame while editing (its key, its film)
    /** A video's film to play: a web address or a copy on the phone as it is, else its part taken out of the file. */
    suspend fun filmOf(m: String): String? = if (m.startsWith("http") || File(m).isFile) m
        else withContext(Dispatchers.IO) { packageParts[m.substringBefore('#')]?.let { OfficeVideo.cached(m, it, context.cacheDir) } }
    fun play(key: String, m: String) = scope.launch {
        filmOf(m)?.let { playing = key to it; WriterLog.i("a video played") } ?: run { message = context.getString(R.string.words_film_not_found) }
    }
    /** A picture changed by the Picture tab: read back in, so its line makes room for its new size; the selection stays. */
    fun changePicture(key: String, img: DocImage) = change {
        WriterLog.i("picture $key: ${PictureXml.wrapOf(img)}, ${img.widthPt.toInt()}×${img.heightPt.toInt()} pt${img.onPage?.let { " at ${it.first.toInt()},${it.second.toInt()}" } ?: ""}")
        pictures[key] = img
        val at = state.selection
        state.setRichTextDocument(WriterBridge.toEditor(current(), pictures, documentStyles), at)
        dirty = true; styleEdits++ // the text may be the same: the draft is kept all the same
    }
    // Insert › Equation (Phase 2): Word's OMML, typed in its linear form; in an empty paragraph a display of its own,
    // centred (Word's), else in the line. Picture › Edit equation writes it again (the file's own look of it is not kept).
    var equationOpen by remember { mutableStateOf<Pair<String, MathNode?>?>(null) } // the equation's key ("" for a new one), what it shows
    // Review › Read aloud (Android's text-to-speech) and the keyboard bar's dictation (Android's voice typing).
    val reader = rememberReadAloud()
    var readingParas by remember { mutableStateOf(emptyList<String>()) }
    val dictate = rememberDictation { said -> change {
        val at = state.selection.min
        val before = state.annotatedString.text.getOrNull(at - 1)
        val after = state.annotatedString.text.getOrNull(state.selection.max)
        state.addTextAfterSelection((if (before != null && !before.isWhitespace()) " " else "") + said + (if (after != null && after.isLetterOrDigit()) " " else ""))
        dirty = true
    } }
    fun putEquation(key: String, math: MathNode) = change {
        val old = pictures[key]
        if (old != null) {
            val display = old.raw?.trimStart()?.startsWith("<m:oMathPara") == true
            // An .odt's: written as a new LibreOffice Math object on saving (raw null), the old one left unused.
            changePicture(key, OfficeMath.image(key, math, OfficeMath.equation(math, display), meta.basePt).let { if (kind == OfficeKind.Odt) it.with(raw = null) else it })
            WriterLog.i("equation $key edited: ${OfficeMath.linear(math)}")
            return@change
        }
        // An empty paragraph (as the document has it: the editor's own empty line is not blank letters) gets a display equation.
        val sel = state.selection.min
        val here = WriterBridge.paragraphsAt(state.annotatedString.text, state.toRichTextDocument(), sel, sel).singleOrNull()?.let { current().paras.getOrNull(it) }
        val display = here != null && here.table == null && here.runs.none { it.image != null } && here.text.isBlank()
        val img = OfficeMath.image("n" + java.util.UUID.randomUUID(), math, OfficeMath.equation(math, display), meta.basePt).let { if (kind == OfficeKind.Odt) it.with(raw = null) else it }
        pictures[img.key] = img
        val at = placePicture(img)
        if (display) {
            val para = WriterBridge.paragraphSpans(WriterBridge.blockPlaces(state.annotatedString.text, state.toRichTextDocument()), at, at + 1).firstOrNull()?.first
            if (para != null) { meta = current().let { d -> d.copy(paras = d.paras.mapIndexed { i, p -> if (i == para) p.copy(align = DocAlign.Center) else p }) }; state.setRichTextDocument(WriterBridge.toEditor(meta, pictures, documentStyles), state.selection) }
        }
        state.selection = androidx.compose.ui.text.TextRange(at + 1)
        WriterLog.i("equation inserted (${if (display) "display" else "in line"}): ${OfficeMath.linear(math)}")
    }
    // Picture › Replace: another photo in its place, as wide as it was, wrapped as it was.
    val replacePicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
        val (at, key) = selectedPicture ?: return@rememberLauncherForActivityResult
        val old = pictures[key] ?: return@rememberLauncherForActivityResult
        if (picked != null) scope.launch {
            val img = withContext(Dispatchers.IO) { runCatching { preparePicture(context, picked) }.getOrNull() }
            if (img == null) { message = context.getString(R.string.words_picture_not_used); return@launch }
            changePicture(key, DocImage(key, null, old.widthPt, old.widthPt * img.heightPt / img.widthPt, null, img.bytes, img.extension, PictureXml.wrapOf(old)))
            // a picture control's picture: filled now (its placeholder state gone)
            if (current().paras.any { p -> p.runs.any { it.image?.key == key && it.control != null } }) change<Unit> { meta = Forms.pictureFilled(current(), key); state.setRichTextDocument(WriterBridge.toEditor(meta, pictures, documentStyles), state.selection) }
            state.selection = androidx.compose.ui.text.TextRange(at, at + 1)
        }
    }
    /** Picture › Turn and Flip: done away from the screen, then shown. */
    fun transform(turn: Int, flip: Boolean) {
        val (at, key) = selectedPicture ?: return
        val img = pictures[key] ?: return
        val bytes = img.bytes ?: img.part?.let { packageParts[it] } ?: return
        scope.launch {
            val out = withContext(Dispatchers.Default) { runCatching { transformedPicture(img, bytes, turn, flip) }.getOrNull() }
            if (out == null) { message = context.getString(R.string.words_cannot_rotate_picture); return@launch }
            changePicture(key, out)
            state.selection = androidx.compose.ui.text.TextRange(at, at + 1)
        }
    }
    fun changeTable(key: String, img: DocImage, table: DocTable) {
        val (width, height) = WriterBridge.tableSize(meta, table, documentStyles)
        changePicture(key, img.with(widthPt = width, heightPt = height, table = table))
    }
    /** Spacing set on paragraphs [at]: only what differs from [was] is written, in the file's own words. */
    fun setSpacing(at: Set<Int>, was: ParaLook, now: ParaLook) = change {
        val cur = current()
        fun <T> changed(a: T, b: T) = b.takeIf { it != a }
        val line = now.lineMultiple.takeIf { now.lineExact == null && (was.lineExact != null || it != was.lineMultiple) }
        meta = cur.copy(paras = cur.paras.mapIndexed { i, p ->
            if (i !in at || p.table != null) p else p.copy(keep = OfficeFormats.withSpacing(p.keep, kind, line, changed(was.before, now.before),
                changed(was.after, now.after), changed(was.left, now.left), changed(was.firstLine, now.firstLine), changed(was.right, now.right)))
        })
        dirty = true
        // The editor shows indents: shown again, the cursor where it was.
        if (was.left != now.left || was.firstLine != now.firstLine) state.selection.let { sel -> state.setRichTextDocument(WriterBridge.toEditor(meta, pictures, documentStyles), sel) }
    }
    /** The paragraphs the cursor or selection is in, and how the first of them looks; null when they cannot be told. */
    fun chosen(): Pair<Set<Int>, ParaLook>? {
        val rich = state.toRichTextDocument()
        val cur = current()
        // Text typed beside a table makes one block two paragraphs: then the count differs and nothing is guessed.
        val at = WriterBridge.paragraphsAt(state.annotatedString.text, rich, state.selection.min, state.selection.max)
            .takeIf { it.isNotEmpty() && rich.blocks.count { !it.isLineBreak } == cur.paras.size } ?: return null
        return cur.paras.getOrNull(at.min())?.takeIf { it.table == null }?.let { at to documentStyles.look(it) }
    }
    /** A paragraph style put on the chosen paragraphs: the file's own (or a heading level), shown at once as it looks. */
    fun applyStyle(style: StyleEntry) = change {
        val at = chosen()?.first ?: run { message = context.getString(R.string.words_cursor_in_paragraph); return@change }
        val cur = current()
        meta = cur.copy(paras = cur.paras.mapIndexed { i, p ->
            if (i !in at || p.table != null) p else p.copy(heading = style.heading, keep = (p.keep ?: DocKeep()).copy(style = style.id).takeIf { !it.isEmpty })
        })
        dirty = true
        state.selection.let { sel -> state.setRichTextDocument(WriterBridge.toEditor(meta, pictures, documentStyles), sel) }
    }
    /**
     * Insert › Page break (Word's Ctrl+Enter): the paragraph is split at the cursor and the part after it starts a page;
     * at a paragraph's start, that paragraph does. [blank]: an empty page between (a break before an empty paragraph
     * and before the text after it). Saved as "page break before" (both formats); Backspace at the paragraph's start
     * joins it to the one before, which takes the break away, as in Word.
     */
    fun insertPageBreak(blank: Boolean, column: Boolean = false, section: String? = null) = change {
        val text = state.annotatedString.text
        val rich = state.toRichTextDocument()
        val at = state.selection.min
        val atStart = WriterBridge.blockRange(text, rich, at)?.first == at && rich.blocks.getOrNull(WriterBridge.blockAt(text, rich, at) ?: -1)?.isLineBreak == false
        // The paragraph split: its part after the cursor keeps its style (the editor's Enter makes it Normal after a heading).
        val before = current()
        val split = WriterBridge.paragraphsAt(text, rich, at, at).singleOrNull()?.let { before.paras.getOrNull(it) }
            ?.takeIf { rich.blocks.count { !it.isLineBreak } == before.paras.size }
        state.selection = androidx.compose.ui.text.TextRange(at)
        if (!atStart) state.addTextAfterSelection("\n")
        if (blank) { state.addTextAfterSelection("\n") }
        val cursor = state.selection.min
        val now = state.toRichTextDocument()
        val cur = current()
        val here = WriterBridge.paragraphsAt(state.annotatedString.text, now, cursor, cursor).singleOrNull()
            ?.takeIf { now.blocks.count { !it.isLineBreak } == cur.paras.size } ?: run { message = context.getString(R.string.words_cursor_in_paragraph); return@change }
        meta = cur.copy(paras = cur.paras.mapIndexed { i, p -> when {
            p.table != null -> p
            i == here -> (if (split != null && !atStart) p.copy(heading = split.heading, keep = split.keep?.let { k -> k.copy(parts = k.parts.filter { it.first != "sectPr" }) }?.takeIf { !it.isEmpty }) else p).let {
                when { section != null -> it; column -> it.copy(keep = OfficeFormats.withColumnBreak(it.keep, kind, true)); else -> it.copy(keep = OfficeFormats.withPageBreak(it.keep, kind, true)) } }
            section != null && !atStart && i == here - 1 && split != null -> p.copy(keep = split.keep?.let { k -> k.copy(parts = k.parts.filter { it.first != "sectPr" }) }?.takeIf { !it.isEmpty }) // a section's end stays with the part after the cursor
            blank && i == here - 1 -> p.copy(heading = 0, list = null, keep = OfficeFormats.withPageBreak(null, kind, true)) // the empty page: a plain paragraph
            else -> p
        } })
        // Layout › Breaks › Section in an .odt: the text from the cursor on in a section of its own (LibreOffice's).
        if (section != null && kind == OfficeKind.Odt) {
            meta = OdtSections.insert(meta, here, section == "nextPage") { it.copy(keep = OfficeFormats.withPageBreak(it.keep, kind, true)) }
            WriterLog.i("odt section ($section) from paragraph $here")
        }
        // Layout › Breaks › Section (Word files): a section ends with the paragraph before the cursor's.
        if (section != null && kind == OfficeKind.Docx) {
            val base = original ?: OfficeFormats.write(OfficeDoc(emptyList()), kind)
            val body = SectionBreaks.bodySect(String(OfficeFormats.unzip(base).let { it.getValue(documentPath(it)) }, Charsets.UTF_8)) ?: "<w:sectPr/>"
            val (withBreak, newBody) = SectionBreaks.insert(meta, here - 1, body, section)
            meta = withBreak
            if (newBody != null) original = OfficeFormats.zip(SectionBreaks.withBodySect(OfficeFormats.unzip(base), newBody), null)
            documentStyles = DocStyles.of(kind, original ?: base)
            styleEdits++
            WriterLog.i("section break ($section) after paragraph ${here - 1}")
        }
        dirty = true
        state.setRichTextDocument(WriterBridge.toEditor(meta, pictures, documentStyles), androidx.compose.ui.text.TextRange(cursor))
    }
    /** Borders set on paragraphs [at] (the same for each: alike and side by side, they are one box, as in Word). */
    fun setBorders(at: Set<Int>, box: ParaBox) = change {
        val cur = current()
        meta = cur.copy(paras = cur.paras.mapIndexed { i, p -> if (i !in at || p.table != null) p else p.copy(keep = OfficeFormats.withBorders(p.keep, kind, box)) })
        dirty = true
        message = context.getString(R.string.words_borders_on_pages)
    }
    /** Insert › Drop Cap on the paragraph at the cursor (null: taken off); the pages show it. */
    var dropCapPage by remember { mutableStateOf<Pair<Int, DropCap?>?>(null) } // the options page: its paragraph, as it is
    fun setDropCap(at: Int, drop: DropCap?, font: String? = null, color: Int? = null) = change {
        val cur = current()
        meta = cur.copy(paras = cur.paras.mapIndexed { i, p -> if (i != at || p.table != null) p
            else p.copy(keep = OfficeFormats.withDropCap(p.keep, kind, drop)).let { q -> if (drop == null) q else OfficeFormats.withLettersLook(q, drop.length, kind, font, color) } })
        dirty = true
        // The letters' colour is the editor's too: shown again.
        if (font != null || color != null) state.setRichTextDocument(WriterBridge.toEditor(meta, pictures, documentStyles), state.selection)
        message = if (drop == null) context.getString(R.string.words_drop_cap_off) else context.getString(R.string.words_drop_cap_on_pages)
    }
    /** Word's horizontal line: an empty paragraph with a line under it after this one, and a new paragraph to go on in. */
    fun horizontalLine() = change {
        val text = state.annotatedString.text
        val rich = state.toRichTextDocument()
        val end = WriterBridge.blockRange(text, rich, state.selection.max)?.second ?: return@change
        state.selection = androidx.compose.ui.text.TextRange(end)
        state.addTextAfterSelection("\n")
        state.addTextAfterSelection("\n")
        val cursor = state.selection.min
        val cur = current()
        val here = WriterBridge.paragraphsAt(state.annotatedString.text, state.toRichTextDocument(), cursor, cursor).singleOrNull()
            ?.takeIf { state.toRichTextDocument().blocks.count { !it.isLineBreak } == cur.paras.size && it > 0 } ?: return@change
        meta = cur.copy(paras = cur.paras.mapIndexed { i, p -> when (i) {
            here - 1 -> p.copy(heading = 0, list = null, keep = OfficeFormats.withBorders(null, kind, ParaBox(bottom = Border(0.75f, 0))))
            here -> p.copy(heading = 0, list = null, keep = OfficeFormats.withBorders(p.keep, kind, null))
            else -> p
        } })
        dirty = true
        state.setRichTextDocument(WriterBridge.toEditor(meta, pictures, documentStyles), androidx.compose.ui.text.TextRange(cursor))
    }
    // Comments (asked 2026-09-29): where each one's letters are in the editor, and the one the cursor is in.
    val commentsShown by remember { derivedStateOf {
        if (meta.comments.isEmpty()) emptyList() else {
            val text = state.annotatedString.text
            val places = WriterBridge.blockPlaces(text, state.toRichTextDocument())
            val live = current()
            val byId = live.comments.associateBy { it.id }
            live.paras.flatMapIndexed { i, p ->
                var at = 0
                // Replies: shown in their comment's card, not over the letters again.
                p.runs.flatMap { r -> r.comments.mapNotNull { id -> byId[id]?.takeIf { it.parent == null } }.flatMap { c -> WriterBridge.editorSpans(places, i, at, at + r.text.length).map { c to it } }.also { at += r.text.length } }
            }
        }
    } }
    val hereComment by remember { derivedStateOf { state.selection.min.let { at -> commentsShown.firstOrNull { (_, r) -> at in r.first..r.last + 1 }?.first } } }
    var replyTo by remember { mutableStateOf<DocComment?>(null) }
    var editingComment by remember { mutableStateOf<DocComment?>(null) }
    fun replyComment(parent: DocComment, author: String, text: String) = change {
        val cur = current()
        meta = OfficeFormats.withReply(cur, parent.id, DocComment(OfficeFormats.newCommentId(cur, kind), author, java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString(), text,
            initials = author.split(' ').filter { it.isNotBlank() }.joinToString("") { it.take(1) }.uppercase()))
        dirty = true
    }
    fun resolveComment(c: DocComment) = change { meta = OfficeFormats.withDone(current(), c.id, !c.done); dirty = true }
    var commentsOpen by remember { mutableStateOf(false) } // the phone's sidebar
    var editorLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var commenting by remember { mutableStateOf(false) }
    /** The chosen letters (from, to), or with none chosen the word at the cursor (Word's way for a letter command). */
    fun chosenOrWord(): Pair<Int, Int> {
        val t = state.annotatedString.text
        val sel = state.selection
        if (!sel.collapsed) return sel.min to sel.max
        var a = sel.min; var z = sel.min
        while (a > 0 && t[a - 1].isLetterOrDigit()) a--
        while (z < t.length && t[z].isLetterOrDigit()) z++
        return a to z
    }
    /** Insert › Comment: on the chosen letters, or the word at the cursor. */
    fun addComment(author: String, text: String) = change {
        val t = state.annotatedString.text
        val (from, to) = chosenOrWord()
        val cur = current()
        val spans = WriterBridge.paragraphSpans(WriterBridge.blockPlaces(t, state.toRichTextDocument()), from, to)
        if (spans.isEmpty() || state.toRichTextDocument().blocks.count { !it.isLineBreak } != cur.paras.size) { message = context.getString(R.string.words_choose_words_first); return@change }
        val initials = author.split(' ').filter { it.isNotBlank() }.joinToString("") { it.take(1) }.uppercase()
        meta = OfficeFormats.withComment(cur, spans, DocComment(OfficeFormats.newCommentId(cur, kind), author, java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString(), text, initials = initials))
        dirty = true
    }
    // References › Citations (APA 7): the sources, a citation at the cursor, the reference list, Update.
    var citing by remember { mutableStateOf(false) }
    var sourcesOpen by remember { mutableStateOf(false) }
    var sourceEdit by remember { mutableStateOf<DocSource?>(null) }
    var sourceNew by remember { mutableStateOf(false) }
    /** Shown again after a Writer change of the citations' letters. */
    fun reshow() = state.setRichTextDocument(WriterBridge.toEditor(meta, pictures, documentStyles), state.selection)
    fun saveSource(s: DocSource) = change {
        val cur = current()
        val tagged = if (s.tag.isBlank()) s.copy(tag = Citations.tagFor(kind, s, cur.sources.map { it.tag }, cur.citeStyle)) else s
        val list = if (cur.sources.any { it.tag == tagged.tag }) cur.sources.map { if (it.tag == tagged.tag) tagged else it } else cur.sources + tagged
        meta = Citations.update(cur.copy(sources = list), kind)
        dirty = true; reshow()
    }
    /** References › Sources › Import: a Tetra sources file's references added (none twice), the list brought up to date. */
    fun importSourcesText(text: () -> String?) {
        val read = runCatching { text()?.let(SourcesImport::read) ?: throw IllegalArgumentException(context.getString(R.string.words_nothing_to_import)) }
        val r = read.getOrNull() ?: run { message = read.exceptionOrNull()?.let { if (it is SourcesImport.NotSourcesFile) context.getString(R.string.words_not_sources_file) else it.message } ?: context.getString(R.string.words_file_unreadable); WriterLog.w("sources import", read.exceptionOrNull()); return }
        change {
            val cur = current()
            val added = ArrayList<DocSource>()
            for (s in r.sources) if (!SourcesImport.same(s, cur.sources + added)) added += s.copy(tag = Citations.tagFor(kind, s, (cur.sources + added).map { it.tag }, cur.citeStyle))
            meta = Citations.update(cur.copy(sources = cur.sources + added), kind)
            dirty = true; reshow()
            message = context.resources.getQuantityString(R.plurals.words_sources_added, added.size, added.size) + (if (r.sources.size > added.size) context.resources.getQuantityString(R.plurals.words_sources_already_here, r.sources.size - added.size, r.sources.size - added.size) else "") + (if (r.skipped.isNotEmpty()) context.getString(R.string.words_sources_skipped, r.skipped.joinToString { context.getString(if (it.untitled) R.string.words_skipped_no_title else R.string.words_skipped_not_source, it.number) }) else "")
            WriterLog.i("sources import: ${r.sources.size} read, ${added.size} added, skipped ${r.skipped}")
        }
    }
    fun importSources(uri: Uri) = importSourcesText { context.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) } }
    val pickSources = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked -> picked?.let { importSources(it) } }
    /** The sources Claude made (tetra-sources skill), copied from its answer. */
    fun pasteSources() = importSourcesText {
        context.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
            ?: throw IllegalArgumentException(context.getString(R.string.words_nothing_copied_json))
    }
    /** A request for the Claude app (Android's share sheet): this paper's sources as a Tetra sources file. */
    fun askClaudeForSources() {
        val cur = current()
        val topic = cur.paras.firstOrNull { it.heading == 1 || it.keep?.style?.lowercase() == "title" }?.text?.trim()?.takeIf { it.isNotEmpty() }
            ?: cur.paras.firstOrNull { it.text.isNotBlank() }?.text?.trim()?.take(160).orEmpty()
        val have = cur.sources.joinToString("\n") { "- " + Apa.entry(it).joinToString("") { r -> r.text } }
        val ask = buildString {
            append("Using the tetra-sources skill, make a Tetra sources file (JSON) for my paper \"$name\"")
            if (topic.isNotEmpty()) append(" — its topic: $topic")
            append(".\nOnly real, checked references with their DOIs. I will paste your JSON into Tetra.")
            if (have.isNotEmpty()) append("\nIt already cites (leave these out):\n$have")
        }
        WriterLog.i("ask Claude for sources: ${cur.sources.size} known")
        context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, ask), context.getString(R.string.words_ask_claude_sources)))
    }
    fun deleteSource(s: DocSource) = change { meta = current().let { it.copy(sources = it.sources.filter { x -> x.tag != s.tag }) }; dirty = true }
    fun insertCitation(chosenSources: List<DocSource>, page: String, hide: Set<Char> = emptySet()) = change {
        val cite = Citations.cite(kind, chosenSources.map { it.tag }, page, hide)
        val text = Citations.text(cite, meta.sources, meta.citeStyle)
        if (!state.selection.collapsed) state.removeSelectedText()
        // After a word, a space first (Word's), not part of the citation.
        state.annotatedString.text.getOrNull(state.selection.min - 1)?.takeIf { !it.isWhitespace() && it != '(' }?.let { state.addTextAfterSelection(" ") }
        val start = state.selection.min
        state.addTextAfterSelection(text)
        val t = state.annotatedString.text
        val spans = WriterBridge.paragraphSpans(WriterBridge.blockPlaces(t, state.toRichTextDocument()), start, start + text.length)
        meta = OfficeFormats.withCite(current(), spans, cite)
        if (meta.citeStyle == CiteStyle.IEEE) { meta = Citations.update(meta, kind); reshow() } // the numbers after it move up one
        dirty = true
    }
    fun insertReferenceList() = change {
        val cur = current()
        meta = Citations.insertList(cur, kind, chosen()?.first?.minOrNull() ?: cur.paras.size)
        dirty = true; reshow()
        message = context.getString(R.string.words_references_updated)
    }
    // References › Caption (Phase 2, asked 2026-10-13): "Figure 1: …" under the picture or table at the cursor, its number
    // Word's SEQ field (renumbered by Update fields), in the document's Caption style (Word's own when it has none).
    var captionOpen by remember { mutableStateOf(false) }
    fun insertCaption(label: String, text: String) = change {
        val cur = current()
        val at = ((chosen()?.first?.maxOrNull() ?: (cur.paras.size - 1)) + 1).coerceIn(0, cur.paras.size)
        val style = documentStyles.paragraphStyles().firstOrNull { it.name.equals("caption", ignoreCase = true) }?.id ?: "Caption".takeIf { kind == OfficeKind.Docx }
        val earlier = cur.paras.take(at).count { p -> p.runs.any { Fields.instr(it)?.split(' ')?.getOrNull(1) == label } || (kind == OfficeKind.Odt && p.text.startsWith("$label ")) }
        val number = if (kind == OfficeKind.Docx) DocRun("${earlier + 1}", cite = FIELD_CITE + "SEQ $label \\* ARABIC") else DocRun("${earlier + 1}") // an .odt: the number as letters
        val caption = DocPara(listOf(DocRun("$label "), number) + if (text.isBlank()) emptyList() else listOf(DocRun(": ${text.trim()}")), keep = style?.let { DocKeep(it) })
        meta = Fields.update(cur.copy(paras = cur.paras.take(at) + caption + cur.paras.drop(at)))
        WriterLog.i("caption: $label at paragraph $at")
        dirty = true; reshow(); message = context.getString(R.string.words_caption_inserted)
    }
    var pageStarts by remember { mutableStateOf<List<Pair<Int, Int>>>(emptyList()) } // (paragraph, page number) where each later page begins
    /** The page a paragraph is on, as the pages laid it out last (Print layout); null when there are no pages. */
    fun pageOfPara(j: Int): Int? = if (!onPage) null else (pageStarts.lastOrNull { it.first <= j }?.second ?: 1)
    fun updateCitations() = change { meta = Fields.update(Citations.update(current(), kind), ::pageOfPara); dirty = true; reshow(); message = context.getString(R.string.words_citations_updated) }
    // References › Cross-reference (Phase 2): a caption's words or its page, kept up to date by Update fields — Word's REF
    // and PAGEREF fields to a hidden _Ref bookmark on the caption (Word's own way).
    var crossRefOpen by remember { mutableStateOf(false) }
    fun captions(doc: OfficeDoc) = doc.paras.indices.filter { i -> doc.paras[i].runs.any { Fields.instr(it)?.startsWith("SEQ ", true) == true } }
    /** [how]: "caption" (its words), "label" (its label and number only: "Figure 3"), "page". */
    fun insertCrossReference(target: Int, how: String) = change {
        val cur = current()
        val p = cur.paras.getOrNull(target) ?: return@change
        val page = how == "page"
        // Label and number: a bookmark round those alone (Word's), else one on the whole caption.
        val key = if (how == "label") LABEL_BOOKMARKS else BOOKMARKS
        val marks = p.keep?.parts?.firstOrNull { it.first == key }?.second?.split(' ').orEmpty()
        val name = marks.firstOrNull { it.startsWith("_Ref") } ?: "_Ref${(100000000..999999999).random()}"
        val instr = FIELD_CITE + (if (page) "PAGEREF" else "REF") + " $name \\h"
        val text = if (page) (pageOfPara(target) ?: 1).toString() else if (how == "label") Fields.labelAndNumber(p) else p.text.trim()
        if (!state.selection.collapsed) state.removeSelectedText()
        val start = state.selection.min
        state.addTextAfterSelection(text)
        val spans = WriterBridge.paragraphSpans(WriterBridge.blockPlaces(state.annotatedString.text, state.toRichTextDocument()), start, start + text.length)
        val withRef = OfficeFormats.withCite(current(), spans, instr)
        // The caption gets its bookmark (once); the paragraphs are the same ones, the reference only added letters.
        meta = if (name in marks) withRef else withRef.copy(paras = withRef.paras.mapIndexed { i, q -> if (i != target) q else {
            val keep = q.keep ?: DocKeep()
            q.copy(keep = keep.copy(parts = keep.parts.filter { it.first != key } + (key to (marks + name).joinToString(" "))))
        } })
        WriterLog.i("cross-reference: $how of paragraph $target ($name)")
        dirty = true; reshow(); message = context.getString(R.string.words_cross_reference_inserted)
    }
    // References › Footnotes (asked 2026-09-30): a note's number in the text; its words in a bar and a dialog.
    /** Each note's reference, where it is in the editor: the note and its number. */
    val notesShown by remember { derivedStateOf {
        if (meta.paras.none { p -> p.runs.any { it.note != null } }) emptyList() else {
            val text = state.annotatedString.text
            val places = WriterBridge.blockPlaces(text, state.toRichTextDocument())
            current().paras.flatMapIndexed { i, p ->
                var at = 0
                p.runs.flatMap { r -> (r.note?.let { n -> WriterBridge.editorSpans(places, i, at, at + r.text.length).map { Triple(n, r.text, it) } } ?: emptyList()).also { at += r.text.length } }
            }
        }
    } }
    val hereNote by remember { derivedStateOf { state.selection.min.let { at -> notesShown.firstOrNull { (_, _, r) -> at in r.first..r.last + 1 } } } }
    var noteEditing by remember { mutableStateOf<Pair<DocNote, String>?>(null) } // the note, its number
    // References › a citation tapped (Word's citation menu, Phase 2): its sources to edit, or the citation changed.
    /** Each citation's place in the editor: Word's CITATION instruction (or an ODT mark's tag) and its letters. */
    val citesShown by remember { derivedStateOf {
        if (meta.paras.none { p -> p.runs.any { it.cite != null && !isField(it.cite) } }) emptyList() else {
            val places = WriterBridge.blockPlaces(state.annotatedString.text, state.toRichTextDocument())
            current().paras.flatMapIndexed { i, p ->
                var at = 0
                p.runs.flatMap { r -> (r.cite?.takeIf { !isField(it) }?.let { c -> WriterBridge.editorSpans(places, i, at, at + r.text.length).map { c to it } } ?: emptyList()).also { at += r.text.length } }
            }
        }
    } }
    /** The citation the cursor is inside (not at its edges: typing on after one is not a tap on it). */
    val hereCite by remember { derivedStateOf { state.selection.min.let { at -> citesShown.firstOrNull { (_, r) -> at in r.first + 1..r.last } } } }
    var citeChanging by remember { mutableStateOf<Pair<String, IntRange>?>(null) }
    fun insertNote(end: Boolean) = change {
        if (!state.selection.collapsed) state.selection = androidx.compose.ui.text.TextRange(state.selection.max)
        val start = state.selection.min
        state.addTextAfterSelection("1") // its number, worked out below
        val t = state.annotatedString.text
        val spans = WriterBridge.paragraphSpans(WriterBridge.blockPlaces(t, state.toRichTextDocument()), start, start + 1)
        val style = if (kind == OfficeKind.Docx) (if (end) "EndnoteText" else "FootnoteText") else (if (end) "Endnote" else "Footnote")
        val note = DocNote(end, listOf(DocPara(emptyList(), keep = DocKeep(style))))
        meta = OfficeFormats.numberNotes(OfficeFormats.withNote(current(), spans, note))
        dirty = true
        reshow()
        state.selection = androidx.compose.ui.text.TextRange(start + 1)
        noteEditing = note to (notesShown.firstOrNull { it.first == note }?.second ?: "")
    }
    var noteNumbering by remember { mutableStateOf(false) }
    var artEditing by remember { mutableStateOf<Pair<String, String>?>(null) } // a WordArt's key and words: its look changed after
    /** References › Note numbers: the footnotes' and endnotes' formats, every note renumbered. */
    fun setNoteFormats(foot: String, end: String) = change {
        meta = OfficeFormats.numberNotes(current().copy(footFormat = foot, endFormat = end))
        dirty = true
        reshow()
    }
    fun saveNote(old: DocNote, text: String?) = change {
        val new = text?.let { OfficeFormats.noteEdited(old, it) }
        meta = OfficeFormats.replaceNote(current(), old, new)
        dirty = true
        reshow()
        noteEditing = null
    }
    fun deleteComment(id: String) = change { meta = OfficeFormats.withoutComment(current(), id); dirty = true }
    fun commentActions(open: (DocComment) -> Unit) = CommentActions({ c -> meta.comments.filter { it.parent == c.id } }, open, { deleteComment(it.id) }, { replyTo = it }, { resolveComment(it) }, { editingComment = it })
    /** Review: a comment's words changed (its author, date, replies and place kept). */
    fun editComment(c: DocComment, text: String) = change {
        val cur = current()
        meta = cur.copy(comments = cur.comments.map { if (it.id == c.id) OfficeFormats.withCommentText(it, text) else it })
        dirty = true; WriterLog.i("comment ${c.id} edited")
    }
    // Review › Track changes (asked 2026-09-30): where each change is in the editor's text, and the one at the cursor.
    val changesShown by remember { derivedStateOf {
        if (!TrackChanges.any(meta)) emptyList() else {
            val text = state.annotatedString.text
            val places = WriterBridge.blockPlaces(text, state.toRichTextDocument())
            TrackChanges.list(current()).flatMap { c ->
                if (c.from == c.to) listOfNotNull(places.lastOrNull { it.para == c.para }?.let { Triple(c, it.to, it.to) }) // a paragraph mark: its end
                else WriterBridge.editorSpans(places, c.para, c.from, c.to).map { Triple(c, it.first, it.last + 1) }
            }
        }
    } }
    /** The change at a cursor: the one it is inside, else the one just before it (between two, never both). */
    fun changeAt(at: Int) = (changesShown.firstOrNull { (_, a, z) -> at in a until z } ?: changesShown.lastOrNull { (_, a, z) -> at in a..z })?.first
    val hereChange by remember { derivedStateOf { changeAt(state.selection.min) } }
    // Format painter (the roadmap's "rest of Word", 2026-10-01): the run at the cursor's look, taken; the next words
    // chosen take it once the selection holds still (as Word paints on letting go), once.
    var painter by remember { mutableStateOf<DocRun?>(null) }
    fun runAt(at: Int): DocRun? {
        val (para, from, _) = WriterBridge.paragraphSpans(WriterBridge.blockPlaces(state.annotatedString.text, state.toRichTextDocument()), at, at + 1).firstOrNull()
            ?: WriterBridge.paragraphSpans(WriterBridge.blockPlaces(state.annotatedString.text, state.toRichTextDocument()), (at - 1).coerceAtLeast(0), at).firstOrNull() ?: return null
        var pos = 0
        return current().paras.getOrNull(para)?.runs?.firstOrNull { r -> (from < pos + r.text.length).also { pos += r.text.length } && r.image == null }
    }
    /** Home › Font: the chosen letters (or the word at the cursor) in [name]; letters typed after them take it on. */
    fun setFont(name: String) = change {
        val (from, to) = chosenOrWord()
        val sel = state.selection
        val spans = WriterBridge.paragraphSpans(WriterBridge.blockPlaces(state.annotatedString.text, state.toRichTextDocument()), from, to)
        if (spans.isEmpty()) { message = context.getString(R.string.words_choose_words_first); return@change }
        meta = WriterBridge.restyled(current(), spans) { OfficeFormats.withFont(it, kind, name) }
        dirty = true
        state.setRichTextDocument(WriterBridge.toEditor(meta, pictures, documentStyles), sel)
        WriterLog.i("font $name on ${to - from} letters")
    }
    /** Home › Character spacing (Word's Font › Advanced): [pt] after each letter of the chosen words, less than 0 closer, 0 normal. */
    fun setSpacing(pt: Float) = change {
        val (from, to) = chosenOrWord()
        val sel = state.selection
        val spans = WriterBridge.paragraphSpans(WriterBridge.blockPlaces(state.annotatedString.text, state.toRichTextDocument()), from, to)
        if (spans.isEmpty()) { message = context.getString(R.string.words_choose_words_first); return@change }
        meta = WriterBridge.restyled(current(), spans) { LetterLooks.withSpacing(it, kind, pt) }
        dirty = true
        state.setRichTextDocument(WriterBridge.toEditor(meta, pictures, documentStyles), sel)
        WriterLog.i("character spacing $pt pt on ${to - from} letters")
    }
    fun paint(look: DocRun, sel: androidx.compose.ui.text.TextRange) {
        change {
            val spans = WriterBridge.paragraphSpans(WriterBridge.blockPlaces(state.annotatedString.text, state.toRichTextDocument()), sel.min, sel.max)
            meta = WriterBridge.painted(current(), spans, look)
            dirty = true
            state.setRichTextDocument(WriterBridge.toEditor(meta, pictures, documentStyles), sel)
        }
        WriterLog.i("format painted on ${sel.max - sel.min} letters")
    }
    // Bold and Italic as Word's: on where the paragraph's style makes the words bold/italic too; off there takes the style's
    // off for these words (asked 14 Oct: "if the style commands titles to be bold, show that in the buttons as well").
    fun boldHere(s: SpanStyle) = (s.fontWeight?.weight ?: 400) >= 600
    fun toggleBold(s: SpanStyle) {
        val weight = s.fontWeight?.weight
        if (weight != null && weight >= 600) {
            state.removeSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
            if (weight == STYLE_WEIGHT) { state.removeSpanStyle(SpanStyle(fontWeight = FontWeight(STYLE_WEIGHT))); state.addSpanStyle(SpanStyle(fontWeight = FontWeight(UNBOLD_WEIGHT))) }
        } else {
            state.removeSpanStyle(SpanStyle(fontWeight = FontWeight(UNBOLD_WEIGHT)))
            state.addSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
        }
        WriterLog.i("bold ${if (weight != null && weight >= 600) "off" else "on"} (was weight $weight) on ${state.selection}")
    }
    fun toggleItalic(s: SpanStyle) {
        val slantColor = s.shadow?.color?.toArgb()?.toLong()?.and(0xFFFFFFFFL)
        if (s.fontStyle == FontStyle.Italic) {
            state.removeSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
            if (slantColor == STYLE_SLANT) { state.removeSpanStyle(SpanStyle(shadow = s.shadow)); state.addSpanStyle(SpanStyle(shadow = androidx.compose.ui.graphics.Shadow(Color(UNSLANT.toInt()), Offset.Zero, 0f))) }
        } else {
            if (slantColor == UNSLANT) state.removeSpanStyle(SpanStyle(shadow = s.shadow))
            state.addSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
        }
        WriterLog.i("italic ${if (s.fontStyle == FontStyle.Italic) "off" else "on"} on ${state.selection}")
    }
    /** Selection › Copy formatting: kept here, for Paste formatting (also on other words, later). */
    var copiedLook by remember { mutableStateOf<DocRun?>(null) }
    // Words chosen: the Selection tab is there (first; Android's floating menu is Writer's own) but the tab in use stays
    // (asked 14 Oct: Home stays the default); let go, it goes, and if it was in use the tab before it is back.
    val selectionTabShown by remember { derivedStateOf { (!state.selection.collapsed || textToolbar.shown) && selectedPicture == null && cellEdit == null } }
    LaunchedEffect(Unit) {
        snapshotFlow { selectionTabShown }.collect { chosen ->
            if (!chosen && tab == WriterTab.Selection) tab = beforeSelection ?: WriterTab.Home
            if (chosen) beforeSelection = tab.takeIf { it != WriterTab.Selection } ?: beforeSelection
        }
    }
    val clipboard = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager }
    fun chosenText() = state.selection.let { sel -> state.annotatedString.text.substring(sel.min.coerceAtMost(state.annotatedString.length), sel.max.coerceAtMost(state.annotatedString.length)) }
    fun copyChosen(cutToo: Boolean) {
        val viaEditor = if (cutToo) textToolbar.cut else textToolbar.copy
        val letters = state.selection.length
        if (viaEditor != null) viaEditor() else {
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Writer", chosenText()))
            if (cutToo) state.replaceSelectedText("")
        }
        WriterLog.i("${if (cutToo) "cut" else "copied"} $letters letters (${if (viaEditor != null) "with their look" else "text"})")
    }
    fun pastePlain() {
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text.isNullOrEmpty()) { message = context.getString(R.string.words_nothing_copied); return }
        if (state.selection.collapsed) state.addTextAfterSelection(text) else state.replaceSelectedText(text)
        dirty = true
        WriterLog.i("pasted text only: ${text.length} letters")
    }
    fun pickPainter() {
        if (painter != null) { painter = null; WriterLog.i("format painter off"); return }
        painter = runAt(state.selection.min) ?: run { message = context.getString(R.string.words_painter_cursor); null }
        painter?.let { WriterLog.i("format painter: bold ${it.bold}, italic ${it.italic}, size ${it.sizePt}, colour ${it.color?.let { c -> "%06X".format(c) }}") }
        if (painter != null) message = context.getString(R.string.words_painter_choose)
    }
    LaunchedEffect(painter) {
        val look = painter ?: return@LaunchedEffect
        snapshotFlow { state.selection }.collectLatest { sel ->
            if (sel.collapsed) return@collectLatest
            delay(700) // still chosen: painted
            paint(look, sel)
            painter = null
        }
    }
    // Forms (asked 2026-10-01): where each content control's letters are, and the one the cursor is in (at its edge too).
    val controlsShown by remember { derivedStateOf {
        if (meta.paras.none { p -> p.runs.any { it.control != null } }) emptyList() else {
            val places = WriterBridge.blockPlaces(state.annotatedString.text, state.toRichTextDocument())
            current().paras.flatMapIndexed { i, p ->
                var at = 0
                p.runs.flatMap { r -> (r.control?.let { c -> WriterBridge.editorSpans(places, i, at, at + r.text.length).map { Triple(c, r.text, it) } } ?: emptyList()).also { at += r.text.length } }
            }
        }
    } }
    val hereControl by remember { derivedStateOf { state.selection.min.let { at -> controlsShown.firstOrNull { (_, _, r) -> at in r.first..r.last + 1 } } } }
    // Insert › Content control (Phase 2, Word's Developer tab): a new one at the cursor, its placeholder grey until filled.
    var controlNew by remember { mutableStateOf(false) }
    fun insertControl(newKind: Forms.New, title: String, items: List<String>, format: String) = change {
        val (pr, shown) = Forms.newControl(newKind, title, items, format)
        if (!state.selection.collapsed) state.removeSelectedText()
        // a picture control: Word's placeholder picture in it, filled from its bar (Choose picture)
        if (newKind == Forms.New.Picture) {
            val img = placeholderPicture()
            pictures[img.key] = img
            val at = placePicture(img)
            meta = current().let { d -> d.copy(paras = d.paras.map { p -> if (p.runs.none { it.image?.key == img.key }) p else p.copy(runs = p.runs.map { if (it.image?.key == img.key) it.copy(control = pr) else it }) }) }
            dirty = true; reshow()
            state.selection = androidx.compose.ui.text.TextRange(at + 1)
            WriterLog.i("content control: picture \"$title\"")
            return@change
        }
        val start = state.selection.min
        state.addTextAfterSelection("$shown ") // a space after it, outside: what is typed next is not in it
        val spans = WriterBridge.paragraphSpans(WriterBridge.blockPlaces(state.annotatedString.text, state.toRichTextDocument()), start, start + shown.length)
        meta = WriterBridge.restyled(current(), spans) { it.copy(control = pr, color = if (newKind == Forms.New.CheckBox) it.color else 0x808080) }
        dirty = true; reshow()
        state.selection = androidx.compose.ui.text.TextRange(start + shown.length + 1) // after it and its space
        WriterLog.i("content control: ${newKind.label} \"$title\" (${items.size} items)")
    }
    fun fillControl(old: String, pr: String, text: String) = change {
        meta = Forms.fill(current(), old, pr, text)
        dirty = true
        reshow()
    }
    // Track changes on (Word files): each edit recorded as the reviewer's, compared with the document as it was.
    val prefs = remember { context.getSharedPreferences("writer", Context.MODE_PRIVATE) }
    var tracking by remember { mutableStateOf(false) }
    var askingName by remember { mutableStateOf(false) }
    // A Word file that says to track changes (Word's trackRevisions) opens with them on, as in Word
    LaunchedEffect(loaded) {
        if (!loaded || kind != OfficeKind.Docx || !PageDesign.tracksRevisions(original)) return@LaunchedEffect
        if (prefs.getString("author", "").isNullOrBlank()) askingName = true else { tracking = true; message = context.getString(R.string.words_document_tracks) }
    }
    /** Track changes on or off, and remembered in the Word file as Word remembers it. */
    fun setTracking(on: Boolean) {
        tracking = on
        message = if (on) context.getString(R.string.words_tracking_on) else context.getString(R.string.words_tracking_off)
        if (kind != OfficeKind.Docx || PageDesign.tracksRevisions(original) == on) return
        runCatching { original = OfficeFormats.zip(PageDesign.trackRevisions(OfficeFormats.unzip(original ?: OfficeFormats.write(OfficeDoc(emptyList()), kind)), on), null); dirty = true }
            .onFailure { WriterLog.e("track changes setting", it) }
    }
    LaunchedEffect(loaded, tracking) {
        if (!loaded || !tracking) return@LaunchedEffect
        val author = prefs.getString("author", "").orEmpty()
        meta = current() // what was typed before it was switched on is the document, not an edit
        snapshotFlow { state.annotatedString.text }.drop(1).collect {
            val now = current()
            if (now.paras.map { it.text } == meta.paras.map { it.text }) return@collect // a Writer change, shown (Accept, a style…): not an edit
            val me = DocChange(false, author, java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES).toString())
            val r = TrackChanges.record(meta.paras, now.paras, me, state.selection.min)
            meta = now.copy(paras = r.paras)
            if (r.reshow) state.setRichTextDocument(WriterBridge.toEditor(meta, pictures, documentStyles), androidx.compose.ui.text.TextRange(r.cursor))
        }
    }
    // Review › Spelling (asked 2026-09-30): Tetra's own Greek and English dictionaries (WriterSpelling), as one types.
    val speller = remember { WriterSpelling(context) }
    var spelling by remember { mutableStateOf(prefs.getBoolean("spelling", true)) }
    val spell = rememberSpelling(state, speller, spelling, loaded) // Words' spelling, shared with the PDF's texts (WriterSpelling.kt)
    fun nextMisspelled() {
        if (!spell.next()) message = if (spelling) context.getString(R.string.words_no_spelling_mistakes) else context.getString(R.string.words_spelling_off)
    }
    // Review › ✦ Suggestions (WriterSuggest): AI Assist's edits for the selection or the document, each marked on the page
    // until accepted (one step of Undo) or rejected; asked only on Send, only while AI Assist may use Words.
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { AiGate.load(context) } }
    val hasAiKey = remember { AssistantKey.has(context) }
    val suggestOn = AiGate.allows("writer") && hasAiKey
    var suggestAsking by remember { mutableStateOf(false) }
    var suggestBusy by remember { mutableStateOf<String?>(null) }
    var suggestions by remember { mutableStateOf(emptyList<WriterSuggest.Suggestion>()) }
    val suggestShown by remember { derivedStateOf { WriterSuggest.locate(state.annotatedString.text, suggestions) } }
    val hereSuggestion by remember { derivedStateOf { state.selection.min.let { at -> suggestShown.firstOrNull { at in it.at..it.end } ?: suggestShown.firstOrNull { it.at >= at } } ?: suggestShown.firstOrNull() } }
    fun goSuggestion(s: WriterSuggest.Suggestion?) { s ?: return; state.selection = androidx.compose.ui.text.TextRange(s.at, s.end); goText = s.at to System.nanoTime() }
    fun acceptSuggestion(s: WriterSuggest.Suggestion) {
        val rest = WriterSuggest.after(suggestShown, s)
        state.history.group { replaceKeepingLook(s.at, s.end, s.replace) }
        suggestions = rest; WriterLog.i("suggestion accepted")
        goSuggestion(rest.firstOrNull { it.at >= s.at } ?: rest.firstOrNull())
    }
    fun acceptAllSuggestions() {
        val all = suggestShown
        state.history.group { all.asReversed().forEach { replaceKeepingLook(it.at, it.end, it.replace) } }
        suggestions = emptyList(); message = context.getString(R.string.words_replaced_count, all.size); WriterLog.i("suggestions: all ${all.size} accepted")
    }
    fun askSuggestions(ask: String) {
        val text = state.annotatedString.text; val sel = state.selection
        val (base, part) = if (sel.collapsed) 0 to text else sel.min to text.substring(sel.min, sel.max)
        suggestions = emptyList(); suggestBusy = context.getString(R.string.words_suggest_asking)
        WriterLog.i("suggestions: asked for ${part.length} letters${if (ask.isBlank()) "" else ", with an instruction"}")
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { WriterSuggest.ask(context, part, base, ask) { n, of -> if (of > 1) suggestBusy = context.getString(R.string.words_suggest_asking_n, n, of) } } }
                .onSuccess { (list, cut) ->
                    suggestions = WriterSuggest.locate(state.annotatedString.text, list)
                    message = if (suggestions.isEmpty()) context.getString(R.string.words_suggest_none) else if (cut) context.getString(R.string.words_suggest_cut) else null
                    goSuggestion(suggestions.firstOrNull())
                }
                .onFailure { WriterLog.e("suggestions", it); message = it.shownMessage(context) }
            suggestBusy = null
        }
    }
    /** The next (or previous) change, selected. */
    fun goChange(forward: Boolean, from: Int = state.selection.min) {
        val starts = changesShown.map { it.second to it.third }.distinct().sortedBy { it.first }
        val to = if (forward) starts.firstOrNull { it.first > from } else starts.lastOrNull { it.first < from }
        if (to == null) { message = context.getString(if (starts.isEmpty()) R.string.words_no_tracked_changes else if (forward) R.string.words_no_changes_after else R.string.words_no_changes_before); return }
        state.selection = androidx.compose.ui.text.TextRange(to.first, to.second)
    }
    /** The changes at the cursor (or in the selection) accepted or rejected — or all of them — then on to the next. */
    fun decideChanges(accept: Boolean, all: Boolean = false) = change {
        val cur = current()
        val sel = state.selection
        val chosen = if (all) emptyList() else if (sel.collapsed) listOfNotNull(changeAt(sel.min))
            else changesShown.filter { (_, a, z) -> a < sel.max && z > sel.min || a == z && a in sel.min..sel.max }.map { it.first }.distinct()
        if (!all && chosen.isEmpty()) { message = context.getString(R.string.words_no_change_here); return@change }
        val at = if (all) sel.min else changesShown.filter { it.first in chosen }.minOfOrNull { it.second } ?: sel.min // before meta changes (it is read again then)
        meta = if (all) TrackChanges.all(cur, accept) else TrackChanges.decide(cur, chosen, accept)
        dirty = true
        state.setRichTextDocument(WriterBridge.toEditor(meta, pictures, documentStyles), androidx.compose.ui.text.TextRange(at.coerceIn(0, state.annotatedString.text.length)))
        message = context.getString(when { all && accept -> R.string.words_all_accepted; all -> R.string.words_all_rejected; accept -> R.string.words_accepted; else -> R.string.words_rejected })
        if (!all) goChange(true, at - 1)
    }
    /**
     * References › Table of contents: put at the cursor's paragraph (or, if the document has one, brought up to date),
     * its page numbers from the pages as Writer lays them out. ponytail: laid out on the main thread; fine for a
     * thesis-sized document, move off it if a long one stalls.
     */
    fun insertContents(levels: Int? = null) = change {
        val cur = current()
        val at = chosen()?.first?.minOrNull() ?: cur.paras.size
        val parts = packageParts
        val n = levels ?: Contents.levelsOf(cur, kind)
        meta = runCatching { Contents.insert(cur, kind, documentStyles, at, n) { d, pages ->
            OfficePages.layout(d, documentStyles, pages) { img -> img.bytes ?: img.part?.let { parts[it] } } } }
            .onFailure { WriterLog.e("contents", it) }.getOrNull() ?: run { message = context.getString(R.string.words_contents_failed); return@change }
        dirty = true
        state.setRichTextDocument(WriterBridge.toEditor(meta, pictures, documentStyles), state.selection)
        message = cur.paras.count { it.heading in 1..n }.let { h -> if (n == 1) context.getString(R.string.words_contents_made_level1, h) else context.getString(R.string.words_contents_made_levels, h, n) }
    }
    fun chooseParagraphs(then: (Set<Int>, ParaLook) -> Unit) {
        chosen()?.let { (at, look) -> then(at, look) } ?: run { message = context.getString(R.string.words_cursor_in_paragraph) }
    }
    /** Home › Multilevel list: Writer's numbered lists counted as [levels] say; the list at the cursor becomes one of them. */
    fun setNumbering(levels: List<Pair<String, String>>) {
        if (!state.isOrderedList) state.addOrderedList()
        chooseParagraphs { at, _ -> change {
            val cur = current()
            // The whole list the cursor is in, as Word changes a list rather than an item.
            var from = at.min(); var to = at.max()
            while (from > 0 && cur.paras[from - 1].list != null) from--
            while (to + 1 < cur.paras.size && cur.paras[to + 1].list != null) to++
            meta = cur.copy(numbering = levels, paras = cur.paras.mapIndexed { i, p -> if (i in from..to && p.list?.ordered == true && p.keep?.list != null) p.copy(keep = p.keep.copy(list = null)) else p })
            dirty = true
            state.setRichTextDocument(WriterBridge.toEditor(meta, pictures, documentStyles), state.selection)
            WriterLog.i("multilevel list ${levels.take(3)} on paragraphs $from..$to")
        } }
    }
    // The ruler shows the indents of the paragraph the cursor is in, and its marks set them (as Word's).
    var rulerAt by remember { mutableStateOf<Pair<Set<Int>, ParaLook>?>(null) }
    var hereFont by remember { mutableStateOf<String?>(null) }
    // …and the ribbon which style it has.
    LaunchedEffect(loaded, viewing) {
        if (!loaded || viewing) return@LaunchedEffect
        snapshotFlow { state.selection to meta.paras }.collectLatest { sel ->
            delay(200); rulerAt = chosen()
            // The font at the cursor: its letters' own, else its paragraph style's.
            hereFont = runAt(sel.first.min)?.font?.let { documentStyles.fontName(it) } ?: rulerAt?.second?.font
        }
    }
    val hereStyle = rulerAt?.first?.min()?.let { i -> meta.paras.getOrNull(i) }?.let { documentStyles.styleOf(it) }
    var stylesPage by remember { mutableStateOf(false) }
    var editingStyle by remember { mutableStateOf<StyleEntry?>(null) }
    var headerPage by remember { mutableStateOf(false) }
    var symbolPage by remember { mutableStateOf(false) }
    var pagePage by remember { mutableStateOf(false) }
    var shapesPage by remember { mutableStateOf(false) }
    /** Insert › Text box (the key null) or Picture › Edit text (a text box's key): its words being typed. */
    var boxing by remember { mutableStateOf<Pair<String?, String>?>(null) }
    var wordArt by remember { mutableStateOf(false) } // Insert › WordArt: its words and colour being chosen
    /** Insert › Chart (the key null) or Picture › Edit data (a chart's key): the chart being made. */
    var charting by remember { mutableStateOf<Pair<String?, DocChart>?>(null) }
    var chartUnder by remember { mutableStateOf<String?>(null) } // Table › Make chart: the table (its key) the new chart goes under
    /**
     * The drawing being drawn: on [pages] (each with the paragraph a drawing there is anchored in), or in a box
     * [boxPt] wide when there are none; [edit] is the key of a drawing drawn on again, [strokes] what it had.
     */
    class Drawing(val pages: List<LaidPage> = emptyList(), val anchors: List<Int> = emptyList(), val start: Int = 0, val edit: String? = null,
        val strokes: List<InkStroke> = emptyList(), val boxPt: Float = 0f, val boxAspect: Float = 0.6f)
    var drawing by remember { mutableStateOf<Drawing?>(null) }
    /**
     * A drawing in front of the text (the user, 2026-09-30: drawings go on top of the text): anchored where a paragraph
     * starts, as Word does — never between two letters of a word — [anchor] or the cursor's; at [place] on the page, or
     * (from the drawing box) at the paragraph's top left; the Picture tab moves it into the text if wanted.
     */
    fun putDrawing(bytes: ByteArray, wPt: Float, hPt: Float, place: Pair<Float, Float>?, anchor: Int?, key: String = "n" + java.util.UUID.randomUUID()) {
        val img = DocImage(key, null, wPt, hPt, null, bytes, "png", PictureWrap.InFront, onPage = place)
        pictures[img.key] = img
        val text = state.annotatedString.text; val rich = state.toRichTextDocument()
        val cursor = state.selection
        val start = anchor?.let { WriterBridge.paragraphStarts(text, rich).getOrNull(it) } ?: WriterBridge.blockRange(text, rich, cursor.min)?.first
        start?.let { state.selection = androidx.compose.ui.text.TextRange(it) }
        placePicture(img)
        val shift = if (start != null && cursor.min >= start) 1 else 0
        state.selection = androidx.compose.ui.text.TextRange(cursor.min + shift, cursor.max + shift)
        message = context.getString(R.string.words_drawn_in_front)
    }
    // Insert › Signature (the user, 22 Oct: "does Word has sign by hand or image? … make that as well"): one signature, the PDF
    // editor's too, kept in the app (never in a file but where it is put), drawn once or taken from a picture of it
    val signatureFile = remember { File(context.filesDir, "pdf-signature.png") }
    var drawingSignature by remember { mutableStateOf(false) }
    var redoSignature by remember { mutableStateOf<String?>(null) } // the signature in the document being redrawn
    fun insertSignature() {
        val bytes = runCatching { signatureFile.readBytes() }.getOrNull() ?: run { drawingSignature = true; return }
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        // a picture box in front of the text (the user: "like an image box you can move rotate resize"): dragged, sized and
        // turned with its handles, as Draw's drawings
        putDrawing(bytes, 150f, 150f * o.outHeight / o.outWidth.coerceAtLeast(1), null, null, key = SIGNATURE_KEY + java.util.UUID.randomUUID())
        message = context.getString(R.string.words_signature_in)
        WriterLog.i("signature put in")
    }
    val pickSignature = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
        picked ?: return@rememberLauncherForActivityResult
        runCatching {
            signatureFromPhoto(context, picked, signatureFile)
        }.onSuccess { insertSignature() }.onFailure { message = context.getString(R.string.words_picture_not_used) }
    }
    // Pictures in front of or behind the text (asked 2026-10-01: a cover's logos showed as tiny marks in the corner):
    // drawn on the page in their place, as the pages show them — from the pages' layout, measured from their paragraph's
    // top, so typing above them moves them with it. Worked out again after each Writer change, not each keystroke.
    var floatsShown by remember { mutableStateOf<Map<String, FloatShown>>(emptyMap()) }
    var headPage by remember { mutableStateOf<LaidPage?>(null) } // the first page as laid out: its header, shown faded above the text
    LaunchedEffect(meta, styleEdits, documentStyles, onPage, loaded) { // styleEdits: a picture changed (meta stays)
        if (!loaded) return@LaunchedEffect
        val doc = current()
        val over = doc.paras.indices.flatMap { j -> doc.paras[j].runs.mapNotNull { r -> r.image?.takeIf { PictureXml.wrapOf(it).let { w -> w == PictureWrap.InFront || w == PictureWrap.Behind } }?.let { it.key to j } } }.toMap()
        if (!loaded || !onPage) { floatsShown = emptyMap(); headPage = null; pageStarts = emptyList(); return@LaunchedEffect }
        delay(300)
        val parts = packageParts
        val tops = FloatArray(doc.paras.size)
        val pageOfPara = IntArray(doc.paras.size) { -1 }
        val laid = withContext(Dispatchers.Default) { runCatching { OfficePages.layout(doc, documentStyles, null, pageOfPara, tops) { img -> img.bytes ?: img.part?.let { parts[it] } } }.getOrNull() } ?: return@LaunchedEffect
        headPage = laid.firstOrNull()
        // Page ends (asked 2026-09-30: "why is there no page view on edit"): where each page's first paragraph starts.
        // ponytail: a page that begins inside a paragraph is marked at no place (the editor's lines are not the pages').
        pageStarts = (1 until laid.size).mapNotNull { k -> pageOfPara.indices.firstOrNull { pageOfPara[it] == k }?.takeIf { j -> j > 0 && pageOfPara[j - 1] == k - 1 }?.let { it to laid[k].number } }
        floatsShown = laid.flatMap { page -> page.floats.mapNotNull { f -> f.key?.let { k -> over[k]?.let { j -> k to FloatShown(f.x - page.page.left, f.y - tops[j], f.w, f.h, f.behind, f.x, f.y) } } } }.toMap()
    }
    /** The pages of [doc] as the pages view lays them out, and the page each paragraph starts on (-1: none). */
    suspend fun laidOut(doc: OfficeDoc): Pair<List<LaidPage>, IntArray>? {
        val parts = packageParts
        val index = IntArray(doc.paras.size) { -1 }
        return withContext(Dispatchers.Default) { runCatching { OfficePages.layout(doc, documentStyles, pageIndexOf = index) { img -> img.bytes ?: img.part?.let { parts[it] } } }
            .onFailure { WriterLog.e("draw", it) }.getOrNull() }?.let { it to index } ?: run { message = context.getString(R.string.words_page_not_laid_out); null }
    }
    /**
     * Draw › Draw on page: the page the cursor is on, as the pages show it, to draw over (‹ › for another); the drawing
     * is anchored in the first paragraph of text that starts on its page, placed from the page's corner. A page no
     * paragraph starts on (the middle of a long one) is left out.
     */
    fun drawOnPage() = chooseParagraphs { at, _ ->
        val cur = current()
        scope.launch {
            val (laid, index) = laidOut(cur) ?: return@launch
            val anchors = laid.indices.map { k -> cur.paras.indices.firstOrNull { index[it] == k && cur.paras[it].table == null } ?: -1 }
            val usable = laid.indices.filter { anchors[it] >= 0 }
            drawing = Drawing(usable.map { laid[it] }, usable.map { anchors[it] }, usable.indexOf(index[at.min()]).coerceAtLeast(0))
        }
    }
    /** Arrange › Bring to front / Send to back: [key] over (or under) every other floating picture. */
    fun arrange(key: String, front: Boolean) {
        val img = pictures[key] ?: return
        val others = pictures.values.filter { it.key != key && PictureXml.wrapOf(it) != PictureWrap.InLine }.map { PictureXml.zOf(it) }
        // Word counts from about 251 million, LibreOffice from 0: below 0 the others go up one instead.
        val z = if (front) (others.maxOrNull() ?: PictureXml.zOf(img)) + 1 else (others.minOrNull() ?: PictureXml.zOf(img)) - 1
        val moved = PictureXml.withZ(img, z.coerceAtLeast(0)) ?: run { message = context.getString(R.string.words_wait_arrange); return }
        change {
            if (z < 0) pictures.values.toList().filter { it.key != key && PictureXml.wrapOf(it) != PictureWrap.InLine }
                .forEach { o -> PictureXml.withZ(o, PictureXml.zOf(o) + 1)?.let { pictures[o.key] = it } }
            changePicture(key, moved)
        }
        message = context.getString(if (front) R.string.words_in_front_of_others else R.string.words_behind_others)
    }
    /** Arrange › Align: [key] against the margins ("left", "center", "right", "top", "middle", "bottom"), staying put the other way. */
    fun align(key: String, how: String) = scope.launch {
        val (laid, _) = laidOut(current()) ?: return@launch
        val page = laid.firstOrNull { p -> p.floats.any { it.key == key } } ?: run { message = context.getString(R.string.words_not_on_a_page); return@launch }
        val f = page.floats.first { it.key == key }
        val pg = page.page
        val x = when (how) { "left" -> pg.left; "center" -> pg.left + (pg.textWidth - f.w) / 2; "right" -> pg.width - pg.right - f.w; else -> f.x }
        val y = when (how) { "top" -> pg.top; "middle" -> pg.top + (pg.height - pg.top - pg.bottom - f.h) / 2; "bottom" -> pg.height - pg.bottom - f.h; else -> f.y }
        val img = pictures[key] ?: return@launch
        changePicture(key, PictureXml.placed(img, x, y, kind))
        WriterLog.i("align $key $how")
    }
    // Arrange › Rotate, Group, Ungroup (Phase 2): any angle; two floating ones on a page made one (Word's group), and back.
    var rotating by remember { mutableStateOf<String?>(null) }
    var shapeOptions by remember { mutableStateOf<String?>(null) } // Picture › Shape options: the shape's key
    var standards by remember { mutableStateOf(false) } // Design › Standards
    var designGallery by remember { mutableStateOf<DesignGallery?>(null) } // Design › Style set / Colours / Fonts
    var galleryChosen by remember { mutableStateOf<Any?>(null) } // the choice being previewed on the page
    var galleryStart by remember { mutableStateOf<WriterSnapshot?>(null) } // the document as it was when the gallery opened
    var galleryUndo by remember { mutableIntStateOf(0) }
    fun openGallery(g: DesignGallery) {
        galleryStart = snapshot(); galleryUndo = undone.size; galleryChosen = null; designGallery = g
        // Page numbers, headers and footers sit where the editor shows no page: the pages are shown while choosing.
        if (g == DesignGallery.PageNumbers || g == DesignGallery.HeaderFooter || g == DesignGallery.DropCap || g == DesignGallery.LineNumbers) { galleryViewing = viewing; viewing = true } // a drop cap too: only the pages draw it
    }
    fun galleryClosed() { galleryViewing?.let { viewing = it }; galleryViewing = null }
    // A gallery's choices shown on the page as they are tapped: OK keeps the last as one step, Cancel the document as it was.
    fun galleryCancel() { galleryStart?.let { show(it); while (undone.size > galleryUndo) undone.removeAt(undone.lastIndex) }; designGallery = null; galleryChosen = null; galleryClosed(); WriterLog.i("gallery: cancelled") }
    var artKey by remember { mutableStateOf<String?>(null) } // the WordArt a gallery put in
    fun galleryOk() {
        while (undone.size > galleryUndo + 1) undone.removeAt(undone.lastIndex)
        // WordArt put in: its words typed next, as Word selects "Your text here".
        if (designGallery == DesignGallery.WordArt) artKey?.let { boxing = it to "Your text here" }
        designGallery = null; galleryChosen = null; galleryClosed(); WriterLog.i("gallery: kept")
    }
    var grouping by remember { mutableStateOf<String?>(null) } // the first one chosen: the next one tapped joins it
    fun rotate(key: String, deg: Float) {
        val img = pictures[key] ?: return
        val turned = Arrange.rotated(img, deg) ?: run { message = context.getString(if (img.raw?.startsWith("<draw:g") == true) R.string.words_odt_group_rotate else R.string.words_wait_rotate); return }
        changePicture(key, turned)
        WriterLog.i("rotate $key to $deg°")
    }
    /** [runs] of the document's pictures changed by [swap] (null: taken out), the editor shown again. */
    fun swapPictures(swap: (DocRun) -> List<DocRun>?) = change {
        val cur = current()
        meta = cur.copy(paras = cur.paras.map { p -> p.copy(runs = p.runs.flatMap { r -> if (r.image == null) listOf(r) else swap(r) ?: listOf(r) }) })
        val sel = state.selection
        state.setRichTextDocument(WriterBridge.toEditor(meta, pictures, documentStyles), sel)
        dirty = true; styleEdits++
    }
    fun groupWith(first: String, second: String) = scope.launch {
        grouping = null
        val a = pictures[first]; val b = pictures[second]
        if (a?.raw == null || b?.raw == null) { message = context.getString(R.string.words_wait_group); return@launch }
        val (laid, _) = laidOut(current()) ?: return@launch
        val pa = laid.indexOfFirst { p -> p.floats.any { it.key == first } }; val pb = laid.indexOfFirst { p -> p.floats.any { it.key == second } }
        if (pa < 0 || pb < 0) { message = context.getString(R.string.words_group_must_float); return@launch }
        if (pa != pb) { message = context.getString(R.string.words_group_same_page); return@launch }
        fun box(k: String) = laid[pa].floats.first { it.key == k }.let { floatArrayOf(it.x, it.y, it.w, it.h) }
        val g = (if (kind == OfficeKind.Odt) Arrange::odtGroup else Arrange::group)(listOf(a to box(first), b to box(second)), PictureXml.wrapOf(a) ?: PictureWrap.Square, maxOf(PictureXml.zOf(a), PictureXml.zOf(b)), "g" + java.util.UUID.randomUUID())
            ?: run { message = context.getString(R.string.words_cannot_group); return@launch }
        // The document read from the editor first (it finds its pictures by their keys), then the map changed.
        swapPictures { r -> when (r.image?.key) { first -> listOf(r.copy(image = g)); second -> emptyList(); else -> null } }
        pictures.remove(first); pictures.remove(second); pictures[g.key] = g
        message = context.getString(R.string.words_grouped)
        WriterLog.i("group $first + $second -> ${g.key}")
    }
    fun ungroup(key: String) = scope.launch {
        val img = pictures[key] ?: return@launch
        val (laid, _) = laidOut(current()) ?: return@launch
        val f = laid.flatMap { it.floats }.firstOrNull { it.key == key } ?: run { message = context.getString(R.string.words_not_on_a_page); return@launch }
        val pieces = (if (img.raw?.startsWith("<draw:g") == true) Arrange::odtUngroup else Arrange::ungroup)(img, f.x, f.y, PictureXml.wrapOf(img) ?: PictureWrap.Square, PictureXml.zOf(img)) ?: run { message = context.getString(R.string.words_cannot_ungroup); return@launch }
        swapPictures { r -> if (r.image?.key == key) pieces.map { r.copy(image = it) } else null }
        pictures.remove(key); pieces.forEach { pictures[it.key] = it }
        message = context.getString(R.string.words_ungrouped_into, pieces.size)
        WriterLog.i("ungroup $key -> ${pieces.size}")
    }
    // The second one tapped while grouping: joined to the first.
    LaunchedEffect(selectedPicture?.second) { val first = grouping; val now = selectedPicture?.second; if (first != null && now != null && now != first) groupWith(first, now) }
    /** Picture › Position: the page a floating picture is on, as laid out, and its key. */
    var positioning by remember { mutableStateOf<Pair<LaidPage, String>?>(null) }
    fun position(key: String) = scope.launch {
        val (laid, _) = laidOut(current()) ?: return@launch
        positioning = laid.firstOrNull { p -> p.floats.any { it.key == key } }?.let { it to key }
            ?: run { message = context.getString(R.string.words_not_on_a_page); null }
    }
    /** A drawing made in Writer (its strokes ride in its PNG), drawn on again: on its page if it is placed on one, else in its box. */
    fun editDrawing(key: String) {
        val img = pictures[key] ?: return
        val (strokes, drawnAspect) = (img.bytes ?: img.part?.let { packageParts[it] })?.let { InkPicture.strokesOf(it) } ?: return
        val ky = img.heightPt / img.widthPt / drawnAspect // sized unevenly since: its strokes stretch with it
        val place = PictureXml.placeOf(img)?.takeIf { it.fromH == "page" && it.fromV == "page" && it.x != null && it.y != null }
        val cur = current()
        val para = cur.paras.indexOfFirst { p -> p.runs.any { it.image?.key == key } }
        if (place == null || para < 0) {
            drawing = Drawing(edit = key, strokes = strokes.map { s -> InkStroke(s.points.map { Offset(it.x, it.y * ky) }, s.color, s.widthMm, s.highlighter) },
                boxPt = img.widthPt, boxAspect = img.heightPt / img.widthPt)
            return
        }
        scope.launch {
            // Its page without it (it is drawn again), its strokes where they are on it.
            val (laid, index) = laidOut(cur.copy(paras = cur.paras.map { p -> p.copy(runs = p.runs.filter { it.image?.key != key }) })) ?: return@launch
            val page = laid.getOrNull(index[para]) ?: return@launch
            val w = page.page.width
            drawing = Drawing(listOf(page), listOf(para), edit = key, strokes = strokes.map { s ->
                InkStroke(s.points.map { Offset((place.x!! + it.x * img.widthPt) / w, (place.y!! + it.y * ky * img.widthPt) / w) }, s.color, s.widthMm, s.highlighter) })
        }
    }
    /** Design › APA 7 paper ([ApaPaper]): styles, page number, margins, and what the page asked for; one step to undo. */
    var apaPage by remember { mutableStateOf(false) }
    fun applyApa(clean: Boolean, title: ApaTitle?, abstract: ApaAbstract? = null, runningHead: String? = null) = change {
        val entries = documentStyles.paragraphStyles()
        val parts = runCatching { ApaPaper.file(OfficeFormats.unzip(original ?: OfficeFormats.write(OfficeDoc(emptyList()), kind)), kind, documentStyles, runningHead) }
            .onFailure { WriterLog.e("apa", it) }.getOrNull() ?: run { message = context.getString(R.string.words_document_not_set_up); return@change }
        original = OfficeFormats.zip(parts, if (kind == OfficeKind.Odt) "mimetype" else null)
        documentStyles = DocStyles.of(kind, original)
        var cur = current()
        if (clean) cur = ApaPaper.clean(cur, kind)
        cur = ApaPaper.runInAll(cur) // levels 4 and 5 run into their paragraphs
        if (title != null || abstract != null) {
            // The title page; the abstract on a page of its own; the text on the next page, under the title again (APA: bold, centred).
            val text = cur.paras.takeUnless { it.size == 1 && it[0].text.isEmpty() && it[0].table == null } ?: listOf(DocPara(emptyList()))
            val again = title?.let { DocPara(listOf(DocRun(it.title.trim())), align = DocAlign.Center,
                keep = OfficeFormats.withPageBreak(OfficeFormats.withSpacing(DocKeep(style = entries[1].id), kind, firstLine = 0f), kind, true)) }
            val body = if (again != null) listOf(again) + text
                else if (abstract != null) listOf(text[0].copy(keep = OfficeFormats.withPageBreak(text[0].keep, kind, true))) + text.drop(1) else text
            val abstractPage = abstract?.let { ApaPaper.abstractPage(it, kind, entries[1].id).let { pg -> if (title == null) listOf(pg[0].copy(keep = OfficeFormats.withPageBreak(pg[0].keep, kind, false))) + pg.drop(1) else pg } }.orEmpty()
            cur = cur.copy(paras = (title?.let { ApaPaper.titlePage(it, kind, entries[1].id) }.orEmpty()) + abstractPage + body)
            WriterLog.i("apa: title page ${title != null}, abstract ${abstract != null}")
        }
        if (Citations.find(cur, kind) != null) cur = Citations.update(cur, kind) // the references on a page of their own
        meta = cur
        dirty = true; styleEdits++; reshow()
        message = context.getString(R.string.words_apa_shown)
    }
    /** Design › APA level 4 / 5: the paragraph at the cursor's heading run into it (the chosen words, from its start, if any). */
    fun apaRunIn(level: Int) = change {
        val t = state.annotatedString.text
        val sel = state.selection
        val span = WriterBridge.paragraphSpans(WriterBridge.blockPlaces(t, state.toRichTextDocument()), sel.min, maxOf(sel.max, sel.min + 1)).firstOrNull()
            ?: WriterBridge.paragraphSpans(WriterBridge.blockPlaces(t, state.toRichTextDocument()), (sel.min - 1).coerceAtLeast(0), sel.min).firstOrNull()
            ?: run { message = context.getString(R.string.words_cursor_in_heading); return@change }
        val (para, from, to) = span
        val length = if (!sel.collapsed && from == 0 && to > 0) to else null
        val done = ApaPaper.runIn(current(), para, level, length) ?: run { message = context.getString(R.string.words_nothing_for_heading); return@change }
        meta = done; dirty = true; reshow()
        message = context.getString(if (level == 5) R.string.words_apa_runin_italic else R.string.words_apa_runin, level)
        WriterLog.i("apa run-in level $level at paragraph $para (${length ?: "auto"})")
    }
    var pageBordering by remember { mutableStateOf(false) }
    /** Design › Page colour and Page borders: into the file as it will be written; shown on the pages at once. */
    fun setDesign(color: Int?, border: Border?, sides: String = sheet.border?.sides ?: "tlbr") = change {
        val parts = runCatching { OfficeFormats.unzip(original ?: OfficeFormats.write(OfficeDoc(emptyList()), kind)).let {
            if (kind == OfficeKind.Docx) PageDesign.docx(it, color, border, sides) else PageDesign.odt(it, color, border, sides) } }
            .onFailure { WriterLog.e("design", it) }.getOrNull() ?: run { message = context.getString(R.string.words_page_not_changed); return@change }
        original = OfficeFormats.zip(parts, if (kind == OfficeKind.Odt) "mimetype" else null)
        documentStyles = DocStyles.of(kind, original)
        dirty = true; styleEdits++
        message = context.getString(R.string.words_shown_on_pages)
    }
    /** Design › Watermark: [text] across every page (null: none), into the file's headers as it will be written. */
    fun setWatermark(text: String?, picture: Watermark.Picture? = null) = change {
        val parts = runCatching { OfficeFormats.unzip(original ?: OfficeFormats.write(OfficeDoc(emptyList()), kind)).let {
            if (kind == OfficeKind.Docx) Watermark.docx(it, text, sheet, picture) else Watermark.odt(it, text, sheet, picture) } }
            .onFailure { WriterLog.e("watermark", it) }.getOrNull() ?: run { message = context.getString(R.string.words_watermark_not_set); return@change }
        original = OfficeFormats.zip(parts, if (kind == OfficeKind.Odt) "mimetype" else null)
        documentStyles = DocStyles.of(kind, original)
        dirty = true; styleEdits++
        message = if (text == null && picture == null) context.getString(R.string.words_watermark_removed) else context.getString(R.string.words_shown_on_pages)
        WriterLog.i("watermark: ${text ?: picture?.let { "picture ${it.bytes.size / 1024} KB" } ?: "none"}")
    }
    /** Design › Watermark › Picture watermark: a picture of the phone's behind every page, washed out (Word's way). */
    val pickWatermark = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
        if (picked != null) scope.launch {
            val img = withContext(Dispatchers.IO) { runCatching { preparePicture(context, picked) }.getOrNull() }
            if (img?.bytes == null) { message = context.getString(R.string.words_picture_not_used); return@launch }
            setWatermark(null, Watermark.Picture(img.bytes, img.extension, img.heightPt / img.widthPt))
        }
    }
    var watermarking by remember { mutableStateOf(false) } // the custom watermark's page
    /** Layout: the page's size, orientation and margins, for every section, into the file as it will be written; shown at once. */
    fun setPage(page: DocPage) = change {
        val parts = runCatching { OfficeFormats.unzip(original ?: OfficeFormats.write(OfficeDoc(emptyList()), kind)).let {
            if (kind == OfficeKind.Docx) PageSetup.docx(it, page) else PageSetup.odt(it, page, documentStyles.page) } }
            .onFailure { WriterLog.e("page", it) }.getOrNull() ?: run { message = context.getString(R.string.words_page_not_set); return@change }
        // The sections a section break ends (their sectPr rides on their last paragraph) take it too.
        if (kind == OfficeKind.Docx) current().let { cur -> if (cur.paras.any { p -> p.keep?.parts?.any { it.first == "sectPr" } == true })
            meta = cur.copy(paras = cur.paras.map { p -> p.keep?.takeIf { k -> k.parts.any { it.first == "sectPr" } }?.let { k ->
                p.copy(keep = k.copy(parts = k.parts.map { if (it.first == "sectPr") it.first to PageSetup.docxSection(it.second, page) else it })) } ?: p }) }
        original = OfficeFormats.zip(parts, if (kind == OfficeKind.Odt) "mimetype" else null)
        documentStyles = DocStyles.of(kind, original)
        dirty = true; styleEdits++
    }
    /** A Word document with section breaks: some of its sections end at a paragraph. */
    fun sectioned() = kind == OfficeKind.Docx && meta.paras.any { p -> p.keep?.parts?.any { it.first == "sectPr" } == true }
    /** Layout › Columns: the whole document's — or, once it has section breaks, the cursor's section's (Word's "This section"). */
    fun setColumns(n: Int) = change {
        val sel = state.selection.min
        // An .odt with the cursor in a section (LibreOffice's): that section's columns.
        if (kind == OfficeKind.Odt) WriterBridge.paragraphsAt(state.annotatedString.text, state.toRichTextDocument(), sel, sel).singleOrNull()
            ?.let { at -> OdtSections.withColumns(current(), at, n) }?.let { meta = it; dirty = true; styleEdits++; WriterLog.i("odt section: $n columns"); return@change }
        if (!sectioned()) { setPage(sheet.copy(columns = n)); return@change }
        val cur = current()
        val here = WriterBridge.paragraphsAt(state.annotatedString.text, state.toRichTextDocument(), sel, sel).singleOrNull() ?: 0
        val end = (here until cur.paras.size).firstOrNull { j -> cur.paras[j].keep?.parts?.any { it.first == "sectPr" } == true }
        if (end != null) meta = cur.copy(paras = cur.paras.mapIndexed { j, p -> if (j != end) p else p.copy(keep = p.keep!!.copy(parts = p.keep.parts.map {
            if (it.first == "sectPr") it.first to SectionBreaks.withColumns(it.second, n, sheet.columnGap) else it })) })
        else {
            val base = original ?: OfficeFormats.write(OfficeDoc(emptyList()), kind)
            val parts = OfficeFormats.unzip(base)
            val body = SectionBreaks.bodySect(String(parts.getValue(documentPath(parts)), Charsets.UTF_8)) ?: "<w:sectPr/>"
            original = OfficeFormats.zip(SectionBreaks.withBodySect(parts, SectionBreaks.withColumns(body, n, sheet.columnGap)), null)
            documentStyles = DocStyles.of(kind, original!!)
        }
        dirty = true; styleEdits++
        WriterLog.i("columns $n in the section ending at ${end ?: "the document's end"}")
    }
    /** Layout › Hyphenation: automatic or none, into the file as it will be written; the pages break words by it. */
    fun setHyphenation(on: Boolean) = change {
        val parts = runCatching { PageDesign.hyphenation(OfficeFormats.unzip(original ?: OfficeFormats.write(OfficeDoc(emptyList()), kind)), kind, on) }
            .onFailure { WriterLog.e("hyphenation", it) }.getOrNull() ?: run { message = context.getString(R.string.words_hyphenation_not_set); return@change }
        original = OfficeFormats.zip(parts, if (kind == OfficeKind.Odt) "mimetype" else null)
        documentStyles = DocStyles.of(kind, original)
        dirty = true; styleEdits++
        if (on) message = context.getString(R.string.words_hyphenation_on)
    }
    /** The paragraphs at the cursor (or chosen) made bullets or numbers in place (see [WriterBridge.listKind]). */
    fun switchList(ordered: Boolean) = change {
        val sel = state.selection
        val places = WriterBridge.blockPlaces(state.annotatedString.text, state.toRichTextDocument())
        // the cursor can stand after a line's own text (the library's space after each line): then it is that line's
        val paras = places.filter { it.to >= sel.min && it.from <= sel.max }.ifEmpty { listOfNotNull(places.lastOrNull { it.from <= sel.min }) }.map { it.para }.toSet()
        meta = WriterBridge.listKind(current(), paras, ordered)
        WriterLog.i("list made ${if (ordered) "numbered" else "bulleted"}: paragraphs $paras")
        dirty = true
        state.setRichTextDocument(WriterBridge.toEditor(meta, pictures, documentStyles), sel)
    }
    var hyphenOptions by remember { mutableStateOf(false) }
    /** Layout › Hyphenation options (Word's zone, consecutive limit, capitals), into the file as it will be written. */
    fun setHyphenOptions(o: HyphenOptions) = change {
        val parts = runCatching { HyphenOptions.write(OfficeFormats.unzip(original ?: OfficeFormats.write(OfficeDoc(emptyList()), kind)), kind, o) }
            .onFailure { WriterLog.e("hyphenation options", it) }.getOrNull() ?: run { message = context.getString(R.string.words_hyphenation_options_not_set); return@change }
        original = OfficeFormats.zip(parts, if (kind == OfficeKind.Odt) "mimetype" else null)
        dirty = true; styleEdits++
        WriterLog.i("hyphenation options: $o")
    }
    /** Layout › Line numbers ([restart]: Word's, or null for none), into the file as it will be written; the pages show them. */
    fun setLineNumbers(restart: String?) = change {
        val parts = runCatching { PageSetup.lineNumbers(OfficeFormats.unzip(original ?: OfficeFormats.write(OfficeDoc(emptyList()), kind)), kind, restart) }
            .onFailure { WriterLog.e("line numbers", it) }.getOrNull() ?: run { message = context.getString(R.string.words_line_numbers_not_set); return@change }
        original = OfficeFormats.zip(parts, if (kind == OfficeKind.Odt) "mimetype" else null)
        documentStyles = DocStyles.of(kind, original)
        dirty = true; styleEdits++
        if (restart != null && designGallery == null) message = context.getString(R.string.words_line_numbers_on)
    }
    var datePage by remember { mutableStateOf(false) }
    /** Symbol or date typed at the cursor, over what is chosen (as typing; the editor's own undo). */
    fun typeIn(s: String) { if (!state.selection.collapsed) state.removeSelectedText(); state.addTextAfterSelection(s) }
    /** The document's header, footer and page numbers set (Word), into the file as it will be written; shown on the pages. */
    fun setHeaderFooter(hf: HeaderFooter) = change {
        val parts = runCatching { OfficeFormats.unzip(original ?: OfficeFormats.write(OfficeDoc(emptyList()), kind)).let {
            if (kind == OfficeKind.Docx) HeaderFooterEdit.docx(it, hf, documentStyles.page) else HeaderFooterEdit.odt(it, hf, documentStyles.page) } }
            .onFailure { WriterLog.e("header", it) }.getOrNull() ?: run { message = context.getString(R.string.words_header_not_set); return@change }
        original = OfficeFormats.zip(parts, if (kind == OfficeKind.Odt) "mimetype" else null)
        documentStyles = DocStyles.of(kind, original)
        dirty = true; styleEdits++
        if (designGallery == null) message = context.getString(R.string.words_shown_on_pages)
    }
    /** A style's own definition changed (every paragraph with it changes), in the file's styles; shown at once. */
    fun editStyle(entry: StyleEntry, edit: StyleChange) = change {
        val key = documentStyles.styleKey(entry) ?: run { message = context.getString(R.string.words_style_cannot_change); return@change }
        val cur = current()
        val parts = OfficeFormats.unzip(original ?: OfficeFormats.write(OfficeDoc(emptyList()), kind))
        val path = stylesPath(parts, kind)
        val xml = parts[path]?.let { String(it, Charsets.UTF_8) } ?: run { message = context.getString(R.string.words_no_styles); return@change }
        parts[path] = runCatching { if (kind == OfficeKind.Docx) StyleEdit.docx(xml, key, entry.heading, documentStyles.normalId, edit) else StyleEdit.odt(xml, key, entry.heading, edit) }
            .onFailure { WriterLog.e("style", it) }.getOrNull()?.toByteArray() ?: run { message = context.getString(R.string.words_style_not_changed); return@change }
        original = OfficeFormats.zip(parts, if (kind == OfficeKind.Odt) "mimetype" else null)
        documentStyles = DocStyles.of(kind, original)
        meta = cur
        dirty = true; styleEdits++
        state.selection.let { sel -> state.setRichTextDocument(WriterBridge.toEditor(cur, pictures, documentStyles), sel) }
    }
    /** Design › Style sets: Normal, Title and Headings 1–3 changed together, one step to undo. */
    /**
     * Design › Colours / Fonts: a Word file's theme colours or fonts; in an .odt (no theme) Fonts set the headings' and
     * the text's styles. The pages and editor shown again with them.
     */
    fun applyTheme(colors: ThemeColors?, fonts: ThemeFonts?) = change {
        val cur = current()
        var parts = OfficeFormats.unzip(original ?: OfficeFormats.write(OfficeDoc(emptyList()), kind))
        val done = runCatching {
            if (kind == OfficeKind.Docx) {
                colors?.let { parts = DocTheme.withColors(parts, it) }
                fonts?.let { f ->
                    parts = DocTheme.withFonts(parts, f)
                    // Styles that name a font of their own keep it in Word: the headings and the text take the theme's here.
                    val path = stylesPath(parts, kind)
                    parts[path] = StyleSet.apply(StyleSet.fontsOnly(f), String(parts.getValue(path), Charsets.UTF_8), kind, documentStyles).toByteArray()
                }
            } else fonts?.let { f -> val path = stylesPath(parts, kind); parts[path] = StyleSet.apply(StyleSet.fontsOnly(f), String(parts.getValue(path), Charsets.UTF_8), kind, documentStyles).toByteArray() }
        }.onFailure { WriterLog.e("theme", it) }.isSuccess
        if (!done) { message = context.getString(R.string.words_theme_failed); return@change }
        original = OfficeFormats.zip(parts, if (kind == OfficeKind.Odt) "mimetype" else null)
        documentStyles = DocStyles.of(kind, original)
        meta = cur
        dirty = true; styleEdits++
        state.selection.let { sel -> state.setRichTextDocument(WriterBridge.toEditor(cur, pictures, documentStyles), sel) }
        message = (colors?.let { context.getString(R.string.words_colours_set, themeColoursName(it)?.let(context::getString) ?: it.label) } ?: "") + (fonts?.label?.let { context.getString(R.string.words_fonts_set, it) } ?: "")
        WriterLog.i("theme: ${colors?.name} ${fonts?.name}")
    }
    /** References › Style (and Design › Standards): every citation and the list written again in [c]. */
    fun setCiteStyle(c: CiteStyle) = change {
        meta = Fields.update(Citations.update(current().copy(citeStyle = c), kind), ::pageOfPara); dirty = true; styleEdits++; reshow()
        message = context.getString(R.string.words_citations_in_style, c.label); WriterLog.i("citation style ${c.name}")
    }
    fun putTextBox(lines: List<String>, art: Int?, effects: TextEffects = TextEffects(), warp: TextWarp? = null): String {
        val img = newTextBoxImage(kind, lines, art, effects, warp, PictureWrap.Square)
        pictures[img.key] = img
        val at = placePicture(img)
        state.selection = androidx.compose.ui.text.TextRange(at, at + 1)
        tab = WriterTab.Picture
        return img.key
    }
    fun applyStyleSet(set: StyleSet) = change {
        val cur = current()
        val parts = OfficeFormats.unzip(original ?: OfficeFormats.write(OfficeDoc(emptyList()), kind))
        val path = stylesPath(parts, kind)
        val xml = parts[path]?.let { String(it, Charsets.UTF_8) } ?: run { message = context.getString(R.string.words_no_styles); return@change }
        parts[path] = runCatching { set.applyTo(xml, kind, documentStyles) }.onFailure { WriterLog.e("style set", it) }.getOrNull()?.toByteArray()
            ?: run { message = context.getString(R.string.words_style_set_failed); return@change }
        original = OfficeFormats.zip(parts, if (kind == OfficeKind.Odt) "mimetype" else null)
        documentStyles = DocStyles.of(kind, original)
        meta = cur
        dirty = true; styleEdits++
        state.selection.let { sel -> state.setRichTextDocument(WriterBridge.toEditor(cur, pictures, documentStyles), sel) }
        message = context.getString(R.string.words_style_set_done, context.getString(set.label))
    }
    /** A gallery's card tapped: the document as it was, then this choice on it (a live preview; OK keeps it). */
    var dropAt by remember { mutableStateOf<Int?>(null) } // Insert › Drop cap's paragraph while its strip is open
    var dropLines by remember { mutableIntStateOf(3) } // …and how many lines it drops (the strip's setting)
    fun galleryPick(c: Any) {
        val last = galleryChosen
        if (last != null) galleryStart?.let(::show)
        WriterLog.i("gallery pick: $c")
        when (c) {
            is StyleSet -> { galleryChosen = c; applyStyleSet(c) }
            is ThemeColors -> { galleryChosen = c; applyTheme(c, null) }
            is ThemeFonts -> { galleryChosen = c; applyTheme(null, c) }
            is ArtStyle, is ArtBend -> {
                val style = c as? ArtStyle ?: (last as? Pair<*, *>)?.first as? ArtStyle ?: ART_STYLES[0]
                val bend = c as? ArtBend ?: (last as? Pair<*, *>)?.second as? ArtBend ?: ArtBend(null)
                galleryChosen = style to bend
                artKey = putTextBox(listOf("Your text here"), 0xFF000000.toInt() or style.color,
                    TextEffects(if (style.outline) artOutline(style.color) else null, style.shadow, if (style.glow) artGlow(style.color) else null), bend.warp)
            }
            is NumberSpot -> { galleryChosen = c; setHeaderFooter(c.on(HeaderFooter.of(documentStyles.section, documentStyles.evenAndOdd)))
                goPage = (if (c.place == HeaderFooter.NumberPlace.Footer) -1 else 0) to System.nanoTime() } // where it went, in view
            is HeaderLayout -> { galleryChosen = c; val hf = c.make(HeaderFooter.of(documentStyles.section, documentStyles.evenAndOdd), name.substringBeforeLast('.'))
                setHeaderFooter(hf); goPage = 0 to System.nanoTime() }
            is ListScheme -> { galleryChosen = c; setNumbering(c.levels) }
            is LineNumbering -> { galleryChosen = c; setLineNumbers(c.mode) }
            is FontChoice -> { galleryChosen = c; setFont(c.name) }
            is DropKind -> { galleryChosen = c; dropAt?.let { i -> val now = DropCap.of(current().paras.getOrNull(i)?.keep)
                setDropCap(i, when (c) { DropKind.None -> null; DropKind.Margin -> (now ?: DropCap()).copy(margin = true, lines = dropLines); else -> (now ?: DropCap()).copy(margin = false, lines = dropLines) }); styleEdits++ } } // the pages laid out again with it
        }
    }
    val rulerIndents = rulerAt?.second?.let { RulerIndents(it.left, it.firstLine, it.right) }
    fun setIndents(now: RulerIndents) {
        val (at, look) = rulerAt ?: return
        setSpacing(at, look, look.copy(left = now.left, firstLine = now.firstLine, right = now.right))
    }
    fun commitCell() {
        val edit = cellEdit ?: return
        val img = pictures[edit.ref.key] ?: run { cellEdit = null; return }
        val table = img.table ?: run { cellEdit = null; return }
        val row = table.rows.getOrNull(edit.ref.row) ?: run { cellEdit = null; return }
        val cell = row.cells.getOrNull(edit.ref.cell) ?: run { cellEdit = null; return }
        val typed = WriterBridge.withCellText(edit.cell, edit.value.text)
        if (typed != cell) {
            val rows = table.rows.toMutableList()
            val cells = row.cells.toMutableList()
            cells[edit.ref.cell] = typed
            rows[edit.ref.row] = row.copy(cells = cells)
            changeTable(edit.ref.key, img, table.copy(rows = rows))
        }
        cellEdit = null
    }
    fun pictureBytes(img: DocImage) = img.bytes ?: img.part?.let { packageParts[it] }
    /** Save picture as…: into the phone's Pictures/Tetra, where Photos and Files find it. */
    fun savePicture(img: DocImage) {
        val bytes = pictureBytes(img) ?: return
        scope.launch { message = savePictureToPhone(context, bytes, img.part?.substringAfterLast('.')?.lowercase(Locale.ROOT) ?: img.extension, name) }
    }
    /** Leaving the document: this session's history goes with it (a recovery draft stays only when Writer did not close). */
    fun exit() {
        // The name it was opened under too (Rename and Save as change it).
        setOf(uri ?: "new", open ?: "new").forEach { k -> runCatching { sessionDir(context, k).deleteRecursively() } }
        WriterLog.i("session history cleared"); back()
    }
    fun leave() { commitCell(); if (dirty) leaving = true else exit() }
    BackHandler { if (reading) reading = false else leave() }

    val ink = Color.Black
    val w = islandContentColor()
    // On a phone the top bar goes away while scrolling down the document and comes back when scrolling up (asked
    // 2026-09-29, for room); a tablet has room and keeps it.
    var barShown by remember { mutableStateOf(true) }
    var officeSettings by remember { mutableStateOf(false) } // File › Settings
    var citeStyling by remember { mutableStateOf(false) } // References › Style's strip
    // the top bar's and the ribbon's heights: the ribbon at the top (a mouse in use) stands under the bar, the page under it
    var barPx by remember { mutableIntStateOf(0) }; var islandPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val phone = screen < 700.dp
    val barScroll = remember(phone) { object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
        override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
            if (phone && available.y < -6f) barShown = false else if (available.y > 6f) barShown = true
            return androidx.compose.ui.geometry.Offset.Zero
        }
    } }
    LaunchedEffect(viewing, finding) { barShown = true }
    Box(Modifier.fillMaxSize().background(Color(0xFF1B1D1E)).statusBarsPadding().imePadding()
        .then(Modifier.nestedScroll(barScroll))
        // a keyboard's shortcuts (a tablet's or a desktop's, asked 16 Oct), as Word's
        .onPreviewKeyEvent { k ->
            if (k.type != KeyEventType.KeyDown || !k.isCtrlPressed || !loaded) return@onPreviewKeyEvent false
            when {
                k.key == Key.F -> { finding = true; true }
                viewing || reading -> false
                k.key == Key.S -> { if (!saving) save(); true }
                k.key == Key.Y || (k.key == Key.Z && k.isShiftPressed) -> { redo(); true }
                k.key == Key.Z -> { undo(); true }
                cellEdit != null -> false // a table cell's own text field
                k.key == Key.B -> { toggleBold(state.currentSpanStyle); dirty = true; true }
                k.key == Key.I -> { toggleItalic(state.currentSpanStyle); dirty = true; true }
                k.key == Key.U -> { state.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline)); dirty = true; true }
                else -> false
            }
        }) {
        Column(Modifier.fillMaxSize()) {
            // Find and Replace (Word's magnifier): the matches are found in the text as the editor holds it, so a
            // match's place is where the editor selects it.
            if (finding) {
                val text = state.annotatedString.text
                val matches = remember(text, query) { if (query.isEmpty()) emptyList() else Regex(Regex.escape(query), RegexOption.IGNORE_CASE).findAll(text).map { it.range }.toList() }
                val at = matches.indexOfFirst { it.first >= state.selection.min }.let { if (it < 0) 0 else it }
                fun go(i: Int) { matches.getOrNull(Math.floorMod(i, matches.size.coerceAtLeast(1)))?.let { state.selection = androidx.compose.ui.text.TextRange(it.first, it.last + 1) } }
                FindReplaceBar(w, query, { query = it }, if (query.isEmpty()) "" else if (matches.isEmpty()) "0" else "${at + 1}/${matches.size}", matches.isNotEmpty(),
                    previous = { go(at - 1) }, next = { go(if (state.selection.collapsed) at else at + 1) }, replacing = replacing, setReplacing = { replacing = it },
                    replacement = replacement, setReplacement = { replacement = it },
                    replace = { matches.getOrNull(at)?.let { m -> replaceKeepingLook(m.first, m.last + 1, replacement) } },
                    replaceAll = { state.history.group { matches.asReversed().forEach { m -> replaceKeepingLook(m.first, m.last + 1, replacement) } }; message = context.getString(R.string.words_replaced_count, matches.size) },
                    close = { finding = false })
            } else
            // Word's top bar: back, the name (a dot while unsaved), find, undo and redo.
            androidx.compose.animation.AnimatedVisibility(barShown, Modifier.onSizeChanged { barPx = it.height }) { Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Tool(R.drawable.ic_chevron_left, stringResource(R.string.common_back), w) { leave() }
                // The words, counted as one types (Word's status bar, Phase 2): of the selection too when there is one, worked
                // out a moment after typing stops (a thesis is long); a tap shows all the counts (File › About this file).
                val text = state.annotatedString.text
                val sel = state.selection
                val tables = keys.map { pictures[it]?.table } // a cell edited changes no text
                val all by produceState(TextCounts(0, 0, 0), text, tables, loaded) { if (loaded) { delay(150); value = withContext(Dispatchers.Default) { counted(text) } } }
                val part = remember(text, sel, tables) { if (sel.collapsed) null else counted(text, sel.min, sel.max) }
                Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                    Text((if (dirty) "• " else "") + name, color = w, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // A phone: under the name. A tablet has room beside it (words and characters).
                    if (phone && loaded) Text(TextCounts.label(all.words, part?.words), color = w.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { details = true })
                }
                if (!phone && loaded) {
                    Text((if (part == null) "${wordsCount(all.words)} · ${charactersCount(all.withSpaces)}"
                        else stringResource(R.string.words_count_of, part.words, wordsCount(all.words)) + " · " + stringResource(R.string.words_count_of, part.withSpaces, charactersCount(all.withSpaces)))
                        + (if (file != null && !viewing) " · " + saveState(saving, dirty) + (if (autoSave && dirty && !saving) stringResource(R.string.words_autosave_on_suffix) else "") else "")
                        + (file?.extension?.let { " · .$it" } ?: ""),
                        color = w.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        modifier = Modifier.clickable { details = true }.padding(horizontal = 8.dp))
                }
                // the top bar's tools as the ribbon's: 48 dp in Normal size
                RibbonTools {
                // a tablet or bigger: the open document's heart, the Office home's Favourites (the user, 18 Oct)
                FavouriteHeart(file, w)
                if (reading) {
                    Tool(R.drawable.ic_remove, stringResource(R.string.words_smaller_text), w, enabled = readingZoom > 100) { readingZoom -= 20; context.getSharedPreferences("writer", Context.MODE_PRIVATE).edit().putInt("readingZoom", readingZoom).apply() }
                    Tool(R.drawable.ic_add, stringResource(R.string.words_bigger_text), w, enabled = readingZoom < 260) { readingZoom += 20; context.getSharedPreferences("writer", Context.MODE_PRIVATE).edit().putInt("readingZoom", readingZoom).apply() }
                    RibbonButton(stringResource(R.string.words_close_reading), w, R.drawable.ic_close) { reading = false }
                } else if (viewing) {
                    Tool(R.drawable.ic_menu_book, stringResource(R.string.words_reading_mode), w, enabled = loaded) { viewing = false; reading = true }
                    Tool(R.drawable.ic_view_list, stringResource(R.string.words_navigation), w, enabled = loaded, on = navOpen) { navOpen = !navOpen }
                    Tool(R.drawable.ic_view_column, stringResource(R.string.words_two_pages), w, enabled = loaded, on = pagesAcross == 2) { pagesAcross = if (pagesAcross == 2) 1 else 2 }
                    Tool(R.drawable.ic_share, stringResource(R.string.common_share), w, enabled = file?.isFile == true) { file?.let { shareDriveFile(context, it) } }
                    if (dirty) Tool(R.drawable.ic_save, stringResource(R.string.common_save), w, enabled = loaded) { save() }
                    // a phone: the pencil alone, as Tables' and Slides' (a longer word, "Επεξεργασία", left the file's name no room)
                    if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600) Tool(R.drawable.ic_edit, stringResource(R.string.common_edit), w, enabled = loaded) { viewing = false }
                    else RibbonButton(stringResource(R.string.common_edit), w, R.drawable.ic_edit, enabled = loaded) { viewing = false }
                } else {
                Tool(R.drawable.ic_search, stringResource(R.string.words_find_replace), w, enabled = loaded) { finding = true }
                Tool(R.drawable.ic_undo, stringResource(R.string.common_undo), w, enabled = state.history.canUndo || undone.isNotEmpty()) { undo() }
                Tool(R.drawable.ic_redo, stringResource(R.string.common_redo), w, enabled = state.history.canRedo || redone.isNotEmpty()) { redo() }
                Tool(R.drawable.ic_save, stringResource(R.string.common_save), w, enabled = loaded && (dirty || file == null || cellEdit != null)) { commitCell(); save() }
                // Done at the far right, back to the pages, as Tables' and Slides' (17 Oct; it was an eye, "Pages", before Find)
                Tool(R.drawable.ic_check, stringResource(R.string.common_done), w, enabled = loaded) { commitCell(); viewing = true }
                }
                }
            }
            if (loaded && meta.unsupported.isNotEmpty()) {
                val copyOnly = meta.blocking.isNotEmpty()
                Surface(color = if (copyOnly) Category.Office.color.copy(alpha = 0.22f) else Color(0xFF2A2E30), contentColor = Color.White,
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable { details = true }) {
                    Text(if (copyOnly && meta.blocking == setOf(LegacyDocs.OLDER)) stringResource(R.string.words_older_file_banner, name.substringAfterLast('.').uppercase(), name.substringBeforeLast('.'))
                        else if (copyOnly) stringResource(R.string.words_copy_only_banner, OfficeDoc.notesText(LocalContext.current, meta.blocking.take(3)) + if (meta.blocking.size > 3) "…" else "")
                        else stringResource(R.string.words_simplifies_banner, OfficeDoc.notesText(LocalContext.current, meta.simplified)),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
            } }
            if (viewing && failed == null) Box(Modifier.weight(1f).fillMaxWidth()) {
                pages?.let { PagesView(it, with(LocalDensity.current) { (BASE_SP / meta.basePt).sp.toDp() }, Modifier.fillMaxSize(), goPage,
                    covered = if (galleryViewing != null) (if (LocalConfiguration.current.screenWidthDp < 700) 110.dp else 210.dp) else 0.dp,
                    across = pagesAcross, fitWidth = fitWidth, film = ::filmOf) }
                    ?: Text(stringResource(R.string.words_laying_out), color = w.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.Center))
            } else
            // The page: white, as the document will print — its own colours must show as they are.
            Column(Modifier.weight(1f).fillMaxWidth().padding(top = if (OfficeUi.top && !viewing && !reading) with(density) { islandPx.toDp() } else 0.dp)) {
            val firstBefore = meta.paras.firstOrNull()?.takeIf { it.table == null }?.let { documentStyles.look(it).before } ?: 0f
            val leadingSpace = firstBefore * perPt
            val margins = if (onPage) androidx.compose.foundation.layout.PaddingValues(start = sheet.left * perPt, end = sheet.right * perPt, top = sheet.top * perPt + leadingSpace, bottom = 180.dp)
                else androidx.compose.foundation.layout.PaddingValues(start = 22.dp, end = 22.dp, top = 24.dp + leadingSpace, bottom = 180.dp)
            // With the comments' pane, page and pane are centred together.
            val shift = if (paneOn && onPage) (COMMENT_PANE + 12.dp) / 2 else 0.dp
            if (onPage && loaded && !printPhone) BoxWithConstraints(Modifier.fillMaxWidth()) {
                val x = (maxWidth - sheet.width * perPt) / 2 - shift
                IndentRuler(sheet, perPt, x, rulerIndents) { setIndents(it) }
            }
            val downScroll = rememberScrollState(); val sideScroll = rememberScrollState()
            var handleAt by remember { mutableStateOf<Offset?>(null) } // a handle being dragged: where, on the (unscaled) page
            var edgeLogs by remember { mutableIntStateOf(0) }
            var pageView by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) } // the page's view, in the window: a held handle's edges
            val densityHere = LocalDensity.current
            val ribbonOver = if (OfficeUi.top) 0 else islandPx // the ribbon over the page's bottom, in pixels
            /** The cursor on the page (unscaled), from the last styled layout, with room around it. */
            fun cursorOnPage(): androidx.compose.ui.geometry.Rect? = handleAt?.let { p -> androidx.compose.ui.geometry.Rect(p, androidx.compose.ui.geometry.Size(1f, 1f)) } ?: editorLayout?.let { l ->
                val at = state.selection.max.coerceIn(0, l.layoutInput.text.length)
                with(densityHere) { l.getCursorRect(at).translate(margins.calculateLeftPadding(LayoutDirection.Ltr).toPx(), margins.calculateTopPadding().toPx())
                    // below it, room for the handles and for the ribbon, which lies over the page's bottom (when it is down there)
                    .let { c -> androidx.compose.ui.geometry.Rect(c.left - 32.dp.toPx(), c.top - 16.dp.toPx(), c.right + 32.dp.toPx(), c.bottom + (ribbonOver + 48.dp.toPx()) / pageScale) } }
            }
            // The zoomed page keeps the cursor in sight at every styled layout, at once (the editor's own request came once a
            // keystroke, from the unstyled layout, so the page fell behind the typing).
            // Keyed on the layout alone: a handle being dragged moves the selection, and the page then jumped to its other end.
            // and when the view changes height: the keyboard coming up hid a cursor near the bottom
            LaunchedEffect(editorLayout, pageScale, downScroll.viewportSize) {
                // a handle held: the page stays under the finger (the user, 13 Nov: "selecting text… the view is moving around
                // a lot" — it followed the selection's end at every layout and ran from the finger); the edges scroll it instead
                if (!printPhone || handleAt != null) return@LaunchedEffect
                val c = cursorOnPage() ?: return@LaunchedEffect
                val padX = with(densityHere) { 10.dp.toPx() }; val padY = with(densityHere) { 8.dp.toPx() }
                fun follow(sc: androidx.compose.foundation.ScrollState, from: Float, to: Float): Float {
                    val view = sc.viewportSize.toFloat().takeIf { it > 0f } ?: return 0f
                    return when { from < sc.value -> from - sc.value; to > sc.value + view -> minOf(to - sc.value - view, from - sc.value); else -> 0f }
                }
                val dx = follow(sideScroll, padX + c.left * pageScale, padX + c.right * pageScale)
                val dy = follow(downScroll, padY + c.top * pageScale, padY + c.bottom * pageScale)
                if (dx != 0f) sideScroll.scrollBy(dx)
                if (dy != 0f) downScroll.scrollBy(dy)
                if (dx != 0f || dy != 0f) WriterLog.i("followed the cursor: at ${state.selection.max}, on the page (${c.left.roundToInt()}, ${c.top.roundToInt()}), zoom ${"%.2f".format(pageScale)}, " +
                    "scrolled by (${dx.roundToInt()}, ${dy.roundToInt()}) to (${sideScroll.value}, ${downScroll.value}) of (${sideScroll.maxValue}, ${downScroll.maxValue}), view ${sideScroll.viewportSize}×${downScroll.viewportSize}")
            }
            // Navigation: the heading brought near the top (the cursor alone only scrolled it just into view, under the ribbon).
            val densityNow = LocalDensity.current
            LaunchedEffect(goText) {
                val (at, _) = goText ?: return@LaunchedEffect
                val l = editorLayout ?: return@LaunchedEffect
                val top = l.getLineTop(l.getLineForOffset(at.coerceIn(0, l.layoutInput.text.length))) + with(densityNow) { (margins.calculateTopPadding() + 8.dp - 24.dp).toPx() }
                downScroll.animateScrollTo((top * (if (printPhone) pageScale else 1f)).toInt().coerceAtLeast(0))
            }
            Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { pageView = it.boundsInWindow() }
                .then(if (printPhone) Modifier.pinchZoom({ pageZoom }, 1f, 5f, sideScroll, downScroll) { pageZoom = it } else Modifier)
                // A loupe over a handle being dragged, a little above the finger (asked 14 Oct): the page's letters at a size to aim by.
                .then(if (printPhone) Modifier.magnifier(
                    sourceCenter = { handleAt?.let { p -> Offset(10.dp.toPx() + p.x * pageScale - sideScroll.value, 8.dp.toPx() + p.y * pageScale - downScroll.value) } ?: Offset.Unspecified },
                    magnifierCenter = { handleAt?.let { p -> Offset(10.dp.toPx() + p.x * pageScale - sideScroll.value, 8.dp.toPx() + p.y * pageScale - downScroll.value - 44.dp.toPx()) } ?: Offset.Unspecified },
                    zoom = (1.3f / pageScale).coerceIn(1.25f, 4f), size = androidx.compose.ui.unit.DpSize(150.dp, 48.dp), cornerRadius = 24.dp) else Modifier)
                .verticalScroll(downScroll)
                .then(if (printPhone) Modifier.horizontalScroll(sideScroll) else Modifier)
                .padding(horizontal = 10.dp, vertical = 8.dp)) {
                // The comments' cards, and (as Word draws them) a line from each one's words to its card.
                var cardTops by remember { mutableStateOf<List<Int>>(emptyList()) }
                val ordered = if (paneOn && onPage && loaded) commentsShown.groupBy { it.first }.mapValues { (_, spans) -> spans.minOf { it.second.first } to spans.maxOf { it.second.last + 1 } }
                    .entries.sortedBy { it.value.first } else emptyList()
                if (paneOn && onPage && loaded) {
                    val density = LocalDensity.current
                    CommentPane(ordered.map { (c, at) ->
                        c to (sheet.top * perPt + with(density) { (editorLayout?.takeIf { at.first < it.layoutInput.text.length }?.let { it.getLineTop(it.getLineForOffset(at.first)) } ?: 0f).toDp() })
                    }, hereComment, Modifier.align(Alignment.TopCenter).offset(x = sheet.width * perPt / 2 - shift + 12.dp + COMMENT_PANE / 2).width(COMMENT_PANE),
                        commentActions { c -> ordered.firstOrNull { it.key == c }?.let { state.selection = androidx.compose.ui.text.TextRange(it.value.first) } }) { if (it != cardTops) cardTops = it }
                }
                if (ordered.isNotEmpty() && !printPhone) editorLayout?.let { l ->
                    val here = hereComment
                    androidx.compose.foundation.Canvas(Modifier.matchParentSize().zIndex(1f)) {
                        val sheetW = (sheet.width * perPt).toPx()
                        val sheetX = (size.width - sheetW) / 2 - shift.toPx()
                        val textX = sheetX + (sheet.left * perPt).toPx(); val textY = (margins.calculateTopPadding()).toPx()
                        val paneX = sheetX + sheetW + 12.dp.toPx()
                        ordered.forEachIndexed { k, (c, at) ->
                            val top = cardTops.getOrNull(k) ?: return@forEachIndexed
                            val end = at.second.coerceIn(0, l.layoutInput.text.length)
                            val line = l.getLineForOffset((end - 1).coerceAtLeast(0))
                            val x = textX + l.getHorizontalPosition(end, usePrimaryDirection = true); val y = textY + l.getLineBottom(line)
                            val mine = c == here
                            val color = if (mine) Color(0xFF1A73E8) else Color(0xFF757575)
                            val dash = if (mine) null else androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 5f))
                            // Under the words to the page's edge, then across to the card's first line.
                            val edge = sheetX + sheetW - 4.dp.toPx()
                            drawLine(color, androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Offset(edge, y), 1.dp.toPx(), pathEffect = dash)
                            drawLine(color, androidx.compose.ui.geometry.Offset(edge, y), androidx.compose.ui.geometry.Offset(paneX, top + 22.dp.toPx()), 1.dp.toPx(), pathEffect = dash)
                        }
                    }
                }
                Surface(color = paper, shape = RoundedCornerShape(if (onPage) 0.dp else 6.dp),
                    modifier = (if (printPhone) Modifier.scaledBy(pageScale).followCursor { cursorOnPage()?.let { r -> androidx.compose.ui.geometry.Rect(r.left * pageScale, r.top * pageScale, r.right * pageScale, r.bottom * pageScale) } }.width(sheet.width * perPt).heightIn(min = sheet.height * perPt)
                        else if (onPage) Modifier.align(Alignment.TopCenter).offset(x = -shift).width(sheet.width * perPt).heightIn(min = sheet.height * perPt)
                        else if (reading) Modifier.align(Alignment.TopCenter).width(readingWidth) else Modifier.fillMaxWidth()).heightIn(min = 520.dp)) {
                    when {
                        failed != null -> Text(failed!!, color = ink, modifier = Modifier.padding(24.dp))
                        !loaded -> Text(stringResource(R.string.words_opening), color = ink.copy(alpha = 0.5f), modifier = Modifier.padding(24.dp))
                        else -> Box(Modifier.fillMaxWidth()) {
                            var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
                            var unstyled by remember { mutableStateOf(false) }
                            var hadFonts by remember { mutableStateOf(false) }
                            // A selected picture or table has its blue frame: the selection's shading over its (invisible,
                            // picture-tall) letter only showed as a grey block behind it.
                            val selection = androidx.compose.foundation.text.selection.LocalTextSelectionColors.current
                            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalTextToolbar provides textToolbar,
                                androidx.compose.foundation.text.selection.LocalTextSelectionColors provides
                                // Dark blue handles and shading, as Word's (asked 14 Oct: the theme's pale one hardly showed on the white page).
                                if (selectedPicture != null) androidx.compose.foundation.text.selection.TextSelectionColors(SELECTION_BLUE, Color.Transparent)
                                // The phone's page is drawn scaled and Compose puts its handles where the unscaled page would be
                                // (far off the words): Writer draws its own there (PageHandles).
                                else androidx.compose.foundation.text.selection.TextSelectionColors(if (printPhone) Color.Transparent else SELECTION_BLUE, SELECTION_BLUE.copy(alpha = 0.35f))) {
                            // Line heights untrimmed: Compose's default trims the first and last line's, so a one-line paragraph (an empty
                            // one too) never got its height (asked 2026-10-01: Edit's empty lines unlike the pages').
                            // A keystroke: the library has put back its own look (no fonts, no space between paragraphs) and Writer's
                            // comes only after the next frame — so its last look, carried over the typed letters, goes in now,
                            // before this frame is drawn (the text jumped half a page up and back at each letter, 14 Oct).
                            val heldText = state.annotatedString
                            if (!WriterBridge.showsWriters(state)) lastLook[0]?.let { (look, base) ->
                                WriterBridge.carried(look, base, heldText)?.let { WriterBridge.displayText(state, it) } ?: WriterLog.i("look not carried over: its paragraphs would overlap")
                            }
                            BasicRichTextEditor(state, readOnly = reading, textStyle = TextStyle(color = ink, fontSize = (BASE_SP * shownZoom / 100).sp,
                                lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(androidx.compose.ui.text.style.LineHeightStyle.Alignment.Proportional, androidx.compose.ui.text.style.LineHeightStyle.Trim.None)),
                                cursorBrush = SolidColor(Color(0xFF1A73E8)), onTextLayout = {
                                    // A keystroke can lay the text out once without its styles (the library, between the typed letter and its
                                    // spans): that layout is not the page — kept, its cursor
                                    // shook the zoomed page up, down and sideways (14 Oct).
                                    // Its fonts all gone (only a heading's bold is left) where the last layout had them: a new document
                                    // with no fonts at all is never taken for it.
                                    val fonts = it.layoutInput.text.spanStyles.any { s -> s.item.fontFamily != null }
                                    val plain = !fonts && hadFonts && it.layoutInput.text.isNotEmpty()
                                    if (!plain) hadFonts = fonts
                                    if (plain && !unstyled) {
                                        val held = state.annotatedString
                                        WriterLog.i("unstyled layout skipped: laid out ${it.layoutInput.text.length} letters, ${it.layoutInput.text.spanStyles.size} spans (" +
                                            it.layoutInput.text.spanStyles.take(3).joinToString { s -> "${s.start}-${s.end} w=${s.item.fontWeight?.weight} size=${s.item.fontSize}" } +
                                            "); the editor holds ${held.length} letters, ${held.spanStyles.size} spans, ${held.spanStyles.count { s -> s.item.fontFamily != null }} with a font; same text: ${held.text == it.layoutInput.text.text}; " +
                                            "selection ${state.selection}")
                                    }
                                    unstyled = plain
                                    if (!unstyled) {
                                        // The page's height changing under the typing is what moves the text below it.
                                        editorLayout?.let { was -> if (was.size.height != it.size.height) WriterLog.i("edit layout: ${was.size.height} → ${it.size.height} px tall, ${was.lineCount} → ${it.lineCount} lines, cursor at ${state.selection.max}") }
                                        layout = it; editorLayout = it
                                    }
                                    editorTextWidth = it.size.width; WriterBridge.spaceParagraphs(state, it) },
                                // a picture dragged in from another app: at the cursor, as Insert › Picture
                                modifier = Modifier.fillMaxWidth().heightIn(min = 520.dp).dropPictures(!viewing && !reading && loaded) { insertPicture(it) }.padding(margins))
                            }
                            if (printPhone && selectedPicture == null) layout?.let { l ->
                                val d = LocalDensity.current
                                PageHandles(state, l, Offset(with(d) { margins.calculateLeftPadding(LayoutDirection.Ltr).toPx() }, with(d) { margins.calculateTopPadding().toPx() }), pageScale,
                                    where = { p -> "page view ${pageView.top.roundToInt()}–${pageView.bottom.roundToInt()}, scroll ${downScroll.value} of ${downScroll.maxValue} (view ${downScroll.viewportSize}), the handle reckoned at ${with(d) { (pageView.top + 8.dp.toPx() + p.y * pageScale - downScroll.value).roundToInt() }}" },
                                    edge = { y -> with(d) {
                                        // the finger (in the window) within 28 dp of the page's top, or of the ribbon over its bottom:
                                        // up to 8 dp a frame that way, faster nearer
                                        val zone = 28.dp.toPx(); val top = pageView.top; val bottom = pageView.bottom - ribbonOver
                                        val step = when { y < top + zone -> -(top + zone - y); y > bottom - zone -> y - (bottom - zone); else -> 0f }.coerceIn(-zone, zone) / zone * 8.dp.toPx()
                                        if (step == 0f) 0f else downScroll.dispatchRawDelta(step).also { got -> if (++edgeLogs % 30 == 1) WriterLog.i("handle at the edge: finger at ${y.roundToInt()} in ${pageView.top.roundToInt()}–${pageView.bottom.roundToInt()}, step ${step.roundToInt()}, scrolled ${got.roundToInt()} to ${downScroll.value}") } / pageScale
                                    } }) { handleAt = it }
                            }
                            // The pictures, drawn where their character sits: its left, standing on its line's bottom.
                            layout?.let { l ->
                                val text = l.layoutInput.text.text
                                val density = LocalDensity.current
                                val scale = BASE_SP / meta.basePt
                                // Where each picture goes, worked out first; then drawn (no skipping inside the drawing).
                                val placed = text.indices.filter { text[it] == EDITOR_PICTURE_CHAR || text[it] == OBJECT_MARK }.mapIndexedNotNull { n, i -> // U+FFFC: as Display lays it
                                    keys.getOrNull(n)?.let { pictures[it] }?.let { img -> Triple(i, img, l.getBoundingBox(i).left to l.getLineBaseline(l.getLineForOffset(i))) }
                                }
                                if (shownTabs.any { it.leader != null }) androidx.compose.foundation.Canvas(Modifier.matchParentSize().padding(margins)) {
                                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; textSize = (BASE_SP * shownZoom / 100f).sp.toPx() }
                                    val canvas = drawContext.canvas.nativeCanvas
                                    shownTabs.forEach { tab ->
                                        val leader = tab.leader ?: return@forEach
                                        if (tab.at + 1 >= text.length) return@forEach
                                        val line = l.getLineForOffset(tab.at)
                                        if (l.getLineForOffset(tab.at + 1) != line) return@forEach
                                        val a = l.getHorizontalPosition(tab.at, true)
                                        // The gap's own width: the layout puts the letter after a widened tab (letter spacing) at half
                                        // of it, and the dots stopped half way (asked 2026-10-13).
                                        val z = a + tab.widthPx
                                        val dot = paint.measureText(leader.toString()).coerceAtLeast(1f)

                                        var x = a + dot
                                        while (x + dot < z) { canvas.drawText(leader.toString(), x, l.getLineBaseline(line), paint); x += dot }
                                    }
                                }
                                var drag by remember { mutableStateOf(Offset.Zero) } // how far the selected picture is being dragged
                                var stretch by remember { mutableStateOf<Pair<Float, Float>?>(null) } // its size while a handle is pulled (pixels)
                                var turning by remember { mutableStateOf<Float?>(null) } // its angle while the knob is turned
                                // A page break before a paragraph: the editor has no pages, so a dashed line says where one starts.
                                // A form's fields: a light box behind each (Word shades them), deeper where the cursor is.
                                if (controlsShown.isNotEmpty()) androidx.compose.foundation.Canvas(Modifier.matchParentSize().padding(margins)) {
                                    controlsShown.forEach { (c, _, r) -> if (r.last < text.length) shadeLetters(l, r.first, r.last + 1,
                                        if (c == hereControl?.first) Color(0x552196F3) else Color(0x222196F3), (BASE_SP * shownZoom / 100).sp.toPx()) }
                                }
                                // Commented letters: tinted (the comment itself in the bar above the ribbon).
                                if (commentsShown.isNotEmpty()) androidx.compose.foundation.Canvas(Modifier.matchParentSize().padding(margins)) {
                                    // A resolved one only while the cursor is in it (Word hides them).
                                    commentsShown.forEach { (c, r) -> if (r.last < text.length && (!c.done || c == hereComment)) shadeLetters(l, r.first, r.last + 1,
                                        if (c == hereComment) Color(0x66FFB300) else Color(0x33FFB300), (BASE_SP * shownZoom / 100).sp.toPx()) }
                                }
                                // Spelling: a red wavy line under each word that looks misspelled.
                                if (spell.shown.isNotEmpty()) androidx.compose.foundation.Canvas(Modifier.matchParentSize().padding(margins)) { spellingLines(l, spell.shown) }
                                // ✦ Suggestions: each one's letters shaded, deeper for the one in the bar.
                                if (suggestShown.isNotEmpty()) androidx.compose.foundation.Canvas(Modifier.matchParentSize().padding(margins)) {
                                    suggestShown.forEach { s -> if (s.end <= text.length) shadeLetters(l, s.at, s.end,
                                        if (s == hereSuggestion) Color(0x669C27B0) else Color(0x2A9C27B0), (BASE_SP * shownZoom / 100).sp.toPx()) }
                                }
                                // Tracked changes: inserted letters underlined, deleted struck through (their colour is the author's,
                                // set on the letters), a paragraph mark's change a ¶, and a bar in the margin beside each changed line.
                                if (changesShown.isNotEmpty()) androidx.compose.foundation.Canvas(Modifier.matchParentSize().padding(margins)) {
                                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = (BASE_SP * shownZoom / 100).sp.toPx() * 0.8f }
                                    changesShown.forEach { (c, a, z) ->
                                        if (z > text.length) return@forEach
                                        val color = Color(0xFF000000 or TrackChanges.color(c.author).toLong())
                                        val first = l.getLineForOffset(a); val last = l.getLineForOffset(if (z > a) z - 1 else a)
                                        for (line in first..last) {
                                            drawLine(color, Offset(-8.dp.toPx(), l.getLineTop(line)), Offset(-8.dp.toPx(), l.getLineBottom(line)), 1.5.dp.toPx())
                                            if (z == a) {
                                                paint.color = color.toArgb()
                                                drawContext.canvas.nativeCanvas.drawText("¶", l.getHorizontalPosition(a, true) + 2.dp.toPx(), l.getLineBaseline(line), paint)
                                                continue
                                            }
                                            val s = maxOf(a, l.getLineStart(line)); val e = minOf(z, l.getLineEnd(line, true))
                                            if (e <= s) continue
                                            val x1 = l.getBoundingBox(s).left; val x2 = l.getBoundingBox(e - 1).right
                                            val base = l.getLineBaseline(line)
                                            when (c.kind) {
                                                TrackChanges.Kind.Inserted -> drawLine(color, Offset(x1, base + 2.dp.toPx()), Offset(x2, base + 2.dp.toPx()), 1.dp.toPx())
                                                TrackChanges.Kind.Deleted -> (base - (base - l.getLineTop(line)) * 0.3f).let { y -> drawLine(color, Offset(x1, y), Offset(x2, y), 1.dp.toPx()) }
                                                else -> Unit
                                            }
                                        }
                                    }
                                }
                                // …and a paragraph's borders, as a light outline (the pages draw them as they print).
                                val looks = remember(meta.paras, documentStyles) { meta.paras.map { if (it.table != null) null else documentStyles.look(it) } }
                                // Page breaks, column breaks and section ends: a dashed line at the paragraph that starts after them, named.
                                val breakNames = remember(looks, meta.paras, documentStyles) {
                                    // How the section after a break starts is said by that section's own sectPr (the next one's).
                                    val sections = if (meta.paras.any { p -> p.keep?.parts?.any { it.first == "sectPr" } == true }) documentStyles.sections(meta.paras) else emptyList()
                                    looks.indices.mapNotNull { i -> if (i == 0) null else listOfNotNull(
                                        context.getString(R.string.words_page_break).takeIf { looks[i]?.pageBreakBefore == true },
                                        context.getString(if (sections.getOrNull(i)?.continuous == true) R.string.words_section_break_continuous else R.string.words_section_break_next_page)
                                            .takeIf { meta.paras[i - 1].keep?.parts?.any { it.first == "sectPr" } == true },
                                        context.getString(R.string.words_column_break).takeIf { looks[i]?.columnBreakBefore == true },
                                        context.getString(R.string.words_section_break).takeIf { kind == OfficeKind.Odt && OdtSections.tags(meta.paras[i]) != OdtSections.tags(meta.paras[i - 1]) },
                                    ).takeIf { it.isNotEmpty() }?.let { i to it.joinToString(" · ") } }.toMap()
                                }
                                val breaks = breakNames.keys.toList()
                                val boxed = remember(looks) { looks.indices.filter { looks[it]?.box != null } }
                                val starts = remember(text, breaks, boxed) { if (breaks.isEmpty() && boxed.isEmpty()) emptyList() else state.toRichTextDocument().let { rich ->
                                    WriterBridge.paragraphStarts(text, rich).takeIf { it.size == meta.paras.size } } ?: emptyList() }
                                if (starts.isNotEmpty()) androidx.compose.foundation.Canvas(Modifier.matchParentSize().padding(margins)) {
                                    val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
                                    breaks.forEach { i -> starts.getOrNull(i)?.takeIf { it <= text.length }?.let { o ->
                                        val y = l.getLineTop(l.getLineForOffset(o))
                                        drawLine(Color(0xFF9AA0A6), Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = dash)
                                    } }
                                    boxed.forEach { i ->
                                        val box = looks[i]?.box ?: return@forEach
                                        val a = starts.getOrNull(i)?.takeIf { it <= text.length } ?: return@forEach
                                        val z = ((starts.getOrNull(i + 1) ?: (text.length + 1)) - 1).coerceIn(a, text.length)
                                        val top = l.getLineTop(l.getLineForOffset(a)); val bottom = l.getLineBottom(l.getLineForOffset(z))
                                        val c = Color(0xFF5F6368); val sw = 1.dp.toPx(); val x1 = size.width
                                        val joinsAbove = looks.getOrNull(i - 1)?.box == box; val joinsBelow = looks.getOrNull(i + 1)?.box == box
                                        if (box.top != null && !joinsAbove) drawLine(c, Offset(0f, top), Offset(x1, top), sw)
                                        if (box.between != null && joinsAbove) drawLine(c, Offset(0f, top), Offset(x1, top), sw)
                                        if (box.bottom != null && !joinsBelow) drawLine(c, Offset(0f, bottom), Offset(x1, bottom), sw)
                                        if (box.left != null) drawLine(c, Offset(0f, top), Offset(0f, bottom), sw)
                                        if (box.right != null) drawLine(c, Offset(x1, top), Offset(x1, bottom), sw)
                                    }
                                }
                                if (starts.isNotEmpty()) Box(Modifier.matchParentSize().padding(margins)) {
                                    breaks.forEach { i -> starts.getOrNull(i)?.takeIf { it <= text.length }?.let { o ->
                                        val y = with(density) { l.getLineTop(l.getLineForOffset(o)).toDp() }
                                        Text(breakNames[i].orEmpty() + (pageStarts.firstOrNull { it.first == i }?.let { " · " + stringResource(R.string.words_page_n, it.second) } ?: ""), color = Color(0xFF80868B), fontSize = 10.sp, modifier = Modifier.align(Alignment.TopEnd).offset(y = y - 14.dp).background(paper).padding(horizontal = 4.dp))
                                    } }
                                }
                                // The first page's header, faded in the margin above the text (Word shows it so while editing the text).
                                headPage?.let { hp -> androidx.compose.foundation.Canvas(Modifier.matchParentSize().padding(margins)) { drawIntoCanvas { c ->
                                    val n = c.nativeCanvas; val k = scale.sp.toPx()
                                    n.save(); n.scale(k, k); n.translate(-hp.page.left, -hp.page.top - firstBefore)
                                    n.clipRect(0f, 0f, hp.page.width, hp.page.top)
                                    n.saveLayerAlpha(0f, 0f, hp.page.width, hp.page.top, 110)
                                    hp.draw(n); n.restore(); n.restore()
                                } } }
                                // Where the pages break: a dashed line and the page's number, as at a page break.
                                val pageMarks = remember(text, pageStarts) { if (pageStarts.isEmpty()) emptyList() else WriterBridge.paragraphStarts(text, state.toRichTextDocument()).let { st ->
                                    pageStarts.filter { (j, _) -> looks.getOrNull(j)?.pageBreakBefore != true }.mapNotNull { (j, n) -> st.getOrNull(j)?.takeIf { it <= text.length }?.let { l.getLineTop(l.getLineForOffset(it)) to n } } } }
                                if (pageMarks.isNotEmpty()) {
                                    androidx.compose.foundation.Canvas(Modifier.matchParentSize().padding(margins)) {
                                        val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))
                                        pageMarks.forEach { (y, _) -> drawLine(Color(0xFF1A73E8).copy(alpha = 0.5f), Offset(-margins.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr).toPx(), y), Offset(size.width + margins.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr).toPx(), y), 1.dp.toPx(), pathEffect = dash) }
                                    }
                                    Box(Modifier.matchParentSize().padding(margins)) {
                                        pageMarks.forEach { (y, n) ->
                                            Text(stringResource(R.string.words_page_n, n), color = Color(0xFF1A73E8), fontSize = 10.sp, modifier = Modifier.align(Alignment.TopEnd).offset(y = with(density) { y.toDp() } - 14.dp).background(paper).padding(horizontal = 4.dp))
                                        }
                                    }
                                }
                                val paraStarts = remember(text, floatsShown) { if (floatsShown.isEmpty()) emptyList() else WriterBridge.paragraphStarts(text, state.toRichTextDocument()) }
                                Box(Modifier.matchParentSize().padding(margins)) {
                                    // Drawn in the order the pages draw them (Arrange: the higher over the lower; else as in the text).
                                    placed.sortedBy { PictureXml.zOf(it.second) }.forEach { (i, img, at) ->
                                        // In front of or behind the text: in its place on the page, its own size (from its paragraph's top).
                                        val float = floatsShown[img.key]
                                        val left = float?.let { with(density) { (it.x * scale).sp.toPx() } } ?: at.first
                                        val bottom = at.second
                                        val (w, h) = float?.let { it.w * scale to it.h * scale } ?: WriterBridge.shownSize(img, scale)
                                        val y = if (float != null) (l.getLineTop(l.getLineForOffset(paraStarts.lastOrNull { it <= i } ?: i)) + with(density) { (float.y * scale).sp.toPx() }).toInt()
                                            else if (img.table != null) l.getLineTop(l.getLineForOffset(i)).toInt()
                                            else with(density) { maxOf(l.getLineTop(l.getLineForOffset(i)), bottom - h.sp.toPx()).toInt() } // standing on the line, as Word places an inline picture (from its top when the line is only the picture: Display)
                                        val size = with(density) { stretch?.takeIf { selectedPicture?.first == i }?.let { (a, b) -> Modifier.size(a.toDp(), b.toDp()) } ?: Modifier.size(w.sp.toDp(), h.sp.toDp()) }
                                        val shownWidth = with(density) { w.sp.toPx() }
                                        val shownHeight = with(density) { h.sp.toPx() }
                                        val selected = selectedPicture?.first == i
                                        val table = img.table
                                        // A table is laid out once (not on every keystroke) and drawn as page view draws it: sharp however long.
                                        val laidTable by produceState<Pair<LaidPage, List<TableCellArea>>?>(null, img, meta.basePt, documentStyles, packageParts) {
                                            value = table?.let { t -> withContext(Dispatchers.Default) { runCatching {
                                                val areas = ArrayList<TableCellArea>()
                                                OfficePages.tableLaid(meta, t, documentStyles, img.widthPt, areas) { n -> n.bytes ?: n.part?.let { packageParts[it] } } to areas
                                            }.onFailure { WriterLog.e("table", it) }.getOrNull() } }
                                        }
                                        val cells = laidTable?.second.orEmpty()
                                        val objectInput = if (table != null) Modifier.pointerInteropFilter { event ->
                                            if (event.action == android.view.MotionEvent.ACTION_UP) {
                                                commitCell()
                                                state.selection = androidx.compose.ui.text.TextRange(i, i + 1)
                                                tab = WriterTab.Table
                                                val x = event.x / shownWidth * img.widthPt
                                                val yy = event.y / shownHeight * img.heightPt
                                                cells.firstOrNull { x >= it.x && x <= it.x + it.width && yy >= it.y && yy <= it.y + it.height }?.let { area ->
                                                    val ref = TableCellRef(img.key, area.row, area.cell)
                                                    if (mergeFrom?.key == img.key) { mergeTo = ref; return@let } // choosing where the merge ends
                                                    selectedCell = ref
                                                    val cell = table.rows[area.row].cells[area.cell]
                                                    if (cell.paras.any { p -> p.table != null || p.runs.any { it.image != null } }) message = context.getString(R.string.words_cell_nested)
                                                    else WriterBridge.cellText(cell).let { text -> cellEdit = TableCellEdit(ref, TextFieldValue(text, androidx.compose.ui.text.TextRange(text.length)), cell) }
                                                }
                                            }
                                            true
                                        } else Modifier
                                            .then(if (float?.behind == true) Modifier.graphicsLayer { alpha = 0.45f } else Modifier) // behind the text: the text stays readable over it
                                            // Selected, dragged where it goes on its page — fixed there, free of the text (Word's "Fix position
                                            // on page", as Picture › Position places it).
                                            .then(if (!selected || float == null) Modifier else Modifier.graphicsLayer { translationX = drag.x; translationY = drag.y }
                                                .pointerInput(i, img.key, float) {
                                                    detectDragGestures(
                                                        onDrag = { change, delta -> change.consume(); drag += delta },
                                                        onDragEnd = {
                                                            val k = with(density) { scale.sp.toPx() }
                                                            val moved = drag; drag = Offset.Zero
                                                            changePicture(img.key, PictureXml.placed(img, float.pageX + moved.x / k, float.pageY + moved.y / k, kind))
                                                        },
                                                        onDragCancel = { drag = Offset.Zero },
                                                    )
                                                })
                                            .then(if (!selected || float != null) Modifier else Modifier.graphicsLayer { translationX = drag.x; translationY = drag.y; alpha = if (drag == Offset.Zero) 1f else 0.7f }
                                                .pointerInput(i, img.key) {
                                                    detectDragGestures(
                                                        onDrag = { change, delta -> change.consume(); drag += delta },
                                                        onDragEnd = {
                                                            val middle = Offset(left + drag.x, y + drag.y + with(density) { h.sp.toPx() } / 2)
                                                            drag = Offset.Zero
                                                            movePicture(i, l.getOffsetForPosition(middle), img.key)
                                                        },
                                                        onDragCancel = { drag = Offset.Zero },
                                                    )
                                                })
                                            .combinedClickable(onLongClick = {
                                                // Held (asked 15 Oct): straight to Position, where it follows the finger — for one that is not in line.
                                                state.selection = androidx.compose.ui.text.TextRange(i, i + 1)
                                                tab = WriterTab.Picture
                                                if (PictureXml.wrapOf(img) != PictureWrap.InLine && PictureXml.editable(img)) { WriterLog.i("long press ${img.key}: position"); position(img.key) }
                                            }) {
                                                state.selection = androidx.compose.ui.text.TextRange(i, i + 1)
                                                tab = WriterTab.Picture
                                            }
                                        Box(Modifier.offset { IntOffset(left.toInt(), y) }.then(size)
                                            .then((turning?.takeIf { selected } ?: Arrange.rotationOf(img)).takeIf { it != 0f && (float != null || PictureXml.wrapOf(img) != PictureWrap.InLine) }?.let { Modifier.rotate(it) } ?: Modifier) // turned (Arrange › Rotate)
                                            .then(if (selected) Modifier.border(2.dp, Color(0xFF1A73E8)) else Modifier)
                                            .then(objectInput),
                                            contentAlignment = Alignment.Center) {
                                            if (table != null) androidx.compose.foundation.Canvas(Modifier.matchParentSize().background(Color.White)) {
                                                laidTable?.first?.let { laid -> drawIntoCanvas { c ->
                                                    val n = c.nativeCanvas; val s = this.size.width / laid.page.width
                                                    n.save(); n.scale(s, s); laid.draw(n); n.restore()
                                                } }
                                            } else if (img.group != null) {
                                                // A group: its pieces, as the pages draw them.
                                                val drawn = remember(img, documentStyles) { OfficePages.pictureOf(OfficeDoc(emptyList(), basePt = meta.basePt), documentStyles, img) { pictureBytes(it) } }
                                                androidx.compose.foundation.Canvas(Modifier.matchParentSize()) { drawIntoCanvas { c ->
                                                    val n = c.nativeCanvas; val k = this.size.width / img.widthPt.coerceAtLeast(0.1f)
                                                    n.save(); n.scale(k, k); drawn.setBounds(0, 0, img.widthPt.toInt(), img.heightPt.toInt()); drawn.draw(n); n.restore()
                                                } }
                                            } else if (img.math != null) {
                                                // An equation: drawn as the pages draw it.
                                                val drawn = remember(img.math) { MathDrawable(img.math, meta.basePt) }
                                                androidx.compose.foundation.Canvas(Modifier.matchParentSize()) { drawIntoCanvas { c -> drawn.setBounds(0, 0, this.size.width.toInt(), this.size.height.toInt()); drawn.draw(c.nativeCanvas) } }
                                            } else if (img.chart != null) {
                                                // A chart: drawn from what it shows, as the pages draw it.
                                                val drawn = remember(img.chart) { ChartDrawable(img.chart) }
                                                androidx.compose.foundation.Canvas(Modifier.matchParentSize()) { drawIntoCanvas { c ->
                                                    val n = c.nativeCanvas; val k = this.size.width / img.widthPt.coerceAtLeast(0.1f)
                                                    n.save(); n.scale(k, k); drawn.setBounds(0, 0, img.widthPt.toInt(), img.heightPt.toInt()); drawn.draw(n); n.restore()
                                                } }
                                            } else if (img.box != null) {
                                                // A text box: its words in it, as the pages draw it.
                                                val drawn = remember(img, documentStyles) { OfficePages.textBox(OfficeDoc(emptyList(), basePt = meta.basePt), documentStyles, img) { pictureBytes(it) } }
                                                androidx.compose.foundation.Canvas(Modifier.matchParentSize()) { drawIntoCanvas { c ->
                                                    val n = c.nativeCanvas; val k = this.size.width / img.widthPt.coerceAtLeast(0.1f)
                                                    n.save(); n.scale(k, k); drawn?.setBounds(0, 0, img.widthPt.toInt(), img.heightPt.toInt()); drawn?.draw(n); n.restore()
                                                } }
                                            } else if (ShapePicture.of(img) != null) {
                                                // A shape: drawn as the pages draw it (its outline's width in points too).
                                                val shape = remember(img.raw) { ShapePicture.of(img)!! }
                                                androidx.compose.foundation.Canvas(Modifier.matchParentSize()) { drawIntoCanvas { c ->
                                                    val n = c.nativeCanvas; val k = this.size.width / img.widthPt.coerceAtLeast(0.1f)
                                                    n.save(); n.scale(k, k); shape.drawAt(n, 0f, 0f, img.widthPt, img.heightPt); n.restore()
                                                } }
                                            } else Image(picturePainter(img, packageParts), contentDescription = PictureXml.kind(img), contentScale = ContentScale.FillBounds, modifier = Modifier.matchParentSize())
                                            if (PictureXml.video(img)?.let(OfficeVideo::audio) == false) androidx.compose.foundation.Canvas(Modifier.matchParentSize()) { drawIntoCanvas { c -> OfficeVideo.mark(c.nativeCanvas, 0f, 0f, this.size.width, this.size.height) } }
                                            playing?.takeIf { it.first == img.key }?.let { (_, f) -> VideoFrame(f, Modifier.matchParentSize()) { playing = null } }
                                            // Not a picture (a chart, a shape Writer cannot draw): a grey box that says what it is, kept in the file as it was.
                                            if (pictureBytes(img) == null && img.table == null && img.chart == null && img.math == null && img.group == null && img.box == null && ShapePicture.of(img) == null) Text(PictureXml.kind(img), color = Color(0xFF5F6368), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                            // The cells a merge would take, lit up; else the cell the Table tab acts on, outlined.
                                            if (table != null && tab == WriterTab.Table) {
                                                val from = mergeFrom?.takeIf { it.key == img.key }
                                                val lit = from?.let { f -> TableEdit.cellsBetween(table, f.row to f.cell, (mergeTo ?: f).let { it.row to it.cell }) }
                                                    ?: selectedCell?.takeIf { it.key == img.key && cellEdit == null }?.let { setOf(it.row to it.cell) }.orEmpty()
                                                cells.filter { (it.row to it.cell) in lit }.forEach { a ->
                                                    Box(Modifier.align(Alignment.TopStart)
                                                        .offset { IntOffset((a.x / img.widthPt * shownWidth).roundToInt(), (a.y / img.heightPt * shownHeight).roundToInt()) }
                                                        .size(with(density) { (a.width / img.widthPt * shownWidth).toDp() }, with(density) { (a.height / img.heightPt * shownHeight).toDp() })
                                                        .background(if (from != null) WORD_BLUE.copy(alpha = 0.18f) else Color.Transparent).border(2.dp, WORD_BLUE))
                                                }
                                            }
                                            val edit = cellEdit?.takeIf { it.ref.key == img.key }
                                            val area = edit?.let { e -> cells.firstOrNull { it.row == e.ref.row && it.cell == e.ref.cell } }
                                            if (edit != null && area != null) {
                                                val focus = remember(edit.ref) { FocusRequester() }
                                                val keyboard = LocalSoftwareKeyboardController.current
                                                var hadFocus by remember(edit.ref) { mutableStateOf(false) }
                                                LaunchedEffect(edit.ref) { focus.requestFocus(); keyboard?.show() }
                                                BasicTextField(
                                                    value = edit.value,
                                                    onValueChange = { cellEdit = edit.copy(value = it) },
                                                    // As big as the cell's text is drawn: its size in points, at the table's scale.
                                                    textStyle = TextStyle(color = Color.Black, textAlign = when (edit.cell.paras.firstOrNull()?.align) {
                                                        DocAlign.Center -> TextAlign.Center; DocAlign.End -> TextAlign.End; DocAlign.Justify -> TextAlign.Justify; else -> TextAlign.Start },
                                                        fontSize = with(density) {
                                                        val para = table!!.rows[area.row].cells[area.cell].paras.firstOrNull()
                                                        ((para?.let { documentStyles.look(it).sizePt } ?: meta.basePt) * shownWidth / img.widthPt).toSp()
                                                    }),
                                                    cursorBrush = SolidColor(WORD_BLUE),
                                                    // Its looks shown as they are typed and changed: each run's, from the working cell.
                                                    visualTransformation = { typed ->
                                                        val spPerPt = with(density) { (shownWidth / img.widthPt).toSp().value }
                                                        androidx.compose.ui.text.input.TransformedText(cellAnnotated(WriterBridge.withCellText(edit.cell, typed.text), spPerPt) { p, r ->
                                                            r.sizePt ?: documentStyles.look(p).sizePt ?: meta.basePt }, androidx.compose.ui.text.input.OffsetMapping.Identity)
                                                    },
                                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                                    keyboardActions = KeyboardActions(onDone = { commitCell(); keyboard?.hide() }),
                                                    // Where the cell's own text sits: its margins, and its vertical alignment.
                                                    decorationBox = { inner ->
                                                        val m = documentStyles.tableSpec(table!!).rows.getOrNull(area.row)?.cells?.getOrNull(area.cell)?.margin
                                                        val px = { pt: Float -> with(density) { (pt * shownWidth / img.widthPt).toDp() } }
                                                        Box(Modifier.fillMaxSize().padding(start = px(m?.get(0) ?: 5.4f), top = px(m?.get(1) ?: 0f), end = px(m?.get(2) ?: 5.4f), bottom = px(m?.get(3) ?: 0f)),
                                                            contentAlignment = when (TableEdit.vAlign(edit.cell.props)) { "center" -> Alignment.CenterStart; "bottom" -> Alignment.BottomStart; else -> Alignment.TopStart }) { inner() }
                                                    },
                                                    modifier = Modifier.align(Alignment.TopStart) // the box centres its content; the cell is placed from the corner
                                                        .offset { IntOffset((area.x / img.widthPt * shownWidth).roundToInt(), (area.y / img.heightPt * shownHeight).roundToInt()) }
                                                        .size(with(density) { (area.width / img.widthPt * shownWidth).toDp() }, with(density) { (area.height / img.heightPt * shownHeight).toDp() })
                                                        .background(TableEdit.shading(edit.cell.props)?.let { Color(0xFF000000 or it.toLong()) } ?: Color.White).border(2.dp, WORD_BLUE)
                                                        .focusRequester(focus).onFocusChanged { f ->
                                                            if (f.isFocused) hadFocus = true else if (hadFocus) commitCell()
                                                        },
                                                )
                                            }
                                        }
                                        // Handles on it: stretch from the corner or a side, turn from the knob above (asked 15 Oct).
                                        if (selected && table == null && drag == Offset.Zero) ObjectHandles(left, y.toFloat(), stretch?.first ?: shownWidth, stretch?.second ?: shownHeight,
                                            turning ?: if (PictureXml.wrapOf(img) != PictureWrap.InLine) Arrange.rotationOf(img) else 0f,
                                            rotatable = PictureXml.wrapOf(img) != PictureWrap.InLine && img.raw?.startsWith("<draw:g") != true,
                                            keepAspect = pictureBytes(img) != null, size = { a, b -> stretch = a to b }, sized = {
                                                val k = with(density) { scale.sp.toPx() }
                                                stretch?.let { (a, b) -> stretch = null; WriterLog.i("handles: ${img.key} ${img.widthPt}×${img.heightPt} → ${a / k}×${b / k} pt"); changePicture(img.key, PictureXml.resized(img, a / k, b / k)) }
                                            }, turn = { turning = it }, turned = { turning?.let { turning = null; rotate(img.key, it) } })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        }
        // View › Navigation: the headings on the left, over the desk; a tap goes there (a phone's closes after).
        if (navOpen && loaded) {
            val headings = remember(meta, viewing) { navHeadings(if (viewing) meta else current()) }
            NavigationPane(headings, w, Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 120.dp, bottom = if (viewing) 16.dp else 150.dp).width(if (tablet) 280.dp else 260.dp),
                go = { h ->
                    WriterLog.i("navigate to heading ${h.level} (paragraph ${h.para})")
                    if (viewing) pagesIndex?.getOrNull(h.para)?.takeIf { it >= 0 }?.let { goPage = it to System.nanoTime() }
                    else WriterBridge.paragraphStarts(state.annotatedString.text, state.toRichTextDocument()).getOrNull(h.para)?.let {
                        state.selection = androidx.compose.ui.text.TextRange(it); goText = it to System.nanoTime() }
                    if (!tablet) navOpen = false
                }, close = { navOpen = false })
        }
        // The ribbon, like Word's, at the bottom: a line of tabs and a line of that tab's tools, scrolling sideways
        // when they do not fit. A floating island; typing on the screen's keyboard, a flat strip on it. With a physical
        // keyboard (a keyboard case) the island stays, as high above its suggestions bar as above the screen's bottom
        // (asked 2026-09-27: the strip pressed against them).
        val imeOpen = WindowInsets.isImeVisible
        val config = LocalConfiguration.current
        val physicalKeyboard = config.keyboard == android.content.res.Configuration.KEYBOARD_QWERTY &&
            config.hardKeyboardHidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO
        val island = !imeOpen || physicalKeyboard
        // The phone with the screen's keyboard up: Word's bar on it (B I U, highlighter, dictation, ⋯, hide); ⋯ the ribbon.
        var ribbonOverKeyboard by remember { mutableStateOf(false) }
        LaunchedEffect(imeOpen) { if (!imeOpen) ribbonOverKeyboard = false }
        val keyboardBar = !island && !ribbonOverKeyboard && LocalConfiguration.current.screenWidthDp < 700
        // A Design gallery: in the ribbon's place, a strip of its choices (asked 15 Oct); the page above shows the one tapped.
        var stripOf by remember { mutableStateOf<DesignGallery?>(null) } // kept while its strip slides away
        if (designGallery != null && stripOf != designGallery) stripOf = designGallery
        androidx.compose.animation.AnimatedVisibility(designGallery != null && (!viewing || galleryViewing != null) && !reading,
            Modifier.align(Alignment.BottomCenter), enter = SLIDE_IN, exit = SLIDE_OUT) { stripOf?.let { g -> DesignGalleryStrip(g, galleryChosen, ::galleryCancel, ::galleryOk, ::galleryPick,
            more = if (g == DesignGallery.PageNumbers || g == DesignGallery.HeaderFooter) { { galleryCancel(); headerPage = true } }
                else if (g == DesignGallery.DropCap) { { galleryCancel(); dropAt?.let { i -> dropCapPage = i to DropCap.of(current().paras.getOrNull(i)?.keep) } } } else null,
            drops = listOfNotNull(DropKind.None, DropKind.Dropped, DropKind.Margin.takeIf { kind == OfficeKind.Docx }),
            dropLines = dropLines, dropLinesChange = { n -> dropLines = n; (galleryChosen as? DropKind)?.takeIf { it != DropKind.None }?.let(::galleryPick) },
            glow = kind == OfficeKind.Docx, title = name.substringBeforeLast('.'), lineModes = LINE_NUMBERINGS.filter { it.mode != "newSection" || kind == OfficeKind.Docx },
            fonts = remember(g) { if (g == DesignGallery.Font) (meta.paras.flatMap { p -> p.runs.mapNotNull { r -> r.font?.let { documentStyles.fontName(it) } } } + listOfNotNull(hereFont)).distinct().sorted().plus(WRITER_FONTS).distinct() else emptyList() },
            now = hereFont) } }
        // A window open over the page (Standards, a shape's): no ribbon — not needed, and the page shows what is going on.
        // The ribbon and a gallery's strip slide down and up as they take each other's place (asked 15 Oct).
        // at the bottom for a finger; at the top, under the top bar, while a mouse is in use (or as Settings say), as a PC's Word
        val atTop = OfficeUi.top
        androidx.compose.animation.AnimatedVisibility(!viewing && !reading && designGallery == null && !standards && !citeStyling && shapeOptions == null && !shapesPage,
            (if (atTop) Modifier.align(Alignment.TopCenter).padding(top = with(density) { barPx.toDp() }) else Modifier.align(Alignment.BottomCenter)).onSizeChanged { islandPx = it.height },
            enter = if (atTop) androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn() else SLIDE_IN,
            exit = if (atTop) androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut() else SLIDE_OUT) { Column(Modifier.fillMaxWidth().then(
            if (!island) Modifier.background(Color(0xFF16191A))
            // a phone: a solid bar across the bottom, as Tables' and Slides' ribbons (asked 16 Oct); a tablet: the floating island
            else if (!tablet) Modifier.background(Color(0xFF16191A)).then(if (imeOpen || atTop) Modifier else Modifier.bottomBarPadding()).padding(vertical = 4.dp)
            else Modifier.then(if (imeOpen || atTop) Modifier else Modifier.bottomBarPadding()).padding(12.dp).clip(MaterialTheme.shapes.extraLarge)
                .background(Color(0xFF16191A)).padding(vertical = 4.dp))) { // solid: the page is white
            // A note's number, the cursor at it: the note's words (Edit opens them).
            hereNote?.let { (n, number, _) ->
                Row(Modifier.fillMaxWidth().clickable { noteEditing = n to number }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(if (n.end) R.string.words_endnote_n else R.string.words_footnote_n, number), color = w, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    Text(n.paras.joinToString(" ") { it.text }.ifBlank { stringResource(R.string.words_empty) }, color = w.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                    Text(stringResource(R.string.common_edit), color = w, style = MaterialTheme.typography.labelLarge)
                }
            }
            // A citation, the cursor in it: each of its sources (a tap edits it), and Change (its sources or page).
            hereCite?.let { (c, r) -> CitationBar(c, meta.sources, w, { s, t -> WriterLog.i("citation: edit source $t"); if (s != null) sourceEdit = s else sourceNew = true }) { WriterLog.i("citation: change $c"); citeChanging = c to r } }
            // An equation chosen: its linear form, typed into in place (the page redraws it).
            selectedPicture?.second?.let { k -> pictures[k]?.math?.let { m -> androidx.compose.runtime.key(k) { EquationBar(m, w) { putEquation(k, it) } } } }
            // A word that looks misspelled, the cursor in it: the spell checker's suggestions, a tap puts one in.
            SpellingBar(spell)
            // ✦ Suggestions waiting: the one at the cursor with its replacement and reason, Accept / Reject / Accept all.
            if (suggestBusy != null || suggestShown.isNotEmpty()) SuggestBar(suggestBusy, hereSuggestion, suggestShown.indexOf(hereSuggestion), suggestShown.size, w,
                go = { d -> goSuggestion(suggestShown.getOrNull(Math.floorMod(suggestShown.indexOf(hereSuggestion) + d, suggestShown.size))) },
                accept = { hereSuggestion?.let(::acceptSuggestion) }, reject = { hereSuggestion?.let { s -> suggestions = suggestShown - s; goSuggestion((suggestShown - s).firstOrNull { it.at >= s.at }) } },
                acceptAll = { acceptAllSuggestions() }, close = { suggestions = emptyList() })
            // The form field the cursor is in: its choices, a date, a tick (a text field fills by typing, or Fill in).
            hereControl?.let { (pr, _, r) -> FormBar(pr, controlsShown.filter { it.first == pr }.joinToString("") { it.second }, w,
                choosePicture = { state.selection = androidx.compose.ui.text.TextRange(r.first, r.first + 1); replacePicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { newPr, text -> fillControl(pr, newPr, text) } }
            // The tracked change the cursor is in: what, who and when (Review has Accept and Reject).
            hereChange?.let { c ->
                Row(Modifier.fillMaxWidth().clickable { tab = WriterTab.Review }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF000000 or TrackChanges.color(c.author).toLong())))
                    Text(stringResource(c.label) + (if (c.author.isNotBlank()) " · " + c.author else "") + (c.date?.let { d -> runCatching { " · " + java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
                        .format(java.time.OffsetDateTime.parse(d).atZoneSameInstant(java.time.ZoneId.systemDefault())) }.getOrNull() } ?: ""),
                        color = w, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                }
            }
            // The comment the cursor is in: who and what, a tap opens it.
            hereComment?.takeIf { !paneOn }?.let { c ->
                Row(Modifier.fillMaxWidth().clickable { commentsOpen = true }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFFFB300)))
                    Text((if (c.author.isNotBlank()) c.author + ": " else "") + c.text.replace('\n', ' '), color = w, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                    Text(stringResource(R.string.common_open), color = w, style = MaterialTheme.typography.labelLarge)
                }
            }
            if (reader.speaking) ReadAloudBar(reader, readingParas.getOrNull(reader.at), w)
            if (keyboardBar) {
                val keyboard = LocalSoftwareKeyboardController.current
                val span = state.currentSpanStyle
                KeyboardBar(w, boldHere(span), span.fontStyle == FontStyle.Italic, span.textDecoration?.contains(TextDecoration.Underline) == true,
                    { toggleBold(span) }, { toggleItalic(span) }, { state.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline)) },
                    { c -> if (c == null) span.background.takeIf { it != Color.Unspecified }?.let { state.removeSpanStyle(SpanStyle(background = it)) } else state.addSpanStyle(SpanStyle(background = c)) },
                    { dictate?.invoke() ?: run { message = context.getString(R.string.words_no_voice_typing) } }, { ribbonOverKeyboard = true }, { keyboard?.hide() })
            } else OfficeStripFrame(w, Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth()) {
            val selectedObject = selectedPicture?.second?.let { pictures[it] }
            val tabNames = WriterTab.entries.map { stringResource(it.label) }
            val objectTab = selectedObject?.let { objectKindName(PictureXml.kind(it)) }
            // Selection first, where the hand is when words are chosen (asked 14 Oct, after trying it last).
            OfficeTabRow((listOf(WriterTab.Selection) + WriterTab.entries.filter { it != WriterTab.Selection }).filter { t -> when (t) {
                WriterTab.Selection -> selectionTabShown || tab == WriterTab.Selection
                WriterTab.Picture -> selectedObject != null && selectedObject.table == null
                WriterTab.Table -> selectedObject?.table != null
                else -> true
            } }, tab, w, WORD_BLUE, { tab = it }, contextual = if (selectedObject?.table != null) WriterTab.Table else WriterTab.Picture, label = { t -> if (t == WriterTab.Picture && objectTab != null) objectTab else tabNames[t.ordinal] }) {
                if (ribbonOverKeyboard) Tool(R.drawable.ic_more_horiz, stringResource(R.string.words_back_to_keyboard_bar), w, on = true) { ribbonOverKeyboard = false }
                // a picture, shape or table chosen: ✕ lets go of it, as Slides' and Tables' rows have (the tablet walk, 18 Oct)
                else if (selectedObject != null) androidx.compose.material3.IconButton(onClick = { state.selection = androidx.compose.ui.text.TextRange(state.selection.max); tab = WriterTab.Home },
                    modifier = Modifier.size(36.dp)) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_close), modifier = Modifier.size(16.dp)) }
            }
            // The tools of a tab in pages that fit the width, swiped one page at a time (asked 2026-09-27), with dots.
            val span = state.currentSpanStyle
            val para = state.currentParagraphStyle
            val pages: List<@Composable () -> Unit> = when (tab) {
                WriterTab.File -> listOf({
                    RibbonButton(stringResource(R.string.common_save), w, R.drawable.ic_save, enabled = loaded && (dirty || file == null || cellEdit != null)) { commitCell(); save() }
                    RibbonButton(stringResource(R.string.words_save_copy), w, R.drawable.ic_copy, enabled = loaded && file != null) { commitCell(); save(asCopy = true) }
                    RibbonButton(stringResource(R.string.words_autosave), w, R.drawable.ic_history, on = autoSave) {
                        autoSave = !autoSave; TextEditorStore(context).autoSave = autoSave
                        message = context.getString(if (!autoSave) R.string.words_autosave_off_msg else if (file == null) R.string.words_autosave_on_unnamed else if (meta.blocking.isNotEmpty()) R.string.words_autosave_on_copy else R.string.words_autosave_on)
                    }
                    // a phone: the Office home's favourite here (a tablet has the heart in the top bar; the user, 18 Oct)
                    FavouriteFileButton(file, w)
                }, {
                    RibbonButton(stringResource(R.string.words_save_as), w, R.drawable.ic_save, enabled = loaded) { commitCell(); naming = true }
                    RibbonButton(stringResource(R.string.common_rename), w, R.drawable.ic_edit, enabled = loaded && file != null) { renaming = true }
                    // as Tables' and Slides' (17 Oct): Versions, Export PDF, Print · Share, another app · About, log · Settings
                    RibbonButton(stringResource(R.string.words_versions), w, R.drawable.ic_history, enabled = loaded) { showVersions = true }
                    RibbonButton(stringResource(R.string.words_export_pdf), w, R.drawable.ic_file, enabled = loaded) { exportPdf() }
                    RibbonButton(stringResource(R.string.common_print), w, R.drawable.ic_print, enabled = loaded) { commitCell(); print() }
                }, {
                    RibbonButton(stringResource(R.string.common_share), w, R.drawable.ic_share, enabled = file?.isFile == true) {
                        file?.let { shareDriveFile(context, it) }
                        if (dirty) message = context.getString(R.string.words_shared_last_saved)
                    }
                    RibbonButton(stringResource(R.string.words_open_in_another_app), w, R.drawable.ic_open_with, enabled = file?.isFile == true) {
                        file?.let { openElsewhere(context, it) }
                        if (dirty) message = context.getString(R.string.words_opened_last_saved)
                    }
                    RibbonButton(stringResource(R.string.words_about_file), w, R.drawable.ic_info, enabled = loaded) { details = true }
                    // The log (asked 2026-10-01), to send when something goes wrong: what was done, not the words.
                    RibbonButton(stringResource(R.string.words_send_log), w, R.drawable.ic_share) { WriterLog.export(context)?.let { shareDriveFile(context, it) } }
                    // Office's settings (asked 16 Oct): the ribbon's place and size — Tetra's Settings › Office has them all
                    RibbonButton(stringResource(R.string.common_settings), w, R.drawable.ic_settings) { officeSettings = true }
                })
                // Typing in a table cell: Home formats the cell's text.
                WriterTab.Home -> cellEdit?.let { e -> cellHomePages(w, WriterBridge.withCellText(e.cell, e.value.text), e.value.selection,
                    { p, r -> r.sizePt ?: documentStyles.look(p).sizePt ?: meta.basePt }) { c -> cellEdit = e.copy(cell = c) } } ?: listOf({
                    Tool(R.drawable.ic_format_bold, stringResource(R.string.words_bold), w, on = boldHere(span)) { toggleBold(span) }
                    Tool(R.drawable.ic_format_italic, stringResource(R.string.words_italic), w, on = span.fontStyle == FontStyle.Italic) { toggleItalic(span) }
                    Tool(R.drawable.ic_format_underlined, stringResource(R.string.words_underline), w, on = span.textDecoration?.contains(TextDecoration.Underline) == true) { state.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline)) }
                    Tool(R.drawable.ic_format_strikethrough, stringResource(R.string.words_strikethrough), w, on = span.textDecoration?.contains(TextDecoration.LineThrough) == true) { state.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) }
                    // Superscript and subscript: read back in at once, so they show smaller as a file's do.
                    listOf("X²" to androidx.compose.ui.text.style.BaselineShift.Superscript, "X₂" to androidx.compose.ui.text.style.BaselineShift.Subscript).forEach { (label, shift) ->
                        val sign = Math.signum(span.baselineShift?.multiplier ?: 0f)
                        RibbonButton(label, w, on = sign == Math.signum(shift.multiplier)) { fun set() {
                            val was = span.baselineShift?.takeIf { sign != 0f }
                            // Off: the letters go back to the size they had (the file's own, or the one set on purpose).
                            if (was != null) {
                                WriterBridge.spOf(span.fontSize)?.let { shownSp ->
                                    if (kotlin.math.abs(shownSp - BASE_SP * SCRIPT_SIZE_OWN) < 0.001f * shownSp) state.removeSpanStyle(SpanStyle(fontSize = span.fontSize))
                                    else state.addSpanStyle(SpanStyle(fontSize = (shownSp / SCRIPT_SIZE).sp))
                                }
                                state.removeSpanStyle(SpanStyle(baselineShift = was))
                            }
                            if (sign != Math.signum(shift.multiplier)) state.addSpanStyle(SpanStyle(baselineShift = shift))
                            if (!state.selection.collapsed) { val at = state.selection; state.setRichTextDocument(WriterBridge.toEditor(current(), pictures, documentStyles), at) }
                        }
                            // Letters chosen are shown again (Writer's change, undone as one); at the cursor it is typing.
                            if (state.selection.collapsed) set() else change { set() }
                        }
                    }
                // Font and size on a page of their own: beside B I U they ran off a phone's width (found 14 Oct).
                }, {
                    // Superscript and subscript are shown smaller: the size told and changed is the letters' own.
                    val shrink = if ((span.baselineShift?.multiplier ?: 0f) != 0f) SCRIPT_SIZE else 1f
                    val size = (WriterBridge.spOf(span.fontSize) ?: BASE_SP) / shrink
                    // One point at a time, in the document's points (asked 2026-09-28: by 2 it skipped 13); − and + close
                    // together around a number of fixed width, so two digits fit on a phone as one does.
                    // At a picture or table the editor's character standing for it has its own (huge) size: the text's is told.
                    val onObject = state.annotatedString.text.let { t -> val i = state.selection.min
                        t.getOrNull(i) == EDITOR_PICTURE_CHAR && (!state.selection.collapsed || i == 0 || t.getOrNull(i - 1) == '\n') || t.getOrNull(i - 1) == EDITOR_PICTURE_CHAR }
                    val pt = if (onObject) meta.basePt.roundToInt() else (size / BASE_SP * meta.basePt).roundToInt()
                    fun setPt(n: Int) = state.addSpanStyle(SpanStyle(fontSize = (n.coerceIn(8, 72) * BASE_SP / meta.basePt * shrink).sp))
                    // Home › Font: the fonts in the ribbon's place, each shown in itself (asked 16 Oct, as Page numbers).
                    FontButton(hereFont, w, enabled = !onObject) { openGallery(DesignGallery.Font) }
                    SizeStepper("$pt", w, pt > 8, pt < 72, { setPt(pt - 1) }, { setPt(pt + 1) })
                }, {
                    Swatches(w, R.drawable.ic_format_color_text, stringResource(R.string.words_text_colour), TEXT_COLOURS) { c ->
                        if (c == null) span.color.takeIf { it != Color.Unspecified }?.let { state.removeSpanStyle(SpanStyle(color = it)) } else state.addSpanStyle(SpanStyle(color = c))
                    }
                    Swatches(w, R.drawable.ic_highlighter, stringResource(R.string.words_highlight), HIGHLIGHT_COLOURS) { c ->
                        if (c == null) span.background.takeIf { it != Color.Unspecified }?.let { state.removeSpanStyle(SpanStyle(background = it)) } else state.addSpanStyle(SpanStyle(background = c))
                    }
                    // Change case (Word's Aa): the chosen letters, or the word at the cursor.
                    RibbonMenu("Aa", w, preset = false, title = stringResource(R.string.words_change_case), choices = WriterBridge.Case.entries.map { c -> MenuChoice(stringResource(c.text)) { change {
                        val text = state.annotatedString.text
                        val sel = state.selection
                        val (from, to) = chosenOrWord()
                        state.setRichTextDocument(WriterBridge.changeCase(text, state.toRichTextDocument(), from, to, c), sel)
                    } } })
                    // Character spacing (Word's Font › Advanced › Spacing): the chosen words, or the word at the cursor, closer or wider
                    RibbonMenu(stringResource(R.string.words_spacing), w, R.drawable.ic_format_letter_spacing, title = stringResource(R.string.words_character_spacing), choices = CHARACTER_SPACINGS.mapIndexed { i, (_, pt) -> MenuChoice(stringResource(CHARACTER_SPACING_NAMES[i])) { setSpacing(pt) } })
                    // Format painter: the look of the letters at the cursor, painted on the next words chosen.
                    Tool(R.drawable.ic_format_paint, stringResource(R.string.words_format_painter), w, on = painter != null) { pickPainter() }
                    // Clear formatting: the chosen letters (or, with none chosen, the paragraph) back to their style's look.
                    Tool(R.drawable.ic_format_clear, stringResource(R.string.words_clear_formatting), w) { change {
                        val text = state.annotatedString.text
                        val rich = state.toRichTextDocument()
                        val sel = state.selection
                        val (from, to) = if (!sel.collapsed) sel.min to sel.max
                            else WriterBridge.blockRange(text, rich, sel.min) ?: (0 to 0)
                        state.setRichTextDocument(WriterBridge.clearFormatting(text, rich, from, to, meta.basePt), sel)
                        state.setRichTextDocument(WriterBridge.toEditor(current(), pictures, documentStyles), sel) // the style's look where the letters' own was
                    } }
                }, {
                    // The everyday styles; every other (Subtitle, Quote, Heading 4–6, the document's own) behind Styles….
                    val styles = documentStyles.paragraphStyles()
                    listOf(styles[0] to stringResource(R.string.words_style_normal), styles[1] to stringResource(R.string.words_style_title), styles.first { it.heading == 1 } to "H1", styles.first { it.heading == 2 } to "H2", styles.first { it.heading == 3 } to "H3").forEach { (style, label) ->
                        RibbonButton(label, w, on = hereStyle == style) { applyStyle(style) }
                    }
                    RibbonButton(stringResource(R.string.words_styles_more), w) { stylesPage = true }
                }, {
                    // a numbered item made a bullet (and back) is switched in the document itself, at its level: the library's
                    // toggles moved it a level out instead, still numbered — an empty one even after taking it out first (16 Oct)
                    Tool(R.drawable.ic_format_list_bulleted, stringResource(R.string.words_bullets), w, on = state.isUnorderedList) { if (state.isOrderedList) switchList(false) else state.toggleUnorderedList() }
                    Tool(R.drawable.ic_format_list_numbered, stringResource(R.string.words_numbering), w, on = state.isOrderedList) { if (state.isUnorderedList) switchList(true) else state.toggleOrderedList() }
                    Tool(R.drawable.ic_format_indent_decrease, stringResource(R.string.words_decrease_indent), w, enabled = state.canDecreaseListLevel) { state.decreaseListLevel() }
                    Tool(R.drawable.ic_format_indent_increase, stringResource(R.string.words_increase_indent), w, enabled = state.canIncreaseListLevel) { state.increaseListLevel() }
                    RibbonButton(stringResource(R.string.words_multilevel), w, R.drawable.ic_format_list_numbered) { openGallery(DesignGallery.Lists) }
                }, {
                    listOf(TextAlign.Start to R.drawable.ic_align_left, TextAlign.Center to R.drawable.ic_align_center, TextAlign.End to R.drawable.ic_align_right, TextAlign.Justify to R.drawable.ic_align_justify).forEach { (a, icon) ->
                        val on = para.textAlign == a || (a == TextAlign.Start && (para.textAlign == TextAlign.Unspecified || para.textAlign == TextAlign.Left))
                        Tool(icon, stringResource(when (a) { TextAlign.Center -> R.string.words_align_center; TextAlign.End -> R.string.words_align_end; TextAlign.Justify -> R.string.words_align_justify; else -> R.string.words_align_start }), w, on = on) { state.addParagraphStyle(ParagraphStyle(textAlign = a, textDirection = androidx.compose.ui.text.style.TextDirection.Content)) }
                    }
                }, {
                    // Line spacing (the pages and the file show it; the editor keeps its own lines) and the ⋯ page.
                    Tool(R.drawable.ic_format_line_spacing, stringResource(R.string.words_line_spacing), w) { chooseParagraphs { at, look -> paragraphPage = at to look } }
                    LINE_SPACINGS.forEach { m ->
                        RibbonButton(plain(m), w) {
                            chooseParagraphs { at, look -> setSpacing(at, look, look.copy(lineMultiple = m, lineExact = null)); message = context.getString(R.string.words_line_spacing_set, plain(m)) }
                        }
                    }
                }, {
                    // Borders (Word's, for paragraphs): a centred panel with a preview — the sides, their line, thickness and colour
                    RibbonButton(stringResource(R.string.words_borders), w, R.drawable.ic_border_bottom) { chooseParagraphs { at, look -> bordersPage = at to look } }
                    RibbonButton(stringResource(R.string.common_more), w, R.drawable.ic_more_vert) { chooseParagraphs { at, look -> paragraphPage = at to look } }
                })
                // Word's groups, in its order: Pages, Tables, Illustrations, Links, Comments, Header & Footer, Text, Symbols.
                WriterTab.Insert -> listOf({
                    RibbonButton(stringResource(R.string.words_blank_page), w, R.drawable.ic_note_add, enabled = loaded) { insertPageBreak(true) }
                    RibbonButton(stringResource(R.string.words_page_break), w, R.drawable.ic_insert_page_break, enabled = loaded) { insertPageBreak(false) }
                    RibbonDivider(w)
                    TableSizeButton(w, WORD_BLUE, enabled = loaded) { rows, columns ->
                        val table = if (kind == OfficeKind.Odt) TableEdit.newOdtTable(rows, columns, "Table " + java.util.UUID.randomUUID().toString().take(6))
                            else TableEdit.newTable(rows, columns, documentStyles.page.textWidth)
                        val (width, height) = WriterBridge.tableSize(meta, table, documentStyles)
                        val img = DocImage("t" + java.util.UUID.randomUUID(), null, width, height, table = table)
                        val at = placePicture(img)
                        state.selection = androidx.compose.ui.text.TextRange(at, at + 1)
                        selectedCell = selectedPicture?.second?.let { TableCellRef(it, 0, 0) } // the key it is shown by, now
                        tab = WriterTab.Table
                    }
                }, {
                    RibbonButton(stringResource(R.string.words_picture), w, R.drawable.ic_gallery, enabled = loaded) { pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    RibbonMenu(stringResource(R.string.words_signature), w, R.drawable.ic_draw, enabled = loaded, preset = false, choices = listOf(
                        MenuChoice(stringResource(if (signatureFile.isFile) R.string.words_put_my_signature else R.string.words_draw_my_signature), icon = R.drawable.ic_draw) { insertSignature() },
                        MenuChoice(stringResource(R.string.words_draw_new_one), icon = R.drawable.ic_edit, opens = true) { drawingSignature = true },
                        MenuChoice(stringResource(R.string.words_from_picture_of_it), icon = R.drawable.ic_gallery, opens = true) { pickSignature.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }))
                    RibbonButton(stringResource(R.string.words_shapes), w, R.drawable.ic_category, enabled = loaded) { shapesPage = true }
                    // Word's Online Video, or a film from the phone kept in the file (LibreOffice's media frame; in a .docx beside its picture)
                    VideoButton(w, enabled = loaded, phone = { pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }, online = ::insertOnlineVideo)
                }, {
                    RibbonButton(stringResource(R.string.words_text_box), w, R.drawable.ic_text_box, enabled = loaded) { boxing = null to "" }
                    RibbonButton(stringResource(R.string.words_wordart), w, R.drawable.ic_format_color_text, enabled = loaded) { openGallery(DesignGallery.WordArt) }
                    RibbonButton(stringResource(R.string.words_chart), w, R.drawable.ic_bar_chart, enabled = loaded) { charting = null to WORD_SAMPLE_CHART }
                }, {
                    RibbonButton(stringResource(if (state.isLink) R.string.words_edit_link else R.string.words_link), w, R.drawable.ic_link) { linking = true }
                    RibbonButton(stringResource(R.string.words_comment), w, R.drawable.ic_comment, enabled = loaded) { commenting = true }
                }, {
                    // Header, footer and page numbers: one page for all three (APA: the number top right).
                    RibbonButton(stringResource(R.string.words_header_footer), w, R.drawable.ic_page_header, enabled = loaded) { openGallery(DesignGallery.HeaderFooter) }
                    RibbonButton(stringResource(R.string.words_page_numbers), w, R.drawable.ic_numbers, enabled = loaded) { openGallery(DesignGallery.PageNumbers) }
                }, {
                    // Word's Drop Cap: a strip of cards in the ribbon's place (17 Oct, as Slides'), the one tapped shown at once; More…: its options
                    RibbonButton(stringResource(R.string.words_drop_cap), w, R.drawable.ic_drop_cap, enabled = loaded) { chooseParagraphs { at, _ -> dropAt = at.min()
                        dropLines = DropCap.of(current().paras.getOrNull(at.min())?.keep)?.lines ?: 3; openGallery(DesignGallery.DropCap) } }
                    RibbonButton(stringResource(R.string.words_date), w, R.drawable.ic_calendar_today, enabled = loaded) { datePage = true }
                    // A new line in the same paragraph — Shift+Enter in Word, which a phone keyboard does not have.
                    RibbonButton(stringResource(R.string.words_line_break), w, R.drawable.ic_wrap_text) { change {
                        state.addTextAfterSelection(LINE_BREAK.toString())
                        val at = state.selection
                        state.setRichTextDocument(WriterBridge.toEditor(current(), pictures, documentStyles), at) // shown as a new line at once
                    } }
                }, {
                    RibbonButton(stringResource(R.string.words_symbol), w, R.drawable.ic_special_character, enabled = loaded) { symbolPage = true }
                    RibbonButton(stringResource(R.string.words_equation), w, R.drawable.ic_functions, enabled = loaded) { equationOpen = "" to null }
                    RibbonButton(stringResource(R.string.words_content_control), w, R.drawable.ic_check_box, enabled = loaded) { controlNew = true }
                })
                WriterTab.Selection -> listOf({
                    RibbonButton(stringResource(R.string.common_cut), w, R.drawable.ic_content_cut, enabled = !state.selection.collapsed) { copyChosen(true) }
                    RibbonButton(stringResource(R.string.common_copy), w, R.drawable.ic_copy, enabled = !state.selection.collapsed) { copyChosen(false) }
                    RibbonButton(stringResource(R.string.common_paste), w, R.drawable.ic_content_paste) { textToolbar.paste?.invoke()?.also { dirty = true; WriterLog.i("pasted with its look") } ?: pastePlain() }
                }, {
                    RibbonButton(stringResource(R.string.words_paste_text_only), w, R.drawable.ic_content_paste) { pastePlain() }
                    RibbonButton(stringResource(R.string.common_select_all), w, R.drawable.ic_select_all) { textToolbar.selectAll?.invoke() ?: run { state.selection = androidx.compose.ui.text.TextRange(0, state.annotatedString.length) } }
                }, {
                    RibbonButton(stringResource(R.string.words_copy_formatting), w, R.drawable.ic_format_paint) {
                        copiedLook = runAt(state.selection.min)
                        message = context.getString(if (copiedLook != null) R.string.words_formatting_copied else R.string.words_no_formatting_to_copy)
                    }
                    RibbonButton(stringResource(R.string.words_paste_formatting), w, R.drawable.ic_format_paint, enabled = copiedLook != null) { copiedLook?.let { paint(it, state.selection) } }
                })
                WriterTab.Picture -> {
                    val (at, key) = selectedPicture ?: (0 to "")
                    val img = pictures[key]
                    val editable = img != null && PictureXml.editable(img)
                    // a signature's own tab (the user, 22 Oct: "It is a signature not an image on the tab like the wordart and textbox")
                    if (img != null && PictureXml.kind(img) == "Signature") {
                        val floating = editable && PictureXml.wrapOf(img) != PictureWrap.InLine
                        listOf({
                            RibbonButton(stringResource(R.string.words_size), w, R.drawable.ic_aspect_ratio, enabled = editable) { sizing = true }
                            RibbonButton(stringResource(R.string.words_position), w, R.drawable.ic_open_with, enabled = floating) { position(key) }
                            RibbonButton(stringResource(R.string.words_rotate), w, R.drawable.ic_rotate_right, enabled = floating) { rotating = key }
                        }, {
                            RibbonButton(stringResource(R.string.words_wrap_text), w, R.drawable.ic_wrap_text, enabled = editable) { wrapping = true }
                            RibbonButton(stringResource(R.string.words_bring_to_front), w, R.drawable.ic_flip_to_front, enabled = floating) { arrange(key, front = true) }
                            RibbonButton(stringResource(R.string.words_send_to_back), w, R.drawable.ic_flip_to_back, enabled = floating) { arrange(key, front = false) }
                        }, {
                            RibbonButton(stringResource(R.string.words_redraw), w, R.drawable.ic_draw) { redoSignature = key; drawingSignature = true }
                            RibbonButton(stringResource(R.string.common_delete), w, R.drawable.ic_delete) { state.removeTextRange(androidx.compose.ui.text.TextRange(at, at + 1)) }
                        })
                    } else {
                    val hasBytes = img != null && pictureBytes(img) != null
                    // A drawing made in Writer: drawn on again (its strokes ride in its picture).
                    val drawn = remember(key, img) { img?.let { pictureBytes(it) }?.let { InkPicture.strokesOf(it) } != null }
                    listOf({
                        img?.let(PictureXml::video)?.let { m -> RibbonButton(stringResource(R.string.words_play), w, R.drawable.ic_play) { play(key, m) } }
                        if (drawn) RibbonButton(stringResource(R.string.words_edit_drawing), w, R.drawable.ic_draw) { editDrawing(key) }
                        img?.chart?.let { ch -> RibbonButton(stringResource(R.string.words_edit_data), w, R.drawable.ic_bar_chart) { charting = key to ch } }
                        img?.math?.let { m -> RibbonButton(stringResource(R.string.words_edit_equation), w, R.drawable.ic_functions) { equationOpen = key to m } }
                        // a WordArt Writer made: its words, colour, effects and bend, all to change (Word's Text Effects after)
                        img?.box?.let { paras -> RibbonButton(stringResource(R.string.words_edit_text), w, R.drawable.ic_edit, enabled = editable) {
                            if (TextWarp.art(img.raw) != null) artEditing = key to paras.joinToString("\n") { it.text } else boxing = key to paras.joinToString("\n") { it.text } } }
                        // a shape's own words (Word's Add Text): typed in the box's window, in the shape's middle
                        if (img != null && img.box == null && img.group == null && ShapePicture.of(img)?.geometry?.let { it != "line" && it != "straightConnector1" } == true)
                            RibbonButton(stringResource(R.string.words_add_text), w, R.drawable.ic_edit, enabled = editable) { boxing = key to "" }
                        // A shape (or a text box's frame): its fill, outline, size, angle and alt text in one page.
                        if (img != null && img.group == null && ShapePicture.of(img) != null) RibbonButton(stringResource(R.string.words_shape_options), w, R.drawable.ic_tune, enabled = editable) { shapeOptions = key }
                        RibbonButton(stringResource(R.string.words_size), w, R.drawable.ic_aspect_ratio, enabled = editable) { sizing = true }
                        RibbonButton(stringResource(R.string.words_crop), w, R.drawable.ic_crop, enabled = hasBytes && editable) {
                            scope.launch {
                                cropping = withContext(Dispatchers.IO) { pictureBytes(img!!)?.let { runCatching { OfficePages.croppedPicture(img, it, 2400) }.getOrNull() } }
                                if (cropping == null) message = context.getString(R.string.words_cannot_crop)
                            }
                        }
                    }, {
                        RibbonButton(stringResource(R.string.words_wrap_text), w, R.drawable.ic_wrap_text, enabled = editable) { wrapping = true }
                        // Floating ones are dragged where they go on their page.
                        RibbonButton(stringResource(R.string.words_position), w, R.drawable.ic_open_with, enabled = editable && img?.let { PictureXml.wrapOf(it) } != PictureWrap.InLine) { position(key) }
                    }, {
                        // Arrange (Word's): which floating picture is over which, and lined up with the margins.
                        val floating = editable && img?.let { PictureXml.wrapOf(it) } != PictureWrap.InLine
                        RibbonButton(stringResource(R.string.words_bring_to_front), w, R.drawable.ic_flip_to_front, enabled = floating) { arrange(key, front = true) }
                        RibbonButton(stringResource(R.string.words_send_to_back), w, R.drawable.ic_flip_to_back, enabled = floating) { arrange(key, front = false) }
                        RibbonButton(stringResource(R.string.words_rotate), w, R.drawable.ic_rotate_right, enabled = floating) { rotating = key }
                        if (img?.group != null) RibbonButton(stringResource(R.string.words_ungroup), w, R.drawable.ic_group_objects, enabled = floating) { ungroup(key) }
                        else RibbonButton(stringResource(if (grouping == key) R.string.words_tap_another else R.string.words_group), w, R.drawable.ic_group_objects, enabled = floating, on = grouping == key) {
                            grouping = if (grouping == key) null else key.also { message = context.getString(R.string.words_tap_other_to_group) }
                        }
                        RibbonMenu(stringResource(R.string.words_align), w, R.drawable.ic_align_horizontal_center, enabled = floating, preset = false, choices =
                            listOf("left" to stringResource(R.string.words_align_left), "center" to stringResource(R.string.words_align_centre), "right" to stringResource(R.string.words_align_right), "top" to stringResource(R.string.words_align_top), "middle" to stringResource(R.string.words_align_middle), "bottom" to stringResource(R.string.words_align_bottom))
                                .map { (how, label) -> MenuChoice(label) { align(key, how) } })
                    }, {
                        RibbonButton(stringResource(R.string.words_turn_left), w, R.drawable.ic_rotate_left, enabled = hasBytes && editable) { transform(-90, false) }
                        RibbonButton(stringResource(R.string.words_turn_right), w, R.drawable.ic_rotate_right, enabled = hasBytes && editable) { transform(90, false) }
                        RibbonButton(stringResource(R.string.words_flip), w, R.drawable.ic_flip, enabled = hasBytes && editable) { transform(0, true) }
                    }, {
                        RibbonButton(stringResource(R.string.words_replace), w, R.drawable.ic_gallery, enabled = editable) { replacePicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                        RibbonButton(stringResource(R.string.words_save_as), w, R.drawable.ic_save, enabled = hasBytes) { savePicture(img!!) }
                        RibbonButton(stringResource(R.string.common_delete), w, R.drawable.ic_delete) { state.removeTextRange(androidx.compose.ui.text.TextRange(at, at + 1)) }
                    })
                    }
                }
                WriterTab.Table -> {
                    val (at, _) = selectedPicture ?: (0 to "")
                    // Rows, columns, merging and splitting for both kinds; looks, shading and the ⋯ page write Word's XML (Word only for now).
                    val table = selectedPicture?.second?.let { pictures[it] }?.table
                    val word = kind == OfficeKind.Docx
                    // The cell the tools act on: the one tapped, else the first.
                    fun here(): TableCellRef? = selectedPicture?.second?.let { key -> selectedCell?.takeIf { it.key == key } ?: TableCellRef(key, 0, 0) }
                    // The cell being typed in is put into the table first, and the change made to the table as it then is
                    // (made to the table from before it, the typing was lost); its key is read again after.
                    fun change(said: String? = null, f: (DocTable, TableCellRef) -> DocTable?) {
                        commitCell()
                        val ref = here() ?: return
                        val img = pictures[ref.key] ?: return
                        f(img.table ?: return, ref)?.let { changeTable(ref.key, img, it) } ?: run { message = said }
                    }
                    val from = mergeFrom?.takeIf { it.key == selectedPicture?.second }
                    if (from != null) listOf({
                        val n = table?.let { t -> TableEdit.cellsBetween(t, from.row to from.cell, (mergeTo ?: from).let { it.row to it.cell }).size } ?: 0
                        Text(if (mergeTo == null) stringResource(R.string.words_tap_last_cell) else pluralStringResource(R.plurals.words_n_cells, n, n), color = w, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        RibbonButton(stringResource(R.string.words_merge), w, R.drawable.ic_cell_merge, enabled = mergeTo != null && n > 1) {
                            val to = mergeTo!!
                            change(context.getString(R.string.words_cells_cannot_merge)) { t, _ -> TableEdit.merge(t, from.row to from.cell, to.row to to.cell) }
                            selectedCell = from; mergeFrom = null; mergeTo = null
                        }
                        RibbonButton(stringResource(R.string.common_cancel), w, R.drawable.ic_close) { mergeFrom = null; mergeTo = null }
                    }) else listOf({
                        // The table's numbers as a chart under it (asked 2026-10-14), its look chosen in the chart's own dialog.
                        RibbonButton(stringResource(R.string.words_make_chart), w, R.drawable.ic_bar_chart, enabled = table != null) {
                            commitCell()
                            val key = selectedPicture?.second
                            val made = key?.let { pictures[it]?.table }?.let { OfficeCharts.fromTable(it) }
                            if (made == null) message = context.getString(R.string.words_no_numbers_to_chart) else { chartUnder = key; charting = null to made }
                        }
                    }, {
                        // Everyday tools on the ribbon, the rest behind ⋯ (asked 2026-09-28); three to a page, as a phone fits them.
                        RibbonButton(stringResource(R.string.words_row_above), w, R.drawable.ic_add_row_above, enabled = table != null) { change { t, c -> TableEdit.insertRow(t, c.row, c.cell, below = false) } }
                        RibbonButton(stringResource(R.string.words_row_below), w, R.drawable.ic_add_row_below, enabled = table != null) { change { t, c -> TableEdit.insertRow(t, c.row, c.cell, below = true) } }
                        RibbonButton(stringResource(R.string.words_merge), w, R.drawable.ic_cell_merge, enabled = table != null) { commitCell(); mergeFrom = here(); mergeTo = null }
                    }, {
                        RibbonButton(stringResource(R.string.words_column_left), w, R.drawable.ic_add_column_left, enabled = table != null) { change { t, c -> TableEdit.insertColumn(t, c.row, c.cell, right = false) } }
                        RibbonButton(stringResource(R.string.words_column_right), w, R.drawable.ic_add_column_right, enabled = table != null) { change { t, c -> TableEdit.insertColumn(t, c.row, c.cell, right = true) } }
                        RibbonButton(stringResource(R.string.words_split), w, R.drawable.ic_call_split, enabled = table != null) { commitCell(); tableDialog = "split" }
                    }, {
                        RibbonButton(stringResource(R.string.words_delete_row), w, R.drawable.ic_delete_row, enabled = table != null && table.rows.size > 1) { change(context.getString(R.string.words_last_row_stays)) { t, c -> TableEdit.deleteRow(t, c.row, c.cell) }; selectedCell = null }
                        RibbonButton(stringResource(R.string.words_delete_column), w, R.drawable.ic_delete_column, enabled = table != null && TableEdit.gridCount(table) > 1) { change(context.getString(R.string.words_column_cannot_delete)) { t, c -> TableEdit.deleteColumn(t, c.row, c.cell) }; selectedCell = null }
                        RibbonButton(stringResource(R.string.words_delete_table), w, R.drawable.ic_delete, enabled = selectedPicture != null) {
                            commitCell()
                            state.removeTextRange(androidx.compose.ui.text.TextRange(at, at + 1)); tab = WriterTab.Home
                        }
                    }, {
                        TableEdit.LOOKS.take(4).forEach { look -> LookChip(look) { if (word) change { t, _ -> TableEdit.withLook(t, look) } else message = context.getString(R.string.words_table_styles_word_only) } }
                    }, {
                        Swatches(w, R.drawable.ic_format_color_fill, stringResource(R.string.words_shading), CELL_FILLS) { color ->
                            if (word) change { t, c -> TableEdit.withCells(t, TableEdit.wholeCell(t, c.row, c.cell)) { it.copy(props = TableEdit.withShading(it.props, color?.let { k -> (k.toArgb() and 0xFFFFFF) })) } }
                            else message = context.getString(R.string.words_shading_word_only)
                        }
                        RibbonButton(stringResource(R.string.common_more), w, R.drawable.ic_more_vert, enabled = table != null && word) { commitCell(); tableDialog = "props" }
                    })
                }
                // Word's Design: the page's background — its colour and a box round it.
                WriterTab.Design -> listOf({
                    // A paper's standards in one window (asked 15 Oct): APA 7's paper and run-in headings, the citation style.
                    RibbonButton(stringResource(R.string.words_standards), w, R.drawable.ic_school, enabled = loaded) { standards = true }
                    // Word's Document Formatting gallery, Colours and Fonts (its theme): windows of pictures of the choices.
                    RibbonButton(stringResource(R.string.words_style_set), w, R.drawable.ic_style, enabled = loaded) { openGallery(DesignGallery.StyleSets) }
                    RibbonButton(stringResource(R.string.words_colours), w, R.drawable.ic_format_color_text, enabled = loaded && kind == OfficeKind.Docx) { openGallery(DesignGallery.Colours) }
                    RibbonButton(stringResource(R.string.words_fonts), w, R.drawable.ic_style, enabled = loaded) { openGallery(DesignGallery.Fonts) }
                }, {
                    // Word's Paragraph Spacing: the Normal style's (every paragraph that has it, headings keeping their own space).
                    RibbonMenu(stringResource(R.string.words_paragraph_spacing), w, R.drawable.ic_format_line_spacing, enabled = loaded, choices = SPACING_SETS.map { (name, after, line) ->
                        val label = stringResource(name)
                        MenuChoice(label, detail = stringResource(R.string.words_spacing_detail, plain(after), plain(line))) {
                            editStyle(documentStyles.paragraphStyles()[0], StyleChange(line = line, before = 0f, after = after))
                            message = context.getString(R.string.words_spacing_set_done, label)
                        } })
                }, {
                    // Word's Watermark: its words, one of your own, or none.
                    run {
                        val now = documentStyles.section.watermark
                        val picture = documentStyles.section.watermarkPicture != null
                        RibbonMenu(stringResource(R.string.words_watermark), w, R.drawable.ic_branding_watermark, enabled = loaded, on = now != null || picture, choices =
                            Watermark.PRESETS.map { t -> MenuChoice(t, checked = t == now) { setWatermark(t) } } +
                            MenuChoice(stringResource(R.string.words_custom_watermark), opens = true) { watermarking = true } +
                            MenuChoice(stringResource(R.string.words_picture_watermark), checked = picture, opens = true, icon = R.drawable.ic_gallery) { pickWatermark.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } +
                            listOfNotNull(MenuChoice(stringResource(R.string.words_remove_watermark), opens = true, icon = R.drawable.ic_delete) { setWatermark(null) }.takeIf { now != null || picture }))
                    }
                    Swatches(w, R.drawable.ic_format_color_fill, stringResource(R.string.words_page_colour), PAGE_COLORS.map { Color(0xFF000000 or it.toLong()) }) { c ->
                        setDesign(c?.let { it.toArgb() and 0xFFFFFF }, sheet.border?.line)
                    }
                    RibbonButton(stringResource(R.string.words_page_borders), w, R.drawable.ic_border_outer, enabled = loaded, on = sheet.border != null) { pageBordering = true }
                })
                WriterTab.Draw -> listOf({
                    // On the page (asked 2026-09-30): drawn over the page as it is, put there in front of the text.
                    RibbonButton(stringResource(R.string.words_draw_on_page), w, R.drawable.ic_draw, enabled = loaded) { drawOnPage() }
                    // A drawing box: drawn in, then put in front of the text at its paragraph.
                    RibbonButton(stringResource(R.string.words_drawing_box), w, R.drawable.ic_ink_pen, enabled = loaded) { drawing = Drawing(boxPt = sheet.textWidth.coerceAtMost(450f)) }
                })
                WriterTab.Layout -> listOf({
                    // Word's Layout: margins, orientation, size (one page for all three), then breaks.
                    RibbonButton(stringResource(R.string.words_margins), w, R.drawable.ic_margin, enabled = loaded) { pagePage = true }
                    RibbonButton(stringResource(R.string.words_portrait), w, R.drawable.ic_crop_portrait, enabled = loaded, on = !PageSetup.landscape(sheet)) {
                        if (PageSetup.landscape(sheet)) setPage(sheet.copy(width = sheet.height, height = sheet.width)) }
                    RibbonButton(stringResource(R.string.words_landscape), w, R.drawable.ic_crop_landscape, enabled = loaded, on = PageSetup.landscape(sheet)) {
                        if (!PageSetup.landscape(sheet)) setPage(sheet.copy(width = sheet.height, height = sheet.width)) }
                }, {
                    // Word's Layout › Paragraph: the indents and the space before and after, as number boxes (asked: as OnlyOffice's)
                    val here = rulerAt
                    fun set(f: (ParaLook) -> ParaLook) { val (at, look) = here ?: return; setSpacing(at, look, f(look)) }
                    val step = if (WriterUnits.name == "in") 7.2f else 0.25f * WriterUnits.pt // a tenth of an inch, a quarter centimetre
                    @Composable fun box(label: String, shown: String, down: () -> Unit, up: () -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = w, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp))
                        Tool(R.drawable.ic_remove, stringResource(R.string.words_less_of, label), w, enabled = here != null && loaded) { down() }
                        Text(shown, color = w, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.widthIn(min = 44.dp))
                        Tool(R.drawable.ic_add, stringResource(R.string.words_more_of, label), w, enabled = here != null && loaded) { up() }
                    }
                    val look = here?.second ?: ParaLook()
                    box(stringResource(R.string.words_left), WriterUnits.text(look.left) + " " + WriterUnits.name, { set { it.copy(left = maxOf(0f, it.left - step)) } }, { set { it.copy(left = it.left + step) } })
                    box(stringResource(R.string.words_right), WriterUnits.text(look.right) + " " + WriterUnits.name, { set { it.copy(right = maxOf(0f, it.right - step)) } }, { set { it.copy(right = it.right + step) } })
                    box(stringResource(R.string.words_before), "${Math.round(look.before)} pt", { set { it.copy(before = maxOf(0f, it.before - 6f)) } }, { set { it.copy(before = it.before + 6f) } })
                    box(stringResource(R.string.words_after), "${Math.round(look.after)} pt", { set { it.copy(after = maxOf(0f, it.after - 6f)) } }, { set { it.copy(after = it.after + 6f) } })
                }, {
                    RibbonButton(stringResource(R.string.words_size), w, R.drawable.ic_fit_page, enabled = loaded) { pagePage = true }
                    // Columns: the pages show them (the editor keeps one column of text).
                    RibbonMenu(if (sheet.columns > 1) stringResource(R.string.words_columns_n, sheet.columns) else stringResource(R.string.words_columns), w, R.drawable.ic_view_column, enabled = loaded, title = stringResource(R.string.words_columns), choices =
                        listOf(1 to stringResource(R.string.words_one), 2 to stringResource(R.string.words_two), 3 to stringResource(R.string.words_three)).map { (n, label) -> MenuChoice(label, checked = n == sheet.columns) {
                            if (n != sheet.columns || sectioned()) { setColumns(n); if (n > 1) message = context.getString(R.string.words_columns_done, label) } } })
                }, {
                    RibbonButton(stringResource(R.string.words_page_break), w, R.drawable.ic_insert_page_break, enabled = loaded) { insertPageBreak(false) }
                    RibbonButton(stringResource(R.string.words_blank_page), w, R.drawable.ic_note_add, enabled = loaded) { insertPageBreak(true) }
                    // Layout › Breaks (Phase 2): a column break; section breaks — a new page, or on down the same one.
                    RibbonMenu(stringResource(R.string.words_breaks), w, R.drawable.ic_insert_page_break, enabled = loaded, preset = false, choices = listOf(
                        MenuChoice(stringResource(R.string.words_column_break)) { insertPageBreak(false, column = true); WriterLog.i("column break") },
                        MenuChoice(stringResource(R.string.words_section_break_next_page_menu)) { insertPageBreak(false, section = "nextPage") },
                        MenuChoice(stringResource(R.string.words_section_break_continuous_menu)) { insertPageBreak(false, section = "continuous") }))
                }, {
                    // Word's Line Numbers: none, continuous, restart each page, restart each section (Word only); the pages show them.
                    run {
                        val now = documentStyles.section.lineNumbers?.restart
                        // a tablet or phone: cards on the pages' strip, as Insert › Page numbers (the user, 18 Oct); a desktop: its menu
                        if (!OfficeUi.top) RibbonButton(stringResource(R.string.words_line_numbers), w, R.drawable.ic_format_list_numbered, enabled = loaded, on = now != null) { openGallery(DesignGallery.LineNumbers) }
                        else RibbonMenu(stringResource(R.string.words_line_numbers), w, R.drawable.ic_format_list_numbered, enabled = loaded, on = now != null, choices =
                            (listOf(null to stringResource(R.string.common_none), "continuous" to stringResource(R.string.words_line_numbers_continuous), "newPage" to stringResource(R.string.words_line_numbers_each_page)) + if (kind == OfficeKind.Docx) listOf("newSection" to stringResource(R.string.words_line_numbers_each_section)) else emptyList())
                                .map { (mode, label) -> MenuChoice(label, checked = mode == now) { if (mode != now) setLineNumbers(mode) } })
                    }
                    // Word's Hyphenation: automatic on or off for the whole document (the pages break words at lines' ends), and by
                    // hand an optional hyphen at the cursor (Word's Ctrl + -): the word breaks there only at a line's end
                    val now = documentStyles.hyphenate
                    RibbonButton(stringResource(if (now) R.string.words_hyphenation_on_button else R.string.words_hyphenation_off_button), w, R.drawable.ic_hyphenation, enabled = loaded, on = now) { setHyphenation(!now) }
                    RibbonButton(stringResource(R.string.words_optional_hyphen), w, R.drawable.ic_hyphenation, enabled = loaded) { typeIn("\u00AD"); message = context.getString(R.string.words_optional_hyphen_msg) }
                    RibbonButton(stringResource(R.string.words_hyphenation_options), w, R.drawable.ic_tune, enabled = loaded) { hyphenOptions = true }
                })
                WriterTab.References -> listOf({
                    val has = remember(meta.paras) { Contents.find(meta, kind) != null }
                    RibbonButton(stringResource(if (has) R.string.words_update_contents else R.string.words_table_of_contents), w, R.drawable.ic_toc, enabled = loaded) { insertContents() }
                    // how many heading levels it lists (Word's Show levels): a tap makes it, or makes it again, with that many
                    val levels = remember(meta) { Contents.levelsOf(meta, kind) }
                    Text(stringResource(R.string.words_levels), color = w.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 4.dp))
                    for (n in 1..4) RibbonButton("$n", w, enabled = loaded, on = has && levels == n) { insertContents(n) }
                }, {
                    // Word's Footnotes group.
                    RibbonButton(stringResource(R.string.words_insert_footnote), w, R.drawable.ic_superscript, enabled = loaded) { insertNote(false) }
                    RibbonButton(stringResource(R.string.words_insert_endnote), w, R.drawable.ic_last_page, enabled = loaded) { insertNote(true) }
                    RibbonButton(stringResource(R.string.words_note_numbers), w, R.drawable.ic_tune, enabled = loaded) { noteNumbering = true }
                }, {
                    // Word's Citations & Bibliography, APA 7.
                    RibbonButton(stringResource(R.string.words_insert_citation), w, R.drawable.ic_format_quote, enabled = loaded) { citing = true }
                    RibbonButton(stringResource(R.string.words_sources), w, R.drawable.ic_checklist, enabled = loaded) { sourcesOpen = true }
                }, {
                    // With Claude (tetra-sources skill): ask in the Claude app, then paste its answer — the research's references at once.
                    RibbonButton(stringResource(R.string.words_ask_claude), w, R.drawable.ic_share, enabled = loaded) { askClaudeForSources() }
                    RibbonButton(stringResource(R.string.words_paste_sources), w, R.drawable.ic_content_paste, enabled = loaded) { pasteSources() }
                }, {
                    val listed = remember(meta.paras) { Citations.find(meta, kind) != null }
                    RibbonButton(stringResource(if (listed) R.string.words_update_references else R.string.words_references_list), w, R.drawable.ic_format_list_bulleted, enabled = loaded) { if (listed) updateCitations() else insertReferenceList() }
                    // References › Style (Word's): the citations and the list written again in it — a strip of the styles, each
                    // with its example, in the ribbon's place (asked 16 Oct: it was a list of names)
                    RibbonButton(meta.citeStyle.label, w, R.drawable.ic_format_quote, enabled = loaded) { citeStyling = true }
                }, {
                    // Captions and their numbers (Word's Insert Caption and F9).
                    RibbonButton(stringResource(R.string.words_caption), w, R.drawable.ic_label, enabled = loaded) { captionOpen = true }
                    RibbonButton(stringResource(R.string.words_cross_reference), w, R.drawable.ic_link, enabled = loaded && kind == OfficeKind.Docx) { crossRefOpen = true }
                }, {
                    RibbonButton(stringResource(R.string.words_update_fields), w, R.drawable.ic_refresh, enabled = loaded) { updateCitations() }
                })
                WriterTab.Review -> listOf({
                    // Word's Proofing group: spelling marked as one types (on or off), and the next word marked.
                    RibbonButton(stringResource(R.string.words_spelling), w, R.drawable.ic_spellcheck, enabled = loaded, on = spelling) {
                        spelling = !spelling; prefs.edit().putBoolean("spelling", spelling).apply()
                        message = context.getString(if (spelling) R.string.words_spelling_on_msg else R.string.words_spelling_off_msg)
                    }
                    Tool(R.drawable.ic_chevron_right, stringResource(R.string.words_next_spelling_mistake), w, enabled = loaded && spelling) { nextMisspelled() }
                    if (suggestOn) RibbonButton(stringResource(R.string.words_suggest), w, R.drawable.ic_sparkle, enabled = loaded && suggestBusy == null) { suggestAsking = true }
                    // Word's Speech group: Read aloud from the cursor, a paragraph at a time (Greek or English each).
                    RibbonButton(stringResource(if (reader.speaking) R.string.words_stop_reading else R.string.words_read_aloud), w, R.drawable.ic_volume_up, enabled = loaded, on = reader.speaking) {
                        if (reader.speaking) reader.stop() else {
                            val doc = current()
                            // Equations said in words, in their paragraph's language (one of only maths: the document's).
                            val greekDoc = doc.paras.count { languageOf(it.text).language == "el" } * 2 > doc.paras.count { it.text.isNotBlank() }
                            readingParas = doc.paras.map { p -> if (p.table != null) p.countedText() else {
                                val greek = if (p.runs.any { it.image == null && it.text.any(Char::isLetter) }) languageOf(p.text).language == "el" else greekDoc
                                p.runs.joinToString("") { r -> r.image?.math?.let { " " + OfficeMath.spoken(it, greek) + " " } ?: r.text.replace(OBJECT_MARK.toString(), "") } } }
                            val sel = state.selection.min
                            val from = WriterBridge.paragraphsAt(state.annotatedString.text, state.toRichTextDocument(), sel, sel).minOrNull() ?: 0
                            if (!reader.read(readingParas, from)) message = context.getString(R.string.words_no_tts)
                        }
                    }
                }, {
                    // Word's Tracking group: recording on or off (Word files: an OpenDocument's changes are not kept yet).
                    RibbonButton(stringResource(R.string.words_track_changes), w, R.drawable.ic_track_changes, enabled = loaded && kind == OfficeKind.Docx, on = tracking) {
                        if (tracking) setTracking(false)
                        else if (prefs.getString("author", "").isNullOrBlank()) askingName = true
                        else setTracking(true)
                    }
                }, {
                    // Word's Changes group: go through them, each accepted or rejected, or all at once.
                    Tool(R.drawable.ic_chevron_left, stringResource(R.string.words_previous_change), w, enabled = loaded) { goChange(false) }
                    Tool(R.drawable.ic_chevron_right, stringResource(R.string.words_next_change), w, enabled = loaded) { goChange(true) }
                    RibbonButton(stringResource(R.string.words_accept), w, R.drawable.ic_check, enabled = loaded && hereChange != null) { decideChanges(true) }
                    RibbonButton(stringResource(R.string.words_reject), w, R.drawable.ic_close, enabled = loaded && hereChange != null) { decideChanges(false) }
                }, {
                    val any = changesShown.isNotEmpty()
                    RibbonButton(stringResource(R.string.words_accept_all), w, R.drawable.ic_done_all, enabled = loaded && any) { decideChanges(true, all = true) }
                    RibbonButton(stringResource(R.string.words_reject_all), w, R.drawable.ic_remove_done, enabled = loaded && any) { decideChanges(false, all = true) }
                }, {
                    RibbonButton(stringResource(R.string.words_comments), w, R.drawable.ic_comment, enabled = loaded, on = paneOn) {
                        if (tablet) { panelHidden = !panelHidden; if (meta.comments.isEmpty()) message = context.getString(R.string.words_no_comments) } else commentsOpen = true
                    }
                    // Word's Comments group (asked: reply and resolve from Review): a new one, the one before or after the cursor, and the one at it
                    RibbonButton(stringResource(R.string.words_new_comment), w, R.drawable.ic_comment, enabled = loaded) { commenting = true }
                    fun goComment(on: Boolean) {
                        val at = state.selection.min
                        val starts = commentsShown.map { it.second.first }.distinct().sorted()
                        (if (on) starts.firstOrNull { it > at } ?: starts.firstOrNull() else starts.lastOrNull { it < at } ?: starts.lastOrNull())
                            ?.let { state.selection = androidx.compose.ui.text.TextRange(it); goText = it to System.nanoTime() } ?: run { message = context.getString(R.string.words_no_comments) }
                    }
                    Tool(R.drawable.ic_chevron_left, stringResource(R.string.words_previous_comment), w, enabled = loaded && commentsShown.isNotEmpty()) { goComment(false) }
                    Tool(R.drawable.ic_chevron_right, stringResource(R.string.words_next_comment), w, enabled = loaded && commentsShown.isNotEmpty()) { goComment(true) }
                }, {
                    val c = hereComment
                    RibbonButton(stringResource(R.string.words_reply), w, R.drawable.ic_comment, enabled = loaded && c != null) { replyTo = c }
                    RibbonButton(stringResource(if (c?.done == true) R.string.words_reopen else R.string.words_resolve), w, R.drawable.ic_check, enabled = loaded && c != null) { c?.let(::resolveComment) }
                    RibbonButton(stringResource(R.string.words_delete_comment), w, R.drawable.ic_delete, enabled = loaded && c != null) { c?.let { deleteComment(it.id) } }
                })
                WriterTab.View -> listOf({
                    RibbonButton(stringResource(R.string.words_navigation), w, R.drawable.ic_view_list, enabled = loaded, on = navOpen) { navOpen = !navOpen }
                    // The comments: a pane beside the page on a tablet (shown or hidden), a sidebar on a phone.
                    RibbonButton(stringResource(R.string.words_comments), w, R.drawable.ic_comment, enabled = loaded, on = paneOn) {
                        if (tablet) { panelHidden = !panelHidden; if (meta.comments.isEmpty()) message = context.getString(R.string.words_no_comments) } else commentsOpen = true
                    }
                }, {
                    if (printPhone) {
                        // The page: its width on the screen, larger (two fingers do the same).
                        Tool(R.drawable.ic_remove, stringResource(R.string.words_zoom_out), w, enabled = pageZoom > 1f) { pageZoom = (pageZoom - 0.25f).coerceAtLeast(1f) }
                        Text("${(pageZoom * 100).roundToInt()}%", color = w, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 4.dp))
                        Tool(R.drawable.ic_add, stringResource(R.string.words_zoom_in), w, enabled = pageZoom < 5f) { pageZoom = (pageZoom + 0.25f).coerceAtMost(5f) }
                    } else {
                    Tool(R.drawable.ic_remove, stringResource(R.string.words_zoom_out), w, enabled = zoom > 70) { zoom -= 10 }
                    Text("$zoom%", color = w, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 4.dp))
                    Tool(R.drawable.ic_add, stringResource(R.string.words_zoom_in), w, enabled = zoom < 200) { zoom += 10 }
                    }
                    // A phone: the page as it prints (Print layout), or the text to the screen's width (Mobile view).
                    RibbonButton(stringResource(R.string.words_reading_mode), w, R.drawable.ic_menu_book) { commitCell(); reading = true }
                    // the pages (the eye): as wide as the screen, or two side by side
                    RibbonButton(stringResource(R.string.words_fit_width), w, R.drawable.ic_fit_page, on = fitWidth) { fitWidth = !fitWidth; commitCell(); viewing = true }
                    RibbonButton(stringResource(R.string.words_two_pages), w, R.drawable.ic_view_column, on = pagesAcross == 2) { pagesAcross = if (pagesAcross == 2) 1 else 2; commitCell(); viewing = true }
                    if (!tablet) RibbonButton(stringResource(R.string.words_mobile_view), w, R.drawable.ic_wrap_text, on = mobileView) {
                        mobileView = !mobileView
                        context.getSharedPreferences("writer", Context.MODE_PRIVATE).edit().putBoolean("mobileView", mobileView).apply()
                    }
                }, {
                    // Centimetres or inches for the rulers and every length (APA's margins are given in inches).
                    Text(stringResource(R.string.words_units), color = w.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp))
                    RibbonButton(stringResource(R.string.words_unit_cm), w, on = !WriterUnits.inches) { WriterUnits.set(context, false) }
                    RibbonButton(stringResource(R.string.words_unit_inches), w, on = WriterUnits.inches) { WriterUnits.set(context, true) }
                })
            }
            // A tablet has room for a whole tab at once: its tools in one line, close together, centred (asked 2026-09-27:
            // spread over the width they were too far apart). A phone swipes them page by page.
            // a desktop (asked 16 Oct): the groups on rows, a group kept whole
            if (ribbonRows()) RibbonRows(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) { pages.forEachIndexed { i, page -> if (i > 0) RibbonDivider(w); page() } }
            else if (LocalConfiguration.current.screenWidthDp >= 700) Row(
                // from the left and as tall in every tab, as Slides' and Tables' (the tablet walk, 18 Oct)
                rememberScrollState().let { sc -> Modifier.fillMaxWidth().scrollShade(sc).horizontalScroll(sc) }.padding(horizontal = 8.dp, vertical = 4.dp).heightIn(min = if (OfficeUi.compact) 40.dp else 48.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically,
            ) { RibbonTools { pages.forEachIndexed { i, page -> if (i > 0) RibbonDivider(w); page() } } }
            else androidx.compose.runtime.key(tab) {
                // The tab's tools in one row that scrolls freely, its groups apart by a thin line, faded where there is more
                // (16 Oct: as Tables' and Slides' rows, in place of pages turned one at a time with dots); a new tab from its start
                val sc = remember(tab) { androidx.compose.foundation.ScrollState(0) }
                Row(Modifier.fillMaxWidth().scrollShade(sc).horizontalScroll(sc).padding(horizontal = 4.dp, vertical = 2.dp).heightIn(min = if (OfficeUi.compact) 40.dp else 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    RibbonTools { pages.forEachIndexed { i, group -> if (i > 0) RibbonDivider(w); group() } }
                }
            }
            } }
        } }
        // A link inside the document at the cursor (a contents' entry, a cross-reference): a chip that goes to its bookmark (asked 16 Oct: clickable contents)
        val inside = state.selectedLinkUrl?.takeIf { it.startsWith("#") && loaded }
        // the bookmarked paragraph and its words, found once a link is reached (the document is built for it, not every frame)
        val goal = remember(inside) { inside?.let { url -> current().paras.withIndex().firstOrNull { (_, p) -> p.keep?.parts?.firstOrNull { it.first == BOOKMARKS }?.second?.split(' ')?.contains(url.substring(1)) == true } } }
        val target = goal?.index
        if (target != null && message == null) Surface(color = Color.Black, contentColor = Color.White, shape = CircleShape,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 60.dp, start = 16.dp, end = 16.dp).clip(CircleShape).clickable {
                WriterLog.i("went to a bookmark (paragraph $target)")
                WriterBridge.paragraphStarts(state.annotatedString.text, state.toRichTextDocument()).getOrNull(target)?.let { state.selection = androidx.compose.ui.text.TextRange(it); goText = it to System.nanoTime() }
            }) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_link), contentDescription = null, modifier = Modifier.size(18.dp).padding(end = 6.dp))
                Text(stringResource(R.string.words_go_to, goal.value.text.take(40).trim()), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        OfficeMessage(message, top = 60.dp) { message = null }
        cropping?.let { bitmap ->
            BackHandler { cropping = null }
            // Photos' crop, with straight cuts (90°) on at first (asked 2026-09-28); off, the corners move freely and the
            // picture is straightened as Photos does. ponytail: the cut picture becomes a new one — Word's own crop
            // (a:srcRect) would keep the rest.
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                CornerEditor(bitmap, DocumentQuad.fullFrame(), DocumentQuad.fullFrame(), stringResource(R.string.words_reset), straightOption = true, done = { quad ->
                    cropping = null
                    val (at, key) = selectedPicture ?: return@CornerEditor
                    val img = pictures[key] ?: return@CornerEditor
                    if (quad == DocumentQuad.fullFrame()) return@CornerEditor
                    val cut = runCatching { cropScan(bitmap, quad, 2400f) }.getOrNull() ?: return@CornerEditor
                    val png = img.extension == "png" || img.part?.endsWith(".png", ignoreCase = true) == true
                    val bytes = java.io.ByteArrayOutputStream().also { cut.compress(if (png) android.graphics.Bitmap.CompressFormat.PNG else android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
                    // As big on the page, piece for piece, as it was.
                    changePicture(key, DocImage(key, null, img.widthPt * cut.width / bitmap.width, img.heightPt * cut.height / bitmap.height, null, bytes, if (png) "png" else "jpg", PictureXml.wrapOf(img)))
                    state.selection = androidx.compose.ui.text.TextRange(at, at + 1)
                }, cancel = { cropping = null })
            }
        }
    }

    // The dialogs, in a function of their own: WriterScreen's one method is at the JVM's 64 KB limit.
    @Composable fun Dialogs() {
    if (controlNew) ContentControlDialog({ controlNew = false }) { k, title, items, format -> controlNew = false; insertControl(k, title, items, format) }
    if (pageBordering) PageBorderDialog(sheet.border, { pageBordering = false }) { b, sides ->
        pageBordering = false; setDesign(sheet.color, b, sides); WriterLog.i("page border: ${b?.let { "${it.style} ${it.width}pt #%06X".format(it.color) } ?: "none"} on $sides")
    }
    if (standards) StandardsDialog(meta.citeStyle, { standards = false }, paper = { standards = false; apaPage = true },
        runIn = { level -> standards = false; apaRunIn(level) }, citeStyle = { c -> standards = false; setCiteStyle(c) }, styles = CiteStyle.entries.filter { it != CiteStyle.IEEE || kind == OfficeKind.Docx })
    shapeOptions?.let { key -> pictures[key]?.let { img -> ShapePicture.of(img)?.let { sp ->
        ShapeOptionsDialog(ShapeOptions(sp.fill, sp.line, sp.lineWidth, img.widthPt, img.heightPt, Arrange.rotationOf(img), PictureXml.altOf(img), sp.dash, sp.shadow), { shapeOptions = null }) { o ->
            shapeOptions = null
            var next = PictureXml.shapeLook(img, o.fill, o.line, o.lineWidth, o.dash, o.shadow) ?: img
            if (o.widthPt != img.widthPt || o.heightPt != img.heightPt) next = PictureXml.resized(next, o.widthPt, o.heightPt)
            if (o.degrees != Arrange.rotationOf(img)) next = Arrange.rotated(next, o.degrees) ?: next
            if (o.alt != PictureXml.altOf(img)) next = PictureXml.withAlt(next, o.alt) ?: next
            changePicture(key, next)
            WriterLog.i("shape options $key: fill ${o.fill?.let { "%08X".format(it) }} line ${o.line?.let { "%06X".format(it and 0xFFFFFF) }} ${o.lineWidth}pt ${o.dash ?: "solid"}${if (o.shadow) " shadow" else ""} ${o.widthPt.toInt()}×${o.heightPt.toInt()} ${o.degrees}°")
        } } } }
    rotating?.let { key -> RotateDialog(pictures[key]?.let { Arrange.rotationOf(it) } ?: 0f, { rotating = null }) { deg -> rotating = null; rotate(key, deg) } }
    equationOpen?.let { (key, math) -> EquationDialog(math, { equationOpen = null }) { m -> equationOpen = null; putEquation(key, m) } }
    if (naming) {
        var typed by remember { mutableStateOf(file?.nameWithoutExtension?.let { "$it (2)" } ?: "") }
        var into by remember { mutableStateOf(file?.parentFile ?: TextEditorStore(context).saveDirectory()) }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(stringResource(R.string.words_save_as)) },
            text = {
                SaveAsWhere(into, { into = it }) {
                    OutlinedTextField(typed, { typed = it }, singleLine = true, label = { Text(stringResource(R.string.words_file_name)) }, suffix = { Text(".${kind.extension}") })
                    // A new document chooses its kind; a file stays what it is (its own package is what is written).
                    if (file == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OfficeKind.entries.forEach { k -> RibbonButton(if (k == OfficeKind.Docx) "Word (.docx)" else "OpenDocument (.odt)", MaterialTheme.colorScheme.onSurface, on = kind == k) { kind = k } }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { WriterLog.i("Words: save as into ${into.name}"); saveNew(typed, into) }) { Text(stringResource(R.string.common_save)) } },
            dismissButton = { TextButton(onClick = { naming = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    if (renaming) {
        var typed by remember { mutableStateOf(file?.nameWithoutExtension.orEmpty()) }
        AlertDialog(onDismissRequest = { renaming = false }, title = { Text(stringResource(R.string.common_rename)) },
            text = { OutlinedTextField(typed, { typed = it }, singleLine = true, label = { Text(stringResource(R.string.words_file_name)) }, suffix = { Text(".${kind.extension}") }) },
            confirmButton = { TextButton(onClick = { rename(typed) }) { Text(stringResource(R.string.common_rename)) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.common_cancel)) } })
    }
    if (leaving) AlertDialog(
        onDismissRequest = { leaving = false },
        title = { Text(stringResource(R.string.words_save_name_question, name)) },
        text = { Text(stringResource(if (meta.blocking.isNotEmpty() && file != null) R.string.words_unsaved_copy else R.string.words_unsaved)) },
        confirmButton = { TextButton(onClick = { leaving = false; if (file == null) naming = true else save { exit() } }) { Text(stringResource(R.string.common_save)) } },
        dismissButton = {
            Row {
                TextButton(onClick = { leaving = false }) { Text(stringResource(R.string.common_cancel)) }
                TextButton(onClick = { leaving = false; draftFile(context, draftKey, kind).delete(); exit() }) { Text(stringResource(R.string.words_dont_save), color = Color(0xFFE35A4F)) }
            }
        },
    )
    if (apaPage) ApaPaperDialog(remember(meta) { current().paras.any { it.text.isNotBlank() } },
        java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.LONG)), { apaPage = false }) { clean, title, abstract, head ->
        apaPage = false; applyApa(clean, title, abstract, head)
    }
    if (citing) CitationDialog(meta.sources, pages = kind == OfficeKind.Docx, multiple = kind == OfficeKind.Docx, { citing = false }, { sourceNew = true }) { list, page, hide ->
        citing = false; insertCitation(list, page, hide)
    }
    citeChanging?.let { (c, r) ->
        val (tags, page) = Citations.parse(c)
        CitationDialog(meta.sources, pages = kind == OfficeKind.Docx, multiple = kind == OfficeKind.Docx, { citeChanging = null }, { sourceNew = true }, tags, page, Citations.hidden(c)) { list, newPage, hide ->
            citeChanging = null
            state.selection = androidx.compose.ui.text.TextRange(r.first, r.last + 1) // the old citation, replaced by the new
            insertCitation(list, newPage, hide)
            WriterLog.i("citation changed: $c -> ${list.map { it.tag }} p.$newPage")
        }
    }
    if (crossRefOpen) current().let { doc -> CrossReferenceDialog(captions(doc).map { it to doc.paras[it].text.trim() }, { crossRefOpen = false }) { target, how -> crossRefOpen = false; insertCrossReference(target, how) } }
    if (captionOpen) CaptionDialog({ captionOpen = false }) { label, text -> captionOpen = false; insertCaption(label, text) }
    if (sourcesOpen) SourcesDialog(meta.sources, remember(meta) { Citations.usedTags(current()) }, { sourcesOpen = false }, { s -> if (s == null) sourceNew = true else sourceEdit = s }, { sourcesOpen = false; pickSources.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
        { sourcesOpen = false; pasteSources() }, { askClaudeForSources() }) { deleteSource(it) }
    if (sourceNew || sourceEdit != null) SourceDialog(sourceEdit, { sourceNew = false; sourceEdit = null }) { s -> sourceNew = false; sourceEdit = null; saveSource(s) }
    dropCapPage?.let { (at, now) ->
        val first = current().paras.getOrNull(at)?.runs?.firstOrNull { it.image == null && it.text.isNotEmpty() }
        DropCapDialog(now, kind == OfficeKind.Odt, documentStyles.fontName(first?.font), first?.color, { dropCapPage = null }) { d, font, color ->
            dropCapPage = null
            // Automatic chosen where the letters had a colour: taken off (-1).
            setDropCap(at, d, font.takeIf { it != documentStyles.fontName(first?.font) }, color ?: if (first?.color != null) -1 else null)
        }
    }
    if (watermarking) {
        var text by remember { mutableStateOf(documentStyles.section.watermark ?: "") }
        AlertDialog(
            onDismissRequest = { watermarking = false },
            title = { Text(stringResource(R.string.words_watermark)) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text(stringResource(R.string.words_text_across_pages)) }) },
            confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { watermarking = false; setWatermark(text.trim()) }) { Text(stringResource(R.string.common_ok)) } },
            dismissButton = { TextButton(onClick = { watermarking = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    if (linking) {
        val selection = state.selection
        LinkDialog(selection.collapsed && !state.isLink, state.selectedLinkUrl, state.selectedLinkText, state.isLink, { linking = false }, { linking = false; state.removeLink() }) { text, url ->
            linking = false
            when {
                state.isLink -> state.updateLink(url)
                !selection.collapsed -> state.addLinkToSelection(url)
                else -> state.addLink(text, url)
            }
        }
    }
    selectedPicture?.let { (_, key) -> pictures[key] }?.let { img ->
        val key = selectedPicture!!.second
        img.table?.let { table ->
            val ref = selectedCell?.takeIf { it.key == key } ?: TableCellRef(key, 0, 0)
            when (tableDialog) {
                "split" -> TableSplitDialog(TableEdit.size(table, ref.row, ref.cell).let { (w, h) -> if (w * h > 1) w to h else 2 to 1 }, { tableDialog = null }) { cols, rows ->
                    tableDialog = null
                    TableEdit.split(table, ref.row, ref.cell, cols, rows)?.let { changeTable(key, img, it) } ?: run { message = context.getString(R.string.words_cell_cannot_split) }
                }
                "props" -> TablePropertiesDialog(table, ref.row, ref.cell, { tableDialog = null }) { tableDialog = null; changeTable(key, img, it) }
            }
        }
        if (sizing) PictureSizeDialog(img.widthPt, img.heightPt, picturePainter(img, packageParts), { sizing = false }) { w, h -> sizing = false; changePicture(key, PictureXml.resized(img, w, h)) }
        if (wrapping) {
            val now = PictureXml.wrapOf(img)
            AlertDialog(
                onDismissRequest = { wrapping = false },
                title = { Text(stringResource(R.string.words_wrap_text)) },
                text = {
                    Column {
                        PictureWrap.entries.forEach { wrap ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { wrapping = false; if (wrap != now) changePicture(key, PictureXml.withWrap(img, wrap, kind)) }) {
                                RadioButton(wrap == now, null)
                                Text(wrap.label, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                        // ponytail: floating pictures are drawn in line; showing them in place comes with dragging.
                        Text(stringResource(R.string.words_wrap_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    }
                },
                confirmButton = { TextButton(onClick = { wrapping = false }) { Text(stringResource(R.string.common_close)) } },
            )
        }
    }
    if (hyphenOptions) HyphenOptionsDialog(remember { HyphenOptions.read(OfficeFormats.unzip(original ?: OfficeFormats.write(OfficeDoc(emptyList()), kind)), kind) }, { hyphenOptions = false }) { hyphenOptions = false; setHyphenOptions(it) }
    if (noteNumbering) NoteNumbersDialog(meta.footFormat, meta.endFormat, kind == OfficeKind.Docx, { noteNumbering = false }) { foot, end -> noteNumbering = false; setNoteFormats(foot, end) }
    noteEditing?.let { (n, number) -> NoteDialog(n, number, { noteEditing = null }, { saveNote(n, it) }, { saveNote(n, null) }) }
    if (suggestAsking) SuggestDialog(!state.selection.collapsed, { suggestAsking = false }) { ask -> suggestAsking = false; askSuggestions(ask) }
    if (askingName) ReviewerNameDialog(prefs.getString("author", "").orEmpty(), { askingName = false }) { name ->
        prefs.edit().putString("author", name).apply()
        askingName = false; setTracking(true)
    }
    if (commenting) NewCommentDialog(remember { context.getSharedPreferences("writer", Context.MODE_PRIVATE).getString("author", "") ?: "" }, { commenting = false }) { author, text ->
        commenting = false
        context.getSharedPreferences("writer", Context.MODE_PRIVATE).edit().putString("author", author).apply()
        addComment(author, text)
    }
    editingComment?.let { c ->
        NewCommentDialog(c.author, { editingComment = null }, title = stringResource(R.string.words_edit_comment), start = c.text, confirm = stringResource(R.string.common_done)) { _, text -> editingComment = null; editComment(c, text) }
    }
    replyTo?.let { parent ->
        NewCommentDialog(remember { context.getSharedPreferences("writer", Context.MODE_PRIVATE).getString("author", "") ?: "" }, { replyTo = null }, title = stringResource(R.string.words_reply_to, parent.author.ifBlank { stringResource(R.string.words_comment_lower) })) { author, text ->
            replyTo = null
            context.getSharedPreferences("writer", Context.MODE_PRIVATE).edit().putString("author", author).apply()
            replyComment(parent, author, text)
        }
    }
    if (commentsOpen) {
        val first = commentsShown.groupBy { it.first }.mapValues { (_, spans) -> spans.minOf { it.second.first } }
        CommentSidebar(first.entries.sortedBy { it.value }.map { it.key }, hereComment, { commentsOpen = false },
            commentActions { c -> commentsOpen = false; first[c]?.let { state.selection = androidx.compose.ui.text.TextRange(it) } })
    }
    positioning?.let { (page, key) ->
        PositionDialog(page, key, { positioning = null }) { x, y ->
            val img = pictures[key] ?: return@PositionDialog
            changePicture(key, PictureXml.placed(img, x, y, kind))
            position(key) // the page again, the text around it where it now is
        }
    }
    if (drawingSignature) DrawDialog(200f, { drawingSignature = false }, boxAspect = 0.4f) { png, _, _, _, _ ->
        drawingSignature = false
        val redo = redoSignature; redoSignature = null
        runCatching { signatureFile.writeBytes(png) }.onSuccess {
            // Redraw: this one becomes the new signature, where it is, as wide as it was
            val old = redo?.let { pictures[it] }
            if (old != null) { val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }; android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size, o)
                change { changePicture(old.key, DocImage(old.key, null, old.widthPt, old.widthPt * o.outHeight / o.outWidth.coerceAtLeast(1), null, png, "png", PictureXml.wrapOf(old), onPage = old.onPage)) } }
            else insertSignature()
        }
    }
    drawing?.let { d ->
        DrawDialog(d.boxPt, { drawing = null }, d.pages, d.start, d.boxAspect, d.strokes) { bytes, wPt, hPt, place, k ->
            drawing = null
            val old = d.edit?.let { pictures[it] }
            if (old == null) putDrawing(bytes, wPt, hPt, place, d.anchors.getOrNull(k))
            else changePicture(old.key, DocImage(old.key, null, wPt, hPt, null, bytes, "png", PictureXml.wrapOf(old) ?: PictureWrap.InFront, onPage = place ?: old.onPage))
        }
    }
    if (shapesPage) ShapesDialog(kind == OfficeKind.Odt, { shapesPage = false }) { shape, fill, line ->
        shapesPage = false
        // Word's size for it ([shapeSize]); its picture tab sizes, wraps and places it after
        val (w, h) = shapeSize(shape)
        val img = DocImage("s${System.nanoTime()}", null, w, h, PictureXml.newShape(shape, kind, fill, line, 1f, w, h))
        pictures[img.key] = img
        val at = placePicture(img)
        state.selection = androidx.compose.ui.text.TextRange(at, at + 1)
        tab = WriterTab.Picture
    }
    /** A new text box (WordArt: [art], its words' colour) at the cursor, the text around it (Square); Position places it. */
    boxing?.let { (key, words) ->
        // a shape's own words: named as such, and Done (it is there already)
        val shape = key?.let { pictures[it] }?.takeIf { it.box == null && ShapePicture.of(it) != null }
        TextBoxDialog(words, { boxing = null }, title = shape?.let { stringResource(R.string.words_words_in_shape) }, confirm = if (key != null) stringResource(R.string.common_done) else null) { text, _, _, _ ->
            boxing = null
            val lines = text.split('\n')
            val old = key?.let { pictures[it] }
            // a shape's first words centred, as the file has them
            if (old?.raw != null) changePicture(old.key, old.with(raw = PictureXml.boxText(old.raw, kind, lines),
                box = PictureXml.boxParas(old, lines).let { ps -> if (old.box == null) ps.map { it.copy(align = DocAlign.Center) } else ps }))
            else putTextBox(lines, null)
        }
    }
    charting?.let { (key, start) ->
        ChartDialog(start, key != null, { charting = null; chartUnder = null }) { ch ->
            charting = null
            // From a table: a paragraph of its own right under it (a table is a paragraph "t<index>"), the table kept.
            chartUnder?.let { tk ->
                chartUnder = null
                change {
                    val cur = current()
                    val at = (tk.removePrefix("t").toIntOrNull() ?: (cur.paras.size - 1)) + 1
                    val w = minOf(396f, sheet.textWidth)
                    val img = DocImage("c${System.nanoTime()}", null, w, w * 230f / 396f, chart = ch)
                    pictures[img.key] = img
                    meta = cur.copy(paras = cur.paras.take(at) + DocPara(listOf(DocRun(OBJECT_MARK.toString(), image = img))) + cur.paras.drop(at))
                    WriterLog.i("chart from table $tk: ${ch.series.size} series × ${ch.categories.size} categories, under paragraph ${at - 1}")
                    dirty = true; reshow(); message = context.getString(R.string.words_chart_from_table)
                }
                return@ChartDialog
            }
            val old = key?.let { pictures[it] }
            if (old != null) {
                // Written anew (Word's chart part, LibreOffice's object) where it was, as big, wrapped and placed as it was.
                // ponytail: a file's chart keeps only what Writer reads of it (its data, kind, colours, title), and one
                // placed from its paragraph goes to the paragraph's top left.
                val place = PictureXml.placeOf(old)?.takeIf { it.fromH == "page" && it.fromV == "page" && it.x != null && it.y != null }?.let { it.x!! to it.y!! } ?: old.onPage
                changePicture(old.key, DocImage(old.key, null, old.widthPt, old.heightPt, null, wrap = PictureXml.wrapOf(old)?.takeIf { it != PictureWrap.InLine }, onPage = place, chart = ch))
                return@ChartDialog
            }
            // New: Word's size (5½ × 3.2 inches, or the text's width), in line with the text as Word puts it.
            val w = minOf(396f, sheet.textWidth)
            val img = DocImage("c${System.nanoTime()}", null, w, w * 230f / 396f, chart = ch)
            pictures[img.key] = img
            val at = placePicture(img)
            state.selection = androidx.compose.ui.text.TextRange(at, at + 1)
            tab = WriterTab.Picture
        }
    }
    artEditing?.let { (key, words) ->
        val old = pictures[key]
        val (rgb, fx) = TextWarp.art(old?.raw) ?: (0 to TextEffects())
        TextBoxDialog(words, { artEditing = null }, art = true, glowAllowed = kind == OfficeKind.Docx, confirm = stringResource(R.string.common_done), start = Triple(0xFF000000.toInt() or rgb, fx, TextWarp.of(old?.raw))) { text, color, effects, bend ->
            artEditing = null
            val raw = old?.raw ?: return@TextBoxDialog
            val lines = text.split('\n')
            val fx2 = if (kind == OfficeKind.Docx) effects else effects.copy(glow = null)
            val odt = kind == OfficeKind.Odt
            val written = PictureXml.boxText(raw, kind, lines, color, fx2).let { x -> if (bend != null) TextWarp.applied(x, bend, odt) else TextWarp.removed(x, odt) }
            changePicture(old.key, old.with(raw = written, box = lines.map { DocPara(listOf(DocRun(it, bold = true, sizePt = PictureXml.ART_PT, color = color and 0xFFFFFF,
                font = (if (odt) fx2.odt() else fx2.docx()).ifEmpty { null }))) }))
        }
    }
    if (officeSettings) OfficeSettingsDialog { officeSettings = false }
    if (citeStyling) CiteStyleStrip(meta.citeStyle, CiteStyle.entries.filter { it != CiteStyle.IEEE || kind == OfficeKind.Docx }, { citeStyling = false }) { c -> citeStyling = false; setCiteStyle(c) }
    if (wordArt) TextBoxDialog("", { wordArt = false }, art = true, glowAllowed = kind == OfficeKind.Docx) { text, color, fx, bend -> wordArt = false; putTextBox(text.split('\n'), color, fx, bend) }
    if (pagePage) PageSetupDialog(sheet, { pagePage = false }) { pagePage = false; setPage(it) }
    if (symbolPage) SymbolDialog({ symbolPage = false }) { symbolPage = false; typeIn(it) }
    if (datePage) DateDialog({ datePage = false }) { datePage = false; typeIn(it) }
    if (headerPage) HeaderFooterDialog(HeaderFooter.of(documentStyles.section, documentStyles.evenAndOdd), HeaderFooter.rich(documentStyles.section), { headerPage = false }) { headerPage = false; setHeaderFooter(it) }
    if (stylesPage) StylesDialog(documentStyles, hereStyle, meta.basePt, { stylesPage = false }, { editingStyle = it }) { stylesPage = false; applyStyle(it) }
    editingStyle?.let { entry ->
        StyleDialog(entry, documentStyles.look(DocPara(emptyList(), heading = entry.heading, keep = entry.id?.let { DocKeep(style = it) })), meta.basePt,
            { editingStyle = null }) { change -> editingStyle = null; editStyle(entry, change) }
    }
    bordersPage?.let { (at, look) ->
        BordersDialog(look, { bordersPage = null }, line = { bordersPage = null; horizontalLine() }) { box -> bordersPage = null; setBorders(at, box) }
    }
    paragraphPage?.let { (at, look) ->
        // its style, to be edited from here (Word's Modify Style)
        val entry = remember(at) { at.minOrNull()?.let { current().paras.getOrNull(it) }?.let { documentStyles.styleOf(it) } }
        ParagraphDialog(look, { paragraphPage = null }, style = entry?.name, editStyle = entry?.let { e -> { paragraphPage = null; editingStyle = e } }) { now -> paragraphPage = null; setSpacing(at, look, now) }
    }
    if (showVersions) {
        // History (the user, 2026-10-14): every version as pages to look at; one kept becomes the document, the rest go.
        val entries = remember(reload, dirty, showVersions) {
            (sessionVersions(context, uri ?: "new").map { it to true } + (file?.let { versions(context, it) }.orEmpty()).map { it to false })
                .sortedByDescending { it.first.name.substringBefore('.').toLongOrNull() ?: it.first.lastModified() }
        }
        HistoryDialog(entries, kind, { showVersions = false }) { chosen ->
            showVersions = false
            restoreSession(chosen)
            // The others, older and newer, deleted; the one kept stays as the only version.
            entries.map { it.first }.filter { it != chosen }.forEach { it.delete() }
            WriterLog.i("history: kept ${chosen.name}, ${entries.size - 1} others deleted")
        }
    }
    if (details) AlertDialog(
        onDismissRequest = { details = false },
        title = { Text(name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (kind == OfficeKind.Docx) R.string.words_kind_docx else R.string.words_kind_odt), style = MaterialTheme.typography.bodyMedium)
                val all = remember { TextCounts.of(current().paras.joinToString("\n") { it.countedText() }) }
                Text("${wordsCount(all.words)} · ${charactersCount(all.chars)} ${stringResource(R.string.words_with_spaces, all.withSpaces)} · ${paragraphsCount(current().paras.count { it.text.isNotBlank() })}", style = MaterialTheme.typography.bodyMedium)
                // The selection's counts too, as Word's Word Count does (asked 2026-09-30: the phone's counter is here).
                val sel = state.selection
                if (!sel.collapsed) counted(state.annotatedString.text, sel.min, sel.max).let {
                    Text(stringResource(R.string.words_selected_counts, "${wordsCount(it.words)} · ${charactersCount(it.chars)} ${stringResource(R.string.words_with_spaces, it.withSpaces)}"), style = MaterialTheme.typography.bodyMedium)
                }
                if (meta.blocking.isNotEmpty()) Text(stringResource(R.string.words_does_not_show, OfficeDoc.notesText(LocalContext.current, meta.blocking)), style = MaterialTheme.typography.bodyMedium)
                if (meta.simplified.isNotEmpty()) Text(stringResource(R.string.words_simplified_on_saving, OfficeDoc.notesText(LocalContext.current, meta.simplified)), style = MaterialTheme.typography.bodyMedium)
                if (meta.unsupported.isEmpty()) Text(stringResource(R.string.words_keeps_everything), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.words_page_kept), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { details = false }) { Text(stringResource(R.string.common_ok)) } },
    )
    }
    Dialogs()
}

/** "1 word", "1,234 words" (and characters, paragraphs). */
@Composable internal fun wordsCount(n: Int) = pluralStringResource(R.plurals.words_count_words, n, n)
@Composable internal fun charactersCount(n: Int) = pluralStringResource(R.plurals.words_count_characters, n, n)
@Composable internal fun paragraphsCount(n: Int) = pluralStringResource(R.plurals.words_count_paragraphs, n, n)

/** Word's counts: words, characters without spaces, characters with spaces (not paragraph marks or pictures). */
internal class TextCounts(val words: Int, val chars: Int, val withSpaces: Int) {
    companion object {
        private val WORD = Regex("""[\p{L}\p{N}]+(['’-][\p{L}\p{N}]+)*""")
        fun of(text: String) = TextCounts(WORD.findAll(text).count(),
            text.count { !it.isWhitespace() && it !in PICTURES }, text.count { it != '\n' && it != LINE_BREAK && it !in PICTURES })
        private val PICTURES = charArrayOf(OBJECT_MARK, EDITOR_PICTURE_CHAR, PICTURE_SPOT)
        /** The live count's words (Word's status bar): "1,234 words", or the selection's "12 of 1,234 words". */
        @Composable fun label(all: Int, chosen: Int?) = if (chosen == null) wordsCount(all) else stringResource(R.string.words_count_of, chosen, wordsCount(all))
    }
}
/** A paragraph's letters as Word counts them: a table's are its cells'. */
internal fun DocPara.countedText(): String = table?.rows?.joinToString("\n") { r -> r.cells.joinToString("\n") { c -> c.paras.joinToString("\n") { it.countedText() } } } ?: text

/**
 * Drawn [k] times its laid-out size from its top left, and taking that much room — the phone's page in Print layout
 * (laid out whole, shown at the screen's width or larger). Touches and the cursor follow the drawing.
 */
private fun Modifier.scaledBy(k: Float) = this.layout { m, c ->
    val p = m.measure(androidx.compose.ui.unit.Constraints())
    layout((p.width * k).roundToInt(), (p.height * k).roundToInt()) {
        p.placeWithLayer(0, 0) { scaleX = k; scaleY = k; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f) }
    }
}

/**
 * Two fingers zoom between [min] and [max] (the point between them stays put, the scrolls following); one finger is
 * left to the scrolling and the editor underneath. [now] reads the zoom as it is.
 */
private fun Modifier.pinchZoom(now: () -> Float, min: Float, max: Float, side: androidx.compose.foundation.ScrollState, down: androidx.compose.foundation.ScrollState,
    set: (Float) -> Unit) = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            if (e.changes.count { it.pressed } < 2) continue
            val z = now()
            val next = (z * e.calculateZoom()).coerceIn(min, max)
            val ratio = next / z
            if (ratio != 1f) {
                val centre = e.calculateCentroid()
                set(next)
                side.dispatchRawDelta((side.value + centre.x) * ratio - centre.x - side.value)
                down.dispatchRawDelta((down.value + centre.y) * ratio - centre.y - down.value)
            }
            e.changes.forEach { it.consume() }
        }
    }
}

/**
 * A new text box ([art] null) or WordArt ([art] its colour, ARGB) as Words makes them (Insert › Text box, WordArt; the
 * PDF's Insert › WordArt too), in [kind]'s words, wrapped as [wrap].
 */
internal fun newTextBoxImage(kind: OfficeKind, lines: List<String>, art: Int?, effects: TextEffects = TextEffects(), warp: TextWarp? = null, wrap: PictureWrap = PictureWrap.Square): DocImage {
    val (w, h) = (if (art == null) 180f to (lines.size * 16f + 12f).coerceAtLeast(36f) // 2½ inches wide, as tall as its lines
        else android.graphics.Paint().apply { textSize = PictureXml.ART_PT; typeface = android.graphics.Typeface.DEFAULT_BOLD }
            .let { p -> lines.maxOf { p.measureText(it) } * 1.1f + 16f to lines.size * PictureXml.ART_PT * 1.25f + 10f }) // as big as its words
        // Bent: room for the bend (a circle as tall as it is wide; an arch or a wave a good part of its width).
        .let { (w0, h0) -> when (warp) { null -> w0 to h0; TextWarp.Circle -> maxOf(w0 * 0.6f, 144f).let { it to it }; else -> w0 to maxOf(h0, w0 * 0.35f) } }
    val fx = if (kind == OfficeKind.Docx) effects else effects.copy(glow = null) // LibreOffice has no glow on letters
    val box = lines.map { DocPara(listOf(if (art == null) DocRun(it) else DocRun(it, bold = true, sizePt = PictureXml.ART_PT, color = art and 0xFFFFFF,
        font = (if (kind == OfficeKind.Docx) fx.docx() else fx.odt()).ifEmpty { null }))) }
    if (fx.any) WriterLog.i("WordArt effects: $fx")
    return PictureXml.withWrap(DocImage("b${System.nanoTime()}", null, w, h, PictureXml.newTextBox(kind, w, h, lines, art, fx, warp), box = box), wrap, kind)
}

/** Insert › Signature › From a picture of it (Words' and the PDF's): a photo of a signature on paper made ink — its white see-through — into [into] as a PNG. */
internal fun signatureFromPhoto(context: android.content.Context, picked: android.net.Uri, into: File) {
    // a photo of a signature on paper: its white made see-through, so it sits on the page as ink
    val bmp = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(context.contentResolver, picked)) { dec, info, _ ->
        val k = minOf(1f, 900f / maxOf(info.size.width, info.size.height)); dec.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1))
        dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE }.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
    val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
    for (i in px.indices) { val c = px[i]; val l = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        px[i] = if (l > 170) 0 else (c and 0xFFFFFF) or (((255 - l) * 255 / 255).coerceIn(80, 255) shl 24) }
    bmp.setPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
    into.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
}

/** WordArt's outline: black round coloured letters, grey round black ones; its glow gold, blue round gold letters. */
internal fun artOutline(color: Int) = if (color and 0xFFFFFF == 0) 0x7F7F7F else 0x000000
internal fun artGlow(color: Int) = if (color and 0xFFFFFF == 0xFFC000) 0x4472C4 else 0xFFC000

/** A text box's words, a paragraph a line (Insert › Text box, Picture › Edit text); with [art], WordArt's and its colour. */
@Composable
internal fun TextBoxDialog(initial: String, dismiss: () -> Unit, art: Boolean = false, glowAllowed: Boolean = true, title: String? = null, confirm: String? = null,
    /** a WordArt's colour (ARGB), effects and bend as they are, to be changed */ start: Triple<Int, TextEffects, TextWarp?>? = null,
    done: (String, Int, TextEffects, TextWarp?) -> Unit) {
    var bend by remember { mutableStateOf(start?.third) }
    // A gallery's WordArt: "Your text here" chosen, so the first letter typed replaces it (as Word does).
    var field by remember { mutableStateOf(TextFieldValue(initial, androidx.compose.ui.text.TextRange(if (initial == "Your text here") 0 else initial.length, initial.length))) }
    val text = field.text
    var outlined by remember { mutableStateOf(start?.second?.outline != null) }; var shadowed by remember { mutableStateOf(start?.second?.shadow == true) }; var glowing by remember { mutableStateOf(start?.second?.glow != null) }
    // Word's WordArt colours: blue, orange, grey, gold, green, black (its theme's accents).
    val colors = listOf(0xFF4472C4, 0xFFED7D31, 0xFFA5A5A5, 0xFFFFC000, 0xFF70AD47, 0xFF000000).map { it.toInt() }
    var color by remember { mutableIntStateOf(start?.first ?: colors[0]) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title ?: if (art) "WordArt" else if (initial.isEmpty()) stringResource(R.string.words_text_box) else stringResource(R.string.words_edit_text)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(field, { field = it }, minLines = if (art) 1 else 3, maxLines = 10, placeholder = { Text(stringResource(R.string.words_its_words)) }, modifier = Modifier.fillMaxWidth().focusRequester(focus),
                textStyle = if (art) MaterialTheme.typography.headlineSmall.copy(color = Color(color), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    // The effects as near as the field can show them: a glow or a shadow; outlined letters hollow.
                    shadow = if (glowing) androidx.compose.ui.graphics.Shadow(Color(0xFF000000 or artGlow(color).toLong()), androidx.compose.ui.geometry.Offset.Zero, 12f)
                        else if (shadowed) androidx.compose.ui.graphics.Shadow(Color(0x99000000), androidx.compose.ui.geometry.Offset(3f, 3f), 3f) else null,
                    drawStyle = if (outlined) androidx.compose.ui.graphics.drawscope.Stroke(2f) else null) else androidx.compose.material3.LocalTextStyle.current)
            if (art) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                colors.forEach { c -> Box(Modifier.size(32.dp).clip(CircleShape).background(Color(c)).border(3.dp, if (c == color) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape).clickable { color = c }) }
            }
            // Word's text effects (Format › Text Effects): an outline, a shadow, a glow (Word files only: LibreOffice has none).
            if (art) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Choice(stringResource(R.string.words_outline), outlined) { outlined = !outlined }
                Choice(stringResource(R.string.words_shadow), shadowed) { shadowed = !shadowed }
                if (glowAllowed) Choice(stringResource(R.string.words_glow), glowing) { glowing = !glowing }
            }
            // Word's Transform (LibreOffice's Fontwork): the words along a bend.
            if (art) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Choice(stringResource(R.string.words_straight), bend == null) { bend = null }
                TextWarp.entries.forEach { b -> Choice(warpName(b), bend == b) { bend = b } }
            }
        } },
        confirmButton = { TextButton(onClick = { done(text, color, TextEffects(if (outlined) artOutline(color) else null, shadowed, if (glowing) artGlow(color) else null), bend) }, enabled = text.isNotBlank()) { Text(confirm ?: if (initial.isEmpty()) stringResource(R.string.words_insert) else stringResource(R.string.common_done)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } })
}

/** A picture of the document, from the file's own parts or a new picture's bytes. A chart or shape is a grey box. */
@Composable
internal fun picturePainter(img: DocImage, parts: Map<String, ByteArray>): Painter {
    val bitmap by produceState<ImageBitmap?>(null, img, parts) {
        val bytes = img.bytes ?: img.part?.let { parts[it] }
        value = bytes?.let { withContext(Dispatchers.IO) { runCatching { OfficePages.croppedPicture(img, it, 1400)?.asImageBitmap() }.getOrNull() } }
    }
    // a film's or a sound's icon Android cannot draw (Word's are EMF): a dark frame, a speaker
    val film = PictureXml.video(img)
    val stand = remember(film) { film?.takeIf(OfficeVideo::audio)?.let { OfficeVideo.speaker().let { b -> BitmapPainter(BitmapFactory.decodeByteArray(b, 0, b.size).asImageBitmap()) } } }
    return bitmap?.let { BitmapPainter(it) } ?: stand ?: ColorPainter(Color(if (film != null) 0xFF202124 else 0xFFE3E3E3))
}

/** A picture's bytes as a bitmap no wider or taller than [max] pixels (a phone photo is far bigger than the page needs). */
internal fun decodePicture(bytes: ByteArray, max: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

/**
 * A picture from the phone, ready to go into a document: a JPEG or PNG no bigger than 2400 pixels is kept as it is;
 * anything bigger (or of another kind) is made a JPEG of that size. As wide as it is (96 dpi), at most the page's width.
 */
/** A picture control's placeholder (Word's grey picture of a picture), 1½ inches square. */
private fun placeholderPicture(): DocImage {
    val b = android.graphics.Bitmap.createBitmap(216, 216, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    c.drawColor(0xFFF2F2F2.toInt())
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFA6A6A6.toInt() }
    c.drawCircle(140f, 76f, 16f, p) // the sun
    c.drawPath(android.graphics.Path().apply { moveTo(40f, 160f); lineTo(90f, 96f); lineTo(124f, 136f); lineTo(146f, 112f); lineTo(176f, 160f); close() }, p) // the hills
    p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 6f
    c.drawRect(30f, 46f, 186f, 170f, p)
    val bytes = java.io.ByteArrayOutputStream().also { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    return DocImage("n" + java.util.UUID.randomUUID(), null, 108f, 108f, null, bytes, "png")
}
internal fun preparePicture(context: Context, uri: Uri): DocImage {
    val raw = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
    val mime = bounds.outMimeType ?: ""
    val keep = (mime == "image/jpeg" || mime == "image/png") && maxOf(bounds.outWidth, bounds.outHeight) <= 2400
    val bytes: ByteArray; val w: Int; val h: Int; val ext: String
    if (keep) { bytes = raw; w = bounds.outWidth; h = bounds.outHeight; ext = if (mime == "image/png") "png" else "jpg" }
    else {
        val b = decodePicture(raw, 2400) ?: error("Not a picture.")
        val scale = (2400f / maxOf(b.width, b.height)).coerceAtMost(1f)
        val s = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(b, (b.width * scale).toInt(), (b.height * scale).toInt(), true) else b
        bytes = java.io.ByteArrayOutputStream().also { s.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        w = s.width; h = s.height; ext = "jpg"
    }
    val widthPt = (w * 0.75f).coerceAtMost(450f)
    return DocImage("n" + java.util.UUID.randomUUID(), null, widthPt, widthPt * h / w, null, bytes, ext)
}

/** Word's familiar table picker: tap or drag to choose the rectangle from its top-left corner. */
/**
 * Insert › Table, one picker for Words and Slides (the tablet walk, 18 Oct): the size dragged out on a grid of cells — on a strip in
 * the ribbon's place on a tablet or phone (✓ puts it in), in a window on a desktop.
 */
@Composable internal fun TableSizeButton(w: Color, accent: Color, enabled: Boolean = true, insert: (Int, Int) -> Unit) {
    var dialog by remember { mutableStateOf(false) }
    val strip = officeStrip(stringResource(R.string.words_table), ok = { s -> (s.picked as? Pair<*, *>)?.let { (r, c) -> insert(r as Int, c as Int) } }) { s ->
        val (r, c) = (s.picked as? Pair<*, *>)?.let { (a, b) -> a as Int to b as Int } ?: (0 to 0)
        TableSizeGrid(4, 8, r, c, cell = 18.dp, fill = accent) { rr, cc -> s.picked = rr to cc }
        // a fixed width: the line is centred, and a label growing would move the grid under the finger
        Text(if (r > 0) "$r × $c" else stringResource(R.string.words_drag_across_cells), color = w, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp).width(160.dp))
    }
    RibbonButton(stringResource(R.string.words_table), w, R.drawable.ic_table, enabled = enabled) { strip?.invoke() ?: run { dialog = true } }
    if (dialog) {
        var rows by remember { mutableIntStateOf(2) }
        var columns by remember { mutableIntStateOf(2) }
        AlertDialog(onDismissRequest = { dialog = false }, title = { Text(stringResource(R.string.words_insert_table)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TableSizeGrid(5, 5, rows, columns, fill = accent) { r, c -> rows = r; columns = c }
                    Text("$rows × $columns", style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.words_table_grid_hint), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { dialog = false; insert(rows, columns) }) { Text(stringResource(R.string.words_insert)) } },
            dismissButton = { TextButton(onClick = { dialog = false }) { Text(stringResource(R.string.common_cancel)) } })
    }
}

@Composable private fun TableSizeGrid(maxRows: Int, maxColumns: Int, rows: Int, columns: Int, cell: Dp = 40.dp, fill: Color = WORD_BLUE, select: (Int, Int) -> Unit) {
    BoxWithConstraints(
        Modifier.width(cell * maxColumns).height(cell * maxRows).pointerInput(maxRows, maxColumns) {
            fun choose(point: Offset) {
                val column = (point.x / (size.width / maxColumns.toFloat())).toInt().coerceIn(0, maxColumns - 1)
                val row = (point.y / (size.height / maxRows.toFloat())).toInt().coerceIn(0, maxRows - 1)
                select(row + 1, column + 1)
            }
            detectDragGestures(onDragStart = ::choose, onDrag = { change, _ -> change.consume(); choose(change.position) })
        },
    ) {
        Column {
            repeat(maxRows) { row ->
                Row {
                    repeat(maxColumns) { column ->
                        Box(
                            Modifier.size(cell).padding(2.dp).clip(RoundedCornerShape(4.dp))
                                .background(if (row < rows && column < columns) fill else MaterialTheme.colorScheme.surfaceVariant)
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                .clickable { select(row + 1, column + 1) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A picture dragged in from another app (split screen, a tablet's or a desktop's windows — asked 16 Oct): [onDrop] gets
 * its address, read with the permission Android grants for the drop.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun Modifier.dropPictures(enabled: Boolean, onDrop: (android.net.Uri) -> Unit): Modifier = if (!enabled) this else composed {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = remember(context) { generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.firstNotNullOfOrNull { it as? android.app.Activity } }
    val latest = androidx.compose.runtime.rememberUpdatedState(onDrop)
    val target = remember { object : androidx.compose.ui.draganddrop.DragAndDropTarget {
        override fun onDrop(event: androidx.compose.ui.draganddrop.DragAndDropEvent): Boolean {
            val e = event.toAndroidDragEvent(); val clip = e.clipData ?: return false
            val uri = (0 until clip.itemCount).firstNotNullOfOrNull { clip.getItemAt(it).uri } ?: return false
            activity?.requestDragAndDropPermissions(e)
            WriterLog.i("a picture dropped in")
            latest.value(uri); return true
        }
    } }
    dragAndDropTarget(shouldStartDragAndDrop = { ev -> ev.mimeTypes().any { it.startsWith("image/") } }, target = target)
}

/** Whether the file is saved, for a tablet's top bar (asked 16 Oct: the room there for what is going on). */
@Composable internal fun saveState(saving: Boolean, unsaved: Boolean) = stringResource(if (saving) R.string.words_saving else if (unsaved) R.string.words_not_saved else R.string.words_saved)

/** A tablet's top bar: what is going on with the file, between its name and its tools. */
@Composable internal fun TopInfo(parts: List<String>, tint: Color, modifier: Modifier = Modifier) =
    Text(parts.joinToString(" · "), color = tint.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = modifier.padding(horizontal = 8.dp))

/** [PictureXml.kind]'s word (an English name, as an ID) as shown. */
@Composable internal fun objectKindName(kind: String) = when (kind) {
    "Signature" -> stringResource(R.string.words_signature); "Table" -> stringResource(R.string.words_table); "Equation" -> stringResource(R.string.words_equation)
    "Video" -> stringResource(R.string.words_video); "Audio" -> stringResource(R.string.slides_audio); "Chart" -> stringResource(R.string.words_chart); "SmartArt" -> "SmartArt"; "WordArt" -> "WordArt"
    "Text box" -> stringResource(R.string.words_text_box); "Embedded object" -> stringResource(R.string.words_embedded_object); "Shape" -> stringResource(R.string.words_shape)
    "Image" -> stringResource(R.string.words_tab_image); else -> kind
}

/** A thin line between a ribbon's groups of tools (Words', Slides' and Tables'). */
@Composable internal fun RibbonDivider(tint: Color) = Box(Modifier.layoutId(RIBBON_DIVIDER).padding(horizontal = 6.dp).width(1.dp).height(24.dp).background(tint.copy(alpha = 0.3f)))

/** Design › Paragraph spacing: Word's sets — space after (pt) and line spacing of the Normal style. */
private val SPACING_SETS = listOf(Triple(R.string.words_spacing_none, 0f, 1f), Triple(R.string.words_spacing_compact, 4f, 1f), Triple(R.string.words_spacing_set_tight, 6f, 1.15f),
    Triple(R.string.words_spacing_open, 10f, 1.15f), Triple(R.string.words_spacing_relaxed, 6f, 1.5f), Triple(R.string.words_spacing_double, 8f, 2f))

/** Design › Page colour: Word's light theme tints, and white (a page coloured white is not the same as none in Word). */
internal val PAGE_COLORS = listOf(0xFFF2CC, 0xE2EFDA, 0xDDEBF7, 0xFCE4D6, 0xEDEDED, 0xFFFFFF)

/** A named tool of the ribbon (Save, Heading 1…). */
/** A sideways-scrolling row with more beyond an edge: that edge shaded, so it shows there is more (the user, 2026-10-14). */
/**
 * The zoomed page scrolls to [cursor] (the styled layout's) whenever the editor asks to be brought into view — not to
 * the rectangle the editor gives, which a one-frame unstyled layout puts elsewhere. [cursor] is at the page's scale: the
 * scroll takes it as it is, and the page's own (unscaled) place sent it 1/0.44 too far down on a phone, then the cursor's
 * follow brought it back (13 Nov: "selecting text… the view is moving around a lot").
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.followCursor(cursor: () -> androidx.compose.ui.geometry.Rect?) = this.bringIntoViewResponder(object : androidx.compose.foundation.relocation.BringIntoViewResponder {
    override fun calculateRectForParent(localRect: androidx.compose.ui.geometry.Rect) = (cursor() ?: localRect).also { r ->
        if (kotlin.math.abs(r.top - localRect.top) > 200f) WriterLog.i("editor asked to show (${localRect.left.roundToInt()}, ${localRect.top.roundToInt()}), the cursor is at (${r.left.roundToInt()}, ${r.top.roundToInt()}): the cursor's kept")
    }
    override suspend fun bringChildIntoView(localRect: () -> androidx.compose.ui.geometry.Rect?) {}
})

/**
 * Selection handles drawn on the page itself (so they scale and scroll with it): a drop with a sharp nose at each end of the
 * chosen letters, one under the cursor; dragged, they move that end (asked 14 Oct: Compose's sat far off on the phone's
 * scaled page, and the user wanted a pointier nose). [at]: where the text starts in this box; [scale]: the page's.
 */
@Composable
private fun PageHandles(state: RichTextState, layout: TextLayoutResult, at: Offset, scale: Float, where: (Offset) -> String, edge: (Float) -> Float, dragging: (Offset?) -> Unit) {
    val sel = state.selection
    val len = layout.layoutInput.text.length
    if (len == 0 || sel.max > len) return
    val size = with(LocalDensity.current) { 22.dp.toPx() } / scale.coerceAtLeast(0.05f)
    val ends = if (sel.collapsed) listOf(0 to sel.min) else listOf(-1 to sel.min, 1 to sel.max)
    ends.forEach { (side, index) ->
        val c = layout.getCursorRect(index)
        val nose = Offset(at.x + c.left, at.y + c.bottom)
        // The drop hangs from its nose: to the left of the start, to the right of the end, centred under a cursor.
        val left = when (side) { -1 -> nose.x - size; 1 -> nose.x; else -> nose.x - size / 2 }
        val sideNow by rememberUpdatedState(side); val selNow by rememberUpdatedState(sel)
        val layoutNow by rememberUpdatedState(layout); val startNow by rememberUpdatedState(nose - at - Offset(0f, c.height / 2))
        var drag by remember { mutableStateOf(Offset.Zero) }
        var held by remember { mutableStateOf(false) }
        var place by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
        var fingerY by remember { mutableFloatStateOf(0f) } // where the finger is, in the window
        fun moveTo() {
            dragging(at + drag)
            val to = layoutNow.getOffsetForPosition(drag)
            val s = selNow
            state.selection = when (sideNow) {
                -1 -> TextRange(minOf(to, s.max - 1).coerceAtLeast(0), s.max)
                1 -> TextRange(s.min, maxOf(to, s.min + 1))
                else -> TextRange(to)
            }
        }
        // held by an edge of the view: the page scrolls on, and this end goes with it, though the finger is still
        LaunchedEffect(held) { var logged = false
            while (held) { val dy = edge(fingerY); if (dy != 0f) { if (!logged) { logged = true; WriterLog.i("selection handle at the view's edge: the page scrolls") }; drag += Offset(0f, dy); moveTo() }; kotlinx.coroutines.delay(16) } }
        Box(Modifier.offset { IntOffset(left.roundToInt(), nose.y.roundToInt()) }.size(with(LocalDensity.current) { size.toDp() }).onGloballyPositioned { place = it }
            // Keyed on the side alone: keyed on the place too, the drag ended at the first letter it moved.
            .pointerInput(side) {
                detectDragGestures(onDragStart = { o -> drag = startNow; held = true; fingerY = place?.takeIf { it.isAttached }?.localToWindow(o)?.y ?: 0f; dragging(at + drag)
                        WriterLog.i("selection handle $sideNow held at (${drag.x.roundToInt()}, ${drag.y.roundToInt()}), finger at ${fingerY.roundToInt()} in the window, ${selNow.min}–${selNow.max}; ${where(at + drag)}") },
                    onDragEnd = { held = false; dragging(null); WriterLog.i("selection handle $sideNow let go at (${drag.x.roundToInt()}, ${drag.y.roundToInt()}), ${state.selection.min}–${state.selection.max}") },
                    onDragCancel = { held = false; dragging(null) }) { change, by ->
                    change.consume(); drag += by; place?.takeIf { it.isAttached }?.let { fingerY = it.localToWindow(change.position).y }; moveTo()
                }
            }
            .drawBehind {
                val r = size * 0.42f
                val centre = when (side) { -1 -> Offset(size - r * 1.25f, r * 1.25f + r * 0.2f); 1 -> Offset(r * 1.25f, r * 1.25f + r * 0.2f); else -> Offset(size / 2, size - r) }
                val tip = when (side) { -1 -> Offset(size, 0f); 1 -> Offset(0f, 0f); else -> Offset(size / 2, 0f) }
                val path = androidx.compose.ui.graphics.Path().apply {
                    // The nose: from the tip to the two points where its lines touch the round part.
                    val v = centre - tip; val dist = v.getDistance(); val a = kotlin.math.atan2(v.y, v.x); val spread = kotlin.math.asin((r / dist).coerceAtMost(1f))
                    val t1 = tip + Offset(kotlin.math.cos(a - spread), kotlin.math.sin(a - spread)) * (dist * kotlin.math.cos(spread))
                    val t2 = tip + Offset(kotlin.math.cos(a + spread), kotlin.math.sin(a + spread)) * (dist * kotlin.math.cos(spread))
                    moveTo(tip.x, tip.y); lineTo(t1.x, t1.y); lineTo(centre.x, centre.y); lineTo(t2.x, t2.y); close()
                }
                drawPath(path, SELECTION_BLUE); drawCircle(SELECTION_BLUE, r, centre)
            })
    }
}

/**
 * Android's floating menu over chosen words, taken over (asked 14 Oct): Writer's Selection tab shows instead, with
 * the editor's own Cut/Copy/Paste kept here for its buttons.
 */
private class WriterTextToolbar : androidx.compose.ui.platform.TextToolbar {
    var copy: (() -> Unit)? = null; var cut: (() -> Unit)? = null; var paste: (() -> Unit)? = null; var selectAll: (() -> Unit)? = null
    var shown by mutableStateOf(false); private set
    override val status get() = if (shown) androidx.compose.ui.platform.TextToolbarStatus.Shown else androidx.compose.ui.platform.TextToolbarStatus.Hidden
    override fun showMenu(rect: androidx.compose.ui.geometry.Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
        onCopyRequested?.let { copy = it }; onCutRequested?.let { cut = it }; onPasteRequested?.let { paste = it }; onSelectAllRequested?.let { selectAll = it }
        if (!shown) WriterLog.i("text menu asked: " + listOfNotNull("copy".takeIf { onCopyRequested != null }, "cut".takeIf { onCutRequested != null }, "paste".takeIf { onPasteRequested != null }, "select all".takeIf { onSelectAllRequested != null }).joinToString())
        shown = true
    }
    override fun hide() { shown = false }
}

private val SELECTION_BLUE = Color(0xFF0B3D91)

internal fun Modifier.scrollShade(state: androidx.compose.foundation.ScrollState): Modifier = drawWithContent {
    drawContent()
    val w = 36.dp.toPx()
    val shade = Color.Black.copy(alpha = 0.85f)
    if (state.value > 0) drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(shade, Color.Transparent), 0f, w), size = androidx.compose.ui.geometry.Size(w, size.height))
    if (state.value < state.maxValue) drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, shade), size.width - w, size.width),
        topLeft = androidx.compose.ui.geometry.Offset(size.width - w, 0f), size = androidx.compose.ui.geometry.Size(w, size.height))
}

/** A ribbon's row scrolled sideways, shaded at an edge while there is more that way (as Words' tabs; asked 16 Oct). */
internal fun Modifier.shadedRow(): Modifier = composed { val sc = rememberScrollState(); scrollShade(sc).horizontalScroll(sc) }

/** A dialog's scrolling column: a shade at the top or bottom edge while there is more that way (asked 14 Oct). */
internal fun Modifier.shadedScroll(): Modifier = composed {
    val sc = rememberScrollState()
    val bg = MaterialTheme.colorScheme.surfaceContainerHigh // AlertDialog's own colour
    drawWithContent {
        drawContent()
        val h = 28.dp.toPx()
        if (sc.value > 0) drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(bg, Color.Transparent), 0f, h), size = androidx.compose.ui.geometry.Size(size.width, h))
        if (sc.value < sc.maxValue) drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, bg), size.height - h, size.height),
            topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h), size = androidx.compose.ui.geometry.Size(size.width, h))
    }.verticalScroll(sc)
}

/** [outlined]: a thin border, for a button that is a word (Font's name) and would otherwise read as a label. */
@Composable internal fun RibbonButton(label: String, tint: Color, icon: Int? = null, enabled: Boolean = true, on: Boolean = false, outlined: Boolean = false, click: () -> Unit) = Row(
    // Normal size: at least 48 dp across, so a short one (1, X², H1) is a finger's width too (the user, 18 Oct)
    Modifier.height(if (OfficeUi.compact) 32.dp else 40.dp).widthIn(min = if (OfficeUi.compact) 0.dp else 48.dp).clip(CircleShape).then(if (on) Modifier.background(tint.copy(alpha = 0.18f)) else Modifier)
        .then(if (outlined) Modifier.border(1.dp, tint.copy(alpha = 0.5f), CircleShape) else Modifier)
        // Never taking the focus: tapped while a table cell is being typed in, it took it, the keyboard went down, the
        // ribbon moved under the finger and the tap was lost.
        .focusProperties { canFocus = false }
        .clickable(enabled = enabled, onClick = { WriterLog.i("tap $label"); click() }).padding(horizontal = if (OfficeUi.compact) 8.dp else 12.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
) {
    val c = if (enabled) tint else tint.copy(alpha = 0.35f)
    icon?.let { Icon(painterResource(it), contentDescription = null, tint = c, modifier = Modifier.size(if (OfficeUi.compact) 16.dp else 18.dp)) }
    Text(label, color = c, style = if (OfficeUi.compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** A paragraph's spacing in the editor (sp): before its first line, after its last, and its line's own height. */
internal class BlockSpacing(val before: Float, val after: Float, val line: Float)

/** A picture in front of or behind the text, in the editor: from the text's left and its paragraph's top (pt), its size. */
internal class FloatShown(val x: Float, val y: Float, val w: Float, val h: Float, val behind: Boolean, val pageX: Float, val pageY: Float)

/**
 * Letters [a]..[z] shaded on each of their lines, as tall as the letters (a spaced line is taller than its letters: a
 * range's path covered the space too). [em]: the editor's own size, for letters without one of their own.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.shadeLetters(l: TextLayoutResult, a: Int, z: Int, color: Color, em: Float) {
    val spans = l.layoutInput.text.spanStyles
    for (line in l.getLineForOffset(a)..l.getLineForOffset((z - 1).coerceAtLeast(a))) {
        val s = maxOf(a, l.getLineStart(line)); val e = minOf(z, l.getLineEnd(line, true))
        if (e <= s) continue
        val size = spans.lastOrNull { it.start <= s && it.end > s && it.item.fontSize.isSp }?.item?.fontSize?.toPx() ?: em
        val x1 = l.getHorizontalPosition(s, true); val x2 = l.getHorizontalPosition(e, true)
        val base = l.getLineBaseline(line)
        drawRect(color, Offset(minOf(x1, x2), base - size * 0.95f), androidx.compose.ui.geometry.Size(kotlin.math.abs(x2 - x1), size * 1.22f))
    }
}

/** Spans with their same-looking neighbours joined, as the editor joins them once laid out. */
private fun joinedSpans(spans: List<androidx.compose.ui.text.AnnotatedString.Range<androidx.compose.ui.text.SpanStyle>>) =
    spans.sortedWith(compareBy({ it.start }, { it.end })).fold(ArrayList<androidx.compose.ui.text.AnnotatedString.Range<androidx.compose.ui.text.SpanStyle>>()) { out, r ->
        val i = out.indexOfLast { it.item == r.item && it.end == r.start }
        if (i >= 0) out[i] = out[i].copy(end = r.end) else out += r
        out
    }.sortedWith(compareBy({ it.start }, { it.end }, { it.item.hashCode() }))

/** A colour tool: tapped, its swatches open beside it on the same line; the crossed-out one removes the colour. */
@Composable internal fun Swatches(tint: Color, icon: Int, label: String, colors: List<Color>, choose: (Color?) -> Unit) {
    // a tablet or phone: the colours on a strip of their own, picked, then ✓ (the user, 18 Oct); a desktop: in the ribbon's line
    val strip = officeStrip(label, ok = { s -> s.picked?.let { choose(it as? Color) } }) { s ->
        SwatchDots(tint, label, colors, s.picked) { s.picked = it ?: NO_COLOUR }
        val strips = LocalOfficeStrips.current
        MoreColours(tint, label) { c -> strips?.open = null; choose(c) }
    }
    if (strip != null) { Tool(icon, label, tint) { strip() }; return }
    var open by remember { mutableStateOf(false) }
    Tool(icon, label, tint, on = open) { open = !open }
    if (open) {
        SwatchDots(tint, label, colors, null) { choose(it); open = false }
        Spacer(Modifier.width(4.dp))
    }
}

/** Word's colour table (the user, 18 Oct: "the basic colors at the top and gradually brighter ones as you go down"): black and
 *  Word's ten standard colours, each lighter by a fifth a row down — a tap is the colour. */
internal val COLOUR_TABLE: List<List<Color>> = listOf(0x000000, 0xC00000, 0xFF0000, 0xFFC000, 0xFFFF00, 0x92D050, 0x00B050, 0x00B0F0, 0x0070C0, 0x002060, 0x7030A0).let { base ->
    (0..4).map { row -> base.map { rgb -> fun c(v: Int) = v + (255 - v) * row / 5
        Color(c(rgb shr 16 and 0xFF), c(rgb shr 8 and 0xFF), c(rgb and 0xFF)) } } }

/** A colour strip's last tool, More…: [COLOUR_TABLE] in a window, the colour tapped [choose]n. */
@Composable internal fun MoreColours(tint: Color, label: String, choose: (Color) -> Unit) {
    var open by remember { mutableStateOf(false) }
    RibbonButton(stringResource(R.string.common_more), tint, R.drawable.ic_more_horiz) { open = true }
    if (open) androidx.compose.material3.AlertDialog(onDismissRequest = { open = false }, title = { Text(label) }, text = {
        BoxWithConstraints {
            val cell = minOf(40.dp, maxWidth / COLOUR_TABLE[0].size)
            Column {
                COLOUR_TABLE.forEach { row -> Row { row.forEach { c ->
                    Box(Modifier.size(cell).clickable(onClickLabel = label) { open = false; choose(c) }.padding(3.dp).clip(RoundedCornerShape(4.dp)).background(c)
                        .border(1.dp, Color(0x55888888), RoundedCornerShape(4.dp)))
                } } }
            }
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.common_cancel)) } })
}

/** [Swatches]' picked "no colour" */
internal const val NO_COLOUR = "no colour"

/** Colour dots: "no [label]" first, then [colors]; [picked] (a colour or [NO_COLOUR]) ringed. A dot is a tool's size to tap. */
@Composable internal fun SwatchDots(tint: Color, label: String, colors: List<Color>, picked: Any?, choose: (Color?) -> Unit) {
    Box(Modifier.size(LocalToolSize.current).clip(CircleShape).then(if (picked == NO_COLOUR) Modifier.background(tint.copy(alpha = 0.18f)) else Modifier)
        .clickable(onClickLabel = stringResource(R.string.words_no_colour_of, label)) { choose(null) }, contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_no_color), contentDescription = stringResource(R.string.words_no_colour_of, label.lowercase()), tint = tint, modifier = Modifier.size(22.dp))
    }
    colors.forEach { c ->
        Box(Modifier.size(LocalToolSize.current).clip(CircleShape).clickable(onClickLabel = label) { choose(c) }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(c).border(if (picked == c) 3.dp else 1.dp, if (picked == c) tint else tint.copy(alpha = 0.5f), CircleShape))
        }
    }
}

/**
 * The document's pages (Print layout): white pages of the file's own size on the dark desk, "Page n of N" at the
 * bottom, pinch to zoom (sideways scrolling when wider). A tablet shows them as a desktop does (asked 2026-09-29): a
 * page at the editor's text size ([perPt] a point) when it fits, centred, with Word's rulers — along the top for the
 * page in view, down each page's side. A phone fits the page to its width, no rulers.
 */
@Composable
private fun PagesView(pages: List<LaidPage>, perPt: Dp, modifier: Modifier, goTo: Pair<Int, Long>? = null, covered: Dp = 0.dp,
    /** View › Two pages: pages side by side; View › Fit width: the page as wide as the screen (a tablet's too). */
    across: Int = 1, fitWidth: Boolean = false, /** a video's film to play (its part taken out of the file) */ film: suspend (String) -> String? = { null }) {
    var zoom by remember { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()
    var playing by remember { mutableStateOf<Pair<LaidPage, OfficeVideo.Spot>?>(null) } // a video tapped, playing in its frame on its page
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val coveredPx = with(LocalDensity.current) { covered.toPx() }
    // Navigation: a heading's page. A page as -(n + 1): its foot, clear of a strip [covered] at the bottom (a footer's gallery).
    LaunchedEffect(goTo) { goTo?.let { (page, _) ->
        val p = (if (page < 0) -page - 1 else page).coerceIn(0, pages.lastIndex) / across
        list.animateScrollToItem(p)
        if (page < 0) list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == p }?.let { item ->
            list.animateScrollBy(item.offset + item.size - (list.layoutInfo.viewportEndOffset - coveredPx)) }
    } }
    BoxWithConstraints(modifier) {
        val rulers = maxWidth >= 700.dp && across == 1
        val ruler = if (rulers) 18.dp else 0.dp
        val gap = if (rulers) 6.dp else 0.dp
        val between = 12.dp // between pages side by side
        val widest = pages.maxOf { it.page.width }
        val fit = (maxWidth - ruler - gap - 24.dp - between * (across - 1)) / (widest * across)
        val scale = (if (rulers && !fitWidth) minOf(perPt, fit) else fit) * zoom // on screen, per point
        val content = maxOf(maxWidth, widest * scale * across + between * (across - 1) + ruler + gap + 24.dp)
        val sideways = rememberScrollState()
        val at by remember(across) { derivedStateOf { (list.firstVisibleItemIndex + if (list.firstVisibleItemScrollOffset > 0 && list.layoutInfo.visibleItemsInfo.size > 1) 1 else 0) * across } }
        Column(Modifier.fillMaxSize()) {
            if (rulers) {
                val page = pages[at.coerceIn(0, pages.lastIndex)].page
                val x = (content - (page.width * scale + ruler + gap)) / 2 + ruler + gap - with(LocalDensity.current) { sideways.value.toDp() }
                HorizontalRuler(page, scale, x, Modifier.fillMaxWidth().height(22.dp))
            }
            androidx.compose.foundation.lazy.LazyColumn(
                state = list,
                modifier = Modifier.weight(1f).horizontalScroll(sideways).width(content)
                    .transformable(androidx.compose.foundation.gestures.rememberTransformableState { change, _, _ -> zoom = (zoom * change).coerceIn(0.5f, 4f) }, canPan = { false }),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = maxOf(72.dp, covered + 16.dp)),
            ) {
                items((pages.size + across - 1) / across) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(if (across > 1) between else gap), verticalAlignment = Alignment.Top) {
                        for (laid in pages.subList(row * across, minOf(pages.size, row * across + across))) {
                            if (rulers) VerticalRuler(laid.page, scale, Modifier.width(ruler).height(scale * laid.page.height))
                            Box { androidx.compose.foundation.Canvas(Modifier.width(scale * laid.page.width).aspectRatio(laid.page.width / laid.page.height).background(Color.White)
                                .pointerInput(laid) { detectTapGestures { o -> val k = size.width / laid.page.width; laid.videoAt(o.x / k, o.y / k)?.let { v ->
                                    scope.launch { film(v.film)?.let { f -> playing = laid to OfficeVideo.Spot(f, v.x, v.y, v.w, v.h, v.turn) } } } } }) {
                                drawIntoCanvas { c ->
                                    val n = c.nativeCanvas
                                    val k = size.width / laid.page.width
                                    n.save(); n.scale(k, k); laid.draw(n); n.restore()
                                }
                            }
                            playing?.takeIf { it.first === laid }?.second?.let { v -> VideoFrame(v.film, Modifier.offset(scale * v.x, scale * v.y).size(scale * v.w, scale * v.h).rotate(v.turn)) { playing = null } } }
                        }
                    }
                }
            }
        }
        Surface(color = Color.Black.copy(alpha = 0.75f), contentColor = Color.White, shape = CircleShape,
            modifier = Modifier.align(Alignment.BottomCenter).bottomBarPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.words_page_of, (at + 1).coerceAtMost(pages.size), pages.size), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
        }
    }
}

/** [LetterLooks] in the editor: capitals through the font's small-capitals feature (the editor keeps the letters as
 * typed; ALL CAPS as small capitals made capital-sized), double lines drawn under or through the letters. */
internal data class EditorLetters(val looks: LetterLooks, /** the letters' size (pt): character spacing as a share of it */ val sizePt: Float = 11f) : com.mohamedrejeb.richeditor.model.RichSpanStyle {
    override fun getSpanStyle(richTextConfig: com.mohamedrejeb.richeditor.model.RichTextConfig) =
        // ALL CAPS: every letter a small capital, drawn about as tall as a capital (the font's small capitals are ~0.77 of it).
        (if (looks.caps) SpanStyle(fontFeatureSettings = "c2sc, smcp", fontSize = 1.3.em)
        else SpanStyle(fontFeatureSettings = if (looks.smallCaps) "smcp" else null))
            // Hidden text is still edited here: grey, with Word's dotted line under it (the pages leave it out).
            .let { if (looks.hidden) it.copy(color = Color(0xFF8A8A8A)) else it }
            // Character spacing: as a share of the letters' size (Compose's em)
            .let { if (looks.spacing != 0f) it.copy(letterSpacing = (looks.spacing / sizePt.coerceAtLeast(1f)).em) else it }
    override fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCustomStyle(layoutResult: TextLayoutResult, textRange: androidx.compose.ui.text.TextRange,
        richTextConfig: com.mohamedrejeb.richeditor.model.RichTextConfig, topPadding: Float, startPadding: Float) {
        if (!looks.doubleStrike && !looks.doubleUnder && !looks.hidden) return
        val l = layoutResult
        if (textRange.min >= l.layoutInput.text.length) return
        for (line in l.getLineForOffset(textRange.min)..l.getLineForOffset((textRange.max - 1).coerceAtLeast(textRange.min))) {
            val a = maxOf(textRange.min, l.getLineStart(line)); val z = minOf(textRange.max, l.getLineEnd(line, true))
            if (z <= a) continue
            val x1 = l.getHorizontalPosition(a, true) + startPadding; val x2 = l.getHorizontalPosition(z, true) + startPadding
            val base = l.getLineBaseline(line) + topPadding; val h = l.getLineBottom(line) - l.getLineTop(line)
            val ys = if (looks.doubleStrike) listOf(base - h * 0.30f, base - h * 0.19f) else listOf(base + h * 0.15f) // the editor's own underline is the first
            if (looks.hidden) { var x = x1; val y = base + h * 0.12f; while (x < x2) { drawCircle(Color(0xFF8A8A8A), h * 0.025f, Offset(x, y)); x += h * 0.12f } }
            if (looks.doubleStrike || looks.doubleUnder) ys.forEach { y -> drawLine(Color.Black, Offset(x1, y), Offset(x2, y), h * 0.04f) }
        }
    }
}

/** References › Note numbers (Word's Footnote and Endnote › Number format): each kind's numbers, a preview of both; [word]: Word's *, †, ‡ too. */
@Composable
private fun NoteNumbersDialog(foot: String, end: String, word: Boolean, dismiss: () -> Unit, done: (String, String) -> Unit) {
    var f by remember { mutableStateOf(foot) }
    var e by remember { mutableStateOf(end) }
    val formats = NoteFormats.ALL.filter { word || it.first != NoteFormats.CHICAGO }
    // the preview's words, between the note numbers
    val sample = listOf(stringResource(R.string.words_notes_sample_1), stringResource(R.string.words_notes_sample_2), stringResource(R.string.words_notes_sample_3), ".")
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.words_note_numbers)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((label, now, set) in listOf(Triple(stringResource(R.string.words_footnotes), f) { v: String -> f = v }, Triple(stringResource(R.string.words_endnotes), e) { v: String -> e = v })) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    formats.forEach { (id, shown) -> androidx.compose.material3.FilterChip(selected = id == now, onClick = { set(id) }, label = { Text(shown) }) }
                }
            }
            // the preview: a line with both kinds' first two notes
            Surface(color = Color.White, contentColor = Color.Black, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Text(androidx.compose.ui.text.buildAnnotatedString {
                    fun sup(t: String) { pushStyle(SpanStyle(baselineShift = androidx.compose.ui.text.style.BaselineShift.Superscript, fontSize = 0.7.em)); append(t); pop() }
                    append(sample[0]); sup(NoteFormats.number(1, f)); append(sample[1]); sup(NoteFormats.number(2, f))
                    append(sample[2]); sup(NoteFormats.number(1, e)); append(sample[3])
                }, modifier = Modifier.padding(12.dp))
            }
        } },
        confirmButton = { TextButton(onClick = { done(f, e) }) { Text(stringResource(R.string.common_done)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } })
}

/** References › Caption: which label ("Figure", "Table" — Word's, and the Greek ones) and the words after its number. */
@Composable
private fun CaptionDialog(dismiss: () -> Unit, insert: (String, String) -> Unit) {
    val labels = listOf("Figure", "Table", "Equation", "Εικόνα", "Πίνακας")
    var label by remember { mutableStateOf(labels[0]) }
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.words_caption)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEach { l -> androidx.compose.material3.FilterChip(selected = l == label, onClick = { label = l }, label = { Text(l) }) }
            }
            OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("$label 1: …") }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.words_caption_hint), style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = { insert(label, text) }) { Text(stringResource(R.string.words_insert)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } })
}

/** References › Cross-reference: one of the document's captions, shown as its words or as its page. */
@Composable
private fun CrossReferenceDialog(captions: List<Pair<Int, String>>, dismiss: () -> Unit, insert: (Int, String) -> Unit) {
    var how by remember { mutableStateOf("caption") }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.words_cross_reference)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Word's "Insert reference to".
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("caption" to stringResource(R.string.words_xref_caption), "label" to stringResource(R.string.words_xref_label), "page" to stringResource(R.string.words_xref_page)).forEach { (k, label) ->
                    androidx.compose.material3.FilterChip(selected = how == k, onClick = { how = k }, label = { Text(label) }) }
            }
            if (captions.isEmpty()) Text(stringResource(R.string.words_no_captions), style = MaterialTheme.typography.bodyMedium)
            Column(Modifier.heightIn(max = 320.dp).shadedScroll()) {
                captions.forEach { (i, text) -> Text(text.ifBlank { stringResource(R.string.words_empty) }, style = MaterialTheme.typography.bodyLarge, maxLines = 2,
                    modifier = Modifier.fillMaxWidth().clickable { insert(i, how) }.padding(vertical = 10.dp)) }
            }
        } },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } })
}

/** File › Print: a PDF already made, handed to Android's printing as it is. */
internal class PdfPrintAdapter(val pdf: File, val title: String) : android.print.PrintDocumentAdapter() {
    override fun onLayout(old: android.print.PrintAttributes?, new: android.print.PrintAttributes, cancel: android.os.CancellationSignal?, callback: LayoutResultCallback, extras: android.os.Bundle?) {
        if (cancel?.isCanceled == true) { callback.onLayoutCancelled(); return }
        callback.onLayoutFinished(android.print.PrintDocumentInfo.Builder("$title.pdf").setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), old != new)
    }
    override fun onWrite(pages: Array<out android.print.PageRange>, out: android.os.ParcelFileDescriptor, cancel: android.os.CancellationSignal?, callback: WriteResultCallback) {
        runCatching { pdf.inputStream().use { input -> java.io.FileOutputStream(out.fileDescriptor).use { input.copyTo(it) } } }
            .onSuccess { callback.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES)) }
            .onFailure { callback.onWriteFailed(it.message) }
    }
}

/**
 * File › Versions: this session's copies (every few minutes) and the file as it was before saves, newest first. One
 * opened shows its pages; "Keep this one" makes it the document and deletes every other version.
 */
@Composable
private fun HistoryDialog(entries: List<Pair<File, Boolean>>, kind: OfficeKind, dismiss: () -> Unit, keep: (File) -> Unit) {
    var open by remember { mutableStateOf<File?>(null) }
    val format = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT) }
    fun timeOf(f: File) = format.format(java.util.Date(f.name.substringBefore('.').toLongOrNull() ?: f.lastModified()))
    androidx.compose.ui.window.Dialog(onDismissRequest = { if (open != null) open = null else dismiss() }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.systemBarsPadding().padding(16.dp)) {
                val shown = open
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (shown != null) TextButton(onClick = { open = null }) { Text(stringResource(R.string.words_all_versions)) }
                    Text(if (shown == null) stringResource(R.string.words_versions) else timeOf(shown), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    if (shown != null) Button(onClick = { keep(shown) }) { Text(stringResource(R.string.words_keep_this_one)) }
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.common_close)) }
                }
                if (shown == null) {
                    Text(stringResource(if (entries.isEmpty()) R.string.words_versions_none else R.string.words_versions_about), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                    Column(Modifier.shadedScroll()) {
                        entries.forEach { (f, session) ->
                            Row(Modifier.fillMaxWidth().clickable { open = f }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(R.drawable.ic_history), contentDescription = null, modifier = Modifier.size(22.dp))
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(timeOf(f), style = MaterialTheme.typography.titleSmall)
                                    Text(stringResource(if (session) R.string.words_version_session else R.string.words_version_before_save) + " · ${(f.length() + 1023) / 1024} KB", style = MaterialTheme.typography.bodySmall)
                                }
                                Text(stringResource(R.string.common_open), color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                } else {
                    // Its pages, as View shows a document.
                    val pages by produceState<List<LaidPage>?>(null, shown) {
                        value = withContext(Dispatchers.Default) { runCatching {
                            val bytes = shown.readBytes()
                            val parts = OfficeFormats.unzip(bytes)
                            OfficePages.layout(OfficeFormats.read(bytes, kind), DocStyles.of(kind, bytes)) { img -> img.bytes ?: img.part?.let { parts[it] } }
                        }.onFailure { WriterLog.e("history pages", it) }.getOrNull() }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF3A3A3A))) {
                        pages?.let { PagesView(it, with(LocalDensity.current) { (BASE_SP / 11f).sp.toDp() }, Modifier.fillMaxSize()) }
                            ?: Text(stringResource(R.string.words_laying_out), color = Color.White, modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
        }
    }
}

/**
 * Save picture as… (Words', Slides' and Tables'): [bytes] into the phone's Pictures/Tetra, where Photos and Files find it,
 * named for the document ([document]); [ext] its kind (null: told by its first bytes). What to tell the user.
 */
internal suspend fun savePictureToPhone(context: android.content.Context, bytes: ByteArray, ext: String?, document: String): String {
    val kind = ext ?: when {
        bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png"
        bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
        bytes.size > 3 && String(bytes, 0, 3, Charsets.ISO_8859_1) == "GIF" -> "gif"
        bytes.size > 12 && String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "webp"
        else -> return context.getString(R.string.words_picture_kind_unsaveable)
    }
    val ok = withContext(Dispatchers.IO) { runCatching {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "${document.substringBeforeLast('.')} picture.$kind")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(kind) ?: "image/$kind")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Tetra")
        }
        val target = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(target)!!.use { it.write(bytes) }
    }.isSuccess }
    return context.getString(if (ok) R.string.words_picture_saved else R.string.words_picture_not_saved)
}

/** Home › Character spacing's choices (Word's Condensed and Expanded by, in points), shared by Words and Slides. */
internal val CHARACTER_SPACINGS = listOf("Very tight (−1.5 pt)" to -1.5f, "Tight (−0.75 pt)" to -0.75f, "Normal" to 0f, "Loose (+1.5 pt)" to 1.5f, "Very loose (+3 pt)" to 3f)
/** [CHARACTER_SPACINGS]' names as shown (the English ones stay for Slides). */
internal val CHARACTER_SPACING_NAMES = listOf(R.string.words_spacing_very_tight, R.string.words_spacing_tight, R.string.words_spacing_normal, R.string.words_spacing_loose, R.string.words_spacing_very_loose)
