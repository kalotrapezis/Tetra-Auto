package com.kalotrapezis.drive

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.key.type
import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PowerPoint's viewer: the slides one under the other (a slide tapped: presented from there), their speaker notes under
 * them when asked, Present — full screen, sideways, a swipe or a tap on the right for the next slide, on the left the one
 * before. Animations and transitions: later, with the user watching.
 */
@Composable
internal fun SlidesScreen(back: () -> Unit, open: String) {
    val context = LocalContext.current
    remember { WriterUnits.load(context) } // Size and position in Words' unit, cm or inches
    remember { OfficeFonts.assets = context.applicationContext.assets } // the fonts with Word's letter widths, as Words' and Tables'
    remember { Slides.hintTitle = context.getString(R.string.slides_hint_title); Slides.hintSubtitle = context.getString(R.string.slides_hint_subtitle)
        Slides.hintText = context.getString(R.string.slides_hint_text); Slides.hintPicture = context.getString(R.string.slides_picture) } // an empty placeholder's grey words

    val scope = rememberCoroutineScope()
    var file by remember(open) { mutableStateOf(Uri.parse(open).path?.let(::File)) } // Save as and Rename go on with another
    RememberOpened(file) // the Office home's Recents
    val odp = file?.extension.equals("odp", true)
    var deck by remember { mutableStateOf<Deck?>(null) }
    OpenTiming("Slides", deck != null)
    var failed by remember { mutableStateOf<String?>(null) }
    var notes by remember { mutableStateOf(false) }
    var presenting by remember { mutableStateOf<Int?>(null) }
    // editing (a .pptx): the edits apart from the deck as read; Undo the edit before; a slide chosen; a text box's words being typed
    var editing by remember { mutableStateOf(JustMade.take(open)) } // a file just made with New: editing at once
    var edit by remember { mutableStateOf<SlidesEdit.Edit?>(null) }
    val undos = remember { ArrayList<SlidesEdit.Edit>() }
    val redos = remember { ArrayList<SlidesEdit.Edit>() }
    var saved by remember { mutableStateOf<SlidesEdit.Edit?>(null) }
    var chosen by remember { mutableStateOf<Int?>(null) }
    var typing by remember { mutableStateOf<Pair<SlideItem.Shape, androidx.compose.ui.text.input.TextFieldValue>?>(null) }
    var saving by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var layouts by remember { mutableStateOf(false) } // New slide: the deck's layouts to choose from
    var sectioning by remember { mutableStateOf(false) } // Home › Section: add, rename or remove the one starting at the chosen slide
    var relaying by remember { mutableStateOf(false) } // …for Home › Layout: the chosen slide put on the one tapped
    var source by remember { mutableStateOf<File?>(null) } // File › Versions: the version opened, saved over the file by Save
    var showVersions by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    suspend fun load(f: File) = withContext(Dispatchers.IO) { runCatching { WriterLog.timed("Slides: deck read") { Slides.read(f) } } }
    LaunchedEffect(open) {
        WriterLog.init(context)
        val f = file ?: run { failed = context.getString(R.string.slides_file_cannot_open); return@LaunchedEffect }
        WriterLog.i("Slides: open .${f.extension} of ${f.length() / 1024} KB")
        load(f).onSuccess { deck = it; edit = SlidesEdit.start(it); saved = edit; WriterLog.i("Slides: ${it.slides.size} slides, ${it.slides.sumOf { s -> s.items.size }} shapes") }
            .onFailure { WriterLog.e("Slides: could not open", it); failed = context.getString(R.string.slides_could_not_open) }
        // Changes not saved when the app was closed: the draft opened in their place, unsaved; one older than the file (saved since) goes.
        val kept = slideDraft(context, f).takeIf { it.isFile } ?: return@LaunchedEffect
        if (deck == null || kept.lastModified() <= f.lastModified()) { kept.delete(); return@LaunchedEffect }
        val copy = withContext(Dispatchers.IO) { kept.copyTo(File(context.cacheDir, "slide-draft-${System.nanoTime()}.${f.extension}"), overwrite = true) }
        load(copy).onSuccess { d -> source = copy; deck = d; edit = SlidesEdit.start(d); saved = null; editing = true
            message = context.getString(R.string.slides_draft_back); WriterLog.i("Slides: recovery draft opened") }
            .onFailure { WriterLog.e("Slides: recovery draft", it) }
    }
    val changed = edit != saved
    var autoSave by remember { mutableStateOf(TextEditorStore(context).autoSave) } // File › AutoSave: Words' setting, one for both
    // Design › a theme, or a slide size: a copy of the file with them, read once (the deck shown and saved from it while they are chosen)
    val themed = remember { androidx.compose.runtime.mutableStateMapOf<Pair<SlideTheme?, SlidesSize.Size?>, Pair<File, Deck>>() }
    fun copyKey(e: SlidesEdit.Edit?) = e?.let { it.theme to it.size }?.takeIf { it.first != null || it.second != null }
    val base: Deck? = copyKey(edit)?.let { themed[it]?.second } ?: deck
    LaunchedEffect(edit?.theme, edit?.size) {
        val k = copyKey(edit) ?: return@LaunchedEffect
        val src = source ?: file ?: return@LaunchedEffect
        if (k in themed) return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching {
            val ext = src.extension.lowercase()
            val themedFile = k.first?.let { t -> File(context.cacheDir, "theme-${System.nanoTime()}.$ext").also { SlidesThemes.apply(src, t, it) } } ?: src
            val out = k.second?.let { z -> File(context.cacheDir, "size-${System.nanoTime()}.$ext").also { SlidesSize.resize(themedFile, z, it) } } ?: themedFile
            out to Slides.read(out)
        }.onFailure { WriterLog.e("Slides: a theme or size could not be applied", it) }.getOrNull() }?.let { themed[k] = it; WriterLog.i("Slides: theme ${k.first?.name}, size ${k.second}") }
            ?: run { message = context.getString(R.string.slides_could_not_apply) }
    }
    fun change(to: SlidesEdit.Edit) { edit?.let { undos += it; if (undos.size > 100) undos.removeAt(0) }; redos.clear(); edit = to }
    // a strip's preset shown at once (Design's themes and backgrounds): ✓ keeps it as one change, × puts the slides back
    var previewBefore by remember { mutableStateOf<SlidesEdit.Edit?>(null) }
    fun preview(to: (SlidesEdit.Edit) -> SlidesEdit.Edit) { val b = previewBefore ?: edit ?: return; previewBefore = b; edit = to(b) }
    fun previewKeep() { val b = previewBefore ?: return; val made = edit; previewBefore = null; edit = b; if (made != null && made != b) change(made) }
    fun previewDrop() { previewBefore?.let { edit = it }; previewBefore = null }
    /** Save: the edits into the file — or into [to] (Save as: going on with it; [copy], Save a copy: the file and its edits left as they are). */
    /** [rebase]: the saved file read again as the new start, Undo gone (a new slide size needs the slides as saved). */
    fun save(to: File? = null, copy: Boolean = false, rebase: Boolean = false, then: (() -> Unit)? = null) {
        val e = edit ?: return
        // with a theme chosen: saved from the themed copy, into the file
        val f0 = file ?: return; val f = to ?: f0; val d = base ?: return; val from = copyKey(e)?.let { themed[it]?.first } ?: source ?: f0
        if (copyKey(e) != null && copyKey(e) !in themed) return
        if (saving) return
        saving = true
        scope.launch {
            // Undo kept over a save (the tablet walk, 18 Oct; Tables' and Words' way): the edits stay on the deck as opened, so the file
            // as opened is kept aside the first time and every save writes the edits onto it again
            val opened = if (!copy && !rebase && source == null) File(context.cacheDir, "slides-opened-${System.nanoTime()}.${f0.extension}") else null
            val result = withContext(Dispatchers.IO) { runCatching {
                opened?.let { f0.copyTo(it, overwrite = true) }
                val temp = File(f.parentFile, ".${f.name}.tetra-saving")
                try {
                    WriterLog.timed("Slides: saved ${e.order.size} slides, ${e.texts.size} text boxes") { if (f.extension.equals("odp", true)) SlidesOdpEdit.save(from, d, e, temp) else SlidesEdit.save(from, d, e, temp) }
                    if (f.exists()) keepPrevious(context, f) // Writer's versions
                    check(temp.renameTo(f)) { context.getString(R.string.slides_could_not_save_name, f.name) }
                } finally { temp.delete() }
                if (rebase) Slides.read(f) else null
            } }
            saving = false
            result.onSuccess { nd -> if (copy) { message = context.getString(R.string.slides_saved_copy, f.name); then?.invoke(); return@onSuccess }
                slideDraft(context, f0).delete(); if (to != null) file = to
                if (nd != null) { deck = nd; edit = SlidesEdit.start(nd); saved = edit; source = null; undos.clear(); redos.clear(); chosen = chosen?.coerceAtMost(nd.slides.lastIndex) }
                else { saved = e; opened?.let { source = it } }
                message = context.getString(R.string.slides_saved); then?.invoke() }
                .onFailure { WriterLog.e("Slides: could not save", it); message = it.message ?: context.getString(R.string.slides_could_not_save) }
        }
    }
    var shapes by remember { mutableStateOf(false) } // Shape: the shapes to choose from
    var resizing by remember { mutableStateOf<Pair<Float, Float>?>(null) } // Design › Slide size: the size (pt) asked about, Maximize or Ensure fit
    var sizeAfterSave by remember { mutableStateOf<Pair<Float, Float>?>(null) } // … asked for with other changes unsaved: Save, then go on
    var slideSizing by remember { mutableStateOf(false) } // … Custom…: its width and height in cm
    var paragraphing by remember { mutableStateOf<ParaLook?>(null) } // Home › Paragraph: Words' page open on the box's first paragraph's look
    var officeSettings by remember { mutableStateOf(false) } // File › Settings: the ribbon's place and size
    var clip by remember { mutableStateOf<Pair<SlidesEdit.Added, SlidesEdit.Look?>?>(null) } // Copy's: what Paste puts on a slide
    var slideMenu by remember { mutableStateOf<Triple<Boolean, Int, androidx.compose.ui.geometry.Offset>?>(null) } // a right-click's menu: on the sidebar?, which slide, where
    var cellEdit by remember { mutableStateOf<Pair<Int, androidx.compose.ui.text.input.TextFieldValue>?>(null) } // a cell of the chosen table being typed
    var tableCell by remember { mutableStateOf(0) } // the cell of the chosen table last tapped (row × columns + column): where the Table tools work
    var notesTyping by remember { mutableStateOf<Pair<Int, androidx.compose.ui.text.input.TextFieldValue>?>(null) } // a slide's (uid) speaker notes being typed
    var item by remember { mutableStateOf<String?>(null) } // a picture, shape or text box chosen on the chosen slide (its id)
    var more by remember { mutableStateOf(emptyList<String>()) } // chosen with it (Select all, Choose several): what Group, Align, Distribute and Delete take
    var several by remember { mutableStateOf(false) } // Choose several: a tap adds a thing to what is chosen, or takes it away
    LaunchedEffect(item) { if (item == null) { more = emptyList() } }
    LaunchedEffect(chosen) { more = emptyList(); several = false }
    var ribbonTab by remember { mutableStateOf("Home") } // "Item": the contextual tab of what is chosen on the slide
    var themesPage by remember { mutableStateOf(false) } // Design › Themes: the panel of them
    var effectOptions by remember { mutableStateOf(false) } // Transitions or Animations › Options: the strip of sides and speeds
    var effectKinds by remember { mutableStateOf(false) } // Animations › Effect: the catalog of entrances, emphases and exits, a line each
    var selectionPane by remember { mutableStateOf(false) } // Home › Selection pane: every thing on the slide, front first
    var animPane by remember { mutableStateOf(false) } // Animations › Animation pane: the slide's effects in their order
    var animPainter by remember { mutableStateOf<List<SlideEffect>?>(null) } // Animations › Animation painter: the effects taken, put on the next thing chosen
    LaunchedEffect(ribbonTab, item) { effectOptions = false; effectKinds = false }

    /** [a] put on the chosen slide, then chosen. */
    fun put(a: SlidesEdit.Added) {
        val e = edit ?: return; val c = chosen ?: return; val uid = e.order[c].uid
        change(e.copy(added = e.added + (uid to (e.added[uid].orEmpty() + a)))); item = a.id
    }
    /** The chosen added thing changed by [f] (moved, sized), or gone (null). */
    /** The Table tab on a deck's own table: [op] at [k] ('r' 'c' a row or column before it, 'R' 'C' taken), its moved frame grown with it. */
    fun tableOp(op: Char, k: Int) {
        val e = edit ?: return; val c = chosen ?: return; val id = item ?: return; val uid = e.order[c].uid; val d = base ?: return
        val was = SlidesEdit.slide(d, e, e.order[c]).items.lastOrNull { it.anim == id } as? SlideItem.Table ?: return
        var next = e.copy(tables = e.tables + ((uid to id) to e.tables[uid to id].orEmpty() + (op to k)))
        val now = SlidesEdit.slide(d, next, next.order[c]).items.lastOrNull { it.anim == id } ?: return
        next.frames[uid to id]?.let { f -> val kx = if (was.w > 0) f[2] / was.w else 1f; val ky = if (was.h > 0) f[3] / was.h else 1f
            next = next.copy(frames = next.frames + ((uid to id) to listOf(f[0], f[1], f[2] + (now.w - was.w) * kx, f[3] + (now.h - was.h) * ky))) }
        change(next); WriterLog.i("Slides: own table $op$k")
    }
    fun alter(f: (SlidesEdit.Added) -> SlidesEdit.Added?) {
        val e = edit ?: return; val c = chosen ?: return; val uid = e.order[c].uid; val id = item ?: return
        change(e.copy(added = e.added + (uid to e.added[uid].orEmpty().mapNotNull { if (it.id == id) f(it) else it })))
    }
    /** Item [id] of slide [i] as it is now, a group as its bounds (a finger's drag starts from it). */
    fun current(e: SlidesEdit.Edit, i: Int, id: String): SlideItem? = base?.let { d -> SlidesEdit.box(SlidesEdit.slide(d, e, e.order[i]), id) }
    /** The chosen item moved or sized: [f] gives its x, y, w, h from where it is. */
    fun reframe(f: (SlideItem) -> List<Float>) {
        val e = edit ?: return; val c = chosen ?: return; val id = item ?: return
        val it = current(e, c, id) ?: return
        val (x, y, w, h) = f(it); change(SlidesEdit.framed(e, e.order[c].uid, id, x, y, w, h))
    }
    /** The ids on slide [c] now (its own items', a group's shapes'), which a new item's must not be. */
    fun taken(e: SlidesEdit.Edit, c: Int): List<String> = base?.let { d -> SlidesEdit.slide(d, e, e.order[c]).items.flatMap { listOfNotNull(it.anim, it.own, (it as? SlideItem.Shape)?.id) } }.orEmpty()
    /** Arrange › Rotate and Flip: the chosen item's turn changed by [f] (its place written with it, so a placeholder gets one of its own). */
    fun turn(f: (SlidesEdit.Turn) -> SlidesEdit.Turn) {
        val e = edit ?: return; val c = chosen ?: return; val id = item ?: return; val uid = e.order[c].uid
        val now = current(e, c, id) ?: return
        if (now.grouped) { message = context.getString(R.string.slides_group_cannot_turn); return }
        val t0 = e.turns[uid to id] ?: SlidesEdit.Turn(now.rotation, (now as? SlideItem.Shape)?.flipH ?: (now as? SlideItem.Picture)?.flipH ?: false,
            (now as? SlideItem.Shape)?.flipV ?: (now as? SlideItem.Picture)?.flipV ?: false)
        val framed = SlidesEdit.framed(e, uid, id, now.x, now.y, now.w, now.h)
        change(framed.copy(turns = framed.turns + ((uid to id) to f(t0)))); WriterLog.i("Slides: turned")
    }
    /**
     * A picture's crop made [c] (left, top, right, bottom of the whole picture), its frame changed so what still shows stays where
     * and as big as it was — as PowerPoint's Crop does; all 0 (Reset): the whole picture back round it.
     */
    fun recrop(c: List<Float>) {
        val e = edit ?: return; val cc = chosen ?: return; val id = item ?: return; val uid = e.order[cc].uid
        val p = current(e, cc, id) as? SlideItem.Picture ?: return
        val o = p.crop?.toList() ?: listOf(0f, 0f, 0f, 0f)
        val fw = p.w / (1 - o[0] - o[2]).coerceAtLeast(0.01f); val fh = p.h / (1 - o[1] - o[3]).coerceAtLeast(0.01f)
        val fx = p.x - o[0] * fw; val fy = p.y - o[1] * fh
        val framed = SlidesEdit.framed(e, uid, id, fx + c[0] * fw, fy + c[1] * fh, fw * (1 - c[0] - c[2]), fh * (1 - c[1] - c[3]))
        change(framed.copy(looks = framed.looks + ((uid to id) to (framed.looks[uid to id] ?: SlidesEdit.Look()).copy(crop = c)))); WriterLog.i("Slides: crop $c")
    }
    /** Arrange's order: the chosen item to the front ('f'), the back ('b'), one forward ('+') or one backward ('-'). */
    fun restack(how: Char) {
        val e = edit ?: return; val c = chosen ?: return; val id = item ?: return; val d = base ?: return; val uid = e.order[c].uid
        val ids = SlidesEdit.slide(d, e, e.order[c]).items.mapNotNull { it.anim }.distinct().toMutableList()
        val i = ids.indexOf(id).takeIf { it >= 0 } ?: return
        ids.removeAt(i)
        ids.add(when (how) { 'f' -> ids.size; 'b' -> 0; '+' -> minOf(i + 1, ids.size); else -> maxOf(i - 1, 0) }, id)
        change(e.copy(stack = e.stack + (uid to ids))); WriterLog.i("Slides: order $how")
    }
    /** The chosen item's looks changed by [f] (fill, border, words' colour), saved with the slide. */
    fun restyle(f: (SlidesEdit.Look) -> SlidesEdit.Look) {
        val e = edit ?: return; val c = chosen ?: return; val id = item ?: return; val k = e.order[c].uid to id
        change(e.copy(looks = e.looks + (k to f(e.looks[k] ?: SlidesEdit.Look())))); WriterLog.i("Slides: a box's looks changed")
    }
    // Picture: the phone's photo picker; a copy kept on the phone until Save puts it in the file
    fun putPicture(uri: android.net.Uri) {
        val d = deck ?: return
        scope.launch {
            val made = withContext(Dispatchers.IO) { runCatching {
                val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                val mime = o.outMimeType ?: ""
                val dir = File(context.cacheDir, "slide-pictures").apply { mkdirs() }
                // PNG, JPEG and GIF (animated, it plays) as they are; another kind (HEIC, WebP) made a JPEG, as PowerPoint can show it
                val (ext, data) = when (mime) { "image/png" -> "png" to bytes; "image/jpeg" -> "jpeg" to bytes; "image/gif" -> "gif" to bytes
                    else -> "jpeg" to java.io.ByteArrayOutputStream().also { out -> android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out) }.toByteArray() }
                val f = File(dir, "${System.nanoTime()}.$ext").apply { writeBytes(data) }
                val k = minOf(d.width * 0.6f / o.outWidth.coerceAtLeast(1), d.height * 0.6f / o.outHeight.coerceAtLeast(1))
                Triple(f, o.outWidth * k, o.outHeight * k) to ext
            }.onFailure { WriterLog.e("Slides: a picture could not be read", it) }.getOrNull() }
            val e = edit; val c = chosen
            if (made == null || e == null || c == null) { message = context.getString(R.string.slides_picture_unreadable_dot); return@launch }
            val (f, w, h) = made.first
            put(SlidesEdit.Added(e.newId(e.order[c].uid, taken(e, c)), (d.width - w) / 2, (d.height - h) / 2, w, h, image = f.path, ext = made.second))
            WriterLog.i("Slides: a picture put on a slide")
        }
    }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::putPicture) }
    /** A photo copied for a slide (a background, a picture replaced): PNG or JPEG as it is, another kind made a JPEG, as PowerPoint can show it. */
    fun copied(uri: android.net.Uri): Pair<File, String>? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val png = bytes.size > 4 && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte()
        val data = if (png || (bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte())) bytes
            else java.io.ByteArrayOutputStream().also { out -> android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out) }.toByteArray()
        File(File(context.cacheDir, "slide-pictures").apply { mkdirs() }, "${System.nanoTime()}.${if (png) "png" else "jpeg"}").apply { writeBytes(data) } to if (png) "png" else "jpeg"
    }.getOrNull()
    // Design › Background › Picture: the chosen slide's background
    val pickBack = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri -> uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val made = withContext(Dispatchers.IO) { copied(uri) } ?: run { message = context.getString(R.string.slides_picture_unreadable); return@launch }
            val e = edit ?: return@launch; val c = chosen ?: return@launch
            change(e.copy(backgrounds = e.backgrounds + (e.order[c].uid to SlidesEdit.Back(picture = made.first.path, ext = made.second)))); WriterLog.i("Slides: a picture background")
        }
    }
    /** Insert › Video: [film] (a copy on the phone, or a web address) put on the chosen slide with its picture [poster], [aspect] high for its width, in the slide's middle. */
    fun putVideo(film: String, poster: android.graphics.Bitmap, aspect: Float) {
        val d = deck ?: return; val e = edit ?: return; val c = chosen ?: return
        val pic = File(File(context.cacheDir, "slide-pictures").apply { mkdirs() }, "${System.nanoTime()}.png").apply { outputStream().use { poster.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        val w = minOf(d.width * 0.6f, d.height * 0.6f / aspect); val h = w * aspect
        put(SlidesEdit.Added(e.newId(e.order[c].uid, taken(e, c)), (d.width - w) / 2, (d.height - h) / 2, w, h, image = pic.path, video = film)); WriterLog.i("Slides: a video put on")
    }
    // Insert › Video › From this phone: the film copied (kept until Save puts it in the deck), its picture a frame a second in
    val pickVideo = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri -> uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val made = withContext(Dispatchers.IO) { OfficeVideo.fromPhone(context, uri, File(context.cacheDir, "slide-pictures")) } ?: run { message = context.getString(R.string.slides_video_unreadable); return@launch }
            putVideo(made.first, made.second, made.third)
        }
    }
    // Insert › Audio (PowerPoint's Audio on My PC): a sound from the phone (kept until Save puts it in the deck), its speaker in the slide's middle, played when tapped
    val pickSound = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri -> uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val d = deck ?: return@launch; val e = edit ?: return@launch; val c = chosen ?: return@launch
            val made = withContext(Dispatchers.IO) { OfficeVideo.soundFromPhone(context, uri, File(context.cacheDir, "slide-pictures"))?.let { it to File(File(context.cacheDir, "slide-pictures"), "${System.nanoTime()}.png").apply { writeBytes(OfficeVideo.speaker()) } } }
                ?: run { message = context.getString(R.string.slides_sound_unreadable); return@launch }
            val k = 40f
            put(SlidesEdit.Added(e.newId(e.order[c].uid, taken(e, c)), (d.width - k) / 2, (d.height - k) / 2, k, k, image = made.second.path, video = made.first)); WriterLog.i("Slides: a sound put on")
        }
    }
    /** Insert › Video › Online video: a web address (YouTube's picture fetched for it; else a dark one saying what it is), opened when tapped in a show. */
    fun putOnlineVideo(url: String) = scope.launch {
        val poster = withContext(Dispatchers.IO) { OfficeVideo.poster(url) }
        putVideo(url, poster, poster.height / poster.width.coerceAtLeast(1).toFloat())
    }
    /** A video's film to play: a web address as it is, a copy on the phone, else its part taken out of the deck's file. */
    suspend fun filmOf(m: String): String? = withContext(Dispatchers.IO) { OfficeVideo.film(m, source ?: file, context.cacheDir) }
    var filming by remember { mutableStateOf<Pair<Int, OfficeVideo.Spot>?>(null) } // a video playing in its frame while editing (its slide, where, its film)
    /** The chosen picture made [f] (Replace, Edit drawing), in its frame: one put on here takes it as its own, the file's own in its looks. */
    fun repicture(f: File) {
        val e = edit ?: return; val c = chosen ?: return; val id = item ?: return; val uid = e.order[c].uid
        if (e.added[uid]?.any { it.id == id } == true) alter { it.copy(image = f.path, ext = f.extension) }
        else restyle { it.copy(image = f.path) }
        WriterLog.i("Slides: a picture replaced")
    }
    // Replace picture (PowerPoint's Change Picture)
    val pickReplace = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri -> uri ?: return@rememberLauncherForActivityResult
        scope.launch { withContext(Dispatchers.IO) { copied(uri) }?.let { repicture(it.first) } ?: run { message = context.getString(R.string.slides_picture_unreadable) } }
    }
    fun leave() { if (changed) leaving = true else { file?.let { slideDraft(context, it).delete() }; back() } }
    // The app sent to the background with changes not saved (Android may close it then): kept as a draft, as Words' and Tables'.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        // (AutoSave's setting is Words', one for both)
        val observer = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            val f = file; val e = edit
            if (ev != androidx.lifecycle.Lifecycle.Event.ON_STOP || f == null || e == null || saving) return@LifecycleEventObserver
            val draft = slideDraft(context, f)
            if (e == saved) { draft.delete(); return@LifecycleEventObserver }
            // AutoSave on: the file itself saved as the app goes away (saving re-reads the deck, so while one works Undo is kept)
            if (autoSave) { save(); WriterLog.i("Slides: AutoSave"); return@LifecycleEventObserver }
            val d = copyKey(e)?.let { themed[it]?.second } ?: deck ?: return@LifecycleEventObserver
            val from = copyKey(e)?.let { themed[it]?.first } ?: source ?: f
            scope.launch(Dispatchers.IO) { runCatching {
                draft.parentFile?.mkdirs()
                val temp = File(draft.parentFile, ".${draft.name}.saving")
                if (f.extension.equals("odp", true)) SlidesOdpEdit.save(from, d, e, temp) else SlidesEdit.save(from, d, e, temp)
                check(temp.renameTo(draft)) { "Could not keep the draft." }
                WriterLog.i("Slides: recovery draft kept, ${draft.length() / 1024} KB")
            }.onFailure { WriterLog.e("Slides: recovery draft", it) } }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    /** Whether [hit]'s words can be typed: the file's own text boxes and shapes with words, and what was put on ([a]) but a picture or table. */
    fun typable(hit: SlideItem?, a: SlidesEdit.Added?) = hit is SlideItem.Shape && !hit.grouped && hit.id != null && (if (a == null) hit.text != null else a.image == null && a.prst != "table")
    fun typeInto(hit: SlideItem.Shape) { val t = if (hit.hint) "" else SlidesEdit.lines(hit).joinToString("\n"); typing = hit to androidx.compose.ui.text.input.TextFieldValue(t, androidx.compose.ui.text.TextRange(t.length)) }
    /** Words' equation [m] drawn as a picture (4 pixels a point, its letters 28 pt), kept on the phone until Save: its file and size (pt). */
    fun mathPicture(m: MathNode): Triple<String, Float, Float>? = runCatching {
        val dr = MathDrawable(m, 28f); val w = dr.widthPt.coerceAtLeast(1f); val h = dr.heightPt.coerceAtLeast(1f)
        val bmp = android.graphics.Bitmap.createBitmap((w * 4).toInt().coerceAtLeast(1), (h * 4).toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
        dr.setBounds(0, 0, bmp.width, bmp.height); dr.draw(android.graphics.Canvas(bmp))
        val f = File(File(context.cacheDir, "slide-pictures").apply { mkdirs() }, "${System.nanoTime()}.png")
        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        Triple(f.path, w, h)
    }.onFailure { WriterLog.e("Slides: an equation could not be drawn", it) }.getOrNull()
    /** Insert › Text box (as PowerPoint's): a box of words alone across the slide's middle, typed into at once. */
    fun newTextBox() {
        val d = deck ?: return; val ee = edit ?: return; val c = chosen ?: return
        val a = SlidesEdit.Added(ee.newId(ee.order[c].uid, taken(ee, c)), d.width * 0.2f, d.height * 0.42f, d.width * 0.6f, 40f, prst = SlidesEdit.TEXT_BOX)
        put(a); typing = SlideItem.Shape(a.x, a.y, a.w, a.h, 0f, null, null, id = a.id).also { it.anim = a.id } to androidx.compose.ui.text.input.TextFieldValue("")
        WriterLog.i("Slides: a text box put on")
    }
    /**
     * Insert › WordArt (Words' looks and bends) on [b]'s slide [cc]: on the chosen box with words, else a new text box of Words'
     * "Your text here" (its size, bold, centred), as big as its words and, bent, with room for the bend as Words makes one. The edit and the box's id.
     */
    fun wordArt(b: SlidesEdit.Edit, cc: Int, style: ArtStyle, bend: ArtBend): Pair<SlidesEdit.Edit, String>? {
        val d = base ?: return null; val uid = b.order[cc].uid
        val fx = TextEffects(if (style.outline) artOutline(style.color) else null, style.shadow, if (style.glow && !odp) artGlow(style.color) else null)
        val colour = 0xFF000000.toInt() or style.color
        val warp = bend.warp?.word ?: ""
        val own = item?.let { id -> SlidesEdit.slide(d, b, b.order[cc]).items.lastOrNull { it.anim == id } as? SlideItem.Shape }?.takeIf { it.text != null && it.id != null && !it.grouped }
        if (own != null) { val k = uid to item!!; val l = b.looks[k] ?: SlidesEdit.Look()
            return b.copy(looks = b.looks + (k to l.copy(text = colour, effects = fx, warp = warp, autofit = if (bend.warp != null) 'n' else l.autofit))) to item!! }
        val words = context.getString(R.string.slides_your_text_here)
        val (w, h) = android.graphics.Paint().apply { textSize = PictureXml.ART_PT; typeface = android.graphics.Typeface.DEFAULT_BOLD }.measureText(words).let { it * 1.1f + 30f to PictureXml.ART_PT * 1.25f + 10f }
            .let { (w0, h0) -> when (bend.warp) { null -> w0 to h0; TextWarp.Circle -> maxOf(w0 * 0.6f, 144f).let { it to it }; else -> w0 to maxOf(h0, w0 * 0.35f) } }
        val id = b.newId(uid, taken(b, cc))
        val a = SlidesEdit.Added(id, (d.width - w) / 2, (d.height - h) / 2, w, h, prst = SlidesEdit.TEXT_BOX)
        return b.copy(added = b.added + (uid to (b.added[uid].orEmpty() + a)), texts = b.texts + ((uid to id) to listOf(words)),
            looks = b.looks + ((uid to id) to SlidesEdit.Look(text = colour, size = PictureXml.ART_PT, bold = true, align = DocAlign.Center, effects = fx, warp = warp,
                autofit = if (bend.warp != null) 'n' else null))) to id
    }
    /** Insert › Symbol, Date, Line break (Words'): [words] where the typing cursor is — the chosen box opened to type in first (at its end), else a new text box. */
    fun putWords(words: String) {
        if (typing == null) {
            val c = chosen ?: return; val ee = edit ?: return
            val hit = base?.let { d -> SlidesEdit.slide(d, ee, ee.order[c]) }?.items?.lastOrNull { it.anim == item && item != null }
            if (typable(hit, ee.added[ee.order[c].uid]?.firstOrNull { it.id == item })) typeInto(hit as SlideItem.Shape) else newTextBox()
        }
        val (box, v) = typing ?: return
        typing = box to androidx.compose.ui.text.input.TextFieldValue(v.text.replaceRange(v.selection.min, v.selection.max, words), androidx.compose.ui.text.TextRange(v.selection.min + words.length))
    }
    // a desktop's notes pane let go when a shape or another slide is clicked: the keys are the slide's again (Delete deletes the shape)
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    // voice typing (Words'): what was said in at the typing cursor
    val said = rememberDictation { putWords(it) }
    LaunchedEffect(item, chosen) { if (OfficeUi.top && notesTyping != null) focusManager.clearFocus() }
    BackHandler { when { typing != null -> typing = null; cellEdit != null -> cellEdit = null; notesTyping != null && !OfficeUi.top -> notesTyping = null; layouts -> layouts = false; shapes -> shapes = false; item != null -> item = null; chosen != null -> chosen = null; else -> leave() } }
    val view = remember(base, edit) { base?.let { d -> edit?.let { SlidesEdit.view(d, it) } ?: d } }
    /** The chosen box with words of the file's own (a text box, a placeholder, a shape with words), as it shows: what Home's text tools change. */
    fun chosenWords(): SlideItem.Shape? = (view?.slides?.getOrNull(chosen ?: -1)?.items?.lastOrNull { it.anim == item } as? SlideItem.Shape)?.takeIf { it.text != null && it.id != null && !it.grouped }
    var painter by remember { mutableStateOf<SlidesEdit.Look?>(null) } // Format painter: the look taken, put on the next box chosen
    var copiedLook by remember { mutableStateOf<SlidesEdit.Look?>(null) } // Selection › Copy formatting: kept for Paste formatting (as in Words)
    var strip by remember { mutableStateOf<DesignGallery?>(null) } // Home › Font, Insert › Drop cap: Words' strip of cards in the ribbon's place
    var stripBefore by remember { mutableStateOf<SlidesEdit.Edit?>(null) } // …the edit before a card tapped there (Cancel goes back to it)
    var stripPicked by remember { mutableStateOf<Any?>(null) } // …and the card tapped
    var dropOptions by remember { mutableStateOf(false) } // Drop cap › More…: Words' dialog, raised
    var linking by remember { mutableStateOf(false) } // Insert › Link: Words' link dialog
    var footing by remember { mutableStateOf(false) } // Insert › Header & footer
    var symbols by remember { mutableStateOf(false) } // Insert › Symbol: Words' symbol page
    var dating by remember { mutableStateOf(false) } // Insert › Date: Words' date forms
    var commentsOn by remember { mutableStateOf(false) } // Review › Comments: the chosen slide's comments beside it (over it on a phone)
    var commentAsk by remember { mutableStateOf<Pair<Char, DocComment?>?>(null) } // Words' comment dialog: 'n' new, 'e' edit, 'r' reply (to the comment)
    var hereComment by remember { mutableStateOf<String?>(null) } // the comment tapped (its marker or card)
    var rotating by remember { mutableStateOf(false) } // Arrange › Rotate: Words' rotate dialog
    var sizing by remember { mutableStateOf(false) } // Arrange › Size and position: the numbers
    var boxLayout by remember { mutableStateOf<SlideText?>(null) } // a text box's Layout: its words' margins, wrap and columns as they are
    var shaping by remember { mutableStateOf(false) } // Shape options: Words' page of a shape's styles, fill, outline, size, angle and alt text
    var alting by remember { mutableStateOf(false) } // Alt text of what is not a shape (a picture, a chart, a table, a text box)
    var drawing by remember { mutableStateOf(false) } // Draw › Draw on slide: Words' drawing page over the chosen slide
    var redrawing by remember { mutableStateOf<Pair<SlideItem.Picture, List<InkStroke>>?>(null) } // Edit drawing: a drawing's own strokes, drawn on again
    var sorter by remember { mutableStateOf(false) } // View › Slide sorter: every slide small in a grid
    var outline by remember { mutableStateOf(false) } // View › Outline: every slide's words in one list
    // Review › Read aloud (Words' reader): what is read, and the slide each paragraph is on (the slides follow the reading)
    val reader = rememberReadAloud()
    var reading by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    LaunchedEffect(reader.at) { reading.getOrNull(reader.at)?.first?.let { if (chosen != it) { chosen = it; item = null } } }
    var sourcesOpen by remember { mutableStateOf(false) } // References › Sources: Words' list of them
    var sourceEdit by remember { mutableStateOf<DocSource?>(null) }; var sourceNew by remember { mutableStateOf(false) } // …one in Words' source page
    var citing by remember { mutableStateOf(false) } // References › Insert citation: Words' dialog
    var inked by remember { mutableStateOf<Map<Int, List<List<androidx.compose.ui.geometry.Offset>>>?>(null) } // the show's pen ink, to keep or not
    var savingAs by remember { mutableStateOf(false) } // File › Save as: the new name
    var renaming by remember { mutableStateOf(false) } // File › Rename
    var inspecting by remember { mutableStateOf(false) } // File › Inspect: notes and comments taken out before sharing
    var trimming by remember { mutableStateOf<Pair<List<Int>, Int>?>(null) } // Video › Trim: the film's start and end left out (ms) and its length
    var rehearsing by remember { mutableStateOf(false) } // Slide show › Rehearse timings: the show running with a timer
    var rehearsed by remember { mutableStateOf<Map<Int, Long>?>(null) } // …and the times it took, to keep or not
    var finding by remember { mutableStateOf(false) } // Find and Replace across the slides (Words' bar)
    var query by remember { mutableStateOf("") }; var replacement by remember { mutableStateOf("") }; var replacing by remember { mutableStateOf(false) }
    var findAt by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var slideZoom by remember { mutableStateOf(1f) } // View › Zoom: the slide being edited bigger than fits, moved about by dragging its empty parts
    var rulers by remember { mutableStateOf(false) } // View › Ruler: Words' rulers along the slide's top and side (a tablet's layout)
    // Review › Spelling: the words left to look at (slide, box, line, where in it, the word), suggestions, words ignored
    var spelling by remember { mutableStateOf<Triple<List<SlideSpell>, Map<String, List<String>>, Set<String>>?>(null) }
    val speller = remember { WriterSpelling(context) }
    var grid by remember { mutableStateOf(false) } // View › Gridlines: a grid and the middle guides over the slide, what is dragged snapping to them
    var notesPage by remember { mutableStateOf(false) } // View › Notes page: the notes pane under the slide (a desktop always has it)
    var marked by remember { mutableStateOf(setOf<Int>()) } // …and the slides chosen there (their uids)
    var cropping by remember { mutableStateOf<android.graphics.Bitmap?>(null) } // a picture's crop: the whole picture in Words' crop editor
    /** Find: every place [query] is in the slides' words (any case) — the slide, the box, its line and the letters there. */
    class Found(val slide: Int, val id: String, val line: Int, val range: IntRange)
    fun found(): List<Found> = if (query.isEmpty()) emptyList() else view?.slides.orEmpty().flatMapIndexed { i, s ->
        s.items.filterIsInstance<SlideItem.Shape>().filter { it.id != null && it.text != null && !it.hint && !it.grouped }.sortedWith(compareBy({ it.y }, { it.x })).flatMap { sh ->
            SlidesEdit.lines(sh).flatMapIndexed { li, line -> Regex(Regex.escape(query), RegexOption.IGNORE_CASE).findAll(line).map { Found(i, sh.id!!, li, it.range) }.toList() } }
    }
    /** The finds [ms] made [replacement] as one step of Undo: each box's lines with them changed from the end (each line keeps its paragraph's look). */
    fun replaceFound(ms: List<Found>) {
        val e = edit ?: return; val slides = view?.slides ?: return
        var texts = e.texts
        for ((key, list) in ms.groupBy { it.slide to it.id }) {
            val sh = slides[key.first].items.filterIsInstance<SlideItem.Shape>().firstOrNull { it.id == key.second } ?: continue
            val lines = SlidesEdit.lines(sh).toMutableList()
            for (m in list.sortedByDescending { it.line * 100_000 + it.range.first }) lines[m.line] = lines[m.line].replaceRange(m.range, replacement)
            texts = texts + ((e.order[key.first].uid to key.second) to lines)
        }
        change(e.copy(texts = texts)); WriterLog.i("Slides: replaced ${ms.size}")
    }
    // References (the user's choices, 17 Oct): the deck's sources and style; citations are words, found by the sources and the style
    val sources = edit?.sources ?: deck?.sources.orEmpty()
    val citeStyle = edit?.citeStyle ?: deck?.citeStyle ?: CiteStyle.APA
    /** A source's citation as the slides show it (shortcut: one source and no page a citation; Words' fields carry more). */
    fun cite(s: DocSource, st: CiteStyle) = st.parenthetical(listOf(s))
    /** The tags cited in the slides' words now. */
    fun cited(): Set<String> = view?.slides.orEmpty().flatMap { sl -> sl.items.filterIsInstance<SlideItem.Shape>().mapNotNull { it.text?.paras?.joinToString("\n") { p -> p.runs.joinToString("") { r -> r.text } } } }
        .joinToString("\n").let { all -> sources.filter { cite(it, citeStyle) in all }.map { it.tag }.toSet() }
    /** The sources made [list] and the style [st], as one step of Undo: every citation in the slides' words written again in its new form (live citations). */
    fun resource(list: List<DocSource>, st: CiteStyle, note: String? = null) {
        val e = edit ?: return
        val swaps = sources.mapNotNull { s -> list.firstOrNull { it.tag == s.tag }?.let { n -> cite(s, citeStyle) to cite(n, st) } }.filter { it.first != it.second }
        var texts = e.texts
        if (swaps.isNotEmpty()) view?.slides?.forEachIndexed { i, sl -> sl.items.filterIsInstance<SlideItem.Shape>().filter { it.id != null && it.text != null && !it.hint && !it.grouped }.forEach { sh ->
            val lines = SlidesEdit.lines(sh); val now = lines.map { l -> swaps.fold(l) { acc, (a, b) -> acc.replace(a, b) } }
            if (now != lines) texts = texts + ((e.order[i].uid to sh.id!!) to now) } }
        change(e.copy(sources = list, citeStyle = st, texts = texts)); note?.let { message = it }
        WriterLog.i("Slides: sources ${list.size}, ${st.name}, ${swaps.size} citations rewritten")
    }
    /** Words' import (a Tetra sources file, or Claude's answer pasted): the new ones added, none twice. */
    fun importSources(text: () -> String?) {
        val read = runCatching { text()?.let(SourcesImport::read) ?: throw IllegalArgumentException(context.getString(R.string.slides_nothing_to_import)) }
        val r = read.getOrNull() ?: run { message = read.exceptionOrNull()?.officeMessage(context) ?: context.getString(R.string.slides_file_unreadable); return }
        val added = ArrayList<DocSource>()
        for (n in r.sources) if (!SourcesImport.same(n, sources + added)) added += n.copy(tag = Citations.tagFor(if (odp) OfficeKind.Odt else OfficeKind.Docx, n, (sources + added).map { it.tag }, citeStyle))
        resource(sources + added, citeStyle, context.resources.getQuantityString(R.plurals.slides_sources_added, added.size, added.size) + (if (r.sources.size > added.size) context.getString(R.string.slides_sources_already_here, r.sources.size - added.size) else ""))
    }
    // Home › Reuse slides: another deck read (a copy on the phone), its slides to choose
    var reusing by remember { mutableStateOf<Deck?>(null) }
    val pickDeck = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { u -> u ?: return@rememberLauncherForActivityResult
        message = context.getString(R.string.slides_opening_deck)
        scope.launch {
            val read = withContext(Dispatchers.IO) { runCatching {
                val mime = context.contentResolver.getType(u).orEmpty()
                val ext = if ("opendocument" in mime || u.toString().endsWith(".odp", true)) "odp" else "pptx"
                val f = File(File(context.cacheDir, "reused-decks").apply { mkdirs() }, "${System.nanoTime()}.$ext")
                context.contentResolver.openInputStream(u)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
                Slides.read(f)
            }.onFailure { WriterLog.e("Slides: a deck to reuse could not be read", it) }.getOrNull() }
            message = if (read == null) context.getString(R.string.slides_could_not_open_deck) else null
            reusing = read
        }
    }
    val pickSources = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { u ->
        u?.let { importSources { context.contentResolver.openInputStream(it)?.use { s -> String(s.readBytes(), Charsets.UTF_8) } } } }
    /** A slide's title as the deck shows it: its highest box's words. */
    fun titleOf(sl: Slide) = sl.items.filterIsInstance<SlideItem.Shape>().filter { it.text != null && !it.hint && it.id !in sl.footerIds }.minByOrNull { it.y }?.let { SlidesEdit.lines(it).joinToString(" ").trim() }
    /** A new slide of the deck's title-only layout (else one with only a title, else the first) at [at] with [title] and words [runs] under it. */
    fun madeSlide(e: SlidesEdit.Edit, d: Deck, at: Int, title: String, runs: List<List<DocRun>>, hang: Float): SlidesEdit.Edit {
        val k = d.layouts.indexOfFirst { it.name.equals("Title Only", true) }.takeIf { it >= 0 }
            ?: d.layouts.indices.firstOrNull { i -> d.layouts[i].preview.items.count { it is SlideItem.Shape && it.hint } == 1 } ?: 0
        val uid = e.next(); val hint = d.layouts.getOrNull(k)?.preview?.items?.filterIsInstance<SlideItem.Shape>()?.filter { it.hint }?.minByOrNull { it.y }
        val top = (hint?.let { it.y + it.h } ?: d.height * 0.18f) + 8f
        val box = SlidesEdit.Added(e.newId(uid), d.width * 0.08f, top, d.width * 0.84f, 40f, prst = SlidesEdit.TEXT_BOX, runs = runs, hang = hang)
        return e.copy(order = e.order.toMutableList().apply { add(at.coerceIn(0, size), SlidesEdit.Entry(-1, uid, k)) },
            texts = if (hint?.id != null) e.texts + ((uid to hint.id) to listOf(title)) else e.texts, added = e.added + (uid to listOf(box)))
    }
    /**
     * References › References slide (the user's choices, 17 Oct): the list in the deck's style at its end, a slide titled by the style's
     * heading, over more slides when long ("(continued)"); pressed again, the old ones (any style's heading) go and new ones come (Update).
     */
    fun referencesSlides() {
        val d = base ?: return; val e0 = edit ?: return
        if (sources.isEmpty()) { message = context.getString(R.string.slides_no_sources); return }
        val headings = CiteStyle.entries.map { it.heading }.toSet()
        val shownNow = view?.slides.orEmpty()
        var e = e0.copy(order = e0.order.filterIndexed { i, _ -> shownNow.getOrNull(i)?.let(::titleOf)?.removeSuffix(" (continued)") !in headings })
        val entries = citeStyle.sorted(sources).map { s -> citeStyle.entry(s).map { it.copy(sizePt = 16f) } }
        // as many as fit under the title: their height as Words' engine lays them out (a line each where it cannot: the tests)
        val room = d.height * 0.72f
        fun tall(list: List<List<DocRun>>) = runCatching { SlidesText.height(SlideText(list.map { SlidePara(DocPara(it), ParaLook(left = 36f, firstLine = -36f, listIndented = true)) }, 't', floatArrayOf(7.2f, 3.6f, 7.2f, 3.6f)), d.width * 0.84f) }
            .getOrElse { list.sumOf { r -> r.sumOf { it.text.length } / 70 + 1 } * 19.2f }
        val pages = ArrayList<MutableList<List<DocRun>>>()
        for (en in entries) { val last = pages.lastOrNull(); if (last != null && tall(last + listOf(en)) <= room) last += en else pages += mutableListOf(en) }
        pages.forEachIndexed { n, list -> e = madeSlide(e, d, e.order.size, citeStyle.heading + if (n > 0) " (continued)" else "", list, 36f) }
        change(e); chosen = e.order.size - pages.size; WriterLog.i("Slides: ${pages.size} references slides, ${entries.size} entries")
        message = context.resources.getQuantityString(R.plurals.slides_references_on_slides, pages.size, entries.size, pages.size)
    }
    /** References › Contents slide: after the first slide, each slide's title and number, a tap on one in a show going there; pressed again, made anew (Update). */
    fun contentsSlide() {
        val d = base ?: return; val e0 = edit ?: return
        val shownNow = view?.slides.orEmpty()
        var e = e0.copy(order = e0.order.filterIndexed { i, _ -> shownNow.getOrNull(i)?.let(::titleOf) != "Contents" })
        val rest = e.order.drop(1)
        // its numbers as they will be: the contents slide is the second
        val lines = rest.mapIndexedNotNull { k, en -> shownNow.getOrNull(e0.order.indexOf(en))?.let(::titleOf)?.takeIf { it.isNotBlank() }?.let { t ->
            listOf(DocRun("${k + 3}   ", sizePt = 20f, color = 0x808080), DocRun(t, sizePt = 20f, link = "slide:${en.uid}")) } }
        e = madeSlide(e, d, 1, "Contents", lines, 0f)
        change(e); chosen = 1; WriterLog.i("Slides: a contents slide of ${lines.size}"); message = context.getString(R.string.slides_contents_made, lines.size)
    }
    /** Gridlines' spacing (pt): half a centimetre, or a quarter inch in Words' inches. */
    fun gridStep() = if (WriterUnits.inches) 18f else 14.173f
    /** The name of the section slide [i] starts, if one does (Home › Section). */
    fun sectionAt(i: Int): String? = edit?.let { ee -> base?.let { d -> ee.order.getOrNull(i)?.uid?.let { uid -> ee.sectionsNow(d).firstOrNull { it.second == uid }?.first } } }
    /** The chosen slide's comments changed by [f] (Review › Comments), as one step of Undo. */
    fun recomment(f: (List<SlideComment>) -> List<SlideComment>) {
        val e = edit ?: return; val c = chosen ?: return
        change(e.copy(comments = e.comments + (e.order[c].uid to f(view?.slides?.getOrNull(c)?.comments.orEmpty()))))
    }
    /** Review's Previous and Next comment: the next slide (on or back, round) with comments, its pane open at the first. */
    fun goComment(on: Boolean) {
        val all = view?.slides ?: return; val c = chosen ?: 0
        val order = if (on) (1..all.size).map { (c + it) % all.size } else (1..all.size).map { (c - it + all.size) % all.size }
        val to = order.firstOrNull { all[it].comments.isNotEmpty() } ?: run { message = context.getString(R.string.slides_no_comments_deck); return }
        chosen = to; item = null; commentsOn = true; hereComment = all[to].comments.first().comment.id
    }
    var equating by remember { mutableStateOf<Pair<String?, MathNode?>?>(null) } // Insert › Equation, Edit equation: Words' editor (the item's id; null: a new one)
    var charting by remember { mutableStateOf<Pair<String?, DocChart>?>(null) } // Insert › Chart, Edit data: Words' chart editor (the chart's id; null: a new one)
    var dropLines by remember { androidx.compose.runtime.mutableIntStateOf(3) } // Drop cap: how many lines tall (the strip's setting)
    /** The chosen box's words' size before a raised first letter: what Drop cap's lines are counted in. */
    fun wordsSize(box: SlideItem.Shape?): Float {
        val p = box?.text?.paras?.firstOrNull { pp -> pp.runs.any { it.text.isNotBlank() } } ?: return 18f
        val k = p.runs.indexOfFirst { it.text.isNotEmpty() }
        return SlidesEdit.isRaised(p.runs, k) ?: p.runs.getOrNull(k)?.sizePt ?: 18f
    }
    /**
     * Drop cap, dropped (17 Oct): [b] with item [id] of slide [c]'s first letter in a text box of its own, [n] lines tall, beside the paragraph's
     * first [n] lines — the paragraph split where Words' engine breaks its line [n], the lines before standing in by the letter's width and
     * [distance]. PowerPoint and Impress have no drop cap, so it looks right everywhere but doesn't move with the words. Null: nothing to drop.
     */
    fun dropped(b: SlidesEdit.Edit, c: Int, id: String, n: Int, font: String? = null, color: Int? = null, distance: Float = 0f): SlidesEdit.Edit? {
        val d = base ?: return null; val e = b.order[c]
        val box = SlidesEdit.slide(d, b, e).items.lastOrNull { it.anim == id } as? SlideItem.Shape ?: return null
        val t = box.text ?: return null
        val f = t.paras.indexOfFirst { p -> p.runs.any { it.text.isNotBlank() } }.takeIf { it >= 0 } ?: return null
        val p = t.paras[f]; val r = p.runs.first { it.text.isNotEmpty() }
        val words = SlidesEdit.lines(box).toMutableList()
        val letter = words[f].substring(0, words[f].offsetByCodePoints(0, 1)); val rest = words[f].substring(letter.length)
        // the letter's size: its capital as tall as from the first line's capitals to the last line's foot
        val size = wordsSize(box)
        val face = font ?: DocStyles.Plain.fontName(r.font)
        val paint = android.graphics.Paint().apply { typeface = OfficeFonts.styled(OfficeFonts.typeface(face), r.bold == true, r.italic == true); textSize = 100f }
        val cap = android.graphics.Rect().also { paint.getTextBounds("H", 0, 1, it) }.height() / 100f
        val step = SlidesText.drop(t, box.w, f, p.look.left + size * n * 0.7f, n)?.step ?: return null
        val big = (step + cap * size) / cap
        val left = p.look.left + paint.measureText(letter) * big / 100f + distance + size * 0.15f
        val split = SlidesText.drop(t, box.w, f, left, n) ?: return null
        words[f] = rest.substring(0, split.at).trimEnd()
        val more = split.at < rest.length
        if (more) words.add(f + 1, rest.substring(split.at))
        val k = e.uid to id
        val nu = b.newId(e.uid, taken(b, c))
        // the letter's box: a text box (insets 7.2 and 3.6 pt) its left at the words' edge, its letter's foot on line n's
        val made = b.copy(texts = b.texts + (k to words) + ((e.uid to nu) to listOf(letter)),
            looks = b.looks + (k to (b.looks[k] ?: SlidesEdit.Look()).copy(raise = null, drop = left, dropRest = more)) +
                ((e.uid to nu) to SlidesEdit.Look(size = big, font = face, bold = r.bold, italic = r.italic, text = (color ?: r.color)?.or(0xFF000000.toInt()))),
            added = b.added + (e.uid to b.added[e.uid].orEmpty() + SlidesEdit.Added(nu, box.x + t.insets[0] + p.look.left - 7.2f, box.y, paint.measureText(letter) * big / 100f * 1.15f + 14.4f, big * 1.2f, prst = SlidesEdit.TEXT_BOX)))
        // where the words now stand (from the top, middle or bottom of their box), and the letter's baseline in its own box
        val shown = SlidesEdit.slide(d, made, e).items
        val now = (shown.lastOrNull { it.anim == id } as? SlideItem.Shape)?.text ?: return null
        val laid = SlidesText.block(now, box.w); val room = box.h - now.insets[1] - now.insets[3]
        val top = box.y + now.insets[1] + when (now.anchor) { 'c' -> (room - laid.height) / 2; 'b' -> room - laid.height; else -> 0f }
        val base0 = SlidesText.drop(now, box.w, f, left, n)?.top ?: return null
        val mine = (shown.lastOrNull { it.anim == nu } as? SlideItem.Shape)?.text ?: return null
        val foot = SlidesText.block(mine, 1000f).pieces.firstOrNull()?.let { it.y + it.layout.getLineBaseline(0) / 20f } ?: return null
        val y = top + base0 + split.step - mine.insets[1] - foot
        return made.copy(added = made.added + (e.uid to made.added[e.uid].orEmpty().map { if (it.id == nu) it.copy(y = y) else it }))
    }
    // something chosen on the slide: its tab, unless Transitions or Animations is in use (they work on it too); a box with words
    // keeps Home (17 Oct: its text tools are there, as PowerPoint's), and takes the look a Format painter holds
    // Animation painter (PowerPoint's): the effects taken put on the thing chosen next, after the slide's others, in place of its own
    LaunchedEffect(item) {
        val fx = animPainter ?: return@LaunchedEffect
        val id = item ?: return@LaunchedEffect; val e = edit ?: return@LaunchedEffect; val c = chosen ?: return@LaunchedEffect; val uid = e.order[c].uid
        animPainter = null
        val list = e.anims[uid] ?: SlidesEdit.flat(view?.slides?.getOrNull(c)?.steps.orEmpty())
        change(e.copy(anims = e.anims + (uid to (list.filter { it.target != id } + fx.map { it.copy(target = id) })))); message = context.getString(R.string.slides_animation_painted); WriterLog.i("Slides: animation painted")
    }
    LaunchedEffect(item) {
        val words = chosenWords()
        if (item != null && ribbonTab in setOf("Home", "Insert")) ribbonTab = if (words != null) "Home" else "Item"
        val pl = painter ?: return@LaunchedEffect
        if (words == null) return@LaunchedEffect
        painter = null
        restyle { it.copy(font = pl.font, size = pl.size, bold = pl.bold, italic = pl.italic, underline = pl.underline, strike = pl.strike, text = pl.text, highlight = pl.highlight, align = pl.align, script = pl.script) }
        message = context.getString(R.string.slides_look_painted)
    }
    /** File › Versions: [v] in the editor, unsaved — Save puts it over the file; leaving without saving keeps the file as it is. */
    fun openVersion(v: File) = scope.launch {
        load(v).onSuccess { d -> themed.clear(); source = v; deck = d; edit = SlidesEdit.start(d); saved = null; undos.clear(); redos.clear(); chosen = null; item = null
            editing = true; message = context.getString(R.string.slides_version_open); WriterLog.i("Slides: a version opened") }
            .onFailure { WriterLog.e("Slides: a version", it); message = context.getString(R.string.slides_version_unopenable) }
    }
    /** File › Export PDF (beside the presentation, never over a file) and Print: the slides as shown, changes included. */
    fun slidesPdf(to: File, handouts: Int = 1): Int = view?.let { d -> to.outputStream().use { Slides.pdf(d, it, handouts) } } ?: error("Nothing to export.")
    fun exportPdf() {
        val f = file ?: return
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val target = generateSequence(1) { it + 1 }.map { File(f.parentFile, if (it == 1) "${f.nameWithoutExtension}.pdf" else "${f.nameWithoutExtension} ($it).pdf") }.first { !it.exists() }
                val n = slidesPdf(target); WriterLog.i("Slides: PDF of $n slides, ${target.length() / 1024} KB"); target
            } }
            message = result.fold({ context.getString(R.string.slides_saved_beside, it.name) }, { WriterLog.e("Slides: PDF", it); context.getString(R.string.slides_could_not_make_pdf) })
        }
    }
    /** File › Export pictures (PowerPoint's Export › PNG): each slide shown (hidden ones left out, as the PDF) a PNG 1920 wide, in a folder beside the deck. */
    fun exportPictures() {
        val f = file ?: return; val d = view ?: return
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val dir = generateSequence(1) { it + 1 }.map { File(f.parentFile, if (it == 1) "${f.nameWithoutExtension} slides" else "${f.nameWithoutExtension} slides ($it)") }.first { !it.exists() }.apply { mkdirs() }
                val k = 1920f / d.width
                d.slides.filter { !it.hidden }.forEachIndexed { n, sl ->
                    val bmp = android.graphics.Bitmap.createBitmap(1920, Math.round(d.height * k).coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
                    Slides.draw(android.graphics.Canvas(bmp), d, sl, k)
                    File(dir, "Slide ${n + 1}.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bmp.recycle()
                }
                WriterLog.i("Slides: ${dir.list()?.size} pictures exported"); dir
            } }
            message = result.fold({ context.getString(R.string.slides_saved_pictures, it.name) }, { WriterLog.e("Slides: pictures", it); context.getString(R.string.slides_could_not_make_pictures) })
        }
    }
    fun printIt(handouts: Int = 1) {
        val title = file?.nameWithoutExtension ?: context.getString(R.string.slides_presentation)
        scope.launch {
            val pdf = withContext(Dispatchers.IO) { runCatching { File(context.cacheDir, "print.pdf").also { slidesPdf(it, handouts) } }
                .onFailure { WriterLog.e("Slides: print", it) }.getOrNull() } ?: run { message = context.getString(R.string.slides_could_not_print); return@launch }
            context.getSystemService(android.print.PrintManager::class.java)?.print(title, PdfPrintAdapter(pdf, title), null)
            WriterLog.i("Slides: print ${pdf.length() / 1024} KB")
        }
    }
    /** What is chosen on the slide, deleted — its animation with it (the slide's timing then names only what is there). */
    fun deleteItem(ids: List<String> = listOfNotNull(item) + more) {
        var e = edit ?: return; val c = chosen ?: return; val uid = e.order[c].uid
        val sl = view?.slides?.getOrNull(c)
        // a group made here: its things, and the group gone
        val made = e.groups[uid].orEmpty()
        val members = ids.flatMap { made[it].orEmpty() }.toSet()
        if (members.isNotEmpty()) e = e.copy(groups = e.groups + (uid to made - ids.toSet()))
        for (id in ids.flatMap { made[it] ?: listOf(it) }) {
            val a = e.added[uid]?.firstOrNull { it.id == id }
            if (a == null && id !in members && sl?.items?.none { it.anim == id } != false) continue
            val list = e.anims[uid] ?: SlidesEdit.flat(sl?.steps.orEmpty())
            val anims = if (list.any { it.target == id }) e.anims + (uid to list.filter { it.target != id }) else e.anims
            e = if (a != null) e.copy(added = e.added + (uid to e.added[uid].orEmpty().filter { it.id != id }), anims = anims)
                else e.copy(removed = e.removed + (uid to id), anims = anims)
        }
        if (e == edit) return
        change(e); item = null; WriterLog.i("Slides: ${ids.size} deleted")
    }
    /** Select all: everything on the chosen slide that can be chosen (not hidden), the front-most first. */
    fun chooseAll() {
        val c = chosen ?: return
        val ids = view?.slides?.getOrNull(c)?.items?.filter { it.anim != null && !it.hidden }?.mapNotNull { it.anim }?.distinct()?.reversed().orEmpty()
        if (ids.isEmpty()) { message = context.getString(R.string.slides_nothing_to_choose); return }
        item = ids.first(); more = ids.drop(1); typing = null
    }
    /** What is chosen moved or sized each by [f], given each one's box and all of theirs (one step of Undo). */
    fun reframeAll(f: (SlideItem, SlideItem) -> List<Float>) {
        var e = edit ?: return; val c = chosen ?: return; val uid = e.order[c].uid
        val ids = listOfNotNull(item) + more
        val boxes = ids.mapNotNull { id -> current(e, c, id)?.let { id to it } }
        if (boxes.isEmpty()) return
        val x = boxes.minOf { it.second.x }; val y = boxes.minOf { it.second.y }
        val all = SlideItem.Shape(x, y, boxes.maxOf { it.second.x + it.second.w } - x, boxes.maxOf { it.second.y + it.second.h } - y, 0f, null, null)
        for ((id, b) in boxes) { val (nx, ny, nw, nh) = f(b, all); e = SlidesEdit.framed(e, uid, id, nx, ny, nw, nh) }
        change(e)
    }
    /** Distribute (Arrange): the chosen things spread evenly across ([across]) or down, the first and last staying where they are. */
    fun distribute(across: Boolean) {
        val e = edit ?: return; val c = chosen ?: return
        val boxes = (listOfNotNull(item) + more).mapNotNull { id -> current(e, c, id)?.let { id to it } }.sortedBy { if (across) it.second.x else it.second.y }
        if (boxes.size < 3) { message = context.getString(R.string.slides_choose_three); return }
        val first = boxes.first().second; val last = boxes.last().second
        val span = if (across) last.x + last.w - first.x else last.y + last.h - first.y
        val gap = (span - boxes.sumOf { (if (across) it.second.w else it.second.h).toDouble() }.toFloat()) / (boxes.size - 1)
        var at = if (across) first.x else first.y
        var next = e; val uid = e.order[c].uid
        for ((id, b) in boxes) { next = if (across) SlidesEdit.framed(next, uid, id, at, b.y, b.w, b.h) else SlidesEdit.framed(next, uid, id, b.x, at, b.w, b.h); at += (if (across) b.w else b.h) + gap }
        change(next); WriterLog.i("Slides: distributed ${if (across) "across" else "down"}")
    }
    /** Slide [c] copied after itself: its words, what was put on it, its colours, transition and animations. */
    fun duplicateSlide(c: Int) {
        val e = edit ?: return; val entry = e.order.getOrNull(c) ?: return; val uid = entry.uid
        val nu = e.next()
        fun <V> mine(m: Map<Pair<Int, String>, V>) = m.filterKeys { it.first == uid }.mapKeys { nu to it.key.second }
        change(e.copy(order = e.order.toMutableList().apply { add(c + 1, SlidesEdit.Entry(entry.src, nu, entry.layout)) }, texts = e.texts + mine(e.texts), looks = e.looks + mine(e.looks), frames = e.frames + mine(e.frames), removed = e.removed + e.removed.filter { it.first == uid }.map { nu to it.second },
            added = e.added + (e.added[uid]?.let { mapOf(nu to it) } ?: emptyMap()), transitions = e.transitions + (e.transitions[uid]?.let { mapOf(nu to it) } ?: emptyMap()),
            anims = e.anims + (e.anims[uid]?.let { mapOf(nu to it) } ?: emptyMap()), notes = e.notes + (e.notes[uid]?.let { mapOf(nu to it) } ?: emptyMap()),
            footers = e.footers + (e.footers[uid]?.let { mapOf(nu to it) } ?: emptyMap()), comments = e.comments + (e.comments[uid]?.let { mapOf(nu to it) } ?: emptyMap()),
            turns = e.turns + mine(e.turns), stack = e.stack + (e.stack[uid]?.let { mapOf(nu to it) } ?: emptyMap()), groups = e.groups + (e.groups[uid]?.let { mapOf(nu to it) } ?: emptyMap()), ungrouped = e.ungrouped + e.ungrouped.filter { it.first == uid }.map { nu to it.second }, reset = if (uid in e.reset) e.reset + nu else e.reset, tables = e.tables + mine(e.tables), relayout = e.relayout + (e.relayout[uid]?.let { mapOf(nu to it) } ?: emptyMap())))
        chosen = c + 1
    }
    /** Copy (Cut): the chosen item kept for Paste — what was put here, a picture, a shape with no words; its words on the clipboard for other apps. */
    fun copyItem(): Boolean {
        val e = edit ?: return false; val c = chosen ?: return false; val id = item ?: return false; val uid = e.order[c].uid
        val sl = view?.slides?.getOrNull(c) ?: return false
        val a = e.added[uid]?.firstOrNull { it.id == id }
        val it0 = sl.items.lastOrNull { it.anim == id }
        (it0 as? SlideItem.Shape)?.takeIf { it.text != null && !it.hint }?.let { copy(context, context.getString(R.string.slides_slide_text), SlidesEdit.lines(it).joinToString("\n")) }
        clip = when {
            a != null -> a to e.looks[uid to id]
            it0 is SlideItem.Picture -> it0.bytes()?.let { b ->
                val png = b.size > 3 && b[1] == 'P'.code.toByte()
                val f = File(File(context.cacheDir, "slide-pictures").apply { mkdirs() }, "${System.nanoTime()}.${if (png) "png" else "jpeg"}").apply { writeBytes(b) }
                SlidesEdit.Added("", it0.x, it0.y, it0.w, it0.h, image = f.path, ext = if (png) "png" else "jpeg") to null }
            it0 is SlideItem.Shape && it0.shape != null && it0.text == null -> SlidesEdit.Added("", it0.x, it0.y, it0.w, it0.h, prst = it0.shape.geometry, fill = it0.shape.fill ?: 0xFF4472C4.toInt()) to null
            // a box of the file's with words (B's Selection, 17 Oct): pasted as a text box, its words keeping their size, bold, italic, colour and links
            it0 is SlideItem.Shape && it0.text != null && !it0.hint -> SlidesEdit.Added("", it0.x, it0.y, it0.w, it0.h, prst = SlidesEdit.TEXT_BOX, runs = it0.text.paras.map { it.runs }) to null
            else -> null
        }
        message = if (clip != null) context.getString(R.string.slides_copied) else context.getString(R.string.slides_cannot_copy)
        WriterLog.i("Slides: an item copied (${if (clip != null) "to paste" else "words only"})")
        return clip != null
    }
    /** Paste: what was copied, on the chosen slide, a little lower and to the right of where it stood. */
    fun pasteItem() {
        val (a, look) = clip ?: return; val e = edit ?: return; val c = chosen ?: return; val uid = e.order[c].uid
        val id = e.newId(uid, taken(e, c))
        put(a.copy(id = id, x = a.x + 12f, y = a.y + 12f))
        look?.let { l -> edit?.let { now -> edit = now.copy(looks = now.looks + ((uid to id) to l)) } }
        WriterLog.i("Slides: an item pasted")
    }
    // what is chosen stays chosen when it is still on the slide (the tablet walk, 18 Oct: Undo after Paragraph let it go)
    fun stillThere(e: SlidesEdit.Edit) { val d = deck; val c = chosen; val id = item
        if (d == null || c == null || id == null || SlidesEdit.slide(d, e, e.order.getOrNull(c) ?: return).items.none { it.anim == id }) { item = null } }
    fun undoEdit() { undos.removeLastOrNull()?.let { edit?.let(redos::add); edit = it; chosen = chosen?.coerceAtMost((it.order.size - 1).coerceAtLeast(0)); stillThere(it) } }
    fun redoEdit() { redos.removeLastOrNull()?.let { edit?.let(undos::add); edit = it; chosen = chosen?.coerceAtMost((it.order.size - 1).coerceAtLeast(0)); stillThere(it) } }
    /** The tools of the ribbon's tab [tab], for slide [c] of [e]. */
    /** Aa on [box]: its lines in [case] (Words' change case), as one step of Undo; each letter keeps its look (as typed words do). */
    fun recase(box: SlideItem.Shape, case: WriterBridge.Case) {
        val e = edit ?: return; val c = chosen ?: return; val id = box.id ?: return
        change(e.copy(texts = e.texts + ((e.order[c].uid to id) to SlidesEdit.lines(box).map { WriterBridge.inCase(it, case, true) }))); WriterLog.i("Slides: case changed (${case.name})")
    }
    /** The look of [box]'s words (its first letters' and paragraph's), for the Format painter and Copy formatting. */
    fun lookOf(box: SlideItem.Shape): SlidesEdit.Look? {
        val t = box.text ?: return null
        val r = t.paras.flatMap { it.runs }.firstOrNull { it.text.isNotBlank() } ?: t.paras.firstOrNull()?.runs?.firstOrNull()
        val p = t.paras.firstOrNull { pp -> pp.runs.any { it.text.isNotBlank() } } ?: t.paras.firstOrNull()
        return SlidesEdit.Look(font = DocStyles.Plain.fontName(r?.font), size = r?.sizePt, bold = r?.bold, italic = r?.italic, underline = r?.underline,
            strike = r?.strike, text = r?.color?.or(0xFF000000.toInt()), highlight = r?.highlight?.or(0xFF000000.toInt()) ?: SlidesEdit.NONE, align = p?.para?.align, script = r?.script)
    }
    /** Home's text tools for [box] (17 Oct), Words' own: font strip, size, B I U S, colours, painter, clear; lists, alignment, spacing; anchor, autofit. */
    /** Several things chosen (Select all, Choose several): Group, Align to each other, Distribute, Delete — PowerPoint's Arrange for a selection. */
    /** A thing on every slide (the slide master's) chosen on a slide: back on this slide alone (one put on here), or off every slide. */
    @Composable fun MasterTools(w: Color) {
        val ee = edit ?: return; val c = chosen ?: return; val u = ee.order[c].uid
        val it0 = view?.slides?.getOrNull(c)?.items?.lastOrNull { it.anim == item } ?: return
        val key = it0.master ?: return
        Text(stringResource(R.string.slides_on_every_slide), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
        val mine = key.takeIf { it.startsWith("added ") }?.removePrefix("added ")?.let { id -> ee.added[SlidesEdit.MASTER].orEmpty().firstOrNull { it.id == id } }
        if (mine != null) RibbonButton(stringResource(R.string.slides_only_this_slide), w, R.drawable.ic_crop_landscape) {
            change(ee.copy(added = ee.added + (SlidesEdit.MASTER to ee.added[SlidesEdit.MASTER].orEmpty().filter { it.id != mine.id }) + (u to ee.added[u].orEmpty() + mine),
                looks = ee.looks.mapKeys { (k, _) -> if (k == SlidesEdit.MASTER to mine.id) u to mine.id else k },
                texts = ee.texts.mapKeys { (k, _) -> if (k == SlidesEdit.MASTER to mine.id) u to mine.id else k }))
            item = mine.id; WriterLog.i("Slides: a thing back on one slide") }
        RibbonButton(stringResource(R.string.slides_delete_every_slide), w, R.drawable.ic_delete) {
            // the master's own: the same thing on each master (an .odp's master pages each have their copy) — its kind, place, size and words
            fun words(i: SlideItem) = (i as? SlideItem.Shape)?.let { SlidesEdit.lines(it) }
            val keys = view?.slides.orEmpty().flatMap { it.items }.filter { o -> o.master?.startsWith("added ") == false && o::class == it0::class &&
                kotlin.math.abs(o.x - it0.x) < 0.5f && kotlin.math.abs(o.y - it0.y) < 0.5f && kotlin.math.abs(o.w - it0.w) < 0.5f && kotlin.math.abs(o.h - it0.h) < 0.5f && words(o) == words(it0) }.mapNotNull { it.master }.toSet() + key
            change(if (mine != null) ee.copy(added = ee.added + (SlidesEdit.MASTER to ee.added[SlidesEdit.MASTER].orEmpty().filter { it.id != mine.id })) else ee.copy(removed = ee.removed + keys.map { SlidesEdit.MASTER to it }))
            item = null; message = context.getString(R.string.slides_taken_off_every); WriterLog.i("Slides: a thing taken off every slide") }
    }
    @Composable fun SeveralTools(w: Color) {
        val e = edit ?: return; val c = chosen ?: return; val d = base ?: return
        Text(stringResource(R.string.slides_n_chosen, more.size + 1), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
        RibbonButton(stringResource(R.string.slides_group), w, R.drawable.ic_group_objects) {
            val next = SlidesEdit.grouped(d, e, e.order[c], listOfNotNull(item) + more)
            if (next == e) message = context.getString(R.string.slides_cannot_group) else { change(next); item = next.groups[e.order[c].uid]?.keys?.lastOrNull(); more = emptyList(); several = false; WriterLog.i("Slides: grouped") }
        }
        RibbonMenu(stringResource(R.string.slides_align), w, R.drawable.ic_align_horizontal_center, preset = false, choices =
            listOf(stringResource(R.string.slides_align_left) to { i: SlideItem, a: SlideItem -> listOf(a.x, i.y, i.w, i.h) }, stringResource(R.string.slides_align_centre) to { i: SlideItem, a: SlideItem -> listOf(a.x + (a.w - i.w) / 2, i.y, i.w, i.h) },
                stringResource(R.string.slides_align_right) to { i: SlideItem, a: SlideItem -> listOf(a.x + a.w - i.w, i.y, i.w, i.h) }, stringResource(R.string.slides_align_top) to { i: SlideItem, a: SlideItem -> listOf(i.x, a.y, i.w, i.h) },
                stringResource(R.string.slides_align_middle) to { i: SlideItem, a: SlideItem -> listOf(i.x, a.y + (a.h - i.h) / 2, i.w, i.h) }, stringResource(R.string.slides_align_bottom) to { i: SlideItem, a: SlideItem -> listOf(i.x, a.y + a.h - i.h, i.w, i.h) })
                .map { (label, to) -> MenuChoice(label) { reframeAll(to); WriterLog.i("Slides: several, $label") } })
        RibbonButton(stringResource(R.string.slides_distribute_across), w, R.drawable.ic_horizontal_distribute, enabled = more.size >= 2) { distribute(true) }
        RibbonButton(stringResource(R.string.slides_distribute_down), w, R.drawable.ic_vertical_distribute, enabled = more.size >= 2) { distribute(false) }
        RibbonDivider(w)
        RibbonButton(stringResource(R.string.common_delete), w, R.drawable.ic_delete) { deleteItem() }
    }
    @Composable fun HomeText(box: SlideItem.Shape, w: Color) {
        val t = box.text ?: return
        val r = t.paras.flatMap { it.runs }.firstOrNull { it.text.isNotBlank() } ?: t.paras.firstOrNull()?.runs?.firstOrNull()
        val p = t.paras.firstOrNull { pp -> pp.runs.any { it.text.isNotBlank() } } ?: t.paras.firstOrNull()
        FontButton(DocStyles.Plain.fontName(r?.font), w) { strip = DesignGallery.Font }
        val pt = Math.round(r?.sizePt ?: 18f)
        SizeStepper("$pt", w, pt > 1, pt < 400, { restyle { it.copy(size = (pt - 1).toFloat()) } }, { restyle { it.copy(size = (pt + 1).toFloat()) } })
        RibbonDivider(w)
        Tool(R.drawable.ic_format_bold, stringResource(R.string.slides_bold), w, on = r?.bold == true) { restyle { it.copy(bold = r?.bold != true) } }
        Tool(R.drawable.ic_format_italic, stringResource(R.string.slides_italic), w, on = r?.italic == true) { restyle { it.copy(italic = r?.italic != true) } }
        Tool(R.drawable.ic_format_underlined, stringResource(R.string.slides_underline), w, on = r?.underline == true) { restyle { it.copy(underline = r?.underline != true) } }
        Tool(R.drawable.ic_format_strikethrough, stringResource(R.string.slides_strikethrough), w, on = r?.strike == true) { restyle { it.copy(strike = r?.strike != true) } }
        // Words' superscript and subscript, and Aa (change case: the box's words, each letter keeping its look)
        RibbonButton("X²", w, on = r?.script == 1) { restyle { it.copy(script = if (r?.script == 1) 0 else 1) } }
        RibbonButton("X₂", w, on = r?.script == -1) { restyle { it.copy(script = if (r?.script == -1) 0 else -1) } }
        RibbonMenu("Aa", w, preset = false, title = stringResource(R.string.slides_change_case), choices = WriterBridge.Case.entries.map { cs -> MenuChoice(stringResource(cs.text)) { recase(box, cs) } })
        Swatches(w, R.drawable.ic_format_color_text, stringResource(R.string.slides_text_colour), TEXT_COLOURS) { col -> restyle { it.copy(text = col?.toArgb() ?: 0xFF000000.toInt()) } }
        Swatches(w, R.drawable.ic_highlighter, stringResource(R.string.slides_highlight), HIGHLIGHT_COLOURS) { col -> restyle { it.copy(highlight = col?.toArgb() ?: SlidesEdit.NONE) } }
        // PowerPoint's Character Spacing (Words' choices): the box's letters closer or wider
        run { val now = r?.font?.let { LetterLooks.spacingOf(it) } ?: 0f
            RibbonMenu(stringResource(R.string.slides_spacing), w, R.drawable.ic_format_letter_spacing, on = now != 0f, title = stringResource(R.string.slides_character_spacing), choices = CHARACTER_SPACINGS.mapIndexed { i, (_, pt) ->
                MenuChoice(stringResource(CHARACTER_SPACING_NAMES[i]), checked = now == pt) { restyle { it.copy(letterSpacing = pt) }; WriterLog.i("Slides: character spacing $pt") } }) }
        // PowerPoint's Text Effects (WordArt's, on any box's words): an outline round the letters, a shadow, a glow (an .odp has none)
        run { val fx = TextEffects.of(r?.font); val tint = r?.color ?: 0x4472C4
            RibbonMenu(stringResource(R.string.slides_text_effects), w, R.drawable.ic_format_color_text, on = fx.any, preset = false, choices = listOfNotNull(Triple('o', stringResource(R.string.slides_outline), fx.outline != null), Triple('s', stringResource(R.string.slides_shadow), fx.shadow), Triple('g', stringResource(R.string.slides_glow), fx.glow != null).takeIf { !odp }).map { (kind, label, on) ->
                MenuChoice(label, checked = on) {
                    restyle { it.copy(effects = when (kind) { 'o' -> fx.copy(outline = if (on) null else artOutline(tint)); 's' -> fx.copy(shadow = !on); else -> fx.copy(glow = if (on) null else artGlow(tint)) }) }
                    WriterLog.i("Slides: text effect $kind ${if (on) "off" else "on"}") } }) }
        // Format painter: this box's look taken, put on the next box chosen
        Tool(R.drawable.ic_format_paint, stringResource(R.string.slides_format_painter), w, on = painter != null) {
            painter = if (painter != null) null else lookOf(box)
            if (painter != null) message = context.getString(R.string.slides_choose_box_to_paint)
        }
        // Clear formatting: the letters' own looks gone (the box's fill, border, anchor and autofit stay)
        Tool(R.drawable.ic_format_clear, stringResource(R.string.slides_clear_formatting), w) { restyle { SlidesEdit.Look(fill = it.fill, line = it.line, anchor = it.anchor, autofit = it.autofit, cleared = true) } }
        RibbonDivider(w)
        val numbered = p?.bullet?.text?.firstOrNull()?.isDigit() == true
        Tool(R.drawable.ic_format_list_bulleted, stringResource(R.string.slides_bullets), w, on = p?.bullet != null && !numbered) { restyle { it.copy(list = if (p?.bullet != null && !numbered) 'n' else 'b', scheme = null) } }
        Tool(R.drawable.ic_format_list_numbered, stringResource(R.string.slides_numbering), w, on = numbered) { restyle { it.copy(list = if (numbered) 'n' else '1', scheme = null) } }
        // Words' Multilevel list strip: each paragraph numbered in its level's form (1. a. i. …)
        RibbonButton(stringResource(R.string.slides_multilevel), w, R.drawable.ic_format_list_numbered) { strip = DesignGallery.Lists }
        RibbonDivider(w)
        listOf(DocAlign.Start to R.drawable.ic_align_left, DocAlign.Center to R.drawable.ic_align_center, DocAlign.End to R.drawable.ic_align_right, DocAlign.Justify to R.drawable.ic_align_justify).forEach { (a, icon) ->
            Tool(icon, stringResource(when (a) { DocAlign.Start -> R.string.slides_align_left; DocAlign.Center -> R.string.slides_align_center; DocAlign.End -> R.string.slides_align_right; else -> R.string.slides_align_justify }), w, on = (p?.para?.align ?: DocAlign.Start) == a) { restyle { it.copy(align = a) } }
        }
        RibbonDivider(w)
        // line spacing as Words' (a share of the single line: PowerPoint's 1.2 × the letters' size)
        Icon(painterResource(R.drawable.ic_format_line_spacing), stringResource(R.string.slides_line_spacing), tint = w, modifier = Modifier.padding(horizontal = 4.dp).size(20.dp))
        LINE_SPACINGS.forEach { m -> RibbonButton(plain(m), w, on = p != null && p.look.lineExact == null && kotlin.math.abs(p.look.lineMultiple - m) < 0.01f) { restyle { it.copy(spacing = m) } } }
        // Words' paragraph page (its ⋯): before and after, line spacing exactly, indents — on all the box's paragraphs
        RibbonButton(stringResource(R.string.slides_paragraph), w, R.drawable.ic_more_horiz) { paragraphing = p?.look ?: ParaLook() }
        RibbonDivider(w)
        // where the words stand in the box, and how the box takes too many (PowerPoint's Align Text and Autofit)
        listOf('t' to (R.drawable.ic_vertical_align_top to R.string.slides_words_top), 'c' to (R.drawable.ic_vertical_align_center to R.string.slides_words_middle), 'b' to (R.drawable.ic_vertical_align_bottom to R.string.slides_words_bottom)).forEach { (a, d) ->
            Tool(d.first, stringResource(d.second), w, on = t.anchor == a) { restyle { it.copy(anchor = a) } }
        }
        val grows = edit?.looks?.get((edit?.order?.getOrNull(chosen ?: -1)?.uid ?: -1) to (item ?: ""))?.autofit == 'g'
        RibbonButton(stringResource(R.string.slides_dont_fit), w, R.drawable.ic_close, on = !t.shrink && !grows) { restyle { it.copy(autofit = 'n') } }
        RibbonButton(stringResource(R.string.slides_shrink_text), w, R.drawable.ic_fit_page, on = t.shrink) { restyle { it.copy(autofit = 's') } }
        RibbonButton(stringResource(R.string.slides_box_fits_words), w, R.drawable.ic_unfold_more, on = grows) { restyle { it.copy(autofit = 'g') } }
    }
    @Composable fun SlideRibbon(tab: String, c: Int, e: SlidesEdit.Edit, w: Color) {
        val entry = e.order[c]; val uid = entry.uid
        val sl = view?.slides?.getOrNull(c)
        when (tab) {
            // Words' Selection tab (B, 17 Oct): what is chosen cut, copied, pasted; words pasted alone; its words' look copied and put on another
            "Selection" -> {
                RibbonButton(stringResource(R.string.common_cut), w, R.drawable.ic_content_cut) { if (copyItem()) deleteItem() }
                RibbonButton(stringResource(R.string.common_copy), w, R.drawable.ic_copy) { copyItem() }
                RibbonButton(stringResource(R.string.common_paste), w, R.drawable.ic_content_paste, enabled = clip != null) { pasteItem() }
                RibbonDivider(w)
                RibbonButton(stringResource(R.string.common_select_all), w, R.drawable.ic_select_all) { chooseAll() }
                RibbonButton(stringResource(R.string.slides_choose_several), w, R.drawable.ic_checklist, on = several) { several = !several; if (several) message = context.getString(R.string.slides_tap_more) }
                RibbonDivider(w)
                RibbonButton(stringResource(R.string.slides_paste_text_only), w, R.drawable.ic_content_paste) {
                    context.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.takeIf { it.isNotEmpty() }
                        ?.let { putWords(it); WriterLog.i("Slides: text pasted alone") } ?: run { message = context.getString(R.string.slides_nothing_to_paste) }
                }
                RibbonDivider(w)
                val box = chosenWords()
                RibbonButton(stringResource(R.string.slides_copy_formatting), w, R.drawable.ic_format_paint, enabled = box != null) {
                    copiedLook = box?.let(::lookOf); message = context.getString(R.string.slides_formatting_copied) }
                RibbonButton(stringResource(R.string.slides_paste_formatting), w, R.drawable.ic_format_paint, enabled = copiedLook != null && box != null) { copiedLook?.let { pl ->
                    restyle { it.copy(font = pl.font, size = pl.size, bold = pl.bold, italic = pl.italic, underline = pl.underline, strike = pl.strike, text = pl.text, highlight = pl.highlight, align = pl.align, script = pl.script) }
                    WriterLog.i("Slides: formatting pasted") } }
            }
            "Item" -> if (more.isNotEmpty()) SeveralTools(w) else if (sl?.items?.lastOrNull { it.anim == item }?.master != null) MasterTools(w) else {
                val a = e.added[uid]?.firstOrNull { it.id == item }
                val it0 = sl?.items?.lastOrNull { it.anim == item }
                // a group (the file's or made here): taken apart, each thing where it is
                if (it0?.grouped == true) { RibbonButton(stringResource(R.string.slides_ungroup), w, R.drawable.ic_group_objects) { val d = base ?: return@RibbonButton
                    // the pieces stay chosen, as PowerPoint leaves them (the tablet walk, 18 Oct)
                    val gid = item!!; val pieces = e.groups[entry.uid]?.get(gid) ?: view?.slides?.getOrNull(c)?.items?.filter { it.anim == gid }?.mapNotNull { it.own }?.distinct().orEmpty()
                    change(SlidesEdit.ungrouped(d, e, entry, gid)); item = pieces.firstOrNull(); more = pieces.drop(1); WriterLog.i("Slides: ungrouped") }; RibbonDivider(w) }
                if (typable(it0, a)) RibbonButton(stringResource(R.string.slides_edit_text), w, R.drawable.ic_edit) { typeInto(it0 as SlideItem.Shape) }
                // a shape of the deck's with no words: Add text (PowerPoint's), typed as a box's words are
                else if (a == null && it0 is SlideItem.Shape && it0.text == null && it0.id != null && !it0.grouped) RibbonButton(stringResource(R.string.slides_add_text), w, R.drawable.ic_text_fields) { typeInto(it0) }
                // an equation (put on here, or the deck's own): Words' equation editor again
                ((it0 as? SlideItem.Picture)?.math ?: a?.math)?.let { m -> RibbonButton(stringResource(R.string.slides_edit_equation), w, R.drawable.ic_functions) { equating = item to m }; RibbonDivider(w) }
                // a chart put on here: its numbers in Words' chart editor again
                a?.chart?.let { ch -> RibbonButton(stringResource(R.string.slides_edit_data), w, R.drawable.ic_bar_chart) { charting = a.id to ch }; RibbonDivider(w) }
                // the deck's own chart: its numbers in Words' chart editor, then put on in its place as Slides' own (as an equation is)
                if (a == null && it0 is SlideItem.Chart) { RibbonButton(stringResource(R.string.slides_edit_data), w, R.drawable.ic_bar_chart) { charting = "deck:$item" to it0.chart }; RibbonDivider(w) }
                // a table put on here: Words' Table tab — rows and columns round the cell last tapped, its numbers a chart (Words' fromTable)
                if (a?.prst == "table") {
                    val r0 = (tableCell / a.cols.coerceAtLeast(1)).coerceIn(0, a.rows - 1); val c0 = (tableCell % a.cols.coerceAtLeast(1)).coerceIn(0, a.cols - 1)
                    fun at(t: SlidesEdit.Added, r: Int, c: Int) = t.cells.getOrNull(r * t.cols + c).orEmpty()
                    RibbonButton(stringResource(R.string.slides_make_chart), w, R.drawable.ic_bar_chart) {
                        OfficeCharts.fromTable(DocTable("", "", (0 until a.rows).map { r -> DocRow("", (0 until a.cols).map { c -> DocCell("", listOf(DocPara(listOf(DocRun(at(a, r, c)))))) }) }))
                            ?.let { charting = null to it } ?: run { message = context.getString(R.string.slides_table_no_numbers) } }
                    RibbonDivider(w)
                    RibbonButton(stringResource(R.string.slides_row_above), w, R.drawable.ic_add_row_above) { alter { t -> SlidesEdit.rowAdded(t, r0) }; tableCell += a.cols }
                    RibbonButton(stringResource(R.string.slides_row_below), w, R.drawable.ic_add_row_below) { alter { t -> SlidesEdit.rowAdded(t, r0 + 1) } }
                    RibbonButton(stringResource(R.string.slides_column_left), w, R.drawable.ic_add_column_left) { alter { t -> SlidesEdit.columnAdded(t, c0).let { n -> if (n.x + n.w > (deck?.width ?: Float.MAX_VALUE)) n.copy(w = t.w) else n } }; tableCell += r0 + 1 }
                    RibbonButton(stringResource(R.string.slides_column_right), w, R.drawable.ic_add_column_right) { alter { t -> SlidesEdit.columnAdded(t, c0 + 1).let { n -> if (n.x + n.w > (deck?.width ?: Float.MAX_VALUE)) n.copy(w = t.w) else n } }; tableCell += r0 }
                    RibbonButton(stringResource(R.string.slides_delete_row), w, R.drawable.ic_delete_row, enabled = a.rows > 1) { alter { t -> SlidesEdit.rowTaken(t, r0) }; tableCell = 0 }
                    RibbonButton(stringResource(R.string.slides_delete_column), w, R.drawable.ic_delete_column, enabled = a.cols > 1) { alter { t -> SlidesEdit.columnTaken(t, c0) }; tableCell = 0 }
                    RibbonButton(stringResource(R.string.slides_delete_table), w, R.drawable.ic_delete) { deleteItem() }
                    RibbonDivider(w)
                }
                // a deck's own table: the same tab, its rows and columns kept as the file's (SlidesEdit.tabled)
                if (a == null && it0 is SlideItem.Table) {
                    val rows = it0.rows.size; val cols = it0.cols.size.coerceAtLeast(1)
                    val r0 = (tableCell / cols).coerceIn(0, rows - 1); val c0 = (tableCell % cols).coerceIn(0, cols - 1)
                    RibbonButton(stringResource(R.string.slides_make_chart), w, R.drawable.ic_bar_chart) {
                        OfficeCharts.fromTable(DocTable("", "", it0.cells.map { row -> DocRow("", row.map { (_, t) -> DocCell("", listOf(DocPara(listOf(DocRun(t?.paras?.joinToString(" ") { p -> p.runs.joinToString("") { it.text } }.orEmpty()))))) }) }))
                            ?.let { charting = null to it } ?: run { message = context.getString(R.string.slides_table_no_numbers) } }
                    RibbonDivider(w)
                    RibbonButton(stringResource(R.string.slides_row_above), w, R.drawable.ic_add_row_above) { tableOp('r', r0); tableCell += cols }
                    RibbonButton(stringResource(R.string.slides_row_below), w, R.drawable.ic_add_row_below) { tableOp('r', r0 + 1) }
                    RibbonButton(stringResource(R.string.slides_column_left), w, R.drawable.ic_add_column_left) { tableOp('c', c0); tableCell += r0 + 1 }
                    RibbonButton(stringResource(R.string.slides_column_right), w, R.drawable.ic_add_column_right) { tableOp('c', c0 + 1); tableCell += r0 }
                    RibbonButton(stringResource(R.string.slides_delete_row), w, R.drawable.ic_delete_row, enabled = rows > 1) { tableOp('R', r0); tableCell = 0 }
                    RibbonButton(stringResource(R.string.slides_delete_column), w, R.drawable.ic_delete_column, enabled = cols > 1) { tableOp('C', c0); tableCell = 0 }
                    RibbonButton(stringResource(R.string.slides_delete_table), w, R.drawable.ic_delete) { deleteItem() }
                    RibbonDivider(w)
                }
                if (it0 is SlideItem.Shape || a?.prst == "table") {
                    if (typable(it0, a)) RibbonDivider(w)
                    Swatches(w, R.drawable.ic_format_color_fill, stringResource(R.string.slides_fill), SHAPE_COLOURS.map { Color(it) }) { col -> restyle { it.copy(fill = col?.toArgb() ?: SlidesEdit.NONE) } }
                    if (it0 is SlideItem.Shape) Swatches(w, R.drawable.ic_border_outer, stringResource(R.string.slides_border), SHAPE_COLOURS.map { Color(it) }) { col -> restyle { it.copy(line = col?.toArgb() ?: SlidesEdit.NONE) } }
                    if ((it0 as? SlideItem.Shape)?.text != null || a?.prst == "table") Swatches(w, R.drawable.ic_format_color_text, stringResource(R.string.slides_text_colour), TEXT_COLOURS) { col -> restyle { it.copy(text = col?.toArgb() ?: 0xFF000000.toInt()) } }
                }
                // moved, made bigger or smaller (the arrows a step a press, for where a finger is not precise); what was put on it here, deleted
                if (it0 != null) {
                    if (it0 is SlideItem.Shape || a?.prst == "table") RibbonDivider(w)
                    val step = (deck?.width ?: 720f) / 40
                    RibbonButton(stringResource(R.string.slides_left), w, R.drawable.ic_chevron_left) { reframe { listOf(it.x - step, it.y, it.w, it.h) } }
                    RibbonButton(stringResource(R.string.slides_right), w, R.drawable.ic_chevron_right) { reframe { listOf(it.x + step, it.y, it.w, it.h) } }
                    RibbonButton(stringResource(R.string.slides_up), w, R.drawable.ic_keyboard_arrow_up) { reframe { listOf(it.x, it.y - step, it.w, it.h) } }
                    RibbonButton(stringResource(R.string.slides_down), w, R.drawable.ic_keyboard_arrow_down) { reframe { listOf(it.x, it.y + step, it.w, it.h) } }
                    RibbonDivider(w)
                    RibbonButton(stringResource(R.string.slides_bigger), w, R.drawable.ic_add) { reframe { val k = 1.15f; listOf(it.x - it.w * (k - 1) / 2, it.y - it.h * (k - 1) / 2, it.w * k, it.h * k) } }
                    RibbonButton(stringResource(R.string.slides_smaller), w, R.drawable.ic_remove) { reframe { val k = 1 / 1.15f; listOf(it.x + it.w * (1 - k) / 2, it.y + it.h * (1 - k) / 2, it.w * k, it.h * k) } }
                }
                // Arrange (PowerPoint's): over or under the others, lined up on the slide, turned and mirrored, its exact size and place
                if (it0 != null) {
                    RibbonDivider(w)
                    RibbonButton(stringResource(R.string.slides_bring_forward), w, R.drawable.ic_flip_to_front) { restack('+') }
                    RibbonButton(stringResource(R.string.slides_bring_to_front), w, R.drawable.ic_flip_to_front) { restack('f') }
                    RibbonButton(stringResource(R.string.slides_send_backward), w, R.drawable.ic_flip_to_back) { restack('-') }
                    RibbonButton(stringResource(R.string.slides_send_to_back), w, R.drawable.ic_flip_to_back) { restack('b') }
                    // PowerPoint's slide master, for what is most often put there: this thing on every slide (a logo, a line of words)
                    if (a != null) RibbonButton(stringResource(R.string.slides_on_every_slide), w, R.drawable.ic_copy) {
                        val ee = edit ?: return@RibbonButton; val u = ee.order[c].uid
                        change(ee.copy(added = ee.added + (u to ee.added[u].orEmpty().filter { it.id != a.id }) + (SlidesEdit.MASTER to ee.added[SlidesEdit.MASTER].orEmpty() + a),
                            looks = ee.looks.mapKeys { (k, _) -> if (k == u to a.id) SlidesEdit.MASTER to a.id else k },
                            texts = ee.texts.mapKeys { (k, _) -> if (k == u to a.id) SlidesEdit.MASTER to a.id else k }))
                        message = context.getString(R.string.slides_on_every_slide_now); WriterLog.i("Slides: a thing put on every slide") }
                    RibbonButton(stringResource(R.string.slides_selection_pane), w, R.drawable.ic_layers) { selectionPane = true }
                    run { val dw = deck?.width ?: 720f; val dh = deck?.height ?: 540f
                        RibbonMenu(stringResource(R.string.slides_align), w, R.drawable.ic_align_horizontal_center, preset = false, choices =
                            listOf(stringResource(R.string.slides_align_left) to { i: SlideItem -> listOf(0f, i.y, i.w, i.h) }, stringResource(R.string.slides_align_centre) to { i: SlideItem -> listOf((dw - i.w) / 2, i.y, i.w, i.h) },
                                stringResource(R.string.slides_align_right) to { i: SlideItem -> listOf(dw - i.w, i.y, i.w, i.h) }, stringResource(R.string.slides_align_top) to { i: SlideItem -> listOf(i.x, 0f, i.w, i.h) },
                                stringResource(R.string.slides_align_middle) to { i: SlideItem -> listOf(i.x, (dh - i.h) / 2, i.w, i.h) }, stringResource(R.string.slides_align_bottom) to { i: SlideItem -> listOf(i.x, dh - i.h, i.w, i.h) })
                                .map { (label, to) -> MenuChoice(label) { reframe(to); WriterLog.i("Slides: $label") } }) }
                    if (it0 is SlideItem.Shape || it0 is SlideItem.Picture) {
                        RibbonButton(stringResource(R.string.slides_rotate), w, R.drawable.ic_rotate_right) { rotating = true }
                        RibbonButton(stringResource(R.string.slides_turn_left), w, R.drawable.ic_rotate_left) { turn { it.copy(deg = (it.deg + 270f) % 360f) } }
                        RibbonButton(stringResource(R.string.slides_turn_right), w, R.drawable.ic_rotate_right) { turn { it.copy(deg = (it.deg + 90f) % 360f) } }
                        RibbonButton(stringResource(R.string.slides_flip_across), w, R.drawable.ic_flip) { turn { it.copy(flipH = !it.flipH) } }
                        RibbonButton(stringResource(R.string.slides_flip_up_down), w, R.drawable.ic_flip) { turn { it.copy(flipV = !it.flipV) } }
                    }
                    RibbonButton(stringResource(R.string.slides_size_position), w, R.drawable.ic_aspect_ratio) { sizing = true }
                    // a box with words: PowerPoint's Format Shape › Text Box (inner margins, wrap, columns)
                    (it0 as? SlideItem.Shape)?.text?.takeIf { !it0.hint && it0.id != null }?.let { t -> RibbonButton(stringResource(R.string.slides_layout), w, R.drawable.ic_margin) { boxLayout = t } }
                    // PowerPoint's Text Direction: the box's words across, or turned 90° or 270°
                    (it0 as? SlideItem.Shape)?.text?.takeIf { !it0.hint && it0.id != null && it.warp == null }?.let { t -> RibbonMenu(stringResource(R.string.slides_text_direction), w, R.drawable.ic_rotate_right, on = t.vert != 0, choices = listOf(stringResource(R.string.slides_horizontal) to 0, stringResource(R.string.slides_rotate_90) to 90, stringResource(R.string.slides_rotate_270) to 270).map { (label, v) ->
                        MenuChoice(label, checked = t.vert == v) { restyle { it.copy(vert = v) }; WriterLog.i("Slides: text direction $v") } })  }
                    // a video: played in its frame on the slide
                    it0.media?.let { m -> RibbonButton(stringResource(R.string.slides_play), w, R.drawable.ic_play) { scope.launch { filmOf(m)?.let { f -> chosen?.let { c -> filming = c to OfficeVideo.Spot(f, it0.x, it0.y, it0.w, it0.h, it0.rotation, it0.trim) } } ?: run { message = context.getString(R.string.slides_film_not_found) } } } }
                    // PowerPoint's Trim Video: a film in the deck from a later start to an earlier end (kept in a .pptx; LibreOffice has no such thing)
                    it0.media?.takeIf { m -> !odp && !m.startsWith("http") && !OfficeVideo.audio(m) }?.let { m -> RibbonButton(stringResource(R.string.slides_trim), w, R.drawable.ic_content_cut, on = it0.trim != null) { scope.launch {
                        val len = filmOf(m)?.let { f -> withContext(Dispatchers.IO) { OfficeVideo.length(f) } }
                        if (len == null || len <= 0) message = context.getString(R.string.slides_film_unreadable) else trimming = (it0.trim ?: listOf(0, 0)) to len } } }
                    // a picture's crop (Words' crop editor, the whole picture shown) and Reset (the whole picture again)
                    // Words' Save picture as: into the phone's Pictures/Tetra
                    if (it0 is SlideItem.Picture && it0.media == null) RibbonButton(stringResource(R.string.slides_save_picture_as), w, R.drawable.ic_save) { scope.launch {
                        message = withContext(Dispatchers.IO) { it0.bytes() }?.let { savePictureToPhone(context, it, null, file?.name ?: "Presentation") } ?: context.getString(R.string.slides_could_not_read_picture) } }
                    if (it0 is SlideItem.Picture && it0.math == null) {
                        // PowerPoint's Picture Styles (the tablet app's frame, shadow, rounded corners) and Transparency
                        RibbonMenu(stringResource(R.string.slides_picture_styles), w, R.drawable.ic_style, choices = listOf(stringResource(R.string.slides_plain) to PictureLook(), stringResource(R.string.slides_white_frame) to PictureLook(line = 0xFFFFFF, lineW = 6f, shadow = true), stringResource(R.string.slides_black_frame) to PictureLook(line = 0x000000, lineW = 3f),
                            stringResource(R.string.slides_shadow) to PictureLook(shadow = true), stringResource(R.string.slides_rounded) to PictureLook(round = Slides.ROUND), stringResource(R.string.slides_rounded_shadow) to PictureLook(round = Slides.ROUND, shadow = true))
                            .map { (label, st) -> MenuChoice(label) { restyle { it.copy(picStyle = st) }; WriterLog.i("Slides: picture style $label") } })
                        RibbonMenu(stringResource(R.string.slides_transparency), w, R.drawable.ic_opacity, choices = listOf(0, 15, 30, 50, 65, 80).map { pc ->
                            MenuChoice(if (pc == 0) stringResource(R.string.common_none) else "$pc %", checked = Math.round((1 - (it0.pic?.alpha ?: 1f)) * 100) == pc) { restyle { it.copy(alpha = 1 - pc / 100f) }; WriterLog.i("Slides: transparency $pc%") } })
                        RibbonButton(stringResource(R.string.slides_crop), w, R.drawable.ic_crop) { scope.launch {
                            cropping = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { it0.bytes()?.let { b ->
                                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }; android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, o)
                                var k = 1; while (maxOf(o.outWidth, o.outHeight) / (k * 2) >= 2400) k *= 2
                                android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = k }) } }
                            if (cropping == null) message = context.getString(R.string.slides_cannot_crop)
                        } }
                        if (it0.crop != null) RibbonButton(stringResource(R.string.slides_reset_picture), w, R.drawable.ic_undo) { recrop(listOf(0f, 0f, 0f, 0f)) }
                        RibbonButton(stringResource(R.string.slides_replace), w, R.drawable.ic_gallery) { pickReplace.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                        // a drawing (Words' or Draw on slide's: its strokes ride in its picture) drawn on again
                        val strokes = remember(it0) { it0.bytes()?.let { InkPicture.strokesOf(it) } }
                        if (strokes != null) RibbonButton(stringResource(R.string.slides_edit_drawing), w, R.drawable.ic_draw) { redrawing = it0 to strokes.first }
                    }
                    // Words' Shape options for a shape; for the rest its alt text (what it shows, for screen readers)
                    if ((it0 as? SlideItem.Shape)?.shape != null && !it0.grouped) RibbonButton(stringResource(R.string.slides_shape_options), w, R.drawable.ic_tune) { shaping = true }
                    else RibbonButton(stringResource(R.string.slides_alt_text), w, R.drawable.ic_label) { alting = true }
                }
                if (it0 != null) RibbonDivider(w)
                if (a != null || it0 != null) RibbonButton(stringResource(R.string.common_delete), w, R.drawable.ic_delete) { deleteItem() }
            }
            "Home" -> {
                // a box with words chosen: Words' text tools, on all its words (a letter's own needs the rich typing editor, later)
                chosenWords()?.let { box -> HomeText(box, w); RibbonDivider(w) }
                Text(stringResource(R.string.slides_slide_n, c + 1), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
                if (deck?.layouts?.isNotEmpty() == true) RibbonButton(stringResource(R.string.slides_new_slide), w, R.drawable.ic_add) { relaying = false; layouts = true }
                // PowerPoint's Reuse slides: slides of another deck (.pptx or .odp) put in after this one, in this deck's look
                if (deck?.layouts?.isNotEmpty() == true) RibbonButton(stringResource(R.string.slides_reuse_slides), w, R.drawable.ic_copy) {
                    pickDeck.launch(arrayOf("application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/vnd.oasis.opendocument.presentation")) }
                // Layout (PowerPoint's): the slide on another of the deck's layouts, its words and what is on it kept
                if (deck?.relaid != null && deck?.layouts?.isNotEmpty() == true && entry.layout < 0) RibbonButton(stringResource(R.string.slides_layout), w, R.drawable.ic_view_grid) { relaying = true; layouts = true }
                // Reset (PowerPoint's Home › Slides): its placeholders back to their layout's place, size and look; Tetra's own changes to them gone too
                if (deck?.relaid != null && entry.layout < 0) RibbonButton(stringResource(R.string.slides_reset), w, R.drawable.ic_undo) {
                    val ph = deck?.slides?.getOrNull(entry.src)?.placeholders.orEmpty()
                    fun <V> kept(m: Map<Pair<Int, String>, V>) = m.filterKeys { it.first != uid || it.second !in ph }
                    change(e.copy(reset = e.reset + uid, frames = kept(e.frames), looks = kept(e.looks), turns = kept(e.turns))); item = null; WriterLog.i("Slides: slide reset")
                }
                // Section (PowerPoint's): slides in named groups, from the chosen one; an .odp has none
                if (deck?.sections != null) RibbonButton(stringResource(R.string.slides_section), w, R.drawable.ic_label) { sectioning = true }
                RibbonButton(stringResource(R.string.slides_duplicate), w, R.drawable.ic_copy) { duplicateSlide(c) }
                // PowerPoint's Selection Pane (Home › Arrange): the way to reach a thing under another on a tablet; Select all (Home › Select)
                RibbonButton(stringResource(R.string.slides_selection_pane), w, R.drawable.ic_layers) { selectionPane = true }
                RibbonButton(stringResource(R.string.common_select_all), w, R.drawable.ic_select_all) { chooseAll() }
                RibbonDivider(w)
                RibbonButton(stringResource(R.string.slides_move_up), w, R.drawable.ic_keyboard_arrow_up, enabled = c > 0) { change(e.copy(order = e.order.toMutableList().apply { add(c - 1, removeAt(c)) })); chosen = c - 1 }
                RibbonButton(stringResource(R.string.slides_move_down), w, R.drawable.ic_keyboard_arrow_down, enabled = c < e.order.lastIndex) { change(e.copy(order = e.order.toMutableList().apply { add(c + 1, removeAt(c)) })); chosen = c + 1 }
                RibbonDivider(w)
                val hidden = e.hidden[uid] ?: deck?.slides?.getOrNull(entry.src)?.hidden ?: false // a new slide is shown
                RibbonButton(if (hidden) stringResource(R.string.common_show) else stringResource(R.string.common_hide), w, if (hidden) R.drawable.ic_visibility else R.drawable.ic_visibility_off) { change(e.copy(hidden = e.hidden + (uid to !hidden))) }
                // the slide's speaker notes typed in a bar of their own (asked 17 Oct); a desktop has them under the slide
                if (!OfficeUi.top) RibbonButton(stringResource(R.string.slides_speaker_notes), w, R.drawable.ic_comment) {
                    val n = view?.slides?.getOrNull(c)?.notes.orEmpty()
                    notesTyping = uid to androidx.compose.ui.text.input.TextFieldValue(n, androidx.compose.ui.text.TextRange(n.length))
                }
                RibbonDivider(w)
                RibbonButton(stringResource(R.string.common_delete), w, R.drawable.ic_delete, enabled = e.order.size > 1) { change(e.copy(order = e.order.filterIndexed { k, _ -> k != c })); chosen = c.coerceAtMost(e.order.size - 2) }
            }
            "Insert" -> {
                RibbonButton(stringResource(R.string.slides_picture), w, R.drawable.ic_gallery) { pick.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                RibbonButton(stringResource(R.string.slides_shape), w, R.drawable.ic_category) { shapes = true }
                // Drop cap (asked 17 Oct): Words' strip of cards — PowerPoint and Impress have none, so a raised first letter
                RibbonButton(stringResource(R.string.slides_drop_cap), w, R.drawable.ic_drop_cap) { if (chosenWords() != null) strip = DesignGallery.DropCap else message = context.getString(R.string.slides_choose_box_words) }
                // Text box (as PowerPoint's): a box of words alone across the slide's middle, typed into at once
                RibbonButton(stringResource(R.string.slides_text_box), w, R.drawable.ic_text_box) { newTextBox() }
                // WordArt (Words' strip of looks and bends): a new one, or the chosen box's words in the look tapped
                RibbonButton(stringResource(R.string.slides_wordart), w, R.drawable.ic_format_color_text) { strip = DesignGallery.WordArt }
                TableSizeButton(w, POWERPOINT_ORANGE) { rows, cols ->
                    // in the middle, four fifths of the slide wide, a row 36 pt high
                    val dd = deck ?: return@TableSizeButton; val ee = edit ?: return@TableSizeButton; val cc = chosen ?: return@TableSizeButton
                    val tw = dd.width * 0.8f; val th = rows * 36f
                    put(SlidesEdit.Added(ee.newId(ee.order[cc].uid, taken(ee, cc)), (dd.width - tw) / 2, (dd.height - th) / 2, tw, th, prst = "table", rows = rows, cols = cols, cells = List(rows * cols) { "" }))
                    message = context.getString(R.string.slides_tap_cell); WriterLog.i("Slides: a table put on a slide ($rows × $cols)") }
                // Video (asked 17 Oct): from the phone (kept in the deck) or online (a web address), played when tapped in a show
                VideoButton(w, phone = { pickVideo.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.VideoOnly)) }, online = ::putOnlineVideo)
                RibbonButton(stringResource(R.string.slides_audio), w, R.drawable.ic_volume_up, enabled = chosen != null) { pickSound.launch("audio/*") }
                // Chart (as PowerPoint's): Words' chart editor, the chart across most of the slide
                RibbonButton(stringResource(R.string.slides_chart), w, R.drawable.ic_bar_chart) { charting = null to WORD_SAMPLE_CHART }
                // Link (as Words'): a box's words linked to a web page or a slide; nothing chosen: a text box of words to show
                RibbonButton(stringResource(R.string.slides_link), w, R.drawable.ic_link) { linking = true }
                // Comment (as Words'): Words' comment dialog, the comment by the chosen item (else the slide's top left)
                RibbonButton(stringResource(R.string.slides_comment), w, R.drawable.ic_comment) { commentAsk = 'n' to null }
                // Header & footer (as PowerPoint's): the date, the slide's number and a footer, on this slide or all
                RibbonButton(stringResource(R.string.slides_header_footer), w, R.drawable.ic_page_header) { footing = true }
                // Words' Symbol and Date, and a line break inside a paragraph: where the typing cursor is (the chosen box's words opened at their end)
                RibbonDivider(w)
                RibbonButton(stringResource(R.string.slides_symbol), w, R.drawable.ic_special_character) { symbols = true }
                RibbonButton(stringResource(R.string.slides_date), w, R.drawable.ic_calendar_today) { dating = true }
                RibbonButton(stringResource(R.string.slides_line_break), w, R.drawable.ic_wrap_text) { putWords(LINE_BREAK.toString()) }
                RibbonButton(stringResource(R.string.slides_equation), w, R.drawable.ic_functions) { equating = null to null }
            }
            "File" -> {
                // as Words' and Tables' File: the file's own actions (asked 16 Oct: Share in File); a change saved first, so what goes is what shows
                RibbonButton(stringResource(R.string.common_save), w, R.drawable.ic_save, enabled = changed && !saving) { save() }
                // the same File as Words' and Tables' (17 Oct)
                RibbonDivider(w)
                if (file != null) RibbonButton(stringResource(R.string.slides_versions), w, R.drawable.ic_history) { showVersions = true }
                // as Words' File: Save a copy, AutoSave, Save as, Rename; PowerPoint's Save as template and Inspect
                RibbonButton(stringResource(R.string.slides_save_copy), w, R.drawable.ic_copy, enabled = file != null) {
                    file?.let { f -> save(to = generateSequence(1) { it + 1 }.map { File(f.parentFile, "${f.nameWithoutExtension} (copy${if (it == 1) "" else " $it"}).${f.extension}") }.first { !it.exists() }, copy = true) } }
                RibbonButton(stringResource(R.string.slides_autosave), w, R.drawable.ic_history, on = autoSave) {
                    autoSave = !autoSave; TextEditorStore(context).autoSave = autoSave; message = if (autoSave) context.getString(R.string.slides_autosave_on) else context.getString(R.string.slides_autosave_off) }
                FavouriteFileButton(file, w) // a phone: the Office home's favourite (a tablet: the heart in the top bar)
                RibbonButton(stringResource(R.string.slides_save_as), w, R.drawable.ic_save, enabled = file != null) { savingAs = true }
                RibbonButton(stringResource(R.string.common_rename), w, R.drawable.ic_edit, enabled = file != null) { renaming = true }
                if (!odp) RibbonButton(stringResource(R.string.slides_save_as_template), w, R.drawable.ic_palette, enabled = file != null) {
                    val f = file ?: return@RibbonButton
                    val temp = File(context.cacheDir, "template-${System.nanoTime()}.pptx")
                    save(to = temp, copy = true, then = { scope.launch {
                        val target = generateSequence(1) { it + 1 }.map { File(SlidesTemplates.dir(), if (it == 1) "${f.nameWithoutExtension}.potx" else "${f.nameWithoutExtension} ($it).potx") }.first { !it.exists() }
                        val ok = withContext(Dispatchers.IO) { runCatching { SlidesTemplates.dir().mkdirs(); SlidesTemplates.template(temp, target); temp.delete() }.onFailure { WriterLog.e("Slides: template", it) }.isSuccess }
                        message = if (ok) context.getString(R.string.slides_template_saved, target.name) else context.getString(R.string.slides_could_not_make_template)
                    } })
                }
                RibbonButton(stringResource(R.string.slides_inspect), w, R.drawable.ic_search) { inspecting = true }
                RibbonDivider(w)
                RibbonButton(stringResource(R.string.slides_export_pdf), w, R.drawable.ic_file, enabled = file != null) { exportPdf() }
                RibbonButton(stringResource(R.string.slides_export_pictures), w, R.drawable.ic_gallery, enabled = file != null) { exportPictures() }
                // Print: full slides, or handouts (PowerPoint's 2, 3 with lines for notes, 6 to a page)
                RibbonMenu(stringResource(R.string.common_print), w, R.drawable.ic_print, preset = false, choices = listOf(stringResource(R.string.slides_full_slides) to 1, stringResource(R.string.slides_handouts_2) to 2, stringResource(R.string.slides_handouts_3) to 3, stringResource(R.string.slides_handouts_6) to 6)
                    .map { (label, n) -> MenuChoice(label) { printIt(n) } })
                file?.let { f ->
                    RibbonDivider(w)
                    RibbonButton(stringResource(R.string.common_share), w, R.drawable.ic_share) { if (changed) save { shareDriveFile(context, f) } else shareDriveFile(context, f) }
                    RibbonButton(stringResource(R.string.slides_open_elsewhere), w, R.drawable.ic_open_with) { if (changed) save { openElsewhere(context, f) } else openElsewhere(context, f) }
                }
                RibbonDivider(w)
                RibbonButton(stringResource(R.string.slides_about_file), w, R.drawable.ic_info) { about = true }
                RibbonButton(stringResource(R.string.slides_send_log), w, R.drawable.ic_share) { WriterLog.export(context)?.let { shareDriveFile(context, it) } }
                RibbonDivider(w)
                RibbonButton(stringResource(R.string.common_settings), w, R.drawable.ic_settings) { officeSettings = true }
            }
            "Slide show" -> {
                // PowerPoint's and LibreOffice's Slide Show tab (asked 16 Oct: where presenting starts)
                RibbonButton(stringResource(R.string.slides_from_beginning), w, R.drawable.ic_play) { presenting = 0 }
                RibbonButton(stringResource(R.string.slides_from_this_slide), w, R.drawable.ic_play) { presenting = c }
                RibbonDivider(w)
                // PowerPoint's Set Up Slide Show: Loop until Esc (kept in the file); Rehearse Timings: each slide's time, kept as its "after" time
                val looping = e.loop ?: deck?.loop ?: false
                RibbonButton(stringResource(R.string.slides_loop), w, R.drawable.ic_restore, on = looping) { change(e.copy(loop = !looping)) }
                RibbonButton(stringResource(R.string.slides_rehearse), w, R.drawable.ic_calendar_today) { rehearsing = true; presenting = 0 }
                // Set Up Slide Show's Show without animation and Use timings (kept in the file, as Loop)
                val still = e.noAnimation ?: deck?.noAnimation ?: false; val manual = e.manual ?: deck?.manual ?: false
                RibbonButton(stringResource(R.string.slides_without_animation), w, R.drawable.ic_visibility_off, on = still) { change(e.copy(noAnimation = !still)) }
                RibbonButton(stringResource(R.string.slides_use_timings), w, R.drawable.ic_history, on = !manual) { change(e.copy(manual = !manual)) }
                RibbonDivider(w)
                val hidden = e.hidden[uid] ?: deck?.slides?.getOrNull(entry.src)?.hidden ?: false
                RibbonButton(if (hidden) stringResource(R.string.slides_show_slide) else stringResource(R.string.slides_hide_slide), w, if (hidden) R.drawable.ic_visibility else R.drawable.ic_visibility_off) { change(e.copy(hidden = e.hidden + (uid to !hidden))) }
            }
            "Draw" -> {
                // Words' Draw (PowerPoint's Draw tab): pens and a highlighter on the slide itself, the drawing put on where it was drawn
                RibbonButton(stringResource(R.string.slides_draw_on_slide), w, R.drawable.ic_draw) { drawing = true }
            }
            "View" -> {
                // PowerPoint's Presentation Views: the slide (Normal) or every slide in a grid (Slide sorter), where several are moved at once
                RibbonButton(stringResource(R.string.slides_normal), w, R.drawable.ic_crop_landscape, on = !sorter && !outline) { sorter = false; outline = false }
                RibbonButton(stringResource(R.string.slides_outline_view), w, R.drawable.ic_view_list, on = outline) { outline = true; sorter = false; item = null; typing = null }
                RibbonButton(stringResource(R.string.slides_slide_sorter), w, R.drawable.ic_view_grid, on = sorter) { sorter = true; outline = false; item = null; marked = setOf(uid) }
                RibbonButton(stringResource(R.string.slides_gridlines), w, R.drawable.ic_border_all, on = grid) { grid = !grid }
                // Words' rulers round the slide being edited, in Words' unit from its top left (a tablet's, as Words shows them)
                if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 700) {
                    RibbonButton(stringResource(R.string.slides_ruler), w, R.drawable.ic_margin, on = rulers) { rulers = !rulers }
                    // PowerPoint's Zoom: Fit (the slide as big as the room lets it be), or bigger, moved about by dragging where nothing is
                    RibbonMenu(if (slideZoom == 1f) stringResource(R.string.slides_zoom) else stringResource(R.string.slides_zoom_pc, Math.round(slideZoom * 100)), w, R.drawable.ic_zoom_in, on = slideZoom != 1f, title = stringResource(R.string.slides_zoom), choices = listOf(stringResource(R.string.slides_fit) to 1f, "150%" to 1.5f, "200%" to 2f, "300%" to 3f).map { (label, z) ->
                        MenuChoice(label, checked = slideZoom == z) { slideZoom = z; WriterLog.i("Slides: zoom $label") } })
                }
                // PowerPoint's Reading View: the show from this slide (on a phone or tablet, the screen is its window)
                RibbonButton(stringResource(R.string.slides_reading_view), w, R.drawable.ic_play) { presenting = c }
                // the speaker notes under the slide, typed there (PowerPoint's Notes); a desktop always shows them
                // PowerPoint's Show › Notes: the notes pane on or off beside the view chosen (the tablet walk, 18 Oct: not a view of its own)
                if (!OfficeUi.top) { RibbonDivider(w); RibbonButton(stringResource(R.string.slides_notes), w, R.drawable.ic_comment, on = notesPage) { notesPage = !notesPage; sorter = false; outline = false } }
                if (sorter) {
                    RibbonDivider(w)
                    Text(stringResource(R.string.slides_n_chosen, marked.size), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
                    RibbonButton(stringResource(R.string.slides_choose_all), w, R.drawable.ic_done_all) { marked = e.order.map { it.uid }.toSet() }
                    RibbonButton(stringResource(R.string.slides_duplicate), w, R.drawable.ic_copy, enabled = marked.isNotEmpty()) {
                        e.order.withIndex().filter { it.value.uid in marked }.map { it.index }.reversed().forEach { duplicateSlide(it) } }
                    val allHidden = e.order.filter { it.uid in marked }.all { en -> e.hidden[en.uid] ?: deck?.slides?.getOrNull(en.src)?.hidden ?: false }
                    RibbonButton(if (allHidden) stringResource(R.string.common_show) else stringResource(R.string.common_hide), w, if (allHidden) R.drawable.ic_visibility else R.drawable.ic_visibility_off, enabled = marked.isNotEmpty()) {
                        change(e.copy(hidden = e.hidden + marked.associateWith { !allHidden })) }
                    RibbonButton(stringResource(R.string.common_delete), w, R.drawable.ic_delete, enabled = marked.isNotEmpty() && marked.size < e.order.size) {
                        change(e.copy(order = e.order.filter { it.uid !in marked })); marked = emptySet(); chosen = 0 }
                }
                RibbonDivider(w)
                RibbonButton(stringResource(R.string.slides_comments), w, R.drawable.ic_comment, on = commentsOn) { commentsOn = !commentsOn }
            }
            "References" -> {
                // Words' References for a deck (the user's choices, 17 Oct): its sources, their style, citations put in where one types
                RibbonButton(stringResource(R.string.slides_sources), w, R.drawable.ic_checklist) { sourcesOpen = true }
                // shortcut: IEEE numbers its citations by their order, which words on slides cannot follow
                RibbonMenu(stringResource(R.string.slides_style_x, citeStyle.label), w, R.drawable.ic_style, title = stringResource(R.string.slides_citation_style), choices = CiteStyle.entries.filter { it != CiteStyle.IEEE }.map { st ->
                    MenuChoice(st.label, checked = st == citeStyle) { resource(sources, st, context.getString(R.string.slides_citations_in, st.label)) } })
                RibbonButton(stringResource(R.string.slides_insert_citation), w, R.drawable.ic_format_quote) { if (sources.isEmpty()) sourceNew = true else citing = true }
                RibbonDivider(w)
                // made, or brought up to date (the old ones replaced)
                RibbonButton(stringResource(R.string.slides_references_slide), w, R.drawable.ic_view_list) { referencesSlides() }
                RibbonButton(stringResource(R.string.slides_contents_slide), w, R.drawable.ic_checklist) { contentsSlide() }
            }
            "Review" -> {
                // Words' Read aloud: the slides' words from this slide on, box by box top to bottom; or this slide's speaker notes
                fun readFrom(notesToo: Boolean) {
                    val list = view?.slides.orEmpty().withIndex().drop(c).flatMap { (i, sl) ->
                        if (notesToo) listOf(i to sl.notes) else sl.items.filterIsInstance<SlideItem.Shape>().filter { it.text != null && !it.hint }.sortedWith(compareBy({ it.y }, { it.x }))
                            .flatMap { sh -> SlidesEdit.lines(sh).map { i to it.replace(LINE_BREAK, ' ') } } }.filter { it.second.isNotBlank() }
                    reading = list
                    if (!reader.read(list.map { it.second }, 0)) message = context.getString(R.string.slides_no_tts)
                }
                // PowerPoint's Spelling (Words' dictionaries): every box's words, slide by slide from the first
                RibbonButton(stringResource(R.string.slides_spelling), w, R.drawable.ic_spellcheck) { message = context.getString(R.string.slides_checking_spelling); scope.launch {
                    val words = view?.slides.orEmpty().withIndex().flatMap { (i, sl) -> sl.items.filterIsInstance<SlideItem.Shape>().filter { it.text != null && !it.hint && it.id != null }
                        .sortedWith(compareBy({ it.y }, { it.x })).flatMap { sh -> SlidesEdit.lines(sh).withIndex().flatMap { (n, line) ->
                            WriterSpelling.words(line).map { r -> SlideSpell(i, sh.id!!, sh.anim ?: sh.id!!, n, r, line.substring(r)) } } } }
                    val wrong = speller.check(words.map { it.word })
                    val queue = words.filter { it.word in wrong }
                    message = if (queue.isEmpty()) context.getString(R.string.slides_no_spelling_mistakes) else null
                    if (queue.isNotEmpty()) spelling = Triple(queue, wrong, emptySet())
                    WriterLog.i("Slides: spelling, ${queue.size} words to look at") } }
                RibbonDivider(w)
                RibbonButton(if (reader.speaking) stringResource(R.string.slides_stop_reading) else stringResource(R.string.slides_read_aloud), w, R.drawable.ic_volume_up, on = reader.speaking) { if (reader.speaking) reader.stop() else readFrom(false) }
                if (!reader.speaking) RibbonButton(stringResource(R.string.slides_read_notes), w, R.drawable.ic_volume_up) { readFrom(true) }
                RibbonDivider(w)
                // Words' Comments group: a new one, the comments shown, the one before or after (on other slides too), and the one tapped
                RibbonButton(stringResource(R.string.slides_new_comment), w, R.drawable.ic_comment) { commentAsk = 'n' to null }
                RibbonButton(stringResource(R.string.slides_comments), w, R.drawable.ic_comment, on = commentsOn) { commentsOn = !commentsOn; if (commentsOn && view?.slides?.getOrNull(c)?.comments.isNullOrEmpty()) message = context.getString(R.string.slides_no_comments_slide) }
                Tool(R.drawable.ic_chevron_left, stringResource(R.string.slides_previous_comment), w) { goComment(false) }
                Tool(R.drawable.ic_chevron_right, stringResource(R.string.slides_next_comment), w) { goComment(true) }
                RibbonDivider(w)
                val here = view?.slides?.getOrNull(c)?.comments?.firstOrNull { it.comment.id == hereComment }?.comment
                RibbonButton(stringResource(R.string.slides_reply), w, R.drawable.ic_comment, enabled = here != null) { commentAsk = 'r' to here }
                RibbonButton(stringResource(R.string.slides_delete_comment), w, R.drawable.ic_delete, enabled = here != null) { here?.let { d -> recomment { l -> l.filter { it.comment.id != d.id && it.comment.parent != d.id } } } }
            }
            "Design" -> {
                // PowerPoint's Design: the deck's theme — its colours, fonts and background — light or dark, Tetra's or a template's
                // a tablet or phone: the themes on a strip, the one tapped shown on the slides, ✓ keeps it, More… the whole panel
                val themeStrip = officeStrip(stringResource(R.string.slides_themes), ok = { previewKeep() }, cancel = { previewDrop() }) { s ->
                    val strips = LocalOfficeStrips.current
                    val templates by androidx.compose.runtime.produceState(emptyList<SlideTheme>()) { value = withContext(Dispatchers.IO) { SlidesTemplates.files().mapNotNull { SlidesThemes.fromFile(it) } } }
                    val card = if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 700) 150.dp else 112.dp
                    (SlidesThemes.BUILT_IN + templates).forEach { t -> ThemeCard(t, (s.picked ?: e.theme) == t, Modifier.width(card).padding(horizontal = 3.dp)) { s.picked = t; preview { it.copy(theme = t) } } }
                    RibbonButton(stringResource(R.string.common_more), w, R.drawable.ic_more_horiz) { strips?.cancel(); themesPage = true }
                }
                RibbonButton(stringResource(R.string.slides_themes), w, R.drawable.ic_palette) { themeStrip?.invoke() ?: run { themesPage = true } }
                Text(e.theme?.let { stringResource(R.string.slides_theme_x, it.name) } ?: stringResource(R.string.slides_file_theme), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
                if (e.theme != null) RibbonButton(stringResource(R.string.slides_as_file_was), w, R.drawable.ic_restore) { change(e.copy(theme = null)) }
                // Format background (PowerPoint's): this slide's own colour or picture, the same on all, or as its layout has it again
                RibbonDivider(w)
                fun back(col: Color?) = SlidesEdit.Back(color = col?.toArgb() ?: 0xFFFFFFFF.toInt())
                // a gradient (PowerPoint's Gradient fill): the colour's light tint at the top to the colour at the foot
                fun gradient(col: Color): SlidesEdit.Back { val c = col.toArgb()
                    fun tint(v: Int) = v + (255 - v) * 7 / 10
                    return SlidesEdit.Back(color = android.graphics.Color.rgb(tint(android.graphics.Color.red(c)), tint(android.graphics.Color.green(c)), tint(android.graphics.Color.blue(c))), to = c) }
                // a tablet or phone: each on a strip, the colour tapped shown on the slide, ✓ keeps it (the user, 18 Oct)
                for ((label, icon, make) in listOf(Triple(stringResource(R.string.slides_background), R.drawable.ic_format_color_fill) { c: Color? -> back(c) },
                    Triple(stringResource(R.string.slides_gradient), R.drawable.ic_gradient) { c: Color? -> c?.let(::gradient) })) {
                    val colours = SHAPE_COLOURS.map { Color(it) }
                    val colourStrip = officeStrip(label, ok = { previewKeep(); WriterLog.i("Slides: $label chosen") }, cancel = { previewDrop() }) { s ->
                        SwatchDots(w, label, colours, s.picked) { c -> make(c)?.let { b -> s.picked = c ?: NO_COLOUR; preview { it.copy(backgrounds = it.backgrounds + (uid to b)) } } }
                        val strips = LocalOfficeStrips.current
                        MoreColours(w, label) { c -> make(c)?.let { b -> s.picked = c; preview { it.copy(backgrounds = it.backgrounds + (uid to b)) }; strips?.ok() } }
                    }
                    if (colourStrip != null) Tool(icon, label, w) { colourStrip() }
                    else Swatches(w, icon, label, colours) { c -> make(c)?.let { b -> change(e.copy(backgrounds = e.backgrounds + (uid to b))); WriterLog.i("Slides: $label background") } }
                }
                RibbonButton(stringResource(R.string.slides_background_picture), w, R.drawable.ic_gallery) { pickBack.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                e.backgrounds[uid]?.let { b ->
                    RibbonButton(stringResource(R.string.slides_apply_to_all), w, R.drawable.ic_done_all) { change(e.copy(backgrounds = e.backgrounds + e.order.associate { it.uid to b })) }
                    RibbonButton(stringResource(R.string.slides_reset_background), w, R.drawable.ic_restore) { change(e.copy(backgrounds = e.backgrounds - uid)) }
                }
                // Words' Colours and Fonts strips: the theme's colour set (PowerPoint's Variants) and its heading and body fonts, the design kept
                RibbonDivider(w)
                if (!odp) RibbonButton(stringResource(R.string.slides_colours), w, R.drawable.ic_format_color_text) { strip = DesignGallery.Colours }
                RibbonButton(stringResource(R.string.slides_fonts), w, R.drawable.ic_style) { strip = DesignGallery.Fonts }
                // Slide size (PowerPoint's): its sizes and one's own, the things on the slides fitted in or made as big as it allows
                RibbonDivider(w)
                fun same(d: Deck?, sw: Float, sh: Float) = d != null && Math.abs(d.width - sw) < 0.5f && Math.abs(d.height - sh) < 0.5f
                fun resize(sw: Float, sh: Float) {
                    val e = edit ?: return // as it is now (a strip's preview put back first), not as the ribbon was drawn
                    val now = e.size?.let { it.w to it.h } ?: deck?.let { it.width to it.height }
                    if (now != null && Math.abs(now.first - sw) < 0.5f && Math.abs(now.second - sh) < 0.5f) return
                    if (same(deck, sw, sh)) { change(e.copy(size = null)); return } // back to the file's own
                    // everything on the slides is moved from the file as saved: other changes are saved first (asked, 18 Oct)
                    // saved already (Undo kept over the save): the file read again as the start, then the question
                    if (e.copy(theme = null, size = null) == SlidesEdit.start(deck ?: return)) resizing = sw to sh
                    else if (e == saved) save(rebase = true) { resizing = sw to sh } else sizeAfterSave = sw to sh
                }
                // a tablet or phone: the size tapped shown on the slides (fitted) before ✓, which asks Maximize or Ensure fit (the second walk,
                // 18 Oct); with other changes unsaved only marked — ✓ offers Save first
                val clean = e.copy(theme = null, size = null) == SlidesEdit.start(deck ?: return)
                val sizeStrip = officeStrip(stringResource(R.string.slides_slide_size), ok = { s -> previewDrop(); SlidesSize.PRESETS.firstOrNull { it.first == s.picked }?.second?.let { (sw, sh) -> resize(sw, sh) } }, cancel = { previewDrop() }) { s ->
                    val strips = LocalOfficeStrips.current
                    SlidesSize.PRESETS.forEach { (label, z) -> RibbonButton(stringResource(label), w, on = if (s.picked != null) s.picked == label else same(base, z.first, z.second)) {
                        s.picked = label; if (clean) preview { it.copy(size = if (same(deck, z.first, z.second)) null else SlidesSize.Size(z.first, z.second, false)) } } }
                    RibbonButton(stringResource(R.string.slides_custom), w, on = s.picked == null && SlidesSize.PRESETS.none { (_, z) -> same(base, z.first, z.second) }) { strips?.cancel(); slideSizing = true }
                }
                if (sizeStrip != null) RibbonButton(stringResource(R.string.slides_slide_size), w, R.drawable.ic_aspect_ratio) { sizeStrip() }
                else RibbonMenu(stringResource(R.string.slides_slide_size), w, R.drawable.ic_aspect_ratio, choices = SlidesSize.PRESETS.map { (label, z) -> MenuChoice(stringResource(label), checked = same(base, z.first, z.second)) { resize(z.first, z.second) } } +
                    MenuChoice(stringResource(R.string.slides_custom), checked = SlidesSize.PRESETS.none { (_, z) -> same(base, z.first, z.second) }, opens = true) { slideSizing = true })
            }
            "Transitions" -> {
                // how this slide comes in when presenting; Options: its side, speed, and when the next comes; Play shows it from the slide before
                val now = sl?.transition
                // LibreOffice has no cut: an .odp leaves it out
                val kinds = listOfNotNull(stringResource(R.string.common_none) to "", stringResource(R.string.slides_fade) to "fade", stringResource(R.string.slides_push) to "push", stringResource(R.string.slides_wipe) to "wipe", stringResource(R.string.slides_cover) to "cover", stringResource(R.string.slides_uncover) to "pull", (stringResource(R.string.slides_cut) to "cut").takeIf { !odp })
                fun set(t: SlideTransition) { change(e.copy(transitions = e.transitions + (uid to t))); WriterLog.i("Slides: transition ${t.kind} ${t.dir} ${t.ms}") }
                if (effectOptions) {
                    if (now != null && now.kind in setOf("push", "wipe", "cover", "pull"))
                        for ((label, d) in listOf(stringResource(R.string.slides_from_right) to "l", stringResource(R.string.slides_from_left) to "r", stringResource(R.string.slides_from_below) to "u", stringResource(R.string.slides_from_above) to "d"))
                            RibbonButton(label, w, on = (now.dir ?: "l") == d) { set(now.copy(dir = d)) }
                    if (now != null && now.kind in setOf("push", "wipe", "cover", "pull")) RibbonDivider(w)
                    if (now != null && now.kind.isNotEmpty()) for ((label, ms) in listOf(stringResource(R.string.slides_fast) to 500, stringResource(R.string.slides_medium) to 750, stringResource(R.string.slides_slow) to 1000)) RibbonButton(label, w, on = now.ms == ms) { set(now.copy(ms = ms)) }
                    // when the next slide comes: on a click, or by itself after a time (PowerPoint's Advance Slide)
                    if (now != null && now.kind.isNotEmpty()) RibbonDivider(w)
                    Text(stringResource(R.string.slides_next_slide), style = MaterialTheme.typography.labelMedium, color = w.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
                    for ((label, after) in listOf(stringResource(R.string.slides_on_click) to null, stringResource(R.string.slides_after_s, 3) to 3000, stringResource(R.string.slides_after_s, 5) to 5000, stringResource(R.string.slides_after_s, 10) to 10000, stringResource(R.string.slides_after_s, 20) to 20000))
                        RibbonButton(label, w, on = now?.after == after) { set((now ?: SlideTransition("", null, 0)).copy(after = after)) }
                } else {
                    for ((label, kind) in kinds) RibbonButton(label, w, on = (now?.kind ?: "") == kind) { set(SlideTransition(kind, now?.dir, if (kind.isEmpty()) 0 else now?.ms?.takeIf { it > 0 } ?: 750, now?.after)) }
                    RibbonDivider(w)
                    RibbonButton(stringResource(R.string.slides_options), w, R.drawable.ic_tune) { effectOptions = true }
                    // a kind PowerPoint names in another namespace (p14's) is not copied: it would be written in the wrong one
                    RibbonButton(stringResource(R.string.slides_apply_to_all), w, R.drawable.ic_done_all, enabled = kinds.any { it.second == (now?.kind ?: "") }) { val t = now ?: SlideTransition("", null, 750); change(e.copy(transitions = e.transitions + e.order.associate { it.uid to t })) }
                    RibbonDivider(w)
                    RibbonButton(stringResource(R.string.slides_play), w, R.drawable.ic_play) { presenting = (c - 1).coerceAtLeast(0) }
                }
            }
            "Animations" -> {
                // the chosen item's entrance and exit, each on its own click, in the slide's order; Options: side and speed
                val list = e.anims[uid] ?: SlidesEdit.flat(sl?.steps.orEmpty())
                val id = item
                fun set(to: List<SlideEffect>) { change(e.copy(anims = e.anims + (uid to to))); WriterLog.i("Slides: ${to.size} animations on a slide") }
                val inK = list.indexOfFirst { it.target == id && !it.exit && !it.emphasis }; val outK = list.indexOfFirst { it.target == id && it.exit }
                val emK = list.indexOfFirst { it.target == id && it.emphasis }
                /** The item's entrance ('i'), emphasis ('m') or exit ('o') made [kind] (null: none), in its place in the clicks or last. */
                fun choose(which: Char, kind: String?) {
                    val k = when (which) { 'o' -> outK; 'm' -> emK; else -> inK }; val id2 = id ?: return
                    val old = list.getOrNull(k)
                    val fx = kind?.let { SlideEffect(id2, it, old?.from ?: 'd', 0, old?.ms ?: if (which == 'm') 1000 else 500, which == 'o') }
                    set(when { fx == null -> list.filterIndexed { i, _ -> i != k }; k >= 0 -> list.toMutableList().also { it[k] = fx }; else -> list + fx })
                }
                when {
                    id == null -> {
                        Text(stringResource(R.string.slides_choose_thing), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
                        RibbonButton(stringResource(R.string.slides_play), w, R.drawable.ic_play) { presenting = c }
                    }
                    effectKinds -> {
                        // a catalog: the same kind of choice, so a line each for in, emphasis and out (asked 16 Oct)
                        Column {
                            for ((which, name, kinds) in listOf(
                                Triple('i', stringResource(R.string.slides_in), listOf(stringResource(R.string.common_none) to null, stringResource(R.string.slides_appear) to "appear", stringResource(R.string.slides_fade) to "fade", stringResource(R.string.slides_fly) to "fly", stringResource(R.string.slides_wipe) to "wipe", stringResource(R.string.slides_zoom) to "zoom")),
                                Triple('m', stringResource(R.string.slides_emphasis), listOf(stringResource(R.string.common_none) to null, stringResource(R.string.slides_pulse) to "pulse", stringResource(R.string.slides_spin) to "spin", stringResource(R.string.slides_grow) to "grow")),
                                Triple('o', stringResource(R.string.slides_out), listOf(stringResource(R.string.common_none) to null, stringResource(R.string.slides_disappear) to "appear", stringResource(R.string.slides_fade) to "fade", stringResource(R.string.slides_fly) to "fly", stringResource(R.string.slides_wipe) to "wipe", stringResource(R.string.slides_zoom) to "zoom")))) {
                                val k = when (which) { 'o' -> outK; 'm' -> emK; else -> inK }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(name, style = MaterialTheme.typography.labelMedium, color = w.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(72.dp).padding(start = 6.dp))
                                    for ((label, kind) in kinds) RibbonButton(label, w, on = if (k < 0) kind == null else list[k].kind == kind) { choose(which, kind) }
                                }
                            }
                        }
                    }
                    effectOptions && (inK >= 0 || outK >= 0 || emK >= 0) -> {
                        // the side a fly comes from (or goes to), and how long each of the item's effects takes
                        fun all(f: (SlideEffect) -> SlideEffect) = set(list.map { if (it.target == id) f(it) else it })
                        val mineNow = list.filter { it.target == id }
                        val sided = setOf("fly", "wipe")
                        if (mineNow.any { it.kind in sided }) for ((label, d) in listOf(stringResource(R.string.slides_bottom) to 'd', stringResource(R.string.slides_top) to 'u', stringResource(R.string.slides_left) to 'l', stringResource(R.string.slides_right) to 'r'))
                            RibbonButton(label, w, on = mineNow.first { it.kind in sided }.from == d) { all { if (it.kind in sided) it.copy(from = d) else it } }
                        if (mineNow.any { it.kind in sided }) RibbonDivider(w)
                        for ((label, ms) in listOf(stringResource(R.string.slides_fast) to 500, stringResource(R.string.slides_medium) to 1000, stringResource(R.string.slides_slow) to 2000)) RibbonButton(label, w, on = mineNow.first().ms == ms) { all { it.copy(ms = ms) } }
                        // when its first effect starts (PowerPoint's Start)
                        val k = list.indexOfFirst { it.target == id }
                        RibbonDivider(w)
                        for ((label, c) in listOf(stringResource(R.string.slides_on_click) to 'c', stringResource(R.string.slides_with_previous) to 'w', stringResource(R.string.slides_after_previous) to 'a'))
                            RibbonButton(label, w, on = list[k].start == c) { set(list.toMutableList().also { it[k] = it[k].copy(start = c) }) }
                        // and how long it waits from then (PowerPoint's Delay)
                        RibbonDivider(w)
                        Text(stringResource(R.string.slides_delay), style = MaterialTheme.typography.labelMedium, color = w.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
                        for ((label, ms) in listOf(stringResource(R.string.common_none) to 0, stringResource(R.string.slides_half_s) to 500, stringResource(R.string.slides_n_s, 1) to 1000, stringResource(R.string.slides_n_s, 2) to 2000, stringResource(R.string.slides_n_s, 5) to 5000))
                            RibbonButton(label, w, on = list[k].wait == ms) { set(list.toMutableList().also { it[k] = it[k].copy(wait = ms) }) }
                    }
                    else -> {
                        val first = listOf(inK, emK, outK).filter { it >= 0 }.minOrNull() ?: -1
                        // which click brings it (several effects can share one)
                        val groups = SlidesEdit.group(list); val g = groups.indexOfFirst { st -> st.effects.any { it.target == id } }
                        Text(when { first < 0 -> stringResource(R.string.slides_no_animation); groups[g].auto -> stringResource(R.string.slides_by_itself); else -> stringResource(R.string.slides_click_of, groups.take(g + 1).count { !it.auto }, groups.count { !it.auto }) },
                            style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
                        RibbonButton(stringResource(R.string.slides_effect), w, R.drawable.ic_motion) { effectKinds = true }
                        RibbonButton(stringResource(R.string.slides_options), w, R.drawable.ic_tune, enabled = first >= 0) { effectOptions = true }
                        if (first >= 0) {
                            RibbonDivider(w)
                            RibbonButton(stringResource(R.string.slides_earlier), w, R.drawable.ic_keyboard_arrow_up, enabled = first > 0) { set(list.toMutableList().apply { add(first - 1, removeAt(first)) }) }
                            RibbonButton(stringResource(R.string.slides_later), w, R.drawable.ic_keyboard_arrow_down, enabled = first < list.lastIndex) { set(list.toMutableList().apply { add(first + 1, removeAt(first)) }) }
                        }
                        RibbonDivider(w)
                        // PowerPoint's Animation Pane and Animation Painter
                        RibbonButton(stringResource(R.string.slides_animation_pane), w, R.drawable.ic_format_list_numbered, enabled = list.isNotEmpty()) { animPane = true }
                        RibbonButton(stringResource(R.string.slides_animation_painter), w, R.drawable.ic_format_paint, on = animPainter != null, enabled = first >= 0) {
                            animPainter = if (animPainter != null) null else list.filter { it.target == id }
                            if (animPainter != null) message = context.getString(R.string.slides_choose_for_animations) }
                        RibbonDivider(w)
                        RibbonButton(stringResource(R.string.slides_play), w, R.drawable.ic_play) { presenting = c }
                    }
                }
            }
        }
    }
    // a keyboard's shortcuts (a tablet's or a desktop's, asked 16 Oct): the screen holds the keys while nothing is typed in
    val keys = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(editing, typing == null, cellEdit == null, notesTyping == null, presenting == null) { if (typing == null && cellEdit == null && notesTyping == null && presenting == null) runCatching { keys.requestFocus() } }
    Column(Modifier.fillMaxSize().background(Color(0xFF2B2B2B)).focusRequester(keys).focusable().onPreviewKeyEvent { k ->
        if (k.type != KeyEventType.KeyDown || typing != null || cellEdit != null || notesTyping != null) return@onPreviewKeyEvent false
        val step = (deck?.width ?: 720f) / (if (k.isShiftPressed) 8 else 40)
        when {
            k.key == Key.F5 -> { presenting = if (k.isShiftPressed) chosen ?: 0 else 0; true }
            !editing -> false
            k.isCtrlPressed && k.key == Key.S -> { if (changed && !saving) save(); true }
            k.isCtrlPressed && k.key == Key.F && editing -> { finding = true; true }
            k.isCtrlPressed && (k.key == Key.Y || (k.key == Key.Z && k.isShiftPressed)) -> { redoEdit(); true }
            k.isCtrlPressed && k.key == Key.Z -> { undoEdit(); true }
            // Cut, Copy and Paste as Words' and Tables' (17 Oct): what is chosen on the slide
            k.isCtrlPressed && item != null && k.key == Key.C -> { copyItem(); true }
            k.isCtrlPressed && item != null && k.key == Key.X -> { if (copyItem()) deleteItem(); true }
            k.isCtrlPressed && clip != null && k.key == Key.V -> { pasteItem(); true }
            item != null && (k.key == Key.Delete || k.key == Key.Backspace) -> { deleteItem(); true }
            k.isCtrlPressed && k.key == Key.A && editing && chosen != null -> { chooseAll(); true }
            item != null && k.key == Key.DirectionLeft -> { reframe { listOf(it.x - step, it.y, it.w, it.h) }; true }
            item != null && k.key == Key.DirectionRight -> { reframe { listOf(it.x + step, it.y, it.w, it.h) }; true }
            item != null && k.key == Key.DirectionUp -> { reframe { listOf(it.x, it.y - step, it.w, it.h) }; true }
            item != null && k.key == Key.DirectionDown -> { reframe { listOf(it.x, it.y + step, it.w, it.h) }; true }
            item != null && k.key == Key.Escape -> { item = null; true }
            // with nothing chosen on it, the arrows go from slide to slide
            item == null && (k.key == Key.DirectionUp || k.key == Key.PageUp) -> { chosen = ((chosen ?: 0) - 1).coerceAtLeast(0); true }
            item == null && (k.key == Key.DirectionDown || k.key == Key.PageDown) -> { chosen = ((chosen ?: -1) + 1).coerceAtMost((view?.slides?.size ?: 1) - 1); true }
            else -> false
        }
    }) {
        Surface(color = islandColor(), contentColor = islandContentColor(), modifier = Modifier.fillMaxWidth()) {
            // Find and Replace across the slides (Words' bar): the box a find is in chosen on its slide
            if (finding && editing) Box(Modifier.statusBarsPadding()) {
                val ms = remember(query, view) { found() }
                val at = findAt.coerceIn(0, (ms.size - 1).coerceAtLeast(0))
                fun go(k: Int) { ms.getOrNull(Math.floorMod(k, ms.size.coerceAtLeast(1)))?.let { m -> findAt = Math.floorMod(k, ms.size); chosen = m.slide; item = m.id; sorter = false; outline = false } }
                FindReplaceBar(islandContentColor(), query, { query = it; findAt = 0 }, if (query.isEmpty()) "" else if (ms.isEmpty()) "0" else "${at + 1}/${ms.size}", ms.isNotEmpty(),
                    previous = { go(at - 1) }, next = { go(at + 1) }, replacing = replacing, setReplacing = { replacing = it }, replacement = replacement, setReplacement = { replacement = it },
                    replace = { ms.getOrNull(at)?.let { replaceFound(listOf(it)) } }, replaceAll = { replaceFound(ms); message = context.getString(R.string.slides_replaced_n, ms.size) }, close = { finding = false })
            } else
            // reading aloud: Words' bar, what is being read and Stop
            if (reader.speaking) Box(Modifier.statusBarsPadding().padding(vertical = 6.dp)) { ReadAloudBar(reader, reading.getOrNull(reader.at)?.second, islandContentColor()) } else
            Row(Modifier.statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::leave) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.common_back)) }
                Column(Modifier.weight(1f)) {
                    Text((if (changed) "• " else "") + (file?.name ?: stringResource(R.string.slides_presentation)), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    view?.let { Text(if (editing) stringResource(R.string.slides_editing) else pluralStringResource(R.plurals.slides_n_slides, it.slides.size, it.slides.size), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = islandContentColor().copy(alpha = 0.7f)) }
                }
                if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 700) view?.let { v ->
                    val hidden = v.slides.count { it.hidden }
                    TopInfo(listOfNotNull(chosen?.let { stringResource(R.string.slides_slide_of, it + 1, v.slides.size) } ?: pluralStringResource(R.plurals.slides_n_slides, v.slides.size, v.slides.size),
                        stringResource(R.string.slides_n_hidden, hidden).takeIf { hidden > 0 }, if (editing) saveState(saving, changed) else null, file?.extension?.let { ".$it" }), islandContentColor())
                }
                FavouriteHeart(file, islandContentColor()) // a tablet or bigger: the Office home's Favourites (the user, 18 Oct)
                if (editing) {
                    IconButton(onClick = { finding = true }) { Icon(painterResource(R.drawable.ic_search), contentDescription = stringResource(R.string.slides_find_replace)) }
                    IconButton(onClick = ::undoEdit, enabled = undos.isNotEmpty()) {
                        Icon(painterResource(R.drawable.ic_undo), contentDescription = stringResource(R.string.common_undo)) }
                    IconButton(onClick = ::redoEdit, enabled = redos.isNotEmpty()) {
                        Icon(painterResource(R.drawable.ic_redo), contentDescription = stringResource(R.string.common_redo)) }
                    IconButton(onClick = { save() }, enabled = changed && !saving) { Icon(painterResource(R.drawable.ic_save), contentDescription = stringResource(R.string.common_save)) }
                    IconButton(onClick = { editing = false; chosen = null; typing = null }) { Icon(painterResource(R.drawable.ic_check), contentDescription = stringResource(R.string.common_done)) }
                } else {
                    IconButton(onClick = { notes = !notes }, enabled = deck != null) {
                        Icon(painterResource(R.drawable.ic_comment), contentDescription = if (notes) stringResource(R.string.slides_hide_notes) else stringResource(R.string.slides_show_notes), tint = if (notes) POWERPOINT_ORANGE else islandContentColor())
                    }
                    IconButton(onClick = { presenting = 0 }, enabled = view?.slides?.isNotEmpty() == true) { Icon(painterResource(R.drawable.ic_play), contentDescription = stringResource(R.string.slides_present)) }
                    file?.let { f ->
                        IconButton(onClick = { shareDriveFile(context, f) }) { Icon(painterResource(R.drawable.ic_share), contentDescription = stringResource(R.string.common_share)) }
                        IconButton(onClick = { openElsewhere(context, f) }) { Icon(painterResource(R.drawable.ic_open_with), contentDescription = stringResource(R.string.slides_open_elsewhere)) }
                    }
                    // Edit at the far right, where Done is while editing — the same place in Words, Tables and Slides
                    if (deck != null && file?.extension?.lowercase() in setOf("pptx", "odp") && file!!.canWrite()) IconButton(onClick = { editing = true; WriterLog.i("Slides: editing") }) {
                        Icon(painterResource(R.drawable.ic_edit), contentDescription = stringResource(R.string.common_edit)) }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val d = view
            when {
                failed != null -> Text(failed!!, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp))
                d == null -> Text(stringResource(R.string.slides_opening), color = Color.LightGray, modifier = Modifier.align(Alignment.Center))
                else -> {
                    val list = rememberLazyListState()
                    // the slide in the middle of the screen, where a swipe leaves it
                    val at by remember { derivedStateOf { list.layoutInfo.let { l -> val mid = (l.viewportStartOffset + l.viewportEndOffset) / 2
                        l.visibleItemsInfo.minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - mid) }?.index ?: 0 } } }
                    // a wide screen (a tablet held sideways): each slide no wider than fits the screen's height, so a whole slide shows
                    val fit = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp - 200).coerceAtLeast(200).dp * (d.width / d.height)
                    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 700
                    // a tablet edits a slide at a time: the first is chosen to begin with
                    LaunchedEffect(editing, wide, chosen) { if (editing && wide && chosen == null) chosen = 0 } // the ribbon stays (asked 17 Oct: it went after Save)
                    /**
                     * A desktop's notes pane (the ribbon at the top, asked 17 Oct): slide [i]'s speaker notes under it as PowerPoint's,
                     * small, typed in where they are, a scroll bar beside them; what is typed goes in as it is (one step of Undo a visit).
                     */
                    @Composable fun NotesPane(i: Int, s: Slide) {
                        val uid = edit?.order?.getOrNull(i)?.uid ?: return
                        var draft by remember(uid) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(s.notes)) }
                        var stepped by remember(uid) { mutableStateOf(false) } // this visit's step of Undo made
                        // Undo (or another slide's notes) shown while not typed in
                        LaunchedEffect(s.notes) { if (notesTyping?.first != uid && draft.text != s.notes) draft = androidx.compose.ui.text.input.TextFieldValue(s.notes) }
                        val scroll = rememberScrollState()
                        Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(88.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF3A3A3A))) {
                            androidx.compose.foundation.text.BasicTextField(draft, { v ->
                                    draft = v
                                    val ee = edit ?: return@BasicTextField
                                    if (v.text != (ee.notes[uid] ?: s.notes)) { val next = ee.copy(notes = ee.notes + (uid to v.text)); if (stepped) edit = next else { change(next); stepped = true } }
                                },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White), cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                                modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 12.dp, top = 10.dp, end = 20.dp, bottom = 10.dp).heightIn(min = 60.dp)
                                    .onFocusChanged { f -> if (f.isFocused) { notesTyping = uid to draft; if (chosen != i) chosen = i } else if (notesTyping?.first == uid) { notesTyping = null; stepped = false } },
                                decorationBox = { inner -> Box { if (draft.text.isEmpty()) Text(stringResource(R.string.slides_click_add_notes), color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.bodyMedium); inner() } })
                            // the scroll bar: its thumb as long as the part shown, dragged with the mouse
                            if (scroll.maxValue > 2) Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(12.dp)
                                .pointerInput(Unit) { detectVerticalDragGestures { ch, dy -> ch.consume(); scroll.dispatchRawDelta(dy * (size.height + scroll.maxValue) / size.height) } }
                                .drawBehind {
                                    val h = size.height; val th = (h * h / (h + scroll.maxValue)).coerceAtLeast(16.dp.toPx())
                                    drawRoundRect(Color.White.copy(alpha = 0.45f), androidx.compose.ui.geometry.Offset(size.width - 7.dp.toPx(), scroll.value.toFloat() / scroll.maxValue * (h - th)),
                                        androidx.compose.ui.geometry.Size(4.dp.toPx(), th), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                                })
                        }
                    }
                    /** Review › Comments: the chosen slide's, each Words' card with its replies under it — Edit, Reply, Delete; New at the top. */
                    @Composable fun CommentsPane(modifier: Modifier) {
                        val list = view?.slides?.getOrNull(chosen ?: -1)?.comments.orEmpty()
                        val act = CommentActions(repliesOf = { p -> list.map { it.comment }.filter { it.parent == p.id } }, open = { hereComment = it.id },
                            delete = { x -> recomment { l -> l.filter { it.comment.id != x.id && it.comment.parent != x.id } }; WriterLog.i("Slides: comment deleted") },
                            reply = { commentAsk = 'r' to it }, resolve = null, edit = { commentAsk = 'e' to it })
                        Column(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF2A2A2A)).verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.slides_comments), color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp))
                                androidx.compose.material3.TextButton(onClick = { commentAsk = 'n' to null }) { Text(stringResource(R.string.common_new), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                IconButton(onClick = { commentsOn = false }) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.slides_close_comments), tint = Color.White, modifier = Modifier.size(18.dp)) }
                            }
                            if (list.isEmpty()) Text(stringResource(R.string.slides_no_comments_slide), color = Color.LightGray, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(4.dp))
                            list.filter { it.comment.parent == null }.forEach { sc -> CommentCard(sc.comment, sc.comment.id == hereComment, act) }
                        }
                    }
                    /** View › Outline: one text box's words, typed into where they are (one step of Undo a visit), its hint while empty. */
                    @Composable fun OutlineField(uid: Int, sh: SlideItem.Shape, first: Boolean) {
                        val start = if (sh.hint) "" else SlidesEdit.lines(sh).joinToString("\n")
                        var v by remember(uid, sh.id) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(start)) }
                        var focused by remember(uid, sh.id) { mutableStateOf(false) }; var stepped by remember(uid, sh.id) { mutableStateOf(false) }
                        LaunchedEffect(start) { if (!focused && v.text != start) v = androidx.compose.ui.text.input.TextFieldValue(start) } // Undo shown
                        // each line at its level, with its bullet or number as the slide shows it (the tablet walk, 18 Oct); only drawn, not typed
                        val paras = remember(sh) { sh.text?.paras?.let(SlidePara::numbered).orEmpty() }
                        val shown = remember(paras) { androidx.compose.ui.text.input.VisualTransformation { t -> outlineLines(t.text, paras) } }
                        androidx.compose.foundation.text.BasicTextField(v, { nv -> v = nv
                                val e = edit ?: return@BasicTextField; val next = e.copy(texts = e.texts + ((uid to sh.id!!) to nv.text.split("\n")))
                                if (stepped) edit = next else { change(next); stepped = true } },
                            textStyle = (if (first) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium).copy(color = Color.White),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White), visualTransformation = shown,
                            modifier = Modifier.fillMaxWidth().padding(start = if (first) 0.dp else 16.dp, top = 4.dp).onFocusChanged { f -> focused = f.isFocused; if (!f.isFocused) stepped = false },
                            decorationBox = { inner -> Box { if (v.text.isEmpty()) Text(if (first) stringResource(R.string.slides_hint_title) else stringResource(R.string.slides_hint_text), color = Color.White.copy(alpha = 0.4f), style = if (first) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium); inner() } })
                    }
                    /**
                     * View › Outline (PowerPoint's): every slide's words in one list, each text box in its place top to bottom, typed into
                     * there (good on a phone); a slide's number opens it in Normal.
                     */
                    @Composable fun Outline(d: Deck) {
                        val ee = edit ?: return
                        LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(20.dp, if (OfficeUi.top) 150.dp else 16.dp, 20.dp, if (OfficeUi.top) 16.dp else 150.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            itemsIndexed(d.slides) { i, s ->
                                val uid = ee.order.getOrNull(i)?.uid ?: return@itemsIndexed
                                Row(Modifier.fillMaxWidth().widthIn(max = 900.dp)) {
                                    Text("${i + 1}", color = if (chosen == i) POWERPOINT_ORANGE else Color.LightGray, style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.width(40.dp).clickable { chosen = i; outline = false })
                                    Column(Modifier.weight(1f)) {
                                        sectionAt(i)?.let { Text(it, color = POWERPOINT_ORANGE, style = MaterialTheme.typography.labelLarge) }
                                        val boxes = s.items.filterIsInstance<SlideItem.Shape>().filter { it.id != null && !it.grouped && (it.text != null || it.hint) && it.id !in s.footerIds }.sortedWith(compareBy({ it.y }, { it.x }))
                                        if (boxes.isEmpty()) Text(stringResource(R.string.slides_no_words), color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
                                        boxes.forEachIndexed { k, sh -> OutlineField(uid, sh, k == 0) }
                                    }
                                }
                            }
                        }
                    }
                    /**
                     * View › Slide sorter (PowerPoint's): every slide small in a grid. A tap chooses it (or lets it go); a slide held and
                     * dragged moves there, with the others chosen; double-tapped, it opens in Normal.
                     */
                    @Composable fun SlideSorter(d: Deck) {
                        val ee = edit ?: return
                        val cells = remember { HashMap<Int, androidx.compose.ui.geometry.Rect>() }
                        var held by remember { mutableStateOf<Int?>(null) }; var over by remember { mutableStateOf<Int?>(null) }
                        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(androidx.compose.foundation.lazy.grid.GridCells.Adaptive(if (wide) 220.dp else 150.dp), Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp, if (OfficeUi.top) 150.dp else 16.dp, 16.dp, if (OfficeUi.top) 16.dp else 150.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(d.slides.size) { i ->
                                val s = d.slides[i]; val uid = ee.order.getOrNull(i)?.uid ?: return@items
                                val on = uid in marked
                                Column(Modifier.onGloballyPositioned { cells[i] = it.boundsInRoot() }.alpha(if (held != null && on) 0.5f else 1f)
                                    .pointerInput(i, uid) {
                                        detectDragGesturesAfterLongPress(onDragStart = { held = i; if (uid !in marked) marked = setOf(uid) },
                                            onDrag = { ch, _ -> ch.consume(); val at = (cells[i]?.topLeft ?: androidx.compose.ui.geometry.Offset.Zero) + ch.position
                                                over = cells.entries.firstOrNull { it.value.contains(at) }?.key },
                                            onDragEnd = {
                                                val to = over; held = null; over = null
                                                val e2 = edit ?: return@detectDragGesturesAfterLongPress
                                                if (to != null && e2.order.getOrNull(to)?.uid !in marked) {
                                                    // the chosen ones, in their order, before the slide dropped on (after it when moving on)
                                                    val moving = e2.order.filter { it.uid in marked }; val rest = e2.order.filter { it.uid !in marked }
                                                    val k = rest.indexOf(e2.order[to]) + if (to > e2.order.indexOfFirst { it.uid in marked }) 1 else 0
                                                    change(e2.copy(order = rest.take(k) + moving + rest.drop(k))); WriterLog.i("Slides: sorter moved ${moving.size}")
                                                }
                                            }, onDragCancel = { held = null; over = null })
                                    }
                                    .pointerInput(i) { detectTapGestures(
                                        onTap = { marked = if (uid in marked) marked - uid else marked + uid; chosen = i },
                                        onDoubleTap = { chosen = i; marked = setOf(uid); sorter = false }) }) {
                                    SlideCanvas(d, s, Modifier.fillMaxWidth().aspectRatio(d.width / d.height).clip(RoundedCornerShape(4.dp))
                                        .border(if (on) 3.dp else 1.dp, if (on) POWERPOINT_ORANGE else if (over == i) Color.White else Color(0xFF555555), RoundedCornerShape(4.dp))
                                        .then(if (s.hidden) Modifier.alpha(0.5f) else Modifier))
                                    Text("${i + 1}${if (s.hidden) " · " + stringResource(R.string.slides_hidden) else ""}${sectionAt(i)?.let { " · $it" }.orEmpty()}", color = if (on) POWERPOINT_ORANGE else Color.LightGray, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    }
                    /** Slide [i] as it is edited: chosen with a tap, its items tapped, moved and sized (the phone's list, the tablet's big slide). */
                    @Composable fun SlideBox(i: Int, s: Slide) {
                        Box(Modifier.fillMaxWidth().aspectRatio(d.width / d.height)
                            // a picture dragged in from another app goes on this slide (in its middle)
                            .dropPictures(editing) { uri -> chosen = i; item = null; putPicture(uri) }
                            // a mouse's right button (a desktop's, asked 16 Oct): what is under it chosen, and its menu; before the taps see it
                            .pointerInput(editing, i, s) {
                                if (!editing) return@pointerInput
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                    if (!currentEvent.buttons.isSecondaryPressed) return@awaitEachGesture
                                    down.consume()
                                    val x = down.position.x / size.width * d.width; val y = down.position.y / size.height * d.height
                                    if (chosen != i) { chosen = i; typing = null }
                                    item = s.items.lastOrNull { it.anim != null && x in it.x..(it.x + it.w) && y in it.y..(it.y + it.h) }?.anim
                                    slideMenu = Triple(false, i, down.position)
                                }
                            }
                            // what is chosen, dragged with a finger: moved, or sized from its corner's handle (one step of Undo); elsewhere the list scrolls
                            .pointerInput(editing, chosen, i, item, more) {
                                if (!editing || chosen != i || item == null) return@pointerInput
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val ee = edit ?: return@awaitEachGesture; val uid = ee.order[i].uid; val id = item ?: return@awaitEachGesture
                                    val a = current(ee, i, id) ?: return@awaitEachGesture
                                    val k = d.width / size.width // points a pixel
                                    val px = down.position.x * k; val py = down.position.y * k
                                    val reach = 28.dp.toPx() * k
                                    val handle = kotlin.math.hypot(px - (a.x + a.w), py - (a.y + a.h)) < reach
                                    if (!handle && !(px in a.x..(a.x + a.w) && py in a.y..(a.y + a.h))) return@awaitEachGesture
                                    down.consume()
                                    undos += ee; if (undos.size > 100) undos.removeAt(0)
                                    // several chosen: all of them moved together (sized: the one whose handle it is)
                                    val others = if (handle) emptyList() else more.mapNotNull { o -> current(ee, i, o)?.let { o to it } }
                                    var x = a.x; var y = a.y; var w = a.w; var h = a.h
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!ch.pressed) break
                                        val dx = (ch.position.x - ch.previousPosition.x) * k; val dy = (ch.position.y - ch.previousPosition.y) * k
                                        if (handle) { w = maxOf(12f, w + dx); h = w * a.h / a.w.coerceAtLeast(1f) } else { x += dx; y += dy }
                                        val now = edit ?: break
                                        // Gridlines on: its corner to the grid, or its middle to the slide's middle when near it (PowerPoint's smart guides)
                                        val step = gridStep()
                                        fun snap(v: Float, size: Float, whole: Float) = if (kotlin.math.abs(v + size / 2 - whole / 2) < step / 2) whole / 2 - size / 2 else Math.round(v / step) * step
                                        if (grid && !handle) edit = SlidesEdit.framed(now, uid, id, snap(x, w, d.width), snap(y, h, d.height), w, h)
                                        else if (grid) edit = SlidesEdit.framed(now, uid, id, x, y, maxOf(step, Math.round((x + w) / step) * step - x), h)
                                        else edit = SlidesEdit.framed(now, uid, id, x, y, w, h)
                                        if (others.isNotEmpty()) edit = others.fold(edit!!) { acc, (o, b) -> SlidesEdit.framed(acc, uid, o, b.x + x - a.x, b.y + y - a.y, b.w, b.h) }
                                        ch.consume()
                                    }
                                    WriterLog.i("Slides: ${if (handle) "sized" else "moved"} by a finger")
                                }
                            }) {
                        SlideCanvas(d, s, hints = editing, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                            .then(if (chosen == i) Modifier.border(3.dp, POWERPOINT_ORANGE, RoundedCornerShape(4.dp)) else Modifier)
                            .pointerInput(editing, chosen, i, s) {
                                detectTapGestures { o ->
                                    if (!editing) { presenting = i; return@detectTapGestures }
                                    if (chosen != i) { chosen = i; typing = null; item = null; return@detectTapGestures }
                                    val x = o.x / size.width * d.width; val y = o.y / size.height * d.height
                                    // what is tapped is chosen (its tools in the ribbon); tapped again, a text box's words are typed, a table's cell
                                    val hit = s.items.lastOrNull { it.anim != null && !it.hidden && x in it.x..(it.x + it.w) && y in it.y..(it.y + it.h) }
                                    val a = hit?.anim?.let { id -> edit?.let { ee -> ee.added[ee.order[i].uid]?.firstOrNull { it.id == id } } }
                                    when {
                                        hit == null -> { item = null; message = context.getString(R.string.slides_tap_thing) }
                                        // Choose several: a tap adds it to what is chosen, or takes it away
                                        several && item != null -> { val id = hit.anim!!; typing = null
                                            if (id == item) { item = more.firstOrNull(); more = more.drop(1) } else more = if (id in more) more - id else more + id }
                                        a?.prst == "table" && item == a.id -> {
                                            val r = ((y - a.y) / (a.h / a.rows)).toInt().coerceIn(0, a.rows - 1); val c = ((x - a.x) / (a.w / a.cols)).toInt().coerceIn(0, a.cols - 1)
                                            val t = a.cells.getOrNull(r * a.cols + c).orEmpty()
                                            cellEdit = (r * a.cols + c) to androidx.compose.ui.text.input.TextFieldValue(t, androidx.compose.ui.text.TextRange(t.length)); tableCell = r * a.cols + c
                                        }
                                        item == hit.anim && typable(hit, a) -> typeInto(hit as SlideItem.Shape)
                                        // a deck's own table tapped again: the cell under the finger is the one the Table tab's rows and columns go round
                                        hit is SlideItem.Table && a == null && item == hit.anim -> {
                                            var r = 0; var acc = hit.y; while (r < hit.rows.lastIndex && y > acc + hit.rows[r]) { acc += hit.rows[r]; r++ }
                                            var cc = 0; var ax = hit.x; while (cc < hit.cols.lastIndex && x > ax + hit.cols[cc]) { ax += hit.cols[cc]; cc++ }
                                            tableCell = r * hit.cols.size + cc; message = context.getString(R.string.slides_row_column, r + 1, cc + 1) }
                                        else -> { item = hit.anim; more = emptyList(); typing = null }
                                    }
                                }
                            })
                        // what is chosen: an outline, and (put on it here) the handle its size is dragged from
                        val sel = if (editing && chosen == i) item?.let { SlidesEdit.box(s, it) } else null
                        sel?.let { a -> Canvas(Modifier.matchParentSize()) {
                            val k = size.width / d.width
                            // the others chosen with it: an outline each
                            more.mapNotNull { SlidesEdit.box(s, it) }.forEach { o -> drawRect(POWERPOINT_ORANGE, androidx.compose.ui.geometry.Offset(o.x * k, o.y * k), androidx.compose.ui.geometry.Size(o.w * k, o.h * k), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx())) }
                            drawRect(POWERPOINT_ORANGE, androidx.compose.ui.geometry.Offset(a.x * k, a.y * k), androidx.compose.ui.geometry.Size(a.w * k, a.h * k), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                            val c = androidx.compose.ui.geometry.Offset((a.x + a.w) * k, (a.y + a.h) * k)
                            drawCircle(Color.White, 9.dp.toPx(), c); drawCircle(POWERPOINT_ORANGE, 7.dp.toPx(), c)
                            // the table's cell being typed in, marked (the tablet walk, 18 Oct)
                            cellEdit?.let { (n, _) -> edit?.let { ee -> ee.added[ee.order[i].uid]?.firstOrNull { it.id == item && it.prst == "table" } }?.let { t ->
                                val cw = t.w / t.cols; val ch = t.h / t.rows
                                val at = androidx.compose.ui.geometry.Offset((t.x + n % t.cols * cw) * k, (t.y + n / t.cols * ch) * k); val sz = androidx.compose.ui.geometry.Size(cw * k, ch * k)
                                drawRect(POWERPOINT_ORANGE.copy(alpha = 0.2f), at, sz); drawRect(POWERPOINT_ORANGE, at, sz, style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f.dp.toPx())) } }
                        } }
                        filming?.takeIf { it.first == i }?.second?.let { v -> androidx.compose.foundation.layout.BoxWithConstraints(Modifier.matchParentSize()) { val k = maxWidth / d.width
                            VideoFrame(v.film, Modifier.offset(k * v.x, k * v.y).size(k * v.w, k * v.h).rotate(v.turn), v.trim) { filming = null } } }
                        // Gridlines: the grid, faint, and the slide's middle lines
                        if (grid && editing) Canvas(Modifier.matchParentSize()) {
                            val k = size.width / d.width; val step = gridStep() * k
                            var gx = step; while (gx < size.width) { drawLine(Color(0x33000000), androidx.compose.ui.geometry.Offset(gx, 0f), androidx.compose.ui.geometry.Offset(gx, size.height), 1f); gx += step }
                            var gy = step; while (gy < size.height) { drawLine(Color(0x33000000), androidx.compose.ui.geometry.Offset(0f, gy), androidx.compose.ui.geometry.Offset(size.width, gy), 1f); gy += step }
                            drawLine(Color(0x99E67E22), androidx.compose.ui.geometry.Offset(size.width / 2, 0f), androidx.compose.ui.geometry.Offset(size.width / 2, size.height), 1.5f)
                            drawLine(Color(0x99E67E22), androidx.compose.ui.geometry.Offset(0f, size.height / 2), androidx.compose.ui.geometry.Offset(size.width, size.height / 2), 1.5f)
                        }
                        // its comments' markers (PowerPoint's): a tap shows the comment
                        if (s.comments.isNotEmpty()) androidx.compose.foundation.layout.BoxWithConstraints(Modifier.matchParentSize()) {
                            s.comments.filter { it.comment.parent == null }.forEach { sc ->
                                Box(Modifier.offset(maxWidth * (sc.x / d.width), maxHeight * (sc.y / d.height)).size(28.dp).clip(RoundedCornerShape(5.dp))
                                    .background(if (sc.comment.id == hereComment && commentsOn) POWERPOINT_ORANGE else Color(0xFFFFB300))
                                    .clickable { if (chosen != i) { chosen = i; typing = null; item = null }; commentsOn = true; hereComment = sc.comment.id }, contentAlignment = Alignment.Center) {
                                    Icon(painterResource(R.drawable.ic_comment), stringResource(R.string.slides_comment_by, sc.comment.author), tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        slideMenu?.takeIf { !it.first && it.second == i }?.let { (_, _, at) -> OfficeMenu(at, { slideMenu = null }) {
                                @Composable fun entry(label: String, icon: Int, action: () -> Unit) = OfficeMenuItem(label, icon) { slideMenu = null; WriterLog.i("Slides: menu › $label"); action() }
                                val hit = item?.let { id -> s.items.lastOrNull { it.anim == id } }
                                val ownWords = typable(hit, edit?.let { ee -> ee.added[ee.order[i].uid]?.firstOrNull { it.id == item } })
                                if (ownWords) entry(stringResource(R.string.slides_edit_text), R.drawable.ic_edit) { typeInto(hit as SlideItem.Shape) }
                                if (item != null) {
                                    entry(stringResource(R.string.common_cut), R.drawable.ic_content_cut) { if (copyItem()) deleteItem() }
                                    entry(stringResource(R.string.common_copy), R.drawable.ic_copy) { copyItem() }
                                }
                                if (clip != null) entry(stringResource(R.string.common_paste), R.drawable.ic_content_paste) { pasteItem() }
                                if (item != null) entry(stringResource(R.string.common_delete), R.drawable.ic_delete) { deleteItem() }
                                if (item == null) {
                                    if (deck?.layouts?.isNotEmpty() == true) entry(stringResource(R.string.slides_new_slide), R.drawable.ic_add) { layouts = true }
                                    entry(stringResource(R.string.slides_duplicate_slide), R.drawable.ic_copy) { duplicateSlide(i) }
                                }
                        } }
                        }
                    }
                    // a tablet editing (asked 16 Oct): the slides in a sidebar, the chosen one big beside it, as PowerPoint's and LibreOffice's
                    if (outline && editing) Outline(d) else if (sorter && editing) SlideSorter(d) else if (wide && editing) Row(Modifier.fillMaxSize()) {
                        LazyColumn(Modifier.width(200.dp).fillMaxHeight().background(Color(0xFF1F1F1F)), contentPadding = PaddingValues(12.dp, if (OfficeUi.top) 150.dp else 12.dp, 12.dp, if (OfficeUi.top) 24.dp else 150.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            itemsIndexed(d.slides) { i, s ->
                                Column {
                                sectionAt(i)?.let { Text(it, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 6.dp)) }
                                Row(verticalAlignment = Alignment.Top) {
                                    Text("${i + 1}", color = if (chosen == i) POWERPOINT_ORANGE else Color.LightGray, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(22.dp))
                                    SlideCanvas(d, s, modifier = Modifier.fillMaxWidth().aspectRatio(d.width / d.height).clip(RoundedCornerShape(3.dp))
                                        .then(if (chosen == i) Modifier.border(2.dp, POWERPOINT_ORANGE, RoundedCornerShape(3.dp)) else Modifier)
                                        .then(if (s.hidden) Modifier.alpha(0.5f) else Modifier)
                                        .clickable { if (chosen != i) { chosen = i; typing = null; item = null } }
                                        .pointerInput(i) { awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                            if (!currentEvent.buttons.isSecondaryPressed) return@awaitEachGesture
                                            down.consume(); chosen = i; typing = null; item = null; slideMenu = Triple(true, i, down.position)
                                        } })
                                    slideMenu?.takeIf { it.first && it.second == i }?.let { (_, _, at) -> OfficeMenu(at, { slideMenu = null }) {
                                            @Composable fun entry(label: String, icon: Int, enabled: Boolean = true, action: () -> Unit) = OfficeMenuItem(label, icon, enabled) { slideMenu = null; WriterLog.i("Slides: slide menu › $label"); action() }
                                            val ee = edit
                                            if (deck?.layouts?.isNotEmpty() == true) entry(stringResource(R.string.slides_new_slide), R.drawable.ic_add) { layouts = true }
                                            entry(stringResource(R.string.slides_duplicate), R.drawable.ic_copy) { duplicateSlide(i) }
                                            if (clip != null) entry(stringResource(R.string.common_paste), R.drawable.ic_content_paste) { pasteItem() }
                                            if (ee != null) {
                                                val uid = ee.order[i].uid
                                                val hidden = ee.hidden[uid] ?: deck?.slides?.getOrNull(ee.order[i].src)?.hidden ?: false
                                                entry(if (hidden) stringResource(R.string.slides_show_slide) else stringResource(R.string.slides_hide_slide), if (hidden) R.drawable.ic_visibility else R.drawable.ic_visibility_off) { change(ee.copy(hidden = ee.hidden + (uid to !hidden))) }
                                                entry(stringResource(R.string.slides_delete_slide), R.drawable.ic_delete, enabled = ee.order.size > 1) { change(ee.copy(order = ee.order.filterIndexed { k, _ -> k != i })); chosen = i.coerceAtMost(ee.order.size - 2) }
                                            }
                                    } }
                                }
                                }
                            }
                        }
                        // the chosen slide as big as the room above the floating ribbon lets it be (and above an on-screen keyboard, the notes pane typed in)
                        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().imePadding().padding(start = 24.dp, end = 24.dp, top = if (OfficeUi.top) 150.dp else 16.dp, bottom = if (OfficeUi.top) 16.dp else 150.dp), contentAlignment = Alignment.TopCenter) {
                            val c = (chosen ?: 0).coerceIn(0, d.slides.lastIndex)
                            // as big as it can be; on a desktop, room kept under it for its notes pane
                            // with little room (an on-screen keyboard up) the slide gives way and the pane keeps its height, at least a readable width
                            val withNotes = OfficeUi.top || notesPage
                            val rule = if (rulers && slideZoom == 1f) 20.dp else 0.dp // View › Ruler's room
                            val slideW = minOf(maxWidth - rule, (maxHeight - rule - if (withNotes) 96.dp else 0.dp).coerceAtLeast(if (OfficeUi.top) 0.dp else 120.dp) * (d.width / d.height))
                            Column(Modifier.width(if (withNotes) maxOf(slideW + rule, minOf(maxWidth, 480.dp)) else slideW + rule), horizontalAlignment = Alignment.CenterHorizontally) {
                                if (slideZoom > 1f) {
                                    // zoomed: the slide in a window of the fitted size, scrolled both ways (rulers only at Fit)
                                    Box(Modifier.width(slideW).height(slideW * (d.height / d.width)).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                                        Box(Modifier.width(slideW * slideZoom)) { SlideBox(c, d.slides[c]) } }
                                } else if (rulers) {
                                    val page = DocPage(d.width, d.height, 0f, 0f, 0f, 0f); val perPt = slideW / d.width
                                    Row { androidx.compose.foundation.layout.Spacer(Modifier.width(rule)); HorizontalRuler(page, perPt, 0.dp, Modifier.width(slideW).height(rule)) }
                                    Row { VerticalRuler(page, perPt, Modifier.width(rule).height(slideW * (d.height / d.width))); Box(Modifier.width(slideW)) { SlideBox(c, d.slides[c]) } }
                                } else Box(Modifier.width(slideW)) { SlideBox(c, d.slides[c]) }
                                if (withNotes) NotesPane(c, d.slides[c]) }
                        }
                        if (commentsOn) CommentsPane(Modifier.width(320.dp).fillMaxHeight().padding(top = if (OfficeUi.top) 150.dp else 16.dp, end = 12.dp, bottom = if (OfficeUi.top) 16.dp else 150.dp))
                    } else {
                    // page by page (asked 17 Oct): a swipe ends with the next or the one before in the middle, however hard it is flung
                    val oneAtATime = remember(list) {
                        val base = androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider(list, androidx.compose.foundation.gestures.snapping.SnapPosition.Center)
                        object : androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider by base {
                            override fun calculateApproachOffset(velocity: Float, decayOffset: Float) = 0f
                        }
                    }
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(oneAtATime), contentPadding = PaddingValues(12.dp, if (OfficeUi.top) 130.dp else 12.dp, 12.dp, if (OfficeUi.top) 24.dp else 120.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        itemsIndexed(d.slides) { i, s ->
                            Column(Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = fit)) {
                                Text("${i + 1}${if (s.hidden) " · " + stringResource(R.string.slides_hidden) else ""}${sectionAt(i)?.let { " · $it" }.orEmpty()}", color = if (chosen == i) POWERPOINT_ORANGE else Color.LightGray, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 4.dp))
                                // viewing: a slide tapped is presented from there; editing: tapped, it is chosen — tapped again on a text box, its words are typed
                                SlideBox(i, s)
                                if (editing && chosen == i && (OfficeUi.top || notesPage)) NotesPane(i, s)
                                else if (notes && s.notes.isNotEmpty()) Text(s.notes, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).background(Color(0xFF3A3A3A), RoundedCornerShape(6.dp)).padding(10.dp))
                            }
                        }
                    }
                    }
                    if (!editing || chosen == null) Surface(color = Color.Black.copy(alpha = 0.75f), contentColor = Color.White, shape = CircleShape,
                        modifier = Modifier.align(Alignment.BottomCenter).bottomBarPadding().padding(bottom = 16.dp)) {
                        Text(if (editing) stringResource(R.string.slides_tap_slide) else stringResource(R.string.slides_slide_of, (at + 1).coerceAtMost(d.slides.size), d.slides.size), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                    }
                    // the chosen slide's ribbon, its tabs as Tables' (asked 16 Oct): what is chosen on it, the slide, Insert, Transitions, Animations
                    val e = edit
                    // a gallery (Font, Colours, Fonts, WordArt…) up: the ribbon slides down under it, as Words' (the user, 18 Oct)
                    val galleryUp = editing && strip != null && e != null && (item != null || strip in setOf(DesignGallery.WordArt, DesignGallery.Colours, DesignGallery.Fonts))
                    androidx.compose.animation.AnimatedVisibility(editing && chosen != null && typing == null && cellEdit == null && (notesTyping == null || OfficeUi.top) && !layouts && !shapes && e != null && !galleryUp,
                        // at the bottom for a finger; at the top while a mouse is in use (or as Settings say), as a PC's PowerPoint
                        Modifier.align(if (OfficeUi.top) Alignment.TopCenter else Alignment.BottomCenter),
                        enter = if (OfficeUi.top) androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn() else SLIDE_IN,
                        exit = if (OfficeUi.top) androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut() else SLIDE_OUT) {
                        val c = (chosen ?: 0).coerceIn(0, (e?.order?.size ?: 1) - 1)
                        val w = islandContentColor()
                        // tabs as Words' (17 Oct): File, Home, Insert, …; what is chosen on the slide last, named by what it is
                        val tabs = listOfNotNull("Selection".takeIf { item != null && !sorter && !outline }, "File", "Home", "Insert", "Draw", "Design", "Transitions", "Animations", "Slide show", "References", "Review", "View", "Item".takeIf { item != null && !sorter && !outline })
                        val shown = ribbonTab.takeIf { it in tabs } ?: "Home"
                        val itemKind = e?.let { e1 ->
                            val a = e1.added[e1.order[c].uid]?.firstOrNull { it.id == item }
                            val it0 = view?.slides?.getOrNull(c)?.items?.lastOrNull { it.anim == item }
                            // several chosen, or a group: PowerPoint's names for the tab's things
                            if (more.isNotEmpty()) stringResource(R.string.slides_arrange) else if (it0?.grouped == true) stringResource(R.string.slides_kind_group) else when (it0) {
                                is SlideItem.Table -> stringResource(R.string.slides_kind_table); is SlideItem.Picture -> if (it0.math != null) stringResource(R.string.slides_equation) else if (it0.media?.let(OfficeVideo::audio) == true) stringResource(R.string.slides_audio) else if (it0.media != null) stringResource(R.string.slides_video) else stringResource(R.string.slides_image); is SlideItem.Chart -> stringResource(R.string.slides_chart)
                                is SlideItem.Shape -> if (it0.shape == null && it0.text != null) stringResource(R.string.slides_text_box) else stringResource(R.string.slides_shape)
                                null -> if (a?.prst == "table") stringResource(R.string.slides_kind_table) else if (a?.image != null) stringResource(R.string.slides_image) else stringResource(R.string.slides_shape)
                            }
                        } ?: stringResource(R.string.slides_shape)
                        // a phone: solid across the bottom, as Excel's ribbon; a tablet: Words' floating island
                        Surface(color = Color.Black, contentColor = w, modifier = if (wide) Modifier.fillMaxWidth().bottomBarPadding().padding(12.dp).clip(MaterialTheme.shapes.extraLarge) else Modifier.fillMaxWidth()) {
                            // Options and Effect take the ribbon's place, ‹ back, as Tables' Borders and Number (17 Oct)
                            val strip = when { shown == "Transitions" && effectOptions -> stringResource(R.string.slides_transition_options); shown == "Animations" && item != null && effectKinds -> stringResource(R.string.slides_effect)
                                shown == "Animations" && item != null && effectOptions -> stringResource(R.string.slides_effect_options); else -> null }
                            androidx.compose.animation.AnimatedContent(strip, label = "strip", transitionSpec = { SLIDE_IN togetherWith SLIDE_OUT }) { st ->
                            if (st != null) Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp).then(if (wide) Modifier else Modifier.bottomBarPadding()), verticalAlignment = Alignment.CenterVertically) {
                                StripHead(st, w) { effectOptions = false; effectKinds = false }
                                RibbonLine(androidx.compose.foundation.rememberScrollState(), Modifier.padding(vertical = 2.dp)) { if (e != null) SlideRibbon(shown, c, e, w) }
                            } else
                            OfficeStripFrame(w, if (wide) Modifier.padding(vertical = 4.dp) else Modifier.bottomBarPadding()) { Column {
                                val tabNames = mapOf("Selection" to stringResource(R.string.slides_tab_selection), "File" to stringResource(R.string.slides_tab_file), "Home" to stringResource(R.string.slides_tab_home),
                                    "Insert" to stringResource(R.string.slides_tab_insert), "Draw" to stringResource(R.string.slides_tab_draw), "Design" to stringResource(R.string.slides_tab_design),
                                    "Transitions" to stringResource(R.string.slides_tab_transitions), "Animations" to stringResource(R.string.slides_tab_animations), "Slide show" to stringResource(R.string.slides_tab_slide_show),
                                    "References" to stringResource(R.string.slides_tab_references), "Review" to stringResource(R.string.slides_tab_review), "View" to stringResource(R.string.slides_tab_view))
                                OfficeTabRow(tabs, shown, w, POWERPOINT_ORANGE, { ribbonTab = it }, contextual = "Item", label = { if (it == "Item") itemKind else tabNames[it] ?: it }) {
                                    // a tablet always has a slide chosen: ✕ lets go of what is chosen on it only
                                    if (!wide || item != null) IconButton(onClick = { if (item != null) item = null else chosen = null }, modifier = Modifier.size(36.dp)) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_close), modifier = Modifier.size(16.dp)) }
                                }
                                // the tab's tools on one sliding line, as Words' and Tables' (only catalogs, as colours or effects, take 2–3 lines)
                                key(shown) {
                                    // one sliding line; on a desktop, rows of whole groups (asked 16 Oct)
                                    RibbonLine(androidx.compose.foundation.rememberScrollState(), Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                                        if (e != null) SlideRibbon(shown, c, e, w)
                                    }
                                }
                            } }
                            }
                        }
                    }
                    // a phone's comments: over the slides, at the top
                    if (editing && commentsOn && !wide) CommentsPane(Modifier.align(Alignment.TopCenter).fillMaxWidth().heightIn(max = 380.dp).padding(8.dp))
                    // New slide: the deck's layouts in the strip's place, each a small slide; the one tapped comes after the chosen slide
                    // Home › Font and Insert › Drop cap: Words' strips, a card tapped shown at once; Cancel puts the words back
                    var stripShown by remember { mutableStateOf(DesignGallery.Font) } // kept while it slides away
                    strip?.let { stripShown = it }
                    androidx.compose.animation.AnimatedVisibility(galleryUp, Modifier.align(Alignment.BottomCenter), enter = SLIDE_IN, exit = SLIDE_OUT) {
                        val fonts = remember(base) { ((base?.slides.orEmpty().flatMap { s -> s.items.filterIsInstance<SlideItem.Shape>().flatMap { sh -> sh.text?.paras.orEmpty().flatMap { pp -> pp.runs.mapNotNull { r -> DocStyles.Plain.fontName(r.font) } } } })
                            .distinct().sorted() + STYLE_FONTS).distinctBy { it.lowercase() } }
                        val now = chosenWords()?.text?.paras?.flatMap { it.runs }?.firstOrNull { it.text.isNotBlank() }?.font?.let(DocStyles.Plain::fontName)
                        fun close() { stripBefore = null; stripPicked = null; strip = null }
                        DesignGalleryStrip(stripShown, stripPicked, schemes = LIST_SCHEMES.filter { it.label != LEGAL_SCHEME }, cancel = { stripBefore?.let { edit = it }; close() }, ok = {
                            val made = edit; stripBefore?.let { b -> edit = b; if (made != null && made != b) change(made) }; close(); WriterLog.i("Slides: ${stripShown.title} chosen")
                        }, pick = { ch ->
                            if (ch is ThemeColors || ch is ThemeFonts) {
                                // on the theme chosen, else the deck's own (a PowerPoint file's theme part; an .odp's styles: its fonts only)
                                val b = stripBefore ?: edit ?: return@DesignGalleryStrip
                                val t0 = b.theme ?: (if (odp) null else (source ?: file)?.let { SlidesThemes.fromFile(it) })?.copy(keep = true)
                                    ?: SlideTheme(file?.nameWithoutExtension ?: "Theme", false, List(12) { 0 }, "Calibri Light", "Calibri", keep = true)
                                val t = if (ch is ThemeColors) t0.copy(colors = t0.colors.take(2) + listOf(ch.dk2, ch.lt2) + ch.accents + listOf(ch.link, ch.followed))
                                    else (ch as ThemeFonts).let { f -> t0.copy(major = f.heading, minor = f.body) }
                                stripBefore = b; stripPicked = ch; edit = b.copy(theme = t)
                                return@DesignGalleryStrip
                            }
                            if (stripShown == DesignGallery.WordArt) {
                                // a look and a bend make the WordArt, as in Words: the one not tapped stays as it was
                                val b = stripBefore ?: edit ?: return@DesignGalleryStrip; val cc = chosen ?: return@DesignGalleryStrip
                                val last = stripPicked as? Pair<*, *>
                                val style = ch as? ArtStyle ?: last?.first as? ArtStyle ?: ART_STYLES[0]
                                val bend = ch as? ArtBend ?: last?.second as? ArtBend ?: ArtBend(null)
                                stripBefore = b; stripPicked = style to bend
                                wordArt(b, cc, style, bend)?.let { (made, id) -> edit = made; item = id }
                                return@DesignGalleryStrip
                            }
                            val b = stripBefore ?: edit ?: return@DesignGalleryStrip; val cc = chosen ?: return@DesignGalleryStrip; val id = item ?: return@DesignGalleryStrip
                            val k = b.order[cc].uid to id; val l = b.looks[k] ?: SlidesEdit.Look()
                            val size = wordsSize(chosenWords())
                            stripBefore = b; stripPicked = ch
                            edit = if (ch == DropKind.Dropped) dropped(b, cc, id, dropLines) ?: b else b.copy(looks = b.looks + (k to when (ch) {
                                is FontChoice -> l.copy(font = ch.name)
                                is ListScheme -> l.copy(scheme = LIST_SCHEMES.indexOf(ch), list = null)
                                DropKind.Raised -> l.copy(raise = size * dropLines)
                                else -> l.copy(raise = 0f) // None
                            }))
                        }, more = if (stripShown == DesignGallery.DropCap) { { stripBefore?.let { edit = it }; close(); dropOptions = true } } else null,
                            glow = !odp, fonts = fonts, now = now, drops = listOf(DropKind.None, DropKind.Dropped, DropKind.Raised), dropLines = dropLines, dropLinesChange = { n ->
                                dropLines = n
                                // the letter tapped shown again at its new height
                                val b = stripBefore ?: return@DesignGalleryStrip; val cc = chosen ?: return@DesignGalleryStrip; val id = item ?: return@DesignGalleryStrip
                                val k = b.order[cc].uid to id
                                if (stripPicked == DropKind.Raised) edit = b.copy(looks = b.looks + (k to (b.looks[k] ?: SlidesEdit.Look()).copy(raise = SlidesEdit.slide(base ?: return@DesignGalleryStrip, b, b.order[cc]).items.lastOrNull { it.anim == id }.let { wordsSize(it as? SlideItem.Shape) } * n)))
                                if (stripPicked == DropKind.Dropped) dropped(b, cc, id, n)?.let { edit = it }
                            })
                    }
                    equating?.let { (id, start) ->
                        EquationDialog(start, { equating = null }) { m ->
                            equating = null
                            val (path, ew, eh) = mathPicture(m) ?: return@EquationDialog
                            val ee = edit ?: return@EquationDialog; val cc = chosen ?: return@EquationDialog; val d = deck ?: return@EquationDialog
                            val mine = id?.let { i -> ee.added[ee.order[cc].uid]?.firstOrNull { it.id == i } }
                            val was = id?.let { i -> view?.slides?.getOrNull(cc)?.items?.lastOrNull { it.anim == i } }
                            when {
                                // put on here: drawn again, as tall as before (its width as the new one's)
                                mine != null -> alter { it.copy(image = path, ext = "png", math = m, w = mine.h * ew / eh) }
                                // the deck's own: in its place, put on anew (the old one gone)
                                was != null -> { deleteItem(); val e2 = edit ?: return@EquationDialog
                                    put(SlidesEdit.Added(e2.newId(e2.order[cc].uid, taken(e2, cc)), was.x, was.y, was.h * ew / eh, was.h, image = path, ext = "png", math = m)) }
                                else -> put(SlidesEdit.Added(ee.newId(ee.order[cc].uid, taken(ee, cc)), (d.width - ew) / 2, (d.height - eh) / 2, ew, eh, image = path, ext = "png", math = m))
                            }
                            WriterLog.i("Slides: an equation put on")
                        }
                    }
                    if (symbols) SymbolDialog({ symbols = false }) { symbols = false; putWords(it) }
                    reusing?.let { other ->
                        val picked = remember(other) { androidx.compose.runtime.mutableStateListOf<Int>() }
                        androidx.compose.material3.AlertDialog(onDismissRequest = { reusing = null }, title = { Text(stringResource(R.string.slides_reuse_slides)) },
                            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.slides_reuse_hint, (chosen ?: 0) + 1), style = MaterialTheme.typography.bodySmall)
                                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(androidx.compose.foundation.lazy.grid.GridCells.Adaptive(150.dp), Modifier.heightIn(max = 420.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(other.slides.size) { i -> Column(Modifier.clip(RoundedCornerShape(4.dp)).clickable { if (i in picked) picked.remove(i) else picked.add(i) }) {
                                        SlideCanvas(other, other.slides[i], Modifier.fillMaxWidth().then(if (i in picked) Modifier.border(3.dp, POWERPOINT_ORANGE) else Modifier))
                                        Text("${i + 1}${if (i in picked) "  ✓" else ""}", style = MaterialTheme.typography.labelMedium, color = if (i in picked) POWERPOINT_ORANGE else Color.Unspecified) } }
                                } } },
                            confirmButton = { androidx.compose.material3.TextButton(enabled = picked.isNotEmpty(), onClick = {
                                val ee = edit; val d = deck; reusing = null
                                if (ee != null && d != null) {
                                    val at = chosen ?: 0
                                    val (made, skipped) = SlidesReuse.reused(ee, d, other, picked.sorted(), at, File(context.cacheDir, "slide-pictures"))
                                    change(made); chosen = at + 1
                                    message = context.resources.getQuantityString(R.plurals.slides_n_put_in, picked.size, picked.size) + if (skipped > 0) " (" + context.resources.getQuantityString(R.plurals.slides_things_left_out, skipped, skipped) + ")" else ""
                                    WriterLog.i("Slides: ${picked.size} slides reused, $skipped left out")
                                } }) { Text(stringResource(R.string.slides_insert)) } },
                            dismissButton = { androidx.compose.material3.TextButton(onClick = { reusing = null }) { Text(stringResource(R.string.common_cancel)) } })
                    }
                    spelling?.let { (queue, offered, ignored) ->
                        val sp = queue.first()
                        LaunchedEffect(sp) { chosen = sp.slide; item = sp.sel }
                        fun next(ign: Set<String> = ignored) { val rest = queue.drop(1).filter { it.word !in ign }
                            spelling = if (rest.isEmpty()) null.also { message = context.getString(R.string.slides_spelling_complete) } else Triple(rest, offered, ign) }
                        val lines = view?.slides?.getOrNull(sp.slide)?.items?.lastOrNull { it.anim == sp.sel }?.let { it as? SlideItem.Shape }?.let { SlidesEdit.lines(it) }.orEmpty()
                        SpellingDialog(sp.word, stringResource(R.string.slides_slide_line, sp.slide + 1, lines.getOrNull(sp.line).orEmpty().replace(LINE_BREAK, ' ')), offered[sp.word].orEmpty(), editing, change = { c ->
                            val ee = edit; val line = lines.getOrNull(sp.line)
                            if (ee != null && line != null && line.regionMatches(sp.at.first, sp.word, 0, sp.word.length)) {
                                val uid = ee.order[sp.slide].uid
                                change(ee.copy(texts = ee.texts + ((uid to sp.id) to lines.toMutableList().also { it[sp.line] = line.replaceRange(sp.at, c) })))
                                // on to the next, this line's later words moved by the change
                                val shift = c.length - sp.word.length
                                val rest = queue.drop(1).map { q -> if (q.slide == sp.slide && q.sel == sp.sel && q.line == sp.line && q.at.first > sp.at.last) q.copy(at = q.at.first + shift..q.at.last + shift) else q }.filter { it.word !in ignored }
                                spelling = if (rest.isEmpty()) null.also { message = context.getString(R.string.slides_spelling_complete) } else Triple(rest, offered, ignored)
                            } else next() }, ignore = { next() }, ignoreAll = { next(ignored + sp.word) }, close = { spelling = null })
                    }
                    // only what was changed on the page, each paragraph keeping its own rest (its level's indent): the walk, 18 Oct
                    paragraphing?.let { q -> ParagraphDialog(q, { paragraphing = null }) { n -> paragraphing = null; val parts = paraChanged(q, n)
                        // a second visit adds to the first: what it set before stays, this visit's changes over it
                        if (parts.isNotEmpty()) restyle { l -> val before = if (l.para == null) emptySet() else l.paraParts.ifEmpty { PARA_PARTS.toSet() }
                            l.copy(para = paraOn(l.para ?: n, n, parts), paraParts = before + parts) }
                        WriterLog.i("Slides: paragraph page, $parts") } }
                    // Slide size: PowerPoint's question — the things made as big as the new size allows, or fitted into it
                    resizing?.let { (sw, sh) -> androidx.compose.material3.AlertDialog(onDismissRequest = { resizing = null }, title = { Text(stringResource(R.string.slides_slide_size)) },
                        text = { Text(stringResource(R.string.slides_resize_ask)) },
                        confirmButton = { Row {
                            androidx.compose.material3.TextButton(onClick = { resizing = null; edit?.let { change(it.copy(size = SlidesSize.Size(sw, sh, true))) } }) { Text(stringResource(R.string.slides_maximize)) }
                            androidx.compose.material3.TextButton(onClick = { resizing = null; edit?.let { change(it.copy(size = SlidesSize.Size(sw, sh, false))) } }) { Text(stringResource(R.string.slides_ensure_fit)) } } },
                        dismissButton = { androidx.compose.material3.TextButton(onClick = { resizing = null }) { Text(stringResource(R.string.common_cancel)) } }) }
                    sizeAfterSave?.let { z -> androidx.compose.material3.AlertDialog(onDismissRequest = { sizeAfterSave = null }, title = { Text(stringResource(R.string.slides_slide_size)) },
                        text = { Text(stringResource(R.string.slides_resize_save_first)) },
                        confirmButton = { androidx.compose.material3.TextButton(onClick = { sizeAfterSave = null; save(rebase = true) { resizing = z } }) { Text(stringResource(R.string.slides_save_go_on)) } },
                        dismissButton = { androidx.compose.material3.TextButton(onClick = { sizeAfterSave = null }) { Text(stringResource(R.string.common_cancel)) } }) }
                    if (slideSizing) SlideSizeDialog(base?.width ?: SlidesSize.WIDE_W, base?.height ?: SlidesSize.HEIGHT, { slideSizing = false }) { sw, sh -> slideSizing = false
                        val e = edit ?: return@SlideSizeDialog; val d = deck ?: return@SlideSizeDialog
                        if (Math.abs(d.width - sw) < 0.5f && Math.abs(d.height - sh) < 0.5f) change(e.copy(size = null))
                        else if (e.copy(theme = null, size = null) == SlidesEdit.start(d)) resizing = sw to sh
                        else if (e == saved) save(rebase = true) { resizing = sw to sh } else sizeAfterSave = sw to sh }
                    if (dating) DateDialog({ dating = false }) { dating = false; putWords(it) }
                    val arranged = item?.let { id -> edit?.let { ee -> chosen?.let { cc -> current(ee, cc, id) } } }
                    if (rotating && arranged != null) RotateDialog(arranged.rotation, { rotating = false }) { deg -> rotating = false; turn { it.copy(deg = (deg + 360f) % 360f) } }
                    if (shaping && arranged is SlideItem.Shape) arranged.shape?.let { sp ->
                        ShapeOptionsDialog(ShapeOptions(sp.fill, sp.line, sp.lineWidth, arranged.w, arranged.h, arranged.rotation, arranged.alt.orEmpty(), sp.dash, sp.shadow), { shaping = false }) { o ->
                            shaping = false
                            // one step of Undo: its looks, its size (its top left kept), its angle
                            val ee = edit ?: return@ShapeOptionsDialog; val cc = chosen ?: return@ShapeOptionsDialog; val id = item ?: return@ShapeOptionsDialog; val k = ee.order[cc].uid to id
                            val l = ee.looks[k] ?: SlidesEdit.Look()
                            var next = ee.copy(looks = ee.looks + (k to l.copy(fill = if (o.fill != sp.fill) o.fill ?: SlidesEdit.NONE else l.fill, // only what was changed (a gradient shows as no fill)
                                line = if (o.line != sp.line) o.line ?: SlidesEdit.NONE else l.line, lineWidth = o.lineWidth.takeIf { it != sp.lineWidth } ?: l.lineWidth,
                                alt = o.alt.takeIf { it != arranged.alt.orEmpty() } ?: l.alt,
                                dash = if (o.dash != sp.dash) o.dash.orEmpty() else l.dash, shadow = o.shadow.takeIf { it != sp.shadow } ?: l.shadow)))
                            if (o.widthPt != arranged.w || o.heightPt != arranged.h) next = SlidesEdit.framed(next, k.first, id, arranged.x, arranged.y, o.widthPt, o.heightPt)
                            if (o.degrees != arranged.rotation) next = next.copy(turns = next.turns + (k to (next.turns[k] ?: SlidesEdit.Turn(arranged.rotation, arranged.flipH, arranged.flipV)).copy(deg = o.degrees)))
                            change(next); WriterLog.i("Slides: shape options")
                        }
                    }
                    if (alting && arranged != null) {
                        var typed by remember { mutableStateOf(arranged.alt.orEmpty()) }
                        androidx.compose.material3.AlertDialog(onDismissRequest = { alting = false }, title = { Text(stringResource(R.string.slides_alt_text)) },
                            text = { androidx.compose.material3.OutlinedTextField(typed, { typed = it }, label = { Text(stringResource(R.string.slides_alt_hint)) }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth()) },
                            confirmButton = { androidx.compose.material3.TextButton(onClick = { alting = false; restyle { it.copy(alt = typed.trim()) } }) { Text(stringResource(R.string.common_ok)) } },
                            dismissButton = { androidx.compose.material3.TextButton(onClick = { alting = false }) { Text(stringResource(R.string.common_cancel)) } })
                    }
                    cropping?.let { bmp ->
                        // Words' crop (Photos' corners, straight cuts on); the cut kept as a crop of the whole picture, so Reset brings it back
                        val c0 = (arranged as? SlideItem.Picture)?.crop
                        val start = c0?.let { c -> DocumentQuad(ScanPoint(c[0], c[1]), ScanPoint(1 - c[2], c[1]), ScanPoint(1 - c[2], 1 - c[3]), ScanPoint(c[0], 1 - c[3])) } ?: DocumentQuad.fullFrame()
                        androidx.compose.ui.window.Dialog({ cropping = null }, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                                CornerEditor(bmp, start, DocumentQuad.fullFrame(), stringResource(R.string.slides_reset), straightOption = true, done = { q ->
                                    cropping = null
                                    // shortcut: corners moved freely (not straight) are cut to the box round them
                                    val xs = q.points.map { it.x.coerceIn(0f, 1f) }; val ys = q.points.map { it.y.coerceIn(0f, 1f) }
                                    recrop(listOf(xs.min(), ys.min(), 1 - xs.max(), 1 - ys.max()))
                                }, cancel = { cropping = null })
                            }
                        }
                    }
                    if (selectionPane) e?.let { e1 -> val c1 = chosen ?: return@let; val sl = view?.slides?.getOrNull(c1) ?: return@let; val uid = e1.order[c1].uid
                        SelectionPane(sl, item, { selectionPane = false }, { id -> item = id; typing = null },
                            look = { id, f -> change(e1.copy(looks = e1.looks + ((uid to id) to f(e1.looks[uid to id] ?: SlidesEdit.Look())))); WriterLog.i("Slides: selection pane, $id") },
                            move = { id, how -> item = id; restack(how) })
                    }
                    if (animPane) e?.let { e1 -> val c1 = chosen ?: return@let; val uid = e1.order[c1].uid; val sl = view?.slides?.getOrNull(c1) ?: return@let
                        val list = e1.anims[uid] ?: SlidesEdit.flat(sl.steps)
                        AnimationPane(list, sl, item, { animPane = false }, { id -> item = id }) { to -> change(e1.copy(anims = e1.anims + (uid to to))); WriterLog.i("Slides: animation pane, ${to.size} effects") }
                    }
                    if (sizing && arranged != null) SizeAndPosition(arranged, { sizing = false }) { x, y, ww, hh -> sizing = false; reframe { listOf(x, y, ww, hh) } }
                    boxLayout?.let { t -> TextBoxLayout(t, { boxLayout = null }) { m, wrap, n, gap -> boxLayout = null; restyle { it.copy(insets = m, wrap = wrap, cols = n, colGap = gap) }; WriterLog.i("Slides: a box's layout ($n columns)") } }
                    if (drawing) base?.let { d -> view?.slides?.getOrNull(chosen ?: -1)?.let { sl ->
                        DrawDialog(d.width, { drawing = false }, slide = Triple(d.width, d.height) { c -> Slides.draw(c, d, sl, 1f) }) { png, ww, hh, at, _ ->
                            drawing = false
                            val f = File(File(context.cacheDir, "slide-pictures").apply { mkdirs() }, "${System.nanoTime()}.png").apply { writeBytes(png) }
                            val ee = edit ?: return@DrawDialog; val cc = chosen ?: return@DrawDialog
                            put(SlidesEdit.Added(ee.newId(ee.order[cc].uid, taken(ee, cc)), at?.first ?: 0f, at?.second ?: 0f, ww, hh, image = f.path, ext = "png"))
                            WriterLog.i("Slides: a drawing put on")
                        }
                    } }
                    redrawing?.let { (p, strokes) ->
                        DrawDialog(p.w, { redrawing = null }, boxAspect = p.h / p.w.coerceAtLeast(1f), initial = strokes) { png, _, _, _, _ ->
                            redrawing = null
                            repicture(File(File(context.cacheDir, "slide-pictures").apply { mkdirs() }, "${System.nanoTime()}.png").apply { writeBytes(png) })
                        }
                    }
                    if (sourcesOpen) SourcesDialog(sources, remember(view, sources) { cited() }, { sourcesOpen = false }, { src -> if (src == null) sourceNew = true else sourceEdit = src },
                        { sourcesOpen = false; pickSources.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                        { sourcesOpen = false; importSources { context.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                            ?: throw IllegalArgumentException(context.getString(R.string.slides_nothing_copied)) } },
                        { val topic = view?.slides?.firstOrNull()?.items?.filterIsInstance<SlideItem.Shape>()?.firstNotNullOfOrNull { it.text?.paras?.firstOrNull()?.runs?.joinToString("") { r -> r.text }?.takeIf { t -> t.isNotBlank() } }.orEmpty()
                            val ask = "Using the tetra-sources skill, make a Tetra sources file (JSON) for my presentation \"${file?.nameWithoutExtension.orEmpty()}\"" + (if (topic.isNotEmpty()) " — its topic: $topic" else "") +
                                ".\nOnly real, checked references with their DOIs. I will paste your JSON into Tetra." + (if (sources.isNotEmpty()) "\nIt already cites (leave these out):\n" + sources.joinToString("\n") { "- " + Apa.entry(it).joinToString("") { r -> r.text } } else "")
                            context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, ask), context.getString(R.string.slides_ask_claude))) }
                    ) { gone -> resource(sources.filter { it.tag != gone.tag }, citeStyle) }
                    if (sourceNew || sourceEdit != null) SourceDialog(sourceEdit, { sourceNew = false; sourceEdit = null }) { src ->
                        sourceNew = false; sourceEdit = null
                        val tagged = if (src.tag.isBlank()) src.copy(tag = Citations.tagFor(if (odp) OfficeKind.Odt else OfficeKind.Docx, src, sources.map { it.tag }, citeStyle)) else src
                        resource(if (sources.any { it.tag == tagged.tag }) sources.map { if (it.tag == tagged.tag) tagged else it } else sources + tagged, citeStyle)
                    }
                    if (citing) CitationDialog(sources, pages = false, multiple = false, { citing = false }, { sourceNew = true }) { list, _, _ ->
                        citing = false; list.firstOrNull()?.let { putWords(cite(it, citeStyle)); WriterLog.i("Slides: a citation put in") } }
                    if (sectioning) edit?.let { ee -> base?.let { d -> chosen?.let { cc ->
                        val uid = ee.order[cc].uid; val now = ee.sectionsNow(d); val here = now.firstOrNull { it.second == uid }
                        var typed by remember { mutableStateOf(here?.first ?: context.getString(R.string.slides_section_n, now.size + 1)) }
                        fun set(l: List<Pair<String, Int>>) { sectioning = false; change(ee.copy(sections = l)); WriterLog.i("Slides: sections ${l.size}") }
                        androidx.compose.material3.AlertDialog(onDismissRequest = { sectioning = false }, title = { Text(if (here == null) stringResource(R.string.slides_new_section_from, cc + 1) else stringResource(R.string.slides_section)) },
                            text = { androidx.compose.material3.OutlinedTextField(typed, { typed = it.replace("\n", " ") }, label = { Text(stringResource(R.string.slides_its_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
                            confirmButton = { androidx.compose.material3.TextButton(enabled = typed.isNotBlank(), onClick = {
                                set(if (here == null) now + (typed.trim() to uid) else now.map { if (it.second == uid) typed.trim() to uid else it }) }) { Text(if (here == null) stringResource(R.string.common_add) else stringResource(R.string.common_rename)) } },
                            dismissButton = { Row {
                                if (here != null) androidx.compose.material3.TextButton(onClick = { set(now - here) }) { Text(stringResource(R.string.slides_remove_section)) }
                                androidx.compose.material3.TextButton(onClick = { sectioning = false }) { Text(stringResource(R.string.common_cancel)) } } })
                    } } }
                    commentAsk?.let { (how, of) ->
                        val prefs = context.getSharedPreferences("writer", android.content.Context.MODE_PRIVATE) // the name Words' comments use
                        NewCommentDialog(if (how == 'e') of?.author.orEmpty() else remember { prefs.getString("author", "") ?: "" }, { commentAsk = null },
                            title = when (how) { 'e' -> stringResource(R.string.slides_edit_comment); 'r' -> stringResource(R.string.slides_reply_to, of?.author.orEmpty().ifBlank { context.getString(R.string.slides_comment_word) }); else -> stringResource(R.string.slides_comment) },
                            start = if (how == 'e') of?.text.orEmpty() else "", confirm = if (how == 'e') stringResource(R.string.common_done) else stringResource(R.string.common_add)) { author, text ->
                            commentAsk = null
                            if (how != 'e') prefs.edit().putString("author", author).apply()
                            val now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).withNano(0).toString()
                            when (how) {
                                'e' -> recomment { l -> l.map { if (it.comment.id == of?.id) it.copy(comment = it.comment.copy(text = text)) else it } }
                                'r' -> recomment { l -> val p = l.firstOrNull { it.comment.id == of?.id } ?: return@recomment l
                                    // after the comment's last reply, at its place
                                    val at = l.indexOfLast { it.comment.id == p.comment.id || it.comment.parent == p.comment.id } + 1
                                    l.take(at) + SlideComment(DocComment("t${System.nanoTime()}", author, now, text, parent = p.comment.id), p.x, p.y) + l.drop(at) }
                                else -> { val sl = view?.slides?.getOrNull(chosen ?: -1)
                                    // by the chosen item's top right corner (PowerPoint's), else the slide's top left
                                    val it0 = item?.let { id -> sl?.items?.lastOrNull { it.anim == id } }
                                    val id = "t${System.nanoTime()}"
                                    recomment { l -> l + SlideComment(DocComment(id, author, now, text), it0?.let { minOf(it.x + it.w, (deck?.width ?: 720f) - 20f) } ?: 10f, it0?.y ?: 10f) }
                                    commentsOn = true; hereComment = id }
                            }
                            WriterLog.i("Slides: comment ${how}")
                        }
                    }
                    if (footing) {
                        val ee = edit; val cc = chosen
                        if (ee == null || cc == null) footing = false else {
                            // what the slide shows now: its choices here, else its own date, footer and number
                            val sl = view?.slides?.getOrNull(cc)
                            val start = ee.footers[ee.order[cc].uid] ?: sl?.let { v ->
                                fun words(k: String) = v.items.filterIsInstance<SlideItem.Shape>().firstOrNull { v.footerIds[it.id] == k }?.text?.paras?.joinToString(" ") { p -> p.runs.joinToString("") { it.text } }
                                val date = words("dt")
                                SlidesEdit.Footers(date != null, date?.takeIf { it != SlidesEdit.today() }, words("sldNum") != null, words("ftr"))
                            } ?: SlidesEdit.Footers()
                            FootersDialog(start, { footing = false }) { f, all, notOnTitle ->
                                footing = false
                                val base = view
                                fun mine(i: Int, e: SlidesEdit.Entry) = if (notOnTitle && base?.slides?.getOrNull(i)?.titleSlide == true) SlidesEdit.Footers() else f
                                change(ee.copy(footers = ee.footers + if (all) ee.order.mapIndexed { i, e -> e.uid to mine(i, e) } else listOf(ee.order[cc].uid to mine(cc, ee.order[cc]))))
                                WriterLog.i("Slides: header and footer on ${if (all) "all slides" else "a slide"}")
                            }
                        }
                    }
                    if (linking) {
                        val box = chosenWords()
                        // a picture or shape chosen (no words of its own): the object itself linked, its tap in a show going there (asked 17 Oct: maps that zoom in)
                        val thing = if (box == null) item?.let { id -> view?.slides?.getOrNull(chosen ?: -1)?.items?.lastOrNull { it.anim == id } } else null
                        val now = thing?.link?.let { if (it == "back") "#back" else it } ?: box?.text?.paras?.flatMap { it.runs }?.firstNotNullOfOrNull { it.link }
                        val ee = edit
                        // a slide's link shown as its place in the deck
                        val shownAt = now?.let { l -> (if (l.startsWith("slide:")) ee?.order?.indexOfFirst { it.uid == l.removePrefix("slide:").toIntOrNull() } else view?.slides?.indexOfFirst { it.part == l && l.isNotEmpty() })?.takeIf { it >= 0 }?.let { "#$it" } } ?: now
                        val names = view?.slides.orEmpty().map { sl -> sl.items.filterIsInstance<SlideItem.Shape>().firstNotNullOfOrNull { sh -> sh.text?.paras?.joinToString(" ") { p -> p.runs.joinToString("") { it.text } }?.trim()?.takeIf { it.isNotEmpty() && !sh.hint } }?.take(40) ?: stringResource(R.string.slides_slide_word) }
                        LinkDialog(box == null && thing == null, if (now == "#back") now else shownAt, null, now != null, { linking = false },
                            { linking = false; restyle { if (thing != null) it.copy(objectLink = "") else it.copy(link = "") } }, names, back = thing != null && !odp) { text, to ->
                            linking = false
                            val target = if (to == "#back") "back" else to.takeIf { it.startsWith("#") }?.drop(1)?.toIntOrNull()?.let { i -> ee?.order?.getOrNull(i)?.let { "slide:${it.uid}" } } ?: to
                            if (thing != null) restyle { it.copy(objectLink = target) }
                            else if (box != null) restyle { it.copy(link = target) }
                            else { val d0 = deck ?: return@LinkDialog; val e0 = edit ?: return@LinkDialog; val cc = chosen ?: return@LinkDialog
                                val a = SlidesEdit.Added(e0.newId(e0.order[cc].uid, taken(e0, cc)), d0.width * 0.2f, d0.height * 0.42f, d0.width * 0.6f, 40f, prst = SlidesEdit.TEXT_BOX)
                                val k = e0.order[cc].uid to a.id
                                change(e0.copy(added = e0.added + (k.first to e0.added[k.first].orEmpty() + a), texts = e0.texts + (k to listOf(text)), looks = e0.looks + (k to SlidesEdit.Look(link = target))))
                                item = a.id }
                            WriterLog.i("Slides: a link put on")
                        }
                    }
                    charting?.let { (id, start) ->
                        ChartDialog(start, id != null, { charting = null }) { ch ->
                            charting = null
                            if (id?.startsWith("deck:") == true) { val cc = chosen ?: return@ChartDialog
                                // shortcut: put on anew in Words' chart look (the file's own styling of it not kept); upgrade by writing the numbers into its own chart part
                                val was = view?.slides?.getOrNull(cc)?.items?.lastOrNull { it.anim == id.removePrefix("deck:") } ?: return@ChartDialog
                                deleteItem(); val e2 = edit ?: return@ChartDialog
                                put(SlidesEdit.Added(e2.newId(e2.order[cc].uid, taken(e2, cc)), was.x, was.y, was.w, was.h, prst = "chart", chart = ch))
                                WriterLog.i("Slides: a deck's chart's data changed") }
                            else if (id != null) { alter { it.copy(chart = ch) }; WriterLog.i("Slides: a chart's data changed") }
                            else { val d = deck ?: return@ChartDialog; val ee = edit ?: return@ChartDialog; val cc = chosen ?: return@ChartDialog
                                put(SlidesEdit.Added(ee.newId(ee.order[cc].uid, taken(ee, cc)), d.width * 0.15f, d.height * 0.18f, d.width * 0.7f, d.height * 0.7f, prst = "chart", chart = ch))
                                WriterLog.i("Slides: a chart put on") }
                        }
                    }
                    if (dropOptions) chosenWords()?.let { box ->
                        val p = box.text?.paras?.firstOrNull { pp -> pp.runs.any { it.text.isNotBlank() } }
                        val k = p?.runs?.indexOfFirst { it.text.isNotEmpty() } ?: -1
                        val size = wordsSize(box)
                        val letter = p?.runs?.getOrNull(k)?.takeIf { SlidesEdit.isRaised(p.runs, k) != null }
                        DropCapDialog(letter?.let { DropCap(lines = Math.round((it.sizePt ?: size) / size).coerceIn(2, 10), raised = true) }, true, letter?.font?.let(DocStyles.Plain::fontName), letter?.color,
                            { dropOptions = false }, raised = true) { d, font, color ->
                            dropOptions = false
                            val ee = edit; val cc = chosen; val id = item
                            if (d != null && !d.raised) { if (ee != null && cc != null && id != null) dropped(ee, cc, id, d.lines, font, color?.or(0xFF000000.toInt()), d.distance)?.let { change(it); WriterLog.i("Slides: a drop cap dropped") } }
                            else restyle { it.copy(raise = d?.let { dc -> size * dc.lines } ?: 0f, raiseFont = font, raiseColor = color?.or(0xFF000000.toInt())) }
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(editing && layouts && e != null, Modifier.align(Alignment.BottomCenter), enter = SLIDE_IN, exit = SLIDE_OUT) {
                        val full = deck
                        Surface(color = Color.Black, contentColor = islandContentColor(), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.bottomBarPadding().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                StripHead(if (relaying) stringResource(R.string.slides_layout) else stringResource(R.string.slides_new_slide), islandContentColor()) { layouts = false; relaying = false }
                                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(end = 12.dp)) {
                                    if (full != null) itemsIndexed(full.layouts) { l, layout ->
                                        Column(Modifier.width(132.dp).clickable {
                                            val ee = edit ?: return@clickable
                                            if (relaying) { val uid = ee.order.getOrNull(chosen ?: -1)?.uid ?: return@clickable
                                                change(ee.copy(relayout = ee.relayout + (uid to l))); layouts = false; relaying = false; WriterLog.i("Slides: layout ${layout.name}"); return@clickable }
                                            val c = (chosen ?: -1) + 1
                                            change(ee.copy(order = ee.order.toMutableList().apply { add(c.coerceIn(0, size), SlidesEdit.Entry(-1, ee.next(), l)) }))
                                            chosen = c; layouts = false; WriterLog.i("Slides: a new slide (${layout.name})")
                                        }) {
                                            Text(layout.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 4.dp))
                                            SlideCanvas(full, layout.preview, Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)), hints = true)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // Shape: Words' panel (asked 16 Oct: more than seven) — Word's shapes in their groups, Shape Styles and colours; the one tapped goes in the slide's middle
                    if (editing && shapes && e != null) ShapesDialog(odt = true, dismiss = { shapes = false }) { kind, fill, line ->
                        shapes = false
                        val dd = deck; val ee = edit; val c = chosen
                        if (dd != null && ee != null && c != null) {
                            // Words' proportions for it, its longer side three tenths of the slide's height
                            val (sw, sh) = shapeSize(kind); val k = dd.height * 0.3f / maxOf(sw, sh); val bw = sw * k; val bh = maxOf(sh * k, 1f)
                            val uid = ee.order[c].uid; val id = ee.newId(uid, taken(ee, c))
                            put(SlidesEdit.Added(id, (dd.width - bw) / 2, (dd.height - bh) / 2, bw, bh, prst = kind, fill = fill ?: 0xFF4472C4.toInt()))
                            // its fill and outline as chosen (none for either kept as none)
                            edit?.let { now -> edit = now.copy(looks = now.looks + ((uid to id) to SlidesEdit.Look(fill = fill?.or(0xFF000000.toInt()) ?: SlidesEdit.NONE, line = line?.or(0xFF000000.toInt()) ?: SlidesEdit.NONE))) }
                            WriterLog.i("Slides: a shape put on a slide ($kind)")
                        }
                    }
                    // a text box's words: a line a paragraph, ✕ leaves them, ✓ puts them in (one step of Undo)
                    androidx.compose.animation.AnimatedVisibility(typing != null || cellEdit != null || (notesTyping != null && !OfficeUi.top), Modifier.align(Alignment.BottomCenter).imePadding(), enter = SLIDE_IN, exit = SLIDE_OUT) {
                        val cell = cellEdit
                        // a slide's notes: ✕ leaves them, ✓ puts them in (one step of Undo)
                        val nt = notesTyping?.takeIf { !OfficeUi.top }
                        if (nt != null) Surface(color = Color.Black, contentColor = islandContentColor(), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.bottomBarPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { notesTyping = null }) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.slides_leave_them)) }
                                val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                                LaunchedEffect(nt.first) { focus.requestFocus() }
                                androidx.compose.material3.OutlinedTextField(nt.second, { notesTyping = nt.first to it }, minLines = 3, modifier = Modifier.weight(1f).heightIn(max = 260.dp).focusRequester(focus),
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White), label = { Text(stringResource(R.string.slides_speaker_notes)) })
                                IconButton(onClick = {
                                    edit?.let { ee -> change(ee.copy(notes = ee.notes + (nt.first to nt.second.text.trimEnd()))); WriterLog.i("Slides: notes changed") }
                                    notesTyping = null
                                }) { Icon(painterResource(R.drawable.ic_check), contentDescription = stringResource(R.string.slides_put_them_in)) }
                            }
                        }
                        if (cell != null) Surface(color = Color.Black, contentColor = islandContentColor(), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.bottomBarPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { cellEdit = null }) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.slides_leave_it)) }
                                // the keyboard at once: the box opened to be typed in
                                val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                                LaunchedEffect(cell.first) { focus.requestFocus() }
                                androidx.compose.material3.OutlinedTextField(cell.second, { cellEdit = cell.first to it }, singleLine = true, modifier = Modifier.weight(1f).focusRequester(focus),
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White), label = { Text(stringResource(R.string.slides_cell_words)) })
                                IconButton(onClick = { alter { a -> a.copy(cells = a.cells.toMutableList().also { it[cell.first] = cell.second.text }) }; cellEdit = null }) {
                                    Icon(painterResource(R.drawable.ic_check), contentDescription = stringResource(R.string.slides_put_them_in)) }
                            }
                        }
                        val t = typing
                        fun putTyping() {
                            val ee = edit; val c = chosen
                            if (t != null && ee != null && c != null) {
                                val uid = ee.order[c].uid
                                change(ee.copy(texts = ee.texts + ((uid to t.first.id!!) to t.second.text.split("\n"))))
                                WriterLog.i("Slides: a text box changed")
                            }
                            typing = null
                        }
                        if (cell == null && nt == null)
                        Surface(color = Color.Black, contentColor = islandContentColor(), modifier = Modifier.fillMaxWidth()) { Column {
                            // Words' bar on the keyboard ([KeyboardBar]): its looks on the box, as Home's; ⋯ puts the words in and brings the ribbon back
                            val ee = edit; val c = chosen
                            val box = if (ee != null && c != null) base?.let { d -> SlidesEdit.slide(d, ee, ee.order[c]) }?.items?.lastOrNull { it.anim == item && item != null } as? SlideItem.Shape else null
                            val r = box?.text?.paras?.flatMap { it.runs }?.firstOrNull { it.text.isNotBlank() }
                            val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                            KeyboardBar(islandContentColor(), r?.bold == true, r?.italic == true, r?.underline == true,
                                { restyle { it.copy(bold = r?.bold != true) } }, { restyle { it.copy(italic = r?.italic != true) } }, { restyle { it.copy(underline = r?.underline != true) } },
                                { col -> restyle { it.copy(highlight = col?.toArgb() ?: SlidesEdit.NONE) } },
                                { said?.invoke() ?: run { message = context.getString(R.string.slides_no_voice_typing) } }, ::putTyping, { keyboard?.hide() })
                            Row(Modifier.bottomBarPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { typing = null }) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.slides_leave_it)) }
                                // a new line in the same paragraph (Enter makes a new paragraph), as Words' Line break
                                IconButton(onClick = { putWords(LINE_BREAK.toString()) }) { Icon(painterResource(R.drawable.ic_wrap_text), contentDescription = stringResource(R.string.slides_line_break)) }
                                val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                                LaunchedEffect(t?.first) { if (t != null) focus.requestFocus() }
                                if (t != null) androidx.compose.material3.OutlinedTextField(t.second, { typing = t.first to it }, modifier = Modifier.weight(1f).heightIn(max = 220.dp).focusRequester(focus),
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White), label = { Text(stringResource(R.string.slides_words_lines)) })
                                IconButton(onClick = ::putTyping) { Icon(painterResource(R.drawable.ic_check), contentDescription = stringResource(R.string.slides_put_them_in)) }
                            }
                        } }
                    }
                    OfficeMessage(message, top = 12.dp) { message = null }
                }
            }
        }
    }
    if (themesPage) ThemesDialog(edit?.theme, { themesPage = false }) { t -> themesPage = false; edit?.let { change(it.copy(theme = t)) } }
    if (leaving) androidx.compose.material3.AlertDialog(onDismissRequest = { leaving = false },
        title = { Text(stringResource(R.string.slides_save_changes)) },
        text = { Text(if (undos.isEmpty()) stringResource(R.string.slides_changes_not_saved, file?.name ?: stringResource(R.string.slides_this_presentation)) else pluralStringResource(R.plurals.slides_n_changes_not_saved, undos.size, undos.size, file?.name ?: stringResource(R.string.slides_this_presentation))) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { leaving = false; save { back() } }) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { Row {
            androidx.compose.material3.TextButton(onClick = { leaving = false; file?.let { slideDraft(context, it).delete() }; WriterLog.i("Slides: left without saving"); back() }) { Text(stringResource(R.string.slides_dont_save)) }
            androidx.compose.material3.TextButton(onClick = { leaving = false }) { Text(stringResource(R.string.common_cancel)) }
        } })
    val d = view
    presenting?.let { start -> if (d != null) Present(d, start, notes, jump = { l ->
        // a link's slide: one linked here (its uid), or the deck's own part a file's link names
        (if (l.startsWith("slide:")) edit?.order?.indexOfFirst { it.uid == l.removePrefix("slide:").toIntOrNull() } else d.slides.indexOfFirst { it.part == l })?.takeIf { it >= 0 }
    }, loop = edit?.loop ?: deck?.loop ?: false, noAnimation = edit?.noAnimation ?: deck?.noAnimation ?: false, manual = edit?.manual ?: deck?.manual ?: false, rehearse = if (rehearsing) { times -> rehearsed = times } else null, keepInk = if (editing) { m -> inked = m } else null, film = ::filmOf) { presenting = null; rehearsing = false } }
    // PowerPoint's "Keep your ink annotations?": kept, each slide's strokes a drawing on it (Words' picture with its strokes: Edit drawing draws on)
    inked?.let { m ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { inked = null }, title = { Text(stringResource(R.string.slides_keep_ink)) },
            text = { Text(pluralStringResource(R.plurals.slides_drew_on, m.size, m.size)) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = {
                inked = null
                val e = edit ?: return@TextButton; val d = view ?: return@TextButton
                var added = e.added
                val aspect = d.height / d.width
                for ((i, strokes) in m) {
                    val uid = e.order.getOrNull(i)?.uid ?: continue
                    // the show's pen: red, a 250th of the slide's width
                    val drawn = strokes.filter { it.isNotEmpty() }.map { st -> InkStroke(st.map { androidx.compose.ui.geometry.Offset(it.x, it.y * aspect) }, 0xFFE53935.toInt(), d.width / 250f / 72f * 25.4f, false) }
                    val (cut, box) = InkPicture.cut(drawn, aspect, d.width)
                    val ww = box.width * d.width
                    val png = runCatching { InkPicture.png(cut, box.height / box.width, ww, (ww * 4).toInt().coerceIn(64, 2400)) }.getOrNull() ?: continue
                    val f = File(File(context.cacheDir, "slide-pictures").apply { mkdirs() }, "${System.nanoTime()}.png").apply { writeBytes(png) }
                    added = added + (uid to (added[uid].orEmpty() + SlidesEdit.Added(e.copy(added = added).newId(uid), box.left * d.width, box.top * d.width, ww, box.height * d.width, image = f.path, ext = "png")))
                }
                change(e.copy(added = added)); WriterLog.i("Slides: show ink kept on ${m.size}")
            }) { Text(stringResource(R.string.common_keep)) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { inked = null }) { Text(stringResource(R.string.common_discard)) } })
    }
    if (savingAs || renaming) file?.let { f ->
        var typed by remember { mutableStateOf(if (savingAs) "${f.nameWithoutExtension} (2)" else f.nameWithoutExtension) }
        var kind by remember { mutableStateOf(f.extension.lowercase()) } // Save as: .pptx or .odp (the other format: the deck rebuilt in it)
        var into by remember { mutableStateOf(f.parentFile) } // Save as: the folder chosen (Rename stays beside)
        fun target() = typed.trim().removeSuffix(".$kind").takeIf { it.isNotEmpty() && '/' !in it }?.let { File(if (savingAs) into else f.parentFile, "$it.$kind") }
        androidx.compose.material3.AlertDialog(onDismissRequest = { savingAs = false; renaming = false }, title = { Text(if (savingAs) stringResource(R.string.slides_save_as) else stringResource(R.string.common_rename)) },
            text = { val fields = @Composable {
                androidx.compose.material3.OutlinedTextField(typed, { typed = it }, singleLine = true, label = { Text(stringResource(R.string.slides_file_name)) }, suffix = { Text(".${if (savingAs) kind else f.extension}") })
                if (savingAs) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (k in listOf("pptx", "odp")) androidx.compose.material3.FilterChip(kind == k, { kind = k }, { Text(if (k == "pptx") "PowerPoint" else "LibreOffice") })
                }
                if (savingAs && kind != f.extension.lowercase()) Text(stringResource(R.string.slides_save_as_rebuilt), style = MaterialTheme.typography.bodySmall)
            }
                if (savingAs) SaveAsWhere(into, { into = it }) { fields() } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { fields() } },
            confirmButton = { androidx.compose.material3.TextButton(onClick = {
                val t = target() ?: run { message = context.getString(R.string.slides_not_file_name); return@TextButton }
                if (t == f) { savingAs = false; renaming = false; return@TextButton }
                if (t.exists()) { message = context.getString(R.string.slides_already_there, t.name); return@TextButton }
                if (savingAs && kind != f.extension.lowercase()) { savingAs = false
                    // the other format: the deck as it shows saved into a copy, rebuilt from that in the other format, and gone on with
                    val temp = File(context.cacheDir, "as-${System.nanoTime()}.${f.extension}")
                    save(to = temp, copy = true, then = { scope.launch {
                        val got = withContext(Dispatchers.IO) { runCatching {
                            val pics = File(context.cacheDir, "as-pictures-${System.nanoTime()}")
                            try { SlidesReuse.convert(temp, t, context.assets.open("blank.$kind").use { it.readBytes() }, pics) to Slides.read(t) } finally { temp.delete(); pics.deleteRecursively() }
                        }.onFailure { WriterLog.e("Slides: save as .$kind", it); t.delete() }.getOrNull() }
                        if (got == null) { message = context.getString(R.string.slides_could_not_save_as, kind); return@launch }
                        val (skipped, nd) = got
                        file = t; deck = nd; edit = SlidesEdit.start(nd); saved = edit; source = null; undos.clear(); redos.clear(); chosen = chosen?.coerceAtMost(nd.slides.lastIndex)
                        message = context.getString(R.string.slides_saved_as, t.name) + if (skipped > 0) " (" + context.resources.getQuantityString(R.plurals.slides_things_not_brought, skipped, skipped) + ")" else ""; WriterLog.i("Slides: saved as ${t.name}, $skipped left out")
                    } })
                } else if (savingAs) { savingAs = false; save(to = t); WriterLog.i("Slides: saved as ${t.name}") }
                else { renaming = false
                    // the file, its recovery draft and its versions under the new name (as Words renames)
                    scope.launch { val ok = withContext(Dispatchers.IO) { runCatching {
                        val draft = slideDraft(context, f); val versions = versionsDir(context, f)
                        check(f.renameTo(t)) { "Could not rename ${f.name}." }
                        if (draft.isFile) draft.renameTo(slideDraft(context, t)); if (versions.isDirectory) versions.renameTo(versionsDir(context, t))
                    }.onFailure { WriterLog.e("Slides: rename", it) }.isSuccess }
                        if (ok) { file = t; message = context.getString(R.string.slides_renamed, t.name) } else message = context.getString(R.string.slides_could_not_rename) } }
            }) { Text(if (savingAs) stringResource(R.string.common_save) else stringResource(R.string.common_rename)) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { savingAs = false; renaming = false }) { Text(stringResource(R.string.common_cancel)) } })
    }
    // PowerPoint's Inspect Document, the part that matters before sharing: the speaker notes and the comments taken out
    trimming?.let { (was, len) ->
        var range by remember { mutableStateOf(was[0].toFloat()..(len - was[1]).toFloat()) }
        fun time(ms: Float) = "%d:%04.1f".format((ms / 60000).toInt(), ms % 60000 / 1000)
        androidx.compose.material3.AlertDialog(onDismissRequest = { trimming = null }, title = { Text(stringResource(R.string.slides_trim)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.RangeSlider(range, { range = it }, valueRange = 0f..len.toFloat())
                Row { Text(stringResource(R.string.slides_trim_start, time(range.start)), Modifier.weight(1f)); Text(stringResource(R.string.slides_trim_end, time(range.endInclusive))) }
                Text(stringResource(R.string.slides_trim_note), style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { trimming = null
                // to the tenth of a second the dialog shows
                fun tenth(ms: Float) = Math.round(ms / 100) * 100
                restyle { it.copy(trim = listOf(tenth(range.start), (len - tenth(range.endInclusive)).coerceAtLeast(0))) }; WriterLog.i("Slides: film trimmed ${range.start.toInt()}–${range.endInclusive.toInt()} of $len") }) { Text(stringResource(R.string.common_ok)) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { trimming = null }) { Text(stringResource(R.string.common_cancel)) } })
    }
    if (inspecting) edit?.let { e ->
        val v = view?.slides.orEmpty()
        val notes = v.count { it.notes.isNotBlank() }; val comments = v.sumOf { it.comments.size }
        androidx.compose.material3.AlertDialog(onDismissRequest = { inspecting = false }, title = { Text(stringResource(R.string.slides_inspect)) },
            text = { Text(if (notes + comments == 0) stringResource(R.string.slides_inspect_nothing)
                else stringResource(R.string.slides_inspect_ask, pluralStringResource(R.plurals.slides_inspect_notes, notes, notes), pluralStringResource(R.plurals.slides_inspect_comments, comments, comments))) },
            confirmButton = { if (notes + comments > 0) androidx.compose.material3.TextButton(onClick = { inspecting = false
                change(e.copy(notes = e.notes + e.order.associate { it.uid to "" }, comments = e.comments + e.order.associate { it.uid to emptyList<SlideComment>() }))
                message = context.getString(R.string.slides_inspect_done); WriterLog.i("Slides: inspected, $notes notes and $comments comments out") }) { Text(stringResource(R.string.slides_take_them_out)) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { inspecting = false }) { Text(if (notes + comments == 0) stringResource(R.string.common_close) else stringResource(R.string.common_cancel)) } })
    }
    rehearsed?.let { times ->
        val total = times.values.sum()
        androidx.compose.material3.AlertDialog(onDismissRequest = { rehearsed = null }, title = { Text(stringResource(R.string.slides_rehearse)) },
            text = { Text(stringResource(R.string.slides_rehearsed, total / 60_000, total / 1000 % 60)) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = {
                rehearsed = null
                val e = edit ?: return@TextButton
                val t = times.mapNotNull { (i, ms) -> e.order.getOrNull(i)?.uid?.let { uid -> uid to ((e.transitions[uid] ?: d?.slides?.getOrNull(i)?.transition ?: SlideTransition("", null, 0)).copy(after = ms.toInt().coerceAtLeast(1000))) } }
                change(e.copy(transitions = e.transitions + t)); WriterLog.i("Slides: timings kept for ${t.size}")
            }) { Text(stringResource(R.string.common_keep)) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { rehearsed = null }) { Text(stringResource(R.string.slides_dont_keep)) } })
    }
    if (officeSettings) OfficeSettingsDialog { officeSettings = false }
    if (showVersions) file?.let { f -> VersionsDialog(f, stringResource(R.string.slides_versions_note), { showVersions = false }) { v -> showVersions = false; openVersion(v) } }
    if (about) androidx.compose.material3.AlertDialog(onDismissRequest = { about = false },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { about = false }) { Text(stringResource(R.string.common_close)) } },
        title = { Text(file?.name ?: stringResource(R.string.slides_presentation)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (odp) stringResource(R.string.slides_kind_odp) else stringResource(R.string.slides_kind_pptx))
                file?.let { Text("${"%.1f".format(it.length() / 1024.0 / 1024.0)} MB · " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.lastModified()))) }
                view?.let { d ->
                    val hidden = d.slides.count { it.hidden }
                    Text(pluralStringResource(R.plurals.slides_n_slides, d.slides.size, d.slides.size) + (if (hidden > 0) ", " + stringResource(R.string.slides_n_hidden, hidden) else "") +
                        " · ${if (kotlin.math.abs(d.width / d.height - 16f / 9) < 0.05f) "16:9" else if (kotlin.math.abs(d.width / d.height - 4f / 3) < 0.05f) "4:3" else "%.1f × %.1f cm".format(d.width / 72 * 2.54, d.height / 72 * 2.54)}")
                    val notes = d.slides.count { it.notes.isNotBlank() }
                    if (notes > 0) Text(pluralStringResource(R.plurals.slides_notes_on, notes, notes))
                }
            }
        })
}

/** One slide, as wide as it is given, its own shape. */
@Composable
private fun SlideCanvas(deck: Deck, slide: Slide, modifier: Modifier, hints: Boolean = false) {
    // a slide with a GIF is drawn again every frame while it shows, so it plays
    val moves = remember(slide) { Slides.moves(slide) }
    val frame by androidx.compose.runtime.produceState(0L, moves) { if (moves) while (true) androidx.compose.runtime.withFrameNanos { value = it } }
    Canvas(modifier.aspectRatio(deck.width / deck.height).background(Color.White)) {
        frame // read: a new frame, a new drawing
        drawIntoCanvas { c -> Slides.draw(c.nativeCanvas, deck, slide, size.width / deck.width, hints) }
    }
}

/**
 * Present: the slides full screen and sideways on black, one at a time; a tap or a swipe to the left goes on (the next
 * click's effects, else the next slide with its transition), a swipe the other way back (the slide before, shown whole);
 * two taps bring out the tools, three turn the screen black and back; the shown slides only (hidden ones skipped); back
 * to the list with Back.
 */
@Composable
private fun Present(deck: Deck, start: Int, notes: Boolean, jump: (String) -> Int? = { null },
    /** Slide show › Loop until Esc: after the last, the first again */ loop: Boolean = false,
    /** Set Up Slide Show: each slide whole at once (no animation); its "after" times not used (on a click only) */ noAnimation: Boolean = false, manual: Boolean = false,
    /** Slide show › Rehearse timings: how long each slide (its index) was shown, given when the show ends */ rehearse: ((Map<Int, Long>) -> Unit)? = null,
    /** the pen's ink at the end (each slide's strokes, 0–1 of its width and height), to keep or not */ keepInk: ((Map<Int, List<List<androidx.compose.ui.geometry.Offset>>>) -> Unit)? = null,
    /** a video's film to play (a file, or a web address) */ film: suspend (String) -> String? = { null }, close0: () -> Unit) {
    val context = LocalContext.current
    val shown = remember(deck) { deck.slides.indices.filter { !deck.slides[it].hidden || it == start } }
    var page by remember { mutableStateOf(shown.indexOf(start).coerceAtLeast(0)) }
    // the slide's clicks done; the one playing (and when it began); the slide it came from, and when, for its transition
    var step by remember { mutableStateOf(0) }
    var playing by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var from by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    val now by androidx.compose.runtime.produceState(System.nanoTime()) { while (true) androidx.compose.runtime.withFrameNanos { value = System.nanoTime() } }
    fun slide(p: Int) = deck.slides[shown[p]]
    fun stepsOf(p: Int): List<SlideStep> = if (noAnimation) emptyList() else slide(p).steps
    fun ms(t: Long) = (now - t) / 1_000_000
    // Rehearse timings: the time on each slide, counted from when it came (a slide shown twice, both times)
    val spent = remember { HashMap<Int, Long>() }
    var since by remember { mutableStateOf(System.nanoTime()) }
    fun mark() { if (rehearse != null) { spent[shown[page]] = (spent[shown[page]] ?: 0L) + (System.nanoTime() - since) / 1_000_000; since = System.nanoTime() } }
    val ink = remember { androidx.compose.runtime.mutableStateMapOf<Int, List<List<androidx.compose.ui.geometry.Offset>>>() }
    // links followed: where each came from and the object tapped there (to go back out of it); the zoom running (from which slide, the object, in or out and when)
    val visited = remember { ArrayList<Pair<Int, FloatArray>>() }
    var filmPlaying by remember { mutableStateOf<Pair<Int, OfficeVideo.Spot>?>(null) } // a video tapped, playing in its frame on its slide
    LaunchedEffect(page) { if (filmPlaying?.first != page) filmPlaying = null }
    val scope = rememberCoroutineScope()
    var zooming by remember { mutableStateOf<Triple<Int, FloatArray, Pair<Boolean, Long>>?>(null) }
    fun close() { mark(); rehearse?.invoke(spent); ink.filterValues { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { m -> keepInk?.invoke(m.mapKeys { shown[it.key] }) }; close0() }
    // a group of effects lasts as long as its latest one
    fun length(g: SlideStep) = g.effects.maxOfOrNull { it.delay + it.ms } ?: 0
    /** [v]'s film or sound played in its frame on the slide shown now (a tap on it, or its media call). */
    fun playFilm(v: SlideItem, wait: Int = 0) { val p = page
        scope.launch { kotlinx.coroutines.delay(wait.toLong()); film(v.media!!)?.let { f -> if (page == p) filmPlaying = p to OfficeVideo.Spot(f, v.x, v.y, v.w, v.h, v.rotation, v.trim) } }; WriterLog.i("Slides: a video played") }
    fun play(g: Int) { playing = g to System.nanoTime(); WriterLog.i("Slides: click ${g + 1}")
        stepsOf(page).getOrNull(g)?.effects?.filter { it.kind == "play" }?.forEach { e -> slide(page).items.lastOrNull { it.anim == e.target && it.media != null }?.let { playFilm(it, e.delay) } } }
    // the groups that start by themselves: one after the other, at the slide's start and after a click
    LaunchedEffect(page, step, playing, from) {
        val steps = stepsOf(page)
        playing?.let { (g, t) -> val left = length(steps[g]) - (System.nanoTime() - t) / 1_000_000; if (left > 0) kotlinx.coroutines.delay(left); step = g + 1; playing = null; return@LaunchedEffect }
        from?.let { (_, t) -> val left = (slide(page).transition?.ms ?: 0) - (System.nanoTime() - t) / 1_000_000; if (left > 0) kotlinx.coroutines.delay(left); from = null; return@LaunchedEffect }
        if (step < steps.size && steps[step].auto) play(step)
    }
    fun next() {
        val steps = stepsOf(page)
        when {
            playing != null -> { step = playing!!.first + 1; playing = null } // a click while it plays: it ends at once, as PowerPoint's
            from != null -> from = null
            step < steps.size -> play(step)
            page < shown.lastIndex -> { mark(); from = page to System.nanoTime(); page++; step = 0; playing = null; WriterLog.i("Slides: slide ${page + 1}${slide(page).transition?.let { ", ${it.kind}" } ?: ""}") }
            loop && rehearse == null -> { from = page to System.nanoTime(); page = 0; step = 0; playing = null; WriterLog.i("Slides: from the first again (loop)") }
            else -> close()
        }
    }
    fun back() { if (page > 0) { mark(); page--; step = stepsOf(page).size; playing = null; from = null } }
    /** PowerPoint's number + Enter: straight to that slide (as numbered in the show). */
    fun goTo(p: Int) { if (p in shown.indices && p != page) { mark(); from = page to System.nanoTime(); page = p; step = 0; playing = null } }
    /** A tap at [x], [y] in a [w] × [h] window on words with a link (Insert › Link, a file's own): a web page opened, a slide gone to. True: it was one. */
    fun follow(x: Float, y: Float, w: Float, h: Float): Boolean {
        val k = minOf(w / deck.width, h / deck.height); val px = (x - (w - deck.width * k) / 2) / k; val py = (y - (h - deck.height * k) / 2) / k
        // a video tapped: played in its frame, as PowerPoint plays one on the slide
        slide(page).items.lastOrNull { it.media != null && px in it.x..it.x + it.w && py in it.y..it.y + it.h }?.let { v -> playFilm(v); return true }
        // an object linked (a picture, a shape: asked 17 Oct, maps that zoom in), else words with a link in a box
        val obj = slide(page).items.lastOrNull { it.link != null && px in it.x..it.x + it.w && py in it.y..it.y + it.h }
        val box = if (obj != null) null else slide(page).items.filterIsInstance<SlideItem.Shape>().lastOrNull { px in it.x..it.x + it.w && py in it.y..it.y + it.h && it.text?.paras?.any { p -> p.runs.any { r -> r.link != null } } == true }
        val link = obj?.link ?: box?.text?.paras?.flatMap { it.runs }?.firstNotNullOfOrNull { it.link } ?: return false
        val tapped = (obj ?: box)!!.let { floatArrayOf(it.x, it.y, it.w, it.h) }
        // the slide it came from (PowerPoint's Last Slide Viewed): back out of the object it was entered by
        if (link == "back") { visited.removeLastOrNull()?.let { (p, r) -> mark(); zooming = Triple(page, r, false to System.nanoTime()); page = p; step = stepsOf(p).size; playing = null; from = null
            WriterLog.i("Slides: back to slide ${shown[p] + 1}") }; return true }
        jump(link)?.let { i -> shown.indexOf(i).takeIf { it >= 0 }?.let { mark(); visited += page to tapped; zooming = Triple(page, tapped, true to System.nanoTime()); page = it; step = 0; playing = null; from = null
            WriterLog.i("Slides: link to slide ${i + 1}") }; return true }
        if (Regex("^[a-zA-Z][a-zA-Z+.-]*:").containsMatchIn(link)) { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link))) }; WriterLog.i("Slides: link opened"); return true }
        return false
    }
    // a slide that goes on by itself: its time once its effects are done (and its transition in), then the next
    // shortcut: PowerPoint counts the time from the slide's start; here it runs after its last effect, the same when it has none
    // Pause (the presenter's notes, asked 17 Oct): a moment aside — slides that go on by themselves wait, the teleprompter stops
    var paused by remember { mutableStateOf(false) }
    LaunchedEffect(page, step, playing, from, paused) {
        val after = slide(page).transition?.after?.takeIf { !manual } ?: return@LaunchedEffect
        if (rehearse != null || paused || playing != null || from != null || step < stepsOf(page).size) return@LaunchedEffect
        kotlinx.coroutines.delay(after.toLong()); next()
    }
    /**
     * An item's look now: hidden until its entrance's click, then appearing, fading in or flying in from its side; an
     * exit the other way (disappearing, fading out, flying out to its side), hidden after it.
     */
    fun look(sl: Slide, p: Int): (SlideItem) -> FloatArray? {
        val mine = HashMap<String, MutableList<Pair<Int, SlideEffect>>>()
        val steps: List<SlideStep> = if (noAnimation) emptyList() else sl.steps
        steps.forEachIndexed { g, st -> st.effects.forEach { e -> mine.getOrPut(e.target) { ArrayList() } += g to e } }
        val done = if (p == page) step else steps.size
        val play = if (p == page) playing else null
        val shown = floatArrayOf(1f, 0f, 0f)
        // an emphasis turns or scales about the middle of what it names (a group's, for a group)
        val middle = HashMap<String, Pair<Float, Float>>()
        fun about(id: String) = middle.getOrPut(id) { SlidesEdit.box(sl, id)?.let { it.x + it.w / 2 to it.y + it.h / 2 } ?: (0f to 0f) }
        return { item ->
            val list = item.anim?.let(mine::get)
            if (list == null) shown else {
                val (cx, cy) = about(item.anim!!)
                fun emph(scale: Float, deg: Float) = floatArrayOf(1f, 0f, 0f, scale, deg, cx, cy)
                // before its first effect: hidden if that brings it in, else there
                val first = list.first().second
                var r: FloatArray? = if (first.exit || first.emphasis) shown else null
                for ((g, e) in list) {
                    // done: in, gone, or (grown) bigger
                    if (g < done) { r = if (e.exit) null else if (e.kind == "grow") emph(1.5f, 0f) else shown; continue }
                    if (play?.first == g) {
                        val t = (ms(play.second) - e.delay).toFloat() / e.ms.coerceAtLeast(1)
                        if (t >= 0f) {
                            val k = t.coerceAtMost(1f)
                            // an entrance eases in to where it stands; an exit eases out from there
                            val away = if (e.exit) k * k else (1 - k) * (1 - k)
                            val ease = 1 - (1 - k) * (1 - k)
                            r = when (e.kind) {
                                "pulse" -> emph(1f + 0.1f * kotlin.math.sin(Math.PI * k).toFloat(), 0f)
                                "spin" -> emph(1f, 360f * ease)
                                "grow" -> emph(1f + 0.5f * ease, 0f)
                                "appear" -> if (e.exit) null else shown
                                "play" -> shown
                                "zoom" -> (1 - away).let { s -> floatArrayOf(s, 0f, 0f, s.coerceAtLeast(0.01f), 0f, cx, cy) }
                                "wipe" -> {
                                    val f = if (e.exit) 1 - k else k; val (l, t, rr, b) = listOf(item.x - 4, item.y - 4, item.x + item.w + 4, item.y + item.h + 4)
                                    when (e.from) { 'u' -> floatArrayOf(1f, 0f, 0f, 1f, 0f, cx, cy, l, t, rr, t + (b - t) * f); 'l' -> floatArrayOf(1f, 0f, 0f, 1f, 0f, cx, cy, l, t, l + (rr - l) * f, b)
                                        'r' -> floatArrayOf(1f, 0f, 0f, 1f, 0f, cx, cy, rr - (rr - l) * f, t, rr, b); else -> floatArrayOf(1f, 0f, 0f, 1f, 0f, cx, cy, l, b - (b - t) * f, rr, b) }
                                }
                                "fly" -> when (e.from) {
                                    'l' -> floatArrayOf(1f, -(item.x + item.w) * away, 0f); 'r' -> floatArrayOf(1f, (deck.width - item.x) * away, 0f)
                                    'u' -> floatArrayOf(1f, 0f, -(item.y + item.h) * away); else -> floatArrayOf(1f, 0f, (deck.height - item.y) * away)
                                }
                                else -> floatArrayOf(if (e.exit) 1 - k else k, 0f, 0f)
                            }
                        }
                    }
                    break
                }
                r
            }
        }
    }
    /** The slide now, in a [w]×[h] box: the one before under it while its transition plays. */
    fun render(c: android.graphics.Canvas, w: Float, h: Float) {
        val k = minOf(w / deck.width, h / deck.height)
        val sw = deck.width * k; val sh = deck.height * k
        c.save(); c.translate((w - sw) / 2, (h - sh) / 2); c.clipRect(0f, 0f, sw, sh)
        val cur = slide(page)
        // a link followed from an object: the slide grows out of it (zoom in), or back into it (zoom out), as PowerPoint's Zoom does
        zooming?.let { (old, r, how) -> val (into, t0) = how
            val t = (ms(t0) / 600f).coerceIn(0f, 1f); val e = 1 - (1 - t) * (1 - t)
            if (t < 1f) {
                fun framed(sl: Slide, p: Int, a: Float, alpha: Float) { // [sl] drawn in the box between the object's (a = 0) and the whole slide's (a = 1)
                    c.save(); c.translate(r[0] * k * (1 - a), r[1] * k * (1 - a)); c.scale(r[2] / deck.width * (1 - a) + a, r[3] / deck.height * (1 - a) + a)
                    c.saveLayerAlpha(0f, 0f, sw, sh, (alpha * 255).toInt().coerceIn(0, 255)); Slides.draw(c, deck, sl, k, look = look(sl, p)); c.restore(); c.restore() }
                if (into) { Slides.draw(c, deck, slide(old), k); framed(cur, page, e, 0.35f + 0.65f * e) }
                else { Slides.draw(c, deck, cur, k, look = look(cur, page)); framed(slide(old), old, 1 - e, 1 - e) }
                c.restore(); return
            }
        }
        val tr = cur.transition?.takeIf { it.kind.isNotEmpty() } // going on by itself only: no transition to draw
        val f = from
        val t = if (f != null && tr != null) (ms(f.second).toFloat() / tr.ms.coerceAtLeast(1)).coerceIn(0f, 1f) else 1f
        val e = 1 - (1 - t) * (1 - t)
        if (f != null && tr != null && t < 1f) {
            val old = slide(f.first)
            fun drawOld(dx: Float = 0f, dy: Float = 0f) { c.save(); c.translate(dx, dy); Slides.draw(c, deck, old, k, look = look(old, f.first)); c.restore() }
            fun drawNew(dx: Float = 0f, dy: Float = 0f) { c.save(); c.translate(dx, dy); Slides.draw(c, deck, cur, k, look = look(cur, page)); c.restore() }
            // push and gallery: both move; cover: the new one over; pull: the old one away; wipe: the new one uncovered; the rest fade
            val (ux, uy) = when (tr.dir) { "r" -> 1f to 0f; "u" -> 0f to -1f; "d" -> 0f to 1f; else -> -1f to 0f }
            when (tr.kind) {
                "push", "gallery", "conveyor", "pan" -> { drawOld(ux * sw * e, uy * sh * e); drawNew(-ux * sw * (1 - e), -uy * sh * (1 - e)) }
                "cover" -> { drawOld(); drawNew(-ux * sw * (1 - e), -uy * sh * (1 - e)) }
                "pull" -> { drawNew(); drawOld(ux * sw * e, uy * sh * e) }
                "wipe" -> { drawOld(); c.save()
                    if (ux != 0f) { if (ux < 0) c.clipRect(sw * (1 - e), 0f, sw, sh) else c.clipRect(0f, 0f, sw * e, sh) } else { if (uy < 0) c.clipRect(0f, sh * (1 - e), sw, sh) else c.clipRect(0f, 0f, sw, sh * e) }
                    drawNew(); c.restore() }
                "cut" -> drawNew()
                else -> { drawOld(); c.saveLayerAlpha(0f, 0f, sw, sh, (e * 255).toInt()); drawNew(); c.restore() }
            }
        } else Slides.draw(c, deck, cur, k, look = look(cur, page))
        c.restore()
    }
    // PowerPoint's presenting tools: the pen (ink kept per slide while presenting), the laser (a red dot under the finger), a black screen
    var tool by remember { mutableStateOf<String?>(null) }
    var black by remember { mutableStateOf(false) }
    var laser by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) } // as a part of the slide (0–1)
    // a TV (HDMI, a cast screen): the slide shown there, the phone the presenter's — the slide and its notes
    val displays = remember { context.getSystemService(android.hardware.display.DisplayManager::class.java) }
    var tvDisplay by remember { mutableStateOf(displays?.getDisplays(android.hardware.display.DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.firstOrNull()) }
    DisposableEffect(displays) {
        val listener = object : android.hardware.display.DisplayManager.DisplayListener {
            fun again() { tvDisplay = displays?.getDisplays(android.hardware.display.DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.firstOrNull() }
            override fun onDisplayAdded(id: Int) = again()
            override fun onDisplayRemoved(id: Int) = again()
            override fun onDisplayChanged(id: Int) = Unit
        }
        displays?.registerDisplayListener(listener, null)
        onDispose { displays?.unregisterDisplayListener(listener) }
    }
    val tv = remember(tvDisplay) { tvDisplay?.let { d -> runCatching { TvSlides(context, d, deck).also { it.show(); WriterLog.i("Slides: on a second screen") } }
        .onFailure { WriterLog.w("Slides: the second screen could not show", it) }.getOrNull() } }
    DisposableEffect(tv) { onDispose { tv?.dismiss() } }
    // sideways on one screen; with a second screen the presenter's phone turns as it is held (asked 17 Oct: upright, the slide over the notes)
    val activity = context as? Activity
    val was = remember { activity?.requestedOrientation }
    LaunchedEffect(tv != null) { activity?.requestedOrientation = if (tv != null) ActivityInfo.SCREEN_ORIENTATION_FULL_USER else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }
    DisposableEffect(Unit) {
        WriterLog.i("Slides: presenting from ${start + 1}")
        onDispose { if (was != null) activity?.requestedOrientation = was }
    }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // nothing over the slides: the status and gesture bars hidden (a swipe from the edge shows them a moment)
        val view = androidx.compose.ui.platform.LocalView.current
        DisposableEffect(view) {
            (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { w ->
                androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).apply {
                    hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            onDispose { }
        }
        // PowerPoint's keys: N, →, ↓, Space, Enter, Page Down on; P, ←, ↑, Backspace, Page Up back; B or W black; Home, End; a number and Enter to that slide; Esc ends
        val keys = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(Unit) { runCatching { keys.requestFocus() } }
        var typed by remember { mutableStateOf("") }
        var picking by remember { mutableStateOf(false) } // PowerPoint's See All Slides: a grid to go to any of them
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).focusRequester(keys).focusable().onKeyEvent { k ->
            if (k.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onKeyEvent false
            val digit = k.utf16CodePoint.toChar().takeIf { it.isDigit() }
            when {
                digit != null -> { typed += digit; true }
                (k.key == Key.Enter || k.key == Key.NumPadEnter) && typed.isNotEmpty() -> { goTo(typed.toInt() - 1); typed = ""; true }
                k.key in setOf(Key.N, Key.DirectionRight, Key.DirectionDown, Key.Spacebar, Key.Enter, Key.NumPadEnter, Key.PageDown) -> { typed = ""; if (!black) next(); true }
                k.key in setOf(Key.P, Key.DirectionLeft, Key.DirectionUp, Key.Backspace, Key.PageUp) -> { typed = ""; if (!black) back(); true }
                k.key == Key.B || k.key == Key.W || k.key == Key.Period -> { black = !black; true }
                k.key == Key.MoveHome -> { goTo(0); true }
                k.key == Key.MoveEnd -> { goTo(shown.lastIndex); true }
                k.key == Key.Escape -> { close(); true }
                else -> false
            }
        }, contentAlignment = Alignment.Center) {
            val s = slide(page)
            val upright = tv != null && maxHeight > maxWidth
            // on one screen the tools sit in a dock at the left edge that hides by itself a few seconds after its last use (asked 17 Oct)
            var dock by remember { mutableStateOf(false) }
            var dockUsed by remember { mutableStateOf(0) }
            // it stays while the pen or laser is on (taps draw then, so two taps could not bring it back to turn them off)
            LaunchedEffect(dock, dockUsed, tool) { if (dock && tool == null) { kotlinx.coroutines.delay(4000); dock = false } }
            // the tools: Pen, Laser, and with a second screen Black screen (on one screen a triple tap does it: a button there has no use)
            val toolButtons: @Composable () -> Unit = {
                @Composable fun tool(name: String, icon: Int, on: Boolean, click: () -> Unit) = IconButton(onClick = { click(); dockUsed++ }) {
                    Icon(painterResource(icon), contentDescription = name, tint = if (on) Color(0xFFFF5252) else Color.White.copy(alpha = 0.85f))
                }
                tool(stringResource(R.string.slides_pen), R.drawable.ic_ink_pen, tool == "pen") { tool = if (tool == "pen") null else "pen"; WriterLog.i("Slides: pen ${tool != null}") }
                tool(stringResource(R.string.slides_laser), R.drawable.ic_highlighter, tool == "laser") { tool = if (tool == "laser") null else "laser" }
                if (ink[page].orEmpty().isNotEmpty()) tool(stringResource(R.string.slides_wipe_ink), R.drawable.ic_ink_eraser, false) { ink.remove(page) }
                if (tv != null) tool(stringResource(R.string.slides_black_screen), R.drawable.ic_visibility_off, black) { black = !black }
                tool(stringResource(R.string.slides_all_slides), R.drawable.ic_view_grid, picking) { picking = !picking }
            }
            // the slide, its taps and swipes, the pen and the laser
            val slideArea: @Composable (Modifier) -> Unit = { m -> Box(m.pointerInput(tool) {
                // one tap: on; a swipe: to the left on, to the right back; two quick taps: the tools (one screen); three: the screen black, or back (asked 17 Oct)
                // (a tap waits a quarter second for another, to tell it from a triple tap)
                if (tool == null) awaitEachGesture {
                    var taps = 0; var at = androidx.compose.ui.geometry.Offset.Zero
                    while (taps < 3) {
                        val down = if (taps == 0) awaitFirstDown() else withTimeoutOrNull(250) { awaitFirstDown() } ?: break
                        if (taps == 0) at = down.position
                        var moved = 0f; var end = down
                        while (true) { val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break; moved = ch.position.x - down.position.x; end = ch; if (!ch.pressed) break }
                        if (kotlin.math.abs(moved) > 60.dp.toPx()) { if (taps == 0 && !black) { if (moved < 0) next() else back() }; return@awaitEachGesture }
                        taps++
                    }
                    if (taps == 3) { black = !black; WriterLog.i("Slides: black $black") }
                    else if (!black) { if (taps == 2) { if (tv == null) dock = true }
                        // a video playing: a tap beside it only stops it, the next tap goes on (the user, 17 Oct); swipes and keys go on at once
                        else if (filmPlaying != null) { filmPlaying = null; WriterLog.i("Slides: a video stopped by a tap") }
                        else if (!follow(at.x, at.y, size.width.toFloat(), size.height.toFloat())) next() }
                }
            }, contentAlignment = Alignment.Center) {
            // the whole slide in the window it is shown in, black bars on the sides that are left over (fillMaxSize first made it as tall as the window and cut its sides on a 3:2 tablet)
            Box(Modifier.aspectRatio(deck.width / deck.height).alpha(if (black && tv != null) 0.3f else 1f).pointerInput(page, tool) {
                // the pen draws (points as parts of the slide, so the ink stays put); the laser follows the finger
                if (tool != null) detectDragGestures(
                    onDragStart = { o -> if (tool == "pen") ink[page] = ink[page].orEmpty() + listOf(listOf(androidx.compose.ui.geometry.Offset(o.x / size.width, o.y / size.height)))
                        else laser = androidx.compose.ui.geometry.Offset(o.x / size.width, o.y / size.height) },
                    onDragEnd = { laser = null }, onDragCancel = { laser = null },
                ) { change, _ ->
                    val o = change.position
                    if (tool == "pen") ink[page] = ink[page].orEmpty().let { strokes -> strokes.dropLast(1) + listOf(strokes.last() + androidx.compose.ui.geometry.Offset(o.x / size.width, o.y / size.height)) }
                    else laser = androidx.compose.ui.geometry.Offset(o.x / size.width, o.y / size.height)
                }
            }) {
                Canvas(Modifier.fillMaxSize().background(Color.White)) {
                    now // a new frame: the effects move on
                    drawIntoCanvas { render(it.nativeCanvas, size.width, size.height) }
                    for (stroke in ink[page].orEmpty()) for (i in 1 until stroke.size)
                        drawLine(Color(0xFFE53935), androidx.compose.ui.geometry.Offset(stroke[i - 1].x * size.width, stroke[i - 1].y * size.height),
                            androidx.compose.ui.geometry.Offset(stroke[i].x * size.width, stroke[i].y * size.height), strokeWidth = size.width / 250, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    laser?.let { l -> val p = androidx.compose.ui.geometry.Offset(l.x * size.width, l.y * size.height)
                        drawCircle(Color(0x55FF1744), size.width / 60, p); drawCircle(Color(0xFFFF1744), size.width / 140, p) }
                }
                filmPlaying?.takeIf { it.first == page }?.second?.let { v -> androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) { val k = maxWidth / deck.width
                    VideoFrame(v.film, Modifier.offset(k * v.x, k * v.y).size(k * v.w, k * v.h).rotate(v.turn), v.trim) { filmPlaying = null } } }
            }
            } }
            val presenter: @Composable (Modifier) -> Unit = { m -> PresenterNotes(s.notes, page, paused, m) {
                toolButtons()
                IconButton(onClick = { paused = !paused; WriterLog.i("Slides: paused $paused") }) {
                    Icon(painterResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause), contentDescription = if (paused) stringResource(R.string.slides_go_on) else stringResource(R.string.common_pause), tint = if (paused) POWERPOINT_ORANGE else Color.White.copy(alpha = 0.85f))
                }
                Text(when { black -> stringResource(R.string.slides_second_black); paused -> stringResource(R.string.slides_paused); else -> stringResource(R.string.slides_on_second_screen) }, color = if (black || paused) POWERPOINT_ORANGE else Color(0xFF6FCF97),
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 1)
                Text("${page + 1} / ${shown.size}", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium)
            } }
            // with a second screen: held upright (a phone), the slide across the top and the notes under it; sideways, the slide small
            // beside them (half the width while pointing or drawing); clear of a camera's cutout
            val around = if (tv != null) Modifier.fillMaxSize().displayCutoutPadding() else Modifier.fillMaxSize()
            if (upright) Column(around) { slideArea(Modifier.fillMaxWidth().aspectRatio(deck.width / deck.height)); presenter(Modifier.weight(1f).fillMaxWidth()) }
            else Row(around, verticalAlignment = Alignment.CenterVertically) {
                slideArea(Modifier.weight(1f).fillMaxHeight())
                if (tv != null) presenter(Modifier.weight(if (tool != null) 1f else 1.4f).fillMaxHeight())
            }
            if (notes && tv == null && s.notes.isNotEmpty()) Text(s.notes, color = Color.White, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).padding(8.dp))
            // the TV shows what the phone does, frame by frame: the slide (black when the phone's is), its effects, ink and laser
            LaunchedEffect(tv) { tv?.render = { c, w, h -> if (!black) render(c, w, h) } }
            // the TV drawn again every frame (transitions and effects move there too), with the pen's ink and the laser
            LaunchedEffect(tv) { if (tv != null) while (true) androidx.compose.runtime.withFrameNanos { tv.apply { strokes = if (black) emptyList() else ink[page].orEmpty(); dot = laser.takeIf { !black }; update() } } }
            // black on one screen: the slide covered, taps passing to it (three bring it back); with a second screen only that one goes black
            if (black && tv == null) Box(Modifier.fillMaxSize().background(Color.Black))
            // Rehearse timings: this slide's time so far, in the corner (PowerPoint's Recording bar)
            if (rehearse != null) Text(stringResource(R.string.slides_rehearsing, ms(since) / 60_000, ms(since) / 1000 % 60), color = Color.White, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xCC000000)).padding(horizontal = 10.dp, vertical = 6.dp))
            // See All Slides: every slide shown (hidden ones too, dimmed), one tapped goes there (on the second screen too)
            if (picking) androidx.compose.foundation.lazy.grid.LazyVerticalGrid(androidx.compose.foundation.lazy.grid.GridCells.Adaptive(220.dp),
                Modifier.fillMaxSize().background(Color(0xF0101010)), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(shown.size) { i -> Column(Modifier.clip(RoundedCornerShape(6.dp)).clickable { picking = false; goTo(i); WriterLog.i("Slides: show, to slide ${i + 1} from the grid") }) {
                    SlideCanvas(deck, deck.slides[shown[i]], Modifier.fillMaxWidth().then(if (i == page) Modifier.border(3.dp, POWERPOINT_ORANGE) else Modifier))
                    Text("${shown[i] + 1}", color = if (i == page) POWERPOINT_ORANGE else Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp)) } }
            }
            // the dock at the left edge: the tools and the slide count, brought out by two taps; kept from Android's Back swipe there (asked 17 Oct: it conflicted)
            if (tv == null) Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.animation.AnimatedVisibility(dock, enter = androidx.compose.animation.slideInHorizontally { -it }, exit = androidx.compose.animation.slideOutHorizontally { -it }) {
                    Column(Modifier.systemGestureExclusion().padding(start = 4.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xE6202020)).padding(vertical = 8.dp, horizontal = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${page + 1} / ${shown.size}", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                        toolButtons()
                    }
                }
            }
        }
    }
}

/**
 * The presenter's notes beside the slide while a second screen shows it (asked 17 Oct): Full — all of them, to read and
 * scroll; Teleprompter — large, rolling up by themselves at the chosen speed, from the top again on each slide.
 */
/** Insert › Header & footer (PowerPoint's): the date (updated or fixed words), the slide's number, a footer; Apply to the slide or to all. */
@Composable
private fun FootersDialog(start: SlidesEdit.Footers, dismiss: () -> Unit, apply: (SlidesEdit.Footers, Boolean, Boolean) -> Unit) {
    var date by remember { mutableStateOf(start.date) }
    var fixed by remember { mutableStateOf(start.fixed != null) }
    var fixedText by remember { mutableStateOf(start.fixed ?: SlidesEdit.today()) }
    var number by remember { mutableStateOf(start.number) }
    var footer by remember { mutableStateOf(start.text != null) }
    var footerText by remember { mutableStateOf(start.text.orEmpty()) }
    var notOnTitle by remember { mutableStateOf(true) }
    fun made() = SlidesEdit.Footers(date, fixedText.takeIf { fixed }, number, footerText.takeIf { footer })
    @Composable fun check(label: String, on: Boolean, change: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth().clickable { change(!on) }, verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Checkbox(on, change); Text(label) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.slides_header_footer)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                check(stringResource(R.string.slides_date_time), date) { date = it }
                if (date) Row(Modifier.padding(start = 40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice(stringResource(R.string.slides_update_auto), !fixed) { fixed = false }; Choice(stringResource(R.string.slides_fixed), fixed) { fixed = true } }
                if (date && fixed) androidx.compose.material3.OutlinedTextField(fixedText, { fixedText = it }, singleLine = true, modifier = Modifier.padding(start = 40.dp), label = { Text(stringResource(R.string.slides_date)) })
                check(stringResource(R.string.slides_slide_number), number) { number = it }
                check(stringResource(R.string.slides_footer), footer) { footer = it }
                if (footer) androidx.compose.material3.OutlinedTextField(footerText, { footerText = it }, singleLine = true, modifier = Modifier.padding(start = 40.dp), label = { Text(stringResource(R.string.slides_footer)) })
                check(stringResource(R.string.slides_not_on_title), notOnTitle) { notOnTitle = it }
            }
        },
        confirmButton = { Row {
            androidx.compose.material3.TextButton(onClick = { apply(made(), false, notOnTitle) }) { Text(stringResource(R.string.common_apply)) }
            androidx.compose.material3.TextButton(onClick = { apply(made(), true, notOnTitle) }) { Text(stringResource(R.string.slides_apply_to_all)) } } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun PresenterNotes(notes: String, page: Int, paused: Boolean, modifier: Modifier, header: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val context = LocalContext.current
    var prompter by remember { mutableStateOf(false) }
    var rolling by remember { mutableStateOf(true) }
    // the teleprompter's speed (dp a second) and words' size (sp) kept for the next presentation; 13 the user's pick (17 Oct)
    val prefs = remember { context.getSharedPreferences("slides", android.content.Context.MODE_PRIVATE) }
    var speed by remember { mutableStateOf(prefs.getFloat("prompterSpeed", 13f)) }
    var size by remember { mutableStateOf(prefs.getInt("prompterSize", 28)) }
    val scroll = rememberScrollState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(page) { scroll.scrollTo(0) }
    LaunchedEffect(prompter, rolling, speed, page, paused) {
        if (!prompter || !rolling || paused) return@LaunchedEffect
        var last = androidx.compose.runtime.withFrameNanos { it }
        while (true) { val t = androidx.compose.runtime.withFrameNanos { it }; scroll.scrollBy(with(density) { speed.dp.toPx() } * (t - last) / 1e9f); last = t }
    }
    val chip = androidx.compose.material3.FilterChipDefaults.filterChipColors(labelColor = Color.White, selectedContainerColor = POWERPOINT_ORANGE, selectedLabelColor = Color.White)
    Column(modifier.padding(start = 8.dp, top = 8.dp, end = 12.dp, bottom = 12.dp)) {
        // the presenting tools, Pause, where the slide is shown and the slide count
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = header)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.FilterChip(!prompter, { prompter = false }, { Text(stringResource(R.string.slides_full), maxLines = 1, overflow = TextOverflow.Ellipsis) }, colors = chip)
            androidx.compose.material3.FilterChip(prompter, { prompter = true; WriterLog.i("Slides: teleprompter") }, { Text(stringResource(R.string.slides_teleprompter), maxLines = 1, overflow = TextOverflow.Ellipsis) }, colors = chip)
        }
        // the teleprompter's own on a row of their own, so they fit a phone's width
        if (prompter) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { rolling = !rolling }) { Icon(painterResource(if (rolling) R.drawable.ic_pause else R.drawable.ic_play), contentDescription = if (rolling) stringResource(R.string.common_pause) else stringResource(R.string.slides_roll), tint = Color.White) }
                Text(stringResource(R.string.slides_speed), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                androidx.compose.material3.Slider(speed, { speed = it }, Modifier.weight(1f), valueRange = 5f..120f,
                    onValueChangeFinished = { prefs.edit().putFloat("prompterSpeed", speed).apply() })
                fun resize(by: Int) { size = (size + by).coerceIn(16, 72); prefs.edit().putInt("prompterSize", size).apply() }
                IconButton(onClick = { resize(-4) }, enabled = size > 16) { Icon(painterResource(R.drawable.ic_remove), contentDescription = stringResource(R.string.slides_smaller_text), tint = Color.White) }
                Text("${size}", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = { resize(4) }, enabled = size < 72) { Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.slides_larger_text), tint = Color.White) }
        }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1C1C1C))) {
            val box = maxHeight
            // the teleprompter's words start a third of the way down and roll on until the last line has gone off the top
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp)) {
                if (prompter) androidx.compose.foundation.layout.Spacer(Modifier.height(box / 3))
                Text(notes.ifEmpty { stringResource(R.string.slides_no_notes_slide) }, color = if (notes.isEmpty()) Color.White.copy(alpha = 0.5f) else Color.White,
                    style = if (prompter) MaterialTheme.typography.headlineMedium.copy(fontSize = size.sp, lineHeight = (size * 1.3f).sp) else MaterialTheme.typography.bodyLarge)
                if (prompter) androidx.compose.foundation.layout.Spacer(Modifier.height(box))
            }
        }
    }
}

/** The slides on a second screen (a TV by HDMI or a cast): drawn as the phone shows them, frame by frame, with the pen's ink and the laser. */
private class TvSlides(context: android.content.Context, display: android.view.Display, val deck: Deck) : android.app.Presentation(context, display) {
    var render: ((android.graphics.Canvas, Float, Float) -> Unit)? = null
    var strokes: List<List<androidx.compose.ui.geometry.Offset>> = emptyList()
    var dot: androidx.compose.ui.geometry.Offset? = null
    private val view = object : android.view.View(context) {
        val red = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt(); strokeCap = android.graphics.Paint.Cap.ROUND }
        override fun onDraw(c: android.graphics.Canvas) {
            c.drawColor(android.graphics.Color.BLACK)
            render?.invoke(c, width.toFloat(), height.toFloat())
            val k = minOf(width / deck.width, height / deck.height)
            val w = deck.width * k; val h = deck.height * k
            c.save(); c.translate((width - w) / 2, (height - h) / 2)
            red.strokeWidth = w / 250; red.alpha = 255
            for (st in strokes) for (i in 1 until st.size) c.drawLine(st[i - 1].x * w, st[i - 1].y * h, st[i].x * w, st[i].y * h, red)
            dot?.let { d -> red.alpha = 0x55; c.drawCircle(d.x * w, d.y * h, w / 60, red); red.alpha = 255; c.drawCircle(d.x * w, d.y * h, w / 140, red) }
            c.restore()
        }
    }
    override fun onCreate(savedInstanceState: android.os.Bundle?) { super.onCreate(savedInstanceState); setContentView(view) }
    fun update() = view.invalidate()
}

/** Design › Themes (asked 16 Oct): Tetra's light and dark ones and the templates in Documents/Templates, each a small slide in its colours and fonts. */
@Composable
private fun ThemesDialog(now: SlideTheme?, dismiss: () -> Unit, pick: (SlideTheme) -> Unit) {
    val templates by androidx.compose.runtime.produceState(emptyList<SlideTheme>()) {
        value = withContext(Dispatchers.IO) { SlidesTemplates.files().mapNotNull { SlidesThemes.fromFile(it) } }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 720.dp).then(Modifier.padding(12.dp)), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.slides_themes), style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    @Composable fun group(title: String, list: List<SlideTheme>) {
                        if (list.isEmpty()) return
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 2) {
                            list.forEach { t -> ThemeCard(t, t == now, Modifier.weight(1f)) { pick(t) } }
                            if (list.size % 2 == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) // a last one alone keeps its size
                        }
                    }
                    group(stringResource(R.string.slides_light), SlidesThemes.BUILT_IN.filter { !it.dark })
                    group(stringResource(R.string.slides_dark), SlidesThemes.BUILT_IN.filter { it.dark })
                    group(stringResource(R.string.slides_your_templates), templates)
                    if (templates.isEmpty()) Text(stringResource(R.string.slides_templates_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { androidx.compose.material3.TextButton(onClick = dismiss) { Text(stringResource(R.string.common_close)) } }
            }
        }
    }
}

/** A theme as a small slide: its background, its band, a title and words in its fonts and colours, its six accents. */
@Composable
internal fun ThemeCard(t: SlideTheme, chosen: Boolean, modifier: Modifier = Modifier, click: () -> Unit) {
    fun c(i: Int) = Color(0xFF000000.toInt() or t.colors[i])
    Column(modifier.clip(RoundedCornerShape(10.dp)).border(if (chosen) 3.dp else 1.dp, if (chosen) POWERPOINT_ORANGE else Color(0x55888888), RoundedCornerShape(10.dp)).clickable(onClick = click)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(c(1))) {
            androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                val a = c(4)
                when (t.band) {
                    'l' -> drawRect(a, size = androidx.compose.ui.geometry.Size(size.width * 0.03f, size.height))
                    'r' -> drawRect(a, androidx.compose.ui.geometry.Offset(size.width * 0.94f, 0f), androidx.compose.ui.geometry.Size(size.width * 0.06f, size.height))
                    't' -> drawRect(a, size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.03f))
                    'b' -> drawRect(a, androidx.compose.ui.geometry.Offset(0f, size.height * 0.94f), androidx.compose.ui.geometry.Size(size.width, size.height * 0.06f))
                }
                drawIntoCanvas { cv ->
                    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = (0xFF000000.toInt() or t.colors[0]); textSize = size.height * 0.2f
                        typeface = OfficeFonts.typeface(t.major) ?: android.graphics.Typeface.DEFAULT }
                    cv.nativeCanvas.drawText(t.name, size.width * 0.08f, size.height * 0.42f, p)
                    p.textSize = size.height * 0.11f; p.typeface = OfficeFonts.typeface(t.minor) ?: android.graphics.Typeface.DEFAULT; p.color = 0xFF000000.toInt() or t.colors[2]
                    cv.nativeCanvas.drawText("${t.major} · ${t.minor}", size.width * 0.08f, size.height * 0.62f, p)
                }
                // its accents
                for (i in 0 until 6) drawCircle(c(4 + i), size.height * 0.05f, androidx.compose.ui.geometry.Offset(size.width * (0.1f + i * 0.08f), size.height * 0.8f))
            }
        }
    }
}

/** Changes not yet saved, kept when the app went to the background: one per presentation (by its path), in the app's storage. */
private fun slideDraft(context: android.content.Context, file: File) =
    File(File(context.filesDir, "slide-drafts"), "${file.absolutePath.hashCode().toUInt()}.${file.extension}")

/**
 * Home › Selection pane (PowerPoint's): every thing on [slide], the front one first — tapped, it is chosen (one under another too);
 * its eye hides or shows it ([look]: `Look.hide`), its name is changed in place (`Look.name`), ↑ ↓ bring it forward or send it back ([move]).
 */
@Composable
private fun SelectionPane(slide: Slide, chosen: String?, dismiss: () -> Unit, choose: (String) -> Unit, look: (String, (SlidesEdit.Look) -> SlidesEdit.Look) -> Unit, move: (String, Char) -> Unit) {
    val things = slide.items.filter { it.anim != null && !(it is SlideItem.Shape && it.hint) }.distinctBy { it.anim }.asReversed()
    var naming by remember { mutableStateOf<Pair<String, String>?>(null) }
    @Composable fun kind(it0: SlideItem) = when (it0) {
        is SlideItem.Shape -> if (it0.shape == null && it0.text != null) stringResource(R.string.slides_text_box) else stringResource(R.string.slides_shape)
        is SlideItem.Picture -> if (it0.media?.let(OfficeVideo::audio) == true) stringResource(R.string.slides_audio) else if (it0.media != null) stringResource(R.string.slides_video) else if (it0.math != null) stringResource(R.string.slides_equation) else stringResource(R.string.slides_picture)
        is SlideItem.Chart -> stringResource(R.string.slides_chart); is SlideItem.Table -> stringResource(R.string.slides_kind_table)
    }
    androidx.compose.material3.AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.slides_selection_pane)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            things.forEachIndexed { i, it0 -> val id = it0.anim!!
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(if (id == chosen) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent).clickable { if (!it0.hidden) choose(id) }.padding(start = 8.dp)) {
                    if (naming?.first == id) androidx.compose.material3.OutlinedTextField(naming!!.second, { naming = id to it }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { look(id) { l -> l.copy(name = naming!!.second.trim()) }; naming = null }),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done))
                    else Column(Modifier.weight(1f)) {
                        Text(it0.name ?: kind(it0), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (it0.hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        (it0 as? SlideItem.Shape)?.text?.paras?.joinToString(" ") { p -> p.runs.joinToString("") { it.text } }?.trim()?.takeIf { it.isNotEmpty() && it0.name != null }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    if (naming?.first == id) IconButton(onClick = { look(id) { l -> l.copy(name = naming!!.second.trim()) }; naming = null }) { Icon(painterResource(R.drawable.ic_check), stringResource(R.string.slides_keep_name)) }
                    else IconButton(onClick = { naming = id to (it0.name ?: "") }) { Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.common_rename)) }
                    IconButton(onClick = { look(id) { l -> l.copy(hide = !it0.hidden) } }) {
                        Icon(painterResource(if (it0.hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility), if (it0.hidden) stringResource(R.string.slides_show_it) else stringResource(R.string.slides_hide_it)) }
                    IconButton(onClick = { move(id, '+') }, enabled = i > 0) { Icon(painterResource(R.drawable.ic_keyboard_arrow_up), stringResource(R.string.slides_bring_forward)) }
                    IconButton(onClick = { move(id, '-') }, enabled = i < things.lastIndex) { Icon(painterResource(R.drawable.ic_keyboard_arrow_down), stringResource(R.string.slides_send_backward)) }
                }
            }
            if (things.isEmpty()) Text(stringResource(R.string.slides_nothing_on_slide), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } },
        confirmButton = { androidx.compose.material3.TextButton(onClick = dismiss) { Text(stringResource(R.string.common_done)) } })
}

/**
 * Animations › Animation pane (PowerPoint's): the slide's effects in their order — each with when it starts, what it does and to what;
 * tapped, its thing is chosen; moved up or down, or taken away. [set] gets the list as it is then (one step of Undo each).
 */
@Composable
private fun AnimationPane(list: List<SlideEffect>, slide: Slide, chosen: String?, dismiss: () -> Unit, choose: (String) -> Unit, set: (List<SlideEffect>) -> Unit) {
    @Composable fun named(target: String): String = when (val it0 = slide.items.lastOrNull { it.anim == target }) {
        is SlideItem.Shape -> it0.text?.paras?.joinToString(" ") { p -> p.runs.joinToString("") { it.text } }?.trim()?.take(28)?.ifEmpty { null } ?: stringResource(R.string.slides_shape)
        is SlideItem.Picture -> if (it0.media?.let(OfficeVideo::audio) == true) stringResource(R.string.slides_audio) else if (it0.media != null) stringResource(R.string.slides_video) else if (it0.math != null) stringResource(R.string.slides_equation) else stringResource(R.string.slides_picture)
        is SlideItem.Chart -> stringResource(R.string.slides_chart); is SlideItem.Table -> stringResource(R.string.slides_kind_table); null -> stringResource(R.string.slides_something_put_on)
    }
    // the effect's name as the ribbon gives it (one read from a file that Slides has no name for: as the file has it)
    @Composable fun what(fx: SlideEffect): String {
        val name = when (fx.kind) { "appear" -> R.string.slides_appear; "fade" -> R.string.slides_fade; "fly" -> R.string.slides_fly; "wipe" -> R.string.slides_wipe; "zoom" -> R.string.slides_zoom
            "pulse" -> R.string.slides_pulse; "spin" -> R.string.slides_spin; "grow" -> R.string.slides_grow; "play" -> R.string.slides_play; else -> null }?.let { stringResource(it) } ?: fx.kind.replaceFirstChar { it.uppercase() }
        return when { fx.emphasis -> name; fx.exit -> stringResource(R.string.slides_fx_out, name); else -> stringResource(R.string.slides_fx_in, name) }
    }
    androidx.compose.material3.AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.slides_animation_pane)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            var click = 0
            list.forEachIndexed { i, fx ->
                if (fx.start == 'c' || i == 0) click++
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(if (fx.target == chosen) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent).clickable { choose(fx.target) }.padding(start = 8.dp)) {
                    Text(if (fx.start == 'c' || i == 0) "$click" else "", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(24.dp))
                    Column(Modifier.weight(1f)) {
                        Text(named(fx.target), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(what(fx) + " · " + when (fx.start) { 'w' -> stringResource(R.string.slides_lc_with_previous); 'a' -> stringResource(R.string.slides_lc_after_previous); else -> stringResource(R.string.slides_lc_on_click) } + " · " + stringResource(R.string.slides_secs, fx.ms / 1000f) +
                            (if (fx.wait > 0) " " + stringResource(R.string.slides_after_secs, fx.wait / 1000f) else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { set(list.toMutableList().apply { add(i - 1, removeAt(i)) }) }, enabled = i > 0) { Icon(painterResource(R.drawable.ic_keyboard_arrow_up), stringResource(R.string.slides_earlier)) }
                    IconButton(onClick = { set(list.toMutableList().apply { add(i + 1, removeAt(i)) }) }, enabled = i < list.lastIndex) { Icon(painterResource(R.drawable.ic_keyboard_arrow_down), stringResource(R.string.slides_later)) }
                    IconButton(onClick = { set(list.filterIndexed { k, _ -> k != i }) }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.slides_take_away)) }
                }
            }
            if (list.isEmpty()) Text(stringResource(R.string.slides_no_animations_slide), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } },
        confirmButton = { androidx.compose.material3.TextButton(onClick = dismiss) { Text(stringResource(R.string.common_done)) } })
}

/** A text box's Layout (PowerPoint's Format Shape › Text Box, LibreOffice's Text attributes): inner margins in the user's unit, wrap, columns. */
@Composable
private fun TextBoxLayout(t: SlideText, dismiss: () -> Unit, set: (List<Float>, Boolean, Int, Float) -> Unit) {
    val margins = remember { t.insets.map { mutableStateOf(WriterUnits.text(it)) } }
    var wrap by remember { mutableStateOf(t.wrap) }
    var cols by remember { mutableStateOf(t.cols.coerceAtLeast(1)) }
    var gap by remember { mutableStateOf(WriterUnits.text(if (t.cols > 1) t.colGap else 7.2f)) }
    fun num(s: String) = s.replace(',', '.').toFloatOrNull()
    val ok = margins.all { (num(it.value) ?: -1f) >= 0f } && (num(gap) ?: -1f) >= 0f
    androidx.compose.material3.AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.slides_layout)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.slides_inner_margins, WriterUnits.name), style = MaterialTheme.typography.labelLarge)
            listOf(stringResource(R.string.slides_left), stringResource(R.string.slides_top), stringResource(R.string.slides_right), stringResource(R.string.slides_bottom)).chunked(2).forEachIndexed { row, pair -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEachIndexed { k, label -> val m = margins[row * 2 + k]
                    androidx.compose.material3.OutlinedTextField(m.value, { m.value = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)) } } }
            Row(verticalAlignment = Alignment.CenterVertically) { androidx.compose.material3.Checkbox(wrap, { wrap = it }); Text(stringResource(R.string.slides_wrap_words)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.slides_columns), modifier = Modifier.weight(1f))
                SizeStepper("$cols", MaterialTheme.colorScheme.onSurface, cols > 1, cols < 6, { cols-- }, { cols++ })
            }
            if (cols > 1) androidx.compose.material3.OutlinedTextField(gap, { gap = it }, label = { Text(stringResource(R.string.slides_between_columns, WriterUnits.name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
        } },
        confirmButton = { androidx.compose.material3.TextButton(enabled = ok, onClick = { set(margins.map { WriterUnits.toPt(num(it.value)!!) }, wrap, cols, WriterUnits.toPt(num(gap)!!)) }) { Text(stringResource(R.string.common_ok)) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } })
}

/** Arrange › Size and position (PowerPoint's): [item]'s width, height and place from the slide's top left, in Words' unit (cm or inches); the proportions kept or not. */
@Composable
private fun SizeAndPosition(item: SlideItem, dismiss: () -> Unit, set: (Float, Float, Float, Float) -> Unit) {
    var w by remember { mutableStateOf(WriterUnits.text(item.w)) }; var h by remember { mutableStateOf(WriterUnits.text(item.h)) }
    var x by remember { mutableStateOf(WriterUnits.text(item.x)) }; var y by remember { mutableStateOf(WriterUnits.text(item.y)) }
    var kept by remember { mutableStateOf(true) }
    val ratio = if (item.w > 0f) item.h / item.w else 1f
    fun num(t: String) = t.replace(',', '.').toFloatOrNull()
    val ok = listOf(w, h).all { (num(it) ?: 0f) > 0f } && listOf(x, y).all { num(it) != null }
    androidx.compose.material3.AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.slides_size_position)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            @Composable fun field(label: String, v: String, change: (String) -> Unit) = androidx.compose.material3.OutlinedTextField(v, change, label = { Text("$label (${WriterUnits.name})") }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            field(stringResource(R.string.slides_width), w) { t -> w = t; if (kept) num(t)?.let { h = String.format(java.util.Locale.ROOT, "%.2f", it * ratio) } }
            field(stringResource(R.string.slides_height), h) { t -> h = t; if (kept && ratio > 0f) num(t)?.let { w = String.format(java.util.Locale.ROOT, "%.2f", it / ratio) } }
            Row(verticalAlignment = Alignment.CenterVertically) { androidx.compose.material3.Checkbox(kept, { kept = it }); Text(stringResource(R.string.slides_keep_proportions)) }
            field(stringResource(R.string.slides_from_left), x) { x = it }
            field(stringResource(R.string.slides_from_top), y) { y = it }
        } },
        confirmButton = { androidx.compose.material3.TextButton(enabled = ok, onClick = { set(WriterUnits.toPt(num(x)!!), WriterUnits.toPt(num(y)!!), WriterUnits.toPt(num(w)!!), WriterUnits.toPt(num(h)!!)) }) { Text(stringResource(R.string.common_ok)) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } })
}

/** Review › Spelling's word in a deck: its slide, its box (its id), its line there, where in it, the word. */
internal data class SlideSpell(val slide: Int, val id: String, /** what chooses it (its anim id) */ val sel: String, val line: Int, val at: IntRange, val word: String)

/** Design › Slide size › Custom…: the slides' width and height in cm (PowerPoint's 2.54–142.24 cm). */
@Composable private fun SlideSizeDialog(w0: Float, h0: Float, dismiss: () -> Unit, ok: (Float, Float) -> Unit) {
    fun cm(pt: Float) = String.format(java.util.Locale.ROOT, "%.2f", pt / 28.346457f).trimEnd('0').trimEnd('.')
    var w by remember { mutableStateOf(cm(w0)) }; var h by remember { mutableStateOf(cm(h0)) }
    fun pt(s: String) = s.replace(',', '.').toFloatOrNull()?.takeIf { it in 2.54f..142.24f }?.times(28.346457f)
    androidx.compose.material3.AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.slides_slide_size)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, v, set) in listOf(Triple(stringResource(R.string.slides_width_cm), w) { x: String -> w = x }, Triple(stringResource(R.string.slides_height_cm), h) { x: String -> h = x }))
                androidx.compose.material3.OutlinedTextField(v, set, label = { Text(label) }, singleLine = true, isError = pt(v) == null,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
            Text(stringResource(R.string.slides_size_range), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { androidx.compose.material3.TextButton(onClick = { ok(pt(w) ?: return@TextButton, pt(h) ?: return@TextButton) }, enabled = pt(w) != null && pt(h) != null) { Text(stringResource(R.string.common_ok)) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } })
}

/** View › Outline's lines as drawn: each paragraph indented by its level, its bullet or number before it (the words typed stay as they are). */
internal fun outlineLines(text: String, paras: List<SlidePara>): androidx.compose.ui.text.input.TransformedText {
    val lines = text.split("\n")
    val pre = lines.indices.map { i -> paras.getOrNull(i)?.let { p -> "\u2003\u2003".repeat(p.level) + (p.bullet?.text?.let { "$it  " } ?: "") } ?: "" }
    val starts = IntArray(lines.size).also { a -> var at = 0; lines.forEachIndexed { i, l -> a[i] = at; at += l.length + 1 } }
    val out = androidx.compose.ui.text.buildAnnotatedString { lines.forEachIndexed { i, l -> if (i > 0) append('\n')
        pushStyle(androidx.compose.ui.text.SpanStyle(color = Color.LightGray)); append(pre[i]); pop(); append(l) } }
    return androidx.compose.ui.text.input.TransformedText(out, object : androidx.compose.ui.text.input.OffsetMapping {
        override fun originalToTransformed(offset: Int): Int { val line = starts.indexOfLast { it <= offset }.coerceAtLeast(0); return offset + (0..line).sumOf { pre[it].length } }
        override fun transformedToOriginal(offset: Int): Int {
            var at = 0
            for (i in lines.indices) { val len = pre[i].length + lines[i].length
                if (offset <= at + len) return starts[i] + (offset - at - pre[i].length).coerceIn(0, lines[i].length)
                at += len + 1 }
            return text.length
        }
    })
}
