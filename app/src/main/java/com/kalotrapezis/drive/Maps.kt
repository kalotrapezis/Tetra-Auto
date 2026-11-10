package com.kalotrapezis.drive

import org.json.JSONObject
import java.io.File

/*
 * Tetra's Maps (Plan.md § Maps). Everything is a file in Tetra/.System/Maps/Shared (~/Tetra/Files/Maps on the computer, hidden in
 * Files like the Calendar and the Timer, synced): the computer makes them (tools/maps-greece.sh) and sync carries them,
 * so the desktop can use the same ones later.
 *
 *   <id>.pmtiles     a country's offline map (Protomaps' basemap cut to it), drawn by MapLibre: greece.pmtiles, and any
 *                    other desktop Tetra's map builder made (its mapbuild.js; the user, 27 Oct: "a map builder that downloads
 *                    the map, does the roads stuff and installs it in the app and then uploads to the android devices"),
 *                    with <id>.json beside it ({"name","region","built","bounds":[w,s,e,n],"drive"})
 *   <id>-gh/         its offline routing graph for car, foot and bike (GraphHopper 10.2, the app's own version)
 *   Places/<id>.json one saved place each:
 *     {"id":"k3f9a2","name":"Home","lat":37.9715,"lng":23.7257,"icon":"home","slot":1,"mode":"car","lists":["fav"]}
 *     slot 1–12 puts it on Home's Maps card (0: not there; a phone shows 1–6); mode is how its one tap there goes (car,
 *     foot, bike); lists are the ids of the lists it is in; label, when there, is its name on the Home card (the place
 *     itself, "Πύργος", where the map shows the whole name, "Δημοτικό Σχολείο Πύργου"; the user, 21 Oct).
 *   added-places.tsv places put into the map's own data by hand, in the search list's form (the user's schools, 21 Oct)
 *   Lists/<id>.json  one list each, {"id":"fav","name":"Favourites","emoji":"❤️"} (the user, 21 Oct: "make lists with names
 *     and emojes and add favs in there")
 *   <id>-places.tsv  the offline search list (tools/maps-places.py): towns and places of Search's kinds; with it
 *                    <id>-addresses.tsv, <id>-streets.tsv and <id>-lanes.tsv
 */
internal data class Place(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val icon: String = "pin",
    val slot: Int = 0,
    val mode: String = "car",
    val file: File? = null,
    val lists: List<String> = emptyList(),
    val label: String = "",
) {
    fun json(): String = JSONObject().put("id", id).put("name", name).put("lat", lat).put("lng", lng).put("icon", icon)
        .put("slot", slot).put("mode", mode).put("lists", org.json.JSONArray(lists)).apply { if (label.isNotBlank()) put("label", label) }.toString(1) + "\n"
    /** Its name on the Home card. */
    val short get() = label.ifBlank { name }

    companion object {
        /** Emblems by tab (the user, 21 Oct: "pharmases, hospitals, relatives travel locations, nature like waterfalls rivers beaches … in tabs with categories"). */
        val CATEGORIES = linkedMapOf(
            "General" to listOf("pin", "star", "heart", "accessible", "home", "work", "school"),
            "People" to listOf("family", "friend"),
            "Health" to listOf("pharmacy", "hospital", "doctor"),
            "Travel" to listOf("travel", "hotel", "food", "tavern", "cafe", "shop", "fuel", "parking", "accessible"),
            "Nature" to listOf("beach", "waterfall", "river", "lake", "mountain", "forest", "camping"),
        )
        val ICONS = CATEGORIES.values.flatten().distinct() // the accessible mark is in General and beside Parking (the user, 21 Oct: "a disability mark … for my parking spots and accessible places")
        fun category(icon: String) = CATEGORIES.entries.firstOrNull { icon in it.value }?.key ?: "General"
        /** A tab's name on the screen; the keys above stay the ids. */
        @androidx.annotation.StringRes fun categoryLabel(c: String): Int = when (c) {
            "People" -> R.string.maps_cat_people; "Health" -> R.string.maps_cat_health; "Travel" -> R.string.maps_cat_travel
            "Nature" -> R.string.maps_cat_nature; else -> R.string.maps_cat_general
        }
        val MODES = listOf("car", "foot", "bike")

        /** A file's place; anything that is not one (a stray file, half a write) is null, never an error. */
        fun parse(text: String, file: File? = null): Place? = runCatching {
            val o = JSONObject(text)
            val lat = o.getDouble("lat"); val lng = o.getDouble("lng")
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
            Place(o.optString("id").ifEmpty { file?.nameWithoutExtension ?: return null }, o.optString("name").ifEmpty { "Place" }, lat, lng,
                o.optString("icon").takeIf { it in ICONS } ?: "pin", o.optInt("slot", 0).takeIf { it in 0..MapsFiles.SLOTS } ?: 0,
                o.optString("mode").takeIf { it in MODES } ?: "car", file,
                o.optJSONArray("lists")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } }.orEmpty(),
                o.optString("label"))
        }.getOrNull()
    }
}

/**
 * A trip: stops gone to in order, A, B, C… (the user, 22 Oct: "a plan mode for the phone … how many stops you will do 5,
 * point to the 1st stop, the second stop … can be saved as a trip. When we continue from a stop … start automaticaly for
 * the next"). A file in Tetra/.System/Maps/Shared/Trips, so it syncs and desktop Tetra can read it.
 */
internal data class Trip(val id: String, val name: String, val stops: List<Place>) {
    fun json(): String = JSONObject().put("id", id).put("name", name).put("stops", org.json.JSONArray(stops.map {
        JSONObject().put("name", it.name).put("lat", it.lat).put("lng", it.lng)
    })).toString(1) + "\n"

    companion object {
        fun letter(i: Int) = ('A' + i % 26).toString()
        fun parse(text: String, file: File? = null): Trip? = runCatching {
            val o = JSONObject(text)
            val a = o.getJSONArray("stops")
            val stops = (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { s ->
                val lat = s.getDouble("lat"); val lng = s.getDouble("lng")
                if (lat !in -90.0..90.0 || lng !in -180.0..180.0) null else Place("", s.optString("name").ifEmpty { "Stop ${letter(i)}" }, lat, lng)
            } }
            if (stops.isEmpty()) null else Trip(o.optString("id").ifEmpty { file?.nameWithoutExtension ?: return null }, o.optString("name").ifEmpty { "Trip" }, stops)
        }.getOrNull()
    }
}

internal object MapsFiles {
    const val FOLDER = "Maps"
    const val MAP = "greece.pmtiles"
    const val GRAPH = "greece-gh"
    const val PLACES = "Places"
    /** Home's Maps card: twelve places on a tablet, the first six on a phone (the user, 20 Oct: "slots for 6 locations"; 21 Oct, the tablet: "very big … we can double them"). */
    const val SLOTS = 12
    const val PHONE_SLOTS = 8 // half-size tiles on a phone (the user, 21 Oct: "half the size, so we can have 8")
    const val LISTS = "Lists"
    const val TRIPS = "Trips"
    /** Where the car is: one place with this id ("My car"), saved over by the next parking. */
    const val PARKING = "parking"

    fun root(tetra: File) = File(tetra, TetraSystem.onDisk(FOLDER))
    /** The first map (a map is there when it is a file); [maps] are all of them. */
    fun map(root: File) = ids(root).firstOrNull()?.let { File(root, "$it.pmtiles") } ?: File(root, MAP)
    fun graph(root: File) = File(root, GRAPH)

    /** The maps here, one id each ("greece", "cyprus"), sorted so their layers draw the same way each time. */
    fun ids(root: File): List<String> = root.list().orEmpty().filter { it.endsWith(".pmtiles") && !it.startsWith(".") }.map { it.removeSuffix(".pmtiles") }.sorted()

    /** A map: its id, its name, [bounds] west, south, east, north, and the side traffic keeps to there. */
    class MapPack(val id: String, val name: String, val bounds: DoubleArray?, val driveLeft: Boolean) {
        fun has(lat: Double, lng: Double) = bounds != null && lng >= bounds[0] && lat >= bounds[1] && lng <= bounds[2] && lat <= bounds[3]
    }

    /** Each map with its manifest (the map builder's <id>.json) or, without one (Greece's), its own file's bounds. */
    fun maps(root: File): List<MapPack> = ids(root).map { id ->
        val m = runCatching { JSONObject(File(root, "$id.json").readText()) }.getOrNull()
        val b = m?.optJSONArray("bounds")?.takeIf { it.length() == 4 }?.let { a -> DoubleArray(4) { a.getDouble(it) } } ?: bounds(File(root, "$id.pmtiles"))
        MapPack(id, m?.optString("name")?.ifEmpty { null } ?: id.replaceFirstChar { it.uppercase() }, b, m?.optString("drive") == "left")
    }

    /** Whether traffic keeps left at point [at] of route [r]: the first map whose bounds have it (none: right). */
    fun driveLeftAt(root: File, r: Route, at: Int) = at.coerceIn(0, r.lats.lastIndex).let { i -> maps(root).firstOrNull { it.has(r.lats[i], r.lngs[i]) }?.driveLeft == true }

    /** The day map [id] was made: its manifest's "built", else (Greece's older pack) its .pmtiles file's date. */
    fun built(root: File, id: String): java.time.LocalDate =
        runCatching { java.time.LocalDate.parse(JSONObject(File(root, "$id.json").readText()).getString("built")) }.getOrNull()
            ?: java.time.Instant.ofEpochMilli(File(root, "$id.pmtiles").lastModified()).atZone(java.time.ZoneId.systemDefault()).toLocalDate()

    /** A map made 3 months ago or more is old: roads, shops and hours change; the computer remakes it (mapbuild.js isOld). */
    fun isOld(built: java.time.LocalDate, today: java.time.LocalDate) = !today.isBefore(built.plusMonths(3))

    /** A .pmtiles file's own bounds: its v3 header's west, south, east, north (×10⁷, from byte 102). */
    fun bounds(f: File): DoubleArray? = runCatching {
        java.io.RandomAccessFile(f, "r").use { r ->
            val b = ByteArray(127); r.readFully(b)
            if (String(b, 0, 7, Charsets.ISO_8859_1) != "PMTiles") null
            else java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).let { bb -> DoubleArray(4) { bb.getInt(102 + 4 * it) / 1e7 } }
        }
    }.getOrNull()

    /** The roads of the map [lat],[lng] is on, else the first map's. */
    fun graphAt(root: File, lat: Double, lng: Double): File =
        (mapsAt(root, lat, lng).firstOrNull() ?: maps(root).firstOrNull())?.let { File(root, "${it.id}-gh") } ?: graph(root)

    /**
     * The maps whose bounds have [lat],[lng]; where bounds overlap (Greece's box holds south Bulgaria, Bulgaria's the Evros
     * villages), only those with a car road within 40 m, else the one whose road is nearest. Kept per ~200 m square: limits
     * are asked every 2 s.
     */
    fun mapsAt(root: File, lat: Double, lng: Double, all: List<MapPack> = maps(root)): List<MapPack> {
        val boxes = all.filter { it.has(lat, lng) }
        if (boxes.size < 2) return boxes
        val key = "$root ${Math.round(lat * 500)} ${Math.round(lng * 500)} ${boxes.joinToString { it.id }}"
        val ids = synchronized(onRoads) { onRoads[key] } ?: boxes.map { it.id to MapsRouting.roadMetres(File(root, "${it.id}-gh"), lat, lng) }
            .let { m -> m.filter { it.second <= 40 }.ifEmpty { listOfNotNull(m.filter { it.second.isFinite() }.minByOrNull { it.second }) } }.map { it.first }
            .also { synchronized(onRoads) { onRoads[key] = it } }
        return boxes.filter { it.id in ids }.ifEmpty { boxes }
    }
    private val onRoads = object : LinkedHashMap<String, List<String>>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, List<String>>) = size > 256
    }

    /** A point where the roads cross between two maps; [n]: the crossing it is of (a station's lanes are a point each). */
    data class Crossing(val lat: Double, val lng: Double, val name: String, val n: Int)

    /** Where the roads cross between maps [a] and [b]: the desktop map builder's <a>_<b>-crossings.tsv (ids sorted). */
    fun crossings(root: File, a: String, b: String): List<Crossing> =
        runCatching { File(root, listOf(a, b).sorted().joinToString("_") + "-crossings.tsv").readLines() }.getOrDefault(emptyList()).mapIndexedNotNull { i, l ->
            val c = l.split('\t'); val lat = c[0].toDoubleOrNull(); val lng = c.getOrNull(1)?.toDoubleOrNull()
            if (lat == null || lng == null) null else Crossing(lat, lng, c.getOrElse(2) { "" }, c.getOrNull(4)?.toIntOrNull() ?: (-1 - i))
        }

    /** One part of a route, on one map's graph [dir]. */
    data class Leg(val dir: File, val fromLat: Double, val fromLng: Double, val toLat: Double, val toLng: Double)

    /** The crossings of a border tried for a route, those nearest the way, all their points; fewer through a third country, whose searches multiply. */
    private const val PER = 5
    private const val PER_THROUGH = 3

    /**
     * A route from the map of [dir] in legs, each on one map's own graph (the user, 27 Oct: "Drive to the edge and then start
     * from the other edge"; "at the border … the main road"): one leg when a map holds both ends, as before; else through
     * the maps on the way, fewest borders, at the crossings where [seconds] (null: no way) finds it fastest. A map not
     * joined fails in [w]'s words. Desktop maps.js legs is the same.
     */
    fun legs(dir: File, fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, w: Words,
             seconds: (File, Double, Double, Double, Double) -> Double?): List<Leg> {
        val root = dir.absoluteFile.parentFile ?: return listOf(Leg(dir, fromLat, fromLng, toLat, toLng))
        val all = maps(root)
        val a = mapsAt(root, fromLat, fromLng, all); val b = mapsAt(root, toLat, toLng, all)
        fun gh(m: MapPack) = File(root, "${m.id}-gh")
        val one = { g: File -> listOf(Leg(g, fromLat, fromLng, toLat, toLng)) }
        if (a.isEmpty() || b.isEmpty()) return one(dir) // off the maps: GraphHopper says which end
        // one map holds both ends; where the maps' bounds overlap at a border, the one whose roads reach both
        val common = a.filter { it in b }
        if (common.isNotEmpty() && a.size == 1 && b.size == 1) return one(if (gh(common[0]) == dir) dir else gh(common[0]))
        common.firstOrNull { seconds(gh(it), fromLat, fromLng, toLat, toLng) != null }?.let { return one(gh(it)) }
        val ends = b.filter { it !in a }
        if (ends.isEmpty()) return one(common.firstOrNull()?.let(::gh) ?: dir) // no other map: GraphHopper says why not
        // the maps on the way, fewest borders, through those whose crossings are known
        val back = LinkedHashMap<MapPack, MapPack?>().apply { a.forEach { put(it, null) } }
        val queue = ArrayDeque(a)
        while (queue.isNotEmpty() && ends.none { it in back }) {
            val x = queue.removeFirst()
            for (y in all) if (y !in back && crossings(root, x.id, y.id).isNotEmpty()) { back[y] = x; queue.addLast(y) }
        }
        val last = ends.firstOrNull { it in back } ?: error(w.s(R.string.maps_error_not_joined, a[0].name, ends[0].name))
        val chain = generateSequence(last) { back[it] }.toList().reversed()
        // border by border, each crossing point's best time from the start and the point it came through
        class At(val lat: Double, val lng: Double, val t: Double, val prev: At?)
        fun km(lat1: Double, lng1: Double, lat2: Double, lng2: Double) = Math.hypot(lat1 - lat2, (lng1 - lng2) * Math.cos(Math.toRadians(lat1))) * 111
        var at = listOf(At(fromLat, fromLng, 0.0, null))
        for (i in chain.indices) {
            val next = if (i == chain.lastIndex) listOf(Crossing(toLat, toLng, "", 0)) else {
                val cs = crossings(root, chain[i].id, chain[i + 1].id).sortedBy { km(fromLat, fromLng, it.lat, it.lng) + km(it.lat, it.lng, toLat, toLng) }
                val near = cs.map { it.n }.distinct().take(if (chain.size > 2) PER_THROUGH else PER)
                cs.filter { it.n in near }
            }
            at = next.map { q ->
                var best = At(q.lat, q.lng, Double.POSITIVE_INFINITY, null)
                for (o in at) if (o.t.isFinite()) seconds(gh(chain[i]), o.lat, o.lng, q.lat, q.lng)?.let { s -> if (o.t + s < best.t) best = At(q.lat, q.lng, o.t + s, o) }
                best
            }
        }
        check(at[0].t.isFinite()) { w.s(R.string.maps_error_no_way) }
        val out = ArrayList<Leg>()
        var o = at[0]; var i = chain.lastIndex
        while (true) { val p = o.prev ?: break; out.add(0, Leg(gh(chain[i]), p.lat, p.lng, o.lat, o.lng)); o = p; i-- }
        return out
    }

    /** Each map's <id>-[kind].tsv that is there (places, addresses, streets, lanes). */
    fun lists(root: File, kind: String): List<File> = ids(root).map { File(root, "$it-$kind.tsv") }.filter { it.isFile }

    /** Every file of map [id] to Tetra's Trash, so the removal reaches the other devices, its crossings too (an older builder's pair graph); the saved places stay. */
    fun removeMap(root: File, id: String) {
        val own = Regex(Regex.escape(id) + "(\\.pmtiles|\\.json|-gh|-[a-z]+\\.tsv)")
        val pair = Regex("([a-z0-9-]+)_([a-z0-9-]+)-(gh|crossings\\.tsv)")
        root.list().orEmpty().filter { own.matches(it) || pair.matchEntire(it)?.groupValues?.let { g -> id == g[1] || id == g[2] } == true }.forEach { trash(root, File(root, it)) }
    }

    /**
     * The saved places, one per id: a sync that found a place changed on both sides keeps both files ("parking (2).json"),
     * so the newest file is the place and the older copies go to Tetra's Trash (the user, 23 Oct: "it had 3 parkings, keep
     * only the latest"; and home in three files on the phone, where a save into an empty slot lost to an older copy).
     */
    fun places(root: File): List<Place> = File(root, PLACES).listFiles().orEmpty()
        .filter { it.isFile && !it.name.startsWith(".") && it.name.endsWith(".json", true) }
        .mapNotNull { f -> runCatching { Place.parse(f.readText(), f) }.getOrNull() }
        .groupBy { it.id }.values.map { same ->
            val newest = same.maxBy { it.file?.lastModified() ?: 0L }
            same.filter { it !== newest }.forEach { it.file?.let { f -> trash(root, f) } }
            newest
        }
        .let(::settle)
        .sortedBy { it.name.lowercase() }

    /**
     * One place a slot, both kept: two devices that each put a place into one slot while apart (the user, 23 Oct: slot 7
     * a supermarket on the tablet, home on the computer) — the newest file keeps it, the other takes the first free slot
     * (none free: off Home, still saved). Worked out from the files alone, by second (a sync keeps a file's date to the
     * second), so the phone, the tablet and the computer show the same card without a write; maps.js does the same.
     */
    fun settle(places: List<Place>): List<Place> {
        val taken = mutableSetOf<Int>()
        val late = places.filter { it.slot > 0 }
            .sortedWith(compareByDescending<Place> { (it.file?.lastModified() ?: 0L) / 1000 }.thenBy { it.id })
            .filter { !taken.add(it.slot) }
        val moved = late.associate { p -> p.id to ((1..SLOTS).firstOrNull { it !in taken }?.also { taken += it } ?: 0) }
        return places.map { p -> moved[p.id]?.let { p.copy(slot = it) } ?: p }
    }

    /** Home's twelve: slot n is the place that has it ([settle]d, so one each). */
    fun slots(places: List<Place>): List<Place?> = (1..SLOTS).map { n -> places.firstOrNull { it.slot == n } }

    /** Written beside, then renamed over, so a sync never reads half a place; taking a slot frees it on any other place. */
    fun save(root: File, p: Place): Place {
        val dir = File(root, PLACES).apply { mkdirs() }
        if (p.slot > 0) places(root).filter { it.slot == p.slot && it.id != p.id }.forEach { write(dir, it.copy(slot = 0)) }
        return write(dir, p).also { saved -> files(root, p.id).filter { it != saved.file }.forEach { trash(root, it) } }
    }

    /** Every file of place [id]: its own and any copy a sync made of it under another name. */
    private fun files(root: File, id: String) = File(root, PLACES).listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".json", true) }
        .filter { f -> f.nameWithoutExtension == id || runCatching { Place.parse(f.readText(), f)?.id }.getOrNull() == id }

    /** To Tetra/.System/Trash/Shared, never deleted: a synced file's removal crosses as a move, and it can be put back. */
    private fun trash(root: File, f: File) {
        runCatching { TetraSystem.tetraOf(root).let { DriveRules.moveToTrash(it, DriveRules.relative(it, f)) } }
    }

    private fun write(dir: File, p: Place): Place {
        val to = File(dir, "${p.id}.json")
        val part = File(dir, ".${to.name}.part")
        part.writeText(p.json())
        check(part.renameTo(to)) { "Could not save the place." }
        return p.copy(file = to)
    }

    /** Every file of [p] to the Trash. */
    fun delete(root: File, p: Place) = files(root, p.id).forEach { trash(root, it) }

    /** A map in Settings › Maps: [about] "128 MB · with roads · 27 Oct 2026", [old] when it is due to be remade. */
    class Installed(val id: String, val name: String, val about: String, val old: Boolean)

    /** What is in Tetra/.System/Maps/Shared, for Settings › Maps. */
    fun installed(context: android.content.Context, root: File): List<Installed> {
        val today = java.time.LocalDate.now()
        val day = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.getDefault())
        return maps(root).map { m ->
            val f = File(root, "${m.id}.pmtiles"); val g = File(root, "${m.id}-gh")
            val size = f.length() + g.listFiles().orEmpty().sumOf { it.length() } +
                listOf("places", "addresses", "streets", "lanes").sumOf { File(root, "${m.id}-$it.tsv").length() }
            val built = built(root, m.id)
            val roads = context.getString(if (File(g, "properties").isFile) R.string.maps_with_roads else R.string.maps_no_roads)
            Installed(m.id, m.name, "${size / 1_000_000} MB · $roads · ${built.format(day)}", isOld(built, today))
        }
    }

    fun lists(root: File): List<PlaceList> = File(root, LISTS).listFiles().orEmpty()
        .filter { it.isFile && !it.name.startsWith(".") && it.name.endsWith(".json", true) }
        .mapNotNull { f -> runCatching { PlaceList.parse(f.readText(), f) }.getOrNull() }
        .sortedBy { it.name.lowercase() }

    fun saveList(root: File, l: PlaceList): PlaceList {
        val dir = File(root, LISTS).apply { mkdirs() }
        val to = File(dir, "${l.id}.json")
        val part = File(dir, ".${to.name}.part")
        part.writeText(l.json())
        check(part.renameTo(to)) { "Could not save the list." }
        return l.copy(file = to)
    }

    fun trips(root: File): List<Trip> = File(root, TRIPS).listFiles().orEmpty()
        .filter { it.isFile && !it.name.startsWith(".") && it.name.endsWith(".json", true) }
        .mapNotNull { f -> runCatching { Trip.parse(f.readText(), f) }.getOrNull() }
        .sortedBy { it.name.lowercase() }

    fun saveTrip(root: File, t: Trip) {
        val dir = File(root, TRIPS).apply { mkdirs() }
        val to = File(dir, "${t.id}.json")
        val part = File(dir, ".${to.name}.part")
        part.writeText(t.json())
        check(part.renameTo(to)) { "Could not save the trip." }
    }

    fun deleteTrip(root: File, t: Trip) { File(File(root, TRIPS), "${t.id}.json").delete() }

    /** The list goes; its places stay (they keep its id, which no longer names a list and is passed over). */
    fun deleteList(root: File, l: PlaceList) { File(File(root, LISTS), "${l.id}.json").delete() }

    fun newId(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(6)
}

/** "350 m", "12 km", "1.4 km": a distance for a glance; in [units] "us" "500 ft", "0.3 mi", in "uk" "300 yd", "0.3 mi". */
internal fun metresText(m: Double, units: String = MapsPrefs.units): String {
    val (small, perMetre) = when (units) { "us" -> "ft" to FEET; "uk" -> "yd" to YARDS; else -> return when {
        m < 950 -> "${(Math.round(m / 10.0) * 10).coerceAtLeast(0)} m"
        m < 9950 -> String.format(java.util.Locale.ENGLISH, "%.1f km", m / 1000)
        else -> "${Math.round(m / 1000)} km"
    } }
    return when {
        m < 0.1 * MILE -> "${(Math.round(m * perMetre / 10.0) * 10).coerceAtLeast(0)} $small"
        m < 9.95 * MILE -> String.format(java.util.Locale.ENGLISH, "%.1f mi", m / MILE)
        else -> "${Math.round(m / MILE)} mi"
    }
}

internal const val MILE = 1609.344
internal const val FEET = 3.28084
internal const val YARDS = 1.093613

/** Where on a route a fix is: how far off it, how far along, and the next turn ahead. */
internal data class Progress(val off: Double, val along: Double, val left: Double, val next: Int, val toNext: Double)

/** A route's running total of metres at each point, for [progress]. */
internal fun cumulative(r: Route): DoubleArray {
    val c = DoubleArray(r.lats.size)
    for (i in 1 until c.size) c[i] = c[i - 1] + metresBetween(r.lats[i - 1], r.lngs[i - 1], r.lats[i], r.lngs[i])
    return c
}

/**
 * The fix [lat],[lng] put on route [r] (its [cum]ulative metres): the nearest point of its line, searched from [from] on
 * first (so a route that comes back past itself does not jump back), the whole line when that is far.
 */
internal fun progress(r: Route, cum: DoubleArray, lat: Double, lng: Double, from: Int = 0): Pair<Progress, Int> {
    fun best(range: IntRange): Triple<Double, Int, Double> {
        var bd = Double.MAX_VALUE; var bi = 0; var bt = 0.0
        val k = Math.cos(Math.toRadians(lat)) // metres per degree east, relative to north
        for (i in range) {
            val ax = (r.lngs[i] - lng) * k; val ay = r.lats[i] - lat
            val bx = (r.lngs[i + 1] - lng) * k; val by = r.lats[i + 1] - lat
            val dx = bx - ax; val dy = by - ay
            val len = dx * dx + dy * dy
            val t = if (len == 0.0) 0.0 else (-(ax * dx + ay * dy) / len).coerceIn(0.0, 1.0)
            val px = ax + t * dx; val py = ay + t * dy
            val d = Math.sqrt(px * px + py * py) * 111_195.0
            if (d < bd) { bd = d; bi = i; bt = t }
        }
        return Triple(bd, bi, bt)
    }
    val last = r.lats.size - 2
    if (last < 0) return Progress(metresBetween(lat, lng, r.lats[0], r.lngs[0]), 0.0, 0.0, r.steps.lastIndex, 0.0) to 0
    var (d, i, t) = best(from.coerceIn(0, last)..last)
    if (d > 60) best(0..last).let { (d2, i2, t2) -> if (d2 < d) { d = d2; i = i2; t = t2 } }
    val along = cum[i] + t * (cum[i + 1] - cum[i])
    val next = r.steps.indexOfFirst { it.at > i }.let { if (it < 0) r.steps.lastIndex else it }
    val toNext = (cum[r.steps[next].at.coerceAtMost(cum.lastIndex)] - along).coerceAtLeast(0.0)
    return Progress(d, along, (cum.last() - along).coerceAtLeast(0.0), next, toNext) to i
}

/**
 * Which of a turn's spoken warnings is due ([step] its index, [toNext] metres ahead, [speed] m/s): "e" early, 3 km ahead
 * on a fast road, as Google Maps does; "f" far, about 20 s ahead (500 m at least); "n" at it, about 7 s ahead — or, where
 * the turn's lanes are known to begin 100–700 m before it ([begins], an exit's lane), there, as its broken line starts
 * (the user, 23 Oct: "take the exit on the right when the dashed lines of the deceleration lane begin"); or null. By car the
 * warnings follow the speed (the user, 21 Oct, on the Egnatia: "σε 3χμ πάρτη την έξοδο στα δεξιά").
 */
internal fun dueWarning(step: Int, toNext: Double, speed: Double, car: Boolean, said: Set<String>, begins: Double = 0.0): String? {
    // by car a street early in town, 150 m or more out of it (the user, 23 Oct: at 50 m there was no slowing down for it)
    val near = if (!car) 20.0 else if (begins in 100.0..700.0) begins + 20 else maxOf(120.0, speed * 7)
    val far = if (car) maxOf(500.0, speed * 20) else 150.0
    return when {
        toNext <= near -> "n".takeIf { "n$step" !in said }
        toNext <= far -> "f".takeIf { "f$step" !in said && toNext > near * 1.5 }
        car && speed > 22 && toNext <= 3200 && toNext > far * 1.5 -> "e".takeIf { "e$step" !in said && "f$step" !in said }
        else -> null
    }
}

/** The direction from one point to another in degrees clockwise from north. */
internal fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val la1 = Math.toRadians(lat1); val la2 = Math.toRadians(lat2); val dl = Math.toRadians(lng2 - lng1)
    return Math.toDegrees(Math.atan2(Math.sin(dl) * Math.cos(la2), Math.cos(la1) * Math.sin(la2) - Math.sin(la1) * Math.cos(la2) * Math.cos(dl)))
}

/**
 * A turn's crossing as the car arrives: its roads' directions in degrees clockwise from straight ahead, sorted (the way in
 * is near ±180), [taken] the one to go on; a [roundabout]'s roads seen from its middle and the [exit] said (0: not known).
 * [roads]: each road's size, [arms]' then [then]'s — 2 a main road, 1 secondary or tertiary, 0 a street, −1 a ramp, −2 a
 * service road or track (only where the route takes one) —, empty where not known. [then]: at a staggered crossing, the
 * second one a few metres along [taken], where the route turns again: its other roads, in the same frame, and [thenTaken]
 * the one it leaves by. [entries]: a roundabout's roads only into it, not exits (not counted in [exit]).
 */
internal data class Junction(val arms: List<Double>, val taken: Int, val roundabout: Boolean = false, val roads: List<Int> = emptyList(),
                             val exit: Int = 0, val then: List<Double> = emptyList(), val thenTaken: Int = -1, val entries: Set<Int> = emptySet(),
                             val path: List<Pair<Double, Double>> = emptyList()) {
    /**
     * Worth a picture in the turn card, where the arrow alone can mislead (the user, 23 Oct: Δράμα's five streets): a
     * roundabout, five roads or more, another road leaving within 45° of the one to take, two turns a few metres apart, or
     * a U-turn. A plain cross is not.
     */
    val busy get() = roundabout || arms.size >= 5 || then.isNotEmpty() || uTurn || hooks ||
        arms.indices.any { it != taken && it != way && Math.abs(((arms[it] - arms[taken]) % 360 + 540) % 360 - 180) < 45 }
    /** The way in: the road nearest straight behind. */
    val way get() = arms.indices.minBy { Math.abs(Math.abs(arms[it]) - 180) }
    /**
     * The way on bends hard within 50 m of the crossing ([path], metres right and ahead): out to the right and at once sharp
     * right again, a loop to a bridge (the user, 5 Nov): drawn as it runs, not as a straight road.
     */
    val hooks get() = !roundabout && path.size >= 2 && path.last().let { (x, y) ->
        Math.abs(((Math.toDegrees(Math.atan2(x, y)) - arms[taken]) % 360 + 540) % 360 - 180) > 45 && Math.hypot(x, y) > 15 }
    /** Back the way it came: the way on within 20° of the way in. */
    val uTurn get() = !roundabout && Math.abs(arms[taken]) > 160
}

/** Directions' camera by car where nothing is near: zoom 16.5, tilted 45° to look ahead. */
internal val DRIVING_VIEW = 16.5 to 45.0

/**
 * Directions' camera by car, as zoom and tilt (the user, 23 Oct: "zoom in when we have to, zoom out when the car gains speed
 * on a straight road"; "zoom in and pitch to look more forward as it gets closer to a complicated road"; 3 Nov: "tilt a bit
 * forward while at high speed so I can see more of the road, and close to me with more zoom as I slow down"):
 * the zoom eases from 17.25 at a crawl to 15 at 110 km/h, though not further out than 16.5 while the next turn is under
 * ~40 s ahead; the tilt rises from 45° at 50 km/h to 60° (MapLibre's most) at 110 km/h. Closer step by step towards a busy
 * crossing from ~12 s or 250 m before it (to 17.75 at it), and at any turn slowed for (under 150 m and 32 km/h), tilted 55°
 * there to look along the road. Zoom in quarter steps, tilt in 5° ones, so the camera moves only now and then.
 */
internal fun carView(j: Junction?, sign: Int, toNext: Double, speed: Double): Pair<Double, Double> {
    val reach = maxOf(250.0, speed * 12)
    val busy = j != null && j.busy && toNext < reach
    val slowTurn = sign in setOf(-98, -3, -2, -1, 1, 2, 3, 6) && toNext < 150 && speed < 9
    val kmh = speed * 3.6
    val bySpeed = (17.25 - 2.25 * ((kmh - 10) / 100).coerceIn(0.0, 1.0)).let { if (toNext < speed * 40) maxOf(it, DRIVING_VIEW.first) else it }
    val (zoom, tilt) = when {
        busy -> maxOf(bySpeed, 16.5 + 1.25 * (1 - toNext / reach).coerceIn(0.0, 1.0)) to 55.0 // only ever closer, not out first
        slowTurn -> 17.5 to 55.0
        else -> bySpeed to 45.0 + 15 * ((kmh - 50) / 60).coerceIn(0.0, 1.0)
    }
    return Math.round(zoom * 4) / 4.0 to Math.round(tilt / 5) * 5.0
}

/** Whether the turn card shows [j]'s picture now, [toNext] metres before it. */
internal fun showJunction(j: Junction?, toNext: Double) = j != null && j.busy && toNext < 400

/**
 * [j] drawn [px] square for the turn card (the user, 23 Oct: "a card … with arrows or a circle, blue where I must go"): its
 * roads grey from the middle out at their true angles, the bigger ones thicker, the way in from the bottom and the way on
 * blue with an arrow; a roundabout a grey ring, gone round in blue — anticlockwise, or clockwise where traffic keeps [left]
 * (the UK, Ireland, Cyprus, Malta…) — with its exit's number in the middle, its roads only into it thin and dim. As the turn arrows of Google Maps, HERE and
 * Android Auto draw them (3 Nov): a fork as a Y, its two ways leaving together and parting; a ramp leaving a main road
 * that goes on straight, peeling off it before the crossing; a U-turn as a hairpin on a two-way road; a staggered crossing
 * as two crossings a short road apart.
 */
internal fun junctionPicture(j: Junction, px: Int, left: Boolean = false): android.graphics.Bitmap {
    val b = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    val mid = px / 2f; val far = px * 0.38f; val ringR = if (j.roundabout) px * 0.15f else 0f; val w = px * 0.07f
    val grey = 0xFF9AA0A6.toInt(); val blue = 0xFF4C8DF6.toInt()
    fun paint(color: Int, width: Float, fill: Boolean = false) = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; strokeWidth = width; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
        style = if (fill) android.graphics.Paint.Style.FILL else android.graphics.Paint.Style.STROKE
    }
    fun at(x: Float, y: Float, deg: Double, r: Float) = Math.toRadians(deg).let { android.graphics.PointF((x + Math.sin(it) * r).toFloat(), (y - Math.cos(it) * r).toFloat()) }
    /** A road from [s] bending by [k] to [e], [width] px wide. */
    class Road(val s: android.graphics.PointF, val k: android.graphics.PointF, val e: android.graphics.PointF, val width: Float)
    fun straight(s: android.graphics.PointF, e: android.graphics.PointF, width: Float) = Road(s, android.graphics.PointF((s.x + e.x) / 2, (s.y + e.y) / 2), e, width)
    fun draw(r: Road, color: Int) = c.drawPath(android.graphics.Path().apply { moveTo(r.s.x, r.s.y); quadTo(r.k.x, r.k.y, r.e.x, r.e.y) }, paint(color, r.width))
    /** The arrowhead at ([x], [y]) pointing along ([dx], [dy]). */
    fun arrow(x: Float, y: Float, dx: Float, dy: Float) {
        val n = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1e-3f); val ux = dx / n; val uy = dy / n
        val tip = px * 0.03f; val back = px * 0.1f; val half = px * 0.075f
        c.drawPath(android.graphics.Path().apply {
            moveTo(x + ux * tip, y + uy * tip); lineTo(x - ux * back - uy * half, y - uy * back + ux * half); lineTo(x - ux * back + uy * half, y - uy * back - ux * half); close()
        }, paint(blue, 0f, fill = true))
    }
    fun arrow(r: Road) = arrow(r.e.x, r.e.y, r.e.x - r.k.x, r.e.y - r.k.y)
    // the bigger roads thicker where the crossing has roads of two sizes or more
    val sized = j.roads.size == j.arms.size + j.then.size && j.roads.distinct().size > 1
    fun width(i: Int) = if (!sized) w else w * when (j.roads[i]) { 2 -> 1.35f; 1 -> 1.1f; -2 -> 0.7f; else -> 0.85f }
    /** [arms] as drawn: roads closer than 22° drawn apart, as two a few degrees apart would be one line; [fixed] kept where it is. */
    fun spread(arms: List<Double>, fixed: Int) = arms.toMutableList().also { s ->
        for (k in 0 until s.size - 1) {
            val gap = s[k + 1] - s[k]
            if (gap < 22 && k != fixed && k + 1 != fixed) { s[k] -= (22 - gap) / 2; s[k + 1] += (22 - gap) / 2 }
        }
    }
    val way = j.way; val t = j.taken; val shown = spread(j.arms, way)
    if (j.then.isNotEmpty()) { // a staggered crossing: the first crossing below the middle, the second a short road on along the way on
        val g = px * 0.24f; val r = px * 0.28f
        val a = at(mid, mid, shown[t] + 180, g / 2); val bp = at(mid, mid, shown[t], g / 2)
        val first = j.arms.indices.map { i -> if (i == t) straight(a, bp, width(i)) else straight(a, at(a.x, a.y, shown[i], r), width(i)) }
        val thenShown = spread(j.then, -1)
        val second = j.then.indices.map { k -> straight(bp, at(bp.x, bp.y, thenShown[k], r), width(j.arms.size + k)) }
        (first + second).forEach { draw(it, grey) }
        draw(first[way], blue); draw(first[t], blue); draw(second[j.thenTaken], blue); arrow(second[j.thenTaken])
        return b
    }
    if (j.uTurn) { // a hairpin: up the right of the road, round and back down its left (the other way round where traffic keeps left)
        val side = if (j.arms[t] < 0) -1f else 1f; val d = px * 0.08f; val top = mid - far * 0.3f
        val band = 2 * d + width(way) * 1.4f; val bottom = mid + far - band / 2 // its round end inside the square
        for (i in j.arms.indices) if (i != way && i != t) draw(straight(at(mid, mid, shown[i], 0f), at(mid, mid, shown[i], far), width(i)), grey)
        c.drawLine(mid, top, mid, bottom, paint(grey, band))
        c.drawPath(android.graphics.Path().apply {
            moveTo(mid - side * d, bottom); lineTo(mid - side * d, top)
            arcTo(android.graphics.RectF(mid - d, top - d, mid + d, top + d), if (side < 0) 0f else 180f, if (side < 0) -180f else 180f)
            lineTo(mid + side * d, mid + far * 0.45f)
        }, paint(blue, w))
        arrow(mid + side * d, mid + far * 0.45f, 0f, 1f)
        return b
    }
    val roads = j.arms.indices.map { i -> straight(at(mid, mid, shown[i], ringR), at(mid, mid, shown[i], far), width(i)) }.toMutableList()
    var wayIn = roads[way]
    if (!j.roundabout) {
        val ahead = j.arms.indices.filter { it != way && Math.abs(j.arms[it]) < 60 }
        // a ramp: a main road goes on straight and a ramp leaves it, the route taking one of the two
        val main = ahead.filter { j.roads.getOrElse(it) { 0 } >= 0 }.minByOrNull { Math.abs(j.arms[it]) }?.takeIf { Math.abs(j.arms[it]) < 25 }
        val ramp = ahead.filter { it != main && j.roads.getOrElse(it) { 0 } == -1 }.minByOrNull { Math.abs(j.arms[it] - j.arms[t]) }
        val fork = ahead.filter { it != t }.minByOrNull { Math.abs(j.arms[it] - j.arms[t]) }?.takeIf { t in ahead && Math.abs(j.arms[it] - j.arms[t]) < 50 }
        if (main != null && ramp != null && t in listOf(main, ramp)) {
            val split = android.graphics.PointF(mid, mid + far * 0.35f)
            roads[main] = straight(android.graphics.PointF(mid, mid), at(mid, mid, 0.0, far), width(main))
            roads[way] = straight(android.graphics.PointF(mid, mid), at(mid, mid, 180.0, far), width(way))
            roads[ramp] = Road(split, android.graphics.PointF(mid, mid - far * 0.15f), at(mid, mid, if (j.arms[ramp] < 0) -42.0 else 42.0, far), width(ramp))
            if (t == ramp) wayIn = straight(split, at(mid, mid, 180.0, far), width(way)) else wayIn = roads[way]
        } else if (fork != null) { // a Y: the two ways leave the middle together, straight on, then part at least 50°
            val (l, r) = listOf(t, fork).sortedBy { shown[it] }
            val m = (shown[l] + shown[r]) / 2; val half = maxOf(shown[r] - shown[l], 50.0) / 2
            for ((i, deg) in listOf(l to (m - half).coerceAtLeast(-80.0), r to (m + half).coerceAtMost(80.0)))
                roads[i] = Road(android.graphics.PointF(mid, mid), android.graphics.PointF(mid, mid - far * 0.4f), at(mid, mid, deg, far), width(i))
            wayIn = roads[way]
        }
    }
    // a roundabout's roads only into it thinner and dimmer: still roads, but not exits to count (the coordinator, 3 Nov)
    // a way on that hooks round: its own shape, its first 30 m filling the picture (all 50 m squeezed a 15 m hook thin)
    val hook = if (j.hooks && !j.uTurn) {
        val p = ArrayList<Pair<Double, Double>>(); var len = 0.0; var last = 0.0 to 0.0
        for (q in j.path) {
            val step = Math.hypot(q.first - last.first, q.second - last.second)
            if (len + step >= 30) { val f = (30 - len) / step; p += (last.first + (q.first - last.first) * f) to (last.second + (q.second - last.second) * f); break }
            p += q; len += step; last = q
        }
        val s = (far / maxOf(12.0, p.maxOf { Math.hypot(it.first, it.second) })).toFloat()
        p.map { (x, y) -> android.graphics.PointF(mid + x.toFloat() * s, mid - y.toFloat() * s) }
    } else null
    val soft = android.graphics.CornerPathEffect(px * 0.12f) // a road bends, it has no corners (the user, 5 Nov)
    fun line(ps: List<android.graphics.PointF>) = android.graphics.Path().apply { moveTo(mid, mid); ps.forEach { lineTo(it.x, it.y) } }
    roads.forEachIndexed { i, r -> if (i in j.entries) draw(Road(r.s, r.k, r.e, r.width * 0.6f), 0xFF6E7377.toInt())
        else if (i == t && hook != null) c.drawPath(line(hook), paint(grey, r.width).apply { pathEffect = soft }) else draw(r, grey) }
    if (j.roundabout) c.drawCircle(mid, mid, ringR, paint(grey, w))
    draw(wayIn, blue)
    if (hook != null) {
        c.drawPath(line(hook), paint(blue, roads[t].width).apply { pathEffect = soft })
        val a = if (hook.size >= 2) hook[hook.size - 2] else android.graphics.PointF(mid, mid); val e = hook.last()
        arrow(e.x, e.y, e.x - a.x, e.y - a.y)
        return b
    }
    draw(roads[t], blue)
    if (j.roundabout) ringSweep(shown[way], shown[t], left).let { (start, sweep) -> c.drawArc(mid - ringR, mid - ringR, mid + ringR, mid + ringR, start, sweep, false, paint(blue, w)) }
    arrow(roads[t])
    if (j.roundabout && j.exit > 0) c.drawText("${j.exit}", mid, mid + px * 0.06f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = blue; textSize = px * 0.17f; typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = android.graphics.Paint.Align.CENTER
    })
    return b
}

/**
 * The ring's blue arc from the way in ([inDeg]) round to the way out, as the canvas's start angle and sweep (0 at three
 * o'clock, + clockwise): anticlockwise (negative) where traffic keeps right, clockwise where it keeps [left]; once round when the two meet.
 */
internal fun ringSweep(inDeg: Double, outDeg: Double, left: Boolean): Pair<Float, Float> {
    val round = (((if (left) outDeg - inDeg else inDeg - outDeg) % 360 + 360) % 360).toFloat().let { if (it == 0f) 360f else it }
    return (inDeg - 90).toFloat() to if (left) round else -round
}

/** A sharp bend on a route where no turn is said: [along] metres from its start, to the [right] or left. */
internal data class Bend(val along: Double, val right: Boolean)

/**
 * The sharp bends of route [r] (its [cum]ulative metres), for "a sharp bend ahead" on mountain roads (the user, 23 Oct, by
 * Σκάλα): where the way turns 60° or more between 40 m before a point and 40 m after it — a curve of ~50 m radius or
 * tighter — and no turn of the directions is within 50 m.
 */
internal fun bendsOn(r: Route, cum: DoubleArray): List<Bend> {
    val n = r.lats.size
    if (n < 3) return emptyList()
    fun bearing(a: Int, b: Int) = bearing(r.lats[a], r.lngs[a], r.lats[b], r.lngs[b])
    val turns = r.steps.filter { it.sign != 0 && it.at in 0 until n }.map { cum[it.at] }
    val found = ArrayList<Pair<Double, Double>>() // along, the turn there (+ right)
    var a = 0; var b = 0
    for (i in 1 until n - 1) {
        while (a + 1 < i && cum[i] - cum[a + 1] >= 40) a++
        while (b < n - 1 && (b <= i || cum[b] - cum[i] < 40)) b++
        if (cum[i] - cum[a] < 40 || cum[b] - cum[i] < 40) continue
        val turn = ((bearing(i, b) - bearing(a, i)) % 360 + 540) % 360 - 180
        if (Math.abs(turn) < 60 || turns.any { Math.abs(it - cum[i]) < 50 }) continue
        val last = found.lastOrNull()
        if (last != null && cum[i] - last.first < 60 && (last.second > 0) == (turn > 0)) { // the same bend: its sharpest point
            if (Math.abs(turn) > Math.abs(last.second)) found[found.lastIndex] = cum[i] to turn
        } else found += cum[i] to turn
    }
    return found.map { Bend(it.first, it.second > 0) }
}

/** The warning at a turn: with its distance while that is over 100 m by car, so the right street is clear. */
internal fun nearWords(w: Words, toNext: Double, car: Boolean, text: String) =
    if (car && toNext > 100) MapsPrefs.inDistance(w, toNext, true, text) else text

/** The sharp bend ([bendsOn]) to warn of now, as its index: 30 m to ~7 s ahead (150 m at least), once each. */
internal fun dueBend(bends: List<Bend>, along: Double, speed: Double, said: Set<String>): Int? =
    bends.indices.firstOrNull { i -> "b$i" !in said && bends[i].along - along in 30.0..maxOf(150.0, speed * 7) }

/**
 * A warning's distance as said, with its unit: whole or half kilometres from 1 km, else metres to 50 (to 10 on foot); in
 * [units] "us" or "uk", half miles from half a mile, else feet to 100 (25 on foot) or yards to 50 (10 on foot).
 */
internal fun spokenDistance(m: Double, car: Boolean, units: String = "metric"): Pair<Double, String> {
    fun near(v: Double, step: Double) = Math.round(v / step) * step
    return when {
        units != "us" && units != "uk" -> if (m >= 950) near(m / 1000, 0.5) to "km" else near(m, if (car) 50.0 else 10.0) to "m"
        m >= 0.45 * MILE -> near(m / MILE, 0.5) to "mi"
        units == "us" -> near(m * FEET, if (car) 100.0 else 25.0) to "ft"
        else -> near(m * YARDS, if (car) 50.0 else 10.0) to "yd"
    }
}

/**
 * The toll stations on route [r] (its [cum]ulative metres) and how far along it each is (the user, 21 Oct: "Do we know where
 * the talls are to show them on the map as dots on active routs in a menu show the talls"): OSM's toll booths within 40 m
 * of its line.
 */
internal fun tollsOn(r: Route, cum: DoubleArray, pois: List<Poi>): List<Pair<Poi, Double>> {
    if (r.lats.isEmpty()) return emptyList()
    val south = r.lats.min() - 0.01; val north = r.lats.max() + 0.01; val west = r.lngs.min() - 0.01; val east = r.lngs.max() + 0.01
    val found = pois.asSequence().filter { it.kind == "toll" && it.lat in south..north && it.lng in west..east }
        .mapNotNull { t -> progress(r, cum, t.lat, t.lng).first.takeIf { it.off < 40 }?.let { t to it.along } }
        .sortedBy { it.second }.toList()
    // one station, however many booths OSM has of it (Σπαθοβούνι came three times): within 500 m of the last one kept
    val out = ArrayList<Pair<Poi, Double>>()
    for (t in found) if (out.isEmpty() || t.second - out.last().second > 500) out += t
        else if (out.last().first.name.isEmpty() && t.first.name.isNotEmpty()) out[out.lastIndex] = t
    return out
}

/** The way a moving car faces, from the GPS (the compass shows the phone in its holder, and the car's metal sways it); NaN when slow. */
internal fun headingOf(loc: android.location.Location): Double = if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 2.5f) loc.bearing.toDouble() else Double.NaN

internal fun metresBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
    val a = Math.sin((p2 - p1) / 2).let { it * it } + Math.cos(p1) * Math.cos(p2) * Math.sin(Math.toRadians(lng2 - lng1) / 2).let { it * it }
    return 2 * 6_371_000 * Math.asin(Math.sqrt(a))
}

internal data class PlaceList(val id: String, val name: String, val emoji: String, val file: File? = null) {
    fun json(): String = JSONObject().put("id", id).put("name", name).put("emoji", emoji).toString(1) + "\n"
    companion object {
        fun parse(text: String, file: File? = null): PlaceList? = runCatching {
            val o = JSONObject(text)
            PlaceList(o.optString("id").ifEmpty { file?.nameWithoutExtension ?: return null }, o.optString("name").ifEmpty { "List" }, o.optString("emoji"), file)
        }.getOrNull()
    }
}

/** A place of the search list (greece-places.tsv): a town, or a restaurant, pharmacy, charging point… with what OSM knows of it. */
internal data class Poi(
    val kind: String, val lat: Double, val lng: Double, val name: String, val nameEn: String = "", val more: String = "",
    val hours: String = "", val phone: String = "", val web: String = "", val wheelchair: String = "", val note: String = "",
    /** Its names in Tetra's other languages, "el=Νευροκόπι|de=…" (maps-places.py). */
    val names: String = "",
) {
    /** Accent-blind, its own, English and other names together, and its own in Latin letters (Greeklish), for search. */
    val key = MapsSearch.fold("$name $nameEn ${names.replace('|', ' ')}").let { "$it ${MapsSearch.latin(MapsSearch.fold(name))}" }
    /** Its name, or its kind's ("Pharmacy") when it has none. */
    fun title(c: android.content.Context) = name.ifEmpty { nameEn }.ifEmpty { MapsSearch.label(kind, c) }
    /**
     * Its name in the app's language, shown under its own (the user, 27 Oct: "show the normal one and the other below it"):
     * Νευροκόπι under Гоце Делчев on a Greek phone; null where it has none or it is the same.
     */
    fun named(c: android.content.Context): String? = named(c.resources.configuration.locales[0].language)
    fun named(lang: String): String? = (if (lang == "en") nameEn else names.split('|').firstOrNull { it.startsWith("$lang=") }?.substringAfter('='))
        ?.takeIf { it.isNotBlank() && it != name }
    fun place(c: android.content.Context) = Place("", title(c), lat, lng, MapsSearch.emblem(kind))
}

/**
 * Search, offline (the user, 21 Oct: "show nearby lcations in an area like Google Maps … restorants and pharmases and
 * supermarkets or petrol stations or charging stations add disability parking … button pills in the search page").
 */
internal object MapsSearch {
    /** EOPYY's own pharmacies (tools/maps-eopyy.py), kind "eopyy" (the user, 21 Oct: "add them as a list as well as a pill"). */
    const val EOPYY = "greece-eopyy.tsv"
    /** Cities' own disabled parking spots from data.gov.gr (tools/maps-amea.py), kind disabled_parking. */
    const val AMEA = "greece-amea.tsv"
    /** ΑμεΑ spots from the municipalities' decisions on Διαύγεια, placed by their address (tools/maps-diavgeia.py). */
    const val AMEA_DECISIONS = "greece-amea-diavgeia.tsv"
    /** Places put into the map's own data by hand (the user's schools, 21 Oct: "keep them as system locations"). */
    const val ADDED = "added-places.tsv"
    /** A pill: [label] its name on the screen, [one] a place of it ("Pharmacy"). */
    data class Kind(val key: String, @androidx.annotation.StringRes val label: Int, val emoji: String, val emblem: String, @androidx.annotation.StringRes val one: Int)
    /** Search's pills, in this order. */
    val PILLS = listOf(
        Kind("restaurant", R.string.maps_kind_restaurants, "🍽️", "food", R.string.maps_kind_restaurant), Kind("cafe", R.string.maps_kind_cafes, "☕", "cafe", R.string.maps_kind_cafe),
        Kind("supermarket", R.string.maps_kind_supermarkets, "🛒", "shop", R.string.maps_kind_supermarket),
        Kind("pharmacy", R.string.maps_kind_pharmacies, "💊", "pharmacy", R.string.maps_kind_pharmacy), Kind("eopyy", R.string.maps_kind_eopyy_pharmacies, "⚕️", "pharmacy", R.string.maps_kind_eopyy_pharmacy),
        Kind("fuel", R.string.maps_kind_fuel, "⛽", "fuel", R.string.maps_kind_fuel), Kind("charging", R.string.maps_kind_charging, "🔌", "fuel", R.string.maps_kind_charging),
        Kind("disabled_parking", R.string.maps_kind_disabled_parking, "♿", "accessible", R.string.maps_kind_disabled_parking), Kind("parking", R.string.maps_kind_parking, "🅿️", "parking", R.string.maps_kind_parking),
        Kind("hospital", R.string.maps_kind_hospitals, "🏥", "hospital", R.string.maps_kind_hospital), Kind("bakery", R.string.maps_kind_bakeries, "🥖", "food", R.string.maps_kind_bakery),
        Kind("fast_food", R.string.maps_kind_fast_food, "🍔", "food", R.string.maps_kind_fast_food),
        Kind("atm", R.string.maps_kind_atms, "🏧", "pin", R.string.maps_kind_atm), Kind("hotel", R.string.maps_kind_hotels, "🛏️", "hotel", R.string.maps_kind_hotel),
        Kind("beach", R.string.maps_kind_beaches, "🏖️", "beach", R.string.maps_kind_beach),
        Kind("museum", R.string.maps_kind_museums, "🏛️", "star", R.string.maps_kind_museum), Kind("toilets", R.string.maps_kind_toilets, "🚻", "pin", R.string.maps_kind_toilet),
        Kind("toll", R.string.maps_kind_tolls, "💶", "pin", R.string.maps_kind_toll),
        Kind("kindergarten", R.string.maps_kind_kindergartens, "🧸", "school", R.string.maps_kind_kindergarten),
        Kind("primary_school", R.string.maps_kind_primary_schools, "🎒", "school", R.string.maps_kind_primary_school),
        Kind("secondary_school", R.string.maps_kind_high_schools, "🏫", "school", R.string.maps_kind_high_school),
        Kind("school", R.string.maps_kind_other_schools, "🏫", "school", R.string.maps_kind_school),
        Kind("university", R.string.maps_kind_universities, "🎓", "school", R.string.maps_kind_university),
    )
    private val SETTLEMENTS = mapOf("city" to R.string.maps_kind_city, "town" to R.string.maps_kind_town, "village" to R.string.maps_kind_village,
        "suburb" to R.string.maps_kind_suburb, "neighbourhood" to R.string.maps_kind_neighbourhood, "hamlet" to R.string.maps_kind_hamlet,
        "island" to R.string.maps_kind_island, "islet" to R.string.maps_kind_islet, "locality" to R.string.maps_kind_locality)
    fun settlement(kind: String) = kind in SETTLEMENTS
    /** A place of [kind] in words ("Pharmacy"); a kind Tetra has no name for as OSM writes it. */
    fun label(kind: String, c: android.content.Context) = (PILLS.firstOrNull { it.key == kind }?.one ?: SETTLEMENTS[kind])?.let(c::getString)
        ?: kind.replace('_', ' ').replaceFirstChar { it.uppercase() }
    fun emoji(kind: String) = PILLS.firstOrNull { it.key == kind }?.emoji ?: if (settlement(kind)) "🏘️" else "📍"
    fun emblem(kind: String) = PILLS.firstOrNull { it.key == kind }?.emblem ?: "pin"

    /** Lower case, no accents, final sigma as sigma: "Ξάνθη" and "ξανθη" are the same. */
    fun fold(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").replace('ς', 'σ')

    private val GREEK = mapOf('α' to "a", 'β' to "v", 'γ' to "g", 'δ' to "d", 'ε' to "e", 'ζ' to "z", 'η' to "i", 'θ' to "th", 'ι' to "i", 'κ' to "k",
        'λ' to "l", 'μ' to "m", 'ν' to "n", 'ξ' to "ks", 'ο' to "o", 'π' to "p", 'ρ' to "r", 'σ' to "s", 'τ' to "t", 'υ' to "i", 'φ' to "f", 'χ' to "x",
        'ψ' to "ps", 'ω' to "o")

    /**
     * Greek and Greeklish brought to one spelling, so "krewnas", "kreonas" and "Κρεώνας" meet (the user typed "Krewnas", 21 Oct):
     * ω/ο → o, η/ι/υ/ει/οι → i, αι → e, ου → ou, θ → th, χ → x; in Latin, w → o, y/h → i (not in th), 8 → th.
     */
    fun latin(folded: String): String {
        val g = folded.replace("ου", "\u0001").replace("ει", "ι").replace("οι", "ι").replace("αι", "ε")
        val b = StringBuilder()
        for (c in g) b.append(if (c == '\u0001') "ou" else GREEK[c] ?: c.toString())
        return b.toString().replace("8", "th").replace('w', 'o').replace('y', 'i').replace(Regex("(?<![tcp])h"), "i").replace("ch", "x")
    }

    fun parse(line: String): Poi? {
        val c = line.split('\t')
        if (c.size < 4) return null
        val lat = c[1].toDoubleOrNull() ?: return null; val lng = c[2].toDoubleOrNull() ?: return null
        return Poi(c[0], lat, lng, c[3], c.getOrElse(4) { "" }, c.getOrElse(5) { "" }, c.getOrElse(6) { "" }, c.getOrElse(7) { "" },
            c.getOrElse(8) { "" }, c.getOrElse(9) { "" }, c.getOrElse(10) { "" }, c.getOrElse(11) { "" })
    }

    private var cache: Triple<String, Long, List<Poi>>? = null

    /** The places put in by hand (added-places.tsv), drawn on the map always, as the map's own. */
    fun added(root: File): List<Poi> = File(root, ADDED).takeIf { it.isFile }?.readLines()?.mapNotNull(::parse).orEmpty()

    private var tollCache: Pair<Long, List<Poi>>? = null

    /**
     * Only the search list's toll stations, read in about a second: the whole list takes ~20 s on the phone the first time,
     * and a route waited for it (the user's "Finding the way…" that stayed, 23 Oct).
     */
    @Synchronized fun tolls(root: File): List<Poi> {
        val files = MapsFiles.lists(root, "places")
        if (files.isEmpty()) return emptyList()
        val stamp = files.sumOf { it.lastModified() } + files.size
        tollCache?.let { (at, list) -> if (at == stamp) return list }
        val list = files.flatMap { f -> f.bufferedReader().useLines { lines -> lines.filter { it.startsWith("toll\t") }.mapNotNull(::parse).toList() } }
        tollCache = stamp to list
        return list
    }

    /** The search list in [root] with EOPYY's, read once (about 140 000 places) and kept while the files are the same. */
    @Synchronized fun load(root: File): List<Poi> {
        val files = MapsFiles.lists(root, "places") + listOf(File(root, EOPYY), File(root, AMEA), File(root, AMEA_DECISIONS), File(root, ADDED)).filter { it.isFile }
        if (files.isEmpty()) return emptyList()
        val stamp = files.sumOf { it.lastModified() } + files.size
        cache?.let { (path, at, all) -> if (path == root.path && at == stamp) return all }
        val all = files.flatMap { f -> f.bufferedReader().useLines { lines -> lines.mapNotNull(::parse).toList() } }
        cache = Triple(root.path, stamp, all)
        return all
    }

    /**
     * What [query] finds, of [kind] when one is given, nearest to [lat],[lng] (the map's middle) first; a name that starts
     * with the query before one that has it inside, and towns before shops when only words are given.
     */
    fun search(all: List<Poi>, query: String, lat: Double, lng: Double, kind: String? = null, limit: Int = 60, nearby: Boolean = false): List<Poi> {
        val q = fold(query.trim())
        // Nearby with no words or kind: what is around, not the towns (the user, 21 Oct: "βάλε και ένα κουμπί Nearby")
        if (q.isEmpty() && kind == null) return if (!nearby) emptyList() else all.asSequence().filter { !settlement(it.kind) && Math.abs(it.lat - lat) < 0.05 && Math.abs(it.lng - lng) < 0.065 }
            .sortedBy { metresBetween(lat, lng, it.lat, it.lng) }.take(limit).toList()
        val words = q.split(' ').filter { it.isNotEmpty() }
        val greeklish = latin(q).split(' ').filter { it.isNotEmpty() }
        // a kind with words that name a town: that kind in and around it, not with the town in its name (the user, 21 Oct:
        // "agios loukas" + Supermarkets went to Αθήνα, "ψάχνεις την λέξη … την κατηγορία που βρήσκετε")
        if (kind != null && words.isNotEmpty()) {
            val town = all.asSequence().filter { settlement(it.kind) && (it.key.startsWith(q) || it.key.contains(" $q") || words.all { w -> it.key.contains(w) } || greeklish.all { w -> it.key.contains(w) }) }
                .minByOrNull { metresBetween(lat, lng, it.lat, it.lng) }
            if (town != null) return search(all, "", town.lat, town.lng, kind, limit, nearby = true)
        }
        return all.asSequence()
            .filter { (kind == null || it.kind == kind) && (words.all { w -> it.key.contains(w) } || greeklish.all { w -> it.key.contains(w) }) }
            .map { p -> p to metresBetween(lat, lng, p.lat, p.lng) }
            .sortedWith(compareBy({ (p, _) -> if (q.isEmpty() || p.key.startsWith(q) || p.key.contains(" $q")) 0 else 1 },
                { (p, _) -> if (kind == null && q.isNotEmpty() && !settlement(p.kind)) 1 else 0 }, { (_, d) -> d }))
            .take(limit).map { it.first }.toList()
    }

    /** The list's own entry for something seen on the map: the same name, or the nearest, within [within] metres. */
    fun near(all: List<Poi>, lat: Double, lng: Double, name: String, within: Double = 40.0): Poi? {
        val n = fold(name)
        return all.asSequence().filter { Math.abs(it.lat - lat) < 0.001 && Math.abs(it.lng - lng) < 0.0013 }
            .map { it to metresBetween(lat, lng, it.lat, it.lng) }.filter { it.second <= within }
            .sortedWith(compareBy({ (p, _) -> if (n.isNotEmpty() && fold(p.name) == n) 0 else 1 }, { it.second }))
            .firstOrNull()?.first
    }
}

/**
 * Addresses (each map's <id>-addresses.tsv, tools/maps-places.py): the street, number, postcode and town of a spot (the user, 21 Oct:
 * "To click on a spot show the address the number the postcode … Google maps has no adress for my hpuse this has").
 */
internal object MapsAddresses {
    private class All(val path: String, val at: Long, val lat: DoubleArray, val lng: DoubleArray, val rest: Array<String>)
    private var cache: All? = null

    @Synchronized private fun load(root: File): All? {
        val files = MapsFiles.lists(root, "addresses")
        if (files.isEmpty()) return null
        val stamp = files.sumOf { it.lastModified() } + files.size
        cache?.let { if (it.path == root.path && it.at == stamp) return it }
        val lat = ArrayList<Double>(); val lng = ArrayList<Double>(); val rest = ArrayList<String>()
        for (f in files) f.bufferedReader().useLines { lines -> lines.forEach { line ->
            val a = line.indexOf('\t'); val b = line.indexOf('\t', a + 1)
            if (a > 0 && b > a) { lat += line.substring(0, a).toDoubleOrNull() ?: return@forEach; lng += line.substring(a + 1, b).toDoubleOrNull() ?: 0.0; rest += line.substring(b + 1) }
        } }
        return All(root.path, stamp, lat.toDoubleArray(), lng.toDoubleArray(), rest.toTypedArray()).also { cache = it }
    }

    /**
     * Where an address written as Google writes it is ("Φωκίωνος 5, Δράμα 661 32, Ελλάδα"): the same street and number in
     * the address list, in that postcode when it has one; null when not there.
     */
    fun locate(root: File, address: String, pois: List<Poi> = emptyList()): Pair<Double, Double>? {
        val a = load(root) ?: return null
        val first = address.substringBefore(',').trim()
        val m = Regex("""^(.*\S)\s+(\d+[A-Za-zΑ-Ωα-ω]?)$""").find(first) ?: return null
        val street = MapsSearch.fold(m.groupValues[1]); val number = m.groupValues[2].lowercase()
        val rest = address.substringAfter(',', "")
        val post = Regex("""\b(\d{3})\s?(\d{2})\b""").find(rest)?.let { it.groupValues[1] + it.groupValues[2] }
        // the town ("Ξάνθη 671 00" → Ξάνθη): the same street and number are in many towns ("Τσιμισκή 30" went to Δράμα)
        val town = MapsSearch.fold(rest.substringBefore(',').replace(Regex("""\d"""), "").trim())
        val towns = if (town.isEmpty()) emptyList() else pois.filter { MapsSearch.settlement(it.kind) && (MapsSearch.fold(it.name) == town || MapsSearch.fold(it.nameEn) == town) }
        for (i in a.lat.indices) {
            val r = a.rest[i].split('\t')
            if (r.getOrNull(1)?.lowercase() != number || MapsSearch.fold(r[0]) != street) continue
            val rowPost = r.getOrNull(2)?.replace(" ", "").orEmpty(); val rowTown = MapsSearch.fold(r.getOrNull(3).orEmpty())
            if (post != null && rowPost.isNotEmpty() && rowPost != post) continue
            val sure = (post != null && rowPost == post) || (town.isNotEmpty() && rowTown == town) ||
                towns.any { metresBetween(it.lat, it.lng, a.lat[i], a.lng[i]) < 20_000 }
            if (sure) return a.lat[i] to a.lng[i]
        }
        return null
    }

    /**
     * "Οδός 12, 67100 Ξάνθη": the nearest address within 60 m; where no house near has a number (most villages: Άγιος
     * Λουκάς has none), the street — [road], the one tapped, or the nearest in greece-streets.tsv within 80 m — with the
     * nearest postcode within 1.5 km or the town's own (the user, 23 Oct: "address, number, postcode"; "when I tap on the
     * road give the address").
     */
    fun describe(root: File, pois: List<Poi>, lat: Double, lng: Double, road: String? = null): String? {
        val a = load(root)
        var best = -1; var bestD = 60.0; var post = ""; var postD = 1500.0
        if (a != null) for (i in a.lat.indices) {
            if (Math.abs(a.lat[i] - lat) > 0.014 || Math.abs(a.lng[i] - lng) > 0.018) continue
            val d = metresBetween(lat, lng, a.lat[i], a.lng[i])
            if (d < bestD) { bestD = d; best = i }
            if (d < postD) a.rest[i].split('\t').getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { post = it; postD = d }
        }
        val town = pois.asSequence().filter { it.kind in setOf("city", "town", "village", "suburb") && Math.abs(it.lat - lat) < 0.1 && Math.abs(it.lng - lng) < 0.13 }
            .minByOrNull { metresBetween(lat, lng, it.lat, it.lng) }
        val r = if (best >= 0) a!!.rest[best].split('\t') else null
        val street = r?.let { "${it[0]} ${it.getOrElse(1) { "" }}".trim() } ?: road?.trim()?.ifEmpty { null } ?: MapsStreets.nearest(root, lat, lng, 80.0).orEmpty()
        val code = r?.getOrNull(2)?.ifEmpty { null } ?: post.ifEmpty { null } ?: town?.more?.takeIf { POSTCODE.matches(it) }.orEmpty()
        val place = r?.getOrNull(3)?.ifEmpty { null } ?: town?.let { it.name.ifEmpty { it.nameEn } }.orEmpty()
        return listOf(street, "$code $place".trim()).filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { null }
    }

    /** A Greek postcode as OSM writes a town's: "12345" or "123 45". */
    private val POSTCODE = Regex("""\d{3} ?\d{2}""")
}

/**
 * The named roads (each map's <id>-streets.tsv, tools/maps-places.py: a name and the middle of each road piece), for the street of
 * a spot where no house has a number.
 */
internal object MapsStreets {
    private class All(val path: String, val at: Long, val lat: DoubleArray, val lng: DoubleArray, val name: Array<String>)
    private var cache: All? = null

    @Synchronized private fun load(root: File): All? {
        val files = MapsFiles.lists(root, "streets")
        if (files.isEmpty()) return null
        val stamp = files.sumOf { it.lastModified() } + files.size
        cache?.let { if (it.path == root.path && it.at == stamp) return it }
        val lat = ArrayList<Double>(); val lng = ArrayList<Double>(); val name = ArrayList<String>()
        for (f in files) f.bufferedReader().useLines { lines -> lines.forEach { line ->
            val a = line.indexOf('\t'); val b = line.indexOf('\t', a + 1)
            if (a > 0 && b > a) { lat += line.substring(a + 1, b).toDoubleOrNull() ?: return@forEach; lng += line.substring(b + 1).toDoubleOrNull() ?: 0.0; name += line.substring(0, a) }
        } }
        return All(root.path, stamp, lat.toDoubleArray(), lng.toDoubleArray(), name.toTypedArray()).also { cache = it }
    }

    /** The name of the road piece whose middle is nearest [lat],[lng], within [within] metres. */
    fun nearest(root: File, lat: Double, lng: Double, within: Double): String? {
        val s = load(root) ?: return null
        var best: String? = null; var bestD = within
        for (i in s.lat.indices) {
            if (Math.abs(s.lat[i] - lat) > 0.002 || Math.abs(s.lng[i] - lng) > 0.0025) continue
            val d = metresBetween(lat, lng, s.lat[i], s.lng[i])
            if (d < bestD) { bestD = d; best = s.name[i] }
        }
        return best
    }
}

/**
 * Google Maps' saved places, from Google Takeout (the user, 21 Oct: "be able to bring stuff I saved over"): each list's
 * CSV (Saved/<list>.csv: Title, Note, URL…) and the starred places (Maps (your places)/Saved Places.json). A list entry
 * has a name and a link; the link's !3d…!4d… or @lat,lng gives the spot when it is there, else the name is looked up in the
 * offline search list; what cannot be placed is reported to be found by hand.
 */
internal object MapsImport {
    /** [note]: the list's note, or the address Takeout gives a starred or labelled place. */
    data class Entry(val list: String, val name: String, val note: String, val lat: Double?, val lng: Double?)
    data class Result(val places: Int, val lists: Int, val missed: List<String>)

    private val D34 = Regex("""!3d(-?\d+\.\d+)!4d(-?\d+\.\d+)""")
    private val AT = Regex("""@(-?\d+\.\d+),(-?\d+\.\d+)""")
    private val SEARCH = Regex("""/maps/search/(-?\d+\.\d+),\+?(-?\d+\.\d+)""")
    private val Q = Regex("""[?&]q=(-?\d+\.\d+),\+?(-?\d+\.\d+)""")

    fun coords(url: String): Pair<Double, Double>? = (D34.find(url) ?: AT.find(url) ?: SEARCH.find(url) ?: Q.find(url))
        ?.let { m -> m.groupValues[1].toDouble() to m.groupValues[2].toDouble() }?.takeIf { it.first in -90.0..90.0 && it.second in -180.0..180.0 && (it.first != 0.0 || it.second != 0.0) }

    /** One CSV line's cells (quotes and doubled quotes as Google writes them). */
    fun cells(line: String): List<String> {
        val out = ArrayList<String>(); val b = StringBuilder(); var q = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                q && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { b.append('"'); i++ }
                c == '"' -> q = !q
                c == ',' && !q -> { out += b.toString(); b.clear() }
                else -> b.append(c)
            }
            i++
        }
        return out + b.toString()
    }

    fun csv(list: String, text: String): List<Entry> {
        val lines = text.removePrefix("\uFEFF").lines().filter { it.isNotBlank() }
        val head = lines.firstOrNull()?.let(::cells)?.map { it.trim().lowercase() } ?: return emptyList()
        val title = head.indexOf("title"); val note = head.indexOf("note"); val url = head.indexOf("url")
        if (title < 0) return emptyList()
        return lines.drop(1).map(::cells).mapNotNull { c ->
            val name = c.getOrNull(title)?.trim().orEmpty()
            val at = c.getOrNull(url)?.let(::coords)
            if (name.isEmpty() && at == null) null else Entry(list, name, c.getOrNull(note).orEmpty().trim(), at?.first, at?.second)
        }
    }

    /** Takeout's GeoJSON of starred or labelled places. */
    fun geojson(list: String, text: String): List<Entry> = runCatching {
        val fs = JSONObject(text).optJSONArray("features") ?: return emptyList()
        (0 until fs.length()).mapNotNull { i ->
            val f = fs.optJSONObject(i) ?: return@mapNotNull null
            val p = f.optJSONObject("properties") ?: JSONObject()
            val loc = p.optJSONObject("location")
            val c = f.optJSONObject("geometry")?.optJSONArray("coordinates")
            var lat = c?.optDouble(1); var lng = c?.optDouble(0)
            if (lat == null || lng == null || lat.isNaN() || lng.isNaN() || (lat == 0.0 && lng == 0.0)) {
                val at = (p.optString("google_maps_url").ifEmpty { p.optString("Google Maps URL") }).let(::coords); lat = at?.first; lng = at?.second
            }
            // Takeout's starred places without a location keep it in the link: ?q=lat,lng, or ?q=the address
            val q = Regex("""[?&]q=([^&]+)""").find(p.optString("google_maps_url"))?.groupValues?.get(1)
                ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }?.takeIf { lat == null && coords("?q=$it") == null }
            val address = loc?.optString("address").orEmpty().ifEmpty { p.optString("address") }.ifEmpty { q.orEmpty() }
            val name = loc?.optString("name").orEmpty().ifEmpty { p.optString("name").ifEmpty { p.optString("Title") } }.ifEmpty { address }
            if (name.isEmpty() && lat == null) null else Entry(list, name, address, lat, lng)
        }
    }.getOrDefault(emptyList())

    /**
     * The entries of a Takeout .zip, read as it streams by (a Takeout can be gigabytes: YouTube's videos, Maps' photos; only
     * the lists' .csv files and the places' .json files are kept), or of one .csv / .json given by itself ([fileName] names its list).
     */
    fun read(fileName: String, input: java.io.InputStream): List<Entry> =
        if (fileName.endsWith(".zip", true)) read(fileName, ByteArray(0), input) else read(fileName, input.readBytes())

    fun read(fileName: String, bytes: ByteArray, stream: java.io.InputStream? = null): List<Entry> {
        fun one(name: String, data: ByteArray): List<Entry> {
            val base = name.substringAfterLast('/').substringBeforeLast('.')
            val text = data.toString(Charsets.UTF_8)
            return when {
                name.endsWith(".csv", true) -> csv(base, text)
                name.endsWith(".json", true) -> geojson(base, text)
                else -> emptyList()
            }
        }
        if (!fileName.endsWith(".zip", true)) return one(fileName, bytes)
        val out = ArrayList<Entry>()
        java.util.zip.ZipInputStream(stream ?: bytes.inputStream()).use { z ->
            generateSequence { z.nextEntry }.forEach { e ->
                val n = e.name
                // English or Greek Takeout: Saved / Αποθηκευμένα (the lists), Maps (your places) / Χάρτες (τα μέρη σας) (starred),
                // and the labelled places (Labeled places / Μέρη με ετικέτα); reviews and the rest passed over
                val file = n.substringAfterLast('/')
                val wanted = (("/Saved/" in "/$n" || "/Αποθηκευμένα/" in "/$n") && n.endsWith(".csv", true)) ||
                    (("Maps (your places)/" in n || "Χάρτες (τα μέρη σας)/" in n) && n.endsWith(".json", true) && file !in setOf("Reviews.json", "Κριτικές.json")) ||
                    file in setOf("Labeled places.json", "Μέρη με ετικέτα.json")
                if (!e.isDirectory && wanted) out += one(n, z.readBytes())
            }
        }
        return out
    }

    fun emoji(list: String) = when (list.lowercase()) {
        "favourite places", "favorite places", "αγαπημένα μέρη", "αγαπημένα" -> "❤️"
        "want to go", "θέλω να πάω" -> "🚩"
        "starred places", "saved places", "με αστέρι", "αποθηκευμένα μέρη" -> "⭐"
        "travel plans", "ταξιδιωτικά σχέδια" -> "✈️"
        "labeled places", "μέρη με ετικέτα" -> "🏷️"
        else -> "📌"
    }

    /**
     * Into Tetra: a list for each Google list (one of the same name is reused), a place for each entry placed (a place of
     * the same name within 30 m is reused and joins the list), the rest named in [Result.missed].
     */
    fun into(root: File, pois: List<Poi>, entries: List<Entry>): Result {
        var lists = MapsFiles.lists(root)
        val missed = ArrayList<String>(); var count = 0
        val byName = pois.filter { it.name.isNotEmpty() }.groupBy { MapsSearch.fold(it.name) }
        for ((listName, es) in entries.groupBy { it.list }) {
            val list = lists.firstOrNull { it.name.equals(listName, true) } ?: MapsFiles.saveList(root, PlaceList(MapsFiles.newId(), listName, emoji(listName))).also { lists = lists + it }
            for (e in es) {
                // its own spot; else its name, when one place in Greece has it; else its address in the address list
                val at = if (e.lat != null && e.lng != null) e.lat to e.lng
                    else byName[MapsSearch.fold(e.name)]?.takeIf { it.size == 1 }?.first()?.let { it.lat to it.lng }
                    ?: MapsAddresses.locate(root, e.note, pois)
                if (at == null) { missed += "${e.name} ($listName)"; continue }
                // a spot with no name of its own is named by its address
                val name = e.name.ifEmpty { runCatching { MapsAddresses.describe(root, pois, at.first, at.second) }.getOrNull()?.substringBefore(',').orEmpty() }
                val same = MapsFiles.places(root).firstOrNull { MapsSearch.fold(it.name) == MapsSearch.fold(name) && metresBetween(it.lat, it.lng, at.first, at.second) < 30 }
                MapsFiles.save(root, same?.copy(lists = (same.lists + list.id).distinct())
                    ?: Place(MapsFiles.newId(), name.ifEmpty { "Saved place" }, at.first, at.second, lists = listOf(list.id)))
                count++
            }
        }
        return Result(count, entries.map { it.list }.distinct().size, missed)
    }
}

/**
 * The map enriched by hand and shared (the user, 21 Oct: "να προσθέτω στοιχεία στο χάρτη με τα σωστά σύμβολα, όταν βρήσκο
 * θέσεις αμέα ή μαγαζιά … μαζική εισαγωγή και εξαγωγή των προσωπικών ή των δημώσιων στειχείων για διαμηρασμό και
 * εμπλούτευση του χάρτη κάθε χρήστη"). Map additions are lines of added-places.tsv (the search list's form: a kind, so the
 * right symbol and pill); sharing is GeoJSON, which other map apps read too:
 *
 *   {"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[lng,lat]},
 *     "properties":{"tetra":"map","kind":"disabled_parking","name":"…","more":"…"}}, … "tetra":"place", "icon", "lists":["Work"] …]}
 */
internal object MapsShare {
    private fun line(p: Poi) = listOf(p.kind, "%.6f".format(java.util.Locale.ROOT, p.lat), "%.6f".format(java.util.Locale.ROOT, p.lng), p.name, p.nameEn, p.more,
        p.hours, p.phone, p.web, p.wheelchair, p.note, p.names).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') } + "\n"

    fun add(root: File, p: Poi) {
        val f = File(root, MapsSearch.ADDED)
        root.mkdirs(); f.appendText(line(p))
    }

    /** The addition at [p]'s spot and of its kind goes (the others are written back as they were). */
    fun remove(root: File, p: Poi) {
        val f = File(root, MapsSearch.ADDED)
        if (!f.isFile) return
        val keep = f.readLines().filter { l -> MapsSearch.parse(l)?.let { it.kind == p.kind && metresBetween(it.lat, it.lng, p.lat, p.lng) < 2 } != true }
        val part = File(root, ".${f.name}.part")
        part.writeText(keep.joinToString("") { "$it\n" })
        check(part.renameTo(f)) { "Could not change the map's additions." }
    }

    /** My places ([places], with their lists by name) and/or the map's additions ([added]) as GeoJSON. */
    fun export(places: List<Place>, lists: List<PlaceList>, added: List<Poi>): String {
        val fs = org.json.JSONArray()
        fun feature(lat: Double, lng: Double, props: JSONObject) = JSONObject().put("type", "Feature")
            .put("geometry", JSONObject().put("type", "Point").put("coordinates", org.json.JSONArray().put(lng).put(lat))).put("properties", props)
        places.filter { it.id != MapsFiles.PARKING }.forEach { p ->
            fs.put(feature(p.lat, p.lng, JSONObject().put("tetra", "place").put("name", p.name).put("icon", p.icon)
                .put("lists", org.json.JSONArray(lists.filter { it.id in p.lists }.map { it.name })).apply { if (p.label.isNotBlank()) put("label", p.label) }))
        }
        added.forEach { a ->
            fs.put(feature(a.lat, a.lng, JSONObject().put("tetra", "map").put("kind", a.kind).put("name", a.name).put("more", a.more)
                .apply { if (a.hours.isNotEmpty()) put("hours", a.hours); if (a.phone.isNotEmpty()) put("phone", a.phone); if (a.web.isNotEmpty()) put("web", a.web) }))
        }
        return JSONObject().put("type", "FeatureCollection").put("features", fs).toString(1) + "\n"
    }

    data class Imported(val places: Int, val added: Int, val skipped: Int)

    /**
     * A shared file into Tetra: "map" features become map additions (one already there, of its kind within 10 m, is not
     * added again), the rest my places (one of the same name within 30 m joins the lists instead); GeoJSON from elsewhere
     * (no "tetra") comes in as places, named by its name or title.
     */
    fun import(root: File, text: String): Imported {
        val fs = JSONObject(text).optJSONArray("features") ?: return Imported(0, 0, 0)
        var places = 0; var added = 0; var skipped = 0
        var lists = MapsFiles.lists(root)
        val have = MapsSearch.added(root).toMutableList()
        for (i in 0 until fs.length()) {
            val f = fs.optJSONObject(i) ?: continue
            val c = f.optJSONObject("geometry")?.takeIf { it.optString("type") == "Point" }?.optJSONArray("coordinates")
            val lng = c?.optDouble(0) ?: Double.NaN; val lat = c?.optDouble(1) ?: Double.NaN
            if (lat.isNaN() || lng.isNaN() || lat !in -90.0..90.0 || lng !in -180.0..180.0) { skipped++; continue }
            val p = f.optJSONObject("properties") ?: JSONObject()
            val name = p.optString("name").ifEmpty { p.optString("title").ifEmpty { p.optString("Title") } }
            if (p.optString("tetra") == "map") {
                val kind = p.optString("kind").ifEmpty { "pin" }
                if (have.any { it.kind == kind && metresBetween(it.lat, it.lng, lat, lng) < 10 }) { skipped++; continue }
                val poi = Poi(kind, lat, lng, name, more = p.optString("more"), hours = p.optString("hours"), phone = p.optString("phone"), web = p.optString("web"))
                add(root, poi); have += poi; added++
            } else {
                val listIds = (p.optJSONArray("lists")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()).filter { it.isNotBlank() }.map { ln ->
                    (lists.firstOrNull { it.name.equals(ln, true) } ?: MapsFiles.saveList(root, PlaceList(MapsFiles.newId(), ln, MapsImport.emoji(ln))).also { lists = lists + it }).id
                }
                val same = MapsFiles.places(root).firstOrNull { MapsSearch.fold(it.name) == MapsSearch.fold(name) && metresBetween(it.lat, it.lng, lat, lng) < 30 }
                if (same != null) { if (!same.lists.containsAll(listIds)) MapsFiles.save(root, same.copy(lists = (same.lists + listIds).distinct())); skipped++; continue }
                MapsFiles.save(root, Place(MapsFiles.newId(), name.ifEmpty { "Place" }, lat, lng, p.optString("icon").takeIf { it in Place.ICONS } ?: "pin",
                    lists = listIds, label = p.optString("label")))
                places++
            }
        }
        return Imported(places, added, skipped)
    }
}

/**
 * Standing still or moving, from the phone's accelerometer, for a long tunnel (the user, 21 Oct: "μπορεί για 5 λεπτά να μην
 * έχω GPS … μπορεί να χρησιμοποιεί τους αισθητήρες του κινητού"): a moving car shakes the phone with the road; a standing
 * one, engine running or not, hardly at all. The spread of the acceleration's size over the last two seconds tells them
 * apart (a speed cannot be had from the sensors alone: the error grows too fast).
 */
internal class StillDetector(private val windowNanos: Long = 2_000_000_000L, private val threshold: Double = 0.12) {
    private val times = ArrayDeque<Long>(); private val sizes = ArrayDeque<Double>()

    /** One reading of linear acceleration (gravity taken out), m/s², at [nanos]. */
    fun add(nanos: Long, x: Float, y: Float, z: Float) {
        times.addLast(nanos); sizes.addLast(Math.sqrt((x * x + y * y + z * z).toDouble()))
        while (times.isNotEmpty() && nanos - times.first() > windowNanos) { times.removeFirst(); sizes.removeFirst() }
    }

    /** Standing still: a full window of readings that hardly move; null while there are too few to tell. */
    fun still(): Boolean? {
        if (sizes.size < 20 || times.last() - times.first() < windowNanos * 3 / 4) return null
        val mean = sizes.average()
        return Math.sqrt(sizes.sumOf { (it - mean) * (it - mean) } / sizes.size) < threshold
    }
}

/**
 * ΑμεΑ spots in the municipalities' decisions that could not be placed from their address (greece-amea-check.tsv, made by
 * tools/maps-diavgeia.py), for the user to place by hand (the user, 21 Oct: "add what are you are serten of and I will
 * check (give links) the rest"). One placed or skipped is written to amea-checked.txt (its id), so every device leaves it.
 */
internal data class AmeaCheck(val id: String, val lat: Double, val lng: Double, val municipality: String, val address: String,
    val subject: String, val link: String, val date: String)

internal object MapsCheck {
    const val FILE = "greece-amea-check.tsv"
    const val DONE = "amea-checked.txt"

    fun load(root: File): List<AmeaCheck> {
        val f = File(root, FILE)
        if (!f.isFile) return emptyList()
        val done = File(root, DONE).takeIf { it.isFile }?.readLines()?.map { it.trim() }?.toSet().orEmpty()
        return f.readLines().mapNotNull { line ->
            val c = line.split('\t')
            if (c.size < 8) return@mapNotNull null
            val lat = c[1].toDoubleOrNull() ?: return@mapNotNull null; val lng = c[2].toDoubleOrNull() ?: return@mapNotNull null
            AmeaCheck(c[0], lat, lng, c[3], c[4], c[5], c[6], c[7])
        }.filter { it.id !in done }
    }

    fun done(root: File, id: String) { root.mkdirs(); File(root, DONE).appendText("$id\n") }
}
