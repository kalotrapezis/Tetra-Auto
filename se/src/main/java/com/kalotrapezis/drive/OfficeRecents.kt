package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import android.content.Context
import android.os.Environment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.io.File

/**
 * The Office home's Recents (the user, 18 Oct: "the recents does not update in office when I open a file from picker or Files"):
 * every file opened in Words, Tables, Slides or the PDF viewer, newest first, however it was opened. The home lists them before
 * the newest files of Tetra's Files, and reads them again when it shows ([changes]).
 */
internal object OfficeRecents {
    private const val MAX = 40
    /** Counted up at each open, so a home that is showing reads the list again. */
    var changes by mutableIntStateOf(0); private set

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("office_recents", Context.MODE_PRIVATE)

    fun opened(context: Context, file: File) {
        val path = file.absolutePath
        val list = listOf(path) + read(context).filter { it != path }
        prefs(context).edit().putString("opened", list.take(MAX).joinToString("\n")).apply()
        changes++
    }

    /** The files opened, newest first, those still there. */
    fun list(context: Context): List<File> = read(context).map(::File).filter { it.isFile }

    private fun read(context: Context) = prefs(context).getString("opened", null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
}

/** In Words, Tables, Slides and the PDF viewer: [file] (null: not one yet) put at the top of the Office home's Recents when it opens. */
@Composable internal fun RememberOpened(file: File?) {
    val context = LocalContext.current
    LaunchedEffect(file?.absolutePath) { file?.takeIf { it.isFile }?.let { OfficeRecents.opened(context, it) } }
}

/** The open file's place in the Office home's Favourites (Files' own, as the home's hearts): null when it is not in Tetra's Files. */
internal class OfficeFavourite(val on: Boolean, val toggle: () -> Unit)

@Composable internal fun rememberOfficeFavourite(file: File?): OfficeFavourite? {
    val context = LocalContext.current
    val root = remember { TetraFolder.root(Environment.getExternalStorageDirectory()) }
    val relative = remember(file?.absolutePath) { file?.let { f -> runCatching { f.canonicalFile.relativeTo(root.canonicalFile).path }.getOrNull()?.takeIf { !it.startsWith("..") && it.isNotEmpty() } } }
        ?: return null
    val metadata = remember { DriveMetadata(context) }
    var on by remember(relative) { mutableStateOf(runCatching { metadata.isFavorite(relative) }.getOrDefault(false)) }
    return OfficeFavourite(on) {
        runCatching { metadata.setFavorite(relative, !on) }.onSuccess { on = !on; WriterLog.i("Office: ${if (on) "a favourite" else "no longer a favourite"}") }
    }
}

/** A tablet or bigger (the home's own line): the favourite heart goes in the top bar; a phone has it in the File tab. */
@Composable internal fun favouriteInTopBar() = LocalConfiguration.current.screenWidthDp >= 600

/** The heart in the top bar, filled when the open file is a favourite; nothing when it cannot be one or on a phone. */
@Composable internal fun FavouriteHeart(file: File?, tint: androidx.compose.ui.graphics.Color) {
    if (!favouriteInTopBar()) return
    val fav = rememberOfficeFavourite(file) ?: return
    Tool(if (fav.on) R.drawable.ic_favorite else R.drawable.ic_favorite_border, stringResource(if (fav.on) R.string.office_favourite_remove else R.string.office_favourite_add), tint) { fav.toggle() }
}

/** A phone's File tab: the same as an item. */
@Composable internal fun FavouriteFileButton(file: File?, tint: androidx.compose.ui.graphics.Color) {
    if (favouriteInTopBar()) return
    val fav = rememberOfficeFavourite(file) ?: return
    RibbonButton(stringResource(if (fav.on) R.string.office_favourite_remove else R.string.office_favourite_add), tint, if (fav.on) R.drawable.ic_favorite else R.drawable.ic_favorite_border) { fav.toggle() }
}
