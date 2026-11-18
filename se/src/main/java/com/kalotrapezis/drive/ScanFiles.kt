package com.kalotrapezis.drive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.graphics.pdf.PdfDocument
import java.io.File

object ScanFiles {
    private const val FOLDER = "Documents/Scanned Documents"

    fun outputName(value: String): String {
        val stem = value.trim().removeSuffix(".pdf").trim()
        need(stem.isNotEmpty() && stem.length <= 120 && '/' !in stem && '\\' !in stem && stem !in setOf(".", ".."), R.string.scanner_error_name) { "Enter a valid file name." }
        return "$stem.pdf"
    }

    fun nextAvailable(folder: File, outputName: String): File {
        val stem = outputName.removeSuffix(".pdf")
        return generateSequence(1) { it + 1 }
            .map { index -> File(folder, if (index == 1) outputName else "$stem ($index).pdf") }
            .first { !it.exists() }
    }

    /** Writes [pageCount] pages rendered one at a time (bounded memory) as A4-width PDF pages. */
    fun savePdf(root: File, name: String, pageCount: Int, into: String = FOLDER, render: (Int) -> Bitmap): String {
        need(pageCount > 0, R.string.scanner_error_no_pages) { "There are no pages to save." }
        val folder = File(root, into).canonicalFile
        need(DriveRules.inside(root, folder) && (folder.exists() || folder.mkdirs()), R.string.scanner_error_folder) { "Could not create Scans." }
        val target = nextAvailable(folder, outputName(name)).canonicalFile
        need(DriveRules.inside(root, target) && target.createNewFile(), R.string.scanner_error_file) { "Could not create the scan file." }
        val document = PdfDocument()
        try {
            for (index in 0 until pageCount) {
                val page = render(index)
                val width = A4_WIDTH_POINTS
                val height = (A4_WIDTH_POINTS * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
                val pdfPage = document.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
                pdfPage.canvas.drawBitmap(page, null, android.graphics.Rect(0, 0, width, height), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                document.finishPage(pdfPage)
                page.recycle()
            }
            // A failed write keeps the partial file for recovery instead of deleting user-visible data.
            target.outputStream().use { output -> document.writeTo(output) }
        } finally {
            document.close()
        }
        return DriveRules.relative(root, target)
    }

    /** Every page of [doc] (unsaved changes too) drawn at about 300 dpi, as JPGs in [into]: pages for the scanner. */
    internal fun pdfPages(doc: PdfDoc, into: File): List<File> = doc.sizes.indices.map { i -> jpg(doc.scanOf(i).first, into) }

    /**
     * [uri] as pages for the scanner, JPGs in [into]: a PDF's every page, or one picture (JPG, PNG, HEIC…) upright.
     * A file:// uri is Tetra's own Files, a content:// one Android's picker.
     */
    fun importPages(context: Context, uri: Uri, into: File): List<File> {
        into.mkdirs()
        val type = context.contentResolver.getType(uri)
        if (type == "application/pdf" || uri.path.orEmpty().endsWith(".pdf", true)) {
            val copy = File(into, "${java.util.UUID.randomUUID()}.pdf")
            try {
                context.contentResolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } } ?: error("unreadable")
                val doc = PdfDoc(copy)
                try { return pdfPages(doc, into) } finally { doc.close() }
            } finally { copy.delete() }
        }
        // ImageDecoder turns a photo upright by its EXIF and reads HEIC; a huge picture is halved until it fits a page
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            var sample = 1
            while (maxOf(info.size.width, info.size.height) / sample > 4200) sample *= 2
            decoder.setTargetSampleSize(sample)
        }
        return listOf(jpg(bitmap, into))
    }

    private fun jpg(bitmap: Bitmap, into: File): File = File(into, "${java.util.UUID.randomUUID()}.jpg").also { f ->
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bitmap.recycle()
    }

    private const val A4_WIDTH_POINTS = 595
}
