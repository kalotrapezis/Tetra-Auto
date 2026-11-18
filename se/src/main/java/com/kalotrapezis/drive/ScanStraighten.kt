package com.kalotrapezis.drive

import android.graphics.Bitmap
import android.graphics.Matrix
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin

/*
 * The scanner's fine straightening, after the perspective warp and the 90° turns: a tilt of up to ±45° (by hand or
 * measured from the text lines) and a curved page flattened (a book's pages bending towards the spine), the bend
 * measured once from the lines and kept with the page as a grid, so every render is the same.
 */
internal object ScanStraighten {
    const val MAX_TILT = 45f
    /** The bend grid's cells across and down; the grid holds (GRID + 1)² vertical shifts, fractions of the height. */
    const val GRID = 16

    /** How much a [width]×[height] picture turned by [degrees] is enlarged so that no corner is left empty. */
    fun cover(width: Int, height: Int, degrees: Float): Float {
        val r = Math.toRadians(abs(degrees).toDouble()); val c = cos(r); val s = sin(r)
        return maxOf((width * c + height * s) / width, (width * s + height * c) / height).toFloat()
    }

    /** [source] turned [degrees] clockwise about its centre and enlarged to fill its own size, as a gallery straightens. */
    fun tilt(source: Bitmap, degrees: Float): Bitmap {
        if (abs(degrees) < 0.05f) return source
        val k = cover(source.width, source.height, degrees)
        val m = Matrix().apply { postTranslate(-source.width / 2f, -source.height / 2f); postRotate(degrees); postScale(k, k); postTranslate(source.width / 2f, source.height / 2f) }
        return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also { android.graphics.Canvas(it).drawBitmap(source, m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)) }
    }

    /**
     * The angle the text lines slope at, in degrees (positive: down to the right), or null without two lines to go by:
     * each line's slope fitted to its words, the median taken weighted by length, so a stray caption cannot decide.
     * [lines] in pixels.
     */
    fun slope(lines: List<List<ScanPoint>>): Float? {
        val found = lines.filter { it.size >= 2 }.mapNotNull { pts ->
            val (_, b) = linear(pts.map { it.x }, pts.map { it.y }) ?: return@mapNotNull null
            val deg = Math.toDegrees(atan(b.toDouble())).toFloat()
            if (abs(deg) >= MAX_TILT) null else deg to (pts.maxOf { it.x } - pts.minOf { it.x })
        }.sortedBy { it.first }
        if (found.size < 2) return null
        val half = found.sumOf { it.second.toDouble() } / 2
        var run = 0.0
        return found.first { run += it.second; run >= half }.first
    }

    /**
     * The bend grid of a curved page, from its text lines ([lines]: each line's word baselines, normalized to the
     * page), or null with fewer than three lines long enough to show a curve. Each line gives a curvature (a parabola
     * fitted to its words; its tilt is the other control's); a page bends smoothly, so the curvature down the page is
     * one smooth curve fitted to the lines' (longer lines count more, odd ones — a heading, a line read in pieces —
     * are outvoted first by their neighbours' median). Across the page the shift is that parabola less its straight
     * part, so the page's edges and middle stay where they were on average.
     */
    fun bend(lines: List<List<ScanPoint>>): List<Float>? {
        val found = lines.filter { it.size >= 3 && it.maxOf { p -> p.x } - it.minOf { p -> p.x } >= 0.2f }.mapNotNull { pts ->
            val a = quadratic(pts.map { it.x }, pts.map { it.y }) ?: return@mapNotNull null
            val span = pts.maxOf { it.x } - pts.minOf { it.x }
            Triple(pts.map { it.y }.average().toFloat(), a, span * span)
        }.sortedBy { it.first }
        if (found.size < 3) return null
        val smoothed = found.indices.map { i -> found.subList(maxOf(0, i - 2), minOf(found.size, i + 3)).map { it.second }.sorted().let { it[it.size / 2] } }
        // a(y) = c0 + c1·y + c2·y², weighted least squares (a straight line with only three lines to go by)
        val c = fit(found.map { it.first }, smoothed, found.map { it.third }, if (found.size >= 5) 2 else 1)
        val n = GRID + 1
        return (0 until n * n).map { k ->
            val x = (k % n).toFloat() / GRID; val y = (k / n).toFloat() / GRID
            // keep a(y) inside what the lines showed: a page does not bend more past its last line
            val a = c.foldIndexed(0f) { d, acc, ci -> acc + ci * Math.pow(y.coerceIn(found.first().first, found.last().first).toDouble(), d.toDouble()).toFloat() }
            a * (x * x - x + 1f / 6f) // x² less its straight fit over 0..1
        }
    }

    /** Weighted least squares polynomial of [degree] through ([xs], [ys]): its coefficients, lowest first. */
    private fun fit(xs: List<Float>, ys: List<Float>, ws: List<Float>, degree: Int): List<Float> {
        val m = degree + 1
        val a = Array(m) { DoubleArray(m + 1) }
        xs.indices.forEach { i -> for (r in 0 until m) { for (q in 0 until m) a[r][q] += ws[i] * Math.pow(xs[i].toDouble(), (r + q).toDouble()); a[r][m] += ws[i] * ys[i] * Math.pow(xs[i].toDouble(), r.toDouble()) } }
        for (col in 0 until m) { // Gauss–Jordan, partial pivot
            val piv = (col until m).maxBy { abs(a[it][col]) }; val t = a[col]; a[col] = a[piv]; a[piv] = t
            if (abs(a[col][col]) < 1e-15) return listOf(ys.average().toFloat()) + List(degree) { 0f }
            for (r in 0 until m) if (r != col) { val f = a[r][col] / a[col][col]; for (q in col..m) a[r][q] -= f * a[col][q] }
        }
        return (0 until m).map { (a[it][m] / a[it][it]).toFloat() }
    }

    /** [source] with its bend [grid] taken out: each point moved up or down by its shift, the edges kept as they were. */
    fun flatten(source: Bitmap, grid: List<Float>): Bitmap {
        val n = GRID + 1
        if (grid.size != n * n) return source
        val verts = FloatArray(n * n * 2)
        for (gy in 0 until n) for (gx in 0 until n) {
            val i = gy * n + gx
            verts[i * 2] = gx * source.width / GRID.toFloat()
            verts[i * 2 + 1] = gy * source.height / GRID.toFloat() - grid[i] * source.height
        }
        return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also { out ->
            val canvas = android.graphics.Canvas(out)
            val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
            // the page as it was underneath: where the mesh pulls in from an edge, the gap shows the same paper
            canvas.drawBitmap(source, 0f, 0f, paint)
            canvas.drawBitmapMesh(source, GRID, GRID, verts, 0, null, 0, paint)
        }
    }

    /** Each text line of [page] as its words' bottom-centres in pixels (a one-word line: its own bottom corners). */
    fun textLines(page: Bitmap): List<List<ScanPoint>> = runCatching {
        val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val text = com.google.android.gms.tasks.Tasks.await(recognizer.process(com.google.mlkit.vision.common.InputImage.fromBitmap(page, 0)), 10, java.util.concurrent.TimeUnit.SECONDS)
            text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                val words = line.elements.mapNotNull { e -> e.cornerPoints?.takeIf { it.size == 4 }?.let { c -> ScanPoint((c[2].x + c[3].x) / 2f, (c[2].y + c[3].y) / 2f) } }
                words.takeIf { it.size >= 2 } ?: line.cornerPoints?.takeIf { it.size == 4 }?.let { c -> listOf(ScanPoint(c[3].x.toFloat(), c[3].y.toFloat()), ScanPoint(c[2].x.toFloat(), c[2].y.toFloat())) }
            }
        } finally { recognizer.close() }
    }.getOrDefault(emptyList())

    /** Least squares y = a + b·x: (a, b), or null when the xs do not spread. */
    private fun linear(xs: List<Float>, ys: List<Float>): Pair<Float, Float>? {
        val mx = xs.average(); val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        if (sxx < 1e-9) return null
        val b = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx
        return (my - b * mx).toFloat() to b.toFloat()
    }

    /** Least squares y = a·x² + b·x + c: a, or null when the points cannot fix a parabola. */
    private fun quadratic(xs: List<Float>, ys: List<Float>): Float? {
        // normal equations, solved by Cramer's rule (3×3)
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0; var t0 = 0.0; var t1 = 0.0; var t2 = 0.0
        xs.indices.forEach { i -> val x = xs[i].toDouble(); val y = ys[i].toDouble(); val x2 = x * x
            s0 += 1; s1 += x; s2 += x2; s3 += x2 * x; s4 += x2 * x2; t0 += y; t1 += x * y; t2 += x2 * y }
        fun det(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double, g: Double, h: Double, i: Double) = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        val d = det(s4, s3, s2, s3, s2, s1, s2, s1, s0)
        if (abs(d) < 1e-12) return null
        return (det(t2, s3, s2, t1, s2, s1, t0, s1, s0) / d).toFloat()
    }
}

/**
 * The auto fix's straightening (runs off the main thread, a second or two): [page]'s curve flattened, then its tilt
 * taken out, both measured from its text lines. A page already flat and level, or with no text to go by, is left so.
 * Its light is measured too: a dark or unevenly lit page gets Fix lighting, unless a filter was chosen by hand.
 */
internal fun autoStraightened(page: CapturedPage): CapturedPage {
    fun render(p: CapturedPage) = renderScanPage(p.copy(filter = ScanFilter.Original, strokes = emptyList()), 2, 2000f)
    val base = page.copy(bend = null, tilt = 0f)
    val first = render(base) ?: return page
    val filter = if (page.filterByHand) page.filter else autoFilter(first)
    val lines = ScanStraighten.textLines(first)
    // a bend under a third of a percent of the height is a straight page read slightly unevenly: leave it
    val grid = ScanStraighten.bend(lines.map { l -> l.map { ScanPoint(it.x / first.width, it.y / first.height) } })?.takeIf { it.max() - it.min() > 0.003f }
    first.recycle()
    // the tilt is measured on the flattened page: a curve's ends would pull a straight-line fit
    val slope = if (grid == null) ScanStraighten.slope(lines) else render(base.copy(bend = grid))?.let { b -> ScanStraighten.slope(ScanStraighten.textLines(b)).also { b.recycle() } }
    return page.copy(bend = grid, tilt = slope?.takeIf { abs(it) >= 0.3f }?.let { -it } ?: 0f, filter = filter)
}

/** Fix lighting for a page whose paper is dark or unevenly lit ([ScanFilters.tooDark]), else Original; measured small. */
private fun autoFilter(page: Bitmap): ScanFilter {
    val scale = minOf(1f, 600f / maxOf(page.width, page.height))
    val small = Bitmap.createScaledBitmap(page, maxOf(1, (page.width * scale).toInt()), maxOf(1, (page.height * scale).toInt()), true)
    val pixels = IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) }
    val light = ScanFilters.paperLight(pixels, small.width, small.height)
    if (small !== page) small.recycle()
    val dark = ScanFilters.tooDark(light)
    WriterLog.i("Scanner: paper median ${light[0]}, darkest twentieth ${light[1]}, brightest ${light[2]} (dark under ${ScanFilters.DARK_PAPER}, uneven under ${ScanFilters.UNEVEN_PAPER} of the brightest) → ${if (dark) "Fix lighting" else "Original"}")
    return if (dark) ScanFilter.Lighting else ScanFilter.Original
}
