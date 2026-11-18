package com.kalotrapezis.drive

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale

private const val RECENTS_PREFS = "drive_recents"
private const val RECENTS_KEY = "items"
private const val RECENTS_LIMIT = 50
private const val OPENERS_PREFS = "drive_openers"
const val FILE_OPENER_CHOSEN = "com.kalotrapezis.drive.FILE_OPENER_CHOSEN"
const val FILE_OPENER_PATH = "drive_opener_path"

data class DriveItem(val file: File, val relativePath: String, val isDirectory: Boolean, val modified: Long = -1, val size: Long = -1) {
    /** From the folder's listing ([listDriveFolder]) when it has them, else asked of the file. */
    val lastModified get() = if (modified >= 0) modified else file.lastModified()
    val length get() = if (size >= 0) size else file.length()
}
data class DriveRecent(val relativePath: String, val openedAt: Long)
enum class DriveFolderColor { Blue, Green, Yellow, Red, Purple }
/**
 * Something Files refuses, said in the user's language where it is shown ([res] with [args], see [shownMessage]);
 * the message is the English for the log and for screens that show it as it is.
 */
class DriveProblem(message: String, @androidx.annotation.StringRes val res: Int, vararg val args: Any) : IllegalArgumentException(message)
internal inline fun need(ok: Boolean, @androidx.annotation.StringRes res: Int, vararg args: Any, english: () -> String) { if (!ok) throw DriveProblem(english(), res, *args) }
/** A failure's words for the screen: a Files problem in the user's language, anything else as it says itself. */
fun Throwable.shownMessage(context: Context): String? = (this as? DriveProblem)?.let { context.getString(it.res, *it.args) } ?: message

data class DriveSpaceUsage(val bytesByType: Map<String, Long>) {
    val totalBytes: Long get() = bytesByType.values.sum()
}

/** File boundary rules are kept independent from Compose so they can be tested without a device. */
/**
 * The app's folder on the phone: /sdcard/Tetra. Until 2026-09-24 it was /sdcard/Drive, after the app's old name;
 * it is moved once, by a rename — instant on one storage, no space needed, and it happens or it does not. Nothing
 * that refers to a file inside it changes: favorites, tags, recents and sync all use paths relative to it, so a
 * phone that has moved and a computer that has not still match file for file. When both exist, Tetra is used and
 * Drive is left exactly as it is.
 */
object TetraFolder {
    fun root(storage: File): File {
        val root = File(storage, "Tetra")
        val old = File(storage, "Drive")
        if (!root.exists() && old.isDirectory) old.renameTo(root)
        return (if (!root.exists() && old.isDirectory) old else root) // the rename refused: keep using where it all is
            .also(TetraSystem::migrateOnce)
    }
}

/**
 * /sdcard/Tetra/.System: everything Tetra keeps for its own use, the phone's side of the computer's ~/Tetra/.System (Drive's
 * desktop/system.js and SYSTEM.md, which say who may delete what). Files hold only the user's own files (the user, 29 Oct
 * 2026: "Make Tetra process everything from the system folder; Files and Photos to be userspace only").
 *
 * Sync still names an app's files as before ("Calendar/…", "Trash/…"): [SHARED] says which .System/<App>/Shared each of
 * those names lives in, so a device on an older version sends and gets the same paths. The computer's system.SHARED is the
 * same list.
 */
object TetraSystem {
    const val NAME = ".System"
    val SHARED = mapOf("Calendar" to "Calendar", "Contacts" to "Contacts", "Data" to "Data", "Phone" to "Phone", "Timer" to "Timer", "Maps" to "Maps", "Mail" to "Mail", "AI Assist" to "Assistant", "Browser" to "Browser", "Pills" to "Pills", "Trash" to "Trash")

    /** `<tetra>/.System/<app>/<kind>`, kind one of Data, Tools, Cache, Temp, Shared. */
    fun dir(tetra: File, app: String, kind: String) = File(tetra, "$NAME/$app/$kind")
    /** Where a path of Files is under the Tetra folder: an app's ("Calendar/…") in .System/<App>/Shared, any other as it is. */
    fun onDisk(relative: String): String {
        val top = relative.substringBefore('/')
        val app = SHARED[top] ?: return relative
        return "$NAME/$app/Shared${relative.removePrefix(top)}"
    }
    /** The reverse of [onDisk]: the path sync and Files know a file by. */
    fun fromDisk(relative: String): String {
        val p = relative.split('/')
        if (p.size < 3 || p[0] != NAME || p[2] != "Shared") return relative
        val top = SHARED.entries.firstOrNull { it.value == p[1] }?.key ?: return relative
        return (listOf(top) + p.drop(3)).joinToString("/")
    }
    /** The Tetra folder an app's folder is in ([onDisk]'s .System/<App>/Shared; a plain one beside the others, as tests make). */
    fun tetraOf(appRoot: File): File = appRoot.absoluteFile.let { if (it.name == "Shared" && it.parentFile?.parentFile?.name == NAME) it.parentFile!!.parentFile!!.parentFile!! else it.parentFile!! }

    @Volatile private var migrated: String? = null
    /** [migrate] once a process, before anything reads those folders (every one of them comes through [TetraFolder.root]); the copies then, on their own thread. */
    fun migrateOnce(tetra: File) {
        if (migrated == tetra.path) return
        synchronized(this) {
            if (migrated == tetra.path) return
            migrated = tetra.path
            val (moved, failed) = migrate(tetra)
            if (moved.isNotEmpty()) android.util.Log.i("TetraSystem", "moved ${moved.joinToString("; ")}")
            failed.forEach { android.util.Log.w("TetraSystem", "not moved: $it") }
        }
        val done = File(dir(tetra, "Backups", "Data"), "Sync copies/.set-aside")
        if (!done.exists()) Thread {
            runCatching {
                val r = setAsideCopies(tetra)
                android.util.Log.i("TetraSystem", "sync copies: ${r.moved.size} set aside, ${r.left.size} left where they are: ${r.left.joinToString("; ")}")
                r.failed.forEach { android.util.Log.w("TetraSystem", "not moved: $it") }
                done.parentFile?.mkdirs(); done.writeText(System.currentTimeMillis().toString())
            }.onFailure { android.util.Log.w("TetraSystem", "sync copies: ${it.message}") }
        }.start()
    }

    /**
     * Tetra's own folders into .System, by renaming (one storage: instant, nothing copied or deleted), every file keeping
     * its name, so sync sees nothing change: Calendar → Calendar/Shared and the rest of [SHARED], .notes → Notes/Shared,
     * .templates → Office/Data. A file already at the target is left, both kept, and said; run again, it finishes what an
     * interruption left. (moved, failed).
     */
    fun migrate(tetra: File): Pair<List<String>, List<String>> {
        val moved = mutableListOf<String>(); val failed = mutableListOf<String>()
        SHARED.forEach { (name, app) -> moveInto(File(tetra, name), dir(tetra, app, "Shared"), moved, failed) }
        moveInto(File(tetra, ".notes"), dir(tetra, "Notes", "Shared"), moved, failed)
        moveInto(File(tetra, ".templates"), dir(tetra, "Office", "Data"), moved, failed)
        return moved to failed
    }

    /** [from] to [to] by renaming; into a folder already there, each thing in it on its own. */
    fun moveInto(from: File, to: File, moved: MutableList<String>, failed: MutableList<String>) {
        if (!Files.exists(from.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        if (!Files.exists(to.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            runCatching { to.parentFile?.mkdirs(); Files.move(from.toPath(), to.toPath()) }
                .onSuccess { moved += "$from → $to" }.onFailure { failed += "$from → $to: ${it.message}" }
            return
        }
        if (!from.isDirectory || !to.isDirectory) { failed += "$from: $to is there already, so it was left"; return }
        from.listFiles().orEmpty().forEach { moveInto(it, File(to, it.name), moved, failed) }
        from.delete() // only an emptied folder goes; one still holding something stays
    }

    class Copies(val moved: List<String>, val left: List<String>, val failed: List<String>)
    /**
     * Sync's leftover copies, "name (2).ext" beside "name.ext" (made before the computer's fix of 29 Oct 2026): one holding
     * the same bytes moves to Backups/Data/Sync copies/<date>/, under the path sync knows it by; one that differs, or has no
     * original, stays and is listed (List.txt there, and [Copies.left]). Nothing is deleted. The computer's system.setAsideCopies.
     */
    fun setAsideCopies(tetra: File, today: String = java.time.LocalDate.now().toString(), dryRun: Boolean = false): Copies {
        val moved = mutableListOf<String>(); val left = mutableListOf<String>(); val failed = mutableListOf<String>()
        val to = File(dir(tetra, "Backups", "Data"), "Sync copies/$today")
        val copy = Regex("^(.+) \\(\\d+\\)(\\.[^.]+)?$")
        fun walk(folder: File, rel: String, top: Boolean) {
            for (f in folder.listFiles().orEmpty().sortedBy { it.name }) {
                if (f.name.startsWith(".") || Files.isSymbolicLink(f.toPath()) || (top && f.name in SHARED)) continue
                val r = if (rel.isEmpty()) f.name else "$rel/${f.name}"
                if (f.isDirectory) { walk(f, r, false); continue }
                val m = copy.matchEntire(f.name) ?: continue
                val original = File(folder, m.groupValues[1] + m.groupValues[2])
                if (!original.isFile || original.length() != f.length() || runCatching { !sameBytes(f, original) }.getOrDefault(true)) { left += r; continue }
                if (dryRun) { moved += r; continue }
                moveInto(f, File(to, r), mutableListOf(), failed)
                if (!f.exists()) moved += r
            }
        }
        walk(tetra, "", true)
        // not the Trash: its "(2)"s are its own, a name already there when something was deleted
        SHARED.forEach { (name, app) -> if (name != "Trash") walk(dir(tetra, app, "Shared"), name, false) }
        if (!dryRun && (moved.isNotEmpty() || left.isNotEmpty())) runCatching {
            to.mkdirs()
            File(to, "List.txt").writeText("Moved here (the same bytes as the file beside them):\n${moved.joinToString("\n")}\n\n" +
                "Left where they are (they differ from the file beside them, or it is not there):\n${left.joinToString("\n")}\n")
        }.onFailure { failed += "List.txt: ${it.message}" }
        return Copies(moved, left, failed)
    }

    private fun sameBytes(a: File, b: File): Boolean = a.inputStream().buffered().use { x -> b.inputStream().buffered().use { y ->
        var c: Int; var d: Int
        do { c = x.read(); d = y.read() } while (c == d && c >= 0)
        c == d
    } }
}

/** Files tools › System folders, kept in the "drive" preferences with when each folder was chosen or left out, for sync. */
object SystemFolderChoice {
    private fun prefs(context: Context) = context.getSharedPreferences("drive", Context.MODE_PRIVATE)
    private fun at(context: Context, name: String) = prefs(context).getLong("systemFolderAt.$name", 0)
    fun load(context: Context) { DriveRules.chosenFolders = prefs(context).getStringSet("systemFolders", null).orEmpty().toSet() }
    fun choose(context: Context, chosen: Set<String>) {
        val now = System.currentTimeMillis()
        val edit = prefs(context).edit().putStringSet("systemFolders", chosen)
        (chosen + DriveRules.chosenFolders).filter { (it in chosen) != (it in DriveRules.chosenFolders) }.forEach { edit.putLong("systemFolderAt.$it", now) }
        edit.apply()
        DriveRules.chosenFolders = chosen
    }
    fun records(context: Context) = DriveRules.OPTIONAL_FOLDERS.map { Triple(it, it in DriveRules.chosenFolders, at(context, it)) }
    /** One folder's choice from the computer; the chosen ones are made at once, so they are there before Files opens. */
    fun applyIncoming(context: Context, root: File, name: String, chosen: Boolean, updatedAt: Long) {
        if (name !in DriveRules.OPTIONAL_FOLDERS || !DriveRules.incomingChoiceWins(name in DriveRules.chosenFolders, at(context, name), chosen, updatedAt)) return
        val next = if (chosen) DriveRules.chosenFolders + name else DriveRules.chosenFolders - name
        prefs(context).edit().putStringSet("systemFolders", next).putLong("systemFolderAt.$name", updatedAt).apply()
        DriveRules.chosenFolders = next
        // Left out over there: an empty one goes here too; one with files stays, an ordinary folder, until its files follow.
        if (chosen) File(root, name).mkdirs() else File(root, name).takeIf { it.isDirectory }?.delete()
    }
}

object DriveRules {
    /**
     * Folders the app itself relies on: the scanner saves into Scanned Documents, the Calendar's calendars are the
     * folders in Calendar, and all sync by these paths. They
     * are always there, marked with an emblem, and cannot be renamed, moved or put in the Trash — their contents can.
     */
    private val REQUIRED_FOLDERS = listOf("Documents", "Documents/Scanned Documents", CalendarFiles.FOLDER)
    /**
     * Folders to sort things into, each with its own emblem (asked 16 Oct), chosen in Files tools › System folders.
     * A chosen one is a system folder like Documents; one no longer chosen stays, as an ordinary folder.
     */
    val OPTIONAL_FOLDERS = listOf("Music", "Videos", "Finance", "Work", "Education", "Games", "Development", "AI",
        "Health", "Travel", "Family", "Books", "Others")
    @Volatile var chosenFolders: Set<String> = emptySet()
    val SYSTEM_FOLDERS get() = REQUIRED_FOLDERS + OPTIONAL_FOLDERS.filter { it in chosenFolders }
    fun isSystem(relativePath: String) = relativePath in SYSTEM_FOLDERS
    /**
     * The apps' folders' names (TetraSystem.SHARED): in .System since 29 Oct 2026, synced under these names. One of them left at
     * the top of Tetra (a move that failed) is kept out of Files' lists, search and Move, as they all were since 18 Oct ("can we
     * hide the folders of the calendar and the timer"). The computer hides the same.
     */
    private val APP_FOLDERS = listOf(CalendarFiles.FOLDER, TimerFiles.FOLDER, MapsFiles.FOLDER, ContactsFiles.FOLDER, PhoneHistory.FOLDER, DataFiles.FOLDER, MailFiles.FOLDER, "AI Assist")
    fun isAppFolder(relativePath: String) = APP_FOLDERS.any { relativePath == it || relativePath.startsWith("$it/") }
    /**
     * Sync of the choice, folder by folder (the computer has the same rule): the later answer wins, and on a tie (a
     * choice made before there were times) chosen wins — a folder made on either side is a system folder on both,
     * and one left out on either side is not undone by the other (SYNC_PLAN.md 6g).
     */
    fun incomingChoiceWins(localChosen: Boolean, localAt: Long, chosen: Boolean, at: Long) = at > localAt || (at == localAt && chosen && !localChosen)
    fun ensureSystemFolders(root: File) = SYSTEM_FOLDERS.forEach { File(root, TetraSystem.onDisk(it)).mkdirs() }
    private fun requireMovable(relativePath: String) = relativePath.substringAfterLast('/').let { name -> need(!isSystem(relativePath), R.string.files_error_system_folder, name) { "$name is a system folder and stays where it is." } }

    fun inside(root: File, candidate: File): Boolean {
        val rootPath = root.canonicalFile.path
        val candidatePath = candidate.canonicalFile.path
        return candidatePath == rootPath || candidatePath.startsWith("$rootPath${File.separator}")
    }

    fun folder(root: File, relativePath: String): File {
        need(relativePath.isSafeDriveRelativePath(), R.string.files_error_bad_folder) { "Invalid Drive folder." }
        return File(root, TetraSystem.onDisk(relativePath)).canonicalFile.also {
            need(inside(root, it) && it.isDirectory, R.string.files_error_folder_outside) { "Folder is outside Drive." }
        }
    }

    fun file(root: File, relativePath: String): File {
        need(relativePath.isSafeDriveRelativePath(), R.string.files_error_bad_file) { "Invalid Drive file." }
        return File(root, TetraSystem.onDisk(relativePath)).canonicalFile.also {
            need(inside(root, it) && it.isFile, R.string.files_error_file_outside) { "File is outside Drive." }
        }
    }

    /**
     * A place inside Drive for something that is not there yet — where a synced file lands. The path is checked
     * the same way, and a name already taken is kept: "letter.txt" becomes "letter (2).txt", never a replacement.
     */
    fun newFile(root: File, relativePath: String): File {
        need(relativePath.isSafeDriveRelativePath() && relativePath.isNotEmpty(), R.string.files_error_bad_file) { "Invalid Drive file." }
        val wanted = File(root, TetraSystem.onDisk(relativePath)).canonicalFile
        need(inside(root, wanted), R.string.files_error_file_outside) { "File is outside Drive." }
        if (!wanted.exists()) return wanted
        val base = wanted.name.substringBeforeLast('.', wanted.name)
        val extension = wanted.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var n = 2
        while (true) {
            val candidate = File(wanted.parentFile, "$base ($n)$extension")
            if (!candidate.exists()) return candidate
            n++
        }
    }

    fun item(root: File, relativePath: String): File {
        need(relativePath.isSafeDriveRelativePath(), R.string.files_error_bad_item) { "Invalid Drive item." }
        return File(root, TetraSystem.onDisk(relativePath)).canonicalFile.also {
            need(inside(root, it) && it.exists(), R.string.files_error_item_outside) { "Item is outside Drive." }
        }
    }

    fun relative(root: File, file: File): String {
        need(inside(root, file), R.string.files_error_item_outside) { "Item is outside Drive." }
        return TetraSystem.fromDisk(file.canonicalFile.relativeTo(root.canonicalFile).invariantSeparatorsPath)
    }

    fun parent(relativePath: String): String = relativePath.substringBeforeLast('/', "")

    /** The folders right inside [relativePath], for Move and Copy one level at a time (asked 17 Oct: every level at once was too much); no hidden ones, no Trash. */
    fun subfolders(root: File, relativePath: String): List<String> = folder(root, relativePath).listFiles().orEmpty()
        .filter { it.isDirectory && !it.name.startsWith(".") && inside(root, it) }
        .map { relative(root, it) }
        .filterNot { it == TRASH_FOLDER || isAppFolder(it) }
        .sortedBy { it.lowercase(Locale.ROOT) }

    fun allItems(root: File, includeTrash: Boolean = false): List<DriveItem> = root.walkTopDown()
        .onEnter { folder -> inside(root, folder) && (includeTrash || folder == root || (relative(root, folder).let { it != TRASH_FOLDER && !isAppFolder(it) } && !folder.name.startsWith("."))) }
        .filter { it != root && it.exists() && inside(root, it) && (includeTrash || !it.name.startsWith(".")) }
        .map { DriveItem(it.canonicalFile, relative(root, it), it.isDirectory) }
        .toList()

    fun spaceUsage(root: File): DriveSpaceUsage = DriveSpaceUsage(
        allItems(root, includeTrash = true).asSequence().filterNot { it.isDirectory }
            .groupBy { fileTypeGroup(it.file.extension) }
            .mapValues { (_, items) -> items.sumOf { it.file.length() } },
    )

    fun rename(root: File, relativePath: String, newName: String): String {
        need(newName.isSafeDriveName(), R.string.files_error_bad_name) { "Invalid name." }
        requireMovable(relativePath)
        val source = item(root, relativePath)
        val target = File(source.parentFile, newName).canonicalFile
        moveExisting(root, source, target)
        return relative(root, target)
    }

    /**
     * What New can make (asked 14 Oct): a folder, each file in .System/Office/Data (Drive's hidden .templates until 29 Oct
     * 2026; copied as it is), and a text file and a Word document of Writer's own where it has none of that kind.
     */
    fun newKinds(root: File, blankDeck: ByteArray? = null): List<DriveNew> {
        val templates = TetraSystem.dir(root, "Office", "Data").listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") }.sortedBy { it.name.lowercase(Locale.ROOT) }
            .map { DriveNew(it.nameWithoutExtension, it.extension, it) }
        val built = listOfNotNull(DriveNew("Text file", "txt", shown = R.string.files_new_text), DriveNew("Words document", "docx", shown = R.string.files_new_words),
            DriveNew("Tables spreadsheet", "xlsx", shown = R.string.files_new_tables), blankDeck?.let { DriveNew("Slides presentation", "pptx", bytes = it, shown = R.string.files_new_slides) }).filter { b -> templates.none { it.extension.equals(b.extension, ignoreCase = true) } }
        return listOf(DriveNew("Folder", null, shown = R.string.files_folder)) + templates + built
    }

    /** [kind] named [name] in the folder [folderRelativePath]; its path. Never over something already there. */
    fun create(root: File, folderRelativePath: String, kind: DriveNew, name: String, firstSheet: String = "Sheet1"): String {
        val full = kind.extension?.takeIf { it.isNotEmpty() && !name.endsWith(".$it", ignoreCase = true) }?.let { "$name.$it" } ?: name
        need(full.isSafeDriveName() && !full.startsWith("."), R.string.files_error_bad_name) { "Invalid name." }
        val target = File(folder(root, folderRelativePath), full).canonicalFile
        need(inside(root, target), R.string.files_error_bad_name) { "Invalid name." }
        need(!target.exists(), R.string.files_error_name_taken, full) { "Something named $full is already here." }
        when {
            kind.extension == null -> need(target.mkdir(), R.string.files_error_make_folder) { "Could not make the folder." }
            kind.template != null -> Files.copy(kind.template.toPath(), target.toPath())
            kind.extension.equals("docx", ignoreCase = true) -> target.writeBytes(OfficeFormats.write(OfficeDoc(listOf(DocPara(emptyList()))), OfficeKind.Docx))
            kind.extension.equals("xlsx", ignoreCase = true) -> target.writeBytes(SheetBook.empty(firstSheet))
            kind.bytes != null -> target.writeBytes(kind.bytes)
            else -> target.writeBytes(ByteArray(0))
        }
        return relative(root, target)
    }

    fun copy(root: File, relativePath: String, destinationRelativePath: String): String {
        val source = item(root, relativePath)
        val destination = folder(root, destinationRelativePath)
        need(!source.isDirectory || !inside(source, destination), R.string.files_error_copy_into_itself) { "A folder cannot be copied into itself." }
        val target = File(destination, source.name).canonicalFile
        need(inside(root, target) && !target.exists(), R.string.files_error_name_exists) { "A file with this name already exists." }
        requireNoLinks(source)
        if (source.isDirectory) source.copyRecursively(target, overwrite = false) else Files.copy(source.toPath(), target.toPath())
        return relative(root, target)
    }

    fun move(root: File, relativePath: String, destinationRelativePath: String): String {
        requireMovable(relativePath)
        val source = item(root, relativePath)
        val destination = folder(root, destinationRelativePath)
        need(!source.isDirectory || !inside(source, destination), R.string.files_error_move_into_itself) { "A folder cannot be moved into itself." }
        val target = File(destination, source.name).canonicalFile
        moveExisting(root, source, target)
        return relative(root, target)
    }

    /**
     * After a Move, the regular folders it left empty go too; a system folder (Documents, Scanned Documents) and
     * Trash always stay — the app needs them (asked 2026-09-25). Walks up from each folder given.
     */
    fun removeEmptyFolders(root: File, folders: Collection<String>) {
        for (start in folders) {
            var rel = start
            while (rel.isNotEmpty() && !isSystem(rel) && rel != TRASH_FOLDER && !rel.startsWith("$TRASH_FOLDER/")) {
                val dir = File(root, TetraSystem.onDisk(rel)).canonicalFile
                if (!inside(root, dir) || !dir.isDirectory || dir.list().orEmpty().isNotEmpty() || !dir.delete()) break
                rel = parent(rel)
            }
        }
    }

    fun moveToTrash(root: File, relativePath: String): String {
        val trash = File(root, TetraSystem.onDisk(TRASH_FOLDER)).canonicalFile
        need(inside(root, trash), R.string.files_error_trash_outside) { "Trash is outside Drive." }
        if (!trash.exists()) need(trash.mkdirs(), R.string.files_error_make_trash) { "Could not create Trash." }
        requireMovable(relativePath)
        val source = item(root, relativePath)
        need(!inside(trash, source), R.string.files_error_already_in_trash) { "This item is already in Trash." }
        val target = freeTarget(trash, source)
        moveExisting(root, source, target)
        return relative(root, target)
    }

    /** A name already in [dir] never blocks a move there: the newcomer becomes "name (2)", as everywhere in Drive. */
    private fun freeTarget(dir: File, source: File): File {
        var target = File(dir, source.name)
        for (n in 2..Int.MAX_VALUE) {
            if (!target.exists()) break
            target = File(dir, source.nameWithoutExtension + " ($n)" + source.name.removePrefix(source.nameWithoutExtension))
        }
        return target.canonicalFile
    }

    /**
     * A folder just left out of System folders goes (asked 17 Oct): what is in it to the top of Tetra, or the folder
     * whole to the Trash, as the user answered. Returns each move (from, to), so favorites and tags follow.
     */
    fun clearLeftOut(root: File, name: String, toTrash: Boolean): List<Pair<String, String>> {
        need(name in OPTIONAL_FOLDERS && !isSystem(name), R.string.files_error_still_system, name) { "$name is still a system folder." }
        val dir = File(root, name).canonicalFile
        if (!inside(root, dir) || !dir.isDirectory) return emptyList()
        val entries = dir.listFiles().orEmpty()
        if (toTrash && entries.isNotEmpty()) return listOf(name to moveToTrash(root, name))
        val moved = entries.map { entry ->
            val from = relative(root, entry)
            val target = freeTarget(root.canonicalFile, entry)
            moveExisting(root, entry.canonicalFile, target)
            from to relative(root, target)
        }
        dir.delete()
        return moved
    }

    fun emptyTrash(root: File): Int {
        val trash = File(root, TetraSystem.onDisk(TRASH_FOLDER)).canonicalFile
        need(inside(root, trash), R.string.files_error_trash_outside) { "Trash is outside Drive." }
        if (!trash.exists()) return 0
        val items = trash.listFiles().orEmpty()
        items.forEach { item -> need(inside(root, item), R.string.files_error_unsafe_in_trash) { "Trash contains an unsafe item." }; requireNoLinks(item) }
        items.forEach { item -> need(item.deleteRecursively(), R.string.files_error_delete, item.name) { "Could not permanently delete ${item.name}." } }
        return items.size
    }

    // copyRecursively and deleteRecursively follow links, so one inside a folder would copy in or delete what it points at.
    private fun requireNoLinks(item: File) =
        need(item.walkTopDown().none { Files.isSymbolicLink(it.toPath()) }, R.string.files_error_link, item.name) { "${item.name} holds a link, which Tetra does not follow." }

    private fun moveExisting(root: File, source: File, target: File) {
        need(inside(root, target) && target != source && !target.exists(), R.string.files_error_name_exists) { "A file with this name already exists." }
        Files.move(source.toPath(), target.toPath())
    }
}

fun fileTypeGroup(extension: String): String = when (extension.lowercase(Locale.ROOT)) {
    "doc", "docx", "odt", "rtf" -> "Documents"
    "xls", "xlsx", "csv" -> "Spreadsheets"
    "pdf" -> "PDF"
    "txt", "md" -> "Text"
    "excalidraw" -> "Drawings"
    "jpg", "jpeg", "png", "webp", "gif", "heic", "heif" -> "Pictures"
    "mp4", "mov", "mkv", "avi", "webm" -> "Videos"
    else -> "Other"
}

private fun String.isSafeDriveRelativePath(): Boolean = isEmpty() ||
    (!startsWith('/') && split('/').all { it.isNotEmpty() && it != "." && it != ".." })

private fun String.isSafeDriveName(): Boolean = isNotBlank() && trim() == this &&
    this !in setOf(".", "..") && '/' !in this && '\\' !in this

private const val TRASH_FOLDER = "Trash"

/** Something New makes: a folder ([extension] null), a copy of [template], or Writer's own empty file of [extension]. */
data class DriveNew(val label: String, val extension: String?, val template: File? = null, /** the file's bytes as made (the app's empty deck) */ val bytes: ByteArray? = null,
    /** what New calls it in the user's language; null: a template, called by its file name */ @androidx.annotation.StringRes val shown: Int? = null)

/**
 * Files just made with New (asked 16 Oct: "enter edit mode automatically, he does not want to see an empty page"):
 * Writer, Excel and PowerPoint open them editing, once.
 */
internal object JustMade {
    private val paths = HashSet<String>()
    fun mark(file: File) = synchronized(paths) { paths += file.absolutePath }
    /** Whether [open] (a file:// address) was just made; asked once, it is then a file like the others. */
    fun take(open: String?): Boolean = synchronized(paths) { open?.let { android.net.Uri.parse(it).path }?.let { paths.remove(it) } == true }
}

fun listDriveFolder(root: File, relativePath: String): List<DriveItem> {
    val folder = DriveRules.folder(root, relativePath) // its real path, inside Tetra
    val prefix = DriveRules.relative(root, folder).let { if (it.isEmpty()) "" else "$it/" }
    return folder.listFiles().orEmpty()
        // Dot folders (.System, the apps' own) are hidden as in file managers and on the desktop.
        .filter { !it.name.startsWith(".") }
        // Trash has its own way in; as a folder in the list it is just something to open by accident.
        .filterNot { relativePath.isEmpty() && (it.name == TRASH_FOLDER || DriveRules.isAppFolder(it.name)) }
        // one look at each (25 Oct: each one's real path asked four times made a folder of thousands slow); a link is
        // followed and checked to stay inside Tetra, as every item was before
        .mapNotNull { file ->
            val a = runCatching { Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.getOrNull()
            if (a == null || a.isSymbolicLink) file.takeIf { DriveRules.inside(root, it) }?.let { DriveItem(it.canonicalFile, DriveRules.relative(root, it), it.isDirectory) }
            else DriveItem(file, prefix + file.name, a.isDirectory, a.lastModifiedTime().toMillis(), a.size())
        }
        // Mail's folders (reached by «Αποθήκη»): each mail beside its attachments' folder, not after hundreds of ".files"
        // folders that hid the mails below them (31 Oct: "it only holds attachments, no mails")
        .sortedWith(if (relativePath == MailFiles.FOLDER || relativePath.startsWith(MailFiles.FOLDER + "/")) compareBy { it.file.name.lowercase(Locale.ROOT) }
            else compareBy<DriveItem>({ !it.isDirectory }, { it.file.name.lowercase(Locale.ROOT) }))
}

/**
 * Says when something under Tetra changed — a file pushed, synced, saved by another app, renamed or deleted — so
 * Files and its search show it without a refresh. Android watches one folder at a time, so this watches every
 * visible folder, and looks for folders again only when one is added: on the tablet that look takes seconds
 * (700 folders), and while it runs nothing is watched, so the list is read once more after it.
 */
internal class DriveWatcher(private val root: File, private val changed: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var observer: FileObserver? = null
    @Volatile private var stopped = false
    private var newFolder = false
    private val settle = Runnable {
        changed()
        if (newFolder) {
            newFolder = false
            Thread { watch(); main.post { if (!stopped) changed() } }.start()
        }
    }

    init { Thread(::watch).start() }

    private fun watch() {
        val folders = root.walkTopDown().onEnter { it == root || !it.name.startsWith(".") }.filter { it.isDirectory }.toList()
        synchronized(this) { if (!stopped) start(folders) }
    }

    private fun start(folders: List<File>) {
        observer?.stopWatching()
        observer = object : FileObserver(folders, CREATE or DELETE or MOVED_FROM or MOVED_TO or CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null || path.startsWith(".")) return // the apps' own dot files are not in the list
                val folderArrived = (event and IS_FOLDER) != 0 && (event and (CREATE or MOVED_TO)) != 0
                main.post {
                    if (stopped) return@post
                    if (folderArrived) newFolder = true
                    main.removeCallbacks(settle)
                    main.postDelayed(settle, 300) // a copy is many writes; answer once it settles
                }
            }
        }.also { it.startWatching() }
    }

    private companion object { const val IS_FOLDER = 0x40000000 } // inotify's IN_ISDIR, which FileObserver passes on

    @Synchronized fun stop() {
        stopped = true
        main.removeCallbacks(settle)
        observer?.stopWatching()
    }
}

object DriveRecentsRules {
    fun record(existing: List<DriveRecent>, relativePath: String, openedAt: Long): List<DriveRecent> =
        (listOf(DriveRecent(relativePath, openedAt)) + existing.filterNot { it.relativePath == relativePath }).take(RECENTS_LIMIT)
}

class DriveRecents(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(RECENTS_PREFS, Context.MODE_PRIVATE)

    fun read(root: File): List<DriveRecent> {
        val stored = runCatching { JSONArray(preferences.getString(RECENTS_KEY, "[]")) }.getOrElse { JSONArray() }
        val valid = buildList {
            for (index in 0 until stored.length()) {
                val item = stored.optJSONObject(index) ?: continue
                val path = item.optString("path")
                val openedAt = item.optLong("openedAt")
                if (openedAt > 0 && runCatching { DriveRules.file(root, path) }.isSuccess) add(DriveRecent(path, openedAt))
            }
        }.distinctBy { it.relativePath }.sortedByDescending { it.openedAt }.take(RECENTS_LIMIT)
        save(valid)
        return valid
    }

    fun record(root: File, relativePath: String, openedAt: Long) {
        DriveRules.file(root, relativePath)
        save(DriveRecentsRules.record(read(root), relativePath, openedAt))
    }

    /**
     * Stored recents, without pruning to what is on this device: a file opened on the computer is still a recent
     * file, and each side drops the entries it cannot see when it reads them (`read`).
     */
    fun all(): List<DriveRecent> {
        val stored = runCatching { JSONArray(preferences.getString(RECENTS_KEY, "[]")) }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until stored.length()) {
                val item = stored.optJSONObject(index) ?: continue
                val openedAt = item.optLong("openedAt")
                if (openedAt > 0) add(DriveRecent(item.optString("path"), openedAt))
            }
        }
    }

    /** The most recent open of each file wins, whichever device it happened on. */
    fun merge(incoming: List<DriveRecent>) {
        if (incoming.isEmpty()) return
        save((all() + incoming.filter { it.relativePath.isSafeDriveRelativePath() })
            .groupBy { it.relativePath }.map { (path, opens) -> DriveRecent(path, opens.maxOf { it.openedAt }) }
            .sortedByDescending { it.openedAt }.take(RECENTS_LIMIT))
    }

    private fun save(items: List<DriveRecent>) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("path", it.relativePath).put("openedAt", it.openedAt)) }
        preferences.edit().putString(RECENTS_KEY, array.toString()).apply()
    }
}

/** One file's or folder's user metadata, as it travels between the phone and the computer. */
data class DriveMetaRecord(val path: String, val favorite: Boolean, val color: String?, val tags: Set<String>, val updatedAt: Long)

class DriveMetadata(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("drive_metadata", Context.MODE_PRIVATE)

    fun isFavorite(relativePath: String): Boolean = favorites().contains(relativePath)

    fun setFavorite(relativePath: String, favorite: Boolean) {
        need(relativePath.isSafeDriveRelativePath(), R.string.files_error_bad_item) { "Invalid Drive item." }
        val updated = favorites()
        if (favorite) updated.add(relativePath) else updated.remove(relativePath)
        preferences.edit().putStringSet("favorites", updated).apply()
        touch(relativePath)
    }

    fun color(relativePath: String): DriveFolderColor? = colors()[relativePath]?.let { runCatching { DriveFolderColor.valueOf(it) }.getOrNull() }

    fun setColor(relativePath: String, color: DriveFolderColor?) {
        need(relativePath.isSafeDriveRelativePath(), R.string.files_error_bad_folder) { "Invalid Drive folder." }
        val updated = colors()
        if (color == null) updated.remove(relativePath) else updated[relativePath] = color.name
        saveColors(updated)
        touch(relativePath)
    }

    fun tags(relativePath: String): Set<String> = tagsByPath()[relativePath].orEmpty()

    fun createTag(name: String): String {
        val cleaned = DriveTagRules.name(name)
        preferences.edit().putStringSet("known_tags", knownTags() + cleaned).apply()
        return cleaned
    }

    fun setTags(relativePath: String, names: Set<String>) {
        need(relativePath.isSafeDriveRelativePath(), R.string.files_error_bad_item) { "Invalid Drive item." }
        val cleaned = DriveTagRules.names(names)
        val updated = tagsByPath().toMutableMap()
        if (cleaned.isEmpty()) updated.remove(relativePath) else updated[relativePath] = cleaned
        saveTags(updated)
        preferences.edit().putStringSet("known_tags", knownTags() + cleaned).apply()
        touch(relativePath)
    }

    fun allTags(root: File): List<String> = (knownTags() + tagsByPath().filterKeys { path ->
        path != TRASH_FOLDER && !path.startsWith("$TRASH_FOLDER/") && runCatching { DriveRules.item(root, path) }.isSuccess
    }
        .values.flatten()).sortedWith(String.CASE_INSENSITIVE_ORDER)

    fun favorites(root: File): List<DriveItem> {
        val valid = favorites().filter { runCatching { DriveRules.item(root, it) }.isSuccess }.toMutableSet()
        preferences.edit().putStringSet("favorites", valid).apply()
        return valid.filterNot { it == TRASH_FOLDER || it.startsWith("$TRASH_FOLDER/") }.map { relative ->
            val file = DriveRules.item(root, relative)
            DriveItem(file, relative, file.isDirectory)
        }.sortedBy { it.file.name.lowercase(Locale.ROOT) }
    }

    fun rewritePath(from: String, to: String) {
        preferences.edit().putStringSet("favorites", favorites().map { DriveStoredPathRules.rewrite(it, from, to) }.toSet()).apply()
        saveColors(colors().mapKeys { DriveStoredPathRules.rewrite(it.key, from, to) })
        saveTags(tagsByPath().mapKeys { DriveStoredPathRules.rewrite(it.key, from, to) })
        saveTimes(times().mapKeys { DriveStoredPathRules.rewrite(it.key, from, to) })
        touch(to)
    }

    // --- Sync (SYNC_PLAN.md phase 6d). Files are keyed by their Drive-relative path on both devices, so what a
    // record needs beyond its values is only a time: the newest edit of a path wins, and a tag that the newer
    // side's set no longer holds has been removed. Moving to Drive/Trash/ is a path change like any other.

    /** Everything the user set on a file or folder, one record per path. */
    fun records(): List<DriveMetaRecord> {
        val favorites = favorites()
        val colors = colors()
        val tags = tagsByPath()
        val times = times()
        return (favorites + colors.keys + tags.keys + times.keys).distinct().map { path ->
            DriveMetaRecord(path, path in favorites, colors[path], tags[path].orEmpty(), times[path] ?: 0L)
        }
    }

    /** From the computer. Applied only where its edit is newer than this phone's (last-write-wins per path). */
    fun applyIncoming(record: DriveMetaRecord) {
        if (!record.path.isSafeDriveRelativePath() || record.updatedAt <= (times()[record.path] ?: -1L)) return
        val favorites = favorites()
        if (record.favorite) favorites.add(record.path) else favorites.remove(record.path)
        val colors = colors()
        if (record.color == null) colors.remove(record.path) else colors[record.path] = record.color
        val tags = tagsByPath().toMutableMap()
        val cleaned = runCatching { DriveTagRules.names(record.tags) }.getOrDefault(emptySet())
        if (cleaned.isEmpty()) tags.remove(record.path) else tags[record.path] = cleaned
        preferences.edit().putStringSet("favorites", favorites).putStringSet("known_tags", knownTags() + cleaned).apply()
        saveColors(colors)
        saveTags(tags)
        saveTimes(times() + (record.path to record.updatedAt))
    }

    private fun touch(relativePath: String) = saveTimes(times() + (relativePath to System.currentTimeMillis()))

    private fun times(): Map<String, Long> = runCatching { JSONObject(preferences.getString("updated_at", "{}") ?: "{}") }
        .getOrElse { JSONObject() }.let { json -> buildMap { json.keys().forEach { put(it, json.optLong(it)) } } }

    private fun saveTimes(times: Map<String, Long>) {
        val json = JSONObject()
        times.forEach { (path, at) -> json.put(path, at) }
        preferences.edit().putString("updated_at", json.toString()).apply()
    }

    private fun favorites(): MutableSet<String> = preferences.getStringSet("favorites", emptySet()).orEmpty().toMutableSet()
    private fun knownTags(): Set<String> = preferences.getStringSet("known_tags", emptySet()).orEmpty().mapNotNull { name ->
        runCatching { DriveTagRules.name(name) }.getOrNull()
    }.toSet()
    private fun colors(): MutableMap<String, String> = runCatching { JSONObject(preferences.getString("colors", "{}") ?: "{}") }.getOrElse { JSONObject() }
        .let { json -> buildMap { json.keys().forEach { key -> put(key, json.optString(key)) } }.toMutableMap() }
    private fun saveColors(colors: Map<String, String>) {
        val json = JSONObject()
        colors.forEach { (path, color) -> json.put(path, color) }
        preferences.edit().putString("colors", json.toString()).apply()
    }

    /** Every folder's colour and every item's tags at once: one read for a whole list, not one per row. */
    fun colorMap(): Map<String, DriveFolderColor> = colors().mapNotNull { (k, v) -> runCatching { k to DriveFolderColor.valueOf(v) }.getOrNull() }.toMap()
    fun tagMap(): Map<String, Set<String>> = tagsByPath()

    private fun tagsByPath(): Map<String, Set<String>> = runCatching {
        val json = JSONObject(preferences.getString("tags", "{}") ?: "{}")
        buildMap {
            json.keys().forEach { path ->
                val values = json.optJSONArray(path) ?: return@forEach
                put(path, (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotBlank) }.toSet())
            }
        }
    }.getOrDefault(emptyMap())

    private fun saveTags(tags: Map<String, Set<String>>) {
        val json = JSONObject()
        tags.forEach { (path, names) -> json.put(path, JSONArray(names.toList())) }
        preferences.edit().putString("tags", json.toString()).apply()
    }
}

/** A file of the Files module as it is offered to the computer (SYNC_PLAN.md phase 6e). */
data class DriveFileEntry(val relativePath: String, val sizeBytes: Long, val modified: Long, val sha256: String)

/**
 * Every file under Drive, hashed once and remembered against its size and modified time. Content is the
 * identity, so the computer can tell a moved or renamed file from a new one and move its own copy to match —
 * which is also how a move into Drive/Trash/ reaches the other device.
 */
/**
 * A part of Drive that syncs on its own, at once (the Calendar, 18 Oct: "send it imediatly across devices as soon as
 * connection available", as Notes do): its folders, and its kinds of file in the Trash (deleting is a move there). The
 * computer reads the same names (desktop files.js SCOPES); a new one (the Timer) is a line in each.
 */
internal object DriveScope {
    private val scopes = mapOf("calendar" to (listOf("Calendar") to listOf(".ics")), "timer" to (listOf(TimerFiles.FOLDER) to emptyList<String>()),
        "contacts" to (listOf(ContactsFiles.FOLDER) to listOf(".vcf")), "phone" to (listOf(PhoneHistory.FOLDER) to emptyList<String>()), "data" to (listOf(DataFiles.FOLDER) to emptyList<String>()),
        "mail" to (listOf("Mail/requests", "Mail/outbox") to emptyList<String>()), "assistant" to (listOf("AI Assist") to emptyList<String>()),
        "browser" to (listOf(BrowserSync.FOLDER) to emptyList<String>()), "pills" to (listOf("Pills") to emptyList<String>()))
    fun contains(scope: String, relative: String): Boolean {
        val (dirs, trashExts) = scopes[scope] ?: return false
        return dirs.any { relative.startsWith("$it/") } || (relative.startsWith("Trash/") && trashExts.any { relative.lowercase().endsWith(it) })
    }
}

class DriveManifest(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("drive_hashes", Context.MODE_PRIVATE)

    /** Every file under [root], or only those [keep] takes (a scoped sync: nothing else is read or hashed). */
    fun entries(root: File, keep: (String) -> Boolean = { true }): List<DriveFileEntry> =
        (TetraSystem.SHARED.keys.flatMap { walk(root, File(root, TetraSystem.onDisk(it))) } + walk(root, root))
        .map { it to DriveRules.relative(root, it) }.filter { (_, relative) -> keep(relative) }
        .map { (file, relative) -> DriveFileEntry(relative, file.length(), file.lastModified(), hash(relative, file)) }

    /** The apps' folders are walked where they are (.System): one of those names left at the top of Tetra (a move that failed) is not theirs. */
    private fun walk(root: File, folder: File): List<File> = folder.listFiles().orEmpty()
        .filter { DriveRules.inside(root, it) && !it.name.startsWith(".") && !(folder == root && it.name in TetraSystem.SHARED) }
        .flatMap { if (it.isDirectory) walk(root, it) else listOf(it) }

    /** What was just put at [relativePath] is [sha256]: its date is the sender's, so size and date alone could match an older version. */
    fun remember(relativePath: String, file: File, sha256: String) {
        preferences.edit().putString(relativePath, "${stamp(file)}|$sha256").apply()
    }

    private fun stamp(file: File) = "${file.length()}:${file.lastModified()}"

    private fun hash(relativePath: String, file: File): String {
        val stamp = stamp(file)
        preferences.getString(relativePath, null)?.split('|')?.takeIf { it.size == 2 && it[0] == stamp }?.let { return it[1] }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return SyncRules.hex(digest.digest()).also { preferences.edit().putString(relativePath, "$stamp|$it").apply() }
    }
}

object DriveTagRules {
    fun name(value: String): String = value.trim().also {
        need(it.isNotEmpty() && it.length <= 32 && ',' !in it, R.string.files_error_tag_length) { "Tags must be 1–32 characters and cannot contain commas." }
    }

    fun names(values: Set<String>): Set<String> {
        val cleaned = values.map(::name).toSet()
        need(cleaned.size == values.size, R.string.files_error_tag_unique) { "Tags must be unique." }
        return cleaned
    }
}

object DriveStoredPathRules {
    fun rewrite(path: String, from: String, to: String): String = when {
        path == from -> to
        path.startsWith("$from/") -> "$to${path.removePrefix(from)}"
        else -> path
    }
}

/** Stores the explicit Android handler selected for one Drive-relative file path. */
class DriveOpeners(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(OPENERS_PREFS, Context.MODE_PRIVATE)

    /** This file's own app first, else the app last chosen for its type (asked 2026-09-26: "remember my preference"). */
    fun component(relativePath: String): ComponentName? {
        need(relativePath.isSafeDriveRelativePath(), R.string.files_error_bad_file) { "Invalid Drive file." }
        return (preferences.getString(relativePath, null) ?: preferences.getString(typeKey(relativePath), null))
            ?.let(ComponentName::unflattenFromString)
    }

    fun set(relativePath: String, component: ComponentName) {
        need(relativePath.isSafeDriveRelativePath(), R.string.files_error_bad_file) { "Invalid Drive file." }
        preferences.edit().putString(relativePath, component.flattenToString()).putString(typeKey(relativePath), component.flattenToString()).commit()
    }

    /** The remembered app is gone: forget it for this file and for its type, so the chooser asks again. */
    fun clear(relativePath: String) {
        need(relativePath.isSafeDriveRelativePath(), R.string.files_error_bad_file) { "Invalid Drive file." }
        preferences.edit().remove(relativePath).remove(typeKey(relativePath)).apply()
    }

    // A '/' first can never be a Drive path, so a type key never collides with a file's.
    private fun typeKey(relativePath: String) = "/type/" + relativePath.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun rewritePath(from: String, to: String) {
        val edits = preferences.edit()
        preferences.all.forEach { (path, value) ->
            if (value !is String || path.startsWith("/")) return@forEach
            val rewritten = DriveStoredPathRules.rewrite(path, from, to)
            if (rewritten != path) {
                edits.remove(path)
                edits.putString(rewritten, value)
            }
        }
        edits.apply()
    }
}

class DriveOpenerReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != FILE_OPENER_CHOSEN) return
        val path = intent.getStringExtra(FILE_OPENER_PATH) ?: return
        val component = intent.getParcelableExtra<ComponentName>(Intent.EXTRA_CHOSEN_COMPONENT) ?: return
        if (path.isSafeDriveRelativePath()) DriveOpeners(context).set(path, component)
    }
}
