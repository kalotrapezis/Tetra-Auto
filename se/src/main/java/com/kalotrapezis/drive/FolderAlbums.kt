package com.kalotrapezis.drive

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore

/**
 * Which folders are photos (SYNC_PLAN.md §7 / D). The phone holds far more pictures than a gallery should show —
 * Viber, SyncThing mirrors, a USB copy, game presets under Download — so a folder is only ever *offered* when it
 * sits where Android keeps photos, and only *shown* once it is a default or you said yes to it.
 */
internal object FolderRules {
    /** Always in, never asked: the camera, screenshots, and what the computer sends back. */
    val DEFAULTS = setOf("camera", "screenshots", "tetra")
    private val PHOTO_ROOTS = setOf("dcim", "pictures", "movies")

    /**
     * The album a file's folder belongs to, or null for a folder that is files, not photos (Drive, SyncThing,
     * Documents, a USB copy, the storage root). Named after the folder under DCIM/Pictures/Movies, so
     * Pictures/Viber, Movies/Viber and pictures/Viber are one "Viber"; everything under Download is one question.
     */
    fun albumName(relativePath: String?): String? {
        val parts = relativePath?.split('/')?.filter { it.isNotBlank() }.orEmpty()
        val root = parts.firstOrNull()?.lowercase() ?: return null
        return when {
            root == "download" -> "Download"
            root !in PHOTO_ROOTS -> null
            parts.size == 1 -> parts[0]
            else -> parts[1]
        }
    }

    fun isDefault(album: String) = album.lowercase() in DEFAULTS

    /** `choices` maps a lower-cased album name to the answer given; no entry means never asked. */
    fun isIncluded(relativePath: String?, choices: Map<String, Boolean>): Boolean =
        albumName(relativePath)?.lowercase()?.let { it in DEFAULTS || choices[it] == true } == true
}

/** A folder found on this phone, for its Help organize card and its row in Gallery tools. */
internal data class DeviceFolder(val name: String, val entries: List<Entry>, val included: Boolean?) {
    val countText get() = "${entries.size} ${if (entries.size == 1) "photo or video" else "photos and videos"}"
    /** [countText] in the user's language. */
    fun countText(resources: android.content.res.Resources): String = resources.getQuantityString(R.plurals.photos_or_videos, entries.size, entries.size)
}

internal fun deviceFolders(all: List<Entry>, choices: Map<String, Boolean>): List<DeviceFolder> = all
    .groupBy { FolderRules.albumName(it.relativePath)?.lowercase() }
    .mapNotNull { (key, entries) ->
        key?.takeUnless(FolderRules::isDefault)?.let {
            // Named the way most of its files spell it: pictures/Viber should not rename Viber.
            val name = entries.groupingBy { e -> FolderRules.albumName(e.relativePath)!! }.eachCount().maxBy { e -> e.value }.key
            DeviceFolder(name, entries.sortedByDescending(Entry::takenMillis), choices[it])
        }
    }
    .sortedByDescending { it.entries.size }

/**
 * Really moves photos into another folder (asked 2026-09-25: a folder album is a folder, so taking a photo out of
 * it moves the file). Android keeps the item and its uri and only changes RELATIVE_PATH; a name already taken there
 * is made unique by MediaStore, never overwritten. The key includes the folder, so everything about the photo is
 * carried to its new key, and it leaves the old folder's album and joins the new one's. Needs Android's write consent
 * for photos another app made (the caller asks first). Returns how many moved.
 */
internal fun movePhotosToFolder(context: Context, entries: List<Entry>, dest: String, store: PhotoMetadataStore): Int {
    val target = dest.trim('/') + "/"
    var moved = 0
    for (entry in entries) {
        val uri = entry.contentUri ?: continue
        if (entry.relativePath?.trim('/') == target.trim('/')) continue
        val changed = runCatching {
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, target) }, null, null)
        }.getOrDefault(0)
        if (changed == 0) continue
        val columns = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.SIZE)
        val newKey = context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
            if (!c.moveToFirst()) null
            else PhotoMetadataRules.stableKey(uri.toString(), c.getString(1) ?: "Unnamed", c.getString(0) ?: "Unnamed", if (c.isNull(2)) 0L else c.getLong(2))
        } ?: continue
        store.rekeyPhoto(entry.photoKey, newKey, sameContent = true)
        FolderRules.albumName(entry.relativePath)?.let(store::collectionIdNamed)?.let { store.removeFromCollection(it, listOf(newKey)) }
        FolderRules.albumName(target)?.let(store::collectionIdNamed)?.let { store.addToCollection(it, listOf(newKey)) }
        moved++
    }
    return moved
}
