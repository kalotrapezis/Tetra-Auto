package com.kalotrapezis.drive

// From Notes for Android (the same author), for the notes editor's live styling and read view.

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Just enough Markdown for the read view: the subset the format toolbar writes,
 * plus links and pipe tables (for the text editor's View, asked 1 Nov). No HTML.
 *
 * ponytail: a hand-rolled scanner rather than a Markdown dependency. If nested
 * or reference-style syntax ever matters, swap in a real parser then.
 */
internal fun renderMarkdown(source: String, bodySize: TextUnit = 16.sp): AnnotatedString = buildAnnotatedString {
    val lines = source.lines()
    val tables = tableColumns(lines)
    var fenced = false

    lines.forEachIndexed { index, raw ->
        if (index > 0) append('\n')

        if (raw.trimStart().startsWith("```")) {
            fenced = !fenced
            return@forEachIndexed  // the fence itself is syntax; drop it
        }
        if (fenced) {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = bodySize * 0.9f)) { append(raw) }
            return@forEachIndexed
        }

        // A table row: cells padded to their column in monospace so the columns line up; the header bold, the --- rule a line.
        tables[index]?.let { widths ->
            val cells = tableCells(raw)
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = bodySize * 0.9f)) {
                if (cells.all { it.matches(TABLE_RULE) }) append(widths.joinToString("─┼─") { "─".repeat(it) })
                else withStyle(SpanStyle(fontWeight = if (tables[index + 1] != null && tableCells(lines[index + 1]).all { it.matches(TABLE_RULE) }) FontWeight.Bold else FontWeight.Normal)) {
                    append(widths.indices.joinToString(" │ ") { (cells.getOrElse(it) { "" }).padEnd(widths[it]) })
                }
            }
            return@forEachIndexed
        }

        val heading = Regex("^(#{1,6})\\s+(.*)").find(raw)
        if (heading != null) {
            val level = heading.groupValues[1].length
            val scale = when (level) {
                1 -> 1.7f
                2 -> 1.4f
                3 -> 1.2f
                else -> 1.05f
            }
            withStyle(SpanStyle(fontSize = bodySize * scale, fontWeight = FontWeight.Bold)) {
                appendInline(heading.groupValues[2], bodySize)
            }
            return@forEachIndexed
        }

        val quote = Regex("^\\s*>\\s?(.*)").find(raw)
        if (quote != null) {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                append("│ ")
                appendInline(quote.groupValues[1], bodySize)
            }
            return@forEachIndexed
        }

        // Task boxes before plain bullets — "- [ ] x" is also a bullet.
        val task = Regex("^(\\s*)[-*]\\s+\\[([ xX])]\\s+(.*)").find(raw)
        if (task != null) {
            append(task.groupValues[1])
            append(if (task.groupValues[2].isBlank()) "☐ " else "☑ ")
            appendInline(task.groupValues[3], bodySize)
            return@forEachIndexed
        }

        val bullet = Regex("^(\\s*)[-*+]\\s+(.*)").find(raw)
        if (bullet != null) {
            append(bullet.groupValues[1])
            append("• ")
            appendInline(bullet.groupValues[2], bodySize)
            return@forEachIndexed
        }

        val numbered = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)").find(raw)
        if (numbered != null) {
            append("${numbered.groupValues[1]}${numbered.groupValues[2]}. ")
            appendInline(numbered.groupValues[3], bodySize)
            return@forEachIndexed
        }

        appendInline(raw, bodySize)
    }
}

private val TABLE_RULE = Regex(":?-{3,}:?")
private fun tableCells(line: String) = line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

/** Line index → column widths, for every line of a pipe table: a | row followed by a |---| rule, and the rows after it. */
private fun tableColumns(lines: List<String>): Map<Int, List<Int>> {
    val out = HashMap<Int, List<Int>>()
    var i = 0
    while (i < lines.size - 1) {
        val isRow = { l: String -> l.trimStart().startsWith("|") }
        if (isRow(lines[i]) && isRow(lines[i + 1]) && tableCells(lines[i + 1]).all { it.matches(TABLE_RULE) }) {
            var end = i + 2
            while (end < lines.size && isRow(lines[end])) end++
            val rows = (i until end).filter { it != i + 1 }.map { tableCells(lines[it]) }
            val widths = List(rows.maxOf { it.size }) { c -> rows.maxOf { it.getOrElse(c) { "" }.length }.coerceAtLeast(3) }
            for (k in i until end) out[k] = widths
            i = end
        } else i++
    }
    return out
}

/** A small edit-mode preview which keeps the TextField's source value intact. */
internal fun markdownVisualTransformation(bodySize: TextUnit = 16.sp): VisualTransformation =
    VisualTransformation { source -> markdownEditPreview(source, bodySize) }

private fun markdownEditPreview(source: AnnotatedString, bodySize: TextUnit): TransformedText {
    val text = source.text
    val output = StringBuilder()
    val sourceOffsets = mutableListOf<Int>()
    val styles = mutableListOf<Triple<Int, Int, SpanStyle>>()

    fun append(index: Int) {
        output.append(text[index])
        sourceOffsets += index
    }

    fun appendLine(from: Int, until: Int) {
        var i = from
        while (i < until) {
            val match = listOf(
                "**" to SpanStyle(fontWeight = FontWeight.Bold),
                "__" to SpanStyle(fontWeight = FontWeight.Bold),
                "~~" to SpanStyle(textDecoration = TextDecoration.LineThrough),
                "*" to SpanStyle(fontStyle = FontStyle.Italic),
                "_" to SpanStyle(fontStyle = FontStyle.Italic),
            ).firstNotNullOfOrNull { (marker, style) ->
                if (!text.startsWith(marker, i)) return@firstNotNullOfOrNull null
                val close = text.indexOf(marker, i + marker.length)
                if (close in (i + marker.length + 1)..<until) Triple(marker, close, style) else null
            }
            if (match == null) {
                append(i++)
            } else {
                val (marker, close, style) = match
                val start = output.length
                for (contentIndex in i + marker.length until close) append(contentIndex)
                styles += Triple(start, output.length, style)
                i = close + marker.length
            }
        }
    }

    var lineStart = 0
    while (lineStart < text.length) {
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        var contentStart = lineStart
        var hashes = 0
        while (hashes < 6 && lineStart + hashes < lineEnd && text[lineStart + hashes] == '#') hashes++
        val isHeading = hashes > 0 && lineStart + hashes < lineEnd && text[lineStart + hashes] == ' '
        if (isHeading) contentStart += hashes + 1
        val styledStart = output.length
        appendLine(contentStart, lineEnd)
        if (isHeading) {
            val scale = when (hashes) { 1 -> 1.7f; 2 -> 1.4f; 3 -> 1.2f; else -> 1.05f }
            styles += Triple(styledStart, output.length, SpanStyle(fontSize = bodySize * scale, fontWeight = FontWeight.Bold))
        }
        if (lineEnd < text.length) append(lineEnd)
        lineStart = lineEnd + 1
    }

    val originalToTransformed = IntArray(text.length + 1)
    var visible = 0
    for (offset in originalToTransformed.indices) {
        while (visible < sourceOffsets.size && sourceOffsets[visible] < offset) visible++
        originalToTransformed[offset] = visible
    }
    val transformed = buildAnnotatedString {
        append(output)
        styles.forEach { (start, end, style) -> if (start < end) addStyle(style, start, end) }
    }
    return TransformedText(transformed, object : OffsetMapping {
        override fun originalToTransformed(offset: Int) = originalToTransformed[offset.coerceIn(0, text.length)]
        override fun transformedToOriginal(offset: Int) =
            if (offset < sourceOffsets.size) sourceOffsets[offset.coerceAtLeast(0)] else text.length
    })
}

private val LINK = Regex("""\[([^\]\n]+)]\(([^)\s]+)\)""")

/** Inline spans: `**bold**`, `*italic*`, `~~strike~~`, `` `code` ``. */
private fun AnnotatedString.Builder.appendInline(text: String, bodySize: TextUnit) {
    // Longest markers first, so ** is never mistaken for two *.
    val markers = listOf(
        "**" to SpanStyle(fontWeight = FontWeight.Bold),
        "__" to SpanStyle(fontWeight = FontWeight.Bold),
        "~~" to SpanStyle(textDecoration = TextDecoration.LineThrough),
        "*" to SpanStyle(fontStyle = FontStyle.Italic),
        "_" to SpanStyle(fontStyle = FontStyle.Italic),
        "`" to SpanStyle(fontFamily = FontFamily.Monospace, fontSize = bodySize * 0.9f),
    )

    var i = 0
    while (i < text.length) {
        // [words](https://…) opens in the browser; only web and mail links, never a file's own intent: or content: URI.
        val link = LINK.matchAt(text, i)?.takeIf { m -> m.groupValues[2].let { it.startsWith("https://") || it.startsWith("http://") || it.startsWith("mailto:") } }
        if (link != null) {
            withLink(LinkAnnotation.Url(link.groupValues[2], TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline)))) { appendInline(link.groupValues[1], bodySize) }
            i = link.range.last + 1
            continue
        }
        val hit = markers.firstNotNullOfOrNull { (marker, style) ->
            if (!text.startsWith(marker, i)) return@firstNotNullOfOrNull null
            val close = text.indexOf(marker, i + marker.length)
            // An unclosed marker is literal text, not the start of a span.
            if (close < 0 || close == i + marker.length) null
            else Triple(style, i + marker.length, close)
        }
        if (hit == null) {
            append(text[i])
            i++
        } else {
            val (style, from, to) = hit
            withStyle(style) { append(text.substring(from, to)) }
            i = to + markers.first { text.startsWith(it.first, from - it.first.length) }.first.length
        }
    }
}
