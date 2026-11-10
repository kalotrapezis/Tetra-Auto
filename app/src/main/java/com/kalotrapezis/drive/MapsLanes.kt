package com.kalotrapezis.drive

import java.io.File

/**
 * A road's lanes before a turn, left to right, each with its arrows (OSM's words: through, slight_right…); [use] the ones
 * for the turn; [begins] how many metres before the turn they begin (an exit's lane: where its broken line starts), 0 unknown.
 */
internal data class Lanes(val turns: List<List<String>>, val use: Set<Int>, val begins: Double = 0.0)

/**
 * Lane guidance (the user, 23 Oct, on the Α1 by Ελαιώνας: "you must keep to the second lane from the right, which slowly
 * becomes the outer one"): OpenStreetMap's lane arrows from each map's <id>-lanes.tsv (tools/maps-lanes.py) beside the graph, each
 * the end of a road piece in the way it is driven. A turn's lanes are those of the last piece's end the route passes,
 * facing its way, up to 400 m before the turn.
 */
internal object MapsLanes {
    private class End(val lat: Double, val lng: Double, val bearing: Double, val turns: List<List<String>>, val metres: Double)

    private var cells: Map<Long, List<End>> = emptyMap()
    private var loaded: Pair<File, Long>? = null

    private fun key(lat: Double, lng: Double) = (Math.floor(lat * 200).toLong() shl 32) or (Math.floor(lng * 200).toLong() and 0xffffffffL)

    /** OSM's turn:lanes, as lanes of arrows: empty or "none" is through. */
    fun parse(lanes: String): List<List<String>> =
        lanes.split('|').map { l -> l.split(';').map { it.trim() }.map { if (it.isEmpty() || it == "none") "through" else it }.distinct() }

    /** The lanes files in [dir] (Tetra/.System/Maps/Shared), read once and again when one changes; nothing when there are none. */
    @Synchronized private fun load(dir: File): Map<Long, List<End>> {
        val files = MapsFiles.lists(dir, "lanes")
        if (files.isEmpty()) return emptyMap()
        val stamp = files.sumOf { it.lastModified() } + files.size
        if (loaded == dir to stamp) return cells
        val m = HashMap<Long, MutableList<End>>()
        for (f in files) f.forEachLine { line ->
            val p = line.split('\t')
            val lat = p.getOrNull(0)?.toDoubleOrNull(); val lng = p.getOrNull(1)?.toDoubleOrNull(); val b = p.getOrNull(2)?.toDoubleOrNull()
            if (lat != null && lng != null && b != null && p.getOrNull(3).orEmpty().isNotBlank())
                m.getOrPut(key(lat, lng)) { ArrayList() } += End(lat, lng, b, parse(p[3]), p.getOrNull(5)?.toDoubleOrNull() ?: 0.0)
        }
        cells = m; loaded = dir to stamp
        return cells
    }

    /** Which lanes go the way of a turn by GraphHopper's [sign]: those with its side's arrows; for a keep or a slight turn
     *  with none (the road goes on, the other way leaves on the other side), the through lanes. */
    fun use(turns: List<List<String>>, sign: Int): Set<Int> {
        val right = setOf("slight_right", "right", "sharp_right"); val left = setOf("slight_left", "left", "sharp_left")
        val side = when (sign) { 1, 2, 3, 7 -> right; -1, -2, -3, -7 -> left; else -> emptySet() }
        val bySide = turns.indices.filter { i -> turns[i].any { it in side } }.toSet()
        if (bySide.isNotEmpty()) return bySide
        return if (sign in setOf(0, 1, -1, 7, -7)) turns.indices.filter { "through" in turns[it] }.toSet() else emptySet()
    }

    /** [r]'s steps with their lanes, where [dir] has the lanes file and the route passes a piece's end before a turn. */
    fun on(r: Route, dir: File, w: Words): Route {
        val ends = load(dir)
        if (ends.isEmpty() || r.lats.size < 2) return r
        val cum = cumulative(r)
        return r.copy(steps = r.steps.map { s ->
            val l = if (s.at <= 0 || s.sign in setOf(0, 4, 5, 6)) null else before(r, cum, s, ends)
            // a keep whose lanes are the road's through lanes: the road goes on, the other way leaves (the user, 23 Oct:
            // Google's "take the exit on the right" where one just drives on)
            if (l != null && s.sign in setOf(7, -7) && l.use.all { "through" in l.turns[it] }) s.copy(lanes = l, sign = 0, text = straight(s, w))
            else s.copy(lanes = l)
        })
    }

    /** "Μείνετε δεξιά και οδηγείστε προς Θεσπιές" as "Συνεχίστε ευθεία προς Θεσπιές". */
    fun straight(s: Step, w: Words): String =
        if (s.toward.isEmpty()) w.s(R.string.maps_keep_straight) else w.s(R.string.maps_towards, w.s(R.string.maps_keep_straight), s.toward)

    private fun before(r: Route, cum: DoubleArray, s: Step, ends: Map<Long, List<End>>): Lanes? {
        val at = s.at.coerceAtMost(cum.lastIndex); val turn = cum[at]
        val passed = ArrayList<Pair<End, Double>>() // the ends the route passes facing their way, with how far along
        var i = at
        while (i > 0 && turn - cum[i - 1] <= 1500) i--
        while (i < r.lats.size - 1 && cum[i] <= turn + 15) {
            val heading = bearing(r.lats[i], r.lngs[i], r.lats[i + 1], r.lngs[i + 1])
            val seg = cum[i + 1] - cum[i]
            for (dl in -1..1) for (dg in -1..1) for (e in ends[key(r.lats[i] + dl * 0.005, r.lngs[i] + dg * 0.005)].orEmpty()) {
                if (Math.abs(((e.bearing - heading) % 360 + 540) % 360 - 180) > 35) continue
                // the end on this stretch of the route: its distance from the stretch and how far along it (flat, metres)
                val kx = 111_320 * Math.cos(Math.toRadians(r.lats[i])); val ky = 110_540.0
                val bx = (r.lngs[i + 1] - r.lngs[i]) * kx; val by = (r.lats[i + 1] - r.lats[i]) * ky
                val px = (e.lng - r.lngs[i]) * kx; val py = (e.lat - r.lats[i]) * ky
                val t = if (bx * bx + by * by == 0.0) 0.0 else ((px * bx + py * by) / (bx * bx + by * by)).coerceIn(0.0, 1.0)
                if (Math.hypot(px - t * bx, py - t * by) > 8) continue // a ramp beside the motorway is not on it
                val along = cum[i] + t * seg
                if (along <= turn + 15 && passed.none { it.first === e }) passed += e to along
            }
            i++
        }
        // the last end before the turn, within 400 m; its lanes begin where the pieces with the same lanes before it begin
        val (e, along) = passed.filter { turn - it.second <= 400 }.maxByOrNull { it.second } ?: return null
        val use = use(e.turns, s.sign)
        if (use.isEmpty()) return null
        var start = along - e.metres
        while (true) {
            val prev = passed.firstOrNull { (o, a) -> o !== e && o.turns == e.turns && Math.abs(a - start) < 25 && a - o.metres < start - 1 } ?: break
            start = prev.second - prev.first.metres
        }
        return Lanes(e.turns, use, if (e.metres > 0) (turn - start).coerceAtLeast(0.0) else 0.0)
    }

    /** The lanes in words for the voice, when they are a few at one side ("from the 2 right lanes"); null when all or unclear. */
    fun words(l: Lanes, w: Words): String? {
        val n = l.turns.size; val u = l.use.sorted()
        if (n < 2 || u.isEmpty() || u.size == n || u.last() - u.first() + 1 != u.size) return null
        val k = u.size
        return when {
            u.last() == n - 1 -> w.q(R.plurals.maps_lanes_right, k, k)
            u.first() == 0 -> w.q(R.plurals.maps_lanes_left, k, k)
            k == 1 && n == 3 -> w.s(R.string.maps_lane_middle)
            else -> null
        }
    }

    /** A turn as said ahead of it, with its lanes when they help: "Μείνετε δεξιά προς Θεσπιές, από τη δεξιά λωρίδα". */
    fun said(s: Step, w: Words) = s.text + (s.lanes?.let { words(it, w) }?.let { ", $it" } ?: "")
}

/** Whether the turn card shows the lanes now: from ~1.5 km before the turn. */
internal fun showLanes(l: Lanes?, toNext: Double) = l != null && toNext < 1500

/**
 * [l] drawn [h] high for the turn card, as on the road signs: a lane each, its arrows, the ones to take white, the rest
 * grey, thin lines between lanes.
 */
internal fun lanesPicture(l: Lanes, h: Int): android.graphics.Bitmap {
    val w = (h * 0.72f).toInt()
    val b = android.graphics.Bitmap.createBitmap(w * l.turns.size, h, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF; strokeWidth = h * 0.03f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(h * 0.1f, h * 0.08f), 0f)
    }
    for (k in 1 until l.turns.size) c.drawLine(k * w.toFloat(), h * 0.1f, k * w.toFloat(), h * 0.9f, line)
    for ((k, arrows) in l.turns.withIndex()) {
        val color = if (k in l.use) 0xFFFFFFFF.toInt() else 0xFF80868B.toInt()
        val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; strokeWidth = h * 0.09f; style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = android.graphics.Paint.Style.FILL }
        val cx = k * w + w / 2f; val bottom = h * 0.88f; val mid = h * 0.5f
        for (a in arrows) {
            // the arrow's turn in degrees from straight on (+ right); a merge drawn as the slight turn it asks for
            val deg = when (a) {
                "slight_right", "merge_to_right" -> 40.0; "right" -> 85.0; "sharp_right" -> 135.0
                "slight_left", "merge_to_left" -> -40.0; "left" -> -85.0; "sharp_left" -> -135.0; "reverse" -> -170.0
                else -> 0.0
            }
            val len = h * (if (deg == 0.0) 0.36f else 0.3f)
            val ex = cx + (Math.sin(Math.toRadians(deg)) * len).toFloat().coerceIn(-w * 0.42f, w * 0.42f)
            val ey = mid - (Math.cos(Math.toRadians(deg)) * len).toFloat()
            c.drawPath(android.graphics.Path().apply { moveTo(cx, bottom); lineTo(cx, mid); lineTo(ex, ey) }, stroke)
            // the head: a triangle along the arrow's last stretch
            val ang = Math.atan2((ey - mid).toDouble(), (ex - cx).toDouble()); val s = h * 0.18
            fun pt(t: Double, r: Double) = (ex + Math.cos(t) * r).toFloat() to (ey + Math.sin(t) * r).toFloat()
            val (tx, ty) = pt(ang, s * 0.6); val (lx, ly) = pt(ang + 2.5, s); val (rx, ry) = pt(ang - 2.5, s)
            c.drawPath(android.graphics.Path().apply { moveTo(tx, ty); lineTo(lx, ly); lineTo(rx, ry); close() }, fill)
        }
    }
    return b
}
