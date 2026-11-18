package com.kalotrapezis.drive

import java.io.File

/*
 * What only PDFs have, in Words' style (PDF_LIKE_WORDS.md, Pages and Protect): a page duplicated, the file split, pages
 * cropped to what is on them, comments and fields flattened into the pages, hidden information taken out.
 */

/** Page [i] once more, right after it (one undo step). */
internal fun PdfDoc.duplicatePage(i: Int) = edit("Duplicate page") { p -> p.graftPage(i + 1, p, i) }

/** Comments and form fields made part of the pages they are on: drawn as they look, no longer to be changed (one undo step). */
internal fun PdfDoc.flatten() = edit("Flatten") { p -> p.bake(true, true) }

/**
 * Hidden information taken out (Acrobat's Remove Hidden Information): the file's details (author, title, the programs
 * that made it), its scripts and attached files, pages' thumbnails and private data; the next save writes it whole,
 * so what was taken out does not stay in the file's older parts.
 */
internal fun PdfDoc.removeHidden() {
    edit("Remove hidden information") { p ->
        val t = p.trailer; t.delete("Info")
        val root = t.get("Root")
        listOf("Metadata", "OpenAction", "AA", "PieceInfo").forEach(root::delete)
        root.get("Names").takeIf { !it.isNull }?.let { n -> n.delete("JavaScript"); n.delete("EmbeddedFiles") }
        sizes.indices.forEach { i -> p.findPage(i).let { pg -> listOf("Thumb", "PieceInfo", "AA").forEach(pg::delete) } }
    }
    rewriteWhole()
}

/**
 * The file split ([every] page a file, else in two before page [at]) into files beside [file] named "name (part n)":
 * the files made.
 */
internal fun PdfDoc.split(file: File, every: Boolean, at: Int): List<File> {
    val groups = if (every) sizes.indices.map { listOf(it) } else listOf((0 until at).toList(), (at until sizes.size).toList()).filter { it.isNotEmpty() }
    return groups.mapIndexed { k, pages ->
        val to = generateSequence(1) { it + 1 }.map { n -> File(file.parentFile, "${file.nameWithoutExtension} (part ${k + 1}${if (n > 1) " $n" else ""}).pdf") }.first { !it.exists() }
        extract(pages, to); to
    }
}

/**
 * Pages [pages] cropped to what is drawn on them and a little round it (12 pt; their size as shown, what is outside
 * hidden, not taken out): how many had something to crop to. One undo step.
 */
internal fun PdfDoc.cropToContent(pages: List<Int>): Int {
    var n = 0
    edit("Crop") { _ ->
        pages.forEach { i -> withPage(i) { pg ->
            val b = pg.bounds; val w = 400
            val bmp = com.artifex.mupdf.fitz.android.AndroidDrawDevice.drawPageFitWidth(pg, w)
            val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
            var x0 = bmp.width; var y0 = bmp.height; var x1 = -1; var y1 = -1
            for (y in 0 until bmp.height) for (x in 0 until bmp.width) { val c = px[y * bmp.width + x]
                if ((c shr 16 and 0xFF) < 245 || (c shr 8 and 0xFF) < 245 || (c and 0xFF) < 245) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y } }
            if (x1 < 0) return@withPage
            val k = (b.x1 - b.x0) / bmp.width; val m = 12f
            (pg as com.artifex.mupdf.fitz.PDFPage).setCropBox(com.artifex.mupdf.fitz.Rect(maxOf(b.x0, b.x0 + x0 * k - m), maxOf(b.y0, b.y0 + y0 * k - m),
                minOf(b.x1, b.x0 + (x1 + 1) * k + m), minOf(b.y1, b.y0 + (y1 + 1) * k + m)))
            n++
        } }
    }
    return n
}
