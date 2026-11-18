package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Galleries in the ribbon's place (asked 15 Oct): Design's Style sets, Colours, Fonts, and Insert's WordArt, Page
 * numbers, Header & footer and Home's Multilevel list — pictures of their choices, each tapped shown on the page.
 */
internal enum class DesignGallery(@androidx.annotation.StringRes val title: Int) {
    StyleSets(R.string.words_style_sets), Colours(R.string.words_colours), Fonts(R.string.words_fonts), WordArt(R.string.words_wordart), PageNumbers(R.string.words_page_numbers),
    HeaderFooter(R.string.words_header_footer), Lists(R.string.words_multilevel_list), Font(R.string.words_font), DropCap(R.string.words_drop_cap), LineNumbers(R.string.words_line_numbers)
}

/** [ThemeColors]' names as shown (Office's stay as they are). */
internal fun themeColoursName(t: ThemeColors): Int? = when (t) {
    ThemeColors.Blue -> R.string.words_theme_blue; ThemeColors.Green -> R.string.words_theme_green; ThemeColors.Red -> R.string.words_theme_red
    ThemeColors.Violet -> R.string.words_theme_violet; ThemeColors.Grayscale -> R.string.words_theme_grayscale; else -> null
}

/** [TextWarp]'s names as shown. */
@Composable internal fun warpName(w: TextWarp) = stringResource(when (w) {
    TextWarp.ArchUp -> R.string.words_warp_arch_up; TextWarp.ArchDown -> R.string.words_warp_arch_down; TextWarp.Circle -> R.string.words_warp_circle
    TextWarp.Wave -> R.string.words_warp_wave; TextWarp.SlantUp -> R.string.words_warp_slant_up; TextWarp.SlantDown -> R.string.words_warp_slant_down
})

/** Layout › Line numbers' choices (Word's; [mode] its restart, null none), cards as Insert › Page numbers' (the user, 18 Oct). */
internal data class LineNumbering(val mode: String?, @androidx.annotation.StringRes val label: Int)
internal val LINE_NUMBERINGS = listOf(LineNumbering(null, R.string.common_none), LineNumbering("continuous", R.string.words_line_numbers_continuous), LineNumbering("newPage", R.string.words_line_numbers_each_page),
    LineNumbering("newSection", R.string.words_line_numbers_each_section))

/**
 * Insert › Drop cap's kinds (17 Oct, one strip for Words and Slides, each offering what its file can hold): none; dropped
 * beside the first lines; in the margin (Word's); raised on the first line, a bigger first letter (Slides': PowerPoint and
 * Impress have no drop cap, and every app shows a bigger letter).
 */
internal enum class DropKind(@androidx.annotation.StringRes val label: Int) { None(R.string.common_none), Dropped(R.string.words_dropped), Margin(R.string.words_in_margin), Raised(R.string.words_raised) }

/** Home › Font's gallery: a font chosen. */
internal data class FontChoice(val name: String)

/** A WordArt look: its colour, and Word's effects on its letters. */
internal data class ArtStyle(val color: Int, val outline: Boolean = false, val shadow: Boolean = false, val glow: Boolean = false)
/** Word's WordArt colours (its theme's accents) plain, then with an outline, a shadow, a glow. */
internal val ART_STYLES = listOf(0x4472C4, 0xED7D31, 0x70AD47, 0xFFC000, 0x000000).map { ArtStyle(it) } +
    listOf(ArtStyle(0x4472C4, outline = true), ArtStyle(0x000000, shadow = true), ArtStyle(0xED7D31, shadow = true),
        ArtStyle(0x4472C4, glow = true), ArtStyle(0xFFC000, outline = true, shadow = true))
/** WordArt's bend (null: straight). */
internal data class ArtBend(val warp: TextWarp?)

/** Where the page number goes and how it reads ([place] null: none). */
internal data class NumberSpot(@androidx.annotation.StringRes val label: Int, val place: HeaderFooter.NumberPlace?, val align: DocAlign, val format: String = "#", val roman: Boolean = false) {
    fun on(hf: HeaderFooter) = hf.copy(number = place, numberAlign = align, numberFormat = format, roman = roman)
    /** How it reads on page 1 (of 3). */
    val shown get() = format.replace("#", if (roman) "i" else "1").replace("N", if (roman) "iii" else "3")
}
internal val NUMBER_SPOTS = listOf(
    NumberSpot(R.string.words_spot_top_left, HeaderFooter.NumberPlace.Header, DocAlign.Start), NumberSpot(R.string.words_spot_top_centre, HeaderFooter.NumberPlace.Header, DocAlign.Center),
    NumberSpot(R.string.words_spot_top_right, HeaderFooter.NumberPlace.Header, DocAlign.End), NumberSpot(R.string.words_spot_bottom_left, HeaderFooter.NumberPlace.Footer, DocAlign.Start),
    NumberSpot(R.string.words_spot_bottom_centre, HeaderFooter.NumberPlace.Footer, DocAlign.Center), NumberSpot(R.string.words_spot_bottom_right, HeaderFooter.NumberPlace.Footer, DocAlign.End),
    NumberSpot(R.string.words_spot_page_1_of_3, HeaderFooter.NumberPlace.Footer, DocAlign.Center, "Page # of N"), NumberSpot(R.string.words_spot_one_slash_three, HeaderFooter.NumberPlace.Footer, DocAlign.End, "# / N"),
    // Roman (asked 15 Oct): a thesis's front matter, i, ii, iii.
    NumberSpot(R.string.words_spot_bottom_centre_roman, HeaderFooter.NumberPlace.Footer, DocAlign.Center, roman = true), NumberSpot(R.string.words_spot_bottom_right_roman, HeaderFooter.NumberPlace.Footer, DocAlign.End, roman = true),
    NumberSpot(R.string.common_none, null, DocAlign.End),
)

/** A header-and-footer layout, filled with the document's [title]. */
internal data class HeaderLayout(@androidx.annotation.StringRes val label: Int, val make: (HeaderFooter, String) -> HeaderFooter)
internal val HEADER_LAYOUTS = listOf(
    HeaderLayout(R.string.words_layout_title_centred) { hf, t -> hf.copy(header = t, headerAlign = DocAlign.Center) },
    HeaderLayout(R.string.words_layout_title_left_page_right) { hf, t -> hf.copy(header = t, headerAlign = DocAlign.Start, number = HeaderFooter.NumberPlace.Header, numberAlign = DocAlign.End, numberFormat = "#") },
    HeaderLayout(R.string.words_layout_title_right) { hf, t -> hf.copy(header = t, headerAlign = DocAlign.End) },
    HeaderLayout(R.string.words_layout_title_above_page_below) { hf, t -> hf.copy(header = t, headerAlign = DocAlign.Center, number = HeaderFooter.NumberPlace.Footer, numberAlign = DocAlign.Center, numberFormat = "#") },
    HeaderLayout(R.string.words_layout_book_outer) { hf, t -> hf.copy(header = t, headerAlign = DocAlign.End, number = HeaderFooter.NumberPlace.Footer, numberAlign = DocAlign.End, numberFormat = "#", oddEven = true, evenHeader = t) },
    HeaderLayout(R.string.common_none) { _, _ -> HeaderFooter() },
)

/** Home › Multilevel list: Word's everyday schemes, each level's (numFmt, lvlText). */
internal data class ListScheme(val label: String, val levels: List<Pair<String, String>>)
internal val LIST_SCHEMES = listOf(
    ListScheme("1.  2.  3.", (0..8).map { "decimal" to "%${it + 1}." }),
    ListScheme("1.  a.  i.", (0..8).map { listOf("decimal", "lowerLetter", "lowerRoman")[it % 3] to "%${it + 1}." }),
    ListScheme(LEGAL_SCHEME, (0..8).map { l -> "decimal" to (1..l + 1).joinToString("") { "%$it." } }),
    ListScheme("I.  A.  1.", (0..8).map { listOf("upperRoman", "upperLetter", "decimal")[it % 3] to "%${it + 1}." }),
    ListScheme("i.  a.  1.", (0..8).map { listOf("lowerRoman", "lowerLetter", "decimal")[it % 3] to "%${it + 1}." }),
)
/** Word's legal numbering's name: PowerPoint and Impress number a level on its own, so Slides does not offer it (the tablet walk, 18 Oct) */
internal const val LEGAL_SCHEME = "1.  1.1.  1.1.1."
/** How a scheme's [level] reads for the [n]th item (1, a, i; 1.1.). */
internal fun ListScheme.marker(level: Int, n: Int): String {
    fun one(fmt: String, k: Int) = when (fmt) { "lowerLetter" -> ('a' + (k - 1) % 26).toString(); "lowerRoman" -> listOf("i", "ii", "iii", "iv", "v")[(k - 1) % 5]; "upperRoman" -> listOf("I", "II", "III", "IV", "V")[(k - 1) % 5]
        "upperLetter" -> ('A' + (k - 1) % 26).toString(); else -> k.toString() }
    val text = levels[level].second
    return Regex("%(\\d)").replace(text) { m -> val l = m.groupValues[1].toInt() - 1; one(levels[l].first, if (l == level) n else 1) }
}

/**
 * A gallery in the ribbon's place (asked 15 Oct: "like a ribbon itself, in its place"): its choices as cards in a strip
 * that scrolls sideways, Cancel and OK beside its name; a card tapped shows on the page above at once.
 */
@Composable
internal fun DesignGalleryStrip(what: DesignGallery, chosen: Any?, cancel: () -> Unit, ok: () -> Unit, pick: (Any) -> Unit,
    more: (() -> Unit)? = null, glow: Boolean = true, title: String = "", modifier: Modifier = Modifier, fonts: List<String> = emptyList(), now: String? = null,
    drops: List<DropKind> = emptyList(),
    /** Drop cap: how many lines tall (the user, 17 Oct: a setting, as Word's "Lines to drop", 1–10, and LibreOffice's "Lines", 2–9) */
    dropLines: Int = 3, dropLinesChange: ((Int) -> Unit)? = null, lineModes: List<LineNumbering> = LINE_NUMBERINGS, schemes: List<ListScheme> = LIST_SCHEMES) {
    // the last card: a page of its own (Page numbers' "Your own…", Drop cap's "More…" as the user named it)
    val moreLabel = stringResource(if (what == DesignGallery.DropCap) R.string.common_more else R.string.words_your_own)
    fun c(rgb: Int) = Color(0xFF000000 or (rgb.toLong() and 0xFFFFFF))
    // The phone (asked 15 Oct): smaller cards, the head on its own line above them — and no keyboard over it.
    val phone = LocalConfiguration.current.screenWidthDp < 700
    val k = if (phone) 0.7f else 1f
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(what) { keyboard?.hide() }
    // Its name, if it has one, above it on the strip's dark (asked 15 Oct: better contrast than grey on the card).
    @Composable fun card(selected: Boolean, click: () -> Unit, label: String? = null, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) =
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (label != null) Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(150.dp * k).padding(start = 4.dp))
            Box(Modifier.width(150.dp * k).height(104.dp * k).clip(RoundedCornerShape(12.dp))
                .background(Color.White).border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color(0x33888888), RoundedCornerShape(12.dp))
                .clickable(onClick = click).padding(10.dp * k)) { content() }
        }
    @Composable fun face(name: String?, size: Int, weight: FontWeight = FontWeight.Normal, italic: Boolean = false, color: Color = Color(0xFF222222)) =
        TextStyle(fontFamily = OfficeFonts.family(name), fontSize = (size * k).sp, fontWeight = weight, fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal, color = color)
    val w = Color.White
    // Fonts: a search over Words' and the phone's (asked 17 Oct), beside the strip's name (on a phone, over the cards)
    var query by androidx.compose.runtime.saveable.rememberSaveable(what) { androidx.compose.runtime.mutableStateOf("") }
    @Composable fun search(m: Modifier) = Row(m.height(36.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF2A2E30)).padding(horizontal = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_search), null, tint = w.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            if (query.isEmpty()) Text(stringResource(R.string.words_search_fonts), color = w.copy(alpha = 0.5f), style = MaterialTheme.typography.bodyMedium)
            androidx.compose.foundation.text.BasicTextField(query, { query = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = w),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(w), modifier = Modifier.fillMaxWidth())
        }
        if (query.isNotEmpty()) Icon(painterResource(R.drawable.ic_close), stringResource(R.string.words_clear_search), tint = w, modifier = Modifier.size(18.dp).clickable { query = "" })
    }
    // Drop cap's lines: − n + beside the strip's name (on a phone, over the cards)
    @Composable fun lines(m: Modifier) = Row(m, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(stringResource(R.string.words_lines), color = w, maxLines = 1, style = MaterialTheme.typography.labelLarge)
        Tool(R.drawable.ic_remove, stringResource(R.string.words_fewer_lines), w, enabled = dropLines > 2) { dropLinesChange?.invoke(dropLines - 1) }
        Text("$dropLines", color = w, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(22.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Tool(R.drawable.ic_add, stringResource(R.string.words_more_lines), w, enabled = dropLines < 10) { dropLinesChange?.invoke(dropLines + 1) }
    }
    Column(modifier.fillMaxWidth().bottomBarPadding().imePadding().padding(if (phone) 6.dp else 12.dp).clip(MaterialTheme.shapes.extraLarge).background(Color(0xFF16191A)).padding(vertical = 6.dp)) {
        // the head every strip has (the second walk, 18 Oct: one form): ‹ and the name first, × and ✓ at the end; a phone's on its own line
        Row(Modifier.fillMaxWidth().padding(horizontal = if (phone) 2.dp else 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            StripHead(stringResource(what.title), w, cancel)
            if (!phone && what == DesignGallery.Font) search(Modifier.width(320.dp))
            if (!phone && what == DesignGallery.DropCap && dropLinesChange != null) lines(Modifier.padding(start = 12.dp))
            Box(Modifier.weight(1f))
            RibbonTools {
                Tool(R.drawable.ic_close, stringResource(R.string.common_cancel), w) { cancel() }
                Tool(R.drawable.ic_check, stringResource(R.string.common_ok), w, enabled = chosen != null) { ok() }
            }
        }
        if (phone && what == DesignGallery.Font) search(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp))
        if (phone && what == DesignGallery.DropCap && dropLinesChange != null) lines(Modifier.padding(horizontal = 8.dp))
        val scroll = androidx.compose.foundation.rememberScrollState()
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Row(Modifier.weight(1f).scrollShade(scroll).horizontalScroll(scroll).padding(horizontal = if (phone) 0.dp else 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(if (phone) 8.dp else 10.dp)) {
            when (what) {
                // Each set as a small page: its title, a heading, a line of text, in their own looks.
                DesignGallery.StyleSets -> StyleSet.entries.forEach { set -> card(chosen == set, click = { pick(set) }, stringResource(set.label)) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        fun look(n: String) = set.changes[n]
                        look("Title")?.let { t -> Text(stringResource(R.string.words_style_title), style = face(t.font, 16, if (t.bold == true) FontWeight.Bold else FontWeight.Normal, t.italic == true, t.color?.takeIf { it >= 0 }?.let(::c) ?: Color(0xFF222222)), maxLines = 1) }
                        look("Heading 1")?.let { h -> Text(stringResource(R.string.words_heading_1), style = face(h.font, 12, if (h.bold == true) FontWeight.Bold else FontWeight.Normal, h.italic == true, h.color?.takeIf { it >= 0 }?.let(::c) ?: Color(0xFF222222)), maxLines = 1) }
                        look("Normal")?.let { n -> Text(stringResource(R.string.words_text_of_paper), style = face(n.font, 9, color = n.color?.takeIf { it >= 0 }?.let(::c) ?: Color(0xFF444444)), maxLines = 1) }
                    }
                } }
                // Each palette: its six accents as blocks, the dark and light beside them.
                DesignGallery.Colours -> ThemeColors.entries.forEach { set -> card(chosen == set, click = { pick(set) }, themeColoursName(set)?.let { stringResource(it) } ?: set.label) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp * k)) {
                        Row { set.accents.forEach { a -> Box(Modifier.size(21.dp * k, 30.dp * k).background(c(a))) } }
                        Row { listOf(set.dk2, set.lt2, set.link).forEach { a -> Box(Modifier.size(21.dp * k, 10.dp * k).background(c(a))) } }
                    }
                } }
                // Each pair written in itself: a big Aa, the headings' face, the text's face.
                DesignGallery.Fonts -> ThemeFonts.entries.forEach { set -> card(chosen == set, click = { pick(set) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("Aa", style = face(set.heading, 24))
                        Text(set.heading, style = face(set.heading, 12), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(set.body, style = face(set.body, 9, color = Color(0xFF555555)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } }
                // WordArt: its looks, then its bends — a look and a bend make the WordArt put in.
                DesignGallery.WordArt -> {
                    val (style, bend) = (chosen as? Pair<*, *>) ?: (null to null)
                    ART_STYLES.filter { glow || !it.glow }.forEach { a -> card(style == a, click = { pick(a) }) {
                        Text("Aa", modifier = Modifier.align(androidx.compose.ui.Alignment.Center), style = face(null, 40, FontWeight.Bold, color = c(a.color)).copy(
                            shadow = if (a.glow) androidx.compose.ui.graphics.Shadow(c(if (a.color == 0xFFC000) 0x4472C4 else 0xFFC000), androidx.compose.ui.geometry.Offset.Zero, 14f)
                                else if (a.shadow) androidx.compose.ui.graphics.Shadow(Color(0x99000000), androidx.compose.ui.geometry.Offset(4f, 4f), 3f) else null,
                            drawStyle = if (a.outline) androidx.compose.ui.graphics.drawscope.Stroke(3f) else null))
                    } }
                    val round = stringResource(R.string.words_round_and_round)
                    (listOf(null) + TextWarp.entries).forEach { warp -> card(bend == ArtBend(warp), click = { pick(ArtBend(warp)) }, warp?.let { warpName(it) } ?: stringResource(R.string.words_straight)) {
                        androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4472C4.toInt(); typeface = android.graphics.Typeface.DEFAULT_BOLD }
                            val w = size.width; val h = size.height * 0.8f
                            drawIntoCanvas { cv ->
                                if (warp == null) { paint.textSize = h * 0.4f; paint.textAlign = android.graphics.Paint.Align.CENTER; cv.nativeCanvas.drawText("Abcde", w / 2, h * 0.65f, paint) }
                                else {
                                    val m = warp.letters(w, h); paint.textSize = m
                                    val path = android.graphics.Path(); warp.line(w, h, m).forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
                                    paint.textAlign = android.graphics.Paint.Align.CENTER
                                    cv.nativeCanvas.drawTextOnPath(if (warp == TextWarp.Circle) round else "Abcdefgh", path, 0f, 0f, paint)
                                }
                            }
                        }
                    } }
                }
                // Page numbers: a small page with the number where it goes.
                // Drop cap: each kind a small paragraph showing it (asked 17 Oct: "a ribbon of cards with an example each")
                DesignGallery.DropCap -> drops.forEach { kind -> card(chosen == kind, click = { pick(kind) }, stringResource(kind.label)) {
                    val ink = Color(0xFF222222); val bar = Color(0xFFBDC1C6)
                    @Composable fun line(start: androidx.compose.ui.unit.Dp, width: Float = 1f) = Box(Modifier.padding(start = start).fillMaxWidth(width).height(5.dp * k).background(bar))
                    val big = TextStyle(fontFamily = OfficeFonts.family("Times New Roman"), fontSize = (38 * k).sp, lineHeight = (38 * k).sp, color = ink)
                    when (kind) {
                        DropKind.None -> Column(verticalArrangement = Arrangement.spacedBy(7.dp * k)) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text("A", style = face("Times New Roman", 11, color = ink)); line(2.dp) }
                            repeat(4) { line(0.dp) }
                        }
                        // dropped: beside the first lines, the rest under it; in the margin: out of the text's edge (its line)
                        DropKind.Dropped -> Column(verticalArrangement = Arrangement.spacedBy(7.dp * k)) {
                            Row { Text("A", style = big, modifier = Modifier.padding(end = 4.dp * k))
                                Column(verticalArrangement = Arrangement.spacedBy(7.dp * k), modifier = Modifier.padding(top = 4.dp * k)) { repeat(3) { line(0.dp) } } }
                            repeat(2) { line(0.dp) }
                        }
                        DropKind.Margin -> Row {
                            Text("A", style = big, modifier = Modifier.padding(end = 3.dp * k))
                            Box(Modifier.width(1.dp).height(84.dp * k).background(Color(0xFF8AB4F8)))
                            Column(verticalArrangement = Arrangement.spacedBy(7.dp * k), modifier = Modifier.padding(start = 3.dp * k, top = 4.dp * k)) { repeat(6) { line(0.dp) } }
                        }
                        DropKind.Raised -> Column(verticalArrangement = Arrangement.spacedBy(7.dp * k)) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) { Text("A", style = big); line(2.dp) }
                            repeat(3) { line(0.dp) }
                        }
                    }
                } }
                // Line numbers: two small pages, the numbers in their margins as each choice counts them
                DesignGallery.LineNumbers -> lineModes.forEach { ln -> card(chosen == ln, click = { pick(ln) }, stringResource(ln.label)) {
                    val bar = Color(0xFFBDC1C6); val ink = Color(0xFF5F6368)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp * k)) { for (page in 0..1) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp * k)) {
                        for (i in 1..5) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            val n = when (ln.mode) { null -> ""; "continuous" -> "${page * 5 + i}"; "newSection" -> if (page == 1 && i > 2) "${i - 2}" else "${page * 5 + i}"; else -> "$i" }
                            Text(n, style = TextStyle(fontSize = (7 * k).sp, color = ink), modifier = Modifier.width(10.dp * k))
                            Box(Modifier.fillMaxWidth().height(4.dp * k).background(if (ln.mode == "newSection" && page == 1 && i == 3) Color(0xFF8AB4F8) else bar))
                        }
                    } }
                } }
                DesignGallery.PageNumbers -> NUMBER_SPOTS.forEach { spot -> card(chosen == spot, click = { pick(spot) }) {
                    // No words under it (asked 15 Oct): the small page says where the number goes.
                    if (spot.place != null) MiniPage(header = if (spot.place == HeaderFooter.NumberPlace.Header) spot.shown to spot.align else null,
                        footer = if (spot.place == HeaderFooter.NumberPlace.Footer) spot.shown to spot.align else null, k = k)
                    if (spot.place == null) Text(stringResource(spot.label), modifier = Modifier.align(androidx.compose.ui.Alignment.Center), style = MaterialTheme.typography.labelLarge, color = Color(0xFF5F6368))
                } }
                // Headers and footers: a small page with the document's title and number where the layout puts them.
                DesignGallery.HeaderFooter -> HEADER_LAYOUTS.forEach { layout -> card(chosen == layout, click = { pick(layout) }) {
                    // No words under it either: the small page shows the layout ("None" says so).
                    val hf = layout.make(HeaderFooter(), title.ifBlank { stringResource(R.string.words_title) })
                    fun num(at: HeaderFooter.NumberPlace) = if (hf.number == at) "1" to hf.numberAlign else null
                    if (layout != HEADER_LAYOUTS.last()) MiniPage(header = hf.header.takeIf { it.isNotEmpty() }?.let { it to hf.headerAlign } ?: num(HeaderFooter.NumberPlace.Header),
                        footer = num(HeaderFooter.NumberPlace.Footer), k = k, also = if (hf.header.isNotEmpty() && hf.number == HeaderFooter.NumberPlace.Header) "1" to hf.numberAlign else null)
                    if (layout == HEADER_LAYOUTS.last()) Text(stringResource(layout.label), modifier = Modifier.align(androidx.compose.ui.Alignment.Center), style = MaterialTheme.typography.labelLarge, color = Color(0xFF5F6368))
                } }
                // Fonts (asked 16 Oct: in the ribbon's place, as Page numbers): each written in itself, the one in use marked.
                // then the phone's own fonts (asked 17 Oct), as WPS and LibreOffice on Android list them
                DesignGallery.Font -> {
                    val phoneAll = remember(fonts) { OfficeFonts.phoneFamilies().filter { p -> fonts.none { it.equals(p, true) } } }
                    fun found(l: List<String>) = l.filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }
                    val own = found(fonts); val phoneFonts = found(phoneAll)
                    if (own.isEmpty() && phoneFonts.isEmpty()) Text(stringResource(R.string.words_no_font_called, query.trim()), color = w.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically).padding(16.dp))
                    (own + phoneFonts).forEachIndexed { i, f ->
                        if (i == own.size) Text(stringResource(R.string.words_phone_fonts), color = w, style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically))
                        card(chosen == FontChoice(f) || (chosen == null && f == now), click = { pick(FontChoice(f)) }, f) {
                            Column(Modifier.align(androidx.compose.ui.Alignment.Center), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                Text("Aa Αα", style = face(f, 22), maxLines = 1)
                                Text("123 abc", style = face(f, 12, color = Color(0xFF5F6368)), maxLines = 1)
                            }
                        }
                    }
                }
                // Multilevel lists: three levels as they read.
                DesignGallery.Lists -> schemes.forEach { scheme -> card(chosen == scheme, click = { pick(scheme) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp * k)) {
                        listOf(0 to 1, 1 to 1, 2 to 1, 0 to 2).forEach { (level, n) ->
                            Row(Modifier.padding(start = 14.dp * k * level), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(scheme.marker(level, n), style = face(null, 10), modifier = Modifier.width(34.dp * k))
                                Box(Modifier.width(50.dp * k).height(4.dp * k).background(Color(0xFFBDC1C6)))
                            }
                        }
                    }
                } }
            }
            // Its own words, in a window (the header's text, a format of one's own).
            if (more != null) Box(Modifier.width(110.dp * k).height(104.dp * k).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2A2E30)).clickable(onClick = more).padding(10.dp * k),
                contentAlignment = androidx.compose.ui.Alignment.Center) { Text(moreLabel, color = w, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        }
    }
}

/**
 * Design › Standards (asked 15 Oct: the APA buttons gathered): what a paper must look like — APA 7's paper (its own
 * window: student or professional, title page, abstract), APA's run-in headings 4 and 5 at the cursor, and the style
 * the citations and their list are written in.
 */
/** A strip in the ribbon's place over the page (no dimming: the page in sight), its ✕ and name, its cards sliding. */
@Composable
private fun StripDialog(title: String, dismiss: () -> Unit, cards: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val phone = LocalConfiguration.current.screenWidthDp < 700
    androidx.compose.ui.window.Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.setDimAmount(0f)
        Box(Modifier.fillMaxWidth().fillMaxHeight(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
            Column(Modifier.fillMaxWidth().bottomBarPadding().padding(if (phone) 6.dp else 12.dp).clip(MaterialTheme.shapes.extraLarge).background(Color(0xFF16191A)).padding(vertical = 6.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    IconButton(onClick = dismiss) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.common_close), tint = Color.White) }
                    Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall)
                }
                val scroll = androidx.compose.foundation.rememberScrollState()
                Row(Modifier.fillMaxWidth().scrollShade(scroll).horizontalScroll(scroll).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), content = cards)
            }
        }
    }
}

/** A white card on a strip, its name above it on the dark. */
@Composable
private fun StripCard(title: String, on: Boolean = false, click: () -> Unit, body: @Composable () -> Unit) {
    val k = if (LocalConfiguration.current.screenWidthDp < 700) 0.8f else 1f
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title + if (on) "  ✓" else "", color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp))
        Column(Modifier.width(210.dp * k).height(96.dp * k).clip(RoundedCornerShape(12.dp)).background(Color.White)
            .border(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else Color(0x33888888), RoundedCornerShape(12.dp))
            .clickable(onClick = click).padding(10.dp * k), verticalArrangement = Arrangement.spacedBy(4.dp)) { body() }
    }
}

@Composable private fun stripInk() = TextStyle(color = Color(0xFF222222), fontSize = (if (LocalConfiguration.current.screenWidthDp < 700) 9 else 11).sp)

/** Each citation style with what it writes (asked 16 Oct: examples, not a list of names) — one book, cited and listed. */
@Composable
private fun CiteStyleCards(cite: CiteStyle, styles: List<CiteStyle>, pick: (CiteStyle) -> Unit) {
    val ink = stripInk()
    styles.forEach { c ->
        StripCard(c.label, on = c == cite, click = { pick(c) }) {
            Text(c.parenthetical(listOf(STYLE_SAMPLE), "47", order = listOf(STYLE_SAMPLE.tag)), style = ink.copy(fontWeight = FontWeight.Medium))
            Text(androidx.compose.ui.text.buildAnnotatedString {
                c.entry(STYLE_SAMPLE).forEach { r -> if (r.italic) withStyle(androidx.compose.ui.text.SpanStyle(fontStyle = FontStyle.Italic)) { append(r.text) } else append(r.text) }
            }, style = ink)
        }
    }
}

/** References › Style as a strip in the ribbon's place (asked 16 Oct: it was a list of names): each style's example, picked, the ribbon back. */
@Composable
internal fun CiteStyleStrip(cite: CiteStyle, styles: List<CiteStyle>, dismiss: () -> Unit, pick: (CiteStyle) -> Unit) =
    StripDialog(stringResource(R.string.words_citation_style), dismiss) { CiteStyleCards(cite, styles, pick) }

/**
 * Design › Standards as a strip in the ribbon's place, as Font's and Page numbers' (asked 16 Oct): APA 7's paper, its
 * run-in headings 4 and 5 at the cursor, and the style the citations and their list are written in, each with what it does.
 */
@Composable
internal fun StandardsDialog(cite: CiteStyle, dismiss: () -> Unit, paper: () -> Unit, runIn: (Int) -> Unit, citeStyle: (CiteStyle) -> Unit, styles: List<CiteStyle> = CiteStyle.entries) =
    StripDialog(stringResource(R.string.words_standards), dismiss) {
        val ink = stripInk()
        val results = stringResource(R.string.words_runin_sample_heading); val runsOn = stringResource(R.string.words_runin_sample_text)
        StripCard(stringResource(R.string.words_apa_paper), click = paper) { Text(stringResource(R.string.words_apa_paper_about), style = ink) }
        for ((level, italic) in listOf(4 to false, 5 to true)) StripCard(stringResource(R.string.words_heading_level_n, level), click = { runIn(level) }) {
            Text(androidx.compose.ui.text.buildAnnotatedString {
                withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal)) { append(results) }
                append(runsOn)
            }, style = ink)
        }
        Box(Modifier.padding(top = 22.dp).width(1.dp).height(100.dp).background(Color.White.copy(alpha = 0.3f)))
        CiteStyleCards(cite, styles, citeStyle)
    }

/** The book each citation style's card cites and lists. */
private val STYLE_SAMPLE = DocSource("tetra-sample", DocSource.Type.Book, listOf(DocSource.Person("Kurlansky", "Mark")), year = "2018", title = "Salt: A world history", publisher = "Penguin")

/** A page in small: grey lines of text, and a header's and a footer's words where they stand. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.MiniPage(header: Pair<String, DocAlign>?, footer: Pair<String, DocAlign>?, k: Float, also: Pair<String, DocAlign>? = null) {
    @Composable fun words(t: Pair<String, DocAlign>, top: Boolean) = Text(t.first, maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = TextStyle(fontSize = (8 * k).sp, color = Color(0xFF202124), fontWeight = FontWeight.Medium),
        modifier = Modifier.align(when (t.second) {
            DocAlign.Center -> if (top) androidx.compose.ui.Alignment.TopCenter else androidx.compose.ui.Alignment.BottomCenter
            DocAlign.End -> if (top) androidx.compose.ui.Alignment.TopEnd else androidx.compose.ui.Alignment.BottomEnd
            else -> if (top) androidx.compose.ui.Alignment.TopStart else androidx.compose.ui.Alignment.BottomStart
        }).padding(bottom = if (top) 0.dp else 10.dp * k).widthIn(max = 80.dp * k))
    Column(Modifier.align(androidx.compose.ui.Alignment.Center).padding(vertical = 14.dp * k), verticalArrangement = Arrangement.spacedBy(4.dp * k)) {
        repeat(4) { Box(Modifier.width(if (it == 3) 60.dp * k else 100.dp * k).height(3.dp * k).background(Color(0xFFDADCE0))) }
    }
    header?.let { words(it, true) }
    also?.let { words(it, true) }
    footer?.let { words(it, false) }
}
