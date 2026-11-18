package com.kalotrapezis.drive

import android.graphics.pdf.PdfDocument
import java.io.OutputStream

/**
 * Word's Export › PDF: the document on its own pages (the file's page size, margins and spacing), laid out by
 * [OfficePages] as the page view shows it — by Android's text layout, not by Word.
 */
internal object OfficePdf {
    fun write(doc: OfficeDoc, styles: DocStyles, out: OutputStream, pictureBytes: (DocImage) -> ByteArray? = { null }) {
        val pdf = PdfDocument()
        OfficePages.layout(doc, styles, pictureBytes = pictureBytes).forEachIndexed { i, laid ->
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(laid.page.width.toInt(), laid.page.height.toInt(), i + 1).create())
            laid.draw(page.canvas) // the PDF canvas counts in points, as the layout does
            pdf.finishPage(page)
        }
        pdf.writeTo(out)
        pdf.close()
    }
}
