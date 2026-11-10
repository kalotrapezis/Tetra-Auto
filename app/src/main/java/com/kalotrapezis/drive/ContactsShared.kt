package com.kalotrapezis.drive
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Contacts' colour: the icon's own brown (Plan.md § Contacts › Home). */
internal val CONTACTS_COLOR = Color(0xFFBE7E4D)

internal fun contactsRoot() = ContactsFiles.root(TetraFolder.root(Environment.getExternalStorageDirectory()))

/** Contacts read ahead when Tetra opens (the user, 24 Oct: "no contacts" showed for a second before the list). */
/**
 * Contacts read ahead when Tetra opens, so the screen has them at once (the user, 25 Oct: "slow; taps don't answer until
 * it's all ready"): first the favourites and the screenful the list began with last time (their files' names are kept),
 * their faces made small at once, then everyone, sorted here and not on the screen's thread. [list] is what the screen shows; [all] everyone.
 */
internal object ContactsPreload {
    private const val FIRST = 24
    @Volatile var all: List<Contact>? = null
    /** The list as the screen shows it, sorted for [byLast]: the first screenful, then everyone. */
    var list by mutableStateOf<List<Contact>?>(null)
        private set
    @Volatile var byLast = false
        private set
    private fun order(c: Context) = File(c.cacheDir, "contacts-first.txt")

    fun warm(context: Context) = Thread { runCatching { load(context.applicationContext, ContactsPrefs.byLast(context)) } }.start()

    /** Reads the folder (only files changed since are read again) and answers everyone; one read at a time. */
    fun load(context: Context, byLast: Boolean): List<Contact> = synchronized(this) {
        if (list == null) runCatching {
            val first = order(context).readLines().map(::File).filter { it.isFile }.map(ContactsFiles::cached).filter { it.lines.isNotEmpty() }
            first.forEach { ContactThumbs.get(it.info.photo, ContactThumbs.SMALL) } // the favourites' faces ready before anyone else is read
            if (first.isNotEmpty()) { this.byLast = byLast; list = ContactsFiles.sorted(first, byLast) }
        }
        val everyone = ContactsFiles.sorted(ContactsFiles.all(contactsRoot()), byLast)
        // the favourites (their faces on top) and the first screenful: read first next time; a face is made again only
        // when its photo changed (ContactThumbs knows a photo by its bytes)
        val ahead = (everyone.filter { it.info.favourite } + everyone.take(FIRST)).distinct()
        ahead.forEach { ContactThumbs.get(it.info.photo, ContactThumbs.SMALL) }
        all = everyone; this.byLast = byLast; list = everyone
        runCatching { order(context).writeText(ahead.mapNotNull { it.file?.path }.joinToString("\n")) }
        everyone
    }
}

/**
 * Contacts' photos made small once, off the screen's thread: a row's face is 40 dp, and decoding the vCard's full photo
 * for each row as it came on screen kept taps waiting.
 */
internal object ContactThumbs {
    const val SMALL = 192
    private const val LARGE = 720
    private val cache = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(400)
    fun size(px: Int) = if (px <= SMALL) SMALL else LARGE
    private fun key(photo: String, size: Int) = "${photo.length}:${photo.hashCode()}:$size"
    fun cached(photo: String?, size: Int) = photo?.let { cache.get(key(it, size)) }
    fun get(photo: String?, size: Int): androidx.compose.ui.graphics.ImageBitmap? {
        photo ?: return null
        cached(photo, size)?.let { return it }
        val bytes = runCatching { java.util.Base64.getDecoder().decode(photo) }.getOrNull() ?: return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        val b = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return b.asImageBitmap().also { cache.put(key(photo, size), it) }
    }
}

internal object ContactsPrefs {
    private fun p(c: Context) = c.getSharedPreferences("contacts", Context.MODE_PRIVATE)
    fun byLast(c: Context) = p(c).getBoolean("byLast", false)
    fun setByLast(c: Context, on: Boolean) = p(c).edit().putBoolean("byLast", on).apply()
    /** Settings › Contacts › Birthdays go into: a calendar's folder; none chosen: Γενέθλια or Birthdays (ContactsBirthdays.calendar). */
    fun birthdays(c: Context) = p(c).getString("birthdays", null)
    fun setBirthdays(c: Context, dir: String) = p(c).edit().putString("birthdays", dir).apply()
    /** Events of the birthdays calendar the person said are no one here ("Not this person" in the review). */
    fun dismissed(c: Context): Set<String> = p(c).getStringSet("notLinked", emptySet())!!
    fun dismiss(c: Context, uids: Collection<String>) = p(c).edit().putStringSet("notLinked", dismissed(c) + uids).apply()
}

/** Tetra's own field labels, kept in English in the files (Google's and Apple's words), shown in the UI's language; a typed label as it is. */
private val CONTACT_LABELS = mapOf("Mobile" to R.string.contacts_label_mobile, "Home" to R.string.contacts_label_home, "Work" to R.string.contacts_label_work,
    "Main" to R.string.contacts_label_main, "Home fax" to R.string.contacts_label_home_fax, "Work fax" to R.string.contacts_label_work_fax, "Pager" to R.string.contacts_label_pager,
    "Other" to R.string.contacts_label_other, "Anniversary" to R.string.contacts_label_anniversary, "Date" to R.string.contacts_label_date, "Home page" to R.string.contacts_label_home_page,
    "Blog" to R.string.contacts_label_blog, "Profile" to R.string.contacts_label_profile, "Spouse" to R.string.contacts_label_spouse, "Partner" to R.string.contacts_label_partner,
    "Mother" to R.string.contacts_label_mother, "Father" to R.string.contacts_label_father, "Parent" to R.string.contacts_label_parent, "Brother" to R.string.contacts_label_brother,
    "Sister" to R.string.contacts_label_sister, "Child" to R.string.contacts_label_child, "Friend" to R.string.contacts_label_friend, "Assistant" to R.string.contacts_label_assistant,
    "Manager" to R.string.contacts_label_manager)
internal fun contactLabel(context: Context, id: String): String = CONTACT_LABELS[id]?.let(context::getString) ?: id

/** A contact's round face: the photo, or the initial on a soft brown (the plan's page). Decoded once per photo. */
@Composable internal fun ContactAvatar(i: ContactInfo, size: Dp) {
    val px = ContactThumbs.size(with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() })
    val photo by produceState(ContactThumbs.cached(i.photo, px), i.photo, px) { // the initial until it is decoded (off this thread)
        if (value == null && i.photo != null) value = withContext(Dispatchers.Default) { ContactThumbs.get(i.photo, px) }
    }
    val shown = photo
    if (shown != null) Image(shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape))
    else Box(Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFFD7A982), CONTACTS_COLOR, Color(0xFF8D5A3B)))), contentAlignment = Alignment.Center) {
        val first = i.name.trim().firstOrNull()?.takeIf { it.isLetterOrDigit() }
        if (first == null) Icon(painterResource(R.drawable.ic_person), contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
        else Text(first.uppercase(), color = Color.White, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Medium)
    }
}

/** "23 Oct · turns 41", "23 Oct 1985", "23 Oct"; [age] words an age (true: it is today). */
internal fun dateText(d: VDate, birthday: Boolean, today: LocalDate = LocalDate.now(), locale: Locale, age: (Int, Boolean) -> String): String {
    val day = d.inYear(today.year).format(DateTimeFormatter.ofPattern("d MMM", locale))
    if (d.year == null) return day
    val next = d.inYear(today.year).let { if (it.isBefore(today)) d.inYear(today.year + 1) else it }
    val age = next.year - d.year
    return if (birthday && age in 1..130) "$day · ${age(age, next == today)}" else "$day ${d.year}"
}
