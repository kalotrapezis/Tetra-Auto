package com.kalotrapezis.drive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/** A word read off a picture: its letters and its box in the picture's pixels. */
internal class OcrWord(val text: String, val box: RectF)

/**
 * Reading text off pictures (Plan.md § PDF, Recognize text; Photos next — the user, 22 Oct: "OCR would be nice for photos as
 * well"): Tesseract with Greek and English (tessdata_fast, in the APK's assets), offline. One engine at a time, made the
 * first time it is needed; slow — a page takes seconds — so always off the main thread.
 */
internal object Ocr {
    private var api: TessBaseAPI? = null

    /** The models copied out of the APK once (Tesseract reads files), into the app's own storage. */
    private fun dataDir(context: Context): File {
        val dir = File(context.filesDir, "tessdata").apply { mkdirs() }
        for (lang in listOf("ell", "eng")) {
            val f = File(dir, "$lang.traineddata")
            if (!f.isFile || f.length() == 0L) context.assets.open("tessdata/$lang.traineddata").use { i -> f.outputStream().use { i.copyTo(it) } }
        }
        return dir.parentFile!!
    }

    private fun engine(context: Context) = api ?: TessBaseAPI().also {
        need(it.init(dataDir(context).path, "ell+eng"), R.string.scanner_error_ocr) { "The text reader could not start." }
        api = it
    }

    /** Every word on [bitmap] with its box, Greek and English together. */
    @Synchronized fun words(context: Context, bitmap: Bitmap): List<OcrWord> {
        val t = engine(context)
        t.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
        t.setImage(bitmap)
        t.getUTF8Text() // reads the page; the words come from the iterator
        val out = ArrayList<OcrWord>()
        val it = t.resultIterator ?: return out
        try {
            it.begin()
            do {
                val w = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim().orEmpty()
                val r = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                if (w.isNotEmpty() && it.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD) > 30f) out += OcrWord(w, RectF(r))
            } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
        } finally { it.delete(); t.clear() }
        return out
    }

    /** The words of [bitmap] as lines of text (Photos' Copy text). */
    @Synchronized fun text(context: Context, bitmap: Bitmap): String {
        val t = engine(context)
        t.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
        t.setImage(bitmap)
        return t.getUTF8Text().orEmpty().also { t.clear() }
    }
}
