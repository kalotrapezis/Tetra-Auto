package com.kalotrapezis.drive

import android.content.Context

/**
 * One part of Home: large (its group, as before), medium (its icon alone, big, in four cells; the user, 24 Oct), small (just
 * its icon, like a launcher's) or hidden, at a cell of Home's grid (col, row; -1 until it has one).
 */
internal enum class HomeSize { Large, Medium, Small }

internal data class HomeItem(val id: String, val size: HomeSize = HomeSize.Large, val hidden: Boolean = false, val col: Int = -1, val row: Int = -1) {
    val small get() = size == HomeSize.Small
}

/**
 * Home's layout on this device (the user, 24 Oct: "like a launcher … a grid that expands, to drag and drop where I want
 * them"): a grid of cells, 16 across on a tablet and 8 on a phone (8 Nov: twice as fine, "a grid with higher definition"),
 * growing down as far as the parts go. A small part takes 2 by 2, a medium one 4 by 4; a large one the room its content
 * needs (8 Nov: "no need for every widget to be the same size", LARGE_WIDE / LARGE_NARROW); Local Sync and Settings two rows. A part
 * dropped where others are moves them on to the next free cells. Hidden is only hidden (asked 24 Oct): reminders,
 * timers and sync go on. Kept in this device's preferences, one layout for each grid width, not synced; Settings can
 * never be hidden, or there would be no way back.
 */
internal object HomeLayout {
    const val SETTINGS = "settings"
    private val CARDS = setOf("sync", SETTINGS, "assistant") // two rows high (one row of the old grid)
    /** A large part's cells on a tablet (16 across), by what it shows: lists taller, a few tiles square. */
    // always more than a medium part's 4 by 4 (8 Nov: "some have no large now, Notes and Data"): the large one shows more
    private val LARGE_WIDE = mapOf("mail" to (6 to 6), "calendar" to (6 to 6), "pills" to (6 to 4), "notes" to (4 to 8), "text" to (6 to 4), "files" to (6 to 4),
        "timer" to (6 to 4), "data" to (6 to 4), "contacts" to (6 to 4), "phone" to (6 to 4), "office" to (6 to 4), "maps" to (6 to 4), "browser" to (6 to 4), "passwords" to (6 to 4),
        "sms" to (6 to 2), "sync" to (4 to 2), SETTINGS to (4 to 2), "assistant" to (8 to 2))
    /** On a phone (8 across): the whole width (a medium part is the square), lists taller. */
    private val LARGE_NARROW = mapOf("mail" to (8 to 6), "calendar" to (8 to 6), "notes" to (4 to 8), "sms" to (8 to 2))
    // today's order: a tablet pairs Sync with Settings at the top; a phone has Maps before Contacts and Settings last
    val WIDE = listOf("sync", SETTINGS, "gallery", "files", "notes", "scanner", "office", "text", "calendar", "timer", "contacts", "phone", "maps", "data", "assistant", "mail", "sms", "browser", "pills", "passwords")
    val NARROW = listOf("sync", "gallery", "files", "notes", "scanner", "office", "text", "calendar", "timer", "maps", "contacts", "phone", "data", "assistant", "mail", "sms", "browser", "pills", "passwords", SETTINGS)

    /** Each part's name (a string resource). */
    val NAMES = mapOf("sync" to R.string.home_local_sync, SETTINGS to R.string.common_settings, "gallery" to R.string.app_photos, "files" to R.string.app_files, "notes" to R.string.app_notes,
        "scanner" to R.string.home_scanner_codes, "office" to R.string.app_office, "text" to R.string.app_text_editor, "calendar" to R.string.app_calendar, "timer" to R.string.app_timer,
        "contacts" to R.string.app_contacts, "phone" to R.string.app_phone, "maps" to R.string.app_maps, "data" to R.string.app_data, "assistant" to R.string.app_assistant, "mail" to R.string.app_mail, "sms" to R.string.app_sms, "browser" to R.string.app_browser, "pills" to R.string.app_pills, "passwords" to R.string.app_passwords)

    fun columns(wide: Boolean) = if (wide) 16 else 8
    private fun prefs(c: Context) = c.getSharedPreferences("home", Context.MODE_PRIVATE)
    fun load(c: Context, wide: Boolean) = columns(wide).let { cols ->
        // a layout of the old, half as fine grid (grid8, grid4) comes across once, every cell doubled
        val saved = prefs(c).getString("grid$cols", null) ?: prefs(c).getString("grid${cols / 2}", null)?.let { doubled(it) }
        place(parse(saved, if (wide) WIDE else NARROW), cols)
    }
    /** A saved layout's cells twice as fine: "id:flags:col:row" with col and row doubled (-1, unplaced, stays). */
    fun doubled(saved: String) = saved.split(',').filter { it.isNotBlank() }.joinToString(",") { part ->
        part.split(':').mapIndexed { i, v -> if (i >= 2) v.toIntOrNull()?.let { n -> if (n < 0) "$n" else "${n * 2}" } ?: v else v }.joinToString(":")
    }
    fun save(c: Context, wide: Boolean, items: List<HomeItem>) = prefs(c).edit().putString("grid${columns(wide)}", format(items)).apply()
    fun reset(c: Context, wide: Boolean) = prefs(c).edit().remove("grid${columns(wide)}").remove("grid${columns(wide) / 2}").apply() // the old grid's too, or it would come back

    /** "id:flags:col:row" (flags s small, m medium, h hidden), comma-separated; unknown ids dropped, a new part added small and unplaced (small icons only by default; the user, 25 Oct). */
    fun parse(saved: String?, defaults: List<String>): List<HomeItem> {
        val items = saved.orEmpty().split(',').filter { it.isNotBlank() }.map { part ->
            val p = part.split(':')
            val id = p[0]; val flags = p.getOrElse(1) { "" }
            HomeItem(id, size = if ('s' in flags) HomeSize.Small else if ('m' in flags) HomeSize.Medium else HomeSize.Large, hidden = 'h' in flags && id != SETTINGS, col = p.getOrNull(2)?.toIntOrNull() ?: -1, row = p.getOrNull(3)?.toIntOrNull() ?: -1)
        }.filter { it.id in defaults }.distinctBy { it.id }
        return items + defaults.filter { id -> items.none { it.id == id } }.map { HomeItem(it, size = HomeSize.Small) }
    }

    fun format(items: List<HomeItem>) = items.joinToString(",") { "${it.id}:${when (it.size) { HomeSize.Small -> "s"; HomeSize.Medium -> "m"; else -> "" }}${if (it.hidden) "h" else ""}:${it.col}:${it.row}" }

    /** A part's width and height in cells. */
    fun size(item: HomeItem, cols: Int): Pair<Int, Int> = when {
        item.small -> (if (item.id == "scanner") 4 else 2) to 2 // Scanner & Codes: two apps, two icons
        item.size == HomeSize.Medium -> 4 to 4 // a widget now (8 Nov), Scanner's too: one square
        else -> (if (cols >= 16) LARGE_WIDE else LARGE_NARROW)[item.id] ?: ((if (cols >= 16) cols / 2 else cols) to (if (item.id in CARDS) 2 else 4))
    }.let { (w, h) -> w.coerceAtMost(cols) to h }

    /** How many rows the shown parts take. */
    fun height(items: List<HomeItem>, cols: Int) = items.filter { !it.hidden }.maxOfOrNull { it.row + size(it, cols).second } ?: 0

    /**
     * The grid flows as a phone's (the user, 8 Nov: "fill the gaps automatically, pushing or pulling icons when I resize"):
     * the shown parts in reading order — [first] ahead of any part at its cell, parts with no cell yet last — each at the
     * first cell from the top where it fits. A part made bigger pushes those after it on, one made smaller or hidden pulls
     * them up; no gap is left that a later part could fill.
     */
    fun flow(items: List<HomeItem>, cols: Int, first: String? = null): List<HomeItem> {
        val grid = Grid(cols)
        val order = items.filter { !it.hidden }.sortedWith(compareBy({ it.row < 0 || it.col < 0 }, { it.row }, { it.col }, { it.id != first }))
        val placed = order.associate { it.id to grid.firstFree(it, 0, 0).let { (c, r) -> it.copy(col = c, row = r).also { i -> grid.take(i, c, r) } } }
        return items.map { placed[it.id] ?: it }
    }

    /** Every shown part at a cell of its own, the grid flowed (a saved layout with gaps closes them once). */
    fun place(items: List<HomeItem>, cols: Int): List<HomeItem> = flow(items, cols)

    /** `id` dropped at (col, row): it takes its place in the reading order there, and the grid flows around it. */
    fun drop(items: List<HomeItem>, id: String, col: Int, row: Int, cols: Int): List<HomeItem> {
        val moved = items.first { it.id == id }
        val dropped = moved.copy(col = col.coerceIn(0, cols - size(moved, cols).first), row = row.coerceAtLeast(0))
        return flow(items.map { if (it.id == id) dropped else it }, cols, first = id)
    }

    /** One part changed (resized, hidden, shown): the grid flows again. */
    fun change(items: List<HomeItem>, item: HomeItem, cols: Int): List<HomeItem> = flow(items.map { if (it.id == item.id) item else it }, cols, first = item.id)

    private class Grid(val cols: Int) {
        private val taken = mutableSetOf<Pair<Int, Int>>()
        fun fits(item: HomeItem, col: Int, row: Int): Boolean {
            val (w, h) = size(item, cols)
            return col + w <= cols && (0 until w).all { dx -> (0 until h).all { dy -> (col + dx to row + dy) !in taken } }
        }
        fun take(item: HomeItem, col: Int, row: Int) {
            val (w, h) = size(item, cols)
            for (dx in 0 until w) for (dy in 0 until h) taken += col + dx to row + dy
        }
        /** The first cell, in reading order from (col, row), where the part fits; the grid grows down, so there always is one. */
        fun firstFree(item: HomeItem, col: Int, row: Int): Pair<Int, Int> {
            var c = col; var r = row
            while (!fits(item, c, r)) { if (++c >= cols) { c = 0; r++ } }
            return c to r
        }
    }
}
