package com.kalotrapezis.drive

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * `columns` is what a phone shows, and `targetDp` is roughly how wide one thumbnail wants to be there — the
 * two agree at a phone's ~400dp. See [TimelineRules.columns].
 */
internal enum class TimelineScale(val columns: Int, val targetDp: Int, @androidx.annotation.StringRes val label: Int) {
    Week(2, 190, R.string.photos_week),
    Month(3, 128, R.string.photos_month),
    Year(6, 64, R.string.photos_year),
    ;

    fun finer() = entries.getOrElse(ordinal - 1) { this }
    fun broader() = entries.getOrElse(ordinal + 1) { this }
}

internal object TimelineRules {
    /**
     * How many photos across. A tablet is not a big phone: the same two columns that make a Week comfortable
     * on a phone made every photo 582dp wide on the tablet (24 September), which is how it looked — huge and
     * wrong. So the scale sets how big a thumbnail should *feel* and the screen decides how many fit, never
     * fewer than the phone's count, so a narrow phone is left exactly as it was.
     */
    fun columns(scale: TimelineScale, widthDp: Int): Int = columns(scale.columns, scale.targetDp, widthDp)

    /** The same rule for a grid that has no scale: keep the phone's cell size, fit as many as the screen allows. */
    fun columns(phoneColumns: Int, targetDp: Int, widthDp: Int): Int = maxOf(phoneColumns, widthDp / targetDp)

    fun groupKey(timestampMillis: Long, scale: TimelineScale, zoneId: ZoneId = ZoneId.systemDefault()): String {
        if (timestampMillis <= 0) return "unknown"
        val date = Instant.ofEpochMilli(timestampMillis).atZone(zoneId).toLocalDate()
        return when (scale) {
            TimelineScale.Week -> {
                val fields = WeekFields.ISO
                String.format(Locale.ROOT, "%04d-W%02d", date.get(fields.weekBasedYear()), date.get(fields.weekOfWeekBasedYear()))
            }
            TimelineScale.Month -> YearMonth.from(date).toString()
            TimelineScale.Year -> date.year.toString()
        }
    }

    /** [unknown] and [week] (its number, its year) are the words, in the user's language. */
    fun groupLabel(key: String, scale: TimelineScale, unknown: String, week: (String, String) -> String, locale: Locale = Locale.getDefault()): String = when {
        key == "unknown" -> unknown
        scale == TimelineScale.Week -> week(key.substringAfter("-W"), key.substringBefore("-W"))
        scale == TimelineScale.Month -> YearMonth.parse(key).atDay(1).format(DateTimeFormatter.ofPattern("MMMM yyyy", locale))
        else -> key
    }

    /** Each group starts with one date label, except when the first label is intentionally hidden. */
    fun itemGroupKeys(groups: List<Pair<String, Int>>, firstGroupHasHeader: Boolean = true): List<String> = buildList {
        groups.forEachIndexed { index, (key, photoCount) ->
            if (firstGroupHasHeader || index > 0) add(key)
            repeat(photoCount) { add(key) }
        }
    }

    fun visibleGroupKey(itemGroupKeys: List<String>, firstVisibleItemIndex: Int): String =
        itemGroupKeys.getOrElse(firstVisibleItemIndex) { itemGroupKeys.lastOrNull() ?: "unknown" }

    fun yearForGroup(key: String, unknown: String): String = when {
        key == "unknown" -> unknown
        key.contains("-W") -> key.substringBefore("-W")
        key.length >= 4 -> key.take(4)
        else -> key
    }
}

/**
 * Motion photos, shown as one (asked 2026-09-26; the desktop's library.pairMotion is the same rule): an export
 * splits a motion photo into a picture and a few seconds of video of the same name in the same folder —
 * MVIMG_1.jpg + MVIMG_1.MP4, 20230529_201908.heic + 20230529_201908(2).MP4, PXL_1.MP.jpg + PXL_1.mp4.
 */
internal object MotionRules {
    private val suffix = Regex("""(\(\d+\)|~\d+)$""")

    fun stem(folder: String?, name: String): String =
        (folder.orEmpty().trimEnd('/') + "/" + name.substringBeforeLast('.').removeSuffix(".MP").removeSuffix(".mp").replace(suffix, "")).lowercase(Locale.ROOT)

    /** Each video that is a picture's other half → that picture, by index. `items` is (folder, name, isVideo). */
    fun pairs(items: List<Triple<String?, String, Boolean>>): Map<Int, Int> {
        val pictures = HashMap<String, Int>()
        items.forEachIndexed { i, (folder, name, video) -> if (!video) pictures[stem(folder, name)] = i }
        val out = HashMap<Int, Int>()
        val taken = HashSet<Int>()
        items.forEachIndexed { i, (folder, name, video) ->
            if (video) pictures[stem(folder, name)]?.takeIf { taken.add(it) }?.let { out[i] = it }
        }
        return out
    }

    private val brands = setOf("mp41", "mp42", "isom", "iso2", "iso4", "iso5", "iso6", "avc1", "qt  ", "M4V ", "MSNV")

    /**
     * Where the video inside a motion photo starts (Pixel, Samsung, Xiaomi): the picture's file ends with an MP4,
     * whose first box is `ftyp` after a 4-byte size. -1 when there is none. A HEIC's own `ftyp` is at 4, so the
     * search starts past it; the brand check keeps a stray "ftyp" in the picture's data from counting.
     */
    fun embeddedVideoOffset(bytes: ByteArray): Int {
        var i = 16
        while (i + 8 <= bytes.size) {
            if (bytes[i] == 'f'.code.toByte() && bytes[i + 1] == 't'.code.toByte() && bytes[i + 2] == 'y'.code.toByte() && bytes[i + 3] == 'p'.code.toByte()) {
                val size = ((bytes[i - 4].toInt() and 0xff) shl 24) or ((bytes[i - 3].toInt() and 0xff) shl 16) or ((bytes[i - 2].toInt() and 0xff) shl 8) or (bytes[i - 1].toInt() and 0xff)
                if (size in 16..256 && String(bytes, i + 4, 4, Charsets.ISO_8859_1) in brands) return i - 4
            }
            i++
        }
        return -1
    }
}
