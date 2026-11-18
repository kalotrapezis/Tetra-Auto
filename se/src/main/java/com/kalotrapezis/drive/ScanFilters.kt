package com.kalotrapezis.drive

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Four ways to look at a page, which is as many as anyone chooses between. There were seven, and the extra
 * three were shades of the same two ideas — keep the colours, or throw them away.
 */
enum class ScanFilter(@androidx.annotation.StringRes val label: Int) {
    Original(R.string.scanner_filter_original),
    Lighting(R.string.scanner_filter_lighting),
    SharpInk(R.string.scanner_filter_ink),
    BlackWhite(R.string.scanner_filter_bw),
    /** Set by "Match pages": lighting flattened, then every page tinted to one shared paper tone. */
    SamePaper(R.string.scanner_filter_matched),
}

/** Pure ARGB pixel filters. Every filter except Original first divides out uneven lighting (shadows, lamp gradients). */
object ScanFilters {
    fun apply(pixels: IntArray, width: Int, height: Int, filter: ScanFilter, paperTone: Int = WHITE) {
        if (filter == ScanFilter.Original || pixels.isEmpty()) return
        val background = paperBackground(pixels, width, height)
        val threshold = if (filter == ScanFilter.BlackWhite) inkThreshold(pixels, background) else 0
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val paper = background[index]
            // Flatten lighting: paper becomes white wherever it is, ink keeps its contrast against the local paper.
            val r = flatten(pixel shr 16 and 0xFF, paper shr 16 and 0xFF)
            val g = flatten(pixel shr 8 and 0xFF, paper shr 8 and 0xFF)
            val b = flatten(pixel and 0xFF, paper and 0xFF)
            pixels[index] = if (filter == ScanFilter.SamePaper) tint(levels(r), levels(g), levels(b), paperTone) else filterPixel(r, g, b, filter, threshold)
        }
    }

    internal fun filterPixel(red: Int, green: Int, blue: Int, filter: ScanFilter, threshold: Int = 170): Int {
        val r = levels(red)
        val g = levels(green)
        val b = levels(blue)
        val light = (r * 299 + g * 587 + b * 114) / 1000
        val blueness = b - max(r, g)
        val blueInk = blueness > 18 && light < 220
        return when (filter) {
            ScanFilter.Original, ScanFilter.Lighting, ScanFilter.SamePaper -> rgb(r, g, b)
            ScanFilter.SharpInk -> if (blueInk) saturate(r, g, b, light) else darken(r, g, b)
            ScanFilter.BlackWhite -> if (light < threshold) rgb(0, 0, 0) else rgb(255, 255, 255)
        }
    }

    /**
     * Where this page's ink ends and its paper begins, asked of the page itself.
     *
     * A fixed line at 170 assumed ink is dark, and a receipt printed in grey, a pencil note or a faded laser page
     * is not: every letter sat above the line and the page came out blank. Otsu splits the page's own brightness
     * into two groups and puts the line between them, so faint ink is still ink. The clamp keeps a page that is
     * genuinely all paper from having its own noise promoted into letters.
     */
    internal fun inkThreshold(pixels: IntArray, background: IntArray): Int {
        val histogram = IntArray(256)
        val step = maxOf(1, pixels.size / 200_000)
        var total = 0
        for (index in pixels.indices step step) {
            val pixel = pixels[index]
            val paper = background[index]
            val r = levels(flatten(pixel shr 16 and 0xFF, paper shr 16 and 0xFF))
            val g = levels(flatten(pixel shr 8 and 0xFF, paper shr 8 and 0xFF))
            val b = levels(flatten(pixel and 0xFF, paper and 0xFF))
            histogram[(r * 299 + g * 587 + b * 114) / 1000]++
            total++
        }
        // Otsu names the level the split sits on, and ink at exactly that level is still ink, so the line goes
        // just above it.
        return (otsu(histogram, total) + 1).coerceIn(60, 235)
    }

    /** The brightness that best splits a picture into two groups (Otsu 1979). */
    internal fun otsu(histogram: IntArray, total: Int): Int {
        if (total <= 0) return 170
        val sum = histogram.indices.sumOf { (it * histogram[it]).toLong() }
        var backgroundWeight = 0L
        var backgroundSum = 0L
        var best = 170
        var bestVariance = -1.0
        for (value in 0 until 256) {
            backgroundWeight += histogram[value]
            if (backgroundWeight == 0L) continue
            val foregroundWeight = total - backgroundWeight
            if (foregroundWeight <= 0L) break
            backgroundSum += (value * histogram[value]).toLong()
            val backgroundMean = backgroundSum.toDouble() / backgroundWeight
            val foregroundMean = (sum - backgroundSum).toDouble() / foregroundWeight
            val variance = backgroundWeight.toDouble() * foregroundWeight * (backgroundMean - foregroundMean) * (backgroundMean - foregroundMean)
            if (variance > bestVariance) { bestVariance = variance; best = value }
        }
        return best
    }

    /**
     * Restores the bite that straightening takes out. Correcting the perspective resamples every pixel from
     * between four others, which softens small print — an unsharp mask puts back what that interpolation blurred
     * and nothing more. Deliberately gentle: the page is a photograph, and a hard sharpen turns paper grain and
     * JPEG noise into speckle that the ink then has to compete with.
     *
     * [pixels] are ARGB and are modified in place.
     */
    fun sharpen(pixels: IntArray, width: Int, height: Int, amount: Float = 0.6f) {
        if (width < 3 || height < 3 || amount <= 0f) return
        val source = pixels.copyOf()
        fun channel(index: Int, shift: Int) = (source[index] shr shift) and 0xFF
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val index = y * width + x
            var out = 0xFF shl 24
            for (shift in intArrayOf(16, 8, 0)) {
                // 3x3 tent blur, then push the pixel away from it.
                val blur = (
                    channel(index - width - 1, shift) + 2 * channel(index - width, shift) + channel(index - width + 1, shift) +
                        2 * channel(index - 1, shift) + 4 * channel(index, shift) + 2 * channel(index + 1, shift) +
                        channel(index + width - 1, shift) + 2 * channel(index + width, shift) + channel(index + width + 1, shift)
                    ) / 16
                val own = channel(index, shift)
                out = out or ((own + ((own - blur) * amount).toInt()).coerceIn(0, 255) shl shift)
            }
            pixels[index] = out
        }
    }

    /**
     * Estimates the paper colour under each pixel: the brightest tenth of each grid cell, borrowed from a neighbour
     * when the cell is mostly ink, then bilinearly interpolated.
     */
    internal fun paperBackground(pixels: IntArray, width: Int, height: Int): IntArray {
        val cell = max(8, max(width, height) / 24)
        val columns = (width + cell - 1) / cell
        val rows = (height + cell - 1) / cell
        val grid = IntArray(columns * rows)
        val histogram = IntArray(256)
        for (row in 0 until rows) for (column in 0 until columns) {
            histogram.fill(0)
            val x0 = column * cell
            val y0 = row * cell
            val x1 = min(width, x0 + cell)
            val y1 = min(height, y0 + cell)
            var count = 0
            for (y in y0 until y1 step 2) for (x in x0 until x1 step 2) { histogram[luminance(pixels[y * width + x])]++; count++ }
            var threshold = 255
            var seen = 0
            while (threshold > 0 && seen + histogram[threshold] < max(1, count / 10)) { seen += histogram[threshold]; threshold-- }
            var sr = 0L; var sg = 0L; var sb = 0L; var bright = 0
            for (y in y0 until y1 step 2) for (x in x0 until x1 step 2) {
                val pixel = pixels[y * width + x]
                if (luminance(pixel) >= threshold) { sr += pixel shr 16 and 0xFF; sg += pixel shr 8 and 0xFF; sb += pixel and 0xFF; bright++ }
            }
            grid[row * columns + column] = if (bright == 0) -1 else rgb((sr / bright).toInt(), (sg / bright).toInt(), (sb / bright).toInt())
        }
        // Cells much darker than a neighbour are ink-dominated (header bars, stamps): take the neighbour's paper level.
        // Gentle gradients stay, so lighting is still divided out. Twice, to cover bars two cells thick.
        var spread = grid
        repeat(2) {
            val next = spread.copyOf()
            for (row in 0 until rows) for (column in 0 until columns) {
                var best = spread[row * columns + column]
                for (dy in -1..1) for (dx in -1..1) {
                    val r = row + dy; val c = column + dx
                    if (r in 0 until rows && c in 0 until columns && luminance(spread[r * columns + c]) > luminance(best)) best = spread[r * columns + c]
                }
                val own = spread[row * columns + column]
                next[row * columns + column] = if (own == -1 || luminance(own) < luminance(best) * 3 / 4) best else own
            }
            spread = next
        }
        val result = IntArray(pixels.size)
        for (y in 0 until height) {
            val gy = ((y + 0.5f) / cell - 0.5f).coerceIn(0f, (rows - 1).toFloat())
            val r0 = gy.toInt(); val r1 = min(rows - 1, r0 + 1); val fy = gy - r0
            for (x in 0 until width) {
                val gx = ((x + 0.5f) / cell - 0.5f).coerceIn(0f, (columns - 1).toFloat())
                val c0 = gx.toInt(); val c1 = min(columns - 1, c0 + 1); val fx = gx - c0
                val a = spread[r0 * columns + c0]; val bR = spread[r0 * columns + c1]
                val c = spread[r1 * columns + c0]; val d = spread[r1 * columns + c1]
                fun mix(shift: Int): Int {
                    val top = (a shr shift and 0xFF) * (1 - fx) + (bR shr shift and 0xFF) * fx
                    val bottom = (c shr shift and 0xFF) * (1 - fx) + (d shr shift and 0xFF) * fx
                    return (top * (1 - fy) + bottom * fy).toInt()
                }
                result[y * width + x] = rgb(mix(16), mix(8), mix(0))
            }
        }
        return result
    }

    /**
     * The page's paper light, for the auto fix to choose Fix lighting by: the median brightness of the paper under
     * the ink ([paperBackground]), and its darkest and brightest twentieth — a shadow or a lamp's fall-off pulls the
     * one away from the other. Written to the log, so a page that got the wrong call can be traced.
     */
    internal fun paperLight(pixels: IntArray, width: Int, height: Int): IntArray {
        val paper = paperBackground(pixels, width, height)
        val levels = (paper.indices step max(1, paper.size / 4096)).map { luminance(paper[it]) }.sorted()
        return intArrayOf(levels[levels.size / 2], levels[levels.size / 20], levels[levels.size * 19 / 20])
    }

    /**
     * Too dark to leave as it is: paper under [DARK_PAPER] (a phone's photo of white paper in an ordinary room is
     * 190–210), or lit so unevenly that its darkest twentieth is under [UNEVEN_PAPER] of its brightest.
     */
    internal fun tooDark(light: IntArray) = light[0] < DARK_PAPER || light[1] < light[2] * UNEVEN_PAPER

    internal const val DARK_PAPER = 185
    internal const val UNEVEN_PAPER = 0.75f

    /**
     * Four paper colours of a page, brightest first: lit paper, typical paper, light shadow, shadow.
     * Ink is excluded by only looking at the brightest 60% of the page.
     */
    fun paperSwatches(pixels: IntArray): List<Int> {
        if (pixels.isEmpty()) return List(4) { WHITE }
        val step = maxOf(1, pixels.size / 40_000)
        val sample = (pixels.indices step step).map { pixels[it] }.sortedByDescending(::luminance)
        val paper = sample.subList(0, maxOf(4, sample.size * 6 / 10).coerceAtMost(sample.size))
        return listOf(0.0 to 0.05, 0.05 to 0.25, 0.25 to 0.45, 0.45 to 0.6).map { (from, to) ->
            val slice = sample.subList((sample.size * from).toInt(), maxOf((sample.size * from).toInt() + 1, (sample.size * to).toInt()).coerceAtMost(paper.size))
            rgb(slice.sumOf { it shr 16 and 0xFF } / slice.size, slice.sumOf { it shr 8 and 0xFF } / slice.size, slice.sumOf { it and 0xFF } / slice.size)
        }
    }

    /** Average of several colours, used for the shared paper tone across pages. */
    fun average(colors: List<Int>): Int = if (colors.isEmpty()) WHITE else rgb(
        colors.sumOf { it shr 16 and 0xFF } / colors.size, colors.sumOf { it shr 8 and 0xFF } / colors.size, colors.sumOf { it and 0xFF } / colors.size,
    )

    const val WHITE = -0x1

    private fun tint(r: Int, g: Int, b: Int, tone: Int) = rgb(r * (tone shr 16 and 0xFF) / 255, g * (tone shr 8 and 0xFF) / 255, b * (tone and 0xFF) / 255)
    private fun flatten(value: Int, paper: Int) = min(255, value * 255 / max(paper, 48))
    // Clip the darkest and brightest few percent so paper reaches pure white and ink gets crisper.
    private fun levels(value: Int) = ((value - 20) * 255 / 215).coerceIn(0, 255)
    private fun saturate(r: Int, g: Int, b: Int, light: Int): Int {
        fun boost(channel: Int) = ((light + (channel - light) * 1.9f) * 0.82f).toInt().coerceIn(0, 255)
        return rgb(boost(r), boost(g), boost(b))
    }
    private fun darken(r: Int, g: Int, b: Int): Int {
        fun curve(channel: Int) = (255 * (channel / 255f).pow(2.2f)).toInt()
        return rgb(curve(r), curve(g), curve(b))
    }
    private fun luminance(pixel: Int) = ((pixel shr 16 and 0xFF) * 299 + (pixel shr 8 and 0xFF) * 587 + (pixel and 0xFF) * 114) / 1000
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
