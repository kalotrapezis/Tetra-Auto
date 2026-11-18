package com.kalotrapezis.drive

import android.graphics.Canvas
import android.graphics.Paint
import org.w3c.dom.Element

/**
 * Charts (the plan, 2026-10-01: Word's floating objects, then charts — the same DrawingML chart Excel and PowerPoint
 * use): what a chart shows — its kind, title, categories and each series' values — read from Word's chart part or
 * LibreOffice's chart object, drawn on the pages, and written for a new one.
 */
internal enum class ChartKind(val label: String, @androidx.annotation.StringRes val labelRes: Int, val made: Boolean = true) {
    Column("Column", R.string.office_chart_column), Bar("Bar", R.string.office_chart_bar), Line("Line", R.string.office_chart_line), Area("Area", R.string.office_chart_area),
    Pie("Pie", R.string.office_chart_pie), Doughnut("Doughnut", R.string.office_chart_doughnut), Scatter("Scatter", R.string.office_chart_scatter),
    // read and drawn (asked 16 Oct: Excel's other charts), not offered for a new chart
    Radar("Radar", R.string.office_chart_radar, made = false), Bubble("Bubble", R.string.office_chart_bubble, made = false),
}

/**
 * One series: its name, a value for each category (null: none), its colour (RGB; null: the palette's) and its points' own;
 * [kind]: drawn as another kind than the chart's (a combo's line over columns); [sizes]: a bubble chart's bubbles.
 */
internal data class ChartSeries(val name: String, val values: List<Double?>, val color: Int? = null, val pointColors: Map<Int, Int> = emptyMap(),
    val kind: ChartKind? = null, val sizes: List<Double?> = emptyList(), /** on the second value axis, at the right */ val secondary: Boolean = false)

/**
 * A chart. Scatter: [categories] are the x values (numbers as text), each series the y values. [catTitle] and [valTitle]:
 * the axes' titles (Word's "Axis titles"; the category axis is the horizontal one, a bar chart's the vertical one);
 * [dataLabels]: each value written by its bar, point or slice.
 */
internal data class DocChart(val kind: ChartKind, val title: String?, val categories: List<String>, val series: List<ChartSeries>, val stacked: Boolean = false,
    val legend: Boolean = true, val catTitle: String? = null, val valTitle: String? = null, val dataLabels: Boolean = false)

internal object OfficeCharts {
    /**
     * A chart of a table's numbers (asked 2026-10-14: "select a table and make it a chart, without rewriting every cell"):
     * Word's way — the first row names the series, the first column the categories, each column of numbers a series.
     * A header that is itself a number, or a first column of numbers, is data, not names. Null when there is nothing to draw.
     */
    fun fromTable(t: DocTable): DocChart? {
        // Each cell at its column of the table's grid: a merged cell ("Total" over three) puts the next one where it belongs.
        val rows = t.rows.map { r -> ArrayList<String>().also { out -> r.cells.forEach { c ->
            out += c.paras.joinToString(" ") { it.text }.replace(OBJECT_MARK.toString(), "").trim()
            repeat(TableEdit.span(c) - 1) { out += "" }
        } } }.filter { r -> r.any { it.isNotEmpty() } }
        if (rows.isEmpty()) return null
        val width = rows.maxOf { it.size }
        fun cell(r: Int, c: Int) = rows.getOrNull(r)?.getOrNull(c).orEmpty()
        val namesRow = (1 until width).any { c -> cell(0, c).isNotEmpty() && number(cell(0, c)) == null }
        val first = if (namesRow) 1 else 0
        val labelsColumn = (first until rows.size).any { r -> cell(r, 0).isNotEmpty() && number(cell(r, 0)) == null }
        val from = if (labelsColumn) 1 else 0
        val categories = (first until rows.size).map { r -> if (labelsColumn) cell(r, 0) else "${r - first + 1}" }
        val series = (from until width).mapNotNull { c ->
            val values = (first until rows.size).map { r -> number(cell(r, c)) }
            if (values.all { it == null }) null else ChartSeries(if (namesRow) cell(0, c).ifEmpty { "Series ${c - from + 1}" } else "Series ${c - from + 1}", values)
        }
        if (series.isEmpty() || categories.isEmpty()) return null
        return DocChart(ChartKind.Column, null, categories, series)
    }

    /** A cell's number as people write it: "1.234,5" (Greek), "1,234.5", "12%", "€ 30", "-4"; null for words. */
    fun number(text: String): Double? {
        var s = text.replace(Regex("""[\s\u00A0%€$£]"""), "")
        if (s.isEmpty() || !Regex("""^[-+]?[\d.,]+$""").matches(s)) return null
        val dot = s.lastIndexOf('.'); val comma = s.lastIndexOf(',')
        s = when {
            dot >= 0 && comma >= 0 -> if (comma > dot) s.replace(".", "").replace(',', '.') else s.replace(",", "")
            comma >= 0 -> if (Regex("""^[-+]?\d{1,3}(,\d{3})+$""").matches(s)) s.replace(",", "") else s.replace(',', '.')
            dot >= 0 -> if (Regex("""^[-+]?\d{1,3}(\.\d{3}){2,}$""").matches(s)) s.replace(".", "") else s
            else -> s
        }
        return s.toDoubleOrNull()
    }

    private const val C = "http://schemas.openxmlformats.org/drawingml/2006/chart"
    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val CHART = "urn:oasis:names:tc:opendocument:xmlns:chart:1.0"
    private const val TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
    private const val OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    private const val STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    private const val DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
    private const val SVG = "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"

    /** Office's theme colours (accent 1–6): series without a colour of their own take these in turn. */
    val PALETTE = listOf(0x4472C4, 0xED7D31, 0xA5A5A5, 0xFFC000, 0x5B9BD5, 0x70AD47)

    private fun Element.all(ns: String, name: String): List<Element> = getElementsByTagNameNS(ns, name).let { l -> (0 until l.length).map { l.item(it) as Element } }
    private fun Element.first(ns: String, name: String): Element? = getElementsByTagNameNS(ns, name).item(0) as Element?

    /** Word's (and PowerPoint's, Excel's) chart part: c:chartSpace. Null: a kind Writer does not draw. */
    fun readDrawingml(bytes: ByteArray): DocChart? = runCatching {
        val root = OfficeFormats.parse(bytes).documentElement
        val plot = root.first(C, "plotArea") ?: return null
        val types = mapOf("barChart" to ChartKind.Column, "bar3DChart" to ChartKind.Column, "lineChart" to ChartKind.Line, "line3DChart" to ChartKind.Line,
            "scatterChart" to ChartKind.Scatter, "areaChart" to ChartKind.Area, "area3DChart" to ChartKind.Area, "pieChart" to ChartKind.Pie,
            "pie3DChart" to ChartKind.Pie, "ofPieChart" to ChartKind.Pie, "doughnutChart" to ChartKind.Doughnut,
            "radarChart" to ChartKind.Radar, "bubbleChart" to ChartKind.Bubble)
        // A combo has more than one: the first gives the chart's kind, the others their series' own.
        val groups = plot.kids().filter { it.namespaceURI == C && it.localName in types }
        val group = groups.firstOrNull() ?: return null
        fun kindOf(g: Element) = types.getValue(g.localName).let { if (it == ChartKind.Column && g.kid(C, "barDir")?.getAttribute("val") == "bar") ChartKind.Bar else it }
        val kind = kindOf(group)
        // A group on the second value axis: one that crosses at the far end (Excel puts it at the right).
        val axisById = plot.kids().filter { it.localName == "valAx" || it.localName == "catAx" || it.localName == "dateAx" }.associateBy { it.kid(C, "axId")?.getAttribute("val") }
        fun secondary(g: Element) = g !== group && g.kids().filter { it.localName == "axId" }.mapNotNull { axisById[it.getAttribute("val")] }
            .any { it.localName == "valAx" && (it.kid(C, "axPos")?.getAttribute("val") == "r" || it.kid(C, "crosses")?.getAttribute("val") == "max") }
        val stacked = group.kid(C, "grouping")?.getAttribute("val").let { it == "stacked" || it == "percentStacked" }
        fun points(e: Element?): Map<Int, String> = e?.all(C, "pt")?.associate { p -> p.getAttribute("idx").toInt() to (p.kid(C, "v")?.textContent ?: "") }.orEmpty()
        fun color(spPr: Element?): Int? {
            val fill = spPr?.kids()?.firstOrNull { it.localName == "solidFill" } ?: spPr?.kid(A, "ln")?.kid(A, "solidFill") ?: return null
            fill.kid(A, "srgbClr")?.getAttribute("val")?.toIntOrNull(16)?.let { return it }
            return fill.kid(A, "schemeClr")?.getAttribute("val")?.removePrefix("accent")?.toIntOrNull()?.let { PALETTE.getOrNull(it - 1) }
        }
        var categories = emptyList<String>()
        val series = groups.flatMap { g -> g.kids().filter { it.localName == "ser" }.map { g to it } }.map { (g, s) ->
            val cat = points(s.kid(C, "cat") ?: s.kid(C, "xVal"))
            val vals = points(s.kid(C, "val") ?: s.kid(C, "yVal"))
            val n = maxOf((cat.keys.maxOrNull() ?: -1), (vals.keys.maxOrNull() ?: -1)) + 1
            if (categories.size < n) categories = (0 until n).map { cat[it] ?: "${it + 1}" }
            val spPr = s.kid(C, "spPr")
            ChartSeries(s.kid(C, "tx")?.all(C, "v")?.firstOrNull()?.textContent ?: s.kid(C, "tx")?.textContent?.trim().orEmpty(),
                (0 until n).map { vals[it]?.toDoubleOrNull() },
                color(spPr),
                s.kids().filter { it.localName == "dPt" }.mapNotNull { d -> d.kid(C, "idx")?.getAttribute("val")?.toIntOrNull()?.let { i -> color(d.kid(C, "spPr"))?.let { i to it } } }.toMap(),
                kindOf(g).takeIf { g !== group && it != kind },
                points(s.kid(C, "bubbleSize")).let { b -> (0 until n).map { b[it]?.toDoubleOrNull() } }.takeIf { kind == ChartKind.Bubble }.orEmpty(),
                secondary(g))
        }
        if (series.isEmpty()) return null
        fun titleOf(e: Element?) = e?.kid(C, "title")?.all(A, "t")?.joinToString("") { it.textContent }?.takeIf { it.isNotBlank() }
        // its own title; else, a chart of one series is titled with its name (Office's automatic title) unless that is deleted
        val title = root.first(C, "chart")?.kid(C, "title")?.all(A, "t")?.joinToString("") { it.textContent }?.takeIf { it.isNotBlank() }
            ?: series.singleOrNull()?.name?.takeIf { it.isNotBlank() && root.first(C, "chart")?.kid(C, "title") == null && root.first(C, "autoTitleDeleted")?.getAttribute("val").let { it != "1" && it != "true" } }
        // The axes' titles: the category axis (a scatter's: its x axis, at the bottom), then the value axis.
        val axes = plot.kids().filter { it.localName == "catAx" || it.localName == "valAx" || it.localName == "dateAx" }
        val catAx = axes.firstOrNull { it.localName != "valAx" } ?: axes.firstOrNull { it.kid(C, "axPos")?.getAttribute("val") == "b" }
        val valAx = axes.firstOrNull { it.localName == "valAx" && it !== catAx }
        fun shows(d: Element?) = d?.kid(C, "showVal")?.getAttribute("val").let { it == "1" || it == "true" }
        val labels = shows(group.kid(C, "dLbls")) || group.kids().any { it.localName == "ser" && shows(it.kid(C, "dLbls")) }
        DocChart(kind, title, categories, series, stacked, root.first(C, "legend") != null, titleOf(catAx), titleOf(valAx), labels)
    }.getOrNull()

    /** LibreOffice's chart object: its content.xml (chart:chart, its data in its own table). */
    fun readOdf(bytes: ByteArray): DocChart? = runCatching {
        val root = OfficeFormats.parse(bytes).documentElement
        val chart = root.first(CHART, "chart") ?: return null
        val styles = root.all(STYLE, "style").associateBy { it.getAttributeNS(STYLE, "name") }
        fun props(style: String?, name: String) = styles[style]?.kids()?.firstOrNull { it.localName == name }
        val plot = chart.first(CHART, "plot-area")
        val plotProps = props(plot?.getAttributeNS(CHART, "style-name"), "chart-properties")
        var kind = when (chart.getAttributeNS(CHART, "class")) {
            "chart:bar" -> ChartKind.Column; "chart:line" -> ChartKind.Line; "chart:scatter" -> ChartKind.Scatter; "chart:area" -> ChartKind.Area
            "chart:circle" -> ChartKind.Pie; "chart:ring" -> ChartKind.Doughnut; "chart:radar", "chart:filled-radar" -> ChartKind.Radar
            "chart:bubble" -> ChartKind.Bubble; else -> return null
        }
        if (kind == ChartKind.Column && plotProps?.getAttributeNS(CHART, "vertical") == "true") kind = ChartKind.Bar
        val stacked = plotProps?.let { it.getAttributeNS(CHART, "stacked") == "true" || it.getAttributeNS(CHART, "percentage") == "true" } == true
        val table = chart.first(TABLE, "table") ?: return null
        fun cells(row: Element) = row.all(TABLE, "table-cell").flatMap { c -> List(c.getAttributeNS(TABLE, "number-columns-repeated").toIntOrNull() ?: 1) { c } }
        val header = table.first(TABLE, "table-header-rows")?.first(TABLE, "table-row")?.let { cells(it) }.orEmpty()
        val rows = (table.first(TABLE, "table-rows") ?: table).kids().filter { it.localName == "table-row" }.map { cells(it) }
        // A cell's words are its paragraphs: LibreOffice keeps the cells' range there too, in a draw:g's svg:desc.
        val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
        fun words(c: Element?) = c?.all(TEXT, "p")?.joinToString("\n") { it.textContent }?.trim().orEmpty()
        val categories = rows.map { words(it.firstOrNull()) }
        val colors = plot?.kids()?.filter { it.localName == "series" }?.map { s ->
            props(s.getAttributeNS(CHART, "style-name"), "graphic-properties")?.let { g ->
                (if (kind == ChartKind.Line || kind == ChartKind.Radar || s.getAttributeNS(CHART, "class") == "chart:line") g.getAttributeNS(SVG, "stroke-color") else g.getAttributeNS(DRAW, "fill-color")).removePrefix("#").toIntOrNull(16)
            } }.orEmpty()
        val count = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0) - 1
        if (count <= 0) return null
        fun column(k: Int) = rows.map { r -> r.getOrNull(k)?.let { it.getAttributeNS(OFFICE, "value").toDoubleOrNull() ?: words(it).toDoubleOrNull() } }
        // a combo: a series of another kind says so (chart:class="chart:line" in a bar chart)
        val kinds = plot?.kids()?.filter { it.localName == "series" }?.map { s ->
            when (s.getAttributeNS(CHART, "class")) { "chart:line" -> ChartKind.Line; "chart:bar" -> ChartKind.Column; "chart:area" -> ChartKind.Area; else -> null }
                ?.takeIf { it != kind && !(it == ChartKind.Column && kind == ChartKind.Bar) }
        }.orEmpty()
        // a bubble chart's table: x, y and the size for each series, its name over the size
        if (kind == ChartKind.Bubble) {
            val bubbles = (0 until count / 3).map { k -> ChartSeries(words(header.getOrNull(3 * k + 3)), column(3 * k + 2), colors.getOrNull(k), sizes = column(3 * k + 3)) }
            if (bubbles.isEmpty()) return null
            return@runCatching DocChart(kind, chart.kid(CHART, "title")?.textContent?.trim()?.takeIf { it.isNotEmpty() }, column(1).map { it?.let { v -> ChartWriter.num(v) } ?: "" },
                bubbles, legend = chart.first(CHART, "legend") != null)
        }
        val second = plot?.kids()?.filter { it.localName == "series" }?.map { it.getAttributeNS(CHART, "attached-axis") == "secondary-y" }.orEmpty()
        val series = (1..count).map { k -> ChartSeries(words(header.getOrNull(k)), column(k), colors.getOrNull(k - 1), kind = kinds.getOrNull(k - 1), secondary = second.getOrNull(k - 1) == true) }
        fun axisTitle(dim: String) = plot?.kids()?.firstOrNull { it.localName == "axis" && it.getAttributeNS(CHART, "dimension") == dim }
            ?.kids()?.firstOrNull { it.localName == "title" }?.textContent?.trim()?.takeIf { it.isNotEmpty() }
        val labels = plot?.kids()?.filter { it.localName == "series" }?.any { s ->
            props(s.getAttributeNS(CHART, "style-name"), "chart-properties")?.getAttributeNS(CHART, "data-label-number").let { it == "value" || it == "percentage" } } == true
        DocChart(kind, chart.kid(CHART, "title")?.textContent?.trim()?.takeIf { it.isNotEmpty() }, categories, series, stacked, chart.first(CHART, "legend") != null,
            axisTitle("x"), axisTitle("y"), labels)
    }.getOrNull()
}

/** New charts, written as each program writes its own. */
internal object ChartWriter {
    private fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    fun num(v: Double) = if (v == Math.rint(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
    private fun hex(c: Int) = "%06X".format(c and 0xFFFFFF)
    private fun colorOf(ch: DocChart, k: Int) = ch.series[k].color ?: OfficeCharts.PALETTE[k % OfficeCharts.PALETTE.size]

    /**
     * Word's chart part (c:chartSpace). With a [workbook] (Word's own way): its numbers point at the cells of the
     * embedded Excel workbook ([workbook]'s, Sheet1: A1 empty, series names along row 1, categories down column A),
     * each with its values cached — so Word's own Edit Data opens the same numbers; without, the numbers in the part.
     */
    fun drawingml(ch: DocChart, workbook: Boolean = false): String {
        val round = ch.kind == ChartKind.Pie || ch.kind == ChartKind.Doughnut
        val n = ch.categories.size
        val sb = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><c:chartSpace xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><c:roundedCorners val="0"/><c:chart>""")
        ch.title?.let { sb.append("""<c:title><c:tx><c:rich><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>${esc(it)}</a:t></a:r></a:p></c:rich></c:tx><c:overlay val="0"/></c:title>""") }
        sb.append("""<c:autoTitleDeleted val="${if (ch.title == null) 1 else 0}"/><c:plotArea><c:layout/>""")
        // shortcut: a bubble chart is written as a scatter of its points (no sizes); write bubbleChart if one is made here
        val scatter = ch.kind == ChartKind.Scatter || ch.kind == ChartKind.Bubble
        val tag = when (ch.kind) { ChartKind.Column, ChartKind.Bar -> "barChart"; ChartKind.Line -> "lineChart"; ChartKind.Area -> "areaChart"; ChartKind.Pie -> "pieChart"; ChartKind.Doughnut -> "doughnutChart"; ChartKind.Scatter, ChartKind.Bubble -> "scatterChart"; ChartKind.Radar -> "radarChart" }
        sb.append("<c:$tag>")
        if (ch.kind == ChartKind.Column || ch.kind == ChartKind.Bar) sb.append("""<c:barDir val="${if (ch.kind == ChartKind.Bar) "bar" else "col"}"/>""")
        if (scatter) sb.append("""<c:scatterStyle val="lineMarker"/>""") // Word's "Scatter": markers, no lines (the series' line is none)
        else if (ch.kind == ChartKind.Radar) sb.append("""<c:radarStyle val="marker"/>""")
        else if (!round && ch.kind != ChartKind.Radar) sb.append("""<c:grouping val="${if (ch.stacked) "stacked" else if (ch.kind == ChartKind.Column || ch.kind == ChartKind.Bar) "clustered" else "standard"}"/>""")
        sb.append("""<c:varyColors val="${if (round) 1 else 0}"/>""")
        ch.series.forEachIndexed { k, s ->
            val fill = """<a:solidFill><a:srgbClr val="${hex(colorOf(ch, k))}"/></a:solidFill>"""
            val col = column(k + 1); val last = ch.categories.size + 1
            sb.append("""<c:ser><c:idx val="$k"/><c:order val="$k"/><c:tx>${if (workbook) """<c:strRef><c:f>Sheet1!$$col$1</c:f><c:strCache><c:ptCount val="1"/><c:pt idx="0"><c:v>${esc(s.name)}</c:v></c:pt></c:strCache></c:strRef>""" else "<c:v>${esc(s.name)}</c:v>"}</c:tx>""")
            sb.append(when {
                ch.kind == ChartKind.Line || ch.kind == ChartKind.Radar -> """<c:spPr><a:ln w="28575" cap="rnd">$fill<a:round/></a:ln></c:spPr><c:marker><c:symbol val="circle"/><c:size val="5"/><c:spPr>$fill</c:spPr></c:marker>"""
                scatter -> """<c:spPr><a:ln w="19050"><a:noFill/></a:ln></c:spPr><c:marker><c:symbol val="circle"/><c:size val="5"/><c:spPr>$fill</c:spPr></c:marker>"""
                round -> "" // a slice each: Word colours them (varyColors); the points' own below
                else -> """<c:spPr>$fill</c:spPr>"""
            })
            if (ch.kind == ChartKind.Bar || ch.kind == ChartKind.Column) sb.append("""<c:invertIfNegative val="0"/>""")
            if (round) for (i in 0 until n) sb.append("""<c:dPt><c:idx val="$i"/><c:bubble3D val="0"/><c:spPr><a:solidFill><a:srgbClr val="${hex(s.pointColors[i] ?: OfficeCharts.PALETTE[i % OfficeCharts.PALETTE.size])}"/></a:solidFill><a:ln w="12700"><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill></a:ln></c:spPr></c:dPt>""")
            if (ch.dataLabels) sb.append("""<c:dLbls><c:spPr><a:noFill/><a:ln><a:noFill/></a:ln></c:spPr><c:showLegendKey val="0"/><c:showVal val="1"/><c:showCatName val="0"/><c:showSerName val="0"/><c:showPercent val="0"/><c:showBubbleSize val="0"/></c:dLbls>""")
            if (scatter) {
                // x: the categories as numbers (one that is not is left out, as Word leaves a blank).
                sb.append(numbers("xVal", "Sheet1!\$A\$2:\$A\$$last", ch.categories.map { OfficeCharts.number(it) }, workbook))
                sb.append(numbers("yVal", "Sheet1!$$col$2:$$col$$last", s.values.take(n), workbook))
            } else {
                if (workbook) sb.append("""<c:cat><c:strRef><c:f>Sheet1!${'$'}A${'$'}2:${'$'}A${'$'}$last</c:f><c:strCache><c:ptCount val="$n"/>""") else sb.append("""<c:cat><c:strLit><c:ptCount val="$n"/>""")
                ch.categories.forEachIndexed { i, c -> sb.append("""<c:pt idx="$i"><c:v>${esc(c)}</c:v></c:pt>""") }
                sb.append(if (workbook) "</c:strCache></c:strRef></c:cat>" else "</c:strLit></c:cat>")
                sb.append(numbers("val", "Sheet1!$$col$2:$$col$$last", s.values.take(n), workbook))
            }
            if (ch.kind == ChartKind.Line || scatter) sb.append("""<c:smooth val="0"/>""")
            sb.append("</c:ser>")
        }
        when (ch.kind) {
            ChartKind.Column, ChartKind.Bar -> sb.append("""<c:gapWidth val="150"/>${if (ch.stacked) """<c:overlap val="100"/>""" else ""}<c:axId val="50010001"/><c:axId val="50010002"/>""")
            ChartKind.Line -> sb.append("""<c:marker val="1"/><c:axId val="50010001"/><c:axId val="50010002"/>""")
            ChartKind.Area, ChartKind.Scatter, ChartKind.Bubble, ChartKind.Radar -> sb.append("""<c:axId val="50010001"/><c:axId val="50010002"/>""")
            ChartKind.Pie -> sb.append("""<c:firstSliceAng val="0"/>""")
            ChartKind.Doughnut -> sb.append("""<c:firstSliceAng val="0"/><c:holeSize val="50"/>""")
        }
        sb.append("</c:$tag>")
        if (!round) {
            val (catPos, valPos) = if (ch.kind == ChartKind.Bar) "l" to "b" else "b" to "l"
            val line = """<c:spPr><a:ln w="9525"><a:solidFill><a:srgbClr val="D9D9D9"/></a:solidFill></a:ln></c:spPr>"""
            fun title(t: String?) = t?.let { """<c:title><c:tx><c:rich><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>${esc(it)}</a:t></a:r></a:p></c:rich></c:tx><c:overlay val="0"/></c:title>""" }.orEmpty()
            // A scatter's x axis is one of values too.
            val xAx = if (scatter) "valAx" else "catAx"
            sb.append("""<c:$xAx><c:axId val="50010001"/><c:scaling><c:orientation val="minMax"/></c:scaling><c:delete val="0"/><c:axPos val="$catPos"/>${title(ch.catTitle)}<c:numFmt formatCode="General" sourceLinked="0"/><c:majorTickMark val="none"/><c:minorTickMark val="none"/><c:tickLblPos val="nextTo"/>$line<c:crossAx val="50010002"/><c:crosses val="autoZero"/>${if (scatter) """<c:crossBetween val="midCat"/>""" else """<c:auto val="1"/><c:lblAlgn val="ctr"/><c:lblOffset val="100"/><c:noMultiLvlLbl val="0"/>"""}</c:$xAx>""")
            sb.append("""<c:valAx><c:axId val="50010002"/><c:scaling><c:orientation val="minMax"/></c:scaling><c:delete val="0"/><c:axPos val="$valPos"/><c:majorGridlines>$line</c:majorGridlines>${title(ch.valTitle)}<c:numFmt formatCode="General" sourceLinked="0"/><c:majorTickMark val="none"/><c:minorTickMark val="none"/><c:tickLblPos val="nextTo"/><c:spPr><a:ln><a:noFill/></a:ln></c:spPr><c:crossAx val="50010001"/><c:crosses val="autoZero"/><c:crossBetween val="${if (ch.kind == ChartKind.Line || scatter) "midCat" else "between"}"/></c:valAx>""")
        }
        sb.append("</c:plotArea>")
        if (ch.legend) sb.append("""<c:legend><c:legendPos val="b"/><c:overlay val="0"/></c:legend>""")
        sb.append("""<c:plotVisOnly val="1"/><c:dispBlanksAs val="gap"/></c:chart><c:txPr><a:bodyPr/><a:lstStyle/><a:p><a:pPr><a:defRPr sz="900"><a:solidFill><a:srgbClr val="595959"/></a:solidFill></a:defRPr></a:pPr><a:endParaRPr lang="en-US"/></a:p></c:txPr>""")
        // The workbook its numbers are in (the chart part's relationship rId1).
        if (workbook) sb.append("""<c:externalData r:id="rId1"><c:autoUpdate val="0"/></c:externalData>""")
        sb.append("</c:chartSpace>")
        return sb.toString()
    }
    /** A series' numbers: the workbook's cells [ref] with their values cached, or the values themselves. */
    private fun numbers(tag: String, ref: String, values: List<Double?>, workbook: Boolean): String {
        val pts = values.mapIndexedNotNull { i, v -> v?.let { """<c:pt idx="$i"><c:v>${num(it)}</c:v></c:pt>""" } }.joinToString("")
        val body = """<c:formatCode>General</c:formatCode><c:ptCount val="${values.size}"/>$pts"""
        return if (workbook) "<c:$tag><c:numRef><c:f>$ref</c:f><c:numCache>$body</c:numCache></c:numRef></c:$tag>" else "<c:$tag><c:numLit>$body</c:numLit></c:$tag>"
    }
    /** A spreadsheet column's letters: 0 → A, 26 → AA. */
    fun column(k: Int): String = if (k < 26) ('A' + k).toString() else column(k / 26 - 1) + ('A' + k % 26)

    /**
     * The chart's data as Word embeds it: a small Excel workbook (.xlsx), Sheet1 — A1 empty, the series' names along
     * row 1, the categories down column A (numbers for a scatter's x), the values beside them.
     */
    fun workbook(ch: DocChart): ByteArray {
        fun cell(ref: String, v: Any?): String = when (v) {
            null -> ""
            is Double -> """<c r="$ref"><v>${num(v)}</v></c>"""
            else -> """<c r="$ref" t="inlineStr"><is><t xml:space="preserve">${esc(v.toString())}</t></is></c>"""
        }
        val rows = StringBuilder()
        rows.append("""<row r="1">""" + ch.series.mapIndexed { k, s -> cell("${column(k + 1)}1", s.name) }.joinToString("") + "</row>")
        ch.categories.forEachIndexed { i, c ->
            val r = i + 2
            val first = if (ch.kind == ChartKind.Scatter) OfficeCharts.number(c) ?: c else c
            rows.append("""<row r="$r">""" + cell("A$r", first) + ch.series.mapIndexed { k, s -> cell("${column(k + 1)}$r", s.values.getOrNull(i)) }.joinToString("") + "</row>")
        }
        val sheet = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$rows</sheetData></worksheet>"""
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z ->
            fun put(name: String, xml: String) { z.putNextEntry(java.util.zip.ZipEntry(name)); z.write(xml.toByteArray()); z.closeEntry() }
            put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            put("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>""")
            put("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")
            put("xl/worksheets/sheet1.xml", sheet)
        }
        return out.toByteArray()
    }

    /** LibreOffice's chart object: its content.xml, the data in its own table (the first column the categories). */
    fun odf(ch: DocChart, widthPt: Float, heightPt: Float): String {
        val n = ch.categories.size
        val round = ch.kind == ChartKind.Pie || ch.kind == ChartKind.Doughnut
        val cls = when (ch.kind) { ChartKind.Column, ChartKind.Bar -> "chart:bar"; ChartKind.Line -> "chart:line"; ChartKind.Area -> "chart:area"; ChartKind.Pie -> "chart:circle"; ChartKind.Doughnut -> "chart:ring"; ChartKind.Scatter, ChartKind.Bubble -> "chart:scatter"; ChartKind.Radar -> "chart:radar" }
        val scatter = ch.kind == ChartKind.Scatter || ch.kind == ChartKind.Bubble
        fun col(k: Int): String { var i = k; var s = ""; do { s = ('A' + i % 26) + s; i = i / 26 - 1 } while (i >= 0); return s }
        val last = col(ch.series.size)
        val sb = StringBuilder("""<?xml version="1.0" encoding="UTF-8"?><office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" xmlns:xlink="http://www.w3.org/1999/xlink" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" xmlns:chart="urn:oasis:names:tc:opendocument:xmlns:chart:1.0" office:version="1.3"><office:automatic-styles>""")
        sb.append("""<style:style style:name="pa" style:family="chart"><style:chart-properties chart:vertical="${ch.kind == ChartKind.Bar}" chart:stacked="${ch.stacked}"${if (ch.kind == ChartKind.Line) """ chart:symbol-type="automatic"""" else ""}/></style:style>""")
        ch.series.forEachIndexed { k, _ -> val c = "#" + hex(colorOf(ch, k))
            // Data labels, and a scatter's points only (LibreOffice's "Points only": no line, a symbol each).
            val props = listOfNotNull(""" chart:symbol-type="automatic"""".takeIf { scatter }, """ chart:data-label-number="value"""".takeIf { ch.dataLabels }).joinToString("")
            sb.append("""<style:style style:name="s$k" style:family="chart">${if (props.isEmpty()) "" else "<style:chart-properties$props/>"}<style:graphic-properties draw:fill-color="$c" svg:stroke-color="$c"${if (ch.kind == ChartKind.Line) """ svg:stroke-width="0.08cm"""" else """ draw:stroke="none""""}/></style:style>""") }
        if (round) for (i in 0 until n) sb.append("""<style:style style:name="p$i" style:family="chart"><style:graphic-properties draw:fill-color="#${hex(ch.series.getOrNull(0)?.pointColors?.get(i) ?: OfficeCharts.PALETTE[i % OfficeCharts.PALETTE.size])}" svg:stroke-color="#FFFFFF"/></style:style>""")
        sb.append("""<style:style style:name="axis" style:family="chart"><style:chart-properties chart:display-label="true"/><style:graphic-properties svg:stroke-color="#D9D9D9"/><style:text-properties fo:font-size="9pt" fo:color="#595959"/></style:style>""")
        // A pie clockwise, as LibreOffice writes its own. ponytail: LibreOffice 7 still draws Writer's .odt pies anticlockwise
        // (its own it draws clockwise — something else in its files decides it); the slices and their colours are right.
        sb.append("""<style:style style:name="round" style:family="chart"><style:chart-properties chart:reverse-direction="true"/></style:style>""")
        sb.append("""<style:style style:name="grid" style:family="chart"><style:graphic-properties svg:stroke-color="#D9D9D9"/></style:style>""")
        sb.append("""<style:style style:name="wall" style:family="chart"><style:graphic-properties draw:stroke="none" draw:fill="none"/></style:style>""")
        sb.append("""</office:automatic-styles><office:body><office:chart><chart:chart svg:width="${widthPt}pt" svg:height="${heightPt}pt" chart:class="$cls">""")
        ch.title?.let { sb.append("<chart:title><text:p>${esc(it)}</text:p></chart:title>") }
        if (ch.legend) sb.append("""<chart:legend chart:legend-position="bottom" style:legend-expansion="wide"/>""")
        sb.append("""<chart:plot-area chart:style-name="pa" table:cell-range-address="local-table.${'$'}A${'$'}1:.${'$'}$last${'$'}${n + 1}" chart:data-source-has-labels="both">""")
        fun title(t: String?) = t?.let { "<chart:title><text:p>${esc(it)}</text:p></chart:title>" }.orEmpty()
        val xRange = "local-table.${'$'}A${'$'}2:.${'$'}A${'$'}${n + 1}"
        // A scatter's x axis is of numbers, not categories: its x values are each series' domain, below.
        if (!round) sb.append("""<chart:axis chart:dimension="x" chart:name="primary-x" chart:style-name="axis">${title(ch.catTitle)}${if (scatter) "" else """<chart:categories table:cell-range-address="$xRange"/>"""}</chart:axis><chart:axis chart:dimension="y" chart:name="primary-y" chart:style-name="axis">${title(ch.valTitle)}<chart:grid chart:style-name="grid" chart:class="major"/></chart:axis>""")
        else sb.append("""<chart:axis chart:dimension="x" chart:name="primary-x" chart:style-name="round"><chart:categories table:cell-range-address="local-table.${'$'}A${'$'}2:.${'$'}A${'$'}${n + 1}"/></chart:axis><chart:axis chart:dimension="y" chart:name="primary-y"/>""")
        ch.series.forEachIndexed { k, _ ->
            val c = col(k + 1)
            sb.append("""<chart:series chart:style-name="s$k" chart:values-cell-range-address="local-table.${'$'}$c${'$'}2:.${'$'}$c${'$'}${n + 1}" chart:label-cell-address="local-table.${'$'}$c${'$'}1" chart:class="$cls">""")
            if (scatter) sb.append("""<chart:domain table:cell-range-address="$xRange"/>""")
            if (round && k == 0) for (i in 0 until n) sb.append("""<chart:data-point chart:style-name="p$i"/>""") else sb.append("""<chart:data-point chart:repeated="$n"/>""")
            sb.append("</chart:series>")
        }
        sb.append("""<chart:wall chart:style-name="wall"/></chart:plot-area><table:table table:name="local-table"><table:table-header-columns><table:table-column/></table:table-header-columns><table:table-columns><table:table-column table:number-columns-repeated="${ch.series.size}"/></table:table-columns><table:table-header-rows><table:table-row><table:table-cell><text:p/></table:table-cell>""")
        ch.series.forEach { sb.append("""<table:table-cell office:value-type="string"><text:p>${esc(it.name)}</text:p></table:table-cell>""") }
        sb.append("</table:table-row></table:table-header-rows><table:table-rows>")
        ch.categories.forEachIndexed { i, cat ->
            val x = OfficeCharts.number(cat)?.takeIf { scatter }
            sb.append(if (x != null) """<table:table-row><table:table-cell office:value-type="float" office:value="${num(x)}"><text:p>${num(x)}</text:p></table:table-cell>"""
                else """<table:table-row><table:table-cell office:value-type="string"><text:p>${esc(cat)}</text:p></table:table-cell>""")
            ch.series.forEach { s -> val v = s.values.getOrNull(i)
                sb.append(if (v == null) "<table:table-cell/>" else """<table:table-cell office:value-type="float" office:value="${num(v)}"><text:p>${num(v)}</text:p></table:table-cell>""") }
            sb.append("</table:table-row>")
        }
        sb.append("</table:table-rows></table:table></chart:chart></office:chart></office:body></office:document-content>")
        return sb.toString()
    }
}

/**
 * A chart drawn in its bounds, which count in points: its title at the top, its legend at the foot, the plot between —
 * value gridlines and labels, the categories along the other axis. As Word draws a clustered or stacked chart, flat.
 */
internal class ChartDrawable(val chart: DocChart) : android.graphics.drawable.Drawable() {
    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.clipRect(0, 0, b.width(), b.height())
        drawChart(canvas, b.width().toFloat(), b.height().toFloat())
        canvas.restore()
    }

    private fun color(i: Int, own: Int?) = 0xFF000000.toInt() or (own ?: OfficeCharts.PALETTE[i % OfficeCharts.PALETTE.size])

    private fun drawChart(c: Canvas, w: Float, h: Float) {
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF595959.toInt(); textSize = 9f }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(0xFFFFFFFF.toInt())
        var top = 6f
        chart.title?.let { t ->
            val p = Paint(text).apply { textSize = 13f; color = 0xFF404040.toInt() }
            c.drawText(t, (w - p.measureText(t)) / 2, top + 12f, p); top += 20f
        }
        val round = chart.kind == ChartKind.Pie || chart.kind == ChartKind.Doughnut
        // The legend: the series (a pie: its slices), a square of each colour and its name, centred at the foot.
        val keys = if (round) chart.categories.mapIndexed { i, n -> n to color(i, chart.series[0].pointColors[i]) }
            else chart.series.mapIndexed { i, s -> s.name to color(i, s.color) }
        val legendW = keys.sumOf { (n, _) -> (text.measureText(n) + 18f).toDouble() }.toFloat()
        var x = ((w - legendW) / 2).coerceAtLeast(4f)
        val legendY = h - 8f
        if (chart.legend) for ((n, col) in keys) { fill.color = col; c.drawRect(x, legendY - 7f, x + 7f, legendY, fill); c.drawText(n, x + 10f, legendY, text); x += text.measureText(n) + 18f }
        val bottom = h - if (chart.legend) 20f else 6f
        if (round) { drawPie(c, w, top, bottom, fill); return }
        if (chart.kind == ChartKind.Radar) { drawRadar(c, w, top, bottom, text); return }

        // The values' range, from 0, gridlines at round steps.
        val first = chart.series.filter { !it.secondary }
        val sums = chart.categories.indices.map { i -> first.sumOf { it.values.getOrNull(i) ?: 0.0 } }
        val all = if (chart.stacked) sums else first.flatMap { it.values.filterNotNull() }
        val (lo, hi, step) = range(all)
        // The second value axis (a combo's line on its own scale): its numbers at the right.
        val second = chart.series.filter { it.secondary }.flatMap { it.values.filterNotNull() }
        val (lo2, hi2, step2) = range(second)
        val ticks2 = if (second.isEmpty() || chart.kind == ChartKind.Bar) emptyList() else generateSequence(lo2) { it + step2 }.takeWhile { it <= hi2 + step2 / 2 }.toList()
        val ticks = generateSequence(lo) { it + step }.takeWhile { it <= hi + step / 2 }.toList()
        val horizontal = chart.kind == ChartKind.Bar
        val scatter = chart.kind == ChartKind.Scatter || chart.kind == ChartKind.Bubble
        // A scatter's x values: numbers along their own axis.
        val xs = chart.categories.map { OfficeCharts.number(it) }
        val (xlo, xhi, xstep) = range(xs.filterNotNull())
        val xticks = if (scatter) generateSequence(xlo) { it + xstep }.takeWhile { it <= xhi + xstep / 2 }.toList() else emptyList()
        // The axes' titles: one beside the plot (turned), one under it.
        val sideTitle = if (horizontal) chart.catTitle else chart.valTitle
        val footTitle = if (horizontal) chart.valTitle else chart.catTitle
        val titlePaint = Paint(text).apply { textSize = 10f; color = 0xFF404040.toInt() }
        val catW = if (scatter) 0f else chart.categories.maxOfOrNull { text.measureText(it) } ?: 0f
        val left = 6f + (if (sideTitle != null) 14f else 0f) + if (horizontal) catW + 4f else ticks.maxOf { text.measureText(label(it)) } + 4f
        val plotTop = top + 4f + if (chart.dataLabels) 8f else 0f
        val plotBottom = bottom - (if (horizontal) 12f else 14f) - (if (footTitle != null) 14f else 0f)
        val right = w - 10f - (if (chart.dataLabels && horizontal) 18f else 0f) - (ticks2.maxOfOrNull { text.measureText(label(it)) + 4f } ?: 0f)
        footTitle?.let { t -> c.drawText(t, (left + right - titlePaint.measureText(t)) / 2, bottom - 3f, titlePaint) }
        sideTitle?.let { t -> c.save(); c.rotate(-90f, 6f, (plotTop + plotBottom) / 2); c.drawText(t, 6f - titlePaint.measureText(t) / 2, (plotTop + plotBottom) / 2 + 9f, titlePaint); c.restore() }
        val grid = Paint().apply { color = 0xFFD9D9D9.toInt(); strokeWidth = 0.5f }
        val n = chart.categories.size.coerceAtLeast(1)
        // Where value v stands along the value axis.
        fun at(v: Double) = if (horizontal) (left + (v - lo) / (hi - lo) * (right - left)).toFloat() else (plotBottom - (v - lo) / (hi - lo) * (plotBottom - plotTop)).toFloat()
        fun atX(v: Double) = (left + (v - xlo) / (xhi - xlo) * (right - left)).toFloat()
        fun at2(v: Double) = (plotBottom - (v - lo2) / (hi2 - lo2) * (plotBottom - plotTop)).toFloat()
        for (t in ticks2) c.drawText(label(t), right + 4f, at2(t) + 3f, text)
        for (t in ticks) {
            val p = at(t)
            if (horizontal) { c.drawLine(p, plotTop, p, plotBottom, grid); val s = label(t); c.drawText(s, p - text.measureText(s) / 2, plotBottom + 10f, text) }
            else { c.drawLine(left, p, right, p, grid); val s = label(t); c.drawText(s, left - 4f - text.measureText(s), p + 3f, text) }
        }
        // The categories: a band each, their names under it (every other one when they do not fit); a scatter: its x numbers.
        val band = (if (horizontal) plotBottom - plotTop else right - left) / n
        if (scatter) for (t in xticks) { val s = label(t); c.drawText(s, atX(t) - text.measureText(s) / 2, plotBottom + 11f, text) }
        else {
            val every = if (horizontal) 1 else Math.ceil((catW + 4f) / band.toDouble()).toInt().coerceAtLeast(1)
            chart.categories.forEachIndexed { i, name ->
                if (i % every != 0) return@forEachIndexed
                // A bar chart lists its first category at the bottom, as Word's.
                if (horizontal) c.drawText(name, left - 4f - text.measureText(name), plotBottom - (i + 0.5f) * band + 3f, text)
                else c.drawText(name, left + (i + 0.5f) * band - text.measureText(name) / 2, plotBottom + 11f, text)
            }
        }
        val zero = at(0.0.coerceIn(lo, hi))
        val ns = chart.series.size
        // Data labels: each value by its bar or point.
        fun value(v: Double, x: Float, y: Float, beside: Boolean = false) { if (!chart.dataLabels) return
            val s = label(v); if (beside) c.drawText(s, x + 3f, y + 3f, text) else c.drawText(s, x - text.measureText(s) / 2, y - 3f, text) }
        // Bars first, then lines over them: a combo's series each in its own kind (a line over columns).
        fun kindOf(s: ChartSeries) = s.kind ?: chart.kind
        val bars = chart.series.withIndex().filter { kindOf(it.value) == ChartKind.Column || kindOf(it.value) == ChartKind.Bar }
        val nb = bars.size.coerceAtLeast(1)
        val under = DoubleArray(n)
        bars.forEachIndexed { slot, (k, s) ->
            fill.color = color(k, s.color)
            s.values.forEachIndexed { i, v ->
                if (v == null || i >= n) return@forEachIndexed
                val gap = band * 0.2f
                val thick = if (chart.stacked) band - 2 * gap else (band - 2 * gap) / nb
                val from = if (chart.stacked) under[i] else 0.0
                val a = at(from); val e = at(from + v)
                s.pointColors[i]?.let { fill.color = 0xFF000000.toInt() or it }
                // Bars: the first series at the bottom of its band, as Word's.
                if (horizontal) { val y1 = plotBottom - i * band - gap - (if (chart.stacked) 0 else slot) * thick; c.drawRect(minOf(a, e), y1 - thick, maxOf(a, e), y1, fill)
                    if (chart.stacked) value(v, (a + e) / 2, y1 - thick / 2 + 6f) else value(v, maxOf(a, e), y1 - thick / 2, beside = true) }
                else { val x0 = left + i * band + gap + (if (chart.stacked) 0 else slot) * thick; c.drawRect(x0, minOf(a, e), x0 + thick, maxOf(a, e), fill)
                    value(v, x0 + thick / 2, if (chart.stacked) (a + e) / 2 + 6f else minOf(a, e)) }
                fill.color = color(k, s.color)
                if (chart.stacked) under[i] += v
            }
        }
        val lineUnder = DoubleArray(n)
        // a bubble's size: its area as its number, the largest a quarter of the plot across (Excel's 100%)
        val biggest = chart.series.flatMap { it.sizes.filterNotNull() }.maxOrNull()?.takeIf { it > 0 }
        val bubbleR = minOf(right - left, plotBottom - plotTop) / 8f
        chart.series.forEachIndexed { k, s ->
            val kind = kindOf(s)
            if (kind == ChartKind.Column || kind == ChartKind.Bar) return@forEachIndexed
            val col = color(k, s.color)
            val stack = chart.stacked && s.kind == null
            val pts = s.values.mapIndexedNotNull { i, v -> v?.takeIf { i < n && (!scatter || xs[i] != null) }?.let { i to (if (stack) lineUnder[i] + it else it) } }
            fun px(i: Int) = if (scatter) atX(xs[i]!!) else left + (i + 0.5f) * band
            fun y(v: Double) = if (s.secondary && ticks2.isNotEmpty()) at2(v) else at(v)
            val path = android.graphics.Path()
            pts.forEachIndexed { j, (i, v) -> if (j == 0) path.moveTo(px(i), y(v)) else path.lineTo(px(i), y(v)) }
            if (kind == ChartKind.Area && pts.isNotEmpty()) {
                path.lineTo(px(pts.last().first), zero); path.lineTo(px(pts.first().first), zero); path.close()
                fill.color = col; fill.alpha = 200; fill.style = Paint.Style.FILL; c.drawPath(path, fill); fill.alpha = 255
            } else if (kind == ChartKind.Bubble) {
                fill.color = col
                for ((i, v) in pts) { val size = s.sizes.getOrNull(i) ?: continue; if (biggest == null || size <= 0) continue
                    c.drawCircle(px(i), y(v), (bubbleR * Math.sqrt(size / biggest)).toFloat().coerceAtLeast(1.5f), fill) }
            } else {
                // A scatter: its points alone, as Word's.
                if (!scatter) c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; style = Paint.Style.STROKE; strokeWidth = 2f; strokeJoin = Paint.Join.ROUND })
                fill.color = col; for ((i, v) in pts) c.drawCircle(px(i), y(v), if (scatter) 3f else 2.5f, fill)
            }
            for ((i, v) in pts) value(s.values[i]!!, px(i), y(v) - 2f)
            if (stack) s.values.forEachIndexed { i, v -> if (i < n) lineUnder[i] += v ?: 0.0 }
        }
        // The axis the bars stand on.
        val axis = Paint().apply { color = 0xFFBFBFBF.toInt(); strokeWidth = 0.75f }
        if (horizontal) c.drawLine(zero, plotTop, zero, plotBottom, axis) else c.drawLine(left, zero, right, zero, axis)
    }

    private fun label(v: Double) = if (v == Math.rint(v)) "%d".format(v.toLong()) else "%.2f".format(v).trimEnd('0').trimEnd('.', ',')
    /** Low, high and a round step for [values], from 0 (a scatter's x too). */
    private fun range(values: List<Double>): Triple<Double, Double, Double> {
        val max = (values.maxOrNull() ?: 1.0).coerceAtLeast(0.0); val min = (values.minOrNull() ?: 0.0).coerceAtMost(0.0)
        val step = niceStep((max - min).takeIf { it > 0 } ?: 1.0)
        val lo = Math.floor(min / step) * step
        return Triple(lo, maxOf(Math.ceil(max / step) * step, lo + step), step) // never empty: all zeros still have a scale
    }

    /**
     * A radar: a spoke for each category from the top round clockwise, a ring at each step of the values, each series
     * a closed line with its points (as Excel's and LibreOffice's "radar with markers").
     */
    private fun drawRadar(c: Canvas, w: Float, top: Float, bottom: Float, text: Paint) {
        val n = chart.categories.size.takeIf { it >= 3 } ?: return
        val (lo, hi, step) = range(chart.series.flatMap { it.values.filterNotNull() })
        val labelH = 12f
        val r = (minOf(w - 2 * (chart.categories.maxOf { text.measureText(it) } + 8f), bottom - top - 2 * labelH) / 2).coerceAtLeast(8f)
        val cx = w / 2; val cy = (top + bottom) / 2
        fun point(i: Int, v: Double): Pair<Float, Float> {
            val a = Math.toRadians(-90.0 + 360.0 * i / n); val d = ((v - lo) / (hi - lo)).toFloat() * r
            return cx + d * Math.cos(a).toFloat() to cy + d * Math.sin(a).toFloat()
        }
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD9D9D9.toInt(); style = Paint.Style.STROKE; strokeWidth = 0.6f }
        // the rings, the spokes, the steps' numbers up the first spoke
        generateSequence(lo + step) { it + step }.takeWhile { it <= hi + step / 2 }.forEach { t ->
            val ring = android.graphics.Path()
            for (i in 0 until n) { val (x, y) = point(i, t); if (i == 0) ring.moveTo(x, y) else ring.lineTo(x, y) }
            ring.close(); c.drawPath(ring, grid)
            val s = label(t); c.drawText(s, cx + 2f, point(0, t).second + 3f, Paint(text).apply { textSize = 8f })
        }
        chart.categories.forEachIndexed { i, name ->
            val (x, y) = point(i, hi); c.drawLine(cx, cy, x, y, grid)
            val a = Math.toRadians(-90.0 + 360.0 * i / n)
            val tw = text.measureText(name)
            val lx = x + (Math.cos(a) * 6).toFloat() - tw / 2 + (Math.cos(a) * tw / 2).toFloat()
            c.drawText(name, lx, y + (Math.sin(a) * 8).toFloat() + 3f, text)
        }
        chart.series.forEachIndexed { k, s ->
            val col = color(k, s.color)
            val line = android.graphics.Path(); var first = true
            s.values.forEachIndexed { i, v -> if (v == null || i >= n) return@forEachIndexed; val (x, y) = point(i, v); if (first) line.moveTo(x, y) else line.lineTo(x, y); first = false }
            line.close()
            c.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; style = Paint.Style.STROKE; strokeWidth = 1.5f; strokeJoin = Paint.Join.ROUND })
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col }
            s.values.forEachIndexed { i, v -> if (v != null && i < n) { val (x, y) = point(i, v); c.drawCircle(x, y, 2f, dot)
                if (chart.dataLabels) c.drawText(label(v), x + 3f, y - 3f, text) } }
        }
    }

    /** A pie (or doughnut) of the first series, from twelve o'clock round clockwise, as Word's. */
    private fun drawPie(c: Canvas, w: Float, top: Float, bottom: Float, fill: Paint) {
        val s = chart.series[0]
        val total = s.values.sumOf { (it ?: 0.0).coerceAtLeast(0.0) }.takeIf { it > 0 } ?: return
        val r = (minOf(w - 20f, bottom - top - 8f) / 2).coerceAtLeast(4f)
        val cx = w / 2; val cy = (top + bottom) / 2
        val oval = android.graphics.RectF(cx - r, cy - r, cx + r, cy + r)
        var start = -90f
        s.values.forEachIndexed { i, v ->
            val sweep = ((v ?: 0.0).coerceAtLeast(0.0) / total * 360).toFloat()
            fill.color = color(i, s.pointColors[i])
            c.drawArc(oval, start, sweep, true, fill)
            start += sweep
        }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f }
        start = -90f
        s.values.forEach { v -> val sweep = ((v ?: 0.0).coerceAtLeast(0.0) / total * 360).toFloat(); c.drawArc(oval, start, sweep, true, edge); start += sweep }
        if (chart.kind == ChartKind.Doughnut) { fill.color = 0xFFFFFFFF.toInt(); c.drawCircle(cx, cy, r * 0.5f, fill) }
        // Data labels: each slice's value in it (a doughnut's in its ring).
        if (chart.dataLabels) {
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 9f; isFakeBoldText = true }
            val at = if (chart.kind == ChartKind.Doughnut) 0.75f else 0.62f
            start = -90f
            s.values.forEach { v -> val sweep = ((v ?: 0.0).coerceAtLeast(0.0) / total * 360).toFloat()
                if (v != null && sweep > 0f) { val mid = Math.toRadians((start + sweep / 2).toDouble()); val t = label(v)
                    c.drawText(t, cx + (r * at * Math.cos(mid)).toFloat() - text.measureText(t) / 2, cy + (r * at * Math.sin(mid)).toFloat() + 3f, text) }
                start += sweep }
        }
    }

    /** A round step for about five gridlines: 1, 2 or 5 times a power of ten. */
    private fun niceStep(range: Double): Double {
        val raw = range / 5
        val p = Math.pow(10.0, Math.floor(Math.log10(raw)))
        return listOf(1.0, 2.0, 5.0, 10.0).map { it * p }.first { it >= raw }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
    @Deprecated("Drawable") override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
}
