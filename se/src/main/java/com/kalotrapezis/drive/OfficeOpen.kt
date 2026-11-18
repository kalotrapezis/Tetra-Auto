package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A document, spreadsheet, presentation or PDF another app opens in Tetra (Open with › Tetra Office, asked 16 Oct): the file
 * itself when it is on the phone's storage (edited and saved where it is), else a copy in Documents/From other apps
 * (an email's attachment, a cloud app's file), which Tetra then keeps like any other.
 */
internal object OfficeOpen {
    /** Words', Tables', Slides' and the PDF viewer's kinds, by name. */
    fun isOffice(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("docx", "odt", "doc", "rtf") || Sheets.isSheet(name) || Slides.isSlides(name) || Pdf.isPdf(name)

    /** An address's file on the phone's storage, when it names one: file://, a Files app's (primary:…), Downloads' raw: one, Tetra's own. */
    fun localPath(uri: Uri, storage: File): File? = when {
        uri.scheme == "file" -> uri.path?.let(::File)
        uri.authority == "com.android.externalstorage.documents" -> documentId(uri)?.let { id ->
            if (id.startsWith("primary:")) File(storage, id.removePrefix("primary:")) else null
        }
        uri.authority == "com.android.providers.downloads.documents" -> documentId(uri)?.takeIf { it.startsWith("raw:") }?.let { File(it.removePrefix("raw:")) }
        uri.authority == "com.kalotrapezis.drive.files" -> providerFile(uri.pathSegments, storage)
        else -> null
    }?.takeIf { it.isFile && it.canRead() && it.canWrite() }

    /** Tetra's own share / Open with address (res/xml/local_drive_paths.xml: tetra/…, drive/…) back to its file. */
    fun providerFile(segments: List<String>, storage: File): File? =
        mapOf("tetra" to "Tetra", "drive" to "Drive")[segments.firstOrNull()]?.let { File(storage, (listOf(it) + segments.drop(1)).joinToString("/")) }

    private fun documentId(uri: Uri) = uri.pathSegments.let { s -> s.indexOf("document").takeIf { it >= 0 }?.let { s.getOrNull(it + 1) } }

    /**
     * A media or Downloads document's own file, from MediaStore (the picker's Recent gives these, and other apps' Open with
     * a content://media/… one): a file already on the phone is opened where it is, not copied. Downloads' plain numbers are
     * its own ids, not MediaStore's: left alone.
     */
    private fun mediaPath(context: Context, uri: Uri): File? {
        val row = if (uri.authority == MediaStore.AUTHORITY) uri else {
            val id = documentId(uri) ?: return null
            val ok = uri.authority == "com.android.providers.media.documents" && ':' in id || uri.authority == "com.android.providers.downloads.documents" && id.startsWith("msf:")
            ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id.substringAfter(':').toLongOrNull()?.takeIf { ok } ?: return null)
        }
        return runCatching { context.contentResolver.query(row, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull()?.let(::File)?.takeIf { it.isFile && it.canRead() && it.canWrite() }
    }

    /** The file to open: the phone's own, or a copy made in [into] under the name the other app gives it. */
    fun file(context: Context, uri: Uri, storage: File, into: File, type: String?): File? {
        (localPath(uri, storage) ?: mediaPath(context, uri))?.let { return it }
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }?.replace(Regex("""[\\/:*?"<>|]"""), "_")?.takeIf { it.isNotBlank() } ?: ("Document." + (extensionOf(type) ?: "docx"))
        into.mkdirs()
        var target = File(into, name); var n = 2
        while (target.exists()) { target = File(into, "${name.substringBeforeLast('.')} ($n).${name.substringAfterLast('.', "")}"); n++ }
        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: return null
        WriterLog.i("Office: a file from another app copied into ${into.name}")
        return target
    }

    /** A kind's file ending, when the other app gives no name. */
    fun extensionOf(type: String?) = when (type) {
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
        "application/vnd.oasis.opendocument.text" -> "odt"
        "application/msword" -> "doc"
        "application/rtf", "text/rtf" -> "rtf"
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
        "application/vnd.oasis.opendocument.spreadsheet" -> "ods"
        "application/vnd.ms-excel" -> "xls"
        "text/csv", "text/comma-separated-values" -> "csv"
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
        "application/vnd.openxmlformats-officedocument.presentationml.slideshow" -> "ppsx"
        "application/vnd.oasis.opendocument.presentation" -> "odp"
        "application/pdf" -> "pdf"
        else -> null
    }
}

/** Office's Open, one kind at a time (asked 17 Oct): what Android's picker shows. */
internal enum class OfficePick(val types: Array<String>) {
    Words(arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.oasis.opendocument.text", "application/msword", "application/rtf", "text/rtf")),
    Tables(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.oasis.opendocument.spreadsheet", "application/vnd.ms-excel", "text/csv", "text/comma-separated-values")),
    Slides(arrayOf("application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/vnd.openxmlformats-officedocument.presentationml.slideshow", "application/vnd.oasis.opendocument.presentation")),
    Pdf(arrayOf("application/pdf")),
}

/**
 * Android's picker showing one kind of file; the file chosen goes to [opened]: the phone's own where it can be found,
 * else a copy — a PDF's in the app's cache (only looked at), a document's in Documents/From other apps, as Open with's.
 */
@Composable
internal fun rememberOfficePicker(opened: (File) -> Unit): (OfficePick) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kind by rememberSaveable { mutableStateOf<OfficePick?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val k = kind ?: return@rememberLauncherForActivityResult
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val into = if (k == OfficePick.Pdf) File(context.cacheDir, "pdf").apply { deleteRecursively() } else File(TetraFolder.root(Environment.getExternalStorageDirectory()), "Documents/From other apps")
            val f = withContext(Dispatchers.IO) { runCatching { OfficeOpen.file(context, uri, Environment.getExternalStorageDirectory(), into, k.types.first()) }
                .onFailure { WriterLog.e("Office: the chosen file could not be read", it) }.getOrNull() }
            if (f == null) Toast.makeText(context, context.getString(R.string.office_open_failed), Toast.LENGTH_LONG).show() else opened(f)
        }
    }
    return { k -> kind = k; WriterLog.i("Office: open ${k.name}"); launcher.launch(k.types) }
}
