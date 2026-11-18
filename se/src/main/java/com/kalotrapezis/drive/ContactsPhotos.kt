package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One person Photos knows by name: its uuid (the same on every device), its cover face. */
internal data class PhotoPerson(val groupId: Long, val uuid: String, val name: String, val count: Int, val cover: Uri?, val box: Rect)

/**
 * A contact's photo from the faces Photos already finds (Plan.md § Contacts › The photo): the face cropped square with room
 * round it (its box × 1.8), 512 px, into the contact's file, and X-TETRA-PERSON set to the person's uuid.
 */
internal object ContactsFaces {
    /** The people Photos has named, those whose name matches [name] first. */
    fun people(context: Context, name: String = ""): List<PhotoPerson> {
        val media = listDeviceMedia(context).associateBy { it.photoKey }
        return PhotoMetadataStore(context).use { store ->
            val uuids = store.readableDatabase.rawQuery("SELECT id, uuid FROM face_groups", null).use { c ->
                buildMap { while (c.moveToNext()) if (!c.isNull(1)) put(c.getLong(0), c.getString(1)) }
            }
            store.faceGroups(media.keys).filter { !isGeneratedPersonName(it.name) && uuids[it.id] != null }
                .map { g -> PhotoPerson(g.id, uuids.getValue(g.id), g.name, g.count, media[g.photoKey]?.contentUri, g.bounds()) }
        }.sortedWith(compareByDescending<PhotoPerson> { score(it.name, name) }.thenBy { it.name.lowercase() })
    }

    /** How well a person's name fits a contact's: the same, the same first word, or nothing. */
    fun score(person: String, contact: String): Int {
        val a = ContactsFiles.plain(person); val b = ContactsFiles.plain(contact)
        return when { a.isEmpty() || b.isEmpty() -> 0; a == b -> 2; a.substringBefore(' ') == b.substringBefore(' ') -> 1; else -> 0 }
    }

    /** A person's faces, the cover first, each with its picture. */
    fun faces(context: Context, p: PhotoPerson): List<Pair<Uri, Rect>> {
        val media = listDeviceMedia(context).associateBy { it.photoKey }
        return PhotoMetadataStore(context).use { it.faceGroupFaces(p.groupId) }.sortedByDescending { it.chosen }
            .mapNotNull { f -> media[f.photoKey]?.contentUri?.let { it to f.bounds } }.take(60)
    }

    /** The picture as Photos measured it (upright, its long side at most 1280 px: the face boxes' frame), [box] × 1.8, 512 px. */
    fun square(context: Context, uri: Uri, box: Rect?, size: Int = 512): String? = runCatching {
        val bmp: Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val scale = if (box != null) minOf(1f, 1280f / maxOf(info.size.width, info.size.height)) else minOf(1f, 2048f / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        ContactsSystem.square(bmp, box?.let { RectF(it) }, size = size)
    }.onFailure { WriterLog.e("Contacts: a photo", it) }.getOrNull()

    /**
     * A contact's portrait from a picture and its X-TETRA-FACE: "<the picture's SHA-256>;left,top,right,bottom" (fractions of
     * the upright picture), so the computer finds that face in its copy and puts it in Photos under the contact's person
     * (desktop contactfaces.js) — whichever device detects faces. [box]: a face Photos found; none: the middle square.
     */
    fun portrait(context: Context, uri: Uri, box: Rect?): Pair<String, String>? = runCatching {
        val sha = context.contentResolver.openInputStream(uri)!!.use { input ->
            val d = java.security.MessageDigest.getInstance("SHA-256"); val buf = ByteArray(256 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; d.update(buf, 0, n) }
            d.digest().joinToString("") { "%02x".format(it) }
        }
        val bmp: Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val scale = if (box != null) minOf(1f, 1280f / maxOf(info.size.width, info.size.height)) else minOf(1f, 2048f / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val (photo, square) = ContactsSystem.squareAndBox(bmp, box?.let { RectF(it) })
        photo to faceValue(sha, square)
    }.onFailure { WriterLog.e("Contacts: a portrait", it) }.getOrNull()

    /** "sha;0.2,0.1,0.6,0.6": four decimals, as the computer writes it. */
    fun faceValue(sha: String, box: List<Float>) = sha + ";" + box.joinToString(",") { (Math.round(it * 10000) / 10000.0).toBigDecimal().stripTrailingZeros().toPlainString() }

    /**
     * Contacts with no photo that Photos knows by the same name (or the same first name, when only one person has it), for
     * "Photos knows N of these people — use their faces?" after an import.
     */
    fun <P> matches(contacts: List<ContactInfo>, people: List<P>, name: (P) -> String): List<Pair<ContactInfo, P>> = contacts.filter { it.photo == null }.mapNotNull { c ->
        people.firstOrNull { score(name(it), c.name) == 2 }?.let { return@mapNotNull c to it }
        people.filter { score(name(it), c.name) == 1 }.singleOrNull()?.takeIf { p -> contacts.count { score(name(p), it.name) >= 1 } == 1 }?.let { c to it }
    }.distinctBy { it.second }
}

/** A face from Photos, round: cropped as the contact's photo would be. */
@Composable internal fun FaceThumb(uri: Uri?, box: Rect, size: Dp) {
    val context = LocalContext.current
    val img by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, uri, box) {
        value = uri?.let { withContext(Dispatchers.IO) { ContactsFaces.square(context, it, box, 160)?.let { b ->
            java.util.Base64.getDecoder().decode(b).let { bytes -> android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
        } } }
    }
    img?.let { Image(it, null, Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop) }
        ?: Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant))
}

/**
 * From Photos: the people Photos knows (those named like the contact first); one tapped shows its faces, the cover first,
 * and the face tapped becomes the photo.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable internal fun PhotosFacePicker(contactName: String, dismiss: () -> Unit, pick: (PhotoPerson, Uri, Rect) -> Unit) {
    val context = LocalContext.current
    val people by produceState<List<PhotoPerson>?>(null) { value = withContext(Dispatchers.IO) { runCatching { ContactsFaces.people(context, contactName) }.getOrDefault(emptyList()) } }
    var person by remember { mutableStateOf<PhotoPerson?>(null) }
    TetraSheet(onDismissRequest = dismiss, containerColor = SheetColor, contentColor = islandContentColor()) {
        Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val p = person
            if (p == null) {
                Text(stringResource(R.string.contacts_from_photos), style = MaterialTheme.typography.titleMedium)
                when {
                    people == null -> Text(stringResource(R.string.contacts_looking))
                    people!!.isEmpty() -> Text(stringResource(R.string.contacts_photos_knows_no_one), color = islandContentColor().copy(alpha = 0.7f))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    people.orEmpty().forEach { x ->
                        Column(Modifier.width(76.dp).clickable { person = x }, horizontalAlignment = Alignment.CenterHorizontally) {
                            FaceThumb(x.cover, x.box, 64.dp)
                            Text(x.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { person = null }) { Text("‹ " + stringResource(R.string.contacts_people), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                }
                val faces by produceState<List<Pair<Uri, Rect>>?>(null, p) { value = withContext(Dispatchers.IO) { runCatching { ContactsFaces.faces(context, p) }.getOrDefault(emptyList()) } }
                if (faces == null) Text(stringResource(R.string.contacts_looking))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    faces.orEmpty().forEach { (uri, box) -> Box(Modifier.clickable { pick(p, uri, box) }) { FaceThumb(uri, box, 72.dp) } }
                }
            }
        }
    }
}

/** After an import: "Photos knows N of these people — use their faces?", each ticked, untick any, OK. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun FacesForMany(pairs: List<Pair<ContactInfo, PhotoPerson>>, dismiss: () -> Unit, go: (List<Pair<ContactInfo, PhotoPerson>>) -> Unit) {
    var picked by remember(pairs) { mutableStateOf(pairs.toSet()) }
    TetraSheet(onDismissRequest = dismiss, containerColor = SheetColor, contentColor = islandContentColor()) {
        Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(pluralStringResource(R.plurals.contacts_photos_knows_n, pairs.size, pairs.size), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.contacts_use_faces), style = MaterialTheme.typography.bodySmall, color = islandContentColor().copy(alpha = 0.7f))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                pairs.forEach { pr ->
                    Row(Modifier.fillMaxWidth().clickable { picked = if (pr in picked) picked - pr else picked + pr }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Checkbox(pr in picked, { picked = if (pr in picked) picked - pr else picked + pr })
                        FaceThumb(pr.second.cover, pr.second.box, 40.dp)
                        Text(pr.first.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = dismiss) { Text(stringResource(R.string.contacts_not_now), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                TextButton(enabled = picked.isNotEmpty(), onClick = { go(picked.toList()) }) { Text(stringResource(R.string.contacts_use_n, picked.size), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}
