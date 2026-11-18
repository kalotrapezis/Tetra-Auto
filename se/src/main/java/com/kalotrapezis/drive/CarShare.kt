package com.kalotrapezis.drive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Share on the car's own build (the user, 14 Nov: "SE does not have a sync app like Tetra Car has"), Tetra Auto's Share over the car link.
 * The phone's Send to car keeps the files in its Tetra › To car; the car takes them as it takes the places (CarMaps' "@share/") into its
 * Tetra › From phone, and the phone forgets them. A file of the car's (a USB stick's) goes to the phone's Tetra › From car.
 */
internal object CarShare {
    const val PREFIX = "@share/"
    /** The phone's To car, the car's From phone. */
    fun dir(): File = File(driveRoot(), if (CarMaps.edition) "From phone" else "To car")

    /** On the phone: Send to car while the car's own Tetra Car is linked. */
    fun send(c: Context, uris: List<Uri>) = Thread({
        val d = dir().apply { mkdirs() }
        for (uri in uris) runCatching {
            File(d, name(c, uri)).outputStream().use { out -> c.contentResolver.openInputStream(uri)!!.use { it.copyTo(out, 1 shl 16) } }
        }.onFailure { WriterLog.w("Car share: not kept for the car (${it.message})") }
        WriterLog.i("Car share: ${uris.size} for the car")
        CarWire.tell(c, JSONObject().put("type", "share")) // the car takes them now
    }, "car-share").start()

    fun name(c: Context, uri: Uri) = AutoWire.safeName(c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: uri.lastPathSegment ?: "file").takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "file"

    /** On the phone: the car has [names] now, off To car. */
    fun taken(names: JSONArray) {
        val d = dir().canonicalFile
        for (i in 0 until names.length()) File(d, names.getString(i)).canonicalFile.takeIf { it.parentFile == d && it.isFile }?.delete()
    }

    fun list(): List<File> = dir().listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") }.sortedByDescending { it.lastModified() }

    /** A file from the phone, opened: an app is offered for installing (a Tetra Car update), the rest by its kind. */
    fun open(c: Context, f: File) {
        val type = if (f.name.endsWith(".apk", true)) "application/vnd.android.package-archive"
            else MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        runCatching {
            c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.getUriForFile(c, "${c.packageName}.files", f), type)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { WriterLog.w("Car share: ${f.name} not opened (${it.message})"); Toast.makeText(c, c.getString(R.string.car_share_no_app, f.name), Toast.LENGTH_LONG).show() }
    }
}

/** Tetra Car's menu › Share on the car's build: what came from the phone, and a file sent to it. */
@Composable
internal fun CarSharePage(close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf(CarShare.list()) }
    val version = CarMaps.version.intValue // files taken from the phone: the list again
    LaunchedEffect(version) { files = withContext(Dispatchers.IO) { CarShare.list() } }
    LaunchedEffect(Unit) { CarMaps.check(context) } // anything the phone has for the car, now
    var sending by remember { mutableStateOf<String?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val name = CarShare.name(context, uri)
        sending = name; said = null
        scope.launch {
            val e = withContext(Dispatchers.IO) { runCatching { CarWire.put(context, uri, name) }.exceptionOrNull() }
            WriterLog.i("Car share: $name ${if (e == null) "sent to the phone" else "not sent (${e.message})"}")
            sending = null
            said = if (e == null) context.getString(R.string.car_share_sent, name) else context.getString(R.string.car_share_failed, name, e.message.orEmpty())
        }
    }
    BackHandler(onBack = close)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(close) { Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.common_back)) }
            Text(stringResource(R.string.auto_share), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            Button({ pick.launch(arrayOf("*/*")) }, enabled = sending == null) {
                Icon(painterResource(R.drawable.ic_send), null, Modifier.size(20.dp)); Text("  " + stringResource(R.string.car_share_send), maxLines = 1)
            }
        }
        sending?.let { Text(it, Modifier.padding(top = 12.dp)); LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp)) }
        // a file from the phone on its way: its name, how much has come, a bar; why it did not arrive, and Try again
        CarMaps.receiving.value?.let { r ->
            val size = { b: Long -> android.text.format.Formatter.formatShortFileSize(context, b) }
            if (r.failed == null) {
                Text(stringResource(R.string.car_share_receiving, r.name, size(r.got), size(r.size)), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyLarge)
                LinearProgressIndicator({ if (r.size > 0) (r.got.toFloat() / r.size).coerceIn(0f, 1f) else 0f }, Modifier.fillMaxWidth().padding(top = 4.dp))
            } else Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.car_share_not_arrived, r.name, r.failed), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Button({ CarMaps.receiving.value = null; CarMaps.check(context) }) { Text(stringResource(R.string.car_share_again)) }
            }
        }
        said?.let { Text(it, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyLarge) }
        if (files.isEmpty()) Text(stringResource(R.string.car_share_empty), Modifier.padding(top = 24.dp), style = MaterialTheme.typography.bodyLarge)
        LazyColumn(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            items(files, key = { it.path }) { f ->
                Row(Modifier.fillMaxWidth().clickable { CarShare.open(context, f) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(android.text.format.Formatter.formatShortFileSize(context, f.length()), style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton({ f.delete(); files = CarShare.list() }) { Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.common_delete)) }
                }
            }
        }
    }
}
